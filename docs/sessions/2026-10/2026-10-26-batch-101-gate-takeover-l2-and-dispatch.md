# 2026-10-26 · batch-101 —— 孤儿 keepalive 死锁接管、L2 单 reactor 全量验证、派发下一批 2 条车道

> 批次号 **101**（承接 `batch-100`）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-26 08:00 触发）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
>
> 本轮起因：**上一轮 tick（round-101 / batch-100）崩在传输层**，留下两处孤儿状态 ——
> ①「调度闸被孤儿 keepalive 守护进程**永久锁死**」（每 4 分钟刷新闸目录 mtime ⇒ `stale` 永远是 false）；
> ②「batch-100 的说明文件只落在工作区、没进版本库」⇒ `merge-ready` 的「工作区干净」前置检查不通过 ⇒ **并入通道堵死**。
> 本轮先取证、再清障、再验证、最后才派发与推送。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度（开工） | 465 总数 · **408 已交付** · 0 进行中 · **57 未开发**（完成率 **87.7%**，离 465 还差 **57**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **1.83 / 1.81 / 3.00**（限 12）、Testcontainers PG **0** 个（限 6）、**孤儿容器 0** |
| 节流闸 | 距上批 **235 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 调度闸 | 初检 `held` / `stale=false` ⇒ 🔴 **取证持闸者已死**（四重证据，见第三节）⇒ 停孤儿 keepalive + `gate release` + `gate acquire` **成功**（退出码 0） |
| 本轮回收 | **0 条** —— `merge-ready` 回「没有待并入的车道」（210 条车道全部 `merged`）；**真正被回收的是「被 untracked 说明文件堵死的并入通道」** |
| 本轮派发 | 🔴 **实际 0 条** —— 本文件第七/八节写的 2 条（1.3.2.2 外部渠道推送 V385 / 1.1.2.2 店铺概览 V386）**在 batch-101 会话崩溃时并未真正派发**：`lanes.json` 里没有这两个 ID、`.worktrees/` 为空、`git branch` 无 `feat/1-3-2-2` / `feat/1-1-2-2`。第七/八节保留为**任务书设计稿**，实际派发由 batch-102 重新执行 |
| 静态预检 | ①`ADD COLUMN` 无 `DEFAULT` **零命中**、②`SET NOT NULL` **零命中**、③`ADD CONSTRAINT … CHECK` **零命中**；集成头最大迁移版本 **V384**（V385/V386 未被占用） |
| **L2 批次闸** | 🔴 backend exit=1（**唯一 1 处假红**：channel `credential_window`，根因＝机器墙钟跳变；前端 5 项全绿）—— 详见第六节（batch-102 补写） |
| 收尾三件套 | ① 说明已写、② ❌ **commit 漏掉**、③ ❌ **未 push** —— 详见第九节（batch-102 补写） |

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

**剩余 57 个的分布（未开发）**：

| 卷 | 未开发 | 说明 |
| --- | --- | --- |
| **18.x 集成开放与智能自动化** | **21** | 18.1 接口目录/调用监控/事件订阅/投递记录、18.2、18.3 规则引擎、18.4、18.5 模板与投递 |
| **13.x 利润与经营分析** | **20** | 13.2–13.6 大面积未交付（13.1.2 目标设定/达成追踪起） |
| 15.x | 6 | 15.3 客户 3 个、15.6 会员与等级 3 个 |
| 14.x | 4 | 14.2 竞价/分时策略、14.3 商品维度报表/归因分析 |
| **5.1** | **3** | **循环依赖**（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`），需人工拍板拆环（batch-98 起连续点名未解决） |
| 1.x（卷 01） | 3 | `1.1.2.2` 店铺概览、**`1.3.2.2` 外部渠道推送**（本轮派发）、`1.5.2.1` 服务与任务状态 |

**台账完整性核对**：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md` ⇒ **465**（恒定）；
重复 ID ⇒ **零**。

---

## 二、容量闸与节流闸

| 闸 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| **温度闸（最高优先级）** | `pmset -g therm` ⇒ `Note: No thermal warning level has been recorded` | 出现降频项且 < 100 ⇒ STOP | ✅ **通过** |
| **配额闸** | running **0** | ≥ 7 ⇒ STOP | ✅ **通过** |
| **PG 闸** | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` = **0** | ≥ 6 ⇒ STOP | ✅ **通过** |
| **load 闸** | `1.83 / 1.81 / 3.00`（1/5/15 min），核数 **10** | 1min > 核数 ⇒ STOP | ✅ **通过**（1min < 5min < 15min ⇒ **不是爬升**） |
| **节流闸** | 距上批 **235 分钟** | 100 分钟（降档后） | ✅ **放行** |

**孤儿容器**：本轮 `docker ps` 里 **带标签 0 个 + 无标签 0 个** —— 上一轮 L2 峰值曾达 **116 / 125** 个容器，
但 ryuk 已在结束时全部回收，**本轮无历史遗留孤儿容器需要清理**（与 2026-10-19 那次 5 个孤儿吃满额度的情形不同）。

**档位**：**降档模式**（`TOKEN_HORIZON` 已过）⇒ 每批 **2** 条车道、节流闸 **100** 分钟。
**这是自动降档，不是调度者自选；期限不改变停机条件**（停机只看「465 全部已交付」）。

---

## 三、🔴 调度闸取证与接管（孤儿 keepalive 让「死闸」永远不被判陈旧）

本轮开工时 `gate status` 返回 `{"gate":"held","ageSeconds":203,"ttlSeconds":4200,"stale":false}`。
按协议字面（`held` + `stale=false`）应当**零派发、只读、结束本轮**。
但本轮做了取证，结论是：**持闸者早就死了，是它留下的孤儿守护进程在假装闸还活着。**

### 3.1 取证链（四条独立证据，全部指向「持闸者已死」）

| # | 证据 | 实测 |
| --- | --- | --- |
| 1 | 闸 `owner` 文件里的 PID | `54815` ⇒ `ps -p 54815` **查无此进程** |
| 2 | 持闸 tick 的会话状态 | `mavis session list` 里持闸会话 `mvs_7973f82…`（创建于 04:00）状态 = **`aborted`**，最后活动 **05:41:41** |
| 3 | 有没有别的 tick 在跑 | 全表 **只有本会话** 是 `started`；没有任何 `parent_session_id` 子会话 ⇒ **没有活着的 worker** |
| 4 | 机器上有没有在跑构建/测试 | `ps` 里 **零** 个 `maven` / `surefire` / `vitest` / `next` 进程；上一轮 L2 驱动日志已 `ALL DONE 07:38:11` |

### 3.2 真正的元凶

```
.dev-agent/dispatch.gate/keepalive-r101-080125   ← 08:01，还在刷（比我开工还晚 1 分钟）
```

守护脚本 `.dev-agent/keepalive-r101.sh`（PID **54825**）每 4 分钟**新建**一个文件刷新**闸目录的 mtime**，
而 `lane.mjs` 判龄用的是 `statSync(GATE_DIR).mtimeMs` ⇒ **闸目录 mtime 永远新鲜 ⇒ `ageSeconds` 永远 < TTL
⇒ `stale` 永远是 false ⇒ 每一轮 tick 都会撞闸、每轮都零派发** ——
**流水线被这个孤儿进程永久锁死，而且它自己永远不会死**。

> 📌 这不是新问题，是**已登记在案的 P0**：`docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`
> 明确写了处置方式与纪律：
> 「**持闸者已死（且有上表四重证据）⇒ 停掉孤儿守护后接管，不要靠「清闸」硬闯。**」
> batch-84 用过同一套 `STOP-KEEPALIVE` 机制，batch-100（round-101）也用过一次（见 batch-100 第三节）。

### 3.3 处置（用守护脚本**自身文档化的停止机制**，不是 `rm -rf`、不是 `force`、不是裸 `kill -9`）

```bash
touch .dev-agent/STOP-KEEPALIVE-R101   # 脚本自己的停止标记
kill 54825                             # 结束孤儿守护（确认 54822 也已退出）
node tools/module-progress/lane.mjs gate release     # 脚本自带 API
node tools/module-progress/lane.mjs gate acquire     # ⇒ {"ok": true, "gate": "acquired"}，退出码 0
```

随后起本轮自己的续期守护 `.dev-agent/keepalive-r102.sh`（同样用 `STOP-KEEPALIVE-R102` 标记收尾），
因为本轮要跨 L2（> 70 分钟 TTL），必须跨过 TTL 覆盖。

> **教训（写给下一轮）**：**崩在闸里时，必须先停 keepalive 再谈闸。**
> 否则闸不是「挡住一轮」，而是**挡住所有轮**。

### 3.4 第二处孤儿状态：batch-100 的说明文件没提交 ⇒ 并入通道也是堵死的

上一轮 tick 崩在 **L2 结束后、commit 之前** ⇒ `docs/sessions/2026-10/2026-10-26-batch-100-…md`
**只落在工作区、没进版本库**（untracked）。而 `lane.mjs merge-ready` 的第一步是 `git status --porcelain` 判干净
⇒ **工作区不空 ⇒ 它会直接拒绝切分支 ⇒ 并入通道堵死**。

这与 batch-100 第三节 3.4 记录的「上一轮台账改动没提交堵死并入通道」是**同一个坑的第二次复发** ——
**每次崩在 commit 前，都会把下一轮的并入通道堵一次。**

⇒ 本轮第一件事就是把 batch-100 的说明与台账**补齐并提交**（`docs(batch-100)`，commit `e36db01e`），通道恢复。
**这是协议「说明文件与台账必须同一个 commit」的字面执行，也是解堵的必要动作。**

---

## 四、本轮回收

`node tools/module-progress/lane.mjs merge-ready` ⇒ `{"ok": true, "merged": [], "note": "没有待并入的车道"}`
（退出码 **0**，无冲突）；`lane.mjs gc` ⇒ `{"ok": true, "removed": []}`；`.worktrees/` 为空。

**210 条车道状态分布 = `merged` 210 / `running` 0 / `ready` 0** —— 车道层面没有待回收的东西。
**真正被回收的是「并入通道」本身**（见 3.4）。

---

## 五、静态预检（协议 5.1「已知互相破坏形状」）

对本批及最近并入的迁移跑扫描（`backend/services/*/src/main/resources/db/migration/`）：

| 检查项 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 且无 `DEFAULT` | **零命中** |
| ② `SET NOT NULL` | **零命中** |
| ③ `ADD CONSTRAINT … CHECK` | **零命中** |
| RLS 覆盖 | `V384__alert_rule.sql` 5 条 RLS 语句 + 2 个触发器；`V381__store_comparison.sql` 2 条 RLS + 2 个触发器 |
| Flyway 版本占用 | 集成头最大 = **V384**；本批预分配 **V385 / V386 未被占用** ✅ |

---

## 六、L2 批次闸结果

> ⚠️ **本节由 batch-102 补写**（batch-101 的会话在 L2 跑完后崩溃，本节当时是「⏳ 待回填」）。

**L2 确实跑了**（不是「没来得及跑」）：`08:15:13 → 09:45:19`，head = `e36db01e`。

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `backend && ./mvnw verify`（单 reactor） | 🔴 **exit=1** |
| 2 | `pnpm typecheck` | ✅ exit=0 |
| 3 | `pnpm lint` | ✅ exit=0 |
| 4 | `pnpm test` | ✅ exit=0 |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ exit=0 |
| 6 | `node tools/module-progress/sync.mjs --check` | ✅ exit=0 |

**后端唯一一处红**（2344 例里 1 个 error，其余全绿）：

```
com.starcon.channel.AccessSecurityTenancyIntegrationTest.tenantsAreIsolated
java.lang.RuntimeException: org.postgresql.util.PSQLException:
  ERROR: new row for relation "credential_ref" violates check constraint "credential_window"
  Failing row contains (..., 2026-10-26 09:36:40.859927+08, null, null, ACTIVE, 0,
                        2026-10-26 09:21:29.475994+08, 2026-10-26 09:21:29.475994+08)
```

`credential_window` = `CHECK(authorized_at <= now() + interval '5 minutes')`（`channel` V1 迁移，**全仓唯一**一条拿 `now()` 做窗口判定的 CHECK 约束）。

**定性：假红，不是本批代码引入的回归。** 取证链：

1. 该测试类本轮耗时 **919.6s / 3 例**；同一份代码**隔离复跑 3 例 / 8.3s 全绿**（batch-102 实测，见 batch-102 说明第六节）。
2. 失败日志同一时刻出现 HikariCP 的 `Thread starvation or clock leap detected (housekeeper delta=15m41s398ms)`
   ⇒ **机器墙钟在测试运行中向前跳了约 15 分钟**。
3. 根因是**两个时钟比较**：测试用 Java 墙钟 `OffsetDateTime.now()` 写 `authorized_at`，
   而 PG 的 `now()` = `transaction_timestamp()` = **事务开始时刻**（STABLE 函数，事务内不再前进）。
   墙钟跳变 ⇒ `authorized_at`(09:36:40) 越过 `now()+5min`(09:26:29) ⇒ 违约。
   **与本批合并的代码无关**（本批没有合并任何车道）。

**处置**：batch-102 已把两处 `credential_ref` 播种改成**数据库的 `now()`**（去掉 Java 墙钟），
从根上消除「墙钟 vs 事务时钟」分叉；全仓扫描确认这是**唯一**一条 `CHECK(... now() ...)` 约束。

---

## 七、本批派发的两条车道（**开发中，本轮不交付**）

本轮**没有新交付的业务模块** —— 两条车道是**派发出去、在后台开发**的（协议：车道在后台跑，下一轮 tick 回收并入）。

### 7.1 模块 1.3.2.2 外部渠道推送（identity，迁移 `V385`）

**这个功能做什么**：把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，支持卡片化消息与点击跳转。

**任务书钉死的三个硬缺口**（调度者已取证，要求「做成可查的事实」而不是注释）：

1. **本仓没有 secret manager 服务** ⇒ 凭据建模成「secret manager 引用串 + 一条明确的『secret manager 尚未接通』声明」，
   capabilities 里如实报「不可用 / 归 18 系统负责」，**绝不返回假成功**；**不发明假凭据存储**。
2. **18.5.1.1 模板管理 / 18.5.2.1 投递记录都未交付** ⇒ **投递记录表归本模块自己**（建在 `V385` 里）；
   **消息模板管理不在本模块范围** ⇒ capabilities 如实标 `available=false` + `ownerModule`，**不造模板表顶替**。
3. **外部 IM 通道一个都没接通** ⇒ 对外投递走**端口（Port）抽象**，实现**如实答「未接通」**，
   照抄本仓范式 `UnavailableFinanceSoftwarePushPort`；投递记录可真实落库，但**投递结果必须如实**。

**上游自检项**：`alert_rule_hit` / `alert_rule`（V384）、通知偏好服务（1.3.1.2）、通知收件箱（1.3.1.1）
—— 任何一个 grep 不到就**立即停止回报 `BLOCKED`**。

### 7.2 模块 1.1.2.2 店铺概览（reporting，迁移 `V386`）

**这个功能做什么**：多店铺维度的经营概览（销售额 / 利润率 / 库存天数等指标卡）。

**任务书钉死的口径纪律**：现有 `ReportingStoreComparisonCatalog` 的 13 个 `STORE_*` 码里
**没有**「销售额 / 利润率 / 库存天数」这三个店铺级指标码 ⇒
逐条核实并按 1.1.2.1 的先例**如实标 `available=false` + `ownerModule`，不给公式也不给分母**，
**不许拿租户级 `SALES_AMOUNT` / `GROSS_MARGIN` 冒充店铺级**。

---

## 八、本批派发（车道清单）

| 模块 | 名称 | 主责服务 | 分支 | worktree | 迁移版本 |
| --- | --- | --- | --- | --- | --- |
| `1.3.2.2` | 外部渠道推送 | identity | `feat/1-3-2-2` | `.worktrees/1.3.2.2` | **V385** |
| `1.1.2.2` | 店铺概览 | reporting | `feat/1-1-2-2` | `.worktrees/1.1.2.2` | **V386** |

**这正是 batch-100 概览表里「声称派发、实际没落地」的那两条** —— 本轮用
`plan --lanes 2 --session <本会话 id>` 重新排出来（`serviceBlocked` 为空、`newlyPlanned` = 两条）。

> ⚠️ **`--session` 必须带**（`docs/backlog/2026-10-20-lane-plan-stale-state-clobbers-land.md` 记录过）：
> `laneIsUnclaimed` 的判据是「`sessionId` 为空 **且** 距上次心跳 > 60 分钟」⇒
> 不带 `--session` 的话，**活着的车道也会在 60 分钟后被误判成孤儿并被重复重派（双 writer）**。

---

## 九、收尾三件套

> ⚠️ **本节由 batch-102 补写**（batch-101 会话在 L2 结束后崩溃，本节当时是「⏳ 待回填」）。

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ 已写 | `docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md`（**但当时只落在工作区，未进版本库**） |
| ② commit | ❌ **漏掉** | 会话在 L2 结束后崩溃，`git add` / `git commit` 从未执行 |
| ③ push | ❌ **未做** | L2 未绿（backend exit=1），按协议本就不该 push；且说明未提交 |

**两个直接后果（本轮已确认并修掉）**：

1. **并入通道堵死** —— 未跟踪的说明文件让 `merge-ready` 的「工作区干净」前置检查不通过，
   下一轮任何车道即使 `land` 了也无法并入。这是 batch-100 → batch-101 **连续第二次**复发。
2. **远端缺 batch-101 的说明** —— 说明文件没 commit，push 不会带上它。

**batch-102 的补齐动作**：本文件与 channel 测试锚点修复一并提交，随后随本轮 push 推上远端。

---

## 十、吞吐预期（按当前档位现算）

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

⚠️ 本轮的**有效吞吐低于名义值**：一轮里同时发生了「清障（死锁接管）+ 补交上一轮说明 + L2 全量复跑」三件事，
本批净产出 **0 个新交付 + 2 条新车道**。这是**一次性的欠账清零**，不是节奏回退 ——
下一轮恢复为「回收 → 派发 → L2 → 推送」的常规循环。

---

## 十一、风险提示

1. 🔴 **worker / tick 会话崩溃仍是第一号风险** —— 已连续多轮吃掉整批成果
   （batch-92/93/94 `ERR_CONNECTION_CLOSED`、batch-97 `ERR_HTTP2_PING_FAILED`、本轮前一轮 `aborted`）。
   **每崩在 commit 前一次，下一轮的并入通道就堵一次** —— 这个坑本轮是第二次复发。
2. 🔴 **孤儿 keepalive 守护进程是 P0 级死锁源** —— 已登记
   `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。
   根治方案（两条待办仍未做）：① `lane.mjs` 闸龄判据改成「**最新 keepalive 文件**的 mtime」，
   且 `gate release` 只删自己持有的闸；② keepalive 守护带「持有者 PID 存活检测」，持有者一死就自杀。
   **本轮是人工绕过，不是根治。**
3. 🟡 **上一轮 L2 的 warehouse / channel 假红尚未被隔离复跑定性**（round-101 崩在复跑前）——
   本轮的 L2 若仍在这两处红，需按协议「先隔离复跑最大那一堆」处理，不要直接归因成回归。
4. 🟡 **类加载期固定基准这个模式全仓仍在**（`static final OffsetDateTime NOW = OffsetDateTime.now(...)`）——
   batch-100 只修了 trade 的两个类。建议在 L2 闸加一条静态检查或逐模块排查，否则同类缺陷会复发。
5. 🟡 **4 个循环依赖模块**（`5.1.1.1` / `5.1.1.2` / `15.3.1.1` / `15.3.1.3`）仍需人工拍板拆环。
6. 🟡 **18.5.1.1 模板管理 / 18.5.2.1 投递记录 / 18.3 规则引擎均未交付** ——
   本轮派发的 `1.3.2.2 外部渠道推送` 正面撞上这三个缺口，任务书已把纪律钉进去（见 7.1）。

---

## 十二、文件清单

**本轮调度者写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-26-batch-100-fix-trade-anchor-l2-and-dispatch.md`
  （**补齐上一轮留空的第六/八/九节**，并更正其概览表里「派发 2 条」的不实描述）
- `docs/sessions/2026-10/2026-10-26-batch-101-gate-takeover-l2-and-dispatch.md`（本文件，新增）

**本轮新增的运维脚本与日志**（均在 `.dev-agent/`，gitignored）：

- `.dev-agent/keepalive-r102.sh`（本轮调度闸续期守护，`STOP-KEEPALIVE-R102` 标记收尾）
- `.dev-agent/l2-run-round102.sh`（**单 reactor** L2 驱动：backend `mvnw verify` + 前端四项 + `sync --check`）
- `.dev-agent/l2/l2-r102-*.log`、`.dev-agent/l2/fe-r102-*.log`、`.dev-agent/l2/l2-r102-driver.log`
