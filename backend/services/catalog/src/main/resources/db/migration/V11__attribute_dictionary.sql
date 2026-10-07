-- 3.2.1.1 属性定义列表：把 `attribute_definition` 从「每个 SPU 私有的一套属性」提升为
-- 「租户级属性字典 + SPU 级定义」同一个表，并补上蓝图点名的 `usage` / `filterable`。
--
-- ============================================================ 模型决策（为什么不是另建一张字典表）
--
-- V1 建表时 `attribute_definition.spu_id` 是 NOT NULL 且带 `UNIQUE(tenant_id,spu_id,code)`，
-- 所以它表达的是「这个 SPU 有哪些属性」。但 3.2.1.1 要管的是**属性字典**：
--   * 路由 `/products/attributes` 是租户级列表页，不是某个商品的属性页；
--   * 蓝图要求「按类目配置必填与可选范围」，这是**类目 → 属性**的关系，在 SPU 作用域的表上无处安放；
--   * V9 已经把规格轴（颜色/尺码/容量）建成租户级字典，并要求**规格轴与属性选项共享同一个值源**，
--     而值源 `attribute_option` 的外键指向 `attribute_definition(tenant_id,id)`。
--
-- 候选方案与取舍：
--
--   (A) **同表提升（选它）**：`spu_id` 改可空。`spu_id IS NULL` = 租户级字典条目（3.2.1.1 管），
--       `spu_id IS NOT NULL` = SPU 级定义（已交付的 3.2.2.1 管，语义一行不改）。
--       字典条目的选项仍然落在 `attribute_option`，于是「值源只有一处」这条蓝图硬约束
--       **自动继续成立**，V9 的 `guard_spec_axis_option_source` 一个字都不用改。
--
--   (B) 另建 `attribute_dictionary` 表：不动 V1，但字典的选项会落到**第二张选项表**，
--       规格轴（V9 锁死在 `attribute_option`）与字典选项就变成两个值源，
--       直接违反蓝图「规格选项不得独立于属性选项存在」的硬约束——**否掉**。
--
--   (C) 字典条目彻底替代 SPU 级定义：最干净，但要重写已交付 3.2.2.1 的写入路径与全部既有测试，
--       属于重开旧账，**否掉**。
--
-- (A) 对已交付模块的影响面，逐条核对过：
--   * `attribute_value` 的复合外键 `(tenant_id,spu_id,attribute_id) → attribute_definition(tenant_id,spu_id,id)`
--     **保持原样**。它只在 SPU 级定义上生效（MATCH SIMPLE 遇 NULL 跳过），
--     所以字典条目暂时不会被赋属性值——那是下游 3.2.2.x 的事，不是本模块的缺口。
--   * V9 的 `guard_spu_spec_axis` 里的 `a.spu_id<>NEW.spu_id` 对 NULL 求值为 NULL（不为真），
--     字典条目因此也能作为规格轴的承载属性，**不需要改函数**。
--   * 既有 `CHECK(NOT variant_axis OR (...))` 原样保留，下面只**追加**一条方向更严的约束。
--   * 唯一的代码改动：`CatalogService` 里 `insert into attribute_option values(?,?,?)` 是**位置化插入**，
--     加列后会直接报错，必须改成显式列名（见 V11 配套代码改动）。
--
-- ============================================================ 唯一约束为什么必须是 NULLS NOT DISTINCT
--
-- 原 `UNIQUE(tenant_id,spu_id,code)`：换成字典行（spu_id 为 NULL）后，PostgreSQL 认为 NULL 互不相等，
-- 同租户就能建出两条 code='COLOR' 的字典行——字典最核心的「编码租户内唯一」当场失效。
-- `UNIQUE NULLS NOT DISTINCT` 把 NULL 当成一个真值去重，于是：
--   * 字典行（spu_id IS NULL）→ 编码在**租户内**唯一；
--   * SPU 级行（spu_id 有值）→ 编码在**该 SPU 内**唯一（与 V1 同口径，既有行为不变）。
-- 两种作用域互不干扰：SPU 级行的 code 与某条字典条目相同是允许的，因为它们不在同一个命名空间里
-- （字典是「本租户有哪些属性」，SPU 级是「这个商品用哪些属性、取值模式是什么」）。

-- ------------------------------------------------------------ 1. 属性字典：同表提升
ALTER TABLE attribute_definition ALTER COLUMN spu_id DROP NOT NULL;

-- 蓝图点名的两个字段：用途（描述/规格/物流/合规）、是否参与筛选。
-- 已有行按「描述 + 不参与筛选 + 启用」回填，对已交付的 3.2.2.1 语义中性。
ALTER TABLE attribute_definition
  ADD COLUMN usage text NOT NULL DEFAULT 'DESCRIPTION'
    CHECK(usage IN ('DESCRIPTION','SPECIFICATION','LOGISTICS','COMPLIANCE')),
  ADD COLUMN filterable boolean NOT NULL DEFAULT false,
  -- 停用挡住的是「不许再新建使用」，既有商品上的属性值照常可解析（与 spec_axis.enabled 同口径）。
  ADD COLUMN enabled boolean NOT NULL DEFAULT true,
  -- 字典条目是多人共用的配置，走 version 乐观锁；回填 0 与 spu.version 的既有语义一致。
  ADD COLUMN version bigint NOT NULL DEFAULT 0,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- 【追加，不替换】V1 的 variant_axis 蕴含式保留在此；这里再加一条与字典「用途」对齐的方向：
-- 规格轴属性（variant_axis=true）必须登记为「规格」用途，否则 3.1.3.1 的
-- 「输入：属性定义（用途=规格）」在字典侧就对不上号。反向不设限——
-- 用途=规格但不作为规格轴（如材质、口味）是合法的。
--
-- **只约束字典行（spu_id IS NULL）**，这一点是必须的：
-- 已交付的 3.1.3.1 `SpecAxisService.ensureVariantAttribute` 建 SPU 级规格轴承载属性时不写 usage，
-- 会落到新增列的默认值 'DESCRIPTION'。若不加 `spu_id IS NOT NULL OR` 这一段，
-- 那条已交付的写入路径会被这条新 CHECK 整个打碎（实测 82 个用例 409）。
-- 已交付模块的既有写入语义本模块不改，所以豁免它们是刻意的，不是漏掉。
ALTER TABLE attribute_definition
  ADD CONSTRAINT attribute_definition_variant_usage
  CHECK(spu_id IS NOT NULL OR NOT variant_axis OR usage='SPECIFICATION');

-- 编码唯一性：NULLS NOT DISTINCT 覆盖字典作用域，见上方说明。
ALTER TABLE attribute_definition DROP CONSTRAINT attribute_definition_tenant_id_spu_id_code_key;
ALTER TABLE attribute_definition
  ADD CONSTRAINT attribute_definition_scope_code_key UNIQUE NULLS NOT DISTINCT (tenant_id,spu_id,code);

-- ------------------------------------------------------------ 2. 属性选项：排序与停用
-- 3.2.1.1 的输出含「选项集合」，所以选项的增删改查在本模块内要能做（3.2.1.2 尚未开发，
-- 不因此留残缺，但也不越界去做它的独立页面）。
-- **值源表仍是这一张**：字典条目与 SPU 级定义的选项共用它，蓝图的共享值源约束因此继续成立。
ALTER TABLE attribute_option
  ADD COLUMN position int NOT NULL DEFAULT 0 CHECK(position>=0),
  -- 停用不删除：历史 SKU 的属性值还要能解析（与 V9 的 spec_axis_option.enabled 同口径）。
  ADD COLUMN enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- ------------------------------------------------------------ 3. 类目属性规则（按类目配置必填与可选范围）
-- category 是 V1 的自由文本分类列（spu.category varchar(100)），不是外键：
-- 3.3.1.1 分类树未开发，没有可引用的类目主键。与 V9 的 category_spec_axis 同一口径，
-- 3.3.1.1 落地后应改挂 product_category 主键（见 V11 结尾注释）。
--
-- 建表顺序：category_attribute_rule 先建（它被 category_attribute_option 引用）。
CREATE TABLE category_attribute_rule (
  tenant_id uuid NOT NULL, category varchar(100) NOT NULL, attribute_id uuid NOT NULL,
  -- required=true 表示该类目下每个商品都必须有这条属性；false 表示「允许但非必须」。
  required boolean NOT NULL DEFAULT false,
  -- 「可选范围」的数字侧：仅对 data_type='DECIMAL' 有意义，写入时由服务层校验（见 AttributeDictionaryService）。
  -- 精度与 attribute_value_item.decimal_value 一致（numeric(30,10)），避免同一属性两套小数位。
  min_value numeric(30,10), max_value numeric(30,10),
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,category,attribute_id),
  FOREIGN KEY(tenant_id,attribute_id) REFERENCES attribute_definition(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(category)<>''),
  CHECK(min_value IS NULL OR max_value IS NULL OR min_value<=max_value)
);

-- 「可选范围」的枚举侧：某类目只允许取属性选项值源里的这几个值。
-- 复合外键直接指向 attribute_option(tenant_id,attribute_id,value)：
-- 写不进一个在该属性值源里不存在的值（这是「选项值源只有一处」的兑现点，不需要额外触发器）。
CREATE TABLE category_attribute_option (
  tenant_id uuid NOT NULL, category varchar(100) NOT NULL, attribute_id uuid NOT NULL, option_value varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,category,attribute_id,option_value),
  FOREIGN KEY(tenant_id,category,attribute_id) REFERENCES category_attribute_rule(tenant_id,category,attribute_id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,attribute_id,option_value) REFERENCES attribute_option(tenant_id,attribute_id,value),
  CHECK(btrim(category)<>'')
);

-- 范围侧只能是枚举属性：给非 OPTION 属性配 option_value 会表达出一个永远匹配不上的规则。
CREATE FUNCTION guard_category_attribute_option() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a attribute_definition;
BEGIN
  SELECT * INTO STRICT a FROM attribute_definition
   WHERE tenant_id=NEW.tenant_id AND id=NEW.attribute_id;
  IF a.data_type<>'OPTION' THEN
   RAISE EXCEPTION '属性「%」的数据类型是 %，不能配置可选值范围（只对枚举属性有意义）', a.name, a.data_type
    USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_category_attribute_option BEFORE INSERT OR UPDATE ON category_attribute_option
 FOR EACH ROW EXECUTE FUNCTION guard_category_attribute_option();

-- ------------------------------------------------------------ 4. 索引
-- 字典列表页的主查询路径：租户 + 字典作用域 + 排序。
CREATE INDEX attribute_dictionary_scope ON attribute_definition(tenant_id,spu_id,position,code);
-- 列表页筛选：按数据类型 / 用途 / 是否参与筛选 / 启用状态过滤。
CREATE INDEX attribute_dictionary_typed ON attribute_definition(tenant_id,spu_id,data_type,usage);
CREATE INDEX attribute_dictionary_filterable ON attribute_definition(tenant_id,spu_id,filterable);
-- 「这个编码已存在吗」的唯一性探测（也服务 UNIQUE NULLS NOT DISTINCT 的冲突定位）。
CREATE INDEX attribute_dictionary_code_lookup ON attribute_definition(tenant_id,code);
-- 选项列表按 position 排。
CREATE INDEX attribute_option_order ON attribute_option(tenant_id,attribute_id,position,value);
-- 类目规则：按类目取该类目的全部属性要求（3.3.1.2 / 3.1.1.2 的热读路径）。
CREATE INDEX category_attribute_rule_lookup ON category_attribute_rule(tenant_id,category,position,attribute_id);
-- 反查：「这条属性被哪些类目要求了」——字典详情页要看影响面。
CREATE INDEX category_attribute_rule_reverse ON category_attribute_rule(tenant_id,attribute_id);

-- ------------------------------------------------------------ 5. RLS
-- 两张类目规则表是租户级配置，与 spec_axis 同级：只有租户隔离，可见性由端点层权限把关。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['category_attribute_rule','category_attribute_option'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 【必须跟着 spu_id 一起改】V3 给 attribute_definition 建的 data_scope 是 RESTRICTIVE 策略，
-- 要求「这行属于一个当前可见的 SPU」：EXISTS(SELECT 1 FROM spu ... AND p.id=attribute_definition.spu_id)。
-- spu_id 改可空之后，字典行（spu_id IS NULL）永远不满足这个 EXISTS——写入会被 RLS 直接拒掉，
-- 而且报的是 row-level security policy 违规，不是可读的 409。**只改可空不改策略，字典一行都存不进去。**
--
-- 替换后的口径（两段都必须保留）：
--   * SPU 级定义（spu_id 有值）→ 仍然跟随 SPU 可见性，与 V3 逐字等价，既有行为零变化；
--   * 字典行（spu_id IS NULL）→ 租户级配置，在本租户内可见，与 spec_axis / unit_of_measure 同级。
-- 租户隔离没有被削弱：V1 的 isolation 策略（tenant_id 必须等于 app.current_tenant）仍然生效，
-- 且 RESTRICTIVE 策略之间是**与**的关系，两条都要满足。
DROP POLICY data_scope ON attribute_definition;
CREATE POLICY data_scope ON attribute_definition AS RESTRICTIVE
  USING (spu_id IS NULL OR EXISTS(SELECT 1 FROM spu p
    WHERE p.tenant_id=attribute_definition.tenant_id AND p.id=attribute_definition.spu_id))
  WITH CHECK (spu_id IS NULL OR EXISTS(SELECT 1 FROM spu p
    WHERE p.tenant_id=attribute_definition.tenant_id AND p.id=attribute_definition.spu_id));

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON category_attribute_rule, category_attribute_option TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON category_attribute_rule, category_attribute_option TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--   * **字典条目还不能被赋属性值**：`attribute_value` 的复合外键仍锁在 SPU 作用域，
--     这是有意保留的——把字典条目接到 3.2.2.1/3.2.2.2 的赋值链路属于下游属性赋值模块的改动，
--     不在本迁移里动已交付模块的写入路径。
--   * **category 仍是自由文本**：3.3.1.1 分类树落地后，`category_attribute_rule.category`
--     与 `category_spec_axis.category` 应一起改挂 `product_category` 主键。
--   * **类目属性要求的录入表单生成**（3.3.1.2 的输出）不在本模块：本模块只交付规则的数据模型与读写。