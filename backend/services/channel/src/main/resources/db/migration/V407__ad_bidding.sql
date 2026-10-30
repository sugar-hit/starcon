-- ===========================================================================
-- V407 · ad_bidding —— 14.2.1.1 竞价管理
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md:129）：
--
--   ### 14.2.1.1 竞价管理
--   - **职责**：管理竞价：动态竞价策略选择（只增只降/降低/提高）、手动竞价调整、
--     竞价范围上限、按商品/SKU 的目标 ACOS 反推竞价建议。
--   - **输入**：竞价设置；目标 ACOS/ROAS；历史表现。
--   - **输出**：竞价建议（目标 ACOS → 建议竞价）；批量调整任务。
--   - **关系**：上游 = 14.1.3.1、14.3.2.1；下游 = 平台广告 API。
--     **目标 ACOS 反推竞价是广告与利润打通的接口**——没有利润数据，投放只能看平台给的
--     建议，无法判断是否真的赚钱。
--
-- ---------------------------------------------------------------------------
-- 0. 这七张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   ad_bidding_policy             —— 竞价策略主表（一个店铺 + 一个平台一条）
--   ad_bidding_suggestion         —— 建议批次（口径窗口 + 目标快照 + 判定参数快照）
--   ad_bidding_suggestion_sku     —— 建议批次的 **SKU 级口径行**（ACOS / ROAS 在这里算）
--   ad_bidding_suggestion_item    —— 建议批次的 **关键词级** 建议竞价（成 / 没成 / 为什么）
--   ad_bidding_adjustment         —— 批量调整任务（本地已改，走 14.1.3.1 的写路径）
--   ad_bidding_adjustment_item    —— 批量调整的逐条明细（被哪道闸拦下）
--   ad_bidding_dispatch           —— **待下发队列**（平台广告 API 未交付 ⇒ 调整只落这里）
--
-- ⚠️ **纯新建、零 ALTER 任何既有表**：
--   只对 V1 `platform` / V1 `shop` / V273 `ad_campaign` / V274 `ad_keyword`
--   / V275 `ad_rule_dispatch`（**已交付的上游**，同服务同库，复合 FK 带 tenant_id
--   ⇒ 跨租户挂载物理上不可能）**只读**引用。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴🔴 三档动态竞价策略是**枚举**，不是自由文本
-- ---------------------------------------------------------------------------
--
--   蓝图：「动态竞价策略选择（**只增只降**/降低/提高）」——
--   这是**三档**，不是一个可以随便填的字符串。
--   ⇒ `dynamic_bidding_strategy` 用 CHECK 钉死三档：
--       UP_AND_DOWN 只增只降（平台可上调也可下调）
--       DOWN_ONLY    只降低（只降不升）
--       UP_ONLY      只提高（只升不降）
--   写成自由文本的后果不是「多一点选择」，而是**平台侧收到一个它不认识的策略**，
--   然后整个投放静默按平台默认值跑 —— 界面上却显示「已设置」。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴🔴 两道不同的闸（蓝图没说，但错了就烧钱）
-- ---------------------------------------------------------------------------
--
--   蓝图：「**竞价范围上限**」+「手动竞价调整」—— 这是**两件事**：
--
--   ① **竞价范围上限**（`min_bid` / `max_bid`，在 `ad_bidding_policy` 上）
--      = 广告账户 / 活动级的**允许区间**：结果落在区间外就是**不许**，
--      不许的事不能靠「截断到边界」糊弄过去。
--   ② **调整上限**（单次调整的**额度**）
--      = 单次调整的幅度 / 金额 / 总额预算。这条**不由本表定义**：
--      它由 **14.1.3.1 已交付的写路径**（`AdKeywordService.bulkAction`）执行
--      （单次比例 ±50%、单条绝对 1000.00、单次总上调 5000.00）。
--      本表上另有一份**更严的**租户自定上限（`single_adjust_cap_ratio` /
--      `single_adjust_cap_amount`），两边**取更严的那个**。
--
--   🔴 **越限逐条拒绝并留痕**（写明被哪道闸拦下 + 原因），**绝不静默截断**：
--   静默截断会让人以为「按预期工作」，而实际上调价被改了。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴🔴 比率口径：ACOS 与 ROAS 都是 GENERATED 列
-- ---------------------------------------------------------------------------
--
--   口径（写死，不给第二种解释）：
--     ACOS = 花费 ÷ **归因销售额**     ROAS = 归因销售额 ÷ 花费
--
--   🔴 **分母为 0 ⇒ 一律 NULL（不可比），不是 0**：
--     「压根算不出来」与「算出来是零」在调价里是**相反**的两件事。
--     拿 0 冒充会让「暂停中 / 没同步到 / 没归因到」看起来像「效率极高」。
--   🔴 **GENERATED 列 ⇒ 服务层写不进去**，绕过服务层直接写 SQL 也编不出
--     与原始量对不上的口径（与 14.3.2.1 的 `ad_attribution_fact.attributed` 同构）。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 金额与数量：一律 NUMERIC + BigDecimal，**无 float/double 一列**
-- ---------------------------------------------------------------------------
--
--   金额 NUMERIC(19,4)；计数与比率 NUMERIC(19,6)；时间 timestamptz。
--   （AGENTS.md：禁止浮点记账；数量一律 numeric(19,6)。）
--
-- ---------------------------------------------------------------------------
-- 5. 🔴 平台广告 API 未交付 ⇒ 调整只落**待下发队列**
-- ---------------------------------------------------------------------------
--
--   `ad_bidding_dispatch` 的形状照抄 14.2.2.2 自动规则的 `ad_rule_dispatch`（V275）：
--   同服务同库、同一套「待下发」语义。
--   ⇒ 界面上写「**待下发**」而不是「已生效」——
--     把「本地已改」说成「平台侧已改」是假事实。
--   ⇒ 本表**唯一**能改变队列的动作是「**撤销**」（dispatch_status → CANCELLED）；
--     **刻意不提供**「标记已下发」这种假动作（平台回写真的发生了才有 DISPATCHED，
--     而那要由平台适配器写，本模块不写）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. ad_bidding_policy —— 竞价策略主表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **一个店铺 + 一个平台一条策略**：
--   动态竞价策略与竞价范围上限都是**账户/活动级**的设置，
--   跨店铺共用一条策略会让「这个上限是谁的」说不清。
CREATE TABLE ad_bidding_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 标识 ===
  policy_code varchar(64) NOT NULL CHECK(btrim(policy_code) <> ''),
  policy_name varchar(128) NOT NULL CHECK(btrim(policy_name) <> ''),

  -- === 上游 2.1.1.1 平台目录（全局码表）+ 2.3.1.1 店铺（复合 FK 带 tenant_id）===
  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- 🔴🔴 **动态竞价策略：三档枚举**（只增只降 / 只降低 / 只提高）。
  --   不是自由文本 —— 见文件头第 1 节。
  dynamic_bidding_strategy varchar(16) NOT NULL DEFAULT 'DOWN_ONLY'
    CHECK(dynamic_bidding_strategy IN ('UP_AND_DOWN','DOWN_ONLY','UP_ONLY')),

  -- 🔴 币种：策略一个币种；关键词竞价币种与策略币种不一致 ⇒ **逐条拒绝**，
  --   绝不按同一个数把不同币种一起调。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- === 作用范围 ===
  -- SHOP 店铺级（该店铺全部在投关键词）/ CAMPAIGN 活动级（该店铺某一个活动）。
  scope varchar(16) NOT NULL DEFAULT 'SHOP'
    CHECK(scope IN ('SHOP','CAMPAIGN')),
  target_campaign_id uuid,
  FOREIGN KEY (tenant_id, target_campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- === 状态 ===
  -- DRAFT 草稿 / ACTIVE 生效 / PAUSED 暂停 / ARCHIVED 归档（软删除：留痕要能复盘）
  status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),

  -- 🔴🔴 **闸①：竞价范围上限**（广告账户/活动级的**允许区间**）。
  --   与「闸②：单次调整额度」是**两件不同的事** —— 见文件头第 2 节。
  min_bid numeric(19,4) CHECK(min_bid IS NULL OR min_bid >= 0),
  max_bid numeric(19,4) CHECK(max_bid IS NULL OR max_bid >= 0),

  -- 🔴 **闸②：单次调整额度**（租户自定，**比 14.1.3.1 的更严才有效**）。
  --   14.1.3.1 已交付的写路径自带三条护栏（±50% / 1000.00 / 总上调 5000.00），
  --   本表这两列是**在它之上再收一道**，两边**取更严的那个**。
  --   比例上限 0 = 不额外限制（完全交给 14.1.3.1 的 ±50%）。
  single_adjust_cap_ratio numeric(19,6) NOT NULL DEFAULT 0
    CHECK(single_adjust_cap_ratio >= 0 AND single_adjust_cap_ratio <= 1),
  single_adjust_cap_amount numeric(19,4) NOT NULL DEFAULT 0
    CHECK(single_adjust_cap_amount >= 0),

  -- === 目标（目标 ACOS / 目标 ROAS，**二选一**）===
  -- 🔴 **ACOS 与 ROAS 是互为倒数的同一个东西**（ACOS = 花费/销售额，ROAS = 销售额/花费），
  --   同时给两个就必须自洽，不自洽就等于给了两个互相矛盾的答案。
  --   ⇒ 用 `num_nonnulls` 钉死「**恰好给一个**」，另一个由服务端换算（1/x）。
  --   比率一律 numeric(19,6)（比例，不是百分数：0.25 = 25%）。
  target_acos numeric(19,6) CHECK(target_acos IS NULL OR target_acos > 0),
  target_roas numeric(19,6) CHECK(target_roas IS NULL OR target_roas > 0),
  CHECK(num_nonnulls(target_acos, target_roas) = 1),

  -- 🔴 试运行：默认 true —— 新建策略一律先试运行，要真执行必须显式关掉。
  --   请求侧的 dryRun 与它**取更严的那个**（任一为真 ⇒ 试运行）。
  dry_run boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 租户内策略编码唯一
  UNIQUE(tenant_id, policy_code),

  -- scope 与 target_campaign_id 必须自洽。
  CHECK((scope = 'CAMPAIGN' AND target_campaign_id IS NOT NULL)
     OR (scope = 'SHOP' AND target_campaign_id IS NULL)),

  -- 上下限必须自洽（上限低于下限 ⇒ 一执行就被拒，白建）。
  CHECK(max_bid IS NULL OR min_bid IS NULL OR max_bid >= min_bid)
);

COMMENT ON TABLE ad_bidding_policy IS
  '14.2.1.1 竞价管理：动态竞价策略三档 + 竞价范围上限 + 单次调整额度 + 目标 ACOS/ROAS（二选一）';

CREATE INDEX ad_bidding_policy_shop ON ad_bidding_policy(tenant_id, shop_code);
CREATE INDEX ad_bidding_policy_status ON ad_bidding_policy(tenant_id, status);

-- 🔴 **一个作用范围只有一条策略**：
--   店铺级（SHOP）⇒ 一个店铺 + 一个平台一条；
--   活动级（CAMPAIGN）⇒ 一个店铺 + 一个平台 + **一个活动**一条（活动级策略可以有若干条，
--   它们各有各的竞价范围上限）。
--   ⇒ 用表达式唯一索引把 target_campaign_id 的 NULL 归一成零 UUID
--   （NULL 在 UNIQUE 里互不相等，直接建列唯一索引的话店铺级策略能建出好几条）。
CREATE UNIQUE INDEX ad_bidding_policy_scope
  ON ad_bidding_policy(tenant_id, shop_code, platform_id,
                       coalesce(target_campaign_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ---------------------------------------------------------------------------
-- 2. ad_bidding_suggestion —— 建议批次
-- ---------------------------------------------------------------------------
--
-- ⚠️ **一次生成 = 一个批次**（口径窗口 + 目标快照 + 判定参数快照）。
--   判定参数必须快照进批次（jsonb）⇒ **可复算**：
--   过两个月回头看「当时为什么给这个价」，答案在批次里，不在代码里。
CREATE TABLE ad_bidding_suggestion (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, policy_id)
    REFERENCES ad_bidding_policy(tenant_id, id) ON DELETE CASCADE,

  -- === 口径窗口（**历史表现**的取数区间）===
  window_from date NOT NULL,
  window_to date NOT NULL,
  CHECK(window_to >= window_from),

  -- 🔴 目标快照（**当时的**目标，不是策略上的当前值）——
  --   策略改了以后，历史批次仍然要能解释「当时是按 25% 还是 30% 算的」。
  target_acos numeric(19,6) NOT NULL CHECK(target_acos > 0),
  target_roas numeric(19,6) NOT NULL CHECK(target_roas > 0),

  -- 🔴 **判定参数快照**（jsonb ⇒ 可复算）：公式、闸①区间、闸②额度、归因规则快照、
  --   利润侧口径与结论。页面上「为什么是这个价」直接展示它。
  basis jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 🔴 **归因口径快照**（14.3.2.1 的规则必须固定并公示）：
  --   rule_code / rule_version / attribution_model / attribution_window_days。
  --   没有生效规则时这里是空的 ⇒ 归因口径未定 ⇒ ACOS 不可比。
  attribution_rule_code varchar(64) NOT NULL DEFAULT '',
  attribution_rule_version bigint,
  attribution_model varchar(24) NOT NULL DEFAULT '',
  attribution_window_days int,

  -- 🔴 **利润侧结论**（13.4.1.2，reporting 服务，**只经授权只读 API**）：
  --   available=false ⇒ 这一批**没有**做盈亏平衡校验，原因写在 unavailable_reason 里。
  --   （上游读不到与「上游说这个 SKU 没有利润数据」是**两件事**，分开说。）
  profit_available boolean NOT NULL DEFAULT false,
  profit_unavailable_reason varchar(500) NOT NULL DEFAULT '',

  -- 🔴 **数据截至时刻**（NOT NULL 的口径是「这一批用到的数据截至什么时候」）：
  --   事实为空时也必须有值 —— 取 DB 的 now()，并在 note 里写明「窗口内没有事实」。
  data_as_of timestamptz NOT NULL,

  -- === 批次状态 ===
  -- READY   全部 SKU 都算出来了
  -- PARTIAL 部分 SKU 算不出来（分母为 0 / 未关联商品 / 利润不可比）
  -- EMPTY   窗口内一个 SKU 都没有
  status varchar(16) NOT NULL DEFAULT 'READY'
    CHECK(status IN ('READY','PARTIAL','EMPTY')),

  sku_count integer NOT NULL DEFAULT 0 CHECK(sku_count >= 0),
  suggested_count integer NOT NULL DEFAULT 0 CHECK(suggested_count >= 0),
  rejected_count integer NOT NULL DEFAULT 0 CHECK(rejected_count >= 0),

  -- 🔴 批次幂等键（租户内唯一）：同一次生成重投 ⇒ 返回既有批次，不重算。
  idempotency_key varchar(160) NOT NULL,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key)
);

COMMENT ON TABLE ad_bidding_suggestion IS
  '14.2.1.1 竞价建议批次：口径窗口 + 目标快照 + 判定参数快照（可复算）+ 归因口径快照 + 数据截至时刻';

CREATE INDEX ad_bidding_suggestion_policy ON ad_bidding_suggestion(tenant_id, policy_id, created_at DESC);
CREATE INDEX ad_bidding_suggestion_created ON ad_bidding_suggestion(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. ad_bidding_suggestion_sku —— 建议批次的 **SKU 级口径行**
-- ---------------------------------------------------------------------------
--
-- 🔴 **ACOS / ROAS 在这一层算**（SKU 级才有意义：广告费与归因销售额都要落到商品上）。
--   关键词级那一层只负责把 SKU 的倍率落到**每个关键词自己的当前竞价**上。
--
-- ⚠️ **花费来自 14.1.3.1 的关键词事实**（`ad_keyword_metric`），
--   **归因销售额来自 14.3.2.1 的归因事实**（`ad_attribution_fact`）——
--   两个都是 channel 服务同库同租户的**只读**事实表，复合 FK 不建（事实表不是配置）。
CREATE TABLE ad_bidding_suggestion_sku (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  suggestion_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, suggestion_id)
    REFERENCES ad_bidding_suggestion(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 内部 SKU（catalog 服务的逻辑 id，**不跨服务建 FK**）
  sku_id uuid NOT NULL,
  -- SKU 编码（从 catalog 只读解析；解析不到时是空串，不是编一个）
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- === 原始量（**服务层写的就是这两个数**，比率都是 GENERATED 列算出来的）===
  -- 🔴 花费（窗口内该 SKU 的广告花费，金额 numeric(19,4)）
  spend numeric(19,4) NOT NULL DEFAULT 0 CHECK(spend >= 0),
  -- 🔴 **归因销售额**（窗口内该 SKU 的广告归因销售额，金额 numeric(19,4)）
  attributed_sales numeric(19,4) NOT NULL DEFAULT 0 CHECK(attributed_sales >= 0),
  -- 币种（**不跨币种相加**：跨币种的那一行在服务层就被拆开或拒了）
  currency char(3) NOT NULL DEFAULT '',

  -- 🔴🔴 **ACOS = 花费 ÷ 归因销售额**（GENERATED ⇒ 服务层写不进去）
  --   **分母为 0 ⇒ NULL（不可比），不是 0**。
  current_acos numeric(19,6) GENERATED ALWAYS AS (
    CASE WHEN attributed_sales > 0 THEN round(spend / attributed_sales, 6) END
  ) STORED,
  -- 🔴🔴 **ROAS = 归因销售额 ÷ 花费**（GENERATED）
  --   **分母为 0 ⇒ NULL（不可比），不是 0**。
  current_roas numeric(19,6) GENERATED ALWAYS AS (
    CASE WHEN spend > 0 THEN round(attributed_sales / spend, 6) END
  ) STORED,

  -- 🔴 **盈亏平衡 ACOS**（广告前利润率，来自 13.4.1.2 的 SKU 利润）
  --   = （净利 + 广告费）÷ 单品收入 —— 净利已经扣过广告费，加回来才是「广告前」。
  --   NULL = 利润侧这次**算不出来**（不是 0）。
  break_even_acos numeric(19,6) CHECK(break_even_acos IS NULL OR break_even_acos >= 0),
  -- 利润侧状态：AVAILABLE 算出来了 / NOT_COMPARABLE 不可比（成本缺项）/ NO_DATA 上游没有这个 SKU 的事实
  profit_status varchar(24) NOT NULL DEFAULT 'NO_DATA'
    CHECK(profit_status IN ('AVAILABLE','NOT_COMPARABLE','NO_DATA')),

  -- 🔴 **建议倍率** = 目标 ACOS ÷ 当前 ACOS
  --   当前 ACOS 不可比 ⇒ 倍率 NULL（**算不出来就别给数**）。
  --   倍率 > 1 = 建议提价；< 1 = 建议降价；= 1 = 不动。
  bid_ratio numeric(19,6) CHECK(bid_ratio IS NULL OR bid_ratio > 0),

  -- === 结论 ===
  -- SUGGESTED 给得出建议 / REJECTED 给不出（逐条写明为什么）
  status varchar(16) NOT NULL DEFAULT 'SUGGESTED'
    CHECK(status IN ('SUGGESTED','REJECTED')),
  -- 🔴 **被哪道闸拦下的**（见文件头第 2 节）/ 或「算不出来」的原因码
  guardrail_code varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一批次里一个 SKU 只有一行
  UNIQUE(tenant_id, suggestion_id, sku_id)
);

COMMENT ON TABLE ad_bidding_suggestion_sku IS
  '14.2.1.1 建议批次的 SKU 级口径行：花费 / 归因销售额 + GENERATED 的 ACOS / ROAS + 盈亏平衡 ACOS + 建议倍率';

CREATE INDEX ad_bidding_suggestion_sku_batch ON ad_bidding_suggestion_sku(tenant_id, suggestion_id);
CREATE INDEX ad_bidding_suggestion_sku_sku ON ad_bidding_suggestion_sku(tenant_id, sku_id);

-- ---------------------------------------------------------------------------
-- 4. ad_bidding_suggestion_item —— 建议批次的 **关键词级** 建议竞价
-- ---------------------------------------------------------------------------
--
-- 🔴 **建议竞价 = 该关键词自己的当前竞价 × SKU 的建议倍率**：
--   倍率是 SKU 级效率的修正，逐条落到每个关键词自己的价上（不是把 SKU 的均价抄给所有词）。
CREATE TABLE ad_bidding_suggestion_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  suggestion_id uuid NOT NULL,
  suggestion_sku_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, suggestion_id)
    REFERENCES ad_bidding_suggestion(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, suggestion_sku_id)
    REFERENCES ad_bidding_suggestion_sku(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 关键词（复合 FK ⇒ 跨租户挂引用在物理上不可能）
  keyword_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, keyword_id)
    REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE,
  -- 关键词原文（快照，页面上直接显示）
  keyword_text varchar(200) NOT NULL DEFAULT '',
  -- 关键词当前竞价（**快照**：生成建议那一刻的价）
  current_bid numeric(19,4) NOT NULL DEFAULT 0 CHECK(current_bid >= 0),
  bid_currency varchar(8) NOT NULL DEFAULT '',

  -- 🔴 **建议竞价**（= current_bid × bid_ratio；算不出来时是 NULL，不是 0）
  suggested_bid numeric(19,4) CHECK(suggested_bid IS NULL OR suggested_bid >= 0),
  -- 与当前竞价的差额（**下调为负**；下调不占「总上调」额度）
  bid_delta numeric(19,4),

  -- === 结论 ===
  -- SUGGESTED 给得出建议 / REJECTED 被闸拦下或算不出来
  status varchar(16) NOT NULL DEFAULT 'SUGGESTED'
    CHECK(status IN ('SUGGESTED','REJECTED')),
  -- 🔴 **被哪道闸拦下的**（见文件头第 2 节）
  guardrail_code varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一批次里同一关键词只有一行
  UNIQUE(tenant_id, suggestion_id, keyword_id)
);

COMMENT ON TABLE ad_bidding_suggestion_item IS
  '14.2.1.1 建议批次的关键词级建议竞价：当前竞价 + 建议竞价 + 差额 + 被哪道闸拦下';

CREATE INDEX ad_bidding_suggestion_item_batch ON ad_bidding_suggestion_item(tenant_id, suggestion_id);
CREATE INDEX ad_bidding_suggestion_item_keyword ON ad_bidding_suggestion_item(tenant_id, keyword_id);

-- ---------------------------------------------------------------------------
-- 5. ad_bidding_adjustment —— 批量竞价调整任务
-- ---------------------------------------------------------------------------
--
-- 🔴 **改竞价一律走 14.1.3.1 已交付的写路径**（`AdKeywordService.bulkAction`）：
--   单次比例 ±50% / 单条绝对 1000.00 / 单次总上调 5000.00 / 幂等 / 操作审计
--   **全部继承**，本模块**不另开一条绕过护栏的写路径**。
--   ⇒ `bulk_action_id` 指向 14.1.3.1 的 `ad_keyword_bulk_action.id`（凭证，可追）。
CREATE TABLE ad_bidding_adjustment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, policy_id)
    REFERENCES ad_bidding_policy(tenant_id, id) ON DELETE CASCADE,

  -- 店铺（一次调整限定在同一店铺内；护栏按店铺计价更清楚）
  shop_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, shop_id) REFERENCES shop(tenant_id, id) ON DELETE CASCADE,

  -- === 动作 ===
  action_type text NOT NULL DEFAULT 'BID_ADJUST'
    CHECK(action_type IN ('BID_ADJUST')),
  -- 🔴 运行口径：DRY_RUN 只算「本来会变成多少」/ APPLY 真调（走 14.1.3.1 的写路径）
  run_mode varchar(16) NOT NULL DEFAULT 'DRY_RUN'
    CHECK(run_mode IN ('DRY_RUN','APPLY')),

  -- === 任务状态 ===
  -- RECEIVED 已受理 / APPLIED 全部成功 / PARTIAL 部分成功 / REJECTED 整批被拒
  status varchar(16) NOT NULL DEFAULT 'RECEIVED'
    CHECK(status IN ('RECEIVED','APPLIED','PARTIAL','REJECTED')),

  -- 调价币种（逐条按币种核对）
  currency varchar(8) NOT NULL DEFAULT '',
  -- 调价方式：ABSOLUTE 直接设成某个竞价（本模块只支持绝对值：
  --   百分比调整的语义在「目标 ACOS 反推」里没有意义）
  bid_mode varchar(16) NOT NULL DEFAULT 'ABSOLUTE'
    CHECK(bid_mode IN ('ABSOLUTE')),

  requested_count integer NOT NULL DEFAULT 0 CHECK(requested_count >= 0),
  applied_count integer NOT NULL DEFAULT 0 CHECK(applied_count >= 0),
  failed_count integer NOT NULL DEFAULT 0 CHECK(failed_count >= 0),

  -- 🔴 **14.1.3.1 批量任务的 id**（写路径的凭证；试运行时为空 —— 没真的调）
  bulk_action_id uuid,
  -- 🔴 **护栏快照**（jsonb）：两道闸的取值 + 14.1.3.1 的三条护栏 + 实际总上调
  guardrail jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 🔴 幂等键（租户内唯一）：重投同一批不会重复调价（调价是**花钱**的动作）
  idempotency_key varchar(160) NOT NULL,

  -- 整批被拒的原因（护栏不过时写清是哪一条上限）
  reason varchar(500) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key)
);

COMMENT ON TABLE ad_bidding_adjustment IS
  '14.2.1.1 批量竞价调整任务：本地已改（走 14.1.3.1 写路径）+ 护栏快照 + 幂等键 + 关联 14.1.3.1 的批量任务';

CREATE INDEX ad_bidding_adjustment_policy ON ad_bidding_adjustment(tenant_id, policy_id, created_at DESC);
CREATE INDEX ad_bidding_adjustment_shop ON ad_bidding_adjustment(tenant_id, shop_id);
CREATE INDEX ad_bidding_adjustment_created ON ad_bidding_adjustment(tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. ad_bidding_adjustment_item —— 批量调整的逐条明细
-- ---------------------------------------------------------------------------
--
-- 🔴 **逐条拒绝并留痕**（写明被哪道闸拦下 + 原因），**绝不静默截断**。
CREATE TABLE ad_bidding_adjustment_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  adjustment_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, adjustment_id)
    REFERENCES ad_bidding_adjustment(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 关键词（复合 FK ⇒ 跨租户挂引用在物理上不可能）
  keyword_id uuid,
  FOREIGN KEY (tenant_id, keyword_id)
    REFERENCES ad_keyword(tenant_id, id) ON DELETE CASCADE,
  -- 目标原文（关键词文本）
  target_ref varchar(200) NOT NULL DEFAULT '',

  -- 调价前后的竞价（金额 numeric(19,4)）
  before_bid numeric(19,4),
  after_bid numeric(19,4),
  -- 期望的调价值（请求里给的）
  requested_bid numeric(19,4),

  -- === 逐条结果 ===
  -- APPLIED    成功（**本地已改**，并已进待下发队列）
  -- REJECTED   被闸拦下 / 算不出来
  -- SKIPPED    整批被拒时逐条标跳过
  status varchar(16) NOT NULL
    CHECK(status IN ('APPLIED','REJECTED','SKIPPED')),

  -- 🔴 **被哪道闸拦下的**：
  --   RANGE_MIN / RANGE_MAX    闸① 竞价范围上限（策略的允许区间）
  --   ADJUST_RATIO             闸② 单次调整比例额度（策略自定）
  --   ADJUST_AMOUNT            闸② 单次调整金额额度（策略自定）
  --   UPSTREAM_RATIO           14.1.3.1 的单次比例上限（±50%）
  --   UPSTREAM_ABSOLUTE        14.1.3.1 的单条绝对上限（1000.00）
  --   UPSTREAM_TOTAL           14.1.3.1 的单次总上调上限（5000.00，整批不动）
  --   CURRENCY                 币种不一致（不按同一个数把不同币种一起调）
  --   NO_BASIS                 算不出来（分母为 0 ⇒ ACOS 不可比）
  guardrail_code varchar(24) NOT NULL DEFAULT '',
  -- 逐条原因（**没成必须写清为什么**）
  reason varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一任务里同一关键词只有一条
  UNIQUE(tenant_id, adjustment_id, keyword_id)
);

COMMENT ON TABLE ad_bidding_adjustment_item IS
  '14.2.1.1 批量调整的逐条明细：成了 / 被哪道闸拦下 / 为什么没成（不静默丢弃、不静默截断）';

CREATE INDEX ad_bidding_adjustment_item_batch ON ad_bidding_adjustment_item(tenant_id, adjustment_id);
CREATE INDEX ad_bidding_adjustment_item_keyword ON ad_bidding_adjustment_item(tenant_id, keyword_id);

-- ---------------------------------------------------------------------------
-- 7. ad_bidding_dispatch —— **待下发队列**（平台广告 API 未交付）
-- ---------------------------------------------------------------------------
--
-- ⚠️ 表形状照抄 14.2.2.2 自动规则的 `ad_rule_dispatch`（V275）：
--   同样的「待下发 → 已下发 / 失败 / 撤销」状态机，同样的载荷 jsonb。
--   本模块**唯一**能改变队列的动作是「**撤销**」（PENDING → CANCELLED）。
--   **刻意不提供**「标记已下发」这种假动作。
CREATE TABLE ad_bidding_dispatch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 来源：这条下发对应哪次调整的哪一条明细（复合 FK ⇒ 跨租户挂引用不可能）。
  --   `adjustment_id` **可空**：改动态竞价策略的下发**不来自某次调价**，
  --   它来自策略本身（激活 / 改了策略档位）。
  adjustment_id uuid,
  adjustment_item_id uuid,
  FOREIGN KEY (tenant_id, adjustment_id)
    REFERENCES ad_bidding_adjustment(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, adjustment_item_id)
    REFERENCES ad_bidding_adjustment_item(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 策略锚点（**每条下发都挂一条策略**）：可见性随策略 → 店铺收敛，
  --   与「不来自调价的那条下发」同一条链路。
  policy_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, policy_id)
    REFERENCES ad_bidding_policy(tenant_id, id) ON DELETE CASCADE,

  -- === 目标 ===
  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  external_campaign_id varchar(128) NOT NULL DEFAULT '',
  external_keyword_id varchar(128) NOT NULL DEFAULT '',

  -- 🔴 动作类型：
  --   SET_BID              改竞价
  --   SET_BIDDING_STRATEGY 改动态竞价策略（只增只降 / 只降低 / 只提高）
  action_type varchar(24) NOT NULL
    CHECK(action_type IN ('SET_BID','SET_BIDDING_STRATEGY')),

  -- 🔴 下发载荷：平台适配器要什么就放什么，**不猜平台的具体字段名**
  action_payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- === 队列状态 ===
  -- PENDING     **待下发**（界面上写「待下发」，不是「已生效」）
  -- DISPATCHED  已下发（平台适配器写的，本模块不写）
  -- FAILED      下发失败（平台适配器写的）
  -- CANCELLED  已撤销（本模块**唯一**能自己改的状态）
  dispatch_status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(dispatch_status IN ('PENDING','DISPATCHED','FAILED','CANCELLED')),

  dispatched_at timestamptz,
  dispatch_ref text NOT NULL DEFAULT '',

  -- 🔴 撤销时刻（只有撤销才有值）。**撤销 = 停止下发**：
  --   本地竞价已经通过 14.1.3.1 的写路径改过了，
  --   撤销**不会**把本地竞价改回去（那是一次新的调价，要走同一套护栏）——
  --   界面必须把这件事说清楚，不能让人以为撤销 = 回滚。
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一条调整明细最多一条下发记录
  UNIQUE(tenant_id, adjustment_item_id)
);

COMMENT ON TABLE ad_bidding_dispatch IS
  '14.2.1.1 待下发队列：平台广告 API 未交付 ⇒ 竞价调整与动态竞价策略只落这里；本模块唯一能改的动作是「撤销」';

CREATE INDEX ad_bidding_dispatch_pending ON ad_bidding_dispatch(tenant_id, dispatch_status, created_at);
CREATE INDEX ad_bidding_dispatch_adjustment ON ad_bidding_dispatch(tenant_id, adjustment_id);
CREATE INDEX ad_bidding_dispatch_policy ON ad_bidding_dispatch(tenant_id, policy_id, created_at DESC);
CREATE INDEX ad_bidding_dispatch_target
  ON ad_bidding_dispatch(tenant_id, platform_id, shop_code, external_campaign_id, external_keyword_id);

-- ===========================================================================
-- 第二节：多租户（RLS isolation + 数据范围）与授权
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 多租户隔离：七张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation
-- 与 channel V1 / V273 / V274 / V275 / V285 / V393 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_bidding_policy', 'ad_bidding_suggestion', 'ad_bidding_suggestion_sku',
    'ad_bidding_suggestion_item', 'ad_bidding_adjustment',
    'ad_bidding_adjustment_item', 'ad_bidding_dispatch'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（照抄 V285 / V393 同构）
-- 链路：dispatch → adjustment_item → adjustment → shop
--       suggestion_item → suggestion_sku → suggestion → policy → shop
-- ---------------------------------------------------------------------------
CREATE POLICY data_scope ON ad_bidding_policy AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_bidding_policy.tenant_id
             AND s.code=ad_bidding_policy.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_bidding_policy.tenant_id
             AND s.code=ad_bidding_policy.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_suggestion AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_suggestion.tenant_id
               AND p.id=ad_bidding_suggestion.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_suggestion.tenant_id
               AND p.id=ad_bidding_suggestion.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_suggestion_sku AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_suggestion g JOIN ad_bidding_policy p
               ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE g.tenant_id=ad_bidding_suggestion_sku.tenant_id
               AND g.id=ad_bidding_suggestion_sku.suggestion_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_suggestion g JOIN ad_bidding_policy p
               ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE g.tenant_id=ad_bidding_suggestion_sku.tenant_id
               AND g.id=ad_bidding_suggestion_sku.suggestion_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_suggestion_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_suggestion_sku gs JOIN ad_bidding_suggestion g
               ON g.tenant_id=gs.tenant_id AND g.id=gs.suggestion_id
               JOIN ad_bidding_policy p ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE gs.tenant_id=ad_bidding_suggestion_item.tenant_id
               AND gs.id=ad_bidding_suggestion_item.suggestion_sku_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_suggestion_sku gs JOIN ad_bidding_suggestion g
               ON g.tenant_id=gs.tenant_id AND g.id=gs.suggestion_id
               JOIN ad_bidding_policy p ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE gs.tenant_id=ad_bidding_suggestion_item.tenant_id
               AND gs.id=ad_bidding_suggestion_item.suggestion_sku_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_adjustment AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_adjustment.tenant_id
               AND p.id=ad_bidding_adjustment.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_adjustment.tenant_id
               AND p.id=ad_bidding_adjustment.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_adjustment_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_adjustment a JOIN ad_bidding_policy p
               ON p.tenant_id=a.tenant_id AND p.id=a.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE a.tenant_id=ad_bidding_adjustment_item.tenant_id
               AND a.id=ad_bidding_adjustment_item.adjustment_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_adjustment a JOIN ad_bidding_policy p
               ON p.tenant_id=a.tenant_id AND p.id=a.policy_id
               JOIN shop s ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE a.tenant_id=ad_bidding_adjustment_item.tenant_id
               AND a.id=ad_bidding_adjustment_item.adjustment_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_bidding_dispatch AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_dispatch.tenant_id
               AND p.id=ad_bidding_dispatch.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_bidding_policy p JOIN shop s
               ON s.tenant_id=p.tenant_id AND s.code=p.shop_code
             WHERE p.tenant_id=ad_bidding_dispatch.tenant_id
               AND p.id=ad_bidding_dispatch.policy_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 第三节：账号与授权（沿用 V1/V273/V274/V275/V393 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  ad_bidding_policy, ad_bidding_suggestion, ad_bidding_suggestion_sku,
  ad_bidding_suggestion_item, ad_bidding_adjustment,
  ad_bidding_adjustment_item, ad_bidding_dispatch TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V273 / V274 / V275 同口径）。
REVOKE ALL ON
  ad_bidding_policy, ad_bidding_suggestion, ad_bidding_suggestion_sku,
  ad_bidding_suggestion_item, ad_bidding_adjustment,
  ad_bidding_adjustment_item, ad_bidding_dispatch FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：竞价管理属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.1.3.1 关键词管理、14.2.1.2 分时策略、14.2.2.1 预算分配、14.2.2.2 自动规则
-- 同族 ⇒ **identity 零改动**。
--   读：channel.operation.read
--   写：channel.operation.create（建策略 / 生成建议 / 提交调整 / 撤销下发）
