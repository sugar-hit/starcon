-- 02 渠道与店铺：平台目录、站点、授权连接、凭据引用、店铺与启用站点。
-- 遵循实体目录 §87-99：platform/marketplace 是全局码表（不带 tenant_id），
-- 连接与店铺是租户业务事实（tenant_id NOT NULL + RLS 强制隔离）。

-- ---------------------------------------------------------------------------
-- platform / marketplace · 全局码表
-- ---------------------------------------------------------------------------
-- 发布时随代码更新，不带 tenant_id。蓝图中 2.1.1.1 明确要求：不可直接把全表
-- 暴露给普通请求，因此只有平台目录页（无需租户上下文）可以读，租户数据一律不回查此处。
CREATE TABLE platform (
  id             uuid PRIMARY KEY,
  code           varchar(32) NOT NULL UNIQUE,
  name           varchar(100) NOT NULL,
  integration    text NOT NULL CHECK(integration IN ('OFFICIAL_API','THIRD_PARTY_ISV','INTERMEDIARY')),
  support_status text NOT NULL CHECK(support_status IN ('AVAILABLE','BETA','PLANNED')),
  -- 2.1.1.2 能力矩阵：跨系统隐性依赖，判定"某平台是否支持某功能"，
  -- 被 09 订单、07 库存、10 物流、11 售后引用来禁用按钮，而不是点了才报错。
  capabilities   jsonb NOT NULL DEFAULT '{}'::jsonb,
  position       int NOT NULL DEFAULT 0,
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- 2.1.1.3 站点参数：默认币种/时区/语言供 05 定价、12 财务、16 合规引用。
-- 站点码只在平台内唯一：每个平台都有 US/DE 这类同名站点，平台才是它们的父级。
CREATE TABLE marketplace (
  id             uuid PRIMARY KEY,
  platform_id    uuid NOT NULL REFERENCES platform(id),
  code           varchar(32) NOT NULL,
  name           varchar(100) NOT NULL,
  country_code   char(2) NOT NULL,
  default_currency char(3) NOT NULL,
  default_timezone varchar(64) NOT NULL DEFAULT 'UTC',
  default_locale   varchar(35) NOT NULL DEFAULT 'en-US',
  currency_scale int NOT NULL DEFAULT 2 CHECK(currency_scale BETWEEN 0 AND 4),
  -- 是否需要申报 VAT：合规判断的输入之一
  vat_required   boolean NOT NULL DEFAULT false,
  position       int NOT NULL DEFAULT 0,
  UNIQUE(platform_id, code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- ---------------------------------------------------------------------------
-- platform_enum_mapping · 平台枚举 → 内部枚举
-- ---------------------------------------------------------------------------
-- 2.1.2.1：映射缺失必须显式报"未映射"，不落兜底，否则 09 订单状态机、11 售后原因、
-- 10 轨迹状态会被污染。全局码表，不带 tenant_id。
CREATE TABLE platform_enum_mapping (
  id            uuid PRIMARY KEY,
  platform_id   uuid NOT NULL REFERENCES platform(id),
  enum_kind     varchar(64) NOT NULL,
  external_value varchar(128) NOT NULL,
  internal_value varchar(64) NOT NULL,
  UNIQUE(platform_id, enum_kind, external_value),
  CHECK(btrim(enum_kind)<>'' AND btrim(external_value)<>'' AND btrim(internal_value)<>'')
);

-- ---------------------------------------------------------------------------
-- credential_ref · 凭据引用
-- ---------------------------------------------------------------------------
-- 2.2.1.3 铁律：库里只存密钥管理器引用，绝不存明文 token。
CREATE TABLE credential_ref (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  provider          text NOT NULL DEFAULT 'SECRET_MANAGER',
  external_ref      text NOT NULL,
  token_type        varchar(32) NOT NULL DEFAULT 'OAUTH2',
  granted_scopes    text NOT NULL DEFAULT '',
  authorized_at     timestamptz NOT NULL,
  expires_at        timestamptz,
  rotated_at        timestamptz,
  status            text NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','EXPIRING','EXPIRED','REVOKED')),
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一租户下引用必须唯一，避免两个连接指向同一份凭据而轮换时互相影响
  UNIQUE(tenant_id, external_ref),
  CHECK(btrim(external_ref)<>'')
);

-- ---------------------------------------------------------------------------
-- channel_connection · 一次商家授权连接
-- ---------------------------------------------------------------------------
CREATE TABLE channel_connection (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  platform_id           uuid NOT NULL REFERENCES platform(id),
  credential_id         uuid,
  authorized_account    varchar(255) NOT NULL,
  display_name          varchar(150) NOT NULL DEFAULT '',
  status                text NOT NULL DEFAULT 'ACTIVE'
                        CHECK(status IN ('ACTIVE','EXPIRING','EXPIRED','REVOKED','PAUSED')),
  sync_paused           boolean NOT NULL DEFAULT false,
  last_sync_at          timestamptz,
  last_error            text NOT NULL DEFAULT '',
  version               bigint NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, platform_id, authorized_account),
  FOREIGN KEY(tenant_id, credential_id) REFERENCES credential_ref(tenant_id, id),
  CHECK(btrim(authorized_account)<>'')
);

-- ---------------------------------------------------------------------------
-- shop / shop_marketplace · 商业店铺与启用站点
-- ---------------------------------------------------------------------------
-- 2.3.2.1 经营模式：成本核算的第一分叉点，全托管与自发货的"利润"含义完全不同，
-- 12 财务、13 利润报表必须按此分组。
CREATE TABLE shop (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  platform_id       uuid NOT NULL REFERENCES platform(id),
  connection_id     uuid,
  -- 绑定法人是财务记账与货权的前提；法人由 identity 拥有，这里只存逻辑 ID，
  -- 存在性与租户归属由 identity 的授权 API 确认，不跨库建 FK。
  legal_entity_id   uuid,
  code              varchar(64) NOT NULL,
  name              varchar(150) NOT NULL,
  platform_shop_id  varchar(128) NOT NULL DEFAULT '',
  operating_mode    text NOT NULL DEFAULT 'SELF_FULFILLED'
                    CHECK(operating_mode IN ('SELF_FULFILLED','FULL_CONSIGNMENT','SEMI_CONSIGNMENT','OVERSEAS_WAREHOUSE','LOCAL')),
  -- 负责人用于业绩归属与绩效；可见性由 17 的数据范围单独控制（2.3.2.2）
  owner_id          uuid,
  department_id     uuid,
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- 2.3.1.1 启用站点：一个连接可服务多个店铺，店铺可启用多个站点。
CREATE TABLE shop_marketplace (
  tenant_id     uuid NOT NULL,
  shop_id       uuid NOT NULL,
  marketplace_id uuid NOT NULL,
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED')),
  enabled_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id, shop_id, marketplace_id),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(marketplace_id) REFERENCES marketplace(id)
);

-- ---------------------------------------------------------------------------
-- audit_event / outbox_event · 技术表
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, actor_id uuid NOT NULL, resource_id uuid NOT NULL,
  action text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox_event (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL,
  event_type text NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 多租户隔离
-- ---------------------------------------------------------------------------
-- 全局码表 platform/marketplace 不参与租户隔离；租户业务表一律 FORCE + isolation。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['credential_ref','channel_connection','shop','shop_marketplace','audit_event','outbox_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：店铺负责人可见自己负责的店铺（2.3.2.2）
-- ---------------------------------------------------------------------------
-- 与 catalog V3 的 catalog_scope 同构：每次授权必须同时提供操作权限与其 .read 权限，
-- 且范围（ALL / SELF / DEPARTMENT）由同一次角色分配满足。
CREATE FUNCTION channel_scope(needed text,owner_id uuid,department_id uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
 SELECT coalesce(EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(nullif(current_setting('app.grants',true),''),'[]')::jsonb) g
 WHERE (g->'permissions') ? needed
 AND (g->'permissions') ? (regexp_replace(needed,'\.[^.]+$','')||'.read')
 AND (g->>'scopeMode'='ALL'
 OR (g->>'scopeMode'='SELF' AND owner_id=nullif(current_setting('app.actor',true),'')::uuid)
 OR (g->>'scopeMode'='DEPARTMENT' AND department_id=nullif(current_setting('app.department',true),'')::uuid))),false)
$$;
CREATE FUNCTION channel_operation() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.permission',true),'') $$;

-- owner_id 是创建时的不可变快照，与 catalog 的 stamp_record_owner 同一纪律：
-- 改负责人等于换归属，必须走显式的转让动作而不是静默改字段。
-- 转让端点通过 set_config('app.transfer', ...) 显式声明意图，触发器据此放行，
-- 使"谁在什么时候把店铺转给了谁"只可能来自那个可审计的端点。
CREATE FUNCTION stamp_shop_owner() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
  NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
  IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
 ELSE
  IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
     AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
  THEN RAISE EXCEPTION 'Shop ownership transfer must use the explicit transfer endpoint' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER shop_owner BEFORE INSERT OR UPDATE ON shop FOR EACH ROW EXECUTE FUNCTION stamp_shop_owner();

CREATE POLICY data_scope ON shop AS RESTRICTIVE
 USING (channel_scope(channel_operation(),owner_id,department_id))
 WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 店铺下属对象的可见性随店铺收敛，不允许绕过店铺直接枚举站点。
CREATE POLICY data_scope ON shop_marketplace AS RESTRICTIVE
 USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=shop_marketplace.tenant_id AND s.id=shop_marketplace.shop_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
 WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=shop_marketplace.tenant_id AND s.id=shop_marketplace.shop_id
               AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- 凭据引用只能被拥有连接权限的调用方读写。channel_operation() 返回的已经是
-- 完整权限码（如 channel.connection.update），因此直接比较，不能再拼前缀。
CREATE POLICY credential_scope ON credential_ref AS RESTRICTIVE
 USING (channel_scope(channel_operation(),NULL,NULL))
 WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- 授权时间不能是未来（写入即已发生的动作）。到期时间允许早于当前时间：
-- 2.2.2.1 的健康诊断必须能读出"已过期的凭据"来提示重新授权，因此这里不约束 expires_at 相对 now()。
ALTER TABLE credential_ref ADD CONSTRAINT credential_window
 CHECK(authorized_at <= now() + interval '5 minutes');

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX connection_platform ON channel_connection(tenant_id, platform_id);
CREATE INDEX shop_platform ON shop(tenant_id, platform_id);
CREATE INDEX shop_owner ON shop(tenant_id, owner_id);
CREATE INDEX shop_department ON shop(tenant_id, department_id);
CREATE INDEX shop_marketplace_market ON shop_marketplace(tenant_id, marketplace_id);
-- 2.2.2.2 到期提醒按到期时间扫描，只取临期未失效的凭据
CREATE INDEX credential_expiry ON credential_ref(tenant_id, expires_at)
  WHERE status IN ('ACTIVE','EXPIRING');

-- ---------------------------------------------------------------------------
-- 平台与站点注册表种子（2.1.1.1 / 2.1.1.3）
-- ---------------------------------------------------------------------------
-- 随代码发布的只读注册表。capabilities 是 2.1.1.2 的能力矩阵声明：09 订单、07 库存、
-- 10 物流、11 售后引用它来禁用不支持的按钮，而不是让用户点了才报错。
INSERT INTO platform(id,code,name,integration,support_status,capabilities,position) VALUES
 ('11111111-1111-1111-1111-111111111101','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true,"fba_fulfillment":true}',1),
 ('11111111-1111-1111-1111-111111111102','SHOPIFY','Shopify','OFFICIAL_API','AVAILABLE',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":false}',2),
 ('11111111-1111-1111-1111-111111111103','EBAY','eBay','OFFICIAL_API','AVAILABLE',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',3),
 ('11111111-1111-1111-1111-111111111104','TIKTOK_SHOP','TikTok Shop','OFFICIAL_API','AVAILABLE',
  '{"multi_channel_delivery":false,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true,"creator_attribution":true}',4),
 ('11111111-1111-1111-1111-111111111105','TEMU','Temu','INTERMEDIARY','AVAILABLE',
  '{"multi_channel_delivery":false,"inventory_write_back":true,"return_authorization":false,"label_print":false,"delayed_settlement":true,"full_consignment":true}',5),
 ('11111111-1111-1111-1111-111111111106','WALMART','Walmart','OFFICIAL_API','BETA',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',6),
 ('11111111-1111-1111-1111-111111111107','SHOPEE','Shopee','OFFICIAL_API','BETA',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',7),
 ('11111111-1111-1111-1111-111111111108','LAZADA','Lazada','OFFICIAL_API','BETA',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',8),
 ('11111111-1111-1111-1111-111111111109','MERCADO_LIBRE','Mercado Libre','OFFICIAL_API','PLANNED',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',9),
 ('11111111-1111-1111-1111-111111111110','ALIEXPRESS','AliExpress','OFFICIAL_API','PLANNED',
  '{"multi_channel_delivery":true,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',10),
 ('11111111-1111-1111-1111-111111111111','ETSY','Etsy','OFFICIAL_API','PLANNED',
  '{"multi_channel_delivery":false,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":false}',11),
 ('11111111-1111-1111-1111-111111111112','WAYFAIR','Wayfair','THIRD_PARTY_ISV','PLANNED',
  '{"multi_channel_delivery":false,"inventory_write_back":true,"return_authorization":true,"label_print":true,"delayed_settlement":true}',12);

-- 站点参数供 05 定价（默认币种）、12 财务（本位币/汇率）、16 合规（VAT 申报）引用。
-- marketplace.id 由 md5 派生，保证迁移可重复执行且不依赖序列。
INSERT INTO marketplace(id,platform_id,code,name,country_code,default_currency,default_timezone,default_locale,currency_scale,vat_required,position)
SELECT (substr(md5(p.code||':'||v.code),1,8)||'-'||substr(md5(p.code||':'||v.code),9,4)||'-4'||substr(md5(p.code||':'||v.code),14,3)
        ||'-a'||substr(md5(p.code||':'||v.code),18,3)||'-'||substr(md5(p.code||':'||v.code),21,12))::uuid,
       p.id, v.code, v.name, v.cc, v.cur, v.tz, v.loc, v.scale, v.vat, v.k
FROM (VALUES
 ('AMAZON',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('AMAZON',2,'DE','德国站','DE','EUR','Europe/Berlin','de-DE',2,true),
 ('AMAZON',3,'UK','英国站','GB','GBP','Europe/London','en-GB',2,true),
 ('AMAZON',4,'JP','日本站','JP','JPY','Asia/Tokyo','ja-JP',0,true),
 ('AMAZON',5,'CA','加拿大站','CA','CAD','America/Toronto','en-CA',2,true),
 ('AMAZON',6,'FR','法国站','FR','EUR','Europe/Paris','fr-FR',2,true),
 ('AMAZON',7,'IT','意大利站','IT','EUR','Europe/Rome','it-IT',2,true),
 ('AMAZON',8,'ES','西班牙站','ES','EUR','Europe/Madrid','es-ES',2,true),
 ('AMAZON',9,'AU','澳大利亚站','AU','AUD','Australia/Sydney','en-AU',2,true),
 ('AMAZON',10,'AE','阿联酋站','AE','AED','Asia/Dubai','en-AE',2,true),
 ('AMAZON',11,'BR','巴西站','BR','BRL','America/Sao_Paulo','pt-BR',2,true),
 ('SHOPIFY',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('SHOPIFY',2,'GB','英国站','GB','GBP','Europe/London','en-GB',2,true),
 ('SHOPIFY',3,'DE','德国站','DE','EUR','Europe/Berlin','de-DE',2,true),
 ('SHOPIFY',4,'AU','澳大利亚站','AU','AUD','Australia/Sydney','en-AU',2,true),
 ('EBAY',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('EBAY',2,'UK','英国站','GB','GBP','Europe/London','en-GB',2,true),
 ('EBAY',3,'DE','德国站','DE','EUR','Europe/Berlin','de-DE',2,true),
 ('TIKTOK_SHOP',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('TIKTOK_SHOP',2,'UK','英国站','GB','GBP','Europe/London','en-GB',2,true),
 ('TEMU',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('TEMU',2,'EU','欧洲站','DE','EUR','Europe/Berlin','en-GB',2,true),
 ('WALMART',1,'US','美国站','US','USD','America/Los_Angeles','en-US',2,false),
 ('SHOPEE',1,'SG','新加坡站','SG','SGD','Asia/Singapore','en-SG',2,true),
 ('SHOPEE',2,'MY','马来西亚站','MY','MYR','Asia/Kuala_Lumpur','en-MY',2,true),
 ('SHOPEE',3,'TH','泰国站','TH','THB','Asia/Bangkok','th-TH',2,true),
 ('LAZADA',1,'SG','新加坡站','SG','SGD','Asia/Singapore','en-SG',2,true),
 ('LAZADA',2,'MY','马来西亚站','MY','MYR','Asia/Kuala_Lumpur','en-MY',2,true)
) AS v(pcode,k,code,name,cc,cur,tz,loc,scale,vat)
JOIN platform p ON p.code = v.pcode;

-- ---------------------------------------------------------------------------
-- 账号与授权
-- ---------------------------------------------------------------------------
-- 角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
-- channel_app 是运行角色：平台注册表是全局只读码表，其余租户业务表按 RLS 隔离后可读写。
-- 目录只有平台目录页（无需租户上下文）能读，租户数据一律不回查这里。
GRANT SELECT ON platform, marketplace, platform_enum_mapping TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO channel_app;
REVOKE UPDATE,DELETE ON audit_event FROM channel_app;
REVOKE ALL ON flyway_schema_history FROM channel_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
