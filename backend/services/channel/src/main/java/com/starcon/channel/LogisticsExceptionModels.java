package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.3.1.2 异常件的请求 / 响应形状。
 *
 * <p>🔴 本模块最容易做假的四个地方，这一层就把它们的口径写死：
 *
 * <ol>
 *   <li><b>来源是服务端派生的事实</b>：{@link CreateExceptionCaseRequest} 里
 *       <b>没有</b> source 字段 —— 来源由「有没有异常节点 / 有没有客服反馈 / 有没有规则」
 *       在服务层判定，调用方填了也不算数。填错来源会让「这批异常是哪来的」变成
 *       一句可以随手编的话。</li>
 *   <li><b>时长是整数分钟</b>：{@link ExceptionCaseRow#stalledDurationMinutes()} 是
 *       {@code long}，{@link ExceptionCaseRow#stalledDurationHours()} 是
 *       {@link BigDecimal}（两位小数，纯展示用）。<b>没有浮点小时列</b>，
 *       更没有负时长 —— 负时长意味着锚点时刻算错了。</li>
 *   <li><b>下游待通知是显式状态</b>：{@link DownstreamTaskRow#downstreamStatus()} 从
 *       {@code PENDING_NOTIFY} 起步。11 售后 / 15 客服 / 7 库存**当前未交付**，
 *       写「已通知」「已受理」就是伪造凭据。</li>
 *   <li><b>金额与数量是 BigDecimal</b>：{@code NUMERIC(19,6)}，禁浮点；
 *       币种三字母，跨币种绝不相加。</li>
 * </ol>
 */
public final class LogisticsExceptionModels {
  private LogisticsExceptionModels() {}

  // ================================================================ 枚举

  /** 异常类型（蓝图职责句点名的六种）。 */
  public enum ExceptionType {
    TRACKING_STALLED("轨迹停滞"),
    DELIVERY_FAILED("妥投失败"),
    ADDRESS_ISSUE("地址问题"),
    CUSTOMS_HOLD("清关滞留"),
    DAMAGED_OR_LOST("破损丢件"),
    BUYER_COMPLAINT("买家投诉");

    private final String label;

    ExceptionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of(
          "TRACKING_STALLED",
          "DELIVERY_FAILED",
          "ADDRESS_ISSUE",
          "CUSTOMS_HOLD",
          "DAMAGED_OR_LOST",
          "BUYER_COMPLAINT");
    }
  }

  /** 严重度。 */
  public enum Severity {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("紧急");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }
  }

  /** 异常件状态机。 */
  public enum CaseStatus {
    OPEN("待处理"),
    INVESTIGATING("处置中"),
    ACTIONED("已出处置动作"),
    RESOLVED("已结案"),
    VOID("已作废");

    private final String label;

    CaseStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("OPEN", "INVESTIGATING", "ACTIONED", "RESOLVED", "VOID");
    }
  }

  /** 处置动作类型（蓝图「支持联系承运商、发起理赔、改址、补发」+ 责任到底所需的转派/结案）。 */
  public enum ActionType {
    CONTACT_CARRIER("联系承运商"),
    CONTACT_BUYER("联系买家"),
    CONTACT_CS("联系客服"),
    FILE_CLAIM("发起理赔"),
    REQUEST_ADDRESS_CHANGE("请求改址"),
    REQUEST_RESHIP("请求补发"),
    ESCALATE("升级"),
    ASSIGN("转派责任人"),
    RESOLVE("结案"),
    VOID("作废");

    private final String label;

    ActionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of(
          "CONTACT_CARRIER",
          "CONTACT_BUYER",
          "CONTACT_CS",
          "FILE_CLAIM",
          "REQUEST_ADDRESS_CHANGE",
          "REQUEST_RESHIP",
          "ESCALATE",
          "ASSIGN",
          "RESOLVE",
          "VOID");
    }
  }

  /** 处置记录状态。 */
  public enum DispositionStatus {
    PENDING("待办"),
    IN_PROGRESS("进行中"),
    SUBMITTED("已提交"),
    CONFIRMED("已确认"),
    FAILED("失败"),
    CANCELLED("已取消");

    private final String label;

    DispositionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of(
          "PENDING", "IN_PROGRESS", "SUBMITTED", "CONFIRMED", "FAILED", "CANCELLED");
    }
  }

  /** 理赔 / 补发单类型。 */
  public enum ClaimType {
    CARRIER_CLAIM("向承运商理赔"),
    RESHIP("补发");

    private final String label;

    ClaimType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("CARRIER_CLAIM", "RESHIP");
    }
  }

  /** 理赔 / 补发单状态机。 */
  public enum ClaimStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
    APPROVED("已受理"),
    REJECTED("已拒绝"),
    PAID("已赔付"),
    CANCELLED("已撤销");

    private final String label;

    ClaimStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PAID", "CANCELLED");
    }
  }

  /** 下游模块（蓝图下游：11 售后 / 15 客服 / 7 库存；改址 = 10.2.1.3）。 */
  public enum DownstreamModule {
    INVENTORY("7", "7 库存（补发出库）"),
    AFTER_SALE("11", "11 售后（补发 / 退款）"),
    CUSTOMER_SERVICE("15", "15 客服"),
    ADDRESS_CHANGE("10.2.1.3", "10.2.1.3 改址与取消");

    private final String code;
    private final String label;

    DownstreamModule(String code, String label) {
      this.code = code;
      this.label = label;
    }

    public String code() {
      return code;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("7", "11", "15", "10.2.1.3");
    }
  }

  /** 下游任务状态。 */
  public enum DownstreamStatus {
    PENDING_NOTIFY("待通知"),
    NOTIFIED("已通知"),
    ACKNOWLEDGED("已受理"),
    CANCELLED("已取消");

    private final String label;

    DownstreamStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("PENDING_NOTIFY", "NOTIFIED", "ACKNOWLEDGED", "CANCELLED");
    }
  }

  // ================================================================ 能力声明

  /** 一个能力维度。 */
  public record CapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record LogisticsExceptionCapabilities(
      List<CapabilityDimension> dimensions,
      List<String> exceptionTypes,
      List<String> severities,
      List<String> caseStatuses,
      List<String> actionTypes,
      List<String> dispositionStatuses,
      List<String> claimTypes,
      List<String> claimStatuses,
      List<String> downstreamModules,
      List<String> downstreamStatuses,
      String responsibilityNote,
      String durationNote,
      String moneyNote,
      String upstreamNote,
      String downstreamNote,
      String boundaryNote) {}

  // ================================================================ 概览

  /** 按类型分组的计数。 */
  public record TypeCount(String exceptionType, String exceptionTypeLabel, long count) {}

  /** 按状态分组的计数。 */
  public record StatusCount(String status, String statusLabel, long count) {}

  /** 按严重度分组的计数。 */
  public record SeverityCount(String severity, String severityLabel, long count) {}

  /** 按币种分行的金额合计（🔴 跨币种绝不相加）。 */
  public record CurrencyTotal(String currency, BigDecimal amount, long claimCount) {}

  /** 全量概览（全量口径，不受列表筛选与分页影响）。 */
  public record LogisticsExceptionOverview(
      long caseCount,
      long openCount,
      long investigatingCount,
      long actionedCount,
      long resolvedCount,
      long voidCount,
      long overdueCount,
      long pendingDownstreamCount,
      long claimCount,
      long reshipCount,
      long carrierClaimCount,
      List<CurrencyTotal> claimTotals,
      List<TypeCount> byType,
      List<StatusCount> byStatus,
      List<SeverityCount> bySeverity,
      OffsetDateTime evaluatedAt,
      String note,
      String moneyNote) {}

  // ================================================================ 异常件

  /**
   * 登记一个异常件。
   *
   * <p>🔴 请求里<b>没有</b> source：来源是服务端派生的事实
   * （有异常节点 ⇒ TRACKING_FLAG；有客服反馈 ⇒ CUSTOMER_FEEDBACK；
   * 规则命中 ⇒ RULE_SCAN；其余 ⇒ MANUAL）。
   */
  public record CreateExceptionCaseRequest(
      @Schema(nullable = true) UUID subjectId,
      @Schema(nullable = true) UUID sourceEventId,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String exceptionType,
      @Schema(nullable = true) String severity,
      @Schema(nullable = true) String customerFeedback,
      @Schema(nullable = true) String waybillNo,
      @Schema(nullable = true) String note) {}

  /** 异常件行。 */
  public record ExceptionCaseRow(
      UUID id,
      String caseNo,
      String source,
      String sourceLabel,
      String exceptionType,
      String exceptionTypeLabel,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID subjectId,
      @Schema(nullable = true) UUID sourceEventId,
      String waybillNo,
      String carrierCode,
      String salesOrderNo,
      long stalledDurationMinutes,
      BigDecimal stalledDurationHours,
      UUID ownerMembershipId,
      OffsetDateTime detectedAt,
      OffsetDateTime dueAt,
      boolean overdue,
      String customerFeedback,
      @Schema(nullable = true) OffsetDateTime closedAt,
      String closeReason,
      long dispositionCount,
      long claimCount,
      String note,
      long version) {}

  /** 异常件清单页。 */
  public record ExceptionCasePage(
      List<ExceptionCaseRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String responsibilityNote) {}

  /** 异常件详情：主行 + 处置记录 + 单据 + 下游任务。 */
  public record ExceptionCaseDetail(
      ExceptionCaseRow head,
      List<ExceptionDispositionRow> dispositions,
      List<ExceptionClaimRow> claims,
      List<DownstreamTaskRow> downstreamTasks,
      @Schema(nullable = true) ExceptionUpstreamContext upstream) {}

  /** 上游 10.3.1.1 的轨迹上下文（只读快照）。 */
  public record ExceptionUpstreamContext(
      UUID subjectId,
      String masterWaybillNo,
      String carrierCode,
      String latestNode,
      @Schema(nullable = true) OffsetDateTime latestOccurredAt,
      long eventCount,
      boolean exceptionFlag,
      String lastExceptionCode,
      @Schema(nullable = true) OffsetDateTime lastExceptionAt,
      String subjectStatus) {}

  // ================================================================ 规则

  /** 登记一条异常识别规则。 */
  public record SaveExceptionRuleRequest(
      String ruleCode,
      String ruleType,
      @Schema(nullable = true) String matchNode,
      @Schema(nullable = true) String matchExceptionCode,
      @Schema(nullable = true) Integer thresholdMinutes,
      String severity,
      Integer resolveSlaMinutes,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note) {}

  /** 规则行。 */
  public record ExceptionRuleRow(
      UUID id,
      String ruleCode,
      String ruleType,
      String ruleTypeLabel,
      String matchNode,
      String matchExceptionCode,
      @Schema(nullable = true) Integer thresholdMinutes,
      String severity,
      String severityLabel,
      int resolveSlaMinutes,
      boolean enabled,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 规则清单页。 */
  public record ExceptionRulePage(
      List<ExceptionRuleRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 规则扫描

  /** 规则扫描结果（一次扫描建了哪些件、跳过了哪些件、为什么）。 */
  public record ExceptionScanResult(
      int ruleCount,
      int candidateCount,
      int createdCount,
      int skippedCount,
      List<ExceptionScanSkip> skips,
      List<String> createdCaseNos,
      OffsetDateTime scannedAt,
      String note) {}

  /** 扫描跳过的一条（为什么没建件）。 */
  public record ExceptionScanSkip(
      String ruleCode, String waybillNo, String reason, @Schema(nullable = true) String detail) {}

  // ================================================================ 处置记录

  /** 追加一条处置记录。 */
  public record AddDispositionRequest(
      String actionType,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID targetOwnerMembershipId,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String detail,
      @Schema(nullable = true) String resultNote,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /** 处置记录行。 */
  public record ExceptionDispositionRow(
      UUID id,
      UUID caseId,
      String caseNo,
      String waybillNo,
      String actionType,
      String actionTypeLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID targetOwnerMembershipId,
      BigDecimal amount,
      String currency,
      String detail,
      String resultNote,
      UUID operatorMembershipId,
      OffsetDateTime occurredAt,
      long downstreamTaskCount) {}

  /** 处置记录清单页。 */
  public record ExceptionDispositionPage(
      List<ExceptionDispositionRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 处置记录详情（含它触发的下游任务）。 */
  public record ExceptionDispositionDetail(
      ExceptionDispositionRow head, List<DownstreamTaskRow> downstreamTasks) {}

  // ================================================================ 理赔 / 补发单

  /** 发起一张理赔 / 补发单。 */
  public record CreateExceptionClaimRequest(
      String claimType,
      @Schema(nullable = true) UUID dispositionId,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal qty,
      @Schema(nullable = true) String evidenceNote,
      @Schema(nullable = true) String note) {}

  /** 理赔 / 补发单行。 */
  public record ExceptionClaimRow(
      UUID id,
      String claimNo,
      UUID caseId,
      String caseNo,
      @Schema(nullable = true) UUID dispositionId,
      String waybillNo,
      String claimType,
      String claimTypeLabel,
      String status,
      String statusLabel,
      BigDecimal amount,
      String currency,
      BigDecimal qty,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      String decisionNote,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      long version) {}

  /** 理赔 / 补发单清单页。 */
  public record ExceptionClaimPage(
      List<ExceptionClaimRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String moneyNote) {}

  /** 理赔 / 补发单详情。 */
  public record ExceptionClaimDetail(
      ExceptionClaimRow head, List<DownstreamTaskRow> downstreamTasks) {}

  // ================================================================ 下游任务

  /** 下游待通知任务行。 */
  public record DownstreamTaskRow(
      UUID id,
      UUID caseId,
      String caseNo,
      @Schema(nullable = true) UUID dispositionId,
      @Schema(nullable = true) UUID claimId,
      String downstreamModule,
      String downstreamModuleLabel,
      String downstreamStatus,
      String downstreamStatusLabel,
      String referenceNo,
      String note,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime notifiedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt) {}
}
