-- ===========================================================================
-- 10.1.1.3 时效与限制（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.1.1.3 时效与限制
--   - 职责：维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
--           电池/液体/危险品标记要求），并在选品与刊登阶段提示。
--   - 输入：时效与限制规则；SKU 属性（3.7.2 安全属性）。
--   - 输出：时效估算；限制命中提示。
--   - 关系：上游 = 10.1.1.2、3.7.2；下游 = 5.3.1.1 测算（时效影响转化）、
--           4.2.3.2 合规预检。
--
-- 归属 channel：蓝图卷 10 卷首「后端归属：channel 拥有物流商连接与轨迹」，
-- 10.1 物流渠道主责服务列第一位也是 channel；同 L3 的 10.1.1.1 / 10.1.1.2 已落在
-- channel（V134 / V136），时效与限制挂在渠道配置之下，同库同服务引用是自然的一跳。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移约束到的既有模块 = 【10.1.1.2 渠道配置（V136）四个表】
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数，零 ALTER / 零 DROP 任何既有表**：
--   * 三张新表都以**同库复合外键**（带 tenant_id）引用 V136 的
--     `logistics_channel_config` —— 渠道配置是本模块的直接上游，已交付。
--   * 🔴 「标记要求」与「禁限品类 / 电池 / 液体 / 磁力」的一致性由**触发器**在
--     跨表读取 V136 的 `logistics_channel_restriction` 来保证，**仍然只读**，
--     一个字节都不改它 —— 渠道限制是 10.1.1.2 的主权。
--   * 不碰 channel V1/V2/V3/V4/V93/V129/V134 的任何一张表
--   * **不碰 catalog 的任何一张表**：上游 3.7.2 SKU 安全属性是**入参**（渠道
--     服务的调用方 / 前端把带电带液带磁带危险品这几个布尔与品类传进来），
--     判定在 channel 服务内完成，**一条跨服务 SQL / 跨库 FK / 共享视图都没有**。
--   * 复用 identity 已注册的 channel.operation.* 权限码，**identity 零改动**
--
-- ---------------------------------------------------------------------------
-- 一、职责句逐项落点（「不漏项」自检）
-- ---------------------------------------------------------------------------
--   | 蓝图职责句的词        | 落点                                                   |
--   |----------------------|--------------------------------------------------------|
--   | 渠道时效              | `logistics_transit_rule`（分国家 × 分重量段 × 服务等级）|
--   | 分国家                | `country_code`（两位大写，写入时按渠道适用国家校验）    |
--   | 分重量段              | `weight_min_kg` / `weight_max_kg`（**段不得重叠**）    |
--   | 尺寸上限              | `logistics_size_limit.max_length/width/height/girth_cm` |
--   | 重量上限              | `logistics_size_limit.max_weight_kg`                    |
--   | 禁限品类              | **沿用 V136 的 `logistics_channel_restriction`**（不重复建）|
--   | 电池/液体/危险品标记要求| `logistics_marking_requirement`（电池 / 液体 / 危险品 / 磁力）|
--   | 选品与刊登阶段提示    | POST `precheck`（命中提示）—— 服务端判定，不是前端比对  |
--   | 时效估算              | POST `estimate`（分国家 + 重量 → 命中重量段 + 预计到达）  |
--
-- ---------------------------------------------------------------------------
-- 二、为什么「禁限品类」不在本迁移里重建一张表
-- ---------------------------------------------------------------------------
-- 蓝图把「禁限品类、电池/液体/危险品标记要求」并列写在 10.1.1.3 的职责里，但
-- 10.1.1.2（V136）已经把 `logistics_channel_restriction` 做成了渠道限制的落点，
-- 并且**服务端判定端点**（POST eligibility）已交付。两处各建一张禁限品类表，
-- 结果是「同一个东西存了两份」，改一份不改另一份 —— 而这类分歧的表现是
-- 「下单时被承运商拒收并产生额外费用」，正是蓝图加粗句要避免的那笔钱。
--
-- 所以这里的取舍是：**禁限品类 / 电池 / 液体 / 磁力 读上游，判断在本模块汇总**；
-- 本迁移**新增**的是 10.1.1.2 确实没有的那两类：
--   1. **危险品（DANGEROUS_GOODS）标记要求** —— V136 的四种 restriction_kind
--      里没有危险品，而危险品恰恰是「刊登阶段提示」最需要拦的一类；
--   2. **电池 / 液体 / 磁力的「标记要求」这一层**（LABEL_REQUIRED：要贴什么标识）
--      —— V136 只能表达「禁 / 需人工 / 放行」，表达不了「可以走，但必须贴
--      UN38.3 锂电池标识」，而 10.2.2.2 面单模板要靠这一层绑定打印规格。
--
-- ---------------------------------------------------------------------------
-- 三、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 本模块没有金额列（运费与保价归 10.1.1.2 / 10.1.2.1），但**重量与尺寸是数量**：
-- 一律 **NUMERIC(19,6)**，BigDecimal 绑定，**没有一列是 float/double**。
-- 「时效天数」是整数（int），「截单时间」是 `time`（当地墙钟，无时区偏移 ——
-- 承运商的截止时间是它自己机房的钟，换算成 UTC 只会引入一个假精度）。
--
-- ---------------------------------------------------------------------------
-- 四、重量段必须是**半开区间** [min, max)，而不是「每公斤」的点值
-- ---------------------------------------------------------------------------
-- 承运商的时效承诺是分段的：「0–0.5kg 走 8 天，0.5–30kg 走 12 天，>30kg 走 15 天」。
-- 所以一段的语义是**半开区间 [min, max)** —— 下限含、上限不含，**最后一段的上限为
-- NULL（无上限）**。这是行业档期表的通行写法，也是唯一**无歧义**的写法。
--
-- 为什么不用闭区间 [min, max]（下限含、上限也含）：那样 [0.5,30] 与 [30,45] 在
-- 30.0 这个点上**同时命中**，估算结果取决于数据库返回顺序；而为了避开这个歧义
-- 又得留缝（[0,0.4]、[0.5,30]、[30.1,45]），缝里的 0.45kg 就会得到一句
-- 「这条线路此刻没有承诺」—— 一个完全由取整造成的假缺口。
--
-- 🔴 **同一个（渠道、国家、服务等级）下重量段不得重叠**，由 trigger b 双向拒绝。
-- 只做「唯一索引」挡不住重叠（两段 [0,1) 与 [0.5,1.5) 的四元组本来就不重复），
-- 而重叠的后果是：同一个包裹命中两条时效承诺，估算结果取决于查询顺序 ——
-- 这种 bug 在界面上完全看不出来，只会表现为「有人报时效不准」。
--
-- ---------------------------------------------------------------------------
-- 五、触发器：把「可判定的依据」钉在写入路径上
-- ---------------------------------------------------------------------------
-- (a) readiness 不接受调用方指定 —— 照抄 10.1.1.2 的 guard_logistics_channel_readiness：
--     服务层算一遍，DB 这里再算一遍，绕过服务层直接写 SQL 把它改成 READY 同样被拒。
--     readiness 取值（DB 层，优先级从高到低，唯一可判）：
--       NO_TRANSIT  时效天数没配（或 max < 1）—— 承诺不出时效
--       NO_BAND     重量段为空（min >= max）—— 这个段永远命中不了
--       DISABLED    规则已停用
--       READY       三项齐备
--     刻意**不把「生效区间」算进 readiness**：区间是否生效是**读取时刻**的属性
--     （与 10.1.1.2 的旺季附加费同一口径），做成 readiness 会让一行数据的可用性
--     随墙钟漂移，而 readiness 是要被人筛选和统计的。
--
-- (b) 🔴 重量段不得重叠（同渠道 + 同国家 + 同服务等级 + 时间窗有交集 + 重量区间有交集）。
--     两个区间「有交集」的判据是 `a.min <= b.max and b.min <= a.max`。
--
-- (c) 🔴 时效规则的国家必须是**这条渠道适用**的国家。条件触发：只有当该渠道
--     真的登记过 COUNTRY 适用维度时才校验 —— 有些渠道按店铺适用范围走而没登记
--     国家维度，那种情况下拿「没登记」当「不适用」会把正常配置全拒掉。
--
-- (d) 🔴 **国家级上限不得比渠道级上限更宽**。上限的作用是「超过就拒收」，一份
--     比渠道级更宽松的国家级上限会让人以为那个国家更宽松，从而放行超限件 ——
--     判定端点取的是最严的一条，所以更宽的那条必须**写不进来**，而不是写了不算。
--
-- (e) 🔴🔴 标记要求不得与上游 10.1.1.2 的渠道限制打架：上游已判 BLOCK 的 flag_kind，
--     本表只允许 BLOCK。写成 LABEL_REQUIRED 会得到「一边说承运商拒收带电、
--     一边说贴个标就能走」这种自相矛盾的配置，它的危害是**让运营以为这条路能走**。
--
-- ---------------------------------------------------------------------------
-- 六、下游未交付 ⇒ capabilities 显式写缺口，**不提前造**
-- ---------------------------------------------------------------------------
-- 下游 = 5.3.1.1 测算（时效影响转化）、4.2.3.2 合规预检。
-- 本迁移只交付**时效与限制规则与其判定口径**；10.6.2.1 时效分析、10.1.2.1 运费试算、
-- 4.2.3.2 合规预检、3.7.2 安全属性主数据均未在本服务内交付，服务层 capabilities
-- 逐段写 available=false + ownerModule + 影响，**不用空列表或 0 冒充「没有数据」**。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. logistics_transit_rule · 渠道时效（分国家 × 分重量段 × 服务等级）
-- ---------------------------------------------------------------------------
-- 一行 = 「这条渠道发到这个国家、货重落在这个重量段、按这个服务等级」的时效承诺。
-- 承运商档期与重量段是同一件事的两面（重件走陆运更慢且有上限），所以合成一行，
-- 而不是「渠道 + 国家」一行再挂一个重量段子表 —— 后者会让「这个重量段有没有
-- 承诺」变成一次额外的存在性查询，而它恰恰是判定端点每次都要回答的问题。
CREATE TABLE logistics_transit_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 10.1.1.2：所属渠道配置（同库复合外键，带 tenant_id ⇒ 跨租户挂载不可能）===
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
  service_level text NOT NULL
    CHECK (service_level IN ('ECONOMY','STANDARD','EXPRESS')),

  -- === 分重量段：半开区间 [min, max)，NULL 上限 = 无上限（最后一段，见文件头第四节）===
  weight_min_kg numeric(19,6) NOT NULL CHECK (weight_min_kg >= 0),
  weight_max_kg numeric(19,6) CHECK (weight_max_kg IS NULL OR weight_max_kg > weight_min_kg),

  -- === 时效承诺（自然日，不是工作日 —— 工作日日历不在本模块的职责里）===
  transit_days_min int NOT NULL CHECK (transit_days_min >= 1),
  transit_days_max int NOT NULL CHECK (transit_days_max >= transit_days_min),

  -- 截单时间：当地承运商当天的收货截止钟点（`time` 无时区，见文件头第三节）。
  -- 缺了它，「今天 17:00 还能不能出库」这个问题没人答得了。
  cutoff_time time NOT NULL DEFAULT '18:00:00',

  -- 生效区间：**timestamptz**，要么都给要么都不给。承运商调价换档期时会整段换，
  -- 旧档期必须留在库里（历史运单要能回溯当时的口径），所以是区间而不是覆盖写。
  effective_from timestamptz,
  effective_to timestamptz,
  CHECK ((effective_from IS NULL) = (effective_to IS NULL)),
  CHECK (effective_from IS NULL OR effective_from < effective_to),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),

  -- readiness（DB 层那半）：**不接受调用方指定**，由 trigger a 重算比对。
  readiness text NOT NULL DEFAULT 'READY'
    CHECK (readiness IN ('READY','NO_TRANSIT','NO_BAND','DISABLED')),

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 时效估算的热路径：「这条渠道 + 这个国家 + 这个服务等级，哪些段覆盖了这个重量」。
CREATE INDEX logistics_transit_rule_by_lookup
  ON logistics_transit_rule(tenant_id, channel_id, country_code, service_level,
                            weight_min_kg, weight_max_kg);

-- 「这个国家有哪些时效承诺」（跨渠道视角，也是列表筛选的下拉）。
CREATE INDEX logistics_transit_rule_by_country
  ON logistics_transit_rule(tenant_id, country_code);

CREATE INDEX logistics_transit_rule_by_channel
  ON logistics_transit_rule(tenant_id, channel_id);

-- 列表上的两个下拉（状态 / 可用性）。
CREATE INDEX logistics_transit_rule_by_status
  ON logistics_transit_rule(tenant_id, status);
CREATE INDEX logistics_transit_rule_by_readiness
  ON logistics_transit_rule(tenant_id, readiness);

-- 「生效区间现在覆盖这一刻吗」—— 判定端点与列表的 effectiveNow 同一口径。
CREATE INDEX logistics_transit_rule_by_window
  ON logistics_transit_rule(tenant_id, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- 2. logistics_size_limit · 尺寸上限 / 重量上限
-- ---------------------------------------------------------------------------
-- 职责句里的「尺寸上限、重量上限」。一条 = 一个渠道在**一个国家**（country_code 为
-- NULL 时 = 渠道级，对所有国家生效）上的硬边界。
--
-- 为什么「渠道级 + 国家级」两行而不是只留国家级：绝大多数承运商只有一个通用尺寸
-- 上限，只有少数国家（如带电件）另有一条更严的。只留国家级的话，运营要给「所有
-- 国家 30cm」就得起 N 行（N = 国家数），且改一次上限要改 N 次。
--
-- 判定口径：**取最严的一条**（渠道级与国家级里最严的维度分别取），所以 trigger d
-- 直接拒绝「国家级比渠道级更宽」的那条数据，不让它进来当噪声。
--
-- 三维（长/宽/高）要么都给要么都不给：只给长宽不给高会造出「20×30×∞」这种
-- 看上去能过、实际谁也判不了的半配置，而高恰恰是航空与快递最常卡的一维。
CREATE TABLE logistics_size_limit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- NULL = 渠道级（所有国家）；两位大写 = 该国更严的一条
  country_code varchar(2) CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),

  -- 尺寸一律 cm（行业通用单位，且与承运商合同上的口径一致）
  max_length_cm numeric(19,6) CHECK (max_length_cm IS NULL OR max_length_cm > 0),
  max_width_cm numeric(19,6) CHECK (max_width_cm IS NULL OR max_width_cm > 0),
  max_height_cm numeric(19,6) CHECK (max_height_cm IS NULL OR max_height_cm > 0),
  -- 周长和（长 + 宽 + 高 × 2）：快递与部分航司卡的是这个而不是单边长
  max_girth_cm numeric(19,6) CHECK (max_girth_cm IS NULL OR max_girth_cm > 0),
  -- 重量上限：与「重量段」是两件事 —— 段是**时效承诺**的分片，重量上限是**能不能发**
  max_weight_kg numeric(19,6) CHECK (max_weight_kg IS NULL OR max_weight_kg > 0),

  -- 至少要给一项，否则这是一行「没写限制」
  CHECK (num_nonnulls(max_length_cm, max_width_cm, max_height_cm,
                      max_girth_cm, max_weight_kg) >= 1),
  -- 三维要么都给要么都不给（见上面的说明）
  CHECK ((max_length_cm IS NULL) = (max_width_cm IS NULL)
     AND (max_width_cm IS NULL) = (max_height_cm IS NULL)),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 一个渠道在一个国家（或渠道级）只能有一条上限 —— 两条上限的判定结果取决于
-- 取哪条，与重量段重叠是同一类「靠返回顺序决定结果」的病。
CREATE UNIQUE INDEX logistics_size_limit_by_scope
  ON logistics_size_limit(tenant_id, channel_id, coalesce(country_code, ''));

CREATE INDEX logistics_size_limit_by_channel
  ON logistics_size_limit(tenant_id, channel_id);

-- 「这个国家有哪些渠道有尺寸上限」—— precheck 的热路径。
CREATE INDEX logistics_size_limit_by_country
  ON logistics_size_limit(tenant_id, country_code);

-- ---------------------------------------------------------------------------
-- 3. logistics_marking_requirement · 电池 / 液体 / 危险品 / 磁力 标记要求
-- ---------------------------------------------------------------------------
-- 职责句里的「电池/液体/危险品标记要求」，与 V136 的「渠道限制」是两件事：
--   V136 回答「能不能发」（禁 / 需人工 / 放行）
--   本表回答「能发的话要贴什么」（BLOCK / MANUAL_REVIEW / LABEL_REQUIRED / ALLOWED）
-- LABEL_REQUIRED 是本表存在的理由：它表达「带电可以走，但必须贴 UN38.3 标识」，
-- 而这条信息是 10.2.2.2 面单模板绑定打印规格的依据（漏打标识会在清关出问题）。
CREATE TABLE logistics_marking_requirement (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- DANGEROUS_GOODS 是 V136 的四种里没有的第五种，也是刊登阶段最需要拦的一类
  flag_kind text NOT NULL
    CHECK (flag_kind IN ('BATTERY','LIQUID','DANGEROUS_GOODS','MAGNET')),
  -- BLOCK 渠道直接拒收 · MANUAL_REVIEW 需人工确认 · LABEL_REQUIRED 可以走但必须贴
  -- marking_code 标识 · ALLOWED 明确放行
  requirement_mode text NOT NULL
    CHECK (requirement_mode IN ('BLOCK','MANUAL_REVIEW','LABEL_REQUIRED','ALLOWED')),
  -- 要贴什么标识（UN38.3 / IATA 锂电池标 / 液体向上箭头 …）。要求贴却没点名贴什么，
  -- 等于把「漏打标识」这件事留给下一个页面的人 —— 与 10.2.2.2 的坑是同一个。
  marking_code varchar(64) NOT NULL DEFAULT '',
  -- 承运商侧原文（拒收通知 / 合同条款），留空不影响判定但让人知道「为什么」
  carrier_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个渠道对一个 flag 只有一条要求：两条要求的判定结果同样取决于取哪条
  UNIQUE (tenant_id, channel_id, flag_kind),
  -- 🔴 要求贴标识就必须点名贴什么
  CHECK (requirement_mode <> 'LABEL_REQUIRED' OR btrim(marking_code) <> '')
);

-- precheck 的热路径：「这条渠道对带电/带液/带磁/危险品各是什么要求」。
CREATE INDEX logistics_marking_requirement_by_channel
  ON logistics_marking_requirement(tenant_id, channel_id);

-- 列表筛选下拉 + 概览分布。
CREATE INDEX logistics_marking_requirement_by_flag
  ON logistics_marking_requirement(tenant_id, flag_kind);

-- ---------------------------------------------------------------------------
-- 4. 触发器
-- ---------------------------------------------------------------------------

-- (a) readiness 重算比对（照抄 10.1.1.2 的 guard_logistics_channel_readiness）。
CREATE OR REPLACE FUNCTION guard_logistics_transit_readiness() RETURNS trigger AS $$
DECLARE
  expected text;
BEGIN
  IF NEW.transit_days_max < 1 OR NEW.transit_days_min < 1 THEN
    expected := 'NO_TRANSIT';
  ELSIF NEW.weight_max_kg IS NOT NULL AND NEW.weight_min_kg >= NEW.weight_max_kg THEN
    expected := 'NO_BAND';
  ELSIF NEW.status = 'DISABLED' THEN
    expected := 'DISABLED';
  ELSE
    expected := 'READY';
  END IF;

  IF NEW.readiness <> expected THEN
    -- ⚠️ 占位符一律用**裸 %**：PostgreSQL 的 RAISE 没有 C 的 %s 转换。
    RAISE EXCEPTION
      '时效规则的可用性判定为 %（时效天数 % 至 % 天 / 重量段 [% 至 %] kg / 状态 %），与提交的 % 不符 —— 可用性由这三项算出，不接受手工指定',
      expected, NEW.transit_days_min, NEW.transit_days_max,
      NEW.weight_min_kg, coalesce(NEW.weight_max_kg::text, '无上限'), NEW.status, NEW.readiness
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_transit_readiness_trg
  BEFORE INSERT OR UPDATE ON logistics_transit_rule
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_transit_readiness();

-- (b) 🔴 重量段不得重叠（同渠道 + 同国家 + 同服务等级）。
--     半开区间 [min, max) 的交集判据是 `a.min < b.max and b.min < a.max`；
--     NULL 上限（无上限）按 +∞ 处理 —— 否则最后一段永远不与任何段重叠，
--     一个 200kg 的包裹会同时命中「>45kg」和「>100kg」两段。
CREATE OR REPLACE FUNCTION guard_logistics_transit_band_overlap() RETURNS trigger AS $$
DECLARE
  hit logistics_transit_rule%ROWTYPE;
BEGIN
  SELECT * INTO hit
    FROM logistics_transit_rule r
   WHERE r.tenant_id = NEW.tenant_id
     AND r.channel_id = NEW.channel_id
     AND r.id <> NEW.id
     AND r.country_code = NEW.country_code
     AND r.service_level = NEW.service_level
     AND r.status = 'ACTIVE'
     AND r.readiness = 'READY'
     -- 重量区间有交集（半开区间，NULL 上限按 +∞ 处理）
     AND r.weight_min_kg < coalesce(NEW.weight_max_kg, 'infinity'::numeric)
     AND NEW.weight_min_kg < coalesce(r.weight_max_kg, 'infinity'::numeric)
     -- 生效区间有交集（任一侧无区间 = 常驻，与任何区间都有交集）
     AND (r.effective_from IS NULL OR NEW.effective_from IS NULL
          OR r.effective_from < NEW.effective_to)
     AND (NEW.effective_from IS NULL OR r.effective_to IS NULL
          OR NEW.effective_from < r.effective_to)
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION
      '重量段 [% 至 %] kg 与本渠道 % 已有的一段 [% 至 %] kg 重叠 —— 重叠的时效承诺会让同一个包裹命中两条，估算结果取决于查询顺序',
      NEW.weight_min_kg, coalesce(NEW.weight_max_kg::text, '无上限'), NEW.country_code,
      hit.weight_min_kg, coalesce(hit.weight_max_kg::text, '无上限')
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_transit_band_overlap_trg
  BEFORE INSERT OR UPDATE ON logistics_transit_rule
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_transit_band_overlap();

-- (c) 🔴 时效规则的国家必须是这条渠道**适用**的国家。
--     条件触发：只有该渠道真的登记过 COUNTRY 适用维度时才校验（见文件头第五节）。
CREATE OR REPLACE FUNCTION guard_logistics_transit_country_scope() RETURNS trigger AS $$
DECLARE
  scoped boolean;
  allowed text;
BEGIN
  SELECT count(*) > 0 INTO scoped
    FROM logistics_channel_scope s
   WHERE s.tenant_id = NEW.tenant_id AND s.channel_id = NEW.channel_id
     AND s.scope_kind = 'COUNTRY';

  IF NOT scoped THEN
    RETURN NEW;
  END IF;

  SELECT s.scope_value INTO allowed
    FROM logistics_channel_scope s
   WHERE s.tenant_id = NEW.tenant_id AND s.channel_id = NEW.channel_id
     AND s.scope_kind = 'COUNTRY' AND s.scope_value = NEW.country_code;

  IF allowed IS NULL THEN
    RAISE EXCEPTION
      '渠道不适配国家 %：给它登记时效承诺等于凭空造一条走不通的线路（上游 10.1.1.2 的适用范围里没有这个国家）',
      NEW.country_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_transit_country_scope_trg
  BEFORE INSERT OR UPDATE ON logistics_transit_rule
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_transit_country_scope();

-- (d) 🔴 国家级上限不得比渠道级更宽（见文件头第五节）。
--     只读对照、不改上游：判定取最严的一条，所以更宽的那条必须写不进来。
CREATE OR REPLACE FUNCTION guard_logistics_size_limit_not_looser() RETURNS trigger AS $$
DECLARE
  base_row logistics_size_limit%ROWTYPE;
  base_label text;
  loose text;
BEGIN
  IF NEW.country_code IS NULL THEN
    -- 渠道级本身不受对照约束
    RETURN NEW;
  END IF;

  SELECT * INTO base_row
    FROM logistics_size_limit l
   WHERE l.tenant_id = NEW.tenant_id AND l.channel_id = NEW.channel_id
     AND l.country_code IS NULL AND l.id <> NEW.id;

  IF NOT FOUND THEN
    -- 没有渠道级对照：这条国家级上限就是唯一基准
    RETURN NEW;
  END IF;
  base_label := '渠道级上限';

  -- 逐维比较：只有「两边都填了」才比较（未填 = 该维不限，不是 0）
  IF base_row.max_length_cm IS NOT NULL AND NEW.max_length_cm IS NOT NULL
     AND NEW.max_length_cm > base_row.max_length_cm THEN
    loose := '长 ' || NEW.max_length_cm || ' > ' || base_label || ' ' || base_row.max_length_cm;
  ELSIF base_row.max_width_cm IS NOT NULL AND NEW.max_width_cm IS NOT NULL
     AND NEW.max_width_cm > base_row.max_width_cm THEN
    loose := '宽 ' || NEW.max_width_cm || ' > ' || base_label || ' ' || base_row.max_width_cm;
  ELSIF base_row.max_height_cm IS NOT NULL AND NEW.max_height_cm IS NOT NULL
     AND NEW.max_height_cm > base_row.max_height_cm THEN
    loose := '高 ' || NEW.max_height_cm || ' > ' || base_label || ' ' || base_row.max_height_cm;
  ELSIF base_row.max_girth_cm IS NOT NULL AND NEW.max_girth_cm IS NOT NULL
     AND NEW.max_girth_cm > base_row.max_girth_cm THEN
    loose := '周长和 ' || NEW.max_girth_cm || ' > ' || base_label || ' ' || base_row.max_girth_cm;
  ELSIF base_row.max_weight_kg IS NOT NULL AND NEW.max_weight_kg IS NOT NULL
     AND NEW.max_weight_kg > base_row.max_weight_kg THEN
    loose := '重量 ' || NEW.max_weight_kg || ' > ' || base_label || ' ' || base_row.max_weight_kg;
  END IF;

  IF loose IS NOT NULL THEN
    RAISE EXCEPTION
      '国家 % 的上限比%更宽（% cm）：上限是「超过就拒收」的硬边界，判定取最严的一条，写宽的那条只会让人以为该国更宽松从而放行超限件',
      NEW.country_code, base_label, loose USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_size_limit_not_looser_trg
  BEFORE INSERT OR UPDATE ON logistics_size_limit
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_size_limit_not_looser();

-- (e) 🔴🔴 标记要求不得与上游 10.1.1.2 的渠道限制打架（只读对照，不改上游）。
--     上游已判 BLOCK 的 flag_kind，本表只允许 BLOCK —— 否则会得到
--     「一边说承运商拒收带电、一边说贴个标就能走」的自相矛盾配置。
CREATE OR REPLACE FUNCTION guard_logistics_marking_vs_restriction() RETURNS trigger AS $$
DECLARE
  upstream_mode text;
  upstream_reason text;
BEGIN
  SELECT x.restriction_mode, x.carrier_reason INTO upstream_mode, upstream_reason
    FROM logistics_channel_restriction x
   WHERE x.tenant_id = NEW.tenant_id AND x.channel_id = NEW.channel_id
     AND x.restriction_kind = NEW.flag_kind;

  IF upstream_mode = 'BLOCK' AND NEW.requirement_mode <> 'BLOCK' THEN
    RAISE EXCEPTION
      '上游 10.1.1.2 已把 % 列为硬拦截（%），本表只能登记 BLOCK —— 登记成 % 会得到「一边说承运商拒收、一边说贴个标就能走」的自相矛盾配置',
      NEW.flag_kind,
      CASE WHEN coalesce(upstream_reason, '') = '' THEN '未登记理由' ELSE upstream_reason END,
      NEW.requirement_mode USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_marking_vs_restriction_trg
  BEFORE INSERT OR UPDATE ON logistics_marking_requirement
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_marking_vs_restriction();

-- ---------------------------------------------------------------------------
-- 5. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_transit_rule',
    'logistics_size_limit',
    'logistics_marking_requirement' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 6. 账号与授权（沿用 V1/V93/V129/V134/V136 的条件建角色，保证迁移可重复执行）
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
  logistics_transit_rule, logistics_size_limit, logistics_marking_requirement TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 同口径）。
REVOKE ALL ON
  logistics_transit_rule, logistics_size_limit, logistics_marking_requirement FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 7. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：时效与限制属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.3.2.1 交运批次同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
