-- 3.1.2.3 编码与条码：内部 SKU 编码规则 + 外部条码（sku_identifier）。
--
-- 边界（照 3.1.2.1 / 3.1.2.2 的口径继续）：
--   * 内部编码落在 `sku.code`（V1 已有 UNIQUE(tenant_id,code)），本模块只加**规则**与**分配留痕**，
--     不新建一张自己的编码表——否则 sku.code 与编码表会变成两个事实来源。
--   * `sku.barcode` 是 3.1.2.1 之前就有的单个字符串位（NOT NULL DEFAULT ''），它继续作为
--     「主条码」的兼容位；真正的多 scheme 条码在 sku_identifier 上。这里用触发器把主条码
--     同步过去，让详情页/列表页/下游 08 拣货扫码不必等全部调用方迁移完。
--   * **店铺的 seller_sku 不进本模块**。它不是内部编码，而是平台侧属性，映射关系由 04 刊登维护；
--     把它塞进 sku_identifier 会让"内部唯一条码"这个约束被平台编号污染。
--
-- 编码规则：prefix + 序号(补零) + suffix，序号由 rule_seq 按租户+规则单调递增分配，
-- 保证同一规则内不重号。规则可禁用（停用后不再参与自动生成，但已分配的编码保留）。
CREATE TABLE sku_code_rule (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
 code varchar(64) NOT NULL, name varchar(100) NOT NULL, description text NOT NULL DEFAULT '',
 -- 适用范围：ALL=全部未编码 SKU；SPU=仅该商品族下的 SKU。
 scope text NOT NULL DEFAULT 'ALL' CHECK(scope IN ('ALL','SPU')),
 spu_id uuid,
 prefix varchar(32) NOT NULL DEFAULT '',
 suffix varchar(32) NOT NULL DEFAULT '',
 -- 序号的十进制宽度（补前导零）。1~12，与 sku.code 的 64 字符上限一起约束展开后的长度。
 seq_width int NOT NULL DEFAULT 6 CHECK(seq_width BETWEEN 1 AND 12),
 start_from bigint NOT NULL DEFAULT 1 CHECK(start_from>=1),
 -- 一条规则在一个商品族内只能有一条，避免同一 SPU 命中两条规则导致编号规则不可预期。
 enabled boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code),
 UNIQUE(tenant_id,scope,spu_id) DEFERRABLE INITIALLY IMMEDIATE,
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 CHECK(btrim(code)<>''),
 -- SPU 范围的规则必须绑定商品族；ALL 范围不许带 spu_id，否则「唯一」约束形同虚设。
 CHECK((scope='ALL' AND spu_id IS NULL) OR (scope='SPU' AND spu_id IS NOT NULL))
);

-- 序号分配器：每个租户的每条规则一行，next_value 由数据库独占分配。
-- 用独立表而不是把 next_value 放在规则行上，是为了让「分配号段」这一个动作能单独加锁，
-- 规则行的编辑（改名/停用）不必与批量生成互相阻塞。
CREATE TABLE sku_code_rule_seq (
 tenant_id uuid NOT NULL, rule_id uuid NOT NULL, next_value bigint NOT NULL CHECK(next_value>=1),
 PRIMARY KEY(tenant_id,rule_id),
 FOREIGN KEY(tenant_id,rule_id) REFERENCES sku_code_rule(tenant_id,id) ON DELETE CASCADE
);

-- 外部条码：一个 SKU 可以有多个 scheme 的条码（EAN-13 / UPC-A / GTIN-14 / 自定义），
-- 但同一 scheme 下 value 全租户唯一——这是蓝图点名的硬约束，也是 08 拣货扫码唯一确定
-- 一个 SKU 的前提（扫码只读得到那串数字，不知道 scheme）。
CREATE TABLE sku_identifier (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 scheme varchar(24) NOT NULL, value varchar(128) NOT NULL,
 -- 一个 scheme 至多一个主条码：UNIQUE(...,is_primary) where is_primary 保证唯一。
 is_primary boolean NOT NULL DEFAULT false,
 -- 来源：MANUAL 手工 / SCAN 扫码 / IMPORT 批量导入，用于回溯"这条条码哪来的"。
 source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','SCAN','IMPORT')),
 -- 业务录入时间与系统写入时间分列。
 occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,scheme,value),
 UNIQUE(tenant_id,sku_id,scheme,value),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 CHECK(btrim(scheme)<>''), CHECK(btrim(value)<>'')
);
-- 同一 SKU 的同一 scheme 至多一个主条码。
CREATE UNIQUE INDEX sku_identifier_primary ON sku_identifier(tenant_id,sku_id,scheme) WHERE is_primary;

-- 编码分配留痕：批量补齐/自动生成改写了哪个 SKU 的编码、原来是哪个、现在是哪个。
-- 与 sku_status_event（V5）同一条纪律——改了什么必须能回溯。
CREATE TABLE sku_code_assignment (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
 from_code varchar(64), to_code varchar(64) NOT NULL,
 -- MANUAL=手工输入；RULE=按规则生成；BACKFILL=批量补齐（重跑规则）。
 source text NOT NULL CHECK(source IN ('MANUAL','RULE','BACKFILL')),
 rule_id uuid,
 batch_id uuid,
 actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,rule_id) REFERENCES sku_code_rule(tenant_id,id),
 CHECK(from_code IS NULL OR from_code<>to_code)
);

-- 批量补齐的批次头：requested/matched/applied/skipped 四段计数与 sku_batch_operation（V5）同构。
-- action 覆盖三类批量：BACKFILL/GENERATE 改内部编码，IMPORT 导外部条码——
-- 三者共用同一套「批量结果 + 逐行拒绝清单」的形状，前端也只有一套渲染逻辑。
CREATE TABLE sku_code_batch (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('BACKFILL','GENERATE','IMPORT')),
 status text NOT NULL CHECK(status IN ('PREVIEW','APPLIED')),
 requested_count int NOT NULL CHECK(requested_count>=0),
 matched_count int NOT NULL CHECK(matched_count>=0),
 applied_count int NOT NULL CHECK(applied_count>=0),
 skipped_count int NOT NULL CHECK(skipped_count>=0),
 actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id)
);
-- 冲突拒绝清单：被拒绝的行与原因。这正是蓝图要求的「冲突拒绝清单」交付物，
-- 逐行留痕而不是只回一个总数——运营要知道**哪一行**被拒、**为什么**，才能改完重传。
-- scheme/value 只在 IMPORT（条码导入）时有值；编码类批次留空。
CREATE TABLE sku_code_batch_item (
 tenant_id uuid NOT NULL, batch_id uuid NOT NULL, sku_id uuid,
 -- 行号：批量操作按提交顺序编号，前端据此把拒绝行对齐回用户上传的那一列。
 row_no int NOT NULL CHECK(row_no>0),
 outcome text NOT NULL CHECK(outcome IN ('APPLIED','SKIPPED','REJECTED')),
 reason text NOT NULL DEFAULT '',
 code_before varchar(64), code_after varchar(64),
 scheme varchar(24), value varchar(128),
 PRIMARY KEY(tenant_id,batch_id,row_no),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES sku_code_batch(tenant_id,id) ON DELETE CASCADE
);

-- 枚举取值与查询路径。
-- 规则按 SPU 查（scope='SPU' 时取该商品族的规则）与按启用状态查。
CREATE INDEX sku_code_rule_spu ON sku_code_rule(tenant_id,spu_id) WHERE spu_id IS NOT NULL;
CREATE INDEX sku_code_rule_enabled ON sku_code_rule(tenant_id,enabled,code);
-- 批量补齐扫「还没有编码的 SKU」，正好走 sku 的 spu 索引。
CREATE INDEX sku_code_assignment_sku ON sku_code_assignment(tenant_id,sku_id,occurred_at DESC,id DESC);
CREATE INDEX sku_code_batch_created ON sku_code_batch(tenant_id,created_at DESC,id DESC);
-- 拣货扫码只拿到一串数字：从 (scheme,value) 反查 SKU 走 UNIQUE(tenant_id,scheme,value) 即可，
-- 但「按 value 跨 scheme 找」（用户不填 scheme 的模糊扫码）需要这条。
CREATE INDEX sku_identifier_value ON sku_identifier(tenant_id,value);
-- sku.barcode 是主条码的兼容位，列表页按它精确查。
CREATE INDEX sku_barcode_unique ON sku_identifier(tenant_id,value) WHERE is_primary;

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sku_code_rule','sku_code_rule_seq','sku_identifier','sku_code_assignment','sku_code_batch','sku_code_batch_item'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- 条码、编码留痕的可见性跟随其 SKU：看不到 SKU 就看不到它的条码，
 -- 否则"SKU 不可见但条码可查"会成为绕过页面权限的读法（也正是 08 拣货越权取货条的入口）。
 CREATE POLICY data_scope ON sku_identifier AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_identifier.tenant_id AND s.id=sku_identifier.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_identifier.tenant_id AND s.id=sku_identifier.sku_id));
 CREATE POLICY data_scope ON sku_code_assignment AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_code_assignment.tenant_id AND s.id=sku_code_assignment.sku_id))
  WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_code_assignment.tenant_id AND s.id=sku_code_assignment.sku_id));
 -- 批量明细里既有指向 SKU 的行，也有 SKU 不可见/不存在的被拒行：前者跟随 SKU 可见性，
 -- 后者的 sku_id 为空仍然可读——运营需要看到"这一行被拒是因为 SKU 找不到"，否则拒绝清单不完整。
 CREATE POLICY data_scope ON sku_code_batch_item AS RESTRICTIVE
  USING (sku_id IS NULL OR EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_code_batch_item.tenant_id AND s.id=sku_code_batch_item.sku_id))
  WITH CHECK (sku_id IS NULL OR EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_code_batch_item.tenant_id AND s.id=sku_code_batch_item.sku_id));
END $$;

-- sku.barcode ← sku_identifier 的主条码。
-- 用触发器而不是让应用层双写：双写迟早会因为某条路径漏调而让两个位置不一致，
-- 而条码不一致在 08 拣货扫码时表现为"扫出来是另一个 SKU"，属于会真实打错货的故障。
CREATE FUNCTION sync_primary_barcode() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE primary_value text;
BEGIN
 -- 取出该 SKU 在**被改动的 scheme** 下的主条码；没有主条码则回落为空串
 -- （sku.barcode 是 NOT NULL DEFAULT ''，空串表示"没有主条码"，不是"条码是空"）。
 SELECT i.value INTO primary_value FROM sku_identifier i
  WHERE i.tenant_id=NEW.tenant_id AND i.sku_id=NEW.sku_id AND i.scheme=NEW.scheme AND i.is_primary;
 UPDATE sku SET barcode=coalesce(primary_value,''),version=version+1,updated_at=now()
  WHERE tenant_id=NEW.tenant_id AND id=NEW.sku_id;
 RETURN NEW;
END $$;
CREATE TRIGGER sync_primary_barcode_after_write
 AFTER INSERT OR UPDATE OF is_primary,sku_id,scheme ON sku_identifier
 FOR EACH ROW EXECUTE FUNCTION sync_primary_barcode();
-- 删除主条码时同步清空 sku.barcode：留着一个已经不存在的条码，下游扫码会扫出查无此物的货。
CREATE FUNCTION clear_primary_barcode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE sku SET barcode='',version=version+1,updated_at=now()
  WHERE tenant_id=OLD.tenant_id AND id=OLD.sku_id;
 RETURN OLD;
END $$;
CREATE TRIGGER clear_primary_barcode_after_delete
 AFTER DELETE ON sku_identifier
 FOR EACH ROW WHEN (OLD.is_primary)
 EXECUTE FUNCTION clear_primary_barcode();

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_code_rule, sku_code_rule_seq, sku_identifier, sku_code_assignment, sku_code_batch, sku_code_batch_item TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_code_rule, sku_code_rule_seq, sku_identifier, sku_code_assignment, sku_code_batch, sku_code_batch_item TO starcon_app;