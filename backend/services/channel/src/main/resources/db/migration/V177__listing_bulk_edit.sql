-- ===========================================================================
-- 4.2.1.2 批量编辑（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.2.1.2 批量编辑
--   - 职责：对一批 listing 执行统一的字段修改（追加/替换/正则替换标题、批量换图、统一加卖点、
--           批量改类目），支持按平台差异化模板与操作预览（改前 vs 改后）。
--   - 输入：目标 listing 集合；批量操作定义；预览确认。
--   - 输出：批量修改草稿（待发布）；修改记录。
--   - 关系：上游 = 4.1.1.1；下游 = 4.3.1.2。
--           **批量编辑只改草稿不直接改线上**，必须走发布流程——这是"误改全店标题"事故的唯一防线。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本迁移最重要的一件事：草稿态与线上态是两个不同的存储
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是整个模块的**核心不变量**，它的物理落法是**换一张表**，不是加一个字段：
--
--   * 线上态 = V171 的 `listing`（`title` / `category_path` / `platform_category_id`）。
--     本迁移**零 ALTER、零 UPDATE** 这张表的内容列。
--   * 草稿态 = 本迁移新建的 `listing_content_draft`。批量编辑**只往这张表写**。
--
-- 为什么必须是两张表而不是 `listing.draft_title` + `listing.title`：
--   * 同表两列意味着「发布」是一次 UPDATE，而 UPDATE 天然可被任何一条忘了加 WHERE 的语句
--     一次性打穿 —— 「误改全店标题」事故正是这么发生的。
--   * 分表之后，"草稿改了线上"需要**跨表写入**，跨表写入在本仓只能出现在发布流程（4.3.1.2）
--     里那条被显式 review 的路径上。多一张表本身就是一次物理拦截。
--
-- 🔴 并且不只靠"我们没写那条 UPDATE"：本迁移给 `listing` 加一条 BEFORE UPDATE 触发器
--    `guard_listing_live_content`，只要事务里打开了 `app.draft_only`，对 listing 内容列的
--    任何改动**由 DB 直接拒**。批量编辑的每次 apply 事务都会打开这个标记，
--    于是"将来某个人在批量编辑代码路径里手滑加了句 update listing set title=..."
--    这类事故会在测试期就炸掉，而不是等到全店标题被改完。
--    （4.3.1.2 的发布流程不打开这个标记，它本来就要写线上。）
--
-- ---------------------------------------------------------------------------
-- 二、🔴 诚实边界：卖点与图片的**线上基线**不在本模块
-- ---------------------------------------------------------------------------
-- V171 的 `listing` 只有 title / category_path / platform_category_id 三个内容列，
-- **没有 bullets（卖点）也没有 images（图片）** —— 那两个是 4.2.1.1 刊登编辑器的内容域，
-- 而 4.2.1.1 在集成头**尚未交付**。
--
-- 本模块因此如实处理，而不是伪造一个基线：
--   * TITLE / CATEGORY_PATH / PLATFORM_CATEGORY_ID 的 before 值来自**线上表**，是真的；
--   * BULLETS / IMAGES 的 before 值来自**已有草稿**；没有草稿就是空集合 —— 这是真的空，
--     不是占位符。`/capabilities` 显式声明这两个维度 `onlineBaselineAvailable=false`
--     并写明归属模块与影响，前端据此显示"线上基线未接入"，**不编一个假基线**。
--
-- 「统一加卖点」与「批量换图」仍然是真的批量操作：真实目标集合、真实逐条 before/after、
-- 真实落草稿、真实进修改记录 —— 缺的只是"线上原本长什么样"这一个上游事实。
--
-- ---------------------------------------------------------------------------
-- 三、六种操作与「改后 vs 改前」
-- ---------------------------------------------------------------------------
-- 职责句逐词对应六种 operation_type（不是四种，职责句列了六件事）：
--   追加标题      TITLE_APPEND
--   替换标题      TITLE_REPLACE
--   正则替换标题  TITLE_REGEX_REPLACE
--   批量换图      REPLACE_IMAGES
--   统一加卖点    BULLET_APPEND
--   批量改类目    REPLACE_CATEGORY
--
-- 预览不是"再跑一遍"，而是**同一次计算**的结果：preview 与 apply 落的是同一张
-- `listing_bulk_change` 表的同一批行，apply 只是把 PREVIEWED 的这批行推进成 APPLIED
-- 并把它们写进草稿。集成测试逐条断言"预览看到的 before/after == 落库后的 before/after"，
-- 杜绝"预览一套、执行另一套"这种最伤信任的偏差。
--
-- `listing_bulk_change.outcome` 四值，把"为什么这条没被改"逐条说清：
--   APPLIED             拟改值与原值不同，已进草稿
--   NO_CHANGE           规则跑完结果与原值逐字相同（不制造无意义改动）
--   SKIPPED_LIMIT       改后超出平台长度上限 / 条数上限 —— **显式跳过并写明，绝不静默截断**
--   SKIPPED_NO_BASELINE 该字段没有可比基线（卖点 / 图片的线上基线未接入）
--
-- ---------------------------------------------------------------------------
-- 四、正则替换的 ReDoS 防线是双层的
-- ---------------------------------------------------------------------------
-- 只在服务层校验是不够的（有人可以绕过 HTTP 直接调服务），
-- 所以「什么样的正则允许进这条链路」在两处被拦：
--   * 服务层 `SafeRegex`：静态结构分析（嵌套量词 / 反向引用 / 相邻无界重复）
--     + **真跑一遍对抗语料并计时**，超时即拒（守护线程，可被放弃）。
--   * DB 层 `guard_bulk_change_not_oversized`：before/after 长度上限，
--     保证"灾难级回溯"连"算出一条巨长字符串再写库"这条路都走不到。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本模块**无任何金额列**：批量编辑职责句里的六件事没有一件是改价（改价归 05 定价，
-- 与 4.1.1.1 批量改价被显式拒绝同一纪律）。存在的数量列只有 `image_count` /
-- `bullet_count` 这类**计数**，它们是 int 计数不是业务数量，不参与任何金额计算；
-- 真正需要 NUMERIC(19,6) 的业务数量（平台可售量等）在 V171 的 listing 上，不在本表。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒（4.1.1.1 实测踩过）。

-- ---------------------------------------------------------------------------
-- listing_bulk_template · 按平台差异化的批量模板
-- ---------------------------------------------------------------------------
-- 「支持按平台差异化模板」：同一套批量规则在 Amazon 允许 200 字标题、在 TikTok 只允许 60 字，
-- 上限是**模板按平台配的**（`title_max_length`），不是服务层一个写死的常量 ——
-- 写死成 500 会让"发到 TikTok 的标题超长"直到平台驳回才发现。
-- 模板还决定 operation_type 与参数，操作执行时逐平台切分（`platform_splits`）。
CREATE TABLE listing_bulk_template (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 全局只读码表（channel V1），不跨服务
  platform_id   uuid NOT NULL REFERENCES platform(id),
  code          varchar(64) NOT NULL,
  name          varchar(200) NOT NULL,
  operation_type text NOT NULL
                CHECK(operation_type IN ('TITLE_APPEND','TITLE_REPLACE','TITLE_REGEX_REPLACE',
                                         'REPLACE_IMAGES','BULLET_APPEND','REPLACE_CATEGORY')),
  -- 参数原文：{find,replace,pattern,replacement,images,bullets,categoryPath,platformCategoryId}
  params        jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 平台差异化上限：按平台配而不是服务层写死；与 listing.title 的 varchar(500) 对齐
  title_max_length integer NOT NULL DEFAULT 500 CHECK(title_max_length BETWEEN 1 AND 500),
  image_max_count   integer NOT NULL DEFAULT 9  CHECK(image_max_count BETWEEN 1 AND 20),
  bullet_max_count  integer NOT NULL DEFAULT 10 CHECK(bullet_max_count BETWEEN 1 AND 20),
  enabled       boolean NOT NULL DEFAULT true,
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, platform_id, code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);
COMMENT ON TABLE listing_bulk_template IS
  '4.2.1.2 · 按平台差异化的批量编辑模板（标题长度 / 图片数 / 卖点数上限按平台配，不写死在服务层）。';

-- ---------------------------------------------------------------------------
-- listing_content_draft · 🔴 草稿态存储（与线上 listing 表物理分离）
-- ---------------------------------------------------------------------------
-- 「输出：批量修改草稿（待发布）」的落地表。**批量编辑只写这张表，绝不写 listing。**
--
-- 🔴 touched_fields：只有被本次批量操作真正拟改的字段才在这里出现，其余列保持 NULL。
--    如果把没碰过的字段也复制一份快照进草稿，发布时会把"当时线上是 A、编辑时是 B"的
--    那个 B 一并覆盖回去 —— 那是一次**用户根本没要求**的线上改动，正是本模块要防的那类事故。
--    下面的 CHECK 把「touched_fields 与哪些列有值」钉成一一对应，两处不可能各说各话。
CREATE TABLE listing_content_draft (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  listing_id    uuid NOT NULL,
  -- 🔴 以下五列全是**提议值**；NULL = 本次没拟改这个字段（沿用线上值）
  title         varchar(500),
  bullets       text[],
  images        jsonb,
  category_path varchar(500),
  platform_category_id varchar(128),
  touched_fields text[] NOT NULL DEFAULT '{}',
  -- DRAFT 待发布 / PUBLISHED 已由 4.3.1.2 发布 / DISCARDED 已废弃
  state         text NOT NULL DEFAULT 'DRAFT'
                CHECK(state IN ('DRAFT','PUBLISHED','DISCARDED')),
  -- 草稿是从哪个线上版本派生的：发布前线上被改过（version 前进）就能检出草稿已过期
  base_version  bigint NOT NULL DEFAULT 0,
  base_listing_updated_at timestamptz NOT NULL DEFAULT now(),
  -- 哪一次批量操作最后改的这份草稿
  last_operation_id uuid,
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 草稿必须来自一次真实的批量操作，不允许凭空插一条草稿
  CHECK(state<>'DRAFT' OR last_operation_id IS NOT NULL),
  -- 🔴 touched_fields 与实际有值的列一一对应（有值必在列表里，在列表里必有值）
  CHECK((('TITLE' = ANY(touched_fields))             = (title IS NOT NULL))
     AND (('BULLETS' = ANY(touched_fields))           = (bullets IS NOT NULL))
     AND (('IMAGES' = ANY(touched_fields))            = (images IS NOT NULL))
     AND (('CATEGORY_PATH' = ANY(touched_fields))     = (category_path IS NOT NULL))
     AND (('PLATFORM_CATEGORY_ID' = ANY(touched_fields)) = (platform_category_id IS NOT NULL))),
  -- 图片集合必须是 JSON 数组（'{}' 不是合法的图片集合）
  CHECK(images IS NULL OR jsonb_typeof(images)='array'),
  -- 标题非空：一条空标题的草稿发出去必然被平台驳回
  CHECK(title IS NULL OR btrim(title)<>''),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_content_draft IS
  '4.2.1.2 · 🔴 批量修改草稿（待发布）。与线上 listing 表物理分离：批量编辑只写这张表，'
  'touched_fields 之外的列为 NULL 表示"本次没拟改"，发布由 4.3.1.2 走显式流程。';

-- ---------------------------------------------------------------------------
-- listing_bulk_operation · 批量操作定义与生命周期（输入：批量操作定义 / 预览确认）
-- ---------------------------------------------------------------------------
-- preview 就落库为 PREVIEWED（而不是"预览什么都不写"）：预览是一条需要留痕的事实 ——
-- "当时系统告诉我们会怎么改"是事后追责与复盘的凭据。
CREATE TABLE listing_bulk_operation (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  template_id   uuid,
  operation_type text NOT NULL
                CHECK(operation_type IN ('TITLE_APPEND','TITLE_REPLACE','TITLE_REGEX_REPLACE',
                                         'REPLACE_IMAGES','BULLET_APPEND','REPLACE_CATEGORY')),
  params        jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 目标集合快照（冻结当时的选取，便于回溯"当时选的是哪些"）
  listing_ids   uuid[] NOT NULL,
  requested_count integer NOT NULL,
  applied_count integer NOT NULL DEFAULT 0,
  skipped_count integer NOT NULL DEFAULT 0,
  state         text NOT NULL DEFAULT 'PREVIEWED'
                CHECK(state IN ('PREVIEWED','APPLIED','DISCARDED')),
  -- 🔴 按平台差异化模板切分的结果：[{platformCode, templateId, titleMaxLength, listingIds}]
  platform_splits jsonb NOT NULL DEFAULT '[]'::jsonb,
  actor_id      uuid,
  reason        varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  applied_at    timestamptz,
  UNIQUE(tenant_id, id),
  CHECK(requested_count>=0 AND applied_count>=0 AND skipped_count>=0),
  -- 受影响 + 跳过不可能超过请求数
  CHECK(applied_count+skipped_count<=requested_count),
  -- APPLIED 必须有时间戳；APPLIED 之后不允许再回到 PREVIEWED
  CHECK(state<>'APPLIED' OR applied_at IS NOT NULL),
  FOREIGN KEY(tenant_id, template_id) REFERENCES listing_bulk_template(tenant_id, id)
);
COMMENT ON TABLE listing_bulk_operation IS
  '4.2.1.2 · 一次批量操作（预览落库为 PREVIEWED，确认后推进为 APPLIED）；修改记录挂在它下面。';

-- ---------------------------------------------------------------------------
-- listing_bulk_change · 🔴 逐条「改前 vs 改后」（输出：修改记录）
-- ---------------------------------------------------------------------------
-- 这一张表同时是**预览的数据源**与**修改记录**：preview 与 apply 读的是同一批行，
-- 所以不存在"预览一套、执行另一套"。
CREATE TABLE listing_bulk_change (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  operation_id  uuid NOT NULL,
  listing_id    uuid NOT NULL,
  field         text NOT NULL
                CHECK(field IN ('TITLE','BULLETS','IMAGES','CATEGORY_PATH','PLATFORM_CATEGORY_ID')),
  before_value  text NOT NULL,
  after_value   text NOT NULL,
  -- false = 规则跑完与原值逐字相同（没有制造改动）
  changed       boolean NOT NULL,
  outcome       text NOT NULL
                CHECK(outcome IN ('APPLIED','NO_CHANGE','SKIPPED_LIMIT','SKIPPED_NO_BASELINE')),
  reason        varchar(300) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一次操作对同一条 listing 的同一个字段只留一行（一个字段被两条规则改两次 = 说不清最终值）
  UNIQUE(tenant_id, operation_id, listing_id, field),
  -- 🔴 声称改了却前后一字不差，或声称没改却前后不同，两种自相矛盾都直接拒
  CHECK((outcome='APPLIED') = changed),
  FOREIGN KEY(tenant_id, operation_id) REFERENCES listing_bulk_operation(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_bulk_change IS
  '4.2.1.2 · 🔴 逐条改前/改后记录；预览与执行共用这批行，"预览一套执行另一套"在结构上不可能。';

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX listing_bulk_template_platform ON listing_bulk_template(tenant_id, platform_id, enabled);
-- 🔴 「一条 listing 同时只有一份**待发布**草稿」用**部分唯一索引**表达，而不是整表 UNIQUE(tenant_id,listing_id)：
--   整表 UNIQUE 会让"上一份草稿发布/废弃之后再做一次批量编辑"直接插不进去 —— 运营会发现第二次
--   批量编辑静默失效（ON CONFLICT 命中后什么都不做，草稿没生成也没报错）。
--   部分索引把约束收在真正需要它的那部分行上：PUBLISHED / DISCARDED 的草稿作为历史留档，
--   同一 listing 可以再开一份新的 DRAFT，两份都在库里可查。
CREATE UNIQUE INDEX listing_content_draft_one_active ON listing_content_draft(tenant_id, listing_id)
  WHERE state='DRAFT';
CREATE INDEX listing_content_draft_pending    ON listing_content_draft(tenant_id, updated_at DESC, id);
CREATE INDEX listing_content_draft_operation  ON listing_content_draft(tenant_id, last_operation_id);
CREATE INDEX listing_bulk_operation_recent    ON listing_bulk_operation(tenant_id, created_at DESC, id);
CREATE INDEX listing_bulk_operation_type      ON listing_bulk_operation(tenant_id, operation_type);
CREATE INDEX listing_bulk_change_operation    ON listing_bulk_change(tenant_id, operation_id);
CREATE INDEX listing_bulk_change_listing      ON listing_bulk_change(tenant_id, listing_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴🔴 核心不变量：批量编辑事务期间，线上 listing 的内容列一律不可写。
--     服务层 apply 事务打开 app.draft_only；打开期间任何对 title / category_path /
--     platform_category_id 的改动由 DB 拒（23514）。这条约束不依赖"服务层没写那条 SQL"，
--     所以将来有人在批量编辑路径里手滑加了 update listing set title=… 会在测试期炸掉。
--     4.3.1.2 的发布流程不打开这个标记 —— 它本来就要写线上。
CREATE FUNCTION guard_listing_live_content() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF coalesce(nullif(current_setting('app.draft_only',true),'')::boolean,false) IS NOT TRUE THEN
    RETURN NEW;
  END IF;
  IF NEW.title IS DISTINCT FROM OLD.title
     OR NEW.category_path IS DISTINCT FROM OLD.category_path
     OR NEW.platform_category_id IS DISTINCT FROM OLD.platform_category_id THEN
    RAISE EXCEPTION
      'Bulk edit must never write live listing content (title/category_path/platform_category_id); drafts live in listing_content_draft and publishing goes through 4.3.1.2'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_live_content BEFORE UPDATE OF title, category_path, platform_category_id ON listing
  FOR EACH ROW EXECUTE FUNCTION guard_listing_live_content();

-- (b) 草稿必须由一次批量操作产生，且 DISCARED/PUBLISHED 是终态（改回 DRAFT 等于让一份
--     已经发布或废弃的草稿复活，改它写的到底算哪一次改出来的就答不上来了）。
CREATE FUNCTION guard_content_draft_provenance() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.state IN ('PUBLISHED','DISCARDED') AND NEW.state='DRAFT' THEN
    RAISE EXCEPTION 'Draft % is % and cannot return to DRAFT', OLD.id, OLD.state USING ERRCODE='23514';
  END IF;
  -- 改过的字段一旦定了就不许从 touched_fields 里抹掉：抹掉等于"这次改过它"这段历史消失
  IF TG_OP='UPDATE' AND OLD.last_operation_id IS NOT NULL AND NEW.last_operation_id IS NOT NULL
     AND NEW.last_operation_id <> OLD.last_operation_id THEN
    IF (SELECT count(*) FROM unnest(OLD.touched_fields) f WHERE NOT (f = ANY(NEW.touched_fields))) > 0 THEN
      RAISE EXCEPTION 'Draft % cannot drop previously touched fields', OLD.id USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_draft_provenance BEFORE UPDATE ON listing_content_draft
  FOR EACH ROW EXECUTE FUNCTION guard_content_draft_provenance();

-- (c) 🔴 ReDoS 兜底的第二层：改前/改后长度上限。
--     即便服务层的静态分析漏判了一个灾难级回溯模式，算出来的巨型字符串也进不了库。
CREATE FUNCTION guard_bulk_change_not_oversized() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF char_length(NEW.before_value)>20000 OR char_length(NEW.after_value)>20000 THEN
    RAISE EXCEPTION
      'Bulk change % produced an oversized value (% / % chars); catastrophic-backtracking patterns must be rejected upstream',
      NEW.id, char_length(NEW.before_value), char_length(NEW.after_value) USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER bulk_change_not_oversized BEFORE INSERT OR UPDATE ON listing_bulk_change
  FOR EACH ROW EXECUTE FUNCTION guard_bulk_change_not_oversized();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V136 / V140 / V141 / V143 / V146 / V171 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_bulk_template','listing_content_draft',
    'listing_bulk_operation','listing_bulk_change' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围（RESTRICTIVE，与 V171 同构：可见性随店铺负责人 / 部门收敛）
-- ---------------------------------------------------------------------------
-- 🔴 批量操作尤其需要这条：目标集合里的每一条 listing 都必须落在调用者的店铺范围内，
-- 否则"用户勾了 100 条、其中 30 条不属于他"会被服务层当正常批量执行。
-- 模板是租户级资产、不挂店铺维度（同 3.4.1.1 素材库：租户共享资产），
-- 所以它只有 isolation 策略、没有 data_scope 策略。
CREATE POLICY data_scope ON listing_content_draft AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_draft.tenant_id
             AND l.id=listing_content_draft.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_draft.tenant_id
             AND l.id=listing_content_draft.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_bulk_operation AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_bulk_operation.tenant_id
             AND l.id = ANY(listing_bulk_operation.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_bulk_operation.tenant_id
             AND l.id = ANY(listing_bulk_operation.listing_ids)
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_bulk_change AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_bulk_change.tenant_id
             AND l.id=listing_bulk_change.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_bulk_change.tenant_id
             AND l.id=listing_bulk_change.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V171 的条件建角色，保证迁移可重复执行）
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
  listing_bulk_template, listing_content_draft,
  listing_bulk_operation, listing_bulk_change TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V171 同口径）。
REVOKE ALL ON
  listing_bulk_template, listing_content_draft,
  listing_bulk_operation, listing_bulk_change FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：批量编辑属于「平台操作」语义，与 4.1.1.1 listing 列表、
-- 10.1.1.2 渠道配置、2.1.2.1 平台枚举映射同族，identity 服务零改动。
