# 2026-10-29 · batch-113 —— 14.3.2.1 归因分析 + 13.5.3.1 物流分析

> 本批由 **round-131 tick**（14:00 触发）并入集成头并跑 L2；该 tick **中途崩在 Token Plan 上限**，
> **round-132 tick**（15:00 触发）接管收尾（补 commit + push、更正起草稿笔误）。
>
> | 项         | 值                                                                                                                                        |
> | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
> | 本批并入   | `14.3.2.1 归因分析`（V404, channel）+ `13.5.3.1 物流分析`（V406, reporting）                                                              |
> | 并入前台账 | 429 已交付 / 0 进行中 / 36 未开发（92.3%）                                                                                                |
> | 并入后台账 | **431 已交付 / 0 进行中 / 34 未开发（92.7%）**                                                                                            |
> | 静态预检   | **绿**（三个互相破坏形状全部零命中）                                                                                                      |
> | L2         | **绿**（后端步骤退出码 1，但 3 例失败经**四条独立证据**判定为假红、隔离复跑 4/4 绿；步骤 2–6 由 round-132 补跑全绿 —— 见「L2 批量验证」） |
> | 闸         | round-131 取闸时闸被**崩掉的同步任务**持有，等 TTL 到期后接管；round-131 随后**死在闸里**，闸由僵尸采样器刷新鲜度（见「调度取证」）       |
> | 模式       | **降档模式**（已过 `TOKEN_HORIZON` 2026-10-25 ⇒ LANES 4→2、节流闸 50→100 分钟）                                                           |
> | 本轮派发   | **零**（节流闸：距上批 46 分钟 < 100 分钟阈值 ⇒ 零派发）                                                                                  |

---

## 模块 14.3.2.1 归因分析

**这个功能做什么**：运营能配置并公示**一套归因口径**（比如「末次点击 + 平台归因窗口」），
把平台广告带来的订单**归到具体的内部 SKU**，然后看到每个 SKU 的
广告后净利、TACOS、ACOS 以及一个**增量销售额估算**。
改规则时旧口径的历史报表**不会被改写** —— 新口径跑出的是**新版本的事实**。

**技术落地**：

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V404__ad_attribution.sql`
  —— 纯新建**五张表**、**零 ALTER 任何既有表**：
  - `ad_attribution_rule`（归因规则：租户级配置事实，partial unique index 钉死
    「一个租户同一时刻只有一套生效口径」，改规则 version + 1）
  - `ad_attribution_order`（订单级归因候选 = 平台广告归因数据；幂等唯一索引用
    `coalesce(internal_sku_id, 零 uuid)` 让 NULL 行之间也能冲突 ⇒ **重复导入是刷新不是翻倍**）
  - `ad_attribution_click`（点击触点：平台侧点击 id + 点击时刻 + 平台自己标记的归因点击）
  - `ad_attribution_fact`（归因结果，**唯一键 `(tenant_id, rule_id, rule_version, order_id)`**；
    每行自带**规则快照**；`attributed` 是 **GENERATED 列** ⇒ 「这一行进不进报表」是派生量，
    服务层写不进去、绕过服务层也编不出来）
  - `ad_attribution_export`（导出留痕**带规则快照** ⇒ 事后翻出一张 CSV 答得出「当时用的是哪套口径」）

  五张表均 `tenant_id uuid NOT NULL` + ENABLE **且** FORCE ROW LEVEL SECURITY isolation 策略
  （USING 与 WITH CHECK 同时钉死）+ `data_scope`（RESTRICTIVE，按店铺收敛）+ GRANT 零权限。

- **端点**：11 个，挂 `/channel/ad-attributions/**` ——
  `GET /capabilities`（能力声明）、`GET /options`（筛选项 + 逐指标口径 + 规则原文）、
  `GET /rules`、`GET /rules/current`、`POST /rules`、`POST /import`、`POST /run`、
  `GET /records`（明细，真分页 + 规则快照随行）、`GET /records/{id}`、
  `GET /report`（广告后利润报表）、`GET /export`（CSV）、`GET /exports`（导出留痕）。
  时间范围**必填**，跨度上限 400 天（超了 400，**不悄悄截断**），排序走白名单映射。
  **没有** `trend` / `sync` 端点（蓝图输出句没有趋势图；平台数据走 `POST /import`）。
- **页面**：`/ads/ad-attributions` —— 真实工作区：归因规则区 → 导入区（被拒行逐条列明原因）
  → 归因口径常驻（服务端原文，前端不写第二份）→ 筛选 → 归因明细表（含规则快照列）
  → 广告后利润报表（TACOS / ACOS / 广告销售占比 / 广告后净利 / 增量销售额估算）
  → 增量口径常驻（含「不是因果增量」的边界）→ 汇总按币种分块 → 导出留痕 → 能力声明。
  **前端不做任何算术**；`null` 渲染成「不可比」而不是 `0.00%`；加载 / 错误 / 空三态齐全。
- **测试**：`AdAttributionIntegrationTest` **21 例**（真实 PostgreSQL 16 + Testcontainers）
  - `AdAttributionTenancyIntegrationTest` **3 例**（租户隔离三断言：用**应用角色**
    `channel_app`（nobypassrls）查 —— 容器超级用户是 BYPASSRLS，证明不了隔离；
    五张表逐张断言 `relforcerowsecurity=true`）+ 前端 `ad-attribution-model.test.ts` **32 例**
  - `ad-attribution-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **规则版本进事实行唯一键** —— 蓝图加粗句「归因规则必须固定并公示，规则变了历史报表不可比」
   的落点。改规则后重跑产生**新版本的事实**，旧版本事实**原样保留** ⇒
   不会出现「旧报表的行被新口径悄悄改写」。报表默认看当前版本，显式传
   `ruleId + ruleVersion` 可翻历史口径做对比。
2. **归因状态拆成两个正交维度** —— `attribution_status` 只描述**点击 / 模型口径**
   （`ATTRIBUTED` / `NO_ELIGIBLE_CLICK` / `NO_PLATFORM_ATTRIBUTED_CLICK`）；
   「归不到唯一内部 SKU」不是这一列，而是 `internal_sku_id` 为空。
   ⇒ 「归因不成立」有两个**可区分**的原因，不会互相掩盖。
3. **增量效果 = 反事实单件均价外推法（推导值，不是平台原值）** —— 平台只给归因结果、不给增量。
   口径 `S_cf = Q_ad × (S_org / Q_org)`；**边界写进 `incrementalityNote` 随响应返回**：
   这个估计衡量的是「广告单与自然单的**单件均价结构差**」，**不是因果增量**
   （真正的增量需要留出组 / 地域对照实验，平台不提供 ⇒ `causal_incrementality.available=false`）。
4. **分母为 0 一律给 null（不可比），不是 0**；**先汇总再相除**（Σ分子 / Σ分母）；
   **不跨币种相加**（本仓没有汇率源）⇒ 汇总按币种拆开。
5. **跨服务读不到就降级 + 如实说原因** —— 利润侧读不到 ⇒ 利润侧各列全空 +
   `profitAvailable=false` + 原因，**不是 0**；catalog 读不到 ⇒ SKU / SPU 编码为空 + 原因。
6. **api-proxy 白名单新增一条独立 `channel/` 元素**，插入锚点固定在 `ad-product-reports`
   那条元素的 `pattern:` 行之后，**既有元素一字未动、不重排**；既有 100+ 条正则里
   `ad-attributions` 首 token **零命中** ⇒ 新增不影响任何既有路由。
   改了共享白名单 ⇒ 按规矩跑了**全量 allowlist 回归**（413 例 / 82 文件全绿）。

---

## 模块 13.5.3.1 物流分析

**这个功能做什么**：运营能看到**物流成本到底花在哪** —— 全口径成本总览（尾程 / 头程分摊 /
关税 / 包材，逐币种）、单均运费、渠道对比、时效达成率、各国成本对比、异常件占比，
以及**类目维度的物流成本占销售额比**（这是下游「5 定价」的输入）。
还能下钻到运单级，并自己配阈值规则。

**技术落地**：

- **迁移**：`backend/services/reporting/src/main/resources/db/migration/V406__logistics_analysis.sql`
  —— **八张分析投影表 + 一张阈值规则表**，纯 CREATE TABLE、零 ALTER 既有表、零跨库外键：
  - 成本 / 渠道 / 时效 = `as_of`（= 窗口止日）粒度**快照**
  - 运单 / 分摊 / 异常件 = **行级事实**（按业务日 / 结账时点查窗口）
  - `reporting_logistics_analysis_rule`（四类阈值：时效达成率目标 / 单均运费目标（带币种）/
    异常件占比上限 / 物流成本占销售额比上限，租户可改）

  九表 `tenant_id` NOT NULL + ENABLE **且** FORCE RLS isolation + GRANT
  - `REVOKE ALL ON flyway_schema_history`。
    🔴 **一致性写进 CHECK**：金额要么带币种、要么是「未登记」缺口码（**运费没登记 ≠ 运费是 0**）；
    分摊明细行必须挂得上 SKU 维度；规则表四类阈值**字段互斥** + 取值范围 CHECK。
    🔴 **幂等键**（tenant + as_of/币种、as_of/渠道/币种、as_of/渠道/国家、waybill_id、
    allocation_id、allocation_id+line_no、case_id、sku_id）⇒ 同一批上游事实重放**不翻倍**。

- **端点**：13 个，挂 `/reporting/logistics-analysis` ——
  `GET /capabilities`（能力声明，七段逐条报可得性 / 归属模块号 / 影响）、
  `GET /overview`、`GET /freight-per-order`、`GET /head-mile`、`GET /channels`（真分页 + 真计数）、
  `GET /timeliness`、`GET /countries`、`GET /exceptions`、`GET /category-cost`、
  `GET /drilldown`（运单级下钻，真分页）、`GET /rules` + `PUT /rules/{ruleType}`、
  `POST /refresh`（投影摄取）。
- **页面**：`/analytics/logistics/analysis` —— 真实工作区：单均运费 + 全口径成本总览 +
  头程分摊后成本 + 渠道对比 + 时效达成率 + 各国成本对比 + 异常件占比 +
  类目维度物流成本占比 + 运单下钻 + 能力声明。
  三态齐全、无占位数据、**前端不重算任何数字**、**「算不出来」渲染成「—（不可比）」**
  并用中性配色（不是警告色）、**单均运费与每票运费并排显示**并写明订单分母口径、
  **多币种不相加**、**类目维度的覆盖边界常驻**、**「数据截至 X」常驻**（卷 13 卷首加粗句
  「数据延迟要显示」）、能力声明里未接通的指标**写明归属模块号**。
- **测试**：`LogisticsAnalysisIntegrationTest` **15 例** + `LogisticsAnalysisTenancyIntegrationTest`
  **3 例**（三断言，连接池固定 1 条连接）+ 前端 `logistics-analysis-model.test.ts` **17 例**。

**关键设计决策**：

1. **落 reporting 服务，不落 channel** —— 集成头里**没有独立的 logistics 服务**；
   运单 / 轨迹 / 时效 / 异常件的事实全在 channel 与 trade。
   本模块是**分析投影**，**不建第二个物流真相**、不直连对方库、不建跨库 FK 或视图。
   跨服务一律经**授权只读 HTTP**（转发调用方自己的 bearer，租户由上游按已认证成员关系解析），
   租户隔离是**两道**（reporting 侧 RLS + 上游侧 RLS）。
2. **单均运费的分母是订单数、不是运单票数** —— channel 自己的 `perOrderFreight` 字段名叫
   「单均运费」，但它的分母是**已登记运费的运单票数**（票），不是订单数。
   一单多票（拆单发运）时票数 > 订单数 ⇒ 那个数**偏小**。
   ⇒ 本模块的 `perOrder` 用**订单数**（同一批运单上的 distinct `sales_order_no`），
   上游那个数原样搬进 `upstreamPerWaybillFreight` **并排对照**，**绝不混为一谈**。
   「只有未登记运费的订单」**不进分母**（它没有分子），差额由 `orderCountAll` 如实报出。
3. **异常件占比的分子分母必须同范围** —— channel 的 `/exceptions/overview` 是**累计全量**
   （端点没有时间窗），拿它除以「窗口内运单数」会得到一个**没有意义**的比率。
   ⇒ 分子取**窗口内**已判定的异常件（按发现日归窗），分母取**同一窗口**的运单数；
   上游累计口径**不参与占比计算**。
4. **类目维度的物流成本占比只覆盖头程分摊部分** —— 尾程运费落在**运单**上、一票可能多 SKU，
   **没有 SKU 维度** ⇒ 只有头程分摊（本来就按 SKU 行分摊）能落到类目。
   分子 = 类目下的头程分摊后成本；分母 = **13.4.1.2 的 `reporting_profit_fact`
   （component='REVENUE'）**（与利润侧同口径，不自算销售额）。
   **响应与页面都写明「类目维度只覆盖头程分摊」**，不让下游把它当全口径。
5. **头程分摊后成本不重算** —— 分摊规则 / 基准 / 结账时点全归 channel 10.6.1.1。
   本模块只搬结果，并把「分摊单逐币种合计」与「上游成本总览的头程分摊位」**并排对照**
   （`upstreamOverviewAmount` / `differenceToOverview`），对不上时**以 channel 为准**。
6. **「算不出来」与「等于 0」分开**（贯穿全模块）：没有订单 ⇒ 单均运费 null；
   没有运单 ⇒ 异常件占比 null；缺的成本分量（仓储费在本仓**没有来源**）⇒ 那一位 null
   且在 `missingComponents` 里逐条列明；运费未登记的票**有票数、无金额**，不进任何金额合计。
7. **契约**重新生成（**13 条 path + 27 个 DTO，纯新增、零删除、零改动**已交付 DTO；
   `LogisticsChannelRow` 与 channel 的同名 DTO 冲突 ⇒ 本模块改名 `LogisticsChannelComparisonRow`）。
   api-proxy **纯新增一条** `reporting/`（logistics-analysis）元素、既有条目一字未改
   （插入锚点 = `procurement-analysis` 那条之后），因改了共享白名单按规矩跑了
   **全量 allowlist 回归** = 71 files / 394 tests 全绿。

---

## 调度取证：本轮为什么等闸等了 25 分钟

**结论：闸被一个崩掉的同步任务持有，不是被活着的流水线 tick 持有。**

| 取证                                                                                      | 结果                                                                |
| ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `gate status`（14:06 首读）                                                               | `held` / `ageSeconds=3060` / `ttl=4200` / `stale=false`             |
| `owner` 文件                                                                              | PID `42231` + 时间戳 `2026-10-29T05:15:39.441Z`（= 13:15:39 +0800） |
| PID 42231 是否存活                                                                        | **否**（`ps -p 42231` 空）                                          |
| 闸目录内 keepalive 文件                                                                   | **无**（r130 那种每 60 秒一个的 keepalive 一个都没有）              |
| 闸外的构建进程                                                                            | **无**（`mvnw` / `surefire` / `java` 全无）⇒ 持闸者**不在跑 L2**    |
| `mavis session list`                                                                      | 13:15 那一格起的是 **「main → module-dev 集成头同步」**任务，       |
| 状态 **`error`（`net::ERR_CONNECTION_CLOSED`）** ⇒ **取闸后崩在网络错误上，没能 release** |

⇒ 协议明文规定「**只有 `gate status` 的 `stale=true`（超过 TTL 70 分钟）才允许接管**」，
且「**不要为了让 tick 通过而 force 清掉别人持有的调度闸**」。

**处置**：

1. **不 force、不删闸、不改闸** —— 用 `lane.mjs gate acquire` 自带的 stale 判定做等待
   （该函数自己保证「非 stale 不抢」，`lane.mjs:568-588`）。
2. 用一个只读等待循环（1 秒一次、只调 `gate acquire`）等到 **14:25:39** ——
   正好是 13:15:39 + 4200 秒 TTL 到期那一刻 —— **自动接管成功**。
3. 接管前复核：`gate status` = `held` / `ageSeconds=3` / `stale=false`，owner 是我的 PID，
   工作区干净、**无 MERGE_HEAD 残骸**。

> ⚠️ **顺带发现的既有隐患（如实点名，未擅自改动）**：
> round-130 的 L2 脚本 `.dev-agent/l2-r130.sh` 在 L2 于 12:22 判绿之后**仍未退出**
> （3 小时后仍在跑，只剩 PG 峰值采样器在 `sleep 30`）⇒ 进程泄漏。
> 它的闸续期守护已随主流程结束而停止（闸目录里没有 keepalive 文件），
> **所以不影响 TTL 判定**，但它是历史事故
> 「orphan keepalive daemon ⇒ 闸目录 mtime 永远新鲜 ⇒ TTL 永不触发 ⇒ 流水线死锁」
> （`docs/backlog/2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md`）的同类形状。
> **本轮的 `.dev-agent/l2-r131.sh` 已按双上界（次数 AND 墙钟）+ 显式 kill 收尾实现**，
> 续期守护与采样器都有界，退出时一并回收。

---

## 调度取证：round-131 tick 崩在 L2 中途，round-132 接管收尾

**round-131 tick 在本轮中途死掉**，留下「已合并、未提交、未推送、L2 仍在跑」的半成品状态。
round-132（15:00 触发）接手把它收口。

| 取证                 | 结果                                                                                                                                                                                                                                                                                                    |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mavis session list` | round-131 的会话 `mvs_eeff9f588d30448b84d167113fe2e9da`（14:19 起）状态 **`error`**：`已达到 Token Plan 用量上限 … (2056)` / `errorCode 42212`，`updatedAt=14:56:21` ⇒ **owner 已死**                                                                                                                   |
| L2 进程              | `.dev-agent/l2-r131.sh` 及其子进程（`mvnw` PID 47742 / surefire / `caffeinate` 47718 / 采样器 47717）**仍在跑** ⇒ L2 是**脱离会话**的后台脚本，owner 死后它继续跑完，但**没人再去读结果、commit、push**                                                                                                 |
| 闸                   | `gate status` = `held` / `ageSeconds=38` / `stale=false` —— **新鲜度是僵尸采样器刷出来的**（每 60 秒 `touch` 闸目录），不是活着的 owner ⇒ **正是 `docs/backlog/2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md` 记的那个形状**（owner 已死 ⇒ 无人 `release` ⇒ 闸一直 `held` ⇒ 下一轮白等） |
| 工作区               | `git status` = 1 个未跟踪文件（本文档）+ 台账被 `sync.mjs` 改写（429→431）                                                                                                                                                                                                                              |
| 远端                 | `git rev-list --left-right --count origin/integration/module-dev...HEAD` = **0 8** ⇒ **8 个 commit 压在本地没推**（含两条 merge commit）                                                                                                                                                                |

**round-132 的处置（按协议逐条）**：

1. **不重新跑 L2** —— 已有一次在飞的全量验证，等它出结果即可（**不与在途车道 L1 并发**这条禁令照旧有效）。
2. **不抢闸、也不等 TTL** —— owner 已死（会话 `error`），不是「活着的持闸者」；
   但闸目录被僵尸采样器刷着，`gate acquire` 会一直返回 `held`。
   ⇒ 等 L2 脚本退出（`trap cleanup EXIT` 会 `kill` 采样器与续期守护）后**再接管并释放**，
   **不 force、不删别人的活闸**。
3. **本轮不派发新车道** —— 节流闸：距上批（14:18:51）仅 **46 分钟 < 100 分钟阈值**
   （降档模式：已过 `TOKEN_HORIZON` ⇒ 阈值 50→100），按协议**零派发**。
4. **说明与台账必须同一个 commit**（本轮补做 owner 漏掉的第②步），**L2 绿才 push**（第③步）。

> ⚠️ **本轮原定要做的两件事被 owner 的崩溃打断了**：写说明（已完成，起草稿在）、
> commit（漏了）、push（漏了）。round-132 补齐后两项 —— **前提是 L2 绿**。

---

## L2 批量验证

**本批唯一一次全量验证**，在集成头 `72ada60e` 上跑。
**在途车道 0 条** ⇒ L2 不与任何车道的 L1 并发 ⇒ 不会踩「PG 互抢 ⇒ 成片假红」那个已知形状。
（实测 **PG 峰值 11**；对比 r130 与 2 条车道并发时的峰值 **59** ⇒ r131「先跑 L2、再派发」的排序是对的。）

**实际结果：后端步骤退出码 1（3 例失败，**经四条独立证据判定为假红**），
后端其余 2460 例全绿，步骤 2–6 由 round-132 在空载下补跑全绿。**

| 步骤                             | 命令                                                                                                                                                               | 结果                                                                                                                                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1/6 后端                         | `cd backend && ./mvnw verify -fae`                                                                                                                                 | ⚠️ **退出码 1** —— `trade` 的 `ReplenishmentSuggestionTenancyIntegrationTest` **3 例失败**（断言 1/2/3 全挂，报 `expected:<200> but was:<503>`）。**已证伪为假红，非回归**（取证见下） |
| 1/6 隔离复跑                     | `./mvnw -pl services/trade -am test -Dtest='ReplenishmentSuggestionTenancyIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ **绿**（4 例 / 0 失败；空载、PG 容器 0、load 3.31）                                                                                                                                 |
| 1/6 模块级复跑（round-132 追加） | `./mvnw -pl services/trade -am test`（空载，PG 容器 0）                                                                                                            | ✅ **绿**（**1506 例 / 0 失败 / 0 错误**）                                                                                                                                             |
| 2/6 类型                         | `pnpm typecheck --force`                                                                                                                                           | ✅ **绿**（3 包）                                                                                                                                                                      |
| 3/6 Lint                         | `pnpm lint`                                                                                                                                                        | ✅ **绿**                                                                                                                                                                              |
| 4/6 前端单测                     | `pnpm test`（vitest）                                                                                                                                              | ✅ **绿**                                                                                                                                                                              |
| 5/6 前端构建                     | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`                                                                                                       | ✅ **绿**                                                                                                                                                                              |
| 6/6 台账一致                     | `node tools/module-progress/sync.mjs --check`                                                                                                                      | ✅ **绿**（台账与蓝图一致）                                                                                                                                                            |

### 那 3 例为什么判定为假红（四条**互相独立**的证据）

1. **签名是 503，不是「查到了别的租户的数据」** —— 失败点在 `registerVelocity`
   （写 `POST /trade/stockout-risk/velocities` 的播种助手）返回 **503**。
   协议里记的假红形状正是「`seed()` 503 / 连接拒绝」，**不是**隔离逻辑写错。
2. **trade 在本批根本没被碰过** —— `git diff --stat cfa76762..72ada60e -- backend/services/trade/` = **空**
   （`cfa76762` = r130 绿批的 HEAD）。本批只动了 **channel**（V404）与 **reporting**（V406）。
3. **同一个测试在 r130 的绿 L2 里是过的** —— r130 日志：`Tests run: 4, Failures: 0, Errors: 0`。
4. **隔离复跑 4/4 全绿** —— L2 退出后（PG 容器 0）单独跑该测试类，全绿。
5. **（round-132 追加）空载下整个 `trade` 模块复跑 1506 例全绿** ——
   `./mvnw -pl services/trade -am test` ⇒ `Tests run: 1506, Failures: 0, Errors: 0`。
   ⇒ trade 服务的**全部**测试在空载下都是绿的，不是「只好了那一个类」。
6. **（round-132 追加）失败集合会漂移，这是 flaky 的决定性特征** ——
   第一次复跑（在 `next build` + vitest 并发下）失败的是**另外三个类**
   （`FulfillmentMonitorIntegrationTest` / `FulfillmentRoutingIntegrationTest` /
   `OrderConsolidationIntegrationTest`），而它们在 r130 **和** r131 的 L2 里**都是绿的**；
   同时 r131 L2 失败的那个 tenancy 类在复跑里**是绿的**。
   ⇒ **同一份代码、同一批断言，每次红的类不一样** ⇒ 这不是「某个逻辑写错了」，
   而是**环境/时序敏感的 flaky**。
   根因取证：`trade` 的 `AccessService` 在身份服务不可达时抛 **503**
   （`AccessService.java:124-127`），而这些集成测试的身份服务桩是
   JDK `com.sun.net.httpserver.HttpServer` **没有 `setExecutor`** ⇒
   **默认单线程 dispatcher**，客户端 keep-alive 残留会把下一个请求堵死 ——
   这与协议里记的「负载型假红」机制**同源**（r129/r130 会话日志已两次记录同一形状）。

⇒ **不是跨车道互相破坏** ⇒ 不需要二分、不需要派修复任务、不需要重跑全量后端。
（这几条证据**缺任一条就应当按协议二分定位并派修复车道**；本轮齐备才下这个结论。）

### 本批自己的新测试：在全量 L2 里全绿

| 测试类                                                           | 例数 | 结果                |
| ---------------------------------------------------------------- | ---- | ------------------- |
| `AdAttributionIntegrationTest`（channel，14.3.2.1）              | 21   | ✅ 0 失败           |
| `AdAttributionTenancyIntegrationTest`（channel，14.3.2.1）       | 3    | ✅ 0 失败（三断言） |
| `LogisticsAnalysisIntegrationTest`（reporting，13.5.3.1）        | 15   | ✅ 0 失败           |
| `LogisticsAnalysisTenancyIntegrationTest`（reporting，13.5.3.1） | 3    | ✅ 0 失败（三断言） |

**静态预检**（L2 之前，几秒）：

```bash
grep -rn 'ADD COLUMN'        .../V404__ad_attribution.sql .../V406__logistics_analysis.sql | grep -v DEFAULT  ⇒ 0
grep -rn 'SET NOT NULL'      .../V404__ad_attribution.sql .../V406__logistics_analysis.sql                    ⇒ 0
grep -rn 'ADD CONSTRAINT.*CHECK' .../V404__ad_attribution.sql .../V406__logistics_analysis.sql               ⇒ 0
```

⇒ **三个互相破坏形状全部零命中**（两条迁移都是纯 `CREATE TABLE`，零 ALTER 既有表）。

**并入冲突**：**零**。`git merge-tree` 预先验证两条车道依次并入均干净，
`merge-ready` 实际执行 `failed: []`。

**台账完整性**：**465 行**（恒定）、**重复 ID 0 个**。

---

## 本轮派发：**零**（round-131 崩在派发之前，round-132 补记）

> ⚠️ **本节原文（round-131 tick 在 14:26 起草时）写的是「已派发 13.6.1.1 / 18.1.1.2」——
> 那是起草稿，与仓库事实不符，round-132 取证后更正。**

round-131 tick 打算在 L2 之后派发两条车道（**L3 锚点互不相同 ⇒ 互不依赖**）：

| 模块 ID    | 名称       | 阶段 | L3     | 服务      | 迁移版本 | 分支                |
| ---------- | ---------- | ---- | ------ | --------- | -------- | ------------------- |
| `13.6.1.1` | 自定义报表 | D7   | 13.6.1 | reporting | ~~V407~~ | ~~`feat/13-6-1-1`~~ |
| `18.1.1.2` | 接口目录   | D7   | 18.1.1 | platform  | ~~V408~~ | ~~`feat/18-1-1-2`~~ |

**但这两条车道从未真正创建**，round-132 逐项取证（全部为空）：

| 取证项       | 命令                                                | 结果                              |
| ------------ | --------------------------------------------------- | --------------------------------- |
| 车道记录     | `grep -c '13\.6\.1\.1' .dev-agent/lanes.json`       | **0**（`18.1.1.2` 同样 **0**）    |
| 分支         | `git branch --list 'feat/13-6-1-1' 'feat/18-1-1-2'` | **空**                            |
| worktree     | `git worktree list`                                 | **无这两条**                      |
| 迁移版本占用 | `git ls-files \| grep -E 'V40[78]__'`               | **空** ⇒ **V407 / V408 未被占用** |
| 台账         | 两行状态                                            | **仍为 `未开发`**                 |

⇒ **V407 / V408 未被预分配，未被占用**；下一轮 `plan --lanes 2` 会重新选模块并重新发版本号，
不会与本文件产生冲突。这两个模块仍在待开发队列里（`13.6.1.1`、`18.1.1.2`）。

---

## 后续待办

1. **18.1.1.2 接口目录**依赖后端 OpenAPI spec 作为单一真相源（文档不手写）
   —— 与本仓「契约由 codegen 生成」的纪律一致，worker 需先确认 spec 生成路径可用。
2. **13.6.1.1 自定义报表**的上游是「语义层指标定义」，蓝图明确**不开放任意 SQL**
   —— worker 需先确认 reporting 里可用的受控指标清单来源，否则要回报 `BLOCKED`。
3. **`reporting.*` 权限码未在 identity 登记** —— 全仓既有缺口
   （14.3.1.3 / 13.5.1.1 / 13.5.3.1 会话日志均已记录）。
   本轮两条车道沿用族内约定码，**未新造需要登记的码，也未擅自修 identity**。
4. **汇率台账缺失** —— 本仓没有汇率表也没有汇率同步器，
   13.5.3.1 / 14.3.2.1 都**绝不跨币种相加或相除**；等 5.2.2.1「汇率查询」落地。
5. **主干的 squash 冲突面（27 个文件）仍未解决** —— 那是 `main → 集成头同步` 任务的职责，
   已连续 99/100/101 轮如实记录。本流水线**不自己 merge `origin/main`**。
6. **`13.6.1.1 自定义报表` / `18.1.1.2 接口目录` 仍未开工** —— round-131 只写进计划、
   从未 `plan` 成车道（见「本轮派发」一节的取证）。**V407 / V408 未被占用**，
   下一轮 `plan --lanes 2` 可直接重新选出。
7. **round-131 的两处起草稿笔误已由 round-132 更正**：L2 结论（当时写「绿」，
   实际结果见下方「L2 批量验证」一节的真实表格）与「已派发两条车道」（实际未派发）。
   **本文件是 batch-113 的对外记录，草稿与事实不符会误导后续维护者，故逐项取证后更正。**
