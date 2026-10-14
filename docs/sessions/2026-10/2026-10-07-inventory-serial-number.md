# 7.3.2.1 序列号 — D3 交付

车道 `feat/7-3-2-1` · 2026-10-07 · 基线 `integration/module-dev`（5b939e0）

## 决策摘要

蓝图给 7.3.2.1 的一句话是「序列号校验是一致性硬约束：数量为 1 时必须有且仅有 1 个序列号，不允许『大概齐』」。
**本模块的全部设计都是围绕怎么让「大概齐」在系统里无法存在**，而不是围绕怎么在界面上提醒一句。

这条约束拆成四层，缺任何一层都留了缺口：

| 层  | 位置                                       | 挡住什么                                                                                                                      |
| --- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| 1   | `InventorySerialService.apply`             | 应用层比对 `declaredQuantity` 与 `serialNumbers.size()`，不等则**一条都不写**                                                 |
| 2   | `persist` 落库前                           | 逐号校验可能因撞号/SKU 不符排除掉某些号，此时条数不再等于声明数 —— 必须在写之前停，否则要到 COMMIT 才炸，会伪装成随机提交失败 |
| 3   | `CHECK(serial_count = declared_quantity)`  | 「声明自相矛盾」无法落库（顺带 `trunc()` CHECK 挡住带小数的离散件数量）                                                       |
| 4   | `DEFERRABLE INITIALLY DEFERRED` 约束触发器 | 「数量对得上、实际少写两件」—— 这一层是应用层**验不出来**的                                                                   |

第 4 层是唯一真正闭合缺口的一层，也是本模块最值得记住的设计。前三层都假设「代码是照写的」，
只有第 4 层在 COMMIT 时拿真实行数与声明比对，它不信任任何调用方。

## 关键 trade-off

### 1. 为什么多出 `inventory_serial_operation` 这张表

原本想把「数量 ↔ 条数」直接写成服务里的一段校验。但那样它就只对**照写的代码**成立，
直接写 SQL 或将来多一条写入路径就绕过去了，而且违反 AGENTS.md 的「关键 SQL 必须可审阅」。

代价是三张表而不是一张。收益是这条硬约束落进了 schema：没有承载「声明数量」和「实际条数」两个数字的行，
它就只能是一句可以被绕过的约定。流转行共用 `operation_id`，
「这一单到底动了哪几个号」因此是一次 `group by` 而不是把号塞进数组列。

### 2. 报废只能走 OUTBOUND，不走 STATUS_CHANGE

V58 的 CHECK 规定「报废的号不能挂在库位上」。而货报废时它确实已经从货架上被拿走了。
所以 `OUTBOUND + toStatus=SCRAPPED` 表达的正是这件事，语义与物理一致。

代价：状态机少了一条「在库 → 报废」的直接边，操作员要选「出库 + 报废」两步。
收益：不会出现「状态改成报废、库位还挂着」这种既违反约束又违反物理的数据。
界面上必须把这层意思讲清楚，否则会被当成限制。

### 3. 删除档案走 `SECURITY DEFINER` 函数，而不是直接 `delete`

流转历史是 append-only，应用角色连 `DELETE` 权限都没有。但「扫码录错了」必须能修掉。
于是有 `discard_unused_serial(uuid)` 这个窄入口 —— 数据库里**唯一**被允许删除序列号历史的路径，
它把守卫条件重新校验一遍（守卫不是一个可绕过的开关，而是进入这个函数的前提）。

守卫判据抽成共享函数 `inventory_serial_unreferenced` 给触发器与该函数各调一处：
最早写成「流转条数 ≤ 1」，结果「录错后又挪过一次位」的号（同样只有 1 条流转）会被误删 ——
测试抓到的。判据收紧成「最多一条，且那条必须是建档时那次纯入库」。

### 4. 录入即入库

「扫码录入」时直接写「档案 + 操作头 + 一条 INBOUND 流转」，而不是先建档、以后再补入库记录。
后者会让号在它已经在库的那段时间里没有任何流转历史，正是事后查不到「这号什么时候到的」的原因。
代价是「建档」与「入库」在数据上分不开，作废时必须连带删掉那次操作头与流转（见上面第 3 点）。

### 5. 校验错误逐条按号归因，而不是回一句「校验不通过」

15 个错误码枚举（`SERIAL_COUNT_MISMATCH` / `TRANSITION_NOT_ALLOWED` / `LOCATION_MISMATCH` / `NO_CHANGE` …），
每条带 `serialNumber` / `expected` / `actual`。只回一句失败等于把定位的活儿全推给操作员，
而他手里正拿着上百个号。界面按码分流：「整单退回」（少扫两台）与「挑出个别号」（那两个号被锁在维修库里）
是两种完全不同的正确处置，混在一张红框里等于没归因。

### 6. 前端提交前的本地预检（明确不替代后端）

`localQuantityGuard` 在按下提交前就把「数量写了 3、扫到 2 个」说出来。
它不重复业务规则（后端永远以服务端为准，V58 还有两层），它做的是一件后端做不到的事：
**让人在还没离开货架的时候就知道少扫了两台**。收货现场的错误越晚发现，补扫成本越高。

## 实现中踩到并修掉的坑（跨车道同样适用）

1. **`= any(?)` 在纯文本 SQL 里不是数组绑定。** jOOQ 把 `String[]` 当成多个独立绑定发出去，
   PG 报 `op ANY/ALL (array) requires array on right side`。改成按个数生成 `IN (?,?,...)` 占位符，
   占位符个数由入参个数决定，不存在被填错的位置。
2. **写入顺序必须是「档案 → 流转」。** 流转行有指向档案的外键，反过来写会让建档那次直接撞外键，
   而且这个顺序不能靠「先写完一起提交」绕过去 —— 外键是即时校验的。
3. **服务方法必须在绑定 `app.current_tenant` 的事务里调。** 测试里裸调 `svc.page(...)` 会「看起来通过」
   其实读到空集 —— RLS 挡得死死的。这是最容易被误当成绿灯的假阳性。
   测试因此给每个入口包了一层 `page()/movements()/profileB()`，B 侧读口是独立的：
   用 A 的包装器「顺便看一眼」证明不了隔离。
4. **`?::timestamptz` 后面要跟一个字面量。** 落在 values 末位会让 jOOQ 整体绑定错位一格。
   本模块每个 INSERT 的 cast 后面都跟 `now()` 或 `)` 并逐个核对过「列数 = 表达式数 = 绑定数 + 字面量数」。
5. **纯文本语句里 jOOQ 拿不到列类型。** `fetchValue` 返回 `Object`（窄化要自己写）、
   `get(field, OffsetDateTime.class)` 在拿到 `Instant` 时会抛 ClassCastException（沿用 `moment()` 兜底）。

## 跨车道约束回归自查

V58 只做 `CREATE TABLE` / `CREATE FUNCTION` / 只在**自己三张新表**上 `ENABLE + FORCE RLS` 与 `GRANT/REVOKE`：

- **没有** `ADD COLUMN`（因此不存在「无 DEFAULT 兜底的新列」）
- **没有** `SET NOT NULL`（因此不存在「无 BEFORE INSERT 触发器兜底的 NOT NULL」）
- **没有**触碰任何既有表、既有函数或既有触发器

批次只读引用 V2 已有的 `inventory_lot`，走复合外键 `(tenant_id, lot_id)`。
兄弟模块 7.3.1.1 在另一条车道上开发 V57，本迁移**不依赖它的任何新增对象**。

全量 `./mvnw verify` 覆盖既有 6 个 warehouse 集成测试类全部通过，无回归。

## 文件清单

**新增**

- `backend/services/warehouse/src/main/resources/db/migration/V58__warehouse_inventory_serial.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventorySerialModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventorySerialService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventorySerialController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventorySerialIntegrationTest.java`
- `apps/web/src/components/inventory/inventory-serial.tsx`
- `apps/web/src/components/inventory/serial-model.ts`
- `apps/web/src/components/inventory/serial-model.test.ts`
- `apps/web/src/app/inventory/serials/page.tsx`

**生成物（禁止手改）**

- `packages/api-contracts/src/catalog.ts`（新增 15 个 `Serial*` DTO）
- `packages/api-contracts/src/warehouse.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 7.3.2.1 那行的状态与证据；其余是脚本刷的派生计数）

## 后续待办

- **7.3.1.1 批次管理（V57，另一条车道）** —— 本模块只读引用它，不依赖。并入后建议复核
  `inventory_serial.lot_id` 的复合外键在对方迁移下的行为（对方目前不应改 `inventory_lot` 的既有列）。
- **7.3.1.2 效期与 FEFO** —— 蓝图标明第一期可延后。`inventory_lot.expires_on` 已经在，
  届时序列号的批次分配策略一并设计，不要在 7.3.2.1 里提前埋半截列。
- **与 8 收货拣货 / 11 售后 / 16 合规对接** —— `ref_type` / `ref_id` 已经是开放的来源单据锚点
  （PO / SO / RETURN / REPAIR / ADJUST 都只是字符串约定）。真正的对接时应把这些取值收敛成一份词表，
  届时建议在 `warehouse` 侧加一张 ref_type 维表，而不是继续往 CHECK 里加字面量。
- **`SerialVocabularies` 端点已把枚举下发前端** —— 若将来枚举扩容，先跑
  `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 再改前端。

## 验证方式（真实结果）

```bash
cd backend && ./mvnw verify          # BUILD SUCCESS；warehouse 151 tests / 0 failures；全仓 1605 tests
pnpm typecheck                       # 3/3 tasks successful
pnpm lint                            # 3/3 tasks successful
pnpm test                            # 59 files / 1072 tests passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   # ✓ Compiled successfully，/inventory/serials 9.98 kB
node tools/module-progress/sync.mjs --check                    # ✓ 台账与蓝图一致
```

基线对照：并入前在基线提交上实跑量过一次，`1575 tests / 0 failures`；本次 `1605`，
增量 30 全部来自新增的 `InventorySerialIntegrationTest`，**既有测试数没有下降**。
