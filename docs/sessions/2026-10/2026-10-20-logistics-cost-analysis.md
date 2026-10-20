# 10.6.1.1 成本分析 —— 车道交付

日期：2026-10-20　车道：`feat/10-6-1-1`　worktree：`.worktrees/10.6.1.1`
基线：集成头 `integration/module-dev` @ `969a9832`

> **本车道是续写**：上一个 worker 在写后端一半时因网络错误（`net::ERR_CONNECTION_CLOSED`）整个会话死掉，
> 留下的 5 个文件（模型 / 服务 / 两个跨服务 Port / 迁移）全部是**未提交**状态。
> 本次交付在那些成果上**接着写**：保留迁移与 Port 的整体设计，改掉它们的编译错误与三处逻辑缺陷，
> 补完 Controller、集成测试、前端页面、契约、白名单、台账与会话日志。

## 决策摘要

### 1. 加粗句「报表必须全口径」的落点是**五个分量各占一位**，不是「把能算的算出来摆在那儿」

蓝图原文加粗：「**物流成本常被低估**：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，报表必须全口径」。

落到代码上是五件事同时做，缺一件就退化成半截子报表：

1. **分量台账是一张表**（`logistics_cost_component`），不是 Java 常量 ——
   「这一位今天有没有数、缺了是谁的活」要能在界面上如实显示，还要能指出缺了是谁的活。
   `CHECK (available = false OR (source <> '' AND basis <> ''))` 与
   `CHECK (available = true OR (owner_module <> '' AND unavailable_reason <> ''))`
   是「假装它有真实数据」的物理拦截器。
2. **合计永远带着「我算了谁、没算谁」一起出现**（`includedComponents` / `missingComponents`）。
   一个分量都没读到时，合计是 **null**（算不出来），不是 0 —— 报一个 0 元的合计，
   意思就成了「这批货的物流成本是 0」。
3. **跨币种绝不相加**，逐币种分组，逐币种一行。
4. **null 与 0 是两件事**：分母为 0 的比率 / 单均运费 / 单位重量运费返回 null，
   界面上「暂无数据」与「0.00」长得不一样。
5. **实缴进合计、未缴只进估算序列** —— 估算不是真实成本。

### 2. 包材走**参数**口径，仓储费显式标「不可用」—— 两者都不是 0

上游真值盘点结论：仓库的 `packing_material`（V121）**只有外形尺寸与自重，没有任何成本列**，
而**全仓库没有任何仓储费表**。两种做法：

- ① 都标成「不可用」：诚实，但报表永远补不齐，且丢掉「包材是可以算的」这个事实。
- ② 包材给**参数**口径（租户维护的每票包材成本，报表按「参数 × 本窗票数」算），
  仓储费仍标不可用并点名归属模块。

选 ②。代价是**必须把参数和事实分色**（`source_kind` ∈ FACT / PARAMETER）——
把「租户自己填的每票包材成本」报成「事实」，比不报包材这一位更危险：前者会让下游
（5 定价 / 13 利润）以为这个数有账单支撑。所以 `source_kind` 进了台账、进了 DTO、进了界面徽章。

**参数只对包材开放**：其它四个分量都有事实来源，给它们开参数入口等于制造第二个真相
（「这个数到底是算出来的还是填出来的」）。服务层明确拒绝其它分量。

仓储费永远不可得 ⇒ **`coverage.complete` 永远是 false**：这份报表**从不自称全口径**。

### 3. 跨服务只读走 HTTP Port，上游读不到一律 503 报「读不到」

尾程来自 trade（`logistics_waybill_freight` V132）、头程来自 warehouse（`first_mile_shipment` V80）、
关税来自 warehouse（`duty_tax_estimate` V90）。**跨服务不建 FK / 视图 / 跨库 SQL**。

两个 Port 转发的是**调用方自己的 `Authorization` bearer**，绝不发明 `X-Tenant-Id` ——
租户隔离是**两道**（channel 侧 RLS + 上游侧 RLS），不是「本服务替对方决定租户」。

上游不可达 ⇒ **503，理由写「读不到上游」**，绝不返回空集合冒充「上游没有运费」——
那会让现场看到「成本 0 元」，照着空清单以为这批货根本没花过运费。

### 4. 12 财务（finance 服务）不存在 ⇒ 分摊明细由本服务自持

`backend/services/` 下只有 identity / catalog / channel / warehouse / trade / crm，**没有 finance**。
绝不读写一个不存在的服务。头程费用分摊明细落在**本服务自持事实**
（`logistics_cost_allocation*`），能力声明里如实标注「本服务自有口径」，
与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。

### 5. 分摊规则版本化 + 结账时点（蓝图 5.2.1.3 加粗句）

「分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖」。

- 一行 = 一个**规则版本**，同 `rule_code` 递增版本号；同码同时只有一个 ACTIVE 版本
  （部分唯一索引钉死）。
- **已被分摊引用过的版本改口径被 DB 触发器拒**（23514），绕过服务层直接写 SQL 同样被拒。
- 分摊单引用的是**具体的 rule_id + rule_version**，历史分摊单永远指向它当时用的那一个版本。
- 分摊单上有**两个不可互相替代的时刻**：`computed_at`（算的时刻）与 `cost_as_of`（结账时点）。
  锁定后金额 / 基准 / 规则 / 结账时点**都不可再改**（触发器钉死）。

### 6. 分摊是**拆分**不是**缩水**：最大余数法 + DB 延迟约束双保险

逐行金额用最大余数法分配，保证**逐行合计严格等于来源总额**；
再由 V213 的**延迟约束触发器**在提交时复核一次（绕开服务层直接写 SQL 也一样被拦）。
基准合计为 0 时**拒绝分摊**（而不是摊成 0 行）—— 摊成 0 行等于「这批发运单的成本是 0」。

## 关键 trade-off

| 决定 | 备选 | 取舍 |
| --- | --- | --- |
| 包材走**参数**口径 | 一律标「不可用」 | 多一张表 + 一组端点 + 一块界面；换来「包材这一位能算」以及「参数 ≠ 事实」这条边界被显式标出来 |
| 仓储费标**不可用** | 造一张空的仓储费表 | 造空表等于宣称「仓储费已交付」；宁可让报表永远 `complete=false` |
| 上游不可达 ⇒ **503** | 返回缺项的报表 | 503 明确「读不到」；返回缺项会把「上游挂了」与「上游没有数据」混成一件事 |
| 头程分摊自持在 channel | 等 finance | finance 不存在；等是空话，自持并标 owner 是能交付的形态 |
| 渠道级**费率卡**（V140）**不**进本模块 | 用费率卡估算尾程做对比 | `freight_quote.cost_basis` 物理上只允许 `RULE_ESTIMATE`（估算）；把估算混进「实际成本」报表违反本模块口径。同理关税估算只进估算序列、不进合计 |
| 规则启用顺序：**先落 DRAFT → 退役旧版 → 升新版 ACTIVE** | 直接以 ACTIVE 落库 | 直接落 ACTIVE 会被部分唯一索引当场拒（`logistics_cost_rule_one_active`），症状是「新建现行版本报重复键」 |

## 上一个 worker 留下的代码：保留 / 改写 / 补完

| 文件 | 处理 |
| --- | --- |
| `V213__logistics_cost_analysis.sql` | **保留整体设计**（四张表 + 触发器 + RLS + GRANT）。改写：4 处 PL/pgSQL `RAISE ... , USING ERRCODE=` 语法错误（迁移根本跑不起来）、`guard_cost_allocation_rule_snapshot` 的变量名与列名同名导致 `column reference "rule_code" is ambiguous`、补 `logistics_cost_parameter` 表与 `source_kind` 列 |
| `TradeFreightQueryPort.java` | **保留**（RestClient + 转发调用方 bearer + 超时上限 + 503 语义）。改写：`messageConverters` 传参写法、mapper 缺 `JavaTimeModule`（OffsetDateTime 反序列化直接炸）、TRADE_URL 默认端口 8082 → 8085（8082 是 catalog 的端口，仓库既有 `PendingShipmentQueryPort` 有同样的错，本车道不沿用） |
| `WarehouseCostQueryPort.java` | 同上（mapper 缺 `JavaTimeModule`） |
| `LogisticsCostAnalysisModels.java` | **保留**全部 record 形状。改写：`CostCapabilityDimension` 补 `unavailableReason` 字段、`CostCurrencyRow` 补包材列与四个占比字段、`CostUnitMetric.totalFreightAmount` 改为可空、`CostComponentCapability` / `CostComponentAmount` 补 `sourceKind`、新增 `CostSourceKind` 与参数三件套 |
| `LogisticsCostAnalysisService.java` | **保留**整体结构与口径。改写：补缺失的 `readComponents()`、修 `componentAmount` 签名与调用点、`fetchValue` 的 Object→具体类型、**未登记运费的票会造出 0 元币种行**（把缺项当 0，必须改成只计票数）、分页偏移 int 溢出、规则启用的自降级与撞唯一索引两处缺陷、已结账分摊单被新规则重算取代（服务层明确拒绝）。补完：包材参数口径、参数 CRUD、头程分摊后到岸成本占比 |
| `LogisticsCostAnalysisController.java` | **本车道新写**（8 个端点） |
| `LogisticsCostAnalysisIntegrationTest.java` | **本车道新写**（22 例） |
| `apps/web/src/app/logistics/analytics/` + 组件 + 模型 | **本车道新写** |
| `apps/web/src/lib/api-proxy.ts` | **本车道新写一条 `services[]` 元素**（既有条目一字未动、不重排、不格式化整文件） |
| `packages/api-contracts/` | codegen 产物（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`）。**只保留 channel 的两处改动**；trade 的契约是仓库既有的排序 artifact（另一条车道的类型被追加在文件末尾），与本模块无关，已 `git checkout` 还原 |

## 文件清单

**后端（新增）**

- `backend/services/channel/src/main/resources/db/migration/V213__logistics_cost_analysis.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsCostAnalysisController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/TradeFreightQueryPort.java`
- `backend/services/channel/src/main/java/com/starcon/channel/WarehouseCostQueryPort.java`
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsCostAnalysisIntegrationTest.java`

**前端（新增）**

- `apps/web/src/app/logistics/analytics/page.tsx`
- `apps/web/src/components/logistics/logistics-cost-analysis-model.ts`
- `apps/web/src/components/logistics/logistics-cost-analysis-model.test.ts`
- `apps/web/src/components/logistics/logistics-cost-analysis-workspace.tsx`
- `apps/web/src/app/api/channel/logistics-cost-analysis-allowlist.test.ts`

**改动**

- `apps/web/src/lib/api-proxy.ts`（新增一条 `services[]` 元素）
- `packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（台账 10.6.1.1 一行 + 汇总计数）

## 后续待办

1. **仓储费**（8 仓储作业）：仓储计费与账单落地后，仓储服务的计费事实需要一个授权 HTTP 读出口，
   本模块才能把 `STORAGE_FEE` 从「不可用」翻成「事实」。在那之前报表的 `coverage.complete` 恒为 false。
2. **12 财务（finance 服务）**：落地后经授权 API / event 衔接分摊明细的最终对账，
   并把能力声明里的 `FINANCE_RECONCILIATION` 维度从 `available=false` 翻过来。
3. **包材成本的事实化**：若将来 warehouse 的 `packing_material` 补上成本列，
   应把包材从「参数」口径换成「事实」口径 —— 那时 `source_kind` 要从 PARAMETER 改回 FACT，
   界面上分色随之变化。
4. **10.6.2.1 时效分析**未交付：本模块的超成本判定用的是**单位重量运费**，
   与时效维度正交；两个模块合起来才是「这票货贵且慢」的完整判断。
5. **下游 5 定价 / 13 利润**未交付：它们引用成本时必须带上 `rule_version` 与 `cost_as_of`，
   否则「同一 SKU 今天 30% 明天 25%」无法解释。
