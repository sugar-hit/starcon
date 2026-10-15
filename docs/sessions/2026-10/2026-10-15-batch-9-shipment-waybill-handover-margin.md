# 批次 9 · 发货记录 / 运单申请 / 交运批次 / 代发差价与对账

> 2026-10-15 08:00 批次 · 4 条车道并行开发 · L2 全量验证一次通过
> 集成头 `integration/module-dev` · 车道基线 `e0a67d10` · 交付后集成头 `56d07b4f`

本批交付 **9.4.1.2 发货记录**、**10.2.1.2 运单申请**、**10.3.2.1 交运批次**、**9.6.2.1 代发差价与对账**
四个模块，覆盖 trade 与 channel 两个服务，Flyway 版本 V127–V130。

---

## 模块 9.4.1.2 发货记录

**这个功能做什么**：运营能看到「哪些货已经真的发出去了」——包裹、运单号、承运商、发货时刻、
轨迹状态、买家可见状态、平台回传状态，可筛选可下钻。这是平台绩效（迟发率、有效追踪率）的计算依据。

**技术落地**：

- 迁移：`V127__trade_shipment_records.sql` — 六张表 + 序列 + 触发器 + RLS/GRANT，**零 ALTER 既有表**
- 端点：`/trade/fulfillment/shipment-records`，11 个形状
  （`ShipmentHandoverQueryPort` / `ShipmentRecordService` / `ShipmentRecordController`）
- 页面：`/orders/fulfillment/shipment-records`（工作区 + model + 三态齐全）
- 测试：`ShipmentRecordIntegrationTest` 19 例 + `shipment-records-model.test.ts` 15 例 +
  `shipment-records-allowlist.test.ts` 6 例 = **40 例**
- 提交：`70f15e1a`（8 个 atomic commit）→ 并入 `420f8bf7`

**关键设计决策**：

1. **它就是「已发货」事实的登记处**。同 L3 兄弟 9.4.1.1 的状态机止于 `HANDED_OVER`，
   明确「从不产生已发货」——因为已发货是 warehouse 8.3.4.1 在**揽收后**才确认的事实。
   提前置它会让发货记录与 17 绩效的迟发率算在一个从未发生的时刻上。
2. **`SKIPPED`（决定不回传，强制填原因）与 `UNSET`（漏了）在四层分开**：
   枚举、DB CHECK、报错文案、界面标签配色。并进同一桶就再也分不出「跳过」与「漏了」。
3. 快照列由 DB 触发器钉死等于最新凭据行，避免与登记行漂移。
4. 轨迹 / 买家可见 / 平台回传三个维度 `available=false` 并点名 ownerModule
   （10.3、2.5.1.1、9.4.2.1），未登记的落**独立 UNSET 桶**，不并进正常桶。

---

## 模块 10.2.1.2 运单申请

**这个功能做什么**：向承运商申请运单号——传收寄信息、包裹明细、申报信息，支持批量申请、
失败重试、按渠道限流排队，重复提交不会造出第二张运单。

**技术落地**：

- 迁移：`V128__trade_waybill_application.sql` — 5 张业务表 + 限流口径视图 + 5 触发器，
  **零 ALTER 既有表**；视图单独 GRANT SELECT
- 端点：`/trade/logistics/waybill-applications`，10 个形状
- 页面：`/logistics/waybills`
- 测试：`WaybillApplicationIntegrationTest` **32 例** + `waybill-model.test.ts` 24 例 +
  `waybill-allowlist.test.ts` 5 例 = **61 例**；trade 全量 **401 例绿**，
  另跑 191 例 proxy 回归（含兄弟十组反向断言）
- 提交：`3007df23` → 并入 `4a68307e`

**关键设计决策**：

1. 🔴 **这个模块现在出不了号，而且这是刻意的**。10.1 物流渠道（承运商列表 / 渠道配置 / 时效限制）
   台账确认**全部未交付**，所以承运商建成「可登记的本地事实 + 适配器端口」，
   **没有 vendor 实现时 `resolve` 如实返回 Unavailable，该票 FAILED 并写明 ownerModule**。
   DB 层 CHECK + 触发器保证 `ISSUED` **只能**由承运商真实返回时进入 —— 测试里连运单号都是
   断言「全库零条」。**一个返回成功回执的假适配器，会让「已出号」这个从未发生的事实进入台账。**
   10.1 接通后新增 vendor 包即可，表与服务层一行不用改。
2. **批量申请必须按渠道限流排队**，做成**可见的持久化队列 + 逐条结果**而非同步阻塞循环；
   一条请求不许跨渠道。
3. `idempotency_key` 是输入契约不是优化：DB 层唯一索引钉死，并写反例测试证明它真挡住了。
4. 过程中修掉 4 个真实缺陷：负页码算出负 OFFSET 却报 409「数据冲突」、注册表见到实现就 Connected、
   队列失败时不报「未到期条数」、视图漏 GRANT SELECT。

---

## 模块 10.3.2.1 交运批次

**这个功能做什么**：把已交接的包裹按承运商与交运时间组批，生成**交接清单**交接给承运商并登记回执。
批次交接是责任分界点——批次内包裹丢失的责任在承运商，清单是法律凭据。

**技术落地**：

- 迁移：`V129__logistics_handover_batch.sql` — 4 表 + 7 触发器 + ENABLE/FORCE RLS，
  **零 ALTER 既有表**
- 端点：`/channel/handover-batches`，12 个（**刻意无 DELETE**）
- 页面：`/logistics/handover-batch`
- 测试：`HandoverBatchIntegrationTest` 14 例 + `handover-batch-model.test.ts` 25 例 +
  proxy 补 25 例；channel 全量 **89 例零回归**
- 提交：`6cfdabe3` → 并入 `6c4c6c93`

**关键设计决策**：

1. **交接清单是 append-only，两层锁**：运行角色 REVOKE + 触发器逐字段不可改写。
   批次一旦交接，其成员与交接内容不可被静默改写。
2. **冻结点钉在 `HANDED_OVER`，不是 `DISPATCH_PENDING`**。初版钉错了 —— 那等于把自己的作废功能锁死：
   组错承运商就只能硬交。货权实际在**承运商确认接收**那一刻转移。
   「登记过交接又作废」仍留在 append-only 事件时间线上。
3. **`BEFORE UPDATE` 触发器必须返回 `NEW`**，返回 `OLD` 会**静默丢弃整行更新**——
   看着在拦、实际什么都不做，不变量写了等于没写。测试当场抓到。
4. 承运商适配器**只定义端口、刻意不放假 vendor 实现**（同 10.2.1.2 的理由）。
   运单号无来源时**如实留空 + 缺口码，绝不出库单号冒充**。
5. 跨服务取数走 trade 的授权 HTTP API（`/trade/fulfillment/pending-shipment`，转发调用方自己的 bearer），
   **一条 SELECT 都不碰对方库**，租户隔离是 channel 侧 RLS ＋ trade 侧 RLS 两道。

---

## 模块 9.6.2.1 代发差价与对账

**这个功能做什么**：算清代发业务的成本与结算——代发采购成本（供应商结算价 + 供应商运费）、
我方售价、差价毛利；按周期与供应商对账；**识别亏损代发单并预警**。

**技术落地**：

- 迁移：`V130__trade_dropship_margin_reconciliation.sql` — 9 表 + RLS ENABLE/FORCE，
  **零 ALTER 既有表**
- 端点：`/trade/fulfillment/dropship-margin`，18 路径 / 20 方法
- 页面：`/orders/fulfillment/dropship-margin`
- 测试：`DropshipMarginIntegrationTest` **42 例** + model 27 例 + allowlist 5 例 = **74 例**；
  trade 全量 **411 例绿**（含 9.6.1.1/9.6.1.2/9.6.1.3 无回归）
- 提交：`0a1d5469` + `02b4e0b1` → 并入 `52804251`

**关键设计决策**：

1. 🔴 **平台费未登记时，结论是三值的，不是两值的**。5.2.3.1 平台费用未交付，
   判据「售价低于代发成本 **+ 平台费**」的第二个加数不存在。**按 0 算会同时犯两个方向的错**：
   真实亏损被放过去，且所有代发单被染成亏损把真亏损淹掉。于是：

   | 情形 | 结论 |
   | --- | --- |
   | 售价 30 / 成本 12 / 费率未登记 | **无法判定**（≠ 不亏） |
   | 售价 5 / 成本 12 / 费率未登记 | **已确认亏损** —— 平台费恒 ≥0，少算只会更大 |
   | 售价 30 / 成本 12 / 费率 15%（4.5） | 已含平台费，不亏（13.5） |
   | 售价 30 / 成本 12 / 费率 70%（21） | **已确认亏损**（−3） |

   `无法判定` **单独成桶不并进不亏**；`spread_after_fee` 未登记时整列 **NULL 而非 0**；
   界面用 neutral 色调（该做的动作是补登记费率，不是查供应商）。
2. **代发的「成本」是采购差价而非自有库存成本**，利润口径与自营不同 ⇒ 报表**必须按经营模式分组**，
   否则代发店铺利润会被算错（蓝图 9.6.2.1 的原文要求）。
3. 禁浮点：金额 `BigDecimal` + `NUMERIC(19,4)`，数量 `NUMERIC(19,6)`；
   毛利必须能从自己的构成项重新算出并在测试里**断言这个等式**，而不是断言硬编码数字。
   多币种**不许跨币种直接相加**，汇率未登记时如实标 UNSET（不用 1.0 假装）。
4. 对账结论把**当时用的费率、阈值与算式写进自己的行**，事后改费率不能让历史对账单看起来是按新费率算的。

---

## 本批的调度层工作（非模块本身，但影响了这批能否交付）

### 1. 三条 trade 车道的 `api-proxy.ts` 冲突

`apps/web/src/lib/api-proxy.ts` 是累积白名单，按服务分段。channel 车道直接合入，
三条 trade 车道全部撞在**同一个顶层 alternation token** 上。

解法不是猜，是先量化：基线 trade 有 **12 个顶层 token**，每条车道**恰好新增 1 个**，
且三个 namespace（`fulfillment/shipment-records/`、`logistics/waybill-applications`、
`fulfillment/dropship-margin/`）**互不重叠** ⇒ 求并无歧义。

**判据不是行为级**。用各服务自己生成的 **OpenAPI 契约 path 当用例集**（1105 条，随代码自动变化）：

| | 契约 path | 放行 | 未放行 |
| --- | --- | --- | --- |
| 本批前基线 | 1105 | 982 | 123 |
| 本批后集成头 | 1105 | **1031** | 74 |

**新缺口 0 条，修好 49 条**，无一条兄弟车道路径被挤掉。

### 2. 车道基线在脚下被换掉（Next 14 → 15）

本批车道从 `e0a67d10` 切出，而 main 的合并（`d8b15e93`，PR #7/#8/#9）把集成头带到了新基线，
其中 `apps/web/package.json` 把 **Next `^14.2.18` → `^15.5.27`**。
Next 15 把路由处理器的 `ctx.params` 改成 `Promise`，于是本批三个白名单测试
共 **18 处** `params: { path }` 全部过时，L2 typecheck 红。

分两次修复（`26e7a979` 5 处、`56d07b4f` 13 处），一律照抄仓库既有约定
`api-proxy-union.test.ts:69` 的 `params: Promise.resolve({ path: ... })`。

⚠️ 第一次给 worker 的任务书把范围写成「5 处」，实际是 18 处 —— 因为 L2 日志被我用
`tail -15` 截断，隐藏了 13 条错误。**截断的日志会隐藏证据**，这是本批的一个教训。

### 3. 一个假信号与一次自我更正

- **假信号**：`merge-ready` 连续多轮报「9.1.1.3 / 9.7.1.1 仍有未解决的并入冲突」。
  实际两者**早已是集成头的祖先**，台账早已是「已交付」，V119/V120 都在。
  真因是这两条车道 **`headSha` 字段缺失**，把 `lane.mjs` 的自动补记路径
  `lane.headSha && <祖先判定>` 直接短路 ⇒ 状态**永久**卡在 `conflict`。
  补上 `headSha` 后脚本自动补记为 merged，`gc` 回收了两个滞留 worktree。

- **自我更正**：我据 `grep` 补丁文件断定「braces 的 CVE 加固没覆盖 AST 遍历路径」。
  **这是错的** —— 补丁里 5 处守卫全在。worker 核实后推翻：真因是 **`node_modules`
  相对 lockfile 严重陈旧**（`require.resolve('braces')` 直接 `MODULE_NOT_FOUND`，
  store 里是未打补丁的副本，vitest 3.2.7 vs lockfile 4.1.11）。
  一次 `pnpm install` 即恢复，**一行源码都没改**。
  教训：取证要取**生效**的那一份，不是仓库里声明的那一份。

---

## L2 批次闸结果（全量，集成头上跑一次）

```
backend  ./mvnw verify          BUILD SUCCESS   11:33
         typecheck               Tasks: 3 successful, 3 total
         lint                    3 successful
前端     vitest                  Test Files 161 passed / Tests 2931 passed
         Next production build   成功
         sync --check            ✓ 台账与蓝图一致
```

**静态预检**（先于 L2，几秒）：

```
① ADD COLUMN 无 DEFAULT        → 唯一命中是 V130 的 GENERATED ALWAYS AS ... STORED
                                  （生成列不可被 INSERT，且该表在 V130 内新建）= 误报
② ALTER 既有表                → 命中全是同迁移内新建表 + RLS ENABLE/FORCE = 误报
③ ADD CONSTRAINT CHECK        → 0 条
```

**四条迁移零 ALTER 既有表**，跨车道 DDL 回归面为 0。

**台账**：465 行无重复，已交付 **152 → 156**，未开发 **313 → 309**。

---

## 遗留 / 下游缺口

| 缺口 | ownerModule | 影响 |
| --- | --- | --- |
| 承运商连接 / 渠道配置 | 10.1.1.1 | 运单申请出不了号；交运批次停在 `DISPATCH_PENDING` |
| 渠道时效与限制 | 10.1.1.3 | 无法提示截单 |
| 运单改址与取消 | 10.2.1.3 | `WAYBILL_CANCEL` 不可用 |
| 平台费用 | 5.2.3.1 | 代发亏损判定退化为「无法判定 / 已确认亏损」两分 |
| 应付单 | 12.1.2.1 | 供应商对账单落**待办**，未交给下游前不画成已完成 |
| 多模式利润报表 | 13.4.1.x | 只出本模块口径，不冒充下游 |
| 物流轨迹 / 异常件 | 10.3.1.1 / 10.3.1.2 | 批次「已交接」≠「已揽收」，页面按此措辞 |
| 平台回传 | 2.5.1.1 / 9.4.2.1 | 发货记录的买家可见与回传状态落独立 UNSET 桶 |

## 待办（调度层，不属模块）

1. **给 `braces` 补丁加一条 CI 守卫**：目前没有任何机制校验补丁真的被应用了，
   而 `auditConfig.ignoreCves` 恰好忽略了 CVE-2026-93687 ⇒ 守卫失效时审计层也不兜底。
2. `OrderChangeCancelIntegrationTest`（9.1.1.3）用 `-Dtest=A,B` 指定顺序跑会红，
   换 `PendingShipmentIntegrationTest` 同样复现，单独跑全绿、默认顺序跑全量绿 ——
   集成分支上既有的 Spring 上下文/测试顺序问题，本批只新增文件未改共享测试。
   **下次 L2 红时先排除这一条，别当新回归去二分。**
