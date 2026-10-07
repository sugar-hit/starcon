-- ===========================================================================
-- 7.8.1.1 分配策略配置：同一份可用库存分给多个店铺/平台的方式
-- ===========================================================================
-- 蓝图原文：「定义同一总可用库存分配给多个店铺/平台的方式：按销量占比比例分配、
-- 按固定优先级先到先得、按店铺类型（自营/托管）优先级、留出不分配缓冲。
-- 支持 SKU 或店铺维度的策略覆盖。输入：分配策略配置（方法、参数、生效范围）；
-- 当前可用量；各店铺历史销量。输出：生效的分配策略；各店铺分摊额。」
-- 以及关系段的红线：「**多平台共享库存时必须有一份统一的分摊算法**，否则会出现
-- 『某个店铺一直抢不到货』或『所有店铺都以为有货』的超卖。」
--
-- ---------------------------------------------------------------------------
-- 为什么这个模块必须有「一档」而不是一行可改的配置
-- ---------------------------------------------------------------------------
-- 与 7.2.2.1 的 sellable_policy 完全同构，也用同一条理由：
--   **一个范围槽位在一个时刻只能有一个生效值**，所以「历史」不是另一份拷贝，
--   而是同一批行里已关区间的那些。改策略 = 收口旧档 + 开新档。
-- 蓝图要求「支持 SKU 或店铺维度的策略覆盖」，覆盖就是分档：
--   优先级 SKU > SHOP > DEFAULT（与 17.5.1.1、7.2.2.1 同一套解析约定）。
-- 配套触发器把「策略内容写定后不可改」钉在 DB 层 —— 分配算法是防超卖的那道闸门，
-- 而能悄悄改掉一条正在生效的算法，等于把闸门本身拆了。
--
-- ---------------------------------------------------------------------------
-- 「留出不分配缓冲」与 7.2.2.1 的 buffer_ratio 是**两个不同的东西**
-- ---------------------------------------------------------------------------
--   7.2.2.1 buffer_ratio：平台回写缓冲。单店口径下「可回写给平台的件数留多少余量」，
--                        抵御的是**平台回写同步延迟**。
--   7.8.1.1 buffer      ：分摊缓冲。同一批货分给 N 个店铺时「有多少压根不参与分摊」，
--                        抵御的是**各平台同时下单**（同步延迟之外的另一条超卖路径）。
-- 两者串联：可分配量 = (可用量 − 分摊缓冲)。7.2.2.1 的缓冲在它上游、各店自己的回写口径里，
-- 本模块**不重复扣一次** —— 重复扣会让同一批货被两块缓冲各留一次，余量凭空少一截。
-- capabilities 端点把这条区别原样讲给前端，避免运营把两个百分比当成同一个东西。
--
-- ---------------------------------------------------------------------------
-- 跨服务边界：店铺与 SKU 都不在本服务
-- ---------------------------------------------------------------------------
-- 店铺表在 channel 服务（channel V1 的 shop，2.3.1.1/2.3.2.1 已交付），
-- SKU 在 catalog 服务。AGENTS.md「模块边界纪律」禁止跨服务 SQL / FK / 视图。
-- 照 7.2.2.1 的先例：只存 source_system + reference_id（逻辑 ID）+ 写入时可读名快照。
-- 代价是本服务无法校验「这个店铺真的存在」，由调用方保证（前端选店铺时从 /channel/shops 取）。
--
-- 店铺**类型**（SELF_FULFILLED/FULL_CONSIGNMENT/…）是 2.3.2.1 的 operating_mode，
-- 「按店铺类型优先级」这条方法必须真的能按类型排序，所以它快照到成员行
-- （shop_operating_mode）。同理，历史销量快照不到本服务能推的表上
-- （inventory_transaction 没有店铺维度，出库量 ≠ 店铺销量），
-- 所以另建 allocation_shop_sales 作为**各店铺历史销量的唯一真相来源**，
-- 由登记端点写入（订单域 05 未交付，无法自动派生；capabilities 如实报出来）。
--
-- ---------------------------------------------------------------------------
-- 分摊守恒是 DB 不变量，不是应用层自觉
-- ---------------------------------------------------------------------------
-- 蓝图红线里「统一分摊算法」要防的是「所有店铺都以为有货」。落到 schema 上就是：
--
--   可用量 available = 分摊缓冲 buffer + 可分配量 allocatable
--   可分配量 allocatable = Σ 各店分摊额 allocated_total + 未分配完 unallocated
--
-- 第一条由 allocation_run 的 CHECK 保证；第二条由**延迟约束触发器**保证 ——
-- Σ行 必须等于头里的 allocated_total，逐行加总而不是信服务层写上来的那个数。
-- 用延迟而不是即时触发器：一次分摊要「先写头再写若干行」，即时触发会在事务中途
-- 对着半成品报不平（与 V51 台账、V62 调整单同一条理由）。
--
-- 另外两条钉死「分摊额必须是整件」：一件货不能分给两个店铺，
-- 0.5 件既不是这个店铺的也不是那个店铺的 —— 那是超卖而不是分摊。

-- ===========================================================================
-- 1. valid_shop_type_order：类型优先级必须是五种经营模式的一个**排列**
-- ===========================================================================
-- 「按店铺类型优先级」里的**顺序本身是经营决策**，不能由代码硬编码：
-- 自营优先还是全托管优先，取决于这批货是谁的货权。硬编码等于替租户做决定。
-- 但也不能放任成任意字符串：顺序写错、漏一种类型、重复一种类型，都会让
-- 「按类型优先级」解析出一个没有唯一答案的排序。
CREATE FUNCTION valid_shop_type_order(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
  SELECT value IS NOT NULL
    AND array_length(string_to_array(value, ','), 1) = 5
    AND (string_to_array(value, ',')) @> ARRAY[
          'SELF_FULFILLED','SEMI_CONSIGNMENT','FULL_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL']
    AND NOT EXISTS (
      SELECT 1 FROM unnest(string_to_array(value, ',')) AS m
       WHERE m NOT IN ('SELF_FULFILLED','SEMI_CONSIGNMENT','FULL_CONSIGNMENT',
                       'OVERSEAS_WAREHOUSE','LOCAL'))
$$;

-- ===========================================================================
-- 2. allocation_policy · 分摊策略分档
-- ===========================================================================
CREATE TABLE allocation_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 生效范围三档。SKU 档是蓝图明确要求的「SKU 或店铺维度的策略覆盖」：
  -- 同一批货里某个爆款 SKU 的分摊方式和其余 SKU 不同，是常态。
  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('DEFAULT','SHOP','SKU')),

  -- 跨服务定位键。SHOP 档指向 channel 的 shop，SKU 档指向 catalog 的 sku。
  -- 两者都不在本服务，不建外键（见文件头）。
  source_system varchar(32) NOT NULL DEFAULT '',
  reference_id uuid,
  -- 可读名在写入时快照：店铺改名后，半年前那一档仍然读得懂自己管的是谁。
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 分摊方法。三个取值对应蓝图列的三种「方式」：
  --   PROPORTIONAL_SALES 按销量占比比例分配（销量 0 的店铺分不到货，如实如此）
  --   FIXED_PRIORITY     按固定优先级先到先得
  --   SHOP_TYPE_PRIORITY 按店铺类型（自营/托管）优先级，同类型内再按成员优先级
  method varchar(24) NOT NULL CHECK(method IN (
    'PROPORTIONAL_SALES','FIXED_PRIORITY','SHOP_TYPE_PRIORITY')),

  -- 留出不分配缓冲。二选一，两列互斥由下面的 CHECK 钉死：
  --   RATIO    按比例留（0~1），适合「永远留一成」
  --   QUANTITY 按件数留，适合「不管多少货都留 50 件」
  buffer_mode varchar(8) NOT NULL DEFAULT 'RATIO' CHECK(buffer_mode IN ('RATIO','QUANTITY')),
  buffer_ratio numeric(9,6) NOT NULL DEFAULT 0,
  buffer_quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 按店铺类型优先级时的类型顺序。**策略级参数而不是硬编码**（见 §1）。
  -- 只有 SHOP_TYPE_PRIORITY 会读它，另两种方法下这一列仍然被 CHECK 强制为合法排列 ——
  -- 一列一旦允许非法值，将来切换方法时就会带着一个解析不出顺序的档。
  shop_type_order varchar(400) NOT NULL
    DEFAULT 'SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL',

  -- 比例分配取销量的时间窗口（天，左闭右开：近 N 天，不含今天往前第 N 天的整天）。
  -- 只对 PROPORTIONAL_SALES 有意义，另两种方法不读它。
  sales_window_days integer NOT NULL DEFAULT 30,

  -- 生效区间（左闭右开）。effective_to 为 NULL = 至今仍在生效。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  -- 操作人：已认证成员 id（与 V54/V56 同一纪律，不接受请求参数传入）。
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 范围与跨服务定位键必须配套。少一条就会出现一档永远命中不了：
  -- 「SKU 档却没有 reference_id」在解析时无从回答「它到底管谁」。
  CHECK(
    (scope_type='DEFAULT'  AND source_system=''      AND reference_id IS NULL) OR
    (scope_type IN ('SHOP','SKU') AND source_system<>'' AND reference_id IS NOT NULL)
  ),

  CHECK(effective_to IS NULL OR effective_to > effective_from),

  -- 缓冲比例取值范围（DB 层硬约束，与 V56 同一道防线）。
  CHECK(buffer_ratio >= 0 AND buffer_ratio <= 1),
  CHECK(buffer_quantity >= 0),
  -- 两列互斥：同时给比例和件数，「到底留多少」这个问题就没有唯一答案。
  CHECK((buffer_mode='RATIO'    AND buffer_quantity = 0) OR
        (buffer_mode='QUANTITY' AND buffer_ratio = 0)),

  CHECK(valid_shop_type_order(shop_type_order)),
  CHECK(sales_window_days > 0 AND sales_window_days <= 365)
);

-- 同一范围槽位**至多一档仍在生效**。两个部分唯一索引而不是一条三列 UNIQUE：
-- PG 认为 NULL 互不相等，DEFAULT 档的 reference_id 恒为 NULL，普通 UNIQUE 根本不去重。
CREATE UNIQUE INDEX allocation_policy_open_default
  ON allocation_policy(tenant_id) WHERE scope_type='DEFAULT' AND effective_to IS NULL;
CREATE UNIQUE INDEX allocation_policy_open_reference
  ON allocation_policy(tenant_id, scope_type, reference_id)
  WHERE scope_type<>'DEFAULT' AND effective_to IS NULL;

-- 生效值解析的主查询。
CREATE INDEX allocation_policy_resolve
  ON allocation_policy(tenant_id, scope_type, reference_id, effective_from DESC);
-- 反查「这个店铺/SKU 配过哪几档」（页面的版本时间线走这条）。
CREATE INDEX allocation_policy_reference
  ON allocation_policy(tenant_id, source_system, reference_id) WHERE reference_id IS NOT NULL;
CREATE INDEX allocation_policy_open
  ON allocation_policy(tenant_id, effective_from DESC) WHERE effective_to IS NULL;

-- ===========================================================================
-- 3. allocation_policy_member · 参与分摊的店铺
-- ===========================================================================
-- 成员挂在**策略**上而不是挂在店铺上：同一店铺在不同 SKU 下可能参与不同的分摊，
-- 把成员挂到店铺会让「谁参与这次分摊」这个问题必须靠另一张表回答，
-- 而那张表一旦漏同步，就会出现「这个店铺明明配了却不在名单里」——
-- 那正是蓝图说的「某个店铺一直抢不到货」。
CREATE TABLE allocation_policy_member (
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,

  -- 跨服务定位键：店铺在 channel 服务（2.3.1.1/2.3.2.1 已交付），不建外键。
  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 店铺类型快照，取值与 channel 的 shop.operating_mode 完全一致（2.3.2.1）。
  -- 「按店铺类型优先级」这条方法真的按它排序，所以它必须落在数据里而不是代码里。
  shop_operating_mode varchar(24) NOT NULL CHECK(shop_operating_mode IN (
    'SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL')),

  -- 固定优先级：数值小 = 先到先得。SHOP_TYPE_PRIORITY 下它是**同类型内**的次序。
  priority integer NOT NULL DEFAULT 100 CHECK(priority >= 0),

  -- 份额上限 0~1，可空 = 不设上限。
  -- 「先到先得」没有上限就意味着第一个成员拿走全部 —— 这在业务上几乎总是错的，
  -- 所以这里给一个能显式封顶的口子，并让「不设上限」成为一个必须被看见的取值。
  share_limit numeric(9,6) CHECK(share_limit IS NULL OR (share_limit > 0 AND share_limit <= 1)),

  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),

  -- 同一策略内一个店铺只能出现一次：出现两次会让「这个店分到几成」有两套答案。
  PRIMARY KEY(tenant_id,policy_id,reference_id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES allocation_policy(tenant_id,id) ON DELETE CASCADE
);

-- 分摊时的成员读取顺序：优先级 + 定位键（定位键唯一，所以这是一个全序，
-- 顺序稳定到每一次分摊都能复现同样的结果）。
CREATE INDEX allocation_policy_member_order
  ON allocation_policy_member(tenant_id,policy_id,priority,reference_id);

-- ===========================================================================
-- 4. allocation_shop_sales · 各店铺历史销量（本服务唯一的销量真相来源）
-- ===========================================================================
-- 为什么不能从库存流水推：inventory_transaction 有 kind/ref_type 但**没有店铺维度**，
-- 一个仓发出去的货无法拆到「哪个店卖掉的」；而按店铺类型优先级与按销量占比
-- 都必须按店铺分摊。订单域（05 卷）尚未交付，没有可查的订单事实。
-- 所以销量是**登记进来的事实**，由 POST 端点写入，不是从别处算出来的。
CREATE TABLE allocation_shop_sales (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- SKU 属 catalog：逻辑 ID，不建外键（与 V1/V2 同一纪律）。
  sku_id uuid NOT NULL,

  -- 业务日期（不是系统时间）：销量本来就是业务事实。
  -- 闭区间，比 effective_from/effective_to 少一个边界坑。
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK(period_end >= period_start),

  units_sold numeric(19,6) NOT NULL DEFAULT 0 CHECK(units_sold >= 0),

  recorded_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一店铺同一 SKU 同一区间只有一条：重复登记会把这段时间的销量算两遍，
  -- 比例分配随之偏掉，而界面上看不出任何异常。
  UNIQUE(tenant_id,source_system,reference_id,sku_id,period_start,period_end)
);

-- 比例分配的主查询：近 N 天的销量按店铺聚合。
CREATE INDEX allocation_shop_sales_window
  ON allocation_shop_sales(tenant_id,sku_id,period_end DESC,reference_id);
CREATE INDEX allocation_shop_sales_sku
  ON allocation_shop_sales(tenant_id,sku_id,reference_id);

-- ===========================================================================
-- 5. allocation_run · 一次分摊的头
-- ===========================================================================
-- 落库而不是每次现算：蓝图输出是「各店铺分摊额」，而下游 7.8.2.1 是
-- 「分摊结果查看」。不落库，下游就没有任何东西可看；而且超卖复盘需要回答
-- 「当时到底是按哪一档、当时的销量是多少算出来的」—— 现算只能算出**现在**的结果。
CREATE TABLE allocation_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  policy_id uuid NOT NULL,
  -- 分摊算法的**快照**：哪一档、什么方法。不快照的话，档位一旦被收口，
  -- 这次分摊就再也解释不清了。
  method varchar(24) NOT NULL CHECK(method IN (
    'PROPORTIONAL_SALES','FIXED_PRIORITY','SHOP_TYPE_PRIORITY')),
  sku_id uuid NOT NULL,

  -- 分摊时刻（业务时间）。左闭右开窗口按它回看销量。
  allocated_at timestamptz NOT NULL,
  sales_window_start date NOT NULL,
  sales_window_end date NOT NULL,

  -- 三段账，全部 numeric(19,6) + BigDecimal（AGENTS.md：禁浮点记账）。
  -- 三者都必须是整件：分摊额按件计，半件既不属于这家也不属于那家 —— 那是超卖。
  available_quantity numeric(19,6) NOT NULL CHECK(available_quantity >= 0),
  buffer_quantity numeric(19,6) NOT NULL CHECK(buffer_quantity >= 0),
  allocatable_quantity numeric(19,6) NOT NULL CHECK(allocatable_quantity >= 0),
  allocated_total numeric(19,6) NOT NULL DEFAULT 0 CHECK(allocated_total >= 0),
  -- 未分配完的量：先到先得下低优先级店铺拿不到货是**正常结果**，
  -- 把它显式记成 unallocated，而不是让「分不满」看起来像「刚好分完」。
  unallocated_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(unallocated_quantity >= 0),

  note varchar(500) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES allocation_policy(tenant_id,id),

  -- 第一条守恒式：可用量 = 分摊缓冲 + 可分配量。缓冲超出可用量会让
  -- 可分配量变负，而负的可分配量分摊出去必然是超卖。
  CHECK(buffer_quantity <= available_quantity),
  CHECK(allocatable_quantity = available_quantity - buffer_quantity),

  -- 第二条守恒式（纯列间关系，行级 CHECK 就够，不需要等提交）：
  -- 可分配量 = Σ分摊额 + 未分配完。先到先得下低优先级店铺拿不到货是**正常结果**，
  -- 把它显式记成 unallocated，而不是让「分不满」看起来像「刚好分完」。
  -- 少了这条，「各店分摊额之和 > 可用量」会是一条能提交成功的记录 —— 那就是超卖。
  CHECK(allocated_total + unallocated_quantity = allocatable_quantity),

  CHECK(available_quantity = trunc(available_quantity)),
  CHECK(buffer_quantity = trunc(buffer_quantity)),
  CHECK(allocatable_quantity = trunc(allocatable_quantity)),
  CHECK(allocated_total = trunc(allocated_total)),
  CHECK(unallocated_quantity = trunc(unallocated_quantity)),

  CHECK(sales_window_end >= sales_window_start)
);

-- 第二条守恒式（Σ行 == 头）由延迟约束触发器保证，见 §7。
CREATE INDEX allocation_run_policy ON allocation_run(tenant_id,policy_id,allocated_at DESC);
CREATE INDEX allocation_run_sku ON allocation_run(tenant_id,sku_id,allocated_at DESC);

-- ===========================================================================
-- 6. allocation_run_line · 一次分摊里每个店铺的份额
-- ===========================================================================
CREATE TABLE allocation_run_line (
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',
  shop_operating_mode varchar(24) NOT NULL CHECK(shop_operating_mode IN (
    'SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL')),

  -- 分摊时的次序（1 = 最先）。它是**结果的一部分**而不是配置：
  -- 事后能直接看出「当时是谁先拿的货」。
  allocation_order integer NOT NULL CHECK(allocation_order > 0),

  -- 该店的销量与占比：销量取自 allocation_shop_sales 的登记事实（没有就是 0），
  -- 占比是它在本档全部成员销量里的份额。两者都留痕，「为什么它分得多」才有答案。
  sales_units numeric(19,6) NOT NULL DEFAULT 0 CHECK(sales_units >= 0),
  sales_share numeric(9,6) CHECK(sales_share IS NULL OR (sales_share >= 0 AND sales_share <= 1)),

  -- 分摊额。**必须是整件**：一件货不能劈成两半分给两个店铺，
  -- 半件既不属于这家也不属于那家 —— 那是超卖而不是分摊。trunc 钉在 DB 层。
  allocated_qty numeric(19,6) NOT NULL DEFAULT 0
    CHECK(allocated_qty >= 0 AND allocated_qty = trunc(allocated_qty)),

  -- 为什么它拿到这个数：CAP_REACHED 触到份额上限 / DEMAND_EXHAUSTED 货被前面的分完 /
  -- NO_SALES 销量为 0 拿不到货 / OK 正常。
  -- 蓝图说的「某个店铺一直抢不到货」必须能一眼看出是哪种原因。
  outcome varchar(20) NOT NULL CHECK(outcome IN (
    'OK','CAP_REACHED','DEMAND_EXHAUSTED','NO_SALES','DISABLED')),

  created_at timestamptz NOT NULL DEFAULT now(),

  -- 同一店铺在一次分摊里只有一行：出现两行会让「它一共分到多少」需要额外求和，
  -- 而那一列并不存在于表里。
  PRIMARY KEY(tenant_id,run_id,reference_id),
  FOREIGN KEY(tenant_id,run_id) REFERENCES allocation_run(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 7. DB 级行为约束
-- ===========================================================================

-- 7.1 策略内容写定后不可改，只允许把 effective_to 从 NULL 收到一个值。
-- 与 V56 的 guard_sellable_policy_immutable 同一道防线、同一句理由：
-- 能悄悄覆盖一条正在生效的分摊算法，等于把防超卖的那道闸门拆了。
CREATE FUNCTION guard_allocation_policy_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '分摊策略内容写定后不可修改，effective_to 只能从空收到一个时刻（换版或作废）：'
     '改策略请开新的一档，否则历史那一档的算法会被悄悄改掉，而它可能正是当时防住超卖的那道闸门'
     USING ERRCODE='23514';
 END IF;
 IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '这一档的生效区间已经被收口（effective_to=%），不允许再次修改或复活', OLD.effective_to
     USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to <= OLD.effective_from THEN
   RAISE EXCEPTION '收口时刻必须晚于该档的生效起点 %，否则这一档从未真正生效过', OLD.effective_from
     USING ERRCODE='23514';
 END IF;
 IF (NEW.scope_type,NEW.source_system,NEW.reference_id,NEW.reference_label,
     NEW.method,NEW.buffer_mode,NEW.buffer_ratio,NEW.buffer_quantity,
     NEW.shop_type_order,NEW.sales_window_days,
     NEW.effective_from,NEW.note,NEW.created_by_membership_id)
    IS DISTINCT FROM
    (OLD.scope_type,OLD.source_system,OLD.reference_id,OLD.reference_label,
     OLD.method,OLD.buffer_mode,OLD.buffer_ratio,OLD.buffer_quantity,
     OLD.shop_type_order,OLD.sales_window_days,
     OLD.effective_from,OLD.note,OLD.created_by_membership_id) THEN
   RAISE EXCEPTION '策略内容（范围/方法/缓冲/类型顺序/销量窗口）写定后不可修改，请开新的一档'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_policy_immutable
  BEFORE UPDATE ON allocation_policy
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_policy_immutable();

-- 7.2 同一范围槽位的生效区间不得重叠。
-- 重叠区间在解析时没有唯一答案 ——「那个时刻听谁的」这个问题无解，
-- 而它平时完全看不出来，直到某天两档的方法不一样时爆发。
-- reference_id coalesce 到全零 uuid 才能让 DEFAULT 档的多行互相比较（与 V56 同理由）。
CREATE FUNCTION guard_allocation_policy_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from timestamptz; other_to timestamptz;
BEGIN
 SELECT p.id, p.effective_from, p.effective_to INTO other_id, other_from, other_to
   FROM allocation_policy p
  WHERE p.tenant_id = NEW.tenant_id
    AND p.scope_type = NEW.scope_type
    AND coalesce(p.reference_id,'00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(NEW.reference_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND p.id <> NEW.id
    AND tstzrange(p.effective_from, p.effective_to, '[)')
      && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY p.effective_from
  LIMIT 1;
 IF other_id IS NOT NULL THEN
   RAISE EXCEPTION '该分摊策略在 % ~ % 区间与另一档重叠（id=%）：同一范围同一时刻只能有一个生效值，'
     '否则分摊结果没有唯一答案', NEW.effective_from,
     coalesce(NEW.effective_to::text,'至今'), other_id
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_policy_interval
  BEFORE INSERT OR UPDATE ON allocation_policy
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_policy_interval();

-- 7.3 分摊头与分摊行不可改、不可删。
-- 分摊是一次事实：它回答的是「那一刻那批货分给了谁」，
-- 改一行或删一行等于让这个问题再也答不出来，而超卖复盘恰恰要问它。
CREATE FUNCTION guard_allocation_run_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'allocation_% 是 append-only：不允许 %，要重算请开一次新的分摊',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER allocation_run_append_only
  BEFORE UPDATE OR DELETE ON allocation_run
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_run_append_only();
CREATE TRIGGER allocation_run_line_append_only
  BEFORE UPDATE OR DELETE ON allocation_run_line
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_run_append_only();

-- 7.4 守恒：Σ 分摊行 必须等于头里的 allocated_total，且 Σ + 未分配 == 可分配量。
--
-- 这是蓝图红线「统一分摊算法」在 schema 上的落点：不校验的话，
-- 「各店铺分摊额之和大于可用量」会是一条能提交成功的记录 —— 那就是超卖。
-- 延迟约束触发器：一次分摊要先写头再写若干行，即时触发会在事务中途对着半成品报错。
CREATE FUNCTION guard_allocation_run_conservation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE t uuid; r uuid; head record; summed numeric(19,6);
BEGIN
 -- DELETE 行触发器里 NEW 未赋值，按 TG_OP 分流（同 V2 的 bucket 守恒）。
 IF TG_OP='DELETE' THEN
   t := OLD.tenant_id;
   r := OLD.run_id;
 ELSE
   t := NEW.tenant_id;
   r := NEW.run_id;
 END IF;
 SELECT h.allocatable_quantity, h.allocated_total, h.unallocated_quantity
   INTO head FROM allocation_run h WHERE h.tenant_id = t AND h.id = r;
 IF head IS NULL THEN RETURN NULL; END IF;
 SELECT coalesce(sum(l.allocated_qty), 0) INTO summed
   FROM allocation_run_line l WHERE l.tenant_id = t AND l.run_id = r;
 IF summed IS DISTINCT FROM head.allocated_total THEN
   RAISE EXCEPTION '分摊行合计 % 与分摊头记录的 % 不一致：分摊额之和必须逐行等于头，'
     '两套数并存时无法判断该信谁（分摊 run=%）', summed, head.allocated_total, r
     USING ERRCODE='23514';
 END IF;
 IF head.allocated_total + head.unallocated_quantity <> head.allocatable_quantity THEN
   RAISE EXCEPTION '分摊额 % 加未分配 % 不等于可分配量 %：可分配量要么分完、要么显式记为未分配，'
     '「分不满」不能看起来像「刚好分完」（分摊 run=%）',
     head.allocated_total, head.unallocated_quantity, head.allocatable_quantity, r
     USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER allocation_run_conservation
  AFTER INSERT OR UPDATE OR DELETE ON allocation_run_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_run_conservation();

-- 7.5 成员必须挂在一条真实存在且未作废的策略上。
-- 跨档换版时（新档开、旧档收口）成员要跟着复制，顺序不能反：
-- 先收口再插成员会被这条触发器挡住（那是正确的失败，但表达不出「顺带搬成员」这个意图）。
CREATE FUNCTION guard_allocation_member_policy_open() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE p_to timestamptz;
BEGIN
 SELECT p.effective_to INTO p_to FROM allocation_policy p
  WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.policy_id;
 IF p_to IS NOT NULL THEN
   RAISE EXCEPTION '策略 % 的生效区间已被收口（生效至 %），不能再往它上面挂成员',
     NEW.policy_id, p_to USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_member_policy_open
  BEFORE INSERT ON allocation_policy_member
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_member_policy_open();

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 V1/V2/V54/V56 同一套写法）
-- ===========================================================================
-- 五张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['allocation_policy','allocation_policy_member','allocation_shop_sales',
                           'allocation_run','allocation_run_line'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 既有迁移的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE
  ON allocation_policy, allocation_policy_member, allocation_shop_sales,
     allocation_run, allocation_run_line TO warehouse_app;

-- 策略档不删除：作废走 retire（关区间），档位行永久保留 ——
-- 它是「当时那道闸门长什么样」的唯一凭据。权限层先收一道，§7.1 再收一道。
REVOKE DELETE ON allocation_policy FROM warehouse_app;
-- 销量登记可纠正（登记错了就该改），所以不给 append-only 约束。
-- 分摊头与行是事实：权限层先收一道 DELETE，§7.3 的触发器再收一道 UPDATE。
REVOKE DELETE ON allocation_run, allocation_run_line FROM warehouse_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56 同口径）。
REVOKE ALL ON allocation_policy, allocation_policy_member, allocation_shop_sales,
  allocation_run, allocation_run_line FROM starcon_app;