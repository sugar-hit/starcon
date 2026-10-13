# 2026-10-13: 契约 DTO 简单类名撞车（warehouse 服务内 springdoc 覆盖）

## 摘要

L2 批次闸里 `pnpm typecheck` 红，根因是 **warehouse 服务内部**有两个 record 的简单类名重复
（`ScopeOption`、`StatusOption` 各两份，形状不同）。springdoc 用 record 的**简单类名**做
`components.schemas` 的键，同名 ⇒ 后者覆盖前者 ⇒ `DutyTaxCapabilities` / `FacilityOptions` /
`ReservationOptions` 等既有消费方被静默改了形状。`pnpm test` 与 `pnpm lint` 全绿是因为
vitest 不做类型检查，只有 tsc 会炸。

本次给新引入的那两个 record 加模块前缀改名，重新 codegen，并**把既有消费方的形状修回原状**。

## 决策 / 结论

1. **只改「后来者」，不动「既有语义」。** 最终 4 个名字互不相同：

   | 原名（文件:行） | 形状 | 处置 | 新名 |
   | --- | --- | --- | --- |
   | `DutyTaxModels.ScopeOption` (`:457`) | `(value, label)` | **保持不动** | `ScopeOption` |
   | `ReservationModels.StatusOption` (`:262`) | `(code, label, terminal)` | **保持不动** | `StatusOption` |
   | `WarehouseConfigModels.ScopeOption` (`:323`) | `(code, label)` | 改名 | `WarehouseConfigScopeOption` |
   | `WarehouseFacilityModels.StatusOption` (`:258`) | `(code, label)` | 改名 | `WarehouseFacilityStatusOption` |

2. **为什么只改两个。** 改「后来者」而不是四个全改，可以让并入前的既有 schema 名与形状
   **原样存活**：`ScopeOption={value,label}`、`StatusOption={code,label,terminal}` 与 `363dc00`
   逐字一致。若四个全改，会连带改掉十余个既有消费方与 `apps/web` 的 import 名，
   等于用一次修复制造一批新契约变更。

3. **顺带发现：`FacilityOptions.statuses` 的形状在并入前就是错的。**
   `363dc00` 的 `FacilityOptions.statuses` 指向 `StatusOption`，而那时的 `StatusOption`
   已经是预占那条（带 `terminal`）—— 也就是说这处撞车**早于 8.1.1.3**，设施模块一直拿到
   预占的 3 字段形状。改名后它第一次指向正确的
   `WarehouseFacilityStatusOption={code,label}`。这是本次的净收益，不是回归。

4. **冲突检测闸只覆盖跨服务，覆盖不到服务内。** `catalog-contracts.py` 的 `collisions()`
   逐服务读 spec 再横向比，而服务内的覆盖**发生在 springdoc 生成 spec 之前**，脚本根本看不到。
   所以这类损坏目前没有任何自动拦截。

## 关键 trade-off / 讨论

- **保留 `ScopeOption` / `StatusOption` 这两个「裸名」**：它们语义最泛，未来仍可能再撞。
  备选是四个全改成 `DutyTaxScopeOption` / `ReservationStatusOption`，自解释性更好，
  代价是要改十余个既有 schema 名与前端 import。选前者是因为本次是**修复**不是重构，
  契约 diff 越小越好；裸名的复发风险记在「后续待办」。
- **`Incoterm` / `WarehouseKind` 同名未改**：两个 enum 也是同简单类名且**枚举值不同**
  （7 值 vs 11 值；2 值 vs 5 值）。但它们**没有被任何 schema 引用**，springdoc 压根不产出它们，
  当前影响为零，按「只碰本次需要的文件」不顺手动它。留作后续待办。
- **重新 codegen 会整体重写 `contracts/warehouse.ts`**（merge 驱动器此前往里追加了模块块）。
  已核对：旧 670 个类型名一个没丢，新增 2 个，重复标识符归零。

## 涉及文件

后端（改名 + 调用点，5 个文件）

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseConfigModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseConfigService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseFacilityModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/WarehouseFacilityService.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/WarehouseConfigIntegrationTest.java`

契约（codegen 产物，**禁止手改**）

- `packages/api-contracts/src/contracts/warehouse.ts`
- `packages/api-contracts/src/warehouse.openapi.json`

前端：**无需改动**。`warehouse-config-workspace.tsx` / `warehouse-config-model.test.ts`
的 `ConfigOptions` 是推导类型、不按名 import；`inventory-reservation.tsx` import 的
`StatusOption` 属未改名的预占那条，名字不变。

## 验证

- `backend && ./mvnw -q compile` → EXIT=0
- `pnpm typecheck` → **EXIT=0，6 条报错全消**（修复前：warehouse.ts 4×TS2300、
  warehouse-config-model.test.ts TS2353、warehouse-config-workspace.tsx TS2339）
- `pnpm vitest run apps/web/src/components/warehouse apps/web/src/app/api/warehouse`
  → **13 文件 / 176 例全过**
- `backend && ./mvnw -pl services/warehouse -am test` → 见提交说明

## 后续待办

1. **给 codegen 补「服务内」重名检测**（最高优先）。现在 `collisions()` 只跨服务比，
   服务内覆盖在 spec 生成前就发生了，脚本看不到。可行做法：在
   `WarehouseConfigModels` 等 `*Models.java` 源文件上按简单类名扫一遍重名，
   写盘前非零退出。这是本次这类损坏唯一能自动化的拦截点。
2. **把 `Incoterm`（DutyTax 7 值 vs FirstMile 11 值）与 `WarehouseKind`（SellablePolicy 2 值 vs
   Facility 5 值）改名**。当前零影响（无 schema 引用），但一旦有端点直接返回该 enum 就会立刻损坏。
3. **评估是否把 `DutyTaxModels.ScopeOption` / `ReservationModels.StatusOption` 也补前缀**，
   消除裸名复发风险。属于契约改名，需单独排期。

## 关联

- `docs/backlog/2026-10/2026-10-10-contract-flat-overwrite-and-nav-gaps.md` —— 跨服务覆盖的
  P1 取证，本次是它的**服务内**续篇
- `docs/sessions/2026-10/2026-10-11-contract-collision-gate.md` —— 跨服务碰撞闸的落地记录
- `docs/sessions/2026-10/2026-10-11-contracts-split-per-service.md` —— 按服务分文件
- `tools/module-progress/merge-union.mjs` —— `TS_KEY` 缺 `m` 标志（并入驱动器，2026-10-13 已修）
