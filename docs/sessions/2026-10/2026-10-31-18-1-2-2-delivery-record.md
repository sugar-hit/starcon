# 18.1.2.2 投递记录（卷 18 集成开放与智能自动化 → L3 18.1.2 事件订阅 → 第二格）

- **日期**：2026-10-31
- **车道**：18.1.2.2（分支 `feat/18-1-2-2`）
- **阶段**：D7
- **状态**：已交付

## 决策摘要

**本车道是这条链上第一个真的往外发包的车道。** 18.1.2.1「事件订阅」此前把「事件实际投递」
如实答成「未接入」（端点恒 409、一次包都不发），并在 `EventSubscriptionDeliveryPort` /
`UnavailableEventSubscriptionDeliveryPort` 里写明「投递执行器与投递日志同属那一格（18.1.2.2）」。
本车道交付的正是那一格：投递执行器 + 投递明细 + 死信重放。

蓝图原文（卷 18 → 18.1.2 → 18.1.2.2）：
「职责：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；提供手动重投与死信重放。
输入：投递记录；重放请求。输出：投递日志 + 重放任务。
关系：上游 = 18.1.2.1；下游 = 外部端点。**接收方应按 event_id 幂等去重**——投递保证是"至少一次"，
不是"恰好一次"。」

## 关键 trade-off

### 1. 「至少一次」落在**唯一键**上，不落在代码纪律上

`UNIQUE(tenant_id, event_id, subscription_id)` ⇒ 同一事件对同一订阅**只有一条**投递记录，
重试与重放都在这一行上累加 `attempt_count`；而「投递日志」是**每一次尝试**一行
（`UNIQUE(tenant_id, delivery_id, attempt_no)`）。两个粒度刻意不同 —— 混成一张表就没法回答
「这个事件一共投了几次」。

**trade-off**：这条唯一键**不**保证只发一个包（那只保证「不会登记成两条」）。
蓝图那句「接收方应按 event_id 幂等去重」正是它的另一半 —— 平台允许重复投递，接收方负责去重。
把这个责任推给接收方，比在平台侧假装「恰好一次」诚实。

### 2. 记录先落库，包后发出去

投递记录在发包**之前**就提交了。反过来（先发包后落库）崩在中途 ⇒ 对方收到了、我们没有记录 ⇒
「投没投出去」变成一个答不出来的问题。发包前崩了 ⇒ 留下一条 PENDING，重试扫描会把它捡起来。

**代价**：一次慢投递会占住一条 HTTP 连接最长 120 秒（策略 `request_timeout_seconds` 上限）。
因此 `DeliveryRecordController` **刻意不用** `access.run(...)`（那会把整个调用包在一个数据库事务里），
自己控制事务边界：阶段一（事务）读订阅/解析策略/解密密钥/落记录并提交 → 阶段二（无事务）真的发 HTTP
→ 阶段三（事务）把响应码写进投递日志、更新状态、必要时转死信。

### 3. 响应码 0 是**合法值**

它表示「**根本没拿到响应**」（连接被拒 / 超时 / 没发出去）。把它翻成 200 或 -1 都是在编造事实。
前端对应显示成「没有响应」而不是「0」或「未知」。

### 4. 原始报文字节落库（Base64）

签名盖的是**原始字节**。重放时若重新序列化，字节序/空格可能变 ⇒ 验签对不上。
存原始字节 ⇒ 重放发出去的就是那一份，验签与对端去重都成立。

### 5. 🔴 api-proxy.ts 只做**纯增量**，不做全文件 prettier 重排

**这是本车道最重要的一个工程决策。**

`apps/web/src/lib/api-proxy.ts` 是多车道共用的**累积型**白名单。调度者要求把该文件 prettier 化，
但「prettier 全文件重排」与并行车道 18.1.1.3（调用监控）**在「本车道先并入」顺序必然冲突**。

实测（scratch clone 里跑真实 `git merge`，不是 `git merge-file` 模拟）：

| 变体                                                  | 本车道先并入 | 对方先并入 |
| ----------------------------------------------------- | ------------ | ---------- |
| prettier(base) + 新条目（数组中段）                   | **冲突**     | 0 冲突     |
| prettier(base) + 新条目（数组开头）                   | **冲突**     | 0 冲突     |
| prettier(base) + 新条目（数组末尾前）                 | **冲突**     | 0 冲突     |
| prettier(base) + 新条目（18.1.2.1 之后）              | **冲突**     | 0 冲突     |
| **纯增量：raw base + 新条目（仅新条目 prettier 化）** | **0 冲突**   | **0 冲突** |

冲突点 = 数组**末尾**那个元素：两条车道都在那一段插入内容，而 prettier 恰好会把那一段重排。

⇒ 本车道对 api-proxy.ts **只做纯增量（+56 行 / 0 删除）**；全文件重排由并行车道 18.1.1.3 的
`style(prettier)` 提交承担（它已提交），职责不重复。两种并入顺序的合并结果都已实测：
**0 冲突 + 合并后文件仍 prettier 合规**。

> **补证（收尾复核时补测）**：`prettier --check apps/web/src/lib/api-proxy.ts` 在本车道**报 warn**，
> 但这是**基线继承的存量债、不是本车道引入的**——实测基线 `9b5a83d` 与本车道 HEAD 的
> prettier 偏差**完全相同（各 24 处差异行 / 12 处 hunk，同一批 pattern 与 `contentType.includes`）**，
> 本车道 +56 行**零新增偏差**；并行车道 18.1.1.3 分支上的该文件是 **prettier 合规**的，
> 两条车道并入后文件即合规。

### 6. 🔴 修掉一处真实缺陷（前任遗留）

前任写的 api-proxy 正则把 `dead-letters` 挂在字面量分支里、又让 `{uuid}` 段独立跟在后面 ⇒
`platform/deliveries/dead-letters/{uuid}`（后端**没有**这个 GET 端点）被放行了 ——
而同一段注释里恰恰写着「刻意不放行」。现改成三个**平级**分支（字面量子路径 /
`dead-letters` 含其 `{uuid}/replay` / 裸 `{uuid}` 含 `attempts`·`replay`），三者互斥。

**这个缺陷是靠新写的白名单守卫测出来的**（`delivery-allowlist.test.ts`），变异测试验证：
换回旧正则该用例立即红。

### 7. 对 18.1.2.1 的越界改动（唯一一处，且是必须的）

V413 合法建出了 `platform_delivery_*` 四张表，而 18.1.2.1 的测试里有一条**全库子串枚举**守卫
（`table_name like '%delivery%'` ⇒ 断言全库没有任何投递表），它已被下游模块合法推翻 ⇒
改成**显式表名清单 + schema 限定**的**正面**断言。另一条「本车道只有订阅配置侧的六张表」的查询
**加了 `table_schema = 'public'`**（原来是无 schema 限定的全库子串枚举，会把别的 schema 的表算进来）。

`EventSubscriptionService.capabilities()` 三维按**实际实现**如实改：「事件实际投递」与「死信重放」
改 `available=true`，「**上游 outbox relay**」**保持 false** 并写清依据（经核实 `backend/libraries`
下只有 `tenancy`、**没有 event-envelope 库**，`backend/` 的 pom 里也**没有任何事件通道依赖**，
平台服务**没有跨服务读业务服务 outbox 的授权通道** —— 跨服务读别人的表是模块边界纪律禁止的）。
**绝不为让测试绿而写成假的 true。**

## 文件清单

**新增（后端）**

- `backend/services/platform/src/main/resources/db/migration/V413__webhook_delivery_record.sql`
- `backend/services/identity/src/main/resources/db/migration/V413__webhook_delivery_record_permission.sql`
- `backend/services/platform/src/main/java/com/starcon/platform/DeliveryRecordController.java`
- `backend/services/platform/src/main/java/com/starcon/platform/DeliveryRecordModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/DeliveryRecordService.java`
- `backend/services/platform/src/main/java/com/starcon/platform/WebhookDeliveryExecutor.java`
- `backend/services/platform/src/main/java/com/starcon/platform/WebhookDeliveryPort.java`
- `backend/services/platform/src/test/java/com/starcon/platform/DeliveryRecordIntegrationTest.java`
- `backend/services/platform/src/test/java/com/starcon/platform/DeliveryRecordReceiver.java`
- `backend/services/platform/src/test/java/com/starcon/platform/DeliveryRecordTenancyIntegrationTest.java`

**新增（前端）**

- `apps/web/src/app/integrations/deliveries/page.tsx`
- `apps/web/src/components/integrations/deliveries/delivery-record-model.ts`
- `apps/web/src/components/integrations/deliveries/delivery-record-model.test.ts`
- `apps/web/src/components/integrations/deliveries/delivery-record-workspace.tsx`
- `apps/web/src/app/api/platform/delivery-allowlist.test.ts`（本轮补写，白名单守卫）

**修改**

- `backend/.../EventSubscriptionService.java`（三维能力声明按实际实现改）
- `backend/.../EventSubscriptionIntegrationTest.java`（三条「未接入 ⇒ 409」老断言改成「已接入 ⇒ 真发包」）
- `apps/web/src/lib/api-proxy.ts`（**纯增量** + 修一处正则缺陷）
- `apps/web/src/app/api/platform/webhook-allowlist.test.ts`、`components/integrations/webhooks/*`（4 个）
  —— 纯 prettier 机械格式化，**零逻辑变化**（已用规范化比对逐个证明与 `prettier(base)` 逐字节相同）
- `packages/api-contracts/src/contracts/platform.ts`、`platform.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改 18.1.2.2 那一行）

## 验证

- 后端 L1：`./mvnw -pl services/platform -am test -Dtest='DeliveryRecord*,EventSubscription*'`
  ⇒ **Tests run: 58, Failures: 0, Errors: 0**
  （EventSubscription 25 + DeliveryRecordIntegration 21 + DeliveryRecordTenancy 6 + 其余 6）
- 后端 L0：`./mvnw -q compile` ⇒ 通过
- 前端：`pnpm vitest run apps/web/src/app/api` ⇒ **276 files / 1561 tests 全过**（含全量 allowlist 回归）
  （集成头同命令为 275 files / 1555 tests，差额恰为本车道新增的 `delivery-allowlist.test.ts` 6 例）
- 本车道两个前端测试文件 ⇒ **25 例全过**（模型 19 + 白名单守卫 6）
- `pnpm typecheck` ⇒ 全绿
- 契约：codegen 重跑后 `git diff` 为空（产物与生成器输出逐字节一致）；spec 连续两次导出逐字节相同
- 多租户三断言实跑（真 Testcontainers PostgreSQL 16，**连接池压到 1 条**）：两租户互不可见 /
  伪造 `X-Tenant-Id` 头无效且行落在会话租户名下 / 连接池归还后不串租户

## 后续待办

1. **上游 outbox relay 不在本模块范围**（跨服务事件总线不在 18.1.2 内）：业务服务自动发出的事件
   不会自动走到这里，事件只能由 `POST /platform/deliveries/publish` **手工发布**进投递链路。
   事件定义的「出处」（`source_service` + `source_artifact`）只能由租户登记时写清。
2. **能力地图页面**（`docs/site/capability-map/index.html`）在台账变更后需要刷新 ——
   与 18.1.2.1 的先例一致，这是维护者层级的单独动作，CI 不检查它。
3. `docs/site/capability-map` 之外的 `build-capability-map --check` 目前对本车道**红**，
   原因同上（页面数字滞后于台账），并入后刷新即可。
