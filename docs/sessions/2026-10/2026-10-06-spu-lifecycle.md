# 2026-10-06 交付 3.1.1.3 SPU 生命周期（catalog）

> 蓝图原文：「状态不是自由文本，只能按合法转换矩阵流转」。这一轮把 SPU 状态从
> 四值自由文本改成五态生命周期，并把批量规则做成真实求值引擎 + 明确的投影接入边界。

## 决策摘要

**状态域必须迁移，不能在四值域上打补丁。**

`V1` 的 `spu.status` 是 `DRAFT/ACTIVE/INACTIVE/ARCHIVED`，与蓝图要求的
草稿 → 待上架 → 在售 → 停售 → 停产 不符。四值域无法表达「待上架」这个蓝图明确要求的状态，
也无法区分「停售（可恢复）」和「停产（终态）」——`INACTIVE` 和 `ARCHIVED` 的差别是
语义上的可逆性，值域本身承载不了。因此 `V4__spu_lifecycle.sql` 直接换域并给出前向映射：

| 旧值       | 新值           | 语义                 |
| ---------- | -------------- | -------------------- |
| `DRAFT`    | `DRAFT`        | 草稿，不变           |
| `ACTIVE`   | `ON_SALE`      | 在售                 |
| `INACTIVE` | `SUSPENDED`    | 停售（仍可恢复上架） |
| `ARCHIVED` | `DISCONTINUED` | 停产（终态）         |

迁移用 `pg_constraint` 动态查找并删除引用 `ACTIVE` 的 CHECK，而不是硬编码约束名，
再 UPDATE 再加新 CHECK。既有数据零丢失，无需人工干预。

**转换矩阵是数据，不是 if 分支。**

`SpuLifecycle` 用 `Map<SpuLifecycle, List<Transition>>` 显式建模每一条 (from, to) 出边，
每条边自带三个属性：是否强制原因、是否需要前置条件、人类可读的动作说明。
非法流转由 `transitionTo` 抛错，缺原因由 `check` 拒绝，前置条件由 `readySkuCount` 校验。
矩阵同时驱动前端渲染——前端下拉直接来自 `GET /products/{id}/lifecycle` 的 `transitions`，
不在前端硬编码一份状态表，两边不可能漂移。

最终矩阵（8 条边，全部强制填原因）：

```
DRAFT         → PENDING_LISTING  需至少一个已启用且必填齐备的 SKU
DRAFT         → DISCONTINUED    作废草稿
PENDING_LISTING → ON_SALE        需至少一个已启用且必填齐备的 SKU
PENDING_LISTING → DRAFT          退回草稿补资料
ON_SALE       → SUSPENDED       停售下架
ON_SALE       → DISCONTINUED    直接停产
SUSPENDED     → ON_SALE         恢复在售（需齐备 SKU）
SUSPENDED     → DISCONTINUED    停产
DISCONTINUED  → （终态，无出边）
```

**资料编辑接口不再接受状态改写。**

原来 `PUT /products/{id}` 的 body 带 `status`，可以绕过任何规则直接改状态。
现在更新时若携带与当前不同的状态，直接拒绝并指向生命周期端点。状态唯一入口是
`POST /products/{id}/lifecycle`。这是「状态不是自由文本」的字面执行。

**批量规则：完整实现求值逻辑，但对未接入的投影显式拒绝。**

蓝图给的例子是「有库存且 30 天无销量」，但库存属 07 库存（D3）、销量属 13 分析（D7），
两个服务都还不存在。这里做了明确切分：

- 指标枚举 `LifecycleMetric` 每个都标注事实归属（catalog / 库存 / 分析 / 刊登）
- catalog 自有指标（`SKU_COUNT`、`ACTIVE_SKU_COUNT`、`READY_SKU_COUNT`）走 jOOQ 真实求值
- 跨服务指标必须经 `LifecycleProjectionPort` 读取；当前实现 `UnwiredProjectionPort`
  返回 `available=false`，规则求值直接返回 **422** 并说明「上游投影未接入 + 归属哪个服务」
- 规则定义本身**允许保存**（配置完整、有价值），拒绝的是**执行与试算**

`GET /catalog/lifecycle/metrics` 如实暴露每个指标的可用性与原因，前端在规则编辑器里把
未接入指标置灰并显示说明。这样等 07/13 落地后只需替换端口 Bean，规则与引擎零改动。

**试算用 (updated_at, id) 复合游标，不做静默截断。**

规则要扫全量候选 SPU。单页返回 `hasMore` + `nextCursor`，前端可续扫；
`run` 内部按游标循环直到扫完。测试里专门验证「小页长走完整条游标链，结果与单页大扫描一致」，
防止分页实现出现漏扫或重复。

## 关键 trade-off

| 选择                        | 放弃的方案                                   | 原因                                                             |
| --------------------------- | -------------------------------------------- | ---------------------------------------------------------------- |
| 换掉四值状态域              | 在 `ACTIVE` 上叠一层「是否上架中」标记       | 蓝图要求五态；标记位方案会让状态语义分裂到两个字段               |
| 转换矩阵建模为数据          | service 里写一串 `if (from == X && to == Y)` | 矩阵要同时驱动前端渲染，数据化才可能不漂移                       |
| 未接入投影返回 422 而非禁用 | 保存规则时就不允许选库存/销量指标            | 上游落地后指标立刻可用，提前禁用会让规则配置不完整、且看不出边界 |
| 试算分页暴露游标            | 后台一次性扫完直接返回全部命中               | 候选可能上千，一次性返回会撑爆响应；游标也让「扫了多少」可核对   |
| 规则目标不含 `DRAFT`        | 允许规则把商品批量退回草稿                   | 退回草稿是资料问题，逐个处理更合适，规则只做状态机允许的批量动作 |
| 权限复用 `catalog.spu.*`    | 新增 `catalog.lifecycle.*` 权限页            | 生命周期就是 SPU 状态，换权限页会让角色配置表无谓膨胀            |

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V4__spu_lifecycle.sql`（新增）

**后端**

- `backend/services/catalog/src/main/java/com/starcon/catalog/SpuLifecycle.java`（新增，转换矩阵）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LifecycleMetric.java`（新增，指标与事实归属）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LifecycleProjectionPort.java`（新增，投影端口）
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnwiredProjectionPort.java`（新增，当前未接入实现）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LifecycleService.java`（新增，jOOQ 显式 SQL）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LifecycleController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（改，追加 DTO）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogService.java`（改，状态域分流 + 拒绝状态改写）

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/SpuLifecycleIntegrationTest.java`（新增，11 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/CatalogIntegrationTest.java`（改，跟随新状态域）

**契约（codegen 产物）**

- `packages/api-contracts/src/catalog.ts`、`packages/api-contracts/src/catalog.openapi.json`

**前端**

- `apps/web/src/components/catalog/spu-lifecycle.tsx`（新增）
- `apps/web/src/components/catalog/lifecycle-model.ts`（新增，纯函数）
- `apps/web/src/components/catalog/lifecycle-model.test.ts`（新增，9 例）
- `apps/web/src/components/catalog/catalog-workspace.tsx`（改，接入生命周期段）
- `apps/web/src/components/catalog/product-editors.tsx`（改，状态下拉改只读）
- `apps/web/src/components/common/fields.tsx`（改，状态标签表补五态）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 3.1.1.3 一行 + `sync.mjs` 重算统计）

## 验证结果

- `cd backend && ./mvnw verify` → `BUILD SUCCESS`，identity 13 + catalog 22 + channel 43 = **78 tests 全绿**
- `pnpm typecheck` → 3 个包全部通过
- `pnpm lint` → `✔ No ESLint warnings or errors`
- `pnpm test` → 13 个文件 **75 tests 通过**（含新增 lifecycle-model 9 例）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 构建成功，`/products` 205 kB
- `node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`

## 租户隔离（三断言均在 Testcontainers PostgreSQL 16 上实跑）

1. 两租户互不可见：租户 B 读不到租户 A 的 SPU 历史、规则，读不到 SPU 本体（404）
2. 伪造 tenant header 无效：`X-Tenant-Id` 指向 A 但 `Authorization` 是 B → 404；反之正常返回
3. 连接池归还后不串租户：连接池上限设为 1，5 轮交替读写两租户数据无串扰

另外验证：三张新表 `relrowsecurity` 与 `relforcerowsecurity` 均为真，运行时角色
`rolsuper=false`、`rolbypassrls=false`。

## 后续待办

- **投影接入点**：07 库存（D3）、13 分析（D7）落地后，各自实现 `LifecycleProjectionPort`
  并替换 `UnwiredProjectionPort` Bean。届时「有库存且 30 天无销量」规则即可真实执行。
- **下游联动**：停售是否联动下架平台商品由 04 刊登决定。catalog 已发
  `SpuLifecycleChanged` outbox 事件（含 from/to/reason），04 订阅后补联动逻辑。
- **批量规则调度**：本模块交付的是手动试算与执行。定时自动执行属于调度域，
  需要 18 集成与自动化卷的事件/调度设施，当前无对应上游，未凭空造调度器。
- 剩余 03 卷模块：`3.1.2.1` SKU 列表及其后，按台账顺序推进。
