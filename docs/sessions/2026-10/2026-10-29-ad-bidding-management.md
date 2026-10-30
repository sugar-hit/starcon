# 14.2.1.1 竞价管理（阶段 D7，L3 锚点 14.2.1）

- 日期：2026-10-29
- 车道：`feat/14-2-1-1`（worktree `.worktrees/14.2.1.1`）
- 服务归属：**channel**（卷 14 主责服务；广告竞价事实由 channel 自己拥有）
- 迁移：channel `V407__ad_bidding.sql`（**七张新表，纯 CREATE TABLE，零 ALTER 任何既有表**）
- 上游（均**已交付**，只读引用）：14.1.3.1 `ad_keyword` / `ad_keyword_metric`（V274）·
  14.3.2.1 `ad_attribution_fact`（V404）· 14.2.2.2 `ad_rule_dispatch`（V275）·
  13.4.1.x reporting `sku_profit`（V400，**只经授权只读 API**）

## 决策摘要

### 1. 🔴 改竞价只走 14.1.3.1 已交付的写路径，不另开写路径

本模块的每一次调价都调 `AdKeywordService.bulkAction(AdBulkActionRequest)`，直接继承上游
14.1.3.1 已经钉死的三条护栏与纪律：

| 上游常量                 | 值      | 含义               |
| ------------------------ | ------- | ------------------ |
| `MAX_BID_DELTA_RATIO`    | 0.50    | 单条调价比例上限   |
| `MAX_BID_ABSOLUTE`       | 1000.00 | 单条调价绝对值上限 |
| `MAX_TOTAL_BID_INCREASE` | 5000.00 | 一次批量总上调上限 |

连带继承 `batch_key` **幂等**（重投同一批次 appliedCount=0 + replayed=true）与**操作审计**。

**为什么不自己写一条 UPDATE**：两条写路径并存 ⇒ 「竞价是谁改的、为什么被限、改前改后是多少」
这个问题有两份答案。竞价是花钱的动作，**写路径只能有一条**。

### 2. 🔴 动态竞价策略是三档枚举 + 「竞价范围上限」与「调整上限」是两道不同的闸

`dynamic_bidding_strategy` 由 DB 层 CHECK 钉死三档：`UP_AND_DOWN`（只增只降）/ `DOWN_ONLY`（只降）/
`UP_ONLY`（只增）。**不是自由文本** —— 自由文本会让「平台支持哪几档」这个问题永远无法回答。

两道闸**各自独立**、不能互相顶替：

| 闸  | 管什么                                       | 越限码                                                                                       |
| --- | -------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 闸① | 策略的 `min_bid` / `max_bid`（**结果**区间） | `RANGE_MIN` / `RANGE_MAX`                                                                    |
| 闸② | 单次调整比例 / 金额额度 + 上游三条护栏       | `ADJUST_RATIO` / `ADJUST_AMOUNT` / `UPSTREAM_RATIO` / `UPSTREAM_ABSOLUTE` / `UPSTREAM_TOTAL` |

**越限逐条 REJECTED 并写明是哪道闸、哪个上限，绝不静默截断** —— 静默截断会让「我设了上限」
变成一句没人验证过的空话：调用方永远不知道自己申请的那个 120 被悄悄改成了 100。

**踩到的一个真坑**：上游 `bulkAction` 的比例上限**只在 PERCENT 模式生效**。本模块用 ABSOLUTE
模式时，那条 ±50% 的护栏在服务层是形同虚设的 ⇒ 预检里**补上同一条上限**（策略自定更严值优先）。

### 3. 🔴 ACOS 与 ROAS 都是 GENERATED 列，分母为 0 ⇒ NULL（不可比）而不是 0

```
current_acos numeric(19,6) GENERATED ALWAYS AS
  (CASE WHEN attributed_sales > 0 THEN round(spend / attributed_sales, 6) END)
current_roas numeric(19,6) GENERATED ALWAYS AS
  (CASE WHEN spend > 0 THEN round(attributed_sales / spend, 6) END)
```

分母为 0 给 **NULL 而不是 0**：「没卖货」与「占比完美」是两件相反的事，填 0 会把烧钱广告
显示成最优广告；ROAS 的分母是花费，花费为 0 时是真的零收益（**可比的 0**），与 ACOS 不可比要分开。

两列都是 **DB 自己算的** ⇒ 页面展示的 ACOS 与倍率**必然自洽**，服务层算不出一个和库对不上的数。
金额 `NUMERIC(19,4)` + BigDecimal，计数与比率 `NUMERIC(19,6)`，**零 float / 零 double**；
时间 `timestamptz`（jOOQ values 位置写 `?::timestamptz`）。

### 4. 🔴 建议粒度 = SKU 级口径 + 关键词级落价（两段式）

- **口径在 SKU 级**：花费取 14.1.3.1 `ad_keyword_metric`，按 listing→SKU 当前登记变体数
  **等分**（照抄 14.3.1.3 的两级等分口径）；归因销售额取 14.3.2.1 `ad_attribution_fact`，
  **只取生效规则当前版本**（历史版本不可比）。
- **落价在关键词级**：倍率 = 目标 ACOS ÷ 当前 ACOS；**建议竞价 = 该关键词自己的当前竞价 × 倍率**。

**为什么不把 SKU 均价抄给所有词**：同一个 SKU 下有的词转化好、有的词烧钱不转化，
一刀切按 SKU 均价调 ⇒ 高转化词被抬价（继续加贵）、低转化词被同幅抬价（更快烧穿）。
倍率是 SKU 级的，**落价是词自己的**。

### 5. 🔴 盈亏平衡 ACOS 口径，以及「目标高于盈亏平衡 ⇒ 否决」

reporting 的 `netProfit = revenue − totalCost`，而 `totalCost` **含 `AD_FEE`** ⇒
「广告前」的利润率是 **（净利 + 广告费）÷ 单品收入**。目标 ACOS 高于它 ⇒ 这个目标本身
不可能达成 ⇒ 建议被**否决**（`ABOVE_BREAK_EVEN`），而不是给一条注定达不到的建议。

### 6. 🔴 利润侧读不到 ⇒ 503（绝不返回空集合冒充「上游没有数据」）

蓝图强调「没有利润数据就不知道赚不赚钱」⇒ 利润侧是**硬依赖**。复用既有 `AdProductProfitPort`
（它已区分「上游读不到」与「上游说没数据」），本模块在 `available=false` 时抛 **503**。
空集合会被读成「这个 SKU 查过，没有利润数据」⇒ 建议凭空少一批，且**看不出是缺数据还是真没有**。

跨服务纪律：利润侧只经**授权只读 API**（零跨库 SQL / 零 FK / 零影子表 / 零共享视图），
转发**调用方自己的 bearer**、**不传租户号**。

### 7. 🔴 平台广告 API 未交付 ⇒ 调整只落「待下发队列」；撤销 ≠ 回滚

平台广告 API 未交付 ⇒ 调整**只落队列**（照抄 `ad_rule_dispatch` 形状）。队列唯一能改的动作是
**撤销**（`dispatch_status → CANCELLED` + 写原因），**刻意不提供「标记已下发」** ——
没有真平台回执时，「标记已下发」就是一台可以凭空造成功率的机器。

**撤销 ≠ 回滚**：撤销只把队列置 `CANCELLED`，**不回滚本地竞价**（回滚是一次新的调价，
要走同一套护栏与两道闸）。界面显式说明这一点。

### 8. 目标 ACOS 与目标 ROAS 二选一（互为倒数）

DB 层 `num_nonnulls(target_acos, target_roas) = 1` CHECK 钉死 ⇒ 两个都填或都不填都写不进去。
另一个由服务端换算（1/x）返回，避免调用方自己算出一个和库对不上的数。

### 9. 策略唯一性用表达式唯一索引（不是列唯一索引）

```sql
CREATE UNIQUE INDEX ad_bidding_policy_scope
  ON ad_bidding_policy(tenant_id, shop_code, platform_id,
                       coalesce(target_campaign_id, '00000000-0000-0000-0000-000000000000'::uuid));
```

NULL 在 UNIQUE 里**互不相等** ⇒ 直接建列唯一索引的话店铺级策略能建出好几条。
一个「作用范围」一条策略：SHOP 级一店一条，CAMPAIGN 级一活动一条，二者可共存。

### 10. 复用既有跨服务端口，不新增端口、不改别人文件

只注入既有 `AdProductProfitPort` 与 `AdProductCatalogPort`（不修改它们）⇒ 跨车道零冲突面。
兄弟样板照抄 `AdDaypartService` / `AdDaypartSourcePort` 的形状。

### 11. 台账行与汇总在**车道分支**上改（sync.mjs 跑在 worktree 里）

台账是多车道共享文件，并入时由 `merge=contract-union` 驱动器按**模块 ID** 求并。本车道的
`sync.mjs` 在 worktree 内跑 ⇒ 汇总行重算后与集成头**逐行落在同一值**（两边都是 432 已交付），
卷 13 行只有集成头一侧变、卷 14 行只有本车道一侧变 ⇒ git ort **零冲突、无重复行**。

## 关键 trade-off

| 备选                                               | 为什么没选                                                                                                 |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 自建一条调价写路径（直接 UPDATE `ad_keyword.bid`） | 两条写路径并存 ⇒ 「竞价是谁改的、为什么被限」有两份答案；且上游三条护栏与幂等全部要重写一遍                |
| 建议竞价 = SKU 均价                                | 一刀切：好词被抬价、烂词被同幅抬价；倍率是 SKU 级的、落价必须词自己的                                      |
| ACOS 分母用平台自报 `ad_keyword_metric.sales`      | 平台自报口径与 14.3.2.1 的归因口径**不可比**；口径必须唯一（用归因销售额），平台自报只作参考展示且分开写明 |
| 利润侧读不到 ⇒ 返回空集合                          | 空集合会被读成「查过、这个 SKU 没有利润数据」⇒ 建议凭空少一批且看不出根因 ⇒ 抛 503                         |
| ABSOLUTE 模式下不补 ±50% 预检                      | 上游比例上限只在 PERCENT 模式生效，不补则该护栏在绝对值模式下形同虚设                                      |
| 提供「标记已下发」端点                             | 没有真平台回执时，这是能凭空造成功率的机器 ⇒ 队列唯一能改的动作是「撤销」                                  |
| 撤销时一并回滚本地竞价                             | 回滚是一次新的调价，必须走同一套两道闸；否则回滚路径反而没有护栏                                           |
| 策略唯一性用列唯一索引                             | NULL 在 UNIQUE 里互不相等 ⇒ 店铺级策略能建出好几条 ⇒ 表达式唯一索引 + coalesce 零 UUID                     |

## 涉及文件

**新增（后端）**

- `backend/services/channel/src/main/resources/db/migration/V407__ad_bidding.sql` —— 七张表：
  `ad_bidding_policy` / `ad_bidding_suggestion` / `ad_bidding_suggestion_sku` /
  `ad_bidding_suggestion_item` / `ad_bidding_adjustment` / `ad_bidding_adjustment_item` /
  `ad_bidding_dispatch`；tenant_id NOT NULL + ENABLE/FORCE RLS isolation + RESTRICTIVE
  `data_scope`（按店铺收敛）+ GRANT channel_app、starcon_app 零权限
- `backend/services/channel/src/main/java/com/starcon/channel/AdBiddingModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdBiddingService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/AdBiddingController.java`
  —— 12 path / 16 端点挂 `/channel/ad-bidding`

**新增（后端测试）**

- `backend/services/channel/src/test/java/com/starcon/channel/AdBiddingIntegrationTest.java`（21 例）
- `backend/services/channel/src/test/java/com/starcon/channel/AdBiddingTenancyIntegrationTest.java`（3 例）

**新增（前端）**

- `apps/web/src/components/ads/ad-bidding-model.ts` / `ad-bidding.tsx` / `ad-bidding-model.test.ts`
- `apps/web/src/app/ads/bidding/page.tsx`
- `apps/web/src/app/api/channel/ad-bidding-allowlist.test.ts`

**修改**

- `apps/web/src/lib/api-proxy.ts` —— 新增**独立** `prefix: 'channel/'` 元素（不并入既有 channel
  正则），锚在第一个真正的 `prefix: 'identity/'` 之前
- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts` —— codegen
  **纯增量**（+12 path / +27 schema / 0 删除 / 0 既有条目内容变更）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 14.2.1.1 那一行

## 验证

| 闸      | 命令                                                                                                                                                        | 结果                                                                                             |
| ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| L0      | `./mvnw -pl services/channel -am compile`                                                                                                                   | BUILD SUCCESS                                                                                    |
| L0      | `pnpm typecheck`                                                                                                                                            | Tasks: 3 successful, 3 total                                                                     |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='AdBidding*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`                           | **Tests run: 24, Failures: 0, Errors: 0**（21 + 3）                                              |
| L1 前端 | `pnpm vitest run apps/web/src/app/api/trade apps/web/src/app/api/channel/ad-bidding-allowlist.test.ts apps/web/src/components/ads/ad-bidding-model.test.ts` | **Test Files 73 passed / Tests 415 passed**（改了 api-proxy.ts ⇒ 全量 trade 白名单回归一并跑）   |
| 契约    | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`                                                                              | 9 个服务 DTO 重新生成，本模块契约为纯增量                                                        |
| 白名单  | `.tmp/api-proxy-zero-hit.mjs`（不入库）                                                                                                                     | 370 条元素 / 既有 368 条；本模块 12 条路径**全部零命中**；反向 12 条其它车道形状命中集合一字未变 |

实跑 Testcontainers PostgreSQL 16（Flyway 到 v407）；多租户三断言全过：**两租户互不可见 /
伪造 tenant header 无效 / 连接池归还后不串租户**。

## 后续待办

1. 🔴 **仓库级缺口（非本车道范围）**：identity **未 seed 任何 `reporting.*` 权限码** ⇒
   `GET /reporting/sku-profit/report` 在真实部署中对普通账号返回 403 ⇒ 本模块的建议生成
   按设计返回 503。**同一缺口也影响已交付的 14.2.1.2 与 14.3.1.3** ⇒ 建议在 identity 车道补 seed。
2. 🔴 **平台广告 API 未交付**：待下发队列目前**没有消费者**；平台侧接入后需要补一个「按队列下发 +
   收回执」的 worker，届时才可以开放 `dispatch_status → DISPATCHED`。
3. 🔴 **5.2.2.1 汇率未交付**：本模块**不做跨币种折算**，金额按币种分列（与 14.2.2.2 同纪律）。
4. 建议接入后观察「按目标 ACOS 反推」的实际采纳率，再校准 §4 的等分口径。

## 关联

- 蓝图：`docs/product/capability-blueprint/14-ads-and-growth.md`（L3 锚点 14.2.1）
- 上游模块：14.1.3.1 关键词管理（写路径）、14.3.2.1 归因分析（归因销售额）、
  14.2.2.2 自动规则（待下发队列的形状样板）
- 兄弟模块：14.2.1.2 分时策略（同卷同锚点，`AdDaypartService` 是本模块的代码样板）
- 多租户：`docs/architecture/multi-tenancy.md` · ADR-0002 / ADR-0005
- 台账：`docs/product/capability-blueprint/module-progress.md`（14.2.1.1 行）
