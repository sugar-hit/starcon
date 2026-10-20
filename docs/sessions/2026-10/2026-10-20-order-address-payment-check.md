# 9.2.2.1 地址与支付校验（trade 服务）

> 会话：2026-10-20 · 车道 `feat/9-2-2-1`（worktree `.worktrees/9.2.2.1`）· 集成头基线 `03785467`

## 决策摘要

按蓝图 09 卷 9.2.2.1 交付「地址与支付校验」，归属 **trade** 服务，迁移
`V210__trade_order_address_payment_check.sql`（预分配版本号，**零 ALTER 既有表**）。

蓝图职责句的三样输入，本服务**真接通两样半**：

| 蓝图输入 | 落地 | 来源 |
| --- | --- | --- |
| 订单地址快照 | 真 SQL | V99 `order_address_snapshot`（country_code / postcode / address_line1 / recipient_*） |
| 支付方式 | 真 SQL | V99 `sales_order.payment_method`（六值词表） |
| 支付**状态** | ⚠️ **本服务没有这个事实** | 12 财务未交付 ⇒ 由**调用方随请求提供**，本模块只判它与付款方式自不自洽 |
| 禁运规则配置 | 租户可维护的码表 | `order_address_check_embargo_rule`（**Java 里没有国家数组**） |

输出两件、不是一件：**校验结果**（`delivery_status` 可投递 / 不可投递 / 未定）与
**风险等级**（`risk_level` LOW / MEDIUM / HIGH / CRITICAL）。两者的判定依据刻意不同：
可投递性只看**地址类四项**（禁运国 / 邮编格式 / 街道格式 / 必填联系方式），
风险等级看全部七项。

七项恒定各占一行（照抄 V153「缺失的项也占行」）：
`COUNTRY_EMBARGO` · `POSTCODE_FORMAT` · `STREET_FORMAT` · `REQUIRED_CONTACT` ·
`PAYMENT_CONSISTENCY` · `COD_FULFILLMENT_RISK` · `COD_INSURANCE_POLICY`。

### 加粗句「货到付款订单的履约风险最高，需额外风控与保险策略」的五层落点

1. **CRITICAL 是最高档，且只能由货到付款触发。**
   表级 CHECK `CHECK ((risk_level = 'CRITICAL') = cod_flagged)` **双向**钉死：
   「是货到付款却不是最高风险」与「不是货到付款却给了最高风险」在写库这一层就被拒。
   风险等级**不是**租户可配的阈值 —— 做成可调项就等于允许把履约风险最高的一类单悄悄降级。
2. **货到付款是独立的一项**（`COD_FULFILLMENT_RISK`），不是「支付一致性」里的一个分支。
   「因为状态一致所以这项正常」的读法，会让两件处置完全不同的事（一个要加保险、
   一个要查收款）混成一件。
3. **保险策略单独成一项且恒为未交付**（`COD_INSURANCE_POLICY`）。
   蓝图中**没有任何 L4 模块持有**它（卷 10 的 10.1.1.2 只持有渠道保价规则），
   本模块**不假装**能算保费或推荐投保方案。
4. **额外风控那一半是真接通的**：9.2.2.2 风控校验（V150，同服务同库）已交付，
   capabilities 指出货到付款单的额外风控走它，本模块不重算风控分。
5. **不存在任何端点能改写风险等级** —— 由 DB 触发器从七行明细实算 +
   **表级** `REVOKE UPDATE`（不是列级，见下）。

派生的一条：**货到付款不判成「不可投递」**。COD 订单地址照样能送到，
它的问题是**收不到钱**的风险，不是投递问题 —— 所以 `delivery_status` 仍由地址类四项独立判定。

## 关键 trade-off

- **支付状态由调用方提供，而不是本服务发明一个列。**
  实查 V99 `sales_order` **没有** `payment_status`，V207 `platform_order` 也没有，
  V100 的 `SalesOrderListService` 早就把 `paymentStatus` 恒置为 null 并写明「归 12 财务」。
  本模块若发明一个列冒充上游事实，界面就会显示「未付款」—— 而真相是
  「本服务根本不知道支付进展」（V100 同一纪律）。
  ⇒ 调用方没提供 ⇒ 该项落 `NO_REFERENCE`，**不是**「一致」：
  「没量到」与「量过了、结论是正常」是两件事。
- **支付一致性规则落全 24 行（6 付款方式 × 4 观察状态），不只落矛盾组合。**
  只落矛盾组合的话，「表里没有这一行」就有两种读法（被判为自洽 / 租户根本没配），
  而这两种读法的后果完全相反。落全之后「查不到规则」只意味着**缺参照**。
- **国别格式规则是表，且基线在 SQL 里而不是 Java 里。**
  硬要求是「不许写死国家数组在 Java 里」，所以基线由迁移里的
  `order_address_check_install_baseline` 按租户**幂等落库**（`ON CONFLICT DO NOTHING`），
  落库后判定真身是表 —— 改一行规则不用发版，租户改过的行也不会被基线覆盖。
- **`observed_value` 的口径统一为「观测到的问题数」**（0 = 没问题），
  不可用时为 `null`。给 0 等于宣称「量过了，结果是没问题」。
- **校验结论是凭据不是当前状态**：同一张单跑两次各占一行，
  明细表 append-only（`REVOKE UPDATE/DELETE` + 触发器双保险），
  合计与两个输出由触发器实算（服务层不写）。

## 上游实查结论（动手前 10 分钟自检）

- 9.1.2.1 平台订单同步**已并入集成头**（V207）；地址快照在 V99 的 `order_address_snapshot`。
- 真实列名以 grep 为准：`address_role` / `recipient_name` / `recipient_phone` /
  `country_code char(2)` / `province` / `city` / `district` / `postcode` /
  `address_line1` / `address_line2`。
- 既有正则在 `apps/web/src/lib/api-proxy.ts` 里共 49 个首 token，
  全部与 `order-address-payment-check` **首 token 不同**。

## 过程中被测试与 DB 逼出来的四个真实缺陷（已修）

1. **支付规则基线的三份 CASE 各判各的。**
   `expected_consistent` 与 `expected_severity` 在「货到付款 × 货到付款」这一格不一致，
   被表级 CHECK 当场拒。修法：**自洽由严重度派生**（`severity='OK'` ⇒ 自洽），
   两份判定不可能再打架 —— 这也顺带修掉了 PL/pgSQL `unnest` 交叉连接里
   那一格漏在矛盾分支之外的问题。
2. **列级 `REVOKE` 在表级 `GRANT` 之后是空操作。**
   PG 里表级 UPDATE 蕴含每列 UPDATE，所以「GRANT … UPDATE」后紧跟
   「REVOKE UPDATE(列)」是**空操作**，运行角色其实还能改风险等级
   （只是被表级 CHECK 挡住，测试全绿）。11.1.1.1 车道踩过同一个坑并写进台账。
   修法：先收回**表级** UPDATE（本表允许列集合为空，直接收回整张表）。
3. **PostgreSQL 的 `RAISE … %s` 会把字面量 `s` 一起打出来**（`%s` 里的 `s` 不是类型说明符），
   错误消息改成裸 `%` 占位。
4. **一个 javac 级别的坑**：把 `assertThatThrownBy(...).hasMessageContaining("x")`
   批量改成 `rootMessage(...)` 时正则跨行吞掉了 SQL 字符串里的 `+ "`，
   编译期才报出来 —— 批量改测试断言要用**不跨行**的匹配。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V210__trade_order_address_payment_check.sql`（六张表 + 1 个基线安装函数 + 4 个触发器 + 7 个索引 + RLS/GRANT）
- `backend/services/trade/src/main/java/com/starcon/trade/AddressPaymentCheckModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/AddressPaymentCheckService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/AddressPaymentCheckController.java`（14 个端点）
- `backend/services/trade/src/test/java/com/starcon/trade/AddressPaymentCheckIntegrationTest.java`（33 例）
- `backend/services/trade/src/test/java/com/starcon/trade/AddressPaymentCheckTenancyIntegrationTest.java`（9 例）

**前端**

- `apps/web/src/app/orders/address-payment-check/page.tsx`
- `apps/web/src/components/orders/order-address-payment-check-model.ts`
- `apps/web/src/components/orders/order-address-payment-check-model.test.ts`（17 例）
- `apps/web/src/components/orders/order-address-payment-check-workspace.tsx`
- `apps/web/src/app/api/trade/order-address-payment-check-allowlist.test.ts`（9 例）
- `apps/web/src/lib/api-proxy.ts`（**新增独立一条 services[] 元素**，锚点 `fulfillment/consolidation` 之后）
- `packages/ui/src/lib/task-pages.ts`（登记 `/orders/address-payment-check`）

**契约与台账（多车道共用，合并驱动器按类型名 / OpenAPI 路径 / 模块 ID 求并）**

- `packages/api-contracts/src/trade.openapi.json`（+9 路径 / +20 schema，**无删除**）
- `packages/api-contracts/src/contracts/trade.ts`（+20 行）
- `docs/product/capability-blueprint/module-progress.md`（只改 9.2.2.1 一行的状态与证据两格 +
  `sync.mjs` 自动重算的 5 行总览统计）

## api-proxy token 的选择与不撞车论证

选 `order-address-payment-check`（首 token `order-address`）。
既有全部 49 个条目的首 token 互不相同，与本模块相关的六条是
`order-review`（9.2.1.2）· `order-intercept`（9.2.1.3）· `orders`（含 `orders/risk-validation` 9.2.2.2）·
`fulfillment` · `aftersales` · `platform-orders`（9.1.2.1）——
**首 token 全不相同**，而既有正则都是锚定的（`/^token(...)?$/`），
所以新前缀不会与任何一条抢路径。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` → exit 0；
  `pnpm typecheck` → 3 successful，`Cached: 0 cached, 3 total`（`TURBO_FORCE=true`，绿不是别的 worktree 的缓存）。
- **L1 模块闸**：`./mvnw -pl services/trade -am test -Dtest='AddressPaymentCheck*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **42 tests / 0 failures / 0 errors / 0 skipped**
  （AddressPaymentCheckIntegrationTest 33 + AddressPaymentCheckTenancyIntegrationTest 9）。
- **前端**：`pnpm vitest run apps/web/src/app/api`（改了共享的 `api-proxy.ts` ⇒ 跑全量 allowlist 回归）
  → **96 files / 593 tests 全绿**；本模块 model 17 + allowlist 9；
  `packages/ui/src/lib/nav-routes.test.ts` 10 例全绿。
- **lint**：`pnpm lint` → 3 successful，无新增告警（残留告警都在别的模块的既有文件里）。
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir .tmp/spec-dir`
  → 3412 DTOs / 6 文件；trade 侧 **+9 路径 +20 schema，0 删除**。
  `channel` 侧因基线快照与 `channel.ts` 本就不同步而产生差异，**已 `git checkout` 还原** ——
  那是别的车道的文件，本车道不越界改。

## 后续待办（不属于本车道）

- **9.2.1.3 异常拦截的接线**：V153 的 `ADDRESS_DELIVERABILITY` 项当前恒为 `UPSTREAM_UNAVAILABLE`，
  那是 V153 车道的文件所有权。改它去读本模块结论属于后续车道的工作。
  本模块已提供授权读路径 `GET /trade/order-address-payment-check/orders/{orderId}/latest`。
- **12 财务的支付状态**：交付后由同步器按同一形状写入并改来源枚举，表结构与判定逻辑都不用改。
- **货到付款保险策略**：蓝图中无 L4 归属。capabilities 逐项报 `available=false` +
  `ownerModule=10.1.1.2` + impact，**不假装有保险能力**。
- **16 合规的国别禁运清单**：未交付，当前清单由租户自维护
  （`restriction_source` 同款来源枚举留了 `MODULE_16_COMPLIANCE` 的位置）。
