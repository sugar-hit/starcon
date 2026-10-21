-- ===========================================================================
-- 14.1.1.1 广告活动列表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.1.1 活动列表
--   - 职责：广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、
--           销售额、ACOS/ROAS，按维度筛选与排序，支持批量暂停/启用/调预算。
--   - 输入：筛选条件（平台、店铺、状态、日期）；广告数据同步结果；用户数据范围。
--   - 输出：分页列表 + 汇总；批量操作任务。
--   - 关系：上游 = 平台广告 API 同步；下游 = 14.1.1.2 详情、14.2.2.1 预算。
--           **广告数据同步是本系统的地基**：同步失败时页面要明确显示"数据截至 X 时间"，
--           不能用空数据假装没有投放。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V1 `platform`（全局平台码表）+ V1 `shop`（租户店铺，UNIQUE(tenant_id,code)）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V146 `ad_type_profile`（**已交付的上游 14.1.2.2 广告类型差异**）——
--     本模块的广告类型口径**不在自己这里另立一套**：归一后的内部类型与原始类型标记
--     都由档案带下来，归一口径的真相仍在 2.1.2.1 / 14.1.2.2。
--   * 14.1.1.2 活动详情、14.1.1.3 批量操作、14.2.2.1 预算分配、14.2.2.2 自动规则
--     **均未交付**（台账 module-progress.md 已确认），本模块**不引用它们的任何表**；
--     14.2.2.2 规则引擎上线后按「按外部 id 取活动当前状态与预算/消耗」这个读取形状调用本模块。
--
-- 表名一律走 `ad_campaign` 命名空间，与 V146 `ad_type_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这四张表存在的理由：广告活动是「事实」，不是「视图」
-- ---------------------------------------------------------------------------
-- 蓝图的输入写着「广告数据同步结果」，输出写着「分页列表 + 汇总」。但本批交付时
-- **平台广告 API 同步器并不存在**（上游未交付，14.2.2.2 规则引擎是同批并行车道）。
-- 于是有两种可能的做法：
--   1. 用空数组 / 硬编码常量当列表数据 —— 界面看起来像「本租户没有投放」，
--      而真相是「还没同步到数据」。这正是蓝图加粗句点名要防的那件事；
--   2. **本模块自带活动的真实写入口**（`ad_campaign_sync_run` + `ad_campaign` +
--      `ad_campaign_daily_metric`），让「平台同步结果」有一个真的落点，
--      并把「数据截至 X 时间」变成库里可查的事实。
-- 本迁移选第 2 条。四张表：
--
--   ad_campaign              广告活动主表（平台、店铺、名称、类型、状态、预算 + 数据截至时刻）
--   ad_campaign_daily_metric 活动每日表现明细（曝光/点击/订单/消耗/销售额 → 列表的汇总来源）
--   ad_campaign_sync_run     广告数据同步运行记录（**数据截至时间的唯一来源** + 失败原因）
--   ad_campaign_operation_log 批量操作审计（14 章关键约束「批量调整预算与竞价要走规则并留审计」）
--
-- ---------------------------------------------------------------------------
-- 二、🔴「广告数据同步是本系统的地基」怎么落到界面上
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「同步失败时页面要明确显示『数据截至 X 时间』，不能用空数据假装没有投放。」
-- 落到库上是三件事：
--
--   (1) `ad_campaign_sync_run.data_as_of`：**只有成功/部分成功的同步运行才推进这个水位**，
--       失败的运行**不推进**（data_as_of 可空）。于是「数据截至 X 时间」= 成功同步水位，
--       而不是「最后一次尝试的时间」—— 后者会让运营以为拿到了新数据。
--   (2) `ad_campaign_sync_run.status` ∈ SUCCESS / PARTIAL / FAILED，且 FAILED **必须带 message**：
--       失败原因要和「截至时间」一起显示，只给时间不给原因等于让人猜。
--   (3) `ad_campaign.data_as_of` + `last_synced_at`：每个活动自己带着它的水位，
--       便于回答「这条活动的数据是什么时候的」。
--
-- 由此，「没有投放」与「还没同步到数据」在库上就是**两种不同的状态**：
-- 前者有成功的同步运行、但活动数为 0；后者连一条成功的同步运行都没有。
-- 服务层据此回 `emptyReason`，页面据此显示不同的空态文案 —— 两者**不可混为一谈**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 归一类型与原始类型必须并存（照抄 14.1.2.2 的加粗句）
-- ---------------------------------------------------------------------------
-- 14.1.2.2 已交付，它的模型里写死了：「只给归一类型，『平台 API 原文是什么』就永远答不出来」。
-- 本模块是它的**下游**，若这里只留 `internal_ad_type`，那个问题就会在下游重新长出来。
-- 所以 `ad_campaign` 上有**三列并存**：
--
--   platform_ad_type      原始平台类型标记（平台怎么叫就怎么存）
--   internal_ad_type      归一后的内部类型（跨平台可比的那一列）
--   ad_type_mapping_source 归一口径来自 2.1.2.1 的哪一层（PLATFORM_DEFAULT / TENANT_OVERRIDE / UNMAPPED）
--
-- 🔴 这三列**由触发器从 `ad_type_profile` 复制**，服务层写什么都不作数 ——
-- 活动无法把一条 SB 报成自动广告，归一口径也不会在下游被改写。
--
-- 🔴 UNMAPPED 是**显式合法值**，不是兜底：它表示 2.1.2.1 两层都没给归一口径。
-- 这种活动**照样出现在列表里、照样有完整原始标记**，只是不参与任何「按内部类型」的
-- 横向比较。「没有可比口径」与「没有这种广告」是相反的两件事。
--
-- 🔴 关于 PARTIAL_FIELD_COVERAGE：那是 14.1.2.2 **单条广告报文**粒度的保真状态
-- （类型归一了，但有字段只留在原始报文里）。它**不等于**「归一失败」，也**不是活动级属性** ——
-- 活动的类型归一成功与否由 `internal_ad_type` + `ad_type_mapping_source` 表达。
-- 本模块**不把字段级覆盖率伪装成活动级状态**：那样会凭空造出一个语义。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 预算 / 消耗 / 销售额一律 **NUMERIC(19,4)**（与 V146 同口径）；
-- 曝光 / 点击 / 订单是**计数**，一律 **NUMERIC(19,6)**（与 V146 的数量列同口径）；
-- 比率（CTR / ACOS / ROAS）一律 **NUMERIC(19,6)**，且是 GENERATED 列：
-- **分母为 0 给 NULL（不可比）而不是 0**（「可比但真的是零」与「压根算不出来」相反）。
-- **没有一列是 float/double** —— 这些列直接参与广告决策与 14.3.1.1 报表。
--
-- 🔴 列表里的 ACOS / ROAS 是**先汇总再相除**（总消耗 / 总销售额），
-- 不是「各活动比率的平均」。后者是数学错误：把两个 ACOS 100% 的活动平均成 50%
-- 会让运营以为「整体在盈亏线上」——它其实在亏。这一点由服务层的汇总 SQL 保证。
--
-- ---------------------------------------------------------------------------
-- 五、多租户隔离与数据范围
-- ---------------------------------------------------------------------------
-- 四张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation（照抄 V146 的写法），
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 另加 data_scope（RESTRICTIVE）策略：蓝图的输入写着「用户数据范围」。
-- 广告活动挂在**店铺**下，可见性随店铺 owner / department 收敛
-- （与 V171 listing / V203 listing_performance 同构）—— 不允许绕过店铺直接枚举投放数据。
-- 每日指标 / 同步运行 / 操作审计三层都随活动 → 店铺收敛。
--
-- ===========================================================================
-- 1. ad_campaign_sync_run · 广告数据同步运行（数据截至时间的唯一来源）
-- ===========================================================================
CREATE TABLE ad_campaign_sync_run (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录（全局码表）+ V1 店铺（复合 FK 带 tenant_id）===
  platform_id uuid NOT NULL REFERENCES platform(id),
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- SUCCESS   同步成功，覆盖到 data_as_of
  -- PARTIAL   部分成功（部分店铺/部分活动没拿到），覆盖到 data_as_of
  -- FAILED    同步失败，**data_as_of 不推进**（可空），且 message 必须有内容
  status text NOT NULL CHECK (status IN ('SUCCESS','PARTIAL','FAILED')),

  -- 🔴 数据截至时刻：这份数据**覆盖到**什么时候。
  --   FAILED 时为 NULL —— 失败的同步不让水位前进，否则运营会以为拿到了新数据。
  --   「数据截至 X 时间」= 各次成功运行里最大的 data_as_of。
  data_as_of timestamptz,

  started_at timestamptz NOT NULL,
  finished_at timestamptz,

  -- 本次带回来的活动条数 / 每日指标行数（供界面显示「这次同步到了多少」）
  campaign_count int NOT NULL DEFAULT 0 CHECK (campaign_count >= 0),
  metric_row_count int NOT NULL DEFAULT 0 CHECK (metric_row_count >= 0),

  -- 🔴 FAILED 必须带原因：只给时间不给原因等于让人猜。
  --   PARTIAL 也建议带（哪些没拿到）。
  message text NOT NULL DEFAULT '',

  -- 来源凭据 / 批次标识（平台同步器上线后这里放同步批次号）
  source_ref text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 失败必须有原因；成功必须有水位。两条自洽性由 CHECK 钉死。
  CHECK ((status = 'FAILED') = (data_as_of IS NULL)),
  CHECK (status <> 'FAILED' OR btrim(message) <> '')
);

CREATE INDEX ad_campaign_sync_run_scope ON ad_campaign_sync_run(tenant_id, platform_id, shop_code, created_at DESC);
CREATE INDEX ad_campaign_sync_run_watermark ON ad_campaign_sync_run(tenant_id, data_as_of DESC)
  WHERE data_as_of IS NOT NULL;

-- ===========================================================================
-- 2. ad_campaign · 广告活动主表
-- ===========================================================================
CREATE TABLE ad_campaign (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 2.1.1.1 平台目录 ===
  platform_id uuid NOT NULL REFERENCES platform(id),

  -- === 上游 2.3.1.1 店铺（UNIQUE(tenant_id,code)，复合 FK ⇒ 跨租户挂载不可能）===
  shop_code varchar(64) NOT NULL,
  FOREIGN KEY (tenant_id, shop_code) REFERENCES shop(tenant_id, code) ON DELETE CASCADE,

  -- === 上游 14.1.2.2 广告类型档案（复合 FK 带 tenant_id）===
  ad_type_profile_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, ad_type_profile_id)
    REFERENCES ad_type_profile(tenant_id, id) ON DELETE RESTRICT,

  -- 🔴🔴 原始类型标记 / 归一后内部类型 / 归一口径来源：**由触发器从档案复制**，
  --   服务层写什么都不作数。归一只是**多出来两列**，不是把原列改掉。
  platform_ad_type varchar(64) NOT NULL DEFAULT '' CHECK (btrim(platform_ad_type) <> ''),
  internal_ad_type varchar(32) NOT NULL DEFAULT 'UNMAPPED'
    CHECK (internal_ad_type IN (
      'AUTO_DISCOVERY','SPONSORED_BRAND','SPONSORED_DISPLAY','BRAND_AD',
      'DSP_PROGRAMMATIC','PROMOTION_EVENT','UNMAPPED')),
  ad_type_mapping_source text NOT NULL DEFAULT 'UNMAPPED'
    CHECK (ad_type_mapping_source IN ('PLATFORM_DEFAULT','TENANT_OVERRIDE','UNMAPPED')),

  -- 平台侧活动 id + 活动名
  external_campaign_id varchar(128) NOT NULL CHECK (btrim(external_campaign_id) <> ''),
  campaign_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 归一后的状态（跨平台可比：不同平台的 ENABLED/RUNNING/ACTIVE 归到一处）
  status varchar(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK (status IN ('ENABLED','PAUSED','ENDED','ARCHIVED','PENDING_REVIEW','UNKNOWN')),
  currency char(3) NOT NULL DEFAULT '',

  -- 日预算（钱）。NULL = 平台侧没有预算（如 DSP 固定价），不是 0。
  daily_budget NUMERIC(19,4) CHECK (daily_budget IS NULL OR daily_budget >= 0),
  start_at timestamptz,
  end_at timestamptz,
  CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),

  -- 🔴🔴 平台回写队列**未交付**（蓝图：批量调整要经队列回写平台，14 关键约束）。
  --   本地暂停/启用/调预算会先落在这里并置 pending_platform_writeback = true，
  --   界面上必须显式告诉运营「本地已改、还没回写平台」——
  --   否则界面上「已暂停」会被当成「平台侧已暂停」，那是假事实。
  pending_platform_writeback boolean NOT NULL DEFAULT false,
  local_changed_at timestamptz,

  -- 🔴 这条活动的数据截至时刻 / 最近一次入库时刻（由同步运行带下来）
  data_as_of timestamptz NOT NULL,
  last_synced_at timestamptz NOT NULL,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一平台同一店铺下的平台侧活动 id 唯一（一次同步重放不会造出两条）
  UNIQUE(tenant_id, platform_id, shop_code, external_campaign_id),

  -- 🔴 UNMAPPED 必然来自「2.1.2.1 两层都没查到」，反之亦然 ——
  --   不会出现「声称来自平台默认、类型却是 UNMAPPED」这种自相矛盾的活动。
  CHECK ((ad_type_mapping_source = 'UNMAPPED') = (internal_ad_type = 'UNMAPPED'))
);

CREATE INDEX ad_campaign_list ON ad_campaign(tenant_id, status, id);
CREATE INDEX ad_campaign_shop ON ad_campaign(tenant_id, shop_code, status);
CREATE INDEX ad_campaign_platform ON ad_campaign(tenant_id, platform_id, status);
CREATE INDEX ad_campaign_type ON ad_campaign(tenant_id, internal_ad_type);
CREATE INDEX ad_campaign_external ON ad_campaign(tenant_id, platform_id, shop_code, external_campaign_id);
CREATE INDEX ad_campaign_watermark ON ad_campaign(tenant_id, data_as_of DESC);

-- ===========================================================================
-- 3. ad_campaign_daily_metric · 活动每日表现明细
-- ===========================================================================
-- 列表的「消耗 / 曝光 / 点击 / 订单 / 销售额 / ACOS / ROAS」全部来自这里按日期区间汇总。
-- 之所以存**每日明细**而不是只存一个累计值：
--   * 蓝图的输入明确有「日期」筛选 —— 累计值根本没法按区间筛；
--   * 14.3.1.1 活动报表要按天出图，累计值拆不回每天。
CREATE TABLE ad_campaign_daily_metric (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  -- 自然日（平台当地日）。用 date 而不是 timestamptz：
  -- 平台的「一天」是当地日，按 UTC 时刻切会把一天的量劈成两半。
  metric_date date NOT NULL,

  -- 🔴 数量一律 NUMERIC(19,6)：平台报回来的本来就是整数，
  --   但统一口径就不给将来留坑（与 V146 的数量列同口径）。
  impressions NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  clicks NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  orders NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (orders >= 0),
  -- 🔴 钱一律 NUMERIC(19,4)
  spend NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (spend >= 0),
  sales NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (sales >= 0),

  -- 🔴 派生比率是 GENERATED 列：服务层写不进去，绕过服务层也编不出与原始量对不上的比率。
  --   分母为 0 给 NULL（不可比）而不是 0。
  ctr NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN impressions > 0 THEN round(clicks / impressions, 6) ELSE NULL END
  ) STORED,
  -- ACOS = 消耗 / 销售额；ROAS = 销售额 / 消耗。**口径写在这里，不写在注释里**：
  --   跨部门沟通时「ACOS 到底是哪个除哪个」必须是同一份定义。
  acos NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN sales > 0 THEN round(spend / sales, 6) ELSE NULL END
  ) STORED,
  roas NUMERIC(19,6) GENERATED ALWAYS AS (
    CASE WHEN spend > 0 THEN round(sales / spend, 6) ELSE NULL END
  ) STORED,

  -- 这行数据是何时同步进来的
  synced_at timestamptz NOT NULL,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, campaign_id, metric_date)
);

-- 列表的默认聚合入口：按租户 + 日期区间扫明细
CREATE INDEX ad_campaign_daily_metric_window
  ON ad_campaign_daily_metric(tenant_id, metric_date, campaign_id);

-- ===========================================================================
-- 4. ad_campaign_operation_log · 批量操作审计
-- ===========================================================================
-- 14 章关键约束：「批量调整预算与竞价要走规则并留审计，AI/规则自动调整必须有上限保护」。
-- 本表是「留审计」的落点：**成功与被拒都要留**（被拒的那几条同样要能复盘）。
CREATE TABLE ad_campaign_operation_log (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  campaign_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, campaign_id)
    REFERENCES ad_campaign(tenant_id, id) ON DELETE CASCADE,

  operation text NOT NULL CHECK (operation IN ('PAUSE','ENABLE','SET_BUDGET','ADJUST_BUDGET')),

  before_status varchar(32) NOT NULL DEFAULT '',
  after_status varchar(32) NOT NULL DEFAULT '',
  before_budget NUMERIC(19,4),
  after_budget NUMERIC(19,4),

  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',

  -- APPLIED  已生效
  -- REJECTED 被护栏或校验拒（**也要留**：否则「为什么这条没生效」查不出来）
  -- SKIPPED  无需执行（例如已经是目标状态）
  result text NOT NULL CHECK (result IN ('APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX ad_campaign_operation_log_campaign ON ad_campaign_operation_log(tenant_id, campaign_id, created_at DESC);
CREATE INDEX ad_campaign_operation_log_recent ON ad_campaign_operation_log(tenant_id, created_at DESC);

-- ===========================================================================
-- 5. 触发器
-- ===========================================================================
-- (a) 🔴 广告类型的三列**从档案复制**，且活动 / 档案 / 店铺 / 平台四者必须自洽。
--     自己查档案而不是读 NEW 上的列 —— 与 V146 同样的纪律：依赖触发器执行顺序会得到
--     随名字排序漂移的结果。
CREATE OR REPLACE FUNCTION guard_ad_campaign_identity() RETURNS trigger AS $$
DECLARE
  p_platform_ad_type varchar(64);
  p_internal_ad_type varchar(32);
  p_mapping_source text;
  p_profile_platform uuid;
  p_shop_platform uuid;
BEGIN
  SELECT platform_ad_type, internal_ad_type, mapping_source, platform_id
    INTO p_platform_ad_type, p_internal_ad_type, p_mapping_source, p_profile_platform
    FROM ad_type_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.ad_type_profile_id;

  IF p_platform_ad_type IS NULL THEN
    RAISE EXCEPTION '广告类型档案不存在（tenant=% profile=%）', NEW.tenant_id, NEW.ad_type_profile_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  -- 🔴 档案的平台必须与活动一致：否则一条 TikTok 活动可以挂到 Amazon 档案上，
  --   跨平台可比表会因此出现「口径不同的两行被当成同一类」。
  IF p_profile_platform <> NEW.platform_id THEN
    RAISE EXCEPTION '广告类型档案属于平台 %，与活动的平台 % 不一致', p_profile_platform, NEW.platform_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 店铺的平台也必须与活动一致（店铺是活动的归属地）。
  SELECT platform_id INTO p_shop_platform
    FROM shop
   WHERE tenant_id = NEW.tenant_id AND code = NEW.shop_code;

  IF p_shop_platform IS NULL THEN
    RAISE EXCEPTION '店铺不存在（tenant=% shop=%）', NEW.tenant_id, NEW.shop_code
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF p_shop_platform <> NEW.platform_id THEN
    RAISE EXCEPTION '店铺 % 属于平台 %，与活动的平台 % 不一致', NEW.shop_code, p_shop_platform, NEW.platform_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 三列由档案说了算：服务层无法把一条 SB 报成自动广告，
  --   也无法把 UNMAPPED 报成已归一。
  NEW.platform_ad_type := p_platform_ad_type;
  NEW.internal_ad_type := p_internal_ad_type;
  NEW.ad_type_mapping_source := p_mapping_source;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- 🔴 挂在**所有** UPDATE 上（不是 UPDATE OF 某一列）：归一类型那三列不许被任何 UPDATE 改写，
--   只改状态或预算的行也会重新从档案复制一次 —— 值本来就没变，多一次校验换来「改不掉」。
CREATE TRIGGER guard_ad_campaign_identity_trg
  BEFORE INSERT OR UPDATE ON ad_campaign
  FOR EACH ROW EXECUTE FUNCTION guard_ad_campaign_identity();

-- (b) 🔴 平台侧活动 id 一旦登记就不能就地改：14.3.1.1 报表按它快照过，
--     改了再也复算不出来。要换活动请新建一条。
CREATE OR REPLACE FUNCTION guard_ad_campaign_external_immutable() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND (
      NEW.external_campaign_id <> OLD.external_campaign_id
      OR NEW.platform_id <> OLD.platform_id
      OR NEW.shop_code <> OLD.shop_code) THEN
    RAISE EXCEPTION
      '广告活动的平台 / 店铺 / 平台侧 id 不允许就地改（external_campaign_id %）—— 报表已按这些值快照过，改了就再也复算不出来；请新建一条',
      OLD.external_campaign_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_ad_campaign_external_immutable_trg
  BEFORE UPDATE ON ad_campaign
  FOR EACH ROW EXECUTE FUNCTION guard_ad_campaign_external_immutable();

-- ===========================================================================
-- 6. 多租户隔离：四张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_campaign', 'ad_campaign_daily_metric', 'ad_campaign_sync_run', 'ad_campaign_operation_log' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**店铺**收敛（蓝图的输入「用户数据范围」）
-- ---------------------------------------------------------------------------
-- 与 V171 listing / V203 listing_performance 同构：广告活动挂在店铺下，
-- 运营不能通过投放数据看到无权店铺的投放情况 —— 也不允许绕过店铺直接枚举。
CREATE POLICY data_scope ON ad_campaign AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign.tenant_id AND s.code=ad_campaign.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign.tenant_id AND s.code=ad_campaign.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_daily_metric AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_daily_metric.tenant_id AND c.id=ad_campaign_daily_metric.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_daily_metric.tenant_id AND c.id=ad_campaign_daily_metric.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_sync_run AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign_sync_run.tenant_id AND s.code=ad_campaign_sync_run.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=ad_campaign_sync_run.tenant_id AND s.code=ad_campaign_sync_run.shop_code
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON ad_campaign_operation_log AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_operation_log.tenant_id AND c.id=ad_campaign_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_operation_log.tenant_id AND c.id=ad_campaign_operation_log.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 7. 账号与授权（沿用 V1/V146 的条件建角色，保证迁移可重复执行）
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
  ad_campaign, ad_campaign_daily_metric, ad_campaign_sync_run, ad_campaign_operation_log TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 同口径）。
REVOKE ALL ON
  ad_campaign, ad_campaign_daily_metric, ad_campaign_sync_run, ad_campaign_operation_log FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：广告活动管理属于「平台操作」语义，与 14.1.2.2 广告类型差异、
-- 10.1.1.2 渠道配置、2.1.2.1 平台枚举映射同族，因此 identity 服务零改动。
