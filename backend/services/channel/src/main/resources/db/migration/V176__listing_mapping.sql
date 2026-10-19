-- ===========================================================================
-- 4.1.2.1 映射管理（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.1.2.1 映射管理
--   - 职责：建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；
--           支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。
--   - 输入：映射建立操作；匹配规则；批量候选。
--   - 输出：`listing_mapping_version` 记录（当前生效版本 + 历史版本）；未映射清单回流 02 映射缺失队列。
--   - 关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单（订单行按映射解析 SKU）、
--           07 库存（回写可售量）。**映射未建立时订单进入待映射状态，绝不按名称猜 SKU**（实体目录 §6）。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 全部**纯新建、零 ALTER 任何既有表**（与 V171 / V169 / V170 同一纪律）：
--   * V171 `listing`（平台商品，UNIQUE(tenant_id,id)）与 V171 `listing_variant`
--     （平台变体，UNIQUE(tenant_id,id)）—— 复合 FK 带 tenant_id ⇒ 跨租户挂载物理上不可能。
--     `listing_variant.mapping_status` / `internal_sku_id` / `component_quantity`
--     是 4.1.1.1 建下的**现状位**，本迁移是它们的**写入方**（见第五节触发器 (e)）。
--   * V1 `shop`（经 listing.shop_id 间接决定数据范围；不直接引用）。
--   * 03 商品的 `sku` / `bundle_sku`（catalog V1 / V24）**跨服务**：这里只存逻辑 ID
--     `internal_sku_id`，**不建 FK、不复制 catalog 的任何一列**（与 V171、V1
--     `shop.legal_entity_id` 同一纪律）。存在性与租户归属由 owning service 的授权 API 确认。
--
-- 表名走 `listing_mapping` 命名空间，与 V171 的 `listing` / `listing_variant` /
-- `listing_status_event` / `listing_batch_task` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 归属 channel 而不是 catalog 的理由
-- ---------------------------------------------------------------------------
-- 与 V171 同一个物理理由：映射的可见性必须**随 listing 的负责人 / 部门收敛**
-- （listing 的 owner_id 是 V171 建下的归属快照，shop 的 data_scope 策略读 app.grants）。
-- 放 catalog 就得为了这条数据范围去读 channel 的表 —— 那是跨服务 SQL。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图加粗句「映射未建立时订单进入待映射状态，绝不按名称猜 SKU」的物理落点
-- ---------------------------------------------------------------------------
-- 这句话最容易被做丢的地方是"给个兜底匹配"。本迁移把它钉成**四个不可绕过的物理约束**：
--   ① `listing_mapping` 的行一旦存在就是「已建立的映射」：ACTIVE ⇔ 非 UNMAPPED 的
--      双向 CHECK。**不存在「已建映射但还没填 SKU」这种行**（那条状态由"没有这张表的行"
--      表达，也就是 4.1.1.1 的 UNMAPPED）。
--   ② 批量候选里的 `TITLE_SIMILARITY` 规则**恒为 `requires_confirmation=true`**，
--      且候选在人工 CONFIRMED 之前**不写任何 mapping 行**（候选表与映射表物理分离）。
--   ③ `GET /resolve` 在没有生效映射时返回 `UNMAPPED` + `PENDING_MAPPING`，**不返回任何
--      SKU 猜测**；这个端点是 09 订单行解析 SKU 的权威入口（下游未交付时先立口径）。
--   ④ 候选行落库时把「命中依据原文」`matched_text` 与分数 `score` 一起冻结，
--      事后能回答"当时凭什么推荐的"；匹配本身**永远重查 catalog 授权 API**，
--      候选里的 code/name 只是**展示快照**，不是匹配的数据源（第三节）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 匹配规则的数据来源纪律：候选快照不是匹配的数据源
-- ---------------------------------------------------------------------------
-- 三条规则（SKU 编码匹配 / 条码匹配 / 标题相似度）的候选集**一律来自 catalog 的授权
-- HTTP API**（GET /catalog/skus，跨服务不建 FK / 不跨库 SELECT，与
-- `CarrierProfileQueryPort` 同一手法）。
--
-- `listing_mapping_candidate` 里的 `internal_sku_code` / `internal_sku_name` /
-- `matched_text` / `score` 是**推荐当时的展示快照**，用途只有一个：让"当时推荐了什么、
-- 凭哪句话、几分"可回溯（人工确认是一道要签字的门，签字之后要能复核当时看到的东西）。
-- 它们**不参与任何匹配计算**，代码里没有任何一处拿这两列去做候选筛选。
-- 这样"快照过期"最坏只是显示旧值，**不会让匹配结果随时间漂移**。
--
-- 标题相似度**真算**：pg_trgm 的 `similarity()` 在本库跑（见第九节）。
-- 返回的是 PostgreSQL 自己算出来的实数，不是服务层编的候选。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 `listing_mapping_version` 由本模块建表，4.1.2.2 只接手查询
-- ---------------------------------------------------------------------------
-- 蓝图把「`listing_mapping_version` 记录（当前生效版本 + 历史版本）」写在**本模块**的输出里，
-- 而 4.1.2.2「映射版本」负责「版本列表 + **受影响订单范围**查询」「按版本回溯受影响订单」。
-- 实测：4.1.2.2 **未交付**，09 订单侧也没有任何表能让"受影响订单"有真实来源。
--
-- 所以本迁移把表与「当前生效版本」语义建齐，并把 4.1.2.2 需要的两个字段先留好：
--   * `effective_from` / `effective_to` —— 4.1.2.2 的**生效时间点**（半开区间 [from,to)，
--     to 为 NULL = 当前生效，与 catalog `bundle_version` 的 R1 同口径）
--   * `change_reason`              —— 4.1.2.2 的**变更原因**
--
-- 🔴 **刻意不建**「受影响订单范围」相关的一切：09 订单未交付，建一张空的订单引用表
-- 只会让人以为"按版本回溯订单"已经能用了。`GET /{id}` 返回当前生效版本 + 完整版本历史，
-- 但**不返回任何订单范围**（capabilities 里 available=false + 归属模块）。
--
-- 「同一条映射至多一个当前生效版本」由**部分唯一索引 + 触发器**双重保证：
-- 索引挡并发写入，触发器给出能读懂的原因（直接写 SQL 也绕不过去）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 映射与 `listing_variant` 现状位的同步由 DB 触发器做，不交给服务层
-- ---------------------------------------------------------------------------
-- 4.1.1.1 的列表筛选（mappingStatus = UNMAPPED/PARTIAL/MAPPED）读的是
-- `listing_variant.mapping_status`。如果同步交给服务层，就会出现"映射已建立但列表
-- 仍显示未映射"这种**极难发现**的数据不一致（列表看起来是好的，只是筛选不出来）。
--
-- 所以 `AFTER INSERT OR UPDATE ON listing_mapping` 的触发器**就地**把三列投影过去，
-- 并遵守 V171 `guard_listing_variant_mapping` 的既有不变量（MAPPED/BUNDLE 必须有
-- internal_sku_id；BUNDLE 必须有 component_quantity；非 BUNDLE 不得有 component_quantity）。
-- 解除映射（status=REMOVED）同样由触发器把变体退回 UNMAPPED。
--
-- ---------------------------------------------------------------------------
-- 六、映射基数三态的物理表达
-- ---------------------------------------------------------------------------
--   ONE_TO_ONE  1 个平台变体 → 1 个内部 SKU。component_quantity 必为 NULL。
--   ONE_TO_MANY 1 个平台变体 → 1 个**内部套装 SKU**（catalog 的 bundle_sku，
--               组件明细与数量归 4.1.2.3 / catalog 3.5.1.x）。component_quantity 必 > 0
--               （平台侧卖 1 件 = 内部扣几套组件）。
--   MANY_TO_ONE N 个平台变体 → 1 个内部 SKU（多尺码多颜色同款）。
--               行形状与 ONE_TO_ONE 完全相同，"多对一"是**反向索引上的事实**：
--               `GET /by-sku/{internalSkuId}` 就是它的读出口，否则"多对一"只是一个
--               声明而没有任何端点能证明。
--
-- 🔴 组件 SKU 逐条明细**不建表**：那是 4.1.2.3「套装组件映射」的职责
-- （「维护组件 SKU 与数量的映射…校验组件 SKU 在目标仓库可售」）。本模块只把
-- 平台变体指向**套装 SKU**并记 component_quantity；组件展开归 catalog 的
-- `bundle_component`。在这里建一张组件表等于抢下一个模块的写入所有权，
-- 而且两边会各有一套真相。
--
-- ---------------------------------------------------------------------------
-- 七、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本表**无任何金额列**（映射不碰钱）。存在的数值列：
--   * `component_quantity numeric(19,6)` —— 数量，AGENTS.md 一律 NUMERIC(19,6)
--   * `score              numeric(19,6)` —— 相似度比率，同口径（不用 real / double）
-- **无 float / double 一列**。
--
-- ---------------------------------------------------------------------------
-- 八、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒
-- （14.1.2.2 实测踩过）。集成测试**断言写回的值逐字相等**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 触发器函数必须在建表之前定义：AFTER 触发器要在表上挂，函数体引用同批的表
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stamp_listing_mapping_version_actor() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    NEW.actor_id := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.actor_id IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- 现状位投影：listing_mapping → listing_variant 的三列（见第五节）
CREATE OR REPLACE FUNCTION project_listing_mapping_to_variant() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE listing_variant
     SET mapping_status    = CASE WHEN NEW.status='ACTIVE' THEN NEW.mapping_status ELSE 'UNMAPPED' END,
         internal_sku_id   = CASE WHEN NEW.status='ACTIVE' THEN NEW.internal_sku_id ELSE NULL END,
         component_quantity= CASE WHEN NEW.status='ACTIVE' AND NEW.relation_type='ONE_TO_MANY'
                                  THEN NEW.component_quantity ELSE NULL END,
         version = version + 1,
         updated_at = now()
   WHERE tenant_id = NEW.tenant_id AND id = NEW.listing_variant_id;
  RETURN NEW;
END $$;

-- 映射行自洽守卫：跨列 + 跨语义，CHECK 表达不完整的部分在这里给出可读原因
CREATE OR REPLACE FUNCTION guard_listing_mapping_consistency() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- 🔴 基数相关的三条只对**生效中**的映射成立。
  --   解除后的行（status='REMOVED'）已经「没有映射」：relation_type 只作为历史记录保留，
  --   仍要如实记着「这曾经是一套套装映射」，所以不能再拿它去推 mapping_status。
  IF NEW.status='ACTIVE' THEN
    -- 一对多（套装）必须带组件数量，普通一对一 / 多对一不许带
    IF NEW.relation_type='ONE_TO_MANY' AND NEW.component_quantity IS NULL THEN
      RAISE EXCEPTION 'ONE_TO_MANY (bundle) mapping requires component_quantity > 0' USING ERRCODE='23514';
    END IF;
    IF NEW.relation_type<>'ONE_TO_MANY' AND NEW.component_quantity IS NOT NULL THEN
      RAISE EXCEPTION 'component_quantity is only meaningful for ONE_TO_MANY mappings' USING ERRCODE='23514';
    END IF;
    IF NEW.relation_type='ONE_TO_MANY' AND NEW.component_quantity<=0 THEN
      RAISE EXCEPTION 'component_quantity must be greater than 0' USING ERRCODE='23514';
    END IF;
    -- 套装映射必须有组件数量（与 V171 guard_listing_variant_mapping 的 BUNDLE 不变量一致）
    IF NEW.relation_type='ONE_TO_MANY' AND NEW.mapping_status<>'BUNDLE' THEN
      RAISE EXCEPTION 'ONE_TO_MANY mapping must report mapping_status BUNDLE' USING ERRCODE='23514';
    END IF;
    IF NEW.relation_type<>'ONE_TO_MANY' AND NEW.mapping_status='BUNDLE' THEN
      RAISE EXCEPTION 'mapping_status BUNDLE is only valid for ONE_TO_MANY mappings' USING ERRCODE='23514';
    END IF;
  END IF;
  -- 已生效的映射必须有内部 SKU；未生效（REMOVED）必须没有
  IF NEW.status='ACTIVE' AND NEW.internal_sku_id IS NULL THEN
    RAISE EXCEPTION 'An active mapping must carry internal_sku_id' USING ERRCODE='23514';
  END IF;
  IF NEW.status='REMOVED' AND NEW.internal_sku_id IS NOT NULL THEN
    RAISE EXCEPTION 'A removed mapping must not carry internal_sku_id' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 当前生效版本：同一条映射至多一个（与部分唯一索引同口径，索引挡并发，这里给可读原因）
CREATE OR REPLACE FUNCTION guard_listing_mapping_current_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.effective_to IS NULL AND EXISTS(
       SELECT 1 FROM listing_mapping_version v
        WHERE v.tenant_id=NEW.tenant_id AND v.mapping_id=NEW.mapping_id AND v.effective_to IS NULL) THEN
    RAISE EXCEPTION 'Mapping already has a current effective version; close it before opening a new one'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 版本历史冻结：已关闭（effective_to 非 NULL）的版本行不可改写。
-- 「改映射不影响已产生的订单」—— 一旦某个时刻的映射被订单行引用过，那一行就得能复现。
CREATE OR REPLACE FUNCTION guard_listing_mapping_version_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION 'A closed mapping version is history and must not be rewritten' USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.mapping_id IS DISTINCT FROM OLD.mapping_id
     OR NEW.version_no IS DISTINCT FROM OLD.version_no OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.internal_sku_id IS DISTINCT FROM OLD.internal_sku_id OR NEW.component_quantity IS DISTINCT FROM OLD.component_quantity
     OR NEW.relation_type IS DISTINCT FROM OLD.relation_type OR NEW.change_source IS DISTINCT FROM OLD.change_source
  THEN RAISE EXCEPTION 'Mapping version identity and payload must not be rewritten in place' USING ERRCODE='23514'; END IF;
  -- 当前版本只允许被"关闭"（effective_to 由 NULL 变成一个时刻），不允许复活
  IF OLD.effective_to IS NULL AND NEW.effective_to IS NOT NULL AND NEW.effective_to<OLD.effective_from THEN
    RAISE EXCEPTION 'effective_to must be strictly greater than effective_from' USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NULL AND NEW.effective_to IS NULL
     AND (NEW.effective_from IS DISTINCT FROM OLD.effective_from OR NEW.change_reason IS DISTINCT FROM OLD.change_reason) THEN
    RAISE EXCEPTION 'A current mapping version can only be closed, never re-dated' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 已关闭的版本连删都不许（否则「当时的映射」会被删掉，订单追溯断了）
CREATE OR REPLACE FUNCTION refuse_listing_mapping_version_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION 'A closed mapping version is history and must not be deleted' USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

-- ---------------------------------------------------------------------------
-- listing_mapping · 平台变体 → 内部 SKU/套装的映射（当前态）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  listing_id          uuid NOT NULL,
  listing_variant_id  uuid NOT NULL,
  -- 映射基数三态（见第六节）
  relation_type       text NOT NULL
                      CHECK(relation_type IN ('ONE_TO_ONE','ONE_TO_MANY','MANY_TO_ONE')),
  -- ACTIVE = 映射存在且生效；REMOVED = 已解除（历史仍在 listing_mapping_version 里）
  status              text NOT NULL DEFAULT 'ACTIVE'
                      CHECK(status IN ('ACTIVE','REMOVED')),
  -- 与 listing_variant.mapping_status 同值域，由触发器 (e) 投影过去
  mapping_status      text NOT NULL
                      CHECK(mapping_status IN ('UNMAPPED','MAPPED','BUNDLE')),
  -- 🔴 03 商品的逻辑 ID：跨服务，不建 FK、不复制 catalog 任何一列
  internal_sku_id     uuid,
  component_quantity  numeric(19,6),
  -- 当前生效版本（4.1.2.2 的查询端点未交付前，本模块自己维护这一条指针）
  current_version_id  uuid,
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 「已建立的映射」必须有内部 SKU；「没有映射」由不存在行表达，不是由一行空 SKU 表达
  CHECK((status='ACTIVE') = (mapping_status<>'UNMAPPED')),
  CHECK((mapping_status='UNMAPPED') = (internal_sku_id IS NULL)),
  -- 🔴 基数相关的三条 CHECK **只对生效中的映射成立**。
  --   解除后的行 status='REMOVED'：它已经「没有映射」了，relation_type 只是这条历史
  --   曾经是什么的记录（套装解除后仍如实记 ONE_TO_MANY），不参与「现在是什么」的判断。
  --   若不加这个限定，一条被解除的套装映射会因为 mapping_status 变成 UNMAPPED 而
  --   撞上 `relation_type='ONE_TO_MANY' = (mapping_status='BUNDLE')` 而写不进去。
  -- 套装必须有组件数量，非套装不得有组件数量
  CHECK(status<>'ACTIVE' OR ((relation_type='ONE_TO_MANY') = (component_quantity IS NOT NULL))),
  CHECK(component_quantity IS NULL OR component_quantity>0),
  -- BUNDLE 与 ONE_TO_MANY 互为充要条件（与 V171 guard_listing_variant_mapping 一致）
  CHECK(status<>'ACTIVE' OR ((relation_type='ONE_TO_MANY') = (mapping_status='BUNDLE'))),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_variant_id) REFERENCES listing_variant(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_mapping IS
  '4.1.2.1 映射管理 · 平台变体到内部 SKU/套装的当前映射。internal_sku_id 是 03 的逻辑 ID，不建 FK、不复制 catalog 任何一列。';

-- ---------------------------------------------------------------------------
-- listing_mapping_version · 映射版本（当前生效版本 + 历史版本）
-- ---------------------------------------------------------------------------
-- 4.1.2.2 的**表与生效区间语义**由本迁移建立（蓝图的输出句写在 4.1.2.1）；
-- 4.1.2.2 接手「受影响订单范围」查询，而那需要 09 订单的真实来源，本模块不预建。
CREATE TABLE listing_mapping_version (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  mapping_id          uuid NOT NULL,
  version_no          integer NOT NULL CHECK(version_no>0),
  relation_type       text NOT NULL
                      CHECK(relation_type IN ('ONE_TO_ONE','ONE_TO_MANY','MANY_TO_ONE')),
  mapping_status      text NOT NULL
                      CHECK(mapping_status IN ('UNMAPPED','MAPPED','BUNDLE')),
  internal_sku_id     uuid,
  component_quantity  numeric(19,6),
  -- 🔴 4.1.2.2 的「生效时间点」：半开区间 [effective_from, effective_to)，
  --    effective_to 为 NULL = 当前生效版本（与 catalog bundle_version 的 R1 同口径）
  effective_from      timestamptz NOT NULL,
  effective_to        timestamptz,
  -- 🔴 4.1.2.2 的「变更原因」
  change_reason       varchar(500) NOT NULL DEFAULT '',
  -- MANUAL = 人工建立；BATCH_CONFIRMED = 批量候选人工确认后写入（标题相似度必走这条）
  change_source       text NOT NULL DEFAULT 'MANUAL'
                      CHECK(change_source IN ('MANUAL','BATCH_CONFIRMED')),
  -- 由 DB 触发器从 app.actor 盖上（服务层写不出"谁改的"）
  actor_id            uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, mapping_id, version_no),
  CHECK((mapping_status='UNMAPPED') = (internal_sku_id IS NULL)),
  -- 🔴 基数相关的两条 CHECK 只对「有映射」的版本成立。
  --   解除映射本身也是一版历史：它记 mapping_status='UNMAPPED' + internal_sku_id=NULL，
  --   而 relation_type 如实保留「这条曾经是什么」（套装解除后仍是 ONE_TO_MANY）。
  --   不加这个限定，这一条版本行会因为 relation_type 与 mapping_status 不再互推而写不进去。
  CHECK(mapping_status='UNMAPPED' OR ((relation_type='ONE_TO_MANY') = (component_quantity IS NOT NULL))),
  CHECK(component_quantity IS NULL OR component_quantity>0),
  CHECK(mapping_status='UNMAPPED' OR ((relation_type='ONE_TO_MANY') = (mapping_status='BUNDLE'))),
  -- 区间用「终点严格大于起点」：空区间等于「从未生效」
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  FOREIGN KEY(tenant_id, mapping_id) REFERENCES listing_mapping(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_mapping_version IS
  '4.1.2.1 建表 · 4.1.2.2 查询。映射的当前生效版本与历史版本；effective_from/effective_to 是 4.1.2.2 的生效时间点，change_reason 是变更原因。';

-- ---------------------------------------------------------------------------
-- listing_mapping_batch_task · 批量匹配任务（输入：匹配规则 / 批量候选）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping_batch_task (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 三条规则（与蓝图职责句逐字对应）
  rule              text NOT NULL
                    CHECK(rule IN ('SKU_CODE','BARCODE','TITLE_SIMILARITY')),
  listing_ids       uuid[] NOT NULL,
  requested_count   integer NOT NULL CHECK(requested_count>0),
  candidate_count   integer NOT NULL DEFAULT 0 CHECK(candidate_count>=0),
  -- 候选集为空是**真状态**（这些变体确实没有任何可推荐项），不是"查不到"，所以不报错
  outcome           text NOT NULL DEFAULT 'COMPLETED'
                    CHECK(outcome IN ('COMPLETED')),
  -- 检索规模上限的如实记录（服务层有硬上限，藏在代码里就等于没告诉运营）
  scanned_skus      integer NOT NULL DEFAULT 0 CHECK(scanned_skus>=0),
  truncated         boolean NOT NULL DEFAULT false,
  reason            varchar(500) NOT NULL DEFAULT '',
  actor_id          uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);
COMMENT ON TABLE listing_mapping_batch_task IS
  '4.1.2.1 · 批量匹配任务；scanned_skus / truncated 如实记录检索上限，不让"只扫了前 N 条"看起来像"扫完了"。';

-- ---------------------------------------------------------------------------
-- listing_mapping_candidate · 批量候选（人工确认前不写任何映射）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping_candidate (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  batch_task_id         uuid NOT NULL,
  listing_id            uuid NOT NULL,
  listing_variant_id    uuid NOT NULL,
  external_variant_id   varchar(128) NOT NULL DEFAULT '',
  rule                  text NOT NULL
                        CHECK(rule IN ('SKU_CODE','BARCODE','TITLE_SIMILARITY')),
  -- 03 的逻辑 ID
  internal_sku_id       uuid NOT NULL,
  -- 🔴 以下三列是**推荐当时的展示快照**（见第三节），不参与匹配计算
  internal_sku_code     varchar(64) NOT NULL DEFAULT '',
  internal_sku_name     varchar(200) NOT NULL DEFAULT '',
  matched_text          varchar(200) NOT NULL DEFAULT '',
  -- PostgreSQL pg_trgm similarity() 的真算结果，不是服务层编的分
  score                 numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 蓝图加粗句「标题相似度推荐但需人工确认」的物理落点
  requires_confirmation boolean NOT NULL,
  decision              text NOT NULL DEFAULT 'PENDING'
                        CHECK(decision IN ('PENDING','CONFIRMED','REJECTED')),
  decided_by            uuid,
  decided_at            timestamptz,
  decision_reason       varchar(500) NOT NULL DEFAULT '',
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, batch_task_id, listing_variant_id, internal_sku_id, rule),
  CHECK(score>=0 AND score<=1),
  -- 🔴 标题相似度规则**恒需人工确认**：CHECK 而不是服务层 if，写死了 DB 层也改不掉
  CHECK(rule<>'TITLE_SIMILARITY' OR requires_confirmation),
  -- 已裁决的候选必须有裁决人与裁决时刻（"谁批的"不能为空）
  CHECK((decision='PENDING') = (decided_by IS NULL)),
  CHECK((decision='PENDING') = (decided_at IS NULL)),
  FOREIGN KEY(tenant_id, batch_task_id) REFERENCES listing_mapping_batch_task(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_variant_id) REFERENCES listing_variant(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_mapping_candidate IS
  '4.1.2.1 · 批量候选。requires_confirmation 由 DB CHECK 钉死（标题相似度恒为 true）；候选在 CONFIRMED 之前不写任何 listing_mapping 行。';

-- ---------------------------------------------------------------------------
-- 索引：四个查询形状各走索引
-- ---------------------------------------------------------------------------
CREATE INDEX listing_mapping_listing      ON listing_mapping(tenant_id, listing_id);
CREATE INDEX listing_mapping_sku          ON listing_mapping(tenant_id, internal_sku_id);
CREATE INDEX listing_mapping_relation     ON listing_mapping(tenant_id, relation_type);
CREATE INDEX listing_mapping_status       ON listing_mapping(tenant_id, status);
CREATE INDEX listing_mapping_active       ON listing_mapping(tenant_id, updated_at DESC, id) WHERE status='ACTIVE';
-- 🔴 一个平台变体**至多一条生效中的**映射行。
--   刻意用**部分唯一索引**而不是表级 UNIQUE(tenant_id, listing_variant_id)：
--   解除映射不是删除行（历史要留在 listing_mapping_version 里），那一行会一直占着
--   (tenant_id, listing_variant_id)。表级 UNIQUE 会让「解除之后重新建立映射」永远写不进去，
--   而重新映射是运营的日常动作 —— 变体被解除后重新对上新 SKU 必须能成。
--   部分索引只约束 status='ACTIVE' 的行，恰好表达「同时只能有一条生效映射」。
CREATE UNIQUE INDEX listing_mapping_active_variant
  ON listing_mapping(tenant_id, listing_variant_id) WHERE status='ACTIVE';
CREATE INDEX listing_mapping_version_map  ON listing_mapping_version(tenant_id, mapping_id, version_no DESC);
CREATE INDEX listing_mapping_version_time ON listing_mapping_version(tenant_id, effective_from DESC);
-- 🔴 「至多一个当前生效版本」：部分唯一索引挡并发写入（触发器只给可读原因，挡不住竞态）
CREATE UNIQUE INDEX listing_mapping_version_current
  ON listing_mapping_version(tenant_id, mapping_id) WHERE effective_to IS NULL;
CREATE INDEX listing_mapping_batch_recent  ON listing_mapping_batch_task(tenant_id, created_at DESC, id);
CREATE INDEX listing_mapping_candidate_batch ON listing_mapping_candidate(tenant_id, batch_task_id, score DESC);
CREATE INDEX listing_mapping_candidate_variant ON listing_mapping_candidate(tenant_id, listing_variant_id, decision);
CREATE INDEX listing_mapping_candidate_sku ON listing_mapping_candidate(tenant_id, internal_sku_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
CREATE TRIGGER listing_mapping_version_actor BEFORE INSERT ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION stamp_listing_mapping_version_actor();

CREATE TRIGGER listing_mapping_consistency BEFORE INSERT OR UPDATE ON listing_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_consistency();

-- 🔴 现状位投影（第五节）：映射一变，变体上的映射状态立刻跟着变，服务层漏不掉
CREATE TRIGGER listing_mapping_project AFTER INSERT OR UPDATE OF status, mapping_status,
      internal_sku_id, relation_type, component_quantity ON listing_mapping
  FOR EACH ROW EXECUTE FUNCTION project_listing_mapping_to_variant();

CREATE TRIGGER listing_mapping_current_version BEFORE INSERT ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_current_version();

CREATE TRIGGER listing_mapping_version_frozen BEFORE UPDATE ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_version_frozen();

CREATE TRIGGER listing_mapping_version_no_delete BEFORE DELETE ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_mapping_version_delete();

-- 映射解除后不许复活成 ACTIVE（解除是一条历史事实；要重新映射请建立新行）
CREATE OR REPLACE FUNCTION guard_listing_mapping_reactivate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='REMOVED' AND NEW.status='ACTIVE' THEN
    RAISE EXCEPTION 'A removed mapping must not be reactivated; establish a new mapping instead' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_mapping_no_reactivate BEFORE UPDATE OF status ON listing_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_reactivate();

-- current_version_id 必须指向本映射自己的某个版本（否则"当前生效版本"会指向别人）
CREATE OR REPLACE FUNCTION guard_listing_mapping_current_pointer() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.current_version_id IS NOT NULL AND NOT EXISTS(
       SELECT 1 FROM listing_mapping_version v
        WHERE v.tenant_id=NEW.tenant_id AND v.mapping_id=NEW.id AND v.id=NEW.current_version_id) THEN
    RAISE EXCEPTION 'current_version_id must point at a version of this mapping' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_mapping_current_pointer BEFORE INSERT OR UPDATE OF current_version_id ON listing_mapping
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_current_pointer();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 / V146 / V169 / V170 / V171 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_mapping','listing_mapping_version',
    'listing_mapping_batch_task','listing_mapping_candidate' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：映射的可见性随 listing 的负责人 / 部门收敛
-- ---------------------------------------------------------------------------
-- 与 V171 的 listing / listing_variant data_scope 策略同构：不允许绕过店铺直接枚举映射。
CREATE POLICY data_scope ON listing_mapping AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping.tenant_id AND l.id=listing_mapping.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping.tenant_id AND l.id=listing_mapping.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_mapping_version AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_mapping_version.tenant_id AND m.id=listing_mapping_version.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_mapping_version.tenant_id AND m.id=listing_mapping_version.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- 批量任务的范围：全部目标 listing 可见才允许写入（部分可见的任务会让"用户以为对整批都做了"变成假事实）
CREATE POLICY data_scope ON listing_mapping_batch_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_batch_task.tenant_id AND l.id = ANY(listing_mapping_batch_task.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_batch_task.tenant_id AND l.id = ANY(listing_mapping_batch_task.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_mapping_candidate AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_candidate.tenant_id AND l.id=listing_mapping_candidate.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_mapping_candidate.tenant_id AND l.id=listing_mapping_candidate.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141/V143/V146/V169/V170/V171 的条件建角色）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  listing_mapping, listing_mapping_version,
  listing_mapping_batch_task, listing_mapping_candidate TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 / V140 / V141 同口径）。
REVOKE ALL ON
  listing_mapping, listing_mapping_version,
  listing_mapping_batch_task, listing_mapping_candidate FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 🔴 pg_trgm：标题相似度**真算**的依赖
-- ---------------------------------------------------------------------------
-- 职责句要求「标题相似度推荐」，而硬性要求写明「必须真算，不能返回硬编码候选」。
-- 服务层自己在 Java 里算相似度也行得通，但那样"算出来的分数"与候选集的裁剪
-- （set_limit / GiST 索引）都没法在 SQL 里复现，运营复算时对不上账。
-- 用 PostgreSQL 的 `similarity()`：分数由 PG 给出，可复核、可索引、不依赖语言实现细节。
-- IF NOT EXISTS：与 catalog V21 / V100 同写法，重复执行安全。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：映射属于「平台操作」语义，与 4.1.1.1 Listing 列表、
-- 10.1.1.2 渠道配置、10.1.2.1 运费计算器、2.1.2.1 平台枚举映射、14.1.2.2 广告类型差异同族，
-- 因此 identity 服务零改动。
