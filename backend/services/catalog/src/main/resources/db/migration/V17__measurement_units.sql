-- 3.3.3.1 计量单位（字典视角）：单位组 + 组内换算关系 + 量纲守卫（组级路径）。
--
-- ============================================================ 本迁移与已交付的 V8 是什么关系
--
-- V8（3.1.2.4 单位与换算）建了 `unit_of_measure` 单位字典（编码 / 名称 / 量纲 / 数量精度 /
-- 采购·销售·存储可用性 / 启停），并把「量纲守卫」落在 **SKU 侧**的 `sku_unit_conversion` 上。
-- 它服务的是「这一个 SKU 的箱与件之间几比一」——换算挂在商品档案上。
--
-- 蓝图 3.3.3.1 写的是另一件事：「并定义**单位组内的**换算关系」「输出：单位记录 + 换算查询能力」，
-- 与 SKU 无关：1 千克 = 1000 克 这件事本身成立，与它挂在哪个 SKU 上无关。
-- 那条路径当前**完全不存在**，本迁移补的就是它。
--
-- 边界（不重开旧账）：
--   * `unit_of_measure` 的写入所有权仍是 UnitConversionService，本迁移**不新增**它的写入口、
--     不复制一份平行的单位字典表。字典视角页面消费既有端点。
--   * `sku_unit_conversion` / `sku_unit_conversion_snapshot` 的既有语义一个字不动：
--     单据冻结仍然引用 SKU 侧换算行，组级换算不参与单据冻结（下游 06/07/08 若要按组换算，
--     走 3.1.2.4 的冻结路径，组只提供"这个换算存在且是多少"的权威答案）。
--   * 量纲守卫在**两条路径上都要有**。V8 的 `guard_unit_conversion_dimension` 只守 SKU 换算表，
--     本迁移的守卫守组与组内换算，两者互不替代：绕过 SKU 直连组级路径同样不能把件换算成千克。
--
-- ============================================================ 1. 单位组
--
-- 单位组是租户级字典（与 product_category / spec_axis / brand / unit_of_measure 同级）。
-- 一个组 = 同一量纲内的一组单位，组内任意两个单位之间可换算，跨组与跨量纲不允许。
--
-- 「组带 dimension 列」是**刻意冗余**，不是重复存储事实：
--   * 派生（只从成员算量纲）会让「按量纲筛组」必须 join 成员表再 distinct，
--     而筛组是这个页面最常见的操作；冗余一列换来一条 (tenant_id,dimension,code) 索引。
--   * 冗余必须被强制，否则它会变成第二个真相来源：下面的 `guard_unit_group_dimension`
--     要求 base_unit 与每个成员的量纲都等于组量纲，写不进不一致的数据。
--     应用层（UnitGroupService）在建组与加成员时也判一次并给出人话，两层都在。
CREATE TABLE unit_group (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 组编码：机器标识，租户内唯一。允许中文（与单位码同一口径：运营就是这么叫的，
  -- 「重量组」「长度组」是中文业务词），但不允许空白与超长。
  code varchar(32) NOT NULL,
  name varchar(100) NOT NULL,
  -- 量纲：组内所有单位必须与它同量纲。取值与 unit_of_measure.dimension 完全一致
  -- （WEIGHT / LENGTH / VOLUME / COUNT / PACKAGE），不另造词表。
  dimension text NOT NULL CHECK(dimension IN ('WEIGHT','LENGTH','VOLUME','COUNT','PACKAGE')),
  -- 组基础单位：组内换算的基准。**必须是一个已登记的单位，且属于本组**。
  -- 组基础单位本身不占换算行（比例恒为 1），与 V8「基础单位不在换算表里」同一条纪律。
  base_unit_code varchar(32) NOT NULL,
  note text NOT NULL DEFAULT '',
  -- 停用的组不再出现在新建换算的候选里，但**已配置的组内换算版本照常可读**：
  -- 停用是"不许再新建"，不是"抹掉过去"（与 unit_of_measure.enabled 同语义）。
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>''), CHECK(btrim(name)<>''), CHECK(btrim(base_unit_code)<>''),
  -- 基础单位必须在字典里：组基础单位参与每一次组内换算计算，
  -- 指向一个不存在的单位会让 1 千克 = ? 克 这条算式永远算不出来。
  -- 这条外键能建，是因为 unit_of_measure 有 UNIQUE(tenant_id,code)；
  -- V8 没给 sku_unit_conversion 建外键是**另一个理由**（V6 之前写入的历史行 unit_code 可能不在字典里，
  -- 加外键会让既有事实写不进去），本表是全新表、没有历史包袱，所以可以要这个强约束。
  FOREIGN KEY(tenant_id,base_unit_code) REFERENCES unit_of_measure(tenant_id,code)
);

-- ============================================================ 2. 组成员
--
-- 组成员关系是「这个组有哪些单位」的完整描述，按 (tenant_id,group_id,unit_code) 建主键：
-- 同一个单位在一个组里只能有一条，不允许两种写法各存一份，
-- 换算查询时也就不会在两条候选之间随机挑一条。
CREATE TABLE unit_group_member (
  tenant_id uuid NOT NULL, group_id uuid NOT NULL, unit_code varchar(32) NOT NULL,
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,group_id,unit_code),
  FOREIGN KEY(tenant_id,group_id) REFERENCES unit_group(tenant_id,id) ON DELETE CASCADE,
  -- 单位必须是字典里已登记的单位：引用一个查不到的单位，量纲无从校验、显示名无从回显。
  -- 与 V8 相反（那里是软引用）是刻意的：V8 要兼容历史行，这里只接受本模块写入的行。
  FOREIGN KEY(tenant_id,unit_code) REFERENCES unit_of_measure(tenant_id,code) ON DELETE CASCADE
);

-- ============================================================ 3. 组内换算关系（版本化 + 生效区间）
--
-- 一行 = 某个成员单位在某个生效区间里，「1 个它 = 多少个组基础单位」。
-- 与 sku_unit_conversion 同构：比例必须版本化、区间不重叠、按生效日期取版本、
-- 查不到就报错不回退到最近一版。
CREATE TABLE unit_group_conversion (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, group_id uuid NOT NULL,
  unit_code varchar(32) NOT NULL,
  -- 比例：1 个该单位等于多少个**组基础单位**（1 千克 = 1000 克，组基础单位为克 → 1000）。
  -- 必须严格大于 0：0 会让换算结果恒为 0，负数会让出库变成入库。
  quantity_per_base numeric(19,6) NOT NULL CHECK(quantity_per_base>0),
  effective_from date NOT NULL, effective_to date,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一组的同一单位同一时刻只能有一个版本生效。
  UNIQUE(tenant_id,group_id,unit_code,effective_from),
  -- 换算行必须指向**本组的一个成员**：这条外键把"组内换算"这个名字落到 DB 层。
  -- 少了它，一条指向组外单位的换算行会让矩阵查询算出一个看似合理、实际无意义的比例。
  FOREIGN KEY(tenant_id,group_id,unit_code) REFERENCES unit_group_member(tenant_id,group_id,unit_code)
    ON DELETE CASCADE,
  CHECK(btrim(unit_code)<>''),
  CHECK(effective_to IS NULL OR effective_to>=effective_from)
);

-- 生效区间不重叠。与 V6 的 validate_unit_conversion_window 同一形状，只是作用域换成了组。
-- 「同一时刻只有一个版本生效」是硬要求：两条都生效时换算查询只能随便挑一条，
-- 而运营看到的会是一个完全正常的数字。
CREATE FUNCTION validate_unit_group_conversion_window() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM unit_group_conversion c
   WHERE c.tenant_id=NEW.tenant_id AND c.group_id=NEW.group_id AND c.id<>NEW.id
     AND c.unit_code=NEW.unit_code
     AND daterange(c.effective_from,coalesce(c.effective_to,'infinity'::date),'[]')
      && daterange(NEW.effective_from,coalesce(NEW.effective_to,'infinity'::date),'[]')) THEN
  RAISE EXCEPTION '单位组换算生效区间与已有版本重叠' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_unit_group_conversion_window BEFORE INSERT OR UPDATE ON unit_group_conversion
 FOR EACH ROW EXECUTE FUNCTION validate_unit_group_conversion_window();

-- ============================================================ 4. 量纲守卫（组级路径）
--
-- 蓝图 3.3.3.1 的关系约束：「量纲不匹配的单位之间不允许换算（如『件』不能换算成『千克』）」。
-- V8 的 guard_unit_conversion_dimension 只守 SKU 换算表；下面三条守组级路径，三者缺一不可：
--
--   (1) guard_unit_group_dimension          —— 建组时组基础单位的量纲要等于组量纲；
--   (2) guard_unit_group_member_dimension   —— 每次加成员时该单位量纲要等于组量纲；
--   (3) guard_unit_group_conversion_dimension —— 每次写换算版本时再判一次（含 base_unit 不占行的约束）。
--
-- (3) 在有外键与 (2) 的前提下是冗余的，但冗余在这里是**有意的**：
-- 这三张表是本模块的写入所有者，未来任何一次绕过应用层的写入（脚本、运维 SQL、别的服务）
-- 都必须撞上同一个量纲检查。应用层（UnitGroupService）也判一次并给出人话——
-- 触发器抛的 23514 对运营没有可执行信息。
--
-- 为什么用触发器而不是 CHECK 子查询：CHECK 里不能查另一张表；
-- 而"单位量纲对不上"必须是一个可解释的状态（应用层拒绝并提示换一个同量纲的单位），
-- 不是一条语焉不详的数据库报错。
CREATE FUNCTION guard_unit_group_dimension() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base_dimension text;
BEGIN
 SELECT u.dimension INTO base_dimension FROM unit_of_measure u
  WHERE u.tenant_id=NEW.tenant_id AND u.code=NEW.base_unit_code;
 -- 基础单位已被外键保证存在；这里只判量纲。
 IF base_dimension IS NULL THEN RETURN NEW; END IF;
 IF base_dimension<>NEW.dimension THEN
  RAISE EXCEPTION '量纲不匹配：组「%」声明的量纲是 %，基础单位「%」属于 %，两者必须一致',
   NEW.code, NEW.dimension, NEW.base_unit_code, base_dimension USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_unit_group_dimension BEFORE INSERT OR UPDATE ON unit_group
 FOR EACH ROW EXECUTE FUNCTION guard_unit_group_dimension();

CREATE FUNCTION guard_unit_group_member_dimension() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE member_dimension text; group_dimension text; group_code text;
BEGIN
 SELECT u.dimension INTO member_dimension FROM unit_of_measure u
  WHERE u.tenant_id=NEW.tenant_id AND u.code=NEW.unit_code;
 SELECT g.dimension, g.code INTO group_dimension, group_code
   FROM unit_group g WHERE g.tenant_id=NEW.tenant_id AND g.id=NEW.group_id;
 -- 任一侧查不到：放行。组与单位都由外键保证存在，这里不为并发删除制造写入障碍。
 IF member_dimension IS NULL OR group_dimension IS NULL THEN RETURN NEW; END IF;
 IF member_dimension<>group_dimension THEN
  RAISE EXCEPTION '量纲不匹配：单位「%」属于 %，单位组「%」属于 %，不同量纲的单位不能放进同一个组',
   NEW.unit_code, member_dimension, group_code, group_dimension USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_unit_group_member_dimension BEFORE INSERT OR UPDATE ON unit_group_member
 FOR EACH ROW EXECUTE FUNCTION guard_unit_group_member_dimension();

-- 组基础单位不能被移出组：移走之后组就没有基准了，
-- 而组里已配置的换算版本全部是「相对组基础单位」的比例。
-- 正确做法是先把基础单位改到组内另一个单位，再移除原基础单位。
CREATE FUNCTION refuse_unit_group_base_removal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE group_base text;
BEGIN
 SELECT g.base_unit_code INTO group_base FROM unit_group g
  WHERE g.tenant_id=OLD.tenant_id AND g.id=OLD.group_id;
 IF group_base IS NOT NULL AND group_base=OLD.unit_code THEN
  RAISE EXCEPTION '组基础单位「%」不能移出单位组：组内换算比例都是相对它定义的。请先把基础单位改到组内另一个单位', OLD.unit_code USING ERRCODE='42501';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_unit_group_base_removal BEFORE DELETE ON unit_group_member
 FOR EACH ROW EXECUTE FUNCTION refuse_unit_group_base_removal();

CREATE FUNCTION guard_unit_group_conversion_dimension() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE member_dimension text; group_dimension text; base_code text;
BEGIN
 SELECT u.dimension INTO member_dimension FROM unit_of_measure u
  WHERE u.tenant_id=NEW.tenant_id AND u.code=NEW.unit_code;
 SELECT g.dimension, g.base_unit_code INTO group_dimension, base_code
   FROM unit_group g WHERE g.tenant_id=NEW.tenant_id AND g.id=NEW.group_id;
 IF base_code IS NULL THEN RETURN NEW; END IF;
 -- 组基础单位不占换算行：它的比例恒为 1，另存一行会让同一个事实有两个来源
 -- （与 V8 头部「基础单位不在换算表里」同一个理由）。
 IF NEW.unit_code=base_code THEN
  RAISE EXCEPTION '组基础单位「%」不需要换算行：它的比例恒为 1', NEW.unit_code USING ERRCODE='23514';
 END IF;
 IF member_dimension IS NULL OR group_dimension IS NULL THEN RETURN NEW; END IF;
 IF member_dimension<>group_dimension THEN
  RAISE EXCEPTION '量纲不匹配：单位「%」属于 %，单位组基准单位「%」属于 %，两者之间不允许换算',
   NEW.unit_code, member_dimension, base_code, group_dimension USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_unit_group_conversion_dimension BEFORE INSERT OR UPDATE ON unit_group_conversion
 FOR EACH ROW EXECUTE FUNCTION guard_unit_group_conversion_dimension();

-- ============================================================ 5. 索引
--
-- 列表页主查询：按量纲筛 + 按启用状态筛，按编码稳定排序。
CREATE INDEX unit_group_list ON unit_group(tenant_id,dimension,enabled,code);
-- 「这个单位属于哪些组」——加成员时的重复检查、单位详情里的分组展示都走它。
CREATE INDEX unit_group_member_by_unit ON unit_group_member(tenant_id,unit_code,group_id);
-- 「这个组的成员按什么顺序显示」。
CREATE INDEX unit_group_member_by_group ON unit_group_member(tenant_id,group_id,position,unit_code);
-- 换算查询的主路径：按 asOf 在组内取当前生效版本。与 sku_unit_conversion_effective 同形。
CREATE INDEX unit_group_conversion_effective
 ON unit_group_conversion(tenant_id,group_id,unit_code,effective_from DESC,effective_to DESC NULLS LAST);
-- 开放区间（当前仍生效）的组内换算行是换算计算的默认命中对象。
CREATE UNIQUE INDEX unit_group_conversion_open
 ON unit_group_conversion(tenant_id,group_id,unit_code) WHERE effective_to IS NULL;
-- 「哪些组停用了」与「哪些组还没有配任何组内换算」是概览页的治理缺口指标。
CREATE INDEX unit_group_without_conversion
 ON unit_group(tenant_id,dimension) WHERE enabled;

-- ============================================================ 6. RLS 与授权
--
-- 三张表都是租户级配置（与 brand / product_category / unit_of_measure 同级）：
-- 只有租户隔离，没有 SKU 可见性策略，可见性由端点层权限把关。
-- 数据范围（owner_id / department_id）那套是给「个人负责的业务单据」用的（V3），字典表不适用。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['unit_group','unit_group_member','unit_group_conversion'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON unit_group, unit_group_member, unit_group_conversion TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON unit_group, unit_group_member, unit_group_conversion TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **`sku.base_unit` / `sku_unit_conversion` 不改挂组级换算**：那是 3.1.2.4 已交付的写入所有权，
--     组级换算不改写已冻结的单据快照。在那之前，**SKU 侧换算的权威仍是 sku_unit_conversion**，
--     本模块只提供「同量纲单位组内的通用换算关系」与它的查询能力。
--   * **组内换算不参与单据冻结**：06/07/08 要冻结的是"这个 SKU 当时用的比例"，
--     走 3.1.2.4 的 sku_unit_conversion_snapshot；组只回答"这两个单位之间换算是多少"。
--   * **`unit_of_measure` 的删除仍由 UnitConversionService 把关**：删一个已被组成员引用的单位时，
--     外键 ON DELETE CASCADE 会连带删掉该单位在所有组里的成员行与换算行。
--     这是刻意的（不留指向不存在单位的成员行），服务层会在给出提示后继续执行删除。
--
-- ============================================================ 移出成员为什么会连带删掉组内换算（口径说明）
--
-- `unit_group_member` 对 `unit_group_conversion` 的外键带 ON DELETE CASCADE，
-- 所以"把某个单位移出组"会同时删掉它的组内换算版本。这不是疏漏，是口径：
--
--   * **组内换算是字典配置**。这个单位已经不属于这个组了，
--     "1 千克 = 1000 克"这条关系也就没有归属；留着它会让换算矩阵出现一条
--     指向非成员单位的比例，而这正是外键要防的形状。
--     与 V16「类目没了，品牌属于该类目的关联也就没有意义」同一口径。
--   * **单据冻结快照才是不可变事实**。`sku_unit_conversion_snapshot` 在 V8 里
--     用触发器禁止 UPDATE/DELETE，本迁移一个字都没动它。
--     两者的不可变性口径不同：配置可改，已发生单据不可改。
--
-- 运营要精细控制"删到哪一版"时，可以单独删除已闭合的历史版本
-- （UnitGroupService.deleteConversion）；当前仍开放生效的那一版不允许删，
-- 删掉等于静默让这个单位变成不可换算。