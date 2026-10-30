# batch-115 · 13.6.1.2 看板搭建 + 13.6.2.1 导出与订阅

> 批次号 115 是 round-130（09:15）明确**预留**给本批的号（「batch-115 号未占用（本轮零交付），留给持闸的 08:00 tick 写其本批说明」）。
> 08:00 tick 在并入本批两条车道后、L2 判红、会话崩掉之前，没来得及写这份说明 —— 本轮补上。
>
> | 项                 | 值                                                            |
> | ------------------ | ------------------------------------------------------------- |
> | 批次               | batch-115                                                     |
> | 模块               | 13.6.1.2 看板搭建 / 13.6.2.1 导出与订阅                        |
> | 落地服务           | reporting（卷 13「利润与经营分析」唯一归属服务）              |
> | 迁移               | `V409__board_builder.sql` / `V410__export_subscription.sql`    |
> | 并入集成头的提交  | `8607cd6a merge(13.6.1.2): 看板搭建` / `92181299 merge(13.6.2.1): 导出与订阅` |
> | 本批 L2 批次闸     | 🔴 **红**（红点与处置见文末「L2 批次闸状态」）                |
> | 台账               | 13.6.1.2 / 13.6.2.1 两行状态「已交付」+ 证据格                |

---

## 模块 13.6.1.2 看板搭建

**这个功能做什么**：业务人员**自助搭建经营看板** —— 拖拽组件、挑指标与筛选条件、排布栅格布局、保存共享、设定默认视图与默认时间窗。
蓝图关系句把边界写死了：**「看板需权限过滤，不同角色看到的组件不同，不能一个看板通吃。」**
所以本模块的全部设计都为这一句话服务 —— 权限过滤**不是**前端把卡片藏起来，而是**库层 + 服务层**两道。

上游是 13.6.1.1（受控语义层自助），下游是 1 工作台（可置为个人看板 —— 属卷 01，本模块不另起一套工作台）。

### 技术落地

- **迁移**：`backend/services/reporting/src/main/resources/db/migration/V409__board_builder.sql`
  —— **五张新表 + 一个视图，纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  - `reporting_board`（看板：`board_code` 租户内唯一 / `display_name` / `owner_user_id`（**来自已认证会话**，不在入参里）/ `visibility` PRIVATE 与 TEAM 两档 / `default_filters` jsonb / `default_window_from` 与 `default_window_to`）
  - `reporting_board_component`（组件：`metric_code`（CHECK 钉死全大写下划线形状）/ `dimension_code`（CHECK 钉死只有 5 个白名单码 SCOPE_TYPE / LEGAL_ENTITY / SHOP / BUCKET_DATE / CURRENCY）/ `chart_type` TABLE BAR LINE PIE 四档 / `layout` jsonb / `filter` jsonb / `required_permission`（谁才看得懂这个组件）/ `sort_order`）
  - `reporting_board_share`（按角色共享：`role_id` **或** `permission_code`（CHECK 钉死二选一）+ `scope` ROLE 与 TENANT 两档 + `revoked_at`）
  - `reporting_board_template` + `reporting_board_template_component`（模板与模板组件，与真组件**同构** —— 刻意不用 jsonb 存一坨，否则指标码与维度码会绕过触发器与 CHECK）
  - 视图 `reporting_board_component_visible`（`WITH (security_invoker = true)` ⇒ 基表 FORCE RLS 照常生效）
  - 五表全部 `tenant_id uuid NOT NULL` + **ENABLE 与 FORCE** RLS isolation（USING 与 WITH CHECK 同时钉死）+ `CREATE POLICY isolation` + GRANT `reporting_app`/`starcon_app` + `REVOKE ALL ON flyway_schema_history FROM reporting_app`（与 V376 / V408 完全同构）
  - **三条 DB 层不变量**（不是注释）：① 触发器 `guard_reporting_board_component_known` 拒绝引用**未在 `reporting_metric_definition` 登记**的指标码（与 V408 的 `guard_reporting_custom_report_measure_known` 同一条不变量，模板组件走同一道）；② 栅格布局形状由 IMMUTABLE 函数 `reporting_board_layout_ok` 钉死（位置非负、尺寸为正、`x + w ≤ 12`、`y + h ≤ 64`）—— **非法布局写不进库**；③ 「不同角色看到的组件不同」由库层视图按 `required_permission` 过滤。

- **端点**：**22 条**挂 `/reporting/board`（`ReportingBoardController.java`，jOOQ 显式 SQL，无 JPA）——
  GET `/capabilities`（能力声明）/ GET `/metrics`（可用的语义层指标清单，按当前租户过滤）/ GET `/dimensions`（维度白名单，与 13.6.1.1 完全一致）/ GET `/board`（我的看板：能打开的看板 + 各自看得见的组件数）/ GET `/boards`（清单：**真分页 + 真搜索 + 真筛选**）/ POST `/boards`（新建）/ GET·PUT·DELETE `/boards/{boardId}` / POST `/boards/{boardId}/components`（加组件）/ PUT·DELETE `/boards/{boardId}/components/{componentId}`（改删组件，含拖拽后的位置与尺寸）/ PUT `/boards/{boardId}/layout`（**批量保存布局**：拖拽一次 ⇒ 一次提交）/ GET·POST `/boards/{boardId}/shares` / DELETE `/boards/{boardId}/shares/{shareId}` / GET·PUT `/boards/{boardId}/defaults` / GET `/boards/{boardId}/view`（**按权限过滤后的真实视图**）/ GET `/board-templates` / POST `/board-templates` / POST `/board-templates/{templateId}/instantiate`（模板实例化）。

- **页面**：`/analytics/custom/board`
  —— `apps/web/src/app/analytics/custom/board/page.tsx` + `apps/web/src/components/analytics/board-{model.ts,workspace.tsx}`：组件拖拽与栅格布局、指标与维度选择、筛选条件、按角色共享、默认视图与默认时间窗；
  **加载 / 空 / 错误三态齐全**，空态区分「还没建过看板」与「筛选无结果」；
  `api-proxy` **就地改写**集成头已有的那条白名单元素（不新插元素、不重排、不动别的车道）。

- **测试**：`BoardIntegrationTest` **28 例** + `BoardTenancyIntegrationTest` **9 例** = **37 例全绿**（实跑 Testcontainers PostgreSQL 16）；前端 `board-model.test.ts` **25 例**；因改动了 `api-proxy.ts`，另跑全量 allowlist 回归 **71 文件 394 例全绿**。

### 关键设计决策

1. **组件上的数字不落库** —— 看板 = 组件配置（落库）+ 语义层口径表（V376）+ 投影事实**实时算出来**的读模型。落一份结果就是**第二个真相**：口径改了、事实刷新了，落库那份不会跟着变。
   *取舍*：每个组件渲染都要跑一次 13.6.1.1 的查询引擎，比「存一份结果」慢；换来的是口径改一处、看板与报表自动跟着改，永远对不上不了。
2. **复用上游，不重写聚合逻辑** —— 把 13.6.1.1 的 `ReportConfig` / `FilterSpec` / `execute` 三个声明**从 private 改成包内可见**（同包调用）。另起一套指标计算逻辑是明确禁止的。
3. **权限过滤两道、同一条规则** —— 库层视图过滤一道，服务层用**同一条规则**再过滤一遍（服务层那道是**保险，不是唯一防线**）；没有权限要求的组件谁都能看，看板创建者看得到自己摆的全部组件。
4. **模板组件与真组件同构**（用真表不用 jsonb）—— 否则模板里的指标码与维度码会**绕过**触发器与 CHECK，「模板可以随便写」。
5. **布局形状在 DB 层钉死，服务层再校验一次并直接报错 400** —— 不**静默丢弃**非法布局。

---

## 模块 13.6.2.1 导出与订阅

**这个功能做什么**：把 13.6.1.1 交付时**明确标注为「未交付」**的两件事补齐 —— **报表导出**（Excel / CSV / PDF，大文件走异步任务并给出下载有效期）与**邮件 / 企业 IM 的真实订阅投递**。
13.6.1.1 当时把界面上「导出」写「未交付」而不给假按钮；本模块把它变成真能用的功能。

### 技术落地

- **迁移**：`backend/services/reporting/src/main/resources/db/migration/V410__export_subscription.sql`
  —— **两张新表，纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  - `reporting_export_job`（异步导出任务：`actor_id` 来自**已认证会话** / `status` PENDING RUNNING SUCCEEDED FAILED CANCELLED EXPIRED 六档 / `row_count` / `file_name` / `storage_key` / `byte_size` numeric(19,6) / `content_sha256` / `error` / `worker_claimed_at` / `requested_at` / `started_at` / `finished_at` / **`expires_at` 下载有效期**）
  - `reporting_export_job_source`（**冻结快照**：`report_id` / `format` CSV XLSX PDF 三档 / `selected_fields` / `field_headers` / `filter_snapshot` / `window_from`·`window_to`，**UNIQUE(tenant_id, job_id)** 幂等键 ⇒ 重跑读的是冻结口径，不随报表改动漂移）
  - 两表 `tenant_id uuid NOT NULL` + **ENABLE 与 FORCE** RLS isolation + `CREATE POLICY isolation` + GRANT + `REVOKE ALL ON flyway_schema_history FROM reporting_app`（与 V376 / V389 / V408 完全同构）
  - **三条 DB 层不变量**：① 触发器 `guard_reporting_export_job` 钉死状态机（PENDING→RUNNING→SUCCEEDED→EXPIRED；FAILED / CANCELLED / EXPIRED 为终态不可迁出）+ 租户与操作人不可改写 + 文件落位后不可换存储键；② 触发器 `guard_reporting_export_job_source` 钉死源快照**一经创建不可改**（改了它，「当时按什么导出的」就没有答案了）；③ 触发器 `reporting_export_job_source_tenant` 拒绝源挂到别的租户的任务上（A 的任务导出 B 的报表在数据层就不可能）+ 跨列 CHECK（有文件 ⟺ SUCCEEDED / EXPIRED，sha256 必须 64 位十六进制）。

- **端点**：**9 条**挂 `/reporting/custom-report`（`ReportingExportController.java` + `ReportingDeliveryController.java`，jOOQ 显式 SQL，无 JPA）——
  GET `/export/sources`（可选 `?reportId=`：可导出的报表 + 可选列清单，列键用 `DIM:` / `MEASURE:` 前缀）/ POST `/export/jobs`（**受理异步导出**）/ GET `/export/jobs`（清单：真分页 + 真搜索 + 真状态筛选）/ GET `/export/jobs/{jobId}`（详情，含 downloadable 与下载说明）/ GET `/export/jobs/{jobId}/file`（**下载真文件**，二进制原样透传 + 带走 content-disposition）/ POST `/export/jobs/{jobId}/cancel`（作废：发现导错范围时第一件事就是让它不能再下载）/ POST `/export/sync`（**同步导出**：行数超阈值明确 **409 拒绝，不截断**）/ GET `/deliveries/{deliveryId}`（投递详情）/ POST `/deliveries/dispatch`（**真跑一轮投递**）。权限码 `reporting.custom-report.read` / `reporting.custom-report.write`。

- **页面**：`/analytics/custom/export`
  —— `apps/web/src/app/analytics/custom/export/page.tsx` + `apps/web/src/components/analytics/export-{model.ts,workspace.tsx}`：导出源选择（报表 + 格式 CSV/XLSX/PDF + 列选择）+ 异步任务清单（状态 / 行数 / 大小 / 有效期 / 下载 / 作废）+ 同步导出（**超阈值 409 原样显示拒绝原因**）+ 投递记录台账（**已投递 / 投递失败分开显示，失败带原因**）；
  **加载 / 空 / 错误三态齐全**，空态区分「还没发起过导出」与「筛选无结果」；
  下载走浏览器直接下载；**`downloadable` 由服务端算**，前端不自行推断过期。

- **测试**：`ExportSubscriptionIntegrationTest` **26 例**（含**真实 TestSmtpServer** 与**真实 IM HttpServer**）+ `ExportSubscriptionTenancyIntegrationTest` **8 例** = **34 例全绿**（实跑 Testcontainers PostgreSQL 16；22 条迁移含 V410 一次跑通）；前端 `export-model.test.ts` **19 例** + `export-subscription-allowlist.test.ts` **7 例** + 全量 allowlist 回归 **71 文件 394 例**全绿；根目录 `pnpm typecheck` 绿。

### 关键设计决策

1. **拆两张表而不是一张** —— 任务行关心「进行到哪一步」（状态、文件在哪、多大、什么时候过期），源行关心「导出的到底是什么」（哪张报表、什么格式、哪些列、什么筛选、什么窗口）。两者**变更节奏不同**：源行创建即冻结（触发器钉死），任务行一路改状态；放进同一张表 ⇒ 冻结约束会跟状态更新打架。分法照抄 catalog 的 `V35 export_job`。
2. **口径唯一** —— 导出**复用上游 13.6.1.1 的 `ReportingCustomReportService.run()`** 分页取数，**不另起指标计算逻辑**。上游分页作用在**分组**上（不会把一个组拆到两页），页面用 `groupsCollected` 而非铺开行数比较，以免**静默少导**。
3. **每个币种一行，涉币度量绝不跨币种相加** —— 导出表带「币种」列，与货币无关的量在每个币种行重复；算不出来的量给空单元格 + 原因（不可比写「—」并说明），与页面所见一致。
4. **同步导出超阈值明确 409 拒绝，不截断**（`REPORTING_EXPORT_SYNC_ROW_THRESHOLD`，默认 1000）—— 蓝图写死「同步导出会拖垮服务」；**截断会让人以为导全了，比拒绝危险**。
5. **`expires_at` 由服务层在任务成功那一刻写入**（`REPORTING_EXPORT_TTL_HOURS`，默认 24h）—— 是**业务参数不是 DB 常量**；过期下载回 **410 Gone** 而不是 500（推进 EXPIRED 的写**先提交**再回 410 —— 同事务里抛异常会把那次更新一起回滚）。
6. **投递诚实性** —— 只有传输层回报 `Outcome.sent=true` 才写 DELIVERED；**没配置通道 / 连接失败 / 被拒收 一律记 FAILED 并写明原因**（含「缺哪个环境变量」）。13.6.1.1 只把记录写成 PENDING（「待投递」）是诚实的，本模块不能把水搅浑 —— **绝不允许未发出却标 DELIVERED**。
7. **投递由外部调度触发**（`POST /deliveries/dispatch`）—— 本服务**不装 `@Scheduled`**（与 channel / identity 同一纪律：多副本下自带定时器要做主节点选举与分布式锁）。
8. **投递三段式**：短事务读 → **不持连接发送** → 短事务写状态（原写法在发送期间占着 Hikari 连接）。
9. **DTO 统一带 `ExportSubscription` 前缀** —— catalog 已有 `ExportJobPage` / `ExportSortInfo`，跨服务撞名会被 codegen 拒绝。
10. **二进制下载必须原样透传** —— xlsx/pdf 走 `arrayBuffer()`，**不走 `upstream.text()`**（按 UTF-8 解码会把字节替换成 U+FFFD，下载下来的文件打不开；现场表现是「下载失败」但服务端明明返回 200）。

### 如实标注的缺口

① 🔴 xlsx / pdf 的「能打开」只到「能被 POI / PDFBox 读回来」的断言级别，**没有**做 Excel / Adobe Reader 的人工打开与第三方阅读器兼容性回归；② 🔴 PDF 字体只覆盖 GB2312 子集（Noto Sans SC，SIL OFL 1.1，2.19MB），字体外的字符被替换成 `?` 并记一条警告（可用 `REPORTING_PDF_FONT_PATH` 换字体）；③ 投递由外部调度触发，本服务不装 `@Scheduled`；④ 导出文件存在服务本地磁盘（`REPORTING_EXPORT_STORAGE_ROOT`），多副本部署时下载请求要落到写出文件的那个副本（**本模块未实现对象存储适配**）；⑤ 同步导出阈值 1000 行，超了明确 409 不截断；⑥ 18.1.3.1 导入导出任务通道未并入 —— 本模块自带可独立运行的任务表 + 异步执行 + 下载有效期，并入时由 18.1.3.1 接管调度与产物投递，本表继续作为「导出记录」的**唯一事实来源**；⑦ 1.3.2.2 推送不在本模块内 —— 本模块产出文件与投递记录，工作台侧推送编排属 1.3.2.2；⑧ 导出源只支持自定义报表这一种（13.6.1.1），其他报表类型未接入。

---

## L2 批次闸状态：🔴 红（红点已精确定位，修复归属明确）

**08:00 tick 的 L2（08:03:23 起）判红**：后端 `reporting` 模块 34 例里 **2 failures / 0 errors**。
本轮（12:00）**亲自复跑复核**（`-pl services/reporting -am test -Dtest='CustomReportIntegrationTest*'`）：**34 例 / 2 failures / 0 errors**，与 08:00 那轮完全一致 ⇒ **没有新增破坏，红点未漂移**。

| 位置 | 断言 | 实际 |
| --- | --- | --- |
| `CustomReportIntegrationTest.java:285` | `capabilities.exportAvailable()` 期望 `false` | 实际 `true` |
| `CustomReportIntegrationTest.java:865` | `jsonPath("$.exportAvailable")` 期望 `false` | 实际 `true` |

**根因**：13.6.1.1 交付时，其能力声明如实标注「导出与投递属 13.6.2.1，**未交付**」⇒ 测试把 `exportAvailable == false` **写死**。
13.6.2.1 本批补齐导出与投递后，服务层把能力声明如实翻成 `available = true`（`ReportingCustomReportService.capabilities()`）⇒ **旧断言过期**。
**这不是跨车道互相破坏，是「上游模块的测试写死了下游尚未交付」的过期断言** —— 修复归属 **13.6.1.1 的测试**，不是 13.6.2.1。

**修复清单（共 6 处断言 + 1 处方法名，都在 `CustomReportIntegrationTest.java`）**：

| 行 | 现在 | 应改为 |
| --- | --- | --- |
| 285 | `assertThat(capabilities.exportAvailable())…isFalse()` | `isTrue()`（导出已由 13.6.2.1 交付） |
| 288 | `assertThat(capabilities.deliveryAvailable())…isFalse()` | `isTrue()`（实际投递已由 13.6.2.1 交付） |
| 287 | `assertThat(capabilities.exportUnavailableReason()).isNotBlank()` | `isBlank()`（服务层现在返回 `""`） |
| 290 | `assertThat(capabilities.deliveryUnavailableReason()).isNotBlank()` | `isBlank()`（同上） |
| 865 | `jsonPath("$.exportAvailable").value(false)` | `.value(true)` |
| 866 | `jsonPath("$.deliveryAvailable").value(false)` | `.value(true)` |
| 283 | 方法名 `能力声明显式标出未交付的导出与投递` | 语义已过期（导出与投递**已交付**），应改名 |

⚠️ **不要动 815 行**的 `assertThat(result.note()).contains("未交付")` —— 那是**投递队列**的 note，与「导出能力是否可用」无关，本轮复跑它是**绿的**。
⚠️ **不要动** 286 / 289 行的 `exportOwnerModule()` / `deliveryOwnerModule()` 断言 —— 服务层仍返回 `"13.6.2.1 导出与订阅"`，`contains("13.6.2.1")` 依然成立。

**处置**：按协议「L2 红 → 不 push、不在集成头上继续改 → 定位后派修复任务 → 重跑 L2 绿再 push」。
本轮因**调度闸被活体持锁**（详见 round-132 说明）而**未派发**修复车道，也**未 push** —— 成果与诊断留在集成头本地，修复后随下一批一起 push。

---

## 本轮同时修掉的台账缺陷（13.6.1.2 证据格被静默截断）

**现象**：13.6.1.2 那行的**证据格**在 09:15 的 round-130 提交里从 **6111 字掉到 2100 字**，截断点正好落在「`PUT|DELETE …`」那根**字面竖线**上 —— **端点后半段 + 前端 + 测试 + 缺口四段全部丢失**，而截断后的文件再跑 `sync.mjs --check` 依然是**绿的**。

**根因（工具 bug，不是手改）**：`sync.mjs` 的证据格捕获组是 `([^|\n]*)` —— 它**表达不了「证据格里可以出现竖线」**，于是只截到第一根竖线就收工 ⇒ **每次写盘都静默截短**。作者本意是把「`PUT`」与「`DELETE`」用竖线分隔，竖线却同时把 markdown 表格切成了多列。

**本轮的两处修复**：
1. **数据**：13.6.1.2 的证据格从 `92181299` 恢复**完整 6113 字**（含端点 22 条、前端、测试、缺口四段），字面竖线改成「`PUT` 与 `DELETE …`」，并去掉行尾多余的那根竖线（恢复成与其余 464 行一致的 6 列）。
2. **工具**：`sync.mjs` 的证据格改用**贪婪捕获** `(.*)` 吃到行尾最后一根竖线 ⇒ 证据格里的竖线**原样保留，写盘不再截断**；同时对「证据格含字面竖线」的行**点名告警**，且 `--check` 直接判红退出 1（不再静默）。新增回归测试 `tools/module-progress/sync-evidence-pipe.itest.mjs`（8 例全绿）。

**校验**：修复后 `node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`；再跑一次写模式 → 文件**逐字节不变**（幂等）；465 行齐全、无重复 ID。
