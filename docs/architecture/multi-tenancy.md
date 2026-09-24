# 多租户架构

> Status: Stable — 决策已锁，详见 ADR
> 关联 ADR: `0006-multi-tenancy-strategy.md`（待写）

## 目标

- 单实例服务**所有**租户，运维成本最低
- 数据**强隔离**，零泄漏
- 支持**按租户弹性扩容**（热点租户资源隔离）
- 满足跨境数据合规（不同国家数据可下沉到本地区域）

## 模型选择

采用 **"Shared Database, Shared Schema with Row-Level Security (RLS)"**：

```
                ┌─────────────────────────────┐
                │       Single Postgres       │
                │  ┌───────────────────────┐  │
                │  │  orders               │  │
                │  │  id | tenant_id | ... │  │  ← 所有表带 tenant_id
                │  └───────────────────────┘  │
                │                             │
                │  RLS Policy:                │
                │  USING (tenant_id =         │
                │         current_tenant())   │
                └─────────────────────────────┘
```

## 为什么不是别的

| 方案 | 优点 | 缺点 | 是否采纳 |
|---|---|---|---|
| **每租户一库**（DB-per-tenant） | 物理隔离最强 | 跨租户分析难；DB 数量爆炸；运维成本高 | ❌ |
| **每租户一 schema** | 中等隔离，迁移可控 | 跨租户查询 / 分析难；连接数随租户增长 | ❌ |
| **Shared Schema + RLS** ✅ | 弹性最好；分析友好；连接池共享 | 隔离靠 DB 层保证，任何 SQL 失误都致命 | ✅（本项目） |
| **每租户一服务实例** | 完全隔离 | 部署爆炸 | ❌（但热点租户可走此方案，详见 §4） |

## 实现要点

### 1. 强制带 `tenant_id`

- 所有业务表**强制**带 `tenant_id` 列（NOT NULL + INDEX）
- DB migration 阶段用 trigger 校验：插入/更新时若 `tenant_id IS NULL` 直接抛错
- CI lint 扫 SQL 文件，发现新表不带 `tenant_id` 直接拒绝合入

### 2. RLS Policy 模板

```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
  USING (tenant_id = current_setting('app.current_tenant')::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant')::uuid);
```

### 3. 租户上下文注入

每个请求生命周期：

```ts
// apps/api/src/shared/tenant.middleware.ts
export async function tenantContext(req, next) {
  const tenantId = resolveTenant(req); // JWT / X-Tenant-Id header
  await db.execute(`SET LOCAL app.current_tenant = '${tenantId}'`);
  return next();
}
```

`packages/tenancy/` 提供 `getCurrentTenant()` / `withTenant(id, fn)` 工具，所有业务代码走这里，**禁止任何模块硬编码 tenant**。

### 4. 跨租户操作白名单

只有以下场景可绕过 RLS：
- SaaS 内部运营后台 (`apps/admin`) 的全租户查询
- 异步报表 / BI ETL 任务
- Billing 订阅计费

这些场景必须走专用 service account (`role = 'starcon_internal'`) + 应用层白名单中间件。

### 5. 热点租户隔离

当某租户订单量占总量 > 30% 时，触发"租户隔离"流程：
- 单独 K8s 节点池
- 独立 Redis namespace
- Kafka 单独分区
- DB 仍共享，但查询路由到专用 connection pool

### 6. 数据合规

- 欧盟客户数据可下沉到 EU 区域（K8s 多 cluster + DB 跨区域只读副本）
- 数据出境走 `docs/compliance/data-export.md` 流程

## 自检清单（任何新模块上线前必过）

- [ ] 所有新表带 `tenant_id NOT NULL`
- [ ] RLS policy 已建立
- [ ] 没有硬编码 tenant_id 的代码路径
- [ ] 缓存 key 强制带 tenant 前缀（`packages/tenancy` 提供 helper）
- [ ] Kafka 消息 header 带 `x-tenant-id`
- [ ] E2E 测试覆盖：跨租户访问直接 403

## 故障模式与降级

| 故障 | 影响 | 降级策略 |
|---|---|---|
| 租户上下文未注入 | 整个查询返回空集（fail-closed） | RLS 拒绝访问，比泄漏安全 |
| RLS 误开 | 跨租户数据可见 | 监控：定期跑跨租户探针查询，发现即告警 |
| DB connection pool 满 | 全租户受影响 | 按租户配额分池，热点租户单独池 |