> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 07 · 财务：币种、汇率、结算、总账

owner 域：`finance`。**全项目唯一不允许出现浮点的域。**

## 三条铁律

1. **禁止 `float` / `double` / `real`**。金额一律 `NUMERIC`，汇率一律 `NUMERIC(19,8)`
2. **小数位由 `currency.minor_unit` 决定**。JPY 是 0 位、KWD 是 3 位。「乘 100 转分」的硬编码在 JPY 上会差 100 倍
3. **历史账不随汇率重算**。每笔账在过账时把当时的汇率快照写进行里，之后改汇率表不影响已入账凭证

## 币种

```sql
CREATE TABLE currency (
  code        CHAR(3)     PRIMARY KEY,     -- ISO-4217
  name        TEXT        NOT NULL,
  minor_unit  SMALLINT    NOT NULL,        -- 小数位数：USD=2, JPY=0, KWD=3
  symbol      TEXT,
  status      TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT ck_currency_minor_unit CHECK (minor_unit BETWEEN 0 AND 4)
);
-- 全局静态表，不带 tenant_id，不启用 RLS
```

`minor_unit` 是**数据而不是代码常量**。多币种 ERP 最常见的生产事故之一就是硬编码 2 位小数，遇上日元直接错三个数量级。

### 汇率快照

```sql
CREATE TABLE fx_rate_snapshot (
  id               UUID        PRIMARY KEY,
  tenant_id        UUID        NOT NULL REFERENCES tenant (id),
  base_currency    CHAR(3)     NOT NULL REFERENCES currency (code),
  quote_currency   CHAR(3)     NOT NULL REFERENCES currency (code),
  rate             NUMERIC(19,8) NOT NULL,
  as_of            timestamptz NOT NULL,     -- 汇率的生效时点
  source           TEXT        NOT NULL,     -- 'ECB' | 'MANUAL' | 'PLATFORM'
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_fx_base  FOREIGN KEY (base_currency)  REFERENCES currency (code),
  CONSTRAINT fk_fx_quote FOREIGN KEY (quote_currency) REFERENCES currency (code),
  CONSTRAINT uq_fx_snapshot UNIQUE (tenant_id, base_currency, quote_currency, as_of),
  CONSTRAINT ck_fx_rate  CHECK (rate > 0),
  CONSTRAINT ck_fx_pair  CHECK (base_currency <> quote_currency)
);
```

**append-only，不 UPDATE。** 汇率是时序数据，改历史行等于篡改历史账。取汇率用「取 `as_of` 最近且 `<= 业务时点` 的那条」，而不是取最新。

## 结算

平台账期结算与销售订单是多对一：一个结算单覆盖某周期内的多个订单。

```sql
CREATE TABLE settlement (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  shop_id        UUID        NOT NULL,
  settlement_no  TEXT        NOT NULL,
  period_start   date        NOT NULL,
  period_end     date        NOT NULL,
  currency       CHAR(3)     NOT NULL,
  gross_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,   -- 销售总额
  fee_total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  refund_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
  adjustment_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  net_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,   -- 实际到账
  payout_date    date,
  status         TEXT        NOT NULL DEFAULT 'DRAFT',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_settlement_shop FOREIGN KEY (tenant_id, shop_id) REFERENCES shop (tenant_id, id),
  CONSTRAINT uq_settlement_tenant_no UNIQUE (tenant_id, settlement_no),
  CONSTRAINT uq_settlement_shop_period UNIQUE (tenant_id, shop_id, period_start, period_end),
  CONSTRAINT uq_settlement_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT ck_settlement_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'PAID', 'DISPUTED', 'CLOSED')),
  CONSTRAINT ck_settlement_period CHECK (period_end >= period_start),
  CONSTRAINT ck_settlement_net CHECK (
    net_amount = gross_amount - fee_total_amount - refund_amount + adjustment_amount
  )
);
```

`net_amount` 同样有算式 CHECK —— 平台给的到账金额和分项对不上时直接拒绝入库，而不是留一条永远对不上的账。

### 结算费用明细

```sql
CREATE TABLE settlement_fee (
  id             UUID        PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES tenant (id),
  settlement_id  UUID        NOT NULL,
  fee_code       TEXT        NOT NULL,   -- 'COMMISSION' | 'FBA_FULFILLMENT' | 'STORAGE' | 'RETURN_FEE' | 'AD'
  fee_name       TEXT        NOT NULL,
  amount         NUMERIC(19,4) NOT NULL,  -- 费用在平台侧通常是负数（从货款扣）
  is_deductible BOOLEAN     NOT NULL DEFAULT TRUE,   -- 是否从货款扣减
  raw_payload    JSONB,
  CONSTRAINT fk_fee_settlement FOREIGN KEY (tenant_id, settlement_id) REFERENCES settlement (tenant_id, id)
);
CREATE INDEX idx_fee_settlement ON settlement_fee (tenant_id, settlement_id);
```

费用独立成表而不是塞进 `settlement` 的列：平台费用项**每家每期都不一样**（Shopify、亚马逊、TikTok Shop 完全不同，且会新增），做成列意味着每接一个平台就要改表。`fee_code` + `fee_name` 允许平台新增费用项时无需迁移。

### 结算单明细（订单级）

```sql
CREATE TABLE settlement_line (
  id               UUID        PRIMARY KEY,
  tenant_id        UUID        NOT NULL REFERENCES tenant (id),
  settlement_id    UUID        NOT NULL,
  order_id         UUID        NOT NULL,
  order_amount     NUMERIC(19,4) NOT NULL,      -- 原币金额
  fee_amount       NUMERIC(19,4) NOT NULL DEFAULT 0,
  refund_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
  net_amount       NUMERIC(19,4) NOT NULL,
  -- ↓ 折算本位币，汇率取过账时点
  base_currency    CHAR(3)        NOT NULL,
  fx_rate          NUMERIC(19,8) NOT NULL,
  fx_rate_as_of    timestamptz    NOT NULL,
  base_amount      NUMERIC(19,4)  NOT NULL,
  CONSTRAINT fk_settle_line_settlement FOREIGN KEY (tenant_id, settlement_id) REFERENCES settlement (tenant_id, id),
  CONSTRAINT fk_settle_line_order      FOREIGN KEY (tenant_id, order_id)      REFERENCES sales_order (tenant_id, id),
  CONSTRAINT uq_settlement_line UNIQUE (tenant_id, settlement_id, order_id),
  CONSTRAINT ck_settle_line_net  CHECK (net_amount = order_amount - fee_amount - refund_amount),
  CONSTRAINT ck_settle_line_base CHECK (rate > 0)
);
```

**`fx_rate` 与 `fx_rate_as_of` 直接存在行上**，不在查询时 JOIN `fx_rate_snapshot` 现算。这是「历史金额不随汇率变化」的实现方式 —— 账已经落了，汇率是当时的事实。改动汇率表不影响这里一行。

## 收付款

```sql
CREATE TABLE payment (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  order_id      UUID,
  settlement_id UUID,
  payment_type  TEXT        NOT NULL,     -- 'INBOUND' | 'OUTBOUND'
  method        TEXT        NOT NULL,     -- 'CARD' | 'PAYPAL' | 'BANK_TRANSFER' | 'OFFLINE'
  amount        NUMERIC(19,4) NOT NULL,
  currency      CHAR(3)     NOT NULL,
  paid_at       timestamptz NOT NULL,
  external_txn_id TEXT,                    -- 支付平台流水号
  status        TEXT        NOT NULL DEFAULT 'SUCCEEDED',
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_payment_order      FOREIGN KEY (tenant_id, order_id)      REFERENCES sales_order (tenant_id, id),
  CONSTRAINT fk_payment_settlement FOREIGN KEY (tenant_id, settlement_id) REFERENCES settlement (tenant_id, id),
  CONSTRAINT uq_payment_tenant_id  UNIQUE (tenant_id, id),
  CONSTRAINT uq_payment_external   UNIQUE (tenant_id, method, external_txn_id),
  CONSTRAINT ck_payment_type   CHECK (payment_type IN ('INBOUND', 'OUTBOUND')),
  CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
  CONSTRAINT ck_payment_amount CHECK (amount > 0),
  -- 订单付款与结算收款，至少要挂一个
  CONSTRAINT ck_payment_target CHECK (order_id IS NOT NULL OR settlement_id IS NOT NULL)
);
```

`uq_payment_external` 让支付平台重复回调是幂等的 —— 这是支付场景最关键的一条约束，没有它会重复入账。

## 双分录总账

> **设计已定，实现排在 backlog。** 前面的结算表足以支撑「这笔单赚了多少」，但支撑不了完整会计科目与资产负债，所以总账的表先定下来，避免以后加表时破坏既有单据引用。

```sql
CREATE TABLE ledger_account (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  code         TEXT        NOT NULL,   -- '1000' 现金 / '2000' 应付 / '4000' 销售收入
  name         TEXT        NOT NULL,
  account_type TEXT        NOT NULL,   -- 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE'
  normal_balance TEXT      NOT NULL,   -- 'DEBIT' | 'CREDIT'
  parent_id    UUID,
  status       TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_account_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_account_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);

CREATE TABLE journal_entry (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  entry_no     TEXT        NOT NULL,
  entry_date   date        NOT NULL,
  source_type  TEXT        NOT NULL,    -- 'SALE' | 'PURCHASE' | 'SETTLEMENT' | 'PAYMENT' | 'ADJUSTMENT'
  source_id    UUID,                    -- 来源单据 ID
  memo         TEXT,
  status       TEXT        NOT NULL DEFAULT 'DRAFT',   -- 'DRAFT' | 'POSTED' | 'REVERSED'
  currency     CHAR(3)     NOT NULL,
  created_by   UUID        NOT NULL REFERENCES app_user (id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  posted_at    timestamptz,
  CONSTRAINT fk_je_tenant FOREIGN KEY (tenant_id, source_id) REFERENCES sales_order (tenant_id, id),  -- 见下注
  CONSTRAINT uq_journal_tenant_no   UNIQUE (tenant_id, entry_no),
  CONSTRAINT uq_journal_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_journal_source CHECK (source_type IN ('SALE', 'PURCHASE', 'SETTLEMENT', 'PAYMENT', 'ADJUSTMENT')),
  CONSTRAINT ck_journal_status CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
  CONSTRAINT ck_journal_posted CHECK ((status = 'POSTED' AND posted_at IS NOT NULL) OR status <> 'POSTED')
);

CREATE TABLE journal_entry_line (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  journal_entry_id UUID      NOT NULL,
  line_no       INT         NOT NULL,
  account_id    UUID        NOT NULL,
  direction     TEXT        NOT NULL,   -- 'DEBIT' | 'CREDIT'
  amount        NUMERIC(19,4) NOT NULL,
  base_amount   NUMERIC(19,4) NOT NULL,  -- 本位币金额
  CONSTRAINT fk_jel_entry   FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entry (tenant_id, id),
  CONSTRAINT fk_jel_account FOREIGN KEY (tenant_id, account_id)      REFERENCES ledger_account (tenant_id, id),
  CONSTRAINT uq_journal_line_no UNIQUE (tenant_id, journal_entry_id, line_no),
  CONSTRAINT ck_journal_line_dir CHECK (direction IN ('DEBIT', 'CREDIT')),
  CONSTRAINT ck_journal_line_amt CHECK (amount > 0)
);
```

> `journal_entry.source_id` **不能建外键** —— 它的取值来自 5 个不同的源表（`sales_order` / `purchase_order` / `settlement` / `payment` / 调整单），而外键只能指向一张表。这里接受它是弱引用，靠 `source_type` 判别，并加一条巡检 SQL 定期校验孤儿（记入 backlog）。这是刻意的取舍：**宁可一处弱引用加巡检，也不要一个假的 `source` 父表** —— 建一张空壳 `business_document` 表只为了让外键好看，会污染整个模型。

### 借贷平衡：DB 级保证

`CHECK` 约束只能看单行，做不到「所有分录行合计借 = 合计贷」。用**延迟约束触发器**在提交时检查：

```sql
CREATE OR REPLACE FUNCTION assert_journal_balanced() RETURNS TRIGGER AS $$
DECLARE
  d NUMERIC; c NUMERIC; n INTEGER;
BEGIN
  SELECT COALESCE(SUM(base_amount) FILTER (WHERE direction='DEBIT'), 0),
         COALESCE(SUM(base_amount) FILTER (WHERE direction='CREDIT'), 0),
         COUNT(*)
    INTO d, c, n
    FROM journal_entry_line
   WHERE tenant_id = NEW.tenant_id AND journal_entry_id = NEW.journal_entry_id;

  IF n > 0 AND d <> c THEN
    RAISE EXCEPTION 'journal % 不平: 借 % != 贷 %', NEW.journal_entry_id, d, c;
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_entry_must_balance
  AFTER INSERT OR UPDATE OR DELETE ON journal_entry_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();
```

`DEFERRABLE INITIALLY DEFERRED` 是关键：分录行是先插完最后才检查，所以约束在 `COMMIT` 时才触发。这让「先插两行（借方、贷方）再提交」成为可能，而普通触发器会在插第一行时就报错。

这样一来，**不平的凭证在数据库层面就存不进去**，不依赖应用层记得检查。

## 跨域暴露

| 调用方   | 调什么                                                                              |
| -------- | ----------------------------------------------------------------------------------- |
| order    | `SettlementService.appendableOrders(shopId, period)`                                |
| order    | `FinanceService.recordOrderRevenue(orderId)` → 写结算明细 + 总账凭证                |
| purchase | `FinanceService.recordPayable(purchaseOrderId)`                                     |
| —        | `FxService.convert(amount, from, to, asOf): BigDecimal` —— **唯一**做币种换算的入口 |

## 关键不变量

1. 全域无浮点，金额 `NUMERIC(19,4)`、汇率 `NUMERIC(19,8)`
2. **金额与币种永远成对出现**
3. `minor_unit` 由 `currency` 表决定，**不在代码里硬编码 2**
4. `fx_rate_snapshot` append-only；`settlement_line.fx_rate` 是行内快照，不实时 JOIN
5. `net_amount` 恒等于分项算式（`ck_settlement_net` / `ck_settle_line_net`）
6. 借贷不平衡的凭证**无法提交**（延迟约束触发器）
7. `payment.external_txn_id` 唯一 → 重复回调不重复入账
