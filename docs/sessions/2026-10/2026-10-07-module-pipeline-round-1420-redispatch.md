# 模块流水线 14:20 轮：重派 3 条被上一轮回收的车道

> 调度者（cron 流水线）本轮运行记录。子任务交付后由各 worker 自写同名会话日志。
> 本文件只记调度侧的决定与复核结论。
> 前一轮记录见 `2026-10-07-module-pipeline-counterparty-physical-import.md`（14:00 轮）。

## 决策摘要

| 项 | 值 |
| --- | --- |
| 派发模块 | 3.6.1.1 往来单位档案 / 3.7.2.1 物理与安全属性 / 3.8.1.1 导入向导（均 D1–D2） |
| 分支 | `feat/3-6-1-1`、`feat/3-7-2-1`、`feat/3-8-1-1`（**均未 push**） |
| 集成头 | `integration/module-dev` @ `0d57007`（本轮实测，非模板值） |
| 迁移号 | V27 / V28 / V29（`lane.mjs plan` 预分配，与 14:00 轮相同） |
| 回收 | 无 `ready` 车道可并入；`gc` 无事可做 |
| 闸 | acquire → 已 release |
| 结果 | ledger 57 已交付 / 0 进行中 / 408 未开发（共 465） |
| `redispatched` | **3 条全部为 1** |

## 关键决策 1：三条车道都不是「没开工」，是「开工了但一个 commit 都没有」

`lane.mjs plan --lanes 3` 返回的三条车道都带 `redispatched: 1`，说明 14:00 轮排了车道但派发没完成。
本轮**没有直接重派**，先查了工作区实际状态 —— 三条 lane worktree 里都有大量未提交代码：

```
3.6.1.1  11 个文件  ≈4450 行（迁移 276 / Service 829 / 集成测试 864 / 前端模型 473）
3.7.2.1   7 个文件  ≈2389 行（迁移 127 / Service 596 / 集成测试 696）← 前端与契约全缺
3.8.1.1  11 个文件  ≈4221 行（迁移 350 / Executor 822 / 集成测试 707）← 前端与契约全缺
```

`git log integration/module-dev..HEAD` 在三条 lane 上**全部为空**：没有任何提交。

这正是「子任务报 succeeded ≠ 真做完」那条教训的又一次复现形态 —— Worker 读完全部上下文、
写下前几层，然后在生成过程中耗尽回合，`final_text` 截断在半路，**没 commit、没 land**，
于是下一轮 `plan` 把它判成 `redispatched`。

**处置**：不丢弃、不从头重开。任务书里明确写「工作区已有上一轮未提交的半成品，
逐个审计完成度与正确性，补缺口，不要盲目信任」，并把每条车道的**具体缺口清单**列出来：

| 车道 | 审计重点 / 主要缺口 |
| --- | --- |
| 3.6.1.1 | Controller 仅 229 行 vs Service 829 行 —— 端点大概率没接完 |
| 3.7.2.1 | 迁移仅 127 行 —— 大概率没写 CHECK（毛重≥净重等），**且前端与契约全缺** |
| 3.8.1.1 | **前端与契约全缺**；Executor 822 行要审计是不是真异步 |

**这比重新 dispatch 三个全新 Worker 划算得多** —— 丢弃的是几千行已经写好、且大概率风格对齐的代码。

## 关键决策 2：车道基线落后集成头 1 个提交（可接受，未快进）

派发前实测三条 worktree 的 HEAD：

```
fa8dee8  feat/3-6-1-1
fa8dee8  feat/3-7-2-1
fa8dee8  feat/3-8-1-1
```

真实集成头是 `0d57007`：

```
git rev-list --count fa8dee8..integration/module-dev  →  1
0d57007  docs: 模块流水线 14:00 轮调度记录（派发 3 条车道）
```

落后的唯一提交是 **docs-only 的调度记录**，不含任何代码。14:00 轮已经把三条 lane 快进到
`fa8dee8` 过（那次的缺口是 9 个提交，含 3.5.2.2 的契约与前端），本轮缺口缩到 1 个 docs 提交，
**不需要快进**。并入时 merge 驱动器会处理。

> 顺带印证：14:00 轮记的待办「把『同名分支已存在且非集成头后代 → 快进/重建』固化进
> `lane.mjs plan`」仍然有效，但本轮再次出现的不是陈旧分支，而是**未落地半成品**，
> 属于另一类问题，需要 `plan` 或 `land` 增加「回收未 commit 的车道」的显式处理。

## 关键决策 3：补派前端落点与边界约束（首轮任务书遗漏，本轮追加）

首轮派发的任务书里**没有写前端落点路径**。14:00 轮的日志里其实已经定死了：

```
3.6.1.1 → apps/web/app/partners/page.tsx                （L3 锚点路由 /partners）
3.7.2.1 → apps/web/app/products/sku/[id]/physical/page.tsx（与既有 attributes/ tab 并列）
3.8.1.1 → apps/web/app/products/import/page.tsx
```

三条车道同时跑、各自新建目录层级会打架，所以在 Worker 刚启动、工作量还小的时候
用 `task_append` 追发（`mode: steered`），把落点路径与三条边界约束补齐：

- 3.6.1.1：不得引用 3.6.1.2（未开发），账期/信用额度等角色专有字段不许建列建表；台账只改自己那行
- 3.7.2.1：不得引用 3.7.1.1 / 3.7.1.2（均未开发），危险品只留「是否危险品 + 类别标识」，
  报关申报细节不做；且迁移只 127 行，重点要求把 CHECK 写进 SQL 而非只在 Java 校验
- 3.8.1.1：按**五个**环节实现（标题写「四步」是笔误）；Excel 无依赖必须明说不许静默省略；
  「同租户同类任务互斥」要 DB 部分唯一索引 + 专项断言（含「租户 A 不挡租户 B」）；
  `ImportJobExecutor` 要审计是否真异步（最容易写成同步跑完再返回 200）

另外三条车道统一提醒：**绿测不等于有效断言**，参考 3.5.2.2 的教训做反向验证。

## 资源实测（派发时刻）

```
hw.ncpu                 10
uptime                  load averages: 6.57 3.71 2.52（车道刚起）
Docker MemTotal         11.67 GB
常驻容器合计            <0.6 GB（最大的是 catalog-dev-catalog-1 423 MiB）
另有 2 个 testcontainers-ryuk 与 2 个 reverent_* PG 容器残留在跑
```

**load 6.57 值得注意**：比 14:00 轮记录的 2.28 高出不少，但本轮读数取自派发瞬间，
车道尚未进入 `./mvnw verify`（JVM + Testcontainers PG16）与 `next build` 的压力阶段。
`reverent_*` 容器是上一轮 Worker 遗留的 Testcontainers PG 残留 —— 三个 Worker 全部被回收
却没走到 Ryuk 清理，这会**持续占着连接与内存**，需要确认是否该在 `gc` 里加清理。

**峰值仍未采到**，本轮结论维持：证据不足以支持把全局并发从 6 提到 8。
下一轮应在车道跑验证时补测 Docker 内存峰值，并同时观察 load average 的真实上限。

## 🚨 关键发现：`land` 与 `merge-ready` 之间存在竞态，会把半成品并入集成头

**本轮最重要的问题，比三个模块本身都重要。**

### 现象

14:29:59（本轮 14:20 派发之后 10 分钟），另一个 cron tick（跑 `10,30,50`）执行
`merge-ready`，产出：

```
a8d78e5  merge(3.7.2.1): 物理与安全属性
b56c62d  merge(3.6.1.1): 往来单位档案
```

但**此刻 3.7.2.1 的 worker 还在跑**（`task_query status=running` 实测确认）。

### 根因

`lane.mjs land` 只校验两件事：**工作区干净** + **有新提交**。
它**不知道 worker 是否还在跑**。

3.7.2.1 的 worker 在 14:28:59 提交 `2187a02`（V28 = 127 行版本）后调用了 `land`，
lane 立刻进入可并入状态。worker 随后继续补 DB 约束（未提交），
而另一个 tick 的 `merge-ready` 在这一窗口内把**当时的 HEAD** sweep 了。

结果：`integration/module-dev` 里的 V28 **只有 2 个 CHECK**，
worker 正在补的那 96 行（`sku_safety_dangerous_needs_class`、
`sku_safety_class_needs_dangerous`、`sku_package_dims_positive`、
`sku_package_net_weight_positive` + 触发器层）**全部没进去**。

**即：台账已标 3.7.2.1「已交付」，但集成头里是一份缺 DB 约束的实现。**
这正是「台账与代码实际不符」，且它不是人手写错，是编排器竞态。

### 已采取的处置

用 `task_append`（`mode: steered`）向 3.7.2.1 的 worker 发了紧急通知：
补完约束 → 跑完整验证 → **再次 `land`** → 会话日志注明首次 land 被提前并入。
不 land 的话这 96 行会永远烂在 worktree 里。

### 与「succeeded ≠ 真做完」的区别

上一轮教训是 **Worker 声称做完但没做完**。
这一轮相反：**Worker 做得对，但编排器抢在它做完之前就把它的中间态并进了集成头**。
前者要靠实证复核发现，后者要靠 `land` 的语义修正根治。

### 建议的修复（未自行执行，需改 `lane.mjs`）

1. **`land` 增加 worker 存活校验**：land 时记录 worker session id / 心跳，
   `merge-ready` 只并入「land 之后心跳已停止」的车道；或
2. **`land` 增加二次确认**：要求「land 后等待 N 分钟内无新提交且无心跳更新」才可并入；或
3. **最简单可靠**：`land` 之后 worker 若还要继续干活，就不该 land。
   把「land」明确定义为**该车道的最终交付**，任务书里已写明，
   但 Worker 仍在 land 后继续改代码 —— 应该在任务书里补一句
   「**land 之后禁止再改任何文件；要继续改就先别 land**」。

## 3.6.1.1 的交付复核结论（已实证，非采信报告）

Worker 报告称遇到「改动突然消失」的惊吓场面并归因于「上一轮 Worker 已提交」。
**这个归因是错的**，用 commit 时间戳证伪：

```
ec3781a  14:26:16   feat(catalog): 3.6.1.1 party profile
4262c03  14:26:23   feat(web): /partners workspace
fe9afc0  14:26:28   chore(contracts): regenerate catalog OpenAPI
06933bd  14:26:28   docs: ledger + session log
32c6f1e  14:29:14   docs(session): 反向验证证据
```

五个提交全部落在 **14:26–14:29**，即本轮派发（14:21）之后 ——
是 Worker **自己**的提交，它把自己做的事误记成上一轮的功劳，
并因此一度以为工作丢失而慌乱。代码本身没问题。

独立复核（`git show integration/module-dev:<path>` 直接读并入后的内容）：

| 复核项 | 结果 |
| --- | --- |
| V27 CHECK 约束数 | **14** |
| `ENABLE ROW LEVEL SECURITY` | ✅ |
| `FORCE ROW LEVEL SECURITY` | ✅ |
| GRANT | 2 处 |
| 集成测试 `@Test` 数 | 24 |
| 租户隔离三断言 | 真实存在，**且带非空前置断言**防「空断言恒真」 |
| 工作区未提交文件 | 0 |
| 原子提交数 | 5 |

worker 还做了**两处反向验证**并逐字还原：
① 把 RLS policy 改成 `USING(true) WITH CHECK(true)` → `Tests run: 24, Failures: 2, Errors: 1` 转红，
证明隔离靠的是 DB 层 RLS 而不是应用层恰好带了 tenant 条件；
② 改坏 `requiresReason` → 前端 1 failed / 620 passed 转红。

**这是本轮质量最高的一份交付**，值得作为其他车道的样板。

## 后续待办

- **[P0] 修 `land`/`merge-ready` 竞态**（见上节 3 条建议），否则每个整点都会把半成品并进集成头
- **[P0] 确认 3.7.2.1 第二次 land 之后，`integration/module-dev` 的 V28 真的带上了那 4 个 CHECK 与触发器**
- **[P1] 任务书补一句**「land 之后禁止再改任何文件；要继续改就先别 land」
- **[P1] 并发上限重新评估**：本轮采到 load **6.57**（14:21 派发瞬间）与 **2.51**（14:31，车道跑验证期间），
  Docker 常驻容器仅 ~1.3GB / 11.67GB。**压力远低于预期**，
  因为车道主要吃 JVM 与本机内存、不吃 Docker 配额。
  但样本只有两轮且未覆盖「三条车道同时跑 next build」的时刻，
  建议并发上调到 **8** 前先让一轮 6 条车道全部进入验证高峰再采一次。

- 下一轮先跑 `merge-ready`：三条车道若 `land` 成功应能并入（3.7.2.1 / 3.8.1.1 缺口最大，风险最高）
- 采三条车道跑 `./mvnw verify` 与 `next build` 期间的 **Docker 内存峰值与 load average 峰值**，
  这是决定并发 6→8 的唯一依据，两轮都没采到
- 清理 `reverent_*` 残留 Testcontainers 容器，或在 `lane.mjs gc` 里加一条
  「回收未 commit 的车道 worktree」时顺带清容器的规则
- 把 14:00 轮待办继续挂着：「同名分支已存在且非集成头后代 → 快进/重建」固化进 `lane.mjs plan`
- 根治陈旧分支：定期把 feature 分支回合到集成头 —— **属需用户确认的动作，未自行执行**
---

# 14:45 轮次追记：3.8.1.1 交付复核 + 并入失败诊断

## 3.8.1.1 导入向导：Worker 交付质量高，但**并入被阻塞**

### Worker 查出的 7 处真实缺陷（遗留代码「从未真正跑绿过」）

最严重的一处是**架构级**的：

> 异步执行器只绑 `app.current_tenant` 没绑 `app.actor`，
> 而 `spu` 被 V3 的 `stamp_record_owner` 触发器要求 `app.actor` ——
> **每一行写入都被拒，所有导入任务落 FAILED**。

修法正确：传 `AccessService.Access`（来自 `AccessService#require` 解析已认证成员关系），
而不是裸 tenantId。测试从 14 failures 收敛到 0。

其余 6 处：行分页用 `offset` 导致静默漏数据（改 keyset 游标）、
`r.get("values")` 与列名 `cells` 不符致逐行结果恒空、
SKU 新增模式跳过时 `skipped=false` 虚报成功数、条码全表扫、
缺取消端点（`→CANCELLED` 无出口导致**永久占住互斥槽位**）、CSV 解析异常逃逸成 500。

另修两处**测试自身缺陷**：一个断言用了不在 CSV 里的编码（查不到恒真）、
一个异步用例没等终态与「已入队」构成竞态。

### 实证复核（全部对上）

| 复核项 | 结果 |
| --- | --- |
| 原子提交 | 5 个，14:43:46–14:44:13 |
| 工作区未提交 | 0 |
| V29 表 / CHECK / RLS | 6 表 · 33 CHECK · ENABLE+FORCE |
| 同租户互斥约束 | `UNIQUE(tenant_id,target_entity) WHERE status IN ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING')` ✅ |
| actor 绑定修复 | 在位，附完整理由注释 |
| 集成测试 | 19 例，含「不同租户的同类任务也互不影响」 |
| catalog 全量 | 778 例 0 失败 |
| 前端 | 622 例 / typecheck / lint / Next 构建全绿 |

**Excel 不支持**：已确认各 pom 无 POI/EasyExcel/OpenCSV，页面与迁移文件头都显式写明，
未静默省略 —— 符合「不许偷懒」第 2 条。
**报价边界**：`target_entity` 的 CHECK 不含 `QUOTE`，理由写明「价格唯一写入所有者是定价服务，
catalog 建表等于造出第二个价格事实来源」—— 边界判断正确。

## 🚨 并入失败：`apps/web/src/lib/api-proxy.ts` 冲突（`merge-ready` 退出码 2）

```
✗ 3.8.1.1 并入冲突：apps/web/src/lib/api-proxy.ts
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
CONFLICT_KEYS paths:/catalog/brands paths:/catalog/attributes
```

### 根因：车道分支基线陈旧 + 并集驱动器分不清「删除」与「没见过」

实测：

```
集成头 api-proxy.ts            parties 出现 3 次   ← 3.6.1.1 加的
feat/3-8-1-1 api-proxy.ts      parties 出现 0 次   ← 它从 fa8dee8 切，3.6.1.1 还没并
feat/3-8-1-1 的 merge-base      fa8dee8（3.5.2.2）
```

`feat/3-8-1-1` 从 `fa8dee8` 切出，**早于 3.6.1.1 并入**。
合并时驱动拿它的文件与集成头比，看到「集成头有 `parties`，它没有」，
于是判定它**删掉了** `parties` 块 → 冲突。

**它并没有删。**它只是从未见过那个块。3.8.1.1 自己新增的是 `import-*` 放行块。

**语义正确的解法是两边都保留**（`parties` 与 `import-*` 是互相独立的放行条目）：
要么先把 `integration/module-dev` 合进 `feat/3-8-1-1` 再重试并入，
要么人工把两个注释块都留下。

### 处置：abort 掉半途的 merge，不自行强解

`merge-ready` 失败后**把主工作区留在了半合并状态**
（`integration/module-dev` 上 `MERGE_HEAD` 存在，`api-proxy.ts` 为 `UU`，
其余 3.8.1.1 文件全部 staged）。这个状态会**卡住主工作区与另一 tick 的所有操作**。

按「不要自己强行解冲突」的纪律，本轮执行 `git merge --abort` 恢复到干净状态，
**没有替它挑一个解法**。3.8.1.1 的成果安全留在 `feat/3-8-1-1` @ `28a845a`，未丢失。

> 顺带记一条操作教训：`merge --abort` 会把**当时已 staged 但未提交**的改动一起回退。
> 我在同一轮里先 `git add` 了会话日志再触发 abort，日志内容被一并回退，需重写。
> 正确顺序是：**先 abort 清干净，再写日志并提交**。

### 与「并集驱动把已交付模块改回未开发」同源

上一轮（`36a871e`）已记录过并集驱动的事故，本轮 `c8b6787` 修回了 3.6.1.1 的台账行。
同一个驱动、同一个盲点：**它假设车道分支总是从最新集成头切出**，
一旦不是，它就无法区分「故意删除」与「从未见过」，于是要么误报冲突，要么静默覆盖。

**根治方案**：并集驱动器必须用**三方的 merge base**（`git merge-base`）而不是文件级双边比较 ——
只有「base 里有、ours 有、theirs 没有」的键才判定为「theirs 删除了」；
base 里也没有的，是「theirs 从未见过」，应当保留 ours。

## 3.7.2.1 仍处于「会被永久丢弃」状态（已 steer，等它二次 land）

14:45 实测：

```
08e9e07  in integration head: NO
0124359  in integration head: NO
V28 在集成头里的 CHECK 数量：2    ← 约束那批仍未进去
lane state: merged               ← merge-ready 不会再捞它
```

它的 worktree 被 `gc` **删了两次**（`gc` 按 `state=merged` 判断可回收，
而它当时还在用），worker 改用「先提交到分支保命」策略，worktree 现已恢复在 `0124359`。
已 steer 要求它收尾后**再 land 一次**，否则这批约束永久滞留分支上。

## 全局状态

- 台账 **57 → 59 已交付**（3.6.1.1、3.7.2.1）
- 集成头 `b6bc6a3`；另一个 tick 在 14:40 又派发 3.6.1.2 / 3.6.3.1（均为 0 提交，刚起步）
- `sync --check` 通过
- 车道：3.6.1.1 merged · 3.7.2.1 merged（**但代码未并**）· 3.8.1.1 **conflict**

## 本轮新增待办

- **[P0]** 解决 `api-proxy.ts` 冲突：把两边注释块都保留；或先合集成头进车道分支再重试
- **[P0]** 并集驱动改用**三方 merge base**，不再用双边文件比较
- **[P0]** `merge-ready` 失败后应自动 `merge --abort`，不要把主工作区留在半合并状态
- **[P0]** 确认 3.7.2.1 二次 `land` 之后 V28 在集成头里真的带上 4 个 CHECK + 触发器
- **[P1]** `gc` 回收前应校验 worker 心跳，不要回收 `state=merged` 但仍在跑的 worktree
- **[P1]** 派发前把车道分支快进到当前集成头，避免同类冲突复发
