-- 15.2.1.1 评价列表：汇总各店铺评价（星级 / 内容 / 变体 / 图片 / 平台时间 / 关联订单 /
-- 是否已回复），并给出**店铺评分汇总**（趋势 + 分布），支持筛选与导出。
--
-- 蓝图 15 卷 15.2.1.1 的原话：
--   「职责：汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复、
--     店铺评分汇总，支持筛选（星级、含图/含视频、未回复、时间范围）与导出。
--     输入：评价同步；筛选条件。
--     输出：评价列表 + 评分统计。
--     关系：上游 = 2 同步；下游 = 15.2.1.2 回复、15.2.2.1 索评。
--     **评分趋势比单条评价更重要**：连续下滑要先查是产品问题还是履约问题。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第五个迁移**（前四个：15.4.2.1 知识库 V99 / 15.2.2.1 索评 V220 /
-- 15.2.2.2 索评规则 V228 / 15.1.2.1 买家邮件 V292）。本模块与它们**不共用任何一张表**：
--   * 15.2.2.1 索评（V220）管的是「**要不要向这位买家发起一次索评**」（触达侧），
--     它的 `crm_review_recovery` 只是「收到了评价原文」的**回收桶**，没有店铺评分汇总、
--     没有趋势、没有筛选面；
--   * 15.2.2.2 索评规则（V228）管的是索评**策略配置**；
--   * 15.4.2.1 知识库（V99）管的是「这个格子此刻该给买家答哪一条」（内容侧）。
-- 本模块管的是「**各店铺收到的评价长什么样、整体评分在往哪个方向走**」（**读侧 + 汇总侧**）。
--
-- ⇒ 15.2.2.1 / 15.2.2.2 是本模块的**只读上游**（可引用其既有事实，如索评请求状态），
--   但本文件**不改它们的表结构、规则表与端点**。
--
-- ============================================================ 一、🔴 加粗句「评分趋势比单条评价更重要」
--
-- 这句话的落点**不是**「多存一个平均分」字段，而是一条**可执行的读路径**：
-- 评价必须能**按时间窗口聚合**（趋势）、能**按维度分组**（定位到店铺 / 商品 / 变体），
-- 才能回答「连续下滑到底是产品问题还是履约问题」。
--
-- 因此本文件的建模选择是：
--   * **不建「店铺评分汇总表」**：汇总若是落库的物化结果，就会有**第二份事实**
--     （明细改了汇总没改 ⇒ 「平均分」与「趋势」互相矛盾，而且没人说得清哪份是真的）。
--     汇总一律**由明细现算**（服务层显式 SQL 聚合），单一事实源。
--   * 趋势需要**两个可比窗口**（近窗 vs 前窗），所以明细里必须有**可信的时间轴**：
--     `platform_reviewed_at`（平台上的评价时刻）—— 它是趋势分桶的轴，
--     `registered_at`（登记进本库的时刻）只是「我们什么时候知道这条评价」。
--     🔴 两者**不可互换**：用登记时刻分桶会把「今天集中补录了一批三月的老评价」
--     显示成「今天的评分突然崩了」，那是假下滑，会把客服引到错误的方向上。
--   * 维度要能**定位**到店铺 / 商品 / 变体，所以明细里存 `shop_id` / `product_ref` /
--     `variant_ref`（外加快照名），而不是只存一段自由文本。
--
-- ============================================================ 二、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 本模块要读两样**别的服务的事实**：
--   * **店铺目录**（2.3.1.1）在 **channel** 服务 ⇒ 经 `SignoffDeliveredOrderPort`
--     走**授权 HTTP API** 读（`GET /channel/shops`）。
--   * **关联订单上下文**（订单事实在 **trade** / 履约签收事实在 **channel**）
--     ⇒ 经 `ReviewOrderContextPort` 走**授权 HTTP API** 读，**不直连对方库**、
--     **不建跨库 FK**、**不建跨库视图**。
--
-- ⇒ 本库只存上游的**逻辑引用 + 快照**（shop_id / shop_name / platform_code /
--   sales_order_no），与 15.4.2.1 知识库 V99 第六节、15.2.2.1 索评 V220 第一节
--   同一纪律：快照的作用是**列表页不必回源就能显示**，它**不构成引用完整性**。
--
-- ⚠️ 订单上下文读不到时**必须显式失败 / 显式说明**，
--   **绝不返回空集合冒充「上游没有订单」** —— 客服看到空白会以为这条评价没有关联订单。
--
-- ============================================================ 三、平台评价同步适配器未交付
--
-- `channel/vendor` 目前**只有 OAuth 适配器**（19 个），**没有任何平台的买家评价适配器**。
-- ⇒ 本文件**不假装**同步能力存在：
--   * `source` 的词表里 `PLATFORM_SYNC` **先钉死**（将来接上时不需要改语义），
--     但**当前只允许 `MANUAL_ENTRY` 落库**；
--   * 同步端点由 `UnavailablePlatformReviewPort` 明确答「未接入」，
--     服务层据此返回 **409** 且**一行都不写**。
-- 一个「点了同步就返回成功」的假通道会让运营以为评价已经进来了，
-- 而真相是「一条都没拉到」—— 那正是评价趋势分析失真的源头。
--
-- ============================================================ 四、下游 15.2.1.2 评价回复未交付
--
-- 本表有一列 `replied`（是否已回复），因为它**在蓝图 15.2.1.1 的职责里**
-- （「是否已回复」是列表的一列，也是筛选面之一「未回复」）。
-- 但**模块内的回复不归本模块**：15.2.1.2（评价回复）未交付 ⇒
--   * `replied` 的语义是「**平台侧是否已有卖家公开回复**」，由**登记时运营核对填写**；
--   * 本表**不记录任何回复正文、不记录平台回执、不编造下游 id**；
--   * 能力声明里 `review_reply` 维度 available=false + ownerModule=15.2.1.2。
--
-- ============================================================ 五、一张表
--
--   crm_product_review · 评价本体（= 蓝图输出句里的「评价列表」的事实表）
--
-- 为什么**只有一张表**：
--   * 「是否已回复」是评价的**一个属性**（一列），不是一张表；
--   * 「评分统计 / 趋势 / 分布 / 下滑维度」全部是**派生读**，不是事实；
--   * 「导出」是同一批明细的另一种呈现，不是事实；
--   * 「回复记录」归 15.2.1.2，「同步日志」在同步真正接通前**没有任何行**，
--     建一张永远空的表只会让人以为同步跑过。
--
-- ============================================================ 六、时区、金额与数量
--
--   * 时间一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`
--     （用 `Timestamp.valueOf(LocalDateTime)` 会在东八区静默偏 −28800 秒）。
--   * 本表**没有金额**：评价里不出现金额，平台上的「价格」不是评价的事实。
--   * 星级是小整数（1..5），**不参与任何浮点运算**；平均分由 SQL 的 numeric 聚合给出，
--     服务层用 BigDecimal 承载，**禁浮点**。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 同一口径：角色可能已由运维预置，因此只在缺失时创建，
-- 保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;

-- ============================================================================
-- 1. 表
-- ============================================================================
CREATE TABLE crm_product_review (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  -- 评价号（PR-yyyyMMdd-000001，序列单独授权，见第六节）。
  review_no           varchar(32)  NOT NULL,
  -- 店铺：channel 2.3.1.1 的逻辑引用 + 名称快照（列表页不必回源就能显示）。
  shop_id             uuid         NOT NULL,
  shop_name           varchar(200) NOT NULL,
  platform_code       varchar(32)  NOT NULL,

  -- 来源：MANUAL_ENTRY（当前唯一可落库的来源）/ PLATFORM_SYNC（词表先钉死，未接入）。
  source              varchar(32)  NOT NULL DEFAULT 'MANUAL_ENTRY',
  -- 平台侧评价 id：幂等键。人工登记时可能拿不到 ⇒ 可空；
  -- 平台同步接通后它就是「同一条平台评价只进一次」的凭据。
  external_review_id  varchar(128),

  -- 评价本体
  rating              smallint     NOT NULL,
  title               varchar(300),
  content             text,
  -- 商品与变体（供「定位到维度」用）：ref 是逻辑引用，title/label 是快照。
  product_ref         varchar(128),
  product_title       varchar(300),
  variant_ref         varchar(128),
  variant_label       varchar(200),
  -- 图片与视频
  image_urls          jsonb        NOT NULL DEFAULT '[]'::jsonb,
  video_url           text,
  has_image           boolean      NOT NULL DEFAULT false,
  has_video           boolean      NOT NULL DEFAULT false,

  -- 买家（本仓没有买家档案，15.3.1.1 未交付 ⇒ 只作展示与去重键）
  buyer_ref           varchar(128),
  buyer_name          varchar(200),
  -- 关联订单（trade 的事实，本库只存逻辑引用）
  sales_order_no      varchar(64),

  -- 是否已回复：**平台侧**是否已有卖家公开回复（见文件头第四节）
  replied             boolean      NOT NULL DEFAULT false,
  replied_at          timestamptz,

  -- 平台上的评价时刻（趋势分桶的轴）/ 登记进本库的时刻
  platform_reviewed_at timestamptz NOT NULL,
  registered_at        timestamptz NOT NULL,

  note                varchar(500),
  created_by          uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),

  -- 星级只可能是 1..5（平台评价的星级档位）。
  CONSTRAINT crm_product_review_rating_ck CHECK (rating BETWEEN 1 AND 5),
  -- 来源词表（PLATFORM_SYNC 先钉死，当前不接受它落库 —— 由服务层拒绝，DB 侧只钉词表）。
  CONSTRAINT crm_product_review_source_ck CHECK (source IN ('MANUAL_ENTRY','PLATFORM_SYNC')),
  -- 🔴 「含图」必须真的有图：否则「含图」这个筛选面会命中一条其实没图的评价。
  CONSTRAINT crm_product_review_has_image_ck
    CHECK (NOT has_image OR jsonb_array_length(image_urls) > 0),
  -- 🔴 「含视频」必须真的有视频地址（同一个道理）。
  CONSTRAINT crm_product_review_has_video_ck
    CHECK (NOT has_video OR (video_url IS NOT NULL AND length(btrim(video_url)) > 0)),
  -- 「已回复」必须带回复时刻：否则「已回复但不知道什么时候」永远查不清。
  CONSTRAINT crm_product_review_replied_ck
    CHECK (NOT replied OR replied_at IS NOT NULL)
);

-- ============================================================================
-- 2. 索引
-- ============================================================================
-- 评价号在租户内唯一（导出与引用的凭据）。
CREATE UNIQUE INDEX crm_product_review_no_uniq
  ON crm_product_review(tenant_id, review_no);

-- 🔴 平台评价幂等：同一租户 + 同一平台 + 同一店铺 + 同一平台评价 id 只允许一行。
--   人工登记拿不到平台 id 时 external_review_id 为 NULL ⇒ 该行不参与本约束
--   （PostgreSQL 的唯一索引不把 NULL 视为重复），这是**刻意**的：
--   平台同步接通后它就是「同一条平台评价只进一次」的凭据。
CREATE UNIQUE INDEX crm_product_review_external_uniq
  ON crm_product_review(tenant_id, platform_code, shop_id, external_review_id)
  WHERE external_review_id IS NOT NULL;

-- 列表默认排序：平台评价时刻倒序（趋势与「最近发生了什么」同一根轴）。
CREATE INDEX crm_product_review_list_idx
  ON crm_product_review(tenant_id, platform_reviewed_at DESC, id);

-- 「未回复」筛选面（蓝图点名的筛选之一）。
CREATE INDEX crm_product_review_unreplied_idx
  ON crm_product_review(tenant_id, replied, platform_reviewed_at DESC);

-- 按店铺看评分趋势。
CREATE INDEX crm_product_review_shop_idx
  ON crm_product_review(tenant_id, shop_id, platform_reviewed_at DESC);

-- 「定位到维度」用：按商品 / 变体聚合。
CREATE INDEX crm_product_review_product_idx
  ON crm_product_review(tenant_id, product_ref);
CREATE INDEX crm_product_review_variant_idx
  ON crm_product_review(tenant_id, variant_ref);

-- 按订单回查（评价 → 订单上下文）。
CREATE INDEX crm_product_review_order_idx
  ON crm_product_review(tenant_id, sales_order_no);

-- ============================================================================
-- 3. 评价号序列（单独授权，见第六节）
-- ============================================================================
CREATE SEQUENCE crm_product_review_no_seq START 1;

-- ============================================================================
-- 4. 触发器：来源身份列建后不可改
-- ============================================================================
-- 为什么：这几列回答的是「**这是哪一家平台的哪一条评价**」。
-- 改了它们，「同一条平台评价只进一次」的幂等键就换了人，
-- 「这条评价属于哪家店」在历史里就会出现两个答案 —— 而店铺维度的趋势正是本模块的产出。
-- ⚠️ 触发器里**不用外键**：本批 batch-37 有车道在迁移里给既有表建 FOREIGN KEY，
-- 结果打破了同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a
-- foreign key constraint）。本文件**不给任何既有表建外键**，本文件自己也不建。
--
-- 🔴 **刻意不冻结 rating / content**：登记错了就是错了，运营有权改正；
--   被冻结的是「**这条评价是谁家的**」，不是「这条评价说了什么」。
--   （改正会改趋势，所以 corrected 之后趋势必须重新算 —— 趋势本来就是现算的。）
CREATE FUNCTION guard_crm_product_review_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '评价的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION '评价的店铺建后不可改：改它会让「这家店最近评分在往哪走」在历史里出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION '评价的平台建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.source IS DISTINCT FROM OLD.source THEN
    RAISE EXCEPTION '评价的来源建后不可改：改它会让「这条评价是同步来的还是人工登记的」无从查证' USING ERRCODE='23514';
  END IF;
  IF NEW.external_review_id IS DISTINCT FROM OLD.external_review_id THEN
    RAISE EXCEPTION '评价的平台评价 id 建后不可改：改它会让「同一条平台评价只进一次」这条幂等键换人' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_reviewed_at IS DISTINCT FROM OLD.platform_reviewed_at THEN
    RAISE EXCEPTION '评价的平台时刻建后不可改：趋势分桶的轴就是它，改它会让历史趋势在事后被改写' USING ERRCODE='23514';
  END IF;
  IF NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
    RAISE EXCEPTION '评价的登记时刻建后不可改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_product_review_provenance_trg
  BEFORE UPDATE ON crm_product_review
  FOR EACH ROW EXECUTE FUNCTION guard_crm_product_review_provenance();

-- ============================================================================
-- 5. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
ALTER TABLE crm_product_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_product_review FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_product_review
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_product_review TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_product_review TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条评价就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_product_review_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_product_review_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 6. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.2.1.2 评价回复（未交付）**：本模块只提供「是否已回复」这一个**只读属性**
--     与「未回复」这一个**筛选面**，**不记录回复正文、不记录平台回执、不编造下游 id**。
--     能力声明里 `review_reply` 维度 available=false + ownerModule=15.2.1.2。
--   * **15.2.1.3 差评处理（未交付）**：本模块**不建**差评标记 / 申诉 / 解决率。
--     低星评价在本模块里只是**星级分布里的一档**，不会被自动贴上「产品问题 / 物流问题」的标签
--     —— 那些标签要有证据，而证据属于 15.2.1.3。
--   * **15.2.2.1 索评 / 15.2.2.2 索评规则（已交付）**：本模块**只读**引用其既有事实，
--     **不改**它们的表结构、规则表与端点。
--   * **平台评价同步（未接入）**：见文件头第三节。
