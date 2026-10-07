-- 3.1.2.4 单位与换算：单位字典（unit_of_measure）+ 量纲校验 + 单据冻结快照。
--
-- 边界（沿 V6 结尾注释继续，不重开已交付的账）：
--   * `sku_unit_conversion` 由 V6 建立，**已经是版本化 + 生效区间不重叠**（V6 的
--     validate_unit_conversion_window 触发器）。本迁移不重建它、不改它的列语义，只补两件事：
--       1) 单位字典 `unit_of_measure`（代码 + 名称 + 量纲 + 数量精度 + 可用场景）；
--       2) 量纲守卫——"件"不能换算成"千克"（蓝图 3.3.3.1 的关系约束，V6 注释点名由 3.1.2.4 补）。
--   * **基础单位不在换算表里**。基础单位是 `sku.base_unit`（V1 的列），换算表只放**其他**单位
--     相对基础单位的比例。再插一条 role='BASE'、比例=1 的行会让同一个事实有两个来源，
--     正是 V7 头部反对的形状。面板把两者分开呈现：基础单位来自 SKU，其余来自换算表。
--   * 单位 code **允许中文**（'件' / '箱' / '千克'）。它不是 SKU 内部编码，不走
--     CatalogService.code 的 [A-Z0-9] 词表——历史数据里单位码本来就是中文，
--     套 ASCII 词表会把既有数据判成非法。
--   * 换算表**不加**指向字典的外键。V6 之前写入的换算行（导入/历史遗留）unit_code 可能不在字典里，
--     加外键会让 3.1.2.2 的既有测试与真实历史数据直接写不进去。字典的强约束放在**本模块的写入口**
--     （UnitConversionService 建换算行时要求单位已登记），DB 层只对两侧都已登记的行做量纲守卫。
--     这是刻意的取舍：宁可让遗留行带着"未登记单位"继续可读，也不让迁移把既有事实打碎。
--
-- 快照：sku_unit_conversion_snapshot。单据生成时把**当时生效的比例**连同换算结果一起落库，
-- 并用触发器禁止 UPDATE/DELETE——"之后修改比例不影响已发生单据"这条蓝图要求，靠不可变快照兑现，
-- 不是靠应用层自觉。
CREATE TABLE unit_of_measure (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
 -- 单位代码：允许中文与拉丁字母数字，1~32 位，长度受 SKU.base_unit varchar(32) 约束。
 code varchar(32) NOT NULL, name varchar(100) NOT NULL,
 -- 量纲：量纲不同的单位之间不允许换算（件 → 千克 必须在应用层被拒）。
 dimension text NOT NULL CHECK(dimension IN ('WEIGHT','LENGTH','VOLUME','COUNT','PACKAGE')),
 -- 数量精度：该单位数量允许的小数位，0~6。与换算表 numeric(19,6) 的存储精度一致，
 -- 展示层用它决定四舍五入到几位——存 6 位是为了不丢精度，不是给用户看 6 位小数。
 scale int NOT NULL DEFAULT 0 CHECK(scale BETWEEN 0 AND 6),
 allow_purchase boolean NOT NULL DEFAULT true, allow_sale boolean NOT NULL DEFAULT true,
 allow_storage boolean NOT NULL DEFAULT true,
 -- 停用的单位不再出现在新建换算的候选里，但**已冻结的快照与历史换算版本照常可读**：
 -- 停用是"不许再新建"，不是"抹掉过去"。
 enabled boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
 CHECK(btrim(code)<>''), CHECK(btrim(name)<>'')
);

-- 量纲守卫（只对两侧都已登记的行生效）。
-- 用触发器而不是外键/CHECK 子查询有两个原因：一是 CHECK 里不能查另一张表；
-- 二是"单位未登记"必须是一个**可解释的状态**（应用层拒绝并提示先登记单位），
-- 而不是一条语焉不详的数据库报错。
CREATE FUNCTION guard_unit_conversion_dimension() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE from_dimension text; base_dimension text; base_code text;
BEGIN
 SELECT u.dimension INTO from_dimension FROM unit_of_measure u
  WHERE u.tenant_id=NEW.tenant_id AND u.code=NEW.unit_code;
 SELECT s.base_unit INTO base_code FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id;
 IF base_code IS NULL THEN RETURN NEW; END IF;
 SELECT u.dimension INTO base_dimension FROM unit_of_measure u
  WHERE u.tenant_id=NEW.tenant_id AND u.code=base_code;
 -- 任一侧未登记：放行。未登记由应用层在写入口拦住，这里不为遗留数据制造写入障碍。
 IF from_dimension IS NULL OR base_dimension IS NULL THEN RETURN NEW; END IF;
 IF from_dimension<>base_dimension THEN
  RAISE EXCEPTION '量纲不匹配：「%」属于 %，基础单位「%」属于 %，两者之间不允许换算',
   NEW.unit_code, from_dimension, base_code, base_dimension USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_unit_conversion_dimension BEFORE INSERT OR UPDATE ON sku_unit_conversion
 FOR EACH ROW EXECUTE FUNCTION guard_unit_conversion_dimension();

-- 单据冻结快照。
-- 一行 = 某张单据的某一行在**生成那一刻**用的换算关系与换算结果。
-- 关键在于比例与结果都**落在行上**：之后改 sku_unit_conversion 的生效版本，改不动这里。
CREATE TABLE sku_unit_conversion_snapshot (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 -- 单据类型：PURCHASE 06 采购 / INBOUND 07 入库 / OUTBOUND 07 出库 / PICKING 08 拣货
 -- / TRANSFER 07 调拨 / COUNT 07 盘点。
 document_type text NOT NULL CHECK(document_type IN ('PURCHASE','INBOUND','OUTBOUND','PICKING','TRANSFER','COUNT')),
 -- 单据号：上游服务的单据标识（采购单号 / 入库单号 / …），不是本服务的业务主键。
 document_ref varchar(100) NOT NULL,
 -- 行号：同一单据的第几行。同一行只冻结一次，重跑生成不会二次计数。
 line_no int NOT NULL CHECK(line_no>0),
 -- BASE=单据直接用基础单位（比例恒为 1）；其余对应换算表的角色。
 role text NOT NULL CHECK(role IN ('BASE','PURCHASE','SALE','PACKAGING')),
 unit_code varchar(32) NOT NULL, base_unit varchar(32) NOT NULL,
 -- 单据上的原始数量与**当时**的比例、换算结果。三个数一起冻结：只有结果没有比例，
 -- 事后无法解释这个数怎么算出来的。
 quantity numeric(19,6) NOT NULL CHECK(quantity>0),
 quantity_per_base numeric(19,6) NOT NULL CHECK(quantity_per_base>0),
 base_quantity numeric(19,6) NOT NULL CHECK(base_quantity>0),
 -- 来源换算版本（role='BASE' 时为空：基础单位比例恒为 1，不来自任何换算行）。
 source_conversion_id uuid,
 -- 冻结那一刻该版本的生效区间，一并留存，便于日后核对"当时用的是哪一版"。
 effective_from date NOT NULL, effective_to date,
 -- 业务发生时间与系统写入时间分列。
 occurred_at timestamptz NOT NULL, actor_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 -- 同一单据的同一行只能冻结一次：重跑生成命中已有行时返回原快照，不覆盖也不重复计数。
 UNIQUE(tenant_id,document_type,document_ref,line_no),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,source_conversion_id) REFERENCES sku_unit_conversion(tenant_id,id),
 CHECK(btrim(unit_code)<>''), CHECK(btrim(document_ref)<>''),
 -- 基础单位行不引用换算版本；其余必须引用，否则比例无从追溯。
 CHECK((role='BASE' AND source_conversion_id IS NULL) OR (role<>'BASE' AND source_conversion_id IS NOT NULL))
);

-- 快照不可变。这是"单据冻结"的技术兑现点：比例改了、换算行删了，已发生单据仍然可核对。
-- 放在 DB 层而不是只靠应用层不提供改接口——一旦有人直连库改快照，对账就失去依据。
CREATE FUNCTION refuse_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '单据换算快照不可修改或删除：已发生单据必须保留当时的换算比例' USING ERRCODE='42501';
END $$;
CREATE TRIGGER freeze_unit_conversion_snapshot BEFORE UPDATE OR DELETE ON sku_unit_conversion_snapshot
 FOR EACH ROW EXECUTE FUNCTION refuse_snapshot_mutation();

-- 查询路径。
-- 单位字典：新建换算时按量纲/按场景筛候选；列表页按编码前缀找。
CREATE INDEX unit_of_measure_dimension ON unit_of_measure(tenant_id,dimension,code);
CREATE INDEX unit_of_measure_enabled ON unit_of_measure(tenant_id,enabled,code);
-- 「某个 SKU 的某单位在某天用哪一版」是本模块最热的读路径（换算计算 + 快照冻结都走它）。
-- V6 已有 UNIQUE(tenant_id,sku_id,unit_code,role,effective_from)，这条补的是按生效日期的区间检索。
CREATE INDEX sku_unit_conversion_effective
 ON sku_unit_conversion(tenant_id,sku_id,unit_code,role,effective_from DESC,effective_to DESC NULLS LAST);
-- 开放区间（effective_to IS NULL，即"当前仍生效"）的换算行是换算计算的默认命中对象。
CREATE UNIQUE INDEX sku_unit_conversion_open
 ON sku_unit_conversion(tenant_id,sku_id,unit_code,role) WHERE effective_to IS NULL;
-- 快照：按单据回读（06/07/08 拿着单据号来查当时的比例），与按 SKU 回读该 SKU 参与过的单据。
CREATE INDEX sku_unit_conversion_snapshot_doc
 ON sku_unit_conversion_snapshot(tenant_id,document_type,document_ref,line_no);
CREATE INDEX sku_unit_conversion_snapshot_sku
 ON sku_unit_conversion_snapshot(tenant_id,sku_id,created_at DESC,id DESC);

DO $$ DECLARE tbl text; BEGIN
 -- unit_of_measure 是租户级字典（与 sku_code_rule 同级），只有租户隔离，没有 SKU 可见性策略。
 -- 字典本身不是 SKU 档案的一部分；可见性由 catalog.sku.read 权限在端点层把关。
 FOREACH tbl IN ARRAY ARRAY['unit_of_measure'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 ALTER TABLE sku_unit_conversion_snapshot ENABLE ROW LEVEL SECURITY;
 ALTER TABLE sku_unit_conversion_snapshot FORCE ROW LEVEL SECURITY;
 CREATE POLICY isolation ON sku_unit_conversion_snapshot
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
 -- 快照的可见性跟随其 SKU：看不到 SKU 就看不到它的换算快照，
 -- 否则"SKU 不可见但能读出它的换算比例"会成为绕过页面权限的读法。
 CREATE POLICY data_scope ON sku_unit_conversion_snapshot AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_unit_conversion_snapshot.tenant_id AND s.id=sku_unit_conversion_snapshot.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_unit_conversion_snapshot.tenant_id AND s.id=sku_unit_conversion_snapshot.sku_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON unit_of_measure TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON unit_of_measure TO starcon_app;
-- 快照只允许插入与读取：GRANT 里不发 UPDATE/DELETE，与上面的不可变触发器是同一条纪律的两端。
GRANT SELECT,INSERT ON sku_unit_conversion_snapshot TO catalog_app;
GRANT SELECT,INSERT ON sku_unit_conversion_snapshot TO starcon_app;
