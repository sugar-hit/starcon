-- Ownership is an immutable creation snapshot. Historical rows without an audit actor remain ALL-only.
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['spu','product_research','product_compliance','product_asset'] LOOP
  EXECUTE format('ALTER TABLE %I ADD COLUMN owner_id uuid, ADD COLUMN department_id uuid',tbl);
  EXECUTE format('UPDATE %I t SET owner_id=(SELECT actor_id FROM audit_event e WHERE e.tenant_id=t.tenant_id AND e.resource_id=t.id ORDER BY occurred_at,id LIMIT 1)',tbl);
  EXECUTE format('CREATE INDEX %I ON %I(tenant_id,owner_id)',tbl||'_owner',tbl);
  EXECUTE format('CREATE INDEX %I ON %I(tenant_id,department_id)',tbl||'_department',tbl);
 END LOOP;
END $$;
CREATE FUNCTION stamp_record_owner() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
  NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
  IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
 ELSE
  IF NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id
  THEN RAISE EXCEPTION 'Record ownership is immutable' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
-- Each matching assignment must supply BOTH the operation and its read permission.
CREATE FUNCTION catalog_scope(needed text,owner_id uuid,department_id uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
 SELECT coalesce(EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(nullif(current_setting('app.grants',true),''),'[]')::jsonb) g
 WHERE (g->'permissions') ? needed
 AND (g->'permissions') ? (regexp_replace(needed,'\.[^.]+$','')||'.read')
 AND (g->>'scopeMode'='ALL'
 OR (g->>'scopeMode'='SELF' AND owner_id=nullif(current_setting('app.actor',true),'')::uuid)
 OR (g->>'scopeMode'='DEPARTMENT' AND department_id=nullif(current_setting('app.department',true),'')::uuid))),false)
$$;
CREATE FUNCTION catalog_operation() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.permission',true),'') $$;
CREATE POLICY data_scope ON spu AS RESTRICTIVE
 USING (catalog_scope(CASE WHEN catalog_operation() LIKE 'catalog.research.%' OR catalog_operation() LIKE 'catalog.compliance.%' OR catalog_operation() LIKE 'catalog.assets.%' THEN 'catalog.spu.read' ELSE catalog_operation() END,owner_id,department_id))
 WITH CHECK (catalog_scope(catalog_operation(),owner_id,department_id));
DO $$ DECLARE tbl text; page text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['spu','product_research','product_compliance','product_asset'] LOOP
  EXECUTE format('CREATE TRIGGER record_owner BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION stamp_record_owner()',tbl);
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['product_research','product_compliance','product_asset'] LOOP
  page:=CASE tbl WHEN 'product_research' THEN 'research' WHEN 'product_compliance' THEN 'compliance' ELSE 'assets' END;
  EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (catalog_operation() LIKE %L AND catalog_scope(catalog_operation(),owner_id,department_id)) WITH CHECK (catalog_operation() LIKE %L AND catalog_scope(catalog_operation(),owner_id,department_id))',tbl,'catalog.'||page||'.%','catalog.'||page||'.%');
 END LOOP;
 FOREACH tbl IN ARRAY ARRAY['sku','attribute_definition','attribute_value'] LOOP
  EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=%I.tenant_id AND p.id=%I.spu_id)) WITH CHECK (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=%I.tenant_id AND p.id=%I.spu_id))',tbl,tbl,tbl,tbl,tbl);
 END LOOP;
END $$;
CREATE POLICY data_scope ON attribute_option AS RESTRICTIVE
 USING (EXISTS(SELECT 1 FROM attribute_definition a WHERE a.tenant_id=attribute_option.tenant_id AND a.id=attribute_option.attribute_id))
 WITH CHECK (EXISTS(SELECT 1 FROM attribute_definition a WHERE a.tenant_id=attribute_option.tenant_id AND a.id=attribute_option.attribute_id));
CREATE POLICY data_scope ON attribute_value_item AS RESTRICTIVE
 USING (EXISTS(SELECT 1 FROM attribute_value v WHERE v.tenant_id=attribute_value_item.tenant_id AND v.id=attribute_value_item.value_id))
 WITH CHECK (EXISTS(SELECT 1 FROM attribute_value v WHERE v.tenant_id=attribute_value_item.tenant_id AND v.id=attribute_value_item.value_id));
-- Listing a product never leaks SKU counts beyond the SKU read scope.
CREATE POLICY sku_read_scope ON sku AS RESTRICTIVE USING (
 catalog_operation()<>'catalog.spu.read' OR EXISTS(SELECT 1 FROM spu p WHERE p.id=sku.spu_id AND p.tenant_id=sku.tenant_id AND catalog_scope('catalog.sku.read',p.owner_id,p.department_id))
);
