-- ===========================================================================
-- 4.1.2.3 套装组件映射（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.1.2.3 套装组件映射
--   - 职责：当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与
--           不同套装版本对应，并校验组件 SKU 在目标仓库可售。
--   - 输入：组件映射维护；套装版本选择。
--   - 输出：组件映射记录 + 可售性校验结果。
--   - 关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。
--           **组件数量必须大于 0**，缺任一组件时整个套装不可售。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 全部**纯新建两张表、零 ALTER 任何既有表**（与 V171 / V176 / V177 / V182 同一纪律）：
--   * V176 `listing_mapping`（UNIQUE(tenant_id,id)）—— 本表的**写入前提**：只有
--     `relation_type='ONE_TO_MANY'` 的生效映射才有组件可言，复合 FK 带 tenant_id
--     ⇒ 别的租户挂不上、别的商品也挂不上。
--   * V171 `listing` / `listing_variant` —— 同库同服务，只在数据范围策略里读 owner_id /
--     department_id，与 V182 逐字同构。
--   * V1 `shop` —— 同库同服务，不直接引用。
--
-- 🔴 **跨服务的一律只存逻辑 ID，不建 FK、不复制对方任何一列**：
--   * catalog V23 `bundle_version` / `bundle_component`（3.5.1 套装版本）——
--     `bundle_version_id` / `component_sku_id` 是**逻辑 ID**，存在性与租户归属由
--     catalog 的授权 API（`GET /catalog/versions/{id}/components`）在写入时确认。
--     AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图。
--   * warehouse（07 库存）可售量 —— 存在**别的服务**的库里，本模块<b>一条 SQL 都不碰</b>，
--     可售性只能经 warehouse 的授权 HTTP API 读（见第三节：它因此表达不成 DB 约束）。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 组件表为什么归本模块，而不是复用 catalog 的 bundle_component
-- ---------------------------------------------------------------------------
-- V176 第六节已经把这条线划出来：「组件 SKU 逐条明细不建表 —— 那是 4.1.2.3 的职责」。
-- 真正的分界线是**两个问题，不是两个真相**：
--
--   catalog `bundle_component` 回答：「这个**内部套装**由哪些 SKU 组成」——
--   它是**产品定义**，跟着内部商品走，刊登换不改它都不变。
--
--   本表 `listing_bundle_component` 回答：「这个**平台变体**按哪一版套装、
--   每卖 1 件平台套装要扣几件组件」——它是**刊登侧绑定**。
--
-- 🔴 两者**允许不同**，这正是本表存在的理由，不是重复：
--   * 同一个内部套装版本，A 店按 1:1 卖、B 店按 2:1 卖（平台侧 1 件 = 内部 2 件组件）；
--   * 内部套装升版后，平台侧要跟着换版才能保住「扣减口径不变」。
--
-- 所以本表**引用** `bundle_version_id` 而**不复制**组件构成：构成的真值永远在 catalog，
-- 本表只记「这次刊登选了哪一版、每件几扣」。写入时用 catalog 授权 API 校验
-- 「这个组件 SKU 确实属于这一版」，校验结果落在 `component_sku_id` 的语义上，
-- **不落一份构成快照**（快照是订单侧的冻结职责，那是 09 的 `order_line_component`）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴「支持平台变体与不同套装版本对应」用生效区间表达，不用 status 字段
-- ---------------------------------------------------------------------------
-- 与 4.1.2.2 的 `listing_mapping_version` **同一套纪律**：`effective_from` / `effective_to`
-- 半开区间，`effective_to IS NULL` = 当前生效。选这一套而不是 `status='ACTIVE'` 的理由：
-- 「这一版从什么时候用到现在」本身就是运营要回答的问题，用 status 表达会把它丢掉。
--
-- 「同一条映射至多一个当前生效的套装版本」由**部分唯一索引**挡住：
--   * `where effective_to is null` 的部分唯一索引挡并发写入；
--   * `guard_listing_bundle_binding_current` 触发器给出能读懂的原因（直接写 SQL 也绕不过去）。
--
-- 「关闭后不可复活 / 不可改写」由 `guard_listing_bundle_binding_frozen` 表达，
-- 与 V176 `guard_listing_mapping_version_frozen` 逐字同款：
-- **改组件集合的唯一办法是换一版绑定**，不是就地改 —— 这是「内部套装升版不影响已在售商品」
-- 的物理落点。
--
-- ---------------------------------------------------------------------------
-- 三、🔴「缺任一组件时整个套装不可售」为什么落不到 CHECK 上
-- ---------------------------------------------------------------------------
-- 这句话有两个分支，**只有一半能被数据库表达**：
--
--   * 「组件数量必须大于 0」→ **DB 约束**：本表 `quantity numeric(19,6) NOT NULL CHECK(quantity>0)`
--     ＋ `guard_listing_bundle_component_quantity` 触发器（给出可读原因）。这是硬事实。
--   * 「缺任一组件时整个套装不可售」→ 「可售」的真值在 **warehouse** 的库里（在手 − 预占 −
--     安全缓冲），跨服务。AGENTS.md 禁止跨服务 SQL，所以**表达不成 CHECK / 触发器**。
--     硬写一个「无跨库视图」的假约束只会造成"库里说可售、实际不可售"的假保证。
--
-- 于是这条规则落在**服务层的可售性校验端点**上（`GET .../sellable-check`），并且遵守
-- 4.1.2.2 立下的同款纪律：**「查不到」与「确实不可售」必须在类型上分得开** ——
-- 上游不可达时报 503 并说明读不到，绝不返回「全部可售」冒充成功。
--
-- DB 层只负责它**能**负责的那半句：组件数量恒 > 0、每个组件在该绑定下只出现一行、
-- 已关闭绑定的组件是历史不可改写。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本表**无任何金额列**（组件映射不碰钱）。唯一的数值列是
--   * `quantity numeric(19,6)` —— 数量，AGENTS.md 一律 NUMERIC(19,6)；
--     「0.5 米挂绳 + 2 个挂钩」是真实场景，int 会逼运营把它写成「1 件」，
--     而 07 库存会照着这个假数扣（与 catalog V23 bundle_component 同一理由）。
-- **无 float / double 一列**。
--
-- ---------------------------------------------------------------------------
-- 五、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 触发器函数必须在建表之前定义
-- ---------------------------------------------------------------------------

-- 谁建的：服务层写不出「谁改的」，由 DB 从 app.actor 盖上（与 V176 同一手法）
CREATE OR REPLACE FUNCTION stamp_listing_bundle_binding_actor() RETURNS trigger
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

-- 同一条映射至多一个当前生效的套装版本：给出可读原因（并发由部分唯一索引挡）
CREATE OR REPLACE FUNCTION guard_listing_bundle_binding_current() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.effective_to IS NULL AND EXISTS(
       SELECT 1 FROM listing_bundle_binding b
        WHERE b.tenant_id=NEW.tenant_id AND b.mapping_id=NEW.mapping_id
          AND b.id IS DISTINCT FROM NEW.id AND b.effective_to IS NULL) THEN
    RAISE EXCEPTION 'Listing mapping already has a current bundle version; retire it before opening a new one'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 绑定的身份与载荷不许就地改写；当前绑定只允许被「关闭」，不允许复活或改期
CREATE OR REPLACE FUNCTION guard_listing_bundle_binding_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION 'A retired bundle binding is history and must not be rewritten' USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.mapping_id IS DISTINCT FROM OLD.mapping_id
     OR NEW.bundle_version_id IS DISTINCT FROM OLD.bundle_version_id
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION 'Bundle binding identity and payload must not be rewritten in place' USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NULL AND NEW.effective_to IS NOT NULL
     AND NEW.effective_to < OLD.effective_from THEN
    RAISE EXCEPTION 'effective_to must not be earlier than effective_from' USING ERRCODE='23514';
  END IF;
  -- 当前绑定（effective_to 仍为 NULL）不许改生效起点与变更原因：
  -- 「从什么时候开始用这一版」一旦被就地改写，历史就答不出来了
  IF OLD.effective_to IS NULL AND NEW.effective_to IS NULL
     AND (NEW.effective_from IS DISTINCT FROM OLD.effective_from
          OR NEW.change_reason IS DISTINCT FROM OLD.change_reason) THEN
    RAISE EXCEPTION 'A current bundle binding can only be retired, never re-dated' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 🔴 蓝图加粗句「组件数量必须大于 0」的可读原因（CHECK 已经挡住，这里给人话）
CREATE OR REPLACE FUNCTION guard_listing_bundle_component_quantity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.quantity IS NULL OR NEW.quantity <= 0 THEN
    RAISE EXCEPTION 'Bundle component quantity must be greater than 0（组件数量必须大于 0：数量为 0 的组件会让 07 库存扣减出一条 0 扣减，两边都答不出这个组件到底要不要）'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 组件只能挂在**当前生效**的绑定上：已关闭绑定是历史，往里补组件等于改写历史
--
-- 🔴 「绑定不存在」与「绑定存在但已退役」必须分开判，所以这里用 `FOUND` 而不是
-- `retired IS NULL`：当前生效的绑定 effective_to **本来就是 NULL**，
-- 用 IS NULL 当「不存在」会把每一个正常的新组件都误判成「绑定不存在」。
CREATE OR REPLACE FUNCTION guard_listing_bundle_component_binding_open() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  retired timestamptz;
BEGIN
  SELECT b.effective_to INTO retired
    FROM listing_bundle_binding b
   WHERE b.tenant_id=NEW.tenant_id AND b.id=NEW.binding_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Bundle binding % does not exist for this tenant', NEW.binding_id USING ERRCODE='23503';
  END IF;
  IF retired IS NOT NULL THEN
    RAISE EXCEPTION 'Bundle components of a retired binding are history and must not be added (binding retired at %)', retired
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- 已关闭绑定的组件是历史：就地改写会让「当时这一版是几件」无从查证
CREATE OR REPLACE FUNCTION guard_listing_bundle_component_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  retired timestamptz;
BEGIN
  SELECT b.effective_to INTO retired
    FROM listing_bundle_binding b
   WHERE b.tenant_id=OLD.tenant_id AND b.id=OLD.binding_id;
  IF retired IS NOT NULL THEN
    RAISE EXCEPTION 'Bundle components of a retired binding are history and must not be rewritten' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------
-- listing_bundle_binding · 平台变体的套装映射 → 指向哪个内部套装版本（当前 + 历史）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_bundle_binding (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 🔴 写入前提：只有 4.1.2.1 的生效套装映射才有组件可言。
  --    复合 FK 带 tenant_id ⇒ 别的租户 / 别的商品挂不上这一行。
  mapping_id          uuid NOT NULL,
  -- 🔴 catalog 3.5.1 套装版本：**逻辑 ID**，跨服务不建 FK、不复制 catalog 任何一列。
  --    「平台变体与不同套装版本对应」= 同一 mapping 下换一版就是新的一行（第二节）。
  bundle_version_id   uuid NOT NULL,
  -- 半开区间 [effective_from, effective_to)；effective_to IS NULL = 当前生效
  -- （与 4.1.2.2 的 listing_mapping_version 同一套纪律）
  effective_from      timestamptz NOT NULL,
  effective_to        timestamptz,
  change_reason       varchar(500) NOT NULL DEFAULT '',
  -- 由 DB 触发器从 app.actor 盖上（服务层写不出「谁换的版」）
  actor_id            uuid,
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 半开区间不允许为空区间（等于「从不生效」）
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  FOREIGN KEY(tenant_id, mapping_id) REFERENCES listing_mapping(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_bundle_binding IS
  '4.1.2.3 · 平台变体的套装映射当前指向哪个内部套装版本（bundle_version_id 是 catalog 的逻辑 ID，不建 FK）。换版生成新行，不就地改。';

-- 至多一个当前生效：并发写入由部分唯一索引挡住
CREATE UNIQUE INDEX listing_bundle_binding_current
  ON listing_bundle_binding(tenant_id, mapping_id) WHERE effective_to IS NULL;

CREATE INDEX listing_bundle_binding_mapping    ON listing_bundle_binding(tenant_id, mapping_id, effective_from DESC);
CREATE INDEX listing_bundle_binding_version     ON listing_bundle_binding(tenant_id, bundle_version_id);
CREATE INDEX listing_bundle_binding_recent     ON listing_bundle_binding(tenant_id, created_at DESC, id);

CREATE TRIGGER listing_bundle_binding_actor   BEFORE INSERT ON listing_bundle_binding
  FOR EACH ROW EXECUTE FUNCTION stamp_listing_bundle_binding_actor();
CREATE TRIGGER listing_bundle_binding_current BEFORE INSERT OR UPDATE ON listing_bundle_binding
  FOR EACH ROW EXECUTE FUNCTION guard_listing_bundle_binding_current();
CREATE TRIGGER listing_bundle_binding_frozen  BEFORE UPDATE ON listing_bundle_binding
  FOR EACH ROW EXECUTE FUNCTION guard_listing_bundle_binding_frozen();

-- ---------------------------------------------------------------------------
-- listing_bundle_component · 组件映射记录（逐组件 SKU 与数量）
-- ---------------------------------------------------------------------------
-- 「组件数量必须大于 0」是蓝图加粗句，也是本表唯一不能协商的一条。
CREATE TABLE listing_bundle_component (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  binding_id          uuid NOT NULL,
  -- 🔴 catalog 03 的 SKU：**逻辑 ID**，跨服务不建 FK。
  --    写入时经 catalog 授权 API 校验「这个 SKU 确实属于这一版套装」。
  component_sku_id    uuid NOT NULL,
  -- 每卖 1 件平台套装需要这个组件多少。必须 > 0。
  quantity            numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- 组件角色是自由文本（主机 / 配件 / 赠品…），只给人看，不参与任何判定
  role                text NOT NULL DEFAULT '',
  note                text NOT NULL DEFAULT '',
  version             bigint NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一绑定下同一个组件 SKU 只能有一行：两行时「这个组件要几件」无法回答，
  -- 而 07 库存预占只能取一列（与 catalog V23 bundle_component 同一理由）
  UNIQUE(tenant_id, binding_id, component_sku_id),
  FOREIGN KEY(tenant_id, binding_id) REFERENCES listing_bundle_binding(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_bundle_component IS
  '4.1.2.3 · 逐组件 SKU 与数量。quantity 必须 > 0（蓝图加粗句）。component_sku_id 是 catalog 的逻辑 ID，不建 FK。';

CREATE INDEX listing_bundle_component_binding ON listing_bundle_component(tenant_id, binding_id);
CREATE INDEX listing_bundle_component_sku     ON listing_bundle_component(tenant_id, component_sku_id);
CREATE INDEX listing_bundle_component_recent   ON listing_bundle_component(tenant_id, created_at DESC, id);

CREATE TRIGGER listing_bundle_component_quantity BEFORE INSERT OR UPDATE ON listing_bundle_component
  FOR EACH ROW EXECUTE FUNCTION guard_listing_bundle_component_quantity();
CREATE TRIGGER listing_bundle_component_open    BEFORE INSERT ON listing_bundle_component
  FOR EACH ROW EXECUTE FUNCTION guard_listing_bundle_component_binding_open();
CREATE TRIGGER listing_bundle_component_frozen  BEFORE UPDATE ON listing_bundle_component
  FOR EACH ROW EXECUTE FUNCTION guard_listing_bundle_component_frozen();

-- ---------------------------------------------------------------------------
-- 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 V171 / V176 / V182 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_bundle_binding','listing_bundle_component'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围：组件映射的可见性随 listing 的负责人 / 部门收敛（与 V176 / V182 同构）
CREATE POLICY data_scope ON listing_bundle_binding AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_bundle_binding.tenant_id AND m.id=listing_bundle_binding.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_mapping m JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE m.tenant_id=listing_bundle_binding.tenant_id AND m.id=listing_bundle_binding.mapping_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_bundle_component AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_bundle_binding b
                 JOIN listing_mapping m ON m.tenant_id=b.tenant_id AND m.id=b.mapping_id
                 JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE b.tenant_id=listing_bundle_component.tenant_id AND b.id=listing_bundle_component.binding_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_bundle_binding b
                 JOIN listing_mapping m ON m.tenant_id=b.tenant_id AND m.id=b.mapping_id
                 JOIN listing l ON l.tenant_id=m.tenant_id AND l.id=m.listing_id
             WHERE b.tenant_id=listing_bundle_component.tenant_id AND b.id=listing_bundle_component.binding_id
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

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_bundle_binding  TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_bundle_component TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V171 / V176 / V182 同口径）。
REVOKE ALL ON listing_bundle_binding  FROM starcon_app;
REVOKE ALL ON listing_bundle_component FROM starcon_app;
