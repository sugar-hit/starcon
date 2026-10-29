# 2026-10-29 · 13.6.1.1 自定义报表

> 单模块车道（`feat/13-6-1-1`）。本车道只碰 `reporting` 服务 + 一个前端路由 + 一条 api-proxy 白名单
>
> - 台账的 13.6.1.1 那一行。
>
> | 项       | 值                                                                         |
> | -------- | -------------------------------------------------------------------------- |
> | 模块     | 13.6.1.1 自定义报表（卷 13「利润与经营分析」L3 13.6.1 报表构建下的第一格） |
> | 落地服务 | reporting（卷 13 的唯一归属服务，端口 8088）                               |
> | 迁移     | `V408__custom_report.sql`（七张表，纯 CREATE TABLE）                       |
> | 车道分支 | `feat/13-6-1-1`（不 push，由调度者并入集成头）                             |
> | 台账     | 13.6.1.1 行 由「未开发」改为「已交付」+ 证据格（其余行一字未动）           |

---

## 这个功能做什么

业务人员**自助构建报表**：选数据源（**受控的语义层指标**，不是直连库表）、维度、度量、筛选、
图表类型，保存、共享（分享链接）、挂定时订阅。

蓝图关系句把边界写死了：**「只允许基于语义层指标自助，不开放任意 SQL」** —— 否则报表口径会分裂成
几十个版本，最终没人知道该信哪个。本模块的全部设计都是为这一句话服务的。

## 关键 trade-off

### 1. 派生型指标：按登记口径分别取数再在服务端算

派生型（`DERIVED_RATIO` / `DERIVED_DIFF`）**不写公式**，只引用口径表里的分子分母。引擎递归展开依赖 ⇒
取出**基础指标**（`SUM` / `LAST`）⇒ 分组聚合 ⇒ 在服务端用 `BigDecimal` 算。

- 🔴 **分母为 0 ⇒ 比率为 `NULL`（`NOT_COMPARABLE`），不是 0**。理由写在代码注释、capabilities、
  能力声明与页面上：「压根算不出来」与「算出来是零」在报表里是**相反的两件事**，用 0 冒充会让
  「没同步到 / 暂停中」看起来像「效率为零」。
- 派生深度上限 4 层，口径表被改坏时**直接报错**而不是无限递归。

**取舍**：没有在前端算比率。前端再算一遍就会引入二进制浮点误差，而且会让「界面上的毛利率」和
「报表里的毛利率」对不上 —— 两个真相比一个慢的页面糟得多。

### 2. 涉币指标不跨币种相加

`currency_aware=true` 的指标**按币种分行**（USD 一行、EUR 一行），**没有**跨币种合计 —— 把两个币种的
金额加起来是造假。与货币无关的量（笔数 / 件数）才给一个跨币种合计，它的币种显示为**空串**
（空串 = 与币种无关，不是「币种未知」，与 13.2.2.2 同一纪律）。

**取舍**：本模块**不提供本位币折算合计**。13.1.1.1 有汇率快照折算能力，但自定义报表是「按登记口径自助」，
折算是**另一套口径**（汇率时点、哪些指标允许折算）—— 塞进来会让「口径唯一」变成「口径两套」。

### 3. 存量型指标（`LAST`）的口径与 13.1.1.1 **完全一致**

`LAST` 取**窗口末之前最近一天的快照**（余额不能累加），**不是**「窗口内最近一天」。

**取舍**：一开始想过用「窗口内最近一天」，这样所有指标的日期谓词统一、SQL 简单得多。但那会与
13.1.1.1 经营总览**给出两个不同的数**（同一个指标、同一个窗口）⇒ 正是蓝图禁止的「口径分裂」。
⇒ 宁可让 SQL 多一层（`LAST` 的日期下界不生效 + 相关子查询取每个 `(指标, 币种)` 的最大 `bucket_date`）。

### 4. 维度白名单 = 投影事实上真实存在的列

5 个维度码（`SCOPE_TYPE` / `LEGAL_ENTITY` / `SHOP` / `BUCKET_DATE` / `CURRENCY`）在 DB 层有 CHECK，
在服务层是枚举常量。SQL 片段**只从枚举常量取**，筛选值**一律走 jOOQ 绑定参数**。

- 法人 / 店铺维度给的是 **uuid 文本** —— 语义层不带名称档案，**不编一个名字**（编名字 = 第二个真相）。
- 测试断言「筛选值不出现在 SQL 文本里」（`explainQuerySql` 这个可观测点就是为这条断言存在的）。

### 5. 分享链接：令牌绑定租户 + 绑定归属

令牌 32 位小写十六进制（128 bit 熵），**全局 UNIQUE**；行落在 `tenant_id` 下 ⇒ 别的租户拿着同一个令牌
在自己的租户上下文里**查不到**。未授权访问与「报表不存在」抛**同一个异常、同一句话**
（`"报表不存在或无权访问"`），HTTP 层断言两个 404 的**响应体逐字相同**。

**取舍**：分享链接**必须登录**（要 `reporting.custom-report.read` 权限）。真正的「外部分享」需要匿名
令牌 + 单独的过期策略，那是 13.6.2.1 那一层的事。

### 6. 导出与实际投递：**显式标未交付**

蓝图把导出与订阅推送放在 13.6.2.1（未交付）⇒ 本模块只做「订阅计划 + 待投递记录（队列）」，
`capabilities` 里 `exportAvailable=false` / `deliveryAvailable=false` + 归属模块 + 影响说明，
页面上按原样显示「未交付」，**不提供假按钮**。

- 投递记录状态是 `PENDING`（待投递），**不是「已发送」**。
- 本服务**不装 `@Scheduled`**（与 channel / identity 同一纪律：多副本下自带定时器要做主节点选举与
  分布式锁）⇒ 到点落成待投递记录由**外部调度**调 `POST /reporting/custom-report/deliveries/tick` 触发。

## 文件清单

### 后端（reporting 服务）

| 文件                                   | 作用                                                           |
| -------------------------------------- | -------------------------------------------------------------- |
| `db/migration/V408__custom_report.sql` | 七张表 + RLS + 触发器（度量指标码必须已登记 / cron 必须 5 段） |
| `ReportingCustomReportCatalog.java`    | 维度 / 运算符 / 图表类型白名单 + 能力声明的静态事实            |
| `ReportingCustomReportCron.java`       | 5 段 cron 的受限子集解析 + 下一次命中时刻（UTC）               |
| `ReportingCustomReportModels.java`     | 出入参模型（DTO 名统一带 `CustomReport` 前缀）                 |
| `ReportingCustomReportService.java`    | 报表 CRUD + 查询引擎（分组 / 派生求值 / 币种规则 / 分页）      |
| `ReportingCustomReportController.java` | 18 条端点（`/reporting/custom-report/**`）                     |

### 测试

| 文件                                      | 例数 | 验什么                                                                                             |
| ----------------------------------------- | ---- | -------------------------------------------------------------------------------------------------- |
| `CustomReportIntegrationTest.java`        | 34   | 口径唯一（服务层 + **DB 触发器**）、派生值、分母 0、币种、绑定参数、图表形状约束、分享、订阅与投递 |
| `CustomReportTenancyIntegrationTest.java` | 9    | AGENTS.md 的三条隔离断言 + 指标定义租户级 + 七表 FORCE RLS                                         |

### 前端

| 文件                                                               | 作用                              |
| ------------------------------------------------------------------ | --------------------------------- |
| `apps/web/src/app/analytics/custom-report/page.tsx`                | 路由 `/analytics/custom-report`   |
| `apps/web/src/app/analytics/custom-report/shared/[token]/page.tsx` | 分享链接视图                      |
| `apps/web/src/components/analytics/custom-report-model.ts`         | 纯函数层（展示格式化 + 页面状态） |
| `apps/web/src/components/analytics/custom-report-workspace.tsx`    | 真实工作区                        |
| `apps/web/src/components/analytics/custom-report-shared-view.tsx`  | 分享链接视图                      |
| `apps/web/src/components/analytics/custom-report-model.test.ts`    | 27 例前端断言                     |

### 共享文件（多车道共用，按「类型名 / 路径 / 模块 ID」自动求并）

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（`prefix: 'reporting/'`，
  首 token `custom-report`），锚在**数组末尾**，纯新增零删除零重排。
- `packages/api-contracts/src/contracts/reporting.ts` + `reporting.openapi.json` —— **codegen 产物**
  （`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`），未手改。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 13.6.1.1 那一行。

## 关键设计决策（钉在数据层 / 测试里的那些）

1. **度量必须引用已登记的 `metric_code`** —— DB 触发器 `guard_reporting_custom_report_measure_known`
   拒绝未登记的指标码 ⇒ 「口径唯一」不是注释，是写不进。
2. **维度只有 5 个白名单码** —— DB CHECK + 服务层枚举，两道关。
3. **筛选值只走绑定参数** —— SQL 片段只来自枚举常量；测试断言筛选值不出现在 SQL 文本里。
4. **分母为 0 ⇒ `NULL`** —— 单元级断言 + 能力声明 + 页面文案三处都写明。
5. **涉币指标按币种分行** —— 断言「没有空串币种的合计」。
6. **分享链接的 404 逐字相同** —— HTTP 层断言两个 404 的响应体相等。
7. **指标定义是租户级的** —— 租户 A 自登记的指标不进 B 的清单，B 也引用不了（口径不跨租户）。
8. **时间列显式 `?::timestamptz`** —— jOOQ 3.19 会把 `OffsetDateTime` 绑成 varchar，PG 直接拒。
   （踩过一次：`reporting_custom_report_subscription` 的插入直接 `BadSqlGrammarException`。）

## 验证

| 闸   | 命令                                                                                                                                   | 结果                                                       |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| L0   | `cd backend && ./mvnw -q compile`                                                                                                      | 绿                                                         |
| L0   | `pnpm typecheck`                                                                                                                       | 绿（3 包全绿）                                             |
| L1   | `./mvnw -pl services/reporting -am test -Dtest='CustomReport*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **43 例全绿**（34 + 9，实跑 Testcontainers PostgreSQL 16） |
| L1   | `pnpm vitest run apps/web/src/components/analytics/custom-report-model.test.ts`                                                        | **27 例全绿**                                              |
| L1   | `pnpm vitest run apps/web/src/app/api/trade`（改了 api-proxy.ts ⇒ **必须全量跑**）                                                     | **71 文件 394 例全绿**                                     |
| L1   | `pnpm vitest run apps/web/src/lib/api-proxy.test.ts`                                                                                   | 16 例全绿                                                  |
| 静态 | `node .tmp/api-proxy-zero-hit.mjs`（不入库）                                                                                           | 零命中核对通过                                             |
| 静态 | `node tools/module-progress/sync.mjs --check`                                                                                          | 绿                                                         |

## 后续待办（不属本模块）

- **13.6.2.1 导出与订阅**：异步导出任务表 + 文件有效期 + 邮件 / IM 投递器；接上后本模块的
  `deliveries` 队列由它推进（`PENDING` → `DELIVERED` / `FAILED`）。
- **13.6.1.2 看板搭建**：可直接引用 `reporting_custom_report`（它已带 `visibility`）当组件来源，
  **不需要改结构**。
- **18.4.1.1 自然语言取数**：下游就是本模块的受控语义层，两边共用同一张 `reporting_metric_definition`。
- 本模块**不提供本位币折算合计**（口径取舍，见上），若将来要加，需先定「哪些指标允许折算」的独立口径。
