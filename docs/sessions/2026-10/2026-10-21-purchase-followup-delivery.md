# 6.3.2.1 跟单与交期（卷 06 / L3 6.3.2 采购跟踪的第一格）

- 日期：2026-10-21
- 车道：feat/6-3-2-1（worktree `.worktrees/6.3.2.1`）
- 归属服务：**trade**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项 = trade）

## 决策摘要

| #   | 决策                                                  | 结论与理由                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| --- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ①   | **当前承诺到货日读哪张表**                            | **两个口径并存，页面同时显示**：`documentPromiseDate` = `purchase_order.promised_arrival_date`（**单据上的正式承诺，归 6.3.1.1 所有，本模块只 SELECT**）；`followupPromiseDate` = 本模块交期变更历史里**最后一条**变更的 `to_promised_date`（无变更则等于单据承诺日）。**统计口径（延迟天数）用跟单承诺日**；逾期 / 列表口径仍以单据承诺日为准（6.3.1.1 自己写明「两边若不一致（跟单改了交期而本单没更新），以本单为准」）。不一致时响应里 `promiseDiverged=true`，页面两个日期都摆出来。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ②   | **为什么不改 `purchase_order.promised_arrival_date`** | 三条硬理由：① V254 第 140 行注释原文写死「承诺到货日（对供应商的正式承诺；**6.3.2.1 跟单改的是它的历史**）」；② 6.3.1.2（采购单创建与编辑）**未交付** ⇒ 本仓**不存在**改单据承诺日的授权写入口，裸 UPDATE 会绕过 6.3.1.1 的触发器与不变量；③ V254 的 `guard_purchase_order_status` 对 `CLOSED` 单据冻结了 `promised_arrival_date`（对已关闭的单子改交期会被 DB 直接拒）。⇒ **本模块零 ALTER、零 UPDATE 别人的表**，交期变更落在自己的历史表。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ③   | **延迟天数的口径（含未到货怎么算）**                  | **延迟天数 = 实际到货日 − 到货时刻生效的承诺日**，整数天，**可为负**（提前到货）。「到货时刻生效的承诺日」= 最后一条 `changed_at <= 到货时刻` 的交期变更的 `to_promised_date`，没有则取单据承诺日。⚠️ **多次改期时不是按原始承诺日算** —— 供应商 3/1 答应 3/10 到、3/5 改成 3/20、3/18 到货 ⇒ **提前 2 天（准时）**；按原始承诺日算会得出「逾期 8 天」，把守约说成违约。**未到货的单子不算「延迟天数」**（延迟天数必须有终点），但另计「**已逾期天数**」；两个数分开给。平均延迟在**无已到货样本时是 null（算不出来）而不是 0**。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ④   | **回写 6.2.1.1 / 6.2.1.2 的具体调用方式**             | **两者都调用它们自己的服务方法**（`StockoutRiskService.compute` / `ReplenishmentSuggestionService.compute`），**不是**从 HTTP 打自己 ⇒ 写入所有权与不变量仍归原模块，本模块只**触发**。**绝不**直接 INSERT / UPDATE `replenishment_suggestion*` 与 `stockout_risk_*`。<br>· **6.2.1.1：`available=true` + `delivery_input_applied=true`** —— 交期事实经本模块算出的**有效备货周期**（= 承诺交期天数 + 延迟缓冲）进入它的 `leadTimeDays` 入参，这就是「**交期变更是断货风险的主要输入**」的落点。⚠️ 限制如实写进 `impact`：它的备货周期是**租户级**参数，单张采购单的改期只能以租户级平均生效。<br>· **6.2.1.2：`available=true` 但 `delivery_input_applied=false`** —— 触发它的批次重算（`POST /trade/replenishment-suggestions/batches`），但它的供应商交期取自 **catalog 3.6.2 报价**与它**自有的** `replenishment_order_policy.production_lead_time_days`；本模块的交期事实**没有**合法参数通道进它。唯一能写交期的入口 `upsertOrderPolicy` 写的是**人工策略**，且它会**顶掉**既有那行（含人工维护的起订额与币种）⇒ **本模块不自动写人工策略**，只触发重算并把这个限制如实报出。覆盖天数优先取租户已配置的 `scope='DEFAULT'` 覆盖策略，没有则用调用方兜底值 30（6.2.1.2 的批次会自己记下「来源：计算时传入的兜底值」）。 |
| ⑤   | **6.1.1.3 评级回写**                                  | **未开发 ⇒ 回写不存在**。本模块自己算并输出**准时率 / 平均延迟天数**统计，但**不建**一张「供应商评级」表去冒充 6.1.1.3 的交付物。能力声明与统计响应里都如实标 `available=false` + `ownerModule=6.1.1.3` + 影响说明。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ⑥   | **两个「催单」的关系**                                | 6.3.1.1 的 `POST /trade/purchase-orders/batch/urge` 维护单据上的 `purchase_order.urge_count`（**催过几次**）；本模块的 `URGING` 是**一次具体的沟通**（谁、何时、跟供应商说了什么、供应商怎么答的，**谁在什么时候跟供应商说了什么**）。⇒ **本模块不写 `urge_count`，两张表不合并**，页面也分开表述。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ⑦   | **交期变更与回写的失败语义**                          | 交期变更是**硬事实**（供应商确实改了口），必须先落库；回写是**派生动作**。⇒ **两个独立事务**：变更落库（事务一）→ 回写 6.2.1.1（事务二）→ 回写 6.2.1.2（事务三）。回写失败**不回滚**已落库的变更，只在**再一个**事务里补一条 `FAILED` 留痕（含失败原因与影响说明）。**「回写了没有」是可查的事实，不是一句返回值。**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ⑧   | **时间与数量口径**                                    | 跟单发生时刻 / 交期变更时刻 / 回写尝试时刻是 `timestamptz` ⇒ 用 `OffsetDateTime` 绑定 + values 位置 `?::timestamptz`（`Timestamp.valueOf(LocalDateTime)` 会**静默偏 −28800s**）。承诺到货日是 `date`（业务日历日，**不存时刻**）。延迟天数是**整数天**（`int`），平均延迟用**精确十进制**（`BigDecimal` 两位小数 + `numeric`），**不是浮点**。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

## 关键 trade-off

1. **零 ALTER `purchase_order` vs「跟单改了交期而单据没改」的分叉**
   业务上更「顺」的做法是同步更新采购单的承诺日，让两边永远一致。本模块**不这么做**：
   那张表不是本模块的，6.3.1.2 未交付（没有合法的写入口），而且 V254 的状态机守卫会冻结 CLOSED 单据的该列。
   ⇒ 代价是**两个承诺口径会分叉**，收益是**不越权、不破坏别人的不变量**。
   分叉不是被藏起来，而是**被显式呈现**（`promiseDiverged` + 页面两个日期并列 + 能力声明里写明）。

2. **回写 6.2.1.2 只做「重算触发」而不是「交期数值写入」**
   蓝图要求「交期变更是断货风险的主要输入，必须回写补货建议」，而 6.2.1.2 的供应商交期来自 catalog 报价与人工策略。
   为了让交期数值真的进 6.2.1.2，只能自动写它的**人工策略表**（`replenishment_order_policy`）——
   那会把人工维护的起订额与币种顶掉（其 upsert 是**顶掉 + 新建**，不是合并）。
   ⇒ 本模块**不自动写人工策略**，只触发重算，并在留痕里明确标 `delivery_input_applied=false` + 写清原因。
   **这是一处诚实的「做不到」，不是假装做到。**

3. **6.2.1.1 的备货周期是租户级参数**
   蓝图要的「交期 → 断货风险」在本模块的可用入口里只能以**租户级平均**生效（单张采购单的改期无法按 SKU 精度表达）。
   ⇒ 传的是本模块算出的**有效备货周期**（承诺交期天数 + 延迟缓冲），并把这条限制写进回写留痕的 `impact`，页面也在回写留痕表里直接显示。

4. **延迟统计的样本口径**
   只收 `arrival_status='FULLY_RECEIVED'` 的跟踪单（部分到货的单还没到齐，用它算延迟等于把「到了一半」当成「到了」）；
   承诺交期天数的样本取**未关闭**的采购单（在做的那批单才是当前交期的代表），延迟天数的样本取**已全部到货**的跟踪单（不论单据状态）。

## 文件清单

**后端（trade）**

- `backend/services/trade/src/main/resources/db/migration/V259__purchase_followup_delivery.sql` — 三张新表 + 2 个序列 + 追加式触发器 + RLS + GRANT（**零 ALTER / 零 DROP 既有表**）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseFollowupModels.java` — 传输模型（全部类型带 `PurchaseFollowup` 前缀，避免 codegen 扁平合并时同名覆盖）
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseFollowupService.java` — jOOQ 显式 SQL + 口径计算 + 回写触发
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseFollowupController.java` — 6 个端点 + **三阶段事务**（变更 / 回写 6.2.1.1 / 回写 6.2.1.2）

**测试（trade）**

- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseFollowupFixture.java` — Testcontainers PG16 + identity / warehouse / channel / catalog 四个**真的本地 HTTP 替身**
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseFollowupIntegrationTest.java` — 23 例
- `backend/services/trade/src/test/java/com/starcon/trade/PurchaseFollowupTenancyIntegrationTest.java` — 4 例（租户隔离三断言 + 数据范围 SELF）

**前端**

- `apps/web/src/components/purchase/purchase-followup-model.ts` — 前端模型 + 展示口径
- `apps/web/src/components/purchase/purchase-followup-workspace.tsx` — 真实工作区（四个视图 + 两个登记对话框 + 错误 / 空 / 加载三态）
- `apps/web/src/components/purchase/purchase-followup-model.test.ts` — 模型单测（12 例）
- `apps/web/src/app/purchase/followups/page.tsx` — 页面
- `apps/web/src/app/api/trade/purchase-followup-allowlist.test.ts` — 白名单**双向**守卫（真跑 proxy）
- `apps/web/src/lib/api-proxy.ts` — **只追加**一条 `purchase-followups` 分支（多车道共用的累积型文件，插入锚点固定在 `purchase-orders` 之后）

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json` — codegen 产物（**纯新增 6 条路径 + 15 个 schema，其余 5 个服务逐字节不变**）
- `docs/product/capability-blueprint/module-progress.md` — 只改 6.3.2.1 那一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-21-purchase-followup-delivery.md` — 本文件

## 验证

```
./mvnw -q compile                                   → 退出 0
./mvnw -pl services/trade -am test -Dtest='PurchaseFollowup*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                   → Tests run: 27, Failures: 0, Errors: 0
pnpm typecheck                                      → 3/3 通过
pnpm vitest run apps/web/src/app/api/trade          → 58 文件 315 例全绿（api-proxy 共享文件 ⇒ 全量 allowlist 回归）
pnpm vitest run apps/web/src/components/purchase packages/ui/src/lib/nav-routes.test.ts
                                                   → 16 文件 257 例全绿
```

## 后续待办

1. **6.3.1.2 采购单创建与编辑交付后**，需要一个**授权写入口**把跟单承诺日同步到采购单的 `promised_arrival_date`；
   在那之前两个口径会一直分叉（页面已如实呈现，不隐藏）。
2. **6.1.1.3 供应商评级交付后**，本模块的准时率统计需要一个真正的回写通道；当前 `available=false`。
3. **6.2.1.2 若允许用测量值驱动交期**，需要 6.2.1.2 自己提供「按供应商写交期且**合并**（而不是顶掉）人工策略」的入口；
   否则「交期数值进补货建议」这件事在 6.2.1.2 侧始终只能停在「重算触发」。
4. **6.2.1.1 的备货周期是租户级参数**：若要按 SKU 精度表达单张采购单的交期影响，
   需要 6.2.1.1 的快照入参支持逐 SKU 备货周期。
5. 本模块的**延迟统计**目前依赖 6.3.2.2 的到货事实；当 6.3.2.2 补录到货流水时，历史延迟会随之变化（口径不变）。

## 并入冲突修复（2026-10-21 第二轮，同一车道 `feat/6-3-2-1`）

上一轮并入集成头时 `apps/web/src/lib/api-proxy.ts` 冲突，本车道被标 `conflict` 未并入。
根因：集成头先后并入 **6.3.1.2 采购单创建与编辑**（正则首 token `purchase-order-editor`）
与本模块 **6.3.2.1**（首 token `purchase-followups`），两者都把新 `services[]` 元素插在
「6.3.1.1 采购单列表（`purchase-orders`）」之后，git 无法自动求并。

本轮把车道 rebase 到集成头 `caffaa91`（`git rebase caffaa91`），并按**求并集**解决：

| 文件 | 做法 | 校验 |
| --- | --- | --- |
| `apps/web/src/lib/api-proxy.ts` | 冲突块整理成**两个元素并存**，顺序 `purchase-orders`(6.3.1.1) → `purchase-order-editor`(6.3.1.2) → `purchase-followups`(6.3.2.1)；两个元素的注释块与 `pattern` 完整保留 | 6.3.1.2 元素相对集成头**逐字节一致**（取单边会静默删掉别人已交付的路由 ⇒ 页面 404 而 typecheck/lint/vitest 全绿）；整文件相对集成头只**新增** 6.3.2.1 那一条元素 |
| `packages/api-contracts/**` | 按「类型名 / OpenAPI 路径」求并集 | `trade.openapi.json` 相对集成头 **+6 条路径、+15 个 schema，零删除、零丢失**；`identity`/`catalog`/`channel`/`warehouse`/`crm` 五份契约**逐字节不变** |
| `docs/product/capability-blueprint/module-progress.md` | 按**模块 ID** 求并集 | 相对集成头**只变 6.3.2.1 那一行**（未开发 → 已交付 + 证据）；总览 / 分阶段 / 分系统统计行保持与集成头一致 |

> ⚠️ **台账统计行为何没跟着改**：集成头 `caffaa91` 与本车道原提交的台账**本就未通过**
> `node tools/module-progress/sync.mjs --check` —— 统计行是调度器另行 `sync` 生成的产物。
> 本车道若在这里跑 `sync.mjs`，会连带**截断 6.3.1.2 的证据格**（该格含 `|`，
> 而 sync 的行正则 `([^|\n]*)` 遇 `|` 即止）⇒ 静默 degrade 他人模块的交付记录。
> ⇒ 本车道**不跑 sync、不手改统计行**，只保留自己那 6.3.2.1 一行。

**迁移版本号**：仍是 `V259__purchase_followup_delivery.sql`（**没有建 V263**）。
车道 state 里的 `migrationVersion: V263` 是上一轮 plan 的过期预分配，该字段只用于
`plan` 阶段占位版本号，**不会据此生成迁移文件**；V259 与集成头已有的 V258 / V261 无冲突。

**rebase 后的复验（真实结果）**：

```
./mvnw -q compile                                   → 退出 0
./mvnw -pl services/trade -am test -Dtest='PurchaseFollowup*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                   → Tests run: 27, Failures: 0, Errors: 0
                                                     （PurchaseFollowupIntegrationTest 23 例 +
                                                       PurchaseFollowupTenancyIntegrationTest 4 例，
                                                       租户隔离三断言未删）
pnpm typecheck                                      → 3/3 通过
pnpm vitest run apps/web/src/app/api/trade          → 60 文件 330 例全绿
                                                   （比上一轮的 58 文件 315 例多 2 个文件：
                                                    并行车道新增的 purchase-order-edit /
                                                    development-sample 两条 allowlist 用例）
pnpm vitest run apps/web/src/components/purchase/purchase-followup-model.test.ts
                                                   → 1 文件 14 例全绿
```

**未随本轮改变的既有结论**：上方「决策摘要」与「后续待办」是本模块**首轮**的设计结论，
本轮只做并入冲突修复，未重审。其中「后续待办 1」提到的 **6.3.1.2 未交付**这一前提
已在集成头失效（6.3.1.2 已并入 `caffaa91`）—— 跟单承诺日是否同步到采购单
`promised_arrival_date`，属于**下一个模块的评审范围**，本车道不擅自实现。
