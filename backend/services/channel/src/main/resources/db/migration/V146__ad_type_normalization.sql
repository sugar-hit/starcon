-- ===========================================================================
-- 14.1.2.2 广告类型差异（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.2.2 广告类型差异
--   - 职责：适配不同平台与广告类型的字段差异（自动广告、SB/SD、品牌广告、DSP、大促广告），
--           把差异字段归一到内部模型展示。
--   - 输入：各平台广告类型定义；平台 API 返回。
--   - 输出：归一后的广告类型视图 + 特有字段补充展示。
--   - 关系：上游 = 2.1.2.1 平台映射；下游 = 14.1.1.1。
--           **归一化让跨平台广告可比**，但**必须保留原始类型标记，不能丢信息**。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V1 `platform_enum_mapping`（2.1.2.1 平台默认映射，**全局只读码表**，V3 已收回写权限）
--     与 V3 `platform_enum_override`（2.1.2.1 租户覆盖层）—— 本模块的**内部广告类型
--     口径就挂在这两张表上**（enum_kind = 'AD_TYPE'），不是自己另立一套枚举。
--     这正是蓝图「上游 = 2.1.2.1 平台映射」的物理落点：归一化口径的**唯一真相**
--     在 2.1.2.1，本模块只消费、不复制。
--   * 14.1.1.1 活动列表、14.1.2.1 广告组管理、14.1.3.1 关键词管理**均未交付**（见台账
--     module-progress.md），本模块**不引用它们的任何表**；数据以「归一后的广告数据」形态
--     独立存在，等下游上线时按 external_ad_id / external_campaign_id 对接。
--   * 不碰 trade / listing 的表（广告归因要落到内部 SKU 是 14.1.2.1 的事）
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动
--
-- 表名一律走 `ad_type_` 命名空间，与 V136 `logistics_channel_*`、V140 `freight_*`、
-- V143 `channel_comparison_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这张表存在的理由：广告类型的「差异」本身就是数据，不是代码里的 if-else
-- ---------------------------------------------------------------------------
-- 自动广告、SB、SD、品牌广告、DSP、大促广告六类在每个平台上都叫不同的名字、带不同的
-- 字段。把这个差异写成代码里的 `switch (platform, type)` 会得到三个必然的下场：
--   1. 加一个平台要发一次版，而「这个平台没有 DSP」这件事在系统里根本不存在；
--   2. 归一后的类型与原始类型的对应关系没有落点，出问题无法回答「这条广告原本是什么」；
--   3. 特有字段（SB 的 headline、DSP 的 dealName、大促的折扣标签）在前端变成
--      一堆 if-else，且**原始值无处可查**。
-- 所以差异本身入库：`ad_type_profile`（平台 × 原始类型标记 → 归一后内部类型 +
-- 拍卖方式 + 投放目标 + 能力位）与 `ad_type_field_spec`（该类型的特有字段定义）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「必须保留原始类型标记，不能丢信息」的**物理落点**
-- ---------------------------------------------------------------------------
-- 归一化的目的是跨平台可比，而**可比**天然要求把差异压平 —— 一旦压平就不可逆。
-- 所以本迁移把「不丢信息」做成**四条并存的存储**，缺一不可：
--
--   (1) `platform_ad_type`（原始类型标记）：原始平台怎么叫，就怎么存，不做大小写 /
--       别名折叠。归一只是**多出来一列**，不是把原列改掉。
--   (2) `internal_ad_type`（归一后内部类型）：新增维度，与 (1) 同表并存。
--   (3) `raw_payload jsonb`（原始差异字段原文）：平台 API 返回的差异字段**原文照抄**，
--       任何时候都能取回平台原样。前端展示的「特有字段补充展示」读的就是它。
--   (4) `raw_only_field_keys text[]`（只留在原始报文里的键）+ `normalized_field_keys
--       text[]`（已归一的键）：**由触发器从 (3) 现场派生**，服务层传什么都不作数。
--
-- 🔴 (4) 是本模块的**保真审计**：raw_payload 里 minus normalized_field_keys 的那部分，
-- 就是「归一化没地方放、只能原样留着」的字段。它在界面上必须被显式列出来 ——
-- 「这个字段系统没有归一，只在原始报文里」是一条**必须让运营知道**的事实，
-- 悄悄丢掉就等于违背了蓝图那句加粗话。
--
-- 🔴 与之配套两个方向相反的 CHECK，正好卡住两头：
--   * `normalized_field_keys ⊆ raw_payload` 的键：服务层**不许声称**归一了一个
--     根本不存在的字段（否则保真率会被虚报成 100%）；
--   * `raw_only_field_keys` 由触发器覆盖写入：服务层**不许隐瞒**只留在原始报文里的字段。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 未映射必须显式暴露，不落兜底（照抄 2.1.2.1 的加粗句）
-- ---------------------------------------------------------------------------
-- 2.1.2.1 原文：「映射缺失必须显式报『未映射』，不能默认落到某个兜底状态——
-- 否则状态机被污染。」本模块是**广告类型**，兜底的后果一样重：把 Amazon 的
-- `sponsored_display` 悄悄归到「自动广告」，跨平台可比表里就会凭空多出一批
-- 「看起来可比、其实口径不同」的广告，比价与归因从此不可信。
-- 所以内部类型枚举里**显式包含 UNMAPPED**，且带 UNMAPPED 的行：
--   * `mapping_status` 恒为 UNMAPPED_TYPE，**不参与任何跨平台可比聚合**；
--   * 但**照样出现在类型档案与原始报文视图里** —— 它存在，且带完整原始标记。
-- 「没有可比口径」与「没有这种广告」是相反的两件事，界面必须分开显示。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 花费 / 出价 / 预算 / 归因金额一律 **NUMERIC(19,4)**（与 V140 同口径）；
-- 展示 / 点击 / 转化等**数量**一律 **NUMERIC(19,6)**；
-- 比率（ACOS / ROAS 等可比口径）一律 **NUMERIC(19,6)**。
-- **没有一列是 float/double** —— 跨平台可比表直接参与广告决策，浮点不可接受。
--
-- ---------------------------------------------------------------------------
-- 五、三张表
-- ---------------------------------------------------------------------------
--   ad_type_profile     广告类型档案（平台 × 原始类型标记 → 归一后内部类型 + 能力位）
--   ad_type_field_spec  特有字段定义（驱动「特有字段补充展示」，含可比性与内部落点）
--   ad_type_payload     归一后的广告数据（可比字段 + 原始报文原文 + 保真审计）

-- ---------------------------------------------------------------------------
-- ad_type_profile · 广告类型档案
-- ---------------------------------------------------------------------------
CREATE TABLE ad_type_profile (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录（全局码表，无 tenant_id）===
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- 🔴 原始平台类型标记：平台怎么叫就怎么存。**不折叠大小写、不做别名归并** ——
  --   折叠了就答不出「平台 API 原文是什么」，而那正是「不能丢信息」的地基。
  platform_ad_type varchar(64) NOT NULL CHECK (btrim(platform_ad_type) <> ''),

  -- 🔴 归一后的内部类型。与 platform_ad_type **同表并存**，归一只是多一列。
  internal_ad_type varchar(32) NOT NULL
    CHECK (internal_ad_type IN (
      'AUTO_DISCOVERY',        -- 自动广告
      'SPONSORED_BRAND',       -- SB 品牌广告
      'SPONSORED_DISPLAY',     -- SD 展示型品牌广告
      'BRAND_AD',              -- 品牌广告
      'DSP_PROGRAMMATIC',      -- DSP 程序化
      'PROMOTION_EVENT',       -- 大促广告
      'UNMAPPED'               -- 未映射：显式存在，不兜底（见第三节）
    )),

  display_name varchar(100) NOT NULL CHECK (btrim(display_name) <> ''),

  -- 🔴 拍卖方式与投放目标是**跨平台可比的关键**：不同平台的类型名千差万别，
  --   但「按点击计费 + 目标是拉销量」这条口径是可比的。这两列是归一化真正的产出，
  --   类型名对齐只是副产品。
  auction_model text NOT NULL
    CHECK (auction_model IN ('CPC','CPM','CPA','CPM_GOAL','CPC_GOAL','HYBRID','UNKNOWN')),
  objective text NOT NULL
    CHECK (objective IN ('TRAFFIC','SALES','BRAND_AWARE','BACKGROUND_REACH','DEAL','APP_PROMOTION','UNKNOWN')),

  -- 🔴 能力位：2.1.1.2 能力矩阵的同款用法，被 14.1.2.1 广告组 / 14.2.1.1 竞价 /
  --   14.1.3.1 关键词 用来**禁用按钮**，而不是点了才报错。
  supports_targeting     boolean NOT NULL DEFAULT false,
  supports_bid_schedule  boolean NOT NULL DEFAULT false,
  supports_negative_kw   boolean NOT NULL DEFAULT false,
  supports_creative      boolean NOT NULL DEFAULT false,
  supports_ascii_ad      boolean NOT NULL DEFAULT false,

  -- 🔴 归一口径的来源必须可追溯：内部类型是**走 2.1.2.1 的哪一层**解析出来的。
  --   PLATFORM_DEFAULT  命中 V1 platform_enum_mapping（全局默认，所有租户一致）
  --   TENANT_OVERRIDE   命中 V3 platform_enum_override（租户覆盖）
  --   两者都查不到      ⇒ internal_ad_type 必须落 UNMAPPED（第三节）
  -- 由触发器 (a) 钉死：声称某一层却在那张表里查无此行 ⇒ 写入被拒。
  mapping_source text NOT NULL
    CHECK (mapping_source IN ('PLATFORM_DEFAULT','TENANT_OVERRIDE','UNMAPPED')),

  -- 上游 2.1.2.1 的枚举族名。本模块固定用 'AD_TYPE'，写成一列是为了
  -- 「这条归一到底是哪一族映射给的」在库里就能查到，不靠约定。
  enum_kind varchar(64) NOT NULL DEFAULT 'AD_TYPE' CHECK (btrim(enum_kind) <> ''),

  note text NOT NULL DEFAULT '',
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, platform_id, platform_ad_type),

  -- 🔴 三者必须自洽：UNMAPPED 必然来自「两层都没查到」，反之亦然。
  --   不会出现「声称来自租户覆盖、类型却是 UNMAPPED」这种自相矛盾的档案。
  CHECK ((mapping_source = 'UNMAPPED') = (internal_ad_type = 'UNMAPPED'))
);

CREATE INDEX ad_type_profile_lookup ON ad_type_profile(tenant_id, internal_ad_type);
CREATE INDEX ad_type_profile_platform ON ad_type_profile(tenant_id, platform_id);

-- ---------------------------------------------------------------------------
-- ad_type_field_spec · 特有字段定义
-- ---------------------------------------------------------------------------
-- 驱动「特有字段补充展示」：SB 的 headline、DSP 的 dealName、大促的折扣标签，
-- 各是什么类型、什么单位、界面上叫什么、有没有归一落点 —— 全是**数据**。
CREATE TABLE ad_type_field_spec (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能 ===
  ad_type_profile_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, ad_type_profile_id)
    REFERENCES ad_type_profile(tenant_id, id) ON DELETE CASCADE,

  field_key varchar(64) NOT NULL CHECK (btrim(field_key) <> ''),
  field_label varchar(100) NOT NULL CHECK (btrim(field_label) <> ''),
  data_type text NOT NULL
    CHECK (data_type IN ('STRING','NUMBER','MONEY','BOOLEAN','DATETIME','ENUM','LIST')),

  -- 展示单位（%、天、次…）。是标签不是换算：本模块**不做单位统一**，
  -- 跨平台单位的统一属于 14.3.1.1 报表口径，不在本模块范围。
  unit varchar(32) NOT NULL DEFAULT '',

  -- ENUM 型的候选值。逗号分隔的**数据**，不是代码里的枚举类 ——
  -- 平台的候选值由平台适配器给出，改一次要发版就是这个设计的代价。
  enum_values text NOT NULL DEFAULT '',

  -- 🔴 该字段是否参与跨平台可比。
  comparable boolean NOT NULL DEFAULT false,

  -- 🔴 归一后的内部落点。为空 ⇒ **这个字段没有内部表示，只能原样留在 raw_payload 里**，
  --   此时它必然落进 payload.raw_only_field_keys，被保真审计显式列出。
  -- 这是「不能丢信息」的最后一环：有内部落点的字段可比且可查，
  -- 没有内部落点的字段不可比但**原样可查**。
  internal_representation varchar(64) NOT NULL DEFAULT '',

  -- 录入必填 / 展示顺序
  required_on_input boolean NOT NULL DEFAULT false,
  position int NOT NULL DEFAULT 0,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, ad_type_profile_id, field_key),

  -- 🔴 声称可比却没有内部落点 ⇒ 自相矛盾：不可比的字段没有理由被拿去跨平台比较。
  CHECK (NOT comparable OR btrim(internal_representation) <> '')
);

CREATE INDEX ad_type_field_spec_profile ON ad_type_field_spec(tenant_id, ad_type_profile_id);

-- ---------------------------------------------------------------------------
-- ad_type_payload · 归一后的广告数据
-- ---------------------------------------------------------------------------
-- 一条 = 平台上的一条广告，经归一后的形态。跨平台可比字段与原始报文**同表并存**。
CREATE TABLE ad_type_payload (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  ad_type_profile_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, ad_type_profile_id)
    REFERENCES ad_type_profile(tenant_id, id) ON DELETE CASCADE,

  -- 🔴🔴 原始类型标记与归一后类型：**由触发器从档案复制**，服务层写什么都不作数。
  --   这两列与 ad_type_profile 上的同名列同源并存，是「不能丢信息」的第一环 ——
  --   归一后的这条广告，任何时候都能立刻回答「平台上它原本叫什么」。
  --   查可比视图时按 internal_ad_type 聚合；排查问题时按 platform_ad_type 回原始口径。
  platform_ad_type varchar(64) NOT NULL DEFAULT '' CHECK (btrim(platform_ad_type) <> ''),
  internal_ad_type varchar(32) NOT NULL DEFAULT 'UNMAPPED'
    CHECK (internal_ad_type IN (
      'AUTO_DISCOVERY','SPONSORED_BRAND','SPONSORED_DISPLAY','BRAND_AD',
      'DSP_PROGRAMMATIC','PROMOTION_EVENT','UNMAPPED')),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id, code)，复合 FK）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 平台侧 ID
  external_ad_id varchar(128) NOT NULL CHECK (btrim(external_ad_id) <> ''),
  external_campaign_id varchar(128) NOT NULL DEFAULT '',
  ad_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 归一后的状态（跨平台可比：不同平台的 ENABLED/RUNNING/ACTIVE 归到一处）
  ad_status varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK (ad_status IN ('ENABLED','PAUSED','ENDED','ARCHIVED','PENDING_REVIEW','UNKNOWN')),
  currency char(3) NOT NULL DEFAULT '',

  -- === 跨平台可比的金额与时间 ===
  daily_budget NUMERIC(19,4) CHECK (daily_budget IS NULL OR daily_budget >= 0),
  default_bid NUMERIC(19,4) CHECK (default_bid IS NULL OR default_bid >= 0),
  bid_floor NUMERIC(19,4) CHECK (bid_floor IS NULL OR bid_floor >= 0),
  start_at timestamptz,
  end_at timestamptz,
  CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),

  -- === 跨平台可比的效果量 ===
  -- 🔴 数量一律 NUMERIC(19,6)：平台报回来的展示/点击/转化本来就是整数，
  --   但**费率类比值**（CPC、CPM、CVR）必须能存小数，统一用 19,6 不给将来留坑。
  --   禁浮点：这些列直接参与跨平台可比表与 14.3.1.1 报表。
  impressions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  clicks NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  conversions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (conversions >= 0),
  spend NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (spend >= 0),
  attributed_revenue NUMERIC(19,4) CHECK (attributed_revenue IS NULL OR attributed_revenue >= 0),

  -- 🔴 可比比率是 GENERATED 列：服务层写不进去，绕过服务层也编不出与原始量对不上的比率。
  --   分母为 0 给 NULL（未可比）而不是 0（「可比但真的是零」）。
  ctr NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN impressions > 0 THEN round(clicks / impressions, 6) ELSE NULL END
  ) STORED,
  cpc NUMERIC(19,4) GENERATED ALWAYS AS (
    CASE WHEN clicks > 0 THEN round(spend / clicks, 4) ELSE NULL END
  ) STORED,
  cvr NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN clicks > 0 THEN round(conversions / clicks, 6) ELSE NULL END
  ) STORED,
  acos NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN attributed_revenue IS NOT NULL AND attributed_revenue > 0
         THEN round(spend / attributed_revenue, 6) ELSE NULL END
  ) STORED,

  -- 🔴🔴 原始差异字段原文：平台 API 返回的差异字段**照抄不改**。
  --   这是「不能丢信息」的兜底 —— 归一做错了也还能回到原文重新归一。
  --   任何时候都能取回平台原样，不受归一口径变化影响。
  raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  raw_fetched_at timestamptz NOT NULL,
  source_ref text NOT NULL DEFAULT '',

  -- 🔴 已归一的键。CHECK（由触发器钉）：必须 ⊆ raw_payload 的键 ——
  --   不许声称归一了一个根本不存在的字段，否则保真率能被虚报成 100%。
  normalized_field_keys text[] NOT NULL DEFAULT '{}'::text[],

  -- 🔴🔴 只留在原始报文里的键。**由触发器从 raw_payload − normalized_field_keys 现场派生**，
  --   服务层传什么都不作数 —— 隐瞒「这个字段没归一」在 DB 层就被拒。
  raw_only_field_keys text[] NOT NULL DEFAULT '{}'::text[],

  -- 🔴 保真状态，同样由触发器派生：
  --   UNMAPPED_TYPE            类型都没归一（2.1.2.1 两层都查不到）⇒ 不参与任何可比聚合
  --   PARTIAL_FIELD_COVERAGE   类型归一了，但有字段只留在原始报文里
  --   NORMALIZED               类型归一，且原始字段**全部**有归一落点
  mapping_status varchar(32) NOT NULL DEFAULT 'UNMAPPED_TYPE'
    CHECK (mapping_status IN ('UNMAPPED_TYPE','PARTIAL_FIELD_COVERAGE','NORMALIZED')),

  ingested_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, ad_type_profile_id, external_ad_id),

  -- 原始报文必须是 JSON 对象（不是数组/标量）：「按顶层键算差集」是保真审计的前提。
  CHECK (jsonb_typeof(raw_payload) = 'object')
);

CREATE INDEX ad_type_payload_list ON ad_type_payload(tenant_id, ingested_at DESC, id);
CREATE INDEX ad_type_payload_shop ON ad_type_payload(tenant_id, shop_code);
CREATE INDEX ad_type_payload_profile ON ad_type_payload(tenant_id, ad_type_profile_id);
CREATE INDEX ad_type_payload_status ON ad_type_payload(tenant_id, mapping_status);

-- 归一后的可比查询入口：按内部类型聚合并排除未映射的
CREATE INDEX ad_type_payload_comparable ON ad_type_payload(tenant_id, ingested_at DESC)
  WHERE mapping_status <> 'UNMAPPED_TYPE';

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴 归一口径的来源必须与 2.1.2.1 的实际数据一致。
--     声称 PLATFORM_DEFAULT 却在 platform_enum_mapping 里查无此行，
--     或声称 TENANT_OVERRIDE 却在 platform_enum_override 里查无此行 ⇒ 写入被拒。
--     不钉这一条，「这条广告归到了哪个内部类型」就成了服务层的一面之词。
--     注意：本迁移**不改**那两张表，只读它们。
CREATE OR REPLACE FUNCTION guard_ad_type_profile_mapping_source() RETURNS trigger AS $$
DECLARE
  found_rows integer;
BEGIN
  IF NEW.mapping_source = 'UNMAPPED' THEN
    RETURN NEW;   -- 未映射无需查表（它本来就是「两层都没查到」）
  END IF;

  IF NEW.mapping_source = 'PLATFORM_DEFAULT' THEN
    SELECT count(*) INTO found_rows
      FROM platform_enum_mapping
     WHERE platform_id = NEW.platform_id
       AND enum_kind = NEW.enum_kind
       AND external_value = NEW.platform_ad_type;
  ELSE
    -- 租户覆盖层带 RLS；写入发生在已绑定 app.current_tenant 的事务里，
    -- 查不到行只说明本租户没覆盖，不是数据坏了。
    SELECT count(*) INTO found_rows
      FROM platform_enum_override
     WHERE tenant_id = NEW.tenant_id
       AND platform_id = NEW.platform_id
       AND enum_kind = NEW.enum_kind
       AND external_value = NEW.platform_ad_type;
  END IF;

  IF found_rows = 0 THEN
    RAISE EXCEPTION
      '归一类型 % / % 声称来自 %，但 2.1.2.1 的 % 里查无此行（platform=% enum_kind=% external=%）—— 归一口径的来源必须真实存在，否则「这条广告归到了哪个类型」就成了服务层的一面之词；请先在 2.1.2.1 登记映射，或把 internal_ad_type 落成 UNMAPPED',
      NEW.platform_id, NEW.platform_ad_type, NEW.mapping_source,
      CASE WHEN NEW.mapping_source = 'PLATFORM_DEFAULT'
           THEN 'platform_enum_mapping' ELSE 'platform_enum_override' END,
      NEW.platform_id, NEW.enum_kind, NEW.platform_ad_type
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_type_profile_mapping_source_trg
  BEFORE INSERT OR UPDATE ON ad_type_profile
  FOR EACH ROW EXECUTE FUNCTION guard_ad_type_profile_mapping_source();

-- (b) 🔴 归一后的广告数据必须带原始类型标记，且与档案一致。
--     这里把 profile 的原始标记**复制**到 payload 上（而不是让服务层自己填），
--     于是「归一后的这条广告原本是什么」永远是档案说了算，
--     服务层无法把一条 SB 报成自动广告。
--     同时把 internal_ad_type 从 profile 带下来：归一结果与档案不得各写各的。
CREATE OR REPLACE FUNCTION guard_ad_type_payload_identity() RETURNS trigger AS $$
DECLARE
  p_platform_ad_type varchar(64);
  p_internal_ad_type varchar(32);
BEGIN
  SELECT platform_ad_type, internal_ad_type
    INTO p_platform_ad_type, p_internal_ad_type
    FROM ad_type_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.ad_type_profile_id;

  IF p_platform_ad_type IS NULL THEN
    RAISE EXCEPTION '广告类型档案不存在（tenant=% profile=%）', NEW.tenant_id, NEW.ad_type_profile_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  NEW.platform_ad_type := p_platform_ad_type;
  NEW.internal_ad_type := p_internal_ad_type;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_type_payload_identity_trg
  BEFORE INSERT OR UPDATE ON ad_type_payload
  FOR EACH ROW EXECUTE FUNCTION guard_ad_type_payload_identity();

-- (c) 🔴🔴 保真审计：raw_only_field_keys 与 mapping_status **一律由本触发器派生**。
--     两个方向都卡死：
--       * normalized_field_keys 必须 ⊆ raw_payload 的键 —— 不许虚报归一覆盖率；
--       * raw_only_field_keys 由 raw_payload − normalized_field_keys 现算 —— 不许隐瞒
--         「这个字段系统没有归一，只能原样留着」。
--     这就是蓝图加粗句「必须保留原始类型标记，不能丢信息」的落地点：
--     丢了会在这里被拒，藏了也会在这里被改写出来。
CREATE OR REPLACE FUNCTION guard_ad_type_payload_fidelity() RETURNS trigger AS $$
DECLARE
  payload_keys text[];
  profile_internal_type varchar(32);
BEGIN
  SELECT ARRAY(SELECT jsonb_object_keys(NEW.raw_payload)) INTO payload_keys;

  -- 🔴 这里**自己**从档案读 internal_ad_type，而不是读 NEW.internal_ad_type。
  --    PostgreSQL 按**触发器名字母序**触发（identity < immutable，但 fidelity 排在最前），
  --    依赖「上一条触发器已经把 internal_ad_type 填好了」会得到随名字排序漂移的结果：
  --    实测曾让一条 SPONSORED_DISPLAY 的广告被算成 UNMAPPED_TYPE。
  --    自己查一遍就与触发器顺序彻底解耦，改名也不会改变语义。
  SELECT internal_ad_type INTO profile_internal_type
    FROM ad_type_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.ad_type_profile_id;

  IF profile_internal_type IS NULL THEN
    RAISE EXCEPTION '广告类型档案不存在（tenant=% profile=%）', NEW.tenant_id, NEW.ad_type_profile_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  -- (c0) 键不得重复：数组里带重复键会让下面的差集算错，也会让保真率虚高。
  --      🔴 PostgreSQL 的 CHECK 不允许子查询，所以这条只能落在触发器里。
  IF cardinality(NEW.normalized_field_keys)
       <> cardinality(ARRAY(SELECT DISTINCT unnest(NEW.normalized_field_keys))) THEN
    RAISE EXCEPTION
      'normalized_field_keys 里有重复键（% 个元素、去重后 % 个）—— 重复键会让保真率虚高',
      cardinality(NEW.normalized_field_keys),
      cardinality(ARRAY(SELECT DISTINCT unnest(NEW.normalized_field_keys)))
      USING ERRCODE = 'check_violation';
  END IF;

  -- (c1) 声称已归一的键必须真的存在于原始报文里
  IF EXISTS (
      SELECT 1 FROM unnest(NEW.normalized_field_keys) k
       WHERE NOT (k = ANY (payload_keys))
  ) THEN
    RAISE EXCEPTION
      '声称已归一的字段中至少有一个不在原始报文里 —— 原始报文的键为 [%]。归一覆盖率不得虚报：没有的字段不可能被归一',
      array_to_string(payload_keys, ', ') USING ERRCODE = 'check_violation';
  END IF;

  -- (c2) 只留在原始报文里的键：现场派生，服务层传什么都不作数
  NEW.raw_only_field_keys := ARRAY(
    SELECT k FROM unnest(payload_keys) k
     WHERE NOT (k = ANY (NEW.normalized_field_keys))
     ORDER BY k);

  -- (c3) 保真状态：类型没归一 ⇒ UNMAPPED_TYPE（优先级最高，且这种行不参与任何可比聚合）；
  --     否则有原始专有字段 ⇒ PARTIAL_FIELD_COVERAGE；全有落点 ⇒ NORMALIZED。
  IF profile_internal_type = 'UNMAPPED' THEN
    NEW.mapping_status := 'UNMAPPED_TYPE';
  ELSIF cardinality(NEW.raw_only_field_keys) > 0 THEN
    NEW.mapping_status := 'PARTIAL_FIELD_COVERAGE';
  ELSE
    NEW.mapping_status := 'NORMALIZED';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_type_payload_fidelity_trg
  BEFORE INSERT OR UPDATE ON ad_type_payload
  FOR EACH ROW EXECUTE FUNCTION guard_ad_type_payload_fidelity();

-- (d) 🔴 已归一的广告数据不允许就地改归一口径：原始报文与归一后的可比列已被
--     14.3.1.1 报表按天快照引用，就地改会让那些报表再也复算不出来。
--     要改请把旧行 note 里标注后重登一条新的（external_ad_id 唯一约束会挡住并存）。
CREATE OR REPLACE FUNCTION guard_ad_type_payload_immutable() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND (
      NEW.ad_type_profile_id <> OLD.ad_type_profile_id
      OR NEW.external_ad_id <> OLD.external_ad_id
      OR NEW.raw_fetched_at <> OLD.raw_fetched_at
      OR NEW.raw_payload <> OLD.raw_payload) THEN
    RAISE EXCEPTION
      '已归一的广告数据不允许就地改原始来源或原始报文（external_ad_id %）—— 报表已按这些值快照过，改了就再也复算不出来；请重登一条新的',
      OLD.external_ad_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_type_payload_immutable_trg
  BEFORE UPDATE ON ad_type_payload
  FOR EACH ROW EXECUTE FUNCTION guard_ad_type_payload_immutable();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 同一套写法。租户由
-- backend/libraries/tenancy 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头
-- 进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_type_profile', 'ad_type_field_spec', 'ad_type_payload' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141/V143 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  ad_type_profile, ad_type_field_spec, ad_type_payload TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 / V140 同口径）。
REVOKE ALL ON
  ad_type_profile, ad_type_field_spec, ad_type_payload FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：广告类型差异属于「平台操作」语义，与 10.1.1.2 渠道配置、
-- 10.1.2.1 运费计算器、2.1.2.1 平台枚举映射同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
