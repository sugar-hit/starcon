> **历史设计稿，已由 [V2 微服务数据模型](v2/README.md) 取代。** 下文跨域外键、事务与服务划分不可直接用于新迁移。

# 04 · 库存

owner 域：`inventory`。全项目并发风险最高的一域 —— 超卖是不可逆事故。

## 核心决策：余额权威在仓库级，不在库位级

`inventory_balance` 的键是 `(tenant, sku, warehouse)`，**不带 `location_id`**。`location_id` 只出现在 `inventory_ledger`（记录物理动作发生在哪里）。

理由：履约流程里可用量的决策单位是**仓库**，不是库位 —— 一张订单从某个仓库发货，而不是从某个货架格。权威放在仓库级之后，原子预占就是**单行条件更新**：

```sql
UPDATE inventory_balance
   SET reserved = reserved + :qty, updated_at = now()
 WHERE tenant_id = :t AND sku_id = :s AND warehouse_id = :w
   AND available >= :qty
RETURNING on_hand, reserved;
```

如果把库位也放进键里，仓库级可用量就得 `SUM`，而 `SUM` 之后再 `UPDATE` 就重新引入了「先读余量再无锁写回」的竞态；要修就得对多行加锁并规定加锁顺序，那是一整套更复杂的并发协议。

若将来某租户需要货位级拣货，新增 `location_balance` 作为**派生表**，与仓库级余额在同一事务内维护，并加一条集成测试断言 `SUM(location_balance) == inventory_balance`。

## 表

### 仓库与库位

```sql
CREATE TABLE warehouse (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  code          TEXT        NOT NULL,
  name          TEXT        NOT NULL,
  country_code  CHAR(2)     NOT NULL,
  timezone      TEXT        NOT NULL,
  address_line1 TEXT        NOT NULL,
  address_line2 TEXT,
  city          TEXT        NOT NULL,
  postal_code   TEXT,
  is_default    BOOLEAN     NOT NULL DEFAULT FALSE,
  status        TEXT        NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_warehouse_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_warehouse_tenant_id   UNIQUE (tenant_id, id),
  CONSTRAINT ck_warehouse_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
);

CREATE TABLE location (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  warehouse_id UUID        NOT NULL,
  code         TEXT        NOT NULL,
  name         TEXT        NOT NULL,
  kind         TEXT        NOT NULL,   -- 'BULK' | 'BIN' | 'STAGING' | 'DOCK' | 'QUARANTINE'
  status       TEXT        NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_location_warehouse FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CONSTRAINT uq_location_warehouse_code UNIQUE (tenant_id, warehouse_id, code),
  CONSTRAINT uq_location_tenant_id     UNIQUE (tenant_id, id),
  CONSTRAINT ck_location_kind CHECK (kind IN ('BULK', 'BIN', 'STAGING', 'DOCK', 'QUARANTINE'))
);

-- 每个租户至多一个默认发货仓
CREATE UNIQUE INDEX uq_warehouse_default
  ON warehouse (tenant_id) WHERE is_default AND status = 'ACTIVE';
```

`QUARANTINE`（隔离区）是独立库位类型 —— 质检未过 / 客诉退回的货要能被查到、且**不能被正常订单预占**。少了这个类型，退货就只能进正常库位，然后被发走。

地址字段内联在 `warehouse` 而不是指向 `address` 表：仓库地址是仓库自身属性，不被多方共享，且订单出库时会另存快照。

### 库存流水（append-only，余额的真相来源）

```sql
CREATE TABLE inventory_ledger (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  sku_id        UUID        NOT NULL,
  warehouse_id  UUID        NOT NULL,
  location_id   UUID,
  direction     TEXT        NOT NULL,   -- 见下表
  quantity      NUMERIC(19,4) NOT NULL,  -- 恒为正数，方向由 direction 表达
  unit_cost_amount   NUMERIC(19,4),
  unit_cost_currency CHAR(3),
  ref_type      TEXT        NOT NULL,   -- 'PURCHASE_RECEIPT' | 'SALE_SHIPMENT' | 'ADJUSTMENT' | 'TRANSFER' | 'RETURN'
  ref_id        UUID        NOT NULL,   -- 来源单据行/单据 ID
  ref_line_id   UUID,
  occurred_at   timestamptz NOT NULL,
  created_by    UUID        NOT NULL REFERENCES app_user (id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_ledger_sku   FOREIGN KEY (tenant_id, sku_id)       REFERENCES sku (tenant_id, id),
  CONSTRAINT fk_ledger_wh    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CONSTRAINT fk_ledger_loc   FOREIGN KEY (tenant_id, location_id)  REFERENCES location (tenant_id, id),
  CONSTRAINT ck_ledger_direction CHECK (direction IN (
      'IN', 'OUT', 'RESERVE', 'RELEASE', 'ADJUST_IN', 'ADJUST_OUT'
  )),
  CONSTRAINT ck_ledger_qty  CHECK (quantity > 0),
  CONSTRAINT ck_ledger_cost CHECK (
    (unit_cost_amount IS NULL AND unit_cost_currency IS NULL) OR
    (unit_cost_amount IS NOT NULL AND unit_cost_currency IS NOT NULL)
  )
);

CREATE INDEX idx_ledger_sku_wh  ON inventory_ledger (tenant_id, sku_id, warehouse_id, occurred_at DESC);
CREATE INDEX idx_ledger_ref     ON inventory_ledger (tenant_id, ref_type, ref_id);
```

设计要点：

- **`quantity` 恒为正**，方向由 `direction` 表达。反过来（正数入库、负数出库）会让「求和算余额」的逻辑出现符号错误，而且 `CHECK (quantity > 0)` 拦不住「出库写成 +5」
- **append-only**：不 UPDATE 不 DELETE。要改就写一条冲正流水
- `ref_type` + `ref_id` 是幂等锚点 —— 同一 `ref` 重复投递时靠它去重
- `unit_cost_amount` 与币种**同时为空或同时有值**，CHECK 保证。成本可空是因为盘亏调整没有成本

### 库存余额（投影，权威可用量）

```sql
CREATE TABLE inventory_balance (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL REFERENCES tenant (id),
  sku_id       UUID        NOT NULL,
  warehouse_id UUID        NOT NULL,
  on_hand      NUMERIC(19,4) NOT NULL DEFAULT 0,
  reserved     NUMERIC(19,4) NOT NULL DEFAULT 0,
  available    NUMERIC(19,4) GENERATED ALWAYS AS (on_hand - reserved) STORED,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_balance_sku FOREIGN KEY (tenant_id, sku_id)       REFERENCES sku (tenant_id, id),
  CONSTRAINT fk_balance_wh  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CONSTRAINT uq_balance_sku_wh UNIQUE (tenant_id, sku_id, warehouse_id),
  CONSTRAINT ck_balance_on_hand  CHECK (on_hand  >= 0),
  CONSTRAINT ck_balance_reserved CHECK (reserved >= 0),
  CONSTRAINT ck_balance_available CHECK (available >= 0)   -- ← 超卖的 DB 级兜底
);
```

`available` 用**生成列**而不是应用层算好后写进去：生成列不可能与 `on_hand`/`reserved` 漂移。如果 `available` 是普通列，任何一条忘记同步的 UPDATE 都会让它长期失真，而且很难被发现。

`ck_balance_available CHECK (available >= 0)` 是最后一道防线 —— 即使应用层漏了判断，DB 也会拒绝把余额预占成负数。

### 预占

```sql
CREATE TABLE inventory_reservation (
  id            UUID        PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenant (id),
  sku_id        UUID        NOT NULL,
  warehouse_id  UUID        NOT NULL,
  quantity      NUMERIC(19,4) NOT NULL,
  status        TEXT        NOT NULL DEFAULT 'HELD',
  ref_type      TEXT        NOT NULL,   -- 'ORDER_LINE' | 'TRANSFER_LINE' | 'RETURN_LINE'
  ref_id        UUID        NOT NULL,
  ref_line_id   UUID,
  expires_at    timestamptz,
  released_at   timestamptz,
  created_by    UUID        NOT NULL REFERENCES app_user (id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_res_sku FOREIGN KEY (tenant_id, sku_id)       REFERENCES sku (tenant_id, id),
  CONSTRAINT fk_res_wh  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse (tenant_id, id),
  CONSTRAINT uq_reservation_ref UNIQUE (tenant_id, ref_type, ref_id, sku_id, warehouse_id),
  CONSTRAINT ck_reservation_qty    CHECK (quantity > 0),
  CONSTRAINT ck_reservation_status CHECK (status IN ('HELD', 'RELEASED', 'CONSUMED', 'EXPIRED'))
);

CREATE INDEX idx_reservation_expiry ON inventory_reservation (expires_at)
  WHERE status = 'HELD';
```

`uq_reservation_ref` 让「同一订单行的同一 SKU 在同一仓库」只有一条预占 —— 订单重试、webhook 重投都不会重复占用库存。

`expires_at` + 部分索引支撑「超时未发货自动释放」的任务。

## 关键流程

### 预占（同一事务内）

```
1. SELECT * FROM inventory_balance
    WHERE tenant_id=? AND sku_id=? AND warehouse_id=? FOR UPDATE   -- 或直接条件更新
2. UPDATE inventory_balance SET reserved = reserved + :qty
    WHERE ... AND available >= :qty
   → 影响行数 0 则判定库存不足，整个事务回滚
3. INSERT inventory_ledger (direction='RESERVE', quantity=:qty, ref_type='ORDER_LINE', ref_id=:lineId)
4. INSERT inventory_reservation (status='HELD')
5. 提交
```

流水记 `RESERVE` 但**不改变 `on_hand`** —— 预占不改变物理库存，只改变占用。

### 释放 / 消耗

| 动作     | `inventory_balance`                 | `inventory_ledger.direction` | `reservation.status` |
| -------- | ----------------------------------- | ---------------------------- | -------------------- |
| 释放     | `reserved -= qty`                   | `RELEASE`                    | `HELD → RELEASED`    |
| 出库消耗 | `on_hand -= qty`，`reserved -= qty` | `OUT`                        | `HELD → CONSUMED`    |

两者的区别是**是否同时减 `on_hand`**。写错会导致「货发出去了但账面还有」，所以这两个动作必须由同一个 service 方法提供，不允许各写各的 SQL。

### 状态转换守卫

```sql
UPDATE inventory_reservation
   SET status = 'RELEASED', released_at = now(), updated_at = now()
 WHERE id = :id AND status = 'HELD';    -- 条件写在 WHERE 里
```

影响行数 0 = 已被别人处理过，直接返回「幂等成功」而不是报错。这样重复调用 `release` 是安全的。

## 关键不变量

1. `on_hand >= 0`、`reserved >= 0`、`available >= 0` —— 由 CHECK 强制，不靠应用层
2. **`inventory_ledger` 是只追加的**，余额是它的投影。两者**必须同事务写入**
3. 同一 `(tenant, ref_type, ref_id, sku, warehouse)` 至多一条 `HELD` 预占
4. 任何扣减都是**条件更新**，禁止「先 `SELECT` 余量、再无条件 `UPDATE`」
5. `QUARANTINE` 库位的货**不参与**可预占可用量（由 service 层过滤，不进余额计算）
6. 跨仓库调拨 = 一次 `OUT` + 一次 `IN`，在**同一事务**内完成，不是两个独立操作
