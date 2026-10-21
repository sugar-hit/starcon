# 2026-10-21 6.1.2.2 同款货源推荐：置信度、弱匹配人工确认与一键建单

## 决策摘要

实现蓝图卷 06 的 6.1.2.2「同款货源推荐」（L3 锚点 6.1.2，阶段 D6，trade 服务，
前端 `/supply/alternatives`，后端命名空间 `/trade/source-alternatives`）。

蓝图原话：「当某 SKU 缺货或质量不达标时，推荐可替代的同款/相似货源：同款不同供应商、
历史采购过该货的供应商、价格对比。输入：SKU 编码与图片；历史采购记录；货源库匹配结果。
输出：候选货源列表（供应商、价格、交期、历史质量评分）+ 一键生成采购申请。
**同款判定要给置信度**，图片相似不等于同款，误判会导致采购到错误商品。」

三条落点：

1. **同款判定只认三种证据，强度不同、置信度不同、每条都要写明依据**：
   - `SKU_LINK` **确证**（6.1.2.1 的 `supplier_source_sku_link` 里**人工确认**过
     「这个货源就是这个 SKU」）= 1.0000，**不再要求人工确认**；
   - `PURCHASE_HISTORY` **强线索**（该供应商**历史采购过这个 SKU**，取自 6.3 的
     `purchase_order` / `purchase_order_line`，**只算真正下过、没被驳回**的单）
     = 0.5000~0.7500（按历史笔数递增）；
   - `TITLE_SPEC_MATCH` **弱线索**（标题与规格**完全一致** = 0.4000；标题**包含**且
     规格一致 = 0.3000）。
   **三种证据都不成立的货源根本不进候选清单。**
2. **本模块刻意不做图片相似度自动判定**：本仓没有视觉相似度服务，凭空实现一个
   「图片哈希像所以是同款」就是把采购单指向错误商品。页面上只把目标 SKU 的**参考图**
   与候选图**摆在一起给人看**，`capabilities` 里明写「未交付」。
3. **弱匹配必须人工确认才能生成采购申请**：DB 层 CHECK
   `source_alternative_candidate_confirmation` 钉死（`SKU_LINK` ⇔ 不需人工确认，
   其余 ⇔ 一定需要），服务层再挡一道。

## 关键 trade-off

1. **置信度与排序分成两个数，不合并成一个「推荐度」。**
   置信度回答「是不是同款」，排序分回答「先看哪一条」。合并之后，运营会把「便宜」
   误读成「同一个货」—— 而那正是误判采购的起点。排序分由**租户级权重**
   （价格 / 评级 / 历史采购 / 历史交期，四项和**精确等于 1**，写在
   `source_alternative_policy` 里、可查可改）合成，**不是代码里写死的常数**。
   便宜的错货一样要人工确认。
2. **候选落成快照，而不是每次实时重算。**
   证据（价格区间、交期、评分）都是**那一刻**的事实。三天后回看「当时推荐了什么、
   凭什么」，必须能看到当时的值；实时重算只会给他一个今天的答案。
   `source_alternative_candidate` 与 `source_alternative_decision` 都有**只追加触发器**，
   直连数据库改/删一律拒 —— 「历史上没人确认过」不能被抹掉。
3. **写 `purchase_request` 走 6.2.2.1 的服务方法，不自己插表。**
   `purchase_request` / `purchase_request_line` 的**写入所有者是 6.2.2.1**
   （AGENTS.md：一张事实表只有一个写入所有者）。因此给 `PurchaseRequestService`
   加了一个窄方法 `createFromSuggestion`（`source_type='SUGGESTION'` + 行由调用方直接给），
   并把 `create()` 的落库段抽成私有的 `persistRequest(...)`，让**补货建议**与
   **同款货源推荐**共用同一条落库路径 —— 落库口径必须只有一份。
   ⚠️ 这是本车道唯一改到 6.2.2.1 文件的地方（`PurchaseRequestService` +
   `PurchaseRequestModels`，各加一段），本批另三条车道都在 channel 服务，不撞。
4. **申请行不填预估单价。**
   蓝图加粗句「采购下单时必须以实际下单金额为准，货源快照仅作参考」。所以生成的
   申请行 `unit_price` 留 NULL，参考价区间与采集时刻写进备注；`estimated_amount`
   随之为空 —— 金额不完整时**不拿它去匹配审批阈值**（这正是 `purchase_request` 的设计）。
   与 6.2.2.1 从补货建议转单时同样留空的口径一致。
5. **价格维度只在同币种内归一，不跨币种比价。**
   汇率属 12 财务（未交付）。把 ¥ 与 $ 放一起排序是在编一个假的「最低价」。
   没有可比对象时给**中性 0.5**（不是 0 分）；历史交期没有就是**没有**（不是 0 天）。
6. **评级用最终分排序，但客观分与最终分并排展示、人工调整单独标记。**
   6.1.1.3 的加粗句是「评级必须由客观数据算出，人工加权分需单独标记」——
   折叠成一个「总分」之后，三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。
7. **取最新价按 `snapshot_seq` 取最大那一版**（lateral 子查询），而不是只信
   `current_snapshot_id` 指针 —— 指针与最新版本在正常路径上等价，但按版本号取
   是「最新」这件事的**唯一**定义。

## 文件清单

**新增（后端）**

- `backend/services/trade/src/main/resources/db/migration/V272__source_alternatives.sql`
  —— 四张新表 + RLS + 只追加触发器 + 索引，**零 ALTER 任何既有表**。
- `backend/services/trade/src/main/java/com/starcon/trade/SourceAlternativeModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/SourceAlternativeService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/SourceAlternativeController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/SourceAlternativeFixture.java`
- `backend/services/trade/src/test/java/com/starcon/trade/SourceAlternativeIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/SourceAlternativeTenancyIntegrationTest.java`

**改动（后端，6.2.2.1 的写入口）**

- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestService.java`
  —— 新增 `createFromSuggestion(...)`；`create()` 的落库段抽成 `persistRequest(...)`。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestModels.java`
  —— 新增 `PurchaseRequestSuggestionInput` / `PurchaseRequestSuggestionLineInput`。

**新增（前端）**

- `apps/web/src/components/supply/source-alternative-model.ts`
- `apps/web/src/components/supply/source-alternative-model.test.ts`
- `apps/web/src/components/supply/source-alternative.tsx`
- `apps/web/src/app/supply/alternatives/page.tsx`
- `apps/web/src/app/api/trade/source-alternative-allowlist.test.ts`

**改动（共享文件）**

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（锚点在
  6.2.2.2 需求合并与批量之前），既有条目一字未动。
- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`
  —— codegen 重新生成（+10 路径、+20 DTO，纯新增）。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.1.2.2 那一行的
  「状态」「证据」两格。

## 验证

- L0：`cd backend && ./mvnw -q compile` → 绿；`pnpm typecheck` → 绿。
- L1：`./mvnw -pl services/trade -am test -Dtest='SourceAlternative*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` → **21 例全绿**
  （18 集成 + 3 多租户三断言，真实 PostgreSQL 16 + Testcontainers，连接池压到 1）。
- 前端：`pnpm vitest run apps/web/src/app/api/trade` → **379 例全绿**（68 文件，
  含 api-proxy 全量 allowlist 回归）；`pnpm vitest run apps/web/src/components/supply`
  → **136 例全绿**。

## 后续待办

- **图片相似度自动判定**：等视觉服务就绪后再做；届时 `capabilities` 里那条
  `available=false` 要改成 `true`，并新增一种判定类型与置信度口径。
  **在那之前不要用「图片像」当同款证据。**
- **12.1.1 汇率快照**：就绪后价格对比可跨币种，排序里的价格维度也要改成全局归一。
- **6.3.1.2 采购单**：就绪后本模块的申请草稿可一键转单（当前只到草稿为止）。
- trade 的页面权限码（`trade.alternatives.*`）在 identity 侧**没有**注册
  page_resource —— 这是 trade 全服务的既有缺口，本车道沿用兄弟模块做法**不改 identity**，
  以免与其他车道撞迁移号。
