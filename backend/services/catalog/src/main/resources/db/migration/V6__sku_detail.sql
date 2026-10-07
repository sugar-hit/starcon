-- 3.1.2.2 SKU 详情：详情页要聚合的 catalog 自有子资源 + 查询路径索引。
--
-- 边界（照 3.1.2.1 的口径继续）：
--   * 包装尺寸重量与单位换算是 SKU 档案自身的数据，蓝图 03 的归属表明确写着 catalog 拥有
--     unit_of_measure，所以这两张表落在 catalog。3.7.2.1 物理与安全属性在 sku_package 之上
--     补是否带电/液体/危险品/磁性，3.1.2.4 在 sku_unit_conversion 之上补单位字典（3.3.3）
--     与量纲校验——两者都不属于本模块的范围，这里只把「有效换算版本」这一层做到可判定。
--   * 库存分布（07 库存）、成本与采购价（06 采购）、售价（05 定价）、平台 listing（04 刊登）
--     **一律不落库**：它们各自是别的服务的事实源，catalog 一旦把这些数字存成本地列就会变成
--     第二个事实来源，与蓝图「库存数以 07 为准，售价以 05 为准」直接冲突。读取走
--     SkuDetailProjectionPort / SkuProjectionPort，未接入时显式 available=false，不填 0。
--   * product_asset 是 `LIKE product_research INCLUDING ALL` 的同构复制：没有文件键、没有
--     sku_id、没有任何图片/视频字段。本模块**不修这张表**（修表是 3.4.1.1 的专门任务），
--     媒体区只读它现有的可用列，并在响应里把缺陷如实标出来。

-- 包装尺寸重量：SKU 的物理档案，决定 10 物流的体积重与 08 拣货的装箱体积核算。
-- 一行代表「已维护」，全部字段可空表示这一维度还没采集到——与「采集到是 0」区分开。
CREATE TABLE sku_package (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 length_mm numeric(19,6), width_mm numeric(19,6), height_mm numeric(19,6),
 net_weight_g numeric(19,6), gross_weight_g numeric(19,6),
 packaging_type varchar(64) NOT NULL DEFAULT '',
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 CHECK((length_mm IS NULL OR length_mm>=0) AND (width_mm IS NULL OR width_mm>=0) AND (height_mm IS NULL OR height_mm>=0)),
 CHECK((net_weight_g IS NULL OR net_weight_g>=0) AND (gross_weight_g IS NULL OR gross_weight_g>=0)),
 -- 毛重不可能小于净重：这条在 DB 层挡住，避免详情页算出负的包装附加费。
 CHECK(net_weight_g IS NULL OR gross_weight_g IS NULL OR gross_weight_g>=net_weight_g)
);

-- 单位换算：quantity_per_base 是「1 个该单位等于多少个基础单位」（1 箱 = 12 件 → 12）。
-- 换算比例必须版本化并有生效区间（蓝图 3.1.2.4 的硬约束），否则详情页无法判定"当前有效版本"，
-- 单据也就无从冻结当时的比例。
CREATE TABLE sku_unit_conversion (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 unit_code varchar(32) NOT NULL,
 role text NOT NULL CHECK(role IN ('PURCHASE','SALE','PACKAGING')),
 quantity_per_base numeric(19,6) NOT NULL CHECK(quantity_per_base>0),
 effective_from date NOT NULL, effective_to date,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id,unit_code,role,effective_from),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 CHECK(btrim(unit_code)<>''),
 CHECK(effective_to IS NULL OR effective_to>=effective_from)
);
-- 同一 SKU 的同一单位同一角色，同一时刻只能有一个生效版本。
-- 不加这条就会出现两条"当前有效"，详情页只能随便挑一条——而蓝图对多条有效报价的口径
-- 就是"必须报错让用户选"，这里对换算版本用同一条纪律。
CREATE FUNCTION validate_unit_conversion_window() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM sku_unit_conversion c
   WHERE c.tenant_id=NEW.tenant_id AND c.sku_id=NEW.sku_id AND c.id<>NEW.id
     AND c.unit_code=NEW.unit_code AND c.role=NEW.role
     AND daterange(c.effective_from,coalesce(c.effective_to,'infinity'::date),'[]')
      && daterange(NEW.effective_from,coalesce(NEW.effective_to,'infinity'::date),'[]')) THEN
  RAISE EXCEPTION '单位换算生效区间与已有版本重叠' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_unit_conversion_window BEFORE INSERT OR UPDATE ON sku_unit_conversion
 FOR EACH ROW EXECUTE FUNCTION validate_unit_conversion_window();

-- 详情页的查询路径。
-- 有效值解析按 SKU 取全部属性赋值：V1 的唯一约束是 (tenant_id,attribute_id,sku_id)，
-- 那是"按属性找 SKU"，详情页需要的是"按 SKU 找全部属性"，方向相反，必须另建一条。
CREATE INDEX attribute_value_sku ON attribute_value(tenant_id,sku_id,attribute_id) WHERE sku_id IS NOT NULL;
-- 媒体区按 SPU 取资料行；V3 只给这三张表建了 owner/department 索引。
CREATE INDEX product_asset_spu ON product_asset(tenant_id,spu_id,updated_at DESC,id DESC);
-- 详情页回溯这个 SKU 被哪些批量操作动过。
CREATE INDEX sku_batch_operation_item_sku ON sku_batch_operation_item(tenant_id,sku_id);

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sku_package','sku_unit_conversion'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- 可见性跟随 SKU：看不到 SKU 就看不到它的包装与换算，和 3.1.2.1 的状态历史同一条纪律。
 CREATE POLICY data_scope ON sku_package AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_package.tenant_id AND s.id=sku_package.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_package.tenant_id AND s.id=sku_package.sku_id));
 CREATE POLICY data_scope ON sku_unit_conversion AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_unit_conversion.tenant_id AND s.id=sku_unit_conversion.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_unit_conversion.tenant_id AND s.id=sku_unit_conversion.sku_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_package, sku_unit_conversion TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_package, sku_unit_conversion TO starcon_app;