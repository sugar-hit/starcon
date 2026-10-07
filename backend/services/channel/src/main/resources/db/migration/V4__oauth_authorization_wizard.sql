-- 2.2.1.2 授权向导：平台 OAuth 端点配置、授权发起会话（state/CSRF/PKCE）、
-- 回调解析出的店铺候选，以及"一个平台账号只能绑定一个租户"的跨租户唯一性。
--
-- 设计要点（与蓝图 2.2.1.2「关系」段一一对应）：
--   * 授权回调的租户由 state → 授权发起会话的绑定关系确定，不采信任何请求头。
--     因此需要一个在 RLS 之外解析 state 的入口：oauth_resolve_session_tenant，
--     它是 SECURITY DEFINER 的窄函数，只回答"这个 state 属于哪个租户、且现在还有效吗"。
--   * 平台应用凭据（client_id / client_secret）不入库：库里只放端点与参数名，
--     真正的密钥走运行时配置（见 PlatformOAuthProperties）。

-- ---------------------------------------------------------------------------
-- platform_oauth_profile · 平台 OAuth 端点注册表（全局码表）
-- ---------------------------------------------------------------------------
-- 端点与字段名全部配置驱动：不把任何平台 URL 写死在 Java 里，换环境只需改这张表或运行时配置。
-- 字段名（account_id_field / shops_field / ...）也是配置：各平台账号接口的响应结构不同，
-- 用配置描述而不是用 if/else 硬编码，是为了让新增平台只加数据、不改编码逻辑。
CREATE TABLE platform_oauth_profile (
  platform_id           uuid PRIMARY KEY REFERENCES platform(id),
  authorize_url         text NOT NULL,
  token_url             text NOT NULL,
  -- 账号信息端点留空表示该平台换令牌时已随响应返回账号信息（Shopify 等），不再单独查询
  account_info_url      text NOT NULL DEFAULT '',
  scope_param           varchar(64) NOT NULL DEFAULT 'scope',
  login_hint_param      varchar(64) NOT NULL DEFAULT 'login_hint',
  -- 2.2.1.2 第 2 步「选账号」：部分平台用 prompt=select_account 强制回到账号选择页
  account_prompt_param  varchar(64) NOT NULL DEFAULT '',
  account_prompt_value  varchar(64) NOT NULL DEFAULT '',
  token_auth_method     text NOT NULL DEFAULT 'CLIENT_SECRET_BASIC'
                        CHECK(token_auth_method IN ('CLIENT_SECRET_BASIC','CLIENT_SECRET_POST')),
  token_request_style   text NOT NULL DEFAULT 'FORM'
                        CHECK(token_request_style IN ('FORM','JSON')),
  code_challenge_method text NOT NULL DEFAULT 'NONE' CHECK(code_challenge_method IN ('NONE','S256')),
  default_scopes        text NOT NULL DEFAULT '',
  -- token 响应字段名（各平台大小写/命名不一致：expires_in / expire_in / ...）
  token_access_field    varchar(64) NOT NULL DEFAULT 'access_token',
  token_refresh_field   varchar(64) NOT NULL DEFAULT 'refresh_token',
  token_expires_field   varchar(64) NOT NULL DEFAULT 'expires_in',
  token_scope_field     varchar(64) NOT NULL DEFAULT 'scope',
  -- 账号信息响应字段名
  account_id_field      varchar(64) NOT NULL DEFAULT 'account_id',
  account_name_field    varchar(64) NOT NULL DEFAULT 'account_name',
  shops_field           varchar(64) NOT NULL DEFAULT 'shops',
  shop_id_field         varchar(64) NOT NULL DEFAULT 'shop_id',
  shop_name_field       varchar(64) NOT NULL DEFAULT 'name',
  shop_marketplace_field varchar(64) NOT NULL DEFAULT 'marketplace_code',
  -- 平台要求随授权请求一起带的静态参数（Shopify 的 grant_options、Shopee 的 partner_id 等）
  extra_authorize_params jsonb NOT NULL DEFAULT '{}'::jsonb,
  CHECK(btrim(authorize_url)<>'' AND btrim(token_url)<>'')
);

-- ---------------------------------------------------------------------------
-- oauth_authorization_session · 授权发起会话
-- ---------------------------------------------------------------------------
-- state 是 CSRF 绑定：浏览器跳转时带出去，回调时带回来，服务端只认自己签发且未消费的 state。
-- 一次授权 = 一个会话；回调解析出的账号、店铺候选、最终落库的连接都挂在会话上。
CREATE TABLE oauth_authorization_session (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  platform_id       uuid NOT NULL REFERENCES platform(id),
  state_token       text NOT NULL,
  -- PKCE：S256 时保存原始 verifier，换令牌时回传。verifier 与 state 同生共死，不单独建表。
  code_verifier     text,
  -- 发起人快照：回调没有 bearer，令牌身份从这里恢复，租户归属因此不依赖任何请求头
  initiated_by      uuid NOT NULL,
  actor_department_id uuid,
  actor_grants      jsonb NOT NULL DEFAULT '[]'::jsonb,
  login_hint        varchar(255) NOT NULL DEFAULT '',
  redirect_path     varchar(255) NOT NULL DEFAULT '/channel/oauth/callback',
  requested_scopes  text NOT NULL DEFAULT '',
  granted_scopes    text NOT NULL DEFAULT '',
  status            text NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','AUTHORIZED','FAILED','COMPLETED','EXPIRED','CANCELLED')),
  -- 授权失败必须给出可执行原因（凭据错 / 权限不足 / 店铺已被其他租户绑定），码与文案都留档
  failure_code      varchar(48),
  failure_message   text NOT NULL DEFAULT '',
  authorized_account varchar(255),
  authorized_account_name varchar(255) NOT NULL DEFAULT '',
  connection_id     uuid,
  credential_id     uuid,
  idempotency_key   varchar(128) NOT NULL,
  expires_at        timestamptz NOT NULL,
  completed_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  -- state 全局唯一：回调只凭 state 定位会话，这是"不采信请求头"的物理保证
  UNIQUE(state_token),
  -- 与 credential_ref / channel_connection 同构：供候选表按 (tenant_id, session_id) 建复合外键，
  -- 让"候选必然属于发起该授权的租户"由数据库保证，而不是只靠应用层自觉。
  UNIQUE(tenant_id, id),
  -- 幂等：同一租户重复提交同一个 idempotency_key 返回同一个会话，不重复跳转
  UNIQUE(tenant_id, idempotency_key),
  CHECK(btrim(state_token)<>'' AND btrim(idempotency_key)<>'')
);

-- ---------------------------------------------------------------------------
-- oauth_shop_candidate · 回调解析出的店铺范围候选
-- ---------------------------------------------------------------------------
-- 2.2.1.2 第 5 步「确认店铺范围」：平台账号下可能挂着多个店铺，用户勾选要纳管的那些。
-- 候选由回调写入，确认前不算任何业务事实，因此不建 shop 行。
CREATE TABLE oauth_shop_candidate (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  session_id             uuid NOT NULL,
  platform_id            uuid NOT NULL REFERENCES platform(id),
  external_shop_id       varchar(128) NOT NULL,
  shop_name              varchar(200) NOT NULL DEFAULT '',
  default_marketplace_code varchar(32) NOT NULL DEFAULT '',
  created_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE(session_id, external_shop_id),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES oauth_authorization_session(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(external_shop_id)<>'')
);

-- ---------------------------------------------------------------------------
-- 跨租户唯一性：同一平台账号只能绑定一个租户
-- ---------------------------------------------------------------------------
-- RLS 过滤的是"看得见的行"，但唯一索引是全局的，因此这条索引就是蓝图要求的
-- "店铺已被其他租户绑定"的物理约束：租户 B 再也插不进租户 A 已绑定的平台账号。
-- lower() 让大小写不同的同一个账号（Foo@x.com / foo@x.com）也被判为冲突。
CREATE UNIQUE INDEX connection_account_owner
  ON channel_connection(platform_id, lower(authorized_account));
-- 店铺同理：平台店铺 ID 一旦归属某个租户，别的租户不能再纳管同一个店铺。
-- 只对填了平台店铺 ID 的行生效（手工建的店铺可以留空）。
CREATE UNIQUE INDEX shop_platform_owner
  ON shop(platform_id, lower(platform_shop_id))
  WHERE platform_shop_id <> '';

-- ---------------------------------------------------------------------------
-- RLS 与授权
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['oauth_authorization_session','oauth_shop_candidate'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- oauth_shop_candidate 的数据范围随会话收敛：没有发起人权限的人不该读到候选店铺列表。
CREATE POLICY data_scope ON oauth_shop_candidate AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM oauth_authorization_session s
                 WHERE s.tenant_id=oauth_shop_candidate.tenant_id AND s.id=oauth_shop_candidate.session_id
                 AND channel_scope(channel_operation(),s.initiated_by,s.actor_department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM oauth_authorization_session s
                 WHERE s.tenant_id=oauth_shop_candidate.tenant_id AND s.id=oauth_shop_candidate.session_id
                 AND channel_scope(channel_operation(),s.initiated_by,s.actor_department_id)));

-- ---------------------------------------------------------------------------
-- state → 授权发起人的唯一越权入口
-- ---------------------------------------------------------------------------
-- 为什么必须有这个函数：回调请求没有可信身份（浏览器从平台重定向过来，租户头不可信），
-- 而 oauth_authorization_session 开了 FORCE RLS，没有 app.current_tenant 就一行都读不到。
-- 所以用 SECURITY DEFINER 开一个口子，并把口径收到最窄：
--   * 只认 state_token 精确匹配；
--   * 只认 PENDING 且未过期的会话——回调处理完状态就变，重放拿不到任何东西（这就是 CSRF 防护）；
--   * 只回答"这次授权是谁、在哪个租户、哪个平台发起的"，即 jsonb 里的六个字段。
--     没有令牌、没有 secret、没有对方的连接名或店铺名。
-- 拿到租户后，正常的 app.current_tenant 绑定照旧生效，后续所有读写仍然受 RLS 与数据范围约束。
CREATE FUNCTION oauth_callback_bootstrap(p_state text) RETURNS jsonb
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT jsonb_build_object(
    'tenantId', s.tenant_id,
    'platformId', s.platform_id,
    'initiatedBy', s.initiated_by,
    'actorDepartmentId', s.actor_department_id,
    'actorGrants', s.actor_grants,
    'loginHint', s.login_hint)
  FROM oauth_authorization_session s
  WHERE s.state_token = p_state AND s.status = 'PENDING' AND s.expires_at > now()
$$;
REVOKE ALL ON FUNCTION oauth_callback_bootstrap(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION oauth_callback_bootstrap(text) TO channel_app;

-- 平台账号是否已被别的租户绑定。返回占用者租户；未被占用返回 NULL。
-- 同样必须是 SECURITY DEFINER：租户 B 需要知道"这个账号归 A"，但看不到 A 的任何业务行。
-- 只返回租户 UUID，不暴露对方的连接名、店铺名或任何可识别信息。
CREATE FUNCTION oauth_account_owner(p_platform uuid, p_account text) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT c.tenant_id FROM channel_connection c
  WHERE c.platform_id = p_platform AND lower(c.authorized_account) = lower(btrim(p_account))
$$;
REVOKE ALL ON FUNCTION oauth_account_owner(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION oauth_account_owner(uuid, text) TO channel_app;

-- 平台店铺是否已被别的租户纳管。同上：只回答"归不归别人"，不泄漏细节。
CREATE FUNCTION oauth_shop_owner(p_platform uuid, p_shop text) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT s.tenant_id FROM shop s
  WHERE s.platform_id = p_platform AND lower(s.platform_shop_id) = lower(btrim(p_shop))
$$;
REVOKE ALL ON FUNCTION oauth_shop_owner(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION oauth_shop_owner(uuid, text) TO channel_app;

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
-- 回调按 state 定位会话：UNIQUE(state_token) 已有索引，这里补状态维度供过期清理扫描
CREATE INDEX oauth_session_status ON oauth_authorization_session(tenant_id, status, expires_at);
CREATE INDEX oauth_session_shop_candidates ON oauth_shop_candidate(tenant_id, session_id);

-- ---------------------------------------------------------------------------
-- 平台 OAuth 端点注册表种子（2.2.1.2 上游 = 平台注册表）
-- ---------------------------------------------------------------------------
-- 只覆盖 V1 里标记 AVAILABLE / BETA 的平台：PLANNED 平台没有 OAuth profile，
-- 授权向导会对它们返回"平台尚未接入授权向导"，而不是跳转到不存在的地址。
INSERT INTO platform_oauth_profile (
  platform_id, authorize_url, token_url, account_info_url,
  scope_param, login_hint_param, account_prompt_param, account_prompt_value,
  token_auth_method, token_request_style, code_challenge_method, default_scopes,
  token_access_field, token_refresh_field, token_expires_field, token_scope_field,
  account_id_field, account_name_field, shops_field, shop_id_field, shop_name_field,
  shop_marketplace_field, extra_authorize_params)
SELECT p.id, v.authorize, v.token, v.account, v.scope_param, v.hint_param, v.prompt_param, v.prompt_value,
       v.auth_method, v.req_style, v.pkce, v.scopes,
       v.t_access, v.t_refresh, v.t_expires, v.t_scope,
       v.a_id, v.a_name, v.shops, v.shop_id, v.shop_name, v.shop_mk,
       v.extra::jsonb
FROM (VALUES
  -- Amazon SP-API：授权在卖家中心，换令牌用 Basic 认证，返回 1 小时有效的 access + refresh
  ('AMAZON','https://sellercentral.amazon.com/apps/authorize/consent','https://api.amazon.com/auth/o2/token',
   'https://sellingpartnerapi-na.amazon.com/v1/sellers','scope','login_hint','','',
   'CLIENT_SECRET_BASIC','FORM','S256','sellingpartnerapi::orders sellingpartnerapi::items sellingpartnerapi::feeds',
   'access_token','refresh_token','expires_in','scope',
   'merchant_id','store_name','shops','shop_id','shop_name','marketplace_id',
   '{"duration":"P30D"}'::text),
  -- Shopify：授权与换令牌都在店铺域名下；令牌随响应直接带出，不需要单独查账号；scope 用逗号分隔
  ('SHOPIFY','https://{shop_domain}/admin/oauth/authorize','https://{shop_domain}/admin/oauth/access_token',
   '','scope','','','',
   'CLIENT_SECRET_POST','JSON','NONE','read_orders,read_products,read_inventory,write_inventory',
   'access_token','refresh_token','expires_in','scope',
   'shop_id','shop_name','shops','shop_id','name','primary_locale',
   '{"grant_options[]":"per-user"}'::text),
  -- eBay：prompt=select_account 让用户在授权页重新选账号，对应向导第 2 步「选账号」
  ('EBAY','https://auth.ebay.com/oauth2/authorize','https://api.ebay.com/identity/v1/oauth2/token',
   'https://api.ebay.com/identity/v1/','scope','login_hint','prompt','select_account',
   'CLIENT_SECRET_BASIC','FORM','NONE','offline_access https://api.ebay.com/oauth/api_scope/sell.fulfillment https://api.ebay.com/oauth/api_scope/sell.inventory',
   'access_token','refresh_token','expires_in','scope',
   'username','account_name','shops','shop_id','shop_name','marketplace_id',
   '{}'::text),
  -- TikTok Shop：JSON 换令牌，scope 逗号分隔，令牌与店铺列表分两个接口取
  ('TIKTOK_SHOP','https://services.tiktokshop.com/open/authorize','https://auth.tiktok-shops.com/api/v2/token/get',
   'https://open-api.tiktokglobalshop.com/authorization/202309/shops','scope','','','',
   'CLIENT_SECRET_POST','JSON','NONE','read_orders,read_products,read_inventory',
   'access_token','refresh_token','expires_in','scope',
   'shop_id','shop_name','shops','shop_id','shop_name','region','{}'::text),
  -- Temu：全托管平台，授权在 partner 域，令牌按 app 维度签发，店铺即授权主体
  ('TEMU','https://partner.temu.com/oauth/authorize','https://partner.temu.com/oauth/token',
   'https://partner.temu.com/api/v0/shops','scope','','','',
   'CLIENT_SECRET_POST','JSON','NONE','order,product,warehouse',
   'access_token','refresh_token','expires_in','scope',
   'seller_id','shop_name','shops','shop_id','shop_name','site_code','{}'::text),
  -- Walmart：Marketplace OAuth 无 scope 概念，令牌按店铺签发，账号信息在店铺接口里
  ('WALMART','https://marketplace.walmart.com/oauth/authorize','https://marketplace.walmart.com/oauth2/token',
   'https://marketplace.walmart.com/api/v3/authorization/clients/','state','','','',
   'CLIENT_SECRET_BASIC','FORM','NONE','',
   'access_token','refresh_token','expires_in','scope',
   'client_id','client_name','shops','shop_id','shop_name','marketplace_id','{}'::text),
  -- Shopee：需要 partner_id 才能发起店铺授权；令牌有效期短（2 小时），2.2.2.2 提醒阈值已覆盖
  ('SHOPEE','https://partner.shopeemobile.com/api/v2/shop/auth_partner','https://partner.shopeemobile.com/api/v2/auth/token/get',
   'https://partner.shopeemobile.com/api/v2/shop/get_shop_list','scope','','','',
   'CLIENT_SECRET_POST','FORM','NONE','shop,product,order,logistics',
   'access_token','refresh_token','expires_in','scope',
   'shop_id','shop_name','shops','shop_id','shop_name','region','{}'::text),
  -- Lazada：JSON 换令牌，scope 逗号分隔，账号即店铺
  ('LAZADA','https://auth.lazada.com/oauth/authorize','https://auth.lazada.com/rest/auth/token/create',
   'https://auth.lazada.com/rest/shop/get','scope','','','',
   'CLIENT_SECRET_POST','JSON','NONE','orders,products,inventory',
   'access_token','refresh_token','expires_in','scope',
   'seller_id','shop_name','shops','shop_id','shop_name','country','{}'::text)
) AS v(pcode, authorize, token, account, scope_param, hint_param, prompt_param, prompt_value,
       auth_method, req_style, pkce, scopes,
       t_access, t_refresh, t_expires, t_scope,
       a_id, a_name, shops, shop_id, shop_name, shop_mk, extra)
JOIN platform p ON p.code = v.pcode;

-- platform_oauth_profile 是全局码表：与 platform 一样只读，不参与租户隔离。
GRANT SELECT ON platform_oauth_profile TO channel_app;
-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，V4 新建的表必须显式授权，
-- 否则运行角色会得到 "permission denied" 而不是一条能读懂的报错。
GRANT SELECT,INSERT,UPDATE,DELETE ON oauth_authorization_session, oauth_shop_candidate TO channel_app;
GRANT EXECUTE ON FUNCTION oauth_callback_bootstrap(text), oauth_account_owner(uuid, text),
  oauth_shop_owner(uuid, text) TO channel_app;
