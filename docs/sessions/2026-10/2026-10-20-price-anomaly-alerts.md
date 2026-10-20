# 5.5.2.1 异常告警（D6 阶段，L3 锚点 5.5.2）

> 车道 `feat/5-5-2-1`，worktree `.worktrees/5.5.2.1`，基线 `integration/module-dev`。
> 迁移 `V222__pricing_price_anomaly.sql`（catalog 服务，版本号由调度者预分配）。
> 页面路由 `/pricing/anomaly-alerts`（**不**与 5.5.1.1 价格历史共用 —— 后者未交付，
> 且端点命名空间本就不同）。

## 摘要

落地蓝图 §5.5.2.1：监测四类价格异常（平台价与内部价长期不一致、售价低于成本线、
平台自动降价、多站点价格倒挂），输出异常清单 + 预警通知。
四张新表（观测台账 / 判定规则 / 异常事件 / 预警通知）、11 个端点、一个真实工作区页面、
42 个前端用例 + 26 个后端用例。

## 决策摘要

### 一、🔴 本批最关键的架构决定：模块要自持一张「价格观测台账」

蓝图输入句是「平台价回流；内部价」。实测集成头：

- **平台价**（我们自己那条 listing 在平台上的售价）**全仓没有这个事实** ——
  channel 的 `listing` 表（V171）自己在表注释里写着「**无价格列：售价归 05 定价**」，
  而 05 定价整卷在本模块之前只交付了 5.2.1.1 成本构成；
- **内部价**的权威来源是 5.1.1.1 价目表 / 5.1.1.2 价目项，**二者未交付**，本仓无 `price_list` 表；
- 4.3.2.2 平台状态回流**只回流状态、不回流价格**。

⇒ 照 5.2.1.1 对「06 采购单未交付」的先例（改用同库真实到货事实派生 + 能力声明写明口径），
本模块自持 `price_anomaly_observation`（一行 = 一个 SKU 在一个站点上的一次价格观测），
由 `POST /observations` 登记进入，**不编造来源**，能力声明里逐条点名缺口。

这张表**不是「当前值表」**，是**时间序列**：蓝图第 ① 类异常要判定的是「**长期**不一致」，
没有时间序列就只剩「一次就对不上」—— 而那恰恰是蓝图要求**不要**报的那种。

### 二、四类异常的判定口径各自独立，SQL 各一条

| 异常 | 判定依据 | 口径要点 |
| --- | --- | --- |
| ① 平台价与内部价长期不一致 | 同一站点的连续观测段 | **三道闸门**：≥2 个观测点 + 跨度 ≥ `min_duration_days` + 比例超阈值 |
| ② 售价低于成本线 | 售价 + 5.2.1.1 成本事实 | 判定写进 SQL（折算到成本线币种后比较）；成本快照不完整时标 `costComplete=false` |
| ③ 平台自动降价 | 相邻两次观测 + 变动来源 | **我们自己改的（MANUAL_INTERNAL）不算**；检测小结报出「我们自己改的降价 N 次」 |
| ④ 多站点价格倒挂 | **站点两两比对** | 事件上存**低的那一边**与**贵的那一边**；不是全平台平均价差 |

**① 的两个 SQL 坑（都不报错，只是把结论算错）**：

1. **连续段必须用反向计数器切分**：`sum(case when breach = 0 then 1 else 0 end) over (...)`。
   若写成 `sum(breach)`，每一行都会拿到不同的组号 ⇒ 连续段被切成「每段一个点」⇒
   **持续天数恒为 0**。这个坑第一次跑出来的现象是「跨度 28 天算成 0 天」。
2. **只看包含最新那次观测的连续段**：最新观测已经正常了 ⇒ 异常**当前不成立** ⇒ 自动 RESOLVED。
   若只看「最近的一段偏离」，最新观测恢复正常时那段偏离仍会被当成现状。

### 三、🔴 跨币种站点之间怎么比：显式的比价口径，**绝不编汇率**

德国站 EUR、美国站 USD —— 没有汇率就没有可比的价格。本仓汇率表属 5.2.2.1 / 5.2.2.2（**均未交付**），
且 V215 第九节 R6 已把纪律钉死：「跨币种绝不相加」「**绝不编一个汇率**」。

⇒ 观测行自带 `comparison_currency + fx_rate + fx_rate_date + fx_rate_source`（汇率连同日期与来源
一起记在观测行上 —— 蓝图「汇率必须记录在单据上」的同一纪律）；没给汇率就只与同币种站点比；
跨币种的站点对**如实报为不可比**（检测结果里的 `uncomparablePairs`，带 ownerModule 与原因），
既不静默丢弃，也不乘一个编出来的汇率。

### 四、事件是状态，不是快照

`fingerprint` 是异常的**稳定身份**（不含时间桶）⇒ 重复检测**不会**堆出一堆重复事件，
而是把同一条事件的 `last_seen_at` / `duration_days` 往前推；新事件才发一条预警通知
（一个事件一条，`UNIQUE(tenant_id, event_id)`）。

**「长期」与「发生过」是两种事件**：①②④ 是**当前状态**（不再命中就自动 RESOLVED）；
③ 平台自动降价是**发生过的事**（不会因检测窗口移走而消失，只能由人确认或消解）。
把 ③ 也做成「窗口移走就消失」，等于让「平台自动降过价」这件事在台账上凭空不见。

### 五、🔴 零外键：用触发器做引用校验

上一批（batch-37）有车道在迁移里给既有表建了 FOREIGN KEY，结果打破了同服务既有测试的
`TRUNCATE`（PG：被 TRUNCATE 的表若被别的表外键引用、且引用方不在同一条 TRUNCATE 语句里，
就直接拒绝）—— 那些测试在清理阶段整体报错（39 例 Errors），L2 直接红。

⇒ 本迁移**零个 FOREIGN KEY**：对 `sku` / 事件的引用校验用**触发器**做
（`guard_price_anomaly_observation_sku` / `_event_sku` / `_notification_event`，
与同批 channel `V219__return_shipback_to_origin.sql` 同一手法），对既有测试**零回归面**。

### 六、其它纪律

- 金额一律 `BigDecimal` + `NUMERIC(19,4)`，比例 `NUMERIC(9,6)`，**禁浮点**；
  「低于成本线」的判定写进 SQL，**不让前端拿浮点比**。
- 时间列 `timestamptz`，`?::timestamptz` + `OffsetDateTime` 绑定，测试**断言写回的值**。
- 权限复用 `catalog.spu.read` / `catalog.spu.update`（**不新增权限码** —— 新词表要动 identity 的迁移）。
- 租户只来自已认证成员关系（`AccessService`），**不信任 `X-Tenant-Id` 头**。
- api-proxy 白名单**新增一条独立 `services[]` 元素**（既有 catalog 长正则一字未动），
  首 token `price-anomaly-alerts` 与既有全部首 token **全不相同**且既有正则都锚定 ⇒ 互不遮蔽。

## 关键 trade-off / 讨论

- **自持观测台账 vs 声明不可用**：也可以只落「能力声明 available=false」交白卷，
  但那样四类异常**一类都识别不出来** ⇒ 违反任务书的硬性要求。
  选定自持台账 + 如实标注口径 + 5.1 落地后经授权 API 供数（表结构不必变）。
- **内置默认阈值 vs 强制先配规则才生效**：强制的话，租户没配规则时**一条都检测不出来**，
  页面上是空的 ⇒ 选了「内置默认值 + 规则表可覆盖 + 页面标 `overridden=false`」。
  默认值是**配置默认值**，不是「租户已同意的阈值」—— 这一点在规则行与能力声明里都写明。
- **站点倒挂的严重度默认 HIGH**：它是跨区窜货与合规风险，不是普通的毛利问题。
- **契约只提交 catalog 一份**：codegen 顺带改动的 `channel.ts` / `channel.openapi.json` /
  `trade.openapi.json` 经核实是**集成头既有的契约漂移**（batch-37 的 channel 源码已进仓而契约未重生成），
  与本车道无关 ⇒ 已 `git checkout` 复原，避免给 10.7.1.3 的 channel 车道制造合并冲突。

## 涉及文件

**后端（catalog）**

- `backend/services/catalog/src/main/resources/db/migration/V222__pricing_price_anomaly.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceAnomalyModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceAnomalyRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceAnomalyService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceAnomalyController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceAnomalyIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceAnomalyRulesTest.java`

**前端**

- `apps/web/src/app/pricing/anomaly-alerts/page.tsx`
- `apps/web/src/app/pricing/anomaly-alerts/price-anomaly-page.test.ts`
- `apps/web/src/components/pricing/price-anomaly-workspace.tsx`
- `apps/web/src/components/pricing/price-anomaly-model.ts`
- `apps/web/src/components/pricing/price-anomaly-model.test.ts`
- `apps/web/src/app/api/catalog/price-anomaly-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条 `services[]` 元素**，既有条目一字未动）

**契约与台账**

- `packages/api-contracts/src/catalog.openapi.json`、`packages/api-contracts/src/contracts/catalog.ts`（**纯新增**）
- `docs/product/capability-blueprint/module-progress.md`（只改 5.5.2.1 那一行的「状态」「证据」两格）

## 验证

- L0：`./mvnw -q compile` 全绿；`pnpm typecheck` 全绿。
- L1：`./mvnw -pl services/catalog -am test -Dtest='PriceAnomaly*'` **26 例 0 失败**
  （`PriceAnomalyRulesTest` 9 + `PriceAnomalyIntegrationTest` 17）。
- 前端：`pnpm vitest run`（本车道三个文件）**42 例 0 失败**；
  **全量 allowlist 回归**（改了 `api-proxy.ts`）`pnpm vitest run apps/web/src/app/api` **624 例全绿**。

## 后续待办

- **5.1 价目表交付后**：内部价改为经**授权 API** 供数（观测台账结构不必变）；
  `price_anomaly_observation.source = 'PRICE_LIST'` 的行由价目表驱动写入，而不是人工登记。
- **5.2.2.1 / 5.2.2.2 汇率交付后**：跨币种站点对的「不可比」可以变成真判定 ——
  比价口径改由汇率快照给出，`fx_rate_date` / `fx_rate_source` 指向汇率快照的版本。
- **5.4.2.1 平台价格同步交付后**：平台价与「期望价」的差异记录可作为另一条输入
  （本模块现在的 ① 用的是平台价 vs 内部价两条观测）。
- **1.3.1.1 / 1.3.2.1 工作台预警交付后**：`price_anomaly_notification` 经授权 API / event 投递，
  `delivered` 才有变成 true 的路径与权限。
- **16 合规的跨区窜货模块落地后**：④ 的事件证据里已留 `complianceHint`，
  窜货风控可经**授权 API / event** 消费本模块的事件。
- **5.5.1.1 价格历史交付后**：两者是**不同口径**（历史 = 改过什么；观测 = 现在每个站点各是多少），
  本表**不改**，历史交付后由它经授权 API 供数。

## 关联

- 蓝图：`docs/product/capability-blueprint/05-pricing.md` § 5.5.2.1
- 上游同服务先例：5.2.1.1 成本构成（V215，成本线来源 + 跨币种纪律 R6）
- 外键纪律先例：channel `V219__return_shipback_to_origin.sql`（触发器代替外键）
- 页面先例：`apps/web/src/app/pricing/cost/`（5.2.1.1，真实工作区的写法）
