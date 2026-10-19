-- ===========================================================================
-- 4.1.1.2 Listing 详情（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.1.1.2 Listing 详情
--   - 职责：单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、
--           内容对照（平台当前内容 vs 内部草稿内容）、价格库存差异、发布历史、平台操作记录。
--   - 输入：`listing_id`；聚合平台快照与内部数据；用户权限。
--   - 输出：左右对照视图 + 编辑入口 + 差异高亮 + 回写按钮。
--   - 关系：上游 = 4.1.1.1；下游 = 4.2.1.1 编辑、4.3.2.1 回写。
--           **差异对照是本模块的核心价值**：让运营一眼看出"平台上卖的价格/库存和我系统里设的不一样"。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，零 ALTER 任何既有表）
-- ---------------------------------------------------------------------------
-- 只 CREATE 表 / 索引 / 触发器 / 策略。读的上游：
--   * V171 `listing` / `listing_variant` / `listing_status_event`
--     —— 4.1.1.1 已交付（本模块是它的下游，不改它一列）
--   * V2  `platform_operation` —— 2.5.1.1 已交付，「平台操作记录」直接读它，
--     **不复制**它的任何一列（详见第三节）
--   * V169 `writeback_target` —— 2.5.2.1 已交付，「平台上卖的价格/库存」在系统里
--     已有的那一份登记快照（详见第四节）
--   * V1   `shop` / `marketplace` / `platform` —— 平台原始信息的归属
-- 跨服务的（03 商品、05 定价、07 库存、09 订单、4.2.1.1 草稿）一律**不建 FK、不建列**。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 差异对照是本模块的核心价值，但「可算的差异」比想象的少 —— 先把账算清楚
-- ---------------------------------------------------------------------------
-- 蓝图原句是「平台上卖的价格/库存和我系统里设的不一样」。这是一句**双边**断言，
-- 而本批次能拿到的边只有一侧：
--
--   * 平台侧的价格 / 库存：listing 侧有 `platform_stock_state` / `platform_stock_qty`
--     （02 同步 / 4.3.2.2 回流回报的事实）；价格侧 listing **没有**任何列。
--   * 系统侧的价格 / 库存：**全库不存在**。卷 05 定价整卷 0/19 未交付，
--     07 库存与 09 订单同样未交付 ⇒ 「我系统里设的价格 / 可售量」在 DB 里查不到。
--
-- ⇒ **售价差异与内部可售量差异在本批次不可计算**。服务层把这两行的 `available` 置 false
-- 并写明归属与影响，前端显示「未接入」而不是显示 0。
-- **本迁移因此也不建任何价格 / 库存影子列**：V171 第二节已经论证过一遍 ——
-- 建一个 `price numeric` 等于制造第二个价格事实来源，05 上线后两个数字迟早对不上，
-- 而没人说得清该信哪个。这比「没有价格」危险得多。
--
-- 真正**能**算出来的差异有三条，全部是本模块如实算出来的、没有一个是编的：
--   1. 平台回报可售量 vs 系统回写登记簿快照（第四节）：两个真实数字，可能是最接近蓝图
--      原意的一条 —— 「系统以为有 50 件，平台其实只有 3 件」。
--   2. 平台变体 vs 内部 SKU 映射：变体条数与已映射条数不等 = DIFFERENT。
--   3. 平台在售状态 vs 平台可售状态：`status='ON_SALE'` 且 `platform_stock_state='OUT_OF_STOCK'`
--      = 平台自己前后不一致（挂着在售但买不到），这是漏单最常见的来源。
--
-- ---------------------------------------------------------------------------
-- 二、内容对照的左列（平台当前内容）本模块自己拥有，右列（内部草稿）归 4.2.1.1
-- ---------------------------------------------------------------------------
-- 「内容对照（平台当前内容 vs 内部草稿内容）」要有意义，左列必须**比 listing.title 更全**：
-- 要点 / 描述 / 图片集 / 视频 / A+ / 类目属性 / 合规声明 —— 这些正是 4.2.1.1 刊登编辑器
-- 要渲染的那一套字段，V171 的 `listing` 只存了 title / category_path / platform_category_id，
-- 撑不起一张对照表。
--
-- ⇒ `listing_content_snapshot` 存**平台当前内容**的快照。它是 04 自己拥有的平台侧事实
-- （来自 02 同步 / 4.4.1 链接采集 / 人工登记），与 V171 的 `listing` 是同族但不同物：
-- listing 存的是列表要筛的维度，snapshot 存的是对照要逐字段比的内容。
--
-- 🔴 快照是**追加**的而不是就地覆盖的：`listing_publish_record` 要引用「那次发布用的是
-- 哪一份内容」。就地覆盖会让发布历史指向一份已经被改掉的内容快照，
-- 「当时发出去的到底是什么」永远答不出来 —— 这类证据被改写比查不到更难发现。
-- 「当前」那一份由 `is_current` + 唯一部分索引钉住，DB 层拒绝出现两份当前快照。
--
-- 🔴 本表**只有平台侧**。内部草稿归 4.2.1.1（内容草稿按平台 + 站点 + 语言分别保存），
-- 本模块**不预建**它的表：一个没有写入方的空表会被当成「草稿系统已交付」，
-- 那比「明写未接入」危险得多。服务层对内部草稿侧一律 available=false + 归属 4.2.1.1。
--
-- ---------------------------------------------------------------------------
-- 三、🔴「平台操作记录」直接读 V2 的 platform_operation，不复制、不改、不加索引
-- ---------------------------------------------------------------------------
-- `platform_operation`（2.5.1.1，V2 建）就是「发往平台的写操作」的权威清单：
-- operation / status / verification / attempts / last_error / occurred_at 全在里面。
-- 4.1.1.2 要做的只是**按商品筛出属于这一条 listing 的那一段**。
--
-- 归属怎么算：2.5.2.1 写 platform_operation 时把 `targetType` 与 `externalRef`
-- 原文写进了 payload（见 WritebackBatchService），因此
--   `payload->>'targetType'='LISTING' AND payload->>'externalRef'=listing.external_item_id`
-- 加上 `shop_id` 相等，就精确落到本商品上。**这要求同店铺下 external_item_id 唯一** ——
-- 那正是 V171 的 `UNIQUE(tenant_id, shop_id, external_item_id)`，本模块直接依赖它。
--
-- ⇒ 本迁移对 platform_operation **零 ALTER、零索引**：加索引也是替别的车道改 DDL 面
-- （V169 第二节同纪律），而查询已经被 V2 的 `operation_shop (tenant_id,shop_id,occurred_at DESC)`
-- 覆盖住热点前缀。
--
-- 查不到的：直接写 platform_operation 而不经 writeback 的操作（2.5.1.1 单条派发）不带
-- targetType/externalRef，**无法归属到商品**。这不是丢数据，而是「店铺级操作本来就不是
-- 商品级事实」—— 服务层在返回体里显式说明这一点，不假装它是完整的。
--
-- ---------------------------------------------------------------------------
-- 四、🔴「平台上卖的价格 / 库存」在系统里已有的那一份：writeback_target（V169）
-- ---------------------------------------------------------------------------
-- V169 `writeback_target`（2.5.2.1）给每个可回写目标存了
--   current_stock numeric(14,3) / current_price numeric(18,4) / currency varchar(3)
-- 它的语义在 V169 第三节写死：改价 payload 里 `previousPrice` 取的就是 `current_price`
-- ⇒ **它是「平台上当前在售的值」的快照**，不是「系统里想设的值」。
-- 这正是本模块能拿到的、唯一一处真实的价格数字。
--
-- 🔴 但它**不是**售价的权威来源，理由必须写进返回值而不是藏着：
--   * 它只在该 listing 被登记为可回写目标时才有值，没登记就是 NULL（**不是 0**）；
--   * 05 定价未交付 ⇒ 没有「系统里设的价格」可以和它比 ⇒ 售价差异仍然不可计算；
--   * 因此本模块把它放在**平台侧那一列**（带来源标注），不把它当成第二个价格事实来源，
--     也不拿它推导任何售价结论。
--
-- 同理 current_stock 参与的是「平台回报可售量 vs 系统登记簿快照」这条**可计算**的差异，
-- 而**不是**蓝图说的「内部可售量」——后者归 07 库存，未接入。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 缺货差异筛的是平台侧状态，且与「内部可售量」在数据上分得干干净净
-- ---------------------------------------------------------------------------
-- 蓝图 4.1.1.3 加粗句「平台下架与内部下架是两个事实」在 V171 第二节已经落成
-- `listing.platform_stock_state`。本模块把它放在对照的**平台侧**，
-- 内部侧那一格永远 available=false + 归属 07 库存 ⇒ 两件事在数据上、契约上、
-- 界面上都是两行，永远不会被合成一个「库存」字段。
--
-- ---------------------------------------------------------------------------
-- 六、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本迁移新增的两张表**没有任何金额列**（价格归 05，见第一节）。
-- 存在的 jsonb 列存的是平台侧结构原文（要点数组 / 图片数组 / A+ 模块），
-- 与金额无关。**无 float / double 一列**。
--
-- ---------------------------------------------------------------------------
-- 七、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**。
--
-- ---------------------------------------------------------------------------
-- listing_content_snapshot · 平台当前内容快照（内容对照的左列）
-- ---------------------------------------------------------------------------
CREATE TABLE listing_content_snapshot (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  listing_id         uuid NOT NULL,
  -- 语言：内容按平台 + 站点 + 语言分别保存（4.2.1.1 的草稿也按这个维度存）
  language           varchar(16) NOT NULL,
  -- 平台侧内容原文。**不翻译、不改写、不做长度裁剪**：
  -- 平台写了 240 个字符，界面上就要能看见 240 个字符，截断过的对照是假对照。
  title              varchar(500) NOT NULL DEFAULT '',
  bullet_points      jsonb NOT NULL DEFAULT '[]'::jsonb,
  description        text NOT NULL DEFAULT '',
  images             jsonb NOT NULL DEFAULT '[]'::jsonb,
  video_url          text NOT NULL DEFAULT '',
  a_plus_modules     jsonb NOT NULL DEFAULT '[]'::jsonb,
  category_attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
  compliance_statements jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 来源：SYNC = 02 同步 / COLLECTION = 4.4.1 链接采集 / MANUAL = 人工登记
  source             text NOT NULL DEFAULT 'SYNC'
                     CHECK(source IN ('SYNC','COLLECTION','MANUAL')),
  -- 🔴 同一 listing + 同一语言下，至多一份 is_current=true（唯一部分索引钉住）
  is_current         boolean NOT NULL DEFAULT true,
  -- 快照采集时刻：平台侧内容是什么时候读到的
  captured_at        timestamptz NOT NULL DEFAULT now(),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(language) <> ''),
  -- jsonb 结构纪律：要点 / 图片 / A+ 必须是数组，属性 / 合规必须是对象。
  -- 存成字符串的那些会让对照界面只能显示一行 JSON 原文，等于没有结构化对照。
  CHECK(jsonb_typeof(bullet_points) = 'array'),
  CHECK(jsonb_typeof(images) = 'array'),
  CHECK(jsonb_typeof(a_plus_modules) = 'array'),
  CHECK(jsonb_typeof(category_attributes) = 'object'),
  CHECK(jsonb_typeof(compliance_statements) = 'object')
);
COMMENT ON TABLE listing_content_snapshot IS
  '4.1.1.2 · 平台当前内容快照（内容对照的左列）。追加写、is_current 标当前；内部草稿侧归 4.2.1.1，本表不预建。';

-- 一份 listing 的一种语言只能有一份「当前」快照。冲突即被 DB 拒（而不是静默覆盖）。
CREATE UNIQUE INDEX listing_content_snapshot_current
  ON listing_content_snapshot(tenant_id, listing_id, language) WHERE is_current;
-- 发布历史按商品 + 时间倒序取
CREATE INDEX listing_content_snapshot_listing
  ON listing_content_snapshot(tenant_id, listing_id, captured_at DESC);
-- 采集时刻对齐（同步批次内同一时刻回来的多条内容）
CREATE INDEX listing_content_snapshot_captured
  ON listing_content_snapshot(tenant_id, listing_id, captured_at);

-- ---------------------------------------------------------------------------
-- listing_publish_record · 发布历史（职责句里的「发布历史」）
-- ---------------------------------------------------------------------------
-- 🔴 与 4.1.1.1 的 `listing_status_event` 是**两张不同的表**：
--   * listing_status_event 记的是**状态**怎么变的（DRAFT → ON_SALE …），
--     由 V171 的 AFTER UPDATE 触发器现场派生；
--   * listing_publish_record 记的是**发布动作**本身发生了什么（发的是哪份内容、
--     平台回了什么、失败原因是什么）。状态变了不等于发过一次布 ——
--     把两者合成一张表，「点了发布但平台拒收」这条事实在本模块就存不下来了。
--
-- 分工与 V171 一致：**本模块拥有历史表，发布动作本身归 4.3.1.1 / 4.3.2.1**
-- （它们来调这个写入端点登记结果）。本模块不代它们发起发布。
CREATE TABLE listing_publish_record (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  listing_id    uuid NOT NULL,
  -- 本次发布所用的平台内容快照；没有对应快照时为 null（例如人工直接在平台后台改的）
  snapshot_id   uuid,
  -- PUBLISH = 4.3.1.1 发布；CONTENT_WRITEBACK = 4.3.2.1 内容回写
  action        text NOT NULL DEFAULT 'PUBLISH'
                CHECK(action IN ('PUBLISH','CONTENT_WRITEBACK')),
  outcome       text NOT NULL CHECK(outcome IN ('SUCCEEDED','FAILED','REJECTED')),
  -- 平台返回的请求 ID：平台申诉时「我们什么时候发的」要靠它核验
  platform_request_id varchar(128) NOT NULL DEFAULT '',
  reason        varchar(500) NOT NULL DEFAULT '',
  actor_id      uuid,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  -- 引用的是本 listing 自己的快照：跨商品 / 跨租户挂引用在物理上不可能
  FOREIGN KEY(tenant_id, snapshot_id) REFERENCES listing_content_snapshot(tenant_id, id),
  -- 「没成」必须写明为什么：光一个 FAILED 没人知道该改什么
  CHECK(outcome <> 'FAILED' OR btrim(reason) <> ''),
  CHECK(outcome <> 'REJECTED' OR btrim(reason) <> '')
);
COMMENT ON TABLE listing_publish_record IS
  '4.1.1.2 · 发布历史（发布动作的结果留痕）。状态迁移另见 listing_status_event，两者不是一回事。';

CREATE INDEX listing_publish_record_listing
  ON listing_publish_record(tenant_id, listing_id, occurred_at DESC);
CREATE INDEX listing_publish_record_time
  ON listing_publish_record(tenant_id, occurred_at DESC);
CREATE INDEX listing_publish_record_snapshot
  ON listing_publish_record(tenant_id, snapshot_id);

-- ---------------------------------------------------------------------------
-- 触发器
-- ---------------------------------------------------------------------------
-- (a) 🔴 登记新的「当前」快照时，旧的那份自动降级 —— 「当前」永远只有一份，
--     而且由 DB 保证，不靠服务层记得先 update 再 insert。
CREATE FUNCTION demote_previous_content_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 -- 只在要写「当前」时降级：降级动作本身会把 is_current 写成 false，
 -- 不加这道判断就会自己触发自己。
 IF NEW.is_current THEN
  UPDATE listing_content_snapshot
     SET is_current = false, updated_at = now()
   WHERE tenant_id = NEW.tenant_id
     AND listing_id = NEW.listing_id
     AND language = NEW.language
     AND is_current
     AND id <> NEW.id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_content_snapshot_demote
  BEFORE INSERT OR UPDATE OF is_current ON listing_content_snapshot
  FOR EACH ROW EXECUTE FUNCTION demote_previous_content_snapshot();

-- (b) 🔴 发布记录不可就地改写：它已经是「当时发了什么」的凭据。
--     改用冲销（补一条新记录）而不是 UPDATE —— 与 V2 guard_settled_run 同一纪律。
CREATE FUNCTION guard_publish_record_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'Publish record is append-only; record a new entry instead of rewriting history'
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_publish_record_immutable BEFORE UPDATE ON listing_publish_record
  FOR EACH ROW EXECUTE FUNCTION guard_publish_record_immutable();

-- (c) 🔴 「当前快照」的数量由 DB 兜底：把 is_current 从 true 翻成 false 是允许的
--     （下线当前内容），但同一 listing+语言不得出现两份 current（唯一部分索引挡住）。
--     同时钉住：发布记录引用的快照必须是本 listing 的（复合 FK 已保证租户，
--     这里再保证商品 —— listing_id 与 snapshot_id 必须指向同一个 listing）。
CREATE FUNCTION guard_publish_record_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.snapshot_id IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM listing_content_snapshot c
     WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.snapshot_id AND c.listing_id = NEW.listing_id
 ) THEN
  RAISE EXCEPTION 'Publish record must reference a content snapshot of the same listing' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER listing_publish_record_snapshot BEFORE INSERT OR UPDATE ON listing_publish_record
  FOR EACH ROW EXECUTE FUNCTION guard_publish_record_snapshot();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 V171 第五节 / V169 第四节同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_content_snapshot','listing_publish_record' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：与 listing 同步收敛（照抄 V171 的 listing_variant 写法）
-- ---------------------------------------------------------------------------
-- 可见性随 listing 的店铺负责人 / 部门收敛；不允许绕过 listing 直接枚举快照或发布历史。
CREATE POLICY data_scope ON listing_content_snapshot AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_snapshot.tenant_id
                 AND l.id=listing_content_snapshot.listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_content_snapshot.tenant_id
                 AND l.id=listing_content_snapshot.listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_publish_record AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_publish_record.tenant_id
                 AND l.id=listing_publish_record.listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_publish_record.tenant_id
                 AND l.id=listing_publish_record.listing_id
                 AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V169/V171 的条件建角色，保证迁移可重复执行）
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
  listing_content_snapshot, listing_publish_record TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V169 / V171 同口径）。
REVOKE ALL ON
  listing_content_snapshot, listing_publish_record FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：listing 详情与 4.1.1.1 列表同属「平台操作」语义，
-- 与 10.1.1.2 渠道配置、2.1.2.1 平台枚举映射、14.1.2.2 广告类型差异同族，
-- 因此 identity 服务零改动。
