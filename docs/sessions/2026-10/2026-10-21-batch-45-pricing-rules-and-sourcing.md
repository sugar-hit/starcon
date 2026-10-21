# batch-45 · 价格规则 / 批量调价 / 补货建议 / 供应商资质

- **日期**：2026-10-21 06:00（Asia/Shanghai）
- **集成头**：`integration/module-dev` @ `4ebff806`
- **本批回收**：4 条 ready 车道全部并入成功，合并区间 `67a23147 → 4ebff806`
- **本批派发**：4 条新车道（V240–V243），后台开发中，下一轮回收
- **L2 批量验证**：见文末「L2 结果」

| 模块 | 名称 | 阶段 | 服务 | 迁移 |
| --- | --- | --- | --- | --- |
| 5.3.2.2 | 价格规则 | D6 | catalog | `V236__pricing_rules.sql` |
| 5.4.1.2 | 批量调价 | D6 | catalog | `V237__batch_price_adjustment.sql` |
| 6.2.1.2 | 补货建议 | D6 | trade | `V238__replenishment_suggestion.sql` |
| 6.1.2.3 | 供应商资质 | D6 | trade | `V239__supplier_qualification.sql` |

---

## 模块 5.3.2.2 价格规则

**这个功能做什么**：把「什么时候该改价、改多少、作用于哪些商品」写成可配置的规则。运营在后台维护一组规则
（库存低于阈值、成本上涨、竞品降价、毛利低于下限、库龄超期），系统跑一次评估就把命中记录和**建议调价**
逐条落库；高风险的建议必须人工确认或驳回，确认后才允许后续执行。评估**本身不改价**。

**技术落地**：

- **迁移**：`backend/services/catalog/src/main/resources/db/migration/V236__pricing_rules.sql`
  - 四张新表：`pricing_rule`（规则）/ `pricing_rule_scope`（作用范围）/ `pricing_rule_hit`（命中记录）/
    `pricing_rule_suggestion`（建议调价）
  - 全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY`，命中记录与建议调价的 `data_scope` 跟随 SKU
  - **零外键**，引用校验一律用触发器 `guard_pricing_rule_scope` / `_hit` / `_suggestion`（避开 batch-37 的 TRUNCATE 坑）
  - 金额 `numeric(19,4)`、费率 `numeric(9,6)`、数量 `numeric(19,6)`，时间 `timestamptz` + `?::timestamptz`，全程 BigDecimal 零浮点
  - **触发条件与动作各用专属列 + CHECK 互斥**（五类触发条件各一列，调价幅度只对降价/涨价存在，作用范围四类各一列）
- **端点**：11 个挂 `/catalog/pricing-rules` —— `GET /capabilities`（能力声明：触发条件 / 动作 / 作用范围 / 状态四张词表
  + 上游真实交付状态）、`GET /directory`（作用范围目录：内部分类 + 品牌）、`GET /rules`（真分页 + 关键词 / 触发条件 /
  动作 / 作用范围筛选）、`GET /rules/{id}`、`POST /rules`、`PUT /rules/{id}`（乐观锁，版本对不上 409）、
  `DELETE /rules/{id}`、`POST /evaluate`（跑一次评估 → 命中记录 + 建议调价列表）、`GET /hits`（真分页 + 规则 / SKU /
  是否命中筛选）、`GET /suggestions`（真分页 + 规则 / SKU / 状态 / 是否需人工确认筛选）、`POST /suggestions/{id}/decide`
  （人工确认 / 驳回）
- **页面**：`/pricing/rules` —— `pricing-rule-workspace.tsx` + 纯函数 `pricing-rule-model.ts`
  （能力声明常驻顶部 / 规则台账真分页三维筛选 / 规则表单（条件 + 动作 + 优先级 + 生效时段 + 作用范围 + 自动放行阈值）/
  评估面板 / 命中记录含未命中的判定留痕 / 建议调价列表 + 人工确认驳回；加载态、错误态、空态三态齐全）
- **测试**：`PricingRuleRulesTest` **20 例**（五类阈值判定方向、取不到数返回 null 而不是 false、建议价算术、变动幅度带符号、
  风险闸三个条件、生效时段左闭右开、精度）+ `PricingRuleIntegrationTest` **12 例**（真实 PG16 Testcontainers，连接池压到 1：
  落库 + 生效时段带 +08:00 时刻写回零漂移、乐观锁 409、库存低于阈值命中并产出建议价 `95 = 100 × (1 − 5%)`、
  毛利 `59.75` 来自 5.3.1.1 对建议价的复核、降价超阈值需人工确认且确认后不可改判、驳回必须写原因、优先级先命中者优先
  （另一条标 superseded）、成本上涨需要两版完整快照否则不判定、库龄取不到则不判定、负毛利如实落库、取不到当前价则留痕
  但不产出建议价、未命中也留痕、评估不改价、只读角色评估 403）
  + `PricingRuleTenancyIntegrationTest` **4 例**（多租户三断言：两租户互不可见 / 伪造 `X-Tenant-Id` 头无效 /
  连接池归还后不串租户）+ `pricing-rule-model.test.ts` **24 例** + `pricing-rule-allowlist.test.ts` **5 例**

**关键设计决策**：

- **「取不到数」一律判 `null`（不判定），绝不判 `false`**。成本不完整算出的涨幅是假的；
  用 false 会让规则「命中」并据此产出错误建议价。这是「不判定」与「判定为否」的分界线。
- **竞品降价用「平台在售价的下降幅度」作可测口径**，并在能力声明里写明它**不是竞品价**，
  `ownerModule` 指向未交付的竞品价模块。本仓没有竞品价采集模块，
  与其造一个假竞品价源，不如给一个诚实但口径明确的可测代理。
- **库龄超期规则新建 `catalog → warehouse` 的 `InventoryAgingPort`**（仿 `CostBasisPort` + `CostBasisConfiguration`
  的 Http / Unwired 双实现 + `@Value("${WAREHOUSE_URL:}")` 装配，透传调用方 `Authorization` 头，
  租户由 warehouse 从成员关系解析）。不跨库查表。
- **建议价算术全部委托给已交付的 5.3.1.1 利润测算器复核**，本模块不重写一套毛利算法。

---

## 模块 5.4.1.2 批量调价

**这个功能做什么**：选中一批商品（SKU × 店铺），统一改价 —— 固定金额、百分比或按定价模板。
**必须先看影响预览再执行**：预览会告诉你涉及多少店铺、多少商品、哪些会被跳过、
**改完之后预计亏损的商品数**、预计利润变化；确认无误才能真正执行。

**技术落地**：

- **迁移**：`backend/services/catalog/src/main/resources/db/migration/V237__batch_price_adjustment.sql`
  - `batch_price_adjustment`（批量调价单：调价方式 + 目标集合 + **影响预览报告快照**）/
    `batch_price_adjustment_item`（目标集合里的一个 listing = SKU × 店铺：新旧价 + 改价前后利润 + 亏损标记）
  - `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY`，明细表 `data_scope` 跟随 SKU
  - **零外键**，引用校验用触发器（避开 TRUNCATE 坑）；**零 ALTER 既有表**
  - 金额 `numeric(19,4)`、数量 `numeric(19,6)`、时间 `timestamptz`，全程 BigDecimal 零浮点
- **端点**：7 个挂 `/catalog/batch-price-adjustments` —— `GET /capabilities`、`GET /sites`（目标店铺来自 2.1.1.1
  平台目录，**上游不可达 ⇒ 503 而不是空列表**）、`POST /preview`（影响预览，**无副作用**）、
  `POST /`（执行批量调价，**必须带预览令牌**）、`GET /`（调价单台账，真分页）、`GET /{uuid}`（详情）、
  `GET /{uuid}/items`（明细，真分页 + 真筛选：状态 / 亏损 / 关键字 / 排序）
- **页面**：`/pricing/adjust/batch` —— `batch-price-adjustment-section.tsx` 真实工作区
  （能力声明 + 调价参数 + 影响预览报告 + 逐 listing 新旧价与前后利润 + 调价单台账 + 明细真筛选真分页；
  三态齐全；「确认执行」在没看过预览之前禁用）
- **测试**：`BatchPriceAdjustmentIntegrationTest` **15 例**（真实 PG16 Testcontainers + 强制 RLS、连接池固定 1 条连接：
  **预览一行都不写**、预览报告与反向指标、执行后调价单与明细落库且与预览逐字一致、与 5.3.1.1 单次测算逐字一致、
  改完预计亏损商品数、固定金额与百分比按两位小数落定、按模板方式用 5.3.2.1 的建议价当新价（35）、
  **预览令牌对不上 ⇒ 409 拒绝执行**、令牌对同一份输入稳定、四种跳过规则、跨店铺报出涉及多少店铺、
  HTTP 端点通、只读角色不能发起调价、timestamptz 写回零漂移）
  + `BatchPriceAdjustmentTenancyIntegrationTest` **3 例**（两租户互不可见且**按主键直查对方行读到零行**
  —— 每个零行都配一条同样写法的正对照读到 1 行，防假绿；伪造 `X-Tenant-Id` 头无效；连接池压到 1 时 5 轮交替读写不串租户）
  + `BatchPriceAdjustmentRulesTest` **11 例** + `batch-price-adjustment-model.test.ts` **18 例**

**关键设计决策**：

- **「必须先看预览再执行」落成三道闸**：① 预览跑在一个**注定回滚**的事务里
  （`previewRollback` + `setRollbackOnly()`），**结构上不可能落库** —— 连 5.3.2.1 的应用留痕都不写
  （预览阶段留一条「模板被应用过」的留痕是**假事实**）；② 预览给出**内容哈希令牌**
  （SHA-256 over 规范化参数 + 逐行前后对比，**不含随机量与时间戳** ⇒ 同一份输入永远算出同一个令牌）；
  ③ 执行时服务端**当场重算**一遍预览并逐字比对，对不上 ⇒ **409 拒绝**。绝不照着过期的数字改价。
- **listing 定义成 (SKU × 店铺)**：本仓 catalog **没有** listing 本地投影（listing 事实属 channel 的 4.1.1.1），
  AGENTS.md 禁止跨服务查表 ⇒ **不新建任何 listing 影子表假装上游已交付**。
  当前价取 5.5.2.1 价格观测台账的**最新一条**观测（台账只追加，「最新一条」是一个时间点而非「当前价」），
  取不到就整行跳过（**绝不用 0 顶**），跨币种整行跳过（**绝不按 1:1 折算**）。
- **反向指标必须出现在预览里**：蓝图加粗句「预览要包含『改完后预计亏损的商品数』这类反向指标」。
  预览同时给出改价前 / 改价后的亏损 listing 数、改价后预计亏损的**去重 SKU 数**与预计利润变化；
  成本不完整时利润变化给 `null`，**而不是把没测准的行当 0**。
- **一次最多 200 个 listing**，超限**明确报错**不静默截断 —— 截断了运营会以为「全改过了」。

---

## 模块 6.2.1.2 补货建议

**这个功能做什么**：算出每个 SKU 该进多少货。配置覆盖天数与起订额 / 生产周期后，系统跑一次计算，
给出**逐 SKU 的建议量**（含取整说明、择优依据、预计到货日），并把**每一步计算过程拆解落库**，
让人能追问「为什么是这个数」。运营可以对建议接受 / 调整 / 忽略，每种处置都留痕。

**技术落地**：

- **迁移**：`backend/services/trade/src/main/resources/db/migration/V238__replenishment_suggestion.sql`
  - **六张新表、零 ALTER 既有表、零跨库 FK / 视图**：
    `replenishment_coverage_policy`（覆盖天数配置，SKU / 分类 / 租户默认三档，**新配置收口旧配置**而不是覆盖）·
    `replenishment_order_policy`（采购起订额 + 生产周期，按供应商 / 供应商角色 / 租户默认配置）·
    `replenishment_suggestion_batch`（一次计算 = 一个批次，带评估基准日 / 兜底覆盖天数 / 建议数 / 已处置数）·
    `replenishment_suggestion`（逐 SKU 结论：覆盖口径来源 / 目标库存 / 可用 / 在途 / 备货期内可到达在途 /
    净需求 / MOQ / 采购单位 / 箱规 / 建议量 / 取整说明 / 阶梯单价 / 预计费用 / 建议供应商 / 交期 /
    生产周期 / 预计到货日 / 到货依据 / 建议类别 / 状态 / 处置留痕 / 排序位次）·
    `replenishment_suggestion_step`（**计算过程逐项拆解**）·
    `replenishment_suggestion_decision`（处置留痕）
  - 全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY` + `GRANT trade_app` / `REVOKE starcon_app`
  - **计算结论由触发器冻结**（已算出的结论不可被直接改库）
- **端点**：10 个挂 `/trade/replenishment-suggestions`（jOOQ 显式 SQL）—— `GET capabilities`（各输入真值与缺口 +
  算式原文 + 「头程时效未交付」）、`GET/POST coverage-policies`、`GET/POST order-policies`、`POST batches`（发起计算）、
  `GET batches`（历史批次，真分页）、`GET suggestions`（真分页 + 真筛选：类别 / 状态 / 关键字 / 只看可处置）、
  `GET suggestions/{id}`（详情：结论 + 逐项计算过程 + 取整说明 + 择优依据 + 到货依据 + 处置留痕）、
  `POST suggestions/{id}/decision`（接受 / 调整 / 忽略，**处置说明必填**）
  - 权限 `trade.replenishment.read` / `trade.replenishment.update`（沿用 trade 既有权限码体系，**identity 零改动**）
- **页面**：`/purchase/demand/replenishment`（**不覆盖 6.2.1.1 的 `/purchase/demand`**）
  + `components/purchase/replenishment-suggestion-workspace.tsx` + `replenishment-suggestion-model.ts`：
  口径常驻说明 → 头程时效缺口告警 → 概览卡 → 筛选与发起计算 → 建议清单 → 每条可展开看计算过程 / 取整说明 /
  择优依据 / 到货依据 → 处置弹窗 → 覆盖天数与起订额配置区 → 历史批次区；**三态齐全且文案各不相同**
- **测试**：`ReplenishmentSuggestionCalculationTest` **13 例**（取整三步 / MOQ / 起订额 / 阶梯开口取价 / 跨币种不比价）
  + `ReplenishmentSuggestionIntegrationTest` **28 例**（实跑 Testcontainers PG16：结果逐字段落库、五种类别各自可复现、
  **只有 REPLENISH 可处置**、三种处置各留痕、**timestamptz 往返逐字相等**、真分页、触发器挡改写计算结论）
  + `ReplenishmentSuggestionTenancyIntegrationTest` **4 例**（三断言 + **六张表 RLS ENABLE + FORCE 逐表断言**）
  + 前端 `replenishment-suggestion-model.test.ts` + `replenishment-suggestion-allowlist.test.ts` **19 例**

**关键设计决策**：

- **覆盖天数与起订额配置「收口」而不是「覆盖」**：带生效期，新配置只对之后生效的区间起作用，
  口径**不可事后改写**。否则历史批次重算会得到不同答案，「当时为什么这么算」永远答不出来。
- **计算过程逐项落库**（`replenishment_suggestion_step`）是硬要求：建议量必须可追问，
  结论由触发器冻结、只能通过显式的处置流程改。
- **api-proxy 白名单新增一条独立 `services[]` 元素**（`prefix: 'trade/'` + `env: 'TRADE_URL'` + 独立正则），
  **既有 trade 长正则一字未动、不并入任何 alternation**；插入锚点固定在 `order-address-payment-check` 之后、
  `fulfillment/split` 之前；刻意不放行裸路径与未交付形状。
- **已知下游缺口如实写在能力声明里**：头程时效（10）未交付 ⇒ 预计到货日不含头程段；
  供应商评级（6.1.1.3）未交付 ⇒ 不按评级择优；6.2.1.3 采购计划 / 6.2.2.1 采购申请未交付 ⇒
  **只产出建议与处置留痕，不代它们建单**。

---

## 模块 6.1.2.3 供应商资质

**这个功能做什么**：管理供应商的资质证件（营业执照、ISO、CE、FDA、品牌授权书等）——
登记、审核、续期提醒，并**回答「这个供应商现在能不能卖这个牌子 / 这个商品」**，
把结论（阻断 / 不阻断 + 原因码 + 人话）交给刊登与发货链路消费。

**技术落地**：

- **迁移**：`backend/services/trade/src/main/resources/db/migration/V239__supplier_qualification.sql`
  - **五张新表、零 ALTER 任何既有表**（跨车道 DDL 回归面 = 0）：
    `supplier_qualification`（主表：编码 + **catalog SUPPLIER 角色逻辑 ID + 名称快照** + 资质类型 + 证件名称 +
    证书编号 + 发证机构 + **03 品牌 id + 品牌名快照** + 审核状态 + 有效期起止 + 乐观锁 version）·
    `supplier_qualification_document`（**只存对象存储 key，文件本体不入库**；续期是再上传一份不是覆盖）·
    `supplier_qualification_review`（审核流转事件，**只追加**）·
    `supplier_qualification_reminder`（过期提醒，EXPIRING / EXPIRED **分两类**，幂等生成）·
    `supplier_qualification_policy`（租户级策略：即将过期窗口天数 + 必备资质类型闭集）
  - 全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY` + `GRANT trade_app` / `starcon_app`
- **端点**：`/trade/supplier-qualifications/**`（capabilities / options / overview / policy / qualifications /
  reminders / suppliers / brands / 审核与文档子路径 / blocking-decision 三维）：
  - `GET /suppliers/{supplierRoleId}/blocking-decision`（+ `/brands/{brandId}/blocking-decision` 品牌维度）
  - `GET /skus/{skuId}/blocking-decision`（商品维度；「相关商品」的供应商取自 6.1.2.1 已交付的
    `supplier_source_sku_link → item → stall → supplier_party_id`，**同库同服务只读**，不另建第二套关系）
  - 原因码：`QUALIFICATION_EXPIRED` / `_REVOKED` / `_MISSING` / `_NOT_YET_EFFECTIVE` /
    `BRAND_AUTHORIZATION_MISSING` / `_EXPIRED` / `_REVOKED`
- **页面**：`/supply/sources`（`supplier-qualification-*` 组件）；权限码 `trade.qualifications.*` 在本仓**没有**
  在 identity 注册 `page_resource` —— 这是 trade 全服务的既有缺口，本车道沿用兄弟模块做法**不改 identity**，
  以免与其他车道撞迁移号
- **测试**：`SupplierQualificationIntegrationTest` **31 例** + `SupplierQualificationTenancyIntegrationTest` **3 例**
  （真实 PG16 + Testcontainers，连接池压到 1）：有效期**写回值逐字相等**（比的是**时刻**不是字符串）、
  只给日期时起点取当日 00:00 / 终点取当日最后一微秒、只存逻辑 ID + 名称快照、授权书必须关联品牌、
  供应商不存在时登记失败、编码租户内唯一、**到期状态四态按真实日期算**、提醒窗口来自租户策略、
  审核状态机六条边走通且逐步留痕、**审核流转与资质文件直接改库被触发器挡下**、审核状态机在 DB 层也钉死、
  **提醒扫描把即将过期与已过期分成两条且幂等**、**即将过期不阻断、已过期阻断、已撤销阻断、必备资质缺失阻断**、
  品牌授权缺失与过期阻断、**续期不误伤**（旧件过期但新件有效时不算阻断）、商品维度阻断经 6.1.2.1 解析出供应商、
  商品不存在时显式失败、概览把即将过期与已过期分成两个数、五张表 relforcerowsecurity、HTTP 契约 401

**关键设计决策**：

- **判定单位是「要求」不是「单份证件」**：同供应商 + 同类型（品牌授权再加同品牌）归一组，
  组内有任意一份**当前有效**即满足 ⇒ 续期新增一份后旧件自然过期，**不能因为旧件过期就把他拦下**。
  「即将过期」**不阻断**（仍在有效期内，拦下来是误伤），只有已过期 / 已撤销 / 必备资质缺失或尚未生效才阻断；
  非必备类型缺失**不阻断**。
- **资质类型是受控词表，不是自由文本**：闭集 = 6.1.1.2 附件资质闭集
  （`BUSINESS_LICENSE` / `ISO9001` / `ISO14001` / `CE` / `FDA` / `COC` / `OTHER` **一字不改**）
  ∪ 蓝图点名的三个（`ROHS` / `QUALITY_INSPECTION_REPORT` / `AUTHORIZATION_LETTER`），
  重名取值**共用同一个码** ⇒ 同一个词在两处能对上号。
- **供应商与 03 品牌都在 catalog（另一个库）⇒ 只经授权只读 API**：`SupplierPartyQueryPort`（读 6.1.1.1）、
  `BrandQueryPort`（读 3.3.2 品牌，**本车道新增**）、`QualificationSkuQueryPort`（读 catalog SKU 详情，**本车道新增**）。
  取不到一律 503 且零落库，**不返回空清单冒充「这个租户一个供应商都没有」**。
  SKU 判据**故意不要求 ACTIVE** —— 已停售的 SKU 恰恰最需要阻断结论，
  把它查不出来等于「查不到就不阻断」，那是把安全默认值写反了。
- **库里只存逻辑 ID + 名称 / 编码快照，零跨库 FK / 零跨库 SQL / 零跨库视图**。
  授权书**必须关联 03 品牌**（DB CHECK 钉死 —— 没有品牌就答不出「这个供应商能不能卖这个牌子」）。

---

## 调度器本轮记录

### 静态预检（互相破坏形状扫描）

对本批 4 个新迁移跑「已知互相破坏形状」检查：

| 检查项 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **零命中**（V238 里唯一命中是注释文本） |
| `SET NOT NULL` | **零命中**（同上） |
| `ADD CONSTRAINT ... CHECK` | **零命中** |
| `ALTER TABLE` | 只作用于**同迁移内新建的表**（RLS enable），无既有表 ALTER |
| RLS | 4 个迁移全部含 `ENABLE` / `FORCE ROW LEVEL SECURITY` |

### api-proxy 白名单并集校验

并入 `api-proxy.ts` 时脚本提示「走了行级兜底并集，请人工确认这个文件改对了」，已人工核对：

- 变更量 **+211 行 / −0 行**（**零删除** ⇒ 没有任何既有路由被抢）
- 新增 **4 条独立正则元素**：`replenishment-suggestions/…`、`supplier-qualifications/…`、
  `pricing-rules/…`、`batch-price-adjustments/…`（各车道一条，未并入任何既有 alternation）
- 既有 `batch-calculations` / `pricing-templates` / `profit-calculator` / `price-anomaly-alerts` 等照旧命中

### L2 结果 —— **全绿**

批量验证在集成头上跑**一次**（组批模式的核心收益：N 条车道只付一次全量验证成本）：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | **BUILD SUCCESS**，**6532 例**（270 + 1885 + 1134 + 1535 + 1638 + 70）**0 失败 0 错误**，耗时 **33:49** |
| 类型检查 | `pnpm typecheck` | **exit 0**（3 packages） |
| Lint | `pnpm lint` | **exit 0** |
| 前端全量 | `pnpm test` | **356 文件 / 5516 例全绿**，exit 0 |
| Next 生产构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **exit 0** |
| 契约/台账 | `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致**，exit 0 |

本批 4 个模块的测试在全量运行中逐类复跑全绿：
`BatchPriceAdjustmentIntegrationTest` 15 + `BatchPriceAdjustmentTenancyIntegrationTest` 3 +
`PricingRuleRulesTest` 20 + `PricingRuleTenancyIntegrationTest` 4 +
`ReplenishmentSuggestionIntegrationTest` 28 + `ReplenishmentSuggestionTenancyIntegrationTest` 4。

**跨车道互相破坏：未发生** —— 这正是「每批只跑一次全量」要抓的那一层，本批零命中。

### 本轮的车道心跳与容器观测

- 本批 4 条新车道心跳全部落在集成头状态文件（占位符替换正确），无失联、无崩掉重派。
- L2 期间同机 Testcontainers `postgres:16-alpine` 峰值 **5 个**（含并行车道各自的容器），
  车道 JVM 退出后由 **ryuk** 回收；无标签孤儿容器 **0 个**
  （另有 1 个 `langfuse-postgres-1`（`postgres:15-alpine`）属本机常驻基础设施，与流水线无关，未计入）。

### 本批派发（后台开发中，下一轮回收）

| 模块 | 名称 | 阶段 | 服务 | 迁移 | 分支 | 本轮末状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 5.4.1.1 | 调价单 | D6 | catalog | `V240` | `feat/5-4-1-1` | **已 land**（`f1b0c421`） |
| 5.5.1.1 | 价格历史 | D6 | catalog | `V241` | `feat/5-5-1-1` | **已 land**（`64afd819`） |
| 6.2.1.3 | 采购计划 | D6 | trade | `V242` | `feat/6-2-1-3` | 开发中（L1 模块闸已绿） |
| 10.4.2.2 | 备货预报 | D7 | channel | `V243` | `feat/10-4-2-2` | **已 land**（`b1817711`） |

已 land 的 3 条车道**未并入集成头** —— 并入由下一轮 tick 的 `merge-ready` 统一做，
并入后会经历**下一次 L2 批量验证**才 push。本轮 L2 覆盖的是**本批并入的 4 个模块**。

### 调度者本轮的一处判断纠正（记录在案）

首轮任务书里判定「审批（17 组织）尚未交付 ⇒ 用 Unwired 端口承接」，**该判定有误**：
`backend/services/identity/` 已交付完整审批能力，且 `V83__approval_flow_definition.sql` 出厂注册的
`approval_document_type` 第 5 项就是 `('PRICE_CHANGE','调价单', …)`。
已在车道运行中追加纠正（`POST /identity/approval-rules/evaluate` 试算 +
`POST /identity/approval-instances` 提交实例 + 拉取式状态同步），
避免 5.4.1.1 落成一个「未接线假降级」的半成品。
