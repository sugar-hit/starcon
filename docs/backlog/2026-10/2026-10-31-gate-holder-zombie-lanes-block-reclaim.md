# 🔴 `holderLiveness` 用「state 字段」判活 ⇒ 死 tick 永久伪装成活体，闸死锁无法自愈

> **优先级：P0** —— 它让「闸龄过 TTL ⇒ 自动接管」这条协议自愈路径**机制性不可达**，
> 且**必然**在「持闸 tick 崩在自己派发的车道上」时触发。
>
> **本文件是母文档 `2026-10-24-orphan-keepalive-daemon-gate-deadlock.md` 与
> `2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md`、
> `2026-10-30-orphan-keepalive-daemon-gate-deadlock-round133.md` 的第 4 篇续篇。**
>
> **发现于 round-137（2026-10-31 04:00 tick）**：与前三篇的「孤儿续期守护刷 mtime」
> **是不同的机制**，但**后果一样**（闸死锁 ⇒ 零派发 ⇒ 流水线停摆）。

---

## 一、现象

round-137 取证到的事实（逐条可复核）：

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 tick 会话 | `mvs_43a6ca53e2b946f49723e8934ac281dd` = **`error`**（`ERR_CONNECTION_CLOSED`），最后活动 **03:33:39** |
| 2 | 其 worker 18.1.2.2 | `mvs_fde852cf961844629cc29cb0b2c06074` = **`error`**（`ERR_CONNECTION_CLOSED`），**03:33:37** |
| 3 | 其 worker 18.1.1.3 | `mvs_3d377aaeee5f4564beb810a4d9f7eb02` = **`error`**（`ERR_HTTP2_PING_FAILED`），**03:18:32** |
| 4 | 构建进程 | 无 maven / surefire / vitest / next / playwright |
| 5 | 闸目录 mtime 写入者 | 无续期守护在跑；最后一次写入 = **03:08:04**（该 tick 自己的 `gate touch`） |
| 6 | 该 tick 是否留下提交 | 没有；集成头 HEAD 仍是 02:24 的 `d656a781` |
| 7 | `gate status` | `gate=held, ageSeconds=3155, ttlSeconds=4200, stale=false, reclaimable=false` |

⇒ **持闸者与其两个 worker 在同一时段被网络层掐断**（03:18–03:33）。
形状与 round-123「额度统一熔断」同款：**会话侧熔断，不是代码问题**。

## 二、根因：`holderLiveness()` 的判据是「state 字段值」，不是「心跳新鲜度」

`tools/module-progress/lane.mjs` 的 `holderLiveness()`：

```js
const inFlightLanes = Object.values(state.lanes).filter(
  (l) => l.state === 'running' || l.state === 'ready',
).length;
// ...
holderAlive: inFlightLanes > 0 || buildProcesses.length > 0,
```

`state` 只有在**有人显式改它**的时候才会变（`land` / `merge-ready` / `abandon`）。
持闸 tick 崩掉时，它派发出去的车道**永远停在 `running`** ⇒

> **`inFlightLanes` 恒 > 0 ⇒ `holderAlive` 恒为 true
> ⇒ `gate reclaim` 恒回「持闸者仍活着（在途车道 N 条）」⇒ 拒绝接管。**

⇒ **闸龄过 TTL 并不能解死锁**。这正是 round-130/131/132
「连续三轮只取证不处置」的**机制性根因**（那三轮每轮都证明了持闸者已死，
但工具的接管判据看不见这一点）。

### 讽刺点：**同一文件里已经有正确的判据，只是没被用上**

`lane.mjs` 自己就有两个「车道是否还活着」的正确判据：

- `laneIsStale(lane)` —— 心跳年龄 > `TTL_MINUTES`(90)；
- `laneIsUnclaimed(lane)` —— 无 `sessionId` 且心跳年龄 > `CLAIM_GRACE_MINUTES`(60)；

而 `plan` 判孤儿用的正是 `l.state === 'running' && (laneIsStale(l) || laneIsUnclaimed(l))`。
**`holderLiveness` 完全可以复用同一套判据**，却退回到最弱的「state 字段值」。

### 实测：本轮两条僵尸车道**按 `plan` 的判据已经是孤儿了**

| 车道 | `sessionId` | 心跳年龄 | `laneIsUnclaimed` | `laneIsStale` |
| --- | --- | --- | --- | --- |
| 18.1.2.2 | `null` | **62.6 分钟** | ✅ **true** | false（>90 才 true） |
| 18.1.1.3 | `null` | **69.2 分钟** | ✅ **true** | false |

`plan --lanes 2 --dry-run` 实测（**零副作用**：`git status` 干净、`lanes.json` mtime 未变）：

```json
{ "toDispatch": [
    { "id": "18.1.2.2", "name": "投递记录", "branch": "feat/18-1-2-2", "migrationVersion": "V413", "redispatched": 2 },
    { "id": "18.1.1.3", "name": "调用监控", "branch": "feat/18-1-1-3", "migrationVersion": "V414", "redispatched": 2 } ],
  "newlyPlanned": [], "serviceBlocked": [] }
```

⇒ **`plan` 认为它们该重派；`gate reclaim` 认为持闸者还活着。** 同一份 `lanes.json`，两套矛盾判据。

## 三、为什么不能靠 `abandon` 清僵尸车道（**会删成果**）

`abandon` 把车道置为 `abandoned`，而 `gc` 对 `abandoned` 车道会：

```js
git(['worktree', 'remove', '--force', wt], ROOT, true);
rmSync(wt, { recursive: true, force: true });
```

⇒ **`abandon` + `gc` = 删掉 worktree 里的全部未提交成果。**

而这两条僵尸车道的 worktree 里躺着：

- **18.1.2.2**：14 个路径 —— platform 迁移 `V413__webhook_delivery_record.sql`（4 表，
  `ENABLE` + **`FORCE`** RLS）+ identity 权限码迁移 + `DeliveryRecordController/Service/Models`
  + `WebhookDeliveryExecutor` + 3 个测试类 + 前端 `app/integrations/deliveries/` 与
  `components/integrations/deliveries/` + **共享白名单 `apps/web/src/lib/api-proxy.ts`** 改动
  + 契约 `platform.ts` / `platform.openapi.json`。
  其 note 自述：**「后端 V413+执行器+11 端点+27 例测试全绿（实测）」**
  （※ worker 自陈，重派后**必须重跑 L0/L1 复核**，不可直接采信）。
- **18.1.1.3**：4 个路径 —— platform 迁移 `V414__api_call_monitor.sql`（2 表）+ identity 权限码迁移
  + `ApiCallMonitorModels` + `ApiGatewayEndpoints`。

⇒ **正确的恢复路径是「重派」而不是「放弃」**：重派会**复用既有分支与 worktree**
（`plan` 里 `branchExists` / `existsSync(wt)` 两个判断），成果原地保留，新 worker 接着写。

## 四、待办（交人工 / 后续改动，**本轮不动**）

- [ ] **`holderLiveness()` 的在途车道判据应改用心跳新鲜度，而不是 `state` 字段值** ——
      建议：`const live = Object.values(state.lanes).filter(l =>
      (l.state === 'running' || l.state === 'ready') && !laneIsStale(l) && !laneIsUnclaimed(l))`
      ⇒ 与 `plan` 判孤儿用**同一套**判据，死 tick 的僵尸车道不再伪装成活体。
      ⚠️ **会碰 `lane-gate-reclaim.itest.mjs` 的既有断言**
      （该文件 ③ 断言「`inFlightLanes=2` ⇒ `holderAlive=true`」，
      改动后该用例需要改造成「心跳新鲜的 2 条在途车道」）⇒ **须单独评估 + 改测试，不在流水线 tick 里顺手改**。
- [ ] **`holderLiveness()` 应把「心跳过期的在途车道」单独计数并输出**
      （如 `staleLanes`），让 `gate status` 能直接显示
      「在途 2 条（**均已心跳过期**）」⇒ 调度者一眼看出「这是僵尸不是活体」。
- [ ] **闸目录里应写 `holder: <kind>`**（pipeline tick / sync-main 任务 / 孤儿守护）
      —— round-135 已提，本轮再次确认：**03:00 tick 留下的 `owner` 里 `holder` 是 `unknown`**
      （`gate status` 输出 `holder: "unknown"`）⇒ 光看闸目录**分不出是谁持的**。
- [ ] **`owner` 里的 PID 应升级为「持闸会话 id」**（round-135 已提）——
      本轮若能直接看到「持闸会话 = mvs_43a6ca53e2b946f49723e8934ac281dd」，
      死活判定**一次取证即完成**，不需要再靠「owner PID 存活？」这种已知不可靠的辅助线索。
- [ ] **`gate reclaim` 应支持「持闸会话已死」的显式证据**（如 `--holder-session <id>` +
      会话状态核对），而不是只依赖 `holderLiveness()` 的间接推断。
- [ ] **`--force` 语义需再明确**：现状是「不等闸龄窗口」，**不能**无视持闸者死活
      （`lane-gate-reclaim.itest.mjs` 明确断言「reclaim --force 也拒绝接管活着的持闸者」）。
      ⚠️ 保持这个语义，**不要**为了让流水线能跑而放宽它 —— 放宽 = 双派发 = 版本号撞号 + L2 成片假红。

## 五、给下一轮 tick 的处置顺序（05:00 tick）

1. 复查持闸者仍死（本文档第一节 7 条证据）；
2. **不要** `abandon` + `gc` 僵尸车道（会删成果，见第三节）；
3. 走 round-133 同款**原子接管**：先取证 ⇒ 再在**单个 node 进程**里
   `rename` 陈旧闸到**固定的**偷取目标目录 + `mkdir` 重建 + 写 owner
   （全程无 release→acquire 竞速窗口；`rename` 到固定目标是「双向持闸结构上不可能」的关键）；
4. 接管后立即 `plan --lanes 2`（重派这两条孤儿车道、**复用 worktree**），
   任务书第一节写明「接手半成品 + 18.1.2.2 改了共享白名单 api-proxy.ts ⇒ 必须跑全量 allowlist 回归」。
