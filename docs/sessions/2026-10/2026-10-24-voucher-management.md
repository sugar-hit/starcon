# 12.5.2.1 凭证管理（finance / 阶段 D6）

> 会话日期：2026-10-24 · 车道：`feat/12-5-2-1` · 蓝网点：`docs/product/capability-blueprint/12-finance.md` §12.5.2.1

## 决策摘要

实现 12.5.2.1「凭证管理」：由业务事件自动生成或手工录入会计凭证，支持过账、校验与红冲，
导出标准凭证文件，留审计记录。四条硬约束全部**落成结构而不是文案**：

1. **过账后不可改行，只能红冲** —— 应用层与数据库层**各判一次**：
   - 分录行的 INSERT / UPDATE / DELETE 由 `guard_finance_voucher_line_frozen` 挡住，
     且**旧凭证头与新凭证头都查** ⇒ 「加行 / 改行 / 删行 / 把行挪到别的凭证」四条路全堵死；
   - 凭证头的状态机 `guard_finance_voucher_state` **没有「反过账」这条边**
     （DRAFT→POSTED→REVERSED、DRAFT→VOID），已过账的事��**逐字冻结**
     （只放行 status / reversal_voucher_id / reversed_at / version / updated_at）；
   - 凭证头不可删除（`guard_finance_voucher_no_delete`）⇒ **整单删除**这条路也断掉。
2. **过账事务锁凭证头** —— `select ... for update` 在同一个事务里锁住再校验再落 POSTED，
   并发过账同一张凭证时第二个请求看到的是**过账后**的事实。
3. **校验四条各判两次**（服务层 + 数据层）：至少两行 / 金额有效 / 期间开放 / 借贷平衡。
   「期间开放」在数据层由 `guard_finance_voucher_post_period` 落成：过账那一刻查期间，
   期间不是 OPEN ⇒ **写不进 POSTED**（直连 SQL 也不行）。
4. **乐观并发** —— 入参带 `expected_version`，版本不匹配 ⇒ **409 冲突**，不静默覆盖。

## 关键 trade-off

### ① 科目用软引用（12.5.1.1 未交付）

12.5.1.1「会计科目」**同批并行**，交付时 `finance_account_subject` 表在集成头里**还不存在**。

- **不建**科目表、**不建**指向它的外键 —— 建 FK 会让 Flyway 直接失败；两边同时建同名表也会在并入时撞车。
- 分录行存 `subject_code`（科目编码，文本）+ `subject_name`（**名称快照**）。
- **诚实的后果**：本模块**不声称**校验了科目存在性 —— 写进去的科目编码当前**不会**被核对。
  这一点写进了能力声明（`12.5.1.1` 那一行 `available=false`）与前端页面的一行提示。
- **接法**（写在 `V357__finance_voucher.sql` 第六节「交接面」）：12.5.1.1 交付后二选一 ——
  (a) 加 `subject_id uuid` + 复合外键 `FOREIGN KEY(tenant_id, subject_id) REFERENCES finance_account_subject(tenant_id, id)`，
      并把 `subject_code` 降级为快照；或 (b) 保持软引用，由 12.5.1.1 的科目表自己声明编码唯一，
      本模块在服务层补一条「科目必须存在」的业务校验。

### ② 会计期间表是「过渡期 bootstrap」

蓝图要求过账前校验「**期间开放**」，而 12.5.1.2「会计期间」也**未交付**。
没有期间真相，「期间开放」就是一句假校验 ⇒ 落一份**最小**期间登记 `finance_voucher_period`
（开放 / 关闭 + 本位币 + 关闭留痕）。

- **没有** fiscal 周期、**没有**关账检查清单、**不能重开** ——
  重开已关期间是高风险操作且必须审批留痕，而审批属 17 审批（identity），
  本模块**刻意不提供**本地审批端点、也不造第二套审批引擎 ⇒ **fail-closed**。
- **最终归属是 12.5.1.2**（它的蓝图输出写死「期间状态；关账记录」）⇒ 它交付后**接管本表**
  （照 V350 接管 V346 守卫的同一手法），本模块结构不用改。

### ③ 业务事件映射表由租户维护

用户的会计科目体系是**用户自定的**（12.5.1.1 蓝图为原话），系统只提供模板、不替用户定。
`finance_voucher_mapping` 承载「业务事件 → 借贷科目」，**系统不预置任何一行、不含任何硬编码科目常量**。
没有登记映射 ⇒ 自动生成**如实报错**（不猜科目）。

映射规则的**最终归属也是 12.5.1.1**（它的蓝图输出写死「科目表 + **映射规则**」）⇒
本表在它交付前是**过渡登记处**，交接做法同样写在 V357 第六节。

### ④ 凭证不可删，只有作废 / 红冲

凭证是会计凭据，错了只能**作废**（草稿）或**红冲**（已过账）。
与 finance 其它模块「API 出口刻意不导出 DELETE」的约定一致 ⇒ 本模块**不提供任何删除端点**，
数据层也用触发器 + 收回 DELETE 权限双重挡着。

### ⑤ 业务事件的取数路径

四类业务事件（发货 / 收货 / 收付款 / 费用计提）各有一条**真实**取数路径：

| 事件 | 来源 | 方式 |
| --- | --- | --- |
| 发货 | trade 9.4.1.2 发货记录 + 9.1.2.1 平台订单 | `TradeOrderShipmentPort`（**授权 HTTP API**） |
| 收货 | trade 6.3.2.2 到货跟踪 | `PurchaseArrivalPort`（**授权 HTTP API**） |
| 收付款（付款） | `finance_payment_execution`（SUCCEEDED） | 同服务同库，jOOQ 显式 SQL |
| 收付款（收款） | `finance_settlement`（SETTLED） | 同服务同库，jOOQ 显式 SQL |
| 费用计提 | `finance_cost_collection_entry` | 同服务同库，jOOQ 显式 SQL |

trade 侧**不跨库 SQL / 跨库 FK / 跨库视图**，转发调用方自己的 bearer，租户仍由 trade 侧按
已认证成员关系解析（与既有 `PurchaseArrivalPort` / `TradeOrderShipmentPort` 逐字同构）。

## 文件清单

**迁移**

- `backend/services/finance/src/main/resources/db/migration/V357__finance_voucher.sql` ——
  五张新表 + 五道触发器守卫 + 索引 + RLS（ENABLE **与 FORCE**）+ GRANT。

**后端**（`backend/services/finance/src/main/java/com/starcon/finance/`）

- `VoucherModels.java` —— 传输模型（枚举 / 入参 / 出参 / 能力声明），命名 `FinanceVoucher*`。
- `VoucherService.java` —— 业务逻辑（列表 / 详情 / 录入 / 改草稿 / 过账 / 红冲 / 作废 /
  自动生成 / 导出 / 期间 / 映射）。
- `VoucherController.java` —— 端点（17 个，挂 `/finance/vouchers`）。
- `VoucherIntegrationTest.java`（34 例）、`VoucherTenancyIntegrationTest.java`（5 例）。

**前端**

- `apps/web/src/components/finance/voucher-model.ts` —— 前端模型（端点 + 可读形态）。
- `apps/web/src/components/finance/voucher-model.test.ts`（14 例）。
- `apps/web/src/components/finance/voucher-workspace.tsx` —— **真实工作区**。
- `apps/web/src/app/finance/vouchers/page.tsx` —— 页面路由 `/finance/vouchers`。
- `apps/web/src/app/api/finance/vouchers-allowlist.test.ts`（6 例）—— 白名单**双向**断言。
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（首 token `vouchers`），
  插入锚点固定在 `default-context`（identity）那条之后、`receivables` 那条之前；
  **既有条目一字未改、也不重排**。

**契约**（codegen 产物，**未手改**）

- `packages/api-contracts/src/contracts/finance.ts` —— 新增 **23 个 `FinanceVoucher*` 类型**（+23/-0）。
- `packages/api-contracts/src/finance.openapi.json` —— 重生成。

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md` —— **只改 12.5.2.1 那一行**的状态与证据。
- 本文件。

## 关键 trade-off（续）：踩过的三个坑，记下来省下后来者一轮

1. **`select *` 少连一列 ⇒ 400「字段格式不正确」**：`voucherRow` 要读 `period_code`，
   而 `requireVoucher` 原本用 `select * from finance_voucher` ⇒ jOOQ 读一个 ResultSet 里
   不存在的字段名会抛 `IllegalArgumentException` ⇒ 被统一错误处理器翻译成 400。
   **症状与真因完全不像**（见 `docs/backlog/2026-10-21-global-error-handler-masks-code-defects-as-409-400.md`）。
   修法：查询里**显式连出** `p.period_code`，并在代码里写明原因。
2. **PL/pgSQL 变量名与列名同名 ⇒ 「column reference period_code is ambiguous」**：
   触发器里 `SELECT status, period_code, base_currency INTO period_status, period_code, ...`
   会被判歧义 ⇒ 变量统一加 `v_` 前缀。
3. **`@Schema(nullable = true)` 打在记录类型的字段上 ⇒ 整个 schema 变成可空**：
   `FinanceVoucherResult.reversalVoucher` 引用 `FinanceVoucherRow`，
   于是契约里 `FinanceVoucherRow` 变成 `... | null` ⇒ 前端到处 `possibly null`。
   这是**本仓既有的模式**（`FinanceCostCollectionBasis` 等同样如此）⇒ 前端按「先滤 null」处理。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | **EXIT=0** |
| L0 类型 | `pnpm typecheck` | **3/3 successful** |
| L1 模块 | `cd backend && ./mvnw -pl services/finance -am test -Dtest='Voucher*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 39, Failures: 0, Errors: 0** · BUILD SUCCESS |
| 前端（本车道） | `pnpm vitest run apps/web/src/components/finance/voucher-model.test.ts apps/web/src/app/api/finance/vouchers-allowlist.test.ts` | **2 文件 / 20 例全绿** |
| 前端（**全量 allowlist 回归**） | `pnpm vitest run apps/web/src/app/api` | **226 文件 / 1280 例全绿**（本车道改了共享白名单 `api-proxy.ts`，必须跑全量） |
| Lint | `pnpm lint` | **3/3 successful** |

契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ——
`Generated 6521 DTOs in 7 per-service files`；**只有 finance 的两份产物变化，其余六服务零变化**。

## 后续待办

- **12.5.1.1 交付后**（同批并行）：按 V357 第六节接科目 FK 或补业务校验；
  并接管 `finance_voucher_mapping`（映射规则的最终归属）。
- **12.5.1.2 交付后**：接管 `finance_voucher_period`（fiscal 周期 / 关账检查 / 重开审批留痕）。
- **12.5.2.2 财务报表交付后**：直接消费已过账凭证 + 分录行 + 期间 + 科目编码，无需本模块改动。
- **导航登记**：`packages/ui/src/lib/task-pages.ts` 是四条车道并发修改的共享文件，
  本车道**刻意未动**，留给调度者统一处理。
