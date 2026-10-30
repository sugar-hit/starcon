# round-132 · 12:00 tick —— 调度闸被活体持锁（孤儿续期循环堵死 TTL 接管路径）⇒ 零派发 + 只读

> 本轮（2026-10-30 12:00 tick）**没有派发任何车道、没有取闸、没有回收、没有跑 L2、没有 push**。
> 阻塞点**不是机器容量**（容量闸全绿），而是**调度闸被一个已经崩掉的 tick 的孤儿续期循环无限续命**。

## 一、进度（`sync.mjs` 实时值）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **435**（93.5%） |
| 进行中 | 0 |
| 未开发 | **30** |
| 离全部交付还差 | **30 个** |

分阶段：D7 128 已交付 / 27 未开发；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。
台账完整性：**465 行齐全、无重复 ID**（本轮已复核）。

## 二、档位与闸门判定

| 闸 | 判定 | 实测 |
| --- | --- | --- |
| 静默窗口 | 不生效 | `SILENT_WINDOW=0`（全天运行） |
| 强度档位 | 🔴 **reduced（降档）** | 已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ `LANES=4/2=2`、`THROTTLE_MIN=50×2=100` |
| 批次节流闸 | ✅ PASS | 距上批 **239 分钟** ≥ 阈值 100 分钟 |
| 温度闸 | ✅ GO | `pmset -g therm`：无 CPU_Speed_Limit / Processor_Speed_Limit / Shutdown_SpeedLimit 记录 |
| 配额闸 | ✅ GO | `lanesRunning=0` / 配额 6 |
| PG 闸 | ✅ GO | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 **0** 个（阈值 6）；无标签的孤儿容器 **0** 个 |
| load 闸 | ✅ GO | 1min **1.40** / 5min 1.34 / 15min 1.62（10 核，阈值 12）；趋势**平稳不爬升** |
| **调度闸** | 🔴 **撞闸（held / stale=false）** | `gate status`：`gate=held, ageSeconds=31, ttlSeconds=4200, stale=false` |

⇒ **容量闸全部 GO，卡住流水线的是调度闸。**

## 三、撞闸：按协议零派发 + 只读 + 汇报（不清闸、不 force）

`gate status` 显示闸被**活体持锁**（`stale=false`）⇒ 依 `PIPELINE.md` §3.3 与「最高优先级禁令」：
**不抢闸、不清闸、不 force、不 `plan`、不 `merge-ready`、不 `gc`、不派发**。本轮严格照办。

## 四、根因：崩掉的 tick 留下孤儿续期循环，把 TTL 接管路径彻底堵死

链条（本轮逐条取证，不是推测）：

1. **08:00 tick**（会话 `mvs_13c8b63145f84608a3d889c369ffdf94`）于 **08:01:48** 取得调度闸（`.dev-agent/dispatch.gate/owner` 里写的就是这个时刻与 PID）。
2. 它并入了 **13.6.1.2 看板搭建**（`8607cd6a`）与 **13.6.2.1 导出与订阅**（`92181299`），08:03:23 起跑 L2 批次闸。
3. L2 **判红**（reporting 34 例 2 failures）—— 红点与修复清单见 [batch-115 说明](./2026-10-30-batch-115-board-builder-and-export-subscription.md)。
4. 该会话在 **11:23 前后以 `net::ERR_HTTP2_PING_FAILED` 崩掉**（会话状态 `error`），
   **崩在 `gate release` 之前** ⇒ 闸留在「held」状态。
5. 它顺手起的**续期循环成了孤儿**，至今仍在跑：

   ```bash
   # PID 79126，父 PID 79124，09:05 起，已跑 2h59m
   /bin/bash -c for i in $(seq 1 200); do
     touch ".../.dev-agent/dispatch.gate/keepalive-ka-$(date +%H%M%S)-$i"; sleep 100; done
   ```

   本轮实测：12:02:27 仍写出 `keepalive-ka-120200-66` ⇒ **循环活着**。

6. `lane.mjs` 判「陈旧闸」用的是**闸目录 mtime**（`statSync(GATE_DIR).mtimeMs`）。
   续期循环每 ~163 秒新建一个文件 ⇒ **mtime 永远被刷新** ⇒ `stale` 永远为 `false`
   ⇒ **协议里「崩掉的闸最多挡一轮，下一轮自动接管」这条自愈路径被彻底堵死**。

**后果**：13:00 / 14:00 / 15:00 / 16:00 / 17:00 各 tick 都会**继续撞闸、继续零派发** —— 这已是**连续第 3 轮**零派发（round-130 09:15、round-131 11:25、本轮 12:00）。
**这不是机器闸问题，也不是 L2 长期红的直接原因，而是一个「工具泄漏 + 闸的自愈机制被绕过」的活锁。**

## 五、L2 红点：本轮亲自复核，红点未漂移

本轮在集成头上复跑（不是引用 08:00 那轮的结论）：

```bash
cd backend && ./mvnw -pl services/reporting -am test \
  -Dtest='CustomReportIntegrationTest*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ Tests run: 34, Failures: 2, Errors: 0, Skipped: 0
```

两处失败与 08:00 那轮**完全一致**（`CustomReportIntegrationTest:285` 与 `:865`，都是「`exportAvailable` 期望 false 实际 true」）
⇒ **没有新增破坏**，红点仍精确落在 13.6.1.1 的过期断言上（修复清单 6 处见 batch-115 说明）。
**修复归属 13.6.1.1 的测试，不是本批两个模块** —— 按协议应派一条只修这一个模块的 fix 车道；本轮因撞闸**未派发**。

⚠️ **本批的验证面还有一块空白，必须写明**：08:00 那轮 L2 在 `reporting`（reactor 第 **10/11** 个模块）就 BUILD FAILURE 了，
**第 11 个模块 `services/platform` 从未执行**，**前端那半边（`pnpm typecheck` / `lint` / `test` / Next production build / `sync --check`）也从未执行**。
已验证为绿的只有 backend 根 + tenancy + identity + catalog + channel + warehouse + trade + crm + finance（第 1–9 个）。
⇒ 修完那 6 处断言后，**必须重跑一次完整 L2** 才能拿到全绿结论并 push —— 本轮**不跑**全量 L2（撞闸轮只做只读检查 + 收尾，不制造发热高峰）。

## 六、本轮顺带修掉的台账缺陷（证据格被工具静默截断）

13.6.1.2 那行的**证据格**在 09:15 的 round-130 提交里从 **6111 字掉到 2100 字**，截断点正好落在作者写的字面竖线「`PUT|DELETE …`」上 ——
**端点后半段 + 前端 + 测试 + 缺口四段全丢**，而截断后 `sync.mjs --check` 依然是**绿的** ⇒ 丢失不可见、CI 也看不见。

根因是**工具 bug**：`sync.mjs` 的证据格捕获组 `([^|\n]*)` 表达不了「证据格里可以有竖线」⇒ **每次写盘都静默截短**。
本轮已修：数据（从 `92181299` 恢复完整 6113 字 + 去掉字面竖线与多余列）+ 工具（贪婪捕获 + 竖线点名告警 + `--check` 判红）+ 回归测试 `sync-evidence-pipe.itest.mjs`（8 例全绿）。
详见 batch-115 说明末节。

## 七、收尾三件套

| # | 项 | 结果 |
| --- | --- | --- |
| ① | 写说明 | ✅ `docs/sessions/2026-10/2026-10-30-batch-115-board-builder-and-export-subscription.md`（13.6.1.2 + 13.6.2.1 两节）+ 本文件 |
| ② | commit | ✅ 说明与台账**同一个 commit**（`docs(batch-115): …`） |
| ③ | push | ❌ **未 push** —— L2 未绿（`CustomReportIntegrationTest` 2 处过期断言），按协议「L2 没绿绝对不 push」 |

## 八、给下一轮 / 人工的处置清单（按优先级）

1. **解活锁（人工决策，调度者无权自行清闸）**
   - *更快*：终止孤儿循环 `kill 79126`（或 `node tools/module-progress/lane.mjs gate release` 直接落闸）
     ⇒ 闸目录 mtime 不再刷新，下一个 tick 即可合法取得闸（`gate acquire` 成功）。
   - *不动它*：孤儿循环最多 200 次迭代（09:05:27 起第 1 次，12:12 第 72 次 ⇒ 实测 ~158s/次）
     ⇒ 剩余 128 次约需 **5.6 小时**，即**约 17:45–18:10 跑完**；
     之后 mtime 停止刷新，再过 TTL 70 分钟（**约 19:00–19:20**）才会 `stale=true`
     ⇒ **第一个能合法接管的 tick 是 20:00**。
   - ⚠️ 代价：按上面两条任一路径解闸，**到 20:00 之间的 8 个 tick 都会撞闸、零派发**；30 个模块的交付整体后移。
   - ⚠️ 另注：本地已积压 **18 个未 push 的 commit**（含 `8607cd6a merge(13.6.1.2)`、`92181299 merge(13.6.2.1)`
     与 batch-114/115 的说明），远端 `origin/integration/module-dev` 仍停在 `e93fc3d3`（10-29 23:16）
     —— 它们**全部等 L2 变绿后一起 push**。
2. **取闸后第一件事：派 13.6.1.1 的 fix 车道**（只修这一个模块的 6 处过期断言，修复清单见 batch-115 说明），
   并入后**重跑 L2**，绿了再 push batch-115。
3. **顺手把孤儿进程清理固化进流程**：tick 崩溃时若自己起过续期循环，应在 `finally` 里终止；
   否则每次崩溃都会留一个能无限续命的孤儿。

## 九、吞吐预期（按当前档位现算，不引用任何历史文档里的写死数字）

`LANES=2`（降档后）× 批次数（cron `0 * * * *` 整点触发 + 节流闸 100 分钟 ⇒ **实际每 2 小时放行一批 = 12 批/天**）
⇒ 名义 24 个/天；按组批协议的经验折损系数 ≈ 0.70 ⇒ **实际约 16–17 个/天**。
剩余 30 个模块 ⇒ **理论约 2 天**（2–3 天的诚实区间）。
**但本轮的实际约束不是吞吐，是上面那个活锁** —— 不解闸，理论吞吐是 0。
