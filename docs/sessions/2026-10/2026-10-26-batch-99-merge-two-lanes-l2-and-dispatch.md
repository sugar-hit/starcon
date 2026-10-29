# 2026-10-26 · batch-99 —— 并入 batch-98 两条车道、L2 全量验证、派发下一批 2 条车道

> 批次号 **99**（承接 `batch-98`）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-26 00:00 触发）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
> 本轮**并入 batch-98 派发的 2 条车道**（它们在 batch-98 已 `land`，但**当时未并入**——
> batch-98 的纪律是「本批只验证并推送已在集成头且已绿��代码，新模块代码留到下一轮并入后再过 L2」）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度（开工） | 465 总数 · **406 已交付** · 0 进行中 · **59 未开发**（完成率 **87.3%**，离 465 还差 **59**） |
| 进度（并入后） | 465 总数 · **408 已交付** · 0 进行中 · **57 未开发**（完成率 **87.7%**，离 465 还差 **57**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **2.00 / 1.67 / 1.68**（限 12）、Testcontainers PG **0** 个（限 6） |
| 节流闸 | 距上批 **177 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 调度闸 | `gate status` = `free` / `stale=false` ⇒ `gate acquire` 成功（退出码 0） |
| 本轮回收 | **2 条**：**1.1.2.1 核心指标卡**（reporting，V383）+ **1.3.2.1 预警规则**（identity，V384）—— 两条都是 batch-98 派发、本轮 `land` 后回收的 |
| 本轮派发 | **实际 0 条**（🔴 修正：本行原写「2 条」，那是**计划意图**不是事实 —— 见第九节。`plan` 从未执行：分支 `feat/1-3-2-2` / `feat/1-1-2-2` 不存在、`lanes.json` 里无这两条车道、磁盘上无 V385/V386） |
| 静态预检 | 本批新迁移 = **V384__alert_rule.sql**（唯一）⇒ ①`ADD COLUMN` 无 `DEFAULT` **零命中**、②`SET NOT NULL` **零命中**、③`ADD CONSTRAINT … CHECK` **零命中** |
| api-proxy 复核 | batch-98 遗留清单要求核对「两条车道共同改 `api-proxy.ts` ⇒ 零删除行」⇒ **实测 +86 / −0 行**，既有 239 条元素**一条没动、零重排** |
| **L2 批次闸** | ⏳ **红** —— 后端 9 服务里 **trade 红 2 例**（`AnomalyOrderIntegrationTest` 停留时长断言），其余 8 服务绿；前端 5 项全绿 ⇒ **本批不 push**（见第六节） |
| 收尾三件套 | ① 说明：本文件（补齐于 round-101）· ② commit：**batch-99 的 tick 崩在 commit 之前**（`net::ERR_CONNECTION_CLOSED`），round-101 代为提交 · ③ push：**未 push**（L2 红），round-101 修复后随 batch-100 一起 push（见第十节） |

---

## 一、进度

**开工时**（`node tools/module-progress/sync.mjs`）：18 卷 / 465 个 L4 模块，
已交付 406 · 进行中 0 · 未开发 59（完成率 **87.3%**）。

**并入 2 条车道并重跑台账后**：已交付 **408** · 进行中 0 · 未开发 **57**（完成率 **87.7%**）。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 99 | 0 | 56 |

### 1.1 剩余 57 个的分布（未开发）

| 卷 | 未开发 | 说明 |
| --- | --- | --- |
| **13.x 利润与经营分析** | **20** | 13.2/13.3/13.5/13.6 全未开发；13.1 / 13.4 各剩一半 |
| **18.x 集成开放与智能自动化** | **21** | 18.1 剩 6、18.3 规则引擎 5、18.4 剩 6、18.2 剩 2、18.5 剩 2 |
| 15.x | 6 | 15.3 剩 3、15.6 剩 3 |
| **5.1** | **3** | 5.1.1.1 / 5.1.2.1 等**循环依赖**，需人工拍板拆环（batch-98 已点名，本轮仍未解决） |
| 14.x | 4 | 14.2 剩 2、14.3 剩 2 |
| 1.x（卷 01） | 3 | 1.1.2.2、1.3.2.2、1.5.x 各 1 |

`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（绿，退出码 0）。

**台账完整性核对**（并入后）：
`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md` ⇒ **465**（恒定）；
`sort | uniq -d` ⇒ **空**（零重复 ID）。

---

## 二、容量闸与节流闸

| 项 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| 温度（`pmset -g therm`） | `No thermal warning level has been recorded` | 降频项 < 100 即 STOP | **通过** |
| load（1/5/15 min） | **2.00 / 1.67 / 1.68** | 12 | **通过**（空载基线之上，远未触顶） |
| Testcontainers PG（只数带 `org.testcontainers.sessionId` 标签） | **0** | 6 | **通过**（且**无标签孤儿容器为 0** —— 本轮 `docker ps` 对 `postgres:16-alpine` **零输出**） |
| 在途车道 `running` | **0** | 6 | **通过** |
| 节流闸（距上批） | **177 分钟** | 100（降档后） | **通过** |
| 调度闸 `gate status` | `gate=free` / `stale=false` | — | **可 acquire**（`gate acquire` 退出码 **0**） |

**闸优先级**（温度 > 配额 > PG > load）逐条核对，**无一条命中 ⇒ GO**。

**节流闸时间戳**：`date +%s > .dev-agent/last-batch-at` 写在**通过节流闸之后**、
**取到调度闸之后**（符合协议「时间戳只能在通过闸门之后才写」）——
先取闸再落盘，避免「撞闸的轮次白白消耗掉一个批次位」。

**🔴 温度观测**：整轮 `pmset -g therm` 无降频项。
**容器观测**：L2 期间带 `org.testcontainers.sessionId` 标签的容器峰值见第六节
（本仓已知的 Testcontainers 容器泄漏问题，见 `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`，
标注「待人工决策，调度器不自作主张」）。

---

## 三、本轮回收（并入 batch-98 的 2 条车道）

`node tools/module-progress/lane.mjs merge-ready` ⇒ **成功并入 2 条，零失败、零冲突**：

| 车道 | 模块 | 服务 / 迁移 | 从 | 到 |
| --- | --- | --- | --- | --- |
| `1.1.2.1` | 核心指标卡 | reporting / **V383（预分配但未占用）** | `1e3228b4` | `d385fe18` |
| `1.3.2.1` | 预警规则 | identity / **V384__alert_rule.sql** | `d385fe18` | `6b8747e9` |

合并驱动器的行级兜底并集提示（**如实记录，非报错**）：
- `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`（`api-proxy.ts`）
- `merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录`（台账）

⇒ 台账侧已由 `sync.mjs` 重算覆盖为 408 / 57，**行数恒为 465、零重复 ID**（第一节已核）。

`node tools/module-progress/lane.mjs gc` ⇒ **清理 2 个 worktree**（`1.1.2.1` / `1.3.2.1`），
`.worktrees/` 回到空目录。

### 3.1 batch-98 遗留的复核清单（**本轮兑现**）

batch-98 的收尾清单要求下一轮并入后复核「两条车道共同改动 `apps/web/src/lib/api-proxy.ts`
⇒ 纯新增、零删除、既有条目一条没动」。**实测结果**：

| 检查项 | 期望 | 实测 |
| --- | --- | --- |
| 本批改动量 | 纯新增，零删除 | **+86 行 / −0 行**（= 1.1.2.1 的 +44 + 1.3.2.1 的 +42，与 batch-98 预告完全一致） |
| `reporting/metric-cards` 条目在 | ✅ | ✅ 在（`api-proxy.ts:183` 的正则 `/^metric-cards(?:\/capabilities)?$/`） |
| `identity/alert-rules` 条目在 | ✅ | ✅ 在（`api-proxy.ts:1238` 起的那条 `prefix: 'identity/'` 元素） |
| 既有 239 条元素是否被动过 | ❌ 一条没动 | ❌ **一条没动**（diff 里**零删除行、零重排**） |

---

## 四、静态预检（协议 5.1「已知互相破坏形状」）

本批并入引入的新迁移**只有一个**：`backend/services/identity/src/main/resources/db/migration/V384__alert_rule.sql`
（1.1.2.1 的 V383 **刻意留空未占用**，无迁移文件）。

| 形状 | V384 |
| --- | --- |
| ① `ADD COLUMN` 且无 `DEFAULT` | **零命中** |
| ② `SET NOT NULL` | **零命中** |
| ③ `ADD CONSTRAINT … CHECK` | **零命中** |

⇒ **无命中，不需要在 L2 之前修任何东西。**

**补充核对（V384 的 RLS 与兜底）**：
- `alert_rule` / `alert_rule_hit` 两表均 `ENABLE` **且 `FORCE` ROW LEVEL SECURITY**
  （FORCE 是必须的：表 owner 在 FORCE 下也受策略约束）；
- 两表各有 **BEFORE INSERT 触发器兜底**（`alert_rule_tenant_guard_trigger` /
  `alert_rule_hit_tenant_guard_trigger`）—— 直连 SQL 也写不出跨租户的行；
- `GRANT` 齐备（`alert_rule` 给 SELECT/INSERT/UPDATE/DELETE，**`alert_rule_hit` 只给 SELECT/INSERT**
  ⇒ 命中记录不可改不可删）。

---

## 五、L2 批次闸

按协议 5.2 在集成头 `6b8747e9` 上跑**本批次唯一一次全量验证**。
分三波执行（波次错开是为了压住 Testcontainers PG 容器峰值）：

| 波次 | 服务 | 起止 | 退出码 |
| --- | --- | --- | --- |
| 1（00:02:27 → 00:27:30） | channel / warehouse / trade | 23m / 21m / 25m | channel **0** · warehouse **0** · trade **1** 🔴 |
| 2（00:27:30 → 00:49:26） | catalog / identity / finance | 21m / 3m / 4m | 三个全 **0** |
| 3（00:49:26 → 00:51:13） | reporting / platform / crm | 0m / 0m / 1m | 三个全 **0** |

trade 在波次 1 退出码 1 ⇒ **立即启动 trade 隔离复跑**（00:51:23 起），
用来判断是不是「与其它车道并发抢 Testcontainers 导致的假红」。

前端四项 + 台账闸（`typecheck` / `lint` / `test` / `next build` / `sync --check`）
随隔离复跑一起在 03:19:31 → 03:21:23 收口 ⇒ **五项全 0**。

---

## 六、L2 批量闸结果

🔴 **红。** 根因不在本批次的两个模块，而在 **trade 一个既有测试的时间锚缺陷**。

| 层 | 结果 |
| --- | --- |
| 后端 9 服务 | **8 绿 1 红** —— trade `Tests run: 2458, Failures: 2, Errors: 0` |
| 前端 4 项 | 全绿（typecheck / lint / test / next build） |
| 台账闸 `sync --check` | ✅ 台账与蓝图一致 |

红的两例在同一个类 `com.starcon.trade.AnomalyOrderIntegrationTest`：

```
[ERROR] com.starcon.trade.AnomalyOrderIntegrationTest.地址不可达的单进队列并带停留时长与凭据 -- Time elapsed: 0.183 s <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  260106L
to be between:
  [259080L, 259320L]
	at ...AnomalyOrderIntegrationTest.地址不可达的单进队列并带停留时长与凭据(AnomalyOrderIntegrationTest.java:427)

[ERROR] com.starcon.trade.AnomalyOrderIntegrationTest.风控的两个维度分别给出地址欺诈疑似与重复下单 -- Time elapsed: 0.103 s <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  260106L
to be between:
  [259080L, 259320L]
	at ...AnomalyOrderIntegrationTest.风控的两个维度分别给出地址欺诈疑似与重复下单(AnomalyOrderIntegrationTest.java:484)
```

**trade 单独跑的隔离复跑（00:51:23 → 03:19:31）依然是同样 2 例红**
⇒ **不是并发假红，是真红。**

根因（round-101 取证）：该测试类用**类级 static 基准时刻**播种 ——
`static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)`，
停留时长断言是 `isBetween(72 * 3600L - 120L, 72 * 3600L + 120L)`。
本轮该测试类总耗时 **907.7 秒**（机器负载高 + Testcontainers 起容器慢），
「类加载 → 查询」的漂移达 **906 秒**，远超 ±120 秒容差 ⇒ 断言翻车。

**与本批次无关的三条证据**：

1. 本批次并入的两个模块是 `1.1.2.1`（reporting）与 `1.3.2.1`（identity），
   **一行 trade 代码都没碰** —— `git show --stat d385fe18` 与 `6b8747e9` 的文件清单里零 trade 文件；
2. `git log -- backend/services/trade/` 显示 trade 最近改动是 `3f8b6744 fix(test)` 与
   `ad381ee1 feat(trade): 11.2.3.2`，**都在本批次之前**；
3. 该测试类最后一次改动是 `524f3662 feat(trade): 1.1.1.2 异常订单队列`（更早）。

⇒ 这是 **1.1.1.2 模块遗留的潜伏缺陷被慢机器暴露**，不是本批次的跨车道互相破坏，
也不是 main 同步带进来的回归。

**处置**：round-101 派了一条**修复车道**（只改 trade 测试，不碰生产代码 / 迁移 / 契约 / 前端），
照抄本仓既有先例 `3f8b6744 fix(test)` 的方法
（读数据库自己的时钟做基准 + 避开截断边界 + 默认关闭的时钟错位开关），
修完重跑 L2，绿了再 push。

---

## 七、模块 1.1.2.1 核心指标卡（本轮并入）

**这个功能做什么**：工作台一屏看完「今天/昨天/本月」的销售额、订单量、销量、库存总额、毛利、回款、在途金额，
每张卡带**环比与同比**、趋势箭头和口径说明悬浮提示，点一下可下钻到对应报表。
拿不到上游的指标**明确显示「未接通」并写清归属哪个系统**，不拿 0 冒充。

**技术落地**：

- **迁移**：**无（V383 特意留空未占用）** —— 卷 01 是只读投影层，不产生新的业务事实；
  派生值落库只会比它描述的事实更早过期。**不为了「用掉版本号」造一张没有业务事实的表。**
- **端点**：**2 个**，全挂在 reporting 的 `/reporting/metric-cards`（**零写端点**，卷 01「工作台只读 + 跳转」）：
  - `GET /reporting/metric-cards` —— 七张卡：指标值 + 环比 + 同比 + 趋势箭头 + 下钻链接 + 口径说明 + 数据截至时刻；
  - `GET /reporting/metric-cards/capabilities` —— 逐指标报：有无上游、算法/分子分母/来源表、
    口径 ownerModule、单位与币种、下钻目标是否可用、未接通清单。
- **页面**：`/dashboard/metric-cards`
  （`apps/web/src/app/dashboard/metric-cards/page.tsx`）
  + `apps/web/src/components/dashboard/metric-card-{model,workspace}.tsx`；
  加载 / 错误 / 空三态齐全；工作台卡片注册表**只新增自己那一条**。
- **测试**：后端 `ReportingMetricCardIntegrationTest` 14 例 + `ReportingMetricCardTenancyIntegrationTest` 3 例
  （**多租户三断言实跑 Testcontainers PostgreSQL 16**）= **17 例全绿**；
  前端 `metric-card-model.test.ts` **30 例** + `metric-cards-allowlist.test.ts` **7 例** = **37 例全绿**；
  **全量 api-proxy 回归 253 文件 / 1429 例全绿**。

**关键设计决策**：

1. **口径绝不重算**（蓝图原文：「口径由 13 单点定义，工作台只引用不重算」）
   ⇒ 每个指标的数值与公式**只能来自** `reporting_metric_definition` / `reporting_metric_fact`；
   **SQL 与 Java 里不出现任何硬编码指标公式**；前端也不自己拿两个数算比率，后端算好再给。
2. **七张卡里六张真取到数，一张如实报「未接通」**：
   销售额 / 订单量 / 销量 / 毛利 / 库存总额走 reporting 自己的投影；**回款走 finance 的授权只读 API**；
   **在途金额**（`IN_TRANSIT_VALUE`）在口径表里**没有指标码**，上游只给数量不给金额
   ⇒ 在 capabilities 里报 `available=false` + `ownerModule`（06 采购在途 / 07 库存在途），
   **不给公式也不给分母**（给了就是编一个），卡面显示灰格 + 归属说明，**不是 0**。
3. **「算不出来」与「没有」严格分开**：同比/环比**基期没数据**⇒ 变化率 `null`、显示「无同期基期」，
   **不是 0%、不是「持平」、更不是「-100%」**；派生比分母为 0 ⇒ **算不出，不是 0%**；
   多币种**没有汇率就是不可判定**，**不按 1:1 算**。
4. **跨服务只走授权 API，绝不跨库查表**（AGENTS.md 模块边界纪律）
   ⇒ 回款走 finance 的只读端点；**不跨库直接查 warehouse / finance 的表**。
5. **时间边界按租户时区算，不写死 UTC** —— 13.1.1.2 曾栽在写死 `timezone='UTC'` 上。
6. **展示层十进制精确**：BigInt 缩放 + HALF_UP（`1.005 ⇒ 1.01`，浮点会算成 `1.00`）；
   后端 `BigDecimal` + `NUMERIC`，**禁浮点**。

> 车道自己的会话日志：`docs/sessions/2026-10/2026-10-25-1-1-2-1-core-metric-cards.md`（随合并进集成头）。

---

## 八、模块 1.3.2.1 预警规则（本轮并入）

**这个功能做什么**：用户自己配阈值预警（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、应收超期 Y 天），
系统预置跨境常用规则集、用户可改参数或关闭；配置界面之外还有「命中历史」区。

**技术落地**：

- **迁移**：`V384__alert_rule.sql`（**一个文件**）—— `alert_rule` + `alert_rule_hit` 两表
  + `tenant_id NOT NULL` + ENABLE/**FORCE** RLS + 索引 + GRANT + BEFORE INSERT 触发器兜底
  + **权限码登记**（`identity.alert_rule.read` / `identity.alert_rule.write`）。
  ⚠️ **同一服务里不许出现两个同版本号的迁移** ⇒ 表与权限码必须合并进同一个 V384。
- **端点**：**9 个形状**，全挂 `/identity/alert-rules`：
  GET `/capabilities`、GET `/`、GET `/hits`、POST `/`、PUT `/{uuid}`、
  POST `/{uuid}/disable`、POST `/{uuid}/enable`、POST `/{uuid}/reset`、DELETE `/{uuid}`；
  **租户与 membership_id 只从已认证会话解析**，请求体**不接受** tenantId / userId / membershipId。
- **页面**：`/notifications/rules`（`apps/web/src/app/notifications/rules/page.tsx`）
  + `apps/web/src/components/notifications/alert-rule-{model,workspace}.tsx`；加载 / 错误 / 空三态齐全。
- **测试**：后端 `AlertRuleIntegrationTest` 26 例 + `AlertRuleTenancyIntegrationTest` 6 例 = **32 例全绿**
  （含多租户三断言，Testcontainers PG 16 实跑）；
  前端 `alert-rule-model.test.ts` 18 例 + `alert-rule-allowlist.test.ts` 5 例 = **23 例全绿**；
  **全量 api-proxy 回归 253 文件 / 1427 例全绿**。

**预置规则集（5 条，写在代码里 = 系统事实，不落库）**：

| 规则码 | 对象类型 | 度量 | 默认阈值 | 时间窗 | 比较方向 · 基准 |
| --- | --- | --- | --- | --- | --- |
| `SKU_STOCK_COVER_DAYS_LOW` | SKU | STOCK_COVER_DAYS | 14 | INSTANT | LT · NONE |
| `SHOP_SALES_AMOUNT_DROP` | SHOP | SALES_AMOUNT | 20 | CALENDAR_MONTH | LT · PERIOD_OVER_PERIOD |
| `LEGAL_ENTITY_RECEIVABLE_OVERDUE_DAYS` | LEGAL_ENTITY | RECEIVABLE_OVERDUE_DAYS | 30 | INSTANT | GE · NONE |
| `SHOP_GROSS_MARGIN_DROP` | SHOP | GROSS_MARGIN | 5 | CALENDAR_MONTH | LT · PERIOD_OVER_PERIOD |
| `SUPPLIER_PAYABLE_OVERDUE_DAYS` | SUPPLIER | PAYABLE_OVERDUE_DAYS | 30 | INSTANT | GE · NONE |

默认时区 `Asia/Shanghai`（**不写死 UTC**）；生效值 = 预置默认值 ⨯ 租户覆盖。

**关键设计决策**：

1. **规则引擎本体在 18 系统**（蓝图原文）⇒ 本模块**只做配置界面，不实现求值**。
   开工取证已查实：**18.3.1.1 规则列表 / 18.3.1.2 规则编辑器都是 `未开发`** ⇒
   命中历史读端点返回**空集合 + 未接通声明**（响应带 `engineAvailable=false` + `engineOwnerModule` + `engineNote`），
   页面显示「规则引擎未接通 · 归 18 系统负责」而**不是「0 次命中」** ⇒ **绝不伪造命中记录**。
2. **预置规则集是系统事实，写在代码里**（照抄 `ReportingMetricCatalog` 的「口径唯一」做法），
   **不落库当数据源**；用户改的参数 / 自定义规则 / 关闭动作才落库；
   **生效值 = 预置默认值 ⨯ 租户覆盖**，**SQL 里不许硬编码阈值**。
3. **规则定义的五项**（对象类型、度量、阈值、时间窗、比较方向）**都要在数据层被钉住**（受控词表 / CHECK），
   度量码沿用 `ReportingMetricCatalog` 的命名口径，**不能是随便一个字符串**；
   阈值 `BigDecimal` + `NUMERIC`，**禁浮点**。
4. **规则定义不塞进 `user_preference`**（JSONB，16KB / 单数组 64 项上限，整份覆盖）⇒ 另建自己的表。
5. **预置规则不允许删除，只允许关闭**（蓝图只说「用户可改参数或关闭」）—— 这是设计决定。
6. **写权限额外要求授权范围 `ALL`**（规则是租户级共享配置）⇒ 平台本身禁止把写权限角色绑到非 ALL 范围。
7. **续跑期间自查出并修掉的两个真实缺陷**（**不是环境问题**）：
   ① **阈值短语单位后缀**——服务端 `unitLabel` 是**标签**不是量词，拼出来像「低于 14 天数」；
   ② **草稿校验措辞**——「没选」与「不在受控词表里」混成一句 ⇒ 拆成「请选择时间窗」vs「时间窗不在受控词表里」。
8. **api-proxy 实测取证**：**+42 行 / 零删除**，id 段用**完整 UUID 形状**
   `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+` —— 后者会把 disable/enable/reset 当成 id 吃掉）。

> 车道自己的会话日志：`docs/sessions/2026-10/2026-10-25-1-3-2-1-alert-rules.md`（随合并进集成头）。

---

## 九、本批派发

🔴 **实际派发 0 条。** 本文件原写「派发 2 条」，那是**计划意图**而不是事实 ——
round-101 取证：

| 取证项 | 结果 |
| --- | --- |
| 分支 `feat/1-3-2-2` / `feat/1-1-2-2` | **不存在**（`git branch -a --list` 零命中） |
| `lanes.json` 里的对应车道 | **不存在**（210 条车道状态全是 `merged`，无 `running` / `ready`） |
| 磁盘上的迁移 | 无 `V385` / `V386`（只有 `V384__alert_rule.sql`） |
| 集成头 reflog | 最后一次活动是 00:00:50 的两次 merge，**之后再无任何提交** |
| 任务书文件 | `.dev-agent/taskbooks/r99-1.3.2.2.md` / `r99-1.1.2.2.md` **写了但没派出去**（00:06 写） |

⇒ 本轮 tick 在「写完任务书」与「真正 `plan` + 派发」之间就崩了。
**本文件里所有关于 1.3.2.2 / 1.1.2.2 的描述都属于「计划」，不得当作已派发。**
round-101 已把这两条模块重新排进新一轮（见 batch-100）。

**本轮 tick 的崩因**：`net::ERR_CONNECTION_CLOSED`（agent-runtime），
崩在 merge 之后、L2 之后、commit 之前 ⇒ 台账改动与本文件都没进版本库。

---

## 十、收尾三件套

| 件 | 状态 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅（round-101 补齐） | 本文件 + `2026-10-26-1-1-1-2-anomaly-dwell-anchor.md`（修复车道自己的日志，随合并进集成头） |
| ② commit | ✅（round-101 代为提交） | batch-99 的 tick 崩在 commit 之前；round-101 以 `docs(batch-99):` 提交本文件 + 台账 |
| ③ push | ❌ **本轮不 push** | **L2 红**（trade 2 例）⇒ 协议「L2 没绿绝对不 push」；成果与诊断留在集成头本地，round-101 修复后随 batch-100 一起 push |

⚠️ 本轮 tick 还留了两处**孤儿状态**，round-101 已清理：

1. **调度闸**：tick 崩在闸里，闸目录里残留了它的 keepalive 守护进程，
   每 4 分钟新建一个文件刷新闸目录 mtime ⇒ `gate status` 永远显示 `held` + `stale=false`
   ⇒ **后续每一轮 tick 都会撞闸，流水线会被永久锁死**。
   round-101 先确认持闸者已死（owner PID 不存在、持闸会话状态 `error`、
   无任何构建 / 测试进程在跑、其它 tick 会话列表里没有活的流水线会话），
   才停掉那个孤儿守护进程并释放闸 —— **不是抢活闸**。
2. **未提交的台账改动**：`docs/product/capability-blueprint/module-progress.md`
   带 407 → 408 的统计变化（`sync.mjs` 重算）。`git status --porcelain` 不空 ⇒
   `merge-ready` 会以「主工作区不干净，拒绝切分支」直接退出 ⇒ **并入通道也是堵死的**。
   round-101 以本 commit 一并提交，通道恢复。

---

## 十一、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过） |
| 每批车道数 | **2**（`LANES` 4 减半） |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时**（12 批/天） |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70**（含「L2 返工」×0.90、「BLOCKED / 重试」×0.96、「上游未就绪」×0.95、「长尾占位」×0.90） |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余可自动排完量 | **57** 个未开发 − 4 个循环依赖 ⇒ **53 个待排** ⇒ 53 ÷ 17 ≈ **3.1 天**（区间 **3–5 天**） |

---

## 十二、风险提示

1. **🔴 worker 会话崩溃仍是第一号风险** —— 已连续五轮吃掉整批成果
   （batch-92/93/94 `ERR_CONNECTION_CLOSED`、batch-97 `ERR_HTTP2_PING_FAILED`、batch-98 传输断开）。
   对策（batch-97 提出、batch-98 二次验证、本轮继续写进任务书）：
   **代记心跳 + 朝原 worktree 重派 + 任务书第一条命令就是「提交存量成果」** + **每完成一个可编译单元就 commit 一次**。
2. **🔴 L2 与车道并发会互相拖垮** —— batch-98 实测：L2 与 4 条车道 L1 并发时 PG 峰值 16、
   identity 12 失败 + 1 错误**全是假红**；隔离复跑 587 例全绿。
   ⇒ **本轮把顺序倒过来了：先跑完 L2，再派发车道**（协议步骤 4/5 的常规顺序会让 L2 与新车道的 L1 撞上）。
3. **🟡 共享 static 容器仍是定时炸弹** —— `IdentityIntegrationTestBase` 有 43 个测试类继承，
   一次挂死会连锁拖垮同一 JVM 内的后续所有测试类。根治方案（给不 `.stop()` 的类补 `@AfterAll`）
   在 `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`，标注「待人工决策，调度器不自作主张」⇒ **需要人工拍板**。
4. **🟡 4 个循环依赖模块**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`）仍需人工拍板拆环。
5. **🟡 18.5.1.1 模板管理 / 18.5.2.1 投递记录 / 18.3 规则引擎均未交付** ——
   18.3 的缺口已被 1.3.2.1 用「未接通声明」如实处理；
   **18.5.1.1 / 18.5.2.1 的缺口正是本轮派发的 1.3.2.2「外部渠道推送」要面对的**，
   任务书已把「投递记录归本模块自己（V385）」与「外部通道未接入 ⇒ 端口如实答未接入、绝不返回假成功」
   两条纪律钉进去（照抄 platform 的 `UnavailableFinanceSoftwarePushPort` 范式）。
6. **🟡 本仓没有 secret manager 服务** —— 蓝图 1.3.2.2 写死「凭据不落库，只存 secret manager 引用」，
   而 `platform` 的 V377 只有 BCrypt 摘要、没有引用列 ⇒ 1.3.2.2 必须把这个缺口做成一条**可查的事实**，
   而不是一句注释里的说明。任务书已把这一点写成本车道的硬约束。
7. **🟡 1.1.2.2「库存天数」「利润率」可能没有上游** —— `ReportingStoreComparisonCatalog` 现存指标码里
   没有对应的 `STORE_*` 码 ⇒ 任务书要求逐条核实并按 1.1.2.1 的先例**如实标 `available=false` + ownerModule**，
   **不给公式也不给分母**。

---

## 十三、文件清单

**本轮调度者写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-26-batch-99-merge-two-lanes-l2-and-dispatch.md`（本文件，新增）

**本轮新建的辅助 worktree**：

- `/Users/guan/Projects/starcon-worktrees/l2-verify`（detached @ `6b8747e9`，L2 后端 9 服务分服务验证）

**本轮新增的运维脚本与日志**（均在 `.dev-agent/`，gitignored）：

- `.dev-agent/l2-run-round99.sh`（分服务 L2 驱动，三波）
- `.dev-agent/l2-frontend-round99.sh`（前端四项 + `sync --check`）
- `.dev-agent/l2-chain-r99.sh`（等后端跑完自动接前端）
- `.dev-agent/keepalive-r99.sh`（调度闸续期守护，每 4 分钟新建文件刷新闸目录 mtime）
- `.dev-agent/taskbooks/r99-1.3.2.2.md`、`.dev-agent/taskbooks/r99-1.1.2.2.md`（两条车道的任务书原文）
- `.dev-agent/l2/l2-*.log`、`.dev-agent/l2/frontend-round99.txt`、`.dev-agent/l2/l2-summary-round99.txt`

---

## 十四、后记（round-101 补记）

- 本文件由 round-101（2026-10-26 04:00 tick）补齐第五 / 六 / 九 / 十节，
  并**修正概览表里「派发 2 条」的不实描述**（实为 0 条，只有计划）。
- round-101 的动作、修复车道的交付、L2 复跑与 push 结果见
  `docs/sessions/2026-10/2026-10-26-batch-100-fix-trade-anchor-l2-and-dispatch.md`。
