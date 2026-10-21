# 14.2.2.1 预算分配（阶段 D7，L3 锚点 14.2.2 预算）

- **日期**：2026-10-22
- **车道**：`feat/14-2-2-1`（worktree `integration/.worktrees/14.2.2.1`）
- **蓝图原文**（`docs/product/capability-blueprint/14-ads-and-growth.md` 143-148 行）：
  > 职责：跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，识别预算受限的活动与闲置预算。
  > 输入：预算总额；分配规则；各活动历史表现。
  > 输出：分配方案 + 再平衡建议 + 执行任务。
  > 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。**预算应向利润高的活动倾斜**，按销售额平均分配是常见浪费。

## 决策摘要

### 1. 🔴 蓝图加粗句「预算应向利润高的活动倾斜」的落地形态 = **可插拔的权重位**，不是常量

三个来源（销售目标 / 历史 ROAS / 利润）被建模成 `ad_budget_rule_weight` 的**三行权重**，
而不是把某个来源写死进算法。新增来源 = 新增一行权重 + 一段分值计算，**评分与分摊算法不用改**。

归一化口径是这一层最容易被做错的地方：**先在活动之间把每个来源换成分额（share ∈ [0,1]），
再乘权重相加**。销售目标是钱（金额量纲）、ROAS 是比率，量纲不同；直接相加会让「目标大的活动」
和「效率高的活动」互相污染，算出来的分配额谁也解释不清。

### 2. 🔴 13.4 利润未交付 ⇒ **不用假利润顶替**，但把位子留好

卷 13 的主责服务 reporting 不在集成头，利润维度**当前没有数据源**。三道处理：

- **DB 层**：`CHECK (weight_source <> 'PROFIT' OR weight = 0)` —— 绕服务层直接写 SQL 也写不进非零利润权重；
- **服务层**：非零利润权重返回 409，并把理由说成「13.4 尚未交付，13.4 交付后这一档会自动放开」，
  而不是「参数非法」；
- **界面层**：capabilities 的 `profit_weight` 维度显式 `available=false` + `ownerModule` + 影响说明，
  页面上常驻一条说明，让人看见「现在只按销售目标与历史 ROAS 分配」。

13.4 到位后：放开 CHECK、把利润分值接进评分，**一行算法都不用改**。

### 3. 🔴 币种：一个预算池一个币种，**不折算**

5.2.2.1 汇率查询未交付，本仓没有可用汇率来源。跨币种分配只有两条路：用假汇率折算（错得离谱且无人察觉），
或者**不参与分配**。选后者：币种不一致的活动逐条列进 `ad_budget_plan_skipped` 并写明 `CURRENCY_MISMATCH`，
预算池币种必须与规则一致（不一致直接 400）。

### 4. 🔴 分配额之和**严格等于**池总额；单活动上下限是软约束、池总额是硬约束

少分是预算凭空消失，超发是超预算，两头都是事故。用**最大余数法**（先向下取整到 0.0001，
再按小数部分从大到小补回，并列按活动 id ⇒ 可复现；照抄 5.2.1.3 同款做法），DB 层还有
`CHECK (allocated_amount + unallocated_amount = total_amount)` 兜底。

上下限与总额冲突时**总额优先**（池里只有这么多钱，许诺超出手里的钱就是超发预算）：
上限挡下的部分留成 `unallocated_amount`，下限要抬高某条活动时只能用池里剩下的空间，
否则在依据里写明「受池总额限制未满足」。

### 5. 🔴 闲置预算与预算受限是**两种相反的事实**，必须分开落库

闲置 = 有预算却花不出去（钱在睡觉）；受限 = 想花却没预算（钱不够）。混成一个「预算利用率」数字，
就没人能回答「该给谁加钱」。所以 `idle_budget` / `budget_constrained` 两列 + 汇总两个数分开放。

口径：日均消耗 = 窗口内总消耗 ÷ **窗口自然日数**（对所有活动一致，口径可复算）；利用率 = 日均消耗 ÷ 日预算。
日预算为空/0、**或窗口内没有任何每日指标** ⇒ 利用率**不可比（NULL）**而不是 0 ——
「压根算不出来」与「真的是零」在自动调预算里是相反的两件事。

### 6. 🔴 执行任务**走 14.1.1.1 的写入口**，不另开写路径

`ad_campaign` 是 14.1.1.1 的事实表（**每张事实表只有一个写入所有者**）。本模块的
`ad_budget_task` 不直接 UPDATE 它，而是调用 **`AdCampaignService.bulkOperation`**
（同服务内模块间调用，与 14.1.3.1 → 14.1.2.2 的既有写法同构）。四道闸逐条判定，
超限的**逐条 REJECTED 并留痕**（绝不静默截断）；改动只落本地并标记 `pending_platform_writeback`。
代价：执行任务的逐条结果要自己从上游返回里映射；收益：预算的写路径只有一条，
不会出「两份代码各自改预算，最后谁也说不清预算是谁改的」。

### 7. 🔴 再平衡建议与分配方案是**两个独立视角**

分配方案回答「这笔预算池怎么摊」（**改总额**）；再平衡回答「当前分布里钱该从谁挪给谁」
（**总额不变**：可挪额度 = 当前日预算 − 日均消耗/闲置阈值，缺口 = 日均消耗 − 当前日预算）。
两者都基于同一份历史表现但口径不同，分成两张表，界面上也分开呈现。

## 关键 trade-off

| 选择 | 代价 | 收益 |
| --- | --- | --- |
| 利润权重位留好但恒为 0 | 现在少了一个分配维度（只按销售目标 + ROAS） | 13.4 到位后算法不用改；且**不可能**算出带假利润的预算额 |
| 跨币种不折算、不参与分配 | 跨币种店铺的预算要分池管 | 不会用假汇率折算出错的金额 |
| 统计窗口必须显式给出 | 界面上要多填两个日期 | 口径可复算、可断言；「上个月」这种键在月初月末会圈出不同范围 |
| 池总额硬约束、上下限软约束 | 有些下限在池不够时满足不了 | 池里的钱不会被超发出去 |
| 执行任务复用 14.1.1.1 的写入口 | 逐条结果要自己映射 | 预算写路径只有一条 |
| 方案明细是冻结快照 | 指标后来变了不回写历史方案 | 能复盘「当时为什么这么分」 |

## 文件清单

**后端（channel）**

- 迁移：`backend/services/channel/src/main/resources/db/migration/V278__ad_budget_allocation.sql`
  （10 张表 + 索引 + RLS isolation + data_scope + GRANT；零 ALTER 既有表）
- `backend/services/channel/src/main/java/com/starcon/channel/AdBudgetAllocationModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdBudgetAllocationService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdBudgetAllocationController.java`
- 测试：`backend/services/channel/src/test/java/com/starcon/channel/AdBudgetAllocationIntegrationTest.java`（14 例）

**前端**

- `apps/web/src/app/ads/budgets/page.tsx`
- `apps/web/src/components/ads/ad-budgets.tsx`
- `apps/web/src/components/ads/ad-budget-model.ts`
- `apps/web/src/components/ads/ad-budget-model.test.ts`（23 例）
- `apps/web/src/app/api/channel/ad-budget-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`：**新增一条独立的 `ad-budgets/` 元素**，锚点固定在
  `sku-cost-compositions`（5.2.1.1）那条**之前**；既有条目一字未动、不重排

**契约 / 台账 / 日志**

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  （codegen 产物，**+12 path / +29 类型 / 0 删除**）
- `docs/product/capability-blueprint/module-progress.md`（只改 14.2.2.1 那一行的「状态」「证据」两格）

## 🔴 顺手修掉的一个真 bug（契约同名覆盖）

本模块的护栏 record 原名 `AdBudgetGuardrails`，与 **14.1.1.1 的同名**。
SpringDoc 对同名 schema 会**静默丢掉后一个**：生成出来的契约里，14.1.1.1 的
`AdCampaignCapabilities.guardrails` 被指向了**本模块**的类型（字段 `maxCampaignsPerRequest`
被换成 `maxCampaignsPerTask`）—— 契约与后端不一致，而 typecheck / lint / vitest / Playwright **全部全绿**。

已改名 `AdBudgetAllocationGuardrails`。判据：生成后 `channel.ts` 里**没有任何重复的 `export type` 名**，
且两个护栏类型并存、字段各归各家。**教训**：在 channel 服务里新增 record 时，
类型名要与 `AdCampaign*` / `AdKeyword*` / `AdRule*` 家族都错开，不能只看前缀。

## api-proxy 白名单：首 token 零命中核对结论

在本 worktree 的 `apps/web/src/lib/api-proxy.ts`（6585 行，100+ 条 `services[]` 正则）里核对：
**`ad-budgets` 这个串在既有正则里零命中** —— 既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` /
`ad-type-diffs` 一类，所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
插入锚点选在 `sku-cost-compositions`（5.2.1.1，`prefix: 'catalog/'`）那条**之前**，
与同批另三条车道的锚点相距数百行 ⇒ git 能干净求并。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 退出 0 |
| L0 类型 | `pnpm typecheck` | 3/3 通过 |
| L0 lint | `pnpm lint` | 本车道零告警（其余告警是既有模块的） |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='AdBudgetAllocation*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **14 例 0 失败 0 错误** |
| 前端本模块 | `pnpm vitest run apps/web/src/components/ads/ad-budget-model.test.ts apps/web/src/app/api/channel/ad-budget-allowlist.test.ts` | **28 例全绿** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade` | **116 文件 608 例全绿** |

PG 容器：本车道 verify 期间同时存活 **1 个** `postgres:16-alpine` Testcontainers 容器
（另有 1 个 `postgres:15-alpine` 是本机 langfuse 基础设施，与本车道无关）；
测试 JVM 退出后**已被 ryuk 回收**（`docker ps` 复核为空）。

## 后续待办

1. **13.4 利润模块交付后**：放开 `ad_budget_rule_weight` 上 `PROFIT ⇒ weight = 0` 的 CHECK，
   把利润分值接进评分（归一化成 share 的那一步），并把 capabilities 的 `profit_weight` 维度改成 `available=true`。
   **评分与分摊算法不用改。**
2. **5.2.2.1 汇率查询交付后**：可以把跨币种活动纳入分配（折算口径必须写进返回值，不能只在页面上说）。
3. **平台回写队列交付后**：`ad_budget_task` 的执行结果要能反映平台侧的真实回执，
   而不只是「本地已生效 + 待回写」。
4. **上游若交付销售目标下达模块**：`ad_budget_rule_target.sales_target` 的人工登记要被替换掉，
   `sales_target_source` 维度的 `ownerModule` 要跟着改。
5. 「按分配方案自动建 14.2.2.2 自动规则」的通道**不归本模块**；若要做，要走 14.2.2.2 的写入入口，
   不是本模块直接写 `ad_rule` 表。
