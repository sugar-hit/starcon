-- 6.1.1.3 供应商评级。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：按周期（默认月度）自动统计供应商表现：交期达成率、质量合格率、响应时长、
--             价格竞争力、售后配合度，综合打分并支持人工调整权重。
--   **输入**：收货事件时间与质检结果；采购单沟通记录；权重配置。
--   **输出**：评级结果与排名；评级变动历史；影响采购建议的默认供应商优先级。
--   **关系**：上游 = 6.4.2 质检、6.3.2.1 跟单；下游 = 3.6.2 报价优先级、6.2.1.2 补货建议
--             （优先选高分供应商）。**评级必须由客观数据算出**，人工加权分需单独标记，
--             否则会变成纯主观打分。
--
-- 归属服务：卷 06 的「后端主责服务」是 `trade + catalog（party）`，列表第一项 = trade
-- （tools/module-progress/lane.mjs 的微服务盲区闸读的就是这张表），所以本模块落在 **trade**。
-- 供应商主数据（party / party_role）在 catalog 的独立库里 ⇒ 本迁移**不建**任何指向它的外键，
-- 也不跨库读表；供应商只用逻辑引用 `supplier_ref_id` + 名称快照寻址（与 trade 全线的
-- purchase_order.supplier_ref_id 同一口径）。
--
-- ===========================================================================
-- 一、本迁移**零 ALTER 零 DROP 既有表** ⇒ 跨车道 DDL 回归面 = 0
-- ===========================================================================
-- 读到的既有表（全部只读，不加列不加索引不加约束）：
--   * purchase_order                (V254)  采购单头：承诺到货日 / 供应商
--   * purchase_order_line           (V254)  采购单行：SKU / 数量 / 单价
--   * purchase_arrival_tracking     (V235)  到货跟踪（6.3.2.2）
--   * purchase_arrival_receipt      (V235)  到货流水（分批到货的事实）
--   * purchase_delivery_change      (V259)  交期变更历史（6.3.2.1）
--   * purchase_followup_record      (V259)  跟单记录（6.3.2.1 沟通凭据）
--   * quality_inspection_order      (V249)  来料质检单（6.4.2.1）
--   * supplier_claim                (V188)  供应商索赔（11.2.3.3）
--   * supplier_claim_negotiation    (V188)  索赔协商轮次
--
-- ===========================================================================
-- 二、五个维度各自的口径（分子 / 分母 / 从哪张表算起）
-- ===========================================================================
-- 五个维度**没有一个是存下来的字段**，全部是算出口径。
--
-- **D1 交期达成率（DELIVERY）** —— 数据源：6.3.2.2 的到货流水 + 6.3.2.1 的交期变更。
--     分子：期内到货且「到货日 <= 到货时刻生效的承诺日」的次数；
--     分母：期内到货次数。
--     「到货时刻生效的承诺日」= 该次到货时刻**之前**最后一次交期变更的 to_promised_date，
--     没有变更则取采购单上的 promised_arrival_date。
--     ⇒ 与 6.3.2.1「跟单与交期」的延迟统计**逐字同源**（同一套承诺日口径）。
--     存一份推导结果就多一个可能与事实不一致的副本（改期后忘了同步就是错的比率）。
--     分母为 0 时返回 **null 而不是 0**：「没交货过」与「交货全部延期」是相反的两件事。
--
-- **D2 质量合格率（QUALITY）** —— 数据源：6.4.2.1 的来料质检单。
--     分子：Σ 合格量（qualified_quantity）；
--     分母：Σ 本次质检覆盖批量（lot_size）。
--     用**数量比**而不是「合格批次数 / 检验批次数」：一批 10000 件里合格 9999 件
--     与一批 3 件里合格 1 件，按批次算是同一个结果（1/2），按数量算才是 99.99% 与 33.33%。
--     只算 status='COMPLETED' 且有结论的单；「还没判」的单不进分母（不是不合格，是没数据）。
--     分母为 0 时返回 null（理由同 D1）。
--
-- **D3 响应时长（RESPONSE）** —— 数据源：6.3.2.1 的跟单记录。
--     口径：**「我方跟单发出 → 登记到供应商答复」的等待窗口**。
--     样本 = 期内跟单记录中「有供应商答复（supplier_reply 非空）」且「同一采购单上
--     存在更早的一条**未答复**跟单」的记录；该样本的响应时长 =
--     答复记录的 occurred_at − 上一条未答复记录的 occurred_at。
--     ⇒ 上游把「我方说了什么」与「供应商答了什么」记在**同一行**上，
--     没有独立的「答复时刻」列，所以可测的等待窗口只能是「上一次没答 → 这一次答了」。
--     没有更早未答复记录的那条（第一次沟通就答了）**不构成样本**（没有可测的等待窗口）。
--     平均响应时长（小时）= Σ 窗口 / 样本数；样本数为 0 ⇒ 该维度不可用（不是 0 分）。
--
-- **D4 价格竞争力（PRICE）** —— 数据源：catalog 的 3.6.2 报价台账（**跨服务只读**）
--     + 本库 6.3.1.1 的采购单行（同期真实成交的 SKU 与数量）。
--     对每个「期内从该供应商采购过的 SKU」：
--       参考数量 Q = 该供应商在该 SKU 上的实际采购量；
--       本供应商报价单价 = 其报价在数量 Q 处命中的阶梯单价（Q 低于起订量时取最低档）；
--       同币种最优价 = 其它供应商在同一币种、同一数量处的最低单价；
--       贡献度 = min(1, 同币种最优价 / 本供应商单价)。
--     得分 = Σ(贡献度 × 采购量) / Σ(采购量)（按采购量加权）。
--     ⚠️ **只比同币种**：本库没有汇率表（汇率是 12 财务的资产），
--       100 USD 与 90 EUR 直接比大小是错的。没有同币种对照的 SKU **不计入样本**。
--     ⚠️ **报价是下游不是上游**（蓝图「下游 = 3.6.2 报价优先级」）：
--       用报价算价格竞争力、再把评级回灌报价优先级，会形成自指回路；
--       所以价格竞争力取的是**报价台账里的市场价比**，
--       而评级回流到报价优先级是另一条边（由 3.6.2 侧消费本模块的优先级出口）。
--
-- **D5 售后配合度（SERVICE）** —— 数据源：11.2.3.3 的供应商索赔与协商轮次。
--     分子：期内提交的索赔里「供应商侧参与过协商」的索赔数；
--     分母：期内提交的索赔数。
--     分子按**存在 SUPPLIER 侧协商记录**判定（不是金额加权 —— 一笔小索赔谈得快
--     不代表这家供应商配合好，笔数才是配合的广度）。
--     分母为 0 ⇒ **该维度不可用**（不是 0 分）：「没发生过索赔」与「索赔了但完全不配合」
--     是相反的两件事，折叠成 0 会让从没出过问题的供应商看起来最差。
--
-- ===========================================================================
-- 三、为什么"人工加权分"必须单独标记
-- ===========================================================================
-- 蓝图加粗句：「**评级必须由客观数据算出**，人工加权分需单独标记，
-- 否则会变成纯主观打分。」
--
-- 折叠成「一个总分」的失败模式很具体：把人工分与客观分加进同一列之后，
-- 三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。
-- ⇒ 本表同时存**三个数**：
--     objective_score  纯客观分（五个维度的加权合成分，**只由数据决定**）；
--     manual_adjustment 人工调整量（−50 ~ +50，**必须带理由**）；
--     final_score      最终分 = objective_score + manual_adjustment（0~100，DB 层 CHECK 钉死）。
--   并且 manually_adjusted 布尔列 + 调整明细表（append-only）把每一次人工调整单独留痕。
--
-- ===========================================================================
-- 四、表定义
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 4.1 supplier_rating_weight_policy —— 权重配置（租户级）
-- ---------------------------------------------------------------------------
-- 蓝图「输入」里明写「权重配置」，且「支持人工调整权重」。
-- 权重是**租户级**的：不同公司对「交期」与「价格」的取舍不同，权重跟着租户走。
--
-- 权重和**必须精确等于 1**（DB 层 CHECK，numeric 精确算术，不是近似）：
--   * 大于 1 ⇒ 综合分被放大，名次与分数脱钩；
--   * 小于 1 ⇒ 综合分被压缩，同样名次与分数脱钩；
--   * 等于 0 ⇒ 综合分恒为 0，评级退化成常量。
-- 「某些维度不参与」的正确表达是**该维度权重为 0**，不是「权重和不为 1」。
CREATE TABLE supplier_rating_weight_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 周期类型：月度（默认）/ 季度。蓝图说「按周期（默认月度）」。
  period_type varchar(12) NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),

  -- 五个维度的权重（0~1，精确和 = 1）。
  delivery_weight numeric(9,6) NOT NULL CHECK(delivery_weight >= 0 AND delivery_weight <= 1),
  quality_weight  numeric(9,6) NOT NULL CHECK(quality_weight  >= 0 AND quality_weight  <= 1),
  response_weight numeric(9,6) NOT NULL CHECK(response_weight >= 0 AND response_weight <= 1),
  price_weight    numeric(9,6) NOT NULL CHECK(price_weight    >= 0 AND price_weight    <= 1),
  service_weight  numeric(9,6) NOT NULL CHECK(service_weight  >= 0 AND service_weight  <= 1),

  -- 响应时长的目标小时数：超过目标就得 0 分。默认 72 小时（3 天）。
  -- 放在配置里而不是写死，是因为「多快算快」是本租户的经营口径。
  response_target_hours int NOT NULL DEFAULT 72 CHECK(response_target_hours > 0),

  -- 等级分档（0~1 的分数线）：A ≥ grade_a_min > B ≥ grade_b_min > C ≥ grade_c_min > D。
  grade_a_min numeric(9,4) NOT NULL DEFAULT 0.85 CHECK(grade_a_min > 0 AND grade_a_min <= 1),
  grade_b_min numeric(9,4) NOT NULL DEFAULT 0.70 CHECK(grade_b_min > 0 AND grade_b_min <= 1),
  grade_c_min numeric(9,4) NOT NULL DEFAULT 0.55 CHECK(grade_c_min > 0 AND grade_c_min <= 1),

  note text NOT NULL DEFAULT '' CHECK(length(btrim(note)) <= 2000),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, period_type),

  -- 权重和精确等于 1。
  CONSTRAINT supplier_rating_weight_policy_sum CHECK(
    delivery_weight + quality_weight + response_weight + price_weight + service_weight = 1
  ),
  -- 分档必须严格递减，否则会出现「既是 A 又是 C」的重叠区间。
  CONSTRAINT supplier_rating_weight_policy_grades CHECK(
    grade_a_min > grade_b_min AND grade_b_min > grade_c_min
  )
);

CREATE INDEX supplier_rating_weight_policy_by_tenant
  ON supplier_rating_weight_policy(tenant_id, period_type);

-- ---------------------------------------------------------------------------
-- 4.2 supplier_rating_result —— 评级结果（一个供应商一个周期一行）
-- ---------------------------------------------------------------------------
-- 唯一键 (tenant_id, period_type, period_start, supplier_ref_id)：
-- 同一周期重算**覆盖**同一行（乐观锁 + version），而不是堆出第二行 ——
-- 排名与「本期评级」都必须只对应一份事实。
CREATE TABLE supplier_rating_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  period_type varchar(12) NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  -- 周期起止（闭区间，含首尾两天）。写死在行上是为了让「这个分是哪个窗口算的」可查。
  period_start date NOT NULL,
  period_end   date NOT NULL,
  CONSTRAINT supplier_rating_result_period_order CHECK(period_start <= period_end),

  -- 供应商（catalog 独立库 ⇒ 逻辑引用 + 名称快照，与 trade 全线同口径）。
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 三个分数（见第三节）----
  -- 纯客观分：只由五个维度的加权合成决定。
  objective_score numeric(9,4) NOT NULL CHECK(objective_score >= 0 AND objective_score <= 100),
  -- 人工调整量（−50 ~ +50），默认为 0。
  manual_adjustment numeric(9,4) NOT NULL DEFAULT 0
    CHECK(manual_adjustment >= -50 AND manual_adjustment <= 50),
  -- 最终分 = 客观分 + 人工调整（DB 层钉死，不靠应用层自觉）。
  final_score numeric(9,4) NOT NULL CHECK(final_score >= 0 AND final_score <= 100),
  -- 等级：A / B / C / D。
  grade varchar(1) NOT NULL CHECK(grade IN ('A','B','C','D')),

  -- 周期内名次（1 = 最高分）。同一分数按 supplier_ref_id 升序稳定排序（业务上有意义的键）。
  rank_in_period int CHECK(rank_in_period IS NULL OR rank_in_period > 0),

  -- 参与合成的权重和（≤ 1）。< 1 说明有维度不可用被排除。
  available_weight numeric(9,6) NOT NULL CHECK(available_weight >= 0 AND available_weight <= 1),
  -- 不可用的维度清单（逗号分隔的维度键，如 'PRICE,SERVICE'）与原因（人话）。
  unavailable_dimensions text NOT NULL DEFAULT '',
  unavailable_reason text NOT NULL DEFAULT '',

  -- ---- 五个维度得分（null = 该维度不可用，不是 0 分）----
  delivery_score numeric(9,4) CHECK(delivery_score IS NULL OR (delivery_score >= 0 AND delivery_score <= 100)),
  quality_score  numeric(9,4) CHECK(quality_score  IS NULL OR (quality_score  >= 0 AND quality_score  <= 100)),
  response_score numeric(9,4) CHECK(response_score IS NULL OR (response_score >= 0 AND response_score <= 100)),
  price_score    numeric(9,4) CHECK(price_score    IS NULL OR (price_score    >= 0 AND price_score    <= 100)),
  service_score  numeric(9,4) CHECK(service_score  IS NULL OR (service_score  >= 0 AND service_score  <= 100)),

  -- ---- 五个维度的原始指标（可核对口径）----
  delivery_on_time_rate numeric(9,6) CHECK(delivery_on_time_rate IS NULL OR (delivery_on_time_rate >= 0 AND delivery_on_time_rate <= 1)),
  delivery_arrived_count int NOT NULL DEFAULT 0 CHECK(delivery_arrived_count >= 0),
  quality_pass_rate numeric(9,6) CHECK(quality_pass_rate IS NULL OR (quality_pass_rate >= 0 AND quality_pass_rate <= 1)),
  quality_inspected_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(quality_inspected_quantity >= 0),
  quality_qualified_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(quality_qualified_quantity >= 0),
  response_avg_hours numeric(12,2) CHECK(response_avg_hours IS NULL OR response_avg_hours >= 0),
  response_sample_count int NOT NULL DEFAULT 0 CHECK(response_sample_count >= 0),
  price_index numeric(9,6) CHECK(price_index IS NULL OR (price_index >= 0 AND price_index <= 1)),
  price_sample_count int NOT NULL DEFAULT 0 CHECK(price_sample_count >= 0),
  price_comparable_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(price_comparable_quantity >= 0),
  service_cooperation_rate numeric(9,6) CHECK(service_cooperation_rate IS NULL OR (service_cooperation_rate >= 0 AND service_cooperation_rate <= 1)),
  service_claim_count int NOT NULL DEFAULT 0 CHECK(service_claim_count >= 0),

  -- ---- 人工调整的单独标记（第三节）----
  manually_adjusted boolean NOT NULL DEFAULT false,
  manual_adjusted_by uuid,
  manual_adjusted_at timestamptz,
  manual_adjustment_reason text NOT NULL DEFAULT '' CHECK(length(btrim(manual_adjustment_reason)) <= 1000),

  computed_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, period_type, period_start, supplier_ref_id),

  -- 最终分必须等于「客观分 + 人工调整」，并**夹逼在 0~100**。
  -- 夹逼写进 CHECK 而不是只在应用层做：人工把一家 100 分的供应商再抬 5 分时，
  -- 最终分仍然是 100（分数不是无穷的），而人工调整量**仍然是请求的那 5 分**
  -- （它是「谁提的要求」的凭据，不是「实际增量」；实际增量 = 最终分 − 客观分，
  -- 页面上两个数并排显示，看得出来）。
  -- 写成 greatest/least 之后，重算（客观分变了、人工调整保留）与人工调整
  -- 两条路径共用同一条等式，DB 层一次就钉住了。
  CONSTRAINT supplier_rating_result_score_math CHECK(
    final_score = greatest(0, least(100, objective_score + manual_adjustment))
  ),
  -- 人工调整被标记过 ⇒ 必须有理由、有操作人、有时刻。
  CONSTRAINT supplier_rating_result_manual_marked CHECK(
    NOT manually_adjusted
    OR (btrim(manual_adjustment_reason) <> ''
        AND manual_adjusted_by IS NOT NULL
        AND manual_adjusted_at IS NOT NULL)
  )
);

-- 排名查询：本期按最终分倒序（同分按 supplier_ref_id 升序 —— 业务上有意义的稳定键）。
CREATE INDEX supplier_rating_result_by_period_rank
  ON supplier_rating_result(tenant_id, period_type, period_start, final_score DESC, supplier_ref_id);
-- 单个供应商的评级历史（变动历史与详情页都按它取）。
CREATE INDEX supplier_rating_result_by_supplier
  ON supplier_rating_result(tenant_id, supplier_ref_id, period_start DESC);

-- ---------------------------------------------------------------------------
-- 4.3 supplier_rating_adjustment —— 人工加权分明细（追加式，不可改写）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「人工加权分需单独标记」的**物理落点**：
-- 人工调整不是结果行上的一个数字了事，每一次调整都有独立的一行留痕
-- （谁、何时、调了多少、为什么）。结果行上的 manual_adjustment 只是「当前值」，
-- 明细表才是「历史」。
CREATE TABLE supplier_rating_adjustment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  result_id uuid NOT NULL,
  supplier_ref_id uuid NOT NULL,
  period_type varchar(12) NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 调整前后的分数（objective 不变 —— **人工调整不改客观分**，这是本设计的核心）。
  objective_score numeric(9,4) NOT NULL CHECK(objective_score >= 0 AND objective_score <= 100),
  previous_score numeric(9,4) NOT NULL CHECK(previous_score >= 0 AND previous_score <= 100),
  adjusted_score numeric(9,4) NOT NULL CHECK(adjusted_score >= 0 AND adjusted_score <= 100),
  adjustment numeric(9,4) NOT NULL CHECK(adjustment >= -50 AND adjustment <= 50),

  -- 理由必填：没有理由的人工调整等于没留痕，三个月后无法解释这个名次。
  reason text NOT NULL CHECK(length(btrim(reason)) > 0 AND length(btrim(reason)) <= 1000),

  adjusted_by uuid NOT NULL,
  adjusted_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, result_id) REFERENCES supplier_rating_result(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX supplier_rating_adjustment_by_result
  ON supplier_rating_adjustment(tenant_id, result_id, adjusted_at DESC, id);
CREATE INDEX supplier_rating_adjustment_by_supplier
  ON supplier_rating_adjustment(tenant_id, supplier_ref_id, adjusted_at DESC, id);

-- ---------------------------------------------------------------------------
-- 4.4 supplier_rating_change —— 评级变动历史（追加式，不可改写）
-- ---------------------------------------------------------------------------
-- 蓝图「输出」原文：评级变动历史。
-- 重算一次周期可能把供应商从 B 抬到 A，也可能只是分数微调；
-- 两种都要留一行，否则「评级为什么变了」没人答得上来。
CREATE TABLE supplier_rating_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',
  period_type varchar(12) NOT NULL CHECK(period_type IN ('MONTHLY','QUARTERLY')),
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- 变动前：null = 首次评级（此前没有这一家的评级）。
  previous_grade varchar(1) CHECK(previous_grade IS NULL OR previous_grade IN ('A','B','C','D')),
  previous_score numeric(9,4) CHECK(previous_score IS NULL OR (previous_score >= 0 AND previous_score <= 100)),
  -- 变动后（恒有值）。
  current_grade varchar(1) NOT NULL CHECK(current_grade IN ('A','B','C','D')),
  current_score numeric(9,4) NOT NULL CHECK(current_score >= 0 AND current_score <= 100),
  score_delta numeric(9,4),
  grade_changed boolean NOT NULL,

  -- 变动来源：跨周期滚动（PERIOD_ROLL）/ 同周期重算（RECOMPUTE）/ 人工调整（MANUAL_ADJUSTMENT）。
  change_type varchar(20) NOT NULL
    CHECK(change_type IN ('PERIOD_ROLL','RECOMPUTE','MANUAL_ADJUSTMENT')),
  reason text NOT NULL DEFAULT '' CHECK(length(btrim(reason)) <= 1000),

  operator_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX supplier_rating_change_by_supplier
  ON supplier_rating_change(tenant_id, supplier_ref_id, occurred_at DESC, id);
CREATE INDEX supplier_rating_change_by_period
  ON supplier_rating_change(tenant_id, period_type, period_start, occurred_at DESC, id);

-- ===========================================================================
-- 五、DB 层守卫
-- ===========================================================================
-- 5.1 留痕表不可改写：人工调整明细与评级变动历史都是「当时发生了什么」的举证凭据。
CREATE FUNCTION guard_supplier_rating_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：供应商评级的人工调整与变动历史是「当时发生了什么」的举证凭据',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER supplier_rating_adjustment_append_only
  BEFORE UPDATE OR DELETE ON supplier_rating_adjustment
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_rating_append_only();

CREATE TRIGGER supplier_rating_change_append_only
  BEFORE UPDATE OR DELETE ON supplier_rating_change
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_rating_append_only();

-- 5.2 人工调整必须真的调整了：adjustment = 0 不许进明细表
-- （一次「调整了 0 分」是噪声，会让人以为有人评过这家供应商）。
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'supplier_rating_adjustment_nonzero'
      AND conrelid = 'supplier_rating_adjustment'::regclass
  ) THEN
    ALTER TABLE supplier_rating_adjustment
      ADD CONSTRAINT supplier_rating_adjustment_nonzero CHECK(adjustment <> 0);
  END IF;
END $$;

-- ===========================================================================
-- 六、索引（按真实查询形状建，不多建）
-- ===========================================================================
-- （索引已随各表定义就地建好：权重按周期类型、结果按周期排名与按供应商、
--   调整明细与变动历史按结果 / 供应商 / 周期。）

-- ===========================================================================
-- 七、多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 / V246 / V254 同一套写法）
-- ===========================================================================
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'supplier_rating_weight_policy','supplier_rating_result',
    'supplier_rating_adjustment','supplier_rating_change'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_rating_weight_policy, supplier_rating_result,
  supplier_rating_adjustment, supplier_rating_change
  TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_rating_weight_policy, supplier_rating_result,
  supplier_rating_adjustment, supplier_rating_change
  TO starcon_app;

-- 留痕两张表只追加：运行角色拿不到 UPDATE / DELETE。
-- （REVOKE 放在 GRANT 之后，否则会被上面的 GRANT 覆盖回去。）
REVOKE UPDATE,DELETE ON supplier_rating_adjustment FROM trade_app, starcon_app;
REVOKE UPDATE,DELETE ON supplier_rating_change FROM trade_app, starcon_app;

COMMENT ON TABLE supplier_rating_weight_policy IS
  '6.1.1.3 供应商评级：租户级权重配置（五维权重和精确为 1 + 响应目标小时 + 等级分档）';
COMMENT ON TABLE supplier_rating_result IS
  '6.1.1.3 供应商评级：每供应商每周期一行的评级结果（客观分 / 人工调整 / 最终分分开存）';
COMMENT ON TABLE supplier_rating_adjustment IS
  '6.1.1.3 供应商评级：人工加权分明细（追加式，理由必填）';
COMMENT ON TABLE supplier_rating_change IS
  '6.1.1.3 供应商评级：评级变动历史（追加式，跨周期滚动 / 重算 / 人工调整三类）';
