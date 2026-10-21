-- ===========================================================================
-- 4.2.2.1 刊登模板（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.2.2.1 刊登模板
--   - 职责：沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、
--           套用图片组），一键套用到目标商品。
--   - 输入：模板定义（字段规则 + 图片组 + 必填校验）；套用目标。
--   - 输出：模板记录；套用后的内容草稿。
--   - 关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1 编辑器。
--           **模板引用的是"取值规则"而非一次性拷贝**，商品改价或换图后套用结果跟着变。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 策略**，零 ALTER、零 UPDATE 任何既有表：
--   * V1 `platform`（平台码表，全局只读）/ `marketplace`（站点码表，全局只读）
--   * V1 `shop`（租户店铺，复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能）
--   * V171 `listing`（平台商品）
--   * V177 `listing_content_draft`（🔴 草稿态存储，**本模块复用它，不另建平行草稿表**）
--
-- 03 商品主数据（catalog 服务）：`sku` / `media_asset` / `content_template` 全部**跨服务**，
-- 本表**零外键、零 JOIN、零共享视图** —— 只存**逻辑 ID**（sku_id / asset id / 模板编码），
-- 存在性与取值经 catalog 的授权 HTTP API 确认（与 V171 `listing_variant.internal_sku_id`
-- 同一纪律）。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本模块的核心不变量：模板存的是"规则"，不是"值的快照"
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：**模板引用的是"取值规则"而非一次性拷贝，商品改价或换图后套用结果跟着变。**
--
-- 这句话如果只写在文档里，下一个人就会顺手把「当前标题」「当前图片」抄进模板表 ——
-- 那样模板就退化成了"一次性拷贝"，商品改价后模板不会跟着变，而**没有任何报错**。
-- 所以本迁移用**四道结构约束**把它钉死，缺一道这个不变量就守不住：
--
-- 【结构约束 1】模板表**没有任何「按 listing 存值」的列**。
--   `listing_template` 只有适用范围（scope / platform / marketplace / category_path /
--   shop）与语言；`listing_template_field` 只有**来源引用**与**取值表达式**。
--   值唯一存在的地方是 `listing_template_apply_field` —— 那是**套用日志**（历史事实），
--   不是模板。模板与日志物理分表，「模板吸收了快照」这件事在 schema 层就无处可藏。
--
-- 【结构约束 2】`listing_template_field.source_ref` 对非 LITERAL 来源**必须是标识符**。
--   CHECK `source_ref ~ '^[A-Za-z0-9_.:-]{1,200}$'`：来源引用只能是「属性码 / 内容模板编码 /
--   图片组编码 / 字段名」这类**标识符**，不能是一段自由文本。
--   于是「把商品现在的标题 199.00 美元抄进 source_ref 当快照」在 DB 层直接写不进去。
--
-- 【结构约束 3】`value_expression` 里的占位符**必须是标识符**（触发器
--   `guard_listing_template_field_expression`）：只允许 `{{标识符}}` 形式。
--   于是「把值藏进占位符」也写不进去（`{{Price 199.00}}` 会被拒）。
--   同一触发器还要求：非 LITERAL 来源的取值表达式**至少引用一个占位符** ——
--   一条不引用任何来源的表达式就是伪装成规则的常量快照。
--
-- 【结构约束 4】套用日志**只追加**（触发器 `guard_listing_template_apply_append_only`）。
--   历史那次套用算出了什么值，改不了也删不掉 —— 否则「当时套用出来的是什么」永远答不上来。
--
-- 集成测试把这四条正面打穿：改一次源商品（属性 / 图片）→ 重新套用 → 草稿里的值跟着变，
-- **同时**断言模板那几行在套用前后**逐字未变**（模板没有吸收快照）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 两种作用域必须都真做，判定要有真实数据支撑
-- ---------------------------------------------------------------------------
-- 蓝图：「类目级、店铺级」。两者的**适用范围列互斥**（CHECK 钉死）：
--   CATEGORY 作用域 ⇒ 必须给 category_path、**不许**给 shop_id
--   SHOP     作用域 ⇒ 必须给 shop_id、**不许**给 category_path
-- 互斥不是洁癖：两个都给就会出现「这个模板到底按类目还是按店铺生效」两个答案，
-- 而判定逻辑只能挑一个 —— 挑错的那个就是静默用错模板。
--
-- 判定本身**不是服务层写死的字符串比较**：作用域的成立与否由
-- `listing_template` 触发的 `guard_listing_template_scope` 与套用时
-- `ListingTemplateService.scopeMatches` **在真实数据上**判定：
--   * 平台必须与模板一致（内容按平台 + 站点隔离，见第三节）；
--   * 站点给了就必须与 listing 的 marketplace_id 一致；
--   * CATEGORY ⇒ listing.category_path 与模板 category_path 逐字相同；
--   * SHOP     ⇒ listing.shop_id 与模板 shop_id 相同。
-- 并且 `GET /listing-templates/{id}/matches` 直接把那批**真的命中**的 listing 从库里查出来
-- （真分页），让「适用范围」在界面上是可查的事实而不是一句描述。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 内容按「平台 + 站点」隔离
-- ---------------------------------------------------------------------------
-- 同卷边界段的硬约束：「内容按**平台 + 站点**隔离，同一商品在不同平台内容互不污染」。
--
-- 落法：模板带 `platform_id`（必填）与 `marketplace_id`（可空 = 该平台全站点）。
-- 套用时目标 listing 的平台必须与模板一致；模板给了站点则还必须一致。
-- 跨平台套用直接 422 并写明"模板属于 X 平台，目标属于 Y 平台" ——
-- 静默放行的话，同一条商品在两个平台的内容草稿会互相覆盖。
--
-- 草稿侧（`listing_content_draft`）本身按 listing_id 落，listing 又是按店铺 + 平台事实，
-- 所以「同一商品在不同平台内容互不污染」在存储层天然成立。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 套用只产出草稿，绝不写线上
-- ---------------------------------------------------------------------------
-- 与 4.2.1.2 批量编辑同一纪律：模板套用产出的是**草稿**（V177 的
-- `listing_content_draft`），**不允许存在任何端点让模板直接写线上 `listing`**。
-- 套用事务同样打开 `app.draft_only` 标记（V177 的 `guard_listing_live_content`
-- 触发器在此期间拒绝对线上 listing 内容列的任何改动）——
-- 于是将来有人在模板路径里手滑加了句 `update listing set title=…` 会在测试期就炸。
-- 发布是 4.3.1.2 的职责。
--
-- 本模块**不建平行草稿表**：草稿表是 4.2.1.2 已交付的 `listing_content_draft`，
-- 它的列语义与 CHECK（touched_fields 与有值列一一对应）、触发器
-- （`guard_content_draft_provenance`）本模块全部尊重：
--   * 只写本次**真的拟改**的列，其余列保持 NULL（touched_fields 之外 = 本次没拟改）；
--   * 合并已有草稿时 touched_fields 取并集，**只覆盖本次拟改的列** ——
--     没拟改的列不被清空（否则会把「用户没要求改的线上内容」在发布时抹掉）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴「不知道」是一个状态，不是一个 0
-- ---------------------------------------------------------------------------
-- 跨服务上游（03 文案资产 / 03 媒体库 / 03 商品属性）取不到时，
-- 套用结果里该字段是 `UNKNOWN_SOURCE`（上游读不到）或 `SOURCE_MISSING`
-- （上游读到了但引用的东西不存在），**并带 owner_module 点名归属模块**。
-- 绝**不**回退成「没有值」或 0 —— 那会让运营以为「这个属性就是空的」，
-- 而真相是「我们查不到」。必填字段遇到这两种状态一律记违规（validation FAIL）。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 必填校验要真的算出来，并把违规项指到具体字段与规则
-- ---------------------------------------------------------------------------
-- `listing_template_field.required` 是规则的属性，不是注释。
-- 套用时逐条求值后：
--   * 必填字段求值失败 ⇒ `listing_template_apply_field.violation = true`
--     且 `violation_code` 指到失败类型、`rule_ref` 指到**具体那条规则**
--     （形如 `PRODUCT_ATTRIBUTE:price`）；
--   * 批次级 `validation_status` = PASS / FAIL。
-- 所以「必填校验」是算出来的结果，不是人填的标记。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 套用要幂等，且语义明确定义
-- ---------------------------------------------------------------------------
-- 本模块把幂等语义写死成**两条**，互不打架：
--   * **不带 idempotencyKey 重复套用** ⇒ **覆盖草稿**（重新求值后覆盖同一份 DRAFT）。
--     这是「商品改价后套用结果跟着变」的必要条件 —— 不覆盖就没法看到新值。
--     每次套用都在 `listing_template_apply` 留一条记录（历史可追）。
--   * **带同一个 idempotencyKey 重复套用** ⇒ **返回第一次的那一份结果**，
--     不重新求值、不再写草稿、不再落日志（结果里 idempotentReplay=true）。
--     这是「重复触发不产生重复动作」的那一半。
-- 语义写在端点注释与能力声明里，集成测试两条都打穿。
--
-- ---------------------------------------------------------------------------
-- 八、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本模块**无任何金额列**：刊登模板填的是内容字段（标题 / 卖点 / 图片 / 类目），
-- 不填价格 —— 售价归 05 定价。存在的两个数值列：
--   * `max_count`（图片组张数上限）—— 是**计数**，不是业务数量；
--   * `slot_no`（规则的显示顺序）—— 是序号。
-- 真正的业务数量（平台可售量）在 V171 的 listing 上，不在本迁移。
-- 无 float / double 列。
--
-- ---------------------------------------------------------------------------
-- 九、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒（4.1.1.1 实测踩过）。
-- 集成测试**断言写回的值逐字相等**。

-- ---------------------------------------------------------------------------
-- listing_template · 刊登模板本体（适用范围 + 语言）
-- ---------------------------------------------------------------------------
-- 🔴 本表**没有**任何「按 listing 存值」的列 —— 见文件头「结构约束 1」。
CREATE TABLE listing_template (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  code          varchar(64) NOT NULL,
  name          varchar(200) NOT NULL,
  description   varchar(1000) NOT NULL DEFAULT '',
  -- 🔴 两种作用域互斥（CHECK 钉死，见文件头第二节）
  scope         text NOT NULL CHECK(scope IN ('CATEGORY','SHOP')),
  -- 🔴 内容按平台 + 站点隔离：平台必填，站点可空（= 该平台全站点）
  platform_id   uuid NOT NULL REFERENCES platform(id),
  marketplace_id uuid,
  -- CATEGORY 作用域的适用范围（平台侧类目路径原文）
  category_path varchar(500),
  -- SHOP 作用域的适用范围（租户店铺，复合 FK 带 tenant_id）
  shop_id       uuid,
  -- 内容按语言分（与 V175 快照的 language 同一维度）
  language      varchar(16) NOT NULL DEFAULT 'en-US',
  status        text NOT NULL DEFAULT 'DRAFT'
                CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  CHECK(btrim(language)<>''),
  -- 编码是标识符：它会被别的模块当引用用，不能是一段自由文本
  CHECK(code ~ '^[A-Za-z0-9_.\-]{1,64}$'),
  -- 🔴 作用域互斥：两个都给 = 「到底按类目还是按店铺生效」有两个答案
  CHECK((scope='CATEGORY' AND category_path IS NOT NULL AND btrim(category_path)<>'' AND shop_id IS NULL)
     OR (scope='SHOP'    AND shop_id IS NOT NULL AND category_path IS NULL))
);
COMMENT ON TABLE listing_template IS
  '4.2.2.1 · 🔴 刊登模板（类目级 / 店铺级）。本表只存**适用范围**与语言，**不存任何字段值** —— '
  '模板引用的是取值规则（见 listing_template_field），不是一次性拷贝。';

-- ---------------------------------------------------------------------------
-- listing_template_field · 字段填充规则（🔴 存规则，不存值）
-- ---------------------------------------------------------------------------
-- 一行 = 「某个字段怎么填」的一条规则。
--
-- 🔴 source_kind 五值，逐字对应蓝图职责句里的三处来源 + 两个兜底：
--   LISTING_FIELD     取自目标 listing 自身的当前内容（标题 / 卖点 / 图片 / 类目…）
--   PRODUCT_ATTRIBUTE 取自商品属性（03 商品主数据，跨服务只读，走授权 API）
--   CONTENT_TEMPLATE  引用文案库（03 描述与卖点库，跨服务只读，走授权 API）
--   IMAGE_GROUP       套用图片组（本表的 listing_template_asset_group，指向 03 媒体库）
--   LITERAL           规则里的常量（前后缀 / 固定类目路径）。**唯一允许自由文本的来源**
--
-- 🔴 source_ref 对非 LITERAL 来源必须是**标识符**（结构约束 2）：
--   不能是一段自由文本 —— 否则「把商品现在的值抄进来源引用」就成了一次无声的快照。
CREATE TABLE listing_template_field (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  template_id   uuid NOT NULL,
  field         text NOT NULL
                CHECK(field IN ('TITLE','BULLETS','IMAGES','CATEGORY_PATH','PLATFORM_CATEGORY_ID')),
  source_kind   text NOT NULL
                CHECK(source_kind IN ('LISTING_FIELD','PRODUCT_ATTRIBUTE','CONTENT_TEMPLATE','IMAGE_GROUP','LITERAL')),
  source_ref    varchar(200) NOT NULL DEFAULT '',
  -- 取值表达式：允许常量前后缀 + {{占位符}}。占位符形如 {{value}}（取来源值）
  -- 或 {{属性码}}（取商品属性）。非 LITERAL 来源必须至少引用一个占位符。
  value_expression text NOT NULL DEFAULT '',
  -- 🔴 必填校验是**规则的属性**，套用时真的算出来（见文件头第六节）
  required      boolean NOT NULL DEFAULT false,
  slot_no       integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_id, field),
  FOREIGN KEY(tenant_id, template_id) REFERENCES listing_template(tenant_id, id) ON DELETE CASCADE,
  CHECK(slot_no>=0),
  -- 🔴 结构约束 2：来源引用只能是标识符，不能是一段自由文本
  CHECK(source_kind='LITERAL' OR source_ref ~ '^[A-Za-z0-9_.:\-]{1,200}$'),
  -- LITERAL 必须给常量；其余来源必须给引用
  CHECK((source_kind='LITERAL' AND btrim(value_expression)<>'')
     OR (source_kind<>'LITERAL' AND btrim(source_ref)<>'')),
  -- LISTING_FIELD 的引用只能是已知的 listing 字段名
  CHECK(source_kind<>'LISTING_FIELD'
     OR source_ref IN ('TITLE','BULLETS','IMAGES','CATEGORY_PATH','PLATFORM_CATEGORY_ID')),
  -- 🔴 字段与来源的合法组合（不同字段能取到的来源不一样）
  CHECK((field IN ('TITLE','BULLETS')
         AND source_kind IN ('LISTING_FIELD','PRODUCT_ATTRIBUTE','CONTENT_TEMPLATE','LITERAL'))
     OR (field='IMAGES' AND source_kind IN ('LISTING_FIELD','IMAGE_GROUP','LITERAL'))
     OR (field='CATEGORY_PATH' AND source_kind IN ('LISTING_FIELD','PRODUCT_ATTRIBUTE','LITERAL'))
     OR (field='PLATFORM_CATEGORY_ID' AND source_kind IN ('LISTING_FIELD','LITERAL')))
);
COMMENT ON TABLE listing_template_field IS
  '4.2.2.1 · 🔴 字段填充**规则**（来源引用 + 取值表达式 + 必填标记）。**不存值**：'
  'source_ref 只能是标识符、取值表达式里的占位符只能是标识符，值一律在套用时实时求值。';

-- ---------------------------------------------------------------------------
-- listing_template_asset_group · 图片组（套用图片组）
-- ---------------------------------------------------------------------------
-- 一行 = 一组图片的**引用**，不是图片本身。
--
-- 🔴 asset_ids 存的是 03 媒体库 media_asset 的**逻辑 ID**（跨服务，零 FK 零 JOIN）。
-- 套用时经 catalog 的授权 API 解析成**当前的** storage_key ——
-- 所以素材换了版本（换图），重新套用出来的就是新的图。这正是蓝图那句
-- 「商品改价或换图后套用结果跟着变」在图片侧的真实落法。
--
-- group_kind 两值：
--   ASSET_LIST 明确列出素材 id（顺序即展示顺序）
--   TAG        按媒体库标签取（素材库里给一批图打同一个标签 = 一个组）
CREATE TABLE listing_template_asset_group (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  template_id uuid NOT NULL,
  group_code  varchar(64) NOT NULL,
  group_kind  text NOT NULL DEFAULT 'ASSET_LIST' CHECK(group_kind IN ('ASSET_LIST','TAG')),
  tag_code    varchar(64),
  asset_ids   uuid[] NOT NULL DEFAULT '{}'::uuid[],
  max_count   integer NOT NULL DEFAULT 9,
  slot_no     integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_id, group_code),
  FOREIGN KEY(tenant_id, template_id) REFERENCES listing_template(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(group_code)<>''),
  CHECK(max_count BETWEEN 1 AND 20),
  CHECK(slot_no>=0),
  -- 两种取组方式各给各的必填项，不允许两头都空
  CHECK((group_kind='ASSET_LIST' AND cardinality(asset_ids)>0 AND tag_code IS NULL)
     OR (group_kind='TAG' AND btrim(coalesce(tag_code,''))<>'' AND cardinality(asset_ids)=0))
);
COMMENT ON TABLE listing_template_asset_group IS
  '4.2.2.1 · 图片组**引用**（素材逻辑 id 或媒体库标签）。套用时解析成当前 storage_key，'
  '所以换图后重新套用结果跟着变。';

-- ---------------------------------------------------------------------------
-- listing_template_apply · 套用记录（一次套用 = 一条历史事实）
-- ---------------------------------------------------------------------------
-- 「输出：套用后的内容草稿」的溯源行。
--
-- 🔴 本表**是日志不是模板**：它记的是「那一次套用算出了什么」，
-- 唯一存在价值就是回答"当时套用出来的是什么"。它 append-only（触发器钉死）。
CREATE TABLE listing_template_apply (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  template_id   uuid NOT NULL,
  listing_id    uuid NOT NULL,
  -- 产出的草稿行（逻辑引用；草稿随 listing 级联删除，所以不建外键）
  target_draft_id uuid,
  -- 幂等键：同一模板 + 同一目标 + 同一键只允许一次套用（NULL 互不冲突）
  idempotency_key varchar(120),
  -- 🔴 必填校验的**结果**（算出来的，不是人填的）
  validation_status text NOT NULL CHECK(validation_status IN ('PASS','FAIL')),
  applied_count integer NOT NULL DEFAULT 0,
  unknown_count integer NOT NULL DEFAULT 0,
  violation_count integer NOT NULL DEFAULT 0,
  actor_id      uuid,
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, template_id) REFERENCES listing_template(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  CHECK(applied_count>=0 AND unknown_count>=0 AND violation_count>=0),
  -- 幂等键唯一（同一模板 + 同一目标 + 同一键）
  UNIQUE(tenant_id, template_id, listing_id, idempotency_key)
);
COMMENT ON TABLE listing_template_apply IS
  '4.2.2.1 · 一次套用的历史事实（append-only）。**不是模板** —— 模板在 listing_template / '
  'listing_template_field 里，那两张表没有任何值列。';

-- ---------------------------------------------------------------------------
-- listing_template_apply_field · 逐字段求值明细（套用结果明细 + 必填违规）
-- ---------------------------------------------------------------------------
-- 一行 = 一条规则在那一次套用里的求值结果。
--
-- 🔴 status 五值，把「取到了」与「没取到 / 不知道为什么没取到」彻底分开：
--   RESOLVED        取到了值
--   EMPTY           取到了来源，但来源本身是空的（例如属性没有值）
--   SOURCE_MISSING  上游读到了，但**引用的东西不存在**（属性码不存在 / 模板编码不存在 /
--                   素材 id 不存在 / 标签下没有素材 / listing 没映射内部 SKU）
--   UNKNOWN_SOURCE  **上游读不到**（不可达 / 报错 / 超时）—— 这就是「不知道」
--   LIMIT_EXCEEDED  取到了但超出草稿的物理上限（标题超长 / 图片超数）
-- 后三者带 owner_module 点名归属模块 —— 绝**不**回退成「没有值」或 0。
CREATE TABLE listing_template_apply_field (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  apply_id      uuid NOT NULL,
  field         text NOT NULL
                CHECK(field IN ('TITLE','BULLETS','IMAGES','CATEGORY_PATH','PLATFORM_CATEGORY_ID')),
  source_kind   text NOT NULL,
  -- 🔴 违规指到**具体那条规则**（形如 PRODUCT_ATTRIBUTE:price）
  rule_ref      varchar(300) NOT NULL,
  source_ref    varchar(200) NOT NULL DEFAULT '',
  status        text NOT NULL
                CHECK(status IN ('RESOLVED','EMPTY','SOURCE_MISSING','UNKNOWN_SOURCE','LIMIT_EXCEEDED')),
  -- 本次求值的结果（日志，不是模板）
  value         text,
  -- 「不知道」时点名归属模块（03 文案资产 / 03 媒体库 / 03 商品主数据）
  owner_module  varchar(32),
  reason        varchar(500) NOT NULL DEFAULT '',
  -- 🔴 必填校验的结论：必填字段求值失败 ⇒ violation=true + violation_code
  violation     boolean NOT NULL DEFAULT false,
  violation_code varchar(64) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, apply_id, field),
  FOREIGN KEY(tenant_id, apply_id) REFERENCES listing_template_apply(tenant_id, id) ON DELETE CASCADE,
  -- 声称违规却没有编码，或声称不违规却有编码，两种自相矛盾都直接拒
  CHECK((violation AND violation_code<>'') OR (NOT violation AND violation_code='')),
  -- 「不知道」必须点名归属模块
  CHECK(status NOT IN ('SOURCE_MISSING','UNKNOWN_SOURCE')
     OR (owner_module IS NOT NULL AND btrim(owner_module)<>'')),
  -- 取到值才算 RESOLVED
  CHECK(status<>'RESOLVED' OR (value IS NOT NULL AND btrim(value)<>''))
);
COMMENT ON TABLE listing_template_apply_field IS
  '4.2.2.1 · 逐字段求值明细 + 必填违规（指到具体字段与具体规则）。'
  'SOURCE_MISSING / UNKNOWN_SOURCE 带 owner_module ——「不知道」是一个状态，不是一个 0。';

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX listing_template_list        ON listing_template(tenant_id, updated_at DESC, id);
CREATE INDEX listing_template_scope       ON listing_template(tenant_id, scope, platform_id, status);
CREATE INDEX listing_template_shop        ON listing_template(tenant_id, shop_id);
CREATE INDEX listing_template_category    ON listing_template(tenant_id, category_path);
CREATE INDEX listing_template_field_by_tpl ON listing_template_field(tenant_id, template_id, slot_no, id);
CREATE INDEX listing_template_group_by_tpl ON listing_template_asset_group(tenant_id, template_id, slot_no, id);
CREATE INDEX listing_template_apply_recent ON listing_template_apply(tenant_id, created_at DESC, id);
CREATE INDEX listing_template_apply_tpl   ON listing_template_apply(tenant_id, template_id, created_at DESC);
CREATE INDEX listing_template_apply_list  ON listing_template_apply(tenant_id, listing_id, created_at DESC);
CREATE INDEX listing_template_apply_field_by_apply ON listing_template_apply_field(tenant_id, apply_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴 适用范围的一致性由 DB 复核：站点必须属于该平台、店铺必须属于该平台。
--     这两条不是服务层的 if —— 绕过 HTTP 直连 SQL 写模板也会被拒。
CREATE FUNCTION guard_listing_template_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  site_platform uuid;
  shop_platform uuid;
BEGIN
  IF NEW.marketplace_id IS NOT NULL THEN
    SELECT platform_id INTO site_platform FROM marketplace WHERE id=NEW.marketplace_id;
    IF site_platform IS NULL THEN
      RAISE EXCEPTION '站点不存在：%', NEW.marketplace_id USING ERRCODE='23514';
    END IF;
    IF site_platform <> NEW.platform_id THEN
      RAISE EXCEPTION '模板的站点属于另一个平台：站点 % 属于平台 %，而模板平台是 %',
        NEW.marketplace_id, site_platform, NEW.platform_id USING ERRCODE='23514';
    END IF;
  END IF;
  IF NEW.shop_id IS NOT NULL THEN
    SELECT platform_id INTO shop_platform FROM shop WHERE tenant_id=NEW.tenant_id AND id=NEW.shop_id;
    IF shop_platform IS NULL THEN
      RAISE EXCEPTION '店铺不存在：%', NEW.shop_id USING ERRCODE='23514';
    END IF;
    IF shop_platform <> NEW.platform_id THEN
      RAISE EXCEPTION '模板的店铺属于另一个平台：店铺 % 属于平台 %，而模板平台是 %',
        NEW.shop_id, shop_platform, NEW.platform_id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_template_scope BEFORE INSERT OR UPDATE ON listing_template
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_scope();

-- (b) 🔴🔴 结构约束 3：取值表达式里的占位符必须是标识符，
--     且非 LITERAL 来源必须**至少引用一个占位符**。
--     这一条把「把值藏进占位符」与「用一条不引用来源的表达式冒充规则」一起堵死。
CREATE FUNCTION guard_listing_template_field_expression() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  leftover text;
  m text;
BEGIN
  IF NEW.source_kind='LITERAL' THEN
    RETURN NEW;
  END IF;
  -- 把所有合法占位符抠掉；剩下的若还有 { 或 } 就是格式错误
  leftover := regexp_replace(NEW.value_expression, '\{\{[A-Za-z0-9_.\-]+\}\}', '', 'g');
  IF leftover ~ '[{}]' THEN
    RAISE EXCEPTION '取值表达式的占位符格式不对：只允许 {{标识符}} 形式（标识符 = 字母数字下划线点连字符），实际收到「%」',
      NEW.value_expression USING ERRCODE='23514';
  END IF;
  FOR m IN SELECT (regexp_matches(NEW.value_expression, '\{\{([^}]*)\}\}', 'g'))[1] LOOP
    IF m !~ '^[A-Za-z0-9_.\-]{1,64}$' THEN
      RAISE EXCEPTION '占位符名「%」不是合法标识符：占位符只能引用属性码之类的标识符，不能是一段值（否则模板就存了快照）',
        m USING ERRCODE='23514';
    END IF;
  END LOOP;
  IF btrim(NEW.value_expression)<>'' AND NEW.value_expression !~ '\{\{[A-Za-z0-9_.\-]+\}\}' THEN
    RAISE EXCEPTION '取值表达式必须至少引用一个占位符（如 {{value}} 或 {{属性码}}）：一条不引用任何来源的表达式就是伪装成规则的常量快照，实际收到「%」',
      NEW.value_expression USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_template_field_expression BEFORE INSERT OR UPDATE ON listing_template_field
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_field_expression();

-- (c) IMAGE_GROUP 来源必须指向本模板真实存在的图片组（跨表复核，DB 层）
CREATE FUNCTION guard_listing_template_field_group() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  groups bigint;
BEGIN
  IF NEW.source_kind<>'IMAGE_GROUP' THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO groups FROM listing_template_asset_group
    WHERE tenant_id=NEW.tenant_id AND template_id=NEW.template_id AND group_code=NEW.source_ref;
  IF groups=0 THEN
    RAISE EXCEPTION '图片组「%」不在本模板里：IMAGE_GROUP 来源必须指向本模板已定义的图片组',
      NEW.source_ref USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_template_field_group BEFORE INSERT OR UPDATE ON listing_template_field
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_field_group();

-- (d) 🔴 结构约束 4：套用日志只追加。
--     历史那次套用算出了什么，改不了也删不掉 ——
--     否则「当时套用出来的是什么」永远答不上来。
CREATE FUNCTION guard_listing_template_apply_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '套用记录是只追加的历史事实，不允许 % （删改它会让「当时套用出来的是什么」答不上来）',
    TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_template_apply_append_only BEFORE UPDATE OR DELETE ON listing_template_apply
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_apply_append_only();
CREATE TRIGGER listing_template_apply_field_append_only BEFORE UPDATE OR DELETE ON listing_template_apply_field
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_apply_append_only();

-- (e) 产出的草稿行必须与本次套用同租户同目标（逻辑引用，但租户归属要复核）
CREATE FUNCTION guard_listing_template_apply_draft() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  matched bigint;
BEGIN
  IF NEW.target_draft_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO matched FROM listing_content_draft
    WHERE tenant_id=NEW.tenant_id AND id=NEW.target_draft_id AND listing_id=NEW.listing_id;
  IF matched=0 THEN
    RAISE EXCEPTION '产出的草稿 % 不属于本租户的这条 listing', NEW.target_draft_id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_template_apply_draft BEFORE INSERT ON listing_template_apply
  FOR EACH ROW EXECUTE FUNCTION guard_listing_template_apply_draft();

-- (f) 模板被套用过后不许删除：历史套用记录要能回溯到"用的是哪个模板"
--     （与 catalog V21 refuse_content_template_delete 同一纪律）。
CREATE FUNCTION refuse_listing_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  runs bigint;
BEGIN
  SELECT count(*) INTO runs FROM listing_template_apply
    WHERE tenant_id=OLD.tenant_id AND template_id=OLD.id;
  IF runs>0 THEN
    RAISE EXCEPTION '刊登模板「%」已被 % 次套用引用，不可删除：请改用停用（停用后不能再套用，历史套用记录照常可读）',
      OLD.code, runs USING ERRCODE='23503';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER refuse_listing_template_delete BEFORE DELETE ON listing_template
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_template_delete();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V171 / V177 / V183 / V190 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_template','listing_template_field','listing_template_asset_group',
    'listing_template_apply','listing_template_apply_field' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围（RESTRICTIVE，与 V171 同构：可见性随店铺负责人 / 部门收敛）
-- ---------------------------------------------------------------------------
-- 模板本身是租户级资产、不挂店铺维度（同 V177 listing_bulk_template：租户共享资产），
-- 所以它只有 isolation 策略。
-- 套用记录与明细挂具体 listing，随它的可见性收敛 ——
-- 否则"我套用了什么"会越过店铺数据范围。
CREATE POLICY data_scope ON listing_template_apply AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_template_apply.tenant_id
             AND l.id=listing_template_apply.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_template_apply.tenant_id
             AND l.id=listing_template_apply.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_template_apply_field AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l
             JOIN listing_template_apply a ON a.tenant_id=l.tenant_id AND a.listing_id=l.id
             WHERE a.tenant_id=listing_template_apply_field.tenant_id
             AND a.id=listing_template_apply_field.apply_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l
             JOIN listing_template_apply a ON a.tenant_id=l.tenant_id AND a.listing_id=l.id
             WHERE a.tenant_id=listing_template_apply_field.tenant_id
             AND a.id=listing_template_apply_field.apply_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V191 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  listing_template, listing_template_field, listing_template_asset_group,
  listing_template_apply, listing_template_apply_field TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V191 同口径）。
REVOKE ALL ON
  listing_template, listing_template_field, listing_template_asset_group,
  listing_template_apply, listing_template_apply_field FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：刊登模板属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 4.2.1.2 批量编辑、4.4.2.1 商品认领同族，identity 服务零改动。
--
-- ---------------------------------------------------------------------------
-- 留给下游 / 上游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **4.2.1.1 刊登编辑器**：本模块提供「模板 + 套用」能力；编辑器里的套用入口
--     归 4.2.1.1（该模块在本批并行交付，集成头此刻尚未落地），
--     能力声明里以 available=false + ownerModule='4.2.1.1' 显式标出。
--   * **4.3.1.2 批量发布**：消费本模块产出的草稿（与 4.2.1.2 的草稿同一张表）。
--   * **03 文案资产 / 03 媒体库 / 03 商品主数据**：只经 catalog 的授权 HTTP API 读，
--     不跨服务 SQL、不建跨库 FK、不建共享视图。
