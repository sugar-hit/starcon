-- 3.1.3.1 规格轴定义：租户级规格轴字典 + 与属性选项的同一个值源 + 类目允许规则 + SPU 绑定。
--
-- 边界（沿 V8 结尾注释继续，不重开已交付的账）：
--   * **`attribute_definition` 仍是 V1 的 SPU 作用域表，本迁移不改它的列语义、不迁移既有行。**
--     3.2.1.1（属性定义列表）未开发，但**表已在**；本模块直接读写它，不依赖那个管理页面。
--   * 规格轴（颜色/尺码/容量）要被**多个 SPU 复用**，所以它是**租户级字典** `spec_axis`，
--     而不是每个 SPU 自带一套。这一点是硬需求：退化成"每个 SPU 自己一套"会让 SPU 之间
--     无法共享值源，直接违反蓝图「规格轴与属性选项共享同一个值源」。
--   * **值源只有一个：`attribute_option`。** `spec_axis_option` 不允许凭空出现一个值，
--     必须能在一个已绑定 SPU 的 variant_axis 属性的 `attribute_option` 里找到同一行。
--     双向由两个触发器兜住（见下），不是应用层自觉。
--   * **`variant_axis=true` 的属性定义不被强制挂到规格轴字典上。** V1 允许它独立存在，
--     3.1.1/3.1.2 的既有测试也这么写。加"必须绑定"的触发器会把已交付模块的历史写入口打碎，
--     属于重开旧账。改为在概览里暴露 **未挂字典的 variant_axis 属性数** 作为治理缺口指标
--     （与 V8 的 `unregisteredSkus` 同一口径：0 与"没查过"含义不同）。
--   * `spu.category` 是 V1 的自由文本列（3.3.1.1 未开发，无类目表），类目规则按该文本绑定。
--     3.3.1.1 落地后应改挂 `product_category` 主键，本迁移不改写 V1 列。
--
-- 写入顺序（为什么必须有顺序）：
--   绑 SPU（写 attribute_definition + spu_spec_axis）→ 登记选项（先写 attribute_option，
--   再写 spec_axis_option）。`guard_spec_axis_option_source` 在 spec_axis_option 落库那一刻
--   去值源核对，所以必须先有值源行。应用层 `SpecAxisService` 在同一个事务里按这个顺序写，
--   应用层与 DB 层看到的是同一份事实。

-- ------------------------------------------------------------ 规格轴字典（租户级，可被多个 SPU 复用）
CREATE TABLE spec_axis (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
 -- 轴编码：与承载它的 variant_axis 属性定义同 code（trigger 强制），
 -- 所以它走 CatalogService.code 的 [A-Z0-9_-] 词表：COLOR / SIZE / CAPACITY。
 code varchar(64) NOT NULL, name varchar(100) NOT NULL,
 -- 停用的轴不再出现在绑定候选里，但**已绑定的 SPU 与既有选项照常可读**：
 -- 停用是"不许再新建"，不是"抹掉过去"。
 enabled boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
 CHECK(btrim(code)<>''), CHECK(btrim(name)<>'')
);

-- ------------------------------------------------------------ 类目允许的规格轴规则
-- 放在绑定表之前建：guard_spu_spec_axis 要读它（plpgsql 体在调用时才解析表名，
-- 但先建后用让这份迁移的阅读顺序与依赖顺序一致）。
-- 类目是 V1 的自由文本分类列（spu.category varchar(100)），不是外键：
-- 3.3.1.1 分类树未开发，没有可引用的类目主键。
CREATE TABLE category_spec_axis (
 tenant_id uuid NOT NULL, category varchar(100) NOT NULL, spec_axis_id uuid NOT NULL,
 -- required=true 表示该类目下每个 SPU 都必须有这条轴；false 表示"允许但非必须"。
 required boolean NOT NULL DEFAULT false,
 position int NOT NULL DEFAULT 0 CHECK(position>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,category,spec_axis_id),
 FOREIGN KEY(tenant_id,spec_axis_id) REFERENCES spec_axis(tenant_id,id) ON DELETE CASCADE,
 CHECK(btrim(category)<>'')
);

-- ------------------------------------------------------------ SPU ↔ 规格轴绑定
-- 一行 = "这个 SPU 用这条规格轴，并由这个 variant_axis 属性定义承载它的选项"。
-- attribute_definition_id 是**复合外键**指向 attribute_definition(tenant_id,id)：
-- 承载属性必须真实存在，且 trigger 进一步校验它是本 SPU 的 variant_axis 属性、且 code 与轴一致。
CREATE TABLE spu_spec_axis (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL, spec_axis_id uuid NOT NULL,
 attribute_definition_id uuid NOT NULL,
 position int NOT NULL DEFAULT 0 CHECK(position>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 -- 同一 SPU 上同一条轴只能绑一次（与 V1 的 UNIQUE(tenant_id,spu_id,code) 同口径）。
 UNIQUE(tenant_id,spu_id,spec_axis_id),
 -- 同一 SPU 上同一个属性定义不能被两条轴绑定（否则一个属性有两个轴身份）。
 UNIQUE(tenant_id,spu_id,attribute_definition_id),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,spec_axis_id) REFERENCES spec_axis(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,attribute_definition_id) REFERENCES attribute_definition(tenant_id,id) ON DELETE CASCADE
);

-- 绑定守卫：承载属性必须是**本 SPU** 的 variant_axis 属性，且 code 与规格轴一致。
-- code 一致这条是"轴与属性不会各叫各的"的技术兑现点——3.2.1.1 未开发时最容易出的错
-- 就是建了个 code=COLOR 的属性、又把 SIZE 轴绑上去，两边从此对不上。
-- 类目规则：类目配了允许的轴时，这条轴必须在允许名单里（required 的轴必须已绑定）。
CREATE FUNCTION guard_spu_spec_axis() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a attribute_definition; axis_code text; allowed text[];
BEGIN
 SELECT * INTO STRICT a FROM attribute_definition
  WHERE tenant_id=NEW.tenant_id AND id=NEW.attribute_definition_id;
 IF a.spu_id<>NEW.spu_id THEN
  RAISE EXCEPTION '承载属性属于另一个 SPU，不能作为本 SPU 的规格轴' USING ERRCODE='23514';
 END IF;
 IF NOT a.variant_axis THEN
  RAISE EXCEPTION '属性「%」不是规格轴属性（variant_axis=false），不能承载规格轴', a.name USING ERRCODE='23514';
 END IF;
 SELECT s.code INTO STRICT axis_code FROM spec_axis s
  WHERE tenant_id=NEW.tenant_id AND id=NEW.spec_axis_id;
 IF a.code<>axis_code THEN
  RAISE EXCEPTION '规格轴编码「%」与承载属性编码「%」不一致：两者必须同名，否则值源无法对齐', axis_code, a.code USING ERRCODE='23514';
 END IF;
 -- 类目允许规则（只在该 SPU 的类目确实配了规则时生效）。
 -- **只校验"这条轴在不在允许名单里"，不校验"必绑轴绑齐了没有"**：
 -- 后者放在面板的 missingRequiredAxisCount 上作为缺口提示。放进触发器会让规则无法满足——
 -- 绑第一条必绑轴时第二条还没绑，检查必然失败，而第二条又必须先有绑定才谈得上"齐"。
 SELECT array_agg(c.spec_axis_id::text) INTO allowed FROM category_spec_axis c
  JOIN spu p ON p.tenant_id=c.tenant_id AND p.category=c.category
  WHERE c.tenant_id=NEW.tenant_id AND p.id=NEW.spu_id;
 IF allowed IS NOT NULL THEN
  IF NOT (NEW.spec_axis_id::text = ANY(allowed)) THEN
   RAISE EXCEPTION '该 SPU 所属类目不允许这条规格轴绑定' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_spu_spec_axis BEFORE INSERT OR UPDATE ON spu_spec_axis
 FOR EACH ROW EXECUTE FUNCTION guard_spu_spec_axis();

-- ------------------------------------------------------------ 规格轴选项（值源 = attribute_option）
CREATE TABLE spec_axis_option (
 tenant_id uuid NOT NULL, spec_axis_id uuid NOT NULL, value varchar(100) NOT NULL,
 position int NOT NULL DEFAULT 0 CHECK(position>=0),
 -- 停用的选项不再出现在矩阵候选里，但**仍留在 attribute_option**：历史 SKU 的属性值还要能解析，
 -- 「停用不删除」（与 3.2.1.2 属性选项管理同口径）。
 enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,spec_axis_id,value),
 -- value 的长度与 attribute_option.value 相同：两者存的是同一个值，不是两份拷贝。
 FOREIGN KEY(tenant_id,spec_axis_id) REFERENCES spec_axis(tenant_id,id) ON DELETE CASCADE
);

-- 【硬约束·正向】规格选项不得独立于属性选项存在：
-- 写入 spec_axis_option 的每个值，都必须能在一个**已绑定到本轴的 SPU** 的 variant_axis 属性的
-- attribute_option 里找到同一行。找不到就是孤儿，DB 直接拒（23514 + 可读原因）。
-- 用触发器而不是复合外键，是因为 attribute_option 的主键是 (tenant_id,attribute_id,value)，
-- 而 spec_axis_option 要跨**任意一个**已绑定属性去匹配——单条复合外键表达不了"存在其一"。
CREATE FUNCTION guard_spec_axis_option_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src text;
BEGIN
 SELECT o.value INTO src FROM attribute_option o
  JOIN spu_spec_axis sa ON sa.tenant_id=o.tenant_id AND sa.attribute_definition_id=o.attribute_id
  JOIN attribute_definition d ON d.tenant_id=o.tenant_id AND d.id=o.attribute_id
  WHERE sa.tenant_id=NEW.tenant_id AND sa.spec_axis_id=NEW.spec_axis_id
    AND o.value=NEW.value AND d.variant_axis
  LIMIT 1;
 IF src IS NULL THEN
  RAISE EXCEPTION
   '规格选项「%」在属性选项值源中不存在：规格选项必须与属性选项共享同一个值源（蓝图硬约束）',
   NEW.value USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_spec_axis_option_source BEFORE INSERT OR UPDATE ON spec_axis_option
 FOR EACH ROW EXECUTE FUNCTION guard_spec_axis_option_source();

-- 【硬约束·反向】值源不能被抽走：attribute_option 上仍被规格轴引用的行不允许删或改值。
-- 没有这条，只删值源行就会让 spec_axis_option 变成悬空引用——"同一值两处维护"的反面。
CREATE FUNCTION guard_attribute_option_in_use() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE users int;
BEGIN
 -- BEFORE DELETE 时没有 NEW：不能拿 NEW.value 去判空，那会把"删一行"误报成"值置空"。
 -- 两种操作都用 OLD.value 查引用，区别只在报错文案。
 IF TG_OP='UPDATE' AND NEW.value=OLD.value THEN RETURN NEW; END IF;
 SELECT count(*) INTO users FROM spec_axis_option o
  WHERE o.tenant_id=OLD.tenant_id AND o.value=OLD.value;
 IF users>0 THEN
  RAISE EXCEPTION '属性选项「%」仍被 % 条规格轴选项引用，不能删除或改值：规格选项与属性选项共享同一个值源',
   OLD.value, users USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND NEW.value IS NULL THEN
  RAISE EXCEPTION '属性选项的值不允许置空：它同时是规格选项的值源' USING ERRCODE='23514';
 END IF;
 -- BEFORE DELETE 时 NEW 是 NULL，返回它等于**让 PostgreSQL 静默跳过这一行的删除**：
 -- 删不掉也不报错（实测踩过，症状是"删除成功但行还在"）。DELETE 必须返回 OLD。
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_attribute_option_in_use BEFORE DELETE OR UPDATE ON attribute_option
 FOR EACH ROW EXECUTE FUNCTION guard_attribute_option_in_use();

-- ------------------------------------------------------------ 索引
-- 轴字典：绑定候选按「启用的轴 + 编码」筛。
CREATE INDEX spec_axis_enabled ON spec_axis(tenant_id,enabled,code);
-- 「某 SPU 用哪些轴、按什么顺序」是矩阵计算的热读路径。
CREATE INDEX spu_spec_axis_order ON spu_spec_axis(tenant_id,spu_id,position,spec_axis_id);
-- 「哪些 SPU 用了这条轴」：删轴前的引用检查与绑定影响面统计。
CREATE INDEX spu_spec_axis_reverse ON spu_spec_axis(tenant_id,spec_axis_id);
-- 轴的选项列表按 position 排。
CREATE INDEX spec_axis_option_order ON spec_axis_option(tenant_id,spec_axis_id,position,value);
-- 值源反向查：删属性选项前先看被哪些规格轴引用（trigger 也要走这条索引）。
CREATE INDEX attribute_option_value_lookup ON attribute_option(tenant_id,value);
-- 类目规则：按类目取允许的轴。
CREATE INDEX category_spec_axis_lookup ON category_spec_axis(tenant_id,category,position);

DO $$ DECLARE tbl text; BEGIN
 -- 租户级字典（与 unit_of_measure 同级）：只有租户隔离，可见性由端点层权限把关。
 FOREACH tbl IN ARRAY ARRAY['spec_axis','spec_axis_option','category_spec_axis'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- 绑定是 SPU 档案的一部分：可见性跟随 SPU，否则"看不到这个 SPU 却能读出它用哪些规格轴"。
 ALTER TABLE spu_spec_axis ENABLE ROW LEVEL SECURITY;
 ALTER TABLE spu_spec_axis FORCE ROW LEVEL SECURITY;
 CREATE POLICY isolation ON spu_spec_axis
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
 CREATE POLICY data_scope ON spu_spec_axis AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_spec_axis.tenant_id AND p.id=spu_spec_axis.spu_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_spec_axis.tenant_id AND p.id=spu_spec_axis.spu_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON spec_axis, spu_spec_axis, spec_axis_option, category_spec_axis TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON spec_axis, spu_spec_axis, spec_axis_option, category_spec_axis TO starcon_app;