# 5.2.1.1 成本构成（D6 阶段，L3 锚点 5.2.1）

> 车道 `feat/5-2-1-1`，worktree `.worktrees/5.2.1.1`，基线 `integration/module-dev`。
> 迁移 `V215__sku_cost_composition.sql`（catalog 服务，版本号由调度者预分配）。
> 页面路由 `/pricing/cost`（与 5.2.1.2 / 5.2.1.3 共用）。

## 续写说明（两次崩溃后的第三次派发）

这条车道崩过两次，两轮的代码**都还在 worktree 里且全部未提交**：

- **第一个 worker**（batch-34，11:00）写完 `CostBasisPort` / `SkuCostCompositionModels` / V215 迁移，
  死在最早期（网络错误）。
- **第二个 worker**（batch-35，12:14）修掉继承代码里的编译错、补上 `deliveries()` 的正式实现、
  修了分页截断漏洞、写了配置类 / 规则引擎 / 服务 / 控制器，**L0 编译已通过**，
  12:29 死在写规则单测的过程中。

第三个 worker（本次）**没有推倒重来**：逐个读完 8 个继承文件判断完成度，
在继承代码上就地续写。实测结果：

| 继承文件 | 处理 |
| --- | --- |
| `CostBasisPort.java` | **保留 + 改**：修累加器 NPE（见下）、其余沿用（透传 Authorization / 超时上限 / 503 / Unwired 兜底 / 不返回空集合冒充无数据全部保持原样） |
| `SkuCostCompositionModels.java` | **保留**：词表、能力声明、列表 / 明细 / 写模型齐备，未改 |
| `V215__sku_cost_composition.sql` | **保留**：三表 + `tenant_id NOT NULL` + RLS（`FORCE` 也在）+ 零个指向 warehouse 的外键 |
| `CostBasisConfiguration.java` | **保留**：未配 `WAREHOUSE_URL` ⇒ `UnwiredCostBasisPort` 显式抛错 |
| `SkuCostCompositionRules.java` | **保留**：三种成本法齐全，`BigDecimal` + scale 4 + `HALF_UP` 钉死 |
| `SkuCostCompositionService.java` | **保留 + 改**：修快照窗口、补乐观锁校验、补时间戳转换、补原因校验 |
| `SkuCostCompositionController.java` | **保留**：7 个端点齐备 |
| `SkuCostCompositionRulesTest.java` | **保留**：实测**并未截断**，9 个用例完整，直接通过 |

## 决策摘要

蓝图 §5.2.1.1 的成立与否全在一句加粗上：

> **「成本口径必须在页面上明示用了哪种成本法」，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。**

⇒ 这句话被拆成**四个必须同时成立**的落点，缺一个都算没做：

1. **成本法参与算术，不是一个下拉框**。三种成本法作用在同一份到货事实上，
   给出**三个不同的**单位成本。算法集中在 `SkuCostCompositionRules`，
   **列表 / 明细 / 历史快照三处共用同一份实现** —— 三处各算一次的话，
   迟早出现「明细显示 12.00、历史快照里却是 12.50」这种没法解释的对不上。
2. **成本构成记录带成本法**（`sku_cost_profile.cost_method`，枚举只增不改：它会进历史快照）。
3. **页面显式显示当前用的哪种成本法 + 生效时间**（`/pricing/cost` 顶部「成本口径」块，
   台账每一行也带成本法角标）。
4. **成本法变更留历史**。每次变更追加一条 `sku_cost_revision` 快照
   （含当时的成本法 / 币种 / 五个分项的当时值 / 当时的合计 / 生效时间 / 变更原因），
   旧版本**原样留着**，没有 UPDATE 端点。

**测试必须带反例**：同一 SKU 在三种成本法下采购成本分别是 15.00 / 10.00 / 20.00，
且这三个数**能在成本变动历史里逐条查到**（`SkuCostCompositionIntegrationTest` 的
`同一SKU在不同成本法下采购成本不同且历史可查`）。只断言「当前值不同」不够 ——
历史里查不到，口径变更仍然无法解释。

## 采购成本的来源：实际到货金额，不是采购单成交价

蓝图说「采购成本来自 06 采购的实际单价」。本仓的真实状态是：

| 表 | 是什么 | 能不能当「采购成本」 |
| --- | --- | --- |
| `vendor_delivery_record`（V137） | **到货事实**：`delivery_ref` / `sku_id` / `quantity` / `amount numeric(19,4)` / `currency` / `received_date` | ✅ **本模块的口径**：是**实际到货金额**（已付出去的钱），按成本法加权 |
| `supplier_quote` / `supplier_quote_price_tier`（V32） | **供应商报价**（分档） | ❌ 是**报价**不是成交价 ⇒ 只作 `QuoteReference` **只读参考单列，永不计入合计** |
| 6.3 采购单 | — | 🔴 **全部未开发** ⇒ 没有权威的「采购单下单金额」可对账 |

⇒ 页面与能力声明**都**写明：「6.3 采购单未交付，本项口径是**实际到货金额**；
报价另作只读参考，不计入成本、不计入合计」。

**🔴 12 财务（finance 服务）不存在**（`backend/services/` 下只有 identity / catalog /
channel / warehouse / trade / crm）。蓝图输入句「12 财务的成本核算结果」当前**没有来源**。
照 11.2.3.3 供应商索赔的先例处理：关税分项改取 warehouse 的 `duty_tax_estimate_line`
（10.5.1.3 已交付），并在能力声明与页面写明「**这是本服务口径，不是 12 财务的核算结果**」。
**绝不读写一个不存在的服务，也绝不 import 一个不存在的模块。**

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 采购成本**落库**还是**现算** | **不落库**，由 `vendor_delivery_record` 按成本法实时派生 | 落库就制造了第二个事实来源（到货记录一改，库存里的成本还是旧值），而成本正确性恰恰依赖「成本 = 当前事实按所选成本法算出来的值」 |
| 头程分摊 / 关税**落库**还是**跨服务读** | **跨服务读**（`CostBasisPort`，warehouse 授权只读端点） | AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本库**零个外键指向 warehouse 的表** |
| 跨服务读失败时返回什么 | **503 + 理由写「读不到上游」** | 返回空集合会让页面显示「头程分摊 0.00、关税 0.00」—— 真实情况是「没接上游」，而这两笔在到岸成本里通常不是小数目。显示 0 会让单位成本偏低、毛利偏高，页面上却看不出异常 |
| 分项**读不到**时填什么 | **available=false + 原因**，`amount=null` | 「取不到」与「确实是 0」是两件事。折成 0 等于宣称「这个 SKU 不花钱包装」而真实情况是「没人填过」 |
| 合计**落库**还是**现算** | **现算**；历史快照里存的是**当时的合计** | 当前值落库会多出一份可能与分项不一致的副本；历史快照不可变是它的意义 |
| 包装成本 | **人工参数**（`sku_cost_profile.packaging_cost`，可空 = 未维护） | 全仓**没有**包装成本事实：catalog `sku_package` 只有长宽高重量，warehouse `packing_material` 只有尺寸自重，都**没有成本列**。所以它只能是参数 |
| 手工调整项 | **只追加**（`sku_cost_adjustment`），原因 DB 层 `CHECK` 必填，改 = 作废旧行 + 追加新行 | 蓝图输入句明写「手工调整项**及原因**」。没有原因的调整会让「成本为什么突然变了」永远答不上来 |
| 一个 SKU 一条构成还是**一币种一条** | **一 SKU 一币种一条**（`UNIQUE(tenant_id,sku_id,currency)`） | 跨币种金额绝不相加；本库**没有汇率表**（汇率是 12 财务的资产）⇒ 唯一的诚实做法是逐币种各存一条、逐币种各自算合计。同 SKU 换币种 = **新增一条**，不是就地改币种 |
| 换成本法会不会覆盖历史 | **不会**。`sku_cost_profile` 只是「当前版本」的指针（`current_revision_id`），改的是指针，历史是行 | 照 5.2.1.3 的加粗句「调整必须留历史版本，不能就地覆盖」。「这个 SKU 昨天的成本 12.00 是移动加权算的」要能被回答 |
| 派生分项（采购 / 头程 / 关税）能不能手工改 | **不能**。手工调整只允许作用在 `PACKAGING` 与 `OTHER_DIRECT` | 采购成本是派生值（改了它就和事实脱节），头程与关税是上游事实（改了它就和 warehouse 脱节）—— 要改去改上游的单据 |
| 先进先出的「目标数量」取什么 | **最近一次到货的基础数量**（代理值） | 本服务**没有出库消耗台账**（07 库存未交付），拿不到「现在还剩多少」。取「最近那批有多少」作为正在动的那一层的大小 |
| 到货数量折算不到基础单位怎么办 | **排除该行 + 如实报出被排除的行数**（`excludedDeliveryCount`），该分项标「部分」，合计标「已知部分合计」 | 丢掉几行却报一个完整数字，会让成本看起来比实际低 |
| 页面路由 | **`/pricing/cost`**（与 5.2.1.2 / 5.2.1.3 共用） | 三者都是「成本」，蓝图把它们放在同一个 L3 下。**分区块**结构见下节 |

## 🔴 给兄弟模块的扩展位约定（5.2.1.2 / 5.2.1.3 必读）

5.2.1.2 到岸成本、5.2.1.3 成本分摊与本模块**共用 L3 路由 `/pricing/cost`**。
本模块已把页面做成**分区块**形态，约定如下（**后续兄弟模块要能在不重写本模块页面的前提下加分区**）：

1. **分区注册表**：`apps/web/src/components/pricing/cost-composition-model.ts` 的
   `COST_SECTIONS: readonly CostSection[]`。每个分区有
   `id`（稳定键）/ `title` / `moduleId` / `moduleName` / `delivered` / `summary`。
   兄弟模块**各自新增一条**即可，页面自动按数组顺序渲染。
2. **分区 id 是键**：`composition`（5.2.1.1）/ `landed`（5.2.1.2）/ `allocation`（5.2.1.3）。
   兄弟模块不要复用已占用的 id。
3. **未交付的分区必须显式标 `delivered=false` 并在 `summary` 里写明「未交付」及口径差异**
   （照 11.2.3.3 供应商索赔的先例：未交付的事实要写在页面上，不能藏起来）。
   画一个空白框让人以为已交付，比明说「未交付」糟得多。
4. **端点各自独立**：本模块的端点是 `/catalog/sku-cost-compositions/**`；
   到岸成本与成本分摊**不要**往这个前缀下挂子资源（那会让白名单语义混乱），
   各自开自己的前缀。
5. **口径不合并**：本模块是 **SKU 级单位成本构成**；到岸成本是**单据级**
   （一批货的 CIF + 关税 + 到港）；成本分摊是**规则表**（按重量/体积/金额摊到订单行）。
   三者**不合并成一个「总成本」数字** —— 定价时必须选对口径。
6. **历史不可变是兄弟模块的前提**：5.2.1.3 引用本模块的 `sku_cost_revision` 做回溯，
   所以本模块的历史永远是 append-only。

## 继承代码里修掉的 4 个真 bug

这些都不是「顺手优化」，是**会让蓝图加粗句验证不了**或**让功能直接不可用**的问题：

1. **`CostBasisPort` 累加器 NPE（头程与关税分项全废）**
   `firstMile.computeIfAbsent(key, k -> new BigDecimal[2])` 的元素默认是 `null`，
   下一行 `acc[0].add(share)` 在**第一张单据上就 NPE** ⇒ 头程分摊与关税分项永远取不到。
   改为 `new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}`（关税同理 3 槽）。
   **这是继承代码里最严重的一个** —— 上一轮修的分页截断漏洞掩盖了它（截断判定先于取行）。
2. **快照窗口只有 1 天 ⇒ 历史里的采购成本永远是 null**
   `snapshot()` 用 `effectiveFrom..effectiveFrom` 取到货记录，而明细页用的是**回溯 90 天窗口**。
   三种成本法都是**窗口口径**算法，所以「某一时刻的采购成本」=「以那一刻为终点的回溯窗口」。
   按 1 天算 ⇒ 绝大多数快照的 `purchase_cost` 是 null、`snapshot_complete` 恒为 false、
   `missing_components` 恒含 `PURCHASE` ⇒ **「换成本法后历史里能不能看出两个不同的采购成本」
   这条蓝图加粗句永远验证不了**（三条历史都是 null，看起来完全一样）。
   改为与明细页同口径（`DEFAULT_WINDOW_DAYS` 回溯窗口）。
3. **`UPDATE ... SET effective_from=?` 缺 `?::timestamptz` ⇒ 保存构成整条失败**
   jOOQ 的纯 SQL 绑定把 `OffsetDateTime` 当 `character varying` 送过去，PG 报
   `column "effective_from" is of type timestamp with time zone but expression is of type character varying`。
   同一个字段 INSERT 那边有 `?::timestamptz` 所以没暴露 —— **同一个字段两处写法不一致**是这类 bug 最难找的地方。
4. **乐观锁没校验客户端回传的版本号**
   `save()` 只在 SQL 的 where 里带 `version`（挡得住「读取之后被别人改过」），
   挡不住「客户端拿着一个早就过期的版本照样覆盖别人」⇒ 成本构成是定价的输入，
   静默覆盖会让「页面上显示的成本」与「用户当时看到的」不是同一个。
   照本仓其它模块（报价 / SPU / SKU）同一套写法补上 `version != input.version() ⇒ 409`。

另外补了两处**服务层校验**（HTTP 层已有 `@NotBlank`，但服务层自己也要挡）：
调整原因 / 作废理由为空时返回 **400 + 可读原因**，而不是一路撞到 DB 的 `CHECK` 拿到 500。

## 文件清单

**后端（catalog 服务）**

| 文件 | 说明 |
| --- | --- |
| `backend/services/catalog/src/main/resources/db/migration/V215__sku_cost_composition.sql` | 三表：`sku_cost_profile`（当前构成）/ `sku_cost_adjustment`（手工调整，只追加）/ `sku_cost_revision`（历史快照，只追加）；`tenant_id NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY` + isolation 与 data_scope 两条策略；`UNIQUE(tenant_id,sku_id,currency)`；零外键指向 warehouse |
| `backend/services/catalog/src/main/java/com/starcon/catalog/SkuCostCompositionModels.java` | 词表（`CostMethod` / `CostComponent`）、能力声明、列表 / 明细 / 写模型 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/SkuCostCompositionRules.java` | **成本法加权引擎**（纯计算，无 IO）：移动加权 / 先进先出 / 批次；`BigDecimal` + `MONEY_SCALE=4` + `HALF_UP`；`toBaseQuantity` 折算 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/SkuCostCompositionService.java` | 业务主体：列表 / 明细 / 保存 / 追加调整 / 作废调整 / 历史 / 能力声明 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/SkuCostCompositionController.java` | 7 个端点：`GET /capabilities`、`GET /`、`GET /{skuId}`、`PUT /{skuId}`、`POST /{skuId}/adjustments`、`POST /{skuId}/adjustments/{id}/void`、`GET /{skuId}/revisions` |
| `backend/services/catalog/src/main/java/com/starcon/catalog/CostBasisPort.java` | 跨服务只读端口：warehouse 头程发运单 / 关税估算单；透传调用方 `Authorization`、绝不发明 `X-Tenant-Id`；连接 3s / 读 5s 上限；不可达 ⇒ 503；`UnwiredCostBasisPort` 显式抛错 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/CostBasisConfiguration.java` | `WAREHOUSE_URL` 装配：没配 ⇒ `Unwired`（显式报错，不是静默空集合） |
| `backend/services/catalog/src/test/java/com/starcon/catalog/SkuCostCompositionRulesTest.java` | 规则单测 9 例：三种成本法给出三个不同的数、FIFO 只消耗到目标数量、批次法选中最新批、null ≠ 0、BigDecimal 精度与 scale |
| `backend/services/catalog/src/test/java/com/starcon/catalog/SkuCostCompositionIntegrationTest.java` | **集成测试 16 例**（Testcontainers 真实 PG16 + 强制 RLS + 单连接池）：租户隔离三断言、成本法反例 + 历史可查、原因必填（服务层 / HTTP 层 / DB CHECK 三道）、timestamptz 写回断言、上游不可达 503、来源标注、包装成本未维护、折算排除、批次折算、能力声明 |

**前端**

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/components/pricing/cost-composition-model.ts` | 类型、端点、**`COST_SECTIONS` 分区注册表**、文案与本地校验（`moneyText` 把 null 渲染成「取不到」而不是 0.00） |
| `apps/web/src/components/pricing/cost-composition-workspace.tsx` | 真实工作区：成本口径声明块 / 筛选 / 台账 / 明细（五个分项 + 合计 + 完整度）/ 保存构成 / 手工调整 / 历史 / 批次与报价参考 / 兄弟分区；加载态 · 空态 · 错误态三态齐全 |
| `apps/web/src/app/pricing/cost/page.tsx` | 路由 `/pricing/cost`（**不是** `DomainPlaceholder`） |
| `apps/web/src/app/api/catalog/sku-cost-composition-allowlist.test.ts` | api-proxy 白名单**双向**断言：契约里本模块每个 path 都放行 + 写动作真的转发到 8082 + 伪造 `X-Tenant-Id` 不转发 + 不该存在的形状仍 404 + **兄弟车道路由未被挤掉** |
| `apps/web/src/components/pricing/cost-composition-model.test.ts` | 模型单测 30 例（null 不渲染成 0、来源四分类、合计完整度、分区约定、校验） |
| `apps/web/src/app/pricing/cost/cost-composition-page.test.ts` | 路由与分区块约定：页面不是占位页、三个分区各占一条、未交付显式标注 |

**契约与台账**

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  （codegen 产物：新增 6 个 path + 17 个 DTO；`channel` / `trade` 侧是**其它车道已交付但快照未再生成**的同步结果，
  无语义变化）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 5.2.1.1 这一行**的「状态」「证据」两格

## 后续待办

1. **warehouse 侧补一个「按 SKU 聚合成本」的只读端点**。
   当前 `CostBasisPort` 一次取数内部是 **1+N 次 HTTP 调用**（先取单据列表，再逐张取行明细，在端口内部按 SKU 聚合）。
   warehouse 侧补好聚合端点后换掉 `HttpCostBasisPort` 的 HTTP 实现即可，**服务层与页面的聚合逻辑无需改动**。
   本车道**不**新增 warehouse 的端点（那是另一个服务的写入所有权，且与并行车道有 DDL / 源码冲突面）。
2. **6.3 采购单交付后，采购成本分项改用真实的采购单成交价**。
   现在是「实际到货金额」口径（6.3 未交付），两者的差额需要显式展示（照 5.2.3.1 的「费用差异」纪律）。
3. **6.3 采购单 + 12 财务交付后，跨服务对账**。
   当前是「本服务口径」；与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。
4. **包装成本改为派生值**（待包材目录补成本列，或由 06 采购供数）—— 现在它是人工参数。
5. **兄弟模块 5.2.1.2 / 5.2.1.3** 交付时，在 `COST_SECTIONS` 各新增一条分区
   （`landed` / `allocation`），并各自开独立端点前缀；**不要重写本模块的页面骨架**。
6. `module-progress.md` 的总览计数由 `node tools/module-progress/sync.mjs` 重新统计（本车道按纪律只改了自己那一行）。
