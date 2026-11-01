# 18.4.2.2 选品与需求预测（2026-11-02）—— 销量预测 + 置信区间 + 影响因素 + 只建议不执行

> 车道：`feat/18-4-2-2`（worktree `integration/.worktrees/18.4.2.2`）
> 阶段：D7 · L3 锚点 18.4.2 · 落地后端服务 = **platform**
> 迁移版本号：**V424**（调度器预分配；同批 V422 / V425 / V427 被别车道占用）
> 基线：`integration/module-dev`

---

## 一、这个模块做什么

蓝图 18.4.2.2 原文：「**AI 预测能力**：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、
爆品识别与机会品发现。输入：历史销量、库存、在途、价格、促销日历；预测参数。输出：预测结果 + 置信区间 +
关键影响因素说明。关系：上游 = 13 分析、6 采购、7 库存；下游 = 6.2.1.2 补货建议、5.4 调价。
**预测必须给置信区间和影响因素**，单点数字会让用户过度信任；建议只作为参考输入，不自动执行。」

---

## 二、决策摘要（本车道最关键的六条）

### 1. 🔴 预测必须带区间与影响因素 —— 落到**数据层**，不是提示

- `platform_forecast_result` 的 `point / lower / upper` **三列都是 NOT NULL**：
  样本不足的 SKU **根本不进这张表**（它进的是运行记录里的「跳过计数 + 原因」）。
  ⇒ 「只给单点数字」在**数据库层**就写不进去。
- `platform_forecast_factor` 一行 = 一个因素：**因素码 + 方向（UP/DOWN/NEUTRAL）+ 取值 + 口径说明 + 事实来源**。
  不适用时记「不适用 + 为什么」（例如季节性需 14 天样本），**不假装算过了**。
- 区间不是拍的：**点估计 ± z × σ × √预测天数**（σ = 一步预测误差的样本标准差，分母 n-1；z 取 90/95/99 三档）。

### 2. 🔴 建议只产出，不执行 —— 落到**状态机**

`platform_forecast_suggestion.status` 只有 `SUGGESTED / ADOPTED / REJECTED`，
**没有 EXECUTED**：补货单归 6 采购、调价归 5 定价，跨服务写它们的库是 AGENTS.md 明令禁止的。
「采纳」只是**记下人的决定**。测试里有一条独立断言钉住「建议表不允许出现 EXECUTED 状态」。

### 3. 🔴 上游读不到就**不生成**那一格建议，而不是拿 0 顶上

库存 / 在途读不到 ⇒ **不生成补货建议**（0 冒充「库存为 0」会直接导致该补的不补）；
每次运行的 `inventory_connected / in_transit_connected / purchase_connected` 如实记录接通情况，
前端按「未接通 + 归属系统」显示。

### 4. 🔴 历史销量有一个**带来源**的台账，撞键就报冲突

`platform_forecast_demand_history` 唯一键 `(tenant_id, sku_code, business_date)`：
同一 SKU 同一天只允许一条 —— 两套口径落在同一天会**重复计数**，重复计数直接抬高基线 ⇒ 预测偏高 ⇒ 补多货。
`source` 字段标明「人工录入 / 13 分析同步」，每行可回查。

### 5. 🔴 促销日历是**租户自己的业务事实**

`platform_forecast_promotion.source` 标明录入方式（MANUAL / IMPORT），**不假装是从上游同步来的**。
`expected_lift` **必填** —— 没有它，预测就说不清促销会把销量推高多少；
与其用藏在代码里的默认系数，不如录入时就说清楚。

### 6. 🔴 算法**可解释**，不伪装成 ML

本仓没有任何外部 AI / LLM 供应商适配器 ⇒ 预测走**确定性算法**：
移动平均（最近 maWindowDays 天均值）/ 指数平滑（level = α·当日 + (1-α)·上一期水平）。
算法、参数、样本规则、阈值、数据来源、边界全部逐条写在 `/platform/ai/forecast/capabilities` 里，
页面照着显示。**AI 大模型那一维如实答「没有」并归 18.4 卷。**

---

## 三、关键 trade-off

| 决策 | 取舍 | 代价 / 理由 |
| --- | --- | --- |
| **在途只扣一次**：用 7 库存的「在途净额」，6 采购的未到货量只作参考 | 两个口径都扣 ⇒ 重复扣减 ⇒ 建议量偏小 ⇒ 该补的不补 | 采购侧数字要人工看第二眼（依据里写明「仅作参考」） |
| **缺记录的日子按 0 销量计**（整条日历序列） | 若台账只登记了有销量的日子，基线会**偏低** | 已在 DATA_QUALITY 因素里如实写出覆盖度（`sampleDays / windowDays`），不藏着 |
| **样本不足 4 天不给预测** | 少给一批结果 | 宁可少给也不给「拍脑袋的窄区间」——窄区间比没区间更危险 |
| **促销因子取最大单次、不叠加** | 重叠大促被低估 | 叠加会高估；取最大是保守口径，写在规则里 |
| **调价建议只在弹性 ≤ -0.5 时给、幅度上限 10%** | 不给「一次调到最优」 | 调价是 5 定价的写入所有权，本模块只给参考 |
| **需求历史落自己的表**（而不是每次实时读上游） | 抄了一份「预测用」的需求台账 | 它**不是**订单事实（订单事实住 trade），是**预测输入**；每行带 source + source_ref，来源可回查 |

---

## 四、文件清单

### 后端（platform 服务）

- `backend/services/platform/src/main/resources/db/migration/V424__ai_forecast.sql` —— 六表 + RLS（ENABLE **与 FORCE**）+ `isolation` 策略 + 租户触发器 + GRANT + 发号序列
- `backend/services/platform/src/main/java/com/starcon/platform/AiForecastModels.java` —— 30 个传输模型（`AiForecast*` 前缀，避免与别服务 schema 撞名）
- `backend/services/platform/src/main/java/com/starcon/platform/AiForecastService.java` —— 服务层（jOOQ 显式 SQL）+ 预测引擎
- `backend/services/platform/src/main/java/com/starcon/platform/AiForecastController.java` —— 20 条端点，挂 `/platform/ai/forecast`
- `backend/services/platform/src/main/java/com/starcon/platform/ForecastSalesPort.java` —— 13 分析（reporting 8088）销量趋势
- `backend/services/platform/src/main/java/com/starcon/platform/ForecastInTransitPort.java` —— 7 库存（warehouse 8084）在途净额
- `backend/services/platform/src/main/java/com/starcon/platform/ForecastPurchasePort.java` —— 6 采购（trade 8085）采购事实
- `backend/services/platform/src/test/java/com/starcon/platform/AiForecastIntegrationTest.java` —— 19 例
- `backend/services/platform/src/test/java/com/starcon/platform/AiForecastTenancyIntegrationTest.java` —— 7 例（三条隔离断言）

### 后端（identity 服务，权限码）

- `backend/services/identity/src/main/resources/db/migration/V424__platform_ai_forecast_permission.sql` —— `platform.ai_forecast.read / .create / .update / .adopt`

### 前端

- `apps/web/src/app/ai/forecast/page.tsx` —— 路由 `/ai/forecast`
- `apps/web/src/components/ai/ai-forecast-model.ts` —— 模型层 + 展示口径（区间几何 / 因素 / 建议状态 / 上游连通性）
- `apps/web/src/components/ai/ai-forecast-workspace.tsx` —— 真实工作区（六页签）
- `apps/web/src/components/ai/ai-forecast-model.test.ts` —— 18 例
- `apps/web/src/app/api/platform/ai-forecast-allowlist.test.ts` —— 6 例（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `platform/` services 元素**（既有条目一字未改、不重排）

### 契约 / 台账 / 会话

- `packages/api-contracts/src/contracts/platform.ts` + `packages/api-contracts/src/platform.openapi.json` —— codegen 产物（**未手改**）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 18.4.2.2 这一行**的状态与证据
- `docs/sessions/2026-11/2026-11-02-ai-forecast-demand-forecast.md` —— 本文件

---

## 五、验证（真实输出）

```bash
# L0 编译闸
cd backend && ./mvnw -q -pl services/platform -am compile        # ✅ 无 ERROR

# L0 类型闸
pnpm typecheck                                                   # ✅ 3 packages successful

# L0 格式自检（只查本车道改动过的文件）
pnpm exec prettier --check <本车道的 6 个前端文件>                # ✅ All matched files use Prettier code style!

# L1 模块闸（后端，只跑本车道新增的测试类）
cd backend && ./mvnw -pl services/platform -am test \
    -Dtest='AiForecast*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ✅ Tests run: 26, Failures: 0, Errors: 0, Skipped: 0

# L1 前端
pnpm vitest run apps/web/src/components/ai/ai-forecast-model.test.ts \
                 apps/web/src/app/api/platform/ai-forecast-allowlist.test.ts
# ✅ Test Files 2 passed / Tests 24 passed

# api-proxy 全量 allowlist 回归（因为改了共享白名单）
pnpm vitest run apps/web/src/app/api
# ✅ Test Files 284 passed / Tests 1602 passed
```

**多租户三条断言**（`AiForecastTenancyIntegrationTest`）：

1. 两租户互不可见 —— 列表 + **按主键直查** + 事务外零行；
2. 伪造租户上下文无效 —— 无 `app.current_tenant` 时写入被**租户触发器**挡下（报「缺少租户上下文」），
   伪造 `X-Tenant-Id` 头时行**仍然写入成功但落在会话租户名下**（不是只断言「看不见」）；
3. 连接池固定 **1 条**连接交替 5 轮不串租户，归还后事务外零行。

外加两条独立断言：六张表都是 **FORCE** RLS（不是只 ENABLE）+ 每张表都有 `isolation` 策略 +
`tenant_id NOT NULL`；**建议表不允许出现 EXECUTED 状态**。

---

## 六、后续待办

1. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**
   （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
2. **13 分析的销量口径不带价格** ⇒ 价格弹性目前只能靠人工录单价；
   等上游补一个带价格的销量口径后可以省掉这一步。
3. **回测（18.4.3.2）**：本模块已把每次运行的算法、参数、区间与因素**完整落库**，回测拿得到原始输入；
   回测口径本身归 18.4.3.2。
4. **6.2.1.2 / 5.4 的取数通道**尚未约定：建议被采纳后，下游**怎么取**（事件？轮询？）本模块没有实现，
   也不该由本模块实现（那是属主服务的写入所有权）。
