# lane.mjs plan：用陈旧内存快照回写 lanes.json，会静默撤销并发发生的 land / heartbeat

**发现时间**：2026-10-20 13:00 tick（batch-35 的 4 条车道落地期间）
**严重度**：中高 · **状态**：✅ **已修（2026-10-20 13:00 tick 收尾轮）** —— 修法见文末「已实施」一节；
本轮先用 `land --lane 10.6.1.1` 补回落地标记止血。

## 现象

batch-35 派发的 4 条车道里，**10.6.1.1 成本分析**的 worker 在 13:03:5x 跑完并落地：

```
land --lane 10.6.1.1 → {"ok": true, "lane": "10.6.1.1", "head": "e7a7cd8", "branch": "feat/10-6-1-1"}
```

**命令返回成功，但落地标记根本没进 `lanes.json`。**

13:08 读状态时，10.6.1.1 仍然是 `state=running / headSha=null`，
而同批的 10.3.1.1 / 10.7.1.1 / 5.2.1.1 三条都是 `merged`。
结果：13:00 tick 的 `merge-ready` 只并入了 3 条，**10.6.1.1 的成果（已提交、工作区干净）被漏掉**，
它的模块坑位被占住，直到心跳超 90 分钟才会被当作僵尸车道回收重派。

## 根因

`tools/module-progress/lane.mjs` 的 `plan` 命令是「读整份状态 → 改 → 写整份状态」：

```js
const state = readState();          // ← 13:02:14 读
// ... 循环里每条车道：git branch / git worktree add / pnpm install
if (!dryRun) writeState(state);     // ← 13:03:59 写
```

**`readState()` 与 `writeState()` 之间隔着整个 plan 的执行时间**，而 plan 每条车道要做
`git worktree add` + `pnpm install --prefer-offline`（实测每条约 4–5 秒）。

于是本轮的精确时序变成：

| 时刻 | 事件 | 对 `lanes.json` 的影响 |
| --- | --- | --- |
| 13:02:14 | 13:00 tick 的 `plan` **读取**状态 | 内存快照里 10.6.1.1 = `running`（worker 还没落地） |
| 13:03:44 | 10.6.1.1 的 worker **commit** `e7a7cd83` | — |
| 13:03:46–13:03:59 | plan 建 4 个 worktree（各跑一次 `pnpm install`） | — |
| ~13:03:5x | 10.6.1.1 的 worker 调 `land` → 读状态、改成 `ready`、**写盘成功** | 文件此刻**确实是** `ready` ✅ |
| **13:03:59** | plan 跑完循环，**用 13:02:14 的陈旧快照回写整个文件** | 💥 **`ready` 被静默还原成 `running`** |

`plan` 的写入比 `land` 晚 1–15 秒，但它写的是**旧内容**，于是把新内容覆盖掉了。

## 为什么这个 bug 比看上去严重

1. **完全静默**：`land` 返回 `ok: true`，没有任何错误、没有日志、退出码 0。
   worker 以为自己落地了，调度者以为它 ready，**只有下一次读状态时才发现没落地**。
2. **命中窗口随 batch 规模增长**：`plan` 执行越久（车道越多、`pnpm install` 越慢），窗口越宽。
   本轮是 105 秒，4 条车道。若某天 `pnpm install` 变慢，窗口会更长。
3. **不止 `land`，`heartbeat` 同样会被吞**：心跳被回写覆盖 ⇒ 活着的车道被判失联 ⇒
   被 `laneIsStale` / `laneIsUnclaimed` 回收重派 ⇒ **双 writer 写同一个 worktree**。
   这正是 `lane.mjs` 里 2026-10-07 那段注释描述的老坑的**另一条触发路径**。
4. **后果可量化**：10.6.1.1 若不补回，按 `sessionId` 非空 + 心跳 90 分钟 TTL 判，
   要到 **14:19 之后**才会被回收重派；重派后还要再跑一遍 L0/L1 才落地。
   补回后 14:00 tick 即可并入，**省掉约 1–2 小时**。

## 本轮怎么补的（**不是**修法，只是止血）

确认三道闸都过（worktree dirty=0、HEAD `e7a7cd83` ≠ 基线 `969a983`、分支正确）后，
在**集成头**重跑一次 `land --lane 10.6.1.1` → 标记恢复为 `ready`。
此时 13:00 tick 的 `plan` 已经跑完、不再有并发写入，且 `gc` 不写 `lanes.json`、
`merge-ready` 也不会再跑，所以补写是安全的。

⚠️ **本轮没有改 `lane.mjs`** —— 13:00 tick 正在集成头上跑 L2，
改 `lane.mjs` 会让工作区变脏；而 13:00 tick 的 commit 只 `git add` 说明文件与台账，
**不会带上 `lane.mjs`**，改动会留成脏文件 ⇒ **14:00 tick 的 `merge-ready` 会在
「主工作区不干净，拒绝切分支」处直接退出**，整批并入被堵死。
⇒ 这个缺陷留给下一轮**专门**修，不要在别的 tick 里顺手改。

## 建议的修法（三选一，推荐第 1 个）

1. **`plan` 写盘前重新读一次状态再叠加**（最小改动、直击要害）：
   ```js
   // 循环里只收集「要新建的车道」，不要整份状态一起带走
   const plannedLanes = [];
   for (const row of picked) { /* ... */ plannedLanes.push(state.lanes[id]); }
   if (!dryRun) {
     const fresh = readState();          // ← 重新读，拿到 land/heartbeat 的最新写入
     for (const l of plannedLanes) fresh.lanes[l.id] = l;
     writeState(fresh);                  // ← 只叠加自己新建的车道，不覆盖别人的写入
   }
   ```
   语义从「整份覆盖」变成「增量叠加」，其它车道在这期间的 `land` / `heartbeat` 不再被吞。
2. **缩短窗口**：`plan` 先把 worktree 全部建好、**最后**再读一次状态、写一次盘。
   能把窗口从 105 秒压到几秒，但**不能根治**（窗口仍在，只是变窄）。
3. **给 `lanes.json` 加文件锁**（`flock` / `O_EXCL` 锁文件），`plan` / `land` / `heartbeat`
   全部持锁。最彻底，但改动面最大。

⚠️ 修完必须补一条集成测试：并发跑「`plan` 写盘」与「`land` 写盘」，
断言 `land` 的标记**不被 `plan` 覆盖**。现有 `merge-union.itest.mjs` 已有并入侧的集成测试，
可以照着它的风格补。

## 顺带记录：`plan` 忘了带 `--session` 会重蹈「活车道被误判成孤儿」

13:00 tick 新建的 4 条车道（10.3.1.2 / 10.3.2.2 / 10.6.2.1 / 10.7.1.2）
`sessionId` 全是 `null` —— **该 tick 又漏了 `--session`**。
batch-35 已经把这条写进会话说明（「`plan` 必须继续带 `--session`」），13:00 tick 没照做。

后果与本文件开头那条同源：`laneIsUnclaimed` 的判据是「`sessionId` 为空 **且** 距上次心跳 > 60 分钟」，
`sessionId` 为空时**活着的车道也会在 60 分钟后被误判成孤儿**并被重复重派（双 writer）。

⇒ **建议把 `--session` 变成 `plan` 的默认行为**：读环境变量或从 `lanes.json` 里
已有的 `sessionId` 继承，而不是要求每个 tick 记得手工传。
在 `lane.mjs` 里加一个「若未显式传 `--session` 且当前 tick 有已知 session id，则用它」的兜底，
比在 prompt 里靠人记要可靠得多。

---

## ✅ 已实施（2026-10-20 13:00 tick 收尾轮）

### ① plan 写盘：改成「写前重读 + 增量叠加」（推荐方案 1）

`tools/module-progress/lane.mjs` 的 `plan` 收尾处，从

```js
if (!dryRun) writeState(state);          // ← 整份覆盖
```

改成

```js
if (!dryRun) {
  const fresh = readState();               // ← 重新读最新状态
  fresh.lanes ??= {};
  for (const l of planned) fresh.lanes[l.id] = l;        // 新建的车道：整条写入
  for (const l of orphaned) {                            // 认领的孤儿：只在仍无人接手时覆盖
    const cur = fresh.lanes[l.id];
    if (!cur || cur.state !== 'running') continue;       // 已 land / 已并入 → 不碰
    const curBeat = Date.parse(cur.heartbeatAt ?? cur.createdAt ?? '');
    const ourBeat = Date.parse(l.heartbeatAt ?? '');
    if (Number.isFinite(curBeat) && curBeat > ourBeat) continue;  // 刚续过心跳 → 不碰
    fresh.lanes[l.id] = l;
  }
  writeState(fresh);
}
```

语义从「整份覆盖」变成「增量叠加」：其它车道在这期间的 `land` / `heartbeat` 不再被吞。

### ② 顺带修掉：`land` 的「没有任何新提交」闸门是**死的**

```js
const sha = git(['rev-parse', 'HEAD'], lane.worktree);   // 40 位全量 sha
if (sha === lane.baseSha || !sha) {                      // lane.baseSha 是 7 位短 sha
```

40 位全量 sha 永远不等于 7 位短 sha ⇒ 这条比较**从未成立过** ⇒
「一条 commit 都没有就标 ready」一直畅通。已改成比短 sha：

```js
if (!sha || sha.slice(0, 7) === lane.baseSha) {
```

### ③ 回归测试：`tools/module-progress/lane-plan-state.itest.mjs`（新增）

起一个真的临时仓库，把 `pnpm install` 换成 `sleep 4` 把 plan 的执行窗口拉长，
在窗口期内**并发跑真的** `land` 与 `heartbeat`，断言 plan 收尾时：

1. 刚 land 的车道仍是 `ready`（`landedAt` / `headSha` 都还在），没被还原成 `running`；
2. 刚续过心跳的车道心跳时间仍是新的、备注也还在，没被还原成旧快照值；
3. plan 自己新建的车道确实写进去了（修法没把本职工作弄丢）；
4. `land` 拒绝「一条新提交都没有」的车道（HEAD 仍是基线时退出码非 0）。

**已实测这个测试在旧行为下会红**（把写盘块还原成 `writeState(state)` 后跑：
断言 ①② 失败，报 `state=running` / `landedAt=undefined` / 心跳被还原成旧值 / 备注丢失），
恢复修法后 10 条断言全绿。

### ④ 回归验证

`lane-gate` / `lane-plan-orphan` / `lane-critical-path` / `lane-service-gate` /
`lane-conflict-reconcile` / `lane-merge-abort` / `merge-union` 七个既有 itest 全部照旧通过。

### ⑤ 仍未修的：`plan` 忘了带 `--session`

本文件的第二部分指出 `plan` 不带 `--session` ⇒ `sessionId` 恒为 `null` ⇒
活着的车道在心跳断了 60 分钟后会被误判成孤儿并重派（双 writer）。
**本轮未改**：改 `plan` 的默认 session 语义会改变既有调度行为，
属于「用户决定的节奏参数」范畴，应单独一轮专门处理。
当前缓解：worker 每 25 分钟续一次心跳（> 60 分钟宽限 ⇒ 活着的车道不会踩中）。
