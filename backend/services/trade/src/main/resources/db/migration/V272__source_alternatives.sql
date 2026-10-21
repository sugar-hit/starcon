-- 6.1.2.2 同款货源推荐（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 6.1.2.2 原文：
--   职责：当某 SKU 缺货或质量不达标时，推荐可替代的同款/相似货源：
--         同款不同供应商、历史采购过该货的供应商、价格对比。
--   输入：SKU 编码与图片；历史采购记录；货源库匹配结果。
--   输出：候选货源列表（供应商、价格、交期、历史质量评分）+ 一键生成采购申请。
--   关系：上游 = 6.1.2.1（档口与货源池）、6.1.1.3（供应商评级）、6.3 采购历史；
--         下游 = 6.2.2.1 采购申请。
--         **同款判定要给置信度**，图片相似不等于同款，误判会导致采购到错误商品。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 前端路由 /supply/alternatives，后端命名空间 /trade/source-alternatives ——
-- 与 6.1.2.1（前端 /supply/sources、后端 /trade/sources）、6.1.2.3（/supply/qualifications、
-- /trade/supplier-qualifications）同一纪律，各占一个独立分区。
-- 本迁移**不 ALTER 任何既有表**，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、**同款判定凭什么**：三种证据，强度不同，**都要落库并带置信度**
-- ===========================================================================
-- 蓝图那句加粗句是本模块的生死线：「**同款判定要给置信度**，图片相似不等于同款，
-- 误判会导致采购到错误商品」。于是本模块**不敢**做的事先写清楚：
--
--   * **不做图片相似度自动判定** —— 本仓没有视觉相似度服务，凭空实现一个
--     「图片哈希像所以是同款」就是把采购单指向错误商品。capabilities 里如实写明未交付。
--   * **不把「同一个供应商」当成「同款」** —— 同供应商的货可能是完全不同的规格。
--   * **不给一个拍脑袋的总分** —— 排序分由**租户级权重**（本表第 1 张）合成，
--     四个维度（价格 / 评级 / 历史 / 交期）各占多少写在库里、可查、可改，
--     不是代码里写死的常数。
--
-- 三种证据，强度从高到低：
--
--   1) SKU_LINK          —— **确证**：6.1.2.1 的 supplier_source_sku_link 里已经
--      **人工确认**过「这个货源就是内部这个 SKU」。置信度 1.0000。
--   2) PURCHASE_HISTORY  —— **强线索**：这个供应商**历史采购过这个 SKU**
--      （6.3 采购单事实）。它说明「这家供过这个货」，但**没说这次是同款**，
--      所以仍需人工确认。置信度 0.5000 ~ 0.7500（按历史笔数递增）。
--   3) TITLE_SPEC_MATCH  —— **弱线索**：标题与规格**完全一致**（或标题包含），
--      但**没有任何人工关联**。置信度 0.3000 / 0.4000。
--      ⚠️ 这类候选**必须人工确认**才能生成采购申请（DB 层钉死，见第 3 张表的 CHECK）。
--
-- ===========================================================================
-- 二、**四张表，零 ALTER 任何既有表**
-- ===========================================================================
--   1) source_alternative_policy     租户级规则与权重（每租户一行）
--   2) source_alternative_run        一次推荐（批次头）
--   3) source_alternative_candidate  候选快照（**只追加**：证据是那一刻的事实）
--   4) source_alternative_decision   人工判定事件（**只追加**）
--
-- 跨服务只存**逻辑 ID + 展示快照**，不建跨库 FK / 视图 / SQL：
--   * 内部 SKU（catalog）：source_alternative_run.sku_id + sku_code / sku_name 快照；
--   * 供应商（catalog 的 party）：candidate.supplier_ref_id + supplier_code / supplier_name 快照
--     （快照取自 6.1.2.1 档口上登记的 supplier_name_snapshot）；
--   * 目标仓与法人（warehouse）：run.target_warehouse_* / legal_entity_* 快照。
--
-- ===========================================================================
-- 一、租户级规则与权重（每租户一行；没有就用代码里的默认值，但**改了就以库里的为准**）
-- ===========================================================================
CREATE TABLE source_alternative_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- **弱匹配进入候选清单的最低置信度**：疑似同款（标题+规格一致）低于它就不推荐。
  weak_match_min_confidence numeric(5,4) NOT NULL DEFAULT 0.3000
    CHECK(weak_match_min_confidence >= 0 AND weak_match_min_confidence <= 1),
  -- **确证匹配（人工关联）的最低置信度**。
  confirmed_match_min_confidence numeric(5,4) NOT NULL DEFAULT 0.9000
    CHECK(confirmed_match_min_confidence >= 0 AND confirmed_match_min_confidence <= 1),
  -- **弱匹配必须人工确认才能生成采购申请**。默认开着：蓝图那句「误判会导致采购到
  -- 错误商品」是默认值的选择依据，不是可选项。
  require_manual_confirmation boolean NOT NULL DEFAULT true,
  -- 单次推荐最多落多少候选（防止一个宽泛的 SKU 拉出几千行）。
  max_candidates int NOT NULL DEFAULT 50
    CHECK(max_candidates > 0 AND max_candidates <= 500),

  -- ---- 四个排序维度的权重：和必须**精确等于 1**（DB 层钉死，不是应用层自觉）----
  price_weight numeric(9,6) NOT NULL DEFAULT 0.300000
    CHECK(price_weight >= 0 AND price_weight <= 1),
  rating_weight numeric(9,6) NOT NULL DEFAULT 0.250000
    CHECK(rating_weight >= 0 AND rating_weight <= 1),
  history_weight numeric(9,6) NOT NULL DEFAULT 0.200000
    CHECK(history_weight >= 0 AND history_weight <= 1),
  lead_time_weight numeric(9,6) NOT NULL DEFAULT 0.250000
    CHECK(lead_time_weight >= 0 AND lead_time_weight <= 1),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- **每租户只有一行策略**：两行策略会让「这个租户用哪套权重」变成答不出来的问题。
  UNIQUE(tenant_id),
  CONSTRAINT source_alternative_policy_weights_sum CHECK(
    price_weight + rating_weight + history_weight + lead_time_weight = 1
  )
);

COMMENT ON TABLE source_alternative_policy IS
  '6.1.2.2 同款货源推荐的租户级规则与排序权重：权重和必须精确等于 1（DB CHECK）';

-- ===========================================================================
-- 二、一次推荐（批次头）
-- ===========================================================================
CREATE TABLE source_alternative_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 推荐批次号（租户内唯一）：ALT-20261021-a1b2c3d4。
  run_no varchar(64) NOT NULL CHECK(btrim(run_no) <> ''),

  -- ---- 目标 SKU（catalog 独立库 ⇒ 逻辑引用 + 展示快照，不建跨库 FK）----
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- **推荐理由**：缺货（STOCKOUT）还是质量不达标（QUALITY）。
  -- 闭集：蓝图只点名这两种，但「为什么换货源」不落库，三个月后没人说得清。
  reason varchar(16) NOT NULL CHECK(reason IN ('STOCKOUT','QUALITY')),
  -- 需要补的量（可空 = 运营还没定量）。
  required_quantity numeric(19,6)
    CHECK(required_quantity IS NULL OR required_quantity > 0),

  -- **目标 SKU 的参考图**（取自已关联货源的最新快照）。
  -- 🔴 这张图**只给人看** —— 系统不拿它做相似度判定（没有依据就没有结论）。
  reference_image_url text NOT NULL DEFAULT '',

  -- 批次状态：OPEN 还没下单 / REQUESTED 已生成采购申请 / CLOSED 人工关闭。
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','REQUESTED','CLOSED')),

  -- 落了多少候选 / 确认了几条（快照，写的时候定下来）。
  candidate_count int NOT NULL DEFAULT 0 CHECK(candidate_count >= 0),
  confirmed_count int NOT NULL DEFAULT 0 CHECK(confirmed_count >= 0),

  -- ---- 目标仓与法人（warehouse 独立库 ⇒ 只存 id + 展示快照）----
  target_warehouse_id uuid,
  target_warehouse_code varchar(64) NOT NULL DEFAULT '',
  target_warehouse_name varchar(200) NOT NULL DEFAULT '',
  legal_entity_id uuid,
  legal_entity_code varchar(64) NOT NULL DEFAULT '',
  legal_entity_name varchar(200) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_no)
);

COMMENT ON TABLE source_alternative_run IS
  '6.1.2.2 一次同款货源推荐：候选证据是那一刻的快照，判定事件另表追加';

-- ===========================================================================
-- 三、候选货源快照（**只追加**：证据不可改写）
-- ===========================================================================
CREATE TABLE source_alternative_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL
    REFERENCES source_alternative_run(id) ON DELETE CASCADE,

  -- ---- 货源身份（6.1.2.1，同库同租户只读）----
  item_id uuid NOT NULL,
  snapshot_id uuid,
  snapshot_seq int CHECK(snapshot_seq IS NULL OR snapshot_seq >= 1),
  stall_id uuid NOT NULL,
  platform varchar(32) NOT NULL,
  stall_name varchar(200) NOT NULL DEFAULT '',
  platform_item_id varchar(128) NOT NULL DEFAULT '',
  -- **供应商（catalog 的 party）**：逻辑引用 + 名称快照。
  supplier_ref_id uuid,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 货源快照里的事实（价格永远是区间）----
  title varchar(400) NOT NULL DEFAULT '',
  spec varchar(200) NOT NULL DEFAULT '',
  image_url text NOT NULL DEFAULT '',
  price_min numeric(19,4) CHECK(price_min IS NULL OR price_min > 0),
  price_max numeric(19,4) CHECK(price_max IS NULL OR price_max > 0),
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  moq numeric(19,6) CHECK(moq IS NULL OR moq > 0),
  -- **采集时刻**：这些价格是「某一次采集看到的值」，永不改写。
  collected_at timestamptz,
  on_sale boolean NOT NULL DEFAULT true,

  -- ---- 判定：类型 + 置信度 + 人话依据 ----
  match_type varchar(24) NOT NULL
    CHECK(match_type IN ('SKU_LINK','PURCHASE_HISTORY','TITLE_SPEC_MATCH')),
  -- **置信度只衡量「是不是同款」**，与排序分（score）不是一回事：
  -- 便宜的错货，置信度一样低。
  confidence numeric(5,4) NOT NULL
    CHECK(confidence >= 0 AND confidence <= 1),
  -- **判定依据**：人话，写清凭什么把它算成候选。
  match_basis text NOT NULL CHECK(length(btrim(match_basis)) > 0),
  -- **需不需要人工再确认**：弱匹配一律要（DB 层钉死，见下方 CHECK）。
  requires_manual_confirmation boolean NOT NULL,

  -- ---- 历史事实（6.3 采购历史 / 6.1.1.3 评级）----
  history_order_count int NOT NULL DEFAULT 0 CHECK(history_order_count >= 0),
  history_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(history_quantity >= 0),
  -- **历史平均交期**（建单到承诺到货的天数）；没有历史 = NULL（**不是 0 天**）。
  lead_time_days numeric(12,2)
    CHECK(lead_time_days IS NULL OR lead_time_days >= 0),
  -- **评级：客观分与最终分并排存**，人工调整过要单独标出来
  -- （蓝图 6.1.1.3：评级必须由客观数据算出，人工加权分需单独标记）。
  rating_objective_score numeric(9,4),
  rating_final_score numeric(9,4),
  rating_grade varchar(1) CHECK(rating_grade IS NULL OR rating_grade IN ('A','B','C','D')),
  rating_manually_adjusted boolean,
  rating_period_start date,
  rating_period_end date,

  -- ---- 价格对比（同币种内；**不跨币种换算** —— 汇率属 12 财务）----
  price_rank int CHECK(price_rank IS NULL OR price_rank > 0),
  is_lowest_price boolean NOT NULL DEFAULT false,

  -- **排序分**：按租户级权重合成的 0~1 分。**不是置信度**。
  score numeric(9,6) NOT NULL DEFAULT 0
    CHECK(score >= 0 AND score <= 1),
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个货源在同一个批次里只出现一次。
  UNIQUE(tenant_id, run_id, item_id),
  CONSTRAINT source_alternative_candidate_price_range CHECK(
    price_min IS NULL OR price_max IS NULL OR price_max >= price_min
  ),
  -- 🔴 **确证（人工关联）不再要求人工确认；弱匹配一律要求**。
  --    这条 CHECK 把蓝图那句「误判会导致采购到错误商品」钉在 DB 层：
  --    疑似同款绕过人工确认去下单，插不进来。
  CONSTRAINT source_alternative_candidate_confirmation CHECK(
    (match_type = 'SKU_LINK' AND requires_manual_confirmation = false)
    OR (match_type <> 'SKU_LINK' AND requires_manual_confirmation = true)
  )
);

COMMENT ON TABLE source_alternative_candidate IS
  '6.1.2.2 候选货源快照：置信度只衡量「是不是同款」，排序分另算；弱匹配必须人工确认';

-- ===========================================================================
-- 四、人工判定事件（**只追加**）
-- ===========================================================================
CREATE TABLE source_alternative_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL
    REFERENCES source_alternative_run(id) ON DELETE CASCADE,
  candidate_id uuid NOT NULL
    REFERENCES source_alternative_candidate(id) ON DELETE CASCADE,

  -- CONFIRMED 人工确认是同款 / REJECTED 否决 / REQUESTED 已据此生成采购申请。
  decision_type varchar(16) NOT NULL
    CHECK(decision_type IN ('CONFIRMED','REJECTED','REQUESTED')),
  -- **否决必须写理由**：说不出理由的否决等于没否决，三个月后没人知道为什么弃掉这条。
  reason text NOT NULL DEFAULT '',
  -- REQUESTED 时回填下游采购申请（6.2.2.1，同库同租户只读引用，不建跨模块 FK）。
  purchase_request_id uuid,
  purchase_request_no varchar(64) NOT NULL DEFAULT '',

  decided_by uuid NOT NULL,
  decided_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个候选，同一种判定只能来一次（重复确认 ⇒ 冲突，不是静默再写一条）。
  UNIQUE(tenant_id, candidate_id, decision_type),
  CONSTRAINT source_alternative_decision_reject_reason CHECK(
    decision_type <> 'REJECTED' OR length(btrim(reason)) > 0
  ),
  CONSTRAINT source_alternative_decision_request_link CHECK(
    (decision_type = 'REQUESTED'
        AND purchase_request_id IS NOT NULL
        AND length(btrim(purchase_request_no)) > 0)
    OR (decision_type <> 'REQUESTED'
        AND purchase_request_id IS NULL
        AND purchase_request_no = '')
  )
);

COMMENT ON TABLE source_alternative_decision IS
  '6.1.2.2 候选判定事件：只追加；REQUESTED 记着下游采购申请号';

-- ===========================================================================
-- 五、只追加：候选快照与判定事件都不可改写
-- ===========================================================================
-- 「这个候选当时凭什么被推荐」「谁确认了它」是审计事实。
-- 抹掉它等于「历史上没人确认过」，而那正是误判采购的证据。
CREATE FUNCTION guard_source_alternative_candidate_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '同款货源推荐候选不可改写（表 source_alternative_candidate）：'
    '证据是推荐那一刻的快照，要改请重新推荐一次。run_id=%',OLD.run_id
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER source_alternative_candidate_no_update
  BEFORE UPDATE ON source_alternative_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_source_alternative_candidate_append_only();
CREATE TRIGGER source_alternative_candidate_no_delete
  BEFORE DELETE ON source_alternative_candidate
  FOR EACH ROW EXECUTE FUNCTION guard_source_alternative_candidate_append_only();

CREATE FUNCTION guard_source_alternative_decision_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '同款货源推荐判定不可改写（表 source_alternative_decision）：'
    '判定留痕只能追加。candidate_id=%',OLD.candidate_id
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER source_alternative_decision_no_update
  BEFORE UPDATE ON source_alternative_decision
  FOR EACH ROW EXECUTE FUNCTION guard_source_alternative_decision_append_only();
CREATE TRIGGER source_alternative_decision_no_delete
  BEFORE DELETE ON source_alternative_decision
  FOR EACH ROW EXECUTE FUNCTION guard_source_alternative_decision_append_only();

-- ===========================================================================
-- 六、索引
-- ===========================================================================
-- 推荐批次：按创建时刻倒序读（最近推荐在最前）。
CREATE INDEX source_alternative_run_list
  ON source_alternative_run(tenant_id, created_at DESC, id);
-- 推荐批次：按 SKU 反查（同一个缺货 SKU 的历次推荐）。
CREATE INDEX source_alternative_run_sku
  ON source_alternative_run(tenant_id, sku_id, created_at DESC);
-- 候选：按批次读（详情页的主查询）。
CREATE INDEX source_alternative_candidate_run
  ON source_alternative_candidate(tenant_id, run_id, sort_rank, id);
-- 候选：按货源反查（这个货源被推荐过几次）。
CREATE INDEX source_alternative_candidate_item
  ON source_alternative_candidate(tenant_id, item_id, created_at DESC);
-- 判定：按批次读（详情页的判定留痕）。
CREATE INDEX source_alternative_decision_run
  ON source_alternative_decision(tenant_id, run_id, decided_at DESC, id);
-- 判定：按候选读（这个候选被确认/否决过没有）。
CREATE INDEX source_alternative_decision_candidate
  ON source_alternative_decision(tenant_id, candidate_id, decided_at DESC);

-- ===========================================================================
-- 七、多租户：RLS + 授权（与 trade V1 / V233 / V239 / V246 同一套写法）
-- ===========================================================================
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'source_alternative_policy','source_alternative_run',
   'source_alternative_candidate','source_alternative_decision'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  source_alternative_policy,source_alternative_run,
  source_alternative_candidate,source_alternative_decision TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  source_alternative_policy,source_alternative_run,
  source_alternative_candidate,source_alternative_decision TO starcon_app;

-- ===========================================================================
-- 八、本迁移的回归面（跨车道纪律）
-- ===========================================================================
--   * **不 ALTER 任何既有表**：V233 的货源池六张表、V266 的评级四张表、
--     V246 的采购申请四张表、V254 的采购单三张表都不碰 ⇒ 跨车道 DDL 回归面 = 0。
--   * 对 catalog 的 sku / party、对 warehouse 的仓与法人，**只读逻辑 ID + 展示快照**，
--     跨库 FK 本来就禁止。
--   * 新增函数全部带 source_alternative_ 前缀，与既有函数名不冲突。
--   * 版本号 V272 由调度者预分配，**不自己找最大版本号**（并行车道会同时提交迁移）。
