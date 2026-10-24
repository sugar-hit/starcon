# 1.3.1.2 通知偏好（卷 01 · L3 1.3.1 消息与预警的第二格）

- **日期**：2026-10-24
- **车道**：`feat/1-3-1-2`（worktree `integration/.worktrees/1.3.1.2`）
- **阶段**：D7 · 卷 01「工作台与门户」L3 1.3.1
- **形态**：**纯个人偏好落 identity 服务的 `user_preference`（无新迁移）+ 前端工作区**

---

## 决策摘要

### 1. 落法：复用 `user_preference`，**无新迁移（V374 预分配但不占用）**

蓝图 1.3.1.2 的「关系」字段逐字写着「共享数据：`user_preference`」，
1.3.1.1 的迁移注释（V373）也明确把那张表留给本模块
（「那是**一份偏好**（整份覆盖正合适），与本模块的**逐条已读状态**是两件事」）。

⇒ 复用 1.4.1.2 建好的 `user_preference`（V336），用本模块**自己的**
`page_key = notifications.delivery`。同表的另外两个 key 是 1.4.1.2 的列表偏好与
1.4.2.1 的 `account.locale` —— 三个模块各占一个 key，同一张表、同一套 jOOQ 显式 SQL、
同一套载荷形状校验（`UserPreferencePayloadRules`）。**本模块不建表、不 ALTER。**

**为什么 JSONB 载荷撑得住（账算清楚，不默认）**：

| 疑点 | 结论 |
| --- | --- |
| 容量：类型条数无上界？ | **不是**。通知类型是**受控词表**（五个码，与 1.3.1.1 逐字同表）。载荷里最多 5 个类型桶 × 3 个渠道码 + 4 个免打扰时段，离 16 KB / 单数组 64 项都差两个数量级 |
| 语义：整份覆盖会冲掉并发修改？ | **不冲**。通知偏好是「一个人一份」的**整体配置**（我自己改我的），整份覆盖正合适。与 1.3.1.1「逐条已读状态」那种增量集合（因此才另建表）不是一回事 |

### 2. 类型词表复用 1.3.1.1 的那一份

`NotificationPreferenceRules` 直接读 `NotificationInboxRules.TYPES`（五个受控码）。
**不另立一套枚举** —— 两份词表一旦漂移，表现是「订阅了某类型但偏好页上没有这一行」。
也不碰 1.3.1.1 的 `notification_type_subscription` 表（写入所有权是它的）。

### 3. 关键 trade-off：底线项怎么「落成结构」

蓝图加粗句：「**关键业务通知（超期发货、账务差异）不允许被完全关闭**，
系统强制保留下不可关闭的底线项」。

「落成结构」= **服务端校验 + 明确的不可关闭清单**，不是前端把复选框置灰。
本模块落成**三层**：

1. **写入层**：关键类型（`TIMELINESS_OVERDUE` 超期发货 / `RECONCILIATION_DIFF` 账务差异）
   的渠道集合里**必须**留着底线渠道 `IN_APP`（站内），少了就 400。
2. **读取层**（兜底）：万一有人绕过本服务、用 1.4.1.2 的通用偏好端点
   `/identity/user-preferences/notifications.delivery` 写了空渠道集合，
   **生效值里底线渠道仍然在**，并记一条 `fallbackReasons` 说明为什么。
3. **能力声明层**：`capabilities.types[]` 逐条给出 `mandatory` + `baselineChannel` + 人话规则。

**为什么底线渠道选「站内」而不是邮件/IM**：站内是**唯一不依赖外部通道**的渠道，
邮件与企业 IM 的投递通道属于 1.3.2.2（尚未交付）——
把底线挂在外部渠道上，1.3.2.2 没交付时关键通知就等于没有底线了。

**配套纪律：渠道集合不允许为空**（与 1.3.1.1「订阅集合不允许为空」同一条纪律）。
想彻底不要某一类通知，去 1.3.1.1 取消订阅那一类 —— 两个模块各管一件事：
1.3.1.1 管「收不收」，1.3.1.2 管「收的走哪几个渠道」。

### 4. 关键 trade-off：免打扰时段是「当地时刻 + 时区」，**不存 timestamptz**

免打扰时段是**每天重复的墙上时间**（22:00–08:00）。存成 UTC 时刻会在跨时区用户身上
差几小时，夏令时切换那天再差一小时；而 AGENTS.md 要求时间列用 `timestamptz` ——
那条要求针对的是**记录某一刻发生的事**，而这里记的是**每天重复的时刻**。

⇒ 存的是 `HH:mm` 的**当地时间** + 一个 IANA 时区（`Asia/Shanghai` 这种区域 id，
不收 `UTC+8` 这类偏移量写法 —— 偏移量没有夏令时规则）。
判断「现在算不算在免打扰时段里」时才用那个时区把当下换算成当地时刻，
换算方向是「**当下 → 当地时刻**」，**不涉及任何 UTC 偏移**。
这条换算是整个模块**唯一**一处时间换算 ⇒ `Timestamp.valueOf(LocalDateTime)`
那一类静默偏 −28800s 的错误在这里**根本不会出现**。

**时区口径**：按**用户自己的时区**算（不是租户时区、不是平台时区）：
继承 1.4.2.1「语言与时区」的展示时区，用户在本模块显式设置时以本模块为准
（显式 > 继承 > 平台默认 `Asia/Shanghai`）。来源随响应一起下发（`timezoneSource`），
免打扰因此**跟着这个用户走**而不是跟着服务器走。

**配套决策：免打扰只压制外部渠道**（邮件 / 企业 IM），站内通知照常进收件箱。
半夜一封邮件才是打扰；1.3.1.1 的收件箱是各域投影的聚合，与本模块的投递过滤是两件事。
这个口径随能力声明一起公布（`quietHoursScope = EXTERNAL_CHANNELS`），不是隐含行为。

### 5. 输出形状：契约里没有裸 Map

`Map<String, List<String>>` 经 springdoc 只生成空的 `{}`，前端拿到的是 `unknown`
（11.3.1.2 已踩过一次这个坑）。⇒ 「类型 → 渠道集合」用**列表桶**表达
（`NotificationPreferenceChannelSetting`）。

### 6. 页面的落点：`/notifications/preferences`

台账里 1.3.1.1 与 1.3.1.2 的页面列都写着 `/notifications`（蓝图把 L3 1.3.1 映射到它）。
1.3.1.1 的页面文件里明确写着「本页面只做 1.3.1.1 这一格 …… 本车道**不碰别人的文件**」
⇒ 本模块**另起一条路由** `/notifications/preferences`，**一字未改** 1.3.1.1 的文件。

---

## 文件清单

### 后端（identity 服务）

| 文件 | 说明 |
| --- | --- |
| `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceRules.java` | 纯函数：渠道词表、**不可关闭底线清单**、渠道归一、免打扰时段形状与当地时刻判定、时区校验 |
| `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceModels.java` | 契约形状（列表桶，不用裸 Map） |
| `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java` | 读写（复用 `UserPreferenceService` + `AccountLocaleService`）、生效值归一与兜底、解析、能力声明 |
| `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceController.java` | 5 个端点，挂 `/identity/notification-preferences` |

### 后端测试

| 文件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `backend/services/identity/src/test/java/com/starcon/identity/NotificationPreferenceRulesTest.java` | 24 | 纯函数：词表 / 底线项 / 渠道集合非空 / 底线不可关 / 时段形状 / 跨午夜 / **同一瞬间在不同时区下判定不同** |
| `backend/services/identity/src/test/java/com/starcon/identity/NotificationPreferenceIntegrationTest.java` | 19 | 直查 `user_preference` 断言真落库 / **绕过本服务写坏载荷时底线渠道仍在**（且下次保存时被纠正回合规） / 400 各条 / 命中时段只压制外部渠道 / 继承 1.4.2.1 时区 / **写回库的 `updated_at` 与接口读出是同一时刻** |
| `backend/services/identity/src/test/java/com/starcon/identity/NotificationPreferenceTenancyIntegrationTest.java` | 4 | **多租户三断言**（两租户互不可见 + 同租户内不同成员互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池归还后不串租户）+ FORCE RLS 与运行角色无 bypassrls |

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/app/notifications/preferences/page.tsx` | 路由 `/notifications/preferences` |
| `apps/web/src/components/notifications/notification-preference-model.ts` | 纯函数模型（勾选态 / 入参 / 时段文本 / 时区清单 / 汇总） |
| `apps/web/src/components/notifications/notification-preference-workspace.tsx` | 真实工作区（概览 / 渠道矩阵 / 免打扰时段编辑 / 底线项清单；三态齐全） |
| `apps/web/src/components/notifications/notification-preference-model.test.ts` | 24 例 |
| `apps/web/src/app/api/notification-preference-allowlist.test.ts` | 5 例（反向抽取源码真实调用点 + 刻意不放行的形状仍 404） |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `services[]` 元素**（首 token `notification-preferences`），既有条目一字未改、也不重排 |

### 契约 / 台账 / 日志

| 文件 | 说明 |
| --- | --- |
| `packages/api-contracts/src/contracts/identity.ts`、`packages/api-contracts/src/identity.openapi.json` | codegen 产物（其余 6 个服务**一字未动**） |
| `docs/product/capability-blueprint/module-progress.md` | 只改 1.3.1.2 那一行的「状态」「证据」两格，未重排整表（行数仍 465、无重复 ID） |
| `docs/sessions/2026-10/2026-10-24-notification-preference.md` | 本文件 |

---

## 关键 trade-off 汇总

| 决策 | 取舍 |
| --- | --- |
| 复用 `user_preference` 而不另建表 | 放弃了「DB 层 CHECK 钉死底线」的能力，换来「不碰别人的表、零迁移、与 1.4.2.1 同一套语义」。**用「读取层兜底 + 服务端写入校验 + 能力声明」补回底线约束的强度** |
| 底线渠道选 `IN_APP` | 底线不依赖 1.3.2.2 的外部通道 ⇒ 1.3.2.2 没交付时关键通知仍有底线 |
| 免打扰只压制外部渠道 | 「免打扰」的口径写进能力声明（`quietHoursScope`），不留隐含行为 |
| 免打扰存当地时间 + 时区，不存 timestamptz | 放弃了「统一存 timestamptz」的通用约定，换取跨时区正确 + 夏令时正确。**模块内唯一一处时间换算，方向是「当下 → 当地时刻」** |
| 时区继承 1.4.2.1 | 两个模块之间多了一条读依赖（`AccountLocaleService`），换来「用户的免打扰跟着用户走」 |
| 页面另起 `/notifications/preferences` | 兄弟模块 1.3.1.1 的文件一字未改（多车道并入零冲突） |
| 契约用列表桶不用 Map | 前端拿到结构化类型而不是 `unknown`（11.3.1.2 的教训） |

---

## 验证

```
cd backend && ./mvnw -q compile                                        → 绿
cd backend && ./mvnw -pl services/identity -am test \
  -Dtest='NotificationPreference*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                                   → Tests run: 48, Failures: 0, Errors: 0
                                                                   （Rules 24 + Integration 20 + Tenancy 4）
pnpm typecheck                                                      → 3 packages successful
pnpm vitest run apps/web/src/components/notifications/notification-preference-model.test.ts \
             apps/web/src/app/api/notification-preference-allowlist.test.ts
                                                                   → Test Files 2 passed, Tests 29 passed
pnpm vitest run apps/web/src/app/api                                 → Test Files 243 passed, Tests 1369 passed
                                                                     （改了 api-proxy.ts ⇒ 跑全量 allowlist 回归）
pnpm --filter @starcon/web lint                                      → 无 error（只有别的文件的既有 warning）
```

契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
⇒ 只改动 identity 的两个产物，其余 6 个服务一字未动。

---

## 后续待办

1. **1.3.2.2 外部渠道推送**（未交付）：落地时需要一个「按**某个成员**解析生效偏好」的跨成员入口。
   本模块的服务层方法 `resolve(tenantId, membershipId, type)` 已经就位；
   若 1.3.2.2 落在 identity 之外，需要另加一条带 `X-Internal-Key` 的服务端到服务端端点
   （本模块刻意**没有**建它 —— 那是 1.3.2.2 的调用面，不属于本模块的职责）。
2. **导航注册**：`/notifications`（1.3.1.1）与 `/notifications/preferences`（本模块）
   都没进 `packages/ui` 的导航真相源 —— 与 1.3.1.1 同一套处置（多车道共用文件，不在本车道动）。
   本模块的页面目前只能直接访问路由。
3. **1.3.2.1 预警规则**（未交付）：预警规则命中后也应走本模块的渠道/免打扰过滤，
   届时会复用 `/resolve` 的口径。
4. `packages/ui` 的 `EmptyState` 组件在本模块只用了「读不到 / 没有类型」两处；
   若后续要统一空态样式，可与 1.3.1.1 一并调整。
