# 17.5.2.1 数据字典

**日期**：2026-10-07 · **车道**：17.5.2.1（阶段 D1–D2，L3 锚点 17.5.2）· **分支**：`feat/17-5-2-1`

## 决策摘要

按蓝图 `docs/product/capability-blueprint/17-org-permission-and-settings.md:242-247` 交付数据字典，
照抄 identity 服务既有写法（jOOQ 显式 SQL、`call()` 会话口径、`identity_outbox` 事件、
变更历史追加不可改）。

**数据分层**：`dict_catalog`（平台出厂字典目录，**不带** `tenant_id`，只授予 SELECT）
+ `dict_item`（租户私有字典项）+ `dict_item_usage`（引用台账）+ `dict_history`（变更历史）。
沿用 V48 业务参数「码表 + 租户档」的分层：目录不出租户、项才出租户，
这样租户往目录里加自己的条目就是「租户级自定义扩展」。

**「被引用后只能停用不能删除」四道防线**（沿用 17.2.2.3 离职模块先例）：
1. 控制器**没有 DELETE 端点**（连 `delete` 权限码都不创建）；
2. 迁移只授予 `SELECT/INSERT/UPDATE`，不授予 `DELETE`；
3. `dict_item_usage.item_id` 外键 `ON DELETE RESTRICT`；
4. `dict_item_delete_guard()` 触发器在数据库层挡掉一切 DELETE。

**「不允许修改已被历史数据引用的编码」**：控制器返回 409，
`dict_item_update_guard()` 触发器兜底（绕过应用直连改也会被拒）。
名称、说明、排序**仍可改** —— 业务含义变了要能改，承载历史数据的那个字符串不能改。

## 关键 trade-off

1. **引用统计的口径：「被引用过」按登记记录是否存在判定，不按当前计数是否大于 0。**
   计数归零只说明当下没人用它，历史单据里还留着它，编码依然锁死、依然不能删。
   `referenceCount`（条数之和）与 `referencingSystems`（来源系统个数）分开给：
   停用一个被引用 9000 条的和从未被引用的字典项，风险完全不同。

2. **跨服务边界：引用台账靠下游经授权 API 登记，identity 不查任何服务的库。**
   AGENTS.md 禁止跨服务数据库访问，所以 `dict_item_usage` 是登记台账而非外键。
   代价是 identity 无法自证「下游真的没有引用」，代价换来的是引用统计**不是假的固定数字**。

3. **下拉选项与历史可读性分离**：`/dict-items/options/{dictCode}` 只给 `ACTIVE`（下游下拉用），
   `/dict-items` 默认**含**停用项（配置页要看得见才能判断「这个值还在用不用」）。
   停用项藏起来等于让用户以为被删了。

4. **停用可逆（可恢复），且保持同一个 id 与同一份引用台账** ——
   删掉重建会换一个 id，把已登记的引用统计与变更历史一起割断。

5. **不改蓝图、不新增 `X-Tenant-Id`**：租户全部来自已认证成员关系，
   沿用 `TenantTransactions` + `set_config('app.current_tenant', …, true)`（事务级，连接归还即失效）。

## 文件清单

| 类型 | 路径 |
| --- | --- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V53__data_dictionary.sql` |
| 端点 | `backend/services/identity/src/main/java/com/starcon/identity/DictionaryController.java` |
| 集成测试 | `backend/services/identity/src/test/java/com/starcon/identity/DictionaryIntegrationTest.java`（15 项） |
| 契约 | `packages/api-contracts/src/{catalog.ts,identity.openapi.json}`（codegen 生成，7 个 `Dict*` DTO） |
| 页面 | `apps/web/src/app/org/dictionary/page.tsx` |
| 工作区 | `apps/web/src/components/iam/dictionary-workspace.tsx` |
| 前端模型 | `apps/web/src/components/iam/dictionary-model.ts` |
| 前端测试 | `apps/web/src/components/iam/dictionary-model.test.ts`（24 项） |
| 导航 | `apps/web/src/components/iam/identity-workspace.tsx`（+1 目的地 +1 可见性判定） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（仅 17.5.2.1 那一行 + sync 派生计数） |

## 端点清单

- `GET /identity/dict-catalog` — 出厂目录 + 各字典本租户条目计数
- `GET /identity/dict-items` — 字典项 + 真实引用统计（可按 `dictCode`/`status`/`search` 过滤）
- `GET /identity/dict-items/options/{dictCode}` — 下拉选项，只给 `ACTIVE`；字典不存在 404
- `GET /identity/dict-items/{id}` · `GET /identity/dict-items/{id}/usage` · `GET /identity/dict-history`
- `POST /identity/dict-items` · `PUT /identity/dict-items/{id}`
- `POST /identity/dict-items/{id}/deactivate` · `POST /identity/dict-items/{id}/reactivate`
- `POST /identity/dict-items/{id}/usage` — 下游登记引用（upsert，`first_referenced_at` 用 `least()` 保住）

## 验证

- `cd backend && ./mvnw verify` → identity 162 项、catalog 全量 0 失败，BUILD SUCCESS
- `pnpm typecheck` 绿 · `pnpm lint` 绿（2 条 warning 在 `variant-combination-workspace.tsx`，非本次改动）
- `pnpm test` 969 → **993**（基线 969 于 `git stash -u` 后实测；+24 为本模块新增，无回归）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `✓ Compiled successfully`，含 `/org/dictionary`
- `node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`

## 后续待办

- **下游接入引用登记**：各业务系统（9 审单 / 11 售后 / 7 库存 / trade）尚未调用
  `POST /dict-items/{id}/usage`。在这些系统接上之前，引用统计会一直是 0 ——
  台账如实反映「没人登记过」，不伪装成「已被大量引用」。
- **17.5.2 L3 锚点路由**：台账「L3 锚点路由」列仍为 `—`，因蓝图 L3 锚点行未写路由；
  按任务要求未改蓝图。页面地址 `/org/dictionary` 记在证据列。
- `dict_catalog.item_kind` 的 CHECK 目前锁死四类（对应蓝图职责点名）；
  蓝图写的是「等」，新增类别需同步放宽 CHECK 并更新 `ITEM_KIND_LABELS`。