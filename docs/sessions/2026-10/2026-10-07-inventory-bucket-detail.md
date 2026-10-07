# 7.1.1.2 库存明细 · stock_bucket 事实层 + 逐级下钻视图

日期: 2026-10-07 · 分支 `feat/7-1-1-2-inventory-bucket-detail` · 基线 `feat/3-4-2-2-description-library` (2857047)

## 决策摘要

1. **分层，不改 balance 的键**。新增 `stock_bucket`（仓库 × 库位 × 批次粒度）承载明细行；
   `inventory_balance` 保持仓库级不变，仍是可用量决策单位。
2. **守恒由 DB 强制**：复合外键锁定 bucket 的六个维度与它的 balance 行逐列相同，
   两条 `DEFERRABLE INITIALLY DEFERRED` 约束触发器在提交前核对 Σbucket == balance。
3. **bucket 行不允许删除**，清空请归零 —— 否则"删掉最后一条"会让余额悄悄退回未分解状态。
4. **业务时间与系统时间分开存**：`last_movement_at`（业务发生时间，写入方必须提供，
   DB 强制其在数量变动时前进）与 `updated_at`（系统时间，DB 维护）。
5. **库位与批次只建最小真实档案**，不越界到 08.1.2.x（库位树 / 自动分配）与
   7.3.1.2（FEFO / 效期策略）；刻意不留 `parent_id` 之类的半截列。
6. **行内操作入口给路由契约 + 如实标不可用**：`available=false` + note 说明目标模块
   （7.4.1.1 / 7.5.1.1 / 7.1.2.1）未交付，不给点下去 404 的死链。
7. **前端 `/inventory` 双视图**：总览 ↔ 库位明细切换；总览行按后端给的 `detailHref`
   契约下钻（前端解析 href，不另拼一份参数名）。

## 关键 trade-off

### 为什么是分层而不是给 balance 加两列

V1 文件头写死「键**不含** location_id —— 履约的可用量决策单位是仓库不是库位，
把库位放进键里就得 SUM 后再 UPDATE，重新引入竞态」；而 7.1.1.2 又要求最小事实单位是
「法人货权 × SKU × 仓库 × **库位** × **批次** × 质量状态」。两条同时为真时唯一自洽解是分层。
若把 location_id / lot_id 塞进 balance 的唯一键，预占就变成 SUM 后再 UPDATE ——
正是 V1 拒绝的竞态，还要推翻已落库的生成列口径与 `guard_balance_reservable`。

### 守恒用延迟约束触发器，而不是即时触发器或只靠应用层

一次库存业务本来就要「写 balance → 拆 bucket → 写流水分录」，即时触发器会在事务中途
对着半成品报差异。延迟到提交前才对平，语义才是"一次业务结束后必须对得上"。
balance 侧与 bucket 侧各一条：只改 balance 不同步 bucket 同样会被拦下。

### 「未分解」是合法状态，必须与「不平」分开

没有任何 bucket 的 balance 行处于 7.1.1.1 的仓库级口径，**不受**守恒约束。
所以响应里除 `onHandConserved` 外单列 `undecomposedBalanceCount`，界面上也单独提示 ——
把两者混成"差异"会让明细页看起来像漏数据。

### 核对数字不受行筛选影响

只筛一个库位时，若拿"筛选后的 bucket 汇总"与"balance 全量"对比，必然假报不平。
所以待核对的两边都是**该 balance 的全部 bucket**（`bucketCountForTouched`），
这样任意筛选组合下核对都成立。

### 行内操作入口：契约给全、可用性如实标

蓝图要求明细行带三个操作入口，但目标模块都没交付。做法是 href 给全（带 bucket 定位参数），
`available=false` + note 说明原因。给一个必然 404 的按钮比不给更糟（用户以为系统坏了）；
完全不给又违背蓝图对「行内操作入口」的要求。

### 前端下钻参数从后端 href 解析，而不是前端另拼

`parseDetailHref(row.detailHref)` 按后端 `InventoryOverviewService.detailHref()` 生成的
参数名解析，参数名只存在一处。缺任一维度按"无下钻"处理 —— 带一半条件会让用户以为
看到的就是那一行。

## 文件清单

**新增 · 后端**

- `backend/services/warehouse/src/main/resources/db/migration/V2__warehouse_inventory_bucket.sql`
- `.../com/starcon/warehouse/InventoryBucketModels.java`
- `.../com/starcon/warehouse/InventoryBucketService.java`
- `backend/services/warehouse/src/test/java/.../InventoryBucketIntegrationTest.java`（31 例）

**改动 · 后端**

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryController.java`
  （`/inventory/buckets`、`/inventory/bucket-actions`、`/locations`、`/lots`）

**新增 · 前端**

- `apps/web/src/components/inventory/inventory-bucket-detail.tsx`

**改动 · 前端**

- `apps/web/src/components/inventory/inventory-model.ts`（bucket 查询串、下钻解析、展示口径）
- `apps/web/src/components/inventory/inventory-model.test.ts`（新增 15 例）
- `apps/web/src/components/inventory/inventory-workspace.tsx`（双视图 + 行内下钻）
- `apps/web/src/lib/api-proxy.ts`（warehouse allowlist 放行 buckets/bucket-actions/locations/lots）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行清单钉住）
- `packages/api-contracts/src/{catalog.ts,warehouse.openapi.json}`（codegen 产物，未手改）

**文档**

- `docs/product/capability-blueprint/module-progress.md`（7.1.1.2 → 已交付）
- 本文件

## 踩坑记录

- **DELETE 行触发器里 `NEW` 未赋值**：直接取 `NEW.tenant_id` 会抛
  「record new is not assigned yet」，`COALESCE(NEW.x, OLD.x)` 也救不了，要按 `TG_OP` 分流。
- **`select distinct tenant_id`** 在六表 JOIN 下 ambiguous：必须写 `b.tenant_id`。
- **两段 `where` 拼出语法错误**：`... where not exists (...) where b.warehouse_id = ?`。
  「没有 bucket」必须与筛选条件 AND 在**同一个** where 里。
- **balance 级条件引用了不存在的别名**：`undecomposed()` 的 FROM 别名必须与
  `conditions()` 生成的 `b.xxx` 一致。
- **关键字条件在 BALANCE 作用域引用 `loc` / `lot`**：这两张表只在 bucket 作用域存在，
  条件要按作用域分支（否则 422 变 409）。
- **Java 全角引号不能进标识符**：`「」` 出现在方法名里编译失败。
- **`new BigDecimal.ZERO` 不是合法构造**：`ZERO` 是静态字段。
- **PG 把 FK 约束名截断到 63 字符**：按完整名字匹配查不到，按前缀匹配。
- **权限口径**：`AccessService` 要求 `warehouse.inventory.read` 且父级 `.read`
  同时具备；只有页面级权限不会被 403，别按"服务级读权限"理解。
- **V1 的 `REVOKE ... flyway_schema_history`** 让脱离 Flyway 手跑 psql 时 V1 末行报错
  （迁移由 Flyway 跑时该表已存在，属正常）。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS；identity 35 / catalog 545 / channel 67 /
  warehouse 62（= 31 新增集成 + 30 基线 + 1 契约），合计 709 例，0 失败 0 错误
- `pnpm typecheck` → 3/3 成功
- `pnpm lint` → 3/3 成功
- `pnpm test` → 33 文件 / 471 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/inventory` 108 kB
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致
- V1+V2 迁移在临时 PG16 容器上逐条跑通，12 项 DB 约束逐个实测（守恒拒绝、归属锁定、
  禁删、时间前进、非可售禁预占、NULLS NOT DISTINCT、RLS/GRANT）

## 后续待办

- 7.1.2.1 库存流水台账：bucket 的 `version` 与 `last_movement_at` 已就位，
  写入方按库存业务单（`inventory_transaction.occurred_at`）提供业务时间即可
- 7.2.1.2 预占分配明细：bucket.reserved 已可存可读，分配到具体 bucket 时守恒同样生效
- 7.3.1.2 效期与先进先出：FEFO 分配属该模块；本模块只判定并展示 `expired`
- 7.4.1.1 / 7.5.1.1：落地后把 `InventoryBucketService.actions()` 的 `available` 置 true
  并同步 `apps/web/src/lib/api-proxy.ts` 的 warehouse allowlist
- 08.1.2.1 库位树：给 `warehouse_location` 加层级与防环（本期刻意未预留 `parent_id`）
- 7.1.1.3 可用量计算：复用 `reconciliation` 与 `pickable` 做可售量拆解
