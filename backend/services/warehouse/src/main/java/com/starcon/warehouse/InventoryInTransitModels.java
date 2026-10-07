package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.5.1.2 在途库存。
 *
 * <p>蓝图 07-inventory.md 7.5.1.2 的输入/输出契约：
 *
 * <ul>
 *   <li>职责：跟踪所有在途库存（调拨在途、头程在途、采购在途、FBA 发货在途），含起止仓、批次、数量、预计到达。
 *   <li>输入：调拨发出与头程发运事件；预计到达时间；收货事件。
 *   <li>输出：在途库存清单 + 到达预警（逾期未达）。
 * </ul>
 *
 * <p>DTO 一律带 {@code InTransit} 前缀：四个服务的 schema 被 codegen 拍平进同一命名空间，
 * 重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>数字口径（界面上每一列都对应其中一条，不做二次加工）：
 *
 * <ul>
 *   <li>{@code outstandingQuantity} 仍在途的余量 —— 缺口计算要扣的就是它。
 *   <li>{@code settledQuantity} 已了结 = 已到达 + 已确认短少。
 *   <li>{@code declaredQuantity} = {@code settledQuantity + outstandingQuantity}，即这一腿当初发了多少。
 *   <li>{@code overdueHours} 由 {@code expected_arrive_at} 与「此刻」相减算出来，
 *       **不是**人工标记的字段：ETA 和当前时刻都是库里的真实值，所以预警是算出来的。
 * </ul>
 *
 * <p>调拨腿（{@code transit_kind='TRANSFER'}）由 7.5.1.1 写入，{@code settledQuantity} 恒为 0：
 * 7.5.1.1 的分次收货把到达量拆成独立的 RECEIVED 兄弟行，所以「仍在途的那一行」
 * 天然就是未达余量，到达权威在调拨单的已实收列（详情接口会带出来）。
 * 这个口径差异随每行 {@code attribution} 返回，界面上必须显示，不靠人猜。
 */
public final class InventoryInTransitModels {
  private InventoryInTransitModels() {}

  /** 在途类型词表。与 {@code inventory_transfer.transit_kind} 的 CHECK 逐值对应。 */
  public enum InTransitKind {
    /** 调拨在途：由 7.5.1.1 调拨单产生，本模块只读不写。 */
    TRANSFER("调拨在途"),
    /** 头程在途：供应商/工厂 → 我方仓（10 物流与头程，尚未交付）。 */
    FIRST_MILE("头程在途"),
    /** 采购在途：采购下单 → 我方仓（6 采购，尚未交付）。 */
    PURCHASE("采购在途"),
    /** FBA 发货在途：我方仓 → 平台仓（4 外部仓，尚未交付）。 */
    FBA_OUTBOUND("FBA 发货在途");

    private final String label;

    InTransitKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static InTransitKind parse(String raw) {
      for (var k : values()) {
        if (k.name().equalsIgnoreCase(raw)) {
          return k;
        }
      }
      throw new IllegalArgumentException(
          "未知的在途类型：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 差异原因词表。SHORTAGE 事件必填其一。 */
  public enum InTransitReason {
    LOST("运输丢失"),
    DAMAGED("破损"),
    REJECTED("目的仓拒收"),
    CUSTOMS_HELD("清关扣关"),
    SHORT_SHIPPED("发运即短少");

    private final String label;

    InTransitReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static InTransitReason parse(String raw) {
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw)) {
          return r;
        }
      }
      throw new IllegalArgumentException(
          "未知的差异原因："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /**
   * 逾期判定档位。
   *
   * <p>档位是**算出来的**分桶：{@code overdueHours}（ETA 与此刻的差）落在哪一档就是哪一档，
   * 没有任何人工可写的标记列 —— 改了 ETA，档位立刻跟着变。
   */
  public enum InTransitOverdueLevel {
    /** 未到 ETA（含没有 ETA 的行）：不算逾期。 */
    ON_TIME("未到预计到达"),
    /** 逾期 0–24 小时。 */
    MILD("逾期 1 天内"),
    /** 逾期 1–3 天。 */
    OVERDUE("逾期 1–3 天"),
    /** 逾期 3–7 天。 */
    SEVERE("逾期 3–7 天"),
    /** 逾期 7 天以上。 */
    CRITICAL("逾期 7 天以上"),
    /** 没有登记预计到达：算不出逾期，如实显示而不是算成准点。 */
    NO_ETA("未登记预计到达");

    private final String label;

    InTransitOverdueLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 由「未达余量 + ETA + 此刻」算出档位。
     *
     * <p>已了结的腿（{@code outstanding=0}）永远落在 {@link #ON_TIME}：货都到了还报逾期，
     * 是把已解决的问题当成待办。
     */
    public static InTransitOverdueLevel of(BigDecimal outstanding, OffsetDateTime eta, OffsetDateTime now) {
      if (outstanding == null || outstanding.signum() <= 0) {
        return ON_TIME;
      }
      if (eta == null) {
        return NO_ETA;
      }
      long hours = java.time.Duration.between(eta, now).toHours();
      if (hours < 0) {
        return ON_TIME;
      }
      if (hours < 24) {
        return MILD;
      }
      if (hours < 72) {
        return OVERDUE;
      }
      if (hours < 168) {
        return SEVERE;
      }
      return CRITICAL;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record InTransitQuery(
      String transitKind,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      UUID skuId,
      List<String> overdueLevel,
      Boolean overdueOnly,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 在途清单行。 */
  public record InTransitRow(
      UUID id,
      String transitKind,
      String transitKindLabel,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      UUID legalEntityId,
      String legalEntityCode,
      UUID fromWarehouseId,
      String fromWarehouseCode,
      String fromWarehouseName,
      UUID toWarehouseId,
      String toWarehouseCode,
      String toWarehouseName,
      UUID lotId,
      String lotCode,
      BigDecimal outstandingQuantity,
      BigDecimal settledQuantity,
      BigDecimal declaredQuantity,
      OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      String status,
      String overdueLevel,
      String overdueLevelLabel,
      @Schema(nullable = true) Long overdueHours,
      String carrierName,
      String trackingNo,
      String sourceSystem,
      String sourceReference,
      @Schema(nullable = true) UUID transferOrderId,
      @Schema(nullable = true) String transferOrderCode,
      String attribution,
      long version) {}

  /** 清单页的全量汇总（**不受分页影响**）。 */
  public record InTransitSummary(
      long legCount,
      BigDecimal outstandingQuantity,
      BigDecimal outstandingTransfer,
      long legCountFirstMile,
      BigDecimal outstandingFirstMile,
      long legCountPurchase,
      BigDecimal outstandingPurchase,
      long legCountFbaOutbound,
      BigDecimal outstandingFbaOutbound,
      long overdueLegCount,
      BigDecimal overdueQuantity,
      long noEtaLegCount,
      @Schema(nullable = true) OffsetDateTime earliestExpectedArriveAt) {}

  /**
   * 清单页。
   *
   * <p>{@code summary} 是**全量**汇总（不受分页影响）；下拉词表随响应一起给，界面不用再拉一次。
   */
  public record InTransitPage(
      InTransitSummary summary, List<InTransitRow> rows, List<InTransitOption> kindOptions,
      List<InTransitOption> overdueOptions, List<InTransitWarehouseOption> warehouseOptions,
      long total, int page, int size) {}

  /** 类型/逾期档位下拉项（带各档在途腿数，界面直接显示「有多少条要处理」）。 */
  public record InTransitOption(String value, String label, long legCount) {}

  public record InTransitWarehouseOption(UUID id, String code, String name) {}

  /** 一笔到达或一次差异登记。 */
  public record InTransitEventRow(
      UUID id,
      String kind,
      OffsetDateTime occurredAt,
      BigDecimal quantity,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      String reason,
      String notes) {}

  /** 在途腿详情：行 + 该腿的完整到达/差异时间线。 */
  public record InTransitDetail(InTransitRow leg, List<InTransitEventRow> events, String metricNote) {}

  /** 到达预警清单。{@code generatedAt} 是这次计算的时刻，预警随时间自动变，不落任何标记列。 */
  public record InTransitAlerts(
      OffsetDateTime generatedAt,
      long totalOverdueLegs,
      BigDecimal overdueQuantity,
      List<InTransitRow> rows,
      List<InTransitOption> levelOptions) {}

  /**
   * 补货可扣在途净额（6.2.1.1 的直接输入）。
   *
   * <p>按「目的仓 × SKU」聚合的在途量 —— 缺口计算扣掉它才不会重复补货
   * （蓝图关系段：「在途漏算会导致重复补货」）。这个端点刻意**不含**任何补货算法：
   * 需求预测与下单属 6.2.1.1，本模块只负责把在途这个数按它要的形状交出去。
   */
  public record InTransitNettingRow(
      UUID toWarehouseId,
      String toWarehouseCode,
      String toWarehouseName,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal outstandingQuantity,
      BigDecimal noEtaQuantity,
      long legCount,
      @Schema(nullable = true) OffsetDateTime latestExpectedArriveAt) {}

  public record InTransitNettingPage(
      long total, BigDecimal totalOutstandingQuantity, List<InTransitNettingRow> rows, String usageNote) {}

  /**
   * 登记外部在途腿（头程发运 / 采购发运 / FBA 发货运单事件）。
   *
   * <p>{@code sourceSystem} + {@code sourceReference} 是跨服务定位键兼幂等锚点：
   * 上游重复投递同一条发运事件不会双记在途。调拨腿（{@code transitKind=TRANSFER}）
   * 不在这里登记 —— 它由 7.5.1.1 调拨单发出时产生。
   *
   * <p>刻意**没有**备注字段：登记动作是对上游发运事件的镜像，备注的权威在对方系统，
   * 这里存一份就成了第二份说法。靠 {@code sourceReference} 指回原文即可。
   *
   * <p>{@code shippedAt} 与 {@code expectedArriveAt} 的关系是硬约束：ETA 不能早于发出时刻。
   * 但 ETA **可以**早于「此刻」—— 那正是一条逾期在途（补录 10 天前发出、2 天前该到的批次），
   * 逾期预警要有数据可算，就不能把它挡在门外。
   */
  public record InTransitRegisterRequest(
      String transitKind,
      UUID skuId,
      UUID qualityStatusId,
      UUID legalEntityId,
      UUID fromWarehouseId,
      String fromWarehouseName,
      UUID toWarehouseId,
      String toWarehouseName,
      UUID lotId,
      BigDecimal quantity,
      OffsetDateTime shippedAt,
      OffsetDateTime expectedArriveAt,
      String sourceSystem,
      String sourceReference,
      String carrierName,
      String trackingNo) {}

  /** 到达请求：可只到一部分（分批到货）。 */
  public record InTransitArriveRequest(
      Long expectedVersion,
      OffsetDateTime occurredAt,
      UUID locationId,
      UUID lotId,
      String notes,
      BigDecimal quantity) {}

  /** 差异登记请求：把到达不了的部分如实记为短少，在途归零。差异原因必填。 */
  public record InTransitDiscrepancyRequest(
      Long expectedVersion, OffsetDateTime occurredAt, BigDecimal quantity, String reason, String notes) {}

  /** 写动作后的回读结果（响应是「库里真实存了什么」）。 */
  public record InTransitSaved(
      UUID id,
      String transitKind,
      String status,
      BigDecimal outstandingQuantity,
      BigDecimal settledQuantity,
      BigDecimal declaredQuantity,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      String overdueLevel,
      long version,
      String attribution) {}
}
