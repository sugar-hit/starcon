# batch-39 —— 回收 4 条车道并入集成头 + L2 批量验证（两轮）+ 3 条并入冲突的根因与修复车道

**日期**：2026-10-20 23:00 tick
**模式**：full（`TOKEN_HORIZON=2026-10-25T00:00:00+0800`，当前 2026-10-20 23:00，**未超期**；
`LANES=4` / `THROTTLE_MIN=50` / `RUNNING_QUOTA=6` / `PG_CONTAINER_LIMIT=6` / `LOAD_LIMIT=12`）
**本批交付**：4 个模块并入集成头（`15.2.2.1` / `5.5.2.1` / `5.2.1.2` / `5.2.2.1`），
另派发 4 条车道（3 条并入冲突修复 + 1 条 trade 跨时钟域缺陷修复）。

> 本轮台账：465 个 L4 模块，**已交付 247 / 进行中 3 / 未开发 215**（53.1%）。
> 上一轮（batch-38）交付 243 ⇒ 本批 +4。
> 「进行中 3」= 本批并入冲突的三条车道（`5.2.1.3` / `5.2.3.1` / `5.3.1.1`）——
> 成果已落盘在各自分支，只等下一轮修复并入；台账证据列以「阻塞：」开头，编排脚本会跳过它们。

---

## 模块 15.2.2.1 Request a Review（索评）

**这个功能做什么**：买家已签收之后，运营可以向**这一位买家、这一张已签收的订单**发起一次索评请求，
并且**「索评频次必须受限」不是一句界面提示，而是三道落进数据库的闸门** ——
被频次规则挡住的请求照样落库留痕（`REJECTED` + 原因码），让人事后查得到
「谁在什么时候想向哪位买家索评、被什么挡住了」。

**技术落地**：

- 迁移：`V220__crm_review_request.sql`（**crm 服务**，卷 15 客户与客服）——
  四张新表 `crm_review_frequency_policy`（租户级频次硬上限）/
  `crm_review_request`（索评任务）/ `crm_review_send_record`（发送记录）/
  `crm_review_recovery`（评价回收桶），
  全部 `tenant_id NOT NULL` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + `GRANT crm_app`，
  **纯新建、零 ALTER 任何既有表**；**序列单独授权**
  （`GRANT USAGE, SELECT ON SEQUENCE ... TO crm_app` —— `GRANT ... ON ALL TABLES` 不覆盖序列）。
- 端点：`/crm/review-requests` × **12** ——
  `capabilities` / `frequency-policy`（GET 读 · PUT 配）/ `shops` / `candidates` /
  集合 GET+POST 新建 / `{requestId}` 详情 / `batch` 批量发起 /
  `{requestId}/send-records` / `{requestId}/recoveries` / `recovery-stats`。
  **刻意没有 DELETE**（索评请求是频次计数的凭据），**刻意没有 rules**（索评规则配置归 15.2.2.2）。
- 页面：`/crm/review-requests`（`apps/web/src/app/crm/review-requests/page.tsx` +
  `components/crm/review-request-model.ts` + `review-request-workspace.tsx`）——
  能力声明逐维显形 / 频次硬上限面板 / 单条发起 / 批量执行逐条给结论与原因 /
  索评任务列表（**被频次规则拒绝是独立状态**）/ 详情（发送记录 + 回收桶）/ 评价回收统计；
  **分母为 0 时回收率报「不计算」而不是 0%**。
- 测试：`ReviewRequestIntegrationTest` **22 例** +
  `ReviewRequestTenancyIntegrationTest` **4 例**（租户隔离三断言）；
  前端 `review-request-model.test.ts` **11 例** + `review-requests-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **「索评频次必须受限」落成三层，不是三层 UI**：
   ① 租户级硬上限表，每个值在 DB 层另有天花板（每买家 ≤10 次/窗口、每订单 ≤5 次、
   窗口 ≤365 天、冷却与时点下限 ≤8760 小时）——「把上限调到 999」这条路本身被 CHECK 堵死；
   ② 触发器 `guard_crm_review_request_frequency`（BEFORE INSERT OR UPDATE）在**数据库层**判定
   每订单上限 / 每买家上限 / 冷却窗口，并强制「本次规则快照**只能比硬上限更严**」；
   ③ 服务端用**同一判据**预检并给**可分类的原因码**
   （`PER_ORDER_LIMIT` / `PER_BUYER_LIMIT` / `COOLDOWN_ACTIVE`）。
   **REJECTED / CANCELLED 不占额度** —— 否则「试一次被挡掉」会把额度吃掉。
2. **超限 = 记录下来，不是报错回滚**：「留下原因」的字面落点 ——
   请求行存在、`reject_code` + `reject_reason` 有值、列表里筛得到。
   而**输入本身不合法**（上游没签收 / 店铺不在清单 / 规则比硬上限松）走 4xx、**不写行** ——
   两类错误严格分开。
3. **跨服务只走端口**：上游 10.3.2.2（channel，V217）**不同服务** ⇒ 一条 SQL 都不碰对方的库，
   只经 `SignoffDeliveredOrderPort` 走**授权 HTTP API**
   （`GET /channel/logistics-pickup-signoff/cases?status=DELIVERED` 等），
   本库**只存上游的逻辑引用 + 快照**，**不建跨库 FK**；上游读不到一律 **503**，
   **绝不返回空集合冒充「没有已签收订单」**。
4. **上游没有的维度如实标 `available=false`，不造数据源**：
   `candidate_shop_attribution`（ownerModule=10.3.2.2）、`buyer_identity`
   （ownerModule=15.3.1.1，本仓**没有买家档案**，买家标识由运营按店铺提供、**只作频次去重键**）、
   `platform_review_api`（ownerModule=2，平台侧索评接口**未接入**，
   发送记录**只允许 `MANUAL_HANDOFF`** —— 一个「返回成功回执」的假通道会让
   「买家已经被索评过」在数据上看起来发生过，而过度索评的代价正是平台处罚）。
5. **零外键**：本表之间的引用校验一律用**触发器**
   （`guard_crm_review_send_record_target` / `guard_crm_review_recovery_target`）——
   batch-37 有车道给既有表建 FK 打断了同服务既有测试的 TRUNCATE（39 例 Errors、L2 直接红），
   本车道刻意避开，**既有测试一行未改**。
6. **身份列建后不可改**：触发器 `guard_crm_review_request_identity` 钉死
   上游引用 / 订单号 / 买家 / 店铺 / 签收时刻 / 规则快照 ——
   改它会让「这个买家被索评过几次」这个频次计数在历史上出现两个答案。

---

## 模块 5.5.2.1 异常告警

**这个功能做什么**：盯住四类价格异常 —— 平台价与内部价**长期**不一致、售价低于成本线、
平台自动降价、多站点价格倒挂 —— 输出**可统计的异常事件清单 + 预警通知**，
并且对「德国站比美国站便宜」这种倒挂，**清单上一眼能看出方向**，不是只有一个平均价差。

**技术落地**：

- 迁移：`V222__pricing_price_anomaly.sql`（catalog）—— 四张新表：
  `price_anomaly_observation`（**价格观测台账**，一行 = 一个 SKU 在一个站点上的一次价格观测，
  含内部价 / 平台价 / 变动来源 / 比价口径 / 观测时刻，**只追加**，
  `UNIQUE(tenant_id,sku_id,platform_code,site_code,observed_at)` 钉死「同一时刻一条」）·
  `price_anomaly_rule`（站点价差规则，每种异常类型一条，阈值可空 = 用服务层默认值，
  显式给 0 合法）· `price_anomaly_event`（异常事件，含**站点对** / 持续天数 /
  状态 OPEN→ACKNOWLEDGED→RESOLVED / **fingerprint 幂等键**）·
  `price_anomaly_notification`（预警通知，`owner_module` 恒为 1.3.1.1、`delivered` 恒为 false）。
  `tenant_id NOT NULL` + `ENABLE`/`FORCE` RLS isolation + `RESTRICTIVE` data_scope + `GRANT`。
  **零 ALTER 任何既有表、零 FOREIGN KEY**（引用校验用触发器）。
- 端点：`/catalog/price-anomaly-alerts` × **11** ——
  `capabilities`（四个输入的真值与缺口 + 四类异常口径）/ `observations`（GET 台账 · POST 登记）/
  `events`（清单）/ `events/{id}`（详情）/ `events/{id}/acknowledge` / `events/{id}/resolve` /
  `rules`（GET · POST）/ `notifications` / `detect`（显式触发的检测扫描）。
  权限复用 `catalog.spu.read` / `catalog.spu.update`，**不新增权限码**。
- 页面：`/pricing/anomaly-alerts`（`apps/web/src/app/pricing/anomaly-alerts/page.tsx` +
  `components/pricing/price-anomaly-workspace.tsx` + `price-anomaly-model.ts`）——
  能力声明 → 概览卡 → 筛选与检测触发 → 异常清单（倒挂写「A 低于 B」方向可见）→
  异常详情（判定依据 / 证据 / 相关观测 / 通知 / 确认与消解）→ 观测台账 → 登记观测 →
  检测小结（含不可比站点对）→ 预警通知清单，加载 / 空 / 错误三态齐全。
- 测试：`PriceAnomalyIntegrationTest` **17 例** + `PriceAnomalyRulesTest` **9 例**（合计 26）；
  前端 `price-anomaly-model.test.ts` **28 例** + `price-anomaly-allowlist.test.ts` **5 例**
  + 页面路由约定 9 例。

**关键设计决策**：

1. **模块要自持一张「价格观测台账」**（本批最关键的架构决定）：
   蓝图输入句是「平台价回流；内部价」，而实测集成头 —— channel 的 `listing` 表自己写着
   「**无价格列：售价归 05 定价**」，5.1 价目表（5.1.1.1 / 5.1.1.2）**未交付**、本仓无 `price_list` 表，
   4.3.2.2 平台状态回流**只回流状态、不回流价格** ⇒ **平台价与内部价在本仓此前没有任何事实来源**。
   照 5.2.1.1 对「06 采购单未交付」的先例（改用同库真实到货事实派生 + 能力声明写明口径），
   本模块自持观测台账并由 `POST /observations` 登记进入，**不编造来源**。
   这张表**不是「当前值表」而是时间序列** —— 蓝图第 ① 类异常要判定的正是「**长期**不一致」，
   没有时间序列就只剩「一次就对不上」，而那恰恰是蓝图要求**不要**报的那种。
2. **四类异常的判定 SQL 各一条，且两个坑都不报错、只是把结论算错**：
   ① 连续段必须用**反向计数器**切分（`sum(case when breach = 0 then 1 else 0 end) over (...)`）——
      写成 `sum(breach)` 会让每一行拿到不同的组号 ⇒ 连续段被切成「每段一个点」⇒ **持续天数恒为 0**
      （第一次跑出来的现象就是「跨度 28 天算成 0 天」）；
      且只看**包含最新那次观测**的连续段 ⇒ 最新观测恢复正常时事件自动 RESOLVED。
   ② 成本线取 5.2.1.1 的 `sku_cost_profile.current_revision_id → sku_cost_revision.total_unit_cost`
      （V215，同一服务、同一口径），**判定写进 SQL**（折算到成本线币种后比较，前端不拿浮点比）；
      该 SKU 没有成本构成 ⇒ **不参与判定**（读不到 ≠ 成本为 0）。
   ③ 平台自动降价只看 `AUTOMATIC_PLATFORM`，**我们自己改的（`MANUAL_INTERNAL`）不算**，
      检测小结里如实报出「我们自己改的降价 N 次」。
   ④ 多站点倒挂走**站点两两比对**（行值比较保证每个站点对只出现一次），
      事件上同时存**低的那一边**与**贵的那一边** ⇒ 「德国站低于美国站」一眼可读，
      **不是全平台平均价差**（那个算法会把「德国比美国便宜、但比法国贵」的真实倒挂洗掉）。
3. **跨币种绝不编汇率**：本仓汇率表属 5.2.2.1 / 5.2.2.2（**均未交付**）⇒
   观测行自带比价口径（`comparison_currency` + `fx_rate` + `fx_rate_date` + `fx_rate_source`，
   汇率连同日期与来源一起记在观测行上），没给汇率就只与同币种站点比；
   跨币种的站点对**如实报为不可比**（检测结果里的 `uncomparablePairs`，带 ownerModule 与原因），
   既不静默丢弃也不乘一个编出来的汇率。
4. **事件是状态不是快照**：fingerprint 幂等 ⇒ 重复检测只把同一条事件的持续天数往前推、
   **不堆重复行**；新事件才发一条预警通知（一个事件一条）。
5. **零外键、零 ALTER**：对 sku / 事件的引用校验用**触发器**
   （`guard_price_anomaly_observation_sku` / `_event_sku` / `_notification_event`）——
   与同批 channel V219 同一手法，**既有测试一行未改**、跨车道 DDL 回归面 = 0。
6. **api-proxy 白名单新增一条独立 `services[]` 元素**：首 token `price-anomaly-alerts`
   与既有全部首 token（`products` / `attributes` / `brands` / `parties` / `categories` /
   `sku-cost-compositions` / `listings` / `listing-details` / …）**全不相同**，
   且既有正则都是锚定的（`/^token(...)?$/`）⇒ 两者互不遮蔽；
   刻意**不放行**裸路径 `price-anomaly-alerts`（后端没有这个 GET 端点）
   与任何价格历史形状（5.5.1.1 **未交付**）。

---

## 模块 5.2.1.2 到岸成本

**这个功能做什么**：算出「**货到目标仓可售**」的总成本 = 采购价 + 国际头程 + 关税 + 清关 +
目的地操作费 + 尾程预备，用于海外仓 / FBA 定价；并给出**不同目的地的成本对比**。
最要命的一条是蓝图加粗的那句：**到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），
定价时选错就会用海运成本给自发货商品定价 ⇒ **系统性低估**。

**技术落地**：

- 迁移：`V223__pricing_landed_cost.sql`（catalog）—— 四张新表 `landed_cost_statement` /
  `landed_cost_line` / `landed_cost_component` / `landed_cost_parameter`，
  `tenant_id NOT NULL` + `ENABLE`/`FORCE` RLS + `GRANT`，
  **纯新建四张表、零 ALTER 任何既有表、零个 FOREIGN KEY**（引用校验走触发器）。
- 端点：`/catalog/landed-costs` × **9** —— `capabilities` / 集合 GET（列表）/
  集合 POST（建单）/ `comparisons`（不同目的地成本对比）/ `parameters`（GET · PUT）/
  `{statementId}` 详情 / `{statementId}/components`（六分量明细）/ `{statementId}/recompute`（幂等重算）。
- 页面：`/pricing/cost` 的**「到岸成本」分区**
  （`apps/web/src/app/pricing/cost/` 下的分区，与 5.2.1.1 成本构成是**兄弟分区**，
  口径声明固定在页面顶部 + 台账 + 分量明细 + 参数维护 + 计算）。
- 测试：`LandedCostIntegrationTest` **16 例**（Testcontainers 真实 PostgreSQL 16、连接池压到 1）+
  `LandedCostRulesTest` **7 例**（合计 23）；前端 `landed-cost-model.ts` /
  `landed-cost-workspace.tsx` + `landed-cost-allowlist.test.ts`。

**关键设计决策**：

1. **口径写进表结构与端点响应，不只写在页面上**：
   `landed_cost_statement.cost_basis` 是**只有一个取值的闭集**（`LANDED_TO_TARGET_WAREHOUSE`）——
   有人想往到岸成本里塞一个仓库成本口径，**在 DB 层就被拒**（23514）；
   `excluded_components` 固定写明**不算**的 `STORAGE_FEE` 与 `LAST_MILE_DELIVERY_FEE`；
   每一份响应都带 `basis`（算了哪六项 + 明确不算哪两项），前端把它固定显示在页面顶部。
2. **六分量「每行恒 6 行」，缺项也落行**：
   `landed_cost_component` 每个 SKU 行恒有 6 行分量，缺的标 `available=false` 并写
   `owner_module`（归谁管）+ `impact`（缺了会怎样）。
   DB 级 CHECK 钉死「`available=false ⇒ amount IS NULL`」「`included=true ⇒ amount NOT NULL`」
   ⇒ **「缺口」与「真的是 0」在库里就是两种不同的行**，服务层与前端都不可能把缺口渲染成 0.00。
   用 0 填关税会让到岸成本**系统性低估**，而页面上看不出任何异常 —— 这是本模块最危险的失败模式。
3. **合计由延迟约束触发器在提交时核对**（`guard_landed_cost_totals`，
   `DEFERRABLE INITIALLY DEFERRED`）：行合计、单合计、覆盖度计数三者都核；
   **一个分量都没算出来时合计是 NULL（算不出来）而不是 0**；
   `unit_landed_cost` 用 `GENERATED ALWAYS AS`（`round(total/base_quantity,4)`）—— 写不进去，也漂不了。
4. **承担方由贸易条款决定，不由 `risk_bearer` 决定**：
   `risk_bearer` 说的是**风险转移点**，不是费用承担方 —— DAP 下风险在指定地点转移，
   但**运费仍然是卖方付的**。用 `risk_bearer` 判会把 DAP 下的国际头程**整笔剔掉**，
   而那正是本模块最忌讳的系统性低估。
5. **上游全部跨服务只读，走授权 HTTP 端口**：国际头程取 warehouse `first_mile_shipment`
   （运费 + 保险 + 操作费），关税取 `duty_tax_estimate`（经**报关单**关联：
   发运单 → 报关单 → 关税估算）。服务层**透传调用方自己的 `Authorization` 头**，
   租户由 warehouse 从成员关系解析，**本服务不发明 `X-Tenant-Id`**
   （那等于新建一条「客户端自称租户」的旁路）。
   **上游整体读不到 ⇒ 503 报「读不到」**，绝不返回空集合冒充「没有这笔成本」。
6. **清关费与尾程预备是租户参数，其余四项是上游事实**：
   全仓**没有任何清关费事实**（`customs_declaration` 没有费用列），所以清关费只能是参数；
   尾程预备按定义就是**定价用的预留**，不是尾程派送费实际值（后者归 10.2 / 10.6.1.1）。
   参数按（分量, 目的国, 币种）唯一，**未维护即缺口，不是 0**。
7. **关税与增值税 / GST 分列，但都进合计**：
   `tax_duty_amount` / `tax_other_amount` 分列（10.5.1.3 把 DUTY / VAT / GST 明确分成三笔独立的钱），
   而 `amount` 是**进口环节全部税额** —— 六个分量的闭集里没有单独的 VAT 分量，
   把增值税剔掉就是在给到岸成本漏钱。
8. **跨服务读数用「批次 + 来源单号」而不是「按 SKU 聚合」**：
   5.2.1.1 的 `CostBasisPort` 是按 SKU 聚合的，而到岸成本是**单据级**的 ⇒ 另建 `LandedCostPort`，
   **不复用** `CostBasisPort`。
9. **采购价复用 catalog 同库的「实际到货金额」**：6.3 采购单未交付 ⇒ 用 `vendor_delivery_record`
   （V137）的实际到货金额按移动加权折成基础单位单价，来源单号是**到货单号**而不是采购单号
   （页面与响应都如实标注，不冒充采购单成交价）。
10. **页面路由用 `/pricing/cost` 的「到岸成本」分区，不是 `/pricing/landed-cost`**：
    蓝图功能树里 5.2.1 的 L3 路由就是 `/pricing/cost`，台账路由列也是 `/pricing/cost`，
    而 5.2.1.1 交付时已经在 `COST_SECTIONS` 里留了 `id:'landed'` 的**兄弟分区扩展位**。
    本车道把该分区翻成 `delivered: true` 并接上真实区块，
    并把 5.2.1.1 的分区摘要改成点明「单据级 vs SKU 级」两个口径不合并。

---

## 模块 5.2.2.1 汇率查询

**这个功能做什么**：查询各币种对各本位币的汇率（来源：中行 / 央行 / 平台结算汇率，**可配置**），
展示**汇率日期与来源**，支持**手动录入特定日期的汇率**。
蓝图加粗的那条是：**汇率方向必须显式** ——「1 USD = X CNY」与「1 CNY = X USD」混淆是常见错源。

**技术落地**：

- 迁移：`V225__exchange_rate.sql`（catalog）—— 三张新表
  `exchange_rate_source`（租户级来源配置：code / name / kind（手工 / 中行 / 央行 / 平台结算）/
  enabled / priority / endpoint）· `exchange_rate`（汇率事实：`base_currency` + `quote_currency` +
  `rate` + `effective_date` + 来源）· 拉取日志表，
  **纯新建、零 ALTER 任何既有表、零 FOREIGN KEY**（引用校验走触发器）。
- 端点：`/catalog/exchange-rates` × **14** ——
  `capabilities` / 集合 GET（列表）/ `latest`（最新生效汇率）/ `functional-currencies`（本位币）/
  `sources`（GET · POST）/ `sources/initialize`（幂等初始化四条来源）/
  `sources/{code}`（PUT 改配置）/ 集合 POST（录入一条汇率）/ `{id}`（GET · PUT 改那一条）/
  `fetch`（外部拉取）/ `fetch-log`（拉取日志）。
- 页面：`/pricing/rates`（`apps/web/src/app/pricing/rates/page.tsx` +
  `components/pricing/exchange-rate-model.ts` + `exchange-rate-workspace.tsx`）。
- 测试：`ExchangeRateIntegrationTest` **14 例** + `ExchangeRateRulesTest` **13 例**（合计 27）；
  前端 `exchange-rate-model.test.ts`。

**关键设计决策**：

1. **方向落成结构，不是注释**：`exchange_rate` 同时存 `base_currency` + `quote_currency`，
   `rate` 表示「1 基准 = rate 报价」。少任一列就写不进一条事实，结构上不可能混淆。
   接口与页面的每一处显示都是整句「1 USD = 7.1234 CNY」—— **表格里没有裸的汇率数字列**。
2. **反向汇率由正向显式推导并标注，不落库**：`1/rate`（8 位小数 HALF_UP），
   显示为「1 CNY = 0.1403824 USD（由 1 USD = 7.1234 CNY 推导）」。
   手工再录一条方向相反的会被 **DB 触发器** `guard_exchange_rate_direction` 拒绝（SQLSTATE 23514），
   应用层预检把它翻译成 409 并指出已有的那一条。
3. **同一币种对 + 同一生效日只能有一条**（`UNIQUE(tenant_id,base,quote,effective_date)`）——
   否则「今天的汇率」有多个答案，5.2.2.2 快照与 5.3 测算不知道该冻结哪一条。重复录入 409。
4. **外部拉取真发 HTTP，且刻意不携带用户的 `Authorization` 头**：
   第三方汇率源不是本仓服务，把会话凭据送出去等于泄露凭据 ——
   这一条与 `CostBasisPort`（读 warehouse，**必须**透传）**刻意相反**，已在端口类注释里写明理由。
5. **🔴 失败路径不抛异常**：外部源失败时返回 503 / 400 的**响应体**而不是抛异常 ——
   抛异常会让外层事务回滚，把刚写的**失败日志**一起回滚掉，而失败日志是这次拉取唯一留下的痕迹。
   连不上 / 超时 / 非 2xx / 响应不认 / 没有汇率，五种情况都有明确原因，**不静默返回空**。
6. **本位币经 identity 授权只读端点读**（`GET /identity/legal-entities`，透传调用方 bearer，
   租户由 identity 从成员关系解析）。本位币归**法人**（17.1.2.2），本服务**不**另建本位币表，
   也不从任何请求头猜租户默认币种。读不到 → 503「读不到本位币」，
   不用空集合冒充「没有本位币」。该端点放在**独立路径**上：本位币读不到时，汇率台账仍然可用。
7. **更正 ≠ 新增事实**：同一天同一条汇率记错了改那一条（PUT），不新增第二条；
   更新路径同样过方向守卫与唯一性预检。
8. **汇率表归 catalog 自持**：V215（5.2.1.1）迁移注释里写着「本库没有汇率表（12 财务的资产）」，
   即本模块是这张表的归属方。12 财务（finance 服务）当前不存在 ⇒ 不建 finance 侧表、
   不读写 finance、**不 ALTER 5.2.1.1 的表结构**。

---

## 静态预检（L2 之前）

对本批新迁移跑「已知互相破坏形状」扫描（`V220` / `V222` / `V223` / `V225`）：

| 检查 | V220(crm) | V222(catalog) | V223(catalog) | V225(catalog) |
| --- | --- | --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | 未命中 | 未命中 | 未命中 | 未命中 |
| ② `SET NOT NULL` | 未命中 | 未命中 | 未命中 | 未命中 |
| ③ `ADD CONSTRAINT ... CHECK` | 未命中 | 未命中 | 未命中 | 未命中 |
| ④ `FOREIGN KEY` / `REFERENCES` | 未命中 | 未命中 | 未命中 | 未命中 |
| ⑤ `ALTER TABLE` | 2 处，**均为 DO 块里的 `ENABLE`/`FORCE ROW LEVEL SECURITY`** | 同左 | 同左 | 同左 |

⇒ **零命中**。四条迁移都是**纯新建表**，零 `ALTER TABLE`、零外键 ——
对既有测试的 `TRUNCATE` 回归面 = 0（batch-37 的 FK 事故在本批被彻底避开）。

---

## 并入过程

### 第一批（本 tick 开工时就有 3 条 ready）

| 模块 | 分支 | merge commit | 冲突 |
| --- | --- | --- | --- |
| 15.2.2.1 Request a Review | `feat/15-2-2-1` | `d995ee24` | 无 |
| 5.5.2.1 异常告警 | `feat/5-5-2-1` | `3df9c196` | 无 |
| 5.2.1.2 到岸成本 | `feat/5-2-1-2` | `d6084add` | 无 |

### 第二批（本 tick 期间落地的 4 条）

| 模块 | 分支 | 结果 | 冲突文件 |
| --- | --- | --- | --- |
| 5.2.2.1 汇率查询 | `feat/5-2-2-1` | ✅ 并入 `006b3201` | 无 |
| 5.2.1.3 成本分摊 | `feat/5-2-1-3` | ❌ `conflict` | `cost-composition-model.test.ts`、`cost-composition-workspace.tsx`、`api-proxy.ts` |
| 5.2.3.1 平台费用 | `feat/5-2-3-1` | ❌ `conflict` | 仅 `api-proxy.ts` |
| 5.3.1.1 利润测算器 | `feat/5-3-1-1` | ❌ `conflict` | 仅 `api-proxy.ts` |

`lane.mjs merge-ready` 遇冲突会**自己 `git merge --abort`**（不留下 MERGE_HEAD 残骸）、
把车道标成 `conflict` 并列出冲突文件 —— 工作区保持干净，**没有强行解冲突**。

### 台账完整性

```
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md   → 465
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' ... | sort | uniq -d  → 空（无重号）
```

---

## 并入冲突的根因（3 条车道栽在同一个地方）

**根因：`apps/web/src/lib/api-proxy.ts` 是「共享白名单累积文件」，本仓没给它并集合并驱动器。**

- `.gitattributes` 只给 `packages/api-contracts/src/**` 与 `docs/.../module-progress.md`
  配了 `merge=contract-union` ⇒ **契约与台账自动按键求并**（本批实测：两条都自动合上了）；
  `api-proxy.ts` **不在名单里** ⇒ 走普通 git 三方合并。
- 三条车道各自在 `services[]` 数组**末尾追加一条自己的元素**（这正是任务书要求的做法），
  但它们**基于同一个旧基线 `4983a9cc`** ⇒ 三条分支对数组同一段的追加**互相重叠** ⇒ 顺序合入时逐个撞。
- **实测的元素数**（不是 backlog 里写的 84 —— 那份数已过期）：

| 状态 | `services[]` 元素数 |
| --- | --- |
| 基线 `4983a9cc` | **101** |
| batch-38 的 4 条车道**各自**的分支 | **102**（每条 +1） |
| 并入本批 4 个模块后的当前 head | **105**（101 + review-requests + price-anomaly-alerts + landed-costs + exchange-rates） |
| **解完 3 条冲突后的目标** | **108**（105 + cost-allocations + platform-fees + profit-calculator） |

  ⇒ 三条分支的 `api-proxy.ts` **都是纯追加**（各 +1 条、0 删除），
  冲突纯粹是**插入位置重叠**，语义上**无歧义** ⇒ 完全可以机械求并。
- ⚠️ **漏掉任何一条 = 静默删掉一个已交付模块的路由**，
  而 typecheck / lint / vitest / Playwright **全绿**、页面表现是 404 ——
  这就是本仓反复踩的那个坑。

**⇒ 本 tick 派发一条「只解这 3 条冲突」的修复车道**（不先跑 merge-ready）：
把三条车道分支的 `api-proxy.ts` 都解成**同一个 108 元素的版本**
（105 条既有条目**一字未动** + 三条新元素按固定顺序追加在数组末尾），
把 `cost-composition-{model.test.ts,workspace.tsx}` 解成「5.2.1.2 与 5.2.1.3 两个分区都在」，
修完 commit 回各自车道分支并重新 `land` ⇒ 下一轮 `merge-ready` **干净并入**。

> **结构性解法（仍待决，本轮不做）**：给 `api-proxy.ts` 也配一个按「元素」求并的合并驱动器
> （键 = `services[]` 元素的 `pattern` 源文本；解析不了就**大声失败**，绝不能落到行级去重兜底 ——
> 那会把对象数组拼坏）。另可让兄弟分区各自独立成文件、`COST_SECTIONS` 只存文件名。
> 两条都记在 `docs/backlog/`，**等用户拍板**，调度器不自行改脚本行为。

---

## L2 批量验证

### 第一轮：后端红（trade 1494 例里 1 例 error）

| 阶段 | 退出码 |
| --- | --- |
| `backend && ./mvnw verify` | **1** ❌ |
| 其余五个阶段 | 未执行（前一阶段红即停） |

reactor：`backend`/`tenancy`/`identity`/`catalog`/`channel`/`warehouse` 全过，
**trade FAILURE（08:50 min）**，`crm` SKIPPED。

```
[ERROR] Tests run: 33, Failures: 0, Errors: 1 -- in com.starcon.trade.FulfillmentRequestIntegrationTest
[ERROR] FulfillmentRequestIntegrationTest.完整走通一次履约_从建单到完成:417
  » DataIntegrityViolation: new row for relation "fulfillment_request_step"
    violates check constraint "fulfillment_request_step_check3"
```

### 归因：**跨时钟域**，不是本批回归（本批在 trade 上一个文件都没改）

取证链（每一步都留了命令与输出）：

1. **本批与 trade 无关**：`git diff --name-only 8bcfc215..d6084add -- backend/services/trade/` → **0 个文件**。
   近期也没有并入 `main` 的 merge commit（`git log --merges` 逐条看过）。
2. **隔离复跑全绿**：
   ```
   ./mvnw -pl services/trade -am test -Dtest='FulfillmentRequestIntegrationTest' \
     -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
   → Tests run: 33, Failures: 0, Errors: 0 / BUILD SUCCESS
   ```
3. **那条约束到底是什么**：用一张只含 V152 同款 CHECK 的探针表在 PG 16 上实测
   ⇒ **`fulfillment_request_step_check3` = `CHECK (finished_at IS NULL OR finished_at >= started_at)`**
   （表级 CHECK 的编号规则：第 1 条叫 `<表>_check`，第 2 条 `check1`……第 4 条才是 `check3`）。
4. **两列的时间来自两个不同的时钟**：
   - `started_at` ← **JVM 墙钟**（`FulfillmentRequestService.markStepRunning` 传 `OffsetDateTime.now()`）；
   - `finished_at` ← **DB 时钟**（DB 函数 `complete_fulfillment_step` 里写 `finished_at = clock_timestamp()`）。
   ⇒ **跨时钟域比较**，JVM 比 DB 快哪怕几十微秒就违约。
5. **失败行就是证据**：`started_at = 23:29:54.130301` vs `finished_at = 23:29:54.130226`
   —— **JVM 比 DB 快 75 微秒**。
6. **本机实测 PG 容器与宿主时钟的差**（5 组配对采样）：
   ```
   host=15:38:03.224051  pg=15:38:03.278     (PG 快 ~54ms)
   host=15:38:03.291385  pg=15:38:03.343     (PG 快 ~52ms)
   host=15:38:03.353972  pg=15:38:03.409     (PG 快 ~55ms)
   host=15:38:03.419742  pg=15:38:03.475     (PG 快 ~55ms)
   host=15:38:03.491360  pg=15:38:03.540     (PG 快 ~49ms)
   ```
   稳态下 PG 比宿主快 ~50ms ⇒ 约束**通常**满足；
   但重负载下（本次 L2 与 4 条车道的 L1 并发，**PG 峰值 19 个容器**）
   宿主时钟可能出现一次几十毫秒量级的回跳 ⇒ 约束被击穿。

> ⚠️ **这与 batch-38 记的「跨时钟域断言假红」是同一类缺陷**，
> 但上一次是**测试断言**（拿 JVM `now()` 去比 DB 的 `now()`，只留 2 秒余量），
> 这一次是**落在 DB CHECK 约束里的** —— 后果更重：**生产上会间歇性地拒掉一次合法的履约步完成**。
> ⇒ 本 tick 派一条**只修这一个模块**（9.3.3.1 履约请求）的修复车道：
> 让 `started_at` 与 `finished_at` **来自同一个时钟源**，
> **不允许**简单放宽那条 CHECK（它在防「结束早于开始」的真实错误）。

### 第二轮：重跑全量 L2 —— **后端绿，但前端红（本批引入的真回归）**

第二轮在**机器空载**（4 条车道已全部落地、0 条在途）时跑，PG 不再与车道 L1 抢资源：

| 阶段 | 退出码 |
| --- | --- |
| `backend && ./mvnw verify` | **0** ✅ |
| `pnpm typecheck` | **0** ✅ |
| `pnpm lint` | **0** ✅ |
| `pnpm test` | **1** ❌ |
| Next build / `sync --check` | 未执行（前一阶段红即停） |

后端 reactor **8/8 模块全过**（第一轮被 SKIPPED 的 `crm` 这次也跑到）：

```
backend ...... SUCCESS [  0.001 s]     catalog ....... SUCCESS [03:54 min]
tenancy ...... SUCCESS [  0.414 s]     channel ....... SUCCESS [05:58 min]
identity ..... SUCCESS [01:20 min]      warehouse ..... SUCCESS [10:53 min]
trade ........ SUCCESS [08:20 min]      crm ........... SUCCESS [ 11.753 s]
```

⇒ **上一轮那一例没有复现** ⇒ 跨时钟域的归因成立（空载 ⇒ 不发生时钟回跳）。

**但前端红了 1 条 —— 而且它不是假红，是本批引入的真回归**：

```
FAIL apps/web/src/app/pricing/cost/cost-composition-page.test.ts > 分区块约定 >
     未交付的兄弟分区写明「未交付」并说明口径差异
AssertionError: expected true to be false
 ❯ apps/web/src/app/pricing/cost/cost-composition-page.test.ts:57:31
     57|     expect(landed?.delivered).toBe(false);
 Test Files  1 failed | 327 passed (328)
      Tests  1 failed | 5152 passed (5153)
```

**归因（跨模块回归，落在本批头上）**：

- 5.2.1.2「到岸成本」把 `cost-composition-model.ts` 的 `COST_SECTIONS` 里 5.2.1.2 那条分区
  的 `delivered` 翻成了 `true`（**它确实已交付**，翻得对）；
- 而 **5.2.1.1 交付时留下的** `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`
  里那条用例**硬编码**「5.2.1.2 是未交付分区」⇒ 断言必红；
- 本车道当时更新了**兄弟测试文件** `cost-composition-model.test.ts`（改成「5.2.1.2 已交付」✅），
  **漏掉了 `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`** ——
  因为它的 L1 前端过滤范围是 `apps/web/src/components/pricing` 与 `apps/web/src/app/api`，
  **没有覆盖 `apps/web/src/app/pricing/cost/`**。

⇒ 这正是 L2 这层安全网存在的意义：**车道各自单跑都绿，合并后的集成头才暴露出来。**
⇒ 按协议：**不 push**，派一条**只修这一个测试文件**的修复车道，修复后重跑 L2。

### 第三轮：修复后重跑 —— **全绿** ✅

修复车道 `fix/5-2-1-2-cost-page-assert`（模块 5.2.1.2）把那条用例改写成
**数据驱动的不变量**（不再硬编码「哪个兄弟模块还没交付」）：

```js
for (const section of COST_SECTIONS) {
  if (section.delivered) {
    expect(section.summary, `${section.moduleId} 已交付，摘要不得再写「未交付」`).not.toContain('未交付');
  } else {
    expect(section.summary, `${section.moduleId} 未交付，摘要必须写明「未交付」`).toContain('未交付');
  }
}
```

修复车道用**四个场景**证明新断言既抓得住回归、又扛得住 5.2.1.3 下一轮落地：

| 场景 | 制造的矛盾 | 期望 | 实测 |
| --- | --- | --- | --- |
| A | 5.2.1.2 `delivered`→`false`（摘要无「未交付」） | 红 | `Tests 2 failed / 5 passed` |
| B | 5.2.1.3 `delivered`→`true`（摘要仍写「未交付」） | 红 | `1 failed / 6 passed`，报错带 `5.2.1.3 已交付，摘要不得再写「未交付」` |
| **C** | **模拟 5.2.1.3 完整落地**（三条全 `delivered:true`，摘要无「未交付」无「规则表」） | **绿** | **`Tests 7 passed (7)`** |
| D | 5.2.1.2 摘要「单据级」→「整批口径」 | 红 | `1 failed / 6 passed` |

第三轮结果：

| 阶段 | 退出码 | 备注 |
| --- | --- | --- |
| `backend && ./mvnw verify` | **0** ✅ | 00:16:11，reactor 8/8 SUCCESS，**0 failures / 0 errors**（本轮后端**只跑这一次**） |
| `pnpm typecheck` | **0** ✅ | 修复后重跑 |
| `pnpm lint` | **0** ✅ | 修复后重跑 |
| `pnpm test` | **0** ✅ | **5153 例全绿**（修复前 1 failed / 5152 passed） |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **0** ✅ | `Generating static pages (218/218)` |
| `node tools/module-progress/sync.mjs --check` | **0** ✅ | `✓ 台账与蓝图一致` |

> ⚠️ **为什么后端没有重跑第三轮**（明确记录，别当成漏跑）：
> 后端在 **00:16:11** 那一轮已是绿的（reactor 8/8 SUCCESS / 0 failures），
> 而从那个 commit（`03facdf7`）到最终状态（`76b7a513`）的差异**只有两个文件**：
> ```
> git diff --name-only 03facdf7..76b7a513 -- backend/   →  0
> apps/web/src/app/pricing/cost/cost-composition-page.test.ts
> docs/sessions/2026-10/2026-10-21-cost-page-assert-fix.md
> ```
> ⇒ **零个后端文件**（一个前端测试文件 + 一份会话日志），
> 5955 例后端测试**在物理上不可能被它影响** ⇒ 重跑只是纯粹的发热与 PG 占用。
> 这与协议里「发热是硬约束、用户 10-08 报过温度过高」的取舍一致。
> **本批后端总计只跑了 1 次全量**（与组批协议「每批只跑一次」一致）。

---

## 本批派发的车道

本轮派发 **4 条**（runtime 子任务并发上限 4，已用满）。**没有派发新的功能模块车道** —— 理由见下。

| # | 车道 | 模块 | 迁移 | 分支 / worktree | 目的 |
| --- | --- | --- | --- | --- | --- |
| W0 | 回归修复 | 5.2.1.2 到岸成本 | — | `fix/5-2-1-2-cost-page-assert` / `.worktrees/5.2.1.2-fix` | 修本批引入的跨模块回归（已落地并入 `76b7a513`） |
| W1 | 并入冲突修复 | 5.2.1.3 / 5.2.3.1 / 5.3.1.1 | — | 各自原分支与 worktree | 把三条分支的 `api-proxy.ts` 解成**逐字相同的 108 元素版本**，并解掉 cost-composition 两处冲突 |
| W2 | 缺陷修复 | 9.3.3.1 履约请求 | **V211**（trade） | `fix/9-3-3-1-clock-domain` / `.worktrees/9.3.3.1-fix` | 修**跨时钟域**缺陷：`started_at`（JVM）与 `finished_at`（DB）来自两个时钟源 ⇒ DB CHECK 随机拒掉一次合法的履约步完成 |
| W3 | 缺陷修复 | 4.3.1.1 一键刊登 | —（只改测试） | `fix/4-3-1-1-clock-assert` / `.worktrees/4.3.1.1-fix` | 修 batch-38 记下的**跨时钟域断言假红**：`dispatched_at = now()`（PG 事务开始时刻）与 JVM 墙钟只差 2 秒余量 |

### 为什么本轮**不**派发新的功能模块车道

不是没活可干（未开发 215），而是**派了也注定下一轮并入冲突**：

- 下一轮要并入的三条车道（W1 修好之后）的 `api-proxy.ts` 会是**逐字相同的 108 元素版本**；
- 一条**现在**派发的新功能车道，它的 `api-proxy.ts` 必然是「当前 head 的 105 条 + 它自己那 1 条」= 106 条；
- 本轮用 `git merge-tree` 在临时仓库里做了三组预演，结论是：
  **只有「两边逐字完全相同」才能干净合并**；让一边是另一边的**超集**照样冲突。

⇒ 新派的车道**必然**在这一个文件上撞，而撞了就得再等一轮修复 —— 白烧一条车道位和一个 Flyway 版本号。
⇒ 本轮把 4 个并发名额全部用在「**解冲突 + 修两个跨时钟域缺陷**」上，
它们**直接换来下一轮 3 个模块的干净并入** + 2 个缺陷修复，比派 4 条注定撞车的功能车道划算。

⇒ **结构性解法（api-proxy.ts 的并集合并驱动器）已记入
`docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`，等用户拍板后单独派一条工具链车道。**

---

## 收尾三件套

| # | 动作 | 结果 |
| --- | --- | --- |
| ① | 写说明（comment） | ✅ 本文件 `docs/sessions/2026-10/2026-10-21-batch-39-merge-4-lanes-and-l2.md`（+ 3 份 backlog 记录） |
| ② | commit（说明 + 台账**同一个 commit**） | ✅ `docs(batch-39): …` —— 说明 + 台账行 + backlog 同 commit |
| ③ | push `origin integration/module-dev` | ✅ **L2 全绿之后执行** |

### 本批 push 的 commit

| commit | 内容 |
| --- | --- |
| `d995ee24` | `merge(15.2.2.1): Request a Review` |
| `3df9c196` | `merge(5.5.2.1): 异常告警` |
| `d6084add` | `merge(5.2.1.2): 到岸成本` |
| `006b3201` | `merge(5.2.2.1): 汇率查询` |
| `76b7a513` | `merge(5.2.1.2): 到岸成本`（回归修复车道并入） |
| `docs(batch-39)` | 本批说明 + 台账计数器（247 / 3 / 215）+ 3 份 backlog 记录 |

### 本轮台账

```
已交付 247 · 进行中 3 · 未开发 215   （465 个 L4 模块，53.1% 已交付）
```

「进行中 3」= 本批并入冲突的那三条（`5.2.1.3` / `5.2.3.1` / `5.3.1.1`）——
台账里写明了「阻塞：并入冲突 + 成果已落盘在哪个分支 + 下一轮干净并入」，
**编排脚本 `lane.mjs plan` 见到证据列以「阻塞：」开头会跳过它们**，所以下一轮不会重复排车道。

---

## 本轮落进 backlog 的三件事（需要用户拍板或后续处理）

| 文件 | 优先级 | 内容 |
| --- | --- | --- |
| `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` | **P0** | `api-proxy.ts` 共享白名单文件没有并集合并驱动器 ⇒ 每批必然冲突。含「只有逐字相同才能干净合并」的实验结论、108 元素的目标数、两条结构性解法（**待决**） |
| `docs/backlog/2026-10-2026-10-21-l1-coverage-gap-shared-test-files.md` | P1 | L1 收窄到「本模块」后，**共享文件的兄弟测试文件**可能落在过滤范围外 ⇒ 跨模块回归漏到 L2 才爆。含两条建议（任务书加一条硬性自检 / 把「共享符号 → 测试文件清单」脚本化） |
| `docs/backlog/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md` | **P0** | 跨时钟域的第 2 例，且这次是 **DB CHECK 约束** ⇒ 生产上会间歇拒掉一次合法的履约步完成（不只是测试假红） |

### 还有一个已知问题留给下一轮

batch-38 记下的 **channel 跨时钟域断言假红**（`ListingPublishJobIntegrationTest.dispatchedAtRoundTrips`，
根因是拿 JVM 墙钟去比 PG 的 `now()`、只留 2 秒余量）本轮由 W3 修复车道处理。
**它此前已让 batch-38 赔掉一整轮**，W3 修完并入后，L2 的假红概率会明显下降。
