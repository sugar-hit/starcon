-- ===========================================================================
-- 4.1.2.2 映射版本（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.1.2.2 映射版本
--   - 职责：映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，
--           支持查看某订单使用的是哪个版本、按版本回溯受影响订单。
--   - 输入：映射变更；生效时间点；变更原因。
--   - 输出：版本列表 + 受影响订单范围查询。
--   - 关系：上游 = 4.1.2.1；下游 = 09 订单（`order_line_component` 冻结）。
--           **改映射不影响已产生的订单**，只对新订单生效——这是财务与售后追溯的前提。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 全部**纯新建一张表、零 ALTER 任何既有表**（与 V177 批量编辑、4.1.1.1 同一纪律）：
--   * V176 `listing_mapping_version` —— 版本表与生效区间语义由 4.1.2.1 建好，
--     本迁移**只挂触发器、不改它的任何列**，由它决定本表什么时候被填。
--   * V176 `listing_mapping` —— 版本 → 平台变体 / 平台商品的指向（复合 FK 带 tenant_id）。
--   * V171 `listing` / `listing_variant` —— 同库同服务，读得到。
--   * V1   `shop` —— 同库同服务，读得到。
--
-- 🔴 跨服务的 `internal_sku_id` 是 03 商品的逻辑 ID，**只存不校验**（与 V176 同纪律）：
-- 存在性与租户归属由 catalog 的授权 API 确认，本迁移不建 FK、不复制 catalog 任何一列。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块为什么值得建一张表：版本必须**带得住**它当时的商品范围
-- ---------------------------------------------------------------------------
-- 蓝图要求「**保留**每个版本对应的商品/订单范围」，关键词是「保留」而不是「能查到」。
-- 如果商品范围只靠 `version → mapping → listing` 现查，会出两个真实的问题：
--
--   ① **listing 会被重新同步**：`listing.title` / `external_item_id` 是平台侧事实，
--      02 每次同步都会改写它。版本 1 生效时这个商品叫 A，版本 2 生效时它叫 B，
--      现查会让**版本 1 的历史被后来的同步改写**——正是要防的那种"历史悄悄变了"。
--   ② **listing 可被删除**：`listing_mapping.listing_id` 带 `ON DELETE CASCADE`，
--      删掉 listing 会级联删掉 mapping 与它的版本行。V176 的触发器只挡住
--      **已关闭**版本的删除，**当前生效**的版本是会跟着消失的。届时
--      「这笔订单当时用的是哪条映射、对应哪个平台商品」就再也答不出来。
--
-- 所以：版本一落库，就把**它当时覆盖的商品身份**冻结进本表。
-- 「改映射不影响已产生的订单」要能成立，前提是**回溯得到当时的事实**，
-- 而不只是「现在还能 join 出一个 id」。
--
-- ⚠️ 本表是**快照**，不是主数据：平台侧当前值仍在 `listing`，两者的差异是有意保留的
-- （运营要看「当时叫什么」和「现在叫什么」）。快照列名一律带 `_snapshot` 后缀，
-- 避免有人在别处把它当成平台现状引用。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 填充交给 DB 触发器，不交给服务层
-- ---------------------------------------------------------------------------
-- V176 第五节已经立过这条纪律：「同步交给服务层，就会出现极难发现的数据不一致」。
-- 这里同理：漏写一行 scope 的症状是"版本详情里商品范围是空的"，
-- 而版本本身看起来完全正常 —— 恰恰是最难被发现的一种丢数据。
--
-- `AFTER INSERT ON listing_mapping_version` 触发器把当时的商品身份抓下来，
-- 直接写 SQL 也绕不过去。**每个版本恰好一行 scope**：UNIQUE(tenant_id, version_id) 兜底。
--
-- 读取遵守调用者的 RLS（函数默认 SECURITY INVOKER）：看不见的 listing 不会被快照进来。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 只读：版本的产品范围是历史，不许就地改写
-- ---------------------------------------------------------------------------
-- 与 V176 的 `guard_listing_mapping_version_frozen` 同一纪律：已落库的 scope 只读。
-- 「想改范围」的唯一办法是**改映射生成新版本**，新版本带自己的新 scope ——
-- 这正是本模块「映射变更生成新版本而非原地修改」的物理落点。
--
-- DELETE 不加触发器拦截：版本行被删除时 scope 行随复合 FK 的 ON DELETE CASCADE 一起消失
-- （cascade 同样会触发行级 BEFORE DELETE 触发器，一并拦掉会让级联删不掉）。
-- 真正的护栏在上游：V176 拒绝删除任何**已关闭**的版本。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 刻意不建「受影响订单范围」相关的一切
-- ---------------------------------------------------------------------------
-- 蓝图输出句的第二半是「受影响订单范围查询」，它指向 **09 订单**的真实数据。
-- 实查集成头：trade 侧**没有任何**可供 channel 查询订单的已交付端点，
-- `order_line_component` 的冻结也未交付。
--
-- 所以本迁移**一张订单引用表都不建**：建一张永远为空的表，只会让界面看起来
-- 「按版本回溯订单」已经能用了 —— 那比明确报不可用更糟。
-- 端点侧按仓库既有惯例（`ReservationController` / `WarehouseFacilityController`）
-- 显式返回 `available=false` + ownerModule，见 `ListingMappingVersionController`。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本表**无任何金额列**（映射与版本不碰钱）。唯一的数值列是
-- `version_no integer`（序号，CHECK > 0）。**无 float / double 一列**。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- `captured_at timestamptz` 由 DB 的 `now()` 盖上，**不由服务层绑定** ——
-- 版本落库时刻与范围抓取时刻是同一个事务里的同一个事实，分成两个时间只会让
-- 「这个范围是什么时候抓的」有两个答案。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- listing_mapping_version_scope · 版本在落库那一刻覆盖的商品身份（冻结快照）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_mapping_version_scope (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 🔴 版本行的复合 FK 带 tenant_id ⇒ 别的租户的版本引用不到本表的一行
  version_id          uuid NOT NULL,
  mapping_id          uuid NOT NULL,
  listing_id          uuid NOT NULL,
  listing_variant_id  uuid NOT NULL,
  shop_id             uuid,
  -- 以下五列是**版本落库那一刻的平台侧身份快照**（见第一节）
  shop_code             varchar(64)  NOT NULL DEFAULT '',
  external_item_id      varchar(128) NOT NULL DEFAULT '',
  title_snapshot        varchar(500) NOT NULL DEFAULT '',
  external_variant_id   varchar(128) NOT NULL DEFAULT '',
  -- 版本号冗余一份：列表排序与「这是第几版」不必每次都回表 join 版本行
  version_no          integer NOT NULL CHECK(version_no>0),
  captured_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一个版本恰好一条范围：漏填会被这里挡住，而不是等到界面显示空白才发现
  UNIQUE(tenant_id, version_id),
  FOREIGN KEY(tenant_id, version_id) REFERENCES listing_mapping_version(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_variant_id) REFERENCES listing_variant(tenant_id, id)
);
COMMENT ON TABLE listing_mapping_version_scope IS
  '4.1.2.2 · 版本对应的商品范围**冻结快照**（V182 新建）。平台侧当前值仍在 listing，本表回答的是「当时叫什么」。';

-- ---------------------------------------------------------------------------
-- 索引：版本查询面的四个形状
-- ---------------------------------------------------------------------------
CREATE INDEX listing_mapping_version_scope_version   ON listing_mapping_version_scope(tenant_id, version_id);
CREATE INDEX listing_mapping_version_scope_mapping   ON listing_mapping_version_scope(tenant_id, mapping_id, version_no DESC);
CREATE INDEX listing_mapping_version_scope_listing   ON listing_mapping_version_scope(tenant_id, listing_id);
CREATE INDEX listing_mapping_version_scope_variant   ON listing_mapping_version_scope(tenant_id, listing_variant_id);
CREATE INDEX listing_mapping_version_scope_shop      ON listing_mapping_version_scope(tenant_id, shop_code);
CREATE INDEX listing_mapping_version_scope_recent    ON listing_mapping_version_scope(tenant_id, captured_at DESC, id);

-- ---------------------------------------------------------------------------
-- 触发器：版本落库 ⇒ 抓下它当时的商品范围（第二节）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION capture_listing_mapping_version_scope() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO listing_mapping_version_scope (
    tenant_id, id, version_id, mapping_id, listing_id, listing_variant_id,
    shop_id, shop_code, external_item_id, title_snapshot, external_variant_id, version_no)
  SELECT NEW.tenant_id,
         gen_random_uuid(),
         NEW.id,
         NEW.mapping_id,
         m.listing_id,
         m.listing_variant_id,
         l.shop_id,
         coalesce(s.code,''),
         l.external_item_id,
         l.title,
         v.external_variant_id,
         NEW.version_no
    FROM listing_mapping m
    JOIN listing       l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
    JOIN listing_variant v ON v.tenant_id=m.tenant_id AND v.id=m.listing_variant_id
    -- 店铺用 LEFT JOIN：店铺行缺失不该让版本落库失败（范围的主体是 listing / variant）
    LEFT JOIN shop    s ON s.tenant_id=l.tenant_id AND s.id=l.shop_id
   WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.mapping_id;
  -- 一行都没抓到 = 映射行或平台侧行在 RLS 下不可见。此时**直接拒**：
  -- 静默跳过会留下一条没有商品范围的版本，而那正是本表存在的理由要消灭的情况。
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Cannot capture product scope for mapping version %: mapping or listing row is not visible to this tenant', NEW.id
      USING ERRCODE='23503';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER listing_mapping_version_capture_scope AFTER INSERT ON listing_mapping_version
  FOR EACH ROW EXECUTE FUNCTION capture_listing_mapping_version_scope();

-- 🔴 只读：范围是历史，不许就地改写（第三节）
CREATE OR REPLACE FUNCTION guard_listing_mapping_version_scope_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A mapping version product scope is history and must not be rewritten in place' USING ERRCODE='23514';
END $$;

CREATE TRIGGER listing_mapping_version_scope_frozen BEFORE UPDATE ON listing_mapping_version_scope
  FOR EACH ROW EXECUTE FUNCTION guard_listing_mapping_version_scope_frozen();

-- ---------------------------------------------------------------------------
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 V171 / V176 / V177 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- 与 channel 既有迁移逐字一致（不改写这条公共策略，避免与别人并入时产生无谓 diff）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_mapping_version_scope'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围：版本的可见性随 listing 的负责人 / 部门收敛（与 V176 的版本策略同构）
CREATE POLICY data_scope ON listing_mapping_version_scope AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_mapping_version_scope.tenant_id AND m.id=listing_mapping_version_scope.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_mapping_version_scope.tenant_id AND m.id=listing_mapping_version_scope.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

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

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_mapping_version_scope TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V171 / V176 同口径）。
REVOKE ALL ON listing_mapping_version_scope FROM starcon_app;
