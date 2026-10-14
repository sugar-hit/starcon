# 3.9.1.1 重复 SKU 识别（阶段 D1–D2，L3 锚点 3.9.1）

- 车道：`feat/3-9-1-1`（worktree `.worktrees/3.9.1.1`），基线 `integration/module-dev` @ d9bcd6e
- 迁移版本：V36（预分配，未与并行车道撞号）
- 状态：已交付（台账第 179 行「已交付」，全局已交付 64 → 65）

## 决策摘要

**候选的粒度是「一对 SKU」，不是「一条检测线」。** 蓝图输出把「相似度」和「重叠维度」并列，
后者意味着同一对商品可能被多条线同时命中。所以 `sku_duplicate_candidate` 一行 = 一对 SKU
（`UNIQUE(tenant_id,scan_id,left_sku_id,right_sku_id)`），`overlap_dimension_count` 就是
「中了几条」；逐维度的得分与证据落在 `sku_duplicate_signal`。若按维度各存一行，
「重叠维度」这一栏就无处可查 —— 而它正是蓝图点名的输出。

**总体相似度取各维度得分的最大值，不做加权平均。** 条码撞了是 1.0000，
若被 0.9x 的标题分平均掉，会得到「条码已撞」看起来不如「只是标题像」严重 —— 与事实相反。
「四条全中」这件事由 `overlap_dimension_count` 表达，两个数各有各的职责。

**左右两侧按 uuid 字符串升序存放，并在 DB 层用 CHECK 钉死。** 否则同一对
(X,Y) 与 (Y,X) 能各插一行，候选数与决定数凭空翻倍。集成测试对全表逐候选断言这条。

**四条线的分组键都是各自判定的必要条件，所以分组只影响速度、不影响召回。**
BARCODE 按条码值分组（前提：归一化后相等）；CROSS_SPU 按「归一化标题+品牌+类目」复合键分组
（前提：三者全同）；SIMILAR_PRODUCT 按共享素材哈希分组（前提里就有「至少重合一张图」，
`similarProductSignal` 在没有共享哈希时直接返回 null，所以无损）；VARIAT_COMBINATION 在同 SPU 内
按共享的「轴=取值」分组（Jaccard>0 蕴含至少共享一个轴值）。集成测试
`分组判定不漏检_已知的重复都能被找到` 是这条不变量的回归防线。

**CROSS_SPU 的分组键与判定用同一套归一化（都走 `normalizeTitle`）。** 若分组键用一套、
判定用另一套，会出现「分组把本该命中的对分开了、判定却又认可它」的漏检 ——
而漏检在页面上表现为「已治理干净」，是这个模块最不能犯的错。

**影响引用数只算 catalog 自有的九类，逐项拆分而不是给一个总数。**
04 映射 / 07 库存 / 09 订单的引用在别的服务的库里，跨库读是模块边界纪律明令禁止的；
那部分留给 3.9.1.2 走各自授权 API。逐项拆分是因为「报价 3 条」与「素材 3 张」
的处理成本完全不同，运营看得到是哪几类在挡路。

**本模块只登记决定，不执行合并。** 蓝图把「确认合并/保留决定」写在本模块输出里、
把「执行合并」写在 3.9.1.2 职责里。这条边界落成三件事：决定登记成可查事实；
同时记下登记那一刻两个 SKU 的 `sku.version`（即 3.9.1.2 说的 `expected_version`）；
本模块**不改 sku / spu 任何一列**（集成测试逐列断言 version 与 status 未被改动）。

## 过程中被真实约束纠正的三个判断

1. **同条码多 SKU 不可能来自 `sku_identifier`。** V7 有 `UNIQUE(tenant_id,scheme,value)`，
   夹具第一次写两条同值标识表直接撞唯一键。真实来源是 `sku.barcode` 那个
   **没有唯一约束**的列（批量导入只写列、不同步标识表），以及「A 的列 vs B 的标识表」
   这种跨来源。集成测试因此改成「一侧走列、一侧走标识表」。
2. **同 SPU 建第二个 SKU 会撞 409，而且与本模块无关。** `CatalogService.saveSku`
   第一行是 `lock(spu, input.version())` —— `SkuInput.version` 传的是 **SPU 的 version**；
   没有规格轴属性时所有 SKU 的 `variant_signature` 都是空串，被 V1 的
   `UNIQUE(tenant_id,spu_id,variant_signature)` 挡住。夹具改为「建带规格轴的 SPU +
   每个 SKU 一个不同取值」。
3. **`media_asset` 是内容寻址的。** `UNIQUE(tenant_id,content_sha256)` 意味着
   「两个 SKU 用同一张图」在数据上就是**同一行素材被引用两次**，不是两个内容相同的素材行。
   夹具改为 `newAsset` + `attachMedia`。

另外修了两个自己写出来的真 bug：`jaccard` 用无 scale 的精确除法，
1/3 这类除不尽的比值会抛 `ArithmeticException`（相似度恰恰最常出现 1/3、2/3）；
`KEEP` 决定原本会把 `primary_version` 绑成 0 而不是 null。

## 关键 trade-off

- **扫描同步返回而不是异步受理。** 相似度要算的是「当前全量商品与 SKU 的关系」，
  单租户一次扫描毫秒到秒级；做成异步只会多一个「扫完了吗」的状态机。
  对照 V29 导入向导 —— 那个异步是因为要上传并逐行落文件，本质不同。
- **支持按类目/品牌收窄扫描，并显式标记 `scopeApplied`。** 大租户能分片扫，
  代价是会漏掉跨范围的重复，所以范围非空时概览顶部必须提示（`scopeWarning`）。
  前端把「没扫过」与「扫完 0 个候选」做成两个不同的空态，就是为了不让分片后的
  0 个候选被当成「已治理干净」。
- **相似度阈值默认 0.8000，越界显式 400 而不是静默夹取。** 这一页的结论完全取决于阈值，
  夹取会让「阈值生效了吗」这个问题无法回答。
- **`SIMILAR_PRODUCT` 要求标题近似且至少共用一张素材。** 只凭标题像，满地都是
  「同系列不同容量」「换壳版」；只凭图片像，白底通用主图会误判。

## 文件清单

后端

- `backend/services/catalog/src/main/resources/db/migration/V36__sku_duplicate_candidate.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/DuplicateModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/DuplicateRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/DuplicateDetectionService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/DuplicateDetectionController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/DuplicateRulesTest.java`（20 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/DuplicateDetectionIntegrationTest.java`（19 例）

前端

- `apps/web/src/components/catalog/duplicate-model.ts`
- `apps/web/src/components/catalog/duplicate-model.test.ts`（20 例）
- `apps/web/src/components/catalog/duplicate-workspace.tsx`
- `apps/web/src/app/products/duplicates/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（白名单插入 `duplicate-scans` / `duplicate-candidates` 完整顶层分支）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（+3 组断言）

共用（codegen 产物，禁手改）

- `packages/api-contracts/src/catalog.ts`（+12 个 `Duplicate*` DTO，纯新增）
- `docs/product/capability-blueprint/module-progress.md`（只改 3.9.1.1 一行的路由/状态/证据）

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，catalog **1086 例** 0 失败 0 错误
- `pnpm typecheck` → 3 successful
- `pnpm lint` → 3 successful（只有 3.6.1.1 车道那条既有 warning）
- `pnpm test` → **45 文件 / 772 例** 全通过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → Compiled successfully，
  `/products/duplicates` 7.84 kB 已进产物
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致

契约刷新顺序：`./mvnw test -Dtest=OpenApiContractTest`（全部服务，生成 backend/target/openapi）
→ `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
（直接用现存的 target/openapi 会把新字段退回旧字段）。

## 后续待办

- **3.9.1.2 SKU 合并**：读 `sku_duplicate_decision` 的 MERGE 决定 +
  `primary_version` / `secondary_version` 作为 `expected_version`，执行归并并迁移当前引用
  （映射 / 库存 / 价格）。原记录标记合并并停用而非物理删除，可回溯合并来源。
  04/07/06 的引用数需走各自授权 API 补齐 —— catalog 侧只能给到九类自有引用。
- **历史订单行冻结当时 SKU 快照，不随合并回溯改写**（实体目录 §3.6），
  合并只迁移「当前引用」；这一点已写进 V36 文件头与决定表的 `expected_version` 注释。
- `sku_duplicate_candidate.candidate_count` 是扫描那一刻的**上界**
  （候选行在 SKU 被硬删除时随之删除，停用不影响）；要当前值读候选表。
- 单次扫描 SKU 上限 20000（`MAX_SCAN_SKUS`），超过时报错并引导按类目/品牌分片，
  不截断 —— 一份「看起来扫完了其实只扫了前 20000 个」的清单比扫不出来糟糕得多。
