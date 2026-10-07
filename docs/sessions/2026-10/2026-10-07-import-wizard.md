# 3.8.1.1 导入向导（L3）

日期：2026-10-07 · 分支：`feat/3-8-1-1` · 迁移：`V29__import_wizard.sql`

## 决策摘要

导入向导按蓝图正文的**五个环节**实现（下载模板 → 上传文件 → 字段映射 → 预校验 → 执行）。
蓝图标题写「四步引导」而正文列了五个动作，少任何一个环节向导就有一段路走不通，
所以按正文实现，不按标题砍。

五类可导入对象：商品 SPU / SKU / 属性值 / 包装档案 / 条码。

## 关键 trade-off

### 报价（3.6.2.1）有意不做

价格的唯一写入所有者是定价服务。catalog 现在把报价列存成本地表，
等于凭空造出第二个价格事实来源：两份数字必然漂移，而漂移之后
「平台上架价到底是多少」没有任何一张表能作 answer。

所以 `import_job.target_entity` 的 CHECK **不含 `QUOTE`**，不建报价表也不 mock 一张。
等定价服务就位，报价导入作为独立实体加进 CHECK 与模板字典即可，本文件的其余结构不动。
前端与迁移文件头都显式写明这条边界，不静默省略。

### 校验报告（3.8.1.2）只做数据不做页面

本模块负责把预校验结果**结构化落库**（`import_row_error`，severity 区分
「必须修正」与「可跳过」）并提供查询端点。报告页与「带标注错误文件导出」归 3.8.1.2。

`severity` 是数据而不是界面文案，因为它直接决定该行落不落库 ——
这条分界可查、可统计，不依赖谁来读页面上的说明。

### 上传的文件本体不落库

上传时就把 CSV 解析掉，结果逐行落进 `import_job_row.cells`（jsonb，存原始文本）。
于是：执行阶段只读库、不依赖任何本地磁盘路径（换进程、重启都不丢队列）；
库里没有 bytea，任务表体积与行数同阶而不是与文件体积同阶。

存**原始表头**而不是已映射的 `field_key → 值`：字段映射是上传**之后**才有的一步，
存成已映射的形状就意味着改一次映射要重新上传文件，引导链会断在第三步。

### 互斥放在 DB 层而不是服务层

蓝图要求「同一租户同时只允许一个同类导入任务」。用**部分唯一索引**
`UNIQUE(tenant_id, target_entity) WHERE status IN (活跃态)`：

- 不用「先查再插」：两个并发事务会都读到「没有活跃任务」然后都插入；
- 不用前端禁用按钮：两个标签页同时点、或直接调 API，都拦不住。

服务层只负责把撞出来的 23505 翻译成 409 并点名当前在跑的文件名。
跨租户天然不互挡（索引第一列就是 tenant_id）。

### 只支持 CSV

仓库（`backend/pom.xml` 与各服务 pom）没有 Apache POI / EasyExcel / OpenCSV 任何依赖。
读 `.xlsx` 需要 OOXML 容器解析（zip + XML + 共享字符串表 + 日期序列号换算），
手写一套半可靠的解析器只会得到「能读自己写的文件、读不了 Excel 导出的文件」的结果。

所以 CSV 是手写 RFC 4180 词法（只依赖 JDK），并逐条处理电商导出文件的现实差异：
UTF-8 严格模式 + GBK 兜底、剥 BOM、CRLF/LF/CR 三种行尾、字段内换行与逗号、
`""` 字面引号、尾随逗号算列。**不支持 xlsx 这件事在页面上与本文件里都写明了。**

## 本车道修复的上一轮遗留缺陷（7 处）

上一轮留下的代码**从未真正跑绿过**（14 个用例红）。逐个读出来后修了：

1. **异步执行器漏绑 `app.actor`**（最严重）：`ImportJobExecutor` 只用
   `tenants.run(tenantId, …)` 开事务，而目标表 `spu` 被 V3 的 `stamp_record_owner`
   触发器与 `data_scope` 策略要求 `app.actor` / `app.permission` / `app.grants`，
   于是**每一行写入都被拒**（`ERROR: Authenticated actor required`），
   所有导入任务都落到 FAILED。改为携带 `AccessService.Access`（来自
   `require()` 的已认证成员关系解析结果）开事务。
2. **行分页用 offset**：判定完的行离开 `PENDING` 结果集，`offset += slice.size()`
   会在每批之后跳过整段数据（第一批 1..200，第二批从 401 开始），大批量静默漏数据。
   改为 keyset 游标 `row_no > ?`。
3. **`cells` / `values` 字段名不一致**：`rows()` 读 `r.get("values")`，
   而列名是 `cells` → 逐行结果恒为空。
4. **SKU 新增模式的跳过没计数**：`applySku` 在 CREATE 命中已存在编码时返回
   `skipped=false`（`applySpu` 返回 `true`），同一 CREATE 语义在两类对象上
   给出不同统计，页面虚报「成功导入 1 条」而实际一条都没写。
5. **条码存在性检查全表扫**：与类注释「只查本文件里出现过的编码」自相矛盾，
   改为按本文件的条码值分块查。
6. **缺模板增删改与任务取消端点**：状态机允许 `→ CANCELLED` 却无出口，
   卡住的任务会**永久占住互斥槽位**，该目标对象后续所有导入全被挡在门外。
7. **CSV 解析失败报 500**：`IllegalArgumentException` 逃逸成无信息量的服务端错误，
   翻译为 400（"表头第 3 列没有列名"才是运营该看到的）。

另修两个测试自身的问题：一个断言用了**不在 CSV 里的编码**（查不到恒真，验了个寂寞）；
一个断言断言了措辞而非语义。还有一个异步用例直接读计数而没等终态，
与 `requestPrevalidate` 已经把活扔进线程池这件事构成竞态。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V29__import_wizard.sql`

**后端**（`backend/services/catalog/src/main/java/com/starcon/catalog/`）
- `ImportWizardModels.java` `ImportWizardController.java`
- `ImportTemplateService.java`（模板字典 + 可复用映射配置）
- `ImportJobService.java`（上传 / 映射 / 触发 / 查询）
- `ImportJobExecutor.java`（异步预校验与执行）
- `ImportRules.java`（纯函数校验规则）`ImportCsvParser.java`（RFC 4180）

**测试**（`backend/services/catalog/src/test/java/com/starcon/catalog/`）
- `ImportWizardIntegrationTest.java`（19 例）`ImportCsvParserTest.java`（16 例）
- `ImportRulesTest.java`（24 例）

**前端**
- `apps/web/src/app/products/import/page.tsx`
- `apps/web/src/components/catalog/import-wizard-workspace.tsx`
- `apps/web/src/components/catalog/import-model.ts` + `import-model.test.ts`（25 例）
- `apps/web/src/lib/api-proxy.ts`（放行 `import-*` 三个前缀）
- `packages/api-contracts/src/catalog.ts`（codegen 产物，未手改）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 3.8.1.1 那一行）

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，catalog 778 例全绿
- `pnpm typecheck` → 3 个包全过
- `pnpm lint` → 全过（仅另一模块既有 warning）
- `pnpm test` → 38 文件 / 622 例全过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/products/import` 12.2 kB
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致

## 后续待办

- **报价导入**：等定价服务交付且有授权写入端点后，把 `QUOTE` 加进
  `import_job.target_entity` 的 CHECK、`ImportRules.Entity` 与模板字典。
- **Excel（.xlsx）**：若确需支持，先引入 Apache POI 再加解析路径，
  不要在现有手写解析器上打补丁。
- **3.8.1.2 校验报告**：读 `import_row_error` 即可，无需在本服务另建错误表。
- **契约的 nullable 偏差**：codegen 把 `Instant` 标成非空 `string`，
  而 Java 在该阶段未发生时返回 `null`（`prevalidatedAt` / `startedAt` / `finishedAt`）。
  页面因此不能直接用字符串方法操作这三个时刻。