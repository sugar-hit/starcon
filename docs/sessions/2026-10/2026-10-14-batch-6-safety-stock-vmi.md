# Batch 6 · 安全库存与 VMI 供应商管理库存（2026-10-14 13:30 批次）

> 本批实际交付 2 个模块（**都是回收**，不是新开发）：两条车道从 10:30 批次起滞留，
> 卡在 `apps/web/src/lib/api-proxy.ts` 的 warehouse alternation 冲突上，
> 一直没能并入集成头。本批的核心工作是把它们捞回来并解掉冲突。
> 另有 4 条新车道已派发（见文末），它们不属本批验证范围。

## 本批基线数字

| 指标 | 批次开始 | 批次结束 |
| --- | --- | --- |
| 已交付 | 140 | **142** |
| 未开发 | 325 | **323** |
| 完成率 | 30.1% | **30.5%** |
| 集成分支 | `8a080cd` | `cbd7840` |

---

## 模块 7.2.2.2 安全库存

**这个功能做什么**：给每个 SKU × 仓设一条「安全库存」线，按档位定义补货阈值；
系统按日均销量定期评估，产出缺货预警与补货建议，运营可以确认或忽略每一条建议。
这是错发和断货的守门人 —— 阈值作废走「关闭区间」而不是物理删除，预警和建议都要留档复盘。

**技术落地**：

- **迁移**：`V114__warehouse_safety_stock.sql`（warehouse 服务）
  - 表：`safety_stock_policy`（阈值档位）、`safety_stock_policy_warehouse`（按仓覆盖）、
    `safety_stock_sales`（日均销量登记）、`safety_stock_evaluation`（评估与预警结果）
  - 索引：`safety_stock_policy_resolve` / `_open` / `_reference`、
    `safety_stock_policy_warehouse_scan` / `_warehouse_policy`、`safety_stock_sales_window`、
    `safety_stock_evaluation_recent` / `_warehouse`
  - RLS：全部业务表 `tenant_id NOT NULL` + 库级 RLS 策略
  - 显式 `REVOKE DELETE`：服务端无 DELETE 端点，靠数据库权限兜底「不能物理删」
- **端点**（18 个操作，GET/POST）：
  - `GET/POST /warehouse/inventory/safety-stock/policies` — 阈值档位列表 / 新建
  - `GET /warehouse/inventory/safety-stock/policies/{id}` · `GET .../policies/effective` — 单档 / 当前生效档
  - `POST .../policies/{id}/versions` · `POST .../policies/{id}/retire` — 改版本 / 作废
  - `GET/POST .../sales` — 日均销量登记
  - `POST .../evaluate` — 触发评估
  - `GET .../evaluations` · `GET .../alerts` · `GET .../alerts/{id}` — 评估与预警查询
  - `POST .../alerts/{id}/acknowledge` · `POST .../alerts/{id}/resolve` — 预警处理
  - `GET .../suggestions` · `POST .../suggestions/{id}/acknowledge` · `POST .../suggestions/{id}/dismiss` — 补货建议
  - `GET .../capabilities` — 能力声明
- **页面**：`/inventory/safety-stock` — 阈值档位 / 预警 / 建议三段工作区，
  模型层 `safety-stock-model.ts`（25 例单测），错误态/空态/加载态齐全
- **测试**：`SafetyStockIntegrationTest` **38 例**（Testcontainers PostgreSQL 16，含租户隔离三断言）

**关键设计决策**：
- **字面量子路径必须排在通配支之前**。`alerts/capabilities` 若排在 `[0-9a-f-]+` 之后，
  会被判成「id=capabilities 的预警」而凭空放行一个后端并不存在的形状 —— 这是 alternation
  首个命中即返回的直接后果，不是理论风险。
- pattern 按路径登记、不区分方法，所以读写形状合并在同一支里；「不能删」这条防线
  因此只能落在服务端（无 DELETE 端点 → 405）与数据库（REVOKE DELETE），
  而不能靠白名单 —— 白名单拦不住同一路径上的 DELETE。
- 阈值**作废**（retire，关区间）而不是删除：预警与建议要留档复盘。

---

## 模块 7.7.2.1 VMI 供应商管理库存

**这个功能做什么**：和供应商签一份代管协议，按协议约定对方仓库里哪些货是「我的」、
哪些是「他的」，持续同步水位快照与销售流水，算出可售量与自有货值，
并在可售不足时产生补货信号、走发货与结算流程。
**可售额与自有货值分两张卡展示，永不相加** —— 这是货权红线。

**技术落地**：

- **迁移**：`V115__warehouse_vmi.sql`（warehouse 服务）
  - 表：`vmi_agreement`（协议）、`vmi_agreement_line`（协议行）、
    `vmi_stock_snapshot`（水位快照）、`vmi_sale`（销售流水）、
    `vmi_replenishment_signal`（补货信号）、`vmi_settlement` / `vmi_settlement_line`（结算）、
    `vmi_audit`（审计）
  - 索引：`idx_vmi_agreement_list` / `_supplier`、`idx_vmi_line_agreement`、
    `idx_vmi_line_owner(tenant_id, stock_owner_id, sku_id, warehouse_id)`
  - RLS：全部业务表 `tenant_id NOT NULL` + 库级 RLS
- **端点**（25 个操作，GET/POST）：
  - 协议：`GET/POST .../agreements`、`GET .../agreements/{id}`、
    `POST .../activate` / `/suspend` / `/close`、`GET .../audit`
  - 协议行：`POST .../lines`、`GET/POST .../lines/{lineId}/sales`
  - 水位：`GET .../water-levels`、`GET/POST .../snapshots`
  - 补货信号：`GET .../replenishment-signals`、`POST .../{signalId}/acknowledge` /
    `/dispatch` / `/fulfil` / `/cancel`
  - 结算：`GET .../settlements`、`GET .../settlements/{id}`、
    `POST .../settlements/{settlementId}/confirm`、`GET .../settlement-window`
  - `GET .../capabilities`
- **页面**：`/inventory/vmi` — 协议 / 水位 / 销售 / 补货 / 结算工作区，
  模型层 `vmi-model.ts`（23 例单测）
- **测试**：`VmiIntegrationTest` **27 例**（Testcontainers PostgreSQL 16，含租户隔离三断言 + 货权红线）

**关键设计决策**：
- **「可售 ≠ 自有」是 DB 层不变量**，不是前端约定。可售额与自有货值在前端分两张卡，
  物理上没有「合计」这个动作可以点。
- 协议状态机（`draft → active → suspended → closed`）决定了哪些操作可用，
  状态与可执行动作在服务端判定，不靠前端隐藏按钮。

---

## 并入过程：一次差点造成静默数据丢失的冲突

两条车道都从 `fb59785` 切出，比当时的集成头**落后 10 个提交**，
都在 `apps/web/src/lib/api-proxy.ts` 的 warehouse 顶层 alternation 上冲突。

### 为什么不能「取 theirs」

该 alternation 里 **`inventory\/` 分支出现两次且内容不同**（position 0 与 position 18）。
按 token 集合做差分时，车道版本看起来是「HEAD 的超集，只多一个 safety-stock」——
**这个结论只在内层成立**。放到完整 alternation 上，车道版本**不含 `pick-waves` 分支**：

> 直接取 theirs，会静默删掉 8.3.2.2 已交付的波次路由。
> git 不报冲突、测试不一定覆盖、页面在浏览器里白屏才暴露。

### 实际解法

| 车道 | 保留 | 新增 |
| --- | --- | --- |
| `feat/7-2-2-2` | HEAD 整行（含 `pick-waves`），只把 position 0 的 inventory 支换成车道版 | `safety-stock` 支 |
| `feat/7-7-2-1` | HEAD 整行（含 `pick-waves` 与刚并入的 `safety-stock`），只把 position 18 的 inventory 支换成车道版 | `vmi` 支 |

两次合并都先做了**行为级验证**：用各服务 OpenAPI 契约里声明的 path 当用例集，
逐条断言「契约里声明的路径能被 proxy 放行」，比较并入前后的放行集合：

| | 并入前 | 并入后 | LOST | GAINED |
| --- | --- | --- | --- | --- |
| 7.2.2.2 | 848 放行 / 59 拒 | 864 放行 / 59 拒 | **0** | 16 条 safety-stock |
| 7.7.2.1 | 864 放行 / 59 拒 | 887 放行 / 59 拒 | **0** | 23 条 VMI |

59 条既有缺口与本批无关（用同一探针跑基线才能区分「这批弄坏的」与「本来就坏的」）。

### 工具本身的两次翻车（记录下来是因为它们会再次发生）

1. **探针自身的 off-by-one**：`"prefix: '"` 长度是 9，内容从 `p+9` 起而不是 `p+10`，
   导致每个服务前缀都被截断成 `uth/`。探针报「找不到白名单」而不是静默少算。
2. **探针的正则起始标记**：`indexOf('/^(')` 会漏掉 crm 段那种 `/^knowledge...` 形状。
3. **解冲突脚本自己的切片越界**：合并 VMI 时注释切片误把 HEAD 的旧正则行一并保留，
   产生两行连续 regex 字面量（TS 语法错误）。**是探针「新增 0 条」这个反常结果暴露的它**
   —— 不是断言，是交叉验证起了作用。

---

## 本批派发（不属本批验证范围，下批合并后验证）

| 模块 | 名称 | 阶段 | 服务 | 迁移版本 |
| --- | --- | --- | --- | --- |
| 8.3.3.1 | 复核扫描 | D4 | warehouse | V117 |
| 9.6.1.1 | 代发模式配置 | D4 | trade | V118 |
| 9.1.1.3 | 订单变更与取消 | D4 | trade | V119 |
| 9.7.1.1 | 批发订单与账期 | D4 | trade | V120 |

⚠️ **下批预判**：4 条里有 3 条（9.6.1.1 / 9.1.1.3 / 9.7.1.1）都落在 trade 服务的
同一段 alternation 上，冲突面比本批更大。已在任务书里要求三条车道只做外科式最小追加、
各自补 allowlist 断言、不得重排已有分支，但冲突本身仍需按「一次并一条 + 行为级验证」处理。

---

## L2 批次闸结果（全绿）

| 项 | 结果 |
| --- | --- |
| `backend ./mvnw verify` | **绿** · BUILD SUCCESS · 10:12 · **3321 例** |
| `pnpm typecheck` | **绿** |
| `pnpm lint` | **绿** |
| `pnpm test` | **绿** · 136 文件 / **2549 例** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **绿** |
| `sync.mjs --check` | **绿** |
| **L2 总耗时** | **11 分 13 秒** |

`SafetyStockIntegrationTest`（38 例）与 `VmiIntegrationTest`（27 例）均在本轮实跑通过。

## 静态预检

本批新迁移 `V114` / `V115`：

| 检查 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | 无命中 |
| `SET NOT NULL` 无触发器兜底 | 无命中 |
| `ADD CONSTRAINT ... CHECK` | 无命中 |

## 调度闸与温度观测

- **节流闸**：距上批 **179 分钟** ≥ 150 → GO（实际生效间隔仍是 180 分钟，见本地文件取证）
- **容量闸**：therm `No thermal warning level has been recorded`（无降频项）·
  load 1.93/1.80/1.88（1/5/15min，平稳，远低于阈值 12）· Testcontainers PG 峰值 4（阈值 6）→ **GO**
- **调度闸泄漏修复**：上一批 10:30 持闸后未 `gate release`，本轮开闸被卡 3 小时
  （owner PID 29837 已死）。本批在**派发完成后立即释放**，不再把闸持有到 L2 结束。