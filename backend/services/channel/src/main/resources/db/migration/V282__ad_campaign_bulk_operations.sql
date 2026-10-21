-- ===========================================================================
-- 14.1.1.3 批量操作（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/14-ads-and-growth.md）：
--   ### 14.1.1.3 批量操作
--   - 职责：跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，
--           支持按筛选结果全选并预览变更。
--   - 输入：选中活动集合；调整参数；预览确认。
--   - 输出：批量调整任务（异步）+ 每条执行结果。
--   - 关系：上游 = 14.1.1.1；下游 = 平台广告 API（经 2 队列）。
--           **批量调价要有单次上限与总消耗上限保护**，误操作一次可能烧掉整月预算。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V273 `ad_campaign`（**已交付的上游 14.1.1.1**）—— 活动本体：预算 / 状态 / 结束时间
--     / pending_platform_writeback 都在这里，批量调整改的就是它。
--   * V277 `ad_campaign_group`（**已交付的上游 14.1.1.2**）—— 🔴 竞价的落点是**广告组**，
--     不是活动。批量改竞价写 `ad_campaign_group.default_bid`。
--   * V277 `ad_campaign_group_bid_log` —— 改竞价的审计（APPLIED / REJECTED / SKIPPED 三种
--     结果都留痕）。本模块是它的**第二个写入方**（14.1.1.2 是第一个），理由见第五节。
--   * V273 `ad_campaign_operation_log` —— 活动级操作审计，同样是本模块的第二个写入方。
--   * V1 `shop` —— 数据范围随**活动 → 店铺**收敛（与 V273 同构）。
--
-- 🔴 **不建** 14.1.2.1 广告组管理的表（同批并行车道，V281）：本模块**只读** `ad_campaign_group`，
--   **只写** `default_bid`，不碰广告组的增删改与其它字段。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 这两张表存在的理由：「批量」是「一次提交很多条」，不是「全成功或全失败」
-- ---------------------------------------------------------------------------
-- 上游 14.1.1.1 的 `POST /bulk-operations` 已经能做「暂停 / 启用 / 调预算」，但它是
-- **同步**返回逐条结果的，而且**只接受显式选中的 id**。蓝图这一段要的是另外三件：
--
--   1. **按筛选结果全选**：把筛选条件交给服务端，由服务端解析出 id 集合 ——
--      让前端把 id 全拉回来再循环等于全表扫。
--   2. **预览变更**：不落库的预演，先看清「会变成什么样」再点下去。
--   3. **异步任务**：提交即返回任务，逐条结果事后查。
--
-- 任务表 + 任务项表是这个「可查、可审、可追责」的落点：
--
--   ad_campaign_bulk_task        批量调整任务（一次提交 = 一个任务）
--   ad_campaign_bulk_task_item   每条目标的执行结果（**逐条**）
--
-- 🔴 为什么任务项要单独一张表：蓝图的输出写的是「批量调整任务（异步）+ **每条执行结果**」。
--   只在任务行上放几个计数，「为什么这一条没生效」就永远查不出来 —— 计数说「有 3 条被拒」，
--   但被拒的是哪三条、为什么，逐条结果才是答案。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 状态机：PENDING / RUNNING / DONE / PARTIAL / FAILED / REJECTED
-- ---------------------------------------------------------------------------
-- 🔴 **这是本地任务，不是平台队列**：平台广告 API 未交付，「下游 = 平台广告 API（经 2 队列）」
--   那条队列在本系统里**还不存在**。所以本任务跑完 = **本地已经改了**，
--   **平台侧还没改** —— 这一点由 V273 的 `pending_platform_writeback` 标记，界面上必须显式写出来。
--
--   PENDING   已受理，等执行器领取
--   RUNNING   执行器已领取，正在逐条执行
--   DONE      全部条目都已生效或跳过（没有一条被拒）
--   PARTIAL   一部分生效 / 跳过，一部分被拒（**逐条**业务规则不满足，不是护栏）
--   FAILED    任务本身失败（执行器异常）；逐条结果保留已写下的部分
--   REJECTED  **整批被护栏拒绝**：一条都没有执行
--
-- 🔴 REJECTED 与 PARTIAL 是两件不同的事：
--   护栏（单次上限 / 总额上限 / 条数上限）超限时**整批拒绝** —— 蓝图那句
--   「误操作一次可能烧掉整月预算」要的就是这个：宁可一条都不做，也不要「做了一半」。
--   逐条的业务规则（比如「已归档的活动不能暂停」）才是 PARTIAL 的来源。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 护栏上限：存在表里、看得见、查得到
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「批量调价要有**单次上限**与**总消耗上限**保护」。
-- 上限不是散落在代码里的常量 —— 它们**随任务快照进表**（guardrail_* 列），
-- 于是「这次批量当时的天花板是多少」是可查的事实，而不是要翻代码才知道。
--
-- 四个维度各有一对上限：
--   预算：单条调整上限（max_single_budget_delta）+ 一批总额上限（max_total_budget_delta）
--         + 调整后单活动预算上限（max_campaign_budget）
--   竞价：单组调整上限（max_single_bid_delta）+ 单组竞价上限（max_bid）
--         + 一批总额上限（max_total_bid_delta）
--
-- 🔴 **总额上限就是「总消耗上限」**：一次批量把 200 条活动的预算各加 5000，
--   总额 100 万 —— 单条看每条都没越限，整批却已经烧掉整月预算。
--   只逐条判上限是拦不住这种的，必须有一条**跨条目累加**的总额上限。
--
-- 🔴 **竞价没有消耗预估**：本模块**没有**竞价 → 曝光 / 消耗的预估模型，
--   所以预览只报「Δ 竞价」，**不编**一个假的消耗预估数字出来 ——
--   编出来的预估会被当成事实，运营会照着它做决策。真正的钱袋子约束是活动日预算，
--   那属于 14.2.2.1 预算分配的职责。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 选择方式：FILTER（按筛选全选）/ EXPLICIT（显式勾选）
-- ---------------------------------------------------------------------------
-- 两种选择方式都**在服务端解析**：
--   FILTER   把筛选条件（平台 / 店铺 / 状态 / 类型 / 关键词）交给服务端解析出 id 集合。
--            解析在 SQL 里做（不是「先把 id 拉回前端再循环」），并带游标与硬上限。
--   EXPLICIT 调用方直接给出 id 集合（仍然由服务端逐条校验归属，不信任调用方）。
--
-- 两种方式都会把**解析结果快照**进 `ad_campaign_bulk_task_item` ——
-- 任务执行时改的就是快照里的那几条，不会因为「筛选结果后来变了」而改到别的东西上。
-- `filter_snapshot` / `explicit_ids` 留的是**当时的意图**，供事后复盘。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 审计：三方留痕
-- ---------------------------------------------------------------------------
-- 本模块是两张上游审计表的**第二个写入方**，这是有意的：
--   * `ad_campaign_operation_log`（V273）：活动级的预算 / 状态改动。
--     14.1.1.1 是第一个写入方，本模块是第二个。
--   * `ad_campaign_group_bid_log`（V277）：组级竞价的改动。14.1.1.2 是第一个写入方。
--
-- 理由：这两张表记的是**「谁在什么时候把这个活动 / 这个广告组改了」**这个事实。
-- 改动来自「批量任务」而不是「单条操作」不该让事实消失 —— 少了这一条，
-- 活动详情里的操作历史就会漏掉一整批改动，比重复记一条严重得多。
--
-- 🔴 唯一的例外是「结束时间设置」（SET_END_AT）：`ad_campaign_operation_log.operation`
--   的 CHECK 枚举只有 PAUSE / ENABLE / SET_BUDGET / ADJUST_BUDGET，
--   **加一项要 ALTER 上游的表**（那是 14.1.1.1 的表，本模块不越界改）。
--   所以结束时间的审计落在本模块自己的 `ad_campaign_bulk_task_item` 上 ——
--   宁可少写一张表，也不用「借一个不相关的 operation 值」把事实写歪。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量：禁浮点；时间：timestamptz
-- ---------------------------------------------------------------------------
-- 预算 / 竞价 / 增量一律 **NUMERIC(19,4)**（与 V273 / V277 同口径），配 BigDecimal。
-- 结束时刻用 `timestamptz`（V273 的 `ad_campaign.end_at` 同类型），
-- 服务层用 OffsetDateTime 绑定并在 values 位置写 `?::timestamptz` ——
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s。
--
-- 🔴 「结束时间不能设到过去」这条规则**刻意不做成 ad_campaign 上的触发器**：
--   平台同步会回写**已经结束**的活动（它的 end_at 本来就在过去），
--   一条「end_at 必须晚于 now()」的触发器会把正常的同步打挂 ——
--   那是别人的表、别人的流程，不能为了自己的一条规则去改。
--   所以这条规则落在**服务层的逐条校验**上（逐条 REJECTED 并写明原因）。
--
-- ===========================================================================
-- 1. ad_campaign_bulk_task · 批量调整任务
-- ===========================================================================
CREATE TABLE ad_campaign_bulk_task (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 六种操作对应蓝图职责句的四个词：
  --   预算增减 = ADJUST_BUDGET（增减）/ SET_BUDGET（设为）
  --   竞价调整 = ADJUST_BID（落在**广告组**上）
  --   状态切换 = PAUSE / ENABLE
  --   结束时间 = SET_END_AT
  operation text NOT NULL
    CHECK (operation IN ('ADJUST_BUDGET','SET_BUDGET','PAUSE','ENABLE','SET_END_AT','ADJUST_BID')),

  -- 🔴 目标落在哪一层：活动（预算 / 状态 / 结束时间）还是广告组（竞价）。
  --   竞价是组级事实 —— 把它记成活动级会让「改了哪个组」永远答不出来。
  target_type text NOT NULL CHECK (target_type IN ('CAMPAIGN','AD_GROUP')),

  status text NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','RUNNING','DONE','PARTIAL','FAILED','REJECTED')),

  -- 选择方式与**当时**的选择意图（事后复盘用）
  selection_mode text NOT NULL CHECK (selection_mode IN ('FILTER','EXPLICIT')),
  filter_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 显式勾选的 id 快照：存成 jsonb 而不是 uuid[]，
  --   与 catalog 的 export_job.selected_fields 同一口径（值本身是 id，形状用 jsonb 表达）。
  explicit_ids jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 调整参数快照
  param_budget NUMERIC(19,4),
  param_delta NUMERIC(19,4),
  param_bid NUMERIC(19,4),
  param_bid_delta NUMERIC(19,4),
  param_end_at timestamptz,
  param_clear_end_at boolean NOT NULL DEFAULT false,
  reason text NOT NULL DEFAULT '',

  -- 条数
  target_count int NOT NULL DEFAULT 0 CHECK (target_count >= 0),
  applied_count int NOT NULL DEFAULT 0 CHECK (applied_count >= 0),
  rejected_count int NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
  skipped_count int NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),

  -- 🔴 总增量：预览算一次、执行再算一次，两次都要落库。
  --   「这一批总共动了多少钱」是运营事后对账的第一句话。
  --   🔴 净增量（带方向）与**绝对敞口**分开存：护栏判的是绝对敞口。
  --   「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」—— 只按净变化判上限，
  --   一批「有的加有的减」就能把总额上限绕过去，那正是最典型的烧钱形状。
  total_budget_delta NUMERIC(19,4) NOT NULL DEFAULT 0,
  total_budget_delta_abs NUMERIC(19,4) NOT NULL DEFAULT 0,
  total_bid_delta NUMERIC(19,4) NOT NULL DEFAULT 0,
  total_bid_delta_abs NUMERIC(19,4) NOT NULL DEFAULT 0,

  -- 🔴 当时的选择条件摘要（人话）：复盘时不用去猜 filter_snapshot 里那串 json
  selection_summary text NOT NULL DEFAULT '',

  -- 🔴 护栏快照：本次生效的天花板（当时是多少，事后查得到）
  guardrail_max_campaigns int NOT NULL,
  guardrail_max_groups int NOT NULL,
  guardrail_max_single_budget_delta NUMERIC(19,4) NOT NULL,
  guardrail_max_total_budget_delta NUMERIC(19,4) NOT NULL,
  guardrail_max_campaign_budget NUMERIC(19,4) NOT NULL,
  guardrail_max_bid NUMERIC(19,4) NOT NULL,
  guardrail_max_single_bid_delta NUMERIC(19,4) NOT NULL,
  guardrail_max_total_bid_delta NUMERIC(19,4) NOT NULL,

  -- 🔴 数据范围的依据：**任务项指向的活动**（见下面的 data_scope 策略）。
  --   任务行必须**先于**任务项写入这件事不成立 —— 任务项的外键是**延迟**的（DEFERRABLE），
  --   所以服务层可以先写任务项、再写任务行。

  -- 谁发起的（已认证成员，租户来自成员关系而非请求头）
  requested_by uuid NOT NULL,
  -- 整批被拒 / 执行失败时的原因
  message text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  -- 🔴 领取标记由 DB 裁决（与 catalog 的 export_job 同一纪律）：
  --   两个执行者各写各的局部计数，后写的会把前一个的进度抹掉。
  worker_claimed_at timestamptz,

  UNIQUE(tenant_id, id),

  -- 🔴 「整批被拒」与「执行过但一条都没生效」是相反的两件事：
  --   REJECTED 的任务**一条都不许生效**（applied_count 恒为 0），
  --   但它的任务项**要留着** —— 「是第 7 条越了上限」是事后复盘要看的第一件事。
  CHECK (status <> 'REJECTED' OR applied_count = 0)
);

CREATE INDEX ad_campaign_bulk_task_recent
  ON ad_campaign_bulk_task(tenant_id, created_at DESC);
CREATE INDEX ad_campaign_bulk_task_status
  ON ad_campaign_bulk_task(tenant_id, status, created_at DESC);

-- ===========================================================================
-- 2. ad_campaign_bulk_task_item · 每条执行结果
-- ===========================================================================
CREATE TABLE ad_campaign_bulk_task_item (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  task_id uuid NOT NULL,
  -- 🔴 外键**延迟到提交时**检查（DEFERRABLE INITIALLY DEFERRED）：
  --   任务的可见性靠**任务项**判定（见下面的 data_scope 策略），
  --   而任务项又必须属于任务 —— 不延迟的话只能靠「任务行里存一份店铺数组」绕开，
  --   那样可见性就成了快照（店铺换了负责人，任务还会按旧范围可见）。
  --   延迟之后可以「先写任务项、再写任务行」，可见性始终按**当下**的活动 → 店铺判定。
  FOREIGN KEY (tenant_id, task_id)
    REFERENCES ad_campaign_bulk_task(tenant_id, id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED,

  target_type text NOT NULL CHECK (target_type IN ('CAMPAIGN','AD_GROUP')),

  -- 🔴 目标 id **不建 FK**（与 V277 的 ad_campaign_group_bid_log 同一纪律）：
  --   审计要能记下「我想改的那个活动 / 那个组压根不存在」——
  --   有 FK 的话这条审计自己写不进去，「为什么这条没生效」就永远查不出来。
  --   审计表记的是**当时的目标**，不是「当时存在的行」。
  campaign_id uuid NOT NULL,
  group_id uuid,

  -- 名称快照：审计要能读懂（活动后来改名了也不影响复盘）
  campaign_name varchar(200) NOT NULL DEFAULT '',
  group_name varchar(200) NOT NULL DEFAULT '',

  -- 🔴 before_* / after_* 在**创建时**是「计划值」（预览算出来的那份），
  --   执行时会被**实际值**覆盖 —— 执行前显示的是打算变成什么，执行后显示的是真的变成了什么。
  before_status varchar(32) NOT NULL DEFAULT '',
  after_status varchar(32) NOT NULL DEFAULT '',
  before_budget NUMERIC(19,4),
  after_budget NUMERIC(19,4),
  before_bid NUMERIC(19,4),
  after_bid NUMERIC(19,4),
  before_end_at timestamptz,
  after_end_at timestamptz,

  -- PENDING  还没轮到（任务刚建好）
  -- APPLIED  已生效
  -- REJECTED 被逐条业务规则拒（**也要留**：否则查不出为什么没生效）
  -- SKIPPED  无需执行（例如已经是目标状态）
  result text NOT NULL DEFAULT 'PENDING'
    CHECK (result IN ('PENDING','APPLIED','REJECTED','SKIPPED')),
  message text NOT NULL DEFAULT '',
  executed_at timestamptz,

  UNIQUE(tenant_id, id),

  -- 🔴 AD_GROUP 必须带 group_id；CAMPAIGN 必须不带。
  --   两层混在一列里会让「这个竞价改的是哪个组」答不出来。
  CHECK ((target_type = 'CAMPAIGN' AND group_id IS NULL)
      OR (target_type = 'AD_GROUP' AND group_id IS NOT NULL))
);

CREATE INDEX ad_campaign_bulk_task_item_task
  ON ad_campaign_bulk_task_item(tenant_id, task_id, id);
CREATE INDEX ad_campaign_bulk_task_item_campaign
  ON ad_campaign_bulk_task_item(tenant_id, campaign_id);
CREATE INDEX ad_campaign_bulk_task_item_group
  ON ad_campaign_bulk_task_item(tenant_id, group_id) WHERE group_id IS NOT NULL;

-- ===========================================================================
-- 3. 多租户隔离：两张表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V146 / V171 / V203 / V273 / V277 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'ad_campaign_bulk_task', 'ad_campaign_bulk_task_item' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：可见性随**活动 → 店铺**收敛（与 V273 / V277 同构）
-- ---------------------------------------------------------------------------
-- 🔴 任务行的可见性 = 「它至少有一条任务项落在我的可见范围内」。
--   任务项自己的 data_scope 策略已经按活动 → 店铺收紧了，
--   所以这里反查任务项就等于「按我看得见的那些活动」判定 —— 不用在任务行上存快照。
--   （任务项的外键是延迟的，所以服务层先写任务项、后写任务行，写入时这个判定就有依据。）
CREATE POLICY data_scope ON ad_campaign_bulk_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign_bulk_task_item i
                 WHERE i.tenant_id=ad_campaign_bulk_task.tenant_id AND i.task_id=ad_campaign_bulk_task.id
                 AND EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
                            WHERE c.tenant_id=i.tenant_id AND c.id=i.campaign_id
                            AND channel_scope(channel_operation(),s.owner_id,s.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign_bulk_task_item i
                 WHERE i.tenant_id=ad_campaign_bulk_task.tenant_id AND i.task_id=ad_campaign_bulk_task.id
                 AND EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
                            WHERE c.tenant_id=i.tenant_id AND c.id=i.campaign_id
                            AND channel_scope(channel_operation(),s.owner_id,s.department_id))));

-- 🔴 任务项按**它指向的活动**收敛：看不到的活动，其逐条结果也看不到。
CREATE POLICY data_scope ON ad_campaign_bulk_task_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_bulk_task_item.tenant_id AND c.id=ad_campaign_bulk_task_item.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM ad_campaign c JOIN shop s ON s.tenant_id=c.tenant_id AND s.code=c.shop_code
             WHERE c.tenant_id=ad_campaign_bulk_task_item.tenant_id AND c.id=ad_campaign_bulk_task_item.campaign_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 4. 账号与授权（沿用 V1/V146/V273 的条件建角色，保证迁移可重复执行）
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
  ad_campaign_bulk_task, ad_campaign_bulk_task_item TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V146 / V273 同口径）。
REVOKE ALL ON
  ad_campaign_bulk_task, ad_campaign_bulk_task_item FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本迁移**不新增权限码**：批量调整广告属于「平台操作」语义，与 14.1.1.1 活动列表、
-- 14.1.1.2 活动详情、14.1.2.2 广告类型差异同族，因此 identity 服务零改动。
