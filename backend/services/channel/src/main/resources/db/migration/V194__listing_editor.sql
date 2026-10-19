-- ===========================================================================
-- 4.2.1.1 刊登编辑器（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.2.1.1 刊登编辑器
--   - 职责：单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、
--           A+ 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。
--   - 输入：源商品（03 SKU/SPU）、目标店铺与站点、平台字段要求（02 能力矩阵）；用户编辑内容；已填内容的草稿。
--   - 输出：内容草稿（按平台 + 站点 + 语言分别保存）；提交发布或保存模板的动作；校验结果。
--   - 关系：上游 = 03 商品主数据、03 媒体与文案、02 平台字段要求；下游 = 4.3.1.1 发布。
--           **表单字段由平台要求驱动生成**，不是固定一套表单套所有平台——平台能力矩阵变化时表单自动变化。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 已交付并在本迁移里被**真实读取**的上游：
--   * channel V1 `platform`（全局码表）—— 2.1.1.1 平台目录 / **2.1.1.2 平台能力矩阵**
--     （`platform.capabilities` jsonb）。**表单字段集与"该平台支不支持这个字段"就是从它推出来的。**
--   * channel V1 `marketplace`（全局码表）—— 2.1.1.3 站点与国家参数（站点默认语言、站点级差异）。
--   * channel V1 `shop` / `shop_marketplace` —— 02 店铺与启用站点（目标店铺 + 目标站点）。
--   * channel V171 `listing` / `listing_variant` —— 4.1.1.1 平台商品与变体（线上内容与变体维度）。
--   * channel V175 `listing_content_snapshot` —— 4.1.1.2 平台当前内容快照（"线上现在长什么样"那一列）。
--
-- 跨服务（只经授权 HTTP API，不跨库 SQL / FK / 视图）：
--   * 03 商品主数据（catalog `GET /catalog/skus/{id}`）—— 源商品与属性预填。
--   * 03 媒体库（catalog `GET /catalog/media`）—— 图片集字段的素材来源。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块最重要的一件事：表单字段集**由平台要求推导**，不是写死的一套表单
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是整个模块的**核心不变量**，它的物理落法是**一张注册表 + 一段推导**，不是 if/else：
--
--   * `platform_listing_field_requirement`（本迁移新建，**全局只读码表**，与 V1 的
--     `platform` / `marketplace` 同一类数据）逐行登记「哪个平台（+ 哪个站点 / 哪个类目）
--     有哪些字段、必填与否、长度与条数上限」。
--   * 服务层从这张表**解出**该平台 + 站点 + 类目下要渲染哪些字段，再叠上
--     `platform.capabilities`（2.1.1.2 已交付）判"该平台支不支持这个字段"。
--   * **代码里没有一处 `if (platform == AMAZON)`** —— 平台改版 = 改这张表 + 改能力矩阵，
--     表单自动跟着变。
--
-- 🔴 「不知道」是一个状态，不是一个 0：
--   * 能力矩阵**没有声明**某个能力 ⇒ 该字段的 `capabilityState = UNKNOWN`
--     （**不是**"不支持"、**也不是**"没有约束"），并带 ownerModule 指回落点。
--   * 字段的细则要求在别的模块（类目属性在 4.2.3.1、平台合规声明在 02）⇒
--     `constraintState = DELEGATED` + ownerModule，**不假装本模块知道**。
--   * 平台在能力矩阵里根本没登记 ⇒ `requirementsState = UNKNOWN` + ownerModule。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 草稿态与线上态仍然是两个不同的存储（沿用 4.2.1.2 的手法）
-- ---------------------------------------------------------------------------
--   * 线上态 = V171 的 `listing`（本迁移**只加两列**，见第三节）。
--   * 草稿态 = 本迁移新建的 `listing_editor_draft`。
--   * 提交审核 = 本迁移新建的 `listing_editor_submission`（**只是入队**）。
--
-- 本迁移同样给 `listing` 加一条 BEFORE UPDATE 触发器 `guard_listing_editor_live_content`：
-- 事务里打开了 `app.draft_only` 时，对 `listing.bullets` / `listing.images` 的改动由 DB 直接拒。
-- （V177 的 `guard_listing_live_content` 已经在管 title / category_path / platform_category_id，
-- 两条触发器合起来覆盖**全部四个**线上内容列。）
--
-- ---------------------------------------------------------------------------
-- 三、🔴 对 `listing` 的 ALTER —— 本批最高风险的动作，按"已知互相破坏形状"的规则来
-- ---------------------------------------------------------------------------
-- V171 的 `listing` 只有 title / category_path / platform_category_id 三个内容列，
-- **没有卖点（bullets）也没有图片（images）** —— 4.2.1.2 批量编辑的台账证据里明确写了
-- 「那是 4.2.1.1 刊登编辑器的内容域而它尚未交付」，所以这两列**由本迁移加**。
--
-- 加列规则（照 AGENTS.md 与本仓"已知互相破坏形状"清单）：
--   * **ADD COLUMN 一律给 DEFAULT**：`bullets` 默认 `'{}'::text[]`、`images` 默认 `'[]'::jsonb`。
--     ⇒ 4.2.1.2 已交付的批量编辑 INSERT 路径、以及本批其它车道任何"不写这两列"的 INSERT
--     都不会违约（老 INSERT 缺这两列时取默认值，NOT NULL 也不会被违反）。
--     **不 SET NOT NULL、不加 BEFORE INSERT 触发器** —— 因为 DEFAULT 已经在 INSERT 路径上兜底，
--     再叠一层触发器只会多一处跨车道耦合。
--   * **新增 CHECK 只加结构性的**（jsonb 必须是数组），**不放宽也不收紧旧值域**：
--     新列上的 CHECK 对旧行（默认值）恒成立。
--   * 触发器**只加不改**：V177 的 `guard_listing_live_content` 一字未动，
--     本迁移新建一条平行的 `guard_listing_editor_live_content` 管新增的两列。
--
-- 为什么只加 bullets / images 两个列，而不是把九个字段全加上：
--   * 4.1.1.2 的 `listing_content_snapshot`（V175）**已经**完整承载了每个 listing 每个语言的
--     线上内容（title / bullet_points / description / images / video_url / a_plus_modules /
--     category_attributes / compliance_statements）。再加九个影子列 = 第二个真相来源，
--     两份数字迟早对不上，而没人说得清该信哪个。
--   * `listing` 这一层的定位是**列表级、语言无关**的内容（与既有的 `listing.title` 同口径），
--     4.2.1.2 的批量编辑正是拿它当"改前值"基线。bullets / images 补齐后，
--     批量编辑声明过的「线上基线未接入」不再是因为**列不存在**。
--     （4.2.1.2 的能力声明文本在它自己的文件里，本迁移不改别人的文件。）
--
-- ---------------------------------------------------------------------------
-- 四、🔴 内容按平台 + 站点 + 语言分别保存（DB 层的唯一约束直接表达）
-- ---------------------------------------------------------------------------
-- `listing_editor_draft` 上有两条**部分唯一索引**：
--   * 关联了线上 listing 的草稿：UNIQUE(tenant_id, shop_id, marketplace_id, locale)
--   * 尚未刊登（只有源 SKU）的草稿：UNIQUE(tenant_id, shop_id, marketplace_id, locale, source_sku_id)
-- 平台维度由 shop → platform_id 唯一确定，另加一条触发器钉住"草稿的平台必须等于店铺的平台"，
-- 于是"同一商品在不同平台 / 不同站点的内容互不污染"在 DB 层成立，**不是**靠应用层自觉。
--
-- ---------------------------------------------------------------------------
-- 五、提交审核 = **入队**，不是发布
-- ---------------------------------------------------------------------------
-- 4.3.1.1 一键刊登在集成头**尚未交付**，所以本模块的"提交审核"只做一件事：
-- 把这一份内容**冻结成快照**放进 `listing_editor_submission`（state='QUEUED'）。
-- 派发到平台是 4.3.1.1 的事 —— 这里没有、也不该有任何"直接发给平台"的端点。
-- 队列是**幂等**的：同一个 idempotency_key 重复提交返回同一条入队记录，不产生第二条。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本模块**无任何金额列**：刊登编辑器的职责句里没有改价（售价归 05 定价）。
-- 存在的数量列只有 `min_items` / `max_items` 这类**条数上限**（整数计数，不是业务数量），
-- 以及 `listing.platform_stock_qty` 那种本就属于 4.1.1.1 的 NUMERIC(19,6)。
--
-- ---------------------------------------------------------------------------
-- 七、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。

-- ===========================================================================
-- 一、平台字段要求注册表（全局只读码表，**不带 tenant_id**）
-- ===========================================================================
-- 🔴 为什么是全局码表而不是租户业务表：
--   * 这张表登记的是**平台侧已公布的刊登字段要求**（"Amazon 标题 200 字"），
--     它的真相来源是平台，不是某个租户 —— 与 V1 的 `platform` / `marketplace` 同一类数据。
--   * 租户级的"批量编辑模板上限"在 V177 的 `listing_bulk_template`（租户表）里，
--     两者不是一回事，不合并。
--   * 因此本表**不参与租户隔离**（与 platform / marketplace 同口径），运行角色只授 SELECT。
--   * 内容草稿（`listing_editor_draft`）才是租户业务表，走完整 RLS。
CREATE TABLE platform_listing_field_requirement (
  id              uuid PRIMARY KEY,
  platform_id     uuid NOT NULL REFERENCES platform(id),
  -- 🔴 站点维度：NULL = 该平台全部站点。站点级差异是真实的
  --    （Amazon 美国站标题 200 字、日本站 100 字），所以站点必须是**可空维度**而不是另一个表。
  marketplace_id  uuid REFERENCES marketplace(id),
  -- 类目维度：NULL = 该平台全部类目。类目级要求由 4.2.3.1 平台类目映射负责
  --    （在 catalog 侧，本服务不跨库读），所以这里的类目维度先留空但**留了位置**。
  category_path   varchar(500),
  field_key       text NOT NULL
                  CHECK(field_key IN ('TITLE','BULLETS','DESCRIPTION','VARIANT_DIMENSIONS','IMAGES',
                                      'VIDEO','A_PLUS','CATEGORY_ATTRIBUTES','COMPLIANCE_STATEMENTS')),
  -- 值的形状：决定校验器怎么读这个值（泛化到 8 种形状，不为每个字段写一套解析）
  value_kind      text NOT NULL
                  CHECK(value_kind IN ('TEXT','LONG_TEXT','TEXT_LIST','IMAGE_LIST','VIDEO_REF',
                                       'MODULE_LIST','ATTRIBUTE_MAP','STATEMENT_MAP')),
  -- 必填性：REQUIRED / RECOMMENDED / OPTIONAL / UNSUPPORTED（平台明确不支持这一栏）
  requirement     text NOT NULL
                  CHECK(requirement IN ('REQUIRED','RECOMMENDED','OPTIONAL','UNSUPPORTED')),
  min_length      integer CHECK(min_length IS NULL OR min_length >= 0),
  max_length      integer CHECK(max_length IS NULL OR max_length > 0),
  min_items       integer CHECK(min_items IS NULL OR min_items >= 0),
  max_items       integer CHECK(max_items IS NULL OR max_items > 0),
  -- 🔴 能力门控：这一栏要求平台在 `platform.capabilities`（2.1.1.2）里声明某个能力。
  --    取不到（能力矩阵没有这个键）= **UNKNOWN**，不是 false —— 详见文件头第一节。
  requires_capability text,
  -- 是否可由运营编辑：变体维度归 4.1.2.1 映射域，编辑器只展示不改。
  editable        boolean NOT NULL DEFAULT true,
  -- 取值来源：OPERATOR = 运营手填；LISTING_VARIANT = 由 listing_variant 派生
  value_source    text NOT NULL DEFAULT 'OPERATOR'
                  CHECK(value_source IN ('OPERATOR','LISTING_VARIANT')),
  -- 🔴 细则要求归属别的模块时写在这里 ⇒ 约束状态是 DELEGATED（**不假装本模块知道**）
  constraint_owner_module text,
  -- 平台明确不支持时的原因（前端原样展示）
  note            varchar(500) NOT NULL DEFAULT '',
  enabled         boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  -- 🔴 同一平台 + 站点 + 类目 + 字段只登记一次（NULLS NOT DISTINCT 让"全部站点 / 全部类目"也算同一行）
  UNIQUE NULLS NOT DISTINCT (platform_id, marketplace_id, category_path, field_key),
  -- 上限自洽：条数上限不得小于下限；长度上限不得小于下限
  CHECK(min_items IS NULL OR max_items IS NULL OR max_items >= min_items),
  CHECK(min_length IS NULL OR max_length IS NULL OR max_length >= min_length),
  -- 🔴 声称"平台不支持"的一栏不许再带必填/上限 —— 自相矛盾直接拒
  CHECK(requirement <> 'UNSUPPORTED' OR (min_length IS NULL AND max_length IS NULL
                                         AND min_items IS NULL AND max_items IS NULL
                                         AND requires_capability IS NULL))
);
COMMENT ON TABLE platform_listing_field_requirement IS
  '4.2.1.1 · 🔴 平台字段要求注册表（全局只读码表）：**表单字段集由这张表推导**，'
  '代码里没有平台分支；平台改版改这张表，表单自动跟着变。';

CREATE INDEX platform_listing_field_requirement_lookup
  ON platform_listing_field_requirement(platform_id, marketplace_id, category_path);

-- ---------------------------------------------------------------------------
-- 一之二、注册表种子：逐平台的字段要求
-- ---------------------------------------------------------------------------
-- 口径说明（**不是**在代码里写死平台分支，而是把平台公布的规则**登记成数据**）：
--   * 站点级差异用 marketplace_code 单独登记（Amazon 日本站标题 100 字、欧洲站 150 字），
--     推导时**就近覆盖**（站点+类目 > 站点 > 类目 > 平台）。
--   * requires_capability 引用的键若不在 `platform.capabilities` 里 ⇒ 能力状态 UNKNOWN。
--   * 类目属性（4.2.3.1，在 catalog 侧）与平台合规声明细则（02）⇒ constraint_owner_module，
--     本模块**不跨库读**它们，约束状态是 DELEGATED。
INSERT INTO platform_listing_field_requirement
  (id, platform_id, marketplace_id, category_path, field_key, value_kind, requirement,
   min_length, max_length, min_items, max_items, requires_capability, editable,
   value_source, constraint_owner_module, note)
SELECT md5(p.code || '|' || coalesce(m.code, '*') || '|' || v.field_key)::uuid,
       p.id,
       m.id,
       NULL,
       v.field_key, v.value_kind, v.requirement,
       v.min_length, v.max_length, v.min_items, v.max_items,
       v.requires_capability, v.editable, v.value_source, v.constraint_owner_module, v.note
FROM (VALUES
  -- ---------------- AMAZON ----------------
  ('AMAZON',NULL,'TITLE','TEXT','REQUIRED',1,200,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('AMAZON',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,5,NULL,true,'OPERATOR',NULL,''),
  ('AMAZON',NULL,'DESCRIPTION','LONG_TEXT','REQUIRED',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('AMAZON',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,9,NULL,true,'OPERATOR',NULL,''),
  ('AMAZON',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,
   '能力矩阵未声明 video_support 时，平台是否支持视频为未知'),
  ('AMAZON',NULL,'A_PLUS','MODULE_LIST','OPTIONAL',NULL,NULL,NULL,5,'a_plus_content',true,'OPERATOR',NULL,
   '能力矩阵未声明 a_plus_content 时，平台是否支持 A+ 模块为未知'),
  ('AMAZON',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',
   '变体维度取值归 4.1.2.1 映射域，编辑器只展示'),
  ('AMAZON',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',
   '平台类目必填属性清单在 4.2.3.1（catalog 侧），本服务不跨库读'),
  ('AMAZON',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',
   '平台要求的合规声明项由 02 能力矩阵裁决'),
  -- Amazon 站点级差异（就近覆盖）
  ('AMAZON','JP','TITLE','TEXT','REQUIRED',1,100,NULL,NULL,NULL,true,'OPERATOR',NULL,'日本站标题上限更短'),
  ('AMAZON','DE','TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,'欧洲站标题上限'),
  ('AMAZON','FR','TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,'欧洲站标题上限'),
  ('AMAZON','IT','TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,'欧洲站标题上限'),
  ('AMAZON','ES','TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,'欧洲站标题上限'),
  -- ---------------- SHOPIFY ----------------
  ('SHOPIFY',NULL,'TITLE','TEXT','REQUIRED',1,255,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('SHOPIFY',NULL,'DESCRIPTION','LONG_TEXT','REQUIRED',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('SHOPIFY',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,10,NULL,true,'OPERATOR',NULL,''),
  ('SHOPIFY',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('SHOPIFY',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('SHOPIFY',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('SHOPIFY',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  ('SHOPIFY',NULL,'A_PLUS','MODULE_LIST','UNSUPPORTED',NULL,NULL,NULL,NULL,NULL,false,'OPERATOR',NULL,
   'Shopify 用页面区块代替 A+ 模块'),
  -- ---------------- EBAY ----------------
  ('EBAY',NULL,'TITLE','TEXT','REQUIRED',1,80,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('EBAY',NULL,'DESCRIPTION','LONG_TEXT','REQUIRED',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('EBAY',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,12,NULL,true,'OPERATOR',NULL,''),
  ('EBAY',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('EBAY',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('EBAY',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('EBAY',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- TIKTOK_SHOP ----------------
  ('TIKTOK_SHOP',NULL,'TITLE','TEXT','REQUIRED',1,60,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('TIKTOK_SHOP',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,3,NULL,true,'OPERATOR',NULL,''),
  ('TIKTOK_SHOP',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('TIKTOK_SHOP',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,9,NULL,true,'OPERATOR',NULL,''),
  ('TIKTOK_SHOP',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('TIKTOK_SHOP',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('TIKTOK_SHOP',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('TIKTOK_SHOP',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- TEMU ----------------
  ('TEMU',NULL,'TITLE','TEXT','REQUIRED',1,60,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('TEMU',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,5,NULL,true,'OPERATOR',NULL,''),
  ('TEMU',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('TEMU',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,9,NULL,true,'OPERATOR',NULL,''),
  ('TEMU',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('TEMU',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('TEMU',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- WALMART ----------------
  ('WALMART',NULL,'TITLE','TEXT','REQUIRED',1,75,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('WALMART',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,10,NULL,true,'OPERATOR',NULL,''),
  ('WALMART',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('WALMART',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,8,NULL,true,'OPERATOR',NULL,''),
  ('WALMART',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('WALMART',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('WALMART',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('WALMART',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- SHOPEE ----------------
  ('SHOPEE',NULL,'TITLE','TEXT','REQUIRED',1,120,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('SHOPEE',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,10,NULL,true,'OPERATOR',NULL,''),
  ('SHOPEE',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('SHOPEE',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,9,NULL,true,'OPERATOR',NULL,''),
  ('SHOPEE',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('SHOPEE',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('SHOPEE',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('SHOPEE',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- LAZADA ----------------
  ('LAZADA',NULL,'TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('LAZADA',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,10,NULL,true,'OPERATOR',NULL,''),
  ('LAZADA',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('LAZADA',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,8,NULL,true,'OPERATOR',NULL,''),
  ('LAZADA',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('LAZADA',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('LAZADA',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  ('LAZADA',NULL,'COMPLIANCE_STATEMENTS','STATEMENT_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','2.1.1.2',''),
  -- ---------------- MERCADO_LIBRE（接入状态 PLANNED） ----------------
  ('MERCADO_LIBRE',NULL,'TITLE','TEXT','REQUIRED',1,60,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('MERCADO_LIBRE',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('MERCADO_LIBRE',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,10,NULL,true,'OPERATOR',NULL,''),
  ('MERCADO_LIBRE',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('MERCADO_LIBRE',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  -- ---------------- ALIEXPRESS（接入状态 PLANNED） ----------------
  ('ALIEXPRESS',NULL,'TITLE','TEXT','REQUIRED',1,128,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('ALIEXPRESS',NULL,'BULLETS','TEXT_LIST','OPTIONAL',NULL,255,NULL,10,NULL,true,'OPERATOR',NULL,''),
  ('ALIEXPRESS',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('ALIEXPRESS',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,6,NULL,true,'OPERATOR',NULL,''),
  ('ALIEXPRESS',NULL,'VIDEO','VIDEO_REF','OPTIONAL',NULL,NULL,NULL,1,'video_support',true,'OPERATOR',NULL,''),
  ('ALIEXPRESS',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('ALIEXPRESS',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  -- ---------------- ETSY（接入状态 PLANNED） ----------------
  ('ETSY',NULL,'TITLE','TEXT','REQUIRED',1,140,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('ETSY',NULL,'DESCRIPTION','LONG_TEXT','REQUIRED',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('ETSY',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,10,NULL,true,'OPERATOR',NULL,''),
  ('ETSY',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('ETSY',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1',''),
  -- ---------------- WAYFAIR（接入状态 PLANNED） ----------------
  ('WAYFAIR',NULL,'TITLE','TEXT','REQUIRED',1,150,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('WAYFAIR',NULL,'DESCRIPTION','LONG_TEXT','OPTIONAL',1,20000,NULL,NULL,NULL,true,'OPERATOR',NULL,''),
  ('WAYFAIR',NULL,'IMAGES','IMAGE_LIST','REQUIRED',NULL,NULL,1,12,NULL,true,'OPERATOR',NULL,''),
  ('WAYFAIR',NULL,'VARIANT_DIMENSIONS','ATTRIBUTE_MAP','OPTIONAL',NULL,NULL,NULL,NULL,NULL,false,'LISTING_VARIANT','4.1.2.1',''),
  ('WAYFAIR',NULL,'CATEGORY_ATTRIBUTES','ATTRIBUTE_MAP','REQUIRED',NULL,NULL,NULL,NULL,NULL,true,'OPERATOR','4.2.3.1','')
) AS v(platform_code, marketplace_code, field_key, value_kind, requirement,
       min_length, max_length, min_items, max_items, requires_capability, editable,
       value_source, constraint_owner_module, note)
JOIN platform p ON p.code = v.platform_code
LEFT JOIN marketplace m ON m.platform_id = p.id AND m.code = v.marketplace_code;

-- ===========================================================================
-- 二、🔴 对线上 `listing` 的 ALTER：补齐卖点与图片两列
-- ===========================================================================
-- 🔴 ADD COLUMN **一律给 DEFAULT**：老 INSERT（4.2.1.2 的批量编辑路径、本批其它车道的
--    INSERT）不写这两列时取默认值，不会因为 NOT NULL 而违约。
--    同时**不 SET NOT NULL、不加 BEFORE INSERT 触发器** —— DEFAULT 已经在 INSERT 路径上兜底。
ALTER TABLE listing ADD COLUMN bullets text[] NOT NULL DEFAULT '{}'::text[];
ALTER TABLE listing ADD COLUMN images jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN listing.bullets IS
  '4.2.1.1 · 线上卖点（列表级、语言无关，与 listing.title 同口径）。'
  '逐语言的线上内容真相在 4.1.1.2 的 listing_content_snapshot。';
COMMENT ON COLUMN listing.images IS
  '4.2.1.1 · 线上图片集（列表级、语言无关）。逐语言的线上内容真相在 4.1.1.2 的 listing_content_snapshot。';
-- 新增 CHECK 只加结构性的：图片集必须是 JSON 数组。旧行（默认值 '[]'）恒成立，
-- **不放宽也不收紧**任何旧值域。
ALTER TABLE listing ADD CONSTRAINT listing_images_is_array
  CHECK(jsonb_typeof(images)='array');

-- ===========================================================================
-- 三、listing_editor_draft · 🔴 编辑器草稿（与线上 listing 物理分离）
-- ===========================================================================
-- 「输出：内容草稿（按平台 + 站点 + 语言分别保存）」的落地表。
-- **本模块只往这张表写，绝不写 listing。**
CREATE TABLE listing_editor_draft (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 目标店铺（02 店铺）+ 目标站点（02 站点）。平台由店铺唯一确定，另存一份并由触发器钉住一致。
  shop_id       uuid NOT NULL,
  platform_id   uuid NOT NULL REFERENCES platform(id),
  marketplace_id uuid NOT NULL REFERENCES marketplace(id),
  -- 语言：内容按平台 + 站点 + 语言分别保存
  locale        varchar(16) NOT NULL,
  -- 源商品（03 SKU，**跨服务逻辑 ID**，不建 FK）与关联的线上 listing（可空）
  source_sku_id uuid,
  listing_id    uuid,
  -- 目标平台类目路径（平台侧原文）。**类目维度参与字段要求的就近覆盖**，
  -- 与 listing.category_path 同口径。
  category_path varchar(500) NOT NULL DEFAULT '',
  -- 🔴 内容值：按 field_key 存。**字段集由平台要求决定**（见 platform_listing_field_requirement），
  --    触发器会拒掉"该平台没有的这一栏"。
  field_values  jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- DRAFT 编辑中 / SUBMITTED 已入队待派发 / DISCARDED 已废弃
  state         text NOT NULL DEFAULT 'DRAFT'
                CHECK(state IN ('DRAFT','SUBMITTED','DISCARDED')),
  -- 草稿是从哪个线上版本派生的（关联 listing 时）：线上在之后被改过就能检出草稿已过期
  base_version  bigint NOT NULL DEFAULT 0,
  -- 最近一次校验结果（证据）：字段级违规 + 是否有阻断项
  validation_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  validation_passed boolean,
  validated_at  timestamptz,
  submitted_at  timestamptz,
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(locale)<>''),
  CHECK(jsonb_typeof(field_values)='object'),
  -- 总得有一个主体：要么关联一条线上 listing，要么指定一个源 SKU
  CHECK(listing_id IS NOT NULL OR source_sku_id IS NOT NULL),
  -- 声称校验通过就必须有校验快照与时刻，两者不许各说各话
  CHECK(validation_passed IS NULL OR (validation_snapshot IS NOT NULL AND validated_at IS NOT NULL)),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_editor_draft IS
  '4.2.1.1 · 🔴 刊登编辑器草稿（按平台 + 站点 + 语言分别保存）。与线上 listing 物理分离：'
  '本模块只写这张表，发布是 4.3.1.1 的事。';

-- 🔴「内容按平台 + 站点 + 语言分别保存」在 DB 层的表达：两条**部分唯一索引**
--   （不用整表 UNIQUE：整表 UNIQUE 会让"同一 listing 换个语言再存一份"直接插不进去）。
CREATE UNIQUE INDEX listing_editor_draft_listing_subject
  ON listing_editor_draft(tenant_id, shop_id, marketplace_id, locale)
  WHERE listing_id IS NOT NULL;
CREATE UNIQUE INDEX listing_editor_draft_sku_subject
  ON listing_editor_draft(tenant_id, shop_id, marketplace_id, locale, source_sku_id)
  WHERE listing_id IS NULL AND source_sku_id IS NOT NULL;
CREATE INDEX listing_editor_draft_recent
  ON listing_editor_draft(tenant_id, updated_at DESC, id);
CREATE INDEX listing_editor_draft_listing
  ON listing_editor_draft(tenant_id, listing_id);
CREATE INDEX listing_editor_draft_shop
  ON listing_editor_draft(tenant_id, shop_id, marketplace_id);

-- ===========================================================================
-- 四、listing_editor_submission · 提交审核 = **入队**（不是发布）
-- ===========================================================================
-- 4.3.1.1 一键刊登未交付 ⇒ 本表只到"入队"为止：冻结内容快照 + 校验证据 + 目标三元组。
CREATE TABLE listing_editor_submission (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  draft_id      uuid NOT NULL,
  platform_id   uuid NOT NULL REFERENCES platform(id),
  shop_id       uuid NOT NULL,
  marketplace_id uuid NOT NULL REFERENCES marketplace(id),
  locale        varchar(16) NOT NULL,
  -- QUEUED 已入队待派发 / DISPATCHED 已派发（4.3.1.1 交付后由它推进）/
  -- FAILED 派发失败 / CANCELLED 人工撤销
  state         text NOT NULL DEFAULT 'QUEUED'
                CHECK(state IN ('QUEUED','DISPATCHED','FAILED','CANCELLED')),
  -- 🔴 入队那一刻的内容快照（之后草稿再改也不影响这条入队记录）
  content_snapshot jsonb NOT NULL,
  -- 🔴 入队那一刻的校验证据（"当时我们凭什么认为它可以发"）
  validation_snapshot jsonb NOT NULL,
  -- 幂等键：重复提交同一次内容不产生第二条入队记录
  idempotency_key varchar(128) NOT NULL,
  queued_at     timestamptz NOT NULL DEFAULT now(),
  dispatched_at timestamptz,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  CHECK(btrim(locale)<>''),
  CHECK(jsonb_typeof(content_snapshot)='object'),
  CHECK(jsonb_typeof(validation_snapshot)='object'),
  CHECK(state<>'DISPATCHED' OR dispatched_at IS NOT NULL),
  FOREIGN KEY(tenant_id, draft_id) REFERENCES listing_editor_draft(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE listing_editor_submission IS
  '4.2.1.1 · 提交审核 = **入队**。真正发给平台是 4.3.1.1 一键刊登（未交付），'
  '本表只冻结内容与校验证据。';
-- 一份草稿同时只允许一条在队的入队记录（重复提交返回同一条，不产生第二条）
CREATE UNIQUE INDEX listing_editor_submission_active
  ON listing_editor_submission(tenant_id, draft_id) WHERE state='QUEUED';
CREATE INDEX listing_editor_submission_recent
  ON listing_editor_submission(tenant_id, queued_at DESC, id);

-- ===========================================================================
-- 五、触发器
-- ===========================================================================
-- (a) 🔴🔴 核心不变量：编辑器事务期间，线上 listing 的内容列一律不可写。
--     与 V177 的 `guard_listing_live_content` 平行，**只管本迁移新增的两列**
--     （title / category_path / platform_category_id 由 V177 那条管，本迁移一字未改它）。
CREATE FUNCTION guard_listing_editor_live_content() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF coalesce(nullif(current_setting('app.draft_only',true),'')::boolean,false) IS NOT TRUE THEN
    RETURN NEW;
  END IF;
  IF NEW.bullets IS DISTINCT FROM OLD.bullets OR NEW.images IS DISTINCT FROM OLD.images THEN
    RAISE EXCEPTION
      'Listing editor must never write live listing content (bullets/images); drafts live in listing_editor_draft and publishing goes through 4.3.1.1'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_editor_live_content BEFORE UPDATE OF bullets, images ON listing
  FOR EACH ROW EXECUTE FUNCTION guard_listing_editor_live_content();

-- (b) 草稿的身份不可变 + 平台 / 站点必须与目标店铺对得上。
CREATE FUNCTION guard_editor_draft_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.shop_id IS DISTINCT FROM OLD.shop_id
       OR NEW.marketplace_id IS DISTINCT FROM OLD.marketplace_id
       OR NEW.locale IS DISTINCT FROM OLD.locale
       OR NEW.listing_id IS DISTINCT FROM OLD.listing_id
       OR NEW.source_sku_id IS DISTINCT FROM OLD.source_sku_id THEN
      RAISE EXCEPTION
        'Draft % content identity (shop/site/locale/subject) is immutable; create another draft instead', OLD.id
        USING ERRCODE='23514';
    END IF;
  END IF;
  -- 平台必须等于目标店铺的平台（"按平台隔离"不能被草稿自己声明错）
  IF NOT EXISTS (SELECT 1 FROM shop s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.shop_id
                   AND s.platform_id=NEW.platform_id) THEN
    RAISE EXCEPTION 'Draft % platform does not match the target shop platform', NEW.id
      USING ERRCODE='23514';
  END IF;
  -- 目标站点必须是该店铺**已启用**的站点
  IF NOT EXISTS (SELECT 1 FROM shop_marketplace sm WHERE sm.tenant_id=NEW.tenant_id
                   AND sm.shop_id=NEW.shop_id AND sm.marketplace_id=NEW.marketplace_id
                   AND sm.status='ACTIVE') THEN
    RAISE EXCEPTION 'Draft % target marketplace is not an enabled site of the target shop', NEW.id
      USING ERRCODE='23514';
  END IF;
  -- 关联的 listing 必须属于同一个店铺
  IF NEW.listing_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM listing l WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.listing_id
        AND l.shop_id=NEW.shop_id) THEN
    RAISE EXCEPTION 'Draft % linked listing does not belong to the target shop', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER editor_draft_identity BEFORE INSERT OR UPDATE ON listing_editor_draft
  FOR EACH ROW EXECUTE FUNCTION guard_editor_draft_identity();

-- (c) 🔴 字段集由平台要求决定：**该平台没有的栏不允许出现在草稿里**，
--     且值的形状必须与登记的 value_kind 对得上。
CREATE FUNCTION guard_editor_draft_field_values() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fkey text; fval jsonb; kind text; requirement text;
BEGIN
  FOR fkey, fval IN SELECT e.key, e.value FROM jsonb_each(NEW.field_values) AS e LOOP
    SELECT r.value_kind, r.requirement INTO kind, requirement
      FROM platform_listing_field_requirement r
     WHERE r.platform_id=NEW.platform_id AND r.field_key=fkey AND r.enabled
       AND (r.marketplace_id IS NULL OR r.marketplace_id=NEW.marketplace_id)
       AND (r.category_path IS NULL OR r.category_path=NEW.category_path)
     ORDER BY (r.marketplace_id IS NOT NULL) DESC, (r.category_path IS NOT NULL) DESC
     LIMIT 1;
    IF kind IS NULL THEN
      RAISE EXCEPTION 'Draft % carries field % which the target platform does not declare', NEW.id, fkey
        USING ERRCODE='23514';
    END IF;
    IF requirement='UNSUPPORTED' THEN
      RAISE EXCEPTION 'Draft % carries field % which the target platform declares unsupported', NEW.id, fkey
        USING ERRCODE='23514';
    END IF;
    CASE kind
      WHEN 'TEXT' THEN
        IF jsonb_typeof(fval) <> 'string' THEN
          RAISE EXCEPTION 'Field % must be a text value', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'LONG_TEXT' THEN
        IF jsonb_typeof(fval) <> 'string' THEN
          RAISE EXCEPTION 'Field % must be a text value', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'TEXT_LIST' THEN
        IF jsonb_typeof(fval) <> 'array' THEN
          RAISE EXCEPTION 'Field % must be a text list', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'IMAGE_LIST' THEN
        IF jsonb_typeof(fval) <> 'array' THEN
          RAISE EXCEPTION 'Field % must be an image list', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'MODULE_LIST' THEN
        IF jsonb_typeof(fval) <> 'array' THEN
          RAISE EXCEPTION 'Field % must be a module list', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'VIDEO_REF' THEN
        IF jsonb_typeof(fval) <> 'string' AND jsonb_typeof(fval) <> 'object' THEN
          RAISE EXCEPTION 'Field % must be a video reference', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'ATTRIBUTE_MAP' THEN
        IF jsonb_typeof(fval) <> 'object' THEN
          RAISE EXCEPTION 'Field % must be an attribute map', fkey USING ERRCODE='23514';
        END IF;
      WHEN 'STATEMENT_MAP' THEN
        IF jsonb_typeof(fval) <> 'object' THEN
          RAISE EXCEPTION 'Field % must be a statement map', fkey USING ERRCODE='23514';
        END IF;
    END CASE;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER editor_draft_field_values BEFORE INSERT OR UPDATE ON listing_editor_draft
  FOR EACH ROW EXECUTE FUNCTION guard_editor_draft_field_values();

-- (d) 状态机：DRAFT → SUBMITTED（提交审核入队）；SUBMITTED 之后内容又改了 ⇒ 强制回到 DRAFT
--     （入队记录里冻结的是提交那一刻的快照，所以草稿回到 DRAFT 不会篡改已入队的内容；
--      但"已经入队"这件事必须随内容变化一起作废，否则界面会说"已入队"而内容其实是新的）。
CREATE FUNCTION guard_editor_draft_state() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.state='DISCARDED' AND NEW.state<>'DISCARDED' THEN
    RAISE EXCEPTION 'Draft % is discarded and cannot be reopened', OLD.id USING ERRCODE='23514';
  END IF;
  IF OLD.state='SUBMITTED' AND NEW.field_values IS DISTINCT FROM OLD.field_values THEN
    NEW.state := 'DRAFT';
    NEW.submitted_at := NULL;
  END IF;
  IF NEW.state='SUBMITTED' AND NEW.submitted_at IS NULL THEN
    NEW.submitted_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER editor_draft_state BEFORE UPDATE ON listing_editor_draft
  FOR EACH ROW EXECUTE FUNCTION guard_editor_draft_state();

-- (e) 入队记录只追加：内容快照与校验证据一经写入不许改（那是"当时凭什么这么发"的凭据）。
CREATE FUNCTION guard_editor_submission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.content_snapshot IS DISTINCT FROM OLD.content_snapshot
     OR NEW.validation_snapshot IS DISTINCT FROM OLD.validation_snapshot
     OR NEW.draft_id IS DISTINCT FROM OLD.draft_id
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key THEN
    RAISE EXCEPTION 'Submission % is append-only: content, validation evidence and identity are frozen',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER editor_submission_immutable BEFORE UPDATE ON listing_editor_submission
  FOR EACH ROW EXECUTE FUNCTION guard_editor_submission_immutable();

-- ===========================================================================
-- 六、多租户隔离：租户业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
-- `platform_listing_field_requirement` 是**全局只读码表**，按 V1 的 platform / marketplace
-- 同口径不参与租户隔离。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_editor_draft','listing_editor_submission'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V171 / V177 同构：可见性随店铺负责人 / 部门收敛）
CREATE POLICY data_scope ON listing_editor_draft AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_editor_draft.tenant_id
             AND s.id=listing_editor_draft.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_editor_draft.tenant_id
             AND s.id=listing_editor_draft.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON listing_editor_submission AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_editor_submission.tenant_id
             AND s.id=listing_editor_submission.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_editor_submission.tenant_id
             AND s.id=listing_editor_submission.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 七、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  listing_editor_draft, listing_editor_submission TO channel_app;
-- 平台字段要求注册表是全局只读码表：运行角色只读（与 platform / marketplace 同口径）
GRANT SELECT ON platform_listing_field_requirement TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V191 同口径）。
REVOKE ALL ON
  listing_editor_draft, listing_editor_submission FROM starcon_app;
REVOKE ALL ON platform_listing_field_requirement FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 刊登编辑器属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.2 批量编辑、4.4.2.1 商品认领同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------
