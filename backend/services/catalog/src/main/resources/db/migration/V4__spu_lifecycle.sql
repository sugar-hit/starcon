-- 3.1.1.3 SPU lifecycle: the SPU status becomes a five-state machine instead of free text.
-- Old four-value domain maps forward without losing rows: DRAFT stays, ACTIVE becomes ON_SALE,
-- INACTIVE becomes SUSPENDED (delisted but recoverable), ARCHIVED becomes DISCONTINUED (terminal).
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint
          WHERE conrelid='spu'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%ACTIVE%'
 LOOP EXECUTE format('ALTER TABLE spu DROP CONSTRAINT %I', c.conname); END LOOP;
END $$;
UPDATE spu SET status=CASE status WHEN 'ACTIVE' THEN 'ON_SALE' WHEN 'INACTIVE' THEN 'SUSPENDED'
                                   WHEN 'ARCHIVED' THEN 'DISCONTINUED' ELSE 'DRAFT' END;
ALTER TABLE spu ADD CONSTRAINT spu_status_check
 CHECK(status IN ('DRAFT','PENDING_LISTING','ON_SALE','SUSPENDED','DISCONTINUED'));

-- Batch rules. Quantities are NUMERIC, never floating point.
CREATE TABLE spu_lifecycle_rule (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, code varchar(64) NOT NULL, name varchar(100) NOT NULL,
 description text NOT NULL DEFAULT '', enabled boolean NOT NULL DEFAULT true,
 target_status text NOT NULL CHECK(target_status IN ('PENDING_LISTING','ON_SALE','SUSPENDED','DISCONTINUED')),
 reason_template text NOT NULL DEFAULT '', version bigint NOT NULL DEFAULT 0,
 owner_id uuid, department_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code), CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
CREATE TABLE spu_lifecycle_rule_condition (
 tenant_id uuid NOT NULL, rule_id uuid NOT NULL, ordinal int NOT NULL CHECK(ordinal>=0),
 metric text NOT NULL CHECK(metric IN ('SKU_COUNT','ACTIVE_SKU_COUNT','READY_SKU_COUNT','INVENTORY_ON_HAND','SALES_QTY_30D','LISTING_PUBLISHED_COUNT')),
 operator text NOT NULL CHECK(operator IN ('GT','GTE','EQ','NEQ','LTE','LT')),
 threshold numeric(19,6) NOT NULL,
 PRIMARY KEY(tenant_id,rule_id,ordinal),
 FOREIGN KEY(tenant_id,rule_id) REFERENCES spu_lifecycle_rule(tenant_id,id) ON DELETE CASCADE
);
-- Every recorded transition. occurred_at is business time chosen by the operator, created_at is system time.
CREATE TABLE spu_lifecycle_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL,
 from_status text NOT NULL CHECK(from_status IN ('DRAFT','PENDING_LISTING','ON_SALE','SUSPENDED','DISCONTINUED')),
 to_status text NOT NULL CHECK(to_status IN ('DRAFT','PENDING_LISTING','ON_SALE','SUSPENDED','DISCONTINUED')),
 reason text NOT NULL DEFAULT '', source text NOT NULL CHECK(source IN ('MANUAL','RULE')), rule_id uuid,
 actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,spu_id,id),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,rule_id) REFERENCES spu_lifecycle_rule(tenant_id,id) ON DELETE RESTRICT,
 CHECK(from_status<>to_status)
);
CREATE INDEX spu_lifecycle_event_spu ON spu_lifecycle_event(tenant_id,spu_id,occurred_at DESC,id DESC);
CREATE INDEX spu_lifecycle_rule_enabled ON spu_lifecycle_rule(tenant_id,enabled);

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['spu_lifecycle_rule','spu_lifecycle_rule_condition','spu_lifecycle_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- Rules reuse the SPU ownership snapshot and its page scope; there is no unscoped rule list.
 CREATE TRIGGER record_owner BEFORE INSERT OR UPDATE ON spu_lifecycle_rule
   FOR EACH ROW EXECUTE FUNCTION stamp_record_owner();
 CREATE POLICY data_scope ON spu_lifecycle_rule AS RESTRICTIVE
  USING (catalog_scope(catalog_operation(),owner_id,department_id))
  WITH CHECK (catalog_scope(catalog_operation(),owner_id,department_id));
 CREATE POLICY data_scope ON spu_lifecycle_rule_condition AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu_lifecycle_rule r WHERE r.tenant_id=spu_lifecycle_rule_condition.tenant_id AND r.id=spu_lifecycle_rule_condition.rule_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu_lifecycle_rule r WHERE r.tenant_id=spu_lifecycle_rule_condition.tenant_id AND r.id=spu_lifecycle_rule_condition.rule_id));
 -- History is readable exactly when its SPU is.
 CREATE POLICY data_scope ON spu_lifecycle_event AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_lifecycle_event.tenant_id AND p.id=spu_lifecycle_event.spu_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_lifecycle_event.tenant_id AND p.id=spu_lifecycle_event.spu_id));
END $$;
-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON spu_lifecycle_rule, spu_lifecycle_rule_condition, spu_lifecycle_event TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON spu_lifecycle_rule, spu_lifecycle_rule_condition, spu_lifecycle_event TO starcon_app;