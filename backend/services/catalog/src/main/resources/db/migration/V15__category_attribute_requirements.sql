-- 3.3.1.2 类目属性要求：把 V11 的「类目属性规则」从「必填与否 + 两端数值区间」扩成
-- 蓝图点名的完整能力——必填 / 可选 / 条件必填三种要求形态 + 按数据类型分流的取值范围约束 +
-- 排序 + 启用停用，并在此基础上提供**录入表单 schema 生成**与**保存前校验**的输出基础。
--
-- ============================================================ 模型决策
--
-- 候选方案与取舍：
--
--   (A) **在 `category_attribute_rule` 上加列（选它）**。
--       V11 已经把「某类目对某属性有要求」建成事实表，V14 又把它的锚点从自由文本换成
--       `category_id`。本模块要做的是**加语义**（要求形态 / 条件 / 取值约束 / 启停），
--       不是再加一张「看起来也像类目属性要求」的表——那会出现两个写入所有者，
--       3.2.1.1 已交付的类目规则页与本模块的录入表单会各说各话。
--
--   (B) 另建 `category_attribute_requirement` 表：干净，但同一条「上装要求颜色」
--       要在两张表里各存一份，改必填只改其中一张，校验读到哪张全靠调用方记得。
--       **否掉**。
--
--   (C) 把 category_attribute_option 也重建成 uuid 键：属于重开 V11/V14 的旧账，
--       本模块不夺走已交付模块的写入所有权。**否掉**。
--
-- ============================================================ 继承语义（蓝图没写死，这里定死）
--
-- 决定：**支持从祖先分类继承，按属性逐条就近覆盖（nearest-wins）**。
--
-- 有效规则集的算法（单一规则，不存在「一半继承一半不继承」）：
--   1. 取该类目的祖先链 `product_category.path_ids`（含自己，从根到自己）；
--   2. 对**每个属性**，沿这条链从自己往根找**第一条**该属性的规则，它就是生效规则；
--   3. 若这条生效规则 `enabled=false`，该属性**整体不进入**有效规则集
--      （这是子节点显式豁免祖先要求的唯一手段，不用「留一条空的」表达）；
--   4. 链上任何位置都没有规则的属性，不进入有效规则集。
--
-- 为什么选继承而不是「绑定到具体节点」：
--   * 分类树是运营手工维护的多级结构（服饰 / 上装 / T恤）。不继承的话，
--     每一件 T恤都要把「上装」层已经配好的颜色、尺码原样再配一遍，
--     配错一处就会出现「上装要求尺码、T恤不要求」的静默漏填——这是数据质量问题，不是配置问题。
--   * 就近覆盖让「T恤可以比上装更严」成为默认：只需要在 T恤 上加一条同名规则，
--     祖先那条自动被遮蔽，不需要任何「删除继承」的开关。
--
-- 代价（明确承认）：祖先规则变更会影响全部子孙的有效规则集。
-- 因此读路径必须**按类目现算**，不得缓存；类目详情上要显示每条规则来自哪一层
-- （本模块 `category-requirements` 端点返回的 `depth` / `sourceCategoryName`），否则运营无法自查。
--
-- ============================================================ 1. 加列：要求形态取代 required 布尔
--
-- `required boolean` 是 V11 的表达力：只有「必填 / 非必填」两种。蓝图要求三种形态，
-- 其中 `conditional_required`（给定前置属性取值时才必填）在布尔列里**无法表达**——
-- 写 true 就变成无条件必填（录入表单会在所有场景都强制这个字段），
-- 写 false 就永远不校验（条件形同虚设）。两种都是错的。
--
-- `requirement` 是权威列，`required` 退化为**派生兼容列**，由触发器单向同步：
--
--   REQUIRED             -> required = true
--   OPTIONAL             -> required = false
--   CONDITIONAL_REQUIRED -> required = false
--
-- 最后一条是刻意的：`required=false` 让已交付的 3.2.2.2 SKU 属性覆写
-- （`exists(... and r.required)` 判类目必填）保持**不误伤**——它无法求值条件，
-- 若把条件必填映射成 true，会把「颜色=红色时才必填保修期」变成「所有商品都必须填保修期」，
-- 把合法录入直接挡死。宁可少挡，不可乱挡。条件求值由本模块的保存前校验端点负责。
ALTER TABLE category_attribute_rule
  -- 权威列：要求形态。第一步先可空，触发器与回填之后再收口 NOT NULL。
  ADD COLUMN requirement text,
  -- 条件必填依赖的前置属性：同租户的属性字典条目。复合外键把「引用不存在的属性」挡在 DB 层。
  ADD COLUMN condition_attribute_id uuid,
  -- 比较算子。词表而不是自由文本：算子集合必须与前置属性的数据类型相容
  -- （OPTION 不能用 GT，DECIMAL 不能用 IN 的枚举语义），不可相容的组合表达不出可求值的条件。
  ADD COLUMN condition_operator text,
  -- 文本长度上限。只对 TEXT 有意义。
  ADD COLUMN max_length int,
  -- 是否允许自由填写（提交不在选项集内的值）。只对 OPTION 有意义。
  ADD COLUMN allow_free_text boolean NOT NULL DEFAULT false,
  -- 规则启停。停用 = 该规则不生效；在继承语义下，子节点停用它就是豁免祖先的要求。
  ADD COLUMN enabled boolean NOT NULL DEFAULT true,
  -- 规则自身也要乐观锁：它是多人共用的类目配置（与 product_category.version 同口径）。
  ADD COLUMN version bigint NOT NULL DEFAULT 0,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- ============================================================ 2. requirement 与 required 的同步触发器
--
-- 【为什么用触发器而不是只在服务层同步】V11 已交付的 `AttributeDictionaryService.saveCategoryRule`
-- 仍在显式写 `required`，而它并不认识 requirement。触发器保证无论谁写，两列都不会漂移；
-- 只在本模块服务层同步的话，别人下一次写 `required` 就把它改回去了。
CREATE FUNCTION sync_category_attribute_rule_requirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- INSERT 未显式给 requirement（已交付的 3.2.1.1 走的就是这条路）：从旧的 required 布尔派生，
 -- 于是老接口继续工作、语义不丢。
 IF NEW.requirement IS NULL THEN
  NEW.requirement := CASE WHEN NEW.required THEN 'REQUIRED' ELSE 'OPTIONAL' END;
 END IF;
 NEW.requirement := upper(btrim(NEW.requirement));
 NEW.required := (NEW.requirement = 'REQUIRED');
 NEW.updated_at := now();
 RETURN NEW;
END $$;
CREATE TRIGGER sync_category_attribute_rule_requirement
  BEFORE INSERT OR UPDATE ON category_attribute_rule
  FOR EACH ROW EXECUTE FUNCTION sync_category_attribute_rule_requirement();

-- 收口 NOT NULL（**必须在触发器之后**）：触发器在 BEFORE 阶段填好 requirement，
-- NOT NULL 检查发生在其后，所以「不写 requirement 的老 INSERT」（3.2.1.1）依然能通过。
UPDATE category_attribute_rule SET requirement=CASE WHEN required THEN 'REQUIRED' ELSE 'OPTIONAL' END
  WHERE requirement IS NULL;
ALTER TABLE category_attribute_rule ALTER COLUMN requirement SET NOT NULL;

-- ============================================================ 3. 约束：条件必填与「有条件」必须同时成立
--
-- 只写 requirement='CONDITIONAL_REQUIRED' 而没有前置属性，求值时无从判断；
-- 只写前置属性而 requirement 不是条件必填，那条条件是死配置（界面上看着有配置，求值永不生效）。
-- 拆成两条 CHECK 是为了报错时能直接指出缺的是哪一半。
ALTER TABLE category_attribute_rule
  ADD CONSTRAINT category_attribute_rule_requirement_vocab
  CHECK(requirement IN ('REQUIRED','OPTIONAL','CONDITIONAL_REQUIRED')),
  ADD CONSTRAINT category_attribute_rule_condition_present
  CHECK((requirement='CONDITIONAL_REQUIRED') = (condition_attribute_id IS NOT NULL)),
  ADD CONSTRAINT category_attribute_rule_condition_operator
  CHECK((requirement='CONDITIONAL_REQUIRED') = (condition_operator IS NOT NULL)),
  ADD CONSTRAINT category_attribute_rule_operator_vocab
  CHECK(condition_operator IS NULL
     OR condition_operator IN ('IN','NOT_IN','EQ','NEQ','GT','GTE','LT','LTE')),
  -- 自引用：属性 A 的条件依赖属性 A 自己，求值时读的就是待判定的那个值，永远无法收敛。
  ADD CONSTRAINT category_attribute_rule_no_self_condition
  CHECK(condition_attribute_id IS NULL OR condition_attribute_id<>attribute_id),
  -- 文本长度上限必须为正：0 会让「等于空串」与「任意长」两种语义搅在一起。
  ADD CONSTRAINT category_attribute_rule_max_length_positive
  CHECK(max_length IS NULL OR max_length>0),
  ADD CONSTRAINT category_attribute_rule_condition_attribute_fk
  FOREIGN KEY(tenant_id,condition_attribute_id) REFERENCES attribute_definition(tenant_id,id);

-- ============================================================ 4. 唯一约束
--
-- V11 的主键是 (tenant_id, category, attribute_id)，**category 是自由文本**。
-- 本模块的规则按 `category_id` 存取与继承解析，必须在 id 上也有唯一约束：
--   * 「同一分类同一属性重复配置要拒绝」这条要求的权威落点是 category_id；
--   * 下面条件取值表的外键需要一个被引用的唯一键——PG 在建外键时就校验被引用列存在唯一索引，
--     文本主键满足不了（同一棵树里两个节点可以同名）。
--
-- 只**加**不**换**主键：category_attribute_option 的外键指向
-- (tenant_id,category,attribute_id)，换主键要连带改 3.2.1.1 的 on conflict / delete 与它的一整套
-- 集成测试夹具，那属于重开旧账。文本列继续按 V14 的口径由 CategoryTreeService 在重命名时同步。
CREATE UNIQUE INDEX category_attribute_rule_category_id_key
  ON category_attribute_rule(tenant_id,category_id,attribute_id);

-- ============================================================ 5. 条件取值：与 V11 的 category_attribute_option 同一形态
--
-- 规则 + 取值子集两张表，不塞 jsonb。理由：这些值要参与**反查**
-- （「哪些规则依赖这个属性的某个取值」，表单 schema 生成时的依赖排序要用），
-- 且必须能被复合外键钉在规则与属性字典上，jsonb 两者都做不到。
CREATE TABLE category_attribute_condition_value (
  tenant_id uuid NOT NULL, category_id uuid NOT NULL, attribute_id uuid NOT NULL,
  -- 依赖属性的 id：条件里的取值属于「A 在 B 取某值时必填」这个条件，不是 A 自己的取值。
  condition_attribute_id uuid NOT NULL,
  condition_value varchar(200) NOT NULL,
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,category_id,attribute_id,condition_attribute_id,condition_value),
  -- 规则没了条件取值就没有意义（与 V11「属性没了要求就没有意义」同一口径）。
  FOREIGN KEY(tenant_id,category_id,attribute_id)
    REFERENCES category_attribute_rule(tenant_id,category_id,attribute_id) ON DELETE CASCADE,
  -- 依赖属性必须真实存在（否则条件引用了一个字典里没有的属性）。
  FOREIGN KEY(tenant_id,condition_attribute_id) REFERENCES attribute_definition(tenant_id,id),
  CHECK(btrim(condition_value)<>'')
);

-- 【为什么上面那条外键不够，还要一个触发器】外键只校验「这个属性存在」，
-- 校验不了「这个属性就是该规则声明的条件依赖」。写进一个存在但对不上的依赖属性时，
-- 外键放行，而求值读的是规则的 condition_attribute_id——界面上写着「当 X=某值时必填」，
-- 实际生效的却是另一个属性 Y 的取值。这类配置**不报错、不崩，只是永远求值不出来**，
-- 是最难被发现的一类错配，所以在这里挡住（服务层也会先给可读原因，这里是第二道）。
CREATE FUNCTION guard_category_attribute_condition_value() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE declared uuid;
BEGIN
 SELECT r.condition_attribute_id INTO declared FROM category_attribute_rule r
  WHERE r.tenant_id=NEW.tenant_id AND r.category_id=NEW.category_id AND r.attribute_id=NEW.attribute_id;
 IF declared IS NULL THEN
  RAISE EXCEPTION '条件取值对应的规则不存在，或该规则不是条件必填（没有可依赖的前置属性）'
   USING ERRCODE='23514';
 END IF;
 IF declared<>NEW.condition_attribute_id THEN
  RAISE EXCEPTION '条件取值的依赖属性与规则声明的前置属性不一致：条件取值必须挂在该规则自己的前置属性上'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_category_attribute_condition_value
  BEFORE INSERT OR UPDATE ON category_attribute_condition_value
  FOR EACH ROW EXECUTE FUNCTION guard_category_attribute_condition_value();

-- ============================================================ 6. 索引
--
-- 有效规则集的读路径：祖先链上按 category_id 取规则（继承解析的主查询）。
CREATE INDEX category_attribute_rule_inherited
  ON category_attribute_rule(tenant_id,category_id,enabled,position,attribute_id);
-- 依赖反查：表单 schema 生成时要按「谁依赖我」排序；运营改前置属性的选项时要看影响面。
CREATE INDEX category_attribute_rule_condition_dependency
  ON category_attribute_rule(tenant_id,condition_attribute_id,category_id);
-- 条件取值按规则取（生成 / 改写条件时整体替换）。
CREATE INDEX category_attribute_condition_value_rule
  ON category_attribute_condition_value(tenant_id,category_id,attribute_id,condition_attribute_id,position);

-- ============================================================ 7. RLS
--
-- category_attribute_rule 的 RLS 已在 V11 开启（FORCE + isolation 策略），本迁移只加列不改隔离语义。
-- 新的条件取值表是租户级配置（与规则同级），沿用 V11 的逐字同款策略。
ALTER TABLE category_attribute_condition_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE category_attribute_condition_value FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON category_attribute_condition_value
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON category_attribute_condition_value TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON category_attribute_condition_value TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **平台类目的属性要求不在这里**：蓝图写死了「不同平台的类目要求可能不同，
--     平台侧要求在 04 按平台维护，此处只管内部类目」。规则锚在 product_category.id 上，
--     而平台树的节点归 04 维护。表单生成端点只对内部类目树生效（服务层给可读拒绝），
--     规则的 CRUD 本身不禁止挂到平台树节点上——那需要 04 的映射存在才有意义。
--   * **表单 schema 与保存前校验不落表**：它们是**按类目 + 按提交值现算**的派生结果
--     （条件必填的求值依赖当前填写值，缓存必然过期），由本模块的端点实时产出。
--   * **`attribute_value` 仍不接受字典条目赋值**（V11 结尾的老口子）：把字典接到赋值链路
--     属于下游 3.2.2.x 的改动，本模块不夺走已交付模块的写入路径。