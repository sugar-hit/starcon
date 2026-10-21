# 2026-10-21 5.4.2.1 平台价格同步

## 决策摘要

交付 5.4.2.1「平台价格同步」（catalog 服务，阶段 D6，L3 锚点 5.4.2）。蓝图那句加粗句
是本模块存在的全部理由：

> **平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，不能假设回写即成功。

所以本模块的产出不是「一个回写接口」，而是**三样结构化事实**：

1. **回写任务**：复用 channel 已交付的 02 队列（2.5.1.1 `platform_operation`，
   `operation=UPDATE_PRICE`），**不自造第二套队列**、**不自己造平台 HTTP 客户端**
   （本仓没有真实平台账号与网络出口）。
2. **回写结果**：期望价 / 平台承接价 / 平台实际价 / 差额 / 结局，落成可查可算的事实。
3. **差异记录**：`platform_price_sync_diff` 只追加 —— 一次回写留两条
   （规则判定一条 + 平台回执一条），后来的结论**不覆盖**前面的历史。

上游边界：5.4.1.1 的 `price_adjustment_writeback` 是它的**唯一写入所有者**。
本模块**只读**它（同一服务、同一张库），结掉一条事实只能走它的 ack 端点
（同服务内直接调服务方法 —— 自我 HTTP 调用会在单连接连接池下把自己堵死）。
**本车道未对那张表做任何 GRANT 变更，也没有任何触发器写它。**

### 平台定价规则落成真实数据

蓝图点名的三种平台行为（最低价 / 自动比价 / 多站点联动）落成 `platform_price_rule`
表里**租户自己配的规则**，不是代码里的常量：

| 规则 | 触发条件 | 判定 |
| --- | --- | --- |
| `MIN_PRICE` | 期望价 < 平台最低价 | **REJECTED**（本次**不回写**） |
| `AUTO_COMPARE` | 期望价 > 对比价 × (1 + 容差) | **ADJUSTED**（压到对比价） |
| `MULTI_SITE_LINK` | 期望价越出参照站点价格带 | **ADJUSTED**（拉到带边界） |

判定口径钉死四条：拒收优先于改价（最低价是硬门槛）；**判不出来 ≠ 判成通过**
（对比价缺观测 / 币种不同 ⇒ 该规则**不参与**并把原因写进结果与差异台账）；
币种不同**绝不按 1:1 折算**；越具体的规则越先判（SKU 级 > 站点级 > 平台级）。

### 最低价触发为什么**不**发平台调用

平台根本不收低于门槛的价，发过去只是浪费一次平台调用，还会让 02 队列里多一条
永远失败的���务。所以拦截发生在**派发之前**，但**原因必须显性**：落进
`platform_price_sync_diff`（outcome=REJECTED / reason_code=BELOW_MIN_PRICE），
并回执上游把这条事实结掉（`ack_note` 写明「平台拒收：…��」）。

上游那张表对 `order_item_id` 有唯一约束 ⇒ 同一条明细**不可能**再产生第二条待回写事实。
所以「拒收」不结掉它 = 永远卡在 PENDING，运营再也重试不了。结掉，并把结论留在自己的差异台账里。

### 「登记式边界」与「能力协商」

不自己打真实平台域名。平台那一跳收敛在 channel 的 vendor 适配层里，本模块只负责投递**改价意图**。
投递前先查 channel 里**已登记**的店铺与站点：

- 该平台 / 站点**没有登记**店铺 ⇒ 平台侧根本没有这个落点 ⇒ 显性拒收（`NOT_REGISTERED`），
  **不凭空造一个任务出去**；
- **渠道不可达** ⇒ 本轮记 `FAILED`，且**不结**上游事实（它还得留在 PENDING 里等下一轮重试），
  下一轮重投复用同一条记录、差异台账只追加。**「没落点」与「这次没发出去」是两件事**，
  混在一起会把一次临时抖动写成永久结论。

租户上下文靠**透传调用者的 Authorization 头**解析，**不发明 `X-Tenant-Id` 旁路**
（那等于新建一条「客户端自称租户」的路）。`CHANNEL_URL` 没配时**显式抛错**而不是假装投递成功 ——
假装成功会让页面显示「已回写平台」，而平台侧什么也没发生，那正是那句加粗句最典型的违反方式。

## 关键 trade-off

1. **复用 channel 的 02 队列，不在本模块建「回写任务」表。**
   蓝图输出句是「回写任务（经 02 队列）」，而 2.5.1.1 的回写任务列表已在 channel 交付
   （V2 `platform_operation`）。另起一套队列 = 两个写入所有者 + 两套重试语义。
   ⇒ 本模块的表**没有** attempts、**没有**调度器、**没有**重试计数器；
   重试语义住在 channel 那张表里（`attempts` / `verification`）。
   本模块自己的 `platform_price_sync` 是**一条待回写事实在本模块的处置与结果**，
   不是队列。

2. **「平台实际价」有两个来源，分开记，不合并。**
   `expected_price`（我们**要求**平台收的价）/ `predicted_price`（规则判定出的**平台承接价**）/
   `actual_price`（平台回执给的**实际价**）。合并成一个「价格」列就看不出「平台到底改了多少」。
   差异台账记**差额**（带符号）与**偏离比例**，前端直接给整句
   「期望 120.0000 USD → 实际 118.0000（差 -2.0000，偏离 1.67%）」。

3. **「还没结论」与「结论是 0」必须分得开。**
   平台没回执时 `actual_price` 是 null，页面显示「待平台回执」，**绝不渲染成 0** ——
   0 会让页面显示成「平台把价压到了 0」，那是一个凭空造出来的结论。
   前端单测里有一条专门钉这个（`priceText(null)` 与 `priceText(0)` 必须不同）。

4. **`PENDING` 是独立结局，不并进成功。**
   投递出去之后、在平台回执之前，状态是 PENDING：**既不是成功也不是失败**。
   把它算进成功就是那句加粗句的字面反面。
   `platform_price_sync` 的表级 CHECK 钉死这个自洽性：
   `outcome='ACCEPTED'` ⇒ 实际价 = 期望价且差额 = 0；`outcome='ADJUSTED'` ⇒ 实际价 ≠ 期望价
   且差额 = 实际价 − 期望价；`REJECTED`/`FAILED` ⇒ 没有实际价。

5. **对比价取自 catalog 同库的价格观测台账（5.5.2.1 `price_anomaly_observation`）。**
   本仓唯一的真实平台价来源就在同库，且只取该 SKU 在各平台 / 站点的**最新一条**观测。
   取不到就是取不到（不拿 0 顶）⇒ 对应规则不参与判定并写明原因。
   🔴 币种必须同：EUR 的参照价与 USD 的期望价放在同一个比值里算，算出来的是数字不是结论。

6. **规则作用域的唯一性用**部分唯一索引**（`WHERE status='ACTIVE'`），不用表级 UNIQUE 约束。**
   两条理由：① PostgreSQL 的表级 UNIQUE 约束**只接受列名**，不接受表达式
   （`coalesce(sku_id, …)` 报 syntax error）⇒ 必须用唯一索引；
   ② 停用的规则**不占坑** —— 「停用旧规则 + 新建一条同作用域的规则」是正常运营动作，
   若停用的也占坑这条路就被堵死了。

7. **`price_adjustment_writeback` 上游表有触发器 ⇒ 测试必须走真服务造事实。**
   它要求事实挂在真实存在的调价单明细上，所以测试**真的走一遍建单 → 提交 → 执行**，
   让事实由上游自己产出，而不是绕过触发器插一行假的。

8. **测试与「共享容器」的关系：每条用例把生效规则停用 + 同步只认自己的 SKU。**
   测试容器在同一个 JVM 里被所有测试类共享，规则表不会自动清空 ——
   留着上一条用例建出来的生效规则，下一条用例建同作用域的规则就会撞唯一约束，
   测出来的失败原因与被测行为无关。同步请求带上 `skuId` 之后，
   「本轮只消费自己那条事实」也变成可断言的（否则一条用例会把别的用例留下的事实一起消费掉）。

## 文件清单

### 迁移（catalog 服务，V245）

- `backend/services/catalog/src/main/resources/db/migration/V245__platform_price_sync.sql`
  —— `platform_price_rule` / `platform_price_sync` / `platform_price_sync_diff`
  三表 + RLS + GRANT + 三个引用校验触发器。

### 后端（catalog 服务）

- `PlatformPriceSyncModels.java` —— 契约形状（DTO 一律以 `PlatformPriceSync` 开头，
  避开 codegen 的同名覆盖）。
- `PlatformPriceSyncRules.java` —— 纯规则引擎（无 IO、无租户上下文）。
- `PlatformPriceSyncPort.java` —— 跨服务端口（catalog → channel）+ HTTP 实现 + Unwired 实现。
- `PlatformPriceSyncConfiguration.java` —— 端口装配。
- `PlatformPriceSyncService.java` —— 服务层（jOOQ 显式 SQL）。
- `PlatformPriceSyncController.java` —— 9 个端点。

### 后端测试

- `PlatformPriceSyncFixture.java` —— 共享底座（继承 5.4.1.1 的底座，**不改它一个字符**；
  自带 02 队列替身 + 造真上游事实的夹具）。
- `PlatformPriceSyncIntegrationTest.java`（22 例）
- `PlatformPriceSyncTenancyIntegrationTest.java`（3 例）
- `PlatformPriceSyncRulesTest.java`（11 例）

### 前端

- `apps/web/src/app/pricing/sync/page.tsx`
- `apps/web/src/components/pricing/platform-price-sync-model.ts`（+ `.test.ts`，18 例）
- `apps/web/src/components/pricing/platform-price-sync-workspace.tsx`
- `apps/web/src/app/api/catalog/platform-price-sync-allowlist.test.ts`（5 条）
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**新增一条 `price-sync` 元素
  （插在「5.4.1.1 调价单」元素之后、数组末尾）。

### 契约与台账

- `packages/api-contracts/src/contracts/catalog.ts` + `packages/api-contracts/src/catalog.openapi.json`
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成
  （+15 type / +8 path）。**trade 的两个生成物已回退** —— 那是别的车道在制品。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 5.4.2.1 那一行。

## 后续待办

1. **平台侧真正的执行器**（channel 的 vendor 适配层对 `UPDATE_PRICE` 的实现）**不在本模块职责内**。
   本模块交付的是蓝图那句「回写任务（经 02 队列）」与「实际价与期望价的差异记录」。
   02 队列里的任务由 channel 侧的适配器推进 —— 在那之前，记录停在 PENDING（**不假装成功**）。
2. **平台回执的调用方**：本模块提供 `POST /catalog/price-sync/records/{uuid}/receipt`
   作为平台适配器 / 核验通道的回执入口（与 2.5.2.1 / 4.1 刊登流程里「平台回执」端点同构）。
   接上真实平台适配器后，由它来调。
3. **未接线**：跨店铺比价（5.4 定价监控卷）、5.5.2.1 异常告警里的「站点价差倒挂」告警
   （本模块的联动规则判定结果是它的输入，但告警本身归 5.5.2.1）。
4. **对比价取不到时的处置**：目前是「规则不参与判定 + 写明原因」。
   若产品要求「取不到对比价就不许回写」，那是策略变更，需要在规则上加一个开关。
