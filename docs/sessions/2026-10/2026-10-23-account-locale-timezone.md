# 1.4.2.1 语言与时区（阶段 D7 · L3 锚点 1.4.2）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/1-4-2-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/1.4.2.1`）
- **基线**：`integration/module-dev` @ `7725fdd0`（`merge(1.5.1.1): 同步与 API 健康`）
- **迁移版本号**：`V340`（**预分配但不占用** —— 理由见下）
- **页面**：`/settings/account-locale`

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 201-207 行）：

> ### 1.4.2.1 语言与时区
>
> - **职责**：界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。
> - **输入**：用户选择；平台站点时区清单。
> - **输出**：界面语言包与时间显示口径。
> - **关系**：上游 = 02 平台时区参数；下游 = 全站时间渲染。**存储统一 `timestamptz`**，只在展示层转换；业务日期口径由各单据自己定义。

本车道落地要点：

1. **不建表，复用 1.4.1.2 的 `user_preference`（page key = `account.locale`）⇒ V340 预分配但不占用。**
   蓝图把「界面语言与展示时区」定义为**纯个人偏好**，与「列表与表格偏好」「工作台布局」同属
   「一页一行 + JSONB」的场景。另建一张表 = 同一个偏好两套真相；而复用才能吃到
   「偏好按页面 key 存储，新增字段不影响旧偏好」这条红利（零 ALTER、零数据迁移）。
   **V340 留给后续真正需要建表的车道**，不占号 ⇒ 并行车道不会撞 Flyway 版本。
2. **`UserPreferencePayloadRules` 零改动复用。** 它守的是**结构安全**（键名词表、深度 ≤4、
   键数 ≤64、体积 ≤16KB、敏感键名与凭据形状的值一律拒），不是页面语义。
   `account.locale` 落在其 page_key 词表内，`locale` / `timezone` 既不是敏感词干也不是凭据形状。
   ⇒ 通用偏好端点与本模块端点**写的是同一张表的同一行**。
3. **「不改变存储时区」是可执行断言，不是文案。** 写回时刻一律 `?::timestamptz` +
   `OffsetDateTime` 绑定（**不是 `Timestamp.valueOf(LocalDateTime)` —— 后者按 JVM 默认时区解释，
   在东八区静默偏 −28800s**）。测试断言 API 返回的 `updatedAt` 与**直查库里的绝对时刻逐秒相等**、
   两个相差 13 小时的展示时区之间写入漂移 < 60s、载荷里**只有 locale 与 timezone、没有换算后的时刻**。
4. **时区只认 tzdb 里的区域 id，偏移量写法一律 400。** `ZoneId.of()` 的宽松语义会接受
   `UTC+8` / `GMT+8:00`，但**偏移量没有夏令时规则** —— 用户选了它，跨夏令时那天的显示时间差一小时。
   ⇒ 校验的是「用户选的**展示**时区是不是真实存在的 IANA 区域 id」，**不是**「入库前把时间换算成本地时间」。
5. **界面语言：当前只能如实报 `available=false`，界面上不提供切换开关。**
   **本仓确实没有 i18n 运行时**（实测：`packages/i18n` 只是空占位目录（仅 `.gitkeep`、无 package.json），
   全仓无 `next-intl` / `react-intl` / `formatjs` / `lingui`，`next.config.js` 无 `i18n` 段，
   无 `middleware.ts` 改写语言路由，无语言包目录）⇒ 全站文案是硬编码中文。
   放一个「English」下拉 = 「选了 English 但文案一点没变」的假开关 ⇒ **不做**。
   缺什么、归谁都写进能力声明的 `missing` / `ownerModule`；数据侧已按 `zh-CN` 落库，
   i18n 运行时与语言包到位后往 `SUPPORTED_LOCALES` 加一项即可。
6. **需要独立端点而不是只复用 `/identity/user-preferences/{pageKey}`**，三个理由：
   ① 需要**能力声明**（通用端点没有这个形状）；② 需要**语义校验**（通用端点只守结构，
   不守「这个时区是不是真的存在」）；③ 需要**防御性回落**（有人绕过本模块用通用端点写了
   `Not/AZone` 时，读回来必须回落并说清原因，而不是把一个渲染会抛异常的时区发给前端）。
7. **显示口径由服务端声明**（`AccountLocaleDisplayConvention` = `Intl.DateTimeFormat` 的选项集，
   取 `packages/ui/src/lib/world-clock.ts` 已在用的 `hourCycle: h23` + `timeZoneName: shortOffset`），
   前端**原样**传给 `Intl`。口径若散落在各页面的 `Intl.DateTimeFormat` 调用里，
   全站会出现两套写法、两种偏移标签；两边各写一份必然漂移。
8. **平台站点时区走 channel 的授权只读端点**（`GET /channel/platforms` → `marketplace.default_timezone`），
   **不跨库读 channel**。该端点要 `channel.registry.read` 权限；拿不到时界面上如实说
   「读不到平台站点清单」，**与「平台站点没有时区」分开说**。
9. **界面上「平台站点时区」与「全部 IANA 时区」必须分开标注**：后者来自
   `Intl.supportedValuesOf('timeZone')`（浏览器的 tzdb），只是「所有可选时区」，
   **不是**平台站点清单 —— 混为一谈用户会以为平台站点有几百个时区。

## 关键 trade-off

| 决策点 | 取舍 | 理由 |
| --- | --- | --- |
| 存储：复用 `user_preference` vs 建新表 | **复用（V340 不占用）** | 同属纯个人偏好；另建表 = 同一个偏好两套真相，且吃不到「新增字段不影响旧偏好」的红利。预分配的 V340 留给后续真需要建表的车道，不占号避免撞 Flyway。 |
| 界面语言：报 `available=false` vs 提供切换开关 | **如实报 false，不提供开关** | 本仓无 i18n 运行时与语言包（实测确认），提供开关就是被明令禁止的假开关。数据侧已按 zh-CN 落库，语言包到位即可开。 |
| 时区校验：区域 id vs 偏移量 | **只认区域 id** | 偏移量没有夏令时规则，跨夏令时那天的显示时间会差一小时。 |
| 通用偏好端点 vs 独立端点 | **独立端点** | 需要能力声明 + 语义校验 + 防御性回落，三者通用端点都不提供；但**存储仍是同一张表同一行**。 |
| 契约类型名：`CapabilityDimension` vs `AccountLocaleCapabilityDimension` | **加模块前缀** | codegen 的跨服务同名闸**真的拦下了一次**：channel 已有两处 `CapabilityDimension`，字段不同会互相覆盖、闸直接中止生成。按本仓惯例（`SaasCapabilityDimension` / `UsageCapabilityDimension` / `ListingStatusCapabilityDimension`）改名，**不往 allowlist 塞例外**。`DisplayConvention` 同样改名（通用名早晚被抢）。 |
| 默认展示时区 | **`Asia/Shanghai`** | 工作台首页现在就是按它渲染日期的 ⇒ 没设置过偏好的用户看到的渲染**与今天一模一样**；取别的值等于让所有存量用户第一次进页面就发现日期变了。 |
| api-proxy 锚点 | **`user-preferences` 之后** | 离同批 1.2.2.1 的锚点约 1500 行、离 1.4.2.2 的锚点亦远超 300 行 ⇒ git 能干净求并。首 token `user-locale` 对既有 192 条正则**实测命中 0 处**。 |

## 文件清单

**后端（identity 服务，`backend/services/identity/`）**

- `src/main/java/com/starcon/identity/AccountLocaleModels.java` — 形状：`AccountLocaleView` / `AccountLocaleInput` / `AccountLocaleCapabilities` / `AccountLocaleCapabilityDimension` / `AccountLocaleDisplayConvention`
- `src/main/java/com/starcon/identity/AccountLocaleRules.java` — 纯函数规则：时区与语言的校验归一化、回落原因、显示口径声明
- `src/main/java/com/starcon/identity/AccountLocaleException.java` — 本模块的 400（**带一句能直接显示在界面上的原因**）
- `src/main/java/com/starcon/identity/AccountLocaleService.java` — 读写 / 重置 / 能力声明 / 防御性回落（**复用 `UserPreferenceService` 与 `UserPreferencePayloadRules`，零改动**）
- `src/main/java/com/starcon/identity/AccountLocaleController.java` — 4 个端点 + **控制器局部**的 `@ExceptionHandler`（不动全局 `ApiErrorHandler`）

**后端测试**

- `src/test/java/com/starcon/identity/AccountLocaleRulesTest.java`（12 例，纯函数）
- `src/test/java/com/starcon/identity/AccountLocaleIntegrationTest.java`（15 例，实跑 PG16）
- `src/test/java/com/starcon/identity/AccountLocaleTenancyIntegrationTest.java`（4 例，多租户三断言）

**前端（`apps/web/`）**

- `src/app/settings/account-locale/page.tsx` — 路由页（**自己的路由目录，不碰共享的 `settings/profile/page.tsx`**）
- `src/components/dashboard/account-locale-workspace.tsx` — 真实工作区（加载 / 错误 / 空三态齐全）
- `src/lib/account-locale-model.ts` — 纯模型（候选清单构建与过滤、渲染前可渲染性检查、口径转换）
- `src/lib/use-account-locale.ts` — 模块级 store 的可复用 hook（读不到按默认继续渲染；写失败只留提示）
- `src/lib/account-locale-model.test.ts`（19 例）
- `src/lib/account-locale-api-proxy.test.ts`（10 例）
- `src/lib/api-proxy.ts` — **只新增一条独立的 `services[]` 元素**，既有条目一字未动也不重排

**契约（codegen 产物，禁手改）**

- `packages/api-contracts/src/contracts/identity.ts`（+5 个类型）
- `packages/api-contracts/src/identity.openapi.json`

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md` — **只改 1.4.2.1 那一行的「状态」「证据」两格**
- 本文件

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ **EXIT=0**；`pnpm typecheck` ⇒ **3/3 successful**
- **L1 模块闸**：`./mvnw -pl services/identity -am test -Dtest='AccountLocale*' …` ⇒ **Tests run: 31, Failures: 0, Errors: 0** · BUILD SUCCESS（实跑 Testcontainers PostgreSQL 16，连接池压到 1）
- **前端**：`pnpm vitest run apps/web/src/lib/account-locale-{model,api-proxy}.test.ts` ⇒ **29 例全绿**（19 + 10）
- **全量 allowlist 回归**（改了共享的 `api-proxy.ts` 必跑）：`pnpm vitest run apps/web/src/app/api` ⇒ **206 文件 / 1178 例全绿**
- `pnpm lint` ⇒ 3/3 successful（仅既有模块的既有 warning，本模块无告警）
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ⇒ identity 新增 5 个类型，**其余五个服务逐字节不变**（`git diff --stat packages/` 只列出 identity 两个文件）

> ⚠️ 契约重生成的一个坑：`**必须用六个服务「活导出的」 spec`**。
> `packages/api-contracts/src/*.openapi.json` 是 codegen 自己写出的 `sort_keys=True` 快照，
> 直接拿它当输入会让六个服务的 `.ts` 全部发生**键序重排**（数千行无关 diff）。
> 正确做法：先跑各服务的 `OpenApiContractTest` 导出到 `backend/target/openapi/`，再跑 codegen。

## 后续待办

- **界面语言切换**：需要先有 i18n 运行时（语言上下文 + 缺键回落）与语言包（zh-CN / en-US），
  再把全站文案 `t()` 化 —— 这是**全站工作量**，不是本模块能补的一格。
  到位后本模块只需往 `AccountLocaleRules.SUPPORTED_LOCALES` 加一项。
  本仓蓝图中**没有这一格**（1.4.2 账号偏好下只有本模块与 1.4.2.2），建议挂 1.4.2 下或作为平台级基础能力单独立项。
- **全站时间渲染的逐页迁移**：本模块只提供「时区参数 + 显示口径」，
  页面把 `Intl.DateTimeFormat` 换成按 `useAccountLocale().timezone` 渲染是另一个模块的事。
- **业务日期口径**：仍由各单据自己定义，**不要**在本模块加「全局业务时区」。
- **平台站点时区清单**：目前经 `GET /channel/platforms` 只读取用；若 channel 之后提供
  更细的站点时区端点，可换用，但**不要**在 identity 侧跨库读 channel。
