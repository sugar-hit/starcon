# 2026-10-26: 1.1.2.2 店铺概览（卷 01 工作台与门户 L3 1.1.2 关键指标第二格）

## 摘要

蓝图的「按店铺横向对比当期表现（销售额、订单量、利润率、库存天数、广告花费），识别掉队店铺」——
落成 **reporting 服务**上的一个**只读投影读模型**（不是新起服务、不是新起表）。

**迁移：无（不占用 Flyway 版本号）。** 卷 01 卷首写死「不产生新的业务事实，只聚合各系统的投影」
「工作台只读 + 跳转」；本模块读的就是 13.1.1.2 多店对比**已经投影好的**店铺维度事实，
派生值落库只会比它描述的经营数字更早过期 ⇒ 不新建任何表
（reporting 服务本车道只有既有的 V376 / V379 / V381）。

> **本车道是续跑**：上一轮 worker 在**前端构建阶段**因传输层崩溃
> （`net::ERR_HTTP2_PING_FAILED`，2026-10-26 16:45）挂掉，当时写的 6 个后端文件还是
> untracked。调度者为保全把它们**原样 commit**（`96772517`，**内容零改动**）。
> 本轮续跑的职责是：**独立复跑 L1 确认存量成果是绿的**（不能默认它是对的），
> 再补完前端 + 契约 + 台账 + 会话日志。

## 决策 / 结论

1. **🔴 五个维度逐条不同，不能一把尺子量完 —— 这是本模块的核心验收项。**
   | 维度 | 店铺级口径码 | available | ownerModule |
   | --- | --- | --- | --- |
   | 销售额 | `STORE_GMV_PLATFORM` / `STORE_GMV_RESTORED` / `STORE_CALIBER_GAP`（**三个并列**） | ✅ | 9.1.1.1 平台单列表（trade） |
   | 订单量 | `STORE_ORDER_COUNT` | ✅ | 9.1.1.1 平台单列表（trade） |
   | 利润率 | **无** | 🔴 false | 13.2 利润分析（未交付）/ 12.4.1.2 成本计算（finance） |
   | 库存天数 | **无** | 🔴 false | 7.1.1.1 库存总览（warehouse）/ 13.5.1.1 库存分析（未交付） |
   | 广告花费 | `STORE_AD_SPEND` | ✅ | 14.3.1.1 活动报表（channel） |

2. **🔴 「销售额」是三个口径的并列，不是一个数。**
   13.1.1.2 的店铺口径表里**没有**一个叫「销售额」的码 —— 它登记的是**两份成交金额口径**
   （平台侧 / 我方还原）外加两者的**口径差异额**。目录既然专门留了这个派生码，
   就是要求把口径差异摆出来而不是藏起来 ⇒ 页面**三者并列呈现**。
   只取其一当「销售额」= 抹掉口径差异 = 破坏口径唯一。
   **前端用结构保证**：{@link salesLines} 对每个币种**恒定返回三条**（缺一条就是它自己坏了），
   且口径差异额是后端按 `DERIVED_DIFF` 算好的 —— 前端**一个减法都不做**。

3. **🔴 接不通的维度「不给公式也不给分母」，而且必须写明归属模块号。**
   利润率与库存天数在店铺口径表里**没有店铺级指标码**；上游成本分录（12.4.1.2）与利润表
   （12.5.2.2）**只有法人 / 公司维度**，在手库存（7.1.1.1）按 SKU × 法人 × 仓组织 ——
   把公司维度的利润率挂到店铺上、把仓维度的库存除以销量，都是**在代码里新写一条口径公式**
   （第二个真相）。给了公式页面上的数字就变成「看起来有依据的假数」。
   ⇒ 页面显示「**未接通 + 归属模块号 + 为什么**」，让人知道**去哪维护**，而不是给一个 0。

4. **🔴 租户级 `SALES_AMOUNT` / `GROSS_MARGIN` 绝对不许拿来冒充店铺级。**
   那是 1.1.2.1 核心指标卡的**租户**口径（`reporting_metric_definition`），
   与本模块的**店铺**口径（`reporting_store_metric_definition`）是两张表、两套维度。

5. **🔴 权限码沿用 `reporting.store-comparison.read`，不另起一个。**
   本模块读的就是 13.1.1.2 那套店铺维度读模型（同一批表、同一套口径、同一份 RLS）；
   另起一个码会让「谁能看店铺经营数据」出现两个答案，而新码还要去 identity 登记
   （那需要另一个服务的迁移版本号，本车道没有分配）。

6. **🔴 「算不出来」与「等于 0」在两个层面分开。**
   ① 口径已接通但这个店铺这个窗口没有事实 ⇒ `NO_DATA` + `null`，页面显示「—」，
   **不是 0**；② 两个口径**一致**时口径差异额**真的等于 0**（不是「无数据」）——
   这两件事必须能区分开，测试里各有一条断言钉住。

7. **🔴 窗口边界按本租户时区算，不写死 UTC。**
   「今日 / 昨日 / 本月」的边界取自本租户店铺投影事实的**分桶时区**；取不到时退化为
   `FALLBACK_UTC` 并在响应里说清楚。集成测试把固定时钟放在 UTC 的 10-25 与
   Asia/Shanghai 的 10-26 的**不同日期**上 ⇒ 任何写死 `timezone='UTC'` 的实现会直接红。

8. **🔴 多币种绝不相加。**
   每个币种各占一行，界面上**永远没有跨币种的「总计」**。排序按平台侧成交金额规模，
   但**排名在币种内做** —— 一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。

9. **🔴 前端展示层走十进制字面串 + BigInt 缩放整数，不走浮点运算。**
   金额是 `numeric(19,4)`，而 JS 的 `number` 是二进制浮点。所以前端**不做任何算术**，
   展示时把 number 还原成**十进制字面串**，用 BigInt 在缩放整数上做 HALF_UP 四舍五入与
   千分位分组。实测：`1.005` ⇒ `1.01`（按 double 四舍五入会得到 `1.00`）。

10. **🔴 api-proxy 必须新增一条独立元素。**
    发车前用脚本（`.tmp/api-proxy-zero-hit.mjs`，不入库）把基线**全部 231 条**既有
    `services[]` 元素逐条按 `resolve()` 的**同构口径**
    （`path.startsWith(prefix)` 收窄 → 再测 `pattern.test(path.slice(prefix.length))`）实测：
    本模块 6 个形状**全部零命中**；既有 `prefix === 'reporting/'` 恰好 **4 条**，
    正则都是**按命名空间精确匹配**的
    （`overview|projection` / `expense-variance` / `store-comparison` / `metric-cards`），
    **不覆盖 `store-overview`** ⇒ 必须新增独立条目，不能并进既有分支。
    同一支脚本**反向**核对 **23 条别的车道已交付的形状全部仍然命中** ⇒ 不误伤。
    实测改动量：**纯新增 49 行、零删除、零重排**，插入锚点选在那四条之后
    （与同批其它车道的插入点相隔很远，并入时 git 能干净求并）。

11. **🔴 刻意不放行的形状**：`store-overview/{uuid}`（顶层没有 id 段）、
    `store-overview/refresh`（**本模块没有写端点**，卷 01 只读；店铺维度投影的刷新是
    13.1.1.2 的职责）、`store-overview/unknown-sub`。放行后端不存在的映射比 404 更糟 ——
    界面会以为功能已交付。

12. **🔴 下钻不编参数。**
    13 的店铺分析页（`/analytics/dashboard/store-comparison`）与该店铺订单页
    （`/orders/platform-sync`）**都已交付**，但这两页**目前都不接受按店铺预筛选的 URL 参数**
    ⇒ 点进去看到的是全量列表。本模块**不编一个不存在的深链参数**。

13. **工作台卡片注册表：登记自己那一条。**
    `metric.store-overview` → `/dashboard/store-overview`，只新增这一条，
    既有条目一字不动、不重排。连带同步 1.4.1.1 那个**累积型固件**里两处硬编码的卡片 key
    清单（注册表是累积型的，加一张卡必然让那两处清单变长 —— 不改它 CI 直接红）。
    注册表自己的纪律是「只登记**已交付、真的能打开**的页面」，本模块现在满足。

## 涉及文件

**后端（reporting 服务，本车道新增）**

- `backend/services/reporting/src/main/java/com/starcon/reporting/StoreOverviewCatalog.java` —— 五个维度的清单（系统事实）
- `.../StoreOverviewModels.java` —— 契约形状
- `.../StoreOverviewService.java` —— jOOQ 显式 SQL 只读聚合
- `.../StoreOverviewController.java` —— 3 个 GET 端点挂在 `/reporting/store-overview`
- `backend/services/reporting/src/test/java/com/starcon/reporting/StoreOverviewIntegrationTest.java`（11 例）
- `.../StoreOverviewTenancyIntegrationTest.java`（3 例，多租户三断言）
- **迁移：无（不占用 Flyway 版本号）**

**前端**

- `apps/web/src/app/dashboard/store-overview/page.tsx` —— 页面路由
- `apps/web/src/components/dashboard/store-overview-model.ts` —— 前端模型（取数 / 格式化 / 口径文案）
- `apps/web/src/components/dashboard/store-overview-model.test.ts`（33 例）
- `apps/web/src/components/dashboard/store-overview-workspace.tsx` —— 工作区组件（加载 / 错误 / 空三态）
- `apps/web/src/app/api/reporting/store-overview-allowlist.test.ts`（6 例，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条** `reporting/store-overview` 元素（+49 行，−0 行）
- `apps/web/src/components/dashboard/workbench-card-registry.ts` —— **只新增一条** `metric.store-overview`
- `apps/web/src/components/dashboard/workbench-layout-model.test.ts` —— 累积型固件的两处硬编码清单同步

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/reporting.ts` —— 新增 9 个 `StoreOverview*` DTO
- `packages/api-contracts/src/reporting.openapi.json`

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 1.1.2.2 那一行的「状态」「证据」两格
  （+ `node tools/module-progress/sync.mjs` 自动刷的派生计数：已交付 408 → 409、未开发 57 → 56、
  完成率 87.7% → 88.0%、D7 阶段表与卷 01 汇总表各 +1；**其余 464 个模块的行一字未动**，
  复跑 `sync.mjs --check` ⇒ 「✓ 台账与蓝图一致」）
- `docs/sessions/2026-10/2026-10-26-1-1-2-2-store-overview.md` —— 本文件

## 关键 trade-off / 讨论

1. **销售额给一个数还是三个？** 三个。取舍很明确：三个数在视觉上更"吵"，
   运营第一眼会觉得"怎么有三个销售额"。但口径表里**登记的就是三个** ——
   只显示一个，等于把口径差异（平台侧 ≠ 我方还原）读成经营差异，
   而那恰恰是这个模块要帮人识别的东西。

2. **未接通的维度给不给公式？** 不给，连分母也不给。
   两个"大概"选项都被否：库存量 ÷ 销量速度 = 在代码里新写一条公式（第二个真相）；
   用公司维度的利润率挂到店铺上 = 跨维度造假。宁可显示「未接通 + 归属模块号」。

3. **窗口边界写死 UTC 行不行？** 不行。跨境 ERP 的"今日"按店铺所在地算才有意义，
   而本租户的店铺投影事实里就带着分桶时区 ⇒ 直接用它，取不到时退化成 UTC 并**明说**。
   测试把固定时钟钉在两个时区的不同日期上，写死 UTC 会直接红。

4. **多币种要不要给个合计？** 不给。与 1.1.2.1 同一条纪律：没有汇率快照就不折算，
   缺任一汇率就是**不可判定**，不是按 1:1 算。

5. **掉队判定要不要在前端再算一遍环比？** 不要。环比增长率 ≤ −20% 的判定
   **完全同源**于 13.1.1.2 多店对比（口径归 13），前端只显示后端给的 `classification`
   与 `classificationNote`。前端重算就是第二套判定。

6. **台账那行要不要顺手改别的行？** 不改。台账规则是「只改自己那一行」。
   1.4.1.1 那一行里若还写着「1.1.2.2 尚未交付 ⇒ 不登记」，留给 1.4.1.1 的车道刷新
   （见「后续待办」）。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `backend/./mvnw -q compile` | **exit 0**，BUILD SUCCESS |
| **L1 模块闸（独立复跑）** | `./mvnw -pl services/reporting -am test -Dtest='StoreOverview*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 14, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS（`StoreOverviewIntegrationTest` 11 + `StoreOverviewTenancyIntegrationTest` 3，实跑 Testcontainers PostgreSQL 16） |
| 类型 | `pnpm typecheck` | **3 successful / 3 total** |
| Lint | `pnpm lint` | **3 successful / 3 total**（只有别的模块的既有 warning，本车道零告警） |
| 前端单测 | `pnpm vitest run apps/web/src/components/dashboard/store-overview-model.test.ts apps/web/src/app/api/reporting/store-overview-allowlist.test.ts` | **39 passed / 39**（2 files） |
| 前端组件 | `pnpm vitest run apps/web/src/components/dashboard/` | **14 files / 362 tests passed** |
| **白名单全量回归** | `pnpm vitest run apps/web/src/app/api` | **255 files / 1440 tests passed** —— 未误伤 13.1.1.1 / 13.4.2.2 / 13.1.1.2 / 1.1.2.1 的 reporting 路由 |
| 白名单零命中核对 | `node .tmp/api-proxy-zero-hit.mjs pre` / `post` | 插入前 231 条既有元素里本模块 6 个形状**全零命中**、23 条他车道形状**全命中**；插入后 232 条元素里本模块 3 个真实形状**命中**、3 个禁行形状**仍零命中**、23 条他车道形状**仍全命中** |

**Testcontainers 资源竞争**：本车道验证期间**未出现**任何 `Connection refused` / 503。
L1 跑完后 `docker ps` 里 `postgres:16-alpine` 容器数为 **0** ——
本车道自己起的那个已被 **ryuk 回收**（机器上只剩用户自己的 `postgres:15-alpine`
（另一个项目，已跑 6 天）与 `testcontainers/ryuk` 守护容器）。
按本轮纪律**只跑了自己那一个测试类**（`-Dtest='StoreOverview*'`），没有跑整个 reporting 服务，
所以没有容器堆积。

**上一轮 worker 的成果是绿的**：续跑后**独立复跑**了 L1（原 worker 自报 14 例全绿但调度者
没有复跑过）。结论一致 —— 14 例全绿，未改任何后端代码。

## 后续待办

1. **1.4.1.1 台账那行可能有一句过时的话**：若还写着「1.1.2.2 店铺概览尚未交付 ⇒ **不登记**」，
   本车道已交付并已登记 `metric.store-overview` ⇒ 该由 1.4.1.1 的车道刷新
   （本车道按规则没碰它那一行）。
2. **店铺维度的利润率**仍未接通。要接它，得由 **12 财务成本核算**先提供**带店铺维度**的成本事实，
   并在 13 的**店铺口径表**里登记指标码 —— **不是**在本模块里写一条公式。归属：12 / 13.2。
3. **店铺维度的库存天数**仍未接通。要接它，得由 **07 库存总览**先提供**带店铺维度**的在手库存，
   并在 13 的店铺口径表里登记指标码。归属：07 / 13.5.1.1。
4. **下钻只能到整页**：13 的店铺分析页与 09 的订单页**都不接受**按店铺预筛选的 URL 参数 ——
   要做成真正的深链，得由那两页各自加筛选参数。归属：13 / 09。
5. **「销售额」永远是三个口径**这件事在台账 1.1.2.1 那行可能需要提醒：
   两张卡的「销售额」**不是同一个口径**（租户级 vs 店铺级），别让人以为它们应该相等。

## 关联

- 蓝图：`docs/product/capability-blueprint/01-dashboard-and-portal.md`（1.1.2 关键指标 → 1.1.2.2 店铺概览）
- 上游口径单点定义：13.1.1.2 的 `reporting_store_metric_definition`
  （`reporting_store_metric_fact` / `reporting_store_profile`）
- 上游模块：13.1.1.2 多店对比（读模型）、9.1.1.1 平台单列表（trade）、
  14.3.1.1 活动报表（channel）、14.1.1.1 广告活动列表（channel）
- 下游：13 店铺分析页 `/analytics/dashboard/store-comparison`、09 该店铺订单页 `/orders/platform-sync`
- 兄弟模块：1.1.2.1 核心指标卡（租户级口径，**不可与本模块的店铺级口径混用**）
- 台账：`docs/product/capability-blueprint/module-progress.md` 的 1.1.2.2 行
