# 2026-10-22 batch-57 —— 节流闸拦截：零派发只读轮（距上批 39 分钟 < 50 分钟阈值）

> 批次号接在 batch-56 之后（`docs/sessions/2026-10/` 下现有最大为 `batch-56`，已用
> `ls | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` 确认无重号）。
> 集成头 `integration/module-dev`，本轮基线 `c9290c3d`（与 `origin/integration/module-dev` **完全一致**，0/0）。
> 本轮为 **2026-10-22 01:00 tick**，`SILENT_WINDOW=0`，`MODE=full`（`LANES=4` / `THROTTLE_MIN=50`；
> 当前 01:0x < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`，**未进入降档**）。
>
> ⚠️ **本轮模块级交付 = 0。** 批次节流闸未达阈值，按协议**零派发 + 零并入 + 零 L2**结束本轮。
> 本文件记录的是**本轮的结论与交接清单**，不是功能交付说明。
> 写法沿用 batch-42 / batch-44（同样是零派发只读轮）的先例：**说明照写、commit 照做、push 照做**。
>
> ⚠️ **批次号提醒（给下一轮）**：00:00 tick 在其交接段里预留的是「写 batch-57 说明」给
> 合并 + L2 那一轮。**batch-57 已被本只读轮占用**，所以「合并 4 条卷 14 车道 + L2 绿」那一轮
> 请取 **batch-58**。

---

## 本轮摘要

本轮在**同步台账之后立刻**撞上**批次节流闸**：

```
LAST=$(cat .dev-agent/last-batch-at)   # 1792599709 → 2026-10-22 00:21:49 +0800
NOW=$(date +%s)                        # 2026-10-22 01:00:33 +0800
ELAPSED=$(( (NOW - LAST) / 60 ))      # 39 分钟
阈值 THROTTLE_MIN=50                   # 39 < 50 ⇒ 未达阈值
```

按 `PIPELINE.md` 第 1.5 步「**未达阈值 → 本轮直接结束。不取闸、不 plan、不派发、不跑 L2。
汇报写一行即可。这是零成本早退 —— 不碰 git、不起容器、不派子任务**」：

- **未取调度闸**（`gate acquire` 未执行；`gate status` 显示 `gate=free` / `ageSeconds=null` / `stale=false`，闸空闲可用）
- **未 plan、未派发 worker**（0 条新车道）
- **未跑静态预检与 L2**（本轮没有新迁移可扫，也没有新代码可验）
- **未并入 4 条 `ready` 车道**（并入属于「取闸之后」的第 3 步，见下文「为什么不并入」）
- **未写 `.dev-agent/last-batch-at`**（未过节流闸就写时间戳会把节奏无限往后推 —— 该闸唯一会出的严重故障）
- **未跑 `gc`**（`gc` 会动 worktree 与 `.git`，同属「不碰 git」范畴）

本轮实际动作 = `sync.mjs` ×1 + 6 组只读取证 + 1 次只读文档 commit + 1 次 push。

---

## 进度快照（`node tools/module-progress/sync.mjs` 实测输出）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **297**（63.9%） |
| 进行中 | 0 |
| 未开发 | **168** |
| 距 465 全交付 | **还差 168 个** |

分阶段（已交付 / 进行中 / 未开发）：

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 59 | 0 | 1 |
| D5 | 52 | 0 | 0 |
| D6 | 43 | 0 | 35 |
| D7 | 23 | 0 | 132 |

> 剩余大头是 **D7（132）** 与 **D6（35）**；D1–D2 / D3 / D5 已清空，D4 只剩 1 个尾单。
>
> ⚠️ **服务缺口仍然堵着**（沿用 batch-56 的 `serviceBlocked` 结论，本轮未复核变化）：
> 卷 12（主责 `finance` 不在集成头，12 个样本）、卷 1（「前端聚合」不在集成头，20 个样本）、
> 卷 13（主责 `reporting` 不在集成头，14 个样本）—— 这三卷**排不出车道**，属人工决策项。
>
> 台账完整性核对通过：`module-progress.md` 的 L4 行数 **465**（须恒为 465），
> 模块 ID **无重复**（`sort | uniq -d` 输出为空），`sync.mjs` 跑完工作区 **无 diff**。

---

## 容量闸判定：**GO**（四闸全过，但被节流闸拦下）

| 闸 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| **温度**（最高优先级） | `pmset -g therm` → `No thermal warning level has been recorded` / `No performance warning level has been recorded` / `No CPU power status has been recorded` | 出现降频项且 < 100 | ✅ 过（无降频项） |
| **配额**（running 车道数） | **0** | `RUNNING_QUOTA=6` | ✅ 过 |
| **PG**（Testcontainers 容器） | **0** | `PG_CONTAINER_LIMIT=6` | ✅ 过 |
| **load** | 1min **1.55** / 5min 2.12 / 15min 2.84 | `LOAD_LIMIT=12`（核数 10） | ✅ 过 |

- **load 趋势**：`1min(1.55) < 5min(2.12) < 15min(2.84)` ⇒ **在下降**，不是爬升态。
- **PG 容器**：`docker ps --filter ancestor=postgres:16-alpine` 输出**空** ——
  既没有带 `org.testcontainers.sessionId` 标签的在用容器，**也没有无标签的历史遗留孤儿容器**
  （本轮无需清理；2026-10-19 那批吃掉 5/6 额度的孤儿已消失）。
- **调度闸**：`gate status` → `gate=free`、`ageSeconds=null`、`ttlSeconds=4200`、`stale=false` ⇒ 闸空闲、无泄漏。
- **静默窗口闸**：`SILENT_WINDOW=0` ⇒ 全天运行，不设时段闸。
- **强度档位**：`MODE=full`（`LANES=4` / `THROTTLE_MIN=50`）。

> ⚠️ **容量闸全过 ≠ 可以派发**：节流闸（时间维度）在容量闸之后独立生效，本轮命中它。

---

## ⛔ 节流闸判定：**BLOCKED**（本轮的唯一拦截项）

| 项 | 值 |
| --- | --- |
| 上批时间戳 `.dev-agent/last-batch-at` | `1792599709` → **2026-10-22 00:21:49 +0800** |
| 本轮采样时刻 | **2026-10-22 01:00:33 +0800** |
| 实际间隔 | **39 分钟** |
| 阈值 `THROTTLE_MIN` | **50 分钟** |
| 结论 | **39 < 50 ⇒ 未达阈值 ⇒ 零派发** |
| 下次放行时刻 | **01:11:49**（之后任意时刻过闸）；下一个 cron 触发点 **02:00**，届时间隔 **98 分钟** ⇒ 放行 |

---

## 交接清单：4 条 `ready` 车道（下一轮合并 + L2）

本轮未回收任何车道，以下 4 条原样留在集成头的 `lanes.json` 里（`state=ready`，成果在分支上等合并）：

| 模块 | 名称 | L3 | 迁移 | 分支 head | 落地时刻（+0800） | 已完成 |
| --- | --- | --- | --- | --- | --- | --- |
| **14.1.1.2** | 活动详情 | 14.1.1 | `V277` | `62a84e61` | 00:39:01 | 后端 17 例绿 + 契约重生成（+10 path/+29 schema）+ 前端 allowlist 608 例绿 + typecheck 绿 |
| **14.2.2.1** | 预算分配 | 14.2.2 | `V278` | `349fb82d` | 00:59:59 | 契约重生成（+29 类型 / +12 path，避开同名冲突）+ 前端 model/workspace 落地 |
| **14.3.1.1** | 活动报表 | 14.3.1 | `V279` | `97519d21` | 00:57:13 | 后端 18 例绿、前端 27 例绿、typecheck 绿；lint + 台账 + 会话日志完成 |
| **14.3.2.2** | 效率诊断 | 14.3.2 | `V280` | `6775defc` | 00:51:23 | 前端完成：`api-proxy` 新增 `ad-diagnostics` 元素（锚点 `^stock-forecasts` 之前）+ `/ads/diag` 页面 |

> `lanes.json` 现状：`merged 91` + `ready 4`，**`running 0`** —— 没有在途车道，本轮 L2 不存在与 L1 抢 PG 的风险。

### 并入预演结论（已由 00:00 tick 完成，本轮直接引用，未重跑）

`git merge-tree --write-tree` 干跑（只写 tree 对象，不改 ref / 不改工作区 / 不产生合并 commit）：
4 条车道两两合 + 顺序合全部 **exit 0**，最终树里**冲突标记 0 处**；
`api-proxy.ts` 的 `services[]` 元素数 base **129** → 合并后 **133**（每车道恰好 +1），
**从 base 掉落的元素 0 个**、**丢失的首 token 0 个**；迁移 `V277`–`V280` 四个都在；
channel 契约 path 数 547 → 587（+40 = 10+12+6+12，与四条车道的端点数逐条对上）。

⇒ 协议点名最阴的失败模式（「取错一侧静默删掉别人已交付的路由，而 typecheck / lint / vitest 全绿」）
**在本批已被证明不会发生**。**下一轮不必再做并入预演。**

⚠️ 但预演同时暴露了一个**真 bug**（已在 14.2.2.1 车道路上修掉）：护栏 record 原名
`AdBudgetGuardrails` 与 **14.1.1.1 同名** —— SpringDoc 静默丢掉后一个，
契约里 14.1.1.1 的 `guardrails` 字段被指向了本模块的类型，
**契约与后端不一致，而 typecheck / lint / vitest 全绿**。
已改名 `AdBudgetAllocationGuardrails`，以「生成后 `channel.ts` 无重复 `export type` 名」为判据复核。
⇒ 下一轮 L2 的契约校验要盯这一类：**重名类型不会让任何测试变红，只会让契约静默串味。**

---

## 为什么不并入那 4 条 `ready` 车道

三条理由，都指向同一个动作：**本轮不碰 git**。写法沿用 batch-44 的同款论证：

1. **协议顺序**：并入属于「取闸之后」的第 3 步，而节流闸在第 1.5 步就结束了本轮；
   `merge-ready` 会 `git checkout` 集成分支并做 `git merge`，属于典型的 git 状态变更。
2. **闸互斥**：`main → module-dev` 同步任务（`SYNC_CRON_ID=01450d5d…`）与本流水线
   **共用同一把调度闸**。不取闸就 merge，撞上同步任务会留下 `MERGE_HEAD` 残骸
   （2026-10-07 一晚被这个缺陷撂过两次）。
3. **不制造未验证代码**：协议规定并入后必须跟一次 L2 才允许 push。本轮被节流闸禁止跑 L2，
   若此时并入，集成头就会带着**未验证代码**停留近一小时，反而违背「集成头只接受绿批次」。

**代价**：4 条 `ready` 车道会空闲到 **02:00 tick**（`14.2.2.1` 已空闲 ~1 小时）。
这是节流闸换来的确定性与温度控制，属预期成本，不是故障。

---

## 🔍 本轮三处值得记下来的取证

### ① 有效节奏实际是「每 2 小时一批」，不是配置意图的每小时

`CRON=0 * * * *`（60 分钟栅格）+ `THROTTLE_MIN=50` 之间只有 **10 分钟余量**，
而协议假设「落盘耗时 13–40 秒」。实测**落盘滞后可达 21 分钟**：

| tick | 触发时刻 | `last-batch-at` 落盘 | 落盘滞后 | 下一格间隔 | 下一格判定 |
| --- | --- | --- | --- | --- | --- |
| 00:00 tick | 00:00 | 00:21:49 | **21 分钟** | 39 分钟 | **被拦**（39 < 50） |
| 02:00 tick | 02:00 | （本轮未写） | — | 98 分钟 | 放行 |

⇒ **落盘滞后 > 10 分钟时，下一格必然被拦**（因为 `60 - 滞后 < 50`）。
本轮实测滞后仅 **1.5 分钟**（协议文件并行读、只读检查并行跑）⇒ 滞后是**可变**的，
落在 10 分钟以内就能放行、超过就被拦 ⇒ **有效节奏在 1 小时与 2 小时之间抖动**。

**修法全部属于用户的决定，调度器不得自行改阈值**（协议硬规定），此处只把事实摆出来：

| 方案 | 效果 | 代价 |
| --- | --- | --- |
| **压缩 tick 启动耗时**（协议文件并行读、只读检查并行跑 —— 本轮 1.5 分钟） | 落盘滞后压到 10 分钟内 ⇒ 恢复每小时 | 零配置改动，**推荐先试这个** |
| 把 `THROTTLE_MIN` 从 50 降到 30–35 | 容得下 20–30 分钟滞后 | 放行变频繁 ⇒ 发热占空比上升 |
| 接受 2 小时节奏 | 名义吞吐减半 | 168 个模块的交付周期翻倍 |

### ② 本轮与 00:00 tick **并行**（cron 栅格已追不上单轮耗时）

`mavis session list` 取证：本轮 session `mvs_d1c8a2eb…`（01:00 触发）与
**00:00 tick `mvs_c9db8b73…` 同时存活**——后者是 4 条 batch-56 worker 的父 session，
`updatedAt` 到 **01:04:58** 才停。00:00 tick 的单轮跨度约 **65 分钟 > 60 分钟 cron 栅格**，
于是 01:00 tick 与它重叠。

00:00 tick 在 01:03:59 提交了 `c9290c3d`（并入预演结论）并 push，**已 push 完毕、闸已释放**，
本轮开始时 `origin/integration/module-dev` 与本地 HEAD **完全一致（0/0）**，工作区干净 ⇒ 无竞态残留。

⇒ **重叠本身不危险**（调度闸 + `merge-ready` 的 git 操作由闸互斥保护），
但它意味着「一轮干不完 60 分钟」时**每轮都会重叠**，需要盯 `gate status` 的 `stale` 字段。

### ③ `main → module-dev` 同步任务已连续 **15 轮**卡在同一组冲突上

冲突文件 5 个，与上轮**逐字相同**，其中包含 **`apps/web/src/lib/api-proxy.ts`** ——
**正是 batch-56 这 4 条车道修改的那个文件**。

- 同步任务方结论：集成头侧 blob 是超集（`services` prefix 120→129 全包含），倾向取集成分支侧。
- **根因已定位**：`d28188d6`（PR #11）是 squash 合并，**merge base 永远停在 `1bb270a0`**
  ⇒ 每轮都重复同一组冲突，**纯靠自动解冲突解不掉，需要人工决策一次**（或改用 `--no-squash` 重合）。
- 下一轮合并 batch-56 的 4 条车道时，`api-proxy.ts` 会**同时**受到「同步任务的 5 文件冲突面」
  与「4 条车道各自新增 1 个元素」两边影响 ⇒ **必须手工求并集**，且并入后要跑
  「取错一侧静默删路由」三件套判据（集合包含 / 首 token 完整 / 元素三件套齐全）。

---

## 下一轮（02:00 tick）动作清单

1. `gate acquire`（先 `gate status` 确认 `free`；`stale=true` 才允许接管）
2. `node tools/module-progress/lane.mjs merge-ready` 回收 **4 条 ready 车道**
   —— `api-proxy.ts` **必须求并集**（同步任务 5 文件冲突面 × 4 条车道各 +1 元素）
3. `node tools/module-progress/sync.mjs` 刷新台账 + `lane.mjs gc`（回收 11 个已合并的 stale worktree）
4. 跑「已知互相破坏形状」扫描（本批 `V277`–`V280`，4 个迁移均**零 ALTER 既有表** ⇒ 大概率不命中）
5. 跑**一次** L2 全量：`mvnw verify` + `pnpm typecheck/lint/test` + `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` + `sync.mjs --check`
   —— 契约校验要额外盯**重名 `export type`**（见上文 ②）
6. L2 绿 ⇒ 写 **batch-58** 说明（4 个模块各一节）⇒ commit ⇒ push
7. L2 红 ⇒ 不 push，二分定位（最多 2 次全量），派修复任务
8. 然后才 `plan --lanes 4` 派发下一批

### 顺带记一笔：`.worktrees/` 的 housekeep

`.worktrees/` 里堆着 **11 个已合并的 stale worktree**（`*-fix` 系列），
`git merge-base --is-ancestor` 全部 **MERGED**、工作区 `dirty_files=0` ⇒ 可安全 `gc`。
另外 `fix/batch-37-l2-green` 的 worktree **目录已不存在**但 git 仍登记着 ⇒ 需 `git worktree prune`。
⚠️ `fix/4-5-2-1-allowlist-negative` 的分支**未被判定为已并入**（可能已被 squash / cherry-pick 覆盖），
`gc` 前需人工确认，**不要盲删**。

---

## 收尾三件套（本轮）

| # | 项 | 结果 |
| --- | --- | --- |
| ① | 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-22-batch-57-throttle-gate-readonly.md` |
| ② | commit | ✅ `docs(batch-57): 零派发只读轮 —— 节流闸拦截（距上批 39 分钟 < 50 分钟阈值）`（与台账同一个 commit） |
| ③ | push | ✅ `git push origin integration/module-dev` |

⚠️ **本轮 push 的安全性说明**：本轮**零并入**，工作区在 commit 前是干净的，
自 batch-55 那次「L2 复跑绿」（`1c6dba79`）以来**只有 docs 文件变动**
（`git diff --name-only 1c6dba79 HEAD` = 4 个 `docs/sessions/2026-10/*.md`），
**没有任何代码文件进入集成头** ⇒ push 不会带入任何未经 L2 验证的代码。
这与 batch-42 / batch-44 两个零派发只读轮的先例一致（**说明照写、commit 照做、push 照做**）。
