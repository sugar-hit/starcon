# batch-84 —— 成本计算执行 / 会计期间 / 税率配置 三车道回收 + L2 + 四车道派发

> 调度者：Mavis（自动 tick，2026-10-24 11:00–12:15 +0800）
> 集成头：`integration/module-dev` @ `1c73d1e8`（回收 3 条车道后）
> 强度档位：`MODE=full`（当前时刻 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`），`LANES=4`、`THROTTLE_MIN=50`
> 批次号：84（接 batch-83 之后；写入前已二次核对 `docs/sessions/2026-10/` 下无重号）
> 本轮性质：**回收 + 一次全量 L2 + 派发**（batch-83 的交接单第 9 节第 1 条指定的正是本轮职责）

---

## 1. 本轮性质：回收 3 条 + L2 + 派发 4 条

batch-83 只派发未回收（`merge-ready` 返回 `merged: []`），并在交接单里点名：
「**回收本批 4 条车道 → merge-ready → 跑一次完整 L2 → 写 batch-84 交付说明 → commit → push**」。
本轮正是那一轮。

| 动作 | 结果 |
| --- | --- |
| 回收并入 | **3 条**（12.6.1.1 / 12.4.1.2 / 12.5.1.2）—— batch-83 派的第 4 条 **12.5.2.2 财务报表本轮开始时仍在途**（心跳仅 4 分钟前） |
| 全量 L2 | **1 次**（r1 假红 → 隔离复跑取证 → r2 绿，58 分 31 秒） |
| 派发 | **4 条**（12.4.1.3 / 12.5.1.3 / 12.6.1.2 / 16.3.2.1），V362–V365 |

### 1.1 ⚠️ 12.5.2.2 财务报表在**本轮 L2 期间**落地（state = `ready`）—— 本轮**不并入**

12.5.2.2 在 12:05 前后把 `state` 推到 `ready`（note：后端 V360 + 服务 + 端点 + 30 测试、契约 17 类型、
前端工作区 + 白名单 + 14 测试、L0/L1 全绿）。
但本轮的 `merge-ready` 是 **11:03** 跑的，L2 验的是 **11:03 的集成头** ⇒
**12.5.2.2 的代码不在这次绿灯 L2 的覆盖范围内**。

「集成头只接受绿批次」+「L2 没绿绝对不 push」⇒ **本轮不并入 12.5.2.2**，
否则 push 出去的集成头会含一段**从未跑过全量 L2** 的代码 —— 那正是组批模式要防的事。

⇒ **12.5.2.2 留给 batch-85**：下一 tick 的 `merge-ready` 会并入它，
并与本轮派发的 4 条车道一起跑那一次全量 L2。

---

## 2. 进度（`sync.mjs` 本轮实时值）

| 项 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | **383**（82.4%） |
| 进行中 | 0 |
| 未开发 | **82** |
| 离 465 还差 | **82** |

台账完整性（协议第 3 步必查）：行数 **465** ✅、重复模块 ID **0** ✅。

### 2.1 ⚠️ 剩余 82 格里有 47 格被「服务缺口」堵死（结构性问题，未变）

`plan` 的 `serviceBlocked` 字段实测：

| 卷 | 未开发数 | 主责服务 | 状态 |
| --- | --- | --- | --- |
| 13 利润与经营分析 | 23 | `reporting` | **不在集成头** ⇒ 不可调度 |
| 18 集成开放与智能自动化 | 24 | `platform` | **不在集成头** ⇒ 不可调度 |

⇒ **真正可调度的只剩 35 格**（82 − 47）。
即使吞吐拉满，本流水线也会在 **418/465（89.9%）** 处停住 —— 这仍是当前**唯一的一级结构性阻塞**。

---

## 3. 容量闸判定（先于一切动作，只读）

| 闸 | 读数 | 判定 |
| --- | --- | --- |
| 温度闸（最高优先级） | `pmset -g therm` → 「No thermal warning level has been recorded」 | ✅ 通过（无 `CPU_Speed_Limit` 等降频项） |
| 配额闸 | running = 0 ≤ `RUNNING_QUOTA=6` | ✅ 通过 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 < `PG_CONTAINER_LIMIT=6` | ✅ 通过 |
| load 闸 | `1.82 / 2.05 / 2.59`（1/5/15min）< `LOAD_LIMIT=12`，且 1min < 5min < 15min ⇒ **在下降** | ✅ 通过 |
| 孤儿容器 | 无标签的 `postgres:16-alpine` 容器 **0** 个 | ✅ 无需清理 |
| 节流闸 | 距上批 **54 分钟** ≥ `THROTTLE_MIN=50` | ✅ 放行（时间戳在过闸后才写） |
| 静默窗口 | `SILENT_WINDOW=0`（全天运行），但温度/容量/节流闸照旧生效 | ✅ 不适用 |
| 调度闸 | `gate status` → `gate=free, stale=false` → `gate acquire` 成功（exit 0） | ✅ 已取得 |

**结论：GO —— 本轮回收 3 条、L2 一次、派发 4 条。**

---

## 4. 本批回收（3 条车道并入集成头）

| 模块 ID | 名称 | 阶段 | L3 | Flyway | merge commit |
| --- | --- | --- | --- | --- | --- |
| 12.6.1.1 | 税率配置 | D6 | 12.6.1 | **V361** | `0418935f` |
| 12.4.1.2 | 成本计算执行 | D6 | 12.4.1 | **V358** | `115c7943` |
| 12.5.1.2 | 会计期间 | D6 | 12.5.1 | **V359** | `1c73d1e8` |

`merge-ready` 退出码 0，`failed: []`，**无冲突文件**。`gc` 已回收 3 个 worktree。

### 4.1 merge-union 走了「行级兜底并集」—— 已人工核对

`merge-union` 报了两遍「走了行级兜底并集，请人工确认这个文件改对了」。
本轮**逐字核对了台账 diff**：`git diff` 只有 22 增 10 删，
全部是**统计数字刷新**（已交付 380→383、未开发 85→82、完成率 81.7%→82.4%、
D6 已交付 62→65）+ Markdown 空行规整 + 3 行模块状态转「已交付」。
**465 行模块 ID 一行未动、无重复、无丢失** ⇒ 兜底并集的结果是正确的。

---

## 5. 本批交付模块说明（每个模块一节）

### 模块 12.6.1.1 税率配置

**这个功能做什么**：用户按「目的国/地区 + 税种（VAT / IOSS / 销售税 / 代扣代缴）+ 税码」
维护自己的税率，并把它绑到具体店铺站点上；系统按「订单发生的那一刻」取出当时生效的那一版税率来算税，
**政策变了也不会追溯改写历史订单的税**。

**技术落地**：

- 迁移：`V361__finance_tax_rate.sql` — **两张新表、纯 CREATE TABLE、零 ALTER 零 DROP 既有表、零 ADD COLUMN 到别人的表**：
  `finance_tax_rate`（税率记录，**版本链**）· `finance_tax_shop_binding`（店铺站点默认税率与税码绑定，**版本链**）。
  两表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT finance_app/starcon_app。
- 端点：**13 个**挂 `/finance/tax-rates`（权限码 `finance.tax_rate.read / .create / .update`）：
  GET `/capabilities` · `/options` · `/jurisdictions` · `/shops` · `/rates` · `/rate-versions` · `/rate-compare` · `/resolve` · `/bindings` · `/binding-versions` · `/state-sales-tax`；POST `/rates` · `/bindings`。
- 页面：`/finance/tax-rates`（`apps/web/src/components/finance/tax-rate-{model,workspace}.tsx` + 路由页）——
  真实工作区：四个税种选项（含口径说明、**不标任何「推荐税率」**）+ 税率记录表（版本 / 生效区间）+
  登记换税率表单 + 版本链与逐字段版本对比 + **按生效时间取税率**面板（命中不到时如实显示「该时点无生效税率」而不是 0%）+
  店铺站点绑定与绑定版本链 + **州销售税（归 channel）只读区块**（读不到时显示「读不到」而不是「没有州销售税」）+
  辖区选项表（不可用辖区写明原因）+ 能力声明；加载 · 空 · 错误三态齐全，无占位数据。
- 测试：**56 例**（后端 38 = `TaxRateIntegrationTest` 30 + `TaxRateTenancyIntegrationTest` 8，
  实跑 Testcontainers PostgreSQL 16、连接池压到 1，三条隔离断言跑在**本模块自己的两张表**上；
  前端 18 = `tax-rate-model.test` 13 + `tax-rate-allowlist.test` 5，allowlist **双向**断言；
  全量 allowlist 回归 **230 文件 / 1300 例**通过）。
- 契约：codegen 重生成 ⇒ `packages/api-contracts/src/contracts/finance.ts` 新增 **20 个 `FinanceTax*` 类型**（+20/-0，零既有类型内容变化、零路径删除）。

**关键设计决策**：

- **「税率必须带生效时间、历史订单用当时税率，不能被新政策追溯修改」落成了结构而不是文案**：
  ① 每条税率带**生效区间**（左闭右开，`effective_to` 为空 = 还没被换掉）；
  ② **版本链**（触发器钉死版本号递增 + 生效起点严格递进 + 新版本必须接在上一版之后）；
  ③ **历史不可改写**（触发器钉死：税率 / 辖区 / 税码 / 版本号 / 生效起点 / 接续版本一律不许改；`effective_to` 只允许「从空变成有值」这一次）；
  ④ **生效区间不许重叠**（`tstzrange` 触发器）⇒ 某一时点命中哪一版永远唯一；
  ⑤ 部分唯一索引保证同链**同时只有一个生效版本**。
- **绑定钉的是「一条税档」（辖区 × 税码）而不是某个税率版本 id** ⇒ 政策改税率时，
  绑在这条税档上的店铺自动从新版本的生效时刻起按新税率算，历史订单仍按当时那版算
  —— 避免了「绑定指向已失效版本」的悬空状态与无解的操作顺序。绑定自己也有生效区间与版本链 ⇒ **换绑同样不追溯**。
- **按生效时间取税率命中不到一律报错**，不退回最新一条、不按 0% 处理。
- **与 channel 的职责切分**：美国**州级销售税**归 channel 16.3.2.3（`sales_tax_rate_rule`），
  本模块**不重复建表**，只经授权只读 API 引用；「目的国/地区」的值域取自 channel 16.3.1.1 的受控辖区码表
  （**不复制、不跨库 FK、落库前实时校验**）⇒ **美国辖区（联邦与 50 州）在本模块录不进去**并写明归 channel。
- **税率零预置行** —— 税率是用户自己的税务事实，系统只提供四个税种的词表与受控辖区选项，**不替用户决定税率、不标任何「推荐税率」**。
- 税率一律 `numeric(9,6)`（0.19 = 19%，**禁浮点**；精度超 6 位直接拒而不是静默四舍五入）；
  **税率不带币种列**（税率是比例，作用于任何币种的应税基数，币种属税额那一侧）。

### 模块 12.4.1.2 成本计算执行

**这个功能做什么**：在会计期间结束时，按当时生效的计价方法（先进先出 / 移动加权平均 / 批次成本 / 指定成本）
把「这段时间卖出去的货应该结转多少成本」真算出来落成账；算错了可以按期间重算，
两次批次之间还能逐 SKU 比对差异。

**技术落地**：

- 迁移：`V358__finance_cost_calculation.sql` — **三张新表、零 ALTER 零 ADD COLUMN 到别人的表**：
  `finance_cost_calculation_run`（成本计算批次表头：期间 / 范围 / 方法 / 版本 / 汇总 / 重算链）·
  `finance_cost_calculation_layer`（成本层，按批次扣减的口径）·
  `finance_cost_calculation_entry`（成本分录 = 应结转金额）。
  三表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT。
- 端点：**9 个**挂 `/finance/cost-calculation`（权限码 `finance.cost_calculation.read / .create / .update`）：
  GET `/capabilities`（上游真接通 / 下游缺口归谁）· GET `/options`（四个计价方法选项 + 筛选项）·
  POST `/runs`（执行成本计算）+ GET `/runs`（真分页 + 真筛选）· GET `/runs/{id}`（批次明细：表头 + 成本层 + 成本分录）·
  POST `/runs/{id}/recalculate`（按期间重算）· GET `/runs/compare`（差异对比）· GET `/layers` · GET `/entries`（真分页 + 真筛选 + 汇总）。
- 页面：`/finance/cost-calculation`（`apps/web/src/components/finance/cost-calculation-{model,workspace}.tsx` + 路由页）——
  真实工作区：执行成本计算表单 + 批次表（期间 / 方法版本 / 结转金额 / 覆盖与缺口 / 重算）+
  批次明细（成本层表 + 成本分录表，**有缺口的行排在最后并显示原因**）+ 差异对比表 + 能力声明；加载 · 空 · 错误三态齐全。
- 测试：**46 例**（后端 26 = `CostCalculationIntegrationTest` 19 + `CostCalculationTenancyIntegrationTest` 7，
  实跑 Testcontainers PostgreSQL 16、连接池压到 1，三条隔离断言跑在**本模块自己的三张表**上；
  前端 20 = `cost-calculation-model.test` 15 + `cost-calculation-allowlist.test` 5，allowlist **双向**断言）。

**关键设计决策**：

- **数量账在 warehouse、价值账在 finance**：出库数量经只读端口 `GET /warehouse/inventory/ledger` + `/{transactionId}`
  （7.1.2.1 的真实带符号分录，取绝对值）读，**一条库存流水都不改**（重算不能写库存）；
  成本层经 `GET /catalog/sku-cost-compositions/{skuId}`（5.2.1.1 真实到货记录派生）读。
- **计价方法的唯一真相是 12.4.1.1**：读 `finance_cost_valuation_method` 在**期间起点生效**的那一版
  （刻意**不**按 `status='ACTIVE'` 过滤 —— 那只是「当前」那一版，算历史期间要的是当时生效的那版），
  本模块只在批次行记**快照**（方法 + 版本号 + 配置行 ID），**不另抄一份配置**。
- **「算不出来 ≠ 0」写进数据层**：覆盖量 + 缺口量 = 出库量由触发器钉死；
  有缺口 ⇒ 分录状态 INCOMPLETE 且**单价留空**、缺口原因如实写出（CHECK 钉死，半截成本不会被当成完整单价）；
  异币种成本层被排除并如实报出（**绝不跨币种相加**）；上游读不到一律 503（写明「读不到」而不是「这个期间没有出库」）。
- **重算 = 另起批次**：旧批次是当时那笔计算的证据，**不被改写**（触发器钉死：已完成的批次的口径 / 期间 / 汇总不可改，
  已完成批次的成本层与成本分录不可改不可删）；新批次把旧批次挂进重算链（`supersedes_run_id` / `superseded_by_run_id`）。
- **差异对比不落第四张表**：两次批次之间逐 SKU 对比由服务层从两批分录 Σ 出来（再存一份就是第二个真相来源）；
  期间或币种不一致时在结论里如实说明。
- api-proxy **新增一条独立 services[] 元素**（prefix `finance/`、首 token `cost-calculation`
  发车前 grep 实测**零命中**、插入锚点在**数组最开头**）；**刻意不放行** `variance`
  （成本差异分析属 12.4.1.3，未交付）、`profit`（13 未交付）、裸路径、顶层 `{uuid}`、`runs/compare/{uuid}`、`runs/{uuid}/not-a-shape`。

### 模块 12.5.1.2 会计期间

**这个功能做什么**：维护法人账簿的会计期间（自然月或 4-4-5 fiscal 周期），
支持关账与**高风险的重开**（重开必须走审批、必须留痕、关过的痕迹不许被抹掉）；
期间一旦关掉，里面就不能再过账。

**技术落地**：

- 迁移：`V359__finance_accounting_period.sql` — **接管 V357 的 `finance_voucher_period`**
  （照 V350 接管 V346 守卫的同一手法），**不是新建一张平行期间表**：
  ADD COLUMN 给 `finance_voucher_period` 补 **fiscal 周期**
  （`calendar_id` / `fiscal_year` / `fiscal_period_no` / `period_type` /
  `period_length_days`（**生成列** = `period_end - period_start + 1`，与日期不可能漂移））
  与**重开留痕**（`reopened_at` / `reopened_by` / `reopen_reason` / `reopen_approval_instance_id` / `reopen_count`），
  并按 V350 的 `pg_constraint` 扫描法**换掉** V357 的 `finance_voucher_period_closed_ck`。
  新表 3 张：`finance_accounting_calendar` / `finance_accounting_period_event` / `finance_accounting_period_approval`。
- 端点：**15 个**挂 `/finance/accounting-periods`（权限 `finance.accounting_period.read / .create / .update`）：
  capabilities / options / 集合 GET+POST（手工登记自定义期间）· {uuid} GET+PUT（**只有备注可改**：期间身份字段不可改）·
  {uuid}/close · {uuid}/reopen（**四种结局如实报出来**）· {uuid}/events（关账记录）· {uuid}/approvals（审批链路）·
  calendars GET+POST · calendars/{uuid} GET+PUT · calendars/{uuid}/generate-periods。
  ⚠️ 另补了一条 `POST /finance/vouchers/periods/{id}/close` —— 走那条路径关掉的期间**同样有关账记录**
  （留痕只写在服务层的话，走那条路径关掉的期间就没有记录）。
- 页面：`/finance/accounting-periods`（`apps/web/src/components/finance/accounting-period-model.ts` +
  `accounting-period-workspace.tsx` + `apps/web/src/app/finance/accounting-periods/page.tsx）——
  真实工作区：会计日历（自然月 / 4-4-5）+ 按日历生成一个财年的期间 + 期间列表（真分页 + 真筛选 + 状态汇总）+
  关账（必须写原因）+ 重开（**四种结局分开说**，只有「已重开」才显示成开了，其余三种明说「期间仍然关闭」）+
  期间详情（关账记录 + 审批链路）；加载 · 空 · 错误三态齐全。
  🔴 页面**明确标出「关账检查清单属 12.5.1.3，未交付」**（能力声明如实报 `available=false`），**不假装**跑过清单。
- 测试：**39 例**实跑 Testcontainers PostgreSQL 16
  （`AccountingPeriodIntegrationTest` 35 + `AccountingPeriodTenancyIntegrationTest` 4，迁移实跑到 V359），
  逐条断言覆盖：日历可建可查可停用 / 4-4-5 必须给锚点日 / 自然月生成 12 期且财年边界正确 /
  4-4-5 生成 12 期且长度是 28·28·35 且首尾相接、末日正好锚点 +363 天 / 重跑生成跳过已存在编码并逐个报出 /
  关账真的落库且**留下关账记录** / 关账必须写原因 / 已关闭期间关不掉 /
  **没有生效审批流就不许重开且期间仍关闭** / **重开先提交审批、期间仍然关闭** / **审批通过后重开真的执行**
  （重开人 / 时刻 / 原因 / 审批实例 id 落库，且**关账痕迹仍留着**）/ **审批被驳回如实报驳回且期间仍关闭** /
  **审批不可达如实报 503 且期间保持关闭** /
  🔴 **直连 SQL 也重开不了没有审批的期间** / 🔴 **直连 SQL 也改不了期间身份字段** / 🔴 **直连 SQL 也删不掉期间** /
  🔴 **直连 SQL 也改不了已关闭期间的关账留痕** / 留痕只能追加（运行角色 UPDATE/DELETE 已收回）/
  🔴 **接管后「已关闭期间禁止过账」依然生效**（走 12.5.2.1 端点被拒 + 直连 SQL 也被拒）/ **重开之后期间恢复可过账** /
  列表真分页真筛选且汇总覆盖整个结果集 / 能力声明如实报出关账清单与报表两个缺口 /
  **+08:00 夹具写回逐位相等不偏八小时**（断言写回的值）。
  前端本车道 2 文件 / 16 例全绿、**全量 allowlist 回归 230 文件 / 1300 例全绿**。
- 契约：codegen 重生成 ⇒ `finance.ts` 新增 **21 个 `FinanceAccounting*` 类型**（+21/-0，无一既有类型内容变化；
  其余六份契约零变化；finance 的 OpenAPI 快照新增 11 条路径、21 个 schema，删除 0 条）。

**关键设计决策**：

- **接管而不是新建平行表**：V357 第六节交接面原文「期间表的**最终归属是 12.5.1.2**」「12.5.1.2 交付后**接管本表**」
  ⇒ `finance_voucher_period` 被 `finance_voucher` 以 `FOREIGN KEY(tenant_id, period_id)` 引用，**本表不能删**。
- **旧 CHECK 与重开并存不了**：V357 的 `finance_voucher_period_closed_ck` 写死「OPEN ⇒ closed_at / closed_by 必须为 NULL
  且 closed_reason 必须为空」；但重开之后期间回到 OPEN，**关账痕迹必须留着**（「关账记录」是蓝图写死的输出，不能因为重开就被抹掉）。
  ⇒ 手法与 V350 接管 V346 的 `execution_available` CHECK **逐字同构**：先按 `pg_constraint` 把老 CHECK 找出来 DROP 掉，再补新的。
- **重开是高风险操作 ⇒ 审批留痕**：走既有 `ApprovalPort`（授权 HTTP API，**不跨库**）；
  审批不可达时如实报「审批不可达」而不是假装通过。审批有「提交 / 通过 / 驳回 / 不可达」四种结局，**四种都如实报出来**。
- **期间身份字段不可改、期间不可删、关账留痕只可追加** —— 三条都由触发器钉死，**直连 SQL 也绕不过**。
- 「已关闭期间禁止过账」已由 V357 的 `guard_finance_voucher_post_period` 生效，本模块**不重复实现、不改语义**。

---

## 6. 静态预检（互相破坏形状扫描）

对本批**新迁移**（V358 / V359 / V361）逐个扫描 `ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / `ADD CONSTRAINT ... CHECK`：

| 迁移 | ADD COLUMN 无 DEFAULT | SET NOT NULL | ADD CONSTRAINT CHECK | 判定 |
| --- | --- | --- | --- | --- |
| V358 | 1 处命中 —— **纯注释文本**（「零 ALTER 零 ADD COLUMN 到别人的表」） | 0 | 0 | ✅ 无违规 |
| V359 | 8 处命中 —— 逐条核对：`calendar_id` / `fiscal_year` / `fiscal_period_no` / `period_length_days` / `reopened_at` / `reopened_by` / `reopen_approval_instance_id` **均可空**；`period_type` / `reopen_reason` / `reopen_count` **NOT NULL 但都带 DEFAULT** | 0 | 4 个 CHECK | ✅ 无违规 |
| V361 | 0 | 0 | 0 | ✅ 无违规 |

**V359 的 4 个新 CHECK 与 V357 的存量行兼容性已逐条核对**：

- V357 的 `finance_voucher_period` **没有任何 bootstrap INSERT**（`grep -c 'INSERT INTO finance_voucher_period' = 0`）⇒ 存量行为空。
- V357 的 `closed_ck` 是 V359 `state_ck` 的**真子集**（CLOSED 分支一字不差；OPEN 分支 V359 额外放宽出「关过又重开」这一种）⇒ 兼容。
- V359 的 `calendar_ck`：ADD COLUMN 后存量行取 DEFAULT `'CUSTOM'` + `calendar_id/fiscal_year/fiscal_period_no` 皆 NULL ⇒ 命中 CUSTOM 分支 ✅。
- V359 的 `445_len_ck` / `fiscal_no_ck`：存量行 `period_type='CUSTOM'`、`fiscal_period_no IS NULL` ⇒ 命中 ✅。
- V359 的 `calendar_fk`：`calendar_id` 皆 NULL ⇒ 外键对 NULL 不生效 ✅。

仓库全量扫描命中的其它形状（V67 / V73 / V77 / V91 / V105 / V350 / V199 / V83 …）**全部落在历史迁移**，
这些表早已随 batch-82 的绿灯 L2 验证过，**不是本批引入的回归面**。

---

## 7. L2 批量验证

### 7.1 r1 假红 —— `identity` 的子串断言被时间戳误伤

r1（11:04:23 起，213 秒）在 `identity` 模块挂掉：

```
[ERROR] Tests run: 515, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions:630
```

surefire 报告原文：

```
Expecting actual:
  "[{...,"quantity":111.0,...,"collectedAt":"2026-10-24T11:05:59.057222+08:00",...}]"
not to contain:
  "222"
```

⇒ `collectedAt` 的**微秒小数 `057222`** 里含子串 `222`，被
`assertThat(forA.toString()).doesNotContain("222")`（对**整段 JSON** 做子串匹配）误伤 ⇒ **假红**。

**按协议「先隔离复跑最大那一堆，不要直接归因成回归」执行，三条独立证据**：

1. **隔离复跑绿**：
   `./mvnw -pl services/identity -am test -Dtest='SaasUsageIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
   ⇒ `Tests run: 14, Failures: 0, Errors: 0` —— **BUILD SUCCESS**。
2. **本批 3 个 merge commit 根本没碰 identity**：`git show --stat` 的改动面只有
   `apps/web/src/lib/api-proxy.ts`、`packages/api-contracts/src/contracts/finance.ts`、
   `packages/api-contracts/src/finance.openapi.json`、台账 —— 全在 finance 侧；
   `git log --since='2026-10-24 09:18' -- backend/services/identity/` **为空**
   ⇒ 自上一次绿灯 L2（09:18:39）以来 **identity 一行代码都没变**。
3. **方向上不可能**：finance 依赖 identity（`ApprovalPort` 走 identity 授权 API），identity **不依赖** finance
   ⇒ V358 / V359 / V361 三条迁移不可能影响 identity 的测试。

⇒ **判定为既有 flaky 断言，不是本批回归**。
已开 backlog：`docs/backlog/2026-10-24-saas-usage-tenant-isolation-substring-assertion-flake.md`
（含建议修法：断言改打**字段值**而不是整段 JSON 子串）。本轮**未**在集成头上直接改代码（协议：L2 红时不在集成头上继续改）。

### 7.2 r2 —— 全量绿灯

**11 项退出码全 0 ⇒ `L2 GREEN`**。日志：`.dev-agent/l2-run-batch-84-r2.log`；
r1 日志保留在 `.dev-agent/l2-run-batch-84-r1-flawk.log`（诊断证据）。

| 闸 | 结果 |
| --- | --- |
| `[1/6]` backend `./mvnw verify` | ✅ `MVN_EXIT=0`，`BUILD SUCCESS`，**55 分 50 秒** |
| ↳ 8 个 Maven 模块 | backend 0.000s · tenancy 0.361s · identity 02:12 · catalog 05:38 · channel 14:56 · warehouse 12:45 · trade 16:51 · crm 01:20 · finance 02:04 —— **全部 SUCCESS** |
| `[2/6]` `pnpm typecheck` | ✅ `TYPECHECK_EXIT=0` |
| `[2/6]` `pnpm lint` | ✅ `LINT_EXIT=0` |
| `[2/6]` `pnpm test` | ✅ `TEST_EXIT=0` —— **594 个测试文件 / 8228 例全绿**（15.34s） |
| `[3/6]` Next production build | ✅ `BUILD_EXIT=0` |
| `[4/6]` `sync.mjs --check` | ✅ `SYNCCHECK_EXIT=0`（台账与蓝图一致） |
| `[5/6]` `merge-union.itest` | ✅ `ITEST_EXIT=0`（含 OpenAPI 键序与数值保真断言） |
| `[5/6]` codegen python 单测 | ✅ `CODEGEN_EXIT=0`（`Ran 17 tests ... OK`） |
| `[6/6]` 契约闸（重新生成 + 逐字节比对） | ✅ `CONTRACT_GENERATE_EXIT=0`（生成 6637 DTOs）· `CONTRACT_GATE_EXIT=0` · `CONTRACT_TREE_DRIFT_EXIT=0` |

**r2 全程 58 分 31 秒**（11:08:45 → 12:07:16）。
本轮 L2 是本仓已观测到的**偏慢一次**（历史 41–56 分钟），原因：机器上同时有 12.5.2.2 车道在途；
load 全程 ≤ 5.4（10 核），`pmset -g therm` 全程无降频项。

### 7.3 本轮 L2 与在途车道的并发取舍（**主动决策，非偶然**）

协议第 4 步（派发）在第 5 步（L2）之前，但「最高优先级禁令」写着
**「L2 不得与在途车道的 L1 并发跑」**（实测：L2 + 4 条车道 L1 并发时 PG 峰值 16，成片假红）。

本轮**实测确认了这条禁令的严重性**：L2 跑到一半时 `docker ps` 里同时有 **33 个** `postgres:16-alpine`
容器（**同一个** Testcontainers sessionId ⇒ 一个测试类一个 PG，持续创建与回收）
⇒ 若此时再叠加 4 条车道的 L1，PG 与 CPU 争抢必然放大。

⇒ **本轮决策：先跑完 L2，再派发 4 条车道。**
代价：4 条车道的起点比「边跑 L2 边派发」晚约 1 小时；
收益：L2 在**只有 1 条在途车道（12.5.2.2）**的安静机器上跑完，零假红；
且 4 条车道发车时机器上 PG 容器数归零（实测 0），它们的 L1 拿得到干净的容器额度。

> 本轮为此加了两个本地守护（均在 `.dev-agent/`，gitignored）：
> `.dev-agent/batch84-keepalive.sh` —— 每 4 分钟续调度闸 + 给**已 plan 未发车**的 4 条车道续心跳
> （否则从 11:01 plan 到 12:08 发车超过 90 分钟 TTL，车道会被误判崩掉重派）。
> 调度者收尾时创建 `.dev-agent/STOP-KEEPALIVE` 让它停。

---

## 8. 本批派发（4 条车道）

| 模块 ID | 名称 | 阶段 | L3 | Flyway | 分支 | worktree | api-proxy 首 token | 插入锚点 | 落哪个服务 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 12.4.1.3 | 成本差异分析 | D6 | 12.4.1 | **V362** | `feat/12-4-1-3` | `.worktrees/12.4.1.3` | `cost-variance` | 数组最开头 | finance |
| 12.5.1.3 | 关账 | D6 | 12.5.1 | **V363** | `feat/12-5-1-3` | `.worktrees/12.5.1.3` | `period-close` | 第一个 `prefix: 'identity/'` 之前 | finance |
| 12.6.1.2 | VAT/IOSS 申报数据 | D6 | 12.6.1 | **V364** | `feat/12-6-1-2` | `.worktrees/12.6.1.2` | `filing-draft` | 第一个 `prefix: 'warehouse/'` 之前 | finance |
| 16.3.2.1 | VAT/IOSS | D7 | 16.3.2 | **V365** | `feat/16-3-2-1` | `.worktrees/16.3.2.1` | `vat-ioss` | 数组末尾 | **channel** |

**迁移版本号冲突预检**：`V362`–`V365` 四个文件在仓库里均 **0 命中** ⇒ 无撞号
（仓库现存 finance 最大版本 `V361`，`V360` 属仍在途的 12.5.2.2）。

**worktree 预检**（含 batch-83 点名的 `lane.mjs` 重派缺陷复查）：4 个 worktree 的
`HEAD` **全部等于** `baseSha = 1c73d1e`，`ahead = 0` / `behind = 0` / `dirty = 0`
⇒ 无残留未跟踪文件、无双 writer、无旧基线。

**首 token 零命中实测**：`cost-variance` / `period-close` / `filing-draft` / `vat-ioss`
在 `apps/web/src/lib/api-proxy.ts` 里全部 **0 命中**。
⚠️ `tax-return` 曾有 1 处命中（`tax-registration/tax-returns` 被 api-proxy 注释标注为
「12.6 税务未交付」的保留位）⇒ 12.6.1.2 改用 `filing-draft`，避开与 16 卷税号模块撞语义。

**任务书**：4 份全文落盘在 `.dev-agent/taskbooks/{12.4.1.3,12.5.1.3,12.6.1.2,16.3.2.1}.md`（不入版本库）。
**逐字校验：无任何占位符残留**（连模板里那段「看到占位符就停下」的警告原文也一并改写，
避免 worker 把警告里的尖括号误当成真占位符而误停）。

### 8.1 本批 4 条车道各自的跨车道纪律（已写进任务书）

| 模块 | 关键约束 |
| --- | --- |
| **12.4.1.3 成本差异分析** | 差异基数必须是 12.4.1.2 的**真实结转金额**，不是自己重算一遍成本；「预算/标准成本」基线**本仓没有现成表** ⇒ 必须自建基线 + 生效区间，且**基线缺失 ≠ 差异为 0**（如实显示「无基线，不可比」）；五类差异（实际 vs 标准、采购价波动、头程成本波动、损耗差异、汇率影响）**各自独立可看**并给出主因归因 + 异常提示；**多币种绝不相加**（汇率不可得就分组呈现，**不用 1:1 兜底**）；采购价 / 头程成本是跨服务 ⇒ 走授权 HTTP API 只读端口，**禁跨库 SQL/FK/视图**。 |
| **12.5.1.3 关账** | **只 CREATE 自己的新表**（检查清单 / 检查项结果 / 关账执行记录），**读** `finance_voucher_period`；**绝不新建平行期间表**、**不重实现期间状态机**、**不实现重开**（那是 12.5.1.2 的）；检查清单要**真查数据**（未结转成本→`finance_cost_calculation_run`、未过账凭证→`finance_voucher` 的 DRAFT、未核销款项→`finance_receipt`/`finance_receivable`）；⚠️ **未对账差异因 12.3.2.1/12.3.2.2 未交付 ⇒ 必须如实标「不可判定」**（不得假装通过、也不得把关账卡死在一个永远无法满足的条件上）；**阻断项 vs 可结转未清项**两类都要有分类依据；关账是高风险操作 ⇒ 走 `ApprovalPort` 审批留痕（**自己独立的 document type code**），**审批不可达时如实报不可达**；「已关闭期间禁止过账」已由 V357 触发器生效 ⇒ **不重复实现、不改语义**。 |
| **12.6.1.2 VAT/IOSS 申报数据** | 「本系统提供申报底稿，**不替代税务申报动作**」是产品边界：产出**底稿 + 汇总 + 真实可下载的导出文件**，不假装提交申报；辖区码**必须用受控码表**（`/finance/tax-rates/jurisdictions` + channel 的 `tax_registration_jurisdiction`），**落库前实时校验**、**美国辖区录不进去**并写明归 channel；税率取数**必须走「按生效时间取税率」**（`/finance/tax-rates/resolve`），**命中不到就报错**；**多币种绝不相加**；可抵扣进项**取不到就如实报「无可抵扣数据」**，**不许凭空造进项表假装有数据**；⚠️ 16.3.2.1 **同批在途** ⇒ 按「读不到 ⇒ 如实显示『读不到 IOSS 订单清单』」处理，**不假造、不 BLOCKED**。 |
| **16.3.2.1 VAT/IOSS** | 落 **channel 服务**（卷 16 主责 = `channel + identity`，取第一项）；**IOSS 订单与一般 VAT 订单必须区分统计**（蓝图加粗句，「混在一起申报是最常见的错误」）⇒ 口径必须**结构化地区分**，不许只在页面上用颜色区分；⚠️ 12.6.1.2 申报底稿**同批在途 ⇒ 不是硬依赖**（方向上本模块是它的上游），读不到就如实报读不到；税率适用来自 finance 12.6.1.1 ⇒ **跨服务**走授权 HTTP API 只读端口（照 `SalesTaxTransactionPort` 的 `FINANCE_URL` 写法），**finance 不可达时如实显示「读不到」**，不退化成 0%；辖区 / 税号**同服务同库**可直接读 `tax_registration_jurisdiction` / `tax_registration_record`（16.3.1.1 已交付）；**不碰** 16.3.2.2 EPR 的表、**不碰** 16.3.2.3 州销售税的表。 |

---

## 9. 收尾三件套

| 项 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-24-batch-84-cost-calc-period-tax.md`（另含 backlog 一条） |
| ② commit | ✅ 与台账、backlog **同一个 commit**（`docs(batch-84): ...`），未拆开 |
| ③ push | ✅ `git push origin integration/module-dev`（**只推集成头，绝不推 main**） |

> ⚠️ **push 出去的内容 = 通过绿灯 L2 的集成头**：12.5.2.2 **不包含在内**（见 1.1）。

---

## 10. 吞吐预期（按当前档位现算）

- `CRON=0 * * * *`（整点，每小时触发）× `THROTTLE_MIN=50` ⇒ 每小时放行一批 4 条车道
- `MODE=full`、`LANES=4` ⇒ 名义 **96 条/天**
- 折损系数（折损项连乘经验值）≈ **0.70** ⇒ 名义口径 ≈ **67 条/天**
- **实测口径**：`lanes.json` 近 4 天实际并入数 = 19 / 35 / 50 / 35 / 43 ⇒ **均值 ≈ 41 条/天**
- **诚实区间：约 25–45 条/天**（实测比名义折损口径保守，因为长尾车道与 BLOCKED 车道占位）

**但本轮的真实上限不是吞吐，是服务缺口**：可调度的只剩 **35 格**（见 2.1）。
按 41 条/天算这 35 格约 **1 天**可清完；剩下 47 格（卷 13 / 卷 18）需要先有 `reporting` 与 `platform` 两个后端服务。
**在这两个服务落地之前，流水线会在 418/465 处空转。**

---

## 11. 后续待办（给下一 tick / 人工）

1. **回收本批 4 条车道 + 12.5.2.2** → `merge-ready` → **跑一次完整 L2** → 写 batch-85 交付说明 → commit → push。
   ⚠️ 12.5.2.2 财务报表（V360）在本轮 L2 期间落地、state=`ready`，**本轮刻意未并入**（见 1.1）；
   它与本批 4 条车道一起进 batch-85 的那一次全量 L2。
2. 🔴 **决策点：`reporting` 与 `platform` 两个后端服务要不要建？** 不建 ⇒ 流水线在 418/465 停住；
   建 ⇒ 需要先在 `backend/services/` 落服务骨架 + Flyway 基线 + `docs/architecture/` 的服务边界文档，
   再用 `lane.mjs plan` 的微服务闸（读蓝图 README「后端主责服务」列第一项）自动放行卷 13 / 卷 18。
3. ⚠️ **修 flaky 断言**：`docs/backlog/2026-10-24-saas-usage-tenant-isolation-substring-assertion-flake.md`
   —— 本轮只取证，未在集成头上改代码。
4. ⚠️ **修 `lane.mjs` 的重派缺陷**（batch-83 点名）：重派车道复用了旧 worktree 却没把 HEAD 复位到新 `baseSha`。
   本轮 4 条车道全部新开，未复现；建议仍在 `plan` 里加一步「worktree HEAD != baseSha ⇒ reset --hard」。
5. **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` 至今未登记卷 12 的新页面（12.4.1.1 / 12.5.2.1 / 12.4.1.2 / 12.5.1.2 / 12.6.1.1 等均刻意跳过），
   待各车道落地后由调度者统一补登记。
6. **孤儿容器**：本轮实测 0 个无标签的 `postgres:16-alpine` 孤儿容器，无需清理。
