-- 17.1.2.1 法人档案 + 17.1.2.2 本位币。
--
-- 法人是货权、收付款与记账主体，**不等于租户**：一个租户可含多个法人，
-- 这是"店铺收入记到哪个主体"的基础。channel.shop.legal_entity_id 指向这里，
-- 但不建跨库外键——存在性与租户归属由本服务授权 API 确认。
--
-- 本位币归法人，租户默认币种只是展示默认，两者不能混（实体目录 §5）。

CREATE TABLE legal_entity (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),
  code                varchar(64) NOT NULL,
  legal_name          varchar(200) NOT NULL,
  -- char 与 text 的默认值比较会报 "text = integer"（bpchar 与 int 的隐式转换歧义），
-- 因此用 varchar 存国家/币种代码：长度已在 CHECK 里约束，不需要 bpchar 的补空格语义
registration_country varchar(2) NOT NULL DEFAULT 'CN',
  registration_no     varchar(64) NOT NULL DEFAULT '',
  tax_no              varchar(64) NOT NULL DEFAULT '',
  -- 本位币：本法人记账所用的币种，决定 12 财务总账的记账基准
  base_currency       varchar(3) NOT NULL,
  address             text NOT NULL DEFAULT '',
  -- 账簿规则：金额舍入与数量精度。跨境常见一个主体多账簿（默认业务账 vs 法定账），
  -- 本期先落法人级默认，17.6 再展开多账簿。
  amount_scale        int NOT NULL DEFAULT 2 CHECK(amount_scale BETWEEN 0 AND 6),
  quantity_scale      int NOT NULL DEFAULT 3 CHECK(quantity_scale BETWEEN 0 AND 6),
  rounding            text NOT NULL DEFAULT 'HALF_UP'
                      CHECK(rounding IN ('HALF_UP','HALF_EVEN','DOWN','UP')),
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  -- btrim() 返回 text，直接与整数比较会报 "operator does not exist: text = integer"
  CHECK(btrim(code)<>'' AND btrim(legal_name)<>'' AND length(btrim(base_currency))=3)
);

-- 法人状态变更会影响记账与货权，因此删除前必须确认没有店铺或单据仍引用它。
-- 停用（status=SUSPENDED）是可逆且被推荐的处置方式。
CREATE FUNCTION guard_legal_entity_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE refs bigint;
BEGIN
  SELECT count(*) INTO refs FROM legal_entity_reference WHERE tenant_id=OLD.tenant_id AND legal_entity_id=OLD.id;
  IF refs > 0 THEN
    RAISE EXCEPTION '该法人仍被 % 项业务引用，请先停用而不是删除', refs USING ERRCODE='23503';
  END IF;
  RETURN OLD;
END $$;

-- 引用登记表：channel 服务在本库之外，写入方通过授权 API 登记引用事实。
-- 这样"能不能删法人"的判定就有真实依据，而不是靠猜测。
CREATE TABLE legal_entity_reference (
  tenant_id     uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  source        text NOT NULL,
  reference_id  uuid NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id, legal_entity_id, source, reference_id)
);
CREATE INDEX legal_entity_reference_entity ON legal_entity_reference(tenant_id, legal_entity_id);

CREATE TRIGGER legal_entity_delete_guard
  BEFORE DELETE ON legal_entity FOR EACH ROW EXECUTE FUNCTION guard_legal_entity_delete();

DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['legal_entity','legal_entity_reference'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
CREATE INDEX legal_entity_list ON legal_entity(tenant_id, status, code);

-- 17.1 租户与法人：法人档案按页面 CRUD 授权，组织变更沿用 V3 的"仅 OWNER 可变更"纪律。
INSERT INTO page_resource(code,name) VALUES ('identity.legal_entity','法人档案')
ON CONFLICT (code) DO NOTHING;
-- unnest(ARRAY[...]) 的元素类型由字面量推断，这里显式给 text[]，
-- 否则 PG 会把单元素数组推成 text 再与 varchar 比较，报 "text = integer"
INSERT INTO permission SELECT 'identity.legal_entity.'||action,'identity.legal_entity',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.legal_entity.%' ON CONFLICT DO NOTHING;

GRANT SELECT,INSERT,UPDATE,DELETE ON legal_entity TO identity_app;
GRANT SELECT,INSERT,DELETE ON legal_entity_reference TO identity_app;