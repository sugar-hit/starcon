# batch-5 · 波次与合并拣货 + 分仓规则（含一次并入冲突的定性）

- 时间：2026-10-14 10:30 – 11:53 CST
- 调度：`lane.mjs plan --lanes 4` → 4 条车道并行开发 → 整批一次 L2 全量验证
- 结果：**2 条并入并通过 L2**（8.3.2.2、9.3.2.1），**2 条并入冲突**（7.2.2.2、7.7.2.1，冲突文件 `apps/web/src/lib/api-proxy.ts`）
- 集成头：`fb59785` → `2e909b6`

## 本批台账数字（sync.mjs 实时取值）

| 项 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | 140（30.1%） |
| 进行中 | 0 |
| 未开发 | 325 |

---

## 模块 8.3.2.2 波次与合并拣货

**这个功能做什么**：仓库不再一张出库单拣一次。多张出库单按承运商 / 库区 / 时段合并成一个拣货波次，
作业员沿一条路径走完一批订单，拣货效率显著提升；每张出库单在波次里仍有独立位置，
波次完成后仍按订单拆分复核与打包（**合并不改变订单粒度**）。

**技术落地**：

- 迁移：`V113__warehouse_pick_wave.sql`（warehouse，**建四张表、零 ALTER**）
  - `pick_wave_rule` 合并规则配置 · `pick_wave` 波次单头 · `pick_wave_task` 波次成员
    （各出库单在波次中的位置）· `pick_wave_event` append-only 流水
  - RLS：四张表逐张 `ENABLE` + `FORCE ROW LEVEL SECURITY` + `isolation` 策略
    （`DO $$ FOREACH` 循环，租户取 `current_setting('app.current_tenant')`）
- 端点：`/warehouse/pick-waves` 顶层 12 个形状（刻意不挂 outbound-orders 之下 ——
  出库单是履约单据、拣货波次是现场工单，单据取消后工单还在）
- 页面：`/warehouse/pick-waves`（真实工作区 + 加载/空/错误三态）
- 测试：`PickWaveIntegrationTest` **30 例**（Testcontainers PG 16，连接池=1）+
  `pick-wave-model.test.ts` 20 例 + `pick-wave-allowlist.test.ts` 8 例

**关键设计决策**：

1. **波次进度不做冗余列**。明细进度住在 8.3.2.1 的 `pick_task` 里，挂触发器等于 ALTER 别人的表；
   改为查询时 `pick_wave_task ⋈ pick_task` 一次聚合。「合并不改订单粒度」由 DB 守卫兜住
   （完成波次必须全部成员工单进入终态）。
2. **同键只能有一个未完波次** —— 这就是「合并」二字的全部含义。代价是撞上限后要等上一波收工，
   所以候选接口必须同时报 `eligible` / `waiting` / `unruled` 三个数，不能只报一个。
3. **库区是推导值，不是列**。仓库没有 `zone` 列，库区 = 取货库位沿 V105 库位树上行到顶层根节点；
   跨区任务没有单一库位，只进「库区不限」的规则。
4. 顺带把 8.3.2.1 的 `capabilities.waveAvailable` 由 `false` 改为 `true`（上游明确留的边界），
   并同步其两处断言。

**过程中踩到并修掉的三个真 bug**（不是测试写错）：

- jOOQ 的 PlainSQL 认不出 `c.*` 的列名 → `Field "zone_code" is not contained in row type`
- **SQL 注释里的 `?` 会被当占位符计数**（注释里写「`?` 会数错」，那个 `?` 自己被算成第 8 个标记）
- 迁移守卫的跃迁判据写成「回表读当前行 status 再和 OLD.status 比」⇒ 恒为假，
  `PLANNED→COMPLETED` 非法跃迁被放行

---

## 模块 9.3.2.1 分仓规则

**这个功能做什么**：一张订单进来，系统按各仓的库存可用性、运费成本、时效承诺、**货权归属**、
买家地区匹配度选出该由哪个仓发货，给出发货仓与分配数量；一个仓都分不出来时把订单挂起并
逐条说明每个候选为什么被排除，而不是悄悄落到某个默认仓。

**技术落地**：

- 迁移：`V116__trade_warehouse_allocation.sql`（trade，**八张表、零 ALTER V92**）
  - `fulfillment_warehouse_allocation_{rule,warehouse,stock_estimate,shipping_estimate,
    decision,candidate,hold,event}`
  - **与 9.3.2.2 的 `fulfillment_routing_*` 命名空间彻底分开**：V92 七张表一表未动、
    连 JOIN 都没有；也**刻意不复用** `fulfillment_path_cost_profile`
    （那是「平台 × 路径 × 目的国」费率口径，本模块要「仓 × SKU × 目的国」每单运费，
    复用就是跨模块读别人的事实表）
- 端点：`/trade/fulfillment/allocation` **15 个**，与 9.3.2.2 的 `/routing` **并列而非嵌套**
- 页面：`/orders/fulfillment/allocation`（刻意不占用 `/orders/fulfillment` ——
  那个路由在 task-pages 里已登记给 9.3.3.1 待发货订单）
- 测试：`WarehouseAllocationIntegrationTest` **40 例** + model 19 例 + allowlist 6 例

**关键设计决策**：

1. **货权（legal_entity）是仓库的属性，且是硬闸 + 权重两层**，不是输出里的附注字段：
   - 硬闸：法人不符的仓直接不可行（`LEGAL_ENTITY_MISMATCH`），由 BEFORE 触发器
     `guard_warehouse_allocation_candidate_legal_entity` 钉在**候选写入路径**上 ——
     绕过服务层直接写 SQL 同样选不中
   - 权重：`weight_legal_entity` 参与五维总分
   - 唯一能放宽硬闸的是显式兜底 `CROSS_LEGAL_ENTITY`，放宽后如实标记并进汇总
   - 测试用**同一份数据双向验证权重真的起作用**：成本权重 0.6 时选更便宜的跨法人仓；
     货权权重 0.6 时选同法人的更贵仓
2. **`SUSPENDED` 是真正的终态**（200 + `holdId`），不是错误响应。每个被排除的仓在
   `..._candidate` 里逐条记 `infeasible_reason_code`，`..._hold` 记全量枚举的
   `exclusion_detail`（`仓A=货权法人不符；仓B=无库存估算`），两处互为印证。
   DB 用延迟约束触发器双向钉死：`ALLOCATED ⇒ 必有 selected 候选且仓号/法人/数量自洽`、
   `SUSPENDED ⇒ 必有 hold 且零 selected 候选` ⇒「这单没货」不可能显示成「已决策走 A 仓」。
3. **判定顺序「资格 → 库存 → 成本 → 打分」与 9.3.2.2 自洽**，货权进第 1 层做闸、第 4 层做权重。
4. **本模块不预占 warehouse 库存**（`capabilities` 显式报 `reservesUpstreamStock=false`）——
   现在假装预占，下游 9.3.3.1 落地时会出现两边都以为占了同一批货。

---

## 🚨 并入冲突：7.2.2.2 与 7.7.2.1 卡在同一个文件（本批唯一的未收口项）

### 现象

`merge-ready` 结果：8.3.2.2、9.3.2.1 并入成功；7.2.2.2、7.7.2.1 报

```
✗ 7.2.2.2 并入冲突：apps/web/src/lib/api-proxy.ts
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
```

两条车道**代码本身都是好的**：各自 L1 全绿（7.2.2.2 = 1319 例、7.7.2.1 = 1308 例，均 0 失败），
迁移 V114 / V115、端点、页面、测试都齐。**冲突纯粹发生在共享白名单文件上。**

### 定性（已取证，非推测）

`apps/web/src/lib/api-proxy.ts` 的 warehouse 段是**一个单行正则**（约 13KB，第 555 行），
按「顶层 alternation 分支」组织。两条冲突车道的端点都在 `inventory/(...)` 组**内部**：

| 车道 | 端点 | 新增的组内子分支 |
| --- | --- | --- |
| 7.2.2.2 | `/warehouse/inventory/safety-stock/*`（16 条） | `safety-stock` |
| 7.7.2.1 | `/warehouse/inventory/vmi/*`（23 条） | `vmi` |

两条车道都只改了同一个 `inventory/(...)` 顶层 token，git 自然判冲突。
**解法在语义上是纯并集**（往同一个 alternation 里各加一支），但 merge 驱动器的
语义分支没有走到，落到了行级兜底 —— 那条 stderr **不是例行噪音，它每次都在报告
「我没能按语义合并」**。

### 为什么不能靠「下轮自然好」

冲突是确定性的，不是时序偶发：下轮集成头只会有**更多** warehouse 分支，
retry 同一冲突不会自愈。

### 本轮为什么没有强行解

按调度协议「不要自己强行解冲突」。而且此时该文件正处于未解决冲突状态，
再手工编辑会把下轮的解决难度推高。**留待下轮定向处理。**

### 下轮可执行的做法

1. 顺序并入（**一次只并一条**），把冲突面从 2 条降到 1 条
2. 冲突只可能出现在 warehouse 段那一个单行正则；解法是往 `inventory/(...)` 的
   alternation 里追加 `safety-stock(?:...)` 与 `vmi(?:...)` 两支，其余逐字节不动
3. 并完用仓库自带的 allowlist 测试把关（两条车道各自都写了
   `*-allowlist.test.ts`，它们从**自己模型源码反向抽取真实调用点**逐条断言放行）
4. 想再稳一层，用本轮的 `.dev-agent/route-behavior.cjs`（见下）复核

---

## 本批新增的验证工具：契约驱动的行为级路由校验

`.dev-agent/route-behavior.cjs`（gitignored，调度器专用）。它不靠结构解析，
而是**直接用各车道自己生成的 OpenAPI 契约里的 path 作为用例集**，断言
「契约里声明的每一条 path 都能被 proxy 放行」—— 不需要人工维护用例清单。

用法与结果：

```
node .dev-agent/route-behavior.cjs HEAD --unmerged feat/7-2-2-2 feat/7-7-2-1
```

- `HEAD`：warehouse 366/370、trade 40/40 共 **406/410** 放行
- `fb59785`（本批之前）：**386/390** 放行
- ⇒ 本批新增 20 条 path（warehouse +10、trade +10）**全部放行，0 回归**
- ⇒ 4 条未放行的是**本批之前就存在**的缺口，位置固定，不是这批弄坏的：
  - `inventory/printing/devices/{deviceId}`
  - `inventory/consignments/{agreementId}/sales`
  - `inventory/consignments/{agreementId}/lines/{lineId}/sales`
  - `inventory/availability`
- 未并入车道校验：`feat/7-2-2-2` 16 条、`feat/7-7-2-1` 23 条，在当前 HEAD 上
  **放行 0 条** —— 正确（还没并进来就不该放行）

**为什么要有这个工具**：`lane.mjs` 从不跑测试，`land` 只校验「工作区干净 + 有新提交」。
合并「成功」不等于路由还在 —— git 不报冲突、合并返回 ok，症状要等那个页面
在浏览器里白屏才暴露。上面那 4 条既有缺口就是工具第一次跑就抓出来的。

### 写这个工具时自己踩的两个坑（记录下来给下轮）

1. **不能用 `/\/\^\((.*)\)\$\//` 抽取单行正则**。trade 段的正则以 `(?:` 开头，
   那个 `(` 会被模式里的 `\(` 吃掉，抽出来以 `?:` 开头，拼成 `^?:` 就是
   `Nothing to repeat`。
2. **不能把外层括号拆掉再补锚点**。`^a|b|c$` 实际含义是 `(^a)|b|(c$)` ——
   锚点只绑到首尾两项，那是另一个正则。必须 `^` + 完整分组 + `$`。
3. `execSync` 默认 `maxBuffer` 1MB，`warehouse.openapi.json` 超过 1MB，
   会被截断成非法 JSON。取契约必须显式加大 `maxBuffer`。
4. proxy 的 pattern 是对 **`path.join('/')` 去掉服务前缀后**的字符串求值的，
   **没有前导斜杠**。留一个斜杠会让 410 条 path 全部判成未放行。

---

## L2 批次闸结果（整批只跑一次）

| 项 | 结果 |
| --- | --- |
| `cd backend && ./mvnw verify` | **BUILD SUCCESS**，**3257 例 / 0 失败 / 0 错误**，09:07 |
| `pnpm typecheck` | 3/3 successful |
| `pnpm lint` | 3/3 successful（仅既有 warning，均在别车道文件） |
| `pnpm test` | **134 文件 / 2498 例全绿**，4.07s |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功，`/warehouse/pick-waves` 在路由表内 |
| `node tools/module-progress/sync.mjs --check` | 一致 |
| **L2 实测耗时** | **11 分 36 秒**（本地参数文件里按 12 分钟估算，第一次拿到真数据） |

新测试类确认在 L2 里真的跑了：

```
PickWaveIntegrationTest            Tests run: 30, Failures: 0
WarehouseAllocationIntegrationTest Tests run: 40, Failures: 0
```

### 静态预检（互相破坏形状，L2 之前）

对本批**已并入**的两个迁移：

- `ADD COLUMN` 无 `DEFAULT`：**0 命中**
- `SET NOT NULL`：**0 命中**
- `ALTER` 他人既有表：**0 命中**（V113 对 V110 的六张表零改动；V116 对 V92 的七张表零改动）
- RLS 覆盖：V113 四张表、V116 八张表，`DO $$ FOREACH` 循环逐张
  `ENABLE` + `FORCE` + `isolation` 策略，**无遗漏**

---

## 本批观测到的两处 flake（均未在 L2 复现，不构成回归证据）

1. `StockOwnershipIntegrationTest.货权档优先级…`（7.7.1.1，**非本批模块**）
   在 8.3.2.2 的 L1 首轮失败过一次。取证：单独跑全绿 → 排除本车道测试类全模块全绿 →
   带上本车道复跑 1311 例全绿。**L2 全量亦未复现。**
2. `SafetyStockIntegrationTest.同一个SKU的自有仓档与平台仓档可以并存`（7.2.2.2 自有）
   首轮全量跑出现 `total=1`（应 2），单独跑 38/38 全绿。7.2.2.2 曾提出
   「应用时钟与数据库时钟不同源」的解释，**专门加回归闸去验，被实测证伪**
   （回退修复后回归闸照样通过），因此把那个推测性改动**回退**了，没有拿未证实的结论改代码。
   后续两轮全量均未复现，**根因至今未定**，已留在 7.2.2.1 的会话日志里。

## 待办（下轮）

1. **解决 7.2.2.2 / 7.7.2.1 的 api-proxy 并入冲突**（本批唯一未收口项，做法见上）
2. **修掉 4 条既有路由缺口**：`inventory/availability`、`inventory/printing/devices/{deviceId}`、
   `inventory/consignments/{agreementId}/sales`、`inventory/consignments/{agreementId}/lines/{lineId}/sales`
   —— 后端契约里有、前端 proxy 放行不了，界面调用会得到 404。属另外三个已交付模块。
3. **api-proxy 结构缺陷**：warehouse 段有 28 个顶层 token 但只有 25 个唯一 ——
   `inventory/` 出现两次（且两份内容不同：2788 vs 2447 字符，在第 211 字符处分叉），
   `facilities` 出现三次。重复分支不影响匹配结果，但**让任何结构化合并分析都不可靠**，
   也是驱动器反复冲突的背景。建议单独开一轮清理。
4. 7.2.2.2 的**界面欠账**：阈值新建/换版后端端点完整可用，页面尚未做配置表单
   （当前页面提供读、评估、预警流转、建议采纳/忽略）。已在台账「遗留」中记录。
5. `StockOwnershipIntegrationTest` 的偶发失败根因（建议交 7.7.1.1 的所有者）。