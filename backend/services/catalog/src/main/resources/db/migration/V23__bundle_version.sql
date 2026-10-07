-- 3.5.1.1 套装版本管理：组合套装（多个 SKU 按数量捆绑销售）的版本。
--
-- 蓝图 3.5.1.1 的原话：
--   「职责：管理组合套装（多个 SKU 按数量捆绑销售）的版本：创建版本、设置生效区间、发布后不可变。」
--   「输入：套装名称、组件清单、版本号、生效区间；发布操作。」
--   「输出：套装版本记录（草稿/已发布）；供 04 刊登为独立商品、供 09 订单展开组件。」
--   「关系：上游 = 3.5.1.2；下游 = 3.1.2.2（套装作为可售 SKU）、09 订单（组件展开）。
--     **虚拟套装不持有独立库存**，扣的是组件库存（实体目录 §3.4）。」
--
-- ============================================================ 一、为什么分「套装」与「套装版本」两张表
--
-- 蓝图把「套装」和「套装版本」当成两件事：版本号、生效区间、发布状态都是**版本**的属性，
-- 而「这个套装存在、编码是什么、归档没归档」是**套装**的属性。
--
-- 不合并成一张表的具体理由（任意一条都足以单独成立）：
--   1. 「同一套装同一时刻至多一个生效版本」是**同套装内跨行**的约束。两行合一就没有
--      「同一个 bundle_id」这个分组键了，重叠判定会退化成全表两两比较——既慢又说不清
--      冲突的是哪两个套装。
--   2. 套装编码是**租户内唯一**且建后不可改（它是刊登与订单里的稳定引用），
--      版本号只要求**同套装内**唯一。合并后 UNIQUE(tenant_id, code) 与
--      UNIQUE(tenant_id, code, version_no) 会互相打架：改一个版本号就会撞编码唯一键。
--   3. 下游 04 刊登、09 订单引用的是「套装 + 当时的版本」，而不是「某个版本号恰好叫 v3」。
--      归档一个套装（bundle.status='ARCHIVED'）不该动它的历史版本。
--
-- ============================================================ 二、生效区间的裁决规则（本文件最关键的一段）
--
-- 蓝图标了「设置生效区间」，但**没有规定重叠怎么办**。本文件选一条规则并钉死：
--
--   **规则 R1（半开区间）**：生效区间是左闭右开的 [effective_from, effective_to)。
--     `effective_to` 为 NULL 表示「此后一直有效」（等价于 +∞）。
--     于是 [10:00,12:00) 与 [12:00,14:00) **不重叠**——12:00 这一时刻恰好属于后一个版本。
--     用闭区间的话这两个版本会在 12:00 这一瞬间「同时生效」，而「当前生效版本」这个
--     查询就必须返回两行或随便挑一行，两种都是编造。
--
--   **规则 R2（重叠一律拒绝，不做裁决）**：同一个套装下，任意两条**已发布**版本的区间
--     不得重叠。冲突时拒绝发布，并在报错里点名与哪一版（version_no + 区间）冲突。
--     为什么不选「后来者覆盖」或「区间窄者优先」：这两种都要先定义「谁是后来者」，
--     而版本号的先后与区间的先后**互不相关**（v3 可能从今年 6 月开始生效而 v2 从去年开始）。
--     一旦要靠「规则」决定谁生效，运营就再也答不出「3 月 15 日卖的是哪一版」——
--     而那正是 09 订单冻结展开结果时必须能回答的问题。拒绝重叠把这个答案变成唯一的。
--
--   **规则 R3（只有已发布版本参与判定）**：草稿的区间随便重叠。草稿是待办不是事实，
--     两个草稿都还没生效，让它们互相排斥只会逼着运营先决定发布顺序。
--     草稿**发布的那一刻**才被 R2 检查。
--
--   **规则 R4（当前生效版本的确定性查询）**：
--     `published AND effective_from <= t AND (effective_to IS NULL OR t < effective_to)`
--     由 R2 保证至多命中一行。这是一个纯函数式的时刻查询，04 刊登与 09 订单都走它。
--
--   **规则 R5（已发布版本只能「向前关闭」）**：已发布版本的生效区间**不能重开、不能往后延、
--     不能改成已经过去的时刻**，只允许把 `effective_to` 从 NULL（或较晚的值）收紧到
--     一个**仍在未来**的时刻。为什么必须留这一个口子：若连收紧都禁掉，
--     第一个开放区间的版本发布后就永远无法被接替，整个版本机制第二天就死了。
--     而收紧只能往前、且不能落进过去，保证「历史某时刻生效的是哪一版」这个答案永不被追溯改写。
--
-- ============================================================ 三、发布后不可变的强制位置
--
-- 「已发布版本不可变」在三层各钉一遍，任何一层被绕过都还有下一层：
--   1. 服务层（BundleVersionService）：发布后拒绝改名 / 改版本号 / 改生效起点 / 改组件。
--   2. CHECK 约束：PUBLISHED 必须带 published_by + published_at。
--   3. **触发器**（文件头说的道理：CHECK 在 PostgreSQL 里可以被 NOT VALID 或迁移脚本绕过，
--      触发器不行）：
--      guard_bundle_version_published（BEFORE UPDATE，逐列比较已发布版本）
--      refuse_bundle_version_published_delete（BEFORE DELETE，已发布版本不可删）
--      guard_bundle_component_published（BEFORE INSERT/UPDATE/DELETE，组件挂在已发布版本上）
--
-- 已发布版本不可删的理由不是「留痕好看」：09 订单冻结的是**当时**的组件展开结果，
-- 但订单行本身引用的是套装；删掉历史版本会让「这个订单当时是按哪个版本展开的」无从查证。
-- 要停止一个套装，正确做法是归档 bundle 而不是删版本。
--
-- ============================================================ 四、与 3.5.1.2 组件维护 / 3.5.2.x BOM 的边界
--
--   * **3.5.1.2 组件维护**（未开发）在本表之上做：组件增删改的独立工作台、
--     **循环引用检测**（套装 A 含套装 B 且 B 含 A）、组件必须是本租户真实 SKU。
--     本文件把 `bundle_component` 的**形状**先建对——行挂在**版本**上（不是挂在套装上），
--     因为「已发布版本的组件不可改，只能新建版本」这条纪律要求组件本身带版本归属。
--     本模块交付的是「随草稿版本一起编辑组件清单 + 发布时校验组件齐备」，
--     因为组件清单是蓝图写在本模块「输入」里的东西；循环检测留给 3.5.1.2。
--   * **组件只引用 SKU，不直接引用套装**。嵌套套装（套装含套装）在蓝图的表述里
--     是「套装作为可售 SKU」（3.1.2.2）——套装自己先成为一个可售 SKU 行，
--     再被别的套装当组件引用。循环检测因此落在 3.1.2.2/3.5.1.2 的 SKU↔套装链路上，
--     而 bundle_component 保持一条干净的 SKU 外键，本文件不需要含糊的多态列。
--   * **3.5.2.x BOM** 是生产/加工用的物料清单，会产生实物成品并持有自己的库存；
--     套装是销售用的虚拟组合，**不持有独立库存**，扣的是组件库存（实体目录 §3.4）。
--     两者语义不同，不合并表。
--
-- ============================================================ 1. 套装档案
--
-- 一行 = 一个租户的一个套装。它只回答「这个套装存在吗、编码是什么、还在用吗」，
-- 版本的一切都在 bundle_version。
CREATE TABLE bundle (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 套装编码：租户内唯一且**建后不可改**（服务层强制）。它是刊登与订单里的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  -- 状态。ARCHIVED 只表示「不再新建版本」，历史版本照常可读（09 订单要读）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  -- created_at 是业务时间（运营什么时候建的套装），
  -- updated_at 是系统时间（改动发生的时刻），两者分开存。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一租户同编码只能有一个套装：编码重复时「刊登里的这个套装指哪个」无法回答。
  UNIQUE(tenant_id,code)
);

-- ============================================================ 2. 套装版本
--
-- 一行 = 一个套装的一个版本。这是本模块的事实表：草稿可改，发布后按文件头 R5 与
-- 第三节的触发器钉死。
CREATE TABLE bundle_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, bundle_id uuid NOT NULL,
  -- 版本号。同套装内唯一（运营手打的「v1」「2026-春季」），**建后不可改**。
  version_no varchar(32) NOT NULL CHECK(btrim(version_no)<>''),
  -- 套装名称（挂在版本上而不是套装上）：「改名」在蓝图里属于不可改清单的一项，
  -- 说明名称是随版本走的。09 订单冻结的也是当时的名称。
  name varchar(200) NOT NULL CHECK(btrim(name)<>''),
  -- 生效区间 [effective_from, effective_to)。effective_to 为 NULL = 一直有效（规则 R1）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  -- 草稿 → 已发布，单向。撤回发布不是合法动作（触发器在第三节钉死）。
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  -- 谁在什么时候发布的。status='PUBLISHED' 时两者都必须有值。
  published_by uuid, published_at timestamptz,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同套装同版本号只能有一行：两行时「v2 是哪一个」无法回答，而生效区间判定会两边都算数。
  UNIQUE(tenant_id,bundle_id,version_no),
  -- 空区间没有意义（等于「从不生效」）。左闭右开，所以 to 必须严格大于 from。
  CONSTRAINT bundle_version_range_ordered
    CHECK (effective_to IS NULL OR effective_to > effective_from),
  -- 已发布必须有发布人与发布时刻：没有发布人的 PUBLISHED 在数据里等于「有人按过发布」这种
  -- 无法证伪的说法，而这一版会被 09 订单按它展开组件。
  CONSTRAINT bundle_version_published_needs_actor
    CHECK (status<>'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),
  -- 草稿不能带着发布痕迹（否则「已发布」与「草稿」的差别只剩一个没有行为后果的字段）。
  CONSTRAINT bundle_version_draft_has_no_release
    CHECK (status<>'DRAFT' OR (published_by IS NULL AND published_at IS NULL)),
  FOREIGN KEY(tenant_id,bundle_id) REFERENCES bundle(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. 版本组件
--
-- 一行 = 一个版本里的一个组件 SKU。**行挂在版本上而不是套装上**：
-- 「已发布版本的组件不可改，只能新建版本」要求每条组件记录都能回答「我属于哪一版」。
--
-- quantity 是 NUMERIC(19,6) 而不是 int：套装里出现「0.5 米挂绳 + 2 个挂钩」这类按计量单位
-- 组合是真实场景（3.5 挂在「组合与物料」下，BOM 侧本来就有单位用量与损耗率），
-- int 会逼着运营把 0.5 写成「1 件」，而 09 订单展开与 07 库存扣减都会照着这个假数走。
-- 禁浮点：BigDecimal + NUMERIC，精度由列类型固定。
CREATE TABLE bundle_component (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, version_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- 每件套装需要这个 SKU 多少。必须 > 0：数量为 0 的组件行是「配了一个组件但不消耗」，
  -- 它会让 09 订单展开出一条数量为 0 的子行、07 库存扣减出一条 0 扣减，两边都答不出
  -- 「这个组件到底要不要」。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- 组件角色是自由文本（主机 / 配件 / 赠品…），只给人看，不参与任何判定。
  -- NOT NULL 但可为空串：留空是合法选择。
  role text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一版本里同一个 SKU 只能有一行：两行时「这个组件要几件」无法回答
  -- （是 2 件还是 1+2 件？），而订单展开只能取一列。
  UNIQUE(tenant_id,version_id,sku_id),
  FOREIGN KEY(tenant_id,version_id) REFERENCES bundle_version(tenant_id,id) ON DELETE CASCADE,
  -- 组件 SKU 必须是本租户真实存在的 SKU（复合外键），否则「扣组件库存」扣的是一个
  -- 不存在的东西。用 RESTRICT 而不是 CASCADE：删一个被套装引用的 SKU 应该被拒绝，
  -- 让运营显式处理，而不是悄悄把套装拆掉。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 4. 触发器（纵深防线，见文件头第三节）
--
-- 已发布版本的逐列不可变：改名 / 改版本号 / 改归属套装 / 改生效起点 / 改备注 / 改发布留痕
-- 一律拒绝；effective_to 只允许按规则 R5 向前关闭。
--
-- 名字按字母序：immutable 在 interval 之前，所以同一次 UPDATE 里「能不能改」先于
-- 「会不会撞区间」判定——报错先说最根本的那条。
CREATE FUNCTION guard_bundle_version_published() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'PUBLISHED' THEN RETURN NEW; END IF;

 -- 已发布 → 已发布（或别的状态）不允许：发布是单向的。
 -- 撤回发布会让这一版从 09 订单的展开依据里消失，而历史订单冻结的是「当时的版本」。
 IF NEW.status<>'PUBLISHED' THEN
  RAISE EXCEPTION '已发布的套装版本不能撤回发布（版本 %）：历史订单冻结的是当时展开所用的版本，撤回会让「这单当时按哪一版展开的」无从查证。要停止使用请把套装归档，或让下一个版本接替',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 IF NEW.bundle_id IS DISTINCT FROM OLD.bundle_id THEN
  RAISE EXCEPTION '已发布的套装版本不能改归属套装（版本 %）：套装是刊登与订单里的稳定引用，换套装等于让这一版的历史归属失效。要换请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.version_no IS DISTINCT FROM OLD.version_no THEN
  RAISE EXCEPTION '已发布的套装版本不能改版本号（% → %）：版本号是同套装内的唯一标识，改它会让「v2 是哪一个」在历史数据里出现两个答案。要换请新建版本',
   OLD.version_no, NEW.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.name IS DISTINCT FROM OLD.name THEN
  RAISE EXCEPTION '已发布的套装版本不能改名（版本 %）：发布后名称已经被 04 刊登与 09 订单引用，改名会让「当时卖的那个套装叫什么」查不到。要改名请新建版本并让新版本接替',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '已发布的套装版本不能改生效起点（版本 %）：移动起点会追溯改写历史上生效的是哪一版，而订单冻结的正是当时的版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.published_by IS DISTINCT FROM OLD.published_by
    OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
  RAISE EXCEPTION '已发布的套装版本不能改发布留痕（版本 %）：「谁在什么时候发布的」是这版可追溯的凭据，改掉它等于让发布这件事无法证伪',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.note IS DISTINCT FROM OLD.note THEN
  RAISE EXCEPTION '已发布的套装版本不能改备注（版本 %）：备注写在发布内容里，发布后与组件、名称一样不可变。要补充说明请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 -- effective_to 是唯一的例外，且只允许向前关闭（规则 R5）。
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '已发布的套装版本不能重开生效区间（版本 %）：重新变成「一直有效」会让已经接替它的版本与它同时生效，规则 R2 立刻被破坏',
    OLD.version_no USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to THEN
   RAISE EXCEPTION '已发布的套装版本不能把生效终点往后延（版本 %，% → %）：往后延会与已经接替它的版本重叠，「这个时刻生效的是哪一版」将不再唯一',
    OLD.version_no, OLD.effective_to, NEW.effective_to USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to <= now() THEN
   RAISE EXCEPTION '已发布的套装版本不能把生效终点设成已经过去的时刻（版本 %，%）：那会追溯改写历史上生效的是哪一版，而订单冻结的正是当时的版本',
    OLD.version_no, NEW.effective_to USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bundle_version_published BEFORE UPDATE ON bundle_version
  FOR EACH ROW EXECUTE FUNCTION guard_bundle_version_published();

-- 已发布版本不可删（连同删套装时的级联一起拦住）。
CREATE FUNCTION refuse_bundle_version_published_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布的套装版本不能删除（版本 %）：09 订单冻结的是当时的组件展开结果，删掉这一版会让「这单当时按哪一版展开的」无从查证。要停止使用请把套装归档，而不是删版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_bundle_version_published_delete BEFORE DELETE ON bundle_version
  FOR EACH ROW EXECUTE FUNCTION refuse_bundle_version_published_delete();

-- 组件挂在已发布版本上时不可增删改——「已发布版本的组件不可改，只能新建版本」。
-- 蓝图标的是 3.5.1.2 的纪律，但它落在本模块的版本表上，所以在版本这一层先钉死。
CREATE FUNCTION guard_bundle_component_published() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_no text;
BEGIN
 IF TG_OP='DELETE' THEN
  SELECT status, version_no INTO parent_status, parent_no
    FROM bundle_version WHERE tenant_id=OLD.tenant_id AND id=OLD.version_id;
 ELSE
  SELECT status, version_no INTO parent_status, parent_no
    FROM bundle_version WHERE tenant_id=NEW.tenant_id AND id=NEW.version_id;
 END IF;
 IF parent_status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布版本的组件不可修改（版本 %）：历史订单冻结的是当时展开的组件清单，改组件会让「这单当时包含哪些组件」无从查证。要改组件请新建一个版本并让新版本接替',
   parent_no USING ERRCODE='23514';
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER guard_bundle_component_published
  BEFORE INSERT OR UPDATE OR DELETE ON bundle_component
  FOR EACH ROW EXECUTE FUNCTION guard_bundle_component_published();

-- 区间重叠拒绝（规则 R2）。只有已发布版本参与判定（规则 R3），
-- 所以草稿之间随便重叠，发布的那一刻才被检查。
CREATE FUNCTION guard_bundle_version_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash record;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
 SELECT v.version_no, v.effective_from, v.effective_to INTO clash
   FROM bundle_version v
  WHERE v.tenant_id=NEW.tenant_id
    AND v.bundle_id=NEW.bundle_id
    AND v.id<>NEW.id
    AND v.status='PUBLISHED'
    -- 半开区间比较：'[)' 让 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠（规则 R1）。
    AND tstzrange(v.effective_from, v.effective_to, '[)')
        && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY v.effective_from, v.id
  LIMIT 1;
 IF FOUND THEN
  RAISE EXCEPTION '生效区间与已发布版本 % 冲突（本版本 %：% → %）：同一个套装在同一时刻只能有一个生效版本，否则 04 刊登与 09 订单都答不出「这个时刻该用哪一版」。已发布版本的区间不能改，请调整本版本的区间或把接替版本的终点设为 % 的起点',
   clash.version_no, NEW.version_no, NEW.effective_from,
   coalesce(NEW.effective_to::text, '一直有效'),
   clash.effective_from USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bundle_version_interval BEFORE INSERT OR UPDATE ON bundle_version
  FOR EACH ROW EXECUTE FUNCTION guard_bundle_version_interval();

-- ============================================================ 5. 索引
--
-- 套装列表页：按状态 + 更新时间倒序。
CREATE INDEX bundle_list ON bundle(tenant_id, status, code);
-- 版本列表页的主查询形状：按套装读全部版本。
CREATE INDEX bundle_version_by_bundle ON bundle_version(tenant_id, bundle_id, status, effective_from DESC, id);
-- **「当前生效版本」的确定性查询形状**（规则 R4，04 刊登与 09 订单都走它）。
CREATE INDEX bundle_version_effective ON bundle_version(tenant_id, bundle_id, effective_from, effective_to)
  WHERE status='PUBLISHED';
-- 工作台默认视图：草稿优先、改动最近的在上。
CREATE INDEX bundle_version_drafts ON bundle_version(tenant_id, status, updated_at DESC, id DESC);
-- 已发布版本里「还没接替、还一直有效」的那些——运营要盯着它们接替。
CREATE INDEX bundle_version_open_ended ON bundle_version(tenant_id, bundle_id, effective_from)
  WHERE status='PUBLISHED' AND effective_to IS NULL;
-- 版本详情页的组件清单。
CREATE INDEX bundle_component_by_version ON bundle_component(tenant_id, version_id, sku_id);
-- 反查：某个 SKU 被哪些套装引用（07 库存预占组件、09 订单展开都要读这个方向）。
CREATE INDEX bundle_component_by_sku ON bundle_component(tenant_id, sku_id);

-- ============================================================ 6. RLS 与授权
--
-- 三张表全部租户级数据（套装是虚拟组合，不按人分配数据范围 —— 它是租户共享的商品定义，
-- 与 V18 的媒体库、V22 的译文同一口径，不套 V3 的 owner_id/department_id）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['bundle','bundle_version','bundle_component'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON bundle, bundle_version, bundle_component TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON bundle, bundle_version, bundle_component TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **04 刊登**（把套装当独立商品卖）：取 bundle + 当前生效版本
--     （GET /catalog/bundles/{id}/effective?at=...，规则 R4 的时刻查询），
--     名称与组件来自那一版。只有已发布版本可刊登。
--   * **3.1.2.2 套装作为可售 SKU**：套装本身不占 SKU 行；刊登需要一个可售 SKU 时，
--     由那一侧建 SKU 并引用 bundle + bundle_version。本模块不写 SKU。
--   * **07 库存**：虚拟套装**不持有独立库存**，扣的是组件库存；按本表的组件清单展开。
--   * **09 订单**：下单时读当时的生效版本并把组件展开结果冻结进订单行组件。
--   * **3.5.1.2 组件维护**：本表已具备它需要的形状（组件挂版本、数量 NUMERIC(19,6)>0），
--     循环引用检测与组件工作台在那一侧实现。
--   * **3.5.2.x BOM**：生产/加工用，产生实物成品；与套装的销售语义分表。