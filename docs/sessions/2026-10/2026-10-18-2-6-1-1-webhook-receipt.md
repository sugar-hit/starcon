# 02.6.1.1 接收记录（D5 阶段，L3 锚点 2.6.1）

> 车道 `feat/2-6-1-1`，worktree `.worktrees/2.6.1.1`，基线 `integration/module-dev` 的 55bf6888。

## 决策摘要

蓝图 §2.6.1.1 把这一节的成败压在两句话上，实现全部围绕它们展开：

1. **先持久化再处理** —— 接收成功即落库并返回 200，业务处理异步化。
   `POST /channel/webhooks/{endpointKey}` 只做验签 + 落库，绝不等待业务处理。
   平台回调有超时上限，同步处理必然让平台超时重推，重推又把压力放大成雪崩。
2. **重复 webhook 不产生第二条可处理记录** —— 去重锚点是外部事件 ID，
   由 `webhook_receipt_first_delivery` 部分唯一索引（`WHERE duplicate_of IS NULL`）在 DB 层裁决。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 重复推送要不要留记录 | **留**，但标 `DUPLICATE` 并用 `duplicate_of` 指回首推 | 蓝图 §2.6.1.2 要求「展示同一业务事件被平台重推的次数」，只留一条下游就没有可展示的东西。真正的「只处理一次」由状态 + 唯一索引保证，业务侧只会看到一条可处理记录 |
| 验签密钥存哪 | 自持 `webhook_endpoint.signing_secret_enc`（AES-256-GCM，主密钥 `CHANNEL_WEBHOOK_SIGNING_KEY` 注入） | `SecretStore` 刻意**不提供**回读（2.2.1.3「令牌明文不入库」）。但验签**必须**能读回密钥——这是接收侧密钥与 OAuth 令牌的本质区别。不改 `secrets` 包，避免动 2.2 的文件 |
| 权限码 | **复用 `channel.sync.*`**，不写 identity 迁移 | 沿用 10.4.2.1 的既定处置：本车道去 identity 加迁移会与其他车道撞版本号 |
| 入口行如何查（回调没有用户会话） | `resolve_webhook_endpoint()` SECURITY DEFINER 函数 | 入口表带 RLS，而调用方此刻还没有租户上下文，直接查必然落空。函数只返回验签必需字段且只命中 `ACTIVE` 入口；真正的授权由 HMAC 签名完成 |
| 平台回调的数据范围策略 | 仍挂 `channel_scope` RESTRICTIVE 策略，接入侧显式声明系统身份 | 没有关掉策略——关掉会让任何用户的越权读一起放行 |
| 报文里的凭据字段 | 入库前递归脱敏（`access_token` 等 14 个字段名） | AGENTS.md：明文凭据不入库。断言直接查库里的 jsonb，不只看视图 |

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V170__webhook_receipt.sql`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/WebhookSignature.java`（新，HMAC-SHA256 + AES-GCM）
- `.../WebhookModels.java`（新，线上契约 record）
- `.../WebhookService.java`（新，落库/去重/认领/收尾）
- `.../WebhookController.java`（新，8 个端点）
- `backend/services/channel/src/test/java/com/starcon/channel/WebhookReceiptIntegrationTest.java`（新，27 例）
- `backend/services/channel/src/test/java/com/starcon/channel/OpenApiContractTest.java`（改：补 `CHANNEL_WEBHOOK_SIGNING_KEY` 属性，与既有 `INTERNAL_KEY` 同款）

生成物（禁止手改）

- `packages/api-contracts/src/contracts/channel.ts`（+6 类型）
- `packages/api-contracts/src/channel.openapi.json`

前端

- `apps/web/src/components/channel/channel-workspace.tsx`（改：新增「平台消息」分区 + `WebhookInbox` + `WebhookEndpointForm`）
- `apps/web/src/app/api/channel/webhook-receipt-allowlist.test.ts`（新，双向断言）
- `apps/web/src/lib/api-proxy.ts`（改：**新增**三条分支，未替换既有 alternation）

台账

- `docs/product/capability-blueprint/module-progress.md`（只改 2.6.1.1 那一行的「状态」「证据」两格）

## 验证

| 闸 | 结果 |
| --- | --- |
| L0 后端编译 `./mvnw -q compile` | 通过，无 ERROR |
| L0 `pnpm typecheck` | 通过（3 tasks successful） |
| L1 `./mvnw -pl services/channel -am test -Dtest='WebhookReceipt*'` | **Tests run: 27, Failures: 0, Errors: 0** |
| 前端 `pnpm vitest run apps/web/src/app/api/channel/` | **Test Files 7 passed / Tests 32 passed** |

租户隔离三条断言均在 Testcontainers PostgreSQL 16 上实跑（池大小设为 1，连接复用最易串租户）：
两租户互不可见、伪造 `X-Tenant-Id` 无效、连接池归还后不串租户。

## api-proxy 求并的坑（下次并入必看）

`api-proxy.ts` 是多车道共用的**累积型**正则文件，两层 alternation 都要求并。
本次用「基线正则 vs 合并后正则」逐条对拍 57 条兄弟车道路径，确认**零回归**，
并把这条对拍固化进 `webhook-receipt-allowlist.test.ts` 的最后一个用例。

我自己第一版写 `webhook-receipts(?:\/summary|[0-9a-f-]+…)` 时**漏了 `\/`**，
症状是「白名单看着登记了，实际浏览器里 404」。是这个测试抓出来的，不是 typecheck/lint。

## 遗留（不属本车道，未擅自改）

`api-proxy.ts` 的 `sync-failures(?:\/summary|[0-9a-f-]+\/replay)?` 同样漏了 `\/`，
导致 **2.4.2.2 人工重放**（`POST /channel/sync-failures/{id}/replay`）在浏览器里 404，
而 typecheck / lint / vitest / Playwright 全绿。这是已交付模块的真实缺陷，
修法是加一个 `\/`，但它属于 2.4.2.2 的文件范围，请指派对应车道处理。

## 后续待办

- **2.6.1.2 去重视图**（下游，未开发）：按 `external_event_id` 归并重推次数与首次处理结果。
  本模块已把 `duplicate_of` 与 `status='DUPLICATE'` 落到位，归并展示即可直接建在现有数据上。
- **2.6.2.1 增量补拉**：消费 `status='RECEIVED'` 的记录做补拉缺口填补。
- 平台回调目前要求平台把签名放在 `X-Starcon-Signature` 或 `X-Shopify-Hmac-Sha256`；
  其他平台的请求头名在登记入口时逐条配置，验签算法固定 HMAC-SHA256（真实实现，非占位）。
- `CHANNEL_WEBHOOK_SIGNING_KEY` 需在部署时由 secret manager 注入 32 字节 AES-256 密钥；
  缺失或长度不对时启动即失败，不静默降级。
