-- 3.1.2.1 SKU 列表：把「全租户 SKU 明细 + 多维筛选 + 批量操作 + 导出」需要的事实落到 catalog 库里。
--
-- 边界：这里只加 **catalog 自有** 的字段与索引。
-- 蓝图要求的库存（在仓/在途/可售）归 07 库存（阶段 D3）、售价归 05 定价（阶段 D5）、
-- 成本与采购价归 06 采购、在售平台归 04 刊登。这四个服务本阶段都还没落地，
-- 因此它们 **不落库**：catalog 一旦把这些数字存成本地列，就会变成第二个事实来源，
-- 与蓝图「库存数以 07 为准，售价以 05 为准」直接冲突。读取统一走 SkuProjectionPort，
-- 未接入时显式表达 available=false，而不是填 0 让界面看起来"都有库存"。
CREATE TABLE sku_status_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 from_status text NOT NULL CHECK(from_status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
 to_status text NOT NULL CHECK(to_status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
 reason text NOT NULL DEFAULT '',
 source text NOT NULL CHECK(source IN ('MANUAL','BATCH')),
 batch_id uuid,
 actor_id uuid NOT NULL,
 -- occurred_at 是操作人选择的业务时间，created_at 是系统写入时间，两者分列存储。
 occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id,id),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 CHECK(from_status<>to_status)
);

-- 批量操作任务本身也是交付物（蓝图：输出 = 分页结果 + 批量操作任务）。
-- 预览与执行共用这张表：预览写 status='PREVIEW'，执行写 status='APPLIED'，
-- 因此"先看影响范围再执行"的两步留痕在同一条审计线上。
CREATE TABLE sku_batch_operation (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('STOP_SELLING','SET_STATUS')),
 target_status text NOT NULL CHECK(target_status IN ('ACTIVE','INACTIVE','ARCHIVED')),
 reason text NOT NULL,
 status text NOT NULL CHECK(status IN ('PREVIEW','APPLIED')),
 requested_count int NOT NULL CHECK(requested_count>=0),
 matched_count int NOT NULL CHECK(matched_count>=0),
 applied_count int NOT NULL CHECK(applied_count>=0),
 skipped_count int NOT NULL CHECK(skipped_count>=0),
 actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id)
);
CREATE TABLE sku_batch_operation_item (
 tenant_id uuid NOT NULL, batch_id uuid NOT NULL, sku_id uuid NOT NULL,
 outcome text NOT NULL CHECK(outcome IN ('APPLIED','SKIPPED')),
 reason text NOT NULL DEFAULT '',
 PRIMARY KEY(tenant_id,batch_id,sku_id),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES sku_batch_operation(tenant_id,id) ON DELETE CASCADE
);

-- 筛选与稳定排序所需的索引。
-- 默认排序是 (updated_at DESC, id DESC)，这条复合索引同时服务默认页和翻页；
-- 单独的 barcode 索引服务精确/前缀查条码，variant_signature 服务规格关键字。
CREATE INDEX sku_list_updated ON sku(tenant_id,updated_at DESC,id DESC);
CREATE INDEX sku_list_status ON sku(tenant_id,status,updated_at DESC,id DESC);
CREATE INDEX sku_list_code ON sku(tenant_id,code);
CREATE INDEX sku_list_barcode ON sku(tenant_id,barcode);
CREATE INDEX sku_list_signature ON sku(tenant_id,variant_signature);
-- SPU 侧筛选（分类）走 spu，按分类过滤 SKU 时从这里进。
CREATE INDEX spu_list_category ON spu(tenant_id,category);
CREATE INDEX sku_status_event_sku ON sku_status_event(tenant_id,sku_id,occurred_at DESC,id DESC);
CREATE INDEX sku_batch_operation_created ON sku_batch_operation(tenant_id,created_at DESC,id DESC);

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sku_status_event','sku_batch_operation','sku_batch_operation_item'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- 状态历史与批量明细的可见性跟随其 SKU：SKU 不可见则它们也不可见，
 -- 不能出现"看不到 SKU 却看得到它的状态变更"这种绕过页面权限的读法。
 CREATE POLICY data_scope ON sku_status_event AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_status_event.tenant_id AND s.id=sku_status_event.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_status_event.tenant_id AND s.id=sku_status_event.sku_id));
 CREATE POLICY data_scope ON sku_batch_operation_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_batch_operation_item.tenant_id AND s.id=sku_batch_operation_item.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_batch_operation_item.tenant_id AND s.id=sku_batch_operation_item.sku_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_status_event, sku_batch_operation, sku_batch_operation_item TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_status_event, sku_batch_operation, sku_batch_operation_item TO starcon_app;
