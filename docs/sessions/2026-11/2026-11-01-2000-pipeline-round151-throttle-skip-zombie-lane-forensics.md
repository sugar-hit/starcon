# round-151 · 并行车道流水线第 151 轮（2026-11-01 20:00 tick）：节流闸命中 + 崩闸取证

> 轮次：**round-151**（2026-11-01 20:00 tick）
> 集成头：`integration/module-dev`，本轮**未改动任何产品代码**，HEAD 保持 `d19d2df6`
> 模式：**降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过期 ⇒ `LANES=4→2`、节流闸 `50→100` 分钟）
> 推送目标：`origin/integration/module-dev`（**不推 main**）

## 一、本轮结论速览

| 项                | 结果                                                                              |
| ----------------- | --------------------------------------------------------------------------------- |
| 进度              | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · 95.7%（D7 17 / D6 3）  |
| 节流闸            | 🔴 **命中**（距上批 **86 分钟** < 降档后阈值 **100 分钟**）⇒ **零派发**              |
| 温度闸            | ✅ 通过（`pmset -g therm`：无 thermal / performance warning，无 CPU power 记录）    |
| 配额闸            | ✅ 通过（在途车道 2 < 配额 6）                                                     |
| PG 闸             | ✅ 通过（Testcontainers 标签容器 **0** < 6；无标签孤儿容器也是 0）                   |
| load 闸           | ✅ 通过（2.74 / 2.12 / 2.85，阈值 12，核数 10；非爬升趋势）                          |
| 容量闸总判定      | **GO**（四条全过）—— 但**节流闸独立命中** ⇒ 本轮零派发                              |
| 调度闸            | 🔴 **被死 tick 持有**（`stale=true` 4780s > TTL 4200s，但 `reclaimable=false`）      |
| 本批回收          | **无**（`ready` 车道 0 条，两条在途车道均已僵死未落地）                              |
| 本批派发          | **无**（节流闸命中 ⇒ 零派发，符合协议）                                             |
| 静态预检          | ⚪ 不适用（本轮无新迁移并入）                                                        |
| L2 批量验证       | ⚪ **未跑**（节流闸命中 ⇒ 协议禁止跑 L2；且本轮无新并入内容可验证）                  |
| 收尾三件套        | 说明 ✅ / commit ✅ / **push ❌（L2 未绿 ⇒ 按协议随下一批绿色批次一起 push）**        |

**⇒ 本轮结论：零派发 + 零回收 + 一次只读取证 + 崩溃取证。**

## 二、节流闸命中：本轮为什么零派发

节流闸在「同步台账之后立刻」执行，判据读 `.dev-agent/last-batch-at`：

```
last-batch-at = 2026-11-01 18:35:14 CST   （round-150 通过节流闸后落盘）
now           = 2026-11-01 20:01:21 CST
ELAPSED       = 86 分钟
阈值          = 100 分钟（THROTTLE_MIN 50 × 2，降档模式）
⇒ 86 < 100 ⇒ 命中 ⇒ 本轮直接结束：不取闸、不 plan、不派发、不跑 L2
```

⚠️ 降档是**自动**的（`TOKEN_HORIZON=2026-10-25` 已过期 7 天），不询问、不改回高强度。
⚠️ 时间戳是 18:35:14 落的 —— 那是 round-150 **通过闸门之后**写的（协议要求），
虽然该轮随后崩掉，什么也没交付，但节流闸只认这个时间戳。

**下一格（21:00 tick）会放行**：ELAPSED ≈ 145 分钟 ≥ 100 分钟。
cron 是 `0 * * * *`（60 分钟栅格）、降档阈值 100 分钟 ⇒ **实际每 2 小时放行一批**。

## 三、🔴 调度闸取证：持闸者已死，但闸接不走（P0 缺陷仍在）

`node tools/module-progress/lane.mjs gate status`：

```json
{ "gate": "held", "ageSeconds": 4780, "ttlSeconds": 4200, "stale": true,
  "holder": "unknown", "ownerPid": 48466,
  "acquiredAt": "2026-11-01T10:41:41.356Z", "reclaimable": false,
  "inFlightLanes": 2, "buildProcesses": [], "holderAlive": true }
```

**持闸者确实已死**，六条独立证据：

| # | 证据                                                                       | 取值                                            |
| - | -------------------------------------------------------------------------- | ----------------------------------------------- |
| 1 | 持闸 tick 会话 `mvs_c95dee72c4fa4de2bf4ded7bb166f832`                      | **error** `ERR_CONNECTION_CLOSED`，最后活动 18:45:04 |
| 2 | 闸目录里最新的 `keepalive` / `owner` 文件 mtime                             | 18:41 ⇒ **79 分钟未续期**（TTL 70 分钟已过）     |
| 3 | 构建进程                                                                    | **0 个**（无 mvnw / surefire / vitest / next）   |
| 4 | `ownerPid` 48466                                                            | **进程已死**（旁证，非独立证据）                 |
| 5 | 两条在途车道的心跳                                                            | 18:37 / 18:40 ⇒ **已停 85+ 分钟**                |
| 6 | 集成头 HEAD                                                                  | `d19d2df6`（round-150 **没留下任何提交**）        |

**但 `reclaimable=false`，闸接不走** —— 根因是 backlog 里挂了很久的 P0：

`holderLiveness()` 用「`state` 字段值」判在途，而不是心跳新鲜度：

```js
const inFlightLanes = Object.values(state.lanes).filter(
  (l) => l.state === 'running' || l.state === 'ready',
).length;
holderAlive: inFlightLanes > 0 || buildProcesses.length > 0,
```

两条僵尸车道的 `state` 永远停在 `running` ⇒ `inFlightLanes` 恒 > 0 ⇒ `holderAlive` 恒 true
⇒ `gate acquire` 与 `gate reclaim` **结构性地永远拒绝接管**。
而唯一能回收僵尸车道的 `plan` 又必须在闸内才能跑 ⇒ **死锁**。

详见 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`。
该文档已给出建议补丁（改用 `laneIsStale` / `laneIsUnclaimed` 同一套判据），
但同时写明「**须单独评估 + 改测试，不在流水线 tick 里顺手改**」
（会碰 `lane-gate-reclaim.itest.mjs` 的既有断言）⇒ **本轮不动 `lane.mjs`**。

## 四、🔴 网络事故取证：4 个会话在 18:39–19:21 全部被掐断

这不是代码问题，是**会话侧熔断**（形状同 round-137 / round-123）：

| 会话                                                          | 角色                | 最后活动  | 错误                            |
| ------------------------------------------------------------- | ------------------- | --------- | ------------------------------- |
| `mvs_c95dee72c4fa4de2bf4ded7bb166f832`                         | round-150 tick      | 18:45:04  | `ERR_CONNECTION_CLOSED`         |
| `mvs_860f262667ac4c3e9e66b120454f906a`                         | worker 18.3.1.2     | 18:42:05  | `ERR_NETWORK_CHANGED`           |
| `mvs_d5a188f49ef840bd915651609ddd074f`                         | worker 18.4.1.1     | 18:39:45  | 500 Internal server error       |
| `mvs_76711255ea0147ab9b03f52705dd2e15`                         | 19:00 tick          | 19:01:28  | `ERR_CONNECTION_CLOSED`         |
| `mvs_7990a55aadb84f6db8cc4337172da27c`                         | main→module-dev 同步 | 19:21:18  | `ERR_HTTP2_PING_FAILED`         |
| `mvs_7b866a6e833f478bad5a6f8ec580eabe`                         | 交付看板刷新        | 19:14:12  | `ERR_CONNECTION_CLOSED`         |

⇒ round-150 在 18:35 派发两条车道、18:41 取闸，**十分钟内连同两个 worker 一起被网络掐断**；
19:00 那一格也没能开工 ⇒ **连续两轮空转**。这是本轮吞吐的最大威胁（见第九节）。

## 五、两条僵尸车道的成果盘点（**不许 abandon，会删成果**）

`abandon` + `gc` 会 `git worktree remove --force` ⇒ 删掉 worktree 里全部未提交成果。
正确恢复路径是**重派**（`plan` 会复用既有分支与 worktree）。

| 车道      | 迁移版本 | worktree 里的成果                                                                                                                                          | 判定                       |
| --------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| 18.3.1.2  | V421     | **3 个 commit**（`3a985300` 契约重生成 / `1b669480` 多租户隔离测试 / `efcd30a3` 修编译错误）+ `packages/api-contracts` 已更新 + **`api-proxy.ts` 未提交改动** | 有实质成果，重派即续写   |
| 18.4.1.1  | V422     | 只有 `.tmp/BRIEF.md`（04:30）+ 一份 round-146 文档；**无任何实现 commit**                                                                              | 实质为空，重派等于从头做 |

⚠️ **18.3.1.2 改了共享白名单 `apps/web/src/lib/api-proxy.ts`** ⇒ 重派后的任务书必须写明：
**必须跑全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api/trade`
（该文件取错一侧会静默删掉别人已交付的路由，而 typecheck / lint / vitest / Playwright 全绿）。
它的插入位置选在 `services[]` 数组**末尾**（而非开头），与 18.3.1.1 / 18.3.3.1 的开头插入相距 14000+ 行 ⇒ 并入时能干净求并。
首 token `automation-rule-editor` 与 18.3.1.1 的 `automation-rules` **互斥**（第 14 字符 `s` vs `-`），不会互相吃掉路由。

## 六、下一轮预演（`plan --dry-run`，**零副作用**）

```bash
node tools/module-progress/lane.mjs plan --lanes 2 --dry-run
```

| 项                                    | 结果                                                             |
| ------------------------------------- | ---------------------------------------------------------------- |
| `toDispatch`                          | **18.3.1.2**（重派第 3 次）、**18.4.1.1**（重派第 2 次）—— 复用 worktree |
| `newlyPlanned`（`--lanes 2`）        | `[]` —— 2 个槽位被两条僵尸车道占满                               |
| `newlyPlanned`（`--lanes 4` 仅探测） | `["18.4.2.2", "18.4.3.1"]`                                        |
| `serviceBlocked`                      | `[]` —— **没有服务缺口**，20 个未开发模块不是被微服务闸堵住      |
| 副作用                                | `lanes.json` mtime 不变（1793529600）、`git status` 干净 ✅        |

⇒ 20 个未开发模块里，至少 18.4.2.2 / 18.4.3.1 已可排；真正的卡点是**闸 + 僵尸车道**，不是模块依赖。

## 七、Flyway 版本号复核（无撞号风险）

集成头现有迁移最大版本 **V420**（platform + identity 成对）⇒ **V421 / V422 空闲**，
与两条僵尸车道预分配的版本号一致，无撞号。18.3.1.2 worktree 里的
`platform/V421__automation_rule_editor.sql` + `identity/V421__platform_automation_rule_editor_permission.sql`
沿用既有「同号跨两服务」的成对写法（与 V411–V420 一致）。

## 八、静态预检与 L2

- **静态预检**：不适用 —— 本轮**无新迁移并入**（`merge-ready` 无 `ready` 车道可并）。
- **L2 批量验证**：**未跑**。两个原因叠加：① 节流闸命中 ⇒ 协议明令不跑 L2；
  ② 本轮无新并入内容，集成头的**产品代码自 batch-124 那次绿 L2（18:36）以来一字未改**
  （`git diff 25460f6d..HEAD` 只有 2 个 main-sync 会话日志文件，+314 行纯文档）。
  ⇒ 集成头当前代码 == 18:36 通过 L2 的那份代码。

## 九、收尾三件套与推送

| 步骤      | 结果                                                                       |
| --------- | -------------------------------------------------------------------------- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-11/2026-11-01-2000-pipeline-round151-throttle-skip-zombie-lane-forensics.md`） |
| ② commit | ✅ `docs(round-151): 20:00 tick 节流闸命中（86<100）⇒ 零派发 + 崩闸取证（持闸 tick 已死 / 两条僵尸车道 / P0 holderLiveness 缺陷）` |
| ③ push   | ❌ **本轮不 push** —— L2 未绿（且协议禁止本轮跑 L2）⇒ 按协议**随下一批绿色批次一起 push** |

> 本轮结束时 `origin/integration/module-dev` 与本地 HEAD **一致**（`d19d2df6`），**无积压 commit**。
> 本文件是唯一的本地增量，将由下一批（batch-125）的 push 带上（round-149 的文档同样是随 batch-124 落 origin 的）。

## 十、下一轮（21:00 tick）恢复顺序

照 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` 第五节：

1. 复查持闸者仍死（第六节六条证据重跑一遍）；
2. **不要** `abandon` + `gc` 僵尸车道（会删 18.3.1.2 的成果）；
3. 走 **round-133 同款原子接管**：单 node 进程内 `rename` 陈旧闸到固定偷取目标 + `mkdir` 重建 + 写 owner
   （无 release→acquire 竞速窗口；`rename` 到固定目标是「双向持闸结构上不可能」的关键）；
4. 接管后 `plan --lanes 2` ⇒ 重派 18.3.1.2 / 18.4.1.1（复用 worktree，成果原地保留）；
5. 任务书写明 18.3.1.2 的 allowlist 全量回归要求（第五节）；
6. 车道落地后 `merge-ready` → 一次 L2 批次闸 → 绿 ⇒ 写 batch-125 说明 → commit → push。

## 十一、风险与待办

1. **🔴 会话侧熔断是当前最大威胁**：18:39–19:21 之间 6 个会话全被掐断（含本流水线两轮 + 同步任务 + 看板）。
   若 21:00 那一格再被掐断，流水线会继续空转。**建议用户关注网络/额度稳定性**（这是用户侧决定，调度器不自行改参数）。
2. **P0 `holderLiveness()` 缺陷未修**（第三节）：建议**单独立项**修 `lane.mjs` 并同步改
   `lane-gate-reclaim.itest.mjs` 的既有断言；在此之前每轮都要靠一次性脚本接管。
3. **18.4.1.1 实质为空**（第五节）：重派等于从头做，`V422` 仍在。
4. **节流闸在降档 + 60 分钟栅格下实际每 2 小时才放行一批** ⇒ 名义吞吐
   `2 车道 × 12 批/天 = 24/天 × 0.70 ≈ 16–17 模块/天`；
   剩余 20 个模块 ⇒ 理论 1.2 天，但**须扣掉重派返工与会话熔断**，诚实区间 **1–3 天**，
   不承诺精确完成日期。
5. `pmset -g therm` 无任何 thermal / performance 警告记录；PG 容器（带标签与孤儿）均为 0 ⇒ **机器侧无遗留压力**。
