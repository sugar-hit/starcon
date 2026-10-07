-- 3.5.2.2 BOM 展开：按 BOM 版本与需求量展开出需要采购/备货的组件清单。
--
-- 蓝图 3.5.2.2 的原话：
--   「职责：按 BOM 版本与需求量展开出需要采购/备货的组件清单与数量，支持多层展开和按仓库过滤。」
--   「输入：成品 SKU + BOM 版本 + 需求量（或基于 06/07 的缺口触发）。」
--   「输出：多级展开的组件清单（含需求量、当前可用、在途、缺口）。」
--   「关系：上游 = 3.5.2.1、07 库存、06 采购；下游 = 06 采购单（缺口转采购）、07 补货。
--     **展开结果是建议不是事实**，实际采购由 06 生成单据后成立。」
--
-- ============================================================ 零、与 3.5.2.1 的分工（V25 文件头末节已经钉死的边界）
--
-- V25 逐字写着「多级递归展开、子 BOM 当作组件、按仓库过滤、缺口计算全部归 3.5.2.2」，
-- 且 **bom_component 只引用 SKU 不引用 BOM** —— 嵌套 BOM 在数据上靠
-- 「子 BOM 的成品 SKU 出现在父 BOM 的组件行里」表达。本文件补的正是 V25 刻意不做的四件事：
--
--   1. **多层递归展开**：V25 的 R7 只算一层，本模块把它逐层递推下去。
--   2. **子 BOM 判定**：V25 不预先摊平嵌套（那是本模块的判定职责）。
--   3. **按仓库过滤**：按仓库过滤可用量与在途量。
--   4. **缺口计算**：需求量 − 可用 − 在途。
--
-- 生效区间 R1–R5、损耗率 R6、单层需求量 R7 **逐字沿用 V25，不另立一套**：
-- 尤其 R7 的公式与「保留 6 位小数 HALF_UP、不取整」在本模块一行不改地复用
-- （Java 侧转发 {@code BomRules.requiredQuantity}，子级拿父级的缺口当「产量」代入同一公式）。
--
-- ============================================================ 一、逐层结转的需求量递推（本模块钉死的 E1）
--
-- 蓝图要的是「需要采购/备货的组件清单」—— 采购口径必然是**净额**，不是毛额：
--
--   顶层（depth=1，成品版本的直接组件）：
--     required = output_quantity × unit_quantity × (1 + scrap_rate/100)     ← 就是规则 R7
--   每一层（depth>1，父行是子装配）：
--     required = **父行的缺口 gap** × unit_quantity × (1 + scrap_rate/100)  ← 仍是规则 R7 的同一个公式
--   每一行：
--     available  = warehouse 服务按仓库过滤后返回的可用量（**跨服务 HTTP，不在本库聚合**）
--     in_transit = warehouse 服务按仓库过滤后返回的在途量
--     gap        = max(0, required − available − in_transit)                ← 下限 0，见下
--
-- **为什么子级用「父行的缺口」而不是「父行的需求量」**：
-- 半成品 SA 在本仓已有 4 个、在途 3 个、这次要用 10 个 → 只差 3 个才需要向下展开。
-- 若按 10 展开，SA 下面的面料会被多算 7 份的量，而那 7 份 SA 根本不生产 ——
-- 多算出来的面料会变成一张真实的采购单，也就是**凭空多花的钱**。
-- 蓝图把本模块的结果定性为「建议」，而建议多算比少算更危险：少算只是要补一单，
-- 多算是一张没人能解释为什么不买的采购单。
--
-- ============================================================ 二、缺口公式与「在途为什么可以抵扣」（本模块钉死的 E2）
--
--   **gap = max(0, required − available − in_transit)**
--
--   * **为什么在途可以抵扣**：在途货的**货权已归本租户**（V1 迁移的守恒式写明
--     「在手 = 在仓实物 + 在途」，货权离开原仓时计入在途）。它不是「还要不要买」，
--     而是「已经买了、只是还没到」。抵扣它是为了不把同一批料**重复下单**。
--   * **什么情况下不该抵扣（本模块据此做的设计）**：
--       (a) 在途发往的**不是你要的那个仓** —— 所以在途必须按 `to_warehouse_id`
--           一起过滤。只按收货仓过滤可用量、不和在途上按收货仓过滤，
--           会出现「深圳仓的缺口被上海仓发来的在途抵扣掉」，而那批货根本不会进深圳仓。
--           这是仓库过滤必须同时作用在 available 与 in_transit 上的唯一理由。
--       (b) 在途**到货晚于需要它的时刻** —— 那是提前期判断，属 06/07 的补货决定，
--           不属本模块。所以本模块把 available / in_transit / gap **三列都原样保留**，
--           缺口只做「不低于 0」的截断，不替下游做提前期决策：
--           运营看到「缺 3、可用 0、在途 5」时自己会知道那 5 件到得太晚。
--   * **为什么不把负数暴露成负缺口**：负数表达的是「库存多了」，
--     而「多出来多少」对采购决策无用、对页面排版有害（缺口列会出现负号）。
--     富余量由 available / in_transit 两列自己说话，不额外开一列。
--
-- ============================================================ 三、子 BOM 的判定规则（本模块钉死的 E3）
--
-- 判定分四步，**顺序不能换**：
--
--   1. **是不是子装配**：存在 `bom.output_sku_id = 该组件行的 sku_id` 的 BOM（本租户）。
--      没有 → 叶子件，按采购/备货件处理，到此为止。
--   2. **哪个子 BOM**：`bom` 表对 (tenant_id, output_sku_id) **没有唯一约束**
--      （V25 只建了 bom_by_output_sku 索引），所以同一个成品 SKU 理论上能被多个 BOM 声明为产出。
--      这时「按哪一版展开」有两个答案。本模块**取 bom.code 升序的第一个**（确定性），
--      并在该行置 `ambiguous_sub_bom = true` 且把候选 BOM 逐个写进 note ——
--      确定性 + 显式告警，而不是静默挑一个也不说。
--   3. **哪个子版本**：对选中的子 BOM 按规则 **R4** 取 `as_of` 这一刻生效的**已发布**版本
--      （半开区间，与 V25 触发器同一段 SQL 形状）。只有已发布版本参与（R3）。
--      **取不到生效版本 → 该组件行按叶子件处理**，note 写明「这个组件本身是另一个 BOM 的成品，
--      但在 as_of 这一刻没有生效版本，所以按采购件处理」——
--      因为按已发布版本算料是 08 加工单与 06 采购的纪律，草稿不是事实（R3）。
--   4. **展开到几层**：见第四节 E4。
--
-- ============================================================ 四、循环引用与深度上限（本模块钉死的 E4）
--
-- **循环引用：直接拒绝展开并报错，不截断。**
-- 判定键是**当前路径上出现过的成品 SKU**：子 BOM 由 output_sku_id 唯一确定，
-- 所以「同一个 SKU 在自己的祖先链上再次出现」等价于「BOM 图上绕回了自己」。
-- 拒绝（409）而不是截断的理由：绕回自己意味着这批料的需求量**在数学上无界**，
-- 任何有限的截断结果都是一个错的小数，而它会被当成采购建议下成真实的单。
-- 报错信息必须逐个点名绕回路径（成品A → 半成品B → 成品A），
-- 否则运营只知道「有循环」却不知道改哪一行。
-- 注意**这条约束在数据库层做不到**：bom_component 只引用 SKU 不引用 BOM（V25 的刻意选择），
-- 只有真正展开时才知道绕回，所以纵深防线在服务层，展开循环里逐层判定。
--
-- **深度上限：截断但必须显式标明。**
-- 合法的 BOM 树可以很深（成品 → 组件 → 组件 → …）。上限默认 10 层、最多 25 层
-- （可用性限制：再深就不是人能核对的清单，而领错料要上仓库找回来）。
-- 命中上限时：run 的 status = 'TRUNCATED'，该行 `truncated = true`，note 写明
-- 「已展开到第 N 层上限，这里还有下级 BOM 未展开」——
-- **静默截断是未完成**：页面上必须看得见「这份清单不完整」。
--
-- ============================================================ 五、available / in_transit 绝不落成本地事实（服务边界硬纪律）
--
-- AGENTS.md「模块边界纪律」：跨服务必须走授权 API / event，
-- **禁止跨服务 SQL / FK / 共享视图**。inventory_balance / inventory_transfer
-- 是 warehouse 服务的表，catalog 本库**没有它们**，本迁移也**不建**任何指向它们的列：
--
--   * 本模块的可用量与在途量由 catalog 侧通过
--     `POST /warehouse/inventory/availability`（授权端点，带 X-Internal-Key 与调用者的
--     Authorization 头，**租户仍由 identity 从已认证成员关系解析**，warehouse 自己绑定
--     app.current_tenant 后受 RLS 强制隔离）取得，**不在本库复制一份库存快照**。
--   * 但「哪一批量被算进了这一次展开」是**本模块自己的事实**（缺 3 是哪一次展开算出来的），
--     所以每次展开把结果冻结成 run + line 两张表 —— 冻结的是**建议**，不是库存事实：
--     库存随时在变，而「06 的采购单引用了哪一次展开、那次算出来的缺口是多少」必须可回溯。
--
-- ============================================================ 1. 展开运行记录
--
-- 一行 = 一次展开。它是「建议」的凭据：06 采购单引用的是**某一次展开的 id**，
-- 而不是「现在的 BOM × 现在的库存」—— 后者随时会变，采购单发出去之后无法解释
-- 「当时为什么按这个数下单」。
CREATE TABLE bom_explosion_run (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 被展开的 BOM 与版本。version_id 指向**具体那一版**（可含草稿：试算草稿是纯计算，不是事实），
  -- 所以本行不回查「此刻生效的是哪一版」—— 事后重算会得到另一个答案。
  bom_id uuid NOT NULL,
  version_id uuid NOT NULL,
  version_no varchar(32) NOT NULL,
  output_sku_id uuid NOT NULL,
  -- 成品数量（规则 R7 的产量因子）。正整数：生产计划按件下达，0.5 个成品在仓库里不存在。
  -- 允许写成 numeric 而不是 int 是为了与 unit_quantity 同精度递推（子级拿父级缺口当产量时是小数）。
  output_quantity numeric(19,6) NOT NULL CHECK(output_quantity > 0),
  -- **仓库过滤**：按仓库算可用与在途。逻辑 ID，**不建外键**（warehouse 是另一个服务，
  -- 建跨库 FK 就是 AGENTS.md 明令禁止的跨服务耦合）。为空 = 不限仓库（全仓合计）。
  warehouse_id uuid,
  -- **展开基准时刻 as_of**：子 BOM 取哪一版（R4 的时刻查询）与结果一起冻结下来。
  -- 事后重算会得到另一个答案（子 BOM 可能已经换了版本），所以它是业务时间的一部分。
  as_of timestamptz NOT NULL,
  -- 深度上限与实际到达深度（实际 > 上限是不可能的；两者相等且 status='TRUNCATED'
  -- 表示「正好在上限处被截断」）。
  max_depth int NOT NULL CHECK(max_depth >= 1),
  reached_depth int NOT NULL CHECK(reached_depth >= 1 AND reached_depth <= max_depth),
  -- 结果状态。TRUNCATED = 命中深度上限、清单不完整（页面必须显红）。
  -- CYCLE 在本设计里不会出现：循环引用直接抛 409，不落库（见第四节）。
  status text NOT NULL CHECK(status IN ('COMPLETED','TRUNCATED')),
  -- 行数与合计（写回的结果，不是实时 join 出来的 —— 展开结果一旦算完就该冻结）。
  line_count int NOT NULL CHECK(line_count >= 0),
  purchase_line_count int NOT NULL CHECK(purchase_line_count >= 0),
  total_required numeric(19,6) NOT NULL DEFAULT 0,
  total_gap numeric(19,6) NOT NULL DEFAULT 0,
  -- 谁在什么时候展开的（展开是读操作，但留痕是为了让「这份采购建议是哪来的」可查）。
  requested_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  -- created_at 是系统时间（落库时刻），updated_at 同样；业务时间由 as_of 承担，两者分开存。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,bom_id) REFERENCES bom(tenant_id,id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id,version_id) REFERENCES bom_version(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 2. 展开明细行
--
-- 一行 = 展开树上的一层。parent_line_id 为空表示「成品版本的直接组件」，
-- 否则指向它的父行 —— 递归的形状因此完全落在数据里，页面不必自己猜层级。
CREATE TABLE bom_explosion_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, run_id uuid NOT NULL,
  parent_line_id uuid,
  -- 层级（成品版本直接列出的组件是第 1 层）。与 reached_depth 一起构成上限判定。
  depth int NOT NULL CHECK(depth >= 1),
  -- 物化路径（如 '1'、'1.3'、'1.3.2'）：稳定排序 + 出错时能指名道姓说「哪一条路径上的哪一行」。
  path varchar(1000) NOT NULL CHECK(btrim(path)<>''),
  sku_id uuid NOT NULL,
  -- **来源组件行**：这一行是从哪条 bom_component 算出来的（回溯「用量与损耗率是哪来的」）。
  component_id uuid NOT NULL,
  -- **算出这一行的 BOM 版本**：子装配行上指的是**子 BOM 的那个版本**，
  -- 所以「这一层的料是按哪一版算的」逐行可答。
  source_version_id uuid NOT NULL,
  source_version_no varchar(32) NOT NULL,
  source_bom_code varchar(64) NOT NULL,
  -- 逐行冻结 unit_quantity 与 scrap_rate（复刻自来源组件行）：
  -- 结果要能脱离「现在的 BOM」独立成立，06 采购单不能因为 BOM 改了就对不上账。
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity > 0),
  scrap_rate numeric(7,4) NOT NULL CHECK(scrap_rate >= 0 AND scrap_rate <= 100),
  -- **需求量**：规则 R7 的逐层递推结果（含损耗、含逐层结转），6 位小数、不取整。
  required_quantity numeric(19,6) NOT NULL CHECK(required_quantity >= 0),
  -- 可用与在途来自 warehouse 服务的授权端点（**不建外键**，见第五节）。不足 0 时截到 0：
  -- 负库存是 warehouse 侧的数据问题，不该让本模块的缺口公式出现「负的可用减负的缺口」。
  available numeric(19,6) NOT NULL DEFAULT 0 CHECK(available >= 0),
  in_transit numeric(19,6) NOT NULL DEFAULT 0 CHECK(in_transit >= 0),
  -- 缺口：max(0, required − available − in_transit)，规则见文件头第二节。
  gap numeric(19,6) NOT NULL DEFAULT 0 CHECK(gap >= 0),
  -- **子 BOM 判定结果**（规则 E3，逐个字段对应那四步）。
  is_sub_assembly boolean NOT NULL DEFAULT false,
  sub_bom_id uuid,
  sub_bom_code varchar(64),
  sub_version_id uuid,
  sub_version_no varchar(32),
  -- 同一个成品 SKU 被多个 BOM 声明为产出 → 取 code 序第一个并显式告警（规则 E3 第 2 步）。
  ambiguous_sub_bom boolean NOT NULL DEFAULT false,
  -- 实际是否向下展开了（子装配有生效版本且未命中深度上限）。
  expanded boolean NOT NULL DEFAULT false,
  -- 可用 + 在途已覆盖本行需求 → 不再向下展开（向下展开只会产生一串 0 需求的噪声行）。
  covered boolean NOT NULL DEFAULT false,
  -- 命中深度上限，本行还有下级没展开（规则 E4）。
  truncated boolean NOT NULL DEFAULT false,
  role text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一层路径唯一：同一棵展开树上同一个位置只能有一行，
  -- 两行时「这一层的这个组件要多少」有两个答案，而递归会把它算两遍。
  UNIQUE(tenant_id,run_id,path),
  FOREIGN KEY(tenant_id,run_id) REFERENCES bom_explosion_run(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id,component_id) REFERENCES bom_component(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 3. 索引
--
-- 运行记录列表页：按 BOM 读历史，最新在前。
CREATE INDEX bom_explosion_run_by_bom
  ON bom_explosion_run(tenant_id, bom_id, created_at DESC, id);
-- 倒排：这个成品 SKU 被展开过多少次（页面上「最近展开」按成品筛选走它）。
CREATE INDEX bom_explosion_run_by_output_sku
  ON bom_explosion_run(tenant_id, output_sku_id, created_at DESC);
-- 「待补货」视图：按缺口排序拿最缺的那几个成品（下游 07 补货按它排优先级）。
CREATE INDEX bom_explosion_run_gap
  ON bom_explosion_run(tenant_id, total_gap DESC, created_at DESC)
  WHERE total_gap > 0;
-- 展开树按路径稳定输出（父行必须先于子行插入，所以读的时候也需要同样的顺序）。
CREATE INDEX bom_explosion_line_by_run
  ON bom_explosion_line(tenant_id, run_id, path, id);
-- 倒排：某个 SKU 在展开结果里出现在哪几次、哪几层（06 算「这个料要买给谁」读它）。
CREATE INDEX bom_explosion_line_by_sku
  ON bom_explosion_line(tenant_id, sku_id, run_id);
-- 只看需要买的行（缺口 > 0 的叶子件），不带叶子标记：
-- 叶子件才是「要采购/备货的对象」，中间层是过程而不是采购对象。
CREATE INDEX bom_explosion_line_purchase
  ON bom_explosion_line(tenant_id, run_id, gap DESC)
  WHERE gap > 0 AND expanded = false;

-- ============================================================ 4. RLS 与授权
--
-- 两张表都是租户级数据（BOM 是租户共享的生产定义，与 V25 三张表同一口径，
-- 不套 V3 的 owner_id / department_id）。
--
-- **递归展开的每一层都受这条策略约束**：展开服务逐层查询，每一条 SQL 都带
-- tenant_id=? 且 FORCE RLS 在数据库层再挡一次 —— 展开树里没有任何一层能绕过隔离。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['bom_explosion_run','bom_explosion_line'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON bom_explosion_run, bom_explosion_line TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON bom_explosion_run, bom_explosion_line TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **06 采购与供应**：取某一次展开（GET /catalog/bom-explosion-runs/{id}），
--     读它冻结下来的 line 里 gap > 0 的行，生成采购单。
--     **本模块只给建议**：缺口怎么取整、补多少安全库存、要不要合并同一 SKU 的多行，
--     都是 06 的决定 —— 蓝图原话「展开结果是建议不是事实，实际采购由 06 生成单据后成立」。
--   * **07 库存补货**：同一批行作为补货建议的输入，并可用
--     GET /catalog/bom-explosion-runs?bomId= 回溯「这份清单当时算的是什么数」。
--   * **08 仓储作业**：加工单领料前先展开，确认要领的料确实落在这棵树上。
