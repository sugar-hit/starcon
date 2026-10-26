# 2026-10-24 · batch-89 —— 回收 12.3.2.2 并入集成头 + 派发 12.3.2.3

> 批次号 **89**（承接 `batch-88`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-88`）。
> 集成头 `integration/module-dev`；本批并入 **1 条**车道：**12.3.2.2 差异处理**。
> 调度者：Mavis（pipeline cron tick，2026-10-24 22:05 触发）。
> ⚠️ 本轮**协议字面顺序被打破**：派发发生在 L2 **之前**（原因与代价见「L2 与派发的顺序」一节）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 回收并入 | **1 条**（12.3.2.2 差异处理）—— `merge-ready` 成功，**零冲突**；merge commit `759d2503` |
| 新迁移 | `V374__reconciliation_difference_handling.sql`（finance）—— **三张新表，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表** |
| 后端服务 | finance（12.3.2.2） |
| 台账 | 398 → **399 已交付**，未开发 67 → **66**，465 行完整性校验通过、无重复 ID |
| 静态预检 | ①`ADD COLUMN` 无 DEFAULT、②`SET NOT NULL`、③`ADD CONSTRAINT CHECK` —— **三项均零命中** |
| L2 批次闸 | **第 1 轮直接 GREEN**：12 个退出码全 0；后端 **668 类 / 10,468 例**、前端 **624 文件 / 8,649 例**全绿，Next build + sync --check + merge-union itest + codegen 单测 + 契约闸 + 强制 typecheck 全 0（22:05:55 → 23:06:55，**61 分 00 秒**） |
| 派发 | **1 条**新车道（`--lanes 4`，编排器只排得出 1 条 —— 结构性原因见「排不出 4 条车道」）；该车道**本轮内跑完并 `land` 成功**，留 `ready` 给下一轮并入 |
| 调度闸 | `gate acquire` 成功（TTL 4200s），收尾 `gate release` |

---

## 模块 12.3.2.2 差异处理

**这个功能做什么**：把对账跑出来的差异变成**一张有约束的工单**——谁负责、为什么差（我方多记 / 对方多记 /
时间差 / 汇率差 / 手续费遗漏）、要不要动账（登记调整分录）、最后**闭环**（判定「已调整」或「已核销」）。
页面上按币种分组、给出分类分布，让「某一类差异反复出现」看得见 —— 这正是蓝图说的
「**差异分类是改进闭环的输入**：某类差异反复出现说明上游流程有系统性问题」。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V374__reconciliation_difference_handling.sql`
  — **三张新表**：`finance_reconciliation_handling`（处理记录：分类归因 + 责任人 + 闭环状态，
  `UNIQUE(tenant_id, difference_id)` ⇒ 一条差异至多一条处理记录）·
  `finance_reconciliation_adjustment`（**调整分录，append-only**）·
  `finance_reconciliation_handling_event`（**处理时间线，append-only**）。
  三表 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS isolation + GRANT（处理记录**不可删**，
  分录与时间线**只给 SELECT + INSERT**）。
  🔴 **上游 12.3.2.1 的四张表一个字未改**（不 ALTER / 不 DROP / 不 ADD COLUMN / **不改它的 GRANT**）。
- 端点：**11 个**，挂 `/finance/reconciliation-differences`：
  `GET /capabilities`（能力声明 + 上游/下游缺口逐条列出）· `GET /options`（真实任务 / 币种 / 责任人 + 受控词表）·
  `GET /`（**差异处理队列**：真分页 + 真筛选 + 真搜索 + **按币种分组汇总** + 分类分布 + 责任人工作量）·
  `GET /{uuid}`（处理详情 + 每条被禁动作的**服务端**阻断理由）· `GET /{uuid}/events`（时间线真分页）·
  `POST /{uuid}/claim`（认领并指定责任人）· `POST /{uuid}/assign`（改派）·
  `POST /{uuid}/classify`（分类归因，**理由必填**）· `POST /{uuid}/adjustments`（登记调整分录）·
  `POST /{uuid}/adjust`（判定「已调整」，终态）· `POST /{uuid}/write-off`（判定「已核销」，终态）。
- 页面：`/finance/reconciliation-differences` —— 真实工作区（队列 + 按币种分组汇总 + 分类分布 +
  单条差异的处理动作 + 调整分录清单 + 闭环时间线），**加载 / 空 / 错误三态齐全，无占位数据**；
  页面**不做任何业务算术**，差额算不出来显示「—（汇率不可得，不按 1:1 兜底）」而**不是 0**。
- 测试：**63 例全绿** —— 后端 40（`ReconciliationDifferenceIntegrationTest` 29 +
  `ReconciliationDifferenceTenancyIntegrationTest` 11，Testcontainers PostgreSQL 16、连接池压到 1，
  三条租户隔离断言跑在**本模块自己的三张表**上）；前端 23（模型 17 + 白名单 6）；
  改了共享白名单 ⇒ **全量 allowlist 回归 246 文件 / 1389 例全绿**。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成（**未手改**）⇒
  `packages/api-contracts/src/contracts/finance.ts` 新增 **26 个 `ReconciliationDiff*` DTO**，
  diff 语义上**纯增量**（22 行新增 / 0 行删除），**其余六服务契约零变化**。

**关键设计决策**：

1. 🔴 **上游差异是「执行时的事实」⇒ 处理记录另起三张表，只推进上游的 `status`**。
   上游挂着 `guard_finance_reconciliation_difference_immutable` 触发器（除 `status` 外每字段不可改写，
   `status` 只能向前）⇒ 「分类 / 责任人 / 结论 / 分录」在物理上无处可放。
   取舍：否决「ALTER 上游表加列」（明令禁止，且 V370 已应用过，改内容会炸 Flyway checksum）、
   也否决「往上游 `finance_reconciliation_event` 塞事件」（那张表 `event_type` 被 CHECK 钉死成任务级事件，
   放行新类型 = ALTER 上游的表）⇒ 代价是多了「上游状态 ↔ 处理记录状态」的同步，
   而这份同步由 `guard_finance_reconciliation_handling_state` 触发器**逐次核对**：
   处理记录写什么状态，上游差异就必须**已经是**那个状态 ⇒ 直连 SQL 也写不出自相矛盾。
2. 🔴 **状态机与上游同词表、同相位**：`OPEN → CLAIMED → ADJUSTED / WRITTEN_OFF`，
   两个终态**并列**（「已调整」= 要动账，**至少要有一条分录**；「已核销」= 不需要动账，**不要求**分录）。
   闭环后**整行冻结**（连分类都不能再改）—— 「处理结论」被改写等于把已经结掉的账重新打开。
3. 🔴 **闭环是一张有约束的工单，不是「改个状态」**：表级 CHECK 规定
   `CLAIMED ⇒ 有责任人`；`ADJUSTED / WRITTEN_OFF ⇒ 有责任人 **且** 有分类 **且** 有结论`；
   **分类理由必填**；**调整分录 = 凭证 或 说明**（`voucher_no IS NOT NULL OR btrim(explanation) <> ''` ——
   蓝图写的是「或」，不是「和」）。判定「已调整」**必须至少有一条分录**，
   由 `guard_finance_reconciliation_handling_close` 在 DB 层再钉一道。
4. 🔴 **归因分类 ≠ 上游差异类型**：上游 `difference_type`（EXTERNAL_ONLY / BOOK_ONLY / AMOUNT_MISMATCH /
   CURRENCY_MISMATCH / DIMENSION_MISMATCH）说「差在哪儿」；本模块 `category` 说「**为什么差**」。
   蓝图那句「改进闭环的输入」指的正是后者 ⇒ 队列汇总必须给出**分类 × 币种**的分布。
5. 🔴 **多币种绝不相加**：每条调整分录**自带币种**，上游跨币种差异 `difference_amount` 恒为 NULL
   （汇率不可得，**不按 1:1 兜底**），一切汇总**按币种分组** ⇒ 结构上不存在「把 USD 和 EUR 加起来」这一格。
   一行差异上**只放分录条数、不放分录金额**（一条差异的分录可能跨币种，行上相加会算错）。
6. **不生成凭证**（下游 12.5.2.1 未交付）：只**登记**分录（凭证号**或**说明），自己造一套凭证 = 造出第二套账。
7. **只存责任人 uuid，不存姓名**（本仓没有用户目录服务），与 08 仓储 `assignee_user_id` 同一口径；页面**不编造姓名**。
8. **权限码沿用 `finance.receivable.read / .update`**，不新造词表（新造一档要改 identity 的迁移 = 别的服务的写入所有权）。
9. **契约修正**：`ReconciliationDiffDetail` **不挂 `handling` 对象** —— 处理记录可能还不存在，
   而 springdoc 对 `$ref` 属性**不保留 `nullable`** ⇒ 一律**摊平成可空的简单类型**。
10. **api-proxy 新增一条独立 `services[]` 元素**（首 token `reconciliation-differences` 对既有 **228** 条正则
    **逐条实测零命中**），插入锚点 = 12.3.2.1 那条之后，既有条目**一字未改、也不重排**；
    `adjustments` 排在 `adjust` **之前**（`adjust` 是它的前缀）。

> 模块自身的完整设计与实测记录见同批并入的
> `docs/sessions/2026-10/2026-10-24-reconciliation-difference-handling.md`（车道自己写的，**已随 merge 进仓**）。

---

## 静态预检

对本批唯一的新迁移（V374）跑「已知互相破坏形状」三项扫描：

| 形状 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 DEFAULT | **零命中** |
| ② `SET NOT NULL` | **零命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **零命中** |

补充核对：V374 = **3 × CREATE TABLE + 0 × ALTER TABLE** + 索引 + 触发器，
RLS 的 `ENABLE` / `FORCE` 作用于**它自己新建的表** ⇒ 对既有表的回归面 = **0**。

⚠️ 表内的 `CHECK` 约束（`category` / `status` / `voucher_no IS NOT NULL OR btrim(explanation) <> ''` 等）
是**新建表自带**的，不是 `ALTER TABLE ... ADD CONSTRAINT`，不落在三项扫描的形状里 ——
这与「往既有表加约束」不是一回事。

---

## 本批派发：12.3.2.3 自动匹配规则（V375）

`plan --lanes 4` **只排得出 1 条**车道（编排器自身的解析口径，本轮不自行挑模块）：

| 车道 | 模块 ID | 名称 | 阶段 | 分支 | worktree | 迁移版本 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 12.3.2.3 | 自动匹配规则 | D6 | `feat/12-3-2-3` | `.worktrees/12.3.2.3` | **V375** |

任务书已按 `PIPELINE.md`「子任务模板」逐项填好并**逐字检查无残留 `<...>` 占位符**
（worker 的 shell 里没有 `$PROJECT_ROOT`，占位符没替换会让它把心跳写进自己 worktree 的 `lanes.json`，
车道会被误判崩掉重派）。任务书额外点名了 **12.3.2.2 正在另一条车道并行开发**及其**禁碰文件清单**，
并把「卷 12 硬约束：不允许『金额相同即匹配』——至少两个独立维度（REFERENCE / DATE / TYPE）」写进硬性要求。

### 该车道在本轮内跑完并 `land` 成功（本轮不并入）

- 车道报告：commit `ac7e569c`（分支 `feat/12-3-2-3`），`land` 返回 `ok: true` ⇒ 状态 `ready`。
- L0 `./mvnw -q compile` EXIT=0 · `pnpm typecheck` 3/3 · `pnpm lint` 3/3。
- L1 `-Dtest='ReconciliationMatchRule*'` ⇒ **22 例全绿**（功能 12 + 租户 10，实跑 Testcontainers PG 16）。
- **上游回归** `-Dtest='ReconciliationTask*'` ⇒ **27 例全绿**（它改了 12.3.2.1 的 `ReconciliationMatcher`
  与 `ReconciliationTaskService`，必须自己复跑上游）。
- 前端本模块 **28 例全绿**（model 21 + allowlist 7）；**全量 allowlist 回归 30 文件 / 154 例全绿**。
- 契约：codegen 重生成 ⇒ 新增 **17 个 DTO + 9 条 path**，既有 schema **0 改动 0 删除**（纯增量）。
- 蓝图点名的五件事全部落地：① 匹配键 ② 容差（汇率差 / 手续费）③ 优先级 ④ 模糊匹配兜底 ⑤ 匹配率目标与监控。
- 关键取舍：蓝图把匹配键写成「订单号 + 金额 + 日期 + 类型」**四项**，
  但卷 12 硬约束说「金额相同只是候选，必须结合订单号、日期、类型」
  ⇒ `match_keys` **只接受 {REFERENCE, DATE, TYPE}**、最少 2 个维度，
  **金额只作容差、永不计入维度个数**（DB CHECK + BEFORE 触发器 + 服务校验 + 前端根本没有「金额」选项 四道闸）。
- 顺带修了 12.3.2.1 的一个隐患：`matchOnce` 原用 `|Δ| ≤ 容差` 会在容差 > 0 时造出两侧合计不等的匹配组，
  直接撞上游「组内合计逐位相等」触发器 ⇒ 改为逐位相等，容差只管候选带；默认容差 0 时行为逐位不变。

⚠️ **本轮刻意不并入 12.3.2.3**：本批 L2 跑的是「12.3.2.2 并入后」的树；
此刻再并入 12.3.2.3 会让**正在跑的 L2 验证一棵已经过时的树** ⇒ 验证失去意义。
⇒ **12.3.2.3 保持 `ready`，由下一轮 tick 并入并做它的 L2。**

---

## 排不出 4 条车道：结构性服务缺口（与 batch-87 / batch-88 同一形状，已到最后一格）

本轮 `plan --lanes 4` 的 `serviceBlocked` 直接给出原因：

| 卷 | 原因 | 模块数（样本 24 / 24） |
| --- | --- | --- |
| **13** | 主责服务 `reporting` **不在集成头** | 23 |
| **18** | 主责服务 `platform` **不在集成头** | 24 |

加上 batch-88 已登记的**蓝图循环依赖 4 个**（`5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3`）
与被两者传递性挡住的 **14 个**，**66 个未开发模块里 65 个当前排不出来**。

⚠️ **本批之后可排模块归零**：12.3.2.3 落地后，`plan` 将返回空数组 ⇒ 流水线进入
「**每轮零派发**」状态，直到 `reporting` 或 `platform` 之一被引导进集成头。
⇒ **停机条件「全部 465 个模块已交付」在当前状态下不可达**，需要人工决策：

- (a) `reporting` 的**事件 → 投影**管道形状（卷首明写「基于事件构建的**可重建**投影，不是业务写入来源」，
  **不能照抄 finance / crm 的形状** ⇒ 属设计决策）；
- (b) `platform` 的「公共能力 + 各服务内的事件 / outbox / inbox」落点；
- (c) 是否解开卷 05 / 卷 15 的两对蓝图循环依赖（属**蓝图语义变更**，协议明令调度者不得自行改锚点）。

⚠️ 三个服务引导都要动 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` /
`.github/workflows/ci.yml` ⇒ **同批并行直接撞车**，每批只能引导一个服务 ⇒ **本轮未擅自引导**。
精确分解与杠杆排序见 P0 backlog
`docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`。

---

## L2 与派发的顺序（本轮打破协议字面顺序，如实记录）

`PIPELINE.md` 的字面顺序是「第 4 步 plan/派发 → 第 5 步 L2」，而
`docs/backlog/2026-10/2026-10-24-l2-before-dispatch-ordering.md`（batch-86 取证，P1）
与 batch-88 的实际执行都把顺序倒过来：**L2 在前、派发在后**，理由是
按字面顺序会让 **L2 与刚发车车道的 L1 并发抢 Testcontainers PG** ——
2026-10-19 实测那种形状下的 `seed()` 503 / 连接拒绝**全是假红**。

本轮的实际顺序：

```
取闸 → merge-ready（0 条，12.3.2.2 在本轮中途落地后补并入）→ sync → gc
     → plan --lanes 4 + 派发 12.3.2.3（22:03 发车）
     → 12.3.2.2 落地 → merge-ready（并入 1 条）→ sync → gc
     → 静态预检(5.1) → L2 全量(5.2) → 写说明 → commit → push (5.3) → gate release
```

⇒ **派发在 L2 之前**（本轮先派 12.3.2.3，12.3.2.2 在派发后才落地），
⇒ L2 运行时**有 1 条在途车道**（12.3.2.3），**不是 batch-88 那种「机器空闲、零在途车道」的干净形状**。
若本批 L2 红，**先按「环境性假红」取证**（隔离复跑该测试类 + 看 surefire 报错是否为
PG 连接握手超时），**不要**直接归因成跨车道互相破坏，也**不要**盲目二分。

---

## L2 批量验证结果

在集成头上跑全量 6 步（`.dev-agent/l2-run.sh`，`FORCE_TYPECHECK=1`）。
**第 1 轮直接 GREEN —— 没有出现假红。**

- 时间：**22:05:55 → 23:06:55（61 分 00 秒）**，日志 `.dev-agent/l2-run-batch-89.log`。
- 12 个退出码**全 0**。

| 步骤 | 退出码 | 实测 |
| --- | --- | --- |
| [1/6] backend `./mvnw verify` | `MVN_EXIT=0` | **9 个 Maven 模块全 SUCCESS**（tenancy / identity / catalog / channel / warehouse / trade / crm / finance）；**668 个测试类 / 10,468 例，零失败零错误零跳过**（channel 2322 · trade 2458 · catalog 1998 · warehouse 1672 · finance 948 · identity 579 · crm 491）；Maven 总耗时 **58 分 27 秒** |
| [2/6] `pnpm typecheck` | `TYPECHECK_EXIT=0` | 绿 |
| [2/6] `pnpm lint` | `LINT_EXIT=0` | 绿 |
| [2/6] `pnpm test` | `TEST_EXIT=0` | **624 个测试文件 / 8,649 例全通过** |
| [3/6] Next production build | `BUILD_EXIT=0` | 绿（`NEXT_TELEMETRY_DISABLED=1`） |
| [4/6] `sync.mjs --check` | `SYNCCHECK_EXIT=0` | 绿（台账与蓝图一致） |
| [5/6] `merge-union.itest` | `ITEST_EXIT=0` | 绿 |
| [5/6] codegen python 单测 | `CODEGEN_EXIT=0` | 绿 |
| [6/6] 契约闸 重新生成 | `CONTRACT_GENERATE_EXIT=0` | 绿 |
| [6/6] 契约闸 逐字节比对 | `CONTRACT_GATE_EXIT=0` | 绿（**零漂移**） |
| [6/6] 契约产物 vs HEAD | `CONTRACT_TREE_DRIFT_EXIT=0` | 绿 |
| [附加] 强制 typecheck（`--force`） | `FORCED_TYPECHECK_EXIT=0` | 绿（本批改了 `packages/api-contracts`，不走 turbo 缓存，防止假绿） |

⇒ **L2 GREEN** ⇒ **本批可以 push**。

### 与 batch-88 的规模对账（本批是**净增量**，没有把别人的东西改坏）

| 项 | batch-88 | batch-89 | 差 |
| --- | --- | --- | --- |
| 后端测试类 | 666 | **668** | **+2** |
| 后端测试例 | 10,428 | **10,468** | **+40** |
| 前端测试文件 | 622 | **624** | **+2** |
| 前端测试例 | 8,626 | **8,649** | **+23** |

⇒ 增量**恰好等于** 12.3.2.2 自己的测试量（后端 29 + 11 = 40 例 / 2 个类；前端 17 + 6 = 23 例 / 2 个文件）
⇒ **既没有删掉别人的测试，也没有引入额外用例** —— 这一条直接反证了「共享文件合并把别人的东西改坏了」。

### ⚠️ 一个与 batch-88 相反的实测结论（值得记）

batch-88 的第 1 轮 L2 **红**了（`channel.IpRiskLibraryTenancyIntegrationTest` 的 PG 连接 SSL 握手读超时），
被判定为「L2 与在途车道 L1 并发抢 Testcontainers PG」导致的**环境性假红**。
本轮形状**相同**（L2 运行时 12.3.2.3 的 L1 正在跑，车道自报其 verify 期间同时存活 **3 个** PG16 容器），
**但第 1 轮直接绿**。

⇒ **修正结论**：「L2 与在途车道 L1 并发」**不必然**产生假红 ——
它是**概率性**的资源争抢，不是必然。batch-88 r1 的红是那次恰好撞上了，
而不是「只要并发就必红」。⇒ 今后遇到 PG 握手超时类失败，
**仍按假红取证流程走**（隔离复跑 + 比对失败面），但**不要**预先假定并发 = 必红，
也**不要**为了怕并发就把 L2 无限往后推（那是零吞吐的另一个极端）。

---

## 收尾三件套

| # | 动作 | 结果 |
| --- | --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-24-batch-89-reclaim-1-lane-dispatch-1.md`（每模块一节：功能 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策）；车道自己的完整记录 `docs/sessions/2026-10/2026-10-24-reconciliation-difference-handling.md` 已随 merge 进仓 | 已落盘 |
| ② commit | ✅ `docs(batch-89): 12.3.2.2 —— 差异处理并入集成头，全量 L2 绿灯`（**本说明文件与台账同一个 commit**） | 已提交 |
| ③ push | ✅ `git push origin integration/module-dev`（**只推集成头，绝不推 `main`**） | 已推送 |

---

## 吞吐预期（按本轮实际档位现算，不引用任何历史文档里的写死数字）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 强度档位 | **full**（`MODE=full`） | 当前时刻 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ 不降档，`LANES=4` / `THROTTLE_MIN=50` |
| 静默窗口 | **不生效**（`SILENT_WINDOW=0`） | 全天运行；⚠️ 温度闸 / 容量闸 / 节流闸**全部照旧生效** |
| cron 栅格 | `0 * * * *` ⇒ 24 触发点/天 | `.dev-agent/PIPELINE.local.md` |
| 节流闸 | 距上批 **120 分钟** ≥ 阈值 50 ⇒ 放行 | 本轮实测 |
| 容量闸 | running 1/6 · PG 容器 0/6 · load 2.78→1.95→3.06（限 12）· therm 无降频 ⇒ **GO** | 本轮实测 |
| **本批实际派发** | **1 条**（不是 4 条） | 编排器只排得出 1 条，见「排不出 4 条车道」 |

**⚠️ 最重要的吞吐结论：当前的瓶颈不是机器，是「可排模块池」。**

- 66 个未开发模块里 **65 个当前排不出来**（47 个缺主责服务 + 4 个蓝图循环依赖 + 14 个被传递性挡住）。
- 本批（12.3.2.3）落地后 ⇒ **`plan` 必然返回空数组** ⇒ 流水线进入「每轮零派发」状态。
- 若三项结构性阻塞全部解除 ⇒ 届时 `plan` 才能一次排满 4 条，名义吞吐回到 **4 × 每小时 ≈ 96 个/天**，
  按协议经验折损系数 ≈ 0.70 ⇒ **实际约 65–70 个/天** ⇒ 剩余 66 个模块约 **1 天**；
  若只解除引导服务（不解循环依赖）⇒ 约 **2–3 天**。
- ⇒ **诚实区间：1–3 天**（前提是三项阻塞被解除）。**在阻塞解除之前，这个数字不成立** ——
  流水线会安静地每轮零派发，直到有人做决定。

---

## 风险提示

1. 🔴 **结构性天花板已到最后一格**（最高优先级）：见「排不出 4 条车道」一节 ——
   停机条件「全部 465 个模块已交付」**当前不可达**，需人工决策 `reporting` / `platform` 的引导形状
   与两对蓝图循环依赖是否解开。
2. 🟡 **本批 L2 不是「机器独占」的干净形状**：派发在 L2 之前（见「L2 与派发的顺序」），
   运行时有 1 条在途车道的 L1 ⇒ 若出现 PG 连接握手超时类失败，**先按假红取证**，别直接归因成回归。
3. 🟡 **12.3.2.1 页面有一句「差异处理属 12.3.2.2（未交付）」已经过时 —— 且被一条测试锁住**（用户可见）：
   - `apps/web/src/components/finance/reconciliation-task-model.ts:397`
     ⇒ `'差异的认领 / 调整 / 核销属 12.3.2.2（未交付）：本页只输出差异清单，不提供处理动作'`
   - `apps/web/src/components/finance/reconciliation-task-model.test.ts:282`
     ⇒ 测试 `it('未处理差异明确说「处理动作属 12.3.2.2（未交付）」')` **把过时文案钉住了**
     ⇒ 只改文案不改测试会红；改测试 = 改**别的模块**的测试文件
   - `apps/web/src/lib/api-proxy.ts:1598`（注释）：解释为什么 12.3.2.1 的 api-proxy 元素
     **刻意不放行** `reconciliation-tasks/{uuid}/differences/{uuid}` —— 理由「差异处理属 12.3.2.2（未交付）」已过时
     （但**该路径本身确实不需要放行**：12.3.2.2 有自己的 `/finance/reconciliation-differences` 路由）
   ⇒ 属 12.3.2.1 的文件，12.3.2.2 车道按纪律**未改动**，交回属主。
   ⚠️ **本轮未擅自改的两个理由**：(a) L2 已在跑，改前端会让「验证的树」与「提交的树」不一致；
   (b) 本轮 L2 运行时**已有 1 条在途车道**，再派一条车道会让它的 L1 与本轮 L2 抢 Testcontainers PG
   （batch-88 r1 的假红正是这个形状）⇒ 留到 12.3.2.3 落地、`plan` 归零时，用**修复车道**处理。
   ⇒ **本批之后 `plan` 必返空数组 ⇒ 修复车道正好是那时的天然派发内容。**
   🟢 顺带澄清：各 finance 页面上的「未交付的上游/下游」**提示框是数据驱动的**
   （`caps.downstreamGaps.filter((gap) => !gap.available)`，由后端能力声明决定）⇒ **随模块交付自动自愈**，
   过时的只是**代码注释**，不是用户看到的文案。
4. 🟢 **导航注册表系统性滞后（P2，非本批引入）**：`packages/ui/src/lib/task-pages.ts` 只登记 78 条，
   真实页面 374 个 ⇒ **323 条已交付模块的页面点不进去**。本批 12.3.2.2 的页面同样**刻意未登记**
   （共享文件，多车道并发改必然冲突）。已登记于
   `docs/backlog/2026-10/2026-10-24-nav-task-pages-registry-systemic-lag.md`。
5. 🟡 **Flyway 版本倒挂（P1，沿用 batch-88 的登记）**：V374（12.3.2.2）**晚于** V371 / V373 并入集成分支。
   不影响 L2（Testcontainers 每次都是全新库），但存量库 `flyway migrate` 会报
   `Detected resolved migration not applied to database: 374`。
   已登记于 `docs/backlog/2026-10/2026-10-24-flyway-out-of-order-version-inversion.md`。
