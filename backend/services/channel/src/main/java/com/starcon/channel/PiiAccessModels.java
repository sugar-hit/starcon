package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.6.1.1 PII 管理的传输模型。
 *
 * <p>蓝图原文：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * <b>完整地址查看需留痕</b>——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * <p>🔴 <b>本文件最重要的一条纪律：脱敏值与完整值是两种东西，模型上就分得开。</b>
 *
 * <ul>
 *   <li>{@code PiiAccessFieldValue.masked} —— 这个值是不是脱敏后的。
 *       前端**必须**按它决定要不要加「已脱敏」的标识，不能靠猜。
 *   <li>{@code PiiAccessFieldValue.present} —— 上游到底有没有给这个字段。
 *       {@code present=false} 是「<b>上游没有这个字段</b>」，**不是**「这个字段是空字符串」。
 *   <li>{@code PiiAccessSubjectDetail.known} —— 「<b>这个人 / 这张单在不在</b>」。
 *       {@code known=false} 是「查无此人」，**不是**「这个人没有 PII」。
 * </ul>
 *
 * <p>把这三种「没有」折成空串，是 PII 系统最致命的一种撒谎：界面会显示「电话：—」，
 * 而真相可能是「上游读不到」或「这个人根本没有电话」。
 */
public final class PiiAccessModels {

  private PiiAccessModels() {}

  // ================================================================ 能力声明

  /** 逐个字段类别的口径。 */
  public record PiiAccessFieldInfo(
      String fieldCode,
      String fieldLabel,
      /** 脱敏规则的人话说明（界面上要能解释清楚「为什么是长这样」）。 */
      String maskRule,
      /** 15 客户（crm）这个来源给不给这个字段。 */
      boolean availableInCrm,
      /** 9 订单（trade）这个来源给不给这个字段。 */
      boolean availableInTrade) {}

  /** 逐个上游读面的可用性。 */
  public record PiiAccessSourceState(
      String sourceCode, String sourceLabel, boolean available, String note) {}

  /** 能力声明。 */
  public record PiiAccessCapabilities(
      List<PiiAccessFieldInfo> fields,
      List<PiiAccessSourceState> sources,
      String maskingSemantics,
      String authorizationSemantics,
      String auditSemantics,
      String exportSemantics,
      String storageSemantics,
      String downstreamNote,
      String boundaryNote) {}

  // ================================================================ 策略

  /**
   * PII 访问与导出策略（一租户一条）。
   *
   * <p>🔴 <b>刻意没有「是否脱敏」「导出是否需要审批」两个字段</b>：
   * 脱敏是硬默认、审批是硬规则，做成可配置等于留后门。
   */
  public record PiiAccessPolicyView(
      UUID policyId,
      String policyName,
      int maxExportRecords,
      int exportApprovalTtlHours,
      int maxRevealPerDay,
      boolean requireReasonOnReveal,
      long version,
      /** 后端没登记过时是 false（返回体里**不预填任何默认值**）。 */
      boolean configured,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 策略登记 / 修改（upsert，一租户一条）。 */
  public record PiiAccessPolicyInput(
      @NotBlank @Size(max = 120) String policyName,
      @NotNull @Min(1) @Max(500) Integer maxExportRecords,
      @NotNull @Min(1) @Max(720) Integer exportApprovalTtlHours,
      @NotNull @Min(0) Integer maxRevealPerDay,
      @NotNull Boolean requireReasonOnReveal,
      @Schema(nullable = true) Long version) {}

  // ================================================================ 授权配置

  /** 授权配置（角色 × 字段 × 成员范围）。 */
  public record PiiAccessGrantView(
      UUID grantId,
      String roleCode,
      String fieldCode,
      String fieldLabel,
      String scopeMode,
      String scopeLabel,
      boolean reasonRequired,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      String status,
      UUID grantedBy,
      OffsetDateTime grantedAt,
      @Schema(nullable = true) UUID revokedBy,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      String revokeReason,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 授权配置登记 / 修改。 */
  public record PiiAccessGrantInput(
      @NotBlank @Size(max = 64) String roleCode,
      @NotBlank @Pattern(regexp = "NAME|PHONE|EMAIL|ADDRESS") String fieldCode,
      @NotBlank @Pattern(regexp = "ALL|DEPARTMENT|SELF") String scopeMode,
      @Schema(nullable = true) Boolean reasonRequired,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 600) String note) {}

  /** 撤销授权。**理由必填** —— 没有理由的撤销事后无法追责。 */
  public record PiiAccessGrantRevokeInput(@NotBlank @Size(max = 600) String revokeReason) {}

  /** 授权配置列表（真分页）。 */
  public record PiiAccessGrantPage(
      List<PiiAccessGrantView> items, int page, int size, long total) {}

  // ================================================================ 主体（脱敏展示）

  /**
   * 一个主体的<b>脱敏</b>视图（默认路径）。
   *
   * @param known 上游**有没有这个主体**。false ⇒ 查无此人（**不是**「这个人没有 PII」）
   * @param fields 逐个字段的脱敏值
   * @param availableFields 上游确实给了值的字段
   * @param missingFields 上游**没给**的字段（≠ 空串）
   */
  public record PiiAccessSubjectView(
      String subjectKind,
      String subjectRef,
      String subjectLabel,
      boolean known,
      List<PiiAccessSubjectField> fields,
      List<String> availableFields,
      List<String> missingFields,
      String sourceLabel,
      String note) {}

  /**
   * 一个字段的脱敏值。
   *
   * <p>🔴 刻意用<b>列表</b>而不是 {@code Map<String, String>}：契约 codegen 把
   * {@code Map} 落成无类型的 {@code {}}（既有的 AccountHealth 阈值也是这个形状），
   * 前端拿到的就只是「一个空对象」—— 按字段名取值时 TS 直接报错。
   * 列表 + 字段码能让契约类型是真的。
   */
  public record PiiAccessSubjectField(
      String fieldCode, String fieldLabel, String maskedValue) {}

  /** 主体列表（真分页 + 真搜索）+ 读面可用性。 */
  public record PiiAccessSubjectPage(
      boolean available,
      String note,
      List<PiiAccessSubjectView> items,
      int page,
      int size,
      long total,
      List<PiiAccessSourceState> sources) {}

  // ================================================================ 主体（查看）

  /**
   * 单个字段的值。
   *
   * @param masked 这个值是不是脱敏后的
   * @param present 上游**有没有给**这个字段（false ⇒ 字段缺失，不是空串）
   */
  public record PiiAccessFieldValue(
      String fieldCode, String label, String value, boolean masked, boolean present) {}

  /** 某个字段的授权判定结果（界面上要能说清「凭什么能看到」）。 */
  public record PiiAccessFieldDecision(
      String fieldCode,
      boolean allowed,
      String reason,
      String roleCode,
      @Schema(nullable = true) UUID grantId) {}

  /** 单个主体的查看结果。 */
  public record PiiAccessSubjectDetail(
      String subjectKind,
      String subjectRef,
      String subjectLabel,
      boolean known,
      List<PiiAccessFieldValue> fields,
      boolean revealed,
      String revealNote,
      List<PiiAccessFieldDecision> decisions) {}

  /**
   * 查看完整值的结果。
   *
   * <p>🔴 <b>为什么用「返回结果」而不是「抛异常」</b>：被拒时服务层**已经把那次越权尝试
   * 写进审计表**了，而审计表是 append-only 的。如果在事务里抛异常，
   * 整个事务回滚 ⇒ <b>越权尝试的留痕会一起被回滚掉</b> ——
   * 那正是最该被看见的一类记录，却恰恰记不下来。
   * 所以：**服务层返回「被拒」，由控制器在事务提交之后才转成 HTTP 403**。
   */
  public record PiiAccessRevealOutcome(
      boolean allowed, String denialReason, PiiAccessSubjectDetail detail) {}

  // ================================================================ 审计

  /** 一条访问审计。 */
  public record PiiAccessAuditRow(
      UUID auditId,
      String subjectKind,
      String subjectRef,
      String subjectLabel,
      String fieldCode,
      String action,
      String decision,
      @Schema(nullable = true) UUID grantId,
      String grantRoleCode,
      String decisionReason,
      UUID actorId,
      String actorName,
      String reason,
      @Schema(nullable = true) UUID exportRequestId,
      OffsetDateTime occurredAt) {}

  /** 审计汇总计数。 */
  public record PiiAccessAuditCounts(
      long total, long viewMasked, long viewFull, long export, long denied) {}

  /** 审计列表（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public record PiiAccessAuditPage(
      List<PiiAccessAuditRow> items,
      int page,
      int size,
      long total,
      PiiAccessAuditCounts counts) {}

  // ================================================================ 导出

  /**
   * 导出申请。
   *
   * @param recordCount 申请导出多少条
   * @param subjectRefs 主体清单（**必须逐条列明**，不许「导出全部」）
   */
  public record PiiAccessExportRequestInput(
      @NotBlank @Pattern(regexp = "CUSTOMER|ORDER") String subjectKind,
      @NotBlank @Pattern(regexp = "NAME|PHONE|EMAIL|ADDRESS") String fieldCode,
      @NotNull @Min(1) Integer recordCount,
      @NotBlank @Size(max = 600) String purpose,
      @NotNull @Size(min = 1, max = 500) List<@NotBlank @Size(max = 160) String> subjectRefs) {}

  /** 导出清单里的一条主体。 */
  public record PiiAccessExportItemView(
      UUID itemId, String subjectKind, String subjectRef, String subjectLabel) {}

  /** 导出申请视图。 */
  public record PiiAccessExportRequestView(
      UUID requestId,
      String requestNo,
      String subjectKind,
      String fieldCode,
      String fieldLabel,
      int recordCount,
      int maxRecordsLimit,
      String purpose,
      String status,
      String statusLabel,
      UUID requestedBy,
      String requestedByName,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) UUID approvedBy,
      @Schema(nullable = true) String approvedByName,
      @Schema(nullable = true) OffsetDateTime approvedAt,
      String decisionNote,
      @Schema(nullable = true) UUID executedBy,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) UUID exportBatchId,
      OffsetDateTime expiresAt,
      List<PiiAccessExportItemView> items,
      OffsetDateTime createdAt) {}

  /** 导出申请列表（真分页 + 真筛选）。 */
  public record PiiAccessExportPage(
      List<PiiAccessExportRequestView> items,
      int page,
      int size,
      long total,
      PiiAccessExportCounts counts) {}

  /** 导出申请汇总计数。 */
  public record PiiAccessExportCounts(
      long total, long pending, long approved, long rejected, long executed) {}

  /** 审批 / 驳回入参。 */
  public record PiiAccessExportDecisionInput(@Schema(nullable = true) @Size(max = 600) String decisionNote) {}

  /** 导出执行结果里的一行（**这是真的完整值** —— 已审批、有限量、有留痕）。 */
  public record PiiAccessExportedRow(
      String subjectKind, String subjectRef, String subjectLabel, String value, boolean present) {}

  /** 一次导出执行的结果。 */
  public record PiiAccessExportResult(
      UUID requestId,
      String requestNo,
      String exportNo,
      UUID batchId,
      int recordCount,
      String fieldCode,
      OffsetDateTime exportedAt,
      List<PiiAccessExportedRow> rows) {}

  /**
   * 导出执行的结果。
   *
   * <p>🔴 与 {@link PiiAccessRevealOutcome} 同一个理由：被拒时（未审批 / 审批过期 /
   * 超出当前上限）服务层**已经把那次导出尝试写进审计表**了 ——
   * 在事务里抛异常会把留痕一起回滚掉。所以由控制器在事务提交后才转成 HTTP 403。
   */
  public record PiiAccessExportOutcome(
      boolean allowed, String denialReason, PiiAccessExportResult result) {}
}
