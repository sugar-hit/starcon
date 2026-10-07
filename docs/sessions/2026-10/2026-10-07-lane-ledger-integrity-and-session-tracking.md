# 并行车道调度：台账完整性事故 + session 追踪修复

日期：2026-10-07 16:00 tick
范围：调度器（不改业务代码）

## 决策摘要

本轮没派新模块之前先做了三件事，因为台账本身已经不可信了：**修回 1 个被误置的台账行、
回收 1 条假冲突车道、补上 lane session 追踪**。三件事都指向同一个根因链：
`plan` 只读台账判「是否已交付」，而台账行会被后续的 docs 提交改回未开发。

### 1. 3.8.1.2 台账行被去重提交误置（已修）

`1b94f62 docs: 台账去重并落定 3.8.1.2 状态` 把 3.8.1.2 整行重置为「未开发 / 证据 —」，
但该模块成果早已在集成头里：`eddbdef merge(3.8.1.2)` + `V34__import_validation_report.sql`
+ `593a9fa` 前端页 + 契约重生成。

后果不是汇总数字少 1，而是**编排层会重排已交付模块**：`plan` 读台账 → 认为未开发 →
排进 `toDispatch` → 且 `feat/3-8-1-2` 分支还存在所以「直接复用，不重新切」→
worktree 停在 `16db9ea`，**落后集成头 62 个提交**。
worker 若在旧基线上开工，会重复建端点、重复写迁移，且大概率在
`apps/web/src/lib/api-proxy.ts` 撞车。

已按集成头实际代码恢复该行状态为已交付 + 证据，回收车道。汇总由 已交付 63 → 64。
其余 7 个有 merge 提交的模块台账均正确，**误置只此一处**。

### 2. 3.6.2.1 的 `conflict` 是残留标记（已修）

车道状态是 `conflict`，但 `merge-base --is-ancestor feat/3-6-2-1 integration/module-dev` = YES、
ahead 0、worktree 干净、台账已交付 —— 合并其实成功过（`9ee770c merge(3.6.2.1)`），
`conflict` 是更早一次失败尝试留下的。
`merge-ready` 只捞 `ready`，所以这状态会**永久卡死**该车道。已改回 `merged` 并 gc 回收。

### 3. `plan` 缺 `--session` 导致活车道被误判「未派发」（本轮起修复）

`lane.mjs:452` 的判据是 `state === 'running' && (!l.sessionId || laneIsStale(l))`。
派发子任务用的是 `task` 工具，**它的 sessionId 从来没回写过 lane 记录**，
所以任何正常运行中的车道都满足 `!l.sessionId`，每轮 `plan` 都会把它列进 `toDispatch`
并标 `redispatched: n`。

本轮实测：3.8.2.1 `redispatched` 累计到 2，heartbeat 距当时 0 分钟、worktree 有真实未提交产出
（`export-model.ts`）、3 个提交未并入 —— **照抄 toDispatch 就会两个 writer 同改一个 worktree**，
这正是 14:20 轮「重复派发 + 数据静默回退」事故的成因。

修复方式（无需改脚本）：`plan` 本就支持 `--session`，此前一直没传。
本轮起改为 `plan --lanes N --session <sessionId>`，并在派发后把 `task` 返回的真实
session_id 写回 lane 记录。**该改动不影响失联回收**：判据是 `||`，
`laneIsStale` 单独成立仍会重派，worker 真死了照样 90 分钟后被回收。

### 4. 复用陈旧分支会让 worker 在旧基线开工（已修）

`3.9.2.1` 首次 plan 复用 abandoned 留下的 `feat/3-9-2-1`，worktree 停在 `7ef6372`，
**落后集成头 63 个提交**。已删 worktree + 删分支 + 删 lane 记录，重新 plan，
新 worktree 落在 `d9bcd6e`（behind = 0）。

**判法（每次 plan 完都要做）**：
```bash
git -C .worktrees/<id> rev-list --count HEAD..integration/module-dev   # 必须为 0
```

## 关键 trade-off

- **台账状态由 worker 自报、编排层全信**：本次事故的根源。短期无法重构
  （台账是多车道共用的生成式文档），折中做法是每轮 tick 用
  「merge 提交 vs 台账状态」做一次交叉审计，只在发现不一致时按集成头实际代码回填。
  长期应让 sync 从代码事实反推状态，而不是从蓝图 + 人工填报。
- **陈旧分支复用 vs 重新切**：`plan` 选择复用是为了省 worktree 初始化开销，
  代价是可能落后几十个提交。当前没有版本护栏，靠 tick 人工核 `rev-list --count`。

## 文件清单

- `docs/product/capability-blueprint/module-progress.md` — 恢复 3.8.1.2 行（状态 + 证据）
- 提交 `d9bcd6e docs: 台账刷新到 64 已交付`（sync.mjs 自动提交，含本次回填）
- `.dev-agent/lanes.json` — 3.6.2.1 conflict→merged；删除 3.9.1.1/3.9.2.1 陈旧记录；
  回填 3.9.1.1/3.9.2.1 的真实 sessionId
- 回收 worktree：`.worktrees/3.6.2.1`、`.worktrees/3.8.1.2`、`.worktrees/3.9.1.1`(陈旧)、`.worktrees/3.9.2.1`(陈旧)
- 删除分支：`feat/3-9-1-1`、`feat/3-8-1-2`、`feat/3-9-2-1`（均为无产出的陈旧分支）

## 后续待办

1. `apps/web/src/lib/api-proxy.ts` 的 catalog 白名单仍是**一行约 4000 字符的正则**，
   并行车道结构性冲突点。根治方案是把白名单重构成多行数组/独立常量，
   优于给 merge driver 加「按正则分段求并」新模式（语义风险高）。
2. `plan` 复用陈旧分支缺版本护栏，建议在 `lane.mjs` 里加：复用分支前校验
   `rev-list --count <branch>..<integration>`，落后超过 N 个提交就重切。
3. 全局并发是否从 6 提到 8 仍缺**实测峰值**：本轮三条车道刚开工，还没进入
   `./mvnw verify` 阶段。需在下一 tick（车道跑满 Testcontainers + `next build` 时）采样
   Docker 内存峰值后再定。
4. 17.1.1.1（租户列表）abandoned 171 分钟未被回收，台账仍「未开发」，
   等排到 D7 阶段时重新 plan 即可，无需干预。
