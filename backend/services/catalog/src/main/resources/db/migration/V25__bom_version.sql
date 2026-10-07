-- 3.5.2.1 BOM 版本管理：物料清单（成品由哪些组件组成）的版本。
--
-- 蓝图 3.5.2.1 的原话：
--   「职责：维护物料清单（成品由哪些组件组成），支持多版本、版本生效区间、单位用量与损耗率，
--     服务于委外加工与生产型卖家。」
--   「输入：BOM 版本、组件行（SKU、单位用量、损耗率、生效区间）；发布操作。」
--   「输出：BOM 版本记录；供 06 采购建议与 08 加工单引用。」
--   「关系：上游 = 3.5.2.2；下游 = 06 采购与供应（加工采购）、08 仓储作业（组装单）。
--     **BOM 与套装的区别**：BOM 用于生产/加工（产生实物成品），套装用于销售（虚拟扣组件）。」
--
-- ============================================================ 零、BOM 与套装的三条实质差异（先讲清楚，后面每一处都由它推导）
--
-- V23 已经把「套装版本管理」建成三张表（bundle / bundle_version / bundle_component）。
-- 本文件**不合并、不复用**它们，因为两者的业务语义不同 —— 不是「同一张表加个类型列」能糊过去的：
--
--   * **差异 1：BOM 有成品，套装没有。**
--     套装的 `bundle` 只挂一个编码，它**不指向任何 SKU**：虚拟组合不占 SKU 行，
--     卖套装扣的是组件库存（实体目录 §3.4）。而 BOM 的存在意义就是
--     「这个**成品**由哪些组件组成」—— 蓝图的「职责」第一句就是这个。
--     所以本表有 `bom.output_sku_id` NOT NULL：没有成品就没有物料清单。
--     这条不是多加的字段，而是 BOM 与套装在**数据形状**上的根本差别：
--     套装行的 `sku_id` 全是输入，BOM 行里「成品」和「组件」是两类角色。
--
--   * **差异 2：组件行带损耗率。**
--     生产会损耗（裁切边角、烧制破损、来料不良），销售套装不损耗 ——
--     卖一套就是把组件原样扣出去。所以 `bom_component.scrap_rate` 是 BOM 独有的列，
--     套装组件行没有、也不该有（给套装加一个永远为 0 的列，只会让「这个数字为什么是 0」
--     变成一个永远没人回答的问题）。
--
--   * **差异 3：BOM 产出实物，套装产出订单行。**
--     后果是**冻结的历史不同**：套装冻结的是 09 订单展开出的组件子行；
--     BOM 冻结的是「这一单加工单当时按哪一版领的料、损耗率是多少」——
--     08 加工单与 06 采购都要按当时的损耗率回溯「这批料为什么是这个数」。
--     所以本模块的触发器比 V23 更严：已发布版本的**组件用量与损耗率一并冻结**
--     （V23 的 guard_bundle_component_published 只挡增删改，本文件连
--     「改单位用量 / 改损耗率」都挡在同一条触发器里）。
--
-- ============================================================ 一、为什么仍然分「BOM」与「BOM 版本」两张表
--
-- 与 V23 第一节同源，理由逐条成立（换的是名字，逻辑没变）：
--   1. 「同一 BOM 同一时刻至多一个生效版本」是**同 BOM 内跨行**的约束。
--      两行合一就没有 bom_id 这个分组键，重叠判定退化成全表两两比较。
--   2. BOM 编码是**租户内唯一**且建后不可改（它是 06 采购建议与 08 加工单的稳定引用），
--      版本号只要求**同 BOM 内**唯一。合并后 UNIQUE(tenant_id,code) 与
--      UNIQUE(tenant_id,code,version_no) 互相打架。
--   3. 归档一个 BOM 不该动它的历史版本 —— 08 加工单要读已归档 BOM 的历史版本。
--
-- ============================================================ 二、生效区间的裁决规则 R1–R5（沿用 V23，不改判）
--
-- BOM 的时间语义与套装**完全一致**（都是「某一时刻该用哪一版」），
-- 所以 R1–R5 逐字沿用 V23 文件头第二节，不另立一套：
--
--   **R1（半开区间）**：[effective_from, effective_to)，to 为 NULL = +∞。
--     [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠。
--   **R2（重叠一律拒绝，不做裁决）**：同一 BOM 下任意两条**已发布**版本区间不得重叠。
--     冲突时报错点名与哪一版（version_no + 区间）撞了。
--     为什么 BOM 更不能「自动裁决」：08 加工单是**实物领料**，用错一版 BOM
--     就是领错料、算错成本，而且错在仓库里撤不回来。
--   **R3（只有已发布版本参与判定）**：草稿区间随便重叠，发布那一刻才检查。
--   **R4（当前生效版本的确定性查询）**：
--     published AND from <= t AND (to IS NULL OR t < to)，由 R2 保证至多命中一行。
--   **R5（已发布版本只能向前关闭）**：只允许把 effective_to 从 NULL（或较晚值）
--     收紧到一个**仍在未来**的时刻。
--
-- ============================================================ 三、损耗率与用量的语义（本模块新钉死的两条）
--
-- 蓝图写了「单位用量与损耗率」但**没有规定单位与算法**。本文件钉死：
--
--   **规则 R6（scrap_rate 是「百分数」，不是「比率」）**：
--     `scrap_rate = 5` 表示**损耗 5%**，`scrap_rate = 0` 表示无损耗。
--     为什么不存比率（0.05）而存百分数（5）：
--     运营在页面上填的是「5%」这个心理数，转成比率要悄悄除以 100；
--     而一个填错的 5（当比率读就是 500% 损耗）会让 06 采购量翻 6 倍且**不报错**。
--     存百分数让「填 5」只有一个自然解释，也让下一条的上界自解释。
--     数据库侧因此有 `scrap_rate <= 100` 的 CHECK：100% 损耗 = 这个组件
--     一丁点都用不上、只需要补损耗，那已经不是一个有效的 BOM 行了
--     （真要这样表达，请把这一行删掉并在成品上记废料，不该让「领料 0 件」出现在加工单上）。
--     **禁浮点**：BigDecimal + NUMERIC(7,4)，服务层全程 BigDecimal，不用 double。
--
--   **规则 R7（需求量 = 单位用量 × 产量 × (1 + 损耗率/100)，保留 6 位小数 HALF_UP）**：
--     这个「生产 N 个成品需要多少某组件」的**单层**计算属于本模块
--     （蓝图的「输入」含单位用量与损耗率，算不出需求量这两个数就是摆设）。
--     但**多级递归展开不归本模块** —— 那是 3.5.2.2（BOM 展开）的独立职责，
--     它要在展开树上把子 BOM 当作父 BOM 的组件继续往下走。
--     本模块只算一层，且**只给「需要多少」这个精确数，不给「订多少」**：
--     取整成采购量、补安全库存、判缺口是 3.5.2.2 + 06 的事（蓝图 3.5.2.2 原话：
--     「展开结果是建议不是事实」）。把 6 位小数的需求量提前取整成整数，
--     就是在下游拿到单据之前先替它做了决定。
--
-- ============================================================ 四、发布后不可变的强制位置
--
-- 与 V23 第三节同构，三层各钉一遍：
--   1. 服务层（BomVersionService）：发布后拒绝改名 / 改版本号 / 改生效起点 / 改组件。
--   2. CHECK 约束：PUBLISHED 必须带 published_by + published_at。
--   3. 触发器（CHECK 在 PostgreSQL 里可被 NOT VALID 或迁移脚本绕过，触发器不行）：
--      guard_bom_version_published（BEFORE UPDATE，逐列比较）
--      refuse_bom_version_published_delete（BEFORE DELETE）
--      guard_bom_component_published（BEFORE INSERT/UPDATE/DELETE）
--      guard_bom_version_interval（BEFORE INSERT/UPDATE，区间重叠）
--
-- ============================================================ 1. BOM 档案
--
-- 一行 = 一个租户的一个 BOM。它只回答「这个 BOM 存在吗、编码是什么、产什么成品、还在用吗」。
CREATE TABLE bom (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- BOM 编码：租户内唯一且**建后不可改**（服务层强制）。它是 06 采购建议与 08 加工单的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  -- **成品 SKU**（差异 1）：BOM 产出的是实物成品，所以成品必须是一个真实存在的 SKU。
  -- 与 3.5.1.1 对照：bundle 没有任何 sku 外键 —— 虚拟组合不占 SKU 行。
  -- 建后不可改（服务层强制）：改成品等于让「这一版算出来的料要装到别的东西上」，
  -- 而已发布版本算出来的需求量已经被 08 加工单按实物领走了。
  output_sku_id uuid NOT NULL,
  -- 状态。ARCHIVED 只表示「不再新建版本」，历史版本照常可读（08 加工单要读）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  -- created_at 是业务时间（运营什么时候建的 BOM），updated_at 是系统时间。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一租户同编码只能有一个 BOM：编码重复时「加工单里的这个 BOM 指哪个」无法回答。
  UNIQUE(tenant_id,code),
  -- 成品必须是本租户真实存在的 SKU（复合外键），否则「按这个 BOM 生产」生产的是一个不存在的东西。
  -- 用 RESTRICT 而不是 CASCADE：删一个被 BOM 指向的 SKU 应该被拒绝，
  -- 让运营显式处理，而不是悄悄把这个 BOM 变成指向空气的清单。
  FOREIGN KEY(tenant_id,output_sku_id) REFERENCES sku(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 2. BOM 版本
--
-- 一行 = 一个 BOM 的一个版本。这是本模块的事实表：草稿可改，发布后按 R5 与第四节的触发器钉死。
CREATE TABLE bom_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, bom_id uuid NOT NULL,
  -- 版本号。同 BOM 内唯一（运营手打的「v1」「2026-春季」），**建后不可改**。
  version_no varchar(32) NOT NULL CHECK(btrim(version_no)<>''),
  -- 名称（挂在版本上而不是 BOM 上）：与 V23 同理，08 加工单冻结的也是当时的名称。
  name varchar(200) NOT NULL CHECK(btrim(name)<>''),
  -- 生效区间 [effective_from, effective_to)。effective_to 为 NULL = 一直有效（规则 R1）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  -- 草稿 → 已发布，单向。撤回发布不是合法动作（触发器在第四节钉死）。
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  published_by uuid, published_at timestamptz,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同 BOM 同版本号只能有一行：两行时「v2 是哪一个」无法回答，而区间判定会两边都算数。
  UNIQUE(tenant_id,bom_id,version_no),
  -- 空区间没有意义（等于「从不生效」）。左闭右开，所以 to 必须严格大于 from。
  CONSTRAINT bom_version_range_ordered
    CHECK (effective_to IS NULL OR effective_to > effective_from),
  -- 已发布必须有发布人与发布时刻：这一版会被 08 加工单按它算料、06 按它提建议。
  CONSTRAINT bom_version_published_needs_actor
    CHECK (status<>'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),
  -- 草稿不能带着发布痕迹。
  CONSTRAINT bom_version_draft_has_no_release
    CHECK (status<>'DRAFT' OR (published_by IS NULL AND published_at IS NULL)),
  FOREIGN KEY(tenant_id,bom_id) REFERENCES bom(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. 版本组件
--
-- 一行 = 一个版本里的一个组件 SKU。**行挂在版本上而不是 BOM 上**：
-- 「已发布版本的组件不可改」要求每条组件记录都能回答「我属于哪一版」。
CREATE TABLE bom_component (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, version_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- **单位用量**：每 1 个成品需要这个组件多少（规则 R7 的因子之一）。
  -- NUMERIC(19,6)：生产型卖家的组件是「0.5 米面料 + 2 颗螺丝」这类按计量单位组合，
  -- int 会逼着运营把 0.5 写成「1 件」，而 08 领料与 06 采购量都会照着这个假数走。
  -- 必须 > 0：数量为 0 的组件行是「配了一个组件但不消耗」，
  -- 08 加工单会领出一条 0 件、两边都答不出「这个组件到底要不要」。
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity > 0),
  -- **损耗率**（差异 2 + 规则 R6）：百分数，5 = 5%。默认 0（无损耗）而不是必填 ——
  -- 委外加工里「这个组件不损耗」是绝大多数情况，必填只会让人填一堆 0。
  -- 上界 100：见规则 R6 的论证（100% 损耗 = 这行不该存在）。
  scrap_rate numeric(7,4) NOT NULL DEFAULT 0
    CHECK(scrap_rate >= 0 AND scrap_rate <= 100),
  -- 组件角色是自由文本（主板 / 面料 / 包材…），只给人看，不参与任何判定。
  role text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一版本里同一个 SKU 只能有一行：两行时「这个组件要几件」无法回答，
  -- 而 06 采购建议只能取一列。
  UNIQUE(tenant_id,version_id,sku_id),
  FOREIGN KEY(tenant_id,version_id) REFERENCES bom_version(tenant_id,id) ON DELETE CASCADE,
  -- 组件 SKU 必须是本租户真实存在的 SKU，否则「按这个 BOM 领料」领的是一个不存在的东西。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 4. 触发器（纵深防线，见文件头第四节）
--
-- 已发布版本的逐列不可变：改名 / 改版本号 / 改归属 BOM / 改生效起点 / 改备注 / 改发布留痕
-- 一律拒绝；effective_to 只允许按规则 R5 向前关闭。
--
-- 名字按字母序：immutable 在 interval 之前，所以同一次 UPDATE 里「能不能改」先于
-- 「会不会撞区间」判定——报错先说最根本的那条。
CREATE FUNCTION guard_bom_version_published() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'PUBLISHED' THEN RETURN NEW; END IF;

 -- 已发布 → 别的状态不允许：发布是单向的。
 -- 撤回发布会让这一版从 08 加工单与 06 采购建议的依据里消失，
 -- 而加工单已经在仓库里按它领过料了，撤不回来。
 IF NEW.status<>'PUBLISHED' THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能撤回发布（版本 %）：08 加工单已经按当时的版本领过料，撤回会让「这批料当时按哪一版、什么损耗率算的」无从查证。要停止使用请把 BOM 归档，或让下一个版本接替',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 IF NEW.bom_id IS DISTINCT FROM OLD.bom_id THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改归属 BOM（版本 %）：BOM 编码是加工单与采购建议里的稳定引用，换 BOM 等于让这一版的历史归属失效。要换请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.version_no IS DISTINCT FROM OLD.version_no THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改版本号（% → %）：版本号是同 BOM 内的唯一标识，改它会让「v2 是哪一个」在历史数据里出现两个答案。要换请新建版本',
   OLD.version_no, NEW.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.name IS DISTINCT FROM OLD.name THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改名（版本 %）：发布后名称已经被 06 采购建议与 08 加工单引用，改名会让「当时生产的那版叫什么」查不到。要改名请新建版本并让新版本接替',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改生效起点（版本 %）：移动起点会追溯改写历史上生效的是哪一版，而 08 加工单领的料是按当时的版本算的',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.published_by IS DISTINCT FROM OLD.published_by
    OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改发布留痕（版本 %）：「谁在什么时候发布的」是这版可追溯的凭据，改掉它等于让发布这件事无法证伪',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.note IS DISTINCT FROM OLD.note THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能改备注（版本 %）：备注写在发布内容里，发布后与组件、名称一样不可变。要补充说明请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 -- effective_to 是唯一的例外，且只允许向前关闭（规则 R5）。
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '已发布的 BOM 版本不能重开生效区间（版本 %）：重新变成「一直有效」会让已经接替它的版本与它同时生效，规则 R2 立刻被破坏',
    OLD.version_no USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to THEN
   RAISE EXCEPTION '已发布的 BOM 版本不能把生效终点往后延（版本 %，% → %）：往后延会与已经接替它的版本重叠，「这个时刻该用哪一版算料」将不再唯一',
    OLD.version_no, OLD.effective_to, NEW.effective_to USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to <= now() THEN
   RAISE EXCEPTION '已发布的 BOM 版本不能把生效终点设成已经过去的时刻（版本 %，%）：那会追溯改写历史上生效的是哪一版，而 08 加工单领的料是按当时的版本算的',
    OLD.version_no, NEW.effective_to USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bom_version_published BEFORE UPDATE ON bom_version
  FOR EACH ROW EXECUTE FUNCTION guard_bom_version_published();

-- 已发布版本不可删（连同删 BOM 时的级联一起拦住）。
CREATE FUNCTION refuse_bom_version_published_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布的 BOM 版本不能删除（版本 %）：08 加工单已按当时的版本领过料，删掉这一版会让「这批料当时按哪一版、什么损耗率算的」无从查证。要停止使用请把 BOM 归档，而不是删版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_bom_version_published_delete BEFORE DELETE ON bom_version
  FOR EACH ROW EXECUTE FUNCTION refuse_bom_version_published_delete();

-- 组件挂在已发布版本上时不可增删改 —— 「已发布版本的组件不可改，只能新建版本」。
-- 与 V23 的同名触发器比，本条**多挡两件事**：改 unit_quantity 与改 scrap_rate。
-- 理由是 08 加工单领的是「单位用量 × 产量 × (1+损耗率)」算出来的数，
-- 改损耗率等于追溯改写当时领了多少料（差异 3）。
CREATE FUNCTION guard_bom_component_published() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_no text;
BEGIN
 IF TG_OP='DELETE' THEN
  SELECT status, version_no INTO parent_status, parent_no
    FROM bom_version WHERE tenant_id=OLD.tenant_id AND id=OLD.version_id;
 ELSE
  SELECT status, version_no INTO parent_status, parent_no
    FROM bom_version WHERE tenant_id=NEW.tenant_id AND id=NEW.version_id;
 END IF;
 IF parent_status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布版本的组件不可修改（版本 %）：08 加工单已按当时的单位用量与损耗率领过料，改组件（增删、换 SKU、改用量、改损耗率）会让「这批料当时为什么是这个数」无从查证。要改请新建一个版本并让新版本接替',
   parent_no USING ERRCODE='23514';
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER guard_bom_component_published
  BEFORE INSERT OR UPDATE OR DELETE ON bom_component
  FOR EACH ROW EXECUTE FUNCTION guard_bom_component_published();

-- 区间重叠拒绝（规则 R2）。只有已发布版本参与判定（规则 R3），
-- 所以草稿之间随便重叠，发布的那一刻才被检查。
CREATE FUNCTION guard_bom_version_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash record;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
 SELECT v.version_no, v.effective_from, v.effective_to INTO clash
   FROM bom_version v
  WHERE v.tenant_id=NEW.tenant_id
    AND v.bom_id=NEW.bom_id
    AND v.id<>NEW.id
    AND v.status='PUBLISHED'
    -- 半开区间比较：'[)' 让 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠（规则 R1）。
    AND tstzrange(v.effective_from, v.effective_to, '[)')
        && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY v.effective_from, v.id
  LIMIT 1;
 IF FOUND THEN
  RAISE EXCEPTION '生效区间与已发布版本 % 冲突（本版本 %：% → %）：同一个 BOM 在同一时刻只能有一个生效版本，否则 08 加工单与 06 采购建议都答不出「这一刻该按哪一版算料」。已发布版本的区间不能改，请调整本版本的区间或把接替版本的终点设为 % 的起点',
   clash.version_no, NEW.version_no, NEW.effective_from,
   coalesce(NEW.effective_to::text, '一直有效'),
   clash.effective_from USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bom_version_interval BEFORE INSERT OR UPDATE ON bom_version
  FOR EACH ROW EXECUTE FUNCTION guard_bom_version_interval();

-- ============================================================ 5. 索引
--
-- BOM 列表页：按状态 + 更新时间倒序。
CREATE INDEX bom_list ON bom(tenant_id, status, code);
-- 版本列表页的主查询形状：按 BOM 读全部版本。
CREATE INDEX bom_version_by_bom ON bom_version(tenant_id, bom_id, status, effective_from DESC, id);
-- **「当前生效版本」的确定性查询形状**（规则 R4，06 采购建议与 08 加工单都走它）。
CREATE INDEX bom_version_effective ON bom_version(tenant_id, bom_id, effective_from, effective_to)
  WHERE status='PUBLISHED';
-- 工作台默认视图：草稿优先、改动最近的在上。
CREATE INDEX bom_version_drafts ON bom_version(tenant_id, status, updated_at DESC, id DESC);
-- 已发布版本里「还没接替、还一直有效」的那些——运营要盯着它们接替。
CREATE INDEX bom_version_open_ended ON bom_version(tenant_id, bom_id, effective_from)
  WHERE status='PUBLISHED' AND effective_to IS NULL;
-- 版本详情页的组件清单。
CREATE INDEX bom_component_by_version ON bom_component(tenant_id, version_id, sku_id);
-- **倒排查询**：某个 SKU 被哪些 BOM 用着（改组件前要先看谁在用；
--   06 采购建议算「这个料要买给谁」也读这个方向）。
CREATE INDEX bom_component_by_sku ON bom_component(tenant_id, sku_id);
-- 倒排到 BOM 档案：某个成品被哪些 BOM 声明为产出（防止同一个 SKU 被两个 BOM 同时声明）。
CREATE INDEX bom_by_output_sku ON bom(tenant_id, output_sku_id);

-- ============================================================ 6. RLS 与授权
--
-- 三张表全部租户级数据（BOM 是租户共享的生产定义，与 V18 的媒体库、
-- V23 的套装同一口径，不套 V3 的 owner_id/department_id）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['bom','bom_version','bom_component'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON bom, bom_version, bom_component TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON bom, bom_version, bom_component TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **06 采购与供应**（加工采购）：取 BOM + 当前生效版本
--     （GET /catalog/boms/{id}/effective?at=...，规则 R4 的时刻查询），
--     按「单位用量 × 需求量 × (1+损耗率)」算要买多少料 —— 但**取整成采购量、
--     判缺口、生成采购单是 06 的事**，本模块只给精确需求量（规则 R7）。
--   * **08 仓储作业**（组装单）：按当时的生效版本冻结领料明细，
--     记录当时的单位用量与损耗率，之后要能回溯「这批料为什么是这个数」。
--   * **3.5.2.2 BOM 展开**：本表已具备它需要的形状（组件挂版本、
--     unit_quantity + scrap_rate）。**多级递归展开、子 BOM 当作组件、
--     按仓库过滤、缺口计算全部归 3.5.2.2**，本模块刻意不做 ——
--     本表的 bom_component 只引用 SKU，不引用 BOM（与 V23 组件只引用 SKU 同理：
--     嵌套 BOM 在数据上靠「子 BOM 的成品 SKU 出现在父 BOM 的组件行里」表达，
--     而那一层的判定是 3.5.2.2 的职责，本文件不预先摊平它）。
--   * **3.5.1.x 套装**：分表不碰。套装销售用（虚拟扣组件、不持有独立库存），
--     BOM 生产用（产实物成品、持有自己的 SKU）。两者生命周期与冻结对象都不同。
