-- ===========================================================================
-- 4.4.1.3 采集箱（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.4.1.3 采集箱
--   - 职责：采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品
--           （生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。
--   - 输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。
--   - 输出：认领后的商品草稿；采集箱状态更新。
--   - 关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。
--           **认领时必须做品牌与分类映射**，否则采集来的商品归属混乱，
--           后续无法按品牌做合规与绩效分析。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 归属 channel 而不是 catalog 的理由
-- ---------------------------------------------------------------------------
-- 采集箱的输入是**采集草稿**（4.4.1.1 落的 listing_collect_draft，4.4.1.2 也落同一张表），
-- 输出是**认领结果（商品草稿载荷）**。这条链路上没有任何一张 catalog 的事实表被写：
--   * 认领要「生成 SPU/SKU 草稿」，但**建档归 03 catalog（3.1.1.1）** ——
--     AGENTS.md 禁止跨服务数据库访问，04 卷边界也明写「不做：不新建 SKU（引导到 03）」。
--   * 因此本模块**只**在 channel 内产出「已映射、已命名、已预填内容」的商品草稿载荷
--     （listing_collect_claim_result），**不写** catalog 的 spu / sku / attribute 表，
--     也**不编造** catalog 的属性字典 id、计量单位 id、SPU 编码方案。
--   * 草稿的**落库**由 03 的建档流程消费；服务层的 capabilities 把这一维显式标成
--     available=false + ownerModule=3.1.1.1 + impact，**不假装已完成跨服务建档**。
--
-- 本迁移**纯新建五张表、零 ALTER 任何既有表**（与 V202 / V204 同一纪律）：
--   * `listing_collect_source` / `listing_collect_task` / `listing_collect_draft`
--     —— 4.4.1.1 的三张表，**只读复用**（采集箱读草稿、删草稿），**本迁移不动它们**。
--   * `listing_bulk_collect_task` / `listing_bulk_collect_result` —— 4.4.1.2 的两张表，
--     **只读复用**（采集箱读它来判定条目的来源是「链接采集 / 批量采集 / 插件上报」），
--     **本迁移不动它们**。
--   * `listing_claim` / `listing_claim_field` —— 4.4.2.1 跨店搬家，语义不同，**不碰**。
--   * `listing_migration_*` —— 4.4.2.2 批量搬迁，**不碰**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 边界一：采集箱**不建** SPU / SKU 记录
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**认领时必须做品牌与分类映射**」—— 这一条在本迁移里有物理落点：
-- `listing_collect_claim_rule` **必须**同时带：
--   * `target_category_code`（目标分类）**或** `category_mapping`（源类目 → 目标分类）；
--   * `brand_mapping`（源品牌 → 目标品牌引用）**非空**；
--   * `naming_template`（命名规则）**非空**且含占位符。
-- 三者缺一 ⇒ 服务层拒绝该规则，认领时逐条失败并写明原因。
--  ⇒ 「没有映射就不能认领」是 CHECK 约束 + 服务层校验双重保证，**不是 TODO**。
--
-- 认领**产出**的是草稿载荷（listing_collect_claim_result 里的 spu_draft / sku_drafts），
-- **不是** catalog 里的 spu / sku 行 —— 这两张表在 channel 的库里，与 catalog 无关。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 边界二：图片**只记 URL**，不入媒体库
-- ---------------------------------------------------------------------------
-- 媒体库归 03 catalog，AGENTS.md 明令**禁止跨服务数据库访问**。
-- ⇒ 认领结果里的图片仍然是**外部图片地址**（草稿里怎么存，这里就怎么带），
--   不下载二进制、不写媒体表。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 采集箱是「暂存区」：条目与草稿的关系
-- ---------------------------------------------------------------------------
-- 采集草稿（4.4.1.1 的事实）与采集箱条目（4.4.1.3 的事实）是**两张表**，
-- 不是一张表的两种状态：
--   * 草稿是「这个 URL 被采到了」；条目是「它被挑进了采集箱」——后者带标签、
--     带认领状态、带认领结果，**是本模块自己的可变状态**。
--   * 一份草稿最多入箱一次：`UNIQUE(tenant_id, draft_id)`。
--   * 删除条目时连带清理草稿 —— 但**仅当**草稿没有被 4.4.1.2 的结果清单引用
--     （`listing_bulk_collect_result.draft_id` 外键，V202 建表时**没有** ON DELETE CASCADE）。
--     被引用时保留草稿（否则会破坏 4.4.1.2 的结果清单事实），服务层在响应里写明。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本模块**不新增任何金额列**：外部页面原文标价在草稿上（4.4.1.1 的
-- `source_price_amount numeric(19,4)`），采集箱**只读引用**、不改写、不换算。
-- 认领结果里也没有我方售价（售价归 05 定价）。
-- 数量：本模块没有任何数量事实（我方可售量归 07 库存），只有计数字段
-- （`sku_draft_count` / `tag_count`）—— 计数是整数，不是业务数量。
--
-- ---------------------------------------------------------------------------
-- 五、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz`（jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒），
-- 并**截断到微秒**（PG timestamptz 精度就是微秒）。
--
-- ===========================================================================
-- 1) listing_collect_box_tag · 采集箱标签字典（租户业务表）
-- ===========================================================================
CREATE TABLE listing_collect_box_tag (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  name              varchar(64) NOT NULL,
  -- 标签颜色（十六进制或 CSS 颜色名；空串 = 用默认色）
  color             varchar(32) NOT NULL DEFAULT '',
  position          int NOT NULL DEFAULT 0,
  actor_id          uuid,
  -- 负责人 / 部门：可见性随它收敛（与 V202 listing_collect_task 同一纪律）
  owner_id          uuid,
  department_id     uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一租户内标签名唯一（否则「同名的两个标签」会让分组统计无法解释）
  UNIQUE(tenant_id, name),
  CHECK(btrim(name)<>'')
);
COMMENT ON TABLE listing_collect_box_tag IS
  '4.4.1.3 · 采集箱标签字典。批量打标的词表：标签本身是租户级共享词表，可见性按负责人/部门收敛。';

CREATE INDEX listing_collect_box_tag_recent ON listing_collect_box_tag(tenant_id, position, created_at DESC);
CREATE INDEX listing_collect_box_tag_owner  ON listing_collect_box_tag(tenant_id, owner_id);

-- ===========================================================================
-- 2) listing_collect_box_item · 采集箱条目（租户业务表）
-- ===========================================================================
CREATE TABLE listing_collect_box_item (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 指向 4.4.1.1 的采集草稿（4.4.1.2 的结果也落同一张表）
  draft_id          uuid NOT NULL,
  -- 条目来源：LINK 链接采集 / BULK 批量采集 / PLUGIN 浏览器插件上报
  -- （入箱时从 listing_bulk_collect_result 判定，不是猜的）
  origin            text NOT NULL CHECK(origin IN ('LINK','BULK','PLUGIN')),
  -- PENDING 待认领 / CLAIMED 已认领
  state             text NOT NULL CHECK(state IN ('PENDING','CLAIMED')),
  -- 🔴 最近一次认领失败的原因（成功时为空串）——
  --    「为什么这一条没认领成功」必须看得见，否则运营只能一条条试。
  claim_error       text NOT NULL DEFAULT '',
  -- 认领时间（未认领时为 null）
  claimed_at        timestamptz,
  actor_id          uuid,
  owner_id          uuid,
  department_id     uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一份草稿最多入箱一次
  UNIQUE(tenant_id, draft_id),
  FOREIGN KEY (tenant_id, draft_id) REFERENCES listing_collect_draft(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_collect_box_item IS
  '4.4.1.3 · 采集箱条目。🔴 不是商品、不是 SPU/SKU：本表只记录「哪份采集草稿被挑进了采集箱、'
  '打了什么标签、认领到了哪一步」；认领产出的商品草稿载荷在 listing_collect_claim_result 里。';

-- 列表默认排序（时间倒序）
CREATE INDEX listing_collect_box_item_recent ON listing_collect_box_item(tenant_id, created_at DESC, id);
-- 状态筛选（待认领 / 已认领）
CREATE INDEX listing_collect_box_item_state  ON listing_collect_box_item(tenant_id, state);
-- 认领结果回查
CREATE INDEX listing_collect_box_item_draft  ON listing_collect_box_item(tenant_id, draft_id);

-- ===========================================================================
-- 3) listing_collect_box_item_tag · 条目 ↔ 标签（租户业务表）
-- ===========================================================================
CREATE TABLE listing_collect_box_item_tag (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  item_id           uuid NOT NULL,
  tag_id            uuid NOT NULL,
  actor_id          uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一条目同一标签只挂一次（重复打标是幂等的）
  UNIQUE(tenant_id, item_id, tag_id),
  FOREIGN KEY (tenant_id, item_id) REFERENCES listing_collect_box_item(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, tag_id) REFERENCES listing_collect_box_tag(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_collect_box_item_tag IS
  '4.4.1.3 · 采集箱条目的标签关联（批量打标的落点）。';

CREATE INDEX listing_collect_box_item_tag_item ON listing_collect_box_item_tag(tenant_id, item_id);
CREATE INDEX listing_collect_box_item_tag_tag  ON listing_collect_box_item_tag(tenant_id, tag_id);

-- ===========================================================================
-- 4) listing_collect_claim_rule · 认领规则（租户业务表）
-- ===========================================================================
-- 蓝图输入句「认领规则（**目标分类、品牌映射、命名规则**）」——三条都在这张表里，
-- 且**都是必填**：缺一条的规则建不出来（CHECK 约束），服务层再校验一次。
CREATE TABLE listing_collect_claim_rule (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  name              varchar(100) NOT NULL,
  -- 🔴 目标分类（内部分类的外部引用编码；分类字典归 03 catalog，本模块不跨服务校验）
  target_category_code varchar(100) NOT NULL DEFAULT '',
  -- 🔴 分类映射：源类目名 → 目标分类编码。命中时优先于 target_category_code。
  category_mapping  jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 品牌映射：源品牌名 → 目标品牌引用。**非空**且每个值非空。
  brand_mapping     jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 命名规则：模板，支持 {brand} {title} {model} {category} {source} 占位符
  naming_template   varchar(400) NOT NULL DEFAULT '',
  -- 从草稿的哪个属性键取品牌 / 类目（默认「品牌」「类目」）
  brand_attribute_key    varchar(64) NOT NULL DEFAULT '品牌',
  category_attribute_key varchar(64) NOT NULL DEFAULT '类目',
  -- ACTIVE 可用 / DISABLED 停用
  state             text NOT NULL CHECK(state IN ('ACTIVE','DISABLED')),
  actor_id          uuid,
  owner_id          uuid,
  department_id     uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, name),
  CHECK(btrim(name)<>''),
  -- 🔴 jsonb 结构纪律：两个映射都必须是对象（不是数组、不是字符串）
  CHECK(jsonb_typeof(category_mapping) = 'object'),
  CHECK(jsonb_typeof(brand_mapping) = 'object'),
  -- 🔴 品牌映射不能是空对象 —— 「没有品牌映射就不能认领」
  --    （PostgreSQL 没有 jsonb_object_length，且 CHECK 里不允许子查询；
  --     「非空对象」用与 '{}' 比较表达，映射值级别的校验在服务层做 —— 那里会把
  --     空键空值整条丢掉，丢完为空就建不出规则）
  CHECK(brand_mapping <> '{}'::jsonb),
  -- 🔴 分类映射要么有目标分类兜底，要么自己就是非空映射
  CHECK(btrim(target_category_code) <> '' OR category_mapping <> '{}'::jsonb),
  -- 🔴 命名规则不能是空串（且必须含占位符，服务层再校验占位符）
  CHECK(btrim(naming_template) <> '')
);
COMMENT ON TABLE listing_collect_claim_rule IS
  '4.4.1.3 · 认领规则（目标分类 + 品牌映射 + 命名规则）。🔴 品牌映射非空是 DB 级硬约束：'
  '蓝图加粗句「认领时必须做品牌与分类映射」——没有映射的规则建不出来，认领也就无从谈起。';

CREATE INDEX listing_collect_claim_rule_recent ON listing_collect_claim_rule(tenant_id, created_at DESC, id);
CREATE INDEX listing_collect_claim_rule_state  ON listing_collect_claim_rule(tenant_id, state);

-- ===========================================================================
-- 5) listing_collect_claim_result · 认领结果（商品草稿载荷，租户业务表）
-- ===========================================================================
-- 蓝图输出句「**认领后的商品草稿**」—— 落在这里。
-- 🔴 这是**草稿载荷**，不是 catalog 里的 spu / sku 行：落库归 03 建档（3.1.1.1）。
CREATE TABLE listing_collect_claim_result (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  item_id           uuid NOT NULL,
  rule_id           uuid NOT NULL,
  -- 🔴 命名规则的产出（SPU 草稿名）
  spu_name          text NOT NULL,
  -- 🔴 映射结果（认领的硬要求：品牌与分类都必须映射出来）
  target_category_code varchar(100) NOT NULL,
  target_brand_code varchar(100) NOT NULL,
  -- 映射依据（草稿里的原始品牌 / 原始类目，原文照抄，便于复核）
  source_brand      text NOT NULL DEFAULT '',
  source_category   text NOT NULL DEFAULT '',
  -- 🔴 SPU 草稿载荷（预填内容：标题 / 描述 / 图片 URL / 规格 / 属性 / 来源）
  spu_draft         jsonb NOT NULL,
  -- 🔴 SKU 草稿行（按草稿的规格键值生成；载荷里不含 catalog 的属性 id —— 那是 03 的事实）
  sku_drafts        jsonb NOT NULL DEFAULT '[]'::jsonb,
  sku_draft_count   int NOT NULL DEFAULT 0 CHECK(sku_draft_count>=0),
  -- 🔴 恒为 PENDING_BUILD：商品草稿的**落库**归 03 建档（3.1.1.1），
  --    本模块**不跨服务写 catalog 的表**，也不假装建档已完成。
  build_state       text NOT NULL DEFAULT 'PENDING_BUILD' CHECK(build_state='PENDING_BUILD'),
  build_note        text NOT NULL DEFAULT '',
  claimed_at        timestamptz NOT NULL,
  claimed_by        uuid,
  actor_id          uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个条目只认领一次（重复认领要走「重新入箱」而不是改写历史）
  UNIQUE(tenant_id, item_id),
  FOREIGN KEY (tenant_id, item_id) REFERENCES listing_collect_box_item(tenant_id, id) ON DELETE CASCADE,
  -- 规则被删除时不允许（RESTRICT）：认领结果必须能回溯「用的是哪条规则」
  FOREIGN KEY (tenant_id, rule_id) REFERENCES listing_collect_claim_rule(tenant_id, id),
  CHECK(btrim(spu_name)<>''),
  CHECK(btrim(target_category_code)<>''),
  CHECK(btrim(target_brand_code)<>''),
  CHECK(jsonb_typeof(spu_draft) = 'object'),
  CHECK(jsonb_typeof(sku_drafts) = 'array')
);
COMMENT ON TABLE listing_collect_claim_result IS
  '4.4.1.3 · 认领结果 = 认领后的**商品草稿载荷**（已映射品牌与分类、已按命名规则命名、已预填内容）。'
  '🔴 不是 catalog 的 SPU / SKU 行：落库归 03 建档（3.1.1.1），本模块不跨服务写表。';

CREATE INDEX listing_collect_claim_result_recent ON listing_collect_claim_result(tenant_id, claimed_at DESC, id);
CREATE INDEX listing_collect_claim_result_rule   ON listing_collect_claim_result(tenant_id, rule_id);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V171 / V190 / V202 / V204 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_collect_box_tag','listing_collect_box_item',
                             'listing_collect_box_item_tag','listing_collect_claim_rule',
                             'listing_collect_claim_result'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：按**负责人 / 部门**收敛（与 V202 / V204 同一纪律）
-- ---------------------------------------------------------------------------
-- ALL 全租户可见 / SELF 只看自己建的 / DEPARTMENT 看本部门的。
-- SELF 语义要求 owner_id 非空 ⇒ 建标签 / 建规则 / 入箱时必须写入 owner_id（app.actor）。
CREATE POLICY data_scope ON listing_collect_box_tag AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

CREATE POLICY data_scope ON listing_collect_claim_rule AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 🔴 条目的可见性**随它引用的采集草稿收敛**（草稿的可见性又随它的采集任务收敛）——
--    也就是说：能看到那份草稿，就能看到它在箱子里的条目；看不到，就看不到。
--    这条链上**不另存一份** owner_id/department_id，避免两份归属打架。
CREATE POLICY data_scope ON listing_collect_box_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_collect_draft d
                 WHERE d.tenant_id=listing_collect_box_item.tenant_id
                   AND d.id=listing_collect_box_item.draft_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_collect_draft d
                 WHERE d.tenant_id=listing_collect_box_item.tenant_id
                   AND d.id=listing_collect_box_item.draft_id));

-- 标签关联的可见性随条目收敛
CREATE POLICY data_scope ON listing_collect_box_item_tag AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_collect_box_item i
                 WHERE i.tenant_id=listing_collect_box_item_tag.tenant_id
                   AND i.id=listing_collect_box_item_tag.item_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_collect_box_item i
                 WHERE i.tenant_id=listing_collect_box_item_tag.tenant_id
                   AND i.id=listing_collect_box_item_tag.item_id));

-- 认领结果的可见性随条目收敛
CREATE POLICY data_scope ON listing_collect_claim_result AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_collect_box_item i
                 WHERE i.tenant_id=listing_collect_claim_result.tenant_id
                   AND i.id=listing_collect_claim_result.item_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_collect_box_item i
                 WHERE i.tenant_id=listing_collect_claim_result.tenant_id
                   AND i.id=listing_collect_claim_result.item_id));

-- ---------------------------------------------------------------------------
-- 认领结果**只追加**：已认领的结果不允许被 UPDATE 改写
-- ---------------------------------------------------------------------------
-- 认领结果是一次映射决策的留痕（「这份草稿按哪条规则、映射成了什么品牌与分类」）。
-- 改写它等于把历史改掉 —— 要重新认领请重新入箱。
CREATE FUNCTION refuse_listing_collect_claim_result_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing collect claim result is an append-only fact and must not be updated' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_collect_claim_result_no_update BEFORE UPDATE ON listing_collect_claim_result
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_collect_claim_result_update();

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V207 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 标签 / 条目 / 标签关联 / 认领规则：租户自己的东西，可增删改查。
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_collect_box_tag      TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_collect_box_item      TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_collect_box_item_tag  TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_collect_claim_rule    TO channel_app;

-- 🔴 认领结果只授 SELECT + INSERT：
--   * 认领结果是**只追加**的留痕（上面的触发器也钉死了 UPDATE）；
--   * DELETE 权限必须留着 —— 条目被批量删除时，靠 ON DELETE CASCADE 连带清理。
GRANT SELECT,INSERT,DELETE ON listing_collect_claim_result TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表。
REVOKE ALL ON listing_collect_box_tag, listing_collect_box_item, listing_collect_box_item_tag,
               listing_collect_claim_rule, listing_collect_claim_result FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：复用 identity 已注册的 channel.operation.*（identity 服务零改动）
-- ---------------------------------------------------------------------------
-- 采集箱属于「平台操作」语义，与 4.1.1.1 列表、4.4.1.1 链接采集、4.4.1.2 批量采集、
-- 4.4.2.1 商品认领、4.3.1.2 批量发布同族，因此 identity 服务零改动。
