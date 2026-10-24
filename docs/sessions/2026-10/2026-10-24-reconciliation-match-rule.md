# 2026-10-24 · 12.3.2.3 自动匹配规则

> 阶段 D6 · L3 锚点 12.3.2（卷 12「财务核算」）· 后端主责服务 finance · 车道 `feat/12-3-2-3`

## 决策摘要

**做什么**：把 12.3.2.1 的「自动匹配」从**写死在代码里**变成**可配置、可持久化、可统计**的规则集，
并给出蓝图点名的五项能力：匹配键、容差（汇率差 / 手续费）、优先级、模糊匹配兜底、匹配率目标与监控。

**五项能力逐条的落地方式**：

| 蓝图要求 | 落地 | 在哪 |
| --- | --- | --- |
| 匹配键（订单号 + 金额 + 日期 + 类型） | **匹配键里剔掉金额**：只能取 {REFERENCE, DATE, TYPE} 且至少两个；至少对上的维度数 ≥ 2 | `finance_reconciliation_match_rule.match_keys` / `min_dimension_count` |
| 容差（汇率差、手续费） | 三种容差各有口径：金额容差 = 候选配对带；手续费 / 汇率差容差 = 残差**归因**带 | `amount_tolerance` / `fee_tolerance` / `exchange_rate_tolerance` |
| 优先级 | 数字越小越先试；激活态**同租户内优先级唯一**（partial UNIQUE index）⇒ 次序确定 | `priority` + `..._active_priority_uk` |
| 模糊匹配兜底 | 放松「怎么比」（订单号按前缀比、窗口放宽），**不放松「要比几个」**（仍 ≥ 2 个维度） | `fuzzy_*` 列 + `ReconciliationMatcher` 模糊阶段 |
| 匹配率目标与监控 | 匹配率 + 未匹配原因分布 + 快照历史，全部来自 12.3.2.1 的真实结果行 | `finance_reconciliation_match_rate` / `..._reason` |

## 关键 trade-off

### 1. 🔴 蓝图写「匹配键 = 订单号 + 金额 + 日期 + 类型」，但硬约束说「金额相同只是候选」

**取舍**：按**硬约束**执行 —— 匹配键里**没有金额**。蓝图那句是四项并列的日常表述，
而卷 12 的加粗句是**结构性硬约束**（「不允许『金额相同即匹配』」）。
⇒ 金额只以**容差**出现，**不计入维度个数**。

**代价**：蓝图字面上「金额」这一项没有成为一个可勾选的匹配键。
**补偿**：能力声明里写清为什么剔除；页面上**根本没有「金额」这一档**；
服务层 400 提示点名「金额不在匹配键里」；DB 层 CHECK + 触发器双保险。

### 2. 规则怎么绑到对账任务上：不动 12.3.2.1 的表

**取舍**：规则**自己声明适用范围**（币种 / 账户 / 店铺，可空 = 全部），引擎按任务口径挑规则。
**代价**：一条规则只作用于**特定范围**的任务；租户要「全部适用」就留空。
**备选与否决理由**：给 `finance_reconciliation_task` 加 `rule_id` 列要 **ALTER 上游表** ——
那是 12.3.2.1 的写入所有权，违反模块边界纪律。

### 3. ⚠️ 容差**不放宽「配平」**

12.3.2.1 的 DB 触发器 `guard_finance_reconciliation_match_group_amount` 要求组内两侧合计**逐位相等**。
⇒ 容差只作用在**候选配对带**与**残差归因带**，不去 ALTER 那个触发器。

**顺带修掉一个上游隐患**：「先吃金额相等的候选对」那一趟原写 `|Δ| ≤ 容差`，
容差 > 0 时会造出「两侧合计不相等的匹配组」⇒ 直接撞上游触发器报错。
本车道把它改成**逐位相等**，容差只管候选带（这才是它该管的事）。
⇒ 12.3.2.1 既有测试 **27 例复跑全绿**（默认容差 0 ⇒ 行为逐位不变）。

### 4. 「判不了」是一档独立状态

匹配率**分母为 0** ⇒ 比率恒为 `null`（**不写 0**，0 会被读成「一条都没配上」）；
**没有启用中的规则**（没有目标）⇒ 达标与否**判不了**，不是「未达标」。
前端 `matchRateTargetVerdict` 把「判不了」与「未达标」分成两个不同色调。

### 5. 匹配率的口径

匹配率 = 匹配行数 ÷（匹配行数 + 未匹配条数），两个数都来自 12.3.2.1 的真实结果行。
**按币种分组，绝不跨币种合计**（12.7.1.1 账户单一币种）。
未匹配原因分布**扩充**了 12.3.2.1 的五种差异类型：残差落在手续费 / 汇率差容差带内的**另归两类**
⇒ 「这批没对上的主要是手续费，不是账错了」一眼可见。

## 文件清单

### 新增

- `backend/services/finance/src/main/resources/db/migration/V375__reconciliation_match_rule.sql`
  —— 四张新表（规则集 / 规则事件流 / 匹配率快照 / 未匹配原因分布）+ RLS + GRANT + 六道守卫
- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationMatchRuleModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationMatchRuleService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationMatchRuleController.java`
- `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationMatchRuleIntegrationTest.java`（12 例）
- `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationMatchRuleTenancyIntegrationTest.java`（10 例）
- `apps/web/src/components/finance/reconciliation-match-rule-model.ts`
- `apps/web/src/components/finance/reconciliation-match-rule-model.test.ts`（21 例）
- `apps/web/src/components/finance/reconciliation-match-rule-workspace.tsx`
- `apps/web/src/app/finance/reconciliation-match-rules/page.tsx`
- `apps/web/src/app/api/finance/reconciliation-match-rules-allowlist.test.ts`（7 例）
- `docs/sessions/2026-10/2026-10-24-reconciliation-match-rule.md`（本文件）

### 改动

- `backend/.../ReconciliationMatcher.java` —— **规则驱动改造**（新增 `Spec` + 规则集入口 + 模糊阶段；
  旧的 `match(external, book, windowDays, tolerance)` 保留且行为逐位不变）
- `backend/.../ReconciliationTaskService.java` —— 匹配时优先用启用中的规则集，没有则退回任务自己的参数
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（首 token `reconciliation-match-rules`）
- `packages/api-contracts/src/contracts/finance.ts` / `packages/api-contracts/src/finance.openapi.json`
  —— codegen 产物（**未手改**）：新增 17 个 DTO + 9 条 path，既有 0 改动 0 删除
- `docs/product/capability-blueprint/module-progress.md` —— **只改自己那一行**的状态与证据两格

### 刻意没碰

- 12.3.2.2「差异处理」的全部文件（另一条车道）
- 12.3.2.1 的四张表与端点（只读，不 ALTER / 不 DROP / 不加触发器 / 不改 GRANT）
- `packages/ui/src/lib/task-pages.ts`（多车道共享，留给调度者统一登记）

## 验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | exit 0 |
| L0 类型 | `pnpm typecheck` | 3/3 通过 |
| L1 模块闸 | `./mvnw -pl services/finance -am test -Dtest='ReconciliationMatchRule*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **22 例全绿**（功能 12 + 租户 10），实跑 Testcontainers PostgreSQL 16 |
| 回归（改了上游引擎） | 同上 `-Dtest='ReconciliationTask*'` | **27 例全绿**（12.3.2.1 既有行为未变） |
| 前端本模块 | `pnpm vitest run <model.test.ts> <allowlist.test.ts>` | **28 例全绿** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api/finance` | **30 文件 154 例全绿**（改了 api-proxy.ts ⇒ 必跑） |
| Lint | `pnpm lint` | 3/3 通过（本车道零告警） |

## 后续待办

- **12.3.2.2 差异处理**（另一条车道）：接本模块的未匹配原因分布做差异归类与处理闭环。
  本模块**只统计**、不处理。
- **12.7.2.1 汇率敞口**：真正的跨币种折算与汇率敞口归它。
  本模块的汇率差容差只是**同币种**下两侧折算口径不同的残差带，**不折算**、跨币种差额恒为 null。
- **调度者**：台账顶部计数（已交付 398 → 399 / 未开发 67 → 66）由合并时统一更新，本车道**只改自己那一行**。
- **调度者**：`packages/ui/src/lib/task-pages.ts` 的导航登记（多车道共享文件，本车道刻意未动）。
- 规则改了只对**之后新建**的对账任务生效 —— 12.3.2.1 的已执行任务结果**不可改写**（重跑 = 新建任务）。
