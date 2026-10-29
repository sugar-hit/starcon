# 2026-10-28: 集成头同步 main 第 93 轮 —— 冲突未并入（**26 个**，墙比第 92 轮宽了 15 个）＋ 孤儿 tick 台账残骸取证

> 本轮 11:15 起跑、**11:23:45 取到闸**（TTL 接管）、11:27 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **26 个**（25 add/add + 1 content）——比第 92 轮的 11 个**多 15 个**。
> main tip 仍是 `823ebca5`（同一颗 squash），**已连续 14 轮（第 79–93 轮）撞同一堵墙**。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（26 个冲突，25 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                         |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–92 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                |
| 台账                   | **420 已交付 / 0 进行中 / 45 未开发**（465 行，`sync.mjs --check` ✅ 一致） |
| 台账变化               | **汇总表刷新**（418/47 → 420/45），提交 `72276489`；明细行零变化          |
| 冲突                   | 26 个（见「三、冲突面」）                                                  |
| 预检                   | **未跑**（无 merge commit 可检；按协议冲突即中止）                         |
| push                   | **未 push**（本地领先远端 **88** 个提交；且 head 的最后一次全量 L2 是红的） |
| 闸                     | **已释放**                                                                |
| 等闸时长               | **约 8 分钟**（11:15 → 11:23:45，TTL 接管）                              |

## 一、取闸：TTL 接管，孤儿闸的机械指纹再次成立

首读 `gate status` ⇒ `held` / `ageSeconds=3686` / `ttl=4200` / **`stale=false`**。

- `owner` 文件里 pid **18253**、时间戳 `2026-10-28T02:01:58.010Z`；
- `ps` 取证：**没有任何** `node` / `java` / `mvnw` / `pnpm` 进程在跑
  ⇒ 持闸者早已不在，但 `ageSeconds < TTL` ⇒ `stale` 仍是 `false` ⇒ **按协议不能抢、不能清**。
- 闸目录里**只有 `owner`、0 个 `keepalive-*`** ⇒ 与第 91/92 轮记录的**孤儿闸指纹**一致。

⇒ 本轮走**密集 `gate acquire` 竞态**（协议第 1 步给的写法），每 1 秒一次；
**11:23:45 TTL 过期，取到闸**（owner pid 21503，ageSeconds=3，`stale=false`）。

⚠️ 本轮再次实测到第 92 轮 §1.3 那条：**只要持闸者还在续期，`stale` 就恒为 false，TTL 不是兜底**；
但本轮的持闸者**已经死了**（无 keepalive、无进程），所以 TTL 确实到期、接管成立 ——
这与「持闸者活着但没起 keepalive」的上一轮是**两种不同的死法**，判定必须靠闸目录指纹而不是靠猜。

等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `git status`），
**没有跑第二个构建**（流水线的 `mvnw verify` 与预检共用 `backend/**/target/` 与 PG 容器）。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后 `origin/main` tip 仍是 **`823ebca5 Integration/module dev (#66)`**
—— 与第 85–92 轮**同一颗** squash（单父，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
```

### 2.1 模块级口径（465 行台账逐模块 join，本轮独立复算）

| 台账版本                     | 已交付 | 进行中 | 未开发 |
| ---------------------------- | ------ | ------ | ------ |
| `origin/main` tip `823ebca5` | 406    | 0      | 59     |
| 集成头（合并时 `72276489`）  | **420**| 0      | **45** |

- **main 已交付 / 集成头非已交付 = 0 个** ⇒ **本次合并不携带任何新交付**；
- **集成头已交付 / main 未交付 = 14 个**（第 92 轮是 11 个，集成头又前进了 3 个）：

| 模块 ID   | main   | 集成头 |
| --------- | ------ | ------ |
| 1.1.2.1   | 未开发 | 已交付 |
| 1.1.2.2   | 未开发 | 已交付 |
| 1.3.2.1   | 未开发 | 已交付 |
| 1.3.2.2   | 未开发 | 已交付 |
| 1.5.2.1   | 未开发 | 已交付 |
| 13.1.2.1  | 未开发 | 已交付 |
| 13.1.2.2  | 未开发 | 已交付 |
| 13.2.1.1  | 未开发 | 已交付 |
| 13.2.1.2  | 未开发 | 已交付 |
| 13.2.2.1  | 未开发 | 已交付 |
| 13.2.2.2  | 未开发 | 已交付 |
| 13.3.1.1  | 未开发 | 已交付 |
| 13.3.2.1  | 未开发 | 已交付 |
| 14.2.1.2  | 未开发 | 已交付 |

⇒ **合并不带新交付，却要冒 14 个已交付模块被回退的风险** —— 本轮同样**不并入**。

## 三、冲突面（26 个 = 第 92 轮的 11 个 + 新增 15 个 channel 测试文件）

`git merge --no-ff origin/main`（PRE=`72276489`）⇒ **26 个冲突**：

| 冲突类型 | 数量 | 文件 |
| -------- | ---- | ---- |
| add/add  | 25   | `apps/web/src/components/dashboard/workbench-card-registry.ts`、`apps/web/src/components/dashboard/workbench-layout-model.test.ts`、`backend/services/channel/src/test/java/com/starcon/channel/{DataGovernanceIntegrationTest,DataGovernanceTenancyIntegrationTest,FilingCalendarIntegrationTest,FilingCalendarTenancyIntegrationTest,GlobalSearchGroupIntegrationTest,GlobalSearchIntegrationTest,LinkageRiskIdentityStub,LinkageRiskIntegrationTest,LinkageRiskTenancyIntegrationTest,SalesTaxIntegrationTest,SalesTaxTenancyIntegrationTest,SyncHealthIntegrationTest,SyncHealthTenancyIntegrationTest,VatIossIntegrationTest,VatIossTenancyIntegrationTest}.java`、`backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`、`backend/services/identity/src/main/java/com/starcon/identity/{NotificationInboxController,NotificationPreferenceService}.java`、`backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`、`backend/services/reporting/src/main/java/com/starcon/reporting/{ReportingFinancePort,ReportingUpstreamClient}.java`、`backend/services/trade/src/test/java/com/starcon/trade/{AnomalyOrderIntegrationTest,AnomalyOrderTenancyIntegrationTest}.java` |
| content  | 1    | `apps/web/src/lib/api-proxy.ts` |

**本轮关键增量**：墙**变宽了 15 个** —— 新增的 15 个**全部是 `channel` 服务的测试文件**。
原因是结构性的：merge-base 被拉回 `6522c497`，该点之后集成头新增的文件在 merge-base 上**根本不存在**
⇒ 两侧都是「各自新增」⇒ add/add。集成头每多交付一个 lane 的测试文件，冲突面就多几个。

`merge-resolve.mjs`（只读判定器）对这 26 个**全部**判「取集成分支侧」，
依据是「main 侧是集成分支历史上的祖先快照 ⇒ 两侧是线性演进，取新侧不丢内容」
⇒ 与 §2.1 的模块级复算**互相印证**：main 的内容是集成头的旧快照，并进去只会回退。

### 3.1 只读预演与真实 merge 逐个一致（沿用第 90 轮建立的方法）

等闸期间用 `git merge-tree --write-tree --name-only integration/module-dev origin/main` 做了**只读**冲突预演
（不碰工作区、不改引用），它给出的清单与**取闸后真实 merge** **逐个一致**（26 个，同名同类型）。
⇒ 「是否要进合并流程」这个判断**可以在取闸前就做完**。

⚠️ `merge-tree` 会调用 `merge-union` 驱动（打印「走了行级兜底并集，请人工确认这个文件改对了」
与「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」）—— 这两条在真实 merge 里同样出现，
**是已知现象，不是新增异常**；台账文件因此**不在**冲突清单里。

**未解任何冲突**（协议硬边界）：`git merge --abort` 已执行，**`MERGE_HEAD` 已清**，
HEAD 回到 `72276489`，`git status --porcelain` 为空（0 个改动文件）。

## 四、本轮新增取证：孤儿 tick 的台账残骸，**本身就是 HEAD 的一处「红」**

取闸后 `git status --porcelain` **不是空的** —— 有 1 个改动文件：

```
 M docs/product/capability-blueprint/module-progress.md
```

归属取证（沿用第 64 轮「脏工作区先分清归属」的方法）：

1. 该 diff **只动汇总计数表**（已交付 418→420、未开发 47→45、D7 111→113、卷 13 的 9→11），
   **一个模块明细行都没动**；
2. `git diff` 里没有任何 `状态` 列的增删 ⇒ 不是人工改了交付状态；
3. **HEAD 提交版本本身是不一致的**：`git checkout --` 之后跑 `node tools/module-progress/sync.mjs --check`
   ⇒ `✗ 台账与蓝图不一致` —— 明细行是 420/45，汇总表却停在 418/47；
4. `owner` pid 18253 已死、无 keepalive ⇒ **上一任持闸 tick 跑完 sync.mjs 但没来得及提交就崩了**，
   留下这份残骸。

⇒ 结论：**这份残骸不是垃圾，是一次正确但没落盘的台账刷新**。
本轮重跑 `node tools/module-progress/sync.mjs`，产物 blob = **`b9a81396`**，与残骸**逐字节相同**
⇒ 确定性再生产物，**无信息丢失**；`--check` 随即转绿。

处理：以 `72276489 chore(progress): 刷新台账计数（补齐孤儿 tick 未提交的 sync.mjs 产物）` **提交**
（只改汇总表，不动明细行与业务代码）。

⚠️ 为什么不能把残骸留在工作区：`lane.mjs` 的 `merge-ready` 在 `git status --porcelain` 非空时
**直接 `exit 1` 拒绝并入**（`tools/module-progress/lane.mjs:948`）⇒
脏工作区会**堵死流水线整条车道并入通道**。提交掉它同时解掉两个问题：
台账汇总与明细一致（流水线 L2 第 6/6 步 `sync --check` 由红转绿）＋ 通道不再被堵。

## 五、为什么没 push / 没跑预检

- **没跑预检**：无 merge commit 可检（`mvnw compile` + `pnpm typecheck` 是给「合并成功后的集成头」用的）。
  本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。
- **没 push**：本地集成头领先 `origin/integration/module-dev` **88** 个提交、落后 0 个。
  本轮**没有产生任何可发布的合并**，发布是流水线的决定（第 85–92 轮同此口径）。
  ⚠️ head 的最后一次全量 L2 是**红**的（第 92 轮 §1.2 取证：`services/channel` 2383 例中 11 例失败），
  按流水线禁令「L2 没绿绝对不 push」，发布侧此刻也**不该**推。

## 六、待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （已连续 **14 轮**：第 79–93 轮）。见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **待办（来自 §三）**：冲突面**随集成头前进而变宽**（11 → 26，全部是 lane 的测试文件）。
   P0 不解，冲突面只会继续涨。
3. **协议待补**：`SYNC-MAIN.md` 第 3 步应写明**「脏工作区先分清归属」** ——
   区分「tick 在途改台账」与「上一任 tick 崩了留下的残骸」；后者若经 `sync.mjs` 重跑能**逐字节复现**，
   说明它是正确产物，应提交而不是丢弃（丢弃 = 把台账留在红状态 + 堵死 `merge-ready`）。
4. **协议待补**：`SYNC-MAIN.md` 第 1 步应写明**孤儿闸的两种死法**（持闸者活着但没起 keepalive
   ⇒ TTL 不到期、不能接管；持闸者已死且无 keepalive ⇒ TTL 到期可接管），靠闸目录指纹区分，不要猜。
5. 下一轮同步若 main 仍无新提交，预期仍是「冲突未并入」；**除非 P0 被解，否则本任务会一直空转**。

## 七、文件清单

- 新增：本文件
- 新增提交：`72276489`（台账汇总表刷新，孤儿 tick 残骸的确定性再生产物）
- 未改任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify
- 闸：11:23:45 取到（TTL 接管）→ 收尾时释放
