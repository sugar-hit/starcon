# 11.3.1.1 退款申请（接手车道）

- 日期：2026-10-18
- 分支：`feat/11-3-1-1`（worktree `.worktrees/11.3.1.1`，基线 `55bf6888`）
- 卷 11 售后与退货 · L3 锚点 11.3.1 · 阶段 D4

## 接手时核实到的实际状态

上一个 worker 在 18:45 被 Token Plan 配额打断（`errorCode 42212`），**一个 commit 都没提交**，
但 14 个文件完整留在 worktree 里。**它留给调度器的自述是「正在写多租户三断言测试」——
这条自述不准确**。逐个读完后实际状态如下：

| 文件 | 自述 | 实际读到的状态 |
| --- | --- | --- |
| `V166__trade_refund_application.sql` | 完成 | **完整**（784 行，三表 + 触发器 + RLS 循环，第五节写明「不 ALTER 任何既有表」） |
| `RefundApplicationModels.java` | 完成 | **完整**（540 行） |
| `RefundApplicationService.java` | 完成 | **完整**（1270 行） |
| `RefundApplicationController.java` | 完成 | **完整**（244 行，11 个端点） |
| `RefundApplicationIntegrationTest.java` | 17/17 绿 | **完整**（904 行，17 例） |
| `RefundApplicationTenancyIntegrationTest.java` | **「正在写」** | **也是完整的**（454 行）——三断言 + 第四条 FORCE RLS 检查一条不缺，括号闭合、编译通过 |
| 前端 4 件（model / workspace / 两个测试）+ page.tsx | 完成 | **完整**（317 + 643 + 222 + 179 行） |
| `api-proxy.ts` | 完成 | **正确的独立 `services[]` 元素**，没往 trade alternation 里并分支 |
| 契约 | 完成 | **正确的 codegen 产物**（见下） |

**结论：代码基本是完整的，交接点判断偏悲观。** 所以这次接手不是补写半成品，
而是**逐项验证 + 补真实缺口**——「看起来写完了就跳过验证」正是最容易把假绿并进集成的姿势。

## 依赖自检（开工前 10 分钟）

在 `55bf6888` 上逐个 grep 确认，全部已交付，**不构成 BLOCKED**：

- 上游 11.1.1.2 售后单详情 → `V156` / `V157`
- 上游 11.2.1.1 退货授权单 → `V161`
- 同 L3 兄弟 11.3.1.2 退款状态 → `V159`
- 下游 12.2.2.2 付款执行（卷 12 财务）→ **未交付**，`backend/services/` 下无 finance 服务。
  处理方式如实报出：`capabilities.handoffToFinance.available=false` + `ownerModule=12.2.2.2`，
  **不假装已对接、不造恒空的影子表**；dispatch 必须挂一条已存在的 `refund_execution`（V159），本模块只引用不创建。

## 我这次补了什么

### 唯一一处真实缺口：`effective_from` 的时区偏移写回断言

要求 6 写明「用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s」且
「写时间的测试要**断言写回的值**」。核实后发现既有断言**恰好绕开了这个 bug 能咬人的形状**：

- 申请单上的 `submitted_at` / `approved_at` 全是服务端 `now()`（UTC），
  偏 8 小时一样自洽，「断言接近 now()」根本抓不到；
- `T1 = 2026-03-01T08:00:00+08:00` 在测试里**到处在写，却一次都没被断言回读**；
- 而 `refund_approval_policy.effective_from` 是本模块三张表里
  **唯一一个由调用方显式传入的 `OffsetDateTime`**，且它直接决定审批规则从哪一刻生效 ——
  偏 8 小时意味着规则提前或推迟一整天生效。

补 `显式时区偏移的时间戳写回后不偏移`：写 `+08:00` 进去，**直接查库比对瞬时值**，
钉死 `2026-03-01T00:00Z`。

**为什么必须查库而不是只断言服务层读回**：一次「写歪 + 读歪正好抵消」也能让只断言读回的测试变绿。
断言钉的是绝对瞬时值，与 JVM 时区无关（`Timestamp.valueOf` 的偏移量恰好取决于 JVM 默认时区，
只断言服务层往返等于把判据交给了环境）。

服务层代码本身是对的（`OffsetDateTime` 绑定 + values 位写 `?::timestamptz`，
全文无 `Timestamp.valueOf`，只在注释里作为反例出现），**这次补的是测试的判据，不是修 bug**。

### 其余核实结论（未改动）

- **契约**：比对 base 与当前 openapi 语义差异 —— 新增 **11 条路径 / 23 个 schema，
  删除 0 条，共用 schema 内容变更 0 个**。`trade.ts` 文本 diff 里出现的 `AftersalesIntake*` /
  `Exchange*` 是**位置移动**不是新增（base 里本就有 18 个同名类型、14 条路径）。
  33k 行的文本 diff 是插入导致的行号位移churn，**是真实 codegen 产物，非手改**。
- **迁移**：`grep` 无 `ALTER TABLE`、无 `REFERENCES`；三表 `ENABLE` + **`FORCE`** RLS；
  留痕表 `REVOKE UPDATE,DELETE`；金额一律 `numeric(19,4)`，全文无 `float`/`double`。
- **禁止项扫描**：后端无 `TODO` / `UnsupportedOperationException` / 占位页（两处「占位符」
  命中都是 SQL 绑定占位符的注释）；前端 4 件无任何命中。
- **api-proxy**：确认是**新增独立 `services[]` 元素**，且 `refund-applications/` 与相邻的
  `refunds/`、`rma-authorizations/` 前缀互不相交（`refunds` 与 `refund-applications` 只差一个连字符，
  allowlist 里有专门一条断言盯这个连字符）。

## 关键 trade-off

1. **可退余额的口径写两遍**（服务层 `balanceOf` + DB 触发器 `guard_refund_application`），
   刻意冗余：蓝图那句「必须在服务端做」的真正风险是**各处现算**——改了一处忘了另一处，
   两边分叉，而每处单独看都是对的，且**没有任何测试**会发现。写两遍 + 注释里把两个算法逐条对照，
   是用冗余换「口径改错一定会被看见」。四个占用桶里**没有 FAILED** 同样是这个思路：
   V159 的 CHECK 已钉死「FAILED ⇒ 钱确定没出去」，确定性失败不占额度是**语义正确**而非宽松。
2. **命名空间独立于 `/trade/refunds`**，且**刻意不给 DELETE / verify / pay**：
   放行一个后端不存在的映射比 404 更糟——界面会以为功能已交付，点了必然失败。
3. **建单与提交分开**，金额在**建单那一刻和提交那一刻各校验一次**：
   页面打开到提交之间，额度可能已被别的申请占走。
4. **可退余额允许为负、不截断成 0**：「超了 50 块」与「正好退光」补救动作完全不同，
   截断会把两者变成同一个「—」。

## 验证与真实输出

全部在 `.worktrees/11.3.1.1` 下执行。

**L0 后端编译**
```
cd backend && ./mvnw -q -o compile     →  exit 0
```

**L0 前端类型检查**
```
pnpm typecheck     →  Tasks: 3 successful, 3 total   (exit 0)
```

**L1 后端**（`./mvnw -pl services/trade -am test -Dtest='RefundApplication*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`）
```
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.RefundApplicationIntegrationTest
[INFO] Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.trade.RefundApplicationTenancyIntegrationTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
迁移实跑：Flyway `now at version v166`，Testcontainers 真实 PG16，
连接池 `maximum-pool-size=1`。

**前端本模块测试**
```
pnpm vitest run apps/web/src/components/aftersales/refund-application-model.test.ts \
                  apps/web/src/app/api/trade/refund-application-allowlist.test.ts
  → Test Files 2 passed (2) | Tests 16 passed (16)
```

**api-proxy 全量 allowlist 双向回归**（动了 `api-proxy.ts` 必跑：
取错一侧会静默删掉别人已交付的路由，表现为页面 404，而 typecheck / lint / vitest 全绿）
```
pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/aftersales/refund-application-model.test.ts
  → Test Files 37 passed (37) | Tests 206 passed (206)
```

## 后续待办

- 并入由调度器执行（`lane.mjs land --lane 11.3.1.1` 已标记就绪，**未 push**）。
- 契约文件在并入时需与其他车道求并——本车道对 `packages/api-contracts` 是**纯增量**
  （0 删除 / 0 共用 schema 变更），冲突面应最小，但 `openapi.json` 的插入位移仍可能产生
  大块 diff，属预期。
- 12 财务整卷交付后，`handoffToFinance.available` 与 dispatch 路径需要重新评估。
