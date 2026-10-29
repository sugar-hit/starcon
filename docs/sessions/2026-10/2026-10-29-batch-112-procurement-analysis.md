# 2026-10-29 · batch-112 · 采购分析（13.5.2.1）

> 本批为**并行车道批次**：`lane.mjs plan --lanes 2` 派发 → 2 条车道并行开发 →
> `lane.mjs merge-ready` 并入集成头 `integration/module-dev` → 集成头上跑**一次** L2 批量验证 → 本说明随台账同一个 commit 推送。
> 本文件是**批次总说明**；模块的完整设计决策与验证细节见该模块自己的会话日志（见节末「模块详细记录」）。

- 集成头：`integration/module-dev`，批次并入 commit `cfa76762`（13.5.2.1 采购分析）
- 车道：`feat/13-5-2-1`（V405，reporting 服务）
- 静态预检（已知互相破坏形状）：**无命中** —— 本批新迁移 `V405__procurement_analysis.sql`
  无 `ADD COLUMN` 无 `DEFAULT`、无 `SET NOT NULL`、无新增 `CHECK` 约束；
  唯一的 `ALTER TABLE` 是 DO 块里给新表开 `ENABLE/FORCE ROW LEVEL SECURITY`（标准 RLS 写法）
- 台账：已交付 428 → **429**，未开发 37 → **36**
- 本轮是**降档模式**：当前时刻已超过 `.dev-agent/PIPELINE.local.md` 的 `TOKEN_HORIZON`（2026-10-25T00:00:00+0800）
  ⇒ 每批 `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**。

---

## 模块 13.5.2.1 采购分析

**这个功能做什么**：从**采购与供应商**的授权只读 API 拉事实，落成本服务的**分析投影**，算出
**采购金额趋势与占比、供应商集中度（+ 断供风险信号）、价格波动、到货准时率、采购周期、采购成本占销售额比**，
并给出**供应商对比**与**SKU 级下钻**。

供应商集中度不是中性百分比 —— 它回答的是「**这家店断供时会不会直接被打穿**」：
单一供应商占比过高是一个**风险信号**，响应里直接给 HIGH / MEDIUM / LOW 等级、点名是哪一家、占比多少、
以及断供时哪一档采购额会中断。

**技术落地**：

- 迁移：`backend/services/reporting/src/main/resources/db/migration/V405__procurement_analysis.sql`
  —— 落成本服务**自己的分析投影**（四张投影/事实表 + 一张阈值规则表），
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + GRANT，`starcon_app` 零权限；
  **零 ALTER 任何既有表** ⇒ 跨车道 DDL 回归面 = 0。
- 端点：`/reporting/procurement-analysis` × **12**
  | 端点 | 负责什么 |
  | --- | --- |
  | `GET /capabilities` | 能力声明：哪些上游真接通、哪些确实拿不到（归属模块号 + 影响） |
  | `GET /trend` | 采购金额趋势与占比 |
  | `GET /concentration` | 供应商集中度 + 风险等级 + 风险说明 |
  | `GET /price-variance` | 价格波动 |
  | `GET /on-time` | 到货准时率（**只搬运** trade 6.3.2.2 的判定，不重算） |
  | `GET /purchase-cycle` | 采购周期（**到货日 − 下单日**，不是承诺交期） |
  | `GET /cost-to-sales` | 采购成本占销售额比 |
  | `GET /suppliers` | 供应商对比 |
  | `GET /drilldown` | SKU 级下钻 |
  | `GET /rules` | 阈值规则（GET） |
  | `POST /refresh` | 刷新投影（幂等 upsert，写的是投影不是业务事实） |
- 页面：`/analytics/procurement/analysis`
  —— `apps/web/src/app/analytics/procurement/analysis/page.tsx` +
  `apps/web/src/components/analytics/procurement-analysis-{model,workspace}.tsx`。
  筛选维度 / 趋势 / 对比 / 下钻 / 阈值规则编辑 / 刷新动作齐备；
  **加载态、错误态、空态三态齐全**；四种 `null`（比率不可比 / 无金额 / 无到货记录 / 未评级）
  在界面上渲染成 **「—（暂无数据）」+ 中性配色**，**不用警告色** ——
  「这一格没数据」是中性事实，不是异常。
- 测试：`ProcurementAnalysisIntegrationTest` **29 例**（业务口径）+
  `ProcurementAnalysisTenancyIntegrationTest` **3 例**（多租户三条断言）
  + `procurement-analysis-model.test.ts` **26 例** + `procurement-analysis-allowlist.test.ts` **6 例**。
  **L1 模块闸实跑 = 32 tests / 0 failures / 0 errors**；前端 32 例绿；
  全量 allowlist 回归 **270 files / 1527 tests 绿**（改了共享白名单 `api-proxy.ts`）。

**关键设计决策**：

1. **上游归属以「已交付现实」为准，不以任务书的猜测为准** ——
   任务书写「供应商主数据在 catalog」，实测**供应商评级在 trade**（`/trade/supplier-ratings/ratings`，V266）、
   **到货流水在 trade**（`/trade/purchase-analytics/facts`，V251），
   而供应商列表 / 账期资质在 catalog（V137 / V142）⇒ 按实测接线。
   （台账里 6.1.1.2 那一行已经记过同类修正。）
2. **两套金额口径必须互斥，否则同一笔采购会被算两次** ——
   trade 的到货流水（**每次到货一行**）与 catalog 的到货记录（**也带金额**）都能算出采购金额。
   ⇒ **金额全部取 trade 一处**；catalog 的到货记录**只用于采购周期**
   （它是全仓唯一同时有「下单日」与「到货日」的事实源）。
   **落地方式不是注释提醒，而是表结构**：`reporting_procurement_analysis_delivery_fact`
   **刻意没有金额 / 数量 / 币种三列** ⇒ 「同一笔采购被算两次」这件事**在结构上不可能发生**。
3. **集中度给的是风险信号，不是一个中性的百分比** ——
   `HIGH` = 单一供应商占比 ≥ 单家阈值（默认 40%）；
   `MEDIUM` = 前 N 家（默认 3）合计 ≥ 合计阈值（默认 60%）；其余 `LOW`。
   HHI 也算了（0~10000）但**只作参考指数、不参与定级** ——
   4 家 30/30/20/20 的 HHI 是 2600，可最大供应商只有 30%，
   叫它「高风险」比叫「中风险」更离谱 ⇒ 定级只由**租户自己可配的阈值**决定。
4. **销售额分母引用 13.1.1.1 的投影，不重算** ——
   取 `reporting_metric_fact` 的 `SALES_AMOUNT` / `scope_type='COMPANY'`
   （卷 13 卷首「**口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算」）。
   该投影没跑时比率是 **null（不可比，不是 0%）**，并报出归属模块号与影响。
   *否决方案*：自己投影一份销售额，模块自洽了，但那是**第二个真相**。
5. **「算不出来」与「等于 0」分开（四条贯穿全模块）** ——
   ① 分母为 0 ⇒ null（不是 0%）；② 没有金额的到货流水是「—」，不进任何合计（不是 0 元），
   并在趋势里单独计数；③ 没有到货记录 ⇒ 采购周期不可比（不是 0 天）；
   ④ 没有评级 ⇒ 评级列是「未评级」（不是 0 分 —— 把「没评过」写成 0 分等于替上游断言「这家很差」）。
6. **准时与周期的口径归属不同，各写各的** ——
   准时率判定归 **trade 6.3.2.2**，本模块**只搬运不重算**，分母 = **已到货**流水
   （PENDING 未到货不进分母，另计）；
   采购周期 = **到货日 − 下单日**（承诺交期不是实际周期，两者不能混，响应里 `caliberNote` 写明）。
   采购金额**含 PENDING**（已承诺待到货也是采购承诺额），但 PENDING **不进准时率分母**。
7. **工程上两个坑已写进代码注释** ——
   jOOQ 解析器不吃 `ON CONFLICT DO UPDATE` 里引用目标表的 `case when`（报 `bad SQL grammar`），
   改成**先读后写**；`timestamptz` 列必须写 `?::timestamptz` 并绑 `OffsetDateTime`，
   不写显式 cast 时 PG 按 varchar 送过去 ⇒ 落库直接炸。

**模块详细记录**：`docs/sessions/2026-10/2026-10-29-procurement-analysis.md`

---

## 本批 L2 批量验证

L2 在集成头 `cfa76762` 上跑**一次**全量：
`./mvnw verify -fae` → `pnpm typecheck --force` → `pnpm lint` → `pnpm test` →
`NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `sync.mjs --check`。

> 本轮用 `-fae`（fail at end）而不是默认 fail-fast ——
> round-129 用 fail-fast，channel 一红就停下，**reporting / trade / warehouse 根本没跑**，
> 13.5.1.1 落地的 reporting 模块**没被验证过**。`-fae` 一次跑完 11 个模块并给出完整失败清单。

### 上轮 L2 的红与本轮的判定

round-129（2026-10-29 08:04）的 L2 在 channel 红 1 例：

```
ListingPublishJobIntegrationTest.endpointsRoundTrip:926 Status expected:<200> but was:<503>
Tests run: 2436, Failures: 1, Errors: 0
```

取证结论（不是猜的）：503 来自 `AccessService.require()` 的 catch ⇒ **identity 上游不可达**；
该测试的 identity 桩是**进程内 `com.sun.net.httpserver.HttpServer`**，
`HttpServer.create(addr, 0)` 且**从不 setExecutor** ⇒ JDK 默认**单线程** dispatcher，
客户端 keep-alive 连接残留会把下一个请求堵死 ⇒ 超时 ⇒ 503。
**同一个缺陷在 round-119 换了个受害模块重演**（那次是 catalog `SpuLifecycleIntegrationTest` 的 404/503，
而 catalog 本轮 1999 例全绿）⇒ **同一个缺陷在两轮里换模块漂移 = 随机 / 负载依赖**，
而该测试类自己的注释就写着「全量跑时必假红」。
⇒ 判定为**负载型假红**，不是跨车道互相破坏 ⇒ 按协议先重跑、**不做二分**。

本轮 `-fae` 全量复跑的结果：**绿**。

### 本轮 L2 实测结果（`cfa76762`，2026-10-29 11:15:03 → 12:22:18）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `./mvnw verify -fae` | **MVN_EXIT=0**，11 个模块全部构建通过（backend → tenancy → identity → catalog → channel → warehouse → trade → crm → finance → reporting → platform） |
| 后端测试 | — | **Tests run: 11,222 · Failures: 0 · Errors: 0 · Skipped: 0**（identity 644 / catalog 1999 / channel 2436 / warehouse 1672 / trade 2460 / crm 491 / finance 970 / reporting 453 / platform 97） |
| 类型 | `pnpm typecheck --force` | **EXIT=0** |
| Lint | `pnpm lint` | **EXIT=0** |
| 前端测试 | `pnpm test` | **679 files / 9589 tests 全部通过** |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **EXIT=0** |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | **EXIT=0** —— 「✓ 台账与蓝图一致」 |

**本批新模块在 L2 里的实测**：
`ProcurementAnalysisIntegrationTest` **29 例全绿** + `ProcurementAnalysisTenancyIntegrationTest` **3 例全绿**；
上一批的 `InventoryAnalysisIntegrationTest` 16 例 + `InventoryAnalysisTenancyIntegrationTest` 3 例也一并全绿
（⇒ round-129 用 fail-fast 时「reporting 根本没跑、13.5.1.1 没被验证过」的那个洞，本轮被 `-fae` 补上了）。

**round-129 的 503 假红本轮未复现** ⇒ 印证了「负载型假红」的判定，**不做二分**。

### 温度与资源观测

| 项 | 观测值 | 判定 |
| --- | --- | --- |
| `pmset -g therm` | `No thermal warning level has been recorded` | ✅ 无降频 |
| load（峰值 / 结束） | 10.45 / 8.51（阈值 `LOAD_LIMIT=12`） | ✅ 未越限 |
| Testcontainers PG 容器 | 运行后 **0** 个带标签、**0** 个孤儿容器 | ✅ 已由 ryuk 回收干净 |
| PG 容器峰值 | **59**（阈值 `PG_CONTAINER_LIMIT=6`） | ⚠️ **超限，见下方风险** |

⚠️ **PG 峰值 59 远超阈值 6** —— 成因是**本轮 L2 的 surefire 并行 fork 与 2 条在途车道的 L1 测试叠加**
（Testcontainers 每个测试类起一个 PG16 + ryuk sidecar）。该阈值是**派发闸**、不是运行期闸，
所以不影响本轮的判定（派发时容器基数为 0，闸是过的），但**并发峰值的量级需要报给用户**：
若下一轮并发车道数上调，PG 峰值会同比放大。**本轮不自行调整阈值** —— 那是用户的决定。

---

## 本轮在途车道（下一轮 tick 回收）

| 车道 | 模块 | 服务 | 迁移版本 | 状态 |
| --- | --- | --- | --- | --- |
| `feat/14-3-2-1` | 14.3.2.1 归因分析 | channel | V404 | **已落地（`ready`）**，HEAD `8bea14ff` |
| `feat/13-5-3-1` | 13.5.3.1 物流分析 | reporting | V406 | **已落地（`ready`）**，HEAD `0a0fe767` |

⚠️ 两条车道都在本轮 L2 **跑完之后**才落地 ⇒ **本轮不并入**（并入后集成头就与刚刚验证过的 HEAD 不一致了）。
按协议，下一轮 tick 的 `merge-ready` 会把这两条并入并跑下一批 L2。

**两条车道的 `api-proxy.ts` 改动已预核对**：`14.3.2.1` 纯新增 41 行（channel 簇，锚点 L3092 附近）、
`13.5.3.1` 纯新增 45 行（reporting 簇，锚点 L706 附近）⇒ **0 删除**，两簇相距 2000+ 行且区段不重叠，
下一轮并入应能干净求并。

⚠️ **14.3.2.1 是本日的第三次重派**：08:01 那轮 worker 在写迁移时失联（只留下 341 行的
`V404__ad_attribution.sql` 未跟踪文件）；11:09 那轮 worker 已跑完依赖自检、读完蓝图与兄弟模块实现，
但**会话在网络层断开**（`ERR_NETWORK_IO_SUSPENDED`）而中断，未写任何代码。
调度器已把该车道分支基线重置到当前集成分支 `cfa76762` 后再次派出。
⇒ **记一笔：本次失败是子任务会话层面的网络中断，不是代码缺陷，也不是车道编排逻辑问题。**

**两条车道的自检结论（供下一轮并入时参考）**：

- **14.3.2.1 归因分析** —— 迁移 `V404__ad_attribution.sql`（5 表，**零 ALTER 既有表**）；
  端点 `/channel/ad-attributions/**` 11 个；页面 `/ads/ad-attributions`；
  测试 21 + 3 + 32 + 5；L1 **24 例全绿**，前端 allowlist 全量回归 **83 files / 445 例绿**；
  契约 channel **+11 path / +23 schema / 0 删除**；4 个 atomic commit，HEAD `8bea14ff`。
  关键设计：**事实表唯一键含 `rule_version`** ⇒ 改规则后重跑产生新版本事实、旧版本原样保留
  ⇒ 蓝图加粗句「规则变了历史报表不可比」不会被自己破坏。
- **13.5.3.1 物流分析** —— 迁移 `V406__logistics_analysis.sql`（8 投影表 + 1 阈值规则表，**零 ALTER**）；
  端点 `/reporting/logistics-analysis/**` 13 个；页面 `/analytics/logistics/analysis`；
  测试 15 + 3 + 17；L1 **18 例全绿**，前端 allowlist 全量回归 **71 files / 394 例绿**；
  契约 13 path + 27 DTO 纯新增。关键设计：头程分摊**不重算**（读 10.6.1.1 的分摊结果），
  单均运费分母用**订单数**（与 channel 的「票」口径并排对照）。

---

## 遗留与后续（不在本批职责内，如实记录）

1. **`reporting.*` 权限码未在 identity 登记** —— 全仓 grep 无命中 ⇒ `reporting.sku-profit.read` 在真实用户下无法被授予。
   **这是 reporting 服务族的既有缺口**（14.3.1.3 / 13.5.1.1 已如实记录），需 identity 侧补注册。
2. **采购成本占销售额比的下游** —— 13.4 利润模块可读 `GET /reporting/procurement-analysis/cost-to-sales`
   （蓝图 6.5.2.1 关系句：「采购成本占比是利润健康度的先行指标」），等 13.4 侧接线。
3. **「已到货口径」的采购成本** —— 目前采购金额含 PENDING，只在 `caliberNote` 里写明。
   若下游 13.4 需要已到货口径，**应在本模块加显式口径位并让下游引用**，
   而不是让下游自己减 —— 那会让两个口径并存。
4. **trade 6.5.2.1 的采购事实台账要先同步**（`POST /trade/purchase-analytics/facts/sync`）才有到货流水；
   本模块的 `POST /reporting/procurement-analysis/refresh` 只做**投影**、**不代跑**上游的同步 ——
   能力声明里会报出「上游事实台账为空」这一事实。
5. **13.5.3.1 物流分析**（同一 L3 的下一格）可复用本模块的
   「逐币种分段 + 风险信号 + 阈值规则表」这套骨架。
