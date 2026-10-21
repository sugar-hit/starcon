package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.4.2.2 授权凭证的传输模型。
 *
 * <p>蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。<b>凭证需保留原始文件与元数据，争议时是唯一证据。</b>」
 *
 * <p>🔴 四个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link BrandCredentialCapabilities} —— 「哪些能力量得到、哪些量不到，量不到归谁」。
 *   <li>{@link BrandCredentialRecord} + {@link BrandCredentialDetail} —— 「凭证档案」：
 *       授权方 / 凭证编号 / 类型 / 有效期 / 状态 / 当前文件版本 / 最近核验状态。
 *   <li>{@link BrandCredentialVerificationResult} —— 「这份凭证文件现在是不是真的、能不能用」：
 *       结论 + 逐条原因 + 判定时的输入快照。
 *   <li>{@link BrandCredentialVerificationRow} + {@link BrandCredentialVerificationPage} ——
 *       「核验记录」（追加式凭据，真分页）。
 * </ul>
 *
 * <p>🔴 <b>「没核验过」与「核验过但不通过」是两个状态</b>：{@code verificationState=UNVERIFIED} 是
 * <b>还没查</b>，{@code FAILED} 才是<b>查了且没过</b>。把前者显示成后者，等于把「没查」
 * 写成「假的」；把后者显示成前者，等于把「假的」写成「没查」。两者都不许丢掉原因。
 */
public final class BrandCredentialModels {

  private BrandCredentialModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付 / 已交付的下游或维度。
   *
   * @param moduleId 模块号（如 16.4.1.2）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 量不到 / 未交付的影响说明（界面原样显示）
   */
  public record BrandCredentialCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param credentialTypes 凭证类型词表
   * @param conclusions 核验结论词表
   * @param severities 核验分级词表
   * @param verificationStates 凭证「最近核验状态」词表
   * @param expiryStates 到期状态词表
   * @param verificationSemantics 核验结论口径（每种结论各自意味着什么）
   * @param digestSemantics 「真伪」到底是怎么判的（本地重算 sha256，不连品牌方系统）
   * @param appendOnlySemantics 追加式纪律（文件版本与核验记录都不可改写）
   * @param boundaryNote 与 16.4.2.1 品牌授权的边界说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record BrandCredentialCapabilities(
      List<BrandCredentialCapabilityGap> gaps,
      List<String> credentialTypes,
      List<String> conclusions,
      List<String> severities,
      List<String> verificationStates,
      List<String> expiryStates,
      String verificationSemantics,
      String digestSemantics,
      String appendOnlySemantics,
      String boundaryNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 凭证档案

  /**
   * 凭证档案的一行。
   *
   * @param credentialId 凭证 id
   * @param authorizationId 上游 16.4.2.1 的授权档案 id（逻辑 id，可空）
   * @param grantor 授权方
   * @param credentialNo 凭证编号
   * @param credentialType 凭证类型
   * @param credentialTypeLabel 凭证类型的人话
   * @param subject 被授权主体
   * @param brandId 关联品牌（catalog 逻辑 id，可空）
   * @param brandName 品牌名快照
   * @param validFrom 有效期起点
   * @param validUntil 有效期终点
   * @param issuedOn 签发日（可空）
   * @param status ACTIVE / REVOKED
   * @param statusLabel 状态的人话
   * @param revokedAt 撤销时刻（可空）
   * @param revokedReason 撤销理由（可空）
   * @param expiryState 到期状态（ACTIVE / EXPIRING / EXPIRED / NOT_YET_EFFECTIVE / REVOKED）
   * @param expiryStateLabel 到期状态的人话
   * @param daysToExpiry 距到期天数（负 = 已过期 N 天）
   * @param currentVersion 当前文件版本号（**现算**：由 brand_cred_document 聚合出来）
   * @param documentCount 文件版本数
   * @param signatoryCount 签署方数
   * @param verificationState 最近核验状态（UNVERIFIED / VERIFIED / FAILED，**现算**）
   * @param verificationStateLabel 最近核验状态的人话
   * @param lastVerifiedAt 最近一次核验时刻（可空）
   * @param lastConclusion 最近一次核验结论（可空）
   * @param note 备注
   * @param version 行级乐观锁版本
   * @param createdAt 创建时刻
   * @param updatedAt 最近更新时刻
   */
  public record BrandCredentialRecord(
      String credentialId,
      String authorizationId,
      String grantor,
      String credentialNo,
      String credentialType,
      String credentialTypeLabel,
      String subject,
      String brandId,
      String brandName,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      LocalDate issuedOn,
      String status,
      String statusLabel,
      OffsetDateTime revokedAt,
      String revokedReason,
      String expiryState,
      String expiryStateLabel,
      int daysToExpiry,
      int currentVersion,
      int documentCount,
      int signatoryCount,
      String verificationState,
      String verificationStateLabel,
      OffsetDateTime lastVerifiedAt,
      String lastConclusion,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一个签署方。 */
  public record BrandCredentialSignatoryRow(
      String signatoryId,
      String credentialId,
      String signatoryName,
      String signatoryTitle,
      String signatoryOrg,
      String contact,
      boolean identityVerified,
      String note,
      OffsetDateTime createdAt) {}

  /** 一个凭证文件版本（**只追加**；文件本体在受管存储里，本体不在这里）。 */
  public record BrandCredentialDocumentRow(
      String documentId,
      String credentialId,
      int versionNo,
      String fileName,
      String contentType,
      BigDecimal sizeBytes,
      String storageKey,
      String sha256,
      String uploadedBy,
      OffsetDateTime uploadedAt,
      String note,
      OffsetDateTime createdAt) {}

  /** 凭证档案详情：档案 + 签署方 + 文件版本 + 最近的核验记录。 */
  public record BrandCredentialDetail(
      BrandCredentialRecord record,
      List<BrandCredentialSignatoryRow> signatories,
      List<BrandCredentialDocumentRow> documents,
      List<BrandCredentialVerificationRow> verifications) {}

  /** 凭证档案列表（**真分页**）。 */
  public record BrandCredentialPage(
      List<BrandCredentialRecord> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 核验

  /**
   * 一条核验原因。
   *
   * @param code 原因码（机器可读）
   * @param label 原因的人话标题
   * @param message 原因说明（**必须能直接显示给运营**）
   * @param severity FAIL（不通过的原因）/ WARN（通过了但仍要提醒）
   */
  public record BrandCredentialVerificationReason(
      String code, String label, String message, String severity) {}

  /**
   * 一条核验记录（追加式凭据）。
   *
   * @param verificationId 核验记录 id
   * @param credentialId 被核验的凭证
   * @param credentialNo 凭证编号（快照）
   * @param grantor 授权方（快照）
   * @param documentId 被核验的文件版本（可空）
   * @param documentVersionNo 被核验的文件版本号（0 = 没有文件）
   * @param evaluatedAt 判定时刻（DB 时钟）
   * @param ruleVersion 判定用的规则版本
   * @param conclusion 结论
   * @param conclusionLabel 结论的人话
   * @param severity PASS / FAIL
   * @param severityLabel 分级的人话
   * @param passed 是否通过
   * @param reasons 逐条原因（**不通过时必非空**）
   * @param reasonCount 原因条数
   * @param checkedBy 核验人
   * @param createdAt 记录写入时刻
   */
  public record BrandCredentialVerificationRow(
      String verificationId,
      String credentialId,
      String credentialNo,
      String grantor,
      String documentId,
      int documentVersionNo,
      OffsetDateTime evaluatedAt,
      long ruleVersion,
      String conclusion,
      String conclusionLabel,
      String severity,
      String severityLabel,
      boolean passed,
      List<BrandCredentialVerificationReason> reasons,
      int reasonCount,
      String checkedBy,
      OffsetDateTime createdAt) {}

  /** 核验记录历史（**真分页**，计数不受分页影响）。 */
  public record BrandCredentialVerificationPage(
      List<BrandCredentialVerificationRow> items, long total, int page, int size) {}

  /**
   * 一次核验的结论（服务端算出来的）。
   *
   * @param verificationId 核验记录 id（**追加式凭据**，事后改写不了）
   * @param credential 被核验的凭证档案（算完顺带回给界面，省一次回查）
   * @param documentId 被核验的文件版本（可空）
   * @param documentVersionNo 被核验的文件版本号（0 = 没有文件）
   * @param evaluatedAt 判定时刻（DB 时钟）
   * @param ruleVersion 判定用的规则版本
   * @param conclusion 结论
   * @param conclusionLabel 结论的人话
   * @param severity PASS / FAIL
   * @param severityLabel 分级的人话
   * @param passed 是否通过
   * @param reasons 逐条原因（**不通过时必非空**）
   * @param snapshot 判定时的输入快照（登记摘要 / 实算摘要 / 文件版本 / 签署方数 …）
   * @param checkedBy 核验人
   */
  public record BrandCredentialVerificationResult(
      String verificationId,
      BrandCredentialRecord credential,
      String documentId,
      int documentVersionNo,
      OffsetDateTime evaluatedAt,
      long ruleVersion,
      String conclusion,
      String conclusionLabel,
      String severity,
      String severityLabel,
      boolean passed,
      List<BrandCredentialVerificationReason> reasons,
      Map<String, Object> snapshot,
      String checkedBy) {}

  /**
   * 批量核验的结果。
   *
   * @param results 逐条核验结论（**顺序与请求一致**）
   * @param verifiedCount 通过的条数
   * @param failedCount 不通过的条数
   */
  public record BrandCredentialVerificationBatchResult(
      List<BrandCredentialVerificationResult> results, int verifiedCount, int failedCount) {}

  // ---------------------------------------------------------------- 录入

  /**
   * 凭证登记 / 修改的入参。
   *
   * <p>🔴 名字里带 {@code Registration} 是**必需**的：catalog 3.2.1.2 已经有
   * {@code BrandAuthorizationInput}，两个服务同名 DTO 会被
   * {@code tools/codegen/catalog-contracts.py} 的跨服务覆盖检测拦下。
   */
  public record BrandCredentialRegistrationInput(
      @Schema(nullable = true) String authorizationId,
      @NotBlank @Size(max = 200) String grantor,
      @NotBlank @Size(max = 120) String credentialNo,
      @NotBlank @Size(max = 24) String credentialType,
      @Size(max = 200) String subject,
      @Schema(nullable = true) String brandId,
      @Size(max = 200) String brandName,
      @NotBlank String validFrom,
      @NotBlank String validUntil,
      @Schema(nullable = true) String issuedOn,
      @Size(max = 1000) String note) {}

  /** 登记一个签署方。 */
  public record BrandCredentialSignatoryInput(
      @NotBlank @Size(max = 200) String signatoryName,
      @Size(max = 120) String signatoryTitle,
      @Size(max = 200) String signatoryOrg,
      @Size(max = 200) String contact,
      Boolean identityVerified,
      @Size(max = 1000) String note) {}

  /**
   * 上传凭证文件（JSON 形状：文件本体走 base64）。
   *
   * <p>🔴 浏览器侧走同源代理，代理只转发 JSON（请求体上限约 200,000 字符），
   * 所以 base64 后的原文上限约 140 KB；直连 multipart 上传的服务端硬限是 20 MB。
   * 两者都写在这里，界面据此提示而不是让用户撞一个没有解释的 413。
   *
   * @param fileName 文件名
   * @param contentType MIME 类型
   * @param contentBase64 文件本体的 base64（**不含** data URL 前缀）
   * @param note 备注
   */
  public record BrandCredentialDocumentInput(
      @NotBlank @Size(max = 255) String fileName,
      @Size(max = 120) String contentType,
      @NotBlank @Size(max = 40_000_000) String contentBase64,
      @Size(max = 1000) String note) {}

  /** 批量核验的入参（**一次最多 50 个**）。 */
  public record BrandCredentialVerificationBatchInput(
      @NotBlank @Size(max = 50) List<@NotBlank String> credentialIds) {}

  /** 撤销凭证的入参（**必须写明理由**）。 */
  public record BrandCredentialRevokeInput(@NotBlank @Size(max = 1000) String reason) {}

  // ---------------------------------------------------------------- 上游候选

  /**
   * 上游 16.4.2.1 的授权档案候选（**同库只读**）。
   *
   * @param authorizationId 授权档案 id
   * @param brandName 品牌名快照
   * @param grantor 授权方
   * @param licenseeName 被授权主体
   * @param effectiveFrom 授权期限起点
   * @param effectiveTo 授权期限终点
   */
  public record BrandCredentialAuthorizationOption(
      String authorizationId,
      String brandName,
      String grantor,
      String licenseeName,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo) {}

  /** 上游候选清单。 */
  public record BrandCredentialAuthorizationOptionPage(
      List<BrandCredentialAuthorizationOption> items, long total, String note) {}

  // ---------------------------------------------------------------- 概览

  /**
   * 概览（页面顶部的卡片）。
   *
   * @param credentialCount 凭证总数
   * @param activeCount 有效凭证数
   * @param expiringCount 即将过期数
   * @param expiredCount 已过期数
   * @param revokedCount 已撤销数
   * @param verifiedCount 最近一次核验通过的数
   * @param failedCount 最近一次核验不通过的数
   * @param unverifiedCount 从未核验过的数
   * @param documentCount 凭证文件版本总数
   */
  public record BrandCredentialOverview(
      long credentialCount,
      long activeCount,
      long expiringCount,
      long expiredCount,
      long revokedCount,
      long verifiedCount,
      long failedCount,
      long unverifiedCount,
      long documentCount) {}
}
