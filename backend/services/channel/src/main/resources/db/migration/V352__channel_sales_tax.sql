-- ===========================================================================
-- 16.3.2.3 销售税与 Marketplace Facilitator —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 168-174 行）：
--   ### 16.3.2.3 销售税与平台代收
--   - 职责：处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、
--           免征额、按平台的代扣代缴说明与对账。
--   - 输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
--   - 输出：各州税务状态 + 代扣代缴对账结果。
--   - 关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
--           **平台代扣代缴不等于免税**，卖家的申报义务依然存在，只是由平台代收代缴，
--           需要正确对账。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块存在的理由：**「应缴」与「已代缴」必须是两条金额线**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是硬要求，不是「页面上显示一个净额」。绝大多数卖家把 Marketplace Facilitator
-- 理解成「平台替我交了 ⇒ 我不用交」—— 这恰恰是最贵的误解：
-- **代扣代缴只是把「谁来收」换了，申报义务没消失**。
-- 所以本模块的数据模型**刻意不设**「销售税净额」这一列：
--
--   * `self_taxable_sales`  —— **自营应税销售额**（自营那条线，来源 12.3.1.2 交易明细）
--   * `exempt_sales`        —— 落在免征额内的部分（不是免税，是**还没到起征点**）
--   * `taxable_base`        —— 真正的应税基数
--   * `expected_tax`        —— **应缴**（卖家自己欠税局的数）
--   * `facilitator_tax`     —— **已由平台代扣代缴**（平台替收的那笔）
--   * `variance`            —— 差异 = 应缴 − 已代缴
--
-- 只记一个净额的话，「平台少收了我得补缴」与「平台多收了我要退款」会被压成同一个 0。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.3.2.3）⇒ `sales_tax_*`。
--   与既有 `tax_registration_*`（16.3.1.1）、`epr_*`（16.3.2.2）、
--   `compliance_rule_*`（16.2.1.1）、`compliance_intercept_*`（16.2.1.2）、
--   `ip_risk_*`（16.4.1.1）、`brand_authz_*` / `brand_cred_*`（16.4.2.x）
--   **彼此隔离**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 州码不写死：复用 16.3.1.1 已有的 `tax_registration_jurisdiction`
-- ---------------------------------------------------------------------------
-- 本仓**已经**有一张销售辖区码表（V312，16.3.1.1 建的），里面已经把美国 50 州 + DC
-- 以 `code='US-XX'` / `subdivision_code='US-XX'` / `tax_regime='SALES_TAX'` 的形式落库。
-- 本模块**直接复用它**（触发器钉死引用），**不另建一张州表**，
-- **更不许在 Java 里写死一张州税率常量表冒充业务规则**（见第四节）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 税率阈值与免征额是**可配置、可版本化的规则数据**，不是代码常量
-- ---------------------------------------------------------------------------
-- 「加州 7.25%」「内华达 100 万美元经济联系阈值」这类数字**会变**，而且因州因时而异。
-- 把它写成 Java 常量表 = 把会变的业务规则焊死在代码里，改一次要发一次版，
-- 而且**查不出「当时用的是哪一版」**。
-- 所以：
--   * 规则落在 `sales_tax_rate_rule`（租户级事实，可录入、可查、可改版）；
--   * **一个州可以有多版规则**（`rule_version` + `effective_from` / `effective_to`）；
--   * 生效区间**不许重叠**（触发器钉死）；
--   * **已封版（SUPERSEDED）的规则内容冻结**（触发器钉死）；
--   * 对账结果**快照**当时用的版本号与税率 ⇒ 事后改规则**改不动历史判定**
--     （蓝图「关键约束」第 2 条：规则变更不能追溯改变历史判定）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V300 / V308 / V312 / V314 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 数据范围（RESTRICTIVE）**只套在店铺维度的表上**（`sales_tax_shop_state`），
--   其余是**租户级合规事实**（税率规则、代缴说明、对账结果），只有 isolation 策略 ——
--   与 V312 / V314 同一口径。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 与同卷 V300 / V308 / V311 / V312 / V314 同一纪律：外键会打破同服务既有测试的 TRUNCATE。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 金额与数量纪律
-- ---------------------------------------------------------------------------
-- 金额与数量一律 `numeric(19,6)` + `BigDecimal`（**禁浮点**），币种 `char(3)` 必带。
-- 税率 `numeric(9,6)`（税率是小数率，6 位足够；不足 6 位补零）。
-- 汇总**按币种分组**，**不同币种绝不直接相加**。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
-- ===========================================================================

-- ===========================================================================
-- 一、sales_tax_rate_rule · 销售税率规则（按州 × 生效期间，版本化）
-- ===========================================================================
-- 🔴 一个租户在同一个州可以有多版规则；生效区间是**左闭右开** [effective_from, effective_to)，
--   effective_to 为 NULL 表示「当前这版还没被换掉」。
--
-- 🔴 规则是**租户级事实**：同一个州，不同租户可能有不同的登记形态（自营发货 / FBA / 免税），
--   阈值与免征额可能不同 ⇒ 规则不能做成全局码表，必须带 tenant_id。
CREATE TABLE sales_tax_rate_rule (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 🔴 州码：必须真实落在 tax_registration_jurisdiction 且 tax_regime='SALES_TAX'
  --   且是**州一级**（subdivision_code 非空 ⇒ US-XX），触发器钉死，不留自由文本。
  state_code          varchar(16) NOT NULL,
  -- 版本号（同一州内递增，从 1 开始）
  rule_version        int NOT NULL CHECK(rule_version >= 1),
  -- 生效区间（左闭右开）
  effective_from      date NOT NULL,
  effective_to        date,
  -- 州基准税率（小数率，0.0725 = 7.25%）
  base_rate           numeric(9,6) NOT NULL CHECK(base_rate >= 0 AND base_rate <= 1),
  -- 州级阈值（销售额 / 经济联系阈值）：达到才产生申报义务
  threshold_amount    numeric(19,6) NOT NULL DEFAULT 0 CHECK(threshold_amount >= 0),
  -- 免征额：低于此额的部分不产生应缴（**不是免税**，是还没到起征点）
  exemption_amount    numeric(19,6) NOT NULL DEFAULT 0 CHECK(exemption_amount >= 0),
  -- 该州是否实施 Marketplace Facilitator（平台代扣代缴制度）
  marketplace_facilitator boolean NOT NULL DEFAULT false,
  -- 申报周期（与 16.3.1.1 的 filing_cycle 同口径）
  filing_cycle        varchar(16) NOT NULL DEFAULT 'QUARTERLY'
                      CHECK(filing_cycle IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  -- ACTIVE = 当前这版；SUPERSEDED = 已被新版本接替（内容冻结）；ARCHIVED = 撤下
  status              varchar(16) NOT NULL DEFAULT 'ACTIVE'
                      CHECK(status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
  note                varchar(500) NOT NULL DEFAULT '',
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, state_code, rule_version),
  -- 同一州的生效起始日唯一（两个版本不能从同一天开始）
  UNIQUE(tenant_id, state_code, effective_from),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE sales_tax_rate_rule IS
  '16.3.2.3 · 销售税率规则（租户级，按州 × 生效期间版本化）。'
  '税率 / 州级阈值 / 免征额是**可配置的业务规则数据**，不许写死在代码里。'
  '生效区间左闭右开且不许重叠；SUPERSEDED 版本内容冻结；对账结果快照当时用的版本。';

CREATE INDEX sales_tax_rate_rule_state
  ON sales_tax_rate_rule(tenant_id, state_code, effective_from DESC);
CREATE INDEX sales_tax_rate_rule_active
  ON sales_tax_rate_rule(tenant_id, state_code)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 二、sales_tax_shop_state · 店铺的州级销售税关系（nexus）
-- ===========================================================================
-- 🔴 这是「店铺所在州数据」的**归属地**：一家店在哪个州有销售税联系（物理存在 / 库存存放 /
--   经济联系），决定了该州的销售额要不要算进自营应税。
--
-- 🔴 状态码用 `tax_registration_record`（16.3.1.1）**同库只读**：本模块**不写**它、
--   也**不把**它那个「辖区级登记号」复制一份当自己的权威源 ——
--   上游是「这个主体在这个辖区有没有登记」的**粗**粒度档案，本表是「哪几家店在哪几个州有联系」的
--   **细**粒度事实，两者是「粗 / 细两层」。
CREATE TABLE sales_tax_shop_state (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 本服务自家 shop.id（触发器钉死：必须在本租户的店铺目录里真实存在）
  shop_id           uuid NOT NULL,
  -- 州码（触发器钉死：tax_registration_jurisdiction 的州一级行）
  state_code        varchar(16) NOT NULL,
  -- 联系类型：PHYSICAL_PRESENCE 物理存在 / INVENTORY_STORED 库存存放 /
  --           ECONOMIC_NEXUS 经济联系 / MARKETPLACE_FACILITATOR 仅平台代收
  nexus_type        varchar(32) NOT NULL
                    CHECK(nexus_type IN ('PHYSICAL_PRESENCE','INVENTORY_STORED',
                                         'ECONOMIC_NEXUS','MARKETPLACE_FACILITATOR')),
  -- 该州是否已登记（销售税账号）
  registered        boolean NOT NULL DEFAULT false,
  registration_no   varchar(64) NOT NULL DEFAULT '',
  -- 登记生效日（**date**，不是时刻：销售税登记是按天算的）
  registered_from   date,
  note              varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一家店在一个州只有一条联系记录（重录 = 改这一行，不是插第二条）
  UNIQUE(tenant_id, shop_id, state_code)
);
COMMENT ON TABLE sales_tax_shop_state IS
  '16.3.2.3 · 店铺的州级销售税联系（nexus）。决定该州的自营应税销售额怎么归集。'
  'registered / registration_no 是**该店在该州**的登记事实，与 16.3.1.1 的辖区级登记档案是两层。';

CREATE INDEX sales_tax_shop_state_state
  ON sales_tax_shop_state(tenant_id, state_code);
CREATE INDEX sales_tax_shop_state_shop
  ON sales_tax_shop_state(tenant_id, shop_id);

-- ===========================================================================
-- 三、sales_tax_facilitator_scope · 按平台的代扣代缴说明（州 × 平台）
-- ===========================================================================
-- 🔴 蓝图输入点名「按平台的代扣代缴说明」：同一个州，Amazon 代收、eBay 代收、Walmart 不代收，
--   汇缴给谁、要不要单独报表 —— 这些是**逐（州 × 平台）**的业务事实，不能写成一句通用说明。
CREATE TABLE sales_tax_facilitator_scope (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  state_code          varchar(16) NOT NULL,
  platform_code       varchar(64) NOT NULL,
  -- 该平台在该州是否代扣代缴
  facilitator_enabled boolean NOT NULL DEFAULT false,
  -- 汇缴对象（代收的钱交给谁）
  remit_to            varchar(200) NOT NULL DEFAULT '',
  -- 是否需要单独提交代扣代缴报表
  report_required     boolean NOT NULL DEFAULT true,
  -- 说明正文（申报口径、豁免、注意事项）
  note                varchar(1000) NOT NULL DEFAULT '',
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, state_code, platform_code)
);
COMMENT ON TABLE sales_tax_facilitator_scope IS
  '16.3.2.3 · 按平台的代扣代缴说明（州 × 平台）。'
  '说明的是「平台在这个州代不代收、汇给谁、要不要单独报表」—— 不是税率本身（税率在 sales_tax_rate_rule）。';

CREATE INDEX sales_tax_facilitator_scope_state
  ON sales_tax_facilitator_scope(tenant_id, state_code);

-- ===========================================================================
-- 四、sales_tax_facilitator_report · 平台代扣代缴报表（**上传原文 + 解析版本**）
-- ===========================================================================
-- 🔴 平台代扣代缴报表是**外部事实**：原文要留着，改一次要能说清「当时那一份长什么样」。
--   同一份 file_key 每重新解析一次，parse_version +1
--   （UNIQUE(tenant_id, file_key, parse_version)）。
--
-- 🔴 与 finance 的 `finance_settlement_source_file`（12.3.1.1 平台结算报告）**刻意分开**：
--   那张表记的是「平台结算报告」，这里记的是「平台代扣代缴报表」—— 两份文档、两种语义。
CREATE TABLE sales_tax_facilitator_report (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 平台编码（本服务 platform 目录里的 code）
  platform_code     varchar(64) NOT NULL,
  -- 报表身份（平台报表号 / 上传文件名）
  file_key          varchar(160) NOT NULL,
  file_name         varchar(200) NOT NULL DEFAULT '',
  content_type      text NOT NULL CHECK(content_type IN ('CSV','JSON')),
  -- **原始内容**（原文，一字不改）
  raw_content       text NOT NULL,
  -- 内容指纹（sha256 十六进制）
  content_hash      varchar(64) NOT NULL,
  byte_size         bigint NOT NULL DEFAULT 0 CHECK(byte_size >= 0),
  -- 解析版本号：同一 file_key 每重新解析一次 +1
  parse_version     int NOT NULL CHECK(parse_version >= 1),
  -- PARSED = 解析成功并已落库；REJECTED = 文件级错误，整份没进来
  parse_status      text NOT NULL CHECK(parse_status IN ('PARSED','REJECTED')),
  -- 解析告警（逐条：第几行 + 为什么）
  parse_warnings    jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 报表覆盖的申报期间
  period_start      date NOT NULL,
  period_end        date NOT NULL,
  uploaded_by       uuid,
  parsed_at         timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, file_key, parse_version),
  CHECK(period_end >= period_start)
);
COMMENT ON TABLE sales_tax_facilitator_report IS
  '16.3.2.3 · 平台代扣代缴报表（上传原文 + 内容指纹 + 解析版本号）。'
  '原文一字不改地留着：改一次要能说清「当时那一份长什么样」。';

CREATE INDEX sales_tax_facilitator_report_key
  ON sales_tax_facilitator_report(tenant_id, file_key, parse_version DESC);
CREATE INDEX sales_tax_facilitator_report_period
  ON sales_tax_facilitator_report(tenant_id, platform_code, period_start, period_end);

-- ===========================================================================
-- 五、sales_tax_facilitator_line · 代扣代缴报表明细行（州 × 平台）
-- ===========================================================================
-- 🔴 一行 = 某份报表里的「某州 × 某平台」的一笔代扣代缴汇总。
--   source_line_no 是**文件里的行号**，不是自增 id —— 逐行核对时要说得出「第几行」，
--   解析告警也指着同一个行号。
CREATE TABLE sales_tax_facilitator_line (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  report_id         uuid NOT NULL,
  state_code        varchar(16) NOT NULL,
  platform_code     varchar(64) NOT NULL,
  -- 报表明细可选带店铺编码（平台报表按店拆行时用得上）
  shop_code         varchar(64) NOT NULL DEFAULT '',
  -- 应税销售额（平台口径）
  taxable_amount    numeric(19,6) NOT NULL DEFAULT 0 CHECK(taxable_amount >= 0),
  -- 平台已代扣代缴的税额
  tax_collected     numeric(19,6) NOT NULL DEFAULT 0 CHECK(tax_collected >= 0),
  -- 订单笔数
  order_count       bigint NOT NULL DEFAULT 0 CHECK(order_count >= 0),
  currency          char(3) NOT NULL,
  source_line_no    int NOT NULL CHECK(source_line_no >= 1),
  remark            varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一份报表里行号唯一（重解析一份新版本报表是另一份 report_id）
  UNIQUE(tenant_id, report_id, source_line_no)
);
COMMENT ON TABLE sales_tax_facilitator_line IS
  '16.3.2.3 · 代扣代缴报表明细行（州 × 平台）。source_line_no = 文件里的行号，逐行核对指着同一个号。';

CREATE INDEX sales_tax_facilitator_line_report
  ON sales_tax_facilitator_line(tenant_id, report_id);
CREATE INDEX sales_tax_facilitator_line_state
  ON sales_tax_facilitator_line(tenant_id, state_code, currency);

-- ===========================================================================
-- 六、sales_tax_reconciliation · 代扣代缴对账结果（州 × 期间 × 币种）
-- ===========================================================================
-- 🔴🔴 **两条金额线同时存在**（本模块存在的理由，见第一节）：
--   expected_tax    —— 应缴（卖家欠税局的）
--   facilitator_tax —— 已由平台代扣代缴（平台替收的）
--   variance        —— 差异 = 应缴 − 已代缴
-- **不设「销售税净额」这一列**：压成净额就看不出「平台少收了」与「平台多收了」。
--
-- 🔴 **快照当时用的规则**：rule_version / base_rate / threshold_amount / exemption_amount
--   一起落库 ⇒ 事后改规则**改不动历史判定**（蓝图关键约束第 2 条）。
--
-- 🔴 金额与数量一律 numeric(19,6)，币种 char(3) 必带，**不同币种绝不直接相加**。
CREATE TABLE sales_tax_reconciliation (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  state_code            varchar(16) NOT NULL,
  period_start          date NOT NULL,
  period_end            date NOT NULL,
  currency              char(3) NOT NULL,
  -- 自营应税销售额（来源：12.3.1.2 交易明细，ORDER_SALES 行）
  self_taxable_sales    numeric(19,6) NOT NULL DEFAULT 0 CHECK(self_taxable_sales >= 0),
  -- 落在免征额内的部分（**不是免税**，是还没到起征点）
  exempt_sales          numeric(19,6) NOT NULL DEFAULT 0 CHECK(exempt_sales >= 0),
  -- 应税基数 = 自营应税销售额 − 免征额内部分
  taxable_base          numeric(19,6) NOT NULL DEFAULT 0 CHECK(taxable_base >= 0),
  -- 应缴（卖家自己欠税局的数）
  expected_tax          numeric(19,6) NOT NULL DEFAULT 0,
  -- 已由平台代扣代缴（平台替收的数，**平台代扣代缴报表口径**）
  facilitator_tax       numeric(19,6) NOT NULL DEFAULT 0 CHECK(facilitator_tax >= 0),
  -- 结算单口径的平台代扣税（12.3.1.2 的 WITHHOLDING_TAX 行）—— **交叉验证用**，
  -- 不参与 variance 计算：它与报表口径不一致，本身就是「要查」的信号。
  settlement_tax        numeric(19,6) NOT NULL DEFAULT 0 CHECK(settlement_tax >= 0),
  -- 差异 = 应缴 − 已代缴（**正数 = 平台少收了，卖家要补缴**）
  variance              numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 规则快照：当时用的是哪一版、什么税率、什么阈值与免征额
  rule_version          int NOT NULL,
  base_rate             numeric(9,6) NOT NULL,
  threshold_amount      numeric(19,6) NOT NULL DEFAULT 0,
  exemption_amount      numeric(19,6) NOT NULL DEFAULT 0,
  -- 期间内该州是否发过新版规则（true ⇒ 税率跨版本，需人工复核）
  rate_changed_in_period boolean NOT NULL DEFAULT false,
  -- BALANCED 差异为 0 / VARIANCE 有差异 / BELOW_THRESHOLD 未达阈值（无义务）/
  -- NO_ACTIVITY 期间无销售也无代缴 / RULE_MISSING 没有生效规则（判不了）/
  -- NEXUS_MISSING 该州没登记任何店铺的销售税联系（自营销售额无法归集，判不了）
  status                varchar(24) NOT NULL
                        CHECK(status IN ('BALANCED','VARIANCE','BELOW_THRESHOLD',
                                         'NO_ACTIVITY','RULE_MISSING','NEXUS_MISSING')),
  -- 该州有销售税联系的店铺数（0 ⇒ 自营应税销售额无法归集）
  shop_count            int NOT NULL DEFAULT 0,
  computed_at           timestamptz NOT NULL,
  computed_by           uuid,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个（州 × 期间 × 币种）只留一条对账结果（重算 = 覆盖这一条）
  UNIQUE(tenant_id, state_code, period_start, period_end, currency),
  CHECK(period_end >= period_start)
);
COMMENT ON TABLE sales_tax_reconciliation IS
  '16.3.2.3 · 代扣代缴对账结果（州 × 期间 × 币种）。'
  '**同时存「应缴」与「已代缴」两条金额线** —— 平台代扣代缴不等于免税，申报义务依然存在。'
  'rule_version / base_rate 是快照：事后改规则改不动历史判定。';

CREATE INDEX sales_tax_reconciliation_state
  ON sales_tax_reconciliation(tenant_id, state_code, period_end DESC);
CREATE INDEX sales_tax_reconciliation_period
  ON sales_tax_reconciliation(tenant_id, period_start, period_end);

-- ===========================================================================
-- 七、触发器
-- ===========================================================================
-- 🔴 (a) 州码必须真实落在 tax_registration_jurisdiction 的**州一级**销售税辖区里
--     （US-XX，tax_regime='SALES_TAX'）。**不留自由文本**。
CREATE FUNCTION guard_sales_tax_state_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM tax_registration_jurisdiction j
        WHERE j.code = NEW.state_code
          AND j.tax_regime = 'SALES_TAX'
          AND j.subdivision_code <> '') THEN
    RAISE EXCEPTION
      'Sales tax state % is not a known US state-level sales tax jurisdiction code', NEW.state_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_rate_rule_state_ref
  BEFORE INSERT OR UPDATE ON sales_tax_rate_rule
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_state_ref();
CREATE TRIGGER sales_tax_shop_state_state_ref
  BEFORE INSERT OR UPDATE ON sales_tax_shop_state
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_state_ref();
CREATE TRIGGER sales_tax_facilitator_scope_state_ref
  BEFORE INSERT OR UPDATE ON sales_tax_facilitator_scope
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_state_ref();
CREATE TRIGGER sales_tax_facilitator_line_state_ref
  BEFORE INSERT OR UPDATE ON sales_tax_facilitator_line
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_state_ref();
CREATE TRIGGER sales_tax_reconciliation_state_ref
  BEFORE INSERT OR UPDATE ON sales_tax_reconciliation
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_state_ref();

-- 🔴 (b) 店铺必须在本租户的店铺目录里真实存在（**零外键**的跨表引用校验）。
CREATE FUNCTION guard_sales_tax_shop_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM shop s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.shop_id) THEN
    RAISE EXCEPTION
      'Sales tax shop state references a shop that does not exist in this tenant: %', NEW.shop_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_shop_state_shop_ref
  BEFORE INSERT OR UPDATE ON sales_tax_shop_state
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_shop_ref();

-- 🔴 (c) 明细行必须挂在本租户真实存在的报表下。
CREATE FUNCTION guard_sales_tax_facilitator_line_report_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM sales_tax_facilitator_report r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.report_id) THEN
    RAISE EXCEPTION
      'Sales tax facilitator line references a report that does not exist in this tenant: %',
      NEW.report_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_facilitator_line_report_ref
  BEFORE INSERT OR UPDATE ON sales_tax_facilitator_line
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_facilitator_line_report_ref();

-- 🔴 (d) 同一州的规则生效区间**不许重叠**。
--     「两版规则同时生效」= 「当时该用哪一版」这个问题没有答案 ⇒ 触发器直接拒。
CREATE FUNCTION guard_sales_tax_rate_rule_no_overlap() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
       SELECT 1 FROM sales_tax_rate_rule r
        WHERE r.tenant_id = NEW.tenant_id
          AND r.state_code = NEW.state_code
          AND r.id <> NEW.id
          AND r.effective_from < COALESCE(NEW.effective_to, DATE '9999-12-31')
          AND COALESCE(r.effective_to, DATE '9999-12-31') > NEW.effective_from) THEN
    RAISE EXCEPTION
      'Sales tax rate rule effective window overlaps another version of the same state: %',
      NEW.state_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_rate_rule_no_overlap
  BEFORE INSERT OR UPDATE ON sales_tax_rate_rule
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_rate_rule_no_overlap();

-- 🔴 (e) 已封版（SUPERSEDED）或已撤下（ARCHIVED）的规则**内容冻结** ——
--     只允许改 effective_to 与 status（关窗口 + 标状态），别的字段一律不许改写。
--     「改一个数字就等于说谎」的防线。
CREATE FUNCTION guard_sales_tax_rate_rule_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('SUPERSEDED','ARCHIVED') AND (
       NEW.state_code IS DISTINCT FROM OLD.state_code
    OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
    OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
    OR NEW.base_rate IS DISTINCT FROM OLD.base_rate
    OR NEW.threshold_amount IS DISTINCT FROM OLD.threshold_amount
    OR NEW.exemption_amount IS DISTINCT FROM OLD.exemption_amount
    OR NEW.marketplace_facilitator IS DISTINCT FROM OLD.marketplace_facilitator
    OR NEW.filing_cycle IS DISTINCT FROM OLD.filing_cycle
    OR NEW.note IS DISTINCT FROM OLD.note) THEN
    RAISE EXCEPTION
      'Sales tax rate rule % is sealed (status=%); its rule facts cannot be rewritten', OLD.id, OLD.status
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_rate_rule_frozen
  BEFORE UPDATE ON sales_tax_rate_rule
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_rate_rule_frozen();

-- 🔴 (f) 代扣代缴报表的**解析凭据不可改写**：
--     原文、内容指纹、解析版本、解析状态一旦落库就不许改 ——
--     「这份报表当时被解析成了什么」是审计事实，不是可编辑字段。
CREATE FUNCTION guard_sales_tax_facilitator_report_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.file_key IS DISTINCT FROM OLD.file_key
     OR NEW.raw_content IS DISTINCT FROM OLD.raw_content
     OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
     OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
     OR NEW.parse_version IS DISTINCT FROM OLD.parse_version
     OR NEW.parse_status IS DISTINCT FROM OLD.parse_status
     OR NEW.content_type IS DISTINCT FROM OLD.content_type
     OR NEW.period_start IS DISTINCT FROM OLD.period_start
     OR NEW.period_end IS DISTINCT FROM OLD.period_end THEN
    RAISE EXCEPTION
      'Sales tax facilitator report % is an audit record; its parsed facts cannot be rewritten', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sales_tax_facilitator_report_immutable
  BEFORE UPDATE ON sales_tax_facilitator_report
  FOR EACH ROW EXECUTE FUNCTION guard_sales_tax_facilitator_report_immutable();

-- ===========================================================================
-- 八、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'sales_tax_rate_rule',
    'sales_tax_shop_state',
    'sales_tax_facilitator_scope',
    'sales_tax_facilitator_report',
    'sales_tax_facilitator_line',
    'sales_tax_reconciliation'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴 数据范围（RESTRICTIVE）：**只套在店铺维度的表上**（`sales_tax_shop_state`），
--   其余是租户级合规事实（税率规则、代缴说明、报表、对账结果）—— 与 V312 / V314 同一口径。
CREATE POLICY data_scope ON sales_tax_shop_state AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=sales_tax_shop_state.tenant_id
                  AND s.id=sales_tax_shop_state.shop_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=sales_tax_shop_state.tenant_id
                  AND s.id=sales_tax_shop_state.shop_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 九、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;
-- 租户业务事实：可读写
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_rate_rule TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_shop_state TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_facilitator_scope TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_facilitator_report TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_facilitator_line TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sales_tax_reconciliation TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V314 同口径）。
REVOKE ALL ON sales_tax_rate_rule FROM starcon_app;
REVOKE ALL ON sales_tax_shop_state FROM starcon_app;
REVOKE ALL ON sales_tax_facilitator_scope FROM starcon_app;
REVOKE ALL ON sales_tax_facilitator_report FROM starcon_app;
REVOKE ALL ON sales_tax_facilitator_line FROM starcon_app;
REVOKE ALL ON sales_tax_reconciliation FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- 🔴 **序列授权**：本迁移的六张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 销售税台账语义与 16.1.1.1 账号健康、
-- 16.2.2.1 合规预检、16.3.1.1 税务登记同族，复用 identity 已注册的
-- channel.operation.*（读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 十、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **2 渠道与店铺（上游，同库只读）**：本模块读本服务自己的 `shop`（店铺目录）
--     与 16.3.1.1 的 `tax_registration_record`（辖区级登记档案，**只读**）。
--     本模块**不写**上游的表，**不把**上游的登记号复制一份当自己的权威源。
--   * **12.3.1.2 交易明细（上游，跨服务授权 HTTP API）**：自营应税销售额
--     （ORDER_SALES 行）与平台代扣代缴（WITHHOLDING_TAX 行）经 finance 服务的
--     `GET /finance/transactions` 读。**禁止跨服务 SQL / 跨库 FK / 共享视图**。
--   * **12.6 申报（下游，未交付）**：本模块只产出「各州应缴 / 已代缴 / 差异」的**对账结论**，
--     **不建申报表、不做申报提交、不声称已申报**。下游 12.6 交付后直接消费
--     `sales_tax_reconciliation`。
--   * **16.3.2.1 VAT/IOSS**：未交付 ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计。
--   * **16.3.2.2 EPR**：已交付 ⇒ 两模块**互不写对方的表**（各自的命名空间与写所有权）。
-- ===========================================================================
