# 1.1.1.2 异常订单队列（阶段 D7 · L3 锚点 1.1.1 / 路由 /dashboard/todo）

> 会话记录：**决策摘要 + 关键 trade-off + 文件清单 + 后续待办**，不复述过程。
> 本车道是**卷 01（工作台与门户）的第一条车道**。

## 决策摘要

交付 1.1.1.2「异常订单队列」：trade 服务的 4 个只读端点（`/trade/anomaly-orders`）+
前端 `/dashboard/todo` 工作区 + 19 例后端集成测试 + 25 例前端模型单测 + 1 个 api-proxy 双向白名单测试。

**本模块是只读投影，不新建任何表、不占用 V327、不提供任何写端点。**
卷 01 的头把边界写死：「不产生新的业务事实，只聚合各系统的投影」「不做：任何写业务数据的操作。
工作台只读 + 跳转；一切状态变更回到归属系统完成」。⇒ 全部判定都在已有事实表上**现场算**，
不物化成「异常表」（物化表要有人写、有人保鲜、有人对账，而卷 01 明写不产生新事实）。

**七类异常 → 真实事实表**（蓝图写的表名是示意）：

| 异常类型 | 事实来源 | 归属 |
| --- | --- | --- |
| 平台超时未回传发货 | `shipment_writeback_task` + `shipment_record` | trade |
| 地址不可达 | `order_address_check.delivery_status='NOT_DELIVERABLE'` | trade |
| 买家留言待确认 | `platform_order.normalized_status='PENDING_CONFIRMATION'` | trade |
| 黑名单买家 | `buyer_blacklist_order_flag` | **channel**（前端合并） |
| 库存不足卡单 | `stock_bucket` / 可售量投影 | **warehouse**（前端合并） |
| 地址欺诈疑似 | `order_risk_assessment_component` 的 `ADDRESS_CONCENTRATION` 维 | trade |
| 重复下单 | 同表 `ORDER_FREQUENCY` 维 | trade |

蓝图写的 `order.status_history` 真实表名是 **`sales_order_status_history`**；
蓝图写的 `order_charge` 真实存在，但**没有「异常标记」列**（它记的是费用，正负由 DB CHECK 钉死），
所以本模块把它当**订单费用明细**进详情页，**不当异常判据**。

## 关键 trade-off

- **批量动作为什么只跳转、不写**（蓝图输出写的是「批量标记忽略 / 转人工 / 转售后」）：
  卷 01 明确「工作台只读 + 跳转」。取舍：**跳转到归属系统的入口 + 交接选中的订单号**，
  工作台不代改订单状态。代价是批量处置要多一次人工交接；
  换来的是**页面的处置结论与归属系统的结论永远只有一份**（不造第二份真相）。
  🔴 **「标记忽略」在 `capabilities` 里 `available=false`** 并写明缺什么
  （归属系统当前没有「忽略一条异常」的写端点），页面把它渲染成**带缺口的说明而不是能一键落地的按钮**。
- **跨服务聚合放前端还是放 trade**：AGENTS.md 禁止跨服务 SQL / FK / 共享视图，
  所以黑名单（channel）与可用量（warehouse）只能经它们各自的授权只读端点读。
  取舍：**前端聚合层合并**（`GET /channel/buyer-blacklist/flags`、
  `GET /warehouse/inventory/sellable/skus`），trade 侧**不**代读。
  代价是前端要为跨服务类型做**本地筛选**（后端按类型过滤会把跨服务两类整类漏掉）；
  换来的是模块边界不被穿透。
- **连接键必须逐字同式**：黑名单按 `buyer_key`（收件人三件套 md5，**分隔符 U+001F**，
  trade 侧现算 `md5(concat_ws(E'\x1f', ...))`）、库存按 `sku_id`。
  键写错的表现是「一条异常都没有」而不是报错 ⇒ 单元测试专门钉住这两种漏判。
- **「查无库存账」≠「库存充足」**：warehouse 对没有库存账的 SKU 返回**没有那一行**而不是 0。
  取舍：单列为「查无库存账」如实报出，**不**算成「库存不足」；
  channel / warehouse 读不到时页面明确写「本次**没有**黑名单信号 / **判不了**库存不足」，
  **不冒充「没有异常」**。
- **停留时长逐类锚定**（不是「订单创建至今」）：平台回传任务创建时刻 / 地址校验判定基准时刻 /
  平台单下单时刻 / 风控评估基准时刻；一张单命中多类时清单行取**最久**那一条。
  两个时间阈值（24 小时）做成常量并**在能力声明里公开**，不让「超时」这个词没有含义。
- **计数与清单同源**：异常概览的七类计数与清单是**同一份合并后集合**算出来的，
  翻页不变；类型筛选统一在前端合并之后做（跨服务两类后端过滤不了）。
- **页面只做本格，不替兄弟模块实现**：1.1.1.1 我的待办与 1.1.1.3 审批待办**未交付**，
  本车道只做 1.1.1.2，页面留好扩展位。

## 文件清单

**后端（trade 服务，只读）**
- `backend/services/trade/src/main/java/com/starcon/trade/AnomalyOrderModels.java`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/AnomalyOrderService.java`（新增，jOOQ 显式 SQL）
- `backend/services/trade/src/main/java/com/starcon/trade/AnomalyOrderController.java`（新增，4 个 GET）
- `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java`（新增，12 例）
- `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java`（新增，7 例）
- **迁移：无**（只读现有表 ⇒ 不占用 V327）

**前端**
- `apps/web/src/components/dashboard/anomaly-order-model.ts`（新增：跨服务合并 / 筛选 / 汇总 / 文案 / 导航）
- `apps/web/src/components/dashboard/anomaly-order-model.test.ts`（新增，25 例）
- `apps/web/src/components/dashboard/anomaly-order-workspace.tsx`（新增：能力声明 / 概览 / 清单 / 批量处置 / 明细）
- `apps/web/src/app/dashboard/todo/page.tsx`（新增：L3 锚点路由）
- `apps/web/src/app/api/trade/anomaly-order-allowlist.test.ts`（新增：白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**共享白名单**：新增一条独立 `services[]` 元素，锚在**数组最开头**）

**契约与台账**
- `packages/api-contracts/src/trade.openapi.json` + `packages/api-contracts/src/contracts/trade.ts`
  （`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成；
  新增 16 个 `AnomalyOrder*` 类型，其余 5 个服务逐字节不变）
- `docs/product/capability-blueprint/module-progress.md`（1.1.1.2 一行的状态与证据）
- `docs/sessions/2026-10/2026-10-23-anomaly-order-queue.md`（本文件）

## 后续待办

- **1.1.1.1 我的待办 / 1.1.1.3 审批待办** 未交付；同一路由下由后续车道补另外两块，
  本页面的能力声明区与筛选区可复用，但**不要**把它们包进本页面。
- **平台绩效指标**（`platform_performance_metric`）本仓没有这张表也没有模块持有（属 1.1.2.3 履约健康），
  已在 `capabilities.notConnected` 里如实报出；将来 1.1.2.3 落地后，
  「平台超时未回传发货」与「平台侧绩效」是两条线，不要合并。
- **「标记忽略」需要归属系统提供写端点**（09 订单处理页）才能变成可落地的批量动作；
  在那之前工作台不写业务数据这条边界不能破。
- **蓝图的「共享数据 listing 映射状态」** 归 02 渠道所有，跨服务读被禁；
  将来若需要展示 listing 映射结论，应由 channel 提供只读端点，由前端合并。
- **跨服务取数的取数上限**：本车道用「清单最多 5 页 × 200、黑名单最多 5 页 × 200、
  可售量最多 10 页 × 200」并**如实报出截断**；租户规模上来后应改为按需取（按 buyerKey / skuId 精确取）。
