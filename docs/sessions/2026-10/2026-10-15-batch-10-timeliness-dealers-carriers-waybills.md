# 批次 10 · 时效分析 / 经销分销 / 承运商列表 / 运单列表

> 2026-10-15 10:30 派发 · 11:03 全部落地 · 11:21 L2 全绿
> 集成分支：`45107b67` → `e67b8696`（4 个 merge commit）
> 本批 4 模块 · 16 张新表 · 41 个端点 · 104 条集成测试 · **零 ALTER 既有表**

---

## 模块 9.5.1.1 时效分析

**这个功能做什么**：运营能按店铺、仓库、商品、承运商四个维度，看订单从「下单→出库」「出库→交接」「承诺到货→实际到货」三段各自的耗时分布，并直接下钻到超时明细。蓝图加粗句是「**时效是平台算法权重项**，超时会持续压低自然流量」——所以这个页面的目的是在流量已经被压下去之前看见它。

**技术落地**：

- 迁移：`V131__trade_order_timeliness_analysis.sql`（514 行，4 表：`order_timeliness_fact` / `_promise` / `_delivery` / `_event`）
- 端点：`/trade/fulfillment/timeliness` × 10 个映射（capabilities / report / dimensions / overdue / events / facts/{id}/promise / facts/{id}/delivery / sync …）
- 页面：`/orders/fulfillment/timeliness`（`order-timeliness-{model,workspace}` + page）
- 测试：`OrderTimelinessIntegrationTest` 37 例 + 模型 12 例 + 白名单 6 例；L1 trade 499 例 0 失败

**关键设计决策**：

- **三段时长是三个独立的 `GENERATED ... STORED` bigint（整数秒），不是相减算出来的。**
  分段独立生成 ⇒ 缺一个环节时整段落 `UNSET`，而不是编出第三种算法去凑一个数。
- **上游全部同库，零跨服务调用** —— `shipment_record`(V127)、`shipment_pending_unit`(V124)、
  `sales_order`/`sales_order_line`(V99) 都在 trade 库，本批唯一没有 HTTP stub 的模块。
- **轨迹数据源不存在（10.3 未交付），但仍然实现了「登记」端点。**
  `capabilities` 仍把承诺/实际两个维度标 `available=false` 并写明 ownerModule，
  但登记是本仓已验证的做法（9.4.1.1 SLA、9.4.1.2 轨迹、9.6.2.1 费率都这么做过），
  也是让蓝图第三个指标真正可算的前提 —— 否则只能交付 3 个里的 2 个。
  未登记的行落**独立的** `NO_PROMISE` / `NO_ACTUAL` 桶，不合并、不按 0 计。
- 前端把 `null` 时长渲染成「未测出」而不是「0 秒」；分母为 0 的比率渲染成「—（无法判定）」而不是 0%。

---

## 模块 9.7.1.2 经销与分销管理

**这个功能做什么**：管理经销商的授权（区域/产品线/期限/经销价/返点）、销量配额与回款考核、返点计提台账、跨区域销售的窜货预警。蓝图的四个输出全部真实落地，没有一个是占位。

**技术落地**：

- 迁移：`V133__trade_dealer_distribution.sql`（428 行，7 表：`dealer_profile` / `dealer_authorization` / `dealer_authorization_event` / `dealer_quota` / `dealer_sale` / `dealer_rebate` / `dealer_diversion_alert`）
- 端点：`/trade/dealers` × 16 个映射（capabilities / candidates / profiles / authorizations(+`{id}`/status) / quotas / quota-attainment / sales / rebates(/accrue) / diversion-alerts(+`{id}`/state)）
- 页面：`/orders/dealers`（`dealer-channel-{model,workspace}` + page，已登记进 `task-pages.ts`）
- 测试：`DealerChannelIntegrationTest` 29 例 + 模型 15 例 + 白名单 6 例；L1 trade 491 例 0 失败

**关键设计决策**：

- **四个不可用的上游被编码成数据，而不是文案。**
  `compliance_state` 只能写 `UNVERIFIED`（16 合规未交付）；
  `finance_confirmed` 硬接 `false` 并逐行渲染成「本模块口径 · 未与财务确认」——
  蓝图原话是「返点计提**需与财务确认口径**」，那就不能让人以为已经确认过。
- **配额完成率不是存储列**，是 `round(actual/target, 6)` 现算；服务层重算一遍与落库值比对，
  不一致直接 500（防止两条路径各算各的）。target=0 返回 `null` → 前端渲染「不考核」。
- **返点比例一律 `NUMERIC(9,6)` + CHECK 0..1**，完成率等除法结果同样不用浮点。
- **「可复用 5.5.2 站点价差倒挂逻辑」这句被判定为未来建议而非现成依赖**（5.5.2 未交付），
  所以跨区域判定只按授权区域越界做，并在 capabilities 里明写这条口径**未与 5.5.2 合并**。

---

## 模块 10.1.1.1 承运商列表

**这个功能做什么**：承运商档案 + 可用性状态（快递/专线/邮政/FBA 承运/末端派送），含覆盖国家、支持业务类型等维度，支持筛选与分页。

**技术落地**：

- 迁移：`V134__logistics_carrier_profile.sql`（431 行，3 表：`carrier_logistics_profile` / `carrier_country_coverage` / `carrier_business_scope`）
- 端点：`/channel/carriers` × 7 个映射（capabilities / overview / list / sync / `{id}` GET·PUT·DELETE）
- 页面：`/logistics/carriers`
- 测试：`CarrierIntegrationTest` 14 例 + 模型 14 例 + 白名单 14 例；L1 channel 103 例 0 失败（89 基线 + 14 新增）

**关键设计决策**：

- **蓝图加粗句是字面落实的：不建承运商主档表、不做「新建承运商」端点。**
  「承运商是往来单位的一种角色，不在此处重复建档」——
  身份 / 接口类型 / 账号 / 能力全部是 catalog 的只读快照，`SaveCarrierRequest` 不接受任何 identity 字段，
  DB 触发器拒绝本地改快照。**在 channel 里重建一份法人信息就是违约。**
- **凭据只存密钥管理器引用 + 掩码**，不落真实账号密码；测试只断言引用与掩码。
- 可用性状态**不是管理员手填的下拉框**，由「接口类型是否配置 / 必需字段是否齐 / secretRef 是否存在」判定。
- 同步来的承运商若物流侧未配置，落 `NOT_REGISTERED`（= 上游知道、但物流侧没配），
  而不是给 `carrier_category`/`service_tier` 编默认值 —— 编默认值正是「硬编码常量当数据」。

> ⚠️ **本模块的派发前提被 worker 推翻并纠正**：调度器的任务书写「3.6.1.2 角色档案已交付在 identity（`V30__*.sql`）」，
> 实际在 **catalog**（`catalog/V30__party_role_profiles.sql`）；identity 的 `V5__legal_entity.sql` 是 17.1.2.1
> **我方法人档案**。若照任务书字面执行，会把「我方自己的法人」当成「上游承运商」读取 —— 测试能绿，业务是错的。
> 调度器已复核确认 worker 判断正确。

---

## 模块 10.2.1.1 运单列表

**这个功能做什么**：运单主列表 —— 运单号、订单/包裹、承运商渠道、发货仓、状态、下单时间、重量、运费，支持按渠道/状态/时间/目的国筛选，并提供申请运单、取消、打印三个批量操作。

**技术落地**：

- 迁移：`V132__trade_waybill_listing.sql`（298 行，2 表：`logistics_waybill_freight` / `logistics_waybill_print_log`）
- 端点：`/trade/logistics/waybills` × 8 个映射（capabilities / filter-options / list / `{id}` / `{id}/freight / actions/{apply,cancel,print}`）
- 页面：`/logistics/shipments`（正好是 10.2.1.2 预留的路由）
- 测试：`WaybillListingIntegrationTest` 24 例 + 模型 16 例 + 白名单 6 例；L1 trade 486 例 0 失败

**关键设计决策**：

- **服务归属裁定：落 trade 而非 channel。**
  蓝图卷 10 头部写「`trade` 拥有运单与报关单据」，且主上游 10.2.1.2 已交付在 trade（`V128__trade_waybill_application.sql`）。
  同服务只读 join 优于跨服务调用，且若放 channel 就会出现两个服务各建一张运单表，
  违反「一张事实表只有一个写入所有者」。
- **列表行查询时现算，不建投影表** —— 否则「列表空」会被误读成「今天一票都没发」，
  而真因可能只是同步没跑。运单号的唯一写入所有者永远是 10.2.1.2。
- **蓝图 7 个展示状态里 4 个没有数据源**（已揽收/运输中/已签收/异常，10.3 未交付），
  这些筛选返回**「没有数据源」的空页**，而不是退化成「今天一票都没发」。
- **运费是登记不是计算** —— 全仓唯一的 `freight/shipping_cost` 是 V116 的按分摊**估算**（另一个事实），
  没有借用。「未登记」是可读状态，与「已登记的 0」分别统计，合计按币种分组。

---

## 本批批次级记录

### 并入冲突：三条 trade 车道撞同一支 alternation

`apps/web/src/lib/api-proxy.ts` 的 trade 段是一支巨大的正则，三条 trade 车道各自追加一个分组 ⇒ 两次并入冲突。

按「求并集」语义解决，三步验证：

1. **先证明形态** —— 用深度感知的顶层分支解析器（括号内/转义内的 `|` 不算分隔）拆两侧分支集。
   第一次尝试断言「中段必须是纯追加」**直接失败**，暴露真实形态：9.7.1.2 的 `dealers` 追加在末尾，
   9.5.1.1 的 `timeliness` 插在 `logistics/waybill-applications` **之前** ⇒ 两处不同位置的插入，不是纯追加。
2. **逐条断言零丢失** —— ours 16 支原样保留、theirs 16 支原样保留、并集 17 支（=16+1，无重复无意外新增）、
   净增字符数**恰好等于**新增分支长度；第二次同样处理得 18 支。
   另确认两条车道对该文件**各自只改那 1 行正则**（相对各自 merge base 均为 1 insertion / 1 deletion），
   故从 HEAD 基线重建不丢任何车道改动；重建后 `git diff --stat` 精确为 1 file / 1 insertion / 1 deletion。
3. **运行时探针实跑**（不满足于「正则能编译」）：trade 段 21 放行 + 6 拒绝、channel 段 6 放行 + 3 拒绝，
   其中 `carriers/{id}/channels`（10.1.1.2 未交付）确实 404 ⇒ **守卫非空转，不是放行一切**。

> 探针本身也骗了一次：用 `/trade/` 前缀测时 28 条全 404，连 `b2b-orders/capabilities` 这种老路径也「挂」。
> 真因是被测代码是 `pattern.test(path.slice(service.prefix.length))`，要比的是**去掉服务前缀、无前导斜杠**的相对路径。
> 差一步就会把「探针坏了」当成「合错了」而去改生产文件。

### 静态预检（合并后、L2 之前）

| 检查 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | ✓ 无命中 |
| `SET NOT NULL` 无触发器兜底 | ✓ 无命中 |
| **ALTER 既有表** | ✓ **零** —— 跨车道 DDL 回归面 = 0 |
| 新表 RLS / FORCE RLS / GRANT | ✓ 16 张全覆盖 |

### L2 批次闸（全量，集成头上一次）

| 阶段 | 结果 |
| --- | --- |
| `backend ./mvnw verify` | **BUILD SUCCESS**，**3789 例**，0 失败 0 错误 |
| `pnpm typecheck` | ✓ 3/3 |
| `pnpm lint` | ✓ |
| `pnpm test` | ✓ **3024 例** / 168 文件 |
| Next production build | ✓ Compiled successfully，141 静态页 |
| `sync.mjs --check` | ✓ 台账与蓝图一致 |

### 遗留与点名

- **9.5.1.1 的 api-proxy 分支没有注释块**，三个兄弟分支都有。安全相关白名单文件缺注释是真实的文档债，
  但那是该车道产出边界，**只点名不代笔**。
- **`trade.ts` 与 `channel.openapi.json` 在集成头里存在陈旧漂移**（`trade.ts` 缺
  `DropshipMargin*`/`DropshipFeeRate*` 且无 codegen 头注释；channel 快照 operationId/path 顺序漂移）。
  两条车道都**主动回退了自己域外的 diff**，没有替别人的车道背锅。codegen 会重写全部 6 个 service，
  这个漂移会在下批任何一次 codegen 里被顶出来。
- **`DropshipTrackingIntegrationTest`(9.6.1.3) 存在挂钟 flake**：首轮全量曾报 `期望 02:54:09Z 实得 02:54:08Z`，
  根因是该测试的 `at(2)` 辅助函数**在断言时重新读了一次 `now()`** 并截断到秒。本批 L2 未复现，
  但它与已知的两个 flake 源（端口抢占、固定编码互相覆盖）不同类 —— 那两个重跑会好，
  这个重跑绿只说明**这一次恰好没跨秒**。应由 9.6.1.3 的主人把夹具 pin 到固定时刻。
- **`logistics/waybill-applications/capabilities` 在白名单里 404**，属 10.2.1.1 车道的缺口，
  9.5.1.1 发现后未越界修改，仅从自己的兄弟回归断言里移除。

### 调度器自查（本批暴露的两个自身失误）

1. **派发前校验有盲区**：用 `git rev-list --count 集成头..分支 == 0` 判「全新车道」，
   但该判据只能证明「没有前人成果」，**证明不了基线是最新的** ——
   9.7.1.2 的 worktree 落后集成头 68 个提交，直接上游 9.7.1.1 只在集成头上。
   正确判据必须**两个方向都读**（`ahead=0` 且 `behind=0`）。
2. **台账的 `migrationVersion` 不携带服务归属**：我据 `mv=V30` 断定「在 identity」而写进任务书，
   实际在 catalog。两条车道各自独立发现并纠正了同一个错误前提。
