# 2026-10-15 代发差价与对账（9.6.2.1）

阶段 D4，L3 锚点 9.6.2，车道分支 `feat/9-6-2-1`。
后端主责服务 **trade**，Flyway 版本号 **V130**（预分配）。

## 决策摘要

**1. 平台费未登记 = 「无法判定」，不是「按 0 算」（本模块唯一不可让步的一条）**

蓝图把亏损判据写成「售价低于代发成本**+ 平台费**」，而输入里的 5.2.3.1 **未交付** ——
这条判据的两个加数里有一个根本不存在。按 0 算会同时犯两个方向的错：

- 把真实亏损**放过去**（售价 30 / 代发成本 12 / 真实平台费 8 的单被判成不亏）；
- 又把所有代发单**染成亏损**，把真正的亏损淹掉。

因此结论做成**三值**，并用 `GENERATED` 列钉在 DB 层（应用层算错也改不动）：

| 结论 | 判据 |
| --- | --- |
| `LOSS_CONFIRMED` | 售价 < 代发成本（**即使平台费未登记也成立**）；或平台费已登记且售价 < 代发成本 + 平台费 |
| `NOT_LOSS` | 平台费已登记，且售价 ≥ 代发成本 + 平台费 |
| `UNDETERMINED_NO_FEE` | 售价 ≥ 代发成本，但平台费**未登记** —— 无法判定 |

关键取舍：「售价 < 代发成本」这一支在平台费未登记时**也算确认亏损**。
理由是平台费恒 ≥ 0，只会让亏损更大 —— 少算它只可能**高估**毛利，不会**漏判**亏损。
所以把「无法判定」的范围收窄到「成本内但费率未知」那一格，而不是整片留白。

`UNDETERMINED_NO_FEE` **单独成桶不并进 `NOT_LOSS`**（照抄 9.6.1.3 已验证的
「未登记项落独立 UNSET 桶」）：并进去之后界面上就是一片绿，
「平台费一直没登记」这件事会彻底消失，直到某天有人真的亏了才发现。

**2. 差价毛利唯一真相是四个生成列，测试断言等式本身**

`dropship_cost / spread_before_fee / spread_after_fee / loss_verdict` 全是
`GENERATED ALWAYS`，只引用基列。`spread_after_fee` 在 `platform_fee_amount` 为 NULL 时
**整个表达式为 NULL 而不是 0** —— 界面上「差价 0」与「差价算不出来」必须长得不一样。

集成测试断言的是**等式本身**（差价 == 售价 − 采购 − 运费 − 平台费），
不是某个硬编码的期望数字：换一组金额，等式仍然必须成立。

**3. 事实可重算，但对账快照不可改写（分层）**

一开始把 `margin_fact` 做成完全不可改写，后来发现是错的：成本在订单落定前还会变，
供应商回执改了成本，代发单就永远停在旧差价上。

最终分层：`margin_fact` 是「当前最好的一次结论」，**允许重算**；
但**一旦被非草稿对账单引用就不可改写**（触发器 `guard_dropship_margin_fact_evidence`）。
冻结副本另有一道：`reconciliation_line` 是 INSERT 时写下的**快照**，
不是运行期 join 当前费率的视图。改费率不会让历史对账单看起来是按新费率算的。

**4. 多币种：不用 1.0 假装**

汇率来源同样未交付，故建模为**可登记**。未登记时该行折算列整组为 NULL、
被生成列 `is_in_totals` 排除在合计外、单独进 `undetermined_*` 桶。

头侧 `fx_rate_id/value` 只在**全程同一个汇率**时才记；多币种混用时**留空**
（每行各自留证）—— 填一个假的单一汇率会让下游折算出错的应付，比留空更坏。
为此单独加了 `fee_unregistered_order_count`，让「合计里的平台费 0.00」
不会被读成「这批单的平台费真的是 0」。

**5. SKU 维度刻意不摊供应商运费与平台费**

这两项在代发单上是**单级**金额，摊到行上必须假设一个分摊规则，
而那个假设会直接改变「哪个 SKU 在赚钱」的结论。宁可少给一个数，
也不给一个看起来精确、实际是假设的数 —— 在该行 note 里写明原因。

**6. 「已交给下游」与「下游已受理」是两个状态**

`SENT`（本服务发出去了）与 `ACKNOWLEDGED`（下游真的接手了）刻意不合并。
合并后界面会把一个没人接的待办画成「应付单已生成」。

## 跨车道 / 边界

- **零 ALTER 既有表**：不碰 V99 / V118 / V122 / V126 的任何一张表，跨车道 DDL 回归面 = 0。
- 只读上游：9.6.1.2 `fulfillment_dropship_order` / `_line`、9.6.1.1 `fulfillment_dropship_config`、
  9.1 `sales_order`（取 `order_source` 作平台键）**全部同属 trade 库**，因此可同库 FK / 只读 join。
- 一条 SQL 都不碰 5.2.3.1 / 5.2.3.2（catalog）、12.1.2.1 / 13.4.1.x（finance）。
- 不依赖 9.4.1.2 发货记录（本批另一车道，本模块未用到）。

## 过程中的两个真实坑（都留了记录）

1. **全角标点漏进 SQL 字符串字面量**：`（`、`），` 写在引号外，
   PG 报 `syntax error`。在 Java 里拼带中文的 SQL 时要格外小心。
   另外 `//` 行注释写进字符串拼接里，会把它后面的内容整段吞进 SQL。
2. **api-proxy 白名单插入点**：本组必须插在 `b2b-orders` 可选组**之后**、外层括号**之前**。
   早先一版把 `?` 放错位置，导致**裸 `trade/b2b-orders` 也被拒** ——
   由 `b2b-order-allowlist.test.ts` 反向钉住时发现。最终用「最长公共前缀 / 后缀逐字比对」
   证明该行是**纯插入、零删除**，只插入了本组。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V130__trade_dropship_margin_reconciliation.sql`

**后端**
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipMarginModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipMarginService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipMarginController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/DropshipMarginIntegrationTest.java`（42 例）

**前端**
- `apps/web/src/app/orders/fulfillment/dropship-margin/page.tsx`
- `apps/web/src/components/orders/dropship-margin-workspace.tsx`
- `apps/web/src/components/orders/dropship-margin-model.ts`
- `apps/web/src/components/orders/dropship-margin-model.test.ts`（27 例）
- `apps/web/src/app/api/trade/dropship-margin-allowlist.test.ts`（5 例）

**共用 / 台账**
- `apps/web/src/lib/api-proxy.ts`（trade 段外科式最小追加一组）
- `packages/api-contracts/src/contracts/trade.ts`（codegen，+33 类型、零真实删除）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 9.6.2.1 |` 一行）

## 验证

- L0：`./mvnw -q compile` 绿。
- L1：`./mvnw -pl services/trade -am test` → **411 例全绿**，含三个兄弟代发套件
  （DropshipConfig 24 / DropshipOrder 28 / DropshipTracking 29）无回归。
- 迁移在真实 PG 16 上单独全链验证过（V1 → V130 依次应用无错），
  并实测了三值判定与延迟合计触发器。
- `pnpm typecheck` 3/3 绿；`pnpm vitest run apps/web/src/app/api/trade/ apps/web/src/components/orders/`
  → 316 例全绿（含 12 个白名单套件 69 例）。

## 后续待办

1. **5.2.3.1 交付后**：把平台费来源从 `TENANT_MAINTAINED` 换成 `MODULE_5_2_3_1`，
   `fulfillment_dropship_fee_rate` 表结构不需要改一行（枚举位置已留好）。
2. **12.1.2.1 交付后**：finance 消费 `fulfillment_dropship_payable_task`，
   把 `task_status` 从 OPEN 推进到 SENT / ACKNOWLEDGED。
3. **13.4.1.1 交付后**：利润报表按 `business_model` 分组消费本模块的差价口径。
4. **`undetermined_*` 桶清空后**：需要重开一张对账单（已 FINALIZED 的那张按设计不可改）。
