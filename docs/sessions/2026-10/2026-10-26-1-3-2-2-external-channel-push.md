# 2026-10-26: 1.3.2.2 外部渠道推送（identity 服务 · 渠道登记 + 投递记录）

## 摘要

蓝图模块 **1.3.2.2 外部渠道推送**（卷 01 · L3 1.3.2 预警订阅）在本车道交付：
迁移 `V385__external_channel_push.sql`（`external_push_channel` + `external_push_delivery` 两张表）·
**两个端口抽象**（`ExternalChannelDeliveryPort` / `SecretReferencePort`）+ 两个如实答「未接通」的实现 ·
10 个端点形状挂 `/identity/external-channel-push` · 真实前端工作区 `/notifications/push` ·
后端 20 例 + 前端 30 例全绿。

**本仓没有 secret manager，外部 IM / 邮件通道也一个都没接通** ⇒ 本模块的落点是
「**把通知筛好、把凭据只存引用、把每一次「尝试投递 → 通道未接通」如实落成记录**」，
而不是「把消息真的送出去」。页面上写的是「未接通 + 归 18 系统负责」，不是「0 次投递」。

## 决策 / 结论

1. **落地服务 = identity，端点前缀 `/identity/external-channel-push`**。卷 01 §1.3 整域都在 identity
   （1.3.1.1 收件箱、1.3.1.2 偏好、1.3.2.1 预警规则都在这里）。外部渠道推送存的是
   **租户级共享的渠道与投递事实**，warehouse / trade / channel / finance 都不拥有这个概念。

2. **🔴 凭据不落库 = 一条数据层 CHECK，不只是一句注释**。
   `secret_ref` 只接受 `secret://` / `vault://` / `external-secrets://` / `k8s://` 开头的引用串；
   `https://…` 这类 **Webhook 地址本体**被 **V385 的 CHECK 直接拒掉**。
   服务层 `ExternalPushRules.normalizeSecretRef` 再拒一道（错误消息直说「凭据不落库」）。
   理由：凭据一旦落库就是**不可逆的泄露**（进了日志、进了备份、进了 binlog）——
   这条纪律必须落在写入路径上，绕得过服务层的直连 SQL 也要挡得住。

3. **🔴 对外投递走端口，端口实现如实答「未接通」**。照抄本仓既有范式
   （platform 的 `UnavailableFinanceSoftwarePushPort`、crm 的 `UnavailablePlatformEmailPort`）：
   端口先立起来，实现逐能力答「未接通 + 缺什么 + 归谁」，**不返回任何假成功、也不产出任何假凭据**。
   归属写**卷级**（18 集成与自动化）—— 蓝图里没有单独列一格「外部 IM 接入」，
   与 platform 那条写「18.2」是同一套做法。

4. **投递记录有四个状态码，不是两个**：`DELIVERED` / `FAILED`（还有重试次数）/
   `FAILED_EXHAUSTED`（重试耗尽、告警已升起）/ `DEFERRED`（落在免打扰时段里，**推迟**而不是失败）。
   「该等」与「投不出去」混成一个状态，就既说不出什么时候会再投，也会在告警里误报。

5. **失败重试与免打扰到期共用一个字段 `next_attempt_at` + 一条到期扫描**。
   「该再试一次」与「该去投了」都是「到点了就动」，两条路径合成一条扫描 ⇒ 少一处状态机。
   **本服务不装 `@Scheduled`**（与本仓 10.4.2.2 巡检同一套纪律：多副本下自带定时器要做主节点选举
   与分布式锁，那是平台能力不是领域能力）⇒ 由外部调度（K8s CronJob）按间隔调
   `POST /deliveries/retry-due`。页面也留了一个手动入口。

6. **告警落在本表自己的三列上**（`alert_state` / `alerted_at` / `alert_message`），
   **不写 `operation_log`** —— 每张事实表只有一个写入所有者，那张表归 17.5.3.2。
   也不假装能生成站内通知（通知内容是各域投影，1.3.1.1 明确「不作为业务事实来源」）。
   确认告警**只改告警状态、不改投递结果**：「我知道了」与「它投出去了」是两件事。

7. **偏好过滤是真过滤（1.3.1.2）**：收件人没订阅该渠道 ⇒ 400 拒掉且**一行都不落库**；
   落在免打扰时段里 ⇒ 落一条 `DEFERRED`，`next_attempt_at` 排在**时段结束的那一刻**
   （按收件人当地墙上时间换算，不按 UTC）。人工重试**也**受免打扰压制 ——
   那是收件人的偏好，不该被任何入口绕过。

8. **候选清单只列 1.3.2.1 的真命中**（`alert_rule_hit.resolution='HIT'`）。
   `UNDECIDABLE` 是「算不出来」，**不是**命中，推送它没有意义。
   命中**标题正文由服务端按命中事实生成**（规则名 / 度量名 / 观测值 / 阈值），
   调用方只能给跳转目标 ⇒ 「推出去的那句话」永远能在命中记录里对上。

9. **规则命中不带通知类型 ⇒ 推送时由操作人选定**（受控词表，1.3.1.1 那一份）。
   备选是给命中硬编一个分类映射（SKU→缺货之类），但那条映射在业务上多数是错的
   （库存可覆盖天数 ≠ 缺货）⇒ 不编。

10. **NOTIFICATION 来源的候选不在后端清单里**：通知内容是各域的只读投影，
    1.3.1.1 明确「通知是投影」⇒ 由前端聚合层取走后提交推送（与 1.3.1.1 同一套做法）。

11. **跳转目标只允许站内相对路径**：一条通知可以把收件人带到站外去，而那个地址没有任何审核环节。

12. **api-proxy 新增一条独立 `services[]` 元素**，不往既有 identity 分支并分支
    （该文件**不在** merge-union 驱动覆盖范围内，走普通 git merge ⇒ 插入点必须彼此错开）。
    插入锚点固定在 `5.2.3.1 平台费用` 那条元素**之后**（约第 9724 行），
    与同批兄弟车道 1.3.2.1 的锚点（约第 1234 行）相距 8000 行以上。
    id 段用**完整 UUID 形状**（不是 `[0-9a-f-]+` —— 后者会把 `retry` / `retry-due` 也当成 id 吃掉）。
    零命中用脚本实测（`.tmp/api-proxy-zero-hit.mjs`，不入库）：既有 231 条正则对本模块 9 个形状零命中。

## 关键 trade-off

- **投递记录表归本模块自己，不等 18.5.2.1**。蓝图把「外部渠道投递记录（成功/失败/重试）」写在本模块的
  **输出**里，而 18.5.2.1 的「投递记录」是**模板维度**的统计（触达率、退订）—— 两者不是同一张表。
  本模块建自己的表（V385），18.5.2.1 未交付这件事在能力声明里如实标出来，
  **不冒充**它的统计口径。
- **模板管理不造表顶替**。18.5.1.1 未交付 ⇒ 投递记录里存的是**发出当时的内容快照**，
  没有「模板版本」这个概念。模板一旦交付，快照变成「模板版本 + 渲染结果」两列，历史仍解释得清。
  代价是现在没有多语言模板；好处是**没有一张假装存在的表**。
- **`channel_id` 不建外键**（照抄 V384 的 `alert_rule_hit.rule_id` 同一条纪律）：渠道删了投递历史仍要留着，
  行内留渠道码与渠道名的**快照** ⇒ 「上个月投过几次」不会随删渠道一起消失。
- **投递记录 append-only**（GRANT 只给 SELECT/INSERT/UPDATE，**不给 DELETE**）：
  「投过没有」是事实，删掉它等于让这件事没发生过。重试**更新同一行**而不是每次插一行 ——
  一条通知投 3 次是**一次投递的三次尝试**，记成三行会让「触达率」这类统计从一开始就错。
- **收件人是必填的**：推送要按**这个人的**偏好过滤，没有收件人就无法谈「尊重偏好」。

## 文件清单

**迁移**

- `backend/services/identity/src/main/resources/db/migration/V385__external_channel_push.sql`

**后端（identity 服务）**

- `…/identity/ExternalPushModels.java` —— 契约形状（13 个 `External*` 类型）
- `…/identity/ExternalPushRules.java` —— 纯函数校验（引用形状 / 词表 / 相对路径 / 退避表）
- `…/identity/ExternalChannelDeliveryPort.java` —— 对外交付端口
- `…/identity/UnavailableExternalChannelDeliveryPort.java` —— 如实答「未接通」
- `…/identity/SecretReferencePort.java` —— 凭据引用解析端口
- `…/identity/UnavailableSecretReferencePort.java` —— 如实答「未接通」
- `…/identity/ExternalPushService.java` —— 渠道登记 / 候选 / 推送 / 重试 / 告警
- `…/identity/ExternalPushController.java` —— 10 个端点形状 + 权限码闸

**后端测试**

- `…/identity/ExternalChannelPushTestSupport.java`（抽象，**不持有容器**）
- `…/identity/ExternalChannelPushIntegrationTest.java`（14 例 · 业务行为）
- `…/identity/ExternalChannelPushTenancyIntegrationTest.java`（6 例 · 多租户三断言）

**前端**

- `apps/web/src/app/notifications/push/page.tsx`
- `apps/web/src/components/notifications/external-channel-push-model.ts`
- `apps/web/src/components/notifications/external-channel-push-workspace.tsx`
- `apps/web/src/components/notifications/external-channel-push-model.test.ts`（25 例）
- `apps/web/src/app/api/external-channel-push-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **+63 / −0 行**，新增一条独立 `services[]` 元素

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/identity.ts`（新增 13 个 `External*` 类型）
- `packages/api-contracts/src/identity.openapi.json`

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md` —— **只改 1.3.2.2 那一行**的状态与证据
- `docs/sessions/2026-10/2026-10-26-1-3-2-2-external-channel-push.md` —— 本文件

## 验证

- L0：`cd backend && ./mvnw -q -pl services/identity -am compile` → **EXIT=0**
- L1：`./mvnw -pl services/identity -am test -Dtest='ExternalChannelPush*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 20, Failures: 0, Errors: 0 / BUILD SUCCESS**
  （两个测试类**各自起自己的 PG 容器**并在 `@AfterAll` 里 `.stop()`；跑完 `docker ps
  --filter ancestor=postgres:16-alpine` = **0**，已被 ryuk 回收）
- 前端本模块：`pnpm vitest run <model.test.ts> <allowlist.test.ts>` → **30 例全绿**
- **全量 allowlist 回归**（本车道改了共享白名单 api-proxy.ts，必须跑全量）：
  `pnpm vitest run apps/web/src/app/api` → **255 文件 / 1439 例全绿**
- `pnpm typecheck` → **3/3 successful**
- `pnpm lint` → **3/3 successful**（本车道新增文件零告警）

## 后续待办

1. **secret manager 服务**（18 系统，卷级归属）—— 通了之后 `SecretReferencePort` 换实现即可，
   表结构与端点**不用动**（`secret_ref` 本来就只存引用串）。
2. **企业微信 / 钉钉 / 飞书 / 邮件的外部通道适配器**（18 系统，卷级归属）——
   通了之后 `ExternalChannelDeliveryPort` 换实现即可。适配器要拿到凭据**本体**，
   那一次解析只在内存里流转，**不落库**。
3. **18.5.1.1 模板管理**（未交付）—— 交付后投递记录加「模板版本 + 渲染结果」两列，
   历史仍解释得清（快照已经在那儿了）。
4. **18.5.2.1 投递记录**（模板维度的触达率与退订统计，未交付）——
   与本模块的「渠道投递尝试事实」是两张表，不要混口径。
5. ⚠️ **兄弟模块的边界文案已过时**：`NotificationPreferenceService.capabilities()` 的 boundaries 里写着
   「1.3.2.2 外部渠道推送未交付」，本车道交付后这句话不再准确 ——
   那是 **1.3.1.2 车道自己的文件**，本车道**不越界改**，留待调度者裁决。
6. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
   （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
7. **identity 服务的错误响应体是空的**：`ResponseStatusException` 的 reason 走不到响应体
   （本仓既有状况，兄弟模块同样如此）⇒ 前端只能拿到状态码，拿不到「为什么被拒」的具体措辞。
   要修得改共享的 `ApiErrorHandler`，不属本车道范围，记此备案。
