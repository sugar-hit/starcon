# 2026-09-27: 数据实体与表结构设计

## 摘要

用户要求「先设计数据实体和表结构，不着急写代码和页面」，并问 ORM / DB 选型，确认「这里先用 pg」。本轮产出完整数据模型设计稿（`db/schemas/` 9 个文件 + ERD）、选型调研、数据模型未决问题 backlog。**本轮未写任何后端代码、未产出 Flyway 迁移。**

## 直接回答选型

- **DB = PostgreSQL 16**。星云 ERP / 订单聚合场景的事实标准；`NUMERIC` 精度、行锁条件更新、`GENERATED` 生成列、`DEFERRABLE` 约束触发器都是本设计的依赖项
- **ORM = 不引入 ORM**。采用 **jOOQ**（从 schema 生成类型安全查询类，SQL 由人写），已在 [ADR-0003](../../decisions/0003-backend-runtime-kotlin-spring-boot.md) 锁定。jOOQ 严格说不是 ORM —— 这是刻意的：库存扣减与财务过账依赖「这段 SQL 到底做了什么」可逐行审阅，Hibernate 的隐式 flush 时机与懒加载会让关键扣减变成不可预期的隐式 SQL，表现为超卖或账不平

## 关键设计取舍（12 条，全部记入调研）

1. **UUIDv7 应用层生成** —— PG 16 没有内置 `uuidv7()`（PG 18 才有），所以这是硬要求不是风格偏好
2. **`TEXT + CHECK` 而非 PG `ENUM`** —— ENUM 删值需重建整个类型；订单状态必然演进，演进成本要低
3. **默认不做软删除**（3 个明确例外）—— 软删除行仍占唯一编码，且是 RLS 之外第二套易漏的过滤条件
4. **库存余额权威在仓库级，不在库位级** —— 履约的可用量决策单位是仓库；权威放仓库级，原子预占才是单行条件更新。库位进键就得 `SUM` 后写回，重新引入竞态
5. **流水 + 余额双表** —— 余额可审计可重算，代价是必须同事务写
6. **SPU/SKU 变型用两张关联表** —— 不用多态外键，因为多态拿不到引用完整性，错了会生成重复 SKU 组合
7. **`listing_sku_mapping` 刻意冗余 `shop_id`** —— 换取「同店同 external_sku 只能映射一个内部 SKU」这条唯一约束可表达
8. **状态机数据驱动**（`order_status_transition`）
9. **借贷平衡用 `DEFERRABLE INITIALLY DEFERRED` 约束触发器** —— 不平的凭证在 DB 层就存不进去
10. **`journal_entry.source_id` / `attachment.resource_*` 用弱引用** —— 宁可一处弱引用换掉「建空壳父表」对全模型的污染
11. **角色按租户隔离，权限码表全局**
12. **`membership` 唯一 `(tenant_id, user_id)`** —— 让租户解析退化成一次唯一查找，这是 ADR-0002 的落地关键

## 大量设计被压进 DB 层

不是「应用层记得判断」而是 DB 拒绝：

| 约束                                                         | 拦住的错误           |
| ------------------------------------------------------------ | -------------------- |
| `CHECK (available >= 0)`（生成列）                           | **超卖**             |
| `CHECK (grand_total = subtotal - discount + shipping + tax)` | 总额与分项对不上的账 |
| `CHECK (fulfilled_qty <= quantity)`                          | 发货量超过订购量     |
| `CHECK (received_qty + rejected_qty <= quantity)`            | 收货+拒收超过订购量  |
| 延迟约束触发器                                               | **借贷不平的凭证**   |
| `UNIQUE (tenant, shop, external_order_no)`                   | 平台重推产生重复订单 |
| `UNIQUE (tenant, method, external_txn_id)`                   | 支付回调重复入账     |
| `UNIQUE (tenant, scope, idempotency_key)`                    | webhook 重复处理     |

## 诚实性约束（不伪造成功）

- `shipment.is_manual` + `CHECK (is_manual = FALSE OR manual_note IS NOT NULL)` —— 无真实物流接口时人工出库必须写原因，**不允许把人工出库伪装成平台回传成功**
- `stock.standard_cost` 明确标注为「缓存的当前值」，真实成本历史在采购行与库存流水里

## 涉及文件

- `db/schemas/README.md` — 全局约定（12 条强制）+ 全表清单 + owner 归属
- `db/schemas/erd.md` — 三张 mermaid 关系图 + 依赖方向 + 循环依赖的处理
- `db/schemas/01-tenancy.md` — 租户/组织/用户/成员/角色权限 + **租户上下文注入链路**
- `db/schemas/02-master-data.md` — SPU/SKU/属性/分类/品牌/单位 + 供应商/客户/承运商/地址
- `db/schemas/03-channel.md` — 平台/店铺/凭据引用/listing/外部映射/同步状态
- `db/schemas/04-inventory.md` — 仓库/库位/余额/流水/预占
- `db/schemas/05-order.md` — 订单/订单行/状态机/出库/包裹/轨迹
- `db/schemas/06-purchase.md` — 采购/收货/退货退款
- `db/schemas/07-finance.md` — 币种/汇率快照/结算/收付款/双分录
- `db/schemas/08-platform-infra.md` — outbox/幂等/审计/附件
- `db/schemas/09-pricing-and-crm.md` — 定价/客户标签/接触记录
- `docs/investment/2026-09-27-data-model-and-orm-decision.md` — 选型调研 + 12 条取舍的备选对比
- `docs/backlog/2026-09/2026-09-27-data-model-open-questions.md` — A/B/C 三组未决问题
- 本会话日志

## 验证

- 本轮只改 Markdown，未跑 `pnpm lint/typecheck/test`
- 链接目标已逐个校验存在
- 交付前 `prettier --check` 通过

## 后续待办

**阻塞项**：[backlog A 组 8 条](../../backlog/2026-09/2026-09-27-data-model-open-questions.md) 需要业务答案，其中两条会影响核心设计：

- **A3 批次/序列号/效期** —— 若支持，库存余额键要加 lot 维度，**会改动 04 的核心设计**
- **A7 `org` 页面到底是什么** —— 组织管理？平台组织架构（Amazon marketplaces）？公司设置？不清楚会导致 `organization` 表方向错

B 组 7 条需要技术 spike，其中 **B1 租户创建时的 RLS 上下文**（还没有 `app.current_tenant` 时怎么建首个租户）和 **B2 jOOQ codegen 与 Flyway 的任务顺序**（CI 每次 codegen 都要起 PG）要优先。

A + B 收敛后，数据模型从设计稿升级为可实施，M1 才能开工。

## 关联

- [ADR-0002 多租户](../../decisions/0002-multi-tenancy-strategy.md)
- [ADR-0003 后端运行时](../../decisions/0003-backend-runtime-kotlin-spring-boot.md)
- [选型调研](../../investment/2026-09-27-data-model-and-orm-decision.md)
- [主数据与一致性调研](../../investment/2026-09-27-backend-and-master-data.md)
- [上一轮：技术栈 ADR](2026-09-27-backend-stack-adr-kotlin-spring-boot.md)
