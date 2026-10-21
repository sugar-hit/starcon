package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.6.1.2 打样与样品管理的传输模型。
 *
 * <p>蓝图原话：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
 * 样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」输入「打样申请；供应商/工厂；样品信息与图片」。
 * 输出「打样单 + 样品台账 + 确认记录；样品成本」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code DevelopmentSample} 开头。
 *
 * <p><b>八条建模纪律</b>：
 *
 * <ul>
 *   <li><b>「来源」与「用途」是两个维度，缺一不可</b>：{@code sourceType} 说「这个样品从哪来」
 *       （供应商 / 自有产 / 档口），{@code usageType} 说「它是干什么的」（开发打样）。
 *       🔴 <b>达人寄样（14.5.1.2）是营销样品</b>，属 14 卷，**不在本模块**。
 *       它同样来自供应商 —— 光看来源分不出来，所以靠 {@code usageType} 分。
 *       {@link DevelopmentSampleUsageType} 的词表里**没有「其它」桶**，
 *       留一个「其它」就等于把营销寄样塞进来，而那正是蓝图担心的
 *       「营销费用污染开发成本 ⇒ 新品利润被高估」。</li>
 *   <li><b>金额全程 BigDecimal</b>，DB 侧 numeric(19,4)；数量 numeric(19,6)；**禁浮点**。</li>
 *   <li><b>一张打样单一个币种</b>：不同币种的数绝不相加（DB 层有触发器钉死）。</li>
 *   <li><b>时间一律 timestamptz</b>：用 {@link OffsetDateTime} 传输，不传本地时刻。</li>
 *   <li><b>状态是字符串 + 中文名成对返回</b>（{@code status} / {@code statusLabel}），
 *       前端不必自己再写一遍映射 —— 各写一遍迟早漂移。</li>
 *   <li><b>「答不出来」与「确实是 0」不折叠</b>：成本是否已入账用
 *       {@code expensePosted} + {@code expenseOwnerModule} + {@code expenseNote} 三件一起说明。</li>
 *   <li><b>确认结果三值</b>：通过 / 修改 / 放弃，<b>没有「其它」</b>；
 *       「修改」必须带修改意见（{@code issueNote}），只说「要改」不说改什么，
 *       下一轮打样的人还是得猜。</li>
 *   <li><b>物流轨迹取不到就说取不到</b>：{@code trackingAvailable=false} +
 *       {@code trackingOwnerModule='10 物流'}，本模块只记**物流快照**，不假装已接通物流平台。</li>
 * </ul>
 */
public final class DevelopmentSampleModels {

  private DevelopmentSampleModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 样品<b>来源</b>（受控三值）。
   *
   * <p>🔴 **只说「从哪来」，不说「干什么用」** —— 后者是 {@link DevelopmentSampleUsageType}。
   * 达人寄样同样来自供应商，**光看来源分不出来**，混进来的代价是营销费用污染开发成本。
   */
  public enum DevelopmentSampleSourceType implements Labelled {
    SUPPLIER("供应商打样"),
    OWN_FACTORY("自有产打样"),
    STALL("档口打样");

    private final String label;

    DevelopmentSampleSourceType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 样品<b>用途</b>（受控词表，**只有一个值**）。
   *
   * <p>🔴 词表里**没有「达人寄样」，也没有「其它」**：达人寄样属 14.5.1.2（14 卷），
   * 是**营销样品**，不是本模块的范围。数据库 CHECK 会在**存储层**拒绝任何非
   * {@code DEVELOPMENT} 的用途 —— 「不要留一个『其它』把达人寄样塞进来」。
   */
  public enum DevelopmentSampleUsageType implements Labelled {
    DEVELOPMENT("开发打样");

    private final String label;

    DevelopmentSampleUsageType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 打样单状态 —— 一次打样活动的生命周期。
   *
   * <p>🔴 状态机在服务层（{@code DevelopmentSampleRules.allowedOrderTargets}）与
   * V261 迁移的守卫触发器（数据库层）**两处**都钉死。
   * {@link #CANCELLED} / {@link #CLOSED} 是**终态**：没有出边，不提供「恢复」。
   */
  public enum DevelopmentSampleOrderStatus implements Labelled {
    REQUESTED("已申请"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    DevelopmentSampleOrderStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 样品状态 —— 一个样品一件的生命周期。
   *
   * <p>🔴 {@link #CONFIRMED} / {@link #ABANDONED} 是**终态**：没有出边。
   * 「修改」的结果是 {@link #REVISING}（可再编辑、可重新打样），**不是**终态。
   */
  public enum DevelopmentSampleStatus implements Labelled {
    REQUESTED("已申请"),
    MAKING("打样中"),
    SHIPPED("已发出"),
    RECEIVED("已到样"),
    CONFIRMED("已确认"),
    REVISING("修改中"),
    ABANDONED("已放弃");

    private final String label;

    DevelopmentSampleStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 确认结果 —— 蓝图职责句点名的三个结果：**通过 / 修改 / 放弃**。
   *
   * <p>🔴 没有「其它」：确认结果只有这三个，数据库 CHECK 同样只放这三个。
   */
  public enum DevelopmentSampleConfirmationResult implements Labelled {
    PASSED("通过"),
    MODIFY("修改"),
    ABANDONED("放弃");

    private final String label;

    DevelopmentSampleConfirmationResult(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 成本类型（封闭五值，**无「其它」**）。
   *
   * <p>🔴 「其它」桶会把说不清的钱混进来 —— 费用归集最怕的就是「其它」，
   * 它让「这个钱花在哪」永远答不出来。
   */
  public enum DevelopmentSampleCostType implements Labelled {
    SAMPLE_FEE("打样费"),
    MATERIAL("物料费"),
    TOOLING("模具费"),
    FREIGHT("物流费"),
    INSPECTION("检品费");

    private final String label;

    DevelopmentSampleCostType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 物流状态（**人工登记的快照口径**，不是平台轨迹状态）。 */
  public enum DevelopmentSampleLogisticsStatus implements Labelled {
    SHIPPED("已发出"),
    IN_TRANSIT("运输中"),
    DELIVERED("已签收"),
    EXCEPTION("异常"),
    RETURNED("已退回");

    private final String label;

    DevelopmentSampleLogisticsStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：开发立项（6.6.1.1）与档口（6.1.2.1）**同库已交付** ⇒ 可用；
   * 供应商与媒体库在 catalog **已交付** ⇒ 可用；而**进费用归集（12 财务）**、
   * **试产衔接（6.6.2.1）**、**真实物流轨迹（10 物流）**三件事**做不到** ——
   * 哪一个接通了必须由接口自己报出。
   */
  public record DevelopmentSampleCapabilities(
      boolean projectReferenceAvailable,
      String projectReferenceSource,
      String projectReferenceNote,
      boolean stallReferenceAvailable,
      String stallReferenceSource,
      String stallReferenceNote,
      boolean supplierDirectoryAvailable,
      String supplierDirectorySource,
      String supplierDirectoryNote,
      boolean mediaLibraryAvailable,
      String mediaLibrarySource,
      String mediaLibraryNote,
      boolean expenseAggregationAvailable,
      String expenseAggregationOwnerModule,
      String expenseAggregationNote,
      boolean logisticsTrackingAvailable,
      String logisticsTrackingOwnerModule,
      String logisticsTrackingNote,
      boolean pilotOrderAvailable,
      String pilotOrderOwnerModule,
      String pilotOrderNote,
      boolean marketingSampleDelivered,
      String marketingSampleOwnerModule,
      String marketingSampleNote,
      String sourceUsageNote,
      String orderStateMachineNote,
      String sampleStateMachineNote,
      String costAggregationNote,
      String boundaryNote) {}

  // ================================================================ 下拉项

  /** 建单参照项。 */
  public record DevelopmentSampleOptions(
      List<DevelopmentSampleSourceOption> sourceTypes,
      List<DevelopmentSampleUsageOption> usageTypes,
      List<DevelopmentSampleOrderStatusOption> orderStatuses,
      List<DevelopmentSampleStatusOption> sampleStatuses,
      List<DevelopmentSampleConfirmationOption> confirmationResults,
      List<DevelopmentSampleCostTypeOption> costTypes,
      List<DevelopmentSampleLogisticsOption> logisticsStatuses) {}

  /** 一个来源选项。 */
  public record DevelopmentSampleSourceOption(String value, String label) {}

  /** 一个用途选项。 */
  public record DevelopmentSampleUsageOption(String value, String label) {}

  /** 一个打样单状态选项。 */
  public record DevelopmentSampleOrderStatusOption(
      String value, String label, boolean terminal, List<String> allowedTargets) {}

  /** 一个样品状态选项。 */
  public record DevelopmentSampleStatusOption(
      String value, String label, boolean terminal, List<String> allowedTargets) {}

  /** 一个确认结果选项。 */
  public record DevelopmentSampleConfirmationOption(String value, String label) {}

  /** 一个成本类型选项。 */
  public record DevelopmentSampleCostTypeOption(String value, String label) {}

  /** 一个物流状态选项。 */
  public record DevelopmentSampleLogisticsOption(String value, String label) {}

  // ================================================================ 列表

  /** 打样单清单的一行（一次打样活动）。 */
  public record DevelopmentSampleOrderRow(
      UUID id,
      String orderNo,
      UUID developmentProjectId,
      String projectNo,
      String projectName,
      String sourceType,
      String sourceTypeLabel,
      String usageType,
      String usageTypeLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) UUID stallId,
      String stallCode,
      String stallName,
      String currency,
      UUID requesterId,
      String requesterName,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime expectedDeliveryAt,
      int sampleCount,
      int confirmedSampleCount,
      @Schema(nullable = true) BigDecimal costTotal,
      String purposeNote,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 打样单清单页（真分页）。 */
  public record DevelopmentSampleOrderPage(
      List<DevelopmentSampleOrderRow> rows, long total, int page, int size) {}

  /** 样品台账（跨打样单）清单页（真分页）。 */
  public record DevelopmentSamplePage(List<DevelopmentSampleRow> rows, long total, int page, int size) {}

  /** 按状态分组的概览。 */
  public record DevelopmentSampleSummary(
      long totalOrders,
      long totalSamples,
      @Schema(nullable = true) BigDecimal totalCost,
      String totalCostCurrency,
      List<DevelopmentSampleCostCurrencyTotal> costByCurrency,
      List<DevelopmentSampleOrderStatusCount> orderStatuses,
      List<DevelopmentSampleStatusCount> sampleStatuses,
      List<DevelopmentSampleOrderRow> recentOrders) {}

  /**
   * 按币种的样品成本合计。
   *
   * <p>🔴 **不同币种的数绝不相加**（汇率查询 5.2.2.1 未交付）⇒ 概览给的是**分组**而不是一个总数。
   */
  public record DevelopmentSampleCostCurrencyTotal(
      String currency, BigDecimal totalAmount, long orderCount) {}

  /** 一个打样单状态的计数。 */
  public record DevelopmentSampleOrderStatusCount(String status, String label, long count) {}

  /** 一个样品状态的计数。 */
  public record DevelopmentSampleStatusCount(String status, String label, long count) {}

  // ================================================================ 详情

  /** 打样单详情：打样单 + 样品台账 + 迁移历史 + 成本。 */
  public record DevelopmentSampleOrderDetail(
      DevelopmentSampleOrderRow order,
      List<DevelopmentSampleRow> samples,
      List<DevelopmentSampleOrderTransitionRow> transitions,
      DevelopmentSampleCostView cost,
      List<String> allowedTargets) {}

  /** 样品的一行（一个样品一件）。 */
  public record DevelopmentSampleRow(
      UUID id,
      UUID orderId,
      String sampleNo,
      UUID developmentProjectId,
      String projectNo,
      String projectName,
      String sourceType,
      String sourceTypeLabel,
      String usageType,
      String usageTypeLabel,
      String status,
      String statusLabel,
      String specName,
      String specValue,
      BigDecimal quantity,
      String currency,
      int revisionRound,
      int confirmationCount,      int shipmentCount,
      int mediaCount,
      @Schema(nullable = true) String latestTrackingNo,
      @Schema(nullable = true) String latestLogisticsStatus,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 样品详情：样品 + 进度事件 + 确认记录 + 物流快照 + 图片。 */
  public record DevelopmentSampleDetail(
      DevelopmentSampleRow sample,
      List<DevelopmentSampleTransitionRow> transitions,
      List<DevelopmentSampleConfirmationRow> confirmations,
      List<DevelopmentSampleShipmentRow> shipments,
      List<DevelopmentSampleMediaRow> media,
      List<String> allowedTargets) {}

  /** 打样单状态迁移事件的一行。 */
  public record DevelopmentSampleOrderTransitionRow(
      UUID id,
      UUID orderId,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      UUID actorId,
      String actorName,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime appliedAt) {}

  /** 样品制作进度事件的一行。 */
  public record DevelopmentSampleTransitionRow(
      UUID id,
      UUID sampleId,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      UUID actorId,
      String actorName,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime appliedAt) {}

  /** 确认记录的一行（通过 / 修改 / 放弃）。 */
  public record DevelopmentSampleConfirmationRow(
      UUID id,
      UUID sampleId,
      String result,
      String resultLabel,
      int confirmSeq,
      String reason,
      String issueNote,
      UUID actorId,
      String actorName,
      OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime appliedAt) {}

  /** 样品物流快照的一行。 */
  public record DevelopmentSampleShipmentRow(
      UUID id,
      UUID sampleId,
      String trackingNo,
      String carrierCode,
      String carrierName,
      String logisticsStatus,
      String logisticsStatusLabel,
      OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime expectedArrivalAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      boolean trackingAvailable,
      String trackingOwnerModule,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 样品图片的一行（catalog 媒体库逻辑引用 + 快照元数据）。 */
  public record DevelopmentSampleMediaRow(
      UUID id,
      UUID sampleId,
      UUID mediaRefId,
      String mediaName,
      String mediaType,
      String fileFormat,
      String mimeType,
      String contentSha256,
      @Schema(nullable = true) BigDecimal byteSize,
      @Schema(nullable = true) Integer widthPx,
      @Schema(nullable = true) Integer heightPx,
      String storageKey,
      @Schema(nullable = true) OffsetDateTime uploadedAt,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** catalog 媒体库的候选（跨服务只读读回来的形状）。 */
  public record DevelopmentSampleMediaCandidateRow(
      UUID id,
      String name,
      String mediaType,
      String fileFormat,
      String mimeType,
      @Schema(nullable = true) BigDecimal byteSize,
      @Schema(nullable = true) Integer widthPx,
      @Schema(nullable = true) Integer heightPx,
      String contentSha256,
      String storageKey) {}

  /** 样品成本视图：成本头（一个币种）+ 明细行。 */
  public record DevelopmentSampleCostView(
      @Schema(nullable = true) UUID costId,
      UUID orderId,
      String currency,
      BigDecimal totalAmount,
      boolean expensePosted,
      String expenseOwnerModule,
      String expenseNote,
      @Schema(nullable = true) OffsetDateTime postedAt,
      List<DevelopmentSampleCostLineRow> lines) {}

  /** 成本明细行的一行。 */
  public record DevelopmentSampleCostLineRow(
      UUID id,
      UUID costId,
      String costType,
      String costTypeLabel,
      BigDecimal amount,
      String currency,
      String description,
      OffsetDateTime incurredAt,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  // ================================================================ 输入

  /** 打样单输入（建单）。 */
  public record DevelopmentSampleOrderCreateInput(
      @NotBlank @Size(max = 64) String orderNo,
      @NotNull UUID developmentProjectId,
      @NotNull DevelopmentSampleSourceType sourceType,
      @NotNull DevelopmentSampleUsageType usageType,
      /** 供应商（sourceType=SUPPLIER 时必填）：catalog 的 SUPPLIER 角色 id。 */
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) @Size(max = 64) String supplierCode,
      @Schema(nullable = true) @Size(max = 200) String supplierName,
      /** 档口（sourceType=STALL 时必填）：6.1.2.1 的档口 id。 */
      @Schema(nullable = true) UUID stallId,
      @Schema(nullable = true) @Size(max = 64) String stallCode,
      @Schema(nullable = true) @Size(max = 200) String stallName,
      @NotBlank @Size(min = 3, max = 3) String currency,
      @NotNull UUID requesterId,
      @Size(max = 100) String requesterName,
      @NotNull OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime expectedDeliveryAt,
      @Schema(nullable = true) @Size(max = 2000) String purposeNote,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 打样单更新输入（**整体替换**语义：null 表示「清空」）。 */
  public record DevelopmentSampleOrderUpdateInput(
      @NotNull DevelopmentSampleSourceType sourceType,
      @NotNull DevelopmentSampleUsageType usageType,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) @Size(max = 64) String supplierCode,
      @Schema(nullable = true) @Size(max = 200) String supplierName,
      @Schema(nullable = true) UUID stallId,
      @Schema(nullable = true) @Size(max = 64) String stallCode,
      @Schema(nullable = true) @Size(max = 200) String stallName,
      @NotBlank @Size(min = 3, max = 3) String currency,
      @NotNull UUID requesterId,
      @Size(max = 100) String requesterName,
      @Schema(nullable = true) OffsetDateTime expectedDeliveryAt,
      @Schema(nullable = true) @Size(max = 2000) String purposeNote,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 打样单状态迁移输入。
   *
   * <p>🔴 {@code reason} **必填**：迁移事件要回答「为什么」，一句「推进一下」不是留痕。
   */
  public record DevelopmentSampleOrderTransitionInput(
      @NotNull DevelopmentSampleOrderStatus toStatus,
      @NotBlank @Size(max = 500) String reason,
      /** 迁移发生时刻。留空 = 服务端当前时刻。 */
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /** 样品登记输入。 */
  public record DevelopmentSampleCreateInput(
      @NotBlank @Size(max = 64) String sampleNo,
      @Size(max = 120) String specName,
      @Size(max = 200) String specValue,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @Size(max = 500) String remark) {}

  /** 样品更新输入（**整体替换**语义）。 */
  public record DevelopmentSampleUpdateInput(
      @Size(max = 120) String specName,
      @Size(max = 200) String specValue,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @Size(max = 500) String remark) {}

  /**
   * 样品进度迁移输入（请求 → 打样中 → 已发出 → 已到样；修改中 → 重新打样）。
   *
   * <p>🔴 **确认类状态（已确认 / 修改中 / 已放弃）不走这个端点** ——
   * 它们必须带一条**确认记录**，走 {@code POST .../confirmations}。
   */
  public record DevelopmentSampleTransitionInput(
      @NotNull DevelopmentSampleStatus toStatus,
      @NotBlank @Size(max = 500) String reason,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /**
   * 确认输入（通过 / 修改 / 放弃）。
   *
   * <p>🔴 {@code reason} **必填**；{@code result=MODIFY} 时 {@code issueNote} **必填**
   * （只说「要改」不说改什么，下一轮打样的人还是得猜）。
   */
  public record DevelopmentSampleConfirmationInput(
      @NotNull DevelopmentSampleConfirmationResult result,
      @NotBlank @Size(max = 500) String reason,
      @Schema(nullable = true) @Size(max = 2000) String issueNote,
      @Schema(nullable = true) OffsetDateTime confirmedAt) {}

  /** 物流快照输入。 */
  public record DevelopmentSampleShipmentInput(
      @NotBlank @Size(max = 128) String trackingNo,
      @Size(max = 32) String carrierCode,
      @Size(max = 100) String carrierName,
      @NotNull DevelopmentSampleLogisticsStatus logisticsStatus,
      @NotNull OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime expectedArrivalAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 样品图片输入（catalog 媒体库引用）。 */
  public record DevelopmentSampleMediaInput(
      @NotNull UUID mediaRefId,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 成本明细行输入。 */
  public record DevelopmentSampleCostLineInput(
      @NotNull DevelopmentSampleCostType costType,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
      @Size(max = 200) String description,
      @NotNull OffsetDateTime incurredAt) {}
}
