-- ===========================================================================
-- 12.6.2.1 发票管理 —— finance 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/12-finance.md 第 296-301 行）：
--   ### 12.6.2.1 发票管理
--   - 职责：管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、
--           编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。
--   - 输入：订单与结算数据；发票模板；编号规则；作废申请。
--   - 输出：发票与贷项单 + 编号序列 + 状态记录。
--   - 关系：上游 = 9 订单、12.3.1；下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。
--           **各国发票要求差异大**，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 与相邻模块的职责切分（**不重复建表、零 ALTER 既有表**）
-- ---------------------------------------------------------------------------
--   * **12.6.1.1 税率配置（V361）**：税率 / 税码 / 辖区码表的**唯一真值源**。
--     本模块**不复制**税率表、不建指向它的外键；落库前经
--     `GET /finance/tax-rates/resolve` 与 `GET /finance/tax-rates/jurisdictions`
--     （同服务、同库，服务层直连）**实时校验**。
--   * **12.5.2.1 凭证（V357）**：发票**不写凭证**。本模块只产出发票事实；
--     要过账走 12.5.2.1 的真实接口，不重复实现凭证过账。
--   * **11.4 售后**：退货开票调整 = **红冲**。红冲金额**从原发票逐行带出**（本文件第
--     四节的镜像守卫把「不许手填」钉在 DB 层）。
--   * **9 订单 / 12.3.1 结算**：**只读**。订单侧经 trade 的授权只读 API 读
--     「开票需求」（`b2b_receivable.invoice_state='REQUESTED'`）；结算侧同库只读。
--     本模块**不写**订单表、**不写**结算表。
--
--   ⇒ 本文件**只 CREATE 自己的六张新表**，表名前缀 `finance_invoice_*`，
--     与 `finance_tax_*` / `finance_voucher*` / `finance_settlement*` **彼此隔离**。
--     **零 ALTER、零 DROP、零 ADD COLUMN 到别人的表**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 金额：BigDecimal + NUMERIC，禁浮点
-- ---------------------------------------------------------------------------
--   * 行金额 `numeric(19,4)`，数量 `numeric(19,6)`，税率 `numeric(9,6)`。
--   * `line_amount` / `tax_amount` 是**生成列**（由数量 × 单价 × 税率算出），
--     应用层算错时它们不会跟着错 —— **没有第二个写入口**。
--   * 单头 `subtotal_amount` / `tax_amount` / `total_amount` 由**触发器**从明细求和派生。
--   * 币种是 `char(3)`，且**一张发票只有一个币种**；多币种**绝不相加**（见第五节）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 编号序列：并发安全（**不许「先查 max 再 +1」**）
-- ---------------------------------------------------------------------------
-- `finance_invoice_sequence` 用 `UNIQUE(tenant_id, sequence_key, period_code)` +
-- `INSERT ... ON CONFLICT DO UPDATE ... RETURNING next_value` 取号：
-- 取号是**一条原子语句**，两个并发请求不会拿到同一个号。
--
--   * `sequence_key` = 模板 × 编号范围（租户/国家/平台）× 期间口径；
--   * `period_code`  = 'NONE' / 'YYYY' / 'YYYY-MM'（由模板的编号规则决定）。
--
-- 🔴 **编号只在「开具」时分配**：草稿没有正式编号（`invoice_number` 为 NULL）。
-- 草稿不是对外凭据，提前占号会让作废的草稿在号段里留下空洞。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 状态机：显式转换规则（草稿 / 开具 / 作废 / 红冲）
-- ---------------------------------------------------------------------------
--   DRAFT ──开具──▶ ISSUED ──作废──▶ VOID
--                      │
--                      └──红冲──▶ REVERSED
--   DRAFT ──作废──▶ VOID（作废从未开具的草稿）
--
--   * 转换合法性由 **DB 触发器**钉死（`finance_invoice_guard`）；
--   * `VOID` / `REVERSED` 是**终态**，不可再转；
--   * **作废与红冲必须留痕**：谁（`voided_by` / `reversed_by`）、何时
--     （`voided_at` / `reversed_at`）、为什么（`reason`，作废时**必填**）；
--   * 每次状态变化写一条 `finance_invoice_event`（append-only，运行角色
--     UPDATE/DELETE 已收回）。
--
-- 🔴 **红冲必须成对**：贷项通知单**必须**指向一张已开具的原发票
--   （`original_invoice_id` NOT NULL + 触发器校验），**不允许孤立红冲**；
--   同一张原发票**只允许一张**未终结的贷项通知单。
--
-- 🔴 **红冲金额从原发票逐行带出，不许手填**：贷项行的数量 / 单价 / 税码 / 税率 /
--   金额**必须逐项等于**原发票对应行的镜像（数量取负表示红冲方向），
--   由 `finance_invoice_line_guard` 逐项比对 —— 手填一个「差不多」的数**写不进去**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多币种绝不相加
-- ---------------------------------------------------------------------------
-- 一张发票一个币种（`currency`），币种与金额一起被钉在单据上。
-- 跨币种的合计**一律按币种分组呈现**；本模块**不做本位币折算**，
-- 汇率不可得时如实报「汇率不可得」，**绝不静默按 1:1 相加**。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 税率实时校验（不抄一份税率表）
-- ---------------------------------------------------------------------------
-- 每一行的税率**在落库前**经 12.6.1.1 的 `resolve` 实时取：
--   * 命中不到 ⇒ **报错**，不退回最新一条、不按 0% 处理；
--   * 命中的税率**版本 id**（`tax_rate_id`）与税率快照（`tax_rate`）一起落库，
--     发票因此**自带**当时的税率口径，之后政策改税率不影响这张发票；
--   * 辖区码必须落在 12.6.1.1 的受控辖区码表里，且**不是美国辖区**
--     （联邦与 50 州归 channel 16.3.2.3）。
--
-- 🔴 **贷项行例外**：贷项行镜像原发票行，税率**由原发票行带出**
--   （红冲必须精确冲回当初的税），不再另行取税率。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 finance 既有迁移（V338 ~ V361）同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
--
-- 发票的业务日期 `issue_date` 是 **date**（日历日，不是某一瞬间）：
-- 发票日是开票日历上的那一天，没有「几点几分」的意思。
-- ===========================================================================

-- ===========================================================================
-- 一、finance_invoice_template · 发票模板（按平台 / 国家配置）
-- ===========================================================================
-- 🔴 **模板零预置行**：模板是租户自己的开票事实（抬头、税档、号段规则）。
-- 系统只提供**结构**与**受控辖区选项**，**不替用户决定**任何一个国家的发票长什么样。
--
-- 🔴 **模板按国家 / 平台配置**：同一租户可以给「德国 + 亚马逊」和「德国 + Shopify」
--   各配一张模板；同一（国家 × 平台 × 单据类型）下**同一时刻只能有一张生效模板**
--   （生效区间不许重叠 + 部分唯一索引），否则「按模板生成」就没有唯一答案。
CREATE TABLE finance_invoice_template (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 模板编码（租户内唯一，生成发票时快照进单据）
  template_code     varchar(64) NOT NULL,
  template_name     varchar(200) NOT NULL,
  -- 目的国（ISO 3166-1 alpha-2）
  country_code      char(2) NOT NULL,
  -- 受控辖区码（**国家一级**）：取自 12.6.1.1 的辖区选项（其真值源是 channel
  -- 16.3.1.1 的受控码表）。服务层落库前实时校验 ⇒ 辖区码不是自由文本。
  jurisdiction_code varchar(16) NOT NULL,
  -- 辖区名称**快照**（跨模块引用不建 FK，名称留在本表免得半年后没人说得清是哪个辖区；
  -- 与 12.6.1.1 的 finance_tax_rate.jurisdiction_name 同一手法）
  jurisdiction_name varchar(100) NOT NULL DEFAULT '',
  -- 平台编码（channel 2 平台目录；空串 = 通用模板，不限平台）
  platform_code     varchar(32) NOT NULL DEFAULT '',
  -- 单据类型：销售发票 / 贷项通知单（贷项单的模板与发票分开配：抬头与号段往往不同）
  document_type     varchar(16) NOT NULL
                    CHECK(document_type IN ('INVOICE','CREDIT_NOTE')),
  -- 默认税种（行级可覆盖，但覆盖后仍要过实时校验）
  tax_type          varchar(16) NOT NULL
                    CHECK(tax_type IN ('SALES_TAX','VAT','IOSS','WITHHOLDING')),
  -- 默认税码（该辖区下的税档；行级可覆盖）
  tax_code          varchar(32) NOT NULL DEFAULT '',
  -- 模板默认币种（一张发票只有一个币种；行级不覆盖币种）
  currency          char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 发票抬头（快照进单据）
  title             varchar(200) NOT NULL DEFAULT '',
  -- 编号规则：前缀 + 范围 + 期间口径
  sequence_prefix   varchar(32) NOT NULL,
  sequence_scope    varchar(16) NOT NULL
                    CHECK(sequence_scope IN ('TENANT','COUNTRY','PLATFORM')),
  -- 🔴 号段取值（**由上面两项派生**，DB 层钉死自洽）：
  --   TENANT  → 'ALL'（全租户一个号段）
  --   COUNTRY → country_code（按目的国分号段）
  --   PLATFORM→ platform_code（按平台分号段）
  --   蓝图纪律「发票号按『租户 + 期间/国家』生成」说的就是这一列。
  sequence_scope_value varchar(32) NOT NULL,
  sequence_period   varchar(16) NOT NULL DEFAULT 'NONE'
                    CHECK(sequence_period IN ('NONE','MONTHLY','YEARLY')),
  -- 生效区间（左闭右开）
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz,
  -- ACTIVE = 可用于生成；DISABLED = 停用（历史模板仍被已开具的发票快照引用）
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','DISABLED')),
  note              varchar(500) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_code),
  -- 🔴 **同一个编号前缀只能对应一个号段**：否则两张模板（前缀相同、号段不同）
  --   会各自取号，两边都拿到「INV-000001」—— 同一租户两张发票同号，税务上直接是事故。
  UNIQUE(tenant_id, sequence_prefix, sequence_scope_value),
  -- 同一版本链内生效起点唯一
  UNIQUE(tenant_id, country_code, platform_code, document_type, effective_from),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 号段取值必须与「范围 + 国家 / 平台」自洽
  CHECK((sequence_scope = 'TENANT'   AND sequence_scope_value = 'ALL')
     OR (sequence_scope = 'COUNTRY'  AND sequence_scope_value = country_code)
     OR (sequence_scope = 'PLATFORM' AND sequence_scope_value = platform_code))
);
COMMENT ON TABLE finance_invoice_template IS
  '12.6.2.1 · 发票模板（租户级，按 平台 × 国家 × 单据类型 配置，含编号规则）。'
  '🔴 **模板零预置行** —— 系统只提供结构与受控辖区选项，不替用户决定任何国家的发票长什么样。'
  '同一（国家 × 平台 × 单据类型）下同一时刻只能有一张生效模板（生效区间不许重叠）。'
  '辖区码取自 12.6.1.1 的受控辖区码表（真值源是 channel 16.3.1.1），**只允许国家一级**。';

CREATE INDEX finance_invoice_template_scope
  ON finance_invoice_template(tenant_id, country_code, platform_code, document_type, effective_from DESC);

-- 同一（租户 × 国家 × 平台 × 单据类型）同时只能有一张生效模板
CREATE UNIQUE INDEX finance_invoice_template_one_effective
  ON finance_invoice_template(tenant_id, country_code, platform_code, document_type)
  WHERE effective_to IS NULL AND status = 'ACTIVE';

-- ===========================================================================
-- 二、finance_invoice_sequence · 编号序列（**并发安全**）
-- ===========================================================================
-- 🔴 取号**必须**是条原子语句：
--   INSERT INTO finance_invoice_sequence(...) VALUES(...)
--   ON CONFLICT (tenant_id, sequence_key, period_code)
--   DO UPDATE SET next_value = finance_invoice_sequence.next_value + 1
--   RETURNING next_value;
-- 「先查 max 再 +1」在并发下会让两张发票拿到同一个号 ——
-- 那不是「号段用乱」，那是**两张发票同号**，税务上直接是事故。
CREATE TABLE finance_invoice_sequence (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 号段键 = 模板 × 编号范围（租户 / 国家 / 平台）
  sequence_key   varchar(160) NOT NULL,
  -- 期间口径：NONE / YYYY / YYYY-MM
  period_code    varchar(16) NOT NULL,
  -- 下一个可用的号
  next_value     bigint NOT NULL DEFAULT 1 CHECK(next_value >= 1),
  last_issued_at timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sequence_key, period_code)
);
COMMENT ON TABLE finance_invoice_sequence IS
  '12.6.2.1 · 发票编号序列（租户级，按 号段 × 期间）。'
  '🔴 **取号必须原子**：ON CONFLICT DO UPDATE + RETURNING，'
  '并发下两张发票不会拿到同一个号（绝不允许「先查 max 再 +1」）。';

-- ===========================================================================
-- 三、finance_invoice · 发票头（销售发票 + 贷项通知单）
-- ===========================================================================
CREATE TABLE finance_invoice (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 发票号：**开具时**才分配（草稿为 NULL）。租户内唯一。
  invoice_number      varchar(64),
  document_type       varchar(16) NOT NULL
                      CHECK(document_type IN ('INVOICE','CREDIT_NOTE')),
  status              varchar(16) NOT NULL DEFAULT 'DRAFT'
                      CHECK(status IN ('DRAFT','ISSUED','VOID','REVERSED')),
  -- 模板（跨服务 / 跨模块引用一律不建 FK，模板编码与抬头是**快照**）
  template_id         uuid NOT NULL,
  template_code       varchar(64) NOT NULL,
  title               varchar(200) NOT NULL DEFAULT '',
  -- 目的国与辖区（快照；辖区名留一份，免得模板改了之后没人说得清这张发票开的是哪个辖区）
  country_code        char(2) NOT NULL,
  jurisdiction_code   varchar(16) NOT NULL,
  jurisdiction_name   varchar(100) NOT NULL DEFAULT '',
  platform_code       varchar(32) NOT NULL DEFAULT '',
  -- 店铺站点（channel 2.3.1.1 店铺目录的逻辑 ID）
  shop_id             uuid,
  shop_code           varchar(64) NOT NULL DEFAULT '',
  -- 上游来源（**只读引用**，不建跨库 FK）
  --   source_receivable_id：9 订单侧的 b2b_receivable（开票需求）
  --   source_settlement_id：12.3.1 结算单（finance 同库）
  source_receivable_id uuid,
  source_settlement_id uuid,
  source_order_no     varchar(64) NOT NULL DEFAULT '',
  customer_name       varchar(200) NOT NULL DEFAULT '',
  -- 🔴 一张发票**只有一个币种**；跨币种合计一律分组呈现，不做本位币折算
  currency            char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 发票业务日期（日历日）
  issue_date          date NOT NULL,
  -- 金额：**由明细行派生**（触发器写），服务层写不进去
  subtotal_amount     numeric(19,4) NOT NULL DEFAULT 0,
  tax_amount          numeric(19,4) NOT NULL DEFAULT 0,
  total_amount        numeric(19,4) NOT NULL DEFAULT 0,
  -- 红冲配对（贷项单 → 原发票；原发票 → 贷项单）
  original_invoice_id      uuid,
  original_invoice_number varchar(64) NOT NULL DEFAULT '',
  reversal_invoice_id      uuid,
  -- 作废 / 红冲留痕（谁、何时、为什么）
  reason              varchar(500) NOT NULL DEFAULT '',
  issued_by           uuid,
  issued_at           timestamptz,
  voided_by           uuid,
  voided_at           timestamptz,
  reversed_by         uuid,
  reversed_at         timestamptz,
  -- 平台开票对接
  platform_sync_status varchar(16) NOT NULL DEFAULT 'NOT_SUBMITTED'
                      CHECK(platform_sync_status IN
                            ('NOT_SUBMITTED','SUBMITTED','ACKNOWLEDGED','FAILED')),
  platform_invoice_ref varchar(128) NOT NULL DEFAULT '',
  created_by          uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, invoice_number),
  -- 贷项单**必须**指向原发票（孤立红冲在 DB 层就写不出来）
  CHECK(document_type = 'INVOICE' OR original_invoice_id IS NOT NULL),
  CHECK(status <> 'ISSUED' OR invoice_number IS NOT NULL),
  CHECK(status <> 'ISSUED' OR issued_at IS NOT NULL)
);
COMMENT ON TABLE finance_invoice IS
  '12.6.2.1 · 发票与贷项通知单（租户级）。'
  '状态机 = 草稿 / 开具 / 作废 / 红冲，转换规则由 DB 触发器钉死；'
  '**作废与红冲必须留痕**（谁、何时、为什么）。'
  '🔴 **红冲成对**：贷项单必须指向一张已开具的原发票，不允许孤立红冲。'
  '🔴 金额由明细行派生；一张发票只有一个币种，跨币种绝不相加。';

CREATE INDEX finance_invoice_list
  ON finance_invoice(tenant_id, status, issue_date DESC, id);
CREATE INDEX finance_invoice_source
  ON finance_invoice(tenant_id, source_receivable_id);
CREATE UNIQUE INDEX finance_invoice_one_reversal
  ON finance_invoice(tenant_id, original_invoice_id)
  WHERE document_type = 'CREDIT_NOTE' AND status IN ('DRAFT','ISSUED');

-- ===========================================================================
-- 四、finance_invoice_line · 发票明细行（**税码税率税额**）
-- ===========================================================================
-- 🔴 `line_amount` / `tax_amount` 是**生成列**：
--   行金额 = 数量 × 单价（4 位小数）；税额 = 行金额 × 税率（4 位小数）。
-- 应用层算错时它们不会跟着错 —— **没有第二个写入口**。
--
-- 🔴 数量的**符号**表达红冲方向：
--   销售发票 quantity > 0；贷项通知单 quantity < 0。
--   于是「一张发票的合计」在两种单据上都是**同号可加**的。
CREATE TABLE finance_invoice_line (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  invoice_id    uuid NOT NULL,
  line_no       int NOT NULL CHECK(line_no >= 1),
  sku_code      varchar(64) NOT NULL DEFAULT '',
  product_name  varchar(200) NOT NULL DEFAULT '',
  -- 数量（销售发票为正，贷项单为负）
  quantity      numeric(19,6) NOT NULL CHECK(quantity <> 0),
  unit_price    numeric(19,4) NOT NULL CHECK(unit_price >= 0),
  line_amount   numeric(19,4) GENERATED ALWAYS AS (round(quantity * unit_price, 4)) STORED,
  -- 税码 / 税率 / 命中的税率版本（**实时校验后**才允许落库）
  tax_type      varchar(16) NOT NULL
                CHECK(tax_type IN ('SALES_TAX','VAT','IOSS','WITHHOLDING')),
  tax_code      varchar(32) NOT NULL,
  tax_rate      numeric(9,6) NOT NULL CHECK(tax_rate >= 0 AND tax_rate <= 1),
  tax_rate_id   uuid NOT NULL,
  tax_amount    numeric(19,4) GENERATED ALWAYS AS (round(quantity * unit_price * tax_rate, 4)) STORED,
  -- 🔴 贷项行的镜像来源（原发票行）与带出的金额（原行金额的绝对值）
  original_line_id     uuid,
  original_line_amount numeric(19,4),
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, invoice_id, line_no),
  CHECK((original_line_id IS NULL) = (original_line_amount IS NULL))
);
COMMENT ON TABLE finance_invoice_line IS
  '12.6.2.1 · 发票明细行（租户级）。'
  '🔴 行金额与税额是**生成列**（数量 × 单价 × 税率），应用层算错也不会跟着错。'
  '🔴 贷项行必须**逐项镜像**原发票行（数量取负）：红冲金额从原发票带出，不许手填。';

CREATE INDEX finance_invoice_line_invoice
  ON finance_invoice_line(tenant_id, invoice_id, line_no);

-- ===========================================================================
-- 五、finance_invoice_event · 状态记录（**append-only 留痕**）
-- ===========================================================================
-- 每次状态变化写一条：谁、何时、为什么、从什么状态到什么状态。
-- 运行角色的 UPDATE / DELETE 已收回 —— 留痕表能改就等于没有留痕。
CREATE TABLE finance_invoice_event (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  invoice_id  uuid NOT NULL,
  event_type  varchar(24) NOT NULL CHECK(event_type IN (
                'CREATED','ISSUED','VOID','REVERSED','PLATFORM_SUBMITTED',
                'PLATFORM_ACKNOWLEDGED','PLATFORM_FAILED','CREDIT_NOTE_CREATED')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status   varchar(16) NOT NULL DEFAULT '',
  reason      varchar(500) NOT NULL DEFAULT '',
  -- 操作人（identity 已认证的成员 user id）——「谁」就靠这一列
  actor_id    uuid,
  occurred_at timestamptz NOT NULL,
  detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE finance_invoice_event IS
  '12.6.2.1 · 发票状态记录（append-only 留痕）。'
  '🔴 **作废与红冲必须留痕**（谁、何时、为什么）。'
  '运行角色的 UPDATE / DELETE 已收回 —— 留痕表能改就等于没有留痕。';

CREATE INDEX finance_invoice_event_invoice
  ON finance_invoice_event(tenant_id, invoice_id, occurred_at, id);

-- ===========================================================================
-- 六、finance_invoice_platform_submission · 平台开票对接记录
-- ===========================================================================
-- 每次提交平台开票写一条：报文快照 + 摘要 + 结果 + 平台回执号。
-- 失败**也**留痕（error_message 写明上游原话）——
-- 「提交失败」不留痕等于没提交过。
CREATE TABLE finance_invoice_platform_submission (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  invoice_id     uuid NOT NULL,
  platform_code  varchar(32) NOT NULL,
  status         varchar(16) NOT NULL DEFAULT 'PENDING'
                 CHECK(status IN ('PENDING','SUBMITTED','ACKNOWLEDGED','FAILED')),
  -- 报文快照（提交出去的原文，留档以便对账）
  payload        jsonb NOT NULL,
  -- 报文摘要（sha-256）：同一份报文重复提交要能一眼看出来
  payload_digest varchar(64) NOT NULL,
  attempts       int NOT NULL DEFAULT 1 CHECK(attempts >= 1),
  -- 平台回执号（平台确认后回填）
  platform_ref   varchar(128) NOT NULL DEFAULT '',
  error_message  varchar(500) NOT NULL DEFAULT '',
  submitted_at   timestamptz,
  acknowledged_at timestamptz,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE finance_invoice_platform_submission IS
  '12.6.2.1 · 平台开票对接记录（租户级）。'
  '报文快照 + sha-256 摘要 + 提交结果 + 平台回执号；'
  '🔴 **失败也留痕**（error_message 写上游原话）—— 提交失败不留痕等于没提交过。';

CREATE INDEX finance_invoice_platform_submission_invoice
  ON finance_invoice_platform_submission(tenant_id, invoice_id, created_at DESC);

-- 同一张发票同一时刻只允许一条未终结的提交
CREATE UNIQUE INDEX finance_invoice_platform_submission_one_open
  ON finance_invoice_platform_submission(tenant_id, invoice_id)
  WHERE status IN ('PENDING','SUBMITTED');

-- ===========================================================================
-- 七、DB 守卫：把蓝图的话变成数据层做不到的事
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 模板生效区间不许重叠（同一 国家 × 平台 × 单据类型）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_invoice_template_no_overlap() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM finance_invoice_template x
     WHERE x.tenant_id = NEW.tenant_id
       AND x.country_code = NEW.country_code
       AND x.platform_code = NEW.platform_code
       AND x.document_type = NEW.document_type
       AND x.id <> NEW.id
       AND tstzrange(x.effective_from, x.effective_to, '[)')
          && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ) THEN
    RAISE EXCEPTION '同一国家同一平台同一单据类型的模板生效区间不许重叠'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_invoice_template_no_overlap
  BEFORE INSERT OR UPDATE ON finance_invoice_template
  FOR EACH ROW EXECUTE FUNCTION finance_invoice_template_no_overlap();

-- ---------------------------------------------------------------------------
-- (b) 单头金额由明细行派生（**服务层写不进去**）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_invoice_amount_from_lines() RETURNS trigger AS $$
DECLARE
  v_invoice uuid;
  v_tenant  uuid;
BEGIN
  v_invoice := COALESCE(NEW.invoice_id, OLD.invoice_id);
  v_tenant  := COALESCE(NEW.tenant_id, OLD.tenant_id);

  UPDATE finance_invoice i
     SET subtotal_amount = COALESCE((SELECT sum(l.line_amount) FROM finance_invoice_line l
                                      WHERE l.invoice_id = v_invoice AND l.tenant_id = i.tenant_id), 0),
         tax_amount      = COALESCE((SELECT sum(l.tax_amount) FROM finance_invoice_line l
                                      WHERE l.invoice_id = v_invoice AND l.tenant_id = i.tenant_id), 0),
         total_amount    = COALESCE((SELECT sum(l.line_amount + l.tax_amount) FROM finance_invoice_line l
                                      WHERE l.invoice_id = v_invoice AND l.tenant_id = i.tenant_id), 0),
         updated_at = now()
   WHERE i.id = v_invoice AND i.tenant_id = v_tenant;

  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_invoice_amount_from_lines
  AFTER INSERT OR UPDATE OR DELETE ON finance_invoice_line
  FOR EACH ROW EXECUTE FUNCTION finance_invoice_amount_from_lines();

-- ---------------------------------------------------------------------------
-- (c) 发票守卫：状态机 + 已开具后冻结 + 编号只在开具时确定
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_invoice_guard() RETURNS trigger AS $$
DECLARE
  v_orig finance_invoice%ROWTYPE;
BEGIN
  -- ---- 1. 状态转换合法性（显式转换规则）----
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
         (OLD.status = 'DRAFT'  AND NEW.status IN ('ISSUED','VOID'))
      OR (OLD.status = 'ISSUED' AND NEW.status IN ('VOID','REVERSED'))
    ) THEN
      RAISE EXCEPTION '发票状态不能从 % 转到 %（允许：草稿→开具/作废，开具→作废/红冲）',
        OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 2. 编号只在「开具」这一刻确定 ----
  IF NEW.invoice_number IS DISTINCT FROM OLD.invoice_number THEN
    IF NOT (OLD.status = 'DRAFT' AND NEW.status = 'ISSUED') THEN
      RAISE EXCEPTION '发票编号只能在开具时确定一次'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.invoice_number IS NULL OR btrim(NEW.invoice_number) = '' THEN
      RAISE EXCEPTION '开具时必须给出发票编号'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 3. 开具必须有开具时间与开具人 ----
  IF NEW.status = 'ISSUED' AND OLD.status = 'DRAFT' THEN
    IF NEW.issued_at IS NULL THEN
      RAISE EXCEPTION '开具时必须留下开具时间'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.issued_by IS NULL THEN
      RAISE EXCEPTION '开具时必须留下开具人'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 4. 作废必须留痕：谁、何时、为什么 ----
  IF NEW.status = 'VOID' AND OLD.status <> 'VOID' THEN
    IF NEW.reason IS NULL OR btrim(NEW.reason) = '' THEN
      RAISE EXCEPTION '作废必须写明原因'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.voided_at IS NULL THEN
      RAISE EXCEPTION '作废必须留下作废时间'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.voided_by IS NULL THEN
      RAISE EXCEPTION '作废必须留下作废人'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 5. 红冲必须成对：原发票存在、已开具、尚未被红冲过 ----
  IF NEW.status = 'REVERSED' AND OLD.status <> 'REVERSED' THEN
    IF NEW.reversal_invoice_id IS NULL THEN
      RAISE EXCEPTION '红冲必须指向对应的贷项通知单'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.reversed_at IS NULL OR NEW.reversed_by IS NULL THEN
      RAISE EXCEPTION '红冲必须留下红冲人与红冲时间'
        USING ERRCODE = 'check_violation';
    END IF;
    IF btrim(COALESCE(NEW.reason, '')) = '' THEN
      RAISE EXCEPTION '红冲必须写明原因'
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_orig
      FROM finance_invoice x
     WHERE x.id = NEW.reversal_invoice_id AND x.tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '红冲指向的贷项通知单不存在或不属于本租户'
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_orig.document_type <> 'CREDIT_NOTE' OR v_orig.original_invoice_id IS DISTINCT FROM NEW.id THEN
      RAISE EXCEPTION '红冲必须成对：贷项通知单必须指向这张原发票'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 6. 已开具后业务列冻结（只能作废 / 红冲）----
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.document_type IS DISTINCT FROM OLD.document_type
       OR NEW.template_id IS DISTINCT FROM OLD.template_id
       OR NEW.template_code IS DISTINCT FROM OLD.template_code
       OR NEW.title IS DISTINCT FROM OLD.title
       OR NEW.country_code IS DISTINCT FROM OLD.country_code
       OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
       OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
       OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.shop_code IS DISTINCT FROM OLD.shop_code
       OR NEW.source_receivable_id IS DISTINCT FROM OLD.source_receivable_id
       OR NEW.source_settlement_id IS DISTINCT FROM OLD.source_settlement_id
       OR NEW.source_order_no IS DISTINCT FROM OLD.source_order_no
       OR NEW.customer_name IS DISTINCT FROM OLD.customer_name
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.issue_date IS DISTINCT FROM OLD.issue_date
       OR NEW.original_invoice_id IS DISTINCT FROM OLD.original_invoice_id
       OR NEW.subtotal_amount IS DISTINCT FROM OLD.subtotal_amount
       OR NEW.tax_amount IS DISTINCT FROM OLD.tax_amount
       OR NEW.total_amount IS DISTINCT FROM OLD.total_amount THEN
      RAISE EXCEPTION '已开具的发票业务列不可改写（只能作废或红冲）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_invoice_guard
  BEFORE UPDATE ON finance_invoice
  FOR EACH ROW EXECUTE FUNCTION finance_invoice_guard();

-- ---------------------------------------------------------------------------
-- (d) 贷项单守卫：**红冲成对**，不允许孤立红冲
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_invoice_credit_pair_guard() RETURNS trigger AS $$
DECLARE
  v_orig finance_invoice%ROWTYPE;
BEGIN
  IF NEW.document_type <> 'CREDIT_NOTE' THEN
    RETURN NEW;
  END IF;

  IF NEW.original_invoice_id IS NULL THEN
    RAISE EXCEPTION '贷项通知单必须指向原发票（不允许孤立红冲）'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO v_orig
    FROM finance_invoice x
   WHERE x.id = NEW.original_invoice_id AND x.tenant_id = NEW.tenant_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION '贷项通知单指向的原发票不存在或不属于本租户'
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_orig.document_type <> 'INVOICE' THEN
    RAISE EXCEPTION '只能红冲销售发票，不能红冲贷项通知单'
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_orig.status <> 'ISSUED' THEN
    RAISE EXCEPTION '只能红冲已开具的发票（原发票当前状态是 %）', v_orig.status
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_orig.reversal_invoice_id IS NOT NULL THEN
    RAISE EXCEPTION '该发票已被红冲过（贷项通知单 %），不允许重复红冲',
      v_orig.reversal_invoice_id USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.currency <> v_orig.currency THEN
    RAISE EXCEPTION '贷项通知单的币种必须与原发票一致（原发票 %）', v_orig.currency
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.jurisdiction_code <> v_orig.jurisdiction_code THEN
    RAISE EXCEPTION '贷项通知单的辖区必须与原发票一致（原发票 %）', v_orig.jurisdiction_code
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_invoice_credit_pair_guard
  BEFORE INSERT ON finance_invoice
  FOR EACH ROW EXECUTE FUNCTION finance_invoice_credit_pair_guard();

-- ---------------------------------------------------------------------------
-- (e) 明细行守卫：只在草稿期可写 + 符号必须与单据类型一致 + 贷项行逐项镜像
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_invoice_line_guard() RETURNS trigger AS $$
DECLARE
  v_inv    finance_invoice%ROWTYPE;
  v_line   finance_invoice_line%ROWTYPE;
BEGIN
  IF TG_OP = 'DELETE' THEN
    SELECT * INTO v_inv FROM finance_invoice x
     WHERE x.id = OLD.invoice_id AND x.tenant_id = OLD.tenant_id;
    IF FOUND AND v_inv.status <> 'DRAFT' THEN
      RAISE EXCEPTION '已开具的发票明细不可改写（只能作废或红冲）'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
  END IF;

  SELECT * INTO v_inv FROM finance_invoice x
   WHERE x.id = NEW.invoice_id AND x.tenant_id = NEW.tenant_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '明细行必须挂在一张本租户的发票上'
      USING ERRCODE = 'check_violation';
  END IF;

  IF TG_OP = 'UPDATE' THEN
    IF v_inv.status <> 'DRAFT' THEN
      RAISE EXCEPTION '已开具的发票明细不可改写（只能作废或红冲）'
        USING ERRCODE = 'check_violation';
    END IF;
    -- 镜像行的镜像来源不可改（否则「带出」这件事会被就地篡改）
    IF OLD.original_line_id IS NOT NULL
       AND (NEW.original_line_id IS DISTINCT FROM OLD.original_line_id
            OR NEW.original_line_amount IS DISTINCT FROM OLD.original_line_amount) THEN
      RAISE EXCEPTION '贷项行的镜像来源不可改写'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- 符号必须与单据类型一致：发票为正、贷项为负
  IF v_inv.document_type = 'INVOICE' AND NEW.quantity <= 0 THEN
    RAISE EXCEPTION '销售发票的行数量必须为正'
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_inv.document_type = 'CREDIT_NOTE' AND NEW.quantity >= 0 THEN
    RAISE EXCEPTION '贷项通知单的行数量必须为负（红冲方向）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 贷项行必须**逐项镜像**原发票行：金额从原发票带出，不许手填
  IF v_inv.document_type = 'CREDIT_NOTE' THEN
    IF NEW.original_line_id IS NULL OR NEW.original_line_amount IS NULL THEN
      RAISE EXCEPTION '贷项行必须指向原发票的行并带出金额（红冲金额不许手填）'
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_line FROM finance_invoice_line x
     WHERE x.id = NEW.original_line_id AND x.tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '贷项行指向的原发票行不存在或不属于本租户'
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_line.invoice_id <> v_inv.original_invoice_id THEN
      RAISE EXCEPTION '贷项行的镜像来源必须属于这张发票的原发票'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.quantity <> -v_line.quantity THEN
      RAISE EXCEPTION '贷项行的数量必须由原发票行带出（负号表示红冲方向），不许手填'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.unit_price <> v_line.unit_price THEN
      RAISE EXCEPTION '贷项行的单价必须由原发票行带出，不许手填'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.tax_type <> v_line.tax_type
       OR NEW.tax_code <> v_line.tax_code
       OR NEW.tax_rate <> v_line.tax_rate
       OR NEW.tax_rate_id <> v_line.tax_rate_id THEN
      RAISE EXCEPTION '贷项行的税码与税率必须由原发票行带出，不许手填'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.original_line_amount <> abs(v_line.line_amount) THEN
      RAISE EXCEPTION '贷项行的金额必须由原发票行带出，不许手填'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_invoice_line_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_invoice_line
  FOR EACH ROW EXECUTE FUNCTION finance_invoice_line_guard();

-- ===========================================================================
-- 八、多租户（RLS isolation）+ 授权
-- ===========================================================================
ALTER TABLE finance_invoice_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice_template FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice_template
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_invoice_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice_sequence
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_invoice_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice_line FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice_line
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_invoice_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice_event FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice_event
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_invoice_platform_submission ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_invoice_platform_submission FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_invoice_platform_submission
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_template TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_template TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_sequence TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_sequence TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_line TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_line TO starcon_app;
-- 🔴 留痕表 append-only：运行角色只能 INSERT / SELECT，UPDATE / DELETE **不授予**
GRANT SELECT,INSERT ON finance_invoice_event TO finance_app;
GRANT SELECT,INSERT ON finance_invoice_event TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_platform_submission TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_invoice_platform_submission TO starcon_app;

-- ===========================================================================
-- 九、边界声明（不是本模块的缺口）
-- ===========================================================================
--  * **12.6.1.1 税率配置（已交付）**：本模块的税率 / 税码 / 辖区码**全部实时取自它**，
--    本模块**不建**税率表、不抄一份税率。🔴 命中不到一律报错，不按 0% 处理。
--  * **12.5.2.1 凭证（已交付）**：发票**不写凭证**。本模块只产出发票事实；
--    过账走 12.5.2.1 的真实接口，不重复实现凭证过账。
--  * **11.4 售后（已交付）**：退货开票调整 = **红冲**，金额从原发票逐行带出。
--  * **9 订单 / 12.3.1 结算**：**只读**。本模块**不写**订单表、**不写**结算表，
--    跨服务只走授权只读 HTTP API。
--  * **平台开票对接**：报文生成、批量提交与回执登记在本模块；
--    **实际报文递交**走 channel（平台适配器的归属服务），channel 尚未提供该接口时
--    提交**失败并如实留痕**，能力声明里写明缺口归谁。
--  * **各国发票要求差异大**：模板按国家配置，**本系统不承诺覆盖所有国家税务规则**；
--    能力声明里如实写清支持哪些、缺口归谁。
-- ===========================================================================
