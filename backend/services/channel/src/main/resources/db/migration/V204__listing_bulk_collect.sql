-- ===========================================================================
-- 4.4.1.2 批量/插件采集（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.4.1.2 批量/插件采集
--   - 职责：支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，
--           解决大批量找货场景。
--   - 输入：分类页 URL / 关键词 + 页数范围；插件上报的页面数据。
--   - 输出：批量采集任务 + 结果清单。
--   - 关系：上游 = 4.4.1.1；下游 = 4.4.1.3。**抓取必须有频率限制与去重**
--           （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 本模块**复用** 4.4.1.1 的采集任务与草稿结构，不另起一套采集模型
-- ---------------------------------------------------------------------------
-- 蓝图「上游 = 4.4.1.1」与任务书「批量采集产出的每一条结果同样落成 listing_collect_draft
-- 草稿（下游 4.4.1.3 采集箱认领的就是它）」是硬约束。
-- V202 已经把「一次商品采集」表达得很完整：
--   * `listing_collect_task`  —— 一次抓取（成功与失败都留痕）
--   * `listing_collect_draft` —— 采集草稿（**不是商品**，没有 sku/spu/售价/库存任何一列）
-- ⇒ 本模块**不建**第二套草稿表，批量采集与插件上报产出的每一条结果都写进这两张既有表：
--   * 批量采集命中一条商品 URL ⇒ 写一条 `listing_collect_task`（state=SUCCEEDED）
--     + 一条 `listing_collect_draft`（下游 4.4.1.3 认领的就是它）；
--   * 插件上报一条商品 ⇒ 同样写一条 `listing_collect_task`（state=SUCCEEDED，http_status=0
--     表示「不是本服务发起的 HTTP 抓取，而是插件在用户浏览器里抓到的数据」）
--     + 一条 `listing_collect_draft`。
-- ⇒ 本模块**只 INSERT 这两张既有表，不 ALTER 它们、不改它们任何一列**
--   （「不许 ALTER 任何既有表」纪律的延续）。
--
-- 🔴 为什么插件上报也要写 `listing_collect_task`：
--   `listing_collect_draft.task_id` 是 `NOT NULL` 且外键指向
--   `listing_collect_task(tenant_id, id)` —— 草稿**不可能**脱离采集任务单独存在。
--   这不是本模块的选择，是 4.4.1.1 已经定下的结构纪律（草稿永远是某次采集的产物）。
--   附带好处：去重因此**天然跨模式生效** —— 插件报上来的商品，如果之前已被链接采集
--   或被批量采集抓到过，命中同一条规范化 URL，直接跳过。
--
-- ---------------------------------------------------------------------------
-- 一、本迁移**纯新建四张表、零 ALTER 任何既有表**
-- ---------------------------------------------------------------------------
--   * `listing_collect_source` / `listing_collect_task` / `listing_collect_draft`
--     —— 4.4.1.1 的三张表，**只读复用**（批量链路 INSERT 采集任务与草稿），**本迁移不动它们**。
--   * `listing_claim` / `listing_claim_field` —— 4.4.2.1 跨店搬家，语义不同，**不碰**。
--   * `listing_migration_*` —— 4.4.2.2 批量搬迁，**不碰**。
--
-- 新建四张表：
--   1) `listing_bulk_collect_policy` —— 限频策略（**全局码表**，不带 tenant_id）
--   2) `listing_bulk_collect_search` —— 平台分页/搜索模板（**全局码表**，不带 tenant_id）
--   3) `listing_bulk_collect_task`   —— 批量采集任务（**租户业务表**，RLS）
--   4) `listing_bulk_collect_result` —— 批量采集结果清单（**租户业务表**，RLS）
--
-- 🔴 为什么 1) 2) 是**全局码表**而不是租户级配置（与 V1 `platform` / `marketplace`、
--    V202 `listing_collect_source` 同一先例）：
--   * 不带 tenant_id、不启用 RLS、**只授 SELECT** ⇒ 租户不能自行放宽限频阈值，
--     也不能自行把任意域名加进「可搜索的平台」—— 抓取面不会因为单个租户而变大。
--   * 运维改策略 / 改模板 = 改码表（迁移 / 运维流程），不经过任何业务端点。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 边界一：采集**只产生草稿**，不建 SPU / SKU
-- ---------------------------------------------------------------------------
-- 与 4.4.1.1 同一纪律，且本模块**没有**任何一条路径能绕开它：
--   * 本迁移**不建**任何 SPU / SKU 表，也不建指向 catalog 的外键（跨服务 FK 禁止）。
--   * 本模块写的是 `listing_collect_draft` —— 它**没有** `spu_id` / `sku_id` /
--     `internal_sku_id` / `price` / `stock` **任何一列**，一列都没有就不可能
--     「顺手续建一个商品」。
--   * 结果清单表 `listing_bulk_collect_result` 同样**没有**商品主数据列，
--     它只是「这次批量采集碰到了哪些 URL、每条的结果是什么」的清单。
--   * 认领（草稿 → 内部商品）由 4.4.1.3 采集箱 → 3.1.1.1 建档流程完成，
--     那条链路的写所有权**不在本模块**。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 边界二：图片**只记 URL**，不入媒体库
-- ---------------------------------------------------------------------------
-- 媒体库归 03 catalog，AGENTS.md 明令**禁止跨服务数据库访问**。
-- ⇒ 批量结果清单与采集草稿里的图片都是**外部图片地址**，不下载二进制、不写媒体表。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 限频：三层，每一层都**明确拒绝**并给出可读原因
-- ---------------------------------------------------------------------------
-- 蓝图「**抓取必须有频率限制**……避免……外部站点压力」。本模块把限频拆成三层，
-- 每一层都有**明确的拒绝路径**（不是排队等死）：
--
--   ① **并发数**（`listing_bulk_collect_policy.max_concurrent_tasks`）
--      同一租户同时处于 PENDING / RUNNING 的批量任务数达到上限 ⇒ 发起新任务时
--      **429** 并写明「本租户同时最多 N 个批量采集任务」。
--      计数基准是**任务历史本身**（不是另建一张「运行中任务表」）—— 另建就会出现
--      「运行表说没在跑、任务表说在跑」的两份事实。
--
--   ② **任务启动间隔**（`listing_bulk_collect_policy.min_task_start_interval_seconds`）
--      同一租户上一次发起采集任务距今不足该间隔 ⇒ **429** 并写明还需等多少秒。
--
--   ③ **同 host 抓取间隔**（`listing_bulk_collect_search.bulk_min_interval_seconds`）
--      同一租户同一 host 的两次抓取（翻页请求 + 商品详情请求都算）之间必须有最小间隔。
--      发起任务时若该 host 刚被抓过 ⇒ **429** 并写明还需等多少秒；
--      任务**运行中**若下一次抓取需要等待的时间超出本次运行的时间预算
--      （`listing_bulk_collect_policy.max_task_seconds`）⇒ 任务停在 `RATE_LIMITED`
--      并写明「还需等多少秒、点继续接着采」，**已采到的结果全部保留**。
--
-- 🔴 为什么批量采集有**自己**的同 host 间隔，而不是直接用 4.4.1.1 的
--    `listing_collect_source.min_interval_seconds`（30–60 秒）：
--   单条采集是**人点的**，一次一个链接，间隔大一点没有代价；
--   批量采集是**机器跑的**，30 秒一个请求意味着一个 3 页 × 20 条的任务要跑半小时，
--   那不是限频，那是把功能做成不可用。
--   ⇒ 批量间隔按平台单独配（默认 2 秒 = 0.5 req/s，仍然是温和的速率），
--   与单条采集的保守间隔**并存**：两条路径都受「同租户同 host 的最小间隔」约束，
--   只是速率不同。运维可随时调整。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 去重：同 URL 不重复抓；已存在的商品跳过
-- ---------------------------------------------------------------------------
-- 蓝图「（**同 URL 不重复抓、已存在的商品跳过**）」。本模块的去重**复用 4.4.1.1 的键**：
--   * 去重键 = `listing_collect_url` 规范化后的 URL（去 utm/spm/from 等跟踪参数、
--     归一 host、排序查询参数、去 fragment），落 `listing_collect_task.normalized_url`。
--   * 判据 = 该租户下该规范化 URL 是否已有 **SUCCEEDED** 的采集任务
--     （4.4.1.1 的 `listing_collect_task_one_success` 部分唯一索引），
--     或已有 `listing_collect_draft`（`UNIQUE(tenant_id, normalized_url)`）。
--   * 命中 ⇒ 结果清单记一条 `SKIPPED_DUPLICATE` + **不发出第二次 HTTP 请求**。
--   * 🔴 **失败的抓取不占去重名额**（4.4.1.1 的纪律）：否则一次网络抖动就把这个 URL
--     永久锁死。
--   * 本表再加一道**部分唯一索引** `listing_bulk_collect_result_fetched`：
--     同一租户同一规范化 URL 在结果清单里**只能有一条 FETCHED**。
--
-- 🔴 跨模式去重：因为批量与插件都写 `listing_collect_task` / `listing_collect_draft`，
--   「链接采集抓过的商品」不会被批量采集再抓一遍，「插件报过的商品」也不会被
--   批量采集再抓一遍 —— 三种入口共享同一个去重事实。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   * 结果清单里唯一的金额列是 `price_amount numeric(19,4)` —— **外部页面原文标价**，
--     **不是我方售价**（售价归 05 定价，整卷未交付）。插件上报的价格同样只是
--     来源站上的报价。用 BigDecimal 绑定、DB 用 NUMERIC，**无 float / double 一列**。
--   * 计数列（`total_pages` / `discovered_count` / `fetched_count` /
--     `skipped_duplicate_count` / `failed_count` / `skipped_rate_limited_count`）
--     都是整数计数，**不是数量事实**（我方可售量归 07 库存，本模块不产出）。
--
-- ---------------------------------------------------------------------------
-- 七、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**。
--
-- ===========================================================================
-- 1) listing_bulk_collect_policy · 限频策略（全局码表，不带 tenant_id）
-- ===========================================================================
CREATE TABLE listing_bulk_collect_policy (
  id                uuid PRIMARY KEY,
  code              varchar(32) NOT NULL UNIQUE,
  name              varchar(100) NOT NULL,
  -- 🔴 同一租户同时处于 PENDING / RUNNING 的批量任务数上限（超限 ⇒ 429）
  max_concurrent_tasks integer NOT NULL DEFAULT 2 CHECK(max_concurrent_tasks>=1),
  -- 🔴 同一租户两次发起采集任务的最小间隔（秒，超限 ⇒ 429）
  min_task_start_interval_seconds integer NOT NULL DEFAULT 2 CHECK(min_task_start_interval_seconds>=0),
  -- 单个任务最多翻多少页
  max_pages_per_task integer NOT NULL DEFAULT 10 CHECK(max_pages_per_task>=1),
  -- 单个任务最多落多少条草稿
  max_items_per_task integer NOT NULL DEFAULT 100 CHECK(max_items_per_task>=1),
  -- 单次插件上报最多带多少条商品
  max_items_per_report integer NOT NULL DEFAULT 100 CHECK(max_items_per_report>=1),
  -- 🔴 单次运行的时间预算（秒）：超出就停在 RATE_LIMITED，已采到的结果保留
  max_task_seconds integer NOT NULL DEFAULT 60 CHECK(max_task_seconds>=1),
  enabled           boolean NOT NULL DEFAULT true,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
COMMENT ON TABLE listing_bulk_collect_policy IS
  '4.4.1.2 · 批量采集限频策略（全局码表，只授 SELECT）。租户不能自行放宽阈值 —— '
  '限频是外部站点压力的保护，不是可调的业务参数。';

INSERT INTO listing_bulk_collect_policy
  (id,code,name,max_concurrent_tasks,min_task_start_interval_seconds,max_pages_per_task,
   max_items_per_task,max_items_per_report,max_task_seconds,enabled)
VALUES
  ('6c1d0002-0000-4000-8000-000000000001','DEFAULT','批量采集默认限频策略',
   2,2,10,100,100,60,true);

-- ===========================================================================
-- 2) listing_bulk_collect_search · 平台分页 / 搜索模板（全局码表，不带 tenant_id）
-- ===========================================================================
-- 🔴 为什么需要这张表：蓝图的关键词入口是「**关键词 + 页数范围**」，
--   光有关键词拼不出 URL —— 每个平台的搜索页地址与翻页参数都不一样。
--   把它们做成**配置**（而不是写死在 Java 里）有两个好处：
--     * 平台改版时只改码表，不用改代码、不用重新发版；
--     * 请求里可以**临时覆盖**模板（见服务层），不必等运维改配置。
CREATE TABLE listing_bulk_collect_search (
  id                uuid PRIMARY KEY,
  -- 🔴 命中的平台白名单编码（4.4.1.1 的 listing_collect_source.code）
  source_code       varchar(32) NOT NULL UNIQUE REFERENCES listing_collect_source(code),
  name              varchar(100) NOT NULL,
  -- 关键词搜索页 URL 模板：含 {keyword} 与 {page} 两个占位符
  -- （空串 = 该平台不支持关键词批量，调用时会明确拒绝而不是拼一个假 URL）
  search_url_template text NOT NULL DEFAULT '',
  -- 分类页翻页参数名（分类页 URL 上承载页码的 query 参数）
  category_page_param varchar(64) NOT NULL DEFAULT 'page',
  -- 站点第一页对应的页码偏移：{page} 实际收到 page_origin + (N - 1)
  -- （淘宝 / 天猫的 s 参数是 0 基的，1688 的 beginPage 是 1 基的）
  page_origin       integer NOT NULL DEFAULT 1 CHECK(page_origin>=0),
  -- 🔴 批量抓取同 host 的最小间隔（秒）—— 与 4.4.1.1 的 min_interval_seconds 分开，
  --    理由见文件头第四节。0 = 不限（仅测试与自建源站用）。
  bulk_min_interval_seconds integer NOT NULL DEFAULT 2 CHECK(bulk_min_interval_seconds>=0),
  enabled           boolean NOT NULL DEFAULT true,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(source_code)<>'' AND btrim(name)<>'')
);
COMMENT ON TABLE listing_bulk_collect_search IS
  '4.4.1.2 · 平台搜索/分页模板（全局码表，只授 SELECT）。'
  'search_url_template 里的 {keyword} / {page} 由服务层替换；模板可被请求临时覆盖。';

-- 种子：真实平台的公开搜索入口（运维可改码表，不必改代码）
INSERT INTO listing_bulk_collect_search
  (id,source_code,name,search_url_template,category_page_param,page_origin,bulk_min_interval_seconds,enabled)
VALUES
  ('6c1d0002-0000-4000-8000-000000000101','ALI_1688','1688 搜索',
   'https://s.1688.com/selloffer/offer_search.htm?keywords={keyword}&beginPage={page}','page',1,2,true),
  ('6c1d0002-0000-4000-8000-000000000102','TAOBAO','淘宝搜索',
   'https://s.taobao.com/search?q={keyword}&s={page}','s',0,2,true),
  ('6c1d0002-0000-4000-8000-000000000103','TMALL','天猫搜索',
   'https://list.tmall.com/search_product.htm?q={keyword}&s={page}','s',0,2,true),
  ('6c1d0002-0000-4000-8000-000000000104','ALIBABA','阿里巴巴国际站搜索',
   'https://www.alibaba.com/trade/search?SearchText={keyword}&pageNum={page}','pageNum',1,3,true),
  ('6c1d0002-0000-4000-8000-000000000105','MADE_IN_CHINA','中国制造网搜索',
   'https://www.made-in-china.com/productdirectory.do?word={keyword}&pageNum={page}','pageNum',1,3,true),
  ('6c1d0002-0000-4000-8000-000000000106','DHGATE','敦煌网搜索',
   'https://www.dhgate.com/wholesale/search.do?act=search&searchkey={keyword}&page={page}','page',1,3,true);

-- ===========================================================================
-- 3) listing_bulk_collect_task · 批量采集任务（租户业务表）
-- ===========================================================================
CREATE TABLE listing_bulk_collect_task (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 命中的白名单行（🔴 恒非空：不在白名单的平台根本不会被采，不会留下任务）
  source_id         uuid NOT NULL REFERENCES listing_collect_source(id),
  -- CATEGORY_PAGE 分类页批量 / KEYWORD 关键词批量 / PLUGIN 浏览器插件上报
  mode              text NOT NULL CHECK(mode IN ('CATEGORY_PAGE','KEYWORD','PLUGIN')),
  -- CATEGORY_PAGE 模式：用户给的分类页 URL 原文
  category_url      text NOT NULL DEFAULT '',
  -- KEYWORD 模式：关键词原文
  keyword           text NOT NULL DEFAULT '',
  page_from         int NOT NULL DEFAULT 1 CHECK(page_from>=1),
  page_to           int NOT NULL DEFAULT 1 CHECK(page_to>=1),
  -- 生效的分页 URL 模板（KEYWORD 模式含 {keyword}/{page}；CATEGORY_PAGE 模式含 {page}；
  -- 空串 = 用平台配置的分类页翻页参数自行拼）
  page_url_template text NOT NULL DEFAULT '',
  -- PENDING 待运行 / RUNNING 运行中 / SUCCEEDED 全部采完 / PARTIAL 部分采完
  -- FAILED 失败 / RATE_LIMITED 被限频打断（可继续）
  state             text NOT NULL CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','RATE_LIMITED')),
  error_message     text NOT NULL DEFAULT '',
  -- 计划页数
  total_pages       int NOT NULL DEFAULT 0 CHECK(total_pages>=0),
  -- 结果清单里发现的条目数
  discovered_count  int NOT NULL DEFAULT 0 CHECK(discovered_count>=0),
  -- 成功落成采集草稿的条数
  fetched_count     int NOT NULL DEFAULT 0 CHECK(fetched_count>=0),
  -- 因「同 URL 已采过」被跳过的条数
  skipped_duplicate_count int NOT NULL DEFAULT 0 CHECK(skipped_duplicate_count>=0),
  -- 抓取失败的条数
  failed_count      int NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  -- 因限频被跳过的条数
  skipped_rate_limited_count int NOT NULL DEFAULT 0 CHECK(skipped_rate_limited_count>=0),
  -- 断点：下一次运行从第几页接着采
  next_page         int NOT NULL DEFAULT 1 CHECK(next_page>=1),
  -- 🔴 同 host 最近一次**翻页请求**的时刻（同 host 抓取间隔的另一半基准；
  --    商品详情请求的基准在 listing_collect_task 里，与 4.4.1.1 同一份事实）
  last_grab_at      timestamptz,
  last_grab_host    varchar(255) NOT NULL DEFAULT '',
  actor_id          uuid,
  -- 负责人 / 部门：可见性随它收敛（与 V1 shop / V171 listing 同一纪律）
  owner_id          uuid,
  department_id     uuid,
  started_at        timestamptz,
  finished_at       timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(page_to>=page_from),
  -- 模式与输入必须自洽：分类页模式要有分类页 URL，关键词模式要有关键词
  CHECK(mode<>'CATEGORY_PAGE' OR btrim(category_url)<>''),
  CHECK(mode<>'KEYWORD' OR btrim(keyword)<>'')
);
COMMENT ON TABLE listing_bulk_collect_task IS
  '4.4.1.2 · 批量/插件采集任务。🔴 不是商品、也不是草稿本体：'
  '本表只记录「这次批量采集做了什么、成了几条、跳过几条、为什么」；'
  '真正的采集草稿在 4.4.1.1 的 listing_collect_draft 里。';

-- 任务列表（默认排序：时间倒序）
CREATE INDEX listing_bulk_collect_task_recent ON listing_bulk_collect_task(tenant_id, created_at DESC, id);
-- 状态筛选
CREATE INDEX listing_bulk_collect_task_state  ON listing_bulk_collect_task(tenant_id, state);
-- 模式筛选
CREATE INDEX listing_bulk_collect_task_mode   ON listing_bulk_collect_task(tenant_id, mode);
-- 来源平台筛选
CREATE INDEX listing_bulk_collect_task_source ON listing_bulk_collect_task(tenant_id, source_id);
-- 🔴 并发计数基准：同一租户当前在跑的任务（PENDING / RUNNING）
CREATE INDEX listing_bulk_collect_task_running ON listing_bulk_collect_task(tenant_id, state)
  WHERE state IN ('PENDING','RUNNING');
-- 🔴 同 host 抓取间隔基准（翻页请求那一半）
CREATE INDEX listing_bulk_collect_task_grab   ON listing_bulk_collect_task(tenant_id, last_grab_host, last_grab_at DESC);

-- ===========================================================================
-- 4) listing_bulk_collect_result · 批量采集结果清单（租户业务表）
-- ===========================================================================
CREATE TABLE listing_bulk_collect_result (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  bulk_task_id      uuid NOT NULL,
  -- BULK 批量链路抓到的 / PLUGIN 浏览器插件上报的
  origin            text NOT NULL CHECK(origin IN ('BULK','PLUGIN')),
  -- 来自第几页（PLUGIN 模式恒为 0）
  page_no           int NOT NULL DEFAULT 0 CHECK(page_no>=0),
  -- 该页内的第几条
  item_index        int NOT NULL DEFAULT 0 CHECK(item_index>=0),
  -- 来源 URL 原文 + 规范化 URL（去重键）
  source_url        text NOT NULL,
  normalized_url    text NOT NULL,
  -- 标题 / 主图 URL（🔴 只记 URL，不下载二进制、不入媒体库）
  title             text NOT NULL DEFAULT '',
  image_url         text NOT NULL DEFAULT '',
  -- 🔴 外部页面原文标价（**不是我方售价**；售价归 05 定价）
  price_amount      numeric(19,4),
  price_currency    varchar(16),
  -- FETCHED 已落成采集草稿 / SKIPPED_DUPLICATE 已存在被跳过
  -- FAILED 抓取失败 / SKIPPED_RATE_LIMITED 被限频跳过
  state             text NOT NULL CHECK(state IN ('FETCHED','SKIPPED_DUPLICATE','FAILED','SKIPPED_RATE_LIMITED')),
  -- 跳过原因（SKIPPED_* 时非空）
  skip_reason       text NOT NULL DEFAULT '',
  -- 失败原因（FAILED 时非空）
  error_message     text NOT NULL DEFAULT '',
  -- 🔴 复用 4.4.1.1：本次采集对应的采集任务与采集草稿
  collect_task_id   uuid,
  draft_id          uuid,
  -- 抓取时刻（插件上报时 = 上报时刻）
  captured_at       timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, bulk_task_id) REFERENCES listing_bulk_collect_task(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, collect_task_id) REFERENCES listing_collect_task(tenant_id, id),
  FOREIGN KEY (tenant_id, draft_id) REFERENCES listing_collect_draft(tenant_id, id)
);
COMMENT ON TABLE listing_bulk_collect_result IS
  '4.4.1.2 · 批量采集结果清单。🔴 不是商品、也不是草稿本体：'
  'draft_id 指向 4.4.1.1 的 listing_collect_draft —— 下游 4.4.1.3 采集箱认领的是那一张。';

-- 结果清单分页（按任务 + 稳定次序）
CREATE INDEX listing_bulk_collect_result_task   ON listing_bulk_collect_result(tenant_id, bulk_task_id, id);
-- 状态筛选
CREATE INDEX listing_bulk_collect_result_state  ON listing_bulk_collect_result(tenant_id, state);
-- 去重查找：同租户 + 规范化 URL
CREATE INDEX listing_bulk_collect_result_url    ON listing_bulk_collect_result(tenant_id, normalized_url);
-- 🔴 同 URL 不重复抓：同一租户同一规范化 URL 在结果清单里**只能有一条 FETCHED**
CREATE UNIQUE INDEX listing_bulk_collect_result_fetched
  ON listing_bulk_collect_result(tenant_id, normalized_url) WHERE state='FETCHED';

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V169 / V170 / V171 / V175 / V183 / V187 / V190 / V202 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_bulk_collect_task','listing_bulk_collect_result'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：按**负责人 / 部门**收敛（与 V202 的 listing_collect_task 同一纪律）
-- ---------------------------------------------------------------------------
-- ALL 全租户可见 / SELF 只看自己跑的 / DEPARTMENT 看本部门的。
-- SELF 语义要求 owner_id 非空 ⇒ 建任务时必须写入 owner_id（app.actor）。
CREATE POLICY data_scope ON listing_bulk_collect_task AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 结果清单的可见性随它所属的批量任务收敛
CREATE POLICY data_scope ON listing_bulk_collect_result AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_bulk_collect_task t WHERE t.tenant_id=listing_bulk_collect_result.tenant_id
                 AND t.id=listing_bulk_collect_result.bulk_task_id
                 AND channel_scope(channel_operation(),t.owner_id,t.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_bulk_collect_task t WHERE t.tenant_id=listing_bulk_collect_result.tenant_id
                 AND t.id=listing_bulk_collect_result.bulk_task_id
                 AND channel_scope(channel_operation(),t.owner_id,t.department_id)));

-- ---------------------------------------------------------------------------
-- 批量采集历史不可删除：它是「这次批量采集发生过」的留痕
-- ---------------------------------------------------------------------------
CREATE FUNCTION refuse_listing_bulk_collect_task_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing bulk collect task is an audit fact and must not be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_bulk_collect_task_no_delete BEFORE DELETE ON listing_bulk_collect_task
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_bulk_collect_task_delete();

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V203 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 两张全局码表：channel_app 可读；租户**不能**改（只授 SELECT）。
GRANT SELECT ON listing_bulk_collect_policy TO channel_app;
GRANT SELECT ON listing_bulk_collect_search TO channel_app;

-- 🔴 刻意**不授 DELETE**（任务是不可删除的留痕；结果清单随任务走）
GRANT SELECT,INSERT,UPDATE ON listing_bulk_collect_task TO channel_app;
GRANT SELECT,INSERT,UPDATE ON listing_bulk_collect_result TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表。
REVOKE ALL ON listing_bulk_collect_task, listing_bulk_collect_result FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：复用 identity 已注册的 channel.operation.*（identity 服务零改动）
-- ---------------------------------------------------------------------------
-- 批量/插件采集属于「平台操作」语义，与 4.1.1.1 列表、4.4.1.1 链接采集、
-- 4.4.2.1 商品认领、4.3.1.2 批量发布同族，因此 identity 服务零改动。
