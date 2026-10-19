# 4.5.1.2 关键词排名（D5 阶段，L3 锚点 4.5.1）

> 车道 `feat/4-5-1-2`，worktree `.worktrees/4.5.1.2`，基线 `integration/module-dev` 的 `9f0fd1d7`。
> 迁移 `V205__listing_keyword_ranking.sql`（channel 服务）。

## 决策摘要

蓝图 §4.5.1.2 只有四行，成立的与否全在一句加粗上：

> **「排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口。」**

所以本模块的第一件事不是写排名表，而是**先把「名次是某个时刻的快照」变成结构**：

1. **抓取时间是一列，不是猜的**：`listing_keyword_rank_snapshot.crawled_at` NOT NULL。
   排名是「某一次抓取看到的名次」，不是「现在的名次」—— 这两件事差着一个抓取周期。
   谁把排名当成实时值用，结论就是错的。
2. **不准确窗口也是一列**：`accuracy_window_minutes` NOT NULL。平台的名次本身有波动
   （个性化、库存、地域、A/B 实验），抓回来的那一刻就已经不是最终值了。
   窗口多大**由抓取方给**（平台限制 + 抓取频率），服务端**不替它编**。
3. **两列写在数据行里，不是只写页面文案** —— 只写 UI 的话，导出的报表里那列就没了。
4. **接口契约里必须显式给出**「数据截至何时 / 可能存在多大延迟窗口」：
   `crawledAt` / `accuracyWindowMinutes` / `accuracyNote` 三个字段在**每个**返回
   排名数据的响应里都有，不是只在一个详情页里。
5. **排名没有就写 null，不写 0、不写一个很大的数**：平台没报自然排名 =
   「这次没进监测范围」，这与「排名第 0」是两件完全不同的事。

**排名是「位置」不是数量**：自然排名 / 广告排名用 `integer` 存（无浮点），
数字越小越好。「较上次的变化」= 本次名次 − 上次名次。

**「记录历史变化，低于阈值时预警」**落成三件事：排名历史表（事实）、
变化预警（**记录**，不是通知）、关键词维度报表（**真 SQL 聚合**，不建表）。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 抓取时间 / 不准确窗口**落表**还是**只写页面** | **两列都落表，且每个响应都带** | 蓝图加粗句是契约级要求。只写页面 ⇒ 导出的报表里看不出那个名次是什么时候的；只写表 ⇒ 前端无从标注 |
| 不准确窗口由谁给 | **抓取方必填，服务端不编** | 服务端编的窗口是假的。平台限流与抓取频率只有抓取方知道；服务端替它编一个 = 造了一个看起来很专业的错数字 |
| 名次没报到时写什么 | **null**（不是 0、不是很大的数） | 写 0 会被下游读成「排第一」或「完全没排名」—— 那两件事都不是事实。与 4.5.1.1 的「比率分母为 0 返回 null」同族 |
| 「较上次的变化」**落表**还是**现算** | **现算**（lateral 取最近两次抓取） | 「上一次」是**按抓取时间**定的，不是按行号定的。落了列，改了抓取顺序就会算错。照抄 4.5.1.1「比率不落表」的原则 |
| 变化量在**没有上一次**时写什么 | **null**（不是 0） | 「没有可比的上次」与「没变化」是两件事。写 0 会被读成「名次没动过」 |
| 阈值**可空**还是给默认值 | **三个阈值全部可空 = 该维度不预警** | 写死默认阈值会制造大量运营不想要的预警。而**「没配阈值」与「阈值是 0」是两件事**：0 意味着「掉出第 0 名就预警」（等于永不触发），那是配置不是「没配」 |
| 「低于阈值」的判定方向 | **名次数字 > 阈值** | 名次是位置，数字越大越差。阈值 10 = 「守住前 10」，名次 25 ⇒ 掉出去了 |
| 「变化预警」怎么定义 | **相对上一次抓取退步超过退步阈值** | 蓝图把「变化预警」与「低于阈值」并列写，说明变化本身也要能预警。退步 = 名次数字**变大**（变差） |
| 预警**落表**还是现算 | **落表**（`listing_keyword_rank_alert`） | 预警是**事件**（某个时刻判定了一次），不是当前状态。历史必须留痕 —— 否则「当时判过没有」查无对证 |
| 预警**发通知**还是只记录 | **只记录，不发通知** | 下游 1 工作台预警与 14 广告都未交付。本模块**不假装**具备推送能力：预警落库 + 允许人工确认留痕（`acknowledged_at` / `acknowledged_by`），capabilities 里 `available=false` + ownerModule + impact |
| 关键词集合**物理删除**还是**软删除** | **软删除**（`active` 标志） | 排名历史与预警记录必须留着 —— 关键词停了不等于当初抓到的名次不算数。物理删除会级联抹掉历史，「当时发生了什么」就查无对证了 |
| 平台 / 站点**独立存**还是**从商品推导** | **推导**（商品 → 店铺 → 平台 / 站点） | 商品是唯一事实来源。独立存一份就可能与商品矛盾，而矛盾的平台归属会让排名报表按错误的维度分组 |
| 一张表还是四张 | **四张**（关键词集合 / 抓取任务 / 排名历史 / 预警） | 蓝图输入写的是「**排名抓取任务**」。一次抓取是一个**有范围、有通道、有成败**的整体（覆盖了哪个商品的哪些关键词、哪些没顾上），挂在快照行上会出现「同一批数据被拆成 N 行、每行各说各话」。分开之后「还没抓过」与「抓过但这个关键词没数据」是**两回事**（前端空态必须分开渲染） |
| 抓取批次挂**商品**还是**关键词** | **挂商品**（一个批次覆盖该商品的一批关键词） | 与 4.5.1.1 的「一次同步 = 一个有范围的整体」同构。且批次级幂等键因此有稳定的粒度 |
| 重复投递同一批怎么办 | **整批不落库**（`idempotency_key` 租户内唯一，回执 `replayed=true`） | 与「同刻覆盖」是两件不同的事：**重投**用同一个键（跳过），**修正**用新键（覆盖）。混成一个机制就分不清「又抓了一遍」与「抓错了要改」 |
| 幂等键由谁给 | **调用方必填**，服务端不替它编 | 服务端自己编的键要么按内容哈希（修正时哈希变了，去重失效），要么按商品+时刻（修正会被当成重投而静默丢弃）。**没有它就分不清「重投」与「修正」** |
| 修正产生的新判定 | **新的一条预警记录**（唯一键带 `crawl_id` 而非 `snapshot_id`） | 若唯一键只有 `snapshot_id`，修正产生的新判定会撞上旧判定被 `on conflict do nothing` 静默丢掉 —— **修正就等于「没生效」**。带 `crawl_id` 之后旧判定作为历史留着 |
| 关键词**不属于**本次抓取的商品 | **整批拒绝**（422） | 那是调用方的契约错误，不是数据缺口。静默跳过会让调用方以为「这批 5 个关键词都收了」 |
| 关键词**已停用** | **跳过 + 逐条写明原因** | 停用是正常业务状态，不是错误。但**不静默丢弃** —— 调用方要能知道哪几个没进库 |
| 报表**建表**还是**实时聚合** | **实时聚合** | 报表是排名历史的**聚合视图**，落表就会与事实漂移 |
| 报表聚合键 | **（平台, 站点, 关键词）** | 站点码只在平台内唯一（每个平台都有 US/DE），平台才是父级 |
| 报表**没有样本**时平均名次 | **null**（不是 0） | 报 0 会被读成「平均第 0 名」—— 那是结论不是缺口 |
| 趋势按什么归集 | **UTC**（`crawled_at at time zone 'UTC'`），日 / 周 / 月 | 抓取时间是 timestamptz。直接 `date_trunc` 会随会话时区平移，同一份数据在不同的库里算出不同的桶 |
| 趋势没有数据时 | **空数组** | 一条平的假曲线会让人以为「这段时间排名没变」，而真相是「没抓到数据」 |
| 页面路由 | **新建 `/listings/keywords`** | 4.5.1.1 的会话日志留了一条待办：「`/listings/monitor` 已占，它交付时可能需要另开一条路径或同树下的子路径，先与调度者确认」。本批任务书已明确**新建** `apps/web/src/app/listings/keywords/page.tsx` 且**不动** `monitor` ⇒ 另开一条路径，且**不登记** task-pages（与最近交付车道一致） |
| API 前缀 | **`/channel/listing-keyword-rankings`（独立前缀）** | 4.1.1.1 已把 `/channel/listings/{uuid}/...` 登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单。而且「关键词排名」与「平台表现」「平台状态回流」是**三条独立通道**，口径、限流与重试互不干涉 |
| 权限码 | 复用 `channel.operation.read` / `.create` / `.update` / `.delete` | 新增要动 identity 的权限真相源，是别的车道的事。与 4.1.1.1 / 4.3.2.2 / 4.4.2.x / 4.5.1.1 同族 |
| 契约的 schema 名 | 全部 `ListingKeywordRanking*` | springdoc 用 record 的**简单类名**做 `components.schemas` 的键，重名会**静默覆盖**别人的契约（7.8.2.1 已踩过 `AllocationRunPage` 撞名） |
| 白名单怎么改 | **新增一条 `services[]` 元素**，插在 4.5.1.1 `listing-performance` 那一条**之后** | 既有 channel 分支是**单行超长正则**，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。本批另外三条车道的锚点互不重叠，git 能干净求并 |

## 未交付的上游 / 下游（如实标注，不假装有）

`GET /channel/listing-keyword-rankings/capabilities` 逐维度声明，四段 `available=false`：

| 维度 | 归属模块 | 本模块怎么表达 |
| --- | --- | --- |
| 1 工作台预警推送 | 1 工作台与门户（整卷未交付） | 预警只**记录** + 允许人工确认留痕，**不发通知**；推送通道 / 免打扰 / 升级策略归它 |
| 14 广告（关联投放） | 14 广告与增长（整卷未交付） | 广告排名只说明「付费位在第几名」，**不含**花费、点击与转化口径；**不用**「自然名次涨了 ⇒ 广告有效」这种猜出来的因果替代 |
| 真实排名抓取调度器 | 02 渠道与店铺（同步运行时） | 本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都记下来），但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决 |
| 竞品侧排名与变更 | 4.5.2.1 跟卖监控、4.5.2.2 上新与变更监控 | 本模块只监控**自有商品**的关键词名次；竞品数据不进自有商品模型 |

## 前端这个页面上最容易做丢的五件事

1. **抓取时间与不准确窗口标在界面上** —— 页面顶部横幅 + 列表每行 + 明细区顶部 +
   历史表每行 + 报表每行。蓝图加粗句是**契约级**要求，不是可选 UI 文案。
2. **名次没有就显示「—」，绝不显示成 0** —— `null` = 「这次平台没报这个维度的名次」。
3. **变化量 null 显示成「—」而不是「0 位」** —— 没有上一次抓取时是 null。
4. **名次变大 = 变差** —— 页面写「↓ 退了 N 位 / ↑ 进了 N 位」，不是「上升 / 下降」。
   从第 5 名掉到第 20 名是**退步**，方向标反了会让人做出相反的决策。
5. **趋势没有数据时是空态，不是平线** —— 且 Y 轴 `reversed`（名次越小越好），
   平台没报名次的桶在图上是**断点**而不是 0 线。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V205__listing_keyword_ranking.sql`（新建，四表 + 索引 + RLS + GRANT + 三个触发器）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingKeywordRankingModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingKeywordRankingService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingKeywordRankingController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingKeywordRankingIntegrationTest.java`（新建，25 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingKeywordRankingTenancyIntegrationTest.java`（新建，4 例）

**前端**

- `apps/web/src/app/listings/keywords/page.tsx`（新建）
- `apps/web/src/components/listing-keyword-ranking/listing-keyword-ranking-model.ts`（新建）
- `apps/web/src/components/listing-keyword-ranking/listing-keyword-ranking-model.test.ts`（新建，24 例）
- `apps/web/src/components/listing-keyword-ranking/listing-keyword-ranking-workspace.tsx`（新建）
- `apps/web/src/app/api/channel/listing-keyword-rankings-allowlist.test.ts`（新建，4 例）

**共享白名单 / 契约 / 台账（改动最小）**

- `apps/web/src/lib/api-proxy.ts`（**只新增一条 `services[]` 元素**，零删除、零改动既有元素）
- `packages/api-contracts/src/channel.openapi.json` + `contracts/channel.ts`（codegen 产物，**禁止手改**；
  纯新增 10 条 path + 19 个 schema，其余 5 个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md`（**只改 4.5.1.2 那一行**的状态与证据两格）

**刻意没动**：`packages/ui/src/lib/task-pages.ts` / `nav-config.ts`（只建页面不登记）、
`apps/web/src/app/listings/monitor/page.tsx`（4.5.1.1 的页面）、
`backend/services/channel/pom.xml`（本批 3 条 channel 车道共用 ⇒ 零新依赖）、
别的模块的迁移 / 端点 / 页面 / 测试。

## 端点清单

| 端点 | 作用 |
| --- | --- |
| `GET /channel/listing-keyword-rankings/capabilities` | 逐维度能力声明 + 排名字段口径 + 抓取时间与不准确窗口说明 |
| `GET /channel/listing-keyword-rankings/filter-options` | 筛选面板选项（平台 / 店铺 / 站点，均取本租户真实有的） |
| `GET /channel/listing-keyword-rankings` | 关键词集合列表（真分页 + 商品/店铺/平台/站点/关键词/启用状态筛选） |
| `POST /channel/listing-keyword-rankings` | 新建关键词（挂到商品上，带三个阈值） |
| `PUT /channel/listing-keyword-rankings/{keywordId}` | 改关键词与阈值 |
| `DELETE /channel/listing-keyword-rankings/{keywordId}` | 🔴 停用关键词（**软删除**，历史与预警留着） |
| `GET /channel/listing-keyword-rankings/{keywordId}` | 排名明细（最新名次 + 变化 + 阈值 + 抓取时间 + 窗口） |
| `GET /channel/listing-keyword-rankings/{keywordId}/history` | 排名历史（真分页，逐行带抓取时间与窗口） |
| `GET /channel/listing-keyword-rankings/{keywordId}/trend` | 趋势序列（日 / 周 / 月，UTC 归集） |
| `POST /channel/listing-keyword-rankings/crawls` | 🔴 接收排名抓取任务（批次级幂等；抓取时间与不准确窗口必填） |
| `GET /channel/listing-keyword-rankings/alerts` | 变化预警列表（真分页） |
| `PUT /channel/listing-keyword-rankings/alerts/{alertId}/acknowledge` | 人工确认一条预警（留痕，不推送） |
| `GET /channel/listing-keyword-rankings/report` | 🔴 关键词维度报表（真 SQL 聚合） |

## 踩过的三个坑（留给下一条车道）

1. **Java 可变参数不会展开尾部的 `Object[]`**：`sql.fetch(sql, a, b, c, d, arr)` 里的
   `arr` 会被当成**一个**绑定值（jOOQ 还会把它渲染成 `cast(? as any[])`），
   结果是 PG 报「参数 N 没有值」。**必须把全部绑定放进同一个 `List` 再 `toArray()` 传进去。**
2. **SQL 的 WHERE 必须在所有 JOIN 之后**。把筛选条件拼在 `join ... on ...` 后面、
   `left join` 前面 ⇒ `syntax error at or near "left"`。
3. **PG 不允许 DISTINCT 的 ORDER BY 表达式不在 select list 里**（42P10）——
   `select distinct p.code ... order by p.position` 会炸，改用 `exists` 或把排序列选出来。

## 验证

- `./mvnw -q compile` → 退出 0
- `./mvnw -pl services/channel -am test -Dtest='ListingKeywordRanking*'` → **29 例 0 失败 0 错误 BUILD SUCCESS**
  （`ListingKeywordRankingIntegrationTest` 25 + `ListingKeywordRankingTenancyIntegrationTest` 4）
- `pnpm typecheck` → 3/3 通过
- `pnpm lint` → 通过（告警全部落在**既有**其它文件，本车道零告警）
- `pnpm vitest run apps/web/src/components/listing-keyword-ranking apps/web/src/app/api/channel/listing-keyword-rankings-allowlist.test.ts`
  → **28 例全绿**（model 24 例 + allowlist 4 例）
- `pnpm vitest run apps/web/src/app/api/trade` → **42 文件 224 例全绿**（任务书指定的 allowlist 回归）
- `pnpm vitest run apps/web/src/app/api` → **91 文件 553 例全绿**（全量 allowlist 回归，确认白名单没挤掉兄弟车道路径）
- 契约：codegen 后 `channel.ts` 类型集合**零删除、19 新增**；其余 5 个服务的契约与
  `contracts/*.ts` **逐字节不变**
- Testcontainers：本车道 verify 期间同时存活 **2 个 `postgres:16-alpine`**（两个测试类各一个静态容器）
  + 1 个 `testcontainers/ryuk`；**运行结束后已被 ryuk 全部回收**（`docker ps -a` 里
  `postgres:16-alpine` 计数 = 0，无孤儿容器）

## 后续待办

1. **1 工作台预警**（整卷未交付）—— 交付后由它读 `listing_keyword_rank_alert` 推送预警；
   本模块的表结构与口径不用改，**不**自己发通知。
2. **14 广告（关联投放）**（整卷未交付）—— 交付后由它关联本模块的排名数据；
   本模块**不会**自己去猜广告带来的流量占比。
3. **02 真实排名抓取调度器**（归 02 同步运行时）—— 本模块的入库端点已就绪，
   适配器把平台名次推进来即可（带稳定的幂等键 + 抓取时间 + 不准确窗口）。
4. **4.5.2.1 跟卖监控 / 4.5.2.2 上新与变更监控** —— 竞品侧排名与变更归它们；
   本模块只管自有商品。
5. **关键词集合的录入入口** —— 本轮交付了租户内的增删改查与**抓取结果入库**端点，
   但「从商品库里选一批核心词批量建关键词」的批量入口**未做**（蓝图只写「关键词集合
   （按商品维护）」，逐条维护已覆盖职责范围）。若后续要做，走同一个
   `POST /channel/listing-keyword-rankings` 集合入口，不另开通道。
