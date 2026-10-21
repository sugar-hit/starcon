-- ===========================================================================
-- 2.4.2.3 映射缺失队列（channel 服务）
-- ===========================================================================
-- 蓝图 02-channel-and-shop.md §2.4.2.3 原文：
--
--   **职责**：平台商品已同步但未映射到内部 SKU 的记录，等待人工建立映射或创建新 SKU。
--   **输入**：`listing_variant` 无映射的记录；`listing_mapping_version` 的错误状态。
--   **输出**：待映射清单（平台商品、变体、店铺、价格）；操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。
--   **关系**：上游 = 04 刊登与内容、09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。
--             **不按名称猜 SKU**；映射建立后回填受影响订单，历史订单冻结当时映射版本。
--
-- ---------------------------------------------------------------------------
-- 一、三条必须守住的边界（本迁移逐条落点）
-- ---------------------------------------------------------------------------
--
-- 🔴 边界一：**不按名称猜 SKU**。
--    本表是**纯待办队列**：它只把「哪个平台变体还没有内部 SKU」摆出来等人决策，
--    **不存任何"疑似匹配"字段**（没有 suggested_sku_id、没有相似度分数、没有候选列表）。
--    4.1.2.1 的 `listing_mapping_candidate` 已经承担了「推荐但需人工确认」的角色，
--    本表若再存一份建议就等于**制造第二个匹配事实来源** —— 而蓝图这句话禁的正是这个。
--    ⇒ 人工只能通过「映射到已有 SKU」明确给出 internal_sku_id，或走「新建 SKU」交给 03。
--
-- 🔴 边界二：**历史订单冻结当时映射版本**。
--    映射建立后要能回填受影响订单，但历史订单保留**当时**生效的映射版本。
--    本模块的落点有三层：
--      1. `listing_mapping_gap.mapping_version_id` 记的是**当时解决这个缺口的那一版**；
--      2. `guard_listing_mapping_gap_backfill_frozen` 触发器：回填目标**一旦写下就不可改写**
--         （改一个已回填的缺口去指向别的版本 = 改写历史，DB 层直接拒）；
--      3. 09 订单侧的回填**尚未接线**（见第四节），所以本表**不记任何订单条数** ——
--         记一个 0 会让人以为"没有订单受影响"，而真相是"订单还没接进来"。
--    版本本身的历史冻结由 V176 的 `guard_listing_mapping_version_frozen` 负责（已关闭的版本行
--    不可改写、不可删除），本迁移不重复实现，只引用。
--
-- 🔴 边界三：**跨服务**。
--    「09 订单（缺映射订单也进此队列）」的上游在 **trade 服务**。AGENTS.md 禁止跨服务 SQL /
--    跨库 FK / 共享视图 ⇒ 订单来源**只能经 event / 授权 API** 进入本表。
--    **当前 trade 侧没有推这条事件**，所以：
--      * 本表已经建好 `source='ORDER'` 的**接入点**（`order_ref` 列 + 事件入口端点）；
--      * 但本批次的**已接入来源只有**平台商品同步（`source='LISTING_SYNC'`）——
--        即 `listing_variant` 无映射 + `listing_mapping_version` 的解除态；
--      * 能力声明里 `order_source_wired=false` + 归属模块 + 影响，**不假装已接**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 价格维度：本服务没有「平台 listing 售价」事实
-- ---------------------------------------------------------------------------
-- V171 第二节已经论证过一遍：卷 05 定价整卷未交付，全库不存在任何"平台 listing 售价"事实表
-- （`supplier_quote_price_tier` 是 06 采购的报价、`b2b_price_book` 是 trade 的 B2B 协议价，
-- 都**不是** listing 售价）。所以：
--
--   * `listing` 上**没有** price 列，本迁移**也不给 listing 加**（那会制造第二个价格事实来源）；
--   * 本表带 `price numeric(19,4)` + `currency char(3)`，语义是
--     **「入队那一刻平台报的售价」**——与 05 定价的「系统价」是**两个事实**，
--     本列不参与定价、不回写、也不覆盖 05 的数字；
--   * 该列的值只可能来自**携带平台价的事件载荷**（订单来源）；平台商品来源恒为 NULL，
--     能力声明里 `priceAvailable=false` + 归属 05 定价 + 影响，**不显示 0、不显示假价格**。
--
-- ---------------------------------------------------------------------------
-- 三、表设计
-- ---------------------------------------------------------------------------
--
-- listing_mapping_gap · 待映射清单（一行 = 一个待人工决策的缺口）
--   状态机（DB 层 CHECK 钉死）：
--     PENDING  ── 映射到已有 SKU ──▶ MAPPED    （离队：已建立映射并回填到那一版）
--     PENDING  ── 标记忽略     ──▶ IGNORED   （离队：人工判定不用映射）
--     PENDING  ── 新建 SKU     ──▶ PENDING    （**不离队**：等 03 建档，仍在队列里）
--
-- listing_mapping_gap_sku_request · 「新建 SKU」的请求登记
--   channel 只**登记意图**，不代 03 建档：03 的建档需要 SPU 归属 + 编码规则 + 属性值，
--   本服务一个都没有（catalog 的 POST /catalog/products/{id}/skus 要 productId + code + name +
--   barcode + baseUnit + 属性值），所以派发是**待接线**而不是"假装建好了"。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- listing_mapping_gap · 待映射清单
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping_gap (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  listing_id          uuid NOT NULL,
  listing_variant_id  uuid NOT NULL,
  -- 来源：LISTING_SYNC = 平台商品同步进来（本地来源，已接入）；
  --       ORDER       = 09 订单缺映射（**经事件接入，当前 trade 侧未推**，见第一节边界三）
  source              text NOT NULL DEFAULT 'LISTING_SYNC'
                      CHECK(source IN ('LISTING_SYNC','ORDER')),
  -- 入队原因：
  --   VARIANT_UNMAPPED    = 平台变体从未建立映射（listing_variant 无映射）
  --   MAPPING_RELEASED    = 映射曾建立后被解除 —— 这就是「listing_mapping_version 的错误状态」
  --                         在本模块的落点：当前生效版本是解除版（mapping_status='UNMAPPED'），
  --                         变体因此退回未映射，需要**重建**而不是首次建立
  --   ORDER_UNMAPPED      = 订单来源缺映射（事件接入）
  gap_reason          text NOT NULL DEFAULT 'VARIANT_UNMAPPED'
                      CHECK(gap_reason IN ('VARIANT_UNMAPPED','MAPPING_RELEASED','ORDER_UNMAPPED')),
  -- 队列状态：PENDING 在队 / MAPPED 已建映射（离队）/ IGNORED 人工忽略（离队）
  status              text NOT NULL DEFAULT 'PENDING'
                      CHECK(status IN ('PENDING','MAPPED','IGNORED')),
  -- 人工决策：NONE 未决策 / MAPPED 映射到已有 SKU / CREATE_SKU 新建 SKU / IGNORED 标记忽略
  decision            text NOT NULL DEFAULT 'NONE'
                      CHECK(decision IN ('NONE','MAPPED','CREATE_SKU','IGNORED')),
  -- 🔴 平台侧身份快照（入队那一刻冻结，与 V182 listing_mapping_version_scope 同一手法）：
  --    变体后来改名 / 换店，队列里这一行仍能说清"当时它叫什么"
  shop_code             varchar(64)  NOT NULL DEFAULT '',
  external_item_id      varchar(128) NOT NULL DEFAULT '',
  title_snapshot        varchar(500) NOT NULL DEFAULT '',
  external_variant_id   varchar(128) NOT NULL DEFAULT '',
  attributes_snapshot   jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 平台侧售价快照（numeric(19,4)，**禁浮点**：服务层一律 BigDecimal）
  --    语义 = 入队那一刻**平台报的**售价，与 05 定价的「系统价」是两个事实。
  --    平台商品来源当前恒为 NULL（channel 侧没有平台售价事实，见第二节）
  price                numeric(19,4),
  currency             char(3),
  -- 决策结果：internal_sku_id 是 **03 商品的逻辑 ID**（跨服务，不建 FK、不复制 catalog 任何一列，
  -- 与 V176 listing_mapping.internal_sku_id 同一纪律）
  internal_sku_id     uuid,
  -- 回填目标：建立映射时产生的那一版（🔴 一旦写下不可改写，见第四节触发器）
  mapping_id          uuid,
  mapping_version_id  uuid,
  decided_by          uuid,
  decided_at          timestamptz,
  decision_note       varchar(500) NOT NULL DEFAULT '',
  ignore_reason       varchar(500) NOT NULL DEFAULT '',
  -- 回填：映射建立后把**当前**待映射记录接上（🔴 不是回头改写历史订单的映射版本）
  backfilled_at       timestamptz,
  -- 订单来源的外部单号（source='ORDER' 时有值；本地来源恒为空串）
  order_ref           varchar(128) NOT NULL DEFAULT '',
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 队列行的落点：变体 + 来源
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_variant_id) REFERENCES listing_variant(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 价格与币种同生共死：给了价没有币种（或反之）就是自相矛盾的快照
  CHECK((price IS NULL) = (currency IS NULL)),
  CHECK(price IS NULL OR price>=0),
  -- 🔴 已离队的行必须说清是怎么离的；已回填的行必须有回填版本
  CHECK(status<>'MAPPED' OR (decision='MAPPED' AND mapping_version_id IS NOT NULL AND backfilled_at IS NOT NULL)),
  CHECK(status<>'IGNORED' OR (decision='IGNORED' AND btrim(ignore_reason)<>'')),
  -- 订单来源必须有外部单号；本地来源不得有（防止把本地变体伪装成订单缺口）
  CHECK((source='ORDER') = (btrim(order_ref)<>''))
);
COMMENT ON TABLE listing_mapping_gap IS
  '2.4.2.3 映射缺失队列 · 待映射清单。**不按名称猜 SKU**：本表只有缺口与人工决策，没有任何"疑似匹配"。';

-- 🔴 至多一条**在队**记录（同一变体 + 同一来源）。
--   用**部分唯一索引**而不是表级 UNIQUE：映射解除后变体会重新入队，
--   那时**旧行必须留作历史**（它记着"当时用的是哪一版"），新行才是当前待办。
CREATE UNIQUE INDEX listing_mapping_gap_pending
  ON listing_mapping_gap(tenant_id, listing_variant_id, source) WHERE status='PENDING';

-- 查询面的索引
CREATE INDEX listing_mapping_gap_tenant_status  ON listing_mapping_gap(tenant_id, status, created_at DESC, id);
CREATE INDEX listing_mapping_gap_variant         ON listing_mapping_gap(tenant_id, listing_variant_id);
CREATE INDEX listing_mapping_gap_listing         ON listing_mapping_gap(tenant_id, listing_id);
CREATE INDEX listing_mapping_gap_shop            ON listing_mapping_gap(tenant_id, shop_code);
CREATE INDEX listing_mapping_gap_source          ON listing_mapping_gap(tenant_id, source, status);
CREATE INDEX listing_mapping_gap_price           ON listing_mapping_gap(tenant_id, price) WHERE price IS NOT NULL;
CREATE INDEX listing_mapping_gap_version         ON listing_mapping_gap(tenant_id, mapping_version_id);
CREATE INDEX listing_mapping_gap_order_ref       ON listing_mapping_gap(tenant_id, order_ref) WHERE btrim(order_ref)<>'';

-- ---------------------------------------------------------------------------
-- listing_mapping_gap_sku_request · 「新建 SKU」的请求登记（待 03 接线）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping_gap_sku_request (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  gap_id              uuid NOT NULL,
  -- 03 建档所需字段：channel **只登记意图**，不代 03 建档
  requested_code      varchar(64)  NOT NULL DEFAULT '',
  requested_name      varchar(200) NOT NULL DEFAULT '',
  requested_barcode   varchar(128) NOT NULL DEFAULT '',
  requested_base_unit varchar(32)  NOT NULL DEFAULT '',
  -- 派发状态：PENDING = 已登记，等 03 侧接线后派发
  --           DISPATCHED = 已派发（03 建了档，created_sku_id 有值）
  --           FAILED = 派发失败（last_error 有值）/ CANCELLED = 人工撤单
  status              text NOT NULL DEFAULT 'PENDING'
                      CHECK(status IN ('PENDING','DISPATCHED','FAILED','CANCELLED')),
  dispatched_at       timestamptz,
  -- 03 建出来的 SKU（03 的逻辑 ID，跨服务不建 FK）
  created_sku_id      uuid,
  last_error          varchar(500) NOT NULL DEFAULT '',
  actor_id            uuid,
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, gap_id) REFERENCES listing_mapping_gap(tenant_id, id) ON DELETE CASCADE,
  -- 已派发必须有派发时刻与建出来的 SKU；失败必须有原因
  CHECK(status<>'DISPATCHED' OR (dispatched_at IS NOT NULL AND created_sku_id IS NOT NULL)),
  CHECK(status<>'FAILED' OR btrim(last_error)<>'')
);
COMMENT ON TABLE listing_mapping_gap_sku_request IS
  '2.4.2.3 · 「新建 SKU」的请求登记。**当前 03 侧无接线**：本表只登记意图，派发是待接线而不是"假装建好了"。';

-- 至多一条**未结**的建档请求（同一缺口同一时刻）
CREATE UNIQUE INDEX listing_mapping_gap_sku_request_active
  ON listing_mapping_gap_sku_request(tenant_id, gap_id) WHERE status IN ('PENDING','DISPATCHED');
CREATE INDEX listing_mapping_gap_sku_request_gap ON listing_mapping_gap_sku_request(tenant_id, gap_id, created_at DESC);

-- ===========================================================================
-- 触发器：队列由 DB 维护，服务层漏不掉
-- ===========================================================================
-- 纪律照抄 V176 的现状位投影：映射状态一变，变体上的现状立刻跟着变。
-- 队列同理 —— 变体一进未映射就入队，一建立映射就出队，
-- **不靠服务层记得去同步**（那会出现「映射已建立但队列还挂着」的极难发现的不一致）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 触发器一：变体进入未映射 ⇒ 入队
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enqueue_listing_mapping_gap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_shop_code varchar(64);
  v_item       varchar(128);
  v_title      varchar(500);
BEGIN
  IF NEW.mapping_status <> 'UNMAPPED' THEN
    RETURN NEW;
  END IF;

  SELECT s.code, l.external_item_id, l.title
    INTO v_shop_code, v_item, v_title
    FROM listing l
    JOIN shop s ON s.tenant_id=l.tenant_id AND s.id=l.shop_id
   WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.listing_id;

  INSERT INTO listing_mapping_gap (
    id, tenant_id, listing_id, listing_variant_id, source, gap_reason,
    shop_code, external_item_id, title_snapshot, external_variant_id, attributes_snapshot)
  VALUES (
    gen_random_uuid(), NEW.tenant_id, NEW.listing_id, NEW.id, 'LISTING_SYNC',
    CASE WHEN EXISTS (SELECT 1 FROM listing_mapping m
                       WHERE m.tenant_id=NEW.tenant_id AND m.listing_variant_id=NEW.id)
         THEN 'MAPPING_RELEASED' ELSE 'VARIANT_UNMAPPED' END,
    coalesce(v_shop_code,''), coalesce(v_item,''), coalesce(v_title,''),
    NEW.external_variant_id, NEW.attributes)
  ON CONFLICT (tenant_id, listing_variant_id, source) WHERE status='PENDING' DO NOTHING;

  RETURN NEW;
END $$;

CREATE TRIGGER listing_variant_enqueue_gap
  AFTER INSERT OR UPDATE OF mapping_status ON listing_variant
  FOR EACH ROW EXECUTE FUNCTION enqueue_listing_mapping_gap();

-- ---------------------------------------------------------------------------
-- 触发器二：映射版本建立 ⇒ 缺口被回填到**那一版**
-- ---------------------------------------------------------------------------
-- 🔴 只认**真正建立了映射**的版本（mapping_status<>'UNMAPPED'）。
--    解除映射时也会追加一版（mapping_status='UNMAPPED'），那不是"缺口被解决"，
--    恰恰相反 —— 变体退回未映射，要重新入队（触发器一负责）。
CREATE OR REPLACE FUNCTION resolve_listing_mapping_gap_on_version() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_variant_id uuid;
BEGIN
  IF NEW.mapping_status = 'UNMAPPED' THEN
    RETURN NEW;
  END IF;

  SELECT m.listing_variant_id INTO v_variant_id
    FROM listing_mapping m
   WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.mapping_id;
  IF v_variant_id IS NULL THEN
    RETURN NEW;
  END IF;

  UPDATE listing_mapping_gap g
     SET status='MAPPED',
         decision='MAPPED',
         internal_sku_id=NEW.internal_sku_id,
         mapping_id=NEW.mapping_id,
         mapping_version_id=NEW.id,
         decided_by=coalesce(NEW.actor_id,g.decided_by),
         decided_at=now(),
         backfilled_at=now(),
         version=version+1,
         updated_at=now()
   WHERE g.tenant_id=NEW.tenant_id
     AND g.listing_variant_id=v_variant_id
     AND g.status='PENDING';

  RETURN NEW;
END $$;

CREATE TRIGGER listing_mapping_gap_resolve_on_version
  AFTER INSERT ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION resolve_listing_mapping_gap_on_version();

-- ---------------------------------------------------------------------------
-- 触发器三：🔴 回填目标冻结（边界二的物理落点）
-- ---------------------------------------------------------------------------
-- 「历史订单冻结当时映射版本」的队列侧落点：
-- 一个缺口**被回填到哪一版**是历史事实，不许事后改写。
-- 改一个已回填的缺口去指向别的版本 = 改写历史 —— DB 层直接拒。
CREATE OR REPLACE FUNCTION guard_listing_mapping_gap_backfill_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.mapping_version_id IS NOT NULL
     AND (NEW.mapping_version_id IS DISTINCT FROM OLD.mapping_version_id
          OR NEW.backfilled_at IS DISTINCT FROM OLD.backfilled_at) THEN
    RAISE EXCEPTION
      'A resolved mapping gap is history: its backfilled mapping version must not be rewritten'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER listing_mapping_gap_backfill_frozen
  BEFORE UPDATE ON listing_mapping_gap
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_gap_backfill_frozen();

-- ===========================================================================
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 V171 / V176 / V182 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_mapping_gap','listing_mapping_gap_sku_request'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围：队列的可见性随 listing 的负责人 / 部门收敛（与 V182 的版本策略同构）
CREATE POLICY data_scope ON listing_mapping_gap AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_gap.tenant_id AND l.id=listing_mapping_gap.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_gap.tenant_id AND l.id=listing_mapping_gap.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- 建档请求随缺口一起收敛
CREATE POLICY data_scope ON listing_mapping_gap_sku_request AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_mapping_gap g
             WHERE g.tenant_id=listing_mapping_gap_sku_request.tenant_id AND g.id=listing_mapping_gap_sku_request.gap_id
             AND channel_scope(channel_operation(),
                  (SELECT l.owner_id FROM listing l WHERE l.tenant_id=g.tenant_id AND l.id=g.listing_id),
                  (SELECT l.department_id FROM listing l WHERE l.tenant_id=g.tenant_id AND l.id=g.listing_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_mapping_gap g
             WHERE g.tenant_id=listing_mapping_gap_sku_request.tenant_id AND g.id=listing_mapping_gap_sku_request.gap_id
             AND channel_scope(channel_operation(),
                  (SELECT l.owner_id FROM listing l WHERE l.tenant_id=g.tenant_id AND l.id=g.listing_id),
                  (SELECT l.department_id FROM listing l WHERE l.tenant_id=g.tenant_id AND l.id=g.listing_id))));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 channel 既有迁移的条件建角色写法）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_mapping_gap TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_mapping_gap_sku_request TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V171 / V176 / V182 同口径）。
REVOKE ALL ON listing_mapping_gap FROM starcon_app;
REVOKE ALL ON listing_mapping_gap_sku_request FROM starcon_app;

-- ===========================================================================
-- 存量回填：V191 之前就已同步进来、且仍然没有映射的变体
-- ===========================================================================
-- 🔴 触发器只覆盖 V191 之后的新写入；升级前就在库里的变体需要补进队列。
--   逐租户 set_config 再插：迁移账号未必是超级用户，FORCE RLS 下
--   **不带 app.current_tenant 的跨租户 INSERT 会被静默插成 0 行** ——
--   那会让"升级后队列是空的"看起来像"没有缺口"，是最坏的一种静默失败。
DO $$
DECLARE
  v_tenant  uuid;
  v_grants  text := '[{"permissions":["channel.operation.read","channel.operation.create",'
                     || '"channel.operation.update","channel.operation.delete"],"scopeMode":"ALL"}]';
BEGIN
  FOR v_tenant IN
    SELECT DISTINCT v.tenant_id
      FROM listing_variant v
     WHERE v.mapping_status='UNMAPPED'
  LOOP
    PERFORM set_config('app.current_tenant', v_tenant::text, true);
    PERFORM set_config('app.actor', '', true);
    PERFORM set_config('app.department', '', true);
    PERFORM set_config('app.permission', 'channel.operation.create', true);
    PERFORM set_config('app.grants', v_grants, true);

    INSERT INTO listing_mapping_gap (
      id, tenant_id, listing_id, listing_variant_id, source, gap_reason,
      shop_code, external_item_id, title_snapshot, external_variant_id, attributes_snapshot)
    SELECT gen_random_uuid(), v.tenant_id, v.listing_id, v.id, 'LISTING_SYNC',
           CASE WHEN EXISTS (SELECT 1 FROM listing_mapping m
                              WHERE m.tenant_id=v.tenant_id AND m.listing_variant_id=v.id)
                THEN 'MAPPING_RELEASED' ELSE 'VARIANT_UNMAPPED' END,
           coalesce(s.code,''), coalesce(l.external_item_id,''), coalesce(l.title,''),
           v.external_variant_id, v.attributes
      FROM listing_variant v
      JOIN listing l ON l.tenant_id=v.tenant_id AND l.id=v.listing_id
      LEFT JOIN shop  s ON s.tenant_id=l.tenant_id AND s.id=l.shop_id
     WHERE v.tenant_id=v_tenant AND v.mapping_status='UNMAPPED'
    ON CONFLICT (tenant_id, listing_variant_id, source) WHERE status='PENDING' DO NOTHING;
  END LOOP;
END $$;
