-- 02.6.1 平台消息接入 · 接收记录。
--
-- 铁律（蓝图 §2.6.1.1）：
--   1) **先持久化再处理**：接收成功即落库并返回 200，业务处理异步化。
--      平台回调有超时上限，同步处理会让平台超时重推，重推又会把压力放大成雪崩。
--   2) **重复推送不产生第二条可处理记录**：去重锚点是外部事件 ID，
--      第一条被认领为待处理，后续重推全部标记为重复并指向第一条。
--   3) **验签失败也要落库**：验签失败的回调同样是安全事件，丢掉它等于放弃审计线索。

-- ---------------------------------------------------------------------------
-- webhook_endpoint · 平台回调入口登记
-- ---------------------------------------------------------------------------
-- 回调 URL 里的 endpoint_key 是**公开**的（平台要能直接打进来），它不是凭证：
--   能伪造 URL 的人不代表能通过验签，真正的信任锚点是 HMAC 签名。
-- 签名密钥只存密文（AES-GCM），主密钥由 CHANNEL_WEBHOOK_SIGNING_KEY 注入，
-- 不入库、不进代码库（AGENTS.md：第三方 key 一律走 secret manager）。
CREATE TABLE webhook_endpoint (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL,
  connection_id        uuid NOT NULL,
  -- 公开回调路径片段：/channel/webhooks/{endpoint_key}
  endpoint_key         varchar(64) NOT NULL,
  signature_algorithm  text NOT NULL DEFAULT 'HmacSHA256'
                       CHECK(signature_algorithm IN ('HmacSHA256')),
  -- 平台把签名放在哪个请求头里（Shopify 用 X-Shopify-Hmac-Sha256，eBay 用 X-Ebay-Signature…）
  signature_header     varchar(64) NOT NULL DEFAULT 'X-Starcon-Signature',
  -- 签名值的编码形式：HEX 或 BASE64，两种都要能真实比对，不能只认一种
  signature_encoding   text NOT NULL DEFAULT 'HEX'
                       CHECK(signature_encoding IN ('HEX','BASE64')),
  -- AES-GCM 密文（nonce|ciphertext，Base64），**绝不入库明文**
  signing_secret_enc   text NOT NULL,
  status               text NOT NULL DEFAULT 'ACTIVE'
                       CHECK(status IN ('ACTIVE','DISABLED')),
  last_received_at     timestamptz,
  version              bigint NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 全局唯一：公开回调路径不能撞车，否则一个平台的投递会落到另一个租户的入口
  UNIQUE(endpoint_key),
  -- 一个连接只登记一个入口：重复登记会让验签密钥出现两份，排查时无从判断哪份生效
  UNIQUE(tenant_id, connection_id),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- webhook_receipt · 接收记录（蓝图 §2.6.1.1 的本体）
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_receipt (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  endpoint_id        uuid NOT NULL,
  connection_id      uuid NOT NULL,
  -- 来源店铺。平台回调不一定带店铺维度（部分平台只给连接级事件），因此可空
  shop_id            uuid,
  -- 外部事件 ID：去重的唯一锚点。下游 2.6.1.2 的去重视图按它归并
  external_event_id  varchar(200) NOT NULL,
  event_type         varchar(100) NOT NULL DEFAULT '',
  topic              varchar(100) NOT NULL DEFAULT '',
  -- 原始报文。入库前已脱敏（凭据类字段剔除），不存明文 token
  payload            jsonb NOT NULL DEFAULT '{}'::jsonb,
  payload_digest     varchar(64) NOT NULL DEFAULT '',
  -- 验签结论。UNCONFIGURED 与 FAILED 必须区分：前者是运维没登记密钥，
  -- 后者是有人在伪造推送，两者的处置动作完全不同。
  signature_status   text NOT NULL
                     CHECK(signature_status IN ('VERIFIED','FAILED','MISSING','UNCONFIGURED')),
  signature_header   varchar(64) NOT NULL DEFAULT '',
  -- 处理状态。RECEIVED=已入库，DUPLICATE=重复，SIGNATURE_FAILED=验签失败，
  -- PROCESSING=处理中，PROCESSED=已处理完，FAILED=处理失败可重试。
  status             text NOT NULL DEFAULT 'RECEIVED'
                     CHECK(status IN ('RECEIVED','DUPLICATE','SIGNATURE_FAILED',
                                      'PROCESSING','PROCESSED','FAILED')),
  -- 指向首次接收记录：只有 duplicate_of IS NULL 的那条可以被认领处理
  duplicate_of       uuid,
  delivery_attempt   int NOT NULL DEFAULT 1 CHECK(delivery_attempt >= 1),
  last_error         text NOT NULL DEFAULT '',
  -- 接收时间：本模块的核心事实字段。平台超时重推的排查完全依赖它，
  -- 必须精确落库（timestamptz + OffsetDateTime 绑定，不能用 LocalDateTime 截断）
  received_at        timestamptz NOT NULL,
  processing_started_at timestamptz,
  processed_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, endpoint_id) REFERENCES webhook_endpoint(tenant_id, id),
  FOREIGN KEY(tenant_id, connection_id) REFERENCES channel_connection(tenant_id, id),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY(tenant_id, duplicate_of) REFERENCES webhook_receipt(tenant_id, id)
);

-- **只处理一次**的物理保证：同一租户 + 同一入口 + 同一外部事件 ID，
-- 只允许存在一条「首次」记录（duplicate_of IS NULL）。
-- 并发投递下第二个请求会撞唯一索引，从而不会同时产生两条可处理记录。
CREATE UNIQUE INDEX webhook_receipt_first_delivery
  ON webhook_receipt(tenant_id, endpoint_id, external_event_id)
  WHERE duplicate_of IS NULL;

-- ---------------------------------------------------------------------------
-- 多租户隔离
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['webhook_endpoint','webhook_receipt'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 接收记录的数据范围随店铺收敛，与 sync_failure 同一纪律：
-- 运营不该通过回调记录看到无权店铺的推送内容。
CREATE POLICY receipt_scope ON webhook_receipt AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));
CREATE POLICY endpoint_scope ON webhook_endpoint AS RESTRICTIVE
  USING (channel_scope(channel_operation(),NULL,NULL))
  WITH CHECK (channel_scope(channel_operation(),NULL,NULL));

-- 回调入口解析：接入侧要在**还没有租户上下文**时按 endpoint_key 找到入口。
-- 入口表带 RLS，直接查必然查不到，因此走 SECURITY DEFINER。
--
-- 它刻意只返回验签必需的字段（不返回 signing_secret 以外的任何业务数据，
-- 也不返回其他租户的行），且必须命中 ACTIVE 入口 —— 停用的入口立即不再受理投递。
-- 能猜到 URL 的人只得到"这里有个入口"，真正的授权由随请求的 HMAC 签名完成。
CREATE FUNCTION resolve_webhook_endpoint(p_key text)
RETURNS TABLE (id uuid, tenant_id uuid, connection_id uuid, signing_secret_enc text,
               signature_algorithm text, signature_header text, signature_encoding text)
LANGUAGE sql STABLE SECURITY DEFINER AS $$
  SELECT e.id, e.tenant_id, e.connection_id, e.signing_secret_enc,
         e.signature_algorithm, e.signature_header, e.signature_encoding
  FROM webhook_endpoint e
  WHERE e.endpoint_key = p_key AND e.status = 'ACTIVE'
$$;
REVOKE ALL ON FUNCTION resolve_webhook_endpoint(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_webhook_endpoint(text) TO channel_app;

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
-- 工作台默认视图：按接收时间倒序翻页
CREATE INDEX webhook_receipt_recent ON webhook_receipt(tenant_id, received_at DESC);
-- 状态看板：按状态聚合计数
CREATE INDEX webhook_receipt_status ON webhook_receipt(tenant_id, status, received_at DESC);
-- 入口视角的排查路径：这个入口最近收到了什么
CREATE INDEX webhook_receipt_endpoint ON webhook_receipt(tenant_id, endpoint_id, received_at DESC);
-- 验签失败是安全事件，需要单独可检索
CREATE INDEX webhook_receipt_bad_signature ON webhook_receipt(tenant_id, received_at DESC)
  WHERE signature_status IN ('FAILED','MISSING','UNCONFIGURED');
-- 下游 2.6.1.2 去重视图按外部事件 ID 归并同一业务事件的重推次数
CREATE INDEX webhook_receipt_event ON webhook_receipt(tenant_id, external_event_id, received_at);

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V2 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE ON webhook_endpoint, webhook_receipt TO channel_app;
-- webhook_receipt 需要 UPDATE：认领处理（RECEIVED→PROCESSING）与收尾（→PROCESSED/FAILED）
-- 都写这条记录；"已处理完不可再改"由下方触发器保证，不靠撤表权限。
GRANT DELETE ON webhook_endpoint TO channel_app;

-- 已处理的记录不可再被改写：可逆性靠补新记录冲销，不靠改历史。
-- 这条纪律让「处理了多久、处理成什么样」在事后永远说得清。
CREATE FUNCTION guard_settled_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status IN ('PROCESSED','SIGNATURE_FAILED','DUPLICATE') THEN
    RAISE EXCEPTION '该接收记录已终结，不可修改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER receipt_settled BEFORE UPDATE ON webhook_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_settled_receipt();

-- 验签失败的记录不允许被认领处理：没有可信来源的推送一旦进入业务处理
-- 就等于让伪造数据污染订单。这条约束落在 DB 层，绕不过去。
CREATE FUNCTION reject_unverified_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.status = 'PROCESSING' AND NEW.signature_status <> 'VERIFIED' THEN
    RAISE EXCEPTION '验签未通过的回调不允许进入业务处理' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER receipt_unverified BEFORE UPDATE ON webhook_receipt
  FOR EACH ROW EXECUTE FUNCTION reject_unverified_claim();

-- 权限码复用 channel.sync.*（02.4/02.5/10.4.2.1 同一处置）：
-- 接收记录就是平台消息的入库侧，与同步运行同属平台对接面。
-- 另起权限码需要写 identity 迁移（跨车道版本号风险），收益不足。
