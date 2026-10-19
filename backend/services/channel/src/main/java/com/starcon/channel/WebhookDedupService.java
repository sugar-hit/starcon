package com.starcon.channel;

import static com.starcon.channel.WebhookDedupModels.*;

import com.starcon.channel.WebhookModels.ReceiptStatus;
import com.starcon.channel.WebhookModels.WebhookReceiptView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.6.1.2 去重视图。
 *
 * <p>这个模块存在的唯一理由是蓝图那句话：<b>重复事件不产生第二条业务记录</b>。
 * 因此实现分成两半，缺一不可：
 *
 * <ol>
 *   <li><b>看得见</b>（读）：以 {@code (endpoint_id, external_event_id)} 把同一业务事件的
 *       全部投递归并成一行，标出被平台重推了几次，并引用首次处理的结果。
 *       归并直接建在 §2.6.1.1 落下的数据上（V174 的两个视图），不另抄一份表——
 *       重复推送还在源源不断进来，任何副本都会和真实状态撒谎。
 *   <li><b>挡得住</b>（写）：{@link #claim} 让下游<b>按业务事件</b>而不是按接收记录去认领。
 *       这是"下游按 event_id 去重"真正落地的地方：重复的投递没有任何别的出口，
 *       谁都拿不到它，第二次认领必然被拒。
 * </ol>
 *
 * <p>关于蓝图写的 {@code event_id}：§2.6.1.1 实际落地到库的列是
 * {@code webhook_receipt.external_event_id}，去重由
 * {@code webhook_receipt_first_delivery} 部分唯一索引
 * （{@code UNIQUE (tenant_id, endpoint_id, external_event_id) WHERE duplicate_of IS NULL}）
 * 裁决。因此本模块一律按真实结构对齐，不按蓝图的字面列名建任何东西。
 */
@Service
public class WebhookDedupService {
  private final DSLContext sql;

  public WebhookDedupService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 读：去重视图
  // ==================================================================

  /**
   * 去重视图列表：一个业务事件一行。
   *
   * <p>筛选与排序都对着真实问题设计：
   *
   * <ul>
   *   <li>{@code duplicatedOnly} —— 「平台在重推哪几条」，接入排障第一个要看的；
   *   <li>{@code firstStatus} —— 「还有哪些事件卡在待处理」，重复再多也不等于处理了；
   *   <li>默认按最近一次推送时间倒序 —— 平台正在重推的东西永远排在最前面。
   * </ul>
   */
  public List<WebhookDedupEventView> events(
      AccessService.Access actor,
      String externalEventId,
      UUID connectionId,
      String firstStatus,
      boolean duplicatedOnly,
      int limit) {
    int capped = Math.min(Math.max(limit, 1), 200);
    boolean byEvent = externalEventId != null && !externalEventId.isBlank();
    boolean byConnection = connectionId != null;
    boolean byStatus = firstStatus != null && !firstStatus.isBlank();

    List<Object> binds = new ArrayList<>();
    binds.add(actor.tenantId());
    if (byEvent) binds.add("%" + externalEventId.trim() + "%");
    if (byConnection) binds.add(connectionId);
    if (byStatus) binds.add(firstStatus);
    binds.add(capped);

    return sql
        .fetch(
            """
            select d.first_receipt_id,d.endpoint_id,d.connection_id,d.shop_id,d.external_event_id,
                   d.event_type,d.topic,d.signature_status,d.delivery_count,d.duplicate_count,
                   d.last_delivery_attempt,d.first_received_at,d.last_received_at,d.first_status,
                   d.payload_digest,d.first_processing_started_at,d.first_processed_at,
                   d.first_last_error,
                   co.authorized_account,p.code platform_code,s.name shop_name
            from webhook_dedup_event d
            join channel_connection co on co.tenant_id=d.tenant_id and co.id=d.connection_id
            join platform p on p.id=co.platform_id
            left join shop s on s.tenant_id=d.tenant_id and s.id=d.shop_id
            where d.tenant_id=?
            """
                + (byEvent ? " and d.external_event_id ilike ?" : "")
                + (byConnection ? " and d.connection_id=?" : "")
                + (byStatus ? " and d.first_status=?" : "")
                + (duplicatedOnly ? " and d.duplicate_count>0" : "")
                + " order by d.last_received_at desc,d.first_received_at desc,d.external_event_id"
                + " limit ?",
            binds.toArray())
        .stream()
        .map(WebhookDedupService::mapEvent)
        .toList();
  }

  private static WebhookDedupEventView mapEvent(Record r) {
    String firstStatus = r.get("first_status", String.class);
    return new WebhookDedupEventView(
        r.get("first_receipt_id", UUID.class),
        r.get("endpoint_id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("authorized_account", String.class),
        r.get("platform_code", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("external_event_id", String.class),
        r.get("event_type", String.class),
        r.get("topic", String.class),
        r.get("signature_status", String.class),
        r.get("delivery_count", Long.class),
        r.get("duplicate_count", Long.class),
        r.get("last_delivery_attempt", Integer.class),
        r.get("first_received_at", Instant.class),
        r.get("last_received_at", Instant.class),
        firstStatus,
        r.get("payload_digest", String.class),
        r.get("first_processing_started_at", Instant.class),
        r.get("first_processed_at", Instant.class),
        r.get("first_last_error", String.class),
        // 判断放在服务端一处：只有首次记录还在等认领时本组才可被处理。
        ReceiptStatus.RECEIVED.name().equals(firstStatus));
  }

  /** 单个业务事件的去重详情：归并行 + 命中记录 + 全部投递时间线。 */
  public WebhookDedupDetail detail(AccessService.Access actor, UUID firstReceiptId) {
    Record group =
        sql.fetchOne(
            """
            select d.first_receipt_id,d.endpoint_id,d.connection_id,d.shop_id,d.external_event_id,
                   d.event_type,d.topic,d.signature_status,d.delivery_count,d.duplicate_count,
                   d.last_delivery_attempt,d.first_received_at,d.last_received_at,d.first_status,
                   d.payload_digest,d.first_processing_started_at,d.first_processed_at,
                   d.first_last_error,
                   co.authorized_account,p.code platform_code,s.name shop_name
            from webhook_dedup_event d
            join channel_connection co on co.tenant_id=d.tenant_id and co.id=d.connection_id
            join platform p on p.id=co.platform_id
            left join shop s on s.tenant_id=d.tenant_id and s.id=d.shop_id
            where d.tenant_id=? and d.first_receipt_id=?
            """,
            actor.tenantId(),
            firstReceiptId);
    if (group == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该外部事件没有接收记录");

    WebhookDedupEventView event = mapEvent(group);
    List<WebhookDedupHitView> hits =
        sql.fetch(
            """
            select receipt_id,first_receipt_id,external_event_id,delivery_attempt,received_at,
                   signature_status,status,payload_digest,first_status,first_processed_at,
                   first_last_error
            from webhook_dedup_hit
            where tenant_id=? and endpoint_id=? and external_event_id=?
            order by received_at,delivery_attempt
            """,
            actor.tenantId(),
            event.endpointId(),
            event.externalEventId())
            .stream()
            .map(WebhookDedupService::mapHit)
            .toList();
    return new WebhookDedupDetail(event, hits, deliveries(actor.tenantId(), event));
  }

  private static WebhookDedupHitView mapHit(Record r) {
    return new WebhookDedupHitView(
        r.get("receipt_id", UUID.class),
        r.get("first_receipt_id", UUID.class),
        r.get("external_event_id", String.class),
        r.get("delivery_attempt", Integer.class),
        r.get("received_at", Instant.class),
        r.get("signature_status", String.class),
        r.get("status", String.class),
        r.get("payload_digest", String.class),
        r.get("first_status", String.class),
        r.get("first_processed_at", Instant.class),
        r.get("first_last_error", String.class));
  }

  /** 全部投递（含首次），按接收时间排。与 {@code hits} 的差别只是"含不含首次那一条"。 */
  private List<WebhookReceiptView> deliveries(UUID tenantId, WebhookDedupEventView event) {
    return sql
        .fetch(
            """
            select r.id,r.endpoint_id,r.connection_id,r.shop_id,r.external_event_id,r.event_type,
                   r.topic,r.signature_status,r.status,r.duplicate_of,r.delivery_attempt,
                   r.payload_digest,r.last_error,r.received_at,r.processing_started_at,
                   r.processed_at,co.authorized_account,p.code platform_code,s.name shop_name
            from webhook_receipt r
            join channel_connection co on co.tenant_id=r.tenant_id and co.id=r.connection_id
            join platform p on p.id=co.platform_id
            left join shop s on s.tenant_id=r.tenant_id and s.id=r.shop_id
            where r.tenant_id=? and r.endpoint_id=? and r.external_event_id=?
            order by r.received_at,r.delivery_attempt
            """,
            tenantId,
            event.endpointId(),
            event.externalEventId())
        .stream()
        .map(
            r ->
                new WebhookReceiptView(
                    r.get("id", UUID.class),
                    r.get("endpoint_id", UUID.class),
                    r.get("connection_id", UUID.class),
                    r.get("authorized_account", String.class),
                    r.get("platform_code", String.class),
                    r.get("shop_id", UUID.class),
                    r.get("shop_name", String.class),
                    r.get("external_event_id", String.class),
                    r.get("event_type", String.class),
                    r.get("topic", String.class),
                    r.get("signature_status", String.class),
                    r.get("status", String.class),
                    r.get("duplicate_of", UUID.class),
                    r.get("delivery_attempt", Integer.class),
                    r.get("payload_digest", String.class),
                    r.get("last_error", String.class),
                    r.get("received_at", Instant.class),
                    r.get("processing_started_at", Instant.class),
                    r.get("processed_at", Instant.class)))
        .toList();
  }

  /**
   * 去重总览。
   *
   * <p>「重复投递总条数」与「发生过重推的事件数」刻意分开算：一个事件被推 5 次会同时
   * 贡献 4 和 1，合成一个数字之后就再也分不开"平台在死磕一条事件"和
   * "很多事件各重推了一次"——这两种情况的处置完全不同。
   */
  public WebhookDedupSummary summary(AccessService.Access actor) {
    Record totals =
        sql.fetchOne(
            """
            select count(*)::bigint total_events,
                   count(*) filter (where duplicate_count>0)::bigint duplicated_events,
                   coalesce(sum(duplicate_count),0)::bigint duplicate_deliveries,
                   count(*) filter (where delivery_count=1)::bigint single_delivery_events,
                   count(*) filter (where first_status in ('RECEIVED','PROCESSING'))::bigint
                     pending_first_events,
                   min(case when duplicate_count>0 then first_received_at end) oldest_duplicate_at
            from webhook_dedup_event
            where tenant_id=?
            """,
            actor.tenantId());
    Record worst =
        sql.fetchOne(
            """
            select external_event_id,duplicate_count
            from webhook_dedup_event
            where tenant_id=? and duplicate_count>0
            order by duplicate_count desc,last_received_at desc
            limit 1
            """,
            actor.tenantId());
    return new WebhookDedupSummary(
        totals.get("total_events", Long.class),
        totals.get("duplicated_events", Long.class),
        totals.get("duplicate_deliveries", Long.class),
        totals.get("single_delivery_events", Long.class),
        totals.get("pending_first_events", Long.class),
        worst == null ? "" : worst.get("external_event_id", String.class),
        worst == null ? 0L : worst.get("duplicate_count", Long.class),
        totals.get("oldest_duplicate_at", Instant.class));
  }

  // ==================================================================
  // 写：按业务事件去重认领（下游消费侧"只处理一次"的落地点）
  // ==================================================================

  /**
   * 按业务事件认领，而不是按接收记录认领。
   *
   * <p>这是"重复事件不产生第二条业务记录"真正被强制执行的地方。下游业务服务拿着
   * 平台事件来消费时，只有首次那一条是它的出口：重复的投递连同它的 id 在这里都拿不到，
   * 所以它没有任何路径能创建出第二条业务记录。
   *
   * <p>第二次认领必然失败：首次记录的状态已经被本次调用改掉了，而认领条件要求
   * {@code status='RECEIVED'}。并发下第二个请求也会因状态已变而落到 0 行，
   * 返回冲突——不会退化成"两次都成功"。
   *
   * <p>之所以不把重复投递的 id 开放成认领入口：一旦重复行也能被认领，
   * "只处理一次"就退化成一条靠自觉遵守的约定，而那正是重复订单的来源。
   */
  public WebhookDedupClaimResult claim(
      AccessService.Access actor, UUID firstReceiptId, WebhookDedupClaimInput input) {
    // first_receipt_id 命中即证明传进来的是首次记录（视图的取法就是 duplicate_of IS NULL 优先），
    // 所以下面只需守住状态与验签两条，不必再复查 duplicate_of。
    Record group =
        sql.fetchOne(
            "select first_receipt_id from webhook_dedup_event where tenant_id=? and first_receipt_id=?",
            actor.tenantId(),
            firstReceiptId);
    if (group == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该外部事件没有接收记录");

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.execute(
            """
            update webhook_receipt
            set status='PROCESSING',
                shop_id=coalesce(?,shop_id),
                processing_started_at=?::timestamptz,
                updated_at=now()
            where tenant_id=? and id=? and status='RECEIVED' and signature_status='VERIFIED'
            """,
            input == null ? null : input.shopId(),
            now,
            actor.tenantId(),
            firstReceiptId);
    if (updated == 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该业务事件已不在待处理状态：重复推送不会产生第二条业务记录，请按首次处理结果幂等消费");

    return new WebhookDedupClaimResult(
        firstReceiptId, firstReceiptId, ReceiptStatus.PROCESSING.name(), now.toInstant());
  }
}
