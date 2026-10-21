# 14.1.3.1 关键词管理（阶段 D7，L3 锚点 14.1.3）

- **车道**：feat/14-1-3-1（worktree `.worktrees/14.1.3.1`）
- **蓝图**：14-ads-and-growth.md（14.1.3 关键词管理与分析）
- **主责服务**：channel（与 14.1.2.2 广告类型差异同库；关键词属平台侧数据，不属于 catalog / trade）
- **迁移版本**：V274 `V274__ad_keywords.sql`
- **API 命名空间**：`channel/ad-keywords`

## 决策摘要

### 1. 六张新表、**零 ALTER 任何既有表**

`ad_keyword`（关键词主体）· `ad_keyword_metric`（关键词表现，按 UTC 日）·
`ad_search_term`（搜索词报告）· `ad_negative_keyword`（否定词）·
`ad_keyword_bulk_action`（批量任务）· `ad_keyword_bulk_action_item`（逐条结果）。

**活动用外部引用，不建跨车道 FK**：14.1.1.1 活动列表 / 14.1.1.3 批量操作 / 14.1.2.1 广告组管理
在台账上均**未交付**，所以活动维度只落 `external_campaign_id` / `external_keyword_id`
（平台 + 店铺 + 外部 id 三元组），不建指向不存在表的 FK。等活动侧交付后按外部 id 对接，
本模块的表结构不需要改。

六表统一 `tenant_id uuid NOT NULL` + `ENABLE` / `FORCE` RLS isolation + RESTRICTIVE 数据范围策略
（随店铺收敛，子表随父表收敛）+ `GRANT channel_app` / `REVOKE starcon_app`。

### 2. 归一类型**沿用 14.1.2.2**，不自造第三套

`ad_keyword` 上 `internal_ad_type` 与 `platform_ad_type` **同表并存**（归一只多一列、
原始标记不折叠）；未映射显式是 `UNMAPPED` 合法值。登记时服务端调
`AdTypeDiffService.resolve()`（租户覆盖 > 平台默认 > 未映射）解析后与请求声明**逐项比对，
不一致即拒** —— 声称归一成一个平台根本没配过的类型，会让跨平台可比性变成假的。

### 3. 比率**全部 SQL 现算，分母为 0 给 null 而不是 0**

ACOS = 花费 / 销售额（分母是**销售额**，不是曝光也不是点击）；CPC = 花费 / 点击；
CTR = 点击 / 曝光；CVR = 转化 / 点击。分母为 0 一律返回 `null`：

- 「没卖货」与「广告完美」是两件相反的事，ACOS 填 0 会把烧钱广告显示成最优广告；
- 「不可比」与「真的是 0」必须分开渲染，前端因此区分 `null` 与 `0` 两种展示。

### 4. 一个关键词**只允许一个指标币种**，报表**按币种分行**

`ad_keyword.metric_currency` 由首行写入定下，DB 触发器 `guard_ad_keyword_metric_currency`
核对后续指标行币种一致。跨币种的花费/销售额**不可相加**，
所以关键词报表**按币种分行**而不是给一个「总额」。

### 5. 否定词的依据**由服务端现算**，调用方自述只作审计

`ad_negative_keyword.evidence jsonb` 的数值由服务端按「**搜索词报告优先 → 落到关键词表现**」
现算；调用方自己填的依据存 `claimedEvidence` 只作审计留痕。
DB 触发器 `guard_ad_negative_keyword_evidence` 拒绝空依据
（绕服务层直接写 SQL 同样被拒），`guard_ad_negative_keyword_shop` 拒绝跨店铺否定。
否定必须写明原因；状态机 `DECIDED → PUSHED / REJECTED` 并留平台回执。

### 6. 批量调价有护栏，**越线整批不动**（而不是部分生效）

护栏四条：单批 ≤ 200 条、单条幅度 ≤ 50%、单条绝对值 ≤ 1000.00、总上调 ≤ 5000.00。

- **总上调越限** → 任务整体 `REJECTED`，逐条 `SKIPPED` 并写明是哪条上限；
- **单条越限**（幅度 / 绝对 / 币种不符）→ **该条** `FAILED`，不拖累同批其它条目；
- 逐条 `before_bid` / `after_bid` 与结果落 `ad_keyword_bulk_action_item`。

前端护栏数值从 `capabilities` 取，**不写死在前端**。

### 7. 批次幂等用 `batch_key` 列实现（不额外建表）

重投同一批次 → `appliedCount=0` + `replayed=true`，重投两次不会把出价改两次。
批量任务重投返回既有任务计数 + `replayed=true` + 原因说明本次未执行。

### 8. 排名与词频**引用 4.5.1.2**，不新建排名表、不自算名次

- 名次：读 `listing_keyword_rank_snapshot` **最近一次**抓取（`order by crawled_at desc limit 1`，
  不用 `array_agg` —— `array_agg` 会跳过 null，把「没排进名次」与「没抓过」混成一条）；
- 词频：`listing_keyword` 里同平台同词被**多少个商品**监控着。

### 9. 未交付的相邻模块在 `capabilities` 里**显式声明为缺口**

`available=false` + `ownerModule` + `impact` 逐条点名：
14.2.1.1 竞价策略引擎、14.1.3.2 投放定位、14.1.1.1 活动列表 / 批量操作、
平台侧否定词推送（02 通道 / 平台适配器）。本模块**不替它们实现**。

## 关键 trade-off

| 选择                            | 放弃了什么               | 为什么                                                                          |
| ------------------------------- | ------------------------ | ------------------------------------------------------------------------------- |
| 活动用**外部 id 引用**，不建 FK | 「库里能 join 出活动名」 | 14.1.1.1 未交付，FK 会指向一张不存在的表；外部引用也是平台侧唯一稳定的键        |
| 指标**按币种分行**              | 一个「总花费」数字       | 跨币种相加得到的是不存在的数；分行后每行都能与平台账单对上                      |
| 比率**分母为 0 给 null**        | 「页面永远有数」         | 0 与 null 是两种事实：没卖货 ≠ 占比为 0                                         |
| 护栏**越线整批不动**            | 「尽量执行一部分」       | 部分生效会让「这次批量到底改了什么」无法回答 —— 逐条结果 + 整批拒绝各有明确答案 |
| 否定依据**服务端现算**          | 信任调用方提交的依据     | 调用方自述的依据可以被随手编；现算 + 触发器拒空是唯一能让「否定有据」成立的方式 |
| 名次**读 4.5.1.2 的表**         | 本模块自建排名表         | 自建就是第二套名次口径，跨模块对不上；引用还免了「谁先更新」的问题              |
| 批量目标**重复即 400**          | 静默去重                 | 静默去重会把「我以为改了 5 条」变成 4 条                                        |

## 文件清单

**后端**

- `backend/services/channel/src/main/resources/db/migration/V274__ad_keywords.sql`
  —— 六张新表 + 3 个护栏触发器 + RLS isolation / 数据范围策略 + GRANT / REVOKE
- `backend/services/channel/src/main/java/com/starcon/channel/AdKeywordModels.java`
  —— 19 个端点的请求 / 响应 record + 4 段口径常量（指标口径 / 词频口径 / 否定依据口径 / 护栏口径）
- `backend/services/channel/src/main/java/com/starcon/channel/AdKeywordService.java`
  —— 显式 SQL 实现 19 个能力（`sql.execute` 走 `DSLContext`，**不引用任何 jOOQ 生成类**）
- `backend/services/channel/src/main/java/com/starcon/channel/AdKeywordController.java`
  —— 19 个 HTTP 映射（13 条路径形状）挂 `/channel/ad-keywords`，权限复用 `channel.operation.*`
- `backend/services/channel/src/test/java/com/starcon/channel/AdKeywordIntegrationTest.java`（21 例）
- `backend/services/channel/src/test/java/com/starcon/channel/AdKeywordTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/components/ads/ad-keywords-model.ts` —— 类型 + API 封装 + 纯函数（金额 / 比率 / 名次 / 同步态 / 空态 / 依据行 / 护栏摘要 / 校验）
- `apps/web/src/components/ads/ad-keywords.tsx` —— 工作区（能力与口径、关键词列表 + 筛选 + 分页、关键词明细、登记 / 改词、指标同步、搜索词同步、搜索词报告（浪费高亮）、否定词建议（带依据数值 + 采纳）、否定记录（依据 + 平台回执）、批量调价与否定（勾选目标 + 逐条结果）、批量任务历史、关键词报表（按币种分行）、词频与排名；加载 / 空 / 错误三态齐全）
- `apps/web/src/components/ads/ad-keywords-model.test.ts`（20 例）
- `apps/web/src/app/ads/keywords/page.tsx`
- `apps/web/src/app/api/channel/ad-keywords-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**
  （插入锚点：文件中**第一条** `prefix: 'channel/'` 元素之前；未并入既有 alternation 正则）

**共享生成物（codegen 产物，多车道共用）**

- `packages/api-contracts/src/channel.openapi.json` —— paths **+13 / -0**，schemas **+32 / -0**，
  既有 paths 与 schemas **结构变化 0**
- `packages/api-contracts/src/contracts/channel.ts` —— 类型 **+32 / -0**

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 14.1.3.1 那一行的「状态」「证据」两格
  改为已交付；另跑了一次 `node tools/module-progress/sync.mjs`（见「台账统计口径」一节）

## 过程中被测试逼出来的真实缺陷（已修）

1. **`limit/offset` 绑参顺序颠倒** —— 分页参数绑反了，第一页返回最后一批数据。
2. **`?` 占位符与 SQL 文本顺序不一致** —— lateral 子查询的占位符出现在 `WHERE` 之前，
   位置对不上导致绑定错值。
3. **count 子查询里写了两个 `from`** —— 生成 `from from ...`，SQL 直接语法错。
4. **text block 首行没有空格** —— 拼出来是 `as sourcefrom ...`。
5. **`Filters` 别名硬编码成 `k`** —— negatives 用别名 `n`、bulkActions 用 `a`，
   复用同一段拼接 SQL 时列名解析不到。
6. **`st.bucket_date` 不在 `GROUP BY`** —— PostgreSQL 直接报错。
7. **report 外层引用 `f.code` 越界** —— `f` 是子查询别名，外层不可见；改用 `km.*`。
8. **Jackson `DecimalNode` 抹掉尾随零** —— `12.5000` 变成 `12.5`，金额展示对不上账单；
   改成流式 `JsonParser` + `new BigDecimal(p.getText())` 保留标度。
9. **批量目标重复导致 DuplicateKey** —— 同一目标提交两次把唯一索引撞了；服务层提前 400 并点名重复项。
10. **api-proxy 正则 uuid 分支缺前导 `/`、裸路径未放行** —— 真实调用点被 404 挡掉。

## 验证

| 项                                             | 命令                                                                                               | 结果                                                                                                                |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| L0 编译                                        | `cd backend && ./mvnw -q -pl services/channel -am compile`                                         | exit 0                                                                                                              |
| L0 类型                                        | `TURBO_FORCE=true pnpm typecheck`                                                                  | **3 successful / 3 total（0 cached）**                                                                              |
| L0 lint                                        | `TURBO_FORCE=true pnpm lint`                                                                       | 3 successful（4 条 warning 全在**别的车道**的既有文件：catalog / orders / warehouse，与本车道无关）                 |
| L1 后端                                        | `./mvnw -pl services/channel -am test -Dtest='AdKeyword*' -Dsurefire.failIfNoSpecifiedTests=false` | **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0**（21 + 4），BUILD SUCCESS；Flyway 实跑到 `now at version v274` |
| 前端（改了共享白名单 ⇒ 跑全量 allowlist 回归） | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/components/ads`                         | **47 文件 / 262 例全通过**                                                                                          |
| 契约                                           | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`                     | channel：paths +13/-0、schemas +32/-0，既有结构变化 0；trade 侧既有漂移已 `git checkout --` 还原，不混入本车道      |

多租户三断言在 `AdKeywordTenancyIntegrationTest` 里**实跑**（Testcontainers PostgreSQL 16）：
两租户互不可见、伪造 `X-Tenant-Id` 无效、连接池归还后不串租户；
另加六张表 `FORCE` RLS 生效断言。

## 台账统计口径

基线台账的 `sync.mjs --check` **本来就是红的**（别的车道改了行没重跑同步，汇总数字停在 287）。
本车道按项目自己的处置口径（`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`：
「台账是 `sync.mjs` 的生成物，跑一次 `sync.mjs` 重新生成即可，
实测它只改汇总表与行内空白，不动交付状态」）跑了一次同步：

- 已交付 287 → **293**（其中 **+1 是本车道 14.1.3.1**，另 +5 是基线里已被标成「已交付」、
  但汇总数字没跟上的既有行）；
- 除汇总表与行内空白外，**其他模块行的「状态」「证据」两格逐字节未变**（已按行比对确认）。

## 后续待办

1. **14.2.1.1 竞价策略引擎未交付** —— 批量调价只提供**人工发起的、带护栏的**改价，
   没有自动策略；`capabilities` 里已显式标 `available=false` + `ownerModule`。
2. **14.1.3.2 投放定位未交付** —— 关键词主体没有投放定位维度，
   capabilities 里显式标缺口，不 mock 一份定位数据。
3. **14.1.1.1 活动列表 / 14.1.1.3 批量操作未交付** —— 活动维度目前只有外部 id，
   活动侧交付后按外部 id 对接即可，本模块表结构不用改。
4. **平台侧否定词推送未接** —— 当前只登记否定决策（`DECIDED`）与平台回执
   （`PUSHED` / `REJECTED`），真正的「推到平台」要等 02 通道的平台适配器落地。
5. **`ad_keyword.listing_keyword_id` 目前可空** —— 4.5.1.2 的 `listing_keyword` 在
   同一服务同一库，是**真 FK**（`ON DELETE SET NULL`）；未关联到商品监控的关键词允许为空。
