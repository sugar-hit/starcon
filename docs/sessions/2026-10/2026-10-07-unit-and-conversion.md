# 3.1.2.4 单位与换算

## 决策摘要

交付卷 03 商品主数据系统 3.1.2.4「单位与换算」，分支 `feat/3-1-2-4-unit-conversion`，
基线 `feat/3-1-2-3-sku-code-barcode`（tip `205aebc`）。

四条核心决策：

1. **复用 V6 的 `sku_unit_conversion`，不重建换算表。** V6 已经建好了换算表、
   `quantity_per_base numeric(19,6)`、生效区间列，以及 `validate_unit_conversion_window`
   触发器（区间不重叠）。V6 的头注释点名「3.1.2.4 在 sku_unit_conversion 之上补单位字典
   （3.3.3）与量纲校验」。重建一张换算表会让同一个事实有两个来源，正是 V7 头部反对的形状。

2. **基础单位不落换算表。** 基础单位是 `sku.base_unit`（V1 的列），换算表只放**其他**单位
   相对基础单位的比例。再插一条 `role='BASE'`、比例=1 的行会让基础单位有两个来源。
   单据直接用基础单位时比例恒为 1，不占换算行（快照表里用 `role='BASE'` 表达，此时
   `source_conversion_id` 为空，DB CHECK 保证这一致性）。

3. **快照靠两层 DB 约束兑现，不靠应用自觉。**
   - `refuse_snapshot_mutation` 触发器：BEFORE UPDATE OR DELETE 一律拒绝（ERRCODE 42501）。
   - GRANT 只给 `SELECT,INSERT`，**不发 UPDATE/DELETE**。
   两层独立：权限层挡住运行时角色，触发器挡住迁移账号/超级用户直连。集成测试两层都断言。

4. **查不到生效版本就报错，绝不回退到"最近的一版"。** 按生效日期取不到版本时直接 400，
   错误信息说明"请先配置换算版本并让生效区间覆盖这一天"。静默回退会让一张按过期比例生成的
   采购单看起来完全正常——这是本模块最贵的一类错误。

## 关键 trade-off

- **换算表不建指向字典的外键。** V6 之前写入的换算行（导入/历史遗留）`unit_code` 可能不在
  字典里，加外键会让 3.1.2.2 的既有测试与真实历史数据直接写不进去。字典的强约束放在**本模块
  的写入口**（`addConversion` 要求单位已登记且启用），DB 层的 `guard_unit_conversion_dimension`
  只对两侧都已登记的行生效。遗留行仍可读，并在面板/界面上标记为「未登记」。取舍理由：宁可让
  遗留行带着"未登记"继续可读，也不让迁移把既有事实打碎。

- **基础单位也必须已登记才能建换算（显式拒绝，不放行）。** `sku.base_unit` 是 V1 的自由文本列，
  3.1.2.2 能写入字典里没有的字符串；查不到基础单位量纲时无从判量纲。这是实现期发现并修掉的
  真实漏洞：初版对"基础单位未登记"是放行的，等于让「件 → 千克」从后门进来。

- **快照按单据业务时间取比例，不是按"今天"。** 初版 `freeze` 传 `asOf=null`（=今天），
  补录一张 2024-06-01 的采购单会用今天的比例去算去年的账。改为 `occurredAt` 的日期。
  测试 `frozenSnapshotSurvivesLaterRatioChange` 覆盖：改比例后重算历史日期仍命中旧版。

- **换算区间重叠的报错要在插入前查冲突区间。** 触发器一旦抛错，PostgreSQL 把整个事务标记为
  aborted，此时再查库只会拿到 `current transaction is aborted`。改为插入前先 `existingWindows()`
  查好，异常时直接拼进 409 消息。

- **快照对外 id 取第一行真实写入的行 id。** 初版凭空造一个 `Ids.next()` 返回，等于给调用方一个
  指向空气的句柄（拿它回查必然查不到）。改为第一行的真实行 id。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V8__unit_conversion.sql`

**后端**
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitRules.java`（新增，纯规则）
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitConversionService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitConversionController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（改：追加 3.1.2.4 DTO）

**测试**
- `backend/services/catalog/src/test/java/com/starcon/catalog/UnitRulesTest.java`（新增，12 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/UnitConversionIntegrationTest.java`（新增，22 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`

**前端**
- `apps/web/src/components/catalog/unit-conversion-model.ts`（新增）
- `apps/web/src/components/catalog/unit-conversion-model.test.ts`（新增，24 例）
- `apps/web/src/components/catalog/unit-conversion-workspace.tsx`（新增）
- `apps/web/src/app/products/sku/units/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（改：catalog allowlist 放行 `unit-conversion`）
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（改：列表页加入口链接，最小改动）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 3.1.2.4 一行；其余 5 行是
  sync.mjs 自动重算的计数器 36→37）

## 端点

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/catalog/unit-conversion/units` | `catalog.sku.read` |
| POST | `/catalog/unit-conversion/units` | `catalog.sku.update` |
| PUT | `/catalog/unit-conversion/units/{id}` | `catalog.sku.update` |
| DELETE | `/catalog/unit-conversion/units/{id}?version=` | `catalog.sku.update` |
| GET | `/catalog/unit-conversion/overview` | `catalog.sku.read` |
| GET | `/catalog/unit-conversion/skus/{id}` | `catalog.sku.read` |
| POST | `/catalog/unit-conversion/skus/{id}/conversions` | `catalog.sku.update` |
| POST | `/catalog/unit-conversion/convert` | `catalog.sku.read`（纯计算） |
| POST | `/catalog/unit-conversion/snapshots` | `catalog.sku.update` |
| GET | `/catalog/unit-conversion/snapshots?documentRef=&documentType=` | `catalog.sku.read` |

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS（catalog 模块 66 例 + 其他服务全绿）
- `pnpm typecheck` → 3 packages successful
- `pnpm lint` → No ESLint warnings or errors
- `pnpm test` → 18 files / 160 tests passed
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/products/sku/units` 9.12 kB
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- 代理放行实测（临时探针跑真实 route handler 后已删除）：
  `catalog/unit-conversion/{units,overview,skus/{id},skus/{id}/conversions,convert,snapshots}` 全 200

## 后续待办

1. **`apps/web/src/lib/api-proxy.ts` 的 catalog allowlist 缺 `sku-code`（3.1.2.3 的问题，本次未修）。**
   已用真实 route handler（`apps/web/src/app/api/catalog/[...path]/route.ts`）实测确认：
   `catalog/sku-code/overview`、`/rules`、`/skus/{id}`、`/batch` 全部返回 **404「接口不存在」**，
   而同一次探测里 `catalog/unit-conversion/*` 与 `catalog/skus/export` 均为 200。
   后果是 3.1.2.3 的编码与条码工作区在浏览器里调后端必然 404。
   这属于已交付模块的账，本次只在报告里提出，未擅自修改。补一条
   `sku-code(?:\/(?:rules|overview|skus\/[0-9a-f-]+(?:\/(?:code|identifiers(?:\/[0-9a-f-]+)?))?|batch|batches\/[0-9a-f-]+|identifiers\/import))?`
   即可。

2. **3.3.1.1 商品分类的阻塞问题**（无 `product_category` 表、`CatalogService.table()` 白名单只有
   research/compliance/assets、api-proxy 未放行、前端无页面）——按调度要求未动，另行处理。

3. **3.3.3.1 计量单位**仍是未开发状态。本模块的 `unit_of_measure` 已覆盖它要求的
   「编码 / 名称 / 量纲 / 数量精度 / 可用场景」字典部分；3.3.3.1 若要补「单位组内的通用换算系数」
   （不依赖 SKU 的组内换算，如 1 吨 = 1000 千克），应在 `unit_of_measure` 之上另建组表，
   不要往 `sku_unit_conversion` 里塞——那张表是 SKU 维度的、带生效区间的。
