# 16.1.1.1 账号健康总览（阶段 D7，L3 锚点 16.1.1）

- **日期**：2026-10-22
- **车道**：`feat/16-1-1-1`（worktree `integration/.worktrees/16.1.1.1`）
- **迁移版本**：`V300__account_health.sql`（channel 服务，预分配版本号）
- **蓝图原文**（`docs/product/capability-blueprint/16-compliance-and-risk.md` 84-89 行）：
  > 职责：按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、申诉状态、风险等级，支持跨店铺对比与风险排序。
  > 输入：平台绩效数据同步；风险等级计算规则；用户数据范围（店铺维度）。
  > 输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
  > 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。**风险排序比明细更重要**：先处理最可能掉号的店铺。

## 决策摘要

### 1. 🔴 平台账号绩效同步**未接入** —— 本模块最关键的一条缺口

蓝图输入句写的是「平台绩效数据同步」，而**本仓没有任何平台账号绩效（ODR / 迟发率 /
取消率 / 有效追踪率）的拉取适配器** —— channel 的 `vendor` 包下只有 OAuth / 广告 / 达人等
适配器，**没有账号绩效适配器**。

处置（本车道与 15.1.1.1「消息只能人工登记」同一手法）：

- 立**平台绩效端口** `vendor/PlatformPerformancePort` + 当前实现
  `vendor/UnavailablePlatformPerformancePort`（照 14.5.1.1 的 `UnavailableInfluencerDataPort`
  同款写法）：对所有平台答「未接入」，**不返回任何指标**、**不抛异常**、说清责任方与影响；
- **同步端点恒 409 且一条数据都不写**（`POST /channel/account-health/shops/{uuid}/sync`）；
- 绩效数据只能**人工录入**，录入条目 **source 恒为 MANUAL**（入参里根本没有 source 字段，
  服务端写死），页面上一眼看得出这条是手工来的；
- 这个缺口在**能力声明**里是一条**可查的事实**（moduleId=2.4 / available=false / ownerModule=2.4 /
  影响说明），不是文档里的一句话。

> **绝不返回假指标**：一条「ODR = 3.2%」的假同步会让运营以为账号真的在掉号，
> 而现场一个真实指标都没有。

### 2. 🔴 「量不到」既不是 0，也不是低风险

指标值一律 **0..1 比值**（`0.0280` = 2.8%，**不是百分数**），**NULL = 该指标量不到**。

- 量不到的指标**不进已知数**、**不算通过**，并各产出一条 `UNKNOWN` 依据；
- **四项没量全 ⇒ 风险等级 `UNKNOWN`（判不了），不能断言 `HEALTHY`** ——
  把「没测到」写成「健康」是最危险的一种撒谎；
- DB 层用 CHECK 钉住：`HEALTHY` ⇒ `known_metric_count = 4 且 breach_count = 0`；
  `CRITICAL`/`WARNING` ⇒ `breach_count > 0`；`UNKNOWN` ⇒ `breach_count = 0`。

方向也参与判定：「有效追踪率」是 `BELOW_IS_BAD`（越小越危险），
方向与阈值的关系在 DB 层 CHECK 住。

### 3. 🔴 阈值在服务端算、可配置、有版本；**规则变更不追溯改变历史判定**

卷首约束第二条（规则要有生效时间与版本，规则变更不能追溯改变历史判定）落到三层：

| 层 | 落点 |
| --- | --- |
| 判定凭据 | `account_health_assessment` **append-only**（触发器钉住等级/依据/阈值快照/版本/时刻；另有 no-delete 触发器） |
| 阈值快照 | 判定行里存**当时用到的阈值**（metric → warn/critical/direction/source/version），阈值改了之后仍说得出「当时按什么判的」 |
| 规则版本 | 改阈值**必然推进 `rule_version`**（触发器 `guard_account_health_threshold_version`）：版本不许回退，**改值不推进版本直接拒写** |

内置默认阈值（ODR 1%/2.8%、迟发 4%/10%、取消 2.5%/5%、追踪 98%/95%）是**本模块的默认政策、
不是任何平台下发的口径**（各平台定义与阈值都不同），可按租户覆盖；
能力声明里写明这一点，页面上也标「内置默认阈值（可覆盖）」。

### 4. 🔴 排序真的按风险排，且每一家都带得出「为什么排这个位置」

蓝图加粗句「风险排序比明细更重要」 ⇒ 排序放在页面最上面一节，并且：

- 次序：等级（CRITICAL → WARNING → UNKNOWN → HEALTHY）→ 命中项数（多者先）→
  最大越线幅度（越线越狠者先）→ 店铺编码（稳定兜底，保证分页不因并列而抖动）；
- 名次是**全局名次**（第 2 页从第 11 位开始，不是「本页第几个」）；
- 每一家都带 `rankReason`（如「第 3 位：命中 1 项警告阈值，需要尽快跟进。」）；
- **UNKNOWN 排在 HEALTHY 前面**：量不全的店铺**还不知道**会不会掉号，
  而量全且未命中阈值的才是确凿健康。

### 5. 🔴 下游三块未交付 ⇒ 不编数据、不编跳转目标

16.1.1.2 指标明细、16.1.2.1 违规与处罚、16.1.2.2 申诉管理**都未交付**：

- 「政策违规计数」「申诉状态」**不编**（那是「没有数据」，不是「违规数为 0」）；
- 「需处理项」只给**本模块自己真实存在**的待处理项（命中阈值 / 指标不全 / 平台同步未接入），
  跳转目标**只能是本模块自己的页面**（`/compliance/account?shopId=`）——
  跳到不存在的页面比不跳更糟；
- api-proxy 白名单**刻意不放行** `violations` / `appeals` / `trends` 这些形状。

### 6. 店铺清单直接读真实店铺目录

`shop` / `platform` 是 channel 服务自己库里的 2.1.1.1 店铺目录 —— 健康卡片的店铺清单
**直接读它**，不写死店铺常量、不造平行店铺表。`shop` 自带 `data_scope` 策略 ⇒
可见性随店铺负责人 / 部门收敛（蓝图输入句「用户数据范围（店铺维度）」）；
`account_health_threshold` 是**租户级规则**，刻意**只带 isolation 策略**
（否则规则表在部分人眼里凭空消失，改阈值的人看不到自己改过什么）。

### 7. 零外键

本迁移**零外键**（跨表引用一律用触发器 `guard_account_health_shop_ref` 校验 `shop_id`
落在本租户店铺目录里）。既有测试**一行未改**。

## 关键 trade-off

- **「量不全 ⇒ UNKNOWN」比「按已知项给个等级」更保守**：一家只量到 ODR 的店铺排不到「健康」，
  会多占一点排序位。取舍理由：把「没测到」写成「健康」会让运营漏掉一个真在掉号的店；
  而排序里 UNKNOWN 仍排在 HEALTHY 前面，风险并没有被藏起来。
- **内置默认阈值 vs 留空**：阈值表留空的话所有店铺都是 UNKNOWN，模块等于没有判定能力；
  用一组**写明是默认值、可按租户覆盖**的阈值让判定先能跑起来，比留空更有用 ——
  代价是必须把「这是默认政策不是平台事实」写进能力声明与页面。
- **判定凭据落库 vs 现算**：现算更省事，但改阈值会追溯改变历史判定，违反卷首约束；
  落成 append-only 凭据（带阈值快照）多一张表、多一个写入路径，换来「上次为什么这么判」永远答得出来。
- **先算全量再排序再分页**：风险等级是**算出来的**，所以名次只能在全量上算完才有全局意义；
  代价是要把可见店铺的指标一次读进内存（租户店铺数在几十~几百量级，可接受）。

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V300__account_health.sql`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/AccountHealthModels.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/AccountHealthRules.java`（新，纯规则层）
- `backend/services/channel/src/main/java/com/starcon/channel/AccountHealthService.java`（新，jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/AccountHealthController.java`（新，11 个端点）
- `backend/services/channel/src/main/java/com/starcon/channel/vendor/PlatformPerformancePort.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/vendor/UnavailablePlatformPerformancePort.java`（新）

### 后端测试

- `backend/services/channel/src/test/java/com/starcon/channel/AccountHealthRulesTest.java`（新，13 例）
- `backend/services/channel/src/test/java/com/starcon/channel/AccountHealthIntegrationTest.java`（新，20 例）
- `backend/services/channel/src/test/java/com/starcon/channel/AccountHealthTenancyIntegrationTest.java`（新，3 例）

### 前端

- `apps/web/src/components/account-health/account-health-model.ts`（新）
- `apps/web/src/components/account-health/account-health-workspace.tsx`（新）
- `apps/web/src/components/account-health/account-health-model.test.ts`（新，13 例）
- `apps/web/src/app/compliance/account/page.tsx`（新）
- `apps/web/src/app/api/channel/account-health-allowlist.test.ts`（新，5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点 = ad-rules 那条之后；既有条目一字未动、不重排）

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`
  （**codegen 产物**，+17 个 `AccountHealth*` DTO、+11 条路径；其余 5 个服务零漂移）
- `docs/product/capability-blueprint/module-progress.md`（**只改 16.1.1.1 那一行**的状态与证据两格）

## 验证

- `./mvnw -q compile` → exit 0
- `./mvnw -pl services/channel -am test -Dtest='AccountHealth*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **36 例 0 失败 0 错误 BUILD SUCCESS**（Testcontainers 真实 PostgreSQL 16）
- `pnpm typecheck` → 3/3 通过
- `pnpm vitest run apps/web/src/app/api` → **173 文件 999 例全绿**（api-proxy 全量 allowlist 回归，因为改了共享白名单）
- `pnpm vitest run <本模块两个前端测试文件>` → 21 例全绿
- `pnpm exec eslint <本模块文件>` → 零告警

## 后续待办

1. **平台账号绩效适配器**（归卷 02 **2.4 同步运行**）：接上后本模块的
   `POST /shops/{uuid}/sync` 自动放行，指标会以 `source=PLATFORM_SYNC` 落库，
   服务层与前端**一行都不用改**（端口就是那条接缝）。
2. **16.1.1.2 绩效指标监控**：趋势图 / 阈值预警 / 「是否会跌破阈值」的预测 ——
   本模块只给当前状态与排序，不做那三样。
3. **16.1.2.1 违规与处罚**：违规计数落地后，可在健康卡片上补一维
   （届时本模块的 `UNKNOWN` 判定与违规计数是**两件独立的事**，不要混成一条结论）。
4. **16.1.2.2 申诉管理**：申诉状态与申诉入口落地后，再在需处理项里加第四类。
5. 阈值默认值的口径**未与任何平台对齐**（各平台定义不同）；接平台适配器时，
   默认阈值应按平台拆分，或在能力声明里补一条「当前阈值与平台口径无关」的显式提醒。
