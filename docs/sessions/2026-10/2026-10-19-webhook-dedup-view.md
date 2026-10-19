# 02.6.1.2 去重视图（D5 阶段，L3 锚点 2.6.1）

> 车道 `feat/2-6-1-2`，worktree `.worktrees/2.6.1.2`，基线 `integration/module-dev` 的 8300d842。

## 决策摘要

蓝图 §2.6.1.2 只有四行，但成立与否全在一句话上：**重复事件不产生第二条业务记录**。
实现因此分成两半，缺一不可：

1. **看得见**（读）—— 以 `(endpoint_id, external_event_id)` 把同一业务事件的全部投递
   归并成一行，标出重推次数，并**引用首次处理结果**。
2. **挡得住**（写）—— `POST /webhook-dedup/{firstReceiptId}/claim` 让下游**按业务事件**
   而不是按接收记录认领。这是「下游按 event_id 去重」真正落地的地方：重复的投递连同它的
   id 都拿不到，没有路径能创建出第二条业务记录。

## 开工自检：上游到底长什么样（与蓝图两处不符）

蓝图按字面写会写错，实现一律对齐 **V170 的真实结构**：

| 蓝图写的 | 实际落地 | 影响 |
| --- | --- | --- |
| `event_id` | `webhook_receipt.external_event_id` | 契约层用 `externalEventId` 语义，不造不存在的列名 |
| 「重复推送不产生第二条记录」 | **重复推送照样入库**，标 `status='DUPLICATE'`、`duplicate_of` 指回首推 | 去重命中记录取自**那些 DUPLICATE 行**，不是"被拒的行" |
| 隐含"事件 ID 全局唯一" | `UNIQUE(tenant_id,endpoint_id,external_event_id) WHERE duplicate_of IS NULL`（**部分**唯一索引） | 去重锚点必须带 `endpoint_id` |

最后一条是本模块最容易写错的地方：`webhook_receipt` 上**没有** `external_event_id` 的
全量唯一约束。被拒的只是"第二个自称首次的行"（`insert ... on conflict do nothing`）。
若只按 `(tenant, event_id)` 归并，两个入口各自独立的事件会被错并成一条——那才是货真价实的
「重复业务记录」，比不展示更糟。视图的分组键因此与部分唯一索引**同口径**。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 视图 vs 物化表 | **视图**（`webhook_dedup_event` / `webhook_dedup_hit`） | 去重是**读时归并**，「只处理一次」是**写时保证**（部分唯一索引 + `guard_settled_receipt` 触发器）。重复推送源源不断进来，任何副本都会和真实状态撒谎 |
| 视图的权限语义 | `WITH (security_invoker = true)` | 用默认 definer 语义会把 `webhook_receipt` 的 isolation + receipt_scope 两层 RLS 在视图层抹平 —— 那是最危险的一类假隔离。沿用 trade V126 的既有处置 |
| 组标识放路径用什么 | **首次接收记录 uuid**，不用 `external_event_id` | 后者是平台任意文本（最长 200 字符，可能含 `/` 与 `:`），塞进路径段要在白名单里放宽字符集并引入编码歧义；uuid 天然落在既有 `[0-9a-f-]+` 段里。代价是详情要先拿列表里的 `firstReceiptId` 才能点开 |
| 总览口径 | 重复投递**总条数**与被重推**事件数**分成两个量 | 一个事件被推 5 次会同时贡献 4 和 1。合成一个数字之后就再也分不开「平台在死磕一条事件」和「很多事件各重推了一次」，而这两种的处置完全不同 |
| 是否提供按 event_id 的认领端点 | 不提供，只按首次 uuid 认领 | 一旦重复行也能被认领，「只处理一次」就退化成一条靠自觉遵守的约定 —— 那正是重复订单的来源 |
| 权限码 | 复用 `channel.sync.*`，不新增 | 新增要动 identity 的权限真相源，是别的车道的事，也会撞迁移版本号 |
| `starcon_app` 授权 | 视图 `REVOKE ALL` | 它是跨服务只读角色，拿不到任何业务表（与 V1、V2、V169 同口径）。业务服务按 event_id 去重走授权 API，不直连 channel 的库 |

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V174__webhook_dedup_view.sql`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/WebhookDedupModels.java`（新，6 个线上契约 record）
- `backend/services/channel/src/main/java/com/starcon/channel/WebhookDedupService.java`（新，jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/WebhookDedupController.java`（新，4 个端点）
- `backend/services/channel/src/test/java/com/starcon/channel/WebhookDedupIntegrationTest.java`（新，22 例）

生成物（禁止手改，由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 产出）

- `packages/api-contracts/src/contracts/channel.ts`（+6 类型）
- `packages/api-contracts/src/channel.openapi.json`（+4 paths / +6 schemas，**零删除、零改动**既有条目）

前端

- `apps/web/src/components/channel/channel-workspace.tsx`（改：新增 `WebhookDedupPanel`，挂在既有「平台消息」分区下方）
- `apps/web/src/app/api/channel/webhook-dedup-allowlist.test.ts`（新，双向断言）
- `apps/web/src/lib/api-proxy.ts`（改：**新增**一条 `webhook-dedup` 分支，未替换既有 alternation）

台账

- `docs/product/capability-blueprint/module-progress.md`（只改 2.6.1.2 那一行的「状态」「证据」两格）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端编译 | `./mvnw -q compile` | 通过，无 ERROR |
| L0 前端类型 | `pnpm typecheck` | 通过（3 tasks successful） |
| L0 前端 lint | `pnpm lint` | 通过（告警均为其它模块既有文件，本车道零新增） |
| L1 模块闸 | `./mvnw -pl services/channel -am test -Dtest='WebhookDedup*,OpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 23, Failures: 0, Errors: 0**（22 去重 + 1 契约导出） |
| 前端模块 | `pnpm vitest run apps/web/src/app/api/channel/` | **Test Files 10 passed / Tests 46 passed** |
| api-proxy 全量回归（改了共享白名单，必跑） | `pnpm vitest run apps/web/src/app/api/trade` | **38 files / 203 tests passed** |
| api-proxy 全量回归（全目录） | `pnpm vitest run apps/web/src/app/api/` | **65 files / 427 tests passed** |

租户隔离三条断言均在 Testcontainers PostgreSQL 16 上实跑（连接池大小设为 1，连接复用最易串租户）：
两租户互不可见、伪造 `X-Tenant-Id` 无效、连接池归还后不串租户。

L1 跑完时本车道的 PG 容器已被 ryuk 回收（跑前跑后 `docker ps` 均为同样 5 个其它车道/本地的容器）。

## 遗留（不属本车道，未擅自改）

- `packages/api-contracts/src/contracts/trade.ts` 在 HEAD 上就与 `trade.openapi.json`
  **不同步**（后者有 `ExchangeOrderRow` / `Refund*` / `FilterOption` 等 schema，前者缺一部分）。
  跑 codegen 会把它们一并写出来，但那不是本车道的东西，已 `git checkout` 还原。
  需要一条独立车道处理。
- `webhook-receipt-allowlist.test.ts`（2.6.1.1 的文件）把
  `channel/webhook-receipts/duplicates`、`channel/webhook-receipts/events/ORDER-1`
  列为「下游未交付：2.6.1.2」的 404 用例。本车道交付后该注释已过时，但两个路径在本车道
  的形状下**依然合法地 404**（本模块用 `/webhook-dedup/{firstReceiptId}`）。改那个文件属于
  改别人的文件，未动。

## 后续待办

- **2.6.2.1 增量补拉**：与本模块共用 `external_event_id` 锚点。补拉必须幂等（同一条外部记录
  被补拉多次不得产生第二张订单），本模块的 `claim` 已经是那个幂等闸口的形状，可以直接复用。
- 业务服务（09 订单 / 04 刊登 / 12 结算）接入时走 `POST /channel/webhook-dedup/{firstReceiptId}/claim`
  拿唯一出口，不要按 `webhook_receipt` 逐条认领——后者的路径下重复行虽然也领不到，但要把
  「哪条是重复」这件事在每个消费方各判断一遍。
- 验签失败的事件在去重视图里如实显示为 `SIGNATURE_FAILED` 且 `processable=false`。
  若某租户的这类事件持续增长，多半是密钥轮换后平台侧没更新，属安全事件。
