package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.7.1.3 弃置与销毁的请求 / 响应形状。
 *
 * <p>🔴 本模块最容易做假的三处，这一层就把口径写死：
 *
 * <ol>
 *   <li><b>销毁方式是枚举事实</b>：{@link DisposalMethod} 只有压碎 / 焚烧 / 专业销毁三个值，
 *       <b>不是自由文本</b>；而且方式**决定凭证要求**（专业销毁要重量、焚烧要处置证明编号），
 *       页面与服务端都不许自造方式。</li>
 *   <li><b>凭证不是备注栏</b>：{@link RecordDisposalEvidenceRequest} 一次只登记**一类**凭证
 *       （照片 / 重量 / 处置证明编号 / 执行方签核），每类有**专属必填字段**；
 *       凭证不齐时销毁单**进不了完成态**（服务端 422 + DB 触发器各拒一次）。</li>
 *   <li><b>币种口径不混</b>：销毁费用与索赔金额都走销毁单那**一个**费用币种，
 *       本模块<b>不做任何汇率换算</b>，两者<b>永远不相加</b>。</li>
 * </ol>
 */
public final class ReturnDisposalModels {
  private ReturnDisposalModels() {}

  // ================================================================ 枚举

  /** 销毁单状态机。 */
  public enum DisposalStatus {
    DRAFT("草稿（待排期）"),
    SCHEDULED("已排期（凭证要求已冻结）"),
    DISPOSED("已销毁（完成态，凭证必须齐备）"),
    CANCELLED("已取消");

    private final String label;

    DisposalStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 🔴 销毁方式（**枚举事实**，不是自由文本）。
   *
   * <p>蓝图输入点名的三种：压碎 / 焚烧 / 专业销毁。方式同时**决定凭证要求**：
   * 专业销毁要重量记录、焚烧要处置证明编号。
   */
  public enum DisposalMethod {
    SHRED("压碎"),
    INCINERATE("焚烧"),
    PROFESSIONAL("专业销毁");

    private final String label;

    DisposalMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 凭证种类（🔴 一类凭证一张，不给「两个互相矛盾的事实」留位置）。 */
  public enum EvidenceKind {
    PHOTO("销毁照片"),
    WEIGHT("重量记录"),
    CERTIFICATE("处置证明编号"),
    EXECUTOR("执行方签核");

    private final String label;

    EvidenceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 销毁费用类型。 */
  public enum DisposalCostType {
    DESTROY_FEE("销毁费"),
    PICKUP_FEE("上门取件费"),
    TRANSPORT_FEE("运输费"),
    LABEL_FEE("标签 / 处理费"),
    OTHER("其他费用");

    private final String label;

    DisposalCostType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 索赔依据类型。 */
  public enum ClaimType {
    BRAND("品牌方索赔"),
    SUPPLIER("供应商索赔");

    private final String label;

    ClaimType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 索赔依据状态。 */
  public enum ClaimBasisStatus {
    PENDING("待提交下游"),
    SUBMITTED("已提交下游"),
    REJECTED("下游驳回");

    private final String label;

    ClaimBasisStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 凭证要求的主体类型。 */
  public enum PolicySubjectType {
    GENERAL("租户默认"),
    BRAND("品牌方要求"),
    PLATFORM("平台政策");

    private final String label;

    PolicySubjectType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 重量单位。 */
  public enum WeightUnit {
    KG("千克"),
    G("克"),
    LB("磅");

    private final String label;

    WeightUnit(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 留痕事件类型。 */
  public enum DisposalEventType {
    ORDER_CREATED("新建销毁单"),
    ORDER_SCHEDULED("排期"),
    EVIDENCE_RECORDED("登记凭证"),
    ORDER_DISPOSED("执行销毁"),
    COST_RECORDED("记费用"),
    CLAIM_BASIS_CREATED("生成索赔依据"),
    CLAIM_SUBMITTED("提交下游索赔"),
    CLAIM_REJECTED("下游驳回"),
    ORDER_CANCELLED("取消销毁单"),
    POLICY_CREATED("登记凭证要求"),
    POLICY_REVOKED("撤销凭证要求");

    private final String label;

    DisposalEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  // ================================================================ 能力声明

  /** 能力维度（🔴 缺口必须写清 ownerModule 与 impact，不许装成已交付）。 */
  public record DisposalCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record ReturnDisposalCapabilities(
      List<DisposalCapabilityDimension> dimensions,
      List<String> statuses,
      List<String> methods,
      List<String> evidenceKinds,
      List<String> costTypes,
      List<String> claimTypes,
      List<String> weightUnits,
      String methodNote,
      String evidenceNote,
      String currencyNote,
      String financeNote,
      String claimNote,
      String upstreamNote,
      String boundaryNote) {}

  // ================================================================ 概览

  /** 按币种分行的合计（🔴 跨币种不许相加）。 */
  public record DisposalCurrencyTotal(String currency, BigDecimal amount, long itemCount) {}

  /** 全量概览。 */
  public record ReturnDisposalOverview(
      long orderCount,
      long draftCount,
      long scheduledCount,
      long disposedCount,
      long cancelledCount,
      long candidateCount,
      BigDecimal candidateQty,
      long evidenceCount,
      long disposedOrderCount,
      BigDecimal disposedQty,
      BigDecimal disposedWeightKg,
      long claimBasisCount,
      List<DisposalCurrencyTotal> costTotals,
      OffsetDateTime evaluatedAt,
      String note,
      String currencyNote) {}

  // ================================================================ 待销毁清单

  /**
   * 一条待销毁候选（来自 10.7.1.1 的「弃置销毁」处置单）。
   *
   * <p>🔴 只有 {@code dispositionMethod = 'DISCARD'} 的处置单会出现在这里；
   * 转销售 / 退运回国的处置单**根本不会**出现在候选清单里。
   */
  public record DisposalCandidateRow(
      UUID dispositionOrderId,
      String dispositionNo,
      UUID decisionId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      BigDecimal disposedQty,
      String dispositionMethod,
      String dispositionMethodLabel,
      String issuedAt,
      String note) {}

  /** 待销毁清单页。 */
  public record DisposalCandidatePage(
      List<DisposalCandidateRow> rows,
      int page,
      int size,
      long total,
      BigDecimal totalQty,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 凭证要求

  /** 凭证要求（品牌方 / 平台 / 租户默认）行。 */
  public record ReturnDisposalPolicyRow(
      UUID id,
      String subjectType,
      String subjectTypeLabel,
      String subjectRef,
      boolean requiredPhoto,
      boolean requiredWeight,
      boolean requiredCertificate,
      boolean requiredExecutor,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      String status,
      String statusLabel,
      String note,
      OffsetDateTime createdAt) {}

  /** 凭证要求页。 */
  public record ReturnDisposalPolicyPage(
      List<ReturnDisposalPolicyRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 登记一条凭证要求。 */
  public record CreateDisposalPolicyRequest(
      String subjectType,
      String subjectRef,
      boolean requiredPhoto,
      boolean requiredWeight,
      boolean requiredCertificate,
      boolean requiredExecutor,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note) {}

  /** 撤销一条凭证要求。 */
  public record RevokeDisposalPolicyRequest(String reason) {}

  // ================================================================ 销毁单

  /** 建一张销毁单。 */
  public record CreateDisposalOrderRequest(
      UUID dispositionOrderId,
      String disposalMethod,
      BigDecimal disposalQty,
      @Schema(nullable = true) String weightUnit,
      String costCurrency,
      @Schema(nullable = true) String note) {}

  /** 排期（把要求冻结下来的那一刻）。 */
  public record ScheduleDisposalRequest(
      @Schema(nullable = true) OffsetDateTime plannedAt, @Schema(nullable = true) String note) {}

  /**
   * 执行销毁（完成态）。
   *
   * <p>🔴 凭证不齐时服务端直接拒（422）——「销毁完了但没有凭证」是品牌方追责的起点。
   */
  public record DisposeDisposalRequest(
      @Schema(nullable = true) OffsetDateTime disposedAt,
      @Schema(nullable = true) UUID executedBy,
      String executorName,
      @Schema(nullable = true) BigDecimal weightKg,
      @Schema(nullable = true) String note) {}

  /** 取消销毁单。 */
  public record CancelDisposalRequest(String reason) {}

  /** 销毁单列表行。 */
  public record ReturnDisposalRow(
      UUID id,
      String disposalNo,
      String status,
      String statusLabel,
      String disposalMethod,
      String disposalMethodLabel,
      String sourceReference,
      UUID dispositionOrderId,
      UUID decisionId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      BigDecimal disposalQty,
      BigDecimal weightKg,
      String weightUnit,
      boolean requirePhoto,
      boolean requireWeight,
      boolean requireCertificate,
      boolean requireExecutor,
      String costCurrency,
      BigDecimal totalCost,
      long evidenceCount,
      OffsetDateTime plannedAt,
      OffsetDateTime disposedAt,
      String executorName,
      OffsetDateTime createdAt,
      String note,
      long version) {}

  /** 销毁单列表页。 */
  public record ReturnDisposalPage(
      List<ReturnDisposalRow> rows,
      int page,
      int size,
      long total,
      BigDecimal totalCost,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 凭证

  /** 销毁凭证行。 */
  public record ReturnDisposalEvidenceRow(
      UUID id,
      UUID disposalOrderId,
      String evidenceKind,
      String evidenceKindLabel,
      String photoUrl,
      BigDecimal weightKg,
      String certificateNo,
      String executorName,
      OffsetDateTime occurredAt,
      UUID recordedBy,
      String note,
      OffsetDateTime createdAt) {}

  /** 登记一类凭证。 */
  public record RecordDisposalEvidenceRequest(
      String evidenceKind,
      @Schema(nullable = true) String photoUrl,
      @Schema(nullable = true) BigDecimal weightKg,
      @Schema(nullable = true) String certificateNo,
      @Schema(nullable = true) String executorName,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) String note) {}

  // ================================================================ 费用

  /** 销毁费用行。 */
  public record ReturnDisposalCostRow(
      UUID id,
      UUID disposalOrderId,
      String costType,
      String costTypeLabel,
      BigDecimal amount,
      String currency,
      String vendorRef,
      OffsetDateTime incurredAt,
      String note) {}

  /** 记一笔销毁费用。 */
  public record RecordDisposalCostRequest(
      String costType,
      BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String vendorRef,
      @Schema(nullable = true) OffsetDateTime incurredAt,
      @Schema(nullable = true) String note) {}

  // ================================================================ 索赔依据

  /** 索赔依据行。 */
  public record ReturnDisposalClaimBasisRow(
      UUID id,
      UUID disposalOrderId,
      String claimType,
      String claimTypeLabel,
      String counterpartyCode,
      BigDecimal claimQty,
      BigDecimal claimWeightKg,
      BigDecimal claimedAmount,
      String claimedCurrency,
      boolean evidenceComplete,
      String sourceReference,
      String claimRef,
      String status,
      String statusLabel,
      OffsetDateTime submittedAt,
      String rejectReason,
      String note,
      OffsetDateTime createdAt) {}

  /** 生成索赔依据。 */
  public record CreateDisposalClaimBasisRequest(
      String claimType,
      String counterpartyCode,
      @Schema(nullable = true) BigDecimal claimedAmount,
      @Schema(nullable = true) String claimedCurrency,
      @Schema(nullable = true) String note) {}

  /** 提交下游索赔（回填下游索赔单号）。 */
  public record SubmitDisposalClaimBasisRequest(String claimRef, @Schema(nullable = true) String note) {}

  /** 下游驳回。 */
  public record RejectDisposalClaimBasisRequest(String reason) {}

  // ================================================================ 留痕

  /** 留痕行。 */
  public record ReturnDisposalEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      UUID orderId,
      UUID evidenceId,
      UUID costId,
      UUID claimBasisId,
      UUID policyId,
      UUID actorId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 留痕页。 */
  public record ReturnDisposalEventPage(
      List<ReturnDisposalEventRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 详情

  /** 销毁单详情（销毁单 + 凭证 + 费用 + 索赔依据 + 留痕）。 */
  public record ReturnDisposalDetail(
      ReturnDisposalRow order,
      List<ReturnDisposalEvidenceRow> evidence,
      List<ReturnDisposalCostRow> costs,
      List<ReturnDisposalClaimBasisRow> claimBases,
      List<ReturnDisposalEventRow> events) {}
}
