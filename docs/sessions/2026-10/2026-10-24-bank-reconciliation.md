# 2026-10-24 12.7.1.2 银行对账（卷 12 L3 12.7 资金的第二格）

> 车道：`feat/12-7-1-2`（worktree `integration/.worktrees/12.7.1.2`），基线 `integration/module-dev` @ `1e9ab421`。
> 迁移版本号由调度者预分配为 **V369**（同批其它车道 V366 / V368 / V370）。
> L0 编译闸 + L1 模块闸全绿（见文末「验证」）。

## 这个模块做什么

蓝图 12-finance.md 12.7.1.2 原文：「导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，
支持人工认领与说明，生成对账报告。」输入：「流水文件（导入）；系统收支记录；匹配规则。」
输出：「匹配结果 + 未匹配清单 + 对账报告。」关系：上游 = 12.7.1.1、12.2；下游 = 12.3.2.1 对账。
加粗句：「**未匹配项要能认领到具体单据**，否则余额永远平不了——这是财务日常最高频的工作。」

本车道交付 **9 张新表 + 18 个端点 + 1 个真实工作区页面**（jOOQ 显式 SQL + 真实 PostgreSQL 16，
无常量、无 mock、无占位页）。

## 关键决策

### 1. 🔴 「不允许『金额相同即匹配』」钉在**三层**（卷 12 硬约束）

卷 12 对 12.3.2.1 写明：「对账不允许『金额相同即匹配』——金额相同只是候选，
必须结合订单号、日期、类型。」本模块把它落成三道闸：

| 层 | 做法 |
| --- | --- |
| 规则词表 | `finance_bank_recon_rule.rule_type` 只有 `REFERENCE_EXACT` / `COUNTERPARTY_AMOUNT` / `AMOUNT_DATE_WINDOW` 三种**受控值**；每一种都**至少组合两个独立维度**（单号 / 对手方 / 日期窗口）+ 方向 + 金额。**没有任何一条只看金额。** |
| 参数上限 | `date_window_days` 的 DB CHECK 钉死 `BETWEEN 1 AND 31` —— 窗口放到几百天等于用「金额相同」冒充匹配。 |
| 结果兜底 | `guard_finance_bank_recon_match_consistency` 触发器校验匹配的两侧**同批次 / 同方向 / 金额差 ≤ 容差**。 |

**取舍**：不引入「金额容差 + 宽窗口」这种看起来更灵活的规则 —— 它会把「金额相同即匹配」换个马甲。
容差字段保留，但**默认 0**，且**必须**同时满足其它维度。

### 2. 🔴 「未匹配项要能认领到具体单据」是**数据层的一等公民**（不是备注里的自由文本）

- `finance_bank_recon_unmatched` 上有 `claimed_document_type`（受控词表）/ `claimed_document_id` /
  `claimed_document_no` / `claimed_by` / `claimed_at` / `claim_reason`。
- `CLAIMED` 态 ⇒ 这些字段**必须齐全**（CHECK 钉死）；`EXCLUDED` 态 ⇒ 必须写排除理由。
- DB 触发器 `guard_finance_bank_recon_unmatched_claim_target` / `guard_finance_bank_recon_claim_target`
  **校验该单据真的存在于本租户**（`finance_receipt` / `finance_payment_order` / `finance_settlement`），
  服务层也校验一次 —— 认领一张不存在的单据在两层都被拒。
- 每次认领 / 排除都**追加**一条 `finance_bank_recon_claim`（append-only，触发器 + 收回运行角色权限），
  可以多次认领、留痕不改写。

**取舍：认领只写本模块的表，不改被认领单据的状态。**
改别人的状态是上游模块（12.2 / 12.3.1.1）的写入所有权；本模块只在报告里把它计为「已解决」。
这条边界让「谁改的」在跨服务链路上永远查得到。

### 3. 银行流水是**外部事实 ⇒ append-only**；系统记录快照是**读模型 ⇒ 可重建**

- 流水行导入后**不许改写**：`guard_finance_bank_recon_statement_line_append_only` 触发器 +
  运行角色 REVOKE UPDATE/DELETE（两层）。改流水等于改写外部凭据 ⇒ 正确做法是换一个对账批次重新导入。
- 匹配状态**不在流水行自己身上**，而在 `finance_bank_recon_match` / `finance_bank_recon_unmatched`
  两张派生表上 —— 这样 append-only 的外部事实与可推进的状态机不混在一张表里。
- 系统记录快照（`finance_bank_recon_system_line`）是**读模型**：上游单据是**状态机**而不是物理删除
  （作废 = 改状态）⇒ 快照**只增不删**（UPSERT，id 由 `(批次, 来源, 来源 id)` 确定性派生 ⇒ 稳定）。
  万一上游记录真的消失了，保留旧快照比悄悄删掉更安全（差额会**显式地不平**，而不是被抹平）。

### 4. 匹配是**增量**的：只补匹配，不撤销

重跑匹配**不会**撤销既有匹配、也**不会**删掉已认领 / 已排除的差异（那是人工处理过的历史）。
已匹配的银行流水行被钉住。要重来 ⇒ **新建一个对账批次**。

**取舍**：牺牲了「一键重算」的便利，换来「认领过的举证不会被一次重跑抹掉」。
财务场景里后者更重要。

### 5. 余额调节表：调节项**只算「待处理」的未匹配项**

```
调节后余额 = 银行期末余额 + 银行已收未入账 − 银行已付未入账
                    − 我方已收银行未收 + 我方已付银行未付
差额       = 调节后余额 − 我方账面余额
```

- 已认领（CLAIMED）/ 已排除（EXCLUDED）的差异是**已被人工解决**的 ⇒ **不进调节表**
  （进了就永远平不了）。
- 我方账面余额取自 **12.7.1.1 的余额历史**（`finance_fund_account_balance`）。
  **读不到 ⇒ 调节后余额与差额恒为 `null`**，报告带 `bookBalanceAvailable=false` ——
  写 0 会被读成「账是平的」，那是骗人。
- 一次对账**只有一个币种**（继承 12.7.1.1 的账户，单账户单币种是硬约束）⇒ 全部金额可相加，
  本模块**不做任何跨币种折算**（折算归 12.7.2.1）。

### 6. 系统侧候选集：按「同币种 + 期间」取，**不 ALTER 上游的表**

上游现状如实标注：

- 12.2.1.1 收款单的 `fund_account_ref` 仍由 V341 的 CHECK 钉死**恒为 NULL**；
- 12.2.2.1 付款单的 `payment_account_id` 是**无 FK 的自由 uuid**。

⇒ 候选集按**「同币种 + 期间」**取，并把「这条记录自称挂在哪个账户上」作为**附加过滤**
（声明了别的账户 ⇒ 排除；没声明 ⇒ 仍是候选）。真正的「哪一笔对应哪一笔」由**规则**决定，
不由本模块假装决定。上游把受管账户引用接线之后，候选集**自动**收窄 —— 本模块不需要改。

### 7. 导入：CSV 必须带表头；每一行不合法都**看得见**

- **CSV 必须有表头**：没有表头就只能靠列序猜，猜错一次就是**整批流水挂到错误的字段上**
  （金额列变日期列）而且**不会报错**。有表头 ⇒ 列名对不上直接拒收（400，并列出必需的列）。
- **JSON 的键也要规范化**（`occurredAt` → `occurredat`）：否则 camelCase 的账单文件会因
  「列名找不到」而**整行被跳过**（这是实现期真实踩到的坑，已写进测试）。
- 逐行解析：不合法的行进 `warnings`（**带行号与原因**），页面**必须**显示出来 ——
  只报「导入 N 行」会让人以为没丢东西。
- **时间必须带时区偏移**（`2026-10-01T08:30:00+08:00` 或 `...Z`）：
  `Timestamp.valueOf(LocalDateTime)` 在东八区**静默偏 −28800s**，到账时间偏 8 小时就是错账。
  没有偏移的时间串**直接拒收**，并写进告警。
- 幂等键 = 批次 + 银行流水号 ⇒ 重复导入不重复入账（幂等命中）。
- 金额**恒正 + 方向单独一列** ⇒ 不出现「负数代表支出」的二义性。

### 8. 规则表**不预置任何一行**；租户第一次建批次时写入内置默认规则

内置三条（单号精确 / 对手方+金额 / 金额+日期窗口），租户之后可改优先级、关掉、加自己的。
内置规则**可以关、不能删**（删掉会让「默认怎么配」这件事消失）。
规则**编码不可改** —— 编码是历史匹配的追溯依据（每条匹配记录都记着它用的是哪条规则）。

### 9. 权限码**沿用**同 L3 兄弟模块，不新造词表

12.7.1.1 用的是 `finance.receivable.read` / `.update`，本模块**沿用同一套**：
12.7 资金家族共用同一套词汇表。新造一档要改 identity 的迁移（那是它的写入所有权，
且迁移版本号由调度者统一分配）⇒ 刻意不碰。

### 10. api-proxy：新增**一条独立**元素，首 token `bank-reconciliation`

- 锚点：集成分支当前第 1505 行那条「1.1.2.3 履约健康（trade 服务…）」元素的 `{` **之前**。
- 既有 225 条元素**一字未改、也不重排**；元素数 225 → **226**。
- **刻意不并进 `fund-analysis`**：那是一条**封闭 alternation**，共用首段会让两条正则纠缠。
- id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`；
  字面量子路径（import / match / …）排在 `{uuid}` 段**之前**判别。
- ⚠️ 实现期真实踩到的坑：JS 正则**字面量**里每个 `/` 都必须转义（`\/`），
  否则字面量被提前截断 ⇒ TS1005 语法错误（`pnpm typecheck` 立刻炸，不是静默 404）。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/finance/src/main/resources/db/migration/V369__bank_reconciliation.sql` |
| 后端 | `backend/services/finance/src/main/java/com/starcon/finance/BankReconciliationModels.java` |
| 后端 | `backend/services/finance/src/main/java/com/starcon/finance/BankReconciliationService.java` |
| 后端 | `backend/services/finance/src/main/java/com/starcon/finance/BankReconciliationController.java` |
| 测试 | `backend/services/finance/src/test/java/com/starcon/finance/BankReconciliationIntegrationTest.java` |
| 测试 | `backend/services/finance/src/test/java/com/starcon/finance/BankReconciliationTenancyIntegrationTest.java` |
| 前端模型 | `apps/web/src/components/finance/bank-reconciliation-model.ts` |
| 前端工作区 | `apps/web/src/components/finance/bank-reconciliation-workspace.tsx` |
| 前端测试 | `apps/web/src/components/finance/bank-reconciliation-model.test.ts` |
| 页面 | `apps/web/src/app/finance/bank-reconciliation/page.tsx` |
| 白名单 | `apps/web/src/lib/api-proxy.ts`（**新增一条独立元素**，既有 225 条一字未改） |
| 契约（生成物） | `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json` |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（**只改 12.7.1.2 那一行**的两格） |

## 验证（真实输出）

- **L0 编译闸**：`./mvnw -q compile`（backend）⇒ BUILD SUCCESS。
- **L1 模块闸**：`./mvnw -pl services/finance -am test -Dtest='BankReconciliation*'` ⇒
  **Tests run: 31, Failures: 0, Errors: 0**（BankReconciliationIntegrationTest 23 +
  BankReconciliationTenancyIntegrationTest 8），实跑 Testcontainers PostgreSQL 16、连接池压到 1。
- **租户隔离三断言**（跑在本模块自己的九张表上）：
  ① 两租户互不可见（含按主键直查 B 的行也被挡住）；② 伪造租户上下文无效 ——
  **并断言行确实落在会话租户名下**（不是只断言「看不见」），伪造 `X-Tenant-Id` 头改不动写入归属；
  ③ 连接池归还后不串租户（池大小 = 1，5 轮交替读 + 事务外读 0 行）。
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ⇒
  新增 **30 个 `FinanceBankRecon*` DTO** + **18 条 path**；快照 diff 语义上**纯增量**
  （既有 schema 0 改动、0 删除）。
- **前端**：`pnpm typecheck` ⇒ 3/3 通过；`pnpm lint` ⇒ 通过（仅他车道文件的既存 warning）；
  `pnpm vitest run apps/web/src/app/api` ⇒ **236 文件 / 1330 例**全绿（api-proxy 全量 allowlist 回归）；
  `pnpm vitest run apps/web/src/components/finance` ⇒ **27 文件 / 356 例**全绿
  （含本模块模型 18 例）。

## 后续待办 / 留给别人的动作

1. **12.2 的受管资金账户引用接线**（归 12.2 自己的车道）：
   `finance_receipt.fund_account_ref` 的 CHECK 仍钉死恒为 NULL，`finance_payment_order.payment_account_id`
   仍无 FK ⇒ 本模块候选集按「同币种 + 期间」取。上游放开后候选集**自动**收窄，
   本模块**不需要改**（也不该由本车道 ALTER 上游的表）。
2. **12.3.2.1 对账**（下游未交付）：拿本模块的未匹配清单与对账报告做结算单侧的核对；
   本模块**不代**它产出结算单侧的差异。
3. **定时跑批**：本模块的匹配是**手动触发**的端点动作；批处理调度归下游模块。
4. **导航入口**：本页**刻意没有**在 `packages/ui/src/lib/task-pages.ts` 登记 ——
   那个文件是多车道并发修改的共享文件，留给调度者统一处理。
