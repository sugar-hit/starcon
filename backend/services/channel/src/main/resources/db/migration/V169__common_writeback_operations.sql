-- ===========================================================================
-- 2.5.2.1 常用回写操作
-- ===========================================================================
-- 蓝图原文（02-channel-and-shop.md）：
--   职责：提供高频回写的快捷入口与批量操作（批量改库存、批量改价、批量下架、
--         批量回传物流），支持按店铺与筛选条件批量执行。
--   输入：用户勾选的记录范围；操作参数（库存数量/价格/物流单号）。
--   输出：生成 N 条独立回写任务，逐条记录结果，部分失败不影响其余。
--   关系：上游 = 04、07、09、10；下游 = 2.5.1.1。**批量操作不等于事务**：
--         每条独立成败、部分失败要如实回报，不能因一条失败回滚全部
--         （跨服务无全局事务）。
--
-- ---------------------------------------------------------------------------
-- 一、硬依赖只有一张：platform_operation（2.5.1.1 已交付，V2 建）
-- ---------------------------------------------------------------------------
-- 「上游 = 04、07、09、10」说的是**哪些业务事件会触发回写**（改了刊登要回写、
-- 改了库存要回写、发了货要回传物流），它们是本模块**消费的上游事件类型**，
-- 不是要去 JOIN 的表。04 整卷尚未开发，但本模块不需要它的任何一张表：
-- 真正落地时只有一件事要做 —— **往 platform_operation 写 N 行**。
--
-- 因此本迁移**零 ALTER 既有表**：
--   * 不改 platform_operation 的任何列、CHECK 或索引（它的 operation 取值域
--     是 2.5.1.1 的对外契约，扩它等于替别的车道改契约）；
--   * 「批量下架」因此映射到 operation='PUBLISH' + payload 里 delist=true，
--     而不是给 CHECK 塞第四个值 —— 理由见第四节。
-- ⇒ 跨车道 DDL 回归面 = 0，2.5.1.1 的既有插入路径一行未动。
--
-- ---------------------------------------------------------------------------
-- 二、writeback_target：为什么本模块需要一张「可回写目标」登记簿
-- ---------------------------------------------------------------------------
-- 蓝图输入句是「**用户勾选的记录范围**」+「按**店铺与筛选条件**批量执行」。
-- 要按筛选条件挑出候选集，就先得有一个**属于本服务**的候选集。
--
-- channel 库里现有的表没有一张是这个粒度：platform/marketplace 是目录，
-- shop 是店铺，sync_* 与 platform_operation 是**已经产生的回写任务**，
-- 都不是「可以被回写的对象」。而真正的对象（刊登、商品、物流单）分属 04 /
-- 07 / 09 / 10 各自的库，AGENTS.md 禁止跨服务 SQL / 跨库视图 / 跨库 FK，
-- 所以本服务**查不到**它们，也不该猜。
--
-- ⇒ 本表就是 channel 自己拥有的那份投影：上游服务把「这个店铺下的这个对象
--    可以被回写」登记进来，运营也可以在工作区直接登记与封禁。它与 9.4.2.1
--    （trade V147）对 shop_id 的处理同一个道理 —— **跨服务的 uuid 由调用方
--    带入，本服务不编造**。
--
-- 表里只存**平台侧业务键**（external_ref / listing_ref / sku_code），不存跨服务
-- 的内部 uuid 外键：业务键是平台与人都认的东西，内部 uuid 换了服务就断。
--
-- ---------------------------------------------------------------------------
-- 三、批量 ≠ 事务：结果落在哪、怎么算
-- ---------------------------------------------------------------------------
-- 蓝图加粗句的落地方式：
--   * 每条被选中的目标**独立**生成一条 platform_operation（PENDING），
--     各自带自己的业务幂等键 ⇒ 平台侧逐条成败，互不影响；
--   * **生成前**就被拦下的那条（目标不存在 / 目标被封禁 / 参数不合法 /
--     币种与目标不符 / 发货目标不是 SHIPMENT）**不生成**平台任务 ——
--     没发出去的东西不该出现在「发往平台的写操作」清单里冒充失败；
--     它记在 writeback_batch_item 上，带真实原因，**照样出现在批次结果里**；
--   * 批次状态**不落库**，每次读都从 platform_operation 的真实状态聚合。
--     落库一个 status 字段就等于多一个能写歪、且会与真实任务状态撒谎的地方。
--
-- ---------------------------------------------------------------------------
-- 四、批量下架为什么用 PUBLISH + payload.delist，而不是扩 CHECK
-- ---------------------------------------------------------------------------
-- platform_operation.operation 的 CHECK（V2）是 2.5.1.1 的对外契约，取值域写死在
-- 既有迁移里。给它加 'DELIST' 需要 ALTER 既有表，而本模块对「下架」的平台动作
-- 表述就是**改刊登的在线状态** —— 与上架同一条刊登写通道，只是 active 取反。
-- 把两者合成一个可区分的 payload（delist=true + active=false）既不碰别人的
-- 契约，又让派发方（未来接 vendor adapter 的一侧）能分辨方向。
--
-- ---------------------------------------------------------------------------
-- 五、幂等键：一个批次一个键、一条任务一个键
-- ---------------------------------------------------------------------------
-- 蓝图 2.5.1.1 强调「每个回写任务必须带业务幂等键，避免重试造成平台侧重复上架
-- 或重复扣减」，本模块是那个约束的**批量入口**，所以两层都要有：
--   * writeback_batch.idempotency_key  UNIQUE(tenant_id, idempotency_key)
--     调用方自拟（= 一次批量动作的业务身份）；没给就由服务端按
--     「kind + 店铺 + 筛选条件 + 参数」算摘要 —— 同样的请求提交两次只生成一批。
--   * writeback_batch_item.operation_id → platform_operation.idempotency_key
--     由「批次键 + 目标 + 参数摘要」导出，**不接受调用方自拟**：调用方自拟就能
--     两次「不同键、同一个目标」，恰好绕开幂等。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量
-- ---------------------------------------------------------------------------
-- 禁浮点：current_stock NUMERIC(14,3)、current_price NUMERIC(18,4)、
-- price NUMERIC(18,4)，服务层一律 BigDecimal。
--
-- ---------------------------------------------------------------------------
-- 七、多租户
-- ---------------------------------------------------------------------------
-- 三张表与 V2 同一套：ENABLE + FORCE RLS，策略读 app.current_tenant（由
-- libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来）。
-- 刻意**不**加 V2 里 sync_cursor 那条 channel_scope RESTRICTIVE 策略：
-- platform_operation 本身没有按负责人/部门收敛数据范围（2.5.1.1 的口径），
-- 批量回写的候选集要跟 2.5.1.1 的可见范围一致，另起一套只会让两页对不上。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 批号发号器：WB-yyyyMMdd-000001
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS writeback_batch_no_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. writeback_target · 可回写目标登记簿
-- ---------------------------------------------------------------------------
CREATE TABLE writeback_target (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,

  -- 2.3 店铺目录（同库 FK）。跨服务不建 FK，故登记方必须带对。
  shop_id        uuid NOT NULL,

  -- LISTING = 平台商品/变体（批量改库存、改价、下架的对象）
  -- SHIPMENT = 平台订单（批量回传物流的对象）
  target_type    varchar(16) NOT NULL
                 CHECK(target_type IN ('LISTING','SHIPMENT')),

  -- 平台侧业务键：平台按它定位，我们按它去重。三者合起来定位一个可写对象。
  external_ref   varchar(128) NOT NULL CHECK(btrim(external_ref)<>''),
  listing_ref    varchar(128) NOT NULL DEFAULT '',
  sku_code       varchar(64)  NOT NULL DEFAULT '',

  -- 当前值快照：批量操作前界面上要显示「从多少改成多少」，没有快照就只能显示
  -- 一个新值，用户无从判断这次批量会不会把库存改崩。
  current_stock  numeric(14,3),
  current_price  numeric(18,4),
  -- varchar(3) 而不是 char(3)：后者是定长补空格，比较与正则都要先去尾随空格，
  -- 那种坑在币种这种「错了就错一个字符」的字段上代价最高。
  currency       varchar(3),

  -- BLOCKED = 已被封禁，不允许发起回写（平台拒收、店铺停售、合规下架）。
  -- 批量时命中它的那一条单独失败并写明原因，其余照常执行 —— 这正是
  -- 「部分失败不影响其余」最常见的真实来源。
  status         varchar(16) NOT NULL DEFAULT 'ACTIVE'
                 CHECK(status IN ('ACTIVE','BLOCKED')),

  blocked_reason varchar(400) NOT NULL DEFAULT '',
  source_ref     varchar(64)  NOT NULL DEFAULT '',
  version        bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一店铺同一类对象只登记一次：重复推送同一条上游事件不会造出两个目标。
  UNIQUE(tenant_id,shop_id,target_type,external_ref),
  FOREIGN KEY(tenant_id,shop_id) REFERENCES shop(tenant_id,id),

  -- 库存与价格必须成对合法：只有价格没有币种，改价就是个没有单位的数字。
  CONSTRAINT writeback_target_price_needs_currency
    CHECK(current_price IS NULL OR (currency IS NOT NULL AND btrim(currency) ~ '^[A-Z]{3}$')),
  CONSTRAINT writeback_target_stock_currency_pair
    CHECK(current_stock IS NULL OR currency IS NULL OR btrim(currency) ~ '^[A-Z]{3}$'),
  CONSTRAINT writeback_target_values_positive
    CHECK((current_stock IS NULL OR current_stock >= 0)
          AND (current_price IS NULL OR current_price > 0)),
  CONSTRAINT writeback_target_blocked_needs_reason
    CHECK(status <> 'BLOCKED' OR btrim(blocked_reason) <> ''),
  CONSTRAINT writeback_target_shipment_has_no_price
    CHECK(target_type <> 'SHIPMENT' OR (current_stock IS NULL AND current_price IS NULL))
);

CREATE INDEX writeback_target_shop
  ON writeback_target(tenant_id,shop_id,target_type,status);
-- 筛选条件里的关键词与 SKU 检索走这一条（external_ref 命中前缀、sku_code 命中包含）。
CREATE INDEX writeback_target_lookup
  ON writeback_target(tenant_id,lower(external_ref) text_pattern_ops,
                     lower(sku_code) text_pattern_ops);
-- 批量候选集的默认排序（按外部键稳定分页）。
CREATE INDEX writeback_target_order
  ON writeback_target(tenant_id,shop_id,external_ref);

-- ---------------------------------------------------------------------------
-- 2. writeback_batch · 一次批量动作的头
-- ---------------------------------------------------------------------------
CREATE TABLE writeback_batch (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  batch_no       varchar(32) NOT NULL,

  -- UPDATE_STOCK 批量改库存 / UPDATE_PRICE 批量改价 /
  -- DELIST 批量下架（映射为 platform_operation.operation='PUBLISH'）/
  -- SHIP_UPDATE 批量回传物流。
  kind           varchar(16) NOT NULL
                 CHECK(kind IN ('UPDATE_STOCK','UPDATE_PRICE','DELIST','SHIP_UPDATE')),

  -- NULL = 按筛选条件跨店铺批量；非空 = 单店铺批量。
  shop_id        uuid,
  -- 冻结的筛选条件与参数：事后复盘「当时到底选了什么、报了什么」靠这两列，
  -- 不回头去猜。
  filter_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  param_snapshot  jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 业务幂等键（见文件头第五节）。命中即复用既有批次：不产生第二批任务。
  idempotency_key varchar(160) NOT NULL CHECK(btrim(idempotency_key)<>''),

  -- 目标条数与被拒条数在**读**时从 writeback_batch_item 聚合，落库会与真实
  -- 逐条结果撒谎；这里只记规模，用于「这次点了多少条」的复盘。
  selected_count int NOT NULL DEFAULT 0 CHECK(selected_count >= 0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count >= 0),

  created_by_membership_id uuid,
  reason         varchar(400) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,batch_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,shop_id) REFERENCES shop(tenant_id,id),
  -- 被拒条数不可能超过选中条数：那是同一批的两半，不是两批。
  CONSTRAINT writeback_batch_rejected_within_selected
    CHECK(rejected_count <= selected_count)
);

CREATE INDEX writeback_batch_created
  ON writeback_batch(tenant_id,created_at desc);
CREATE INDEX writeback_batch_kind
  ON writeback_batch(tenant_id,kind,created_at desc);
-- 「这个店铺最近批量做了什么」——平台申诉时最常按店铺查。
CREATE INDEX writeback_batch_shop
  ON writeback_batch(tenant_id,shop_id,created_at desc);

-- ---------------------------------------------------------------------------
-- 3. writeback_batch_item · 逐条结果（一行 = 批次里的一条）
-- ---------------------------------------------------------------------------
CREATE TABLE writeback_batch_item (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  batch_id       uuid NOT NULL,
  position       int NOT NULL CHECK(position >= 1),

  -- 目标可能不存在（勾选的是一个已删的目标），故 target_id 允许为空：
  -- 这种情况**照样要有一行**，否则「选了 5 条、只回报 4 条」比失败更糟。
  target_id      uuid,
  -- external_ref / target_type / shop_id 同理：一律允许为空。**编一个出来比留空更糟** ——
  -- 编出来的店铺会让「这条回写发给了谁」变得无法复盘，而那正是平台申诉要的证据。
  external_ref   varchar(128),
  shop_id        uuid,
  target_type    varchar(16) CHECK(target_type IN ('LISTING','SHIPMENT')),

  -- QUEUED  = 已生成 platform_operation（PENDING），等平台回执
  -- REJECTED = 生成前被拦下（没发出去），reason 必须写明
  item_result    varchar(16) NOT NULL CHECK(item_result IN ('QUEUED','REJECTED')),

  operation_id   uuid,
  -- 任务状态快照（QUEUED 时从 platform_operation 读）。落库一份是给 2.5.1.1
  -- 那张任务清单做引用，**不是**结果的真相 —— 批次视图一律现查。
  operation_status varchar(16) NOT NULL DEFAULT '',
  reason         varchar(500) NOT NULL DEFAULT '',

  -- 发货时刻（仅 SHIP_UPDATE）：冻结「当时报给平台的时间」。
  -- timestamptz，绑定必须写 ?::timestamptz + OffsetDateTime。
  shipped_at     timestamptz,

  created_at     timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一批次里同一目标只出现一次：两条同目标的回写 = 平台上被改两次。
  UNIQUE(tenant_id,batch_id,target_id),
  -- 批次内的序号唯一：逐条回报要有稳定顺序，重读不能换位。
  UNIQUE(tenant_id,batch_id,position),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES writeback_batch(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,target_id) REFERENCES writeback_target(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id) REFERENCES platform_operation(tenant_id,id),

  -- 「没发出去」必须写明为什么：光一个 REJECTED 没人知道该改什么。
  CONSTRAINT writeback_batch_item_rejected_needs_reason
    CHECK(item_result <> 'REJECTED' OR btrim(reason) <> ''),
  -- 「已排队」必须有平台任务：没有任务的一行等于凭空多了一条回写。
  CONSTRAINT writeback_batch_item_queued_needs_operation
    CHECK(item_result <> 'QUEUED' OR operation_id IS NOT NULL),
  -- 被拒的那条不得凭空挂一个平台任务：没发出去就不该在清单里冒充失败。
  CONSTRAINT writeback_batch_item_rejected_has_no_operation
    CHECK(item_result <> 'REJECTED' OR operation_id IS NULL),
  -- 已排队的必须有目标：没有目标的那条不知道往哪发。
  CONSTRAINT writeback_batch_item_queued_needs_target
    CHECK(item_result <> 'QUEUED' OR (target_id IS NOT NULL AND shop_id IS NOT NULL
                                     AND target_type IS NOT NULL)),
  -- 发货时刻只对物流回传有意义。
  CONSTRAINT writeback_batch_item_shipped_at_only_for_shipment
    CHECK(shipped_at IS NULL OR target_type = 'SHIPMENT')
);

CREATE INDEX writeback_batch_item_batch
  ON writeback_batch_item(tenant_id,batch_id,position);
CREATE INDEX writeback_batch_item_target
  ON writeback_batch_item(tenant_id,target_id);
CREATE INDEX writeback_batch_item_operation
  ON writeback_batch_item(tenant_id,operation_id);

-- ---------------------------------------------------------------------------
-- 4. 多租户隔离（与 V2 第 129 行同一套写法）
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['writeback_target','writeback_batch','writeback_batch_item'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 5. 授权
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON writeback_target, writeback_batch, writeback_batch_item TO channel_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V2 同口径）。
REVOKE ALL ON writeback_target, writeback_batch, writeback_batch_item FROM starcon_app;
-- 单号发号器必须显式授权：GRANT ... ON TABLE 不覆盖 SEQUENCE。
GRANT USAGE, SELECT ON SEQUENCE writeback_batch_no_seq TO channel_app;
