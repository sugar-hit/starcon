# 4.2.1.2 批量编辑（channel 服务）

- **日期**：2026-10-19
- **车道**：`feat/4-2-1-2`（worktree `.worktrees/4.2.1.2`，基线集成头 `integration/module-dev`）
- **蓝图**：`docs/product/capability-blueprint/04-listing-and-content.md` §4.2.1.2

## 交付了什么

`V177__listing_bulk_edit.sql` + `ListingBulkEdit{Controller,Models,Service}.java` + `SafeRegex.java` +
`ListingBulkEditIntegrationTest.java`（17 例）。

职责句六种操作全部真实现：追加标题 / 替换标题 / 正则替换标题 / 批量换图 / 统一加卖点 / 批量改类目，
加按平台差异化模板与逐条「改前 vs 改后」预览。输出 = 待发布草稿 + 修改记录。

## 关键决策与 trade-off

### 1. 核心不变量做了三层，不是靠"我们没写那条 SQL"

蓝图加粗句「批量编辑只改草稿不直接改线上」是本模块唯一不可让步的一条。落地拆成三层：

1. **API 形状**：全模块唯一持久化写路径 `confirm()` 产出草稿，没有任何改线上的端点。
   前端连调用入口都拿不到。
2. **事务标记**：confirm 事务打开 `app.draft_only`（`set_config(...,true)` 事务局部），
   V177 的 `guard_listing_live_content` 触发器在此期间拒绝对线上内容列的任何 UPDATE。
   **这层的价值在未来**：有人后来在批量路径里手滑加了 `update listing set title=…`，
   会在测试期炸，而不是等到全店标题被改完。
3. **物理分表**：草稿态在 `listing_content_draft`，与线上 `listing` 两张表。

**取舍**：第 2 层给 `listing` 加了一条 BEFORE UPDATE 触发器，是本迁移里唯一的 ALTER 既有表的地方
（虽然只加触发器、不动任何列）。V171 是「零 ALTER 既有表」，这里破了这个洁癖。
判断依据是：不加触发器的话，"批量编辑"在代码审查层面就是一个带筛选器的全表 UPDATE，
审查能挡住一次，挡不住第二次。

### 2. 诚实边界：卖点与图片的线上基线没有编

V171 的 `listing` **没有卖点列也没有图片列** —— 那是 4.2.1.1 刊登编辑器的内容域，
而 4.2.1.1 在集成头尚未交付。

- 标题与类目的 before 真的取自线上 listing。
- 卖点/图片的 before 取自**已有草稿**，没有就是空集，capabilities 显式声明
  `onlineBaselineAvailable=false` + ownerModule=4.2.1.1 + 影响。
- **统一加卖点在没有可比基线时显式跳过**（SKIPPED_NO_BASELINE）。理由不是保守，
  是真的会造成事故：照做会产出一份只含新卖点的草稿，发布时 touched_fields 含 BULLETS，
  listing 原有卖点被清空 —— 那是一次用户根本没要求的线上改动。
- 换图照做，因为它是整组替换语义，没有基线也能确定结果。

### 3. 预览与执行共用同一批行

`preview()` 把逐条 before/after 落库成 `PREVIEWED`；`confirm()` 不重新计算，
只把**同一批 `listing_bulk_change` 行**推进并写进草稿。
所以"预览一套、执行另一套"在结构上不可能，测试逐条比对两者相等。

**取舍**：预览因此有了持久化副作用（一次预览 = 一条修改记录）。
判断依据是"当时系统告诉我们会怎么改"本身就是追责与复盘的凭据，
静默预览反而会让"我明明看到会改成这样"无法取证。

### 4. `touched_fields`：没拟改的字段不进草稿

只改标题的批量**不会**顺手把类目快照进草稿。否则发布时会把"当时线上是 A、编辑时是 B"的 B
一并覆盖回去 —— 又是一次没人要求的线上改动。
CHECK 把 `touched_fields` 与哪些列非 NULL 钉成一一对应，两处不可能各说各话。

## 过程中踩到并修掉的真 bug（都是先写错、后被测试打出来的）

1. **PG 数组不能直接绑 `UUID[]`** —— 驱动按元素类型送成 `uuid`，
   `?::uuid[]` 报 `cannot cast type uuid to uuid[]`。必须先拼成 PG 数组字面量 `{a,b}` 再绑文本。
   `text[]`（卖点 / touched_fields）同坑。
2. **`SafeRegex` 漏判出现在模式末尾的量词** —— 判据原本是
   `quantifierEnd(p, i) > i`，而单字符量词 `*` `+` `?` 在没有惰性后缀时 `quantifierEnd == i`，
   于是最经典的 `(a+)+` 恰好因为结尾那个 `+` 被判成"不是量词"而**整个模式放行**。
   改成独立的 `isQuantifierAt()`。这条如果没写对应的测试，防线就是纸糊的。
3. **卖点基线判据写错** —— 原判据是"有没有草稿"，正确判据是"有没有卖点基线"。
   一份只换过图的草稿同样没有卖点基线，照做一样会清空原有卖点。

另外发现一处**既有**的隐患（不在我这条车道，未改）：
`ListingIntegrationTest` 的 `AMAZON` 常量是 `1111...-111`，而 V1 把这个 id 播种成了 **ETSY**，
它的 `seedGlobalCatalog` 因 `on conflict (id) do nothing` 静默不生效。
该测试不断言平台 code，所以一直没暴露。我这条车道断言平台切分，一断言就撞上了，
改成直接用 V1 已播种的真实平台 id（`...-101`=AMAZON / `...-104`=TIKTOK_SHOP）。

## 验证

- **L0**：`./mvnw -q compile` → BUILD SUCCESS；`pnpm typecheck` → 3/3 successful。
- **L1**：`./mvnw -pl services/channel -am test -Dtest='ListingBulkEdit*'` →
  `Tests run: 17, Failures: 0, Errors: 0` / BUILD SUCCESS（Testcontainers 真实 PG16 实跑 Flyway V177）。
- **契约**：六服务 `OpenApiContractTest` 导 spec → `catalog-contracts.py --spec-dir` 重生成，
  2834 DTOs。⚠️ 重生成顺带补上了仓库里**既有的**契约漂移（channel 的 ad-type-diffs /
  carriers / freight / handover-batches / label-templates 等端点、trade 的 refund-applications
  此前都不在快照里）—— 那是之前失联车道的欠账，不是本模块的改动，
  `packages/api-contracts/src/**` 绑了 `merge=contract-union`，并入时由驱动器求并。
- **api-proxy**：新增**独立** services[] 元素（不往既有 channel 超长 alternation 里并分支），
  锚点固定在 4.1.1.1 `listings` 条目之后、`trade/` 之前。
  正则用 node 单独验过：11 个真实形状全部放行、8 个刻意不放行的形状全部拒
  （含裸路径 `listing-bulk-edit` 与任何 `…/publish`）。

## 待办 / 遗留

- 4.2.1.1 刊登编辑器交付后，卖点与图片的线上基线才有来源；
  届时 `onlineBaselineAvailable` 应从 false 转 true，BULLET_APPEND 的 SKIPPED_NO_BASELINE 分支可退役。
- 4.3.1.2 批量发布消费本模块草稿时，**必须**遵守 `touched_fields`：
  不在列表里的字段保持线上一致，不得用草稿里的旧快照覆盖。
- 批量目标集合目前无条数上限。真实场景若要"一键改全店"，
  建议在入口加一条按店铺/筛选条件的批量选取接口，而不是让前端传几万个 id。
