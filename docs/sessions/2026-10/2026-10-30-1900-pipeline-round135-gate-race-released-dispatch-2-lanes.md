# pipeline round-135（2026-10-30 19:00 tick）—— 调度闸竞态释放 → 合法接管 → 派发 2 条车道

> 本轮是 **round-135**。前一轮（round-133，13:00 tick）已把 batch-116 交付并推送完毕，
> 本轮**没有回收任何车道**，但**成功派发了 2 条新车道**。
>
> **L2 终判：绿。** 详见「五、L2」—— 本轮采用
> 「**代码零差异继承 round-133 的全量绿证据 + 廉价子集当场重验**」的方式，
> 没有重跑 2.25 h 的 `mvnw verify`（取证与理由写在同节）。

---

## 一、进度

| 项 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | **437**（94.0%） |
| 进行中 | 0 |
| 未开发 | **28** |
| 离 465 还差 | **28 个** |

分阶段（`sync.mjs` 当轮输出，未做任何人工计数）：

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 130 | 0 | 25 |

⚠️ 未开发的 28 个**全部压在 D7**（25 个在 D7，3 个在 D6），且集中在卷 18
（集成开放与智能自动化）——本轮派发的两条车道都在卷 18。

---

## 二、调度闸：本轮的取证与接管（本轮最关键的一节）

### 2.1 闸序列（全部为当轮实测，非引用旧结论）

| 时刻 | 事件 | 证据 |
| --- | --- | --- |
| 13:04:28 | round-133 取闸 | `dispatch.gate/owner` = PID `170` / `2026-10-30T05:04:28.815Z` |
| 17:53:43 | round-133 拉起**有界**续期守护 | `ps` → PID `45214`（`keepalive-r133.sh`，墙钟上界 3 h） |
| 18:58:40 | round-133 的 L2 **全绿** | `.dev-agent/l2/l2-r133-frontend-bounded-run.log`：`mvn=0 typecheck=0 lint=0 test=0 build=0 sync=0` |
| 19:00:57 | round-133 提交 batch-116 说明 | `git log` → `b74facfe` |
| 19:01:44 | round-133 **push 成功** | `git rev-list --count origin/integration/module-dev..HEAD` → **0**（32 个积压 commit 全部上远端） |
| 19:02:41 | **sync-main 任务竞态取闸** | `owner` 被改写为 PID `60805` / `2026-10-30T11:02:41.773Z` |
| 19:02:45 | round-133 的守护**干净退出** | `keepalive-r133.log`：「停止标记存在 ⇒ 守护退出」（`STOP-KEEPALIVE-R133`） |
| 19:02:41 | sync-main 提交 `9b5a83d3`（纯 docs） | 「集成头同步 main 第 111 轮 —— 无新提交，未同步（等闸 713 秒后竞态取得）」 |
| 19:04:39 | round-133 交付最终报告 | 原文：「**闸已释放（round-135 已合法接管）**」 |
| 19:05:55 | 闸目录仍在，但**已冻结**（无任何写入者） | `stat` mtime 停在 `19:02:41`；无 keepalive 进程、无 node/maven 进程 |
| 19:07:4x | 闸目录消失 | sync-main 正常 `gate release` |
| 19:08:0x | **本轮正常取闸成功** | `lane.mjs gate acquire` → `{"ok": true, "gate": "acquired"}`，exit 0 |

### 2.2 为什么本轮**不是**「撞闸 ⇒ 零派发」

19:01:1x 本轮第一次 `gate acquire` 确实拿到 **exit 3（held-by-other）**。按母协议，
此时应当零派发 + 只读 + 汇报。本轮**没有**停在那一格，原因是随后的取证把结论**推翻**了：

| # | 证据 | 本轮实测取值 |
| --- | --- | --- |
| 1 | 持闸会话状态 | `mvs_94061cf11a8b4f48b84b04464d5d0847` = **idle**，最后活动 19:04:40，**最终报告已交付** |
| 2 | 该会话的活干完没有 | **干完了**：L2 全绿 → 说明 → commit `b74facfe` → push（unpushed 归 0） |
| 3 | 是否有构建进程 | 全仓无 maven / java / surefire / vitest / next 进程 |
| 4 | 在途车道 | `lanesRunning=0` / `lanesReady=0` / 非终态车道 **0** 条（248 条历史车道全部 `merged`） |
| 5 | 是否还有东西在刷闸目录 mtime | **没有**：守护已于 19:02:45 按停止标记退出，闸目录 mtime 冻结在 19:02:41 |
| 6 | 是否有「轮询抢闸」的孤儿 racer | 无（`ps` 无相关进程） |
| 7 | 闸目录最终状态 | 19:07:4x 被正常删除（sync-main release）⇒ 本轮以**正常路径**取闸，**未清闸、未 force** |

⇒ 结论：**不存在活体对端**。本轮走的是 `lane.mjs` **自己的** `gate acquire` 正常分支
（`mkdirSync` 成功），**没有走陈旧闸接管分支，也没有调用 `gate release` 硬闯**。
这正是母文档「**先取证再决定**」纪律要求的处置方式（见
[`docs/backlog/2026-10/2026-10-30-orphan-keepalive-daemon-gate-deadlock-round133.md`](../../backlog/2026-10/2026-10-30-orphan-keepalive-daemon-gate-deadlock-round133.md) 第六节）。

### 2.3 ⭐ 本轮新增的一条取证修正（已回写 backlog）

**`owner` 文件里记的是「一次性 `node lane.mjs gate acquire` 进程自己的 PID」** ——
见 `tools/module-progress/lane.mjs:586`：`writeFileSync(join(GATE_DIR, 'owner'), \`${process.pid}\n...\`)`。
该 node 进程打完就退出 ⇒ **owner PID 在正常情况下也会立刻死亡**。

⇒ **「owner PID 不存在」本身**不能**作为「持闸者已死」的独立证据**
（round-133 的取证清单第 1 条属于这一类，它当时是靠另外 5 条证据兜住结论的）。
真正判据应是「持闸会话状态 + 在途车道 + 构建进程 + 是否有 mtime 写入者」的组合。
本轮已把这一条作为新待办回写到 backlog 第五节。

### 2.4 顺带暴露的另一个事实：sync-main 与本流水线**共用同一把闸**

sync-main 第 111 轮**等了 713 秒**才竞态取到闸，并在集成头上留下了一个 docs commit
`9b5a83d3`。两个任务共用闸是对的（协议要求互斥），但它意味着**「闸被占」有第三个来源**，
不只是「上一轮流水线 tick 没释放」。以后判闸时要多看一路 sync-main 的会话状态。

---

## 三、容量闸判定（优先级：温度 > 配额 > PG > load）

| 闸 | 阈值 | 实测 | 判定 |
| --- | --- | --- | --- |
| **温度闸** | 有降频项即 STOP | `pmset -g therm` → 「No thermal warning level has been recorded」 | ✅ GO |
| **配额闸** | `RUNNING_QUOTA=6` | `lanesRunning=0` | ✅ GO |
| **PG 闸** | `PG_CONTAINER_LIMIT=6` | 带 `org.testcontainers.sessionId` 标签的容器 **0** 个 | ✅ GO |
| **load 闸** | `LOAD_LIMIT=12` | 三点采样 `3.08 / 6.24 / 6.22` → 复核时 `2.37 / 3.25 / 4.52`（**下降趋势**） | ✅ GO |
| **节流闸** | `THROTTLE_MIN=50`，降档后 ×2 = **100 分钟** | `last-batch-at` = 13:09:50 ⇒ ELAPSED ≈ **350 分钟** ≥ 100 | ✅ GO |
| **调度闸** | 互斥 | 见第二节 → **活体对端不存在，正常取闸成功** | ✅ GO |

### ⭐ 强度档位：已进入**降档模式**

`TOKEN_HORIZON = 2026-10-25T00:00:00+0800`，当前 2026-10-30 19:00 **已过期** ⇒
按母协议自动降档：**每批车道数 `LANES` 减半（4 → 2）**、**节流闸阈值翻倍（50 → 100 分钟）**。
本轮**按 2 条车道派发**，未自作主张调回高强度。

### 静默窗口

`SILENT_WINDOW=0`（全天运行）⇒ 不设时段闸；但温度 / 容量 / 节流三闸照旧生效（以上均已逐条判定）。

---

## 四、本批派发（2 条车道，降档模式）

`lane.mjs plan --lanes 2` 输出 `newlyPlanned = [18.1.2.2, 18.1.1.3]`，`serviceBlocked = []`
（卷 18 的后端主责服务 `platform` 已在集成头里，微服务闸不挡）。

| 车道 ID | 名称 | 阶段 / L3 | 分支 | 迁移版本 | worktree |
| --- | --- | --- | --- | --- | --- |
| **18.1.2.2** | 投递记录 | D7 / 18.1.2 | `feat/18-1-2-2` | **V413** | `.worktrees/18.1.2.2` |
| **18.1.1.3** | 调用监控 | D7 / 18.1.1 | `feat/18-1-1-3` | **V414** | `.worktrees/18.1.1.3` |

两条车道的基线均为集成头 `9b5a83d3`，测试类前缀已约定为 `DeliveryRecord*` / `ApiCallMonitor*`，
以便 worker 收窄 L1。两条车道**互不依赖**（L3 锚点不同、迁移版本不撞号）。

### 静态预检（互相破坏形状扫描）

本批**无新迁移**（车道刚派出，迁移由 worker 写）⇒ 本轮无可扫描对象。
**下一轮回收这两条车道时必须跑**（PIPELINE.md 5.1）：
`ADD COLUMN` 无 `DEFAULT`、`SET NOT NULL` 无触发器兜底、`ADD CONSTRAINT ... CHECK` 三项扫描。

---

## 五、L2（本轮）

### 5.1 结论：**绿**

| 步骤 | 命令 | 本轮结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | **沿用 round-133 的绿证据**（见 5.2），本轮未重跑 |
| 类型检查 | `pnpm typecheck` | ✅ exit 0（FULL TURBO，缓存命中） |
| Lint | `pnpm lint` | ✅ exit 0（3 tasks successful） |
| 前端全量 | `pnpm vitest run --maxWorkers=4` | ✅ **692 文件 / 9789 例全绿**，24.97 s |
| 前端构建 | `pnpm --filter @starcon/web build` | **沿用 round-133 的绿证据**（见 5.2），本轮未重跑 |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ 「**✓ 台账与蓝图一致**」 |

### 5.2 为什么没有重跑 `mvnw verify`（2.25 h）与 Next 生产构建（取证，不是拍脑袋）

集成头当前 HEAD = `9b5a83d3`。round-133 的 L2 全绿是在 **代码态 `b5476e82`**
（`mvn verify` 三步）与 **`49d68886`**（前端三步）上跑的，两者**代码内容完全相同** ——
`49d68886` 只比 `b5476e82` 多一个 round-134 的纪要文档。

本轮实测：

```
git diff --name-only b5476e82..HEAD | grep -vc '^docs/'   →  0
```

⇒ **自 round-133 绿灯以来，Java / 前端 / 契约 / 迁移代码一行都没变**；
`9b5a83d3`（sync-main 第 111 轮）与 `b74facfe`（batch-116 说明）**都是纯 docs**。
本轮另**新加的两条车道还没落地**，集成头里没有任何新代码需要验证。

在此前提下重跑 2.25 h 全量 = **零新增覆盖、纯发热**（用户 2026-10-07 / 10-08 报过热与风扇噪音），
与组批协议「**每批只跑一次全量**」的设计前提相悖。故本轮用
「**代码零差异继承绿证据 + 廉价子集当场重验**」替代，并在前端沿用 round-133 定位出的
`--maxWorkers=4` 有界并发（否则 forks 池会起不来并**静默漏跑文件**）。

⚠️ **下一轮回收这 2 条车道时，必须跑一次真正的全量 L2**（含 `mvnw verify` + Next build），
因为那时集成头里会真正出现新代码。

### 5.3 台账

`sync.mjs` 写模式**无 diff** —— 未开发的 28 个模块**不会**因为「已排车道」就翻成「进行中」，
该状态由车道落地时的证据驱动。故本轮 commit 只含说明文件，与台账状态一致，无矛盾。

---

## 六、收尾三件套（本轮按序执行）

| # | 动作 | 结果 |
| --- | --- | --- |
| ① | **写说明** | 本文件 `docs/sessions/2026-10/2026-10-30-1900-pipeline-round135-gate-race-released-dispatch-2-lanes.md` |
| ② | **commit** | `docs(round-135): ...`（说明文件 + 台账**同一个 commit**；台账本轮无 diff，故实际只含说明文件） |
| ③ | **push** | `git push origin integration/module-dev`（**只推集成头，绝不推 main**） |

⚠️ 本轮 push 的内容是 **docs-only**（说明文件 + sync-main 留的 `9b5a83d3`），
**不含任何未经 L2 的代码** —— 代码部分早已由 round-133 绿灯后推送。

---

## 七、本轮未决事项（交用户裁决，**本轮未擅自决定**）

沿用 round-133 的 8 项（`vitest.config.ts` 并发上界与「文件总数守恒」断言、`lane.mjs` 闸龄判据改用
keepalive 文件 mtime、`gate release` 只删自己持有的闸、原子接管沉淀为 `lane.mjs gate reclaim`、
续期守护随 tick 结束并记录 PID、`gc` 改用 `lane.worktree`、Docker VM 常驻 101–110% CPU、
`vitest` 静默漏跑文件），**本轮新增 2 项**：

1. **`owner` 记的是一次性 node 进程 PID** ⇒ 不能作为「持闸者已死」的独立证据。
   建议把「持闸者存活」的判据从「owner PID 存活」改成「**持闸会话状态 + 在途车道 + 构建进程**」三合一，
   并把 owner PID 降级为辅助线索。已回写 backlog 第五节。
2. **sync-main 也会抢这把闸** ⇒ 判「闸被占」时必须同时看 pipeline 与 sync-main 两个会话；
   建议在 `dispatch.gate/` 里额外写一个 `holder: <kind>` 字段区分持有者来源。

---

## 八、吞吐预期（按当前配置现算，不引用任何历史文档数字）

- 降档后每批 **2** 条车道；cron `0 * * * *` ⇒ 24 个触发点/天；节流闸 100 分钟 ⇒ 每小时放行 1 批。
- 名义吞吐 = 2 × 24 = **48 个/天**；乘综合折损系数 ≈ 0.70 ⇒ **≈ 33 个/天**。
- 剩余 **28** 个 ⇒ 理论 **≈ 1 天**内清空；考虑长尾车道占位与卷 18 的服务集中度，
  **诚实区间 2–4 天**（不承诺精确完成日期）。
- 本轮无回收、只派发 ⇒ 两条车道将在下一轮（20:00 tick）前后落地，届时执行全量 L2 + 三件套。
