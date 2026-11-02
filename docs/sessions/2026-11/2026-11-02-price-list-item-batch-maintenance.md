# 2026-11-02: 5.1.1.2 价目项批量维护（阶梯价 / 批量设价 / Excel 导入导出 / 同优先级歧义）

## 摘要

蓝图 05「定价与价目」→ 5.1「价目表」→ 5.1.1.2「价目项批量维护」落地。

车道分支 `feat/5-1-1-2`，迁移版本号由调度者预分配为 **V433**
（`backend/services/catalog/src/main/resources/db/migration/V433__price_list_item.sql`）。

蓝图原文：「**职责**：在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、
Excel 导入导出，保存前预览影响范围。**输入**：价目项录入/批量操作（固定值、按百分比、按类目统一）；
`price_list_item` 记录。**输出**：价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告。
**关系**：上游 = 5.1.1.1、3.1.2.1 SKU；下游 = 5.3 测算、5.4 调价。
**同优先级多条有效价目必须报错让用户选，不随机取**（实体目录 catalog 价格规则）。」

交付物：3 张新表、11 个端点（`/catalog/price-lists` 命名空间下）、`/pricing/lists` 的**价目项分段**、
40 个后端测试（实跑 Testcontainers PostgreSQL 16）+ 35 个前端测试文件 477 例。

## 决策 / 结论

### 1. 🔴 蓝图加粗句的落法：**先选档，再选优先级**（顺序反了会把阶梯价判成冲突）

「同优先级多条有效价目必须报错让用户选」这条规则有个陷阱：同一个 SKU 的多行**不一定是冲突** ——
那是**阶梯价**（数量门槛不同）。所以解析顺序必须是：

1. **先选档**：在 `min_quantity <= 数量` 的行里取 `min_quantity` 最大的一档；
2. **再选优先级**：档内取 `priority` 最小的那条（**数字越小越优先**，与 3.6.2.1 供应商报价同一约定，
   不在本仓另造一套「越大越优先」的反向语义）；
3. **最小的 priority 命中多条 ⇒ 歧义**：`selected` 恒为 null、抛 409，
   候选清单挂在 ProblemDetail 上（id / SKU / 单价 / 门槛 / 优先级 / 生效区间逐条带出来）。

⇒ 结构上**没有任何一条兜底路径会替它挑一条**（`PriceListItemRules.resolve` 在歧义时把
`selected` 置 null，`PriceListItemService.resolve` 直接抛异常）。

### 2. 🔴 价目项**没有**币种列 —— 「一价目一币种」在下游的结构性落法

V431（5.1.1.1）给本模块定了三条接口约定，本迁移逐条兑现：

- `price_list_item` **没有** `currency` 列 ⇒ 币种只能来自 `price_list.currency`
  ⇒ 价目项在结构上**无法跨币种**（硬约束不靠服务层记得检查）；
- `price_list_id` 指向 `price_list.id`（稳定主键）；
- 建 `AFTER INSERT/UPDATE/DELETE` 触发器调用 V431 预留的 `price_list_sync_item_count()`
  ⇒ 5.1.1.1 的 `price_list.item_count` 真的跟着价目项写入变化。

Excel 导入文件里**允许**带「币种」列（运营从别的表导出来的表头就带它），但那一列只用于**校验**：
与服务端这张表的币种不一致 ⇒ 该行判错、整批不落库。

### 3. 🔴 批量：预览与执行走**同一个计划函数** ⇒ 预览即执行

`plan(priceListId, header, request)` 同时供 `preview()` 与 `apply()` 使用，
所以预览里的「新增 N 条 / 覆盖 N 条 / 跳过 N 个 / 会造成 N 个冲突」就是执行的结果。

**槽位规则** = 一条价目项的身份是 `(SKU, 数量门槛, 生效起点)` —— 与库里唯一约束
`UNIQUE(tenant_id, price_list_id, sku_id, min_quantity, valid_from)` 逐字一致：

- 同槽位已存在 ⇒ **覆盖**（改单价与区间）；
- 不存在 ⇒ **新增**。

为什么不用「有重叠就覆盖」：那样会留下「旧行还开着、新行又插进来」的空洞，
而空洞期的价格是未定义的。槽位规则让批量设价**不会**莫名其妙改掉别的行。

### 4. 🔴 导入先报后改：任何一行有错 ⇒ 整批不落库

逐行校验（SKU 不存在 / 单价不是数字 / 单价或门槛为负 / **币种与价目表不一致** /
生效区间非法 / 文件内重复 / 生效起点格式认不出来）：

- **ERROR**（该行数据本身不合法）⇒ 整批 `REJECTED`，**什么都没写**；
- **WARNING**（合法但会与既有价目项同优先级且区间重叠）⇒ 落库，但报告里逐条写明会造成歧义。

批次行与明细行都落库（`price_list_import_batch` / `price_list_import_row`），
明细行存的就是**刚才校验过的那批值** ⇒ 落库时**不重新解析文件**
（重新解析就可能落库到与刚才校验过的不是同一批数据）。

### 5. 🔴 歧义的 handler 放在**本控制器**里，不动共享的 `ApiErrorHandler`

`ApiErrorHandler` 是多车道共用的文件，改它会在并入时撞车 ⇒ 用控制器级
`@ExceptionHandler`（Spring 里控制器级 handler 优先于 advice）。
同理，`packages/api-contracts` 与台账是多车道共用的，按类型名 / OpenAPI 路径 / 模块 ID 自动求并。

### 6. 🔴 api-proxy 白名单：锚点选在**数组末尾**

本批另外三条车道分别被指定了 catalog 中段 / crm 中段 / identity 中段三个锚点 ——
四条车道必须占四个相距万行的锚点，否则下轮并入时又会在同一个锚点撞车。

判别顺序：**字面量子路径**（conflicts / resolve / preview / apply / export / import）
排在 `{uuid}` 段**之前**；id 段用完整 UUID 形状 `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
（不是 `[0-9a-f-]+`）。**刻意不放行** `price-lists/lists/{uuid}/conflicts` ——
那是 **5.1.2.1**（本表的下游守门员）的形状，本模块不实现跨表冲突判定。

### 7. ⚠️ 改了 5.1.1.1 的 allowlist 测试（**必须改**，否则是一条真路由被钉成「不存在」）

5.1.1.1 的 `price-list-allowlist.test.ts` 原本断言 `price-lists/lists/{id}/items` 是 404
（理由：价目项是 5.1.1.2 的写入所有权，本模块不建那张表）。
**5.1.1.2 交付后这条前提不成立** —— 该路由现在真的存在。
照 11.1.1.2 售后单详情那次（aftersales-allowlist.test.ts）的既有先例，
把这条负向样本移除并在文件里写明原因；其余负向样本（`items/batch`、`lists/{id}/conflicts`、
非 UUID 段…）保持 404 断言不变。

## 关键 trade-off

| 取舍 | 选择 | 理由 |
| --- | --- | --- |
| 导入文件怎么传 | **base64 JSON**，不走 multipart | 浏览器同源代理只转发 JSON（`request.text()` + 固定 `Content-Type`），multipart 过不去 —— 15.x 认证材料上传已经踩过这个坑，照抄同一手法。代价是体积涨 33%。 |
| 时间列怎么存 | Excel 里写**带偏移的 ISO-8601 字符串**，不是日期单元格 | xlsx 的日期单元格存的是「本地时间 + 无时区」，同一个瞬间在不同机器上会读出不同偏移。价目项的生效区间是**跨时区对账**的事实。 |
| 导出格式 | **真 xlsx**（Apache POI 5.2.3，版本号钉在 catalog 的 pom 里） | 不手搓「假 xlsx」—— 下载下来打不开的文件比不提供这个格式更糟。代理对 xlsx content-type 已是二进制原样透传。 |
| 冲突要不要拦写入 | **不拦，只报** | 蓝图把「保存时检测冲突、冲突即拒绝」写成了 **5.1.2.1** 的职责（它是本表的下游守门员）。本模块的职责是**把冲突摆出来**（冲突清单 + 批量预览的冲突计数 + 解析时的 409 候选）。 |
| 分类 / 品牌怎么取 | `spu.category` / `spu.brand` 的**字符串精确匹配** | 蓝图的「按分类/品牌范围」没有指定口径；这两个字段是 catalog 既有主数据（类目树与品牌表另在，且跨服务读要走授权 API）。页面上明确提示「分类名取自 spu.category」。 |

## 文件清单

**迁移（catalog，V433）**
- `backend/services/catalog/src/main/resources/db/migration/V433__price_list_item.sql`
  —— `price_list_item` / `price_list_import_batch` / `price_list_import_row` 三张新表、
  零 ALTER 任何既有表、零外键（触发器 guard）、ENABLE + FORCE RLS、GRANT、索引。

**后端（catalog）**
- `PriceListItemModels.java`（输入 / 输出 / 词表）
- `PriceListItemRules.java`（纯规则：归一化 / 半开区间 / **选档 + 优先级** / 百分比换算）
- `PriceListItemService.java`（jOOQ 显式 SQL：CRUD / 批量计划 / 冲突 / 解析 / Excel 导入导出）
- `PriceListItemExcelService.java`（Apache POI 真读写真 xlsx）
- `PriceListItemController.java`（11 个端点 + 控制器级歧义 handler）
- `backend/services/catalog/pom.xml`（+ poi / poi-ooxml 5.2.3）

**测试（catalog）**
- `PriceListItemRulesTest.java`（15 例）
- `PriceListItemIntegrationTest.java`（20 例）
- `PriceListItemTenancyIntegrationTest.java`（5 例，三条隔离断言）

**前端（apps/web）**
- `apps/web/src/components/pricing/price-list-item-model.ts`（+ `.test.ts`）
- `apps/web/src/components/pricing/price-list-item-workspace.tsx`
- `apps/web/src/components/pricing/pricing-lists-page.tsx`（页签：价目表 / 价目项）
- `apps/web/src/app/pricing/lists/page.tsx`（**只改默认导出的那一行**）
- `apps/web/src/app/api/catalog/price-list-item-allowlist.test.ts`
- `apps/web/src/app/api/catalog/price-list-allowlist.test.ts`（**移除了已失效的负向样本**）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，锚点 = 数组末尾）

**契约 / 台账**
- `packages/api-contracts/src/catalog.openapi.json` + `src/contracts/catalog.ts`（codegen 重新生成，
  **纯新增 15 schema / 9 path，0 删除 0 改动**）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 5.1.1.2 |` 那行 + 摘要格的算术后果）

## 验证

```
L0 编译闸   cd backend && ./mvnw -q compile                       → EXIT=0
L1 模块闸   ./mvnw -pl services/catalog -am test -Dtest='PriceListItem*' ...
             → Tests run: 40, Failures: 0, Errors: 0   BUILD SUCCESS
               （PriceListItemRulesTest 15 + PriceListItemIntegrationTest 20
                 + PriceListItemTenancyIntegrationTest 5）
             5.1.1.1 回归（我的迁移加了一个触发器）→ Tests run: 37, 全绿
L0 类型闸   pnpm typecheck                                         → 3/3 successful
L0 格式闸   pnpm exec prettier --check <本车道改动文件>            → All matched files use Prettier code style
前端测试    pnpm vitest run apps/web/src/app/api/catalog apps/web/src/components/pricing
             → Test Files 35 passed (35) · Tests 477 passed (477)
全量白名单  pnpm vitest run apps/web/src/app/api                  → 293 文件 / 1649 例全绿
             pnpm vitest run apps/web/src/app/api/trade            → 71 文件 / 394 例全绿
```

**Testcontainers 容器**：三个测试类各自起一个 `postgres:16-alpine`（静态块里 `pg.start()`），
每个测试类结束在 `@AfterAll` 里 `pg.stop()` ⇒ **验证结束时容器已被 ryuk 回收**，
验证期间同时存活的 PG 容器数 ≤ 1（surefire 默认串行执行测试类）。

## 后续待办 / 已知缺口（如实记录）

- **5.1.2.1 优先级与冲突检测未交付**：本模块只管**同一张价目表内部**的歧义；
  跨价目表（同 SKU + 同店铺 + 同站点 + 同时段）的优先级与保存时拒写**不在本模块**。
- **不拦截写入冲突**（拦截是 5.1.2.1 的职责），只负责把冲突摆出来。
- **分类 / 品牌是自由文本字段**（`spu.category` / `spu.brand`），批量按分类/品牌命中的是
  **字符串精确匹配**；若将来接上类目树（`spu_category`）与品牌表，口径需要重新对齐。
- ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
  （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
- ⚠️ **台账摘要格的存量偏差**：集成头的 `module-progress.md` 摘要写着「已交付 453 / 未开发 12」，
  而实际行状态是 **455 / 10**（早于本车道的两条别的车道手改了行、没重算摘要）。
  本车道**只改自己那一行**（与 5.1.1.1 的先例一致：合并驱动对台账汇总块是**整行去重**，
  两侧都改过汇总行会留下重复汇总块 ⇒ 摘要格本车道一律不动），
  **也没有**跑 `sync.mjs` 全量重算（那会重排整份文件，违反「不要重排整表」）
  ⇒ 并入后请调度者统一跑一次 `node tools/module-progress/sync.mjs`。
