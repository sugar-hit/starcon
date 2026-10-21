# 2026-10-20 01:00 调度轮次：调度闸被活 tick 持有，零派发只读取证

> 本轮**未派发新车道、未跑 L2、未 push**。
> 原因是调度闸被**上一轮仍在运行的活 tick** 持有（`stale=false`），按协议不得抢占。
> 本轮实际动作 = 同步台账 + 只读取证 + 写这份轮次记录。

## 一句话结论

00:00 那一轮（session `mvs_a9bb…`，同时是 4 条车道 worker 的父会话）**至今仍在跑 L2 全量验证**；
它在 00:03:04 取到调度闸、00:40:20 并入本批 4 条车道、00:40:48 拉起 `mvnw verify`，
至今（01:04）还在 `trade` 服务（第 7/8 个模块）。闸龄 3659s < TTL 4200s ⇒ **持闸者活着**，
本轮必须让路，否则两个 tick 同时改同一个集成头会留 MERGE_HEAD 残骸。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 模式 | `full`（未过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800`） | `.dev-agent/PIPELINE.local.md` |
| 静默窗口 | `SILENT_WINDOW=0`（全天运行，01:00 不受钟点限制） | 同上 |
| 车道数 | `LANES=4` | 同上 |
| 节流闸 | `THROTTLE_MIN=50`，实测距上批 **57 分钟 ⇒ 放行** | 同上 + `last-batch-at` |
| 配额 / PG / load | `RUNNING_QUOTA=6` / `PG_CONTAINER_LIMIT=6` / `LOAD_LIMIT=12` | 同上 |

## 台账现状（`sync.mjs` 实时值）

| 指标 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | **218**（46.9%） |
| 进行中 | 0 |
| 未开发 | **247** |
| 台账行数 | 465 ✓ |
| 重复模块 ID | 无 ✓ |

分阶段：已交付 D1–D2 80 / D3 36 / D4 56 / D5 31 / D6 5 / D7 10；未开发 D7 145 / D6 73 / D5 21 / D3 4 / D4 4。

## 取证一：调度闸被活 tick 持有，不是陈旧闸

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 3659, "ttlSeconds": 4200, "stale": false }   # stale=false ⇒ 持闸者活着

$ cat .dev-agent/dispatch.gate/owner
48395
2026-10-19T16:03:04.293Z          # = 2026-10-20 00:03:04 +0800
```

`owner` 里的 PID 48395 已不存在 —— **但这不能当作持闸者已死的证据**（`gate acquire` 写完 owner 即退出，
协议明文警告过）。真正的活性证据在下面两处。

## 取证二：持闸 tick 正在集成头里跑 L2（决定性证据）

```
$ ps -eo pid,etime,pcpu,command | grep -E 'mvnw|surefirebooter'
72510  21:17  ... org.codehaus.plexus.classworlds.launcher.Launcher verify
74504  04:55  47.7%  .../backend/services/trade/target/surefire/surefirebooter-...jar
```

- `mvnw verify` 跑在**本集成头 worktree 的 backend 里**（`maven.multiModuleProjectDirectory=…/integration/backend`）
- 起点 00:40:48 = 并入完成（00:40:20）后 **28 秒** ⇒ 这就是持闸 tick 的 L2
- `backend/services/*/target` mtime 00:42 / 00:46 / 00:57 持续推进 ⇒ **在跑，不是卡死**
- 活会话 `mvs_a9bb52457b324ac18ce1f8dcabb1a7b8`（`status: started`，4 条车道 worker 的父会话）
  最近一条消息：warehouse **1535 例 0 failures 0 errors**，现处 `trade`（7/8 模块）

⚠️ 附带发现：`[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`
是 fork JVM 拆除超时的**良性噪声**，不是测试失败（活 tick 已自行确认）。

## 取证三：容量闸全部放行（只有调度闸拦住了本轮）

| 闸 | 判据 | 实测 | 结论 |
| --- | --- | --- | --- |
| 温度闸 | `pmset -g therm` 降频项 < 100 | `No thermal warning level has been recorded` | 放行 ✓ |
| 配额闸 | running ≥ 7 判 STOP | `lanesRunning: 0`（配额 6） | 放行 ✓ |
| PG 闸 | 带 testcontainers 标签的容器 ≥ 6 判 STOP | **4**（同一个 sessionId `272e3116…`，是活 tick L2 自己的容器） | 放行 ✓ |
| load 闸 | 1min > 核数判 STOP；`1min>5min>15min` 判爬升 | 核数 10；load 2.89/3.12/3.49 → 2.90/3.12/3.48 → 3.21/3.18/3.50（**1min < 15min，在降**） | 放行 ✓ |
| 孤儿容器 | 无标签且无连接 | **0 个**（ryuk 正常回收） | 无需清理 ✓ |

⇒ 本轮零派发的**唯一**原因是调度闸被活 tick 持有，**不是**机器过热、不是配额、不是 PG。

## 取证二补记：后端 L2 已在 01:04:31 跑完且全绿

```
$ tail -3 .tmp/l2-backend.log
[INFO] Total time:  23:44 min
[INFO] Finished at: 2026-10-20T01:04:31+08:00

$ grep -E 'BUILD SUCCESS|BUILD FAILURE' .tmp/l2-backend.log
[INFO] BUILD SUCCESS
```

全仓 `Tests run: ... Failures: 0, Errors: 0`（trade 1391 / warehouse 1535 / crm 18 …），
且无任何 `Failures: [1-9]` / `Errors: [1-9]` 计数行 —— **本批 4 条车道的后端半边 L2 已绿**。
活 tick 随后转入前端半边（01:04 起 `l2-typecheck.log`），并已备好
`.tmp/batch-29-body.md`（00:43）/ `.tmp/batch-29-draft.md`（00:18）。

⚠️ 留给下一轮的判据：**batch-29 的后端侧已验证通过**，若前端半边（typecheck/lint/test/Next build）
转红，**不要回头怀疑后端**，按前端回归排查。

## 车道状态

`lanes.json` 共 8 条车道，**全部 `merged`**（7.8.2.2 / 4.4.2.2 / 4.2.1.1 / 4.2.2.1 属本批，
11.2.3.3 / 7.8.2.1 / 4.4.2.1 / 2.4.2.3 属 batch-28）；worktree 已被活 tick gc 干净。
⇒ **本轮无可回收车道**，无需 `merge-ready` / `gc`。

## 关键 trade-off

- **不抢闸、不清闸、不 force**：协议 §3.3 明确 `held + stale=false` ⇒ 只读 + 汇报。
  `stale=true`（> TTL 70 分钟）才允许接管。闸龄才 61 分钟，且持闸者在实打实地跑 L2。
- **不并发跑第二个 L2**：L2 正跑在**同一个 worktree** 的 `backend/` 里，
  两个 `mvnw verify` 共用 `target/` 与 PG 容器 ⇒ 必假红（2026-10-19 20:00 实测：PG 峰值 16、1364 例里 27 个假红）。
- **不写 batch-29 说明、不代劳 commit**：batch-29（7.8.2.2 / 4.4.2.2 / 4.2.1.1 / 4.2.2.1）的
  说明与台账必须**同一个 commit**，那是活 tick L2 绿之后的事。抢写会拆 commit、撞 index.lock。
- **不 push**：origin 停在 `1bb270a0`（batch-28），本地领先 11 个 commit，其中含本批 4 个 merge ——
  **L2 未绿，推上去就是推未验证代码**。

## 文件清单（本轮改动）

- `docs/sessions/2026-10/2026-10-20-0100-scheduler-gate-held-live-l2.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **工作区脏但未提交**：
  `sync.mjs` 回写的汇总头（已交付 214→218、D3 35→36、D5 28→31、卷 04/07 各 +1）。
  **故意不提交**：台账必须与 batch-29 的说明进同一个 commit，交给活 tick 一起提交。
  已验证 `sync.mjs` 幂等（连跑两次输出字节一致），本轮没有破坏活 tick 正在写的状态。

## 后续待办（交给下一轮 / 活 tick）

1. 活 tick：后端 L2 已 **BUILD SUCCESS**（01:04:31 收尾）；余下 `pnpm typecheck/lint/test` + `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` + `sync --check`
   → 写 `docs/sessions/2026-10/2026-10-20-batch-29-*.md` → 与台账同 commit 提交 → push `integration/module-dev` → `gate release`。
2. 下一轮 tick：闸放行后按 `LANES=4` 排新车道；节流闸看 `last-batch-at`（00:03:04）。
3. 无人需要清理孤儿容器（本轮实测 0 个）。
4. 本轮的 `docs(sessions):` commit 会被活 tick 的 `git add docs/sessions/2026-10/` 一并带入
   batch-29 的 push；**本轮自身不 push**（L2 未绿 + 批次归属活 tick）。
