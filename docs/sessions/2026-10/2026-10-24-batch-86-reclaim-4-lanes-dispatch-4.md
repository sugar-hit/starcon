# 2026-10-24 · batch-86 —— 回收 4 条车道并入集成头 + 派发 4 条新车道

> 批次号 86（承接 `batch-85`，取号时已确认目录内最大为 `batch-85`）。
> 集成头 `integration/module-dev`，本批并入 4 条车道：**12.6.1.2 / 12.6.2.1 / 12.7.1.2 / 1.1.1.1**。

## 本批概览

| 项 | 值 |
| --- | --- |
| 回收并入 | 4 条（12.6.1.2、12.6.2.1、12.7.1.2、1.1.1.1）—— `merge-ready` 全部成功，**零冲突** |
| 新迁移 | `V366__finance_invoice_management.sql`、`V368__filing_draft.sql`、`V369__bank_reconciliation.sql`（1.1.1.1 无迁移） |
| 后端服务 | finance（3 条车道）· 前端聚合层（1 条车道） |
| 台账 | 388 → **392 已交付**，未开发 77 → **73**，465 行完整性校验通过、无重复 ID |
| 静态预检 | ①`ADD COLUMN` 无 DEFAULT、②`SET NOT NULL`、③`ADD CONSTRAINT CHECK` —— **三项均零命中** |
| L2 批次闸 | 全量 6 步，`MVN_EXIT=0 / TYPECHECK_EXIT=0 / LINT_EXIT=0 / TEST_EXIT=0 / BUILD_EXIT=0 / SYNCCHECK_EXIT=0 / ITEST_EXIT=0 / CODEGEN_EXIT=0 / CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0` → **L2 GREEN**（16:09:01 起，17:07:39 结束，日志 `.dev-agent/l2-run-batch-86.log`） |
| 派发 | 4 条新车道（见文末「本轮派发」） |

---

## 模块 12.6.1.2 VAT/IOSS 申报数据

**这个功能做什么**：按辖区与周期把订单税务数据、结算收付款数据汇总成一份**可核对的申报底稿**——可导出发票文件或对接申报接口。底稿自带税率版本与汇率快照，用户能复核「这���数字是按哪一版税率算出来的」。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V368__filing_draft.sql` — 三张新表（`finance_filing_draft` 底稿头 · `finance_filing_draft_line` 汇总行，**按币种分组** · `finance_filing_draft_export` 导出留痕）。纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表。全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT。**版本号第二轮由 V364 改为 V368**（V364 低于集成分支已应用的 V367，是真倒挂）。
- 端点：8 个，挂 `/finance/filing-draft`；权限 `finance.filing_draft.read` / `.create`。
- 页面：`/finance/filing-draft` —— 申报底稿列表 + 生成底稿 + 汇总报表 + 导出；加载 / 错误 / 空三态齐全。
- 测试：后端 **25 例**（19 + 6）+ 前端 **22 例**（17 + 5）；全量 api allowlist 回归 233 文件 / 1315 例。契约新增 15 个 `FinanceFiling*` 类型（+15/-0，codegen 产物未手改）。

**关键设计决策**：

1. **「不替代税务申报动作」落成结构而不是文案** —— 三张表里**根本没有**「申报状态 / 受理号 / 已提交」列，api-proxy 也刻意不放行 `filing-draft/submit`。底稿一旦生成即**不可改写快照**（DB 触发器钉死辖区 / 期间 / 口径 / 覆盖情况 / 税率版本；状态只允许 `GENERATED → SUPERSEDED` 一次；部分唯一索引保证同时只有一份生效底稿）。重新取数 = 旧底稿作废 + 新起一版。
2. **多币种绝不相加** —— 汇总行按（底稿 × 币种）唯一 ⇒ **底稿头不能有金额列**，一有就会诱导出「跨币种总额」这种无意义的数。折算列 `converted_net_payable` 只在汇率真的取到时才有值，取不到时为 NULL 且 `exchange_rate_available=false`，**绝不按 1:1 兜底**。
3. **可抵扣进项：改口径，不造表** —— 仓库里没有「进项发票」概念。建一张空表再从里面读出 0 是**假装有数据**；改为从 `finance_voucher`（POSTED）→ `finance_voucher_line` → `finance_account_subject` 取「应交税费类 + 税务属性 TAXABLE/REVERSE_CHARGE」的借方发生额。代价是口径依赖用户自己维护的科目属性，取不到时如实报「无可抵扣进项数据」并**不产出应缴** —— 假的 0 会直接变成一份错的申报底稿。
4. **辖区归属靠收货国** —— trade 的 `sales_order` 没有辖区字段也没有 `shop_id`，唯一能判辖区的事实是 `order_address_snapshot.country_code`。逐单拉明细是 N+1 ⇒ 设 500 单上限，且**上限截断必须让人看见**（`order_truncated=true` + 口径说明写明「这不是该期间的全部销售」）。
5. **列表读不到 ⇒ 整个取数 503，不留半份底稿** —— 明细读不到只影响一张单（计入 `unmapped_count`）；**列表**读不到意味着整个期间的销售事实都没有，此时生成「0 张订单的底稿」比不生成更危险。
6. **IOSS 清单读不到 ⇒ 留空而不是 0** —— 16.3.2.1（V365）同批开发中，`ChannelIossPort` 刻意不把上游当硬依赖，404 一律显式失败并把 IOSS 两列留成 NULL。**绝不因为上游没交付就编一份 IOSS 清单出来**。
7. **IOSS 代收金额不并进已收 VAT** —— 两者口径不同，合并会让申报口径失真。

---

## 模块 12.6.2.1 发票管理

**这个功能做什么**：管理销售发票与贷项通知单——按平台 / 国家模板生成，维护税码税率税额、编号与状态（草稿 / 开具 / 作废 / 红冲），支持批量与平台开票对接。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V366__finance_invoice_management.sql` — 六张新表（`finance_invoice_template` · `finance_invoice_sequence` · `finance_invoice` · `finance_invoice_line` · `finance_invoice_event` · `finance_invoice_platform_submission`）。纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表；3 个 BEFORE INSERT 触发器。
- 端点：18 个，挂 `/finance/invoices`；权限 `finance.invoice.read` / `.create` / `.update`。
- 页面：`/finance/invoices` —— 发票列表（真分页 + 真筛选）+ 建单 + 编号规则 + 模板 + 平台开票递交与回执；三态齐全。
- 测试：后端 **39 例**（32 + 7）+ 前端 **25 例**（20 + 5）；api allowlist 回归 234 文件 / 1320 例；finance 前端组件回归 24 文件 / 322 例。契约新增 33 个 `FinanceInvoice*` 类型（+33/-0）。

**关键设计决策**：

1. **税率与税额不是建单入参** —— 建单只收 SKU / 数量 / 单价；行金额 `numeric(19,4)` 与税额 `numeric(19,4)` 是 DB **生成列**，单头合计由触发器从明细求和派生。**调用方填不出一个假的税额** —— 这是本模块最想要的那条性质。行级允许覆盖税种 / 税码，但照样要过实时校验。
2. **税率真值源归 12.6.1.1（V361），本模块不复制表、不建 FK** —— 每行落库前经 `TaxRateService.resolve` 实时校验，命中不到一律报错（**不退回最新一条、不按 0% 处理**）。命中的税率版本 id 与税率快照一起落库 ⇒ 发票自带当时口径，之后政策改税率不影响这张发票。
3. **红冲金额从原发票逐行带出** —— 退货开票调整（归 11.4 售后）由 DB 层镜像守卫逐项比对，手填一个「差不多」的数**写不进去**。
4. **写入所有权不越界** —— 本模块**不写凭证**（归 12.5.2.1）；对订单侧**只读**、不回写 `invoice_state`（写入所有权在 trade）；平台开票的**实际递交**归 channel 16.3.2.x，本模块只负责报文生成、批量编排与回执登记，递交失败**如实留痕并写上游原话**，不假装提交成功。
5. **单币种，不出现跨币种合计** —— 币种钉在单据上，汇总按币种分组；请求折算时经 catalog 汇率快照取汇率，**汇率不可得就如实报「汇率不可得」，绝不按 1:1 兜底**。
6. **时间字段**：`timestamptz` 一律 `OffsetDateTime` 绑定 + values 位置写 `?::timestamptz`（jOOQ 3.19 会把 `OffsetDateTime` 绑成 varchar，PG 直接拒）。**不用** `Timestamp.valueOf(LocalDateTime...)` —— 那会静默偏 −28800s。测试**断言写回的值**，不是只断言没抛异常。

---

## 模块 12.7.1.2 银行对账

**这个功能做什么**：把银行 / 支付平台的流水与系统里的收支记录逐笔对起来——自动匹配、列出未匹配项、允许认领到具体单据、生成余额调节表式对账报告。**差额为 0 才叫账平了。**

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V369__bank_reconciliation.sql` — **九张新表**，零 ALTER 零 DROP 既有表、零跨库外键，命名空间 `finance_bank_recon_*` 与既有 finance 表隔离：`session`（对账批次，状态机 DRAFT→IMPORTED→MATCHED→RECONCILED **只能向前**）· `statement_line`（银行流水，**外部事实 ⇒ append-only**，DB 触发器 + 收回运行角色 UPDATE/DELETE 双保险）· `system_line`（系统收支快照，读模型可重建）· `match`（一对一匹配结果）· `unmatched`（未匹配清单，分 BANK / SYSTEM 两侧）· `claim`（人工认领举证，append-only）· `rule`（匹配规则，**表里不预置任何一行**）· `report`（对账报告 + 余额调节表）· `event`（事件流）。九表 tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT；7 个 BEFORE INSERT 触发器。
- 端点：18 个，挂 `/finance/bank-reconciliation`；权限**沿用**同 L3 兄弟模块 12.7.1.1 的 `finance.receivable.read` / `.update`（12.7 资金家族共用一套词汇表，**不新造权限词表**、不改 identity 服务）：capabilities / options / sessions / {id} / {id}/import / {id}/match / {id}/statement-lines / {id}/system-lines / {id}/matches / {id}/unmatched / {id}/unmatched/{unmatchedId}/claim / {id}/unmatched/{unmatchedId}/exclude / {id}/claims / {id}/reconcile / {id}/report / {id}/events / rules / rules/{id}。
- 页面：`/finance/bank-reconciliation` —— 对账批次列表（真分页 + 真筛选）+ 建批次 + **流水导入（CSV / JSON 真解析 + 逐行告警：时区偏移 / 币种 / 重复流水号都带行号与原因，不静默丢弃）** + 自动匹配 + 匹配结果（每条说清「为什么这两行配成一对」）+ **未匹配清单（认领到具体单据 / 说明后排除）** + 余额调节表式报告（账面余额读不到时显示「—」而**不是 0**）+ 匹配规则 + 事件流。页面**不做业务算术、不自己配对**。
- 测试：后端 **31 例**（`BankReconciliationIntegrationTest` 23 + `BankReconciliationTenancyIntegrationTest` 8，实跑 Testcontainers PostgreSQL 16、连接池压到 1，三条隔离断言跑在**本模块自己的九张表**上）+ 前端 **18 例**；api allowlist 回归 236 文件 / 1330 例（元素数 225 → 226）。契约新增 **30 个 `FinanceBankRecon*` DTO** + 18 条路径（既有 schema 0 改动 / 0 删除）。

**关键设计决策**：

1. **卷 12 硬约束「不允许『金额相同即匹配』」钉在三层** —— ① 规则类型是**受控词表**（REFERENCE_EXACT / COUNTERPARTY_AMOUNT / AMOUNT_DATE_WINDOW），三种都**至少组合两个独立维度**（单号 / 对手方 / 日期窗口）+ 方向 + 金额，**没有任何一条只看金额**；② **日期窗口上限 31 天**由 DB CHECK 钉死（窗口放到几百天等于用「金额相同」冒充匹配）；③ 匹配结果的方向 / 金额一致性由 DB 触发器兜底。
2. **「未匹配项要能认领到具体单据」落成数据层一等公民** —— 认领目标是**受控词表**（收款单 / 付款单 / 结算单 / 其它）+ 单据 id + 单号；DB 触发器校验该单据**真的存在于本租户**（认领不存在的单据直接被拒），CLAIMED 态 ⇒ 认领字段齐全由 CHECK 钉死。每次认领 / 排除都**追加**一条 claim（append-only），**认领只写本模块的表、不改被认领单据的状态**（那是上游模块的写入所有权）。
3. **四类调节额只算「待处理」** —— 银行已收未入账 / 已付未入账 / 我方已收银行未收 / 已付银行未付；已认领 / 已排除是终态，不进调节表 ⇒ **差额为 0 才叫账平了**。
4. **上游不 ALTER、不造假** —— 系统侧候选集来自同服务真实上游表（`finance_receipt` / `finance_payment_order` / `finance_settlement_line`）；12.2 的受管账户引用尚未接线（`fund_account_ref` 恒 NULL）⇒ 候选集按「同币种 + 期间」取并把「自称挂在哪个账户上」当附加过滤，上游放开那条 CHECK 后候选集**自动**收窄。
5. **时间事实不可猜** —— 流水事实时刻一律 OffsetDateTime + `?::timestamptz`；导入时**没有时区偏移的时间串直接拒收**（`Timestamp.valueOf(LocalDateTime)` 在东八区静默偏 −28800s，到账时间偏 8 小时就是错账）。写时间的测试**断言写回的值逐位相等**。
6. **上游缺口如实标注（一个字不打折）** —— ① 12.2 受管资金账户引用未接线 ⇒ 候选集按「同币种 + 期间」取；② 12.3.2.1 对账未交付 ⇒ 本模块**不**做结算单侧核对，只交付「银行·支付平台流水 ⇄ 我方收支记录」这一层；③ 12.7.2.1 汇率敞口 ⇒ 一次对账只有一个币种，本模块**绝不做**跨币种折算。

---

## 模块 1.1.1.1 我的待办

**这个功能做什么**：把订单、库存、渠道、财务、客户、审批六处系统的待办投影并成一条按紧急度排序的队列，每张卡片带归属系统与下一步入口。**工作台只读**——状态变更一律回归属系统完成。

**技术落地**：

- 迁移：**无** —— 纯前端聚合层，**不新建任何表、不占用 V370**（Flyway 版本号留给真正需要它的场景）、**不建后端微服务**、**不提供任何写端点**、对后端零改动。蓝图写死「不写业务数据，只读投影」。
- 端点：**零新增** —— 读八个**既有**只读投影端点，与蓝图「覆盖：」一行的八种动作一一对应：超期未发货 → `GET trade/fulfillment/timeliness/overdue` · 待审订单 → `GET trade/order-pending-review/queue` · 库存缺口 → `GET warehouse/inventory/stockout-alerts/stockouts` · 待处理同步失败 → `GET channel/sync-failures` · 待核对账差异 → `GET finance/supplier-reconciliation` · 待回复站内信 → `GET crm/message-inbox/messages` · 待审批单据 → `GET identity/approval-todos` · 库存低于安全线 → `GET warehouse/inventory/safety-stock/alerts`。
- 页面：`/dashboard/todo/my-todos`（`apps/web/src/app/dashboard/todo/my-todos/page.tsx` + `my-todo-model.ts` + `my-todo-workspace.tsx`）—— 数据源可达性区（八个来源逐条报「已接通 / 无权限 / 读不到」+ 归属系统 + 上游端点 + 实际生效的数据范围）+ 待办概览（总数 / 未读角标 / 最久停留 / 四档严重度 / 判不了严重度条数 / 读不到来源数 / 无权限来源数）+ 按类型筛 + 筛选（严重度 / 停留时长下限 / 关键字）+ 待办队列（排序卡片：类型 / 单据引用 / 严重度 / 停留时长 / 数据范围 / **下一步入口**跳归属系统处理页并带定位参数）。加载 / 错误 / 空三态齐全，**禁 mock**；上游取数被页数上限截断时如实报「只取回前 N 条（共 M 条）」。
- 测试：`my-todo-model.test.ts` **39 例**（八类一一对应 / 「判不了」不被当成「没有」三态 / 已处置的不再是待办 / 读不到与没有是两件事 / 紧急度排序四则 / 计数与清单同源 / 筛选五则 / 归一七则 / 数据范围八则）+ `my-todo-allowlist.test.ts` **6 例**（真跑 proxy 断言 HTTP 状态码，含**反向抽取**的真实调用点逐条放行、刻意不放行的写形状仍然 404、**代理不转发伪造的 `X-Tenant-Id` 与 `X-Internal-Key`**）。另：`pnpm vitest run apps/web/src/app/api` 237 文件 / 1336 例全绿。
- 契约：未重新生成（后端零改动 ⇒ 契约不变）。

**关键设计决策**：

1. **工作台不写业务数据** —— 页面上**没有认领 / 完成 / 转交**任何写动作，每张卡片只有一条出口（跳归属系统）。不代兄弟模块实现：1.1.1.2 异常订单队列（`/dashboard/todo`）与 1.1.1.3 审批待办（`/dashboard/todo/approvals`）各有自己的页面，本车道只挂自己的同层子路由。
2. **数据范围服务端判定，前端不掺和** —— 租户来自已认证的成员关系（`auth/session` → 各服务 `AccessService.require` → 身份服务 `/internal/access`），**不信任任何客户端租户头**（代理只转发会话 cookie 换来的 `Authorization`）。前端按 `GrantPermissions.scopeMode` 逐字同式算出每个来源**实际生效**的范围并显示，但该判定**只用于「要不要去读」和「怎么措辞」，不是授权** —— 服务端会独立再判一次。
3. **三件「判不了」与「没有」严格分开** —— ① 严重度判不了（风险分 null、审批节点没配时限、上游给了没见过的词）落独立 `UNKNOWN` 档并排在 `LOW` **之前**；② 停留时长判不了是 `null` **不是 0**（0 会把「不知道停了多久」显示成「刚发生」）；③ 来源读不到逐类单列、**不计入总数**，也不冒充「0 条」；④ 另加第四件 —— **无权限与读不到分开**：无权限的来源**根本不发请求**（发了必然 403 且会把「无权限」说成「服务挂了」）。
4. **严重度按源分别推、不统一硬编码阈值** —— 各源判据不同（风险分 / 超期秒数 / 上游自带 severity / 失败原因 / 优先级 / 未结差异条数），统一阈值会重算业务口径 ⇒ 严重度**不可跨源直接比大小**，页面按档位展示。
5. **紧急度排序末两级保证顺序稳定** —— 严重度降序 → 停留时长降序 → 类型名 → 引用（顺序乱跳会让人以为待办自己在动）；停留时长判不了的排在同级**之后**（「停了多久不知道」≠「停了很久」）。
6. **已处置的不再是待办** —— 已审的单、已解决的预警、已重放的同步失败、已读的站内信、已发的站内信（OUTBOUND 不是「待回复」）、没有未结差异的对账单，一律不进队列（上游默认口径外**显式再挡一次**，免得将来上游改了默认口径而这里悄悄把已处置的报成待办）。
7. **本车道未改 `api-proxy.ts`（k = 0）** —— 发车前用脚本把基线 **225** 条 services[] 正则**逐条编译并实测命中**（与 `resolve()` 同构：先切掉 prefix 再 test，不是目测、不是 `grep -c`），实测八个形状全部已在基线放行 ⇒ 无需新增元素，元素数仍 = 225，该文件**一字未改、未重排** ⇒ 并入时在该文件上零冲突面。

---

## L2 批次闸结果

本批唯一一次全量验证在集成头上跑完（脚本 `.dev-agent/l2-run.sh`，日志 `.dev-agent/l2-run-batch-86.log`）：

| 步骤 | 指标 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | `MVN_EXIT=0`（后端 **881 例，0 failures / 0 errors**） |
| [2/6] | `pnpm typecheck` / `lint` / `test` | `TYPECHECK_EXIT=0` / `LINT_EXIT=0` / `TEST_EXIT=0`（vitest **610 文件 / 8412 例全绿**） |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | `BUILD_EXIT=0` |
| [4/6] | `sync.mjs --check` | `SYNCCHECK_EXIT=0` |
| [5/6] | merge-union / codegen 回归 | `ITEST_EXIT=0` / `CODEGEN_EXIT=0` |
| [6/6] | 契约闸（重新生成 + 逐字节比对） | `CONTRACT_GENERATE_EXIT=0` / `CONTRACT_GATE_EXIT=0` / `CONTRACT_TREE_DRIFT_EXIT=0` |

⇒ **L2 GREEN**。静态预检三项（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**；本批 3 个新迁移共 12 个 BEFORE INSERT 触发器兜底。

## 本轮派发

本轮另派发 **4 条新车道**（后台跑，落地后由下一轮 tick 回收并入）：

| 模块 ID | 名称 | 阶段 | L3 | 迁移版本号 | 分支 | 后端归属 |
| --- | --- | --- | --- | --- | --- | --- |
| 12.3.2.1 | 对账任务 | D6 | 12.3.2 | `V370` | `feat/12-3-2-1` | finance |
| 16.3.1.2 | 申报日历 | D7 | 16.3.1 | `V371` | `feat/16-3-1-2` | channel |
| 1.1.3.2 | 资金与回款风险 | D7 | 1.1.3 | `V372` | `feat/1-1-3-2` | 前端聚合（大概率无迁移） |
| 1.3.1.1 | 通知列表 | D7 | 1.3.1 | `V373` | `feat/1-3-1-1` | 前端聚合 + identity 个人状态 |

四条车道互不依赖（L3 锚点各不相同），迁移版本号互不重叠（V370–V373）。四份任务书落在
`.dev-agent/taskbooks/{12.3.2.1,16.3.1.2,1.1.3.2,1.3.1.1}.md`（该目录不进版本库，权威副本是
`lanes.json` 的车道记录 + 各车道的分支内容）。

⚠️ **派发前做了一次基线修正**：`lane.mjs plan` 对「分支已存在」的复用逻辑只复用分支、**不重新切基线**
（`lane.mjs:749-751`），导致 1.1.3.2 / 1.3.1.1 两条**重派车道**停在旧基线 `1e9ab421` 上 ——
比集成分支少 4 条已并入车道，**api-proxy 白名单少 3 条元素**（共享白名单取错一侧会静默删掉别人已交付的路由）。
两条分支当时**没有任何独有提交**（`git rev-list --count 1e9ab421..<branch>` = 0）、工作区干净
⇒ `reset --hard` 到集成分支是**无损**的，已执行。四条车道现在基线统一为 `56d7e98c`。
附带修好了一个潜在缺陷：`lane.mjs land` 用「HEAD 短 sha == baseSha」判「无新提交」（`lane.mjs:920`），
旧基线会让这条闸恒不成立 ⇒ 一条**什么都没做的车道也能 land**；统一基线后该闸恢复正常。

## 遗留待办（沿用上一轮结论，未在本轮改动）

- **Flyway 版本倒挂（P1，不影响 L2，影响部署）** —— `V366`（12.6.2.1）并入时版本号低于集成分支已应用的 `V367`。L2 用全新 Testcontainers 库、按版本号顺序应用全部迁移 ⇒ **L2 不会红**；但**存量库**上跑 `flyway migrate` 会报 `Detected resolved migration not applied to database: 366`（`outOfOrder=false`）。处置选项见 `docs/backlog/2026-10/2026-10-24-flyway-out-of-order-version-inversion.md`，**本轮不动**（擅自改名会与下一轮 `plan` 的 `nextFree()` 撞号）。
- **`lane.mjs` 闸龄判据** —— 闸龄读的是闸目录 mtime，孤儿 keepalive 守护会让「死闸」永远不被判陈旧；待改为「最新 keepalive 文件的 mtime」，且 `gate release` 只删自己持有的闸。见 `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。
