-- ===========================================================================
-- 4.5.1.1 Listing 表现（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.5.1.1 Listing 表现
--   - 职责：单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、
--           评分变化，支持时间范围对比。
--   - 输入：listing_id；平台表现数据同步结果；时间范围。
--   - 输出：表现指标 + 趋势图；下钻到 13 产品表现。
--   - 关系：上游 = 02 同步的平台流量数据；下游 = 13 产品表现、14 广告（关联投放）。
--           **表现数据来自平台而非内部订单**，与内部订单口径不同，展示时需标注来源。
--
-- ---------------------------------------------------------------------------
-- 一、🔴🔴 本迁移最重要的一件事：**数据来自平台，不是内部订单**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「表现数据来自平台而非内部订单，与内部订单口径不同，展示时需标注来源」
-- 落到本迁移上是三件事，缺一不可：
--
--   ① **表里存的每一个数都是平台回报的口径**，不是内部订单聚合出来的：
--      曝光 / 点击 / Session / 销量 / 销售额 / 退货件数 / 退货金额 / 评分 / 评价数。
--      列名与表注释都写明「平台口径，非内部订单」——
--      半年后有人看到 `units_ordered` 会本能地以为那是内部订单行数，
--      而平台的「销量」与内部订单行数**天然对不上**（平台按平台自然日归集、
--      含取消与部分退款、内部订单按本系统单据归集），两个数混用会直接做出
--      错误的选品结论。
--
--   ② **本模块不去 trade 的订单表里聚合**。AGENTS.md 明令禁止跨服务查表，
--      本迁移**零 ALTER 任何既有表**、零跨服务 FK、零跨库 SELECT：
--      表现数据只能由 02 同步 / 平台适配器**推进来**（ingest 端点）。
--
--   ③ **展示层必须标注来源**。后端每个响应都带 `dataSource` / `sourceNote`，
--      前端把它显示在指标区顶部 —— 这是蓝图的硬要求，不是可选项。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 比率**一律不落表**，SQL 现算（照抄 9.5.2.1 履约质量）
-- ---------------------------------------------------------------------------
-- 转化率 / 退货率 / 点击率全部由分子分母现算：
--   点击率 CTR   = clicks / impressions
--   转化率       = units_ordered / sessions
--   退货率       = returns_count / units_ordered
-- **分母为 0 时返回 null，而不是 0 或 100** —— 报 0 会被读成「转化率 0%」，
-- 那是结论不是缺口；报 100 更荒谬。
-- 落了表就会与事实漂移（改了分母那一行，比率列不会跟着变），
-- 所以本迁移**没有**任何比率列，NUMERIC(9,6) 这个类型在本模块没有落点。
--
-- ---------------------------------------------------------------------------
-- 三、两张表：一张是事实，一张是**同步结果**
-- ---------------------------------------------------------------------------
--   listing_performance_snapshot  平台口径的表现快照（一行 = 一个 listing 的一个统计日）
--   listing_performance_sync     平台表现数据的**同步结果**（蓝图输入项）
--
-- 为什么同步结果要单独一张表：蓝图输入写的是「平台表现数据**同步结果**」，
-- 不是「平台表现数据」。一次同步是一个**有范围、有通道、有成败**的整体
-- （覆盖了哪几天、哪几天被跳过、走的哪条通道），这些元信息挂在快照行上会
-- 出现「同一批数据被拆成 N 行、每行各说各话」的局面。
-- 分开之后，「还没有同步结果」与「同步过了但这个区间没有数据」是**两回事**
-- （前端空态必须分开渲染），而这个区分靠的就是这张表。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 幂等与覆盖的两级语义
-- ---------------------------------------------------------------------------
--   * **批次级幂等**：listing_performance_sync.idempotency_key 在租户内唯一。
--     同一个 key 再次投递 ⇒ **整批不再落库**，直接返回既有那条同步记录。
--     key 由平台适配器提供（平台报表 id + 区间之类），服务端**不替调用方编**。
--   * **同日覆盖**：同一个 (listing, stat_date, source) 只有一行。
--     平台**修正**同一天的报表时用**新的**批次 key 推进来 ⇒ 该行被覆盖，
--     `revision` 递增、`last_sync_id` 指向最后一次写入它的批次。
--     「同一天只能有一行」是 UNIQUE 约束的结构事实，不靠服务层自觉。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
--   金额（销售额 / 退货金额）  numeric(19,4)   + char(3) 币种
--   数量（曝光 / 点击 / Session / 销量 / 退货件数）  numeric(19,6)
--   评分                      numeric(9,4)    （与 V171 listing.rating 同型）
--   评价数                    numeric(19,6)   （与 V171 listing.rating_count 同型）
--   时间                      timestamptz
--   日期（统计日）            date
-- 无 float / double 一列。金额一律 BigDecimal。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 依赖面：只引用同服务已交付的表，零跨车道 DDL 回归
-- ---------------------------------------------------------------------------
--   * V171 `listing`（商品本体；UNIQUE(tenant_id, id) 是本模块复合外键的目标）
--   * V1   `channel_scope(...)`（数据范围策略，与 V171/V199 同一套）
--
-- 下游 = 13 产品表现、14 广告 —— **两段都未交付**，
-- 都在 `GET /capabilities` 里显式声明 available=false + ownerModule + impact，
-- **不假装具备**，前端据此显示「未接入」而不是显示假数据。
--
-- ===========================================================================
-- 1. listing_performance_sync · 平台表现数据的同步结果
-- ===========================================================================
CREATE TABLE listing_performance_sync (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 🔴 同步结果挂在**哪个商品**上（平台回报总是针对某个 listing）
  listing_id        uuid NOT NULL,
  -- 🔴 来源：PLATFORM 平台回报（唯一真实口径）/ API 人工补录
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','API')),
  -- 🔴 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 平台给的口径区间（timestamptz；服务层用 OffsetDateTime 绑定，
  --    values 位置写 ?::timestamptz —— 4.1.1.1 实测踩过 jOOQ 绑 varchar 被 PG 拒的坑）
  period_start      timestamptz NOT NULL,
  period_end        timestamptz NOT NULL,
  -- 🔴 覆盖的**统计日**范围（date）。平台按自然日归集，所以快照按日存。
  stat_from         date NOT NULL,
  stat_to           date NOT NULL,
  -- 本批收到多少条快照（事实：payload 里带了多少条）
  received_count    integer NOT NULL DEFAULT 0,
  -- 其中多少条被跳过（落在声明区间外 —— 记在 detail.skipped 里，**不静默丢弃**）
  skipped_count     integer NOT NULL DEFAULT 0,
  -- 🔴 状态机：RECEIVED 已接收（写入瞬间）→ APPLIED 全部落库 / PARTIAL 部分落库。
  --    终态不可再改（DB 触发器钉死）—— 同步结果是**只追加**的证据。
  status            text NOT NULL DEFAULT 'RECEIVED'
                    CHECK(status IN ('RECEIVED','APPLIED','PARTIAL')),
  -- 🔴 幂等键：租户内唯一。重复投递同一个 key ⇒ 整批不再落库。
  idempotency_key   varchar(160) NOT NULL,
  -- 平台给的原文结构（jsonb，含 skipped 明细），平台给什么存什么，不解释不加工
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  note              varchar(500) NOT NULL DEFAULT '',
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, idempotency_key),
  CHECK(period_end >= period_start),
  CHECK(stat_to >= stat_from),
  CHECK(received_count >= 0),
  CHECK(skipped_count >= 0),
  -- 🔴 跳过数不许超过收到数（否则「全部落库」与「部分落库」就分不清了）
  CHECK(skipped_count <= received_count),
  -- 🔴 复合外键带 tenant_id ⇒ 跨租户挂引用在物理上不可能
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_performance_sync IS
  '4.5.1.1 · 平台表现数据的**同步结果**（蓝图输入项）。一次同步 = 一个有范围、有通道、'
  '有成败的整体；快照行是它的明细。idempotency_key 租户内唯一 ⇒ 重复投递整批不再落库。'
  '本表记录的是**平台口径**的覆盖范围，不含任何内部订单派生的数字。';

-- ===========================================================================
-- 2. listing_performance_snapshot · 平台口径的表现快照（事实）
-- ===========================================================================
CREATE TABLE listing_performance_snapshot (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  listing_id        uuid NOT NULL,
  -- 🔴 统计日：**平台自己给的那一天**（平台自然日口径），不是内部下单日。
  --    这两个日期不是一回事，趋势按平台日归集才对得上平台的看板。
  stat_date         date NOT NULL,
  -- 🔴 曝光（平台回报口径，非内部订单）
  impressions       numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 点击（平台回报口径，非内部订单）
  clicks            numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 Session（平台回报口径，非内部订单）
  sessions          numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 销量（**平台口径，非内部订单**）：平台侧的成交件数
  units_ordered     numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 销售额（**平台口径，非内部订单**）：平台侧的成交金额
  sales_amount      numeric(19,4) NOT NULL DEFAULT 0,
  currency          char(3) NOT NULL DEFAULT '',
  -- 🔴 退货件数 / 退货金额（**平台口径，非内部订单**）
  returns_count     numeric(19,6) NOT NULL DEFAULT 0,
  return_amount     numeric(19,4) NOT NULL DEFAULT 0,
  -- 🔴 平台评分与评价数（**期末值**：当天平台给的评分/评价数快照）
  rating            numeric(9,4),
  rating_count      numeric(19,6),
  -- 🔴 来源与通道（冗余一份，便于按通道筛「哪些是轮询补捞的」）
  source            text NOT NULL DEFAULT 'PLATFORM'
                    CHECK(source IN ('PLATFORM','API')),
  ingest_channel    text NOT NULL DEFAULT 'API'
                    CHECK(ingest_channel IN ('WEBHOOK','POLL','API')),
  -- 🔴 最后一次写入这行的同步批次（复合外键 ⇒ 同租户且必须同商品，见下方触发器）
  last_sync_id      uuid NOT NULL,
  -- 🔴 覆盖次数：平台修正同一天报表时递增（1 = 平台首次回报）
  revision          integer NOT NULL DEFAULT 1,
  -- 平台给的原文结构（jsonb）。平台若自报了转化率/退货率，原样存这里，
  -- **不**另开一列 —— 一个指标两个答案比少一个答案更糟。
  detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
  received_at       timestamptz NOT NULL DEFAULT now(),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 同一天、同一来源只有一个数 —— UNIQUE 约束的结构事实，不靠服务层自觉
  UNIQUE(tenant_id, listing_id, stat_date, source),
  CHECK(impressions >= 0),
  CHECK(clicks >= 0),
  CHECK(sessions >= 0),
  CHECK(units_ordered >= 0),
  CHECK(sales_amount >= 0),
  CHECK(returns_count >= 0),
  CHECK(return_amount >= 0),
  CHECK(revision >= 1),
  -- 🔴 销量为 0 时退货件数不可能大于 0（平台口径自洽）
  CHECK(units_ordered > 0 OR returns_count = 0),
  FOREIGN KEY(tenant_id, listing_id) REFERENCES listing(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, last_sync_id) REFERENCES listing_performance_sync(tenant_id, id)
    ON DELETE CASCADE
);
COMMENT ON TABLE listing_performance_snapshot IS
  '4.5.1.1 · **平台口径**的表现快照（一行 = 一个 listing 的一个平台统计日）。'
  '曝光/点击/Session/销量/销售额/退货/评分 全部是**平台回报**的口径，'
  '**非内部订单** —— 平台销量与内部订单行数天然对不上，混用会做出错误的选品结论。'
  '转化率/退货率/点击率**不落表**，由 SQL 现算（落了表就会与事实漂移）。'
  'UNIQUE(tenant_id, listing_id, stat_date, source) ⇒ 同一天只有一个数。';

-- ===========================================================================
-- 3. 索引
-- ===========================================================================
-- 快照侧：单个商品的趋势（最热路径：按天倒序取区间）
CREATE INDEX listing_performance_snapshot_listing_time
  ON listing_performance_snapshot(tenant_id, listing_id, stat_date DESC);
-- 快照侧：全租户按时间范围扫（表现列表的时间筛选走这条）
CREATE INDEX listing_performance_snapshot_time
  ON listing_performance_snapshot(tenant_id, stat_date DESC);
-- 同步侧：某个商品的同步历史（回答「这个商品的平台数据什么时候同步过」）
CREATE INDEX listing_performance_sync_listing
  ON listing_performance_sync(tenant_id, listing_id, received_at DESC);
-- 同步侧：按通道筛（区分「平台推送」与「轮询补捞」）
CREATE INDEX listing_performance_sync_channel
  ON listing_performance_sync(tenant_id, ingest_channel, received_at DESC);

-- ===========================================================================
-- 4. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 🔴 快照引用的同步批次必须**存在**且**是同一个商品**的。
--     「这条表现数据是从哪次同步来的」与「这次同步说的是不是同一个商品」
--     —— 两件事都必须答得上来。
CREATE FUNCTION guard_listing_performance_snapshot_sync() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE
  sy listing_performance_sync%ROWTYPE;
BEGIN
  SELECT * INTO sy FROM listing_performance_sync
   WHERE tenant_id=NEW.tenant_id AND id=NEW.last_sync_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing performance snapshot % must reference an existing sync batch', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF sy.listing_id <> NEW.listing_id THEN
    RAISE EXCEPTION 'Listing performance snapshot % must reference a sync batch of the same listing',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_performance_snapshot_sync BEFORE INSERT OR UPDATE ON listing_performance_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_listing_performance_snapshot_sync();

-- (b) 🔴 同步结果一旦落到终态就**不可再改** —— 它是「当时同步了什么」的凭据。
--     平台后来补报了同一天的数据，那是**新的一条同步记录**（新 key），
--     不是把旧的那条 UPDATE 掉。
--     状态机只允许一步：RECEIVED → APPLIED / PARTIAL。
CREATE FUNCTION guard_listing_performance_sync_final() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status <> 'RECEIVED' THEN
    RAISE EXCEPTION 'Listing performance sync % is already final; a later report is a NEW sync row, not an update',
      OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.status NOT IN ('APPLIED','PARTIAL') THEN
    RAISE EXCEPTION 'Listing performance sync % must move from RECEIVED to APPLIED or PARTIAL', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_performance_sync_final BEFORE UPDATE ON listing_performance_sync
  FOR EACH ROW EXECUTE FUNCTION guard_listing_performance_sync_final();

-- ===========================================================================
-- 5. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
-- 隔离测试就成了自欺欺人（9.5.2.1 实测踩过）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_performance_snapshot','listing_performance_sync'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性随**商品所属店铺**收敛（与 V171 listing_status_event / V199 队列同构）：
-- 运营不能通过表现数据看到无权店铺的商品表现。
CREATE POLICY data_scope ON listing_performance_snapshot AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_performance_snapshot.tenant_id
             AND l.id=listing_performance_snapshot.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_performance_snapshot.tenant_id
             AND l.id=listing_performance_snapshot.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

CREATE POLICY data_scope ON listing_performance_sync AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_performance_sync.tenant_id
             AND l.id=listing_performance_sync.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing l WHERE l.tenant_id=listing_performance_sync.tenant_id
             AND l.id=listing_performance_sync.listing_id
             AND channel_scope(channel_operation(),l.owner_id,l.department_id)));

-- ===========================================================================
-- 6. 账号与授权（沿用 V1..V199 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON listing_performance_snapshot TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_performance_sync TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V199 同口径）。
REVOKE ALL ON listing_performance_snapshot FROM starcon_app;
REVOKE ALL ON listing_performance_sync FROM starcon_app;

-- ===========================================================================
-- 7. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：Listing 表现属于「平台操作/查看」语义，
-- 与 4.1.1.1 列表、4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁同族，
-- identity 服务零改动。
