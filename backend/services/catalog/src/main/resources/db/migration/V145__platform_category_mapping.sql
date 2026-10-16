-- 4.2.3.1 平台类目映射：内部分类 ↔ 平台类目的**多对多**映射，带平台 + 站点两个维度，
-- 外加一份独立的「平台类目必填属性映射规则」（平台的某个属性对应内部哪个属性 ID）。
--
-- ============================================================ 上游交接：本迁移只做 V14 留的那个口子
--
-- V14 结尾写死了三句话，其中两句直接是本模块的输入契约：
--
--   > * **平台类目映射的叶子到平台 category_id 的映射**属于 04 刊登：本模块只把平台树建起来并可维护，
--   >   「这个叶子对应平台的哪个类目 id」是刊登侧的维护节奏，不在这里抢先定义。
--
-- 所以本迁移**不新建平台类目表**：`product_category_tree.tree_kind='PLATFORM'` 的节点
-- 就是一个平台类目，`product_category_tree.tree_kind='INTERNAL'` 的节点就是内部分类，
-- 两边都直接用复合外键指过去。这样「平台类目改名」仍然是 3.3.1.1 的写入所有权，
-- 本模块不夺走（每张事实表只有一个写入所有者）。
--
-- ============================================================ 模型决策：四个必须钉死的取舍
--
--   (A) **映射是独立的事实表，不在 product_category 上加两个列**。
--       蓝图写死了「同一个内部类目在不同平台/不同站点映射到不同的平台类目」——
--       这是**多对多**关系，加列的话 N 个平台就要 N 列，且永远表达不了
--       「两个内部类目映射到同一个平台类目」这一侧。关系就该是一张表。
--
--   (B) **平台与站点是两条独立的列，不合成一个「平台站点」字符串**。
--       合成一个字符串的话，「Amazon US 和 Amazon DE 共用一条平台树」这种最常见的情况
--       必须靠字符串前缀解析来还原两个维度，而解析一旦写错，映射会静默串到别的站点——
--       症状是「刊登到 DE 站却填了 US 站的类目」，平台审核才会报错。
--       这里用两条带 CHECK 的独立列，任何时候都能直接 group by 平台或站点。
--
--   (C) **属性映射规则是独立的一张表，不是映射表上的一个字符串列**。
--       蓝图要求「平台的『材质』对应内部哪个属性」，这是**一行一个属性**的关系：
--       要能列出某个平台类目的必填属性清单（4.2.1.1 表单生成 / 4.2.3.2 预检的输入），
--       要能按内部属性反查「这个内部属性在哪些平台被用」，还要能统计
--       「哪些平台必填属性还没绑定内部属性」。塞成字符串列这三个问题一个都答不了。
--
--   (D) **internal_attribute_id 可空**。
--       这一条看起来像在放松约束，实际相反：它是本模块「必填属性清单」这个输出的前提。
--       平台要求填 material，而租户的属性字典里还没有对应属性——这个事实**必须能记下来**，
--       否则运营配不出这条规则，只能等刊登被平台驳回才知道自己缺一个属性。
--       可空 + `requirement='REQUIRED' AND internal_attribute_id IS NULL` 就是
--       「已知缺口」的确定口径（见第 4 节的 partial index），4.2.3.2 直接消费它做阻断项。
--
-- ============================================================ 1. 映射表
CREATE TABLE platform_category_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 内部分类：必须落在 tree_kind='INTERNAL' 的树上（由第 3 节的触发器钉死，不是普通 CHECK）。
  internal_category_id uuid NOT NULL,
  -- 平台维度。编码与 channel 服务 platform.code 同一形态（varchar(32)，大写字母数字），
  -- 但**不建跨服务外键**：channel 的 platform / marketplace 是全局码表，
  -- AGENTS.md 禁止跨服务 FK / 跨库访问（见下面「与 channel 的边界」一节）。
  platform_code varchar(32) NOT NULL,
  -- 站点维度。与平台分开存：同一平台的不同站点往往要求不同的类目与不同的必填属性。
  site_code varchar(32) NOT NULL,
  -- 平台类目：必须落在 tree_kind='PLATFORM' 且该树 platform_code = platform_code 的树上。
  platform_category_id uuid NOT NULL,
  -- 是否为这个「内部分类 × 平台 × 站点」组合的**默认目标**。
  -- 多对多意味着一个内部分类在同平台同站点下可以映射到多个平台类目
  -- （例如主类目 + 平台的活动专用子类目），刊登时需要一个确定的选择，
  -- 由第 2 节的 partial unique index 保证「至多一个默认」。
  is_default boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 多对多的**唯一性口径**：同一个（内部分类，平台，站点，平台类目）只允许一条。
  -- 少了它，重复提交会攒出几条一模一样的记录，台账上看起来像「配了多次」。
  UNIQUE(tenant_id, internal_category_id, platform_code, site_code, platform_category_id),
  -- ON DELETE CASCADE 两条都必要：
  --   * 内部分类被删 → 指向它的映射失去意义（与 V14「删属性会级联删掉它的类目规则」同一口径）；
  --   * 平台类目被删 → 刊登侧的目标直接消失了，留着就是一条指向空处的死规则。
  FOREIGN KEY(tenant_id, internal_category_id) REFERENCES product_category(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, platform_category_id) REFERENCES product_category(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(platform_code) <> '' AND btrim(site_code) <> ''),
  -- 平台与站点的编码形态：ASCII 大写字母数字与短横线。中文站点名不是没有
  -- （Amazon 中国站），但那是**显示名**，显示名在平台树的 product_category.name 上，
  -- 这里的 code 是刊登侧要拼进请求的机器标识，与规格轴 code 同一纪律。
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 自映射：把内部分类映射到「自己那棵树里的节点」不可能成立
  --（两个外键指向不同树），这条是防触发器被绕过时的兜底，不是主要防线。
  CHECK(internal_category_id <> platform_category_id)
);

-- 「至多一个默认」：partial unique index 而不是全表 UNIQUE + 服务层自查。
-- 服务层自查挡不住并发——两个请求同时把两条映射设为默认都会通过检查，
-- 然后刊登侧拿到两条默认，「用哪个」变成数据库的物理顺序，不可复现。
CREATE UNIQUE INDEX platform_category_mapping_default
  ON platform_category_mapping(tenant_id, internal_category_id, platform_code, site_code)
  WHERE is_default;

-- ============================================================ 2. 平台类目属性映射规则（独立的一份）
--
-- 挂在**映射**上而不是直接挂在平台类目上：同一个平台类目在内部分类 A 上要求材质，
-- 在内部分类 B 上可能完全不要求。同一个平台类目的必填清单因此是**按映射**而论的，
-- 这正是 4.2.1.1 填表单时要查的东西（它手上有一个具体的内部分类）。
CREATE TABLE platform_category_attribute_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  mapping_id uuid NOT NULL,
  -- 平台侧属性编码（如 material / bullet_point_1），刊登时直接进平台请求体。
  platform_attribute_code varchar(64) NOT NULL,
  -- 平台侧属性显示名（如「材质」）。刻意冗余存一份：平台的属性名会随平台改版变，
  -- 运营维护这条规则时看到的应该是**平台的原话**，而不是内部属性字典里的中文名。
  platform_attribute_name varchar(200) NOT NULL,
  -- 内部属性字典条目（3.2.1.1 attribute_definition 的租户级字典行，spu_id IS NULL）。
  -- 可空的理由见开头 (D)：「已知还没绑定」本身是要被记下来的事实。
  internal_attribute_id uuid,
  -- 平台对该属性的要求强度。REQUIRED 即蓝图点名的「平台类目必填」。
  requirement text NOT NULL CHECK(requirement IN ('REQUIRED', 'RECOMMENDED', 'OPTIONAL')),
  -- 平台侧数据类型。它与内部属性的 data_type 常常不同（平台要 ENUM、内部是 OPTION），
  -- 所以是独立一列而不是复用内部类型——form 生成的类型选择器要用平台这一侧。
  platform_data_type text NOT NULL
    CHECK(platform_data_type IN ('TEXT', 'ENUM', 'NUMBER', 'BOOLEAN', 'DATE')),
  -- 平台属性是否接受多值（Amazon 的 bullet_point_1 就是多值）。
  multiple boolean NOT NULL DEFAULT false,
  -- 平台侧可选值。仅 platform_data_type='ENUM' 时有意义，非枚举存空数组（不是 NULL，
  -- NULL 会让 jOOQ 绑定与前端渲染各写一份空值判断）。
  allowed_values text[] NOT NULL DEFAULT '{}'::text[],
  -- 值变换：内部值送到平台前要做什么。DIRECT=原样；其余是刊登侧 4.2.1.1 的值转换算子。
  transform text NOT NULL DEFAULT 'DIRECT'
    CHECK(transform IN ('DIRECT', 'TRIM', 'UPPER', 'LOWER', 'NUMBER_PARSE', 'BOOLEAN_PARSE', 'DATE_PARSE')),
  enabled boolean NOT NULL DEFAULT true,
  position int NOT NULL DEFAULT 0 CHECK(position >= 0),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个映射下同一个平台属性码只能有一条：重复配两次的话，
  -- 刊登时到底发哪个值取决于查询顺序，且两边的运营会各自看到「我配的没生效」。
  UNIQUE(tenant_id, mapping_id, platform_attribute_code),
  FOREIGN KEY(tenant_id, mapping_id) REFERENCES platform_category_mapping(tenant_id, id) ON DELETE CASCADE,
  -- 指到 3.2.1.1 的属性字典。ON DELETE RESTRICT（**不级联**）：
  -- 删内部属性会让已配好的平台规则静默失效，而正在刊登的批次会突然少一个必填项。
  -- 这里选择让删除被拒绝，由服务层给出可读原因。
  FOREIGN KEY(tenant_id, internal_attribute_id) REFERENCES attribute_definition(tenant_id, id),
  CHECK(btrim(platform_attribute_code) <> '' AND btrim(platform_attribute_name) <> ''),
  CHECK(platform_attribute_code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
  -- 枚举属性必须给候选值，否则刊登侧既无法渲染下拉也无法做值校验，
  -- 只能把内部值原样发过去等平台驳回。钉在 DB 层比放在服务层更难被绕过。
  CHECK(platform_data_type <> 'ENUM' OR cardinality(allowed_values) > 0)
);

-- 候选值里不许有空白项：多值属性把 '' 当候选值，刊登时会发一个空串给平台。
--
-- 【为什么用触发器而不是 CHECK】PostgreSQL 明令 CHECK 里不能用子查询
-- （ERROR: cannot use subquery in check constraint，实测 V145 首次执行即报 0A000），
-- 而 `not exists (select 1 from unnest(allowed_values) ...)` 正是子查询。
-- 与 V14 的防环触发器、V37 的认证守卫同一手法：把「需要查别的东西」的约束放到触发器，
-- CHECK 只留给能纯靠本行列算出来的那些。
CREATE FUNCTION guard_platform_attribute_allowed_values() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM unnest(NEW.allowed_values) v WHERE btrim(v) = '') THEN
  RAISE EXCEPTION '候选值里不允许出现空白项：刊登时会把它当成一个空值发给平台'
    USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER platform_attribute_allowed_values_guard
  BEFORE INSERT OR UPDATE ON platform_category_attribute_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_platform_attribute_allowed_values();

-- ============================================================ 3. 树种类与平台归属守卫（触发器）
--
-- 【为什么不能只用 CHECK】CHECK 只能看本行的列，看不到 product_category 挂在哪棵树上。
-- 而「内部分类必须来自 INTERNAL 树」这条约束一旦只在服务层校验，
-- 任何绕过服务的写入（运维 psql、未来的 ETL、另一个服务的迁移脚本）
-- 都能造出「内部类目映射到另一个内部类目」的记录——而那行记录在界面上
-- 完全看不出异常，只有刊登提交时才会暴露。
--
-- 【与服务层的关系】服务层同样校验一遍，但目的是**给出可读原因**：
-- 触发器抛的是 '23514' + 一句中文，映射管理页可以把它原样显示；
-- 而服务层能在返回前就把「左边这个类目是平台树的，不能当内部分类」说清楚。
-- 两者是纵深，不是重复。
CREATE FUNCTION guard_platform_category_mapping_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  internal_kind  text;
  platform_kind  text;
  platform_owner varchar(32);
BEGIN
  SELECT t.tree_kind INTO internal_kind
    FROM product_category c
    JOIN product_category_tree t ON t.tenant_id = c.tenant_id AND t.id = c.tree_id
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.internal_category_id;
  IF internal_kind IS DISTINCT FROM 'INTERNAL' THEN
    RAISE EXCEPTION '映射的内部分类必须来自 INTERNAL 分类树，实际是 %', coalesce(internal_kind, '（类目不存在）')
      USING ERRCODE = '23514';
  END IF;

  SELECT t.tree_kind, t.platform_code INTO platform_kind, platform_owner
    FROM product_category c
    JOIN product_category_tree t ON t.tenant_id = c.tenant_id AND t.id = c.tree_id
   WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.platform_category_id;
  IF platform_kind IS DISTINCT FROM 'PLATFORM' THEN
    RAISE EXCEPTION '映射的平台类目必须来自 PLATFORM 分类树，实际是 %', coalesce(platform_kind, '（类目不存在）')
      USING ERRCODE = '23514';
  END IF;

  -- 平台归属必须与树声明的平台一致。少了这一条就可以写出
  -- 「platform_code='AMAZON' 但平台类目挂在 SHOPIFY 的树」这种行，
  -- 刊登时去 Amazon 找一个 Shopify 的类目 id，平台必然驳回。
  IF platform_owner IS DISTINCT FROM NEW.platform_code THEN
    RAISE EXCEPTION '该平台类目属于平台 %，与映射声明的平台 % 不一致', platform_owner, NEW.platform_code
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER platform_category_mapping_scope_guard
  BEFORE INSERT OR UPDATE ON platform_category_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_platform_category_mapping_scope();

-- ============================================================ 4. 索引
--
-- 台账主查询（按内部分类列出它在各平台各站点的映射）：4.2.1.1 填表单时走的正是这条。
CREATE INDEX platform_category_mapping_by_internal
  ON platform_category_mapping(tenant_id, internal_category_id, platform_code, site_code, enabled);
-- 反查：某个平台类目被哪些内部分类映射到。列顺序带上平台与站点，
-- 是因为这个计数只在**同平台同站点**内有意义（Amazon 的类目与 Shopify 的同名类目是两个东西），
-- 而台账每行都要算一次它 —— 把它排进索引前缀，这条计数就是索引扫描而不是逐行子查询。
CREATE INDEX platform_category_mapping_by_platform_category
  ON platform_category_mapping(tenant_id, platform_category_id, platform_code, site_code);
-- 平台 × 站点维度的筛选（页面顶部那两排下拉，以及按平台/站点看覆盖率）。
CREATE INDEX platform_category_mapping_by_platform_site
  ON platform_category_mapping(tenant_id, platform_code, site_code, enabled);
-- 属性规则按映射取（必填属性清单的主查询）。
CREATE INDEX platform_category_attribute_mapping_by_mapping
  ON platform_category_attribute_mapping(tenant_id, mapping_id, position, platform_attribute_code);
-- 「哪些平台必填属性还没绑定内部属性」——4.2.3.2 合规预检的阻断项清单，
-- 以及本页的覆盖缺口统计。两处都只扫这一小部分行，全表 index 反而更贵。
CREATE INDEX platform_category_attribute_mapping_unmapped_required
  ON platform_category_attribute_mapping(tenant_id, mapping_id)
  WHERE internal_attribute_id IS NULL AND requirement = 'REQUIRED' AND enabled;
-- 反查「这个内部属性在哪些平台被用」（属性字典页提示「本属性已映射到哪些平台类目」）。
CREATE INDEX platform_category_attribute_mapping_by_internal
  ON platform_category_attribute_mapping(tenant_id, internal_attribute_id)
  WHERE internal_attribute_id IS NOT NULL;

-- ============================================================ 5. RLS
--
-- 两张表都是租户级配置（与 product_category / spec_axis / unit_of_measure 同级）：
-- 只有租户隔离，可见性由端点层的权限把关，不做数据范围策略。
-- FORCE RLS 是必须的：否则表 owner（catalog_app 若被授予 owner 身份，
-- 或迁移角色复用同一登录）会绕过策略，隔离测试就成了自欺欺人。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['platform_category_mapping', 'platform_category_attribute_mapping'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)', tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_category_mapping, platform_category_attribute_mapping TO catalog_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_category_mapping, platform_category_attribute_mapping TO starcon_app;

-- ============================================================ 与 channel 服务的边界（写死，避免下游误解）
--
-- platform / marketplace 码表在 **channel** 服务（V1__channel_registry_connections_and_shops.sql，
-- 2.1.1.1 / 2.1.1.2 / 2.1.1.3），是**不带 tenant_id 的全局码表**。本迁移因此：
--   * **不建跨服务外键**（AGENTS.md 明令禁止跨服务 FK / 跨库视图）；
--   * 只按 code 形态校验（大小写与字符集），**不校验该平台是否真的在 channel 注册过**。
--     理由：catalog 无法查 channel 的表，建一个本地平台码表又会造出第二个写入所有者。
--     代价（必须承认）：运营可以填一个 channel 里不存在的平台码，
--     症状是该平台永远同步不到类目。缓解是平台的候选值由**已建的 PLATFORM 分类树**给出
--     （前端 platform-sites 端点返回真实树 + 已在用的站点码），树是租户自己维护的真实数据，
--     不是硬编码常量。
--   * channel 若后续交付「平台类目同步」能力，应通过 event 把平台码与站点码同步进
--     **平台分类树**（tree_kind='PLATFORM'），本模块的 platform_code 自然就有了权威来源；
--     在那之前不改本表结构。

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **4.2.1.1 表单生成**消费的是 `platform_category_attribute_mapping` 的必填清单，
--     按「内部分类 × 平台 × 站点」解析到具体的一条映射后取其属性规则。
--     本模块提供 resolve 端点完成这次解析，下游不需要自己拼查询。
--   * **4.2.3.2 合规预检**消费的是「REQUIRED 且未绑定内部属性」这一组行
--     （第 4 节的 partial index 就是为它建的），以及停用/未映射的内部分类。
--   * **刊登时的值转换**（transform 列）由 4.2.1.1 执行：这里只存算子名，不存转换实现，
--     一个算子的两份实现迟早会对不上。
