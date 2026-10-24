-- ===========================================================================
-- V360 · finance_financial_report —— 12.5.2.2 财务报表
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.5.2.2）原文：
--
--   ### 12.5.2.2 财务报表
--   - **职责**：生成资产负债表、利润表、现金流量表、科目余额表、明细账；支持期间对比、
--     导出、钻取到凭证。
--   - **输入**：已过账凭证；期间范围；报表模板。
--   - **输出**：报表 + 钻取路径 + 导出文件。
--   - **关系**：上游 = 12.5.2.1 凭证管理；下游 = 17 审计、对外报送。
--     **报表数字必须能钻到凭证**，"报表与明细对不上"是财务系统的致命问题。
--
-- ---------------------------------------------------------------------------
-- 0. 与上游 12.5.2.1（V357）的分工：**本模块只读它的账**
-- ---------------------------------------------------------------------------
--
--   * 本迁移**不建任何账务表**：已过账凭证、分录行、会计期间全部由 12.5.2.1 拥有。
--     本模块**只读** `finance_voucher` / `finance_voucher_line` / `finance_voucher_period`
--     ⇒ **零 ALTER 零 DROP 零 ADD COLUMN** 到别人的表（AGENTS.md：模块边界纪律）。
--   * 本模块自己只有两张表：
--       finance_report_template        —— 报表模板（**租户维护，系统不预置任何一行**）
--       finance_report_export_event    —— 导出留痕（**append-only**）
--     两者 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 1. 取数口径（三条，写死在服务层与能力声明里，页面也会原样显示）
-- ---------------------------------------------------------------------------
--
--   ① **草稿绝不进报表**：只取 `finance_voucher.status IN ('POSTED','REVERSED')`。
--      DRAFT（草稿）与 VOID（作废）都不是账。
--
--   ② **红冲按「原凭证 + 反向凭证」净额处理**（不是简单排除）：
--      12.5.2.1 的红冲语义是「新建一张反向凭证（借贷对调、金额相同）+ 原凭证标 REVERSED」，
--      而 DB 层 `finance_voucher_reversed_ck` 钉死「REVERSED ⇒ 必须指向那张红冲凭证」，
--      FK 又保证那张红冲凭证真实存在 ⇒ 原凭证与反向凭证**必然成对**。
--      两者同时纳入取数 ⇒ 同一科目上借贷对调、金额相同 ⇒ **净额自动抵消为 0**；
--      而「原凭证在上期、红冲凭证在本期」时，本期净额同样正确抵消。
--      ⇒ 口径写死为：**红冲不是排除，是净额**。
--
--   ③ **多币种绝不相加**：所有汇总按 `finance_voucher.currency` 分组。
--      本模块**不做任何本位币折算**（折算要汇率，汇率来自 catalog 的只读端口；
--      汇率不可得时应当按币种分组呈现，而不是瞎折算 —— 那属于 12.7 资金分析的敞口口径）。
--      ⇒ 表结构里**没有** "本位币金额" 这一列，就是让这件事在结构上做不到。
--
-- ---------------------------------------------------------------------------
-- 2. 报表模板：蓝图输入里的「报表模板」落地处
-- ---------------------------------------------------------------------------
--
--   蓝图把「报表模板」写进**输入** ⇒ 系统必须有一个地方让租户定义
--   「哪些科目归到哪一条报表行、属于哪个段落、现金流属于哪一类活动」。
--
--   ⚠️ **系统不预置任何一行模板**：会计科目体系是用户自定的（12.5.1.1 蓝图为原话），
--      写死一套标准模板就等于把用户的账写死 ⇒ 与 12.5.2.1 的映射表同一手法：
--      **系统不预置，租户自己登记**。
--
--   模板**可选**：没有模板时，资产负债表 / 利润表 / 科目余额表 / 明细账
--   仍然出数（按 `finance_account_subject` 的 category + balance_direction 归类，
--   每行一个科目）；模板的作用是**把若干科目归并成一条报表行**并给出行名 / 段落 /
--   现金流活动类别。
--
--   ⚠️ **现金流量表必须先有模板**：「哪个科目是现金科目」是会计政策，
--      写死「1001/1002 就是现金」就是把用户的账写死 ⇒ 未配置时
--      现金流量表返回**空 + 一句说清原因的话**，绝不虚构数据。
--
-- ---------------------------------------------------------------------------
-- 3. 金额与时间口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)（Java 侧 BigDecimal）；**全链路禁浮点**。
--   * 绝对时间 timestamptz（Java 侧绑 OffsetDateTime + `?::timestamptz`）。
--   * 业务日期 date（voucher_date / period_start / period_end）。
--   * 本表**不含金额**（模板是配置；金额的载体是凭证）。
--
-- ===========================================================================

-- ===========================================================================
-- 第一节：finance_report_template —— 报表模板（租户业务表）
-- ===========================================================================

CREATE TABLE finance_report_template (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 报表类型：五张报表各自独立配置。
  report_type    text NOT NULL CHECK(report_type IN (
    'BALANCE_SHEET','INCOME_STATEMENT','CASH_FLOW','TRIAL_BALANCE','GENERAL_LEDGER')),
  -- 科目编码（**软引用** `finance_voucher_line.subject_code`）：
  --   🔴 **不建指向 finance_account_subject 的外键** —— 凭证行的科目是快照，
  --      手工凭证允许带科目表里没有的编码（那是租户自定体系）。
  --      本表同样**不假装核对科目是否存在**：科目编码写错时，报表只会少归一个科目，
  --      不会报错 —— 这一点如实写进能力声明。
  subject_code   varchar(64) NOT NULL CHECK(btrim(subject_code) <> ''),
  -- 科目名称快照（与凭证行一样是快照，租户改名不影响历史模板的含义）。
  subject_name   varchar(120) NOT NULL DEFAULT '',
  -- 报表行编码：一行模板 = 报表上的一行。**同一行可挂多个科目**（模板行因此能归并科目）。
  line_code      varchar(64) NOT NULL CHECK(btrim(line_code) <> ''),
  -- 报表行名称（给界面看）。
  line_name      varchar(200) NOT NULL DEFAULT '',
  -- 段落（资产 / 负债 / 权益 / 收入 / 成本费用 / 经营活动…）：**自由文本**，
  --   不写死枚举 —— 用户可以把利润表分成「主营收入 / 其他收入」两段。
  section        varchar(64) NOT NULL DEFAULT '',
  -- 现金流活动类别：**只对 CASH_FLOW 有意义**。
  --   OPERATING 经营 / INVESTING 投资 / FINANCING 筹资。
  cash_flow_group text NOT NULL DEFAULT '' CHECK(cash_flow_group IN (
    '','OPERATING','INVESTING','FINANCING')),
  -- 现金角色：**只对 CASH_FLOW 有意义**。
  --   CASH 现金科目（银行存款 / 库存现金…）/ NON_CASH 非现金科目（对方科目）。
  cash_role      text NOT NULL DEFAULT '' CHECK(cash_role IN ('','CASH','NON_CASH')),
  -- 行序（报表里从上到下）。
  sort_order     int NOT NULL DEFAULT 0,
  -- 启用 / 停用。停用**不是删除**：历史报表口径要能复现。
  enabled        boolean NOT NULL DEFAULT true,
  note           varchar(500) NOT NULL DEFAULT '',
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一张报表里，一个科目只能有一条模板行（否则报表行会重复计入）。
  UNIQUE(tenant_id, report_type, subject_code),
  -- CASH_FLOW 的模板行必须说清现金角色；其余报表类型不碰这两个字段。
  CONSTRAINT finance_report_template_cash_ck CHECK (
    report_type <> 'CASH_FLOW'
    OR (cash_role IN ('CASH','NON_CASH')
        AND (cash_role = 'CASH' OR cash_flow_group IN ('OPERATING','INVESTING','FINANCING')))
  )
);

CREATE INDEX finance_report_template_by_report
  ON finance_report_template(tenant_id, report_type, sort_order, line_code);
CREATE INDEX finance_report_template_by_subject
  ON finance_report_template(tenant_id, subject_code);

-- ===========================================================================
-- 第二节：finance_report_export_event —— 导出留痕（append-only）
-- ===========================================================================

-- 蓝图输出写死「导出文件」，12.5.2.1 的习惯是「导出 ⇒ 写一条 EXPORTED 留痕」。
-- 本模块的导出行留痕**独立成表**（不写进 12.5.2.1 的 finance_voucher_event ——
-- 那是凭证的事件流，塞进报表级事件会把两种语义搅在一起）。
--
-- ⚠️ **append-only**：运行角色的 UPDATE / DELETE 已收回（见第四节）。
--   留痕里带 **content_hash**（SHA-256 十六进制）⇒ 事后能证明「导出的那份文件就是这一份」。
CREATE TABLE finance_report_export_event (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  report_type            text NOT NULL CHECK(report_type IN (
    'BALANCE_SHEET','INCOME_STATEMENT','CASH_FLOW','TRIAL_BALANCE','GENERAL_LEDGER')),
  format                 text NOT NULL CHECK(format IN ('CSV','JSON')),
  -- 期间范围（**逻辑引用** `finance_voucher_period.id`：不建 FK ——
  --   那张表的最终归属是 12.5.1.2，它接管时不该被本模块的外键绑住）。
  period_from_id         uuid NOT NULL,
  period_to_id           uuid NOT NULL,
  compare_period_from_id uuid,
  compare_period_to_id   uuid,
  -- 币种范围：ALL（全部按币种分组） / 具体币种。
  currency_scope         text NOT NULL DEFAULT 'ALL',
  -- 明细账专用：看的哪个科目。
  subject_code           varchar(64) NOT NULL DEFAULT '',
  -- 导出内容规模（行数 / 币种数 / 涉及的凭证数 / 分录行数）。
  row_count              int NOT NULL CHECK(row_count >= 0),
  currency_count         int NOT NULL CHECK(currency_count >= 0),
  voucher_count          int NOT NULL CHECK(voucher_count >= 0),
  line_count             int NOT NULL CHECK(line_count >= 0),
  filename               varchar(200) NOT NULL,
  content_bytes          int NOT NULL CHECK(content_bytes >= 0),
  -- 导出内容的 SHA-256（十六进制，64 字符）。
  content_hash           char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  actor_id               uuid,
  occurred_at            timestamptz NOT NULL,
  UNIQUE(tenant_id, id)
);

CREATE INDEX finance_report_export_event_by_time
  ON finance_report_export_event(tenant_id, occurred_at DESC, id);
CREATE INDEX finance_report_export_event_by_report
  ON finance_report_export_event(tenant_id, report_type, occurred_at DESC);

-- ===========================================================================
-- 第三节：DB 层守卫（服务层与直连 SQL 都绕不过）
-- ===========================================================================

-- 模板**不可物理删除**：停用（enabled=false）才是改口径的方式。
-- 理由与 12.5.1.1 的科目一致：模板一旦被历史报表引用，删掉会让「当时的报表口径」无法复现。
CREATE OR REPLACE FUNCTION guard_finance_report_template_no_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '报表模板 % 不可删除：停用（enabled=false）才是改口径的方式', OLD.line_code
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_report_template_no_delete
  BEFORE DELETE ON finance_report_template
  FOR EACH ROW EXECUTE FUNCTION guard_finance_report_template_no_delete();

-- ===========================================================================
-- 第四节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_report_template','finance_report_export_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_report_template, finance_report_export_event TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  finance_report_template, finance_report_export_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;

-- 「授权在前、收回在后」：
--   * 导出留痕是**举证凭据**，只能追加；
--   * 模板的删除由触发器挡住（这里是**收回** DELETE，双保险）。
REVOKE UPDATE,DELETE ON finance_report_export_event FROM finance_app;
REVOKE DELETE ON finance_report_template FROM finance_app;

-- ===========================================================================
-- 第五节：交接面（写给 12.5.1.1 / 12.5.1.2 / 17 审计）
-- ===========================================================================
--
-- ① **12.5.1.1 会计科目（已交付，V356）—— 本模块读它的分类，但仍是软引用**：
--    本模块的报表分类口径取自 `finance_account_subject.category` +
--    `balance_direction`（**LEFT JOIN**，科目没登记 ⇒ 归「未分类」段落，不并入合计）。
--    🔴 **本模块不建指向它的外键**：`finance_voucher_line.subject_code` 是**快照**，
--       手工凭证允许带科目表里没有的编码 ⇒ 建 FK 会让 12.5.2.1 的既有数据写不进去。
--       ⇒ 因此本模块**不声称**校验过科目存在性（能力声明里如实写清）。
--
-- ② **12.5.1.2 会计期间（未交付）—— 本模块只读 `finance_voucher_period`**：
--    12.5.2.1 的那张期间表是过渡期 bootstrap（只能关、不能重开）。
--    本模块的「期间范围」= 那张表里的期间**序列**（from ~ to），
--    12.5.1.2 交付后接管该表（补 fiscal 周期 / 重开审批）⇒ **本模块结构不用改**：
--    本模块只读 `period_start / period_end / period_code`。
--
-- ③ **17 审计 / 对外报送（下游）**：
--    本模块的导出留痕（`finance_report_export_event`，append-only + SHA-256）
--    就是给审计与对外报送留的举证凭据 —— 对外报送前「导出了哪一份、什么口径、什么时候」
--    说得清，且事后可验证文件未被替换。
