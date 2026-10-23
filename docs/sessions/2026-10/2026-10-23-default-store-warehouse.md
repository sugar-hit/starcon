# 1.4.2.2 默认店铺与仓库（阶段 D7 · L3 锚点 1.4.2）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/1-4-2-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/1.4.2.2`）
- **基线**：`integration/module-dev` @ `645e26e5`
- **迁移版本号**：`V341__*.sql` **预分配但未占用**（本模块不建表，理由见下）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/01-dashboard-and-portal.md`）：

> ### 1.4.2.2 默认店铺与仓库
>
> - **职责**：设定默认进入的店铺与默认操作的法人/仓库，减少每次进入的重复选择。
> - **输入**：用户选择；用户有权限的店铺/仓库集合。
> - **输出**：默认上下文，注入后续页面初始筛选。
> - **关系**：上游 = 02 店铺、08 仓库、17 权限；下游 = 09、07、13 各页面的默认筛选。**默认值必须在权限范围内**，权限变更后自动失效并回落。

本车道落地要点：

1. **不建表（V341 特意留空）。** 默认上下文是**纯个人偏好**，落在 1.4.1.2 已建的
   `user_preference` 表（identity V336）里，用本模块自己的 page_key `default-context`。
   同批 1.4.1.1 / 1.4.2.1 两条车道用同一张表 ⇒ **零 ALTER**（任何无 DEFAULT 的 ADD COLUMN
   都会让对方的 INSERT 违约）。**没有为了「用掉版本号」造一张没有业务事实的表。**
2. **零改动复用 `UserPreferencePayloadRules`。** 它的校验是**通用形状**校验（不是按 page_key 分派），
   本模块的载荷 `{shopId, legalEntityId, warehouseId}` 直接被它覆盖；模块自己的语义规则
   （三个维度各至多一个 UUID、显式 null = 清空）放在**新建的** `DefaultContextRules` 里，
   不去抢 1.4.2.1 可能要碰的同一个文件。
3. **候选集只有一个来源，本服务不造第二套权限真相源。** 店铺来自 channel `GET /channel/shops`、
   仓库来自 warehouse `GET /warehouse/facilities`、法人来自 identity 自己的 `legal_entity` 档案。
   「这个 id 在不在你的可见集里」**只有那次查询能回答**，前端与服务端都不另算一遍。
4. **「默认值必须在权限范围内」的落地是三段**：写入前逐条比对候选集（不在 ⇒ 400；
   **来源读不到 ⇒ 503**，读不到 ≠ 不在权限范围内）；读时重新核对（不在清单里 ⇒ 判为**失效**、
   不再生效，并**回落到租户自己的默认仓** `is_default`）⇒ 蓝图说的「权限变更后自动失效并回落」，
   **不需要任何同步任务**；显式清空的维度（键在、值为 null）与「从没设过」在库里可区分，
   前者不再被回落覆盖。

## 关键 trade-off

- **卷 17 的权限范围到底有没有权威来源？** 任务书说「卷 17 整个服务未并入集成头，没有可用的权威来源」。
  实查结论**不一样**：蓝图 README 的服务归属表写明 **17.3 权限管理归 identity**，
  `role_assignment` / `role_assignment_scope`（V47）**就在本仓**。但本模块**仍然没有自己算范围** ——
  因为 channel 的 `shop` 表上有 RESTRICTIVE `data_scope` 策略（`channel_scope()` 按
  `app.actor` / `app.department` / `app.grants` 过滤），**上游授权端点返回的就是已经过范围闸的可见集**；
  再算一遍就是第二套真相。warehouse 的 `warehouse` 表**只有 isolation 策略、没有 data_scope**
  ⇒ 仓库维度目前是**租户级**可见，这条**在能力声明里如实写明**，不假装有范围过滤。
- **仓库清单为什么不用 `/warehouse/warehouses`？** 它的权限闸写的是 `inventory.read`，
  而 identity 侧只登记了 `warehouse.inventory.read`（V8 注册的是 `warehouse.` 前缀一族码），
  两者对不上 ⇒ 用它会恒定 403，仓库维度变成一个**永远空**的选择器。改用 `GET /warehouse/facilities`
  （`warehouse.facilities.read`，V69 注册、OWNER 默认带），它同样是仓库档案的真实清单，
  还带 `is_default` 标记 —— 「默认仓库」的回落来源就是它。**权限码登记不一致是仓库服务的既有缺口，
  本模块只绕开它并如实说明，没有改别的服务的权限码。**
- **法人清单为什么读 identity 自己的表，而不是 warehouse 的 `legal_entity_ref`？**
  法人主数据归 17.1（identity），`legal_entity_ref` 是 warehouse 侧登记的**引用副本**。
  用副本当权威会出现两个真相源。代价是：`legal_entity` 表目前**只有显式 `tenant_id` 谓词、
  没有行级 RLS**（本模块未改该表、未加跨服务外键）—— 缺口归 17.1，已写进能力声明的边界。
- **端点为什么自己开而不是复用 `user-preferences`？** 因为本模块要多返回三样东西：
  **生效后的**上下文（失效项已剔除并回落）、**候选集**、**逐维度来源状态**。
  通用偏好端点只回传原始载荷，前端拿到失效项也不知道该怎么回落。

## 文件清单

后端（`backend/services/identity`，全部新建，无既有文件改动）：

- `src/main/java/com/starcon/identity/DefaultContextModels.java` —— 请求/响应形状
- `src/main/java/com/starcon/identity/DefaultContextRules.java` —— 载荷形状的纯函数校验（page_key `default-context`）
- `src/main/java/com/starcon/identity/DefaultContextService.java` —— 读/写/失效/回落/候选集/能力声明
- `src/main/java/com/starcon/identity/DefaultContextController.java` —— `/identity/default-context` 五个端点
- `src/main/java/com/starcon/identity/DefaultContextShopDirectoryPort.java` —— channel 店铺只读端口（Http / Unwired 两态）
- `src/main/java/com/starcon/identity/DefaultContextWarehouseDirectoryPort.java` —— warehouse 仓库只读端口（翻完分页）
- `src/main/java/com/starcon/identity/DefaultContextDirectoryConfiguration.java` —— 两个端口的装配（按 `CHANNEL_URL` / `WAREHOUSE_URL` 有无选实现）
- `src/test/java/com/starcon/identity/DefaultContextTestBase.java` —— 真 HTTP 替身（按 bearer 分租户）
- `src/test/java/com/starcon/identity/DefaultContextIntegrationTest.java` —— 业务行为 12 例
- `src/test/java/com/starcon/identity/DefaultContextTenancyIntegrationTest.java` —— 多租户三断言 4 例

前端（`apps/web/src/app/settings/default-context`，全部新建）：

- `page.tsx` —— 路由 `/settings/default-context`
- `default-context-model.ts` —— 类型取自 OpenAPI 契约 + 纯函数 + 接口调用
- `default-context-workspace.tsx` —— 真实工作区（三个下拉 / 摘要 / 能力声明 / 三态）
- `default-context-model.test.ts` —— 15 例
- `default-context-api-proxy.test.ts` —— 5 例（双向钉住白名单）

共用文件（**只改自己那几处**）：

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立的 services[] 元素**（prefix `identity/`，
  锚在**数组末尾** `sync-health` 之后 ≈ 第 9880 行；与同批 1.1.1.3 / 1.4.2.1 的锚点
  （`user-preferences` 附近 ≈ 第 1352 行）**相距 8500 行以上**；既有条目一字未动、也不重排）
- `packages/api-contracts/src/contracts/identity.ts` + `src/identity.openapi.json` —— codegen 产物
  （identity 新增 10 个 `DefaultContext*` 类型，其余 5 个服务逐字节不变）
- `deploy/docker/docker-compose.catalog.yml` —— identity 段补 `CHANNEL_URL` / `WAREHOUSE_URL`
  （不补的话本地 docker 部署里目录端口走 Unwired，页面会如实报「未接通」）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 1.4.2.2 那一行**的状态与证据

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3 packages successful）
- L1 模块闸：`./mvnw -pl services/identity -am test -Dtest='DefaultContext*'` ✅ **16 例全绿**
  （业务 12 + 多租户三断言 4，实跑 Testcontainers PostgreSQL 16、连接池压到 1）
- 前端：`pnpm vitest run apps/web/src/app/settings/default-context` ✅ **20 例全绿**
- 全量 allowlist 回归（改了 api-proxy.ts 必跑）：`pnpm vitest run apps/web/src/app/api` ✅
  **206 文件 / 1178 例全过，零回归**
- `pnpm lint` ✅（只有别的模块的既有 warning）
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ✅

## 后续待办

1. **下游页面的默认筛选注入**（09 订单履约 / 07 库存核算 / 13 利润分析）**不在本车道**：
   它们自己读 `GET /identity/default-context` 取初始筛选，本模块不替它们改查询口径。
2. **`inventory.read` 权限码登记不一致**（仓库服务 263 处调用点用 `inventory.read`，
   identity 只登记了 `warehouse.inventory.read`）—— 归仓库服务 / 17.3 的车道，
   本模块只绕开并在能力声明里写明。
3. **`legal_entity` 缺行级 RLS** —— 归 17.1 法人档案。
4. **warehouse 的 `warehouse` 表没有 data_scope 策略** ⇒ 仓库维度目前是租户级可见；
   等 17.3.2.2 的范围引擎落地后，仓库维度应改为读上游的**范围收口**清单。
