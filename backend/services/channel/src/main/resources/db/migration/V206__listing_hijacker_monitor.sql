-- ===========================================================================
-- 4.5.2.1 跟卖监控（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.5.2.1 跟卖监控
--   - 职责：监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，
--           发现异常跟卖或恶意压价时告警。
--   - 输入：定时抓取或平台接口返回的跟卖信息。
--   - 输出：跟卖记录 + 预警通知 + 对比视图。
--   - 关系：上游 = 抓取任务；下游 = 16 合规（品牌保护）、1 工作台预警。
--           **跟卖数据只能作为参考**，处置动作（投诉/举报）在平台侧完成，
--           系统只负责发现与提醒。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本迁移最重要的一件事：**跟卖数据只能作为参考，处置动作在平台侧**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「跟卖数据只能作为参考，处置动作（投诉/举报）在平台侧完成，
-- 系统只负责发现与提醒」落到本迁移上是三件事，缺一不可：
--
--   ① **本表里没有任何处置状态**。没有 complaint_id、没有 ticket_no、
--      没有「已投诉 / 已举报」列。跟卖记录与预警记录只回答两个问题：
--      「有没有人在跟卖」与「这个跟卖价低到什么程度」。
--      真要投诉，去平台侧做 —— 系统连一个「发起投诉」的入口都不提供。
--      （服务层的 `capabilities` 里 `brand_protection_enforcement` 与
--      `workbench_alert_notification` 两维 available=false + ownerModule。）
--
--   ② **每个响应都带「仅供参考」的标注**。后端每个列表 / 详情 / 对比视图都带
--      `dataSource='PLATFORM_SCRAPED'` 与 `sourceNote`，前端把它固定显示在页面顶部。
--      抓取来的跟卖数据是**平台侧的一个观测**，不是平台侧的裁决 ——
--      抓取可能滞后、可能漏、可能抓到已经下架的 offer。
--
--   ③ **预警是「记录」，不是「通知」**。`listing_hijacker_alert` 落一条记录就完事，
--      本模块**不主动发通知**（1 工作台预警未交付）。通知那一步是别人的活，
--      在这里伪造一个「已通知」字段比不通知更危险 —— 运营会以为通知已经发出去了。
--
-- ---------------------------------------------------------------------------
-- 二、三张表：监控对象 / 跟卖记录 / 预警记录
-- ---------------------------------------------------------------------------
--   listing_hijacker_watch    监控对象（按商品 / ASIN 配置要监控的跟卖，租户内可维护）
--   listing_hijacker_record   跟卖记录（跟卖者、价格、店铺、所在平台、发现时间）
--   listing_hijacker_alert    预警记录（异常跟卖 / 恶意压价）
--
-- 为什么是三张而不是一张：
--   * **监控对象是配置**，不是事实。它可以被暂停、改阈值、删除；它与某次抓取无关。
--   * **跟卖记录是事实**（某时刻观察到某人在跟卖、价多少）。它只追加，不修改语义。
--   * **预警记录是判定结论**（这条记录命中了「恶意压价」/「异常跟卖」）。
--     把判定结果挂在记录行上会出现「一行既是被观察到的数据、又是结论」，
--     混在一起之后想回答「哪些是预警」就得在代码里写 if。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 比率**一律不落表**，SQL 现算（照抄 4.5.1.1 Listing 表现的纪律）
-- ---------------------------------------------------------------------------
-- 「价格差比例」=（自家参考价 − 跟卖价）÷ 自家参考价
-- 这个量**没有落点**在本模块任何一张表里：
--   * 落了表就会与事实漂移（改一次参考价，那一列的旧比例不会跟着变）；
--   * **分母（自家参考价）为 0 时必须返回 null**，不是 0 也不是 100 ——
--     报 0 会被读成「跟卖价和自家价一样」，那是结论不是缺口。
-- 所以对比视图里它是 **SQL 现算** 的列，本迁移**零个比例列**。
--
-- ⚠️ 唯一落表的「比例」是 `listing_hijacker_watch.price_gap_ratio_threshold`
-- —— 那是**配置阈值**（运营自己定的门槛），不是由事实算出来的量，
-- 落表是对的（它是输入，不是结论）。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 自家参考价：05 定价整卷未交付 ⇒ 人工维护的参考值，不是平台权威价
-- ---------------------------------------------------------------------------
-- 实测集成头：卷 05 定价 0 / 19 未交付，全库不存在任何「平台 listing 售价」事实表
-- （与 4.1.1.1 的结论一致）。所以本模块**不伪造一个价格来源**：
--
--   * `listing_hijacker_watch.reference_price` 是**租户手工维护的自家参考售价**，
--     列注释与表注释都写明「参考值，不是平台回流的权威价」；
--   * 不给 = 没有参考价 ⇒ 异常跟卖（按价格差判定的那条）**不判定**，
--     只有「恶意压价阈值」那条还能命中；
--   * 05 定价交付后，它会被平台侧的权威售价取代或与之对账，
--     在那之前**不假装**已经有第二个价格事实来源。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 跟卖者的「所在平台」不冗余：它从被监控 listing 派生
-- ---------------------------------------------------------------------------
-- 跟卖是**同一个平台上**的事（同一个 ASIN、同一个 marketplace 上的 offer）。
-- 冗余一列 `platform_id` 到跟卖记录上，就会出现「记录说在 A 平台、
-- 而被监控的 listing 在 B 平台」这种自相矛盾的数据 —— 而跟卖记录的正确性
-- 恰恰是本模块最要紧的事（它会驱动预警与对比视图）。
-- 所以：**平台不落列**，一律由 `listing → shop → platform` 派生。
-- 服务层把派生出来的平台码写进响应（「所在平台」这个信息一个不少），
-- 但**不存第二份可能不一致的答案**。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 己方其他店铺跟卖：是**算出来**的，不是调用方声明的
-- ---------------------------------------------------------------------------
-- 蓝图职责句写的是「监控**他人/己方其他店铺**对本 ASIN 的跟卖行为」——
-- 自家另一个店铺跟自家 listing 同样是渠道冲突，必须被盯上。
-- 但「这个跟卖者是不是我们自己人」**不能**由调用方说了算
-- （否则一个抓取适配器报错了类型，整条数据的性质就错了）。
-- 落法：`listing_hijacker_record.hijacker_shop_id` 由服务层按跟卖者的
-- **平台侧店铺标识**去本租户的 `shop.platform_shop_id` 匹配出来 ——
-- 匹配上就是自家店铺（OWN_SHOP），匹配不上就是外部跟卖者（EXTERNAL）。
-- 服务层**不接受**调用方直接指定这个性质。
--
-- ---------------------------------------------------------------------------
-- 七、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   价格（跟卖价 / 自家参考价 / 压价阈值）  numeric(19,4)
--   比例阈值（配置项）                      numeric(9,6)
--   时间（发现时间 / 预警时刻）              timestamptz
--   币种                                    char(3)
-- 无 float / double 一列。金额一律 BigDecimal。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V171 `listing`（被监控的商品；UNIQUE(tenant_id,id) 是本模块复合外键的目标）
--   * V1   `shop`（自有店铺；UNIQUE(tenant_id,id) + platform_shop_id 用来认自家店铺）
--   * V1   `channel_scope(...)`（数据范围策略，与 V171/V199/V203 同一套）
--
-- 本迁移**零 ALTER 任何既有表**、零跨服务 FK、零跨库 SELECT。
--
-- ===========================================================================
-- 1. listing_hijacker_watch · 监控对象
-- ===========================================================================
CREATE TABLE listing_hijacker_watch (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 被监控的自家 listing（跟卖的目标就是这个 ASIN）
  listing_id        uuid NOT NULL,
  -- 🔴 平台商品 ID 原文（ASIN）—— 冗余一份便于按 ASIN 检索与人工核对
  external_item_id  varchar(128) NOT NULL,
  -- 🔴 自家参考售价（**人工维护的参考值，不是平台回流的权威价**；05 定价未交付）
  reference_price   numeric(19,4),
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 恶意压价阈值：跟卖价 ≤ 该值 ⇒ 判为「恶意压价」。不给 = 不判定这一条
  price_cut_threshold numeric(19,4),
  -- 🔴 异常跟卖的价格差比例阈值：跟卖价比自家参考价低出该比例 ⇒ 判为「异常跟卖」
  --     （**配置项**，不是由事实算出来的比例，所以落表是对的；不给 = 不判定这一条）
  price_gap_ratio_threshold numeric(9,6),
  -- 🔴 状态：ACTIVE 监控中 / PAUSED 暂停。暂停只停「接收与判定」，
  --     已有记录与预警**不删**（历史事实不因为配置变了就消失）
  status            text NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','PAUSED')),
  note              varchar(500) NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一个 listing 一个监控对象 —— 「同一个 ASIN 被配了两套阈值」是配置错误
  UNIQUE(tenant_id, listing_id),
  CHECK(reference_price IS NULL OR reference_price >= 0),
  CHECK(price_cut_threshold IS NULL OR price_cut_threshold >= 0),
  -- 比例阈值 ∈ [0,1)：1.0 意味着「比自家价低 100% ��算异常」= 永远不命中
  CHECK(price_gap_ratio_threshold IS NULL
        OR (price_gap_ratio_threshold >= 0 AND price_gap_ratio_threshold < 1)),
  -- 🔴 复合外键带 tenant_id ⇒ 跨租户挂引用在物理上不可能
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_hijacker_watch IS
  '4.5.2.1 · **监控对象**（按商品 / ASIN 配置要监控的跟卖，租户内可维护）。'
  'reference_price 是**租户人工维护的自家参考售价**（05 定价整卷未交付，'
  '系统内没有平台权威售价这个事实来源）；price_cut_threshold / price_gap_ratio_threshold '
  '是**配置阈值**，不是结论。**本表不含任何处置状态** —— 投诉 / 举报在平台侧完成。';
COMMENT ON COLUMN listing_hijacker_watch.reference_price IS
  '自家参考售价（人工维护，**非平台权威价**；05 定价未交付）。不给 = 异常跟卖的价格差判定不生效。';
COMMENT ON COLUMN listing_hijacker_watch.price_gap_ratio_threshold IS
  '异常跟卖的价格差比例阈值（**配置项**）：跟卖价比自家参考价低出该比例即判为异常跟卖。';

-- ===========================================================================
-- 2. listing_hijacker_record · 跟卖记录（事实）
-- ===========================================================================
CREATE TABLE listing_hijacker_record (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 挂在哪个监控对象下（复合外键 ⇒ 同租户）
  watch_id          uuid NOT NULL,
  -- 🔴 被跟卖的商品（冗余一份：既是查询热路径，也让 RLS 的数据范围策略可以直查 listing）
  listing_id        uuid NOT NULL,
  -- 🔴 跟卖者（卖家标识，平台侧原文）
  hijacker_seller_id varchar(128) NOT NULL,
  -- 🔴 跟卖者所在店铺的**平台侧标识**（如 Amazon Seller ID）——
  --    它是「这个跟卖者是不是我们自己人」的判定依据（见第六节）
  hijacker_shop_identifier varchar(128) NOT NULL,
  -- 🔴 跟卖者店铺名（平台侧原文，仅供展示）
  hijacker_shop_name varchar(200) NOT NULL DEFAULT '',
  -- 🔴 命中本租户自有店铺时写入（**由服务层按平台侧店铺标识匹配得出**，
  --    调用方不能直接指定）—— 复合外键 ⇒ 不可能指到别的租户的店铺
  hijacker_shop_id  uuid,
  -- 🔴 跟卖价格（**平台侧观测到的价格**，不是内部定价）
  hijacker_price   numeric(19,4) NOT NULL,
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 发现时间（平台侧发现这条跟卖的时刻；timestamptz）
  observed_at       timestamptz NOT NULL,
  -- 🔴 来源：PLATFORM 平台接口 / SCRAPE 定时抓取 / API 人工补录
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','SCRAPE','API')),
  -- 🔴 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 批次幂等键：**一个抓取批次一个键**（平台重投同一批时靠它整批跳过）。
  --     它**不唯一** —— 一个批次里有 N 条跟卖记录，N 行共用同一个键；
  --     记录级的幂等由下面那条 UNIQUE(tenant_id, watch_id, hijacker_seller_id, observed_at) 承担。
  --     批次级的「这一批已经落过了」由服务层按
  --     (tenant_id, watch_id, observed_at, batch_idempotency_key) 精确判定。
  batch_idempotency_key varchar(160) NOT NULL,
  -- 平台给的原文结构（jsonb，原样存，不解释不加工）
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一个跟卖者在同一时刻被观察到 ⇒ 只有一行（不是"多抓几次多条"）
  UNIQUE(tenant_id, watch_id, hijacker_seller_id, observed_at),
  CHECK(hijacker_price >= 0),
  CHECK(btrim(hijacker_seller_id) <> ''),
  CHECK(btrim(hijacker_shop_identifier) <> ''),
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_hijacker_watch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  -- 🔴 复合外键带 tenant_id ⇒ 不可能指到别的租户的店铺。
  --     ON DELETE 取默认的 NO ACTION（与 V171 `listing.shop_id` 同口径）——
  --     不用 SET NULL：复合外键的 SET NULL 会把 tenant_id 一起置空，而它是 NOT NULL。
  --     店铺是**归档**（status='ARCHIVED'）不是硬删，所以这条约束拦不到正常流程。
  FOREIGN KEY(tenant_id, hijacker_shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE listing_hijacker_record IS
  '4.5.2.1 · **跟卖记录**（跟卖者、价格、店铺、所在平台、发现时间）。'
  '一行 = 一次观察到的跟卖事实。**平台由 listing → shop → platform 派生，不冗余一列**'
  '（冗余就会出现「记录说在 A 平台、被监控商品在 B 平台」这种自相矛盾的数据）。'
  'hijacker_shop_id 由服务层按平台侧店铺标识匹配自有店铺得出 —— '
  '**己方其他店铺跟卖**因此是被算出来的，不是被声明出来的。'
  '**本表不构成任何处置结论**：投诉 / 举报在平台侧完成。';
COMMENT ON COLUMN listing_hijacker_record.hijacker_price IS
  '跟卖价格（**平台侧观测值**，非内部定价口径；05 定价未交付，系统内没有权威售价）。';

-- ===========================================================================
-- 3. listing_hijacker_alert · 预警记录（判定结论）
-- ===========================================================================
CREATE TABLE listing_hijacker_alert (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  watch_id          uuid NOT NULL,
  -- 🔴 触发这条预警的跟卖记录（一条记录最多一条预警）
  record_id         uuid NOT NULL,
  listing_id        uuid NOT NULL,
  -- 🔴 预警类型：MALICIOUS_PRICE_CUT 恶意压价 / ANOMALOUS_HIJACK 异常跟卖
  alert_type        text NOT NULL
                    CHECK(alert_type IN ('MALICIOUS_PRICE_CUT','ANOMALOUS_HIJACK')),
  -- 🔴 严重度：HIGH / MEDIUM
  severity          text NOT NULL CHECK(severity IN ('HIGH','MEDIUM')),
  -- 🔴 触发时刻的跟卖价快照（**快照**，防后续改价让预警的含义漂移）
  observed_price    numeric(19,4) NOT NULL,
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 判定时刻的自家参考售价**快照**（不是当前值 —— 事后改了参考价，
  --     这条预警的依据仍然是当时那个数）
  reference_price   numeric(19,4),
  -- 🔴 状态：OPEN 未处理 / ACKNOWLEDGED 已阅。
  --     **这是内部的记录状态，不是平台侧的处置状态** ——
  --     投诉 / 举报 / 申诉在平台侧完成，本模块连入口都不提供。
  status            text NOT NULL DEFAULT 'OPEN'
                    CHECK(status IN ('OPEN','ACKNOWLEDGED')),
  -- 🔴 命中了哪条规则（写明依据，半年后还看得懂为什么报这一条）
  reason            varchar(500) NOT NULL DEFAULT '',
  acknowledged_at   timestamptz,
  acknowledged_by   uuid,
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  detected_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一条跟卖记录最多一条预警（判定是"是/否"的事，不是次数的事）
  UNIQUE(tenant_id, record_id),
  CHECK(observed_price >= 0),
  FOREIGN KEY(tenant_id, record_id) REFERENCES listing_hijacker_record(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, watch_id) REFERENCES listing_hijacker_watch(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_hijacker_alert IS
  '4.5.2.1 · **预警记录**（异常跟卖 / 恶意压价）。'
  '🔴 **这是记录，不是通知**：本模块不主动发通知（1 工作台预警未交付），'
  'status=ACKNOWLEDGED 只是内部「已阅」，**不代表已在平台侧投诉或举报**。'
  '一条跟卖记录最多一条预警（UNIQUE(tenant_id, record_id)）。'
  '**价格差比例不落表** —— 它是现算的量，落了表就会与事实漂移。';

-- ===========================================================================
-- 4. 索引
-- ===========================================================================
-- 监控对象侧：按状态筛「在盯哪些 ASIN」
CREATE INDEX listing_hijacker_watch_status
  ON listing_hijacker_watch(tenant_id, status, created_at DESC);
-- 监控对象侧：按商品取
CREATE INDEX listing_hijacker_watch_listing
  ON listing_hijacker_watch(tenant_id, listing_id);
-- 跟卖记录侧：某个监控对象的时间线（最热路径：按发现时间倒序取区间）
CREATE INDEX listing_hijacker_record_watch_time
  ON listing_hijacker_record(tenant_id, watch_id, observed_at DESC);
-- 跟卖记录侧：按商品取（跨监控对象看某个 ASIN 的全部跟卖）
CREATE INDEX listing_hijacker_record_listing_time
  ON listing_hijacker_record(tenant_id, listing_id, observed_at DESC);
-- 跟卖记录侧：按跟卖者取（"这个卖家到底在跟我们几次"）
CREATE INDEX listing_hijacker_record_seller
  ON listing_hijacker_record(tenant_id, hijacker_seller_id, observed_at DESC);
-- 预警侧：按状态 + 时间（工作台式的"未处理预警"列表）
CREATE INDEX listing_hijacker_alert_status_time
  ON listing_hijacker_alert(tenant_id, status, detected_at DESC);
-- 预警侧：按监控对象 + 时间
CREATE INDEX listing_hijacker_alert_watch_time
  ON listing_hijacker_alert(tenant_id, watch_id, detected_at DESC);

-- ===========================================================================
-- 5. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 🔴 跟卖记录的商品必须与它的监控对象一致。
--     "这条跟卖是冲着哪个 ASIN 来的" 与 "它挂在哪个监控对象下" 必须答得上来。
CREATE FUNCTION guard_listing_hijacker_record_watch() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  w listing_hijacker_watch%ROWTYPE;
BEGIN
  SELECT * INTO w FROM listing_hijacker_watch
   WHERE tenant_id=NEW.tenant_id AND id=NEW.watch_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing hijacker record % must reference an existing watch', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF w.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing hijacker record % must reference a watch of the same listing',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_hijacker_record_watch BEFORE INSERT OR UPDATE ON listing_hijacker_record
  FOR EACH ROW EXECUTE FUNCTION guard_listing_hijacker_record_watch();

-- (b) 🔴 跟卖者不能是「被监控 listing 自己所在的店铺」。
--     那不是跟卖，是自家 listing 本身 —— 把它当跟卖记一条，
--     运营会对着自己的商品去查"谁在跟卖我"。
CREATE FUNCTION guard_listing_hijacker_record_not_self() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  own_shop_id uuid;
BEGIN
  SELECT s.id INTO own_shop_id
    FROM listing_hijacker_watch w
    JOIN listing l ON l.tenant_id=w.tenant_id AND l.id=w.listing_id
    JOIN shop s ON s.tenant_id=l.tenant_id AND s.id=l.shop_id
   WHERE w.tenant_id=NEW.tenant_id AND w.id=NEW.watch_id;
  IF own_shop_id IS NOT NULL AND NEW.hijacker_shop_id IS NOT NULL
     AND NEW.hijacker_shop_id = own_shop_id THEN
    RAISE EXCEPTION 'Listing hijacker record % points at the watched listing own shop', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_hijacker_record_not_self BEFORE INSERT OR UPDATE ON listing_hijacker_record
  FOR EACH ROW EXECUTE FUNCTION guard_listing_hijacker_record_not_self();

-- (c) 🔴 预警引用的跟卖记录必须与它是同一个监控对象、同一个商品。
CREATE FUNCTION guard_listing_hijacker_alert_consistency() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  r listing_hijacker_record%ROWTYPE;
BEGIN
  SELECT * INTO r FROM listing_hijacker_record
   WHERE tenant_id=NEW.tenant_id AND id=NEW.record_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing hijacker alert % must reference an existing record', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF r.watch_id <> NEW.watch_id OR r.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing hijacker alert % must reference a record of the same watch and listing',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_hijacker_alert_consistency BEFORE INSERT OR UPDATE ON listing_hijacker_alert
  FOR EACH ROW EXECUTE FUNCTION guard_listing_hijacker_alert_consistency();

-- (d) 🔴 预警一旦被「已阅」，它的判定结论与触发价格**不可再改**。
--     预警是"当时为什么报这一条"的凭据；改它等于改历史。
CREATE FUNCTION guard_listing_hijacker_alert_final() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status = 'ACKNOWLEDGED' AND (
       NEW.alert_type IS DISTINCT FROM OLD.alert_type
    OR NEW.severity IS DISTINCT FROM OLD.severity
    OR NEW.observed_price IS DISTINCT FROM OLD.observed_price
    OR NEW.reason IS DISTINCT FROM OLD.reason
    OR NEW.record_id IS DISTINCT FROM OLD.record_id) THEN
    RAISE EXCEPTION 'Listing hijacker alert % is acknowledged; its conclusion is immutable',
      OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_hijacker_alert_final BEFORE UPDATE ON listing_hijacker_alert
  FOR EACH ROW EXECUTE FUNCTION guard_listing_hijacker_alert_final();

-- ===========================================================================
-- 6. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
-- 隔离测试就成了自欺欺人（9.5.2.1 实测踩过）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_hijacker_watch','listing_hijacker_record','listing_hijacker_alert'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性随**商品所属店铺**收敛（与 V171 listing_status_event / V203 表现快照同构）：
-- 运营不能通过跟卖数据看到无权店铺的商品被跟卖的情况。
CREATE POLICY data_scope ON listing_hijacker_watch AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_watch.tenant_id
             AND l.id=listing_hijacker_watch.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_watch.tenant_id
             AND l.id=listing_hijacker_watch.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_hijacker_record AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_record.tenant_id
             AND l.id=listing_hijacker_record.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_record.tenant_id
             AND l.id=listing_hijacker_record.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_hijacker_alert AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_alert.tenant_id
             AND l.id=listing_hijacker_alert.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_hijacker_alert.tenant_id
             AND l.id=listing_hijacker_alert.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ===========================================================================
-- 7. 账号与授权（沿用 V1..V203 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_hijacker_watch TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_hijacker_record TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_hijacker_alert TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V203 同口径）。
REVOKE ALL ON listing_hijacker_watch FROM starcon_app;
REVOKE ALL ON listing_hijacker_record FROM starcon_app;
REVOKE ALL ON listing_hijacker_alert FROM starcon_app;

-- ===========================================================================
-- 8. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：跟卖监控属于「平台操作/查看」语义，
-- 与 4.1.1.1 列表、4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁同族，
-- identity 服务零改动。
