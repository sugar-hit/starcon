-- ===========================================================================
-- 4.4.2.1 商品认领（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.4.2.1 商品认领
--   - 职责：把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，
--           生成 B 店铺的新 listing 草稿。
--   - 输入：源 listing；目标店铺与站点；保留/重写的内容字段选择。
--   - 输出：目标店铺的 listing 草稿 + 待发布任务。
--   - 关系：上游 = 4.1.1.2；下游 = 4.3.1.1。**新店铺 listing 是全新平台资源**，
--           需重新过平台审核；源 listing 不受影响。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 归属 channel 而不是 catalog 的理由（与 4.1.1.1 / 4.1.1.2 / 4.3.1.2 / 4.3.2.1 同一先例）
-- ---------------------------------------------------------------------------
-- 卷 04 在 blueprint README 的 L2 表里写的是「catalog + channel」（列表首项 catalog），
-- 编排脚本据此排了这条车道。但**已交付的同族事实全部在 channel 服务**：
--   * 4.1.1.1 Listing 列表   → channel `V171__listing_list.sql`（建 `listing` / `listing_variant`）
--   * 4.1.1.2 Listing 详情   → channel `V175__listing_detail.sql`（`listing_content_snapshot`）
--   * 4.3.1.2 批量发布       → channel `V183__listing_publish_batch.sql`（`publish_batch` / `publish_task`）
--   * 4.3.2.1 内容回写       → channel `V187__listing_content_writeback.sql`
--
-- 本模块要读**源 listing**、要写**目标店铺 listing 草稿**，源与目标都是 channel 自己的事实：
--   * `listing` 必须按**店铺 / 站点**筛选与收敛可见性，而 `shop` / `shop_marketplace` 是
--     channel V1 的事实，且带 `data_scope` RESTRICTIVE 策略读 `app.grants`。
--   * AGENTS.md 禁止跨服务数据库访问。把本模块放 catalog 就必须为了店铺维度数据范围去读
--     channel 的表 —— 那是跨服务 SQL，比「多写一个 JOIN」严重得多。
--   * 目标草稿要挂进 V183 的发布队列（`publish_batch` / `publish_task`），那两张表也在 channel。
--
-- 本迁移**纯新建两张表、零 ALTER 任何既有表**（与 V177 / V183 / V187 同一纪律）：
--   * V171 `listing` / `listing_variant` —— 源与目标都是平台商品（读 + 写）
--   * V175 `listing_content_snapshot` —— 复用的内容事实（读 + 写目标的那一份）
--   * V176 `listing_mapping` / `listing_mapping_version` —— 复用的映射事实（读 + 写目标的那一份）
--   * V183 `publish_batch` / `publish_task` —— **待发布任务复用它**（写，不新建平行表）
--   * V1   `shop` / `shop_marketplace` / `marketplace` —— 目标店铺与站点（读）
--
-- 🔴 跨服务：03 商品主数据（catalog）的 `sku` 只经逻辑 id `internal_sku_id` 引用，
-- **不建 FK、不复制 catalog 任何一列**（编码 / 名称 / 规格一律不落在本表）——
-- 与 V1 `shop.legal_entity_id`、V171 `listing_variant.internal_sku_id` 同一纪律。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 边界一：源 listing 不受影响（DB 级钉死，不靠服务层自觉）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**源 listing 不受影响**」。这条不是「服务层没去改它」就够了 ——
-- 服务层漏写一句 update，「源 listing 被改掉」这件事就再也说不清了。
--
-- 所以本迁移用**两个 DB 触发器**把它钉死：
--   (a) `guard_claim_source_untouched`（AFTER INSERT ON listing_claim）
--       —— 认领记录落库的那一刻，源 listing 的 `status` / `review_status` / `version`
--          必须与认领时记下的快照**逐字相等**。不相等就说明有人在认领过程中动过源商品，
--          这次认领直接被拒（23514）。这条同时兼作乐观并发：源商品在认领中途被改过，
--          本次认领的"复用"就已经不是它了。
--   (b) 认领**只 INSERT**，不 UPDATE 源 listing。`listing_claim` 上的不可改写触发器
--       钉住源 / 目标身份（`source_listing_id` / `target_listing_id` / 两个 `shop_id`）
--       不可就地改写 —— 一次认领的"从哪到哪"必须是当时那个事实。
--
-- 集成测试另有**逐字断言**：认领前后源 listing 的 status / review_status / version /
-- updated_at 与 `listing_status_event` 条数**完全不变**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 边界二：目标是草稿态，**尚未过平台审核**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**新店铺 listing 是全新平台资源，需重新过平台审核**」。
--
-- 这条在**四个层面**同时成立，缺一层就有一层会骗人：
--   ① `listing` 行本身：`status='DRAFT'` + `review_status='NOT_SUBMITTED'`。
--      平台可售状态是 `UNKNOWN`（新商品在平台上还不存在，没有任何平台侧可售事实），
--      评分 / 评论数 / 币种 / 上架时间全部为 NULL —— **不复制源 listing 的平台事实**：
--      源商品在 A 店铺的评分与币种是**源商品的平台事实**，搬到 B 店铺后它们一个都不成立。
--   ② DB 触发器 `guard_claim_source_untouched` 顺带断言目标 listing 此刻仍是
--      DRAFT / NOT_SUBMITTED —— 认领记录与「已发布」不可能同时成立。
--   ③ **不写 `listing_publish_record`**：那张表是 V175 的「发布动作结果留痕」，
--      写一条进去等于宣称"发过了" —— 而本模块**没有发过任何东西**。
--   ④ 待发布任务只**入队**（`publish_task.state='PENDING'`），实际派发由 4.3.1.2
--      批量发布的既有机制（V183）承接。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 边界三：待发布任务**复用 V183**，不另建平行发布任务表
-- ---------------------------------------------------------------------------
-- 「输出：目标店铺的 listing 草稿 + **待发布任务**」。这里的"待发布任务"就是 V183 的
-- `publish_task`（一张批次 + 一条 PENDING 任务），**不是**本模块自建的另一张任务表：
--   * 另建一张 = 平台上会出现两套"待发布队列"，4.3.1.2 的限流（在途 / 窗口）只管一套，
--     另一套的任务会被绕过配额直接发出去 —— 那正是 V183「批量任务必须限流」要防的事。
--   * 复用 = 本模块的草稿天然落在 4.3.1.2 的批次里，运营在 `/listings/publish` 就能看到它、
--     暂停它、重排它、按各平台配额派发它。
--
-- ⚠️ `publish_task.draft_id` 指向 `listing_content_draft`（V177，4.2.1.2 批量编辑的产出）。
-- 刊登编辑器（4.2.1.1）与批量编辑（4.2.1.2）的内部内容草稿**尚未交付**，所以本模块
-- **不预建**那张表、也**不伪造**一条 `listing_bulk_operation` 来凑 `last_operation_id`
-- （V177 的 CHECK 明确要求草稿必须来自一次真实的批量操作，认领不是批量编辑）。
-- ⇒ 本模块登记的待发布任务 `draft_id` 为空，内容以**本次认领登记的内容快照**为准。
-- 这一点在 `/channel/listing-claims/capabilities` 里明写（available=false + ownerModule）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 「复用原商品内容与映射」复用的是**既有事实**，不另造一套内容表
-- ---------------------------------------------------------------------------
--   * 内容：`listing_content_snapshot`（V175）—— 源 listing 的当前内容快照。
--     目标 listing 另写**一份**当前快照（`source='MANUAL'`，即"人工登记"），
--     内容按本次的字段选择决定。它是 V175 已有的那张表，不是新表。
--   * 映射：`listing_variant`（V171）+ `listing_mapping` / `listing_mapping_version`（V176）——
--     目标的变体与映射**复用源的内部 SKU 逻辑 id**（`internal_sku_id`），
--     并按 V176 的既有规则写版本 1（`effective_from=now()`、`effective_to=NULL`）。
--
-- 🔴 平台类目 ID 与**站点**绑定，不是与店铺绑定，也不是与平台绑定：
--   源站点与目标站点**不同**时，源站点的 `platform_category_id` 在目标站点毫无意义，
--   照抄过去等于在目标 listing 上写一个**假的平台事实**。
--   ⇒ 站点相同时才允许「保留」；站点不同时该字段只能「重写」（显式给目标站点的类目 ID）
--     或「跳过」，并且 `listing_claim_field.note` 会写明原因。
--   同理，源 listing 的 `category_path`（人类可读的类目路径）是**可读的**，
--   跨站点保留它不构成假事实，但也要在 note 里提示复核。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本迁移**无任何金额列**：listing 售价归 05 定价（整卷未交付），与 V171 / V177 同一纪律。
-- 存在的数值列只有两个，都是**数量**：
--   * `variant_count`      integer  —— 复制的变体条数
--   * `mapped_variant_count` integer —— 其中已映射到内部 SKU 的条数
-- 变体的组件数量沿用 V171 `listing_variant.component_quantity numeric(19,6)`，
-- 映射的组件数量沿用 V176 `listing_mapping.component_quantity numeric(19,6)`。
-- **无 float / double 一列**。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**。
--
-- ===========================================================================
-- listing_claim · 一次认领（跨店搬家的操作留痕）
-- ===========================================================================
CREATE TABLE listing_claim (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  -- 源 listing（跨店搬家的"从"）
  source_listing_id      uuid NOT NULL,
  -- 目标 listing（本模块新建的草稿，"到"）
  target_listing_id      uuid NOT NULL,
  source_shop_id         uuid NOT NULL,
  target_shop_id         uuid NOT NULL,
  source_marketplace_id  uuid,
  target_marketplace_id  uuid NOT NULL REFERENCES marketplace(id),
  -- 本次复用的源内容快照（认领时源 listing 的当前内容）
  source_snapshot_id     uuid,
  source_language        varchar(16) NOT NULL DEFAULT '',
  -- 🔴 源侧状态快照：DB 触发器据此证明「认领没有碰过源 listing」
  --    （status / review_status / version 三者在认领前后必须逐字相等）
  source_status          text NOT NULL
                         CHECK(source_status IN ('DRAFT','ON_SALE','UNDER_REVIEW','REJECTED',
                                                 'DELISTED_BY_US','DELISTED_BY_PLATFORM','BLOCKED')),
  source_review_status   text NOT NULL
                         CHECK(source_review_status IN ('NOT_SUBMITTED','PENDING','APPROVED','REJECTED')),
  source_version         bigint NOT NULL DEFAULT 0,
  -- 🔴 认领状态：
  --    DRAFT     = 目标草稿已生成，尚未发布
  --    PUBLISHED = 目标 listing **已离开草稿态**（由 DB 触发器在 listing.status 变化时派生）
  --    ⚠️ PUBLISHED 只表示"不再是草稿"，**不代表平台已批准**：平台审核结果是
  --       4.3.1.1 / 4.3.2.2 的事实，本模块不写、也不猜。
  state                  text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED')),
  -- 目标 listing 的平台商品 ID 保留位：草稿未发布 ⇒ 平台尚未分配 ASIN / item_id。
  -- 用「CLAIM-PENDING:」前缀明示它是保留位而不是平台原文；真实 ID 由发布流程回写。
  target_external_item_id varchar(128) NOT NULL,
  -- 复制的变体 / 映射条数（如实记，0 不是"没有变体"而是"没有复制"）
  variant_count          integer NOT NULL DEFAULT 0 CHECK(variant_count>=0),
  mapped_variant_count   integer NOT NULL DEFAULT 0 CHECK(mapped_variant_count>=0),
  -- 待发布任务：复用 V183 的批次与任务（**不另建平行发布任务表**）
  publish_batch_id       uuid,
  publish_task_id        uuid,
  actor_id               uuid,
  note                   varchar(500) NOT NULL DEFAULT '',
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个目标 listing 只可能被认领一次
  UNIQUE(tenant_id, target_listing_id),
  -- 复合 FK 带 tenant_id ⇒ 跨租户挂载在物理上不可能
  FOREIGN KEY (tenant_id, source_listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY (tenant_id, target_listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY (tenant_id, source_shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY (tenant_id, target_shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY (tenant_id, source_snapshot_id) REFERENCES listing_content_snapshot(tenant_id, id),
  FOREIGN KEY (tenant_id, publish_batch_id) REFERENCES publish_batch(tenant_id, id),
  FOREIGN KEY (tenant_id, publish_task_id) REFERENCES publish_task(tenant_id, id),
  -- 「跨店搬家」的前提：源与目标必须是**两个不同的店铺**
  CHECK (source_listing_id <> target_listing_id),
  CHECK (source_shop_id <> target_shop_id),
  -- 映射条数不可能多于变体条数
  CHECK (mapped_variant_count <= variant_count)
);
COMMENT ON TABLE listing_claim IS
  '4.4.2.1 · 一次商品认领（跨店搬家）的操作留痕。源 listing 与目标 listing 是两条独立事实：'
  '本表只 INSERT，不改源；目标恒为草稿态（尚未过平台审核）。待发布任务复用 V183 的 publish_batch / publish_task。';

-- ===========================================================================
-- listing_claim_field · 逐字段的保留 / 重写决策（输入句「保留/重写的内容字段选择」）
-- ===========================================================================
-- 一次认领的"为什么目标草稿长这样"必须答得出来：哪些字段复用了源、哪些被重写、
-- 哪些被跳过、跳过的原因是什么。这张表就是那个答案。
CREATE TABLE listing_claim_field (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  claim_id      uuid NOT NULL,
  -- 🔴 可选字段集与 V175 `listing_content_snapshot` + V171 `listing` 的列**逐字对齐**，
  --    不含任何价格 / 库存 / 评分字段（售价归 05、内部可售量归 07、评分是平台侧事实）。
  field         text NOT NULL
                CHECK(field IN ('TITLE','BULLETS','IMAGES','DESCRIPTION','A_PLUS','VIDEO_URL',
                                 'CATEGORY_PATH','PLATFORM_CATEGORY_ID',
                                 'CATEGORY_ATTRIBUTES','COMPLIANCE_STATEMENTS')),
  -- KEEP    = 复用源的值
  -- REWRITE = 用调用方给的值重写
  -- SKIP    = 该字段不写入目标草稿（例如平台类目 ID 跨站点不可复用且未给重写值）
  decision      text NOT NULL CHECK(decision IN ('KEEP','REWRITE','SKIP')),
  -- 源值来自哪里：平台内容快照 / listing 行 / 源侧根本没有这个字段
  source_origin text NOT NULL
                CHECK(source_origin IN ('PLATFORM_SNAPSHOT','LISTING','NOT_PRESENT')),
  -- 源值原文（JSON 字段存 JSON 原文；不翻译、不裁剪）
  source_value  text,
  -- 落到目标草稿上的值（SKIP 时为 NULL）
  target_value  text,
  -- 例如「平台类目 ID 与站点绑定，跨店认领后必须重新映射到目标站点」
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, claim_id, field),
  FOREIGN KEY (tenant_id, claim_id) REFERENCES listing_claim(tenant_id, id) ON DELETE CASCADE,
  -- 跳过就没有目标值；不跳过就一定有一个目标值（可以是空串 = 清空该字段）
  CHECK ((decision='SKIP') = (target_value IS NULL)),
  -- 源侧没有这个字段时，源值必须为空（不能一边说"没有"一边给值）
  CHECK ((source_origin='NOT_PRESENT') = (source_value IS NULL))
);
COMMENT ON TABLE listing_claim_field IS
  '4.4.2.1 · 逐字段的保留 / 重写 / 跳过决策。source_origin 记清源值来自平台内容快照还是 listing 行，'
  'note 记清跳过原因（例如平台类目 ID 跨站点不可复用）。';

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
-- 认领历史按时间倒序取（列表页默认排序）
CREATE INDEX listing_claim_recent      ON listing_claim(tenant_id, created_at DESC, id);
-- 源 / 目标两个方向都要能查（4.1.1.2 的「这个商品被认领到哪去了 / 从哪来的」）
CREATE INDEX listing_claim_source      ON listing_claim(tenant_id, source_listing_id);
CREATE INDEX listing_claim_target      ON listing_claim(tenant_id, target_listing_id);
CREATE INDEX listing_claim_target_shop ON listing_claim(tenant_id, target_shop_id);
CREATE INDEX listing_claim_state       ON listing_claim(tenant_id, state);
CREATE INDEX listing_claim_publish     ON listing_claim(tenant_id, publish_batch_id);
CREATE INDEX listing_claim_field_claim ON listing_claim_field(tenant_id, claim_id, field);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴🔴 源 listing 未被改动 + 目标仍是草稿态（边界一 / 边界二）
--     认领记录落库的那一刻做这件事，而不是"服务层记得别改"。
CREATE FUNCTION guard_claim_source_untouched() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  src listing%ROWTYPE;
  tgt listing%ROWTYPE;
BEGIN
  SELECT * INTO src FROM listing WHERE tenant_id=NEW.tenant_id AND id=NEW.source_listing_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Source listing % no longer exists at the moment the claim is recorded', NEW.source_listing_id
      USING ERRCODE='23514';
  END IF;
  -- 🔴 源商品的平台状态 / 审核状态 / 版本号必须与认领时记下的快照逐字相等。
  --    不相等 = 有人在认领过程中动过源商品（或源商品在认领中途被改过）：
  --    这次的"复用"已经不是源商品当前的样子，本次认领直接被拒。
  IF src.status IS DISTINCT FROM NEW.source_status
     OR src.review_status IS DISTINCT FROM NEW.source_review_status
     OR src.version IS DISTINCT FROM NEW.source_version THEN
    RAISE EXCEPTION
      'Source listing % changed during the claim (status % -> %, review % -> %, version % -> %); a claim must never modify the source listing',
      NEW.source_listing_id, NEW.source_status, src.status, NEW.source_review_status, src.review_status,
      NEW.source_version, src.version
      USING ERRCODE='23514';
  END IF;

  -- 🔴 目标必须是草稿态：未提交平台审核（边界二）
  SELECT * INTO tgt FROM listing WHERE tenant_id=NEW.tenant_id AND id=NEW.target_listing_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Target listing % does not exist', NEW.target_listing_id USING ERRCODE='23514';
  END IF;
  IF tgt.status <> 'DRAFT' OR tgt.review_status <> 'NOT_SUBMITTED' THEN
    RAISE EXCEPTION
      'The claimed target listing must stay in DRAFT / NOT_SUBMITTED (a new shop listing has not passed platform review yet); got % / %',
      tgt.status, tgt.review_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_claim_source_guard AFTER INSERT ON listing_claim
  FOR EACH ROW EXECUTE FUNCTION guard_claim_source_untouched();

-- (b) 🔴 源 / 目标身份不可就地改写：一次认领的"从哪到哪"必须是当时那个事实。
CREATE FUNCTION guard_claim_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.source_listing_id IS DISTINCT FROM OLD.source_listing_id
     OR NEW.target_listing_id IS DISTINCT FROM OLD.target_listing_id
     OR NEW.source_shop_id IS DISTINCT FROM OLD.source_shop_id
     OR NEW.target_shop_id IS DISTINCT FROM OLD.target_shop_id
     OR NEW.source_marketplace_id IS DISTINCT FROM OLD.source_marketplace_id
     OR NEW.target_marketplace_id IS DISTINCT FROM OLD.target_marketplace_id
     OR NEW.source_snapshot_id IS DISTINCT FROM OLD.source_snapshot_id THEN
    RAISE EXCEPTION 'Claim source/target identity must not be rewritten in place' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_claim_identity BEFORE UPDATE ON listing_claim
  FOR EACH ROW EXECUTE FUNCTION guard_claim_identity();

-- (c) 🔴 认领记录不可删除：它是"这次搬家发生过"的事实（源 listing 不受影响的那一半凭据）。
CREATE FUNCTION refuse_listing_claim_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing claim is an audit fact and must not be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_claim_no_delete BEFORE DELETE ON listing_claim
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_claim_delete();

-- (d) 🔴 目标离开草稿态时，认领状态由 DB 触发器现场派生（服务层漏写不了）。
--     与 V171 `record_listing_status_change` 同一手法：状态迁移是事实，派生是义务。
--     ⚠️ 本函数默认 SECURITY INVOKER：认领记录的数据范围随**源与目标两个 listing** 收敛，
--     调用者看不到源商品时这一行不会被更新（而不是被拒）—— 范围外的认领本来就不可见。
CREATE FUNCTION project_claim_target_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    UPDATE listing_claim
       SET state='PUBLISHED', updated_at=now()
     WHERE tenant_id=NEW.tenant_id AND target_listing_id=NEW.id AND state='DRAFT';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_claim_target_state AFTER UPDATE OF status ON listing
  FOR EACH ROW EXECUTE FUNCTION project_claim_target_state();

-- (e) 逐字段决策随认领记录一起走：认领记录不可删 ⇒ 字段决策也不可单独删（级联删除会被上面的
--     拒绝触发器挡下 —— 这里显式说明，避免有人以为级联能绕过它）。
CREATE FUNCTION refuse_listing_claim_field_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing claim field decision is an audit fact and must not be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_claim_field_no_delete BEFORE DELETE ON listing_claim_field
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_claim_field_delete();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 / V146 / V169 / V170 / V171 /
-- V175 / V176 / V177 / V183 / V187 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_claim','listing_claim_field'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：认领**跨两个店铺**，可见性随源与目标**两个** listing 收敛
-- ---------------------------------------------------------------------------
-- 与 V171 `listing` / `listing_variant` 的 data_scope 同构：不允许绕过店铺直接枚举认领。
-- 为什么两个都要：认领把 A 店铺的内容搬进 B 店铺，源与目标任一不在范围内，
-- 这条认领就不是"这个用户的数据范围内的搬家" —— 半个事实比没有事实更危险。
CREATE POLICY data_scope ON listing_claim AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_claim.tenant_id
                 AND l.id=listing_claim.source_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id))
     AND EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_claim.tenant_id
                 AND l.id=listing_claim.target_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_claim.tenant_id
                 AND l.id=listing_claim.source_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id))
     AND EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_claim.tenant_id
                 AND l.id=listing_claim.target_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- 字段决策的范围随它所属的认领收敛
CREATE POLICY data_scope ON listing_claim_field AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_claim c WHERE c.tenant_id=listing_claim_field.tenant_id
                 AND c.id=listing_claim_field.claim_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_claim c WHERE c.tenant_id=listing_claim_field.tenant_id
                 AND c.id=listing_claim_field.claim_id));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V187 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 🔴 刻意**不授 DELETE**：两张表都是只追加的事实（删除被 DB 触发器拒绝）。
GRANT SELECT,INSERT,UPDATE ON listing_claim TO channel_app;
GRANT SELECT,INSERT ON listing_claim_field TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V171 / V183 同口径）。
REVOKE ALL ON listing_claim, listing_claim_field FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：复用 identity 已注册的 channel.operation.*（identity 服务零改动）
-- ---------------------------------------------------------------------------
-- 商品认领属于「平台操作」语义，与 10.1.1.2 渠道配置、2.1.2.1 平台枚举映射、
-- 14.1.2.2 广告类型差异同族，因此 identity 服务零改动。
