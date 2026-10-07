# 8.1.1.2 仓库详情（车道 feat/8-1-1-2）

- 时间：2026-10-07 21:54 → 22:2x（Asia/Shanghai）
- 分支：`feat/8-1-1-2`（基线 integration/module-dev `8dbf182`）
- 结果：**已交付**，四件套齐全（迁移 / 端点 / 页面 / 测试）

## 决策摘要

1. **不建新表**。仓库详情是纯聚合视图，8.1.1.1 的结论（「列表不存独立数据」）对详情同样成立。
   唯一的 DDL 是给 `warehouse` 补**地址五列**——蓝图 8.1.1.2 职责第一条就是「基础信息与**地址**」，
   而 V1 建表时只有 `country_code`。没有给地址单开一张 `warehouse_address`：仓库与地址是 1:1，
   单开表会让「仓库的地址」出现两个真相来源，而详情页是最常读的一页。
2. **补 `update` 权限码，不补 `delete`**。8.1.1.1 的 identity V69 文件头与仓库服务的类注释都点名
   「改仓库属性属 8.1.1.2」，本模块兑现这个交接。`delete` 不注册：仓库不是可物理删除的主数据，
   停用与归档才是出口，且本服务没有 DELETE 端点（identity V53「无端点的权限码不注册」）。
3. **`detailUnavailable` 交接**。8.1.1.1 把它写死 `true` 让前端禁用详情入口；本模块交付时翻成
   `false` 并在列表行加详情链接。不翻的话控制塔只能靠手敲 URL 到达 —— 这是它自己文件头设计的开关。
4. **「未交付」与「有但是 0」严格区分**。没有数据表的维度一律 `available=false` + `ownerModule`
   + `note` + `href=null`，而不是给 0 或空数组。

## 上游依赖实查（与调度简报有出入，按实查为准）

| 简报说法 | 实查结论 | 影响 |
| --- | --- | --- |
| 「8.1.2.1 库位表不存在，库位结构要报未接入」 | **不成立**：`warehouse_location` 由 V2 建立，带 `type`/`status`/`pickable` | 库位结构给**真实数据**（数量、类型分布、可拣数、按类型的在手量 + 库位清单真分页）。`available=false` 的只剩**层级**（无 `parent_id`、无容量承重列，归 8.1.2.1）与自动分配（8.1.2.2） |
| 收货未交付、上架未交付 | 收货**已交付**（8.2.1.1，V70 `goods_receipt`）；未交付的是 8.2.2.1 上架任务（无表） | 收货作业是真数（单数 + 累计实收量分列） |
| 作业人员与设备：表不存在 | **部分成立**：`warehouse_work_task`（V74 / 8.5.1.1）带 `warehouse_id` + `operator_user_id` | 拆成三个子维度分别表态：任务/派工**真数**；花名册排班未接入（8.1.1.3）；设备未接入 —— `print_device`（V73）**没有 `warehouse_id`**，设备是租户级的，按仓统计在数据层就无从谈起 |

## 关键 trade-off

- **聚合一律用标量子查询而不是 join 展开**。库位/批次维度 join 会成笛卡尔积，四个 count 与三个 sum
  会互相放大。沿用 8.1.1.1 的写法。
- **库位切面调用同一段 SQL 两次**（库存分布、库位结构各一次）而不是互相传值：两处返回同一批行，
  「库存分布说 42、库位结构说 41」这种漂移不可能发生。
- **库位清单独立成端点**而不是塞进全景：它是控制塔里唯一会增长的列表（上百上千），
  塞进全景会让首屏变慢。
- **维护表单刻意不含编码/仓库类型/货权法人/默认仓标记**：分别是仓库标识、记账口径（V55 触发器按它裁决）、
  财务前提（蓝图 8.1.1.1 红线）、部分唯一索引联动。逐条理由写在模型注释里，不是没做完。

## 顺带修掉的一个真 bug

`ZoneId.of("+08:00")` **不抛异常** —— 它返回 `ZoneOffset`。所以「用 ZoneId.of 拒绝偏移量时区」
这条既有注释是不成立的，偏移量能一路存进库，而偏移量不表达夏令时规则。本模块在维护路径上
**显式拒绝**偏移量，并把 `DateTimeException` 翻成 `IllegalArgumentException`
（前者既不是 `IllegalArgumentException` 也不是 `DateTimeParseException`，会冒到 `ApiErrorHandler`
之外变成 500 —— 用户输错时区得到服务端错误）。
**注意：8.1.1.1 的 `create()` 路径仍有同一个缺口**，本车道没有改它（不碰别的模块的代码），
留给后续车道或单独的修复提交。

## 文件清单

**迁移**
- `backend/services/warehouse/src/main/resources/db/migration/V77__warehouse_detail_address.sql`（地址五列；不建表）
- `backend/services/identity/src/main/resources/db/migration/V77__warehouse_facilities_update_permission.sql`

**后端**（`backend/services/warehouse/src/main/java/com/starcon/warehouse/`）
- `WarehouseDetailModels.java`、`WarehouseDetailService.java`、`WarehouseDetailController.java`
- 交接改动：`WarehouseFacilityService.java`（`detailUnavailable` 一个字面量）

**测试**
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/WarehouseDetailIntegrationTest.java`（31 例）
- 交接改动：`WarehouseFacilityIntegrationTest.java`（该字面量的断言 true→false）

**前端**
- `apps/web/src/app/warehouse/facilities/[id]/page.tsx`
- `apps/web/src/components/warehouse/warehouse-detail-model.ts` / `-model.test.ts` / `warehouse-detail-workspace.tsx`
- 交接改动：`warehouse-facility-workspace.tsx`（列表行加详情链接 + 表头）
- `apps/web/src/lib/api-proxy.ts`（白名单**求并**）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（反向拒绝清单更新）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/{catalog,warehouse}.ts`、`*.openapi.json`

**台账**：`docs/product/capability-blueprint/module-progress.md` 第 286 行（只有 8.1.1.2 自己那行）

## 验证方式（真实结果）

```
cd backend && ./mvnw verify
  BUILD SUCCESS
  identity 183 / catalog 1205 / channel 66 / warehouse 664，Failures: 0, Errors: 0

python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
  Generated 1030 DTOs and two OpenAPI snapshots

pnpm typecheck        → Tasks: 3 successful
pnpm lint             → Tasks: 3 successful
pnpm test             → Test Files 78 passed / Tests 1372 passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
  exit 0，路由表含 ƒ /warehouse/facilities/[id]  8.65 kB
node tools/module-progress/sync.mjs --check
  ✓ 台账与蓝图一致
```

## 跨车道回归判断（规则 8 的静态判据）

V77 对既有表只做 `ADD COLUMN`（nullable、无 DEFAULT）与两个 `IS NULL OR ...` 短路的新 CHECK：

- `ADD COLUMN` 后看 DEFAULT → **没有 DEFAULT**，且 nullable，故是元数据操作，不重写表。
- `SET NOT NULL` → **没有**，故不命中「缺 BEFORE INSERT 触发器兜底」那条判据。
- 没有新增触发器、没有收紧任何既有行的取值。
- 仓库里所有 `insert into warehouse(...)` 都是**显式列名**（已全量核对），不会被列数变化打到。

因此不存在让别的车道违约的形状。实证上也确实没打到：全量 `verify` 里既有模块
（8.1.1.1 的 29 例、7.1.1.1、7.4.2.2、7.1.1.x 等 664 例 warehouse 测试）全绿。

## 后续待办

- **8.1.1.1 `create()` 的偏移量时区缺口**（上面「顺带修掉的真 bug」一节）未修，属 8.1.1.1 的代码。
- 8.1.1.3 仓库配置落地后，本模块的三条备货策略要跟着翻成 `available=true`。
- 8.1.2.1 库位树落地后，给 `warehouse_location` 加 `parent_id` 与容量承重列，入口清单里的
  `LOCATION_TREE` 才能翻成可用。
- 设备与仓库的绑定关系需要一个真实落点（`print_device` 加 `warehouse_id` 还是中间表），
  这是产品决策，不是实现细节。