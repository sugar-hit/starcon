# 2026-10-30: 13.6.2.1 导出与订阅

## 摘要

在 `feat/13-6-2-1` 分支（worktree `.worktrees/13.6.2.1`，基线 `integration/module-dev` @ `84599442`）落地能力蓝图 **13.6.2.1「导出与订阅」**（卷 13 L3 13.6.2 的第二格，D7 阶段）。补齐了 13.6.1.1 明确标注为「未交付」的两件事：**报表导出（Excel / CSV / PDF）+ 大文件异步导出与下载有效期**，与**邮件 / 企业 IM 的真实订阅投递**。产出两张新表（`reporting_export_job` / `reporting_export_job_source`）、9 条端点、1 个前端页面、34 例后端集成测试 + 26 例前端单测。

## 决策 / 结论

1. **拆两张表而不是一张**。任务行（`reporting_export_job`）关心「进行到哪一步」——状态、文件在哪、多大、什么时候过期；源行（`reporting_export_job_source`）关心「导出的到底是什么」——哪张报表、什么格式、哪些列、什么筛选、什么窗口。两者**变更节奏不同**：源行创建即冻结（触发器钉死），任务行一路改状态。放进同一张表 ⇒ 冻结约束会跟状态更新打架。分法照抄 catalog 的 `V35 export_job`。

2. **口径唯一**：导出**复用上游 13.6.1.1 的 `ReportingCustomReportService.run()`** 分页取数，不另起指标计算逻辑。上游分页作用在**分组**上（不会把一个组拆到两页），页面用 `groupsCollected` 而非铺开行数比较，以免静默少导。

3. **每个币种一行**。导出表带「币种」列，涉币度量按币种分行，**绝不跨币种相加**；与货币无关的量在每个币种行重复。算不出来的量给空单元格 + 原因（不可比写「—」并说明），与页面所见一致。

4. **同步导出超阈值明确 409 拒绝，不截断**（`REPORTING_EXPORT_SYNC_ROW_THRESHOLD`，默认 1000）。蓝图写死「大导出走异步任务并给出有效期，同步导出大文件会拖垮服务」——截断会让人以为导全了，比拒绝危险。

5. **`expires_at` 由服务层在任务成功那一刻写入**（`REPORTING_EXPORT_TTL_HOURS`，默认 24h），是**业务参数不是 DB 常量**。过期下载端点回 **410 Gone** 而不是 500。

6. **投递诚实性**：只有传输层回报 `Outcome.sent=true` 才写 DELIVERED；没配置通道 / 连接失败 / 被拒收 **一律记 FAILED 并写明原因**（含缺哪个环境变量）。**绝不允许未发出却标 DELIVERED** —— 13.6.1.1 只把记录写成 PENDING（「待投递」），那是诚实的，本模块不能把水搅浑。

7. **投递由外部调度触发**（`POST /deliveries/dispatch`），本服务**不装 `@Scheduled`** —— 与 channel / identity 同一纪律：多副本下自带定时器要做主节点选举与分布式锁。

8. **投递三段式**：短事务读 → **不持连接发送** → 短事务写状态。原先的写法在发送期间占着 Hikari 连接。

9. **传输配置懒读取**：投递端口与导出业务参数都在**每次调用时**读 Environment（双查环境变量名与 Spring 宽松名），运维改配置免重启，测试可逐例翻转。

10. **DTO 统一带 `ExportSubscription` 前缀**。catalog 已有 `ExportJobPage` / `ExportSortInfo`，跨服务撞名会被 codegen 拒绝。

11. **api-proxy 就地改写**集成头已有的 `custom-report` 那一条 `services[]` 元素（**不新插元素、不重排、不动别的车道**），放行 8 个新形状。

12. **二进制下载必须原样透传**：新增 `binaryPassthrough` 分支，xlsx/pdf 走 `arrayBuffer()`，**不走 `upstream.text()`** —— 后者按 UTF-8 解码，字节被替换成 U+FFFD，下载下来的文件打不开（现场表现是「下载失败」但服务端明明返回 200）。

## 关键 trade-off / 讨论

- **PDF 中文字体**：PDF 标准 14 字体只有 WinAnsi 编码，写中文会抛异常。选择内嵌 **Noto Sans SC 的 GB2312 子集**（SIL OFL 1.1，2.19MB，随 jar 发布），而非只支持 ASCII 的方案或让运维自备。代价：字体外的字符被替换成 `?` 并记一条警告；可用 `REPORTING_PDF_FONT_PATH` 换字体。渲染时**先 sanitize 再 truncate** —— 顺序反了 `getStringWidth` 会抛 "No glyph"。

- **导出文件存本地磁盘**（`REPORTING_EXPORT_STORAGE_ROOT`，默认 `target/reporting-export-store`）：实现了 `ReportingExportStorage` 端口，留了换对象存储的口子，但**本模块未实现对象存储适配**。多副本部署时下载请求要落到写出文件的那个副本 —— 这是**已知的部署约束，已如实写进台账缺口**，不假装解决。

- **不新建投递表**：沿用 13.6.1.1 的 `reporting_custom_report_delivery`（队列）与 `reporting_custom_report_subscription`（计划），本模块只**消费**那张队列并推进状态 ⇒ 不产生第二个投递真相。

- **api-proxy 元素就地改写 vs 新增元素**：13.6.1.1 的记录说「新增一条独立元素，插入锚点在数组末尾」。本模块改用**就地改写同一条元素**，因为同属 `custom-report` 命名空间，拆成两条会让同一前缀的匹配规则分散两处，且字面量子路径（`export/sync` / `deliveries/dispatch`）与 `{uuid}` 段的先后关系只能在同一条正则里表达。发车前用脚本按 `resolve()` 同构口径实测：**8 个新形状全部放行（基线不放行）**、**8 个刻意不放行形状全部被拒**、**51 条既有形状命中集合一字未变**。

## 验证

- L0：`cd backend && ./mvnw -q compile` ✅；根目录 `pnpm typecheck` ✅（3/3 包）。
- L1：`./mvnw -pl services/reporting -am test -Dtest='ExportSubscription*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ✅ —— `ExportSubscriptionIntegrationTest` 26 例 + `ExportSubscriptionTenancyIntegrationTest` 8 例 = **34 例全绿**，实跑 Testcontainers PostgreSQL 16（22 条迁移含 V410 一次跑通）。测试期 PG 容器映射端口 51732，测试结束后 `docker ps -a` 中已无该容器 ⇒ 已被 ryuk 回收；同期机器上残留的其他 `postgres:16-alpine` 与 ryuk 容器来自并行车道，不属本车道。
- 前端：`export-model.test.ts` 19 例 ✅、`export-subscription-allowlist.test.ts` 7 例 ✅、**全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api/trade` 71 文件 394 例 ✅**（改了 api-proxy.ts ⇒ 必跑）。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成 ✅，`git diff --stat packages/` 确认**只有** reporting 两个文件变化，其余 8 个服务逐字节未变。

## 涉及文件

**新增**

- `backend/services/reporting/src/main/resources/db/migration/V410__export_subscription.sql`
- `backend/services/reporting/src/main/resources/fonts/NotoSansSC-Reporting.ttf` + `OFL.txt`
- `backend/services/reporting/src/main/java/com/starcon/reporting/`：`ReportingExportModels.java`、`ReportingExportCatalog.java`、`ReportingExportStorage.java`、`LocalReportingExportStorage.java`、`ReportingExportRenderer.java`、`ReportingExportTableBuilder.java`、`ReportingExportService.java`、`ReportingExportExecutor.java`、`ReportingExportSettings.java`、`ReportingDeliveryTransport.java`、`SmtpReportingDeliveryTransport.java`、`WebhookReportingDeliveryTransport.java`、`ReportingDeliveryService.java`、`ReportingExportController.java`、`ReportingDeliveryController.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/ExportSubscriptionIntegrationTest.java`、`ExportSubscriptionTenancyIntegrationTest.java`
- `apps/web/src/components/analytics/export-model.ts`、`export-model.test.ts`、`export-workspace.tsx`
- `apps/web/src/app/analytics/custom/export/page.tsx`
- `apps/web/src/app/api/reporting/export-subscription-allowlist.test.ts`

**修改**

- `backend/services/reporting/pom.xml`（poi 5.2.3 / poi-ooxml 5.2.3 / pdfbox 2.0.4）
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingCustomReportService.java`（capabilities：exportAvailable / deliveryAvailable 改 true）
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingCustomReportCatalog.java`（移除两条「未交付」说明，改为 PDF 字形限制说明）
- `apps/web/src/lib/api-proxy.ts`（custom-report 正则放行 8 个新形状 + `binaryPassthrough` 分支）
- `packages/api-contracts/src/contracts/reporting.ts`、`packages/api-contracts/src/reporting.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（仅 13.6.2.1 一行的「状态」「证据」两格）

## 后续待办

- 🔴 **xlsx / pdf 的「能打开」只做到断言级别**：测试断言工作簿能被 `XSSFWorkbook` 打开、PDF 能被 PDFBox 解析并取出正确页数与文本；**没有**做 Excel / Adobe Reader 的人工打开与第三方阅读器兼容性回归。后续可在 CI 里加真 Office 兼容层校验。
- 🔴 **对象存储适配未做**：多副本部署前需要把 `ReportingExportStorage` 换成共享存储实现，否则下载请求必须落到写出文件的副本。
- **18.1.3.1 导入导出任务通道并入**：届时由它接管调度与产物投递，本模块的两张表继续作为「导出记录」的**唯一事实来源**。
- **1.3.2.2 推送**：工作台侧把导出结果推到卡片的编排属 1.3.2.2，本模块只提供文件与投递记录。
- **PDF 字体覆盖**：若业务出现 GB2312 之外的字符（如生僻姓名），需换完整字体并重做子集。

## 关联

- `docs/product/capability-blueprint/13-profit-and-analytics.md`（13.6.2.1 蓝图原文，第 240–245 行）
- `docs/product/capability-blueprint/module-progress.md`（13.6.2.1 行）
- `docs/decisions/0002-multi-tenancy-strategy.md`、`docs/architecture/multi-tenancy.md`
- `docs/sessions/2026-10/2026-10-29-custom-report.md`（上游 13.6.1.1）
