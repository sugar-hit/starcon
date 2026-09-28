CREATE TABLE spu (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, code varchar(64) NOT NULL, name varchar(200) NOT NULL,
 description text NOT NULL DEFAULT '', brand varchar(100) NOT NULL DEFAULT '', category varchar(100) NOT NULL DEFAULT '',
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code), CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
CREATE TABLE attribute_definition (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL, code varchar(64) NOT NULL, name varchar(100) NOT NULL,
 data_type text NOT NULL CHECK(data_type IN ('TEXT','DECIMAL','BOOLEAN','DATE','OPTION')),
 value_mode text NOT NULL CHECK(value_mode IN ('COMMON_LOCKED','COMMON_DEFAULT','SKU_ONLY')),
 multiple boolean NOT NULL DEFAULT false, required boolean NOT NULL DEFAULT false, variant_axis boolean NOT NULL DEFAULT false,
 unit varchar(32) NOT NULL DEFAULT '', group_name varchar(100) NOT NULL DEFAULT '基本参数', position int NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,spu_id,id), UNIQUE(tenant_id,spu_id,code),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 CHECK(NOT variant_axis OR (data_type='OPTION' AND value_mode='SKU_ONLY' AND NOT multiple AND required)),
 CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
CREATE TABLE attribute_option (
 tenant_id uuid NOT NULL, attribute_id uuid NOT NULL, value varchar(100) NOT NULL,
 PRIMARY KEY(tenant_id,attribute_id,value), FOREIGN KEY(tenant_id,attribute_id) REFERENCES attribute_definition(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE sku (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL, code varchar(64) NOT NULL, name varchar(200) NOT NULL,
 barcode varchar(128) NOT NULL DEFAULT '', base_unit varchar(32) NOT NULL DEFAULT '件', status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED')),
 variant_signature text NOT NULL, version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,spu_id,id), UNIQUE(tenant_id,code), UNIQUE(tenant_id,spu_id,variant_signature),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE attribute_value (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL, attribute_id uuid NOT NULL, sku_id uuid,
 state text NOT NULL CHECK(state IN ('SET','CLEAR')),
 UNIQUE(tenant_id,id), UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id),
 FOREIGN KEY(tenant_id,spu_id,attribute_id) REFERENCES attribute_definition(tenant_id,spu_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,spu_id,sku_id) REFERENCES sku(tenant_id,spu_id,id) ON DELETE CASCADE
);
CREATE TABLE attribute_value_item (
 tenant_id uuid NOT NULL, value_id uuid NOT NULL, attribute_id uuid NOT NULL, ordinal int NOT NULL CHECK(ordinal>=0),
 text_value text, decimal_value numeric(30,10), boolean_value boolean, date_value date, option_value varchar(100),
 PRIMARY KEY(tenant_id,value_id,ordinal),
 FOREIGN KEY(tenant_id,value_id) REFERENCES attribute_value(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,attribute_id,option_value) REFERENCES attribute_option(tenant_id,attribute_id,value),
 CHECK(num_nonnulls(text_value,decimal_value,boolean_value,date_value,option_value)=1)
);
-- Validate typed values even when written outside the HTTP controller.
CREATE FUNCTION validate_attribute_item() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a attribute_definition; v attribute_value;
BEGIN
 SELECT * INTO STRICT v FROM attribute_value WHERE tenant_id=NEW.tenant_id AND id=NEW.value_id;
 SELECT * INTO STRICT a FROM attribute_definition WHERE tenant_id=NEW.tenant_id AND id=v.attribute_id;
 IF NEW.attribute_id<>v.attribute_id OR v.state<>'SET' OR
    (NOT a.multiple AND NEW.ordinal<>0) OR
    NOT (CASE a.data_type WHEN 'TEXT' THEN NEW.text_value IS NOT NULL WHEN 'DECIMAL' THEN NEW.decimal_value IS NOT NULL
    WHEN 'BOOLEAN' THEN NEW.boolean_value IS NOT NULL WHEN 'DATE' THEN NEW.date_value IS NOT NULL WHEN 'OPTION' THEN NEW.option_value IS NOT NULL END)
 THEN RAISE EXCEPTION 'Invalid typed attribute value' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_item BEFORE INSERT OR UPDATE ON attribute_value_item FOR EACH ROW EXECUTE FUNCTION validate_attribute_item();
CREATE FUNCTION validate_value_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a attribute_definition;
BEGIN
 SELECT * INTO STRICT a FROM attribute_definition WHERE tenant_id=NEW.tenant_id AND id=NEW.attribute_id;
 IF (NEW.sku_id IS NULL AND (a.value_mode='SKU_ONLY' OR NEW.state='CLEAR')) OR
    (NEW.sku_id IS NOT NULL AND a.value_mode='COMMON_LOCKED') OR (NEW.state='CLEAR' AND a.required)
 THEN RAISE EXCEPTION 'Invalid attribute owner or clear' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_owner BEFORE INSERT OR UPDATE ON attribute_value FOR EACH ROW EXECUTE FUNCTION validate_value_owner();
CREATE TABLE product_research (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid, title varchar(200) NOT NULL,
 notes text NOT NULL DEFAULT '', reference text NOT NULL DEFAULT '', status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEW','APPROVED','ARCHIVED')),
 locale varchar(35) NOT NULL DEFAULT '', target_country varchar(2) NOT NULL DEFAULT '', expires_on date,
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id)
);
CREATE TABLE product_compliance (LIKE product_research INCLUDING ALL);
ALTER TABLE product_compliance ADD FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id);
CREATE TABLE product_asset (LIKE product_research INCLUDING ALL);
ALTER TABLE product_asset ADD FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id);
CREATE TABLE audit_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, actor_id uuid NOT NULL, resource_id uuid NOT NULL,
 action text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL,
 event_type text NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz
);
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['spu','sku','attribute_definition','attribute_option','attribute_value','attribute_value_item','product_research','product_compliance','product_asset','audit_event','outbox_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
CREATE INDEX spu_list ON spu(tenant_id,updated_at DESC);
CREATE INDEX sku_spu ON sku(tenant_id,spu_id);
CREATE INDEX attribute_spu ON attribute_definition(tenant_id,spu_id);
GRANT USAGE ON SCHEMA public TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO starcon_app;
REVOKE UPDATE,DELETE ON audit_event FROM starcon_app;
REVOKE ALL ON flyway_schema_history FROM starcon_app;
