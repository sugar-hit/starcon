# 3.8.2.1 导出（阶段 D1–D2）

- 日期: 2026-10-07
- 车道: `3.8.2.1`（worktree `.worktrees/3.8.2.1`，分支 `feat/3-8-2-1`）
- 迁移: `V35__product_export.sql`
- 页面: `/products/export`

## 决策摘要

导出做成**多数据源注册式**，而不是「一张宽表 + 一个查询」。蓝图的上游写的是
「3.1.1.1 / 3.1.2.1 / 3.6.2 等列表页」——一个开集，所以扩展点必须在代码侧：

- `ExportDataSource` 接口自带字段白名单、排序白名单、筛选词表、count / stream 两条查询
- `ExportDataSources` 把容器里所有实现收成 `key → 数据源`
- **加一个数据源 = 新增一个 `@Component`，迁移 / 服务层 / 前端都不用改**

迁移只建**一张** `export_job`：蓝图那对输出（导出文件 + 导出记录）里，
「条件 / 字段 / 行数 / 时间 / 操作人」全在这一行上，拆成任务表 + 字段明细表
只会多出一个能不一致的口子。文件本体走 3.4.1.1 的 `MediaStorage` 端口，不进业务库。

## 关键 trade-off

**1. 条件用 jsonb 快照，不用自由 map，也不拆子表。**
快照让「这批数据是按当时那套条件、按哪几列、按什么顺序导出的」永远答得上来；
触发器禁止事后改写。子表会让「这次导出到底选了什么」变成一次 join。
自由 map 则生成不出可用的 OpenAPI（`Map<String,Object>` 落到 TS 上是空对象类型），
所以改成强类型 `ExportFilterInput` + 按数据源词表校验归属。

**2. PII 授权用「联系人」数据源落地，而不是留成一个开关。**
蓝图要求「含联系方式等敏感字段的导出需显式授权并留审计」，但 SPU 与 SKU 两张表里
**一个联系方式字段都没有**。只接这两个源，这条要求只能落成永远为假的布尔开关。
所以注册了第三个数据源 `CONTACT`（上游 3.6.3.1 地址与联系人，已交付），
把 EMAIL / PHONE / MOBILE 标成 `pii=true`。理由与金额并存：**权限回答「能不能看」，
授权回答「这次导出可不可以发生」，是两件事。**

**3. 不加「同一租户同类任务互斥」。**
那是导入的蓝图原话。导出是只读作业，多个人同时导不同筛选条件没有任何互相覆盖风险，
加互斥只会白白挡住合法的并发导出。

**4. 过期与作废都删文件。**
只把状态推进到 EXPIRED 而把文件留在磁盘上，等于「有效期过了但文件还在」——
对一份含联系方式的导出，那是一份没人认领的数据。所以 `sweepExpired` 与
`cancel` 都要真删。EXPIRED 保留 `storage_key`（否则过期后找不到文件做清理、
审计也答不出「导出去的是不是这一份」），由 CHECK 钉死「有文件 ⟺ 成功或已过期」。

**5. 台账 route 列留 `—`。**
蓝图给 L3 `3.8` 的锚点是 `/products/import`，`3.8.1.1` 的 route 列同样是 `—`。
页面落点 `/products/export` 写在证据列里，不擅自改 route 单元格（sync.mjs 会以蓝图为准）。

**6. 报价数据源留扩展点，不写占位实现。**
3.6.2.1 本轮未并入，且价格的写入所有者是定价服务。所以注册表里没有 QUOTE，
也没有 `TODO` + 抛异常，而是把「加一个 `@Component` 即可」这件事记进
`docs/backlog/2026-10/2026-10-07-quote-export-data-source-extension.md`。

## 测试过程中暴露并修掉的产品缺陷

这三个都是**实测出来的真 bug**，不是测试写法问题：

1. **执行器在租户事务之外读 `export_job`** → FORCE RLS 下取不到行，
   每次导出都 404 → FAILED。改为用 `access.run(actor, permission)` 包住快照读取。
2. **`sweepExpired` 只改状态不删文件** —— 见上面 trade-off 4。
3. **触发器禁止在取消时清空 `storage_key`**，导致作废已成功的导出直接被 DB 拒绝。
   改为「换键永远禁止；清空键只在 CANCELLED 时允许」，并在注释里写明
   `file_name` / `row_count` / `sha` 为什么不跟着清。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V35__product_export.sql`

**后端**

- `ExportModels.java`（对外契约 record）
- `ExportDataSource.java`（数据源接口 + ExportColumn / ExportSort）
- `SqlExportDataSource.java`（count 与分批 stream 的 SQL 通用实现）
- `ExportDataSources.java`（注册表）
- `SpuExportDataSource.java` / `SkuExportDataSource.java` / `ContactExportDataSource.java`
- `ExportRules.java`（纯校验：字段、排序、筛选归属、PII 授权）
- `ExportJobService.java`（受理 / 查询 / 下载 / 作废 / 惰性过期）
- `ExportJobExecutor.java`（异步执行）
- `ExportController.java`

**测试**

- `ExportIntegrationTest.java`（16 项，Testcontainers PG16 + 强制 RLS + 单连接池）
- `export-model.test.ts`（18 项）

**前端**

- `apps/web/src/app/products/export/page.tsx`
- `apps/web/src/components/catalog/export-model.ts`
- `apps/web/src/components/catalog/export-workspace.tsx`
- `apps/web/src/lib/api-proxy.ts`（只**追加** `export-*` 分支）

**契约 / 台账 / 文档**

- `packages/api-contracts/src/*`（codegen 产物，494 DTOs，只读）
- `docs/product/capability-blueprint/module-progress.md`（只改 3.8.2.1 一行的状态与证据）
- `docs/backlog/2026-10/2026-10-07-quote-export-data-source-extension.md`

## 验证

| 命令                                                         | 结果                                                      |
| ------------------------------------------------------------ | --------------------------------------------------------- |
| `./mvnw verify`（backend）                                   | BUILD SUCCESS，6 模块全过；catalog 992 tests / 0 failures |
| `-Dtest=ExportIntegrationTest`                               | Tests run: 16, Failures: 0, Errors: 0                     |
| `pnpm typecheck`                                             | 3/3 成功                                                  |
| `pnpm lint`                                                  | 3/3 成功                                                  |
| `pnpm test`                                                  | 43 files / 734 tests 全过                                 |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功，`/products/export` 6.58 kB                          |
| `node tools/module-progress/sync.mjs --check`                | ✓ 台账与蓝图一致                                          |

多租户三断言全部实跑：两租户互不可见、伪造 `X-Tenant-Id` 无效、单连接池反复交替不串租户；
另加**数据范围断言**：SELF 范围的用户对同一 SPU 导出 0 行，
同一条件下 ALL 范围导得出全部行（后者是必要的对照，否则「0 行」可能只是压根没数据）。

## 后续待办

- **3.6.2.1 报价并入后加 QUOTE 数据源**（见 backlog 条目）：只需一个 `@Component`
- **18.1.3.1 导入导出任务通道**并入后接管调度与产物投递；
  `export_job` 继续作为导出记录与 PII 授权审计的唯一事实来源（审计不挂在别人的通道上）
- **16.6.1 审计**落地后应读 `export_job`，而不是另建一套导出审计
- `EXPORT_DOWNLOAD_TTL_HOURS`（默认 24h）与 `MEDIA_STORAGE_ROOT` 走部署侧配置；
  `MediaStorage` 上生产应换对象存储实现类
