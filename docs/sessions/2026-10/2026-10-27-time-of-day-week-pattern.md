# 2026-10-27 · 13.2.1.2 分时与分周模式（V392）

> 车道 `feat/13-2-1-2` · 迁移版本 **V392** · reporting 服务 + apps/web。
> 本车道**被网络中断过两次**（`net::ERR_CONNECTION_CLOSED`）：第一次在跑 L1 闸时（9 个文件 / 5129 行全部保留），
> 第二次在改完台账行时（后端 + 前端 + 契约全部在位、闸全绿）。两次都**没有丢代码**，
> 靠的是「worktree 当唯一真相源 + 每次中断后先核实既有文件再续做」。

## 决策摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.2.1.2 分时与分周模式（阶段 D7，L3 锚点 13.2.1） |
| 迁移 | `V392__time_of_day_week_pattern.sql` —— 4 张表，纯 CREATE TABLE |
| 后端 | `ReportingTimePattern{Catalog,Models,Port,RefreshService,Service,Controller}` |
| 端点 | 10 个映射 / 7 条路径，挂 `/reporting/time-pattern` |
| 页面 | `/analytics/sales/time-pattern` |
| L1 | 后端 **24 例绿**（19 + 5 三断言）· 前端 50 例绿 · **全量 allowlist 回归 263 文件 / 1487 例绿** |
| 契约 | `reporting.ts` + `reporting.openapi.json`：**17 schema / 7 path，纯增量，零删除** |

## 这个功能做什么

按**每个店铺各自的时区**算出「什么时候出单」，给出时段分布热力图与高峰时段排序，
让仓内人力排期和分时广告投放有据可依。

## 技术落地

- **迁移**：`V392__time_of_day_week_pattern.sql` —— 4 张表
  （`reporting_time_pattern_metric_definition` / `reporting_time_pattern_slot` /
  `reporting_time_pattern_shop_profile` / `reporting_time_pattern_fact`），
  `tenant_id NOT NULL` + ENABLE **且** FORCE ROW LEVEL SECURITY + 隔离策略 + GRANT；
  **零 ADD COLUMN、零 SET NOT NULL、零跨库外键**（集成头静态预检三条全 0）。
- **端点**（`ReportingTimePatternController`，10 个映射 / 7 条路径）：
  `GET /`（热力图 + 小时/星期分布 + 时段判定 + 高峰排序）·
  `GET /capabilities` · `GET /metrics` · `GET /shops` ·
  `GET /slots`（时段配置清单）· `POST /slots`（改单条）· `PUT /slots`（整体替换）·
  `PUT /slots/{id}` · `DELETE /slots/{id}` · `POST /refresh`（刷新投影）。
  ⚠️ id 段用**完整 UUID 形状**（不是 `[0-9a-f-]+`，后者会把 `capabilities` 也当 id 放行）。
- **页面**：`/analytics/sales/time-pattern`
  —— `apps/web/src/components/analytics/time-pattern-model.ts`（纯函数层，不做业务算术）+
  `time-pattern-workspace.tsx`（真实工作区）+ `app/analytics/sales/time-pattern/page.tsx`。
  筛选维度（时间范围 / 店铺 / 汇总口径）、时段分布热力图、高峰时段排序、
  店铺时区档案、时段配置清单、能力声明区块，**加载态 / 错误态 / 空态三态齐备**。
- **测试**：`TimePatternIntegrationTest`（19 例）· `TimePatternTenancyIntegrationTest`（5 例）·
  前端 `time-pattern-model.test.ts` + `time-pattern-allowlist.test.ts`。

## 关键设计决策

### 🔴 跨店铺汇总口径：两个都做，但**默认按店铺本地时间**，且在页面上说清区别

蓝图的硬约束是「**跨境多时区店铺必须按各自时区统计，汇总时区不同会造成误判**」。
所以本模块把汇总口径做成**显式二选一**，而不是悄悄替用户选一个：

| 口径 | 「小时 17」指的是 | 回答的问题 |
| --- | --- | --- |
| **`LOCAL_TIME`（默认）** | **每个店铺各自**的本地 17:00–17:59 | 「买家在什么时间点下单」 |
| `UTC_SLOT` | 所有店铺共同的 **UTC** 17:00–17:59 | 「仓内同一绝对时刻的压力」 |

**默认选 `LOCAL_TIME` 的理由**：蓝图的下游是 8.5 作业排班与 14 广告（分时投放），
问的都是「买家在什么时间点下单」，而**买家的时钟是店铺所在市场的本地时钟** ⇒
用 UTC 槽位回答排班问题会系统性错位。两种口径的说明**原样显示在页面上**，
让用户看得懂「汇总是按哪个口径」。

### 🔴 时区取自**站点**的 `default_timezone`，不是店铺、不是硬编码表

读 channel 的 `shop_marketplace → marketplace.default_timezone`（2.1.1.3 站点参数）：
一个店铺可以启用多个站点 ⇒ 时区跟着**站点**走。
换算用 **IANA 标识**（`ZoneId.of` + `atZoneSameInstant`），**不用固定 UTC 偏移**（固定偏移会错过夏令时）。
**定不了时区的订单单列成「无法唯一归属」，不进格子、不摊给任何时段**（摊给谁是造假），
且**不计入任何维度的占比分母**。

### 🔴 高峰低谷有两个**互不替代**的刻度，分开显示

- **时段层级（PEAK / SHOULDER / TROUGH）** = **租户自己配**的区间与判定阈值
  （落 `reporting_time_pattern_slot`，数据层 CHECK 保证区间**不可重叠、不留空洞**）。
- **P90 刻度** = 24 个小时桶订单量分布的 **90 分位**（数据驱动的刻度）。

两者**是两回事**，页面上分开显示，不合并成一个数。

### 🔴 一份事实，两种切法

事实只落一份（订单 × 店铺 + 本地日期 + 本地小时 + 本地星期几）；
「按小时」「按星期」在**查询期**聚合出来 ⇒ 不按两种切法各投影一份事实（那是「一个数两个真相」）。

### 🔴 时钟域：落库时刻用 PG 的 `now()`

本仓已有**三次**同类跨钟域事故（identity 审批待办 / warehouse 货权档 / trade 拆单）。
`?::timestamptz` 绑定 OffsetDateTime，写时间的测试**断言写回的值**。

## 本车道修掉的 3 个真实缺陷（不是测试期望算错，是实现错）

1. **迁移触发器的 DELETE 分支拿「被删的行」和「新行」比** ⇒ 整体替换时段配置**永远被拒**。
2. **`seedSlots` 会把初始四段时段插回租户的自定义划分** ⇒ 租户自己配的时段被污染。
3. **`UTC_SLOT` 口径把「定不了时区」的订单重复计入格子**（与「无法唯一归属」那块**双计**）⇒ 破坏模块声明的不变量。

另修 3 处**测试期望算错**（夹具漏数、星期算错、阈值该传 0..1 比例而不是 9）。

## 白名单安全网（本次共享文件改动，单独核对过）

`apps/web/src/lib/api-proxy.ts` 新增**一条独立 `services[]` 元素**（不往既有 reporting 正则里并分支），
插入锚点在 `store-site` 那条之后（与别的车道用的「数组末尾」锚点相距 12000 行以上 ⇒ git 能干净求并）。

- **零命中核对**（脚本实测，非目测）：把全部 **246 条**既有元素的 prefix + 正则按 `resolve()`
  的同构口径逐条实测 ⇒ 本模块 7 个形状**零命中**。
- **反向核对**：另外 **44 条**别的车道已交付的 reporting 形状**全部仍然命中**（不会被遮蔽）。
- **刻意不放行**：后端不存在的映射（`/heatmap`、`/peak-ranking`、`/{uuid}` 顶层）**故意 404** ——
  放行后端不存在的路径比 404 更糟。
- `services[]` 元素数 **249 → 250 → 251（本车道只 +1）**。
- **全量 allowlist 回归 263 文件 / 1487 例绿**（改了共享白名单就必须跑）。

## 契约：纯增量，且**其余八个服务的churn已回退**

第一次跑 codegen 时八个无关服务的契约文件全变了（8545 行）⇒ 实测是**属性键序漂移**，
原因是那八个 spec 是从**已排序的快照**回灌的，而仓库里的 `.ts` 是按**实时序**渲染的。
canonical 生成读**实时 spec**，所以那八个服务的churn是回灌产物 ⇒ **已全部回退**，
只保留 reporting 的**纯增量**（17 schema / 7 path / **零删除**）。

## 后续待办

1. 卷 13 子页面仍没有导航入口（batch-105 遗留）—— 四个已交付的分析页互相不可达。
   backlog：`docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`。
2. `UTC_SLOT` 口径目前只是**可选项**，没有做成「按店铺本地时区分别出图」的多视图并列。
3. 时段配置目前按**租户**维度；未来若要按店铺维度差异化配置，需要拆表。
