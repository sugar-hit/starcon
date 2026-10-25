# 2026-10-25 · 流水线零派发只读轮 —— 调度闸被同行活 tick 持有（它在跑分服务 L2）

> 调度者：Mavis（pipeline cron tick，2026-10-25 **18:00** 触发）。
> 集成头 `integration/module-dev` @ `d0270403`。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
>
> 本轮与上一轮（`d0270403`）同类：**闸被同行活 tick 持有 ⇒ 零派发、零并入、零 L2、零 push**。
> 但**观测面比上一轮更具体** —— 同行这轮已经取闸、派了 2 条车道、正在跑分服务 L2，
> 本文件记录的是**该现场的实测快照**，供下一轮 tick 与人工接手时用。

## 一、进度（`sync.mjs` / `lane.mjs status` 实测）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **404** |
| 进行中 | **0** |
| 未开发 | **61**（完成率 **86.9%**，离 465 还差 **61**） |
| 阶段分布 | D1–D2 80/0/0 · D3 40/0/0 · D4 60/0/0 · D5 52/0/0 · D6 75/0/**3** · D7 97/0/**58** |
| 在途车道 | **2**：`13.1.1.2 多店对比`（reporting, V381）· `18.2.1.1 财务软件`（platform, V382） |

台账 `module-progress.md` 的 402→404 增量来自同行本轮的两条并入
（`8fd35cd5` 13.4.2.2 费用差异 / `4b9a48a1` 18.4.2.1 文本与翻译），
**该文件是同行的在途产物，本轮不碰**（留给同行在其 L2 转绿后用 `docs(batch-N):` 与说明一并带走）。

## 二、调度闸：被活 tick 持有 ⇒ 本轮零派发

| 观测 | 实测 |
| --- | --- |
| `lane.mjs gate acquire` | 退出码 **3**，`{"ok": false, "gate": "held-by-other"}` |
| 闸龄 / TTL | `ageSeconds` 1400·s / `ttlSeconds` 4200·s ⇒ **`stale: false`**（陈旧闸才可抢，本轮不是） |
| 闸持有者 | owner PID 57898，取闸时刻 `2026-10-25T08:39:52.861Z`（= 16:39:52 本地） |
| 闸目录续期痕迹 | `keepalive-170830-r17` / `-171350-r18` / `-173712-r19` ⇒ **17:37 仍在续期** |
| 持闸会话 | pipeline tick `mvs_4f64ebf1e19a4e6c8baf931fa2444c36`（16:02 触发）状态 **`started`**（非 error） |
| 同行工作痕迹 | `.tmp/l2-phaseB.sh`（17:59 新建）· `.tmp/l2-svc/{reporting,channel,warehouse}.log`（18:03 仍在写）· `lanes.json`（17:55 有心跳） |

⇒ **持闸者是活 tick，不是残留闸**。依协议「撞闸（退出码 3）→ 零派发 + 只读检查 + 简短汇报就结束」，
本轮**未清闸、未 force、未取闸**。

⚠️ **owner PID 57898 当前查无此进程，但闸龄未过 TTL ⇒ 属「PID 记录不可靠、闸本身有效」**。
判闸的依据是**闸目录 mtime + 会话存活 + 后台进程**，不是 owner 文件里的 PID。

## 三、容量闸判定（温度 > 配额 > PG > load）

| 闸 | 实测 | 判定 |
| --- | --- | --- |
| **温度闸** | `pmset -g therm` → `No thermal warning level has been recorded` / `No performance warning level` / `No CPU power status` ⇒ **无任何 Speed_Limit 项** | ✅ **PASS** |
| **配额闸** | 在途车道 **2** / 上限 **6** | ✅ PASS |
| **PG 闸** | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 **93 个** / 上限 **6** | 🔴 **超限 15 倍** |
| **load 闸** | `7.74 / 7.12 / 6.38`（1/5/15 分钟）/ 上限 **12** | ✅ PASS（**较 16:00 的 2.10/2.78/2.54 明显抬升**，与 L2 并行跑两个服务同步） |
| **节流闸** | 距 `last-batch-at`（12:00:22）**约 360 分钟** ≥ 降档阈值 **100 分钟** | ✅ 本可放行 |

**PG 闸超限的定性（重要，不误判）**：93 个容器**不是泄漏孤儿**（全部带 sessionId 标签，
且与同行正在跑的 L2 阶段 A 的 ryuk session 一致），而是**同一次 L2 运行期间、
本仓 ≈238 个不 `.stop()` 的测试类各自建出的 PG 容器在单服务 fork JVM 生命周期内累积**
（详见 `batch-97` 第三节的量化表：channel 113 / finance 64 / crm 34 / platform 4 个不 stop）。
同行已用「**分服务独立 maven session**」把峰值从「全 reactor 累积」压到「单服务峰值」
（实测 `reporting` 单服务跑完后 `PG_LIVE_AFTER 0`，ryuk 完整回收）。

⇒ **本轮对容器不做任何干预。** 上一 tick 的「清道夫」正是因判据分不清
「泄漏容器」与「共享 static 容器」而**误杀**共享容器，直接制造了 r3 的假红
（`PendingInspectionTenancyIntegrationTest` 3 例 ERROR）。**清道夫已被同行杀掉，不再复活。**

## 四、本轮动作清单

| 动作 | 结果 |
| --- | --- |
| 同步台账 `sync.mjs` | ✅ 18 卷 / 465 模块；404 已交付 · 0 进行中 · 61 未开发 |
| 取调度闸 `gate acquire` | 🔴 退出码 3（held-by-other） |
| 回收车道 `merge-ready` | ⏭️ 未执行（撞闸轮次禁止） |
| 排车道 `plan` | ⏭️ 未执行（撞闸轮次禁止，且 `plan` 有副作用） |
| 派发 worker | ⏭️ **零派发** |
| 静态预检（互相破坏形状扫描） | ⏭️ 本批无新迁移入集成头，无扫描对象 |
| L2 批量验证 | ⏭️ **未由本轮触发** —— 同行正在跑（见下） |
| 写说明 | ✅ 本文件 |
| commit | ✅ 仅本文件（台账与 batch-96/97 是同行在途产物，**故意不碰**） |
| push | ❌ **未 push** —— **L2 未绿**（同行 L2 仍在阶段 A），本地 9 个待推 commit 属同行批次 |

## 五、同行 L2 的实时观测（只读，本轮未干预）

同行把 L2 拆成阶段 A / 阶段 B，阶段 A 跑在独立 worktree
`/Users/guan/Projects/starcon-worktrees/l2-verify`（detached @ `d0270403`，与并入动作隔离）。

| 服务 | 状态 | 实测 |
| --- | --- | --- |
| reporting | ✅ **已完成** | `Tests run: 32, Failures: 0, Errors: 0` · `PG_LIVE_AFTER 0`（ryuk 完整回收） |
| channel | ⏳ 在跑（17:14 起） | 18:03 仍在推进，`AdCampaignReport` / `BuyerBlacklistTenancy` / `LogisticsTransit` 等逐类绿 |
| warehouse | ⏳ 在跑（17:14 起） | `PickWaveIntegrationTest` 30 例绿；日志里的 `Connection refused` 栈是**测试自身构造的失败场景**（随后的 `Tests run: 30, Failures: 0, Errors: 0` 为准），不是用例失败 |
| trade / crm / finance / catalog | ⏳ 未开始（阶段 A 队列内） | — |

## 六、风险提示（继承自同行取证，本轮未新增）

1. 🔴 **worker 会话崩溃仍是第一号风险** —— `net::ERR_HTTP2_PING_FAILED` 已连续三轮吃掉整批成果
   （batch-92/93/94 的 `ERR_CONNECTION_CLOSED` 同族）；上一 tick `mvs_f130e6a1…` 即死于它。
   已在途的 2 条车道心跳（17:55）尚健康，但成果仍未提交 —— **下一轮必须先查这两条车道是否又崩**。
2. 🔴 **容器泄漏未根治** —— 根治方案（给不 `.stop()` 的类补 `@AfterAll`）在
   `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`，标注「待人工决策，调度器不自作主张」⇒ **需要人工拍板**。
3. 🔴 **9 个 commit 未推送** —— 全部属同行 batch-97 的批次；**L2 未绿前不得 push**。
4. 🟡 **4 个循环依赖模块**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`）仍需人工拍板拆环。
5. 🟡 **调度闸 TTL（70 分钟）远小于一轮实际时长**（同行本轮已跑超 2 小时）⇒ 全程靠 `touch 新文件名` 续期维持。

## 七、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON` 已过） |
| 每批车道数 | **2** |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时** |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70** |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余量 | 61 个未开发 − 4 个循环依赖 ⇒ **57 个待排** ⇒ 57 ÷ 17 ≈ **3.4 天**（区间 **3–5 天**） |

## 八、文件清单

**本轮调度器写入集成头的文件（仅 1 个）**：

- `docs/sessions/2026-10/2026-10-25-pipeline-zero-dispatch-gate-held-live-peer-l2.md`（本文件，新增）

**本轮刻意未碰的同行在途产物**：

- `docs/sessions/2026-10/2026-10-25-batch-96-two-lane-delivery-and-clock-domain-fix.md`（未跟踪）
- `docs/sessions/2026-10/2026-10-25-batch-97-container-leak-l2-split-and-lane-recovery.md`（未跟踪，**含 `<!--L2_RESULTS-->` / `<!--CLOSEOUT-->` 占位符，尚未写完**）
- `docs/product/capability-blueprint/module-progress.md`（`M`，402→404 的在途增量）

⇒ 这三份文件由**持闸的同行 tick** 在其 L2 转绿后用 `docs(batch-97):` **说明 + 台账同 commit** 带走。
本轮若代为提交，会把**未填完的占位符文档**和**同行的台账行**提前钉进历史，与同行计划冲突 ⇒ **不做**。
