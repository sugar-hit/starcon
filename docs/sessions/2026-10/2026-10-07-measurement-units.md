# 3.3.3.1 计量单位

## 决策摘要

交付卷 03 商品主数据系统 3.3.3.1「计量单位」（D1–D2，路由 `/products/units`），
分支 `feat/3-3-3-1-measurement-units`，基线 `feat/3-3-2-1-brand-management`（tip `b4c4fff`）。

地基先行：`V8__unit_conversion.sql`（3.1.2.4）已经建了 `unit_of_measure` 单位字典
（编码 / 名称 / 量纲 / 数量精度 / 采购·销售·存储可用性 / 启停）与 SKU 侧的
`sku_unit_conversion` + 单据冻结快照。本模块补的是蓝图那句
「**并定义单位组内的换算关系**」「输出：单位记录 + 换算查询能力」——
与 SKU 无关的组级换算（1 千克 = 1000 克 这件事本身成立），该路径此前完全不存在。

五条核心决策：

1. **新建三张组表，不碰 `unit_of_measure` 的写入。**
   单位字典的写入所有者是 `UnitConversionService`（V8 建立），字典视角页面消费它的既有端点，
   不新建第二个写入口，也不复制一份平行的单位字典表。
   `/catalog/unit-groups` 与 3.1.2.4 的 `/catalog/unit-conversion` **并存不合并**：
   前者是字典视角，后者是 SKU 视角（服务 06/07/08 的单据生成与冻结快照）。
   合并会让「谁是这个字段的写入所有者」在代码里说不清。

2. **换算拒绝返回 200 + 原因码，而不是 HTTP 错误。**
   「件换不成千克」是**业务判定不是调用失败**，调用方要拿到结构化结果去提示用户，
   接一个异常就只剩下 try/catch 里的一句 message。七条原因码：
   `SELF_CONVERSION` / `UNIT_UNREGISTERED` / `UNIT_DISABLED` / `GROUP_DISABLED` /
   `DIMENSION_MISMATCH` / `NOT_SAME_GROUP` / `NO_EFFECTIVE_VERSION`。
   判定顺序从最具体排到最笼统，且**量纲守卫先于任何组内判断**——
   用户问的正是「为什么换不了」，先答「量纲不匹配」比先说「不在同一个组」有用。

3. **量纲守卫双层，且 DB 层的三个触发器是有意冗余的。**
   V8 的 `guard_unit_conversion_dimension` 只守 SKU 换算表。组级路径另配
   `guard_unit_group_dimension` / `guard_unit_group_member_dimension` /
   `guard_unit_group_conversion_dimension`。
   第三条在有外键与第二条的前提下是冗余的，但冗余在这里是**有意的**：
   未来任何一次绕过应用层的写入（脚本、运维 SQL、别的服务）都必须撞上同一个量纲检查。
   应用层同时判一次并给出人话（「单位「件」属于数量，请改选重量单位的成员」），
   因为触发器抛的 23514 对运营没有可执行信息。

4. **组基础单位不占换算行，但可以在一次保存里迁移。**
   比例恒为 1 的基准单位另存一行，等于让同一个事实有两个来源（V8 头部反对的形状），
   所以 `refuse_unit_group_base_removal` 触发器禁止它在还是基准时被移出组。
   实现上踩过一个坑：最初 `save()` 用「删光成员再全插」做整体替换，
   改基准单位时（克 → 千克）删光那一瞬旧基准仍被认定为基准，整次保存被自己的守卫拒掉——
   结果是「改基准单位」这个动作永远做不了。改成**增量删/插**（先改 `base_unit_code`，
   再删旧基准成员）后，基准单位迁移成为一次正常保存。
   这条对应 `groupBaseUnitCanBeMigratedToAnotherMemberInOneSave` 用例。

5. **移出成员会级联删掉它的组内换算版本——刻意的，不是疏漏。**
   组内换算是**字典配置**：这个单位已经不属于这个组，「1 千克 = 1000 克」也就没有归属；
   留着它会让换算矩阵出现一条指向非成员单位的比例。与 V16「类目没了，品牌属于该类目的关联
   也就没有意义」同一口径。真正不可删的是 3.1.2.4 的 `sku_unit_conversion_snapshot`——
   那是已发生单据的冻结事实，DB 层禁止 UPDATE/DELETE。
   两者的不可变性口径不同，不要混。
   为了让运营自己控制「删到哪一版」，另给 `DELETE /{id}/conversions/{conversionId}`：
   已闭合的历史版本可删，**当前仍开放生效的那一版不可删**（删了等于静默让该单位不可换算）。

## 关键 trade-off

- **组上的 `dimension` 列是刻意冗余，不是重复存储事实。**
  派生（只从成员算量纲）会让「按量纲筛组」必须 join 成员表再 distinct，
  而筛组是这个页面最常见的操作；冗余一列换来一条 `(tenant_id,dimension,code)` 索引。
  冗余必须被强制，否则它会变成第二个真相来源——触发器与应用层都要求
  基准单位与每个成员的量纲都等于组量纲。

- **与 V8 反着来：组成员与组内换算都加了指向 `unit_of_measure` 的真实外键。**
  V8 刻意不给 `sku_unit_conversion.unit_code` 加外键（V6 之前写入的历史行可能不在字典里，
  加了会让既有事实写不进去）。这里不同：这三张表是本模块新写的、没有历史包袱，
  所以可以要这个强约束。`unit_group_conversion` 的复合外键
  `(tenant_id,group_id,unit_code) → unit_group_member` 把「组内换算」这个名字落到 DB 层：
  少了它，一条指向组外单位的换算行会让矩阵算出一个看似合理、实际无意义的比例。

- **`deleteUnit` 补了单位组引用检查。**
  删一个已被组成员引用的单位会经外键级联删掉该单位在所有组里的成员行与换算版本——
  静默的数据丢失。与该方法已有的两条检查（换算版本 / 快照引用）同一口径：被引用就拒绝，
  并提示改为停用。这是唯一一处修改已交付模块的代码，是 V17 引入引用关系后必须补的。

- **换算查询的比例链是 `quantity × fromPerBase ÷ toPerBase`，先乘后除。**
  先求比值再乘会在中间多丢一次 6 位小数精度。先乘后除让损失最少。
  用 HALF_UP 而不是截断：截断会让账面比实盘少一点点，而差额不会留在任何地方。

- **判定基准日用 UTC 当天，不用服务器本地当天。**
  「这一版换算今天生效吗」按本地时区算，同一份数据在 UTC 机器与 Asia/Shanghai 机器上
  会给出不同结论。详情端点额外接受 `?asOf=`：补录一条 2024 年的比例时，
  「哪一版当时生效」与「今天哪一版生效」必须能分开看。

- **前端只把原因码翻成话，不重算换算判定。**
  复制一份算法到浏览器里的症状是「页面说能换算、接口说不能」，
  而换算比例错一次就是一批按错比例记完的库存账。

## 文件清单

后端（新增）：

- `backend/services/catalog/src/main/resources/db/migration/V17__measurement_units.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitGroupRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitGroupService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/UnitGroupController.java`

后端（修改）：

- `CatalogModels.java`：追加 3.3.3.1 段（14 个 record）
- `UnitConversionService.java`：`deleteUnit` 补单位组引用检查

测试（新增）：

- `backend/services/catalog/src/test/java/com/starcon/catalog/UnitGroupRulesTest.java`（11 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/UnitGroupIntegrationTest.java`（19 例）

前端（新增）：

- `apps/web/src/app/products/units/page.tsx`
- `apps/web/src/components/catalog/unit-group-workspace.tsx`
- `apps/web/src/components/catalog/unit-group-model.ts`
- `apps/web/src/components/catalog/unit-group-model.test.ts`（27 例）

前端（修改）：

- `apps/web/src/lib/api-proxy.ts`：放行 `unit-groups/**`
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`：放行用例 1 例

生成物（codegen，禁手改）：

- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`

台账：

- `docs/product/capability-blueprint/module-progress.md` 的 3.3.3.1 行 + sync 自动重算的汇总统计

## 对「真正的欠账清单」的调整

任务书给的四条欠账全部成立，按原样做完。**补了一条任务书没列的**：

- **组内换算版本的删除端点。** 实现过程中发现：移出成员被「该单位还有换算版本」拒绝后，
  错误消息让用户「先删除这些换算版本」——而当时根本没有这个端点，
  于是「把一个单位移出组」这个动作永远做不到。补 `DELETE /{id}/conversions/{conversionId}`
  之后闭合成环。

其余未发现 V8 已覆盖的情况：`unit_of_measure` 的字段语义、量纲守卫、
`sku_unit_conversion` 的版本化与生效区间、单据冻结快照，都由 V8 / V6 提供，本模块原样复用。

## 后续待办

- **组级换算尚未被下游消费。** 06 采购 / 07 库存 / 08 拣货的单据生成目前仍走 3.1.2.4 的
  `sku_unit_conversion` 与 `sku_unit_conversion_snapshot`。V17 迁移结尾写明了归属：
  组只回答「这两个单位之间换算是多少」，单据冻结仍引用 SKU 侧换算行，不改写已冻结的快照。
- **`sku.base_unit` / `sku_unit_conversion` 不改挂组级换算**，那是 3.1.2.4 已交付的写入所有权。
- **`unit_of_measure` 的量纲仍不可改**（沿用 V8 的决定）：改量纲会让既有换算在守卫触发器
  下一次写入时突然报「量纲不匹配」。要改就新建一个单位。
