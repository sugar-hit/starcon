-- 6.4.1.2 BOM 关联：加工单引用 BOM 版本并**冻结展开结果**，后续 BOM 修改不影响已下达的加工单。
--
-- 蓝图 06-purchase-and-supply.md 6.4.1.2 原文：
--   职责：加工单引用 BOM 版本并冻结展开结果，后续 BOM 修改不影响已下达的加工单。
--   输入：BOM 版本选择；冻结动作。
--   输出：冻结的组件清单快照。
--   关系：上游 = 3.5.2.2 展开；下游 = 6.4.1.1。
--         **冻结与订单冻结组件同理**：加工周期长，BOM 中途改配会导致实物与系统不符。
--
-- 归属：**trade 服务**（与 6.4.1.1 同车道同库），前端路由 /purchase/processing，
-- 后端命名空间 /trade/processing —— 与 6.4.1.1 同一纪律。
--
-- ===========================================================================
-- 一、本模块的本质是「快照」不是「引用」（蓝图那句加粗是验收项）
-- ===========================================================================
--
-- **反面做法（明确拒绝）**：在加工单上存一个 bom_version_id，然后每次领料时
-- 去 join 现在的 bom_component。那样 BOM 中途改配，已下达的加工单领料量会跟着变，
-- 实物与系统不符 —— 正是蓝图点名要避免的那件事。
--
-- 本文件落的是**拍平的一份快照**：下达那一刻把展开结果的每一行连同它的推导依据
-- （单位用量、损耗率、成品 SKU、组件 SKU 及其顺序、所在层与路径）整份写进本服务自己的库。
-- 读快照时**不 join catalog、不 join 当前 BOM**，所以 catalog 侧后来怎么改都与它无关。
--
-- ===========================================================================
-- 二、与已交付的 6.4.1.1（V97）是什么关系 —— 逐条说清，避免有人以为重复建设
-- ===========================================================================
--
-- V97 已经在**创建加工单**时按 catalog 的**单层 R7** 冻结了三元组
-- （unit_quantity / scrap_rate / required_quantity）到 processing_order_component。
-- 本模块冻结的是**另一件东西**，两者的形状、层级与用途都不同：
--
--   ┌────────────────────┬──────────────────────────┬────────────────────────────┐
--   │                    │ 6.4.1.1（V97 已交付）     │ 6.4.1.2（本文件）           │
--   ├────────────────────┼──────────────────────────┼────────────────────────────┤
--   │ 上游模块            │ 3.5.2.1 BOM（V25）        │ 3.5.2.2 展开（V26）         │
--   │ 冻结的形状          │ 单层：成品版本直接组件    │ 多层：整棵展开树            │
--   │ 是否含子装配下级    │ 否（只有一层）            │ 是（子 BOM 继续往下展开）  │
--   │ 是否含可用/在途/缺口│ 否                       │ 是（V26 的 E2 三列）        │
--   │ 是否有 depth / path │ 否（平铺一张领料清单）    │ 是（展开树的层与物化路径）  │
--   │ 回答的问题          │ 「这张单要领哪些料」      │ 「这批料当时是按哪棵树算的」│
--   └────────────────────┴──────────────────────────┴────────────────────────────┘
--
-- 蓝图把上游写成 3.5.2.2 而不是 3.5.2.1，正说明这一层不是重复：**展开树才是冻结对象**。
-- 举例：一个成品用半成品 SA，SA 自己又由面料 + 拉链组成。单层 R7 只告诉你「要 3 个 SA」，
-- 答不出「这 3 个 SA 的面料和拉链按哪一版、什么损耗率算的」—— 而加工周期长达数周，
-- SA 在这期间改配就会让实物与系统不符。**多层展开快照正是回答这个问题的。**
--
-- **因此本文件不替换、不改写 processing_order_component**：
--   * 加工单的组件**领用记录**仍由 6.4.1.1 维护（它要跟着 issued_quantity 推进进度）；
--   * 本文件冻结的是**这条领用量当初是由哪棵树、哪一版、什么损耗率算出来的凭据**。
-- 两者是「凭据」与「进度」的关系，不是同一张表的两个版本。
--
-- ===========================================================================
-- 三、逐条对齐已交付口径，不另立一套
-- ===========================================================================
--
-- **不重新实现 R1–R7**：展开由 catalog 3.5.2.2（V26）真算，本表只**原样落库**它的结果。
--   * **R6（损耗率是百分数不是比率）**：scrap_rate numeric(7,4) + CHECK 0<=x<=100，
--     与 V25 bom_component / V26 bom_explosion_line **完全同精度同上界**。
--     存比率（0.05）会让「填 5」被读成 500% 损耗且不报错，领料量直接翻 6 倍。
--   * **R7（需求量 = 单位用量 × 产量 × (1 + 损耗率/100)）**：required_quantity
--     用 numeric(19,6) 与来源同精度，**不取整**。本表不重算这个数，也不做取整 ——
--     取整是 3.5.2.2 与 06 采购建议的职责。
--   * **E1（逐层结转）**：深层的 required 是按「父行缺口」递推的，所以本表
--     **必须同时冻结 available / in_transit / gap** —— 只冻 required 就答不出
--     「这一层为什么是这个数」，而那正是「这批料当时为什么是这个数」的全部证据。
--   * **E4（深度截断必须显式）**：snapshot_status 与 truncated 都落库，
--     TRUNCATED 的快照必须能被读出来并显式告知「清单不完整」。
--     **静默截断是未完成** —— 冻结一份不完整的展开树却不告知，比不冻结更危险。
--   * **R4（子 BOM 按 as_of 时刻取生效版本）**：as_of 随快照一起冻结。
--     事后重算会得到另一个答案（子 BOM 可能已经换了版本）。
--
-- ===========================================================================
-- 四、跨服务边界：本表对 catalog 只存逻辑 ID + 快照值，不建外键
-- ===========================================================================
--
-- AGENTS.md 模块边界纪律：BOM 与展开表都在 **catalog 服务的库**里。本文件
-- **不建跨库 FK、不建跨库视图、不跨库 JOIN**，只存 catalog 的逻辑 ID
-- （bom_id / bom_version_id / component_id / sub_bom_id …）与当时的快照值。
-- 与 V97 的 bom_id / bom_version_id 同一纪律（V97 第九节原文）。
--
-- ===========================================================================
-- 1. BOM 冻结快照（**本模块的事实表**）
-- ===========================================================================
-- 一行 = 一张加工单冻结下来的那次展开结果。
CREATE TABLE processing_bom_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 所属加工单。一张加工单**只有一份**冻结快照：重复冻结会让「这张单按哪棵树算的」
  -- 在历史里出现两个答案，而下游领料只有一个口径可用。
  order_id uuid NOT NULL,
  -- **来源展开运行**（catalog 3.5.2.2 V26 bom_explosion_run 的 id，逻辑 ID，不建跨库 FK）。
  -- 它是「这份快照是怎么算出来的」的上游凭据；快照本身不依赖它可读 —— 即使
  -- catalog 那边将来清理了运行记录，本快照仍然完整成立（这正是快照的意义）。
  explosion_run_id uuid NOT NULL,
  -- 被引用的 BOM 与版本（逻辑 ID + 版本号快照，跨服务不建外键）。
  bom_id uuid NOT NULL,
  bom_version_id uuid NOT NULL,
  bom_version_no varchar(32) NOT NULL CHECK(btrim(bom_version_no)<>''),
  -- **成品 SKU**：冻结的是「为这个成品算的」，成品变了这份快照就没有指涉对象。
  output_sku_id uuid NOT NULL,
  output_sku_code varchar(64) NOT NULL CHECK(btrim(output_sku_code)<>''),
  -- **产量**（规则 R7 的产量因子）。整棵树的 required 都由它驱动，冻结它才能复算。
  output_quantity numeric(19,6) NOT NULL CHECK(output_quantity > 0),
  -- **仓库过滤**（V26 的同名参数）：展开按哪个仓算的可用与在途。逻辑 ID，不建外键。
  -- 空 = 全仓合计。为空不是「没记」，而是「当时就是全仓合计」。
  warehouse_id uuid,
  -- **展开基准时刻 as_of**：子 BOM 取哪一版（规则 R4）。业务时间的一部分，
  -- 事后重算会得到另一个答案，所以必须冻结（与 V26 同一理由）。
  as_of timestamptz NOT NULL,
  -- 深度上限与实际到达深度（E4 判定）。
  max_depth int NOT NULL CHECK(max_depth >= 1),
  reached_depth int NOT NULL CHECK(reached_depth >= 1 AND reached_depth <= max_depth),
  -- 快照状态。TRUNCATED = 冻结的是一棵**被深度上限截断**的树，清单不完整，
  -- 页面上必须显红。COMPLETED = 清单完整。
  snapshot_status text NOT NULL CHECK(snapshot_status IN ('COMPLETED','TRUNCATED')),
  -- 行数与合计（写回的结果，不是实时 join 出来的 —— 冻结即定格）。
  line_count int NOT NULL CHECK(line_count >= 0),
  purchase_line_count int NOT NULL CHECK(purchase_line_count >= 0),
  total_required numeric(19,6) NOT NULL DEFAULT 0,
  total_gap numeric(19,6) NOT NULL DEFAULT 0,
  -- 谁在什么时候冻结的（冻结是不可逆动作，必须留痕）。
  frozen_by uuid NOT NULL,
  frozen_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **一张加工单一份快照**：这是本模块最关键的一条约束，也是**唯一**需要它成立的理由。
  -- 没有它，两次冻结会让「这张单当时按哪棵树算的」有两个答案，
  -- 而领料与回溯「这批料为什么是这个数」只能选一个。
  --
  -- **刻意不对 explosion_run_id 加 UNIQUE**：上游 catalog 每调一次展开就新建一条
  -- bom_explosion_run，所以同一个 run 被冻两次本来就不会发生；而一旦将来支持
  -- 「拿一次已算好的展开结果直接冻到另一张单」，这条约束就会把一个合法场景
  -- 挡下来，却换不来任何新的保证。倒排查询由下面的按 run 索引承担。
  UNIQUE(tenant_id,order_id),
  -- 到达深度与状态必须自洽（与 V26 bom_explosion_run 的同一判据）：
  -- 被截断的快照必须真的停在上限那一层。
  -- **反向不成立**：COMPLETED 时 reached_depth 完全可以等于 max_depth ——
  -- 那只是「这棵树天然就有这么深，且下面确实没有下级 BOM」，不是被截断。
  -- 只在这里加 TRUNCATED ⇒ 停在上限这一条，与 V26 的语义逐字一致，不多判。
  CONSTRAINT processing_bom_snapshot_truncated_needs_actor
    CHECK (snapshot_status<>'TRUNCATED' OR reached_depth = max_depth),
  -- 行数与实际写入的明细行数必须一致（冻了「N 行」就要真的有 N 行，
  -- 否则「这份清单有多少行」与实际不符）。
  CONSTRAINT processing_bom_snapshot_has_lines
    CHECK (line_count > 0),
  CONSTRAINT processing_bom_snapshot_purchase_lines_within_total
    CHECK (purchase_line_count <= line_count),
  -- 已冻结快照必须有冻结人与冻结时刻：冻结不可逆，没有留痕就无从查证。
  CONSTRAINT processing_bom_snapshot_frozen_needs_actor
    CHECK (frozen_by IS NOT NULL AND frozen_at IS NOT NULL),
  FOREIGN KEY(tenant_id,order_id) REFERENCES processing_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 2. 快照明细行（**冻结的组件清单** = 蓝图输出句）
-- ===========================================================================
--
-- 一行 = 展开树上的一层，在冻结那一刻被拍平成事实。
--
-- 与 V26 bom_explosion_line 的关系：形状逐列对齐（含 depth / path / unit_quantity /
-- scrap_rate / required_quantity / available / in_transit / gap 与子 BOM 判定四列），
-- **但它是本服务的事实，不是 catalog 记录的影子**：读它不 join catalog。
-- 差异只有两处，且都是必要的：
--   * parent_line_id 指向**本表**的行（V26 指本表内的父行），所以树形完全落在本库；
--   * 不存 component_id 的外键（跨库 FK 禁止），只存逻辑 ID 供回溯。
CREATE TABLE processing_bom_snapshot_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, snapshot_id uuid NOT NULL,
  -- 父行（展开树上一层）。空 = 成品版本的直接组件（第 1 层）。
  parent_line_id uuid,
  -- 层级与物化路径（'1'、'1.3'、'1.3.2'）：稳定排序 + 出错时能指名道姓说哪条路径上的哪一行。
  depth int NOT NULL CHECK(depth >= 1),
  path varchar(1000) NOT NULL CHECK(btrim(path)<>''),
  -- **组件 SKU** + code/name 快照（catalog 改名后历史单据仍能显示当时的组件名）。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **来源组件行**（catalog bom_component 的 id，逻辑 ID，不建外键）：
  -- 回溯「这一行的用量与损耗率是从哪条组件行算出来的」。
  component_id uuid NOT NULL,
  -- **算出这一行的 BOM 版本**（子装配行上指的是子 BOM 的那个版本）：
  -- 逐行冻结「这一层的料是按哪一版算的」，是多层展开快照的核心价值 ——
  -- 单层快照答不出「3 个 SA 的面料按哪一版算的」。
  source_version_id uuid NOT NULL,
  source_version_no varchar(32) NOT NULL,
  source_bom_code varchar(64) NOT NULL,
  -- **单位用量**（R7 因子之一）：每 1 个成品需要这个组件多少。
  -- NUMERIC(19,6) 与 V25 bom_component / V26 bom_explosion_line 同精度：
  -- 「0.5 米面料 + 2 颗螺丝」这类按计量单位组合的组件，整数会逼运营写假数。
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity > 0),
  -- **损耗率**（V25 **R6**：百分数，5 = 5%），上界 100 —— 与来源列**完全同义**。
  -- 禁浮点：BigDecimal + NUMERIC(7,4)，服务层全程 BigDecimal。
  scrap_rate numeric(7,4) NOT NULL
    CHECK(scrap_rate >= 0 AND scrap_rate <= 100),
  -- **需求量**：R7 的逐层递推结果（含损耗、含逐层结转），6 位小数、**不取整**。
  required_quantity numeric(19,6) NOT NULL CHECK(required_quantity >= 0),
  -- 可用与在途来自 warehouse 的授权端点（**不建外键**）。它们必须被冻结是因为
  -- E1 的逐层结转：深层的 required 是按**父行缺口**递推的，不冻这三个数
  -- 就答不出「这一层为什么是这个数」。
  available numeric(19,6) NOT NULL DEFAULT 0 CHECK(available >= 0),
  in_transit numeric(19,6) NOT NULL DEFAULT 0 CHECK(in_transit >= 0),
  -- 缺口：max(0, required − available − in_transit)（V26 规则 E2，下限 0）。
  gap numeric(19,6) NOT NULL DEFAULT 0 CHECK(gap >= 0),
  surplus numeric(19,6) NOT NULL DEFAULT 0 CHECK(surplus >= 0),
  -- **子 BOM 判定结果**（V26 规则 E3 逐字段对应）：这一行的组件本身是不是某个 BOM 的成品，
  -- 它当时命中了哪个子 BOM 的哪一版。
  is_sub_assembly boolean NOT NULL DEFAULT false,
  sub_bom_id uuid,
  sub_bom_code varchar(64),
  sub_version_id uuid,
  sub_version_no varchar(32),
  ambiguous_sub_bom boolean NOT NULL DEFAULT false,
  -- 实际是否向下展开了 / 是否因可用+在途已覆盖而不再展开 / 是否命中深度上限（E4）。
  expanded boolean NOT NULL DEFAULT false,
  covered boolean NOT NULL DEFAULT false,
  truncated boolean NOT NULL DEFAULT false,
  -- 组件角色与备注：写进快照时一并冻结，事后回溯时能看到当时的说明。
  role text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一份快照里同一个物化路径只能有一行：两行时「这一层的这个组件要多少」有两个答案，
  -- 而递归会把它算两遍。
  UNIQUE(tenant_id,snapshot_id,path),
  FOREIGN KEY(tenant_id,snapshot_id) REFERENCES processing_bom_snapshot(tenant_id,id) ON DELETE CASCADE,
  -- 父行必须是**同一份快照**里的行：跨快照指父子会让两棵不同的树接在一起，
  -- 展开树的层与路径随即失去意义（这是本表唯一一条自引用外键，用复合键保证同租户同快照）。
  FOREIGN KEY(tenant_id,parent_line_id) REFERENCES processing_bom_snapshot_line(tenant_id,id) ON DELETE CASCADE,
  -- 层级与父行必须自洽：第 1 层没有父行，非第 1 层必须有父行，且父层级恰比自己小 1。
  -- 少了这一条，展开树可以在数据里断开，而页面只会照着断掉的树渲染。
  CONSTRAINT processing_bom_snapshot_line_root_has_no_parent
    CHECK ((depth = 1) = (parent_line_id IS NULL))
);

-- ===========================================================================
-- 3. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 加工单详情页 / 冻结预览：按加工单读它唯一的那份快照。
-- UNIQUE(tenant_id,order_id) 已覆盖这一形状，**不重复建**。
-- 「当前正在生产的单按哪一版 BOM」：按 BOM 版本倒排（改 BOM 前先看谁在用）。
CREATE INDEX processing_bom_snapshot_by_version
  ON processing_bom_snapshot(tenant_id, bom_version_id, frozen_at DESC, id);
-- 按成品 SKU 反查：同一成品在不同加工单上分别用了哪一版 BOM。
CREATE INDEX processing_bom_snapshot_by_output_sku
  ON processing_bom_snapshot(tenant_id, output_sku_id, frozen_at DESC, id);
-- 来源展开运行倒排（回溯「这次展开被谁冻结了」）。
CREATE INDEX processing_bom_snapshot_by_run
  ON processing_bom_snapshot(tenant_id, explosion_run_id, id);
-- **被截断的快照**（部分索引）：清单不完整的冻结必须能被单独筛出来告警，
-- 因为「冻结了一份不完整的树」在业务上等价于「这批料有一部分没人算过」。
CREATE INDEX processing_bom_snapshot_truncated
  ON processing_bom_snapshot(tenant_id, frozen_at DESC, id)
  WHERE snapshot_status = 'TRUNCATED';
-- 快照的展开树按路径稳定输出（父行必须先于子行插入，读的时候也需要同样顺序）。
CREATE INDEX processing_bom_snapshot_line_by_snapshot
  ON processing_bom_snapshot_line(tenant_id, snapshot_id, path, id);
-- 倒排：某个组件 SKU 在冻结快照里出现过哪些层级（回答「这个料按什么损耗率算过」）。
CREATE INDEX processing_bom_snapshot_line_by_sku
  ON processing_bom_snapshot_line(tenant_id, sku_id, snapshot_id);
-- 只看需要采购的行（缺口 > 0 的叶子件），不带叶子标记：
-- 叶子件才是「要采购/备货的对象」，中间层是过程而不是采购对象（与 V26 同形状）。
CREATE INDEX processing_bom_snapshot_line_purchase
  ON processing_bom_snapshot_line(tenant_id, snapshot_id, gap DESC)
  WHERE gap > 0 AND expanded = false;

-- ===========================================================================
-- 4. 触发器（**只挂在新表上**，纵深防线）
-- ===========================================================================
--
-- **为什么不加在 processing_order 上**：6.4.1.1 已交付且它的集成测试已经跑绿，
-- 在它的表上加约束会连带炸掉那个模块的契约（V97 第九节明确「跨车道 DDL 回归面 = 0」
-- 是它选型的目标）。本模块**只读** processing_order、**不改它的任何一列**，
-- 因此跨车道 DDL 回归面仍然是 0，3.5.2.1 / 3.5.2.2 / 6.4.1.1 的集成测试都不需要为本车道重跑。

-- 4.1 **快照不可改写**：冻结是本模块唯一的动作，冻结之后这张快照就是事实。
--     BOM 后来怎么改、展开怎么重算、库存怎么变，都不许回写它 ——
--     一旦能改，「这批料当时按哪一版、什么损耗率算的」就永远答不出来。
--     与 V97 processing_order_event 的 append-only 同一纪律。
CREATE FUNCTION guard_processing_bom_snapshot_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- plpgsql 的 RAISE EXCEPTION 第一个参数必须是**字面量格式串**，不接受 `||` 拼接
   -- （V97 同一纪律：消息一律写成一行，不是排版偷懒而是语法要求）。
 RAISE EXCEPTION '% 是已冻结快照，% 会被拒绝（加工单 %）：冻结之后 BOM 再怎么改配、库存再怎么变，这张单当时按哪一版、哪棵树、什么损耗率领的料都必须原样留存；回写它等于让「这批料当时为什么是这个数」永远答不出来。要按新 BOM 加工请另开一张加工单',
   TG_TABLE_NAME, TG_OP, OLD.order_id USING ERRCODE='23514';
END $$;
CREATE TRIGGER processing_bom_snapshot_immutable
  BEFORE UPDATE OR DELETE ON processing_bom_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_processing_bom_snapshot_frozen();

-- 4.2 **快照明细同样不可改写**：行的增删改全部拒绝。理由与 4.1 同源 ——
--     明细是「清单长什么样」的举证凭据，少一行就答不出「当时算了几种料」，
--     改一行就答不出「这一行当时是多少」。
CREATE FUNCTION guard_processing_bom_snapshot_line_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是已冻结快照的明细，% 会被拒绝（快照 %）：冻结的组件清单是「这批料当时按什么口径算的」的举证凭据，增删改任何一行都会让清单与当时的展开结果对不上',
   TG_TABLE_NAME, TG_OP, OLD.snapshot_id USING ERRCODE='23514';
END $$;
CREATE TRIGGER processing_bom_snapshot_line_immutable
  BEFORE UPDATE OR DELETE ON processing_bom_snapshot_line
  FOR EACH ROW EXECUTE FUNCTION guard_processing_bom_snapshot_line_frozen();

-- 4.3 冻结必须落在**已存在的加工单**上，且那张单要能被下游读得懂：
--     下达（RELEASED 及之后）的加工单已经在仓库按当时的量领过料了，
--     这时再补一份快照只会在历史里出现两个答案。
--     反过来，草稿单的快照是可以反复冻结的（对应 UNIQUE(tenant_id,order_id) 只允许一份，
--     所以实际上是「先作废再重开」而不是「原地改」）。
CREATE FUNCTION guard_processing_bom_snapshot_release() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_no text;
BEGIN
 SELECT status, order_no INTO parent_status, parent_no
   FROM processing_order WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
 IF parent_status IS NULL THEN
  RAISE EXCEPTION '加工单不存在（%），不能冻结 BOM 展开结果', NEW.order_id USING ERRCODE='23514';
 END IF;
 IF parent_status <> 'DRAFT' THEN
  RAISE EXCEPTION '加工单「%」当前是 %，不能再冻结 BOM 展开结果：它已经按当时的组件领料清单发出去了，这时补一份快照会让「这张单当时按哪棵树算的」出现两个答案。要按新 BOM 加工请另开一张加工单', parent_no, parent_status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_bom_snapshot_release BEFORE INSERT ON processing_bom_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_processing_bom_snapshot_release();

-- 4.4 快照行写入时必须挂在一份**草稿态**加工单上。
--     与 4.3 同源，但挡的是另一条路径：先插快照行、后把单子推进到 RELEASED。
CREATE FUNCTION guard_processing_bom_snapshot_line_release() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_no text;
BEGIN
 SELECT o.status, o.order_no INTO parent_status, parent_no
   FROM processing_order o
   JOIN processing_bom_snapshot s ON s.tenant_id = NEW.tenant_id AND s.id = NEW.snapshot_id
  WHERE o.tenant_id = NEW.tenant_id AND o.id = s.order_id;
 IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
  RAISE EXCEPTION '加工单「%」已是 %，不能往它的 BOM 冻结快照里写明细：冻结清单必须在下达之前定稿，下达之后再补会让「当时算了几种料」出现两个答案', parent_no, parent_status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_bom_snapshot_line_release BEFORE INSERT ON processing_bom_snapshot_line
  FOR EACH ROW EXECUTE FUNCTION guard_processing_bom_snapshot_line_release();

-- ===========================================================================
-- 5. 多租户：RLS + 授权（与 trade V1 / V92 / V97 同一套写法）
-- ===========================================================================
-- 两张表租户级数据，一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['processing_bom_snapshot','processing_bom_snapshot_line'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  processing_bom_snapshot, processing_bom_snapshot_line TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  processing_bom_snapshot, processing_bom_snapshot_line TO starcon_app;
-- 4.1 / 4.2 收回改写权（「授权在前、收回在后」，顺序不能颠倒，
-- 否则下面这两条会被上面的 GRANT 覆盖回去，冻结就只剩注释）。
REVOKE UPDATE,DELETE ON processing_bom_snapshot FROM trade_app;
REVOKE UPDATE,DELETE ON processing_bom_snapshot_line FROM trade_app;

-- ---------------------------------------------------------------------------
-- 6. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 的 trade_tenancy_probe、V92 的七张履约表、
--     V97 的五张加工单表一列都不碰 —— 没有 ADD COLUMN、没有 SET NOT NULL、
--     也没有既有表的触发器被新约束覆盖。所以对 6.4.1.1 / 9.3.2.2 的
--     **DDL 回归面 = 0**，它们的既有集成测试不需要为本车道重跑。
--   * 唯一的跨表依赖是 `processing_bom_snapshot.order_id` 指向
--     `processing_order(tenant_id,id)`（复合外键 + CASCADE）与两处只读触发器查询，
--     **只读不改写**，所以 6.4.1.1 侧的行为不受影响。
--   * 对 catalog 的 bom / bom_version / bom_explosion_run / bom_explosion_line
--     **只存逻辑 ID，不加任何约束**：跨库 FK 本来就禁止，
--     所以本文件既没有约束到 V25 / V26 的任何一张表，
--     3.5.2.1 与 3.5.2.2 的集成测试也不需要为本车道重跑。
--   * 新增函数全部带 processing_bom_snapshot 前缀，与 V1 / V25 / V92 / V97
--     的既有函数名不冲突。