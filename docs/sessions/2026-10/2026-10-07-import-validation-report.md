# 2026-10-07 3.8.1.2 校验报告

车道 `3.8.1.2`（分支 `feat/3-8-1-2`，迁移 `V34__import_validation_report.sql`）。

## 决策摘要

**本模块不重新校验。** 校验规则是 3.8.1.1 的 `ImportRules`（纯函数），
唯一写入所有者是 `ImportJobExecutor`。V34 只做三件事：把某一轮预校验结论**固化成报告**、
在这份报告上做**分类统计与逐行定位**、以及**导出带标注的文件**。
没有第二套规则，也没有第二份校验事实。

**报告是快照，不是视图。** 这是本轮最关键的一条。`import_row_error` 的唯一键是
`UNIQUE(tenant_id, job_id, row_no, field_key, code)`，而预校验**可重跑**，
重跑是 `delete from import_row_error where job_id=?` 后整批重写 ——
于是「运营昨天导出并发出去的那份错误清单，今天打开页面已经变了」。
报告页恰恰是用来跟人对账的，所以报告必须钉在某一轮结论上。

**过期判据用 `generated_at` vs `import_job.updated_at`，不用 `prevalidated_at`。**
V29 的触发器是 `prevalidated_at IS NULL` 才写那列，重跑预校验不会更新它 ——
拿它比较，两轮预校验会永远得出「没变过」。`updated_at` 每次 update 都刷（同一个触发器），
于是「报告生成之后任务又被写过」这个事实一定被看见。宁可误报过期（点一下重新生成），
也不能拿过期结论让人去改文件。

**读策略：第一次读生成，之后原样返回快照 + `stale` 标记。**
先考虑过「每次读都物化」，但那样 `stale` 永远是 false（重算完当然是新的），
字段就成了摆设，报告页也就永远没有机会告诉用户「你看到的不是当前结论」。
报告正在被人逐条核对，中途换掉它比显示「已过期」危险得多。
导出则反过来：报告过期时**拒绝**导出（409），不静默重算。

**分类词表封闭六类，没有 OTHER 兜底桶。**
留兜底桶看起来更「健壮」，实际后果是 3.8.1.1 将来新增一个码时会悄悄落进「其他问题」——
运营在报告里看到一栏自己看不懂的东西，却不知道该改什么，而分类这件事本来就是为了消除这种看不懂。
所以 `ImportValidationRules.ALL_CODES` 是封闭词表，`categoryOf` 对未知码返回 `null`，
生成报告时显式失败（点名该码），`ImportValidationRulesTest` 断言词表与上游 14 个机器码一一对应。
上游两处归类有取舍，都写在类的 javadoc 里：`NOT_FOUND_IN_UPDATE_MODE` → 唯一冲突
（问的是「这条记录在库里存在吗」，不是「被引用的对象存不存在」），
`UNMAPPED_COLUMN` → 必填缺失（运营看到的症状就是那一格空的，动作也是同一个）。

**条级 severity 必须归并成行级结论。**
落不落库是**行级**事实：同一行 2 条 WARNING + 1 条 ERROR 仍然不落库。
而 `import_row_error` 是条级的，单看一条答不出「这一行能不能进」。
所以 `ImportValidationRules.conclude()` 做归并，且 `blocking()` 直接读
`ImportRules.Severity` 枚举而不是本地复制一份 —— 复制的那份迟早与上游不同步，
不同步的后果是「报告说能落库、执行阶段把它丢了」。

## 关键 trade-off

**文件原始列序在库里不可复原，导出按模板列序。**
`import_job_row.cells` 是 jsonb，PostgreSQL 的 jsonb 不保留键序（按长度再按字节重排），
所以读回来的顺序不是上传时的顺序。按模板列序（`import_template_column.ordinal`）导出
反而更实用：与「下载模板」给出的列头一致，运营对照模板改字段时不用在两套列序之间换算。
模板未声明的文件列不导出 —— 它们本来就**不被导入读取**
（`ImportJobService.resolveValues` 只按映射读模板声明的列）。

**导出标注列追加在末尾，文件可以原样重新上传。**
这是导出能不能落地的判据：多余列被导入忽略，所以运营不必手工删列。
集成测试真的把导出的文件走一遍 `upload` + 预校验来证明，而不是只在注释里声称。

**导出字节现算，不存 blob；登记 sha256 做守卫。**
与 V29「文件本体不落库」同一纪律。现算换来的性质是「同一个报告导两次字节一致」，
所以 `exportFile` 里那次哈希比对不是装饰，而是「内容确实只由报告决定」的运行时守卫。
台账只登记（谁、何时、基于哪一轮、多少行、哈希多少），不登记多轮报告的历史 ——
审计流水是 17.5.3.1 的职责，不造第二个审计表。

**报告页与导入向导并列，不嵌进步骤。**
报告是对某一个任务那一轮结论的**反复核对**（来回翻明细、导出两三版文件），
与「下载 → 上传 → 映射 → 预校验 → 执行」那条一次性引导链节奏不同。
塞进向导会让「现在我在第几步」在报告页上无法回答。
入口用 `?jobId=` 直达，**没有改动 3.8.1.1 的任何前端文件**。

**api-proxy 挂在已有段下。** 新路径作为 `report` 子段挂在 `import-jobs/{id}/` 之下，
对那条 4000 字符的巨型单行正则改动最小、跨车道冲突最少。
`route.test.ts` 补三组断言：新路径放行、未声明子路径仍 404、
以及 3.8.1.1 既有路径（templates / field-mappings / mapping|prevalidate|execute|cancel|rows|errors）
没被改坏 —— 后者是并行三条车道下的必要回归钉子。

## 文件清单

后端（新增）
- `backend/services/catalog/src/main/resources/db/migration/V34__import_validation_report.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImportValidationRules.java`（纯函数）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImportValidationModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImportValidationService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImportValidationController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/ImportValidationRulesTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/ImportValidationReportIntegrationTest.java`

前端（新增）
- `apps/web/src/components/catalog/validation-report-model.ts`
- `apps/web/src/components/catalog/validation-report-model.test.ts`
- `apps/web/src/components/catalog/validation-report-workspace.tsx`
- `apps/web/src/app/products/import/report/page.tsx`

前端（改动）
- `apps/web/src/lib/api-proxy.ts`（仅 `import-jobs/{id}/` 段加 `report` 子段 + 注释）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（追加三组断言）

生成物 / 台账
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`（codegen，未手改）
- `docs/product/capability-blueprint/module-progress.md`（只改 3.8.1.2 那一行的状态与证据）

## 未改动

3.8.1.1 的全部 Java 文件与 `V29__import_wizard.sql` 一个字没动；
蓝图原文文件没动；identity / channel / warehouse 三份契约快照字节不变
（回喂 codegen 后 git 只显示 catalog 两份改动）。

## 后续待办

- **3.8.2.1 导出**：本模块的导出登记与内容生成是同一段代码，全库导出走那里；
  当前刻意没有扩范围（一个任务内的错误清单 ≠ 全库导出）。
- **xlsx 支持**：与 V29 第 1 节同一条边界，仓库无 Excel 读写依赖。
  若确需支持，先引入 Apache POI，不要在现有 CSV 路径上打补丁。
- **分类词表的维护成本**：`ImportValidationRules.ALL_CODES` 与上游 14 个码手工对齐。
  3.8.1.1 新增错误码时，生成报告会显式失败（不会静默归错类），但仍需要人来补映射。
  如果将来错误码数量明显增长，可以改成从规则层导出码表 —— 本轮没有做，
  因为现在的失败模式是**响亮**的（点名报错码），不是静默的。
- **`prevalidated_at` 语义**：V29 触发器不更新它这件事在 3.8.1.2 之外还影响别的模块
  （任何想用它当「轮次标识」的地方都会踩同一个坑）。已在 V34 文件头写明理由，
  但更彻底的做法是上游在重跑预校验时清空并重写该列 —— 那是 3.8.1.1 的表，本车道没有动它。