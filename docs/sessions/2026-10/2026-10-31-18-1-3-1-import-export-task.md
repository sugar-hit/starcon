# 18.1.3.1 导入导出任务（卷 18 集成开放与智能自动化 → L3 18.1.3 数据交换 → 第一格）

- **日期**：2026-10-31
- **车道**：18.1.3.1（分支 `feat/18-1-3-1`）
- **阶段**：D7
- **状态**：已交付

## 决策摘要

交付**统一的数据导入导出通道**（platform 服务，端口 8089）：任务创建（选数据域与范围）→ 异步执行 →
进度跟踪 → 结果下载（带有效期）→ 失败行报告（逐行逐列）→ 模板下载。

蓝图原文（卷 18 → 18.1.3 → 18.1.3.1）：
「职责：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、进度跟踪、结果下载、失败行报告、模板下载。
输入：导出/导入请求；文件上传。输出：任务记录 + 文件（带有效期）+ 错误报告。关系：上游 = 全部业务域；
下游 = 用户本地文件。**大文件一律异步**，并在下载链接上设置有效期，避免存储无限增长。」

**最关键的一条设计决定**是「上游 = 全部业务域」怎么落地 —— 答案见下面第 1 条 trade-off。

## 关键 trade-off

### 1. 🔴「上游 = 全部业务域」按**三条通道**落地，不是「一条 SQL join 七个服务的表」

根 `AGENTS.md` 的模块边界纪律写死：「跨 `backend/services/<name>/` 必须走授权 API / event，
禁止跨服务 SQL / FK / 共享视图」。所以「全部业务域」在本模块里被拆成三条通道，
每一格都在 `ImportExportDomains` 里写明「数据从哪来」与「没接通缺什么、归谁」：

| 通道 | 数据域 | 做法 |
| --- | --- | --- |
| ① **platform 自有表** | 应用与凭证 / 事件订阅 / 投递记录 / 调用埋点 | jOOQ **显式 SQL** 读自己的表 |
| ② **属主服务授权只读端点** | 商品与 SKU（catalog）/ 销售订单（trade）/ 库存头寸（warehouse）/ 平台商品（channel）/ 会计凭证（finance） | 跨服务 HTTP，**只转发调用方自己的 bearer**、不传租户号、**不抄副本** |
| ③ **属主服务文件型导入作业** | 商品域（catalog 的 multipart 导入作业） | 通道转发文件 + 轮询作业 + 把上游逐行错误回灌成本模块的失败行报告 |

**取舍**：② 的五个域各写一个**小端口**（`CatalogProductPort` / `TradeOrderPort` /
`WarehouseInventoryPort` / `ChannelListingCatalogPort` + 复用既有 `FinanceVoucherPort`），
而不是写一个「万能聚合端口」—— 后者会把五个上游的字段差异揉成一团，
而字段名写错在本仓**不会报错**（上游客户端关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`），
只会静默变成 null，让导出文件出现整列空白。

**没接通的格子一律 `available=false`**，并在 `capabilities()` 里写明缺什么、归谁
（照抄 platform 的 `UnavailableFinanceSoftwarePushPort` 的纪律）：
导入只对「platform 自己拥有导入格式的域」（事件订阅）与「属主服务有文件型作业的域」（商品域）接通；
订单 / 库存 / 平台商品 / 凭证 / 客户**只能导出不能导入**；
客户域的**导出**也未接通（crm 没有面向平台的客户档案列表授权端点）。
**绝不为让测试绿而写成假的 true。**

### 2. 导出与导入**共用一张任务表**

两条链（任务创建 → 异步执行 → 进度跟踪 → 结果下载 → 失败行报告）对两个方向**完全同构**，
方向只是 `direction` 字段。拆两张表会让状态机、有效期、作废这些逻辑写两遍，
而两遍实现必然漂移 —— 这正是 catalog 把 `export_job` / `import_job` 分开之后各写了一套状态机的原因。

代价：一张表上要同时表达两种生命周期（导出的「结果文件」与导入的「回执文件」），
所以文件角色用 `role` 区分（`RESULT` / `ERROR_REPORT` / `SOURCE` / `TEMPLATE`），
且 `CHECK (status <> 'SUCCEEDED' OR result_file_id IS NOT NULL)` ——
**成功却没有结果文件的任务在数据上是在骗人**，这条由数据库挡住。

### 3. 🔴 异步执行是**进程内执行器**，任务表里**不存凭据**

导出要经属主服务的授权端点读数据，而上游按**已认证成员关系**解析租户 ⇒ 需要调用方的会话。
若把执行放到外部调度器，就必须把 bearer 存进数据库（= 把 secret 落库），
或者改造成服务间凭据（= 新增一整套凭据体系）。

取舍：让**调用方的 bearer 只在内存里活到这次执行结束**，任务表里一个凭据都不存。
代价是**进程重启后卡住的任务不能自动重试**（没有凭据就读不到上游，重试只会再失败一次），
所以它们被如实标成「执行中断」并写明「请重新发起一次」，而不是静默重试或假装还在跑。

### 4. 文件**落磁盘不落库**

一次全量导出的业务数据可能是几十 MB，落进 PostgreSQL 的 TOAST 会把库撑大、
备份变慢、查询变慢，而且导出文件是**一次性产物**（到期即删），本来就不该长期躺在事实表里。
库里只留 `storage_key` + `content_sha256` + `byte_size`。
存储实现（`LocalImportExportStorage`）照抄 reporting 的 `ReportingExportStorage`：
**真写盘真读回**（不用内存 Map 替身 —— 那样永远验不出「文件确实不在了」），
且做**路径穿越防护**（键归一化后校验解析结果落在根目录内），键里带租户段。

### 5. 状态机与冻结字段**钉在数据库触发器上**

`CHECK` 在 PostgreSQL 里可以被 `NOT VALID` 绕过，触发器不会。所以：
合法状态迁移、范围快照 / 方向 / 数据域 / 操作人创建后不可改、「SUCCEEDED 必须有结果文件」、
「非终态不许有 finished_at」全部由 V415 的触发器 + CHECK 裁决 ——
应用层写错也绕不过去。

### 6. 浏览器侧上传走 **base64 JSON**，不走 multipart

同源代理（`apps/web/src/lib/api-proxy.ts`）只转发 JSON（`request.text()` + 固定
`Content-Type: application/json`），multipart 过不去 —— 走 multipart 的话请求体不是 multipart，
服务端解析不出文件部分。
⇒ 后端**同时**提供两个入口：`POST /platform/io/tasks/import` 的 JSON 变体（浏览器用，
base64 携带内容）与 multipart 变体（直连 API 的客户端用）。
代价：浏览器这条路受代理的请求体上限约束（200 KB），大文件批量导入要分片或直连。

### 7. 🔴 api-proxy.ts 只做**纯增量**

`apps/web/src/lib/api-proxy.ts` 是三条车道共用的**累积型**白名单。新条目是**独立的一条
`services[]` 元素**，插在 18.1.2.2 投递记录那条之后（数组靠前，与数组末尾相距 13000 行以上），
既有条目**一字未改、也不重排**。

首 token `io` 的零命中核对是**实测**的：用一次性脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）
**用 TypeScript 编译器解析**本文件里 `services` 变量的真实数组字面量，
逐条取出 `prefix` 与 `pattern`，按 `resolve()` 的同构口径（先按 prefix 闸门筛，
再测 `pattern.test(path.slice(prefix.length))`）对本模块的 13 条路径逐条判定：
**13/13 零命中**（既有 **272 条**元素里没有任何一条会吃掉本模块的路径）；
反向也核对了本条新正则对别人已交付形状（webhooks / deliveries / apps / text / finance-software /
call-monitor / reporting / catalog / trade）的**零命中** ⇒ 不会遮蔽别人已交付的路由。

## 涉及文件

**后端（platform 服务）**

- `backend/services/platform/src/main/resources/db/migration/V415__import_export_task.sql` — 四张表 + RLS + 触发器 + GRANT
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportModels.java` — 模型层
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportDomains.java` — **数据域目录（三条通道的取舍都在这里）**
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportService.java` — 服务层（能力声明 / 任务 / 文件 / 失败行 / 模板 / 执行）
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportController.java` — 端点
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportExecutor.java` — 进程内执行器
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportStorage.java` / `LocalImportExportStorage.java` — 文件存储端口与实现
- `backend/services/platform/src/main/java/com/starcon/platform/ImportExportCsv.java` — CSV 读写（UTF-8 BOM + RFC 4180）
- `backend/services/platform/src/main/java/com/starcon/platform/CatalogProductPort.java` / `TradeOrderPort.java` / `WarehouseInventoryPort.java` / `ChannelListingCatalogPort.java` — 授权只读端口 + 商品域导入委托
- `backend/services/platform/src/main/java/com/starcon/platform/PlatformUpstreamClient.java` — 追加 `postEmpty` / `postMultipart` / `getBytes` 三支
- `backend/services/identity/src/main/resources/db/migration/V415__platform_io_permission.sql` — 权限码登记

**后端测试**

- `backend/services/platform/src/test/java/com/starcon/platform/ImportExportIntegrationTest.java` — 17 例
- `backend/services/platform/src/test/java/com/starcon/platform/ImportExportTenancyIntegrationTest.java` — 7 例（三条隔离断言）

**前端**

- `apps/web/src/app/integrations/io-tasks/page.tsx` — 页面入口
- `apps/web/src/components/integrations/io-task-workspace.tsx` — 真实工作区（六段）
- `apps/web/src/components/integrations/io-task-model.ts` — 模型与口径
- `apps/web/src/components/integrations/io-task-model.test.ts` — 19 例
- `apps/web/src/app/api/platform/io-task-allowlist.test.ts` — 5 例（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` — **纯增量**：新增一条独立 `platform/` 元素

**契约与台账**

- `packages/api-contracts/src/contracts/platform.ts` / `packages/api-contracts/src/platform.openapi.json` — codegen 产物（**不手改**）
- `docs/product/capability-blueprint/module-progress.md` — 只改 18.1.3.1 这一行的「状态」「证据」两格

## 关键 trade-off / 讨论（其余）

- **失败行报告逐行逐列**而不是一个错误字符串：一次导入 300 行里 12 行有问题，
  运营要看到**哪 12 行、哪一列、为什么**，否则得把文件重新导一遍才知道哪里错。
  行号是**文件里的物理行号**（表头 = 1，与 catalog 的导入向导同一口径）。
- **一行可以有多处问题**：那一行的地址 scheme 不对 + 应用不存在 ⇒ 两条失败项，
  不是一条（合并成一条会丢掉其中一处原因）。
- **导入的订阅不带签名密钥**：密钥明文只能显示一次，批量导入无法交付给对接方 ——
  造一把谁也拿不到的密钥等于制造一个「看起来配置好了、实际上验签永远过不了」的订阅。
  页面与能力声明都写明「请在订阅列表里逐个生成并交给对方」。
- **超限就报错，不偷偷截断**：单次导出上限 20000 行，超过就让任务失败并说明该缩小区间 ——
  偷偷只导一部分会让导出的文件**看起来是完整的，而它不是**。

## 后续待办

- **18.1.3.2「数据迁移」**（同卷兄弟，蓝图写明「上游 = 18.1.3.1」）：本模块**不依赖**它、
  **也没有**顺手实现它 —— 历史迁移要试运行与回滚核对，是另一套语义。留给下一条车道。
- **导入域的覆盖面**：目前只有事件订阅（本模块自己落库）与商品域（委托 catalog）可导入。
  其余业务域要接通，需要**属主服务**各自提供**文件型导入作业端点**（含逐行错误），
  通道侧只加一个端口即可。归属写进了 `capabilities()`。
- **crm 的客户档案只读端点**：客户域导出要接通，需要 crm 补一个
  `/crm/customers` 形状的分页列表端点（通道侧只加一个端口）。
- **导出通道的授权**：当前通道用 `platform.io.*` 四个权限码统一授权，
  没有按**数据域**细分（catalog 用 `catalog.spu.read` 等各域自己的读权限）。
  跨域导出的「逐域鉴权」是更严的口径，尚未实现 —— 能力声明里已写明当前口径。

## 关联

- 蓝图：[`docs/product/capability-blueprint/18-integration-and-automation.md`](../product/capability-blueprint/18-integration-and-automation.md) 18.1.3.1（第 120–125 行）
- 台账：[`docs/product/capability-blueprint/module-progress.md`](../product/capability-blueprint/module-progress.md) 18.1.3.1
- 多租户：[`docs/architecture/multi-tenancy.md`](../../architecture/multi-tenancy.md) / [ADR-0002](../../decisions/0002-multi-tenancy-strategy.md)
- 相邻车道：18.1.2.1 事件订阅（`2026-10-31-18-1-2-2-delivery-record.md` 同月目录）、18.2.1.1 财务软件（`ImportExportDomains` 的取舍照抄它的 `UnavailableFinanceSoftwarePushPort` 范式）
