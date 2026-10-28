# 2026-10-28: 13.3.1.2 生命周期与分层 —— 六档分层 + ABC + 各层建议（阶段 D7，L3 13.3.1 的第二格）

> 车道 `feat/13-3-1-2`（从集成头 `integration/module-dev` @ `2fc29b57` 切出）。
> 蓝图：`docs/product/capability-blueprint/13-profit-and-analytics.md` → 13.3.1.2「生命周期与分层」。
> **本车道是 L3 13.3.1 的第二个模块** ⇒ 写法照抄同 L3 的 13.3.1.1（产品表现分析）。

## 摘要

| 项 | 值 |
| --- | --- |
| 模块 | 13.3.1.2 生命周期与分层（卷 13 L3 13.3.1 产品分析 → 第二格） |
| 迁移 | `V396__lifecycle_segmentation.sql`（三张新表，零 ALTER 既有表） |
| 端点 | 5 个，挂 `/reporting/lifecycle-segmentation` |
| 页面 | `/analytics/products/lifecycle` |
| 测试 | 后端 24 例（21 功能 + 3 租户隔离）· 前端 34 例 · api-proxy allowlist 回归 71 文件 / 394 例 |
| L0 | `./mvnw -q compile` exit 0；`pnpm typecheck` 3/3 successful |
| L1 | `LifecycleSegmentation*` ⇒ **24 例全绿**（Failures 0 / Errors 0）BUILD SUCCESS |
| 契约 | codegen 产物，**纯增量**（reporting.ts +10 / reporting.openapi.json +847，removed = 0） |

## 决策摘要

### ① 🔴 **不另起一套口径表**（口径唯一）

分层的依据是**销量**与**毛利**。这两个口径**已经在** 13.2.2.2 的
`reporting_product_metric_definition` 与 13.3.1.1 的
`reporting_product_performance_metric_definition` 里登记了。

⇒ 本模块**只引用指标码**（规则表里的 `quantity_metric_code` / `amount_metric_code` /
`gross_profit_metric_code`），**不另起一张口径表重复登记同一批指标** ——
重复登记就是第二个真相：租户改了 13.3.1.1 的毛利口径，本模块若有一份自己的登记，
两边会算出两个不同的毛利，分层结果随即失真。

⇒ 服务层按口径表**递归求值**（SUM / DERIVED_DIFF / DERIVED_RATIO 的分子分母都从口径表读），
查询 SQL 里**不写死公式**；口径没登记 ⇒ 显式报「口径未投影」并点名归谁管，**不是 0**。

### ② 🔴 **只取 SKU 层的事实，SPU 由 SKU 上卷**

13.2.2.2 把销量 / 销售额**同时**写在 SKU 层与 SPU 层。两层都取 ⇒ 窗口总量翻倍
（份额的分母翻倍 ⇒ 档位全错，而且**不报错**，只是全部分错档）。

⇒ 本模块的口径是「**SKU 原生、SPU 由 SKU 上卷**」，事实查询固定 `dimension_type = 'SKU'`。
库存 / 库龄本来就是 SKU 粒度（物理事实不可按平台拆分），同样按 SKU 上卷。

### ③ 金额**单独一张按币种分行的表**

分层结果一个商品可能同时有 USD 与 EUR 的成交额，**没法塞进一行**（硬塞就得选一个币种丢另一个，
或者把两者相加 —— 两者都是造假）。

⇒ 主表只放**与币种无关**的量（销量件数）与**档位 / ABC / 建议动作**；
金额（销售额 / 毛利额 / 按金额的 ABC）落在**按币种分行**的子表里。

⇒ **ABC 按金额必须按币种分别算**：金额跨币种不可比（1 USD 与 1 EUR 谁大没有答案）。
一个商品有两个币种时，它在两个币种里可能是不同的档 —— 这就是真相，不许抹平。

### ④ 判定顺序是**确定的**：先生命周期，后表现

新品 / 衰退是**阶段**，不是「表现好 / 表现差」；把它们放在表现规则之前，
新品才不会被「表现规则」重新定义成平款（那样新品永远只能是平款）。

`NEW` → `DECLINING` → `HOT` → `STRONG` → `SLOW` → `STEADY`。

- **NEW**：首次销售距判定基准日 ≤ 新品天数。回溯窗口里找不到第一次销售 ⇒ 新品判定**不成立**
  （**不是「它是新品」**）。
- **DECLINING**：近窗日均销量相对**对比窗口**日均下滑 ≥ 阈值。对比窗口内没有销量事实 ⇒
  衰退**不可算**（**不是「没衰退」**）。
- **SLOW**：份额低于平款门槛 **且** 日均销量低于滞销门槛 **且** 在手库存 > 0 ——
  没库存的卖不动不叫滞销。

建议动作：爆款 / 旺款 ⇒ **备货**；滞销款 ⇒ **清仓**；衰退款 ⇒ **下架**；新品 / 平款 ⇒ **推广**。

### ⑤ 规则**在库里**，改规则 = **加一个版本**

阈值 / 分类口径 / 版本 / 生效区间 / 启停 / 变更人全落在
`reporting_lifecycle_segmentation_rule`，运行时真相是那张表（服务层的常量只是**标准份**，
第一次分层时按租户播种一份）。

⇒ 部分唯一索引 `(tenant_id, dimension_type) WHERE enabled` ⇒ **同一维度层同时只能有一条生效规则**
（两条同时生效 ⇒「这个商品算爆款还是旺款」取决于读到哪条，报表就成了薛定谔的）。

⇒ 改规则**不改历史结果**：结果行上钉死了 `rule_id` / `rule_version`，
半年后回看仍然知道当时是按哪版规则分的层。改完要重跑一次分层才生效。

⇒ 播种前**先验口径**：口径码还没登记时**不播种** —— 播种一条指向不存在口径的规则，
只会让分层永远「算不出来」，而没人知道是规则坏了。

### ⑥ 🔴 **比例一律是百分数**（服务端 ×100）

前端模型测试抓到的一个真 bug：规则阈值与份额原本是**分数**（0.80 / 0.8824），
而 13.3.1.1 的 PERCENT 口径约定是**服务端先乘 100 再除**（先除到 4 位小数再乘 100 会把有效数字截掉）。

⇒ 统一成**百分数**：`sales_share` / `decline_ratio` 存与接口都走百分数；
规则阈值在 DB 里存百分数（10.0000 = 10%），CHECK 相应放宽到 `0 ~ 100`。
前端只做展示格式化，**绝不自己乘 100**。

### ⑦ 🔴 **timestamptz 往返**：本模块自己绑了一列

13.3.1.1 / 13.3.2.1 的 `created_at` 都取 PG 的 `now()`（DB 钟域），不绑 JVM 墙钟 ——
本模块原本也是。但那样本模块就没有任何 JVM 绑定的 timestamptz，
「`Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s」这条陷阱也就无从被测试盯住。

⇒ 给结果行加一列 `source_observed_at timestamptz`（**上游库存 / 库龄快照的观测时刻** ——
日事实 + 时点快照混在一张读模型里时，这个时刻本身是有业务含义的），
用 `?::timestamptz` + `OffsetDateTime` 绑定，并断言**夹具 `2026-10-04T18:00+08:00` 读回仍是同一瞬间**。

## 关键 trade-off

| 选择 | 代价 | 收益 |
| --- | --- | --- |
| 引用别人的口径表，不另起一张 | 不能自定义「本模块专用口径」 | 口径唯一；租户改口径本模块自动跟着改 |
| 金额单独一张按币种分行的表 | 多一次 join | 钱永远不可能被跨币种加在一起 |
| 结果落库（刷新时算）而不是查询期算 | 改了上游要重跑一次才更新 | 分层结果**可审计**（钉住规则版本）；查询快 |
| 规则版本化而不是就地改 | 表里会有多行历史版本 | 半年后回看仍知道当时按哪版规则分的层 |
| 只到 SKU / SPU 层，不做 LISTING 层 | Listing 粒度没有分层结果 | LISTING 是**上架形态**不是商品本身（同款多 Listing 就是多行） |
| api-proxy 挂在 13.3.1.1 那条之后（不是数组末尾） | 与「插在数组末尾」的约定不同 | 与同批 13.3.2.2 不撞段 ⇒ 并入集成头时零冲突面 |

## 涉及文件

**新增（后端）**

- `backend/services/reporting/src/main/resources/db/migration/V396__lifecycle_segmentation.sql`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLifecycleSegmentationCatalog.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLifecycleSegmentationModels.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLifecycleSegmentationService.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingLifecycleSegmentationController.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/LifecycleSegmentationIntegrationTest.java`
- `backend/services/reporting/src/test/java/com/starcon/reporting/LifecycleSegmentationTenancyIntegrationTest.java`

**新增（前端）**

- `apps/web/src/app/analytics/products/lifecycle/page.tsx`
- `apps/web/src/components/analytics/lifecycle-segmentation-model.ts`
- `apps/web/src/components/analytics/lifecycle-segmentation-workspace.tsx`
- `apps/web/src/components/analytics/lifecycle-segmentation-model.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts`（+58 / −0：新增一条独立 `services[]` 元素）
- `packages/api-contracts/src/contracts/reporting.ts`（codegen 产物，+10）
- `packages/api-contracts/src/reporting.openapi.json`（codegen 产物，+847）
- `docs/product/capability-blueprint/module-progress.md`（只改 13.3.1.2 那一行）

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                      # exit 0
pnpm typecheck                                       # 3/3 successful

# L1 模块闸（只跑本模块自己的测试类）
cd backend && ./mvnw -pl services/reporting -am test \
  -Dtest='LifecycleSegmentation*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ 24 例全绿（21 + 3），Failures 0 / Errors 0，BUILD SUCCESS

# 前端
pnpm vitest run apps/web/src/components/analytics/lifecycle-segmentation-model.test.ts   # 34 例
pnpm vitest run apps/web/src/app/api/trade      # 共享白名单全量回归：71 文件 / 394 例
```

**api-proxy 零命中核对**（`.tmp/api-proxy-zero-hit.mjs`，不入库）：用 TypeScript 编译器解析
`services` 的**真实数组字面量**，按 `resolve()` 的同构口径实测 —— 本模块 6 条路径对既有
254 条元素**零命中**，本条新正则对既有 22 条 reporting 形状**零命中**（不遮蔽别的车道），
刻意不放行的 6 个形状全部 404。

## 后续待办

- **下游**（别的车道）：6.2.1.2 备货、5.4 调价、7.6.1 呆滞消费本模块的输出；
  本模块**不消费**它们（滞销判定用的是 13.3.1.1 的库存 / 库龄快照，不依赖 7.6.1）。
- **本轮不做**：LISTING 层分层（LISTING 是上架形态）；库存金额（warehouse 侧没有成本列）；
  13.4 多模式利润口径的毛利（本模块的毛利 = 销售额 − 成本法出库成本）。
- **可后续补**：分档汇总的**时间趋势**（各档商品数随时间的变化）—— 现在的分档汇总是**单窗口**的；
  要看趋势需要跨窗口的快照表，本轮没做。
