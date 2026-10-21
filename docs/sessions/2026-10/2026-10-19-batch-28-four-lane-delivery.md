# 批次 28 · 供应商索赔 / 分摊结果查看 / 商品认领 / 映射缺失队列

> 时间：2026-10-19 22:19 tick 派发 → 23:0x 并入集成头
> 集成头：`integration/module-dev`
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0

---

## 本批回收

`lane.mjs merge-ready` 并入 4 条 ready 车道的过程中，**2.4.2.3 第一次并入失败**：

```
✗ 2.4.2.3 并入冲突：apps/web/src/lib/api-proxy.ts
```

原因：`api-proxy.ts` 是多车道共用的白名单累积文件，**2.4.2.3 与 4.4.2.1 把各自新的
`services[]` 元素插在了完全相同的位置**（基线 `6904288d` 里 `listing-content-writeback`
元素之后、`prefix: 'trade/'` 之前）。三方合并时两边在同一处各插一个块 ⇒ git 判冲突。

按协议「不要自己强行解冲突」，派了一个**只修这一个点**的修复任务：把 2.4.2.3 的元素
换到与 4.4.2.1 拉开 ≥30 行的锚点（内容逐字不变），跑全量 allowlist 回归后重新落地。
修复后并入成功，车道状态由 `conflict` 自动补记为 `merged`。

| 模块 | 名称 | 落地服务 | 迁移 | 车道分支 |
| --- | --- | --- | --- | --- |
| 11.2.3.3 | 供应商索赔 | trade | V188 | `feat/11-2-3-3` |
| 7.8.2.1 | 分摊结果查看 | warehouse | V189 | `feat/7-8-2-1` |
| 4.4.2.1 | 商品认领 | channel | V190 | `feat/4-4-2-1` |
| 2.4.2.3 | 映射缺失队列 | channel | V191 | `feat/2-4-2-3` |

### 静态预检（并入后、跑 L2 之前）

对本批 4 条新迁移跑「已知互相破坏形状」三条扫描：

| 检查 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **未命中** |
| ② `SET NOT NULL` | **未命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **未命中** |

4 条迁移（V188–V191）**全部只 CREATE TABLE**，对既有表的唯一 ALTER 是自建表自己的
`ENABLE/FORCE ROW LEVEL SECURITY`。⇒ **跨车道 DDL 回归面 = 0**。

---

## 模块 11.2.3.3 供应商索赔

**这个功能做什么**：质量原因退回来的货，可以对供应商正式发起一笔索赔 —— 关联到具体批次
与责任判定、登记索赔金额、记录每一轮协商、最后登记赔付；冲减应付**必须由财务确认才生效**，
系统只跟踪流程与金额，不会自动到账。

**技术落地**：

- 迁移：`backend/services/trade/src/main/resources/db/migration/V188__supplier_claim.sql`
  — 5 张新表、8 个守卫触发器、索引、`ENABLE`+`FORCE` RLS isolation、`GRANT trade_app`，
  **零 ALTER 零 DROP 既有表**（对 V179 `return_quality_*` / V180 `product_issue_attribution` /
  V185 `quality_supplier_ref` 全部只读）
- 端点：`SupplierClaimController` — `/trade/supplier-claims` 共 **16 个端点方法**
  （capabilities / candidates / 集合读+建单 / {id} / items 增删 / submit / start-negotiation /
  negotiations / agree / reject / withdraw / settlements / settlements/{id}/confirm /
  settlements/{id}/reject）
- 页面：`/aftersales/returns/supplier-claims` — 筛选 状态/冲减状态/责任环节/供应商/金额区间/
  时间区间/关键字，操作 发起索赔·选候选加明细·记录协商·进入协商·置为已达成·登记赔付·
  **财务确认冲减**·财务驳回赔付·撤回·驳回，加载/空/错误三态齐全
- 测试：`SupplierClaimIntegrationTest` 24 例 + `SupplierClaimTenancyIntegrationTest` 4 例
  （实跑 Testcontainers PG16）；前端 `supplier-claim-model.test.ts` 8 例 +
  `supplier-claim-allowlist.test.ts` 3 例；共享白名单全量回归 42 文件 / 224 例

**关键设计决策**：

1. **12 财务服务尚不存在**（`backend/services/` 下只有 identity / catalog / channel /
   warehouse / trade / crm），蓝图要求的「冲减应付」**不能去读写一个不存在的服务**。
   ⇒ 冲减落在 trade 自己的事实表 `supplier_claim_settlement`；与 12 财务的最终对账留待
   finance 落地后经**授权 API / event** 衔接。否决方案：在 trade 里预建 finance 表结构
   （那是跨服务抢所有权）。
2. **「索赔不是自动到账，冲减应付需财务确认」落成结构而不是文案**：
   `requested_amount`（登记）与 `effective_amount`（已生效）**两列分离**，登记时 effective 恒为 0；
   表级 CHECK 钉死「未 CONFIRMED ⇒ 0 / CONFIRMED ⇒ 等于登记金额且确认人 + 确认时刻 +
   财务单据号齐全」；**不存在「直接置为已结算」的端点**；已确认分录与已结算单整行冻结；
   财务确认用**独立权限码** `trade.aftersales.finance`。
3. **金额全 `NUMERIC` + `BigDecimal`**，`timestamptz` 用 `OffsetDateTime` 绑定并在 values
   位置写 `?::timestamptz`（用 `Timestamp.valueOf` 会静默偏 −28800s），测试断言写回值。
4. **租户隔离三断言用 `trade_app` 连接**，不用 Testcontainers 超级用户 —— 超级用户绕过 RLS，
   用它查永远「读得到」，那是断言本身失效而不是隔离被破。

---

## 模块 7.8.2.1 分摊结果查看

**这个功能做什么**：某个 SKU 在各个店铺分到了多少、已经用掉多少、还剩多少、平台那边挂着多少，
以及**回写失败或延迟带来的实际超卖敞口**；回写状态没回填时明确显示「尚未回填」，
不会拿 0 冒充。

**技术落地**：

- 迁移：`backend/services/warehouse/src/main/resources/db/migration/V189__warehouse_allocation_result_view.sql`
  — 1 张新表 `allocation_writeback_snapshot`（回写状态的**本地投影**）+ 2 个触发器 +
  ENABLE/FORCE RLS + GRANT，**零 ALTER 既有表**
- 端点：`AllocationResultController` — `/warehouse/inventory/allocation-results/` 共 **6 个**
  （capabilities / results / runs / reconciliation / writebacks GET+POST）
- 页面：`/inventory/allocation-results`（另在 `/inventory/allocation/results` 留同工作区入口）—
  筛选 SKU/仓/店铺/分摊轮次，操作 查看分摊明细 / 核对回写一致性 / 查看风险敞口，三态齐全
- 测试：`AllocationResultIntegrationTest` 25 例（Testcontainers PG16）；
  前端 `allocation-result-model.test.ts` 20 例 + `allocation-result-allowlist.test.ts` 5 例；
  共享白名单全量回归 77 文件 / 739 例

**关键设计决策**：

1. **4.3.2.1 平台回写在 channel 服务 ⇒ 禁止跨服务读表**。回写状态以 warehouse 自己的
   **投影事实**落地：每条快照带**来源标识（EVENT/API/MANUAL）+ `as_of` 采集时刻**；
   一致性核对只基于本地投影算。否决方案：直接在 warehouse 里 join channel 的表。
2. **「不知道」≠ 0**：`writebackState=UNKNOWN` + 字段全 null，界面渲染成「尚未回填」+「—」。
   这是本模块最重要的一条 —— 缺一个投影就报 0，等于把同步窗口的延迟藏起来。
3. **敞口落成结构**：敞口 = `max(0, 平台挂着的 − 我方剩余)`；缓冲取 7.2.2.1 的
   `sellable_policy.buffer_ratio`（与 7.8.1.1 分摊缓冲、7.8.1.2 保底池**三者未混用**）；
   敞口 > 缓冲 ⇒ 判 `BEYOND_BUFFER`（缓冲不足 ⇒ 同步窗口内会超卖）。
4. **顺带修掉两个真缺陷**：
   ① 本模块 DTO 一度与 7.8.1.1 的 `AllocationRunPage` **重名** —— SpringDoc 的
   `components/schemas` 以 schema 名为键，同名后者覆盖前者，**悄悄换掉了 7.8.1.1 的契约**
   （其 typecheck 报 `starvedLineCount 不存在`），而 codegen 的重名检测只跨服务、不跨同服务模块，
   查不出来。已改名，契约 diff 回到纯增量。
   ② api-proxy 白名单若插在 `allocation-priority` 之后会落在**组外**的顶层分支，整组 404
   而 typecheck / lint / vitest **全绿**。锚点改到确定在 `inventory/` 组内的位置。

---

## 模块 4.4.2.1 商品认领

**这个功能做什么**：把 A 店铺已经有的 listing「认领」到 B 店铺 —— 复用原商品内容与映射，
在目标店铺生成一份**新的 listing 草稿**并挂一个待发布任务；源 listing 完全不受影响。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V190__listing_claim.sql`
  — 2 张新表 `listing_claim` / `listing_claim_field` + 4 个触发器，**零 ALTER 既有表**
- 端点：`ListingClaimController` — `/channel/listing-claims` 共 **5 个**
  （capabilities / 集合读 + 认领 / {id} / listings/{listingId}/claims）
- 页面：`/listings/claim` — 源商品选择 → 目标店铺/站点选择 → 逐字段保留/重写/跳过三步流程，
  加载/空/错误三态齐全
- 测试：`ListingClaimIntegrationTest` 12 例 + `ListingClaimTenancyIntegrationTest` 3 例；
  前端 `listing-claim-model.test.ts` 23 例 + `listing-claim-allowlist.test.ts` 4 例；
  共享白名单全量回归 76 文件 / 498 例

**关键设计决策**：

1. **归属 channel 而非 catalog**。蓝图 README 的「后端主责服务」列写系统 04 = `catalog + channel`
   （首项 catalog），编排脚本取首项判服务；但已交付的 4.x listing 族（4.1.1.1 / 4.1.1.2 /
   4.3.1.2 / 4.3.2.1）**全部在 channel**。台账 4.1.1.1 的证据已写明理由：「listing 必须按
   店铺/站点筛选并收敛可见性，而 `shop`/`shop_marketplace` 是 channel V1 的事实且带
   data_scope RESTRICTIVE 策略；**放 catalog 就得为店铺维度数据范围去读 channel 的表 —— 那是跨服务 SQL**」。
   ⇒ 本模块沿用同一先例落 channel，并把理由写进台账证据格。
2. **「源 listing 不受影响」落成结构**：服务层对源只读；V190 触发器
   `guard_claim_source_untouched` 在认领记录落库时复核源的 status / review_status / version
   逐字相等；认领记录同时存「认领时」与「现在」两组快照供界面比对；测试逐字断言源的状态 /
   版本 / updated_at / 状态事件条数全不变。
3. **「新店铺 listing 是全新平台资源，需重新过平台审核」落成结构**：目标恒为 `DRAFT` /
   `NOT_SUBMITTED` / 平台可售 `UNKNOWN`，评分/币种/上架时间/可售量全 NULL，
   **零 `listing_publish_record` 写入**；待发布任务**复用 V183** 的 `publish_batch` + `publish_task`
   （PENDING），不建平行发布任务表。4.3.1.1 一键刊登未交付 ⇒ 本模块只**创建任务**，不实际发往平台。
4. **不复制任何列**：03 商品只经逻辑 id 引用，不建 FK；售价/库存/销量不读也不建列。

---

## 模块 2.4.2.3 映射缺失队列

**这个功能做什么**：平台商品已经同步进来、但没映射到内部 SKU 的变体，在这里排成一条待办清单，
运营可以**映射到已有 SKU / 新建 SKU / 标记忽略**；系统**不会按名称猜 SKU**。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V191__listing_mapping_gap_queue.sql`
  — `listing_mapping_gap` + `listing_mapping_gap_sku_request` 两张新表、9 个索引
  （含部分唯一索引 `listing_mapping_gap_pending`）、3 个触发器、RLS isolation + data_scope +
  `GRANT`（`starcon_app` 零权限）
- 端点：`ListingMappingGapController` — `/channel/mapping-gaps/**` 共 **9 个**
  （清单 / summary / filter-options / capabilities / 详情 / map / create-sku / ignore / orders）
- 页面：`/channels/mapping-gaps` — 六筛选维度 + 三个操作入口 + 三态齐全
  （空态区分「一条缺口都没有」与「有筛选但没结果」）
- 测试：`ListingMappingGapIntegrationTest` 13 例（实跑 Testcontainers PG16）；
  前端 `mapping-gap-model.test.ts` 15 例 + `mapping-gap-allowlist.test.ts` 5 例；
  共享白名单全量回归 75 文件 / 477 例

**关键设计决策**：

1. **「不按名称猜 SKU」是硬约束**：表层**没有** suggested / score / candidate 任何一列，
   「映射到已有 SKU」**复用 4.1.2.1 的 `ListingMappingService.save`**，`internalSkuId` 必须由人给出；
   测试断言清单 JSON 里不得出现 `suggest·candidate·confidence·score·similarity` 任何键。
2. **「历史订单冻结当时映射版本」**：缺口记录「当时那一版」；V191 触发器让**回填目标写下即不可改写**；
   测试断言改映射生成 v2 后缺口仍指向 v1、DB 拒绝改写回填目标与已关闭版本、按历史时刻 resolve
   仍拿到当时的 SKU。
3. **09 订单来源在 trade ⇒ 跨服务**：本批**未接线**，接入点已建
   （`source='ORDER'` + `order_ref` + `POST /mapping-gaps/orders`）但**无生产者**，
   能力声明 `orderSourceWired=false`。**不写假数据、不假装已接**。
4. **价格为诚实空值**：channel 侧无平台售价事实（05 定价整卷未交付）⇒ `priceAvailable=false`，
   页面显示「未接入（归 05 定价）」而非 0；价格区间筛选是**真 SQL**，只覆盖带快照的行。

---

## 本批跨车道复核（调度者独立核对，不采信 worker 自述）

| 车道 | 契约 | schemas | paths | 丢失 |
| --- | --- | --- | --- | --- |
| 11.2.3.3 | trade.* | 1004 → 1030（+26） | 434 → 449（+15） | **0** |
| 4.4.2.1 | channel.* | 333 → 339（+6） | 204 → 208（+4） | **0** |
| 2.4.2.3 | channel.* | 333 → 351（+18） | 204 → 213（+9） | **0** |
| 7.8.2.1 | warehouse.* | 960 → 973（+13） | 449 → 454（+5） | **0** |

两条 channel 车道的 path 集合**互不相交**（`listing-claims*` vs `mapping-gaps*`），
`resolve()` 顺序不会互相误吃；合并后 channel 预期 **schemas 357 / paths 217，零丢失**。

`api-proxy.ts` 三条车道全部是**纯插入**（+47 / +41 / +39，删除 0），锚点互不重叠；
7.8.2.1 改的是 warehouse/inventory 那条长正则（456 / 691），与 channel / trade 组不撞。

**顺带修掉的一个缺口**：7.8.2.1 上一轮把 codegen 产物写进临时目录（避免覆盖其他车道的契约），
只把 `contracts/warehouse.ts` 拷回仓库，**漏掉了同批的 `warehouse.openapi.json`**。
后果是仓库里 warehouse 的 OpenAPI 快照仍只记录 449 条 path，本批的 5 条端点在快照里不存在，
而**仓库里没有校验 openapi.json 与后端 spec 同步的测试**（`pnpm test` 不覆盖）⇒ 缺口会静默合入。
已用该车道自己 codegen 的输出补回（schemas 960→973，paths 449→454，零丢失）。

---

## L2 批量验证

本批全量验证跑在集成头 `b135026e`（4 条车道已并入）：

| 相 | 内容 | 结果 |
| --- | --- | --- |
| ① | `cd backend && ./mvnw verify` | **BUILD SUCCESS**（23:26 min）|
| ② | `pnpm typecheck` | ✅ 3/3 successful |
| ③ | `pnpm lint` | ✅ 3/3 successful（仅存量文件既有 react-hooks warning）|
| ④ | `pnpm test`（vitest run） | ✅ **261 文件 / 4335 例全绿** |
| ⑤ | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ Next production build 成功 |
| ⑥ | `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

后端各服务：identity 1:22 · catalog 2:43 · channel 1:30 · warehouse 10:22 ·
**trade 1391 例 0 失败 0 错误** · **crm 18 例 0 失败 0 错误**。

### 第一次跑红：1 例 trade 错误 —— 隔离复跑后判定为**边缘时序 flake，不是本批回归**

第一次 `mvnw verify`：trade **1391 例里 1 个 error**，`crm` 被 reactor 跳过：

```
[ERROR] OrderChangeCancelIntegrationTest.平台允许且在窗口内_减量申请通过并算出退款金额
        » ResponseStatus 400 BAD_REQUEST
        "平台 AMAZON / 店铺 SHOP-1 没有登记「减少数量」的可变更规则：没有规则不等于允许"
[ERROR] Tests run: 1391, Failures: 0, Errors: 1
```

按协议「成片失败先隔离复跑，不要直接归因成回归」定位，结论是**不是回归**：

1. **失败点在本批没碰过的代码上**：`order_platform_change_rule`（9.x 平台可变更规则）不在本批
   4 条迁移的 ALTER 范围内 —— 静态预检已确认 V188–V191 对既有表**零 ALTER**。
2. **整模块复跑全绿**：同一 head（`b135026e`，含本批全部 4 条车道）上单独跑 trade 全量
   → **1391 例 0 失败 0 错误 / BUILD SUCCESS**；被 reactor 跳过的 crm 单独跑 → **18 例 0 失败**。
3. **机制定位**：这个断言比较的是**两支不同的钟** —— 测试用 JVM 钟 `OffsetDateTime.now()`
   写 `effective_from`，而服务查询要求 `effective_from <= now()`（**数据库钟**）。
   实测本机 Docker 里 PG 容器钟**落后宿主 JVM 约 0.1–0.3 秒**（括号法测三次：
   落后 0.113s / 0.192s / 0.272s）。插入到查询的间隔一旦短于这个差值，规则就「还没生效」，
   服务按 fail-closed 返回「没有登记…没有规则不等于允许」；而断言要的是「不允许减少数量」
   —— 两句不同 ⇒ 失败。**既有测试的边缘时序脆弱，与本批 4 个模块无关。**
4. **第二次全量 `mvnw verify` 全绿**（23:26 min，见上表）。不是"重跑一次就好"搪塞，
   是把机制查清之后拿到的干净一次全绿。

⚠️ **遗留待办（不属于本批 4 个模块，本轮未擅自改）**：`OrderChangeCancelIntegrationTest` 的
`rule(...)` 辅助方法用 JVM 钟写 `effective_from`，在容器钟落后宿主的机器上会偶发不可见。
稳妥写法是改用数据库钟 `now()`，或写一个明显更早的时刻。
这属于 trade 服务的既有测试健壮性，建议单独派一个修复任务。

## push

L2 绿之后按「收尾三件套」执行：

- ① 写说明 → 本文件 `docs/sessions/2026-10/2026-10-19-batch-28-four-lane-delivery.md`
- ② commit → 说明文件与台账**同一个 commit**（`docs(batch-28):` 前缀）
- ③ push → `git push origin integration/module-dev`（**只推集成头，不推 main**）

本批推送模块：**11.2.3.3 供应商索赔 · 7.8.2.1 分摊结果查看 · 4.4.2.1 商品认领 ·
2.4.2.3 映射缺失队列**（迁移 V188 / V189 / V190 / V191）。
