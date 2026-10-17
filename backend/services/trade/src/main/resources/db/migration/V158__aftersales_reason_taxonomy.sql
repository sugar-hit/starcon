-- 11.1.2.1 售后原因分类（卷 11 售后与退货的原因口径）。
--
-- 蓝图 11-aftersales-and-returns.md 第 99-104 行原文：
--   职责：维护售后原因分类树（质量问题、描述不符、尺寸不符、物流损坏、买家原因、误购、
--         欺诈退货），支持多选与平台原因码映射，驱动后续的质量归因分析。
--   输入：原因分类维护；平台原因码映射（2.1.2.1）。
--   输出：原因分类记录；报表用统一口径。
--   关系：上游 = 2 平台映射；下游 = 11.5.2 质量分析、6 采购（供应商索赔）。
--         **原因口径不统一会让退货率分析失真 —— 必须强制选择分类而不是留自由文本。**
--
-- 归属：**trade 服务**（蓝图 11 卷 L2 表 11.1 售后受理 → 主责服务 trade）。
--
-- 本迁移**只新建表、序列、函数与触发器，零 ALTER 零 DROP 既有表** ——
-- aftersales_case 簇（V156）是 11.1.1.1 车道的写入所有者，本模块只**读**它，
-- 跨车道 DDL 回归面 = 0。刻意**不加外键**指向 aftersales_case：
-- 加 FK 会让本迁移对别的车道的表产生 DDL 依赖，而同批另有车道也在往 trade 落迁移。
-- 售后单存在性由触发器 guard_aftersales_attribution_category 真查一次校验
-- （DB 层查，不是 Java 层查 —— 绕过服务层直写数据库同样挡得住）。
--
-- ===========================================================================
-- 一、加粗句「必须强制选择分类而不是留自由文本」的**三层**落地
-- ===========================================================================
-- 这句话不能只做成「有一个非空的 reason 字段」—— 那样只是把自由文本换了个名字。
-- 本迁移把「强制」拆成三条**互相独立、任何一条都单独成立**的约束：
--
--   (1) **不允许空归因**、**恰好一个主因**：归因是一个「集合」（attribution_set）
--       + 一组「成员」（attribution_item）。集合必须**至少一个成员**且
--       **恰好一个 is_primary**。这两条由 **DEFERRABLE INITIALLY DEFERRED 的
--       CONSTRAINT TRIGGER 在 commit 时判定** —— 选「延迟到提交」而不是普通 BEFORE 触发器，
--       是因为一次归因是「先插集合、再插 N 个成员」的**多语句**写入：
--       BEFORE 触发器在插第一个成员时只会看到 0 个成员，会把合法写入全部误杀。
--       延迟到提交才有意义：**提交的瞬间必须已经成立**。
--       ⇒ 绕过后端服务、直接开 psql 手写 SQL 也**存不进**一个没有成员的归因，
--         也存不进「两个主因」或「零个主因」的归因。
--
--   (2) **归因只能指向真实且启用的分类**：成员上的 category_id 由
--       guard_aftersales_attribution_category 真查 aftersales_reason_category，
--       分类必须**属于同一租户**且 **ACTIVE**。用「指向已停用/别人的分类」糊弄过去
--       会让 11.5.2 的退货率统计把一批数据算进一个运营已经宣布作废的口径里。
--
--   (3) **自由文本只是补充**：自由文本落在**集合**上（attribution_note），
--       而不是在成员上；且**集合必须先有结构化成员才谈得上 note**（由 (1) 保证）。
--       换句话说：**没有分类就不可能只留下一段文字**，因为一个零成员的集合根本提交不了。
--       这正是「不允许只写自由文本」的物理含义。
--
-- ⚠️ 刻意**没有**「单次归因最多几个成员」的上限之外的语义：
--   多选是职责句明确要求的（「支持多选」），上限设在 10（见服务层）只为了防止
--   「把整棵树 47 个叶子全勾上」这种没有归因意义的写法 —— 蓝图要求的是
--   「不同原因分类要有权重或主次」，没有主次的全选会让归因分析退化成无意义的均匀分布。
--
-- ===========================================================================
-- 二、分类树：**可维护的结构**，不是硬编码枚举表
-- ===========================================================================
-- 层级 + 编码 + 启用/停用 + 排序，四件事都是真列：
--   * 层级：parent_id（自引用，**同表** FK 不算跨模块依赖）+ depth（触发器维护的真列，
--     可进 WHERE 与 ORDER BY）+ 三层上限。三层足够表达
--     「质量问题 → 外观缺陷 / 功能失效」，又不会让运营维护出一棵没法看的树。
--   * 编码：category_code 租户内唯一，**报表口径的稳定锚点**。改名不该改编码 ——
--     11.5.2 与 6 采购的历史数据是按编码对齐的。
--   * 启用/停用：category_status。停用是**口径变更**：停用后不再可被新归因选中，
--     但历史归因**仍然指向它**（成员表上的外键是 RESTRICT，删不掉也改不了指向）。
--   * 排序：sort_order + id 次序，**报表导出的确定性由它保证**（见索引节的说明）。
--
-- ⚠️ **蓝图点名的七类怎么进来**：它们是**基线词表**，由服务层按租户**幂等落库**
--   （AftersalesReasonService.ensureBaseline，ON CONFLICT DO NOTHING），
--   而不是写死在 Java 数组里当数据源。落库之后它们就是**普通的租户行** ——
--   能改名、能排序、能停用、能加子节点，能加树外的新根。
--   照抄 V99 把平台来源提前写进 CHECK 的做法：词表先给出，但**结构归运营维护**。
--   七类逐字对齐蓝图原文，且 root_kind 的 CHECK 就是这份词表
--   （根节点必须声明自己属于七类中的哪一类，子节点不声明 —— 见 (parent_id IS NULL) = (root_kind IS NOT NULL)）。
--   **这条等式让「根必须归类、子不许冒充根」在 DB 层不可能出错**：
--   建一个没有 root_kind 的根，报表就不知道它该汇总到哪一类；
--   给一个子节点编一个 root_kind，两条计数会被重复计入同一个口径。
--
-- ===========================================================================
-- 三、平台原因码映射：**trade 自己的表**，不 join channel
-- ===========================================================================
-- 实查集成头：上游 2.1.2.1 的 platform_enum_mapping / platform_enum_override
--   在 **channel 服务**的独立库 starcon_channel
--   （V1__channel_registry_connections_and_shops.sql / V3__platform_enum_tenant_override.sql），
--   **不是 trade 库**。按 AGENTS.md 模块边界纪律：**禁止跨服务 SQL / FK / 视图**。
--   ⇒ 本模块**不能** join channel 的表，也不能建跨库 FK。
--   ⇒ 正确做法是在 trade 库里建**自己的**映射表（形状与 channel 的枚举映射语义一致：
--      内部口径 ⇄ 平台码，双向可查，带来源与采集时刻）。
--   同步器将来按**同一形状**写入时，**表结构与查询都不用改**。
--   capabilities 里如实报 crossServiceReadAvailable=false + 归属模块号 2.1.2.1。
--
-- 与 V150/V156 的同一套纪律：mapping_source 区分 TENANT_MAINTAINED（租户自维护）
--   与 MODULE_2_ENUM_MAPPING（同步器写入），后者必须带 source_reference
--   （哪份来源的哪一段），否则「这行是同步器写的」只是口头声明。
--   captured_at 是**采集时刻**不是登记时刻（填 now() 会让三个月前的映射被当成今天的）。
-- ===========================================================================

-- ===========================================================================
-- 四、原因分类树
-- ===========================================================================
CREATE TABLE aftersales_reason_category (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 父分类。**自引用外键**（同表，不跨模块）⇒ 树结构由 DB 保证真的有父节点。
  -- ON DELETE RESTRICT：父分类被删时若还有子分类，删除直接失败 ——
  -- 宁可报错也不能留下一批「指向不存在父节点的孤儿」。
  parent_id uuid REFERENCES aftersales_reason_category(id) ON DELETE RESTRICT,

  -- 编码：租户内唯一，**报表口径的稳定锚点**（改名不改编码）。
  category_code varchar(48) NOT NULL CHECK(btrim(category_code) <> ''),
  category_name varchar(128) NOT NULL CHECK(btrim(category_name) <> ''),

  -- 根节点归类：蓝图点名的七类。非根节点为 NULL。
  -- ⚠️ 等式 CHECK 让「根必须归类、子不许冒充根」在 DB 层不可能出错（见文件头第二节）。
  root_kind varchar(32) CHECK(root_kind IS NULL OR root_kind IN (
      'QUALITY_DEFECT','DESCRIPTION_MISMATCH','SIZE_MISMATCH','LOGISTICS_DAMAGE',
      'BUYER_REASON','MISTAKE_BUY','FRAUD_RETURN'
  )),

  -- 停用是口径变更：停用后不再可被新归因选中，历史归因仍指向它。
  category_status varchar(12) NOT NULL DEFAULT 'ACTIVE'
    CHECK(category_status IN ('ACTIVE','DISABLED')),

  -- 排序：报表导出顺序由 (sort_order, code, id) 唯一确定，不依赖插入顺序。
  sort_order integer NOT NULL DEFAULT 0,

  -- 层级：**触发器维护的真列**（父 depth + 1，根为 1）。可进 WHERE 与 ORDER BY。
  depth smallint NOT NULL DEFAULT 1,

  description varchar(500) NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,category_code),
  CHECK(depth BETWEEN 1 AND 3),
  CHECK((parent_id IS NULL) = (root_kind IS NOT NULL)),
  -- 非根节点不许编 root_kind（等式的推论，单独写出来是为了让报错更直白）。
  CHECK(parent_id IS NOT NULL OR root_kind IS NOT NULL),
  CHECK(parent_id IS NULL OR parent_id <> id)
);

-- ===========================================================================
-- 五、归因：集合 + 成员（加粗句的落点，见文件头第一节）
-- ===========================================================================
-- 一次归因 = 一个集合。改归因是**追加一个新集合**并把旧的标记 superseded_at，
-- 与 V156 的责任人流转同一套纪律：**纠纷举证需要知道「当初为什么这么判」**，
-- 抹掉前一版等于毁掉证据。
CREATE TABLE aftersales_reason_attribution_set (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 售后单。**刻意不加外键**（V156 的同一条纪律：不 ALTER 别的车道的表、
  -- 不产生跨车道 DDL 依赖）。存在性与租户归属由触发器真查 aftersales_case 校验。
  case_id uuid NOT NULL,

  -- 自由文本：**只是结构化归因之外的补充说明**（文件头第一节 (3)）。
  -- 它挂在集合上而不是成员上，且一个零成员的集合提交不了
  -- ⇒ 「只写了一段话没选分类」在物理上不存在。
  attribution_note text NOT NULL DEFAULT '' CHECK(length(attribution_note) <= 1000),

  -- NULL = 当前生效的那一版。部分唯一索引保证**一张售后单只有一版生效归因**。
  superseded_at timestamptz,

  attributed_by uuid NOT NULL,
  attributed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

-- 加粗句第一层：**恰好一版生效归因**。
-- ⚠️ 必须是**部分唯一索引**而不是表级 UNIQUE 约束：PostgreSQL 不允许在表级
--   `UNIQUE(...)` 后面写 WHERE（语法错误 42601），部分唯一只能是独立索引。
--   它挡的是「同一张单同时有两版生效归因」—— 报表按单取当前归因时会拿到两行，
--   而退货率分析的分母会因此翻倍。
CREATE UNIQUE INDEX aftersales_reason_set_current_uq
  ON aftersales_reason_attribution_set(tenant_id, case_id) WHERE superseded_at IS NULL;

-- 归因成员（多选）。**append-only**：改了不改写、补一版不删旧版。
CREATE TABLE aftersales_reason_attribution_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  set_id uuid NOT NULL REFERENCES aftersales_reason_attribution_set(id) ON DELETE RESTRICT,
  -- 冗余 case_id：报表「按售后单取当前归因」是最热的查询形状，
  -- 冗余一列让它只走一个索引（集合 → 成员本来要多一次 join）。
  -- 由触发器钉死与所属集合的 case_id 一致（guard_aftersales_attribution_category）。
  case_id uuid NOT NULL,
  category_id uuid NOT NULL REFERENCES aftersales_reason_category(id) ON DELETE RESTRICT,

  -- 恰好一个主因（加粗句第一层，由提交期约束触发器钉死）。
  is_primary boolean NOT NULL DEFAULT false,

  -- 权重：多选时「这个原因占多少」的显式表达。**必须给出**而不是默认为 1 ——
  -- 归因分析无意义的最常见形态是「什么都勾了、权重全是 1」，那等于没归因。
  -- 上限 1（单个原因不可能超过全部权重），下限 > 0（权重为 0 的成员应当直接不勾）。
  weight numeric(19,6) NOT NULL CHECK(weight > 0 AND weight <= 1),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,set_id,category_id)
);

-- ===========================================================================
-- 六、平台原因码映射（trade 自有表，不 join channel —— 见文件头第三节）
-- ===========================================================================
CREATE TABLE aftersales_reason_platform_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  category_id uuid NOT NULL REFERENCES aftersales_reason_category(id) ON DELETE RESTRICT,

  -- 平台来源：与 sales_order.order_source（V99，同库只读）的 CHECK 逐字对齐，
  -- **刻意不用 channel 的 shop 维度** —— 卷 2 的店铺在独立库，禁止跨服务读。
  platform_source varchar(24) NOT NULL CHECK(platform_source IN (
      'AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688'
  )),
  platform_reason_code varchar(64) NOT NULL CHECK(btrim(platform_reason_code) <> ''),
  platform_reason_name varchar(128) NOT NULL DEFAULT '',

  mapping_source varchar(32) NOT NULL CHECK(mapping_source IN (
      'TENANT_MAINTAINED','MODULE_2_ENUM_MAPPING'
  )),
  -- 写成 MODULE_* 时必须指明是哪份来源的哪一段，否则「这行是同步器写的」只是口头声明。
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 采集时刻（照抄 V150 的 captured_at 纪律：填 now() 会让三个月前的映射被当成今天的）。
  captured_at timestamptz NOT NULL,

  mapping_status varchar(12) NOT NULL DEFAULT 'ACTIVE'
    CHECK(mapping_status IN ('ACTIVE','DISABLED')),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一个平台的同一个原因码只能映射到一个内部口径：两边都映射会让
  -- 报表按码汇总时同一批退货被重复计入两行。
  UNIQUE(tenant_id,platform_source,platform_reason_code),
  CHECK(mapping_source = 'TENANT_MAINTAINED' OR btrim(source_reference) <> '')
);

-- ===========================================================================
-- 七、护栏触发器
-- ===========================================================================
-- ① 树的结构完整性：深度、停用父节点、成环。
--    自引用外键能保证「父节点存在」，但保证不了「父节点是 ACTIVE」也挡不住
--    「A 挂到 B、B 挂到 A」这种环 —— 环会让遍历树的前端直接死循环。
CREATE FUNCTION guard_aftersales_reason_category_tree() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_parent_status varchar(12);
  t_parent_depth smallint;
  t_ancestor uuid;
BEGIN
  IF NEW.parent_id IS NULL THEN
    NEW.depth := 1;
    RETURN NEW;
  END IF;

  SELECT category_status, depth INTO t_parent_status, t_parent_depth
    FROM aftersales_reason_category
   WHERE tenant_id = NEW.tenant_id AND id = NEW.parent_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '父分类不存在或不属于当前租户：parent_id=%', NEW.parent_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF t_parent_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '不可把分类挂在已停用的父分类下（parent_code 所在节点已停用）'
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_parent_depth >= 3 THEN
    RAISE EXCEPTION '分类树最多三层，父节点已在第 % 层', t_parent_depth
      USING ERRCODE = 'check_violation';
  END IF;
  NEW.depth := t_parent_depth + 1;

  -- 成环检测：从父节点往上走，看有没有走回 NEW.id 自己。
  -- ⚠️ 深度上限已经把行走步数限制在 2 步内，不会死循环。
  t_ancestor := NEW.parent_id;
  WHILE t_ancestor IS NOT NULL LOOP
    IF t_ancestor = NEW.id THEN
      RAISE EXCEPTION '分类树不允许成环：把 % 挂到它自己的后代下面', NEW.id
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT parent_id INTO t_ancestor
      FROM aftersales_reason_category
     WHERE tenant_id = NEW.tenant_id AND id = t_ancestor;
  END LOOP;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_reason_category_tree
  BEFORE INSERT OR UPDATE OF parent_id ON aftersales_reason_category
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reason_category_tree();

-- ② 归因成员必须指向**同租户、ACTIVE** 的分类，且 case_id 与所属集合一致，
--    售后单必须真实存在（V156 的表，只读）。
--    这一层是「绕过后端直写数据库」时唯一还生效的防线。
CREATE FUNCTION guard_aftersales_attribution_category() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(12);
  t_set_case uuid;
BEGIN
  SELECT case_id INTO t_set_case
    FROM aftersales_reason_attribution_set
   WHERE tenant_id = NEW.tenant_id AND id = NEW.set_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '归因集合不存在或不属于当前租户：set_id=%', NEW.set_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_set_case <> NEW.case_id THEN
    RAISE EXCEPTION '归因成员的 case_id 与所属集合不一致：集合=% 成员=%',
      t_set_case, NEW.case_id USING ERRCODE = 'check_violation';
  END IF;

  -- 售后单必须真实存在且属于当前租户（V156 的表，只读真查，不建 FK）。
  PERFORM 1 FROM aftersales_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：case_id=%', NEW.case_id
      USING ERRCODE = 'no_data_found';
  END IF;

  SELECT category_status INTO t_status
    FROM aftersales_reason_category
   WHERE tenant_id = NEW.tenant_id AND id = NEW.category_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '原因分类不存在或不属于当前租户：category_id=%', NEW.category_id
      USING ERRCODE = 'no_data_found';
  END IF;
  IF t_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '不可归因到已停用的原因分类（category_id=%）', NEW.category_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_attribution_category
  BEFORE INSERT OR UPDATE ON aftersales_reason_attribution_item
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_attribution_category();

-- ③ **加粗句的提交期判定**：一个归因集合必须至少一个成员、且恰好一个主因。
--    ⚠️ 用 CONSTRAINT TRIGGER + DEFERRABLE INITIALLY DEFERRED：
--       一次归因是「插集合 → 插 N 个成员」的多语句写入，
--       非延迟触发器在插第一个成员时只会数到 0 个成员，会把**合法写入全部误杀**。
--       延迟到提交才有意义：提交的**瞬间**必须已经成立。
--    ⇒ 绕过后端服务直接写 SQL，也存不进「没有成员」「两个主因」「零个主因」的归因。
--
--    判定逻辑抽成一个**普通函数**（而不是把 SQL 抄进两个触发器函数）：
--    两处抄一遍的话，改了一处忘了另一处，两个触发器就会给出不同的结论，
--    而「哪个是真的」没人说得清。
CREATE FUNCTION assert_aftersales_reason_attribution_set(p_tenant uuid, p_set uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  t_total integer;
  t_primary integer;
BEGIN
  SELECT count(*), count(*) FILTER (WHERE is_primary)
    INTO t_total, t_primary
    FROM aftersales_reason_attribution_item
   WHERE tenant_id = p_tenant AND set_id = p_set;

  IF t_total = 0 THEN
    RAISE EXCEPTION '归因必须至少选择一个原因分类（蓝图：必须强制选择分类而不是留自由文本）。set_id=%', p_set
      USING ERRCODE = 'check_violation';
  END IF;
  IF t_primary <> 1 THEN
    RAISE EXCEPTION '归因必须有且仅有一个主因，当前有 % 个主因（共 % 个原因）。set_id=%',
      t_primary, t_total, p_set USING ERRCODE = 'check_violation';
  END IF;
END
$$;

-- 集合侧：集合本身被写入（新建 / 标记失效）时重判该集合。
CREATE FUNCTION guard_aftersales_reason_attribution_set() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM assert_aftersales_reason_attribution_set(NEW.tenant_id, NEW.id);
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_guard_aftersales_reason_attribution_set
  AFTER INSERT OR UPDATE ON aftersales_reason_attribution_set
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reason_attribution_set();

-- ④ 成员侧同样挂一个延迟触发器：集合本身没动、但成员被改动时也要重判一次
--    （否则「先建集合+成员、再把全部成员删掉」这条路能绕开 ③）。
-- ⚠️ 这里传的是 **NEW.set_id** 而不是 NEW.id：
--    在成员表上 NEW.id 是**成员**的 id，把它当 set_id 去数成员必然数出 0 个，
--    于是**每一次合法归因**都会被判成「没有选择任何原因分类」。
CREATE FUNCTION guard_aftersales_reason_attribution_item_set() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_tenant uuid;
  t_set uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN
    t_tenant := OLD.tenant_id;
    t_set := OLD.set_id;
  ELSE
    t_tenant := NEW.tenant_id;
    t_set := NEW.set_id;
  END IF;
  PERFORM assert_aftersales_reason_attribution_set(t_tenant, t_set);
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_guard_aftersales_reason_attribution_item
  AFTER INSERT OR UPDATE OR DELETE ON aftersales_reason_attribution_item
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reason_attribution_item_set();

-- ⑤ 平台映射必须指向同租户、ACTIVE 的分类（与 ② 同一套理由）。
CREATE FUNCTION guard_aftersales_reason_platform_mapping() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(12);
BEGIN
  SELECT category_status INTO t_status
    FROM aftersales_reason_category
   WHERE tenant_id = NEW.tenant_id AND id = NEW.category_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '原因分类不存在或不属于当前租户：category_id=%', NEW.category_id
      USING ERRCODE = 'no_data_found';
  END IF;
  IF t_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '不可把平台原因码映射到已停用的原因分类（category_id=%）', NEW.category_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_reason_platform_mapping
  BEFORE INSERT OR UPDATE OF category_id ON aftersales_reason_platform_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reason_platform_mapping();

-- ⑥ 删除分类的护栏：有子分类或已有归因时**不许删**。
--    自引用 FK 已经挡住「有子分类」；这里把「已被归因引用」也变成一条明确的报错，
--    而不是让它从成员表的 RESTRICT 外键冒出来（文案与根因对不上号）。
CREATE FUNCTION guard_aftersales_reason_category_delete() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_used integer;
BEGIN
  SELECT count(*) INTO t_used
    FROM aftersales_reason_attribution_item
   WHERE tenant_id = OLD.tenant_id AND category_id = OLD.id;
  IF t_used > 0 THEN
    RAISE EXCEPTION '该原因分类已被 % 条归因引用，不能删除。要停用请改状态 —— 停用后历史归因仍会指向它',
      t_used USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) INTO t_used
    FROM aftersales_reason_platform_mapping
   WHERE tenant_id = OLD.tenant_id AND category_id = OLD.id;
  IF t_used > 0 THEN
    RAISE EXCEPTION '该原因分类已被 % 条平台原因码映射引用，不能删除', t_used
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN OLD;
END
$$;

CREATE TRIGGER trg_guard_aftersales_reason_category_delete
  BEFORE DELETE ON aftersales_reason_category
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_reason_category_delete();

-- ===========================================================================
-- 八、索引：每一条都对应一个真实会被跑的查询形状
-- ===========================================================================
-- ① 分类树整树读取（前端一次拿全 + 稳定排序）。
--    ⚠️ 排序键是 (sort_order, category_code, id) 而不是单独的 sort_order ——
--    sort_order 允许重复，单靠它排会让同一层的兄弟节点顺序**不确定**，
--    而蓝图要求「报表用统一口径」：两次导出同一批数据必须逐行相同。
CREATE INDEX aftersales_reason_category_by_tree
  ON aftersales_reason_category(tenant_id, parent_id, sort_order, category_code, id);
-- ② 按根口径汇总（11.5.2 质量分析的主查询形状）：按七类之一 + 生效归因计数。
CREATE INDEX aftersales_reason_category_by_root_kind
  ON aftersales_reason_category(tenant_id, root_kind) WHERE root_kind IS NOT NULL;
-- ③ 当前生效的归因集合按售后单取（详情页 / 改归因页的热查询）。
--    部分索引 superseded_at IS NULL：历史版本不占索引空间。
CREATE INDEX aftersales_reason_set_current_by_case
  ON aftersales_reason_attribution_set(tenant_id, case_id) WHERE superseded_at IS NULL;
-- ④ 按批归因时间倒序（报表区间筛选）。
CREATE INDEX aftersales_reason_set_by_attributed_at
  ON aftersales_reason_attribution_set(tenant_id, attributed_at DESC);
-- ⑤ 成员按集合取（③ 的第二步）与按分类反查（报表口径的另一半）。
CREATE INDEX aftersales_reason_item_by_set
  ON aftersales_reason_attribution_item(tenant_id, set_id);
CREATE INDEX aftersales_reason_item_by_category
  ON aftersales_reason_attribution_item(tenant_id, category_id);
-- ⑥ 成员按 case 直取（不 join 集合就能拿到「这张单勾了哪些原因」）。
CREATE INDEX aftersales_reason_item_by_case
  ON aftersales_reason_attribution_item(tenant_id, case_id);
-- ⑦ 平台映射双向查：按平台码查（同步器写入侧）、按分类查（维护界面侧）。
CREATE INDEX aftersales_reason_mapping_by_platform
  ON aftersales_reason_platform_mapping(tenant_id, platform_source, platform_reason_code);
CREATE INDEX aftersales_reason_mapping_by_category
  ON aftersales_reason_platform_mapping(tenant_id, category_id, mapping_status);

-- ===========================================================================
-- 九、多租户：RLS + 授权（与 trade V92 / V144 / V150 / V156 第八节同一套写法）
-- ===========================================================================
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：原因分类是**跨租户汇总口径**的数据 ——
--   漏掉租户谓词会把 A 家的「质量问题占比」算进 B 家的报表，
--   而原因口径一旦串了，退货率分析会整体失真（这正是加粗句要防的事）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'aftersales_reason_category','aftersales_reason_attribution_set',
    'aftersales_reason_attribution_item','aftersales_reason_platform_mapping'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  aftersales_reason_category, aftersales_reason_attribution_set,
  aftersales_reason_attribution_item, aftersales_reason_platform_mapping TO trade_app;

-- ---- 列级白名单（照抄 V156 第 463-473 行的既有结论）----
-- ⚠️ PostgreSQL 里表级 UPDATE 蕴含该表**每一列**的 UPDATE 权限，
--   在表级 GRANT 之后写 `REVOKE UPDATE(depth)` 是**空操作** ——
--   has_column_privilege 仍返回 true，等于「以为收紧了其实没收紧」。
--   要真的收住，必须先收回表级 UPDATE，再逐列授回。
REVOKE UPDATE ON aftersales_reason_category FROM trade_app;
-- 只授回真正需要改的列。**depth 不在名单里** ⇒ 运行角色写不出一个假深度去绕开
-- 「三层上限」；root_kind / created_by / created_at 同理不可改。
GRANT UPDATE(parent_id,category_name,category_status,sort_order,description,updated_by,updated_at)
  ON aftersales_reason_category TO trade_app;

REVOKE UPDATE ON aftersales_reason_platform_mapping FROM trade_app;
-- captured_at / mapping_source / created_by 不可改：改了就等于伪造采集时刻。
GRANT UPDATE(category_id,platform_reason_name,mapping_status,updated_at)
  ON aftersales_reason_platform_mapping TO trade_app;

-- ---- 归因：append-only，只允许「标记旧版本失效」这一个 UPDATE ----
-- 归因是纠纷举证与质量归因分析的原材料：改了不改写、补一版不删旧版。
REVOKE UPDATE,DELETE ON aftersales_reason_attribution_item FROM trade_app;
REVOKE UPDATE,DELETE ON aftersales_reason_attribution_set FROM trade_app;
-- 集合上唯一需要的写操作是 superseded_at（把当前生效的那一版标记为失效），
-- 其余列一律不可改 —— 否则「谁在什么时候定的归因」可以被事后改掉。
GRANT UPDATE(superseded_at) ON aftersales_reason_attribution_set TO trade_app;

-- 本迁移**不 ALTER 任何既有表**：aftersales_case 簇（V156，11.1.1.1 车道的写入所有者）
--   全部只读（触发器里的 SELECT）。本模块对它们的全部依赖都是 SELECT，
--   跨车道 DDL 回归面 = 0。
