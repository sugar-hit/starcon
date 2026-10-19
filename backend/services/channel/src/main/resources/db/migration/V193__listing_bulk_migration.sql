-- ===========================================================================
-- 4.4.2.2 批量搬迁（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.4.2.2 批量搬迁
--   - 职责：批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），
--           并对重复铺货风险给出提示。
--   - 输入：筛选规则；目标店铺集合；重复度控制参数。
--   - 输出：批量搬迁任务 + 结果明细；重复度风险提示。
--   - 关系：上游 = 4.4.2.1；下游 = 4.3.1.2。**搬迁后必须做标题差异化**，否则平台可能判定重复铺货
--           并抑制 listing。
--
-- ---------------------------------------------------------------------------
-- 零、归属 channel（与 4.4.2.1 / 4.1.1.1 / 4.1.1.2 / 4.3.1.2 同一先例）
-- ---------------------------------------------------------------------------
-- 源 listing、目标店铺、目标草稿、待发布任务全部是 channel 自己的事实：
--   * V171 `listing` / `listing_variant`      —— 源与目标（读 + 写目标草稿）
--   * V175 `listing_content_snapshot`         —— 被复用的内容（读源 + 写目标）
--   * V176 `listing_mapping` / `listing_mapping_version` —— 被复用的映射（读 + 写目标）
--   * V183 `publish_batch` / `publish_task`    —— 待发布任务（**复用，不另建平行表**）
--   * V169 `writeback_target`                 —— 平台上当前在售的值（**只读**，价格维度的唯一真实来源）
--   * V190 `listing_claim` / `listing_claim_field` —— 4.4.2.1 的认领事实（**只读**，见第七节）
--
-- 本迁移**纯新建四张表、零 ALTER 任何既有表**（与 V177 / V183 / V187 / V190 同一纪律）。
-- 🔴 跨服务：03 商品主数据只经逻辑 id `internal_sku_id` 引用，不建 FK、不复制 catalog 任何一列。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 蓝图加粗句「搬迁后必须做标题差异化」—— 落成 DB 约束，不是提示文案
-- ---------------------------------------------------------------------------
-- 「否则平台可能判定重复铺货并抑制 listing」这句话如果只活在提示文案里，它随时会丢：
-- 一次赶工的实现就会把源标题原样抄进目标草稿，然后**什么都没发生** ——
-- 直到平台把 listing 抑制掉，运营还不知道是哪一步丢的。
--
-- 所以本迁移用**两道物理闸**把「差异化」钉死：
--   (a) `listing_migration_item` 的 CHECK：MIGRATED 的行 `target_title <> source_title`（逐字不同）。
--   (b) BEFORE INSERT 触发器 `guard_migration_title_differentiated`：
--       比较 **lower(btrim(...))** 之后的标题 —— 只加一个空格或改大小写**不算差异化**，
--       平台看到的是同一个标题。
-- 两条都违反时直接 23514 拒绝这一行，DB 层就长不出"没做差异化的搬迁结果"。
--
-- 差异化本身**怎么算**在服务层（ListingMigrationService），但算出来的东西逐条留痕在
-- `listing_migration_content_diff`（原值 / 新值 / 规则 / 规则参数 / 谁改的）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴「重复铺货风险」与「已搬迁」是两个独立事实，落在两张独立的表上
-- ---------------------------------------------------------------------------
--   * `listing_migration_duplicate` —— 重复度评估（分值 / 等级 / 判定依据 / 最像的那条既有 listing）。
--     分值是**算出来的**（标题 / 类目 / 属性三轴相似度加权），不是写死的常量；
--     目标店铺一条既有 listing 都没有时等级是 `UNKNOWN`（**不是 0 分**）。
--   * `listing_migration_item.state='MIGRATED'` —— 已搬迁。
--     唯一索引 `listing_migration_item_live` 让「同一个源商品搬进同一个店铺两次」在 DB 层
--     不可能发生（第二次会被唯一约束挡下，而不是靠服务层记得查）。
--
-- 两条查询各走各的端点（/duplicate-risks 与 /migrated），**不合并成一个结论** ——
-- 「风险高」与「已经搬过了」是两种完全不同的处置。
--
-- ---------------------------------------------------------------------------
-- 三、🔴「不知道」是一个状态，不是一个 0
-- ---------------------------------------------------------------------------
--   * 价格维度：来源是 V169 `writeback_target.current_price`（平台上当前在售的值的登记簿快照）。
--     该 listing 没登记为可回写目标 ⇒ **没有登记值** ⇒ 显式 UNKNOWN，**不是 0**
--     （V175 的 writebackRegistry() 已经用同一口径说过一遍：「未登记不是 0」）。
--   * 销量维度：**没有已交付的数据源**。平台侧销量归 4.5.1.1 平台商品监控（未交付），
--     内部订单口径归 09（跨服务，禁止跨库 SQL）。⇒ 本表**不建任何销量列**，
--     筛选时该维度显式标记为"来源未接入"，命中数按「未知值策略」如实报，绝不读成 0。
--   * 重复度分值：三个分量都不可知时 `score IS NULL` + 等级 `UNKNOWN`。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   * `listing_migration_duplicate.score` / `*_similarity`  numeric(6,5) —— 是分值不是钱
--   * 筛选里的价格区间与销量区间是**请求参数**（Java 侧 BigDecimal），落到
--     `writeback_target.current_price numeric(18,4)` 上比较；**无 float / double 一列**。
--   * 数量（销量区间）走 BigDecimal / NUMERIC，与 05 定价口径一致。
--
-- ---------------------------------------------------------------------------
-- 五、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`。
-- 集成测试**断言写回的值逐字相等**。
--
-- ---------------------------------------------------------------------------
-- 六、幂等（AGENTS.md「发布是异步任务 + 幂等，重复触发不产生重复 listing」同一纪律）
-- ---------------------------------------------------------------------------
-- 三道闸：
--   (a) `UNIQUE(tenant_id, idempotency_key)` —— 同一个搬迁任务重复触发只会得到**同一个批次**。
--       幂等键由调用方给；不给时服务层按「源店铺 + 目标集合 + 筛选规则 + 重复度参数」的
--       规范化摘要派生 ⇒ 同一个请求重复提交天然幂等。
--   (b) `UNIQUE(tenant_id, batch_id, source_listing_id, target_shop_id)` —— 同一批次里
--       同一个（源商品 × 目标店铺）只可能有一条结果。
--   (c) 唯一索引 `listing_migration_item_live` —— 已搬迁的那一对**搬第二次不成立**。
--
-- ---------------------------------------------------------------------------
-- 七、上游 4.4.2.1 的认领事实：只读，不抢写入所有权
-- ---------------------------------------------------------------------------
-- 「每张事实表只有一个写入所有者」——`listing_claim` 的写入所有者是 4.4.2.1，
-- 本模块**只读**它：判断「这个源商品是不是已经被认领到目标店铺了」。
-- 被 4.4.2.1 认领过去的商品，本模块再搬一次就是重复铺货 ⇒ 直接跳过并写明原因。
--
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- listing_migration_batch · 批量搬迁任务（一次搬迁 = 一个批次）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_migration_batch (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 源店铺（批量搬迁 = 「从一个店铺搬到多个店铺」）
  source_shop_id    uuid NOT NULL,
  -- 目标店铺集合：冗余成一列是给 data_scope 策略用的（策略要判断"全部目标都在范围内"）
  target_shop_ids   uuid[] NOT NULL,
  -- 目标店铺与站点的明细（店铺编码 / 店铺名 / 站点 id / 站点编码），原文快照
  target_detail     jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 筛选规则快照（类目 / 销量区间 / 价格区间 / 未知值策略 / 排序 / 分页）
  filter_rule       jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 重复度控制参数（跳过的风险等级 / 未知风险处置 / 比对上限 / 标题模板）
  duplicate_policy  jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 幂等键：同一个键重复触发 ⇒ 返回同一个批次，不产生第二条搬迁结果
  idempotency_key   varchar(200) NOT NULL,
  state             text NOT NULL DEFAULT 'EXECUTED'
                    CHECK(state IN ('EXECUTED','PARTIAL','FAILED')),
  -- 命中候选数（筛选后、搬迁前）
  candidate_count   integer NOT NULL DEFAULT 0 CHECK(candidate_count>=0),
  -- 实际产生的搬迁结果条数（= 目标商品数）
  total_count       integer NOT NULL DEFAULT 0 CHECK(total_count>=0),
  migrated_count    integer NOT NULL DEFAULT 0 CHECK(migrated_count>=0),
  skipped_count     integer NOT NULL DEFAULT 0 CHECK(skipped_count>=0),
  failed_count      integer NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  -- 待发布批次：复用 4.3.1.2 的 V183（**不另建平行发布任务表**）
  publish_batch_id  uuid,
  actor_id          uuid,
  note              varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 幂等：同一个租户里同一个幂等键只能有一个批次
  UNIQUE(tenant_id, idempotency_key),
  FOREIGN KEY (tenant_id, source_shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY (tenant_id, publish_batch_id) REFERENCES publish_batch(tenant_id, id),
  -- 目标店铺集合不能为空，也不能含源店铺（源店搬到自己身上不构成搬迁）
  CHECK(cardinality(target_shop_ids) > 0),
  CHECK(NOT (source_shop_id = ANY(target_shop_ids))),
  -- 计数自洽
  CHECK(migrated_count+skipped_count+failed_count = total_count),
  -- 结果条数 = 候选商品数 × 目标店铺数（上限）
  CHECK(total_count <= candidate_count * cardinality(target_shop_ids))
);
COMMENT ON TABLE listing_migration_batch IS
  '4.4.2.2 · 批量搬迁任务（一次搬迁 = 一个批次，N 个源商品 × M 个目标店铺）。idempotency_key 唯一 ⇒ 重复触发不产生第二批。';

-- ---------------------------------------------------------------------------
-- listing_migration_item · 搬迁结果明细（源商品 × 目标店铺）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_migration_item (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  batch_id            uuid NOT NULL,
  source_listing_id   uuid NOT NULL,
  source_shop_id      uuid NOT NULL,
  target_shop_id      uuid NOT NULL,
  target_marketplace_id uuid NOT NULL REFERENCES marketplace(id),
  -- 目标草稿（本次搬迁新建的 listing）；SKIPPED / FAILED 时为空
  target_listing_id   uuid,
  -- 原标题与差异化后的标题（逐条留痕，另有 listing_migration_content_diff 记规则）
  source_title        varchar(500) NOT NULL DEFAULT '',
  target_title        varchar(500) NOT NULL DEFAULT '',
  state               text NOT NULL
                      CHECK(state IN ('MIGRATED','SKIPPED','FAILED')),
  -- 跳过原因（SKIPPED）：ALREADY_MIGRATED / ALREADY_CLAIMED / DUPLICATE_RISK / ...
  skip_reason         text,
  -- 失败原因（FAILED）
  failure_reason      text,
  -- 重复度结论（分值与等级是**算出来的**；UNKNOWN 时分值为 NULL）
  duplicate_level     text NOT NULL DEFAULT 'UNKNOWN'
                      CHECK(duplicate_level IN ('LOW','MEDIUM','HIGH','CRITICAL','UNKNOWN')),
  duplicate_score     numeric(6,5),
  -- 待发布任务（复用 V183）
  publish_task_id     uuid,
  actor_id            uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 幂等：同一批次里同一个（源商品 × 目标店铺）只可能有一条结果
  UNIQUE(tenant_id, batch_id, source_listing_id, target_shop_id),
  FOREIGN KEY (tenant_id, batch_id) REFERENCES listing_migration_batch(tenant_id, id),
  FOREIGN KEY (tenant_id, source_listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY (tenant_id, target_listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY (tenant_id, source_shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY (tenant_id, target_shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY (tenant_id, publish_task_id) REFERENCES publish_task(tenant_id, id),
  -- 「搬了」⇒ 一定有目标草稿；「没搬」⇒ 一定没有
  CHECK((state='MIGRATED') = (target_listing_id IS NOT NULL)),
  -- 🔴 蓝图加粗句：搬迁结果的标题必须与原标题**逐字不同**（配合触发器做归一化比较）
  CHECK(state<>'MIGRATED' OR target_title <> source_title),
  -- 跳过 / 失败都必须写明原因（"没成"没人知道该改什么）
  CHECK(state<>'SKIPPED' OR (skip_reason IS NOT NULL AND btrim(skip_reason) <> '')),
  CHECK(state<>'FAILED' OR (failure_reason IS NOT NULL AND btrim(failure_reason) <> '')),
  -- 分值与等级自洽：UNKNOWN 等级没有分值；有分值就有等级
  CHECK((duplicate_level='UNKNOWN') = (duplicate_score IS NULL)),
  CHECK(duplicate_score IS NULL OR (duplicate_score >= 0 AND duplicate_score <= 1))
);
COMMENT ON TABLE listing_migration_item IS
  '4.4.2.2 · 搬迁结果明细。一行 = 一个源商品在一个目标店铺上的搬迁结果。';

-- 「已搬迁」在 DB 层的物理事实：同一个源商品搬进同一个店铺，第二次不成立
CREATE UNIQUE INDEX listing_migration_item_live
  ON listing_migration_item(tenant_id, source_listing_id, target_shop_id)
  WHERE state='MIGRATED';

-- ---------------------------------------------------------------------------
-- listing_migration_content_diff · 差异化留痕（原值 vs 新值 + 规则 + 谁改的）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_migration_content_diff (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  item_id       uuid NOT NULL,
  -- 差异化的字段：标题 / 卖点 / 描述
  field         text NOT NULL CHECK(field IN ('TITLE','BULLETS','DESCRIPTION')),
  -- 🔴 规则序号：一个字段可能经过多步规则（模板 → 店铺标识 → 站点标识 → 截断 → 消歧），
  --    每一步留一行。seq 递增 ⇒ "原值 → 差异化后的值"的完整演化过程都在库里。
  seq           integer NOT NULL CHECK(seq>=0),
  -- 原值原文（JSON 字段存 JSON 原文；不翻译、不裁剪 —— 裁剪过的对照是假对照）
  source_value  text NOT NULL DEFAULT '',
  -- 差异化后的值
  target_value  text NOT NULL DEFAULT '',
  -- 依据的规则（如 STORE_MARKER / SITE_MARKER / COLLISION_RESOLVE / TEMPLATE）
  rule_id       text NOT NULL CHECK(btrim(rule_id) <> ''),
  -- 规则参数（店铺编码 / 站点编码 / 模板 / 序号 …），原文存
  rule_params   jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id      uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, item_id, field, seq),
  FOREIGN KEY (tenant_id, item_id) REFERENCES listing_migration_item(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_migration_content_diff IS
  '4.4.2.2 · 逐字段逐规则的差异化留痕：原值、每一步之后的值、依据哪条规则、谁改的。';

-- ---------------------------------------------------------------------------
-- listing_migration_duplicate · 重复铺货风险评估（可解释，不是常量分值）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_migration_duplicate (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  item_id             uuid NOT NULL,
  source_listing_id   uuid NOT NULL,
  target_shop_id      uuid NOT NULL,
  -- 综合分（0~1）；目标店铺无任何可比商品时为 NULL（**不是 0**）
  score               numeric(6,5),
  level               text NOT NULL
                      CHECK(level IN ('LOW','MEDIUM','HIGH','CRITICAL','UNKNOWN')),
  -- 最像的那条既有 listing（判定依据的锚点）
  comparable_listing_id uuid,
  comparable_title    varchar(500) NOT NULL DEFAULT '',
  -- 参与比对的既有 listing 条数
  comparable_count    integer NOT NULL DEFAULT 0 CHECK(comparable_count>=0),
  -- 三个分量：标题 / 类目 / 属性（不可知时为 NULL，**不是 0**）
  title_similarity     numeric(6,5),
  category_similarity  numeric(6,5),
  attribute_similarity numeric(6,5),
  -- 判定依据（权重 / 阈值 / 命中的词 / 属性键 / 人话结论），原文存
  basis               jsonb NOT NULL DEFAULT '{}'::jsonb,
  evaluated_at        timestamptz NOT NULL DEFAULT now(),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, item_id),
  FOREIGN KEY (tenant_id, item_id) REFERENCES listing_migration_item(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, comparable_listing_id) REFERENCES listing(tenant_id, id),
  FOREIGN KEY (tenant_id, target_shop_id) REFERENCES shop(tenant_id, id),
  -- 分值与等级自洽
  CHECK((level='UNKNOWN') = (score IS NULL)),
  CHECK(score IS NULL OR (score >= 0 AND score <= 1)),
  -- 有分值就必须给出锚点与依据（"这个分是怎么来的"必须答得出来）
  CHECK(level='UNKNOWN' OR (comparable_listing_id IS NOT NULL AND comparable_count > 0))
);
COMMENT ON TABLE listing_migration_duplicate IS
  '4.4.2.2 · 重复铺货风险评估。分值由标题 / 类目 / 属性三轴相似度加权算出，basis 记判定依据。';

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
CREATE INDEX listing_migration_batch_recent  ON listing_migration_batch(tenant_id, created_at DESC, id);
CREATE INDEX listing_migration_batch_source  ON listing_migration_batch(tenant_id, source_shop_id);
CREATE INDEX listing_migration_batch_state   ON listing_migration_batch(tenant_id, state);
CREATE INDEX listing_migration_batch_publish ON listing_migration_batch(tenant_id, publish_batch_id);

CREATE INDEX listing_migration_item_batch   ON listing_migration_item(tenant_id, batch_id, created_at);
CREATE INDEX listing_migration_item_source   ON listing_migration_item(tenant_id, source_listing_id);
CREATE INDEX listing_migration_item_target   ON listing_migration_item(tenant_id, target_shop_id);
CREATE INDEX listing_migration_item_state    ON listing_migration_item(tenant_id, state);
CREATE INDEX listing_migration_item_risk     ON listing_migration_item(tenant_id, duplicate_level, created_at DESC);

CREATE INDEX listing_migration_diff_item     ON listing_migration_content_diff(tenant_id, item_id);
CREATE INDEX listing_migration_duplicate_item ON listing_migration_duplicate(tenant_id, item_id);
CREATE INDEX listing_migration_duplicate_shop ON listing_migration_duplicate(tenant_id, target_shop_id, level);
CREATE INDEX listing_migration_duplicate_risk ON listing_migration_duplicate(tenant_id, level, evaluated_at DESC);
CREATE INDEX listing_migration_duplicate_source ON listing_migration_duplicate(tenant_id, source_listing_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴🔴 标题必须真的差异化（蓝图加粗句）：归一化比较（去空白 + 忽略大小写），
--     只改大小写或加一个空格**不算**差异化 —— 平台看到的是同一个标题。
CREATE FUNCTION guard_migration_title_differentiated() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.state = 'MIGRATED'
     AND lower(btrim(NEW.target_title)) = lower(btrim(NEW.source_title)) THEN
    RAISE EXCEPTION
      'Migrated listing must carry a differentiated title: source and target titles are identical after normalization (% -> %).',
      NEW.source_title, NEW.target_title
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_migration_title_guard BEFORE INSERT ON listing_migration_item
  FOR EACH ROW EXECUTE FUNCTION guard_migration_title_differentiated();

-- (b) 源 / 目标身份不可就地改写：一次搬迁的"从哪到哪"必须是当时那个事实。
CREATE FUNCTION guard_migration_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
     OR NEW.source_listing_id IS DISTINCT FROM OLD.source_listing_id
     OR NEW.source_shop_id IS DISTINCT FROM OLD.source_shop_id
     OR NEW.target_shop_id IS DISTINCT FROM OLD.target_shop_id
     OR NEW.target_marketplace_id IS DISTINCT FROM OLD.target_marketplace_id
     OR NEW.source_title IS DISTINCT FROM OLD.source_title THEN
    RAISE EXCEPTION 'Migration source/target identity must not be rewritten in place' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_migration_identity BEFORE UPDATE ON listing_migration_item
  FOR EACH ROW EXECUTE FUNCTION guard_migration_identity();

-- (c) 搬迁结果不可删除：它是"这次搬家发生过没有"的凭据。
CREATE FUNCTION refuse_listing_migration_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing migration result is an audit fact and must not be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_migration_item_no_delete BEFORE DELETE ON listing_migration_item
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_migration_delete();

-- (d) 批次不可删除：它带着筛选规则与重复度参数的快照。
CREATE TRIGGER listing_migration_batch_no_delete BEFORE DELETE ON listing_migration_batch
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_migration_delete();

-- (e) 差异化留痕与风险评估随结果走（结果不可删 ⇒ 留痕也不可单独删）。
CREATE TRIGGER listing_migration_diff_no_delete BEFORE DELETE ON listing_migration_content_diff
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_migration_delete();
CREATE TRIGGER listing_migration_duplicate_no_delete BEFORE DELETE ON listing_migration_duplicate
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_migration_delete();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V171 / V175 / V176 / V177 / V183 / V187 / V190 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_migration_batch','listing_migration_item',
    'listing_migration_content_diff','listing_migration_duplicate' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：按**店铺维度**收敛（与 V171 listing / V190 listing_claim 同构）
-- ---------------------------------------------------------------------------
-- 批次：源店铺与**全部**目标店铺都要在范围内（只看得见一半目标就开搬迁 = "以为搬了整批"是假事实）
CREATE POLICY data_scope ON listing_migration_batch AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_migration_batch.tenant_id
                 AND s.id=listing_migration_batch.source_shop_id
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id))
     AND (SELECT count(*) FROM shop s WHERE s.tenant_id=listing_migration_batch.tenant_id
                 AND s.id = ANY(listing_migration_batch.target_shop_ids)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id))
         = cardinality(listing_migration_batch.target_shop_ids))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_migration_batch.tenant_id
                 AND s.id=listing_migration_batch.source_shop_id
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id))
     AND (SELECT count(*) FROM shop s WHERE s.tenant_id=listing_migration_batch.tenant_id
                 AND s.id = ANY(listing_migration_batch.target_shop_ids)
                 AND channel_scope(channel_operation(),s.owner_id,s.department_id))
         = cardinality(listing_migration_batch.target_shop_ids));

-- 明细：源商品与目标草稿都要在范围内（源与目标任一不可见 ⇒ 这条搬迁不在用户的数据范围内）
CREATE POLICY data_scope ON listing_migration_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_migration_item.tenant_id
                 AND l.id=listing_migration_item.source_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id))
     AND (listing_migration_item.target_listing_id IS NULL
          OR EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_migration_item.tenant_id
                    AND l.id=listing_migration_item.target_listing_id
                    AND channel_scope(channel_operation(),l.owner_id,l.department_id))))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_migration_item.tenant_id
                 AND l.id=listing_migration_item.source_listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id))
     AND (listing_migration_item.target_listing_id IS NULL
          OR EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_migration_item.tenant_id
                    AND l.id=listing_migration_item.target_listing_id
                    AND channel_scope(channel_operation(),l.owner_id,l.department_id))));

-- 差异化留痕与风险评估的范围随它所属的明细收敛
CREATE POLICY data_scope ON listing_migration_content_diff AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_migration_item i WHERE i.tenant_id=listing_migration_content_diff.tenant_id
                 AND i.id=listing_migration_content_diff.item_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_migration_item i WHERE i.tenant_id=listing_migration_content_diff.tenant_id
                 AND i.id=listing_migration_content_diff.item_id));

CREATE POLICY data_scope ON listing_migration_duplicate AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_migration_item i WHERE i.tenant_id=listing_migration_duplicate.tenant_id
                 AND i.id=listing_migration_duplicate.item_id))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_migration_item i WHERE i.tenant_id=listing_migration_duplicate.tenant_id
                 AND i.id=listing_migration_duplicate.item_id));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V190 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 🔴 刻意**不授 DELETE**：四张表都是只追加的事实（删除被 DB 触发器拒绝）。
GRANT SELECT,INSERT,UPDATE ON listing_migration_batch TO channel_app;
GRANT SELECT,INSERT,UPDATE ON listing_migration_item TO channel_app;
GRANT SELECT,INSERT,UPDATE ON listing_migration_content_diff TO channel_app;
GRANT SELECT,INSERT,UPDATE ON listing_migration_duplicate TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V171 / V183 同口径）。
REVOKE ALL ON
  listing_migration_batch, listing_migration_item,
  listing_migration_content_diff, listing_migration_duplicate FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：复用 identity 已注册的 channel.operation.*（identity 服务零改动）
-- ---------------------------------------------------------------------------
-- 批量搬迁属于「平台操作」语义，与 4.1.1.1 列表、4.2.1.2 批量编辑、4.3.1.2 批量发布、
-- 4.4.2.1 商品认领同族，因此 identity 服务零改动。
