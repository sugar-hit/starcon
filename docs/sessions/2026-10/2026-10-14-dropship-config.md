# 9.6.1.1 代发模式配置（阶段 D4，L3 锚点 9.6.1）

- 日期：2026-10-14
- 车道：`feat/9-6-1-1`（worktree `.worktrees/9.6.1.1`，基线 `integration/module-dev` @ `cbd78408`）
- 迁移：trade `V118__trade_dropship_config.sql`

## 决策摘要

蓝图的 9.6.1.1 有一条被加粗写在「关系」里的硬约束：

> **物流路径与货权分开判断**：不经自有仓不代表不承担货权；只有已确认的纯代理模式才适用不记我方库存，其他模式与 7.7 货权规则联合评审。

实现它有三种做法：加一个「是否代发」布尔；加一个「不经我方仓」布尔；或者把**物流**与**货权**拆成两个互不推导的字段，让「不记我方库存」成为两者**同时**成立才有的结论。选了第三种，因为前两种恰好是这句话要防的错误 —— 界面上一眼看到「走供应商直发」，就会得出「不占我方库存」。

落法是两条 `GENERATED ALWAYS` 生成列：

```sql
counts_against_own_inventory = fulfillment_path <> 'SUPPLIER_DIRECT' OR ownership_mode <> 'PURE_AGENCY'
pure_agency_exempt           = fulfillment_path =  'SUPPLIER_DIRECT' AND ownership_mode = 'PURE_AGENCY'
```

生成列是这一版的核心收益：**应用层改不了，也就没有「服务层忘了判」这条路**。服务层只读出来如实报给下游 9.6.1.2，不另算第二份；前端同样只读 `pureAgencyExempt`，不重算。两份算法迟早漂移，漂移后界面显示的「不占我方库存」就会和下游实际预占与否对不上。

四种组合里最要紧的是 `SUPPLIER_DIRECT + OUR_ACCOUNT`（VMI 型：供应商替我发货但货权在我方）—— 它**必须**占用我方库存口径，集成测试专门为这一格留了断言。

## 关键 trade-off

1. **不预占我方库存，而只报出「该不该占」的结论。**
   真正预占要调 warehouse 的授权 API，那是下游 9.6.1.2 的职责。现在假装预占，两边会同时以为占了同一批货。`capabilities` 里 `reservesOwnInventory=false` 如实报出。

2. **上游一律用带来源标注的只读投影，不跨库读。**
   2.3.1 店铺（channel）、3.6.2 供应商报价（catalog）、7.1.1.3 可售量（warehouse）分属三个独立数据库。沿用 9.3.2.1/9.3.2.2 的同一套做法：投影表带 `source`（MODULE_* / TENANT_MAINTAINED）与 `observed_at`，将来同步器按同一形状写入即可，**表结构与判定逻辑都不需要改**。
   `capabilities` 里 `OWN_STOCK` 维度显式 `available=false`（蓝图明写「代发不查自有库存」），并写明理由，而不是画成 0 或「—」。

3. **「没有观测」与「观测为 0」在数据层就分开。**
   `supplier_available_qty` 为 NULL 时 reason_code 是 `NO_SUPPLIER_STOCK_RECORD`，理由串明写「没人报过 ≠ 供应商确实没货」。0 是确定结论，NULL 是缺口，画成同一个 0 会让人以为这单确定发不出。

4. **差价转负不拦，但一定显形。**
   拦了就录不进真实亏损的供应商报价。改由可售校验抓成 `NEGATIVE_MARGIN`，再由 `listing_policy` 决定下架 —— 蓝图下游 9.6.1.2 说的「差价转负会静默亏损」，闸门要开在配置与刊登这一层。

5. **可售与可刊登分两列。**
   `ALWAYS_LIST` 下「不可售但仍可刊登」是合法状态。合成一列会让页面分不清「该去补货」与「该下架换供应商」。

## 踩到的三个真问题（都值得留档）

1. **CHECK 的蕴含方向写反了。**
   最初写的是 `ownership_mode <> 'PURE_AGENCY' OR (三个确认字段齐)`。这在 `OUR_ACCOUNT` 时前半段直接为真、恒过；而在 `PURE_AGENCY` 时前半段为假、变成「纯代理**必须**带确认」—— 两个方向正好都反了，17 个用例全被 check3 拒掉。改成两个方向互斥的分支才对。教训：写「A 不成立时必须 B」不要用单向蕴含，除非你确认过 SQL 三值逻辑下 `A IS TRUE` 分支的短路方向。

2. **同表多个 BEFORE 触发器按名字字母序触发。**
   `dropship_ownership_clear_on_change` 与 `dropship_ownership_confirmed` 都挂在 config 表上，本以为写文件的顺序就是执行顺序。实际 PostgreSQL 按**触发器名字母序**触发，所以确认器原本先跑、随后确认才被清空 —— 作废形同虚设。改成 `clear_on_change` < `confirmed` 才正确（cl < co），并在迁移里把这个纪律写进注释。

3. **`Timestamp.valueOf` 的时区陷阱在 SQL 占位符上同样有对应物。**
   `?::timestamptz` 的 cast 必须写在 values 的**对应位置**，写在绑定序列末位之后会让 cast 落到最后一个参数上（本次 `evaluation` 的 INSERT 少写了一个 `?`，结果 `evaluated_at` 的 cast 套到了 `counts_against_own_inventory` 的布尔值上，报「cannot cast type boolean to timestamp」）。写时间的测试断言了写回值（`timestamptz写入没有静默偏移`）。

## 文件清单

**后端（trade）**
- `backend/services/trade/src/main/resources/db/migration/V118__trade_dropship_config.sql`（新建，7 表 + 生成列不变量 + 6 个触发器 + RLS/GRANT）
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipConfigModels.java`（新建，全部 `Dropship*` 前缀避免 schema 键冲突）
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipConfigService.java`（新建，jOOQ 显式 SQL）
- `backend/services/trade/src/main/java/com/starcon/trade/DropshipConfigController.java`（新建，`/trade/fulfillment/dropship` 12 个方法）
- `backend/services/trade/src/test/java/com/starcon/trade/DropshipConfigIntegrationTest.java`（新建，24 例）

**契约（codegen 产物，只经脚本生成）**
- `packages/api-contracts/src/contracts/trade.ts`（+29 类型）
- `packages/api-contracts/src/trade.openapi.json`

**前端**
- `apps/web/src/lib/api-proxy.ts`（外科式最小追加 trade 段的 `fulfillment/dropship` 一组）
- `apps/web/src/app/orders/fulfillment/dropship/page.tsx`（新建）
- `apps/web/src/components/orders/dropship-config-workspace.tsx`（新建）
- `apps/web/src/components/orders/dropship-config-model.ts`（新建）
- `apps/web/src/components/orders/dropship-config-model.test.ts`（新建，17 例）
- `apps/web/src/app/api/trade/dropship-config-allowlist.test.ts`（新建，6 例）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 9.6.1.1 那一行的「状态」「证据」两格）

## 验证

- L0：`./mvnw -q -o compile` → exit 0
- L1：`./mvnw -o -pl services/trade -am test` → **Tests run: 219, Failures: 0, Errors: 0**（含 DropshipConfigIntegrationTest 24 例）
- `pnpm typecheck` → 3 packages 全绿
- `pnpm vitest run`（本模块 2 个文件）→ 23 passed
- `pnpm vitest run apps/web/src/app/api/trade/`（含兄弟车道 6 个白名单文件）→ 36 passed，确认外科式追加没挤掉任何既有分支

多租户三断言实跑（Testcontainers PostgreSQL 16，连接池压到 1）：两租户互不可见 / 伪造 `X-Tenant-Id` 无效（读路径 404，且**断言伪造写入的行确实落在自称的租户名下**）/ 连接池归还后不串租户（5 轮 A→B 交替 + 事务外读为空）。

## 后续待办

- **9.6.1.2 代发单与供应商下单**：消费本模块的可售标记；下单时才知道要不要去 warehouse 预占我方库存（`counts_against_own_inventory=true` 才预占）。供应商涨价/无货的显式告警也落在那条车道。
- **同步器**：三张投影表的 `source` 目前都是 `TENANT_MAINTAINED`。2.3.1 / 3.6.2 的同步器落地后按同一形状写入并改 `source` 枚举即可，本模块表结构与判定逻辑不需要改。
- **9.6.2.1 差价与对账**：`expected_margin_amount` / `expected_supplier_amount` 是生成列，对账时可直接取，不需在财务侧重算。
- `warehouse.ts` 在集成头相对已提交版本有 47/47 纯重排漂移（7.2.2.2 安全库存那次 codegen 留下的），本车道已 revert 不带入；谁先碰契约谁顺手重生成一次即可。
