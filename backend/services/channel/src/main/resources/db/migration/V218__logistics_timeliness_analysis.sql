-- ===========================================================================
-- 10.6.2.1 时效分析（channel 服务）
-- ===========================================================================
-- 蓝图原文（10-logistics-and-freight.md）：
--   职责：分析时效表现：各渠道各国家的妥投率、超时率、平均时效波动、旺季变化，
--         用于渠道评分与时效承诺设置。
--   输入：轨迹数据；承运商承诺时效。
--   输出：时效报表 + 渠道评分 + 异常集中分析。
--   关系：上游 = 10.3.1.1；下游 = 10.1.2.2 比价、9.5.1 订单时效。
--         **时效数据要按渠道和国家交叉分析**，总体平均会掩盖某些渠道在某些国家的
--         系统性延误。
--
-- ---------------------------------------------------------------------------
-- 一、事实源唯一：10.3.1.1 的 logistics_tracking_subject
-- ---------------------------------------------------------------------------
-- 本迁移**不新建任何事实表**。时效分析的每一个数字都从 10.3.1.1 的轨迹主体
-- 表算出来：
--   * 实际时效 = latest_occurred_at（签收时刻） − shipped_at（发货时刻）；
--   * 承诺时效 = promised_eta_at（**发货当时**的档期快照，V212 里就是这么存的）；
--   * 超时判定 = 已签收 且 latest_occurred_at > promised_eta_at。
-- 时区语义全部落在 timestamptz 上：timestamptz − timestamptz 是**绝对时刻差**，
-- 与服务器 / 会话时区无关 —— 跨时区渠道的时效不会因为数据库跑在哪个时区而变。
--
-- 承诺是**快照**不是实时 join（V212 第二节已定调）：事后改档期不能让一笔已经
-- 延误的货变成准点，所以本表只读快照列，不去 join logistics_transit_rule 重算。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 加粗句的物理落点：**按渠道 × 国家交叉存，不存「这条渠道总体表现」**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些
-- 国家的系统性延误。」
--
-- 这句话在 DDL 上的落点就是本表的主键形状：
--   UNIQUE (tenant_id, channel_id, country_code, window_start, window_end)
-- **物理上就存不下「这条渠道总体成功率」那一行** —— 想存也得把 country_code 填成
-- 某个具体国家，于是它就不再是总体值了。
--
-- 同一口径在 10.1.2.2 比价（V143 的 channel_rating_score）里已经先行落地过一遍，
-- 那里也是按渠道 × 国家交叉存并直接引用了本模块的加粗句。两处口径一致，
-- 比价将来换数据来源时不需要改表结构。
--
-- ---------------------------------------------------------------------------
-- 三、原始计数是事实，比率是生成列 —— 服务层写不进一个与计数对不上的比率
-- ---------------------------------------------------------------------------
-- 三个比率（妥投率 / 达成率 / 超时率）是 GENERATED ALWAYS AS ... STORED：
--   delivery_rate = delivered / shipped     （发出后最终签投的比例）
--   on_time_rate  = on_time  / comparable   （**有承诺可比**的签收票里准点的比例）
--   overdue_rate  = overdue / comparable   （有承诺可比的签收票里超时的比例）
-- 分母为 0 时给 NULL 而不是 0：0 会被读成「一单没发」。
-- 生成列意味着**服务层写不进去**，绕过服务层直接写 SQL 也编不出一个与计数对不上的
-- 比率 —— 比率与计数打架是「假报表」的头号来源。
--
-- ⚠️ **达成率与超时率的分母是 comparable（on_time + overdue），不是 delivered。**
-- 「已签收但没有承诺时效可比」的那批票单列 no_promise_count：把它们算成准点或超时
-- 都是编造 —— 那批票既没准点也没超时，是**没资格比**。而把它们塞进分母会让「没登记
-- 承诺」显示成「100% 不准点」，那会把运营的注意力引到根本不是问题的地方
-- （「不可比不是 0 分」这条口径在 10.1.2.2 比价里已经用过一次，这里同一口径）。
--
-- 三条 CHECK 把计数之间的算术**钉死**：
--   shipped   = delivered + pending + returned（发出去的票：签收了 / 还在路上 / 退回了）
--   delivered = comparable + no_promise      （签收的票：有承诺可比 / 没承诺可比）
--   comparable = on_time + overdue           （可比的那批：准点 / 超时）
-- 「已签收但没有承诺时效」必须单列一档：把它算成准点或超时都是编造。
--
-- ---------------------------------------------------------------------------
-- 四、评分是「判断」不是「事实」：服务层算，DB 钉住取值域与等级一致性
-- ---------------------------------------------------------------------------
-- timeliness_score（0~100 的时效评分）不是生成列 —— 它的公式里有权重与容忍度，
-- 那是**策略**，不是事实；策略写死在 DDL 里等于把经营口径焊死在库里，改一次口径
-- 就要改一次表。公式写在服务层（LogisticsTimelinessService），本表只钉：
--   * 取值域 0~100；
--   * 等级与分数的对应关系（CHECK）—— EXCELLENT ≥ 90 / GOOD 75~90 /
--     FAIR 60~75 / POOR < 60 / INSUFFICIENT ⇒ 分数为 NULL。
-- 「样本不足 ⇒ 分数为 NULL 而不是 0 分」也钉在这条 CHECK 上：0 分是「比过了，
-- 确实最差」，NULL 是「压根没资格比」，两者混起来会让人把没样本的渠道当成
-- 最差的渠道。
--
-- ---------------------------------------------------------------------------
-- 五、跨车道回归自查
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 策略，零 ALTER 任何既有表**：
--   * 不碰 V129 / V134 / V136 / V139 / V141 / V212 / V214 的任何一张表；
--   * 唯一引用的既有表是 logistics_channel_config（V136，同库复合外键，
--     带 tenant_id ⇒ 跨租户挂载不可能）；
--   * **不写 channel_rating_score（V143）** —— 那张表的写入所有者是 10.1.2.2，
--     本模块只产出自己的时效评分台账，比价换来源是比价那条车道的事。
--   * 跨车道 DDL 回归面 = 0。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. logistics_timeliness_score · 时效评分台账（渠道 × 国家 × 统计窗口）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_timeliness_score (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 渠道（10.1.1.2，同库复合外键，带 tenant_id）===
  -- ON DELETE CASCADE：渠道配置被撤下，它那份时效评分就没有意义了 ——
  -- 留着会让「这个渠道还准时」继续出现在报表里，而渠道已经不存在了。
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- === 国家（两位大写）===
  -- 🔴 不是「这条渠道总体表现」的占位符：country_code 必填且必须是真实国家码，
  --    物理上存不下总体行。
  country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),

  -- === 统计窗口（左闭右开：[window_start, window_end)）===
  window_start timestamptz NOT NULL,
  window_end timestamptz NOT NULL,
  window_days int NOT NULL CHECK (window_days > 0),
  CHECK (window_end > window_start),

  -- === 原始计数（人可核对的事实；比率由这些数派生，不接受任何直接写入）===
  shipped_count int NOT NULL CHECK (shipped_count >= 0),
  delivered_count int NOT NULL CHECK (delivered_count >= 0),
  -- 有承诺时效可比的已签收票数（= on_time + overdue）：达成率 / 超时率的分母
  comparable_count int NOT NULL CHECK (comparable_count >= 0),
  on_time_count int NOT NULL CHECK (on_time_count >= 0),
  overdue_count int NOT NULL CHECK (overdue_count >= 0),
  -- 已签收但**没有承诺时效可比**（不算准点，也不算超时 —— 是不可比）
  no_promise_count int NOT NULL CHECK (no_promise_count >= 0),
  pending_count int NOT NULL CHECK (pending_count >= 0),
  -- 已退回（妥投失败：退回寄件人，不算妥投）
  returned_count int NOT NULL CHECK (returned_count >= 0),

  -- 算术钉死：写进来的数字自相矛盾 ⇒ 直接被拒。
  CHECK (shipped_count = delivered_count + pending_count + returned_count),
  CHECK (delivered_count = comparable_count + no_promise_count),
  CHECK (comparable_count = on_time_count + overdue_count),

  -- === 三个比率（生成列：服务层写不进去，绕过服务层也编不出对不上的比率）===
  delivery_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN shipped_count > 0
         THEN round(delivered_count::numeric / shipped_count::numeric, 4)
         ELSE NULL END
  ) STORED,
  on_time_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN comparable_count > 0
         THEN round(on_time_count::numeric / comparable_count::numeric, 4)
         ELSE NULL END
  ) STORED,
  overdue_rate numeric(9,4) GENERATED ALWAYS AS (
    CASE WHEN comparable_count > 0
         THEN round(overdue_count::numeric / comparable_count::numeric, 4)
         ELSE NULL END
  ) STORED,

  -- === 时效统计（小时；没有可比样本时为 NULL，不凑数）===
  -- 平均实际时效：latest_occurred_at − shipped_at
  avg_transit_hours numeric(19,4),
  -- 平均时效偏差：latest_occurred_at − promised_eta_at（**带符号**：正数=晚于承诺，
  -- 负数=早于承诺；平均下来为负说明这家渠道普遍提前到，那是好事）
  avg_deviation_hours numeric(19,4),
  -- 时效波动：实际时效的样本标准差（同一个格子里的离散程度）
  stddev_transit_hours numeric(19,4),
  -- P90 时效：长尾那一截比平均值更能暴露「偶尔极慢」
  p90_transit_hours numeric(19,4),
  -- 最大超时小时数：最差的一票超了多少
  max_overdue_hours numeric(19,4),

  -- === 评分（服务层按公式算，DB 钉取值域与等级一致性）===
  -- NULL = 不可比（无样本或样本不足），不是 0 分。
  timeliness_score numeric(9,4),
  score_grade varchar(12) NOT NULL,
  -- 最小样本量门槛：样本不足这一格时分数为 NULL，门槛本身**随快照落库**
  -- （事后改门槛不能让已经落库的那一格看起来是「够样本」算出来的）。
  min_sample_threshold int NOT NULL CHECK (min_sample_threshold >= 1),
  sample_sufficient boolean NOT NULL,

  CHECK (timeliness_score IS NULL OR (timeliness_score >= 0 AND timeliness_score <= 100)),
  -- 等级与分数必须自洽：INSUFFICIENT ⇒ 分数为 NULL；否则等级由分数区间唯一决定。
  CHECK (
    (timeliness_score IS NULL AND score_grade = 'INSUFFICIENT')
    OR
    (timeliness_score IS NOT NULL AND
      ( (score_grade = 'EXCELLENT' AND timeliness_score >= 90)
     OR (score_grade = 'GOOD'      AND timeliness_score >= 75 AND timeliness_score < 90)
     OR (score_grade = 'FAIR'      AND timeliness_score >= 60 AND timeliness_score < 75)
     OR (score_grade = 'POOR'      AND timeliness_score < 60) ))
  ),
  -- 样本充分与否必须与分数的有无一致
  CHECK ((sample_sufficient AND timeliness_score IS NOT NULL)
      OR (NOT sample_sufficient AND timeliness_score IS NULL)),

  computed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 加粗句在 DDL 上的落点：主键形状里就没有「渠道总体」这一行。
  UNIQUE (tenant_id, channel_id, country_code, window_start, window_end)
);

-- 报表主路径：按渠道 × 国家读某一档窗口的评分
CREATE INDEX logistics_timeliness_score_by_dimension
  ON logistics_timeliness_score(tenant_id, window_start, window_end, channel_id, country_code);

-- 按国家读（界面上的国家筛选）
CREATE INDEX logistics_timeliness_score_by_country
  ON logistics_timeliness_score(tenant_id, country_code, computed_at DESC);

-- 按渠道读
CREATE INDEX logistics_timeliness_score_by_channel
  ON logistics_timeliness_score(tenant_id, channel_id, computed_at DESC);

-- 台账默认排序：最近算的那一档在前
CREATE INDEX logistics_timeliness_score_by_computed
  ON logistics_timeliness_score(tenant_id, computed_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 多租户隔离：业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V141 / V212 / V214 同一套写法。
ALTER TABLE logistics_timeliness_score ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics_timeliness_score FORCE ROW LEVEL SECURITY;

CREATE POLICY isolation ON logistics_timeliness_score
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- ---------------------------------------------------------------------------
-- 3. 账号与授权（沿用 V1/V93/V129/V134/V136/V141/V212 的条件建角色，保证可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON logistics_timeliness_score TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V136 / V212 同口径）。
REVOKE ALL ON logistics_timeliness_score FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 4. 权限码复用：identity 零改动
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：时效分析属于「平台/承运商操作」这一族，与 10.1.1.2
-- 渠道配置、10.1.1.3 时效与限制、10.3.1.1 轨迹查询、10.3.2.1 交运批次同族，
-- 因此 identity 服务零改动，跨车道回归面 = 0。
