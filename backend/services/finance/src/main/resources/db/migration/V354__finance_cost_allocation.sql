-- ===========================================================================
-- V354 · 12.4.2.2 分摊规则
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.4.2.2 分摊规则）原文：
--
--   职责：配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数
--         （数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。
--   输入：规则配置；待分摊费用池。
--   输出：分摊结果（保留舍入差额到指定对象）+ 分摊明细。
--   关系：上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。
--         **分摊结果之和必须等于被分摊总额**（含舍入差处理），
--         否则利润报表总额对不上 —— 这是财务最不能接受的错误。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移做什么 / 不做什么（先把边界钉死，再写 DDL）
-- ---------------------------------------------------------------------------
--
--   本模块**做**：把 12.4.2.1 归集出来的**费用池**按规则**分摊**到
--   SKU / SPU / 店铺 / 订单 / 类目五个维度上，产出**分摊结果 + 分摊明细**，
--   并把**舍入差**显式落到一个**指定对象**上、留痕。
--
--   本模块**不做**：
--   * **不产出任何利润数字**（下游 13 利润不在本仓）；
--   * **不改归集口径**（费用怎么归集是 12.4.2.1 的事；本模块只读它的池）；
--   * **不凭空造分摊对象**（分摊对象必须来自真实上游事实，见第四节）。
--
--   ⇒ 边界在**数据层**表达，不靠注释：
--       · 分摊结果行上有 `batch_id`，批次上有 `pool_id` ⇒
--         「结果属于哪个池的哪一次分摊」结构上说得清，不会出现游离结果；
--       · 批次上有 `UNIQUE(tenant_id, pool_id) WHERE status = 'ALLOCATED'`
--         ⇒ **一个池同时只能有一次分摊**（重复分摊会把钱摊两遍）；
--       * 批次上有 `CHECK(allocated_total = source_total)`
--         ⇒ **「分摊结果之和 = 被分摊总额」这条等式在数据层就写不出来违反的**。
--
-- ---------------------------------------------------------------------------
-- 1. 放宽 12.4.2.1 钉死的三个约束（**放宽方向，绝不收紧**）
-- ---------------------------------------------------------------------------
--
--   12.4.2.1（V351）在费用池与条目上钉了三个**收紧**的 CHECK，把「已分摊」
--   这个状态**写不出来**（本模块当时未交付）：
--
--     finance_cost_collection_pool.allocation_available   CHECK (= false)
--     finance_cost_collection_entry.allocated_amount       CHECK (= 0)
--     finance_cost_collection_entry.allocation_status       CHECK (= 'UNALLOCATED')
--
--   V351 第七节原话：「本模块的 `allocation_available` / `allocated_amount` /
--   `allocation_status` 由它在自己的迁移里放宽」⇒ 那正是本迁移的活。
--
--   ⚠️ **只放宽、不收紧**（这是跨车道纪律，不是风格问题）：
--   12.4.2.1 的 INSERT **不写**这三列（走默认值 false / 0 / 'UNALLOCATED'）。
--   若本迁移把 CHECK 换成别的**更紧**的值，12.4.2.1 自己的 INSERT 立刻违约，
--   它的集成测试当场红 —— 而本车道的 L1 收窄到本模块，**看不到**那个红，
--   红的是批次末的 L2。这正是「跨车道互相破坏」的经典形状。
--   ⇒ 下方重加的 CHECK 全部满足：默认取值仍然合法。
--
--   ⚠️ **语义变更必须说清楚**（列名 `allocation_available` 是上游起的，
--   这里把它的含义从「本模块不做分摊 ⇒ 恒 false」改成
--   「**该池已完成分摊**」，并在第五节的触发器里强制它与分摊批次同生共死）：
--
--   · pool.allocation_available ：false = 未分摊 / true = 已分摊
--   · entry.allocated_amount    ：该条目已被分摊掉的金额（分摊是**拆分**，
--                                  不是缩水 ⇒ 池被完整分摊时该条目 100% 摊完）
--   · entry.allocation_status   ：UNALLOCATED / ALLOCATED
--
--   `allocation_owner_module` **一个字都不改** —— 归属方恒为 '12.4.2.2'，就是本模块。
--
--   用**动态 SQL 按列删 CHECK**，不写死约束名：PostgreSQL 对匿名列级 CHECK
--   的默认命名是 `<表>_<列>_check`，但那是实现细节；按列反查约束名才是稳的。
--
-- ---------------------------------------------------------------------------
-- 2. 四张表：规则 → 分摊对象 → 分摊批次 → 分摊结果（明细）
-- ---------------------------------------------------------------------------
--
--   finance_cost_allocation_rule    分摊规则（维度 / 基数 / 生效区间 / 顺序 / 舍入差处理）
--   finance_cost_allocation_target  分摊对象（**真实上游事实的快照** + 它的四种基数）
--   finance_cost_allocation_batch   分摊批次（一次分摊：被分摊总额 / 分摊总额 / 舍入差）
--   finance_cost_allocation_result  分摊结果（= 分摊明细：每个对象摊到多少 / 怎么摊的）
--
--   为什么「分摊对象」要单独一张表、而不是把对象塞进结果行：
--   蓝图输出是「分摊结果（保留舍入差额到指定对象）+ 分摊明细」，而
--   **基数是对象的属性**（数量 / 金额 / 重量 / 体积）。对象先存在、
--   结果后产生，两者不是一回事：一次池可能被分摊、撤销、再分摊，
--   每次的对象集合可以不同 ⇒ 对象与结果必须分开两张表。
--
--   为什么对象表要**快照**真实上游事实：
--   * 「历史分摊不能被新规则追溯改写」⇒ 当时用的基数必须留在库里；
--   * 上游随时可能改包装重量、改销量 —— 事后回看「当时按什么摊的」必须答得上来。
--
-- ---------------------------------------------------------------------------
-- 3. 维度与基数：蓝图点名的五 + 五，一个都不少
-- ---------------------------------------------------------------------------
--
--   维度（分摊到谁身上）：SKU / SPU / SHOP / ORDER / CATEGORY
--   基数（按什么摊）：    QUANTITY / AMOUNT / WEIGHT / VOLUME / EQUAL
--
--   「EQUAL（等额）」不是偷懒的兜底：它是一个**真实的分摊口径**
--   （把一笔费用均摊给 N 个对象），财务上确实要用（例如内部办公费均摊给各店铺）。
--
--   🔴 **基数与维度的组合不是自由的，但也不在 DB 层钉死配对**：
--   哪个维度上哪个基数取得到，取决于**上游有没有那份事实**
--   （见第四节）—— 那是数据问题，不是词汇问题。
--   ⇒ 本模块的纪律是：**基数取不到的那一行明确记成「基数不可得」**，
--     它不参与分母、分摊额为 0（0 的含义是「没分到」而不是「它不用花钱」），
--     且**全部取不到时拒绝分摊**（而不是摊出一张全 0 的单）。
--     这条纪律与 catalog 5.2.1.3（V224）逐字同构。
--
-- ---------------------------------------------------------------------------
-- 4. 分摊对象从哪来：**真实上游事实**，绝不造数
-- ---------------------------------------------------------------------------
--
--   | 维度       | 真实来源                                              | 可得基数            |
--   |------------|-------------------------------------------------------|---------------------|
--   | ORDER      | trade 平台订单（9.1.2.1）                             | AMOUNT / EQUAL      |
--   | SHOP       | trade 平台订单按店铺汇总 + channel 店铺主数据（2.3.1.1）| AMOUNT / EQUAL      |
--   | SKU        | trade 销售订单行（9.1.1.1）+ catalog SKU（3.1.2.2）    | QUANTITY / AMOUNT / WEIGHT / VOLUME / EQUAL |
--   | SPU        | 上述 SKU 按 catalog 的 spuId 汇总                      | 同 SKU              |
--   | CATEGORY   | 上述 SKU 按 catalog 的类目汇总                         | 同 SKU              |
--
--   · **绝不跨服务 SQL**：对象来自上游**授权 HTTP 只读端点**（AGENTS.md 模块边界纪律）。
--   · **绝不跨币种相加**：采集时按池的币种过滤，取不到的订单被跳过并**如实计数**。
--   · **基数取不到的 SKU**（catalog 没维护包装重量 / 体积）**不编造**：
--     该对象的 weight / volume 留空，采集结果里写明「未维护」。
--
-- ---------------------------------------------------------------------------
-- 5. 舍入差：落在一个**指定对象**上，且必须留痕
-- ---------------------------------------------------------------------------
--
--   数学上 Σ(总额 × 基数 ÷ Σ基数) = 总额，但逐行四舍五入到 4 位小数后会差几分。
--   ⇒ 服务层用**向下取整 + 把余下的分补回去**（catalog V224 同一算法）：
--       · 每个参与对象先**向下**取整到 4 位；
--       · 余下的零头（< 参与对象数 × 0.0001）按**舍入差策略**补回去：
--           LARGEST_REMAINDER 余数最大者优先 / SMALLEST_REMAINDER 余数最小者优先 /
--           FIRST_TARGET 第一个 / LAST_TARGET 最后一个 / SPECIFIED 规则里指定的那个。
--       · **零头绝不消失**：每一笔补回去的 0.0001 都记在结果的
--         `rounding_adjustment` 上，批次上记 `rounding_difference` 与落点对象。
--   ⇒ **Σ结果 = 被分摊总额**，永远成立（DB 层有延迟约束触发器兜底，见第七节）。
--
--   ⚠️ **为什么不用「四舍五入后把差额补给某一个对象」**：
--   那样差额可能为负，落到对象上会让它的分摊额**变成负数** ——
--   负成本比差几分钱更糟。向下取整 + 补差**保证每一行都 ≥ 0**。
--
-- ---------------------------------------------------------------------------
-- 6. 引用校验用**触发器**，不建 FOREIGN KEY
-- ---------------------------------------------------------------------------
--
--   仓库先例（catalog V224）：给既有表加 FOREIGN KEY 会打破同服务既有测试的
--   `TRUNCATE`（级联删不掉就报错）⇒ 本迁移**一个 FOREIGN KEY 都不建**，
--   跨表引用一律用触发器核对。触发器只在 INSERT/UPDATE 时跑，
--   `TRUNCATE` 不触发 ⇒ 不影响别的车道的既有测试。
--
-- ---------------------------------------------------------------------------
-- 7. 多租户（RLS isolation）+ 授权
-- ---------------------------------------------------------------------------
--
--   四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation
--   + GRANT finance_app / starcon_app，与 V351 同纪律：
--   运行角色必须 NOBYPASSRLS，否则隔离断言全部变成空断言。
--
-- ===========================================================================

-- ===========================================================================
-- 1. 放宽 12.4.2.1 钉死的三个约束
-- ===========================================================================

-- 费用池：allocation_available 由「恒 false」放宽为「可 true」。
DO $$
DECLARE
  c record;
BEGIN
  FOR c IN
    SELECT conname
      FROM pg_constraint
     WHERE conrelid = 'finance_cost_collection_pool'::regclass
       AND contype = 'c'
       AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                            WHERE attrelid = 'finance_cost_collection_pool'::regclass
                              AND attname = 'allocation_available')]::smallint[]
  LOOP
    EXECUTE format('alter table finance_cost_collection_pool drop constraint %I', c.conname);
  END LOOP;
END $$;

-- 条目：allocated_amount 由「恒 0」放宽为「≥ 0 的真实分摊金额」。
DO $$
DECLARE
  c record;
BEGIN
  FOR c IN
    SELECT conname
      FROM pg_constraint
     WHERE conrelid = 'finance_cost_collection_entry'::regclass
       AND contype = 'c'
       AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                            WHERE attrelid = 'finance_cost_collection_entry'::regclass
                              AND attname = 'allocated_amount')]::smallint[]
  LOOP
    EXECUTE format('alter table finance_cost_collection_entry drop constraint %I', c.conname);
  END LOOP;
END $$;

-- 条目：allocation_status 由「恒 UNALLOCATED」放宽为「词表受限」。
DO $$
DECLARE
  c record;
BEGIN
  FOR c IN
    SELECT conname
      FROM pg_constraint
     WHERE conrelid = 'finance_cost_collection_entry'::regclass
       AND contype = 'c'
       AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                            WHERE attrelid = 'finance_cost_collection_entry'::regclass
                              AND attname = 'allocation_status')]::smallint[]
  LOOP
    EXECUTE format('alter table finance_cost_collection_entry drop constraint %I', c.conname);
  END LOOP;
END $$;

-- 重加**放宽后**的 CHECK：默认取值（false / 0 / 'UNALLOCATED'）仍然合法 ⇒
-- 12.4.2.1 的老 INSERT 一行都不受影响。
ALTER TABLE finance_cost_collection_entry
  ADD CONSTRAINT finance_cost_collection_entry_allocated_amount_check
  CHECK (allocated_amount >= 0);

ALTER TABLE finance_cost_collection_entry
  ADD CONSTRAINT finance_cost_collection_entry_allocation_status_check
  CHECK (allocation_status IN ('UNALLOCATED', 'ALLOCATED'));

-- 费用池的 allocation_available 重加**非平凡**的 CHECK：
--   已分摊（true）⇒ 该池必须至少有一条分摊批次（由第五节触发器保证同生共死，
--   这里只保证「不会凭空 true」）。
--   ⚠️ 默认 false 仍然合法 ⇒ 12.4.2.1 的老 INSERT 不受影响。
CREATE OR REPLACE FUNCTION finance_cost_collection_pool_allocation_guard() RETURNS trigger AS $$
BEGIN
  IF NEW.allocation_available AND NOT EXISTS (
        SELECT 1 FROM finance_cost_allocation_batch b
         WHERE b.tenant_id = NEW.tenant_id AND b.pool_id = NEW.id AND b.status = 'ALLOCATED') THEN
    RAISE EXCEPTION '费用池 % 不能凭空标成已分摊：必须先有分摊批次', NEW.pool_key
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_collection_pool_allocation_guard
  BEFORE INSERT OR UPDATE ON finance_cost_collection_pool
  FOR EACH ROW EXECUTE FUNCTION finance_cost_collection_pool_allocation_guard();

-- ===========================================================================
-- 2. 分摊规则
-- ===========================================================================
--   蓝图点名的五件事，全部落在这一张表上：
--     分摊维度（target_dimension）· 分摊基数（allocation_basis）·
--     生效区间（effective_from / effective_to）·
--     分摊顺序（priority，数字小 = 先）·
--     舍入差处理（rounding_policy + 舍入差落点）。
CREATE TABLE finance_cost_allocation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_code varchar(64) NOT NULL,
  rule_name varchar(160) NOT NULL,

  -- 分摊到谁身上：SKU / SPU / SHOP / ORDER / CATEGORY。
  target_dimension varchar(16) NOT NULL,
  -- 按什么摊：QUANTITY / AMOUNT / WEIGHT / VOLUME / EQUAL。
  allocation_basis varchar(16) NOT NULL,

  -- 适用哪个费用池：池标识；'*' = 适用于本租户的全部池。
  pool_key varchar(64) NOT NULL DEFAULT '*',

  -- 分摊顺序：多规则命中时**数字小的先**（= 优先级高的先用）。
  priority integer NOT NULL DEFAULT 100,

  -- 舍入差处理：零头补给谁。
  rounding_policy varchar(24) NOT NULL DEFAULT 'LARGEST_REMAINDER',
  -- policy = SPECIFIED 时**必填**：零头落到哪个对象。
  rounding_target_type varchar(16),
  rounding_target_code varchar(200),

  -- 生效区间：池的归属期间必须落在区间内才命中。
  effective_from date NOT NULL,
  effective_to date,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code),

  CHECK(btrim(rule_code) <> ''),
  CHECK(btrim(rule_name) <> ''),
  CHECK(target_dimension IN ('SKU','SPU','SHOP','ORDER','CATEGORY')),
  CHECK(allocation_basis IN ('QUANTITY','AMOUNT','WEIGHT','VOLUME','EQUAL')),
  CHECK(btrim(pool_key) <> ''),
  CHECK(priority >= 0),
  CHECK(rounding_policy IN ('LARGEST_REMAINDER','SMALLEST_REMAINDER','FIRST_TARGET','LAST_TARGET','SPECIFIED')),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  CHECK(status IN ('ACTIVE','INACTIVE')),
  -- 舍入差落点：SPECIFIED 时必须点名到对象；其它策略下不允许乱填。
  CHECK((rounding_policy = 'SPECIFIED'
         AND rounding_target_type IS NOT NULL
         AND btrim(coalesce(rounding_target_code,'')) <> '')
       OR (rounding_policy <> 'SPECIFIED'
           AND rounding_target_type IS NULL
           AND rounding_target_code IS NULL))
);

CREATE INDEX finance_cost_allocation_rule_by_pool
  ON finance_cost_allocation_rule(tenant_id, pool_key, priority);
CREATE INDEX finance_cost_allocation_rule_by_status
  ON finance_cost_allocation_rule(tenant_id, status, effective_from);

-- ===========================================================================
-- 3. 分摊对象（真实上游事实的快照 + 它的四种基数）
-- ===========================================================================
--   一行 = 「某个池上，某个维度的某个对象，它的基数是多少」。
--
--   🔴 **对象不是本模块造出来的**：每行都带 `basis_source`
--   （这份基数是从哪个上游的哪个事实来的）与 `basis_note`
--   （取不到时写明「未维护」而不是留一个安静的 0）。
CREATE TABLE finance_cost_allocation_target (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  pool_id uuid NOT NULL,

  target_dimension varchar(16) NOT NULL,
  target_type varchar(24) NOT NULL,
  target_code varchar(200) NOT NULL,
  target_name varchar(200) NOT NULL DEFAULT '',

  -- 四种基数。哪个维度上哪个取得到，取决于上游有没有那份事实（见文件头第四节）。
  basis_quantity numeric(19,6),
  basis_amount numeric(19,4),
  basis_weight numeric(19,6),
  basis_volume numeric(19,6),

  -- 这份基数来自哪个上游事实（CATALOG_SKU / TRADE_SALES_ORDER_LINE /
  -- TRADE_PLATFORM_ORDER / CHANNEL_SHOP）。
  basis_source varchar(32) NOT NULL,
  -- 基数取不到时写明原因（「包装重量未维护」之类），**留空等于「没说」**。
  basis_note text NOT NULL DEFAULT '',

  -- 目标事实的发生日（落在池的归属期间内）。
  target_date date NOT NULL,

  collected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等：同一个池上同一个对象只留一行（重复采集覆盖，不重复计）。
  UNIQUE(tenant_id, pool_id, target_dimension, target_code),

  CHECK(target_dimension IN ('SKU','SPU','SHOP','ORDER','CATEGORY')),
  CHECK(target_type IN ('SKU','SPU','SHOP','ORDER','CATEGORY')),
  CHECK(btrim(target_code) <> ''),
  CHECK(basis_quantity IS NULL OR basis_quantity >= 0),
  CHECK(basis_amount IS NULL OR basis_amount >= 0),
  CHECK(basis_weight IS NULL OR basis_weight >= 0),
  CHECK(basis_volume IS NULL OR basis_volume >= 0),
  CHECK(btrim(basis_source) <> '')
);

CREATE INDEX finance_cost_allocation_target_by_pool
  ON finance_cost_allocation_target(tenant_id, pool_id, target_dimension);

-- ===========================================================================
-- 4. 分摊批次（一次分摊）
-- ===========================================================================
--   ★ `allocated_total = source_total` 这条 CHECK 就是蓝图那句
--     「分摊结果之和必须等于被分摊总额」的**数据层落点**。
CREATE TABLE finance_cost_allocation_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  pool_id uuid NOT NULL,
  rule_id uuid NOT NULL,

  currency char(3) NOT NULL,
  -- 被分摊总额（= 费用池合计，由条目派生）。
  source_total numeric(19,4) NOT NULL,
  -- 分摊总额（= Σ分摊结果）。🔴 必须与 source_total **逐分相等**。
  allocated_total numeric(19,4) NOT NULL,
  -- 舍入差：向下取整后被补回去的零头合计（**不消失**）。
  rounding_difference numeric(19,4) NOT NULL DEFAULT 0,
  -- 零头主要落在谁身上（多对象分摊时是拿到最多零头的那个）。
  rounding_target_type varchar(16),
  rounding_target_code varchar(200),

  -- 参与分摊的对象数 / 基数不可得的对象数（**都要能被看见**）。
  target_count integer NOT NULL DEFAULT 0,
  basis_unavailable_count integer NOT NULL DEFAULT 0,

  status varchar(16) NOT NULL DEFAULT 'ALLOCATED',
  allocated_at timestamptz NOT NULL,
  reverted_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(source_total > 0),
  -- ★★★ 蓝图那句等式，钉死。
  CHECK(allocated_total = source_total),
  CHECK(rounding_difference >= 0),
  CHECK(target_count > 0),
  CHECK(basis_unavailable_count >= 0),
  CHECK(status IN ('ALLOCATED','REVERTED')),
  CHECK((status = 'ALLOCATED' AND reverted_at IS NULL)
        OR (status = 'REVERTED' AND reverted_at IS NOT NULL))
);

-- 一个池**同时**只能有一次分摊（撤销后可重新分摊，历史批次留档）。
CREATE UNIQUE INDEX finance_cost_allocation_batch_one_active_per_pool
  ON finance_cost_allocation_batch(tenant_id, pool_id) WHERE status = 'ALLOCATED';
CREATE INDEX finance_cost_allocation_batch_by_pool
  ON finance_cost_allocation_batch(tenant_id, pool_id, allocated_at DESC);

-- ===========================================================================
-- 5. 分摊结果（= 分摊明细）
-- ===========================================================================
--   一行 = 「这一次分摊里，某个对象摊到了多少、怎么摊的」。
--   🔴 基数不可得的对象**也留一行**（分摊额 0 + basis_available = false）——
--     它「没分到」这件事必须看得见，而不是安静地消失。
CREATE TABLE finance_cost_allocation_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  batch_id uuid NOT NULL,

  target_dimension varchar(16) NOT NULL,
  target_code varchar(200) NOT NULL,
  target_name varchar(200) NOT NULL DEFAULT '',

  -- 实际用的基数（EQUAL 口径下恒为 1）。
  basis_value numeric(19,6) NOT NULL,
  basis_available boolean NOT NULL,
  -- 占比（展示用；真正的计算用的是 exact_amount）。
  share_ratio numeric(19,10) NOT NULL,
  -- 未舍入的理论分摊额（保留 10 位，看得见「舍入前是多少」）。
  exact_amount numeric(19,10) NOT NULL,
  -- 最终分摊额（4 位，与金额列精度一致）。
  allocated_amount numeric(19,4) NOT NULL,
  -- 舍入差调整（这一行拿到的零头）。
  rounding_adjustment numeric(19,4) NOT NULL DEFAULT 0,
  -- 是不是规则指定的零头落点。
  is_rounding_target boolean NOT NULL DEFAULT false,
  basis_note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, batch_id, target_code),

  CHECK(target_dimension IN ('SKU','SPU','SHOP','ORDER','CATEGORY')),
  CHECK(btrim(target_code) <> ''),
  CHECK(basis_value >= 0),
  CHECK(exact_amount >= 0),
  -- 🔴 分摊额永不为负（向下取整 + 补差保证，见文件头第五节）。
  CHECK(allocated_amount >= 0),
  -- 基数不可得 ⇒ 分摊额必须是 0，且不能是零头落点。
  CHECK(basis_available OR (allocated_amount = 0 AND rounding_adjustment = 0 AND NOT is_rounding_target)),
  -- 基数可得 ⇒ 分摊额必须等于「**向下取整** + 零头调整」。
  -- ⚠️ 这里必须用 trunc（截断）而不是 round（四舍五入）：
  --   算法是「先向下取整、再把零头补回去」，用 round 复核会与实际写法对不上。
  --   exact_amount ≥ 0 ⇒ trunc 与 floor 等价。
  CHECK(NOT basis_available OR allocated_amount = trunc(exact_amount, 4) + rounding_adjustment)
);

CREATE INDEX finance_cost_allocation_result_by_batch
  ON finance_cost_allocation_result(tenant_id, batch_id, target_code);

-- ===========================================================================
-- 6. 触发器：把「蓝图的话」变成数据层做不到的事
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 规则守卫：生效区间与舍入差落点
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_cost_allocation_rule_guard() RETURNS trigger AS $$
BEGIN
  IF NEW.effective_to IS NOT NULL AND NEW.effective_to < NEW.effective_from THEN
    RAISE EXCEPTION '分摊规则 % 的生效区间是反的（起 % > 止 %）', NEW.rule_code,
      NEW.effective_from, NEW.effective_to USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.rounding_policy = 'SPECIFIED' THEN
    IF NEW.rounding_target_type IS NULL OR btrim(coalesce(NEW.rounding_target_code,'')) = '' THEN
      RAISE EXCEPTION '分摊规则 % 指定了「零头给指定对象」，就必须点名是哪个对象',
        NEW.rule_code USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.rounding_target_type <> NEW.target_dimension THEN
      RAISE EXCEPTION '分摊规则 % 的零头落点类型 % 与分摊维度 % 不一致', NEW.rule_code,
        NEW.rounding_target_type, NEW.target_dimension USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_allocation_rule_guard
  BEFORE INSERT OR UPDATE ON finance_cost_allocation_rule
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_rule_guard();

-- ---------------------------------------------------------------------------
-- (b) 分摊对象守卫：池必须存在且属于本租户；维度必须与规则口径一致
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_cost_allocation_target_guard() RETURNS trigger AS $$
DECLARE
  p finance_cost_collection_pool%ROWTYPE;
BEGIN
  SELECT * INTO p FROM finance_cost_collection_pool x
   WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.pool_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '费用池不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF NEW.target_type <> NEW.target_dimension THEN
    RAISE EXCEPTION '分摊对象的类型 % 与维度 % 必须一致', NEW.target_type, NEW.target_dimension
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_allocation_target_guard
  BEFORE INSERT ON finance_cost_allocation_target
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_target_guard();

-- ---------------------------------------------------------------------------
-- (c) 分摊结果守卫：批次必须存在且属于本租户；对象必须在这个池的对象集合里
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_cost_allocation_result_guard() RETURNS trigger AS $$
DECLARE
  b finance_cost_allocation_batch%ROWTYPE;
BEGIN
  SELECT * INTO b FROM finance_cost_allocation_batch x
   WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.batch_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '分摊批次不存在或不属于本租户' USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF b.status <> 'ALLOCATED' THEN
    RAISE EXCEPTION '分摊批次已撤销，不能再写分摊结果' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
        SELECT 1 FROM finance_cost_allocation_target t
         WHERE t.tenant_id = NEW.tenant_id
           AND t.pool_id = b.pool_id
           AND t.target_dimension = NEW.target_dimension
           AND t.target_code = NEW.target_code) THEN
    RAISE EXCEPTION '分摊对象 % 不是这个池的分摊对象（不能凭空摊给一个不在集合里的对象）',
      NEW.target_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_allocation_result_guard
  BEFORE INSERT ON finance_cost_allocation_result
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_result_guard();

-- ---------------------------------------------------------------------------
-- (d) 🔴 等式兜底：**延迟约束触发器**，事务提交时校验
-- ---------------------------------------------------------------------------
--   「分摊结果之和 = 被分摊总额」是本模块唯一必须恒成立的等式。
--   放在触发器里而不是只靠应用层：应用层漏一次（或被人直接改库），
--   合计就永远错了 —— 而这个数正是下游 13 利润拿去做 SKU 利润的输入。
--
--   ⚠️ **必须是延迟约束触发器**：结果是逐行 INSERT 的，
--   校验 Σ 时前面的行还没写完 ⇒ 必须在**提交时**统一校验。
--
--   ⚠️ 触发器刻意**不是** SECURITY DEFINER：以调用方身份执行 ⇒ RLS 生效 ⇒
--   跨租户串扰不可能发生（与 V351 的守卫同一纪律）。
CREATE OR REPLACE FUNCTION finance_cost_allocation_check_total() RETURNS trigger AS $$
DECLARE
  tid uuid;
  bid uuid;
  b finance_cost_allocation_batch%ROWTYPE;
  total numeric(19,4);
BEGIN
  tid := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
  bid := CASE WHEN TG_OP = 'DELETE' THEN OLD.batch_id ELSE NEW.batch_id END;

  SELECT * INTO b FROM finance_cost_allocation_batch x
   WHERE x.tenant_id = tid AND x.id = bid;
  -- 批次不存在（被删了）或已撤销 ⇒ 不校验（撤销后的历史结果不再参与等式）。
  IF NOT FOUND OR b.status <> 'ALLOCATED' THEN
    RETURN NULL;
  END IF;

  SELECT COALESCE(sum(r.allocated_amount), 0) INTO total
    FROM finance_cost_allocation_result r
   WHERE r.tenant_id = tid AND r.batch_id = bid;

  IF total <> b.source_total THEN
    RAISE EXCEPTION
      '分摊结果之和 % ≠ 被分摊总额 % —— 分摊结果之和必须等于被分摊总额，否则利润报表总额对不上',
      total, b.source_total USING ERRCODE = 'check_violation';
  END IF;
  IF b.allocated_total <> b.source_total THEN
    RAISE EXCEPTION
      '分摊批次 % 的分摊总额 % ≠ 被分摊总额 %', b.rule_id, b.allocated_total, b.source_total
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER finance_cost_allocation_result_total
  AFTER INSERT OR UPDATE OR DELETE ON finance_cost_allocation_result
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_check_total();

-- ---------------------------------------------------------------------------
-- (e) 上游分摊状态与分摊批次**同生共死**
-- ---------------------------------------------------------------------------
--   费用池的 allocation_available 与条目的 allocated_amount / allocation_status
--   是 12.4.2.1 留给本模块的三列（见文件头第一节）。
--   ⇒ 它们**不能**由应用层随手改（改一次就与分摊事实对不上），
--     一律由本触发器随分摊批次同步：
--       批次落库（ALLOCATED）⇒ 池标成已分摊、该池全部条目标成已分摊；
--       批次撤销（REVERTED）⇒ 池与条目回到未分摊。
CREATE OR REPLACE FUNCTION finance_cost_allocation_sync_upstream() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status = 'ALLOCATED' THEN
      UPDATE finance_cost_collection_pool p
         SET allocation_available = true, updated_at = now()
       WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.pool_id;

      UPDATE finance_cost_collection_entry e
         SET allocated_amount = e.amount,
             allocation_status = 'ALLOCATED',
             updated_at = now()
       WHERE e.tenant_id = NEW.tenant_id AND e.pool_id = NEW.pool_id;
    END IF;
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'ALLOCATED' AND NEW.status = 'REVERTED' THEN
      UPDATE finance_cost_collection_pool p
         SET allocation_available = false, updated_at = now()
       WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.pool_id;

      UPDATE finance_cost_collection_entry e
         SET allocated_amount = 0,
             allocation_status = 'UNALLOCATED',
             updated_at = now()
       WHERE e.tenant_id = NEW.tenant_id AND e.pool_id = NEW.pool_id;
    END IF;
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_cost_allocation_sync_upstream
  AFTER INSERT OR UPDATE ON finance_cost_allocation_batch
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_sync_upstream();

-- ===========================================================================
-- 7. 多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_cost_allocation_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_allocation_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_allocation_rule
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_allocation_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_allocation_target FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_allocation_target
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_allocation_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_allocation_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_allocation_batch
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_cost_allocation_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_cost_allocation_result FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_cost_allocation_result
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_rule TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_target TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_target TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_batch TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_batch TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_result TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_cost_allocation_result TO starcon_app;

-- ===========================================================================
-- 8. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13 利润（未交付，在别的服务）**：本模块产出 `finance_cost_allocation_result`
--     ——「哪个对象摊到了多少钱」—— 这正是 SKU 利润需要的**费用侧输入**。
--     本模块**不产出任何利润数字**。
--   * **12.5.2.1 凭证（未交付）**：分摊批次还**没有**生成会计凭证 ——
--     蓝图没写这一格，本模块不越界做它。
--   * **13.4.1.1 成本差异（未交付）**：本模块的分摊结果是它的**上游事实**之一。
