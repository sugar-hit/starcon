# 2026-10-07 · 8.1.1.1 仓库列表

## 结论摘要

8.1.1.1 已交付：迁移 `V69__warehouse_list_facilities.sql`（warehouse 服务）+ `V69__warehouse_facilities_page_permissions.sql`（identity 服务）、
4 个端点、工作区 `/warehouse/facilities`、集成测试 29 例 + 前端纯函数 23 例 + 代理白名单 2 例。
两个服务的 Flyway 版本都取调度器预分配的 **V69**（未自行找最大版本号）。

蓝图原文很短，但里面有一句是财务红线：

> 「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、时区、启用状态、
> 库存量与作业量，支持筛选与新增。」
> 「**仓库绑定法人是财务前提**：不同法人的库存货权与收入归属不同。」

**依赖自检结论**（10 分钟内查完，不是照蓝图「上游 =」假定的）：

| 蓝图要的东西       | 集成头实际状态                                                                   |
| ------------------ | -------------------------------------------------------------------------------- |
| `warehouse` 表     | ✅ V1 已有（code/name/country_code/timezone/default_legal_entity_id/status）     |
| 仓库类型           | ⚠️ V55 有 `warehouse.kind`，但只有四种，**蓝图点名的监管仓没有落点**             |
| 法人（上游 17）    | ✅ identity `V5__legal_entity.sql` + warehouse 侧引用登记 `legal_entity_ref`     |
| 库存量             | ✅ `inventory_balance` / `stock_bucket` / `platform_stock_observation`（V55）    |
| 作业量             | ✅ 本服务已有真实作业单据：`stocktake_task`、`transfer_order`、`inventory_entry` |
| 收货/出库/组装作业 | ❌ 8.2/8.3/8.4 未交付 → capabilities 显式报 `available=false`                    |

## 关键 trade-off

### 1. 不建「仓库列表投影表」

`warehouse` 表要展示的列**全都已经存在**，剩下的两个缺口都不是「加一列」能解决的：

- `kind` 取值不够（缺监管仓）
- 类型/法人/国家这三个筛选维度里，**法人和国家都不在任何现有索引的前缀上**

最省事的做法是建一张并行的列表投影表。**没这么做**：蓝图 7.1.1.1 已经把纪律写成字面
（「总览数字必须能下钻到明细且能核对上，总览只是聚合视图，不存独立数据」）。存一份列表数据，
就意味着「列表上的数」与「warehouse 表上的数」会有两个真相来源 —— 那是 7.1.1.1 明确拒绝的形状。
所以 V69 只做两件事：放宽 `kind` 的 CHECK + 补两条筛选索引。

### 2. 监管仓 = `BONDED`，而不是新开一列

蓝图五类 → 现有四类的映射逐条对齐，只新增一个值：

| 蓝图       | `warehouse.kind`           |
| ---------- | -------------------------- |
| 自营       | `LOCAL`（已有）            |
| 三方仓     | `THIRD_PARTY`（已有）      |
| 海外仓     | `OVERSEAS`（已有）         |
| 平台仓     | `PLATFORM_FBA`（已有）     |
| **监管仓** | **`BONDED`（本迁移新增）** |

为什么监管仓算**自有账本**而不是平台仓观测值：保税仓的货是本租户自有货，只是处于海关监管、
未完税出区。货权与自有仓一样。这一层**不需要新写触发器**，V55 的两道现成闸门自动兑现：

- `guard_platform_warehouse_no_ledger` 只拦 `PLATFORM_FBA` → `BONDED` 的账本余额放行
- `guard_observation_requires_platform_warehouse` 只放行 `PLATFORM_FBA` 写观测值
  → `BONDED` 想写观测值被 DB 拒绝，不会出现「自有账本 + 观测值」两个数

为什么放宽 CHECK 而不是加「是否保税」标记列：`kind` 已经是 7.1.3.1 多仓矩阵的**行分组轴**，
分组轴必须单值；再加一列会出现「既不是 OVERSEAS 又打了 BONDED 标记」的行。

**存量行不回填**：V55 的存量行全是 `LOCAL`（V55 文件头已写明理由）。系统无从判断某个仓是不是保税仓，
猜一个值写进去就是伪造事实；运营在自己的列表里改时才落真实类型。

### 3. 权限码另起 `warehouse.facilities`，不复用 `warehouse.inventory`

warehouse 服务既有端点（7.1.x / 7.2.x / 7.4.x / 7.5.x）全部共用 `warehouse.inventory.*` 一组码。
仓库档案**没有**跟着共用：

- 8.1.1.1 是**主数据写入**（新增仓库），7.1.x 是**库存聚合只读**。风险面完全不同 ——
  建一个仓会改变全公司库存归属的顶层结构，调错一个库存数字不会。
- 绑在同一组码上，「能改库存总览的人也能建仓」，反过来「被授权建仓的人顺带拿到全部库存读写」。

**只注册 `read` + `create`，刻意不注册 `update` / `delete`**：8.1.1.1 的职责范围是
「展示 + 筛选 + 新增」；改仓库属性属 8.1.1.2、行为参数属 8.1.1.3，两条车道落地时各自补自己的码。
先放行 update/delete 只会让人以为「仓库能改能删」，而界面上没有任何入口
（与 identity V53 数据字典「刻意不创建 delete 权限」同一纪律）。

### 4. 作业量只用**已交付**的作业单据，未交付的显式报 false

蓝图要「作业量」，但 8.2 收货 / 8.3 出库拣货 / 8.4 组装三条链都还没落库。

口径 = 本服务真实存在的作业单据：

| 维度       | 来源                                                                  | 已交付     |
| ---------- | --------------------------------------------------------------------- | ---------- |
| 盘点任务   | `stocktake_task` × `stocktake_sheet`（按仓范围 + 按该仓库位范围都算） | ✅ 7.4.2.1 |
| 仓间调拨   | `transfer_order`（调入 / 调出分别计数）                               | ✅ 7.5.1.1 |
| 库存变动   | `inventory_entry` 带符号分录条数                                      | ✅ 7.1.2.1 |
| 收货作业   | `goods_receipt` 表不存在                                              | ❌ 8.2     |
| 拣货与出库 | `outbound_order` / `pick_task` 表不存在                               | ❌ 8.3     |
| 组装作业   | `assembly_order` 表不存在                                             | ❌ 8.4     |

后三个在 `/warehouse/facilities/capabilities` 里 `available=false` + `ownerModule` + 具体原因，
前端据此禁用并说明。**没有**把它们算成 0 冒充「这个仓不作业」—— 0 条收货作业与
收货作业还没做，是两件完全不同的事。

调拨的调入/调出用**两个独立子查询**再相加，没有 join 出来：join 会让一张调拨单同时命中
`from_warehouse_id` 与 `to_warehouse_id` 而翻倍。

### 5. 平台仓观测值与自有账本在响应里是两个字段

`WarehouseRow.stock`（`inventory_balance` 聚合）与 `WarehouseRow.platformStock`
（`platform_stock_observation` 各来源最新一条）**永不相加**，后者在 `kind != PLATFORM_FBA` 时恒为 `null`。

`null` 而不是 `0`：后者会被读成「这个平台仓观测到 0 件」，前者是「这个来源不存在」。
观测取 `distinct on (sku_id, source) ... collected_at desc` 的最新一条 ——
`platform_stock_observation` 是 append-only 的历史序列（V55），直接求和会把 300+310 算成 610 这个不存在的数。

## 踩到的两个坑（都写进测试了）

### 1. jOOQ 的异常不是 jOOQ 的异常

服务里写的

```java
try { sql.execute(...); }
catch (org.jooq.exception.DataAccessException e) { if ("23505".equals(e.sqlState())) ... }
```

**catch 永远不执行。** Spring Boot 的 `DefaultExceptionTranslatorExecuteListener` 会把 jOOQ 异常
翻成 `org.springframework.dao.DuplicateKeyException`。表现是：唯一冲突直接抛一句带 SQL 的英文给界面。
正确写法是接 Spring 的类型。这也解释了本服务既有测试为什么统一断言
`org.springframework.dao.DataAccessException` 而不是 jOOQ 的。

### 2. `localTime` 是「现在几点」，不是采集时刻

第一版测试把 `localTime` 和「采集时刻在该时区的墙上时间」放在一起断言，跑不过 ——
因为 `localTime` 回答的是「**现在**这个仓库当地几点」，两个是不同的问题。
混为一谈会让这个测试只在一个固定时刻成立。已拆成两条断言：
写回的是同一个**瞬间**（`toInstant()` 相等 + 偏移相等），当地时间按**当下**时刻在该时区算。

## 时区为什么不落库偏移量

`warehouse.timezone` 存 IANA ID（`Asia/Shanghai` / `America/New_York`），行上再给一个服务端算好的
`localTime`。理由：偏移量不表达夏令时规则，`+08:00` 回答不了「纽约这周几号该切夏令时」。

- 新增时区填 `+08:00` → `ZoneId.of` 直接抛，界面上也提前拦（提示要填 IANA ID）
- 库里存在 JDK 不认识的时区 → `localTime` 返回 `null` 而不是静默退化成 UTC。
  退化成 UTC 会让运营看到一个看起来正常却错了 8 小时的时刻。
- 观测的 `collected_at` 按 `OffsetDateTime` 绑定，values 位置写 `?::timestamptz`；
  测试断言写回的是同一个瞬间而不是同一个墙面值（`Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s）。

## 国家为什么不内置国别表

仓库已有 `country_code`（V1 的 CHECK 只管长度是 2）。国家下拉**只列本租户真实在用的国家**：

- 内置一份国别表会和法务 / 海关口径漂移，而这份表没人维护
- 按已存在的仓库聚合出来的是真数据

新增时校验两位大写字母（`CN` / `cn` 不该是两个国家），不查外部词表 —— 并在
`/warehouse/facilities/options` 的 `countryNote` 里把这个取舍写给用户看。

## 跨车道约束回归（要求 #8）

本迁移对既有表的改动只有两类，**都不可能让别的车道违约**：

1. `DROP CONSTRAINT IF EXISTS warehouse_kind_check` + 用五值重建 —— **放宽** CHECK，
   已有四种取值一行都不受影响，也不影响任何 INSERT。
2. 两条**纯新增**索引。

静态判据逐条对过：`ADD COLUMN` —— 没有；`SET NOT NULL` —— 没有；触发器 —— 没有新增。
所以不需要回头重跑别的模块的集成测试来「被约束回归」。

`warehouse` 的 `tenant_id NOT NULL` + `ENABLE/FORCE RLS` + `isolation` 策略由 **V1** 建立，
V69 里刻意没有重复 `ALTER TABLE ... ENABLE ROW LEVEL SECURITY`（Flyway 幂等，但重复写会让人
误以为「RLS 是这个迁移加的」，归属写错比不写更糟）。测试里逐张断言 `relforcerowsecurity`。

identity 的 V69 是**纯 INSERT**（`page_resource` / `permission` / `role_permission`），
不碰任何表结构。

## 文件清单

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V69__warehouse_list_facilities.sql`
- `backend/services/identity/src/main/resources/db/migration/V69__warehouse_facilities_page_permissions.sql`

**后端**

- `WarehouseFacilityModels.java` — 传输模型、`WarehouseKind`（含 `platformObserved()`）、
  `WorkVolumeCapability`（`available` + `ownerModule` + `note`）
- `WarehouseFacilityService.java` — jOOQ 显式 SQL：列表（四个标量子查询聚合，不 join 展开）、
  合计、筛选白名单、排序白名单、真分页、下拉项、能力声明、新增校验
- `WarehouseFacilityController.java` — `/warehouse/facilities`（GET 列表 / POST 新增）、
  `/facilities/options`、`/facilities/capabilities`

**测试**

- `WarehouseFacilityIntegrationTest.java` 29 例（Testcontainers PG16、连接池=1、
  租户隔离三断言实跑、平台仓不相加、时区双向折算、五类词表、排序白名单、
  默认仓唯一、平台仓记自有账本被 DB 拒）
- `warehouse-facility-model.test.ts` 23 例
- `route.test.ts` 新增 2 例（四个调用形状放行 + 详情/改删形状**刻意不放行**）

**前端**

- `apps/web/src/app/warehouse/facilities/page.tsx` — 真实工作区，不是 `DomainPlaceholder`
- `apps/web/src/components/warehouse/warehouse-facility-model.ts` / `.test.ts`
- `apps/web/src/components/warehouse/warehouse-facility-workspace.tsx`
- `apps/web/src/components/warehouse/api.ts`

**共享文件（只加自己的，不动别人的分支）**

- `apps/web/src/lib/api-proxy.ts` — warehouse pattern 追加 `facilities(?:\/(?:options|capabilities))?`
- `packages/ui/src/lib/task-pages.ts` — 追加 `/warehouse/facilities` 一条任务页

## 导航：为什么不改 nav-config.ts

`SYSTEMS[].items[].children` 在 `nav-config.ts` 里是**从 `IMPLEMENTED_TASK_PAGES` 派生**的：

```js
children: IMPLEMENTED_TASK_PAGES.filter(
  (page) => page.domain === item.href.slice(1) && !isAccountDomain(page.domain)),
```

所以只在 `TASK_PAGES` 登记一条 `domain: 'warehouse'` + `implemented: true` 的任务页，
`NAV_DESTINATIONS` 自动多出对应条目 —— `nav-routes.test.ts` 的
「已实现任务页与菜单条目一一对应」不变量自动成立，**不需要碰共享的导航常量文件**。

页面路由取蓝图 08 的 L3 锚点 `/warehouse/facilities`，与台账「L3 锚点路由」列一致。
刻意**没有**替换 `/warehouse`（L3「仓库与库位」伞页，仍是 `DomainPlaceholder`）——
那是 8.1.2.x 库位树那条车道的范围，两条车道并行时各改各的才不会撞。

## 后续待办（不属于本车道）

- **8.1.1.2 仓库详情**：行上的 `id` / 法人 / 时区 / 库存分布都是为它准备的，
  但端点与页面不存在，所以 `WarehouseFacilityPage.detailUnavailable = true`、
  界面上没有进详情按钮、api-proxy 也**刻意不放行** `facilities/{id}`。
- **8.1.1.3 仓库配置**：`update` / `delete` 权限码留给它，
  identity 迁移里追加 `permission` 即可（表结构不动）。
- **8.2 / 8.3 / 8.4 落地后**：往 `workCapabilities()` 各加一条 `available=true`，
  并在 `WorkVolume` 上加对应计数 —— 表与列都不必动。
- `legal_entity_ref` 与 identity 的法人主数据是两条腿（跨服务引用登记），
  目前靠人工同步登记。两条腿的对账属 12 财务 / 17 法人的范畴，本车道不越界。
