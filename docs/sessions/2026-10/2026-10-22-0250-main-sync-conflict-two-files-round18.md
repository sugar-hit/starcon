# 2026-10-22 02:50: main 同步第 17 轮 —— 冲突面 1 → 2 文件，main 相对集成分支零业务新增

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 17 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `bf6179b8` 不变。**
四选一里的「**冲突未并入**」。

本轮与前 16 轮（0115 / 0315 / 0415 / 0515 / 0643 / 0740 / 1127 / 1145 / 1720 / 2123 / 0055 / 0115 轮16）撞的是**同一堵墙**：
`origin/main` 仍用 **squash** 合入，`merge-base` 仍停在 `1bb270a0`，
`git merge --no-ff origin/main` 仍以冲突告终。

**本轮的两个独立增量**（不采信历史会话日志，全部重新取证）：

1. **冲突面从 1 文件扩到 2 文件** —— 上一轮（10-22 01:15，第 16 轮）的唯一冲突是一个
   `docs/sessions/**` 的 add/add 文档冲突，本轮已自行消解；
   本轮 `apps/web/src/lib/api-proxy.ts`（4 个 ours-only 冲突区）**重新出现**，
   且 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` **新增**为冲突文件
   （main 侧 a105acf0「修正集成分支并入主分支的新内容判据」改写了它）。
2. **main 相对集成分支的「零业务新增」首次被三路独立取证确认**（见取证四）：
   此前 16 轮只查了台账模块领先数；本轮补了**代码维度**与**树差异维度**，
   三路全部指向同一个结论：**main 侧没有集成分支不具备的业务内容**。

## 决策 / 结论

1. **冲突只报不解**（协议 `tools/module-progress/SYNC-MAIN.md` §0 硬规定 + 第 3 步，
   且 `PIPELINE.md:489` 同口径：「不要自己强行解冲突」）。
   本轮 `git merge --abort`，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `bf6179b8`，**未 push 合并内容**。

2. **本轮冲突面 = 2 个文件，7 个冲突区**：

   | 文件 | 冲突区 | ours 行数 | theirs 行数 | 性质 |
   | --- | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | 1（行 500–553） | 51 | **0** | 纯 ours 侧新增 |
   | 同上 | 2（行 648–698） | 48 | **0** | 纯 ours 侧新增 |
   | 同上 | 3（行 5339–5393） | 52 | **0** | 纯 ours 侧新增 |
   | 同上 | 4（行 5447–5506） | 57 | **0** | 纯 ours 侧新增 |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` | 1（行 152–156） | 1 | 1 | 双向 |
   | 同上 | 2（行 184–213） | 0 | 27 | 纯 theirs 侧新增 |
   | 同上 | 3（行 220–235） | 5 | 8 | 双向 |

   ⇒ `api-proxy.ts` 是**跨轮反复出现的同一堵墙**：4 个冲突区**全部 main 侧为空**，
   对应集成分支把 4 个广告模块从 trade 的「超长单行 alternation」提升为独立 `services[]` 条目
   （注释里写明：多车道同时并入会让同一条 alternation 判冲突，是**结构性**冲突，retry 不会自愈）。
   `MAIN_MERGE_CRON_PROMPT.md` 是**本轮新增**的冲突面，来自 main 侧新增的「落地树哈希」判据段落。

3. **三路取证一致：main 相对集成分支零业务新增**：

   | 维度 | 方法 | 结论 |
   | --- | --- | --- |
   | 台账模块领先数 | 逐模块 ID（465 个）三方比对 main 台账 vs 集成分支台账 | main 领先 **0**；集成分支领先 **4** |
   | 后端代码 | main 侧 8 个 `feat:` 提交涉及的 **466 个** 后端文件逐字节比对 | **466/466 完全一致**，差异 0、缺失 0 |
   | 树差异分类 | `git diff --name-only HEAD origin/main` 81 个文件分类 | main 独有 **23** 个（**全是 docs 与 progress-site 工具，零业务代码**）；集成分支独有 **46** 个；两侧都有但内容不同 **12** 个（其中 7 个是契约/台账，走 `merge-union` 已干净求并） |

   ⇒ main 侧的 8 个 `feat:` 提交（供应商评级 10 项、到岸成本 24 项、平台价格同步 7 项、
   采购单审批 5 项、需求合并 4 项、采购单创建 2 项、待检处理+授权凭证 2 项、品牌授权 1 项）
   **在集成分支里已经逐字节存在** —— 它们是被 squash 合入 main 的集成分支自身产出。

4. **`merge-union` 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集，
   打印「折叠了 465 行同 ID 记录」；`packages/api-contracts/**` 7 个文件全部干净合并；
   `.gitignore` 干净合并；**唯一走兜底行级并集的是台账本身**（打印人工确认提示，属预期）。

5. **台账数字**（merge abort 后，集成头 `bf6179b8`）：已交付 **301** / 进行中 **0** / 未开发 **164**
   （465 个模块，完成率 64.7%）。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。

6. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 取证

### 一、闸：等了约 23 分钟，密集 acquire 竞态命中

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "held", "ageSeconds": 140, "ttlSeconds": 4200, "stale": false }
```

`stale: false` ⇒ 持闸 tick 活着，**等它释放，不抢不清不 force**。

活性取证（闸外）：`ps` 显示持闸 tick 正在跑流水线 L2 的全量验证：

```
26553  02:20  .../maven ... Launcher verify
26995  01:21  .../services/catalog/target/surefire/surefirebooter-...
```

⇒ 等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log`），
**连 `git fetch` 都没跑**（协议「等闸期间不要跑第二个构建」+ 避免无谓 IO）。

按协议「直接竞态 `gate acquire`，不要低频 `status` 观测」（闸交接窗口可能窄到零宽度）：

```bash
for i in $(seq 1 3600); do
  if node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true'; then
    echo "GATE ACQUIRED after ${i}s"; break
  fi
  sleep 1
done
```

第一轮 1200 次窗口耗尽（`GATE TIMEOUT`）仍未拿到 —— 期间 L2 依次跑过
catalog → channel → trade 的 Testcontainers 集成测试，L2 日志 `.dev-agent/l2-run-batch58.log`
从 `[1/4] backend ./mvnw verify` 一路推进。
**第二个 3600 次窗口在第 1386 秒命中**：

```
GATE ACQUIRED after 1386s
```

命中时机正是 L2 结束（`===== L2 GREEN =====` / `===== L2 END 2026-10-22 02:58:04 =====`）的交接窗口，
下一 tick 未能在同一秒内抢到。

> ⚠️ 闸归属核对：命中后 `cat .dev-agent/dispatch.gate/owner` ⇒ PID `53115`，
> `gate status` ⇒ `held / ageSeconds 25 / stale false` ⇒ 确认本轮持有。
> ⚠️ 中途另见 `.worktrees/14.x.x.x/backend` 下有 lane worker 的独立 maven 进程，
> 那些在**各自 worktree** 里跑，与集成头 worktree 不共享 `target/`，不构成本轮预检干扰。

### 二、fetch 与新提交判定

```
$ git fetch origin --prune
From https://github.com/sugar-hit/starcon
 - [deleted]  (none) -> origin/chore/sync-module-dev
 - [deleted]  (none) -> origin/docs/sync-round-verification-record

$ git merge-base --is-ancestor origin/main integration/module-dev && echo NO_UPDATE || echo HAS_UPDATE
HAS_UPDATE

$ git rev-list --count integration/module-dev..origin/main
26
```

差异规模：`git diff --stat integration/module-dev...origin/main` ⇒
**1292 files changed, 815033 insertions, 92692 deletions**（三点 diff 含 squash 引入的全量历史，数字虚高；
真实树差异见取证四的第 3 路）。

### 三、待并入的 26 个 main 提交

**feat 交付类（8 个，共 55 项能力）**：

- `e57e1497` feat: 供应商评级（6.1.1.3）、同款货源推荐（6.1.2.2）、转量产与建档（6.6.2.2）、
  调整审批（7.4.1.2）、证据与申诉（11.4.1.2）、工单管理（11.4.2.1）、退货分析（11.5.1.1）、
  活动列表（14.1.1.1）、关键词管理（14.1.3.1）等 10 项能力交付
- `ec225641` feat: 到岸成本（5.2.1.2）、成本分摊（5.2.1.3）、汇率查询（5.2.2.1）、
  汇率快照（5.2.2.2）、平台费用（5.2.3.1）、物流与头程费用（5.2.3.2）、利润测算器（5.3.1.1）、
  批量测算（5.3.1.2）、定价模板（5.3.2.1）、价格规则（5.3.2.2）、调价单（5.4.1.1）、
  批量调价（5.4.1.2）、价格历史（5.5.1.1）、异常告警（5.5.2.1）、档口与货源池（6.1.2.1）、
  供应商资质（6.1.2.3）、缺货与断货风险（6.2.1.1）、补货建议（6.2.1.2）、采购计划（6.2.1.3）、
  到货与逾期（6.3.2.2）、备货预报（10.4.2.2）、弃置与销毁（10.7.1.3）、
  Request a Review（15.2.2.1）、索评规则（15.2.2.2）等 24 项能力交付
- `a9f3b4d0` feat: 平台价格同步（5.4.2.1）、采购申请（6.2.2.1）、质检单（6.4.2.1）、
  采购对账（6.5.1.1）、采购分析（6.5.2.1）、平台托管与全托管备货（7.7.2.2）、
  认证与资质（16.2.2.2）等 7 项能力交付
- `807739db` feat: 采购单审批（6.3.1.3）、跟单与交期（6.3.2.1）、试产单（6.6.2.1）、
  调整单管理（7.4.1.1）、纠纷列表（11.4.1.1）等 5 项能力交付
- `478778ef` feat: 需求合并与批量（6.2.2.2）、采购单列表（6.3.1.1）、
  不良品处理（6.4.2.2）、开发立项（6.6.1.1）等 4 项能力交付
- `05fb216e` feat: 采购单创建与编辑（6.3.1.2）、打样与样品管理（6.6.1.2）交付
- `73791049` feat: 待检处理（8.2.2.2）、授权凭证（16.4.2.2）交付
- `a257a922` feat: 品牌授权（16.4.2.1）交付 —— 授权方、授权范围（店铺/国家/类目/期限）、
  授权文件与被授权主体，校验刊登时品牌使用是否落在授权范围内

**合并/进度/工具类（18 个）**：

- `d28188d6` Integration/module dev (#11)
- `9efa2fee` Merge pull request #23 from sugar-hit/chore/progress-site-tools-resync
- `a105acf0` docs: 修正集成分支并入主分支的新内容判据
- `fa74cb2d` docs: 补记集成分支并入主分支的验证结论
- `92066867` docs(sessions): 补记集成头同步 main 的 7 轮冲突取证
- `ce8a5965` docs(sessions): 补记 D5–D7 夜间冲刺自动化作业手册
- `7ac8f3c0` docs(progress-site): 会话日志补记定时任务、刷新机制演进与三个真 bug
- `fce1e1cc` fix(progress-site): bash 3.2 空数组展开 + 页脚显示数据来源
- `cacdc8f8` fix(progress-site): 自检必须与构建读同一个来源的台账
- `21db1a48` fix(progress-site): 每日刷新改为「fetch + 从 ref 读文档」，与本地 git 状态解耦
- `22e1ec84` feat(progress-site): 交付进度看板 + 每日 08:00 自动原地更新
- `a83380c6` docs: 记录统一错误处理器掩盖代码缺陷的取证结论
- `80cbba00` / `ba9c11ab` / `b1b9f936` / `3bedc528` / `7eca6b28` / `34d6e8d5` chore: 同步集成分支最新进度

### 四、三路「零业务新增」取证

#### 第 1 路：台账模块逐 ID 比对（465 个模块）

```
main 台账模块数: 465   集成头台账模块数: 465
== main 已交付但集成头未交付（main 领先）==  合计: 0
== 集成头已交付但 main 非已交付 ==          合计: 4
```

⇒ main 领先 **0** 个模块。集成分支领先的 4 个是广告与增长卷的本批交付
（14.1.1.2 活动详情 / 14.2.2.1 预算分配 / 14.3.1.1 活动报表 / 14.3.2.2 效率诊断）。

#### 第 2 路：后端代码逐字节比对（466 个文件）

```
main feat 提交涉及的后端文件数: 466
  集成头已完全一致: 466
  内容不同: 0
  集成头缺失: 0
```

⇒ main 上 8 个 `feat:` 提交带来的**全部**后端代码（迁移 SQL、Controller、Models、Service、
集成测试）**在集成分支里逐字节已存在**。main 侧没有集成分支不具备的业务内容。

#### 第 3 路：树差异分类（81 个文件）

| 类别 | 数量 | 内容 |
| --- | --- | --- |
| main 独有 | **23** | 15 个 `docs/sessions/**` + 1 个 `docs/backlog/**` + 8 个 `tools/progress-site/**`（看板工具与刷新脚本）⇒ **零业务代码** |
| 集成头独有 | **46** | 广告与增长本批交付（前端页面/组件/白名单测试 + channel 的 4 组 Controller/Models/Service + V277–V280 迁移 + 4 个集成测试）+ 6 个本会话/车道文档 |
| 两侧都有、内容不同 | **12** | 7 个契约/台账（走 `merge-union` 干净求并）+ `.gitignore`（干净合并）+ `api-proxy.ts` + `MAIN_MERGE_CRON_PROMPT.md`（**冲突**） |

⇒ main 侧唯一「集成分支没有」的东西是**会话文档 + progress-site 看板工具**，
不是业务交付。这两类都是 `origin/main` 从集成分支回流时带上的**副产物**。

### 五、冲突文件内部结构

#### `apps/web/src/lib/api-proxy.ts` —— 纯 ours 侧新增（结构性，retry 不自愈）

```
区1: 行500-553   ours=51行  theirs=0行
区2: 行648-698   ours=48行  theirs=0行
区3: 行5339-5393 ours=52行  theirs=0行
区4: 行5447-5506 ours=57行  theirs=0行
```

增量量化：

```
base=2634 ours=6793 theirs=6585
ours 相对 base 新增 4160 行; theirs 相对 base 新增 3952 行
theirs 新增行中 ours 完全没有的: 0 行
```

⇒ **main 侧没有任何一行是集成分支没有的**。冲突纯由「同一批模块在两侧的表达方式不同」
造成：集成分支把广告模块提升为独立 `services[]` 元素（文件注释写明这是为了避免
多车道共改一条超长 alternation 时必然冲突），main 侧仍把它们留在 trade 的 alternation 里。
**与 10-21 21:23 轮记录的 4 个冲突区同构**（那轮是 6.3.2.1 / 6.3.1.3 / 6.6.2.1 / 11.4.1.1，
本轮换成了 14.2.2.1 / 14.3.1.1 / 14.1.1.2 / 14.3.2.2 —— 集成分支每批新交付广告模块，
都会在 `api-proxy.ts` 产生一组新的 ours-only 冲突区）。

#### `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` —— 本轮新增冲突面

```
base=349 ours=621 theirs=659
区1: 行152-156  ours=1行  theirs=1行
区2: 行184-213  ours=0行  theirs=27行
区3: 行220-235  ours=5行  theirs=8行
```

theirs 相对 base 新增行中 ours 完全没有的：**36 行**，全部属于 main 侧 a105acf0 引入的
**「落地树哈希」判据**段落：

```
MT_OUT=$(git merge-tree --write-tree --name-only upstream/main upstream/integration/module-dev 2>&1)
MAIN_TREE=$(git rev-parse upstream/main^{tree})
[ "$MT_TREE" = "$MAIN_TREE" ] && echo "零变更：本轮不开 PR" || echo "有新增内容：走第 4–7 节"
```

⇒ 这是一个**协议文档**（另一个 cron 任务的 prompt 源文件），内容上是 main 侧对
「集成分支并入主分支」这一**反向**任务判据的修正，与本任务（main → 集成头）无业务关系。
⚠️ 本任务**不动其他 cron 任务**，故不解此冲突。

### 六、abort 与状态核对

```
$ git merge --abort
$ git status --porcelain          # 空
$ git rev-parse HEAD              # bf6179b83280d36255203e0b3edee65c9cdcca83
$ test -f .git/MERGE_HEAD         # 无 MERGE_HEAD 残留
```

⚠️ `git rev-parse --git-dir` ⇒ `/Users/guan/Projects/starcon/.git/worktrees/integration`
（worktree 的 `MERGE_HEAD` 在真实 gitdir 下，`git merge --abort` 已正确清理）。

### 七、台账与闸收尾

```
$ node tools/module-progress/sync.mjs --check
✓ 台账与蓝图一致

| 模块总数 | 465 |
| 已交付   | 301 |
| 进行中   | 0   |
| 未开发   | 164 |  完成率 64.7%
```

闸：期间按 `PIPELINE.md` 续期口径 `touch .dev-agent/dispatch.gate/keepalive-<新名>`
（新建文件刷新目录 mtime，`gate status` 确认 `ageSeconds` 归零）；
收尾 `node tools/module-progress/lane.mjs gate release`（本轮**确实 acquire 成功过**，符合协议前提）。

## 后续待办

1. **本轮未 push 任何合并内容**，集成头仍是 `bf6179b8`，流水线 L2 基线未受污染。
2. **`api-proxy.ts` 的 ours-only 冲突区会随集成分支的广告交付批次换一批**
   —— 10-21 21:23 轮是 6.3.2.1/6.3.1.3/6.6.2.1/11.4.1.1，本轮是 14.2.2.1/14.3.1.1/14.1.1.2/14.3.2.2。
   冲突区**数量恒为 4**（每个广告模块一个区），但**模块会换**。
   ⇒ 需要一个**机制性**解法（把 `services[]` 的生成改为按键求并的生成器，
   或让该文件走 `merge-union` 的「按键 = 模块 ID」分支），否则集成分支与 main 永远合不拢。
3. **`MAIN_MERGE_CRON_PROMPT.md` 的 36 行「落地树判据」**需人工确认后并入
   （它属于反向任务的协议，本任务无权修改）。
4. **main 侧仍用 squash 合入**，`merge-base` 停在 `1bb270a0` 已 18 轮。
   只要 main 侧继续 squash，合成分歧就必然持续；根治点在 main 侧的合入方式，不在本任务。
5. main 侧已积累 23 个集成分支没有的文件（15 个历史会话文档 + 8 个 progress-site 工具），
   它们是集成分支回流 main 的副产物，随下一次机制性解冲突时一并并入即可。
