import { NextRequest, NextResponse } from 'next/server';

const cookieName = 'starcon_session';
const legacyCookieName = 'starcon_catalog_session';

/**
 * Upstream routing.
 *
 * <p>Each backend service owns a prefix and its own database; the browser never talks to them
 * directly. A path is only proxied when it matches a service's allowlist — unknown paths must 404
 * here rather than being forwarded, so a typo cannot reach a service that would treat it as valid.
 *
 * <p>The full path is forwarded upstream, prefix included: the services expose their own namespace
 * (`/identity/roles`, `/catalog/products`) and the browser's /api mount is only a local alias.
 */
const services: readonly {
  prefix: string;
  pattern: RegExp;
  env: string;
  fallback: string;
}[] = [
  {
    // 13.1.1.1 经营总览（reporting 服务，卷 13「利润与经营分析」L3 13.1.1 下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance / trade 那几条巨型
    //   alternation 里并分支 —— 既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「数组末尾」（收尾的 `];` 之前）；本车道落在这里，
    //   与它们的插入点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「reporting」的零命中核对结论 ----
    //   发车前用脚本把本文件里**全部 234 个既有元素**（`prefix` + 正则）逐条抽出来，
    //   对本模块的 8 条路径逐条跑「前缀闸门 + 正则」判定：
    //     reporting/overview            reporting/overview/capabilities
    //     reporting/overview/metrics    reporting/overview/trend
    //     reporting/overview/drilldown  reporting/projection/events
    //     reporting/projection/refresh  reporting/projection/rebuild
    //   **零命中** —— 既有 8 个 prefix（channel/ trade/ finance/ catalog/ crm/ identity/
    //   warehouse/ auth/）里**没有** `reporting/`，而 resolve() 先按 prefix 闸门筛，
    //   所以别的服务的正则根本吃不到本模块的路径。
    //   核对脚本留在 .tmp/ 下（不入库），命令与输出见本次提交正文。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   overview                  经营总览（指标卡 + 趋势 + 异常提示 + 数据更新时间戳）
    //   overview/capabilities     能力声明
    //   overview/metrics          指标口径目录（口径唯一的展示形态）
    //   overview/trend            趋势（逐日逐币种）
    //   overview/drilldown        下钻构成明细（哪个订单 / 哪个 SKU / 哪个活动）
    //   projection/events         投影摄取（POST，事件 → 投影管道的主路径）
    //   projection/refresh        从上游拉取并投影（POST，与推共用同一套幂等与口径）
    //   projection/rebuild        清空投影并重建（POST）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/                     —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   reporting/overview/{uuid}       —— 顶层没有 id 段（总览按查询参数定位，不是按 id）。
    //   reporting/projection/{uuid}     —— 同上，投影摄取是批量端点，没有 id 段。
    //   reporting/overview/unknown      —— 未实现的子路径仍然 404。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^(?:overview(?:\/(?:capabilities|metrics|trend|drilldown))?|projection(?:\/(?:events|refresh|rebuild))?)$/,
  },
  {
    // 13.3.1.1 产品表现分析（reporting 服务，卷 13「利润与经营分析」L3 13.3 产品分析下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**13.1.1.1 经营总览那条 reporting/ 之后**（数组最前面那一段，紧邻数组第一个元素）----
    //   ⚠️ 本条目**曾经**挂在数组末尾（收尾的 `];` 之前、紧跟 13.2.2.2 的 product-sales 之后），
    //   与同批 13.3.2.1 的 traffic-conversion 撞在同一段 ⇒ 并入集成头时 `api-proxy.ts` 报 CONFLICT。
    //   本车道改挂到**集成头没有动过的位置**（13.1.1.1 那条之后），
    //   与 14.2.1.2 的 ad-dayparts（第一个 `prefix: 'identity/'` 之前）
    //   相距 1300 行以上、与 13.3.2.1 的 traffic-conversion（数组末尾）相距 12000 行以上
    //   ⇒ 三处追加互不重叠，并入时 git 能干净求并。
    //
    // ---- 首 token「product-performance」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（本车道的 .tmp/ 一次性脚本，不入库）把本文件里**全部既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 resolve() 的**同构口径**实测
    //   （path.startsWith(prefix) 收窄服务 → 再测 pattern.test(path.slice(prefix.length))）：
    //     reporting/product-performance               零命中
    //     reporting/product-performance/capabilities  零命中
    //     reporting/product-performance/metrics       零命中
    //     reporting/product-performance/refresh       零命中
    //   **零命中** —— 既有 `prefix === 'reporting/'` 的 12 条正则都是**按命名空间精确匹配**的
    //   （首 token 分别是 overview / projection / expense-variance / store-comparison /
    //     sales-trend / target-attainment / metric-cards / store-overview / targets /
    //     store-site / product-sales / time-pattern / traffic-conversion），
    //   **没有一条能吃下 `product-performance`** ⇒ 本模块 4 条路径在放行前**全部 404**。
    //   反向核对：另外 **67 条**别的车道已交付的 reporting 形状
    //   （13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.2.1.1 / 13.1.2.x / 1.1.2.x /
    //     13.2.2.1 / 13.2.1.2 / 13.2.2.2 / 13.3.2.1）
    //   **全部仍然命中** —— 本条新元素不会遮蔽它们。
    //   另外核对了本模块**不得**出现在别的服务前缀下：trade/ catalog/ channel/ finance/
    //   warehouse/ crm/ identity/ platform/ 下的 product-performance **全部 404**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这 4 条）：
    //   product-performance           360 度分析（九大区块 + 趋势 + 排名分位 + 环比基线；裸路径是 @GetMapping 在类根上的）
    //   product-performance/capabilities 能力声明（真读过哪些上游 / 哪些指标本轮拿不到、归谁管）
    //   product-performance/metrics   360 度指标口径目录（口径唯一的展示形态）
    //   product-performance/refresh   刷新 360 度投影（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/product-performance/{uuid}       —— 顶层没有 id 段（按查询参数定位，不是按 id）。
    //   reporting/product-performance/unknown      —— 未实现的子路径仍然 404。
    //   reporting/product-performance/drilldown    —— 本模块**没有**层级钻取端点：360 度是**汇总读模型**，
    //     钻取属各业务域自己的处理页（蓝图「下游 = 各业务域的处理页」）。
    //   reporting/product-performance/spu-summary  —— SPU 汇总属 13.2.2.2 的 product-sales 命名空间。
    //
    // 字面量子路径（capabilities / metrics / refresh）排在可选段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^product\-performance(?:\/(?:capabilities|metrics|refresh))?$/,
  },
  {
    // 13.4.2.2 费用差异（reporting 服务，卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 那条 reporting/ 正则里并分支 ——
    //   13.1.1.1 的条目**一字未改、也不重排**（那条正则只放行 overview / projection 两个命名空间）。
    //
    // ---- 插入锚点选在**13.1.1.1 那条 reporting/ 之后**（数组最前面那一段）----
    //   同批其它车道的插入点是「数组末尾」（收尾的 `];` 之前）；本车道落在开头这一段，
    //   与它们的插入点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「expense-variance」的零命中核对结论 ----
    //   发车前用脚本把本文件里**全部 228 个既有元素**（`prefix` + 正则）逐条抽出来，
    //   对本模块的 7 条路径逐条跑「前缀闸门 + 正则」判定：
    //     expense-variance/capabilities
    //     expense-variance/variance
    //     expense-variance/trend
    //     expense-variance/recommendations
    //     expense-variance/by-sku
    //     expense-variance/refresh
    //     expense-variance
    //   **零命中** —— 既有 228 条正则里没有任何一条会吃掉本模块的路径，
    //   而 resolve() 先按 prefix 闸门筛，所以别的服务的正则根本吃不到本模块的路径。
    //   核对脚本留在 .tmp/ 下（不入库），命令与输出见本次提交正文。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   capabilities       能力声明（哪些上游真接通、哪些确实算不出差异）
    //   variance           差异表 + 有实际无预计 + 偏差趋势 + 规则调优建议
    //   trend              偏差趋势（单独取一条线）
    //   recommendations    规则调优建议（单独取一份清单）
    //   by-sku             SKU 级差异（真分页 + 真计数）
    //   refresh            刷新费用事实（POST，与 projection/refresh 同一条管道）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   expense-variance             —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   expense-variance/{uuid}      —— 顶层没有 id 段（差异表按查询参数定位，不是按 id）。
    //   expense-variance/not-a-shape —— 多一层仍然 404。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^expense-variance\/(?:capabilities|variance|trend|recommendations|by-sku|refresh)$/,
  },
  {
    // 13.1.1.2 多店对比（reporting 服务，卷 13「利润与经营分析」L3 13.1.1 经营看板下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 / 13.4.2.2 那两条 reporting/ 正则里
    //   并分支 —— 那两条**一字未改、也不重排**（它们分别只放行 overview / projection 与 expense-variance
    //   两个命名空间）。
    //
    // ---- 插入锚点选在**13.4.2.2 那条 reporting/ 之后**（数组最前面那一段）----
    //   同批其它车道的插入点是「数组末尾」（收尾的 `];` 之前）；本车道落在开头这一段，
    //   与它们的插入点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「store-comparison」的零命中核对结论 ----
    //   发车前把本文件里**全部既有元素**（prefix + 正则）逐条抽出来，对本模块的 5 条路径
    //   逐条跑「前缀闸门 + 正则」判定：
    //     store-comparison
    //     store-comparison/capabilities
    //     store-comparison/metrics
    //     store-comparison/shops
    //     store-comparison/refresh
    //   **零命中** —— 既有两条 reporting/ 正则（overview / projection 与 expense-variance）
    //   都吃不到本模块的路径；resolve() 先按 prefix 闸门筛，所以别的服务的正则也根本够不着。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   （裸路径）      对比矩阵 + 排名 + 差异归因提示 + 掉队/增长店铺
    //   capabilities   能力声明（哪些上游真接通、店铺维度确实拿不到什么）
    //   metrics        店铺对比的指标口径目录（口径唯一的展示形态）
    //   shops          店铺清单 + 平台清单（筛选器与「按平台分组」用）
    //   refresh        刷新店铺维度投影（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   store-comparison/{uuid}      —— 顶层没有 id 段（对比矩阵按查询参数定位，不是按 id）。
    //   store-comparison/not-a-shape —— 多一层仍然 404。
    //   store-comparison/alerts      —— 店铺级预警未交付（卷 13 的预警是另一格）。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^store-comparison(?:\/(?:capabilities|metrics|shops|refresh))?$/,
  },
  {
    // 13.2.1.1 销量趋势（reporting 服务，卷 13「利润与经营分析」L3 13.2 销量分析下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 / 13.4.2.2 / 13.1.1.2 那几条
    //   reporting/ 正则里并分支 —— 那几条**一字未改、也不重排**（它们分别只放行
    //   overview / projection、expense-variance、store-comparison 三个命名空间）。
    //
    // ---- 插入锚点选在**13.1.1.2 那条 reporting/ 之后**（数组最前面那一段）----
    //   同批其它车道（13.2.2.2）插在**数组末尾**（收尾的 `];` 之前）；
    //   本车道落在开头这一段，两个锚点相隔 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「sales-trend」的零命中核对结论 ----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 234 个既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`），
    //   对本模块的 6 条路径逐条判定：
    //     sales-trend                sales-trend/capabilities
    //     sales-trend/comparison     sales-trend/events
    //     sales-trend/metrics        sales-trend/refresh
    //   **零命中** —— 既有 `prefix === 'reporting/'` 的几条正则都是**按命名空间精确匹配**的
    //   （overview / projection、expense-variance、store-comparison、metric-cards、
    //   store-overview），**不覆盖 `sales-trend`**；而别的 prefix 下的正则根本吃不到本模块的路径
    //   （`resolve()` 先按 prefix 闸门筛）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这几条）：
    //   sales-trend            销量趋势（趋势点列 + 环比 / 同比 + 异常标注）
    //   sales-trend/comparison 多维对比（同比 / 环比 / 店铺间 / 产品间）
    //   sales-trend/events     异常日期标注（大促 / 秒杀 / 缺货 / 断货）
    //   sales-trend/capabilities 能力声明（哪些上游真接通、哪些口径确实拿不到）
    //   sales-trend/metrics    指标口径目录（口径唯一的展示形态）
    //   sales-trend/refresh    刷新销量趋势投影（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   sales-trend/{uuid}      —— 顶层没有 id 段（趋势按查询参数定位，不是按 id）。
    //   sales-trend/anomalies   —— 异常清单随趋势响应一起返回，没有独立端点。
    //   sales-trend/alerts      —— 销量预警未交付（卷 13 的预警订阅是另一格）。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^sales-trend(?:\/(?:capabilities|comparison|events|metrics|refresh))?$/,
  },
  {
    // 13.1.2.2 达成追踪（reporting 服务，卷 13「利润与经营分析」L3 13.1.2 目标管理下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.2.1.1
    //   那几条 reporting/ 正则里并分支 —— 那几条**一字未改、也不重排**（它们分别只放行
    //   overview / projection、expense-variance、store-comparison、sales-trend 四个命名空间）。
    //
    // ---- 插入锚点选在**13.2.1.1 那条 reporting/ 之后**（数组最前面那一段）----
    //   同批其它车道（13.2.2.1 店铺与站点）落在**数组末尾**（收尾的 `];` 之前）；
    //   本车道落在开头这一段，两个锚点相隔 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「target-attainment」的零命中核对结论（用 TypeScript 编译器实评数组）----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）**用 TypeScript 编译器解析**
    //   本文件里 `services` 变量的**真实数组字面量**（不是文本计数），
    //   逐条取出 `prefix` 与 `pattern`，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`），
    //   对本模块的 5 条路径逐条判定：
    //     target-attainment/capabilities 零命中
    //     target-attainment/board        零命中
    //     target-attainment/ranking      零命中
    //     target-attainment/snapshots    零命中
    //     target-attainment/evaluate     零命中
    //   **零命中** —— 既有 `prefix === 'reporting/'` 的几条正则都是**按命名空间精确匹配**的
    //   （overview / projection、expense-variance、store-comparison、sales-trend、
    //     metric-cards、store-overview），**不覆盖 `target-attainment`**；
    //   而别的 prefix 下的正则根本吃不到本模块的路径（`resolve()` 先按 prefix 闸门筛）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这几条）：
    //   target-attainment/capabilities  能力声明（哪些实际值拿得到、判定规则是什么）
    //   target-attainment/board         达成追踪看板（达成率表 + 预测 + 未达标高亮 + 排名 + 预警）
    //   target-attainment/ranking       排名（人员 / 产品维度）
    //   target-attainment/snapshots     已落快照清单（真分页 + 真计数）
    //   target-attainment/evaluate      评估并落快照（POST，写的是派生读模型不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/target-attainment        —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   target-attainment/{uuid}            —— 顶层没有 id 段（达成追踪按查询参数定位，不是按 id）。
    //   target-attainment/unknown-sub       —— 多一层仍然 404。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^target-attainment\/(?:capabilities|board|ranking|snapshots|evaluate)$/,
  },
  {
    // 1.1.2.1 核心指标卡（reporting 服务，卷 01「工作台与门户」L3 1.1.2 关键指标下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 / 13.4.2.2 / 13.1.1.2 那三条
    //   reporting/ 正则里并分支 —— 那三条**一字未改、也不重排**（它们分别只放行
    //   overview / projection、expense-variance、store-comparison 三个命名空间）。
    //
    // ---- 插入锚点选在**那三条 reporting/ 之后**（数组靠前那一段）----
    //   同批其它车道（1.3.2.1，identity 服务）插在 `identity/` 那条元素附近；
    //   本车道落在数组开头这一段，两个锚点相隔很远，并入时 git 能干净求并。
    //
    // ---- 首 token「metric-cards」的零命中核对结论 ----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 240 个既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`）：
    //     reporting/metric-cards               零命中
    //     reporting/metric-cards/capabilities  零命中
    //     reporting/metric-cards/unknown-sub   零命中
    //     reporting/metric-cards/refresh       零命中
    //     reporting/metric-cards/{uuid}        零命中
    //   **零命中** —— 本文件里 `prefix === 'reporting/'` 的既有元素恰好 **3 条**，
    //   它们的正则都是**按命名空间精确匹配**的
    //   （`overview|overview/capabilities|overview/metrics|overview/trend|overview/drilldown|
    //     projection|projection/events|projection/refresh|projection/rebuild` /
    //     `expense-variance/...` / `store-comparison/...`），
    //   **不覆盖 `metric-cards`**；而别的 prefix 下的正则根本吃不到本模块的路径
    //   （`resolve()` 先按 prefix 闸门筛）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这两条）：
    //   metric-cards            核心指标卡（指标值 + 环比 + 同比 + 趋势箭头 + 下钻链接 + 口径说明）
    //   metric-cards/capabilities 能力声明（逐个指标报上游 / 口径归属 / 下钻目标是否可用）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/metric-cards/{uuid}      —— 顶层没有 id 段（卡片按查询参数定位，不是按 id）。
    //   reporting/metric-cards/refresh     —— 🔴 本模块**没有写端点**：卷 01 卷首写死
    //                                          「工作台只读 + 跳转」，刷新由用户点页面上的刷新按钮。
    //   reporting/metric-cards/unknown-sub —— 多一层仍然 404。
    //   reporting/metric-cards              —— 放行的是**裸路径**本身（这是 @GetMapping 在类根上的），
    //                                          别与上面这条「刻意不放行」清单里的裸路径混淆：
    //                                          刻意不放行的是**别的服务**下的 `reporting/` 裸路径。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^metric-cards(?:\/capabilities)?$/,
  },
  {
    // 1.1.2.2 店铺概览（reporting 服务，卷 01「工作台与门户」L3 1.1.2 关键指标下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往 13.1.1.1 / 13.4.2.2 / 13.1.1.2 /
    //   1.1.2.1 那四条 reporting/ 正则里并分支 —— 那四条**一字未改、也不重排**
    //   （它们分别只放行 overview / projection、expense-variance、store-comparison、metric-cards
    //   四个命名空间）。
    //
    // ---- 插入锚点选在**那四条 reporting/ 之后**（数组靠前那一段）----
    //   同批其它车道插在 `identity/`、`warehouse/`、`crm/` 那几条附近或数组末尾；
    //   本车道落在数组开头这一段，锚点与它们的插入点相隔很远，并入时 git 能干净求并。
    //
    // ---- 首 token「store-overview」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 231 条既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`）：
    //     reporting/store-overview               零命中
    //     reporting/store-overview/capabilities  零命中
    //     reporting/store-overview/shops         零命中
    //     reporting/store-overview/unknown-sub   零命中
    //     reporting/store-overview/refresh       零命中
    //     reporting/store-overview/{uuid}        零命中
    //   **零命中** —— 本文件里 `prefix === 'reporting/'` 的既有元素恰好 **4 条**，
    //   它们的正则都是**按命名空间精确匹配**的
    //   （`overview|overview/capabilities|overview/metrics|overview/trend|overview/drilldown|
    //     projection|projection/events|projection/refresh|projection/rebuild` /
    //     `expense-variance/...` / `store-comparison/...` / `metric-cards/...`），
    //   **不覆盖 `store-overview`**；而别的 prefix 下的正则根本吃不到本模块的路径
    //   （`resolve()` 先按 prefix 闸门筛）。
    //   同一支脚本同时反向核对了 **23 条别的车道已交付的形状**
    //   （13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 1.1.2.1 / 1.1.2.3）**全部仍然命中** ——
    //   本条新元素不会遮蔽它们。
    //   另外 grep 口径：既有全文里 `store-overview` 这个串出现次数 = **0**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这三条）：
    //   store-overview            店铺概览（按店铺横向对比当期表现 + 识别掉队店铺）
    //   store-overview/capabilities 能力声明（五个维度逐条报指标码 / 接不接得通 / 口径归谁）
    //   store-overview/shops      可见店铺集合（平台筛选器用）+ 每个店铺在这个窗口有没有事实
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/store-overview/{uuid}      —— 顶层没有 id 段（概览按查询参数定位，不是按 id）。
    //   reporting/store-overview/refresh     —— 🔴 本模块**没有写端点**：卷 01 卷首写死
    //                                          「工作台只读 + 跳转」，店铺维度投影的刷新是
    //                                          13.1.1.2 多店对比的职责（`/reporting/store-comparison/refresh`）。
    //   reporting/store-overview/unknown-sub —— 多一层仍然 404。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^store-overview(?:\/(?:capabilities|shops))?$/,
  },
  {
    // 13.1.2.1 目标设定（reporting 服务，卷 13「利润与经营分析」L3 13.1.2 目标管理下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有那几条 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**（它们分别只放行 overview / projection、expense-variance、
    //   store-comparison、metric-cards、store-overview 五个命名空间）。
    //
    // ---- 插入锚点选在**那几条 reporting/ 之后**（数组靠前那一段）----
    //   同批其它车道插在 identity/、warehouse/、crm/ 那几条附近或数组末尾；
    //   本车道落在数组开头这一段，锚点与它们的插入点相隔很远，并入时 git 能干净求并。
    //
    // ---- 首 token「targets」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 244 条既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`）：
    //     reporting/targets                                       零命中
    //     reporting/targets/capabilities                          零命中
    //     reporting/targets/current-period                         零命中
    //     reporting/targets/decomposition                          零命中
    //     reporting/targets/metrics                                零命中
    //     reporting/targets/owners                                 零命中
    //     reporting/targets/{uuid}                                 零命中
    //     reporting/targets/{uuid}/baseline                        零命中
    //   **零命中** —— 本文件里 `prefix === 'reporting/'` 的既有元素恰好 **5 条**，
    //   它们的正则都是**按命名空间精确匹配**的，**不覆盖 `targets`**；
    //   而别的 prefix 下的正则根本吃不到本模块的路径（`resolve()` 先按 prefix 闸门筛）。
    //   同一支脚本同时反向核对了 **24 条别的车道已交付的 reporting 形状**
    //   （13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 1.1.2.1 / 1.1.2.3）**全部仍然命中** ——
    //   本条新元素不会遮蔽它们。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这 8 条）：
    //   targets                目标清单（真分页 + 真计数 + 按币种合计）
    //   targets/capabilities   能力声明（归属对象来源 / 历史基线边界 / 哪些口径没登记）
    //   targets/metrics        蓝图点名的目标指标清单（按归属维度，可引用哪些口径码）
    //   targets/owners         归属对象清单（部门 / 人员 / 店铺 / 产品）
    //   targets/current-period 当前周期边界（**按本租户时区算**，不写死 UTC）
    //   targets/decomposition  分解预览（不落库）：分解到月 / 周，合计精确等于目标值
    //   targets/{uuid}         目标详情（含完整分解计划与历史基线快照）；也是 PUT / DELETE 的落点
    //   targets/{uuid}/baseline 重新读一次历史基线（POST）
    //
    // ⚠️ **id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把字面量子路径也吃掉）。
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/targets/not-a-shape        —— 不存在的子路径仍然 404。
    //   reporting/targets/capabilities/extra —— 多一层仍然 404。
    //   reporting/targets/{uuid}/unknown      —— 目标 id 下只有 baseline 一个子路径。
    //   reporting/targets/tracking           —— 🔴 13.1.2.2 达成追踪未交付（那是下游另一格）。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^targets(?:\/(?:capabilities|current-period|decomposition|metrics|owners)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/baseline)?)?$/,
  },
  {
    // 13.2.2.1 店铺与站点（reporting 服务，卷 13「利润与经营分析」L3 13.2.2 维度拆解下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有那几条 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**（它们分别只放行 overview / projection、expense-variance、
    //   store-comparison、sales-trend、metric-cards、store-overview、targets、product-sales
    //   八个命名空间）。
    //
    // ---- 插入锚点选在**targets 那条之后**（数组靠前那一段）----
    //   同批其它车道插在 identity/、warehouse/、crm/ 那几条附近或数组末尾；
    //   本车道落在数组开头这一段，锚点与它们的插入点相隔很远，并入时 git 能干净求并。
    //
    // ---- 首 token「store-site」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 341 条既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`）：
    //     reporting/store-site               零命中
    //     reporting/store-site/capabilities  零命中
    //     reporting/store-site/drilldown     零命中
    //     reporting/store-site/metrics       零命中
    //     reporting/store-site/refresh       零命中
    //   **零命中** —— 本文件里 `prefix === 'reporting/'` 的既有元素恰好 **8 条**，
    //   它们的正则都是**按命名空间精确匹配**的，**不覆盖 `store-site`**；
    //   而别的 prefix 下的正则根本吃不到本模块的路径（`resolve()` 先按 prefix 闸门筛）。
    //   ⚠️ 特别核对过两个**同前缀**的老邻居：`store-comparison` 与 `store-overview` ——
    //   它们的首 token 是 `store-c` / `store-o`，与 `store-site` **两两不相交**。
    //   同一支脚本同时反向核对了 **42 条别的车道已交付的 reporting 形状**
    //   （13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.2.1.1 / 1.1.2.1 / 1.1.2.2 / 13.1.2.1 / 13.2.2.2）
    //   **全部仍然命中** —— 本条新元素不会遮蔽它们。
    //   另外核对了本模块**不得**出现在别的服务前缀下：trade/ store-site、catalog/ store-site、
    //   channel/ store-site **全部零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这 5 条）：
    //   store-site            店铺 / 站点 / 平台维度拆解（分组报表 + 占比 + 增长贡献 + 新老店 + 渠道占比）
    //   store-site/capabilities 能力声明（哪些上游真接通、站点归属边界、判定规则）
    //   store-site/metrics    指标口径目录（口径唯一的展示形态）
    //   store-site/drilldown  构成明细下钻（哪张订单、哪张订单的哪一行）
    //   store-site/refresh    刷新店铺/站点投影（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/store-site/{uuid}        —— 顶层没有 id 段（报表按查询参数定位，不是按 id）。
    //   reporting/store-site/unknown-sub   —— 多一层仍然 404。
    //   reporting/store-site/shops         —— 🔴 店铺清单随报表的行一起给出，没有独立端点。
    //   reporting/store-site/channels      —— 🔴 渠道占比随响应里的 channelShares 给出。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern: /^store\-site(?:\/(?:capabilities|drilldown|metrics|refresh))?$/,
  },
  {
    // 13.2.1.2 分时与分周模式（reporting 服务，卷 13「利润与经营分析」L3 13.2 销量分析下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有那几条 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**（它们分别只放行 overview / projection、expense-variance、
    //   store-comparison、sales-trend、metric-cards、store-overview、targets、store-site、
    //   product-sales 九个命名空间）。
    //
    // ---- 插入锚点选在**store-site 那条之后**（数组靠前那一段，紧邻 13.2.2.1）----
    //   同批其它车道的插入点是「数组末尾」（收尾的 `];` 之前，13.2.2.2 的 product-sales 就在那里）
    //   与「第一个 identity/ 之前」「第一个 warehouse/ 之前」；
    //   本车道落在数组开头这一段，与「数组末尾」那个锚点相距 12000 行以上 ⇒ git 能干净求并。
    //
    // ---- 首 token「time-pattern」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把本文件里**全部 246 条既有元素**
    //   （`prefix` + 正则）逐条抽出来，按 `resolve()` 的**同构口径**实测
    //   （`path.startsWith(prefix)` 收窄服务 → 再测 `pattern.test(path.slice(prefix.length))`）：
    //     reporting/time-pattern                              零命中
    //     reporting/time-pattern/capabilities                 零命中
    //     reporting/time-pattern/metrics                      零命中
    //     reporting/time-pattern/shops                        零命中
    //     reporting/time-pattern/slots                        零命中
    //     reporting/time-pattern/slots/{uuid}                 零命中
    //     reporting/time-pattern/refresh                      零命中
    //   **零命中** —— 本文件里 `prefix === 'reporting/'` 的既有元素的首 token 分别是
    //   overview / projection / expense-variance / store-comparison / sales-trend /
    //   metric-cards / store-overview / targets / store-site / product-sales，
    //   **没有一条能吃下 `time-pattern`**；而别的 prefix 下的正则根本够不着本模块的路径
    //   （`resolve()` 先按 prefix 闸门筛）。
    //   ⚠️ 反向核对：另外 **44 条**别的车道已交付的 reporting 形状
    //   （13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.2.1.1 / 13.2.2.2 / 13.1.2.x / 13.2.2.1）
    //   **全部仍然命中** —— 本条新元素不会遮蔽它们。
    //   另外核对了本模块**不得**出现在别的服务前缀下：trade/ catalog/ channel/ finance/
    //   warehouse/ crm/ identity/ platform/ 下的 time-pattern **全部零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 `reporting.openapi.json` 里就这 7 条）：
    //   time-pattern              热力图 + 小时/星期分布 + 时段判定 + 高峰时段排序（裸路径是 @GetMapping 在类根上的）
    //   time-pattern/capabilities 能力声明（时区怎么定、汇总按哪个口径、高峰低谷怎么判、哪些确实拿不到）
    //   time-pattern/metrics      分时指标口径目录（口径唯一的展示形态）
    //   time-pattern/shops        店铺时区档案（每个店铺按哪个时区统计；定不了的也列出来并写清原因）
    //   time-pattern/slots        时段配置清单（租户自己的数据：高峰/平峰/低谷的区间与判定阈值）
    //   time-pattern/slots/{uuid} 单条时段配置的改 / 删（PUT / DELETE）
    //   time-pattern/refresh      刷新分时投影（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/time-pattern/{uuid}       —— 顶层没有 id 段（报表按查询参数定位，不是按 id）。
    //   reporting/time-pattern/heatmap       —— 热力图随主响应一起给出，没有独立端点。
    //   reporting/time-pattern/peak-ranking  —— 高峰排序随主响应一起给出，没有独立端点。
    //   reporting/time-pattern/not-a-shape   —— 多一层仍然 404。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / metrics / shops / slots / refresh）排在 {uuid} 段
    //   **之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^time\-pattern(?:\/(?:capabilities|metrics|shops|slots(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|refresh))?$/,
  },
  {
    // 12.4.1.3 成本差异分析（finance 服务，卷 12「财务核算」L3 12.4.1 成本计算下的第三格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「第一个 `identity/` 之前」「第一个 `warehouse/` 之前」
    //   与「数组末尾」；本车道落在这里，与它们的插入点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「cost-variance」的零命中核对结论 ----
    //   发车前用 `grep -n "cost-variance" apps/web/src/lib/api-proxy.ts` 实测：**零命中**（无输出）。
    //   本文件既有 services[] 正则里，本模块 **10 个形状逐一实测零命中**：
    //     cost-variance/capabilities / cost-variance/options
    //     cost-variance/baselines / cost-variance/baselines/versions
    //     cost-variance/baselines/compare
    //     cost-variance/reports / cost-variance/reports/{uuid}
    //     cost-variance/items / cost-variance/drivers / cost-variance/alerts
    //   既有 finance 那支是单行超长 alternation，其首 token 是 `receivables` / `receivable-aging` /
    //   `payables` / `receipts` / `bad-debt` / `payment-plans` / `payment-orders` /
    //   `payment-executions` / `write-offs` / `settlement` / `transactions` / `fund-analysis` /
    //   `cost-collections` / `cost-valuation` / `deferred-settlement` / `cost-calculation`，
    //   **全不相同** —— 特别是 `cost-calculation` 与 `cost-valuation`：
    //   首 token 就不是 `cost-variance`（`cost-c` / `cost-v` ≠ `cost-va`），
    //   既不会被它们吃掉，也不会吃掉它们 ⇒ 两个正则两两不相交。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cost-variance/capabilities            能力声明（上游真接通 / 下游缺口归谁）
    //   cost-variance/options                 筛选项（基线类型 / 维度 / 币种 / 驱动因素 / 可比性 / 严重度）
    //   cost-variance/baselines               基线列表（GET）/ 登记或切换基线（POST，**追加版本**）
    //   cost-variance/baselines/versions      版本历史（新到旧）
    //   cost-variance/baselines/compare       版本对比（切换前 / 切换后，逐字段给出差异）
    //   cost-variance/reports                 报表列表（GET）/ 执行差异分析（POST）
    //   cost-variance/reports/{uuid}          报表明细（GET：表头 + 按币种分组 + 驱动因素 + 异常提示）
    //   cost-variance/items                   明细列表（GET，真分页 + 真筛选：逐 SKU × 币种 × 驱动因素）
    //   cost-variance/drivers                 驱动因素列表（GET，真分页 + 真筛选：按币种分组 + 主因归因）
    //   cost-variance/alerts                  异常提示列表（GET，真分页 + 真筛选）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   cost-variance                         —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   cost-variance/{uuid}                  —— 顶层没有 id 段（报表按 /reports/{uuid} 定位）。
    //   cost-variance/reports/{uuid}/not-a-shape —— 多一层仍然 404。
    //   cost-variance/baselines/{uuid}        —— 基线按 维度 × 币种 × 版本 定位，**没有** id 段。
    //   cost-variance/not-a-uuid              —— 非本模块的子路径。
    //   cost-variance/profit                  —— 🔴 利润属 13（未交付）。
    //   cost-calculation/variance             —— 🔴 那是 12.4.1.2 的形状（它的差异对比是 runs/compare）；
    //                                               本模块用**自己的** `cost-variance` 前缀，不复用它。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `compare` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / baselines / items / drivers / alerts /
    // baselines\/versions / baselines\/compare）排在 {uuid} 段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^cost\-variance\/(?:capabilities|options|baselines(?:\/(?:versions|compare))?|reports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|items|drivers|alerts)$/,
  },
  {
    // 12.4.1.2 成本计算执行（finance 服务，卷 12「财务核算」L3 12.4.1 成本计算下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「第一个 `identity/` 之前」「第一个 `warehouse/` 之前」
    //   与「数组末尾」；本车道落在这里，与它们的插入点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「cost-calculation」的零命中核对结论 ----
    //   发车前用 `grep -n "cost-calculation" apps/web/src/lib/api-proxy.ts` 实测：**零命中**（exit 1）。
    //   本文件既有 services[] 正则里，本模块 **8 个形状逐一实测零命中**：
    //     cost-calculation/capabilities / cost-calculation/options
    //     cost-calculation/runs / cost-calculation/runs/compare
    //     cost-calculation/runs/{uuid} / cost-calculation/runs/{uuid}/recalculate
    //     cost-calculation/layers / cost-calculation/entries
    //   既有 finance 那支是单行超长 alternation，其首 token 是 `receivables` / `receivable-aging` /
    //   `payables` / `receipts` / `bad-debt` / `payment-plans` / `payment-orders` /
    //   `payment-executions` / `write-offs` / `settlement` / `transactions` / `fund-analysis` /
    //   `cost-collections` / `cost-valuation` / `deferred-settlement`，
    //   **全不相同** —— 特别是 `cost-collections` 与 `cost-valuation`：
    //   首 token 就不是 `cost-calculation`（`cost-col` / `cost-val` ≠ `cost-cal`），
    //   既不会被它们吃掉，也不会吃掉它们 ⇒ 两个正则两两不相交。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cost-calculation/capabilities            能力声明（上游真接通 / 下游缺口归谁）
    //   cost-calculation/options                 筛选项 + 四个计价方法选项（复用 12.4.1.1 的那一份）
    //   cost-calculation/runs                    批次列表（GET）/ 执行成本计算（POST）
    //   cost-calculation/runs/{uuid}             批次明细（GET：表头 + 成本层 + 成本分录）
    //   cost-calculation/runs/{uuid}/recalculate 按期间重算（POST，**另起批次**，旧批次不被改写）
    //   cost-calculation/runs/compare            差异对比（GET，两次批次之间逐 SKU）
    //   cost-calculation/layers                  成本层列表（GET，真分页 + 真筛选）
    //   cost-calculation/entries                 成本分录列表（GET，真分页 + 真筛选 + 汇总）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   cost-calculation                          —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   cost-calculation/{uuid}                   —— 顶层没有 id 段（批次按 /runs/{uuid} 定位）。
    //   cost-calculation/runs/compare/{uuid}      —— 字面量段刻意不接 id 段。
    //   cost-calculation/runs/{uuid}/not-a-shape  —— 多一层仍然 404。
    //   cost-calculation/not-a-uuid               —— 非 UUID 段不应被 uuid 分支吃掉。
    //   cost-calculation/variance                 —— 🔴 成本差异分析属 12.4.1.3，**未交付**：
    //                                               本模块的「差异对比」是 runs/compare（两次批次之间）。
    //   cost-calculation/cost-layers|cost-results —— 🔴 成本层与结转金额在 runs/layers/entries 上，
    //                                               不另开这两个路径。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `compare` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / layers / entries / runs / runs\/compare）排在
    // {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^cost\-calculation\/(?:capabilities|options|layers|entries|runs(?:\/(?:compare|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/recalculate)?))?)$/,
  },
  {
    // 12.2.2.1 付款单与审批（finance 服务，卷 12「财务核算」L3 12.2.2 付款下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「第一个 `identity/` 之前」「第一个 `warehouse/` 之前」
    //   与「数组末尾」；本车道落在这里，与它们的插入点相距 600 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「payment-orders」的零命中核对结论 ----
    //   本文件既有 **204 条** services[] 正则（按 `pattern:` 逐条抽出、剔除行注释后实测）里，
    //   对本模块 **8 个形状逐一实测零命中**：
    //     payment-orders / payment-orders/capabilities / payment-orders/options
    //     payment-orders/exchange-rate
    //     payment-orders/payable-lines/{uuid}
    //     payment-orders/{uuid} / payment-orders/{uuid}/submit
    //     payment-orders/{uuid}/sync-approval
    //   既有 finance 那支是单行超长 alternation，其首 token 是 `receivables` / `receivable-aging` /
    //   `payables` / `prepayments` / `payment-plans` / `write-off` / `settlement` / `bad-debt` 等，
    //   **全不相同**；特别是既有的 `^payment\-plans(?:\/…)?$` 与 `^payment(?:\/…)?$` 形状
    //   都不匹配 `payment-orders`（首 token 就不同）⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   payment-orders                     列表（GET，真分页 + 真筛选 + 真排序 + 按币种分开汇总）
    //   payment-orders                     建单（POST，草稿）
    //   payment-orders/{uuid}              单张付款单详情（GET：单头 + 明细 + 分批 + 事件流 + 审批记录）
    //   payment-orders/{uuid}              改单（PUT，**只限草稿态**）
    //   payment-orders/capabilities        能力声明（付款执行 / 资金账户未交付 + 归属模块）
    //   payment-orders/options             筛选项（状态 / 审批状态 / 币种 / 付款方式 / 付款对象 / 付款计划）
    //   payment-orders/exchange-rate       汇率快照（GET，读 catalog 5.2.2.1）
    //   payment-orders/payable-lines/{uuid} 某张应付单下可被选中的明细行（GET，只读 12.1.2.1）
    //   payment-orders/{uuid}/submit        提交审批（POST）
    //   payment-orders/{uuid}/sync-approval 同步审批结果（POST，读回 17 审批的链路）
    //
    // ⚠️ **刻意没有「审批通过 / 审批驳回」**：审批属 17 审批（identity，已交付）——
    //   本模块只提交、只读回，**不造第二套审批引擎** ⇒ 放行这两个形状会让界面以为
    //   「本地就能审」，那是一条假的审批路径。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / exchange-rate / payable-lines）排在 {uuid} 段**之前**判别
    // —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   payment-orders/{uuid}/execute       —— 🔴 付款执行属 12.2.2.2，**未交付**
    //   payment-orders/{uuid}/pay            —— 同上
    //   payment-orders/{uuid}/mark-paid      —— 同上
    //   payment-orders/{uuid}/settle         —— 同上（结算/核销另属 12.2.1.2 / 12.1.1）
    //   payment-orders/{uuid}/approve        —— 🔴 审批属 17 审批（identity），本模块**不提供**本地审批端点
    //   payment-orders/{uuid}/reject         —— 同上
    //   payment-orders/batch                 —— 本模块**没有**批量建单端点
    //   payment-orders/{uuid}/ledger         —— 🔴 凭证属 12.5.2.1，未交付
    //   payment-orders/not-a-uuid            —— 非 UUID 段不应被 uuid 分支吃掉
    //   payment-orders/{uuid}/not-a-shape    —— 多一层仍然 404
    //   payment-orders/capabilities/{uuid}   —— 字面量段刻意不接 id 段
    //   payment-orders/options/{uuid}        —— 同上
    //   payment-orders/payment-orders        —— 交替写错就会互相吃掉，必须反向钉住
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^payment\-orders(?:\/(?:capabilities|options|exchange\-rate|payable\-lines\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|sync\-approval))?))?$/,
  },
  {
    // 12.2.2.2 付款执行（finance 服务，卷 12「财务核算」L3 12.2.2 付款下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **12.2.2.1 付款单那条之后**（数组前段，finance 簇里）----
    //   同批其它车道插的是别的锚点（数组最开头 / 某个 identity|warehouse 之前 / 数组末尾），
    //   本车道落在这里，与它们的插入点相距足够远，并入时 git 能干净求并。
    //
    // ---- 首 token「payment-executions」的零命中核对结论 ----
    //   本文件既有 **209 条** services[] 正则（按 `pattern:` 逐条抽出实测）里，
    //   **没有任何一条**含 `payment-executions` 这个串；本模块 **10 个形状**对既有正则
    //   逐一实测**零命中**，本模块 13 个负向形状对既有正则也**零命中**。
    //   既有 finance 那几条的首 token 是 `receivables` / `receivable-aging` / `payables` /
    //   `prepayments` / `payment-plans` / `write-off` / `settlement` / `bad-debt` /
    //   `receipts` / `payment-orders` / `transactions` / `fund-analysis` 等，
    //   **全不相同**；特别是既有的 `^payment\-orders…` 与 `^payment\-plans…` 都不匹配
    //   `payment-executions`（首 token 就不同）⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   payment-executions                     列表（GET，真分页 + 真筛选 + 按币种分开汇总）
    //   payment-executions                     发起付款执行（POST）
    //   payment-executions/{uuid}              执行单详情（GET：执行单 + 尝试 + 事件 + 分摊 + 锁汇）
    //   payment-executions/{uuid}/verify       状态查证（POST，**不重发**）
    //   payment-executions/{uuid}/retry        查证后重发（POST，**服务端先查证再重发**）
    //   payment-executions/capabilities        能力声明（资金账户 / 凭证未交付 + 归属模块）
    //   payment-executions/options             筛选项（状态 / 失败分类 / 币种 / 渠道 / 付款单）
    //   payment-executions/pending-orders      可发起付款执行的付款单（已批 / 执行中，逐批列出）
    //   payment-executions/market-rate         当前市场汇率（GET，读 catalog 5.2.2.1）
    //   payment-executions/channels            支付渠道列表（GET，凭据只存**引用**）
    //   payment-executions/channels            登记支付渠道（POST）
    //   payment-executions/channels/{uuid}     改支付渠道配置（PUT）
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / pending-orders / market-rate / channels）
    // 排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   payment-executions/{uuid}/execute       —— 发起执行是**集合**上的 POST，不是子路径
    //   payment-executions/{uuid}/pay            —— 同上（后端没有这两个别名）
    //   payment-executions/{uuid}/mark-paid      —— 同上
    //   payment-executions/{uuid}/settle         —— 同上（结算 / 核销属 12.3 / 12.2.1.2）
    //   payment-executions/{uuid}/approve        —— 🔴 审批属 17 审批（identity），本模块**不提供**本地审批端点
    //   payment-executions/{uuid}/reject         —— 同上
    //   payment-executions/{uuid}/ledger         —— 🔴 凭证属 12.5.2.1，未交付
    //   payment-executions/batch                 —— 本模块**没有**批量执行端点
    //   payment-executions/not-a-uuid            —— 非 UUID 段不应被 uuid 分支吃掉
    //   payment-executions/{uuid}/not-a-shape    —— 多一层仍然 404
    //   payment-executions/capabilities/{uuid}   —— 字面量段刻意不接 id 段
    //   payment-executions/payment-executions    —— 交替写错就会互相吃掉，必须反向钉住
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^payment\-executions(?:\/(?:capabilities|options|pending\-orders|market\-rate|channels(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:verify|retry))?))?$/,
  },
  {
    // 12.1.1.3 坏账与核销（finance 服务，卷 12「财务核算」L3 12.1.1 应收与资金下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「第一个 `prefix: 'identity/'` 之前」「第一个 `prefix: 'warehouse/'` 之前」
    //   与「数组末尾」；本车道落在这里，与它们的插入点相距 600 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「bad-debt」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `bad-debt` 这个串
    //   （既有 finance 条目的首 token 是 `receivables` / `receivable-aging` / `payables` / `receipts`，
    //   全不相同），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   bad-debt                                  列表（GET，真分页 + 真筛选 + 真排序 + 汇总）/ 建单（POST）
    //   bad-debt/capabilities                     能力声明（上游真接通 / 下游缺口归谁 / 会计处理不可用）
    //   bad-debt/options                          筛选项（状态 / 事由 / 币种 / 店铺 / 依据类型）
    //   bad-debt/summary                          坏账汇总（按状态 / 事由 / 币种）
    //   bad-debt/{uuid}                           单张申请详情（GET：单头 + 依据材料 + 审计留痕 + 可执行动作）
    //   bad-debt/{uuid}/evidence                  加依据材料（POST）
    //   bad-debt/{uuid}/evidence/{uuid}/remove    移除依据材料（POST · 软移除）
    //   bad-debt/{uuid}/submit                    提交审批（POST）
    //   bad-debt/{uuid}/approve                   审批通过（POST）
    //   bad-debt/{uuid}/reject                    审批驳回（POST）
    //   bad-debt/{uuid}/write-off                 执行核销（POST）
    //   bad-debt/{uuid}/void                      作废申请（POST）
    //
    // ⚠️ **刻意没有 `bad-debt/batch-write-off` 之类的批量形状** ——
    //   蓝图 12-finance.md 加粗句：「不允许批量一键核销无审批」⇒ 后端不提供批量端点，
    //   放行一个不存在的写入口比 404 更糟（界面会以为功能已交付）。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / summary）排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   bad-debt/batch-write-off     —— 🔴 批量一键核销：蓝图明令禁止
    //   bad-debt/{uuid}/settle       —— 🔴 核销金额的写者恒为 12.2.1.2，本模块不提供
    //   bad-debt/{uuid}/evidence/{uuid}/upload —— 本模块不提供材料上传（材料以引用登记）
    //   bad-debt/{uuid}/evidence/{uuid}           —— 刻意不放行裸 DELETE 形状：
    //                                                 finance 的 API 出口不导出 DELETE
    //   bad-debt/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   bad-debt/{uuid}/ledger       —— 🔴 凭证属 12.5.2.1，未交付
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^bad-debt(?:\/(?:capabilities|options|summary|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:evidence|submit|approve|reject|write\-off|void))?(?:\/evidence\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/remove)?)?)?)?$/,
  },
  {
    // 1.1.1.2 异常订单队列（trade 服务，卷 01「工作台与门户」L3 1.1.1 待办与待决策的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 trade 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组最开头**（本文件第一个元素之前）----
    //   同批其它车道的插入点是「`prefix: 'identity/'` 之前」「`prefix: 'warehouse/'` 之前」
    //   与「数组末尾」；本车道落在这里，与它们的插入点相距 600 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「anomaly-orders」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `anomaly-orders` 这个串
    //   （既有含 "anomaly" 的是 catalog 的 `price-anomaly-alerts`，首 token 全不相同），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   anomaly-orders                    异常订单清单（GET，真分页 + 真筛选 + 真排序 + 汇总）
    //   anomaly-orders/capabilities       能力声明（七类异常哪几类真接通 + 动作缺口）
    //   anomaly-orders/options            筛选项（异常类型 / 店铺 / 订单来源 / 停留时长档位）
    //   anomaly-orders/{uuid}             单条明细（GET：订单行 + 费用 + 状态留痕 + 逐条凭据）
    //
    // ⚠️ **本模块是只读的**：没有 POST / PUT / DELETE 端点，所以这里**一个写形状都不放行**。
    //   卷 01 的边界写死「工作台只读 + 跳转；一切状态变更回到归属系统完成」——
    //   放行一个不存在的写入口，比 404 更糟（界面会以为功能已交付）。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options）排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   anomaly-orders/not-a-uuid         —— 非 UUID 段不应被 uuid 分支吃掉
    //   anomaly-orders/{uuid}/ignore       —— 🔴 「标记忽略」没有归属系统的写端点，
    //                                         工作台不写业务数据 ⇒ 这个形状不存在
    //   anomaly-orders/bulk-*              —— 批量动作一律落到归属系统，工作台不另建写路径
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^anomaly\-orders(?:\/(?:capabilities|options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 6.1.1.3 供应商评级（trade 服务，卷 06「采购与供应」L3 6.1.1 供应商管理下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 trade 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点选在**数组最开头**（`auth/` 之前）----
    //   同批其它车道的插入点是「`prefix: 'trade/'` 之前」或「数组末尾」；
    //   开头这一段本仓**还没有车道用过**，落在这里的 diff 与它们的插入点不重叠，
    //   并入时 git 不会判冲突。
    //
    // ---- 首 token「supplier-ratings」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `supplier-ratings` 这个串
    //   （既有的是 `supplier-claims` / `supplier-qualifications` / `supplier-sources`），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   supplier-ratings/capabilities                  能力声明（五个维度各自的依据与下游归属）
    //   supplier-ratings/options                       词表（周期 / 维度 / 等级 / 变动来源）
    //   supplier-ratings/overview                      概览（等级分布、平均分、人工调整家数）
    //   supplier-ratings/policy                        权重配置（GET 读 / PUT 改）
    //   supplier-ratings/compute                       计算一个周期的评级（POST，写动作）
    //   supplier-ratings/ratings                       评级列表（GET，真分页 + 真筛选 + 真排序）
    //   supplier-ratings/ratings/{uuid}                单条评级详情（GET）
    //   supplier-ratings/ratings/{uuid}/adjustment     人工加权分（POST，理由必填）
    //   supplier-ratings/changes                       评级变动历史（GET，真分页）
    //   supplier-ratings/priorities                    默认供应商优先级（GET，供 6.2.1.2 消费）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   supplier-ratings                              —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   supplier-ratings/ratings/{uuid}/delete        —— 评级结果是事实，不提供删除。
    //   supplier-ratings/ratings/{uuid}/recompute      —— 重算走 `POST /compute`（按周期），
    //                                                    不按单条重算。
    //   supplier-ratings/export                       —— 导出走 3.8.2.1 的统一通道，不另开一条。
    //   supplier-ratings/ratings/not-a-uuid            —— 非 UUID 段不应被 uuid 分支吃掉。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / overview / policy / compute / ratings / changes /
    // priorities）排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    // 根路径后的那一段**是必需的**（`supplier-ratings` 裸路径被拒）：
    // 后端没有「一条不存在的汇总列表接口」，放行它会让界面以为可用。
    pattern:
      /^supplier\-ratings(?:\/(?:capabilities|options|overview|policy|compute|changes|priorities|ratings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/adjustment)?)?))$/,
  },
  {
    // 14.1.1.1 广告活动列表（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-type-diffs`（14.1.2.2）、`rate-comparison`（10.1.2.2）等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'auth/'` 那条**之前** ----
    //   同批其它车道的插入点分别是「第一个 `channel/` 之前」「中段 trade 区」「数组末尾」，
    //   与本处相距 400 行以上，git 能干净求并。
    //
    // ---- 首 token「ad-campaigns」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-campaigns` 这个串
    //   （既有的是 `ad-type-diffs` / `campaign-*` 一类的其它命名），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-campaigns/capabilities          能力声明（未交付段 available=false + ownerModule）
    //   ad-campaigns/options               筛选项下拉 + 批量操作护栏上限
    //   ad-campaigns/campaigns             活动列表（GET，真分页 + 真筛选 + 真排序 + 汇总 + 数据截至时间）
    //   ad-campaigns/campaigns/{uuid}      活动详情（GET，含每日明细与操作审计）
    //   ad-campaigns/by-external           🔴 按平台侧 id 取活动当前状态与预算/消耗（GET，供 14.2.2.2 规则引擎调用）
    //   ad-campaigns/imports               登记广告数据同步结果（POST，真实写入口）
    //   ad-campaigns/bulk-operations       批量暂停 / 启用 / 调预算（POST，逐条返回执行结果）
    //   ad-campaigns/sync-runs             同步运行历史（GET，数据截至时间的逐条依据）
    //
    // 判别顺序：字面量子路径（capabilities / options / by-external / imports / bulk-operations /
    // sync-runs / campaigns）排在 {uuid} 段之前 —— alternation 是首个命中即返回，顺序反了会被
    // 通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-campaigns` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-campaigns/campaigns/{uuid}/metrics` —— 每日明细随详情一起返回，不单独开一条
    //   * `ad-campaigns/campaigns/{uuid}/preview` —— 变更预览属于 14.1.1.3（未交付）
    //   * `ad-campaigns/campaigns/select-all` —— 「按筛选结果全选」属于 14.1.1.3（未交付）
    //   * `ad-campaigns/bulk-operations/tasks` —— 异步批量任务属于 14.1.1.3（未交付）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-campaigns`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-campaigns(?:\/(?:capabilities|options|by\-external|imports|bulk\-operations|sync\-runs|campaigns(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))$/,
  },
  {
    // 12.7.2.1 资金分析（finance 服务，卷 12「财务核算」L3 12.7.2 资金下的唯一一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**（本文件的 diff 应当只有纯追加）。
    //
    // ---- 插入锚点选在 `prefix: 'auth/'`（login/logout/session/memberships/switch）那条**之前** ----
    //   同批其它三条车道各自锚在别的行上，彼此相距很远 ⇒ git 能干净求并。
    //
    // ---- 首 token「fund-analysis」的零命中核对结论 ----
    //   本文件既有 **206** 条 services[] 元素（每条一个 `fallback:`）里**没有任何一条**含
    //   `fund-analysis`（既有的 finance 首 token 是 `bad-debt` / `receivable-aging` /
    //   `payment-plans` / `payables` / `prepayments` / `write-off` / `receivables` /
    //   `receipts` / `settlement`，其余服务的前缀是 identity / catalog / channel /
    //   warehouse / trade / crm / auth …），
    //   且逐条用 `new RegExp(pat).test('…')` 实测过：既有 206 条正则（含注释里被顺手抽出来的
    //   3 条样例正则，共 209 条 —— 是真实元素集合的**超集**，所以这个结论更强而不是更弱）
    //   对本模块的 11 个 shape **全部零命中**；本模块的 11 个 shape 对既有正则也**全部零命中**
    //   ⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   fund-analysis/capabilities         能力声明（12.7.1.1 / 12.7.1.2 两个口径 available=false + ownerModule）
    //   fund-analysis/options              筛选项下拉（币种 + 本位币候选，全部来自真实数据）
    //   fund-analysis/cash-flow-trend      现金流入流出趋势（实际 / 预期 / 计划三个口径并列）
    //   fund-analysis/currency-distribution 各币种资金分布（四路真实聚合，按币种分开）
    //   fund-analysis/exposure             汇率敞口测算（实时；汇率取不到上游 503，不返回空集合）
    //   fund-analysis/exposure/measure     汇率敞口测算并落成快照（POST）
    //   fund-analysis/exposure/snapshots   敞口快照历史（真分页）
    //   fund-analysis/turnover-days        资金周转天数（DSO / DPO；存货段恒 null，不用 0 冒充）
    //   fund-analysis/payment-concentration 付款集中度（时间维度 + 付款对象维度）
    //   fund-analysis/liquidity-alerts     流动性预警（四条规则跑在真实聚合上）
    //   fund-analysis/thresholds           流动性预警阈值配置（GET 读 / PUT 整体替换）
    //
    // ⚠️ **字面量子路径的顺序**：更长的形状（exposure/measure、exposure/snapshots）
    //   排在 `exposure` **之前**判别 —— alternation 是首个命中即返回，顺序反了会被短支吃掉半截。
    //
    // ⚠️ **本模块没有 id 段**：12 个端点全是**固定形状**，没有「按 id 取详情」这种资源。
    //   所以本条正则里**没有** {uuid} 段（也就不存在「非 UUID 段被 uuid 分支吃掉」的风险）。
    //   若将来加了 id 段，id 段必须用**完整 UUID 形状**
    //   `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`：
    //   后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `fund-analysis` —— 本模块没有「一条不存在的汇总列表接口」
    //   * `fund-analysis/not-a-shape` —— 不是本模块的任何形状
    //   * `fund-analysis/accounts` —— 🔴 资金账户（12.7.1.1）**未交付**
    //   * `fund-analysis/bank-statements` —— 🔴 银行对账（12.7.1.2）**未交付**
    //   * `fund-analysis/bank-reconciliation` —— 🔴 银行对账（12.7.1.2）**未交付**
    //   * `fund-analysis/profit` —— 🔴 利润与经营分析（13）**未交付**
    //   * `fund-analysis/dashboard-cards` —— 🔴 工作台卡片（卷 01）**未交付**
    //   * `fund-analysis/exposure/not-a-uuid` —— 非 UUID 段不应被放行
    //   * `fund-analysis/thresholds/PAYMENT_CONCENTRATION` —— 没有逐档端点（配置是整体替换）
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^fund\-analysis\/(?:exposure\/measure|exposure\/snapshots|exposure|capabilities|options|cash\-flow\-trend|currency\-distribution|turnover\-days|payment\-concentration|liquidity\-alerts|thresholds)$/,
  },
  {
    prefix: 'auth/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^(login|logout|session|memberships|switch)$/,
  },
  {
    // 14.4.1.2 优惠券与秒杀（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有分支（`ad-promotions` / `ad-campaigns` / `ad-budgets` / `ad-keywords` …）**一字未动、
    // 也不重排** —— 同批多车道共享这一个文件，往同一条 alternation 里并分支会让 git 必然判冲突
    // 且 retry 永远不会自愈。
    //
    // ---- 插入锚点选在 `auth/`（login/logout/session/memberships/switch）那条**之后** ----
    //   本批其它三条车道（14.1.3.2 / 14.4.2.1 / 14.5.1.1）各自锚在别的行上
    //   （`listing-collects` / `aftersales/...` / `purchase-followups` 那行之后），
    //   彼此相距很远 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-coupons」与「ad-flash-sales」的零命中核对结论 ----
    //   本文件既有 **134** 条 services[] 正则里**没有任何一条**含 `ad-coupons` 或 `ad-flash-sales`
    //   （既有的是 `ad-campaigns` / `ad-budgets` / `ad-keywords` / `ad-rules` / `ad-reports` /
    //   `ad-type-diffs` / `ad-promotions` 一类），
    //   且逐条用 `new RegExp(pat).test('…')` 实测过：既有 134 条对本模块的 19 个 shape
    //   **全部零命中** ⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   `ad-promotions` 不匹配 `ad-coupons` / `ad-flash-sales`（14.4.1.1 兄弟模块不撞车）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-coupons/capabilities                    能力声明（平台营销回写 / 客户主数据 /
    //                                               买赠补贴成本三个维度 available=false）
    //   ad-coupons/options                         下拉项（券类型 / 状态 / 同步状态 / 领取方式 / 渠道）
    //   ad-coupons/coupons                         券列表（GET，真分页 + 真筛选）/ 登记券（POST）
    //   ad-coupons/coupons/{uuid}                  券详情（GET）/ 改券规则（POST，替换语义）
    //   ad-coupons/coupons/{uuid}/status           改本地状态（POST；上线 / 暂停 / 结束 / 停用）
    //   ad-coupons/coupons/{uuid}/codes            批量生成券码（POST，真库存计数）/ 券码列表（GET）
    //   ad-coupons/coupons/{uuid}/claims           领取 / 定向发放（POST，限领累计校验）
    //   ad-coupons/coupons/{uuid}/redemptions      核销（POST，**必须绑定订单标识**）
    //   ad-coupons/coupons/{uuid}/statistics        核销统计（GET，真聚合）
    //   ad-coupons/coupons/{uuid}/channels         投放渠道与投放计划（GET 读 / POST 登记）
    //   ad-flash-sales/capabilities                秒杀能力声明（平台营销回写 / 毛利两个维度 false）
    //   ad-flash-sales/options                     秒杀下拉项
    //   ad-flash-sales/flash-sales                 秒杀列表（GET）/ 登记秒杀（POST）
    //   ad-flash-sales/flash-sales/{uuid}          秒杀详情（GET）/ 改秒杀（POST）
    //   ad-flash-sales/flash-sales/{uuid}/status   改本地状态（POST）
    //   ad-flash-sales/flash-sales/{uuid}/claims   领取秒杀（POST，库存真扣 + 按人限领）
    //   ad-flash-sales/flash-sales/{uuid}/redemptions 核销秒杀（POST，绑定订单标识）
    //   ad-flash-sales/flash-sales/{uuid}/statistics   秒杀统计（GET，真聚合）
    //
    // 判别顺序：字面量子路径（capabilities / options / coupons / flash-sales）排在 {uuid} 段之前
    // —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-coupons` / `ad-flash-sales` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-coupons/coupons/{uuid}/delete` —— 券是事实，撤下走 status=CANCELLED
    //   * `ad-coupons/coupons/{uuid}/platform-writeback` —— **平台营销接口未交付**，
    //     放行它等于让界面以为「券已经回写到平台了」
    //   * `ad-coupons/coupons/{uuid}/recipients` —— **15 客户未交付** ⇒ 定向发放只记收件人标识
    //   * `ad-coupons/coupons/{uuid}/margins` —— 毛利不可用（成本价在 05 定价，跨服务不可读）
    //   * 非 UUID 段 —— `ad-coupons/coupons/not-a-uuid` 必须 404
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-coupons`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-coupons(?:\/(?:capabilities|options|coupons(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|codes|claims|redemptions|statistics|channels))?)?))$/,
  },
  {
    // 14.4.1.2 优惠券与秒杀 —— **秒杀**这一半（channel 服务）。
    // 与上面 `ad-coupons` 那条**独立成条**，不并进同一条 alternation（同批多车道并入时
    // git 才能干净求并）。形状与理由完全对齐优惠券那一条，注释不再重复。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-flash\-sales(?:\/(?:capabilities|options|flash\-sales(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|claims|redemptions|statistics))?)?))$/,
  },
  {
    // 16.4.1.2 检测与预警（channel 服务，卷 16「合规与风险」L3 16.4「知识产权」的第二格）。
    // 纯**新增一条独立的 `services[]` 元素**，既有的 `ip-risk-library`（16.4.1.1 上游）、
    // `ad-coupons` / `ad-flash-sales` 等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点选在 `prefix: 'identity/'` 那条元素**之前**（即 `ad-flash-sales` 那条之后）----
    //   同批其它三条车道分别锚在 `// 14.5.2.1 带货归因` 之前、`// 11.4.1.1 平台纠纷列表` 之前
    //   与数组末尾（`buyer-risk-scores` 之后），与本处互不重叠 ⇒ git 能干净求并。
    //
    // ---- 首 token「ip-detections」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `ip-detections` 这个串
    //   （既有的是 `ip-risk-library`（上游 16.4.1.1）/ `buyer-risk-scores` / `platform-violations` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ip-detections/capabilities                能力声明（各条口径 + 未交付的外部边界逐条列出）
    //   ip-detections/detections                  检测报告清单（GET，真分页 + 真筛选）/ 发起检测（POST）
    //   ip-detections/detections/{uuid}           报告详情（GET，含命中项 / 预警 / 整改建议 / 复核任务）
    //   ip-detections/alerts                      预警清单（GET，真分页）
    //   ip-detections/alerts/{uuid}/status        确认 / 处置预警（PUT，终态不可回退）
    //   ip-detections/remediations                整改建议清单（GET）/ 手工新增一条（POST）
    //   ip-detections/remediations/{uuid}/status  推进整改建议状态（PUT，终态不可回退）
    //   ip-detections/reviews                     人工复核队列（GET）/ 提一条复核（POST）
    //   ip-detections/reviews/{uuid}              复核结论（PUT，CONFIRMED / DISMISSED）
    //   ip-detections/external-queries            外部商标 / 专利查询清单（GET）/ 登记一条（POST）
    //   ip-detections/external-queries/{uuid}     外部查询详情（GET，含登记回来的结果）
    //   ip-detections/external-queries/{uuid}/hits   登记查回来的外部结果（POST，append-only）
    //   ip-detections/external-queries/{uuid}/status 推进查询状态（PUT：查无结果 / 查询失败）
    //   ip-detections/inspection-plans            巡检计划清单（GET）/ 新建计划（POST）
    //   ip-detections/inspection-plans/{uuid}     改巡检计划（PUT）
    //   ip-detections/inspection-runs            巡检运行清单（GET）/ 跑一轮巡检（POST）
    //   ip-detections/inspection-runs/{uuid}     巡检运行详情（GET，这一轮产出的报告）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ **末尾刻意不带 `?`**：带了就等于放行裸路径 `ip-detections` ——
    //   后端没有「一条汇总列表接口」，一切入口都带子路径。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ip-detections` —— 本模块没有裸集合读
    //   * `ip-detections/block` / `ip-detections/release` /
    //     `ip-detections/detections/{uuid}/block` / `.../release` ——
    //     **拦截权归 16.2.1.2 拦截执行（已交付）**，本模块只产出**建议**（recommended_action），
    //     放行一个不存在的拦截入口会让界面以为「已经拦了」
    //   * `ip-detections/image-search` / `ip-detections/detections/{uuid}/reverse-image-search` ——
    //     **以图搜图未交付**（本仓没有图像识别引擎）；图片比对是**标识文本比对**，
    //     命中**一律**进人工复核队列
    //   * `ip-detections/external-queries/sync` / `ip-detections/external-trademark-search` ——
    //     **自动外呼外部商标库未交付**（本仓没有凭据与网络出口）；外部结果靠**登记承接**
    //   * `ip-detections/detections/{uuid}/delete` / `reviews/{uuid}/delete` /
    //     `alerts/{uuid}/delete` —— 检测结论与命中项是**证据**，**append-only**，不做删除
    //   * `ip-detections/alerts/{uuid}` —— 没有「按 id 取单条预警」这个端点
    //   * `ip-detections/detections/{uuid}/status` / `.../hits` —— 报告的子动作只有这两段，
    //     而**都不存在**（报告状态由复核结果回写，不单独开一条）
    //   * `ip-detections/reviews/{uuid}/status` —— 复核结论走 PUT 复核任务**本身**
    //   * `ip-detections/overview` / `ip-detections/stats` —— 汇总在 reports 清单里，不另开一条
    //   * 非 UUID 段 —— `ip-detections/detections/not-a-uuid` 必须 404
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ip\-detections(?:\/(?:capabilities|detections|alerts|remediations|reviews|external\-queries|inspection\-plans|inspection\-runs)|\/detections\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/alerts\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status|\/remediations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status|\/reviews\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/external\-queries\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/hits|\/status)?|\/inspection\-plans\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/inspection\-runs\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 12.1.1.2 应收账龄（finance 服务，卷 12「财务核算」L3 12.1.1 应收下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条
    //   alternation 里并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突
    //   （结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**第一个 `prefix: 'identity/'` 之前**（约第 300 行）----
    //   ⚠️ 本文件**不在** merge-union 驱动器的覆盖范围内（.gitattributes 只给
    //   `packages/api-contracts/src/**` 与 `module-progress.md` 配了驱动）⇒ 它走**普通 git merge**。
    //   同批三条 finance 车道如果都插在**数组末尾同一个位置**，三边插入不同内容 ⇒ **必然冲突**，
    //   下一轮并入时该车道会被标 conflict、整个模块交付被卡住一轮。
    //   ⇒ 本批三条车道的锚点必须**彼此相距 600 行以上**：
    //     本车道（12.1.1.2，首 token `receivable-aging`）→ 第一个 `identity/` 之前（~300 行）
    //     12.1.2.1（首 token `payables`）              → 第一个 `warehouse/` 之前（~1379 行）
    //     12.2.1.1（首 token `receipts`）                → 数组末尾（~10058 行）
    //   既有条目**一字未改、也不重排** —— 只是把本块从数组末尾搬到这里。
    //
    // ---- 首 token「receivable-aging」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `receivable-aging`
    //   这个串（12.1.1.1 那条的首 token 是 `receivables`，全不相同），
    //   所以「receivable-aging」既是独立字面量，也不会被既有分支的前缀吃掉。
    //   另核：全文件只有**一个** `prefix: 'finance/'`（12.1.1.1 的），
    //   它的正则是 `^receivables...`，匹配不到 `receivable-aging/...`。
    //   （三条 finance 条目的 prefix 都是 `finance/`，但首 token 互不相同 ⇒ 三个正则两两不相交；
    //     `resolve()` 对同一个 prefix 返回的 `{base: FINANCE_URL}` 也是同一个后端 ⇒
    //     **数组里的先后顺序不影响正确性**。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   receivable-aging/capabilities   能力声明（上游真接通 / 下游缺口归谁）
    //   receivable-aging/options        筛选项（币种 / 店铺 / 客户）+ 当前生效的账龄区间
    //   receivable-aging/distribution    账龄分布报表（GET，按店铺 / 客户 / 币种下钻）
    //   receivable-aging/overdue         逾期清单（GET，真分页 + 真筛选 + 汇总覆盖整个结果集）
    //   receivable-aging/buckets         账龄区间配置（GET 读 / PUT 整体替换）
    //
    // ⚠️ **本模块没有 id 形状的端点**：账龄区间配置是**整体替换**（PUT /buckets），
    //   不是逐档增删改 —— 档位之间必须连续覆盖 1 天起，这条约束只有对着整份配置才判得出来，
    //   逐档写会出现「改到一半的中间态」，落在缝里的应收会不属于任何一档。
    //   所以 `buckets/{code}` **刻意不放行**：后端没有这个映射。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   receivable-aging                     —— 裸路径：没有「一个不存在的汇总列表接口」
    //   receivable-aging/buckets/NOT_DUE      —— 没有逐档端点（配置是整体替换）
    //   receivable-aging/settlements         —— 🔴 平台结算（12.3.1）**未交付**
    //   receivable-aging/write-offs          —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   receivable-aging/collections          —— 🔴 催收预警（1.3.2.1）**未交付**
    //   receivable-aging/settlements/...     —— 同上
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^receivable\-aging\/(?:capabilities|options|distribution|overdue|buckets)$/,
  },
  {
    // 12.1.2.2 付款计划（finance 服务，卷 12「财务核算」L3 12.1.2 应付下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条
    //   alternation 里并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突
    //   （结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**第一个 `prefix: 'identity/'` 之前** ----
    //   ⚠️ 本文件**不在** merge-union 驱动器的覆盖范围内（.gitattributes 只给
    //   `packages/api-contracts/src/**` 与 `module-progress.md` 配了驱动）⇒ 它走**普通 git merge**。
    //   同批四条车道如果都插在**同一个位置**，四边插入不同内容 ⇒ **必然冲突**。
    //   ⇒ 本批四条车道的锚点必须**彼此相距 600 行以上**：
    //     本车道（12.1.2.2，首 token `payment-plans`）→ 第一个 `identity/` 之前（约 350 行）
    //     同批另外三条 → 「数组最开头」「第一个 `warehouse/` 之前」「数组末尾」
    //   既有条目**一字未改、也不重排** —— 只是把本块放到这个锚点上。
    //
    // ---- 首 token「payment-plans」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `payment-plans`
    //   这个串（全文件 grep 零命中），所以「payment-plans」既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //   另核：12.1.1.1 的首 token 是 `receivables`、12.1.1.2 是 `receivable-aging`、
    //   12.1.2.1 是 `payables` —— 与本条**两两不相交**。
    //   （finance 的几条条目 prefix 都是 `finance/`，但首 token 互不相同 ⇒ 正则两两不相交；
    //     `resolve()` 对同一个 prefix 返回的 `{base: FINANCE_URL}` 也是同一个后端 ⇒
    //     **数组里的先后顺序不影响正确性**。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   payment-plans                          付款计划表（GET，真分页 + 真筛选 + 真排序 + 汇总）
    //   payment-plans/capabilities             能力声明（付款执行 / 提醒推送未交付 + 归属模块）
    //   payment-plans/options                  筛选项（币种 / 状态 / 条件 / 供应商 / 两类规则）
    //   payment-plans/forecast                 资金需求预测（GET，按币种 × 到期窗口实时聚合）
    //   payment-plans/reminders                到期提醒清单（GET，真分页 + 真筛选）
    //   payment-plans/reminders/scan           扫描到期提醒（POST，真实写入口）
    //   payment-plans/reminders/{uuid}/acknowledge  确认提醒（POST）
    //   payment-plans/reminders/{uuid}/dismiss      忽略提醒（POST）
    //   payment-plans/batches                  付款批次清单（GET，真分页 + 真筛选）
    //   payment-plans/term-rules               账期规则（GET 读 / POST 新建）
    //   payment-plans/term-rules/{uuid}        改账期规则（PUT）
    //   payment-plans/milestone-rules          里程碑规则（GET 读 / POST 新建整套）
    //   payment-plans/milestone-rules/{uuid}   改里程碑规则（PUT，整套替换）
    //   payment-plans/generate                 生成付款计划（POST，只生成不排批次）
    //   payment-plans/schedule                 按供应商与到期窗口批量安排付款（POST）
    //   payment-plans/{uuid}                   单条计划详情（GET）
    //   payment-plans/{uuid}/cancel            取消计划（POST）
    //
    // ⚠️ **刻意不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   payment-plans/{uuid}/execute           —— 🔴 付款执行属 12.2.2.1，**未交付**
    //   payment-plans/{uuid}/pay               —— 同上
    //   payment-plans/{uuid}/settle            —— 同上
    //   payment-plans/payments                 —— 🔴 付款单属 12.2.2.1，**未交付**
    //   payment-plans/push                     —— 🔴 提醒推送属 1.3，**未交付**
    //   payment-plans/notifications            —— 同上
    //   payment-plans/not-a-uuid               —— 非 UUID 段不应被 uuid 分支吃掉
    //   payment-plans/{uuid}/not-a-shape       —— 多一层仍然 404
    //   payment-plans/capabilities/{uuid}      —— 字面量段刻意不接 id 段
    //   payment-plans/forecast/{uuid}          —— 同上
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / forecast / reminders / batches / term-rules /
    // milestone-rules / generate / schedule）排在 {uuid} 段之前判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^payment\-plans(?:\/(?:capabilities|options|forecast|reminders(?:\/scan)?|batches|term\-rules|milestone\-rules|generate|schedule|reminders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:acknowledge|dismiss)|term\-rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|milestone\-rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/cancel)?))?$/,
  },
  {
    // 12.3.1.2 交易明细（finance 服务，卷 12「财务核算」L3 12.3.1 平台结算下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**第一个 `prefix: 'identity/'` 那条元素之前** ----
    //   同批其它车道的插入点各不相同（数组最开头 / `prefix: 'warehouse/'` 之前 / 数组末尾），
    //   本车道落在这里，与它们的插入点相距 100 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「transactions」的零命中核对结论 ----
    //   ① 本文件既有 services[] 正则里**没有任何一条**含 `transactions` 这个串
    //      （既有 finance 支部的首 token 是 `receivables` / `receivable-aging` / `payables` /
    //      `receipts` / `payment-plans` / `bad-debt` / `settlement`，全不相同）；
    //   ② 逐条实测（不是目测、不是 grep 计数）：把本文件既有 services[] 的**全部 199 条正则**逐条抽出来
    //      （**198 条**既有 + 本车道新增的这一条），对本模块的**全部 7 个 path shape** 逐个
    //      `new RegExp(pat).test(path.slice(prefix.length))`（与 `resolve()` 同一套判别口径），
    //      结果是**零命中** —— 既没有既有条目会放行本模块的路径，
    //      也没有本模块的路径会被既有条目误吞。
    //      另测了**全部 13 个负向 shape**（未交付 / 不该存在的映射）：同样零命中。
    //      （实测脚本：`.tmp/verify-api-proxy-transactions.mjs`，随车道提交前可重跑复现。）
    //   ③ ⚠️ 首 token **刻意不用 `settlement-transactions`**：12.3.1.1 结算单管理已经占了
    //      `settlement`（正则 `/^settlement(?:\/(?:capabilities|options|shops|cycles|import|...))?$/`）。
    //      若本模块也用 `settlement` 开头，两条正则会互相吃掉对方的路径 ⇒ 页面会**静默 404**，
    //      而 typecheck / lint / vitest / Playwright **全部全绿**。
    //      `transactions` 与它不同首 token ⇒ 互不影响（双向断言见 transactions-allowlist.test.ts）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   transactions                     集合读（GET，真分页 + 真筛选 + 真排序 + 按币种/交易类型汇总）
    //                                   / 登记一条明细（POST）
    //   transactions/capabilities        能力声明（上游真接通 / 下游缺口归谁 / 平台交易报告同步不可用）
    //   transactions/options             筛选项（交易类型 / 类别 / 币种 / 来源 / 结算单 / 平台，全部来自真实数据）
    //   transactions/batch               批量登记明细（POST）
    //   transactions/import              上传平台交易报告（POST，CSV / JSON 真解析 + 真入库）
    //   transactions/reconciliation       汇总校验（GET，明细合计 = 结算单汇总，实时算出来的）
    //   transactions/{uuid}              单条明细详情（GET）
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / batch / import / reconciliation）排在 {uuid} 段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // ⚠️ **刻意不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   transactions/{uuid}/reconcile   —— 🔴 自动对账任务属 12.3.2.1，**未交付**
    //   transactions/{uuid}/match        —— 🔴 对账匹配属 12.3.2.1，**未交付**
    //   transactions/{uuid}/profit       —— 🔴 利润属 13，**未交付**
    //   transactions/{uuid}/sync         —— 🔴 平台交易报告自动同步属「卷 02 同步」，**未交付**
    //   transactions/{uuid}/edit         —— 明细行是 append-only 的外部事实，**没有**编辑端点
    //   transactions/{uuid}/delete       —— 同上，**没有**删除端点
    //   transactions/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   transactions/{uuid}/not-a-shape  —— 多一层仍然 404
    //   transactions/capabilities/{uuid} —— 字面量段刻意不接 id 段
    //   transactions/import/{uuid}       —— 同上
    //   transactions/reconciliation/{uuid} —— 同上
    //   transactions/batch/{uuid}        —— 同上
    //   transactions/settlement-transactions —— 首 token 写错（12.3.1.1 占的是 `settlement`）
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^transactions(?:\/(?:capabilities|options|batch|import|reconciliation|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 12.7.1.1 账户管理（finance 服务，卷 12「财务核算」L3 12.7 资金下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里
    //   并分支 —— 既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **`transactions` 那条之后**（即 `accounting-periods` 那条之前）----
    //   与本批其它车道的插入点（数组最开头 / `identity/` 之前 / `warehouse/` 之前 / 数组末尾）
    //   彼此相距 700 行以上 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「fund-accounts」的零命中核对结论（本车道实测）----
    //   本文件既有 services[] 正则里**没有任何一条**含 `fund-accounts` 这个串
    //   （同 L3 的 12.7.2.1 资金分析占的是 `fund-analysis`，首 token 不同 ⇒ 两个正则两两不相交，
    //   不会互相吃掉对方的路径）；本模块的正则对既有 finance 各条**零误伤**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   fund-accounts                     账户列表（GET）/ 建账户（POST）
    //   fund-accounts/capabilities        能力声明（上游真接通 / 下游缺口归谁）
    //   fund-accounts/options             筛选项（账户类型词表 + 真实币种 + identity 的法人清单）
    //   fund-accounts/balances/summary    余额汇总（**按币种分组**；跨币种合计恒为 null）
    //   fund-accounts/{uuid}              账户详情（GET）/ 改档案（PUT）
    //   fund-accounts/{uuid}/balances     余额历史（GET）/ 登记余额（POST，追加一条）
    //   fund-accounts/{uuid}/deactivate   停用账户（POST）
    //   fund-accounts/{uuid}/activate     启用账户（POST）
    //
    // ⚠️ **刻意不放行**：
    //   fund-accounts/{uuid}/delete       —— 资金凭据**不做物理删除**（删掉等于让「钱曾在这个账户里」
    //                                         从未发生过）；停用走 /deactivate。
    //   fund-accounts/reconciliation       —— 🔴 12.7.1.2 银行对账**未交付**（另一个模块）。
    //   fund-accounts/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉。
    //   fund-accounts/{uuid}/not-a-shape  —— 多一层仍然 404。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `balances` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / balances\/summary）排在 {uuid} 段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^fund\-accounts(?:\/balances\/summary|\/(?:capabilities|options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:balances|deactivate|activate))?))?$/,
  },
  {
    // 12.5.1.2 会计期间（finance 服务，卷 12「财务核算」L3 12.5.1 账簿与科目下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里
    //   并分支 —— 既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **`prefix: 'identity/'` 那条元素之前**（即 `transactions` 那条之后）----
    //   同批另外三条车道分别锚在「数组最开头」「第一个 `prefix: 'warehouse/'` 之前」「数组末尾」，
    //   与本处互不重叠 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「accounting-periods」的零命中核对结论（本车道实测）----
    //   本文件既有 **294** 条 services[] 正则（按 `pattern:` 逐条抽出实测）与本模块 **11** 个形状
    //   **逐一零命中**（`grep -c 'accounting-periods' apps/web/src/lib/api-proxy.ts` → **0**）；
    //   本模块的正则对其它车道已放行的前缀（`vouchers` / `receivables` / `settlement` /
    //   `transactions` / `account-subjects` …）也**零命中**（首 token 完全不同，且本正则首尾都锚定）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   accounting-periods                                期间列表（GET，真分页 + 真筛选 + 状态汇总）/ 登记自定义期间（POST）
    //   accounting-periods/capabilities                   能力声明（自然月 / 4-4-5 / 关账 / 重开 / 下游缺口归谁）
    //   accounting-periods/options                        筛选项（状态 / 期间类型 / 本位币 / 日历 / 财年）
    //   accounting-periods/calendars                      会计日历清单（GET）/ 新建日历（POST）
    //   accounting-periods/calendars/{uuid}               日历详情（GET）/ 改日历（PUT，**账期形状不可改**）
    //   accounting-periods/calendars/{uuid}/generate-periods 按日历生成一个财年的期间（POST）
    //   accounting-periods/{uuid}                         期间详情（GET：期间 + 关账记录 + 审批链路）/ 改备注（PUT）
    //   accounting-periods/{uuid}/close                   关闭期间（POST，**必须写明关闭原因**）
    //   accounting-periods/{uuid}/reopen                  重开期间（POST，**高风险：必须审批留痕**）
    //   accounting-periods/{uuid}/events                  期间事件留痕（GET）＝ 蓝图输出里的「关账记录」
    //   accounting-periods/{uuid}/approvals               期间审批链路（GET，identity 链路的本地镜像）
    //
    // 判别顺序：字面量子路径（capabilities / options / calendars）排在 {uuid} 段
    // **之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截
    //（`calendars` 尤其危险：它不是 uuid 形状，放到 {uuid} 段之后会被吃掉）。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   accounting-periods/{uuid}/delete       —— 🔴 期间是会计事实，**不可删**（错了只能关账或重开）
    //   accounting-periods/{uuid}/checklist    —— 🔴 关账检查清单属 12.5.1.3，**未交付**
    //   accounting-periods/{uuid}/lock         —— 🔴 关账锁定属 12.5.1.3，**未交付**
    //   accounting-periods/{uuid}/approve      —— 🔴 审批属 17 审批（identity），本模块**不提供**本地审批端点
    //   accounting-periods/batch               —— 本模块**没有**批量端点
    //   accounting-periods/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   accounting-periods/{uuid}/not-a-shape  —— 多一层仍然 404
    //   accounting-periods/capabilities/{uuid} —— 字面量段刻意不接 id 段
    //   accounting-periods/accounting-periods  —— 交替写错就会互相吃掉，必须反向钉住
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^accounting\-periods(?:\/(?:capabilities|options|calendars(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/generate\-periods)?)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:close|reopen|events|approvals))?))?$/,
  },
  {
    // 12.5.1.3 关账（finance 服务，卷 12「财务核算」L3 12.5.1 会计科目与期间下的第三格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**第一个 `prefix: 'identity/'` 元素之前** ----
    //   同批其它车道的插入点是「数组最开头」「第一个 `prefix: 'warehouse/'` 之前」与「数组末尾」，
    //   与本处互不重叠 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「period-close」的零命中核对结论 ----
    //   发车前用 `grep -n "period-close" apps/web/src/lib/api-proxy.ts` 实测：**零命中**（exit 1）。
    //   再把既有 services[] 的 **219 条正则**逐条抽出（脚本在 `.tmp/check-period-close.mjs`，
    //   逐字符扫出正则字面量 —— 正则内部允许 `//` 行注释，用「第一个 / 到下一个 /」会漏解析），
    //   对本模块 **10 个正向形状**与 **12 个负向形状**逐一实测：**全部零命中**。
    //   既有 finance 那几支的首 token 是 `receivables` / `receivable-aging` / `payables` /
    //   `receipts` / `settlement` / `transactions` / `vouchers` / `accounting-periods` /
    //   `account-subjects` / `cost-calculation` / `payment-orders` / `payment-executions` /
    //   `bad-debt` …，**全不相同** ⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   period-close/capabilities                          能力声明（上游真接通 / 下游缺口归谁）
    //   period-close/options                               筛选项 + 期间下拉
    //   period-close/checklists                            清单列表（GET，真分页 + 真筛选）/ 跑清单（POST）
    //   period-close/checklists/{uuid}                     清单详情（GET：四类检查项 + 按币种金额分组）
    //   period-close/checklists/{uuid}/items               明细钻取（GET，真分页）
    //   period-close/checklists/{uuid}/checks/{checkUuid}/carry-forward
    //                                                      把阻断项下调为可结转未清项（POST，**必须写例外依据**）
    //   period-close/executions                           关账执行记录列表（GET）/ 提交关账（POST）
    //   period-close/executions/{uuid}                    关账执行详情（GET：执行记录 + 审批链路）
    //   period-close/executions/{uuid}/sync-approval      同步审批结果（POST，批准后执行关账）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   period-close/filings / period-close/declarations —— 🔴 属 12.6.1.2 申报，**未交付**
    //   period-close/reports                             —— 🔴 属 12.5.2.2 报表，**未交付**
    //   period-close/tax-rates                            —— 🔴 属 12.6.1.1 税率配置
    //   period-close                                      —— 裸路径：后端**没有**「一条不存在的汇总列表接口」
    //                                                      （本模块的集合级端点是 /checklists 与 /executions，
    //                                                        顶层没有汇总列表 ⇒ 顶层组**不写成可选**）
    //   period-close/{uuid}                               —— 顶层没有 id 段
    //   period-close/checklists/{uuid}/not-a-shape        —— 多一层仍然 404
    //   period-close/checklists/not-a-uuid                —— 非 UUID 段不应被 uuid 分支吃掉
    //   period-close/executions/{uuid}/approve            —— 🔴 审批属 17 审批（identity），本模块**不提供**本地审批端点
    //   period-close/executions/{uuid}/reject             —— 同上
    //   period-close/batch                                —— 本模块**没有**批量关账端点
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `carry-forward` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / checklists / executions / items / checks）排在
    // {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ **顶层组不写成可选**（`period-close` 裸路径必须 404：本模块没有顶层汇总列表端点）。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^period\-close\/(?:capabilities|options|checklists(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:items|checks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/carry\-forward))?)?|executions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/sync\-approval)?)?)$/,
  },
  {
    // 14.2.1.2 分时策略（channel 服务，卷 14「广告与增长」的 L3 锚点 14.2.1）。
    //
    // 蓝图：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
    // 支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。
    // 输出：分时策略配置 + 定时调整任务。关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
    // **分时策略需按店铺时区配置**，跨时区店铺用错时区会完全失效。」
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 `channel/` 那条巨型
    //   alternation 里并分支 —— 既有条目**一字未改、也不重排**。
    //   本文件是多车道共用的累积型白名单，往同一条 alternation 里并分支会让多车道同时
    //   并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点：第一个 `prefix: 'identity/'` 条目的 `  {` 之前（调度者指定）----
    //   与同批 13.3.1.1（reporting）的锚点（数组最开头）相距 1000+ 行 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-dayparts」的零命中核对结论 ----
    //   本文件既有 251 个 `services[]` 元素的正则里**没有任何一条**含 `ad-dayparts`
    //   （既有的是 `ad-campaigns` / `ad-budgets` / `ad-rules` / `ad-keywords` /
    //   `ad-diagnostics` / `ad-promotions` 一类），
    //   且逐条用 `new RegExp(pat).test('…')` 实测过：既有元素对本模块的 14 个 shape
    //   **全部零命中** ⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   `ad-dayparts` 也不匹配 `ad-diagnostics` / `ad-campaigns` 等同族兄弟模块。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-dayparts/capabilities                  能力声明（时区口径 / 系数口径 / 上限 / 未交付维度）
    //   ad-dayparts/shops                         店铺清单 + 各自的时区归属结论（定不了的也列出来）
    //   ad-dayparts/shops/{shopCode}/timezone     单个店铺的时区归属结论
    //   ad-dayparts/strategies                    分时策略清单（GET，真分页 + 真筛选）
    //   ad-dayparts/strategies/{uuid}             策略详情（GET，含 24 小时曲线）/ 归档（DELETE）
    //   ad-dayparts/strategies/{uuid}/curve       整体替换分时曲线（PUT）
    //   ad-dayparts/strategies/{uuid}/status      改策略状态（POST：激活 / 暂停 / 归档）
    //   ad-dayparts/recommendations               生成推荐曲线（POST）/ 推荐批次列表（GET）
    //   ad-dayparts/recommendations/{uuid}        推荐批次详情（GET，含逐小时判定依据）
    //   ad-dayparts/recommendations/{uuid}/apply   把推荐曲线套到策略上（POST）
    //   ad-dayparts/tasks                         定时调整任务列表（GET，真分页 + 真筛选）
    //   ad-dayparts/tasks/run                     跑一轮（POST，**外部调度触发的一次入口**）
    //   ad-dayparts/tasks/{uuid}                  任务详情（GET，含逐条调整明细）
    //   ad-dayparts/caps                          调整上限读取形状（GET，供 14.2.2.2 自动规则取用）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   ad-dayparts                             —— 裸路径：本模块没有裸集合读，一切入口都带子路径。
    //   ad-dayparts/strategies/{uuid}/delete    —— 策略是配置事实，撤下走 status=ARCHIVED，
    //                                              不物理删除（曲线 / 基准 / 任务历史还要能复盘）。
    //   ad-dayparts/tasks/{uuid}/cancel          —— 任务状态由服务端推进，页面没有这个动作。
    //   ad-dayparts/recommendations/{uuid}/preview —— 变更预览属于 14.1.1.3 批量操作（未交付）。
    //   ad-dayparts/weekdays                     —— 按星期分曲线属 14.2.1.3（**未交付**），
    //                                              本模块的曲线是**按小时的**，放行它等于让界面以为已交付。
    //   ad-dayparts/platform-sync                —— 平台回写队列**未交付**，放行它等于让界面
    //                                              以为「已经回写平台」。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`：后者会把 `carry-forward` 这种非 UUID 形状也当成 id 放行）。
    // 字面量子路径（capabilities / caps / shops / strategies / recommendations / tasks）
    // 排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ **顶层组不写成可选**（`ad-dayparts` 裸路径必须 404：本模块没有顶层汇总列表端点）。
    // ⚠️ `{shopCode}` 段用**店铺编码形状**（字母数字 / 下划线 / 连字符，1..64 位），
    //   它不是 UUID —— 用 UUID 形状会把真实店铺编码挡在门外。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-dayparts(?:\/(?:capabilities|caps|shops(?:\/[A-Za-z0-9_\-]{1,64}\/timezone)?|strategies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:curve|status))?)?|recommendations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/apply)?)?|tasks(?:\/run|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))$/,
  },
  {
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      // audit 是 17.5.3.1 审计日志：列表、变更详情与 CSV 导出。
      // 导出走 GET + query（与列表同一套条件），所以 allowlist 只需放行读路径。
      // operation-log 是 17.5.3.2 操作日志：检索、单条详情、CSV 导出与链路关联视图。
      //   trace/{requestId} 放行但**不接 uuid 段**——请求 ID 可能是网关传来的非 UUID 串，
      //   用字符集而不是 {uuid} 段来界定。purge 是 POST 的留存期清理，
      //   同样在读路径正则里放行（前端页面只读，清理动作留给运维脚本调用）。
      // tenants 是 17.1.1.1 租户列表：只有 tenants 与 tenants/current 两个形状，
      //   **刻意不接 {uuid} 段** —— 按任意 tenant id 读取租户不是这个模块该有的能力，
      //   可切换集合只能由服务端从成员关系推导（实体目录 §4 的窄权限路径）。
      // department-leaders 是 17.2.1.2 部门负责人：负责人清单（没有负责人的部门也在列，
      //   因为它们要在审批流里配兜底）。departments/{id}/leader 下面挂 leader、proxies、
      //   grants 与 approval-chain 四类**已实现**的子资源：多放一段会让一个还不存在的
      //   路径看起来像已交付，所以逐段列出而不是一条通配。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // offboarding 是 17.2.2.3 离职与停用：只登记「停用」，不提供任何 DELETE，
      //   所以这一支没有删除动作分支。四种形状分别是案卷本身、撤销、
      //   责任转移条目（登记 / 执行转移）、回收条目（登记 / 执行回收），
      //   后两者的动作后缀各只认一个，多一层仍然 404。
      //   第一段 offboarding 是字面量，与 members/{uuid} 靠首段区分，不会互相吃掉。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // business-parameters 是 17.5.1.1 业务参数：三种形状分处同一前缀下——
      //   business-parameters（参数码表）/effective（生效值解析）/history（变更历史）
      //   都是单个字面量子路径；overrides 是集合读 + POST + {uuid} 的 PUT/DELETE；
      //   freezes 是 POST，读取是 {documentType}/{documentNo} 两段普通字符串。
      //   两段路径里都不含横杠，与 overrides 后只接一段 [0-9a-f-]+ 的形状不会互相吃掉。
      // numbering 是 17.5.1.2 编号规则：entities（单据实体码表）/rules（集合读 + POST）/
      //   rules/conflicts（冲突检测）/rules/{uuid}（GET·PUT·DELETE）/rules/{uuid}/next（取号）
      //   /preview（POST 预览）/sequences（计数器）/issued（已发放编号）。
      //   conflicts 与 {uuid} 靠第一段是字面量还是 UUID 区分；next 与 {uuid}/preview 不冲突。
      // approval-flows 是 17.4.1.1 审批流定义：approval-flows 集合读 + POST 新建；
      //   document-types / registrations / active / positions 是字面量子路径（**刻意不接 id 段**——
      //   那是读出厂码表与本租户注册情况，不按 id 寻址）；
      //   {uuid} 是单条流程的 GET·PUT·DELETE，其下的 validation 是结构体检（GET）；
      //   positions 下面挂 {uuid} 是岗位的 PUT/DELETE（岗位是被节点引用还是被删，按 id 寻址）。
      //   字面量段一律排在 [0-9a-f-]+ 之前判别：首段是字面量还是 UUID 两者不会互相吃掉。
      //   同样**没有**额外动作段：审批实例的发起与处理是 17.4.1.2 / 17.4.2.1 的职责，
      //   这里放行一个不存在的形状只会让界面以为已交付。
      // approval-rules 是 17.4.1.2 审批规则：approval-rules 是规则集集合读 + POST 新建；
      //   {uuid} 是规则集的 GET·PUT·DELETE，其下的 validation 是结构体检（GET）；
      //   effective-policy 是某单据类型的生效策略读出口，evaluate 是对已保存规则的只读试算（POST）。
      //   字面量段（effective-policy / evaluate）排在 [0-9a-f-]+ 之前判别，两类不会互相吃掉。
      //   同样**没有**审批实例相关的动作段：触发规则只产出规则集与生效策略，
      //   真正发起 / 处理审批是 17.4.2.1 的职责，放行一个不存在的形状只会让界面以为已交付。
      // my-approvals 是 17.4.2.1 待我审批：my-approvals 是列表读（筛选/排序/分页全在服务端）；
      //   capabilities 是对接状态读出口；batch 是批量审批/转交（POST）；
      //   {uuid} 是单条待办（GET）；其下 decide（POST 审批）与 transfer（POST 转交）两个字面量段。
      //   字面量段一律排在 [0-9a-f-]+ 之前判别，首段是字面量还是 UUID 两者不会互相吃掉。
      // approval-instances 是 17.4.2.1 的提交入口（POST）：各业务系统登记待审批用，
      //   与上面那些只读页面同属一次交付。
      //   **刻意不放行 approval-instances/{uuid}**：没有「按 id 取单条实例」这个端点。
      // approval-records 是 17.4.2.2 审批记录：集合读（筛选/排序/分页全在服务端）；
      //   statistics / capabilities / export 三个**字面量子路径**排在 [0-9a-f-]+ 之前判别；
      //   by-actor/{actorId} 是按人视角，第一段 by-actor 同样是字面量，排在 {uuid} 之前；
      //   {uuid} 是单条实例的完整链路（GET），字面量段与 UUID 段两类不会互相吃掉。
      //   **刻意不放行任何写动作段**：审批记录只读（上游只给 approval_instance_action 授
      //   SELECT,INSERT，从不授 UPDATE/DELETE），放行一个不存在的 POST/PUT/DELETE
      //   只会让界面以为已交付。
      // onboardings 是 17.2.2.2 成员开通与入职：列表、开通（创建）与完成入职。
      //   形状逐字列出：onboardings 是集合读 + POST 开通；
      //   onboardings/{id}/complete 是完成入职的状态流转（POST）。
      //   **刻意不放行 onboardings/{id}**：后端没有「按 id 取单条入职记录」这个端点，
      //   放行它会让界面以为已交付——放行不该存在的路径比 404 更糟。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/(?:[0-9a-f-]+\/accounting-books|references)|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|tenants(?:\/current)?|audit(?:\/(?:export|[0-9a-f-]+))?|operation-log(?:\/(?:export|trace\/[0-9a-zA-Z-]+|[0-9a-f-]+))?||approval-records(?:\/(?:statistics|capabilities|export|by-actor\/[0-9a-f-]+|[0-9a-f-]+))?|hierarchy-authorizations(?:\/check|\/[0-9a-f-]+(?:\/revoke)?)?|accounting-books(?:\/[0-9a-f-]+)?|business-parameters(?:\/(?:effective|history|overrides(?:\/[0-9a-f-]+)?|freezes(?:\/[^/]+\/[^/]+)?))?|numbering(?:\/(?:entities|preview|sequences|issued|rules(?:\/conflicts|\/[0-9a-f-]+(?:\/next)?)?))?|approval-flows(?:\/(?:document-types|registrations|active|positions(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/validation)?))?|approval-rules(?:\/(?:effective-policy|evaluate|[0-9a-f-]+(?:\/validation)?))?|my-approvals(?:\/(?:capabilities|batch|[0-9a-f-]+(?:\/(?:decide|transfer))?))?|approval-instances|onboardings(?:\/[0-9a-f-]+\/complete)?|department-leaders|departments\/[0-9a-f-]+\/(?:leader(?:\/(?:proxies|grants)(?:\/[0-9a-f-]+)?)?|approval-chain)|offboarding(?:\/[0-9a-f-]+(?:\/revert|\/responsibility-items(?:\/[0-9a-f-]+(?:\/transfer)?)?|\/reclaim-items(?:\/[0-9a-f-]+(?:\/reclaim)?)?)?)?|legal-entities\/(?:[0-9a-f-]+\/(?:accounting-books|ledger-books|default-book)|references)|accounting-books(?:\/[0-9a-f-]+(?:\/fact-assignments(?:\/[0-9a-f-]+)?)?)?)$/,
  },
  {
    // 1.3.2.1 预警规则（identity 服务，卷 01 · L3 1.3.2 预警 → 1.3.2.1）。
    //
    // 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 分支的 alternation 里并分支
    // —— 那些分支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'identity/'` 那条元素**之后** ----
    //   （与 14.5.1.2 建联与样品同一条锚点，同族 identity 条目挨着放。）
    //
    // ⚠️ **基线的 identity/ 条目并不覆盖本模块**（那是逐条列出的路由白名单，
    //   不是「identity/ 前缀下全放行」）：`alert-rules` 不在它的 alternation 里，
    //   所以本模块的每一个形状在基线上都是 404。实测方式是**逐条编译并命中**的脚本
    //   （与 resolve() 同构：先切掉 prefix 再 test），不是目测、不是 grep -c。
    //
    // 放行的形状（逐条列出，不放行「看起来像」但服务端不存在的形状）：
    //   alert-rules                      —— 生效规则清单（GET）
    //   alert-rules/capabilities         —— 能力声明：预置规则集 + 受控词表 + 引擎是否接通（GET）
    //   alert-rules/hits                 —— 命中历史（GET，**只读**：写入归 18 系统引擎）
    //   alert-rules/{uuid}               —— 单条规则的改与删（PUT / DELETE）
    //   alert-rules/{uuid}/disable       —— 关闭（POST）
    //   alert-rules/{uuid}/enable        —— 重新启用（POST）
    //   alert-rules/{uuid}/reset         —— 恢复预置默认值（POST）
    //
    // **刻意不放行**：
    //   alert-rules/not-a-uuid           —— 非 UUID 段不该被 uuid 分支吃掉
    //   alert-rules/{uuid}/extra         —— 服务端没有这一层，多放一段会让不存在的路径看起来像已交付
    //   alert-rules/capabilities/extra   —— 同上
    //   alert-rules/hits/extra           —— 同上
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `disable` / `enable` / `reset` 这种非 UUID 形状也当成 id 吃掉，
    // 于是 `/{uuid}/disable` 永远匹配不上。
    // 字面量子路径（capabilities / hits）排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回。
    //
    // ⚠️ 预置规则的 id 是**由规则码派生的确定性合成 id**（uuid v3 形状），
    //   同样落在这个完整 UUID 形状里，不需要额外的正则分支。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^alert\-rules(?:\/capabilities|\/hits|(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:disable|enable|reset))?)?)$/,
  },
  {
    // 14.5.1.2 建联与样品（channel 服务，卷 14 广告与增长 / L3 14.5.1 达人管理下的第二格）。
    //
    // 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 分支的 alternation 里
    // 并分支 —— 那些分支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，
    // retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'identity/'` 那条元素**之后** ----
    //   本仓历史上用过的锚点是「数组最开头」「`auth/` 前后」「`trade/` 之前」
    //   「`catalog/` 之前」「`warehouse/` 前后」「数组末尾」与「`purchase-followups` 之后」
    //   （14.5.1.1 达人库用的就是最后一个）。`identity/` 元素**之后**这个位置本仓
    //   还没有车道用过，与同批其它车道常见的锚点相距 2000 行以上 ⇒ git 能干净求并。
    //   锚点相邻的既有条目是 `identity/`（前缀 `identity/`）与 `price-anomaly-alerts`
    //   （前缀 `catalog/`）—— 前缀都不同，不会与本条目抢同一条路径。
    //
    // ⚠️ 首 token「outreach」的零命中核对结论：
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `outreach` 这个串
    //   （全文唯一的 `outreach` 出现在 14.5.1.1 那条条目的**注释**里 ——
    //   `influencers/outreach —— 建联与样品（14.5.1.2）未交付`，那是注释不是正则）。
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   resolve() 是「按顺序逐条匹配、首个命中即返回」，而本条目**之前**的三条
    //   `channel/` 元素（`ad-campaigns` / `ad-coupons` / `ad-flash-sales`）的正则
    //   都是 `^ad\-…` 锚定的，**零命中** `outreach/…` ⇒ 本条目不会被它们遮蔽。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   outreach/capabilities                能力声明（未交付段 available=false + ownerModule）
    //   outreach/options                     下拉项（状态 / 跟进方式 / 佣金形态 / 平台 / 店铺 / 模板变量）
    //   outreach/summary                     概览统计
    //   outreach/outreach-availability       逐平台的达人私信官方接口接入情况 + 今日发送数
    //   outreach/segments                    筛选条件（GET 列表 / POST 新建）
    //   outreach/segments/{uuid}             筛选条件（POST 改 / DELETE 删）
    //   outreach/segments/{uuid}/candidates  按规则筛出的候选达人（真筛选）
    //   outreach/templates                   邀约模板（GET 列表 / POST 新建）
    //   outreach/templates/{uuid}            邀约模板（POST 改 / DELETE 删）
    //   outreach/templates/{uuid}/preview    模板预览（真渲染）
    //   outreach/policies                    风控策略（按平台：每日上限 + 最小间隔）
    //   outreach/outreachs                   建联记录（GET 列表 / POST 新建）
    //   outreach/outreachs/{uuid}            建联记录（GET 详情 / POST 改 / DELETE 删）
    //   outreach/outreachs/batch             批量建联（**只生成草稿**，不是批量群发）
    //   outreach/outreachs/{uuid}/receipt    手动登记「已发送 + 平台回执」
    //   outreach/outreachs/{uuid}/send       走官方接口发送（适配器未接入 ⇒ 明确失败且不写数据）
    //   outreach/outreachs/{uuid}/status     推进建联状态
    //   outreach/outreachs/{uuid}/followups  登记合作跟进
    //   outreach/followups                   跟进列表（GET）
    //   outreach/samples                     样品单（GET 列表 / POST 新建）
    //   outreach/samples/{uuid}              样品单（POST 改）
    //   outreach/samples/{uuid}/status       手动推进样品单（只能到 OUTBOUNDED / CANCELLED）
    //   outreach/samples/{uuid}/shipments    登记样品包裹（发货 / 物流单号）
    //   outreach/shipments                   包裹列表（GET）
    //   outreach/shipments/{uuid}/status     推进包裹状态（送达 / 签收 / 丢件 / 退回）
    //   outreach/commission-rules            佣金规则（GET 列表 / POST 新建）
    //   outreach/commission-rules/{uuid}     佣金规则（POST 改 / DELETE 删）
    //   outreach/commission-accruals          佣金计提（GET 列表 / POST 登记）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `outreach` —— 本模块没有裸集合读，一切入口都带子路径。
    //     末尾**刻意不带 `?`**：带了等于放行裸路径。
    //   * `outreach/outreachs/{uuid}/batch-send` —— **批量群发不存在**：蓝图红线是
    //     「建联必须走官方接口，批量私信是平台风控重点」。批量能力只到
    //     `POST /outreachs/batch`（批量生成**草稿**），每一条仍要逐条登记回执才发出。
    //   * `outreach/outreachs/{uuid}/delete` —— 已发出的私信是举证凭据，删不掉（DELETE 打在
    //     `{uuid}` 上本身就是删整条，不是动作）。
    //   * `outreach/samples/{uuid}/shipments/{uuid}` —— 包裹是**样品单的子资源**，
    //     读包裹走 `outreach/shipments`（带 `sampleOrderId` 筛选）。
    //   * `outreach/attributions` —— 带货归因（14.5.2.1）**未交付**。
    //   * `influencers/outreach` —— 14.5.1.1 达人库那条条目里**刻意不放行**的形状：
    //     本模块的端点在前缀 `outreach/` 下，不在 `influencers/` 下。
    //   * 非 UUID 段 —— `outreach/outreachs/not-a-uuid` 必须 404。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `batch` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / summary / policies / followups /
    // outreach-availability / commission-accruals 那一组）排在 {uuid} 段之前判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^outreach(?:\/(?:capabilities|options|summary|outreach\-availability|followups)|\/segments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/candidates)?)?|\/templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/preview)?)?|\/policies|\/outreachs(?:\/batch|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:receipt|send|status|followups))?)?|\/samples(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|shipments))?)?|\/shipments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?|\/commission\-rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/commission\-accruals)$/,
  },
  {
    // 5.5.2.1 异常告警（**本条目是新增一条 services[] 元素**，不是往既有 catalog 分支的
    // alternation 里并**）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然判冲突
    // （结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 插入锚点固定在 `catalog/` SKU 列表那条元素**之前** —— 那样本车道与其他三条车道的
    // 插入点被隔开，git 能干净求并。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   price-anomaly-alerts                          —— 🔴 **刻意不放行裸路径**：后端没有
    //     「GET /catalog/price-anomaly-alerts」这个端点（集合读是 /events 与 /observations），
    //     放行后端不存在的路径比 404 更糟 —— 界面会以为功能已交付。
    //   price-anomaly-alerts/capabilities             能力声明（四个输入的真值 + 四类异常口径）
    //   price-anomaly-alerts/observations             价格观测台账（GET）+ 登记观测（POST）
    //   price-anomaly-alerts/events                   异常清单（GET）
    //   price-anomaly-alerts/events/{id}              异常详情（GET）
    //   price-anomaly-alerts/events/{id}/acknowledge  确认异常（POST）
    //   price-anomaly-alerts/events/{id}/resolve      消解异常（POST）
    //   price-anomaly-alerts/rules                   判定规则（GET）+ 保存规则（POST）
    //   price-anomaly-alerts/notifications           预警通知（集合读）
    //   price-anomaly-alerts/detect                  运行检测（POST）
    //
    // 🔴 `capabilities` / `observations` / `events` / `rules` / `notifications` / `detect`
    //   的首字母都不落在 [0-9a-f] 里，所以字面量子路径不会被 uuid 分支提前吃掉；
    //   uuid 分支只出现在 `events/` 下面，且**只接 acknowledge / resolve 两个动作**
    //   —— 不接的动作（不存在的后端端点）一律 404。
    //
    // 🔴 token 与既有条目**不撞**：既有 catalog 元素的首 token 是 products / attributes /
    //   brands / parties / categories / category-mappings / records / media / skus /
    //   unit-groups / unit-conversion / spec-axis / variant-combination / … 以及
    //   `sku-cost-compositions`（5.2.1.1 成本构成）；channel 元素的首 token 是 listings /
    //   listing-details / listing-performance / listing-changes / listing-bulk-migrations 等。
    //   本条目的首 token `price-anomaly-alerts` 与它们**首 token 全不相同**；
    //   且既有正则都是**锚定**的（`/^token(...)?$/`），所以两者互不遮蔽。
    //
    // 🔴 刻意**不放行**：5.5.1.1 价格历史（**未交付**）—— 它与本模块同属 L3「5.5 价格监控」，
    //   但端点命名空间不同，放行后端不存在的路径比 404 更糟。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^price-anomaly-alerts(?:\/capabilities|\/observations|\/events(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?|\/rules|\/notifications|\/detect)$/,
  },
  {
    // 16.6.2.1 数据出境与留存（**本条目是新增一条独立的 services[] 元素**，
    // 不是往既有 channel 巨型 alternation 里并分支 —— 同批多车道共享这一个文件，
    // 往同一段超长单行正则里并分支会让 git 必然判冲突，retry 不会自愈）。
    //
    // 插入锚点固定在 `// 5.5.1.1 价格历史` 那条元素**之前**（与本批其它三条车道的
    // 插入点彼此不重叠，git 能干净求并）。
    //
    // 🔴 ---- 首 token「data-governance」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**以 `data` 开头
    //   （既有的是 `defective` / `demand` / `development` / `discount` 一类，
    //   全部以 `^` 锚定），也没有任何一条含 `data-governance` 这个串。
    //   既有正则都是**锚定**的（`/^token(...)?$/`），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   data-governance/capabilities            能力声明（上游可用性 / 五套口径 / 边界）
    //   data-governance/domains                 数据域登记表（GET 列表 / PUT 登记修改）
    //   data-governance/transfers               跨境传输登记（GET 列表 / POST 登记）
    //   data-governance/transfers/{uuid}        单条传输登记（GET）
    //   data-governance/transfers/{uuid}/suspend 暂停传输（POST，理由必填）
    //   data-governance/transfers/{uuid}/end    终止传输（POST，理由必填；终态）
    //   data-governance/retention-policies      留存策略（GET 列表 / POST 登记新版本）
    //   data-governance/retention-conflicts     留存期裁决清单（GET；实时算出来的）
    //   data-governance/deletion-requests       删除请求（GET 列表 / POST 提交）
    //   data-governance/deletion-requests/{uuid}     单条删除请求（GET）
    //   data-governance/deletion-requests/{uuid}/approve 审批（POST）
    //   data-governance/deletion-requests/{uuid}/reject  驳回（POST，理由必填）
    //   data-governance/deletion-requests/{uuid}/execute 执行（POST；派发清理工单）
    //   data-governance/cleanup-orders          清理工单（GET 列表 / POST 登记）
    //   data-governance/cleanup-orders/{uuid}   单张清理工单（GET）
    //   data-governance/cleanup-orders/{uuid}/execute 回报执行结果（POST）
    //   data-governance/cleanup-records         清理执行记录（GET；append-only）
    //
    // 🔴 ---- 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟）----
    //   data-governance/deletion-requests/{uuid}/delete
    //     —— 删除请求**没有直接删除端点**：物理删除跨服务做不了，
    //        本模块产出的是**清理工单 + 执行记录**，直接删数据的入口不存在。
    //   data-governance/retention-policies/{uuid} / …/{uuid}/update
    //     —— 留存策略**不可原地改写**（改策略 = 登记新版本，旧条自动作废），
    //        没有「改一条已有策略」的端点。
    //   data-governance/cleanup-records/{uuid} / …/{uuid}/delete
    //     —— 清理执行记录是 **append-only** 凭据，没有单条读取与删除端点。
    //   data-governance/domains/{code}
    //     —— 数据域按**查询参数** `?domainCode=` 寻址，不接路径段
    //        （路径段留给真正的 UUID 实体 id，避免把业务编码混进 id 位）。
    //   data-governance/transfers/{非 uuid 段}
    //     —— id 段必须走 UUID 分支，字面量子路径一律 404。
    //
    // 🔴 ---- id 段用**完整 UUID 形状** ----
    //   `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `batch` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / domains / transfers / retention-policies /
    //   retention-conflicts / deletion-requests / cleanup-orders / cleanup-records）
    //   排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^data-governance(?:\/capabilities|\/domains|\/transfers(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:suspend|end))?)?|\/retention-policies|\/retention-conflicts|\/deletion-requests(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:approve|reject|execute))?)?|\/cleanup-orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/execute)?)?|\/cleanup-records)$/,
  },
  {
    // 5.5.1.1 价格历史（**本条目是新增一条 services[] 元素**，不是往既有 catalog 分支的
    // alternation 里并**）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然判冲突
    // （结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   price-history                          —— 🔴 **刻意不放行裸路径**：后端没有
    //     「GET /catalog/price-history」这个端点（集合读是 /changes 与 /timeline），
    //     放行后端不存在的路径比 404 更糟 —— 界面会以为功能已交付。
    //   price-history/capabilities             能力声明（价格 / 销量 / 利润三个维度的真值与缺口）
    //   price-history/changes                  价格变更时间轴（GET）+ 登记价格变更（POST）
    //   price-history/changes/{id}              单条变更详情（GET）
    //   price-history/timeline                 一个 listing（SKU × 店铺）的时间轴（GET）
    //   price-history/comparison               「降价到底有没有用」的价格 + 利润 + 销量对比（GET）
    //   price-history/sales                    一段区间的销量事实（GET；上游未接线 ⇒ 503）
    //   price-history/reasons                  变更原因字典（GET）+ 保存原因（POST）
    //
    // 🔴 `capabilities` / `changes` / `timeline` / `comparison` / `sales` / `reasons`
    //   的首字母都不落在 [0-9a-f] 里，所以字面量子路径不会被 uuid 分支提前吃掉；
    //   uuid 分支只出现在 `changes/` 下面，且**只接一段 uuid** ——
    //   不接的动作（不存在的后端端点）一律 404。
    //
    // 🔴 token 与既有条目**不撞**：既有 catalog 元素的首 token 是 products / attributes /
    //   brands / parties / categories / category-mappings / records / media / skus /
    //   unit-groups / unit-conversion / spec-axis / variant-combination / …
    //   `sku-cost-compositions`（5.2.1.1）与 `price-anomaly-alerts`（5.5.2.1）等。
    //   本条目的首 token `price-history` 与它们**首 token 全不相同**；
    //   且既有正则都是**锚定**的（`/^token(...)?$/`），所以两者互不遮蔽。
    //
    // 🔴 刻意**不放行**：price-history/changes/{id}/**改写**类动作 ——
    //   变更事实是**不可变事实**（只授 SELECT / INSERT，没有 UPDATE / DELETE 端点），
    //   改价要改就**再登记一条**，不在原地改写已经发生过的历史。
    //   放行一个不存在的写动作只会让界面以为已交付。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^price-history(?:\/capabilities|\/changes(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/timeline|\/comparison|\/sales|\/reasons)$/,
  },
  {
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      // skus 是 3.1.2.1 SKU 列表：分页检索、能力声明、批量操作与 CSV 导出。
      // unit-conversion 是 3.1.2.4 单位与换算：单位字典、SKU 换算版本、基础数量换算与单据快照。
      //   units 后面可选一段 id（字典行的更新/删除）；skus/{id} 取面板，skus/{id}/conversions 建换算版本；
      //   snapshots 后面不接 id —— 按 ?documentRef= 查询，快照是按单据号寻址的不可变行。
      // spec-axis 是 3.1.3.1 规格轴定义：租户级规格轴字典、选项、SPU 绑定与类目规则。
      //   axes/{id}/options 按 ?value= 删除，不接第三段 id；category-rules 用 query 传 specAxisId。
      // variant-combination 是 3.1.3.2 组合唯一性校验：组合清单、预校验、矩阵草稿。
      //   spus/{id} 下面挂 combinations / check / drafts；drafts/{draftId} 可选一段 id（详情与作废）。
      // attributes 是 3.2.1.1 属性字典：租户级字典分页、概览、选项集合与类目规则。
      //   与 products/{id}/attributes（3.2.2.1 商品的 SPU 属性）**不是同一个前缀**：
      //   后者第一段是 UUID，这里第一段是字面量 attributes，字典页不需要带商品 id。
      //   options 按 ?value= 寻址，不接第三段 id；category-rules 用 query 传 category/attributeId。
      //   `options`（不带 id 段）是 3.2.1.2 的**跨属性选项总览**：
      //   它与 `{uuid}/options` 靠第一段区分——一个是字面量 options，一个是 UUID。
      //   `options/lifecycle` 与 `options/order` 是 3.2.1.2 的启用停用与批量重排，两者都用 PUT。
      // sku-attribute-override 是 3.2.2.2 SKU 属性覆写：按 SKU 寻址的有效值解析面板。
      //   与 SkuCodeController / UnitConversionController 同形（`<模块>/skus/{id}`），
      //   所以第一段是字面量 sku-attribute-override，不是 products —— 两者不冲突。
      //   下面挂 attribute-overrides/{attributeId}（PUT 覆写、DELETE 取消覆写）
      //   与 attribute-overrides/{attributeId}/clear（PUT 显式清空）。
      // categories 是 3.3.1.1 分类树管理：多棵独立的分类树、多级层级、拖拽、启用停用、
      //   多语言分类名与 SPU 主/辅分类。
      //   第一段是字面量 categories，与 `products/{uuid}`（UUID）、`attributes`（字面量）
      //   三者互不冲突：products/attributes 首段是 products，其余两个首段各不相同。
      //   trees/{id}/nodes 后面**不接第三段 id** —— 一次取回整棵树（结构 + 路径串 + 商品数
      //   来自同一次查询，拆成多个端点会让前端拿到互相不一致的三个瞬间）。
      //   nodes/{id}/move 是拖拽落点（PUT），nodes/{id}/enabled 是启用停用（PUT）。
      // category-mappings 是 4.2.3.1 平台类目映射：内部分类 ↔ 平台类目的**多对多**映射，
      //   带 platform + site 两个独立维度，外加「平台某属性对应内部哪个属性」的规则。
      //   刻意**不挂 categories 之下**（虽然语义相邻）：分类树管「类目本身是什么」，
      //   本模块管「这个类目在某个平台某个站点对应哪个平台类目」，后者随平台改版频繁变动，
      //   挂在同一前缀下会让「改类目名」和「改平台映射」挤进一个页面。首段字面量不同，不会互吃。
      //   字面量子路径（overview / platform-sites / attribute-options / resolve）排在 [0-9a-f-]+ 之前判别。
      //   resolve 是按「内部分类 × 平台 × 站点」解析刊登目标与必填属性清单（4.2.1.1 表单生成的输入）。
      //   attributes/{ruleId}/enabled 是规则级启用停用；default 与 enabled 是映射级的两个动作段。
      //   spus/{id} 是 SPU 的主分类与辅助分类（GET 读、PUT 整体替换）。
      //   attribute-requirements 是 3.3.1.2 类目属性要求，按类目寻址：
      //   overview 是覆盖率概览；{categoryId} 是配置视图（GET）与规则保存（POST，同一路径两种方法）；
      //   {categoryId}/effective 只要生效规则集；entry-form-schema（POST 生成录入表单 schema）
      //   与 validate（POST 保存前逐字段校验）是本模块的两个输出；
      //   {categoryId}/{attributeId} 同一路径 DELETE 删规则、PUT .../enabled 启停。
      // brands 是 3.3.2.1 品牌管理：租户级品牌字典 + 授权凭证 + 刊登判定。
      //   第一段是字面量 brands，与 `products/{uuid}`（UUID）不冲突。
      //   overview / candidates 是只读聚合；listing-eligibility 有两条路径——GET 单个品牌
      //   与 POST 批量（id 数量由调用方决定，放请求体而不是拼进 URL，避免撞长度上限）。
      //   {id}/authorizations 下面挂 {authorizationId} 与 {authorizationId}/enabled（撤销，不是删除）。
      // unit-groups 是 3.3.3.1 计量单位（字典视角）：单位组 + 组内换算版本 + 换算查询。
      //   与 3.1.2.4 的 unit-conversion **并存不合并**：后者是 SKU 视角的换算与单据冻结快照，
      //   前者是字典视角的单位组，两条路径共用同一份 unit_of_measure 单位字典。
      //   {id}/conversions 下面挂 {conversionId}（DELETE 删已闭合的历史版本；
      //   当前开放生效的那一版后端会拒，否则等于静默让该单位不可换算）。
      //   convert 是 POST（带请求体），不是 GET + query——试算的数量与基准日放 body 更合适。
      // media 是 3.4.1.1 媒体库：素材上传与版本、分类标签、引用计数、失效检测。
      //   与 `records/assets` **不是同一个东西**：后者是 3.1.x 的通用记录面
      //   （落 product_asset，形状是 title/notes/reference），不是媒体表。
      //   overview / verify-sweep / references 是只读或聚合路径；
      //   {id} 下面挂 versions / references / integrity-checks / enabled；
      //   references 下面可再挂一段 {referenceId}（DELETE 解除引用）；
      //   tags 是标签字典，tags/{tagId} 后面可挂 enabled。
      //   上传走 POST + multipart（文件是字节，不能走 JSON），路径与其它 POST 同形。
      //   processing 是 3.4.1.2 图片处理：处理配方（目标平台规格）+ 异步处理任务。
      //   overview / recipes / jobs / assets/{id}/history 是聚合与只读路径；
      //   recipes/{id} 后面可挂 enabled；jobs/{jobId} 下面挂 items（条目与产物）与 cancel（请求取消）。
      //   提交走 POST /processing/jobs（JSON：配方 id + 素材 id 列表），语义是「已受理」
      //   而不是「已完成」——像素加工在线程池里跑，前端轮询任务详情看进度。
      // titles 是 3.4.2.1 标题库：标题模板（变量占位）+ 词根字典 + 批量生成批次 + 候选选用。
      //   与 `media`（3.4.1.x）不是一回事：那是二进制素材资产，这里是文本文案资产，
      //   被 04 刊登同时引用但维护节奏不同（素材跟拍摄批次走，文案跟平台规则走）。
      //   templates/{id} 与 keywords/{id} 后面各可挂 enabled；
      //   generations/{id} 下面挂 skus（逐 SKU 结果与未产出原因）；
      //   records/{id} 下面挂 select / deselect / reject（选用状态机：
      //   同一 SKU 同一平台只能选用一条，所以要能取消与淘汰）。
      //   records/export 是 CSV 导出，走 GET + query 而不是 POST——导出结果与当前筛选完全一致。
      //   提交走 POST /generations，语义是「已生成」而不是「已受理」：标题生成是纯字符串计算
      //   （重复度比较范围收在单个 SKU 内），几十毫秒就完事，所以返回时候选已经落库，
      //   前端**不轮询**。
      // descriptions 是 3.4.2.2 描述与卖点库：内容模板 + 多语言译文 + 卖点短语库 + 批量填充。
      //   与 `titles` 并列而不是合并：那边是**有平台硬字符上限的短文本**（矛盾是重复铺货判定），
      //   这边是**无字符上限的长文本**（矛盾是「变量缺失必须报告」）。排版约束不同，路由分开。
      //   templates/{id} 下面挂 enabled、translations（可只到二级，也可带 {translationId}）
      //   与 translation-coverage（GET + locale query：这几种语言能不能直接填）；
      //   phrases/{id} 与 renders/{id} 后面可挂 enabled / skus / missing-variables；
      //   translations/{id} 是**不带模板 id** 的一级寻址（与 phrases/{id} 同一形状：
      //   删一条译文时前端手上只有译文 id，而 URL 的首段是字面量 translations 而非 UUID）；
      //   sections 与 sections/export 分别是段落列表与 CSV 导出（GET + query，
      //   导出结果与当前筛选完全一致）。
      //   提交走 POST /renders，语义是「已填充」而不是「已受理」。
      // bundles 与 versions 是 3.5.1.1 套装版本管理：套装档案 + 版本（草稿/已发布）+ 生效区间 + 组件清单。
      //   与 titles / descriptions / translations 并列而不合并：那几套的「版本」是**文案版本**，
      //   这里的「版本」是**套装版本**，两者名字一样而语义完全不同，合并会让「v2 指哪个版本」无法回答。
      //   bundles 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的时刻查询**，
      //   04 刊登与 09 订单取版本走的就是它）、{id}/versions（某个套装的版本列表 / 建草稿版本）；
      //   versions 是跨套件的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、components（组件清单的读与整体替换）。
      //   虚拟套装不持有独立库存：扣的是组件库存（实体目录 §3.4），本模块不写库存。
      // components 与 bundle-skus 是 3.5.1.2 组件维护：跨版本组件清单 + 逐行增删改 +
      //   循环引用检测。与 versions 下的 components **并列而不合并**：
      //   PUT /versions/{id}/components 是 3.5.1.1 的「整体替换清单」，
      //   POST /versions/{id}/components 是本模块的「加一行」，两者语义不同必须能被 URL 区分；
      //   PUT|DELETE /components/{id} 改/删单行（带行级乐观锁 rowVersion）。
      //   components 下的 overview 是组件工作台概览，{id} 是单行组件详情。
      //   versions/{id}/component-check 是**结构化校验结果**（一次列全循环/数量非法/组件不存在），
      //   与「保存时报错」分开：保存只给一条原因，校验让运营一次改完而不是反复提交试错。
      //   bundle-skus 是「哪些 SKU 代表哪些套装」这条指向 —— 嵌套套装在数据上靠它表达，
      //   没有它「套装 A 含套装 B」无法表达，循环也就无从发生；改指向同样要过循环检测。
      // boms / bom-versions / bom-components 是 3.5.2.1 BOM 版本管理：物料清单（成品由哪些组件组成）
      //   的版本。与 bundles / versions **并列而不合并**：名字都叫「版本」但语义不同 ——
      //   那边是「几个 SKU 捆在一起卖」（套装：虚拟扣组件、不持有独立库存），
      //   这边是「这个成品要领哪些料」（BOM：产实物成品、有自己的成品 SKU）。
      //   合并会让「v2 指哪个版本」在 URL 上无法回答。
      //   boms 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的
      //   时刻查询**，06 采购建议与 08 加工单取版本走的就是它）、{id}/versions（建草稿版本）。
      //   bom-versions 是跨 BOM 的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、copy（版本复制：整份复制成新草稿，用量与损耗率都带走）、
      //   components（组件清单的读与整体替换）、requirement（**单层**需求量，规则 R7，不取整）。
      //   bom-components/usage 是**倒排查询**：某个 SKU 被哪些 BOM 版本当组件用着 ——
      //   改用量之前要先看这个方向。它不接 id 段（按 ?skuId= 寻址，首段是字面量 bom-components）。
      //   多级递归展开不在本模块：那是 3.5.2.2（BOM 展开）的独立职责。
      // bom-explosions 是 3.5.2.2 BOM 展开：**多层递归展开树**（子 BOM 当作组件继续往下走）、
      //   按仓库过滤、需求量/可用/在途/缺口，以及**展开运行记录**（读与列表）。
      //   展开结果会落库成一条运行记录，因为 06 的采购单引用的是**某一次展开的 id** ——
      //   「现在的 BOM × 现在的库存」随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
      //   与 bom-versions 并列而不合并：那边是「物料清单长什么样」，这边是「按它生产 N 个成品缺多少料」。
      //   它只有 {id} 段（读与列表），没有 overview 之类的子路径 —— 展开是一次动作不是一个子资源。
      //   循环引用返回 409（不放行任何路径去绕过它）。
      // parties 是 3.6.1.1 往来单位档案：业务主体（企业或个人）+ 角色指派
      //   （供应商 / 客户 / 承运商）+ 状态流转 + 软删除。
      //   与 products/** 并列而不挂在它下面：往来单位不是商品的属性，而是与商品平级的主数据
      //   —— 06 采购、11 售后、12 财务也引用它，而那些都不是商品页面。
      //   第一段是字面量 parties，与 products/{uuid}、attributes、brands 互不冲突。
      //   overview / candidates 是只读聚合（candidates 是 06/11/12 的下拉数据源，默认只给可用的）；
      //   {id}/status 是状态流转（进出拉黑要理由，**走独立端点**而不是复用 PUT {id}，
      //   否则「改个名字」也会被迫填理由）；
      //   {id}/roles 下面可挂 {roleId}（PUT 改状态备注、DELETE 解除指派）——
      //   角色指派与主体是「整体替换 + 单条增删改」两种语义并存，所以两条路径都要放。
      //   {id}/role-profiles 是 3.6.1.2 的汇总读出口（三个身份一次拿全，避免详情页拼出旧数据）；
      //   {id}/{supplier|customer|carrier}-profile 是 3.6.1.2 的角色档案读写（GET / PUT / DELETE），
      //   字面量三个而不是一条通配 —— 放行 {id}/xxx 的任意子段会让「一个还没实现的子路径」
      //   看起来像已交付（那正是本文件之前对这三条刻意不放行的原因）。
      // role-profiles 是 3.6.1.2 角色档案台账：overview（覆盖率 + 两个脏数据探针）、
      //   options（词表，与后端同源）、列表（按身份分页 + 缺档案筛选）。
      //   它与 parties 并列而不是挂在 {id} 下面：运营问的是「哪些供应商还没建档案」，
      //   主体不固定，所以那是集合资源。
      // supplier-quotes 是 3.6.2.1 供应商报价：报价台账 + 蓝图点名的「有效报价」输出。
      //   第一段是**字面量** supplier-quotes，与 products/{uuid}、parties、role-profiles
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出而不是一条通配：
      //     overview / options  —— 概览与词表（只读聚合）
      //     effective           —— 蓝图输出「有效报价列表」（歧义时 selected 为 null 并逐条标状态）
      //     effective/resolve   —— 06 采购带出价格走它，歧义时**必须 409**（规则 R2）
      //     {id}                —— 单条报价的读写（GET / PUT / DELETE）
      //     {id}/tiers          —— 阶梯价明细（编辑面板单独刷新阶梯时用）
      //   放行 {id} 之后任意一段会让「一个还没实现的子路径」看起来像已交付，
      //   所以 {id} 只带 /tiers 一个字面量子段。
      // translations 是 3.4.3.1 商品多语言内容：SPU/SKU 的多语言名称与描述 + 语言需求 + AI 建议。
      //   与 descriptions 并列而不是合并：那边带 locale 的是「描述模板与其渲染段落」的多语言，
      //   这边是「商品档案本身」的多语言（V22 文件头有完整边界说明）。
      //   locales/requirements 与 locales/sync 是「需要哪些语言由店铺启用站点推导」的一侧：
      //   sync 走 POST，02 渠道未接入时返回 409 并写明归属，不是一个 HTTP 错误页。
      //   spus/{id} 与 skus/{id} 是商品翻译工作台（可带 entries 建译文）；
      //   entries 下的 status 是人工校对（PUT，会记校对人与校对时刻），entries/export 是 CSV 导出；
      // export-sources 与 export-jobs 是 3.8.2.1 导出：能力声明（字段/排序/筛选词表）、
      //   导出记录（条件、字段、行数、时间、操作人）、以及 {id}/file 下载与 {id}/cancel 作废。
      //   第一段是字面量 export-*，与 products/{uuid}、import-jobs 三者互不冲突。
      //   {id} 后面最多再挂一段 file 或 cancel，不放行更深层 —— 放行一个还没实现的子路径
      //   会让它看起来像已交付。
      //   suggestions/{id}/adopt 与 /reject 是建议处置（POST，处置是终态）；
      //   batches 可带 {batchId} 读批次详情（逐条结果）。
      // import-jobs 是 3.8.1.1 导入向导的任务面，3.8.1.2 的校验报告挂在它的 report 子树上
      //   （报告不是独立资源：它是「某一个导入任务那一轮预校验结论」的视图，
      //   挂在任务下之后租户隔离只需守住一条父路径，父任务读不到则子报告读不到）。
      //   {id}/report 是总览（第一次 GET 顺带生成，之后原样返回快照 + 过期标记），
      //   同一个 POST 是显式重新生成；
      //   {id}/report/issues 是错误明细表（分页 + severity/category/rowNo 三种筛选，可组合）；
      //   {id}/report/exports 是导出的登记与下载（GET 列表 / POST 发起 / {exportId}/file 下载字节），
      //   文件走普通链接下载而不是 JSON，与模板下载同一处理方式。
      // completeness 是 3.9.2.1 完整度评分：评分台账 + 评分卡 + 缺失项清单 +
      //   批量补全入口 + 报关/认证声明读写 + 刊登/清关预检。
      //   第一段是字面量 completeness，与 products/{uuid}、parties、supplier-quotes
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出（不放行任意一段）：
      //     overview / options                     —— 概览与词表（六维度、分档、阈值）
      //     gaps / gaps/summary                    —— 缺失项清单（按缺失码聚合）与统计
      //     evaluations / evaluations/{id}          —— 评估快照（回答「当时为什么被拦住」）
      //     precheck/listing                       —— 4.2.3.2 刊登预检（POST，带 SPU 清单）
      //     precheck/customs                       —— 报关流程的清关预检（POST）
      //     customs/skus/{skuId}                   —— 报关属性声明（GET / PUT / DELETE）
      //     certifications/skus/{skuId}[/{certId}] —— 认证声明（一个 SKU 多条）
      //     SPU|SKU/{ownerId}                      —— 评分卡；主体类型写成字面量而不是
      //       {ownerType}，否则它会连带放行「一个还没实现的子路径」——
      // supply 是 06 采购与供应的**采购视角**供应商档案（6.1.1.1 列表 / 6.1.1.2 详情，
      //   catalog 服务，路由 /catalog/supply/vendors/**）。
      //   注意这整段此前**不在 allowlist 里**：6.1.1.1 交付时后端端点与页面都在，
      //   但浏览器侧请求会在代理层 404 —— 放行与否是「界面以为已交付」与「真的能调通」的区别。
      //   子路径逐字列出（不放行任意一段）：
      //     options / overview                   —— 6.1.1.1 清单与列表下钻
      //     {roleId}/profile                     —— 采购侧运营档案（PUT）
      //     {roleId}/deliveries[/{id}/void]      —— 到货事实（只追加 + 作废）
      //     {roleId}/quality-inspections[/{id}/void] —— 质检事实（只追加 + 作废）
      //     detail-options                       —— 6.1.1.2 词表
      //     {roleId}/panorama                    —— 6.1.1.2 全景
      //     {roleId}/purchase-history            —— 6.1.1.2 采购历史（分页 + 聚合）
      //     {roleId}/quotes                      —— 6.1.1.2 报价清单（只读）
      //     {roleId}/settlement-terms            —— 6.1.1.2 账期与开票（GET / PUT）
      //     {roleId}/qualifications[/{id}]       —— 6.1.1.2 附件资质（POST / PUT / DELETE）
      //   刻意**不**放行 6.3 采购单与 6.5 对账：那两个模块尚未交付，
      //   放行不该存在的路径比 404 更糟（界面会以为功能已交付）。
      //       放行不该存在的路径比 404 更糟：界面会以为功能已交付。
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|attributes(?:\/(?:overview|category-rules|options(?:\/(?:lifecycle|order))?|[0-9a-f-]+(?:\/(?:options|category-rules))?))?|brands(?:\/(?:overview|candidates|listing-eligibility|[0-9a-f-]+(?:\/(?:listing-eligibility|enabled|authorizations(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|parties(?:\/(?:overview|candidates|[0-9a-f-]+(?:\/(?:status|roles(?:\/[0-9a-f-]+)?|role-profiles|(?:supplier|customer|carrier)-profile|addresses(?:\/(?:defaults|candidates|[0-9a-f-]+(?:\/(?:usages|default|versions(?:\/[0-9]+)?))?))?|contacts(?:\/(?:default|candidates|[0-9a-f-]+(?:\/primary)?))?))?))?|categories(?:\/(?:overview|trees(?:\/[0-9a-f-]+(?:\/nodes)?)?|nodes\/[0-9a-f-]+(?:\/(?:move|enabled))?|spus\/[0-9a-f-]+|attribute-requirements(?:\/overview|\/[0-9a-f-]+(?:\/[0-9a-f-]+(?:\/enabled)?|\/(?:effective|entry-form-schema|validate))?)))|category-mappings(?:\/(?:overview|platform-sites|attribute-options|resolve|[0-9a-f-]+(?:\/(?:default|enabled|attributes(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|media(?:\/(?:overview|verify-sweep|references|tags|processing(?:\/(?:overview|recipes(?:\/[0-9a-f-]+(?:\/enabled)?)?|jobs(?:\/[0-9a-f-]+(?:\/(?:items|cancel))?)?|assets\/[0-9a-f-]+\/history)?)?)|[0-9a-f-]+(?:\/(?:versions|references(?:\/[0-9a-f-]+)?|integrity-checks|enabled))?|tags\/[0-9a-f-]+(?:\/enabled)?)?|skus(?:\/(?:capabilities|batch|export))?|unit-groups(?:\/(?:overview|convert|[0-9a-f-]+(?:\/(?:enabled|conversions(?:\/[0-9a-f-]+)?))?))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?|spec-axis(?:\/(?:overview|axes(?:\/[0-9a-f-]+(?:\/options)?)?|spus\/[0-9a-f-]+(?:\/axes(?:\/[0-9a-f-]+)?)?|category-rules))?|variant-combination(?:\/(?:overview|spus\/[0-9a-f-]+(?:\/(?:combinations|check|drafts(?:\/[0-9a-f-]+)?))?))?|sku-attribute-override\/skus\/[0-9a-f-]+(?:\/attribute-overrides\/[0-9a-f-]+(?:\/clear)?)?|titles(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|keywords(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|generations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?)?|records(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:select|deselect|reject))?)?))?|descriptions(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|translation-coveragetranslations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|phrases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:skus|missing-variables))?)?|translations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|sections(?:\/export)?))?|bundles(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|components|component-check))?)?|components(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?|bundle-skus(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|boms(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|bom-versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|copy|components|requirement))?)?|bom-components\/usage|bom-explosions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?)?|import-field-mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:mapping|prevalidate|execute|cancel|rows|errors|report(?:\/(?:issues|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/file)?))?))?)?|supplier-quotes(?:\/(?:overview|options|effective(?:\/resolve)?|[0-9a-f-]+(?:\/tiers)?))?|role-profiles(?:\/(?:overview|options))?|translations(?:\/(?:overview|locales(?:\/(?:requirements|sync|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?))?|spus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|entries(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|coverage|suggestions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adopt|reject))?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))|duplicate-scans(?:\/(?:overview|[0-9a-f-]+(?:\/candidates)?)?)?|duplicate-candidates\/[0-9a-f-]+(?:\/(?:decision|merge-preview|merge)?)?|export-sources|export-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:file|cancel))?)?|zombie-candidates(?:\/(?:overview|options|rules(?:\/[A-Za-z0-9_]+)?|evidence|reference-stats|deactivate|records|deactivation-targets))?|completeness(?:\/(?:overview|options|gaps(?:\/summary)?|evaluations(?:\/[0-9a-f-]+)?|precheck\/(?:listing|customs)|customs\/skus\/[0-9a-f-]+|certifications\/skus\/[0-9a-f-]+(?:\/[0-9a-f-]+)?|(?:SPU|SKU)\/[0-9a-f-]+))?|customs-attributes(?:\/profiles|\/skus\/[0-9a-f-]+(?:\/(?:submit|review))?|\/declaration-ready)|sku-merges(?:\/overview|\/by-candidate\/[0-9a-f-]+|\/by-sku\/[0-9a-f-]+|\/[0-9a-f-]+)?|description-templates(?:\/(?:overview|options|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|sections))?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?|kb(?:\/(?:overview|options|entries(?:\/[0-9a-f-]+(?:\/(?:enabled|translations(?:\/[0-9a-f-]+)?|translation-coverage|rule-bindings(?:\/[0-9a-f-]+)?|usages))?)?|translations\/[0-9a-f-]+|rule-versions(?:\/[0-9a-f-]+)?|review-tasks(?:\/[0-9a-f-]+)?|usages|hit-rates))?|customs-destination-versions(?:\/effective|\/requirements|\/skus\/[0-9a-f-]+|\/[0-9a-f-]+\/publish)|customs-declaration\/resolve|supply\/vendors(?:\/(?:options|overview)|\/[0-9a-f-]+(?:\/(?:profile|panorama|purchase-history|quotes|settlement-terms|deliveries(?:\/[0-9a-f-]+\/void)?|quality-inspections(?:\/[0-9a-f-]+\/void)?|qualifications(?:\/[0-9a-f-]+)?))?)?)$/,
  },
  {
    // 12.7.1.2 银行对账（finance 服务，卷 12「财务核算」L3 12.7.1 资金账户下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目（`receivables` / `receipts` / `fund-analysis` / `transactions` / `settlement` …）
    //   都是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在「第 1505 行那条 `1.1.2.3 履约健康（trade 服务…）」元素的 `{` 之前** ----
    //   同批其它三条车道（各自锚点）相距数百行 ⇒ git 能干净求并。
    //
    // ---- 首 token「bank-reconciliation」的零命中核对结论 ----
    //   发车前用 `grep -n "bank-reconciliation" apps/web/src/lib/api-proxy.ts` 实测：
    //   既有 225 条 services[] 正则里**没有任何一条**含 `bank-reconciliation` 这个串
    //   （唯一命中的是 `fund-analysis` 那条注释里的一句「🔴 银行对账（12.7.1.2）未交付」），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    //   🔴 **刻意不并进 `fund-analysis` 那条**：那是一条**封闭 alternation**
    //   （`exposure/measure|exposure/snapshots|exposure|capabilities|options|cash-flow-trend|
    //     currency-distribution|turnover-days|payment-concentration|liquidity-alerts|thresholds`），
    //   共用首段会让两条元素的正则纠缠、埋下互相吃掉的隐患 ⇒ 本模块用**自己的**首段。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   bank-reconciliation/capabilities                     能力声明（上游真接通 / 下游缺口归谁）
    //   bank-reconciliation/options                          筛选项（账户取自 12.7.1.1 真实档案 + 受控词表 + 本租户规则）
    //   bank-reconciliation/sessions                         对账批次列表（GET）/ 建批次（POST）
    //   bank-reconciliation/sessions/{uuid}                  批次详情
    //   bank-reconciliation/sessions/{uuid}/import           导入银行 / 支付平台流水（CSV / JSON 真解析）
    //   bank-reconciliation/sessions/{uuid}/match            跑自动匹配
    //   bank-reconciliation/sessions/{uuid}/statement-lines  银行流水行
    //   bank-reconciliation/sessions/{uuid}/system-lines     系统记录快照
    //   bank-reconciliation/sessions/{uuid}/matches          匹配结果
    //   bank-reconciliation/sessions/{uuid}/unmatched        未匹配清单
    //   bank-reconciliation/sessions/{uuid}/unmatched/{uuid}/claim    人工认领到具体单据
    //   bank-reconciliation/sessions/{uuid}/unmatched/{uuid}/exclude  说明后排除
    //   bank-reconciliation/sessions/{uuid}/claims          认领 / 说明记录（append-only 举证）
    //   bank-reconciliation/sessions/{uuid}/reconcile        生成对账报告并确认
    //   bank-reconciliation/sessions/{uuid}/report           对账报告（余额调节表）
    //   bank-reconciliation/sessions/{uuid}/events           事件流
    //   bank-reconciliation/rules                           匹配规则列表（GET）/ 新建规则（POST）
    //   bank-reconciliation/rules/{uuid}                    改匹配规则（PUT）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   bank-reconciliation                        —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   bank-reconciliation/not-a-shape             —— 不是本模块的任何形状。
    //   bank-reconciliation/sessions/not-a-uuid     —— 非 UUID 段不应被放行。
    //   bank-reconciliation/sessions/{uuid}/delete  —— 本模块**不做物理删除**（银行流水是外部事实）。
    //   bank-reconciliation/sessions/{uuid}/unmatched/{uuid}/unclaim —— 认领是**终态**，没有「撤销认领」。
    //   bank-reconciliation/rules/{uuid}/delete     —— 内置规则不能删（删掉会让「默认怎么配」这件事消失）。
    //   fund-analysis/bank-reconciliation           —— 🔴 那是别的形状（fund-analysis 的封闭 alternation），本模块**不**复用。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`：
    // 后者会把 `import` / `report` 这种非 UUID 形状也当成 id 放行）。
    // 字面量子路径（import / match / statement-lines / system-lines / matches / unmatched /
    // claims / reconcile / report / events）排在 {uuid} 段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^bank\-reconciliation\/(?:capabilities|options|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|sessions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:import|match|statement-lines|system-lines|matches|unmatched(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:claim|exclude))?|claims|reconcile|report|events))?)?)$/,
  },
  {
    // 12.3.2.1 对账任务（finance 服务，卷 12「财务核算」L3 12.3.2 对账下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目（`receivables` / `receipts` / `fund-analysis` / `transactions` / `settlement` …）
    //   都是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在「12.7.1.2 银行对账那条之后」** ----
    //   既有 finance 那几条的首 token 是 `receivables` / `receivable-aging` / `payables` /
    //   `receipts` / `fund-analysis` / `transactions` / `settlement` / `bank-reconciliation` …
    //   **全不相同** ⇒ 本模块的正则与它们两两不相交，不会互相吃掉对方的路径。
    //
    // ---- 首 token「reconciliation-tasks」的零命中核对结论（本车道实测）----
    //   发车前用 `grep -n "reconciliation-tasks" apps/web/src/lib/api-proxy.ts` 实测：
    //   既有 services[] 正则里**没有任何一条**含 `reconciliation-tasks` 这个串
    //   （既有含 `reconciliation` 的只有 `transactions/reconciliation` 与
    //   `fund-accounts` 注释里的一句「银行对账未交付」，两者首 token 都对不上）。
    //
    // 🔴 **刻意不并进 `bank-reconciliation` 那条**：那是 12.7.1.2 银行对账的封闭 alternation，
    //   共用首段会让两条元素的正则纠缠、埋下互相吃掉的隐患 ⇒ 本模块用**自己的**首段。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   reconciliation-tasks                  对账任务列表（GET）/ 建任务（POST）
    //   reconciliation-tasks/capabilities     能力声明（上游真接通 / 缺口归谁）
    //   reconciliation-tasks/options          筛选项（账户取自 12.7.1.1 真实档案 + 受控词表）
    //   reconciliation-tasks/{uuid}           任务详情（GET）/ 改对账范围（PUT）
    //   reconciliation-tasks/{uuid}/match     跑自动匹配（POST）
    //   reconciliation-tasks/{uuid}/matches   匹配结果
    //   reconciliation-tasks/{uuid}/differences 差异清单
    //   reconciliation-tasks/{uuid}/events    事件流
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reconciliation-tasks/{uuid}/differences/{uuid} —— 🔴 差异处理属 12.3.2.2（**未交付**）。
    //   reconciliation-tasks/{uuid}/resolve / acknowledge / write-off —— 同上（12.3.2.2 的动作面）。
    //   reconciliation-tasks/{uuid}/rerun —— 本模块**不做重跑**：匹配结果是一次性事实，
    //                                               重跑 = 新建一个任务（DB 触发器也会 409）。
    //   reconciliation-tasks/{uuid}/rules —— 匹配规则管理属 12.3.2.3（**未交付**，本模块不建规则表）。
    //   reconciliation-tasks/not-a-uuid       —— 非 UUID 段不应被 uuid 分支吃掉。
    //   reconciliation-tasks/{uuid}/not-a-shape —— 多一层仍然 404。
    //   reconciliation-tasks/capabilities/{uuid} —— 字面量段刻意不接 id 段。
    //   bank-reconciliation/reconciliation-tasks —— 🔴 那是别的形状，本模块**不**复用。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `match` / `matches` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^reconciliation\-tasks(?:\/(?:capabilities|options)|(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:matches|match|differences|events))?))?$/,
  },
  {
    // 12.3.2.2 差异处理（finance 服务，卷 12「财务核算」L3 12.3.2 对账下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **12.3.2.1 对账任务那条之后** ----
    //   12.3.2.2 是 12.3.2.1 的直接下游（L3 12.3.2 对账），挨着放最说得清；
    //   同批其它车道的锚点分别是「数组最开头」「第一个 `prefix: 'identity/'` 之前」
    //   「第一个 `prefix: 'warehouse/'` 之前」「数组末尾」，与本处相距上万行 ⇒ git 能干净求并。
    //
    // ---- 首 token「reconciliation-differences」的零命中核对结论（本车道实测）----
    //   发车前用 `.tmp/check-allowlist.mjs` 把本文件既有 **228** 条 services[] 正则**逐条抽出**，
    //   对本模块下面 11 个正向 shape 与 7 个负向 shape **逐条实测**：
    //   **既有正则对本模块的首 token 零命中**（`grep -c "reconciliation-differences"` = 0）。
    //   🔴 尤其确认过它**不吃**兄弟模块 12.3.2.1 的 `reconciliation-tasks`：
    //   两个首 token 在 `reconciliation-` 之后分岔（`tasks` vs `differences`），互不为前缀。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   reconciliation-differences                    差异处理队列（GET，真分页 + 真筛选 + 真搜索 + 按币种分组汇总）
    //   reconciliation-differences/capabilities       能力声明（上游真接通 / 下游缺口归谁）
    //   reconciliation-differences/options            筛选项（真实任务 / 币种 / 责任人 + 受控词表）
    //   reconciliation-differences/{uuid}             单条差异的处理详情（GET）
    //   reconciliation-differences/{uuid}/events      处理时间线（GET，真分页）
    //   reconciliation-differences/{uuid}/claim       认领并指定责任人（POST）
    //   reconciliation-differences/{uuid}/assign      改派责任人（POST）
    //   reconciliation-differences/{uuid}/classify    分类归因（POST，人工分类 + 理由）
    //   reconciliation-differences/{uuid}/adjustments 登记调整分录（POST，凭证或说明）
    //   reconciliation-differences/{uuid}/adjust      判定「已调整」（POST，终态）
    //   reconciliation-differences/{uuid}/write-off   判定「已核销」（POST，终态）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reconciliation-differences/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉。
    //   reconciliation-differences/{uuid}/not-a-shape  —— 多一层仍然 404。
    //   reconciliation-differences/capabilities/{uuid} —— 字面量段刻意不接 id 段。
    //   reconciliation-differences/options/{uuid}      —— 同上。
    //   reconciliation-differences/{uuid}/delete       —— 本模块**不做物理删除**
    //                                                       （闭环记录删掉 = 差异凭空消失）。
    //   reconciliation-differences/{uuid}/rerun        —— 没有「重跑」：闭环是终态，
    //                                                       重新处理要另建一条对账任务。
    //   reconciliation-difference/capabilities         —— 🔴 交替写错（少一个 s）就会互相吃掉，
    //                                                       必须反向钉住。
    //   reconciliation-tasks/{uuid}/differences/{uuid}  —— 🔴 那是 12.3.2.1 命名空间下的形状，
    //                                                       12.3.2.2 **有自己的命名空间**，不共用。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `claim` / `classify` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //   同理 `adjustments` 排在 `adjust` **之前**（`adjust` 是它的前缀，反了会被吃掉半截）。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^reconciliation\-differences(?:\/(?:capabilities|options)|(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adjustments|adjust|write-off|claim|assign|classify|events))?))?$/,
  },
  {
    // 12.3.2.3 自动匹配规则（finance 服务，卷 12「财务核算」L3 12.3.2 对账下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目（`receivables` / `receipts` / `fund-analysis` / `transactions` / `settlement` /
    //   `bank-reconciliation` / `reconciliation-tasks` …）都是单行超长正则，多车道同时并进去
    //   git 必然判冲突（结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点固定在「12.3.2.1 对账任务那条之后」** ----
    //   同 L3 的兄弟模块挨着放，语义上最清楚；本车道与别的车道插入点相距足够远，
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「reconciliation-match-rules」的零命中核对结论（本车道实测）----
    //   发车前用 `node` 抽出本文件既有 **228 条** services[] 正则逐条实测：
    //   本模块 9 个形状对既有正则**全部零命中**（NO HIT）。
    //   既有含 `reconciliation` 的只有 `reconciliation-tasks`（12.3.2.1）——
    //   首 token 就不是 `reconciliation-match-rules` ⇒ 两条正则**两两不相交**，
    //   不会互相吃掉对方的路径。
    //
    // 🔴 **刻意不并进 `reconciliation-tasks` 那条**：那是 12.3.2.1 的封闭 alternation，
    //   共用首段会让两条元素的正则纠缠、埋下互相吃掉的隐患 ⇒ 本模块用**自己的**首段。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   reconciliation-match-rules                    规则列表（GET）/ 建规则（POST）
    //   reconciliation-match-rules/capabilities       能力声明（上游真接通 / 缺口归谁 / 硬约束口径）
    //   reconciliation-match-rules/options            筛选项（账户取自 12.7.1.1 真实档案 + 受控词表）
    //   reconciliation-match-rules/monitoring         匹配率监控（GET：匹配率 + 未匹配原因分布）
    //   reconciliation-match-rules/{uuid}             规则详情（GET）/ 改规则（PUT）
    //   reconciliation-match-rules/{uuid}/activate    启用规则（POST）
    //   reconciliation-match-rules/{uuid}/deactivate  停用规则（POST）
    //   reconciliation-match-rules/{uuid}/events      规则事件流（GET，append-only 举证）
    //   reconciliation-match-rules/{uuid}/rate-snapshots 匹配率快照（GET 历史 / POST 记录一次）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reconciliation-match-rules/{uuid}/delete       —— 规则**不做物理删除**（停用即可，
    //                                                    历史快照与事件流要引用它）。
    //   reconciliation-match-rules/{uuid}/rules        —— 本模块没有「规则里再套规则」这个形状。
    //   reconciliation-match-rules/{uuid}/preview      —— 本模块**不提供**「试跑匹配」：匹配是
    //                                                    12.3.2.1 的动作，结果一次性不可改写。
    //   reconciliation-match-rules/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉。
    //   reconciliation-match-rules/{uuid}/not-a-shape  —— 多一层仍然 404。
    //   reconciliation-match-rules/capabilities/{uuid} —— 字面量段刻意不接 id 段。
    //   reconciliation-match-rule/capabilities         —— 单数前缀与后端路由不一致，必须 404。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `activate` / `events` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / monitoring）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^reconciliation\-match\-rules(?:\/(?:capabilities|options|monitoring)|(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:activate|deactivate|events|rate\-snapshots))?))?$/,
  },
  {
    // 1.1.2.3 履约健康（trade 服务，卷 01「工作台与门户」L3 1.1.2 关键指标下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 trade 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'identity/'` 那条元素（`dict-catalog`，第 776 行的 `{`）**之前** ----
    //   同批其它三条车道（1.1.1.2 异常订单队列 / 1.1.3.1 库存风险 / 1.2.1.1 全局搜索框）
    //   各自锚在数组最开头、`prefix: 'warehouse/'` 那条之前、数组末尾，
    //   与本处相距数百行 ⇒ git 能干净求并。
    //
    // ---- 首 token「fulfillment/health」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `fulfillment/health` 这个串
    //   （既有的是 `fulfillment/routing` / `fulfillment/allocation` / `fulfillment/dropship` /
    //   `fulfillment/pending-shipment` / `fulfillment/shipment-records` / `fulfillment/timeliness` …），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   fulfillment/health/capabilities   能力与口径声明（六个维度各自的算法/分母/来源，
    //                                       ODR 无上游 ⇒ available=false + ownerModule + impact）
    //   fulfillment/health/summary        健康度卡片汇总（每张卡带分子分母、灯色与判定依据）
    //   fulfillment/health/red-flags      红灯项明细（GET，真分页 + 真筛选；口径与卡片同源）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   fulfillment/health                 —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   fulfillment/health/**写端点**      —— 本命名空间下**没有任何 POST/PUT/DELETE**：
    //                                        卷 01 的红线是「工作台只读 + 跳转，一切状态变更回到归属系统完成」。
    //   fulfillment/health/red-flags/{id}  —— 红灯项不按 id 取单条，它们是清单里的一行。
    //
    // 「平均出库时长」那一格走 08 仓储作业自己的只读报表
    // （`warehouse/outbound-timeliness/report`），**既有白名单已放行**，此处不重复。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern: /^fulfillment\/health\/(?:capabilities|summary|red-flags)$/,
  },
  {
    // 17.5.2.1 数据字典（identity 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 identity 那条巨型 alternation（`permissions` / `roles` / `legal-entities` /
    // `accounting-books` / `business-parameters` / `numbering` / `approval-*` …）**一字未动、
    // 也不重排** —— 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，
    // 往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突（结构性，
    // retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `// 14.2.2.1 预算分配（channel 服务）` 那条**之前** ----
    //   同批其它车道的锚点分别是「`// 5.5.1.1 价格历史` 之前」「`// 07 库存：库存总览` 之前」
    //   「`// 2.4.2.3 映射缺失队列` 之前」「数组末尾」，与本处相距 200 行以上，git 能干净求并。
    //
    // ---- 首 token「dict-」的零命中核对结论 ----
    //   `grep -c 'dict\-' apps/web/src/lib/api-proxy.ts` = **0**（基线全文 173 条既有正则里
    //   没有任何一条含 `dict` 这个串）⇒ 首 token 是独立字面量，既不会被既有分支的前缀吃掉，
    //   也不会吃掉它们（`^dict\-` 与既有任何首 token 互不为前缀）。
    //
    // ---- 逐条核对过的后端端点清单 ----
    //   backend/services/identity/src/main/java/com/starcon/identity/DictionaryController.java
    //   （类级 `@RequestMapping("/identity")`）逐条抄下来：
    //     GET    /dict-catalog                   字典目录（全局码表 + 每个字典的字典项计数）
    //     GET    /dict-items                     字典项列表（可按 dictCode / status / search 过滤；
    //                                             默认**包含**停用项）
    //     GET    /dict-items/options/{dictCode}  下拉选项（只给 ACTIVE）
    //     GET    /dict-items/{id}                单条字典项（`@PathVariable UUID id`）
    //     GET    /dict-items/{id}/usage          引用台账明细（按来源系统列出）
    //     GET    /dict-history                   变更历史（只追加、不可改）
    //     POST   /dict-items                     登记字典项
    //     PUT    /dict-items/{id}                改字典项
    //     POST   /dict-items/{id}/deactivate     停用
    //     POST   /dict-items/{id}/reactivate     启用
    //     POST   /dict-items/{id}/usage          登记一条引用（下游经授权 API 回报用量）
    //   以上 **11 条全部**在后端存在，本条**逐条放行**。
    //   （前端目前用到其中 9 条：`GET /dict-items/options/{dictCode}` 与
    //   `POST /dict-items/{id}/usage` 后端已交付但前端还没调，一并登记免得下次再漏。）
    //
    //   ⚠️ 后端**没有** DELETE 端点 —— V53 迁移的四道防线之一（控制器不提供 DELETE、
    //     迁移只授予 SELECT/INSERT/UPDATE、dict_item_usage.item_id 外键 ON DELETE RESTRICT、
    //     dict_item_delete_guard() 触发器在数据库层挡掉一切 DELETE）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `dict-items/{uuid}/delete`      —— 后端没有删除端点；字典项只能停用。
    //   * `dict-items/{uuid}/archive`     —— 后端只有 activate / deactivate 两个动作。
    //   * `dict-items/{uuid}/enable`      —— 启用的正式名字是 `reactivate`。
    //   * `dict-items/options`（缺 dictCode）—— 后端没有「所有字典的选项」这个形状，
    //                                           下拉必须指明是哪一本字典。
    //   * `dict-catalog/{uuid}`            —— 目录是集合读，没有「按 id 取一条目录」。
    //   * `dict-history/{uuid}`            —— 历史是集合读（可按 dictCode / itemId 过滤），
    //                                           没有单条形状。
    //   * `dict-items/not-a-uuid`         —— 非 UUID 段不应被 uuid 分支吃掉。
    //   * `dict-items/{uuid}/usage-list`  —— 引用台账就是 `/usage` 这一个形状。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `options` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ `{dictCode}` 段用**受控值域** `[A-Z][A-Z0-9_]{0,63}`：字典编码是平台随代码发布的
    //   **全局码表**（V53 迁移 `dict_catalog.code varchar(64)`，种子是 ORDER_STATUS /
    //   SHELF_STATUS / ORDER_CANCEL_REASON / AFTERSALE_REASON / INVENTORY_ADJUST_REASON /
    //   PRODUCT_CATEGORY 等大写蛇形）。值域是**开放**的 ——「加一类字典只是往这张表加一行」，
    //   CHECK 跟着放宽即可 —— 所以这里用紧收紧的字符集而不是写死枚举；
    //   **绝不用** `[^/]+`（那等于把任意路径都当成字典编码放行）。
    //   后端 `dictCode.trim()` 是**大小写敏感**的精确匹配，所以只放行大写形态。
    // ⚠️ 字面量子路径（`options`）排在 `{uuid}` 段之前判别 —— alternation 是首个命中即返回，
    //   顺序反了会被通配支吃掉半截。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^dict\-(?:catalog|history|items(?:\/options\/[A-Z][A-Z0-9_]{0,63}|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:usage|deactivate|reactivate))?)?)$/,
  },
  {
    // 1.3.1.1 通知列表（identity 服务，卷 01「工作台与门户」L3 1.3.1 消息与预警的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 那条巨型 alternation 里并分支 ——
    //   既有条目**一字未改、也不重排**（本文件是多车道共用的累积型白名单，
    //   每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让多车道并入时 git 必然判冲突）。
    //
    // ---- 插入锚点固定在 `17.5.2.1 数据字典（identity 服务）` 那条元素**之后** ----
    //   按内容定位：该元素的 pattern `/^dict\-(?:catalog|history|items…)$/` 全文唯一。
    //   同批并发车道是 12.3.2.1（finance）、16.3.1.2（channel）、1.1.3.2（不改 api-proxy），
    //   都不落在这个 identity 簇 ⇒ 相距足够远，git 能干净求并。
    //
    // ---- 首 token「notification-inbox」的零命中核对结论（.tmp/zero-hit-check.mjs 实测）----
    //   既有 **224** 条 services[] 正则里含 `notification` 串的只有 **2** 条：
    //     `price-anomaly-alerts/notifications`（catalog 5.5.2.1 价格异常预警）
    //     `logistics-pickup-signoff/notifications`（channel 物流签收的下游待通知清单）
    //   两条都是**别的模块的 notification 子路径**，首 token 与 `notification-inbox` 不同 ⇒ 互不吃掉。
    //   本模块的 5 个形状对既有 224 条正则**逐一实测零命中**；
    //   既有 31 条 `prefix: 'identity/'` 的正则对本模块的 5 个形状也**全部零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   notification-inbox/capabilities   能力声明（受控类型词表 + 批量上限 + 未交付的相邻模块）
    //   notification-inbox/read-state     查一批通知的已读状态（POST：要查的键集合可能几百条，
    //                                      GET 的 URL 扛不住这个长度）
    //   notification-inbox/read           批量标记已读（POST，只对点名的那几条生效）
    //   notification-inbox/unread         批量取消已读（POST，幂等）
    //   notification-inbox/subscriptions  订阅类型（GET 读生效值 / PUT 整体替换）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   notification-inbox                 —— 裸路径：后端**没有**「一条汇总列表接口」，
    //                                          通知内容是各域的只读投影，前端聚合层自己取。
    //   notification-inbox/read-state/all  —— 🔴 **刻意没有**「把本租户全部通知标已读」这种端点：
    //                                          批量已读只对**点名**的键生效。
    //   notification-inbox/preferences     —— 🔴 1.3.1.2 通知偏好（渠道 / 免打扰）**未交付**。
    //   notification-inbox/rules           —— 🔴 1.3.2.1 预警规则**未交付**。
    //   notification-inbox/push            —— 🔴 1.3.2.2 外部渠道推送**未交付**。
    //   notification-inbox/not-a-shape     —— 不是本模块的任何形状。
    //
    // ⚠️ 本模块**没有 id 段**（通知键是「类型 + 上游主键」的复合键，走请求体），
    //   所以「id 段用完整 UUID 形状」这条约定在此不适用。
    // ⚠️ `read-state` 排在 `read` **之前**判别 —— alternation 是首个命中即返回，
    //   顺序反了会被 `read` 这支吃掉半截（`read` 后面多一段 `-state` 就 404 了）。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^notification\-inbox\/(?:capabilities|read\-state|read|unread|subscriptions)$/,
  },
  {
    // 1.3.1.2 通知偏好（identity 服务，卷 01「工作台与门户」L3 1.3.1 消息与预警的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 那条巨型 alternation 里并分支 ——
    //   既有条目**一字未改、也不重排**（本文件是多车道共用的累积型白名单，
    //   往同一条 alternation 里并分支会让多车道并入时 git 必然判冲突）。
    //
    // ---- 插入锚点：1.3.1.1「通知列表」那条元素**之后** ----
    //   该元素的 pattern `/^notification\-inbox\/(?:capabilities|read\-state|read|unread|subscriptions)$/`
    //   在本文件**全文唯一**，据此定位。两条同属 1.3.1 兄弟模块，挨着放便于日后核对。
    //
    // ---- 首 token「notification-preferences」的零命中核对结论（.tmp/zero-hit-check.mjs 实测）----
    //   本模块的 3 个形状对既有 services[] 正则**逐条实测零命中**；
    //   首 token 与 `notification-inbox` 只差一个连字符，**不会**互相吃掉：
    //   `notification-inbox` 那支要求整段就是 `notification-inbox/...`，
    //   而本模块的路径首段是 `notification-preferences`（`inbox` ≠ `preferences`）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   notification-preferences              我的通知偏好（GET 读生效值 / PUT 保存 / DELETE 重置）
    //   notification-preferences/capabilities  能力声明（渠道词表 + 不可关闭的底线清单 + 边界）
    //   notification-preferences/resolve      解析某一类通知现在该走哪些渠道（POST，供 1.3.2.2 过滤）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   notification-inbox/preferences     —— 🔴 1.3.1.1 自己那条元素里刻意不放行的形状：
    //                                          本模块的路径是 `notification-preferences`，
    //                                          不是 `notification-inbox/preferences`（那一支仍然 404）。
    //   notification-preferences/push       —— 🔴 1.3.2.2 外部渠道推送**未交付**。
    //   notification-preferences/rules      —— 🔴 1.3.2.1 预警规则**未交付**。
    //   notification-preferences/{anything} —— 偏好是一个**整体资源**（一份配置），
    //                                          没有「按 id 取一条偏好」的形状。
    //
    // ⚠️ 本模块**没有 id 段**：偏好是「一个人一份」的整体配置，没有路径参数。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^notification\-preferences(?:\/(?:capabilities|resolve))?$/,
  },
  {
    // 1.1.1.3 审批待办（identity 服务，卷 01「工作台与门户」L3 1.1.1 待办与待决策的第四格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **identity/ 的「17.5.2.1 数据字典」那条之后** ----
    //   锚点元素的 pattern 以 `/^dict\-(?:catalog|history|items` 开头，在本文件**全文唯一**。
    //   本批其它常见锚点（数组最开头、identity 前缀那两处之前、数组末尾）
    //   与本处相距均在 900 行以上 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「approval-todos」的零命中核对结论 ----
    //   用脚本把本文件既有 **260** 条 services[] 正则**逐条实测**（不是目测、不是 grep 计数）：
    //   对本模块的 7 个 shape（approval-todos / …/capabilities / …/options / …/{uuid} /
    //   …/not-a-uuid / …/{uuid}/decide / …/{uuid}/transfer）**全部零命中** ⇒
    //   首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   既有含 "approval" 的是 `approval-records` / `approval-flows` / `approval-rules` /
    //   `my-approvals` / `approval-instances`（都挂在 identity/ 下），首 token 全不相同。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   approval-todos    待审批清单（GET，真分页 + 真筛选 + 真排序 + 整集合分档 + 能力声明）
    //
    // ⚠️ **本模块是只读的**：蓝图 1.1.1.3 原文「工作台不执行审批动作，避免两处审批逻辑不一致」，
    //   后端**一个写端点都没有** ⇒ 这里**一个写形状都不放行**。
    //   能力声明（filters / actions）与分档（documentTypes / businessDomains / currencies /
    //   amountBranches）都**在同一个响应体里** ⇒ **没有** `/capabilities`、`/options` 子路径。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   approval-todos/capabilities          —— 后端没有这个端点（能力声明在清单响应体里）
    //   approval-todos/options               —— 同上（分档在清单响应体里）
    //   approval-todos/{uuid}                —— 没有单条待办详情端点
    //   approval-todos/{uuid}/decide         —— 🔴 审批动作归 17.4.2.1，工作台不执行审批动作
    //   approval-todos/{uuid}/transfer       —— 同上（转交也归 17）
    //   approval-todos/{uuid}/copy           —— 复制单号是页内动作，没有端点
    //   approval-todos/not-a-uuid            —— 非 UUID 段不应被放行
    //
    // ⚠️ id 段**根本不存在**：本模块只有一个集合读端点，没有「按 id 取单条」的形状，
    //   所以这里**不需要** uuid 段，也就没有「非 UUID 段被 uuid 分支吃掉」这一类风险。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^approval\-todos$/,
  },
  {
    // 14.2.2.1 预算分配（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-rules`（14.2.2.2）、`ad-keywords`（14.1.3.1）
    // 等**一字未动、也不重排** —— 本文件是多车道共用的累积型白名单，每条服务规则是一段
    // 超长单行正则，往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突
    // （结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `sku-cost-compositions`（5.2.1.1，`prefix: 'catalog/'`）那条**之前** ----
    //   同批其它车道的插入点分别是「`ad-campaigns` 那条不动」「`location-allocations` 那条之前」
    //   与「`compliance-precheck` 那条之前」，彼此相距数百行 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-budgets」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-budgets` 这个串
    //   （既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-budgets/capabilities              能力声明（利润维度 available=false + ownerModule + 影响）
    //   ad-budgets/options                   下拉项 + 护栏上限 + 可用指标日期区间
    //   ad-budgets/rules                     分配规则集合读（GET，真分页 + 筛选）/ 登记规则（POST）
    //   ad-budgets/rules/{uuid}              规则详情（GET，含权重位与销售目标）
    //   ad-budgets/pools                     预算池集合读（GET）/ 登记预算池（POST）
    //   ad-budgets/pools/{uuid}              预算池详情（GET）
    //   ad-budgets/plans                     分配方案集合读（GET）/ 计算并落库一份方案（POST）
    //   ad-budgets/plans/{uuid}              方案详情（GET，含明细 + 未纳入清单 + 再平衡建议）
    //   ad-budgets/rebalances                再平衡建议集合读（GET，真分页 + 筛选）
    //   ad-budgets/tasks                     执行任务集合读（GET）/ 生成任务（POST）
    //   ad-budgets/tasks/{uuid}              任务详情（GET，含逐条结果）
    //   ad-budgets/tasks/{uuid}/apply        执行任务（POST，逐条落预算 + 逐条留痕）
    //
    // 判别顺序：字面量子路径（capabilities / options / rebalances / plans / pools / rules / tasks）
    // 排在 {uuid} 段之前 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-budgets` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-budgets/plans/{uuid}/rebalance` —— 再平衡建议随方案详情一起返回，
    //     另有集合读 `ad-budgets/rebalances`，没有按方案 id 取再平衡的独立形状
    //   * `ad-budgets/tasks/{uuid}/cancel` —— 执行任务**不可撤销**（跑了就是跑了，
    //     要改只能再算一份新方案），放行一个不存在的动作只会让界面以为已交付
    //   * `ad-budgets/rules/{uuid}/delete` —— 规则是配置事实，撤下走 status=ARCHIVED
    //   * `ad-budgets/plans/{uuid}/approve` —— 方案状态由执行任务推进，本模块没有审批动作
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-budgets`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-budgets(?:\/(?:capabilities|options|rebalances|plans(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|pools(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/apply)?)?))$/,
  },
  {
    // 5.2.1.1 成本构成：SKU 的单位成本构成（分项 + 合计 + 生效时间）与成本变动历史。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   sku-cost-compositions                                 构成台账（集合读；筛选/分页在服务端）
    //   sku-cost-compositions/capabilities                    能力声明（五个分项的来源 + 三种成本法）
    //   sku-cost-compositions/{skuId}                         构成明细（GET）+ 保存构成（PUT）
    //   sku-cost-compositions/{skuId}/adjustments             追加手工调整项（POST）
    //   sku-cost-compositions/{skuId}/adjustments/{id}/void   作废调整项（POST，要理由）
    //   sku-cost-compositions/{skuId}/revisions               成本变动历史（只读）
    //
    // 🔴 `capabilities` 与 `adjustments` 的首字母都落在 [0-9a-f] 里，
    // 所以字面量子路径必须排在 uuid 分支**之前**判别，否则它们会被当成
    // 「id=capabilities 的那个 SKU」拼出去（这是本仓反复踩过的坑）。
    //
    // 🔴 刻意**不**放行：到岸成本（5.2.1.2）与成本分摊（5.2.1.3）尚未交付 ——
    // 那两个模块共用页面路由 /pricing/cost，但端点各自独立。
    // 放行后端不存在的路径比 404 更糟 —— 界面会以为功能已交付。
    //
    // 与既有分支不重叠：既有 `catalog/` 长正则里没有 `sku-cost-compositions`，
    // 所以本元素既不会被它遮蔽，也不会遮蔽它。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^sku-cost-compositions(?:\/capabilities|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adjustments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/void)?|revisions))?)?$/,
  },
  {
    // 5.2.1.2 到岸成本：「货到目标仓可售」的总成本（单据级口径）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   landed-costs                          到岸成本台账（集合读；筛选/分页在服务端）
    //   landed-costs/capabilities             能力声明（六个分量的来源 + 成本口径）
    //   landed-costs/comparisons              不同目的地的成本对比（按 skuId 查）
    //   landed-costs/parameters               租户参数（清关费 / 尾程预备）：读 + 保存（PUT）
    //   landed-costs/{id}                     到岸成本单明细（GET）
    //   landed-costs/{id}/components          逐项分量（GET，分页）
    //   landed-costs/{id}/recompute           按来源发运单重算（POST）
    //   landed-costs                          计算一张到岸成本单（POST）
    //
    // 🔴 `capabilities` / `comparisons` / `parameters` 三个字面量子路径必须排在
    // uuid 分支**之前**判别，否则它们会被当成「id=capabilities 的那张单」拼出去
    // （这是本仓反复踩过的坑）。
    //
    // 与既有分支不重叠：既有 `catalog/` 长正则里没有 `landed-costs`，
    // 所以本元素既不会被它遮蔽，也不会遮蔽它。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^landed-costs(?:\/(?:capabilities|comparisons|parameters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:components|recompute))?)?$/,
  },
  {
    // 14.1.3.1 关键词管理（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有分支（`landed-costs` / `02 渠道与店铺` / `ad-type-diffs` / `listing-keyword-rankings` …）
    // **一字未动、也不重排** —— 同批多车道共享这一个文件，每条服务规则是一段超长单行正则，
    // 往同一条 alternation 里并分支会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在本文件里**第一条** `channel/` 元素**之前**（`02 渠道与店铺` 那条），
    // 与本批其它车道（`auth/` 之前、中段 trade 区、数组末尾）的插入点相距 400 行以上。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   ad-keywords                               关键词集合读（GET，真分页 + 筛选）/ 登记（POST）
    //   ad-keywords/capabilities                  能力声明（未交付段 available=false + ownerModule）
    //   ad-keywords/filter-options                筛选面板选项（平台/店铺/外部活动/归一类型/币种）
    //   ad-keywords/{uuid}                        关键词详情（GET）/ 改（PUT）/ 归档（DELETE）
    //   ad-keywords/metrics                       🔴 关键词表现数据同步（POST，真实写入口）
    //   ad-keywords/search-terms                  🔴 搜索词报告（POST 同步 / GET 报告）
    //   ad-keywords/negative-suggestions          🔴 否定词建议（GET，带依据数值）
    //   ad-keywords/negatives                     否定记录（GET 读 / POST 登记）
    //   ad-keywords/negatives/{uuid}/result       平台回执登记（PUT）
    //   ad-keywords/bulk-actions                  批量调价与否定（GET 读 / POST 执行）
    //   ad-keywords/bulk-actions/{uuid}           批量任务详情（GET，含逐条执行结果）
    //   ad-keywords/report                        关键词报表（GET，按币种分行）
    //   ad-keywords/frequency                     词频与排名（GET，引用 4.5.1.2 口径）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / filter-options / metrics /
    // search-terms / negative-suggestions / negatives / bulk-actions / report / frequency）
    // 排在 uuid 段**之前**判别，否则会被 uuid 分支提前吃掉半截。
    // 末尾的 `?` 允许**裸路径** `ad-keywords`（集合读 + 登记，本模块真的有这两个动作）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-keywords` 的 **PUT/DELETE** 之外的形状（后端只有集合读 + 登记）
    //   * `ad-keywords/{uuid}/metrics` —— 指标是**批量**入口，不按关键词下钻
    //   * `ad-keywords/{uuid}/negatives` —— 否定按自己的集合读筛选，不按关键词下钻
    //   * `ad-keywords/targeting` —— 投放定位是 14.1.3.2（另一个未开发模块）
    //   * `ad-keywords/bid-strategy` —— 竞价策略引擎是 14.2.1.1（另一个未开发模块）
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //    （**不是** `[0-9a-f-]+` —— 后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-keywords(?:\/(?:capabilities|filter\-options|metrics|search\-terms|negative\-suggestions|negatives|bulk\-actions|report|frequency)|\/negatives\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/result|\/bulk\-actions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 14.3.1.1 活动报表（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-keywords`（14.1.3.1）等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'warehouse/'` 那条**元素之前**（仓库那条的注释块很长，
    //   它的 `prefix` 在文件 850 行附近，元素本体从 656 行开始）----
    //   同批其它车道的插入点分别是「`sku-cost-compositions` 那条之前」与「`compliance-precheck`
    //   那条之前」，与本处相距 150 行以上，git 能干净求并。
    //
    // ---- 首 token「ad-reports」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-reports` 这个串
    //   （既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-reports/capabilities          能力声明（TACOS / 跨币种统一显式 available=false）
    //   ad-reports/options               筛选项下拉 + 三个口径的定义 + 时间跨度上限说明
    //   ad-reports/rows                  报表表（GET，真分页 + 真筛选 + 真排序 + 汇总 + 口径说明）
    //   ad-reports/trend                 趋势图（GET，按日/周/月分桶，按币种拆成多条线）
    //   ad-reports/export                导出 CSV（GET + query；text/csv 由代理原样透传）
    //   ad-reports/exports               导出留痕历史（GET，真分页）
    //
    // ⚠️ **本模块的六个形状都没有 id 段**，所以这里**不写 uuid 段**（写了反而会把
    //   `capabilities` 这类字面量子路径当成 id 放行）。将来若新增按 id 的形状，
    //   必须用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`），并把字面量子路径排在 `{uuid}` 段**之前** ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-reports` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-reports/rows/{id}` —— 报表是按维度聚合的，没有「单行报表详情」这个形状
    //   * `ad-reports/tacos` —— TACOS 没有分母（总销售额口径 13.4 未接入），
    //     放行它会让界面以为「TACOS 这一列有数」
    //   * `ad-reports/export/{id}` —— 导出是**集合级**（按当前筛选整体导出），没有单条导出
    //   * `ad-reports/keywords` / `ad-reports/products` —— 关键词级与商品级报表分别归
    //     14.3.1.2 与 14.3.1.3，放行它们等于让界面以为那两个模块已交付
    //   * `ad-reports/capabilities/{id}` —— 字面量子路径不接 id 段
    //   * `ad-reports/rows/not-a-uuid` —— 本模块没有 id 段，任何带尾段的形状都不是真实端点
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-reports`。
    // `exports` 排在 `export` 之前：alternation 是首个命中即返回，
    // 虽然带 `$` 时回溯也能匹配，但显式排序免得后人读错。
    pattern: /^ad\-reports(?:\/(?:capabilities|options|rows|trend|exports|export))$/,
  },
  {
    // 14.3.1.2 广告组与词报表（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-keywords`（14.1.3.1）、`ad-reports`（14.3.1.1）
    // 等**一字未动、也不重排** —— 本文件是多车道共用的累积型白名单，每条服务规则是一段超长
    // 单行正则，往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突
    // （结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `ad-reports` 那条元素的 `pattern:` 行**之后** ----
    //   锚点行是本文件里唯一的字面量
    //   `pattern: /^ad\-reports(?:\/(?:capabilities|options|rows|trend|exports|export))$/,`
    //   同批其它车道（14.1.2.1 广告组管理 / 14.1.1.3 批量操作 / 14.4.1.1 促销列表）
    //   各自锚在别的行上，git 能干净求并。
    //
    // ---- 首 token「ad-group-keyword-reports」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-group-keyword-reports`
    //   这个串（既有的是 `ad-campaigns` / `ad-keywords` / `ad-reports` / `ad-rules` /
    //   `ad-type-diffs` / `ad-diagnostics` 一类），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //   （`ad-group-reports` 与 `ad-keyword-reports` 同样零命中；本车道取最长的那个，
    //   避免与 14.1.2.1 将来可能用的 `ad-groups` 撞车。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-group-keyword-reports/capabilities   能力声明（组级指标显式 available=false）
    //   ad-group-keyword-reports/options        筛选项下拉 + 口径定义 + 缓存说明
    //   ad-group-keyword-reports/rows           报表表（GET，真分页 + 真筛选 + 真排序 + 汇总）
    //   ad-group-keyword-reports/exports        导出留痕（GET 列表 / POST 批量选中导出）
    //   ad-group-keyword-reports/exports/{uuid} 单条导出留痕详情
    //   ad-group-keyword-reports/exports/{uuid}/csv  导出 CSV（text/csv 由代理原样透传）
    //
    // 判别顺序：`exports` 排在 `{uuid}` 段之前 —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。`{uuid}` 段用**完整 UUID 形状**
    // `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-group-keyword-reports` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-group-keyword-reports/rows/{id}` —— 报表是按维度聚合的，没有「单行报表详情」
    //   * `ad-group-keyword-reports/export`（单数）—— 导出走 POST /exports + 按 id 下载 CSV
    //   * `ad-group-keyword-reports/exports/{id}/preview` —— 没有「变更预览」这个形状
    //   * `ad-group-keyword-reports/exports/{id}/metrics` —— 指标随报表一起返回
    //   * `ad-group-keyword-reports/capabilities/{id}` —— 字面量子路径不接 id 段
    //   * `ad-group-keyword-reports/rows/not-a-uuid` —— 任何非 UUID 尾段都不是真实端点
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-group-keyword-reports`。
    pattern:
      /^ad\-group\-keyword\-reports\/(?:capabilities|options|rows|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/csv)?)?)$/,
  },
  {
    // 02 渠道与店铺：平台目录、授权连接、店铺与站点。
    // health/reminders 属于连接健康，marketplace 挂在店铺下，transfer 是负责人转让。
    // platforms/{code}/enums 后面可选一段 id：租户映射覆盖按 id 删除。
    // oauth/* 是 2.2.1.2 授权向导：callback 由平台重定向直接打进来（浏览器导航，GET），
    // sessions 后面可选一段 id 与 shops/confirm/cancel。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // warehouse-providers 是 10.4.2.1 海外仓连接：服务商目录、连接、仓账户、
    // 库存同步与外部库存观测（只读）。
    // 形状逐个列出：
    //   warehouse-providers                     服务商目录（集合读）
    //   connections / connections/{id}           连接读写
    //   connections/{id}/sync-paused             暂停/恢复同步（POST + query）
    //   connections/{id}/accounts                建仓账户（POST）
    //   accounts / accounts/{id}                 仓账户读
    //   accounts/{id}/inbound-stock-lines        登记服务商报送（POST）——适配器的真实输入
    //   accounts/{id}/sync                       触发库存同步（POST）
    //   sync-runs                                同步记录（集合读）
    //   accounts/{id}/observations               观测流水（集合读）
    //   accounts/{id}/observations/latest        各 SKU 最新观测
    // ⚠️ sync-runs 与 connections / accounts 是**兄弟**不是子路径：
    //    早先一版把它写进了 connections 的组里，warehouse-providers/sync-runs
    //    被静默拦成 404（typecheck/lint 全绿，只有真跑 proxy 才看得见）。
    // 动作段逐字列出而不是通配：多放一层会让「一个还不存在的动作」看起来像已交付。
    // 刻意**没有** DELETE 段：观测快照不可删（蓝图「不能覆盖」），连接/仓账户也不靠物理删收回。
    // 字面量段（latest）排在 [0-9a-f-]+ 之前判别，两类不会互相吃掉。
    // handover-batches 是 10.3.2.1 交运批次：候选池、按承运商+交运时间的组批预览、
    // 批次与逐件交接清单。形状逐个列出：
    //   handover-batches                     批次列表（GET）+ 组批（POST）
    //   handover-batches/capabilities        能力声明（未交付段 available=false）
    //   handover-batches/group-preview       组批预览（真 GROUP BY 读端点）
    //   handover-batches/candidates          候选池（上游 9.4.1.1 的只读投影）
    //   handover-batches/candidates/sync     同步候选池（唯一写入口）
    //   handover-batches/{id}                批次详情
    //   handover-batches/{id}/manifest       交接清单（法律凭据，追责时导出）
    //   handover-batches/{id}/timeline       事件时间线
    //   handover-batches/{id}/dispatch       登记交接（POST）
    //   handover-batches/{id}/receipt        承运商回执登记（POST）
    //   handover-batches/{id}/cancel         作废（POST）
    // carriers 是 10.1.1.1 承运商列表：承运商档案 + 可用性状态。
    //   carriers                           列表（GET，真分页 + 筛选）
    //   carriers/capabilities              能力声明（未交付段 available=false）
    //   carriers/overview                  全量概览（不受筛选与分页影响）
    //   carriers/sync                      同步承运商身份（POST，唯一写上游快照的入口）
    //   carriers/{id}                      详情（GET）/ 改物流侧档案（PUT）/ 撤下物流侧档案（DELETE）
    // 字面量子路径（capabilities/overview/sync）排在 [0-9a-f-]+ **之前**判别，
    // 与 handover-batches 同款次序：让没交付的形状（如 carriers/{id}/channels）
    // 继续 404，而不是被 id 段静默吃掉后看起来像已交付。
    // 刻意**不**放行 carriers/{id}/channels 之类 —— 那是 10.1.1.2 渠道配置的路径，
    // 模块未开发，代理层放开会让一个还不存在的模块看起来像已交付。
    // 字面量子路径（capabilities / group-preview / candidates）排在 [0-9a-f-]+ **之前**判别，
    // 否则会被当成一个 id 段静默吃掉 —— 只在真跑 proxy 时才看得见。
    // candidates/sync 是**两段**：candidates 后面多跟一个字面量 sync，
    // 刻意不带任何可选量 —— 多放一层会让「一个还不存在的动作」看起来像已交付。
    // 刻意**没有** DELETE 段：交接清单是法律凭据，交运批次不能靠物理删除收回。
    // 02.6.1.2 去重视图（webhook-dedup）与 webhook-receipts 同一处置，仅**新增**分支：
    // summary 排在 [0-9a-f-]+ 之前判别（同 webhook-receipts/summary 的次序），
    // 动作只有 claim 一个且不带任何可选量 —— 放行一个还不存在的动词比 404 更糟。
    pattern:
      /^(platforms(?:\/[A-Za-z0-9_]+\/enums(?:\/[0-9a-f-]+)?)?|connections(?:\/(?:reminders|[0-9a-f-]+(?:\/(?:credential|health|revoke))?))?|shops(?:\/[0-9a-f-]+(?:\/(?:marketplaces(?:\/[0-9a-f-]+)?|transfer))?)?|sync-cursors(?:\/[0-9a-f-]+(?:\/(?:pause|resume|rewind|trigger))?)?|sync-runs(?:\/[0-9a-f-]+(?:\/(?:complete|resolve))?)?|sync-failures(?:\/summary|[0-9a-f-]+\/replay)?|platform-operations(?:\/[0-9a-f-]+(?:\/(?:start|succeed|fail|unknown|verify))?)?|oauth\/(?:callback|sessions(?:\/[0-9a-f-]+(?:\/(?:shops|confirm|cancel))?)?)|warehouse-providers(?:\/(?:connections(?:\/[0-9a-f-]+(?:\/(?:sync-paused|accounts))?)?|sync-runs|accounts(?:\/[0-9a-f-]+(?:\/(?:inbound-stock-lines|sync|observations(?:\/latest)?))?)?))?|handover-batches(?:\/(?:capabilities|group-preview|candidates(?:\/sync)?|[0-9a-f-]+(?:\/(?:manifest|timeline|dispatch|receipt|cancel))?))?|carriers(?:\/(?:capabilities|overview|sync|[0-9a-f-]+))?|webhook-dedup(?:\/summary|\/[0-9a-f-]+(?:\/claim)?)?|webhook-endpoints(?:\/[0-9a-f-]+\/disable)?|webhook-receipts(?:\/summary|\/[0-9a-f-]+(?:\/(?:claim|complete))?)?|webhooks\/[A-Za-z0-9_-]+)?$/,
  },
  {
    // 16.5.2.2 关联风险（channel 服务，卷 16「合规与风控系统」L3 16.5.2 账号安全下的第二格）。
    //
    // 蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
    // 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
    // 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
    // 下游 = 1 工作台预警。**关联封号是跨境多店经营的头号风险**：官方 API 授权是规避关联的关键，
    // 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 分支的 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   插入锚点固定在 `// 07 库存：库存总览（7.1.1.1）` 那条**之前**，
    //   与同批其它车道（5.5.1.1 价格历史 / 2.4.2.3 映射缺失队列 / 数组末尾）互不重叠。
    //
    // ---- 首 token「linkage-risk」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `linkage` 这个串
    //   （`grep -c linkage api-proxy.ts` = 0；既有首 token 是 `access-security` /
    //   `buyer-risk-scores` / `ip-risk-library` / `pii-access` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   linkage-risk/capabilities                     能力声明（逐维度 available + autoDiscoverable + ownerModule）
    //   linkage-risk/policy                          关联风险策略（GET 读 / PUT 改；阈值不给默认值）
    //   linkage-risk/shops                           图谱节点来源（真实店铺 + 法人绑定）
    //   linkage-risk/identifiers                     关联事实登记（GET 真分页 / POST 登记）
    //   linkage-risk/identifiers/{uuid}              修改 / 删除一条登记（PUT / DELETE）
    //   linkage-risk/identifiers/{uuid}/shops        把店铺挂到共享因子上（POST）
    //   linkage-risk/identifiers/{uuid}/shops/{uuid} 解除一条共享事实（DELETE）
    //   linkage-risk/graph                           关联图谱（GET：节点 + 边 + 关联组）
    //   linkage-risk/scan                            跑一次扫描（POST，真去读事实真落库）
    //   linkage-risk/alerts                          风险提示列表（GET，真分页 + 真筛选 + 汇总）
    //   linkage-risk/alerts/{uuid}                   风险提示详情（GET，含处置留痕与整改建议）
    //   linkage-risk/alerts/{uuid}/acknowledge       确认（待处置 → 已确认）
    //   linkage-risk/alerts/{uuid}/resolve           整改完成（待处置 / 已确认 → 已整改）
    //   linkage-risk/alerts/{uuid}/dismiss           忽略（待处置 / 已确认 → 已忽略）
    //   linkage-risk/remediations                    整改建议列表（GET）
    //   linkage-risk/remediations/{uuid}/status      处置一条整改建议（PUT）
    //
    // 判别顺序：**字面量子路径（capabilities / policy / shops / graph / scan / alerts / identifiers /
    // remediations）排在各自的 {uuid} 段之前** —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ **末尾刻意不带 `?`**：带了就等于放行裸路径 `linkage-risk` ——
    //   后端没有「一条汇总列表接口」，放行它会让界面以为可用。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `linkage-risk` —— 本模块没有裸集合读
    //   * `linkage-risk/payment-methods` / `linkage-risk/payout-accounts` /
    //     `linkage-risk/fund-accounts` —— **12.7.1 资金账户（12.7.1.1 账户管理）未交付**，
    //     支付方式 / 收款账户维度的真相归它；本模块**不建它们的影子数据**
    //   * `linkage-risk/browser-fingerprint` / `linkage-risk/device-fingerprint` /
    //     `linkage-risk/simulate-login` / `linkage-risk/proxy` ——
    //     **蓝图明令禁止的能力**（模拟登录 / 指纹浏览器 / 绕关联），本仓一个都不提供，
    //     代理层更不能放行（放行 = 告诉界面「这个入口存在」）
    //   * `linkage-risk/graph/{uuid}` —— 图谱是**整体读**（节点 + 边 + 关联组一起给），
    //     没有按 id 取的端点
    //   * `linkage-risk/clusters` —— 关联组在图谱与提示详情里，没有独立的集合端点
    //   * `linkage-risk/alerts/{uuid}/delete` —— 处置走状态机（确认 / 整改 / 忽略），**不是删除**；
    //     删掉提示等于删掉处置留痕
    //   * `linkage-risk/alerts/{uuid}/reopen` —— 「已整改 / 已忽略」是**终态**，
    //     要重新处置只能等下一次扫描，不提供「重开」入口
    //   * `linkage-risk/workbench-alerts` / `linkage-risk/push-to-workbench` ——
    //     **下游 1 工作台预警未交付**，本模块只产出提示与建议，**不代**下游推送
    //   * `linkage-risk/access-security/**` —— 16.5.2.1 登录与访问安全**已交付**
    //     （`access-security` 是**另一条**独立的 services[] 元素），路径不同、互不影响
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^linkage\-risk(?:\/(?:capabilities|policy|shops|graph|scan|alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve|dismiss))?)?|identifiers(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/shops(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)?)?|remediations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?))$/,
  },
  {
    // 12.1.2.1 应付单管理（finance 服务，卷 12「财务核算」L3 12.1.2 应付下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往上一条 finance/ 的
    //   alternation 里并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突
    //   （结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**第一个 `prefix: 'warehouse/'` 那条元素之前**（基线 ~1379 行）----
    //   ⚠️ **本文件是多车道共用的累积型白名单，同批车道必须各占一个彼此相距 600 行以上的锚点**，
    //   否则三边都插在「数组末尾」同一个位置 ⇒ 必然冲突（结构性，retry 不会自愈）。
    //   本批分工：12.1.1.2 应收账龄（`receivable-aging`）→ 第一个 `prefix: 'identity/'` 之前；
    //   本条（`payables`）→ 第一个 `prefix: 'warehouse/'` 之前；
    //   12.2.1.1 收款单（`receipts`）→ 数组末尾。
    //   既有条目**一字未改、也不重排**，只是把本块从数组末尾**搬**到这个锚点。
    //
    // ---- 首 token「payables」/「prepayments」的零命中核对结论（移动后在整份文件上实测）----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**的首 token 是 `payables`
    //   或 `prepayments`（`payables` 只作为 `trade/purchase-order-approvals` 的**子分支**
    //   出现过一次，首 token 全不相同；`prepayments` 全文件零命中），
    //   所以两个首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // ---- 与同服务其它 finance 条目的先后顺序**不影响正确性** ----
    //   resolve() 对同一个 prefix 只返回 `{base: FINANCE_URL}`（同一个后端），
    //   而三条 finance 条目的首 token 互不相同（`receivables` / `receipts` /
    //   `receivable-aging`）、三个正则两两不相交 ⇒ 谁先谁后都命中同一条正确路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   payables                              应付单集合（GET 真分页 + 真筛选 + 汇总；POST 人工录入）
    //   payables/capabilities                 能力声明（哪些上游真接通 + 缺口归谁）
    //   payables/options                      筛选项（来源 / 状态 / 币种 / 应付对象）
    //   payables/unpaid                       未付清单（12.1.2.1 的输出之一）
    //   payables/policies                     应付确认政策（每个来源 × 义务类型全列）
    //   payables/policies/{source}            配置一个（来源 × 义务类型）的确认政策（PUT）
    //   payables/import-purchase-arrivals     从 6.3.2.2 到货应付部分确认导入（幂等）
    //   payables/import-first-mile            从 10.4.1.1 头程发运单导入物流费用（幂等）
    //   payables/import-ad-spend              从 14.3.1.1 活动花费导入广告费（幂等）
    //   payables/{uuid}                       应付单详情（单头 + 明细行 + 事件流）
    //   payables/{uuid}/void                  作废（红冲的等价物）
    //   prepayments                            预付款集合（**独立聚合**，不是应付单的子资源）
    //   prepayments/{uuid}                    预付款详情（单头 + 抵扣明细）
    //   prepayments/{uuid}/allocations        登记一笔预付款抵扣
    //   prepayments/{uuid}/void               作废预付款
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   payables/{uuid}/settle                —— 🔴 付款（12.2.2.1）**未交付**，
    //                                             本模块**不提供**任何「标记已付」的写入口
    //   payables/{uuid}/write-off             —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   payables/{uuid}/invoice               —— 🔴 发票（12.6.2.1）**未交付**
    //   payables/{uuid}/confirm               —— 🔴 供应商对账的财务侧确认（12.1.2.3）**未交付**
    //   payables/payments                     —— 🔴 收付款（12.2.x）**未交付**
    //   payables/settlements                  —— 🔴 平台结算（12.3.1）**未交付**
    //   payables/not-a-uuid                   —— 非 UUID 段不应被 uuid 分支吃掉
    //   payables/{uuid}/not-a-shape           —— 本模块的动作只有 void
    //   prepayments/{uuid}/delete             —— 事实不可删：作废走 status='VOID'
    //   prepayments/{uuid}/revoke-allocation  —— 🔴 撤销抵扣属 12.2.2.1 域（**未交付**）
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^(?:payables(?:\/(?:capabilities|options|unpaid|policies(?:\/[A-Z_]+)?|import\-purchase\-arrivals|import\-first\-mile|import\-ad\-spend|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/void)?))?|prepayments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:allocations|void))?)?)$/,
  },
  {
    // 12.2.1.2 核销（finance 服务）。纯**新增一条独立的 services[] 元素**：
    // 既有条目**一字未改、也不重排** —— 本文件是多车道共用的累积型白名单，
    // 每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让
    // 同批多车道并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 services[] 里**第一个 `prefix: 'warehouse/'` 那条元素之前** ----
    //   `warehouse/` 在本文件里出现多次（认准**第一个**）：本条插在它那条元素**之前**。
    //   同批其它车道的锚点分别是「数组最开头」「第一个 `prefix: 'identity/'` 之前」「数组末尾」，
    //   各自相距 600 行以上，git 能干净求并。
    //
    // ---- 首 token「write-off」的零命中核对结论（在本文件全文上实测）----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条的首 token 是 `write-off`**：
    //   既有与「核销」相关的字面量都是**子路径**（`receivables/{uuid}/write-off`、
    //   `receipts/{uuid}/write-off`、`receivable-aging/write-offs`、`payables/{uuid}/write-off`），
    //   首 token 分别是 receivables / receipts / receivable-aging / payables —— 与本条互不相同。
    //   ⇒ 本条既不会被既有分支的前缀吃掉，也不会吃掉别人的路由。
    //
    // ---- 与同服务其它 finance 条目的先后顺序**不影响正确性** ----
    //   resolve() 对同一个 prefix 只返回 `{base: FINANCE_URL}`（同一个后端），
    //   而各条 finance 条目的首 token 互不相同（`receivables` / `receipts` / `payables` /
    //   `prepayments` / `write-off`）、正则两两不相交 ⇒ 谁先谁后都命中同一条正确路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   write-off                            核销记录集合（GET 真分页 + 真筛选 + 汇总；POST 人工指定核销）
    //   write-off/capabilities               能力声明（哪些真接通 + 缺口归谁）
    //   write-off/options                    筛选项（状态 / 方式 / 币种）
    //   write-off/pending                    待核销视图（可核销的收款 + **应收明细行**）
    //   write-off/advance-balance            预收余额（按币种分开，两段口径不重复计数）
    //   write-off/advance                    预收挂账行列表
    //   write-off/advance/{uuid}/release     释放一笔预收挂账
    //   write-off/auto-match                 自动匹配核销（金额 + 订单号 + 日期三者对上）
    //   write-off/auto-match/candidates      自动匹配候选（只读，不落库）
    //   write-off/{uuid}                     核销记录详情（单头 + 分配明细 + 事件流）
    //   write-off/{uuid}/reverse             核销撤销（把明细行的已核销金额退回去）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   write-off/{uuid}/write-off           —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   write-off/{uuid}/settle              —— 🔴 付款（12.2.2.1）**未交付**
    //   write-off/profit                     —— 🔴 回款口径 / 利润（13）**未交付**
    //   write-off/not-a-uuid                 —— 非 UUID 段不应被 uuid 分支吃掉
    //   write-off/{uuid}/not-a-shape         —— 本模块的动作只有 reverse
    //   write-off/auto-match/{uuid}          —— 自动匹配不接受 id 段
    //   write-off/capabilities/{uuid}        —— 字面量段刻意不接 id 段
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^write\-off(?:\/(?:capabilities|options|pending|advance-balance|auto\-match(?:\/candidates)?|advance(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/release)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/reverse)?))?$/,
  },
  {
    // 12.5.2.2 财务报表（finance 服务）。纯**新增一条独立的 services[] 元素**：
    // 既有条目**一字未改、也不重排** —— 本文件是多车道共用的累积型白名单，
    // 每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让
    // 同批多车道并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 services[] 里**第一个 `prefix: 'warehouse/'` 那条元素之前** ----
    //   `warehouse/` 在本文件里出现多次（认准**第一个**）：本条插在它那条元素**之前**。
    //   同批其它车道的锚点分别是「数组最开头」「第一个 `prefix: 'identity/'` 之前」「数组末尾」，
    //   各自相距 600 行以上，git 能干净求并。
    //
    // ---- 首 token「financial-reports」的零命中核对结论（在本文件全文上实测）----
    //   `grep -c "financial-reports" apps/web/src/lib/api-proxy.ts` ⇒ **0**：
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `financial-reports` 这个串
    //   （既有的是 `vouchers` / `payables` / `write-off` / `cost-collections` 一族），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // ---- 与同服务其它 finance 条目的先后顺序**不影响正确性** ----
    //   resolve() 对同一个 prefix 只返回 `{base: FINANCE_URL}`（同一个后端），
    //   而各条 finance 条目的首 token 互不相同（`vouchers` / `payables` / `write-off` /
    //   `financial-reports` …）、正则两两不相交 ⇒ 谁先谁后都命中同一条正确路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   financial-reports/capabilities            能力声明（哪些真接通 + 缺口归谁）
    //   financial-reports/options                 筛选项（期间 / 币种 / 科目，全部来自真实数据）
    //   financial-reports/templates               报表模板集合（GET 列表 / POST 登记）
    //   financial-reports/templates/{uuid}        修改一条模板（PUT，乐观并发）
    //   financial-reports/templates/{uuid}/disable 停用一条模板（**没有删除**）
    //   financial-reports/balance-sheet           资产负债表
    //   financial-reports/income-statement        利润表
    //   financial-reports/cash-flow               现金流量表
    //   financial-reports/trial-balance           科目余额表
    //   financial-reports/general-ledger          明细账（逐笔展开一个科目）
    //   financial-reports/drill                   钻取到凭证（= 报表行上的 drillPath）
    //   financial-reports/export                  导出 CSV / JSON（真内容 + 一条留痕）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   financial-reports                          —— 🔴 裸路径：本模块**没有**「一张报表」的集合端点，
    //                                                 五张报表各自是独立端点，不存在裸资源
    //   financial-reports/{uuid}                   —— 本模块的 id 段只出现在 templates 之下
    //   financial-reports/templates/{uuid}/delete  —— 🔴 模板**不可删**：删掉会让「当时的报表口径」无法复现，
    //                                                 改口径用「停用」
    //   financial-reports/templates/{uuid}/enable  —— 反向动作没有端点（停用是单向的）
    //   financial-reports/not-a-uuid               —— 非 UUID 段不应被 uuid 分支吃掉
    //   financial-reports/templates/not-a-uuid     —— 同上
    //   financial-reports/capabilities/{uuid}      —— 字面量段刻意不接 id 段
    //   financial-reports/export/{uuid}            —— 导出是集合级动作，不按 id 取
    //   financial-reports/drill/{uuid}             —— 钻取是集合级动作，不按 id 取
    //   financial-reports/financial-reports        —— 交替写错就会互相吃掉，必须反向钉住
    //   financial-reports/reports                  —— 同上（别把首 token 拼成别名）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^financial\-reports\/(?:capabilities|options|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/disable)?)?|balance\-sheet|income\-statement|cash\-flow|trial\-balance|general\-ledger|drill|export)$/,
  },
  {
    // 1.1.3.1 库存风险（卷 01 工作台与门户）。纯**新增一条独立的 services[] 元素**：
    // 既有那条 `warehouse/` 巨型 alternation **一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，
    // 往同一条 alternation 里并分支会让同批多车道并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'warehouse/'` 那条元素**之前** ----
    //   同批其它车道的锚点分别是「数组最开头」「`prefix: 'identity/'` 之前」「数组末尾」，
    //   四处相距 600 行以上，git 能干净求并。
    //
    // ---- 为什么是第二条 `warehouse/` 元素而不是往既有那条里并分支 ----
    //   代理的判别顺序是「`path.startsWith(prefix)` 收窄服务 → 再测正则」（见文件末尾 resolve()），
    //   所以**同一服务可以有多条元素**：本条排在既有 warehouse 那条**之前**，
    //   `warehouse/inventory-risk/...` 命中本条，其它 warehouse 路径继续落到既有那条。
    //   两条元素的 env / fallback 完全一致，路由到的仍是同一个 warehouse 服务。
    //
    // ---- 首 token「inventory-risk」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `inventory-risk` 这个串
    //   （既有的是 `inventory/...` 家族与 `inventory/stockout-alerts` / `inventory/consignment-stock`），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   inventory-risk/capabilities   能力声明（哪些输入接通了、哪些没接通及归属模块与影响）
    //   inventory-risk/projection     (仓 × SKU) 库存事实投影（GET，真分页 + 真筛选）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   inventory-risk                 —— 裸路径：后端没有「一条不存在的汇总端点」，汇总在 projection 里。
    //   inventory-risk/projection/{...} —— 投影是集合读，明细在行内，没有子资源。
    //   任何写形状（POST / PUT / DELETE 的**独立子路径**）—— 本模块是**只读投影**，
    //     不产生任何业务事实，卷 01 的定位写死了「工作台只读 + 跳转；一切状态变更回到归属系统完成」。
    //     代理的白名单是**按路径**判别、不看 HTTP 方法，所以往 projection 上 POST 会被转发，
    //     但后端 InventoryRiskProjectionController 只映射了两个 GET —— 落上去是 405，不是「静默生效」。
    //     刻意**不新开** inventory-risk/refresh 这类写子路径：放行一个后端不存在的映射比 404 更糟。
    //   注意既有 warehouse 那条里已有 `inventory/...` 分支，**本条不动它**：
    //     `inventory-risk` 与 `inventory` 是两个不同的首段（前者多一个 `-risk`），互不覆盖。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^inventory\-risk\/(?:capabilities|projection)$/,
  },
  {
    // 1.4.1.2 列表与表格偏好（identity 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `identity/` 与 `warehouse/` 元素**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，
    // 往同一条 alternation 里并分支会让同批多车道并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `prefix: 'warehouse/'` 那条**元素之前**（07 库存 / 08 仓储作业那条，
    //   它的注释块很长，所以按「元素的 `{`」而不是按 `prefix:` 行定位）----
    //   同批其它车道的锚点分别是「`prefix: 'catalog/'` 之前」「`prefix: 'identity/'` 之前」
    //   「数组末尾」，与本处相距 300 行以上，git 能干净求并。
    //
    // ---- 首 token「user-preferences」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `user-preferences` 这个串
    //   （既有的是 `identity/roles`、`identity/tenants`… 一类），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // ---- 路径段为什么不是 UUID 形状 ----
    //   本模块的路径段是**页面 key**（如 `orders.pending-shipment`），不是资源 id：
    //   偏好的自然键就是「哪一页」，用 id 寻址反而多绕一层（要先查列表才知道 id）。
    //   所以这里用**严格的小写 slug 词表** `[a-z0-9][a-z0-9._-]{0,159}`，
    //   刻意**不**用宽松的 `[0-9a-f-]+` —— 那种写法会把 `capabilities` 这类字面量子路径
    //   当成 id 放行。本模块**没有** id 段，所以「id 段用完整 UUID 形状」这条约定在此不适用。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   user-preferences                 列出我全部页面的偏好（GET）/ 全部重置（DELETE）
    //   user-preferences/{pageKey}       读一页（GET）/ 保存一页（PUT，upsert）/ 重置一页（DELETE）
    //
    // 末尾**刻意不带 `?`**：裸 `user-preferences` 是本模块**真实存在**的端点（列表 / 全部重置），
    //   所以它作为一条**显式**的备选写出来，而不是靠一个尾部 `?` 顺带放行出来的。
    //
    // 刻意**不放行**的形状：
    //   * `user-preferences/{任意非 slug 形状}` —— 页面 key 词表之外的一律 404
    //     （含大写字母、空格、路径分隔符）。后端的 CHECK 与本正则同词表。
    //   * `user-preferences/{pageKey}/{子路径}` —— 本模块没有子资源。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^user\-preferences$|^user\-preferences\/[a-z0-9][a-z0-9._-]{0,159}$/,
  },
  {
    // 1.4.2.1 语言与时区（identity 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `identity/` 与 `user-preferences` 元素**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，往同一条 alternation 里并分支会让同批多车道
    // 并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `user-preferences` 那条元素**之后**（按内容定位：该元素
    //   的 pattern `/^user\-preferences$|^user\-preferences\/[a-z0-9][a-z0-9._-]{0,159}$/`
    //   全文唯一）----
    //   **与同批其它车道的锚点距离（2026-10-23 实测本车道基线 7725fdd0）**：
    //     · 1.2.2.1「常用页面收藏」锚在 identity 段约第 **2916** 行 ⇒ 与本处相距约 **1538 行**；
    //     · 1.4.2.2「默认店铺与仓库」锚在别处 ⇒ 与本处相距 **300 行以上**。
    //   两条都远超 300 行的安全距离 ⇒ git 能干净求并（本文件没有 union merge driver，
    //   两条车道改同一位置必然冲突，且 retry 不会自愈）。
    //
    // ---- 首 token「user-locale」的零命中核对结论 ----
    //   本文件既有 **192** 条 services[] 正则**逐条实测**（.tmp/zero-hit-check.mjs，
    //   与运行时 resolve() 同样的 fallthrough 口径）：`user-locale` 与
    //   `user-locale/capabilities` 两个新路径对既有 192 条正则 ⇒ **命中 0 处**。
    //   既有含 `user` 的是 `user-preferences`（1.4.1.2），首 token 全不相同，互不遮蔽。
    //   反向也测了：本条正则对既有 identity 形状（`user-preferences` / `roles` /
    //   `tenants` / `global-search/recent` / `shortcut-templates` …）⇒ **全部不命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   user-locale                 读 / 写 / 重置我的界面语言与展示时区
    //   user-locale/capabilities    能力声明（逐维 available + 缺什么 + 归谁）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   user-locale/{任意子路径}    —— 本模块没有子资源。
    //   user-locale/preferences     —— 偏好走 1.4.1.2 的 user-preferences 端点。
    //   user-locale/history         —— 本模块不存历史，只存当前选择。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^user\-locale(?:\/capabilities)?$/,
  },
  {
    // 07 库存：库存总览（7.1.1.1）、库存明细 bucket（7.1.1.2）、筛选下拉参照数据与超储阈值。
    // overview/capabilities/policies 是总览本身；warehouses/legal-entities/skus/quality-statuses
    // 是筛选下拉项。policies/{id} 后面接一段 id（更新与删除）。
    // buckets/bucket-actions 是 7.1.1.2 的明细读端点（纯读，不需要写权限）；
    // locations/lots 是逐级下钻的库位与批次下拉项，与其余参照数据同形。
    // sellable-policies 是 7.2.2.1 可售量口径：列表 / effective / pools / pools/{poolKey} / {id}
    // 五个读形状，calculation（POST 试算）、{id}/versions（换版）、{id}/retire（作废）三个动作。
    // transfers 是 7.5.1.1 调拨单：列表 / {id} / {id}/movements 三个读形状，
    // 以及 {id}/confirm、{id}/ship、{id}/receive、{id}/close 四个动作端点
    // （刻意**没有** DELETE 段：调拨单要靠分录与在途行回溯，不能物理删掉一条单据）。
    // cross-entity-transfers 是 7.5.2.1 跨法人调拨，**刻意不复用 transfers 前缀**：
    //   两者语义不同（那边货权前后同一个法人、不定价、无内部结算凭据），
    //   挂在同一前缀下会让「跨法人」看起来只是普通调拨的一个筛选项 ——
    //   而那正是蓝图点名要避免的「当作普通同仓调拨」。
    //   读形状：列表 / {id} / {id}/movements / {id}/settlement；
    //   动作形状：{id}/source-confirm 与 {id}/target-confirm（**两个独立端点**，
    //   让调用方在请求体里声明代表哪一方等于让它可以自称另一方）、
    //   {id}/ship、{id}/receive、{id}/close、{id}/cancel。
    //   同样**没有** DELETE 段：已发运的货权变更不能物理删除，只能结案。
    // in-transit 是 7.5.1.2 在途库存：调拨 / 头程 / 采购 / FBA 发运四类同一个口径。
    //   in-transit 同一路径两种方法：GET 清单 / POST 登记（登记是对上游发运事件的镜像）。
    //   alerts 与 netting 是两个字面量聚合路径（到达预警 / 补货可扣在途净额）。
    //   {legId} 是详情；{legId}/arrive 与 {legId}/discrepancy 是两个写动作，都带 expectedVersion 乐观锁。
    //   动作后缀**逐个列出**而不是一条通配：多放一层会让「一个还不存在的动作」看起来像已交付。
    //   刻意**没有** DELETE / PUT 段：在途腿靠事件时间线回溯，货已上路不该改历史。
    // 口径路径上刻意**没有** DELETE 段：作废是审计事件，档位行永久保留，
    // 放行一个删除形状会让"能不能物理删掉一条防超卖配置"看起来是能。
    // effective / pools / pools/{poolKey} 是字面量，排在 {id} 之前判别，两者不会互相吃掉。
    // stocktakes 是 7.4.2.1 盘点单：列表 / capabilities / {id} / {id}/lines /
    // {id}/lines/{lineId}/count / {id}/tasks / {id}/tasks/{taskId}/assign。
    // 盘点路径上同样**没有** DELETE：账面数是冻结快照，删一张盘点单等于让这次盘从未发生过。
    // capabilities 是字面量，排在 {id} 之前判别。
    // stocktake-variances 是 7.4.2.2 盘点差异处理：capabilities / sheets/{sheetId}/
    // lines|report|audit|adjustments / adjustments/{id}(+submit|approve|reject|cancel|post)。
    // 同样**没有** DELETE：差异处理全程只追加审计，归因/调整单/审计行都不可删，
    // 放行一个删除形状会让「能不能把一条差异的痕迹抹掉」看起来是能。
    // adjustments 是 7.4.1.1 **通用调整单**（与上面 stocktake-variances 下的 adjustments
    // 是两套单据：那一套要求先有盘点表，这一套是通用调整）：
    //   capabilities / {id} / {id}/lines / {id}/lines/{lineId}/remove
    //   / {id}/attachments / {id}/attachments/{attachmentId}/remove
    //   / {id}/submit|approve|reject|cancel|post
    // **没有** DELETE 形状：删行与摘凭证都是显式动作后缀（POST .../remove），
    // 而且数据库只允许**草稿**单删行/摘凭证 —— 路径上放行 DELETE 会让
    // 「能不能把一条已提交的调整痕迹抹掉」看起来是能。
    // adjustment-sources 是 6.4.2.2 不良品处置单的跨服务只读投影：
    //   defective-dispositions / defective-dispositions/{orderId}/instructions。
    //   事实归 trade 所有，本服务只经授权 HTTP 读它 —— 不跨库读表、不建跨库 FK。
    // adjustment-approvals 是 7.4.1.2 **调整审批**（阈值审批、批量审批、驳回工作流）：
    //   capabilities                     能力声明（阈值闸门是否启用 / 为什么没委托 17 组织 / 金额为什么是申报值）
    //   rules[/{id}/update|status]        阈值规则（类型 + 币种 + 金额区间 + 数量区间 ⇒ 层级与人数）
    //   approvers[/{id}/status]           审批人授权（层级 + 金额授权区间 + 数量授权上限）
    //   pending                          待我审批工作台（真分页，带「此刻我能不能批」与原因）
    //   evaluate                         阈值判定试算（只读，不落库）
    //   {id}/submit                      提交待审（冻结判定 + 登记估值）
    //   {id}                             审批单详情（审批单 + 全部链路记录）
    //   {id}/approve|reject               批准 / 驳回（判定通过才放行到 7.4.1.1 的批准动作）
    //   batch/approve|batch/reject        批量审批 / 批量驳回（逐条给结论）
    //   valuations                       调整的会计影响（登记额 / 生效额分两列）
    // 判别顺序：**字面量子路径**（capabilities / rules / approvers / pending / evaluate /
    // batch / valuations）一律排在 {uuid} 段之前 —— alternation 是首个命中即返回。
    // {uuid} 段用**完整 UUID 形状**（不是 [0-9a-f-]+）：后者会把 bad / dead 这类非 UUID
    // 字面量也当成 id 放行，放行后端不存在的映射比 404 更糟。
    // 刻意**不含** DELETE / PUT 形状：阈值规则与审批人授权走 status 停用（不物理删），
    // 审批链路记录是 append-only（服务端无 DELETE 端点 + DB 的 REVOKE 与触发器各挡一次）。
    // 与 7.4.1.1 的 adjustments 段**不是同一段**：单据与审批是两件事，混在一段会让
    // 「单据接口」与「审批中心接口」在白名单上互相打架。
    // capabilities 是字面量，排在 {id} 之前判别（'p' 落在 [0-9a-f-] 之外，
    // 判别顺序错了会凭空放行一个「id=capabilities」的包裹）。
    // facilities 是 8.1.1.1 仓库列表 + 8.1.1.2 仓库详情（同一个页面，详情挂在列表之下）。
    // facilities/options 与 facilities/capabilities 是两个**单段字面量**子路径
    // （筛选下拉项与作业维度接入情况），排在任何 {uuid} 之前判别。
    // {uuid} 后面可选接一段 locations / entrypoints：库位清单与子模块入口清单。
    // 这三段是 8.1.1.2 交付时**追加**进来的，8.1.1.1 已放行的四个形状
    // （facilities、facilities/options、facilities/capabilities、POST facilities）
    // 一条都不许被挤掉 —— 白名单是求并，不是二选一。
    // PUT {uuid}（改基础信息与地址）走同一路径：代理按方法透传，写方法由后端端点裁决。
    // 刻意**没有** DELETE 段：仓库不是可物理删除的主数据，停用与归档才是出口，
    // 放行删除形状会让「能不能把一个仓连同它的库存痕迹抹掉」看起来是能。
    // 也不放行 {uuid}/config：行为参数属 8.1.1.3，尚未交付。
    // 与 warehouses|legal-entities|skus 的区别：那几个是 7.1.x 各页共用的**下拉参照项**
    // （只有 id/code/name 等少数列），facilities 是**列表本体**（带库存量、作业量、
    // 货权法人、当地时间）。名字相近但形状不同，混用会让筛选下拉退化成全量传输。
    // ownership 是 7.7.1.1 货主与所有权：capabilities / resolved / report / drift /
    // reference-options / reassign 六个读与动作形状，owners[/{id}[/status]]、
    // rules[/{id}[/versions|/retire]] 两组读与动作。
    // 同样**没有** DELETE 段：货主与货权档都是「当时这批货归谁」的唯一凭据，
    // 停用走 status / 收区间，放行一个删除形状会让「能不能抹掉一条货权痕迹」看起来是能。
    // capabilities/resolved/report/drift/reference-options 是字面量，排在 {id} 之前判别。
    // allocation-policies 是 7.8.1.1 分配策略配置：capabilities / effective / calculation（试算）
    // / runs（落库分摊）/ runs/{runId}（批次明细）/ {id}（详情）/ {id}/versions（换版）
    // / {id}/retire（作废）七个形状。allocation-sales 是销量登记与窗口名册。
    // 同样**没有** DELETE：策略档与分摊批次都是不可改的事实，作废走收口区间，
    // 放行一个删除形状会让「能不能把一条分摊算法配置抹掉」看起来是能。
    // effective / calculation / runs 是字面量，排在 {id} 之前判别，彼此不会互相吃掉。
    // allocation-priority 是 7.8.1.2 优先级与保底：capabilities / floors / floors/{id} /
    // floors/{id}/versions（换版）/ floors/{id}/retire（作废）/ calculation（带保底试算）/
    // runs（带保底落库）/ resolutions（保底生效记录）七个形状。
    // 同样**不新增**任何形状：保底档走 retire（收口区间）、保底生效记录 append-only，
    // 后端根本没有 DELETE 端点，V76 另有 REVOKE DELETE 与不可变触发器各挡一次。
    // 注意：白名单是**按路径**登记的，不区分方法 —— 对同一条已登记路径发 DELETE 会被转发到
    // 后端（并在后端拿到 405）。所以「不能删」这件事的防线在服务端与 DB，不在这里；
    // 这里只负责保证前端源码里真实出现的路径一个都不漏。
    // capabilities / floors / calculation / runs / resolutions 是字面量，排在 {id} 之前判别。
    // allocation-results 是 7.8.2.1 分摊结果查看：capabilities / results（分摊明细）/
    // runs（分摊轮次）/ reconciliation（平台回写一致性核对）/ writebacks（回写状态观测，
    // POST 登记用同一路径）五个形状。
    // 同样**不新增**任何形状：回写观测是 append-only 事实（V189 的 REVOKE UPDATE/DELETE
    // + 不可变触发器各挡一次），后端根本没有 DELETE / PUT 端点。
    // 插入锚点固定在 `allocation-policies` 之后、`allocation-sales` 之前 ——
    // 那个位置**确定在 `inventory\/` 这一组内部**：另一处候选锚点
    // （`allocation-priority` 之后）在这条正则里是**组外**的顶层分支，
    // 插在那里会让 `inventory/allocation-results/...` 整组匹配不上（表现是页面 404，
    // 而 typecheck / lint / vitest 全绿）。
    // capabilities / results / runs / reconciliation / writebacks 是字面量，
    // 彼此不会互相吃掉。
    // consignments 是 7.7.1.2 寄售与代销：capabilities / {id}(+lines|stock-view|settlements|
    // settlement-window|returns|audit|activate|suspend|close) / {id}/lines/{lineId}/sales /
    // settlements/{id}(+confirm) / returns/{id}(+execute|cancel)。
    // 同样**没有** DELETE：销售事实与审计是凭据（append-only），协议行是结算的依据，
    // 放行删除形状等于让「把一笔应付的痕迹抹掉」看起来是能。
    // outbound-orders 是 8.3.1.1 出库单列表：capabilities / 集合读 / 集合写（建单）/
    //   pick-requests（生成拣货任务）/ print / cancel，以及 {id}/transition（状态推进）。
    //   同样**没有** DELETE 出库单，也没有 {id} 单条详情：详情是 8.3.1.2 的职责，
    //   放行一个不存在的形状比 404 更糟（放行不该存在的路径会让界面以为已交付）。
    //   capabilities / pick-requests / print / cancel 是字面量首段，与 {uuid}/transition
    //   靠「首段是字面量还是 UUID」区分，两条不冲突。
    // labor 是 8.5.1.1 作业效率：capabilities / report / ranking / trend /
    // work-tasks / work-tasks/{id} / work-tasks/{id}/(assign|start|complete|exception)。
    // 与 warehouses 同级（不在 inventory/ 之下），因为前端打的是 warehouse/labor/...。
    // 同样**没有** DELETE：人效是提成核算与排班的凭据 —— 作业任务定稿后计量列由数据库
    // 触发器冻结、作业事件 append-only，放行删除等于让「改不掉也能抹掉」。
    // goods-receipts 是 8.2.1.1 收货单：capabilities / {id} / {id}/movements，
    // 以及 POST 建单、PUT 改单、POST {id}/receive 实收、POST {id}/close 关单。
    // 同样**没有** DELETE：收货单是账实凭证，草稿可以改、实收可以追加，
    // 但删一张收货单等于让这次收货从未发生过。
    // capabilities 是字面量，排在 {id} 之前判别。
    // customs-declarations 是 10.5.1.1 报关单：capabilities / 集合读 / 集合写（建单）/
    //   {id} / {id}/(documents|results|events) / PUT {id} 改单 /
    //   {id}/(submit-for-review|review|reject-review|withdraw-review) /
    //   POST {id}/documents 生成单据、POST {id}/documents/{docId}/finalize 定稿、
    //   POST {id}/submit 提交报关行、POST {id}/results 登记海关结果、
    //   POST {id}/amend 改单、POST {id}/cancel 取消。
    // 命名空间与 first-mile-shipments 同级（都在 /warehouse 之下，不在 inventory/ 之下），
    // 因为前端打的是 warehouse/customs-declarations/... —— 报关单的来源就是那张头程发运单。
    // 同样**没有** DELETE：报关单是海关举证凭据，提交痕迹、三单据版本链与结果回执
    // 都是 append-only 审计，放行删除形状等于让「这张单报没报过」变得不可追责。
    // 三条判别顺序不能调换：capabilities 是字面量排在 {id} 之前；
    // documents/{docId}/finalize 是两段 uuid，必须排在单段 {id} 组里用 \/ 分支匹配，
    // 否则会被 {id}(documents) 提前吃掉半截、把 finalize 留在匹配之外。
    // duty-taxes 是 10.5.1.3 关税与税费：capabilities / 集合读 / 税率表(rates + rates/{id} PUT) /
    //   集合写（建估算）/ PUT {id} 改基础 / {id}/(lines|payments|variances) /
    //   POST {id}/(recalculate|lock|void|payments) / GET /variance-analysis。
    // 命名空间与 customs-declarations **同级**（都在 /warehouse 之下、都不在 inventory/ 之下）：
    // 它的输入就是那张报关单，放进 inventory/ 会让 8.2 现场作业与 10 物流税费混成一个前缀。
    // variance-analysis 同样是字面量子路径，必须排在单段 {id} 之前判别，
    // 否则会被 \/[0-9a-f-]+ 之外的分支漏掉（variance-analysis 不是 uuid，{id} 也接不住它）。
    // 同样**没有** DELETE：实缴与差异是 append-only 的举证凭据（V90 收回 UPDATE/DELETE 授权
    // 并钉了触发器），放行删除形状等于让「这票货缴了多少税、差在哪」变得不可追责。
    // trade-documents 是 10.5.2.1 贸易单证：capabilities / 集合读 / 集合写（建包）/
    //   PUT {id} / rules（GET 集合 + POST + {id} 的 PUT）/ templates（同样三形状）/
    //   certifications（同样三形状）/ preference-rules（同样三形状）/
    //   preference-confirmations（只读）/ reminders（GET + {id}/resolve 的 POST）/
    //   {id} / {id}/(documents|checks|reminders|events) /
    //   POST {id}/(submit-for-review|review|reject-review|withdraw-review|check|submit|acknowledge|send-back|cancel) /
    //   POST {id}/documents 生成单证、POST {id}/documents/{docId}/finalize 定稿、
    //   POST {id}/documents/{docId}/preference-claim 登记优惠主张、
    //   POST {id}/documents/{docId}/preference-confirm 业务确认。
    // 命名空间与 customs-declarations / duty-taxes **同级**（都在 /warehouse 之下、都不在 inventory/ 之下）：
    // 它的上游就是那张报关单，放进 inventory/ 会让 8.2 现场作业与 10 物流贸易单证混成一个前缀。
    // 判别顺序：字面量子路径（capabilities/rules/templates/certifications/preference-rules/
    //   preference-confirmations/reminders）排在单段 {id} 之前，否则会被 [0-9a-f-]+ 之外的分支漏掉；
    //   两段 uuid 的 documents/{docId}/xxx 排在单段 {id}/documents 组之后，用 \/ 分支匹配三段，
    //   否则会被单段 {id} 吃掉半截、把 finalize 留在匹配之外。
    // 同样**没有** DELETE：备证资料、校验结论与缺失提醒都是 append-only 的举证凭据，
    //   放行删除形状等于让「这套单证备过没有、被退回过没有」变得不可追责。
    // serials / lots 的明细子路径此前漏登记：白名单里只有集合读
    // （serials 与 lots 各一条），而页面实际在调 serials/stats、serials/{id}、
    // serials/{id}/movements、lots/{id}、lots/{id}/distribution、lots/{id}/movements，
    // 后端 InventorySerialController / InventoryLotController 这些端点都真实存在。
    // 漏一条 = 那些页面在浏览器里拿代理 404，而 pnpm test 直连服务层发现不了。
    // 两组都刻意**没有**在 movements 之外再放行别的动作段：
    // 序列号的 validate-movement 与导入是既有形状，批次只读不写。
    // location-allocations 是 8.1.2.2 库位自动分配，七个形状：
    //   capabilities（能力声明）/ recommend（推荐，纯计算不落库）/ list（决策清单，真分页）
    //   / by-receipt/{receiptId}（某张收货单上的决策）/ 裸 POST（选定一个库位）
    //   / {allocationId}（DELETE = 撤销决策，带原因，不是删数据）/ {allocationId}/release。
    //   判别顺序：capabilities / recommend / list / by-receipt 四条**字面量**子路径排在
    //   [0-9a-f-]+ 之前 —— 「ca」「de」「ad」这类前缀本身就是合法十六进制字符，顺序反了会被
    //   UUID 分支先吃掉半截、再因多出后缀而整条落空。by-receipt 后面那层 uuid 写死，
    //   多一层（/by-receipt/{id}/xxx）仍然 404。
    //   末位**保留**可选量（`)?`），而 8.1.2.1 的 location-trees 刻意不带：
    //   本模块的写入形状就是裸 POST location-allocations（选定），不带就永远 404；
    //   而 location-trees 没有「对整棵树」这一个动作，所以那边必须拒绝裸路径。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    // printing 是 8.3.4.2 面单与标签打印：capabilities / devices / templates(/{id}) /
    //   targets(/{id}(/history)) / reprint-rules / jobs(/{id}(/execute|/cancel|/items/{itemId}/content)) / logs。
    //   jobs/{id}/items/{itemId}/content 是打印内容下载端点：它返回的是打印那一刻落库并校验过的那份
    //   渲染内容（ZPL 指令流或 HTML 打印视图），因此**不能**走下面 text/csv 那条原样透传分支 ——
    //   走 JSON.parse 会把条码指令流解析失败变成 503。这里只放行路径，字节由响应体原样带回。
    //   刻意**没有** DELETE 段：打印凭据（label_print_log）与任务明细都是 append-only 审计凭据，
    //   放行一个删除形状会让「能不能把一条打印痕迹抹掉」看起来是能。
    pattern:
      // distribution / platform-observations / serials / lots / internal-access —— 这些路径此前漏登记，
      // 白名单只在浏览器端生效、单测直连服务层发现不了，漏一条就等于该页面在浏览器里拿代理 404。
      //
      // 7.2.1.2 预占分配明细（只动 reservations 这一支，别人的分支一律没碰）：
      //   reservations                              集合级（7.2.1.1 预占需求列表）
      //   reservations/options                      字面量子路径，7.2.1.1
      //   reservations/capabilities                 字面量子路径，7.2.1.1
      //   reservations/{id}/allocations             本模块：分配明细行（GET）+ 登记分配（POST）
      //   reservations/{id}/allocations/{id}/commit 本模块：承诺分配（POST）
      //   reservations/{id}/allocations/{id}/consume 本模块：消耗出库（POST）
      //   reservations/{id}/timeline                本模块：状态迁移时间线（GET）
      //   reservations/{id}/allocation-options      本模块：下拉项（状态 / bucket 候选 / 登记目标）
      //   reservations/{id}/capabilities            本模块：能力声明（释放与过期见下方 7.2.1.3 段落）
      //
      // 为什么 options / capabilities 排在 [0-9a-f-]+ 那支**前面**：
      //   这两支是字面量子路径，而 [0-9a-f-]+ 能吃下任意十六进制串。若把字面量支放到后面，
      //   `reservations/options` 会先被通配支判成「id=options 的明细」而放行 —— 白名单宁可少放，
      //   不可放错：放错等于凭空开出一个后端不存在的端点入口。
      // 为什么 [0-9a-f-]+ 支排在最后：
      //   它必须排在所有字面量子路径之后，否则 options/capabilities/allocation-options/timeline
      //   全都会被它当成「带 id 的子路径」提前匹配掉（正则 alternation 是**首个命中即返回**，
      //   不是最长匹配 —— 这是与「贪婪」直觉相反、但在这一行正则上真实生效的行为）。
      // reservations/{id}/entries **仍然不放行**：本链路的时间线叫 /timeline，
      //   /entries 不是对外形状。放行一个后端不存在的形状，比 404 更糟。
      //   （reservations/{id}/release 与 /expiry 由 7.2.1.3 就地加进同一支的最内层，
      //     见下方 7.2.1.3 段落。）
      //
      // 尾部几条分支的相对位置（8.5.2.1 库位利用率 / 8.4.2.1 拆解单 / 8.2.2.1 上架 / 8.3.2.1 拣货）：
      //   location-trees → location-utilization → location-allocations → assembly-orders
      //   → disassembly-orders → putaway-tasks → pick-tasks
      // 七条都是**字面量前缀**，两两之间不存在前缀歧义（`location-` 之后第 9 个字符就分叉：
      //   trees 的 t / utilization 的 u / allocations 的 a；assembly / disassembly 在第 2 个字符
      //   分叉；putaway-tasks 与 pick-tasks 在第 1 个字符就分叉），因此彼此调换不改变匹配结果。
      // 仍保留各自车道当时选的位置，只为让下一条车道插进来时看得出该挨着谁：
      //   location-utilization 挨着 location-trees（同属库位命名空间，8.5.2.1 选的位置）；
      //   disassembly-orders 挨着 assembly-orders（8.4.2.1 选的位置）。
      //
      // ⚠️ 8.2.2.1（putaway-tasks）与 8.3.2.1（pick-tasks）**选的是同一个插入点**
      //   （disassembly-orders 之后、当时的最末一条），两条车道必然互撞。
      //   求并时必须**先后排成两条**（如上），**绝不能二选一** —— 少一条就是那个模块
      //   在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
      //   本行最终顺序按模块号升序（8.2.2.1 在 8.3.2.1 前）。
      //
      // 8.5.3.1 出库时效：**就地追加在最后一支 parcels 之后**，既有条目一条没动、也没重排。
      //   四条形状，全部是字面量子路径，没有 [0-9a-f-]+ 通配：
      //     outbound-timeliness/capabilities  四环节能力 + 生效阈值 + 口径声明（GET）
      //     outbound-timeliness/report       时效分布 + 瓶颈 + 超时明细（GET）
      //     outbound-timeliness/order-times   投递订单下单时刻（POST，幂等 upsert）
      //     outbound-timeliness/thresholds    配四环节阈值（PUT，幂等 upsert）
      //   为什么排在 parcels 之后：本模块与 parcels/assembly-orders 等一样是
      //   **顶层字面量分支**（不在 inventory/ 之下），彼此第 1 个字符就分叉
      //   （o / p / a / d），换位置不改变匹配结果；留在末尾是本车道选的位置。
      //   order-times 与 thresholds 是**写入**形状：前者是上游 trade 投递订单时刻的授权入口，
      //   后者是配置。它们只被 /capabilities 的口径声明与本页的阈值展示读到，
      //   但列出它们的代价是零（字面量逐条列，不引入通配）。
      // 7.1.1.3 可用量计算（本行已并入集成头；下面这段留作后续车道的定位参照）：
      //   本车道插在 warehouse 的**第一处** `inventory\/` 分组里，紧跟
      //   `sellable-policies(...)` 之后、`stocktakes(...)` 之前：
      //     ...|policies(?:\/[0-9a-f-]+)?|sellable-policies(...)|sellable(?:\/(?:capabilities|skus))?|stocktakes(...)
      //   **只改了第一处**：该正则里 `inventory\/` 分组出现两次（内容逐字相同的历史重复），
      //   alternation 首个命中即返回，所以第一处生效即足够，第二处不动以免求并时多一处冲突点。
      //   为什么排在 sellable-policies 之后而不是之前：`sellable` 是 `sellable-policies` 的前缀，
      //   放在其后即使 `sellable(?:\/(?:capabilities|skus))?` 只吃掉 `sellable` 五字符，
      //   锚点 `$` 也会让它失败并回退到 sellable-policies 分支，结论无歧义。
      //   为什么没有 `[0-9a-f-]+` 通配歧义：`s` / `l` 非十六进制字符，
      //   `sellable` 永远匹配不上任何 `\/[0-9a-f-]+` 分支（`b`、`e`、`d`、`c`、`a`、`f` 是，
      //   但整串匹配不上），所以不存在「被当成 id 凭空放行」的风险。
      //   放行形状：GET /warehouse/inventory/sellable、/sellable/skus、/sellable/capabilities。
      //   刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，算一次不构成一次库存业务。
      //
      // 7.2.1.3 释放与过期（**就地扩展 reservations 同一支**，别人的分支一条没碰）：
      //   reservations/release-candidates          字面量集合级：过期候选清单 + 汇总
      //   reservations/expiry-runs                 字面量集合级：批量过期清理的运行历史
      //   reservations/expiry-runs/{id}            单次运行详情
      //   reservations/release-records             字面量集合级：释放凭据（可按预占过滤）
      //   reservations/release-options             字面量集合级：下拉项 + 过期规则
      //   reservations/release-capabilities        字面量集合级：本链路的能力声明
      //   reservations/expire-overdue              字面量集合级：批量过期清理（POST）
      //   reservations/{id}/release                手动释放（POST）
      //   reservations/{id}/expiry                 补写 / 清空过期时刻（POST）
      //
      //   本车道新增的 7 条**字面量子路径全部插在 [0-9a-f-]+ 那支之前**，位置在既有
      //   `options` / `capabilities` 之后。7.2.1.2 的注释已写明原因：alternation 是
      //   **首个命中即返回**，字面量支放到通配支后面，`release-candidates` 会被判成
      //   「id=release-candidates 的明细」而凭空放行一个不存在的形状。
      //   `release` / `expiry` 挂在 `[0-9a-f-]+(...)?` 最内层，与 allocations / timeline 同级。
      //
      // 8.2.2.1 上架任务 / 8.3.2.1 拣货任务：见上方「尾部几条分支的相对位置」段落 ——
      //   两条都是**顶层**分支（warehouse/pick-tasks/... 、warehouse/putaway-tasks/...），
      //   不在 inventory/ 之下，也不挂在 goods-receipts / outbound-orders 底下。
      //   各自形状逐条列在自己的 allowlist 测试里，两条车道各自新增的分支都保留。
      // 7.2.2.1 的 sellable-policies 刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，
      //   算一次不构成一次库存业务（7.8.1.2 那条车道已查证：白名单按路径登记、不区分方法，
      //   所以同一路径的 DELETE 会被转发到后端，「不能删」的防线因此在服务端而非白名单）。
      // 8.3.2.2 波次与合并拣货：**顶层**分支 warehouse/pick-waves/...，不挂在 outbound-orders 之下 ——
      //   一个波次横跨多张出库单，它不属于任何一张出库单（同 pick-tasks 的理由）。
      //   放行形状：capabilities / rules / rules/{id}/retire / candidates / {id} /
      //   {id}/release / {id}/complete / {id}/cancel / {id}/tasks/{taskId}/detach，加集合级 GET 与 POST。
      //   **capabilities / rules / candidates 三个字面量子路径全部排在 [0-9a-f-]+ 之前**：
      //   alternation 是首个命中即返回，而 `candidates` 的 c/a/n/d/i/t/e/s 全落在 [0-9a-f-] 里，
      //   排到通配支后面会被判成「id=candidates 的波次」而凭空放行一个不存在的形状。
      //   刻意**不含** pick-tasks/{id}/waves 那种形状：波次不是某一张拣货任务的子资源。
      // 8.3.3.1 复核扫描：**顶层**分支 warehouse/recount-scans/...，不挂在 outbound-orders 之下 ——
      //   一张出库单可以复核多次（判异常 → 退回拣货 → 重拣 → 再复核），每次是一张独立复核单，
      //   挂成 outbound-orders/{id}/recount 会让契约看起来像「一张单只复核一次」。
      //   放行形状：capabilities / rules / rules/{id} / rules/{id}/retire / {id} /
      //   {id}/(open-box|scans|settle|complete|escalate|cancel) / {id}/exceptions/{exceptionId}/resolve，
      //   加集合级 GET 与 POST。
      //   **capabilities / rules 两个字面量子路径排在 [0-9a-f-]+ 之前**：alternation 是首个命中即返回。
      //   **两段 uuid 的 exceptions/{exceptionId}/resolve 排在单段 {id} 组里用 \/ 分支匹配**，
      //   否则会被 {id}(exceptions) 提前吃掉半截、把 resolve 留在匹配之外。
      //   刻意**不含** pick-tasks/{id}/recount 那种形状：复核接的是「已完成拣货 + 出库单待复核」，
      //   不接某一张拣货任务的子资源。
      // 7.2.2.2 安全库存（safety-stock，17 个形状）：阈值分档（policies / policies\/effective /
      //   policies\/{id} / policies\/{id}\/versions / policies\/{id}\/retire）、日均销量登记
      //   （sales 读写）、评估与预警（evaluate / evaluations / alerts / alerts\/{id} /
      //   alerts\/{id}\/acknowledge / alerts\/{id}\/resolve）、补货建议
      //   （suggestions / suggestions\/{id}\/acknowledge / suggestions\/{id}\/dismiss）与能力声明
      //   （capabilities）。
      //   判别顺序：`capabilities` / `effective` / `sales` / `evaluate` / `evaluations` 这些
      //   **字面量**子路径一律排在各自的 `[0-9a-f-]+` 通配**之前**判别，否则 `alerts/capabilities`
      //   会被当成 `alerts/{id}` 凭空放行一个后端并不存在的形状。
      //   刻意**不含** DELETE 段：阈值作废走 retire（关区间），预警与建议都要留档复盘，
      //   能不能物理删掉的答案是不能 —— 服务端无 DELETE 端点（405）+ V114 的 REVOKE DELETE。
      //   注意本文件的 pattern 是**按路径登记、不区分方法**的，所以读写形状合并在一支里。
      //   刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，算一次不构成一次库存业务。
      //
      // shipment-handovers 是 8.3.4.1 发货交接的六个形状（追加在 packing-orders 之后，
      // 仍是同一个 alternation 里的一个分支，没有改动任何既有分支）：
      //   capabilities   能力与边界声明（含「轨迹归 10」「平台回写归 2」）
      //   [id]           详情；[id]/completion 判据；[id]/waybills 登记承运商与运单号
      //   [id]/confirm   交接确认 = 实发扣减 + 预占消费（同一事务）
      //   [id]/cancel    作废（库存不动）
      // 同样**不含** DELETE/PUT：交接是事实、流水是 append-only（V123 的守卫会拒），
      // 作废一次就不能复活 —— 要更正就作废重开一张。
      // 10.2.2.1 包裹详情：**顶层**分支 warehouse/parcels/...，不挂在 packing-orders 之下 ——
      //   10 物流查轨迹、12 财务做运费归集时手里拿的是 parcel_id，不是作业单 id
      //   （打包作业单可以失败退回复核再重打包，每次一张独立作业单，挂上去会让契约看起来
      //   像「一个包裹只有一个作业单」）。
      //   放行形状：capabilities / {id}。
      //   **capabilities 排在 [0-9a-f-]+ 之前**：alternation 是首个命中即返回，而
      //   capabilities 的 c/a/p 里 p 落在 [0-9a-f-] 之外，判别顺序错了会凭空放行一个
      //   后端并不存在的「id=capabilities 的包裹」。
      //   刻意**不含任何写操作**：本模块是 8.3.3.2 包裹凭据的只读视图，
      //   包裹与明细项是事实（V121 的守卫直接拒已结束的打包单再改），没有 POST/PUT/DELETE 端点。
      /^(inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|sellable(?:\/(?:capabilities|skus))?|safety-stock(?:\/capabilities|\/policies(?:\/effective|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|\/sales|\/evaluate|\/alerts(?:\/[0-9a-f-]+(?:\/(?:acknowledge|resolve))?)?|\/suggestions(?:\/[0-9a-f-]+\/(?:acknowledge|dismiss))?|\/evaluations)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|adjustments(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/remove)?|\/attachments(?:\/[0-9a-f-]+\/remove)?|\/(?:submit|approve|reject|cancel|post))?)?|adjustment-sources\/defective-dispositions(?:\/[0-9a-f-]+\/instructions)?|adjustment-approvals(?:\/capabilities|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:update|status))?|\/approvers(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?|\/pending|\/evaluate|\/batch\/(?:approve|reject)|\/valuations|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|approve|reject))?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-results(?:\/(?:capabilities|results|runs|reconciliation|writebacks))?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|reservations(?:\/options|\/capabilities|\/release-candidates|\/expiry-runs(?:\/[0-9a-f-]+)?|\/release-records|\/release-options|\/release-capabilities|\/expire-overdue|\/[0-9a-f-]+(?:\/allocations(?:\/[0-9a-f-]+\/(?:commit|consume))?|\/timeline|\/allocation-options|\/capabilities|\/release|\/expiry)?)?|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities))?|warehouses|legal-entities|skus|quality-statuses|locations|labor(?:\/capabilities|\/report|\/ranking|\/trend|\/work-tasks(?:\/[0-9a-f-]+(?:\/(?:assign|start|complete|exception))?)?)?|goods-receipts(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:movements|receive|close))?)?|goods-receipt-discrepancies(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:evidence|decide|claim(?:\/(?:submit|withdraw))?|close))?)?|processing-orders(?:\/capabilities|\/boundary|\/issues|\/executions)?|first-mile-shipments(?:\/capabilities|\/source-candidates|\/[0-9a-f-]+(?:\/(?:transit-legs|confirm|dispatch|cancel))?)?|transit-trackings(?:\/capabilities|\/alerts(?:\/evaluate-all|\/[0-9a-f-]+\/(?:acknowledge|close))?|\/thresholds(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:events|receipt|close)|\/alerts\/evaluate)?)?|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints))?)?|lots|customs-declarations(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:documents|results|events|submit-for-review|review|reject-review|withdraw-review|submit|amend|cancel)|\/documents\/[0-9a-f-]+\/finalize)?)?|duty-taxes(?:\/capabilities|\/variance-analysis|\/rates(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:lines|payments|variances|recalculate|lock|void))?)?|trade-documents(?:\/capabilities|\/rules(?:\/[0-9a-f-]+)?|\/templates(?:\/[0-9a-f-]+)?|\/certifications(?:\/[0-9a-f-]+)?|\/preference-rules(?:\/[0-9a-f-]+)?|\/preference-confirmations|\/reminders(?:\/[0-9a-f-]+\/resolve)?|\/[0-9a-f-]+(?:\/(?:documents|checks|reminders|events|submit-for-review|review|reject-review|withdraw-review|check|submit|acknowledge|send-back|cancel)|\/documents\/[0-9a-f-]+(?:\/finalize|\/preference-claim|\/preference-confirm))?)?|inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-results(?:\/(?:capabilities|results|runs|reconciliation|writebacks))?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|vmi(?:\/capabilities|\/agreements(?:\/[0-9a-f-]+(?:\/(?:activate|suspend|close|water-levels|settlement-window|audit|sales|lines(?:\/[0-9a-f-]+\/sales)?|replenishment-signals|snapshots|settlements(?:\/[0-9a-f-]+)?))?)?|\/replenishment-signals(?:\/[0-9a-f-]+(?:\/(?:acknowledge|dispatch|fulfil|cancel))?)?|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?)|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints|config(?:\/(?:options|impacts|changes)|\/(?:shifts)(?:\/[0-9a-f-]+)?)?))?)?|location-trees(?:\/capabilities|\/[0-9a-f-]+(?:\/locations(?:\/[0-9a-f-]+)?)?)|location-utilization(?:\/capabilities|\/[0-9a-f-]+(?:\/locations\/[0-9a-f-]+)?)|location-allocations(?:\/(?:capabilities|recommend|list|by-receipt\/[0-9a-f-]+|[0-9a-f-]+(?:\/release)?))?|assembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:issue|cancel))?)?|disassembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?|putaway-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|start|scan|complete|cancel))?)?|pick-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|picks|exceptions(?:\/[0-9a-f-]+\/resolve)?|complete|reopen|cancel))?)?|pick-waves(?:\/capabilities|\/rules(?:\/[0-9a-f-]+\/retire)?|\/candidates|\/[0-9a-f-]+(?:\/(?:release|complete|cancel)|\/tasks\/[0-9a-f-]+\/detach)?)?|recount-scans(?:\/capabilities|\/rules(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/(?:open-box|scans|settle|complete|escalate|cancel)|\/exceptions\/[0-9a-f-]+\/resolve)?)?|packing-orders(?:\/capabilities|\/materials(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/completion|\/parcels(?:\/[0-9a-f-]+\/(?:items|measure|label))?|\/complete|\/fail|\/cancel)?)?|shipment-handovers(?:\/capabilities|\/[0-9a-f-]+(?:\/completion|\/waybills|\/confirm|\/cancel)?)?|parcels(?:\/capabilities|\/[0-9a-f-]+)|outbound-timeliness(?:\/(?:capabilities|report)|\/order-times|\/thresholds))$/,
  },
  {
    // trade（09 订单履约）：第一条 9.x 模块车道 9.3.2.2（FBA 与自发货选择）落地，
    // 所以这里**首次**放行子路径 —— 仍然逐条列出真实存在的形状，不做通配。
    //
    // fulfillment/routing 是 9.3.2.2 的全部端点，七类：
    //   capabilities  能力与上游可用性声明
    //   mcf-authorizations / cost-profiles / stock-snapshots
    //                   三张输入表：集合读 + POST 维护（各两个形状）
    //   evaluations    路径评估（只读试算，POST）
    //   decisions      集合读 + POST 落决策 + {uuid} 详情 + {uuid}/status 状态流转
    //   switches       缺货切换记录（集合读；append-only，**没有**任何写形状）
    //
    // processing 是 6.4.1.1 加工单（卷 06 采购与供应，本车道首次放行）：
    //   capabilities  能力与上下游可用性
    //   requirements   按 BOM 版本试算组件需求量（catalog 规则 R7，只读不落库）
    //   orders         集合读 + POST 建单 + {uuid} 详情
    //   orders/{uuid}/status    推进状态（下达 / 开工 / 报完工 / 登记入库 / 作废）
    //   orders/{uuid}/progress  进度登记（不改生命周期）
    //   orders/{uuid}/components/issue  登记组件领用进度（**不改库存**）
    //   factories      加工厂集合读 + POST 新建；factories/{uuid} 取档案与更新
    // 三段形状（orders/{uuid}/components/issue）排在单段 {uuid} 之后，
    // 否则会被单段分支吃掉半截、把 /issue 留在匹配之外。
    // 刻意**没有** DELETE：加工单与事件流都是 append-only 的凭据。
    //
    // 判别顺序：字面量子路径（capabilities / mcf-authorizations / cost-profiles /
    //   stock-snapshots / evaluations / switches）排在单段 {uuid} 之前，
    //   否则会被 [0-9a-f-]+ 分支漏掉。两段形状（decisions/{id}/status）排在
    //   单段 decisions/{id} 之后。
    //
    // 刻意**没有** DELETE：决策、切换与事件流都是 append-only 的举证凭据
    // （V92 的 REVOKE 与触发器已经这么钉了），放行删除形状等于让界面以为可以。
    // fulfillment/allocation 是 9.3.2.1 分仓规则的全部端点（**新的一组**，不并进上面的
    // fulfillment/routing 组 —— 那是 9.3.2.2 的形状，混在一起会让「路由决策」与
    // 「分仓决策」在权限与日志上分不开）：
    //   capabilities  能力与上游可用性声明
    //   rules         分仓规则集合读 + POST 维护（优先级、兜底策略与五维权重）
    //   warehouses    仓库档案集合读 + POST 维护（**货权法人登记在这里**）
    //   stock-estimates / shipping-estimates
    //                 可用量与运费时效估算：集合读 + POST 维护（各两个形状）
    //   simulations   分仓试算（POST，只读不落库）
    //   allocations   集合读 + POST 执行分配 + {uuid} 详情 + {uuid}/status 状态流转
    //   holds         挂起清单（集合读；挂起是 append-only 的凭据，**没有**任何写形状）
    // 判别顺序与 routing 组一致：字面量子路径排在 allocations/{uuid} 之前，
    // 否则会被 [0-9a-f-]+ 分支吃掉；两段形状排在单段 {uuid} 之后。
    // fulfillment/dropship 是 9.6.1.1 代发模式配置（**新的一组**，不并进 routing / allocation）：
    //   capabilities   能力与上游可用性声明
    //   shop-refs      2.3.1 店铺只读投影：集合读 + POST 维护
    //   supplier-refs  3.6.2 供应商报价只读投影：集合读 + POST 维护
    //   supplier-stocks 供应商可供货量观测：集合读 + POST 维护
    //   configs        集合读 + POST 维护 + {uuid} 详情 + {uuid}/status + {uuid}/lines
    //   evaluations    代发可售标记：集合读 + POST 评估
    //   events         事件流（集合读；append-only，**没有**任何写形状）
    // 判别顺序与既有两组一致：字面量子路径排在 configs/{uuid} 之前，
    // 两段形状（configs/{uuid}/status、configs/{uuid}/lines）排在单段 {uuid} 之后。
    // 刻意**没有** DELETE：可售标记与事件流是 append-only 的判定凭据。
    // fulfillment/dropship-orders 是 9.6.1.2 代发单与供应商下单（**新的一组**）：
    //   capabilities    能力与上游可用性声明
    //   endpoints        供应商代发接口登记：集合读 + POST 维护（只存 credential_ref）
    //   orders           集合读 + POST 建单 + {uuid} 详情
    //   orders/{uuid}/confirm-payment 人工确认收款（9.1 无支付状态列，见 V122 文件头二）
    //   orders/{uuid}/dispatch        提交供应商
    //   orders/{uuid}/supplier-ack    人工录入供应商回执（单号 + 实际成本）
    //   orders/{uuid}/shipment        记录供应商发货与运单号
    //   orders/{uuid}/fail            人工置失败（必落 ORDER_FAILED 告警）
    //   alerts           差异告警工作台：集合读
    //   alerts/{uuid}/acknowledge     处置告警（只改处置字段，不删历史行）
    //   events           事件流（集合读；append-only，**没有**任何写形状）
    // 刻意**没有** DELETE：告警与事件流是「已发生差异」的凭据，删掉等于抹掉历史。
    // fulfillment/pending-shipment 是 9.4.1.1 待发货（**新的一组**，不并进上面四组）：
    //   capabilities  能力与上游可用性声明（含「平台 SLA 无上游、8.3.4.2/8.3.4.1/10 未交付」）
    //   sync          从 8.3.3.2 授权只读接口同步已完成打包的出库单（幂等投影，**唯一的写入口**）
    //   board         分组看板：仓库 / 承运渠道 / 承运商 / 发货时限 四选一（真 GROUP BY）
    //   units         待发货清单：集合读（真分页 + 真筛选 + 排序白名单）
    //   units/{uuid}  单元详情（单头 + 包裹 + 运单 + 时间线 + 完成判据）
    //   units/{uuid}/sla      登记发货时限（平台 SLA 无上游可读，登记而不伪造）
    //   units/{uuid}/cancel   作废单元（退回仓库，不产生发货记录）
    //   batch-tasks   集合读 + POST 建批量任务（{uuid} 形状见下）
    //   batch-tasks/{uuid}            任务详情（任务头 + 逐条执行结果 + 时间线）
    //   batch-tasks/{uuid}/execute     执行（逐条推进；任务状态由结果算出，不接受直接指定）
    //   batch-tasks/{uuid}/cancel      取消任务（终态不可回退）
    //   batch-tasks/{uuid}/waybill-acks 登记承运商运单回执
    //   batch-tasks/{uuid}/labels       回填包裹面单打印凭据（8.3.4.2 未交付，本模块不渲染 ZPL）
    // 判别顺序与既有两组一致：字面量子路径（capabilities / board / units / batch-tasks）
    // 排在 [0-9a-f-]+ 分支之前，否则会被单段分支吃掉半截。
    // 刻意**没有** DELETE：待发货单元与批量任务都带凭据，要更正就作废重开一张。
    // 10.1.1.2 渠道配置（**本条目是新增一条 services[] 元素，不是往既有 channel 分支的
    // alternation 里并**）。同批 4 条车道共享这一个文件，而每个服务在 services[] 里是**一段
    // 超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然判冲突
    // （结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路，
    // 插入锚点固定在 `prefix: 'trade/'` **之前** —— 那样本车道与其他三条车道的插入点
    // 被既有条目隔开，git 能干净合并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-channels                    集合读（GET，真分页 + 筛选）/ POST 登记渠道
    //   logistics-channels/capabilities       能力声明（未交付段 available=false）
    //   logistics-channels/overview           全量概览（不受筛选与分页影响）
    //   logistics-channels/eligibility        🔴 渠道限制判定（POST，只读无副作用）
    //   logistics-channels/{uuid}             详情（GET）/ DELETE 撤下渠道配置
    //
    // 判别顺序：字面量子路径（capabilities / overview / eligibility）**排在
    // [0-9a-f-]+ 分支之前**。写反了的话 `p`（eligibility 的首字母）落在
    // `[0-9a-f-]` 里，会被 uuid 分支提前吃掉 —— 而 `logistics-channels/p` 根本不是 uuid，
    // 正则会静默匹配失败，或者更糟：拼出一个「id=capabilities 的渠道」被凭空放行。
    // 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    // 只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-channels/{uuid}/transit-times` / `/limits`
    //     —— 10.1.1.3 时效与限制未交付
    //   * `logistics-channels/{uuid}/rates` —— 10.1.2.1 运费试算未交付
    //   * `logistics-channels/{uuid}/quotes`    —— 10.1.2.2 渠道比价未交付
    //   * `logistics-channels/sync` —— 本模块不经任何跨服务同步：承运商档案同库
    //     （10.1.1.1 已落在 channel），没有需要投影的上游
    //   * `logistics-channels/{uuid}/carriers` —— 所属承运商是**建渠道时**的入参，
    //     不是一个可以单独 GET 的资源（承运商档案在 /channel/carriers 读）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    //    `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行，
    //    症状是有人 GET /api/channel/logistics-channels/c 会拿到一个「渠道 id 是 c」
    //    的请求打到后端，而不是被白名单当场 404。
    // ⚠️ 字面量子路径**排在** uuid 段之前：写反的话 `capabilities/overview/eligibility`
    //    里以 [0-9a-f] 开头的那个会被 uuid 分支吃掉一半。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-channels(?:\/(?:capabilities|overview|eligibility|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
    // 裸路径 logistics-channels **放行**：它就是本模块的集合读 + POST 登记入口。
  },
  {
    // 12.3.1.3 延迟结算（finance 服务，卷 12「财务核算」L3 12.3.1 结算与对账下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组中段**（`logistics-channels` 那条之后）----
    //   本车道与同批其它车道的插入点相距 **8000 行以上**（它们的锚点都在数组末尾附近），
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「deferred-settlement」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `deferred` 这个串
    //   （逐条 `pattern:` 实测：命中数 0），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //   ⚠️ 与同服务的兄弟模块首 token **互不相同**：`settlement`（12.3.1.1 结算单）、
    //   `transactions`（12.3.1.2 交易明细）、`deferred-settlement`（本模块）——
    //   三条正则两两不相交，吞掉的表现是页面 404，而 typecheck / lint / vitest 全绿。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   deferred-settlement                    列表（GET，真分页 + 真筛选 + 两套口径汇总）
    //   deferred-settlement/capabilities       能力声明（哪些上游真接通 + 缺口归谁）
    //   deferred-settlement/options            筛选项（状态 / 平台 / 店铺 / 币种 / 结算模式）
    //   deferred-settlement/rules              结算规则清单（GET）/ 登记规则（POST）
    //   deferred-settlement/summary            在途资金统计（GET：两套口径 + 预计到账时间）
    //   deferred-settlement/scan               扫描已发货订单入台账（POST）
    //   deferred-settlement/{uuid}             台账明细（GET：单头 + 事件流）
    //   deferred-settlement/{uuid}/void        作废（POST，终态）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / rules / summary / scan）排在 {uuid} 段之前判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   deferred-settlement/{uuid}/arrival      —— 🔴 到账登记属 12.3.1.1 结算单，本模块**只读**它的到账事实
    //   deferred-settlement/{uuid}/profit        —— 🔴 利润（13）**未交付**
    //   deferred-settlement/{uuid}/reconcile     —— 🔴 对账任务（12.3.2.1）**未交付**
    //   deferred-settlement/{uuid}/sync          —— 🔴 平台结算报告自动同步（卷 02）**未交付**
    //   deferred-settlement/{uuid}/edit|delete   —— 台账行没有改、没有删（作废走 status='VOID'）
    //   deferred-settlement/not-a-uuid           —— 非 UUID 段不应被 uuid 分支吃掉
    //   deferred-settlement/{uuid}/not-a-shape   —— 本模块的动作只有 void
    //   deferred-settlement/settlement-deferred  —— 首 token 写错（12.3.1.1 占的是 `settlement`）
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^deferred\-settlement(?:\/(?:capabilities|options|rules|summary|scan|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/void)?))?$/,
  },
  {
    // 16.5.1.1 黑名单（channel 服务，卷 16 合规与风险 / L3 16.5.1 买家风控下的第一格）。
    //
    // 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 channel 分支的 alternation 里
    // 并分支 —— 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，
    // retry 不会自愈）。既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点固定在 10.1.1.2 `logistics-channels` 那一条**之后**、
    //      14.5.2.1 `attributions` 那一条**之前** ----
    //   同批其它三条车道的锚点分别是 `prefix: 'identity/'` 之前、`// 11.4.1.1 平台纠纷列表`
    //   之前、数组末尾（`buyer-risk-scores` 之后），**与本条互不重叠**。
    //
    // ---- 首 token「buyer-blacklist」的零命中核对结论 ----
    //   本文件既有 140+ 条 services[] 正则里，**没有任何一条**含 `buyer-blacklist` 这个串
    //   （既有的是 `buyer-risk-scores`（16.5.1.2）、`buyer-emails`（15.1.2.1，在 crm 前缀下）、
    //   `influencers/.../blacklist`）—— 首 token 逐个核过，**命中 0**。
    //   `buyer-blacklist` 与 `buyer-risk-scores` 只差两个字符，**互不为前缀**，不会互相吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   buyer-blacklist/capabilities                能力声明（未交付段 available=false + ownerModule）
    //   buyer-blacklist/entries                     黑名单清单（GET，真分页 + 真筛选 + 汇总计数）
    //   buyer-blacklist/entries                     手工录入黑名单（POST）
    //   buyer-blacklist/entries/auto                自动录入（POST，来自风控信号）
    //   buyer-blacklist/entries/{uuid}              登记详情（GET；头 + 状态流转留痕 + 申诉单）
    //   buyer-blacklist/entries/{uuid}/release       解除（POST；**必须带理由**，追加一条留痕）
    //   buyer-blacklist/entries/{uuid}/appeals      提交申诉（POST）
    //   buyer-blacklist/appeals/{uuid}/decision     申诉裁决（POST；通过 ⇒ 解除，驳回 ⇒ 维持）
    //   buyer-blacklist/check                       下单时检查（POST；命中就落一条订单拦截标记）
    //   buyer-blacklist/flags                       订单拦截标记清单（GET，真分页 + 真筛选）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `auto` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ 字面量子路径（capabilities / flags / check / entries / entries/auto）排在
    //    uuid 段之前判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `buyer-blacklist` —— 后端**没有**「一条汇总列表接口」，一切入口都带子路径。
    //     末尾**刻意不带 `?`**：带了等于放行裸路径。
    //   * `buyer-blacklist/entries/{uuid}/delete` —— 🔴 黑名单是判定凭据，**只能解除不能删除**
    //     （删除等于把历史抹掉）；本模块的解除入口是 `/release`。
    //   * `buyer-blacklist/entries/{uuid}/status` —— 改黑名单状态没有第二个入口，
    //     状态只能由「登记 / 解除 / 申诉裁决」这些**留痕事件**推进。
    //   * `buyer-blacklist/entries/{uuid}/flags` —— 拦截标记有自己的集合读 `/flags`。
    //   * `buyer-blacklist/overview` / `/stats` —— 本模块**没有**这两个形状；
    //     概览在 `/entries` 的 `counts` 里。
    //   * `buyer-blacklist/appeal` —— 申诉是**挂在某条登记下的子资源**，
    //     形状是 `/entries/{uuid}/appeals` 与 `/appeals/{uuid}/decision`，没有裸的 `/appeal`。
    //   * `buyer-blacklist/intercept` / `/orders/{uuid}/block` —— 🔴 改订单状态、
    //     执行拦截是 **9.2.1.3 异常拦截**（trade 服务，台账 D4 已交付）的职责；
    //     按模块边界纪律**跨服务不写对方的表**，本模块只产出**建议动作**与订单拦截标记，
    //     **不代劳**、也**不放行**指向它不存在的入口。
    //   * `buyer-blacklist/aftersales` —— 🔴 开售后单是 **11.1.1.1 售后单列表**
    //     （trade 服务，台账 D4 已交付）的职责；本模块**不代建售后单**，也不放行入口。
    //   * `buyer-blacklist/entries/not-a-uuid` —— 非 UUID 段不应被 uuid 分支吃掉。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^buyer\-blacklist\/(?:capabilities|flags|check|entries(?:\/auto|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:release|appeals))?)?|appeals\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/decision)$/,
  },
  {
    // 14.5.2.1 带货归因（channel 服务，卷 14 广告与增长 / L3 14.5.2 达人效果下的第三格）。
    //
    // 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 channel 分支的 alternation 里
    // 并分支 —— 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，
    // retry 不会自愈）。
    //
    // ---- 插入锚点固定在 10.1.1.2 `logistics-channels` 那一条**之后** ----
    //   与同批其它三条车道各自用过的锚点（`auth/` 前后 / `listing-claims` 之前 /
    //   `purchase-followups` 之后 / `listing-performance` 之后 / `one-click-publish` 之后 /
    //   数组开头 / 数组末尾）**全部不重叠**，本车道这一段是**物流区**，离它们都隔着
    //   上千行 ⇒ git 能干净求并。
    //
    // ---- 首 token「attributions」的零命中核对结论 ----
    //   本文件既有 140+ 条 services[] 正则里，**首 token** 是 `attributions` 的**一条也没有**：
    //   既有含 `attributions` 这个串的两条是 `product-attribution/attributions`
    //   与 `aftersales/reasons/attributions` —— 它们的 `attributions` 是**第二段**
    //   （首 token 分别是 `product-attribution` 与 `aftersales`），且两条都挂在
    //   `prefix: 'trade/'` 下，**根本不会被 `channel/` 这个前缀取到**。
    //   本条目的首 token `attributions` 是**独立字面量**，不会被既有分支的前缀吃掉，
    //   也不会吃掉它们（`^attributions` 与 `^product-attribution` / `^aftersales` 互不为前缀）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   attributions/capabilities            能力声明（平台带货数据同步 / 佣金进利润表 13.4 /
    //                                          订单级事实三个维度 available=false + ownerModule）
    //   attributions/options                 下拉项（归因模型 / 分摊基准 / 退款口径 / 佣金口径 /
    //                                          计提状态 / 平台 / 店铺 / 币种）
    //   attributions/sync-status             逐平台的带货数据同步接入情况
    //   attributions/sync                    触发一次同步（适配器未接入 ⇒ 明确 409，不写数据）
    //   attributions/rules                   归因规则列表（GET，真分页 + 真筛选）
    //   attributions/rules/current           当前生效的归因规则（GET）
    //   attributions/rules                   配置归因规则（POST；保存新规则时旧的自动停用）
    //   attributions/run                     归因批次（POST；没有生效规则 ⇒ 409）
    //   attributions/records                 归因记录列表（GET，真分页 + 多维筛选 + 真排序）
    //   attributions/records/{uuid}          归因详情（GET）
    //   attributions/records/{uuid}/accrual  佣金计提状态变更（POST）
    //   attributions/report                  达人效果报表（GET，真分页 + 真筛选）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `run` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ 字面量子路径（capabilities / options / sync-status / sync / rules / rules/current /
    //    run / report / records）排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，
    //    顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `attributions` —— 后端没有「一条不存在的汇总接口」。
    //     末尾**刻意不带 `?`**：带了等于放行裸路径。
    //   * `attributions/export`              —— 本模块不提供导出
    //   * `attributions/records/{uuid}/delete` —— 归因是事实，不提供删除
    //   * `attributions/profit`               —— 🔴 13.4 利润（reporting 服务）**未交付**，
    //     放行它等于让界面以为「佣金成本已经进利润表了」
    //   * `attributions/{uuid}`               —— 没有「按 id 取单条」这个形状（详情走 records/{uuid}）
    //   * `attributions/records/not-a-uuid`   —— 非 UUID 段不应被 uuid 分支吃掉
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^attributions(?:\/(?:capabilities|options|sync-status|sync|rules(?:\/current)?|run|report|records(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/accrual)?)?))$/,
  },
  {
    // 10.3.1.1 轨迹查询（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然
    // 判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 插入锚点固定在 10.1.1.2 `logistics-channels` 那条**之后**、10.1.1.3
    // `logistics-transit-limits` 那条**之前** —— 三个锚点被既有条目隔开，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-tracking/capabilities      能力声明（未交付段 available=false）
    //   logistics-tracking/overview          全量概览（不受筛选与分页影响）
    //   logistics-tracking/subjects          轨迹清单（GET，真分页 + 筛选）
    //   logistics-tracking/subjects/sync     从上游同步运单集合（POST，唯一写入口之一）
    //   logistics-tracking/subjects/{uuid}   单票轨迹时间线 + 时效对比（GET）
    //   logistics-tracking/events            事件流（GET，真分页 + 筛选）/ 承运商推送（POST）
    //   logistics-tracking/pull-tasks        拉取任务列表（GET）/ 建轮次（POST）
    //   logistics-tracking/pull-tasks/{uuid} 拉取任务详情（含逐票结果，GET）
    //
    // ⚠️ **裸路径 `logistics-tracking` 不放行**：后端这一组端点**没有**裸集合读
    // （不像 10.1.1.2 的 `logistics-channels` 那样有），契约里也不存在
    // `/channel/logistics-tracking` 这一条。多放一段会让一个**还不存在**的路径
    // 看起来像已交付 —— 界面点下去得到 404，而后端其实从来就没实现它。
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / overview / sync）**排在 uuid 段之前**。
    // 写反的话 `s`(sync) 落在 `[0-9a-f]` 里会被 uuid 分支提前吃掉 —— 而
    // `logistics-tracking/subjects/sync` 根本不是 uuid，正则会静默匹配失败，
    // 或者更糟：拼出一个「id 是 sync 的轨迹主体」被凭空放行。
    // 同理**裸 uuid 也不放行**：`logistics-tracking/subjects/{uuid}` 之外的
    // `logistics-tracking/{uuid}` 不是任何端点。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    //    `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行，
    //    症状是有人 GET /api/channel/logistics-tracking/c 会拿到一个「主体 id 是 c」
    //    的请求打到后端，而不是被白名单当场 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-tracking/subjects/{uuid}/events` —— 时间线就在详情里，没有第二个形状
    //   * `logistics-tracking/events/{uuid}`             —— 事件按事件 ID 定位，不按 id
    //   * `logistics-tracking/pull-tasks/{uuid}/items`   —— 逐票结果在任务详情里
    //   * `logistics-tracking/exceptions` / `/dispositions`
    //     —— 异常**处置**归 10.3.1.2（未交付），本页只标记异常节点
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-tracking\/(?:capabilities|overview|events|pull\-tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|subjects(?:\/sync|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 10.3.1.2 异常件（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然
    // 判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 插入锚点固定在 10.3.1.1 `logistics-tracking` 那条**之后**、10.1.1.3
    // `logistics-transit-limits` 那条**之前** —— 另外三条车道的锚点
    // （10.3.2.2 / 10.6.2.1 / 10.7.1.2）都落在别处，四个锚点互不重叠，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-tracking/exceptions                     异常件清单（GET，真分页 + 筛选）/ 登记（POST）
    //   logistics-tracking/exceptions/capabilities         能力声明（未交付段 available=false + ownerModule）
    //   logistics-tracking/exceptions/overview             全量概览（不受筛选与分页影响）
    //   logistics-tracking/exceptions/rules                异常识别规则（GET 清单 / POST 登记）
    //   logistics-tracking/exceptions/scan                 按规则扫描建件（POST，幂等）
    //   logistics-tracking/exceptions/{uuid}              异常件详情（GET）
    //   logistics-tracking/exceptions/{uuid}/dispositions  该件的处置记录（GET 列表 / POST 追加）
    //   logistics-tracking/exceptions/{uuid}/claims       该件的单据（GET 列表 / POST 发起）
    //   logistics-tracking/dispositions                   全量处置记录（GET，真分页 + 筛选）
    //   logistics-tracking/dispositions/{uuid}            处置记录详情（GET）
    //   logistics-tracking/dispositions/claims             全量理赔 / 补发单（GET）
    //   logistics-tracking/dispositions/claims/{uuid}      单据详情（GET）
    //
    // ⚠️ **裸路径 `logistics-tracking` 仍不放行**：10.3.1.1 的那条元素已明确说明
    //    后端**没有**裸集合读，本模块也不新增一个（异常件清单在 `/exceptions`）。
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / overview / rules / scan）**排在 uuid 段之前**。
    //    写反的话 `s`(scan) / `r`(rules) / `c`(capabilities) / `o`(overview) 落在
    //    [0-9a-f] 里会被 uuid 分支提前吃掉 —— 而且这几个首字母都不是十六进制，
    //    真正危险的是 `claims` 与 `capabilities` 同首字母 `c` 时容易顺手写歪。
    //    `dispositions` 下同理：`claims`（字面量）排在 uuid 段之前。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    //    `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行，
    //    症状是有人 GET /api/channel/logistics-tracking/exceptions/c 会拿到一个
    //    「异常件 id 是 c」的请求打到后端，而不是被白名单当场 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-tracking/exceptions/{uuid}/rules`  —— 规则是**租户级**集合，不挂在某一件上
    //   * `logistics-tracking/exceptions/{uuid}/scan`   —— 扫描是集合级动作，不对单件做
    //   * `logistics-tracking/exceptions/{uuid}/events` —— 轨迹事件归 10.3.1.1，不在这里
    //   * `logistics-tracking/dispositions/{uuid}/claims` —— 单据挂在**异常件**上，不挂在处置记录上
    //   * `logistics-tracking/exceptions/{uuid}/downstream-tasks`
    //     —— 下游待通知任务在异常件详情里，没有第二个形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-tracking\/(?:exceptions(?:\/capabilities|\/overview|\/rules|\/scan|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/dispositions|\/claims)?)?|dispositions(?:\/claims(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 10.1.1.3 时效与限制（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。插入锚点与 10.1.1.2 同款：固定在 `prefix: 'trade/'`
    // 之前，纯增量，不重排既有条目与 import。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-transit-limits                       裸路径（保留：形态对齐其余模块）
    //   logistics-transit-limits/capabilities          能力声明（未交付段 available=false）
    //   logistics-transit-limits/overview              全量概览（不受筛选与分页影响）
    //   logistics-transit-limits/transit-rules         时效规则集合读 / POST 登记
    //   logistics-transit-limits/transit-rules/{uuid}  详情（GET）/ DELETE 撤下
    //   logistics-transit-limits/size-limits           尺寸 / 重量上限集合读 / POST
    //   logistics-transit-limits/size-limits/{uuid}    详情（GET）/ DELETE
    //   logistics-transit-limits/markings              标记要求集合读 / POST
    //   logistics-transit-limits/markings/{uuid}       详情（GET）/ DELETE
    //   logistics-transit-limits/precheck              🔴 限制命中提示（POST，只读无副作用）
    //   logistics-transit-limits/estimate              🔴 时效估算（POST，只读无副作用）
    //
    // 判别顺序：字面量子路径（capabilities / overview / transit-rules / size-limits /
    // markings / precheck / estimate）**排在 uuid 段之前**。写反的话 `e`(estimate) /
    // `c`(capabilities) 落在 [0-9a-f] 里会被 uuid 分支提前吃掉，拼出一个「id 是 e 的
    // 时效规则」被凭空放行。
    // ⚠️ uuid 尾巴**只挂在三个集合名后面**，不挂在 precheck / estimate / capabilities
    // 后面：那两个是判定端点，不存在 `/precheck/{uuid}` 这种形状；写成
    // `(?:literal|uuid)` 的单层 alternation 会把 `/precheck/{uuid}` 一起放行。
    // 同理**裸 uuid 也不放行**：`logistics-transit-limits/{uuid}` 不是任何端点。
    // id 段用**完整 UUID 形状**（照抄本文件里其余 20 处），不是 `[0-9a-f-]+`：
    // 后者把单字符 `e` 当合法 id，症状是有人 GET .../transit-rules/e 会打到后端。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-transit\-limits(?:\/(?:capabilities|overview|precheck|estimate|transit-rules|size-limits|markings|(?:transit-rules|size-limits|markings)\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 10.3.2.2 揽收与签收确认（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然
    // 判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 插入锚点固定在 10.1.1.3 `logistics-transit-limits` 那条**之后**、10.7.1.1
    // `return-dispositions` 那条**之前** —— 与另外三条车道（10.3.1.2 / 10.6.2.1 / 10.7.1.2）
    // 的锚点互不重叠，git 能干净求并。既有条目一律**一字未动、不重排、不格式化整文件**。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-pickup-signoff/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   logistics-pickup-signoff/overview            全量概览（不受筛选与分页影响）
    //   logistics-pickup-signoff/cases               确认单清单（GET，真分页 + 筛选）
    //   logistics-pickup-signoff/cases/sync          从交运批次同步确认单（POST，幂等投影）
    //   logistics-pickup-signoff/cases/{uuid}        确认单详情（GET，含时间线 / 提醒 / 待通知）
    //   logistics-pickup-signoff/cases/{uuid}/attest 人工确认揽收 / 签收（POST）
    //   logistics-pickup-signoff/reconcile           巡检（POST）：推进状态 + 判超时 + 生成提醒
    //   logistics-pickup-signoff/reminders           提醒任务清单（GET，真分页 + 筛选）
    //   logistics-pickup-signoff/reminders/{uuid}/acknowledge 认领提醒（POST）
    //   logistics-pickup-signoff/reminders/{uuid}/resolve    处置提醒（POST）
    //   logistics-pickup-signoff/notifications       下游待通知清单（GET，真分页 + 筛选）
    //   logistics-pickup-signoff/notifications/{uuid}/consume 登记下游消费结果（POST）
    //   logistics-pickup-signoff/events              确认事件流（GET，真分页 + 筛选）
    //   logistics-pickup-signoff/sla-rules           时限规则清单（GET）/ 登记或改（POST）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `logistics-pickup-signoff` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `logistics-pickup-signoff/cases/{uuid}/events` —— 确认时间线就在详情里，没有第二个形状
    //   * `logistics-pickup-signoff/cases/{uuid}/reminders` —— 提醒按确认单聚合在详情里
    //   * `logistics-pickup-signoff/sla-rules/{uuid}` —— 规则按承运商 + 渠道定位，不按 id
    //   * `logistics-pickup-signoff/notifications/{uuid}/retry`
    //     —— 下游消费由下游自己重试，本模块只登记结果
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处），不是 `[0-9a-f-]+`：
    //    后者把单字符 `e` 当合法 id，症状是有人 GET .../cases/e 会打到后端。
    // ⚠️ 字面量子路径（capabilities / overview / cases / reconcile / reminders /
    //    notifications / events / sla-rules）**排在 uuid 段之前**判别：写反的话
    //    `cases` 里的 `c`、`events` 里的 `e` 落在 [0-9a-f] 里会被 uuid 分支提前吃掉。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-pickup\-signoff(?:\/(?:capabilities|overview|cases|reconcile|reminders|notifications|events|sla-rules)|\/cases\/(?:sync|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(?:\/attest)?|\/reminders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:acknowledge|resolve)|\/notifications\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/consume)$/,
  },
  {
    // 17.5.2.1 商品生命周期规则（catalog 服务，卷 03 商品主数据）。
    //
    // 纯**新增一条独立的 `services[]` 元素**，既有的 catalog 那条巨型 alternation
    // （`products` / `attributes` / `brands` / `parties` / `categories` / `category-mappings` /
    // `records` / `media` / `skus` / `unit-groups` / `spec-axis` …）**一字未动、也不重排** ——
    // 往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突（结构性，
    // retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `// 10.7.1.1 退货接收与处置决策` 那条**之前** ----
    //   同批其它车道的锚点与本处相距 200 行以上，git 能干净求并。
    //
    // ---- 首 token「lifecycle」的零命中核对结论 ----
    //   `grep -c '\^lifecycle' apps/web/src/lib/api-proxy.ts` = **0**：基线全文 173 条既有正则里
    //   **没有任何一条以 `lifecycle` 开头**。文件里 21 处 `lifecycle` 逐个核过：
    //     * `attributes/options/lifecycle`（3.2.1.2 的批量重排）—— 是**第二段**，首 token 是
    //       `attributes`，本条的首 token 与它互不为前缀；
    //     * `listing-lifecycles`（4.1.1.3）—— 首 token 是 `listing-lifecycles`，
    //       与 `lifecycle` 在第 3 个字符就分叉（`lif` vs `lis`），互不为前缀；
    //     * `listing-details/{uuid}/lifecycle` —— 该条目**刻意不放行**（七态状态机归 4.1.1.3），
    //       与本条不同前缀、不同控制器，无关。
    //   ⇒ 本条的首 token `lifecycle` 是独立字面量，不会被既有分支吃掉，也不会吃掉它们。
    //
    // ---- 逐条核对过的后端端点清单 ----
    //   backend/services/catalog/src/main/java/com/starcon/catalog/LifecycleController.java
    //   （类级 `@RequestMapping("/catalog")`）逐条抄下来：
    //     GET    /products/{id}/lifecycle       单个 SPU 的生命周期视图（`@PathVariable UUID id`）
    //     POST   /products/{id}/lifecycle       推进该 SPU 的生命周期
    //     GET    /lifecycle/metrics             生命周期指标字典
    //     GET    /lifecycle/rules               生命周期规则列表
    //     POST   /lifecycle/rules               新建生命周期规则
    //     PUT    /lifecycle/rules/{id}          改规则
    //     DELETE /lifecycle/rules/{id}          删规则（带 version 乐观锁）
    //     GET    /lifecycle/rules/{id}/preview   规则试跑预览
    //     POST   /lifecycle/rules/{id}/run      执行规则
    //   以上 **9 条全部**在后端存在，本条**逐条放行**。
    //   （`/products/{id}/lifecycle` 这一组前端页面在用 —— `spu-lifecycle.tsx` 的
    //   `catalog/products/${spuId}/lifecycle` GET / POST —— 同样在本次漏登记之列。）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `lifecycle/rules/{uuid}/delete`    —— 删除就是 `DELETE /lifecycle/rules/{id}` 本体，
    //                                            没有单独的 `/delete` 子动作。
    //   * `lifecycle/rules/{uuid}/executions`—— 执行结果随 `POST /lifecycle/rules/{id}/run`
    //                                            的返回值给出，没有独立的执行记录集合。
    //   * `lifecycle/rules/{uuid}/preview/{x}`—— 预览是一段带 limit 的查询串，不是子资源。
    //   * `lifecycle/rules/{uuid}/history`    —— 规则没有独立的变更历史端点。
    //   * `lifecycle/metrics/{uuid}`          —— 指标是字典，不是集合资源。
    //   * `lifecycle/rules/not-a-uuid`        —— 非 UUID 段不应被 uuid 分支吃掉。
    //   * `lifecycle/transitions`             —— 生命周期状态机的枚举不在本控制器的端点里。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `preview` / `run` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ 字面量子路径（`metrics` / `rules`）排在 `{uuid}` 段之前判别 —— alternation 是首个
    //   命中即返回，顺序反了会被通配支吃掉半截。
    //
    // ---- `catalog/records/{kind}`（3.1.x 商品档案）**刻意不在本条重复登记** ----
    //   它**已经**由既有 3.1.x 那条 catalog 元素登记（既有巨型 alternation 里的
    //   `records/(research|compliance|assets)` 分支）。既有条目在数组里**更靠前**，
    //   resolve() 首个命中即返回 ⇒ 本条再写一遍只会是**永远走不到的死分支**。
    //   实测（resolve 脚本）：`catalog/records/research` / `catalog/records/compliance` /
    //   `catalog/records/assets` / `catalog/records/assets/{uuid}` 既有元素**已放行**；
    //   `catalog/records`（裸路径）/ `catalog/records/products`（不存在的 kind）/
    //   `catalog/records/assets/{uuid}/x` 既有元素**已拒**。
    //   后端 `CatalogService.table(kind)` 的取值域就是 research / compliance / assets 三个
    //   （`default -> throw missing()`），与既有条目的 alternation **完全一致**，无需放宽。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^(?:lifecycle\/(?:metrics|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:preview|run))?)?)|products\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/lifecycle)$/,
  },
  {
    // 10.7.1.1 退货接收与处置决策（**本条目是新增一条 services[] 元素**，不是往既有
    // channel 分支的 alternation 里并）。插入锚点固定在本文件里
    // `logistics-transit-limits/`（10.1.1.3）那条**之后**、`freight/`（10.1.2.1）
    // 那条**之前** —— 两条既有条目把本车道的插入点夹在中间，与其他车道两两被隔开，
    // git 能干净求并。既有条目一律**一字未动、不重排、不格式化整文件**。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   return-dispositions/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   return-dispositions/overview                全量概览（不受筛选与分页影响）
    //   return-dispositions/registrations           接收登记集合读（GET，真分页 + 筛选）/ 登记（POST）
    //   return-dispositions/registrations/{uuid}    接收登记详情（GET，含决策与留痕）
    //   return-dispositions/restrictions            再销售限制集合读（GET）/ 登记（POST）
    //   return-dispositions/restrictions/{uuid}/revoke 撤销再销售限制（POST）
    //   return-dispositions/eligibility             🔴 可行性判定（GET，只读无副作用）
    //   return-dispositions/decisions               处置决策集合读（GET）/ 记录决策（POST）
    //   return-dispositions/decisions/{uuid}        处置决策详情（GET）
    //   return-dispositions/orders                  处置单集合读（GET）/ 下发处置单（POST）
    //   return-dispositions/orders/{uuid}           处置单详情（GET，含成本明细与库存指令）
    //   return-dispositions/orders/{uuid}/costs     追加处置成本（POST）
    //   return-dispositions/orders/{uuid}/cancel    撤销处置单（POST）
    //   return-dispositions/events                  留痕集合读（GET，真分页 + 筛选）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / overview / registrations /
    // restrictions / eligibility / decisions / orders / events）排在 uuid 分支之前。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    // `[0-9a-f-]+`：后者把单字符 `e` 当合法 id，症状是有人 GET .../orders/e 会打到后端。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `return-dispositions` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `return-dispositions/decisions/{uuid}/consume` —— 决策只能被下发处置单消费，
    //     没有独立消费端点
    //   * `return-dispositions/orders/{uuid}/instructions` —— 库存状态变更指令是**只出
    //     不消费**的事实，消费方 7.4.1.1 调整单管理未交付，放行它只会让人以为本页
    //     能确认指令已被 7 库存执行
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^return\-dispositions(?:\/(?:capabilities|overview|registrations|restrictions|eligibility|decisions|orders|events)|\/registrations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/restrictions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/revoke|\/decisions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/costs|\/cancel)?)$/,
  },
  {
    // 10.6.2.1 时效分析（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。同批多车道共享这一个文件，而每个服务在 services[] 里
    // 是**一段超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然
    // 判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路。
    //
    // 插入锚点固定在 10.7.1.1 `return-dispositions` 那条**之后**、10.1.2.1 `freight/`
    // 那条**之前** —— 与另外三条车道的锚点两两被既有条目隔开，git 能干净求并。
    // 既有条目一律**一字未动、不重排、不格式化整文件**。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-timeliness/capabilities            能力声明（未交付段 available=false）
    //   logistics-timeliness/overview                全量概览（不受筛选与分页影响）
    //   logistics-timeliness/reports/matrix          🔴 渠道 × 国家交叉报表（GET，真分页 + 筛选）
    //   logistics-timeliness/reports/monthly         月度趋势 / 旺季变化（GET）
    //   logistics-timeliness/reports/anomalies       异常集中分析（GET，帕累托）
    //   logistics-timeliness/reports/scores          渠道时效评分（GET，实时计算）
    //   logistics-timeliness/reports/score-snapshots  评分台账（GET，真分页）/ 重算落库（POST）
    //   logistics-timeliness/subjects                逐票时效明细（GET，真分页 + 筛选）
    //
    // ⚠️ **裸路径 `logistics-timeliness` 不放行**：后端这一组端点**没有**裸集合读
    // （本模块是报表模块，一切入口都带子路径），契约里也不存在
    // `/channel/logistics-timeliness` 这一条。多放一段会让一个**还不存在**的路径
    // 看起来像已交付 —— 界面点下去得到 404，而后端其实从来就没实现它。
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / overview / reports / subjects）
    // **排在各自的子路径之前**。
    //
    // ⚠️ **本条目一段 uuid 都没有**：本模块的端点全是「集合读 + 明细读」，
    // 没有一条是按 uuid 定位单个资源（明细是「窗口 + 筛选 + 分页」，
    // 不是 `logistics-timeliness/subjects/{uuid}`）。所以这里**没有** uuid 段，
    // 也就没有「单字符 e 被当 uuid 放行」那类坑 —— 少一段正则胜过写一段防错。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-timeliness/subjects/{uuid}` —— 明细按窗口 + 筛选 + 分页取，没有单票形状
    //   * `logistics-timeliness/reports/{anything}` —— reports 下只有上面列出的五个子路径
    //   * `logistics-timeliness/snapshots` —— 评分台账的路径是 reports/score-snapshots
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-timeliness\/(?:capabilities|overview|subjects|reports\/(?:matrix|monthly|anomalies|scores|score\-snapshots))$/,
  },
  {
    // 10.1.2.1 运费计算器（**本条目是新增一条 services[] 元素**，与 10.1.1.2 渠道配置
    // 那条并列，**不改也不重排**它）。同批多车道共享这一个文件，而每个服务在
    // services[] 里是**一段超长单行正则**：两条车道都往同一服务的 alternation 里并分支，
    // git 必然判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」，
    // 插入锚点固定在 `prefix: 'trade/'` **之前** —— 那样本车道与其他车道的插入点被
    // 既有条目隔开，git 能干净合并。
    //
    // 并入集成头时本条目排在 10.1.1.3 的 `logistics-transit-limits` 那条**之后**：
    // 两条用的是同一个锚点，形状也一样（各自一条独立的 `channel/` 元素），
    // 所以解法是**保留两条、彼此并列**，既不并成 alternation 也不重排。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   freight/capabilities            能力声明（未交付段 available=false）
    //   freight/estimate               🔴 运费试算（POST，无副作用，不落库）
    //   freight/quotes                 试算并落库（POST）/ 试算单集合读（GET，真分页 + 筛选）
    //   freight/quotes/{uuid}          试算单详情（GET，含逐渠道计算轨迹）
    //   freight/rate-cards             费率卡与分国家规则（GET）/ 登记改费率卡（POST）
    //   freight/country-rules          登记改分国家规则（POST）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / estimate / quotes /
    // rate-cards / country-rules）排在 [0-9a-f-]+ 分支之前，否则会被 uuid 分支提前吃掉。
    // 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    // 只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `freight/quotes/{uuid}/settlement` —— 结算用实际成本，口径不在本模块
    //     （试算用规则、结算用实际，不能拿试算当实际成本）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 10.1.2.1 运费计算器的 `quotes/{uuid}` **在改本车道之前就没被放行**：
    // 原正则里 `quotes` 后面没有再接一段 uuid，所以「试算单详情」这一页在浏览器里
    // 静默 404（后端端点存在、契约里有、typecheck / lint / vitest 全绿）。本车道顺手补上，
    // 因为比价单详情页与它是同一类形状，留着这个洞会让它继续骗人。
    pattern:
      /^freight(?:\/(?:capabilities|estimate|quotes|rate\-cards|country\-rules|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})|\/quotes\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
    // 裸路径 freight **不放行**：本模块没有裸集合读，运费计算器的一切入口都带子路径。
  },
  {
    // 10.1.2.2 渠道比价（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `freight/`（10.1.2.1）、`logistics-transit-limits/`（10.1.1.3）、
    // `logistics-channels/`（10.1.1.2）三条**一字未动、也不重排** —— 同批多车道共享
    // 这一个文件，每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让
    // git 必然判冲突且 retry 永远不会自愈。插入锚点固定在本文件里 `freight/` 那条
    // **之后**、`label-templates` 那条**之前**，彼此的插入点被既有条目隔开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   rate-comparison/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   rate-comparison/compare             🔴 比价（POST，无副作用，不落库）
    //   rate-comparison/comparisons         比价并落库（POST）/ 比价单集合读（GET，真分页 + 筛选）
    //   rate-comparison/comparisons/{uuid}  比价单详情（GET，含逐渠道评分明细与算式）
    //   rate-comparison/ratings             渠道评分集合读（GET）/ 登记改评分（POST）
    //   rate-comparison/shop-priorities     店铺预设优先级集合读（GET）/ 登记改（POST）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / compare / comparisons /
    // ratings / shop-priorities）排在 uuid 分支之前，否则会被 uuid 分支提前吃掉。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `rate-comparison` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `rate-comparison/ratings/{uuid}` —— 评分不是按 id 单独取的（列表带筛选即可）
    //   * `rate-comparison/shop-priorities/{uuid}` —— 同上
    //   * `rate-comparison/settlement` —— 结算用实际成本，口径不在本模块
    //     （比价成本来自 10.1.2.1 的试算，试算用规则、结算用实际）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `rate-comparison` —— 本模块没有裸集合读，
    // 放行它会让界面以为「有一条列表接口」，而它转发过去只会拿到 503 / 404。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^rate\-comparison(?:\/(?:capabilities|compare|comparisons|ratings|shop\-priorities)|\/comparisons\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 10.7.1.2 退运回国（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `rate-comparison/`（10.1.2.2）、`label-templates`（10.2.2.2）、`freight/`
    // （10.1.2.1）、`logistics-transit-limits/`（10.1.1.3）、`logistics-channels/`
    // （10.1.1.2）**一字未动、也不重排** —— 同批多车道共享这一个文件，每条服务规则是一段
    // 超长单行正则，往同一条 alternation 里并分支会让 git 必然判冲突且 retry 永远不会
    // 自愈。插入锚点固定在本文件里 `rate-comparison/`（10.1.2.2）那条**之后**、
    // `14.1.2.2 广告类型差异` 那条**之前** —— 四个车道的插入点两两被既有条目隔开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   return-shipbacks/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   return-shipbacks/overview                全量概览（不受筛选与分页影响）
    //   return-shipbacks/candidates              待退运清单（只取 10.7.1.1 决策为「退运回国」的处置单）
    //   return-shipbacks/consignments            退运单集合读（GET，真分页 + 筛选）/ 建退运单（POST，**合单**）
    //   return-shipbacks/consignments/{uuid}     退运单详情（GET，含明细行 / 报关 / 成本 / 分摊 / 收货 / 分录）
    //   return-shipbacks/consignments/{uuid}/lines     往既有退运单加一行（继续凑单，POST）
    //   return-shipbacks/consignments/{uuid}/customs   登记报关资料（POST）
    //   return-shipbacks/consignments/{uuid}/costs     登记成本并**立刻分摊**（POST）
    //   return-shipbacks/consignments/{uuid}/allocations 成本分摊明细（GET，逐行权重 / 比例 / 尾差）
    //   return-shipbacks/consignments/{uuid}/confirm   确认退运单（POST）
    //   return-shipbacks/consignments/{uuid}/dispatch  发出退运单（POST）
    //   return-shipbacks/consignments/{uuid}/arrival   登记回国收货（POST）
    //   return-shipbacks/consignments/{uuid}/entries   登记重新入库 / 报废分录（POST）
    //   return-shipbacks/consignments/{uuid}/close     关闭退运单（POST）
    //   return-shipbacks/consignments/{uuid}/cancel    取消退运单（POST）
    //   return-shipbacks/events                 留痕集合读（GET，真分页 + 筛选）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / overview / candidates /
    // consignments / events）排在 uuid 分支之前，否则会被 uuid 分支提前吃掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    // `[0-9a-f-]+`：后者把单字符 `e` 当合法 id，症状是有人 GET .../consignments/e 会打到后端。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `return-shipbacks` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `return-shipbacks/consignments/{uuid}/repatriation` —— 退运单本身就是退运，
    //     没有「再退运一次」的子资源
    //   * `return-shipbacks/consignments/{uuid}/costs/{uuid}` —— 成本没有独立删除端点
    //     （成本只追加、错单靠退运单取消来纠正）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^return\-shipbacks(?:\/(?:capabilities|overview|candidates|consignments|events)|\/consignments\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:lines|customs|costs|allocations|confirm|dispatch|arrival|entries|close|cancel))?)$/,
  },
  {
    // 14.1.2.2 广告类型差异（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `rate-comparison/`（10.1.2.2）、`label-templates`（10.2.2.2）、`freight/`
    // （10.1.2.1）、`logistics-transit-limits/`（10.1.1.3）、`logistics-channels/`
    // （10.1.1.2）**一字未动、也不重排** —— 同批多车道共享这一个文件，每条服务规则是一段
    // 超长单行正则，往同一条 alternation 里并分支会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在本文件里 `rate-comparison/` 那条**之后**、`label-templates` 那条**之前**，
    // 彼此的插入点被既有条目隔开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   ad-type-diffs/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   ad-type-diffs/resolve                   🔴 走 2.1.2.1 两层解析归一口径（GET，无副作用）
    //   ad-type-diffs/profiles                  档案集合读（GET，真分页 + 筛选）/ 登记档案（POST）
    //   ad-type-diffs/profiles/{uuid}           档案详情（GET，含特有字段定义与未定义原始键）
    //   ad-type-diffs/profiles/{uuid}/field-specs  特有字段定义集合读（GET）/ 登记（POST）
    //   ad-type-diffs/payloads                  归一后广告集合读（GET）/ 登记（POST）
    //   ad-type-diffs/payloads/{uuid}           详情（GET，含原始报文原文与特有字段补充展示）
    //   ad-type-diffs/comparable                归一后的广告类型视图（GET）
    //   ad-type-diffs/fidelity                  保真概览（GET）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / resolve / profiles / payloads /
    // comparable / fidelity）排在 uuid 分支之前，否则会被 uuid 分支提前吃掉。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-type-diffs` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-type-diffs/payloads/{uuid}/raw` —— 原文随详情一起返回，不单独开一条
    //   * `ad-type-diffs/profiles/{uuid}/payloads` —— 广告按自己的集合读筛选，不按档案下钻
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-type-diffs` —— 本模块没有裸集合读，
    // 放行它会让界面以为「有一条列表接口」，而它转发过去只会拿到 503 / 404。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-type\-diffs(?:\/(?:capabilities|resolve|profiles|payloads|comparable|fidelity)|\/profiles\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/field\-specs)?|\/payloads\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 10.2.2.2 面单模板（channel 服务）。纯**增量**追加：既有条目一字未动，
    // 插入锚点固定在 `logistics-channels` 那组**之后**、`prefix: 'trade/'` **之前** ——
    // 那样本车道与其它车道的插入点被既有条目隔开，git 能干净合并。
    //
    // 并入集成头时本条目排在 10.1.1.3 的 `logistics-transit-limits` 那条**之后**：
    // 两条用的是同一个锚点，形状也一样（各自一条独立的 `channel/` 元素），
    // 所以解法是**保留两条、彼此并列**，既不并成 alternation 也不重排。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   label-templates                    集合读（GET，真分页 + 筛选）/ POST 登记模板
    //   label-templates/capabilities       能力声明（未交付段 available=false）
    //   label-templates/overview           全量概览（不受筛选与分页影响）
    //   label-templates/binding            🔴 渠道规则强绑定判定（POST，只读无副作用）
    //   label-templates/{uuid}             详情（GET）/ DELETE 撤下模板
    //   label-templates/{uuid}/status      启用 / 停用（POST）
    //   label-templates/{uuid}/preview     🔴 真渲染预览（POST，出 ZPL + 真条码）
    //   label-templates/{uuid}/renders     渲染历史（GET，真分页）
    //
    // ⚠️ 字面量子路径（capabilities / overview / binding）**排在** [0-9a-f-] 分支之前：
    //    写反的话 `binding` 的首字母 b 落在 [0-9a-f] 里，会被 uuid 分支提前吃掉，
    //    正则静默匹配失败，或更糟：拼出一个「id=binding 的模板」被凭空放行。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `label-templates/{uuid}/print` —— 打印下发是 8.3.4.2 的主权，本模块不建打印引擎
    //   * `label-templates/{uuid}/deploy` —— 同上，本模块只到模板与渲染产物
    //   * `label-templates/resolve` —— 运单 ↔ 模板的自动选版未做（见能力声明 ownerModule）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^label\-templates(?:\/(?:capabilities|overview|binding|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|preview|renders))?))?$/,
  },
  {
    // 10.6.1.1 成本分析（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 10.2.2.2 `label-templates` 那条**之后**、2.5.2.1
    // `writeback-batches` 那条**之前**：与本批另外三条车道的插入点被既有条目隔开，
    // git 能干净求并。既有条目一律**一字未动、不重排、不格式化整文件**。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-cost-analysis/capabilities        能力声明（五个分量的来源 / 可得性）
    //   logistics-cost-analysis/overview            成本报表（逐币种合计 + 覆盖说明）
    //   logistics-cost-analysis/by-channel          各渠道成本对比
    //   logistics-cost-analysis/over-cost-orders    超成本订单清单（真分页）
    //   logistics-cost-analysis/allocation-rules    分摊规则版本列表 / POST 新建版本
    //   logistics-cost-analysis/allocations         分摊单列表 / POST 发起分摊
    //   logistics-cost-analysis/allocations/{uuid}  分摊单详情（头 + 逐行明细）
    //   logistics-cost-analysis/allocations/{uuid}/lock  结账（锁定后不可再改）
    //   logistics-cost-analysis/parameters          成本参数列表 / POST 维护 / DELETE {uuid}
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / overview / by-channel /
    //    over-cost-orders / allocation-rules / allocations / parameters）排在 uuid 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `p`（parameters 的首字母）当 id 吃掉，`.../parameters/p` 会被凭空放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    // ⚠️ **裸路径不放行**：后端没有 `GET /channel/logistics-cost-analysis` 这个映射，
    //    放行它只会把「这里没有这个接口」从代理层的 404 推迟到后端的 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-cost-analysis/allocations/{uuid}/lines` —— 逐行明细随详情一起返回
    //   * `logistics-cost-analysis/allocations/{uuid}/supersede` —— 取代是重算分摊的自然结果，
    //     不另开一个动作端点（那会让「谁取代了谁」有两个入口）
    //   * `logistics-cost-analysis/report` —— 报表就是 overview，不开别名
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-cost\-analysis\/(?:capabilities|overview|by\-channel|over\-cost\-orders|allocation\-rules|parameters(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|allocations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/lock)?)?)$/,
  },
  {
    // 2.5.2.1 常用回写操作（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `label-templates`（10.2.2.2）、`ad-type-diffs`（14.1.2.2）、`rate-comparison`
    // （10.1.2.2）、`logistics-channels`（10.1.1.2）等**一字未动、也不重排** —— 本文件是
    // 多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里并分支
    // 会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在 `label-templates` 那条**之后**、`prefix: 'trade/'` **之前**。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   writeback-targets                    候选池集合读（GET，真分页 + 筛选）/ POST 登记目标
    //   writeback-targets/{uuid}/status      封禁 / 解封目标（POST）
    //   writeback-batches                    批次集合读（GET，真分页 + 结局筛选）/ POST 执行批量
    //   writeback-batches/preview            🔴 执行前试算（POST，只读无副作用，不落任何一行）
    //   writeback-batches/{uuid}             批次详情（GET，逐条结果）
    //
    // 判别顺序：字面量子路径 `preview` 排在 uuid 分支之前（与既有两组同款次序）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    // 后者会把 `p`（preview 的首字母）当 id 吃掉，`writeback-batches/p` 会被凭空放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `writeback-batches/{uuid}/items` —— 逐条结果随详情一起返回，不单独开一条
    //   * `writeback-targets/{uuid}` —— 目标没有独立详情端点，候选池集合读已带全部字段
    //   * `writeback-batches/{uuid}/retry` —— 批量不等于事务，整批重发是有资损风险的动作，
    //     重试按 2.5.1.1 的任务清单逐条判断（2.5.1.2 未知结果核验），本模块刻意不提供整批重发入口
    //   * `writeback-targets/sync` —— 本模块不经任何跨服务同步：上游（04/07/09/10）推事件或
    //     运营在工作区登记，没有需要本服务投影的对象
    // ⚠️ 末尾**刻意不带 `?`**：两个裸路径（writeback-targets / writeback-batches）都是真实的
    // 集合读写入口，必须放行。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^writeback\-(?:targets(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?|batches(?:\/preview|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 4.1.1.1 Listing 列表（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在本文件里**最后一条 `channel/` 元素**（10.2.2.2 `label-templates`）
    // **之后**、`prefix: 'trade/'` **之前**：与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listings                    分页列表（GET，七个筛选维度 + 真分页）/ 登记平台商品（POST）
    //   listings/capabilities       能力声明（价格 / 销量 / 内部可售量 available=false + ownerModule）
    //   listings/filter-options     筛选面板词表（不硬编码在前端）
    //   listings/{uuid}             单条读取（列表行点进去的最小载荷；全景详情归 4.1.1.2）
    //   listings/batch              批量操作（POST：分配负责人 / 下架；改价改库存显式拒绝并留痕）
    //   listings/batch-tasks        批量操作任务列表（GET，被拒绝的改价同样可回溯）
    //
    // ⚠️ 判别顺序：`batch-tasks` 必须排在 `batch` **之前**。写反的话 `batch` 先吃掉前缀，
    //    `batch-tasks` 永远匹配不到（症状是「批量任务列表 404」，很容易被误判成正则写错）。
    // ⚠️ 那个 `\/` 必须写在**字面量子路径与 uuid 段共用的那一层**上
    //    （`(?:\/(?:batch|capabilities|…|[0-9a-f]{8}…))`）。写成
    //    `(?:\/batch|[0-9a-f]{8}…)` 会让斜杠只属于第一个分支 —— 字面量路径照样放行，
    //    而 `listings/{id}` 静默 404，症状是「点进详情就 404，但列表和批量都正常」。
    // ⚠️ 裸路径 `listings` **放行**：本模块的列表就是 `GET /channel/listings?...`，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search），
    //    不放行裸路径等于列表页在浏览器里直接 404。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listings/{uuid}/mapping`    —— 映射的建立 / 版本化 / 批量匹配归 4.1.2.1 / 4.1.2.2
    //   * `listings/{uuid}/lifecycle`  —— 七态状态机与平台回写归 4.1.1.3
    //   * `listings/{uuid}/diff`       —— 差异对照是 4.1.1.2 的核心价值，不在本模块
    //   * `listings/{uuid}/publish`    —— 发布流程归 4.3.1，本模块只登记不上线
    //   * `listings/{uuid}/history`    —— 状态历史随列表行返回，不单独开一条
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listings(?:\/(?:batch\-tasks|batch|capabilities|filter\-options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 4.1.1.3 生命周期与下架（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.1.1.1 `listings` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listing-claims` / `listing-status-events` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-lifecycles                    生命周期列表（GET，真分页 + 七态/来源/店铺/平台/关键词筛选）
    //   listing-lifecycles/capabilities       能力声明（未交付段 available=false + ownerModule）
    //   listing-lifecycles/filter-options     筛选面板词表（不硬编码在前端）
    //   listing-lifecycles/diff               🔴 平台下架 vs 内部下架的差异区（真分页）
    //   listing-lifecycles/sync               🔴 批量同步：把还没联动过的平台事件应用到内部状态（POST）
    //   listing-lifecycles/{uuid}             单个 listing 的生命周期视图（GET）
    //   listing-lifecycles/{uuid}/timeline    🔴 状态变更时间线（GET，真分页 + 按来源筛）
    //   listing-lifecycles/{uuid}/actions     🔴 人工动作：主动下架 / 重新上架 / 提交审核 / 标记封禁（POST）
    //   listing-lifecycles/{uuid}/platform-status 🔴 平台状态被动同步（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / diff / sync）必须排在 uuid 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `d`（diff 的首字母）当 id 吃掉，`listing-lifecycles/d` 会被凭空放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-lifecycles/{uuid}/publish`  —— 发布流程归 4.3.1，本模块只管状态
    //   * `listing-lifecycles/{uuid}/batch`     —— 批量生命周期动作归 4.1.1.1（见能力声明 ownerModule）
    //   * `listing-lifecycles/{uuid}/appeal`    —— 被封禁的申诉工单归 4.3.2.2 / 16.2.1.2，未交付
    //   * `listing-lifecycles/{uuid}/republish` —— 重新上架的平台回写归 4.3.1.2 / 4.3.2.2，未交付
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-lifecycles(?:\/(?:capabilities|filter\-options|diff|sync)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:timeline|actions|platform\-status))?)?$/,
  },
  {
    // 4.1.2.1 映射管理（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.1 的 `listings` 元素**之后**、`prefix: 'trade/'` **之前**：
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-mappings` 这个独立前缀而不是 `listings/{id}/mapping`：
    // 4.1.1.1 交付时把 `/channel/listings/{uuid}/mapping` 显式登记成「不放行」
    // （那是本模块的形状），在同一棵路径树上开子资源要同时改它的白名单与它的拒绝清单，
    // 而那份清单是别人的成果。映射本身也是独立资源：它挂在**平台变体**上，
    // 不挂在 listing 上（一个 listing 有多个变体，映射是逐变体成立的）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-mappings                       列表（GET 真分页 + 筛选）/ 建立映射（POST）
    //   listing-mappings/capabilities          能力声明（未交付段 available=false + ownerModule）
    //   listing-mappings/filter-options        筛选词表（不硬编码在前端）
    //   listing-mappings/{uuid}                详情（当前映射 + 完整版本历史）/ 解除映射（DELETE）
    //   listing-mappings/batch-candidates      批量候选（POST，只写候选表）
    //   listing-mappings/candidates            候选列表（GET 真分页）
    //   listing-mappings/candidates/{uuid}/decide 候选裁决（POST 确认 / 否决）
    //   listing-mappings/unmapped              未映射清单（GET 真分页）
    //   listing-mappings/resolve               订单行解析入口（POST）
    //   listing-mappings/by-sku/{uuid}         多对一反查（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / batch-candidates /
    //    candidates / unmapped / resolve / by-sku）必须排在 `{uuid}` **之前**，
    //    否则会被 uuid 分支吃掉半截（症状是「能力声明 404，但列表页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-mappings/{uuid}/versions`   —— 版本历史随详情一起返回，不单独开一条
    //   * `listing-mappings/{uuid}/affected-orders` —— 「按版本回溯受影响订单」归 4.1.2.2，未交付
    //   * `listing-mappings/{uuid}/components` —— 套装组件明细归 4.1.2.3，未交付
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-mappings(?:\/(?:capabilities|filter\-options|batch\-candidates|candidates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/decide)?|unmapped|resolve|by\-sku\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 2.6.2.1 增量补拉（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `listings`（4.1.1.1）、`writeback-batches`（2.5.2.1）、`label-templates`
    // （10.2.2.2）、`ad-type-diffs`（14.1.2.2）等**一字未动、也不重排** —— 本文件是
    // 多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在 `listings` 那条**之后**、`prefix: 'trade/'` **之前**。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   backfills                 集合读（GET，按连接/状态筛选）/ POST 发起补拉
    //   backfills/capabilities    能力声明（下游同步处理器 available=false + ownerModule）
    //   backfills/summary         补拉状态分布
    //   backfills/gaps            漏推缺口检测（按 2.6.1.1 接收记录的静默区间）
    //   backfills/{uuid}          任务详情
    //   backfills/{uuid}/records  处理记录集合读（GET）/ 提交一批补拉结果（POST，逐条判定幂等）
    //   backfills/{uuid}/segments 分段轮询留痕（证明「只给最近 N 条」时分段轮询真的发生了）
    //   backfills/{uuid}/complete 收尾：结算四个数字 + 推进游标
    //   backfills/{uuid}/cancel   人工中止
    //
    // 判别顺序：字面量子路径（capabilities / summary / gaps）必须排在 uuid 段**之前**。
    // 写反的话 `c`（capabilities）与 `s`（summary）落在 [0-9a-f] 里，会被 uuid 段提前吃掉 ——
    // 正则静默匹配失败，或者更糟：拼出一个「id=capabilities 的补拉任务」被凭空放行。
    // ⚠️ 那个 `\/` 必须写在**字面量子路径与 uuid 段共用的那一层**上，与 listings 同款。
    // ⚠️ 裸路径 `backfills` **放行**：集合读与发起补拉都是它的入口，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `backfills/{uuid}/records/{recordId}` —— 逐条结果随详情返回，不单开一条
    //   * `backfills/{uuid}/retry` —— 补拉重试就是「再发起一次」：幂等键相同会拿回同一任务，
    //     另开一条 retry 只会让人以为重试会绕过幂等
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^backfills(?:\/(?:capabilities|summary|gaps|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:records|segments|complete|cancel))?))?$/,
  },
  {
    // 4.2.1.2 批量编辑（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支（理由同上面 4.1.1.1 那条：
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突）。
    // 插入锚点固定在 4.1.1.1 `listings` 条目**之后**、`prefix: 'trade/'` **之前**：
    // 与兄弟模块相邻但仍是独立元素，各车道插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-bulk-edit/capabilities             能力声明（草稿隔离 + 卖点/图片线上基线未接入 + 发布归 4.3.1.2）
    //   listing-bulk-edit/templates                平台差异化模板列表（GET）+ 新建/更新（POST）
    //   listing-bulk-edit/preview                 操作预览：逐条真实 before/after（POST，落 PREVIEWED）
    //   listing-bulk-edit/confirm                 预览确认（POST，凭 operationId 推进，只写草稿）
    //   listing-bulk-edit/regex-check             正则 ReDoS 预检（POST）
    //   listing-bulk-edit/operations              修改记录列表（GET）+ 单次详情 operations/{uuid}（GET）
    //   listing-bulk-edit/drafts                  待发布草稿清单（GET）+ 单条 drafts/{uuid}（GET）
    //   listing-bulk-edit/drafts/{uuid}/discard   废弃草稿（POST，保留留痕不删除）
    //   listing-bulk-edit/scope-check             目标集合可见性预检（GET，跨租户/不存在的 id）
    //
    // ⚠️ **刻意不放行裸路径 `listing-bulk-edit`**：本模块没有裸路径资源，
    //    所以最外层分组**不带 `?`** —— 写了 `?` 就会连裸路径一起放行。
    //    放行一个后端不存在的映射比 404 更糟：界面会以为功能已交付。
    // ⚠️ **刻意没有 publish / apply 形状**：蓝图加粗句「批量编辑只改草稿不直接改线上」。
    //    批量编辑**没有任何**改线上的端点，放行 `…/publish` 等于在代理层凭空造一个
    //    绕过草稿的入口 —— 那正是「误改全店标题」事故要走的那条路。
    // ⚠️ 字面量子路径（capabilities/templates/preview/confirm/regex-check/scope-check/
    //    operations/drafts）各自成一支，不与 uuid 段混在一个 alternation 里：
    //    uuid 段的 `\/` 必须**与字面量路径的 `\/` 写在同一层**，否则 `drafts/{uuid}` 会静默 404。
    // ⚠️ `drafts/{uuid}/discard` 的 `discard` 挂在 **uuid 段之后**：
    //    写成 `/drafts/discard` 会放行一个不存在的「按 listing 废弃全部」的形状。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-bulk\-edit(?:\/(?:capabilities|templates|preview|confirm|regex\-check|scope\-check)|\/operations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/discard)?)?)$/,
  },
  {
    // 4.1.1.2 Listing 详情（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.1 的 `listings` 元素**之后**、`prefix: 'trade/'` **之前**：
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-details` 这个独立前缀而不是 `listings/{id}/...`：
    // 4.1.1.1 交付时把 `/channel/listings/{id}/diff` 显式登记成「不放行」（那是本模块的形状），
    // 也把 `/channel/listings/{id}` 定义成「列表行点进去的最小载荷」。在同一棵路径树上开子资源，
    // 要同时改它的白名单与它的拒绝清单 —— 而那份清单是别人的成果。
    // 「列表」与「详情全景」本来也是两种资源：列表行是筛选结果的一行，
    // 详情是聚合了四份上游事实的全景视图，两者连字段集合都不一样。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-details/capabilities            能力声明（售价 / 内部可售量 / 内容草稿 / 销量 available=false + ownerModule）
    //   listing-details/{uuid}                  全景（七块一次给全 + 差异高亮计数）
    //   listing-details/{uuid}/diff             差异对照（本模块的核心价值）
    //   listing-details/{uuid}/content          内容对照（平台当前内容 vs 内部草稿内容）
    //   listing-details/{uuid}/publish-history  发布历史（GET 真分页）
    //   listing-details/{uuid}/platform-operations 平台操作记录（GET 真分页）
    //   listing-details/{uuid}/content-snapshots 登记平台当前内容快照（POST）
    //   listing-details/{uuid}/publish-records  登记发布动作结果（POST）
    //
    // ⚠️ 判别顺序：`capabilities` 必须排在 `{uuid}` **之前**，否则会被 uuid 分支吃掉半截
    //    （症状是「能力声明 404，但详情页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-details/{uuid}/lifecycle`  —— 七态状态机与平台回写归 4.1.1.3
    //   * `listing-details/{uuid}/reprice`     —— 改价归 05 定价，本服务不建影子价格表
    //   * `listing-details/{uuid}/restock`     —— 改库存归 07 库存
    //   * `listing-details/{uuid}/draft`       —— 内容草稿归 4.2.1.1，尚未交付
    //   * `listing-details/{uuid}/writeback`   —— 内容回写归 4.3.2.1，尚未交付
    //   * `listing-details/{uuid}/publish`     —— 发布流程归 4.3.1，本模块只留痕不发起
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-details(?:\/(?:capabilities|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:content|diff|publish\-history|platform\-operations|content\-snapshots|publish\-records))?))?$/,
  },
  {
    // 4.1.2.2 映射版本（channel 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade / channel 分支的 alternation 里并分支 —— 那些分支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.2 `listing-details` 元素**之后**、`prefix: 'trade/'`（9.2.1.2）**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并（与 4.2.1.2 / 4.1.1.2 同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，`mapping-versions` 这个前缀
    // 与 `listings` / `listing-mappings` / `listing-details` / `listing-bulk-edit` 都不同首段，互不吃。
    //
    // 🔴 为什么用 `mapping-versions` 这个独立前缀而不是 `listing-mappings/{uuid}/versions`：
    // 4.1.2.1 交付时把 `/channel/listing-mappings/{uuid}/affected-orders` 显式登记成「不放行」
    // 并在它自己的白名单测试里断言 404（理由：归 4.1.2.2）。往那条分支里加子路径会
    // 让两个车道改同一行正则，合并必冲突。版本是**跨映射**的查询面（列表按 SKU / 店铺 /
    // 生效区间筛，不是挂在某条映射下面），独立前缀也更贴它的实际形状。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   mapping-versions                          版本列表（GET 真分页 + 生效区间重叠筛选）
    //   mapping-versions/capabilities             能力声明（受影响订单 / 订单→版本 available=false + ownerModule）
    //   mapping-versions/filter-options           筛选词表（不硬编码在前端）
    //   mapping-versions/{uuid}                   单版详情（与上一版的真比对 + 商品范围）
    //   mapping-versions/{uuid}/product-scope     按版本回溯商品范围（冻结快照 + 平台当前值对照）
    //   mapping-versions/{uuid}/affected-orders   受影响订单范围（返回 available=false，无 items 字段）
    // ⚠️ 判别顺序：`capabilities` / `filter-options` 必须排在 `{uuid}` **之前**，否则会被
    //    uuid 分支吃掉半截（症状是「能力声明 404，但详情页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余分支的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 本模块**全是 GET**：映射变更生成新版本是 4.1.2.1 的写路径
    //    （`POST /channel/listing-mappings`），这里刻意**不放行任何写方法** ——
    //    放行一个能改写历史版本的形状，比 404 更糟。
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `mapping-versions/{uuid}/versions`      版本历史随详情一起返回，不单独开一条
    //   * `mapping-versions/{uuid}/orders`        「查看某订单用的是哪个版本」是**订单 → 版本**
    //                                              反查，归 09 订单（trade 冻结 order_line_component），
    //                                              本服务查不到订单，放行它只会让人反复试一个不存在的路
    //   * `mapping-versions/{uuid}/components`    套装组件明细归 4.1.2.3
    //   * `mapping-versions/{uuid}/revert`        版本不可回滚：改映射只会生成新版本
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^mapping\-versions(?:\/(?:capabilities|filter\-options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:product\-scope|affected\-orders))?))?$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 1.2.2.2 快捷入口配置（identity 服务，卷 01「工作台与门户」L3 1.2.2 下的第二格）。
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 `prefix: 'identity/'` 那条元素**之前**（即 `saas-plans` 那条之前）----
    //   同批另外三条车道的锚点分别是「`prefix: 'catalog/'` 之前」「`prefix: 'warehouse/'` 之前」
    //   与「数组末尾」；本锚点离最近的 `catalog/` 元素 600 行以上、离 `warehouse/` 1300 行以上、
    //   离数组末尾 6700 行以上 ⇒ 与三条车道的插入点互不重叠，git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条 `identity/` 巨型 alternation
    //   里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「shortcut-templates」的零命中核对结论 ----
    //   本文件既有 248 条 services[] 正则里**没有任何一条**含 `shortcut-templates` 或 `shortcut`
    //   这个串（`grep -c` 实测两者都是 0）⇒ 既有的 `identity/` 元素**不会**静默吞掉本模块的路径，
    //   本模块的元素也**不会**吃掉别人的任何路径（双向都实测过）。
    //
    //   逐条核对过本模块的**全部**形状（放行的 6 个 + 刻意不放行的 6 个），
    //   既有的 `identity/` 巨型 alternation 与其它前缀的 alternation 里都没有 `shortcut` 这个串，
    //   也没有任何通配段能顺带吃掉这个首 token。
    //
    // ---- 路径根只有一个：shortcut-templates ----
    //   模板 CRUD、个人覆盖项、最终生效集合是同一件事（岗位模板 → 覆盖 → 生效）的三个面，
    //   所以放在同一棵路径树下，而不是拆成两个首 token（拆开会让白名单多一条元素，
    //   多车道并入时就多一个冲突面）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   shortcut-templates                        模板列表（GET，真分页 + 真筛选）/ 新建模板（POST）
    //   shortcut-templates/capabilities           能力声明（合并规则 / 权限闸 / 下游未交付归属）
    //   shortcut-templates/effective              **当前用户最终生效的快捷入口集合**（GET）
    //   shortcut-templates/overrides              个人覆盖项列表（GET）/ 写覆盖项（POST）
    //   shortcut-templates/overrides/{uuid}       删掉自己的覆盖项（DELETE）
    //   shortcut-templates/{uuid}                 模板详情（GET）/ 改模板（PUT）/ 删模板（DELETE）
    //
    // ⚠️ 末尾**刻意不带 `?`**：末尾挂一个 `?` 就是「放行裸路径」。
    //   本模块的裸路径 `shortcut-templates` **是真的有端点的**（列表 + 新建），
    //   所以这里用**显式的 alternation** 把裸路径列出来，而不是靠一个可选组顺带放行 ——
    //   形态与既有的 `identity/roles` / `identity/members`（裸集合读）一致，
    //   但与「本来没有裸集合端点、却顺手用 `?` 放行了裸路径」是两回事。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 字面量子路径（`capabilities` / `effective` / `overrides`）排在 id 段之前判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `shortcut-templates/{uuid}/entries` —— 入口清单整体写在模板上，没有独立的子资源
    //   * `shortcut-templates/effective/...` —— 「最终生效集合」只有列表，没有按 id 取单条
    //   * `shortcut-templates/overrides/all` —— 覆盖项没有「一把全清」的动作
    //   * `shortcut-templates/overrides/bulk` —— 覆盖项只能逐条写（批量写会让「这次覆盖了哪个入口」
    //     在审计里说不清）
    //   * `shortcut-templates/not-a-uuid` —— 非 UUID 形状的 id 一律 404
    //   * `shortcut-templates/{uuid}/copy` —— 复制模板没有端点
    //
    // 🔴 本模块**不写**收藏相关的形状（1.2.2.1 常用页面收藏未交付）：
    //   放行一个本模块不实现的路径只会让界面以为「已经可用」。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^(?:shortcut\-templates|shortcut\-templates\/(?:capabilities|effective|overrides(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 1.2.2.1 常用页面收藏（identity 服务，卷 01「工作台与门户」L3 1.2.2 下的第一格）。
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 `shortcut-templates` 那条元素**之后**（按内容定位）----
    //   锚点元素 = 1.2.2.2 快捷入口配置那条（`prefix: 'identity/'`；它的 pattern 在本文件
    //   第 2917 行、`},` 在第 2918 行）。**本元素插在它之后**（第 2919 行起）。
    //   上面那条元素**一字未动**（不改它的 pattern、不改它的注释、不重排）：
    //   它放行 `shortcut-templates` 家族（岗位/团队**预置**的入口模板），
    //   本模块放行 `page-favorites` 家族（用户**自己收藏**的页面）—— 两格各管各的形状。
    //
    // ---- 与同批其它车道的行距（并入时 git 能否干净求并）----
    //   本文件是**多车道共用的累积型白名单**，且**没有** union merge driver
    //   （`tools/module-progress/merge-union.mjs` 只并 `packages/api-contracts` 的
    //   `*.ts` 与 `*.openapi.json`，**不碰** api-proxy.ts）
    //   ⇒ 两条车道改同一个位置必然 git 冲突（结构性，retry 永远不会自愈）。
    //   * **1.1.1.3 审批待办**：锚在 `user-preferences` 那条元素**之后**
    //     （它的 pattern 在第 1379 行、`},` 在第 1380 行 ⇒ 插入点约第 1381 行）。
    //     **本条与它相距 1538 行**（2919 − 1381），远大于 300 行的要求。
    //   * **1.4.2.2 默认店铺与仓库**：锚点在本文件的别处。
    //     本条落在 identity 元素簇 2917–3069（shortcut-templates / 本条 / saas-plans /
    //     saas-usage）之间 —— 若并入时发现 1.4.2.2 也恰好插在这一簇，
    //     **那就是唯一的冲突点**：把两条元素并成一条即可（两者的 `prefix` / `env` /
    //     `fallback` 完全一致，两段 alternation 直接接起来，语义与路由结果都不变）。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条 `identity/` 巨型 alternation
    //   里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「page-favorites」的零命中核对结论（本车道独立实测，非引用他车道结论）----
    //   用 `new RegExp(pat).test(path)` 对**既有每一条** services[] 正则实测本模块的
    //   全部 8 个形状（3 个放行 + 5 个刻意不放行）：**全部零命中**。
    //   实测口径：把本文件既有 `services[]` 的每一条 `pattern:` 正则抽出来
    //   （**192 条**，逐字符处理转义与字符类里的斜杠；其中 191 条可解析，逐条 `test()`）。
    //   ⇒ 既有的 `identity/` 巨型 alternation **不会**静默吞掉本模块的路径，
    //   本模块的元素也**不会**吃掉别人的任何路径（双向都实测过：
    //   把既有正则里的 **140 个字面量 token** 逐个喂给本模块的正则，零误伤）。
    //   这条核对是必做项：`resolve()` 是「首个命中即返回」，落错分支会拿到
    //   **别的服务的响应**而不是 404 —— 症状是页面静默串味，而 typecheck / lint / vitest 全绿。
    //
    // ---- 路径根只有一个：page-favorites ----
    //   收藏清单、收藏/取消收藏、排序、清理隐藏项是同一件事的四个面，
    //   所以放在同一棵路径树下，而不是拆成两个首 token。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   page-favorites                 收藏清单（GET）/ 收藏一个页面（POST）
    //                                  / 取消收藏一个页面（DELETE，目标路径走 ?href= 查询参数）
    //   page-favorites/order           排序（PUT：把某条移到可见清单第 N 位）
    //   page-favorites/hidden          清理当前因权限不足而被隐藏的收藏（DELETE）
    //
    // ⚠️ 末尾**刻意不带 `?`**：末尾挂一个 `?` 就是「放行裸路径」。
    //   本模块的裸路径 `page-favorites` **是真的有端点的**（清单 / 收藏 / 取消收藏），
    //   所以这里用**显式的 alternation** 把裸路径列出来，而不是靠一个可选组顺带放行 ——
    //   形态与既有的 `identity/roles` / `identity/members`（裸集合读）一致。
    //
    // ⚠️ 子路径只用两个字面量子路径（`order` / `hidden`），没有通配段 ——
    //   放行一个后端不存在的映射比 404 更糟（界面会以为功能已交付）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `page-favorites/not-a-shape` —— 本模块只有 order / hidden 两个子路径
    //   * `page-favorites/order/extra` —— 排序下面没有更深的一层
    //   * `page-favorites/hidden/extra` —— 清理动作没有子资源
    //   * `page-favoritesx` —— 首 token 拼错一律 404（不放行模糊匹配）
    //   * `favorite-pages` —— 另一套前缀不是本模块的形状
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^page\-favorites(?:\/(?:order|hidden))?$/,
  },
  {
    // 17.7.1.1 套餐与权益（identity 服务，卷 17「组织权限与设置」L3 17.7.1 下的第一格）。
    //
    // 本条目是**新增一条独立的 `services[]` 元素**，既有的 `identity/` 巨型 alternation
    // **一字未动、也不重排** —— 那一支是单行超长正则，往里并分支会让多车道同时并入时
    // git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `// 2.4.2.3 映射缺失队列（channel 服务）` 那条元素**之前** ----
    //   本批另外三条车道的锚点分别是「`// 5.5.1.1 价格历史` 之前」「`// 07 库存：库存总览
    //   （7.1.1.1）` 之前」与「数组末尾」，与本处互不重叠 ⇒ git 能干净求并。
    //
    // ---- 首 token「saas-plans」的零命中核对结论 ----
    //   本文件既有 173 条 services[] 正则里**没有任何一条**含 `saas-plans` 这个串
    //   （既有的是 `business-parameters` / `numbering` / `approval-flows` / `my-approvals`
    //   一类），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   逐条用 `new RegExp(pat).test('…')` 实测过：既有 173 条对本模块的 13 个 shape
    //   **全部零命中** ⇒ resolve() 的「首个命中即返回」不会让本模块的路径落到别的分支上。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   saas-plans/capabilities            能力声明（哪些已交付、哪些未交付 + 归属模块 + 影响面）
    //   saas-plans/options                 下拉选项与词表（套餐、权益维度、超限策略、周期）
    //   saas-plans/plans                   套餐列表（GET）/ 新建套餐（POST）
    //   saas-plans/plans/{code}            套餐详情（GET）/ 改套餐（PUT）
    //   saas-plans/plans/{code}/entitlements        新增权益项（POST）
    //   saas-plans/plans/{code}/entitlements/{code} 改权益项（PUT）/ 删权益项（DELETE）
    //   saas-plans/subscriptions           跨租户订阅看板（GET）/ 开通订阅（POST）
    //   saas-plans/subscriptions/expire-due          批量处理到期订阅（POST）
    //   saas-plans/subscriptions/{uuid}    订阅详情（GET，含权益用量与变更留痕）
    //   saas-plans/subscriptions/{uuid}/status       状态流转（POST）
    //   saas-plans/subscriptions/{uuid}/events       变更留痕（GET）
    //   saas-plans/usage                   权益用量视图（GET，?tenantId=）/ 登记用量（POST）
    //   saas-plans/entitlement-check       超限判定（GET，?tenantId=&entitlementCode=）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `saas-plans` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `saas-plans/plans/{code}/entitlements/{code}/extra` —— 权益项下面没有更深的一层
    //   * `saas-plans/subscriptions/{uuid}/delete` —— 订阅是事实，不提供物理删除
    //   * `saas-plans/not-a-shape` —— 非 UUID / 非大写标识符的段不应被放行
    //   * `saas-plans/plans/starter` —— 套餐编码是大写标识符，小写编码后端会拒（400）
    //   * `saas-platform/roles` —— **平台运营角色登记是服务端到服务端的专用路径**
    //     （要 X-Internal-Key），浏览器打不到它；放行它等于让任意租户把自己升级成平台运营方
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`）：后者会把 `expire-due` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / plans / subscriptions / usage / entitlement-check）
    // 排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^saas\-plans\/(?:capabilities|options|plans(?:\/[A-Z][A-Z0-9_]{1,63}(?:\/entitlements(?:\/[A-Z][A-Z0-9_]{1,63})?)?)?|subscriptions(?:\/expire\-due|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|events))?)?|usage|entitlement\-check)$/,
  },
  {
    // 17.7.1.2 用量统计（identity 服务，卷 17「组织权限与设置」L3 17.7.1 下的第二格）。
    //
    // 本条目是**新增一条独立的 `services[]` 元素**，既有的 `identity/` 巨型 alternation
    // **一字未动、也不重排** —— 那一支是单行超长正则，往里并分支会让多车道同时并入时
    // git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `// 2.4.2.3 映射缺失队列（channel 服务）` 那条元素**之前** ----
    //   即 17.7.1.1 套餐与权益那条元素**之后**。同批另一条车道（17.7.2.2 平台运维视图）
    //   锚在数组末尾，与本处相隔 6000 行以上 ⇒ git 能干净求并。
    //
    // ---- 首 token「saas-usage」的零命中核对结论 ----
    //   本文件既有 **182** 条 services[] 正则里**没有任何一条**会命中本模块的 8 个 shape：
    //   逐条用 `new RegExp(pat).test('…')` 实测过（既有的是 `saas-plans` / `business-parameters`
    //   / `platform-flags` / `dictionary` 一类），**全部零命中** ⇒ resolve() 的
    //   「首个命中即返回」不会让本模块的路径落到别的分支上。
    //   反向也核过：本模块这条正则对既有 identity/ 形状（`saas-plans/**` / `tenants` /
    //   `roles` / `business-parameters` / `platform-flags/**` / `dictionary/**` …）零命中。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   saas-usage/capabilities   能力声明（哪些已交付、哪些未交付 + 归属模块 + 影响面）
    //   saas-usage/options        词表（六个维度码 / 四个周期码 / 覆盖与计费状态）
    //   saas-usage/report         用量报表（GET，?tenantId=&kind=&periodType=）
    //   saas-usage/trend          趋势（GET，?tenantId=&kind=&periodType=）
    //   saas-usage/billing-basis  计费依据（GET，?tenantId=&kind=&periodType=）
    //   saas-usage/capacity       容量规划（GET，?periodType=）
    //   saas-usage/coverage       维度覆盖与来源系统现状（GET）
    //   saas-usage/collections    归集批次历史（GET，?tenantId=）/ 运行归集（POST）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `saas-usage` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `saas-usage/not-a-shape` —— 非本模块的子路径
    //   * `saas-usage/report/extra` —— 本模块的读出口下面没有更深的一层
    //   * `saas-usage/collections/{uuid}` —— 归集批次没有单条详情端点
    //
    // ⚠️ query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search），
    //    所以 `?tenantId=…` 这类筛选参数不用登记。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^saas\-usage\/(?:capabilities|options|report|trend|billing\-basis|capacity|coverage|collections)$/,
  },
  {
    // 12.6.1.2 VAT/IOSS 申报数据（finance 服务，卷 12「财务核算」L3 12.6.1 税率配置下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里
    //   并分支 —— 那些是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点固定在集成分支当前文件**第 4071 行**那条元素之前 ----
    //   该元素的注释标题是「2.4.2.3 映射缺失队列（channel 服务）」，
    //   插入点 = 该元素 `{` 起始行之前（**不是** `prefix:` 行之前 —— 插错位置会破坏对象字面量）。
    //   同批另外 3 条车道的锚点分别在第 314 行、第 1505 行、第 9040 行，四处互不相邻，
    //   git 能干净求并。
    //
    // ---- 首 token「filing-draft」的零命中核对结论 ----
    //   对**集成分支当前基线**（`integration/module-dev` 的 api-proxy.ts）实测：
    //   `grep -c "filing-draft"` = **0**（零命中）。
    //   本文件既有 225 条 services[] 正则里**没有任何一条**含 `filing-draft` 这个串
    //   （既有 finance 那几支的首 token 是 `receivables` / `receivable-aging` / `payables` /
    //   `receipts` / `bad-debt` / `payment-plans` / `payment-orders` / `payment-executions` /
    //   `write-offs` / `settlement` / `transactions` / `fund-analysis` / `cost-collections` /
    //   `cost-valuation` / `deferred-settlement` / `tax-rates`，**全不相同**），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    //   ⚠️ **刻意不用 `tax-return` / `tax-returns`**：`tax-registration/tax-returns` 这个形状已被
    //   本文件注释标注为「12.6 税务（finance 服务在本仓尚未落地）**未交付**」的保留位，
    //   用了会和 16 卷的税号模块撞语义。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   filing-draft/capabilities            能力声明（上游真接通 / 缺口归谁 / 产品边界）
    //   filing-draft/options                 筛选项（申报线 / 周期 / 状态 / 币种 + 辖区受控码表）
    //   filing-draft/drafts                  底稿列表（GET，真分页 + 真筛选）/ 生成底稿（POST）
    //   filing-draft/drafts/{uuid}           底稿详情（GET：头 + 按币种分组的汇总 + 数据来源）
    //   filing-draft/drafts/{uuid}/summary    汇总报表（GET，按币种分组）
    //   filing-draft/drafts/{uuid}/export     导出申报文件（GET，CSV / JSON **真文件** + 导出留痕）
    //   filing-draft/exports                 导出留痕列表（GET，真分页 + 真筛选）
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `summary` / `export` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / exports / drafts）排在 `{uuid}` 段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   filing-draft                        —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   filing-draft/{uuid}                 —— 顶层没有 id 段（底稿按 /drafts/{uuid} 定位）。
    //   filing-draft/drafts/not-a-uuid      —— 非 UUID 段不应被 uuid 分支吃掉。
    //   filing-draft/drafts/{uuid}/not-a-shape —— 多一层仍然 404。
    //   filing-draft/capabilities/{uuid}    —— 字面量段刻意不接 id 段。
    //   filing-draft/submit                 —— 🔴 「向税务机构提交申报」**本模块不做这个动作**，
    //                                          也不假装提交成功（申报主体与责任在用户）。
    //   filing-draft/reports                —— 🔴 财务报表（12.5.2.2）**未交付**。
    //   filing-draft/invoices               —— 🔴 发票管理（12.6.2.1）**未交付**。
    //   filing-draft/tax-rates              —— 🔴 税率配置（12.6.1.1）**不是本模块的路径**。
    //   filing-draft/vat-ioss               —— 🔴 VAT/IOSS 事实（16.3.2.1）**是另一条车道**。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^filing\-draft\/(?:capabilities|options|exports|drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:summary|export))?)?)$/,
  },
  {
    // 2.4.2.3 映射缺失队列（channel 服务）。本条目是**新增一条独立的 `channel/` 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.2.2 `mapping-versions` 条目**之后**、4.1.2.3 `bundle-components`
    // 条目**之前**：落在**映射族**（`listing-mappings` / `mapping-versions`）旁边。
    // ⚠️ 与 4.4.2.1（`listing-content-writeback` 之后）、11.2.3.3（trade 组内）的插入点
    //    都拉开 30 行以上 —— 同一处插入两个元素会让 git 判冲突（结构性，retry 不会自愈）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   mapping-gaps                 待映射清单（GET 真分页 + 六个筛选维度）
    //   mapping-gaps/summary         队列概览（状态 / 原因 / 店铺分布 + 最老的一条）
    //   mapping-gaps/filter-options  筛选面板词表（不硬编码在前端）
    //   mapping-gaps/capabilities    能力声明（未交付段 available=false + ownerModule）
    //   mapping-gaps/orders          09 订单缺映射的**事件接入点**（POST；当前 trade 侧未推）
    //   mapping-gaps/{uuid}          缺口详情（GET）
    //   mapping-gaps/{uuid}/map      映射到已有 SKU（POST）
    //   mapping-gaps/{uuid}/create-sku  登记「新建 SKU」请求（POST；**只登记意图，不建档**）
    //   mapping-gaps/{uuid}/ignore   标记忽略（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / summary / orders）
    //    必须排在 `{uuid}` 分支**之前**，否则会被 uuid 段吃掉半截
    //    （症状是「能力声明 404，但列表页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    // ⚠️ 裸路径 `mapping-gaps` **放行**：本模块的列表就是 `GET /channel/mapping-gaps?...`，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `mapping-gaps/{uuid}/backfill`        —— 回填由映射版本落库的 DB 触发器完成，没有独立端点
    //   * `mapping-gaps/{uuid}/affected-orders` —— 「回填受影响订单」未接线（09 订单在 trade 服务）
    //   * `mapping-gaps/{uuid}/suggest`         —— 🔴 **绝不按名称猜 SKU**：本模块不提供任何自动匹配
    //   * `mapping-gaps/batch`                  —— 批量建立映射归 4.1.2.1，本模块只逐条决策
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^mapping\-gaps(?:\/(?:capabilities|filter\-options|summary|orders|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:map|create\-sku|ignore))?))?$/,
  },
  {
    // 4.1.2.3 套装组件映射（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.2.1 的 `listing-mappings` 或 4.1.2.2 的 `mapping-versions` 分支里并子路径
    // —— 那两条是已交付车道的成果，多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `mapping-versions` 条目**之后**、既有 `listing-publish` 条目**之前**：
    // 与同 L3 的两个兄弟相邻但仍是独立元素，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   bundle-components/capabilities                    能力声明（未交付段 available=false）
    //   bundle-components/filter-options                   筛选词表（不硬编码在前端）
    //   bundle-components/bindings                         套装版本绑定列表（GET）+ 新建绑定（POST）
    //   bundle-components/bindings/{uuid}                  单个绑定详情：版本选择 + 逐组件明细
    //   bundle-components/bindings/{uuid}/sellable-check  🔴 可售性校验结果（缺任一组件 ⇒ 整套不可售）
    //   bundle-components/components                       组件映射记录列表（GET）+ 新增组件（POST）
    //   bundle-components/components/{uuid}                改组件（PUT）/ 删除组件登记（DELETE）
    //
    // ⚠️ 判别顺序：`capabilities` / `filter-options` 必须排在 `{uuid}` **之前**，否则会被
    //    uuid 分支吃掉半截（症状是「能力声明 404，但详情页正常」）。
    // ⚠️ 两个字面量段 `bindings` / `components` 也必须在 uuid 分支之前，理由同上。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余分支的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `bundle-components/bindings/{uuid}/components`  组件明细随详情一起返回，不单独开一条
    //   * `bundle-components/bindings/{uuid}/orders`       「按组件预占库存」是 07 库存自己的写路径
    //                                                     （warehouse 执行），本服务不发起预占
    //   * `bundle-components/components/{uuid}/sellable-check`  可售性校验按**绑定**判定，
    //                                                     因为整套的可售性是所有组件的合取
    //   * `bundle-components/bindings/{uuid}/revert`       换版不靠 revert：生成新绑定并退役旧的，
    //                                                     已退役绑定的组件是历史，不可复活
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^bundle\-components(?:\/(?:capabilities|filter\-options|bindings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/sellable\-check)?)?|components(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?$/,
  },
  {
    // 4.3.1.2 批量发布（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.2 `listing-details` 条目**之后**、既有 `prefix: 'trade/'` 元素**之前**：
    // 与兄弟模块相邻但仍是独立元素，各车道插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-publish` 这个独立前缀而不是 `listings/{id}/publish`：
    // 4.1.1.1 与 4.1.1.2 都把 `listings/{uuid}/publish` 显式登记成「不放行」（那是单条发布，
    // 归 4.3.1.1 / 4.3.1.3）。批量发布是**队列**（批次 + 限流 + 顺序 + 重试），
    // 与「列表行」不是同一种资源 —— 强行开在同一棵路径树上要同时改别人的白名单与拒绝清单。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-publish/capabilities            能力声明（SPU 状态筛选 / 平台实际调用 available=false + ownerModule）
    //   listing-publish/rate-limits             🔴 平台限流配置（GET 查 / POST 配，可配置可查的数据）
    //   listing-publish/batches                 建批次（POST，按筛选规则）+ 批次列表（GET）
    //   listing-publish/batches/{uuid}          批次详情（总体进度五计数 + 百分比）
    //   listing-publish/batches/{uuid}/tasks    批次任务列表（GET，可按状态筛；失败明细用 state=FAILED）
    //   listing-publish/batches/{uuid}/dispatch 🔴 一轮派发：按各平台自己的配额排队
    //   listing-publish/batches/{uuid}/pause    暂停（停止派发新任务；在途不受影响）
    //   listing-publish/batches/{uuid}/resume   恢复
    //   listing-publish/batches/{uuid}/cancel   人工中止
    //   listing-publish/batches/{uuid}/reorder  调整顺序（按给的新次序重排待派发任务）
    //   listing-publish/batches/{uuid}/retry    批量重试（只重试 FAILED，不重跑已成功的）
    //   listing-publish/tasks/{uuid}/report     在途任务的成功/失败回执（4.3.1.3 回报）
    //
    // ⚠️ 判别顺序：`capabilities` 与 `rate-limits` 必须排在 `{uuid}` **之前**，
    //    否则会被 uuid 分支吃掉半截（症状是「能力声明 404，但列表页正常」）。
    // ⚠️ 四个批次动作（dispatch / pause / resume / cancel / reorder / retry）挂在
    //    **uuid 段之后**且各自成支：写成 `/batches/dispatch` 会放行一个不存在的
    //    「按批次编码派发」的形状。
    // ⚠️ 裸路径 `listing-publish` **刻意不放行**（最外层分组不带 `?`）：本模块没有裸路径资源。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-publish/batches/{uuid}/retry-all` —— 没有「连成功的一起重跑」的入口，
    //     那是本模块最危险的假实现（重复发布 / 重复建 listing）
    //   * `listing-publish/tasks/{uuid}`            —— 单条任务详情随批次任务列表返回，不单开一条
    //   * `listing-publish/rate-limits/{uuid}`      —— 限流配置按 (平台, 店铺) upsert，没有单条删除
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-publish(?:\/(?:capabilities|rate\-limits)|\/batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:tasks|dispatch|pause|resume|cancel|reorder|retry))?)?|\/tasks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/report)$/,
  },
  {
    // 4.3.2.1 内容回写（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 上面那条 `listing-publish`（4.3.1.2 批量发布）**一字未动、也不重排** —— 本文件是多车道
    // 共用的累积型正则，取错一侧会静默删掉别人已交付的路由，症状是页面 404 而 lint/typecheck 全绿。
    // 插入锚点固定在 `listing-publish` 那组**之后**、既有 `prefix: 'trade/'` 那条**之前** ——
    // 各车道的插入点被既有条目隔开，git 能干净求并（与 9.2.1.2 / 10.1.1.2 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-content-writeback` 这个独立前缀，而不是 `listings/{uuid}/writeback`：
    //   4.1.1.1 已把 `listings/{uuid}` 那一段登记成「不放行」，在同一棵路径树上开子资源要同时改
    //   别人已交付的白名单与拒绝清单；而「内容回写」是**批量触发 + 队列 + 限流 + 变更历史**，
    //   与「列表行」不是同一种资源 —— 强行开在同一棵树上会与 4.1.1.1 / 4.1.1.2 的既有决定打架。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                                能力声明（平台往返 / 上游变更事件 / 描述与 A+ 基线 available=false）
    //   writebacks                                  建一次回写（POST，按变更类型 / 批量）+ 列表（GET）
    //   writebacks/{uuid}                           单次触发的总体进度（六计数 + 百分比）
    //   writebacks/{uuid}/tasks                     任务列表（GET，可按状态筛；跳过用 SKIPPED_NO_CHANGE）
    //   writebacks/{uuid}/dispatch                  🔴 一轮派发：按各平台配额排队 + 写 02 队列
    //   writebacks/{uuid}/retry                     🔴 批量重试（只重试 FAILED，不重跑已成功的）
    //   writebacks/{uuid}/cancel                    人工中止（已在途的不取消）
    //   tasks/{uuid}/changes                        🔴 逐字段改前 / 改后（含两侧来源）
    //   tasks/{uuid}/report                         在途任务的成功 / 失败回执（4.3.2.2 回报）
    //   listings/{uuid}/content-history             某商品的变更历史（按时间倒序）
    //
    // ⚠️ 判别顺序：`capabilities` 必须排在所有 `{uuid}` 分支**之前**，否则会被 uuid 分支吃掉半截
    //    （症状是「能力声明 404，但列表页正常」）。
    // ⚠️ 三个 uuid 段各自分支：`writebacks/{uuid}` / `tasks/{uuid}/changes|report` /
    //    `listings/{uuid}/content-history`。⚠️ `tasks` 那段的**动作段是必选的**
    //    （`(?:...)?` 会连 `tasks/{uuid}` 裸路径一起放行 —— 后端没有这个资源，
    //    单条任务详情随任务列表返回），写成可选就成了一个凭空多出来的 GET。
    // ⚠️ 裸路径 `listing-content-writeback` **刻意不放行**（最外层分组不带 `?`）：本模块没有裸路径资源。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-content-writeback/writebacks/{uuid}/writebacks` —— 一次触发只回写一次，没有嵌套触发
    //   * `listing-content-writeback/tasks/{uuid}`                    —— 单条任务详情随任务列表返回，不单开一条
    //   * `listing-content-writeback/writebacks/{uuid}/preview`        —— 回写不是编辑，没有「确认前先看看」
    //   * `listing-content-writeback/prices|stocks`                    —— 🔴 那是 05 定价 / 07 库存两条独立通道
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-content\-writeback(?:\/capabilities|\/writebacks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:tasks|dispatch|retry|cancel))?)?|\/tasks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:changes|report)|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/content\-history)$/,
  },
  {
    // 4.2.1.1 刊登编辑器（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.4.2.1 `listing-claims` 元素**之前** —— 与其它车道
    // （`listing-claims` 之后 / `crm/` 之前 / `services` 数组最末尾）都不重叠。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-editor` 这个独立前缀而不是 `listings/{id}/editor`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「编辑」本来就不是 listing 的一种状态 —— 草稿按 **平台 + 站点 + 语言** 存，
    //   一条 listing 在不同语言下就是多份草稿，挂 listing 上会把这个维度压扁。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-editor/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   listing-editor/form-schema             🔴 动态表单契约（字段集由平台要求推导）
    //   listing-editor/validate                字段级校验试算（不落库）
    //   listing-editor/drafts                  草稿列表（GET）
    //   listing-editor/drafts/{uuid}           单条草稿（GET）
    //   listing-editor/drafts/{uuid}/submit    🔴 提交审核 = 入队（POST）
    //   listing-editor/submissions             入队队列（GET）
    //   listing-editor/source-product          源商品（03，只读）
    //   listing-editor/media-assets            媒体库素材（03，只读）
    //   listing-editor/drafts（POST）          保存草稿（同一形状两种方法）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / form-schema / validate / drafts / submissions /
    //    source-product / media-assets）**排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-editor/drafts/{uuid}/publish`  —— 🔴 派发归 4.3.1.1，本模块只入队
    //   * `listing-editor/drafts/{uuid}/dispatch` —— 同上
    //   * `listing-editor/drafts/{uuid}/apply-template` —— 🔴 4.2.2.1 刊登模板未交付
    //   * `listing-editor/drafts/{uuid}/delete`   —— 草稿是内容事实，不提供删除
    //   * `listing-editor/templates`              —— 4.2.2.1 刊登模板未交付
    //   * `listing-editor/listing/{uuid}`        —— 没有"按 listing 取编辑器"这个形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-editor(?:\/capabilities|\/form\-schema|\/validate|\/submissions|\/source\-product|\/media\-assets|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/submit)?)?)?$/,
  },
  {
    // 4.2.3.2 合规预检（违禁词/侵权）（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.2.1.1 `listing-editor` 元素**之后**、4.4.2.1 `listing-claims` 元素**之前**。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-compliance-precheck` 这个独立前缀：
    //   * 它**不是** `compliance-precheck` —— 那是同批并行那条车道（16.2.2.1 合规体检报告，
    //     合规域 D7）的前缀。两边名字几乎一样但**不共表、不共端点、不共页面**，
    //     用错前缀会打到别人的端点上去。
    //   * 它也**不是** `listings/{id}/compliance` —— `/channel/listings/{uuid}/...` 那一段
    //     由 4.1.1.1 登记成「不放行」，在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，
    //     而那份清单是别人的成果。而且「合规预检」不是 listing 的一种状态，
    //     它是**发布前对一份草稿的一次扫描**（按 平台 + 站点 + 语言 分别存的那一份内容）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-compliance-precheck/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   listing-compliance-precheck/filter-options           筛选项词表
    //   listing-compliance-precheck/drafts                   可扫描的编辑器草稿（GET）
    //   listing-compliance-precheck/drafts/{uuid}/publish-gate  🔴 服务端发布闸门（GET）
    //   listing-compliance-precheck/scans                    触发扫描（POST）/ 扫描历史（GET）
    //   listing-compliance-precheck/scans/{uuid}             单次扫描详情（GET）
    //   listing-compliance-precheck/risks/decisions          风险批量处置（POST）
    //   listing-compliance-precheck/words                    词库（GET）/ 新增词（POST）
    //   listing-compliance-precheck/words/{uuid}             改词（PUT）/ 删词（DELETE）
    //   listing-compliance-precheck/words/{uuid}/enabled     启停词（PUT）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / drafts / scans / risks / words）
    //    **排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    //    `drafts/{uuid}/publish-gate` 这一支排在裸 `drafts/{uuid}` 段之前（首段是字面量 `drafts`，
    //    与 uuid 段不会互相吃掉，但顺序保持「字面量在前」这一条文件的统一口径）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-compliance-precheck/drafts/{uuid}/publish`  —— 🔴 发布归 4.3.1.1，本模块只出闸门结论
    //   * `listing-compliance-precheck/drafts/{uuid}/dispatch` —— 同上
    //   * `listing-compliance-precheck/scans/{uuid}/publish`   —— 同上
    //   * `listing-compliance-precheck/compliance-precheck`   —— 🔴 那是 16.2.2.1 的前缀，不属于本模块
    //   * `listing-compliance-precheck/words/{uuid}/scan`     —— 扫描是针对草稿的，不是针对词的
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-compliance\-precheck(?:\/capabilities|\/filter\-options|\/risks\/decisions|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/publish\-gate)?|\/scans(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/words(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?)$/,
  },
  {
    // 4.5.2.1 跟卖监控（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.2.3.2 `listing-compliance-precheck` 那条**之后**、
    // 4.4.2.1 `listing-claims` 那条**之前** —— 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-hijackers` 这个独立前缀：
    //   * 跟卖在跨境电商语境里英文是 **hijacker**（抢listing的人），所以前缀是 `listing-hijackers`。
    //   * 它**不是** `listing-claims`（4.4.2.1 商品认领，跨店搬家的 listing 草稿）——
    //     那是"把 listing 搬走"，本模块是"盯着别人抢我的 listing"，两码事。
    //   * 它**不是** `listing-compliance-precheck`（4.2.3.2 发布前的违禁词/侵权扫描）——
    //     那是"扫内容"，本模块是"看价格"。
    //   * 它**不是** `listing-keyword-rankings`（4.5.1.2 关键词排名，未交付）——
    //     那是"看排名"，与跟卖价无关。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-hijackers/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   listing-hijackers/filter-options           筛选项词表
    //   listing-hijackers/watches                   监控对象列表（GET）/ 新建监控对象（POST）
    //   listing-hijackers/watches/{uuid}           改监控对象（PUT）/ 删除监控对象（DELETE）
    //   listing-hijackers/records                   跟卖记录列表（GET）/ 接收跟卖信息（POST）
    //   listing-hijackers/alerts                    预警记录列表（GET）
    //   listing-hijackers/alerts/{uuid}/acknowledge 标记预警已阅（PUT）
    //   listing-hijackers/comparison               价格对比视图（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / watches / records / alerts /
    //    comparison）**排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    //    `alerts/{uuid}/acknowledge` 这一支排在裸 `alerts` 段之后 —— 首段是字面量 `alerts`，
    //    与 uuid 段不会互相吃掉，但顺序保持「字面量在前」这一条文件的统一口径。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-hijackers/{uuid}/complaint`       —— 🔴 投诉/举报/申诉在**平台侧**完成，
    //     本模块只负责发现与提醒，**没有**这些动作端点
    //   * `listing-hijackers/{uuid}/report`          —— 同上
    //   * `listing-hijackers/{uuid}/appeal`          —— 同上
    //   * `listing-hijackers/notifications`          —— 🔴 1 工作台预警未交付（available=false）
    //   * `listing-hijackers/brand-protection`        —— 🔴 16 合规品牌保护未交付（available=false）
    //   * `listing-hijackers/crawlers`                —— 🔴 02 抓取调度器未交付（available=false）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-hijackers(?:\/capabilities|\/filter\-options|\/comparison|\/watches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/records|\/alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?)$/,
  },
  {
    // 4.4.2.1 商品认领（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `listing-content-writeback` 那条**之后**、既有 `prefix: 'trade/'`
    // 元素**之前** —— 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-claims` 这个独立前缀而不是 `listings/{id}/claim`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「认领」本来就不是 listing 的一种状态 —— 它是**一次跨店搬家的操作**，
    //   挂在 listing 上会让"这个商品的状态"多出第七种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-claims                        认领历史（GET 真分页 + 状态/目标店铺筛选）/ 发起认领（POST）
    //   listing-claims/capabilities           能力声明（未交付段 available=false + ownerModule）
    //   listing-claims/{uuid}                 单次认领详情（GET）
    //   listing-claims/listings/{uuid}/claims 某个商品的认领历史（作为源或作为目标）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）**排在** uuid 段之前，
    //    `listings/{uuid}/claims` 这一支也排在裸 uuid 段之前（首段是字面量 `listings`，
    //    与 uuid 段不会互相吃掉，但顺序保持"字面量在前"这一条文件的统一口径）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-claims/{uuid}/publish`   —— 🔴 派发归 4.3.1.2 批量发布，本模块只入队
    //   * `listing-claims/{uuid}/dispatch`  —— 同上，平台限流与派发都在 4.3.1.2 那条通道
    //   * `listing-claims/{uuid}/abandon`   —— 认领记录是只追加的事实，没有"作废"这个动作
    //   * `listing-claims/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-claims/{uuid}/fields`     —— 逐字段决策随详情一起返回，不单开一条
    //   * 裸 `listing-claims/{uuid}/listings` —— 没有"某次认领的商品列表"这个形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-claims(?:\/capabilities|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/claims|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 4.4.1.1 链接采集（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.2.1 `listing-claims` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-status-events` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-collects                    采集任务列表（GET，真分页 + 状态/来源筛选）/ 发起一次链接采集（POST）
    //   listing-collects/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   listing-collects/{uuid}              采集任务详情（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-collects/{uuid}/draft`   —— 草稿随任务详情一起返回，不单开一条
    //   * `listing-collects/{uuid}/retry`   —— 失败重试就是再发一次同一个 URL（后端自己去重）
    //   * `listing-collects/{uuid}/claim`   —— 🔴 认领是 4.4.1.3 采集箱的职责，尚未交付
    //   * `listing-collects/batch`          —— 🔴 批量/插件采集是 4.4.1.2 的职责，尚未交付
    //   * `listing-collects/box`            —— 🔴 采集箱是 4.4.1.3 的职责，尚未交付
    //   * `listing-collects/sources`        —— 白名单随能力声明一起返回，不单开一条
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern: /^listing\-collects(?:\/capabilities|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 14.1.3.2 投放定位（channel 服务）。本条目是**新增一条独立的 services[] 元素**，
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的累积型白名单，
    // 每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让多车道同时并入时
    // git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.1.1 `listing-collects` 那条**元素之后**（锚点字面量：
    // `pattern: /^listing\-collects...$/,`）—— 与本批其它三条车道的插入点互不重叠，
    // git 能干净求并。
    //
    // ---- 首 token「ad-targetings」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-targetings` 这个串
    //   （既有的是 `ad-campaigns` / `ad-campaign-details` / `ad-keywords` / `ad-groups` /
    //   `ad-reports` / `ad-rules` 一类），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-targetings                    定位设置集合读（GET，真分页 + 真筛选 + 真排序）/ 新建（POST）
    //   ad-targetings/capabilities       能力声明（未交付段 available=false + ownerModule）
    //   ad-targetings/options            筛选项下拉 + 建议门槛与系数护栏
    //   ad-targetings/summary            定位设置汇总（条数；不做跨币种金额相加）
    //   ad-targetings/effects            🔴 定位效果对比表（GET，真分页 + 真筛选 + 真排序 + 汇总）
    //   ad-targetings/suggestions        🔴 调整建议（GET，带依据数值与口径）
    //   ad-targetings/daypart-profile    🔴 时段画像（GET，供下游 14.2.1.2 分时策略复用）
    //   ad-targetings/interpret          🔴 解读平台原文定位串（POST，预览 / 落库）
    //   ad-targetings/metrics            🔴 定位维度效果数据同步（POST，真实写入口）
    //   ad-targetings/{uuid}             定位设置详情（GET）/ 改（PATCH）/ 归档（DELETE）
    //   ad-targetings/{uuid}/operations  定位操作留痕（GET，真分页）
    //   ad-targetings/{uuid}/narrow      🔴 收窄投放（POST，带证据门槛）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / options / summary / effects / suggestions /
    //   daypart-profile / interpret / metrics）必须排在 uuid 段**之前**判别，
    //   否则它们会被当成「id=capabilities 的那个 UUID」拼出去（这是本仓反复踩过的坑）。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （**不是** `[0-9a-f-]+` —— 后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `ad-targetings/{uuid}/metrics`      —— 效果数据是**批量**入口，不按设置下钻
    //   * `ad-targetings/{uuid}/suggestions` —— 建议按维度聚合，不按单条设置出建议
    //   * `ad-targetings/{uuid}/interpret`    —— 解读是**活动 / 组**级的，不是单条设置
    //   * `ad-targetings/{uuid}/daypart-profile` —— 时段画像是集合级的
    //   * `ad-targetings/{uuid}/bid` / `/bid-factor` —— 🔴 改竞价归 14.2.1.1（尚未交付），
    //     本模块只给**出价系数建议**与**系数记录**，放行它会让界面以为能改价
    //   * `ad-targetings/export`             —— 本模块没有导出入口
    //   * `ad-targetings/batch`              —— 本模块没有批量动作入口
    //   * `ad-targetings/writeback`          —— 🔴 平台回写队列未交付，放行它会让人以为能回写平台
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-targetings(?:\/(?:capabilities|options|summary|effects|suggestions|daypart\-profile|interpret|metrics)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:operations|narrow))?)?$/,
  },
  {
    // 4.4.1.2 批量/插件采集（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.1.1 `listing-collects` 那一条**之后** ——
    // 与本批另外三条车道的锚点互不重叠，git 能干净求并。
    //
    // 🔴 为什么用 `listing-bulk-collects` 这个**独立前缀**而不是 `listing-collects/batch`：
    //   4.4.1.1 的 `listing-collects` 元素刻意**没有**放行 `/batch`（那是本模块的职责）。
    //   若把批量采集挂在它下面，就必须去改别人已交付的那一条元素 —— 那是别人的成果。
    //   批量采集本来就不是「一次链接采集」的一种，它是一批 URL 的批量动作，
    //   独立前缀更贴切（与 4.4.2.1 `listing-claims`、4.3.2.2 `listing-status-events` 同一手法）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-bulk-collects                     批量任务列表（GET，真分页 + 模式/状态/来源筛选）/ 发起批量采集（POST）
    //   listing-bulk-collects/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   listing-bulk-collects/plugin-reports      浏览器插件上报入口（POST）
    //   listing-bulk-collects/{uuid}              批量任务详情（GET）
    //   listing-bulk-collects/{uuid}/results      结果清单（GET，真分页 + 状态筛选）
    //   listing-bulk-collects/{uuid}/run          执行一次批量采集（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / plugin-reports）必须排在 uuid 段**之前**，
    //    否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-bulk-collects/box`             —— 🔴 采集箱是 4.4.1.3 的职责，尚未交付
    //   * `listing-bulk-collects/{uuid}/claim`     —— 🔴 认领是 4.4.1.3 采集箱的职责，尚未交付
    //   * `listing-bulk-collects/{uuid}/draft`    —— 草稿随结果清单一起返回，不单开一条
    //   * `listing-bulk-collects/{uuid}/retry`    —— 继续采集就是再调一次 `/run`（服务端保留断点）
    //   * `listing-bulk-collects/undeclared-action` —— 不存在的动作
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-bulk\-collects(?:\/capabilities|\/plugin\-reports|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/run|\/results)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 4.4.1.3 采集箱（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.1.2 `listing-bulk-collects` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`order-intercept` / `fulfillment/consolidation` /
    // `listing-keyword-rankings` 之后）互不重叠，git 能干净求并。
    //
    // 🔴 为什么用 `listing-collect-box` 这个**独立前缀**而不是 `listing-collects/box`：
    //   4.4.1.1 的 `listing-collects` 元素刻意**没有**放行 `/box`（那是本模块的职责），
    //   若把采集箱挂在它下面，就必须去改别人已交付的那一条元素 —— 那是别人的成果。
    //   采集箱本来就不是「一次链接采集」的一种状态，是**一批草稿的暂存区**，
    //   独立前缀更贴切（与 4.4.2.1 `listing-claims`、4.5.2.1 `listing-hijackers` 同一手法）。
    //
    // 🔴 首 token 与既有每一条都不同（既有：`listing-collects` / `listing-bulk-collects` /
    //   `listing-keyword-rankings` / `listing-hijackers` / `listing-claims` /
    //   `listing-compliance-precheck` / `listing-performance`），而白名单里的正则都是
    //   **锚定**的（`/^token(...)?$/`）⇒ 首 token 不同 ⇒ 互不覆盖、互不吃掉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-collect-box/capabilities               能力声明（未交付段 available=false + ownerModule）
    //   listing-collect-box/items                      采集箱条目（GET，真分页 + 筛选）/ 收录（POST）
    //   listing-collect-box/items/{uuid}               单个条目详情（GET）
    //   listing-collect-box/items/claim                批量认领（POST）
    //   listing-collect-box/items/tags                 批量打标（POST）
    //   listing-collect-box/items/remove               批量删除 / 移出采集箱（POST）
    //   listing-collect-box/groups                     分组统计（GET）
    //   listing-collect-box/candidates                 还没进箱的采集草稿（GET，真分页）
    //   listing-collect-box/tags                       标签词表（GET / POST）/ 删标签（DELETE /{uuid}）
    //   listing-collect-box/rules                      认领规则（GET / POST）/ 删规则（DELETE /{uuid}）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / groups / candidates / claim / tags / remove）
    //    必须排在 uuid 段**之前**判别，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-collect-box/undeclared-action`      —— 不存在的动作
    //   * `listing-collect-box/items/{uuid}/claim`      —— 认领是**批量**动作，勾选后一次提交
    //   * `listing-collect-box/items/{uuid}/tags`       —— 打标同样是批量动作
    //   * `listing-collect-box/items/{uuid}/remove`     —— 同上
    //   * `listing-collect-box/items/{uuid}/draft`      —— 草稿内容随条目详情一起返回，不单开一条
    //   * `listing-collect-box/claim`                   —— 认领走集合入口（/items/claim），不单开一条
    //   * `listing-collect-box/box`                     —— 本模块自己的前缀，不该再套一层
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-collect\-box(?:\/capabilities|\/groups|\/candidates|\/tags(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/items(?:\/claim|\/tags|\/remove|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)?$/,
  },
  {
    // 4.3.2.2 平台状态回流（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `listing-claims` 那一条**之后**（4.4.2.1 商品认领已交付），
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-status-events` 这个独立前缀而不是 `listings/{id}/status-events`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「平台状态回流」与「内容回写」是**两条独立通道**（一个把内部变更推出去、
    //   一个把平台状态收回来），状态机、限流与重试互不干涉 —— 挂在 listing 上会让
    //   "这个商品的状态"多出第九种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-status-events                            ✗ 刻意**不放行**：本模块没有裸路径资源
    //   listing-status-events/capabilities               能力声明（未交付段 available=false + ownerModule）
    //   listing-status-events/events                     状态变更记录（GET 真分页）/ 接收一条平台事件（POST）
    //   listing-status-events/events/{uuid}              单条状态变更记录（GET）
    //   listing-status-events/queue                      需处理队列（GET 真分页 + 按驳回原因筛选）
    //   listing-status-events/queue/{uuid}               单条队列项（GET）
    //   listing-status-events/queue/{uuid}/acknowledge   人工确认（POST）
    //   listing-status-events/queue/{uuid}/resolve       人工处理完成（POST）
    //   listing-status-events/conflicts                  冲突与告警（GET 真分页）
    //   listing-status-events/actions/republish          一键处理：按驳回原因批量修正重发（POST）
    //   listing-status-events/listings/{uuid}/history     某个商品的状态变更记录（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / events / queue / conflicts / actions/republish）
    //    **排在** uuid 段之前，否则会被 uuid 段吃掉半截；`listings/{uuid}/history` 这一支的
    //    首段是字面量 `listings`，与 uuid 段不会互相吃掉，但顺序保持"字面量在前"这一条文件的统一口径。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-status-events`                      —— 裸路径：本模块没有裸资源
    //   * `listing-status-events/{uuid}`                —— 单条事件在 `events/{uuid}` 下，不在裸 uuid 下
    //   * `listing-status-events/events/{uuid}/dispatch` —— 🔴 派发归 4.3.1.2 批量发布与 02 限流队列
    //   * `listing-status-events/events/{uuid}/report`  —— 4.3.2.1 的任务回执形状在**它自己的**前缀下
    //   * `listing-status-events/events/{uuid}/republish` —— 动作统一在 `actions/republish`，不按事件寻址
    //   * `listing-status-events/queue/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-status-events/actions/publish`       —— 4.3.1.2 批量发布
    //   * `listing-status-events/actions/preview`       —— 本模块没有"确认前先看看"这个状态
    //   * `listing-status-events/lifecycle`             —— 4.1.1.3 生命周期与下架（未交付）
    //   * `listing-status-events/publish-tasks`         —— 4.3.1.3 发布任务（未交付）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-status\-events(?:\/capabilities|\/events(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/queue(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?|\/conflicts|\/actions\/republish|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/history)$/,
  },
  {
    // 4.5.1.1 Listing 表现（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.3.2.2 `listing-status-events` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-claims` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-performance` 这个独立前缀而不是 `listings/{id}/performance`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段路径登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「平台表现」与「平台状态回流」本来就是**两条独立通道**（一个把状态收回来、
    //   一个把表现收回来），口径、限流与重试互不干涉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-performance                    表现快照列表（GET 真分页 + 商品/店铺/平台/时间范围筛选）
    //                                          / 接收平台表现数据同步结果（POST，批次级幂等）
    //   listing-performance/capabilities       能力声明（卷 13 / 卷 14 未交付 ⇒ available=false + ownerModule）
    //   listing-performance/{uuid}             单个 listing 的表现明细（GET）+ 时间范围对比
    //   listing-performance/{uuid}/trend       趋势序列（GET，日/周/月）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-performance/syncs`         —— 同步结果是入库回执，不单开一条查询形状
    //   * `listing-performance/{uuid}/orders`  —— 🔴 内部订单是 09 的事实，本模块不跨服务读它
    //   * `listing-performance/{uuid}/ads`     —— 🔴 14 广告关联投放（整卷未交付）
    //   * `listing-performance/{uuid}/product` —— 🔴 13 产品表现下钻（整卷未交付）
    //   * `listing-performance/{uuid}/sync`    —— 同步走 POST 集合入口，不按商品寻址
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-performance(?:\/capabilities|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/trend)?)?$/,
  },
  {
    // 4.5.1.2 关键词排名（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.5.1.1 `listing-performance` 那一条**之后** ——
    // 与本批另外三条车道的锚点互不重叠，git 能干净求并。
    //
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-keyword-rankings` 这个独立前缀而不是 `listings/{id}/keywords`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段路径登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「关键词排名」与「平台表现」「平台状态回流」本来就是**三条独立通道**
    //   （一个把名次收回来、一个把表现收回来、一个把状态收回来），
    //   口径、限流与重试互不干涉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-keyword-rankings                     关键词集合列表（GET 真分页）/ 新建关键词（POST）
    //   listing-keyword-rankings/capabilities        能力声明（1 工作台 / 14 广告 / 02 抓取调度器未交付
    //                                               ⇒ available=false + ownerModule）
    //   listing-keyword-rankings/filter-options      筛选面板选项（平台 / 店铺 / 站点）
    //   listing-keyword-rankings/crawls              接收排名抓取任务（POST，批次级幂等）
    //   listing-keyword-rankings/alerts              变化预警列表（GET 真分页）
    //   listing-keyword-rankings/alerts/{uuid}/acknowledge  人工确认一条预警（PUT）
    //   listing-keyword-rankings/report              关键词维度报表（GET，真 SQL 聚合）
    //   listing-keyword-rankings/{uuid}              单个关键词的排名明细（GET）/ 改阈值（PUT）/ 停用（DELETE）
    //   listing-keyword-rankings/{uuid}/history      排名历史（GET，真分页）
    //   listing-keyword-rankings/{uuid}/trend        趋势序列（GET，日/周/月）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / crawls / alerts）必须排在
    //    {uuid} 段**之前**判别，否则会被 {uuid} 通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-keyword-rankings/undeclared-action` —— 本模块**确定没有**的动作
    //   * `listing-keyword-rankings/{uuid}/crawls`      —— 抓取走集合入口，不按关键词寻址
    //   * `listing-keyword-rankings/{uuid}/alerts`     —— 预警是集合查询，不按关键词寻址
    //   * `listing-keyword-rankings/listing-hijackers` —— 🔴 4.5.2.1 跟卖监控（别的模块的职责）
    //   * `listing-keyword-rankings/{uuid}/ads`        —— 🔴 14 广告关联投放（整卷未交付）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-keyword\-rankings(?:\/capabilities|\/filter\-options|\/crawls|\/report|\/alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/history|\/trend)?)?$/,
  },
  {
    // 4.5.2.2 上新与变更监控（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `listing-keyword-rankings`（4.5.1.2 关键词排名）那一条**之后** ——
    // 与本批另外三条车道的锚点（`listing-bulk-collects` / `order-intercept` /
    // `fulfillment/consolidation`）两两相隔 ≥ 74 行，默认 3 行上下文窗口不会互相覆盖。
    //
    // 🔴 为什么用 `listing-changes` 这个独立前缀：
    //   * 4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 登记成「不放行」，
    //     在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单；
    //   * 而且「竞品变更监控」是**对外部目标的观测**（目标身份 = 平台 + ASIN，
    //     与自有商品模型零引用关系），口径、限流与权限都与「listing 本身」不同。
    //
    // 🔴 前缀不与既有任何 `listing-*` token 撞车：既有 token 全是
    //     `listing-collects` / `listing-bulk-collects` / `listing-keyword-rankings` /
    //     `listing-hijackers` / `listing-claims` / `listing-compliance-precheck` /
    //     `listing-performance` / `listing-lifecycles` / `listing-mappings` /
    //     `listing-migrations` / `listing-publish` / `listing-publish-tasks` /
    //     `listing-status-events` / `listing-content-writeback` / `listing-details` /
    //     `listing-editor` / `listing-templates`。
    //     既有正则**都是锚定的**（`/^token(...)?$`），所以只要**首 token 不同**即可；
    //     `listing-changes` 与上述任何一条的首 token 都不同（`changes` ≠ `collects` /
    //     `hijackers` / `claims` / `performance` / …），既不会被既有分支吃掉，
    //     也不会吃掉既有分支。
    //
    //   listing-changes/capabilities              能力声明（02 抓取调度器 / 13 分析 / 1 工作台
    //                                             未交付 ⇒ available=false + ownerModule）
    //   listing-changes/filter-options            筛选面板选项（平台 / 站点 / 枚举取值域）
    //   listing-changes/watches                   监控对象列表（GET 真分页 + 真筛选）/ 新建（POST）
    //   listing-changes/watches/{uuid}            改抓取频率与机会阈值（PUT）/ 删除（DELETE）
    //   listing-changes/crawls                    接收抓取任务结果（POST，批次级幂等）/ 批次列表（GET）
    //   listing-changes/events                    变更事件流（GET 真分页 + 真筛选）
    //   listing-changes/opportunities             市场机会列表（GET 真分页）
    //   listing-changes/opportunities/{uuid}/acknowledge  标记机会已阅（PUT）
    //   listing-changes/watches/{uuid}/history    观测历史（GET 真分页，历史对比的事实源）
    //   listing-changes/watches/{uuid}/comparison 历史对比（GET，服务端 SQL 聚合 + 现算比率）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-changes/undeclared-action`   —— 本模块**确定没有**的动作
    //   * `listing-changes/{uuid}/crawls`        —— 抓取走集合入口，不按监控对象寻址
    //   * `listing-changes/{uuid}/events`        —— 事件是集合查询，不按监控对象寻址
    //   * `listing-changes/{uuid}/opportunities` —— 机会是集合查询，不按监控对象寻址
    //   * `listing-changes/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-changes/actions/undercut`     —— 🔴 本模块**不提供**抢占 / 调价动作
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-changes(?:\/capabilities|\/filter\-options|\/crawls|\/events|\/opportunities(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?|\/watches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:history|comparison))?)?)?$/,
  },
  {
    // 4.4.2.2 批量搬迁（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'`（9.2.1.2 审单规则）那一条**之前** ——
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-migrations` 这个独立前缀而不是 `listings/{id}/migrations`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「批量搬迁」是**一次批量操作**，不是某个商品的状态 —— 挂在 listing 上
    //   会让"这个商品的状态"多出第八种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-migrations                        搬迁历史（GET 真分页）/ 执行搬迁（POST）
    //   listing-migrations/capabilities           能力声明（未交付段 available=false + ownerModule）
    //   listing-migrations/filter-options         筛选面板选项（源店铺 + 真实类目）
    //   listing-migrations/preview                预览：筛选命中什么 + 风险 + 标题差异化（POST，不写库）
    //   listing-migrations/duplicate-risks        「重复铺货风险」查询（GET 真分页）
    //   listing-migrations/migrated               「已搬迁」查询（GET 真分页）
    //   listing-migrations/{uuid}                 单个批次详情（GET）
    //   listing-migrations/{uuid}/items           批次的结果明细（含差异化留痕与风险依据）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / preview / duplicate-risks / migrated）
    //    **排在** uuid 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-migrations/{uuid}/publish`   —— 🔴 派发归 4.3.1.2 批量发布，本模块只入队
    //   * `listing-migrations/{uuid}/dispatch`  —— 同上，平台限流与派发都在 4.3.1.2 那条通道
    //   * `listing-migrations/{uuid}/re-run`    —— 幂等键变了就是**另一个批次**，没有"重跑"这个动作
    //   * `listing-migrations/{uuid}/abort`     —— 搬迁结果是只追加的事实，没有"作废"这个动作
    //   * `listing-migrations/{uuid}/diffs`     —— 差异化留痕随结果明细返回，不单开一条
    //   * `listing-migrations/prices|stocks`    —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-migrations/risks`            —— 风险查询的路径是 `duplicate-risks`
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-migrations(?:\/capabilities|\/filter\-options|\/preview|\/duplicate\-risks|\/migrated|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/items)?)?$/,
  },
  {
    // 4.3.1.1 一键刊登（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `listing-migrations`（4.4.2.2 批量搬迁）那一条**之后** ——
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `one-click-publish` 这个独立前缀：
    //   4.3.1.2 批量发布已经占了 `/channel/listing-publish`（batches / rate-limits /
    //   dispatch / pause / resume / cancel / reorder / retry）。两条通道的语义完全不同 ——
    //   一键刊登是「一个 SKU → N 个目标，每个目标各自成败、各自重试」，
    //   批量发布是「一个批次 → 一条队列，按平台 API 配额排队」。
    //   共用前缀会让「一个平台失败」被误写成「整批失败」，正是蓝图那句加粗要防的事。
    //   也不用 `listing-publish-jobs`：既有正则锚定 `^listing\-publish(...)?$`，
    //   虽不匹配，但极易被后来人误写成 `listing\-publish\-jobs` 而互相吃掉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   one-click-publish/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   one-click-publish/target-options          目标店铺 / 站点候选（只列已启用站点）
    //   one-click-publish/content-sources         内容来源候选（编辑器入队记录 + 刊登模板）
    //   one-click-publish/jobs                    作业列表（GET）+ 建作业（POST，幂等）
    //   one-click-publish/jobs/{uuid}             单个作业详情（GET）
    //   one-click-publish/jobs/{uuid}/targets     该作业的发布任务（每个目标一个）
    //   one-click-publish/jobs/{uuid}/steps       该作业的步骤时间线
    //   one-click-publish/jobs/{uuid}/dispatch    派发（逐目标独立，一个失败不影响其他）
    //   one-click-publish/targets/{uuid}          单个发布任务（GET）
    //   one-click-publish/targets/{uuid}/report   平台回执（成功 / 驳回 / 失败）
    //   one-click-publish/targets/{uuid}/retry    独立重试（只重试失败的那一个）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / target-options / content-sources）
    //    **排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸 `one-click-publish`            —— 后端没有「列出全部」的映射在裸路径上
    //   * `one-click-publish/jobs/{uuid}/cancel` —— 撤销未实现
    //   * `one-click-publish/jobs/{uuid}/timeline` —— 单次发布任务的时间线页归 4.3.1.3（未交付）
    //   * `one-click-publish/jobs/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `one-click-publish/{uuid}`        —— 没有「按 id 直接取作业」的裸形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^one\-click\-publish(?:\/capabilities|\/target\-options|\/content\-sources|\/jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:targets|steps|dispatch))?)?|\/targets\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:report|retry))?)$/,
  },
  {
    // 4.3.1.3 发布任务（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.3.1.1 `one-click-publish` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-claims` / `listing-status-events` 之后）互不重叠，
    // git 能干净求并。
    //
    // 🔴 为什么用 `listing-publish-tasks` 这个独立前缀：
    //   4.3.1.1 一键刊登占了 `/channel/one-click-publish`（一个 SKU → N 个目标，各自成败），
    //   4.3.1.2 批量发布占了 `/channel/listing-publish`（一个批次 → 一条队列，按平台配额排队），
    //   本模块是**单次发布任务的进度视图**（步骤时间线 + 错误明细 + 重试操作）。
    //   三者是三套独立的状态机，共用前缀会让「一个平台失败」被误写成「整批失败」。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-publish-tasks/capabilities               能力声明（阶段→步骤键映射 + 未交付段）
    //   listing-publish-tasks/tasks                      任务列表（GET，真分页 + 来源/阶段/平台/店铺筛选）
    //   listing-publish-tasks/tasks/{uuid}               任务详情（步骤时间线 + 错误明细 + 重试尝试）
    //   listing-publish-tasks/tasks/{uuid}/steps         步骤时间线（单独一条，便于只刷新时间线）
    //   listing-publish-tasks/tasks/{uuid}/retry         任务级重试（整条任务重来）
    //   listing-publish-tasks/tasks/{uuid}/steps/{key}/retry  步骤级重试
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / tasks）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ steps 段下的 stepKey 只放行 4.3.1.1 落的八个步骤键（PRICE / STOCK 是作业级步骤，
    //    也在同一段里 —— 它们出现在**作业级**时间线上，不是目标级）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸 `listing-publish-tasks`        —— 后端没有「列出全部」的映射在裸路径上
    //   * `listing-publish-tasks/jobs`      —— 作业归 4.3.1.1（one-click-publish）
    //   * `listing-publish-tasks/batches`   —— 批次归 4.3.1.2（listing-publish）
    //   * `listing-publish-tasks/tasks/{uuid}/cancel` —— 撤销未实现
    //   * `listing-publish-tasks/tasks/{uuid}/report` —— 平台回执归 4.3.1.1 / 4.3.2.2
    //   * `listing-publish-tasks/tasks/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-publish\-tasks(?:\/capabilities|\/tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:steps(?:\/(?:TEMPLATE|CONTENT|MAPPING|MEDIA|PRICE|STOCK|SUBMIT|RESULT)\/retry)?|retry))?)?)$/,
  },
  {
    // 6.5.1.1 采购对账（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'`（9.2.1.2 审单规则）**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ 根路径用**独立字面量** `purchase-reconciliations`：与既有 trade 分支的
    //    `order-review` / `order-intercept` / `purchase-plans` / `purchase-arrivals`
    //    首段互不吃，所以本条放在哪个位置都不会误伤它们。
    //    动手前已逐条核对本文件里**全部 100 条**既有正则对 `purchase-reconciliations`
    //    的命中数 = **0**（含 /capabilities /options /{uuid} /{uuid}/lines 等 7 个探针形状）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                能力声明（基数来源 / 未接线事实 / 边界）
    //   options                     下拉项（供应商 / 期间预设 / 币种 / 代扣代缴类型）
    //   （裸路径）                   对账单列表（GET，真分页 + 汇总）+ 发起对账（POST）
    //   {uuid}                      对账单详情（头 + 明细 + 差异 + 回执 + 留痕 + 基数口径）
    //   {uuid}/lines                明细行列表（GET，真分页 + 按币种分桶）
    //   {uuid}/deductions           登记代扣代缴费用（POST）
    //   {uuid}/send                 发给供应商确认（POST）
    //   {uuid}/confirmations        录入供应商确认回执（POST，逐行比对 → 逐条差异）
    //   {uuid}/discrepancies        差异清单（GET，真分页）+ 追加差异（POST）
    //   {uuid}/discrepancies/{uuid}/resolve  处理差异（挂起 / 解决）（POST）
    //   {uuid}/reopen               重开对账（追加差异）（POST）
    //   {uuid}/close                关闭对账单（POST）
    //   {uuid}/export               导出 CSV（GET，text/csv）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-reconciliations/{uuid}/payable` / `/payments` / `/confirm-payable`
    //     —— 蓝图加粗句「对账由采购发起、12 财务执行付款，避免采购擅自确认应付」：
    //     12 财务服务当前**不存在**，采购侧**没有**确认应付的端点。
    //   * `purchase-reconciliations/{uuid}/quality-deductions`
    //     —— 6.4.2.1 质检单与本模块同批并行开发，此刻不存在 ⇒ 没有这个映射。
    //   * `purchase-reconciliations/{uuid}/delete`
    //     —— 对账单是要拿去和供应商对质的凭据，没有删除形状。
    //   * `purchase-reconciliations/{uuid}/discrepancies/{uuid}/delete`
    //     —— 差异只能挂起（HELD）或解决（RESOLVED），没有删除形状。
    //   * `purchase-reconciliations/{uuid}/lines/{lineId}`
    //     —— 明细行在 `{uuid}/lines` 里，没有按行 id 的独立映射。
    //   * `purchase-reconciliations/import` / `/batch`
    //     —— 后端没有批量导入 / 批量发起对账的端点。
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / options）排在 {uuid} 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 100 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-reconciliations(?:\/capabilities|\/options|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/discrepancies\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/resolve|\/lines|\/deductions|\/send|\/confirmations|\/discrepancies|\/reopen|\/close|\/export)?)?$/,
  },
  {
    // 6.1.2.2 同款货源推荐（trade 服务，卷 06 采购与供应的 L3 锚点 6.1.2）。
    //
    // 蓝图：「当某 SKU 缺货或质量不达标时，推荐可替代的同款/相似货源：同款不同供应商、
    // 历史采购过该货的供应商、价格对比。输入：SKU 编码与图片；历史采购记录；货源库匹配结果。
    // 输出：候选货源列表（供应商、价格、交期、历史质量评分）+ 一键生成采购申请。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 6.2.2.2 需求合并与批量（既有条目）**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 已核对本文件里**全部既有正则**：`source-alternatives` **零命中**
    // （既有分支不会先把它吃掉）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   source-alternatives/capabilities            能力与上下游可用性（明说「图片相似度判定未交付」）
    //   source-alternatives/options                 词表（推荐理由 / 判定类型 / 批次状态 / 判定类型）
    //   source-alternatives/targets                 目标仓与法人下拉（warehouse 未接线时如实报「不可用」）
    //   source-alternatives/overview                概览（不受筛选与分页影响）
    //   source-alternatives/policy                  租户级规则与排序权重（GET 读 + PUT 改，权重和必须等于 1）
    //   source-alternatives/runs                    推荐批次清单（GET，真分页 + 真筛选）/ 新建推荐（POST）
    //   source-alternatives/runs/{uuid}             批次详情（批次头 + 候选 + 判定留痕 + 价格对比）
    //   source-alternatives/skus/{uuid}/candidates  实时候选预览（**不落库**，与落库口径同一套计算）
    //   source-alternatives/candidates/{uuid}/decision             人工判定（确认 / 否决，否决必填理由）
    //   source-alternatives/candidates/{uuid}/purchase-request     一键生成采购申请（写 6.2.2.1 的申请单）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / options / targets / overview / policy）排在
    //    {uuid} 段**之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 100 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    //
    // 🔴 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `source-alternatives` —— 后端没有「一条不存在的汇总列表接口」。
    //   * `source-alternatives/runs/{uuid}/candidates` —— 候选随批次详情一起返回，
    //     没有「按批次再列一次候选」的映射。
    //   * `source-alternatives/candidates/{uuid}` —— 候选信息随批次详情返回，
    //     没有「按候选 id 直达」的端点（那会让猜 id 的人直接读到证据快照）。
    //   * `source-alternatives/skus/{uuid}` —— 只有候选预览这一个形状。
    //   * `source-alternatives/purchases` / `/purchase-requests` —— 下游申请单由 6.2.2.1 的
    //     命名空间管，本模块只写不读。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^source\-alternatives(?:\/(?:capabilities|options|targets|overview|policy)|\/runs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/candidates|\/candidates\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:decision|purchase-request))$/,
  },
  {
    // 6.2.2.2 需求合并与批量（trade 服务，卷 06 采购与供应的 L3 锚点 6.2.2）。
    //
    // 蓝图：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与
    // 供应商、批量打印。」输入「多行需求；供应商选择（相同供应商才能合并）」。
    // 输出「合并后的采购单草稿；未合并行说明原因」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'`（6.5.1.1 采购对账）那一条**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 已用脚本逐条核对本文件里**全部既有正则**：`demand-merges/**` **零命中**
    // （既有分支不会先把它吃掉）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   demand-merges                        合并批次清单（GET，真分页 + 真筛选 + 真排序）/ 新建合并（POST）
    //   demand-merges/capabilities            能力声明（含「6.3.1.2 采购单未交付」与合并硬规则）
    //   demand-merges/options                 供应商 / 目标仓 / 法人下拉（跨服务只读，上游不可达返 503）
    //   demand-merges/print                   批量打印（POST）→ 每张草稿一张纸
    //   demand-merges/{uuid}                  批次详情（草稿 + 行 + 行来源 + 未合并行 + 批量修改留痕）
    //   demand-merges/{uuid}/drafts           草稿清单（GET，真分页 + 按供应商筛选）
    //   demand-merges/{uuid}/drafts/{uuid}    草稿详情（草稿 + 行 + 行来源）
    //   demand-merges/{uuid}/lines            草稿行清单（GET，真分页 + 真筛选）
    //   demand-merges/{uuid}/unmerged         未合并行清单（GET，真分页 + 按原因码筛选）
    //   demand-merges/{uuid}/quantity         批量改数量（POST，整体生效或整体拒绝）
    //   demand-merges/{uuid}/supplier         批量改供应商（POST，搬行后重新校验分组）
    //   demand-merges/{uuid}/finalize         定稿（POST，OPEN → FINALIZED，内容冻结）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / options / print）排在 {uuid} 段**之前** ——
    //    否则「capabilities」会被 uuid 分支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 100 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    //
    // 🔴 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `demand-merges/{uuid}/delete` —— 草稿是业务凭据（6.3.1.2 要读它去建真正的采购单），
    //     后端**没有**删除端点，DB 层也不提供 DELETE。
    //   * `demand-merges/{uuid}/convert` / `demand-merges/{uuid}/purchase-orders`
    //     —— 6.3.1.2 采购单**未交付**：本模块只产出**采购单草稿**，
    //     凭空放行「转采购单」会让界面显示「已下单」而其实没有。
    //   * `demand-merges/{uuid}/approve` —— 审批结论由 17 组织（identity）裁定，
    //     本模块没有本地批准端点。
    //   * `demand-merges/{uuid}/export` —— 本模块只提供「批量打印」（POST /print），
    //     没有 CSV 导出端点。
    //   * `demand-merges/{uuid}/lines/{uuid}` —— 单行信息随详情与行清单一起返回，
    //     没有「按 id 取单行」的映射。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^demand\-merges(?:\/(?:capabilities|options|print)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|lines|unmerged|quantity|supplier|finalize))?)?$/,
  },
  {
    // 9.2.1.2 审单规则（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：各车道的插入点被既有条目隔开，
    // git 能干净求并（与 9.3.1.2 合单 / 10.1.1.2 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（含 shop_code / 买家等级的缺口）
    //   rules                            规则列表（GET 真分页 + POST 新建）
    //   rules/{uuid}                     规则详情（GET 头+条件 / PUT 更新）
    //   rules/{uuid}/status              切换规则状态（DRAFT / ACTIVE / DISABLED）
    //   buyer-profiles                   买家画像列表（GET）+ 登记更新（PUT upsert）
    //   orders/{uuid}/review             对真实订单跑一次审单判定（POST，落规则生效记录）
    //   orders/{uuid}/trial              拿真实订单试跑规则（POST，标 DRY_RUN 不冒充结论）
    //   evaluations                      判定记录列表（GET 真分页）
    //   evaluations/{uuid}               判定详情（结论 + 完整命中链 + 订单事实）
    // 判别顺序：字面量子路径（capabilities / rules / buyer-profiles / evaluations）
    // **排在** {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    // ⚠️ orders/{uuid}/review|trial 两段都排在规则子资源之后 —— orders 这一层先由字面量
    //    `orders` 认领，再进 uuid 与动作段，不会与 rules 的分支互相干扰。
    // ⚠️ 刻意**没有** DELETE：规则一旦参与过判定，它就是「这单为什么被拦」的凭据；
    //    删掉只会停用（status=DISABLED），不会让历史命中链断掉。
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `order-review/orders`（裸路径）—— 后端没有「列出全部订单」的映射，
    //     本模块只对调用方给定的 order_id 判定
    //   * `order-review/rules/{uuid}/delete` —— 见上，规则不提供删除
    //   * `order-review/evaluations/{uuid}/events` —— 判定是 append-only 留痕，没有事件流
    //   * `order-review/bulk-review` —— 批量判定属 9.2.1.1 待审队列（未交付）
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-review\/(?:capabilities|buyer\-profiles|evaluations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:review|trial))$/,
  },
  {
    // 9.1.2.1 平台订单同步（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `order-review` 那一条**之后**：各车道的插入点被既有条目隔开，
    // git 能干净求并（与 9.2.1.2 / 11.3.2.1 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                能力声明（状态词表 / 下游缺口 / 金额与归一口径）
    //   webhook                     平台事件实时受理（POST）
    //   poll                        按来源 + 时间窗轮询补拉（POST）
    //   batches                     同步批次列表（GET，真分页 + 汇总）
    //   batches/{uuid}              批次详情（逐条受理结果 + 异常明细）
    //   orders                      平台单列表（GET，真分页 + 汇总）
    //   orders/{uuid}               平台单详情（投影 + 组件展开 + 受理记录）
    //   exceptions                  异常明细列表（GET，真分页 + 按错误码汇总）
    //   mappings                    映射副本列表（GET）+ 租户自维护登记（POST）
    //   mappings/sync               从 2.1.2.1（channel）同步映射副本（POST）
    //
    // ⚠️ 首段 `platform-orders` 是**独立字面量**：与既有 trade 分支的 `order-review`、
    //    `order-intercept`、`orders\/risk-validation` 互不吃（首段不同），
    //    所以本条放在哪个位置都不会误伤它们。
    // ⚠️ 判别顺序：字面量子路径（capabilities / webhook / poll / exceptions /
    //    mappings / batches / orders）必须排在 {uuid} 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    // ⚠️ 裸路径 `platform-orders` 本身**不放行**：后端没有这个映射，
    //    放行它会让界面以为「一条不存在的概览接口」可用。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `platform-orders/orders/{uuid}/review` —— 9.2 审单的批量判定不属本车道
    //   * `platform-orders/orders/{uuid}/fulfillment` —— 9.3 履约各动作不属本车道
    //   * `platform-orders/sales` —— 13 销量的形状不属本车道
    //   * `platform-orders/mappings/{uuid}` —— 映射副本只有列表与登记，没有按 id 寻址
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^platform\-orders\/(?:capabilities|webhook|poll|exceptions|mappings(?:\/sync)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 11.3.2.1 换货授权（trade 服务）。本条目同样是**新增一条 services[] 元素**，
    // 不往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.3.1.2 `refunds` 那条**之前**：前缀形状互不相交
    // （`aftersales/exchanges/…` vs `refunds/…`），谁先被匹配都互不吃。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：allowlist 不放行时浏览器侧一律 404，
    //    界面永远停在错误态而后端日志一次请求都没有 —— 放行与不通的区别只在浏览器侧。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   aftersales/exchanges                      列表（GET）+ 批准换货（POST）
    //   aftersales/exchanges/capabilities         能力声明 + 两条硬规则原文
    //   aftersales/exchanges/filters              可授权的换货售后单下拉
    //   aftersales/exchanges/{uuid}               详情（头 + 逐行 + 可执行动作）
    //   aftersales/exchanges/{uuid}/cancel        撤销换货（POST，释放真实预占）
    //
    // ⚠️ `capabilities` / `filters` 必须写在 id 形状**之前**：否则 `{uuid}` 那一支
    //   会把它们当 id 放行 —— 而 id 段是完整 UUID 形状，本来匹配不上，
    //   真正会出事的是顺序颠倒后字面量段被当成 id 段的一部分。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `aftersales/exchanges/{uuid}` 的 PUT / DELETE —— 换货授权行在 V162 里是
    //     append-only 的（运行角色的 UPDATE/DELETE 已被 REVOKE），改写等于毁掉
    //     「这笔换货当初预占了多少、约了谁、什么时候撤的」这条追溯链；
    //   * `aftersales/exchanges/{uuid}/outbound` —— 9.3.3.1 新出库接手未交付，
    //     capabilities 里 outboundHandoffDelivered=false 如实报出；
    //   * `aftersales/exchanges/{uuid}/return` —— 11.2 退回旧货未交付，
    //     capabilities 里 oldGoodsReturnDelivered=false 如实报出。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/exchanges(?:\/(?:capabilities|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/cancel)?)?$/,
  },
  {
    // 11.3.2.2 换货新单（trade 服务）。本条目同样是**新增一条 services[] 元素**，
    // 不往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.3.2.1 `exchanges` 那条**之前**：两条的前缀形状互不相交
    // （`aftersales/exchange-orders/…` vs `aftersales/exchanges/…`），
    // 谁先被匹配都互不吃 —— 注意 `exchange-orders` 与 `exchanges` 是**前缀相近但不相等**
    // 的两个字面量，正则里 `exchanges` 那一支并不会吃掉 `exchange-orders`。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：allowlist 不放行时浏览器侧一律 404，
    //    界面永远停在错误态而后端日志一次请求都没有 —— 放行与不通的区别只在浏览器侧。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   aftersales/exchange-orders                      列表（GET）+ 建换货新单（POST）
    //   aftersales/exchange-orders/capabilities         能力声明 + 硬规则原文 + 闭环口径
    //   aftersales/exchange-orders/filters              可建新单的换货授权下拉
    //   aftersales/exchange-orders/{uuid}               详情（头 + 逐行 + 时间线 + 没闭环原因）
    //   aftersales/exchange-orders/{uuid}/submit        提交进审单队列（POST）
    //   aftersales/exchange-orders/{uuid}/sync          同步闭环状态（POST，只读真实事实）
    //   aftersales/exchange-orders/{uuid}/cancel        撤销换货新单（POST）
    //
    // ⚠️ `capabilities` / `filters` 必须写在 id 形状**之前**：否则 `{uuid}` 那一支
    //   会把它们当 id 放行 —— 而 id 段是完整 UUID 形状，本来匹配不上，
    //   真正会出事的是顺序颠倒后字面量段被当成 id 段的一部分。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `aftersales/exchange-orders/{uuid}` 的 PUT / DELETE —— 换货关联与逐行在 V167 里
    //     是 append-only 的（运行角色的 UPDATE/DELETE 已被 REVOKE），改写等于毁掉
    //     「这笔换货当初换了什么、发出去了没有、谁撤的」这条追溯链；
    //   * `.../{uuid}/dispatch` —— **本模块刻意没有「手工标记已发货」的入口**：
    //     发货事实归 8 出库（读 9.4.1.1 的 shipment_record），放行它就等于给假交付开门；
    //   * `.../{uuid}/close` —— 同理，闭环由 sync 按真实事实推进，不接受手工指定状态。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/exchange-orders(?:\/(?:capabilities|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|sync|cancel))?)?$/,
  },
  {
    // 11.3.1.2 退款状态（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.2 售后单详情那条**之前**：本条与 11.1.1.2 的前缀形状
    // （`aftersales/…` vs `refunds/…`）互不相交，谁先被匹配都互不吃。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：后端 11 个端点已经交付、页面也已经存在，
    //    但 allowlist 不放行时浏览器这一侧一律 404 —— 界面会永远停在错误态，
    //   而后端日志里一次请求都没有（放行与不通的区别只在浏览器这一侧，极难定位）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   refunds                                    列表（GET）+ 登记待发起（POST，幂等键）
    //   refunds/capabilities                       能力声明 + 状态机逐条转换规则
    //   refunds/filters                            筛选项词表（来自真实数据 DISTINCT）
    //   refunds/discrepancies                      差异清单（GET，默认只列未裁定）
    //   refunds/{uuid}                             详情聚合（GET）
    //   refunds/{uuid}/dispatch                    派发 / 查证后重试（POST）
    //   refunds/{uuid}/verifications               登记「先查证交易」（POST）
    //   refunds/{uuid}/reports                     登记外部执行结果回报（POST）
    //   refunds/{uuid}/platform-sync               平台侧同步结果回报（POST）
    //   refunds/{uuid}/discrepancies               登记一条对账差异（POST）
    //   refunds/{uuid}/discrepancies/{uuid}/resolve 人工裁定差异（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `refunds/{uuid}` 的 PUT / DELETE —— 退款执行与其派发/查证/回报/差异都是留痕，
    //     改写或删除等于毁掉「这笔钱到底退没退、查过没有」的证据链
    //   * `refunds/{uuid}/refund-request` —— 11.3.1.1 退款申请（可退余额校验 + 审批）**未交付**，
    //     capabilities 里 refundRequestDelivered=false 如实报出
    //   * `refunds/{uuid}/returns` —— 11.2 退回旧货未交付
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^refunds(?:\/(?:capabilities|filters|discrepancies)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:dispatch|verifications|reports|platform-sync|discrepancies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/resolve)?)?)?)?$/,
  },
  {
    // 11.3.1.1 退款申请（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.2.1.1 退货授权单那条**之后**（本条紧贴同为 11.3.1 锚点的
    // 11.3.1.2 那条下方），与其他车道的新增元素被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `refunds/`**：那是 11.3.1.2 退款状态的命名空间（执行 / 查证 /
    // 派发 / 对账差异），本模块是**申请与审批**的那一半。两者的写入所有者不同 ——
    // 退款申请单归本模块，退款执行记录归 11.3.1.2 —— 混在一个命名空间里迟早有人
    // 代写对方那张表，而「这笔钱退没退」会出现两个来源各说各话。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条一律以
    // `refund-applications/` 开头，与 `refunds/` 的形状互不相交（`refunds` 后面必须
    // 是 `/` 或结尾，`refund-applications` 的第 8 个字符是 `-`），谁先被匹配都不误吃。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                    能力与上下游可用性（**明说 12 财务未交付**、
    //                                  可退余额五桶口径、fail-closed 审批规则）
    //   refundable-balance?caseId=      可退余额校验（GET，五个占用桶服务端实时查）
    //   approval-policy?currency=       审批规则读取 / 维护（GET + PUT）
    //   filters                         筛选项词表（来自真实数据 DISTINCT）
    //   （裸）                          列表（GET，真分页 + 多维筛选 + 排序白名单）
    //   （裸）                          发起退款申请（POST，**建草稿不是提交**）
    //   {uuid}                          详情聚合（GET，头 + 余额 + 规则 + 执行状态 + 留痕 + 动作）
    //   {uuid}                          改草稿要素（PUT，**只有 DRAFT 可改**）
    //   {uuid}/submit                   提交审批（POST，**这一刻重新校验可退余额**）
    //   {uuid}/decide                   审批：同意 / 驳回（POST，**落单签还是双签由规则决定**）
    //   {uuid}/dispatch                 交财务执行（POST，必须挂一条已存在的执行记录）
    //   {uuid}/execution-outcome        登记执行结果（POST，成功 / 失败）
    //   {uuid}/cancel                   人工撤销（POST，钱还没出去时）
    //
    // ⚠️ 判别顺序：三个**字面量子路径**（capabilities / refundable-balance /
    //   approval-policy / filters）**排在** uuid 段之前 —— 否则 `filters`
    //   会被当成 uuid 段匹配掉（uuid 段只认完整 UUID 形状，`filters` 不是，
    //   但仍然把字面量写在前面才不必依赖这一层推理）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //   后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 退款审批是资金凭据，删掉一行等于抹掉「这笔钱为什么退、
    //     谁批的」这段证据。
    //   * `{uuid}/verify` —— 「先查证交易再重试」属 **11.3.1.2**。放行它会让界面显示成
    //     本模块能查证，而真相是执行记录定论与否由 11.3.1.2 说了算，
    //     查证不了却显示按钮 = 点了必然失败。
    //   * `{uuid}/pay` —— 真实付款执行属 **12.2.2.2，未交付**。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^refund-applications(?:\/(?:capabilities|refundable-balance|approval-policy|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|decide|dispatch|execution-outcome|cancel))?)?$/,
  },
  {
    // 11.2.1.1 退货授权单（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.3.1.2（refunds）那条**之后**、11.1.1.2 那条**之前**：
    // 与其他车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条与相邻两条的前缀形状互不相交
    // （本条一律以 `rma-authorizations/` 开头），谁先谁后都不误吃。
    //
    // 命名空间**独立于 `/trade/aftersales`**：退货授权单是另一张单据（行级授权数量 +
    // 退货地址 + 回寄物流 + 寄回时限），不是售后单的另一个视图。塞进
    // `aftersales/cases/{uuid}/returns` 会让人以为它是 11.1.1.2 详情的一个子资源，
    // 而本模块的下游是 11.2.1.2 退货指令，不是售后详情页。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                 能力与上下游可用性（**明说 11.2.1.2 / 11.2.2.x /
    //                               11.2.3.x 全部未交付，「授权 ≠ 已收到」**）
    //   returnable-quantities        原订单行可退量校验（GET，三个来源服务端实时查）
    //   （裸）                       列表（GET，真分页 + 多维筛选 + 排序白名单）
    //   （裸）                       登记退货授权单（POST，建草稿，**不是审批**）
    //   {uuid}                       详情聚合（GET，头 + 行 + 审批留痕 + 可执行动作）
    //   {uuid}                       改草稿要素（PUT，**只有 DRAFT 可改**）
    //   {uuid}/decide                审批：授权 / 部分授权 / 拒绝（POST）
    //   {uuid}/return-logistics      登记买家回寄物流（POST，三件套同进同出）
    //   {uuid}/cancel                人工撤销（POST，可退量立刻还给订单行）
    //   expire-overdue               超时未寄自动关闭（POST）
    //
    // ⚠️ 判别顺序：两个**字面量子路径**（capabilities / returnable-quantities /
    //   expire-overdue）**排在** uuid 段之前 —— 否则 `returnable-quantities`
    //   会被当成 uuid 段匹配掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 退货授权是审批单据，删掉一行等于抹掉纠纷证据
    //   * `{uuid}/receive` —— 登记退货实收属 **11.2.2.1，未交付**。
    //     蓝图加粗句「授权不等于已收到」：货可能还在路上，也可能根本没寄回；
    //     放行一个「标记已收到」会让界面显示成货已到手并开始做入库分录，
    //     而退回商品**默认进待检**，直接当已收货处理会把残次品当新品卖出去。
    //   * `{uuid}/instruction` —— 退货指引与标签属 **11.2.1.2，未交付**
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^rma-authorizations\/(?:capabilities|returnable-quantities|expire-overdue|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:decide|return-logistics|cancel))?)$|^rma-authorizations$/,
  },
  {
    // 11.2.1.2 退货指令下发（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.2.1.1（rma-authorizations）那条**之后**、11.1.1.2 那条**之前**：
    // 与相邻两条的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条与相邻两条的前缀形状互不相交
    // （本条一律以 `rma-instructions/` 开头），谁先谁后都不误吃。
    //
    // 命名空间**独立于 `/trade/rma-authorizations`**：本模块是 11.2.1.1 的**下游**
    // （指引 / 标签 / 轨迹），不是退货授权单的另一个视图。塞进
    // `rma-authorizations/{uuid}/instructions` 会让两个模块共用一个前缀，
    // 而它们各自的写入所有权与状态机是分开的。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                能力与上下游可用性（**明说 10 物流 / 15 客服 /
    //                                11.2.2.1 / 11.2.3.x 全部未交付，「已下发 ≠ 已寄回」**）
    //   （裸）                      列表（GET，真分页 + 多维筛选 + 按承运商/运单号反查 + 排序白名单）
    //   （裸）                      登记退货指引草稿（POST，挂在已授权的授权单下）
    //   {uuid}                      详情聚合（GET，头 + 行 + 留痕 + 轨迹 + 当前标签 + 动作）
    //   {uuid}                      改草稿要素（PUT，**只有 DRAFT 可改**：下发即冻结）
    //   {uuid}/issue                下发指引（POST，内容在这一刻冻结）
    //   {uuid}/acknowledge          登记买家已确认收到（POST，**主动回执**，不是我们推测）
    //   {uuid}/cancel               人工撤销（POST）
    //   expire-overdue              寄回时限到期自动失效（POST，**只判时限，不判货有没有回来**）
    //   {uuid}/label                生成退货标签（POST，真字节 + SHA-256 校验和）
    //   {uuid}/label                取标签文件内容（GET，服务端重算校验和比对）
    //   {uuid}/label/deliver        交付标签（POST，**生成 ≠ 交付**）
    //   {uuid}/label/void           作废标签（POST，理由必填，作废后可重出）
    //   {uuid}/tracking             回寄轨迹时间线（GET，追加式事件 + 按运单号分组的当前状态）
    //   {uuid}/tracking             登记一条轨迹（POST，追加式；承运商重复推送去重）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / expire-overdue）**排在** uuid 段之前 ——
    //   否则 `capabilities` 会被当成 uuid 段匹配掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ `label` 后面**只接** `deliver` 与 `void` 两个字面量段：多一层仍然 404 ——
    //   放行一个后端不存在的形状只会让界面以为已交付。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 指引是发给买家的一份文件，删掉一行等于抹掉纠纷证据
    //   * `{uuid}/receive` —— 登记退货实收属 **11.2.2.1，未交付**。
    //     蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」：放行「标记已收到」
    //     会让界面显示成货已到手并开始做入库分录，而退回商品**默认进待检**，
    //     直接当已收货处理会把残次品当新品卖出去。
    //   * `{uuid}/waybill` —— 退货运单属 **10 物流，未交付**。本模块出的模板标签
    //     **不含承运商运单号**，带了就等于声称已对接面单接口。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^rma-instructions\/(?:capabilities|expire-overdue|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:issue|acknowledge|cancel|tracking|label(?:\/(?:deliver|void))?))?)$|^rma-instructions$/,
  },
  {
    // 11.2.2.1 退货入库（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.1.2 `rma-instructions` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`return-receipts/` vs `rma-instructions/` vs `aftersales/cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `return-receipts`                            集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                               / 登记实收草稿（POST）
    //   `return-receipts/capabilities`               能力与上游可用性声明（GET）
    //   `return-receipts/receivable`                 剩余可收量（GET，扫码前先看还能收几件）
    //   `return-receipts/{uuid}`                     详情（GET） / 改草稿（PUT，仅 DRAFT）
    //   `return-receipts/{uuid}/post`                入库（POST）—— **生成待检分录，不是可售**
    //   `return-receipts/{uuid}/cancel`              撤销（POST，仅 DRAFT，已入库不可撤销）
    //   `return-receipts/{uuid}/photos`              追加拍照留证（POST，仅 DRAFT）
    //
    //   * **刻意不放行** `{uuid}/resell` / `{uuid}/inspect`：处置与质检属
    //     11.2.3.2 / 11.2.3.1，**未交付**。放行它们会让界面显示成「这批货可以直接转可售」，
    //     而蓝图加粗句「退回商品**默认进待检状态**，直接进可售会把残次品当新品卖出去」正是
    //     这条规则要防的事。
    //   * **刻意不放行** `{uuid}/receive`：11.2.1.2 的指引下发 ≠ 货已到手，
    //     登记实收只能走本模块的草稿 → 入库两段式。
    //   * **刻意没有** `{uuid}` 上的 DELETE：行与照片是纠纷证据（运行角色 UPDATE/DELETE
    //     已在 DB 层收回），放行删除形状等于让「这批货收了几件、判定成什么样」不可追责。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^return-receipts(?:\/(?:capabilities|receivable|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:post|cancel|photos))?))?$/,
  },
  {
    // 15.1.2.1 邮件收发（crm 服务，卷 15 的 L3 锚点 15.1.2 的买家邮件子域）。
    //
    // 蓝图：「管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：收发、附件、模板、
    // 与站内信的双向同步或标记为同源。输入：邮件同步；发送请求。输出：邮件列表与详情 + 发送记录。
    // 关系：上游 = 2 同步；下游 = 15.1.1.2 发送。
    // **部分平台要求邮件与站内信在同一会话处理**，分开展示会导致重复回复买家。」
    //
    // 本条目是**新增一条独立的 services[] 元素**，不是往既有 crm 分支的 alternation 里并分支 ——
    // 既有三条 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）、
    // `^review-requests...$`（15.2.2.1 索评）与 `^review-rules...$`（15.2.2.2 索评规则），
    // 首 token 都与 `buyer-emails` 不同，三条互不吃掉；同批并行的 15.1.1.1 消息收件箱
    // 也各自**新增独立元素**（首 token `message-inbox`），本车道不碰它那条。
    //
    // ---- 插入锚点选在 **11.2.2.1 `return-receipts` 之后 / 11.2.2.2 `receipt-allocations` 之前** ----
    //   这一段是 **trade 服务的 11 卷退货收件**区，与本批另外三条车道的落点
    //   （crm/ 知识库 · 索评 · 索评规则那条带，以及 channel 的广告 / 达人区）**相距 1700 行以上**，
    //   并入时 git 能干净求并。既有条目一字未动。
    //
    // ---- 首 token「buyer-emails」的零命中核对结论 ----
    //   本文件既有 **143 条** services[] 正则里**没有任何一条**含 `buyer-emails` 这个串
    //   （全文出现次数 = 0）。带 `buyer` 字样的只有两条，且首 token 与本模块完全不同：
    //     * `^order-review\/...`（9.2.1.2 trade 订单审核）—— 它内部的 `buyer-profiles` 是子形状；
    //     * `^purchase-order-approvals(?:\/...|members(?:\/(?:buyers|approvers))`（trade 采购订单审批）
    //       —— 它的 `buyers` 是 `members` 下的子形状。
    //   两者都在 `prefix: 'trade/'` 下，首 token 与 `buyer-emails` 不同 ⇒ **互不吃掉**。
    //   既有正则都是**锚定**的（`^token(...)?$`），而本文件的 `resolve()` 先按
    //   `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/' 的元素
    //   才有机会吃到 `crm/buyer-emails/...`。
    //
    // 形状逐条列出：
    //   buyer-emails                          邮件集合读（GET，真分页 + 真筛选）/ 新建（POST，登记收到的邮件）
    //   buyer-emails/capabilities             能力声明（逐维 available + ownerModule + impact）
    //   buyer-emails/shops                    店铺目录（来自上游 channel 的真实清单 + 每平台接入状态）
    //   buyer-emails/sync                     平台买家邮件同步（POST）—— 🔴 适配器未接入，端点恒 409
    //   buyer-emails/templates                邮件模板集合（GET 真分页真筛选 / POST 新建）
    //   buyer-emails/templates/{uuid}         单条模板（GET 详情 / PUT 更新 / DELETE 删除）
    //   buyer-emails/{uuid}                   单封邮件详情（GET）
    //   buyer-emails/{uuid}/conversation      会话视图（GET，同会话的收 + 发两行）
    //   buyer-emails/{uuid}/attachments       登记附件元数据（POST）
    //   buyer-emails/{uuid}/send-records      发送记录（GET 列表 / POST 登记）
    //
    // 判别顺序：**字面量子路径（capabilities / shops / sync / templates）排在 {uuid} 段之前** ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //   `templates` 那一支**自带**可选的 {uuid} 段（`templates/{uuid}` 是真实形状），
    //   它排在裸 {uuid} 支之前，两支首字符不同（`t` vs 十六进制串），互不吃掉。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // ⚠️ **裸路径 `buyer-emails` 是放行的**：本模块的集合读与新建都在裸路径上，
    //   与同服务的 15.2.2.1 `review-requests` 同一形状。蓝图输出句点名「邮件列表与详情」，
    //   列表必须有集合路径 —— 把它挡掉只会让页面 404（白名单漏登记的真实后果）。
    //   与之相对，字面量段（capabilities / shops / sync / templates）**刻意不接** {uuid} 段，
    //   它们是能力声明、目录、聚合与配置，**不按 id 寻址**。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `buyer-emails/{uuid}/delete` —— 邮件是会话与「有没有重复回复」的凭据，不可删；
    //   * `buyer-emails/{uuid}/replies` —— 回复是**发送记录**（send-records），不是另一个资源；
    //   * `buyer-emails/{uuid}/tags` / `/attachments/{uuid}` / `/send-records/{uuid}` ——
    //     后端没有「按单条附件 / 单条发送记录寻址」的映射；
    //   * `buyer-emails/{uuid}/inbox` —— 站内信原文属 **15.1.1.1，未交付**，
    //     放行一个站内信入口等于让界面以为「邮件与站内信已经合并」；
    //   * `buyer-emails/{uuid}/rules` —— 邮件规则属 **15.2.2.2，未交付**；
    //   * `buyer-emails/templates/{uuid}/publish` —— 模板没有「发布」这个动作，只有启停；
    //   * 任何 `capabilities/{uuid}` / `shops/{uuid}` / `sync/{uuid}` —— 字面量段不接 id 段；
    //   * 非 UUID 段（如 `buyer-emails/not-a-uuid`）必须 404，不能被宽松分支吃掉。
    //   * PUT / DELETE 之外的第三层子路径 —— 后端只有上面列出的那些。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^buyer-emails(?:\/(?:capabilities|shops|sync)|\/templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:attachments|conversation|send-records))?)?$/,
  },
  {
    // 15.1.2.2 邮件规则（crm 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `buyer-emails`（15.1.2.1 邮件收发）等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `^buyer-emails...$`（15.1.2.1 邮件收发）那条元素之后** ----
    //   本车道与 15.1.2.1 是同卷兄弟（15.1.2 邮件），锚在它后面语义上最自然；
    //   同批另三条车道（15.4.1.1 / 15.5.1.1 / 16.1.2.1）的锚点不在这里 ⇒ git 能干净求并。
    //
    // ---- 首 token「email-rules」的零命中核对结论 ----
    //   本文件既有 150+ 条 services[] 正则里**没有任何一条**含 `email-rules` 这个串
    //   （既有的是 `buyer-emails` / `customer-tags` / `message-classifications` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   email-rules/capabilities                  能力声明（逐维 available + ownerModule + impact）
    //   email-rules/options                       词表（动作 / 命中方式 / 重要类别 / 工单标签码）
    //   email-rules/rules                         规则集合读（GET，真分页 + 真筛选）/ 新建（POST）
    //   email-rules/rules/{uuid}                  单条规则（GET / PUT 修改 / DELETE 删除）
    //   email-rules/rules/{uuid}/preview          试算（POST，**不写命中记录**）
    //   email-rules/tags                          工单标签字典（GET 真分页 / POST 新建）
    //   email-rules/tags/{uuid}                   单个标签（GET / PUT / DELETE）
    //   email-rules/emails/{uuid}/tags            某封邮件挂的工单标签（GET / POST 打标）
    //   email-rules/emails/{uuid}/evaluate        评估一封邮件（POST，写命中记录）
    //   email-rules/evaluate-batch                批量评估（POST，逐条给出结论与原因）
    //   email-rules/hits                          命中记录集合读（GET，真分页 + 真筛选）
    //   email-rules/hits/{uuid}                   单条命中详情（GET，含命中依据与规则快照）
    //   email-rules/assignments                   自动分配结果集合读（GET，真分页 + 真筛选）
    //   email-rules/assignments/{uuid}            单条分配结果（GET）
    //   email-rules/assignments/{uuid}/cancel     取消分配结果（POST，理由必填）
    //   email-rules/statistics                    规则命中统计 + 动作分布 + 静默被拒数（GET）
    //
    // 判别顺序：字面量子路径（capabilities / options / statistics / evaluate-batch /
    // rules / tags / emails / hits / assignments）排在 {uuid} 段之前 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `email-rules` —— 本模块的集合读都在子路径上
    //     （`rules` / `tags` / `hits` / `assignments`），裸路径没有后端映射；
    //   * `email-rules/rules/{uuid}/disable` —— 启用/停用走同一个 PUT 字段，
    //     不另开一条动作路径；
    //   * `email-rules/hits/{uuid}/delete` —— 命中记录**只追加**，DB 层拒绝改删；
    //   * `email-rules/hits/{uuid}/edit` —— 同上；
    //   * `email-rules/assignments/{uuid}/dispatch` —— **15.4.1.1 工单分配未交付**，
    //     放行它等于让界面以为「已经转工单了」；
    //   * `email-rules/emails/{uuid}/auto-reply` —— **15.5.1 自动回复未交付**，
    //     本模块只留痕意图，不真的发出；
    //   * `email-rules/tickets` / `email-rules/sync` —— 工单与平台同步都不在本模块；
    //   * 任何 `capabilities/{uuid}` —— 能力声明**不按 id 寻址**；
    //   * 非 UUID 段（如 `email-rules/rules/not-a-uuid`）必须 404，不能被宽松分支吃掉。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `email-rules`。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^email\-rules(?:\/(?:capabilities|options|statistics|evaluate\-batch|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/preview)?)?|tags(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|emails\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:tags|evaluate)|hits(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|assignments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/cancel)?)?))$/,
  },
  {
    // 11.2.2.2 实收分配（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 11.2.3.1 质检与判定（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.2.1 `return-receipts` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`receipt-allocations/` vs `return-receipts/` vs `cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `receipt-allocations`                        集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                               / 按收货事件分配（POST，**重复提交幂等**）
    //   `receipt-allocations/capabilities`           能力与硬规则声明（GET）
    //   `receipt-allocations/order-returns`          订单行已退数量（GET，9 订单的取数面）
    //   `receipt-allocations/{uuid}`                 详情（GET）
    //   `receipt-allocations/{uuid}/reconcile`       对账：超收/少收/超期结转（POST）
    //   `receipt-allocations/{uuid}/reverse`         撤销分配（POST，仅 ALLOCATED，必须写理由）
    //
    //   * **刻意不放行** 任何「重复分配 / 强制再记一次已退数量」的形状：蓝图加粗句
    //     「**按收货事件去重分配，同一批退货不重复记已收数量**」。已退数量是可退余额的
    //     事实来源，多记一次就是资损；传输层**没有** force / allowDuplicate 开关。
    //   * **刻意不放行** `{uuid}` 上的 DELETE：分配是资金动作，撤销只能走
    //     `/reverse`（写理由、走版本校验、由 DB 触发器回冲已退数量）。
    //   * **刻意不放行** 任何「把差额标记为已解决」的形状：处置结论只有
    //     「待人工核实 / 按实际退回认可」—— 超收多退的钱要追回还是认了是财务决定，
    //     编一个「已解决」会让财务侧以为这笔钱已经结清了。
    // 三条的前缀形状互不相交（`return-quality/` vs `return-receipts/` vs `aftersales/cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-receipts`**：本模块的产物是**另一种单据**
    // （质检判定 = 一个结论 + 一张交给下游的指令），不是实收单的另一个视图。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `return-quality/capabilities`               能力与上下游可用性声明（GET）
    //   `return-quality/categories`                类目集合读（GET） / 建类目（POST）
    //   `return-quality/categories/{uuid}`         改类目名称与启停（PUT，**刻意不给改编码的入口**）
    //   `return-quality/categories/{uuid}/skus`    把 SKU 归到质检类目（POST）
    //   `return-quality/templates`                 模板集合读（GET） / 建模板草稿（POST）
    //   `return-quality/templates/{uuid}`          模板详情（GET） / 改草稿（PUT，**仅 DRAFT**）
    //   `return-quality/templates/{uuid}/activate` 启用模板（POST）
    //   `return-quality/templates/{uuid}/retire`   停用模板（POST，**刻意用 POST 不是 DELETE**：
    //                                               停用是口径变更，历史质检单要能复现当时的口径）
    //   `return-quality/queue`                     待检队列（GET，**质检员上班第一屏**）
    //   `return-quality/verdict-preview`          判定预览（GET，提交前就知道能判成什么）
    //   `return-quality/inspections`               质检单集合读（GET） / 开单（POST）
    //   `return-quality/inspections/{uuid}`        详情（GET） / 录逐项结果（PUT，**仅 DRAFT**）
    //   `return-quality/inspections/{uuid}/conclude` 判定（POST，结论 + 理由 → **残值由公式算出**）
    //   `return-quality/inspections/{uuid}/cancel`   撤销（POST，仅 DRAFT，已判定是终态）
    //
    // ⚠️ 四段 alternation 各自**整段锚定到 $**，所以不存在「字面量子路径被 uuid 段吃掉」
    //   的顺序陷阱（capabilities / queue 这些字面量段与 UUID 形状互不相交）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/resell` / `{uuid}/dispose` / `{uuid}/scrap` —— 处置属 **11.2.3.2，未交付**。
    //     蓝图加粗句「**报废是不可逆的**，需审批与原因，不能由客服随手处理」正是这条规则的落点：
    //     放行它等于把不可逆动作做成一个按钮。
    //   * `{uuid}/salvage-posting` —— 残值入账属 **12 财务，未交付**，且是**独立数据库**，
    //     跨服务写账被 AGENTS.md 模块边界纪律明令禁止。
    //   * `{uuid}/claim` —— 供应商索赔属 **11.2.3.3，未交付**。本模块只提供举证材料。
    //   * `{uuid}/verdict` —— 结论**终态不可逆**：判定只走 `conclude`，判错了只能作废重判。
    //     再开一个「直接改结论」的形状，会让「这批货到底判成什么」出现两个答案。
    //   * 任何 **DELETE** —— 质检结论与逐项结果是要拿去索赔的举证材料
    //     （运行角色对该表的 UPDATE/DELETE 已在 DB 层收回）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^receipt-allocations(?:\/(?:capabilities|order-returns|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:reconcile|reverse))?))?$/,
  },
  {
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^return-quality\/(?:capabilities|categories|templates|queue|verdict-preview|inspections)$|^return-quality\/categories\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?$|^return-quality\/templates\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:activate|retire))?$|^return-quality\/inspections\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:conclude|cancel))?$/,
  },
  {
    // 11.2.3.2 转可售/残次/报废（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.3.1 `return-quality` 那条**之后**、11.1.2.2 那条**之前**：
    // 三条的前缀形状互不相交（`return-dispositions/` vs `return-quality/` vs
    // `product-attribution/`），谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-quality`**：11.2.3.1 判的是「这批货怎么样」，
    // 11.2.3.2 做的是「按结论把这批货转入什么库存状态」—— 一个出结论，一个出单据。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `return-dispositions`                          处置单集合读（GET，真分页 + 多维筛选 + 汇总）
    //   `return-dispositions/capabilities`             能力与边界声明（GET）
    //   `return-dispositions/instructions`             待处置的质量状态指令（GET）
    //   `return-dispositions/options`                  候选来源库位 + 候选目标库位（GET）
    //   `return-dispositions/{uuid}`                   单条处置单详情（GET）
    //   `return-dispositions`                          建处置决定单（POST，**草稿**）
    //   `return-dispositions/{uuid}`                   改草稿要素（PUT，**仅 DRAFT**）
    //   `return-dispositions/{uuid}/submit`            提交待审（POST）
    //   `return-dispositions/{uuid}/approve`           批准（POST，**独立权限码**）
    //   `return-dispositions/{uuid}/reject`            驳回（POST）
    //   `return-dispositions/{uuid}/cancel`            撤销（POST，仅未执行）
    //   `return-dispositions/{uuid}/execute`           执行库存状态变更（POST）
    //
    // ⚠️ **刻意不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `return-dispositions/{uuid}/resell` / `/defective` / `/scrap`
    //     —— **报废不是一个按钮**：蓝图加粗句「报废是不可逆的，需审批与原因，
    //     不能由客服随手处理」。放行这些形状等于把不可逆动作做成随手点一下。
    //   * 任何 **DELETE** —— 处置决定是「谁批的、为什么报」的举证材料
    //     （运行角色对留痕表的 UPDATE/DELETE 已在 DB 层收回）。
    //   * `return-dispositions/{uuid}/salvage-posting` —— 残值入账属 **12 财务，未交付**。
    //   * `return-dispositions/{uuid}/claim` —— 供应商索赔属 **11.2.3.3**，不在本模块。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    // 一段整段锚定到 $，所以不存在「字面量子路径被 uuid 段吃掉」的顺序陷阱
    // （capabilities / instructions / options 与 UUID 形状互不相交）。
    pattern:
      /^return\-dispositions(?:\/(?:capabilities|instructions|options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|approve|reject|cancel|execute))?))?$/,
  },
  {
    // 11.1.2.2 商品问题归因（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.3.1 `return-quality` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`product-attribution/` vs `return-quality/` vs `cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-quality`**：本模块判的是「这事该谁负责」，
    // 11.2.3.1 判的是「这批货怎么样」—— 两者的输出句不同，形状就不能混。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `product-attribution/capabilities`                   能力声明与边界（GET）
    //   `product-attribution/attributions`                   集合读（GET）+ 建单（POST）
    //   `product-attribution/attributions/{uuid}`            详情（GET）
    //   `product-attribution/attributions/{uuid}/photos`      登记 / 替换照片（PUT，整单替换）
    //   `product-attribution/attributions/{uuid}/photos/compare` 照片比对（POST，**内容指纹**口径）
    //   `product-attribution/attributions/{uuid}/attribute`   出归因结论（POST）
    //   `product-attribution/attributions/{uuid}/supersede`  改判留痕（POST，终态不可逆）
    //   `product-attribution/attributions/{uuid}/lot-flag/refresh` 刷新批次质量标记（POST）
    //   `product-attribution/lot-flags`                      批次质量标记列表（GET）
    //   `product-attribution/lot-flags/by-lot`               单批次标记（GET）
    //
    // ⚠️ 四段 alternation 各自**整段锚定到 $**，所以不存在「字面量子路径被 uuid 段吃掉」
    //   的顺序陷阱。id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），
    //   不是 `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/claim` —— 供应商索赔属 **6.4.2.2，未交付**。归因结论与批次标记是索赔的
    //     **举证材料**，但索赔单的创建、协商记录与赔付处理都不在本模块。
    //   * `{uuid}/rating` —— 供应商评级属 **6.1.1.3，未交付**。
    //   * `{uuid}/similarity` —— **图像相似度**：本仓没有图像库、没有训练好的模型、
    //     也没有第三方视觉服务的接入点。放行它等于渲染一个冒充图像识别能力的控件。
    //   * `{uuid}/verdict` / `PUT {uuid}` —— 归因结论**终态不可逆**：改判只走 `/supersede`
    //     留痕另起一版。再开一个「直接改结论」的形状，会让「这批货到底归给谁」出现两个答案。
    //   * 任何 **DELETE** —— 归因结论、照片与比对是要拿去索赔的举证材料
    //     （留痕表的 UPDATE/DELETE 已在 DB 层收回）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^product-attribution\/(?:capabilities|attributions|lot-flags|lot-flags\/by-lot)$|^product-attribution\/attributions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:photos|photos\/compare|attribute|supersede|lot-flag\/refresh))?$/,
  },
  {
    // 11.2.3.3 供应商索赔（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时
    // 静默删掉同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.1.2.2 `product-attribution` 那条**之后**、11.5.2.1 `quality-analysis` 那条**之前**：
    // 三条的前缀形状互不相交（`supplier-claims/` vs `product-attribution/` vs `quality-analysis/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-quality` 与 `/trade/product-attribution`**：
    // 本模块的产物是**另一种单据**（一次对外的商务主张 + 一串协商 + 一笔待确认的赔付），
    // 不是那两个模块的另一个视图。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `supplier-claims`                                    集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                                        / 发起索赔（POST，建单恒为草稿）
    //   `supplier-claims/capabilities`                       能力与边界声明（GET）
    //   `supplier-claims/candidates`                         可索赔候选（GET，**发起索赔的唯一入口素材**）
    //   `supplier-claims/{uuid}`                             详情（GET）
    //   `supplier-claims/{uuid}/items`                      加索赔明细（POST）
    //   `supplier-claims/{uuid}/items/{itemUuid}`           移除索赔明细（DELETE，**仅草稿**）
    //   `supplier-claims/{uuid}/submit`                     提交索赔（POST）
    //   `supplier-claims/{uuid}/start-negotiation`          进入协商（POST）
    //   `supplier-claims/{uuid}/negotiations`               记一条协商（POST）
    //   `supplier-claims/{uuid}/agree`                      置为已达成（POST）
    //   `supplier-claims/{uuid}/reject`                     驳回索赔（POST）
    //   `supplier-claims/{uuid}/withdraw`                   撤回索赔（POST）
    //   `supplier-claims/{uuid}/settlements`                登记赔付（POST，**幂等**）
    //   `supplier-claims/{uuid}/settlements/{uuid}/confirm`  财务确认冲减（POST）
    //   `supplier-claims/{uuid}/settlements/{uuid}/reject`   财务驳回赔付（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/settle` —— 蓝图加粗句「**索赔不是自动到账**」：已结算只能由
    //     「财务确认赔付分录」这一个动作驱动。放行一个「直接置为已结算」等于让界面显示成
    //     「登记完赔付就等于钱到账了」，而真相恰恰相反。
    //   * `{uuid}/confirm` —— 财务确认必须挂在**赔付分录**上（`/settlements/{uuid}/confirm`），
    //     否则「确认的是哪一笔赔付」在库里没有答案。
    //   * 任何对**索赔单本身**的 DELETE —— 索赔单与协商记录是要拿去和供应商对质的凭据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^supplier-claims(?:\/(?:capabilities|candidates|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|submit|start-negotiation|negotiations|agree|reject|withdraw|settlements(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:confirm|reject))?))?))?$/,
  },
  {
    // 11.5.1.1 退货分析（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.5.2.1 `quality-analysis` 那条**之前**：
    // 两条同属 11.5 分析分区，前缀形状互不相交（`return-analysis/` vs `quality-analysis/`），
    // 与其他车道的新增元素被既有条目隔开，git 能干净求并。
    //
    // ⚠️ 为什么用独立字面量 `return-analysis` 而不是并进既有 `return-quality` / `return-receipts`：
    //   11.2.3.1 质检、11.2.2.1 实收、11.5.1.1 退货分析是三套独立的状态机与口径，
    //   共用前缀会让「一个质检结论」被误写成「一笔退货」——
    //   而退货率的分子分母口径与质检结论**根本不是同一个东西**。
    //
    // ---- 首 token「return-analysis」的零命中核对结论 ----
    //   本文件既有全部 services[] 正则的**首 token**逐条核过：以 `return` 开头的只有
    //   `return-quality` / `return-receipts` / `return-disposals` / `return-dispositions` /
    //   `return-shipbacks` 五条，**首字面量与 `return-analysis` 不同** ——
    //   `return-q` / `return-r` / `return-d` / `return-s` 都不会匹配 `return-a`，
    //   所以既有分支既不会**误吃**本模块的路径，本模块也不会**误吃**它们的路径。
    //   另外全文件字面量里也不存在 "return-analysis" 这个串（grep 零命中）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   return-analysis/capabilities        能力声明与边界（GET）
    //   return-analysis/overview            退货分析总览（GET，按币种分组的退货率/拒退率/时效/平均退款金额）
    //   return-analysis/breakdown           维度下钻（GET，dimension=SHOP|SKU|CATEGORY|REASON|BATCH）
    //   return-analysis/trend               退货金额与退货率趋势（GET，granularity=DAY|WEEK|MONTH）
    //   return-analysis/anomalies           异常识别（GET，含逐条阈值）
    //   return-analysis/snapshots           报表快照列表（GET）/ 保存快照（POST）
    //   return-analysis/basis               口径列表（GET）/ 登记口径（POST）
    //   return-analysis/basis/{uuid}/supersede  口径换版（POST，换版即留痕）
    //
    // 判别顺序：**字面量子路径排在 UUID 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `basis` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   return-analysis                     —— **没有根端点**：本模块的入口是总览与能力声明，
    //     放行裸路径等于让界面以为「退货分析列表」这个页面存在。
    //   return-analysis/basis/{uuid}        —— 口径只有列表与换版两个动作，没有单独的读单条端点。
    //   return-analysis/snapshots/{uuid}    —— 快照只有列表与新建，没有单条读端点。
    //   return-analysis/forecast|predict    —— **退货率预测不存在**（本仓没有建模与预测设施），
    //     放行一个预测入口等于让界面以为系统会预测退货率。
    //   return-analysis/basis/{uuid}/delete —— 口径与快照都是留痕事实（换版写 superseded_by、
    //     快照追加式），后端已收回 UPDATE/DELETE。
    //   任何 DELETE / PUT 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^return\-analysis(?:\/(?:capabilities|overview|breakdown|trend|anomalies|snapshots|basis)|\/basis\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/supersede)$/,
  },
  {
    // 11.5.2.1 质量分析（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.2.2 `product-attribution` 那条**之后**：
    // 两条的前缀形状互不相交（`quality-analysis/` vs `product-attribution/`），
    // 与其他车道的新增元素被既有条目隔开，git 能干净求并。
    //
    //   `quality-analysis/capabilities`                 能力声明与边界（GET）
    //   `quality-analysis/overview`                     质量总览（GET）
    //   `quality-analysis/defect-distribution`          缺陷类型分布（GET）
    //   `quality-analysis/lot-trend`                    批次质量趋势（GET）
    //   `quality-analysis/supplier-comparison`          供应商质量对比（GET）
    //   `quality-analysis/photo-clusters`               缺陷照片聚类（GET，**内容指纹**口径）
    //   `quality-analysis/open-box-rate`                开箱率（GET，**必须与覆盖率一起读**）
    //   `quality-analysis/quality-cost`                 质量成本估算（GET，逐项标依据等级）
    //   `quality-analysis/improvements`                 责任分布 + 改进清单（GET）
    //   `quality-analysis/defect-observations`          集合读（GET）+ 登记（POST）
    //   `quality-analysis/defect-observations/{uuid}/void`        作废（POST，**终态**）
    //   `quality-analysis/cost-parameters`              参数集合读（GET）+ 建参数（POST）
    //   `quality-analysis/cost-parameters/{uuid}/supersede`      换版（POST，**终态**）
    //   `quality-analysis/supplier-refs`                引用集合读（GET）+ 登记（POST）
    //   `quality-analysis/supplier-refs/{uuid}/status`  引用启停（POST）
    //
    // ⚠️ 五段 alternation 各自**整段锚定到 $**。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/similarity` / `photo-clusters?similarity=` —— **图像相似度**：本仓没有图像库、
    //     没有训练好的模型、也没有第三方视觉服务的接入点。放行它等于渲染一个冒充图像
    //     识别能力的控件；聚类口径是 content_sha256 **完全相同**。
    //   * 任何 `negative-review` 形状 —— 全库**没有商品评价表**，本模块不产出
    //     「买家给了差评」这个事实，只在成本里按租户参数**估算**其影响并标成估算项。
    //   * `PUT {uuid}` —— 缺陷观察与成本参数**都是终态**：作废 / 换版只走各自的
    //     一个入口留痕。再开一个「直接改」的形状，会让同一个数字出现两个答案。
    //   * 任何 **DELETE** —— 缺陷观察的发现环节是开箱率的**唯一来源**，
    //     成本参数换版必须留痕（否则三个月后答不出「上次是多少」）。
    //     两者都只在 POST 里留痕，删除不在任何路径上。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^quality-analysis\/(?:capabilities|overview|defect-distribution|lot-trend|supplier-comparison|photo-clusters|open-box-rate|quality-cost|improvements|defect-observations|cost-parameters|supplier-refs)$|^quality-analysis\/defect-observations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/void$|^quality-analysis\/cost-parameters\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/supersede$|^quality-analysis\/supplier-refs\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status$/,
  },
  {
    // 11.4.2.1 工单管理（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.5.2.1 质量分析那条**之后**、11.1.1.2 售后单详情那条**之前**：
    // 与并行车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条的首 token「service-tickets」
    // 与既有所有 trade 条目的首 token 互不相交，谁先被匹配都互不吃。
    //
    // ---- 首 token「service-tickets」的零命中核对结论 ----
    //   本文件既有全部 services[] 正则，**逐条**核过：字面量里**不存在** "service-tickets"
    //   这个串（grep 全文件零命中），也没有任何既有正则的首 token 是 "service-tickets"
    //   的前缀 ⇒ 本条既不会被既有分支吃掉，也不会吃掉既有分支的路径。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   service-tickets                        集合读（GET，真分页 / 真筛选）+ 建单（POST）
    //   service-tickets/capabilities           能力声明（含上下游未交付的逐条说明）
    //   service-tickets/sla-stats              SLA 达成统计（GET，按关闭时间窗）
    //   service-tickets/{uuid}                 单据详情（GET：头 + 转派历史 + 事件流 + 评价 + SLA 快照 + 动作）
    //   service-tickets/{uuid}/assign          人工派单（POST）
    //   service-tickets/{uuid}/auto-assign     名册加权轮转派单（POST）
    //   service-tickets/{uuid}/transfer        转派（POST）
    //   service-tickets/{uuid}/claim           认领（POST）
    //   service-tickets/{uuid}/release         释放责任人（POST）
    //   service-tickets/{uuid}/first-reply     记录首次回复（POST）
    //   service-tickets/{uuid}/status          状态流转（POST）
    //   service-tickets/{uuid}/close           关闭（POST）
    //   service-tickets/{uuid}/satisfaction    满意度评价（POST）
    //   service-tickets/{uuid}/escalate        升级：转投诉 / 转纠纷（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / sla-stats）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   service-tickets/{uuid}/delete          —— 工单是纠纷举证材料，删掉等于毁掉
    //     「谁在什么时候把单给了谁」的凭据；要停止跟单就关闭它（写明原因）。
    //   service-tickets/{uuid}/messages        —— 15 客户与客服的消息模块**未开发**，
    //     放行一个消息入口等于让界面以为买家消息能自动变成工单。
    //   service-tickets/{uuid}/assign-rules    —— 分配规则读的是 11.1.1.3 的责任人名册，
    //     本模块不另建一份规则表。
    //   任何 PUT / DELETE 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^service-tickets(?:\/(?:capabilities|sla-stats|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:assign|auto-assign|transfer|claim|release|first-reply|status|close|satisfaction|escalate))?))?$/,
  },
  {
    // 11.1.1.2 售后单详情（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**（此处即紧邻 11.1.1.1 那条之前），
    // 与其他车道的新增元素被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条先于 11.1.1.1 那条被匹配，
    // 但两者前缀形状互不相交：11.1.1.1 管的是 `cases` / `cases/bulk`（裸路径），
    // 本条管的是 `cases/{uuid}` 与它下面的子路径，各自互不吃。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   case-detail/capabilities        详情页能力声明（**九个区块逐块报可用性 + 归属模块**）
    //   cases/{uuid}                    详情聚合（GET，一次返回九个区块 + 操作入口 + 时间线）
    //   cases/{uuid}/timeline           时间线单独分页读（GET，真分页）
    //   cases/{uuid}/notes              追加单条备注（POST，note_source=TENANT_SINGLE）
    //   cases/{uuid}/messages           登记沟通记录（POST）
    //   cases/{uuid}/attachments        登记附件与凭证（POST）
    //   cases/{uuid}/lines              登记商品行（POST）
    //
    // 判别顺序：字面量子路径（case-detail/capabilities）**排在** uuid 段之前。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ uuid 尾巴只挂在 `cases` 后面，`cases/bulk` 仍归 11.1.1.1 那条 ——
    //    本模块刻意不提供批量端点，批量授权 / 备注 / 转工单是 11.1.1.1 的三件。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `cases/{uuid}` 的 PUT / DELETE —— 留痕与单据在本服务内**不可改写**
    //     （四张留痕表在数据库层连 UPDATE/DELETE 权限都没有），改掉或删掉一行等于毁掉纠纷证据
    //   * `cases/{uuid}/refunds` / `/returns` / `/disputes` —— 11.2 / 11.3 / 11.4 **均未交付**，
    //     capabilities 里逐条报 delivered=false 与归属模块，页面据此显示「该能力未交付」
    //   * `cases/{uuid}/attribution` —— 11.1.2.2 商品问题归因未交付
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/(?:case-detail\/capabilities|cases\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:timeline|lines|messages|attachments|notes))?)$/,
  },
  {
    // 11.1.1.1 售后单列表（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.3.1.2 合单、9.2.2.2 风控两条
    // 并排，各条之间被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities     能力与上下游可用性（**明说店铺维度不可得、15 客服与卷 2 未交付**）
    //   filters          筛选项下拉的租户级词表（币种 / 平台来源来自真实数据）
    //   cases            售后单列表（GET，真分页 + 多维筛选 + 时效预警）
    //   cases            POST 登记一张售后单（11.1.1.3 受理未交付期间的登记入口）
    //   cases/bulk       POST 批量：授权 / 备注 / 转工单（逐条结果回报）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `cases/{uuid}`  —— 单条详情是 11.1.1.2 的职责，**未交付**。
    //     列表行已经带齐分页、筛选、时效、责任人、授权状态与工单号，
    //     而一个点进去 404 的入口比没有这个入口更糟。
    //   * `cases/{uuid}/notes` 之类的子资源 —— 同上，属 11.1.1.2。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern: /^aftersales\/(?:capabilities|filters|cases(?:\/bulk)?)$/,
  },
  {
    // 14.4.2.1 促销效果分析（channel 服务）。本条目是**新增一条独立的 services[] 元素**，
    // 不是往既有 channel 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `pattern: /^aftersales\/(?:capabilities|filters|cases(?:\/bulk)?)$/,`
    // **之后**：与并行车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // ---- 首 token「ad-promotion-analysis」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-promotion-analysis`
    //   （既有的是 `ad-promotions/` / `ad-budgets/` / `ad-campaigns/`），所以首 token 既是
    //   独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-promotion-analysis/capabilities      能力声明（十个维度各自的依据与下游归属）
    //   ad-promotion-analysis/options           词表（判定 / 维度 / 排序 / 促销 / 平台 / 店铺 / 币种）
    //   ad-promotion-analysis/report            效果报表（GET，真聚合 + 真分页 + 真筛选 + 真排序）
    //   ad-promotion-analysis/runs              发起一次分析（POST）+ 运行留痕（GET）
    //   ad-promotion-analysis/exports           导出留痕登记（POST）+ 留痕列表（GET）
    //   ad-promotion-analysis/exports/{uuid}/csv  按留痕 id 下载 CSV
    //   ad-promotion-analysis/cost-snapshots    成本与售价快照（GET 列表 / POST 登记）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   ad-promotion-analysis                   —— 裸路径：后端没有「一条不存在的汇总接口」，
    //                                               一切入口都带子路径。
    //   ad-promotion-analysis/effects           —— 效果数据来自 report 端点的真聚合，
    //                                               没有独立的「效果表」读取接口。
    //   ad-promotion-analysis/runs/{uuid}       —— 运行留痕是追加式证据，不提供单条详情。
    //   ad-promotion-analysis/exports/{uuid}    —— 下载只走 `/{uuid}/csv`；
    //                                               详情在列表里已经有。
    //   ad-promotion-analysis/cost-snapshots/{uuid} —— 快照是登记 + 列表，不给单条更新端点。
    //   ad-promotion-analysis/profit            —— 13.4 利润（reporting 服务）未交付，
    //                                               本模块自算毛利，**不开第二条映射**。
    //   ad-promotion-analysis/attribution       —— 平台促销归因接口未交付，
    //                                               本模块的增量是「活动前 vs 活动期」的对比。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad-promotion-analysis\/(?:capabilities|options|report|runs|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/csv)?|cost-snapshots)$/,
  },
  {
    // 11.1.1.3 售后受理（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.1 那条**之后**、11.1.2.1 那条**之前**：
    // 与并行车道的插入点被既有条目隔开，git 能干净求并。
    // 当前实际顺序：11.1.1.2 → 11.1.1.1 → **本条** → 11.1.2.1。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                        能力声明 + **当前生效**的期限天数与必退词表
    //   cases                    POST        受理一张售后请求（判四项 + 建单 + 派责任人）
    //   cases/{uuid}/rejudge     POST        重新判定（追加一版，旧判定留档）
    //   verdicts                             判定凭据列表（真分页 + 与分页无关的汇总）
    //   reply-sla                  GET/POST  回复时限策略：当前生效档 / 全部档 / 新开一档
    //   reply-sla/effective        GET       按平台档解析当前生效档（**可以返回 null = 没配**）
    //   reply-sla/{uuid}/retire    POST      收口一档（内容不可改，只能换版）
    //   owner-roster               GET/POST  责任人名册：列表 / 登记
    //   owner-roster/{uuid}        PUT       调整名册行（是否接新单 / 权重）
    //
    // 与 11.1.1.1 / 11.1.1.2 / 11.1.2.1 三条**形状互斥**：它们分别是
    // `aftersales/(capabilities|filters|cases…)`、`aftersales/cases/{uuid}/…`、
    // `aftersales/reasons/…`，本条全部带 `intake/` 这一段，谁先被匹配都互不吃。
    //
    // 刻意**不放行**的形状：
    //   * `cases/{uuid}/refunds|returns|disputes` —— 11.2 / 11.3 / 11.4 均未交付
    //   * `verdicts/{uuid}` 的 PUT / DELETE —— 判定凭据 append-only，改写等于毁掉纠纷证据
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/intake\/(?:capabilities|verdicts|reply-sla(?:\/effective|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/retire)?|owner-roster(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|cases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/rejudge)?)$/,
  },
  {
    // 11.1.2.1 售后原因分类（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.1 那条**之后**、既有 `prefix: 'trade/'` 之前：
    // 与并行车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的相邻两条前缀互不相交，
    // 谁先谁后都不误吃：`aftersales/(capabilities|filters|cases)` 与
    // `aftersales/reasons/…` 在正则上是**互斥**的（后者都带 `reasons/` 这一段）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                 能力与上下游可用性（**明说 channel 跨服务读不可得、**
    //                                11.5.2 质量分析与 6 采购索赔未交付**）
    //   tree                         分类树（GET，真递归 CTE 算路径/深度/可归因性）
    //   categories                   POST 新建分类（建根必给 rootKind，建子节点必不给）
    //   categories/{uuid}            PATCH 改名称/状态/排序/说明/父分类（**只读只写白名单**）
    //   categories/{uuid}            DELETE 删叶子且从未被引用的分类
    //   attributions                 GET  按 caseId 读当前归因
    //   attributions                 POST 登记一次归因（**至少一个原因、恰好一个主因**）
    //   mappings                     GET  平台原因码映射列表（trade 自有表）
    //   mappings                     POST 新建映射
    //   mappings/{uuid}              PATCH 改内部分类/平台原因名/状态（匹配键不可改）
    //   mappings/{uuid}              DELETE 删映射（**同步器写的行只许停用不许删**）
    //   reports                      GET  报表（七类零也出现，主因与次因分开计数）
    // 判别顺序：字面量子路径（capabilities / tree / categories / attributions /
    // mappings / reports）排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `reasons/categories/{uuid}/move` —— 提升为根要一个 rootKind，而 rootKind 是
    //     **不可改列**（改了会把全部历史归因重新归到另一个口径）。刻意不提供这个端点。
    //   * `reasons/export` —— 导出的可用性取决于能导出哪些维度；当前报表的口径由
    //     /reports 原样报出就够了，真要导出必须连排序键一起稳定，不给半成品出口。
    //   * `reasons/attributions/bulk` —— 批量归因：逐单判定 + 逐单回报的成本远高于
    //     「打开这张单、选主因、次因、给权重」本身，批量入口只会诱导乱归因。
    //   * 裸路径 `reasons` —— 后端没有对应映射。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/reasons\/(?:capabilities|tree|categories(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|attributions|mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|reports)$/,
  },
  {
    // 9.2.1.3 异常拦截（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.2.2.2 / 9.3.3.1 / 9.3.1.2
    // 那些条目各自并排，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（**明说 9.2.2.1 / 16 合规 / 5 定价没接通**）
    //   overview                          全量概览（不受筛选与分页影响）
    //   rule 不对 —— 本模块是 policy：capabilities / overview / policy(GET+PUT)
    //   restrictions                     限制清单（GET 真分页 + 真搜索 / POST 登记一条）
    //   sku-costs                         成本基线（GET 真分页 + 真搜索 / POST 登记一条）
    //   checks                            拦截清单（GET，真分页 + 真搜索 + 汇总）
    //   checks                            POST 对一张订单触发一次检查
    //   {uuid}                            检查详情（头 + 八行命中明细 + 人工处置留痕）
    //   {uuid}/disposition                POST 人工处置 —— **取消的唯一合法入口**
    // 判别顺序：字面量子路径（capabilities / overview / policy / restrictions / sku-costs / checks）
    // 排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    // ⚠️ 本白名单是**按 path 判别、不看 HTTP 方法**的，所以「刻意不放行 DELETE」在这里做不到；
    //   真正的拦截点在后端：V153 对留痕表做了 REVOKE UPDATE/DELETE，
    //   Spring 也没有任何 DELETE 映射，删除请求只会拿到 405/404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `checks/{uuid}/auto-cancel` / `checks/{uuid}/confirm-cancel` —— **本模块刻意没有**：
    //     蓝图加粗句「拦截不自动取消，默认转人工」，改最终处置的唯一入口是 /disposition
    //   * `checks/{uuid}/batch-disposition` / `checks/batch-release` —— 批量处置
    //     正是加粗句点名要避免的那种动作
    //   * `checks/{uuid}/decision` —— 同「auto-cancel」，是上面那个陷阱的另一个常见叫法
    //   * `address-validations` —— 9.2.2.1 未交付，本模块**不假装**有地址校验接口；
    //     capabilities 里的 addressValidationAvailable = false 已经说明这件事
    //   * 裸路径 `order-intercept` —— 后端没有对应映射
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-intercept\/(?:capabilities|overview|policy|restrictions|sku\-costs|checks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/disposition)?)?)$/,
  },
  {
    // 9.2.1.1 待审队列（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 9.2.1.3 `order-intercept` 那一条**之后**：
    // 与并行车道（4.4.1.3 → listing-bulk-collects、9.2.2.1 → fulfillment/consolidation、
    // 4.5.2.2 → listing-keyword-rankings）的插入点两两相隔 ≥ 74 行，
    // 默认 3 行上下文窗口不会互相覆盖，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ **首 token 为什么是 `order-pending-review`**：
    //   本仓既有白名单的正则**全部锚定**（`/^token(...)?$/`），所以只要**首 token 不同**就互不吃。
    //   逐条核对过本仓全部 46 条 pattern 的首 token：
    //     `order-review`（9.2.1.2 审单规则，形状是 `order-review/...`）—— 不同；
    //     `order-intercept`（9.2.1.3 异常拦截）—— 不同；
    //     `orders`（9.2.2.2 风控校验，形状是 `orders/risk-validation/...`）—— 不同；
    //     `platform-orders`（9.1.2.1 平台订单同步）—— 不同；
    //     `fulfillment`（9.3.x 履约）、`aftersales`（11.x 售后）、`sales-orders`（9.1.x 订单）……
    //     —— 首 token 全部不同。
    //   ⇒ `order-pending-review` 与既有任何一条都不撞车；
    //   而它**读起来也不会被误认**成 `order-review`（审单规则）——
    //   「排队等人审」与「配规则」是两件事，权限与日志都该分得开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性 + **可作为批量通过依据的规则清单**
    //   summary                          队列概览（待审 / 已挂起 / 已通过 / 已驳回 / 已超时 / 未评估 / 高风险）
    //   queue                            待审列表（GET，真分页 + 真筛选 + DB 层真排序）
    //   queue/{uuid}                     单条详情（订单事实 + 风险五维 + 完整命中链 + 批次历史）
    //   queue/{uuid}/evaluate            按当前生效规则试算（POST，标 DRY_RUN 不冒充结论）
    //   batches                          批量回执列表（GET，真分页 + 真搜索）
    //   batches                          POST 批量通过 / 驳回 / 挂起（**必须带幂等键**）
    //   batches/{uuid}                   批量回执详情（含逐条结果）
    // 判别顺序：字面量子路径（capabilities / summary / queue / batches）排在 {uuid} 分支之前，
    // 否则会被 uuid 分支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `order-pending-review` —— 后端没有对应映射
    //   * `order-pending-review/bulk-approve` / `order-pending-review/decisions` ——
    //     批量动作只有一个入口：`POST /batches`（带幂等键）。放行一个「一键全过」形状
    //     就是在接口层给蓝图加粗句「批量通过必须基于明确的规则，而不是默认全过」开后门
    //   * `order-pending-review/queue/{uuid}/auto-approve` / `/bulk` / `/status` ——
    //     改审单结论的唯一入口是批量端点；单条「改状态」的裸端点不存在
    //   * `order-pending-review/queue/{uuid}/evaluate/extra` —— 多一层就 404
    //   * `order-pending-review/batches/{uuid}/items` —— 逐条结果在 `batches/{uuid}` 里，
    //     不另开一个形状
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-pending\-review\/(?:capabilities|summary|queue(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/evaluate)?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 6.1.2.1 档口与货源池（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 9.2.1.1 `order-pending-review` 那条**之后**、9.2.2.2 `orders/risk-validation`
    // 那条**之前**：与并行车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力声明（快照语义 + 各平台是否已接线 + 未交付的下游）
    //   options                               词表（平台 / 采集方式 / 状态 / 排序键）
    //   overview                              概览（不受筛选与分页影响）
    //   suppliers                             供应商候选（catalog 授权只读，跨服务只经 API）
    //   stalls                                档口列表（GET，真分页 + 真筛选）
    //   stalls                                POST 登记档口
    //   stalls/{uuid}                         档口详情（GET）/ 修改（PUT）
    //   stalls/{uuid}/sync                    触发一次主动抓取（POST；失败带原因，不返回编造的货源）
    //   stalls/{uuid}/collections             插件推送一次采集结果（POST）
    //   items                                 货源条目列表（GET，真分页 + 真筛选 + 真排序）
    //   items/{uuid}                          货源条目详情（GET，含全部快照版本 = 价格历史）
    //   items/{uuid}/favorite                 收藏 / 取消收藏（POST / DELETE）
    //   items/{uuid}/sku-links                建立货源与内部 SKU 的关联（POST）
    //   items/{uuid}/sku-links/{linkId}       解除货源与内部 SKU 的关联（DELETE）
    //   sku/{uuid}/sources                    某内部 SKU 的货源关联视图 + 比价（GET）
    //   collector-endpoint/{platform}         采集接入登记（GET 读 / PUT 写；只存凭证引用）
    // 判别顺序：字面量子路径（capabilities / options / overview / suppliers / stalls / items）
    // 排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `suppliers` / `collector-endpoint` 这类非 UUID 形状也当成 id 放行。
    // ⚠️ platform 段用 `[A-Z0-9_]+`：它是平台词表（ALIBABA_1688 / YIWU_GOU），不是任意文本。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `sources` —— 后端没有「一条不存在的汇总列表接口」
    //   * `sources/items/{uuid}/snapshots` —— 快照版本随详情一起返回，没有独立端点
    //   * `sources/items/{uuid}/update` —— 货源快照是外部观测，改它等于篡改事实
    //   * `sources/stalls/{uuid}/items` —— 货源条目随档口详情一起返回
    //   * `sources/sku/{uuid}/compare` —— 比价就在 `sku/{uuid}/sources` 里
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^sources(?:\/(?:capabilities|options|overview|suppliers)|\/stalls(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:sync|collections))?)?|\/items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:favorite|sku-links(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|\/sku\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/sources|\/collector-endpoint\/[A-Z0-9_]+)$/,
  },
  {
    // 9.2.2.2 风控校验（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.3.1.2 合单那条并排，
    // 两条都被既有条目与其它车道的插入点隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力与上下游可用性（**明说 15 客户 / 16 合规没接通**）
    //   overview                             全量概览（不受筛选与分页影响）
    //   rule / rule                           风控规则（GET 读 + PUT upsert，五维权重合计必须 1.0000）
    //   signals                              信号列表（GET，真分页 + 真搜索）
    //   signals                              POST 登记一条风险信号（15 客户 / 16 合规未接通期间的唯一数据入口）
    //   assessments                          评估列表（GET，真分页 + 真搜索 + 汇总）
    //   assessments                          POST 对一张订单触发一次评估
    //   {uuid}                                评估详情（头 + 五行组件 + 人工复核留痕）
    //   {uuid}/review                         POST 人工复核 —— **拒发的唯一合法入口**
    // 判别顺序：字面量子路径（capabilities / overview / rule / signals / assessments）
    // 排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `assessments/{uuid}/reject` —— **本模块刻意没有**：蓝图加粗句「风控分数不能单独
    //     作为拒绝依据」，改最终动作的唯一入口是 /review（要人工身份）；
    //     若将来下游 9.2.1.3 落拦截表，它也不该走 trade 的这个命名空间
    //   * `assessments/{uuid}/decision` —— 同上，是上面那个陷阱的另一个常见叫法
    //   * `signals/{uuid}` —— 信号是时间序列（按 captured_at 追加），没有「改一条」的语义；
    //     观测值变了就再采一次，同一 (码, 主体, 采集时刻) 走 ON CONFLICT 覆盖
    //   * `signal-codes` / `rules` —— 本模块只有**一租户一条**规则，没有集合读；规则名说清楚更省事
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^orders\/risk\-validation\/(?:capabilities|overview|rule|signals|assessments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/review)?)?)$/,
    // 裸路径 orders/risk-validation **刻意不放行**：后端没有对应映射。
  },
  {
    // 9.3.3.2 履约流程监控（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 9.3.3.1 `fulfillment/requests` 那条**之前**：与并行车道的插入点
    // 被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                                    能力与上下游可用性（含 7.2.1.3 / 1 工作台
    //                                                    两个未交付下游的显式 false）
    //   cases                                            异常流程清单（真分页 + 真 SQL 筛选）
    //   cases/{uuid}                                     详情（案例 + 查证 + 处置 + 释放登记
    //                                                    + 待办 + 事件，带 retryAllowed）
    //   scan                                             扫描检出（判定用数据库时钟）
    //   cases/{uuid}/verify                              查证（加粗句的落点）
    //   cases/{uuid}/disposition                         发起处置（重试 / 切换路径 / 人工介入 /
    //                                                    释放预占）
    //   cases/{uuid}/disposition/{uuid}/status           推进处置状态（状态上的 CAS）
    //   cases/{uuid}/close                               结案（要求至少一条 COMPLETED 处置）
    //   cases/{uuid}/release/{uuid}/ack                  登记 7.2.1.3 释放回执
    //   cases/{uuid}/todos                               登记人工介入待办项
    //   thresholds        GET / PUT                      租户可维护的超时阈值（带解析来源）
    //   todos                                               人工介入待办清单（真分页）
    //   todos/{uuid}/action                               待办认领 / 完成 / 取消
    //   events                                             事件流
    //
    // 判别顺序：**字面量子路径（capabilities / cases / scan / thresholds / todos / events）
    // 必须排在 {uuid} 分支之前**，否则它们的 id 段会被 uuid 分支吃掉半截。
    // ⚠ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 fulfillment/monitor —— 后端没有对应映射，放行只会拿到 404。
    //   * 任何**本模块不存在的 path**：放行只会拿到 404/405。
    //   * `fulfillment/requests/{uuid}/release` 与 `/exceptions` —— 那是 9.3.3.1
    //     那条正则刻意不放行的形状；本模块的释放登记走 `/cases/{uuid}/release/{uuid}/ack`，
    //     异常清单走 `/cases`，**不经** `/requests/{uuid}/exceptions`。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/monitor\/(?:capabilities|scan|thresholds|todos(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/action)?|events|cases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:verify|disposition|close|todos|disposition\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status|release\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/ack))?)?)$/,
  },
  {
    // 6.2.1.1 缺货与断货风险（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有那条 trade/ 分支（9.3.3.2 履约流程监控）**之后**、
    // 9.3.3.1 履约请求那条**之前** —— 与另外三条并行车道的插入点被既有条目隔开，
    // git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                        能力与上下游可用性（含「日均销量由本服务登记」
    //                                        「店铺名称属 channel 不复制」这类必须显式报出的事实）
    //   velocities                          销售速度登记列表（GET）/ 登记（POST）
    //   snapshots                           快照列表（GET）/ 计算一次风险清单（POST）
    //   items                               断货风险清单（GET）
    //   items/{uuid}                        风险行详情（GET）：涉及店铺 + 逐项计算过程
    //
    // 判别顺序：**字面量子路径必须排在 {uuid} 分支之前**，否则它们的 id 段会被 uuid 分支吃掉半截。
    // ⚠ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 stockout-risk —— 后端没有对应映射，放行只会拿到 404。
    //   * `stockout-risk/items/{uuid}/...` 的任何子段 —— 后端只有单条详情，没有子资源。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^stockout\-risk\/(?:capabilities|velocities|snapshots|items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 9.3.3.1 履约请求（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与另外三条并行车道的插入点
    // 被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                        能力与上下游可用性（**下游三个全部未接通**，
    //                                        含 reservesUpstreamStock=false 这类必须显式报出的事实）
    //   requests/{uuid}                     详情（头 + 步骤 + 预占 + 出库单 + 作业 + 超时 + 事件）
    //   requests/{uuid}/advance             推进状态（expectedVersion 必填，冲突回 409）
    //   requests/{uuid}/block               挂起（超时 / 步骤失败 / 回执陈旧）
    //   requests/{uuid}/retry               重试 / 断点恢复（resumeReason 必填）
    //   requests/{uuid}/cancel              取消（终态，reason 必填）
    //   requests/{uuid}/complete            登记跟踪完成（TRACK 步）并收尾
    //   requests/{uuid}/reservation         发出锁定预占指令（登记 7.2，**不假装已锁**）
    //   requests/{uuid}/reservation/ack     登记 7.2 的预占回执
    //   requests/{uuid}/outbound-order      发出库单指令（登记 8.3.1）
    //   requests/{uuid}/outbound-order/ack  登记 8.3.1 的出库单回执
    //   requests/{uuid}/work-orders         批量编排作业（只有 POST，没有 GET 集合读）
    //   requests/{uuid}/work-orders/{uuid2}/status  登记单条作业状态
    //   requests/resumable                  可恢复清单（断点恢复入口）
    //   requests/scan-timeouts              扫描已超时的流程并挂起（只有 POST）
    //   requests/timeouts                   超时 / 失败检出记录列表
    //
    // 判别顺序：**字面量子路径（capabilities / resumable / scan-timeouts / timeouts）
    // 必须排在 {uuid} 分支之前**，否则它们的 id 段会被 uuid 分支吃掉半截，
    // 正则静默匹配失败，或更糟：拼出一个「id=timeouts 的请求」被凭空放行。
    // ⚠ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `fulfillment/requests/{uuid}/release` —— 释放预占是 9.3.3.2 的处置入口，未开发
    //   * `fulfillment/requests/{uuid}/exceptions` —— 异常清单同样判给 9.3.3.2
    //   * `fulfillment/requests/{uuid}/waybill` —— 运单主权在 9.4.1.1 / 8.3.4.2
    //   * 任何**本模块不存在的 path**：后端没有对应映射，放行只会拿到 404/405。
    // ⚠ 关于 DELETE：本白名单是**按 path 判别、不看 HTTP 方法**的，所以
    //   「刻意不放行 DELETE」这句在本文件里**做不到** —— DELETE 打到上面任一形状
    //   仍会被转发。真正的拦截点是后端：V152 第 11 节把 UPDATE/DELETE 从 trade_app
    //   全部收回，Spring 也没有任何 DELETE 映射（trade.openapi.json 里 delete 段为 0），
    //   所以删除请求只会拿到 405/404，且数据库层也删不掉留痕表。这里如实说明，
    //   不写一句「白名单挡住了 DELETE」这种看起来更漂亮但不成立的话。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/requests(?:\/(?:capabilities|resumable|scan-timeouts|timeouts|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:advance|block|retry|cancel|complete|reservation(?:\/ack)?|outbound-order(?:\/ack)?|work-orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?))?))?$/,
  },
  {
    // 6.3.2.2 到货与逾期（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `fulfillment/requests`（9.3.3.1 履约请求）那条**之后**、
    // `fulfillment/consolidation`（9.3.1.2 合单）那条**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ 首段 `purchase-arrivals` 是**独立字面量**：与既有 trade 分支的 `order-review`、
    //    `platform-orders`、`fulfillment/...`、`processing/...`、`aftersales/...`、
    //    `refunds/...`、`supplier-claims/...` 互不吃，所以本条放在哪个位置都不会误伤它们。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                              能力声明（状态机口径 / 逾期两个口径 / 未交付上游）
    //   overdue-board                              逾期预警看板（逾期未到 + 在途滞留两类）
    //   （裸路径）                                 到货跟踪列表（GET 真分页 + 多维筛选）
    //   {uuid}                                    跟踪详情（头 + 流水 + 应付确认 + 建议 + 留痕）
    //   {uuid}/ship                               标记已发货（在途）
    //   {uuid}/arrivals                           登记到货（部分 / 全部由后端按缺口判定）
    //   {uuid}/cancel                             取消跟踪单
    //   {uuid}/sync-in-transit                    同步 10.4.1.2 在途状态快照
    //   {uuid}/dispositions/{uuid}/resolve        处置建议采纳 / 忽略
    //   {uuid}/payable-confirmations/{uuid}/confirm 财务确认应付部分确认
    //   {uuid}/payable-confirmations/{uuid}/reject  财务驳回应付部分确认
    // 判别顺序：字面量子路径（capabilities / overdue-board）排在 {uuid} 分支**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-arrivals/{uuid}/received-qty` —— **没有**这个写形状：
    //     已收数量由 DB 从到货流水重算（V235 守卫①），后端刻意不提供直接改数量的入口。
    //   * `purchase-arrivals/{uuid}/payable-confirmations` —— 没有「直接置为已确认」的旁路；
    //     应付部分确认只能经 confirm / reject 两个动作。
    //   * `purchase-arrivals/{uuid}/dispositions` —— 处置建议是派生结果，不提供手写建议的入口。
    //   * `purchase-arrivals/{uuid}/overdue` —— 逾期判定由承诺到货日推出，不提供手工标逾期的入口。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-arrivals(?:\/(?:capabilities|overdue\-board)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:ship|arrivals|cancel|sync\-in\-transit|dispositions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/resolve|payable\-confirmations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:confirm|reject)))?)?$/,
  },
  {
    // 6.3.1.1 采购单列表（trade 服务，卷 06 采购与供应 / L3 6.3.1 采购单的锚点模块）。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `purchase-arrivals`（6.3.2.2 到货与逾期）那条**之后**、
    // `fulfillment/consolidation`（9.3.1.2 合单）那条**之前**：与本模块同属 L3 6.3 的
    // 就近邻居放在一起，且与另外三条车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ 首段 `purchase-orders` 是**独立字面量**：与既有 trade 分支的 `purchase-arrivals`
    //    （6.3.2.2）、`purchase-requests`（6.2.2.1）、`purchase-plans`（6.2.1.3）、
    //    `purchase-reconciliations`（6.5.1.1）、`purchase-analytics`（6.5.2.1）首 token
    //    全不相同，且既有正则都是**锚定**的（`^token(...)?$`），所以谁也遮蔽不了谁。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   （裸路径）                采购单主列表（GET，真分页 + 多维筛选 + 与分页无关的汇总）
    //   capabilities              能力声明（哪些维度真有依据 / 哪些批量操作能落地）
    //   filters                   筛选项下拉用的租户级词表（状态 / 币种 / 供应商 / 目标仓 / 排序）
    //   export                    导出当前筛选结果 CSV（GET，text/csv 原样透传）
    //   from-conversions           由 6.2.2.1 的转单事实生成采购单（POST）
    //   batch/urge                批量催单（POST，只留痕，不改承诺到货日）
    //   batch/close               批量关闭（POST，理由必填，关闭后整单冻结）
    //
    // ⚠️ **本条目没有 uuid 分支**：本模块是**列表**模块，按 id 取单条的「6.3.1.2 采购单详情」
    //   是同 L3 下的**兄弟模块且未交付** —— 后端此刻压根没有这个映射。
    //   放行一个后端不存在的形状比 404 更糟：界面会以为「详情页已经能用」。
    //   正因为没有 uuid 分支，`capabilities` / `filters` / `export` 三个首字母落在
    //   [0-9a-f] 里（c / f / e）**不构成任何歧义** —— 它们是字面量分支，
    //   `purchase-orders/` 之后的**任何**其它内容一律 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-orders/{uuid}`            —— 6.3.1.2 详情未交付
    //   * `purchase-orders/{uuid}/lines`      —— 6.3.1.2 详情未交付
    //   * `purchase-orders/{uuid}/urge`       —— 催单只有**批量**一个形状
    //   * `purchase-orders/{uuid}/close`      —— 关闭只有**批量**一个形状
    //   * `purchase-orders/{uuid}/export`     —— 导出是**集合**级（当前筛选整体导出）
    //   * `purchase-orders/{uuid}/delete`     —— 采购单是对供应商的正式承诺，没有删除形状
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-orders(?:\/(?:capabilities|filters|export|from\-conversions|batch\/(?:urge|close)))?$/,
  },
  {
    // 6.3.1.2 采购单创建与编辑（trade 服务，卷 06 采购与供应 / L3 6.3.1 采购单的第二格）。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「6.3.1.1 采购单列表」元素**之后**、既有 `fulfillment/consolidation`
    // 元素**之前**：同属 L3 6.3.1 的两个兄弟模块放在一起，且与并行车道的插入点被既有条目隔开。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `purchase-order-editor` 这个**独立首段字面量**：
    //   * 6.3.1.1 已经占了 `purchase-orders`（那是**列表**模块的形状：裸路径 / capabilities /
    //     filters / export / from-conversions / batch/*）。本模块是它的**兄弟**（同一个 L3 下的
    //     两个 L4），**刻意不共用那一个命名空间** —— 否则两个模块的形状会挤进同一支正则，
    //     兄弟模块交付时就得去改对方已登记的分支（跨车道回归面）。
    //   * 已逐条核对本文件里全部既有正则：`purchase-order-editor/**` 对它们**零命中**
    //     （既有条目不会先把它吃掉）；两段前缀在第 14 个字符上就分开了
    //     （`purchase-orders` 与 `purchase-order-editor`），且既有正则都是**锚定**的
    //     （`^token(...)?$`），谁也遮蔽不了谁。
    //   * 前端路由同样分开：列表在 `/purchase/orders`，建单 / 编辑在
    //     `/purchase/orders/new` 与 `/purchase/orders/{id}/edit`。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力声明（哪些维度真有依据 / 哪些归别的模块）
    //   options                          建单参照（供应商 / 目标仓 / 法人 / 币种，全是真实数据）
    //   quote-preview                    带出报价与库存快照（POST，只读无副作用）
    //   orders                           新建采购单草稿（POST）
    //   orders/{uuid}                    读编辑表单（GET）/ 编辑保存（PUT，带 expected_version）
    //   orders/{uuid}/submit             提交（POST，草稿 → 待审批，冻结单价/汇率/单位换算快照）
    //   from-drafts                      由 6.2.2.2 的采购单草稿建单（POST）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / quote-preview / from-drafts）
    //    排在 orders 段之前**。uuid 只出现在 `orders/` 之下 ⇒ 两者不会互相吃掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者会把 `orders/not-a-uuid` 这种形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `purchase-order-editor`
    //    就是凭空多出一条 GET（而界面上并没有一个「采购单编辑器列表」）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `purchase-order-editor` —— 本模块没有裸集合读，放行它会凭空多出一条 GET。
    //   * `purchase-order-editor/orders/{uuid}/delete` —— 采购单是对供应商的正式承诺，
    //     没有删除形状（6.3.1.1 的保护原封不动）。
    //   * `purchase-order-editor/orders/{uuid}/approve` / `/reject` —— **6.3.1.3 采购单审批
    //     未交付**；本模块只把单推到待审批，不代它开审批形状。
    //   * `purchase-order-editor/orders/{uuid}/lines` —— 行随编辑表单一起返回，
    //     没有独立的行子资源端点。
    //   * `purchase-order-editor/orders/{uuid}/tax` —— **12 财务未交付**（本仓没有 finance
    //     服务），本模块不代它开税额形状。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-order\-editor(?:\/(?:capabilities|options|quote\-preview|from\-drafts)|\/orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/submit)?)?)$/,
  },
  {
    // 6.3.2.1 跟单与交期（trade 服务，卷 06 采购与供应 / L3 6.3.2 采购跟踪的第一格）。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `purchase-orders`（6.3.1.1 采购单列表）那条**之后**：
    // 与本模块同属 L3 6.3 的就近邻居放在一起，且与另外三条车道的插入点被既有条目隔开，
    // git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ 首段 `purchase-followups` 是**独立字面量**：与既有 trade 分支的 `purchase-orders`
    //    （6.3.1.1）、`purchase-arrivals`（6.3.2.2）、`purchase-requests`（6.2.2.1）、
    //    `purchase-plans`（6.2.1.3）、`purchase-reconciliations`（6.5.1.1）、
    //    `purchase-analytics`（6.5.2.1）首 token 全不相同，
    //    且既有正则都是**锚定**的（`^token(...)?$`），所以谁也遮蔽不了谁。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   （裸路径）      跟单记录列表（GET，真分页 + 多维筛选）/ 登记跟单（POST）
    //   capabilities    能力声明（哪些下游接上了、哪些没有）
    //   changes         交期变更历史（GET，真分页）/ 登记交期变更（POST，触发回写）
    //   timeline        跟单时间线（跟单记录 + 交期变更合并）
    //   stats           延迟统计（平均延迟天数 / 准时率 / 已逾期）
    //   delivery        逐单交付实况（真分页）
    //
    // ⚠️ **本条目没有 uuid 分支**：跟单与交期都是**集合级**的凭据流，
    //   后端没有「按 id 取单条跟单记录 / 单条交期变更」这个映射 ——
    //   放行一个后端不存在的形状比 404 更糟：界面会以为「详情页已经能用」。
    //   正因为没有 uuid 分支，`capabilities` / `changes` / `timeline` / `stats` / `delivery`
    //   这五个首字母落在 [0-9a-f] 里的（c / t / s / d）**不构成任何歧义** ——
    //   它们是字面量分支，`purchase-followups/` 之后的**任何**其它内容一律 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-followups/{uuid}`            —— 没有单条详情端点
    //   * `purchase-followups/{uuid}/writeback`  —— 回写留痕随交期变更一起返回
    //   * `purchase-followups/{uuid}/delete`     —— 跟单与交期变更都是举证凭据，没有删除形状
    //   * `purchase-followups/export`            —— 本模块不提供导出
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern: /^purchase\-followups(?:\/(?:capabilities|changes|timeline|stats|delivery))?$/,
  },
  {
    // 14.5.1.1 达人库（channel 服务，卷 14 广告与增长 / L3 14.5.1 达人管理下的第一格）。
    //
    // 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 分支的 alternation 里
    // 并分支 —— 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，
    // retry 不会自愈）。
    // 插入锚点固定在既有 `purchase-followups`（6.3.2.1 跟单与交期）那条**之后**：
    // 同批其它三条车道的插入点各在别的行上，四条插入点被既有条目隔开，git 能干净求并。
    //
    // ⚠️ 首 token「influencers」的零命中核对结论：
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `influencers` 这个串
    //   （既有的是 `ad-campaigns` / `ad-keywords` / `ad-reports` / `ad-promotions` /
    //   `listing-*` / `aftersales` 一类），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   influencers/capabilities                能力声明（未交付段 available=false）
    //   influencers/options                     下拉项（粉丝层级 / 拉黑原因 / 合作状态 / 平台 / 店铺 / 币种 / 类目）
    //   influencers/sync-status                 逐平台的达人数据同步接入情况
    //   influencers/sync                        触发一次同步（适配器未接入 ⇒ 明确 409，不写数据）
    //   influencers/profiles                    达人库（真分页 + 多维筛选 + 真排序）
    //   influencers/profiles/{uuid}             达人详情（档案 + 标签 + 合作历史 + 黑名单）
    //   influencers/profiles/{uuid}/tags        批量打标
    //   influencers/profiles/{uuid}/tags/{uuid} 移除一个标签
    //   influencers/profiles/{uuid}/rating      重算评级
    //   influencers/tag-groups                  标签分组（GET 列表 / POST 新建）
    //   influencers/tag-groups/{uuid}           标签分组（POST 改 / DELETE 删）
    //   influencers/tags                        标签（GET 列表 / POST 新建）
    //   influencers/tags/{uuid}                 标签（POST 改 / DELETE 删）
    //   influencers/rating-tiers                评级档位（GET 列表 / POST 新建）
    //   influencers/rating-tiers/{uuid}         评级档位（POST 改 / DELETE 删）
    //   influencers/rating-rules                评分口径（GET 读 / POST 配）
    //   influencers/blacklist                   黑名单（GET 列表 / POST 拉黑）
    //   influencers/blacklist/{uuid}/release    解除黑名单（留痕：时刻 + 理由）
    //   influencers/cooperations                合作历史（GET 列表 / POST 登记）
    //   influencers/cooperations/{uuid}         合作记录（POST 改）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `influencers`                          —— 裸路径：后端没有「一条不存在的汇总接口」。
    //                                                末尾**刻意不带 `?`**：带了等于放行裸路径。
    //   * `influencers/profiles/{uuid}/export`   —— 本模块不提供导出。
    //   * `influencers/attributions`             —— 带货归因（14.5.2.1）**未交付**，
    //                                                放行它会让界面以为归因口径已经可用。
    //   * `influencers/outreach`                 —— 建联与样品（14.5.1.2）**未交付**。
    //   * `influencers/profiles/not-a-uuid`      —— 非 UUID 段不应被 uuid 分支吃掉。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `tags` 这种非 UUID 形状也当成 id 放行。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^influencers(?:\/(?:capabilities|options|sync-status|sync|profiles|tags|tag-groups|rating-tiers|rating-rules|blacklist|cooperations)|\/profiles\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/tags(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/rating)?|\/tags\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/tag-groups\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/rating-tiers\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/blacklist\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/release|\/cooperations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 9.3.1.2 合单（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：两条车道的插入点被既有条目隔开，
    // git 能干净求并（与 10.1.1.2 / 10.1.1.3 / 10.2.2.2 的 channel 条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力与上下游可用性（含 D5 未交付导致的 shop_code 缺口）
    //   rule / rule                           合单规则（GET 读 + PUT upsert）
    //   candidates                            候选订单检索（POST 只读，不落库）
    //   consolidations                        合并单列表（真分页）
    //   consolidations                        POST 落一次合并（头 + 成员 + 逐单回传任务）
    //   {uuid}                                合并单详情（头 + 成员 + 回传任务 + 事件流）
    //   {uuid}/status                         推进状态（CONFIRMED / DISPATCHED / CANCELLED）
    //   writeback-tasks                       回传任务列表
    //   {uuid}/members/{memberUuid}/writeback 按**平台订单**逐单登记物流号回传结果
    // 判别顺序：字面量子路径（capabilities / rule / candidates / writeback-tasks）
    // 排在 {uuid} 分支之前，否则会被 [0-9a-f-]+ 分支吃掉半截。
    // 刻意**没有** DELETE：合并单与事件流是履约凭据，删掉等于抹掉「这批货一起发过」的证据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/consolidation\/(?:capabilities|rule|candidates|writeback-tasks|consolidations(?:\/[0-9a-f-]+(?:\/(?:status|members\/[0-9a-f-]+\/writeback))?)?)$/,
    // 裸路径 fulfillment/consolidation **刻意不放行**：后端没有对应映射，
    // 放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // 9.2.2.1 地址与支付校验（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `fulfillment/consolidation`（9.3.1.2 合单）那条**之后**：
    // 与其余车道（4.4.1.3 / 9.2.1.1 / 4.5.2.2）的插入点两两相隔 ≥ 74 行，
    // git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的第二条不会误吃前一条的路径。
    //
    // ⚠️ 首 token 是 `order-address-payment-check`，与既有 `order-review`（9.2.1.2）、
    //   `order-intercept`（9.2.1.3）、`orders`（含 orders/risk-validation）、
    //   `fulfillment`、`aftersales`、`platform-orders`（9.1.2.1）**首 token 全都不同**，
    //   而既有正则都是锚定的（`/^token(...)?$/`），所以新前缀不会与任何一条抢路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力与上下游可用性（**明说保险策略未交付**）
    //   overview                              全量概览（不受筛选与分页影响）
    //   policy / policy                       校验策略（GET 读 + PUT upsert）
    //   embargo-rules                         禁运国家清单（GET 真分页 / POST 登记）
    //   country-rules                         国别地址格式规则（GET 真分页 / POST 登记）
    //   payment-rules                         支付状态一致性规则（GET 真分页 / POST 登记）
    //   checks                                校验结果列表（GET 真分页 + 风险等级/可投递性筛选）
    //   checks                                POST 落一次校验
    //   {uuid}                                校验详情（GET，头 + 七行明细）
    //   orders/{uuid}/latest                  某订单最近一次校验（**9.2.1.3 异常拦截的读路径**）
    // 判别顺序：字面量子路径（capabilities / overview / policy / embargo-rules /
    // country-rules / payment-rules / checks）排在 {uuid} 分支之前，
    // 否则会被 uuid 分支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `order-address-payment-check/undeclared-action` —— **本模块确定没有**的动作
    //   * `order-address-payment-check/checks/{uuid}/auto-release` —— 本模块**不**改写已落库的结论
    //   * `order-address-payment-check/checks/{uuid}/insurance-quote` —— 保险策略未交付
    //   * `order-address-payment-check/orders/{uuid}` —— 裸路径，没有对应映射
    //   * 裸路径 `order-address-payment-check` —— 后端没有对应映射
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-address\-payment\-check\/(?:capabilities|overview|policy|embargo\-rules|country\-rules|payment\-rules|checks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/latest)$/,
  },
  {
    // 6.2.1.2 补货建议（trade 服务，卷 06 采购与供应的 L3 锚点 6.2.1）。
    //
    // 蓝图：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
    // 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输出「补货建议
    // （建议量、取整说明、预计到货日、预计费用）；支持接受/调整/忽略」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在既有 `order-address-payment-check`（9.2.2.1）那条**之后**、
    // `fulfillment/split`（9.3.1.1 拆单）那条**之前** —— 与同批另外三条车道的插入点
    // 两两不重叠，git 能干净求并。既有条目一字未动。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `replenishment-suggestions` 这个独立前缀：
    //   * 既有 trade 分支是**一条**覆盖全服务的长 alternation，里面没有 `replenishment-suggestions`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`replenishment-suggestions/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 6.2.1.1 断货风险的既有 trade 路由（`stockout-risk/**`）**不是本模块的**，
    //     本条目不放行它，也不改它。
    //   * 6.2.1.3 采购计划与 6.2.2.1 采购申请**都不是本模块的**，不代它们开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   replenishment-suggestions/capabilities            能力与上下游可用性（含「头程时效未交付」）
    //   replenishment-suggestions/coverage-policies       覆盖天数目标配置（GET 真分页 / POST 登记）
    //   replenishment-suggestions/order-policies          采购起订额 / 生产周期配置（GET 真分页 / POST 登记）
    //   replenishment-suggestions/batches                 计算批次（GET 真分页 / POST 发起计算）
    //   replenishment-suggestions/suggestions             建议清单（GET，真分页 + 真筛选）
    //   replenishment-suggestions/suggestions/{uuid}      建议详情（GET：结论 + 计算过程 + 处置留痕）
    //   replenishment-suggestions/suggestions/{uuid}/decision  处置（POST：接受 / 调整 / 忽略）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / coverage-policies / order-policies / batches /
    //    suggestions）排在 {uuid} 段之前** —— alternation 是首个命中即返回，
    //    顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `decision` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `replenishment-suggestions`
    //    就是凭空多出一条 GET（后端没有这个映射）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `replenishment-suggestions` —— 后端没有对应映射。
    //   * `replenishment-suggestions/suggestions/{uuid}/recompute` —— 重算是一次**新的批次**，
    //     不是对一条已落库建议的改写（计算结论不可改写是 DB 层的不变量）。
    //   * `replenishment-suggestions/plans/**` —— 6.2.1.3 采购计划**未交付**，本模块不代它开形状。
    //   * `replenishment-suggestions/purchase-requests/**` —— 6.2.2.1 采购申请**未交付**，
    //     本模块只产出建议与处置留痕，不代它建申请单。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^replenishment\-suggestions\/(?:capabilities|coverage\-policies|order\-policies|batches|suggestions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/decision)?)?)$/,
  },
  {
    // 6.2.1.3 采购计划（trade 服务，卷 06 采购与供应的 L3 锚点 6.2.1）。
    //
    // 蓝图：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
    // 支持调整、锁定、导出给采购员。」输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、
    // 优先级）」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 既有条目**一字未动**（含 6.2.1.2 为 6.2.2.1 预留的那段 `purchase-requests` 说明）。
    //
    // 插入锚点固定在既有 `replenishment-suggestions`（6.2.1.2，本模块的直接上游）元素**之后** ——
    // 与同批另外三条车道的插入点互不重叠，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `purchase-plans` 这个独立前缀：
    //   * 既有 trade 分支是**一条**覆盖全服务的长 alternation，里面没有 `purchase-plans`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`purchase-plans/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 6.2.1.2 的 `replenishment-suggestions/**` 与 6.2.2.1 采购申请**都不是本模块的**，
    //     本条目既不改它也不代它开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   purchase-plans                          计划清单（GET，真分页 + 真筛选 + 真排序）/ 建计划（POST）
    //   purchase-plans/capabilities             能力与上下游可用性（含「锁库端口是否接线」）
    //   purchase-plans/options                  建计划参照项（目标仓 / 法人 / 大促）
    //   purchase-plans/summary                  汇总视图（**日常一段、大促一段**，两段不相加）
    //   purchase-plans/campaigns                大促日历列表（GET 真分页）/ 登记档期（POST）
    //   purchase-plans/campaigns/{uuid}         更新档期（PUT）/ 删除档期（DELETE）
    //   purchase-plans/lock-policies            锁库策略列表（GET 真分页）/ 登记策略（POST）
    //   purchase-plans/{uuid}                   计划详情（GET：头 + 计划行）
    //   purchase-plans/{uuid}/confirm           确认计划（POST：草稿 → 已确认）
    //   purchase-plans/{uuid}/aggregate         从补货建议汇总成计划行（POST）
    //   purchase-plans/{uuid}/lines             计划行清单（GET，真分页 + 真筛选）
    //   purchase-plans/{uuid}/lines/{uuid}      计划行详情（GET：行 + 调整留痕 + 锁定留痕）
    //   purchase-plans/{uuid}/lines/{uuid}/adjust  调整计划行（POST，数量 / 到货日 / 优先级 / 目标仓）
    //   purchase-plans/{uuid}/lines/{uuid}/lock    锁定计划行（POST，走 warehouse 的库存预占）
    //   purchase-plans/{uuid}/export            导出 CSV 给采购员（GET，text/csv 原样透传）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary / campaigns / lock-policies）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `adjust` / `lock` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-plans/{uuid}/delete` —— 采购计划是业务凭据（行调整 / 锁定留痕都指向它），
    //     本模块不提供删除计划；删的是**大促档期**（`campaigns/{uuid}`），且被计划引用时删不掉。
    //   * `purchase-plans/purchase-requests/**` —— 6.2.2.1 采购申请**未交付**，
    //     本模块只产出计划行，不代它建申请单、不代它走审批。
    //   * `purchase-plans/lock-policies/{uuid}` —— 锁库策略走版本化 upsert，没有按 id 单独读/改。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-plans(?:\/(?:capabilities|options|summary|campaigns(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|lock\-policies|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:aggregate|confirm|export|lines(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adjust|lock))?)?))?))?$/,
  },
  {
    // 6.6.1.1 开发立项（trade 服务，卷 06 采购与供应的 L3 锚点 6.6.1）。
    //
    // 蓝图：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
    // 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与
    // 试产单，并作为 SKU 建档的前置。」输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `purchase-plans`（6.2.1.3，同为卷 06）元素**之后**：
    // 与「`prefix: 'trade/'` 之前」「services[] 最末尾」这两个常见锚点都隔开 ≥ 1500 行，
    // git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的第二条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `development-projects` 这个独立前缀：
    //   * 既有 trade 分支的 alternation 里没有 `development-projects`；
    //     已逐条核对本文件里**全部既有正则**：`development-projects/**` **零命中**。
    //   * 6.2.1.2 的 `replenishment-suggestions/**`、6.2.1.3 的 `purchase-plans/**`、
    //     6.5.1.1 的 `purchase-reconciliations/**` 都不是本模块的。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   development-projects/capabilities            能力声明（成本来源 / 14 广告缺口 / 下游未交付）
    //   development-projects/options                 下拉（状态 + 合法去向 / 里程碑类型 / 成本来源 / 交接单类型）
    //   development-projects/summary                 按状态分组的概览
    //   development-projects                         立项清单（GET，真分页 + 真筛选）/ 立项（POST）
    //   development-projects/{uuid}                  立项详情（GET）/ 改立项（PUT，整体替换语义）
    //   development-projects/{uuid}/transitions      推进状态（POST，状态机只认存在的边）
    //   development-projects/{uuid}/milestones       里程碑计划（GET 真集合 / POST 登记）
    //   development-projects/{uuid}/milestones/{uuid}  改里程碑（PUT）/ 删里程碑（DELETE）
    //   development-projects/{uuid}/markets          目标市场与平台（GET 真集合 / POST 登记）
    //   development-projects/{uuid}/markets/{uuid}   删目标市场与平台（DELETE）
    //   development-projects/{uuid}/spec-candidates  规格候选（GET 真集合 / POST 登记）
    //   development-projects/{uuid}/spec-candidates/{uuid} 删规格候选（DELETE）
    //   development-projects/{uuid}/market-observations  手工录入的市场数据（GET 真集合 / POST 登记）
    //   development-projects/{uuid}/cost-estimate    目标成本估算（GET 预览 / POST 应用）
    //   development-projects/{uuid}/sku-candidate    可转 SKU 建档的候选参数（GET）
    //   development-projects/{uuid}/handoffs         交接单（GET 真集合 / POST 产出）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //    `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `development-projects/{uuid}/delete` —— 立项是业务凭据（迁移事件与交接单都指向它），
    //     后端不提供删除；删掉它就抹掉了「这个立项经历过什么」的证据。
    //   * `development-projects/{uuid}/sampling-orders` / `/pilot-orders` ——
    //     6.6.1.2 打样与 6.6.2.1 试产**未交付**，本模块只产**交接单**（`/handoffs`），
    //     放行这两个路径会让界面以为打样单 / 试产单已经存在。
    //   * `development-projects/{uuid}/skus` —— SKU 建档归 catalog（3.1.1.1），
    //     本模块**只产候选参数**（`/sku-candidate`），不建 SKU。
    //   * `development-projects/{uuid}/market-data` —— 系统侧市场数据（14 广告）**未交付**
    //     （reporting 服务不在本仓），这里只有手工录入的 `/market-observations`。
    //   * 任何 DELETE 裸集合形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^development\-projects(?:\/(?:capabilities|options|summary|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:transitions|milestones(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|markets(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|spec\-candidates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|market\-observations|cost\-estimate|sku\-candidate|handoffs))?))?$/,
  },
  {
    // 6.6.1.2 打样与样品管理（trade 服务，卷 06 采购与供应的 L3 锚点 6.6.1）。
    //
    // 蓝图：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
    // 样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」输出「打样单 + 样品台账 + 确认记录；样品成本」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `development-projects`（6.6.1.1，同为 6.6.1 分区）元素**之后**：
    // 与「`prefix: 'trade/'` 之前」「services[] 最末尾」这两个常见锚点都隔开 ≥ 1500 行，
    // git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的第二条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `development-samples` 这个独立前缀：
    //   * 既有 trade 分支的 alternation 里没有 `development-samples`；
    //     已逐条核对本文件里**全部既有正则**：`development-samples/**` **零命中**。
    //   * `development-projects`（6.6.1.1）的正则**不会**吃掉 `development-samples/**`
    //     —— 两者是互不相同的前缀字面量。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   development-samples/capabilities            能力声明（12 费用 / 10 物流 / 6.6.2.1 缺口）
    //   development-samples/options                 下拉（来源 / 用途 / 状态与合法去向 / 确认结果 / 成本类型 / 物流状态）
    //   development-samples/summary                 按状态分组的概览
    //   development-samples/samples                 样品台账（跨打样单，一件样品一行）
    //   development-samples/media-candidates        catalog 媒体库候选（跨服务只读）
    //   development-samples                         打样单清单（GET）/ 新建打样单（POST）
    //   development-samples/{uuid}                  打样单详情（GET）/ 改打样单（PUT）
    //   development-samples/{uuid}/transitions      推进打样单状态（POST，状态机只认存在的边）
    //   development-samples/{uuid}/samples          登记样品（POST）/ 该单下的样品台账（GET）
    //   development-samples/{uuid}/samples/{uuid}                  样品详情（GET）/ 改样品（PUT）
    //   development-samples/{uuid}/samples/{uuid}/transitions      推进制作进度（POST）
    //   development-samples/{uuid}/samples/{uuid}/confirmations    确认结果（POST）/ 确认记录（GET）
    //   development-samples/{uuid}/samples/{uuid}/shipments       物流快照（POST / GET）
    //   development-samples/{uuid}/samples/{uuid}/media           样品图片（POST / GET）
    //   development-samples/{uuid}/cost                            样品成本（GET）
    //   development-samples/{uuid}/cost/lines                      追加成本明细行（POST）
    //   development-samples/{uuid}/cost/lines/{uuid}               删成本明细行（DELETE）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary / samples / media-candidates）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    //    注意 `samples` 这个字面量同时出现在顶层（跨单台账）与 uuid 之下（某单下的样品）：
    //    顶层那个排在 uuid 段之前，uuid 之下那个挂在 uuid 分支内，两者不打架
    //    （`samples` 不是 UUID 形状，uuid 支吃不下它）。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //    `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `development-samples/{uuid}/delete` —— 打样单挂着样品、成本行与全部留痕，
    //     后端不提供删除。
    //   * `development-samples/{uuid}/pilot-orders` —— 6.6.2.1 试产单**未交付**，
    //     放行这个路径会让界面以为试产单已经存在。
    //   * `development-samples/{uuid}/expenses` / `/expense-posts` —— 进费用归集属
    //     **12 费用**（finance 服务**在本仓不存在**），本模块**不代它入账**。
    //   * `development-samples/{uuid}/samples/{uuid}/tracking` —— 真实物流轨迹属
    //     **10 物流**，本模块**只记物流快照**，不假装已接通物流平台。
    //   * `development-samples/marketing-samples` —— 达人寄样（14.5.1.2）属 14 卷，
    //     **不归本模块**：放行它会让界面以为营销样品也能在这里登记。
    //   * 任何 DELETE 裸集合形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^development\-samples(?:\/(?:capabilities|options|summary|samples|media\-candidates|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:transitions|samples(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:transitions|confirmations|shipments|media))?)?|cost(?:\/lines(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)?))?))?$/,
  },
  {
    // 6.6.2.1 试产单（trade 服务，卷 06 采购与供应的 L3 锚点 6.6.2）。
    //
    // 蓝图：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、
    // 试产成本与良率），试产结果记录并对比量产目标，输出转量产建议。」
    // 输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `development-samples`（6.6.1.2，同为 6.6 分区）元素**之后**、
    // `9.3.1.1 拆单`元素**之前**；与本批另外三条车道（6.3.2.1 / 6.3.1.3 / 11.4.1.1）
    // 的插入点被既有条目隔开，git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的第二条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `trial-productions` 这个独立前缀：
    //   * 已逐条核对本文件里**全部 109 条既有正则**（含 trade 的超长 alternation 分支）：
    //     本模块的 18 个形状对它们的命中数 = **0**；
    //   * 逐条核对过邻近首 token：`purchase-orders` / `purchase-order-editor` /
    //     `purchase-plans` / `purchase-requests` / `replenishment-suggestions` /
    //     `development-projects` / `development-samples` / `quality-inspections` /
    //     `defective-dispositions` —— **零命中**（既有的 `order-review/.../trial`
    //     首 token 是 `order-review`，与本模块互不相干）；
    //   * `development-samples`（6.6.1.2）的正则**不会**吃掉 `trial-productions/**`
    //     —— 两者是互不相同的前缀字面量。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   trial-productions/capabilities            能力声明（库存扣减 / 转量产建档 / 汇率三条缺口）
    //   trial-productions/options                 下拉（状态与合法去向 / 需求状态 / 判定结果 / 成本类型 / 建议结论）
    //   trial-productions/summary                 按状态分组的概览 + 按币种分组的成本与成本对比
    //   trial-productions/bom-version-candidates catalog 的 BOM 版本候选（跨服务只读）
    //   trial-productions                         试产单清单（GET）/ 新建试产单（POST）
    //   trial-productions/{uuid}                  试产单详情（GET）/ 改试产单（PUT）
    //   trial-productions/{uuid}/transitions      推进试产单状态（POST，状态机只认存在的边）
    //   trial-productions/{uuid}/component-requirements       组件领用需求（GET）
    //   trial-productions/{uuid}/component-requirements/{uuid}/submit   提交领用需求（POST）
    //   trial-productions/{uuid}/component-requirements/{uuid}/release  记录加工单承接（POST）
    //   trial-productions/{uuid}/inspection-items               质检标准项（GET / POST）
    //   trial-productions/{uuid}/inspection-items/{uuid}        记录质检判定（PUT）
    //   trial-productions/{uuid}/result                          试产结果（GET / POST，良率由后端推导）
    //   trial-productions/{uuid}/cost                           试产成本（GET）
    //   trial-productions/{uuid}/cost/lines                     追加成本明细行（POST）
    //   trial-productions/{uuid}/cost/lines/{uuid}              删成本明细行（DELETE）
    //   trial-productions/{uuid}/cost-comparison                试产成本 vs 量产目标成本（GET）
    //   trial-productions/{uuid}/mass-production-recommendation 转量产建议（GET / POST）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary / bom-version-candidates）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //    `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `trial-productions/{uuid}/delete` —— 试产单挂着组件需求、质检项、结果、成本与
    //     全部留痕，后端**不提供删除**（删掉它就抹掉了「这个试产经历过什么」的证据）。
    //   * `trial-productions/{uuid}/inventory-deductions` / `/stock` —— 蓝图加粗句
    //     「**试产消耗真实组件库存，需走 8 加工单而非直接改库存**」：本模块**只产出组件领用需求**，
    //     **不建库存扣减表**。放行这两个路径会让界面以为本模块在扣库存。
    //   * `trial-productions/{uuid}/component-requirements/{uuid}/deduct` —— 同上：
    //     **领用由 warehouse 侧 8.4.1 组装单承接**，本模块不提供「扣减」动作。
    //   * `trial-productions/{uuid}/assembly-orders` —— 组装单是 **warehouse 独立库**里
    //     8.4.1 的事实，本模块只存它的**逻辑引用 + 展示快照**，**不建组装单**。
    //   * `trial-productions/{uuid}/mass-production-orders` —— 6.6.2.2 转量产与建档**未开发**；
    //     本模块只产**建议事实**，放行它会让界面以为量产单已经存在。
    //   * `trial-productions/{uuid}/skus` —— SKU 建档归 3.1.2.1 与 6.6.2.2，
    //     本模块**不建 SKU**。
    //   * 任何 DELETE 裸集合形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^trial\-productions(?:\/(?:capabilities|options|summary|bom\-version\-candidates|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:transitions|component\-requirements(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|release))?)?|inspection\-items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|result|cost(?:\/lines(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)?|cost\-comparison|mass\-production\-recommendation))?))?$/,
  },
  {
    // 6.6.2.2 转量产与建档（trade 服务，卷 06 采购与供应的 L3 锚点 6.6.2）。
    //
    // 蓝图：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
    // 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
    // 输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 分支的 alternation 里并分支 ——
    // 那一支是一段超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `trial-productions`（6.6.2.1，本模块的**直接上游**）元素**之后**：
    // 与「`prefix: 'trade/'` 之前」「services[] 最末尾」这两个常见锚点都隔开 ≥ 1700 行，
    // git 能干净求并。resolve() 是「按顺序逐条匹配、首个命中即返回」，
    // 同前缀的第二条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `mass-productions` 这个独立前缀：
    //   * 已逐条核对本文件里**全部既有正则**（含 trade 的超长 alternation 分支）：
    //     本模块的 16 个形状对它们的命中数 = **0**；
    //   * 邻近首 token `trial-productions` / `development-samples` / `development-projects` /
    //     `purchase-plans` / `replenishment-suggestions` / `quality-inspections` —— **零命中**；
    //   * `trial-productions`（6.6.2.1）的正则**不会**吃掉 `mass-productions/**`
    //     —— 两者是互不相同的前缀字面量。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   mass-productions/capabilities            能力声明（价格模型 / 生产计划下达两条缺口）
    //   mass-productions/options                 下拉（状态与合法去向 / 决策结论 / 决策来源 / 建档状态 / 属性状态）
    //   mass-productions/summary                 按状态分组的概览 + 按币种分组的目标成本
    //   mass-productions/trial-candidates        可转量产的试产单候选（上游 6.6.2.1，同库只读）
    //   mass-productions/bom-version-candidates  catalog 的 BOM 版本候选（跨服务只读）
    //   mass-productions/spu-candidates         catalog 的 SPU 候选（SKU 建档的「目标键」）
    //   mass-productions/spu-attributes          catalog 的商品属性候选（建档载荷的属性取值范围）
    //   mass-productions                         量产计划清单（GET）/ 新建量产计划（POST）
    //   mass-productions/{uuid}                  量产计划详情（GET）/ 改量产计划（PUT）
    //   mass-productions/{uuid}/transitions      推进量产计划状态（POST，状态机只认存在的边）
    //   mass-productions/{uuid}/bom-lines        量产 BOM 组件行快照（GET）
    //   mass-productions/{uuid}/sku-filing       SKU 建档请求（GET / POST）
    //   mass-productions/{uuid}/sku-filing/{uuid}/submit  提交 SKU 建档（POST，真调商品中心）
    //   mass-productions/{uuid}/handoffs         下游交接留痕（GET）
    //   mass-productions/{uuid}/handoffs/replenishment  重新触发 6.2.1.2 补货建议重算（POST）
    //   mass-productions/{uuid}/cost-comparison  量产目标成本 vs 试产实测成本（GET）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary / trial-candidates /
    //    bom-version-candidates / spu-candidates / spu-attributes）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //    `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `mass-productions/{uuid}/delete` —— 量产计划挂着 BOM 快照、建档请求与全部留痕，
    //     后端**不提供删除**（删掉它就抹掉了「这个量产经历过什么」的证据）。
    //   * `mass-productions/{uuid}/skus` —— 🔴 正式 SKU 的**写入所有权在 catalog**
    //     （3.1.2.1 的 POST /catalog/products/{id}/skus）：本模块只落**建档请求事实**，
    //     经 catalog 的授权接口建档，**不建 SKU 表**。放行它会让界面以为本模块在直接写 SKU。
    //   * `mass-productions/{uuid}/inventory-deductions` / `/stock` —— 组件的库存扣减归
    //     warehouse 侧 8.4.1 组装单，本模块**不扣库存**。
    //   * `mass-productions/{uuid}/assembly-orders` —— 组装单是 **warehouse 独立库**里
    //     8.4.1 的事实，本模块只存它的**逻辑引用 + 展示快照**，**不建组装单**。
    //   * `mass-productions/{uuid}/purchase-orders` —— 采购需求归 6.2.1.2 补货建议
    //     （**同库但写入所有权是它的**）：本模块只做**重算触发**，**不直接写它的表**。
    //   * `mass-productions/{uuid}/prices` —— 价格模型归 5.3.1.1 目标定价（**未开发**），
    //     本模块**不 mock 算价**。
    //   * `mass-productions/{uuid}/sku-filing/{uuid}` —— 建档请求的**明细**没有独立端点
    //     （属性与报关要素随建档请求整体返回），放行它只会让界面拿到 404。
    //   * 任何 DELETE 裸集合形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^mass\-productions(?:\/(?:capabilities|options|summary|trial\-candidates|bom\-version\-candidates|spu\-candidates|spu\-attributes|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:transitions|bom\-lines|sku\-filing(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/submit)?|handoffs(?:\/replenishment)?|cost\-comparison))?))?$/,
  },
  {
    // 9.3.1.1 拆单（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**（即 9.3.1.2 合单那条之前）：
    // 两条车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（含「审单未接通」「不预占上游库存」）
    //   rule / rule                      拆单规则（GET 读 + PUT upsert）
    //   warehouses                       发货仓清单（GET；含 activeOnly 筛选）
    //   warehouses                       POST 登记 / 更新发货仓
    //   stock-snapshots                  可用库存分布清单（GET；按仓 / SKU 筛选）
    //   stock-snapshots                  POST 登记 / 更新可用库存分布
    //   preview                          拆单预览（POST，**无副作用**，与落库同一段判定逻辑）
    //   splits                           拆单列表（真分页 + 状态/维度/订单/仓筛选）
    //   splits                           POST 落一次拆单
    //   {uuid}                           拆单详情（头 + 单元 + 行级明细 + 候选 + 预占 + 回传 + 事件流）
    //   {uuid}/status                    推进状态（CONFIRMED / DISPATCHED / CANCELLED）
    //   reservation-commands             预占指令清单（真分页 + 状态/SKU 筛选）
    //   writeback-tasks                  发货回传任务清单（真分页）
    //   {uuid}/units/{unitUuid}/reservation 按履约单元登记**预占回执**（warehouse 侧）
    //   {uuid}/units/{unitUuid}/writeback     按履约单元登记**发货回传结果**（一单元一条）
    // 判别顺序：字面量子路径（capabilities / rule / warehouses / stock-snapshots /
    // preview / reservation-commands / writeback-tasks）排在 {uuid} 分支之前，
    // 否则会被 [0-9a-f-]+ 分支吃掉半截。
    // 刻意**没有** DELETE：拆单方案、候选依据与事件流都是履约凭据，
    // 删掉等于抹掉「这批货当时为什么这么拆」的证据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/split\/(?:capabilities|rule|warehouses|stock-snapshots|preview|reservation-commands|writeback-tasks|splits(?:\/[0-9a-f-]+(?:\/(?:status|units\/[0-9a-f-]+\/(?:reservation|writeback)))?)?)$/,
    // 裸路径 fulfillment/split **刻意不放行**：后端没有对应映射，
    // 放行它会让界面以为「一条不存在的拆单列表接口」可用。
  },
  {
    // 6.1.2.3 供应商资质：资质档案（营业执照 / 质检报告 / 认证证书 / 授权书）+ 有效期
    // + 审核状态机 + 即将过期提醒 + **阻断判定**（04 刊登与发货链路消费的那一份）。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog / trade 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「9.3.1.1 拆单」元素之后、「9.6.1.3 代发履约跟踪」元素之前，
    // 与另外三条车道的插入点互不重叠。既有条目**一字未动**。
    //
    // 🔴 为什么用 `supplier-qualifications` 这个独立前缀：
    //   * 既有分支是覆盖全服务的**一条超长 alternation**，里面没有 `supplier-qualifications`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`supplier-qualifications/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 6.1.2.2 同款货源推荐与本模块共用 L3 锚点，但**不共用端点**：
    //     本车道只交付 6.1.2.3，不代未交付的兄弟模块开形状。
    //   * catalog 侧那条长正则里已有 `supply/vendors/.../qualifications` 形状
    //     （那是 6.1.1.x 供应商档案模块的端点，**一个字都不动**）：
    //     本模块的端点走 **trade 服务 + `supplier-qualifications` 根路径**，两者不冲突。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   supplier-qualifications/capabilities                     能力声明（词表 / 上下游 gap / 阻断口径）
    //   supplier-qualifications/options                          词表选项（类型 / 审核状态 / 到期状态 / 排序）
    //   supplier-qualifications/overview                         概览（即将过期与已过期分成两个数）
    //   supplier-qualifications/policy        GET / PUT           提醒窗口（天）+ 必备资质类型
    //   supplier-qualifications/qualifications GET / POST        资质列表（真分页 + 筛选）/ 登记资质
    //   supplier-qualifications/qualifications/{uuid} GET / PUT  资质详情（+ 文件 + 审核留痕）/ 修改
    //   supplier-qualifications/qualifications/{uuid}/review     审核流转（状态机；POST）
    //   supplier-qualifications/qualifications/{uuid}/documents  上传资质文件（POST）
    //   supplier-qualifications/reminders     GET                过期提醒清单（真分页；即将过期 / 已过期分列）
    //   supplier-qualifications/reminders/scan POST              生成 / 刷新提醒（幂等）
    //   supplier-qualifications/reminders/{uuid} PUT             处理提醒（确认 / 关闭）
    //   supplier-qualifications/suppliers    GET                供应商候选（catalog 只读）
    //   supplier-qualifications/brands      GET                品牌候选（03 品牌只读）
    //   supplier-qualifications/suppliers/{uuid}/blocking-decision       供应商维度阻断判定
    //   supplier-qualifications/suppliers/{uuid}/brands/{uuid}/blocking-decision 品牌维度阻断判定
    //   supplier-qualifications/skus/{uuid}/blocking-decision             商品维度阻断判定
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / overview / policy /
    //    qualifications / reminders / suppliers / brands）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    //    `reminders/scan` 排在 `reminders/{uuid}` 之前同理（虽然 scan 也不是 UUID 形状）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `scan`、`review` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `supplier-qualifications`
    //    就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `supplier-qualifications` —— 本模块没有裸路径资源。
    //   * `supplier-qualifications/qualifications/{uuid}/documents/{docId}` / `DELETE` ——
    //     资质文件**只追加**（续期是再上传一份，不是覆盖），没有删除形状。
    //   * `supplier-qualifications/qualifications/{uuid}/delete` —— 资质档案不提供删除：
    //     审核留痕与「当时交的是什么」是凭据，删行等于抹掉事实。
    //   * `supplier-qualifications/reminders/{uuid}/delete` —— 提醒走状态流转，不走删除。
    //   * 任何 `supplier-qualifications/**` 之外的 6.1.2.x 形状（6.1.2.2 同款货源推荐
    //     **未交付**，本模块不代它开形状）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^supplier\-qualifications(?:\/(?:capabilities|options|overview|policy|qualifications|reminders|suppliers|brands)|\/qualifications\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:review|documents))?|\/reminders\/scan|\/reminders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/suppliers\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/brands\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?\/blocking\-decision|\/skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/blocking\-decision)$/,
  },
  {
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      // fulfillment/dropship-tracking 是 9.6.1.3 代发履约跟踪与异常（**新的一组**，
      // 与上面两组并列而不是并进去）：配置说「走不走代发」，代发单说「怎么下的」，
      // 跟踪说「现在到哪了、卡在哪、怎么处置」。三件事混在一组里，权限与日志分不开。
      //   capabilities     能力与上下游可用性声明（含三条检出规则的自述）
      //   policy           检出策略（租户级单例）：集合读 + POST 维护
      //   trackings        集合读 + POST 建单 + {uuid} 详情
      //   trackings/{uuid}/supplier-leg  录入供应商段（发货 / 轨迹 / 妥投）
      //   trackings/{uuid}/own-leg       录入我方段（交运 / 妥投）
      //   exceptions       异常清单（集合读）；exceptions/evaluate 是跑检出规则
      //   exceptions/{uuid}/dispose      处置（催供应商 / 改自营发货 / 发起退款）
      //   exceptions/{uuid}/dismiss      判为误报（必须写明理由）
      //   dispositions/{uuid}/status     处置状态流转
      //   tasks            待回传 / 待处置任务（集合读）
      //   tasks/{uuid}/claim             下游领取
      //   tasks/{uuid}/complete          下游回执（受理 / 失败）
      //   events           事件流（集合读；append-only，**没有**任何写形状）
      // 判别顺序：字面量子路径（capabilities / policy / events / exceptions/evaluate）
      // 排在 {uuid} 分支之前，否则会被 [0-9a-f-]+ 分支吃掉半截。
      // 刻意**没有** DELETE：跟踪事件流与异常判定证据都是凭据，删掉等于抹掉历史。
      // logistics/customs-elements 是 10.5.1.2 申报要素（**新的一组**，与上面各组并列）：
      //   capabilities       能力与上下游可用性声明（含三张词表：申报币种 / 品牌类型 / 原产地规则）
      //   filter-options     筛选下拉（目的地 / 状态 / SKU，**真实数据 DISTINCT**）
      //   list               清单（真分页 + 真筛选 + 排序白名单；**汇总不受分页影响**）
      //   upstream-preview   上游合成申报资料（3.7.1.2，**跨服务只读**；不可达返 503 不是空对象）
      //   {uuid}             详情（清单行 + 补充要素项 + **缺失项提示** + 可执行动作）
      //   {uuid}/publish     发布（上游版本存在 / 产地一致 / 要素齐备 三道闸）
      //   {uuid}/close       向前关闭生效区间（只允许收紧到仍在未来的时刻）
      // 裸路径 logistics/customs-elements = POST 建集：@PostMapping 刻意用**无值**写法，
      // 后端没有 .../customs-elements/（尾斜杠）那个映射，所以**不放行**尾斜杠那一支。
      // b2b-orders 是 9.7.1.1 批发订单与账期（卷 09，本车道追加这一支）：
      //   capabilities          能力与上下游可用性
      //   customer-terms        客户档案投影（3.6.1.2 → trade 侧只读投影）：GET 集合 + PUT upsert
      //   price-books           阶梯价投影（05 价目 5.1.1.2 的 trade 侧投影）：GET 集合 + PUT upsert + {uuid} 详情
      //   price-quote           阶梯价试算（POST，只读不落库）
      //   receivables           账期应收列表（只读；核销属于 12 财务，本服务不提供写形状）
      //   {uuid}                订单详情
      //   {uuid}/confirm        确认订单（**确认不产生应收**）
      //   {uuid}/shipments      发货确认 —— 应收的**唯一**产生入口
      //   {uuid}/invoice-requests 登记开票需求
      // 判别顺序：字面量子路径排在 {uuid} 之前，否则会被 [0-9a-f-]+ 分支吃掉。
      // 裸路径 b2b-orders 与集合读共用一支（本文件的 pattern 按路径登记、不区分方法）。
      // dealers 是 9.7.1.2 经销与分销管理（卷 09，本车道追加这一支）：
      //   capabilities          能力与上下游可用性（16 合规 / 12 返点 / 5.5.2 / 13 分析均为 available=false）
      //   candidates            经销商候选（**跨服务只读** 3.6.1.2；上游不可达返 503，不是空清单）
      //   profiles              经销商档案投影（3.6.1.2 → trade 侧只读投影）：GET 集合 + PUT upsert
      //   authorizations        授权台账（输出①）：GET 集合 + POST 建授权
      //   authorizations/{uuid}            授权详情（台账 + 配额 + 计提 + 预警 + 事件流）
      //   authorizations/{uuid}/status     授权状态流转（暂停 / 恢复 / 到期）
      //   quotas               销量与回款考核目标（输出②的目标侧，POST）
      //   quota-attainment     配额完成率（输出②）；完成率目标为 0 时是 **null**，不是 0
      //   sales                销售与回款记录（配额与窜货判定的唯一来源）：GET 集合 + POST 登记
      //   rebates              返点计提台账（输出③，GET；financeConfirmed 恒为 false = 本模块口径未与财务确认）
      //   rebates/accrue       计提动作（POST，只读不代写 12 财务）
      //   diversion-alerts           窜货预警（输出④，GET）
      //   diversion-alerts/{uuid}/state  预警处置（判为误报必须写理由）
      // 刻意**不放行**裸路径 `dealers`（后端没有这个映射）与 `dealers/events`
      // （事件流只在授权详情里给，放行它会让界面以为有独立的事件查询端点）。
      // fulfillment/shipment-writeback 是 9.4.2.1 平台回传（卷 09，本车道追加这一支）：
      //   capabilities          能力与边界声明（三态词汇、店铺目录读不到 ⇒ shopId 由调用方给）
      //   board                 分组看板（status / platform / carrier / verification 四选一）
      //   events                事件流（append-only，真分页）
      //   retry-batch           批量重试（逐条说清谁被重投、谁被哪道闸门拦下）
      //   tasks                 回传任务清单：GET 集合 + POST 从发货记录建任务（幂等）
      //   tasks/{uuid}          任务明细（冻结 payload + 尝试凭据 + 查证 + 时间线）
      //   tasks/{uuid}/submit   提交投递（入 2 队列）
      //   tasks/{uuid}/outcome  登记平台结果三态（成功 / 失败 / **结果未知**）
      //   tasks/{uuid}/verify   查证结论（未知态必须先走这里才谈得上重发）
      //   tasks/{uuid}/retry    单条重试
      //   tasks/{uuid}/corrections 人工修正（新建一版，幂等键随之变化）
      // 刻意**不放行** PUT / DELETE：凭据行 append-only，要更正走 corrections。
      // 字面量子路径（capabilities / board / events / retry-batch / tasks）排在
      // [0-9a-f-]+ 之前判别，否则 tasks 会被 id 分支吃掉。
      /^(?:fulfillment\/routing\/(?:capabilities|mcf-authorizations|cost-profiles|stock-snapshots|evaluations|switches|decisions(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/allocation\/(?:capabilities|rules|warehouses|stock-estimates|shipping-estimates|simulations|holds|allocations(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/dropship\/(?:capabilities|shop-refs|supplier-refs|supplier-stocks|events|configs(?:\/[0-9a-f-]+(?:\/(?:status|lines))?)?|evaluations)|fulfillment\/dropship-orders\/(?:capabilities|endpoints|events|orders(?:\/[0-9a-f-]+(?:\/(?:confirm-payment|dispatch|supplier-ack|shipment|fail))?)?|alerts(?:\/[0-9a-f-]+\/acknowledge)?)|fulfillment\/pending-shipment\/(?:capabilities|board|units(?:\/[0-9a-f-]+(?:\/(?:sla|cancel))?)?|batch-tasks(?:\/[0-9a-f-]+(?:\/(?:execute|cancel|waybill-acks|labels))?)?|sync)|processing\/(?:capabilities|requirements|orders(?:\/[0-9a-f-]+(?:\/(?:status|progress|components\/issue|bom-binding(?:\/lines)?))?)?|factories(?:\/[0-9a-f-]+)?)|sales-orders(?:\/(?:capabilities|[0-9a-f-]+(?:\/submit)?))?|sales-orders\/list(?:\/(?:capabilities|filters))?|sales-orders\/detail(?:\/(?:capabilities|fulfillment-reference|[0-9a-f-]+))?|fulfillment\/dropship-tracking\/(?:capabilities|policy|events|exceptions\/evaluate|exceptions(?:\/[0-9a-f-]+\/(?:dispose|dismiss))?|dispositions\/[0-9a-f-]+\/status|trackings(?:\/[0-9a-f-]+(?:\/(?:supplier-leg|own-leg))?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|sales-orders\/change\/(?:capabilities|platform-rules(?:\/[0-9a-f-]+)?|fulfillment-progress|requests(?:\/[0-9a-f-]+(?:\/(?:status|writeback-tasks))?)?|cancellations(?:\/[0-9a-f-]+(?:\/(?:status|warehouse-tasks(?:\/[0-9a-f-]+\/status)?|writeback-tasks))?)?|writeback-tasks\/[0-9a-f-]+\/status)|b2b-orders(?:\/(?:capabilities|receivables|customer-terms|price-quote|price-books(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:confirm|shipments|invoice-requests))?))?|fulfillment\/shipment-records\/(?:capabilities|board|performance|events|records(?:\/[0-9a-f-]+(?:\/(?:tracking|visibility|callbacks|status))?)?|sync)|fulfillment\/shipment-writeback\/(?:capabilities|board|events|retry-batch|tasks(?:\/[0-9a-f-]+(?:\/(?:submit|outcome|verify|retry|corrections))?)?)|fulfillment\/timeliness\/(?:capabilities|report|dimensions|overdue|events|facts(?:\/[0-9a-f-]+(?:\/(?:promise|delivery))?)?|sync)|fulfillment\/quality\/(?:capabilities|report|dimensions|drilldown|events|after-sale-signals|exceptions|alert-rules(?:\/[A-Z_]+)?|alerts(?:\/evaluate|\/[0-9a-f-]+\/acknowledge)?)|logistics\/waybills\/(?:capabilities|filter-options|list|actions\/(?:apply|cancel|print)|[0-9a-f-]+(?:\/freight)?)||logistics\/customs-elements(?:\/(?:capabilities|filter-options|list|upstream-preview|[0-9a-f-]+(?:\/(?:publish|close))?))?|logistics\/waybill-applications(?:\/(?:capabilities|carriers|batches|queue|requests(?:\/dispatch|\/[0-9a-f-]+(?:\/(?:retry|cancel))?)?))?|logistics\/waybill-address-change\/(?:capabilities|rules|notifications|redirects(?:\/[0-9a-f-]+(?:\/submit)?)?|cancels(?:\/[0-9a-f-]+(?:\/submit)?)?|precheck)|fulfillment\/dropship-margin\/(?:capabilities|fee-rates|fx-rates|cycles|events|facts(?:\/evaluate)?|report|alerts(?:\/evaluate)?|alerts\/[0-9a-f-]+\/(?:acknowledge|dismiss)|reconciliations(?:\/[0-9a-f-]+(?:\/status)?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|dealers\/(?:capabilities|candidates|profiles|quota-attainment|sales|authorizations(?:\/[0-9a-f-]+(?:\/status)?)?|quotas|rebates(?:\/accrue)?|diversion-alerts(?:\/[0-9a-f-]+\/state)?))$/,
    // 裸路径 fulfillment/routing、fulfillment/allocation 与 processing **刻意不放行**：
    // 后端没有对应映射，放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // purchase-requests 是 6.2.2.1 采购申请的**独立前缀**（trade 服务，端口 8085）。
    // 本条目是**新增一条 services[] 元素**，不是往上面那条 trade 长正则的 alternation 里并 ——
    // 那样本车道与并行车道的插入点会被隔开，git 能干净求并。
    //
    // 形状逐条列出（后端命名空间 /trade/purchase-requests）：
    //   purchase-requests                          集合读（真分页 + 真筛选 + 排序白名单）/ POST 建单
    //   purchase-requests/capabilities             能力声明（含「采购单 6.3.1.2 未交付」的如实标注）
    //   purchase-requests/options                  参照下拉（供应商 / 目标仓 / 法人）
    //   purchase-requests/{uuid}                   详情（GET）/ 更新草稿（PUT）
    //   purchase-requests/{uuid}/lines             申请行集合读（GET）/ 手工加行（POST）
    //   purchase-requests/{uuid}/lines/{uuid}/adjust    调整数量 / 交期 / 单价（POST，理由必填）
    //   purchase-requests/{uuid}/lines/{uuid}/supplier  选供应商（POST）
    //   purchase-requests/{uuid}/submit            提交审批（POST）
    //   purchase-requests/{uuid}/approval/refresh  拉取审批结论（POST）
    //   purchase-requests/{uuid}/convert           转单事实（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / options）排在 {uuid} 段**之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `approve`、`export` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ `lines/{uuid}`（**不带**动作后缀）**刻意不放行**：本模块没有「按 id 读单行」的端点，
    //    单行信息随详情一起返回。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-requests/{uuid}/approve` / `/reject` / `/approval/decide` ——
    //     **本模块没有本地批准端点**：审批结论由 17 组织（identity）裁定。
    //     本地批准会把审批流本地化，identity 那边什么都不知道还留一条没人管的待办。
    //   * `purchase-requests/{uuid}/export` —— 本模块不提供导出形状。
    //   * `purchase-requests/{uuid}/delete` —— 申请单是业务凭据（调整留痕 / 转单事实都指向它），
    //     后端也没有删除端点。
    //   * `purchase-requests/purchase-orders` —— 6.3.1.2 采购单**未交付**，
    //     本模块只产出转单事实，不开采购单的形状。
    //
    // 🔴 回归基线：本条目是**新增**元素，上一条 trade 长正则（fulfillment / processing /
    //    sales-orders / b2b-orders / dealers / shipment-writeback 等）与本文件其余所有条目
    //    **一字未动** —— 兄弟车道（warehouse / channel / catalog 组）已交付的形状没有被挤掉。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-requests(?:\/(?:capabilities|options)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/lines(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:adjust|supplier))?|\/(?:submit|convert)|\/approval\/refresh)?)?$/,
  },
  {
    // 14.1.1.3 批量操作（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-campaign-details`（14.1.1.2）、
    // `ad-rules`（14.2.2.2）等**一字未动、也不重排** —— 本文件是多车道共用的累积型白名单，
    // 往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `listing-templates`（4.2.2.1 刊登模板）那条**之前** ----
    //   同批其它车道（14.1.2.1 广告组管理 / 14.3.1.2 广告组与词报表 / 14.4.1.1 促销列表）
    //   都落在 ad-* 与促销相关的区域，与本处相隔 400 行以上，git 能干净求并。
    //
    // ---- 首 token「ad-bulk-operations」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-bulk-operations` 这个串
    //   （既有的 ad-* 首 token 是 ad-campaigns / ad-campaign-details / ad-budgets /
    //   ad-keywords / ad-reports / ad-rules / ad-type-diffs / ad-diagnostics），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-bulk-operations/capabilities       能力声明（未交付段 available=false + ownerModule + 影响）
    //   ad-bulk-operations/options            筛选项下拉 + **护栏上限**（「本次最多 N 个」要能显示）
    //   ad-bulk-operations/selection          🔴 按筛选结果全选（GET，服务端解析出 id 集合 + 游标 + 上限）
    //   ad-bulk-operations/preview            变更预览（POST，**不落库**的预演 + 护栏裁决）
    //   ad-bulk-operations/tasks              批量任务集合读（GET）/ 受理批量任务（POST，异步）
    //   ad-bulk-operations/tasks/{uuid}       单个任务 + **逐条执行结果**（GET）
    //
    // 判别顺序：字面量子路径（capabilities / options / selection / preview / tasks）排在
    // {uuid} 段之前 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}
    //（不是 [0-9a-f-]+：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-bulk-operations` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-bulk-operations/tasks/{uuid}/run` —— 任务由服务端执行器领取，
    //     手动「跑一下」的端点不存在；界面轮询任务详情即可
    //   * `ad-bulk-operations/tasks/{uuid}/cancel` —— 任务没有取消语义（未交付）
    //   * `ad-bulk-operations/preview/{uuid}` —— 预览是**无状态**的预演，不落库、不留 id
    //   * `ad-bulk-operations/writeback` —— 平台回写队列未交付（平台适配层）
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-bulk-operations`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-bulk\-operations(?:\/(?:capabilities|options|selection|preview|tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))$/,
  },
  {
    // listing-templates 是 4.2.2.1 刊登模板的**独立前缀**（channel 服务，端口 8083）。
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 长正则的 alternation 里并 ——
    // 那样本车道与并行车道的插入点会被隔开，git 能干净求并。
    //
    // 形状逐条列出：
    //   listing-templates                      集合读（真分页 + 真筛选：作用域/平台/店铺/类目/状态/关键字）
    //   listing-templates/capabilities         能力声明（编辑器侧套用入口 available=false + ownerModule=4.2.1.1）
    //   listing-templates/filter-options       筛选项词表（平台 / 店铺 / 站点，不硬编码在前端）
    //   listing-templates/apply                一键套用（POST，产出的是**草稿**，不写线上）
    //   listing-templates/applies              套用记录列表（GET 真分页）
    //   listing-templates/applies/{uuid}       单次套用详情（逐字段求值明细 + 必填违规清单）
    //   listing-templates/{uuid}               模板详情（GET）/ 新建与更新（POST）/ 删除（DELETE）
    //   listing-templates/{uuid}/status        启停模板（PUT）
    //   listing-templates/{uuid}/matches      适用范围命中的 listing（GET 真分页 —— 判定有真实数据支撑）
    //
    // 字面量子路径（capabilities / filter-options / apply / applies）一律排在 {uuid} 段**之前**判别，
    // 否则 apply / applies 会被 UUID 分支吃掉。id 段用**完整 UUID 形状**
    // [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}（不是 [0-9a-f-]+）——
    // 宽松的字符集会把 `listing-templates/apply` 之外的任意串也当成 id 放行。
    //
    // **刻意不放行**：
    //   * 裸的 `listing-templates/{uuid}/{任意动作}` —— {uuid} 之后只认 status 与 matches 两个
    //     已实现的子资源；多放一段会让一个还不存在的路径看起来像已交付。
    //   * 任何「直接上线 / 发布」形状 —— 刊登模板只产出草稿，发布归 4.3.1.2；
    //     放行一个发布形状只会让界面以为有直接上线入口。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing-templates(?:\/(?:capabilities|filter-options|apply|applies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|matches))?))?$/,
  },
  {
    // crm 是 15 客户与客服的服务（端口 8086，数据库 starcon_crm），15.4.2.1 知识库
    // 是它的第一条业务端点。本条目是**新增一条 services[] 元素**，
    // 不是往既有分支的 alternation 里并 —— 所以前六个分支逐字未动
    // （由 crm/knowledge-allowlist.test.ts 的「不误伤」一组反向钉住）。
    //
    // ⚠️ 与 catalog 那一支里的 `kb` **不是同一个东西**：那是 11.4.2.2「知识库与话术」
    // （catalog 服务、按平台规则版本组织、页面 /aftersales/knowledge）。
    // 本模块是 crm 服务的客服知识（按店铺/类目/语言组织、页面 /crm/knowledge）。
    // 两个模块都叫「知识库」但回答的不是同一个问题，**刻意不共用命名空间**，
    // 也不共用任何一张表（理由见 backend/services/crm/.../V99__crm_knowledge_base.sql 文件头第零节）。
    //
    // 形状逐条列出：
    //   capabilities / scopes / review-due 是**字面量单段**（刻意不接 id 段 ——
    //     那是能力声明与两条聚合查询，不按 id 寻址）；
    //   entries 是集合读 + POST 新建；entries/effective 是「此刻生效的那些」；
    //     entries/{uuid} 是单条详情；entries/{uuid}/versions 是版本列表 + 建草稿版本；
    //     entries/{uuid}/references 是该知识全部版本的引用记录；
    //   versions/{uuid} 刻意**不放行** —— 后端没有「按 id 取单条版本」的端点；
    //     其下的 publish / close / references 是三个不同的动作，各自寻址，
    //     所以这段后缀是**必需**的（写成可选会让裸的 versions/{uuid} 一起通过，
    //     那等于放行一个后端不存在的映射）。
    //   字面量段一律排在 [0-9a-f-]+ 之前判别：首段是字面量还是 UUID 两者不会互相吃掉。
    //
    // 刻意**没有** DELETE：引用记录是只追加的审计事实（V99 第五节），
    // 已发布版本只能向前关闭（POST close）而不是被改写或删除。
    // 放行删除形状等于让界面以为可以删掉一条引用 —— 那会把「没人用过」这个答案做出来。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^knowledge\/(?:capabilities|scopes|review-due|entries(?:\/effective|\/[0-9a-f-]+(?:\/(?:versions|references))?)?|versions\/[0-9a-f-]+\/(?:publish|close|references))$/,
    // 裸路径 knowledge **刻意不放行**：后端没有这个映射，
    // 放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // 15.1.1.1 消息收件箱（crm 服务，卷 15 的 L3 锚点 15.1.1）。
    //
    // 蓝图：「集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、
    //   收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。
    //   输出：消息列表 + 详情视图 + 关联订单上下文。
    //   **消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）、
    // `^buyer-emails...$`（15.1.2.1 邮件收发）、`^review-requests...$`（15.2.2.1 索评）、
    // `^review-rules...$`（15.2.2.2 索评规则），首 token 都与 `message-inbox` 不同，互不吃掉。
    //
    // ---- 插入锚点 ----
    //   固定在 **`prefix: 'crm/'` 的 `^knowledge\/...$`（15.4.2.1 客服知识库）那条元素之后**。
    //   ⚠️ 上一版锚在「`identity/` 那条之后」—— 与同批 14.5.1.2 建联与样品（首 token `outreach`）
    //   选了**同一个锚点**，两条车道在同一位置插入元素 ⇒ git 必然判冲突（结构性，retry 不会自愈）。
    //   本轮改锚到 `crm/knowledge` 之后：这条锚点本批**只有本车道**用，
    //   与另外三条车道的锚点互不重叠，并入时 git 能干净求并 —— 既有条目**一字未动、也不重排**。
    //
    // ---- 首 token「message-inbox」的零命中核对结论 ----
    //   本文件既有 140+ 条 services[] 正则里**没有任何一条**含 `message-inbox` 这个串。
    //   含 `message` 的只有两处，且都**不可能**吃掉本条目：
    //   ① `aftersales/cases/{uuid}/messages`（11.1.1.2 售后单详情，**活着的正则**）——
    //      首 token 是 `aftersales`，挂在 `prefix: 'trade/'` 下；
    //   ② `service-tickets/{uuid}/messages` —— 只出现在 15.4 工单那条的**注释**里，
    //      且是**刻意不放行**的形状（工单那条正则里根本没有 `messages` 分支）。
    //   且既有正则都是**锚定**的（`^token(...)?$`），而 `resolve()` 先按
    //   `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/' 的
    //   元素才有机会吃到 `crm/message-inbox/...`，而本文件里 prefix='crm/' 的另外几条
    //   正则**不可能**匹配 `message-inbox/...` ⇒ 首 token 不同即可，互不遮蔽。
    //
    // 形状逐条列出（**只放行本模块真实实现的路径**）：
    //   message-inbox/messages            消息集合读（GET，真分页 + 真筛选）/ 登记（POST）
    //   message-inbox/messages/{uuid}     单条消息详情（GET）
    //   message-inbox/capabilities        能力声明（逐维 available + ownerModule + impact）
    //   message-inbox/shops               店铺目录（来自上游 channel 的真实清单）
    //   message-inbox/options             筛选词表（平台与店铺来自本租户真实数据）
    //   message-inbox/platforms           逐平台的站内信接入状态（平台清单来自真实店铺目录）
    //   message-inbox/batch               批量处理（POST，逐条给出结论与原因）
    //   message-inbox/sync                平台消息同步（POST；当前逐平台未接入 ⇒ 后端 409）
    //
    // 判别顺序：**字面量子路径（capabilities / shops / options / platforms / batch / sync）
    // 排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `message-inbox` —— 后端**没有**这个映射（消息集合在 `messages` 上），
    //     所以末尾**刻意不带 `?`**：带了就把裸路径一起放行，而那是一个不存在的端点。
    //   * `message-inbox/messages/{uuid}/replies` —— 15.1.1.2 消息发送**未交付**，
    //     放行它会让界面以为能回复买家。
    //   * `message-inbox/messages/{uuid}/classify` —— 15.1.1.3 消息分类**未交付**，
    //     本模块的优先级由**人工**设置。
    //   * `message-inbox/messages/{uuid}/after-sales` 与 `/tickets` ——
    //     11 售后与 15.4 工单**未交付**，本模块只落**处置意图**，不写下游单据。
    //   * `message-inbox/messages/{uuid}/delete` —— 消息是纠纷举证材料，不提供删除。
    //   * `message-inbox/messages/{uuid}/attachments/{attachmentId}` ——
    //     附件按消息整体读，不按单条附件寻址（后端没有这个映射）。
    //   * `message-inbox/capabilities/{uuid}` / `/shops/{uuid}` / `/options/{uuid}` /
    //     `/platforms/{uuid}` —— 这四个是能力声明与目录，**不按 id 寻址**，
    //     所以那段后缀是**必需**的（写成可选会让裸的 `capabilities/{uuid}` 一起通过）。
    //   * 非 UUID 段 —— `message-inbox/messages/not-a-uuid` 必须 404。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^message\-inbox(?:\/(?:capabilities|shops|options|platforms|batch|sync)|\/messages(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 15.1.1.2 消息发送（crm 服务，卷 15 的 L3 锚点 15.1.1）。
    //
    // 蓝图：「回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；
    //   支持批量相同回复（同类问题）。输出：发送记录 + 平台回执 + 失败原因。
    //   **发送失败要保留原消息状态**，不能让客服以为已回复。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）、
    // `^message-inbox...$`（15.1.1.1 消息收件箱）、`^buyer-emails...$`（15.1.2.1 邮件收发）、
    // `^review-requests...$`（15.2.2.1 索评）、`^review-rules...$`（15.2.2.2 索评规则）、
    // `^cart-recovery...$`（15.6.2.2 弃单挽回），首 token 都与 `message-sends` 不同，互不吃掉。
    //
    // ---- 插入锚点 ----
    //   固定在 **`prefix: 'crm/'` 的 `^message\-inbox...$`（15.1.1.1 消息收件箱）那条元素之后**。
    //   本批另三条车道的锚点分别是：`^reviews...` 之后（15.2.1.2）、`^customer\-tags...` 之后
    //   （15.3.2.2）、channel 的 `^ad\-rules...` 之后（16.1.1.1）—— **四个锚点互不重叠**，
    //   并入时 git 能干净求并；既有条目**一字未动、也不重排**。
    //
    // ---- 首 token「message-sends」的零命中核对结论 ----
    //   本文件既有 190+ 条 services[] 正则里**没有任何一条**含 `message-sends` 这个串
    //   （`grep -c -- "message-sends" apps/web/src/lib/api-proxy.ts` = 0）。
    //   含 `message` 的只有两处，且都**不可能**吃掉本条目：
    //   ① `aftersales/cases/{uuid}/messages`（11.1.1.2 售后单详情，**活着的正则**）——
    //      首 token 是 `aftersales`，挂在 `prefix: 'trade/'` 下；
    //   ② `service-tickets/{uuid}/messages` —— 只出现在 15.4 工单那条的**注释**里。
    //   且既有正则都是**锚定**的（`^token(...)?$`），而 `resolve()` 先按
    //   `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/' 的
    //   元素才有机会吃到 `crm/message-sends/...`，而本文件里 prefix='crm/' 的另外几条
    //   正则**不可能**匹配 `message-sends/...` ⇒ 首 token 不同即可，互不遮蔽。
    //
    // 形状逐条列出（**只放行本模块真实实现的路径**）：
    //   message-sends/capabilities            能力声明（逐维 available + ownerModule + impact）
    //   message-sends/templates               回复模板集合（GET 真分页真筛选 / POST 新建）
    //   message-sends/templates/{uuid}        单条模板（GET 详情 / PUT 更新 / DELETE 删除）
    //   message-sends/knowledge-templates     知识库话术来源（15.4.2 上游，只列生效版本）
    //   message-sends/targets                 可回复的目标消息（GET，带推导出的已回复状态）
    //   message-sends/sends                   发送记录集合（GET 真分页真筛选）/ 登记（POST）
    //   message-sends/sends/{uuid}            单条发送记录详情（GET）
    //   message-sends/sends/{uuid}/result     登记投递结果（POST，人工交接）
    //   message-sends/sends/{uuid}/retry      失败重试（POST，开一次新的尝试）
    //   message-sends/batch                   批量相同回复（POST，逐条给出结论与原因）
    //
    // 判别顺序：**字面量子路径（capabilities / knowledge-templates / targets / batch）
    // 排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // `templates` 与 `sends` 两支**自带**可选的 {uuid} 段（`templates/{uuid}` 是真实形状），
    // 它们与裸 {uuid} 支首字符不同，互不吃掉。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `message-sends` —— 后端**没有**这个映射（集合在 `sends` 与 `templates` 上），
    //     所以末尾**刻意不带 `?`**：带了就把裸路径一起放行，而那是一个不存在的端点。
    //   * `message-sends/sends/{uuid}/classify` —— 15.1.1.3 消息分类**未交付**。
    //   * `message-sends/sends/{uuid}/auto-reply` —— 15.5.1 自动回复**未交付**，
    //     本模块的发送全部由**人工**发起。
    //   * `message-sends/sends/{uuid}/after-sales` / `/tickets` ——
    //     11 售后与 15.4 工单**未交付**，本模块不写下游单据、不编下游单号。
    //   * `message-sends/sends/{uuid}/delete` —— 发送记录是纠纷举证材料，不提供删除。
    //   * `message-sends/sends/{uuid}/attachments/{attachmentId}` ——
    //     附件按发送记录整体读，不按单条附件寻址（后端没有这个映射）。
    //   * `message-sends/capabilities/{uuid}` / `/targets/{uuid}` / `/knowledge-templates/{uuid}` ——
    //     这三个是能力声明与目录，**不按 id 寻址**，所以那段后缀是**必需**的
    //     （写成可选会让裸的 `capabilities/{uuid}` 一起通过）。
    //   * 非 UUID 段 —— `message-sends/sends/not-a-uuid` 必须 404。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^message\-sends(?:\/(?:capabilities|knowledge\-templates|targets|batch)|\/templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/sends(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:result|retry))?)?)$/,
  },
  {
    // 15.1.1.3 消息分类（crm 服务，卷 15 的 L3 锚点 15.1.1）。
    //
    // 蓝图：「对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），
    //   驱动自动回复与优先级，支持人工纠正以改进分类。
    //   输入：消息内容；分类规则（关键词 + 平台类型 + 历史纠正数据）。
    //   输出：分类标签 + 优先级 + 是否可自动回复。
    //   关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。
    //   **人工纠正要回流为训练样本**，否则分类准确率永远上不去。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）、
    // `^message\-inbox...$`（15.1.1.1 消息收件箱）、`^message\-sends...$`（15.1.1.2 消息发送）、
    // `^buyer-emails...$`（15.1.2.1 邮件收发）、`^review-requests...$`（15.2.2.1 索评）、
    // `^review-rules...$`（15.2.2.2 索评规则）、`^cart-recovery...$`（15.6.2.2 弃单挽回），
    // 首 token 都与 `message-classifications` 不同，互不吃掉。
    //
    // ---- 插入锚点 ----
    //   固定在 **`prefix: 'crm/'` 的 `^message\-sends...$`（15.1.1.2 消息发送）那条元素之后**。
    //   本批另三条车道的锚点分别是：`review-replies`（15.2.1.2）之后、`customer-contacts`
    //   （15.3.2.2）之后、`account-health`（16.1.1.1）之后 —— **四个锚点互不重叠**，
    //   并入时 git 能干净求并；既有元素**一字未动、也不重排**。
    //
    // ---- 首 token「message-classifications」的零命中核对结论（实测，非目测）----
    //   脚本 `.tmp/api-proxy-zero-hit.mjs` 从本文件里抽出**插入前**的既有全部 **149 条**
    //   services[] 正则，用 `new RegExp(pat).test(shape)` 逐条实测本模块 12 个 path shape：
    //   **命中数 = 0 / 12 全部零命中**。
    //   另外 grep 口径：**插入前**既有全文里 `message-classifications` 这个串出现次数 = **0**
    //   （插入后这个数只统计到本条注释与本条正则自己，不影响「既有分支吃不掉本车道」的结论）。
    //   且既有正则都是**锚定**的（`^token(...)?$`），而 `resolve()` 先按
    //   `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/' 的
    //   元素才有机会吃到 `crm/message-classifications/...`，而本文件里 prefix='crm/' 的
    //   另外几条正则**不可能**匹配 `message-classifications/...` ⇒ 首 token 不同即可，互不遮蔽。
    //
    // 形状逐条列出（**只放行本模块真实实现的路径**）：
    //   message-classifications/capabilities        能力声明（逐维 available + ownerModule + impact）
    //   message-classifications/options             筛选词表（标签/优先级/命中方式 + 真实平台与类型）
    //   message-classifications/rules               分类规则集合（GET 真分页真筛选 / POST 新建）
    //   message-classifications/rules/{uuid}        单条规则（GET 详情 / PUT 修改 / DELETE 删除）
    //   message-classifications/classifications     分类结论集合（GET，真分页 + 真筛选）
    //   message-classifications/classifications/{uuid}  单条结论详情（GET）
    //   message-classifications/messages/{uuid}     某条消息的结论 + 命中依据 + 纠正历史（GET）
    //   message-classifications/classify            分类一条消息（POST）
    //   message-classifications/classify-batch      批量分类（POST，逐条给出结论与原因）
    //   message-classifications/feedback            人工纠正（POST，只追加）/ 纠正记录列表（GET）
    //   message-classifications/training-samples    训练样本（GET，正文 + 预测 + 纠正）
    //   message-classifications/statistics          规则命中统计 + 标签分布 + 冲突与纠正（GET）
    //
    // 判别顺序：**字面量子路径（capabilities / options / statistics / training-samples /
    // classify-batch / classify / feedback）排在 {uuid} 段之前** —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。`classify-batch` 排在 `classify` **之前**
    // （虽然回溯能救，但显式在前更不容易被人改错）。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    // （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // `/messages/{uuid}` 的 uuid 段是**必需**的（后端没有 `messages` 集合这个映射）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `message-classifications` —— 后端**没有**这个映射，所以末尾**刻意不带 `?`**：
    //     带了就把裸路径一起放行，而那是一个不存在的端点。
    //   * `message-classifications/messages`（无 id）—— 后端只按消息 id 寻址。
    //   * `message-classifications/messages/{uuid}/auto-reply` —— 15.5.1 自动回复**未交付**。
    //   * `message-classifications/messages/{uuid}/alerts` —— 1.3 预警**未交付**。
    //   * `message-classifications/messages/{uuid}/classify` —— 分类走**自己的**前缀
    //     （`POST /classify`），不在消息下再开一条路径。
    //   * `message-classifications/rules/{uuid}/delete` / `/classifications/{uuid}/delete` ——
    //     删除走同一路径的 DELETE 方法，白名单按路径登记、不区分方法。
    //   * `message-classifications/capabilities/{uuid}` —— 能力声明**不按 id 寻址**。
    //   * 非 UUID 段 —— `message-classifications/rules/not-a-uuid` 必须 404。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^message\-classifications(?:\/(?:capabilities|options|statistics|training-samples|classify\-batch|classify|feedback)|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/classifications(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/messages\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 16.4.2.1 品牌授权（channel 服务，卷 16 的 L3 锚点 16.4.2）。
    //
    // 蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
    // 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。
    // 输出：授权档案 + 校验结果。关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
    // **授权过期是「平时没事、上新时出事」的典型**，需到期提醒与刊登阻断。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的 alternation 里并 ——
    // 既有 channel 元素里有十几条超长正则，并进去会让本车道与并行车道的插入点被隔开，git 判冲突。
    // 插入锚点固定在 **15.2.2.1 索评那条之前**（crm 服务的元素），
    // 与同批其它车道的锚点互不重叠，既有条目一字未动。
    //
    // 🔴 根路径用 `brand-authorizations`（**独立前缀**），与 `compliance-precheck`（16.2.2.1 合规预检）、
    //     `listing-compliance-precheck`（4.2.3.2 合规预检）都不重叠。
    //     动手前已用脚本逐条核对**既有全部 100 条 services[] 元素的正则**，
    //     对本模块的 12 条路径（裸路径 + 11 条子形状）**命中数为 0**。
    //
    // 放行的形状逐条列出：
    //   capabilities               能力声明（未交付的下游逐条报出来）
    //   authorizations             授权档案列表（真分页 + 真筛选）+ POST 录入
    //   authorizations/{uuid}      单条授权（档案 + 范围 + 文件）+ PUT 修改
    //   authorizations/{uuid}/enabled   PUT 停用 / 启用（**撤销走这里**）
    //   expiry-reminders           到期提醒清单（临期 / 已过期，真筛选 + 真分页）
    //   checks                     POST 跑一次范围校验 + GET 校验历史
    //   publish-gate               POST 刊登闸门（**服务端算出来的阻断结论**）
    //   candidates/brands          03 品牌候选（catalog，只读）
    //   candidates/suppliers       6.1.2.3 供应商资质候选（trade 服务，只读）
    //   candidates/qualifications  某品牌下已登记的供应商资质候选
    //   shops                      可选店铺（连同它覆盖的国家）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 它会让界面以为「已经可用」）：
    //   * 裸路径 `brand-authorizations` —— 后端没有「一条汇总列表接口」，
    //     列表在 `authorizations` 上。放行裸路径等于放行一个不存在的映射。
    //   * `authorizations/{uuid}/delete` —— 合规台账**不做物理删除**，撤销走 `enabled`；
    //     历史判定凭据是「当时为什么拦」的证据，更不能删。
    //   * `authorizations/{uuid}/documents` —— 授权文件是授权档案的**子集合**，
    //     在 `GET / PUT /authorizations/{uuid}` 里整体读写，没有单独的文档端点。
    //   * `expiry-reminders/scan` —— 到期提醒是**现算**的（按 DB 时钟），没有「扫一遍」这个动作。
    //   * `checks/{uuid}` —— 校验历史只有列表，没有单条详情端点。
    //   * `publish-gate/{uuid}` —— 闸门是一个动作，不是一段资源的子路径。
    //   * 非 UUID 段 —— `authorizations/not-a-uuid` 必须 404，不能被宽松分支吃掉。
    //
    // 字面量子路径（capabilities / expiry-reminders / checks / publish-gate / candidates / shops）
    // 与 {uuid} 段**不在同一层**，不会互相吃掉：`authorizations` 是字面量，
    // 它的下一层才是 {uuid}（`authorizations/not-a-uuid` 落到 404）。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^brand-authorizations\/(?:capabilities|authorizations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|expiry-reminders|checks|publish-gate|candidates\/(?:brands|suppliers|qualifications)|shops)$/,
  },
  {
    // 15.2.2.1 Request a Review（索评）（crm 服务，卷 15 的 L3 锚点 15.2.2）。
    //
    // 蓝图：「按规则向已签收订单的买家发起索评（平台允许范围内），控制频次避免打扰，
    // 支持按店铺/时间/物流状态批量执行。输出：索评任务 + 发送记录 + 评价回收统计。
    // 关系：上游 = 10.3.2.2 签收；下游 = 15.2.1.1 评价。**索评频次必须受限**。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 那条是 15.4.2.1 客服知识库（`knowledge/...`），单行超长正则，
    // 并进去会让本车道与并行车道的插入点被隔开，git 判冲突。
    // 插入锚点固定在 **crm/ 知识库那条之后、warehouse/ 7.8.2.2 那条之前**，
    // 与本批另外三条车道的锚点（catalog 两条、channel 一条）互不重叠。
    //
    // 🔴 为什么用 `review-requests` 这个独立前缀（已逐条核对既有全部 token）：
    //   * 既有 crm 分支的 token 是 `knowledge`（15.4.2.1 知识库）；
    //     `review-due` 是 knowledge **内部**的子形状（`knowledge/review-due`），不是独立 token。
    //   * 既有别的服务的 token：`orders` · `order-review`（9.2.1.2 trade）·
    //     `refunds` · `rma-authorizations` · `return-receipts` · `return-quality` …
    //   * 既有正则都是**锚定**的（`^token(...)?$`），而本文件的 `resolve()` 先按
    //     `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/'
    //     的元素才有机会吃到 `crm/review-requests/...`。
    //     而本文件里 prefix='crm/' 的元素只有知识库那一条，它的正则 `^knowledge\/...$`
    //     **不可能**匹配 `review-requests/...` ⇒ 两条互不吃掉，首 token 不同即可。
    //   * 刻意**不**复用 `order-review`（trade 的订单审核）：那是另一个服务、
    //     另一个模块（9.2.1.2），共用会让「索评」与「订单审核」在白名单上混成一个形状。
    //
    // 形状逐条列出：
    //   review-requests                          索评任务集合读（GET，真分页 + 真筛选）/ 新建（POST）
    //   review-requests/capabilities             能力声明（逐维 available + ownerModule + impact）
    //   review-requests/frequency-policy         频次硬上限（GET 读 / PUT 配置）
    //   review-requests/shops                    店铺目录（来自上游 channel 的真实清单）
    //   review-requests/candidates               已签收候选单（上游 10.3.2.2 确认单清单）
    //   review-requests/batch                    批量执行（POST，逐条给结论与原因）
    //   review-requests/recovery-stats           评价回收统计（GET）
    //   review-requests/{uuid}                   单条索评任务详情（GET）
    //   review-requests/{uuid}/send-records      登记触达（POST）
    //   review-requests/{uuid}/recoveries        登记评价回收（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / frequency-policy / shops / candidates /
    // batch / recovery-stats）排在 {uuid} 段之前** —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `review-requests/{uuid}/replies` / `/tags` / `/appeals` ——
    //     评价回复（15.2.1.2）、差评处理（15.2.1.3）都**未交付**，
    //     本模块只落**评价回收桶**，不放行这些形状等于让界面以为能回复买家。
    //   * `review-requests/{uuid}/delete` —— 索评请求是频次计数的凭据，不可删。
    //   * `review-requests/{uuid}/cancel` —— 后端没有这个映射（状态机里 CANCELLED 暂无入口）。
    //   * `review-requests/rules` —— 索评规则配置归 **15.2.2.2**（未交付），
    //     本模块的规则是**一次请求自带的快照** + 一份租户级频次硬上限。
    //   * `review-requests/{uuid}/send-records/{sendId}` ——
    //     发送记录按请求读，不按单条记录寻址（后端没有这个映射）。
    //   * 任何 DELETE / PUT 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删 / 不能乱改」的防线在服务端与 DB，不在这里。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^review-requests(?:\/(?:capabilities|frequency-policy|shops|candidates|recovery-stats|batch)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:send-records|recoveries))?)?$/,
  },
  {
    // 15.2.1.1 评价列表（crm 服务，卷 15 的 L3 锚点 15.2.1 下的评价子域）。
    //
    // 蓝图：「汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复、
    // 店铺评分汇总，支持筛选（星级、含图/含视频、未回复、时间范围）与导出。
    // 输出：评价列表 + 评分统计。关系：上游 = 2 同步；下游 = 15.2.1.2 回复、15.2.2.1 索评。
    // **评分趋势比单条评价更重要**：连续下滑要先查是产品问题还是履约问题。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 那几条（`^buyer-emails...$` / `^knowledge\/...$` / `^review-requests...$` /
    // `^review-rules...$`）都是超长单行正则，并进去会让本车道与并行车道的插入点被隔开，
    // git 判冲突。
    // 插入锚点固定在 **crm/review-requests 那条之后、crm/review-rules 那条之前**，
    // 与同批另外三条车道的锚点（crm/knowledge 之后 · crm/review-rules 之后 · services[] 末尾）
    // 互不重叠；既有 crm 条目**一字未动、也不重排**。
    //
    // ---- 首 token「reviews」的零命中核对结论 ----
    // 本文件既有全部 144 条 services[] 元素的正则**没有一条**命中本模块的 7 个形状
    // （reviews / reviews/capabilities / reviews/shops / reviews/stats / reviews/export /
    //   reviews/sync / reviews/{uuid}）—— 已用脚本逐条核对，**命中数为 0**。
    // 原因：既有 crm 分支的首 token 分别是 buyer-emails / knowledge / review-requests /
    //   review-rules，与 reviews **首 token 即不同**；而本文件的 resolve() 是
    //   「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //   特别地，^reviews... **不会**匹配 review-requests/... 或 review-rules/...：
    //   正则锚定在开头，review- 之后必须紧跟 s。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   reviews               评价列表集合读（GET，真分页 + 真筛选：星级 / 含图 / 含视频 /
    //                         未回复 / 时间范围 / 店铺 / 商品 / 变体 / 订单号 / 关键字）
    //   reviews/capabilities  能力声明（逐维 available + ownerModule + impact）
    //   reviews/shops         店铺目录（来自上游 channel 的真实清单）
    //   reviews/stats         评分统计（趋势 + 星级分布 + 店铺/商品/变体三个维度的下滑定位）
    //   reviews/export        导出 CSV（GET + query；text/csv 由代理原样透传）
    //   reviews/sync          平台评价同步（POST，**当前恒 409**：适配器未交付，不写任何数据）
    //   reviews/{uuid}        单条评价详情（GET：评价本体 + 关联订单上下文）
    //
    // 判别顺序：**字面量子路径（capabilities / shops / stats / export / sync）排在 {uuid} 段之前**
    //   —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}（不是 [0-9a-f-]+）：
    //   后者会把 capabilities 这种非 UUID 形状也当成 id 放行。
    //
    // ⚠️ **裸路径 reviews 是放行的**（与「刻意不放行」相反）：本模块的列表入口就是集合根路径
    //   （GET /crm/reviews = 评价列表），后端确实有这个映射 —— 不放行它，页面会 404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * reviews/{uuid}/replies —— 评价回复属 **15.2.1.2**（未交付），
    //     本模块只提供「是否已回复」这一个只读属性；放行回复形状会让界面以为能回复买家。
    //   * reviews/{uuid}/appeals · /tags —— 差评处理属 **15.2.1.3**（未交付）。
    //   * reviews/{uuid}/delete —— 评价是评分统计的事实，不可删。
    //   * reviews/capabilities/{uuid} · reviews/shops/{uuid} · reviews/stats/{uuid} ·
    //     reviews/export/{uuid} · reviews/sync/{uuid} —— 这五个是能力声明、目录与聚合查询，
    //     **不按 id 寻址**，所以那段后缀是**必需**的（写成可选会让裸的 capabilities/{uuid} 一起通过）。
    //   * reviews/not-a-uuid —— 非 UUID 段必须 404，不能被宽松分支吃掉。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^reviews(?:\/(?:capabilities|shops|stats|export|sync)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 15.2.1.2 评价回复（crm 服务，卷 15 的 L3 锚点 15.2.1 下的评价子域）。
    //
    // 蓝图：「回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价。
    // 输入：回复内容；目标评价。输出：回复记录 + 平台回执。关系：上游 = 15.2.1.1；下游 = 平台接口。
    // **差评回复是转化机会也是合规风险**，模板必须符合平台措辞规范。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有 crm 那几条（`^buyer-emails...$` / `^knowledge\/...$` / `^review-requests...$` /
    // `^reviews...$` / `^review-rules...$`）都是超长单行正则，并进去会让本车道与并行车道的
    // 插入点被隔开，git 判冲突。插入锚点固定在 **crm/reviews 那条之后**（与同批另外三条车道的
    // 锚点：crm/message-inbox 之后 · crm/customer-tags 之后 · channel/ad-rules 之后互不重叠）；
    // 既有 crm 条目**一字未动、也不重排**。
    //
    // ---- 首 token「review-replies」的零命中核对结论 ----
    // 本文件既有全部 services[] 元素的正则**没有一条**命中本模块的形状 ——
    // 已用脚本逐条核对，**命中数为 0**（`grep -c -- "review-replies" apps/web/src/lib/api-proxy.ts`
    // 在改之前为 0）。原因：既有 crm 分支的首 token 分别是 buyer-emails / knowledge /
    // review-requests / reviews / review-rules，与 review-replies **首 token 即不同**；
    // 而本文件的 resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条。
    // 特别地，`^reviews...` **不会**匹配 `review-replies/...`：正则锚定在开头，
    // review- 之后必须紧跟 s。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   review-replies                     回复记录集合读（GET，真分页 + 真筛选）
    //   review-replies/capabilities        能力声明（逐维 available + ownerModule + impact）
    //   review-replies/policies            平台措辞规范（GET 真分页 / PUT 发新版本）
    //   review-replies/policies/{uuid}     单条措辞规范
    //   review-replies/templates           模板列表（GET 真分页 / POST 新建草稿）
    //   review-replies/templates/{uuid}    模板详情（GET）/ 改草稿（PUT）
    //   review-replies/templates/{uuid}/publish   发布模板（POST）
    //   review-replies/templates/{uuid}/archive   下架模板（POST）
    //   review-replies/batches             批次列表（GET）/ 批量回复（POST）
    //   review-replies/batches/{uuid}      批次详情
    //   review-replies/{uuid}              单条回复详情（GET：回复本体 + 投递历史）
    //   review-replies/{uuid}/deliver      投递到平台（POST，**当前恒 409**：适配器未交付）
    //   review-replies/{uuid}/cancel       撤回待投递回复（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / policies / templates / batches）排在 {uuid} 段之前**
    //   —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}（不是 [0-9a-f-]+）：
    //   后者会把 capabilities 这种非 UUID 形状也当成 id 放行。
    //
    // ⚠️ **裸路径 review-replies 是放行的**（与「刻意不放行」相反）：本模块的列表入口就是集合根路径
    //   （GET /crm/review-replies = 回复记录列表），后端确实有这个映射 —— 不放行它，页面会 404。
    //   注意末尾**不带 `?`**：`?` 结尾的正则会把裸路径也一起放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * review-replies/{uuid}/replies —— 回复的子资源，后端没有这个映射。
    //   * review-replies/{uuid}/appeals · /tags —— 差评处理属 **15.2.1.3**（未交付）。
    //   * review-replies/{uuid}/delete —— 回复记录是**事实**（回复正文建后不可改），不可删。
    //   * review-replies/capabilities/{uuid} · review-replies/batches/{uuid}/deliver —— 这两段
    //     后端**不按 id 寻址**（能力声明与批量投递），放行它们等于承诺一个不存在的映射。
    //   * review-replies/not-a-uuid —— 非 UUID 段必须 404，不能被宽松分支吃掉。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^review\-replies(?:\/capabilities|\/policies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|archive))?)?|\/batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:deliver|cancel))?)?$/,
  },
  {
    // 15.2.1.3 差评处理（crm 服务，卷 15 的 L3 锚点 15.2.1 的第三个子模块）。
    //
    // 蓝图：「差评专项处理：标记原因（产品/物流/服务/误判）、判定是否申诉（部分平台支持）、
    // 联系买家解决后请求修改、跟踪解决率。输入：差评内容；处理动作；买家沟通记录。
    // 输出：处理记录 + 申诉任务 + 解决状态。关系：上游 = 15.2.1.1；下游 = 15.1.1.2 联系买家、
    // 11 售后（解决问题）。**解决差评的正确路径是先解决买家问题再请求修改，
    // 直接申诉容易被驳回且消耗申诉配额。**」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并分支 ——
    // 既有 crm 分支是 `^knowledge\/`、`^reviews`、`^review-requests`、`^review-rules`、
    // `^review-replies` 这几条，首 token 都与 `negative-reviews` 不同；往同一条 alternation
    // 里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 **15.2.1.2 评价回复（review-replies）那条之后**，
    // 与本批另外三条车道的锚点（message-sends / customer-contacts / account-health）互不重叠。
    //
    // ---- 首 token「negative-reviews」的零命中核对结论（**逐条实测**，非眼看）----
    //   用脚本从本文件抽出**全部 147 条** services[] 元素的 prefix + pattern，
    //   对本模块下面列出的**每一个** path shape 逐条跑 `new RegExp(pat).test(shape)`：
    //   **命中数 = 0**。
    //   * 本文件既有全文里字面量 `negative-reviews` 出现次数 = **0**
    //     （既有含 "negative" 的只有 `ad-keywords/negatives` 这类**别的命名空间**的元素，
    //      以及 11.5.2.1 质量分析那条注释里提到的 `negative-review` 形状 ——
    //      那是 `trade/` 前缀下的元素，注释里明写「本模块不产出」）；
    //   * 兄弟车道那四条**首 token 都是 `review`**（`reviews` / `review-replies` /
    //     `review-requests` / `review-rules`）的正则**都是 `^` 锚定**的，
    //     锚点之后必须紧跟各自的字面量 ⇒ 它们**不会**吃掉 `negative-reviews/...`
    //     （`review-` 之后必须紧跟 s / -r / -u / -r，而本模块是 `negative`）。
    //     这一条是**实测**过的，不是推断。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   negative-reviews                       处理单集合（GET 真分页 + 真筛选 / POST 建处理单）
    //   negative-reviews/capabilities          能力声明（逐维 available + ownerModule + impact）
    //   negative-reviews/shops                 店铺目录（来自上游 channel 的真实清单）+ 策略状态
    //   negative-reviews/stats                 解决率统计（分母 = 处理单数，分子 = 已解决数）
    //   negative-reviews/candidates            候选差评清单（还没建处理单的那些）
    //   negative-reviews/rules                 规则列表（GET 真分页 / POST 新建）
    //   negative-reviews/rules/{uuid}          单条规则（GET 详情 / PUT 原地改）
    //   negative-reviews/{uuid}                处理单详情（处理单 + 动作流水 + 申诉 + 订单上下文）
    //   negative-reviews/{uuid}/classify       标记原因 + 判定可申诉（POST）
    //   negative-reviews/{uuid}/actions        动作流水（GET 列表 / POST 记录动作）
    //   negative-reviews/{uuid}/appeals        申诉任务（GET 列表 / POST 建任务）
    //   negative-reviews/{uuid}/appeals/{uuid}/submit    提交到平台（POST，**当前恒 409**：适配器未交付）
    //   negative-reviews/{uuid}/appeals/{uuid}/submitted 人工交接登记已提交（POST）
    //   negative-reviews/{uuid}/appeals/{uuid}/outcome   登记平台判定 通过/驳回（POST）
    //   negative-reviews/{uuid}/appeals/{uuid}/cancel    撤回申诉任务（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / shops / stats / candidates / rules）排在
    //   {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}（不是 [0-9a-f-]+）：
    //   后者会把 capabilities 这种非 UUID 形状也当成 id 放行。
    //
    // ⚠️ **裸路径 negative-reviews 是放行的**（与「刻意不放行」相反）：本模块的列表入口
    //   就是集合根路径（GET /crm/negative-reviews = 处理单列表，POST 同路径 = 建处理单），
    //   后端确实有这个映射 —— 不放行它，页面会 404。
    //   末尾**不带 `?`**：`?` 结尾的正则会把裸路径也一起放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * negative-reviews/{uuid}/appeals/{uuid}/retry —— 后端没有这个映射（撤回 = cancel）。
    //   * negative-reviews/{uuid}/delete —— 处理单是**事实**，不提供删除。
    //   * negative-reviews/export —— 本模块**没有导出**这个能力（蓝图没要求）。
    //   * negative-reviews/{uuid}/tags —— 标签不属本模块。
    //   * 任何「转售后单据」形状 —— **11 售后未交付**：本模块只落**转交意图**
    //     （reason 必填），**不写下游单据 id**；放行一条售后单据路径会让界面以为
    //     「已转售后」真的发生过。
    //   * negative-reviews/not-a-uuid —— 非 UUID 段必须 404，不能被宽松分支吃掉。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^negative\-reviews(?:\/capabilities|\/shops|\/stats|\/candidates|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:classify|actions|appeals(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|submitted|outcome|cancel))?)?))?)?$/,
  },
  {
    // 15.2.2.2 索评规则（crm 服务，卷 15 的 L3 锚点 15.2.2 的第二个子模块）。
    //
    // 蓝图：「配置索评策略：按店铺、按物流方式（自发货/平台仓）设置不同延后时间，避开节假日，
    // 支持 A/B 测试不同话术。输入：规则配置；话术模板。输出：生效策略 + 效果对比。
    // 关系：上游 = 15.2.2.1；下游 = 15.1.1.2 发送。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 crm 分支的 alternation 里并 ——
    // 既有两条 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）与
    // `^review-requests...$`（15.2.2.1 索评），首 token 都与 `review-rules` 不同，
    // 两条互不吃掉。插入锚点固定在 **15.2.2.1 那条之后、warehouse 7.8.2.2 那条之前**，
    // 与本批另外三条车道的锚点互不重叠。
    //
    // 🔴 为什么用 `review-rules` 这个独立前缀（已逐条核对既有全部 token）：
    //   * 既有 crm 分支的 token 是 `knowledge` 与 `review-requests`；
    //     `review-due` 是 knowledge **内部**的子形状（`knowledge/review-due`），不是独立 token。
    //   * 既有别的服务的 token：`orders` · `order-review`（9.2.1.2 trade）·
    //     `refunds` · `rma-authorizations` · `return-receipts` · `return-quality` …
    //   * 既有正则都是**锚定**的（`^token(...)?$`），而本文件的 `resolve()` 先按
    //     `path.startsWith(service.prefix)` 收窄服务、再判别正则 ⇒ 只有 prefix='crm/'
    //     的元素才有机会吃到 `crm/review-rules/...`。
    //     而 prefix='crm/' 的另两条元素正则是 `^knowledge\/...$` 与 `^review-requests...$`，
    //     **不可能**匹配 `review-rules/...` ⇒ 首 token 不同即可。
    //   * 刻意**不**复用 `review-requests/rules`（15.2.2.1 那条里写在拒绝清单上的形状）：
    //     本模块是**独立命名空间**，不往兄弟模块的路径树上挂。
    //
    // 形状逐条列出：
    //   review-rules                       索评规则集合（GET 真分页真筛选 / POST 新建）
    //   review-rules/{uuid}                单条规则（GET 详情 / PUT 更新 / DELETE 删除）
    //   review-rules/capabilities          能力声明（逐维 available + ownerModule + impact）
    //   review-rules/shops                 店铺目录（来自上游 channel 的真实清单）
    //   review-rules/effective             生效策略解析（GET）
    //   review-rules/effect-comparison     效果对比（GET）
    //   review-rules/candidates            可被应用策略的索评请求（上游 15.2.2.1，GET）
    //   review-rules/templates             话术模板集合（GET / POST）
    //   review-rules/templates/{uuid}      单个话术模板（GET / PUT / DELETE）
    //   review-rules/holidays              节假日集合（GET / POST）
    //   review-rules/holidays/{uuid}       单个节假日（GET / PUT / DELETE）
    //   review-rules/assignments          生效策略落地记录（GET）/ 应用策略（POST）
    //   review-rules/assignments/{uuid}   单条落地记录（GET）
    //
    // 判别顺序：**字面量子路径（capabilities / shops / effective / effect-comparison /
    // candidates / templates / holidays / assignments）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `review-rules/capabilities/{uuid}` / `/shops/{uuid}` / `/effective/{uuid}` /
    //     `/effect-comparison/{uuid}` / `/candidates/{uuid}` ——
    //     这五个是聚合查询与目录，**不按 id 寻址**，所以那段后缀是**必需**的
    //     （写成可选会让裸的 `capabilities/{uuid}` 一起通过）。
    //   * `review-rules/{uuid}/variants` —— 后端没有「按规则 id 取变体」这个映射。
    //   * `review-rules/effective/{uuid}` —— 生效策略**不按规则 id 寻址**。
    //   * 任何 PUT / DELETE 形状之外的第三层子路径 —— 后端只有上面列出的那些。
    //   * DELETE 本身是**放行**的：规则 / 模板 / 节假日都是**配置**（不是凭据），
    //     删了就是删了；而「已被生效策略落地引用过的删不掉」这条防线在服务端与 DB，
    //     不在白名单里（白名单是**按路径登记、不区分方法**的）。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^review-rules(?:\/(?:capabilities|shops|effective|effect-comparison|candidates)|\/(?:templates|holidays|assignments)(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 15.3.2.1 客户标签（crm 服务，卷 15 L3 锚点 15.3.2 下的客户标签子域）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 crm 那几条巨型 alternation 里
    //   并分支 —— 那些是超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点：紧跟 `crm/review-rules`（15.2.2.2 索评规则）那条元素之后 ----
    //   同批另外三条车道的锚点分别是：`crm/knowledge` 之后（15.1.1.1 重派）、
    //   `crm/review-requests` 之后（15.2.1.1）、services[] 数组末尾（15.6.2.2）——
    //   都与本处相距足够远，git 能干净求并。
    //
    // ---- 首 token「customer-tags」的零命中核对结论 ----
    //   本文件既有全部 services[] 正则里**没有任何一条**含 `customer-tags` 这个串
    //   （既有的是 `knowledge` / `review-requests` / `review-rules` / `buyer-emails` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   既有 crm 四条的正则都是**锚定**的（`^knowledge/…`、`^review-requests/…`、
    //   `^review-rules/…`、`^buyer-emails/…`），逐条实测对 `customer-tags/…` **全部零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   customer-tags/capabilities                   能力声明（未交付段 available=false + ownerModule）
    //   customer-tags/tags                           标签定义集合读（GET，真分页 + 真筛选）
    //   customer-tags/tags/{uuid}                    标签定义详情（GET）/ 改（PUT）/ 删（DELETE）
    //   customer-tags/rules                          规则集合读（GET）/ 登记规则（POST）
    //   customer-tags/rules/{uuid}                   规则详情（GET）/ 改（PUT）/ 删（DELETE）
    //   customer-tags/rules/{uuid}/run               执行一次规则评估（POST，写动作）
    //   customer-tags/runs                           运行记录集合读（GET，真分页 + 真筛选）
    //   customer-tags/runs/{uuid}                    单次运行详情（GET，含事实汇总）
    //   customer-tags/runs/{uuid}/evaluations        逐客户判定结论（GET，真分页 + 真筛选）
    //   customer-tags/assignments                    标签记录集合读（GET）/ 手工打标（POST）
    //   customer-tags/assignments/{uuid}             标签记录详情（GET，含判定依据快照）
    //   customer-tags/assignments/{uuid}/review      人工复核（POST：确认 / 撤销）
    //   customer-tags/distribution                   标签分布统计（GET）
    //   customer-tags/customer-candidates            候选客户（GET，来自上游订单事实的真实聚合）
    //
    // 判别顺序：字面量子路径（capabilities / distribution / customer-candidates）排在 {uuid} 段之前 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   customer-tags                                  —— 裸路径：后端没有「一条不存在的汇总列表接口」，
    //     一律走 `customer-tags/tags` / `customer-tags/assignments` 等带子路径的入口。
    //   customer-tags/tags/{uuid}/delete               —— 删除走 `DELETE customer-tags/tags/{uuid}`。
    //   customer-tags/rules/{uuid}/recompute          —— 重算走 `POST customer-tags/rules/{uuid}/run`。
    //   customer-tags/segments                        —— **按标签筛出人群**属 15.3.1.1（未交付）：
    //     放行它等于让界面以为「人群已经筛好了」。
    //   customer-tags/promotions / customer-tags/campaigns —— 促销定向属 14.4（未交付）。
    //   customer-tags/care-emails                     —— 关怀邮件属 15.5.2.1（未交付）。
    //   customer-tags/assignments/{uuid}/delete       —— 标签记录是**凭据**（判定依据的载体），
    //     不提供删除；要撤销走 `POST …/review`。
    //   customer-tags/runs/{uuid}/replay              —— 历史运行不可重放，重跑走 `rules/{uuid}/run`。
    //   任何非 UUID 段（如 `customer-tags/not-a-uuid`）—— 必须 404，不能被宽松分支吃掉。
    //
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `customer-tags`。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^customer\-tags(?:\/(?:capabilities|distribution|customer-candidates)|\/tags(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/run)?)?|\/runs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/evaluations)?)?|\/assignments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/review)?)?)$/,
  },
  {
    // 15.3.2.2 接触记录（crm 服务，卷 15 L3 锚点 15.3.2 下的接触记录子域）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 crm 那几条巨型 alternation 里
    //   并分支 —— 那些是超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点：紧跟 `crm/customer-tags`（15.3.2.1 客户标签）那条元素之后 ----
    //   本批另外三条车道的锚点分别是：`crm/message-inbox` 之后（15.1.1.2）、`crm/reviews` 之后
    //   （15.2.1.2）、channel 的 `crm/ad-rules` 之后（16.1.1.1）—— 四个锚点互不重叠，
    //   git 能干净求并。
    //
    // ---- 首 token「customer-contacts」的零命中核对结论 ----
    //   本文件既有全部 services[] 正则里**没有任何一条**含 `customer-contacts` 这个串
    //   （既有的是 `customer-tags` / `knowledge` / `review-requests` / `review-rules` /
    //   `buyer-emails` 一类），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   `customer-tags` 与 `customer-contacts` 在第 9 个字符（「t」vs「c」）就分叉，
    //   两条正则互不吃掉对方。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   customer-contacts/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   customer-contacts/contacts                 接触记录集合读（GET，真分页 + 真筛选）
    //   customer-contacts/contacts/{uuid}          接触记录详情（GET）
    //   customer-contacts/timeline                 客户接触时间线（GET，带当前退订判定）
    //   customer-contacts/ingest                   上游模块自动写入（POST，幂等）
    //   customer-contacts/opt-outs                 退订记录集合读（GET）/ 登记退订（POST）
    //   customer-contacts/opt-outs/{uuid}          单条退订记录（GET）
    //   customer-contacts/opt-outs/{uuid}/revoke   显式撤销退订（POST，必须带原因）
    //   customer-contacts/opt-outs/{uuid}/events   退订审计流水（GET，只追加）
    //   customer-contacts/opt-out-check            可查询的豁免判定（GET，单个 / 批量）
    //
    // 判别顺序：字面量子路径（capabilities / opt-out-check / timeline / ingest）排在 {uuid} 段
    // **之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   customer-contacts                          —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   customer-contacts/opt-out-check/{uuid}      —— 判定按客户标识查，不按退订记录 id 查。
    //   customer-contacts/segments                 —— **按客户主档筛人群**属 15.3.1.1（未交付）：
    //     放行它等于让界面以为「客户档案已经筛好了」。
    //   customer-contacts/campaigns / customer-contacts/outreach
    //                                             —— 营销触达属 15.5.2.1（未交付）：
    //     放行它等于让界面以为「本模块能发触达」（本模块**只**产出判定与记录）。
    //   customer-contacts/opt-outs/{uuid}/delete   —— 退订是**凭据**，不提供删除；
    //     要解除只能走显式的 revoke（带原因、留痕）。
    //   任何非 UUID 段（如 `customer-contacts/not-a-uuid`）—— 必须 404，不能被宽松分支吃掉。
    //
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `customer-contacts`。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^customer\-contacts(?:\/(?:capabilities|opt-out-check|timeline|ingest)|\/contacts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/opt-outs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:revoke|events))?)?)$/,
  },
  {
    // 15.5.2.1 关怀与催办（crm 服务，卷 15 L3 锚点 15.5.2 下的触达子域）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 crm 那几条巨型 alternation 里
    //   并分支 —— 那些是超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点：紧跟 `crm/customer-contacts`（15.3.2.2 接触记录）那条元素之后 ----
    //   本批另外三条车道的锚点分别是：`crm/message-sends` 之后（15.1.1.2）、
    //   `crm/review-replies` 之后（15.2.1.2）、channel 的 `crm/ad-rules` 之后（16.1.1.1）
    //   —— 四个锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「care-reminders」的零命中核对结论（**实测**，非目测）----
    //   ① 字面量核对：本文件既有全文里 `care-reminders` 出现次数 = **0**（既有的是
    //      `customer-contacts` / `customer-tags` / `knowledge` / `review-requests` /
    //      `review-rules` / `buyer-emails` / `message-sends` / `cart-recovery` 一类），
    //      所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   ② 逐条实测：把本文件既有 services[] 的**全部 148 条**正则逐条抽出来，
    //      对本模块的**全部 12 个 path shape** 逐个 `new RegExp(pat).test(path.slice(prefix.length))`
    //      （与 `resolve()` 同一套判别口径：`path.startsWith(prefix)` 收窄后再测正则），
    //      结果是**零命中** —— 既没有既有条目会放行本模块的路径，
    //      也没有本模块的路径会被既有条目误吞。
    //      （实测脚本：`.tmp/verify-api-proxy.mjs`，随车道提交前可重跑复现。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   care-reminders/capabilities                    能力声明（各维度的依据与下游归属）
    //   care-reminders/rules                           触达规则列表（GET，真分页 + 真筛选）
    //   care-reminders/rules/{uuid}                    单条触达规则（GET）
    //   care-reminders/rules/{uuid}/status             启用 / 停用规则（POST）
    //   care-reminders/rules/{uuid}/generate           按规则生成触达任务（POST）
    //   care-reminders/tasks                           触达任务列表（GET，真分页 + 真筛选）
    //   care-reminders/tasks/{uuid}                    单条触达任务（GET）
    //   care-reminders/tasks/{uuid}/dispatch           人工交接登记发送结果（POST）
    //   care-reminders/stats                           统计汇总（GET，**服务端算**）
    //   care-reminders/stats/events                    录入打开 / 点击事件（POST）
    //   care-reminders/stats/platform-events           平台回执自动采集（GET，恒「未接入」）
    //   care-reminders/stats/tasks/{uuid}/events       某条触达的打开 / 点击事件（GET）
    //
    // 判别顺序：字面量子路径（capabilities / stats / stats/events / stats/platform-events）
    // 排在 {uuid} 段**之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `rules` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   care-reminders                                  —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   care-reminders/finance                          —— **12 财务未交付**：
    //     催付只落任务与「已提醒回款」的意图，**没有**财务单据路径可放行。
    //   care-reminders/segments                         —— 按客户主档筛人群属 15.3.1.1（未交付）。
    //   care-reminders/outreach                         —— 「群发触达」与 15.1.1.2 消息发送
    //     （既有 `crm/message-sends` 条目）**不是**本模块的职责：本模块只排期与登记。
    //   care-reminders/rules/{uuid}/delete              —— 触达任务不提供删除（事实不可删）。
    //   care-reminders/tasks/{uuid}/delete              —— 同上。
    //   任何非 UUID 段（如 `care-reminders/rules/not-a-uuid`）—— 必须 404。
    //
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `care-reminders`。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^care\-reminders(?:\/(?:capabilities|stats\/events|stats\/platform-events|stats)|\/stats\/tasks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/events|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|generate))?)?|\/tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/dispatch)?)?)$/,
  },
  {
    // 15.5.1.1 自动回复（crm 服务，卷 15 L3 锚点 15.5.1 下的自动回复子域）。
    //
    // ⚠️ 本条目是**新增一条独立的 `services[]` 元素**，不是往既有 crm 那几条巨型 alternation 里
    //   并分支 —— 那些是超长单行正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点：紧跟 `crm/care-reminders`（15.5.2.1 关怀与催办）那条元素之后 ----
    //   本车道选的是**同卷 15.5 簇**里的锚点（15.5.2.1），与另外三条车道
    //   （15.1.2.2 邮件规则 / 15.4.1.1 工单分配 / 16.1.2.1 合规）各自的锚点互不重叠。
    //
    // ---- 首 token「auto-replies」的零命中核对结论（**实测**，非目测）----
    //   ① 字面量核对：本文件既有全文里 `auto-replies` 出现次数 = **0**
    //      （既有的是 `buyer-emails` / `knowledge` / `review-requests` / `review-rules` /
    //      `message-inbox` / `message-sends` / `message-classifications` / `review-replies` /
    //      `reviews` / `customer-tags` / `customer-contacts` / `care-reminders` /
    //      `cart-recovery` 一类），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   ② 逐条实测：把本文件既有 services[] 的**全部正则**逐条抽出来，对本模块的
    //      **全部 14 个 path shape** 逐个 `new RegExp(pat).test(path.slice(prefix.length))`
    //      （与 `resolve()` 同一套判别口径），结果是**零命中**。
    //      （实测脚本：`.tmp/verify-api-proxy.mjs`，随车道提交前可重跑复现。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   auto-replies/capabilities               能力声明（各维度的依据与下游归属）
    //   auto-replies/settings                  全局开关现状（🔴 一键关闭）
    //   auto-replies/settings/toggle           一键开关（POST；关闭时必须带原因）
    //   auto-replies/rules                     规则列表（GET，真分页 + 真筛选）/ 新建（POST）
    //   auto-replies/rules/{uuid}              规则详情（GET）/ 改（PUT）
    //   auto-replies/calendar                  值班日历列表（GET）/ 登记（POST）
    //   auto-replies/calendar/{uuid}           删除值班日历条目（DELETE）
    //   auto-replies/trigger                   触发一次自动回复评估（POST）
    //   auto-replies/records                   自动回复记录列表（GET，真分页 + 真筛选）
    //   auto-replies/records/{uuid}            单条自动回复记录（GET）
    //   auto-replies/records/{uuid}/escalate   人工手动标记转人工（POST）
    //   auto-replies/escalations               转人工标记列表（GET，真分页 + 真筛选）
    //   auto-replies/takeovers                 人工接管标记列表（GET）/ 登记（POST）
    //   auto-replies/stats                     统计汇总（GET，**服务端算**）
    //
    // 判别顺序：字面量子路径（capabilities / stats / trigger / settings / settings\/toggle /
    // takeovers / escalations）排在 {uuid} 段**之前** —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `rules` 这种非 UUID 形状也当成 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   auto-replies                          —— 裸路径：后端没有「一条不存在的汇总列表接口」。
    //   auto-replies/rules/{uuid}/delete      —— 规则不提供删除（历史记录要引用它的编码）。
    //   auto-replies/records/{uuid}/delete    —— 自动回复记录是**只追加**的事实，不提供删除。
    //   auto-replies/escalations/{uuid}       —— 转人工标记没有详情端点（列表里已带全部字段）。
    //   auto-replies/takeovers/{uuid}         —— 人工接管标记没有详情端点（同上）。
    //   auto-replies/platform-policy          —— 平台对纯自动回复的限制**本仓拿不到**
    //     （channel/vendor 下只有 OAuth 适配器）：放行它等于让界面以为「限制已知」。
    //   auto-replies/webhooks                 —— 平台站内信 webhook **未接入**。
    //   任何非 UUID 段（如 `auto-replies/rules/not-a-uuid`）—— 必须 404。
    //
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `auto-replies`。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^auto\-replies(?:\/(?:capabilities|stats|trigger|settings\/toggle|settings|takeovers|escalations|calendar(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|records(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/escalate)?)?))$/,
  },
  {
    // 7.8.2.2 缺货与饥饿预警（warehouse 服务，7.8.2 的第二个 L3 锚点）。
    //
    // 蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，
    // 区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。
    // 输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 warehouse 那条巨型正则里并 ——
    // 那一行被好几条车道共用，同时改它必然撞车。插入锚点固定在 services[] 的**最末尾**
    // （紧挨着 `];`），与另外三条车道的插入点互不重叠。
    //
    // 形状逐条列出（11 个）：
    //   capabilities            能力声明（两类判定的差别 / 未知态语义 / 未交付的三环）
    //   evaluate                一次评估（POST）—— 分别判定「缺货」与「饥饿」，各自落各自的表
    //   starvations             饥饿店铺清单（GET）
    //   starvations/{uuid}      单条详情（GET）
    //   starvations/{uuid}/acknowledge   认领（POST）
    //   starvations/{uuid}/resolve       处置（POST，处置说明必填）
    //   stockouts               缺货清单（GET）
    //   stockouts/{uuid}        单条详情（GET）
    //   stockouts/{uuid}/acknowledge     认领（POST）
    //   stockouts/{uuid}/resolve         处置（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / evaluate / starvations / stockouts）排在
    // {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 两段形状（{uuid}/acknowledge、{uuid}/resolve）挂在单段 {uuid} 组的可选后缀里，
    // 否则会被单段分支吃掉半截、把动作名留在匹配之外。
    //
    // 刻意**不放行**的形状：
    //   裸路径 inventory/stockout-alerts —— 后端没有这个映射（清单在 /starvations 与 /stockouts 两个子路径上）。
    //   任何 {uuid} 之外的第三层子路径 —— 后端只有 acknowledge 与 resolve 两个动作。
    //   任何 DELETE / PUT 形状 —— 后端根本没有对应端点（405），而 V192 的 REVOKE DELETE
    //   与不可删触发器还会各挡一次。注意白名单是**按路径登记、不区分方法**的，
    //   所以「不能删」的防线在服务端与 DB，不在这里。
    //
    // 与既有分支不重叠：既有 warehouse 那条巨型正则的 alternation 里
    // 没有 `stockout-alerts`（`stocktakes` / `stocktake-variances` 与它不是一回事），
    // 所以 `inventory/stockout-alerts/...` 不会先被既有分支吃掉。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^inventory\/stockout-alerts\/(?:capabilities|evaluate|starvations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?|stockouts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?)$/,
  },
  {
    // 7.7.2.2 平台托管与全托管备货（warehouse 服务，卷 07「库存核算」的 L3 锚点 7.7.2）。
    //
    // 蓝图：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，
    // 需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。
    // **全托管下"备货"不等于"自有库存"**，平台仓在库数是平台回执的观测值，不得并入自有库存余额；
    // 结算按平台规则（可能按件或按日）而非我方发货确认。」
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 warehouse 那条巨型 alternation 里并分支 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 里**最后一条 `prefix: 'warehouse/'` 元素之后**
    // （7.8.2.2 的 `inventory/stockout-alerts` 元素之后），与同批其它车道（trade / channel / catalog 组）
    // 的插入点互不重叠。
    //
    // 🔴 为什么用 `consignment-stock` 这个独立前缀：
    //   7.7.1.2 寄售与代销已交付，它的命名空间是 `inventory/consignments`（复数，寄售协议/销售/结算/退货）。
    //   本模块是**平台托管备货**（备货单 / 平台仓收发回执 / 平台结算回执），
    //   与寄售**不是同一件事**：寄售的货主是第三方（ consignments 走 consignment_agreement），
    //   平台托管的货主是平台本身、全托管下货权随货交给平台。
    //   两个名字只差一个词，挨太近会让人把寄售协议与平台托管备货单当成同一件事，
    //   所以本模块用 `consignment-stock` 单独占一段命名空间。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   consignment-stock/capabilities            能力声明（四口径定义 / 结算口径 / 分账声明 / 未接入环节）
    //   consignment-stock/shops/{uuid}             店铺经营模式读出口（2.3.2.1，只读）
    //   consignment-stock/forecast-preview         备货预报预填预览（10.4.2.2，只读）
    //   consignment-stock/orders                   备货单集合（GET 真分页 / POST 建单）
    //   consignment-stock/orders/{uuid}            单据详情（GET：头部 + 明细 + 台账 + 对账 + 审计）
    //   consignment-stock/orders/{uuid}/lines      追加备货明细行（POST，仅草稿态）
    //   consignment-stock/orders/{uuid}/ship       发货（POST，DRAFT → SHIPPED）
    //   consignment-stock/orders/{uuid}/confirm-receipt  平台确认收货（POST）
    //   consignment-stock/orders/{uuid}/receipts   平台仓收发回执（GET 列表 / POST 登记）
    //   consignment-stock/orders/{uuid}/settlement-receipts  平台结算回执（GET 列表 / POST 登记）
    //   consignment-stock/orders/{uuid}/ledger      托管备货台账（GET，四口径分列）
    //   consignment-stock/orders/{uuid}/reconciliation  与自有库存的对账结果（GET）
    //   consignment-stock/orders/{uuid}/settlement-basis  结算口径标记（GET，供 12.3.1 结算）
    //   consignment-stock/orders/{uuid}/close       关闭备货单（POST）
    //   consignment-stock/orders/{uuid}/cancel      作废备货单（POST）
    //   consignment-stock/orders/{uuid}/audit       审计时间线（GET）
    //
    // 刻意**不放行**的形状：
    //   consignment-stock/orders/{uuid}/delete      —— 后端没有删除端点（405）；
    //     且平台回执 / 结算回执是 append-only 凭据，运行角色的 UPDATE / DELETE 已在 V244 里收回。
    //   consignment-stock/orders/{uuid}/inventory   —— **把平台在库并进自有库存**这件事后端根本没有实现，
    //     而且蓝图加粗句明令禁止；放行它只会让人以为「有办法把平台仓的数写进我方库存」。
    //   consignment-stock/orders/{uuid}/settle      —— 12.3.1 结算未交付，且是**独立数据库**：
    //     跨服务写账被模块边界纪律明令禁止。本模块只出「结算口径标记 + 未结量」给 12 读。
    //   consignment-stock/orders/{uuid}/profit      —— 13.4.1.1 多模式利润未交付。
    //   consignment-stock/orders/{uuid}/forecast    —— 10.4.2.2 备货预报在 **channel** 服务，
    //     本服务只经只读端口取数，warehouse 不提供预报单写入端点。
    //   consignment-stock/{任意其它字面量}          —— 拼错的子路径一律 404；
    //     放行后端不存在的形状比 404 更糟：界面会以为功能已交付。
    //
    // 判别顺序：**字面量子路径（capabilities / forecast-preview / orders）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 与既有分支不重叠：既有 warehouse 那条巨型正则的 `inventory/` 分组里有 `consignments(...)`（复数）
    // 与 `vmi(...)`，**没有** `consignment-stock` —— 两个前缀在第 12 个字符处分叉
    // （`consignments` 的第 12 位是 `s`，`consignment-stock` 的是 `-`），
    // 所以 `inventory/consignment-stock/...` 不会先被既有分支吃掉。
    //
    // 兄弟车道已交付的形状**未被挤掉**（回归基线，见
    // apps/web/src/app/api/warehouse/consignment-stock-allowlist.test.ts 的最后一组断言）：
    //   inventory/vmi/...            7.7.2.1 VMI 供应商管理库存
    //   inventory/consignments/...   7.7.1.2 寄售与代销
    //   inventory/stockout-alerts/... 7.8.2.2 缺货与饥饿预警
    //   inventory/ownership/...      7.7.1.1 货主与所有权
    //   inventory/allocation-*/...   7.8.x 多平台可售量分摊
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^inventory\/consignment-stock\/(?:capabilities|forecast-preview|shops\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:lines|ship|confirm-receipt|close|cancel|receipts|settlement-receipts|ledger|reconciliation|settlement-basis|audit))?)?)$/,
  },
  {
    // 14.1.1.2 活动详情（channel 服务）。纯**新增一条独立的 `services[]` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-keywords`（14.1.3.1）、`ad-rules`（14.2.2.2）、
    // `ad-type-diffs`（14.1.2.2）等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点固定在 `compliance-precheck`（16.2.2.1）那条**之前** ----
    //   同批其它车道的插入点分别是「`sku-cost-compositions` 那条之前」「`location-allocations` 那条之前」
    //   与「`ad-campaigns` 那条不动」，彼此相距数百行 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-campaign-details」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-campaign-details` 这个串
    //   （既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-campaign-details/capabilities                   能力声明（未交付段 available=false + ownerModule）
    //   ad-campaign-details/campaigns/{uuid}               单活动全景（GET：基本设置 + 预算与消耗曲线 + 每日明细 + 结构条数）
    //   ad-campaign-details/campaigns/{uuid}/groups        广告组列表（GET，真分页 + 真筛选）
    //   ad-campaign-details/campaigns/{uuid}/products      投放商品列表（GET，真分页 + 真筛选）
    //   ad-campaign-details/campaigns/{uuid}/creatives     创意列表（GET，真分页 + 按类型筛选）
    //   ad-campaign-details/campaigns/{uuid}/keywords      关键词列表（GET，读上游 14.1.3.1）
    //   ad-campaign-details/campaigns/{uuid}/change-history 平台侧修改历史（GET，**恒为 available=false** + 本地操作审计）
    //   ad-campaign-details/campaigns/{uuid}/structure      登记结构（POST，结构数据的真实写入口）
    //   ad-campaign-details/campaigns/{uuid}/bids          改竞价（POST，逐条结果 + 护栏 + 审计）
    //   ad-campaign-details/campaigns/{uuid}/copy          复制结构（POST，结构副本真落库 + 平台侧待回写）
    //
    // 判别顺序：字面量子路径（capabilities）排在 {uuid} 段之前 —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-campaign-details` —— 本模块没有裸路径资源。
    //   * `ad-campaign-details/campaigns` —— 活动列表归 14.1.1.1 的 `ad-campaigns` 元素，本模块不重开一条。
    //   * `ad-campaign-details/campaigns/{uuid}/budget` —— 预算曲线随全景一起返回，不单独开一条。
    //   * `ad-campaign-details/campaigns/{uuid}/platform-history` —— 平台侧改历史**没有同步源**，
    //     放行它会让界面以为「平台侧改历史」已交付。
    //   * `ad-campaign-details/campaigns/{uuid}/writeback` —— 平台回写队列未交付（平台适配层）。
    //   * `ad-campaign-details/campaigns/{uuid}/groups/{uuid}` —— 广告组的单条详情与增删改归 14.1.2.1（未交付）。
    //   * `ad-campaign-details/campaigns/{uuid}/copy/preview` —— 变更预览属 14.1.1.3（未交付）。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ `campaigns` 后面**必须**跟 id 段（uuid 段不可省）：本模块没有「活动列表」这个资源，
    //    那是 14.1.1.1 的 `ad-campaigns` 元素的活。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-campaign-details`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-campaign\-details(?:\/capabilities|\/campaigns\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:groups|products|creatives|keywords|change-history|structure|bids|copy))?)$/,
  },
  {
    // 16.2.2.1 合规预检（channel 服务，卷 16「合规与风控」的 L3 锚点 16.2.2）。
    //
    // 蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
    // 标签与说明书要求，多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。
    // 输出：体检报告（分维度得分 + 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
    // **预检在保存草稿时就跑一次**（即时反馈），发布时再跑一次（强制校验）。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 7.8.2.2 的 `inventory/stockout-alerts` 元素
    // 之后、`];` 之前），与另外三条车道的插入点互不重叠。
    //
    // 🔴 为什么用 `compliance-precheck` 这个独立前缀：
    //   * `listing-compliance-precheck` 是同批 4.2.3.2 车道的前缀（卷 04 的「合规预检（违禁词/侵权）」）——
    //     那是**另一个模块**：那边出「阻断项 + 提醒项」的风险清单，这边出「分维度得分 + 问题清单 +
    //     整改建议」的体检报告。两边不共用表、不共用端点、不共用页面，也不共用白名单元素。
    //   * `listing-*` 开头的既有前缀已有 8 条，视觉上再挤一条进去更容易看错行。
    //   * `compliance-*` 也不选：16.2.1.1 规则库未来会占那个命名空间。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   compliance-precheck/capabilities            能力声明（逐维度量得到 / 量不到 + 归属模块）
    //   compliance-precheck/drafts                  可供体检的内容草稿（GET，4.2.1.1 的草稿，只读）
    //   compliance-precheck/drafts/{uuid}/check      🔴 保存草稿时的即时反馈（POST）
    //   compliance-precheck/drafts/{uuid}/precheck   🔴 发布前的强制校验（POST）
    //   compliance-precheck/reports                 报告历史（GET，真分页）
    //   compliance-precheck/reports/{uuid}          单份报告（GET：分维度得分 + 问题清单 + 整改建议）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / drafts / reports）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ `drafts/{uuid}` 的**动作段是必选的**（`(?:check|precheck)` 不带 `?`）：
    //    后端没有「按草稿取单条体检入口」这个资源，放行 `drafts/{uuid}` 就是凭空多出一条 GET。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `compliance-precheck` —— 本模块没有裸路径资源。
    //   * `compliance-precheck/drafts/{uuid}` —— 没有「按草稿取单条体检入口」这个资源。
    //   * `compliance-precheck/reports/{uuid}/publish|block|exempt|approve` ——
    //     🔴 拦截、豁免与审批归 16.2.1.2（未交付），本模块只出报告与结论。
    //   * `compliance-precheck/rules|library|certifications` ——
    //     🔴 16.2.1.1 规则库与 16.2.2.2 认证与资质都未交付，本模块不代它们开形状。
    //   * `compliance-precheck/reports/{uuid}/delete` —— 报告是那一次判定的凭据，不可删。
    //   * `listing-compliance-precheck/...` —— 同批 4.2.3.2 车道的形状，不是本模块的。
    //
    // 与既有分支不重叠：既有 channel 分支的 alternation 里没有 `compliance-precheck`，
    // 所以 `compliance-precheck/...` 不会先被既有分支吃掉。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^compliance-precheck(?:\/capabilities|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:check|precheck))?|\/reports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 14.3.2.2 效率诊断（channel 服务，卷 14「广告与增长」的 L3 锚点 14.3.2）。
    //
    // 蓝图：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退，
    // 给出可执行建议与一键调整。输入：14.3 报表指标；诊断规则。输出：诊断清单 + 建议动作。
    // 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。**诊断要给动作而不是只报警**，
    // 否则用户看完还是要自己想怎么办。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 10.4.2.2 的 `stock-forecasts` 元素**之前**（`prefix: 'channel/'`，
    // pattern 首 token 为 `^stock-forecasts`，全文件唯一）——
    // 与同批 14.1.1.2 活动详情（`ad-campaign-details`，落在 `compliance-precheck` 之前）相隔 60+ 行，
    // 与另外三条车道（catalog 调价单 / catalog 价格历史 / trade 采购计划）也互不重叠，git 能干净求并。
    //
    // ---- 首 token「ad-diagnostics」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-diagnostics` 这个串
    //   （既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` / `ad-campaign-details`
    //   一类的其它命名），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-diagnostics/capabilities                能力声明（未交付段 available=false + ownerModule + impact）
    //   ad-diagnostics/rules                       规则集合读（GET）/ 登记规则（POST）
    //   ad-diagnostics/rules/defaults              初始化默认诊断规则（POST，幂等：已存在的不覆盖）
    //   ad-diagnostics/rules/{uuid}                规则详情（GET）
    //   ad-diagnostics/rules/{uuid}/status         启用 / 停用一条规则（POST）
    //   ad-diagnostics/runs                        运行历史（GET）/ 跑一次诊断（POST）
    //   ad-diagnostics/runs/{uuid}                 运行详情（GET：逐条规则的评估结论 + 结论 + 建议动作）
    //   ad-diagnostics/diagnoses                   诊断清单（GET，真分页 + 真筛选）
    //   ad-diagnostics/diagnoses/{uuid}            诊断详情（GET：结论 + 观察量快照 + 建议动作）
    //   ad-diagnostics/actions                     建议动作集合读（GET，真分页 + 真筛选）
    //   ad-diagnostics/actions/{uuid}              建议动作详情（GET）
    //   ad-diagnostics/actions/{uuid}/apply        🔴 一键调整（POST，走 14.1.1.1 已交付的写路径与护栏）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / rules / rules/defaults / runs / diagnoses / actions）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-diagnostics`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-diagnostics` —— 本模块没有裸集合读，一切入口都带子路径。
    //   * `ad-diagnostics/rules/{uuid}/delete` —— 规则是配置事实，撤下走 status=DISABLED，不物理删除。
    //   * `ad-diagnostics/diagnoses/{uuid}/delete` —— 诊断结论是那一次判定的凭据，不可删。
    //   * `ad-diagnostics/diagnoses/{uuid}/apply` —— 调整挂在**建议动作**上，不挂在结论上。
    //   * `ad-diagnostics/actions/{uuid}/dispatch` / `/sync` —— 平台侧真实下发**未交付**
    //     （平台回写队列未接通），放行它只会让界面以为「已回写平台」。
    //   * `ad-diagnostics/actions/{uuid}/bid` —— 竞价调整归 14.2.1.1 竞价管理（**未交付**），
    //     本模块**不跳过它直接写平台**。
    //   * `ad-diagnostics/creative-decay` —— 创意衰退的数据源未就绪（14.1.1.2 未交付），
    //     没有这个资源；它只是诊断清单里一条被显式标为「数据源未就绪」的规则。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-diagnostics(?:\/(?:capabilities|rules(?:\/defaults|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|runs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|diagnoses(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|actions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/apply)?)?))$/,
  },
  {
    // 10.4.2.2 备货预报（channel 服务，卷 10「物流与货运」的 L3 锚点 10.4.2）。
    //
    // 蓝图：「向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，
    // 接收仓端接收与拒收反馈。」加粗句：「**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），
    // 规格不符会被拒收造成额外成本与延误。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 16.2.2.1 的 `compliance-precheck` 元素之后、10.7.1.3 的 `return-disposals`
    // 元素之前 —— 与另外三条车道（catalog 调价单 / catalog 价格历史 / trade 采购计划）的
    // 插入点互不重叠，git 能干净求并。
    //
    // 🔴 为什么用 `stock-forecasts` 这个独立前缀：
    //   * 既有 10.4.2.1 海外仓连接走的是 `warehouse-providers`（服务商目录 / 连接 / 仓账户 /
    //     库存同步 / 外部库存观测），本模块是**它的下游动作**（把货备到那个仓去），
    //     两者语义不同、不共用端点、不共用表，必须分得开。
    //   * 既有 `warehouse-providers` 元素的首 token 是 `warehouse-providers`，本元素的首 token
    //     是 `stock-forecasts`，**首 token 不同就互不遮蔽**（既有正则都是锚定的 `/^token(...)?$/`）。
    //   * 已用脚本逐条核对本文件里**全部**既有正则：`stock-forecasts/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   stock-forecasts                              集合读（GET，真分页 + 真筛选 + 真排序）/ 建预报单（POST）
    //   stock-forecasts/capabilities                 能力声明（未接通维度 available=false + ownerModule + impact）
    //   stock-forecasts/accounts                     目的仓下拉项（10.4.2.1 的仓账户，同库同服务）
    //   stock-forecasts/specs                        备货规格集合读（GET）/ 新建规格版本（POST）
    //   stock-forecasts/specs/{uuid}/retire          停用一版规格（POST）
    //   stock-forecasts/{uuid}                       预报单详情（GET）
    //   stock-forecasts/{uuid}/lines                 替换明细（PUT，**只在草稿态允许**）
    //   stock-forecasts/{uuid}/spec-check            规格校验结果（GET，只读不改状态）
    //   stock-forecasts/{uuid}/validate              过规格闸（POST；规格不过 ⇒ 422 + violations 数组）
    //   stock-forecasts/{uuid}/submit                提交预报 → 生成平台货件创建任务（POST）
    //   stock-forecasts/{uuid}/cancel                作废预报单（POST）
    //   stock-forecasts/{uuid}/availability          备货可用性（GET，自营可售量 + 头程在途，只读 Port）
    //   stock-forecasts/{uuid}/shipment-task         平台货件创建任务（GET）
    //   stock-forecasts/{uuid}/shipment-task/dispatch 推进货件任务（POST；服务商侧失败 ⇒ 502 + 任务体）
    //   stock-forecasts/{uuid}/shipment-task/retry   重试失败的货件任务（POST）
    //   stock-forecasts/{uuid}/shipment-reports      平台货件报送行（GET 集合读 / POST 登记）
    //   stock-forecasts/{uuid}/receipts              仓端接收/拒收回执（GET 集合读 / POST 登记）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / accounts / specs）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `stock-forecasts/{uuid}/delete` —— 预报单靠状态机收口（作废），不靠物理删除；
    //     仓端回执是 append-only 的凭据，更不能删。
    //   * `stock-forecasts/{uuid}/shipment-task/{任意动作}` —— {uuid} 之下只认
    //     dispatch 与 retry 两个动作；多放一层会让一个还不存在的动作看起来像已交付。
    //   * `stock-forecasts/{uuid}/receipts/{任意}` —— 回执按预报单寻址，没有单条回执端点。
    //   * `stock-forecasts/{uuid}/platform-shipment` —— 平台货件接口直连**未接通**
    //     （本仓库没有真实平台账号与网络出口），放行它只会让界面以为已交付。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^stock-forecasts(?:\/capabilities|\/accounts|\/specs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/retire)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:lines|spec-check|validate|submit|cancel|availability|shipment-task(?:\/(?:dispatch|retry))?|shipment-reports|receipts))?)?$/,
  },
  {
    // 10.7.1.3 弃置与销毁（channel 服务，卷 10「物流与货运」的 L3 锚点 10.7.1）。
    //
    // 蓝图：「管理弃置与销毁：无销售价值/品牌方要求/法规不允许再销售的退货，处理销毁、
    // 留存销毁凭证/照片/重量记录，满足平台与品牌方的销毁证明要求；销毁数量可作为品牌方索赔依据。
    // 输入：待销毁清单；销毁方式（压碎/焚烧/专业销毁）；凭证要求。
    // 输出：销毁单 + 凭证 + 数量/重量记录；索赔依据（→ 6.1.2.3 / 11.2.3.3）。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 16.2.2.1 的 `compliance-precheck` 元素
    // 之后、`];` 之前），与另外三条车道的插入点互不重叠。
    //
    // 🔴 为什么用 `return-disposals` 这个独立前缀：
    //   * 既有 `return-dispositions` 是 **10.7.1.1 退货接收与处置决策**（决定去哪儿），
    //     `return-shipbacks` 是 **10.7.1.2 退运回国**（真的把货凑成一批运回国），
    //     本模块是 **10.7.1.3 弃置与销毁**（真的把货毁掉并**留证**）—— 三个模块
    //     不共用表、不共用端点、不共用页面，语义上必须分得开。
    //   * 既有两条正则都是**锚定**的（`/^return\-dispositions(...)$/`、`/^return\-shipbacks(...)$/`），
    //     首 token 不同就**不会互相吃掉**：`return-disposals` 与 `return-dispositions` 差一个尾字母。
    //   * `return-quality`（11.2.3.1 质检）与 `return-receipts`（11.2.2.1 退货入库）在 **trade** 服务，
    //     命名空间本就不同；`refunds` / `supplier-claims` 更是无关。
    //   * 已用脚本逐条核对本文件里**全部 73 条**既有正则：`return-disposals/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   return-disposals/capabilities              能力声明（凭证闸 / 枚举词表 / 未交付下游逐条写明 ownerModule）
    //   return-disposals/overview                  全量概览（不受筛选与分页影响）
    //   return-disposals/candidates                待销毁清单（只取 10.7.1.1 判为「弃置销毁」的处置单）
    //   return-disposals/policies                  凭证要求集合读（GET，真分页 + 筛选）/ 登记（POST）
    //   return-disposals/policies/{uuid}/revoke    撤销凭证要求（POST，必须写理由）
    //   return-disposals/orders                    销毁单集合读（GET，真分页 + 筛选）/ 建销毁单（POST）
    //   return-disposals/orders/{uuid}             销毁单详情（GET，含凭证 / 费用 / 索赔依据 / 留痕）
    //   return-disposals/orders/{uuid}/schedule    排期（POST，**凭证要求在这一刻冻结**）
    //   return-disposals/orders/{uuid}/evidence    登记一类凭证（POST）
    //   return-disposals/orders/{uuid}/costs       记一笔销毁费用（POST）
    //   return-disposals/orders/{uuid}/claim-basis 生成索赔依据（POST）
    //   return-disposals/orders/{uuid}/dispose     执行销毁（POST，🔴 凭证不齐直接 422）
    //   return-disposals/orders/{uuid}/cancel      取消销毁单（POST，必须写理由）
    //   return-disposals/claim-bases/{uuid}/submit 提交下游索赔（POST，回填下游单号）
    //   return-disposals/claim-bases/{uuid}/reject 下游驳回（POST，必须写理由）
    //   return-disposals/events                    留痕集合读（GET，真分页 + 筛选）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / overview / candidates / policies / events）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `return-disposals` 就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `return-disposals` —— 本模块没有裸路径资源。
    //   * `return-disposals/orders/{uuid}/costs/{uuid}` —— 费用没有独立删除端点。
    //   * `return-disposals/orders/{uuid}/repatriation` —— 「退运回国」是 10.7.1.2 的主权。
    //   * `return-disposals/policies/{uuid}` —— 凭证要求**按主体定位**（品牌 / 平台 / 租户默认），
    //     没有「按 id 取单条」这个端点。
    //   * `return-disposals/claim-bases/{uuid}` —— 索赔依据挂在销毁单下，按销毁单寻址。
    //   * `return-disposals/claim-bases/{uuid}/delete` —— 依据不可删（删了就等于没索赔过）。
    //   * `return-disposals/orders/{uuid}/delete` —— 销毁单不可删（不可删是状态机的一部分）。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^return\-disposals(?:\/(?:capabilities|overview|candidates|events)|\/policies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/revoke)?|\/orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:schedule|evidence|costs|claim-basis|dispose|cancel))?)?|\/claim-bases\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:submit|reject))$/,
  },
  {
    // 16.2.2.2 认证与资质：产品认证档案（CE / FCC / RoHS / PSE / FDA / CCC 等）、到期提醒、
    // 覆盖情况统计（哪些 SKU 缺认证）。🔴 蓝图加粗句「认证缺失是 listing 被抑制与下架的
    // 高频原因」「上架时不管、出事才补的典型滞后项」的落点。
    //
    // ⚠️ **本模块是独立一条 services[] 元素**，刻意**不**往既有 channel 长 alternation 里并分支：
    //   那条正则由多车道共用，并进去必然 git 冲突，且取错一侧会静默删掉别人已交付的路由
    //   （表现是页面 404，而 typecheck / lint / vitest / Playwright 全部全绿）。
    //   插入锚点固定在「文件里最后一条 prefix: 'channel/' 元素之后」，与另外三条车道互不重叠。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   product-certifications/capabilities          能力声明（到期口径 / 覆盖口径 / 词表口径 / 边界）
    //   product-certifications/overview              概览（档案数 / 即将过期 / 已过期 / 缺认证 SKU）
    //   product-certifications/types                 生效认证类型词表（内置 ∪ 租户字典）
    //   product-certifications/types/{code}          租户字典行：新增 / 改名 / 停用（PUT）、删除（DELETE）
    //   product-certifications/policy                租户级「即将过期」窗口
    //   product-certifications/certifications        认证档案台账（真分页 + 筛选）+ 登记（POST）
    //   product-certifications/certifications/{id}   认证详情（含适用 SKU / 类目 / 扫描件）+ 修改（PUT）
    //   product-certifications/certifications/{id}/revoke      撤销认证（必须写理由）
    //   product-certifications/certifications/{id}/skus        挂 / 解挂适用 SKU
    //   product-certifications/certifications/{id}/categories   挂 / 解挂对应类目要求
    //   product-certifications/certifications/{id}/documents   扫描件上传（JSON base64 / multipart）
    //   product-certifications/documents/{id}        扫描件元数据
    //   product-certifications/documents/{id}/file    扫描件本体（二进制，**浏览器代理不透传**）
    //   product-certifications/requirements           要求侧登记（真分页）+ 登记（POST）
    //   product-certifications/requirements/{id}      撤销一条要求登记（DELETE）
    //   product-certifications/coverage              覆盖情况统计（哪些 SKU 缺认证）
    //   product-certifications/coverage/skus/{id}    单 SKU 覆盖明细
    //   product-certifications/expiry-reminders      到期提醒（按剩余天数升序）
    //   product-certifications/sku-candidates        catalog SKU 候选（只读）
    //   product-certifications/category-candidates   catalog 内部类目候选（只读）
    //
    // 🔴 前缀**不与 16.2.2.1 的 `compliance-precheck` 撞车** —— 两者是同一 L3 下的两个模块，
    //   各占一条独立元素，谁也不遮蔽谁（16.2.2.1 的元素在文件更前面，本元素在其之后）。
    //
    // ⚠️ `documents/{id}/file` 虽然放行，但**浏览器代理不原样透传二进制**（代理只透传
    //    text/csv、text/vnd.zpl、text/html）：这条是给直连调用方（运维脚本 / 归档任务）用的，
    //    页面上的扫描件清单显示元数据，不靠它下载。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `product-certifications` —— 本模块没有裸路径资源。
    //   * `product-certifications/certifications/{id}/delete` —— 认证档案不可删（它是合规凭据，
    //     能做的只有撤销）。
    //   * `product-certifications/rules` / `library` —— 16.2.1.1 规则库未交付，本模块不代它开形状。
    //   * `product-certifications/publish-gate` —— 16.2.1.2 拦截执行未交付，
    //     本模块只出档案、提醒与覆盖统计，不执行拦截。
    //   * `product-certifications/certifications/{id}/renew` —— 续期是「再上传一份扫描件 +
    //     改有效期」，不是另一个动作。
    //   * 字面量子路径不得带 id 段（`capabilities/{id}`）。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `product-certifications`
    //    就是凭空多出一条资源（界面上会以为有一个「认证与资质」的裸列表端点）。
    //    判别顺序上，字面量子路径（capabilities / overview / types / policy / …）与
    //    {uuid} 段互不吃：字面量先判，两类不会互相吃掉。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^product\-certifications(?:\/(?:capabilities|overview|types|policy|certifications|requirements|coverage|expiry-reminders|sku-candidates|category-candidates)|\/types\/[A-Za-z0-9_-]{1,32}|\/certifications\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:revoke|skus(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|categories(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|documents))?|\/documents\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?|\/requirements\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/coverage\/skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 16.4.2.2 授权凭证：集中存储与核验授权凭证文件（真伪验证记录 / 文件版本 / 签署方信息 /
    // 一键核验）。🔴 蓝图加粗句「凭证需保留原始文件与元数据，争议时是唯一证据」的落点。
    //
    // ⚠️ **本模块是独立一条 services[] 元素**，刻意**不**往既有 channel 长 alternation 里并分支：
    //   那条正则由多车道共用，并进去必然 git 冲突，且取错一侧会静默删掉别人已交付的路由
    //   （表现是页面 404，而 typecheck / lint / vitest / Playwright 全部全绿）。
    //   插入锚点固定在「16.2.2.2 认证与资质那条元素之后」，既有条目一字未动。
    //
    // 🔴 根路径用 `brand-credentials`（**独立前缀**），与 16.4.2.1 的 `brand-authorizations`
    //     不重叠（两字面量互不为前缀，不会互相吃掉）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   brand-credentials/capabilities                 能力声明（真伪怎么判 / 结论口径 / 边界）
    //   brand-credentials/overview                     概览（凭证数 / 有效 / 临期 / 过期 / 撤销 / 核验三态）
    //   brand-credentials/credentials                  凭证档案列表（真分页 + 真筛选）+ 登记（POST）
    //   brand-credentials/credentials/{uuid}           凭证详情 + 修改（PUT）
    //   brand-credentials/credentials/verify-batch     批量核验（POST，一次最多 50 个）
    //   brand-credentials/credentials/{uuid}/revoke    撤销凭证（POST，必须写理由）
    //   brand-credentials/credentials/{uuid}/verify    🔴 一键核验（POST）
    //   brand-credentials/credentials/{uuid}/signatories            签署方清单（GET）+ 登记（POST）
    //   brand-credentials/credentials/{uuid}/signatories/{uuid}    移除签署方（DELETE）
    //   brand-credentials/credentials/{uuid}/documents              文件版本清单（GET）+ 上传（POST）
    //   brand-credentials/documents/{uuid}             凭证文件元数据
    //   brand-credentials/documents/{uuid}/file        凭证文件本体（二进制，**浏览器代理不透传**）
    //   brand-credentials/verifications                核验记录历史（真分页）
    //   brand-credentials/authorizations               上游 16.4.2.1 的授权档案候选（同库只读）
    //
    // ⚠️ `documents/{uuid}/file` 虽然放行，但**浏览器代理不原样透传二进制**（代理只透传
    //    text/csv、text/vnd.zpl、text/html）：这条是给直连调用方（归档任务 / 争议取证）用的，
    //    页面上的文件清单显示元数据与 sha256 摘要，不靠它下载。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `brand-credentials` —— 本模块没有裸路径资源（列表在 credentials 上）。
    //   * `brand-credentials/credentials/{uuid}/delete` —— 合规台账**不做物理删除**，
    //     撤销走 `revoke`；凭证是「争议时是唯一证据」的那份东西。
    //   * `brand-credentials/credentials/{uuid}/restore` —— 撤销是终态，不提供「恢复」。
    //   * `brand-credentials/verifications/{uuid}` —— 核验记录只有列表，没有单条详情端点。
    //   * `brand-credentials/documents/{uuid}/delete` —— 文件版本**只追加**，没有删除。
    //   * 非 UUID 段 —— `credentials/not-a-uuid` 必须 404，不能被宽松分支吃掉。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `brand-credentials`
    //    就是凭空多出一条资源（界面上会以为有一个「授权凭证」的裸列表端点）。
    //    判别顺序上，字面量子路径（capabilities / overview / verifications / authorizations）
    //    与 {uuid} 段互不吃；`credentials/verify-batch` 是字面量，排在 {uuid} 段之前判别。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^brand-credentials\/(?:capabilities|overview|verifications|authorizations|credentials(?:\/verify-batch|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:revoke|verify|signatories(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|documents))?)?|documents\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?)$/,
  },
  {
    // 14.4.1.1 促销列表（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有分支（`ad-budgets` / `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-reports` …）
    // **一字未动、也不重排** —— 同批多车道共享这一个文件，每条服务规则是一段超长单行正则，
    // 往同一条 alternation 里并分支会让 git 必然判冲突且 retry 永远不会自愈。
    //
    // ---- 插入锚点选在 **5.2.2.1 汇率查询**（`prefix: 'catalog/'`）那条**之前** ----
    //   本批其它三条车道（14.1.2.1 广告组管理 / 14.1.1.3 批量操作 / 14.3.1.2 广告组与词报表）
    //   都是 **ads 域**的 `channel/` 元素，插入点会落在广告簇附近（`ad-budgets` 那条在第 544 行、
    //   `ad-keywords` 那条在第 638 行附近）；本条落在 **5200 行开外的 catalog 区**，
    //   彼此相距数千行 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-promotions」的零命中核对结论 ----
    //   本文件既有 129 条 services[] 正则里**没有任何一条**含 `ad-promotions` 这个串
    //   （既有的是 `ad-campaigns` / `ad-budgets` / `ad-keywords` / `ad-rules` / `ad-reports` /
    //   `ad-type-diffs` / `ad-campaign-details` / `ad-diagnostics` 一类），
    //   且逐条用 `new RegExp(pat).test('ad-promotions…')` 实测过：既有 129 条对
    //   `ad-promotions`、`ad-promotions/capabilities`、`ad-promotions/promotions`、
    //   `ad-promotions/promotions/{uuid}`、`ad-promotions/promotions/{uuid}/status`、
    //   `ad-promotions/guardrails` **全部零命中** ⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-promotions/capabilities              能力声明（毛利 / 平台效果 / 平台回写 / 促销费用
    //                                            四个维度 available=false + ownerModule + 影响）
    //   ad-promotions/options                   下拉项（促销类型 / 状态 / 同步状态 / 规则 / 严重级别）
    //   ad-promotions/promotions                促销列表（GET，真分页 + 真筛选）/ 登记促销（POST）
    //   ad-promotions/promotions/{uuid}         促销详情（GET）/ 改促销定义（POST，替换语义）
    //   ad-promotions/promotions/{uuid}/status  改本地状态（POST；排期 / 上线被拦截时 409）
    //   ad-promotions/promotions/{uuid}/check   重跑拦截 / 预警检查（POST）
    //   ad-promotions/guardrails                护栏规则（GET 读 / POST 登记或修改）
    //
    // 判别顺序：字面量子路径（capabilities / options / guardrails / promotions）排在 {uuid} 段之前
    // —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-promotions` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-promotions/effects` —— **平台效果维度未交付**（没有平台促销同步源 ⇒ 不建空的效果表）
    //   * `ad-promotions/promotions/{uuid}/delete` —— 促销是事实，撤下走 status=CANCELLED
    //   * `ad-promotions/promotions/{uuid}/expenses` —— **促销费用维度未交付**（12 财务不在本仓）
    //   * 非 UUID 段 —— `ad-promotions/promotions/not-a-uuid` 必须 404
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-promotions`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-promotions(?:\/(?:capabilities|options|guardrails|promotions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|check))?)?))$/,
  },
  {
    // 5.2.2.1 汇率查询：各币种对各本位币的汇率（方向 + 汇率 + 生效日 + 来源）、来源配置与外部拉取。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   exchange-rates                                 汇率台账（集合读）+ 手工录入（POST）
    //   exchange-rates/capabilities                    能力声明（方向 / 唯一性 / 反向 / 下游状态）
    //   exchange-rates/latest                          某币种对最新的一条汇率（asOf 查询）
    //   exchange-rates/functional-currencies           各币种对各本位币的汇率（读 identity 的法人本位币）
    //   exchange-rates/sources                         来源配置列表（GET）+ 新建来源（POST）
    //   exchange-rates/sources/initialize              初始化内置来源（POST，幂等）
    //   exchange-rates/sources/{code}                  修改来源配置（PUT；按**来源代码**寻址，不是 uuid）
    //   exchange-rates/fetch                           外部拉取（POST）
    //   exchange-rates/fetch-log                       拉取日志（只读）
    //   exchange-rates/{id}                            汇率详情（GET）+ 更正（PUT）
    //
    // 🔴 `capabilities` / `latest` / `sources` / `fetch` 等字面量段排在 uuid 分支**之前**判别：
    //     字面量先判，两类不会互相吃掉。
    // 🔴 `sources/{code}` 的 code 是**来源代码**（如 BANK_OF_CHINA），不是 uuid ——
    //     所以这一段用字符集而不是 {uuid} 段。
    //
    // 刻意**不**放行：5.2.2.2 汇率快照尚未交付（它上游是本模块）——
    // 放行后端不存在的路径比 404 更糟，界面会以为功能已交付。
    //
    // 与既有分支不重叠：既有 `catalog/` 长正则里没有 `exchange-rates`，
    // 所以本元素既不会被它遮蔽，也不会遮蔽它。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^exchange\-rates(?:\/(?:capabilities|latest|functional\-currencies|sources(?:\/initialize|\/[A-Za-z0-9_]{2,32})?|fetch|fetch\-log)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 5.2.2.2 汇率快照（catalog 服务，卷 05「定价与价目」的 L3 锚点 5.2.2）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   exchange-rate-snapshots                      快照台账（集合读）+ 冻结快照（POST）
    //   exchange-rate-snapshots/capabilities         能力声明（冻结 / 方向 / 舍入规则）
    //   exchange-rate-snapshots/replay               复算（POST，纯计算不写库）
    //   exchange-rate-snapshots/rounding-rules       舍入规则配置（GET）+ 新建（POST）
    //   exchange-rate-snapshots/rounding-rules/initialize  初始化内置舍入规则（POST，幂等）
    //   exchange-rate-snapshots/rounding-rules/{code} 修改舍入规则（PUT；按**规则代码**寻址，不是 uuid）
    //   exchange-rate-snapshots/{id}                 快照详情（GET）
    //
    // 🔴 `capabilities` / `replay` / `rounding-rules` 等字面量段排在 uuid 分支**之前**判别：
    //     字面量先判，两类不会互相吃掉。
    // 🔴 `rounding-rules/{code}` 的 code 是**规则代码**（如 JPY / DEFAULT），不是 uuid ——
    //     所以这一段用字符集而不是 {uuid} 段。
    //
    // 刻意**不**放行：本模块**没有** UPDATE / DELETE 端点（快照是只追加的冻结事实），
    // 也没有「导出」「批量冻结」这类不存在的形状 ——
    // 放行后端不存在的路径比 404 更糟，界面会以为功能已交付。
    //
    // 与既有分支不重叠：既有 `catalog/` 长正则里没有 `exchange-rate-snapshots`，
    // 而本条目的首 token `exchange-rate-snapshots` 与兄弟模块的 `exchange-rates` 也是两个 token，
    // 两者不会互相吃掉。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^exchange\-rate\-snapshots(?:\/(?:capabilities|replay|rounding\-rules(?:\/initialize|\/[A-Za-z0-9_]{2,32})?)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 5.2.1.3 成本分摊（catalog 服务，卷 05「定价与价目」的 L3 锚点 5.2.1）。
    //
    // 蓝图：「把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，
    // 支持人工微调并留痕。……**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 10.7.1.3 的 `return-disposals` 元素之后、
    // `];` 之前），与并行车道的插入点互不重叠。
    //
    // 🔴 为什么用 `cost-allocations` 这个独立前缀：
    //   * 既有 catalog 分支的 alternation 里没有 `cost-allocations`（用脚本逐条核对了本文件里
    //     **全部 77 条**既有正则：`cost-allocations/**` **零命中**，既有分支不会先把它吃掉）。
    //   * channel 服务的 `CostAllocation*` DTO 家族（10.6.1.1 物流成本分析）是**服务端类型名**，
    //     与这里的**浏览器侧路径**是两回事；本模块的端点挂在 catalog 的命名空间下，
    //     前缀互不重叠（`catalog/` vs `channel/`）。
    //   * 5.2.1.1 的 `sku-cost-compositions`（成本构成）与 5.2.1.2 的到岸成本各自独立，
    //     本前缀与它们不共用首 token。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   cost-allocations                              分摊单台账（GET，筛选/分页在服务端）+ 新建分摊单（POST）
    //   cost-allocations/capabilities                 能力声明（四条规则 / 两种来源 / 四个基数的口径）
    //   cost-allocations/sources                      可分摊的货件候选（头程发运单，GET）
    //   cost-allocations/{uuid}                       分摊单明细（GET）
    //   cost-allocations/{uuid}/revisions             版本历史（GET）+ 应用重算追加新版本（POST）
    //   cost-allocations/{uuid}/revisions/{no}        某一个版本的快照明细（GET）
    //   cost-allocations/{uuid}/recalculate           试算重算（POST，**不落库**）
    //   cost-allocations/{uuid}/diffs                 两版之间的差异对比（GET）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / sources）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，而 `capabilities` 的首字母 `c` 落在 [0-9a-f] 里，
    //    顺序反了会被 uuid 支吃掉半截（这是本仓反复踩过的坑）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 版本号段用 `\d+`（不是 UUID）：它是 int。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `cost-allocations/{uuid}/lines` —— 明细行随明细与版本一起读，没有独立端点。
    //   * `cost-allocations/{uuid}/delete` —— 历史不可变，分摊单不可删（删了就等于没版本历史）。
    //   * `cost-allocations/batch` —— 没有批量分摊。
    //   * 5.2.1.1 的 `sku-cost-compositions/{uuid}/allocations` —— 那条分支**刻意不放行**：
    //     成本构成不提供「就地分摊」的入口（分摊是 5.2.1.3 自己的资源）。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^cost\-allocations(?:\/(?:capabilities|sources)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:revisions(?:\/[0-9]+)?|recalculate|diffs))?)?$/,
  },
  {
    // 6.5.2.1 采购分析（trade 服务，卷 06 采购与供应的 L3 锚点 6.5.2）。
    //
    // 蓝图：「采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
    // 采购成本占销售额比、按类目/店铺维度的采购效率。输入：采购单与收货数据；
    // 时间范围与维度筛选。输出：报表图表 + 可导出明细；下钻到具体采购单。」
    //
    // 本条目是**新增一条 services[] 元素**，既有条目一字未动（插入锚点固定在
    // 5.2.3.2 物流与头程费用之前，与同批另外几条车道的插入点互不重叠）。
    //
    // 🔴 为什么用 `purchase-analytics` 这个独立顶级前缀：
    //   * 既有 trade 分支是**一条**覆盖全服务的长 alternation，里面没有 `purchase-analytics`；
    //     已用脚本逐条核对本文件里**全部 100 条**既有正则：`purchase-analytics/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 前端路由是 /purchase/analytics，后端命名空间是 /trade/purchase-analytics ——
    //     两者是两件事，不共用前缀（与 6.2.1.3 的 purchase-plans 同一纪律）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   purchase-analytics/capabilities            能力声明（逐条点名未交付的归谁管）
    //   purchase-analytics/options                 筛选下拉（全部来自已物化的真实事实）
    //   purchase-analytics/facts                   下钻明细列表（真分页 + 真筛选 + 真排序）
    //   purchase-analytics/facts/sync              把上游已交付事实物化进台账（POST）
    //   purchase-analytics/facts/{uuid}            单条事实详情
    //   purchase-analytics/sync-runs               同步批次留痕（真分页）
    //   purchase-analytics/summary                 总览 KPI（金额按币种分列）
    //   purchase-analytics/trend                   采购金额趋势（按币种分列）
    //   purchase-analytics/supplier-concentration  供应商集中度与贡献（top-N 占比 + HHI）
    //   purchase-analytics/price-variance          采购价格波动（环比）
    //   purchase-analytics/on-time-rate            到货准时率（分母是承诺到货行）
    //   purchase-analytics/cost-to-sales-ratio     采购成本占销售额比（分母不可用时显式标算不出）
    //   purchase-analytics/efficiency              按类目/店铺维度的采购效率
    //   purchase-analytics/export                  下钻明细 CSV 导出（流式，GET）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / facts / summary / trend …）排在
    //    {uuid} 段之前** —— alternation 是首个命中即返回，而 `facts/sync` 这类字面量子路径
    //    若排在 uuid 段之后会被 uuid 支吃掉半截（这是本仓反复踩过的坑）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `purchase-analytics/facts/{uuid}/lines` —— 事实是**单级**的，没有子行。
    //   * `purchase-analytics/facts/{uuid}/delete` —— 事实台账是凭据，删了等于把这个数从报表里抹掉。
    //   * `purchase-analytics/export/{uuid}` —— 导出按当前筛选整体导出，没有单条导出。
    //   * `purchase-analytics/reconciliation` —— 6.5.1.1 采购对账**未交付**，本模块不代它开形状。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-analytics(?:\/(?:capabilities|options|facts|summary|trend|supplier\-concentration|price\-variance|on\-time\-rate|cost\-to\-sales\-ratio|efficiency|export|sync\-runs)|\/facts\/sync|\/facts\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 5.2.3.2 物流与头程费用（catalog 服务，卷 05 定价与价目的 L3 锚点 5.2.3）。
    //
    // 蓝图：「维护物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）
    // 与头程费用结构，用于测算运费占成本比。」输入「渠道计费规则 / 附加费参数 /
    // 头程运输方式费用」，输出「费用规则集 + 测算输入」，
    // 上游 = 10 物流与头程（**卷级引用**），下游 = 5.3.1.1 测算。
    //
    // 本条目是**新增一条 services[] 元素**，既有条目一字未动（插入锚点固定在
    // 5.2.1.3 成本分摊之后、5.2.3.1 平台费用之前，与另外几条车道的插入点互不重叠）。
    //
    // 🔴 为什么用 `logistics-freight` 这个独立顶级前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `logistics-freight`；
    //     已用脚本逐条核对本文件里**全部 110 条**既有正则：`logistics-freight/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.2.3.1 平台费用与本模块共用页面路由 /pricing/fees，但**不共用端点**：
    //     本车道只交付 5.2.3.2，不代兄弟模块开形状，也不挂在 `platform-fees/` 之下。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   logistics-freight/capabilities                     能力声明（逐维度写明取不到没有 / 归谁管）
    //   logistics-freight/rules                            渠道计费规则集合读（GET）/ 新建（POST）
    //   logistics-freight/rules/{uuid}                     规则详情（GET，含阶梯）
    //   logistics-freight/rules/{uuid}/supersede           新增一个版本并取代旧版本（POST，不可变优先）
    //   logistics-freight/rules/{uuid}/disable             停用一条规则版本（POST，必须写理由）
    //   logistics-freight/surcharges                       附加费集合读（GET）/ 登记（POST）
    //   logistics-freight/surcharges/{uuid}/disable        停用一条附加费（POST，必须写理由）
    //   logistics-freight/head-freight/structures          头程费用结构集合读（GET）/ 新建（POST）
    //   logistics-freight/head-freight/structures/{uuid}    头程费用结构明细（GET，含成本项）
    //   logistics-freight/head-freight/structures/{uuid}/disable  停用一条头程结构（POST）
    //   logistics-freight/head-freight/estimate           头程费测算（GET，逐项成本拆解）
    //   logistics-freight/estimate                         渠道运费测算（GET，逐步拆解）
    //   logistics-freight/actuals                          实际运费集合读（GET）/ 登记（POST）
    //   logistics-freight/actuals/{uuid}/void              作废一条实际运费（POST，必须写理由）
    //   logistics-freight/variance                         运费差异对照（GET，实际 vs 按规则测算）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / estimate / variance / actuals / rules /
    //    surcharges）排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `logistics-freight` 就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `logistics-freight` —— 本模块没有裸路径资源。
    //   * `logistics-freight/rules/{uuid}/delete` —— 规则不可删（要停用请走 /disable）。
    //   * `logistics-freight/rules/{uuid}/tiers` —— 阶梯随规则整体写入，没有独立的替换端点
    //     （不可变优先：改档位 = 新增一个版本）。
    //   * `logistics-freight/actuals/{uuid}` —— 实际运费没有「按 id 取单条」这个端点，也不可就地改写
    //     （只追加 + 作废留理由）。
    //   * `logistics-freight/surcharges/{uuid}` —— 同上。
    //   * `logistics-freight/head-freight/structures/{uuid}/update` —— 同上。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^logistics-freight(?:\/capabilities|\/estimate|\/variance|\/actuals(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/void)?|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:supersede|disable))?)?|\/surcharges(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/disable)?|\/head-freight(?:\/estimate|\/structures(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/disable)?)?))$/,
  },
  {
    // 12.6.2.1 发票管理（finance 服务，卷 12「财务核算」L3 12.6.2 发票与贷项下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点 ----
    //   本车道分配到的锚点 = 本文件第 9040 行那条元素（5.2.3.1 平台费用，catalog 服务）的 `{` 起始行**之前**。
    //   同批另外三条车道的锚点分别在第 314 / 1505 / 4071 行 —— 与本车道相距足够远，
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「invoices」的零命中核对结论（本车道实测）----
    //   发车前用 `grep -c "invoices" apps/web/src/lib/api-proxy.ts` 实测：**零命中**（输出 0）。
    //   （单数 `invoice` 有 4 处命中，但**全是注释**，外加一条既有 `fulfillment/routing/...invoice-requests`
    //   正则 —— 与本条正则的首 token `invoices` 不相交。）
    //   既有 finance 那几支是单行超长 alternation，首 token 是 `receivables` / `receivable-aging` /
    //   `payables` / `receipts` / `bad-debt` / `payment-plans` / `payment-orders` /
    //   `payment-executions` / `write-offs` / `settlement` / `transactions` / `fund-analysis` /
    //   `cost-collections` / `cost-valuation` / `deferred-settlement` / `cost-calculation` /
    //   `cost-variance` / `tax-rates` / `supplier-reconciliation` / `period-close` 等，**全不相同** ⇒
    //   本条正则与它们**两两不相交**（既不会互相吃掉，也不会被吃掉）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   invoices                            发票列表（GET）/ 建一张草稿（POST）
    //   invoices/capabilities               能力声明（上游真接通 / 职责切分 / 缺口归谁）
    //   invoices/options                    筛选项（单据类型 / 状态 / 平台对接状态 / 币种 / 模板）
    //   invoices/templates                  发票模板列表（GET）/ 登记模板（POST）
    //   invoices/requests                   待开票需求（上游 9 订单，**只读**）
    //   invoices/batch                      批量建单（从待开票需求生成草稿）
    //   invoices/summary                    发票汇总（🔴 **按币种分组**，没有跨币种合计）
    //   invoices/platform-submissions       平台提交记录（GET）/ 批量递交平台（POST）
    //   invoices/platform-submissions/{uuid}/acknowledge  登记平台回执（POST）
    //   invoices/{uuid}                     发票详情（GET：头 + 明细）
    //   invoices/{uuid}/issue               开具（POST：草稿 → 已开具，分配正式编号）
    //   invoices/{uuid}/void                作废（POST：🔴 原因必填）
    //   invoices/{uuid}/reverse             红冲（POST：逐行带出生成成对的贷项通知单）
    //   invoices/{uuid}/events              状态记录（GET，append-only 留痕）
    //   invoices/{uuid}/platform-payload    平台开票报文（GET，金额是字符串）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   invoices/{uuid}/update              —— 本模块**没有**「改发票」端点：已开具的发票不可改行，
    //                                           要改只能作废后重开（或红冲）。
    //   invoices/filings                    —— 🔴 12.6.1.2 VAT/IOSS 申报数据**未交付**：
    //                                           放行会让界面以为「已经能出申报底稿」。
    //   invoices/declarations               —— 🔴 同上（申报数据的另一个叫法）。
    //   invoices/reports                    —— 🔴 12.5.2.2 财务报表**未交付**（在同服务另一条命名空间）。
    //   invoices/vouchers                   —— 🔴 12.5.2.1 凭证**不归本模块**：发票不写凭证，
    //                                           过账走凭证模块自己的接口。
    //   invoices/platform-submissions/{uuid} —— 本模块**没有**「按 id 取单条提交记录」的端点
    //                                           （只有清单与登记回执）。
    //   invoices/templates/{uuid}           —— 模板没有单条详情端点（按查询参数定位）。
    //   invoices/not-a-shape                —— 非本模块的子路径。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `events` 这种非 UUID 形状也当成 id 放行。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^invoices(?:\/(?:capabilities|options|templates|requests|batch|summary|platform-submissions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:issue|void|reverse|events|platform-payload))?))?$/,
  },
  {
    // 5.2.3.1 平台费用（catalog 服务，卷 05 定价与价目的 L3 锚点 5.2.3）。
    //
    // 蓝图：「维护平台侧费用的计算规则：佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、
    // 罚款类型，用于利润测算的扣项。」输出「费用规则；测算时的费用项」，
    // 并加粗「**规则参数用于事前测算，实际费用来自 12 的结算对账**——两者差额要在利润报表中
    // 显性展示为『费用差异』，否则测算永远不准却不知道为什么」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 10.7.1.3 的 `return-disposals` 元素之后、
    // `];` 之前），与另外三条车道的插入点互不重叠。既有条目一字未动。
    //
    // 🔴 为什么用 `platform-fees` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `platform-fees`；
    //     已用脚本逐条核对本文件里**全部 77 条**既有正则：`platform-fees/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.2.3.2 物流与头程费用与本模块共用页面路由 /pricing/fees，但**不共用端点**：
    //     本车道只交付 5.2.3.1，不代未交付的兄弟模块开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-fees/capabilities        能力声明（逐维度写明取不到没有 / 归谁管）
    //   platform-fees/directory           平台 / 站点目录（上游 2 平台目录，channel 授权只读）
    //   platform-fees/rules               规则集合读（GET，真分页 + 筛选）/ 新建（POST）
    //   platform-fees/rules/{uuid}        规则详情（GET）/ 修改（PUT，乐观锁）
    //   platform-fees/rules/{uuid}/tiers  整体替换阶梯（PUT）
    //   platform-fees/estimate            费用测算（GET，逐项费用 + 合计完整性）
    //   platform-fees/actuals             实际费用集合读（GET）/ 登记（POST）
    //   platform-fees/actuals/{uuid}/void 作废实际费用（POST，必须写理由）
    //   platform-fees/variance            费用差异对照（GET，实际 vs 按规则测算）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / directory / estimate / variance /
    //    actuals / rules）排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `platform-fees` 就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `platform-fees` —— 本模块没有裸路径资源。
    //   * `platform-fees/rules/{uuid}/delete` —— 费率规则不可删（要停用请把 enabled 置 false 并设生效终点）。
    //   * `platform-fees/actuals/{uuid}` —— 实际费用没有「按 id 取单条」这个端点，也不可就地改写
    //     （只追加 + 作废留理由）。
    //   * `platform-fees/actuals/{uuid}/update` —— 同上，改金额等于让历史事实可回溯修改。
    //   * `platform-fees/logistics` —— 🔴 5.2.3.2 物流与头程费用**未交付**，本模块不代它开形状。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^platform-fees(?:\/capabilities|\/directory|\/estimate|\/variance|\/actuals(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/void)?|\/rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/tiers)?)?)$/,
  },
  {
    // 1.3.2.2 外部渠道推送（identity 服务，卷 01「工作台与门户」L3 1.3.2 预警订阅下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 支里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在「5.2.3.1 平台费用」那条元素之后** ----
    //   同批兄弟车道 1.3.2.1（预警规则）插在 `prefix: 'identity/'` 那条元素**之后**（约第 1234 行），
    //   本车道落在本处（约第 9724 行），两者相距 8000 行以上 ⇒ 并入时 git 能干净求并。
    //   本文件**不在** merge-union 驱动器的覆盖范围内（.gitattributes 只给
    //   `packages/api-contracts/src/**` 与 `module-progress.md` 配了驱动）⇒ 它走**普通 git merge**，
    //   所以插入点必须彼此错开。
    //
    // ---- 首 token「external-channel-push」的零命中核对结论（.tmp/api-proxy-zero-hit.mjs 实测）----
    //   发车前用脚本把本文件里**全部 231 个既有元素**（prefix + 正则）逐条抽出来，
    //   按 `resolve()` 的**同构口径**实测（先切掉 prefix 再 test）：
    //     external-channel-push
    //     external-channel-push/capabilities
    //     external-channel-push/channels
    //     external-channel-push/channels/{uuid}
    //     external-channel-push/candidates
    //     external-channel-push/deliveries
    //     external-channel-push/deliveries/retry-due
    //     external-channel-push/deliveries/{uuid}/retry
    //     external-channel-push/deliveries/{uuid}/acknowledge-alert
    //   **零命中** —— 既有 231 条正则对本模块的形状**全部零命中**；
    //   反向核对（既有已交付形状：identity/roles、identity/alert-rules、
    //   identity/notification-inbox、identity/notification-preferences、
    //   identity/user-preferences、identity/tenants/current、identity/children、
    //   identity/members、catalog/products）对本模块的正则也**全部零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 identity.openapi.json 里就这些）：
    //   external-channel-push/capabilities         能力声明（渠道词表 + secret manager /
    //                                               投递通道 / 模板管理三个「通没接通」）
    //   external-channel-push/channels              渠道清单（GET）/ 登记渠道（POST）
    //   external-channel-push/channels/{uuid}       改渠道（PUT）/ 删渠道（DELETE）
    //   external-channel-push/candidates            可推送候选（1.3.2.1 的真命中）
    //   external-channel-push/deliveries            投递记录清单（GET）/ 发起推送（POST）
    //   external-channel-push/deliveries/retry-due  到期扫描（POST，**自动重试**的入口）
    //   external-channel-push/deliveries/{uuid}/retry             手工重试（POST）
    //   external-channel-push/deliveries/{uuid}/acknowledge-alert  确认告警（POST）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   external-channel-push                        —— 裸路径：本模块**没有**裸路径资源。
    //   external-channel-push/templates               —— 🔴 18.5.1.1 模板管理**未交付**，
    //                                                      本模块**不造模板表**顶替它。
    //   external-channel-push/channels/not-a-uuid     —— 非 UUID 段不该被 uuid 分支吃掉。
    //   external-channel-push/channels/{uuid}/extra   —— 服务端没有这一层。
    //   external-channel-push/deliveries/not-a-uuid/retry —— 同上。
    //   external-channel-push/deliveries/{uuid}/delete —— 服务端没有删除投递记录的端点
    //                                                      （投递记录是「投过没有」的事实，不可删）。
    //   external-channel-push/deliveries/{uuid}/retry/extra —— 多一层仍然 404。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    //   后者会把 `retry` / `retry-due` 这种非 UUID 形状也当成 id 吃掉，
    //   `deliveries/retry-due` 就永远匹配不上。
    // ⚠️ 字面量子路径（capabilities / channels / candidates / deliveries / retry-due）
    //   排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^external\-channel\-push\/(?:capabilities|channels(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|candidates|deliveries(?:\/retry\-due|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:retry|acknowledge\-alert))?)?)$/,
  },
  {
    // 6.4.2.1 质检单（trade 服务，卷 06「采购与供应」的 L3 锚点 6.4.2「来料质检」）。
    //
    // 蓝图：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
    // 不合格判定与扣量。」输出「质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 巨型正则里并 ——
    // 那一支是单行超长 alternation，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.3.2.2 价格规则」元素**之前**，与并行车道的插入点互不重叠；
    // 既有条目一字未动。
    //
    // 🔴 为什么用 `quality-inspections` 这个独立前缀：
    //   * 用脚本逐条核对了本文件里**全部 96 条**既有正则：本模块的 **14 个形状**对它们
    //     **零命中**（既有条目不会先把它吃掉）。
    //   * catalog 侧有一条 `supply/vendors/{uuid}/quality-inspections/{uuid}/void`
    //     （3.6.1.1 供应商详情里的质量表现）—— 那是**供应商档案下的子路径**，
    //     挂在 `supply/vendors/` 之后，与本模块的**根路径** `quality-inspections`
    //     是两个不同的形状，两者不会互相吃掉。
    //   * trade 侧已有一套 `QualityInspection*`（11.x 的**退货质检** `/trade/return-quality`），
    //     路径前缀不同（`return-quality/` vs `quality-inspections/`），也不会互相吃掉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   quality-inspections                              质检单台账（GET，真分页 + 筛选）+ 新建质检单（POST）
    //   quality-inspections/capabilities                 能力声明（上下游可用性 + 三条口径说明）
    //   quality-inspections/options                      模板下拉与词表
    //   quality-inspections/summary                      汇总（可售量 / 不可售量分列）
    //   quality-inspections/templates                    模板列表（GET）+ 新建模板（POST）
    //   quality-inspections/templates/{uuid}             模板详情（GET）+ 改模板（PUT）
    //   quality-inspections/templates/{uuid}/activate    启用模板（POST）
    //   quality-inspections/templates/{uuid}/retire      停用模板（POST）
    //   quality-inspections/{uuid}                       质检单详情（GET）
    //   quality-inspections/{uuid}/items                 录入逐项检验结论（POST）
    //   quality-inspections/{uuid}/concessions           登记让步接收（POST，来源与权限码必填）
    //   quality-inspections/{uuid}/complete              完结并判三量（POST）
    //   quality-inspections/{uuid}/cancel                作废（POST，理由必填）
    //   quality-inspections/{uuid}/export                导出 CSV（GET）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary / templates）排在
    // {uuid} 段之前** —— 否则「capabilities」会被 uuid 支吃掉半截。
    //
    // 🔴 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 ——
    //    界面会以为功能已交付）：
    //   * quality-inspections/{uuid}/dispatch、/disposition —— **不存在**「让 trade 直接
    //     把货分流到库存待检 / 残次 bucket」的端点：warehouse 当前没有可用的授权写端点，
    //     本模块只记录分流**意图**。放行它会让界面显示「已分流」而其实没有。
    //   * quality-inspections/{uuid}/defectives —— 6.4.2.2 不良品处置**未交付**。
    //   * quality-inspections/{uuid}/items/{itemId} —— 没有「按 id 取单条检验结论」的端点，
    //     结论随详情一起返回。
    //   * quality-inspections/{uuid}/concessions/{id} —— 让步记录**只追加**，
    //     不可改写也不可删除，所以没有按 id 寻址的形状。
    //   * quality-inspections/bulk —— 没有批量端点。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^quality\-inspections(?:\/(?:capabilities|options|summary|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:activate|retire))?)?)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:items|concessions|complete|cancel|export))?)?$/,
  },
  {
    // 6.4.2.2 不良品处理（trade 服务，卷 06「采购与供应」的 L3 锚点 6.4.2「来料质检」下的第二格）。
    //
    // 蓝图：「不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
    // 处置结论联动库存状态与供应商索赔。」输出「处置单 + 库存状态变更指令 + 供应商索赔记录」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 巨型正则里并 ——
    // 那一支是单行超长 alternation，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「6.4.2.1 质检单」元素**之后**，与并行车道的插入点互不重叠；
    // 既有条目一字未动。
    //
    // 🔴 为什么用 `defective-dispositions` 这个独立前缀：
    //   * 用脚本逐条核对了本文件里**全部 212 条**既有正则：本模块的 **16 个形状**对它们
    //     **零命中**（既有条目不会先把它吃掉）。
    //   * trade 侧已有一套 `QualityInspection*`（6.4.2.1 质检单，根路径 `quality-inspections/`），
    //     路径前缀不同，不会互相吃掉。
    //   * 11.2.3.3 供应商索赔（V188，根路径 `supplier-claims/` 之类）是**退货侧**的索赔，
    //     与本模块的**来料不良品**索赔是两条链，前缀不同。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   defective-dispositions                              处置单台账（GET，真分页 + 筛选）+ 新建处置单（POST）
    //   defective-dispositions/capabilities                 能力声明（上下游可用性 + 四条口径说明）
    //   defective-dispositions/options                      可处置质检单下拉与词表
    //   defective-dispositions/summary                      汇总（处置量 / 放行量 / 索赔 / 指令状态）
    //   defective-dispositions/{uuid}                       处置单详情（GET）+ 改明细（PUT，仅草稿）
    //   defective-dispositions/{uuid}/decide                决策定稿（POST，生成库存指令与索赔记录）
    //   defective-dispositions/{uuid}/dispatch-inventory    派发库存状态变更指令（POST）
    //   defective-dispositions/{uuid}/complete              完成（POST）
    //   defective-dispositions/{uuid}/cancel                作废（POST，理由必填）
    //   defective-dispositions/{uuid}/claims/{cid}/submit   提交索赔（POST）
    //   defective-dispositions/{uuid}/claims/{cid}/agree    供应商认可赔付（POST）
    //   defective-dispositions/{uuid}/claims/{cid}/reject   供应商拒赔（POST，理由必填）
    //   defective-dispositions/{uuid}/claims/{cid}/withdraw 撤回索赔（POST，理由必填）
    //   defective-dispositions/{uuid}/export                导出 CSV（GET）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / options / summary）排在 {uuid} 段之前** ——
    //    否则「capabilities」会被 uuid 支吃掉半截。
    //
    // 🔴 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 ——
    //    界面会以为功能已交付）：
    //   * defective-dispositions/{uuid}/lines —— 明细随详情一起返回，没有独立的子资源端点。
    //   * defective-dispositions/{uuid}/instructions —— 库存指令随详情一起返回。
    //   * defective-dispositions/{uuid}/claims —— 索赔随详情一起返回。
    //   * defective-dispositions/{uuid}/inventory ——
    //     🔴 **不存在**「让 trade 直接改库存状态」的端点：warehouse 当前没有可用的授权写端点，
    //     本模块只记录**指令**。放行它会让界面显示「已改库存」而其实没有。
    //   * defective-dispositions/{uuid}/claims/{cid} —— 索赔没有按 id 寻址的形状（只随详情返回）。
    //   * defective-dispositions/{uuid}/claims/{cid}/confirm ——
    //     🔴 **不存在**「财务确认入账」的端点：12 财务（finance 服务）在本仓不存在，
    //     已生效冲减金额恒为 0。放行它会让界面显示「已入账」而其实没有。
    //   * defective-dispositions/bulk —— 没有批量端点。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^defective\-dispositions(?:\/(?:capabilities|options|summary)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:decide|dispatch\-inventory|complete|cancel|export)|\/claims\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:submit|agree|reject|withdraw))?)?$/,
  },
  {
    // 6.3.1.3 采购单审批（trade 服务，卷 06「采购与供应」的 L3 锚点 6.3.1「采购单」下的第三格）。
    //
    // 蓝图：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，审批通过后锁定单据生成应付。」
    // 输出「审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 trade 巨型正则里并 ——
    // 那一支是单行超长 alternation，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「6.4.2.2 不良品处理」元素**之后**、「5.3.2.2 价格规则」元素**之前**，
    // 与本批另外三条车道的插入点（6.3.2.1 / 6.6.2.1 / 11.4.1.1）互不重叠；既有条目一字未动。
    //
    // 🔴 为什么用 `purchase-order-approvals` 这个独立前缀：
    //   * 用脚本逐条核对了本文件里**全部 117 条**既有正则（一条不漏、逐条 new RegExp 试跑）：
    //     本模块的 **15 个形状**对它们**零命中**（既有条目不会先把它吃掉）。
    //   * 首 token 与本仓已占用的邻近首 token 全部不同：
    //     `purchase-orders`（6.3.1.1）、`purchase-order-editor`（6.3.1.2）、
    //     `purchase-plans`（6.2.1.3）、`purchase-requests`（6.2.2.1）、
    //     `purchase-arrivals`（6.3.2.2）、`purchase-analytics`（6.5.2.1）、
    //     `purchase-reconciliations`（6.5.1.1）、`replenishment-suggestions`（6.2.1.2）、
    //     `development-projects` / `development-samples`（6.6.1.x）。
    //   * ⚠️ `purchase-orders` 与 `purchase-order-approvals` **只差两个连字符** ——
    //     既有那条是**锚定**的（`^purchase\-orders(?:...)$`），吃不到本模块的路径；
    //     本条也是锚定的，两者互不吃。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   purchase-order-approvals/capabilities            能力声明（GET）
    //   purchase-order-approvals/rules                   规则列表（GET）+ 新建规则（POST）
    //   purchase-order-approvals/rules/{uuid}            改规则（PUT）
    //   purchase-order-approvals/rules/{uuid}/status     启用 / 停用规则（POST）
    //   purchase-order-approvals/members                采购员分级 + 审批人（GET）
    //   purchase-order-approvals/members/buyers         登记 / 改采购员分级（POST）
    //   purchase-order-approvals/members/approvers      登记 / 改审批人（POST）
    //   purchase-order-approvals/orders                 审批台账（GET，真分页 + 筛选）
    //   purchase-order-approvals/orders/batch           批量审批 / 批量驳回（POST）
    //   purchase-order-approvals/orders/{uuid}          审批详情（GET）
    //   purchase-order-approvals/orders/{uuid}/approve  审批通过（POST）
    //   purchase-order-approvals/orders/{uuid}/reject   驳回并附意见（POST）
    //   purchase-order-approvals/payables               应付登记台账（GET）
    //   purchase-order-approvals/payables/{uuid}/confirm 财务确认（POST）
    //   purchase-order-approvals/payables/{uuid}/reject  财务驳回（POST）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / rules / members / orders / payables）排在
    // {uuid} 段之前**，且 `orders/batch` 排在 `orders/{uuid}` 之前 ——
    // 否则「batch」会被 uuid 支吃掉半截。
    //
    // 🔴 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 ——
    //    界面会以为功能已交付）：
    //   * purchase-order-approvals —— 裸路径：本模块没有裸集合读，放行它会凭空多出一条 GET。
    //   * purchase-order-approvals/orders/{uuid}/lines —— 行随 6.3.1.2 的编辑表单一起返回，
    //     没有独立的行子资源端点。
    //   * purchase-order-approvals/orders/{uuid}/chain、/payable、/receipt-release ——
    //     审批链路、应付登记、收货放行**随详情一起返回**，没有独立的子资源端点。
    //   * purchase-order-approvals/orders/{uuid}/revoke-receipt ——
    //     🔴 **不存在**「撤销收货放行」的端点：审批通过后的采购单只能走
    //     APPROVED → CLOSED（V254 的状态机钉死），要改数量与单价得走变更单或红冲重下。
    //     放行它会让界面显示「已撤销」而其实没有。
    //   * purchase-order-approvals/payables/{uuid}、/rules/{uuid}、/members/{uuid} ——
    //     这三张表**没有**按 id 寻址的读端点（台账一次给全，详情按采购单取）。
    //   * purchase-order-approvals/rules/{uuid}/delete —— 规则**不提供删除**：
    //     已命中的规则是历史审批单的依据，删掉会让「按哪条规则批的」答不出来（用停用）。
    //   * purchase-order-approvals/orders/{uuid}/tax —— **12 财务未交付**（本仓没有 finance
    //     服务）：不代它开税额形状。
    //   * purchase-order-approvals/change-orders —— 变更单 / 红冲重下**不在本模块内**：
    //     本模块只锁不改，不代写变更单（否则同一张单会有两份金额真相）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^purchase\-order\-approvals(?:\/(?:capabilities|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|members(?:\/(?:buyers|approvers))?|orders(?:\/(?:batch|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:approve|reject))?)?)?|payables(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:confirm|reject))?))$/,
  },
  {
    // 5.3.2.2 价格规则（catalog 服务，卷 05 定价与价目的 L3 锚点 5.3.2）。
    //
    // 蓝图：「定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）
    // + 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。」输入「规则条件与动作；作用范围；
    // 生效时段」，输出「规则记录 + 命中记录 + 建议调价列表」，并加粗
    // 「**自动调价必须先"建议"再"执行"**，高风险规则（降价超过 X%）需人工确认，
    // 避免错误规则批量改价造成损失」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.3.2.1 定价模板」元素**之前**，与另外几条车道的插入点互不重叠。
    // 既有条目一字未动。
    //
    // 🔴 为什么用 `pricing-rules` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `pricing-rules`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`pricing-rules/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.3.2.1 定价模板与本模块共用 L3 锚点 5.3.2，但**不共用端点**：
    //     模板是「怎么算」，本模块是「什么时候改、改成多少」。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   pricing-rules/capabilities                 能力声明（触发条件 / 动作 / 作用范围 / 状态）
    //   pricing-rules/directory                    作用范围目录（内部分类 + 品牌）
    //   pricing-rules/rules                        规则集合读（GET，真分页 + 筛选）/ 新建（POST）
    //   pricing-rules/rules/{uuid}                 规则详情（GET）/ 修改（PUT，乐观锁）/ 删除（DELETE）
    //   pricing-rules/evaluate                     跑一次评估（POST）→ 命中记录 + 建议调价列表
    //   pricing-rules/hits                         命中记录（GET，真分页 + 筛选）
    //   pricing-rules/suggestions                  建议调价列表（GET，真分页 + 筛选）
    //   pricing-rules/suggestions/{uuid}/decide    人工确认 / 驳回一条建议（POST）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / directory / rules / evaluate / hits /
    //    suggestions）排在 {uuid} 段之前** —— alternation 是首个命中即返回，
    //    顺序反了会被 uuid 支吃掉半截（`rules` / `suggestions` 的首字母都落在 [0-9a-f] 里）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `rules` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `pricing-rules` 就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `pricing-rules` —— 本模块没有裸路径资源。
    //   * `pricing-rules/rules/{uuid}/execute` —— 🔴 本模块只产出**建议调价**，
    //     **不改价**：改价是 5.4.1.1 调价单的写入所有权（蓝图关系句：下游 = 5.4.1.1）。
    //   * `pricing-rules/rules/{uuid}/prices` —— 本模块**不建价目表**（5.1.1.2 的写入所有权）。
    //   * `pricing-rules/suggestions/{uuid}/apply` —— 建议调价没有「直接执行」的端点：
    //     高风险建议必须先经 `decide` 人工确认。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^pricing\-rules(?:\/(?:capabilities|directory|evaluate|hits|rules|suggestions)|\/rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/suggestions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/decide)$/,
  },
  {
    // 5.3.2.1 定价模板（catalog 服务，卷 05 定价与价目的 L3 锚点 5.3.2）。
    //
    // 蓝图：「沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、
    // 按库存周转调整，新品与老品用不同模板。」输入「模板定义（基准 + 调整项）；适用对象
    // （分类/品牌/店铺）」，输出「模板记录；一键应用到商品生成建议价」，
    // 并加粗「**模板是"怎么算"，价目表是"算完是多少"**」。
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.2.3.1 平台费用」元素之后、「5.3.1.1 利润测算器」元素之前，
    // 与另外三条车道的插入点互不重叠。既有条目一字未动。
    //
    // 🔴 为什么用 `pricing-templates` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `pricing-templates`；
    //     已用脚本逐条核对本文件里**全部 84 条**既有正则：`pricing-templates/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.3.2.2 价格规则与本模块共用 L3 锚点，但**不共用端点**：
    //     本车道只交付 5.3.2.1，不代未交付的兄弟模块开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   pricing-templates/capabilities          能力声明（基准 / 调整项 / 适用对象 / 商品阶段）
    //   pricing-templates/directory             适用对象目录（内部分类 + 品牌）
    //   pricing-templates/sites                 目标店铺（站点）列表，来自 2.1.1.1 平台目录
    //   pricing-templates/templates             模板集合读（GET，真分页 + 筛选）/ 新建（POST）
    //   pricing-templates/templates/{uuid}      模板详情（GET）/ 修改（PUT，乐观锁）/ 删除（DELETE）
    //   pricing-templates/templates/{uuid}/apply 一键应用生成建议价（POST）
    //   pricing-templates/applications         应用留痕（GET，真分页）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / directory / sites / applications / templates）
    //    排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `templates` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**不带 `?`**：本模块没有裸集合读，放行裸路径 `pricing-templates` 就是凭空多出一条 GET。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `pricing-templates` —— 本模块没有裸路径资源。
    //   * `pricing-templates/templates/{uuid}/prices` —— 本模块**不建价目表**（5.1.1.2 的写入所有权），
    //     也不改生效价（5.4.1.1 调价单的写入所有权）：本模块只产出**建议价**。
    //   * `pricing-templates/rules` —— 5.3.2.2 价格规则**未交付**，本模块不代它开形状。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^pricing\-templates(?:\/(?:capabilities|directory|sites|applications)|\/templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/apply)?)?)$/,
  },
  {
    // 5.3.1.1 利润测算器：利润拆解（售价 → 每一项扣减 → 毛利）+ 测算方案。
    // 形状逐个列出：
    //   capabilities —— 能力声明（逐项标注上游真实交付状态与归谁管），字面量子路径；
    //   sites —— 目标店铺（站点）列表，来自 2.1.1.1 平台目录，字面量子路径；
    //   calculate —— 一次测算（POST），字面量子路径；
    //   scenarios —— 方案集合（GET 列表 / POST 新建），字面量子路径；
    //   scenarios/{uuid} —— 单个方案（GET 详情 / DELETE 删除）。
    // `capabilities` 与 `calculate` 的首字母都落在 [0-9a-f] 里，但它们是**字面量**分支，
    // 而 uuid 只出现在 `scenarios/` 之下 ⇒ 两者不会互相吃掉。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^profit-calculator(?:\/(?:capabilities|sites|calculate)|\/scenarios(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)?$/,
  },
  {
    // 5.3.1.2 批量测算：对一批 SKU 批量跑利润测算（利润表 + 亏损品/低毛利品标记 + 筛选 + 导出）。
    // 形状逐个列出：
    //   capabilities —— 能力声明（售价来源 / 上游真实交付状态 / 跳过规则），字面量子路径；
    //   sites —— 目标店铺（站点）列表，来自 2.1.1.1 平台目录，字面量子路径；
    //   runs —— 批次集合读（GET 列表）/ 新建批次（POST 发起批量测算），字面量子路径；
    //   runs/{uuid} —— 单个批次（GET 详情）；
    //   runs/{uuid}/results —— 结果表（GET，真分页 + 真筛选）；
    //   runs/{uuid}/export —— 结果表 CSV 导出（GET，与列表共用同一套筛选）。
    // ⚠️ 判别顺序：**字面量子路径（capabilities / sites / runs）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ `capabilities` 与 `sites` 的首字母落在 [0-9a-f] 里，但它们是**字面量**分支，
    //    而 uuid 只出现在 `runs/` 之下 ⇒ 两者不会互相吃掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ **刻意不放行裸路径** `batch-calculations`：本模块没有裸集合读，
    //    放行它就是凭空多出一条 GET。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^batch-calculations(?:\/(?:capabilities|sites)|\/(?:runs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:results|export))?)?))$/,
  },
  {
    // 5.4.1.2 批量调价（catalog 服务，卷 05 定价与价目的 L3 锚点 5.4.1）。
    //
    // 蓝图：「**职责**：对选中的一批 listing 统一改价（固定金额/百分比/按模板），支持预览影响范围
    // （涉及多少店铺、多少商品、预计利润变化）。**输入**：目标集合；调价参数；预览确认。
    // **输出**：批量调价单 + 影响预览报告。**关系**：上游 = 5.3.1.2、4.1.1.1；下游 = 5.4.2.1。
    // **必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.3.1.2 批量测算」元素之后、数组结尾的 `];` 之前（数组最末尾），
    // 与同批另外三条车道的插入点互不重叠。既有条目一字未动。
    //
    // 🔴 为什么用 `batch-price-adjustments` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `batch-price-adjustments`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`batch-price-adjustments/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.4.1.1 调价单与本模块共用 L3 锚点 5.4.1，但**不共用端点**：
    //     本车道只交付 5.4.1.2，不代未交付的兄弟模块开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   batch-price-adjustments              集合读（GET 调价单台账）/ 执行批量调价（POST，带预览令牌）
    //   batch-price-adjustments/capabilities 能力声明（listing 的定义 / 当前价来源 / 预览闸 / 反向指标 / 舍入）
    //   batch-price-adjustments/sites        目标店铺（站点）列表，来自 2.1.1.1 平台目录
    //   batch-price-adjustments/preview      影响预览（POST，**无副作用**：后端在注定回滚的事务里跑它）
    //   batch-price-adjustments/{uuid}       单张调价单（GET 详情）
    //   batch-price-adjustments/{uuid}/items 明细（GET，真分页 + 真筛选）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / sites / preview）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 外层分组**带 `?`**：本模块**有**裸集合路径（GET 列表 / POST 执行），所以裸路径要放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `batch-price-adjustments/{uuid}/items/export` —— 本模块**不导出 CSV**（蓝图的输出是
    //     「调价单 + 影响预览报告」，不是导出件）；放行它只会让界面以为有下载按钮。
    //   * `batch-price-adjustments/{uuid}/sync` —— 5.4.2.1 平台价格同步**未交付**，
    //     本模块不代它开形状（那会把「已回写平台」这个动作凭空放出来）。
    //   * `batch-price-adjustments/rules` —— 5.3.2.2 价格规则**未交付**，本模块不代它开形状。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^batch-price-adjustments(?:\/(?:capabilities|sites|preview)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/items)?)?$/,
  },
  {
    // 5.4.1.1 调价单（catalog 服务，卷 05 定价与价目的 L3 锚点 5.4.1）。
    //
    // 蓝图：「**职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；
    // 记录变更前后值。**输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
    // **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
    // **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：
    // 大额或大幅调价走审批，普通调整可配置免审。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.4.1.2 批量调价」元素之后、数组结尾的 `];` 之前（数组最末尾），
    // 与同批另外三条车道的插入点互不重叠。既有条目一字未动。
    //
    // 🔴 为什么用 `price-adjustments` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `price-adjustments`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`price-adjustments/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.4.1.1 调价单与 5.4.1.2 批量调价共用 L3 锚点 5.4.1，但**不共用端点**：
    //     一张单与一批批量的生命周期、审批口径、版本闸都不一样。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   price-adjustments                        集合读（GET 台账）/ 建单（POST，草稿）
    //   price-adjustments/capabilities           能力声明（listing 的定义 / 当前价来源 / 审批口径 / 状态机 / 版本闸 / 舍入）
    //   price-adjustments/current-price          当前价解析（GET；取不到时后端返回 price=null 而不是 0）
    //   price-adjustments/writebacks             待回写事实（GET，真分页 + 真筛选）—— 给下游 5.4.2.1
    //   price-adjustments/writebacks/{uuid}/ack  待回写事实的回执（POST，由下游 5.4.2.1 调用）
    //   price-adjustments/{uuid}                 单张调价单（GET 详情；读详情会顺带拉一次审批状态）
    //   price-adjustments/{uuid}/submit          提交（草稿 → 待审 / 已批）
    //   price-adjustments/{uuid}/execute         执行（已批 → 已执行，并产出待回写事实）
    //   price-adjustments/{uuid}/reject          驳回（草稿 / 待审 → 已驳回）
    //   price-adjustments/{uuid}/approval/refresh 刷新审批状态（拉取式同步 17 组织的审批结论）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / current-price / writebacks）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ `writebacks/{uuid}/ack` 的 uuid 段**必须**在 `ack` 之前，且 `ack` 是**唯一**允许的后缀 ——
    //    `writebacks/{uuid}`（没有 ack）这个形状后端**不存在**，放行它会让界面以为能查单条回写。
    // ⚠️ 外层分组**带 `?`**：本模块**有**裸集合路径（GET 列表 / POST 建单），所以裸路径要放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `price-adjustments/{uuid}/sync` —— 5.4.2.1 平台价格同步**未交付**，
    //     本模块只产出「待回写」事实，不代它开形状（那会把「已回写平台」这个动作凭空放出来）。
    //   * `price-adjustments/{uuid}/approve` —— 审批结论由 17 组织那边的人做出，
    //     本模块**没有**「本地批准」端点：凭空放行它等于把审批流本地化，
    //     而 identity 那边会什么都不知道（那边还有一条永远没人管的待办）。
    //   * `price-adjustments/{uuid}/export` —— 本模块**不导出 CSV**（蓝图的输出是调价单，
    //     不是导出件）；放行它只会让界面以为有下载按钮。
    //   * `price-adjustments/rules` —— 5.3.2.2 价格规则的端点不是本模块的形状。
    //   * `price-adjustments/approval-rules` —— 审批规则**归 17 组织**（identity 的
    //     `/identity/approval-rules/**`），本模块只做**求值**，不代它开配置形状。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^price-adjustments(?:\/(?:capabilities|current-price|writebacks)(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/ack)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|execute|reject|approval\/refresh))?)?$/,
  },
  {
    // 5.4.2.1 平台价格同步（catalog 服务，卷 05 定价与价目的 L3 锚点 5.4.2）。
    //
    // 蓝图：「**职责**：把生效价格回写到平台，处理平台定价规则（平台可能有最低价 / 自动比价 /
    // 多站点联动），记录回写结果与被平台调整后的实际价。**输出**：回写任务（经 02 队列）；
    // 实际价与期望价的差异记录。**平台可能拒绝或调整价格**（如低于最低价触发），
    // 差异必须显性反馈，不能假设回写即成功。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 catalog 长正则里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在「5.4.1.1 调价单」元素之后、数组结尾的 `];` 之前（数组最末尾），
    // 与同批另外三条车道的插入点互不重叠（它们分别用 warehouse / trade / channel 组）。
    // 既有条目一字未动。
    //
    // 🔴 为什么用 `price-sync` 这个独立前缀：
    //   * 既有 catalog 分支是**一条**覆盖全服务的长 alternation，里面没有 `price-sync`；
    //     已用脚本逐条核对本文件里**全部既有正则**：`price-sync/**` **零命中**
    //     （既有分支不会先把它吃掉）。
    //   * 5.4.1.1 调价单与 5.4.2.1 平台价格同步**不共用端点**：一个回答「这次改价批不批」，
    //     一个回答「这个价推上平台会怎样」；上游的 `price-adjustments/writebacks/{uuid}/ack`
    //     仍是 5.4.1.1 的形状，本模块只是**调用**它，不另开形状。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   price-sync/capabilities                    能力声明（规则语义 / 结局 / 队列归属）
    //   price-sync/rules                          平台定价规则集合读（GET）+ 建规则（POST）
    //   price-sync/rules/{uuid}                   规则详情（GET）+ 改规则 / 停用（PATCH）
    //   price-sync/records                        回写结果集合读（真分页 + 真筛选）
    //   price-sync/records/{uuid}                 回写结果详情（含它的差异记录）
    //   price-sync/records/{uuid}/receipt         平台回执（实际价 / 拒绝原因）
    //   price-sync/diffs                          差异台账（实际价与期望价的差异逐条可查）
    //   price-sync/sync                           执行一次同步（消费上游待回写事实）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / rules / records / diffs / sync）**
    //    必须排在 uuid 段之前 —— alternation 是首个命中即返回，顺序反了会被 uuid 支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ `receipt` **只在 records 之下**放行：规则的回执不存在，放行它会让界面以为能提交规则回执。
    // ⚠️ **裸路径 `price-sync` 不放行**：本模块没有这个集合资源，放行它等于凭空开一个形状。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `price-sync` —— 没有裸集合资源。
    //   * `price-sync/records/{uuid}/approve` —— 平台的结论由**平台回执**给出，
    //     本模块**没有**「本地批准回执」端点：凭空放行它等于把平台结论本地化。
    //   * `price-sync/rules/{uuid}/receipt` —— 规则没有回执。
    //   * `price-sync/export` —— 本模块**不导出 CSV**（蓝图的输出是回写任务与差异记录，
    //     不是导出件）；放行它只会让界面以为有下载按钮。
    //   * `price-sync/records/{uuid}/retry` —— 重试语义住在 channel 的 02 队列
    //     （platform_operation 的 attempts / verification），本模块**不自造第二套队列**；
    //     界面上「再执行一次同步」走的是 `price-sync/sync`，不是按记录重试。
    //   * `price-sync/price-adjustments` —— 5.4.1.1 的命名空间，本模块只**调用**它的 ack 端点。
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      /^price-sync\/(?:capabilities|diffs|sync|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|records(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/receipt)?)?)$/,
  },
  {
    // 8.2.2.2 待检处理（warehouse 服务，卷 08「仓储作业」的 L3 锚点 8.2.2）。
    //
    // 蓝图：「管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、
    // 质检不通过转残次/报废/退货。」「输出：质量状态变更分录；可售量更新。」
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 warehouse 那条巨型 alternation 里并分支 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 `];`），
    // 与同批其它车道（catalog / trade / channel 组）的插入点互不重叠。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   pending-inspections/capabilities            能力声明（硬规则 / 上下游接入情况）
    //   pending-inspections/stock                    待检库存清单（GET，真分页 / 真筛选）
    //   pending-inspections/options                  下拉（仓库 / 质量状态 / 库位 / 质检模板）
    //   pending-inspections                          处理单集合（GET 真分页 / POST 建单）
    //   pending-inspections/{uuid}                   单据详情（GET：单头 + 分录 + 事件 + 可售量前后对照）
    //   pending-inspections/{uuid}/request-inspection 发起质检任务（POST，走 trade 的 6.4.2.1）
    //   pending-inspections/{uuid}/record-result      录入质检结果（POST，三量从质检单回读）
    //   pending-inspections/{uuid}/dispose            处置决定（POST，本模块唯一动库存的动作）
    //   pending-inspections/{uuid}/cancel             取消（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / stock / options）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 两个动作段（{uuid}/xxx）挂在单段 {uuid} 组的可选后缀里，否则会被单段分支吃掉半截、
    // 把动作名留在匹配之外。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   pending-inspections/{uuid}/entries   —— 分录在详情里，没有单独的读端点。
    //   pending-inspections/{uuid}/approve    —— 本模块没有审批动作（审批属 8.5.x 与 17 卷）。
    //   pending-inspections/summary          —— 汇总在列表响应里，没有单独的汇总端点。
    //   pending-inspections/{uuid}/delete     —— 终态不可逆：处置完的凭据不能删（后端 405 + DB 守卫）。
    //   任何 DELETE / PUT 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    //
    // 与既有分支不重叠：既有 warehouse 那条巨型正则的 alternation 里
    // 没有 `pending-inspections`（`putaway-tasks` / `goods-receipts` 与它不是一回事），
    // 所以 `pending-inspections/...` 不会先被既有分支吃掉。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^pending-inspections(?:\/(?:capabilities|stock|options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:request-inspection|record-result|dispose|cancel))?))?$/,
  },
  {
    // 11.4.1.2 证据与申诉（trade 服务，卷 11「售后与退货」L3 11.4「平台纠纷与客诉」的第二格）。
    //
    // 蓝图：「组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，
    // 记录平台回复与结果。」输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
    // 加粗句：「**举证材料必须来自系统记录**（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 trade 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点选在 **11.4.1.1 那一条之前**（而不是 services[] 的最末尾）：
    //   最末尾是同批其它车道也会盯着的默认位置，四条车道都往那里插就是四路冲突。
    //   本锚点绑在 11.4.1.x 这一族自己的注释上，与 6.3.x / 6.6.x / 7.4.x 那些车道的锚点互不重叠。
    //
    // ---- 首 token「platform-dispute-appeals」的零命中核对结论 ----
    //   与上游 11.4.1.1 的首 token「platform-disputes」**不是同一个串**：
    //   「platform-dispute-appeals」里没有「platform-disputes」这个字面量
    //   （`platform-dispute` 后面跟的是 `-appeals` 而不是 `s`）⇒ 上游那条正则吃不掉它。
    //   其余既有首 token（purchase-orders / quality-inspections / knowledge / supplier-claims /
    //   refund-applications / pending-inspections / platform-disputes …）也都不含这个串
    //   ⇒ 逐条零命中（核对脚本见本车道交付报告）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-dispute-appeals                                 集合读（GET，真分页 / 真筛选）+ 建单（POST）
    //   platform-dispute-appeals/capabilities                    能力声明（含上下游未交付的逐条说明）
    //   platform-dispute-appeals/{uuid}                          单据详情（GET：头 + 材料 + 提交记录 + 留痕 + 判据）
    //   platform-dispute-appeals/{uuid}/text                     改申诉文本（PUT，草稿态）
    //   platform-dispute-appeals/{uuid}/materials                挂证据材料（POST）
    //   platform-dispute-appeals/{uuid}/materials/{uuid}         移除证据材料（DELETE）
    //   platform-dispute-appeals/{uuid}/submit                   提交申诉（POST）
    //   platform-dispute-appeals/{uuid}/retry-submission         重试提交到平台（POST）
    //   platform-dispute-appeals/{uuid}/submission-attempts      记录一次提交尝试的结果（POST）
    //   platform-dispute-appeals/{uuid}/platform-reply           收回平台回复与结果（POST）
    //   platform-dispute-appeals/{uuid}/withdraw                 我方撤诉（POST）
    //
    // 判别顺序：**字面量子路径（capabilities）排在 {uuid} 段之前** ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   platform-dispute-appeals/{uuid}/upload       —— **不提供「上传任意文件当举证材料」**：
    //     举证材料必须引用系统里已有的一条事实记录（物流轨迹 / 沟通记录 / 附件 / 质检单）。
    //   platform-dispute-appeals/{uuid}/delete       —— 终态不可逆：申诉凭据不能删（后端 405 + DB 守卫）。
    //   platform-dispute-appeals/{uuid}/auto-lose    —— 没有「自动判负」这回事：
    //     超期自动判负是**上游 11.4.1.1** 的系统行为，本模块只呈现判据结果。
    //   platform-dispute-appeals/summary            —— 汇总在列表响应里，没有单独的汇总端点。
    //   任何 DELETE / PUT 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^platform-dispute-appeals(?:\/(?:capabilities|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:text|materials(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|submit|retry-submission|submission-attempts|platform-reply|withdraw))?))?$/,
  },
  {
    // 15.4.1.1 工单列表（crm 服务，卷 15「客户与客服」的 L3 锚点 15.4.1 下的第一格）。
    //
    // 蓝图：「客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、
    //   转派与升级。」「工单创建（自动/人工）；筛选条件」→「工单列表 + SLA 预警。」
    //   关系：上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理。
    //   **SLA 计时需按营业时间而非自然时间**，否则夜间积压会造成误判。
    //
    // 本条目是**新增一条独立的 `crm/` 元素**，不是往既有 crm 分支的 alternation 里并分支 ——
    // 既有 crm 分支分别是 `^knowledge\/...$`（15.4.2.1 客服知识库）、
    // `^message\-inbox...$`（15.1.1.1 消息收件箱）、`^care\-reminders...$`（15.5.2.1 关怀与催办）等，
    // 每一支都是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // ---- 插入锚点 ----
    //   固定在 `platform-dispute-appeals`（11.4.1.2 平台纠纷申诉，trade 服务）那条元素**之后**。
    //   本文件里的 `crm/` 元素集中在 3700~6300 与 8000 附近，而本锚点在 7700 一带，
    //   与任何一条既有 `crm/` 元素相距 **250 行以上** ⇒ 同为 crm 车道的并行改动
    //   （15.1.2.2 / 15.5.1.1 等）无论插在哪条 crm 元素附近，git 都能干净求并。
    //
    // ---- 首 token「service-tickets」的零命中核对结论 ----
    //   本文件既有全文里 `service-tickets` 出现次数 = **0**（既有的是 `service-ticket-*` 一类的
    //   其它命名也不存在），所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   service-tickets                          工单列表（GET，真分页 + 多维筛选 + 排序白名单）
    //   service-tickets/capabilities             能力声明（15.4.1.2 未交付 ⇒ available=false + ownerModule）
    //   service-tickets/{uuid}                   单张工单（GET，含上游的转派历史）
    //   service-tickets/business-hours           营业时间口径（GET 读 / PUT 改）
    //   service-tickets/business-hours/holidays  节假日例外（PUT 改写）
    //
    // 判别顺序：**字面量子路径（capabilities / business-hours）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `service-tickets/{uuid}/assign|transfer|claim|release|close|reply` ——
    //     工单的**写**动作仍然只在 trade 服务（11.4.2.1），本模块是只读列表；
    //   * `service-tickets/{uuid}/process` —— 15.4.1.2 工单处理**未交付**，
    //     放行它会让界面以为「已能处理工单」；
    //   * 任何 `service-tickets/{uuid}/delete` —— 工单不是可删的事实。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^service\-tickets(?:\/(?:capabilities|business\-hours(?:\/holidays)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 14.1.2.1 广告组管理（channel 服务）。纯**新增一条独立的 `channel/` 元素**，
    // 既有的 `ad-campaigns`（14.1.1.1）、`ad-campaign-details`（14.1.1.2）、
    // `ad-type-diffs`（14.1.2.2）、`ad-reports`（14.3.1.1）等**一字未动、也不重排** ——
    // 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，
    // 往既有那条 alternation 里并分支会让多车道同时并入时 git 必然判冲突。
    //
    // ---- 插入锚点固定在 `platform-disputes`（11.4.1.1）那条**之后**、`platform-disputes`
    //      之后的第一条 trade 元素**之前**（即 `11.4.1.1 平台纠纷列表` 那条**之前**）----
    //   同批其它车道（14.1.1.3 / 14.3.1.2 / 14.4.1.1）的插入点都在卷 14 的相邻元素附近
    //   （`ad-campaigns` 之前 / `ad-reports` 附近 / 促销那一族），
    //   与本锚点相距 600 行以上 ⇒ git 能干净求并。
    //
    // ---- 首 token「ad-groups」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `ad-groups` 这个串
    //   （既有的是 `ad-campaigns` / `ad-campaign-details` / `ad-keywords` / `ad-rules` /
    //   `ad-type-diffs` / `ad-reports` 一类），所以首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ad-groups                              广告组列表（GET，真分页 + 真筛选 + 真排序）
    //   ad-groups/capabilities                 能力声明（未交付段 available=false + ownerModule）
    //   ad-groups/options                      下拉项 + 护栏上限 + 组级报表路由标识
    //   ad-groups/summary                      组级汇总（GET，只给条数，不做跨币种金额相加）
    //   ad-groups/product-candidates           可选投放商品候选（GET，该活动所在店铺的 listing）
    //   ad-groups/{uuid}                       广告组详情（GET，含投放商品 / SKU 映射现状 / 审计）
    //   ad-groups/{uuid}/operations            组级操作审计（GET，真分页）
    //   ad-groups/{uuid}/products              设投放商品（PUT，**替换**语义）
    //
    // 判别顺序：字面量子路径（capabilities / options / summary / product-candidates）排在
    // {uuid} 段之前 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `ad-groups/{uuid}/bids` —— 改竞价**复用**上游 14.1.1.2 的
    //     `ad-campaign-details/campaigns/{uuid}/bids`（带单次上限与绝对上限、逐条留审计）。
    //     本模块**不另起同义端点**：两个端点改同一笔钱，审计就会分叉。
    //   * `ad-groups/{uuid}/metrics` —— 组级指标**没有事实来源**（没有组级指标表，
    //     ad_campaign_daily_metric 是 campaign 维度），capabilities 里 available=false。
    //   * `ad-groups/{uuid}/report` —— 组级报表归 14.3.1.2（同批并行开发）。
    //   * `ad-groups/{uuid}/keywords` —— 关键词归 14.1.3.1（且只有活动维度）。
    //   * `ad-groups/{uuid}/delete` —— 广告组是事实，不提供删除。
    //   * `ad-groups/{uuid}/writeback` —— 平台回写队列未交付（平台适配层）。
    //   * `ad-groups/groups` —— 不重开一条集合读。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // 裸路径 `ad-groups` **刻意放行**：本模块真的有「广告组列表」这个集合读。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-groups(?:\/(?:capabilities|options|summary|product\-candidates|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:operations|products))?))?$/,
  },
  {
    // 16.5.2.1 登录与访问安全（channel 服务，卷 16「合规与风险」L3 16.5.2「账号与访问安全」的第一格）。
    //
    // 蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
    // 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
    // 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
    // **平台凭据是最值钱的资产**，访问必须有审计且最小权限。」
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条巨型 alternation 里并分支
    //   会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 `ad-groups` 那条元素**之后**、`11.4.1.1 平台纠纷列表` 那条**之前** ----
    //   同批其它车道的锚点分别是 `prefix: 'identity/'` 之前、`// 14.5.2.1 带货归因` 之前与数组末尾，
    //   与本锚点相距 600 行以上 ⇒ git 能干净求并。
    //
    // ---- 首 token「access-security」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `access-security` 这个串
    //   （既有的是 `ad-groups` / `ad-campaigns` / `buyer-risk-scores` / `ip-risk-library` 一类），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   access-security/capabilities                    能力声明（逐项 available + ownerModule + 影响面）
    //   access-security/policy                           安全策略（GET 读 / PUT 改）
    //   access-security/mfa/registrations               MFA 因子登记（GET 真分页 / POST 登记）
    //   access-security/mfa/registrations/{uuid}/activate MFA 登记：绑定完成
    //   access-security/mfa/registrations/{uuid}/disable  MFA 登记：停用
    //   access-security/mfa/challenges                   MFA 挑战记录（GET / POST 登记下发）
    //   access-security/mfa/challenges/{uuid}/attempts    验证尝试（append-only，新的一行）
    //   access-security/login-audit                      登录审计（GET 真分页 / 真筛选）
    //   access-security/login-audit/scan                 跑一次扫描（POST，真去上游读）
    //   access-security/alerts                          告警列表（GET，真分页 + 真筛选 + 汇总）
    //   access-security/alerts/{uuid}                    告警详情（GET，含处置留痕）
    //   access-security/alerts/{uuid}/acknowledge        确认（新警 → 已确认）
    //   access-security/alerts/{uuid}/ignore             忽略（新警 / 已确认 → 已忽略）
    //   access-security/alerts/{uuid}/resolve            处置（新警 / 已确认 → 已处置）
    //   access-security/ip-allowlist                     IP 白名单（GET / POST）
    //   access-security/ip-allowlist/check               IP 只读判定（GET，真网段包含运算）
    //   access-security/ip-allowlist/evaluate            IP 判定 + 留痕（POST）
    //   access-security/ip-allowlist/{uuid}              修改 / 删除白名单条目（PUT / DELETE）
    //   access-security/credential-access                凭据访问审计列表（GET）/ 受控访问（POST）
    //   access-security/credential-access/credentials     按凭据汇总（GET）
    //   access-security/credential-access/{uuid}          单条凭据访问审计（GET）
    //   access-security/session                          当前调用方的会话现状 + 会话策略（GET）
    //
    // 判别顺序：**字面量子路径（capabilities / policy / scan / check / evaluate / credentials）
    // 排在 {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    // ⚠️ **末尾刻意不带 `?`**：带了就等于放行裸路径 `access-security` ——
    //   后端没有「一条汇总列表接口」，放行它会让界面以为可用。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `access-security` —— 本模块没有裸集合读
    //   * `access-security/login-audit/{uuid}` —— 登录审计**没有**按 id 取详情的端点
    //     （列表行已经带全字段；单条详情要另开端点时再放行）
    //   * `access-security/login-audit/delete` / `.../login-audit/{uuid}/delete` ——
    //     登录审计是 **append-only** 的凭据，**不做删除**
    //   * `access-security/alerts/{uuid}/delete` —— 告警的处置走状态机（确认 / 忽略 / 处置），
    //     **不是删除**；删掉告警等于删掉处置留痕
    //   * `access-security/alerts/{uuid}/reopen` —— 「已忽略 / 已处置」是**终态**，
    //     要重新处置只能等下一次告警，不提供「重开」入口
    //   * `access-security/credential-access/{uuid}/delete` —— 凭据访问审计是 **append-only**，
    //     **刻意不给 UPDATE/DELETE**（GRANT 只给 SELECT,INSERT）
    //   * `access-security/mfa/registrations/{uuid}/delete` —— MFA 登记的状态机是
    //     「停用 / 撤销」，**不提供物理删除**
    //   * `access-security/sessions` / `access-security/session/{uuid}` ——
    //     会话实体在 17 identity，**本模块不造第二套会话系统**（只读自己的会话策略与现状）
    //   * `access-security/blacklist` —— 16.5.1.1 黑名单**未交付**，本模块**不写黑名单**
    //   * `access-security/related-accounts` —— 16.5.2.2 关联风险**未交付**，
    //     本模块**不编一张「关联账号」表**
    //   * `access-security/connections/{uuid}/credential` —— **下游 2 凭据引用（2.2.1.3）已交付**
    //     （台账：`credential_ref` + `PUT /connections/{id}/credential`）。
    //     本模块**不改它的表、不改它的端点、不改它的签名**，只在它旁边加一层**访问审计**；
    //     那个写端点归 2.2.1.3，**不是本模块的形状** ⇒ 刻意不放行。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^access\-security(?:\/(?:capabilities|policy|login\-audit(?:\/scan)?|alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|ignore|resolve))?)?|mfa\/registrations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:activate|disable))?|mfa\/challenges(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/attempts)?|ip\-allowlist(?:\/check|\/evaluate|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|credential\-access(?:\/credentials|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|session))$/,
  },
  {
    // 11.4.1.1 平台纠纷列表（trade 服务，卷 11「售后与退货」L3 11.4「平台纠纷与客诉」的第一格）。
    //
    // 蓝图：「管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、
    // 举证要求、我方立场、结果记录。」输出：「纠纷单 + 时效倒计时 + 举证清单。」
    // 加粗句：「**平台纠纷有严格举证时限**，超期自动判败，是硬性时效要求。」
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 trade 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 `];`），
    // 与同批其它车道（6.3.2.1 / 6.6.2.1 / 6.3.1.3）的插入点互不重叠。
    //
    // ---- 首 token「platform-disputes」的零命中核对结论（逐条核过本文件**全部**既有正则）----
    //   本文件既有 116 条 services[] 正则，**逐条**用本模块的九个真实调用形状
    //   （platform-disputes / .../capabilities / .../{uuid} / .../evidence-items /
    //     .../evidence-items/{uuid} / .../start-evidence / .../position / .../submit-evidence /
    //     .../resolve / .../withdraw / .../settlements / .../settlements/{uuid}/confirm /
    //     .../settlements/{uuid}/reject）跑过一遍：**命中 0 条**。
    //   逐条核对的既有首 token（全部零命中）：
    //     purchase-orders / purchase-order-editor / purchase-followups / purchase-plans /
    //     purchase-requests / purchase-arrivals / purchase-reconciliation / purchase-analytics /
    //     replenishment-suggestions / development-projects / development-samples /
    //     quality-inspections / defective-dispositions / return-dispositions / return-shipbacks /
    //     quality-analysis / quality-supplier-refs / knowledge / review-requests / review-rules /
    //     supplier-claims / product-attribution / return-quality / return-receipts /
    //     rma-authorizations / rma-instructions / refunds / refund-applications /
    //     platform-order-sync / pending-inspections / putaway-tasks / goods-receipts …
    //   另外本文件**全部**正则的字面量里也不存在 "platform-dispute" 这个串
    //   （grep 全文件零命中）—— 所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-disputes                                集合读（GET，真分页 / 真筛选）+ 建单（POST）
    //   platform-disputes/capabilities                   能力声明（含上下游未交付的逐条说明）
    //   platform-disputes/{uuid}                         单据详情（GET：头 + 举证清单 + 结算分录 + 留痕）
    //   platform-disputes/{uuid}/evidence-items          加举证材料项（POST）
    //   platform-disputes/{uuid}/evidence-items/{uuid}   更新举证材料状态（PUT）/ 移除（DELETE）
    //   platform-disputes/{uuid}/start-evidence          进入举证准备（POST）
    //   platform-disputes/{uuid}/position                更新我方立场与责任判定（POST）
    //   platform-disputes/{uuid}/submit-evidence         提交举证（POST）
    //   platform-disputes/{uuid}/resolve                 记录平台裁决结果（POST）
    //   platform-disputes/{uuid}/withdraw                我方撤诉（POST）
    //   platform-disputes/{uuid}/settlements             登记结算影响（POST）
    //   platform-disputes/{uuid}/settlements/{uuid}/confirm  财务确认结算生效（POST）
    //   platform-disputes/{uuid}/settlements/{uuid}/reject   财务驳回结算影响（POST）
    //
    // 判别顺序：**字面量子路径（capabilities）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 两个动作段（{uuid}/xxx）挂在单段 {uuid} 组的可选后缀里，否则会被单段分支吃掉半截、
    // 把动作名留在匹配之外。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   platform-disputes/{uuid}/auto-lose    —— **超期自动判败是系统做的**（后端 DB 守卫①），
    //     刻意不提供手动入口：一个能提前把自己判负的按钮只会让人用它来提前结案。
    //   platform-disputes/{uuid}/evidence      —— 举证**清单**在详情里，没有单独的读端点。
    //   platform-disputes/{uuid}/upload        —— 本模块只落**清单事实**（该交什么 / 齐不齐 / 缺什么），
    //     不上传材料；材料与申诉属 11.4.1.2（未开发）。
    //   platform-disputes/{uuid}/appeal        —— 申诉提交属 11.4.1.2（未开发），本模块不越界。
    //   platform-disputes/{uuid}/sync          —— channel 服务**没有纠纷专用同步端点**
    //     （已交付的是平台目录 / 连接 / 店铺 / 同步任务 / 同步运行 / 回写 / webhook / 增量补拉），
    //     放行一个不存在的同步入口等于让界面以为平台会自动拉纠纷。
    //   platform-disputes/summary              —— 汇总在列表响应里，没有单独的汇总端点。
    //   platform-disputes/{uuid}/delete        —— 终态不可逆：纠纷凭据不能删（后端 405 + DB 守卫）。
    //   任何 DELETE / PUT 形状 —— 白名单是**按路径登记、不区分方法**的，
    //     所以「不能删」的防线在服务端与 DB，不在这里。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^platform-disputes(?:\/(?:capabilities|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:evidence-items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|start-evidence|position|submit-evidence|resolve|withdraw|settlements(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:confirm|reject))?))?))?$/,
  },
  {
    // 14.2.2.2 自动规则（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 本文件是**多车道共用的累积白名单**，既有条目（`ad-type-diffs/` 14.1.2.2、
    // `label-templates/` 10.2.2.2、`rate-comparison/` 10.1.2.2、`freight/` 10.1.2.1、
    // `platform-disputes/` 11.4.2.1 …）**一字未动、也不重排** —— 往既有那条超长单行
    // alternation 里并分支会让 git 必然判冲突，且 retry 永远不会自愈。
    //
    // 插入锚点固定在**数组末尾**（最后一条 `platform-disputes` 元素之后、数组结尾的 `];` 之前）：
    // 本批另外三条车道的插入点分别在「`auth/` 之前」「第一个 `channel/` 之前」「中段 trade 区」，
    // 与本锚点相距 3000 行以上，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   ad-rules/capabilities                能力声明（未交付段 available=false + ownerModule + 影响）
    //   ad-rules/rules                      规则集合读（GET，真分页 + 筛选）/ 登记规则（POST）
    //   ad-rules/rules/{uuid}               规则详情（GET，含条件与最近一次评估结论）
    //   ad-rules/rules/{uuid}/status        启用 / 停用规则（POST）
    //   ad-rules/rules/{uuid}/conditions    规则条件集合读（GET）
    //   ad-rules/metrics                    广告表现指标集合读（GET）/ 登记指标（POST）
    //   ad-rules/metrics/{uuid}             指标详情（GET）
    //   ad-rules/runs                       评估运行集合读（GET）/ 发起一次评估（POST）
    //   ad-rules/runs/{uuid}                运行详情（GET，含本次命中与执行记录）
    //   ad-rules/hits                       规则命中记录集合读（GET，真分页 + 筛选）
    //   ad-rules/executions                 调整执行记录集合读（GET，真分页 + 筛选）
    //   ad-rules/executions/{uuid}          执行记录详情（GET）
    //   ad-rules/dispatches                 待下发动作集合读（GET，真分页 + 筛选）
    //   ad-rules/dispatches/{uuid}/cancel   撤销待下发动作（POST）
    //   ad-rules/effects                    效果回溯集合读（GET，真分页 + 筛选）
    //   ad-rules/effects/evaluate          回溯一次（POST，把执行前后的指标变化算出来）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / rules / metrics / runs / hits /
    // executions / dispatches / effects / evaluate）排在 {uuid} 段**之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-rules` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-rules/dispatches/{uuid}/dispatch` —— **平台侧真实下发本模块不实现**，
    //     放行一个不存在的下发入口等于让界面以为「平台会自动收下这次调价」
    //   * `ad-rules/rules/{uuid}/delete` —— 规则是配置事实，撤下走 status=ARCHIVED
    //   * `ad-rules/effects/{uuid}` —— 回溯记录随列表与回溯结果一起返回，没有单独的读端点
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-rules`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-rules(?:\/(?:capabilities|rules|metrics|runs|hits|executions|dispatches|effects(?:\/evaluate)?)|\/rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|conditions))?|\/metrics\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/runs\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/executions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/dispatches\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/cancel)$/,
  },
  {
    // 16.1.1.1 账号健康总览（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有 channel 分支（listing / ad-rules / compliance-precheck / brand-authorizations …）
    // **一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，每条服务规则是一段
    // 超长单行正则，往既有那条 alternation 里并分支会让多车道同时并入时 git 必然判冲突
    // （结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`ad-rules` 那条元素之后**（channel 的最后一条元素）----
    //   同批另外三条车道的锚点分别是「`crm/message-inbox` 之后」（15.1.1.2）、
    //   「`crm/reviews` 之后」（15.2.1.2）、「`crm/customer-tags` 之后」（15.3.2.2），
    //   与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「account-health」的零命中核对结论 ----
    //   本文件既有全文里 `account-health` 出现次数 = 0（并用 `new RegExp(pat).test('…')`
    //   逐条实测过：既有全部正则对本模块的 13 个 shape **零命中**）⇒ 首 token 既是独立
    //   字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   account-health/capabilities          能力声明（未交付段 available=false + ownerModule）
    //   account-health/shops                  店铺健康卡片（GET，真分页 + 真筛选）/ 手工录入指标（POST shops/{uuid}/metrics）
    //   account-health/shops/{uuid}           单家店的健康卡片（GET）
    //   account-health/shops/{uuid}/metrics   手工录入一条绩效指标（POST，source 恒为 MANUAL）
    //   account-health/shops/{uuid}/assess    跑一次判定，落一条不可改写的凭据（POST）
    //   account-health/shops/{uuid}/sync      平台账号绩效同步（POST，**未接入 ⇒ 恒 409**）
    //   account-health/ranking                跨店铺风险排序（GET，真分页）
    //   account-health/pending-items          需处理项（GET，只给本模块真实存在的待处理项）
    //   account-health/thresholds             生效阈值清单（GET）
    //   account-health/thresholds/{metric}    改阈值（PUT，metric 是**四个字面量指标码**之一）
    //   account-health/assessments            判定历史（GET，真分页）
    //
    // 判别顺序：字面量子路径（capabilities / shops / ranking / pending-items / thresholds /
    // assessments）排在 {uuid} 段**之前** —— alternation 是首个命中即返回，顺序反了会被
    // 通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ `thresholds/{metric}` 段的 metric 是**四个字面量指标码**（ODR /
    //   LATE_SHIPMENT_RATE / CANCELLATION_RATE / VALID_TRACKING_RATE），**不是 UUID** ——
    //   用宽松的 id 段会把 `/thresholds/任意串` 放行，那是后端不存在的映射。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `account-health`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `account-health` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `account-health/violations` / `account-health/appeals` —— 16.1.2.1 违规与处罚、
    //     16.1.2.2 申诉管理**未交付**，放行等于让界面以为「违规计数 / 申诉状态」已经有了
    //   * `account-health/trends` —— 16.1.1.2 绩效指标监控（趋势 / 预警 / 跌破预测）**未交付**
    //   * `account-health/shops/{uuid}/delete` —— 指标是「当前周期的口径」，改写走录入更新，
    //     不做物理删除
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^account\-health(?:\/(?:capabilities|shops|ranking|pending\-items|thresholds|assessments)|\/shops\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:metrics|assess|sync))?|\/thresholds\/(?:ODR|LATE_SHIPMENT_RATE|CANCELLATION_RATE|VALID_TRACKING_RATE))$/,
  },
  {
    // 16.3.1.1 税号与辖区（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，
    // 每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`account-health` 那条元素之后**（16.1.1.1 账号健康总览）----
    //   同批另外三条车道的锚点分别是「`performance-metrics` 之后」、
    //   「`platform-violations` 之后」、「`cart-recovery` 之后」，
    //   与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「tax-registration」的零命中核对结论 ----
    //   本文件既有全文里 `tax-registration` 出现次数 = 0；
    //   并把既有**全部 services[] 正则**逐条实测：本模块的 8 个 shape 命中 0 处。
    //   首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   tax-registration/capabilities        能力声明（未交付下游 available=false + ownerModule）
    //   tax-registration/jurisdictions       受控辖区码表（**不是自由文本**，值域钉死在 DB 层）
    //   tax-registration/shops               店铺目录（读本服务自己的 shop 表）+ 应当登记的辖区
    //   tax-registration/registrations       登记档案清单（GET，真分页 + 真筛选）/ 登记一条（POST）
    //   tax-registration/registrations/{uuid} 单条登记详情（GET）/ 改登记（PUT，含 status=ARCHIVED 撤下）
    //   tax-registration/gaps                🔴 缺失项清单（GET，真分页 + 真筛选 + 汇总计数）
    //   tax-registration/gap-summary         🔴 缺失项汇总（GET，按类型计数 + 受影响店铺 / 主体数）
    //   tax-registration/coverage            逐店铺的辖区覆盖情况（GET）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `tax-registration` ——
    //   本模块没有裸集合读，一切入口都带子路径。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `tax-registration` —— 本模块没有裸集合读
    //   * `tax-registration/calendar` —— 16.3.1.2 申报日历**未交付**
    //   * `tax-registration/vat` / `tax-registration/ioss` —— 16.3.2.1 VAT/IOSS**未交付**
    //   * `tax-registration/epr` —— 16.3.2.2 EPR/包装法**未交付**
    //   * `tax-registration/tax-returns` —— 12.6 税务（finance 服务在本仓尚未落地）**未交付**
    //   * `tax-registration/registrations/{uuid}/delete` —— 登记是事实，撤下走 status=ARCHIVED，
    //     不做物理删除
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^tax\-registration(?:\/(?:capabilities|jurisdictions|shops|registrations|gaps|gap\-summary|coverage)|\/registrations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 16.3.1.2 申报日历（channel 服务，卷 16「合规与风控」L3 16.3.1 税号与辖区下的第二格）。
    // 纯**新增一条独立的 `services[]` 元素**：既有条目**一字未改、也不重排** —— 本文件是
    // 多车道共用的**累积型**白名单，每条服务规则是一段超长单行正则，往既有那条巨型 alternation
    // 里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`tax-registration` 那条元素之后**（16.3.1.1 税号与辖区）----
    //   本模块是 16.3.1.1 的**兄弟模块**（同一 L3），紧跟其后语义也顺。
    //   与本批其它车道（数组最开头 / identity 之前 / warehouse 之前 / 数组末尾）的插入点
    //   相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「filing-calendar」的零命中核对结论（本车道发车前实测）----
    //   `grep -n "filing-calendar" apps/web/src/lib/api-proxy.ts` = **0 命中**（无输出）⇒
    //   本条是全文件第一次出现这个串，首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //   反向核对：本条正则对兄弟模块的前缀
    //   （`tax-registration` / `vat-ioss` / `sales-tax` / `epr-registrations` / `sync-health`）
    //   **零误伤** —— 它们的首 token 与 `filing-calendar` 完全不同。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   filing-calendar/capabilities        能力声明（上游真读得到 / 下游缺口归谁 / 判定口径）
    //   filing-calendar/options             筛选项（辖区选项走 16.3.1.1 受控码表 + 责任人候选走 identity 成员目录）
    //   filing-calendar/cycle-configs       申报周期配置清单（GET）/ 登记配置（POST）
    //   filing-calendar/cycle-configs/{uuid} 改配置（PUT，含撤下 status=ARCHIVED）
    //   filing-calendar/entries             申报日历清单（GET，真分页真筛选）/ 登记申报义务（POST）
    //   filing-calendar/entries/generate    按周期配置批量生成当前申报期（POST）
    //   filing-calendar/entries/readiness   刷新数据就绪投影（POST，读 12.6.1.2 的生效底稿）
    //   filing-calendar/entries/{uuid}      单条详情（GET）/ 改条目（PUT）
    //   filing-calendar/entries/{uuid}/advance 推进状态（POST，**已提交 / 已确认必须带凭据号**）
    //   filing-calendar/reminders           到期提醒清单（GET，真分页真筛选 + 如实报总数）
    //   filing-calendar/reminders/{uuid}/acknowledge 确认提醒已知悉（POST）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   filing-calendar                          —— 裸路径：后端**没有**「一条汇总列表接口」。
    //   filing-calendar/submit                   —— 🔴 本系统**不代用户向税务机构提交申报**
    //                                                 （蓝图 12.6.1.2：「不替代税务申报动作」）。
    //   filing-calendar/receipts                 —— 同上：系统**不代造受理号**。
    //   filing-calendar/todos                    —— 🔴 下游 1.1.1.x 工作台待办**未接线**；
    //                                                 要接待办请读 `/reminders`。
    //   filing-calendar/entries/{uuid}/submit    —— 同上（没有「提交申报」这个动作）。
    //   filing-calendar/entries/{uuid}/delete    —— 申报义务是事实，**不做物理删除**。
    //   filing-calendar/not-a-uuid              —— 非 UUID 段不应被 uuid 分支吃掉。
    //   filing-calendar/entries/not-a-uuid       —— 同上。
    //   filing-calendar/reminders/{uuid}         —— 提醒按**集合**定位，没有单条查询形状。
    //   filing-calendar/reminders/{uuid}/not-a-shape —— 多一层仍然 404。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `generate` / `readiness` / `advance` 这种非 UUID 形状
    //   也当成 id 放行。
    //   字面量子路径（capabilities / options / generate / readiness / advance / acknowledge）
    //   排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^filing\-calendar\/(?:capabilities|options|cycle\-configs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|entries(?:\/generate|\/readiness|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/advance)?)?|reminders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?)$/,
  },
  {
    // 16.1.1.2 绩效指标监控（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，
    // 每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`account-health` 那条元素之后**（16.1.1.1 账号健康总览）----
    //   同批另外三条车道的锚点分别是「`message-sends` 之后」（15.1.1.2）、
    //   「`review-replies` 之后」（15.2.1.2）、「`customer-contacts` 之后」（15.3.2.2），
    //   与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「performance-metrics」的零命中核对结论 ----
    //   本文件既有全文里 `performance-metrics` 出现次数 = 0；
    //   并把既有**全部 153 条** `services[]` 正则（单行与多行都抽出来）逐条实测：
    //   13 个本模块 shape × 153 条既有正则 ⇒ **命中 0 处**。
    //   既有 `account-health` 那条正则（首 token 是 `account\-health`，与本条同为 `^` 锚定）
    //   对本模块 13 个 shape **零命中** —— 两者互不遮蔽。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   performance-metrics/capabilities          能力声明（未交付下游 available=false + ownerModule）
    //   performance-metrics/shops                  店铺范围清单（GET，真分页 + 真筛选）
    //   performance-metrics/trends                 趋势序列清单（GET，真分页 + 真筛选）
    //   performance-metrics/alerts                 阈值预警凭据（GET，真分页 + 真筛选）
    //   performance-metrics/forecasts              跌破预测凭据（GET，真分页 + 真筛选）
    //   performance-metrics/alert-rules            生效预警阈值清单（GET）
    //   performance-metrics/alert-rules/{metric}   改预警阈值（PUT，metric 是**六个字面量指标码**之一）
    //   performance-metrics/shops/{uuid}/trends             单店全部指标的趋势序列（GET）
    //   performance-metrics/shops/{uuid}/metrics            录入本模块自有指标（POST，source 恒为 MANUAL）
    //   performance-metrics/shops/{uuid}/alert-evaluations  跑一次阈值预警评估（POST，落追加式凭据）
    //   performance-metrics/shops/{uuid}/forecasts           跑一次跌破预测（POST，落追加式凭据）
    //   performance-metrics/shops/{uuid}/forecast            该店最近一次的预测结论（GET）
    //
    // 判别顺序：字面量子路径（capabilities / shops / trends / alerts / forecasts / alert-rules）
    // 排在 {uuid} 段**之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ `shops/{uuid}` 后面**必须**跟一个动作段（trends / metrics / alert-evaluations /
    //   forecasts / forecast）：本模块**没有**「单个店铺详情」这个端点，
    //   所以 {uuid} 段后面的动作段**刻意不带 `?`**（带了就把 `shops/{uuid}` 本身也放行了）。
    // ⚠️ `alert-rules/{metric}` 段的 metric 是**六个字面量指标码**（ODR /
    //   LATE_SHIPMENT_RATE / CANCELLATION_RATE / VALID_TRACKING_RATE / RESPONSE_TIME /
    //   ACCOUNT_RATING），**不是 UUID** —— 用宽松的 id 段会把 `/alert-rules/任意串` 放行，
    //   那是后端不存在的映射。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `performance-metrics`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `performance-metrics` —— 本模块没有裸集合读
    //   * `performance-metrics/shops/{uuid}` —— 没有「单个店铺详情」端点
    //   * `performance-metrics/workbench-alerts` —— 1 工作台预警**未交付**，本模块**不编造**推送
    //   * `performance-metrics/fulfillment-alerts` —— 9 订单履约（迟发预警前置）**未交付**，
    //     本模块**不编造**履约侧联动
    //   * `performance-metrics/shops/{uuid}/delete` —— 指标是「当前周期的口径」，
    //     改写走录入更新（POST metrics），不做物理删除
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^performance\-metrics(?:\/(?:capabilities|shops|trends|alerts|forecasts|alert\-rules)|\/shops\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:trends|metrics|alert\-evaluations|forecasts|forecast)|\/alert\-rules\/(?:ODR|LATE_SHIPMENT_RATE|CANCELLATION_RATE|VALID_TRACKING_RATE|RESPONSE_TIME|ACCOUNT_RATING))$/,
  },
  {
    // 16.2.1.1 规则库（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，
    // 每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`performance-metrics` 那条元素之后**（16.1.1.2 绩效指标监控）----
    //   同批另外三条车道的锚点是「`account-health` 之后」/「`platform-violations` 之后」
    //   /「`cart-recovery` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「compliance-rules」的零命中核对结论 ----
    //   本文件既有全文里 `compliance-rules` 出现次数 = 0；
    //   并把既有**全部 160 条** `services[]` 正则逐条实测：
    //   11 个本模块 shape × 160 条既有正则 ⇒ **命中 0 处**
    //   （实测脚本 `.tmp/verify-api-proxy.mjs`）。
    //   反向也测了：既有模块入口（account-health / performance-metrics /
    //   platform-violations / compliance-precheck / listing-compliance-precheck /
    //   brand-authorizations / listings / shops / listing-editor / ad-rules）
    //   被本条正则**零命中** ⇒ 互不遮蔽。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   compliance-rules/capabilities          能力声明（未交付下游 available=false + ownerModule）
    //   compliance-rules/shops                  店铺范围清单（GET，真分页 + 真统计）
    //   compliance-rules/rules                  规则集（GET，真分页 + 真筛选）/ 建规则（POST，**幂等**）
    //   compliance-rules/rules/{uuid}           规则详情（GET，含全部版本）/ 改生命周期（PUT）
    //   compliance-rules/rules/{uuid}/versions  版本清单（GET）/ **发新版本**（POST，规则改动的唯一入口）
    //   compliance-rules/rules/{uuid}/versions/effective      某一刻生效的是哪一版（GET，**可解释性**）
    //   compliance-rules/rules/{uuid}/versions/{versionId}     版本详情（GET）
    //   compliance-rules/rules/{uuid}/versions/{versionId}/terms        词条清单（GET）/ 追加词条（POST）
    //   compliance-rules/rules/{uuid}/versions/{versionId}/terms/upload 词库上传（POST，**整份一次落库**）
    //   compliance-rules/rules/{uuid}/versions/{versionId}/scopes       适用范围清单（GET）/ 追加（POST）
    //   compliance-rules/effective               生效规则集（GET，真分页；解析时刻可指定）
    //
    // 判别顺序：字面量子路径（capabilities / shops / effective / rules）排在 {uuid} 段
    // **之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ `versions/{versionId}` 后面**必须**跟一个动作段（terms / scopes）：
    //   本模块**没有**「只按版本 id 取详情」以外的其它动作，
    //   所以 {versionId} 段后面的动作段**刻意不带 `?`**。
    // ⚠️ `versions/effective` 是**字面量**：它是「某一刻生效的是哪一版」这个端点，
    //   不是 uuid 段的一部分 —— 放在 uuid 段之前才不会被吃掉。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `compliance-rules`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `compliance-rules` —— 本模块没有裸集合读
    //   * `compliance-rules/executions` / `rules/{uuid}/executions` / `rules/{uuid}/validate`
    //     / `rules/{uuid}/exemptions` —— 16.2.1.2 拦截执行**未交付**，本模块**不编造**放行形状
    //   * `compliance-rules/precheck` / `compliance-rules/scans` —— 4.2.3.2 合规预检走的是
    //     **它自己的** `listing-compliance-precheck` 前缀，不是本模块的
    //   * `rules/{uuid}/terms` / `rules/{uuid}/severity` / `rules/{uuid}/scopes`
    //     —— 改规则**只能发新版本**，没有「就地改词 / 改严重度」的入口
    //   * `rules/{uuid}/versions/{versionId}/delete` / `rules/{uuid}/delete`
    //     —— 版本 / 词条 / 适用范围都不做物理删除
    //   * `compliance-rules/overview` / `compliance-rules/stats` —— 没有这两个汇总端点
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^compliance\-rules(?:\/(?:capabilities|shops|effective|rules)|\/rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/versions(?:\/(?:effective|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:terms(?:\/upload)?|scopes))?))?)?)$/,
  },
  {
    // 16.2.1.2 拦截执行（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，
    // 每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`compliance-rules` 那条元素之后**（16.2.1.1 规则管理）----
    //   同批另外三条车道的锚点是「`platform-appeals` 之后」/「`cart-recovery` 之后」
    //   /「`service-ticket-process` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「compliance-intercepts」的零命中核对结论 ----
    //   本文件既有全文里 `compliance-intercepts` 出现次数 = 0（实测）；
    //   既有条目里的合规前缀是 `compliance-rules`（16.2.1.1）/ `compliance-precheck`（16.2.2.1）
    //   / `listing-compliance-precheck`（4.2.3.2）—— **首 token 独立，互不遮蔽**。
    //   🔴 刻意**不**用 `compliance-rules` 前缀扩展：那是 16.2.1.1 的，
    //   扩展它会把 16.2.1.1 已交付的路由遮蔽掉（页面 404，而 typecheck / lint / vitest 全绿）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   compliance-intercepts/capabilities          能力声明（未交付下游 available=false + ownerModule）
    //   compliance-intercepts/policy                 拦截策略清单（GET）
    //   compliance-intercepts/policy/{scene}         改拦截策略（PUT，**拦截默认不生效**，这里才开）
    //   compliance-intercepts/decisions              判定端点（POST）+ 判定历史（GET，真分页 + 真筛选）
    //   compliance-intercepts/decisions/{uuid}       单次判定详情（GET）
    //   compliance-intercepts/exemptions             申请豁免（POST）/ 豁免单清单（GET，真分页 + 真筛选）
    //   compliance-intercepts/exemptions/{uuid}      豁免单详情（GET）
    //   compliance-intercepts/exemptions/{uuid}/approve  审批豁免（POST，**申请人 ≠ 审批人**）
    //   compliance-intercepts/exemptions/{uuid}/reject   驳回豁免（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ `policy/{scene}` 段的 scene 是**受控枚举字面量**（三个拦截点），不是自由文本 ——
    //   放行任意字符串等于放行一条后端不存在的路径。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `compliance-intercepts`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `compliance-intercepts` —— 本模块没有裸集合读
    //   * `compliance-intercepts/exemptions/{uuid}/delete` —— 豁免单是「凭什么放行」的证据，不可删
    //   * `compliance-intercepts/exemptions/{uuid}/revoke` —— 豁免**终态不可回退**，没有「撤销」这个动作
    //   * `compliance-intercepts/decisions/{uuid}/edit` —— 判定凭据**只追加**，不可改写
    //   * `compliance-intercepts/withdrawals` / `shipments` —— 12 提现与 8.3.4.1 发货
    //     **未接入**（能力声明里 available=false），**不编**放行形状
    //   * `compliance-intercepts/rules` —— 规则库归 16.2.1.1，走它自己的 `compliance-rules` 前缀
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^compliance\-intercepts(?:\/(?:capabilities|policy(?:\/(?:LISTING_PUBLISH|SHIPMENT|WITHDRAWAL))?|decisions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|exemptions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:approve|reject))?)?))$/,
  },
  {
    // 16.1.2.1 违规与处罚（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目**一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，
    // 每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`performance-metrics` 那条元素之后**（16.1.1.2 绩效指标监控）----
    //   同卷同服务的兄弟模块，锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「platform-violations」的零命中核对结论 ----
    //   本文件既有全文里 `platform-violations` 出现次数 = 0；
    //   并把既有**全部 156 条** `services[]` 正则逐条实测：
    //   16 个本模块 shape × 156 条既有正则 ⇒ **命中 0 处**。
    //   反向也测了：既有模块（account-health / performance-metrics / compliance-precheck /
    //   listing-compliance-precheck / brand-authorizations / listings / shops /
    //   listing-editor / ad-rules）的入口被本条正则**零命中** ⇒ 互不遮蔽。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-violations/capabilities          能力声明（未交付下游 available=false + ownerModule）
    //   platform-violations/shops                 店铺范围清单（GET，真分页）
    //   platform-violations/violations            违规记录清单（GET，真分页 + 真筛选）/ 人工登记（POST）
    //   platform-violations/violations/{uuid}     违规详情（GET）
    //   platform-violations/violations/{uuid}/status     处理状态流转（POST）
    //   platform-violations/violations/{uuid}/penalties   记一条处罚措施（POST）
    //   platform-violations/violations/{uuid}/subjects    批量挂涉及商品/订单（POST）
    //   platform-violations/violations/{uuid}/affected   批量定位受影响 listing 与库存（GET）
    //   platform-violations/violations/{uuid}/delist      批量下架（POST，下游 4 刊登 4.1.1.1）
    //   platform-violations/violations/{uuid}/remediations 记一条整改要求（POST）
    //   platform-violations/remediations           整改要求清单（GET，真分页）
    //   platform-violations/remediations/{uuid}    整改状态流转（PUT）
    //   platform-violations/remediations/{uuid}/evidence 追加整改凭证（POST）
    //   platform-violations/penalties              处罚清单（GET，真分页 + 按币种分组的合计）
    //   platform-violations/sync-runs              同步运行记录（GET，真分页）
    //   platform-violations/shops/{uuid}/sync      同步入口：接收平台违规通知（POST，**幂等**）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `platform-violations`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `platform-violations` —— 本模块没有裸集合读
    //   * `platform-violations/violations/{uuid}/delete` —— 违规记录不做物理删除
    //   * `platform-violations/appeals` —— 16.1.2.2 申诉管理**未交付**，本模块**不编造**申诉单
    //   * `platform-violations/finance` —— 12 财务**未交付**，本模块**不建**财务表
    //   * `platform-violations/workbench-alerts` —— 1 工作台预警**未交付**，本模块**不编造**推送
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^platform\-violations(?:\/(?:capabilities|shops|violations|remediations|penalties|sync\-runs)|\/shops\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/sync|\/violations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|penalties|subjects|affected|delist|remediations))?|\/remediations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/evidence)?)$/,
  },
  {
    // 16.1.2.2 申诉管理（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目（`platform-violations` 16.1.2.1 / `account-health` 16.1.1.1 /
    // `performance-metrics` 16.1.1.2 / `compliance-precheck` 16.2.2.1 …）**一字未动、也不重排**
    // —— 本文件是多车道共用的**累积型**白名单，每条服务规则是一段超长单行正则，
    // 往既有那条巨型 alternation 里并分支会让多车道同时并入时 git 必然判冲突。
    //
    // ---- 插入锚点固定在 **`platform-violations` 那条元素之后**（16.1.2.1 违规与处罚）----
    //   同卷同服务的兄弟模块，锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「platform-appeals」的零命中核对结论 ----
    //   本文件既有 161 条 services[] 正则里含 `platform` 的只有五条：
    //   `platforms`（02 渠道，锚在 `^platforms`）/ `platform-orders`（09）/ `platform-fees`（12）/
    //   `platform-dispute-appeals`（11.4.1.1 trade）/ `platform-disputes`（11.4.1）/
    //   `platform-violations`（16.1.2.1）。
    //   它们**全部**锚在各自不同的字面词上（`^platforms` / `^platform\-orders` / …），
    //   逐条实测：本条正则的 14 个 shape 对这 161 条既有正则 ⇒ **命中 0 处**；
    //   反向也测了：`platform-violations` / `platform-dispute-appeals` 的入口被本条正则
    //   **零命中** ⇒ 互不遮蔽。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-appeals/capabilities              能力声明（下游平台申诉接口 available=false + ownerModule）
    //   platform-appeals/shops                     店铺范围清单（GET，真分页）
    //   platform-appeals/appeals                   申诉单清单（GET，真分页 + 真筛选）/ 新建申诉单（POST）
    //   platform-appeals/appeals/{uuid}            申诉单详情（GET）
    //   platform-appeals/appeals/{uuid}/text       改申诉文本（PUT，**只有草稿态能改**）
    //   platform-appeals/appeals/{uuid}/evidence   追加举证材料（POST，**append-only**）
    //   platform-appeals/appeals/{uuid}/submit     提交申诉（POST，**消耗一次申诉配额**）
    //   platform-appeals/appeals/{uuid}/decision   记录平台裁定（POST，通过 / 驳回）
    //   platform-appeals/appeals/{uuid}/withdraw   撤回申诉（POST，终态）
    //   platform-appeals/appeals/{uuid}/reopen     驳回后回到草稿（POST，补充材料再提）
    //   platform-appeals/quotas                    申诉配额清单（GET）/ 配置配额（PUT）
    //   platform-appeals/stats                     申诉次数统计（GET）
    //   platform-appeals/priority-queue            **优先申诉队列**（GET，按影响档位从高到低）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `platform-appeals`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `platform-appeals` —— 本模块没有裸集合读
    //   * `platform-appeals/appeals/{uuid}/delete` —— 申诉单不做物理删除
    //   * `platform-appeals/appeals/{uuid}/platform-submit` —— 平台申诉接口**本仓不存在**，
    //     「提交」只落**提交记录**，**不编造**一个真的把申诉送到平台的入口
    //   * `platform-appeals/platform` —— 同上，平台侧适配器尚未接入
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^platform\-appeals(?:\/(?:capabilities|shops|appeals|quotas|stats|priority\-queue)|\/appeals\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:text|evidence|submit|decision|withdraw|reopen))?)$/,
  },
  {
    // 16.3.2.2 EPR / 包装法（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目（`platform-violations` 16.1.2.1 / `platform-appeals` 16.1.2.2 /
    // `account-health` 16.1.1.1 / `performance-metrics` 16.1.1.2 / `compliance-precheck` 16.2.2.1 …）
    // **一字未动、也不重排** —— 本文件是多车道共用的**累积型**白名单，每条服务规则是一段超长单行正则，
    // 往既有那条巨型 alternation 里并分支会让多车道同时并入时 git 必然判冲突。
    //
    // ---- 插入锚点固定在 **`platform-appeals` 那条元素之后**（16.1.2.2 申诉管理）----
    //   同批同服务的兄弟模块，锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「epr-registrations」的零命中核对结论 ----
    //   本文件既有 `services[]` 正则里含 `epr` 的只有 `tax-registration` 那条元素里的一句**注释**
    //   （`tax-registration/epr` 是 16.3.1.1 白名单里被**拒**的形状），**没有任何正则**以 `epr` 开头。
    //   实测本文件全文出现 `epr-registrations` **0** 次、出现 `epr-registration` **0** 次。
    //   ⚠️ **不要**用 `tax-registration` 前缀扩展 —— 那是 16.3.1.1 的，会遮蔽它的路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   epr-registrations/capabilities          能力声明（下游 available=false + ownerModule）
    //   epr-registrations/regimes               制度码表 + 每个制度在哪些国家适用、适用哪些品类
    //   epr-registrations/categories            适用品类码表（受控值域）
    //   epr-registrations/shops                 店铺目录（GET，真分页）+ 推导出的「需要 EPR 的国家」
    //   epr-registrations/registrations         登记档案清单（GET，真分页 + 真筛选）/ 新建登记（POST）
    //   epr-registrations/registrations/{uuid}  登记详情（GET）/ 改登记（PUT，**版本化**：关旧版 + 建新版）
    //   epr-registrations/registrations/{uuid}/versions  生效版本历史（GET）
    //   epr-registrations/coverage              覆盖情况（GET，真分页 + 真筛选；国家 × 品类反查）
    //   epr-registrations/compliance-status     按国家的合规状态（GET）
    //   epr-registrations/publish-gate          **刊登闸门**（POST，服务端真算的结论 + 逐条原因）
    //   epr-registrations/publish-gate/batch    批量刊登闸门（POST）
    //   epr-registrations/filings               申报期状态（GET，真分页 + 真筛选）
    //   epr-registrations/fees                  费用清单（GET，真分页 + 真筛选）/ 记一笔（POST）
    //   epr-registrations/fees/{uuid}           改一笔费用（PUT）
    //   epr-registrations/fee-summary           费用汇总（GET，**按币种分组**）
    //   epr-registrations/exemptions            不适用声明清单（GET）/ 声明不适用（POST）
    //   epr-registrations/exemptions/{uuid}     撤销不适用声明（PUT，关窗口不删行）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    //   `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当 id 放行）。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `epr-registrations`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `epr-registrations` —— 本模块没有裸集合读
    //   * `epr-registrations/registrations/{uuid}/delete` —— 登记不做物理删除（改 = 新版本）
    //   * `epr-registrations/finance` —— 12 财务在本仓**尚未落地**，本模块**不建**财务凭证表
    //   * `epr-registrations/vat` / `epr-registrations/ioss` —— 16.3.2.1 VAT/IOSS **未交付**
    //   * `epr-registrations/calendar` —— 16.3.1.2 申报日历**未交付**（本模块只算申报期窗口）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^epr\-registrations(?:\/(?:capabilities|regimes|categories|shops|registrations|coverage|compliance\-status|publish\-gate|filings|fees|fee\-summary|exemptions)|\/publish\-gate\/batch|\/registrations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/versions)?|\/fees\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/exemptions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 16.3.2.3 销售税与 Marketplace Facilitator（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目（`epr-registrations` 16.3.2.2 / `platform-violations` 16.1.2.1 /
    // `platform-appeals` 16.1.2.2 / `account-health` 16.1.1.1 / `performance-metrics` 16.1.1.2 /
    // `compliance-precheck` 16.2.2.1 …）**一字未动、也不重排** —— 本文件是多车道共用的
    // **累积型**白名单，每条服务规则是一段超长单行正则，往既有那条巨型 alternation 里并分支
    // 会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`epr-registrations` 那条元素之后**（16.3.2.2 EPR/包装法）----
    //   同 L3 的兄弟模块挨着放，语义上最贴切；与同批其它车道的插入点相距数百行，
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「sales-tax」的零命中核对结论 ----
    //   本文件既有 `services[]` 正则里**没有任何一条**以 `sales-tax` 开头；
    //   既有 channel 元素的首 token 是 `epr-registrations` / `platform-appeals` /
    //   `platform-violations` / `account-health` / `performance-metrics` /
    //   `compliance-precheck` / `listing-compliance-precheck` / `brand-authorizations` …
    //   **全不相同** ⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   ⚠️ **不要**用 `tax-registration` 前缀扩展 —— 那是 16.3.1.1 的，会遮蔽它的路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   sales-tax/capabilities          能力声明（下游 available=false + ownerModule + 判定口径）
    //   sales-tax/states                **各州税务状态**（GET，真分页 + 真筛选）
    //   sales-tax/shops                 店铺目录（GET，真分页）+ 推导出的销售税州
    //   sales-tax/rate-rules            税率规则清单（GET，真分页 + 真筛选）/ 录入一版（POST）
    //   sales-tax/rate-rules/effective  某一刻生效的是哪一版（GET，**可解释性**）
    //   sales-tax/rate-rules/{uuid}     规则详情（GET）/ **发新版本**（PUT，封旧版 + 建新版）
    //   sales-tax/rate-rules/{uuid}/archive  撤下一版（PUT，**不物理删除**：规则是审计凭据）
    //   sales-tax/shop-states           店铺所在州清单（GET，真分页）/ 登记（POST）
    //   sales-tax/shop-states/{uuid}    改一条所在州（PUT）
    //   sales-tax/facilitator-scopes    **按平台的代扣代缴说明**（GET，真分页）/ 登记（POST）
    //   sales-tax/facilitator-scopes/{uuid}  改一条说明（PUT）
    //   sales-tax/facilitator-reports   代扣代缴报表清单（GET，真分页 + 真筛选）
    //   sales-tax/facilitator-reports/import  **上传 + 真解析 + 真落库**（POST）
    //   sales-tax/facilitator-reports/{uuid}   报表详情 + 逐行解析结果（GET）
    //   sales-tax/reconciliations       对账结果清单（GET，真分页 + 真筛选）/ **真算一次对账**（POST）
    //   sales-tax/obligations           申报义务视图（GET，真分页 + 真筛选；下游 12.6 接手用）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `capabilities` / `effective` / `import` 这种非 UUID 形状
    //   也当 id 放行）。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `sales-tax`
    //   （后端没有「一条汇总列表接口」，一切入口都带子路径）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `sales-tax` —— 本模块没有裸集合读
    //   * `sales-tax/filings` / `sales-tax/calendar` —— 16.3.1.2 申报日历与 12.6 申报**未交付**
    //   * `sales-tax/vat` / `sales-tax/ioss` —— 16.3.2.1 VAT/IOSS **未交付**
    //   * `sales-tax/rate-rules/{uuid}/delete` —— 规则不做物理删除（改 = 发新版本）
    //   * `sales-tax/reports` —— 本模块的报表在 `facilitator-reports` 下面，不另开一条
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^sales\-tax(?:\/(?:capabilities|states|shops|rate-rules|rate\-rules\/effective|shop\-states|facilitator\-scopes|facilitator\-reports|facilitator\-reports\/import|reconciliations|obligations)|\/rate\-rules\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/archive)?|\/shop\-states\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/facilitator\-scopes\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\/facilitator\-reports\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 15.6.2.2 购物车与弃单挽回（crm 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目（`buyer-emails` 15.1.2.1 / `knowledge` 15.4.2.1 / `review-requests` 15.2.2.1 /
    // `review-rules` 15.2.2.2）**一字未动、也不重排** —— 本文件是多车道共用的**累积型**
    // 白名单，每条服务规则是一段超长单行正则，往既有那条 alternation 里并分支会让多车道
    // 同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（最后一条元素之后、数组结尾的 `];` 之前）----
    //   同批另外三条车道的锚点分别是「`crm/knowledge` 之后」「`crm/review-requests` 之后」
    //   「`crm/review-rules` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「cart-recovery」的零命中核对结论 ----
    //   本文件既有 141 条 services[] 正则里**没有任何一条**含 `cart` 字样
    //   （既有的是 `buyer-emails` / `knowledge` / `review-requests` / `review-rules` 一类），
    //   并用 `new RegExp(pat).test('…')` 逐条实测过：既有全部正则对本模块的 18 个 shape
    //   **零命中**（0/141×18）⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //   追加本条后 services[] 共 **142** 条（净 +1，既有条目一字未动）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cart-recovery/capabilities                能力声明（未交付段 available=false + ownerModule）
    //   cart-recovery/policy                     触达频次上限 + 弃单超时（GET 读 / PUT 改）
    //   cart-recovery/shops                      店铺目录（上游 channel 的真实清单 + 平台类型）
    //   cart-recovery/shops/{uuid}               声明店铺平台类型（PUT）
    //   cart-recovery/abandonments               弃单清单（GET，真分页 + 真筛选）/ 登记弃单（POST）
    //   cart-recovery/abandonments/{uuid}        单条弃单详情（GET）
    //   cart-recovery/abandonments/{uuid}/touches   触达记录（GET）/ 登记触达（POST）
    //   cart-recovery/abandonments/{uuid}/recoveries 登记挽回成交（POST）
    //   cart-recovery/touches/{uuid}/result      回填触达结果（POST）
    //   cart-recovery/messages                   挽回话术清单（GET）/ 新建草稿（POST）
    //   cart-recovery/messages/{uuid}            话术详情（GET）/ 改内容（PUT）
    //   cart-recovery/messages/{uuid}/approve    审核话术（POST）
    //   cart-recovery/messages/{uuid}/enable     启用话术（POST）
    //   cart-recovery/messages/{uuid}/disable    停用话术（POST）
    //   cart-recovery/unsubscribes               退订名单（GET）/ 登记退订（POST）
    //   cart-recovery/unsubscribes/{uuid}        取消退订（DELETE）
    //   cart-recovery/recovery-rate              挽回率报表（GET，服务端真算）
    //   cart-recovery/sync                       平台购物车事件同步（POST，**恒 409**）
    //
    // 判别顺序：**字面量子路径（capabilities / policy / recovery-rate / sync / shops /
    // messages / unsubscribes / touches / abandonments）排在 {uuid} 段之前** ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `cart-recovery` ——
    //   后端没有「一条不存在的汇总列表接口」，放行它会让界面以为可用。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^cart\-recovery\/(?:capabilities|policy|recovery\-rate|sync|shops(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|messages(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:approve|enable|disable))?)?|unsubscribes(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|touches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/result)?|abandonments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:touches|recoveries))?)?)$/,
  },
  {
    // 16.4.1.1 侵权库（channel 服务）。纯**新增一条独立的 `services[]` 元素**：
    // 既有条目（`brand-authorizations` 16.4.2.1 / `brand-credentials` 16.4.2.2 /
    // `compliance-rules` 16.2.1.1 / `cart-recovery` 15.6.2.2 …）**一字未动、也不重排** ——
    // 本文件是多车道共用的**累积型**白名单，每条服务规则是一段超长单行正则，
    // 往既有那条巨型 alternation 里并分支会让多车道同时并入时 git 必然判冲突
    // （结构性，retry 永远不会自愈）。
    //
    // ---- 插入锚点固定在 **`cart-recovery` 那条元素之后**（15.6.2.2 弃单挽回）----
    //   同批另外三条车道的锚点分别是「`compliance-rules` 之后」/「`platform-appeals` 之后」/
    //   「`service-ticket-process` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 首 token「ip-risk-library」的零命中核对结论 ----
    //   本文件既有全文里 `ip-risk-library` 出现次数 = **0**（已实测），
    //   且它与既有首 token（`brand-authorizations` / `brand-credentials` /
    //   `compliance-rules` / `cart-recovery` …）**不是前缀关系** ⇒ 双向都不会误吃。
    //   ⚠️ **不要**用 `brand-authorizations` / `brand-credentials` 前缀扩展 ——
    //   那是 16.4.2.1 / 16.4.2.2 的，会遮蔽它们的路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   ip-risk-library/capabilities      能力声明（未交付下游 available=false + ownerModule）
    //   ip-risk-library/platforms         适用平台目录（channel 自己的 `platform` 目录）
    //   ip-risk-library/brands            3.3.2 品牌候选（catalog，**跨服务只读**）
    //   ip-risk-library/entries           条目清单（GET，真分页 + 真筛选）/ 登记条目（POST，**幂等**）
    //   ip-risk-library/entries/{uuid}    条目详情（GET）/ 改条目（PUT，**整体替换**）
    //   ip-risk-library/entries/{uuid}/status  启用 · 停用（PUT，**停用必写理由**）
    //   ip-risk-library/imports           批量导入（POST，**行级结果 + 幂等 + 部分失败不回滚**）
    //   ip-risk-library/imports/{uuid}    导入批次详情（逐行结果）
    //   ip-risk-library/platform-summary  平台风险汇总（**反查**入口）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //（不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ip-risk-library` ——
    //   后端没有「一条汇总列表接口」，放行它会让界面以为可用。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ip\-risk\-library\/(?:capabilities|platforms|brands|platform\-summary|entries(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|imports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 15.4.1.2 工单处理（crm 服务）
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（最后一条元素之后、数组结尾的 `];` 之前）----
    //   同批另外三条车道的锚点分别是「`account-health` 之后」「`performance-metrics` 之后」
    //   「`platform-violations` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排**。往既有那条巨型 alternation 里并分支会让多车道
    //   同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「service-ticket-process」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `service-ticket-process` 这个串
    //   （既有的同族首 token 是 `service-tickets` —— 15.4.1.1 工单列表，
    //   以及 trade 侧那条 `service-tickets` 写端点分支）。
    //   ⚠️ `service-ticket-process` 与 `service-tickets` **不是前缀关系**：
    //   前者的第 5 段是 `process`、后者是 `s`，两者在第 13 个字符处就分叉，
    //   所以既有那条 `^service\-tickets...$` 分支**不会**吃掉本模块的任何路径，
    //   本模块的分支也**不会**吃掉 15.4.1.1 的任何路径（双向都实测过）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   service-ticket-process/capabilities                   能力声明（逐维 available + ownerModule）
    //   service-ticket-process/tickets                        处理队列（GET，真分页 / 真筛选）
    //   service-ticket-process/tickets/{uuid}                 单张工单的处理视图（GET）
    //   service-ticket-process/tickets/{uuid}/notes           备注与回复（GET 列表 / POST 新建）
    //   service-ticket-process/tickets/{uuid}/actions         处理记录（GET，append-only 动作时间线）
    //   service-ticket-process/tickets/{uuid}/links           关联单据（GET 列表 / POST 关联）
    //   service-ticket-process/tickets/{uuid}/resolutions      解决确认（GET 列表 / POST 确认）
    //   service-ticket-process/tickets/{uuid}/resolutions/{uuid}/revoke  撤销解决确认（POST）
    //   service-ticket-process/tickets/{uuid}/replies         回复（POST）
    //   service-ticket-process/tickets/{uuid}/status          状态流转（POST）
    //   service-ticket-process/tickets/{uuid}/close           关闭工单（POST，**≠ 解决**）
    //   service-ticket-process/tickets/{uuid}/satisfaction    满意度回收（POST）
    //   service-ticket-process/tickets/{uuid}/sla/pause       SLA 暂停（POST）
    //   service-ticket-process/tickets/{uuid}/sla/resume      SLA 恢复（POST）
    //   service-ticket-process/tickets/{uuid}/escalate        升级（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / tickets）排在 {uuid} 段之前** ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ **末尾刻意不带 `?`**：带了就等于放行裸路径 `service-ticket-process` ——
    //   后端没有「一条不存在的汇总列表接口」，放行它会让界面以为可用。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^service\-ticket\-process\/(?:capabilities|tickets(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:notes|replies|status|close|resolutions|satisfaction|links|actions|escalate)(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/revoke)?|\/sla\/(?:pause|resume))?)?)?$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 16.5.1.2 买家风险评分（channel 服务）
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（最后一条元素之后、数组结尾的 `];` 之前）----
    //   同批另外三条车道的锚点分别是「`compliance-rules` 之后」「`platform-appeals` 之后」
    //   「`cart-recovery` 之后」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条巨型 alternation 里并分支
    //   会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「buyer-risk-scores」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `buyer-risk-scores` 这个串
    //   （既有的是 `buyer-emails` —— 15.1.2.1 买家邮箱，在 crm 服务下；
    //   以及 trade 侧的 `order-review` / `risk-validation` 分支）。
    //   ⚠️ `buyer-risk-scores` 与 `buyer-emails` **不是前缀关系**：
    //   前者的第 6 个字符是 `-`、后者是 `-`，但第 7 个字符就分叉（`r` vs `e`），
    //   所以既有那条 `^buyer\-emails...$` 分支**不会**吃掉本模块的任何路径，
    //   本模块的分支也**不会**吃掉 15.1.2.1 的任何路径（双向都实测过）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   buyer-risk-scores/capabilities                   能力声明（逐维 available + ownerModule）
    //   buyer-risk-scores/rule                          评分规则（GET 读 / PUT 改）
    //   buyer-risk-scores/buyers                        买家名册（GET，真分页 / 真搜索）
    //   buyer-risk-scores/evaluate                      跑一次评分（POST）
    //   buyer-risk-scores/scores                        评分历史（GET，真分页 / 真筛选）
    //   buyer-risk-scores/scores/{uuid}                 单次评分详情（GET）
    //
    // 判别顺序：**字面量子路径（capabilities / rule / buyers / evaluate / scores）排在
    // {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ **末尾刻意不带 `?`**：带了就等于放行裸路径 `buyer-risk-scores` ——
    //   后端没有「一条汇总列表接口」，放行它会让界面以为可用。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `buyer-risk-scores` —— 本模块没有裸集合读
    //   * `buyer-risk-scores/scores/{uuid}/delete` —— 评分是判定凭据，**append-only**，不做删除
    //   * `buyer-risk-scores/blacklist` —— 16.5.1.1 黑名单**未交付**，本模块**不写黑名单**
    //   * `buyer-risk-scores/related-accounts` —— 16.5.2.2 关联风险**未交付**，
    //     本模块**不编一张「关联账号」表**
    //   * `buyer-risk-scores/orders/{uuid}/status` —— 改订单状态是 9.2 的事，
    //     本模块**只出建议**
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^buyer\-risk\-scores\/(?:capabilities|rule|buyers|evaluate|scores(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 16.6.1.1 PII 管理（channel 服务）
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（`buyer-risk-scores` 那条之后、数组结尾的 `];` 之前）----
    //   同批另外三条车道的锚点分别是「`prefix: 'identity/'` 之前」「`// 14.5.2.1 带货归因` 之前」
    //   「`// 11.4.1.1 平台纠纷列表` 之前」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条巨型 alternation 里并分支
    //   会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「pii-access」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `pii-access` 这个串
    //   （本仓既有全文里 `pii` 单独出现次数也是 0）。
    //   ⚠️ `pii-access` 与既有的 `buyer-risk-scores` / `ip-risk-library` / `epr-registrations`
    //   **都不是前缀关系**（首 token 就分叉），所以既有分支**不会**吃掉本模块的任何路径，
    //   本模块的分支也**不会**吃掉别人的任何路径（双向都实测过）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   pii-access/capabilities                     能力声明（脱敏规则 / 授权模型 / 留痕与导出规则）
    //   pii-access/policy                          策略（GET 读 / PUT 改：导出限量 / 审批有效期）
    //   pii-access/grants                          授权配置（GET 列表 / PUT 登记）
    //   pii-access/grants/{uuid}/revoke            撤销授权（POST，理由必填）
    //   pii-access/subjects                        PII 主体列表（GET，真分页 + 真搜索，**默认脱敏**）
    //   pii-access/subjects/{kind}/{ref}           单个主体（GET，默认脱敏；reveal=true 走授权判定）
    //   pii-access/exports                         导出申请列表（GET）/ 提交申请（POST）
    //   pii-access/exports/{uuid}                  导出申请详情（GET，含逐条清单）
    //   pii-access/exports/{uuid}/approve          批准（POST）
    //   pii-access/exports/{uuid}/reject           驳回（POST）
    //   pii-access/exports/{uuid}/execute          执行导出（POST，**必须已审批**）
    //   pii-access/audit                           访问审计（GET，真分页 + 真筛选）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ `{kind}` 段**只放行受控值域** `CUSTOMER|ORDER`：主体类型不是自由文本，
    //   放行任意一段等于把「拼错的类型」也送到后端。
    // ⚠️ `{ref}` 段允许 buyer_ref 这种**非 UUID** 标识（15 客户的 buyer_ref 是逻辑引用），
    //   但**不允许 `/`**：段里带斜杠就意味着多了一层路径，那些形状后端没有。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `pii-access` —— 本模块没有裸集合读
    //   * `pii-access/exports/{uuid}/delete` —— 导出申请是审批凭据，不做删除
    //   * `pii-access/exports` 之外的**批量全量导出**形状（如 `pii-access/exports/all`）——
    //     🔴 **批量导出 PII 必须走审批**，且导出对象必须逐条列明；
    //     放行一个「一把全导」的入口，等于给数据泄露事件留一个后门。
    //   * `pii-access/subjects/{kind}/{ref}/delete` —— 审计是 append-only，没有删除
    //   * `pii-access/purge`、`pii-access/mask-off` —— **没有**「关闭脱敏」的形状：
    //     脱敏是硬默认，不是可选开关。
    //   * `pii-access/exports/{uuid}/approve-all` —— 审批必须逐张做
    //   * `pii-access/audit/{uuid}/delete` —— 访问审计只增不改
    //
    // 🔴 本模块**不写**下游客服与运营页面的专属形状：它们不是本模块的端点，
    //   放行一个本模块不实现的路径只会让界面以为「已经可用」。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^pii\-access\/(?:capabilities|policy|grants(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/revoke)?|subjects(?:\/(?:CUSTOMER|ORDER)\/[0-9a-zA-Z][0-9a-zA-Z._:@-]*)?|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:approve|reject|execute))?)?|audit)$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 17.7.2.1 平台参数与开关（identity 服务）
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（`pii-access` 那条之后、数组结尾的 `];` 之前）----
    //   同批另外三条车道的锚点分别是「`prefix: 'identity/'` 之前」「`// 14.5.2.1 带货归因` 之前」
    //   「`// 2.4.2.3 映射缺失队列` 之前」，与本锚点互不重叠，git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条 `identity/` 巨型 alternation 里
    //   并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 永远不会自愈）。
    //
    // ---- 首 token「platform-flags」的零命中核对结论 ----
    //   本文件既有 services[] 正则里**没有任何一条**含 `platform-flags` 这个串
    //   （全文出现次数 = 0，用 `grep -c 'platform-flags'` 实测过）。
    //   ⚠️ 逐条核对了既有 `prefix: 'identity/'` 元素（**本文件里 `identity/` 前缀的元素只有一条**，
    //   它的 pattern 是 `^(permissions|owner-transfer|legal-entities|...)$` 那一长串）：
    //   把本模块的全部路径形状（`identity/platform-flags` 及其各子路径）喂进那条正则，
    //   **没有任何一条命中** —— 它的 alternation 里既没有 `platform-flags`，
    //   也没有任何通配段（`[0-9a-f-]+` 之类）能顺带吃掉这个首 token。
    //   ⇒ 既有的 `identity/` 元素**不会**静默吞掉本模块的任何路径，
    //      本模块的元素也**不会**吃掉别人的任何路径（双向都实测过）。
    //   ⇒ resolve() 是「首个命中即返回」，所以本元素放在数组末尾也能命中：
    //      前面的 `identity/` 元素对 `platform-flags/...` 一条都不匹配，会继续往下 fallthrough。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-flags/capabilities                     能力声明（逐维 available + ownerModule）
    //   platform-flags/definitions                     出厂码表（平台全局层，不带租户上下文）
    //   platform-flags/settings                         平台全局层当前值列表
    //   platform-flags/settings/{code}                  单个开关的当前值（PUT 改也在这一支）
    //   platform-flags/exceptions                       按租户禁用例外列表
    //   platform-flags/exceptions/{uuid}                单条例外（DELETE 撤销也在这一支）
    //   platform-flags/evaluate                         单个开关的可用性判定（三层裁决）
    //   platform-flags/evaluate-batch                   一组开关的可用性判定（下游门控一次问多个）
    //   platform-flags/history                          平台全局层变更历史
    //
    // ⚠️ `{code}` 段用**受控值域** `[A-Z][A-Z0-9_]{1,63}`（大写字母开头的标识符），
    //   不是自由文本 —— 开关码在库里有同样的 CHECK 约束，放行任意一段等于把
    //   「拼错的码」也送到后端。段里**不允许 `/`**：带斜杠就意味着多了一层路径。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ `evaluate` 与 `evaluate-batch` 写成 `evaluate(?:-batch)?`：
    //   字面量子路径排在 id 段之前判别，alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `platform-flags` —— 本模块没有裸集合读，页面一律从 `/settings` 起步
    //   * `platform-flags/settings/{code}/...` 的任何子动作 —— 改配置就是 PUT 本身，
    //     没有「启用 / 停用 / 重置」这类子端点
    //   * `platform-flags/exceptions/{uuid}/...` 的任何子动作 —— 例外只有「新增」与「撤销」两种
    //   * `platform-flags/tenant-switch` —— 「租户覆盖平台开关」是蓝图**明令禁止**的能力，
    //     放行一个这样的形状等于把一条不存在的后门登记成公开接口
    //   * `platform-flags/{任意其他首段}` —— 本模块的路径根只有 `platform-flags` 一个，
    //     其它首段一律 404
    //
    // 🔴 本模块**不写**其它服务的专属形状：下游各服务的功能门控接入是后续待办
    //   （仓内目前没有统一的 feature-flag 客户端 / SDK），放行一个本模块不实现的路径
    //   只会让界面以为「已经可用」。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^platform\-flags\/(?:capabilities|definitions|settings(?:\/[A-Z][A-Z0-9_]{1,63})?|exceptions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|evaluate(?:-batch)?|history)$/,
  },
  {
    // ---------------------------------------------------------------------------
    // 17.7.2.2 平台运维视图（identity 服务）
    // ---------------------------------------------------------------------------
    //
    // ---- 插入锚点固定在 **services[] 数组的末尾**（`platform-flags` 那条之后、数组结尾的 `];` 之前）----
    //   同批另外那条车道（17.7.1.2 用量统计）锚在 `// 2.4.2.3 映射缺失队列` 之前，
    //   与本锚点相隔 6000 行以上 ⇒ git 能干净求并。
    //
    // ---- 纯**新增一条独立的 `services[]` 元素** ----
    //   既有条目**一字未动、也不重排、不格式化整文件**。往既有那条 `identity/` 巨型
    //   alternation 里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 永不
    //   自愈）。本仓已有两条 `identity/` 元素（各带一条 alternation），本模块自己
    //   单独占一条。
    //
    // ---- 首 token「platform-ops」的零命中核对结论 ----
    //   本文件既有 services[] 共 179 条正则，**逐条**用 `new RegExp(pat).test(...)`
    //   实测过本模块的**全部**路径形状（放行的 10 个 + 刻意不放行的 8 个），
    //   **零命中**（核对脚本见 .tmp/allowlist-check.mjs，结论同样写在这里）。
    //   既有两条 `identity/` 元素的 alternation 里既没有 `platform-ops`，
    //   也没有任何通配段（`[0-9a-f-]+` 之类）能顺带吃掉这个首 token。
    //   ⇒ 既有的 `identity/` 元素**不会**静默吞掉本模块的任何路径，
    //      本模块的元素也**不会**吃掉别人的任何路径（双向都实测过）。
    //   ⇒ resolve() 是「首个命中即返回」，所以本元素放在数组末尾也能命中：
    //      前面的 `identity/` 元素对 `platform-ops/...` 一条都不匹配，会继续往下 fallthrough。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform-ops/capabilities                     能力声明（逐维 available + ownerModule + impact）
    //   platform-ops/overview                         平台健康看板汇总
    //   platform-ops/tenants                          逐租户技术健康摘要（看板主表）
    //   platform-ops/metrics                          技术指标列表（GET，真分页 + 真筛选）
    //   platform-ops/alerts                           告警列表（GET，真分页 + 真筛选）
    //   platform-ops/thresholds                       告警阈值列表
    //   platform-ops/thresholds/{metricCode}          单个指标的阈值（PUT 改也在这一支）
    //   platform-ops/alerts/{uuid}/acknowledge        确认告警
    //   platform-ops/alerts/{uuid}/resolve            闭环告警
    //   platform-ops/self/metrics                     本租户自检视角（只看得到自己租户的行）
    //
    // ⚠️ `{metricCode}` 段用**受控值域** `[A-Z][A-Z0-9_]{1,39}`（大写字母开头的标识符），
    //   不是自由文本 —— 指标码在库里有同样的 CHECK 约束。
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // ⚠️ 字面量子路径（`alerts` / `thresholds`）与带 id 的子动作是**并列的 alternation
    //   分支**，不是前缀：alternation 首个命中即返回，但本正则整段带 `^...$`，
    //   所以 `alerts` 分支不会吃掉 `alerts/{uuid}/acknowledge`（尾部对不上 `$` 会回溯）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `platform-ops` —— 本模块没有裸集合读
    //   * `platform-ops/ingest/metrics` —— **指标接入是服务端到服务端的内部密钥路径**
    //     （要 `X-Internal-Key`），浏览器放行它等于让任意租户往别的租户写指标
    //   * `platform-ops/roles` —— **平台运营角色登记**同样是内部密钥路径，
    //     浏览器打不到它；放行它等于让任意租户把自己升级成平台运营方
    //   * `platform-ops/metrics/{uuid}` —— 指标没有按 id 取单条的端点
    //   * `platform-ops/alerts/{uuid}` —— 告警只有「确认 / 闭环」两种子动作
    //   * `platform-ops/tenants/{uuid}` —— 逐租户健康摘要只有列表，没有按 id 取单条
    //   * `platform-ops/{任意其他首段}` —— 本模块的路径根只有 `platform-ops` 一个
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      /^platform\-ops\/(?:capabilities|overview|tenants|metrics|alerts|thresholds|self\/metrics|alerts\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:acknowledge|resolve)|thresholds\/[A-Z][A-Z0-9_]{1,39})$/,
  },
  // ==========================================================================
  // 1.2.1.1 全局搜索框（前端聚合层 + 五个服务的只读检索端点）。
  //
  // ---- 插入锚点：**数组末尾**（`platform-ops` 那条之后）----
  //   同批其它车道的插入点分别是「数组最开头」「`prefix: 'identity/'` 之前」
  //   「`prefix: 'warehouse/'` 之前」，与本处相距 600 行以上，git 能干净求并。
  //
  // ---- 为什么是**五条**独立的 services[] 元素而不是一条 ----
  //   services[] 的每条元素只带**一个** prefix（一个后端服务）。
  //   全局搜索跨五个服务（catalog / trade / channel / warehouse / identity），
  //   每个服务各占一条 —— 既有的 100+ 条元素**一字未改、也不重排**。
  //
  // ---- 形状（**只放行本模块真实实现的路径**）----
  //   catalog/global-search              商品（SKU / SPU）+ 客户 + 供应商的只读检索
  //   catalog/global-search/capabilities 能力声明（逐域 available + ownerModule + impact）
  //   trade/global-search                销售订单 + 采购单
  //   trade/global-search/capabilities   能力声明
  //   channel/global-search              店铺
  //   channel/global-search/capabilities 能力声明
  //   warehouse/global-search            仓库 + 物流单号
  //   warehouse/global-search/capabilities 能力声明
  //   identity/global-search/recent      最近搜索记录（GET 读 / POST 登记 / DELETE 清空）
  //
  // ---- 刻意**不放行**的形状 ----
  //   global-search 裸路径以外的写端点：四个业务服务的检索端点**只有 GET**
  //     （工作台只读 + 跳转是蓝图的硬约束），放行不存在的 POST/PUT 只会让界面以为
  //     「全局搜索能改数据」。
  //   global-search/{任意其他子路径}：本模块的子路径只有 capabilities 一个。
  //   identity/global-search 裸路径：identity 侧只有 recent 一个子路径。
  // ==========================================================================
  {
    // 12.4.2.1 费用归集（finance 服务，卷 12「财务核算」L3 12.4.2 费用分摊下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 global-search 那组之前 ----
    //   同批其它车道的插入点是「数组最开头」「第一个 identity/ 之前」等；
    //   本车道落在这里，与它们的插入点相距 10k 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「cost-collections」的零命中核对结论 ----
    //   本文件既有 services[] 正则（按 `pattern:` 逐条抽出）里，对本模块 **14 个形状逐一实测零命中**：
    //     cost-collections/capabilities / options / rules / rules/{uuid}
    //     cost-collections/rule-templates
    //     cost-collections/pools / pools/{uuid}
    //     cost-collections/entries / entries/{uuid} / entries/{uuid}/remove
    //     cost-collections/collect/platform-settlement / ad-spend / logistics / head-mile / manual
    //   全文件 grep「cost-collection / costcollection」**零命中** ⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cost-collections/capabilities       能力声明（上游真接通 / 下游缺口归谁）
    //   cost-collections/options            筛选项（来源 / 费用项 / 维度 / 期间口径 / 币种 / 池 / 规则）
    //   cost-collections/rules              归集规则列表（GET）/ 新建（POST）
    //   cost-collections/rules/{uuid}       修改归集规则（PUT）
    //   cost-collections/rule-templates     蓝图标准绑定模板（只读，不落库）
    //   cost-collections/pools              费用池列表（GET）
    //   cost-collections/pools/{uuid}       费用池详情（GET：池 + 它的归集条目）
    //   cost-collections/entries            归集明细列表（GET，真分页 + 真筛选 + 汇总）
    //   cost-collections/entries/{uuid}     归集条目详情（GET：条目 + 归集依据）
    //   cost-collections/entries/{uuid}/remove  撤掉一条归集条目（POST · 调整用）
    //   cost-collections/collect/platform-settlement  从 12.3.1.2 归集平台费（POST）
    //   cost-collections/collect/ad-spend             从 14.3.1.1 归集广告费（POST）
    //   cost-collections/collect/logistics             从 10.2.1.1 归集物流费（POST）
    //   cost-collections/collect/head-mile             从 10.4.1.1 归集头程费（POST）
    //   cost-collections/collect/manual                 人工录入归集（POST）
    //
    // ⚠️ **刻意不放行**：
    //   cost-collections                          —— 裸路径：后端**没有**「一条不存在的汇总列表接口」
    //                                               （列表在 /entries 与 /pools 两处）。
    //   cost-collections/allocation / allocations  —— 🔴 分摊属 12.4.2.2，**未交付**：
    //                                               放行会让界面以为「已经能分摊」，
    //                                               而本模块只产出**待分摊的费用池**。
    //   cost-collections/{uuid}                   —— 顶层没有 id 段。
    //   cost-collections/entries/{uuid}/allocate   —— 同上，分摊结果不存在。
    //   cost-collections/collect/anything-else     —— 归集入口只有上面五个。
    //   cost-collections/not-a-uuid                —— 非 UUID 段不应被 uuid 分支吃掉。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / rules / rule-templates / pools / entries / collect/*）
    // 排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^cost\-collections\/(?:(?:capabilities|options|rule\-templates|rules|pools|entries)(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/remove)?)?|collect\/(?:platform\-settlement|ad\-spend|logistics|head\-mile|manual))$/,
  },
  {
    // 12.4.1.1 计价方法配置（finance 服务，卷 12「财务核算」L3 12.4.1 成本计算下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有 finance 支部的首 token 是 `receivables` / `receivable-aging` / `payables` / `receipts` /
    //   `bad-debt` / `payment-plans` / `payment-orders` / `payment-executions` / `write-offs` /
    //   `settlement` / `transactions` / `fund-analysis` / `cost-collections` / `deferred-settlement`，
    //   本模块占的是**首 token `cost-valuation`** —— 与 `cost-collections` 也刻意不同
    //   （`cost-valuation` ≠ `cost-collections`），两个正则两两不相交 ⇒ 不会互相吃掉对方的路径。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cost-valuation/capabilities            能力声明（上游真接通 / 下游缺口归谁）
    //   cost-valuation/options                 筛选项 + 四个计价方法选项（系统提供选项，不替用户决定政策）
    //   cost-valuation/scopes                  可用计价范围（法人 / 品类，实时读 identity / catalog）
    //   cost-valuation/methods                 方法配置列表（GET）/ 登记或切换方法（POST）
    //   cost-valuation/methods/versions        版本历史（新到旧）
    //   cost-valuation/methods/compare         版本对比（切换前 / 切换后）
    //   cost-valuation/specified-costs         指定成本单价列表（GET）/ 维护指定单价（POST）
    //   cost-valuation/trials                  试算列表（GET）/ 发起试算（POST）
    //   cost-valuation/trials/{uuid}           试算明细（GET：表头 + 四个方法的结果）
    //
    // ⚠️ **刻意不放行**：
    //   cost-valuation                          —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   cost-valuation/recalculate|recompute   —— 🔴 重算属 12.4.1.2，**未交付**：
    //                                               放行会让界面以为「已经能重算」，
    //                                               而本模块只产出**配置面**（方法 / 生效区间 / 版本链 / 试算）。
    //   cost-valuation/cost-layers|cost-results —— 🔴 成本层与结转金额属 12.4.1.2，**未交付**。
    //   cost-valuation/variance                —— 🔴 成本差异分析属 12.4.1.3，**未交付**。
    //   cost-valuation/{uuid}                   —— 顶层没有 id 段（方法按范围 + 版本定位）。
    //   cost-valuation/trials/not-a-uuid        —— 非 UUID 段不应被 uuid 分支吃掉。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
    // 后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / options / scopes / methods / methods\/versions / methods\/compare /
    // specified-costs / trials）排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，
    // 顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^cost\-valuation\/(?:capabilities|options|scopes|methods(?:\/(?:versions|compare))?|specified\-costs|trials(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern: /^global\-search(?:\/capabilities)?$/,
  },
  {
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern: /^global\-search(?:\/capabilities)?$/,
  },
  {
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern: /^global\-search(?:\/capabilities)?$/,
  },
  {
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern: /^global\-search(?:\/capabilities)?$/,
  },
  {
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^global\-search\/recent$/,
  },
  {
    // 1.5.1.1 同步与 API 健康（channel 服务，卷 01「工作台与门户」L3 1.5.1「集成健康」的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 分支的 alternation 里
    //   并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未动、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`identity/global-search/recent` 那条之后）----
    //   同批其它三条车道的插入点分别是「`prefix: 'catalog/'` 之前」「`prefix: 'identity/'` 之前」
    //   与「`prefix: 'warehouse/'` 之前」，与本处互不重叠 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「sync-health」的零命中核对结论 ----
    //   本文件既有 100+ 条 services[] 正则里**没有任何一条**含 `sync-health` 这个串
    //   （既有含 "sync" 的是 `logistics-channels/sync` 一类，首 token 全不相同），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   sync-health/capabilities    能力声明（红黄绿规则表 · 阈值 · 计数口径 · 积压来源）
    //   sync-health/options         筛选项（平台码来自真实连接）
    //   sync-health/summary         总览（红黄绿分布 + 失败 / 限流计数 + 积压 + 没有连接的平台）
    //   sync-health/connections      连接健康卡片（GET，真分页 + 真筛选）
    //   sync-health/connections/{uuid} 单张卡片（GET）
    //   sync-health/pending-items   需处理项（GET；落点是**归属系统**的页面）
    //
    // ⚠️ **本模块是只读的**：卷 01 的边界写死「工作台只读 + 跳转；一切状态变更回到
    //   归属系统完成」。所以这里**一个写形状都不放行** —— 放行一个不存在的写入口
    //   比 404 更糟（界面会以为「重试同步 / 重新授权」在这儿能做）。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行）。
    //   字面量子路径（capabilities / options / summary / pending-items / connections）
    //   排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   sync-health                                —— 裸路径：后端没有「一条汇总列表接口」
    //   sync-health/{任意其他子路径}                 —— 本模块的子路径只有上面那六个
    //   sync-health/connections/{uuid}/retry        —— 🔴 「重试同步」没有归属系统的写端点，
    //                                                    工作台不写业务数据 ⇒ 这个形状不存在
    //   sync-health/connections/{uuid}/reauthorize  —— 同上，「重新授权」归 2.2.1.2
    //   sync-health/connections/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `sync-health`。
    pattern:
      /^sync\-health(?:\/(?:capabilities|options|summary|pending\-items|connections(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))$/,
  },
  {
    // 1.4.2.2 默认店铺与仓库（identity 服务，卷 01「工作台与门户」L3 1.4.2 账号偏好的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 identity 那条巨型 alternation 里
    //   并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`sync-health` 那条之后）----
    //   本车道与同批另两条车道（1.1.1.3、1.4.2.1）的插入点相距 **8000 行以上**：
    //   那两条的锚点都在 `user-preferences` 那条元素附近（本文件第 1352 行上下），
    //   本条在第 9880 行附近 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「default-context」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**含 `default-context` 这个串
    //   （既有含 "default" 的是 `saas-plans` 之类的其它命名，首 token 全不相同），
    //   所以首 token 既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   default-context               当前生效的默认上下文（GET 读 / PUT 存 / DELETE 重置）
    //   default-context/options       三个维度的候选集（GET：店铺 / 法人 / 仓库）
    //   default-context/capabilities  能力声明（GET：哪些维度真接通 + 缺什么 + 归谁）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   default-context/{uuid}        —— 默认上下文是**单数**的一份，没有按 id 取单条的端点
    //   default-context/shops         —— 店铺清单是**读上游**的结果，由 options 一次给全，
    //                                    不另开一个按维度分的路径
    //   default-context/legal-entities —— 同上
    //   default-context/{任意其他子路径} —— 本模块的子路径只有 options 与 capabilities 两个
    //
    // ⚠️ 写形状（PUT / DELETE）放行的是**同一个 default-context 路径**：
    //   它们是「设置我自己这一份偏好」，不涉及别的租户的数据；跨服务的写路径一律不放行。
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^default\-context(?:\/(?:capabilities|options))?$/,
  },
  {
    // 12.5.2.1 凭证管理（finance 服务，卷 12「财务核算」L3 12.5.2 凭证下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 `receivables` / `receipts` /
    //   `settlement` 那几条巨型 alternation 里并分支 —— 那一支是单行超长正则，
    //   多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **`default-context`（identity）那条之后、`receivables` 那条之前**----
    //   也就是「identity 簇的末尾」与「末尾那簇 finance 条目」之间。
    //   与本批其它车道用过的插入点（数组最开头 / 某个 identity|warehouse|auth 之前 /
    //   `payment-orders` 之后 / `settlement` 之后 / 数组末尾）都相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「vouchers」的零命中核对结论 ----
    //   本文件既有 **200+ 条** services[] 正则（按 `pattern:` 逐条抽出实测）里，
    //   **没有任何一条**含 `vouchers` 这个串（逐条 `grep -c voucher` = 0）；
    //   既有 finance 那几条的首 token 是 `receivables` / `receipts` / `settlement` /
    //   `supplier-reconciliation` / `payment-orders` / `payment-executions` /
    //   `payment-plans` / `write-off` / `bad-debt` / `transactions` / `fund-analysis` /
    //   `cost-collections` 等，**全不相同** ⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   vouchers                         列表（GET，真分页 + 真筛选 + 按币种分开汇总）/ 建单（POST）
    //   vouchers/capabilities            能力声明（12.5.1.1 / 12.5.1.2 / 12.5.2.2 缺口归谁）
    //   vouchers/options                 筛选项（币种 / 状态 / 来源类型 / 期间 / 已用过的科目编码）
    //   vouchers/export                  导出标准凭证文件（GET，CSV / JSON）
    //   vouchers/generate                由业务事件自动生成凭证（POST）
    //   vouchers/periods                 会计期间列表（GET）/ 登记期间（POST）
    //   vouchers/periods/{uuid}/close    关闭会计期间（POST）
    //   vouchers/mappings                业务事件 → 借贷科目映射（GET）/ 登记映射（POST）
    //   vouchers/mappings/{uuid}         修改映射（PUT）
    //   vouchers/{uuid}                  凭证详情（GET：凭证 + 分录行 + 审计留痕）/ 改草稿（PUT）
    //   vouchers/{uuid}/post             过账（POST）
    //   vouchers/{uuid}/reverse          红冲（POST，生成一张反向凭证）
    //   vouchers/{uuid}/void             作废草稿（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / export / generate / periods / mappings）
    //   排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   vouchers/{uuid}/delete           —— 🔴 凭证是凭据，**不可删**（错了只能作废 / 红冲）
    //   vouchers/{uuid}/unpost            —— 🔴 没有「反过账」：已过账只能红冲
    //   vouchers/{uuid}/edit              —— 改草稿是**同一个** {uuid} 上的 PUT
    //   vouchers/{uuid}/approve           —— 🔴 审批属 17 审批（identity），本模块**不提供**本地审批端点
    //   vouchers/{uuid}/reject            —— 同上
    //   vouchers/batch                    —— 本模块**没有**批量端点
    //   vouchers/not-a-uuid               —— 非 UUID 段不应被 uuid 分支吃掉
    //   vouchers/{uuid}/not-a-shape       —— 本模块的动作只有 post / reverse / void
    //   vouchers/capabilities/{uuid}      —— 字面量段刻意不接 id 段
    //   vouchers/options/{uuid}           —— 同上
    //   vouchers/export/{uuid}            —— 导出是集合级动作，不按 id 取
    //   vouchers/generate/{uuid}          —— 同上
    //   vouchers/periods/{uuid}           —— 期间按 id 的动作只有 close（多一层子路径）
    //   vouchers/mappings/{uuid}/not-a-shape —— 映射按 id 的动作只有 PUT 本身
    //   vouchers/vouchers                 —— 交替写错就会互相吃掉，必须反向钉住
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^vouchers(?:\/(?:capabilities|options|export|generate|mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|periods(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/close)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:post|reverse|void))?))?$/,
  },
  {
    // 12.1.1.1 应收单管理（finance 服务，卷 12「财务核算」L3 12.1.1 应收下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 trade / crm 那条巨型
    //   alternation 里并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突
    //   （结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`default-context` 那条之后）----
    //   finance 是本批**第一个** finance 车道，本仓既有 100+ 条 services[] 正则里
    //   没有任何一条含 `receivables` 这个串的首 token 形态（trade 那条是
    //   `b2b-orders/receivables`，首 token 是 `b2b-orders`，全不相同），
    //   所以「receivables」既是独立字面量，也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   receivables/capabilities               能力声明（哪些上游真接通 + 缺口归谁）
    //   receivables/options                    筛选项（来源 / 状态 / 币种 / 应收对象 / 店铺）
    //   receivables                            列表（GET，真分页 + 真筛选 + 汇总）/ 建单（POST）
    //   receivables/{uuid}                     单条详情（GET：单头 + 明细行 + 事件 + 催收任务）
    //   receivables/import-b2b                 从发货确认导入账期应收（POST，幂等）
    //   receivables/{uuid}/void                作废（POST，终态）
    //   receivables/{uuid}/dispute             标注 / 取消「争议中」（POST）
    //   receivables/aging                      账龄分布（GET，五档 × 币种）
    //   receivables/collection-tasks           催收任务列表（GET）/ 建任务（POST）
    //   receivables/collection-tasks/generate  批量生成逾期催收任务（POST）
    //   receivables/collection-tasks/{uuid}/close  关闭催收任务（POST）
    //   receivables/policies                   应收确认政策列表（GET，五个来源全列）
    //   receivables/policies/{source}          配置某个来源的确认政策（PUT）
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / aging / import-b2b / policies /
    //   collection-tasks / generate）排在 {uuid} 段之前判别 —— alternation 是首个命中即返回，
    //   顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   receivables/{uuid}/settle              —— 🔴 核销（12.2.1.2）**未交付**，
    //                                             本模块不提供任何改核销状态的写入口
    //   receivables/{uuid}/write-off           —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   receivables/{uuid}/invoice              —— 🔴 发票（12.6.2.1）**未交付**
    //   receivables/payments                   —— 🔴 收付款（12.2.x）**未交付**
    //   receivables/settlements                —— 🔴 平台结算（12.3.1）**未交付**
    //   receivables/not-a-uuid                 —— 非 UUID 段不应被 uuid 分支吃掉
    //   receivables/{uuid}/not-a-shape         —— 本模块的动作只有 void / dispute / collection-tasks
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^receivables(?:\/(?:capabilities|options|aging|import\-b2b|policies(?:\/[A-Z_]+)?|collection\-tasks(?:\/(?:generate|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/close))?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:void|dispute|collection\-tasks))?))?$/,
  },
  {
    // 12.2.1.1 收款单（finance 服务，卷 12「财务核算」L3 12.2.1 资金收付下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 `receivables`（12.1.1.1）那条
    //   alternation 里并分支 —— 多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`receivables` 那条之后）----
    //   与本批其它车道的插入点相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「receipts」的零命中核对结论 ----
    //   本文件既有 200+ 条 services[] 正则里**没有任何一条**能命中 `receipts` 开头的形状：
    //   既有的 `receivables`（12.1.1.1）首 token 是 `receivables`（第 7 个字母就是 a，不是 p），
    //   `b2b-orders/receivables` 属于 trade 的巨型 alternation（首 token 是 `b2b-orders`），
    //   其余 200+ 条与 `receipts` 无公共前缀。
    //   逐条用 `new RegExp(pat).test('…')` 实测过：既有全部正则对本模块的 9 个 shape
    //   **零命中**，且本模块的 9 个负向 shape 对既有正则**也零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   receipts/capabilities              能力声明（哪些上游真接通 + 缺口归谁）
    //   receipts/options                   筛选项（来源 / 来源系统 / 状态 / 币种 / 付款方）
    //   receipts                           列表（GET，真分页 + 真筛选 + 汇总）/ 建单（POST）
    //   receipts/{uuid}                    单条详情（GET：单头 + 预留核销关系 + 事件流）
    //   receipts/import                    到账流水导入（POST，CSV / JSON，幂等）
    //   receipts/balance                   未核销余额（GET，按币种分开）
    //   receipts/{uuid}/allocations        登记预留核销（POST，**预留不是核销**）
    //   receipts/{uuid}/allocations/{uuid}/release  释放预留核销（POST）
    //   receipts/{uuid}/void               作废（POST，终态）
    //
    // 判别顺序：字面量子路径（capabilities / options / import / balance）排在 {uuid} 段之前 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   receipts/{uuid}/settle             —— 🔴 核销（12.2.1.2）**未交付**，
    //                                          本模块不提供任何改核销状态的写入口
    //   receipts/{uuid}/write-off          —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   receipts/settlements               —— 🔴 平台结算（12.3.1）**未交付**
    //   receipts/accounts                  —— 🔴 受管资金账户（12.7.1）**未交付**
    //   receipts/{uuid}/not-a-shape        —— 本模块的动作只有 void / allocations
    //   receipts/not-a-uuid                —— 非 UUID 段不应被 uuid 分支吃掉
    //   receipts/receipts                  —— 交替写错就会互相吃掉，必须反向钉住
    //   receipt                            —— 单数形态不存在
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^receipts(?:\/(?:capabilities|options|import|balance|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:void|allocations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/release)?))?))?$/,
  },
  {
    // 12.3.1.1 结算单管理（finance 服务，卷 12「财务核算」L3 12.3.1 结算与对账下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 `receivables` / `receipts`
    //   那两条巨型 alternation 里并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突
    //   （结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`receipts` 那条之后）----
    //   与本批其它车道的插入点相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「settlement」的零命中核对结论 ----
    //   本文件既有 **187** 条 services[] 正则里**没有任何一条**能命中 `settlement` 开头的形状：
    //   同服务的兄弟模块首 token 分别是 `receivables`（12.1.1.1）与 `receipts`（12.2.1.1），
    //   两者与 `settlement` 没有任何公共前缀（`receipt` vs `settle` 第 4 个字母就分岔）；
    //   其余 185 条与 `settlement` 无公共前缀。
    //   逐条用 `new RegExp(pat).test('…')` 实测过：既有全部正则对本模块的 10 个正向 shape
    //   **零命中**，本模块的 8 个负向 shape 对既有正则也**零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   settlement/capabilities      能力声明（哪些上游真接通 + 缺口归谁）
    //   settlement/options           筛选项（状态 / 来源 / 币种 / 平台）
    //   settlement/shops             店铺选项（上游 2 渠道与店铺 2.3.1.1，经授权 HTTP 只读）
    //   settlement/cycles             结算周期配置列表（GET）/ 登记改写（POST）
    //   settlement                   列表（GET，真分页 + 真筛选 + 汇总）/ 手工登记（POST）
    //   settlement/import            平台账单导入（POST，CSV / JSON，真解析 + 真入库 + 幂等）
    //   settlement/{uuid}            单条详情（GET：单头 + 汇总项 + 明细行 + 事件流 + 原始文件）
    //   settlement/{uuid}/difference 与我方账的差额（GET，实时计算）
    //   settlement/{uuid}/arrival    登记到账（POST）
    //   settlement/{uuid}/void       作废（POST，终态）
    //
    // 判别顺序：字面量子路径（capabilities / options / shops / cycles / import）排在 {uuid} 段之前 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   settlement/{uuid}/settle       —— 🔴 核销（12.2.1.2）**未交付**
    //   settlement/{uuid}/write-off    —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   settlement/{uuid}/reconcile    —— 🔴 自动对账任务（12.3.2.1）**未交付**
    //   settlement/{uuid}/profit       —— 🔴 利润（13）**未交付**
    //   settlement/{uuid}/sync         —— 🔴 平台结算报告自动同步（上游「卷 02 同步」）**未交付**
    //   settlement/{uuid}/not-a-shape  —— 本模块的动作只有 arrival / void / difference
    //   settlement/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   settlement/settlements        —— 交替写错就会互相吃掉，必须反向钉住
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^settlement(?:\/(?:capabilities|options|shops|cycles|import|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:arrival|void|difference))?))?$/,
  },
  {
    // 12.5.1.1 会计科目（finance 服务，卷 12「财务核算」L3 12.5.1 账簿与科目下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   既有条目是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 **`settlement` 那条之后 / `supplier-reconciliation` 那条之前**----
    //   同批其它车道的插入点多数落在数组前段（`payment-orders` / `payment-executions` / `bad-debt`
    //   / `transactions` / `fund-analysis` …）或数组末尾；本处离它们都足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「account-subjects」的零命中核对结论（本车道实测）----
    //   本文件既有 **213** 条 services[] 正则（按 `pattern:` 逐条抽出实测）与本模块 **13** 个形状
    //   **逐一零命中**；本模块的正则对其它车道已放行的前缀（`receivables` / `settlement` /
    //   `transactions` / `bad-debt` / `supplier-reconciliation` …）也**零命中**
    //   （首 token 完全不同，且本正则首尾都锚定）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   account-subjects                          列表（GET，真分页 + 真筛选 + 分类汇总）/ 建科目（POST）
    //   account-subjects/capabilities             能力声明（科目表 / 模板 / 映射 / 下游缺口归谁）
    //   account-subjects/options                  筛选项（类别 / 余额方向 / 税务属性 / 状态 / 来源）
    //   account-subjects/templates                系统科目模板目录（模板只是导入源）
    //   account-subjects/templates/apply          从模板导入科目到指定账簿（POST）
    //   account-subjects/by-code                  按科目编码查（GET，**下游 12.5.2.1 凭证的集成点**）
    //   account-subjects/mappings                 映射规则清单（GET）/ 新建映射规则（POST）
    //   account-subjects/mappings/{uuid}          映射规则详情（GET）
    //   account-subjects/mappings/{uuid}          改映射规则（PUT，分录行全量替换）
    //   account-subjects/mappings/{uuid}/activate  启用映射规则（POST，借贷齐备才让启用）
    //   account-subjects/mappings/{uuid}/deactivate 停用映射规则（POST）
    //   account-subjects/{uuid}                   科目详情（GET：科目 + 变更留痕 + 被引用的映射）
    //   account-subjects/{uuid}                   改科目（PUT，**编码不可改**）
    //   account-subjects/{uuid}/disable           停用科目（POST，停用不是删除）
    //   account-subjects/{uuid}/enable            启用科目（POST）
    //
    // 判别顺序：字面量子路径（capabilities / options / templates / by-code / mappings）排在 {uuid} 段
    // **之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截
    //（`mappings` 尤其危险：它不是 uuid 形状，放到 {uuid} 段之后会被吃掉）。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   account-subjects/{uuid}/ledger      —— 🔴 凭证属 12.5.2.1，未交付
    //   account-subjects/{uuid}/periods     —— 🔴 会计期间属 12.5.1.2，未交付
    //   account-subjects/{uuid}/close       —— 🔴 关账属 12.5.1.3，未交付
    //   account-subjects/{uuid}/delete      —— 刻意**不导出 DELETE**：停用才是「不用了」，
    //                                              删除会让历史凭证引用的科目凭空消失
    //   account-subjects/batch               —— 本模块**没有**批量端点
    //   account-subjects/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   account-subjects/{uuid}/not-a-shape  —— 多一层仍然 404
    //   account-subjects/capabilities/{uuid} —— 字面量段刻意不接 id 段
    //   account-subjects/account-subjects    —— 交替写错就会互相吃掉，必须反向钉住
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^account\-subjects(?:\/(?:capabilities|options|templates(?:\/apply)?|by\-code|mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:activate|deactivate))?)?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:disable|enable))?))?$/,
  },
  {
    // 12.4.2.2 分摊规则（finance 服务，卷 12「财务核算」L3 12.4.2 费用分摊下的第二格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里
    //   并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在 `supplier-reconciliation` 那条之前 ----
    //   同批其它车道的插入点是「数组最开头」「第一个 identity/ 之前」等；
    //   本车道落在这里，与它们的插入点相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「cost-allocations」的核对结论（本车道实测）----
    //   ⚠️ catalog 服务**也**有一个 `cost-allocations`（5.2.1.3 成本分摊：把混合成本的货件
    //   按重量/体积/金额/数量摊到各 SKU）。两者**首 token 相同但前缀作用域不同**：
    //   那条是 `catalog/`，本条是 `finance/`，而 `resolve()` 先按前缀筛、再按正则判 ⇒
    //   两条互不吃掉（实测：202 条既有正则里，finance/ 作用域下**零命中** `cost-allocations`）。
    //   两者是**不同的模块**：catalog 那条摊的是「一次采购共用头程的货件成本」，
    //   本条摊的是「12.4.2.1 归集出来的费用池」—— 页面与端点都不共用。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   cost-allocations/capabilities        能力声明（上游真接通 / 下游缺口归谁）
    //   cost-allocations/options             筛选项（维度 / 基数 / 舍入差策略 / 池 / 规则 / 币种）
    //   cost-allocations/rules               分摊规则列表（GET）/ 新建（POST）
    //   cost-allocations/rules/{uuid}        修改分摊规则（PUT）
    //   cost-allocations/pools               待分摊的费用池列表（GET）
    //   cost-allocations/targets             分摊对象列表（GET）
    //   cost-allocations/targets/collect     采集分摊对象（POST）
    //   cost-allocations/allocate            执行分摊（POST）
    //   cost-allocations/batches             分摊批次列表（GET）
    //   cost-allocations/batches/{uuid}      批次详情（GET：批次 + 分摊明细）
    //   cost-allocations/batches/{uuid}/revert  撤销一次分摊（POST）
    //   cost-allocations/results             分摊结果列表（GET，真分页 + 真筛选 + 汇总）
    //
    // ⚠️ **刻意不放行**：
    //   cost-allocations                     —— 裸路径：后端**没有**「一条不存在的汇总列表接口」
    //                                              （列表在 /pools、/batches、/results 三处）。
    //   cost-allocations/targets/{uuid}      —— 分摊对象没有单条详情端点。
    //   cost-allocations/pools/{uuid}        —— 费用池没有单条详情端点（列表里已带全部字段）。
    //   cost-allocations/results/{uuid}      —— 分摊结果没有单条详情端点。
    //   cost-allocations/ledger / vouchers   —— 🔴 会计凭证（12.5.2.1）与总账**未交付**：
    //                                              放行会让界面以为「分摊已经能生成凭证」。
    //   cost-allocations/profit              —— 🔴 13 利润**未交付**（在别的服务）。
    //   cost-allocations/not-a-shape         —— 非本模块的子路径。
    //   cost-allocations/rules/not-a-uuid    —— 非 UUID 段不应被 uuid 分支吃掉。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / pools / targets / results / allocate）排在
    //   {uuid} 段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^cost\-allocations\/(?:targets\/collect|allocate|(?:capabilities|options|pools|targets|results)|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/revert)?)?)$/,
  },
  {
    // 12.1.2.3 供应商对账（finance 服务，卷 12「财务核算」L3 12.1.2 应付下的第三格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 `receivables` / `receipts` /
    //   `settlement` 那几条巨型 alternation 里并分支 —— 那一支是单行超长正则，
    //   多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`settlement` 那条之后）----
    //   与本批其它车道（`payment-orders` / `fund-analysis` / `transactions`）的插入点
    //   相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「supplier-reconciliation」的零命中核对结论（本车道实测）----
    //   本仓既有 **204** 条 services[] 正则里，与 `supplier` 有关的只有两条：
    //     · `/^supplier(?:\/...)?$/`（供应商索赔 11.2.3.3 一族）
    //     · `/^supplier\-claims...$/`（供应商索赔单据）
    //   两条都要求「supplier 后面紧跟 `/` 或字符串结束」⇒ 首 token 为 `supplier-reconciliation`
    //   的路径在第 9 个字符处是 `-`，**两条都匹配不上** ⇒ 零命中。
    //   逐条用 `new RegExp(pat).test('supplier-reconciliation/...')` 实测过：
    //   既有全部正则对本模块的 12 个正向 shape **零命中**；
    //   本模块的正则对本仓其它车道已放行的前缀（`receivables` / `receipts` / `settlement` …）
    //   也**零命中**（首 token 完全不同）。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   supplier-reconciliation/capabilities   能力声明（上游接线 / 职责分离 / 缺口归谁）
    //   supplier-reconciliation/options        筛选项（状态 / 上游状态 / 币种 / 供应商）
    //   supplier-reconciliation                列表（GET，真分页 + 真筛选 + 按币种汇总）
    //   supplier-reconciliation/import         导入采购侧对账单（POST，幂等）
    //   supplier-reconciliation/{uuid}         单条详情（GET：头 + 明细 + 质检证据 + 差异 + 说明 + 事件 + 应付依据）
    //   supplier-reconciliation/{uuid}/lines   明细行列表（GET，真分页）
    //   supplier-reconciliation/{uuid}/differences  差异清单（GET，真分页）
    //   supplier-reconciliation/{uuid}/match   核对收货与质检（POST）
    //   supplier-reconciliation/{uuid}/confirm 财务确认并生成应付（POST）
    //   supplier-reconciliation/{uuid}/reject  财务驳回（POST）
    //   supplier-reconciliation/{uuid}/differences/{uuid}/explanations 登记差异说明（POST）
    //   supplier-reconciliation/{uuid}/differences/{uuid}/resolve     裁决差异（POST）
    //
    // 判别顺序：字面量子路径（capabilities / options / import）排在 {uuid} 段之前 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   supplier-reconciliation/{uuid}/settle       —— 🔴 付款（12.2.2.1）**未交付**
    //   supplier-reconciliation/{uuid}/write-off    —— 🔴 坏账核销（12.1.1.3）**未交付**
    //   supplier-reconciliation/{uuid}/pay          —— 🔴 付款执行（12.2.2.2）**未交付**
    //   supplier-reconciliation/{uuid}/not-a-shape  —— 本模块的动作只有 match / confirm / reject
    //   supplier-reconciliation/not-a-uuid          —— 非 UUID 段不应被 uuid 分支吃掉
    //   supplier-reconciliation/{uuid}/differences/not-a-uuid —— 同上（差异 id 段）
    //   supplier-reconciliation/reconciliations    —— 交替写错就会互相吃掉，必须反向钉住
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^supplier-reconciliation(?:\/(?:capabilities|options|import|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:lines|differences\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:explanations|resolve)|differences|match|confirm|reject))?))?$/,
  },
  {
    // 12.6.1.1 税率配置（finance 服务，卷 12「财务核算」L3 12.6 税务下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 finance 那几条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`supplier-reconciliation` 那条之后）----
    //   与本批其它车道（数组最开头 / identity 之前 / warehouse 之前）的插入点
    //   相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「tax-rates」的零命中核对结论（本车道实测）----
    //   本文件既有 **155** 条 services[] 正则（按 `pattern:` 逐条抽出实测）里，
    //   没有任何一条含 `tax-rates` 这个串 —— 逐条用 `new RegExp(pat).test('tax-rates/...')`
    //   实测过：对本模块下面 11 个正向 shape **零命中**。
    //   反向也实测过：本条正则对其它车道已放行的前缀
    //   （`cost-valuation` / `cost-collections` / `vouchers` / `receivables` / `transactions` /
    //   `fund-analysis` / `payment-plans` / `deferred-settlement` / `supplier-reconciliation` /
    //   `cost-allocations`）**零误伤**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   tax-rates/capabilities      能力声明（上游真接通 / 职责切分 / 缺口归谁）
    //   tax-rates/options           筛选项 + 四个税种选项（系统提供选项，不替用户决定税率）
    //   tax-rates/jurisdictions     辖区选项（实时读 channel 16.3.1.1 受控辖区码表）
    //   tax-rates/shops             店铺站点选项（实时读 channel 2.3.1.1 店铺目录）
    //   tax-rates/rates             税率记录列表（GET）/ 登记或换税率（POST，追加新版本）
    //   tax-rates/rate-versions     税率版本历史（新到旧）
    //   tax-rates/rate-compare      版本对比（换税率前 / 后）
    //   tax-rates/resolve           按生效时间取税率（订单日期 + 店铺站点 + 辖区 ⇒ 唯一命中）
    //   tax-rates/bindings          店铺站点默认税率与税码绑定（GET）/ 绑定或换绑（POST）
    //   tax-rates/binding-versions  绑定版本历史（新到旧）
    //   tax-rates/state-sales-tax   州销售税规则（🔴 **归 channel 16.3.2.3**，本模块只读引用）
    //
    // ⚠️ **刻意不放行**：
    //   tax-rates                       —— 裸路径：后端**没有**「一条不存在的汇总列表接口」
    //                                      （列表在 /rates 与 /bindings 两处）。
    //   tax-rates/filings               —— 🔴 12.6.1.2 VAT/IOSS 申报数据**未交付**：
    //                                      放行会让界面以为「已经能出申报底稿」。
    //   tax-rates/declarations          —— 🔴 同上（申报数据另一个叫法）。
    //   tax-rates/reports               —— 🔴 12.5.2.2 财务报表**未交付**（在同服务另一条命名空间）。
    //   tax-rates/rates/{uuid}          —— 本模块的税率记录**没有单条详情端点**
    //                                      （按 税种 × 辖区 × 税码 + 版本定位）。
    //   tax-rates/not-a-shape           —— 非本模块的子路径。
    //
    // ⚠️ 本模块**没有 id 段形状**：税率记录与绑定都靠查询参数定位
    //   （税种 / 辖区 / 税码 / 店铺 / 时刻），所以正则里不需要 {uuid} 分支 ——
    //   少一个通配支，就少一个「把非 UUID 段当 id 放行」的口子。
    prefix: 'finance/',
    env: 'FINANCE_URL',
    fallback: 'http://127.0.0.1:8087',
    pattern:
      /^tax\-rates\/(?:binding-versions|rate-versions|rate-compare|capabilities|options|jurisdictions|state-sales-tax|shops|rates|resolve|bindings)$/,
  },
  {
    // 16.3.2.1 VAT/IOSS（channel 服务，卷 16「合规与风控」L3 16.3.2 税务下的第一格）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 那条巨型 alternation 里并分支 ——
    //   那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`tax-rates`（12.6.1.1，finance）那条之后）----
    //   同批其它车道的插入点是「数组最开头」「第一个 `prefix: 'identity/'` 之前」
    //   与「第一个 `prefix: 'warehouse/'` 之前」；本车道落在这里，与它们的插入点相距足够远，
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「vat-ioss」的零命中核对结论（本车道发车前实测）----
    //   `grep -c "vat-ioss" apps/web/src/lib/api-proxy.ts` = **0**（exit 1），
    //   本条是全文件第一次出现这个串 ⇒ 首 token 既是独立字面量，
    //   也不会被既有分支的前缀吃掉。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   vat-ioss/capabilities                    能力声明（上游真读得到 / 下游缺口归谁 / 判定口径）
    //   vat-ioss/options                         筛选项（分档 / 原因 / 申报状态 / 周期 / 辖区 / 币种）
    //   vat-ioss/shops                           店铺目录（读 channel 自己的 shop 表）+ 是否落在 IOSS 覆盖范围内
    //   vat-ioss/registrations                   IOSS 登记清单（GET）/ 登记（POST，含适用店铺与商品）
    //   vat-ioss/registrations/{uuid}            单条登记详情（GET）/ 改登记（PUT，登记号与生效时刻不可改）
    //   vat-ioss/filing-cycles                   申报周期配置（GET）/ 登记（POST）
    //   vat-ioss/filing-cycles/{uuid}            改申报周期配置（PUT）
    //   vat-ioss/filing-periods                  申报期清单（GET）/ 开一期申报（POST）
    //   vat-ioss/filing-periods/{uuid}           单期申报详情（GET，含申报状态）
    //   vat-ioss/filing-periods/{uuid}/draft     出底稿（POST：DRAFT → DRAFTED）
    //   vat-ioss/filing-periods/{uuid}/confirm   确认（POST：DRAFTED → CONFIRMED）
    //   vat-ioss/filing-periods/{uuid}/statistics 本期底稿统计（GET，**按分档 × 币种**分组）
    //   vat-ioss/orders/sync                     从 9 订单拉真实订单 → 识别 IOSS → 落库（POST）
    //   vat-ioss/orders                          IOSS 订单清单（GET，真分页 + 真筛选）
    //   vat-ioss/orders/{uuid}                   单笔订单详情（GET：判定 + 行明细）
    //   vat-ioss/statistics                      全量统计（GET，**按分档 × 币种**分组）
    //   vat-ioss/tax-rates                       税率适用（GET，读 finance 12.6.1.1；读不到就说读不到）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   vat-ioss                                  —— 裸路径：后端**没有**「一条汇总列表接口」，
    //                                                 一切入口都带子路径。
    //   vat-ioss/filings                          —— 🔴 12.6.1.2 VAT/IOSS 申报数据**未交付**
    //                                                 （同批另一条车道）：放行会让界面以为「已经能出申报底稿」。
    //   vat-ioss/filings/capabilities             —— 同上。
    //   vat-ioss/calendar                         —— 🔴 16.3.1.2 申报日历**未交付**。
    //   vat-ioss/calendar/capabilities            —— 同上。
    //   vat-ioss/submit / vat-ioss/declarations   —— 🔴 本系统**不代用户向税务机构提交申报**；
    //                                                 刻意没有提交入口（只保证数据完整可核）。
    //   vat-ioss/not-a-uuid                       —— 非 UUID 段不应被 uuid 分支吃掉。
    //   vat-ioss/orders/not-a-uuid                —— 同上。
    //   vat-ioss/registrations/not-a-uuid         —— 同上。
    //   vat-ioss/filing-cycles/not-a-uuid         —— 同上。
    //   vat-ioss/filing-periods/not-a-uuid        —— 同上。
    //   vat-ioss/filing-periods/{uuid}/not-a-shape —— 多一层仍然 404。
    //   vat-ioss/orders/sync/{uuid}               —— 本模块的同步是**集合级**的，没有 id 段。
    //   vat-ioss/sales-tax / vat-ioss/epr         —— 🔴 16.3.2.3 销售税与 16.3.2.2 EPR
    //                                                 **各有自己的前缀**（channel/sales-tax、
    //                                                 channel/epr-registrations），不是本模块的子路径。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `draft` / `confirm` / `sync` 这种非 UUID 形状也当成 id 放行。
    //   字面量子路径（capabilities / options / shops / statistics / tax-rates / sync /
    //   draft / confirm）排在 {uuid} 段**之前**判别 —— alternation 是首个命中即返回，
    //   顺序反了会被通配支吃掉半截。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^vat\-ioss\/(?:capabilities|options|shops|statistics|tax\-rates|registrations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|filing\-cycles(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|filing\-periods(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:draft|confirm|statistics))?)?|orders(?:\/sync|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 18.4.2.1 文本与翻译（platform 服务，卷 18「集成开放与智能自动化」L3 18.4.2 AI 能力下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有任何一条里并分支 ——
    //   既有条目**一字未改、也不重排**（18.1.1.1 那条 apps 的正则**逐字未动**）。
    //
    // ---- 插入锚点选在**18.1.1.1 那条之前**（同服务相邻、数组末尾之前约 56 行）----
    //   同批其它车道的插入点是「数组最开头」与「数组最末尾」；本车道落在 18.1.1.1 之前，
    //   与「数组最开头」相距 12000 行以上，与「数组最末尾」相距 56 行 ——
    //   两个 hunk 各自独立，并入时 git 能干净求并。
    //
    // ---- 首 token「text」的零命中核对结论 ----
    //   本模块与 18.1.1.1 同为 platform 服务，两条都是 `prefix: 'platform/'`：
    //   resolve() 按 services[] 顺序逐条试，**前缀相同、正则不相交** ——
    //     18.1.1.1 的正则是 `^apps(?:\/…)?$`，首 token 是 `apps`；
    //     本条的正则是 `^text\/…$`，首 token 是 `text`。
    //   两者两两不相交 ⇒ 既有那支**吃不到**本模块的路径，本模块也**吃不到** apps 的路径。
    //   （既有 236 条 services[] 正则里没有以 `text` 开头的首 token：channel 的 `platforms`
    //     是 `channel/` 前缀下「电商平台目录」的**首 token**，与本模块无关。）
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform/text/capabilities            能力声明（哪些真接通 / 哪些还没交付、归谁）
    //   platform/text/overview                概览（几个数）
    //   platform/text/glossary                术语表列表（GET）/ 登记或修改（POST）
    //   platform/text/glossary/{uuid}         删除一条术语（DELETE）
    //   platform/text/banned-terms            违禁词表列表（GET）/ 登记或修改（POST）
    //   platform/text/banned-terms/{uuid}     删除一条违禁词（DELETE）
    //   platform/text/rules                   长度与字符集规则列表（GET）/ 登记或修改（POST）
    //   platform/text/rules/{uuid}            删除一条规则（DELETE）
    //   platform/text/templates               客服回复模板列表（GET）/ 登记或修改（POST）
    //   platform/text/templates/{uuid}        删除一个模板（DELETE）
    //   platform/text/templates/{uuid}/binding 变量绑定（把模板渲染成一封可发出去的回复）
    //   platform/text/tasks                   翻译任务列表（GET，真分页 + 真筛选）/ 建任务（POST）
    //   platform/text/tasks/{uuid}            任务详情（GET：任务 + 当前草稿 + 历史版本 + 检查结论）
    //   platform/text/tasks/{uuid}/generate   走 AI 通道生成（POST，**AI 未接入时 409**）
    //   platform/text/tasks/{uuid}/drafts     人工录入 / 编辑译文（POST，追加新版本）
    //   platform/text/tasks/{uuid}/confirm    人工确认（POST，**发布的硬门槛**）
    //   platform/text/tasks/{uuid}/publish    发布（POST，必须已确认且无 FAIL 检查）
    //   platform/text/tasks/{uuid}/reject     驳回（POST，终态）
    //   platform/text/published               已发布的文案（GET，**下游可取的那一份**）
    //   platform/text/locales                 可选目标语言（GET，读 3.4.3 多语言）
    //   platform/text/source-candidates       可拿来翻译的上游源文本（GET，读 3.4.3 / 4.2.1.1）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   platform/text                          —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   platform/text/generate                 —— 生成是**按任务**走的（tasks/{uuid}/generate），
    //                                               没有「裸生成」这种形状。
    //   platform/text/tasks/{uuid}/publish-all —— 后端没有批量发布。
    //   platform/text/tasks/{uuid}/auto        —— 🔴 自动发布属 18.4.2.1 的**红线**：
    //                                               「翻译结果必须人工确认后发布」——
    //                                               放行它等于让界面以为可以不确认就发。
    //   platform/text/tasks/{uuid}/not-a-shape —— 多一层仍然 404。
    //   platform/text/tasks/not-a-uuid        —— 非 UUID 段不应被 uuid 分支吃掉。
    //   platform/text/glossary/{uuid}/binding —— 变量绑定只属于**模板**，术语表没有这层。
    //   platform/text/ai-settings              —— 🔴 18.4.3.1 使用与配额**未交付**；
    //                                               放行它会让界面以为「能配 AI 供应商」。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `confirm` / `publish` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / overview / glossary / banned-terms / rules / templates /
    //   tasks / published / locales / source-candidates）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'platform/',
    env: 'PLATFORM_URL',
    fallback: 'http://127.0.0.1:8089',
    pattern:
      /^text\/(?:capabilities|overview|published|locales|source\-candidates|glossary(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|banned\-terms(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/binding)?)?|tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:generate|drafts|confirm|publish|reject))?)?)$/,
  },
  {
    // 18.1.1.1 应用与凭证（platform 服务，卷 18「集成开放与智能自动化」L3 18.1.1 API 管理下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有任何一条里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（最后一个 services[] 元素之后、收尾的 `];` 之前）----
    //   同批其它车道的插入点是「数组最开头」与「第一个 identity/ 之前」；
    //   本车道落在末尾，与它们的插入点相距 12000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「apps」的零命中核对结论 ----
    //   发车前用 `grep -n "platform/" apps/web/src/lib/api-proxy.ts` 实测：**既有条目零命中**
    //   （本仓此前**没有任何** services[] 元素用 `platform/` 前缀 —— channel 那支用的是
    //   `platforms`（02 渠道与店铺里「电商平台」的目录），它是 `channel/` 前缀下的**首 token**，
    //   与本模块的 `apps` 首 token 完全不同）。
    //   ⇒ 两条正则的**前缀就不同**（`platform/` vs `channel/` vs `catalog/` …），
    //   resolve() 先按前缀过滤再测正则 ⇒ 天然互斥，不存在「既有两支吃掉本模块路径」的可能。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform/apps                          应用列表（GET）/ 注册应用（POST）
    //   platform/apps/capabilities             能力声明（上游真接通 / 下游缺口归谁）
    //   platform/apps/options                  筛选项（应用类型 / 状态 / 操作 / 配额窗口 / 资源码）
    //   platform/apps/overview                 概览（几个数）
    //   platform/apps/{uuid}                   应用档案详情
    //   platform/apps/{uuid}/revoke            吊销应用（**一键吊销**全部凭证）
    //   platform/apps/{uuid}/credentials       凭证列表（GET）/ 生成凭证（POST，明文只返回一次）
    //   platform/apps/{uuid}/credentials/rotate 轮换凭证（新明文 + 旧摘要失效）
    //   platform/apps/{uuid}/credentials/verify 凭证自检（凭证 / 授权范围 / IP 白名单 / 配额四个判定）
    //   platform/apps/{uuid}/credentials/{uuid}/revoke 吊销单个凭证
    //   platform/apps/{uuid}/scopes            授权范围列表（GET）/ 授权（POST，幂等）
    //   platform/apps/{uuid}/scopes/{uuid}     撤销一条授权（DELETE）
    //   platform/apps/{uuid}/ip-allowlist      IP 白名单列表（GET）/ 新增（POST）
    //   platform/apps/{uuid}/ip-allowlist/{uuid} 删除一条白名单（DELETE）
    //   platform/apps/{uuid}/quotas            配额配置列表（GET）/ 设置（POST，同窗口覆盖）
    //   platform/apps/{uuid}/quota-status      额度判定（每个窗口类型一行）
    //   platform/apps/{uuid}/audit-events      审计留痕（真分页，新到旧）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   platform/app-quota-usages —— 🔴 那是**服务端到服务端**的用量回报路径（内部密钥），
    //                               浏览器打不到；放行它等于让任意浏览器往别的租户写用量。
    //   platform/apps/{uuid}/webhooks / platform/apps/{uuid}/call-logs
    //                             —— 🔴 18.1.2 事件订阅 / 18.1.1.3 调用监控**全部未交付**；
    //                               放行这些形状会让界面以为「已经能看到调用日志」。
    //   platform/apps/not-a-uuid  —— 非 UUID 段不应被放行。
    //   platform/apps/{uuid}/not-a-shape —— 多一层仍然 404。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `revoke` / `rotate` / `verify` 这种非 UUID 形状
    //   也当成 id 放行。
    // 字面量子路径（capabilities / options / overview）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'platform/',
    env: 'PLATFORM_URL',
    fallback: 'http://127.0.0.1:8089',
    pattern:
      /^apps(?:\/(?:capabilities|options|overview)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:revoke|credentials(?:\/(?:rotate|verify)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/revoke)?|scopes(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|ip-allowlist(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|quotas|quota-status|audit-events))?)?$/,
  },
  {
    // 18.2.1.1 财务软件（platform 服务，卷 18「集成开放与智能自动化」L3 18.2 外部系统对接下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有任何一条里并分支 ——
    //   既有条目**一字未改、也不重排**（18.1.1.1 那条 apps、18.4.2.1 那条 text 的正则逐字未动）。
    //
    // ---- 插入锚点选在**数组末尾**（18.1.1.1 那条之后、收尾的 `];` 之前）----
    //   18.4.2.1 那条锚在 18.1.1.1 **之前**，本车道锚在**之后** —— 两个 hunk 各自独立，
    //   并入时 git 能干净求并。
    //
    // ---- 首 token「finance-software」的零命中核对结论 ----
    //   本模块与 18.1.1.1 / 18.4.2.1 同为 platform 服务，三条都是 `prefix: 'platform/'`：
    //   resolve() 按 services[] 顺序逐条试，**前缀相同、正则不相交** ——
    //     18.1.1.1 的正则是 `^apps…$`（首 token apps）；
    //     18.4.2.1 的正则是 `^text\/…$`（首 token text）；
    //     本条的正则是 `^finance\-software\/…$`（首 token finance-software）。
    //   三者两两不相交 ⇒ 既有那两支**吃不到**本模块的路径，本模块也**吃不到**它们的路径。
    //   ⚠️ 注意 `finance/` 前缀那一支是 **12 财务核算**服务（另一个 prefix），
    //     与本模块的 `finance-software` 首 token 不同，不要混淆。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   platform/finance-software/capabilities                 能力声明（哪些真接通 / 哪些还没交付、归谁）
    //   platform/finance-software/overview                     概览（几个数）
    //   platform/finance-software/periods                      会计期间清单（读 12.5.2.1）
    //   platform/finance-software/currencies                   可选币种（读 12.5.2.1 的真实取值）
    //   platform/finance-software/accounts                     连接档案列表（GET，真筛选）/ 登记或修改（POST）
    //   platform/finance-software/accounts/{uuid}/disable      停用一个连接档案（**不删**：历史要能复现）
    //   platform/finance-software/subject-maps                 科目映射列表（GET）/ 登记或修改（POST）
    //   platform/finance-software/subject-maps/{uuid}          删除一条科目映射（DELETE）
    //   platform/finance-software/master-maps                  主数据映射列表（GET）/ 登记或修改（POST）
    //   platform/finance-software/master-maps/{uuid}           删除一条主数据映射（DELETE）
    //   platform/finance-software/voucher-files                凭证文件导出记录列表（GET）
    //   platform/finance-software/voucher-files/{uuid}         凭证文件详情（GET，**含真内容**）
    //   platform/finance-software/voucher-files/export         导出凭证文件（POST）
    //   platform/finance-software/reconcile-tasks              核对任务列表（GET）/ 建核对任务（POST）
    //   platform/finance-software/reconcile-tasks/{uuid}       核对详情（GET：任务 + 外部余额 + 差异）
    //   platform/finance-software/reconcile-tasks/{uuid}/balances  导入外部余额（POST）
    //   platform/finance-software/reconcile-tasks/{uuid}/compute   算核对差异（POST）
    //   platform/finance-software/push                         直连推送凭证（POST，**适配器未接入时 409**）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   platform/finance-software                        —— 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    //   platform/finance-software/voucher-files/download  —— 下载走的是**导出记录详情**（含真内容），
    //                                                       没有单独的下载端点。
    //   platform/finance-software/sync                    —— 🔴 **直连同步主数据**未交付：
    //                                                       放行它会让界面以为「能一键把主数据推到金蝶」。
    //   platform/finance-software/push-status             —— 推送未接入，没有「推送历史」这种形状
    //                                                       （凭空放行会让界面以为有推送流水表）。
    //   platform/finance-software/reconcile-tasks/{uuid}/push —— 推的是**凭证**，不是核对任务。
    //   platform/finance-software/accounts/{uuid}/delete  —— 连接档案**不删**（法定凭证不可删），只有停用。
    //   platform/finance-software/reconcile-tasks/not-a-uuid   —— 非 UUID 段不应被 uuid 分支吃掉。
    //   platform/finance-software/subject-maps/{uuid}/binding  —— 科目映射没有「变量绑定」这一层。
    //
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`）：后者会把 `disable` / `export` 这种非 UUID 形状也当成 id 放行。
    // 字面量子路径（capabilities / overview / periods / currencies）排在 {uuid} 段**之前**判别 ——
    //   alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'platform/',
    env: 'PLATFORM_URL',
    fallback: 'http://127.0.0.1:8089',
    pattern:
      /^finance\-software\/(?:capabilities|overview|periods|currencies|accounts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/disable)?|subject\-maps(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|master\-maps(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|voucher\-files(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|reconcile\-tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:balances|compute))?)?|push)$/,
  },
  {
    // 1.5.2.1 服务与任务状态（channel 服务，卷 01「工作台与门户」L3 1.5.2「服务与任务状态」）。
    //
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 channel 分支的 alternation 里
    //   并分支 —— 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（`platform/finance-software` 那条之后）----
    //   与同批其它车道的插入点相距足够远 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「service-task-status」的零命中核对结论 ----
    //   本文件既有 **243 条** services[] 正则（按 `pattern:` 逐条抽出实测）里
    //   **没有任何一条**能命中 `service-task-status` 开头的形状：
    //   既有的 `service-tickets`（客服工单）、`service-ticket-process` 首 token 全不相同，
    //   其余 243 条与本模块无公共前缀。
    //   逐条用 `new RegExp(pat).test('…')` 实测过：既有全部正则对本模块的 **6 个正形状**
    //   **零命中**，本模块的 **6 个负向形状**对既有正则**也零命中**。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   service-task-status/capabilities  能力声明（规则表 · 阈值 · 逐张表的来源清单 · 失败分类）
    //   service-task-status/options       筛选项（来源取自注册表，触发来源取自真实数据）
    //   service-task-status/summary       总览（任务状态分布 · 停滞 / 失败计数 · 失败分类计数）
    //   service-task-status/tasks         任务状态列表（GET，真分页 + 真筛选）
    //   service-task-status/tasks/{uuid}  单条任务（GET）
    //   service-task-status/alerts        提示清单（GET；**停滞与失败是两个独立列表**）
    //
    // ⚠️ **本模块是只读的**：卷 01 的边界写死「工作台只读 + 跳转；一切状态变更回到
    //   归属系统完成」。所以这里**一个写形状都不放行** —— 放行一个不存在的写入口
    //   比 404 更糟（界面会以为「重跑同步 / 取消任务」在这儿能做）。
    //
    // ⚠️ id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
    //   （不是 `[0-9a-f-]+`：后者会把 `tasks` 这种非 UUID 形状也当成 id 放行）。
    //   字面量子路径（capabilities / options / summary / tasks / alerts）排在 {uuid} 段
    //   **之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   service-task-status                     —— 裸路径：后端没有「一条汇总列表接口」
    //   service-task-status/overview            —— 汇总段只有下面那五个子路径
    //   service-task-status/stats               —— 同上
    //   service-task-status/tasks/not-a-uuid     —— 非 UUID 段不应被 uuid 分支吃掉
    //   service-task-status/tasks/{uuid}/extra   —— tasks 段下只有列表与单条详情
    //   service-task-status/tasks/{uuid}/retry   —— 🔴 「重跑同步」没有归属系统的写端点，
    //                                                 工作台不写业务数据 ⇒ 这个形状不存在
    //   service-task-status/tasks/{uuid}/cancel  —— 同上，「取消任务」归归属系统
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 末尾**刻意不带 `?`**：带了 `?` 就等于放行裸路径 `service-task-status`。
    pattern:
      /^service\-task\-status(?:\/(?:capabilities|options|summary|alerts|tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))$/,
  },
  {
    // 13.2.2.2 SKU/SPU/Listing（reporting 服务，卷 13「利润与经营分析」L3 13.2 销量分析下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（收尾的 `];` 之前、紧跟最后一条既有元素之后）----
    //   同批其它车道（13.2.1.1）的插入点在「13.1.1.2 多店对比」那条元素之后（数组最前面那一段），
    //   两个锚点相距 10000 行以上，并入时 git 能干净求并。
    //
    // ---- 首 token「product-sales」的零命中核对结论 ----
    //   本模块与 13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 13.1.2.x 同为 reporting 服务，
    //   都是 `prefix: 'reporting/'`：resolve() 按 services[] 顺序逐条试，
    //   **前缀相同、正则不相交** —— 既有那几条的首 token 分别是
    //   overview / expense-variance / store-comparison / metric-cards / store-overview，
    //   没有一条能吃下 `product-sales…`；本条的首 token 是 `product-sales`，
    //   也吃不到它们的路径。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**）：
    //   product-sales                  商品销量报表（SKU / SPU / Listing 三层；裸路径是 @GetMapping 在类根上的）
    //   product-sales/capabilities     能力声明（真读过哪些上游 / 确实拿不到什么）
    //   product-sales/metrics          商品维度指标口径目录（口径唯一的展示形态）
    //   product-sales/spu-summary      SPU 汇总（多平台多 SKU 合并看）
    //   product-sales/variants         变体销量分布
    //   product-sales/drilldown        层级钻取（哪张订单的哪一行、哪个 SKU）
    //   product-sales/refresh          投影刷新（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/product-sales/{uuid}       —— 顶层没有 id 段（报表按查询参数定位，不是按 id）。
    //   reporting/product-sales/unknown      —— 未实现的子路径仍然 404。
    //   reporting/product-sales/breakdown    —— 蓝图里的「层级钻取」是 drilldown，不是 breakdown。
    //   reporting/product-sales/{uuid}/variants —— 变体分布是按 SPU 编码查的，不是按 uuid 段。
    //
    // 字面量子路径（capabilities / metrics / spu-summary / variants / drilldown / refresh）
    // 排在可选段**之前**判别 —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^product\-sales(?:\/(?:capabilities|metrics|spu\-summary|variants|drilldown|refresh))?$/,
  },
  {
    // 13.3.2.1 流量转化（reporting 服务，卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第一格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（收尾的 `];` 之前、紧跟 13.2.2.2 product-sales 那条之后）----
    //   同批其它车道的插入点（13.1.1.1 / 13.4.2.2 / 13.2.1.1 / 13.2.1.2 / 13.2.2.1 那几条）
    //   在数组靠前那一段，与本锚点相距 12000 行以上 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「traffic-conversion」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（本车道的 .tmp/ 一次性脚本，不入库）把本文件里**全部 242 条既有元素**
    //   （prefix + 正则）逐条抽出来，按 resolve() 的**同构口径**实测
    //   （path.startsWith(prefix) 收窄服务 → 再测 pattern.test(path.slice(prefix.length))）：
    //     reporting/traffic-conversion               零命中
    //     reporting/traffic-conversion/capabilities  零命中
    //     reporting/traffic-conversion/metrics       零命中
    //     reporting/traffic-conversion/drilldown     零命中
    //     reporting/traffic-conversion/refresh       零命中
    //   **零命中** —— 本文件里 prefix === 'reporting/' 的既有条目**一律不改**，它们的首 token
    //   分别是 overview / expense-variance / store-comparison / sales-trend / metric-cards /
    //   store-overview / targets / store-site / time-pattern / product-sales，
    //   没有一条能吃下 `traffic-conversion…`；而别的 prefix 下的正则根本吃不到本模块的路径
    //   （resolve() 先按 prefix 闸门筛）。
    //   ⚠️ 特别核对过两个**首 token 同字母开头**的老邻居：`targets` 与 `time-pattern` ——
    //   它们与 `traffic-conversion` **两两不相交**（完整 token 不相等，不是前缀关系）。
    //   反向也核对了本文件里 **49 条 reporting 形状**：本条新正则对它们**零命中**
    //   ⇒ 本条不会遮蔽别的车道已交付的路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 reporting.openapi.json 里就这 5 条）：
    //   traffic-conversion             流量转化主报表（漏斗 + 关键指标 + 转化率趋势 + 异常诊断）
    //   traffic-conversion/capabilities 能力声明（真读过哪些上游 / 确实拿不到什么）
    //   traffic-conversion/metrics      指标口径目录（口径唯一的展示形态）
    //   traffic-conversion/drilldown    构成明细下钻（哪个平台商品 / 哪个广告活动 / 哪张内部订单）
    //   traffic-conversion/refresh      投影刷新（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/traffic-conversion/{uuid}        —— 顶层没有 id 段（报表按查询参数定位，不是按 id）。
    //   reporting/traffic-conversion/unknown-sub   —— 多一层仍然 404。
    //   reporting/traffic-conversion/funnel        —— 🔴 漏斗随主报表一起给出，没有独立端点。
    //   reporting/traffic-conversion/trend         —— 🔴 趋势随主报表一起给出，没有独立端点。
    //   reporting/traffic-conversion/diagnosis     —— 🔴 诊断随主报表一起给出，没有独立端点。
    //
    // 字面量子路径（capabilities / metrics / drilldown / refresh）排在可选段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^traffic\-conversion(?:\/(?:capabilities|metrics|drilldown|refresh))?$/,
  },
  {
    // 13.3.2.2 关联营销效果（reporting 服务，卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第二格）。
    // ⚠️ 本条目是**新增一条独立的 services[] 元素**，不是往既有 reporting/ 正则里并分支 ——
    //   既有条目**一字未改、也不重排**。
    //
    // ---- 插入锚点选在**数组末尾**（收尾的 `];` 之前、紧跟 13.3.2.1 traffic-conversion 那条之后）----
    //   与同批其它车道的插入点（13.1.1.1 / 13.4.2.2 / 13.2.1.1 / 13.2.1.2 / 13.2.2.1 那几条）
    //   在数组靠前那一段，与本锚点相距 12000 行以上 ⇒ 并入时 git 能干净求并。
    //
    // ---- 首 token「affiliate-marketing」的零命中核对结论（实测，非目测）----
    //   发车前用脚本（本车道的 .tmp/ 一次性脚本，不入库）把本文件里**全部 249 条既有元素**
    //   （prefix + 正则）逐条抽出来，按 resolve() 的**同构口径**实测
    //   （path.startsWith(prefix) 收窄服务 → 再测 pattern.test(path.slice(prefix.length))）：
    //     reporting/affiliate-marketing               零命中
    //     reporting/affiliate-marketing/capabilities  零命中
    //     reporting/affiliate-marketing/metrics       零命中
    //     reporting/affiliate-marketing/attributions  零命中
    //     reporting/affiliate-marketing/refresh       零命中
    //   **零命中** —— 本文件里 prefix === 'reporting/' 的既有条目**一律不改**，它们的首 token
    //   分别是 overview / expense-variance / store-comparison / sales-trend / metric-cards /
    //   store-overview / targets / store-site / time-pattern / product-sales / product-performance /
    //   traffic-conversion，没有一条能吃下 `affiliate-marketing…`。
    //   ⚠️ 特别核对过两个**首 token 同字母开头**的老邻居：`affiliate-marketing` 与
    //   `ad-…`（channel 侧）分属**不同 prefix**（resolve() 先按 prefix 闸门筛）⇒ 互不影响。
    //   反向也核对了本文件里 **51 条别的车道已交付的 reporting 形状**：本条新正则对它们**零命中**
    //   ⇒ 本条不会遮蔽别的车道已交付的路由。
    //
    // 形状逐个列出（**只放行本模块真实实现的路径**，契约 reporting.openapi.json 里就这 5 条）：
    //   affiliate-marketing               关联营销效果主报表（营销位效果 + 归因成交 + ROI）
    //   affiliate-marketing/capabilities 能力声明（真读过哪些上游 / 确实拿不到什么）
    //   affiliate-marketing/metrics      指标口径目录（口径唯一的展示形态）
    //   affiliate-marketing/attributions 归因明细下钻（每条带规则快照）
    //   affiliate-marketing/refresh      投影刷新（POST，写的是投影不是业务事实）
    //
    // ⚠️ **刻意不放行**（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   reporting/affiliate-marketing/{uuid}        —— 顶层没有 id 段（报表按查询参数定位，不是按 id）。
    //   reporting/affiliate-marketing/unknown-sub   —— 多一层仍然 404。
    //   reporting/affiliate-marketing/roi           —— 🔴 ROI 随主报表按 (营销位, 归因规则) 分列给出，
    //                                                  没有独立端点（拆出去反而会丢掉规则上下文）。
    //   reporting/affiliate-marketing/slots         —— 🔴 营销位台账随主报表一起给出，没有独立端点。
    //
    // 字面量子路径（capabilities / metrics / attributions / refresh）排在可选段**之前**判别 ——
    // alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    prefix: 'reporting/',
    env: 'REPORTING_URL',
    fallback: 'http://127.0.0.1:8088',
    pattern:
      /^affiliate\-marketing(?:\/(?:capabilities|metrics|attributions|refresh))?$/,
  },
];

function resolve(path: string) {
  for (const service of services) {
    if (!path.startsWith(service.prefix)) continue;
    if (service.pattern.test(path.slice(service.prefix.length)))
      return { base: process.env[service.env] ?? service.fallback };
  }
  return null;
}

export async function proxy(
  request: NextRequest,
  context: { params: { path: string[] } | Promise<{ path: string[] }> },
) {
  const path = (await context.params).path.join('/');
  const target = resolve(path);
  if (!target) return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  if (
    !['GET', 'HEAD'].includes(request.method) &&
    request.headers.get('origin') !== request.nextUrl.origin
  ) {
    return NextResponse.json({ message: '请求来源不正确' }, { status: 403 });
  }
  const { base } = target;
  const body = request.method === 'GET' ? undefined : await request.text();
  if (body && body.length > 200_000)
    return NextResponse.json({ message: '请求内容过大' }, { status: 413 });
  try {
    const upstream = await fetch(`${base}/${path}${request.nextUrl.search}`, {
      method: request.method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${request.cookies.get(cookieName)?.value ?? request.cookies.get(legacyCookieName)?.value ?? ''}`,
      },
      ...(body ? { body } : {}),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    });
    const text = await upstream.text();
    const contentType = upstream.headers.get('content-type') ?? '';
    // CSV 导出必须原样透传：走 JSON.parse 会把 CSV 解析失败变成 503，而且即使解析成功
    // 也会被重新序列化成 JSON，下载到的是一串引号转义的文本。
    // 只认 text/csv：JSON 接口一律走下面的正常分支，不受这里影响。
    //
    // 8.3.4.2 面单与标签打印的**打印内容下载**（ZPL 指令流 / HTML 打印视图）同理：
    // 两者都不是 JSON，走 JSON.parse 只会得到一个假的 503，现场看到的就是「下载失败」。
    // 所以把这两个 content-type 一并纳入原样透传（content-disposition 也一起带走，
    // 否则浏览器会拿不到文件名）。白名单式列举而不是「非 JSON 就透传」：
    // 后者会让上游一个返回 HTML 的错误页被当成文件下载下来，更难排查。
    const passthrough =
      contentType.includes('text/csv') ||
      contentType.includes('text/vnd.zpl') ||
      contentType.includes('text/html');
    if (text && passthrough) {
      const raw = new NextResponse(text, { status: upstream.status, headers: {} });
      raw.headers.set('Content-Type', contentType);
      const disposition = upstream.headers.get('content-disposition');
      if (disposition) raw.headers.set('Content-Disposition', disposition);
      raw.headers.set('Cache-Control', 'no-store');
      if (upstream.status === 401) {
        raw.cookies.delete(cookieName);
        raw.cookies.delete(legacyCookieName);
      }
      return raw;
    }
    const data = text ? JSON.parse(text) : null;
    let token: string | undefined;
    if (path === 'auth/login' && upstream.ok && data?.token) {
      token = data.token;
      delete data.token;
    }
    const response = NextResponse.json(data, {
      status: upstream.status,
      headers: { 'Cache-Control': 'no-store' },
    });
    if (token) {
      response.cookies.delete(legacyCookieName);
      response.cookies.set(cookieName, token, {
        httpOnly: true,
        sameSite: 'strict',
        secure: request.nextUrl.protocol === 'https:',
        path: '/',
        maxAge: 8 * 3600,
      });
    }
    if ((path === 'auth/logout' && upstream.ok) || upstream.status === 401) {
      response.cookies.delete(cookieName);
      response.cookies.delete(legacyCookieName);
    }
    return response;
  } catch {
    return NextResponse.json({ message: '服务暂时无法连接，请稍后重试' }, { status: 503 });
  }
}

/** Next.js route handlers receive asynchronously resolved route parameters. */
export async function routeProxy(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  return proxy(request, context);
}
