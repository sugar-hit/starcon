# 2026-10-30: 18.1.2.1 事件订阅（订阅配置侧）

## 摘要

在 `feat/18-1-2-1` 车道实现能力蓝图 **18.1.2.1「事件订阅」**（卷 18，L3 锚点 18.1.2）的**订阅配置侧**：
可订阅事件列表（事件定义注册表）、订阅端点配置、签名密钥、事件过滤、重试与死信**策略配置**，
外加一个**签名试算**端点（真算签名，不投递）。

后端落在 **platform 服务**（端口 8089，库 starcon_platform），前端入口 `/integrations/webhooks`。

**边界**：**18.1.2.2「投递记录」不在本车道** —— 投递明细（事件 ID、订阅方、投递状态、响应码、重试次数、
死信重放）一律不实现。投递执行只留一个**显式的未接入端口**（`EventSubscriptionDeliveryPort` +
`UnavailableEventSubscriptionDeliveryPort`），端点如实答 **409**、一次包都不发。

## 决策 / 结论

1. **「可订阅事件列表」落在 platform 服务自己的注册表表**（`platform_webhook_event_type`）。
   理由：`backend/libraries/` 下**没有** event-envelope 库（只有 `tenancy`），没有共享的事件定义真相源；
   事件类型的**出处**（来源服务 + 出处文件:行）作为表里的两列记下来，**系统零预置行** ——
   清单由租户登记产生，不是一份「看起来会随业务演进而变久、实际不会」的本地词表。
2. **「破坏性变更要新增版本而非原地修改」落成物理约束**：注册表上挂 BEFORE UPDATE 触发器，
   拒绝改 `event_type` / `schema_version` / `payload_schema`（`restrict_violation`）；
   同一 `(event_type, schema_version)` 重复登记在服务层挡成 409。老版本保留、只可标 DEPRECATED。
3. **签名密钥**：SecureRandom 256 bit 生成 → **AES-256-GCM 密文落库**
   （Base64(nonce ‖ ciphertext ‖ tag)）→ **明文只在生成／轮换那一次响应里出现**。
   算法 **HMAC-SHA256**，盖「时间戳 + 点 + 原始字节」（带时间戳是为了让验签方能拒绝重放）。
4. **主密钥用 platform 已有的 `INTERNAL_KEY` 经 HKDF-SHA256 派生**，**不新增必填 env**。
   理由：新增一个必填环境变量会让**没配该变量的既有部署直接启动失败** —— 一次密钥轮换不该让整个
   platform 服务起不来。HKDF 的 info 串与其它用途隔离（`starcon.webhook.signing-key.v1`）。
5. **落库时间戳一律取 DB 时钟**：`clock_timestamp()` 读出来再用 OffsetDateTime 绑定 `?::timestamptz`。
   这样既满足「用 OffsetDateTime 绑定」（避开 `Timestamp.valueOf(LocalDateTime…)` 静默偏 −28800s），
   又让落库值与测试断言处在**同一个钟域**（本仓已有两处同款守卫因跨时钟域在负载下假红过）。
6. **改订阅 / 改策略用 POST 而不是 PUT**：platform 的 catch-all 路由
   （`apps/web/src/app/api/platform/[...path]/route.ts`）只导出 GET / POST / DELETE，
   与同服务 18.1.1.1、18.2.1.1 同一口径（后者改档案也是 POST）。**刻意不去动那条多车道共用的路由文件**。
7. **契约类型前缀用 `EventSubscription*`**：channel 服务那一族 `Webhook*` 已经占了「webhook」这个词
   （那是电商平台回传通道，归 channel），本模块说的是「本系统对外提供的事件订阅」。
   端口里的嵌套 record 也一并改名（`EventSubscriptionDeliveryOutcome` 等），保证整个模块的契约面同前缀。
8. **契约重新生成**：`backend/target/openapi/*.openapi.json` 由各服务的 `OpenApiContractTest` 导出，
   再 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
   核对过：**只有 platform 相关文件变了**，其余 8 个服务逐字节不变；platform 也**没有**删掉任何既有 schema 或 path。

## 涉及文件

**迁移**

- `backend/services/platform/src/main/resources/db/migration/V412__event_subscription.sql`
  （六张表 + RLS + GRANT + 两个触发器）
- `backend/services/identity/src/main/resources/db/migration/V412__event_subscription_permission.sql`
  （`platform.webhook.read / .create / .update / .revoke`，page_code `platform.webhook`，存量 OWNER 补权）

**后端（platform 服务）**

- `EventSubscriptionModels.java` —— 传输模型（24 个 `EventSubscription*` 契约类型）
- `EventSubscriptionService.java` —— 服务层（jOOQ 显式 SQL）
- `EventSubscriptionController.java` —— 17 条端点挂 `/platform/webhooks`
- `EventSubscriptionDeliveryPort.java` / `UnavailableEventSubscriptionDeliveryPort.java` —— 投递端口 + 未接入实现
- `WebhookSigningSecretCipher.java` —— HKDF 派生 + AES-256-GCM + HMAC-SHA256
- `src/test/java/.../EventSubscriptionIntegrationTest.java`（22 例）
- `src/test/java/.../EventSubscriptionTenancyIntegrationTest.java`（6 例）

**契约（codegen 产物，只读）**

- `packages/api-contracts/src/contracts/platform.ts`（+24 行）
- `packages/api-contracts/src/platform.openapi.json`

**前端**

- `apps/web/src/components/integrations/webhooks/webhooks-model.ts`
- `apps/web/src/components/integrations/webhooks/webhooks-model.test.ts`（18 例）
- `apps/web/src/components/integrations/webhooks/webhooks-workspace.tsx`
- `apps/web/src/app/integrations/webhooks/page.tsx`
- `apps/web/src/app/api/platform/webhook-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条独立 `services[]` 元素**，锚点在数组最开头）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（只改 18.1.2.1 一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-30-event-subscription.md`（本文件）

## 关键 trade-off / 讨论

- **投递端点要不要进浏览器白名单？** 进。与同服务 18.2.1.1 的 `/push` 同一处置：
  端点**真实存在**，由后端如实答 409，前端据此显示「未接入」，**不是由白名单替它撒谎**。
  页面本身**不提供**投递按钮（一个点了必然 409 的按钮不如没有）。
- **「不存在」用 404 还是 400？** 用 404。RLS 把对方的行挡掉之后只能答「不存在」；
  答 403 等于告诉调用方「这个 id 真的存在，只是你不能看」，那本身就是一次信息泄露。
  （注意这与本仓 `AppCredentialService.requireApp` 用 400 的旧写法不同 —— 那是既有约定，
  本模块按更严格的语义走，并在会话里记明。）
- **策略参数越界 vs 重复创建哪个先判？** **先校验参数再判重**。反过来写的话，同一个请求只会看到
  409，运营根本不知道自己填错了什么。（第一轮测试就是这么红的：越界参数被「已存在默认策略」的 409 盖住了。）
- **要不要给 platform 新增一个签名主密钥 env？** 不新增。理由见决策 4。
- **本模块落不落金额？** 不落。退避倍数是比率（`numeric(8,2)`），其余是计数（int）。

## 后续待办

- **18.1.2.2「投递记录」**（另一条车道）：需要落 `platform_webhook_delivery` 一族表（事件 ID、订阅方、
  投递状态、响应码、重试次数）、死信保留与**死信重放**端点，并把 `UnavailableEventSubscriptionDeliveryPort`
  换成真的投递执行器（从业务服务 outbox 取事件 → 按订阅投递 → 用 `platform_webhook_signing_secret` 里的
  密钥算签名 → 按 `platform_webhook_policy` 重试）。届时本车道的能力声明里
  「事件实际投递」「上游 outbox relay」「死信重放」三维要翻成 `available=true`。
- **上游 outbox relay** 需要一条**授权的跨服务读通道**（平台服务目前没有跨服务读 catalog / identity
  outbox 的授权 API），并入前要先确认这条通道的归属模块 —— 本车道**没有**替它做决定。
- 订阅端点目前**不做连通性探测**（避免在配订阅时对外部地址发包）；若将来要做，属于 18.1.1.3 调用监控那一格。

## 关联

- 蓝图：`docs/product/capability-blueprint/18-integration-and-automation.md`（18.1.2 / 18.1.2.1 / 18.1.2.2）
- 台账：`docs/product/capability-blueprint/module-progress.md`（18.1.2.1 行）
- 同卷邻居：18.1.1.1 应用与凭证（V377，权限码 V378）、18.2.1.1 财务软件（V382）——
  权限码登记、平台 catch-all 路由、端口 + 未接入实现三处都照抄它们的先例
- ADR：0002 多租户策略、0004 Java 微服务、0005 租户层级与页面 RBAC
