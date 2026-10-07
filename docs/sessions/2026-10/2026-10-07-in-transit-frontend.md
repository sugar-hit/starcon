# 7.5.1.2 在途库存 — 前端补齐（硬停机前）

日期：2026-10-07 · 车道 `7.5.1.2` · worktree `.worktrees/7.5.1.2` · 分支 `feat/7-5-1-2`

## 背景

后端（迁移 `V63__warehouse_in_transit_leg.sql` + `InventoryInTransitService` / `Controller`）已在集成头交付，
台账那一行状态是「部分交付（后端已交付并测试全绿；前端页面与前端契约未做）」。
本车道只补前端：页面 + 前端模型 + 白名单 + 契约重新生成 + 测试。

## 后端真实端点（从 Controller 读出来的，不是猜的）

命名空间 `/warehouse/inventory/in-transit`，浏览器只经 `/api/warehouse` 代理：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `` | 清单（真分页 + **不受分页影响**的全量汇总 + 三组下拉词表） |
| GET | `/alerts` | 到达预警（按 ETA 升序） |
| GET | `/netting` | 补货可扣在途净额（目的仓 × SKU） |
| GET | `/{legId}` | 详情（行 + 到达/差异时间线 + 指标口径说明） |
| POST | `` | 登记外部在途腿 |
| POST | `/{legId}/arrive` | 分批到货 |
| POST | `/{legId}/discrepancy` | 差异登记 |

排序白名单 6 键（`EXPECTED_ARRIVE` / `SHIPPED_AT` / `QUANTITY` / `SKU_CODE` / `OVERDUE` / `CREATED_AT`），
净额排序 3 键（`QUANTITY` / `LATEST_ARRIVAL` / `SKU_CODE`），白名单外的键返回 400 —— 下拉只给这几个。

## 关键 trade-off

**1. 契约与后端在两个可空字段上不一致，前端选择整键省略而不是发空串。**
生成的 `InTransitRegisterRequest` 里 `lotId` 与 `expectedArriveAt` 是**非空 `string`**（Jackson record 未标
`@Schema(nullable)`），但后端 `register()` 明确按可空处理（`request.lotId() != null` 分支、ETA 缺省即 `NO_ETA`）。
发 `""` 会被 Jackson 的 UUID / OffsetDateTime 解析挡成 400。
所以本地定义 `RegisterPayload` 把可空字段标成可选、`buildRegisterPayload` **整键省略**；
响应体一律用契约类型。没有用 `any` / 断言绕过。

**2. 前端预检不是替代后端校验，是让「为什么不许提交」当场可读。**
`registerBlockers` / `arriveBlockers` / `discrepancyBlockers` 逐条对齐后端真实校验，
其中「调拨腿不许在这里登记」尤其需要先讲清（两处都能写同一批货会让在途翻倍）。

**3. 登记下拉刻意不含「调拨在途」。**
调拨腿由 7.5.1.1 调拨单的发出动作产生，后端 `register()` 显式拒绝 `transitKind=TRANSFER`。

**4. 逾期档位不落标记，全部从 `overdueLevel` 推导。**
`NO_ETA` 严重度**低于** `ON_TIME`：把「没登记 ETA」算成准点，正是本模块要防的逾期漏算。

## 文件清单

新增
- `apps/web/src/app/inventory/transit/page.tsx`（路由）
- `apps/web/src/components/inventory/inventory-in-transit-model.ts`
- `apps/web/src/components/inventory/inventory-in-transit-model.test.ts`（49 例）
- `apps/web/src/components/inventory/inventory-in-transit.tsx`

修改（均为共用文件，只做与本模块相关的最小改动）
- `apps/web/src/lib/api-proxy.ts`（warehouse 白名单加 `in-transit` 分支 + 中文注释）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行 6 条真实调用点 + 拒绝 5 条未交付子路径）
- `packages/api-contracts/src/catalog.ts`、`warehouse.openapi.json`（**codegen 产物，未手改**）
- `docs/product/capability-blueprint/module-progress.md`（仅本车道那一行 + sync 自动刷新派生计数）

## 验证（实跑，非声称）

- `./mvnw test -Dtest=OpenApiContractTest` → BUILD SUCCESS（4 服务），刷新 `backend/target/openapi`
- `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → 779 DTO
  - warehouse spec：139 → 153 schemas（**+14 全是 `InTransit*`**），paths +6，**0 消失**
  - `catalog.ts`：真新增恰好 14 个 `InTransit*`，**0 消失**；另有 88 行是既有 warehouse 类型被重新排序（非语义变化）
  - `catalog.openapi.json`：零差异
- `pnpm typecheck` → **3/3 成功，零错误**
- `pnpm lint` → **3/3 成功，无新增 error**；warning 全在他人文件
  （`variant-combination-workspace.tsx`、`goods-receipt.tsx`），`in-transit` 相关文件零告警
- 全量 `npx vitest run`（仓库根）→ 提交前 **64 文件 / 1192 例全过**
  - 开工前基线**自己量**：`npx vitest run` → **63 文件 / 1142 例**
  - 差值 **+1 文件 / +50 例**（49 模型 + 1 白名单拒绝用例），**无回归**
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致

## api-proxy 白名单冲突与求并（提交后才发现的坑）

第一次 `land` 后用 `git merge-tree` 预演，发现并入会**在 `apps/web/src/lib/api-proxy.ts` 冲突**：
本分支是从 7.5.1.1 时点的集成头切出来的，而集成头早已被十余条并行车道推进 ——
`cross-entity-transfers` / `stocktake-variances` / `goods-receipts` / `outbound-orders` /
`allocation-policies` / `ownership` / `aging` / `printing` / `labor` / `facilities` / `health`
各自加了 allowlist 分支。本分支那份 `api-proxy.ts` 是**旧副本**，直接并会撞车，
且调度器用的是**裸 `git merge`**（不自动跑 `merge-union.mjs`），撞了就把车道打成 `conflict`。

处理：把 `integration/module-dev` 并进本分支，`api-proxy.ts` 取**集成头版本原样**，
只在其正则里 `transfers(...)` 之后插入本车道那一条 `in-transit` 分支 + 注释。

求并正确性是**可验证**的，不是靠肉眼：
- `npx vitest run apps/web/src/app/api` → **5 文件 / 56 例全过**，含四条车道各自的
  allowlist 用例（catalog 35 例、iam 7 例、warehouse 8 例、`cross-entity-transfer-allowlist` 2 例）。
  这些用例逐条断言各自车道的路径仍被放行，所以「漏掉别人的分支」会被直接测出来。
- `git diff integration/module-dev HEAD -- apps/web/src/lib/api-proxy.ts` → **+7 / -1**，
  即本分支相对集成头**只多出 in-transit 这一条**，没有把别人的分支改回去。
- 再预演 `git merge-tree --write-tree integration/module-dev HEAD` → **0 冲突**。

并入集成头后全量：**78 文件 / 1403 例全过**（比 64/1192 多出来的是随集成头一起并进来的其他车道用例，
不是本车道写的；本车道自己的增量仍是 49 模型例 + 1 白名单拒绝例）。

## 白名单正反断言

放行（**从 `inventory-in-transit-model.ts` 反向抽取**的真实调用点，非照正则抄）：
`in-transit`(GET+POST)、`/alerts`、`/netting`、`/{legId}`、`/{legId}/arrive`(POST)、`/{legId}/discrepancy`(POST)。
写动作显式跑 POST，证明「只放行读」不成立。

拒绝（后端**不存在**这些形状，放行比 404 更糟）：
`/{legId}/cancel`、`/{legId}/arrive/reverse`、`/audit`、`/forecast`、`/alerts/{legId}`。
另保留既有 `reservations` / `adjustments` / `internal-access` 拒绝断言。

## 刻意没做 / 仍存在的问题

1. **侧栏与搜索导航未登记 `/inventory/transit`**。与 7.5.1.1 现状一致（`/inventory/transfers` 也没进
   `packages/ui/src/lib/task-pages.ts`；那里只有蓝图占位条目 `implemented: false`）。
   本车道刻意不改这个共享文件，避免重排他人条目。**这是已知缺口，不是遗漏。**
2. **未跑全量 `./mvnw verify`，未跑 `pnpm --filter @starcon/web build`**（只跑了 warehouse 段契约测试 + 前端三项）。
3. **`land` 只把车道置为 `ready`，真正并入集成头由调度器下一轮执行**（本车道未碰集成分支、未 push）。
   预演已确认并入零冲突。
4. **能力边界**（界面上已如实标注，未假装有）：
   - 到货 / 差异登记是**直接落库的业务事件，不经审批流、不进常驻调度**。
   - 补货净额**只交数不给建议** —— 需求预测与下单属 6.2.1.1。
   - 上游头程（10）、采购（6）、FBA（4 外部仓）**均未交付**；本模块只靠
     `source_system` + `source_reference` 指回原文，不复制对方系统的说法，不编造上游。
   - 跨租户详情查不到返回 404（不是 403，避免泄露「这批货存在」）。

## 踩坑记录

- **`pnpm contracts:generate` 不能不带参数跑**：它会打四个服务的 live 端口（此环境不通），
  且拿到的是旧 spec。必须先跑 `OpenApiContractTest` 刷新 `backend/target/openapi`，再带 `--spec-dir`。
- **`lane.mjs heartbeat` 必须在主仓库根跑**，不能在 worktree 内跑：
  该脚本用「自身所在仓库根」定位 `.dev-agent/lanes.json`，而 worktree 里没有这个文件（状态是共享的）。
- `LotRef` 契约里**没有 `name` 字段**（只有 `id` / `skuId` / `skuCode` / `lotCode` / `expiresOn` / `status`），
  批次下拉不能按 `Ref`（`{id,code,name}`）建模。
- `Notice` 的 `title` 是必填；`FormField` 的 `hint` 在 `exactOptionalPropertyTypes` 下不能用
  `cond ? 'x' : undefined`，要改成条件展开 `...(cond ? { hint: 'x' } : {})`。