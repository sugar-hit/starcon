# identity 服务 L2 红点：全套 639 例中 38 例 403，根因是跨测试状态污染

- 创建: 2026-10-28
- Owner: **流水线侧**（派一条修复车道；非新蓝图模块）
- 优先级: **P0** —— 阻塞集成头全部 104 个未推送提交
- 关联: `docs/sessions/2026-10/2026-10-28-1800-pipeline-round122-dispatch-two-lanes-identity-l2-red.md` ·
  `tools/module-progress/PIPELINE.md`（L2 批次闸 / 红点处置） ·
  `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`（push 被分叉阻塞，P0 叠加）

---

## 背景

集成头的 L2 批次闸长期红，红点固定落在 `identity` 服务：

```
.tmp/l2-batch107b.status  →  backend_verify=1   （14:05:48 → 14:42:16）
.tmp/l2-batch108.status   →  backend_verify=1   （16:05:58 → 16:35:10）
identity 服务：Tests run: 639, Failures: 38, Errors: 0, Skipped: 0   → BUILD FAILURE
```

⇒ 按 `PIPELINE.md`「**L2 没绿绝对不 push**」，集成头 104 个提交（含 13.3.1.1 / 13.3.1.2 /
13.3.2.1 / 13.3.2.2 等已交付模块的实现）**全部无法发布**，且这批交付**只存在于本机**。

2026-10-28 第 122 轮做了取证，把红点从「不明」推到「可派修复任务的形状」。

### 四个取证（都是实测，不是推测）

| # | 取证方式                                        | 结果                                                                                       |
| - | ----------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 1 | 失败签名（`.tmp/l2-batch108.log`）              | **34 × `expected:<200> but was:<403>`** + 1 × `expected:<400> but was:<403>`，**0 errors** |
| 2 | batch108 是**空载隔离复跑**（runner.log 记录 `labeled PG containers: 0` / `load 1.75`） | **仍然红** ⇒ 与机器负载、PG 抢资源无关 |
| 3 | `git log e6f443ae..HEAD -- backend/services/identity/` | **仅 1 个提交** `32a16a6a`，且是**测试夹具**提交（改时间基准，修的是另一种「7199 vs 7200」取整形状） |
| 4 | **单类隔离复跑**（本轮实测，空载 0 PG）        | `ApprovalTodoIntegrationTest` → **Tests run: 12, Failures: 0**（37.18s，BUILD SUCCESS）     |

⇒ **同一个测试类，单独跑 12/12 绿；在全套 639 例里 11/12 红。**

### 失败类清单

**batch108（38 failures，合计 = 38）** — 主因，**每次都红的确定性部分**：

| 类                                    | 失败数 |
| ------------------------------------- | ------ |
| `ApprovalTodoIntegrationTest`         | 11     |
| `ShortcutTemplateIntegrationTest`     | 6      |
| `ApprovalInboxIntegrationTest`        | 6      |
| `ApprovalInboxTenancyIntegrationTest` | 4      |
| `ApprovalRecordIntegrationTest`       | 3      |
| `ApprovalRecordTenancyIntegrationTest`| 3      |
| `DepartmentLeaderIntegrationTest`     | 2      |
| `AlertRuleIntegrationTest`            | 2      |
| `GlobalSearchRecentIntegrationTest`   | 1      |

**batch107b 额外出现、batch108 未复现**（合计 4）—— 这一层是**抖动**，不是主因：
`PlatformFlagIntegrationTest`(1) · `LedgerBookIntegrationTest`(1) · `OperationLogIntegrationTest`(2)。

⇒ batch107b 合计 34、batch108 合计 38，**两边条数不同** ⇒ 除确定性主因外还有一层抖动；
定位污染源时**以 batch108 的 9 个类为主因清单**，另外 3 个类作为抖动观察项。

### 机制假设（待修复车道证实）

同一 Testcontainers 容器 + 同一租户下，**另一个 identity 测试类污染了共享状态**，
典型是三者之一：

1. **固定标识碰撞** —— 某个测试类用了写死的 `tenant_id` / `actor_id` / 权限码 / 角色 id，
   与后续测试类**共用**同一行，删改时把后者的授权记录一起干掉了 ⇒ 403。
2. **事务未回滚** —— 某个测试类开了事务但没 rollback/finally 清理，
   在同一 PG 容器里留下未提交/已提交的中间状态（锁 ⇒ 后续类等待 ⇒ `ApprovalInboxIntegrationTest`
   在 batch108 耗时 **522.0s**，batch107b 只用 11.59s）。
3. **全局缓存 / 单例状态** —— 生产侧或测试基类里的 static / Spring 单例缓存跨类残留。

### 已排除的方向（不要再重复查）

- ❌ 机器负载 / Testcontainers PG 抢资源（空载隔离复跑仍红）
- ❌ identity **生产代码**回归（生产代码自 `e6f443ae` 起 0 改动）
- ❌ `ApprovalTodoIntegrationTest` 自身的夹具（隔离跑 12/12 绿）
- ❌ 共享库 `backend/libraries/` 变更（0 提交）

---

## 待办内容

- [ ] **派一条修复车道**（非新蓝图模块，目标模块 ID 借用 `fix-identity-l2-red` 这类修复名），
      在**空载**下复现 identity 全套 639 例 ⇒ 拿到复现日志
- [ ] 用 **surefire 执行顺序二分**（`.tmp/l2-batch108.log` 里已有执行顺序，可直接当基线）
      找出污染源测试类：把嫌疑类与失败类**同容器同 JVM** 一起跑，验证能否复现 403
- [ ] 修掉碰撞（**优先改测试夹具，不改生产语义** —— 与 `32a16a6a` 同一原则：
      为迁就测试造错的数据去改业务语义，会把真问题掩盖掉）
- [ ] **验证「断言仍有牙齿」**：改坏锚点必须红，改回必须绿；并**连跑 ≥2 次**确认稳定
- [ ] 修复后**重跑 identity 全套 639 例** ⇒ 全绿
- [ ] 同步更新 `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`
      的待办 1（L2 绿窗口即可 push）

## 影响面

- **阻塞**：集成头 push（104 个提交只在本机）⇒ 同时阻塞
  `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`
- **不阻塞**：车道开发（13.4.1.1 / 13.4.2.1 走 `reporting` 服务，与 identity 无交集）、
  本地分支与 worktree
- **不阻塞其他服务**：`identity` 自带数据库，跨服务无 FK / 共享视图 ⇒ 污染只在 identity 套件内部
- **风险**：若长期不修，每一轮 L2 都会红 ⇒ 每轮都触发「二分定位 + 修复 + 重跑」的返工折损
  （组批协议里 ×0.90 那一项），且 push 积压持续增长
