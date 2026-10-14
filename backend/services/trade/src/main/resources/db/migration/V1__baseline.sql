-- trade 服务引导基线。
--
-- 09 订单履约（蓝图 09 卷「后端归属」）：`trade` 服务拥有订单与售后单据；
-- 订单数据来自 channel 同步、库存动作由 warehouse 执行，两者都通过**授权 API / event**
-- 与本服务交互。本迁移是 trade 的第一版，也是 27 个卷 09 模块落地前的唯一版本。
--
-- ---------------------------------------------------------------------------
-- 本迁移**没有**做什么（刻意为之，不是遗漏）
-- ---------------------------------------------------------------------------
--   * **不建订单表、不建履约状态机、不建分仓规则、不建任何卷 09 的业务表。**
--     9.1.1 订单列表 / 9.3.2.1 分仓规则等 27 个 L4 模块当前全部「未开发」，
--     它们各自会在后续车道里带自己的迁移（V2、V3……），表结构必须由那条车道按蓝图落，
--     不能由服务引导车道替它们猜。
--   * **不建跨服务外键、跨库视图或任何读上游库的 SQL。** sku 属 catalog、shop 属 channel、
--     warehouse 属 warehouse（07/08），四个服务各有独立数据库，本库对它们只存逻辑 ID +
--     必要快照（与 warehouse V1 的 sku_ref / legal_entity_ref 同一纪律）。
--   * **不写种子数据。** 平台码表归 channel，租户/法人归 identity。
--
-- ---------------------------------------------------------------------------
-- tenant 上下文：本库的隔离前提
-- ---------------------------------------------------------------------------
-- `app.current_tenant` 是一个**事务内局部**（transaction-local）的 GUC，
-- 由 backend/libraries/tenancy 的 TenantTransactions 在事务里用
-- `set_config('app.current_tenant', ?, true)` 绑定，第三个参数 true 就是「事务结束自动撤销」。
--
-- 因此本库**刻意不建 set_tenant / clear_tenant 之类的 SQL 函数**：
-- 既有四个服务（identity / catalog / channel / warehouse）都没有建，绑定点只有
-- TenantTransactions 一处；凭空加一个没人调用的函数只会让人以为存在第二条绑定路径，
-- 而那正是多租户最不能有的分叉。清理也不需要函数 —— 提交或回滚时 GUC 自动回到未设置状态。
--
-- 客户端的 `X-Tenant-Id` 头进不来也改不动它：HTTP 头到「可信租户」的解析只能来自
-- 已认证的成员关系（identity 的会话授权），不信任客户端自称，这条由
-- TradeTenancyIntegrationTest 实跑三断言证明。

-- ---------------------------------------------------------------------------
-- 角色
-- ---------------------------------------------------------------------------
-- 角色可能已由运维预置（deploy/docker/catalog-init/01-databases.sh），因此只在缺失时创建，
-- 保证迁移可重复执行（与 channel V1 / warehouse V1 同口径）。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='trade_app') THEN CREATE ROLE trade_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO trade_app;

-- ===========================================================================
-- 1. trade_tenancy_probe · 租户隔离的可验证载体
-- ===========================================================================
-- **这张表不是业务表。** 它唯一的作用是让 trade 的租户隔离在引导阶段就**可证明**：
-- 服务接通了但没有任何表时，「RLS 生效了」是一句没人验证过的话；有了这张表，
-- 引导集成测试才能实跑 AGENTS.md 要求的三条断言（两租户互不可见 / 伪造租户头无效 /
-- 连接池归还后不串租户），并且是真的跑在 PostgreSQL 16 的 FORCE RLS 上。
--
-- 它**不承载任何卷 09 语义**：没有订单号、没有 SKU 行、没有履约状态。
-- 第一条 9.x 模块车道落地时应当显式决定保留还是删除 —— 保留它就得给它一个真实用途，
-- 删掉它则该车道用自己的表重跑一遍三断言。不要让它一直以「占位表」的身份留在生产 schema 里。
CREATE TABLE trade_tenancy_probe (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  label varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  -- 主键是**全局** uuid，租户内再唯一一次：与 warehouse V1 的 warehouse / sku_ref 同一形状，
  -- 这样两个租户不会因为撞主键而互相阻塞写入（隔离要靠 RLS，不是靠撞键）。
  UNIQUE(tenant_id,id),
  CHECK(btrim(label)<>'')
);
CREATE INDEX trade_tenancy_probe_list ON trade_tenancy_probe(tenant_id,created_at);

-- ===========================================================================
-- 2. 多租户：RLS + 授权
-- ===========================================================================
-- 与 warehouse V1 第 9 节 / channel V1 同一套写法，**一字不改**：
-- ENABLE + FORCE（FORCE 是为了让表 owner 也受策略约束，否则运行角色一旦被误授 owner
-- 就等于 RLS 失效），策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
--
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
-- 这条语义由 TradeTenancyIntegrationTest 实跑钉住，改动 nullif 必须同时改测试。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['trade_tenancy_probe'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- trade_app 是运行角色：全表 RLS 隔离后可读写。
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO trade_app;
REVOKE ALL ON flyway_schema_history FROM trade_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 channel V1 / warehouse V1 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;