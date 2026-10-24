-- ===========================================================================
-- 12.6.1.2 VAT/IOSS 申报数据 —— finance 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/12-finance.md）：
--   ### 12.6.1.2 VAT/IOSS 申报数据
--   - 职责：按辖区与周期汇总申报所需数据：taxable 销售额、已收 VAT、IOSS 订单与
--           代收金额、可抵扣进项，导出申报文件或对接申报接口。
--   - 输入：订单税务数据；结算与收付款数据；汇率快照；辖区规则。
--   - 输出：申报底稿 + 汇总报表 + 可导出文件。
--   - 关系：上游 = 12.6.1.1、9 订单、12.2.1.1；下游 = 16 合规（申报日历提醒）。
--           **本系统提供申报底稿，不替代税务申报动作**——申报主体与责任在用户，
--           系统保证数据完整可核。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 「不替代税务申报动作」落成结构而不是文案
-- ---------------------------------------------------------------------------
-- 蓝图那句话在这里由**三张表都不含任何「申报状态」语义**来钉死：
--
--   * 本模块**没有**「已提交 / 已申报 / 受理号」这种列 —— 申报是**用户**对税务
--     机构做的动作，系统既不做、也不假装做。本模块只产出**底稿 + 汇总 + 文件**。
--   * 底稿一旦生成就是**快照**：历史版本行**不可改写**（触发器钉死），重新生成
--     = 旧底稿置为 SUPERSEDED + 新起一版。⇒ 底稿是「当时那份数据的样子」，
--     可以拿去核，也可以拿去做申报；**申报与否是用户的事**。
--   * 每张底稿都带**可核信息**：生成时间（generated_at）、覆盖期间
--     （period_start / period_end）、口径说明（generation_basis）、税率版本
--     （tax_rate_id + 生效区间）、币种（line.currency）、汇率快照
--     （exchange_rate + asOf）。⇒ 拿到一份底稿，能说清「它是用哪些数、按什么
--     口径、在什么时点算出来的」。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 三张表的职责切分
-- ---------------------------------------------------------------------------
--   finance_filing_draft         底稿头：一次取数、一份口径、一个辖区与期间
--   finance_filing_draft_line    汇总行：**按币种分组**（多币种绝不相加）
--   finance_filing_draft_export  导出留痕：谁在什么时候导出了哪个文件、多少行
--
--   「汇总报表」与「导出文件」是**同一份汇总行的两种呈现**（一个是结构化 JSON，
--   一个是 CSV / JSON 文件）—— 所以**不建第二张汇总表**（两个真值源会漂移）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 多币种绝不相加（结构级保证）
-- ---------------------------------------------------------------------------
--   * 汇总行按 (底稿 × 币种) 唯一 ⇒ 底稿头**没有任何金额列**，页面上也就**没有**
--     一个「总额」可以假装是跨币种可加的数。
--   * 折算（base_currency）是**附加**的一列（converted_net_payable），只在
--     汇率**真的取到**时才有值；汇率取不到 ⇒ 该列是 NULL 且 exchange_rate_available
--     = false。**绝不按 1:1 兜底**（1:1 兜底 = 编一个汇率出来）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 辖区码不是自由文本（**不建影子码表、不跨库 FK**）
-- ---------------------------------------------------------------------------
-- 「目的国/地区」的值域由 channel 16.3.1.1 的 `tax_registration_jurisdiction`
-- （受控码表）决定。本模块：
--   * **不复制**那张码表（复制 = 两个真值源，改一处就漂移）；
--   * **不建**指向它的外键（跨库 FK 是模块边界纪律明令禁止的）；
--   * 服务层经**授权只读 HTTP API**（`GET /channel/tax-registration/jurisdictions`）
--     **实时校验**辖区码真实存在后才允许落库；
--   * channel 读不到 ⇒ 503 并写明「读不到」，绝不接受自由文本辖区码。
--
-- 🔴 **美国辖区（联邦与 50 州）录不进去**：州级销售税归 channel 16.3.2.3
--   （sales_tax_rate_rule），本模块**不重复建表**。服务层在落库前挡下并写明归属。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 税率必须按生效时间取（继承 12.6.1.1 的结构语义）
-- ---------------------------------------------------------------------------
--   底稿行上记的是**当时命中的那一版税率**（tax_rate_id + 生效区间 + 税率值），
--   取数走 12.6.1.1 的「按生效时间取税率」；**命中不到一律报错**（不退回最新
--   一条、不按 0% 处理）。底稿因此能自证「这个数是按哪一版税率算的」。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 可抵扣进项：**不凭空造一张进项发票表**
-- ---------------------------------------------------------------------------
--   仓库里**没有**「进项发票」这个概念 ⇒ 本模块**不建**任何进项表（建一张空表
--   再从里面读出 0，是**假装有数据**）。
--   可抵扣进项改从**已有的实际数据**取：
--     finance_voucher（已过账 POSTED）
--       → finance_voucher_line（分录行）
--         → finance_account_subject（科目属性：category / tax_treatment）
--   口径 = **应交税费类科目（category = 'LIABILITY'）且税务属性为应税口径**
--   （tax_treatment ∈ TAXABLE / REVERSE_CHARGE）的**借方**发生额 —— 借方冲减
--   应交税费，就是「已抵扣 / 已缴纳的税额」。
--
--   🔴 取不到就是**取不到**：deductible_available = false 且 deductible_note
--   写明「覆盖期间内没有已过账凭证的应交税费借方发生额（无可抵扣进项数据）」。
--   **不得退化成 0**（0 会被当成「可抵扣进项是零」这个业务结论）。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
--   与同服务最新迁移 V361（12.6.1.1）同一纪律：外键会打破同服务既有测试的
--   TRUNCATE 顺序。本模块的三张表**只用触发器**表达引用关系。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
--   全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
--   —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
--   用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
--
-- ---------------------------------------------------------------------------
-- 九、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS isolation
-- ---------------------------------------------------------------------------
--   与 finance 既有迁移（V338 ~ V361）同一套写法。租户由 backend/libraries/tenancy
--   在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- ===========================================================================

-- ===========================================================================
-- 一、finance_filing_draft · 申报底稿头
-- ===========================================================================
-- 🔴 一个租户在同一个（辖区 × 税种 × 期间 × 申报周期）下**同时只有一份生效底稿**
--   （部分唯一索引）。重新生成 ⇒ 旧底稿置为 SUPERSEDED + 新起一版。
--   ⇒ 页面上永远有一份「当前底稿」，历史版本仍然查得到、导得出。
CREATE TABLE finance_filing_draft (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 辖区码（**国家一级**）：必须真实落在 channel 16.3.1.1 的受控辖区码表里，
  -- 且**不是**美国辖区（州级销售税归 channel 16.3.2.3）。
  jurisdiction_code varchar(16) NOT NULL CHECK(btrim(jurisdiction_code) <> ''),
  jurisdiction_name varchar(200) NOT NULL DEFAULT '',
  country_code      char(2) NOT NULL DEFAULT '' CHECK(country_code = '' OR country_code ~ '^[A-Z]{2}$'),
  -- 税种：本模块只做 **VAT** 与 **IOSS** 两条申报线
  --   （SALES_TAX 归 channel 16.3.2.3 的美国州销售税；WITHHOLDING 不是申报线）。
  tax_type          varchar(16) NOT NULL CHECK(tax_type IN ('VAT','IOSS')),
  -- 覆盖期间（左闭右闭，日历日）。**底稿必须自带期间** —— 没说清期间的申报底稿
  -- 是没法拿去申报的。
  period_start      date NOT NULL,
  period_end        date NOT NULL,
  CHECK(period_end >= period_start),
  -- 申报周期（词表）：月度 / 季度。
  filing_cycle      varchar(16) NOT NULL CHECK(filing_cycle IN ('MONTHLY','QUARTERLY')),
  -- 折算目标币种（可空 = **不折算**）。折算只在汇率真的取到时才有值。
  base_currency     char(3) NOT NULL DEFAULT '' CHECK(base_currency = '' OR base_currency ~ '^[A-Z]{3}$'),
  -- 订单来源过滤（可空 = 全部来源）：MANUAL / PLATFORM / AMAZON / SHOPIFY / ...
  -- 刻意**不做**全量词表 CHECK —— 上游 trade 的来源词表由它自己演进，
  -- 这里硬编一份会变成第二个真值源。
  order_source      varchar(24) NOT NULL DEFAULT '',
  -- 状态：GENERATED 生效 / SUPERSEDED 已被新一轮取数取代（历史，保留供举证）。
  status            varchar(16) NOT NULL DEFAULT 'GENERATED'
                    CHECK(status IN ('GENERATED','SUPERSEDED')),
  -- 取数口径说明（**可核信息**）：这条底稿的应税销售额怎么算、已收 VAT 怎么来、
  -- 可抵扣进项取自哪里、哪些数据读不到。写进库而不是只写在页面上。
  generation_basis  text NOT NULL DEFAULT '',
  -- 取数覆盖情况（数字如实记：读不到的项另有 available 标记）。
  order_count       int NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  included_count    int NOT NULL DEFAULT 0 CHECK(included_count >= 0),
  -- 归属到**别的**辖区的订单数（不在本次申报范围内，但确实是真实存在的事实）。
  out_of_scope_count int NOT NULL DEFAULT 0 CHECK(out_of_scope_count >= 0),
  -- 归属不到任何国家一级辖区的订单数（收货国在受控码表里找不到对应辖区，
  -- 或订单明细读不到）—— **如实计数，不悄悄丢掉**。
  unmapped_count    int NOT NULL DEFAULT 0 CHECK(unmapped_count >= 0),
  -- 本次取数**是否被上限截断**（上游订单总数超过单次取数上限）。
  -- 截断了就必须让人看见 —— 否则底稿会被当成「这个期间全部的销售」。
  order_truncated   boolean NOT NULL DEFAULT false,
  -- 生成时间（**可核信息**）：这份底稿是什么时候算出来的。
  generated_at      timestamptz NOT NULL,
  generated_by      uuid,
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL,
  updated_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id)
);

-- 同一份「辖区 × 税种 × 期间 × 周期」**同时只有一份生效底稿**。
-- 重新生成时先把旧的置为 SUPERSEDED，再插入新的 —— 顺序反了会撞这个索引。
-- ⚠️ PostgreSQL 的 CREATE INDEX **不能**写在 CREATE TABLE 里 ⇒ 单独一条语句。
CREATE UNIQUE INDEX finance_filing_draft_one_live
  ON finance_filing_draft(tenant_id, jurisdiction_code, tax_type, period_start, period_end, filing_cycle)
  WHERE status = 'GENERATED';

-- 列表页的常用筛选维度（辖区 / 期间 / 状态）。
CREATE INDEX finance_filing_draft_jurisdiction_idx
  ON finance_filing_draft(tenant_id, jurisdiction_code, tax_type, period_start DESC);
CREATE INDEX finance_filing_draft_status_idx
  ON finance_filing_draft(tenant_id, status, generated_at DESC);

-- ===========================================================================
-- 二、finance_filing_draft_line · 汇总行（**按币种分组**）
-- ===========================================================================
-- 🔴 一行 = 一个（底稿 × 币种）。**底稿头没有任何金额列** ⇒ 页面上不存在
--   一个「跨币种总额」这种没有意义的数。
CREATE TABLE finance_filing_draft_line (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  draft_id          uuid NOT NULL,
  -- 币种（ISO 4217 alpha-3）。金额一律 numeric，**禁浮点**。
  currency          char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 应税销售额（**不含税净额**）：订单的（小计 − 折扣）之和。
  taxable_sales     numeric(19,4) NOT NULL DEFAULT 0 CHECK(taxable_sales >= 0),
  -- 已收 VAT（订单口径的税额之和）。
  vat_collected     numeric(19,4) NOT NULL DEFAULT 0 CHECK(vat_collected >= 0),
  -- IOSS 订单数与代收金额（**只有 IOSS 底稿才有值**；IOSS 底稿读不到 IOSS 清单时
  --   这两列是 NULL 而不是 0 —— 「读不到」与「是零」是两件完全不同的事）。
  ioss_order_count  int CHECK(ioss_order_count IS NULL OR ioss_order_count >= 0),
  ioss_collected    numeric(19,4) CHECK(ioss_collected IS NULL OR ioss_collected >= 0),
  -- 可抵扣进项（见文件头第六节）。取不到时 deductible_available = false 且
  -- deductible_input 为 NULL。
  deductible_input  numeric(19,4) CHECK(deductible_input IS NULL OR deductible_input >= 0),
  deductible_available boolean NOT NULL DEFAULT false,
  deductible_note   text NOT NULL DEFAULT '',
  -- 应缴 = 已收 VAT − 可抵扣进项（可抵扣进项取不到时**不产出应缴**，而不是产出
  --   一个「等于已收 VAT」的假数）。
  -- ⚠️ **允许为负**：可抵扣进项大于已收 VAT 时是**留抵 / 退税**位置，
  --   那是一个合法的税务事实，不该被 CHECK 挡掉。
  net_payable       numeric(19,4),
  -- 命中税率的**那一版**（可核信息）：id + 税码 + 税率 + 生效区间。
  tax_rate_id       uuid,
  tax_code          varchar(32) NOT NULL DEFAULT '',
  tax_rate          numeric(9,6) CHECK(tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 1)),
  tax_rate_effective_from timestamptz,
  tax_rate_effective_to   timestamptz,
  -- 汇率快照（**可核信息**）：1 本币 = ? 基准币。
  --   取不到 ⇒ exchange_rate 为 NULL 且 exchange_rate_available = false
  --   （**绝不按 1:1 兜底**）。
  exchange_rate     numeric(19,8) CHECK(exchange_rate IS NULL OR exchange_rate > 0),
  exchange_rate_as_of date,
  exchange_rate_source varchar(120) NOT NULL DEFAULT '',
  exchange_rate_available boolean NOT NULL DEFAULT false,
  -- 折算后的应缴（只在汇率可得时才有值；同样允许为负 —— 留抵位置折算后也是负的）。
  converted_net_payable numeric(19,4),
  -- 覆盖期间该币种的**实际到账**（12.2.1.1 收款单口径）—— 让底稿能对照
  -- 「已收 VAT」与「钱到没到」。读不到时为 NULL。
  received_amount   numeric(19,4) CHECK(received_amount IS NULL OR received_amount >= 0),
  -- 纳入本行的订单数（真数字，不是估算）。
  order_count       int NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id),
  -- 一个底稿里一个币种只有一行（两个「EUR 合计」会让人不知道该看哪个）。
  UNIQUE(tenant_id, draft_id, currency)
);

CREATE INDEX finance_filing_draft_line_draft_idx
  ON finance_filing_draft_line(tenant_id, draft_id, currency);

-- ===========================================================================
-- 三、finance_filing_draft_export · 导出留痕
-- ===========================================================================
-- 🔴 导出是**取证动作**：底稿导出过一次、导出了几行、内容多大、什么时候导的，
--   都要留痕（append-only）。留痕记的是**事实**（行数 / 字节数 / 内容哈希），
--   不是文件本身（文件由调用方拿走）。
CREATE TABLE finance_filing_draft_export (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  draft_id          uuid NOT NULL,
  -- 导出格式：CSV（给税务系统 / 人工核对）/ JSON（给系统对接）。
  format            varchar(8) NOT NULL CHECK(format IN ('CSV','JSON')),
  file_name         varchar(200) NOT NULL CHECK(btrim(file_name) <> ''),
  content_type      varchar(120) NOT NULL,
  -- 导出内容的事实指纹：行数 / 字节数 / 内容哈希（sha-256 十六进制）。
  row_count         int NOT NULL CHECK(row_count >= 0),
  byte_size         int NOT NULL CHECK(byte_size >= 0),
  content_hash      varchar(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  exported_at       timestamptz NOT NULL,
  exported_by       uuid,
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL,
  UNIQUE(tenant_id, id)
);

CREATE INDEX finance_filing_draft_export_draft_idx
  ON finance_filing_draft_export(tenant_id, draft_id, exported_at DESC);

-- ===========================================================================
-- 四、触发器：引用完整性 + 历史不可改写
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 汇总行必须挂在**本租户真实存在**的底稿上（替代跨表 FK）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_filing_draft_line_parent_guard() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM finance_filing_draft d
     WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.draft_id
  ) THEN
    RAISE EXCEPTION '汇总行必须挂在本租户真实存在的申报底稿上（draft_id = %）', NEW.draft_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_filing_draft_line_parent_guard
  BEFORE INSERT ON finance_filing_draft_line
  FOR EACH ROW EXECUTE FUNCTION finance_filing_draft_line_parent_guard();

-- ---------------------------------------------------------------------------
-- (b) 导出留痕必须挂在**本租户真实存在**的底稿上（替代跨表 FK）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_filing_draft_export_parent_guard() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM finance_filing_draft d
     WHERE d.tenant_id = NEW.tenant_id AND d.id = NEW.draft_id
  ) THEN
    RAISE EXCEPTION '导出留痕必须挂在本租户真实存在的申报底稿上（draft_id = %）', NEW.draft_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_filing_draft_export_parent_guard
  BEFORE INSERT ON finance_filing_draft_export
  FOR EACH ROW EXECUTE FUNCTION finance_filing_draft_export_parent_guard();

-- ---------------------------------------------------------------------------
-- (c) 🔴 底稿头：只允许「生效 ⇒ 已被取代」这一次状态推进
-- ---------------------------------------------------------------------------
--   底稿是**快照**：辖区 / 期间 / 口径 / 覆盖情况一旦生成就不许改 ——
--   改了就等于「事后把申报底稿改成另一个数」，那正是可核性要防的事。
--   重新取数 = 旧底稿置 SUPERSEDED + 新起一版（历史版本仍然查得到、导得出）。
CREATE OR REPLACE FUNCTION finance_filing_draft_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
     OR NEW.jurisdiction_name IS DISTINCT FROM OLD.jurisdiction_name
     OR NEW.country_code IS DISTINCT FROM OLD.country_code
     OR NEW.tax_type IS DISTINCT FROM OLD.tax_type
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end
     OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
     OR NEW.base_currency IS DISTINCT FROM OLD.base_currency
     OR NEW.order_source IS DISTINCT FROM OLD.order_source
     OR NEW.generation_basis IS DISTINCT FROM OLD.generation_basis
     OR NEW.order_count IS DISTINCT FROM OLD.order_count
     OR NEW.included_count IS DISTINCT FROM OLD.included_count
     OR NEW.out_of_scope_count IS DISTINCT FROM OLD.out_of_scope_count
     OR NEW.unmapped_count IS DISTINCT FROM OLD.unmapped_count
     OR NEW.order_truncated IS DISTINCT FROM OLD.order_truncated
     OR NEW.generated_at IS DISTINCT FROM OLD.generated_at
     OR NEW.generated_by IS DISTINCT FROM OLD.generated_by THEN
    RAISE EXCEPTION '申报底稿是不可改写的快照（辖区 / 期间 / 口径 / 覆盖情况生成后不可改；重新取数请另起一版）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- 状态只允许 GENERATED → SUPERSEDED 这一步，且不许改回去。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status <> 'GENERATED' OR NEW.status <> 'SUPERSEDED' THEN
      RAISE EXCEPTION '申报底稿的状态只允许从 GENERATED 变为 SUPERSEDED（当前 % → %）',
        OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_filing_draft_immutable
  BEFORE UPDATE ON finance_filing_draft
  FOR EACH ROW EXECUTE FUNCTION finance_filing_draft_immutable();

-- ---------------------------------------------------------------------------
-- (d) 🔴 汇总行：生成后**只读**
-- ---------------------------------------------------------------------------
--   底稿的数字一旦落库就不再动 —— 重新取数会另起一版。
CREATE OR REPLACE FUNCTION finance_filing_draft_line_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '申报底稿的汇总行是只读快照（重新取数请另起一版底稿）'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_filing_draft_line_immutable
  BEFORE UPDATE OR DELETE ON finance_filing_draft_line
  FOR EACH ROW EXECUTE FUNCTION finance_filing_draft_line_immutable();

-- ---------------------------------------------------------------------------
-- (e) 🔴 导出留痕：append-only
-- ---------------------------------------------------------------------------
--   导出留痕是取证凭据：改它 = 改证据。
CREATE OR REPLACE FUNCTION finance_filing_draft_export_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '导出留痕是只读取证凭据，不许修改或删除'
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_filing_draft_export_immutable
  BEFORE UPDATE OR DELETE ON finance_filing_draft_export
  FOR EACH ROW EXECUTE FUNCTION finance_filing_draft_export_immutable();

-- ===========================================================================
-- 五、多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_filing_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_filing_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_filing_draft
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_filing_draft_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_filing_draft_line FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_filing_draft_line
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_filing_draft_export ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_filing_draft_export FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_filing_draft_export
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft_line TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft_line TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft_export TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_filing_draft_export TO starcon_app;

-- ===========================================================================
-- 六、留给下游 / 上游的口子（不是本模块的缺口）
-- ===========================================================================
--  * 16.3.2.1 VAT/IOSS（**channel，别的服务**）：IOSS 订单清单与代收金额的
--    **事实归属**在 channel。本模块经授权只读 API 引用；读不到时底稿如实写
--    「读不到 IOSS 订单清单」并把 IOSS 两列留成 NULL（**不退化成 0**）。
--  * 12.6.1.1 税率配置（**同服务，已交付**）：本模块**不写**税率表，只按生效
--    时间取那一版并把它的 id / 生效区间**快照**进底稿行。
--  * 9 订单（**trade，别的服务**）：订单税务数据经授权只读 API 读，**不跨库
--    SQL / 跨库 FK / 跨库视图**。
--  * 12.2.1.1 收款单（**同服务，已交付**）：期间实际到账金额取自
--    finance_receipt，本模块**只读**，不写。
--  * 16.3.1.2 申报日历（**别的服务，未交付**）：下游提醒归它；本模块**不建**
--    任何日历表。
--  * 🔴 **本模块不做「向税务机构提交申报」这个动作**，也不假装提交成功 ——
--    申报主体与责任在用户。
-- ===========================================================================
