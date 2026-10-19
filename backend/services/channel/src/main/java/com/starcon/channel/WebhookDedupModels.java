package com.starcon.channel;

import com.starcon.channel.WebhookModels.WebhookReceiptView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 02.6.1.2 去重视图的线上契约。
 *
 * <p>对应 docs/product/capability-blueprint/02-channel-and-shop.md §2.6.1.2 去重视图。
 *
 * <p>蓝图里这个模块只有四行，但成立与否全在一句话上：<b>重复事件不产生第二条业务记录</b>。
 * 所以契约的形状刻意不是「列一堆 event_id」，而是「<b>一个业务事件一行</b> + 引用首次处理
 * 结果」——只给 event_id 的话，运营看到的是重复了却不知道到底处理了没有，那等于没去重。
 *
 * <p>命名说明：蓝图把去重锚点写作 {@code event_id}，而 §2.6.1.1 落地到库里的列是
 * {@code external_event_id}。契约层一律用后者的语义（{@code externalEventId}），
 * 不为了对齐蓝图的字面写法而在契约里造一个不存在的列名。
 */
public final class WebhookDedupModels {
  private WebhookDedupModels() {}

  /**
   * 02.6.1.2 去重视图的一行 = 一个业务事件。
   *
   * <p>去重锚点是 {@code (endpointId, externalEventId)}：首次接收权由 §2.6.1.1 的
   * {@code webhook_receipt_first_delivery} 部分唯一索引按这个组合裁决，
   * 视图的归并口径与它完全一致。只按 {@code externalEventId} 归并会把两个入口各自
   * 独立的事件错并成一条——那才是货真价实的"重复业务记录"。
   *
   * <p>{@code first*} 前缀的一组字段就是蓝图说的「首次处理结果引用」：
   * 不给一个光秃秃的 id，让处理结论（状态、开始/完成时间、失败原因）跟着一起走。
   */
  public record WebhookDedupEventView(
      /** 首次接收记录 id，同时是本组的稳定标识（重复行都指向它）。 */
      UUID firstReceiptId,
      UUID endpointId,
      UUID connectionId,
      String authorizedAccount,
      String platformCode,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopName,
      String externalEventId,
      String eventType,
      String topic,
      /** 首次记录的验签结论；它是这一整组能不能进入业务处理的唯一裁决。 */
      String signatureStatus,
      /** 平台投递总次数（首次 + 全部重推）。蓝图点名的「重推次数」由 duplicateCount 给出。 */
      long deliveryCount,
      /** 被平台重推的次数。0 表示这条事件一次就投递成功了。 */
      long duplicateCount,
      int lastDeliveryAttempt,
      Instant firstReceivedAt,
      Instant lastReceivedAt,
      /** 首次处理的当前状态：RECEIVED / PROCESSING / PROCESSED / FAILED / SIGNATURE_FAILED。 */
      String firstStatus,
      String payloadDigest,
      @Schema(nullable = true) Instant firstProcessingStartedAt,
      @Schema(nullable = true) Instant firstProcessedAt,
      String firstLastError,
      /**
       * 是否还能被认领：只有首次记录处于 RECEIVED 才为真。
       *
       * <p>它存在是因为"这个事件还没处理"是最常被问的问题，而调用方去推状态
       * 字符串会让页面与后端各写一份判断、改一个漏一个。
       */
      boolean processable) {}

  /**
   * 一次去重命中 = 一次被判定为重复的投递。
   *
   * <p>{@code first*} 一组是首次处理结果的引用：运营问"这次重推到底处理了没有"时，
   * 不需要再回表查一次才能回答。
   */
  public record WebhookDedupHitView(
      UUID receiptId,
      UUID firstReceiptId,
      String externalEventId,
      int deliveryAttempt,
      Instant receivedAt,
      String signatureStatus,
      String status,
      String payloadDigest,
      String firstStatus,
      @Schema(nullable = true) Instant firstProcessedAt,
      String firstLastError) {}

  /**
   * 去重总览。
   *
   * <p>刻意把「重复投递总条数」与「发生过重推的事件数」分成两个量：
   * 一个事件被推 5 次会同时贡献 4 和 1，合成一个数字之后
   * "平台在疯狂重推一条事件"和"很多事件各重推了一次"就再也分不开了。
   */
  public record WebhookDedupSummary(
      long totalEvents,
      long duplicatedEvents,
      long duplicateDeliveries,
      long singleDeliveryEvents,
      /** 首次记录仍在等下游认领或处理中的事件数——去重再严，这批也不会自己动。 */
      long pendingFirstEvents,
      String worstExternalEventId,
      long worstDuplicateCount,
      @Schema(nullable = true) Instant oldestDuplicateAt) {}

  /**
   * 一个业务事件的完整视图：归并行 + 全部命中记录 + 全部投递时间线。
   *
   * <p>{@code deliveries} 含首次那一条（{@code status != 'DUPLICATE'}），
   * 与 {@code hits} 的差别就是"全部投递"与"只看重推"，两者给的是同一组事实的两种切法。
   */
  public record WebhookDedupDetail(
      WebhookDedupEventView event,
      List<WebhookDedupHitView> hits,
      List<WebhookReceiptView> deliveries) {}

  /** 下游按 event_id 去重消费时的认领入参。店铺可后补，不传则沿用首次记录上的值。 */
  public record WebhookDedupClaimInput(@Schema(nullable = true) UUID shopId) {}

  /**
   * 认领结果。
   *
   * <p>{@code receiptId} 恒等于 {@code firstReceiptId}：按业务事件去重认领时，
   * 下游能拿到的**只有**首次那一条，重复投递没有任何别的出口。
   */
  public record WebhookDedupClaimResult(
      UUID firstReceiptId, UUID receiptId, String status, Instant processingStartedAt) {}
}
