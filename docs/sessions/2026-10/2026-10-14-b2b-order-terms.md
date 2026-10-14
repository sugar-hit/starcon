# 9.7.1.1 批发订单与账期（阶段 D4）

- **日期**：2026-10-14
- **车道**：`feat/9-7-1-1`（worktree `.worktrees/9.7.1.1`）
- **L3 锚点**：9.7.1，主责服务 `trade`，迁移版本 `V120`

## 决策摘要

### 1. B2B 建自己的表族，不给 `sales_order` 加字段

9.1.2.2 确立的纪律是「来源是字段不是表」（`sales_order.order_source`），本模块**不推翻**它，
但那条纪律成立的前提是「一张事实表的写入所有者只有一个」。

本批 4 条车道同时在 trade 落迁移，`sales_order` 是 9.1.2.2 的事实表且它的交付说明把
「只新建表、不 ALTER 任何既有表，跨车道 DDL 回归面 = 0」当成验收项。给别人的表加
`order_type` / `cost_basis` / 账期字段，会同时：让另一条车道正在跑的测试在迁移顺序变化时炸掉、
迫使本车道扩写 V99 的延迟约束触发器（那是 9.1.2.2 的 32 条用例的断言对象）、并打破
「一张事实表一个写入所有者」这条 AGENTS.md 纪律。

更根本的理由：**账期到期日、发货确认应收、开票需求这三件事在零售侧根本不存在**。
塞进 `sales_order` 只会产出一半行永远是 NULL 的巨大可空字段表 —— 那正是 catalog V30
拒绝掉的形状（「这个客户没有账期」与「供应商才有账期」被折叠成同一个 NULL）。

结论：`b2b_*` 独立表族，「与零售区分」因此是**结构性的**，不靠约定。

### 2. 「未发货不产生应收」做成四重表达

蓝图最强调的一条。单一实现不够，所以叠了四层：

| 层   | 机制                                                                 | 防的是                                                           |
| ---- | -------------------------------------------------------------------- | ---------------------------------------------------------------- |
| 结构 | `b2b_receivable.shipment_id` NOT NULL 外键                           | 应收挂不到发货单就写不出来 —— 不是没写的代码路径，是走不通的路径 |
| 数值 | `receivable_amount ≤ total_amount` CHECK + 触发器按 `SUM(应收)` 复算 | 头尾两个数各算各的                                               |
| 算术 | 服务层只在 `ship()` 一个方法里产生应收                               | 以后有人「顺手」在下单里加一行                                   |
| 口径 | 账期起算点 = 发货确认时刻，到期日由触发器 `make_interval` 独立复算   | −28800s 那类静默时间偏移                                         |

收款方式三分支各写明理由：`ORDER_SETTLEMENT` 不产生应收（款项已收讫，收进来的是**预收**不是应收，
返回值里明确说明原因而不是给个 null 让人猜）、`ON_RECEIPT` 到期日 = 发货确认时刻、
`CREDIT_TERM` 到期日 = 发货确认 + 账期天数。

### 3. 阶梯区间用 `(上一档下界, 本档下界]`，「恰好等于阈值」不写分支

一条 `min_quantity ≤ quantity ORDER BY min_quantity DESC LIMIT 1` 表达全部语义。
最容易写错的地方就是把它写成 `<`，然后在边界上整体算错一档 —— 集成测试专门覆盖
10 / 99 / 100 / 100.000001 / 1000 五个点。

两档可能同价，所以**只存单价不够**：订单行另存 `applied_tier_min_quantity` 与
`applied_tier_id`，事后才能回答「这行当时按哪一档算的钱」。

### 4. 单价不接受调用方传值

`B2BOrderLineInput` 里**没有** `unitPrice` 字段。允许客户端传单价等于允许绕过起订量与阶梯价
两条硬规则 —— 那正是本模块要防的事。`fail-on-unknown-properties` 已开，传了直接 400。

### 5. 刻意不提供的三样东西

- **`COMPLETED` 状态**：让它可达成需要核销动作，而核销属于 12 财务的写入所有权。
  与其造一个走不到的终态，不如让 `SHIPPED` 就是终态（蓝图的「已完成」语义由
  `invoice_state` 承担）。
- **取消 / 删除**：单向状态机 + 事件流 / 应收明细 append-only。已确认的批发单改明细等于让
  「当时按哪一档算的钱」在历史里出现两个答案。
- **应收核销端点**：抢在 12 财务前写 `b2b_receivable.status` 会让「谁改了应收」无法回答。
  本服务只写自己拥有的那一面（产生 + 开票需求标记）。

## 关键 trade-off

| 决定                                         | 放弃了什么                                   | 为什么值得                                                             |
| -------------------------------------------- | -------------------------------------------- | ---------------------------------------------------------------------- |
| B2B 独立表族                                 | 与零售共用一张订单表带来的「下游只认一张表」 | 跨车道 DDL 回归面归零；账期语义在零售侧无对应物                        |
| 起订量放价目表**表头**（不是每档）           | 「MOQ 与某一档绑定」的直觉写法               | 同一 SKU 的三档可能写着三个不同 MOQ，而无法表达「哪个是对的」          |
| 阶梯投影**整体替换**（先删后插）             | 逐档追加的实现简单性                         | 删档是调价最常见的动作，追加语义表达不了它                             |
| 不给 `b2b_price_tier` 收回 DELETE            | 「投影不可删」的整齐                         | 整体替换走不通会变成一个完全没有信息量的 409                           |
| 阶梯 / 客户投影在本服务内落库                | 「纯消费事件」的干净                         | 05 价目未交付；不落库就没有任何可试算的数据                            |
| 应收列表独立端点                             | 与订单列表合并成一张表                       | 应收按**到期日**排（资金计划取数顺序），与订单的创建时间维度不是一回事 |
| 前端 `/orders/b2b` 而非 `/orders` 第三个 tab | 「订单中心一处看全」                         | `/orders` 是别的模块的页面；且账期应收的排序维度不同                   |

## 过程中改掉的六个真缺陷

1. **阶梯闸不等式写反**（迁移）：写成 `lowest < moq` 会同时制造两种错 —— 放过
   「MOQ 10 + 最低档 50」的空洞，又误拒「MOQ 10 + 起步档 5」这种完全正常的形态。
   正确方向是 `lowest > moq` 才拒，且必须用 **AFTER** 触发器才看得到含本行的完整阶梯
   （BEFORE 时本行尚不可见，第一档永远算成「空阶梯」而被误拒）。
2. **复合外键缺 `UNIQUE(tenant_id,id)`**（迁移）：五张被引用表全部补上。
   PostgreSQL 不会自动把 `(tenant_id,id)` 当唯一约束。
3. **延迟触发器读错行的列**（迁移）：`guard_b2b_receivable_running_total` 写在
   `b2b_receivable` 上却读 `NEW.receivable_amount` —— 那列在 `b2b_order` 上。
   延迟触发器是提交时才跑的，表现为一个完全没有线索的 409。
4. **`PARTIALLY_SHIPPED` 17 字符写不进 `varchar(16)`**（迁移）：而事件流恰恰是发货那一刻
   最该留下的凭据。加宽到 24。
5. **详情的应收查询少 SELECT 两列**（服务层）：构造器读 `order_no` / `customer_name`，
   查询里没有 → jOOQ 抛 `IllegalArgumentException` → 400「字段格式不正确」。
   **只在有发货之后才暴露**（有应收才有行要构造）。
6. **DELETE 授权与整体替换语义冲突**（迁移）：对 `b2b_price_tier` 收回 DELETE 让
   `upsertPriceBook` 的先删后插走不通。

## 文件清单

**后端（新增）**

- `backend/services/trade/src/main/resources/db/migration/V120__trade_b2b_order_terms.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/B2BOrderModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/B2BOrderService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/B2BOrderController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/B2BOrderIntegrationTest.java`

**前端（新增）**

- `apps/web/src/app/orders/b2b/page.tsx`
- `apps/web/src/components/orders/b2b-order-workspace.tsx`
- `apps/web/src/components/orders/b2b-order-model.ts` + `.test.ts`
- `apps/web/src/app/api/trade/b2b-order-allowlist.test.ts`

**共用文件（外科式最小改动）**

- `apps/web/src/lib/api-proxy.ts` — trade alternation **追加** `b2b-orders` 一支
- `packages/ui/src/lib/nav-config.ts` — 运营区追加「批发订单」一条
- `packages/api-contracts/src/contracts/trade.ts` / `trade.openapi.json` — codegen 纯新增 31 条 `B2B*`
- `docs/product/capability-blueprint/module-progress.md` — 只改 9.7.1.1 那一行

## 验证

| 闸          | 命令                                                                | 结果                          |
| ----------- | ------------------------------------------------------------------- | ----------------------------- |
| L0 后端编译 | `cd backend && ./mvnw -q compile`                                   | 通过（无输出）                |
| L0 类型     | `pnpm typecheck`                                                    | 3 个包全绿                    |
| L1 后端     | `cd backend && ./mvnw -pl services/trade -am test`                  | **227 例全绿**，BUILD SUCCESS |
| L1 前端     | `pnpm vitest run <4 个改动文件 + 3 个他车道 trade allowlist + nav>` | **58 例全绿**                 |

trade 全套含本模块 32 例 + 既有 195 例，**跨车道零回归**（手动订单 32 / 加工单 27 /
履约路由 32 / 仓配 40 / 订单列表 22 / 订单详情 16 / BOM 21 / 租户 4 / 契约导出 1）。

## 后续待办

1. **9.3.1.1 履约接入**：履约链路当前只认 `sales_order`，`b2b_order` 不在其中，
   批发订单尚未进入拣货与发货编排。交接方式已写在 `capabilities` 里 ——
   同服务同库读表，**不是跨库读取**，因此不需要跨服务授权 API。
2. **12.6.2 发票**：消费 `invoice_state = 'REQUESTED'`。
3. **12.1.1 应收**：接管核销（`SETTLED`）；本模块刻意不写这个状态。
4. **12.1.2.2 资金计划**：B2B 与零售现金流方向相反（本模块先货后款，零售先款后货），
   必须分开建模，不能共用一条应收曲线。
5. **5.1.1.2 价目项维护**（D6）：交付后由事件同步接管 `b2b_price_book` /
   `b2b_price_tier`，把本服务的投影写入端点降级为兜底。表结构不需要改。
6. **可观测性缺口**：本模块未接 OpenTelemetry（其余服务已接）。
7. **待补的读写面**：批量收货 / 客户对账单导出（对账场景需要把应收按客户聚合导出，
   当前只能逐单看）。属于蓝图「开票与对账」的后半段，本轮未实现。
