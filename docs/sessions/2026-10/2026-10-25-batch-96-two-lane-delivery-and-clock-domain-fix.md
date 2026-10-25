# 2026-10-25 · batch-96 —— 双车道交付并入（13.4.2.2 + 18.4.2.1）+ 跨时钟域断言假红修复 + L2 三轮

> ## 🔴 勘误（2026-10-25 17:2x，batch-97 tick 写入）
>
> **本文件的两处结论与日志不符，已按事实更正**（详见第十二节）：
>
> 1. **第 3 轮 L2 不是 🟢，是 🔴 BUILD FAILURE** —— 失败点在 `warehouse.PendingInspectionTenancyIntegrationTest`
>    （3 例 ERROR，`Connection to localhost:60087 refused`），`trade / crm / finance / reporting / platform` **全部 SKIPPED**。
>    **根因不是跨时钟域断言**，是**本文档第七节自己引入的「容器清道夫」误杀了共享静态容器**
>    （容器 15:33:12 创建 → 清道夫 15:55:39 删除（自报 `age=1347s`，精确吻合）→ 依赖它的测试类 15:59:03 才启动）。
> 2. **收尾三件套没有完成**：push **从未发生** —— 本批 **9 个 commit 至今未推送**（`origin/integration/module-dev..HEAD`），
>    本文件与台账**也从未被 commit**（两者都还是工作区里的未提交改动）。
>
> ⇒ **batch-96 的实际状态是「代码已并入本地集成头，但从未通过 L2、从未推送」。**
> 本轮（batch-97）的第一件事就是把这个状态如实记下来，而不是沿用「🟢 / push ✅」的说法。

> 批次号 **96**（承接 `batch-95`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-95`）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-25 12:00 触发）。
> **强度档位：降档模式** —— 12:00 ≥ `TOKEN_HORIZON=2026-10-25T00:00:00+0800`
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
> ⚠️ 本轮**跨了两次 cron 触发**（12:00 与 15:00 那轮）—— 15:00 那轮撞闸后零派发只读结束，
> 但**它仍提交了一份只读轮文档**（`d0270403`，见第十节），这是本轮发现的**流水线并发纪律问题**。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度 | 465 总数 · **404 已交付** · 0 进行中 · **61 未开发**（完成率 **86.9%**，离 465 还差 **61**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **1.83 / 1.99 / 1.84**（10 核，三点平稳）、Testcontainers PG **0** 个（孤儿容器 **0** 个） |
| 节流闸 | 距上批 **116 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 本批回收 | **2 条** —— `merge-ready` 并入 **13.4.2.2 费用差异**（`ee2a2a56`）+ **18.4.2.1 文本与翻译**（`e1d59315`），`gc` 回收 2 个 worktree |
| 本批派发 | **2 条重派车道**（batch-95 派出的同两条，原地续跑并交付）：**13.4.2.2**（V379，reporting）+ **18.4.2.1**（V380，platform + identity） |
| 静态预检 | 本批 3 个新迁移（V379 / V380×2）**三项扫描全部零命中**（① 无 DEFAULT 的 ADD COLUMN / ② SET NOT NULL / ③ ADD CONSTRAINT CHECK）；RLS 逐表核对通过 |
| **L2 批量闸** | **跑了三轮**：第 1 轮 🔴（warehouse 1 例假红）· 第 2 轮 🔴（channel 1 例假红）· **第 3 轮 🔴（warehouse 3 例 ERROR —— 清道夫误杀共享容器，非跨时钟域断言；见第十二节勘误）** |
| 修复任务 | **2 个** —— `f16de390`（warehouse 留痕时刻断言）· `be3a9d24`（6 个测试文件的跨时钟域断言，commit `3f8b6744`） |
| 台账完整性 | `grep -c` = **465**、重复 ID **0**；`sync.mjs --check` **绿** |
| 收尾三件套 | **说明 ✅（写了）· commit ❌（说明 + 台账从未提交）· push ❌（9 个 commit 从未推送）** —— 判定依据见第八节与第十二节勘误 |

---

## 一、进度

`sync.mjs` 实测（并入后）：**18 卷 / 465 个 L4 模块，已交付 404 · 进行中 0 · 未开发 61**。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 107 | 0 | 53 |

未开发 61 个按卷分布：**卷 1（5）· 卷 5（3）· 卷 13（20）· 卷 14（4）· 卷 15（6）· 卷 18（23）**。

⇒ 本批交付 **2 个模块**（卷 13 与卷 18 各一），比 batch-95（零交付）前进 2 格。

---

## 二、容量闸判定：GO

| 闸 | 判据 | 实测 | 结论 |
| --- | --- | --- | --- |
| 温度闸（最高优先级） | `pmset -g therm` 出现降频项且 < 100 | `Note: No thermal warning level has been recorded` | **通过** |
| 配额闸 | running ≥ 7 即 STOP | running **0** / 配额 6 | **通过** |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` ≥ 6 | **0** 个（孤儿容器也是 0） | **通过** |
| load 闸 | load 1min > 核数（10） | **1.83** / 1.99 / 1.84（10 核），三点平稳 | **通过** |
| 节流闸 | 距上批 ≥ 阈值（降档后 100 分钟） | **116 分钟** | **通过** |

闸优先级（温度 > 配额 > PG > load）下**无一条命中** ⇒ 判定 **GO**。

---

## 三、本批回收：2 条车道并入

`merge-ready` ⇒

```json
{"ok": true, "merged": [
  {"lane": "13.4.2.2", "name": "费用差异",   "from": "e0a7d966", "to": "ee2a2a56"},
  {"lane": "18.4.2.1", "name": "文本与翻译", "from": "ee2a2a56", "to": "e1d59315"}
], "failed": []}
```

`gc` ⇒ 回收 2 个 worktree（`13.4.2.2` / `18.4.2.1`）。

⚠️ 合并器提示了两条兜底求并（`merge-union: 走了行级兜底并集` / `台账按模块 ID 求并集，折叠了 465 行同 ID 记录`）——
已逐项核对台账：行数 465、无重复 ID、两行状态均为「已交付」且证据栏完整。

### 共享文件并入核对（协议 5.1 要求的专项检查）

`apps/web/src/lib/api-proxy.ts`（两条车道都改了这个 1MB 的共享白名单文件）：

| 检查项 | 结果 |
| --- | --- |
| 本批改动量 | **107 insertions / 0 deletions** ⇒ **纯新增，零删除** |
| 13.4.2.2 的 `expense-variance/*` 条目 | ✅ 在（12 处命中） |
| 18.4.2.1 的 `platform/text/*` 条目 | ✅ 在（31 处命中） |
| 既有 228 条元素是否被动过 | ❌ **一条没动**（diff 里零删除行） |

⇒ batch-94 记的风险「两条车道都碰 `api-proxy.ts`，并入后要专门看一次」**已排除**：
两条车道的插入锚点相距 10000+ 行，git 干净求并，没有静默删掉别人已交付的路由。

---

## 四、本批派发：2 条重派车道（承接 batch-95）

batch-95 的两条车道因 worker 被 `ERR_CONNECTION_CLOSED` 打死而带着**未提交成果**失联；
本轮 `plan --lanes 2` 命中编排器的孤儿回收逻辑（保留原分支 / 原 worktree / 原迁移版本号），
**在同一个 worktree 里续跑**，成果零丢失。详见 `docs/sessions/2026-10/2026-10-25-batch-95-worker-crash-recovery-redispatch.md`。

| 项 | 车道 A | 车道 B |
| --- | --- | --- |
| 模块 ID / 名称 | **13.4.2.2 费用差异** | **18.4.2.1 文本与翻译** |
| 阶段 / L3 | D7 / 13.4.2 | D7 / 18.4.2 |
| 主责服务 | `reporting` | `platform`（+ `identity` 权限码） |
| 迁移版本 | **V379** | **V380**（platform + identity 各一份） |
| 车道 commit | `8fd35cd5` | `4b9a48a1` |
| worker 会话 | `mvs_2810534a6f334fb88851350d53c6cdc0` | `mvs_7a423fb29d184c5ab5a772625753b233` |

### 模块 13.4.2.2 费用差异（reporting 服务 · V379）

**这个功能做什么**：把「按规则测算出来的预计费用」和「实际发生的费用」摆在一起算差额，
按 SKU 和期间列出差异率，看出哪些费用规则长期高估或低估，并给出规则调优建议。
持续为负的差异说明费用规则配错了 —— 长期不校准会让所有利润测算失准。

**技术落地**：

- **迁移**：`V379__expense_variance.sql` — 建 **两张事实表**
  `reporting_expense_estimate_fact` / `reporting_expense_actual_fact`；
  `tenant_id NOT NULL` + `ENABLE` **且** `FORCE ROW LEVEL SECURITY`（用 DO 块对全部表统一施加
  `isolation` 策略）+ GRANT；金额 `numeric(24,4)`、数量 `numeric(19,6)`、币种 `char(3)` CHECK。
- **端点**：`/reporting/expense-variance/{capabilities,variance,trend,recommendations,by-sku,refresh}`
  × 6 个 —— 能力声明、差异表、偏差趋势、规则调优建议、按 SKU 拆解、投影刷新；jOOQ 显式 SQL。
- **页面**：`/analytics/profit/expense-variance` — 真实工作区（模型 / 工作区 / 页面三层），
  有筛选维度、错误态 / 空态 / 加载态。
- **测试**：`ExpenseVarianceIntegrationTest` 14 例 + `ExpenseVarianceTenancyIntegrationTest` 3 例
  = **17 例**；前端 model 单测 + api-proxy allowlist 单测 = 21 例。

**关键设计决策**：

1. **预计侧与实际侧分成两张事实表**（而不是一张表两列）——
   两边的来源、刷新节奏、可信度都不同，混在一张表里会让「哪一侧更旧」这件事不可表达。
2. **只读跨服务**（catalog 费用规则 / finance 结算 / channel）：转发调用方自己的 bearer、
   **不传租户号**、显式超时、**取不到就 503 而不是返回空集合** ——
   返回空集合会被上层当成「差异为零」，那是静默的数据错误。
3. **三处缺陷由 worker 就地修掉**（上一轮半成品里的真 bug）：
   - `dataUpdatedAt` 的 SQL **多一个右括号** ⇒ PostgreSQL `syntax error at or near ")"`，
     **14 个用例全挂在同一查询**（把 SQL 抄进真实 `postgres:16-alpine` 逐段二分定位）；
     顺带修掉第二个隐患：`'-infinity'` 哨兵会让**两张表都空时返回 `-infinity` 当「数据更新时间」**。
   - `skuVariance` 的相关子查询引用**外层未分组列** ⇒ 改成 CTE 两侧先各自聚合、再按期间窗口对照。
   - 两处**测试自身**期望错位（建议用例期望 −10% 但自己的夹具是 −15%；多租户断言 2 从头到尾没给租户 B 播种）。

**明确的能力边界（不是缺口，是上游的事实）**：平台费/物流费的 **SKU 级差异算不出来**
（上游实际费用台账只到平台/站点/渠道维度）；**12.3.1.2 结算明细那一侧没有预计值**
（结算明细不带销售额/数量基数，比例规则与按件规则重算不出来 —— 差异率是**不可比**而非 0%）；
广告费的「预计侧」取的是 14 广告预算分配额。页面按「能力声明」如实显示，不做本地兜底。

### 模块 18.4.2.1 文本与翻译（platform 服务 · V380）

**这个功能做什么**：给多语言翻译、标题优化、卖点提炼、违禁词规避建议、客服回复草稿提供一套
**可人工编辑、可人工确认后发布**的文本工作台 —— 机器翻译不能直接发出去，必须有人过一遍。

**技术落地**：

- **迁移**：`V380__text_translation.sql`（platform，**七表**：`platform_text_glossary` /
  `banned_term` / `rule` / `template` / `task` / `draft` / `check`，逐表 `FORCE ROW LEVEL SECURITY` + GRANT）
  + `V380__platform_text_permission.sql`（identity，6 个权限码）。
- **端点**：`/platform/text` 下 **22 个**（`TextTranslationController`），jOOQ 显式 SQL。
- **页面**：`/text` — 真实工作区；AI 未接入的能力按「能力声明」如实显示，不做本地兜底生成。
- **测试**：`TextTranslationIntegrationTest` 25 例 + `TextTranslationTenancyIntegrationTest` 6 例
  = **31 例**；前端 22 例。

**关键设计决策**：

1. **AI 供应商未接入 ⇒ 不 mock 一条「成功的 AI 结果」**。本仓 backend/services/tools 下**不存在任何真实
   AI/LLM 适配器**，照 crm 的 `UnavailablePlatformMessageSendPort` 先例立
   `AiTextGenerationPort` + `UnavailableAiTextGenerationPort`：生成端点**如实答 409 且一次数据都不写**
   （不落草稿/状态/「已翻译」痕迹）。
   **理由**：一条返回成功结果的假通道，会让「文案已经翻译好了」在数据上看起来发生过，而现场一个字都没翻译出来。
2. **确定性管线必须真做完**：术语保护（品牌词缺失 ⇒ FAIL 拦发布）、占位符完整性校验（封闭词表）、
   违禁词命中（租户词表 + 内置红线 + 邮箱电话正则）、长度（Unicode 码点）与字符集、
   **置信度四维可解释加权**（有可解释的计算依据，不是拍脑袋的数）、人工编辑（追加版本）、
   **人工确认 + 发布双闸**（未确认的译文不许被下游取走）、客服话术模板变量绑定。
3. **三处缺陷由 worker 就地修掉**（上一轮半成品里的真 bug）：
   - 🔴 **生产级 bug**：catalog 上游的 SPU/SKU 过滤参数写错 ——
     判据 `"SPU".equals(type)` 而 `type` 是 `CATALOG_SPU`，**两边永远不相等** ⇒ `spuId`/`skuId` 永远是 null
     ⇒ 上游查询不带任何过滤、**返回全量条目**（「查一个 SPU」变成「查所有 SPU」）。
     已改为按 `CATALOG_SPU`/`CATALOG_SKU` 判定，并把测试桩改成**回显收到的 spuId**，
     让「上游有没有被按 SPU 过滤」成为可断言的事实。
   - **发布闸状态语义**：未确认就发布被报成 400（参数错）⇒ 改 **409**；同族四条判断一并统一。
   - **测试夹具** `Map.of` 带 null 值 ⇒ NPE ⇒ 桩自己炸成 500 ⇒ 真错被 503 藏起来。

---

## 五、静态预检（5.1）：本批 3 个新迁移逐个扫

| 形状 | V379（reporting） | V380（platform） | V380（identity） |
| --- | --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **零命中** | **零命中** | **零命中** |
| ② `SET NOT NULL` | **零命中** | **零命中** | **零命中** |
| ③ `ADD CONSTRAINT … CHECK` | **零命中** | **零命中** | **零命中** |
| RLS | 2 表 **ENABLE + FORCE** + `isolation` 策略 | 7 表逐表 **FORCE** + 策略 | 不适用（权限码登记，无业务表） |

全树扫描的命中（①113 / ②42 / ③28）**全部是历史已交付迁移**，早已过 L2。

---

## 六、🔴🔴 本轮真正的战果：**L2 连续两轮假红，根因是「跨时钟域断言」**

这是本轮最值得记的一件事。**两轮 L2 红的都是既有模块的既有测试，而本批一行代码都没碰过它们。**

### 6.1 现象

| 轮次 | 时间 | 红在哪 | 失败原文 |
| --- | --- | --- | --- |
| 第 1 轮 | 12:26–13:43 | `warehouse.WarehouseConfigIntegrationTest.留痕时刻是真实的_不是客户端传的` | `Expecting actual: 13:21:17.383440+08:00 to be strictly after: 13:36:37.298216+08:00` |
| 第 2 轮 | 14:08–14:42 | `channel.SyncHealthIntegrationTest.staleSyncEscalates` | `expected: "YELLOW" but was: "GREEN"` |

**两次红的模块、测试类、报错都完全不同** —— 这是**环境诱发的不稳定**的签名，不是代码回归的签名
（回归是确定性的、会每次落在同一处）。按 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`
的判据三步走，结论一致：**不是本批的锅**。

### 6.2 根因（已用物理拨钟实证，不是推演）

**PG 的 `now()` = `transaction_timestamp()` = 事务的开始时刻。**
仓库里大量列定义是 `changed_at timestamptz NOT NULL DEFAULT now()`，
于是「落库时刻」实际是**写入事务的起点**。
而断言却拿**客户端墙钟** `OffsetDateTime.now()` 播种的值去比 ⇒ **两个时钟在不同域里比**。

| 机制 | 细节 |
| --- | --- |
| channel 那例 | 服务端用 `sql.fetchValue("select now()")`（DB 钟）算 `staleHours`，判定是**严格大于** `> 24`；`staleHours` 是**向下取整**的小时数。测试播种 `now.minusHours(25)`（客户端钟）⇒ 只要 **DB 钟比客户端慢 1 毫秒**，经过时间就是 `24h59m59.999s` ⇒ 截断成 **24** ⇒ `24 > 24` 不成立 ⇒ 判绿 ⇒ **测试红** |
| warehouse 那例 | 同原理的放大版：`changed_at` = 写入事务的 `now()`，而那一次机器被 swap 压垮、JVM 冻结约 **15 分 54 秒**（Hikari 打出 `Thread starvation or clock leap detected (housekeeper delta=15m54s922ms)`），冻结点 13:21:17 正好落在写入事务开启时刻 ⇒ 留痕时刻比断言捕获的 `before` 早了整整 15 分钟 |

**实证方式**：worker 用 `--privileged` 容器 + `date -u -s @<epoch>` **把 PG 容器的系统时钟真的拨慢**
（PG 的 `now()` 取的就是它），用**改动前**的代码复现出**逐字相同**的报错：

```
PROBE 客户端墙钟 = 2026-10-25T07:04:35.743459Z | 数据库时钟 = 2026-10-25T07:04:34.568109Z | 两者相差 1s
expected: "YELLOW"
 but was: "GREEN"
```

**1 秒偏移即翻车。** 改动后在「慢 1 秒」与「慢 1 小时」两种物理错位下**仍全绿**。

### 6.3 为什么这次特别容易翻车：**容器泄漏把机器压垮了**

| 观测 | 数值 |
| --- | --- |
| 全量 verify 期间堆积的 Testcontainers PG 容器 | **54 → 61 个**（同一 ryuk session） |
| swap | **4.13 GB / 5 GB**，空闲页只剩 **4270 页 ≈ 68 MB** |
| Docker 虚拟机 CPU | **287%** |
| JVM 冻结 | **15 分 54 秒** |

⇒ 仓库里 **97 个集成测试类不 `.stop()` 容器**（`docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`
已记：**343 个类用 `PostgreSQLContainer`，其中 246 个显式 `.stop()`，97 个不 stop**），
单次 session 内累积 62+ 个 → Docker VM 内存爆 → swap 抖动 → **宿主机与 PG 容器的时钟关系被打乱**
→ 跨时钟域断言翻车。

**⇒ 本轮两次假红，是「容器泄漏」这个已知未修问题第一次以「L2 连续两轮假红」的形态显形。**

### 6.4 修复（2 个任务，生产代码零改动）

| commit | 内容 |
| --- | --- |
| `f16de390`（源 `444bea4d`） | `WarehouseConfigIntegrationTest`：断言对象换成**本次写入产生的那一行**（加前置断言保证确实只写一行）；「时刻由服务端给出」改在**服务端自己的时钟域**里回答（留痕时刻必须逐字等于同一事务的 `now()`，且不晚于 `clock_timestamp()`）；「当下性」改比服务端墙上时刻，不再拿留痕时刻卡 5 秒窗口 |
| `be3a9d24`（源 `3f8b6744`） | 6 个测试文件：`channel.SyncHealthIntegrationTest`（播种基准改用 `dbNow()`，余量 24h→**30h** / 72h→**90h**，并补更强的 `staleHours` 区间断言）、`identity.ApprovalFlowIntegrationTest`、`identity.ApprovalRuleIntegrationTest`、`catalog.ImportWizardIntegrationTest`、`trade.FulfillmentRequestIntegrationTest`（改为在 DB 时钟域内前后夹逼落库时刻）、`identity.IdentityIntegrationTestBase`（新增共用 `dbNow()`） |

**worker 推翻了我的一条判断（值得记）**：我原本点名
`warehouse.OutboundOrderIntegrationTest` / `OutboundOrderDetailIntegrationTest` 两处
「客户端墙钟 ±5 秒 vs DB `DEFAULT now()`」也是同类隐患。
worker 实测后**否定了这个前提**：`OutboundOrderService.create` **显式绑定** `OffsetDateTime.now()`（JVM 钟），
`DEFAULT now()` 根本没被用上 ⇒ 与测试的 `before` **同属一个时钟域**；
在 DB 钟被拨慢 1 小时的情况下这两类**仍然全绿**（33/33、20/20）⇒ 条件不成立，**没有改**。

### 6.5 处置纪律（沿用 backlog §六之三 的既有做法）

1. **不二分** —— 非确定性失败二分无意义（本批根本没碰那些文件）。
2. **L2 不绿不 push** ⇒ 修复后**重跑整批 L2**，绿了才 push。
3. **不在集成头上继续改** ⇒ 两个修复都走独立 worktree + 独立分支，由调度者并入。

---

## 七、L2 批量闸：跑了三轮

| 轮次 | 后端 | typecheck | lint | 前端测试 | Next build | sync --check |
| --- | --- | --- | --- | --- | --- | --- |
| 第 1 轮（12:26–13:43） | 🔴 warehouse 1 例假红 | ✅ | ✅ | ✅ **634 文件 / 8803 例** | ✅ | ✅ |
| 第 2 轮（14:08–14:42） | 🔴 channel 1 例假红 | — | — | — | — | — |
| **第 3 轮（15:06–16:20）** | 🔴 **warehouse BUILD FAILURE**（`PendingInspectionTenancyIntegrationTest` 3 例 ERROR，`Connection to localhost:60087 refused`）；**trade / crm / finance / reporting / platform 全部 SKIPPED** | — | — | — | — | — |

⚠️ **第 3 轮的失败性质与第 1、2 轮不同**：第 1、2 轮红在**断言取值**（跨时钟域），
第 3 轮红在**容器被删**（`Connection refused`）—— **是本文档第七节引入的「容器清道夫」自己造成的**，
详见第十二节勘误。

**第 1 轮的一个重要副作用**：后端 reactor 在 warehouse 处**失败即停** ⇒
**trade / finance / reporting / platform / crm 根本没跑到** ——
也就是说**本批的两个新模块在第 1 轮里压根没被全量验证过**。
第 2 轮同因（channel 处停）再次没跑到。**第 3 轮同因（warehouse 处停）第三次没跑到**
⇒ **三轮 L2 全部没有验证过本批的两个新模块**（13.4.2.2 在 reporting，18.4.2.1 在 platform）。

### 第 3 轮做了什么额外的事

L2 脚本本轮新增两项（依据 backlog §六之三 / §七 的流水线侧建议）：

1. **时钟偏差探针**：开工前 + **每 5 分钟**复查一次「宿主机墙钟 vs PG 容器 `clock_timestamp()`」的偏差，
   并把偏差、PG 容器数、swap 用量写进 L2 日志 —— 让这类假红**下次自带证据**。
2. **容器清道夫**：每 5 分钟扫一次，**只删「本次 ryuk session + 已存在 ≥180 秒 + 连接数为 0」**的容器；
   探针失灵（VM 卡）就跳过、**绝不盲删**；**绝不碰别的 session**（backlog §八 的纪律）。

   > 🔴 **这一项是错的，已在 batch-97 被停用。**
   > 判据「age ≥180 秒 + 连接数 0」**无法区分「泄漏容器」与「跨测试类复用的共享 static 容器」** ——
   > 后者在本仓有三处：`IdentityIntegrationTestBase`（43 个测试类继承）、
   > `PendingInspectionTestBase`、`ConsignmentStockTestBase`。
   > 它们建好之后、在下一个测试类启动前的那段时间里**连接数恰好是 0、年龄必然超过 180 秒** ⇒ **必被杀**。
   > 实测：容器 `60087` 于 **15:33:12** 被 `PendingInspectionIntegrationTest` 建出，
   > 清道夫于 **15:55:39** 把它删掉（自报 `age=1347s`，与 15:33:12 **精确吻合**），
   > 而依赖它的 `PendingInspectionTenancyIntegrationTest` 于 **15:59:03** 才启动 ⇒ **`Connection refused`**
   > ⇒ **L2 第 3 轮的红，是这道止血措施自己制造的。**
   > batch-97 已 **kill 掉清道夫进程**（PID 47653）并改用「分服务独立 maven session」来限制容器峰值（见 batch-97 文档）。

---

## 八、收尾三件套：写说明 → commit → push

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ **仅写到文件** | 本文件 `docs/sessions/2026-10/2026-10-25-batch-96-two-lane-delivery-and-clock-domain-fix.md`（批次号 96）—— 但**从未被 commit** |
| ② commit | ❌ **未完成** | 说明文件与台账**都还是工作区里的未提交改动**（`git status` 实测：`M module-progress.md` + `?? batch-96....md`）⇒ 协议要求的「说明 + 台账同一个 commit」**根本没发生** |
| ③ push | ❌ **未发生** | `git rev-list --count origin/integration/module-dev..HEAD` = **9** ⇒ 两条车道的代码 + 两个时钟域修复 + 只读轮文档 **9 个 commit 全部未推送**。协议「L2 没绿不 push」是**对的**，但也不能记成「push ✅」 |

---

## 九、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过） |
| 每批车道数 | **2**（`LANES` 4 减半） |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时**（实测：上批落盘 10:02:22 → 本批落盘 12:00:22 = **118 分钟**） |
| 每日实际放行批次 | ≈ **12 批/天** |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70** |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余可自动排完量 | **59 个**（63 − 4 个循环依赖；其中 2 个本批已交付 ⇒ **57 个待排**）⇒ 57 ÷ 17 ≈ **3.4 天**（区间 **3–5 天**） |

⚠️ **修正 batch-94 的一处数字错误**：循环依赖实为 **4 个**模块
（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`，两对互相可达成环），batch-94 写成「8 个」但只列了两对。

---

## 十、风险提示

1. **🔴 容器泄漏是这一切的放大器** —— 97 个测试类不 `.stop()` ⇒ 单次 verify 堆 62+ 容器 ⇒ swap 爆 ⇒
   **时钟关系被打乱 ⇒ 跨时钟域断言翻车**。本轮两次假红都是它的下游后果。
   **根治方案已在 backlog**（让那 97 个类自己收干净），**本轮未做**（跨 97 个文件的改动不适合塞进一个交付批次）。
   本轮只做了「L2 期间有节制地清理同 session 的无连接老容器」作为止血。
2. **🔴 宿主机 vs PG 容器的时钟漂移没有上界** —— 历史上量到 542ms、297 秒，今天量到 **15 分 54 秒**。
   backlog §七 的建议是「开工前量偏差，超阈值就别开跑 + L2 全程复查」——
   **本轮已把这两个探针实装进 L2 脚本**（见第七节）。
3. **🔴 连续三轮同一个失败模式** —— batch-92 / batch-93 / batch-94 的车道都是被
   `net::ERR_CONNECTION_CLOSED`（errorCode 50113）打死，且**都死在 commit 之前**。
   本轮已重派并成功交付，**但这个环境级风险仍在**。建议 worker 改为**阶段性提交**，断线时也不至于从零开始。
4. **并发纪律问题（新发现）** —— 15:00 那轮 cron tick 撞到我持有的闸后，正确地**零派发只读**结束了，
   **但它仍然往集成分支提交了一份只读轮文档**（`d0270403`）⇒ **闸持有期间集成头仍被并发写入**。
   这不是代码问题，但它违反了「持闸即独占集成头」的意图。**建议在协议里补一句：撞闸轮次不得 commit。**
5. **4 个循环依赖模块**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`）需要人工拍板拆环（见第九节）。
6. **用户机器上 `langfuse-worker` / `langfuse-web` 两个容器处于崩溃重启循环**
   （重启 **7687 / 7740** 次）⇒ 持续给 Docker VM 制造抖动。这是**用户的服务，本轮未做任何干预**，
   但它是「机器变卡」的已知来源之一，值得用户自行处理。

---

## 十一、文件清单

**本轮调度器写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-25-batch-96-two-lane-delivery-and-clock-domain-fix.md`（本文件，新增）
- `docs/product/capability-blueprint/module-progress.md`（台账，由 `sync.mjs` 回写）

**本轮并入的代码**：

- 13.4.2.2 费用差异（reporting）—— 迁移 `V379__expense_variance.sql` + 服务/端点/页面/测试
- 18.4.2.1 文本与翻译（platform + identity）—— 迁移 `V380__text_translation.sql` +
  `V380__platform_text_permission.sql` + 端点/页面/测试
- `apps/web/src/lib/api-proxy.ts`（**纯新增 107 行，零删除**）、`packages/api-contracts/**`

**本轮并入的测试修复**（生产代码零改动）：

- `backend/services/warehouse/src/test/.../WarehouseConfigIntegrationTest.java`
- `backend/services/channel/src/test/.../SyncHealthIntegrationTest.java`
- `backend/services/identity/src/test/.../ApprovalFlowIntegrationTest.java`
- `backend/services/identity/src/test/.../ApprovalRuleIntegrationTest.java`
- `backend/services/identity/src/test/.../IdentityIntegrationTestBase.java`
- `backend/services/catalog/src/test/.../ImportWizardIntegrationTest.java`
- `backend/services/trade/src/test/.../FulfillmentRequestIntegrationTest.java`

**并入的会话日志**（worker 各自写的）：

- `docs/sessions/2026-10/2026-10-25-13-4-2-2-expense-variance.md`
- `docs/sessions/2026-10/2026-10-25-18-4-2-1-text-translation.md`

---

## 十二、🔴 勘误（batch-97 tick 写入，2026-10-25 17:2x）

上一 tick（12:00）崩在 `net::ERR_HTTP2_PING_FAILED`，**在它自己的 L2 跑完之前就死了**。
batch-97 接手时对现场做了取证，结论与本文件原来的记载**有两处不符**，现按事实更正。

### 12.1 第 3 轮 L2 的真实结果：🔴 BUILD FAILURE，不是 🟢

日志 `.tmp/l2r3-150653.log`（15:06:53 → 16:20:19）尾部实测：

```
[INFO] identity ................................. SUCCESS [02:31 min]
[INFO] catalog ................................. SUCCESS [06:02 min]
[INFO] channel ................................. SUCCESS [16:15 min]
[INFO] warehouse .......................... FAILURE [48:30 min]
[INFO] trade ................................ SKIPPED
[INFO] crm .................................. SKIPPED
[INFO] finance .............................. SKIPPED
[INFO] reporting .......................... SKIPPED
[INFO] platform ........................... SKIPPED
[INFO] Total time:  01:13 h
```

失败用例：`warehouse.PendingInspectionTenancyIntegrationTest` **3 例 ERROR**，
报错 `Connection to localhost:60087 refused` ⇒ `ApplicationContext` 加载失败。

### 12.2 根因：清道夫误杀了**共享静态容器**（不是跨时钟域断言）

时间戳链（全部来自 docker / 进程实测，非推演）：

| 时刻 | 事件 |
| --- | --- |
| 15:33:12 | 容器 `60087` 被 `PendingInspectionIntegrationTest` 创建（**共享 static 容器**，`PendingInspectionTestBase` 持有） |
| 15:55:39 | **清道夫把它删掉** —— 清道夫自报 `age=1347s`，`1347 + 15:55:39 = 15:33:12` **精确吻合** |
| 15:59:03 | `PendingInspectionTenancyIntegrationTest` 启动，**依赖这个已被删的容器** ⇒ `Connection refused` |

⇒ 「清道夫只删 age≥180s 且连接数 0 的容器」这条判据，
**分不清「泄漏容器」与「跨测试类复用的共享 static 容器」**：
后者在下一个测试类启动前的那段时间里，连接数**恰好是 0**、年龄**必然已过 180 秒** ⇒ **必被杀**。

**同时排除的两个替代解释**：

- **不是内存耗尽**：失败时存活容器仅 **2** 个；无 OOM / kill 证据；host 16GB、Docker VM 12.5GB、单容器 ~20.65MiB。
- **不是宿主机与 PG 的时钟漂移**：时钟偏移确实存在（r1 的 15 分 54 秒冻结有据），
  但 r3 的报错形态是**连接被拒**，与断言取值无关。

### 12.3 对「跨时钟域断言」这条结论的重新评估

- **r1（warehouse 留痕时刻）与 r2（channel staleSync）两次红的诊断本身仍有据**
  （物理拨钟复现过，有逐字相同的报错原文），对应的两个修复 commit `f16de390` / `be3a9d24`
  **仍在 HEAD 上，batch-97 未回退**。
- 但**它们从未被一次全绿 L2 验证过**：r3 挂在它们之前，且 r3 之后 trade / crm / finance /
  reporting / platform **一次都没跑到** ⇒ **batch-96 交付的两个新模块至今未经全量验证**。
- ⇒ **「L2 连续两轮假红，根因是跨时钟域断言」这个总结是不完整的**：
  真因是「**容器泄漏 + 误杀清道夫**」这条独立故障线，跨时钟域断言只是它的下游受害者之一。

### 12.4 batch-96 遗留状态（batch-97 接手时的真实快照）

| 项 | 状态 |
| --- | --- |
| 未推送 commit | **9 个**：`8fd35cd5` `4b9a48a1` `ee2a2a56` `e1d59315` `444bea4d` `f16de390` `3f8b6744` `be3a9d24` `d0270403` |
| 本说明文件 | **未跟踪**（`??`） |
| 台账 `module-progress.md` | **已修改未提交**（`M`） |
| 调度闸 | owner PID 6436 **已死**、闸龄超 TTL ⇒ 属陈旧闸，batch-97 依协议重建取得 |

### 12.5 batch-97 的处置

1. **kill 掉清道夫**（`janitor-loop.sh` PID 47653 与 `janitor.sh`），**不再使用这条判据**。
2. **改用「分服务独立 maven session」限制容器峰值** —— 实测 ryuk 在 maven session 结束时**完整回收**容器
   （catalog 单服务 session 结束后 `docker ps --filter label=org.testcontainers.sessionId` = **0**），
   于是峰值容器数从「全 reactor 累积」压到「单服务峰值」，**不需要动任何一个测试类**。
3. **根治方案（给 238 个不 `.stop()` 的类补 `@AfterAll`）仍属 backlog 的「待人工决策」**，
   本轮**不自作主张**（跨 97+ 文件的改动不适合塞进一个交付批次，见第十节风险 1）。
