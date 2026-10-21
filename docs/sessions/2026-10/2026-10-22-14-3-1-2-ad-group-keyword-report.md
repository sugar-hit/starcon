# 14.3.1.2 广告组与词报表（阶段 D7 · L3 锚点 14.3.1）

- **车道**：14.3.1.2 · 分支 `feat/14-3-1-2` · 基线 `integration/module-dev`（6bd7b1d3）
- **归属**：channel 服务（02 渠道与店铺），与 14.1.1.1 / 14.1.3.1 / 14.3.1.1 同服务同库
- **Flyway**：`V283__ad_group_keyword_reports.sql`（版本号由调度者预分配）

## 决策摘要

1. **组级指标不冒充，声明未交付**。
   实测取证：现有事实表**没有组级指标表** —— `ad_campaign_daily_metric`（V273）是
   **campaign_id 维度**，`ad_keyword_metric` / `ad_search_term`（V274）是 **keyword_id 维度**。
   更硬的一条：**`ad_keyword` 与 `ad_search_term` 根本没有组的引用列**
   （`external_group_id` 只存在于 V277 的 `ad_campaign_group`）⇒ 词指标在物理上无法归到组。
   ⇒ 走能力声明口径：`group_metrics` 维度 `available=false` + `ownerModule=14.1.2.1 广告组管理`
   - 影响说明；组维度的**结构事实**（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动）真实展示，
     指标列一律 **null（不是 0）**，汇总里也没有金额。
     **不**拿活动级指标冒充组级指标，**不**建一张空表冒充「没有数据」。

2. **否定效果评估的口径由本模块定死并公示**（`negativeEffectNote` 随报表与 CSV 表头返回）：
   - 否定生效日 = `applied_at` 的 UTC 日期；为空回退 `evidence_to`；**两者皆空 ⇒ 无法评估**
     （不是「否定后没数据」）。
   - 否定前窗口 = `[起始日, min(截止日, 生效日)]`；否定后窗口 = `[max(起始日, 生效日+1天), 截止日]`。
   - 生效日之后的曝光/点击**应当**归零；**若数据源仍在报就标注「否定后仍有数据」，不把它当成 0**
     （区分「没有数据」（可能只是平台停报）与「有数据但都是 0」）。
   - 否定后的表现以**搜索词报告**为准；被否定的**投放词自身**是否还在跑另由
     `ad_keyword_metric` 判定，两者任一仍有花费或点击即判「否定后仍有数据」。

3. **指标表没有 acos/roas 生成列**（不像 `ad_campaign_daily_metric`）⇒ ACOS / ROAS / CPC
   由服务层**按同口径现算**：ACOS = Σ花费/Σ销售额、ROAS = Σ销售额/Σ花费、CPC = Σ花费/Σ点击，
   分母为 0 一律 **null（不可比）而不是 0**。汇总一律**先汇总再相除**。

4. **关键词级的分页 + 缓存**（蓝图加粗句「查询必须分页与缓存，不能实时全表扫」）：
   - 时间范围**必填**（不给直接 400，不给默认区间）+ 跨度封顶 400 天；
   - **真分页**，且排序是**全序** —— 分桶时把时间桶追加到排序末尾，否则同一词的多行没有稳定次序，
     翻页会重复或漏行；
   - 汇总**单独一条聚合查询**（统计筛选后的全集，不受分页影响），**按币种拆开**（本仓没有汇率源）；
   - 缓存落 `ad_group_keyword_report_cache`，**四条失效依据同时落库**：水位 `data_as_of` /
     批次 `source_batch_key` / 失效令牌 `source_watermark` / 明确过期时刻 `expires_at`。
     **没有水位时显式 BYPASS** —— 宁可每次现算，也不返回一个「缓存了但不知道什么时候过期」的结果。

5. **导出留痕沿用 14.3.1.1 的同一套形状**：`ad_campaign_report_export`（V279）的 `dimension`
   有 CHECK 约束（不含本模块的四个维度），**ALTER 它会动到别的模块已交付的表** ⇒ 按任务书允许的
   第二条路**按同一套形状新建** `ad_group_keyword_report_export`，只多 `granularity` 与 `selection`
   （批量选中的主键）两列 ⇒ 全仓仍然只有**一种导出留痕口径**。

6. **批量选中导出走 POST + body**，且选中集合**进 SQL 的 WHERE**，不在服务层筛 ——
   服务层筛只能筛到**第一页**，用户选了第 3 页上的词，导出里就会没有它（这是实现时发现并修掉的真实 bug）。

## 关键 trade-off

- **组级指标**：宁可在能力声明里说「拿不到」，也不造第二套指标表或拿活动级指标顶替。
  代价是「单组效果」这一半职责当前不交付 —— 责任明确落在 14.1.2.1。
- **缓存放数据库而不是进程内**：多实例下进程内缓存会各算各的；DB 表能让「命中状态 + 失效依据 +
  过期时刻」随响应一起返回，也便于测试钉住。代价是每张报表页多一次读写。
- **加了两条上游表的索引**（`ad_keyword_metric_report_window` /
  `ad_search_term_report_window`）：只加索引，**零 ALTER 任何列、零改任何数据**。
  V274 给 `ad_keyword_metric` 建的是 `(tenant_id, keyword_id, bucket_date)`，**keyword_id 在前**，
  对「先按时间范围收窄」的报表访问路径用不上 ⇒ 不补就是全表扫。
- **汇总不按时间桶拆**：汇总是「整个筛选结果」的汇总，拆成每桶一份会让人以为「按日汇总」和
  「整段汇总」是同一个数。

## 文件清单

**新增（后端）**

- `backend/services/channel/src/main/resources/db/migration/V283__ad_group_keyword_reports.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/AdGroupKeywordReportModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdGroupKeywordReportService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdGroupKeywordReportController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/AdGroupKeywordReportIntegrationTest.java`

**新增（前端）**

- `apps/web/src/components/ads/ad-group-keyword-report-model.ts`
- `apps/web/src/components/ads/ad-group-keyword-report-model.test.ts`
- `apps/web/src/components/ads/ad-group-keyword-report.tsx`
- `apps/web/src/app/ads/group-keyword-reports/page.tsx`
- `apps/web/src/app/api/channel/ad-group-keyword-report-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts` — **只新增一条** `ad-group-keyword-reports/` 元素
  （插入锚点：`ad-reports` 那条元素的 `pattern:` 行之后；首 token 零命中核对通过）
- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  — codegen 产物（+6 path / +12 schema / 0 删除）
- `docs/product/capability-blueprint/module-progress.md` — **只改 14.3.1.2 那一行**的状态与证据

## 验证

- `cd backend && ./mvnw -q compile` → 绿
- `cd backend && ./mvnw -pl services/channel -am test -Dtest='AdGroupKeywordReport*' \
-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **19 例全绿**
- `pnpm typecheck` → 三包绿
- `pnpm vitest run apps/web/src/components/ads/ad-group-keyword-report-model.test.ts \
apps/web/src/app/api/channel/ad-group-keyword-report-allowlist.test.ts` → **29 例全绿**
- `pnpm vitest run apps/web/src/app/api/channel` → **250 例全绿（52 文件）**
  （改了 `api-proxy.ts` ⇒ 必须全量回归累积白名单）
- `pnpm lint` → 三包绿

## 🔴 给调度者的两条提醒

1. **`packages/api-contracts/src/channel.openapi.json` 的 diff 很大，但语义是纯增量**。
   逐条比对的结果是 **+6 path / +12 schema / 0 删除 / 0 修改**。
   之所以行数差很多：**共享契约里 path 的排序在构建之间不稳定** ——
   集成头已提交的那份里 `ad-diagnostics/*` 在 index 575，本车道按指令重新生成后它在 index 253
   （同一个 build 内两次生成结果**完全一致**，说明是跨 build 的发现顺序差异，不是本车道引入的）。
   ⇒ 同批其它车道各自重新生成同一份契约时也会各自带一份重排。
   **建议调度者并入后按 codegen 再生成一次契约**，不要按文本求并。

2. **`node tools/module-progress/sync.mjs --check` 在本车道开工前就是红的**
   （对 HEAD 版本同样报「台账与蓝图不一致」）。总览表是**脚本生成**的，
   同批四条车道都在改状态列 ⇒ 本车道按任务书只改了**自己那一行**的状态与证据，
   **没有**跑 `sync.mjs` 去重算总览（那会动别人也在改的表）。请调度者在批次末尾统一跑一次。

## 后续待办

1. **组级指标**（责任模块 14.1.2.1）：要么给 `ad_keyword` / `ad_search_term` 补上组引用列，
   要么另建组级指标事实表**并给它真实写入口** —— 否则组维度永远只有结构事实。
2. **14.3.2.1 归因分析**：本模块不做归因，能力声明里 `attribution` 显式 available=false。
3. **跨币种统一**：本仓没有汇率源，汇总按币种拆开；汇率源落地后可以补。
4. **同批并行车道的依赖**：本模块**只读** `ad_campaign_group`（14.1.1.2 交付），
   不 import 14.1.2.1 / 14.1.1.3 的类、不建它们的表、不改它们的列。
