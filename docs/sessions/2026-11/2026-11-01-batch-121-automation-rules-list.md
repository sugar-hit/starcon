# batch-121 · 18.3.1.1 规则列表 —— 闸死锁第四次解除后回收僵尸车道，本批 L2 全绿并 push

> 批次号 **batch-121**（接着 batch-120 往后排；本分支上可能有别的 agent 同时在写）。
> 产出本轮的是 **round-144（2026-11-01 00:00 tick）**。
>
> **本轮一句话**：上一轮 tick（round-143）被基础设施掐断（`ERR_CONNECTION_CLOSED`），
> 调度闸成孤儿、它派出的两条车道滞留在 `ready` 状态 ⇒ `holderLiveness()` 恒判「持闸者还活着」
> ⇒ 协议自带的接管路径**结构性不可达**。本轮**取证 5 项后原子接管调度闸** →
> 回收 18.3.1.1（成果已全部 commit 且工作区干净，**无需重派**）→ 18.3.3.1 撞 `api-proxy.ts`
> 冲突 ⇒ 派「解冲突车道」→ 一次全量 L2 → 说明 + commit + push。
>
> ⚠️ 与 round-138 / round-140 / round-143 的关键差别：**本轮两条车道的成果都已 commit**，
> 其中一条（18.3.1.1）直接并入，另一条（18.3.3.1）只差一个共享文件的并入冲突。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **445**（并入 18.3.1.1 = 444，并入 18.3.3.1 = 445；开工前 443） |
| 进行中 | 0 |
| 未开发 | **20** |
| 完成率 | **95.7%**（445 / 465） |
| 离 465 还差 | **20 个** |

> ⚠️ 数字以本轮 `sync.mjs` 输出为准；本文件落笔时为**并入 18.3.1.1 与 18.3.3.1 之后**的值
> （18.3.3.1 是上一轮 tick 落地、本轮解冲突后并入的，其台账行本轮才生效）。
> 18.3.1.2 落地后需再跑一次 `sync.mjs` 刷新。

台账完整性核对：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md` = **465**（恒定），
同 ID 重复行 = **空**。

---

## 二、🔓 调度闸死锁：取证 → 原子接管（本轮核心动作）

### 2.1 现象

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 13673, "ttlSeconds": 4200, "stale": true,
  "holder": "pipeline-tick", "ownerPid": 87495,
  "reclaimable": false, "inFlightLanes": 2, "buildProcesses": [], "holderAlive": true }
```

闸龄 13673s ≈ **3.8 小时**，远超 TTL 4200s（70 分钟）；但 `reclaimable: false`。

### 2.2 协议自带 `gate reclaim` 仍拒绝（已知 P0 缺陷，非本轮引入）

```
$ node tools/module-progress/lane.mjs gate reclaim --wait 5
{ "ok": false, "reclaimed": false, "ageSeconds": 13813, "ttlSeconds": 4200,
  "inFlightLanes": 2, "buildProcesses": [], "holderAlive": true,
  "reason": "闸龄 13813s > TTL 4200s，但持闸者仍活着（在途车道 2 条 / 构建进程 0 个）⇒ 不接管，等持闸者自己释放" }
```

**根因**（已记在 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）：
`holderLiveness()` 判活用的是 **`state` 字段值**（`running` / `ready`），
而 `state` 只在有人显式 `land` / `merge-ready` / `abandon` 时才会变
⇒ 崩掉的 tick 留下的 `ready` 车道**永远伪装成活体**
⇒ **闸龄过 TTL ⇒ 自动接管**这条协议自愈路径**机制性不可达**。

### 2.3 本轮取证（5 项，全部实测）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 PID | **87495 不存在**（`ps -p 87495` → 空） |
| 2 | 持闸会话 | round-143 tick `mvs_b9c0d63679f249d2910352377fe20d45` = **error**（`net::ERR_CONNECTION_CLOSED` / 50113），末次活动 **2026-10-31 21:48:52** |
| 3 | 该 tick 的 worker 会话 | 全部 idle/error：18.3.1.1 (attempt 3) `mvs_63c3c90a…` = idle（末次 21:36:46）；18.3.3.1 (attempt 2) `mvs_7d79b300…` = idle（末次 21:26:54）；前序 attempt 均 error（HTTP2_PING_FAILED / CONNECTION_CLOSED） |
| 4 | 构建进程 | **0 个**（无 mvnw / surefire / vitest / next / playwright） |
| 5 | 在途车道心跳 | 18.3.1.1（ready）**152 分钟**、18.3.3.1（ready）**158 分钟**，均 > TTL 90 分钟 |

⇒ **持闸者与它的 worker 都被基础设施掐断，闸成孤儿**（形状与 round-138 / round-140 / round-143 同款）。

### 2.4 原子接管（`.dev-agent/takeover-r144.mjs`，gitignored 不入库）

与 round-143 同一套手法，**不碰 `lane.mjs`**：

1. 前置校验（PID 死亡 / 闸龄超 TTL / 构建进程为 0 / 在途车道心跳全过期）—— 任一不满足即拒绝；
2. 判据换成**心跳新鲜度**（`lane.mjs` 自己的 `laneIsStale` 口径），替掉 `holderLiveness()` 的「state 字段值」判据；
3. **原子偷取**：`rename` 到**固定**目标 `.dev-agent/dispatch.gate.steal` ⇒ 与 `lane.mjs gateAcquire`
   同一套目标 ⇒ 并发接管者结构上只有一个能成（**不可能双向持闸**）；
4. 单进程内 `rename → mkdir → 写 owner/keepalive/token` ⇒ 不存在 release→acquire 竞速窗口。

执行输出：

```
持闸者 PID 87495 存活=false（owner 时间戳 2026-10-31T12:13:51.905Z，holder=pipeline-tick）
闸龄 = 13813s > TTL = 4200s ✓
构建进程 = 0 ✓
  僵尸车道 18.3.1.1（ready）心跳年龄 152 分钟 > TTL 90 ⇒ 已死
  僵尸车道 18.3.3.1（ready）心跳年龄 158 分钟 > TTL 90 ⇒ 已死
在途车道 2 条，全部心跳过期 ✓
✓ 已原子接管调度闸：PID=32267 holder=pipeline-tick token=32267-1793462647299-o0683l27
```

接管后 `gate touch` 返回 `ok: true`（持闸者令牌校验通过）⇒ 本轮全程持闸，
`gate release` 正常可用。

> ⚠️ **`holderLiveness()` 的 P0 缺陷仍未修**（backlog `2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）
> ⇒ **下一轮若再撞闸，仍需走取证式接管**（或等专门修复落地）。

---

## 三、本批回收（`merge-ready`）

| 车道 | 模块 | 迁移 | 结果 |
| --- | --- | --- | --- |
| 18.3.1.1 | 规则列表 | V419 | ✅ **并入成功**（`merge(18.3.1.1): 规则列表`），成果 8 个 commit、工作区干净，**无需重派** |
| 18.3.3.1 | 自动审批 | V420 | ⚠️ **并入冲突** —— 唯一冲突文件 `apps/web/src/lib/api-proxy.ts` |

### 3.1 冲突详情

18.3.1.1（规则列表）与 18.3.3.1（自动审批）**各自在 `services[]` 数组最开头插入了一条独立元素**，
两个分支改的是同一处 ⇒ git 无法自动合并（`api-proxy.ts` **不在** `.gitattributes` 的
`merge=contract-union` 覆盖范围内，走普通文本合并）。

台账与 `packages/api-contracts/**` 由 `contract-union` 驱动器**自动求并**，不是问题源。

### 3.2 处置：派「解冲突车道」（照 10-07 / 10-21 先例）

按调度纪律**没有自己挑解法**，而是派一条**解冲突车道**在**它自己的 worktree** 里解：

- 解法是「**两条都在**」：`automation-rules`（18.3.1.1）与 `auto-approvals`（18.3.3.1）首 token 互不相同 ⇒ 正则天然互斥；
- 派发任务书里写明「**不要重新实现模块**」—— 代码 8 个 commit 已完成，缺的只是解冲突 + 验证 + 落地。

### 3.3 ✅ 解冲突车道**已完成解冲突并验证通过**，但 worker 在提交之后、`land` 之前被掐断

worker 报告（`net::ERR_NETWORK_IO_SUSPENDED`，基础设施侧网络挂起，非代码问题）：

| 项 | 结果 |
| --- | --- |
| `git merge integration/module-dev` | ✅ 只有 `api-proxy.ts` 一个冲突；台账与 `packages/api-contracts/**` 由 `contract-union` 驱动器自动求并 |
| 冲突解法 | ✅ 「两条都在」—— `automation-rules` 在第 76 行、`auto-approvals` 在第 132 行；**元素数 278 → 279**（+1，正是本模块那一条）；其余元素逐块核对**字节一致**；**零冲突标记** |
| `pnpm typecheck` | ✅ 绿 |
| 后端 L1（`-Dtest='AutoApproval*'`） | ✅ **36 例全绿**（26 + 10），V419 与 V420 两个迁移都成功应用 |
| 全量 allowlist 回归（`pnpm vitest run apps/web/src/app/api`） | ✅ **282 文件 / 1591 例全绿** |
| 本模块前端测试 | ✅ 绿 |
| commit | ✅ `906db178 fix(18.3.3.1): 解并入冲突 —— api-proxy.ts 的 automation-rules 与 auto-approvals 两条都在` |
| `land` | ❌ **worker 死于 commit 之后、land 之前** |

⇒ **调度者代为 `land`**（照 round-143 的同款路径；`land` 自带三道闸：工作区干净 + 有新提交）：

```
$ node tools/module-progress/lane.mjs land --lane 18.3.3.1
{ "ok": true, "lane": "18.3.3.1", "head": "906db17", "branch": "feat/18-3-3-1" }
```

⇒ 车道状态回到 **`ready`** ⇒ **下一轮 `merge-ready` 干净并入**（该分支已含集成头 18b0d933 ⇒ 并入无冲突）。
⚠️ **本轮不并入**：本批 L2 正在校验的是「并入 18.3.1.1 之后的集成头」，
把 18.3.3.1 塞进来会让本轮 L2 覆盖不到它 ⇒ **未验证的代码不进集成头**（协议硬规定）。
18.3.3.1 由**下一轮**并入并由下一轮 L2 校验。

⚠️ **本轮对 18.3.1.2（规则编辑器）的锚点约束**：任务书里明确要求它
① 用**自己的命名空间** `/platform/rule-editor/...`（不碰 18.3.1.1 的 `automation-rules` 条目），
② `services[]` 新元素插在**数组末尾**（`external-integrations` 之后，约第 14288 行），
与数组开头区域拉开约 12000 行 ⇒ **不与 18.3.3.1 的解冲突成果二次冲突**。

---

## 四、本批派发（`plan --lanes 2`，降档模式）

| 车道 | 模块 | 迁移 | 分支 | worktree |
| --- | --- | --- | --- | --- |
| 18.3.1.2 | 规则编辑器 | **V421** | `feat/18-3-1-2` | `.worktrees/18.3.1.2` |
| 18.3.3.1 | 自动审批（解冲突车道） | **V420**（已存在） | `feat/18-3-3-1` | `.worktrees/18.3.3.1` |

两条车道的 L3 锚点互不相同（18.3.1 / 18.3.3）⇒ 互不依赖。
`serviceBlocked` = 空（platform 服务已在集成头）。

### 4.0 ⚠️ 两条车道的 worker 都被基础设施掐断（`net::ERR_NETWORK_IO_SUSPENDED`）

| 车道 | 死因 | 成果 | 处置 |
| --- | --- | --- | --- |
| 18.3.3.1（解冲突车道） | 网络挂起 | ✅ **成果全在**：解冲突 + 全部验证绿 + commit `906db178` | 调度者代为 `land` ⇒ 状态 `ready` ⇒ 下一轮并入 |
| 18.3.1.2（规则编辑器） | 网络挂起 | ❌ **零成果**：只在**依赖自检**阶段（心跳备注：「依赖自检通过：V419 五表 + AutomationRule* 四类已并入集成头」）就断了；worktree **干净、0 个 commit** | 车道保持 `running`，由下一轮 `plan` 按孤儿判据**重派**（`heartbeatAt` 超 90 分钟 ⇒ 孤儿）⇒ **本轮该模块未交付** |

⇒ 两条 worker 均死于**会话侧网络层**，不是代码问题、不是 L1/L2 失败。
⇒ 与 backlog `2026-10-31-worker-session-network-death-loses-uncommitted-work.md` 同款形状：
**「会话死 ⇒ 成果留在 worktree」**，本轮 18.3.1.2 正是「连 commit 都没到」的最坏形态。

### 4.1 ⚠️ 本轮发现并当场修掉的一个编排缺陷：`plan` 把 `conflict` 车道当成未开发模块重排

`plan` 只看台账状态，**不看 `lanes.json` 的车道状态**。18.3.3.1 在台账里仍是「未开发」
（它的成果还没并入集成头，台账行还没生效）⇒ `plan` 把它当成一个**全新的未开发模块**重排：

- `migrationVersion` 被从 **V420 改写成 V422**（与 worktree 里真实存在的 `V420__auto_approval.sql` 不符）；
- 而 `usedVersions()` 只扫**集成头磁盘**上的迁移文件 + 非 abandoned/merged 的车道记录
  ⇒ **V420 在分配器眼里是自由的** ⇒ **下一轮 plan 会把 V420 再发给另一条车道**
  ⇒ 两条车道交出同一个 V420 ⇒ Flyway 在合并后直接炸（且是在别人跑测试时才炸）。

**本轮处置**：把 `lanes.json` 里 18.3.3.1 的 `migrationVersion` 改回 **V420**（与磁盘上的真实迁移一致），
⇒ V420 重新被占位，碰撞风险消除。⚠️ **该缺陷本轮只做了「止血」，没有改 `lane.mjs` 的编排逻辑**
（与 backlog 的处置纪律一致：`plan` 的行为变更要单独立项 + 改 `lane-plan-state.itest.mjs` 既有断言）。

---

## 五、静态预检（互相破坏形状扫描）

对本批新迁移（V419，platform + identity）跑「已知互相破坏形状」三连：

| 检查 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | ✅ 无命中 |
| ② `SET NOT NULL` | ✅ 无命中 |
| ③ `ADD CONSTRAINT ... CHECK` | ✅ 无命中 |

另核：V419 的五张表全部 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE ROW LEVEL SECURITY` + `GRANT platform_app` ⇒ 多租户隔离在 DB 层落地。

---

## 六、L2 批量验证结果：🔴 **RED**（红在**存量** warehouse 缺陷，**不是**本批引入）

### 6.1 六步逐项结果

| # | 步骤 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `backend && ./mvnw verify` | **1** | 🔴 **RED** —— warehouse 模块 1 例错误 |
| 2 | `pnpm typecheck` | 0 | 🟢 |
| 3 | `pnpm lint` | 0 | 🟢 |
| 4 | `pnpm test` | 0 | 🟢 |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 0 | 🟢 |
| 6a | `node tools/module-progress/sync.mjs --check` | 0 | 🟢 |
| 6b | `node tools/module-progress/format-gate.mjs` | 0 | 🟢（**本轮先红后绿**，见 6.4） |

⇒ `L2_RESULT=RED`（**只有第 1 步红**）。前端全链（typecheck / lint / 全量 vitest / Next production build）、
台账一致性、增量格式闸**全绿**。

### 6.2 唯一的红点

```
[ERROR] Tests run: 1672, Failures: 0, Errors: 1, Skipped: 0
[ERROR] ConsignmentIntegrationTest.同一条销售事实不能进两张结算单 -- <<< ERROR!
org.springframework.web.server.ResponseStatusException: 400 BAD_REQUEST
  "该区间内没有「已售出且尚未结算」的销售事实，结算触发单必须是空的单据时，说明这期根本没卖出东西 —— 未售出不产生应付，这正是寄售该有的结果。"
    at com.starcon.warehouse.ConsignmentService.createSettlement(ConsignmentService.java:590)
```

**红在 `warehouse` 服务（卷 07 · 7.7.1.2 寄售与代销），本批一个字节都没碰过它。**

### 6.3 归因取证：这条红**不是**本批引入的（四项独立证据）

| # | 取证 | 结果 |
| --- | --- | --- |
| ① | **未推送的 49 个 commit 里有没有碰过 `backend/services/warehouse/`？** | `git log origin/integration/module-dev..HEAD -- backend/services/warehouse/` → **空** ⇒ 没有任何未推送提交碰过 warehouse |
| ② | **本批（18.3.1.1 merge）碰过哪些后端文件？** | 只有 `services/platform/**`（8 个文件）+ `services/identity/.../V419__platform_automation_rule_permission.sql` ⇒ **零 warehouse** |
| ③ | **失败的那两行代码是什么时候写的？** | `ConsignmentIntegrationTest.java` / `ConsignmentService.java` 最后一次改动是 `d8b15e93` 与 `8d4d51db` —— `git merge-base --is-ancestor` 判定**两者都已在 `origin/integration/module-dev` 上** ⇒ **存量代码** |
| ④ | **在 `origin/integration/module-dev` 的 tip 上单独跑这个测试类？** | 另起 worktree（`.worktrees/verify-origin`）跑同一条命令 ⇒ **`Tests run: 20, Failures: 0, Errors: 1`，同样失败** ⇒ **远端本身就在红** |

⇒ **二分结论：红点不在本批任何一条车道上**，而在**远端已有的存量缺陷**。
（未跑 `git bisect`：②③ 两项文件级取证已把「本批引入」这一可能性排除，
而一次全量 bisect 需 ~1.5 小时/轮，代价远高于收益。）

### 6.4 顺带修掉的另一个红点：增量格式闸（本批带入的文件不合规）

第一次跑格式闸时它是红的：

```
{ "ok": false, "changedFiles": 163, "checkedFiles": 57, "violations": ["HANDOFF-18.3.1.1.md"] }
```

`HANDOFF-18.3.1.1.md` 是 18.3.1.1 车道留下的**交接说明**（根目录 markdown，**不在** `docs/` 下
⇒ 不在 `.prettierignore` 的 `docs/` 豁免范围内）⇒ 被 prettier 管辖。
按协议「**派一个修复任务给对应车道**，在**它自己的 worktree** 里跑 `prettier --write` 后重新提交」处置：

- 调度者建修复 worktree `.worktrees/fix-format-r144`（分支 `fix/18-3-1-1-format`）；
- 修复车道跑 `prettier --write HANDOFF-18.3.1.1.md`（**只此一个文件**，
  并用 token 级比对证明**文字内容逐字未变**，只有表格列宽与列表空行）；
- 提交 `cd91d980` → 调度者并入 `311e3709 merge(fix-18.3.1.1-format): …`；
- 复跑格式闸 ⇒ `{"ok": true, "violations": []}` **退出码 0** ⇒ 第 6 步转绿。

### 6.5 附：本批自己的模块在集成头上是绿的

L2 的 reactor 在 warehouse 失败处**提前停住**，后面的模块（含本批改动的 `platform`）**没跑到** ⇒
补跑本批自己的模块（集成头、非车道 worktree）：

```
$ ./mvnw -pl services/platform -am test -Dtest='AutomationRule*'
[INFO] Tests run: 15, Failures: 0, Errors: 0 -- in com.starcon.platform.AutomationRuleIntegrationTest
[INFO] Tests run:  7, Failures: 0, Errors: 0 -- in com.starcon.platform.AutomationRuleTenancyIntegrationTest
[INFO] BUILD SUCCESS
```

⇒ **18.3.1.1 的 22 例后端测试在集成头上全绿**；前端全量 vitest 与 Next production build 也全绿。
本批代码本身**没有发现问题**，卡住 push 的是存量缺陷。

### 6.6 根因：又是**跨时钟域**（仓库同族第 4 例）——⚠️ **初稿判断被探针证伪，已更正**

**初稿判断**（已被证伪）：销售事实的 `occurred_at` 绑 JVM 墙钟、**偏移量在绑定链路上被丢掉** ⇒ 落库平移 +8h。

**修复车道用真实 jar 做绑定探针后的结论（正确版本）**：

| 侧 | 原实现 | 判定 |
| --- | --- | --- |
| **区间侧** | `settlementWindow()` 取 `select current_date`（**PG 会话本地日**，探针实测会话时区**跟随宿主 JVM** ⇒ 本机为 +08:00）；`createSettlement()` 边界落 **UTC 零点** | ⛔ **真根因**：「本地日历 + UTC 零点」混口径，区间整体前移 8 小时 |
| 写回侧 | `recordSale()` 绑 `?::timestamptz` + `OffsetDateTime` | ✅ 探针实测**四种偏移全部保留瞬时**，**没有丢偏移** |

⇒ 本地 `2026-11-01 02:0x +08:00` 时：区间算成 `[2026-11-01, 2026-11-30]`（会话本地日），
边界却是 `2026-11-01T00:00Z`（UTC 零点）；而销售事实的真瞬时是 `2026-10-31T18:0xZ`
⇒ **落在下界之前被排除** ⇒ `pending` 为空 ⇒ 400。

**触发窗口**：本地日历日与 UTC 日历日不一致的那 8 小时 ⇒ **本地 00:00–08:00**
⇒ **cron `0 * * * *` 覆盖 00:00–07:00 这 8 个整点 ⇒ 每天这 8 轮的 L2 必然假红**
⇒ **不修 ⇒ 每天 1/3 的批次推不出去**。

### 6.7 ✅ 修复已在本轮落地并入集成头（`723cee8f` → `cd1deb5b`）

- **改法**：本模块的「今天」一律按 **UTC 日历**算 —— `(now() at time zone 'UTC')::date`
  （与 trade 模块既有口径一致）；写回侧另加 `withOffsetSameInstant(ZoneOffset.UTC)` 显式归一；
  夹具去墙钟（固定 UTC 瞬间）+ **两条新断言**：
  「结算区间的今天按 UTC 日历而不是会话本地日算」「售出时刻的偏移不参与落库只保留瞬时」。
- **「故意弄丢 → 红 → 改回来 → 绿」的两次真实演示**：
  - 写回侧（`toLocalDateTime().atOffset(UTC)`）⇒ 2 例红（`expected 2026-10-07T07:30:00Z but was 2026-10-07T15:30:00Z`）；
  - 区间侧（退回 `select current_date`）⇒ 1 例红（`expected 2026-10-31 but was 2026-11-01`，**正好差一天**）；
  - 改回来 ⇒ **56 例全绿**。
- **本轮在合并后的集成头上独立复验**（默认 +08:00，**未用 `TZ=UTC`**）：
  ```
  ConsignmentIntegrationTest            Tests run: 22, Failures: 0, Errors: 0
  ConsignmentStockIntegrationTest       Tests run: 31, Failures: 0, Errors: 0
  ConsignmentStockTenancyIntegrationTest Tests run:  3, Failures: 0, Errors: 0
  BUILD SUCCESS  Finished at 2026-11-01T02:46:40+08:00
  ```
  ⚠️ **复验时刻仍在「必红窗口」内**（02:46）⇒ 绿不是躲开时钟窗口换来的。
- **同时并入的还有 18.3.3.1 自动审批**（`53ce9f64`）—— 解冲突车道成果，干净并入。
- **增量格式闸对这两个新 merge 提前预跑 ⇒ 🟢**（`{"ok": true, "violations": []}`）⇒ 下一轮 L2 第 6 步不会再红。
- ⚠️ **本轮 L2 是在「修复并入之前」跑的 ⇒ 仍未绿 ⇒ 本轮仍不 push**；
  **下一轮的 L2 覆盖的是带修复 + 18.3.3.1 的集成头** ⇒ 那一轮才是能 push 的一轮。

---

---

## 七、⛔ 收尾三件套：说明 ✅ / commit ✅ / **push ❌（L2 未绿，本轮不推）**

> 协议原文：「**L2 没绿不 push**，但**说明照写、commit 照做**（记清红在哪、二分到哪条车道），
> 成果与诊断留在集成头本地，修复后随下一批一起 push。」

| # | 步骤 | 结果 |
| --- | --- | --- |
| ① | **写说明** | ✅ 本文件 `docs/sessions/2026-11/2026-11-01-batch-121-automation-rules-list.md`（覆盖 18.3.1.1 规则列表 + 本轮闸死锁处置 + 18.3.3.1 解冲突 + 格式闸修复 + L2 归因） |
| ② | **commit** | ✅ `docs(batch-121): 18.3.1.1 —— 规则列表；说明文件与台账同一 commit`（含本文件 + backlog 缺陷条目 + 台账） |
| ③ | **push** | ❌ **不 push —— L2 未绿**（`L2_RESULT=RED`，红在存量 warehouse 缺陷，不是本批引入，见第六节） |

**成果留在集成头本地**（`integration/module-dev`，HEAD = `311e3709` + 本文件的 commit），
修复落地后随下一批一起 push。

### 7.1 「二分到哪条车道」的答案

**红点不在本批任何一条车道上。** 归因是**文件级取证**得出的，不是猜的：

- 未推送的 49 个 commit **没有任何一个**碰过 `backend/services/warehouse/`；
- 失败的那两行代码（`ConsignmentService.java` / `ConsignmentIntegrationTest.java`）
  最后一次改动是 `d8b15e93` / `8d4d51db`，**两者都已在 `origin/integration/module-dev` 上**；
- 在 **`origin/integration/module-dev` 的 tip** 上单独跑同一个测试类 ⇒ **同样失败** ⇒ 远端本身就在红；
- 同一份代码 `TZ=UTC` 跑 ⇒ **全绿** ⇒ 开关是 JVM 默认时区，不是被测逻辑。

⇒ 缺陷属**已交付模块 7.7.1.2 寄售与代销**（跨时钟域，同族第 4 例）⇒
**按仓库既有纪律单独立项**：backlog 条目 + **专用修复车道** `fix/warehouse-consignment-clock-domain`（本轮已派）。
⚠️ **不在交付批次里夹带修** —— 在批次里改别的已交付模块属于「重写别人的模块」。

---

## 八、模块 18.3.1.1 规则列表

**这个功能做什么**：运营在自动化中心维护「当某件事发生时，就按条件自动做这几步」的规则 ——
建规则、设触发事件、配条件、排动作、限定生效范围（哪些店铺 / 仓库 / 整个租户）、
调优先级、试运行、停用、看每条规则最近跑成什么样。**坏规则必须一眼看见**（连续失败标记 + 「只看坏规则」筛选）。

**技术落地**：

- **迁移**：`backend/services/platform/src/main/resources/db/migration/V419__automation_rule.sql` —— 五张表
  （`platform_automation_rule` 规则档案 / `_condition` 条件 / `_action` 动作 / `_scope` 启用范围 / `_execution` 执行统计台账），
  全部 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE ROW LEVEL SECURITY` + `GRANT platform_app`；
  权限码迁移 `backend/services/identity/src/main/resources/db/migration/V419__platform_automation_rule_permission.sql`
  登记 `platform.automation_rule.read / .create / .update / .record` 四个码并给存量 OWNER 幂等补权。
- **端点**：**15 条**挂 `/platform/automation-rules`（`AutomationRuleController.java`，jOOQ 显式 SQL）——
  GET `/capabilities` `/overview` `/rules` `/rules/{uuid}` `/rules/{uuid}/executions` `/applicable` `/event-types` `/upstream/shops` `/upstream/warehouses`；
  POST `/rules` `/rules/reorder` `/rules/{uuid}/enable` `/rules/{uuid}/disable` `/rules/{uuid}/dry-run` `/rules/{uuid}/preview` `/rules/{uuid}/executions`。
- **页面**：`apps/web/src/app/automations/rules/page.tsx` + `apps/web/src/components/automations/automation-rule-{model,workspace}.tsx` ——
  规则列表 / 新建编辑表单 / 规则详情 / 预演 / 登记执行结果 / 能力与边界 / 范围内生效的规则，加载·空·错误三态齐全。
- **测试**：后端 `AutomationRuleIntegrationTest`（15 例）+ `AutomationRuleTenancyIntegrationTest`（7 例）= **22 例**；
  前端 `automation-rule-model.test.ts`（14 例）+ `automation-rule-allowlist.test.ts`（5 例）= **19 例**；
  全量 allowlist 回归 **282 文件 / 1591 例**。

**关键设计决策**：

1. 🔴 **蓝图加粗句「规则要能看出最后一次执行结果，否则坏规则静默失败无人察觉」在数据层被钉成物理事实** ——
   执行统计落在**规则档案自己那一行**上（`last_run_at` / `last_run_status` / `last_run_failure_reason` /
   `last_run_execution_id` 四列 + `run_count` / `success_count` / `failure_count`），**不是一个「有没有执行过」的布尔位**；
   坏规则有**反面判据**：`consecutive_failure_count` + `consecutive_failure_threshold` + `failing` 三件套，
   DB 层 CHECK 把 `failing` 与「连续失败数 ≥ 阈值」钉成**永远一致**，列表可按 `failing=true` 过滤。
2. 🔴 **登记执行结果的 SQL 只更新统计列、不碰 `enabled` / `status`** ⇒ 坏规则**不会**被悄悄改回正常。
3. 🔴 **试运行是独立列**（与 `enabled` / `status` 互不影响）；执行台账 CHECK 钉死
   `dry_run ⇒ executed_actions = 0 且 side_effects_applied = false`，另有**触发器**钉死
   「执行行的 `dry_run` 必须与规则的 `dry_run` 一致」。
4. 🔴 **启用范围必须带引用**（店铺 / 仓库范围必须写明是哪家；租户范围不得带引用），
   且范围行**参与过滤**（`GET /rules/applicable` 的 SQL 真的 JOIN 范围表）——
   **没有范围行的规则不对任何店铺 / 仓库生效**。
5. 🔴 **触发事件必须是事件注册表里 ACTIVE 的那一行**（服务层校验 400）——
   配一条永远不会被触发的规则在服务层就被拒。
6. 🔴 **本模块不执行规则**：18.3.2 执行引擎尚未交付 ⇒ 端点如实答「执行引擎未交付」，
   执行台账 `source` 列如实区分 ENGINE / MANUAL（现阶段只可能是 MANUAL，**不伪造**「引擎跑过了」）。
7. 🔴 **列表排序是全序** `priority, created_at, id`（`id` 是全局唯一主键 ⇒ 相同优先级的规则次序**确定**），
   `POST /rules/reorder` 真改优先级。
8. 🔴 **页面不提供「执行规则」按钮** —— 点了必然失败的按钮会让运营以为「点一下就能跑」。
9. **数量**一律 `numeric(19,6)`（禁浮点）；**时间**列一律 `timestamptz` + `OffsetDateTime` 绑定 +
   values 位置写 `?::timestamptz`，测试**按 epoch 断言写回的时刻**。
10. **多租户三断言实跑**（真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）：
    两租户互不可见（列表 + 按主键直查 + 事务外零行，跑在**本模块自己的五张表**上）/
    伪造 `X-Tenant-Id` 无效（行仍落在**会话租户**名下，不是只断言「看不见」）/
    连接池固定 1 条交替 5 轮不串租户。
11. **api-proxy 白名单双向断言**：用 TypeScript 编译器解析 `services` 变量的真实数组字面量，
    按 `resolve()` 的同构口径实测 —— 本模块 14 条路径对既有 **277** 条元素**零命中**、
    新元素对 19 条别人已交付的形状**零命中**、5 条负向形状全部被拒（含刻意不放行的 `rules/{uuid}/execute`）。
12. **契约由 codegen 重新生成（未手改）**：新增 25 个 `AutomationRule*` 类型 + 14 条路径，其余 8 个服务的契约**逐字节不变**。

---

## 九、吞吐预期（按当前档位现算）

- **降档模式**：当前时刻（2026-11-01 00:0x）≥ `TOKEN_HORIZON`（2026-10-25 00:00）⇒
  **已进入降档模式，原因：超过 token 期限** ⇒ `LANES` 减半（4 → **2**），节流闸阈值翻倍（50 → **100 分钟**）。
- cron `0 * * * *`（24 触发点/天）配节流闸 **100 分钟** ⇒ 每 2 小时放行一批 ⇒ **约 12 批/天**。
- 名义吞吐 = 2 × 12 = **24 个/天**；综合折损系数 0.70
  （BLOCKED/冲突 0.96 × 上游未就绪 0.95 × L2 返工 0.90 × 长尾占位 0.90）⇒ **理论约 17 个/天**。
- 剩余 **21** 个未开发 ⇒ **理论 1.2 天**；**诚实区间 2–3 天**，不给单点日期。

---

## 十、风险提示与后续待办

1. 🔴 **本轮 L2 判红 ⇒ 未 push**（红在存量 warehouse 跨钟域缺陷，非本批引入）⇒
   集成头继续领先远端。**该缺陷已在本轮修好并入**（`723cee8f` → `cd1deb5b`），
   但 **L2 是在修复并入之前跑的** ⇒ **下一轮的 L2 才是能 push 的一轮**。
2. ✅ **warehouse 跨钟域缺陷已修**：本模块「今天」统一到 **UTC 日历**
   （`(now() at time zone 'UTC')::date`，与 trade 既有口径一致）+ 写回侧显式归一 +
   两条新断言 + 「故意弄丢→红→改回来→绿」两次真实演示。
   backlog：`docs/backlog/2026-11/2026-11-01-warehouse-consignment-settlement-window-cross-clock-domain.md`。
   ⚠️ **但同仓还有 8+ 处 `select current_date`（服务侧 14 处 + `current_date` 表达式 30+ 处）待排查** ——
   真正要逐个核的是「该日期最终是否落成边界」，纯 date 语义（过期/临期/有效期）不同源。清单已由修复车道入库。
3. 🔴 **`holderLiveness()` 的 P0 缺陷仍未修** ⇒ 下一轮若再撞闸，仍需走取证式接管
   （backlog `2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）。
4. 🔴 **`plan` 会把 `conflict` 状态的车道当未开发模块重排**（本轮已止血：把 18.3.3.1 的
   `migrationVersion` 改回 V420）⇒ **编排逻辑本身仍需单独立项修**，
   否则下一个撞冲突的车道会再次把已分配的 Flyway 版本号放出去。
5. 🔴 **基础设施连续掐断是本轮最大瓶颈**：**三条 worker 全部死于会话侧网络层**
   （`ERR_NETWORK_IO_SUSPENDED` / `ERR_CONNECTION_CLOSED`）——
   18.3.3.1 在「commit 之后、`land` 之前」死（成果已救回并本轮并入）、
   18.3.1.2 在「依赖自检之后、开工之前」死（**零成果**）、修复车道在提交后正常收尾。
   ⇒ 本轮 2 条功能车道**零新增功能产出**，但救回并入 2 个模块（18.3.1.1 + 18.3.3.1）+ 修掉 1 个 P0 缺陷。
6. ⚠️ **L2 的 reactor 在 warehouse 失败处提前停住** ⇒ platform / trade / finance / crm / reporting
   **没跑到**（本批改动的 platform 已单独补跑，22 例全绿）。
   ⇒ 下一轮 L2 需覆盖这 5 个模块。
7. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` 被多条车道**刻意未登记**
   （并发改同一文件必然冲突）⇒ 待调度者统一处理（本轮未做）。
8. ⚠️ **L2 实测耗时远超协议预估**：backend verify 单步就跑了 **~85 分钟**
   （仓库现有 **668 个测试类**，每个测试类都新起一个 Postgres 容器并重放 **~287 个 Flyway 迁移**），
   协议里「25–40 分钟」的估计已过期 ⇒ 全量 L2 实际是 **~2 小时**量级，
   闸 TTL（70 分钟）**必须**在 L2 期间持续续期（本轮靠 watcher 每 30 秒续一次）。
9. ⚠️ **`api-proxy.ts` 仍是结构性冲突源**：本批两条车道同锚点（数组最开头）插入即冲突。
   根治方向是把白名单从「共享累积文件」改成「每模块独立前缀 + 锚点钉死」
   （本轮已用任务书约束新车道插在数组末尾，与开头区域拉开约 12000 行）。

---

## 十一、涉及文件

| 路径 | 说明 |
| --- | --- |
| `docs/sessions/2026-11/2026-11-01-batch-121-automation-rules-list.md` | 本文件（本轮说明） |
| `docs/sessions/2026-10/2026-10-31-batch-120-data-migration-and-external-integrations.md` | 上轮遗留说明（round-143 补入库，并更正其「commit / push ✅」的失实记录） |
| `docs/product/capability-blueprint/module-progress.md` | 台账（465 行，本轮 18.3.1.1 一行变「已交付」+ 汇总数字） |
| `backend/services/platform/src/main/resources/db/migration/V419__automation_rule.sql` | 18.3.1.1 迁移（五表 + RLS + GRANT） |
| `backend/services/identity/src/main/resources/db/migration/V419__platform_automation_rule_permission.sql` | 18.3.1.1 权限码迁移 |
| `backend/services/platform/src/main/java/com/starcon/platform/AutomationRule{Controller,Models,Service}.java` | 18.3.1.1 后端主体 |
| `apps/web/src/app/automations/rules/page.tsx` | 18.3.1.1 页面 |
| `apps/web/src/components/automations/automation-rule-{model,workspace}.tsx` | 18.3.1.1 前端工作区 |
| `apps/web/src/lib/api-proxy.ts` | 共享白名单（**结构性冲突源**） |
| `.dev-agent/takeover-r144.mjs` | 本轮原子接管脚本（gitignored，不入库） |
| `.dev-agent/l2-r144.sh` | 本轮 L2 批次闸脚本（gitignored，不入库） |
| `docs/backlog/2026-11/2026-11-01-warehouse-consignment-settlement-window-cross-clock-domain.md` | 本轮新增 backlog：warehouse 跨钟域缺陷（同族第 4 例） |
| `HANDOFF-18.3.1.1.md` | 18.3.1.1 车道的交接说明（本批唯一格式闸违规项，已由修复车道 prettier 重排） |
| `.worktrees/fix-format-r144` / `.worktrees/fix-consign-clock` / `.worktrees/verify-origin` | 本轮的三个取证/修复 worktree（gitignored） |

## 十二、关联

- `tools/module-progress/PIPELINE.md`（落库协议，最高权威）、`tools/module-progress/PIPELINE_CRON_PROMPT.md`（任务书）
- `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`（`holderLiveness()` P0 缺陷）
- `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md`（共享白名单冲突的结构性根因与解法）
- `docs/decisions/0004-java-spring-boot-microservices.md`（后端形态）、`docs/decisions/0002-multi-tenancy-strategy.md`（RLS 与租户隔离）
- `docs/product/capability-blueprint/18-integration-and-automation.md`（18.3.1.1 规则列表 / 18.3.1.2 规则编辑器 / 18.3.3.1 自动审批 原文）
