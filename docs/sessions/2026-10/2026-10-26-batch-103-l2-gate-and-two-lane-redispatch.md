# 2026-10-26 · batch-103 —— 崩在派发前的两条车道回收重派、L2 独占全量验证、26 个提交首次推上远端

> 批次号 **103**。
> ⚠️ **为什么不是 102**：11:07 那一轮的 tick（内部编号 r103）在 `batch-101` 的说明文件里
> 自称 **batch-102**（见该文件第六/九节「本节由 batch-102 补写」），
> 但它**崩在写自己的说明文件之前** ⇒ `docs/sessions/2026-10/` 里**从来没有 batch-102 的文件**。
> 为了不让两个不同的轮次共用同一个批次号（这条分支上还有别的 agent 在写），
> 本轮按「不重号」原则取 **103**。后续轮次请从 104 往后排。
>
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-26 14:00 触发）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度（开工） | 465 总数 · **408 已交付** · 0 进行中 · **57 未开发**（完成率 **87.7%**，离 465 还差 **57**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **1.52 / 1.63 / 1.70**（限 12，1min < 5min < 15min **不是爬升**）、Testcontainers PG **0** 个（限 6）、**孤儿容器 0** |
| 节流闸 | 距上批 **595 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 调度闸 | 初检 `free`（`stale=false`）⇒ `gate acquire` **成功**（退出码 0）⇒ 全程持闸 |
| 本轮回收 | **0 条** —— `merge-ready` 回「没有待并入的车道」（212 条车道：210 `merged` / 2 `running`）；**真正被回收的是 11:07 那一轮崩在派发前的 2 条车道** |
| 本轮派发 | ✅ **2 条** —— `1.3.2.2` 外部渠道推送（`V385`）/ `1.1.2.2` 店铺概览（`V386`），**重派**，不是新排 |
| 静态预检 | ①`ADD COLUMN` 无 `DEFAULT` **本批零命中**、②`SET NOT NULL` **本批零命中**、③`ADD CONSTRAINT … CHECK` **本批零命中**（全仓扫描的 113/42/28 处命中**全部在 V3–V371 的存量迁移里**，本批未新增任何迁移）；集成头最大迁移版本 **V384**，`V385`/`V386` 未被占用 ✅ |
| **L2 批次闸** | ✅ **绿**（后端 10 个模块全绿 · 前端 5 项全绿）—— 详见第五节 |
| 收尾三件套 | ① 说明已写、② commit `本文件所在 commit（`docs(batch-103):`）`、③ push `✅ 已 push` |
| **本轮首次把积压的 26 个提交推上远端** | 远端原为 `1e3228b4`，本地头 `cd9e125e`，**积压 3 轮未推送**（batch-99/100/101 都因 L2 红而「本轮不 push」） |

---

## 一、进度

`node tools/module-progress/sync.mjs` ⇒ 18 卷 / **465 个 L4 模块**，
已交付 **408** · 进行中 0 · 未开发 **57**（完成率 **87.7%**）。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 101 | 0 | 54 |

**剩余 57 个的分布（未开发）**（按 L2 分组，调度者本轮逐行核对台账得出，合计 **57**）：

| 分组 | 未开发 | 明细 |
| --- | --- | --- |
| **18.x 集成开放与智能自动化** | **21** | 18.1 接口目录/调用监控/事件订阅/投递记录 **6** · 18.2 **2** · 18.3 规则引擎 **5** · 18.4 **6** · 18.5 模板与投递 **2** |
| **13.x 利润与经营分析** | **20** | 13.1 **2** · 13.2 **4** · 13.3 **4** · 13.4 **4** · 13.5 **3** · 13.6 **3** |
| **15.x 客户与会员** | **6** | 15.3 **3** · 15.6 会员与等级 **3** |
| **14.x 广告与增长** | **4** | 14.2 竞价/分时策略 **2** · 14.3 商品维度报表/归因 **2** |
| **5.1 定价** | **3** | `5.1.1.1` / `5.1.1.2` / `5.1.2.1` —— **循环依赖**，自 batch-98 起连续点名，需人工拍板拆环 |
| **1.x 工作台与门户** | **3** | `1.1.2.2` 店铺概览、**`1.3.2.2` 外部渠道推送**（本轮重派）、`1.5.2.1` 服务与任务状态 |

**台账完整性核对**：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md` ⇒ **465**（恒定）；
重复 ID ⇒ **零**。

---

## 二、容量闸与节流闸

| 闸 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| **温度闸（最高优先级）** | `pmset -g therm` ⇒ `Note: No thermal warning level has been recorded` | 出现降频项且 < 100 ⇒ STOP | ✅ **通过** |
| **配额闸** | running **0** | ≥ 7 ⇒ STOP | ✅ **通过** |
| **PG 闸** | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` = **0** | ≥ 6 ⇒ STOP | ✅ **通过** |
| **load 闸** | `1.52 / 1.63 / 1.70`（1/5/15 min），核数 **10** | 1min > 核数 ⇒ STOP | ✅ **通过**（1min < 5min < 15min ⇒ **不是爬升**） |
| **节流闸** | 距上批 **595 分钟** | 100 分钟（降档后） | ✅ **放行** |

**孤儿容器**：本轮 `docker ps` 里 **带标签 0 个 + 无标签 0 个** ⇒ 无历史遗留孤儿容器需要清理。
**孤儿 keepalive 守护进程**：本轮**没有**起常驻守护（只用了协议规定的
「`touch` 一个**新文件**续期」），`ps` 里零个 keepalive 进程 ⇒ **P0 死锁源本轮没有复发**。
上一轮 r103 留下的 `STOP-KEEPALIVE-R103` 标记在 12:30 已置位，闸目录本轮开工时**不存在**（`gate=free`）。

**档位**：**降档模式**（`TOKEN_HORIZON` 已过）⇒ 每批 **2** 条车道、节流闸 **100** 分钟。
**这是自动降档，不是调度者自选；期限不改变停机条件**（停机只看「465 全部已交付」）。

---

## 三、本轮回收：11:07 那一轮崩在「派发前」，两条车道是空壳

`node tools/module-progress/lane.mjs merge-ready` ⇒ `{"ok": true, "merged": [], "note": "没有待并入的车道"}`（退出码 0，无冲突）；
`lane.mjs gc` ⇒ `{"ok": true, "removed": []}`。

**车道层面没有待回收的东西，但有两条「排了车道却从未真正开工」的空壳车道**：

| 模块 | 状态 | 取证 |
| --- | --- | --- |
| `1.3.2.2` 外部渠道推送（`V385`） | `running` | `sessionId = null`；`heartbeatAt = createdAt = 2026-10-26T03:07:00.321Z`（**两个时间戳只差 1 毫秒 ⇒ 这不是 worker 续的心跳，是 `plan` 建车道时写进去的**）；worktree HEAD = 基线 `f64946c9`，**零提交** |
| `1.1.2.2` 店铺概览（`V386`） | `running` | 同上（`heartbeatAt = createdAt = 03:07:05.331Z`）；零提交 |

⇒ 结论：**11:07 那一轮 tick（r103）执行到了 `plan`，但崩在「把 `task` 派出去」之前**
（`plan` 有副作用：建分支 + 建 worktree + 装依赖 + 写 `lanes.json`；`task` 派发是下一步）。
**它留下的两条车道是「排了但没人认领」的孤儿**：
`lanes.json` 里 `sessionId` 为空、心跳停在建车道那一刻、工作区里一个 commit 都没有。

**处置**：`plan` 内建的孤儿回收逻辑（`laneIsUnclaimed`：`sessionId` 为空 **且** 距心跳 > 60 分钟）
正好命中这两条 ⇒ 本轮 `plan --lanes 2` 会把它们**原样重派**（保留分支、worktree 与
`V385`/`V386` 版本号，不另排新模块、不浪费版本号）。
本轮开工时距其心跳已 **173 分钟**，远超 90 分钟 TTL ⇒ **确属崩溃车道，不是活着的 worker**。

> 📌 **这一条是本轮最重要的取证**：上一轮的说明文件里写着「派发了 2 条车道」，
> 但 `lanes.json` / worktree / `git branch` 三处证据都表明**那两条车道从未真正开工**。
> 「派发」这一动作在 `plan` 之后、`task` 之前 —— 中间崩掉就是空壳，
> **只有 `sessionId` + 心跳 + 提交数这三样能证明 worker 真的开工了**。

---

## 四、静态预检（协议 5.1「已知互相破坏形状」）

对 `backend/services/*/src/main/resources/db/migration/` 全量扫描：

| 检查项 | 全仓命中 | 本批新增 | 判定 |
| --- | --- | --- | --- |
| ① `ADD COLUMN` 且无 `DEFAULT` | 113 处 | **0** | ✅ 本批零命中 |
| ② `SET NOT NULL` | 42 处 | **0** | ✅ 本批零命中 |
| ③ `ADD CONSTRAINT … CHECK` | 28 处 | **0** | ✅ 本批零命中 |

**全仓那些命中全部在 V3–V371 的存量迁移里**（catalog V14/V41/V15、channel V194/V196/V197/V199、
finance V350/V359、identity V7/V47/V83、trade V98/V238/V246…），
**本批没有并入任何车道 ⇒ 本批没有新迁移 ⇒ 跨车道 DDL 回归面 = 0**。
集成头最大迁移版本 **V384**，`V385`/`V386` **未被占用** ✅。

---

## 五、L2 批次闸结果

**结论：L2 批次闸最终判定为 🟢 绿**，但**不是一次跑绿**，而是「跑红 → 隔离复跑定性 → 补跑没跑到的模块 → 修掉一处真实缺陷」四步走出来的。
这一节把每一步的证据都摆出来，因为**其中两处红是环境假红、一处红是真缺陷**，必须分开记。

### 5.1 第一遍：单 reactor `./mvnw verify`（14:03:04 → 15:45:45，耗时 1h42m）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `backend && ./mvnw verify`（单 reactor） | 🔴 **exit=1**（**Total time 01:42 h**） |
| 2 | `pnpm typecheck` | ✅ exit=0 |
| 3 | `pnpm lint` | ✅ exit=0 |
| 4 | `pnpm test` | ✅ exit=0（**642 个测试文件 / 8935 例全绿**） |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ exit=0（Compiled successfully in 11.3s，370/370 静态页） |
| 6 | `node tools/module-progress/sync.mjs --check` | ✅ exit=0（✓ 台账与蓝图一致） |

**后端逐模块**：

| 模块 | 测试数 | 结果 |
| --- | --- | --- |
| identity | 619 | ✅ 全绿 |
| catalog | 1998 | ✅ 全绿 |
| channel | 2344 | ✅ 全绿（**上一轮那个 `credential_window` 假红没有复发** —— `734ec399` 的修复生效） |
| warehouse | 1672 | 🔴 **1 处红**（见 5.2） |
| **trade / crm / finance / reporting / platform** | — | ⚠️ **根本没跑**：maven 默认 fail-fast，warehouse 一红就中止了 reactor |

### 5.2 warehouse 的 1 处红 = **机器拖慢型假红**（隔离复跑定性）

```
com.starcon.warehouse.ReservationIntegrationTest.超时长从真实写入时刻算而不是零
  Expecting actual: 2238L to be between: [2280L, 2520L]
```

**算术**：测试播种 `expireAt = now − 40min`，期望超时长 ∈ [2280, 2520]（即 2400 ± 120 秒）；
实测 **2238** ⇒ 差了 **−162 秒** ⇒ **「查询那一刻的钟」比「播种那一刻的钟」还早 162 秒**
⇒ **机器墙钟在测试运行中向后跳了**。

**三条独立环境证据**：

1. 同一份 L2 日志里有 **85 条** `Thread starvation or clock leap detected (housekeeper delta=13m55s)`
   ⇒ 墙钟/墙钟推进在测试运行中大幅漂移。
2. 失败的测试类耗时 **909.4 秒**（同一份代码隔离跑只要 14 秒）⇒ 机器被拖慢约 **62 倍**。
3. 该测试文件最后改动是 `4cc6c321`，**本批没有并入任何车道** ⇒ **无回归面**。

**隔离复跑（机器空闲、容器峰值 1）**：

```bash
cd backend && ./mvnw -pl services/warehouse -am test -Dtest='ReservationIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.48 s —— BUILD SUCCESS
```

⇒ **35/35 全绿，14.5 秒**。**定性：环境假红，不是回归。**

### 5.3 trade 的 3 处红 = **容器堆积导致的连接被拒假红**（隔离复跑定性）

补跑 trade 时（`mvnw -pl services/trade,... -am verify`）红在 3 处，**全部是 503**：

| 测试 | 期望 | 实得 |
| --- | --- | --- |
| `SourceAlternativeIntegrationTest.HTTP契约401与403与404` | 401 | **503** |
| `SourceAlternativeTenancyIntegrationTest.伪造租户头无效` | 200 | **503** |
| `SourceAlternativeTenancyIntegrationTest.两租户互不可见` | 200 | **503** |

**根因**：surefire `reuseForks=true` ⇒ 一个服务 = 一个 fork JVM ⇒ **每个测试类各起一个 PG 容器且不 `.stop()`**，
ryuk 只在 session 结束才回收 ⇒ 跑到中段时机器上**同时有 12 个** `postgres:16-alpine`、
swap 用掉 **4.2G/5G**、pageouts **91 万** ⇒ 容器被压垮 ⇒ 日志里 **248 处**
`Connection refused` / `failed to create/setup connection` ⇒ 应用返回 **503**。

> 这正是 2026-10-19 20:00 那轮记录过的形状（`seed()` 503 / 连接被拒 ⇒ 成片假红）。

**隔离复跑（机器空闲、容器峰值 1）**：

```bash
cd backend && ./mvnw -pl services/trade -am test \
  -Dtest='SourceAlternativeIntegrationTest,SourceAlternativeTenancyIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ SourceAlternativeTenancyIntegrationTest: 3/3 绿（8.3s）
# ⇒ SourceAlternativeIntegrationTest: 18/18 绿（3.6s）
```

⇒ **21/21 全绿**。**定性：环境假红（容器堆积），不是回归。**

### 5.4 finance 的 1 处红 = **真实缺陷**（测试的日期脆弱性），已修

`FinanceFundAnalysisTest` / `FundAnalysisIntegrationTest.付款集中度是计划口径且给出真实占比`：

```
Expected size: 3 but was: 2 in:
[FinanceFundConcentrationBucket[periodStart=2026-11-02, amount=600.0000, share=0.750000],
 FinanceFundConcentrationBucket[periodStart=2026-11-09, amount=200.0000, share=0.250000]]
```

**根因（调度者逐行取证）**：

1. 分桶用 `date_trunc('week', p.planned_paid_date)` ⇒ **ISO 周，周一起算**。
2. 桶序列由 `periodSequence(有数据的首桶, 有数据的末桶)` 生成 ⇒ **中间空桶必须靠「首末桶之间差两周」才会被补出来**。
3. 但 fixture 里两个 USD 付款计划是 **TODAY+7 / TODAY+20（相隔 13 天）**，
   与该测试**自己的注释**「两个计划隔了两周 ⇒ 中间那个空桶必须在」**矛盾**。
4. `TODAY = LocalDate.now(ZoneOffset.UTC)`，而**今天 2026-10-26 是周一**：
   `TODAY+7 = 2026-11-02` 正好是周一 ⇒ 被 `date_trunc` 截到本周一；
   `TODAY+20 = 2026-11-15` 落进下一个桶 ⇒ 两个计划**相邻** ⇒ **中间根本没有空桶** ⇒ 断言失败。
   ⇒ **`TODAY` 不是周一时**，A 的周起点早一天、B 落进下下个桶 ⇒ 空桶出现 ⇒ 断言通过。
   ⇒ **这个测试每周只有周一是假红灯**（一天红、六天绿）。

**修法**（commit `cd9e125e`）：把 B 从 `TODAY+20` 挪到 **`TODAY+21`**（应付单到期日与计划日同步挪，保持 `DUE_DATE` 语义一致），
两个计划**恰好相隔 14 天 = 2 个 ISO 周** ⇒ 必然相隔一个桶 ⇒ **中间空桶任何星期都必然存在** ⇒ 断言与日期无关。
**生产代码与迁移零改动**，只改测试 fixture。

**复跑**：`./mvnw -pl services/finance -am verify` ⇒ **BUILD SUCCESS（970/970）**，
`FundAnalysisIntegrationTest` **19/19 绿**。

### 5.5 补跑没跑到的模块（一服务一 session，压住容器峰值）

用 batch-97/98/99 验证过的「**一服务一 maven session**」拆法（每个 session 结束 ryuk 回收本 session 的容器）：

| 服务 | 结果 | 耗时 | 容器峰值（结束时） |
| --- | --- | --- | --- |
| crm | ✅ **491/491** | 1m | 34 |
| finance（修完日期脆弱测试后） | ✅ **970/970** | 3m | 64 → 修完后单跑 ✅ |
| reporting | ✅ **69/69** | <1m | 8 |
| platform | ✅ **97/97** | <1m | 4 |

### 5.6 L2 最终判定

| 层 | 覆盖 | 结果 |
| --- | --- | --- |
| 后端 | tenancy · identity 619 · catalog 1998 · channel 2344 · warehouse 1672 · trade 2458 · crm 491 · finance 970 · reporting 69 · platform 97 ⇒ **10 个模块、约 10 718 例** | 🟢 **全绿**（warehouse 1 处 + trade 3 处 = 环境假红，已隔离复跑定性；finance 1 处 = 真实缺陷，已修并复跑绿） |
| 前端 | typecheck · lint · **vitest 642 文件 / 8935 例** · **Next production build（370/370 静态页）** · `sync --check` | 🟢 **全绿** |

⇒ **本轮 L2 绿 ⇒ 允许 push。**

> ⚠️ **本轮 L2 的成本（诚实记录）**：单 reactor 一遍 1h42m + 隔离复跑 2 次 + 补跑 5 个模块 ≈ **2h20m**。
> 根因是**一个 maven session 里容器只增不减**。⇒ **下轮改进项（已写进第十节风险）**：
> L2 改用「一服务一 session」的拆法，把容器峰值压到「单服务峰值」，
> 而不是「一整个 reactor 累加」—— 后者跑得越久，红的形状越像回归、归因成本越高。

---

## 六、本批派发的两条车道（**重派**，开发中，本轮不交付）

本轮**没有新交付的业务模块**（`merge-ready` ⇒ 0 条可并入）——
两条车道是**重派**出来的（上一轮崩在派发前，它们是空壳），现在在后台开发，
下一轮 tick 回收并入。

| 模块 | 名称 | 主责服务 | 分支 | worktree | 迁移版本 | 重派次数 |
| --- | --- | --- | --- | --- | --- | --- |
| `1.3.2.2` | 外部渠道推送 | identity | `feat/1-3-2-2` | `.worktrees/1.3.2.2` | **V385** | **1** |
| `1.1.2.2` | 店铺概览 | reporting | `feat/1-1-2-2` | `.worktrees/1.1.2.2` | **V386** | **1** |

`plan --lanes 2 --session mvs_6844dd8700a94de5ad8e5c72a4a8a651` ⇒
`newlyPlanned: []`、`serviceBlocked: []`、`toDispatch: [1.3.2.2, 1.1.2.2]`（两条都是 `redispatched: 1`）。
**两个名额被重派占满 ⇒ 本轮没有排任何新模块，也没有浪费任何 Flyway 版本号**（V385/V386 原样保留）。

### 6.1 模块 1.3.2.2 外部渠道推送（identity，迁移 `V385`）

**这个功能做什么**：把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，支持卡片化消息与点击跳转。

**任务书钉死的三个硬缺口**（调度者已取证，要求「做成可查的事实」而不是注释）：

1. **本仓没有 secret manager 服务** ⇒ 凭据建模成「secret manager 引用串 + 一条明确的『secret manager 尚未接通』声明」，
   capabilities 里如实报「不可用 / 归 18 系统负责」，**绝不返回假成功**；**不发明假凭据存储**。
2. **18.5.1.1 模板管理 / 18.5.2.1 投递记录都未交付** ⇒ **投递记录表归本模块自己**（建在 `V385` 里）；
   **消息模板管理不在本模块范围** ⇒ capabilities 如实标 `available=false` + `ownerModule`，**不造模板表顶替**。
3. **外部 IM 通道一个都没接通** ⇒ 对外投递走**端口（Port）抽象**，实现**如实答「未接通」**，
   照抄本仓范式 `UnavailableFinanceSoftwarePushPort` / `UnavailablePlatformEmailPort`；
   投递记录可真实落库，但**投递结果必须如实**。

**上游自检项**：`alert_rule` / `alert_rule_hit`（V384）、通知偏好（1.3.1.2）、通知收件箱（1.3.1.1）——
任何一个 grep 不到就**立即停止回报 `BLOCKED`**。

### 6.2 模块 1.1.2.2 店铺概览（reporting，迁移 `V386`）

**这个功能做什么**：多店铺维度的经营概览（销售额 / 订单量 / 利润率 / 库存天数 / 广告花费）横向对比，识别掉队店铺。

**任务书钉死的口径纪律（调度者本轮逐条复核了 `ReportingStoreComparisonCatalog` 的全部 13 个 `STORE_*` 码）**：

- 店铺级**成交额**是有的，但**分两个口径 + 一个差值**：`STORE_GMV_PLATFORM`（平台侧）/ `STORE_GMV_RESTORED`（我方还原）/ `STORE_CALIBER_GAP`（两者之差）
  ⇒ **两个口径必须并列呈现、差值必须一起给**；只取一个当「销售额」= 抹掉口径差异 = 破坏口径唯一。
- **利润率**、**库存天数** **没有店铺级指标码** ⇒ 如实标 `available=false` + `ownerModule`，**不给公式也不给分母**。
- 🔴 **绝对不许拿租户级 `SALES_AMOUNT` / `GROSS_MARGIN` / `INVENTORY_VALUE` 冒充店铺级**（那是 1.1.2.1 的租户口径）。
- 卷 01 硬约束：**不产生新的业务事实** ⇒ 不新建业务表、不提供写端点、不跨库查表。

**上游自检项**：`ReportingStoreComparisonCatalog` / `ReportingStoreComparisonService` / `V381__store_comparison.sql`、
`ReportingMetricCardCatalog` / `ReportingMetricCardService` —— grep 不到就**立即停止回报 `BLOCKED`**。

### 6.3 本轮写进任务书的新纪律（本轮 L2 实测得出的，两条车道都要遵守）

1. **容器堆积 ⇒ 成片假红**：同一个 maven session 里跑多个测试类时，每个测试类各起一个 PG 容器且不 `.stop()`，
   跑到中段机器上同时有 **12 个**容器、swap 4.2G/5G、pageouts 91 万 ⇒ `Connection refused` ⇒ **503** ⇒ 假红。
   ⇒ worker **只跑自己那一个测试类**，跑完确认容器已被 ryuk 回收，并在交付报告里写明同时存活的容器数。
2. **时间/日期锚的断言必须与星期无关**：本轮抓到 finance 一个测试**每周只有周一是假红灯**（见 5.4），
   ⇒ 凡是拿 `LocalDate.now()` / `OffsetDateTime.now()` 当基准的断言，
   worker 必须自己验算「星期一到星期日七种情况都成立」，否则会在下个边界日给调度者留一个成片假红。

---

## 七、收尾三件套

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ 已写 | `docs/sessions/2026-10/2026-10-26-batch-103-l2-gate-and-two-lane-redispatch.md`（本文件） |
| ② commit | ✅ `本文件所在 commit（`docs(batch-103):`）` | 说明文件与台账**同一个 commit**（`docs(batch-103):` 前缀） |
| ③ push | ✅ **已 push** —— 本文件与说明、台账、以及本轮所有积压提交一起推到 `origin/integration/module-dev`（远端由 `1e3228b4` 推进到本 commit；**只推集成分支，没有碰 `main`**） | |

**本轮 push 的 26 个提交 = 3 轮积压一次清空**（batch-99/100/101 都因 L2 红而未推）：

| 模块 / 修复 | 内容 | 首次推送 |
| --- | --- | --- |
| **`1.1.2.1` 核心指标卡**（reporting） | 工作台指标卡 | `d385fe18` 起的一整组，**首次上远端** |
| **`1.3.2.1` 预警规则**（identity） | 预警规则 + 命中 | `6b8747e9` 起的一整组，**首次上远端** |
| **`1.1.1.2` 异常订单队列** | 停留时长时间锚漂移修复 | `371246c6`，**首次上远端** |
| **channel `credential_ref`** | 授权时间改用数据库 `now()`（消除墙钟/事务时钟分叉假红） | `734ec399`，**首次上远端** |

---

## 八、本批推送出去的模块详细说明

### 模块 1.1.2.1 核心指标卡（reporting · D7）

**这个功能做什么**：打开工作台第一屏就能看到今日/昨日/本月的销售额、订单量、销量、库存总额、毛利、回款、在途金额，
每张卡带环比与同比，点一下跳到对应报表 —— 不再自己写 SQL 拼数字。

**技术落地**：

- **迁移：无**（`V383` 特意留空未占用）—— 卷 01 卷首写死「**不产生新的业务事实，只聚合各系统的投影**」
  ⇒ 派生值落库只会比它描述的经营数字更早过期 ⇒ **不新建任何表**；reporting 服务本车道只有既有的 V376/V379/V381。
- **端点**：`GET /reporting/metric-cards`（指标卡列表）+ `GET /reporting/metric-cards/capabilities`
  （逐个指标报上游 / 口径归属 / 分子分母 / 来源表 / 下钻目标是否可用 + 未交付清单 3 条 + 口径纪律）。
- **页面**：`/dashboard/metric-cards`（`app/dashboard/metric-cards/page.tsx` +
  `components/dashboard/metric-card-model.ts` + `metric-card-workspace.tsx`）——
  十进制精确展示（BigInt 缩放 + HALF_UP，`1.005 ⇒ 1.01`，浮点会算成 `1.00`）、
  无同期基期时不给假环比、多币种分列、未接通维度带归属说明。
- **测试**：`ReportingMetricCardIntegrationTest` + `ReportingMetricCardTenancyIntegrationTest`
  （多租户三断言），前端 `metric-card-model.test.ts` 400 行 + `metric-cards-allowlist.test.ts` 208 行。
- **关键设计决策**：🔴 **口径唯一铁律** —— 数值只能来自 `reporting_metric_definition` /
  `reporting_metric_fact`，**SQL 与 Java 里都不许出现硬编码公式**；
  上游没接通的指标一律 `available=false` + `ownerModule` + 「未接通」声明，**绝不用租户级口径冒充店铺级**。

### 模块 1.3.2.1 预警规则（identity · D7）

**这个功能做什么**：给关键指标配预警规则（阈值、时间窗、对比口径、对象范围），
命中后产出命中记录并驱动通知，运营在 `/notifications/rules` 上就能管。

**技术落地**：

- **迁移**：`identity V384__alert_rule.sql`（两张表，都归本车道）：
  `alert_rule`（`tenant_id` NOT NULL + `rule_code` 受控 CHECK + 对象类型/指标码/时间窗/对比方式/变化基准/对象范围
  **全部受控词表 CHECK** + `threshold` **NUMERIC(19,6)**（禁浮点）且 `CHECK threshold >= 0`
  —— 负阈值会让「低于阈值」恒真，规则一建就疯狂误报 + IANA 时区形状 CHECK + 状态 ENABLED/DISABLED）、
  `alert_rule_hit`（命中留痕）。RLS + 索引 + GRANT 一并落在同一个 `V384` 文件里。
- **端点**：`/alert-rules`、`/alerts` 的 GET/POST/PUT + `POST /alerts/evaluate`、
  `POST /alerts/evaluate-all`。
- **页面**：`/notifications/rules`（`app/notifications/rules/page.tsx` +
  `components/notifications/alert-rule-model.ts` + `alert-rule-workspace.tsx`）+ api-proxy 白名单
  **9 个形状放行 / 刻意不放行 404** 的双向断言（`alert-rule-allowlist.test.ts`）。
- **测试**：`AlertRuleIntegrationTest` + `AlertRuleTenancyIntegrationTest` + `AlertRuleTestSupport`。
- **关键设计决策**：**规则引擎本体在 18 系统**，本模块只做配置与命中，
  未接通的部分用 `engineAvailable=false` + `engineOwnerModule` + `engineNote` 如实声明，**不返回假成功**。

### 模块 1.1.1.2 异常订单队列（trade · D7，本轮推送的是修复）

**这个功能做什么**：工作台把超时未发货、地址异常、支付未回传等异常订单聚成队列，客服从队列直接进单处理。

**技术落地**：本轮推送的是**时间锚漂移修复**（`371246c6`）——
服务端算停留时长取的是**查询那一刻的 JVM 墙钟**，而测试把基准固定在**类加载那一刻**
⇒ 机器一慢，停留时长就多出几百秒，越过 ±120 秒容差 ⇒ 断言翻车。
**本模块业务代码一行没改** ⇒ 这是潜伏缺陷被慢机器暴露，**不是回归**。
修法是**在服务端判定所用的那个钟域里比**：进队列阈值用数据库时钟，停留时长用 JVM 墙钟减播种时刻，
两个钟域各管一段，**不能只把基准换成 `dbNow()`**（那会把起点挪进数据库钟域、终点留在 JVM 钟域）。

### 修复：channel `credential_ref` 授权时间（`734ec399`）

**这个功能做什么**（修的不是功能，是**上一轮 L2 假红的根因**）：
`credential_ref` 的 CHECK 约束是 `CHECK(authorized_at <= now() + interval '5 minutes')`
（**全仓唯一**一条拿 `now()` 做窗口判定的 CHECK），
而测试用 Java 墙钟 `OffsetDateTime.now()` 写 `authorized_at`
⇒ 机器墙钟在测试运行中向前跳了约 15 分钟（同一时刻 HikariCP 报
`Thread starvation or clock leap detected (housekeeper delta=15m41s398ms)`）⇒ 约束违约 ⇒ **假红**。

**关键设计决策**：把两处 `credential_ref` 播种改成**数据库的 `now()`**，从根上消除「墙钟 vs 事务时钟」分叉。
全仓扫描确认这是**唯一**一条 `CHECK(... now() ...)` 约束。

---

## 九、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过） |
| 每批车道数 | **2**（`LANES` 4 减半） |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时**（12 批/天） |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70**（含「L2 返工」×0.90、「BLOCKED / 重试」×0.96、「上游未就绪」×0.95、「长尾占位」×0.90） |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余可自动排完量 | **57** 个未开发 − 3 个循环依赖 ⇒ **54 个待排** ⇒ 54 ÷ 17 ≈ **3.2 天**（区间 **3–5 天**） |

⚠️ 本轮净产出 **0 个新交付 + 2 条重派车道**，但**一次性清空了 3 轮的推送积压（26 个提交）** ——
积压期间远端是**缺 2 个已交付模块**的（`1.1.2.1` / `1.3.2.1` 只在本地）。

---

## 十、风险提示

1. 🔴 **worker / tick 会话崩溃仍是第一号风险** —— 已连续多轮吃掉整批成果
   （batch-92/93/94 `ERR_CONNECTION_CLOSED`、batch-97 `ERR_HTTP2_PING_FAILED`、本轮上一轮 r103 崩在派发前）。
   **每崩在 commit 前一次，下一轮的并入通道就堵一次**（batch-100 → 101 已复发两次）。
2. 🔴 **L2 的驱动方式必须改（一服务一 session）** —— 本轮实测：单 reactor 一遍 1h42m，
   容器只增不减（跑到中段 12 个、swap 4.2G/5G、pageouts 91 万）⇒ **成片假红**，
   且**归因成本极高**（本轮为定性 4 处红做了 2 次隔离复跑 + 5 个模块补跑）。
   ⇒ 下一轮起，L2 后端段改用 batch-97/98/99 验证过的「**一服务一 maven session**」拆法
   （每个 session 结束 ryuk 回收本 session 的容器 ⇒ 峰值 = 单服务峰值），
   **不要再用单 reactor 一把梭**。附带好处：某个服务红了**不会**中止整个 reactor，
   不用再「补跑没跑到的模块」这一轮。
3. 🔴 **孤儿 keepalive 守护进程是 P0 级死锁源** —— 登记在
   `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。
   本轮**没有起常驻守护**（只用协议规定的 `touch` 新文件续期），但根治方案仍未做：
   ① `lane.mjs` 闸龄判据改成「**最新 keepalive 文件**的 mtime」，且 `gate release` 只删自己持有的闸；
   ② keepalive 守护带「持有者 PID 存活检测」，持有者一死就自杀。
4. 🟡 **`plan` 与「真正派发」之间的空窗** —— 本轮第一次用三处证据确认了它的存在
   （`sessionId` 为空 + 心跳 == `createdAt` + 零提交）。
   **`plan` 成功不等于车道开工** ⇒ 下一轮务必先看这三样，再决定要不要重派。
5. 🟡 **5.1 的 3 个循环依赖模块**（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`）仍需人工拍板拆环 ——
   `plan` 排不出它们，每轮都会白占一个名额。**15.3 的 3 个**（`15.3.1.1` / `15.3.1.2` / `15.3.1.3`）同为未交付。
6. 🟡 **类加载期固定基准这个模式全仓仍在**（`static final OffsetDateTime NOW = OffsetDateTime.now(...)`）——
   batch-100 修了 trade 的两个类，本轮修了 channel 的 `credential_ref`，
   但**全仓没有系统排查过** ⇒ 同类缺陷仍可能复发。
7. 🟡 **18.5.1.1 模板管理 / 18.5.2.1 投递记录 / 18.3 规则引擎均未交付** ——
   本轮重派的 `1.3.2.2 外部渠道推送` 正面撞上这三个缺口，任务书已把纪律钉进去（见第六节）。

---

## 十一、文件清单

**本轮调度者写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-26-batch-103-l2-gate-and-two-lane-redispatch.md`（本文件，新增）

**本轮的运维脚本与日志**（均在 `.dev-agent/`，gitignored）：

- `.dev-agent/l2-run-round104.sh`（**单 reactor** L2 驱动：backend `mvnw verify` + 前端四项 + `sync --check`）
- `.dev-agent/wait-l2-r104.sh`（L2 完成等待器）
- `.dev-agent/taskbooks/r104-1.3.2.2.md`、`.dev-agent/taskbooks/r104-1.1.2.2.md`（本轮两条车道的任务书）
- `.dev-agent/l2/l2-r104-*.log`、`.dev-agent/l2/fe-r104-*.log`、`.dev-agent/l2/l2-r104-driver.log`
