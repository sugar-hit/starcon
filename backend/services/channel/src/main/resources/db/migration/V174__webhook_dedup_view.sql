-- 02.6.1.2 去重视图。
--
-- 蓝图 §2.6.1.2 全文四句话，实现全部挂在它们上面：
--
--   职责：以 event_id 为准识别重复推送，展示同一业务事件被平台重推的次数与处理结果。
--   输入：重复的 webhook 接收记录。
--   输出：去重命中记录 + 首次处理结果引用。
--   关系：**重复事件不产生第二条业务记录**——这是"重复 webhook 不产生重复订单"的实现基础。
--
-- ---------------------------------------------------------------------------
-- 开工自检：上游 §2.6.1.1 的真实结构（V170__webhook_receipt.sql）
-- ---------------------------------------------------------------------------
-- 蓝图写的列名是 `event_id`，V170 实际落地的是 **`external_event_id`**。
-- 本迁移一律对齐实际列名，不按蓝图想象改名 —— 改名属于改别人的表结构。
--
-- 更要紧的一点：V170 **没有**对 `external_event_id` 建全量唯一约束，它建的是
-- `webhook_receipt_first_delivery` **部分**唯一索引：
--
--     UNIQUE (tenant_id, endpoint_id, external_event_id) WHERE duplicate_of IS NULL
--
-- 也就是说「重复推送被入库时拒绝」这件事**没有发生**。真实行为是：
--   * 重推照样落库，status='DUPLICATE'，duplicate_of 指回首次那条；
--   * 被拒的只是"第二个自称首次的行"（insert ... on conflict do nothing）。
-- 因此本模块的去重命中记录来自**那些 DUPLICATE 行**，而不是被拒的行。
-- 这个事实决定了两件事：
--   1) 视图必须以「一个业务事件 = 一行」归并，而不是简单按 event_id 列明细；
--   2) 去重锚点必须带 endpoint_id —— 部分唯一索引是按 (tenant, endpoint, event)
--      裁决的，只按 (tenant, event) 归并会把两个入口各自独立的事件错并成一条，
--      那才是真正的"重复业务记录"。键与物理裁决口径一致，才谈得上"只处理一次"。
--
-- 为什么是视图而不是再抄一份表：去重是**读时归并**，而"只处理一次"是**写时保证**。
-- 写时的保证已经在 V170 的部分唯一索引 + guard_settled_receipt 触发器里了；
-- 再落一张物化表等于多一个会和真实状态撒谎的地方（重复推送还在进来，副本却不动）。
-- 视图永远读得出当下真实的重推次数。
--
-- security_invoker = true（PG15+）：视图以**调用者**权限查底层表，
-- webhook_receipt 的 isolation + receipt_scope 两层策略照常生效。
-- 用默认的 definer 语义会把租户隔离在视图层抹平 —— 那是最危险的一类假隔离。

-- ---------------------------------------------------------------------------
-- webhook_dedup_event · 一个业务事件一行（去重视图本体）
-- ---------------------------------------------------------------------------
-- first_receipt_id 的取法：优先取 duplicate_of IS NULL 的那条（那才是首次，
-- 也正是所有重复行 duplicate_of 指向的目标）；万一历史数据里有整组都没有首次行，
-- 退化成按接收时间最早的一条。宁可指向一条真实存在的记录，也不给 NULL——
-- NULL 的"首次处理结果引用"等于没有引用。
CREATE VIEW webhook_dedup_event
  WITH (security_invoker = true) AS
SELECT
  g.tenant_id,
  g.endpoint_id,
  g.external_event_id,
  g.first_receipt_id,
  -- 重推次数：蓝图点名要展示的那个量
  g.delivery_count,
  g.duplicate_count,
  g.last_delivery_attempt,
  g.first_received_at,
  g.last_received_at,
  f.connection_id,
  f.shop_id,
  f.event_type,
  f.topic,
  f.signature_status,
  -- 首次处理结果引用：不是只给一个 event_id，处理结论要跟着走
  f.status                AS first_status,
  f.payload_digest,
  f.processing_started_at AS first_processing_started_at,
  f.processed_at          AS first_processed_at,
  f.last_error            AS first_last_error
FROM (
  SELECT
    r.tenant_id,
    r.endpoint_id,
    r.external_event_id,
    coalesce(
      (array_agg(r.id ORDER BY r.received_at, r.id) FILTER (WHERE r.duplicate_of IS NULL))[1],
      (array_agg(r.id ORDER BY r.received_at, r.id))[1]
    ) AS first_receipt_id,
    count(*)::bigint AS delivery_count,
    count(*) FILTER (WHERE r.duplicate_of IS NOT NULL)::bigint AS duplicate_count,
    max(r.delivery_attempt)::int AS last_delivery_attempt,
    min(r.received_at) AS first_received_at,
    max(r.received_at) AS last_received_at
  FROM webhook_receipt r
  GROUP BY r.tenant_id, r.endpoint_id, r.external_event_id
) g
LEFT JOIN LATERAL (
  SELECT f2.*
  FROM webhook_receipt f2
  WHERE f2.tenant_id = g.tenant_id AND f2.id = g.first_receipt_id
) f ON true;

-- ---------------------------------------------------------------------------
-- webhook_dedup_hit · 去重命中记录（每一次被判定为重复的投递）
-- ---------------------------------------------------------------------------
-- 蓝图输出句是「去重命中记录 **+** 首次处理结果引用」，所以命中行必须自带
-- 首次的处理结论，而不是让人再回表查一次才能知道"这次重推其实处理成了没有"。
-- 内连接 duplicate_of：命中记录的定义就是指得回首推行的那一条，
-- 指不上的行（历史脏数据）不是命中记录，不该出现在这个视图里。
CREATE VIEW webhook_dedup_hit
  WITH (security_invoker = true) AS
SELECT
  h.tenant_id,
  h.endpoint_id,
  h.external_event_id,
  h.id           AS receipt_id,
  h.duplicate_of AS first_receipt_id,
  h.delivery_attempt,
  h.received_at,
  h.payload_digest,
  h.signature_status,
  h.status,
  f.status          AS first_status,
  f.processed_at    AS first_processed_at,
  f.last_error      AS first_last_error
FROM webhook_receipt h
JOIN webhook_receipt f
  ON f.tenant_id = h.tenant_id AND f.id = h.duplicate_of
WHERE h.duplicate_of IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------------
-- 视图不存数据，但数据来源是 webhook_receipt，访问形状也得配上索引。
-- V170 已有的 webhook_receipt_event(tenant_id, external_event_id, received_at)
-- 服务的是"同一 event 的全部投递"这条路径。去重视图多出来的是**命中行**这一形状：
-- 只取 duplicate_of IS NOT NULL 的行，并按组键 (endpoint, event) + 时间排。
-- 这是「只看重推过的事件」筛选和 webhook_dedup_hit 的真实访问路径，
-- 走部分索引：命中行在总投递量里通常是小头，不该拖着一大堆首次行一起排。
CREATE INDEX webhook_receipt_duplicate_hits
  ON webhook_receipt(tenant_id, endpoint_id, external_event_id, received_at)
  WHERE duplicate_of IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 账号与授权
-- ---------------------------------------------------------------------------
-- 只给读。视图是读时归并，去重命中记录本身已由 V170 的 GRANT 覆盖，
-- 这里不存在任何"只经由视图写"的需要 —— 视图一律只读，插入路径一律走 webhook_receipt。
GRANT SELECT ON webhook_dedup_event, webhook_dedup_hit TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V169、V2 同口径）：
-- 业务服务要按 event_id 去重，走授权 API（GET /channel/webhook-dedup），
-- 不能直连 channel 的库，也不能拿只读角色窥见别的租户。
REVOKE ALL ON webhook_dedup_event, webhook_dedup_hit FROM starcon_app;
