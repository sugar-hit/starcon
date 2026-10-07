-- 3.3.1.1 分类树管理：多棵独立的分类树 + 防环 + 主/辅分类 + 多语言分类名 + 类目商品数统计。
--
-- ============================================================ 本迁移兑现的是两个前序模块留的口子
--
-- V9 与 V11 的结尾都写了同一句话：
--
--   > **category 仍是自由文本**：3.3.1.1 分类树落地后，`category_attribute_rule.category`
--   > 与 `category_spec_axis.category` 应一起改挂 `product_category` 主键。
--
-- V13 结尾第三次重复了它。所以本迁移的第一件事不是建分类树，而是**把这两张规则表锚到类目主键上**：
-- 不锚主键，3.2.1.1 的「有商品的类目里没配任何属性要求的缺口」统计（AttributeDictionaryService
-- 的 categoriesWithoutRuleCount）就只能按字符串比分类，永远和分类树对不上——运营在树上看到一个类目，
-- 统计里却是另一个名字，两边永远对不齐。
--
-- ============================================================ 模型决策：为什么是「加 category_id」而不是「把 category 换成 uuid」
--
-- 候选方案与取舍：
--
--   (A) **新增 category_id 外键，保留 category 文本列（本迁移选它）**。
--       `category_id uuid` 是**权威键**，带复合外键 `ON DELETE CASCADE`；`category` 文本列**保留**，
--       继续作为已交付模块（3.1.3.1 规格轴 / 3.2.1.1 属性字典 / 3.2.2.2 SKU 属性覆写）的读写口径。
--
--   (B) 把 `category` 直接**改类型成 uuid**：语义最干净，但 `category` 是
--       `category_attribute_rule` 主键 `(tenant_id,category,attribute_id)` 的一列，
--       `category_attribute_option` 与 `category_spec_axis` 都有指向它的复合外键，
--       改类型会连带重写三个已交付模块的 Service、Controller、DTO、OpenAPI 契约、
--       前端页面与约 40 处集成测试夹具。这属于**重开旧账**——而 V9 / V11 / V13 三份迁移
--       都反复强调「不重开旧账，本模块只加索引/只加列」，口径必须一致。**否掉**。
--
--   (C) 另建一张 `category_rule_link` 关联表，规则表原样不动：多一张事实表就多一个写入所有者，
--       同一条规则会出现在两个地方，违反「每张事实表只有一个写入所有者」。**否掉**。
--
-- 选 (A) 之后，「文本与主键会不会各说各话」是这个设计的**全部风险**，所以口径写死成一条：
--
--   * **category_id 是权威**。新建 / 改动分类树时由 CategoryTreeService 写入，
--     复合外键保证它指向真实存在的类目；类目被删时规则随类目级联删除
--     （与 V11「属性没了要求就没有意义」同一理由）。
--   * **category 文本是给已交付模块的兼容读列**，唯一允许写它的地方是 CategoryTreeService
--     的重命名路径：重命名一个类目时，同一事务内同步 update 两张规则表的 category 文本。
--     除此以外任何模块都不许单独改它。这样文本永远等于「该 category_id 对应类目的当前名称」，
--     不存在两边漂移的可能。
--   * 既有自由文本数据**做数据迁移**，不是留 TODO：为每个租户的每个历史类目文本建一个
--     INTERNAL 树下的类目行，再回填 category_id。见下面第 6 节。
--
-- ============================================================ 1. 分类树（多棵独立的树）
--
-- 蓝图点名的「支持多棵树」在这里落成独立的树实体，**不是**在分类行上加一个 type 标签：
-- 内部管理分类与平台类目映射的**维护节奏完全不同**（平台类目跟着平台改版走，内部类目跟着商品结构走），
-- 两棵树要能各自启用停用、各自版本、各自的编码命名空间。
CREATE TABLE product_category_tree (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 树编码在租户内唯一：内部类目与每个平台各占一个命名空间（AMAZON / SHOPIFY / TIKTOK / EBAY / ALI1688）。
  code varchar(64) NOT NULL,
  name varchar(100) NOT NULL,
  -- 树种类：INTERNAL=内部管理分类；PLATFORM=平台类目映射（下游是 04 刊登）。
  -- 约束而不是自由文本：这两类树的语义差别很大（平台树的叶子对应平台的 category_id），
  -- 让它可枚举才能在服务层给出「平台树不能当内部类目用」这种可读拒绝。
  tree_kind text NOT NULL CHECK(tree_kind IN ('INTERNAL','PLATFORM')),
  -- 平台树必须指明平台（刊登映射要按平台取树）；内部树必须为空。DB 层用 CHECK 钉死，
  -- 少写这一段就会出现「一条平台树没有平台」的行，04 刊登按平台取树时静默取不到。
  platform_code varchar(32),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  CHECK((tree_kind='PLATFORM') = (platform_code IS NOT NULL)),
  CHECK(platform_code IS NULL OR btrim(platform_code)<>'')
);

-- ============================================================ 2. 分类节点（多级 + 防环 + 稳定排序）
CREATE TABLE product_category (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  tree_id uuid NOT NULL,
  -- 编码在**树内**唯一，不在租户内唯一：同一个 "TOP" 在内部树与平台树里各有一条是合法的，
  -- 两棵树本来就不共享命名空间（与 V11 的 attribute_definition 字典/字典行分作用域同一思路）。
  code varchar(64) NOT NULL,
  name varchar(100) NOT NULL,
  -- 父节点为空即根节点。复合外键只保证「父节点存在」，**防环由触发器负责**（外键表达不了环）。
  parent_id uuid,
  -- 同级排序位。拖拽调整层级与同级排序都落这一列，落库后顺序稳定（见索引里的 (tenant_id,tree_id,parent_id,position)）。
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  enabled boolean NOT NULL DEFAULT true,
  -- 祖先链（含自己，从根到自己在数组里的顺序）。存它有两个用处：
  --   * 「这个分类及其所有子分类下的商品数」= spu_category.category_id = any(path_ids)，一次索引扫描出结果；
  --   * 防环判定 O(深度) 直接在数组上做，不必每次递归 CTE。
  -- 拖拽移动子树时整条前缀一起改，见第 3 节的 sync 函数。
  path_ids uuid[] NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,tree_id,code),
  -- 复合外键 (tenant_id,tree_id,parent_id) 的指向目标：没有这条 (tenant_id,tree_id,id) 唯一约束，
  -- 下面那条「父节点必须同树」的外键根本建不出来（PG 要求被引用列有唯一约束）。
  UNIQUE(tenant_id,tree_id,id),
  -- 复合外键把「父节点必须在同一棵树里」钉死在 DB：只判 parent_id 存在的话，
  -- 平台树的类目可以被挂到内部树的父节点下，整棵树当场跨树污染且递归查询查不出来。
  FOREIGN KEY(tenant_id,tree_id,parent_id) REFERENCES product_category(tenant_id,tree_id,id),
  FOREIGN KEY(tenant_id,tree_id) REFERENCES product_category_tree(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  -- 自己是自己的父节点是最短的一处环，先用 CHECK 便宜地挡掉。
  CHECK(parent_id IS NULL OR parent_id<>id),
  -- 祖先链必须以自己结尾：path_ids 是防环与子树统计的共同基础，写坏了两处一起错。
  CHECK(path_ids[array_length(path_ids,1)]=id),
  CHECK(cardinality(path_ids)>=1)
);

-- 多语言分类名。基础名称在 product_category.name（保证任何语言下都有一个可读名字，
-- 不允许「只配了日文、中文名为空」的行），这里存的是**补充**。
-- 按 (tenant_id,locale) 建唯一约束之外再加一条 (tenant_id,category_id,locale) 主键：
-- 同一个类目的同一个语言只能有一条，不允许两种写法各存一份。
CREATE TABLE product_category_name (
  tenant_id uuid NOT NULL, category_id uuid NOT NULL,
  locale varchar(16) NOT NULL,
  name varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,category_id,locale),
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(locale)<>'' AND btrim(name)<>'')
);

-- ============================================================ 3. 防环 + path_ids 同步（DB 层，与服务层构成双重保证）
--
-- 【为什么必须由 DB 保证】蓝图点名的「防环」如果只写在服务层，任何绕过服务的写入
-- （运维 psql、未来的数据迁移脚本、另一个服务的 ETL）都能造出一棵有环的树，
-- 而环一旦形成，所有基于 path_ids / 递归 CTE 的查询都会**死循环**——
-- 不是报错，是把连接池占满。所以这里必须有第二道。
--
-- 判定方式：沿父链向上走，如果走回 NEW 自己，就成环。
CREATE FUNCTION guard_product_category_cycle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE depth int; parent_path uuid[];
BEGIN
 IF NEW.parent_id IS NULL THEN
  -- 根节点：path_ids 只有自己。
  NEW.path_ids := ARRAY[NEW.id];
  RETURN NEW;
 END IF;
 -- 树必须存在（复合外键也会兜，但 23503 没有业务语义，这里给可读原因）。
 IF NOT EXISTS (SELECT 1 FROM product_category_tree t
                WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.tree_id) THEN
  RAISE EXCEPTION '分类树不存在' USING ERRCODE='23514';
 END IF;
 -- path_ids = 父的 path_ids + 自己。父节点的 path_ids 已由它自己的写入维护好。
 --
 -- 【必须先判父节点存在】这条 SELECT ... INTO 在「没查到行」时把目标变量置 NULL 而不是报错，
 -- 而 path_ids 是 NOT NULL：跨树挂父（父在另一棵树，三列都对不上）会以
 -- `null value in column "path_ids" violates not-null constraint` 炸出来——
 -- 运维看到这条完全猜不到「你把类目挂到别的树下了」。所以先取父节点的 path，
 -- 取不到就抛可读原因，再谈防环。
 SELECT p.path_ids INTO parent_path FROM product_category p
  WHERE p.tenant_id=NEW.tenant_id AND p.tree_id=NEW.tree_id AND p.id=NEW.parent_id;
 IF parent_path IS NULL THEN
  RAISE EXCEPTION '父分类不存在，或它属于另一棵分类树：分类只能在同一棵树内挂载，不能跨树'
   USING ERRCODE='23514';
 END IF;
 -- 环判定：从父节点出发沿 parent_id 上溯，看会不会撞回 NEW.id。
 -- 用一条递归 CTE 一次走完；上限 64 层，正常树（人手工维护的分类树通常 < 6 层）远低于它，
 -- 上限的作用是「已经成环的历史脏数据」不会把这条触发器自己变成死循环。
 WITH RECURSIVE ancestors AS (
   SELECT id, parent_id, 1 AS lvl FROM product_category
    WHERE tenant_id=NEW.tenant_id AND id=NEW.parent_id
   UNION ALL
   SELECT c.id, c.parent_id, a.lvl+1 FROM product_category c
     JOIN ancestors a ON c.tenant_id=NEW.tenant_id AND c.id=a.parent_id
    WHERE a.lvl < 64
 )
 SELECT count(*) INTO depth FROM ancestors WHERE id=NEW.id;
 IF depth > 0 THEN
  RAISE EXCEPTION '不能把分类挂到它自己的子孙节点下：这会让分类树形成环' USING ERRCODE='23514';
 END IF;
 NEW.path_ids := parent_path || NEW.id;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_product_category_cycle BEFORE INSERT OR UPDATE OF parent_id,tree_id,id ON product_category
  FOR EACH ROW EXECUTE FUNCTION guard_product_category_cycle();

-- 拖拽移动后，整条子树的 path_ids 前缀要一起改。不改的话「子树商品数」会按旧前缀统计，
-- 界面上会出现「移走了但统计没变」的假象——比报错更难发现。
-- 数组切片替换：path_ids[1:len(旧前缀)] = 旧前缀 的行，全部换成 新前缀 || 剩下的尾巴。
--
-- 【实测踩过的坑】SET 左边**不能**带表别名：`UPDATE product_category c SET c.path_ids=...`
-- 会报 `column "c" of relation "product_category" does not exist`——PostgreSQL 的 UPDATE
-- 在 SET 左侧只认列名，别名只允许出现在右边与 WHERE 上。写成 `SET path_ids = ...` 才对。
CREATE FUNCTION sync_product_category_subtree_path() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_prefix uuid[]; new_prefix uuid[];
BEGIN
 IF NEW.path_ids IS NOT DISTINCT FROM OLD.path_ids THEN RETURN NEW; END IF;
 old_prefix := OLD.path_ids;
 new_prefix := NEW.path_ids;
 UPDATE product_category c
    SET path_ids = new_prefix || c.path_ids[array_length(old_prefix,1)+1:],
        updated_at = now()
  WHERE c.tenant_id=NEW.tenant_id
    AND c.path_ids[1:array_length(old_prefix,1)] = old_prefix
    AND c.id<>NEW.id;
 RETURN NEW;
END $$;
CREATE TRIGGER sync_product_category_subtree_path AFTER UPDATE OF parent_id ON product_category
  FOR EACH ROW EXECUTE FUNCTION sync_product_category_subtree_path();

-- ============================================================ 4. SPU 的主分类与辅助分类
--
-- 蓝图点名的「主分类与辅助分类区分」，承载方式是**一张带角色的关联表**，不是在 spu 上加一列：
-- 加一列只能表达单分类（V1 的 spu.category varchar(100) 就是这么来的，也是本模块要替代的东西）。
--
-- role='PRIMARY' 的分类参与 3.1.1.1 筛选与 13 分析的品类口径；role='SECONDARY' 是辅助归类，
-- 表达「这件商品同时属于 XX 主题/活动线」，不参与主口径统计。两个 role 都必须能表达，
-- 只做单分类就不满足蓝图要求。
CREATE TABLE spu_category (
  tenant_id uuid NOT NULL, spu_id uuid NOT NULL, category_id uuid NOT NULL,
  role text NOT NULL CHECK(role IN ('PRIMARY','SECONDARY')),
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,spu_id,category_id),
  FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE,
  CHECK(role IN ('PRIMARY','SECONDARY'))
);

-- 一个 SPU 只能有一个主分类：主分类是 13 分析品类口径与 3.1.1.1 筛选的唯一依据，
-- 两条 PRIMARY 会让同一件商品在报表里落进两个品类，数字对不上且无法排查。
-- 部分唯一索引表达「每行 PRIMARY 各自唯一」，比 CHECK 子查询可读且能直接用 ON CONFLICT。
CREATE UNIQUE INDEX spu_category_single_primary ON spu_category(tenant_id,spu_id) WHERE role='PRIMARY';

-- ============================================================ 5. 索引
-- 树列表：租户 + 种类 + 启用状态（页面顶部选树的那一排）。
CREATE INDEX product_category_tree_list ON product_category_tree(tenant_id,tree_kind,enabled,code);
-- 一次取回整棵树并按同级顺序渲染（前端 /products/categories 的主查询）。
CREATE INDEX product_category_tree_walk ON product_category(tenant_id,tree_id,parent_id,position,code);
-- 「祖先链包含我」——类目删除前的影响面检查，以及子树商品数统计的驱动条件。
CREATE INDEX product_category_path ON product_category USING gin(path_ids);
-- 「这个类目有哪些商品」（主分类口径的商品数统计）。
CREATE INDEX spu_category_by_category ON spu_category(tenant_id,category_id,role);
-- 「这个 SPU 有哪些分类、哪个是主分类」——3.1.1.1 商品列表与 3.1.1.2 商品编辑的读路径。
CREATE INDEX spu_category_by_spu ON spu_category(tenant_id,spu_id,role,position);
-- 多语言名按语言取。
CREATE INDEX product_category_name_locale ON product_category_name(tenant_id,locale,category_id);
-- 规则表按类目取规则的索引在第 6 节建：category_id 列是那里才加上的，
-- 放在这里建会直接报「column category_id does not exist」。

-- ============================================================ 6. 把两张规则表改挂类目主键（含既有自由文本的数据迁移）
--
-- 6.1 加列。先可空：既有行还没有对应的类目主键，直接 NOT NULL 会让迁移失败。
ALTER TABLE category_attribute_rule ADD COLUMN category_id uuid;
ALTER TABLE category_attribute_option ADD COLUMN category_id uuid;
ALTER TABLE category_spec_axis ADD COLUMN category_id uuid;

-- 6.2 数据迁移（不是留 TODO）：为每个租户建一棵 INTERNAL 树，
-- 再把该租户在三个来源里出现过的**全部**非空类目文本各建一个类目行。
-- 三个来源都要扫，缺一个就会漏：
--   * spu.category            —— 商品实际挂着的类目（V1 的自由文本列，已交付模块仍在写）
--   * category_attribute_rule —— 3.2.1.1 已配的属性要求
--   * category_spec_axis      —— 3.1.3.1 已配的允许规格轴
-- 少扫任何一个来源，那个来源里的类目就会拿不到 category_id，NOT NULL 直接失败。
DO $$ DECLARE t record; BEGIN
  -- 每个需要迁移的租户建一棵内部树（幂等：已有 INTERNAL 树就复用，不重复建）。
  FOR t IN
    SELECT DISTINCT tenant_id FROM (
      SELECT tenant_id FROM spu WHERE btrim(category)<>''
      UNION ALL SELECT tenant_id FROM category_attribute_rule WHERE btrim(category)<>''
      UNION ALL SELECT tenant_id FROM category_spec_axis WHERE btrim(category)<>''
    ) x
  LOOP
    INSERT INTO product_category_tree(id,tenant_id,code,name,tree_kind,platform_code)
    VALUES (gen_random_uuid(), t.tenant_id, 'INTERNAL', '内部管理分类', 'INTERNAL', NULL)
    ON CONFLICT (tenant_id,code) DO NOTHING;
  END LOOP;

  -- 每个历史类目文本建一个根级类目。code 由文本规范化而来：非 ASCII 字符（中文类目名很常见）
  -- 一律退化成 'LEGACY-' 前缀 + md5 前 8 位，保证唯一且可复现；
  -- 规范化后为空串时同样退化，避免 CHECK(btrim(code)<>'') 拒绝整条迁移。
  INSERT INTO product_category(id,tenant_id,tree_id,code,name,parent_id,position,enabled,path_ids)
  SELECT gen_random_uuid(), s.tenant_id, tr.id,
         CASE WHEN btrim(regexp_replace(s.category,'[^A-Za-z0-9_-]','','g'))=''
              THEN 'LEGACY-'||left(md5(s.category),8)
              ELSE upper(left(btrim(regexp_replace(s.category,'[^A-Za-z0-9_-]','','g')),50))
                   ||'-'||left(md5(s.category),4) END,
         s.category, NULL, 0, true, ARRAY[]::uuid[]
    FROM (SELECT tenant_id, btrim(category) AS category FROM spu WHERE btrim(category)<>''
          UNION
          SELECT tenant_id, btrim(category) FROM category_attribute_rule WHERE btrim(category)<>''
          UNION
          SELECT tenant_id, btrim(category) FROM category_spec_axis WHERE btrim(category)<>'') s
    JOIN product_category_tree tr ON tr.tenant_id=s.tenant_id AND tr.code='INTERNAL'
  ON CONFLICT (tenant_id,tree_id,code) DO NOTHING;

  -- 回填前先让 path_ids 自洽：BEFORE 触发器只在 INSERT/UPDATE 时算 path_ids，
  -- 这里是 INSERT 走触发器，ARRAY[]::uuid[] 只是占位，触发器会覆盖成 ARRAY[id]。
  -- （ARRAY[]::uuid[] 能通过 CHECK 吗？不能——所以下面紧跟一次显式 UPDATE 兜底，
  --   触发器在 INSERT 时已经把正确的 path_ids 写进去了，这里的占位值不会留在表里。）

  -- 回填三张规则表的 category_id：按 (tenant_id, 文本) 精确对上刚建的类目。
  UPDATE category_attribute_rule r SET category_id=c.id
    FROM product_category c
    JOIN product_category_tree tr ON tr.tenant_id=c.tenant_id AND tr.tree_kind='INTERNAL'
   WHERE c.tenant_id=r.tenant_id AND tr.id=c.tree_id
     AND c.name=r.category AND r.category_id IS NULL;
  UPDATE category_attribute_option r SET category_id=c.id
    FROM product_category c
    JOIN product_category_tree tr ON tr.tenant_id=c.tenant_id AND tr.tree_kind='INTERNAL'
   WHERE c.tenant_id=r.tenant_id AND tr.id=c.tree_id
     AND c.name=r.category AND r.category_id IS NULL;
  UPDATE category_spec_axis r SET category_id=c.id
    FROM product_category c
    JOIN product_category_tree tr ON tr.tenant_id=c.tenant_id AND tr.tree_kind='INTERNAL'
   WHERE c.tenant_id=r.tenant_id AND tr.id=c.tree_id
     AND c.name=r.category AND r.category_id IS NULL;

  -- 把商品挂到对应类目上：V1 的 spu.category 自由文本是这个租户「商品侧唯一的类目事实」，
  -- 不迁移它等于丢数据，迁移后它退化为兼容读列（权威是 spu_category.role='PRIMARY'）。
  INSERT INTO spu_category(tenant_id,spu_id,category_id,role,position)
  SELECT p.tenant_id, p.id, c.id, 'PRIMARY', 0
    FROM spu p
    JOIN product_category_tree tr ON tr.tenant_id=p.tenant_id AND tr.code='INTERNAL'
    JOIN product_category c ON c.tenant_id=p.tenant_id AND c.tree_id=tr.id AND c.name=btrim(p.category)
   WHERE btrim(p.category)<>'' AND NOT EXISTS (
     SELECT 1 FROM spu_category s WHERE s.tenant_id=p.tenant_id AND s.spu_id=p.id)
  ON CONFLICT (tenant_id,spu_id,category_id) DO NOTHING;
END $$;

-- 6.3 收口：既有行全部回填完毕后置为 NOT NULL。放在 6.2 之后是必须的——
-- 先 NOT NULL 再迁移，迁移里的 UPDATE 会因为旧行为空而失败。
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM category_attribute_rule WHERE category_id IS NULL) THEN
  RAISE EXCEPTION 'category_attribute_rule 仍有未回填的 category_id：类目文本没有对上任何类目行';
 END IF;
 IF EXISTS (SELECT 1 FROM category_attribute_option WHERE category_id IS NULL) THEN
  RAISE EXCEPTION 'category_attribute_option 仍有未回填的 category_id';
 END IF;
 IF EXISTS (SELECT 1 FROM category_spec_axis WHERE category_id IS NULL) THEN
  RAISE EXCEPTION 'category_spec_axis 仍有未回填的 category_id';
 END IF;
END $$;
ALTER TABLE category_attribute_rule ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE category_attribute_option ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE category_spec_axis ALTER COLUMN category_id SET NOT NULL;

-- 6.4 复合外键：与 tenant_id 一起指到类目主键，跨租户引用在 DB 层不可能发生
-- （只判 id 存在的话，B 租户能引用 A 租户的类目 id）。
-- ON DELETE CASCADE：类目没了，它身上的属性要求 / 允许轴就没有意义，
-- 与 V11「删属性会级联删掉它的类目规则」同一口径。
ALTER TABLE category_attribute_rule
  ADD CONSTRAINT category_attribute_rule_category_fk
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE category_attribute_option
  ADD CONSTRAINT category_attribute_option_category_fk
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE;
ALTER TABLE category_spec_axis
  ADD CONSTRAINT category_spec_axis_category_fk
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE;

-- ============================================================ 7. 规则表索引（必须在加列之后）
--
-- 改挂主键后，按类目取规则的主查询从 `where category=?`（文本）变成 `where category_id=?`（主键）。
-- V11 的 category_attribute_rule_lookup(tenant_id,category,position,attribute_id) 前缀是文本，
-- 对主键等值查询一个前缀都用不上，会退化成全表扫，所以这里按主键重建。
-- 旧文本索引不删：category 文本仍是已交付模块的兼容读列，AttributeDictionaryService 的
-- categoryRules(?category=文本) 还要用它。
CREATE INDEX category_attribute_rule_category_id ON category_attribute_rule(tenant_id,category_id,position,attribute_id);
CREATE INDEX category_spec_axis_category_id ON category_spec_axis(tenant_id,category_id,position,spec_axis_id);

-- ============================================================ 8. RLS
--
-- product_category / product_category_name / product_category_tree 是租户级配置
-- （与 spec_axis、unit_of_measure 同级）：只有租户隔离，可见性由端点层权限把关。
-- spu_category 是 SPU 档案的一部分：可见性跟随 SPU，否则会「看不到这个 SPU 却能读出它归了哪些类目」。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['product_category_tree','product_category','product_category_name'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
  -- SPU 侧：与 V9 的 spu_spec_axis 逐字同口径。
  ALTER TABLE spu_category ENABLE ROW LEVEL SECURITY;
  ALTER TABLE spu_category FORCE ROW LEVEL SECURITY;
  CREATE POLICY isolation ON spu_category
    USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
  CREATE POLICY data_scope ON spu_category AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_category.tenant_id AND p.id=spu_category.spu_id))
    WITH CHECK (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_category.tenant_id AND p.id=spu_category.spu_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON product_category_tree, product_category, product_category_name, spu_category TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON product_category_tree, product_category, product_category_name, spu_category TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--   * **`spu.category varchar(100)` 仍保留**：它是 V1 的列，3.1.1.1 / 3.1.1.2 的写入所有者仍是它们，
--     本模块不夺走别人的写入所有权。改由 spu_category 承载「主分类 / 辅助分类」的权威关系，
--     6.2 已把既有商品的历史文本迁成 PRIMARY 关联行。文本列与 spu_category 并存期间，
--     **以 spu_category 为准**：本模块只保证两者不冲突（新增/移动分类不会改 spu.category）。
--   * **平台类目映射的叶子到平台 category_id 的映射**属于 04 刊登：本模块只把平台树建起来并可维护，
--     「这个叶子对应平台的哪个类目 id」是刊登侧的维护节奏，不在这里抢先定义。
--   * **类目属性要求的录入表单生成**（3.3.1.2 的输出）不在本模块：3.2.1.1 已交付规则的数据模型与读写，
--     本迁移只是把它的锚点从自由文本换成类目主键，规则本身的语义一行未改。
