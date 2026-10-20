# 4.5.2.2 上新与变更监控（D5 阶段，L3 锚点 4.5.2）

> 车道 `feat/4-5-2-2`，worktree `.worktrees/4.5.2.2`，基线 `integration/module-dev` 的 `03785467`。
> 兄弟模块 4.5.2.1（跟卖监控）、4.5.1.2（关键词排名）、4.5.1.1（Listing 表现）已交付，
> 本模块**照抄它们的写法**（真分页 / `capabilities` 能力声明 / 筛选项词表 /
> 比率不落表一律 SQL 现算、分母为 0 返回 null / 批次级幂等），但**不复用它们的表**。
> ⚠️ 4.5.2.1 的会话日志留了一条待办：「两者语义相近（都是「盯一个 ASIN」），
> 届时可能需要复核两者的配置是否该合并到一张表」——
> **本车道不擅自合并，自己建自己的表（零 ALTER 任何既有表）**，
> 「是否该合并」留给后续复核（理由见下表第 2 行）。

## 决策摘要

蓝图 §4.5.2.2 只有四行，成立的与否全在一句加粗上：

> **监控对象与自有商品分离**，竞品数据不进自有商品模型，只做参考指标。

所以本模块的第一件事不是写判定，而是**先把「监控对象与自有商品分离」变成结构**：

1. **监控对象表里没有 `listing_id`，也没有到 `listing` 的外键。**
   4.5.2.1 的监控对象挂在**自家 listing** 上（那是「监控我自己的商品有没有被跟卖」），
   本模块盯的是**别人家的 ASIN** —— 目标身份只有「平台 + 平台商品 ID（ASIN）原文」这一对。
   挂上 `listing_id` 有两个具体后果：竞品观测值会被下游当成「我方商品的价格」读走；
   删掉一个我方 listing 会 FK CASCADE 连带删掉竞品的历史。
2. **五张表零个自有商品列**（无 `listing_id` / `sku_id` / `internal_sku_id` / `shop_id`），
   集成测试逐表查 `information_schema` 钉住。
3. **每个响应都带 `dataSource='PLATFORM_SCRAPED'` 与 `sourceNote` / `actionsNote`** ——
   蓝图加粗句因此是**契约级**的硬要求，不是页面上的装饰。
4. **服务层没有任何抢占 / 调价 / 铺货端点**，api-proxy 白名单里**刻意不放行**这类形状 ——
   放行一个后端不存在的映射比 404 更糟：界面会以为「已经可用」。

五张表：`listing_change_watch`（监控对象 = 配置）/
`listing_change_crawl`（抓取批次）/`listing_change_snapshot`（观测快照 = 事实）/
`listing_change_event`（变更事件 = 判定结论）/`listing_change_opportunity`（市场机会 = 结论的结论）。
分成五张是因为四者的**生命周期与语义不同**：配置可暂停可删、批次整批幂等、事实只追加、
事件一旦产生就被「一次观测 × 一个维度」唯一约束钉住、机会一旦「已阅」就不可再改。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 监控对象挂哪里 | **不挂 listing**，只认「平台 + ASIN 原文」 | 蓝图加粗句的物理兑现。竞品与自有商品零引用关系 —— 挂了就有「删我方商品连带删竞品历史」和「竞品价被当成我方价」两个具体后果 |
| **与 4.5.2.1 的配置是否合并到一张表** | **不合并**，各建各的（零 ALTER），**留给后续复核** | 4.5.2.1 会话日志留的待办。两者**身份模型不同**：4.5.2.1 的监控对象是「自家 listing 的跟卖配置」（有 `listing_id`），本模块是「竞品 ASIN 的观测配置」（没有 `listing_id`）。合表要先回答「一条监控对象能不能既没有 `listing_id` 又有 `listing_id`」—— 这个问题本车道**不替后人回答**。本车道只保证：不 ALTER 对方任何表，将来无论是合表还是并表都不产生跨车道 DDL 回归 |
| 目标性质要不要区分竞品 / 自有 | `target_kind` ∈ `COMPETITOR` / `OWN_ASIN`，**两者都不写进自有商品模型** | 蓝图写的是「监控目标 ASIN/**竞品**」—— 两者都在范围内。但 `OWN_ASIN` 存的仍然是**平台侧观测值**（平台看到的价/排名），不是我方商品记录，所以同样零自有商品列、同样标「参考数据」 |
| 四类变化怎么判定 | 上下架 / 价格 / 排名 / 评论数**各自独立**判定 | 蓝图职责句列了四类，少做一类就是半成品。四类都要能被**识别出来并落成事件**，不是只存「当前值」 |
| **比较基准取什么** | **上一个「已知」值**，不是「上一次观测」 | 平台这次没报价格 ⇒ 不产生价格事件，**且基准回退到上一个报过价格的那次**。用「上一次观测」的话，「平台没报」会吃掉下一次真正的价格变化 —— 运营永远看不到那个涨价 |
| 「平台没报」怎么表达 | `null` / `UNKNOWN`，**绝不写 0** | 写 0 会被下游读成「价格 0 元」「排第一」。`null` 与 0 是两件完全不同的事 |
| 首次观测算不算变化 | **不算**，但上下架维度**留一条事件**（旧状态 `UNKNOWN` → 新状态） | 「没有基准就算不出变化」—— 价格 / 排名 / 评论数在首次观测时**不产生事件**。但模块名里的「**上新**」正是这件事：「这个目标当前是在售的」是一条必须能被看到的状态迁移 |
| 「上新」要不要直接报机会 | **默认关**（`listed_opportunity`），由监控对象逐个开 | 每个新建监控对象都报一次「上新」是噪声。断货默认开（蓝图的示例场景） |
| 变化量 / 比例落不落表 | **一律不落表，SQL 现算**（照抄 4.5.1.1 / 4.5.2.1） | 落了表就要在每次写入时去查上一次并比较，逻辑散在两处，迟早漂移；而且「上一次」是按观测时刻定的，落列天然会错 |
| 分母为 0 时怎么办 | **返回 null**，不是 0、也不是 100 | 报 0 会被读成「价格没变」，那是结论不是缺口。测试里两条断言钉住：变化比例 null、且**不判定**涨价机会 |
| 事件里存什么 | 存**当时那一对观测值的快照**（`previous_value` / `current_value`），不存变化量 | 变化量是会漂移的派生量，快照是不会漂移的事实。判定依据（`reason`）也逐条留痕 |
| 方向字段含不含好坏 | **不含**：`direction` 只说该维度朝哪变 | `RANK + INCREASED` = 名次数字变大 = **竞品表现变差**（对我方有利），但方向字段本身不替运营下这个结论 —— 好坏由市场机会判定决定。前端按维度翻译文案（`directionLabel`），不把 `direction` 直接翻成「上升 / 下降」（那会把排名读反） |
| 排名怎么存 | `integer`（名次）+ **必须带口径**（`rank_basis` ∈ BSR / CATEGORY / KEYWORD） | 名次是**位置**不是数量，没有就写 null 不写 0。「类目第 12 名」与「关键词第 120 名」不是同一个量，混在一起比是错的 |
| 抓取批次的形状 | 一次抓取 = 一个监控对象 + `crawledAt` + **N 条观测**（每条带自己的 `observedAt`） | 批次时刻与观测时刻是**两个时间轴**：一次抓取任务可以带回同一目标的**多条历史观测**（回补），合成一列会把回补写错 |
| 幂等的两级语义 | 批次级 `idempotency_key`（整批跳过）+ 观测级 `UNIQUE(watch, observed_at, source)`（覆盖并 `revision+1`） | 两个不同的问题：「平台重投同一批」与「同一次观测被重复上报」。混成一个键就分不清「重投」与「新一轮抓取」 |
| 重复投递的回执 | `replayed=true` + `appliedCount` 报**原批次**的落库条数 + `skipped` 写明为什么 | 与 4.5.1.2 / 4.5.2.1 同一口径。页面必须显示 `replayed`，否则运营会以为平台又抓了一遍 |
| 批内观测时刻重复 | **跳过并逐条写明原因** | 后一条会覆盖前一条 —— 不能让数据悄悄消失 |
| 数值越界（负价等） | **跳过并逐条写明原因**（枚举写错则整批 422） | 枚举写错是适配器的 bug，应该响亮地失败；单个数值异常不该让 500 条的回补整批死掉 |
| 修正同一次抓取时机会怎么办 | 事件**覆盖更新**，机会**不重算**（`on conflict do nothing`） | 机会记录的是「当时的判定」，改它等于改历史。已阅的机会还有 DB 触发器钉着不可改 |
| 历史对比落不落表 | **不落表**（观测快照 + 变更事件的 SQL 聚合） | 落了表就会与事实漂移（改了阈值、修正了同一次抓取，旧的比例不会跟着变） |
| 监控对象能不能换 ASIN | **不能**（给了不同 `externalItemId` ⇒ 422） | 换了会让历史事件挂到别的竞品上。要换目标就新建一条 |
| 暂停的语义 | 暂停 = **拒收**新的抓取结果（422），但**历史不删** | 暂停是「不收」，不是「收了就藏起来」。历史事实不因为配置变了就消失 |
| 端点前缀 | `/channel/listing-changes`（**独立前缀**） | 4.1.1.1 已把 `/channel/listings/{uuid}/...` 登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单 |
| 前缀为什么不撞车 | `listing-changes` 与既有**全部 17 个** `listing-*` token 首 token 均不同 | 既有正则**都是锚定的**（`/^token(...)?$`），所以只要首 token 不同即可，既不会被既有分支吃掉、也不会吃掉既有分支。既有 token：`listing-collects` / `listing-bulk-collects` / `listing-keyword-rankings` / `listing-hijackers` / `listing-claims` / `listing-compliance-precheck` / `listing-performance` / `listing-lifecycles` / `listing-mappings` / `listing-migrations` / `listing-publish` / `listing-publish-tasks` / `listing-status-events` / `listing-content-writeback` / `listing-details` / `listing-editor` / `listing-templates` |
| api-proxy 插入锚点 | 既有 `listing-keyword-rankings`（4.5.1.2）那一条**之后** | 本批另外三条车道的锚点（`listing-bulk-collects` @1749 / `order-intercept` @2789 / `fulfillment/consolidation` @2946）与本条（@1893）两两相隔 ≥ 74 行，默认 3 行上下文窗口不会互相覆盖 |
| 负向断言的样本 | **全部取自己前缀下**的形状 | 兄弟车道的前缀在同批并入后就是**已登记的合法前缀**，拿它断言 404 必然假红（batch-32 的 4.5.2.1 踩过）。反向更危险：若那条白名单被取错一侧而静默删掉，「已交付的路由被误删」这个真事故会被负向断言**盖住** |
| 权限码 | 复用 `channel.operation.*` | identity 零改动，与 4.1.1.1 / 4.3.2.2 / 4.4.2.x / 4.5.x 同族 |

## 未交付的上游（如实标注，不假装有）

| 段 | 状态 | 本模块的处理方式 |
| --- | --- | --- |
| **02 的抓取调度器** | 归 02 | `real_crawl_scheduler.available=false` + `ownerModule=02 渠道与店铺`。本模块只提供**入库端点**（通道 `WEBHOOK` / `POLL` / `API` 都记下来）；监控对象上的 `crawl_interval_minutes` 是给 02 抓取器读的**配置**，但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决，不自建轮询 |
| **13 分析** | 整卷未交付 | `analytics_consumption.available=false` + `ownerModule=13 利润与分析`。本模块产出的是**事实与事件**；分析口径（竞品份额、价格带分布、机会排序）由 13 侧交付后消费。在这里**伪造**一个「竞品份额」数字比不提供更危险 |
| **1 工作台的通知** | 整卷未交付 | `workbench_opportunity_delivery.available=false` + `ownerModule=01 工作台与门户`。本模块产出的是**机会记录**，**不主动发通知** —— 通知投递由 1 侧交付后由它来读；在���里伪造一个「已通知」字段比不通知更危险 |

## 文件清单

**后端（`backend/services/channel/`）**

- `src/main/resources/db/migration/V211__listing_changes_monitor.sql` —— 五张新表 + 索引 + 5 个触发器 + RLS（ENABLE **与** FORCE）+ GRANT；**零 ALTER 任何既有表**、**零对 `listing` / 商品主数据的引用**
- `src/main/java/com/starcon/channel/ListingChangeModels.java` —— 传输模型（18 个具名 record）
- `src/main/java/com/starcon/channel/ListingChangeService.java` —— jOOQ 显式 SQL + 四维变化识别 + 机会判定 + 历史对比聚合
- `src/main/java/com/starcon/channel/ListingChangeController.java` —— 12 组端点
- `src/test/java/com/starcon/channel/ListingChangeIntegrationTest.java` —— 26 例
- `src/test/java/com/starcon/channel/ListingChangeTenancyIntegrationTest.java` —— 4 例（**多租户三断言**）

**前端（`apps/web/`）**

- `src/components/listing-change/listing-change-model.ts` —— 类型 + 取数 + 纯函数
- `src/components/listing-change/listing-change-model.test.ts` —— 24 例
- `src/components/listing-change/listing-change-workspace.tsx` —— 真实工作区（参考数据标注 / 未接入能力 / 监控对象 / 变更事件流 / 市场机会 / 历史对比 + 三态）
- `src/app/listings/changes/page.tsx` —— 页面路由（**新建**，不登记 task-pages）
- `src/app/api/channel/listing-changes-allowlist.test.ts` —— 4 例（放行 + 拒绝 + 不误伤兄弟车道）
- `src/lib/api-proxy.ts` —— **只新增一条** `listing-changes/` 元素（插在 `listing-keyword-rankings` 之后）

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json` / `src/contracts/channel.ts` —— **codegen 重生成**（未手改任何生成物；其余五个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md` —— 只改本模块那一行的「状态」「证据」两格

## 验证

```
cd backend && ./mvnw -q compile                                  # 退出 0
cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='ListingChange*' -Dsurefire.failIfNoSpecifiedTests=false \
    -DfailIfNoSpecifiedTests=false                              # 30 例 0 失败 0 错误 BUILD SUCCESS
pnpm typecheck                                                  # 3/3 通过
pnpm lint                                                       # 通过（本车道零告警）
pnpm vitest run apps/web/src/components/listing-change \
             apps/web/src/app/api/channel/listing-changes-allowlist.test.ts
                                                               # 2 文件 32 例全绿
pnpm vitest run apps/web/src/app/api                            # 95 文件 571 例全绿（含全量 allowlist 回归）
```

## 后续待办（不属本车道）

1. **🔴 4.5.2.1 的「配置是否该合并到一张表」** —— 本车道**明确没有合并**（各建各的、零 ALTER），
   把决定权留给后续复核。需要复核的**具体问题**是：两者身份模型不同
   （4.5.2.1 的监控对象有 `listing_id`，本模块的没有），
   合表前要先回答「一条监控对象能不能既没有 `listing_id` 又有 `listing_id`」。
2. **4.5.2.1 的页面注释**（`apps/web/src/app/listings/hijackers/page.tsx`）里写着
   「4.5.2.2 上新与变更监控 —— **尚未交付**」，本模块交付后这句已过时。
   **本车道没改它**（「只碰自己的文件」），请后续批次顺手更新。
3. **02 的抓取调度器交付后**，本模块的 `source` / `ingest_channel` 会真正出现 `SCRAPE` / `POLL` 的数据，
   `crawl_interval_minutes` 也会开始被真实调度器读取。
4. **13 分析交付后**，由它来消费本模块的变更事件流与历史对比（跨服务走授权 API，不跨库读表）。
5. **1 工作台交付后**，由它来读本模块的 `listing_change_opportunity`（跨服务走授权 API）。
6. **排名口径若要扩到关键词维度**，`rank_basis_key` 已经留好（关键词原文 / 类目路径），
   但 4.5.1.2 关键词排名是**另一套**数据（自家商品的关键词排名）——
   本模块的竞品关键词排名与之**不共享表**，也不互相引用。
