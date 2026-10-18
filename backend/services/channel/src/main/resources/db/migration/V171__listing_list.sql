-- ===========================================================================
-- 4.1.1.1 Listing 列表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.1.1.1 Listing 列表
--   - 职责：按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、
--           评分、近 7 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。
--   - 输入：筛选条件；用户数据范围（店铺维度）；`listing` + `listing_variant` + 销量/库存投影。
--   - 输出：分页列表（含内部 SKU 映射状态、缺货标记、审核状态）；批量操作任务；点击进入 4.1.1.2。
--   - 关系：上游 = 02 店铺、03 SKU、05 价格、07 库存；下游 = 4.1.1.2、4.2.1.1、4.3.1。
--           **"未映射"和"缺货"是两个独立状态**，列表要能同时显示且可分别筛选。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER 任何既有表：
--   * V1 `shop`（租户店铺，UNIQUE(tenant_id,id)）+ V1 `shop_marketplace`（启用站点）——
--     复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
--   * V1 `marketplace`（站点码表，全局只读）与 V1 `platform`（平台码表，全局只读）
--   * 03 商品的 `sku`（catalog V1）**跨服务**：这里只存逻辑 ID `internal_sku_id`，
--     **不建 FK、不复制 catalog 的任何一列**（编码 / 名称 / 规格一律不落在本表）。
--     与 V1 `shop.legal_entity_id` 同一纪律：存在性与租户归属由 owning service 的
--     授权 API 确认，不跨库建 FK。
--
-- 表名走 `listing` 命名空间，与 V136 `logistics_channel_*`、V140 `freight_*`、
-- V143 `channel_comparison_*`、V146 `ad_type_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 归属 channel 而不是 catalog 的理由
-- ---------------------------------------------------------------------------
-- 卷 04 在 blueprint README 的 L2 表里写的是「catalog + channel」。本模块落 channel 的理由是
-- **物理的而不是偏好**：
--   * `listing` 必须按**店铺 / 站点**筛选与收敛可见性，这两张表（`shop` / `shop_marketplace`）
--     是 channel V1 的事实，且带 `data_scope` RESTRICTIVE 策略读 `app.grants`。
--   * AGENTS.md 禁止跨服务数据库访问。把 listing 放 catalog 就必须为了店铺维度数据范围去读
--     channel 的表 —— 那是跨服务 SQL，比"多写一个 JOIN"严重得多。
--   * 平台商品是**平台侧的事实**（ASIN / item_id / 平台状态 / 平台评分），与 channel 已有的
--     `platform` / `marketplace` / `shop` / 同步运行时同族。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 上游依赖的诚实边界：卷 05 价格整卷未交付，卷 09 销量、卷 07 内部可售量同理
-- ---------------------------------------------------------------------------
-- 实测集成头：**卷 05 定价 0 / 19 未交付**，全库不存在任何"平台 listing 售价"事实表
-- （`supplier_quote_price_tier` 是 06 采购的报价、`b2b_price_book` / `b2b_price_tier` 是
-- trade 的 B2B 协议价，**都不是** listing 售价）。所以本迁移**刻意不建任何价格列**：
--
--   * 建一个 `price numeric(19,4)` 影子列 = 假装有第二个价格事实来源。05 上线后两个数字
--     迟早对不上，而没人说得清该信哪个 —— 这比"没有价格"危险得多。
--   * 蓝图「批量改价改库存（跳 05/07）」本身就写明这两件事**跳到 05/07**，不是本模块的活。
--   * 列表的 `capabilities` 端点显式返回 `priceAvailable=false` + 归属模块 + 影响，
--     前端据此显示「价格能力未接入」，**不回 0、不显示假价格**。
--
-- 同理：销量归 09 订单、内部可售量归 07 库存，二者都跨服务，本表都不建列。
--
-- ⚠️ 但「缺货」是**能**在本模块成立的能力，因为 listing 侧有一个 04 自己拥有的事实：
-- **平台回报的可售状态**（`platform_stock_state`）。它来自 02 同步 / 4.3.2.2 状态回流，
-- 与 07 的内部可售量是**两个事实**（蓝图 4.1.1.3 加粗句「平台下架与内部下架是两个事实」
-- 是同一纪律）。所以缺货标记筛的是平台侧状态，07 的内部可售量在 capabilities 里
-- 显式 available=false —— 两者不会被混为一谈。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 蓝图加粗句「未映射和缺货是两个独立状态」的物理落点
-- ---------------------------------------------------------------------------
-- 「未映射」= 平台变体有没有落到内部 SKU（`listing_variant.internal_sku_id` 是否为空）；
-- 「缺货」 = 平台侧可售状态（`listing.platform_stock_state`）。
-- 两者**分属两张表、两个维度**，可以同时为真、也可以一真一假。因此：
--   * 两列都不许由对方推导（不能用"没映射所以当缺货"这类兜底）；
--   * 列表的 mappingStatus 筛选走一条 SQL 里的聚合（UNMAPPED / PARTIAL / MAPPED），
--     outOfStock 筛选走 listing 自己的列 —— 两条筛选互不影响，也不互相取消。
--   * DB 层用触发器钉住：声称 MAPPED 却给不出 internal_sku_id 的行**直接被拒**。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 近 7 日状态变化：由 DB 触发器现场派生，服务层漏写不了
-- ---------------------------------------------------------------------------
-- 「近 7 日状态变化」是职责句里的展示项，而状态变更是 4.1.1.3 的输出。本模块只需要
-- **读到**它，所以事件表 `listing_status_event` 随本迁移建立，4.1.1.3 在这张表上补
-- 生命周期状态机与平台回写即可，无需第二次建表。
--
-- 🔴 但事件记录**不交给服务层写**：`listing` 的 status 一变，AFTER UPDATE 触发器就地
-- 落一条事件（来源固定为 MANUAL，4.3.2.2 的平台回流才是 PLATFORM）。服务层可以改状态，
-- 但**改不出"状态变了而历史里查不到"**这种数据 —— 漏记历史比多记历史更难发现。
--
-- ---------------------------------------------------------------------------
-- 五、批量操作任务（输出「批量操作任务」）
-- ---------------------------------------------------------------------------
-- `listing_batch_task` 落库每一次批量操作（批量分配负责人 / 批量下架 / 批量改价 / 批量改库存）。
-- 后两者在本模块被**显式拒绝**（归 05 / 07），但任务表照样记录这次被拒的意图与理由 ——
-- 「用户点了改价、系统没做」是必须留痕的事实，不是可以静默吞掉的 422。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本表**无任何金额列**（价格归 05，见第二节）。存在的两个数值列：
--   * `rating numeric(9,4)`     —— 平台评分，是分数不是钱
--   * `rating_count numeric(19,6)` —— 评论数是数量，按 AGENTS.md 一律 NUMERIC(19,6)
-- **无 float / double 一列**，无 double 精度字段。
--
-- ---------------------------------------------------------------------------
-- 七、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**。

-- ---------------------------------------------------------------------------
-- listing · 平台商品（列表的一行）
-- ---------------------------------------------------------------------------
CREATE TABLE listing (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 上游 02 店铺：复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
  shop_id           uuid NOT NULL,
  -- 上游 02 站点：marketplace 是全局只读码表（V1），不跨服务
  marketplace_id    uuid,
  -- 平台商品 ID：ASIN / item_id / offer_id 原文照抄，不折叠大小写
  external_item_id  varchar(128) NOT NULL,
  title             varchar(500) NOT NULL,
  -- 平台类目路径（平台侧原文，如 "Electronics > Audio > Headphones"）
  category_path     varchar(500) NOT NULL DEFAULT '',
  platform_category_id varchar(128) NOT NULL DEFAULT '',
  -- 4.1.1.3 的七态生命周期在列表上作为筛选维度存在；状态机与平台回写归 4.1.1.3
  status            text NOT NULL DEFAULT 'DRAFT'
                    CHECK(status IN ('DRAFT','ON_SALE','UNDER_REVIEW','REJECTED',
                                     'DELISTED_BY_US','DELISTED_BY_PLATFORM','BLOCKED')),
  -- 审核状态与驳回原因（输出里的「审核状态」）
  review_status     text NOT NULL DEFAULT 'NOT_SUBMITTED'
                    CHECK(review_status IN ('NOT_SUBMITTED','PENDING','APPROVED','REJECTED')),
  review_message    varchar(1000) NOT NULL DEFAULT '',
  -- 负责人（筛选维度之一）。归属快照，纪律照抄 V1 shop.owner_id
  owner_id          uuid,
  department_id     uuid,
  -- 🔴 缺货标记：平台侧可售状态。**与 07 的内部可售量是两个事实**，不得互相推导
  platform_stock_state text NOT NULL DEFAULT 'UNKNOWN'
                    CHECK(platform_stock_state IN ('IN_STOCK','OUT_OF_STOCK','UNKNOWN')),
  platform_stock_qty   numeric(19,6),
  -- 平台评分与评论数（平台侧事实）
  rating            numeric(9,4),
  rating_count      numeric(19,6),
  currency          char(3),
  launched_at       timestamptz,
  last_synced_at    timestamptz,
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, shop_id, external_item_id),
  -- 平台侧可售量给了值就必须是正数；'UNKNOWN' 时允许为空，两者不许自相矛盾
  CHECK(platform_stock_state<>'IN_STOCK' OR platform_stock_qty IS NULL OR platform_stock_qty>0),
  CHECK(platform_stock_qty IS NULL OR platform_stock_qty>=0),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE listing IS '4.1.1.1 Listing 列表 · 平台商品（channel 服务）。无价格列：售价归 05 定价。';

-- ---------------------------------------------------------------------------
-- listing_variant · 平台变体 → 内部 SKU 的映射现状
-- ---------------------------------------------------------------------------
-- 「内部 SKU 映射状态」的落点。internal_sku_id 是**逻辑 ID**（catalog V1 sku 的 id），
-- 不跨库建 FK，也不复制 catalog 的编码 / 名称 —— 与 V1 shop.legal_entity_id 同一纪律。
-- 映射的**建立 / 版本化 / 批量匹配**归 4.1.2.1 / 4.1.2.2，本表只承载「现在有没有映射上」。
CREATE TABLE listing_variant (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  listing_id        uuid NOT NULL,
  external_variant_id varchar(128) NOT NULL,
  -- 平台侧变体属性原文（尺码 / 颜色 …），jsonb 存平台原样返回的结构
  attributes        jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 映射状态与 internal_sku_id 必须一致，由触发器钉住（见第四节之后）
  mapping_status    text NOT NULL DEFAULT 'UNMAPPED'
                    CHECK(mapping_status IN ('UNMAPPED','MAPPED','BUNDLE')),
  internal_sku_id   uuid,
  -- 组件数量：仅 BUNDLE 有意义（4.1.2.3 套装组件映射的现状位）
  component_quantity numeric(19,6),
  version           bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, listing_id, external_variant_id),
  -- 声称已映射却给不出内部 SKU，或给了 SKU 却声称未映射，两种都直接拒
  CHECK((mapping_status='UNMAPPED') = (internal_sku_id IS NULL)),
  -- 组件数量必须大于 0（蓝图 4.1.2.3 加粗句：组件数量必须大于 0）
  CHECK(component_quantity IS NULL OR component_quantity>0),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_variant IS '4.1.1.1 · 平台变体与内部 SKU 的映射现状；internal_sku_id 是跨服务逻辑 ID，不建 FK。';

-- ---------------------------------------------------------------------------
-- listing_status_event · 状态变更事件（近 7 日状态变化的来源）
-- ---------------------------------------------------------------------------
-- 由 listing 的 AFTER UPDATE 触发器派生，见第四节。
CREATE TABLE listing_status_event (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  listing_id   uuid NOT NULL,
  from_status  text,
  to_status    text NOT NULL,
  -- 来源：MANUAL = 人工操作（触发器派生）；PLATFORM = 4.3.2.2 平台状态回流
  source       text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','PLATFORM')),
  note         varchar(500) NOT NULL DEFAULT '',
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_status_event IS '4.1.1.1 · 状态变更事件；status 一变即由 DB 触发器落一条，服务层漏记不了。';

-- ---------------------------------------------------------------------------
-- listing_batch_task · 批量操作任务（输出「批量操作任务」）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_batch_task (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- ASSIGN_OWNER / DELIST 由本模块执行；REPRICE / RESTOCK 显式拒绝（归 05 / 07）
  action        text NOT NULL
                CHECK(action IN ('ASSIGN_OWNER','DELIST','REPRICE','RESTOCK')),
  -- SUCCEEDED = 已执行；REJECTED = 显式拒绝（归 05 / 07），**同样落库留痕**
  outcome       text NOT NULL CHECK(outcome IN ('SUCCEEDED','REJECTED')),
  -- 目标 listing 集合（冻结当时的快照，便于回溯"当时选的是哪些"）
  listing_ids   uuid[] NOT NULL,
  requested_count integer NOT NULL,
  affected_count  integer NOT NULL DEFAULT 0,
  skip_count      integer NOT NULL DEFAULT 0,
  -- 参数原文（负责人 id / 拒绝理由 …）
  params        jsonb NOT NULL DEFAULT '{}'::jsonb,
  reason        varchar(500) NOT NULL DEFAULT '',
  actor_id      uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(requested_count>=0 AND affected_count>=0 AND skip_count>=0)
);
COMMENT ON TABLE listing_batch_task IS '4.1.1.1 · 批量操作任务；被拒绝的改价 / 改库存同样留痕。';

-- ---------------------------------------------------------------------------
-- 索引：列表的七个筛选维度各自可走索引
-- ---------------------------------------------------------------------------
CREATE INDEX listing_list_recent   ON listing(tenant_id, updated_at DESC, id);
CREATE INDEX listing_shop         ON listing(tenant_id, shop_id);
CREATE INDEX listing_marketplace  ON listing(tenant_id, marketplace_id);
CREATE INDEX listing_status       ON listing(tenant_id, status);
CREATE INDEX listing_owner        ON listing(tenant_id, owner_id);
CREATE INDEX listing_category     ON listing(tenant_id, category_path);
CREATE INDEX listing_stock_state  ON listing(tenant_id, platform_stock_state);
CREATE INDEX listing_ext_id       ON listing(tenant_id, shop_id, external_item_id);

CREATE INDEX listing_variant_listing  ON listing_variant(tenant_id, listing_id);
CREATE INDEX listing_variant_mapping ON listing_variant(tenant_id, mapping_status);
CREATE INDEX listing_variant_sku     ON listing_variant(tenant_id, internal_sku_id);

-- 近 7 日状态变化按「列出这些事件的 listing」扫
CREATE INDEX listing_status_event_recent ON listing_status_event(tenant_id, listing_id, occurred_at DESC);
CREATE INDEX listing_status_event_time   ON listing_status_event(tenant_id, occurred_at DESC);
CREATE INDEX listing_batch_task_recent   ON listing_batch_task(tenant_id, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 负责人归属快照：owner_id / department_id 由已认证操作者在事务内设置，
--     客户端的 X-Tenant-Id 头进不来也改不动它。改归属必须走显式转让端点
--     （set_config('app.transfer', ...)），纪律与 V1 stamp_shop_owner 完全一致。
CREATE FUNCTION stamp_listing_owner() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
  NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
  IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
 ELSE
  IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
     AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
  THEN RAISE EXCEPTION 'Listing ownership transfer must use the explicit transfer flag' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_owner BEFORE INSERT OR UPDATE ON listing FOR EACH ROW EXECUTE FUNCTION stamp_listing_owner();

-- (b) 🔴 映射状态与内部 SKU 必须一致（服务层声称 MAPPED 却给不出 SKU，DB 直接拒）。
--     CHECK 约束已经挡住，这里再加一道触发器：它是**跨列 + 跨语义**的判据，
--     且能给出比 CHECK 更明确的错误原因。
CREATE FUNCTION guard_listing_variant_mapping() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.mapping_status IN ('MAPPED','BUNDLE') AND NEW.internal_sku_id IS NULL THEN
  RAISE EXCEPTION 'Variant claims mapping status % but carries no internal_sku_id', NEW.mapping_status
    USING ERRCODE='23514';
 END IF;
 IF NEW.mapping_status='UNMAPPED' AND NEW.internal_sku_id IS NOT NULL THEN
  RAISE EXCEPTION 'Variant carries internal_sku_id but claims UNMAPPED' USING ERRCODE='23514';
 END IF;
 -- BUNDLE 必须带组件数量，普通 MAPPED 不该带（避免"套装数量=1"这种自相矛盾的写法）
 IF NEW.mapping_status='BUNDLE' AND NEW.component_quantity IS NULL THEN
  RAISE EXCEPTION 'Bundle variant requires component_quantity > 0' USING ERRCODE='23514';
 END IF;
 IF NEW.mapping_status<>'BUNDLE' AND NEW.component_quantity IS NOT NULL THEN
  RAISE EXCEPTION 'component_quantity is only meaningful for BUNDLE variants' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_variant_mapping BEFORE INSERT OR UPDATE ON listing_variant
  FOR EACH ROW EXECUTE FUNCTION guard_listing_variant_mapping();

-- (c) 🔴 状态一变就落事件，服务层漏记不了。
--     来源固定 MANUAL：平台来源的事件由 4.3.2.2 的回流入口写（那条链路自己写 PLATFORM）。
CREATE FUNCTION record_listing_status_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status THEN
  INSERT INTO listing_status_event
    (id,tenant_id,listing_id,from_status,to_status,source,note,occurred_at)
  VALUES
    (gen_random_uuid(),NEW.tenant_id,NEW.id,OLD.status,NEW.status,'MANUAL','',now());
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_status_change AFTER UPDATE OF status ON listing
  FOR EACH ROW EXECUTE FUNCTION record_listing_status_change();

-- (d) 🔴 listing 一旦登记，平台商品标识不可就地改写：列表、批量操作、订单行解析都按它寻址，
--     改了会让"这条 listing 到底是哪个平台商品"永远答不出来。要换只能新登记一条。
CREATE FUNCTION guard_listing_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (NEW.shop_id IS DISTINCT FROM OLD.shop_id OR NEW.external_item_id IS DISTINCT FROM OLD.external_item_id)
    AND coalesce(nullif(current_setting('app.reidentify',true),'')::boolean,false) IS NOT TRUE
 THEN RAISE EXCEPTION 'Listing shop/external_item_id must not be rewritten in place' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_identity BEFORE UPDATE ON listing FOR EACH ROW EXECUTE FUNCTION guard_listing_identity();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 / V146 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing','listing_variant','listing_status_event','listing_batch_task' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：列表按**店铺维度**收敛（蓝图「输入：用户数据范围（店铺维度）」）
-- ---------------------------------------------------------------------------
-- 与 V1 的 shop_marketplace data_scope 策略同构：可见性随店铺 owner / department 收敛，
-- 不允许绕过店铺直接枚举 listing。
CREATE POLICY data_scope ON listing AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing.tenant_id AND s.id=listing.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing.tenant_id AND s.id=listing.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON listing_variant AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_variant.tenant_id AND l.id=listing_variant.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_variant.tenant_id AND l.id=listing_variant.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_status_event AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_status_event.tenant_id AND l.id=listing_status_event.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_status_event.tenant_id AND l.id=listing_status_event.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- 批量任务的范围随目标 listing 收敛：全部目标可见才允许写入这条任务。
-- （部分可见的任务会让"用户以为对整批都做了"变成假事实。）
CREATE POLICY data_scope ON listing_batch_task AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_batch_task.tenant_id AND l.id = ANY(listing_batch_task.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_batch_task.tenant_id AND l.id = ANY(listing_batch_task.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141/V143/V146 的条件建角色，保证迁移可重复执行）
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
  listing, listing_variant, listing_status_event, listing_batch_task TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 / V140 同口径）。
REVOKE ALL ON
  listing, listing_variant, listing_status_event, listing_batch_task FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：listing 属于「平台操作」语义，与 10.1.1.2 渠道配置、
-- 10.1.2.1 运费计算器、2.1.2.1 平台枚举映射、14.1.2.2 广告类型差异同族，
-- 因此 identity 服务零改动。
