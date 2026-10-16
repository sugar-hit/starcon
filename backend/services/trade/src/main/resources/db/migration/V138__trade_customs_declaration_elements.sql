-- ===========================================================================
-- 10.5.1.2 申报要素（归属 trade 服务）
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md:224-229）：
--   职责：维护申报所需的品名、规格材质、用途、品牌、数量、单价、币种、产地、
--         原产地规则（RCEP/优惠税率适用），支持按目的地国家差异化。
--   输入：3.7.1.2 目的地报关版本；批次实际信息。
--   输出：完整申报要素集；缺失项提示。
--   关系：上游 = 3.7.1.2；下游 = 10.5.1.1。
--         **申报品名不能照搬销售标题**，两者用途不同（前者为清关，后者为转化），
--         混用是常见被退单原因。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移约束到的既有模块 = 无。纯新建两张表。
-- ---------------------------------------------------------------------------
-- 零 ALTER、零触发器挂载到既有表、零 FK 指向别模块的表、零既有表 ADD COLUMN。
-- → 跨车道 DDL 回归面 = 0（与 trade V132 / V133 同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 零之二、既有 catalog「申报要素」与本表的边界（本文件最要紧的一节）
-- ---------------------------------------------------------------------------
-- catalog 侧**已经有**一套申报要素相关结构（V94 / V95），开工前逐条读过，边界如下。
-- 先说结论：**本表不重建那套，本表是它在「目的地 + 批次实际 + 缺失项提示」三个
-- 维度上的扩展**。catalog V95 第二节已经**明文拒绝**在目的地版本里复制申报要素，
-- 那正是本模块存在的理由（原文：「申报要素：**只能来自基础属性**，目的地版本**不复制**它」）。
--
--   ┌────────────────────────────┬──────────────┬──────────────┬────────────┐
--   │ 事实                       │ 归属         │ 载体         │ 维度       │
--   ├────────────────────────────┼──────────────┼──────────────┼────────────┤
--   │ HS 编码 / 税号             │ catalog      │ V94/V95      │ SKU + 目的地│
--   │ 归类口径的原产地           │ catalog      │ V94          │ SKU        │
--   │ 法定计量单位               │ catalog      │ V94          │ SKU        │
--   │ 基础申报品名               │ catalog      │ V94/V95      │ SKU + 目的地│
--   │ 基础申报要素清单（有序）   │ catalog      │ V94          │ **仅 SKU** │
--   │ 享惠资格 / 目的地是否承认 │ catalog      │ V94/V95      │ SKU + 目的地│
--   │ ── 以下三件 catalog 明确不做 ────────────────────────────────────── │
--   │ 按目的地差异化的申报要素集 │ **本模块**   │ 本文件       │ SKU+目的地  │
--   │ 批次实际（数量/单价/币种） │ **本模块**   │ 本文件       │ 批次        │
--   │ 缺失项提示（逐项可执行）   │ **本模块**   │ 现算（见五） │ SKU+目的地  │
--   └────────────────────────────┴──────────────┴──────────────┴────────────┘
--
-- 三条「catalog 不做」的理由逐条写实（不是「我们没做」，是「那边做了会出错」）：
--
--   1. **目的地维度**：V95 第二节已经把「目的地版本不覆盖申报要素」写成结构决定，
--      理由是「在本表复制一份要素 = 出现第二个事实来源，两个来源不一致时
--      『10 报关按哪份申报』变成没人能回答的问题」。那个论证对**目的地版本表**成立，
--      但它同时也留下了一个真空：**各国申报要素体系确实不同**（V95 自己写了这句话），
--      而那个差异在 catalog 侧无处安放。本表就是这个差异的**唯一**安放处，
--      且它读 catalog 的合成结果（授权 HTTP）而不是复制 catalog 的行。
--
--   2. **批次实际信息**（数量 / 单价 / 币种）：V94 零之三写明 catalog 只有
--      `declared_value_reference`，而且**明文禁止**下游把它当申报值用
--      （原文：「它绝不会被下游当成申报货值直接落库」）。理由是成交价是 10 报关的
--      事实、汇率是 12 财务的事实。本模块正是 10 报关的上游，**由它按批次落
--      真实数量与单价**才不违反那条纪律 —— 这是职责的自然归属，不是重复建设。
--
--   3. **缺失项提示**：V94 规则 A4 已经有一道闸，但它是对**基础属性 APPROVED 那一刻**
--      的一次性 DB 二元判定（缺任一 required 要素就报错），产出的是「通过 / 不通过」。
--      蓝图对本模块的输出句要的是「**缺失项提示**」—— 逐项点名缺了哪一项、为什么。
--      两者形状不同：一个是审核闸，一个是可执行工作清单。本模块的提示**现算**
--      （第五节论证），不落表。
--
-- **申报品名为什么不复用 catalog 的 declared_name**：
-- catalog 的 declared_name 是**归类口径**下的品名（配合 HS 编码供海关归类），
-- 并且它走的是 3.7.1.1 的审核闸。本模块的 declared_name 是**要写进这一批货的
-- 申报资料**的那一格，随目的地、随批次实际变化。蓝图加粗句「申报品名不能照搬
-- 销售标题」需要**一个能与销售标题比较的实体**才成立 —— catalog 侧没有销售标题
-- （销售标题在商品/渠道侧），所以那道闸只能在本模块落（第二节规则 C1）。
--
-- ---------------------------------------------------------------------------
-- 零之三、为什么 sku_id 是逻辑引用而不是外键
-- ---------------------------------------------------------------------------
-- SKU 的唯一写入所有者在 **catalog** 服务（3.1 商品 / 3.3 SKU）。AGENTS.md 明令
-- 禁止跨服务 FK，所以本表对 sku_id **不建外键**（与 V132 的
-- first_mile_shipment_leg_receipt 对 inventory_transfer 的处理同一取舍）。
-- 建集 / 发布时经 {@code CustomsElementSetPort} 走 catalog **授权只读 HTTP**
-- 核对（真实存在性 + 销售标题 + 合成申报资料），核对不过就拒绝。
-- 「逻辑引用 + 授权端点核对」两层都在，且没有一行跨库 SQL。

-- ===========================================================================
-- 一、两张表：申报要素集（头）与目的地补充要素项（有序子表）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. customs_element_set —— 一个 SKU 在一个目的地国家的一个版本的申报要素集
-- ---------------------------------------------------------------------------
-- UNIQUE(tenant_id,sku_id,destination_country_code,version_no) 与 V25 / V95 同一纪律：
-- 同 SKU 同目的地下版本号唯一。两行时「v2 是哪一个」无法回答，而区间判定会两边都算数。
CREATE TABLE customs_element_set (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,

  -- ===== 目的地（本模块存在的理由）=====
  -- 目的地国家。ISO 3166-1 alpha-2，两位大写字母。
  -- **用 varchar(2) 而不是 char(2)**：PostgreSQL 的 char(n) 是空白填充类型，
  -- 值在比较/拼接时带尾随空格，报错极难排查（V94 第 85–89 行 / V95 第 117–122 行）。
  destination_country_code varchar(2) NOT NULL,

  -- 版本号。同 SKU 同目的地内唯一（运营手打的「2026 春季 RCEP 版」）。
  -- **建后不可改**（触发器钉死，见规则 C6）。
  version_no integer NOT NULL CHECK(version_no>0),

  -- ==========================================================================
  -- 要素本体：九件。**全部可空** —— 可空不是宽松，是「还没填」与「填错」必须分开。
  -- ==========================================================================
  -- 为什么要可空：蓝图输出句要「**缺失项提示**」，而缺失项提示只有在**允许缺**时
  -- 才有对象。全部 NOT NULL 的话缺失提示恒为空集，那是一条永远返回「齐备」的假话。
  -- 齐备性由**发布闸**（规则 C4）+ 缺失项提示（现算）负责，不靠列的 NOT NULL。
  -- 本表所有「文本类」列一律用「空串 = 无」而不是 NULL（V94 / V95 同一口径），
  -- 免得下游每读一列都先判一次 null。

  -- ---- 1. 申报品名（**独立字段，独立校验**）----
  -- 蓝图加粗句「申报品名不能照搬销售标题」的落点。两条防线：
  --   * 服务层：建集 / 改集时经 port 取到**真实销售标题**（catalog skuName）后比对；
  --   * DB 层：sales_title_snapshot 存下当时的销售标题，规则 C1 的 CHECK 挡住
  --     「绕过服务层直接 SQL 照抄」。两层都在，理由见 C1。
  declared_name text NOT NULL DEFAULT '',

  -- 建集时记下的销售标题快照。**始终非空**（取不到销售标题就拒绝建集，
  -- 所以它不会是空串），长度上限 500 —— 销售标题比申报品名长得多。
  sales_title_snapshot text NOT NULL DEFAULT '',

  -- ---- 2. 规格材质 ----
  spec_material text NOT NULL DEFAULT '',
  -- ---- 3. 用途 ----
  usage text NOT NULL DEFAULT '',
  -- ---- 4. 品牌类型（自有品牌 / 贴牌 / 无品牌）----
  -- 与「品牌」分成两列：海关的品牌申报要素常问的是**类型**（例 US 的 brand type
  -- 要求区分 own brand / OEM / no brand），只报一个品牌名答不出这个问题。
  -- varchar(24) 装得下 NO_BRAND / OWN_BRAND / OEM 及其本地化取值；
  -- 取值域由 Java 侧 ElementSetBrandType 枚举持有并出现在 capabilities，
  -- **前端不抄一份**（照 10.4.1.1「风险承担方从前端不抄枚举」的纪律）。
  brand_type varchar(24) NOT NULL DEFAULT '',
  -- ---- 4b. 品牌 ----
  -- **即使无品牌也要填**（填「无品牌」或 N/A）：申报资料里「品牌」一栏空着
  -- 与「明确没有品牌」在海关侧是两个不同状态，所以它始终是必填格。
  brand text NOT NULL DEFAULT '',

  -- ---- 5. 数量 ----
  -- **批次实际信息**（蓝图输入句第二项）。numeric(19,6) 沿用 V85 第 227 行
  -- 报关明细行的同一刻度。可空 = 还没填；给了必须 > 0（0 数量没有申报意义，
  -- 而它会被下游当成「这批货申报 0 件」）。
  quantity numeric(19,6),

  -- ---- 6. 单价 ----
  -- 金额口径 numeric(19,4) + BigDecimal，**禁浮点**（与 V85 declared_value 第 230 行
  -- 同一刻度 —— 本表的 单价×数量 要能被 10.5.1.1 直接落进 declared_value，
  -- 两边刻度不同就等于让下游做一次静默四舍五入）。给���必须 > 0。
  unit_price numeric(19,4),

  -- ---- 7. 币种（ISO 4217，**不是自由文本**）----
  -- varchar(3) + DB CHECK 枚举。三位大写正则（V85 第 270 行的 `[A-Z]{3}`）
  -- **不够**：它会放行 ZZZ / XXX / ABC 这些根本不是货币的代码，
  -- 而一个填错的币种会让整票货值算错一个数量级。所以这里枚举真实 ISO 4217 码。
  -- Java 侧 ElementSetCurrency 枚举持有同一份清单，
  -- 由 CustomsElementSetIntegrationTest 的「枚举与 DB CHECK 逐项相等」一例钉住不漂移。
  currency varchar(3) NOT NULL DEFAULT '',

  -- ---- 8. 产地 ----
  -- 与 catalog 的 origin_country_code 是**同一个事实的两个口径**：catalog 那个是
  -- 归类口径（按 HS 规则判的），本模块这个是**这一票实际发出来的产地**
  -- （批次实际信息：同一 SKU 可能一部分自产、一部分委外生产，产地不同税则不同）。
  -- 两者不一致时发布被拒（规则 C5），而不是静默取其中一个。
  origin_country_code varchar(2) NOT NULL DEFAULT '',

  -- ---- 9. 原产地规则 + 优惠税率适用 ----
  -- 原产地规则代码（RCEP / CPTPP / CEPA / CO / FTA / GSP …）。
  -- **空串 = 不主张优惠**，不是 NULL（V94 preference_agreement 同一口径）。
  origin_rule_code varchar(32) NOT NULL DEFAULT '',
  -- 是否主张优惠税率。**与 origin_rule_code 双向一致**（规则 C3，两个方向都钉）：
  -- 只勾不填 → 海关无从适用税率，优惠静默失效而运营以为享惠（少缴的税会被追缴）；
  -- 只填不勾 → 下游同时收到「不能享惠」与「有协定」两个矛盾答案。
  preferential_rate_applicable boolean NOT NULL DEFAULT false,

  -- ==========================================================================
  -- 生效区间 [effective_from, effective_to)（规则 R1，语义与 V25 / V95 逐字一致）
  -- ==========================================================================
  -- 业务时间。**必须有生效区间**：原产地规则与优惠税率适用性是会变的
  -- （RCEP 降税清单逐年调整、各国税则定期换版），一份没有区间的申报要素集
  -- 永远「有效」，等于让三年前的税率口径被今天直接引用。
  effective_from timestamptz NOT NULL,
  -- NULL = 一直有效（+∞）。
  effective_to timestamptz,

  -- ==========================================================================
  -- 状态机与留痕（照 V25：草稿 → 发布，单向）
  -- ==========================================================================
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  -- 发布留痕。与 V25 published_by / published_at、V95 同一形状。
  published_by uuid,
  published_at timestamptz,

  -- 乐观锁凭据。与 sku_package / customs_attribute 同一套口径：
  -- 并发编辑两个人都提交时，后到的必须撞 409 而不是静默覆盖先到的申报事实。
  version bigint NOT NULL DEFAULT 0,

  -- note 是运营补充说明（例「DE 的 RCEP 降税清单口径已与报关行确认」），
  -- **不参与任何判定**：让自由文本进判定条件就等于给了一条绕过发布闸的旁路。
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同 SKU 同目的地同版本号只能有一行。
  UNIQUE(tenant_id,sku_id,destination_country_code,version_no),

  -- ---- 规则 R1：区间有序（左闭右开，to 必须严格大于 from）----
  CONSTRAINT customs_element_set_range_ordered
    CHECK (effective_to IS NULL OR effective_to > effective_from),

  -- ---- 目的地格式（与 V95 规则 B4/B5 同一口径）----
  -- 顺带把 ISO 3166-1 alpha-2 的形状钉住：两位大写字母。
  -- **不接受 '*'**：本表的目的地是申报资料本身的口径归属，不是筛选条件，
  -- 照搬 V85/V92 的 '*' 通配语法会把一份 CN 口径的资料申报到德国（V95 第五节完整论证）。
  CONSTRAINT customs_element_set_country_format
    CHECK (destination_country_code ~ '^[A-Z]{2}$'),

  -- ---- 产地格式 ----
  CONSTRAINT customs_element_set_origin_format
    CHECK (origin_country_code='' OR origin_country_code ~ '^[A-Z]{2}$'),

  -- ---- 数量：给了必须 > 0 ----
  CONSTRAINT customs_element_set_quantity_positive
    CHECK (quantity IS NULL OR quantity>0),
  -- ---- 单价：给了必须 > 0 ----
  CONSTRAINT customs_element_set_unit_price_positive
    CHECK (unit_price IS NULL OR unit_price>0),

  -- ---- 币种：空串（未填）或真实 ISO 4217 码 ----
  -- ⚠️ 这一串 IN 列表与 Java 侧 ElementSetCurrency 枚举**必须逐项一致**，
  -- 由 CustomsElementSetIntegrationTest 的对应一例钉死。改一边不改另一边会让
  -- 「前端下拉能选、DB 拒收」或反过来「DB 放行、前端选不到」。
  CONSTRAINT customs_element_set_currency_iso4217
    CHECK (currency='' OR currency IN (
      'AED','AUD','BRL','CAD','CHF','CLP','CNY','COP','CZK','DKK','EGP','EUR',
      'GBP','HKD','HUF','IDR','ILS','INR','JPY','KRW','KZT','MXN','MYR','NGN',
      'NOK','NZD','PEN','PHP','PLN','RUB','SAR','SEK','SGD','THB','TRY','TWD',
      'USD','VND','ZAR')),

  -- ---- 品牌类型：空串（未填）或受支持的取值 ----
  -- 与 Java 侧 ElementSetBrandType 枚举逐项一致（同一测试钉住）。
  CONSTRAINT customs_element_set_brand_type_domain
    CHECK (brand_type='' OR brand_type IN ('OWN_BRAND','OEM','NO_BRAND','NOT_APPLICABLE')),

  -- ---- 原产地规则代码：只允许大写字母 / 数字 / 下划线（长度上限见下）----
  -- 不枚举具体协定：协定清单是 16 合规的资产（照 V94 对 ISO 币种表的同一取舍），
  -- 这里只钉**形状**（不把自由文本放进来），取值域由 capabilities 暴露给界面。
  CONSTRAINT customs_element_set_origin_rule_code_format
    CHECK (origin_rule_code='' OR origin_rule_code ~ '^[A-Z0-9_]{1,32}$'),

  -- ---- 规则 C3：优惠适用性与原产地规则双向一致 ----
  -- 主张优惠就必须说清按哪个协定：不报协定海关无从适用税率。
  CONSTRAINT customs_element_set_preference_needs_rule
    CHECK (NOT preferential_rate_applicable OR btrim(origin_rule_code)<>''),
  -- 反向：填了协定却不主张，下游会拿到两个互相矛盾的答案。
  CONSTRAINT customs_element_set_rule_needs_preference
    CHECK (preferential_rate_applicable OR btrim(origin_rule_code)=''),

  -- ---- 规则 C1：申报品名不得照搬销售标题（DB 那一半）----
  -- 与 sales_title_snapshot 比。**忽略大小写与首尾空白**：「照搬」在现实里
  -- 经常是复制后顺手改了首字母大小写，那仍然是照搬。
  -- 空串放行：申报品名还没填是「缺失项」，由缺失项提示点名，不在这里报错 ——
  -- 在这里报错会让「先建集后补内容」这条正常路径直接走不通。
  CONSTRAINT customs_element_set_declared_name_not_sales_title
    CHECK (btrim(declared_name)=''
        OR lower(btrim(declared_name)) <> lower(btrim(sales_title_snapshot))),

  -- ---- 发布态一致性（规则 C2，方向与 V94 A3 / V95 B2 一致）----
  -- PUBLISHED ⟹ 有发布人、有发布时刻。没有发布人的「已发布」等于「有人按过发布」
  -- 这种无法证伪的说法，而这份资料会被 10.5.1.1 原样引用。
  CONSTRAINT customs_element_set_published_needs_actor
    CHECK (status<>'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),
  -- DRAFT ⟹ 无发布痕迹。带发布痕迹的 DRAFT 会让「哪些还没发布」失真：
  -- 运营看到一堆带发布时间的草稿，会以为它们已经在下游生效了。
  CONSTRAINT customs_element_set_draft_has_no_release
    CHECK (status<>'DRAFT' OR (published_by IS NULL AND published_at IS NULL)),

  -- ---- 长度上限 ----
  -- 申报品名 / 规格材质 / 用途 / 品牌会原样出现在报关单上，截断成另一个商品
  -- 比报错糟得多，所以一律报错不截断。
  CONSTRAINT customs_element_set_text_lengths
    CHECK (length(declared_name)<=500
       AND length(spec_material)<=500
       AND length(usage)<=500
       AND length(brand)<=200
       AND length(sales_title_snapshot)<=500
       AND length(note)<=1000)
);

-- ---------------------------------------------------------------------------
-- 2. customs_element_set_item —— 该目的地版本的**补充**申报要素项（有序）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么本表**不**是「一套申报要素」的本体：
-- 声明要素本体（品名/规格材质/用途/品牌/数量/单价/币种/产地/原产地规则）已经是
-- 上一张表上的**强类型列** —— 它们要过 NUMERIC 与 ISO 4217 的约束，存成无结构
-- 文本就等于把这些约束降级成应用层自觉，DB 层再也拦不住一个非法币种。
--
-- 那本表装的是什么：**该目的地特有的、在九件之外的补充要素**（有序）。
-- 各国申报要素编号体系不统一（常见 "01" 但也有 "A1" 形态，V94 第 202–204 行
-- 说明了这一点），这些额外项**结构随目的地变化**，钉成固定列等于拒掉一部分真实目的地。
--
-- 与 catalog V94 的 `customs_declaration_element` 的关系（**不是同一张表**）：
--   * V94 那张挂在**基础属性**上，是每个 SKU 的**基础**要素清单，全局只有一份；
--   * 本表挂在**本模块的要素集**上，是**这个目的地**的补充项，随版本变化。
-- 本模块**读** V94 的合成结果（授权 HTTP，见零之二），**不复制**它的行，
-- 也不对它做任何写 —— 复制会造出第二个事实来源（V95 第二节的论证）。
--
-- UNIQUE(tenant_id,element_set_id,ordinal)：同一位次不能有两行，
-- 两行时「这一位填什么」无法回答（V94 第 212–213 行同一纪律）。
CREATE TABLE customs_element_set_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  element_set_id uuid NOT NULL,

  -- 位次。**有序**是业务事实：海关按序比对申报要素，换顺序会被判填报不符。
  ordinal smallint NOT NULL,

  -- 要素代码（例 "A1" / "07"）。varchar(32) 而不是数字：
  -- 各国的申报要素编号并不统一是数字，收窄成数字等于把一部分目的地拒之门外
  -- （V94 第 202–204 行同一论证）。
  element_code varchar(32) NOT NULL,
  element_name text NOT NULL,
  element_value text NOT NULL DEFAULT '',

  -- 是否必填。**与 element_value 的空值是两件事**：一条 required 且已填的要素
  -- 和一条 required 但还空着的要素，在申报单上是两个完全不同的状态
  -- （V94 第 207–209 行同一口径）。本模块的缺失项提示正是靠这两者相减得出的。
  required boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一位次不能有两行。
  UNIQUE(tenant_id,element_set_id,ordinal),

  CONSTRAINT customs_element_item_ordinal_positive CHECK(ordinal>0),
  CONSTRAINT customs_element_item_code_format
    CHECK (element_code ~ '^[A-Za-z0-9._-]{1,32}$'),
  CONSTRAINT customs_element_item_name_not_blank CHECK(btrim(element_name)<>''),
  CONSTRAINT customs_element_item_lengths
    CHECK(length(element_code)<=32 AND length(element_name)<=200 AND length(element_value)<=500),

  -- 要素集被删则补充项一起删（不留孤儿项）。
  FOREIGN KEY(tenant_id,element_set_id)
    REFERENCES customs_element_set(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 二、索引（每条对应一个真实的查询路径）
-- ===========================================================================
-- 列表页默认排序（updated_at DESC, id DESC）——与 V94 customs_attribute_updated 同一形状。
CREATE INDEX customs_element_set_updated
  ON customs_element_set(tenant_id, updated_at DESC, id DESC);

-- 按状态筛选（DRAFT / PUBLISHED 两档），与列表排序共用一条。
CREATE INDEX customs_element_set_status
  ON customs_element_set(tenant_id, status, updated_at DESC, id DESC);

-- 按目的地筛选：**本模块最高频的筛选**（运营在「发往德国的这批货要素齐了吗」
-- 这个视图里工作），走这条部分索引。
CREATE INDEX customs_element_set_destination
  ON customs_element_set(tenant_id, destination_country_code, sku_id);

-- 发布队列：已发布行按生效区间查（10.5.1.1 取「此刻生效的那一份」）。
CREATE INDEX customs_element_set_effective
  ON customs_element_set(tenant_id, effective_from, effective_to, sku_id)
  WHERE status='PUBLISHED';

-- 缺失项提示的热点：要素集按 SKU 反查其补充项，与头表是 1:N。
CREATE INDEX customs_element_set_item_by_set
  ON customs_element_set_item(tenant_id, element_set_id, ordinal);

-- 按要素代码反查（例「全库 A1 项都填了没有」）。
CREATE INDEX customs_element_set_item_by_code
  ON customs_element_set_item(tenant_id, element_code, element_set_id);

-- ===========================================================================
-- 三、触发器兜底（纵深防线）
-- ===========================================================================
-- 上一层的 CHECK 可以被 `ALTER TABLE ... DROP CONSTRAINT` 或迁移脚本绕过，
-- 触发器不行（与 V21 / V22 / V23 / V25 / V28 / V94 / V95 同一纪律）。
-- 触发器那一遍负责给**可读的中文报错**（照 V94 guard_customs_attribute_review 的风格：
-- 说清后果，而不是只报一个约束名 —— 运营看到的是「这条会被退单」而不是「23514」）。
--
-- 三条本模块自己的不变量，编号 C1–C6 沿用 C 段（不复用 V94 的 A / V95 的 B）：

-- ---- guard_element_set —— C1 销售标题、C2 发布态、C3 优惠一致性、C6 版本号不可改 ----
CREATE FUNCTION guard_element_set() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- C1 的可读版本。放在最前面：品名错了，后面几条的报错都指错了地方。
 IF btrim(NEW.declared_name)<>'' THEN
   IF lower(btrim(NEW.declared_name)) = lower(btrim(NEW.sales_title_snapshot)) THEN
    RAISE EXCEPTION '申报品名「%」与销售标题完全相同：申报品名是给海关归类用的口径，
     销售标题是给顾客看的转化文案，两者用途不同，混用是常见被退单原因。
     请按清关口径重写（例如材质、用途、结构，而不是「爆款」「高颜值」这类转化用语）',
     NEW.declared_name USING ERRCODE='23514';
   END IF;
   IF length(btrim(NEW.declared_name)) < 2 THEN
    RAISE EXCEPTION '申报品名只有一个字符：它会原样出现在报关单上，
     海关无法据此归类，而归类不符会直接导致退单或罚款（扣货与罚款由卖家承担）'
     USING ERRCODE='23514';
   END IF;
 END IF;
 -- C3 的可读版本，两个方向都报。
 IF NEW.preferential_rate_applicable AND btrim(NEW.origin_rule_code)='' THEN
  RAISE EXCEPTION '勾了主张优惠税率就必须填原产地规则代码（RCEP / CPTPP 等）：
   海关要凭协定代码才能适用优惠税率，没有它这项优惠直接失效，
   而运营以为已经享惠 —— 少缴的税到年底可能被追缴' USING ERRCODE='23514';
 END IF;
 IF (NOT NEW.preferential_rate_applicable) AND btrim(NEW.origin_rule_code)<>'' THEN
  RAISE EXCEPTION '没勾主张优惠税率就不该填原产地规则代码（当前填了「%」）：
   这一行会同时告诉 10 报关「不能享惠」和 16 合规「有协定」，下游拿到两个互相矛盾的答案',
   NEW.origin_rule_code USING ERRCODE='23514';
 END IF;
 -- C2 的可读版本。
 IF NEW.status='PUBLISHED' AND (NEW.published_by IS NULL OR NEW.published_at IS NULL) THEN
  RAISE EXCEPTION '发布必须同时记录发布人与发布时刻：没有发布人的「已发布」等于
   「有人按过发布」这种无法证伪的说法，而这份资料会被 10.5.1.1 报关单原样引用'
   USING ERRCODE='23514';
 END IF;
 -- C6：版本号**永远**建后不可改（与 V95 第 332 行同一纪律）。
 -- 区间判定（规则 R4）按 (sku, 目的地, 区间) 命中至多一行，
 -- 改版本号等于让「当时报的是哪一版」这件事从此无法复现。
 IF TG_OP='UPDATE' AND NEW.version_no IS DISTINCT FROM OLD.version_no THEN
   RAISE EXCEPTION '版本号建后不可改（当前 %，试图改成 %）：报关资料事后要能回答
   「当时按哪一版报的」，改了版本号这个答案就永久丢失。要换请新建版本',
   OLD.version_no, NEW.version_no USING ERRCODE='23514';
 END IF;

 -- C7：**已发布只允许把生效终点收紧到一个仍在未来的时刻**（规则 R5，与 V95 的
 -- guard_destination_version_published 同一纪律）。
 -- 为什么要这条：10.5.1.1 已经把这一版抄进报关单了，事后再改内容或改起点，
 -- 就等于让「这批货当时报的是什么」与现在库里存的对不上 —— 海关事后复核必查这一项。
 -- 唯一的例外是**向前关闭**（把终点收紧）：那是「这一版从某个时刻起不再使用」，
 -- 不改写它覆盖过的任何历史区间。
 IF TG_OP='UPDATE' AND OLD.status='PUBLISHED' THEN
   IF NEW.status<>'PUBLISHED' THEN
     RAISE EXCEPTION '已发布的申报要素集不能撤回发布（目的国 % v%）：10.5.1.1 已按这一版
      生成申报资料，撤回会让「这批货当时按哪一版申报的」无从查证。要停止使用请让下一版接替它',
      OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
   END IF;
   IF NEW.destination_country_code IS DISTINCT FROM OLD.destination_country_code THEN
     RAISE EXCEPTION '已发布的申报要素集不能改目的地（% → %）：目的地决定这一版申报给哪个国家，
      换目的地等于把 A 国的申报资料报给了 B 国。要换请新建版本',
      OLD.destination_country_code, NEW.destination_country_code USING ERRCODE='23514';
   END IF;
   IF NEW.sku_id IS DISTINCT FROM OLD.sku_id THEN
     RAISE EXCEPTION '已发布的申报要素集（目的国 % v%）不能改归属 SKU：
      换 SKU 等于把 A 品的申报要素套到 B 品上',
      OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
   END IF;
   IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
     RAISE EXCEPTION '已发布的申报要素集不能改生效起点（目的国 % v%）：移动起点会追溯改写
      历史上某个时刻该用哪一版，而报关单是按当时的版本生成的',
      OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
   END IF;
   IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
     -- 只允许「收紧到一个仍在未来的时刻」：to 必须更早（NULL = +∞，任何具体时刻都比它早）
     -- 且那个时刻必须还没到。放宽、或收紧到一个已过去的时刻都拒绝。
     IF NEW.effective_to IS NULL
        OR (OLD.effective_to IS NOT NULL AND NEW.effective_to >= OLD.effective_to) THEN
       RAISE EXCEPTION '已发布的申报要素集（目的国 % v%）只允许把生效终点收紧到一个更早的时刻
        （原 %，新 %）：放宽终点等于把这一版继续用于它覆盖不到的未来申报',
        OLD.destination_country_code, OLD.version_no,
        coalesce(OLD.effective_to::text,'+∞'), coalesce(NEW.effective_to::text,'+∞')
        USING ERRCODE='23514';
     END IF;
     IF NEW.effective_to <= now() THEN
       RAISE EXCEPTION '已发布的申报要素集（目的国 % v%）不能把生效终点设到已经过去的时刻（%）：
        收紧终点的用途是「这一版从现在起不再使用」，设到过去等于追溯改写历史申报',
        OLD.destination_country_code, OLD.version_no, NEW.effective_to USING ERRCODE='23514';
     END IF;
   END IF;
 END IF;

 -- C7 的第二半：**已发布行的申报要素本体不可原地改**（与 V95 第 353 行
 -- destination_hs_code 那一支同一纪律）。
 -- 为什么必须这样：10.5.1.1 报关单生成时把这一版**抄**进
 -- customs_declaration_line.declaration_elements（jsonb 快照，V85 第 245 行），
 -- 快照一旦抄完就不会跟着上游改。所以上游这一版被原地改掉之后，
 -- 库里存的与报关单上写的就是两份不同的申报事实，而事后复核只能查到一份。
 -- 要改正文：**新建一个版本**（唯一正确的做法），不是改这一版。
 IF TG_OP='UPDATE' AND OLD.status='PUBLISHED'
    AND (NEW.declared_name IS DISTINCT FROM OLD.declared_name
      OR NEW.spec_material IS DISTINCT FROM OLD.spec_material
      OR NEW.usage IS DISTINCT FROM OLD.usage
      OR NEW.brand_type IS DISTINCT FROM OLD.brand_type
      OR NEW.brand IS DISTINCT FROM OLD.brand
      OR NEW.quantity IS DISTINCT FROM OLD.quantity
      OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
      OR NEW.currency IS DISTINCT FROM OLD.currency
      OR NEW.origin_country_code IS DISTINCT FROM OLD.origin_country_code
      OR NEW.origin_rule_code IS DISTINCT FROM OLD.origin_rule_code
      OR NEW.preferential_rate_applicable IS DISTINCT FROM OLD.preferential_rate_applicable
      OR NEW.sales_title_snapshot IS DISTINCT FROM OLD.sales_title_snapshot) THEN
   RAISE EXCEPTION '已发布的申报要素集（目的国 % v%）不能原地改申报要素本体：
    10.5.1.1 已把这一版抄进报关单，改这一版会让库里的申报事实与报关单上写的那份对不上，
    而事后复核只能查到其中一份。要改正文请新建一个版本（version_no + 1）',
    OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER guard_element_set_trg
  BEFORE INSERT OR UPDATE ON customs_element_set
  FOR EACH ROW EXECUTE FUNCTION guard_element_set();

-- ---- guard_element_set_interval —— C5 已发布集合的区间不重叠（跨行）----
-- 与 V95 规则 B1 / V25 规则 R2 同一纪律：**重叠一律拒绝，不做裁决**。
-- 冲突时报错点名与哪一版（版本号 + 区间）撞了 —— 只说「区间重叠」的话，
-- 运营要自己回去找是哪一版。
-- 只在 PUBLISHED 之间判定（规则 R3）：草稿区间随便重叠，发布那一刻才检查。
CREATE FUNCTION guard_element_set_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash record;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
 SELECT version_no, effective_from, effective_to INTO clash
   FROM customs_element_set
  WHERE tenant_id=NEW.tenant_id
    AND sku_id=NEW.sku_id
    AND destination_country_code=NEW.destination_country_code
    AND status='PUBLISHED'
    AND id<>NEW.id
    AND effective_from < coalesce(NEW.effective_to,'infinity'::timestamptz)
    AND coalesce(effective_to,'infinity'::timestamptz) > NEW.effective_from
  LIMIT 1;
 IF FOUND THEN
  RAISE EXCEPTION '该 SKU 在目的国 % 的已发布区间与 v% 撞了（v% 的区间是 [% ~ %]）：
   同一 SKU 同一目的地任意两条已发布版本的区间不得重叠，
   否则「此刻该用哪一版申报」没有答案，而答案是海关事后复核必查的第一件事',
   NEW.destination_country_code, clash.version_no, clash.version_no,
   clash.effective_from, coalesce(clash.effective_to,'+∞') USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER guard_element_set_interval_trg
  BEFORE INSERT OR UPDATE ON customs_element_set
  FOR EACH ROW EXECUTE FUNCTION guard_element_set_interval();

-- ---- guard_element_set_publish —— C4 发布闸：九件齐备 + required 补充项齐备 ----
-- **必须用触发器**：required 补充项齐备性是**跨表**的（头表 + 子表），
-- CHECK 只能看本行，天然做不到（与 V94 规则 A4 同一形状）。
--
-- 蓝图输出句：「完整申报要素集；**缺失项提示**」。C4 是那句输出的「完整」那一半，
-- 缺失项提示（服务层逐项点名）是「提示」那一半 —— 两者同一个事实的两种形状。
--
-- 为什么必须有这道闸：海关按申报要素**逐项**比对，缺一项就是填报不符，
-- 而退单与罚款由卖家承担（V85 文件头第 9 行）。
CREATE FUNCTION guard_element_set_publish() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE missing text := ''; blank_required text;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;

 -- 九件逐项点名。刻意逐条写而不是用数组拼一个「必填项未填」：
 -- 运营要看到的是「品牌类型没填」「原产地规则没填」而不是一句泛化提示。
 IF btrim(NEW.declared_name)='' THEN missing := missing || '申报品名、'; END IF;
 IF btrim(NEW.spec_material)='' THEN missing := missing || '规格材质、'; END IF;
 IF btrim(NEW.usage)='' THEN missing := missing || '用途、'; END IF;
 IF btrim(NEW.brand_type)='' THEN missing := missing || '品牌类型、'; END IF;
 IF btrim(NEW.brand)='' THEN missing := missing || '品牌、'; END IF;
 IF NEW.quantity IS NULL THEN missing := missing || '数量、'; END IF;
 IF NEW.unit_price IS NULL THEN missing := missing || '单价、'; END IF;
 IF btrim(NEW.currency)='' THEN missing := missing || '币种、'; END IF;
 IF btrim(NEW.origin_country_code)='' THEN missing := missing || '产地、'; END IF;
 IF NEW.preferential_rate_applicable AND btrim(NEW.origin_rule_code)='' THEN
   missing := missing || '原产地规则（已主张优惠税率）、';
 END IF;

 IF missing<>'' THEN
  RAISE EXCEPTION '不能发布：申报要素缺失 [%]。海关按申报要素逐项比对，缺一项就是填报不符，
   而退单与罚款由卖家承担。补齐后再发布 —— 缺失项提示已逐项列出，可直接照着补',
   rtrim(missing, '、') USING ERRCODE='23514';
 END IF;

 -- 补充项：required 且值空着的（跨表，这是 CHECK 做不到的那一半）。
 SELECT string_agg(element_code || ' ' || element_name, '、' ORDER BY ordinal)
   INTO blank_required
   FROM customs_element_set_item
  WHERE tenant_id=NEW.tenant_id AND element_set_id=NEW.id
    AND required AND btrim(element_value)='';
 IF blank_required IS NOT NULL THEN
  RAISE EXCEPTION '不能发布：以下必填补充申报要素还没填值 [%]：海关逐项比对，缺一项即填报不符',
   blank_required USING ERRCODE='23514';
 END IF;

 -- 发布瞬间的销售标题复核：集合被改过而快照没跟着更新时，在这里兜住。
 IF lower(btrim(NEW.declared_name)) = lower(btrim(NEW.sales_title_snapshot)) THEN
  RAISE EXCEPTION '不能发布：申报品名与销售标题完全相同（两者用途不同，混用是常见被退单原因）'
   USING ERRCODE='23514';
 END IF;

 RETURN NEW;
END $$;

CREATE TRIGGER guard_element_set_publish_trg
  BEFORE INSERT OR UPDATE ON customs_element_set
  FOR EACH ROW EXECUTE FUNCTION guard_element_set_publish();

-- ---- guard_element_set_item ---- 已发布集合的补充项不可改删（与 C7 同一纪律）----
-- 与头表那条成对：头表钉住九件本体，这一条钉住**补充要素项**。
-- 只看一次头表状态是不够的 —— 绕过服务层直接 UPDATE/DELETE 子表，
-- 头表一行不动就改掉了已发布集合的申报内容，而 C4 的发布闸只在发布那一刻跑过。
CREATE FUNCTION guard_element_set_item() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE set_status text;
DECLARE parent_id uuid; DECLARE parent_tenant uuid;
BEGIN
 IF TG_OP='DELETE' THEN
   parent_tenant := OLD.tenant_id; parent_id := OLD.element_set_id;
 ELSE
   parent_tenant := NEW.tenant_id; parent_id := NEW.element_set_id;
 END IF;
 SELECT status INTO set_status
   FROM customs_element_set
  WHERE tenant_id=parent_tenant AND id=parent_id;
 IF set_status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布的申报要素集，其补充申报要素不可改删（要素集 %）：
   补充项与九件本体同为已发布的申报事实，10.5.1.1 抄走时是一起抄的；
   要改正文请新建一个版本（version_no + 1），而不是改这一版',
   parent_id USING ERRCODE='23514';
 END IF;
 RETURN COALESCE(NEW,OLD);
END $$;

CREATE TRIGGER guard_element_set_item_trg
  BEFORE INSERT OR UPDATE OR DELETE ON customs_element_set_item
  FOR EACH ROW EXECUTE FUNCTION guard_element_set_item();

-- ===========================================================================
-- 四、多租户与授权（与 trade V1 / V116 / V128 / V132 / V133 同一套写法）
-- ===========================================================================
-- 两张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- **FORCE 而不是只 ENABLE**：只有 FORCE 才挡得住「运行角色被误授为表 owner」这一种
-- 真实的运维事故（TradeTenancyIntegrationTest.基线表强制rls而不是只启用 钉的就是它）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'customs_element_set',
    'customs_element_set_item' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  customs_element_set,
  customs_element_set_item TO trade_app;

-- starcon_app 是跨服务只读角色，**拿不到本模块的任何表**：
-- 申报要素是本服务的写入所有者事实，别服务读它必须走授权 HTTP 端口，
-- 而不是直连库（AGENTS.md 模块边界纪律；与 V1 / V116 / V128 / V132 同口径）。
REVOKE ALL ON
  customs_element_set,
  customs_element_set_item FROM starcon_app;

-- ===========================================================================
-- 五、为什么不把「缺失项提示」落表
-- ===========================================================================
-- 缺失项是**当前行的现算结论**：九件里哪些空着 + required 补充项里哪些没填值。
-- 落一列就得靠触发器在每次改内容时同步它，而**漏同步一次的后果是
-- 「已补齐却仍报缺失」** —— 运营会为一个不存在的缺口再去查一遍报关行。
-- V94 零之三对派生值的论证在这里逐字适用（「落一列就等于缓存了一个会被审核流程
-- 改写的派生状态」），所以缺失项提示一律**查询时现算**，DB 层只保留发布闸 C4
-- 那个**真二元事实**（能不能发布）。

-- ===========================================================================
-- 六、与 10.5.1.1 报关单（warehouse V85）的交界
-- ===========================================================================
-- 本模块是它的**上游**（蓝图关系句：上游 = 3.7.1.2；下游 = 10.5.1.1）。
-- 交界刻意做成**单向**：10.5.1.1 取本模块的要素集回填它自己的
-- `customs_declaration_line.declaration_elements`（jsonb，V85 第 245 行）。
-- 本模块**不读** warehouse 的任何表，**不建**跨服务 FK，**不写**报关明细行 ——
-- 报关单行的唯一写入所有者是 10.5.1.1，双写会让「这票货报了什么」出现两个来源。
-- 拍平成 jsonb 发生在 10.5.1.1 那一侧，本表保持强类型列（第一节论证）。
