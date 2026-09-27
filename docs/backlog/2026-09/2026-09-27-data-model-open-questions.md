# 数据模型未决问题

- 创建: 2026-09-27
- Owner: TBD
- 优先级: P0 —— 写第一条 Flyway 迁移之前必须收敛
- 关联: [数据模型设计](../../../db/schemas/README.md)、[选型调研](../../investment/2026-09-27-data-model-and-orm-decision.md)、[ADR-0002](../../decisions/0002-multi-tenancy-strategy.md)、[ADR-0003](../../decisions/0003-backend-runtime-kotlin-spring-boot.md)
- 状态: **Open**

## 背景

[数据模型设计](../../../db/schemas/README.md) 是设计稿，标了 12 条取舍。有几处我按「先不复杂化」做了决定，但它们**需要业务侧确认**，不是纯技术判断。写迁移之前不定下来，改起来就是数据迁移。

判断标准分两类：**需要业务答案的**（下面 A 组）先问人；**需要技术验证的**（B 组）先做 spike。

---

## A 组 · 需要业务确认（阻塞迁移）

- [ ] **A1 · 调拨（warehouse transfer）缺表**
      设计里 [04-inventory.md](../../../db/schemas/04-inventory.md) 的不变量提到「跨仓库调拨 = 一次 OUT + 一次 IN，同一事务」，但**没有 `inventory_transfer` 表**。调拨单需要单据头（在途状态、发起仓、目标仓、预计到达），否则无法追踪在途库存。
      → 决定：加 `inventory_transfer` / `inventory_transfer_line`，还是第一期不做调拨？

- [ ] **A2 · 盘点（cycle count）缺流程**
      库存表能记 `ADJUST_IN` / `ADJUST_OUT`，但没有盘点单。需要「盘点 → 差异 → 审批 → 调整」才能审计。当前设计等于允许任何人直接调库存。
      → 决定：盘点单要不要进第一期？盘亏盘盈是否需要审批流？

- [ ] **A3 · 批次 / 序列号 / 效期要不要**
      `sku.is_serial_tracked` 字段已预留，但 `inventory_ledger` 是 SKU 粒度，**没有批次或序列号维度**。食品、化妆品、电子类目必然要效期（FEFO 出库）。
      → 决定：第一期支持的类目是否包含效期管理？涉及类目就要建 `inventory_lot`（batch + expiry + serial），且库存余额键要加 lot —— **这会改动 04 的核心设计**。

- [ ] **A4 · 一单一出库单够不够**
      [05-order.md](../../../db/schemas/05-order.md) 现在是 `UNIQUE (tenant_id, order_id)`，即一单只能一个出库单。真实场景经常拆单发货（部分缺货先发）。
      → 决定：支持拆单发货吗？支持的话改 `UNIQUE (tenant_id, order_id, sequence)`。

- [ ] **A5 · 一个租户内是否需要多组织**
      `membership.organization_id` 是单值，隐含「一个租户一个组织」。如果客户要在一个租户下区分多个事业部/品牌线，就需要拆 `membership_organization`。
      → 决定：租户 → 组织是 1:1 还是 1:N？

- [ ] **A6 · 「四个业务系统」怎么映射到权限**
      [01-tenancy.md](../../../db/schemas/01-tenancy.md) 判断「products/orders/inventory/finance 是工作界面视角而非组织实体」，所以没按它们拆 `organization`。需要确认 `permission` 码表的粒度 —— 是按「域 + 动作」（`order.read`）还是按「系统 + 动作」（`erp.orders.read`）。
      → 关联：前端 `packages/ui/src/lib/domain-spec.ts` 里已有 15 个域的规格，权限码表应对齐它。

- [ ] **A7 · `app/web` 的 `org` 页面到底是什么**
      前端有 `apps/web/src/app/org/page.tsx`（当前是 `DomainPlaceholder`）。从名字看像「组织管理」，但也可能是「平台组织架构（Amazon 的 marketplaces）」或「公司设置」。**这个不清楚，`organization` 表的设计方向就可能错。**
      → 需要看 `domain-spec.ts` 里 `org` 的规格，或直接问。

- [ ] **A8 · 价格与币种的关系**
      `price_list.currency` 独立于 `shop.default_currency` 和 `tenant.base_currency`。多币种定价（同款商品在 USD 店和 EUR 店不同价）是常态。
      → 决定：一价目多币种（`price_list_item` 加 `currency` 列）还是一价目一币种（按币种建多个价目）？

---

## B 组 · 需要技术验证（先做 spike）

- [ ] **B1 · 租户创建时的 RLS 上下文**
      `tenant` 表的策略是「只能读到自己那行」，但**创建租户时还没有 `app.current_tenant`**。需要一条独立的引导路径（用 `starcon_internal` 角色创建首个租户与 `is_system` 角色）。
      → 要验证这条引导路径不会被 RLS 堵死。

- [ ] **B2 · jOOQ codegen 与 Flyway 的执行顺序**
      codegen 读的是**迁移后的真实 schema**，所以 Gradle 里 `flywayMigrate` 必须早于 `jooqCodegen`。要确认这条任务依赖在干净 clone 上能跑通。
      → 风险：CI 每次跑 codegen 都要先起 PG。

- [ ] **B3 · 延迟约束触发器在连接池下的行为**
      `journal_entry_must_balance` 是 `DEFERRABLE INITIALLY DEFERRED`，在 `COMMIT` 时检查。要确认 Spring 的事务管理与 PG 延迟约束兼容（框架如果在提交后再做某些操作，可能影响约束求值时机）。

- [ ] **B4 · `CITEXT` 扩展的迁移顺序**
      `app_user.email CITEXT` 需要 `CREATE EXTENSION citetext`。要确认托管 PostgreSQL（如 RDS / Cloud SQL）是否默认允许该扩展，以及在迁移里创建是否需要超级用户权限 —— **与「运行角色非 superuser」的约束冲突时怎么办**。

- [ ] **B5 · 弱引用的孤儿巡检 SQL**
      `journal_entry.source_id` 和 `attachment.resource_id` 没有外键，需要巡检语句找孤儿。要写出可放进定时任务的 SQL 并确认其执行计划不走全表。

- [ ] **B6 · JSONB 字段的 RLS 交互**
      `outbox_event.payload`、`sales_order.raw_payload` 等含 JSONB。RLS 的 `WITH CHECK` 只约束 `tenant_id` 列，**约束不到 JSONB 内部**。要确认没有租户敏感数据被放进 payload 却没有行级保护。

- [ ] **B7 · `uuidv7()` 的应用层实现与 PG 16 兼容**
      ADR-0003 钉了 PG 16，PG 16 无内置 `uuidv7()`。要在 Kotlin 侧实现并补单测（单调性、时序排序），且确认与 jOOQ 的 `UUID` 类型映射正常。

---

## C 组 · 实现阶段要补的（不阻塞设计）

- [ ] **C1** `outbox_event` / `audit_log` / `idempotency_record` 的分区与保留策略。数据量增长后这三张表会最大。
- [ ] **C2** `analytics` / `profit` 两个页面对应的读模型。设计里没有预聚合表，报表直接查事实表在数据量上来后会很慢。
- [ ] **C3** `weight_kg` / `volume_cm3` 的单位换算。`unit_of_measure.dimension` 已有，但没有换算系数表 —— 混用 g/kg 时需要。
- [ ] **C4** `listing.status = 'DELETED'` 与 `deleted_at` 双标记是否冗余。当前两个字段表达同一件事，留着是为了少写一个迁移，但要确认不会不一致。
- [ ] **C5** `customer.external_customer_id` 的唯一性。现在无唯一约束（跨店铺会重复），若需要「按平台客户聚合」要另建店铺级映射。

---

## 完成标准

A 组全部有明确答案、B 组全部有验证结论后，[数据模型设计](../../../db/schemas/README.md) 从「设计稿」升级为可实施状态，M1（DB 直连 + 迁移 + jOOQ codegen）才能开工。
