-- ===========================================================================
-- 16.3.2.1 VAT / IOSS —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 154-160 行）：
--   ### 16.3.2.1 VAT/IOSS
--   - 职责：管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、
--           IOSS 订单识别与代收代缴统计、与财务申报数据的对接。
--   - 输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
--   - 输出：IOSS 订单清单与金额统计；申报状态。
--   - 关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
--           **IOSS 订单与一般 VAT 订单必须区分统计**，混在一起申报是最常见的错误。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块存在的理由：**「IOSS 订单与一般 VAT 订单必须区分统计」必须真的落地**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是硬要求，不是「页面上给 IOSS 的单染个颜色」。本迁移把它落成**三道**：
--
--   * **判定层**：`vat_ioss_order.filing_scope` 是一列**受控枚举**（IOSS / GENERAL_VAT /
--     OUT_OF_SCOPE / UNRESOLVED），**每一张订单行都有一个结构化取值** —— 不是靠 UI 标签、
--     不是靠备注、不是靠人眼分辨。金额汇总一律 `group by filing_scope, currency`，
--     于是「IOSS 的钱」与「一般 VAT 的钱」在**取数层面**就是两组数。
--   * **理由层**：`reason_code` 把「为什么判成这一档」钉死（超门槛 / 应税消费品 / B2B /
--     店铺不在覆盖范围 / 税率读不到 …）⇒ 复核时能逐条追。
--   * **申报层**：`vat_filing_period` 按辖区开申报期，**状态只能前进**
--     （DRAFT 待提交 → DRAFTED 已出底稿 → CONFIRMED 已确认），由触发器钉死。
--
-- 🔴 **为什么用「三档 + 一档判不了」而不是两档**：
--   「读不到税率」「订单没有收货国家」「汇率读不到没法折算门槛」这三类**判不了**，
--   把它们塞进 IOSS 或一般 VAT 都是**假结论**。所以第四档 `UNRESOLVED` 独立存在，
--   它**不参与** IOSS 申报，但**必须**出现在清单与汇总里 —— 否则「少算了」看起来像「没有」。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 表命名空间（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.3.2.1）⇒ `vat_ioss_*` 与 `vat_filing_*`。
--   与既有 `tax_registration_*`（16.3.1.1）、`epr_*`（16.3.2.2）、
--   `sales_tax_*`（16.3.2.3）、`compliance_rule_*`（16.2.1.1）**彼此隔离**。
--
--   🔴 本迁移**只 CREATE 自己的新表**：零 ALTER 任何既有表、零 ADD COLUMN 到别人的表、
--   零 DROP 别人的表。跨车道 DDL 回归面 = 0。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V248 / V300 / V308 / V312 / V314 同一套写法。租户由
-- backend/libraries/tenancy 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头
-- 进不来也改不动它。
--
-- 🔴 **数据范围（RESTRICTIVE）本模块刻意不套**：IOSS 登记与申报期是**租户级合规事实**
--   （与法人维度的税务登记同理，V312 的注释已写明理由：法人的数据范围归 identity 所有，
--   本服务读不到，硬套店铺收敛会让「这个租户到底有没有 IOSS 登记」凭空消失）。
--   本模块的档案**不挂在任何一家店下面**（适用店铺是登记的**范围**，不是它的归属），
--   所以只有 isolation 策略。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 与同卷 V300 / V308 / V312 / V314 同一纪律：外键会打破同服务既有测试的 TRUNCATE。
-- 跨表引用校验改成 BEFORE 触发器。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 金额与数量纪律
-- ---------------------------------------------------------------------------
-- 金额与数量 `numeric(19,6)` + **币种** `char(3)`，**禁浮点**；
-- 税率 `numeric(9,6)`（0.19 = 19%）。
-- 汇总时**按币种分组**，**不许把不同币种直接相加**（EUR + USD 不是钱）。
--
-- 🔴 **税率列允许为 NULL，但 NULL 的含义是「读不到」** —— 不是 0%：
--   税率来自 finance 的 12.6.1.1（跨服务只读端口），读不到就是读不到，
--   绝不允许退化成 0% 或「免税」。`tax_rate_source` 把这件事写进数据本身。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
-- ===========================================================================

-- ===========================================================================
-- 一、vat_ioss_registration · 租户的 IOSS 登记（租户业务事实）
-- ===========================================================================
-- 🔴 **粒度：租户 × 一个 IOSS 号**。欧盟 IOSS 是**一站式**制度：一个 IOSS 号覆盖
--   全部欧盟成员国，所以这里**不是**「一个辖区一个号」（那是 16.3.1.1 的粗粒度层）。
--   本模块与 16.3.1.1 是**粗 / 细两层**，不是同一份事实的两个副本。
--
-- 🔴 **适用店铺与商品是登记的「范围」，不是登记的「归属」** ⇒ 各自一张子表
--   （下面第二节 / 第三节），登记行本身**不挂在任何一家店下面**。
CREATE TABLE vat_ioss_registration (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- IOSS 登记号（欧盟一站式编号）
  registration_no   varchar(64) NOT NULL CHECK(btrim(registration_no)<>''),
  -- 启用 / 停用（停用 = 这条登记不再作为 IOSS 判定依据）
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','DISABLED')),
  -- 生效窗口：effective_from NOT NULL；effective_to 为空 = 当前生效的登记。
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz,
  -- 中介服务机构（IOSS 常见形态：委托中介申报）。**不填就是自己申报**。
  intermediary_name varchar(200) NOT NULL DEFAULT '',
  -- 🔴 IOSS 免征门槛（intrinsic value，货值 + 运费）。
  --   默认 150 EUR —— 这是欧盟法规里的免征线，**做成可配置**而不是写死在代码里。
  --   🔴 门槛**带币种**：门槛永远是欧元口径，订单不是欧元时要折算 ⇒ 折算需要汇率，
  --   汇率读不到就**判不了**（UNRESOLVED），**不许拿一个猜的汇率凑**。
  threshold_amount  numeric(19,6) NOT NULL DEFAULT 150.000000 CHECK(threshold_amount > 0),
  threshold_currency char(3) NOT NULL DEFAULT 'EUR' CHECK(threshold_currency ~ '^[A-Z]{3}$'),
  note              varchar(500) NOT NULL DEFAULT '',
  recorded_by       uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE vat_ioss_registration IS
  '16.3.2.1 · 租户的 IOSS 登记（一个租户一个 IOSS 号，欧盟一站式）。'
  '与 16.3.1.1 的辖区级 VAT 登记是**粗 / 细两层**，本模块不写上游、不复制上游的号。'
  '门槛带币种：订单币种 ≠ 门槛币种时要折算，折算读不到汇率 ⇒ 判不了，不许拿猜的汇率凑。';

-- 🔴 一个租户**同时只能有一条当前生效的登记**（effective_to 为空的那条只能有一条）
CREATE UNIQUE INDEX vat_ioss_registration_current
  ON vat_ioss_registration(tenant_id) WHERE effective_to IS NULL;

CREATE INDEX vat_ioss_registration_effective
  ON vat_ioss_registration(tenant_id, effective_from DESC);

-- ===========================================================================
-- 二、vat_ioss_registration_shop · 登记的适用店铺（版本行的子表）
-- ===========================================================================
-- 🔴 **用 platform_code + shop_code 而不是 shop_id**：
--   店铺目录在本服务自己的 `shop` 表里（V1），而订单侧的店铺标识来自 trade 的
--   `platform_order.shop_code`（跨服务，**只存字符串**，不建跨库 FK）。
--   两边用同一个字符串口径 ⇒ 不跨库、不复制店铺主键、不造平行店铺表。
CREATE TABLE vat_ioss_registration_shop (
  tenant_id      uuid NOT NULL,
  registration_id uuid NOT NULL,
  platform_code  varchar(64) NOT NULL CHECK(btrim(platform_code)<>''),
  shop_code      varchar(64) NOT NULL CHECK(btrim(shop_code)<>''),
  PRIMARY KEY(tenant_id, registration_id, platform_code, shop_code)
);
COMMENT ON TABLE vat_ioss_registration_shop IS
  '16.3.2.1 · IOSS 登记的适用店铺。平台码 + 店铺码与 trade 的 platform_order.shop_code 同一口径；'
  '**零外键**，跨表引用由触发器校验。';

CREATE INDEX vat_ioss_registration_shop_code
  ON vat_ioss_registration_shop(tenant_id, platform_code, shop_code);

-- ===========================================================================
-- 三、vat_ioss_registration_goods · 登记的适用商品（版本行的子表）
-- ===========================================================================
-- 🔴 **「适用商品」为什么要显式登记**：IOSS 有一条硬规则 —— **应税消费品**
--   （酒、烟、能源）**不适用 IOSS**，哪怕货值没超过门槛。
--   系统不能从商品标题里猜「这是不是酒」 ⇒ 由租户在登记里逐个 SKU 标注。
--
-- 🔴 **没登记的 SKU 按「非应税消费品」处理**（不是「判不了」）：
--   标注是**显式动作**，不标就是默认不适用 —— 这条默认写进能力声明与页面。
--   标了 `excise_goods=true` 的 SKU 即使在覆盖范围内也**走一般 VAT**。
CREATE TABLE vat_ioss_registration_goods (
  tenant_id      uuid NOT NULL,
  registration_id uuid NOT NULL,
  sku_code       varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  goods_name     varchar(200) NOT NULL DEFAULT '',
  -- 是否应税消费品（酒 / 烟 / 能源）—— true ⇒ 该 SKU 不适用 IOSS，走一般 VAT
  excise_goods   boolean NOT NULL DEFAULT false,
  PRIMARY KEY(tenant_id, registration_id, sku_code)
);
COMMENT ON TABLE vat_ioss_registration_goods IS
  '16.3.2.1 · IOSS 登记的适用商品。excise_goods=true 的 SKU（酒 / 烟 / 能源）'
  '**不适用 IOSS**，走一般 VAT。未登记的 SKU 按非应税消费品处理。';

CREATE INDEX vat_ioss_registration_goods_code
  ON vat_ioss_registration_goods(tenant_id, sku_code);

-- ===========================================================================
-- 四、vat_ioss_filing_cycle · 申报周期配置（租户业务事实）
-- ===========================================================================
-- 🔴 **辖区码钉死在 16.3.1.1 的码表里**（`tax_registration_jurisdiction.code`，
--   如 EU-DE / EU-FR）—— 本模块**不另建辖区表**、**不写死欧盟 27 国清单**。
--   码表是**全局码表**（不带 tenant_id），本模块**只读**。
CREATE TABLE vat_ioss_filing_cycle (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  jurisdiction_code varchar(16) NOT NULL CHECK(btrim(jurisdiction_code)<>''),
  -- 申报周期：按月 / 按季 / 半年 / 年
  filing_cycle     varchar(16) NOT NULL
                   CHECK(filing_cycle IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
  -- 申报期起始月（1..12）。周期 + 起始月 ⇒ 能算出「本期申报期是哪段」。
  filing_start_month int NOT NULL CHECK(filing_start_month BETWEEN 1 AND 12),
  -- 申报截止日（当月几号）
  deadline_day     int NOT NULL CHECK(deadline_day BETWEEN 1 AND 28),
  status           varchar(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK(status IN ('ACTIVE','DISABLED')),
  note             varchar(500) NOT NULL DEFAULT '',
  recorded_by      uuid,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一个辖区同一周期**只允许一条生效配置**（换配置 = 先停用旧的）
  UNIQUE(tenant_id, jurisdiction_code, filing_cycle)
);
COMMENT ON TABLE vat_ioss_filing_cycle IS
  '16.3.2.1 · 申报周期配置（辖区 × 周期）。辖区码必须落在 16.3.1.1 的 '
  '`tax_registration_jurisdiction.code` 里（触发器钉死，不留自由文本）。';

CREATE INDEX vat_ioss_filing_cycle_jurisdiction
  ON vat_ioss_filing_cycle(tenant_id, jurisdiction_code, filing_cycle);

-- ===========================================================================
-- 五、vat_filing_period · 申报期（租户业务事实）+ 🔴 状态只能前进
-- ===========================================================================
-- 🔴 **申报状态的三档口径**（页面与能力声明里写的就是这三条）：
--   DRAFT     待提交 —— 本期刚开出来，还没出底稿
--   DRAFTED   已出底稿 —— 底稿已按「IOSS / 非 IOSS 分开」生成，等待人工核对
--   CONFIRMED 已确认 —— 人工已确认本期数据完整可核
--
-- 🔴 **本系统只保证数据完整可核，不代用户向税务机构提交申报**
--   ⇒ 刻意**没有** `SUBMITTED` / `FILED` 状态：那两个状态由用户在税务机构门户操作，
--   本仓任何模块都无权替用户声称「已向税务机构申报」。
--
-- 🔴 **状态只能前进**（触发器钉死）：DRAFT → DRAFTED → CONFIRMED，
--   不许回退、不许跳过。回退 = 「已确认的底稿被悄悄改回去」，那比没确认更危险。
CREATE TABLE vat_filing_period (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  jurisdiction_code varchar(16) NOT NULL CHECK(btrim(jurisdiction_code)<>''),
  -- 申报期区间（含首含尾）
  period_start      date NOT NULL,
  period_end        date NOT NULL,
  status            varchar(16) NOT NULL DEFAULT 'DRAFT'
                    CHECK(status IN ('DRAFT','DRAFTED','CONFIRMED')),
  drafted_at        timestamptz,
  confirmed_at      timestamptz,
  confirmed_by      uuid,
  note              varchar(500) NOT NULL DEFAULT '',
  recorded_by       uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(period_end >= period_start),
  -- 🔴 「已出底稿」必须带出底稿时刻；「已确认」必须带出底稿时刻 + 确认时刻 + 确认人。
  CHECK(status <> 'DRAFTED'  OR drafted_at IS NOT NULL),
  CHECK(status <> 'CONFIRMED' OR (drafted_at IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)),
  -- 🔴 一个辖区同一区间**只允许开一期**（防止同一段数据被开两期、两期各报一次）
  UNIQUE(tenant_id, jurisdiction_code, period_start, period_end)
);
COMMENT ON TABLE vat_filing_period IS
  '16.3.2.1 · 申报期与申报状态。DRAFT 待提交 → DRAFTED 已出底稿 → CONFIRMED 已确认，'
  '**状态只能前进**（触发器钉死）。刻意没有「已向税务机构提交」状态 —— '
  '本系统只保证数据完整可核，不代用户向税务机构提交申报。';

CREATE INDEX vat_filing_period_jurisdiction
  ON vat_filing_period(tenant_id, jurisdiction_code, period_start DESC);

CREATE INDEX vat_filing_period_status
  ON vat_filing_period(tenant_id, status, period_start DESC);

-- ===========================================================================
-- 六、vat_ioss_order · IOSS 订单识别结果（租户业务事实）🔴 统计的**唯一**来源
-- ===========================================================================
-- 🔴 **每一行都带一个结构化的 `filing_scope`** —— 这就是「IOSS 订单与一般 VAT 订单
--   必须区分统计」的物理落点。取数时 `group by filing_scope, currency` 即可分档，
--   **不需要**看颜色、不需要看标签、不需要人工分辨。
--
-- 🔴 **订单事实来自上游 trade（9 订单）**，本表是**本服务自己的识别结果台账**：
--   上游是**订单事实**的写者，本模块是**税务判定**的写者 —— 两个写者各写各的，
--   本表**不复制**上游的任何一列业务含义，只记判定要用到的字段 + 判定结果。
CREATE TABLE vat_ioss_order (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL,
  -- 上游 trade 的平台单投影 ID（**逻辑引用**，跨服务不建外键）
  source_order_id      uuid NOT NULL,
  -- 上游统一销售订单 ID（逻辑引用）
  source_sales_order_id uuid NOT NULL,
  source_order_no      varchar(64) NOT NULL,
  platform_code        varchar(64) NOT NULL,
  shop_code            varchar(64) NOT NULL,
  order_source         varchar(32) NOT NULL,
  -- 平台单归一状态（CANCELLED 的单不进申报统计）
  order_status         varchar(32) NOT NULL,
  -- 买家类型：INDIVIDUAL（B2C）/ ORGANIZATION（B2B）/ UNKNOWN（**上游没给**）
  -- 🔴 UNKNOWN 是独立一档：猜成 B2C 会把 B2B 单算进 IOSS ⇒ **判不了**，不许猜。
  customer_type        varchar(16) NOT NULL
                       CHECK(customer_type IN ('INDIVIDUAL','ORGANIZATION','UNKNOWN')),
  -- 收货国家（ISO 3166-1 alpha-2）。🔴 **允许为空** —— 订单没有收货国家是真实存在的
  --   （上游没采到 / 地址不全），那属于「判不了」（UNRESOLVED / NO_DESTINATION_COUNTRY），
  --   **不许**替它猜一个国家，也不许因为 CHECK 而写不进去。
  destination_country  char(2) NOT NULL
                       CHECK(destination_country = '' OR length(btrim(destination_country))=2),
  -- 辖区码（16.3.1.1 码表，如 EU-DE）；空 = 收货地不是本模块跟踪的辖区
  jurisdiction_code    varchar(16) NOT NULL DEFAULT '',
  order_date           date NOT NULL,
  currency             char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 🔴 金额一律 numeric，币种必带；汇总按币种分组，**不同币种绝不直接相加**
  goods_amount         numeric(19,6) NOT NULL DEFAULT 0 CHECK(goods_amount >= 0),
  shipping_amount      numeric(19,6) NOT NULL DEFAULT 0 CHECK(shipping_amount >= 0),
  -- 计税基数（货值 + 运费）
  tax_base             numeric(19,6) NOT NULL DEFAULT 0 CHECK(tax_base >= 0),
  -- 🔴 代收代缴税额（订单口径：平台 / 订单上实际收了多少税）
  tax_amount           numeric(19,6) NOT NULL DEFAULT 0 CHECK(tax_amount >= 0),
  -- 🔴 适用税率（来自 finance 12.6.1.1）。**允许为 NULL —— NULL 的含义是「读不到」**，
  --   绝不是 0%。`tax_rate_source` 把这件事写进数据本身。
  tax_rate             numeric(9,6) CHECK(tax_rate IS NULL OR tax_rate >= 0),
  tax_rate_source      varchar(16) NOT NULL DEFAULT 'UNAVAILABLE'
                       CHECK(tax_rate_source IN ('FINANCE','UNAVAILABLE')),
  -- 🔴 **结构化地区分**：IOSS / 一般 VAT / 不在范围 / 判不了
  filing_scope         varchar(16) NOT NULL
                       CHECK(filing_scope IN ('IOSS','GENERAL_VAT','OUT_OF_SCOPE','UNRESOLVED')),
  -- 为什么判成这一档（复核时逐条追）
  reason_code          varchar(32) NOT NULL,
  reason_detail        varchar(500) NOT NULL DEFAULT '',
  synced_at            timestamptz NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一张上游订单**只识别一次**（重复同步 = 改这一行，不追加第二条）
  UNIQUE(tenant_id, source_order_id),
  CHECK(btrim(source_order_no)<>'' AND btrim(shop_code)<>'')
);
COMMENT ON TABLE vat_ioss_order IS
  '16.3.2.1 · IOSS 订单识别结果台账（统计的唯一来源）。'
  'filing_scope 是**结构化地区分**：IOSS / GENERAL_VAT / OUT_OF_SCOPE / UNRESOLVED —— '
  '取数时 group by filing_scope, currency 即可把 IOSS 与一般 VAT 分开，**不许混在一起申报**。'
  'tax_rate 为 NULL 表示**税率读不到**，**不是 0%**。';

CREATE INDEX vat_ioss_order_scope
  ON vat_ioss_order(tenant_id, filing_scope, order_date DESC);

CREATE INDEX vat_ioss_order_currency
  ON vat_ioss_order(tenant_id, currency, order_date DESC);

CREATE INDEX vat_ioss_order_jurisdiction
  ON vat_ioss_order(tenant_id, jurisdiction_code, order_date DESC);

CREATE INDEX vat_ioss_order_shop
  ON vat_ioss_order(tenant_id, platform_code, shop_code, order_date DESC);

-- ===========================================================================
-- 七、vat_ioss_order_line · 订单行明细（商品粒度）
-- ===========================================================================
-- 🔴 **行明细是「商品粒度」的申报底稿**：行上有 SKU、数量、金额与税额，
--   「哪些商品适用 IOSS、哪些是应税消费品」才追得到单。
CREATE TABLE vat_ioss_order_line (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  order_id   uuid NOT NULL,
  line_no    int NOT NULL CHECK(line_no >= 1),
  sku_code   varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name   varchar(200) NOT NULL DEFAULT '',
  quantity   numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_price numeric(19,6) NOT NULL DEFAULT 0 CHECK(unit_price >= 0),
  line_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(line_amount >= 0),
  tax_amount numeric(19,6) NOT NULL DEFAULT 0 CHECK(tax_amount >= 0),
  -- 行税率（来自订单自身口径；NULL = 上游没给，**不是 0%**）
  tax_rate   numeric(9,6) CHECK(tax_rate IS NULL OR tax_rate >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, order_id, line_no)
);
COMMENT ON TABLE vat_ioss_order_line IS
  '16.3.2.1 · IOSS 订单行明细（商品粒度）。零外键：order_id 由触发器校验真实存在。';

CREATE INDEX vat_ioss_order_line_order ON vat_ioss_order_line(tenant_id, order_id, line_no);
CREATE INDEX vat_ioss_order_line_sku   ON vat_ioss_order_line(tenant_id, sku_code);

-- ===========================================================================
-- 八、触发器
-- ===========================================================================

-- 🔴 (a) 辖区码必须真实落在 16.3.1.1 的码表里（**不留自由文本**的物理落点）
CREATE FUNCTION guard_vat_ioss_jurisdiction_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM tax_registration_jurisdiction j WHERE j.code = NEW.jurisdiction_code) THEN
    RAISE EXCEPTION 'VAT/IOSS jurisdiction % is not a known jurisdiction code', NEW.jurisdiction_code
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_ioss_filing_cycle_jurisdiction_ref
  BEFORE INSERT OR UPDATE ON vat_ioss_filing_cycle
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_jurisdiction_ref();
CREATE TRIGGER vat_filing_period_jurisdiction_ref
  BEFORE INSERT OR UPDATE ON vat_filing_period
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_jurisdiction_ref();

-- 🔴 (b) 登记的适用店铺 / 适用商品必须挂在**真实存在的登记**上（**零外键**的引用校验）
CREATE FUNCTION guard_vat_ioss_registration_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM vat_ioss_registration r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.registration_id) THEN
    RAISE EXCEPTION
      'VAT/IOSS registration scope references a registration that does not exist in this tenant: %',
      NEW.registration_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_ioss_registration_shop_ref
  BEFORE INSERT OR UPDATE ON vat_ioss_registration_shop
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_registration_ref();
CREATE TRIGGER vat_ioss_registration_goods_ref
  BEFORE INSERT OR UPDATE ON vat_ioss_registration_goods
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_registration_ref();

-- 🔴 (c) 🔴 **已关闭的登记内容冻结**：改了就是「历史判定按当时那版算」被破坏。
--     关闭 = 把 effective_to 写上；关闭之后除 effective_to 外的任何改动都被拒。
CREATE FUNCTION guard_vat_ioss_registration_closed_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.effective_to IS NOT NULL THEN
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       OR NEW.registration_no IS DISTINCT FROM OLD.registration_no
       OR NEW.status IS DISTINCT FROM OLD.status
       OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
       OR NEW.threshold_amount IS DISTINCT FROM OLD.threshold_amount
       OR NEW.threshold_currency IS DISTINCT FROM OLD.threshold_currency
       OR NEW.intermediary_name IS DISTINCT FROM OLD.intermediary_name
       OR NEW.note IS DISTINCT FROM OLD.note THEN
      RAISE EXCEPTION
        'VAT/IOSS registration % is closed; its facts cannot be rewritten', OLD.id
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_ioss_registration_closed_immutable
  BEFORE UPDATE ON vat_ioss_registration
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_registration_closed_immutable();

-- 🔴 (d) 🔴 **登记号与生效时刻不可回改**：改了等于「把当初申报时用的号偷偷换掉」。
--     想换号 = 关闭当前登记 + 新建一条。
CREATE FUNCTION guard_vat_ioss_registration_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.registration_no IS DISTINCT FROM OLD.registration_no THEN
    RAISE EXCEPTION
      'VAT/IOSS registration_no is immutable (close this registration and create a new one instead)'
      USING ERRCODE='23514';
  END IF;
  IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION
      'VAT/IOSS registration effective_from is immutable (close this registration and create a new one instead)'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_ioss_registration_identity_immutable
  BEFORE UPDATE ON vat_ioss_registration
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_registration_identity_immutable();

-- 🔴 (e) 🔴 **申报状态只能前进**：DRAFT → DRAFTED → CONFIRMED，
--     不许回退、不许跳过。回退会让「已确认的底稿被悄悄改回去」。
CREATE FUNCTION guard_vat_filing_period_status_forward_only() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  rank_old int;
  rank_new int;
BEGIN
  rank_old := CASE OLD.status WHEN 'DRAFT' THEN 1 WHEN 'DRAFTED' THEN 2 WHEN 'CONFIRMED' THEN 3 END;
  rank_new := CASE NEW.status WHEN 'DRAFT' THEN 1 WHEN 'DRAFTED' THEN 2 WHEN 'CONFIRMED' THEN 3 END;
  IF rank_new < rank_old THEN
    RAISE EXCEPTION
      'VAT filing period status cannot move backwards (% -> %); a confirmed draft must not be reopened silently',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  IF rank_new > rank_old + 1 THEN
    RAISE EXCEPTION
      'VAT filing period status cannot skip a step (% -> %); draft the draft before confirming it',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'DRAFTED' AND NEW.drafted_at IS NULL THEN
    NEW.drafted_at := now();
  END IF;
  IF NEW.status = 'CONFIRMED' AND NEW.confirmed_at IS NULL THEN
    NEW.confirmed_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_filing_period_status_forward_only
  BEFORE UPDATE ON vat_filing_period
  FOR EACH ROW EXECUTE FUNCTION guard_vat_filing_period_status_forward_only();

-- 🔴 (f) 订单行必须挂在**真实存在的订单**上（**零外键**的引用校验）
CREATE FUNCTION guard_vat_ioss_order_line_ref() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM vat_ioss_order o
        WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.order_id) THEN
    RAISE EXCEPTION
      'VAT/IOSS order line references an order that does not exist in this tenant: %', NEW.order_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vat_ioss_order_line_ref
  BEFORE INSERT OR UPDATE ON vat_ioss_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_vat_ioss_order_line_ref();

-- ===========================================================================
-- 九、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['vat_ioss_registration','vat_ioss_registration_shop',
                             'vat_ioss_registration_goods','vat_ioss_filing_cycle',
                             'vat_filing_period','vat_ioss_order','vat_ioss_order_line'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 十、账号与授权
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
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_registration TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_registration_shop TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_registration_goods TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_filing_cycle TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_filing_period TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_order TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vat_ioss_order_line TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V314 同口径）。
REVOKE ALL ON vat_ioss_registration FROM starcon_app;
REVOKE ALL ON vat_ioss_registration_shop FROM starcon_app;
REVOKE ALL ON vat_ioss_registration_goods FROM starcon_app;
REVOKE ALL ON vat_ioss_filing_cycle FROM starcon_app;
REVOKE ALL ON vat_filing_period FROM starcon_app;
REVOKE ALL ON vat_ioss_order FROM starcon_app;
REVOKE ALL ON vat_ioss_order_line FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;

-- 🔴 **序列授权**：本迁移的七张表**全部用应用侧生成的 uuid 作主键**，
--   没有任何 serial / identity / sequence 列 ⇒ 本迁移**不需要**序列授权。

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— VAT/IOSS 合规台账语义与 16.3.1.1 税务登记、
-- 16.3.2.2 EPR、16.3.2.3 销售税同族，复用 identity 已注册的 channel.operation.*
-- （读 / 录 / 改），identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 十一、留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **12.6.1.1 税率配置（上游，finance 服务，跨服务只读端口）**：税率适用**不在本库**。
--     本模块经 `GET /finance/tax-rates/resolve`（**按生效时间取税率**，命中不到一律报错）
--     读取，**不复制**任何税率行、不建跨库 FK / 视图。读不到 ⇒ `tax_rate` 留 NULL +
--     `tax_rate_source='UNAVAILABLE'`，**绝不退化成 0%**。
--   * **9 订单（上游，trade 服务，跨服务只读端口）**：订单事实归 trade，本模块**只读**。
--     本表是**本服务自己的识别结果台账**，不复制上游的业务含义。
--   * **16.3.1.1 税号与辖区（同库只读）**：辖区码表 `tax_registration_jurisdiction`
--     **只读**，本模块**不写** `tax_registration_record`、**不另建**辖区表。
--   * **5.2.2.2 汇率（上游，catalog 服务，跨服务只读端口）**：门槛是欧元口径，
--     订单不是欧元时要折算 ⇒ 读 `GET /catalog/exchange-rates/latest`；
--     **读不到 ⇒ 判不了**（UNRESOLVED），不许拿猜的汇率凑。
--   * **12.6.1.2 申报底稿（下游，同批另一条车道，迁移 V364）**：方向上是**本模块在上游**
--     —— 本模块输出「IOSS 订单清单与金额统计」，那是 12.6.1.2 的**输入**。
--     该模块在本仓**尚未交付** ⇒ 本模块**不编造**它的底稿、不声称已与它对账；
--     能力声明里逐条列 `available=false` + `ownerModule` + 影响说明。
--   * **16.3.1.2 申报日历**：未交付 ⇒ 本模块**只算**申报期与状态，**不做**日历与截止提醒。
--   * **16.3.2.3 销售税**：**美国州级销售税归它**，本模块不碰、不重复建表。
-- ---------------------------------------------------------------------------
