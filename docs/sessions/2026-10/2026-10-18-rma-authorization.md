# 2026-10-18 · 11.2.1.1 退货授权单（阶段 D4，L3 锚点 11.2.1）

车道：`feat/11-2-1-1`，worktree `.worktrees/11.2.1.1`，迁移 `V161__trade_rma_authorization.sql`。

## 决策摘要

### 1. 「已退数量」这一列当前根本不存在 —— 这是本模块最关键的一个发现

蓝图职责句写「确认可退数量（**原订单行已退/已发数量约束**）」。实查集成头 `f760ba37`：

| 事实 | 落点 | 状态 |
| --- | --- | --- |
| 订购数量 | `sales_order_line.quantity`（V99，9 章） | ✅ 可读 |
| 已发数量 | `order_fulfillment_line_progress.shipped_qty`（V119，9.1.2.2） | ✅ 可读 |
| **已退数量** | **无此列** | ❌ 写入所有者是 **11.2.2.2 实收分配，未交付** |

→ **trade-off**：不建影子表（照抄 8.2.1.1 / 8.2.1.2 对「回写采购单」「假 purchase_claim 表」的同一判断）。
一张本服务自造的「已退数量」会被当成已对接，而重复记已退数量会让「这个 SKU 一共退了几件」出现两个答案
（实收按收货事件去重分配是 11.2.2.2 的硬规则）。
→ 可退量里 `receivedQty` **恒为 null 并标注归属模块**，**不填 0 冒充「没退过」**。填 0 会让运营以为这行从来没退过，
在重复退货面前失去唯一的提醒。当前按「其他在途授权累计」扣减，11.2.2.2 交付后在同一处叠加。

### 2. 「可退上限」钉三层，DB 那一层是真查不是快照

`上限 = min(订购量, 已发量) − 其他在途授权单的累计授权量`

- **表级 CHECK**：用行上的三个快照判。
- **触发器 `guard_rma_authorization_line`**：写入时刻真查 `sales_order_line` 与
  `order_fulfillment_line_progress` 再算一遍 —— 绕过服务层直连 SQL 也绕不过（测试实跑证明）。
- **服务层**：`decide` 时**重新查**，不信页面传来的数（页面打开到点「授权」之间可能过了几十分钟）。

两个方向性判断：
- **没有履约进度投影行 ⇒ 已发按 0 处理**。宁可拦住也不能放过：放行的代价是把从没到过买家手上的货按已收货处理。
- **只有 `AUTHORIZED` / `PARTIALLY_AUTHORIZED` 占用可退量**。拒绝 / 撤销 / 超时关闭的单不会有货回来
  （超时正是「没寄回」），量要还给订单行。

### 3. 一个状态列到底，`decision` 由它派生

不存第二份真相。存两份就会出现「状态是部分授权、决定字段写着全部授权」这种自相矛盾的行，
而审批单据的自相矛盾在纠纷里是要命的。

**没有 `RECEIVED` 状态** —— 登记实收属 11.2.2.1（未交付）。蓝图加粗句「授权不等于已收到」在传输层就要说清：
`received` 恒 `false` 且带 `receivedOwnerModule`，而不是给个 null 让界面自己猜。
页面上同理**不渲染**「登记已收到」控件：渲染一个注定 422 的按钮等于告诉运营系统坏了，
而真相是这条路径本就不该存在。

### 4. 超时未寄自动关闭是**显式端点**而不是后台定时器

定时器要另起 worker 进程与调度配置，那是另一个模块的事。给出真实可调用的关闭动作 + 逐条结果，
判据在这一处定死，调度侧以后定时调它即可。

判据只有一个：`return_by_at < 现在` 且状态仍在途。
**已登记回寄物流的单也照常关闭** —— 登记只说明买家报了个运单号，货到没到库是 11.2.2.1 的事。

### 5. 刻意不碰 `aftersales_authorization`（V156）

它是 11.1.1.1 批量授权的写入所有者。本模块的退货授权单是**另一份单据**（行级授权数量 + 退货地址 +
回寄物流 + 时限），塞进去会把「同意退 3 件中的 2 件」压成一个金额，谁都答不出退的是哪几件。
同理**不改写 `aftersales_case.status`**（属 11.1.1.3 未交付，且该表是 11.1.1.1 的写入所有权）。

### 6. 退货地址只能租户维护

蓝图输入句写「3.6.3 或平台 RMA 地址」。**实查：3.6.3.1 确已交付，但迁移在 catalog 服务**（V31），
是另一个数据库 ⇒ 按 AGENTS.md 模块边界纪律禁止跨服务 SQL / 视图 / FK，本服务读不到。
平台地址同理（channel 独立库，本服务无任何平台地址表）。
→ 照抄 V150 `signal_source` / V156 `owner_source` / V157 `message_source` 的既有解法：
落成租户可维护的真实列，每行强制来源枚举与 `source_reference`；正式接口到位后同步器按**同一形状**写入，
**表结构与查询都不用改**。

### 7. `target_disposition` 是意图，不是处置结论

货还在路上（授权 ≠ 收到），此刻任何「已处置」都是假的。真正的处置决定属 11.2.3.2（未交付），
且**必须等实收到货**（11.2.2.1）。所以列名带 `target_`，能力声明里显式报处置结论未交付。

## 关键 trade-off

| 选择 | 放弃的东西 | 为什么值得 |
| --- | --- | --- |
| 不建已退数量影子表 | 「可退量」少了一个口径 | 重复记已退数量会让「一共退了几件」有两个答案 |
| 没有 `RECEIVED` 状态 | 页面看不到「已收到」进度 | 编一个会让下游以为货已到手并开始入库分录 —— 退回商品**默认进待检**，当已收货处理会把残次品当新品卖出去 |
| 显式端点而非定时器 | 「自动」二字 | 定时器要另起 worker 与调度配置；判据定死在一处，调度侧以后定时调即可 |
| 已退数量显示 null 而非 0 | 表格里一列都是「不可得」 | 填 0 会让运营以为「这行从来没退过」，失去重复退货前的唯一提醒 |
| 前端不自己推断能不能批 | 少一份「本地快校验」 | 页面的数和后端的口径一漂移，运营就照着错的数做决定 |

## 过程中修掉的三个真 bug（都是先写错、被测试逼出来的）

1. **`? in (...)` 与 PostgreSQL 的 JSONB `?` 运算符冲突**：万能状态流转语句报出来的是一句与真实原因
   毫无关系的语法错误。改成**每个目标状态一条 SQL** —— 顺带 `decided_by` / `cancelled_by` 二选一的
   条件表达式也不再需要，读的人不用在脑子里跑一遍才知道落了哪几列。
2. **审批留痕的 `occurred_at` 取错列**：早期版本 `coalesce(decided_at, cancelled_at, created_at, now())`，
   于是**超时关闭事件的时刻被记成了当初审批的那一刻**。两条事件 `occurred_at` 完全相同，排序退化成按随机
   UUID 排，「建单 → 审批 → 超时关闭」这条时间线**时好时坏**（跑一次绿、跑一次红）。改成每类事件用它自己那一刻。
3. **在途累计按 `case_line_id` 排除**：一张售后单可以开多张退货授权单，它们引用**同一个** `case_line_id`，
   按 `case_line_id` 排除会把先批的那张一起排除掉，累计恒为 0 ——
   于是「上限已被别人占满」这条约束在真实使用里形同虚设。改成**不排除任何单**（草稿授权量恒 0，
   被拒 / 撤销 / 超时的单被状态过滤掉）。

另有一个**测试里的假绿**值得记：多租户测试里把 `asA(...)` 的调用嵌进了 `asB(...)` 的 lambda ——
内层事务会把 `app.current_tenant` 重新设成 A，于是外层那次「跨租户审批」实际是在 A 的租户下跑的、
必然不报错，断言变成了永远为真。已改为在 lambda 外先取出行 id。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V161__trade_rma_authorization.sql`

**后端**（trade 服务）
- `.../java/com/starcon/trade/RmaAuthorizationModels.java`（传输模型 + 枚举 + 排序白名单）
- `.../java/com/starcon/trade/RmaAuthorizationService.java`（能力声明 / 可退量 / 列表 / 详情 / 建单 / 改单 / 审批 / 回寄物流 / 撤销 / 超时关闭）
- `.../java/com/starcon/trade/RmaAuthorizationController.java`（10 个端点，挂 `/trade/rma-authorizations`）

**测试**（Testcontainers PostgreSQL 16，连接池压到 1）
- `.../test/java/com/starcon/trade/RmaAuthorizationIntegrationTest.java`（39 例）
- `.../test/java/com/starcon/trade/RmaAuthorizationTenancyIntegrationTest.java`（4 例，三断言 + 迁移落地断言）

**契约**（codegen 产物，禁止手改）
- `packages/api-contracts/src/contracts/trade.ts`（+23 个 `Rma*` 类型）
- `packages/api-contracts/src/trade.openapi.json`

**前端**
- `apps/web/src/app/aftersales/returns/page.tsx`
- `apps/web/src/components/aftersales/rma-authorization-workspace.tsx`
- `apps/web/src/components/aftersales/rma-authorization-model.ts`
- `apps/web/src/components/aftersales/rma-authorization-model.test.ts`
- `apps/web/src/app/api/trade/rma-authorization-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**只新增一条 services[] 元素**，锚在 11.3.1.2 之后、11.1.1.2 之前）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（**只改本模块那一行**的状态与证据两格）

## 验证

- L0 编译闸：`./mvnw -q compile` 绿；`pnpm typecheck` 绿（并用 `pnpm exec tsc --noEmit` 直取排除 turbo 缓存）
- L1 模块闸：`./mvnw -pl services/trade -am test -Dtest='RmaAuthorization*'` → **43 例全过**
- 前端：`pnpm vitest run apps/web/src/components/aftersales/rma-authorization-model.test.ts` → 21 例；
  因改了 `api-proxy.ts`，按要求跑了**全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api/trade`
  → 33 文件 / 182 例全过
- lint：`pnpm --filter @starcon/web lint` 无新增告警

## 后续待办

1. **11.2.1.2 退货指令下发**（下游，未开工）：消费本模块的授权单发退货指引与标签；
   本模块已经把地址与时限放在单头上，下游可直接读。
2. **11.2.2.1 / 11.2.2.2**：实收登记与已退数量更新。**11.2.2.2 交付后要在可退量里叠加实收已退量**
   —— 同一处（`RmaAuthorizationService.returnableLine` 的 inflight 计算旁），不要另起一份口径。
3. **11.2.3.2**：处置结论落地后，`target_disposition` 才从「意图」变成「已执行」，届时需要一条状态迁移
   与一次历史回填评估。
4. **9.3.3.1 履约进度若补数**：本模块对「没有投影行 ⇒ 已发按 0」的处理会随之放宽，
   届时 `NO_FULFILLMENT_PROGRESS` 这个来源值要一起评估。
5. `rma_authorization.target_disposition` 目前无索引；若 11.5.1 退货分析要按处理方式聚合再补。
