# 2026-10-24 · 16.3.1.2 申报日历（阶段 D7）

## 决策摘要

| 项   | 结论                                                                                                  |
| ---- | ----------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/channel/src/main/resources/db/migration/V371__filing_calendar.sql`（三张新表，纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表，零 FOREIGN KEY；版本号由调度者预分配为 **V371**） |
| 表   | `filing_calendar_cycle_config`（申报周期配置）· `filing_calendar_entry`（申报日历条目）· `filing_calendar_reminder`（到期提醒） |
| 端点 | 14 个，挂 `/channel/filing-calendar`；权限复用 `channel.operation.read / .create / .update`（identity 零改动） |
| 页面 | `/compliance/filing-calendar`                                                                         |
| 测试 | 后端 43（25 集成 + 3 租户三断言 + 15 纯函数）· 前端 36（21 模型 + 5 allowlist + 10 兄弟模块）· 全量 allowlist 回归 78 文件 / 393 例 |
| 契约 | `packages/api-contracts/src/contracts/channel.ts` 新增 18 个 `FilingCalendar*` 类型（+18/-0）；spec 新增 11 条路径、零删除 |

### 蓝图原文

> ### 16.3.1.2 申报日历
>
> - **职责**：管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），到期提醒与责任人。
> - **输入**：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
> - **输出**：申报日历视图 + 提醒 + 状态跟踪。
> - **关系**：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。**漏报罚款远高于系统能提供的价值**，日历提醒是最低成本的合规保障。

## 关键 trade-off

### 1. 「本模块不执行申报」落成结构而不是文案

蓝图 12.6.1.2 写死「本系统提供申报底稿，**不替代税务申报动作**」。本模块同理：**日历不向任何税务机构提交申报**，只**跟踪**状态。落成三处结构：

- `status` 是受控词表（`NOT_STARTED / DATA_READY / SUBMITTED / CONFIRMED`），**DB 触发器钉死单调**（rank 不许下降 —— 倒退 = 漏报被悄悄抹掉）。
- 推进到 `SUBMITTED` / `CONFIRMED` **必须带依据**（谁 / 何时 / 凭据号），触发器钉死「没有依据就推不到已提交」。凭据号是**用户从税务机构拿到的外部凭据**，系统**不代造受理号**。
- api-proxy 刻意**不放行** `filing-calendar/submit`、`filing-calendar/receipts` —— 放行一个不存在的入口比 404 更糟（界面会以为功能已交付）。

### 2. 「数据就绪」是**投影**，不是抄一份底稿

12.6.1.2 的底稿住在 **finance 服务**（另一个库）⇒ 只经授权只读 HTTP API（`GET /finance/filing-draft/drafts`）读，**不跨库 SQL / FK / 视图**。

- `filing_calendar_entry` **不含任何金额列**：底稿的金额留在 finance，日历只存**状态**。抄金额进来就是两个真值源。
- 🔴 **「读不到」与「没就绪」是两个状态**：上游不可达 ⇒ `data_readiness_available = false` + 原因，界面上显示「读不到」；**绝不显示成「数据没就绪」** —— 前者要去查上游，后者是「查过了，确实没有」，处置动作完全相反。
- 12.6.1.2 只做 VAT / IOSS 两条申报线 ⇒ 其余申报线**判不了就绪**（`available=true` + 「12.6.1.2 未覆盖该申报线」），**不假装就绪**。

### 3. 辖区不另建码表，引用走触发器

真值源是 16.3.1.1 的 `tax_registration_jurisdiction` —— 它与本模块**在同一个库**（channel），所以直接读、**不复制它的行**；落库前由 DB 触发器钉死 ⇒ 辖区码不是自由文本（服务层与 DB 层各拒一次）。本模块**零 FOREIGN KEY**，跨表引用一律走触发器（与 V312 / V368 同一纪律）。

### 4. 截止日是**当地时刻**，不是 UTC

欧盟各国申报截止时间都是当地时间（Berlin 23:59 ≠ UTC 23:59）。写死 UTC 会差几小时 —— 而**差几小时就足以错过一个截止日**。

- 周期配置存 IANA 时区名（如 `Europe/Berlin`）+ 小时 + 期起始日（1..28，**刻意不允许 29/30/31**：短月没有那一天）。
- 申报期以**配置的生效起始日为原点**按周期在月份上排格子（`periodFor`）：期起始日 1 号 + 按季 ⇒ 日历季度；期起始日 15 号 ⇒ 每格从 15 号开始。
- 条目**快照** `due_at` + `due_timezone` ⇒ **改配置不改历史条目**（否则「上个月到底哪天截止」会被今天的配置改写，而那正是稽查要看的东西）。
- 读库时 JDBC 会把 `timestamptz` 换算成 JVM 默认时区（本机 +08:00）⇒ 显示口径必须跟着条目自己的时区走（`localText`），否则德国截止日会显示成「第二天 05:00」，看起来像差了一天。

### 5. 「配置」与「条目」分两张表

- `filing_calendar_cycle_config` = **模板**（怎么算期、怎么算截止日、提前几天提醒），会改。
- `filing_calendar_entry` = **那一次申报的事实**（截止日快照、所需数据、状态、责任人），不会改。

一条表就分不清「规则」和「这一次的事」，改规则会顺手改掉历史。

### 6. 责任人 = 租户内成员（跨库软引用 + 落库前校验）

只存 identity 的 `membership` 逻辑 ID：**不建跨库外键、也不复制成员姓名**（与 16.3.1.1 不复制法人名称同一纪律）。落库前经 identity 的授权只读 API 校验它确实是**本租户**的成员；**成员目录读不到 ⇒ 拒绝落库**（503），不存一个查无此人的 id。调用方自己（已认证会话里的 membership）永远合法 —— 目录读不到时至少还能把自己设成责任人。

### 7. 漏报是高代价事件 ⇒ 排序与截断都要说实话

- 日历默认排序把**逾期未提交 / 临近截止未提交**排在最前（`order by (case when status in ('SUBMITTED','CONFIRMED') then 1 else 0 end), due_at asc`）。
- 逾期判定在**服务端**按 DB 当前时刻算（`overdue(due_at, now())`），**不信任客户端时间**。
- 提醒清单**如实报总数**：「只取回前 N 条（共 M 条，未确认 K 条）」—— 到期提醒不能被分页吞掉；`unacknowledgedCount` 是**全量计数**，不受分页影响。

### 8. 提醒是**派生行**，但「已知悉」是**事实**

`filing_calendar_reminder` 由条目派生两档（提前 / 截止），但确认已知悉（谁、什么时候）会落库 —— 不能每次读都重算一遍。「是否已逾期」**不在表里**：逾期是当前时刻与截止日的关系，读时现算。

## 文件清单

### 新增

- `backend/services/channel/src/main/resources/db/migration/V371__filing_calendar.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/FilingCalendarModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/FilingCalendarRules.java`（纯函数：状态 rank / 申报期 / 截止日 / 逾期）
- `backend/services/channel/src/main/java/com/starcon/channel/FilingCalendarService.java`（jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/FilingCalendarController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/FilingDraftReadinessPort.java`（channel → finance 只读端口）
- `backend/services/channel/src/main/java/com/starcon/channel/MemberDirectorySourcePort.java`（channel → identity 只读端口）
- `backend/services/channel/src/test/java/com/starcon/channel/FilingCalendarIntegrationTest.java`（25 例）
- `backend/services/channel/src/test/java/com/starcon/channel/FilingCalendarTenancyIntegrationTest.java`（3 例 · 租户隔离三断言）
- `backend/services/channel/src/test/java/com/starcon/channel/FilingCalendarRulesTest.java`（15 例）
- `apps/web/src/components/compliance/filing-calendar-model.ts`
- `apps/web/src/components/compliance/filing-calendar-model.test.ts`
- `apps/web/src/components/compliance/filing-calendar-workspace.tsx`
- `apps/web/src/app/compliance/filing-calendar/page.tsx`
- `apps/web/src/app/api/channel/filing-calendar-allowlist.test.ts`

### 改动

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（首 token `filing-calendar`，追加在 `tax-registration` 元素**之后**；既有条目一字未改、不重排）。
- `packages/api-contracts/src/channel.openapi.json` · `packages/api-contracts/src/contracts/channel.ts` —— codegen 重生成（纯新增）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 16.3.1.2 那一行的「状态」「证据」两格。

## 验证

```
./mvnw -q compile                                            → 退出 0
./mvnw -pl services/channel -am test -Dtest='FilingCalendar*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                          → Tests run: 43, Failures: 0, Errors: 0
pnpm typecheck                                              → 3/3 通过
pnpm lint                                                   → 通过（零新增告警）
pnpm vitest run apps/web/src/components/compliance \
    apps/web/src/app/api/channel/filing-calendar-allowlist.test.ts
                                                          → 3 文件 36 例全绿
pnpm vitest run apps/web/src/app/api/channel                → 78 文件 393 例全绿（改过 api-proxy 的全量回归）
node tools/module-progress/sync.mjs --check                 → ✓ 台账与蓝图一致
```

契约：channel **纯新增 11 条路径与 18 个 schema、零删除、零改既有条目**；其余 6 个服务的契约**逐字节不变**。

## 后续待办

1. **下游 1 工作台待办（1.1.1.x）未接线** —— 本模块只交付提醒与状态的端点与页面，**没有**把提醒推进工作台页面（那是别的模块已交付的页面）。要接待办请读 `GET /channel/filing-calendar/reminders`（未确认的提醒）。接线时**不要改** `apps/web/src/app/dashboard/todo/**`。
2. **16.3.2.2 EPR/包装法** 的申报周期归它 —— 本模块**不编造**它的申报线；EPR 辖区要进日历，得等它的口径落地后由用户登记。
3. **16.3.2.x 的「执行申报」** 未交付 —— 本模块只跟踪状态，不代用户提交。
4. **能力声明里的「12.6.1.2 只覆盖 VAT / IOSS」** 是当前口径；若 12.6.1.2 扩到别的申报线，改的是 `FilingCalendarRules` 里的那条受控词表与能力声明文案。
5. `data_readiness_available` 目前只在**显式刷新**（`POST /entries/readiness`）时更新；页面上显示「上次核对时刻」。若要改成每次打开页面自动刷新，改前端 `refresh()` 即可，后端语义不变。
