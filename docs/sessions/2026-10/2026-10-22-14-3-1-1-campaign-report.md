# 14.3.1.1 活动报表（阶段 D7）

- 日期：2026-10-22
- 车道：14.3.1.1（worktree `integration/.worktrees/14.3.1.1`，分支 `feat/14-3-1-1`）
- 蓝图来源：`docs/product/capability-blueprint/14-ads-and-growth.md` 第 157–162 行

## 决策摘要

**模块**：14.3.1.1 活动报表 —— 活动维度效果报表（花费、曝光、点击、CTR、CPC、订单、转化率、
销售额、ACOS/ROAS/TACOS），按日/周/月与多维对比，支持导出。输出 = 报表表 + 趋势图 + 口径说明。
上游 = 14.1.1.1（已交付），下游 = 14.3.2.1（未交付）。

**交付物**

| 类别 | 位置 |
| --- | --- |
| 迁移 | `backend/services/channel/src/main/resources/db/migration/V279__ad_campaign_reports.sql` |
| 后端 | `AdCampaignReportModels.java` / `AdCampaignReportService.java` / `AdCampaignReportController.java`（`backend/services/channel/src/main/java/com/starcon/channel/`） |
| 端点 | `GET /channel/ad-reports/{capabilities,options,rows,trend,export,exports}` |
| 页面 | `apps/web/src/app/ads/reports/page.tsx` + `apps/web/src/components/ads/ad-campaign-reports.tsx` + `ad-campaign-report-model.ts` |
| 测试 | `AdCampaignReportIntegrationTest.java`（18 例）+ `ad-campaign-report-model.test.ts`（22 例）+ `ad-report-allowlist.test.ts`（5 例） |

**事实表直接用上游的**：`ad_campaign_daily_metric`（V273）与 `ad_campaign_sync_run`（V273）。
本模块**不复制**任何一列指标，也**不建**第二套事实表，也不建视图 —— 建视图会让「先汇总再相除」
变成第二套要维护的口径。V279 只新建一张 `ad_campaign_report_export`（导出留痕）。

## 关键 trade-off

### 1. TACOS：没有分母，就不出数值（而不是造一个）

TACOS = 广告花费 / **总销售额**。总销售额来自 13.4 利润模块，而卷 13 的主责服务 reporting
**不在集成头**（`plan` 的 serviceBlocked 已点名）⇒ TACOS 现在**没有分母**。

三条被排除的做法与原因：

- 不用 `0` 当分母 → 那会得到无穷大；
- 不用**广告销售额**顶替总销售额 → 那算出来的是 ACOS 不是 TACOS，且会被当成事实传下去；
- 不在 V279 里建一张「总销售额影子表」凑分母 → 那会把一个假口径固化成事实。

落地：走本仓既有的能力声明口径，TACOS 显式 `available=false` + `ownerModule` 指到 13.4 +
`impact` 说清「总销售额口径未接入，当前不出 TACOS 数值」。**报表行不给 TACOS 字段** ——
留一个恒为 null 的字段会让调用方误以为「取到了，只是恰好是空」。界面上按
「未接入（原因）」渲染。

### 2. 跨币种：按币种拆开，而不是合并

上游 14.1.1.1 的台账已写明「跨平台单位统一不在本模块（属 14.3.1.1 报表口径）」—— 本模块就是
那个口径的落点。但本仓**没有汇率源**（无汇率表、无汇率同步器）。

取舍：**不做**跨币种换算，改为**分组键里始终带币种、汇总按币种拆成多份**。于是「Amazon 的 USD
消耗」与「Amazon 的 EUR 消耗」永远是两行，不会被加成一个假总数。代价是跨币种横向比较暂时做不了 ——
这一条在 capabilities 里显式 `available=false`（责任模块 = 汇率源，未交付），不装作有。

### 3. 聚合口径：先汇总再相除

上游 `ad_campaign_daily_metric` 上有 `ctr` / `acos` / `roas` 三个 **GENERATED 列**，那是**单行**口径；
报表是**多行聚合**口径。**对生成列求平均是数学错误**：两条 ACOS 100% 与 50% 的活动平均成 75%，
而整体其实是 27.5%（Σ消耗/Σ销售额）—— 会让运营以为整体在盈亏线上，它其实在亏。

落地：服务层聚合 SQL 一律 `sum()` 之后再相除，并在集成测试里用这两组数把「平均口径」与
「汇总口径」的差钉死。

### 4. 性能纪律：时间范围必填 + 桶数封顶 + 真分页 + 汇总单独一条查询

蓝图加粗句「关键词级数据量极大，查询必须分页与缓存，不能实时全表扫」。本模块是活动维度，
量级小一些，但日/周/月 × 多维对比一样容易变成全表扫。

取舍与做法：

- **时间范围必填**（不给区间直接 400，不给「默认区间」）—— 「默认最近 30 天」这种省事选项
  会让运营以为自己看的是全量；
- **按粒度封顶 400 个桶**（DAY 400 天 / WEEK 400 周 / MONTH 400 月），超了报错，
  **不做悄悄截断**（悄悄截断 = 报表少算了一段却没人知道）；
- 所有聚合走 jOOQ 显式 SQL，谓词顺序对齐上游索引
  `ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)`：先 `tenant_id`、
  再 `metric_date` 范围；
- **真分页**（LIMIT/OFFSET）+ **汇总单独一条聚合查询**（统计筛选后的全集，不是当前页）；
- 真正的**物化预聚合缓存**留给关键词级（14.3.1.2）那种量级 —— 本模块靠上面四条保证不实时全表扫，
  `report_cache` 维度显式 `available=false` 并把「已交付的那部分」写进 `impact`。

### 5. 导出：口径写进表头 + 留痕

广告花费与销售额直接进财务与归因，导出一次就是一份可以外传的账。所以：

- CSV 的**列名里带公式**（`ACOS(Σ花费/Σ销售额)`），拿去做跨部门沟通时 CSV 自己就说明了
  「ACOS 是哪个除哪个」；
- 不可比的比率写「不可比」（不是 0、不是空）；TACOS 写「不可用（总销售额口径未接入）」；
- 每次导出**留痕**（`ad_campaign_report_export`：谁、什么时候、什么筛选条件、多少行、对着哪个水位）。
  留痕里存的是**筛选条件**而不是报表数据快照 —— 报表随时可按同样条件重算，而数据快照会在下一次
  同步后与「当时的报表」对不上，留一份反而是假事实。

### 6. api-proxy 白名单：新增独立元素，不并入既有 alternation

`apps/web/src/lib/api-proxy.ts` 是多车道共用的累积型白名单，每条服务规则是一段超长单行正则。
往同一条 alternation 里并分支会让多车道同时并入时 git 必然判冲突（结构性，retry 不会自愈）。

- 新开一条独立 `services[]` 元素，**插入锚点固定在 `prefix: 'warehouse/'` 那条元素之前**
  （该元素本体从文件 656 行开始，`prefix` 在 850 行附近），与同批其它车道相距 150 行以上；
- **首 token 零命中核对**：本文件 100+ 条既有正则里**没有任何一条**含 `ad-reports`
  （既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` 一类）；
- 本模块 6 个形状**都没有 id 段**，因此正则里**不写 uuid 段**（写了反而会把 `capabilities`
  这类字面量子路径当成 id 放行）；将来若加 `{id}` 形状，必须用完整 UUID 形状
  `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 且字面量子路径排在前面；
- 刻意**不放行**：裸路径 `ad-reports`、`ad-reports/tacos`（TACOS 没有分母，放行它界面会以为有值）、
  `ad-reports/keywords` / `ad-reports/products`（14.3.1.2 / 14.3.1.3 未交付）、
  `ad-reports/rows/{id}`（报表是聚合的，没有单行详情）。

## 踩到并修掉的问题

1. **平台 id 抄错**：测试里沿用上游的 `11111111-...-111`，但 V1 的平台目录里那个 id 是 **ETSY**
   不是 Amazon ⇒ 「按平台分组」拿到的是 "Etsy"。改用 V1 里真实的 Amazon / TikTok id。
   （这说明「抄上游测试的种子常量」也得核对一眼 V1 的种子数据。）
2. **`List.of(` 少一个右括号**：能力声明里 11 个 `new AdReportCapabilityDimension(...)` 之后
   外层 `List.of(` 没闭合，编译期报「需要 ')' 或 ','」。
3. **`exactOptionalPropertyTypes`**：导出链接的查询对象里每个键都可能是 `undefined`，
   可选属性不接受显式 `undefined` ⇒ 单独定义了 `AdCampaignReportExportQuery`（`?: string | undefined`）。
4. **契约再生成带出与本车道无关的既有漂移**：`packages/api-contracts/src/trade.*` 的 diff 全是
   trade 侧早就存在但从未再生成过的模块（MassProduction / PlatformAppeal 等）。已
   `git checkout` 回退，**只提交 channel 侧**（channel 侧纯新增 6 path / 14 schema / 0 删除，
   另有 6 个 schema 只是 `minimum: 0` → `minimum: 0.0` 的序列化噪声）。

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` → 通过；`pnpm typecheck` → 3 包绿；
  `apps/web` 内 `npx tsc --noEmit` 单独复跑 → exit 0。
- L1 模块闸：
  `./mvnw -pl services/channel -am test -Dtest='AdCampaignReport*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 18, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS。
- 前端：`pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade apps/web/src/components/ads`
  → **121 个文件 / 725 例全绿**（其中本模块新增 2 文件 / 27 例）。
- `pnpm lint` → 绿（仅既有文件的 react-hooks warning，与本车道无关）。
- Prettier 已对本车道 5 个文件 `--write`（`api-proxy.ts` 本来就合规，未变）。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → 重新生成。

## 后续待办

- **TACOS**：13.4 利润模块（卷 13 的主责服务 reporting）落地后，把总销售额口径接进来，
  届时 `metricDefinitions.tacos.available` 翻 true，报表行加 `tacos` 列。
- **跨币种统一**：接上汇率源之后，才谈得上把不同币种换算成同一货币再汇总。
- **关键词级 / 商品维度报表**：14.3.1.2 / 14.3.1.3，量级大得多，物化预聚合缓存的活在那两条车道。
- **归因分析**：14.3.2.1，可直接消费本模块的 `rows` / `trend` 形状。
