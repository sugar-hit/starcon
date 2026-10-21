package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.3.2.1 跟单与交期的传输模型。
 *
 * <p>蓝图 06 卷 06-purchase-and-supply.md 的 6.3.2.1 原文：「记录与供应商的沟通跟进（催单、确认、
 * 异常反馈），维护承诺到货日变更历史，统计平均延迟天数。」输入「跟单记录；新的承诺日期与原因。」
 * 输出「跟单时间线；交期变更历史；延迟统计。」
 *
 * <p><b>本文件最重要的纪律：三个口径一个都不许含糊。</b>
 *
 * <ul>
 *   <li><b>「当前承诺到货日」有两个口径，页面必须同时看到</b>：
 *       {@code documentPromiseDate} = 单据上的正式承诺（{@code purchase_order.promised_arrival_date}，
 *       归 6.3.1.1 所有，本模块**只读**）；{@code followupPromiseDate} = 跟单口径的当前承诺
 *       （本模块交期变更历史里最后一条的 {@code to_promised_date}）。
 *       两者不一致时 {@code promiseDiverged} = true —— 页面要显示「单据承诺日 / 跟单承诺日」，
 *       <b>绝不</b>只显示一个让人以为两边一致。</li>
 *   <li><b>延迟天数是整数天，可以是负数</b>（提前到货）。平均延迟天数用精确十进制
 *       （{@code BigDecimal}，两位小数，DB 侧 {@code numeric}），
 *       <b>不是浮点</b>；逐单延迟天数是 {@code int}。</li>
 *   <li><b>未到货的单子不算「延迟天数」</b>（延迟天数必须有终点），
 *       但另算「已逾期天数」。两个数分开给，<b>不混成一个</b>。</li>
 *   <li><b>「回写了没有」是可查的事实</b>：{@code writeback} 逐条给
 *       {@code available} / {@code status} / {@code ownerModule} / {@code impact}。
 *       下游未交付或写入口语义对不上时，如实标 {@code available=false}，
 *       <b>绝不假装已经回写</b>。</li>
 * </ul>
 *
 * <p><b>命名。</b>全仓契约被 codegen 合并进<b>一个扁平文件</b>、同名 key 后者覆盖前者且不报冲突，
 * 所以本文件的每一个类型都带 {@code PurchaseFollowup} 前缀（逐个 grep 比对，零重叠）。
 */
public final class PurchaseFollowupModels {

  private PurchaseFollowupModels() {}

  // ================================================================ 枚举

  /** 跟单类型。 */
  public enum PurchaseFollowupType {
    URGING("催单"),
    CONFIRM("确认"),
    EXCEPTION("异常反馈"),
    OTHER("其他");

    private final String label;

    PurchaseFollowupType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 沟通渠道。 */
  public enum PurchaseFollowupChannel {
    PHONE("电话"),
    EMAIL("邮件"),
    IM("即时消息"),
    WECHAT("微信"),
    PLATFORM("平台消息"),
    OTHER("其他");

    private final String label;

    PurchaseFollowupChannel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明里的一条。
   *
   * @param available 本模块**真的**做到了吗
   * @param ownerModule 这件事的归属模块（{@code available=false} 时说明它归谁）
   * @param impact 没做到的影响是什么
   */
  public record PurchaseFollowupCapability(
      String name,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact) {}

  /** 能力声明：哪些维度真有依据、哪些下游接上了没有。 */
  public record PurchaseFollowupCapabilities(
      List<String> dimensions,
      List<PurchaseFollowupCapability> capabilities,
      List<String> notes) {}

  // ================================================================ 跟单记录

  /** 登记一条跟单记录的入参。 */
  public record PurchaseFollowupRecordInput(
      @NotBlank @Size(max = 64) String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      @Schema(nullable = true) UUID supplierRefId,
      @NotNull PurchaseFollowupType followupType,
      @NotNull PurchaseFollowupChannel channel,
      @NotBlank @Size(max = 2000) String content,
      @Schema(nullable = true) @Size(max = 2000) String supplierReply,
      @NotNull OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime nextFollowupAt) {}

  /** 一条跟单记录。 */
  public record PurchaseFollowupRecordRow(
      UUID id,
      String followupNo,
      String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      PurchaseFollowupType followupType,
      String followupTypeLabel,
      PurchaseFollowupChannel channel,
      String channelLabel,
      String content,
      String supplierReply,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime nextFollowupAt,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 跟单记录分页（真分页）。 */
  public record PurchaseFollowupRecordPage(
      List<PurchaseFollowupRecordRow> rows, long total, int page, int size) {}

  /** 跟单记录查询条件。全部可空，null = 不限。 */
  public record PurchaseFollowupRecordQuery(
      @Schema(nullable = true) String purchaseOrderNo,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) PurchaseFollowupType followupType,
      @Schema(nullable = true) PurchaseFollowupChannel channel,
      @Schema(nullable = true) OffsetDateTime occurredFrom,
      @Schema(nullable = true) OffsetDateTime occurredTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) UUID actorDepartmentId,
      @Schema(nullable = true) String dataScope) {}

  // ================================================================ 交期变更

  /** 登记一次交期变更的入参。 */
  public record PurchaseFollowupChangeInput(
      @NotBlank @Size(max = 64) String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      @Schema(nullable = true) UUID supplierRefId,
      @NotNull LocalDate fromPromisedDate,
      @NotNull LocalDate toPromisedDate,
      @NotBlank @Size(max = 1000) String reason,
      @Schema(nullable = true) OffsetDateTime changedAt) {}

  /** 一次交期变更。 */
  public record PurchaseFollowupChangeRow(
      UUID id,
      String changeNo,
      String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      LocalDate fromPromisedDate,
      LocalDate toPromisedDate,
      String reason,
      UUID changedBy,
      OffsetDateTime changedAt,
      /** 该次变更之后，这个采购单的**跟单承诺日**（= 变更后的日期）。 */
      LocalDate followupPromiseDate,
      /** 单据上的正式承诺（6.3.1.1 所有，本模块只读）。 */
      @Schema(nullable = true) LocalDate documentPromiseDate,
      /** 两个口径不一致 = true。 */
      boolean promiseDiverged,
      List<PurchaseFollowupWritebackRow> writeback) {}

  /** 交期变更历史分页（真分页）。 */
  public record PurchaseFollowupChangePage(
      List<PurchaseFollowupChangeRow> rows, long total, int page, int size) {}

  /** 交期变更查询条件。 */
  public record PurchaseFollowupChangeQuery(
      @Schema(nullable = true) String purchaseOrderNo,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) LocalDate changedFrom,
      @Schema(nullable = true) LocalDate changedTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) UUID actorDepartmentId,
      @Schema(nullable = true) String dataScope) {}

  // ================================================================ 回写留痕

  /**
   * 一次回写的真实结果。
   *
   * @param available 本模块**真的**把交期事实写进去了吗
   * @param deliveryInputApplied 交期事实**是否**真的作为参数进了那次重算
   * @param status SUCCEEDED / FAILED / SKIPPED
   * @param ownerModule 那张表的归属模块（写入所有权仍归它，本模块只是触发）
   */
  public record PurchaseFollowupWritebackRow(
      UUID id,
      String targetModule,
      String targetEndpoint,
      String ownerModule,
      boolean available,
      boolean deliveryInputApplied,
      String status,
      String resultRef,
      String resultSummary,
      String errorMessage,
      String impact,
      OffsetDateTime attemptedAt) {}

  // ================================================================ 时间线

  /** 时间线上的一条（跟单记录与交期变更合并）。 */
  public record PurchaseFollowupTimelineEntry(
      String kind,
      String kindLabel,
      UUID id,
      String referenceNo,
      String purchaseOrderNo,
      String supplierCode,
      String supplierName,
      String title,
      String detail,
      @Schema(nullable = true) LocalDate fromPromisedDate,
      @Schema(nullable = true) LocalDate toPromisedDate,
      OffsetDateTime occurredAt,
      UUID actorId) {}

  /** 跟单时间线分页（真分页）。 */
  public record PurchaseFollowupTimelinePage(
      List<PurchaseFollowupTimelineEntry> rows, long total, int page, int size) {}

  /** 时间线查询条件。 */
  public record PurchaseFollowupTimelineQuery(
      @Schema(nullable = true) String purchaseOrderNo,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) OffsetDateTime occurredFrom,
      @Schema(nullable = true) OffsetDateTime occurredTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) UUID actorDepartmentId,
      @Schema(nullable = true) String dataScope) {}

  // ================================================================ 延迟统计

  /**
   * 一张采购单的交付实况。
   *
   * @param documentPromiseDate 单据上的正式承诺（6.3.1.1 所有）
   * @param followupPromiseDate 跟单口径的当前承诺（最后一次变更后）
   * @param promiseDiverged 两个口径不一致
   * @param arrivedAt 全部到货日（未全部到货时为 null）
   * @param delayDays 延迟天数（整数天，可为负 = 提前到货；未全部到货时为 null）
   * @param overdueDays 已逾期天数（仅未全部到货且已逾期时有值）
   */
  public record PurchaseFollowupDeliveryRow(
      String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) LocalDate documentPromiseDate,
      @Schema(nullable = true) LocalDate followupPromiseDate,
      boolean promiseDiverged,
      @Schema(nullable = true) String arrivalStatus,
      @Schema(nullable = true) LocalDate arrivedAt,
      @Schema(nullable = true) Integer delayDays,
      @Schema(nullable = true) Integer overdueDays,
      int changeCount,
      int followupCount,
      @Schema(nullable = true) OffsetDateTime lastFollowupAt) {}

  /** 延迟统计分页（逐单明细，真分页）。 */
  public record PurchaseFollowupDeliveryPage(
      List<PurchaseFollowupDeliveryRow> rows, long total, int page, int size) {}

  /**
   * 延迟统计汇总。
   *
   * @param arrivedOrderCount 已全部到货、进入延迟统计样本的单数
   * @param averageDelayDays 平均延迟天数（已到货样本；可为负 = 整体提前）
   * @param onTimeCount 准时（延迟天数 ≤ 0）的单数
   * @param onTimeRate 准时率（百分比，两位小数）
   * @param pendingOrderCount 尚未全部到货的单数
   * @param overduePendingCount 其中已逾期的单数
   * @param averageOverdueDays 未到货样本的平均已逾期天数
   * @param changedOrderCount 有过交期变更的采购单数
   * @param changeCount 交期变更总条数
   * @param effectiveLeadTimeDays 有效备货周期（回写 6.2.1.1 用；无数据时为 null）
   */
  public record PurchaseFollowupDeliveryStats(
      int arrivedOrderCount,
      @Schema(nullable = true) BigDecimal averageDelayDays,
      int onTimeCount,
      @Schema(nullable = true) BigDecimal onTimeRate,
      int pendingOrderCount,
      int overduePendingCount,
      @Schema(nullable = true) BigDecimal averageOverdueDays,
      int changedOrderCount,
      int changeCount,
      @Schema(nullable = true) Integer effectiveLeadTimeDays,
      String promiseBasis,
      String delayBasis,
      PurchaseFollowupRatingWriteback ratingWriteback) {}

  /** 6.1.1.3 供应商评级的回写状态（如实标 available=false，不冒充）。 */
  public record PurchaseFollowupRatingWriteback(
      boolean available, String ownerModule, String impact, String note) {}
}
