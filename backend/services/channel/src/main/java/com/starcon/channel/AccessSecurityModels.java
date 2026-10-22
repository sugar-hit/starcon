package com.starcon.channel;

import static com.starcon.channel.AccessSecurityRules.*;

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
 * 16.5.2.1 登录与访问安全的传输模型。
 *
 * <p>蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * <b>平台凭据是最值钱的资产</b>，访问必须有审计且最小权限。」
 *
 * <p>🔴 <b>本文件最重要的两条纪律：</b>
 *
 * <ol>
 *   <li><b>{@code known=false} 与「没有记录」是两件事。</b>
 *       {@code known} 表达的是「上游<b>读到了没有</b>」，
 *       不是「上游<b>有没有数据</b>」。上游不可达 ⇒ 503（不返回空集合）；
 *       上游正常但查无记录 ⇒ {@code known=false} + 空集合。
 *       把这两种情况折成「没有异常登录」，界面就会把「我们没查到」说成「没有风险」。</li>
 *   <li><b>量不到的维度必须显式标 {@code available=false} + 归属模块 + 影响面。</b>
 *       例如「按来源 IP 判定异常登录」在本仓<b>量不到</b>（identity 的登录事件不带 IP），
 *       那就写清「量不到、归 17 identity、影响是什么」，而不是给一个假的 0。</li>
 * </ol>
 */
public final class AccessSecurityModels {

  private AccessSecurityModels() {}

  // ================================================================ 能力声明

  /**
   * 单个能力面的可用性。
   *
   * @param available 现在**做得到**吗（false ⇒ 该面只有策略没有事实）
   * @param ownerModule 做得到的部分归哪个模块 / 缺的那部分归哪个模块
   * @param impact 做得到 / 做不到分别意味着什么
   */
  public record AccessSecurityCapability(
      String code,
      String label,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：逐能力面 + 五条口径说明。 */
  public record AccessSecurityCapabilities(
      List<AccessSecurityCapability> capabilities,
      String mfaNote,
      String loginAuditNote,
      String alertNote,
      String ipAllowlistNote,
      String sessionNote,
      String credentialNote,
      String downstreamNote,
      String boundaryNote) {}

  // ================================================================ 策略

  /** 安全策略（一租户一条）。 */
  public record AccessSecurityPolicyView(
      UUID policyId,
      String policyName,
      boolean mfaRequired,
      String mfaPrimaryFactor,
      int mfaGracePeriodMinutes,
      @Schema(nullable = true) OffsetDateTime mfaEnforcementFrom,
      int sessionIdleTimeoutMinutes,
      int sessionAbsoluteTimeoutMinutes,
      int sessionMaxConcurrent,
      boolean sessionRevokeOnMfaChange,
      boolean sessionRevokeOnPasswordChange,
      String ipAllowlistMode,
      int anomalyWindowMinutes,
      int anomalyFailedLoginThreshold,
      int anomalySuccessLoginThreshold,
      long version,
      /** 后端没登记过策略时是 false（**后端不预填任何默认阈值**）。 */
      boolean configured,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 策略登记 / 修改（upsert）。🔴 阈值与口径必须由租户显式给，不给默认值。 */
  public record AccessSecurityPolicyInput(
      @NotBlank @Size(max = 120) String policyName,
      boolean mfaRequired,
      @NotNull @Pattern(regexp = "TOTP|SMS|EMAIL") String mfaPrimaryFactor,
      @NotNull @Min(0) @Max(43200) Integer mfaGracePeriodMinutes,
      @Schema(nullable = true) OffsetDateTime mfaEnforcementFrom,
      @NotNull @Min(1) @Max(10080) Integer sessionIdleTimeoutMinutes,
      @NotNull @Min(1) @Max(43200) Integer sessionAbsoluteTimeoutMinutes,
      @NotNull @Min(1) @Max(100) Integer sessionMaxConcurrent,
      boolean sessionRevokeOnMfaChange,
      boolean sessionRevokeOnPasswordChange,
      @NotNull @Pattern(regexp = "DISABLED|REPORT_ONLY|ENFORCE") String ipAllowlistMode,
      @NotNull @Min(1) @Max(1440) Integer anomalyWindowMinutes,
      @NotNull @Min(1) @Max(1000) Integer anomalyFailedLoginThreshold,
      @NotNull @Min(1) @Max(1000) Integer anomalySuccessLoginThreshold,
      @Schema(nullable = true) Long version) {}

  // ================================================================ MFA 登记

  /** MFA 因子登记（登记 / 绑定状态）。 */
  public record AccessSecurityMfaRegistrationView(
      UUID registrationId,
      UUID userId,
      @Schema(nullable = true) UUID membershipId,
      String factorType,
      String factorLabel,
      String status,
      /** 外部密钥管理器引用是否已登记（**只有引用，没有明文密钥**）。 */
      boolean secretRefPresent,
      @Schema(nullable = true) OffsetDateTime boundAt,
      @Schema(nullable = true) OffsetDateTime lastVerifiedAt,
      String revokedReason,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** MFA 登记入参。 */
  public record AccessSecurityMfaRegistrationInput(
      @NotNull UUID userId,
      @Schema(nullable = true) UUID membershipId,
      @NotNull @Pattern(regexp = "TOTP|SMS|EMAIL") String factorType,
      @Size(max = 120) String factorLabel,
      /** 外部密钥管理器里的因子密钥**引用**（本仓不生成、不保存明文因子密钥）。 */
      @Size(max = 400) String secretRef) {}

  /** MFA 登记列表（真分页 + 真筛选）。 */
  public record AccessSecurityMfaRegistrationPage(
      List<AccessSecurityMfaRegistrationView> items, int page, int size, long total) {}

  // ================================================================ MFA 挑战

  /**
   * MFA 挑战的一次尝试。
   *
   * <p>🔴 同一次挑战的多次尝试是**多行**（{@code challengeId} 相同、{@code attemptNo} 递增），
   * 事后改写它等于伪造「验证了几次」。
   */
  public record AccessSecurityMfaChallengeView(
      UUID recordId,
      UUID challengeId,
      UUID registrationId,
      int attemptNo,
      String factorType,
      String outcome,
      String deliveryChannel,
      String deliveryRef,
      String failureReason,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      OffsetDateTime occurredAt) {}

  /** 挑战下发登记入参。 */
  public record AccessSecurityMfaChallengeInput(
      @NotNull UUID registrationId,
      @NotNull @Pattern(regexp = "TOTP|SMS|EMAIL") String factorType,
      @NotNull @Pattern(regexp = "AUTHENTICATOR_APP|SMS|EMAIL|MANUAL") String deliveryChannel,
      @Size(max = 400) String deliveryRef,
      @NotNull @Min(30) @Max(1800) Integer expiresInSeconds) {}

  /** 验证尝试登记入参。 */
  public record AccessSecurityMfaChallengeAttemptInput(
      @NotNull @Pattern(regexp = "VERIFIED|FAILED|EXPIRED") String outcome,
      @Size(max = 400) String failureReason) {}

  /** MFA 挑战列表（真分页 + 真筛选）。 */
  public record AccessSecurityMfaChallengePage(
      List<AccessSecurityMfaChallengeView> items, int page, int size, long total) {}

  // ================================================================ 登录审计

  /** 一条登录审计。 */
  public record AccessSecurityLoginAuditView(
      UUID auditId,
      UUID sourceEventId,
      String eventType,
      String outcome,
      String actorName,
      @Schema(nullable = true) UUID actorId,
      String level,
      String requestId,
      String summary,
      OffsetDateTime occurredAt,
      /** 来源 IP；**identity 的登录事件不带 IP ⇒ 恒为空**。 */
      String sourceIp,
      boolean sourceIpAvailable,
      boolean anomaly,
      String anomalyCode,
      String note) {}

  /**
   * 登录审计列表。
   *
   * @param known 上游**读到过没有**（false ⇒ 本地还没有任何登录事件留存，
   *     「没有记录」≠「没有异常」）
   */
  public record AccessSecurityLoginAuditPage(
      boolean known,
      String note,
      List<AccessSecurityLoginAuditView> items,
      int page,
      int size,
      long total) {}

  /** 扫描入参（不填则按策略的窗口取「最近一个窗口」）。 */
  public record AccessSecurityScanInput(
      @Schema(nullable = true) OffsetDateTime from, @Schema(nullable = true) OffsetDateTime to) {}

  /** 一次扫描的结果。 */
  public record AccessSecurityScanResult(
      boolean known,
      String note,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      /** 上游事件（{@code known=false} 时是 **null**，不是空集合）。 */
      @Schema(nullable = true) List<AccessSecurityLoginAuditView> events,
      int eventsScanned,
      int eventsRecorded,
      int alertsCreated,
      int alertsUpdated,
      List<AccessSecurityAlertView> alerts) {}

  // ================================================================ 告警

  /** 告警处置留痕。 */
  public record AccessSecurityAlertActionView(
      UUID actionId,
      String action,
      String fromStatus,
      String toStatus,
      @Schema(nullable = true) UUID actorId,
      String actorName,
      String reason,
      OffsetDateTime createdAt) {}

  /** 一条告警。 */
  public record AccessSecurityAlertView(
      UUID alertId,
      String alertType,
      String severity,
      String status,
      String subjectKind,
      String subjectValue,
      String title,
      String summary,
      int eventCount,
      @Schema(nullable = true) OffsetDateTime windowStart,
      @Schema(nullable = true) OffsetDateTime windowEnd,
      OffsetDateTime detectedAt,
      @Schema(nullable = true) UUID acknowledgedBy,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID ignoredBy,
      @Schema(nullable = true) OffsetDateTime ignoredAt,
      String ignoreReason,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      String resolutionNote,
      long version,
      OffsetDateTime createdAt,
      List<AccessSecurityAlertActionView> actions) {}

  /** 告警列表（真分页 + 真筛选 + 汇总）。 */
  public record AccessSecurityAlertPage(
      List<AccessSecurityAlertView> items,
      int page,
      int size,
      long total,
      long newCount,
      long acknowledgedCount,
      long ignoredCount,
      long resolvedCount,
      long criticalCount,
      long highCount) {}

  /** 告警处置入参（🔴 处置理由必填）。 */
  public record AccessSecurityAlertActionInput(@NotBlank @Size(max = 600) String reason) {}

  // ================================================================ IP 白名单

  /** IP 白名单条目。 */
  public record AccessSecurityIpAllowlistView(
      UUID entryId,
      String cidrRange,
      String label,
      String scopeKind,
      @Schema(nullable = true) UUID scopeId,
      String status,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID createdBy,
      String createdByName,
      long version,
      OffsetDateTime createdAt) {}

  /** IP 白名单登记入参。 */
  public record AccessSecurityIpAllowlistInput(
      @NotBlank @Size(max = 64) String cidrRange,
      @Size(max = 120) String label,
      @NotNull @Pattern(regexp = "TENANT|CONNECTION") String scopeKind,
      @Schema(nullable = true) UUID scopeId,
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo) {}

  /** IP 白名单列表（真分页 + 真筛选）。 */
  public record AccessSecurityIpAllowlistPage(
      List<AccessSecurityIpAllowlistView> items, int page, int size, long total) {}

  /**
   * IP 判定结果。
   *
   * @param inAllowlist 这个 IP 是否**落在生效中的白名单网段内**
   * @param allowed 这个 IP 现在**能不能进**（REPORT_ONLY 口径下不在白名单**仍然能进**，
   *     只是会被记一条告警 —— 所以「在不在白名单」与「能不能进」必须分成两个字段）
   * @param enforce 当前口径是否强制（false ⇒ 只告警不拦）
   * @param reason 人话结论（🔴 空白名单 + 强制口径 ⇒ 一律拒绝）
   */
  public record AccessSecurityIpCheckResult(
      String ip,
      boolean valid,
      boolean allowed,
      boolean inAllowlist,
      String mode,
      String modeLabel,
      @Schema(nullable = true) UUID matchedEntryId,
      @Schema(nullable = true) String matchedCidr,
      boolean enforce,
      String reason) {}

  // ================================================================ 凭据访问审计

  /** 一条凭据访问审计。 */
  public record AccessSecurityCredentialAccessView(
      UUID accessId,
      UUID credentialId,
      String externalRef,
      String provider,
      String accessKind,
      String accessPurpose,
      String outcome,
      @Schema(nullable = true) UUID actorId,
      String actorName,
      String actorSource,
      String requestId,
      String note,
      OffsetDateTime occurredAt) {}

  /** 凭据访问审计列表（真分页 + 真筛选 + 汇总）。 */
  public record AccessSecurityCredentialAccessPage(
      List<AccessSecurityCredentialAccessView> items,
      int page,
      int size,
      long total,
      long successCount,
      long deniedCount,
      long failedCount) {}

  /** 受控访问入参。 */
  public record AccessSecurityCredentialAccessInput(
      @NotNull UUID credentialId, @NotBlank @Size(max = 200) String accessPurpose) {}

  /**
   * 一份凭据的元信息 + 它的访问统计。
   *
   * 🔴 <b>这里永远没有令牌</b>：2.2.1.3 的 {@code credential_ref} 本身就只存引用。
   */
  public record AccessSecurityCredentialView(
      UUID credentialId,
      String provider,
      String externalRef,
      String tokenType,
      String grantedScopes,
      String status,
      @Schema(nullable = true) OffsetDateTime authorizedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      long accessCount,
      @Schema(nullable = true) OffsetDateTime lastAccessedAt,
      @Schema(nullable = true) UUID lastAccessedBy) {}

  /** 凭据维度的访问汇总（真分页 + 真排序）。 */
  public record AccessSecurityCredentialPage(
      List<AccessSecurityCredentialView> items, int page, int size, long total) {}

  /** 受控访问的结果（读到的元信息 + 留下的审计凭据）。 */
  public record AccessSecurityControlledAccessResult(
      AccessSecurityCredentialView credential,
      AccessSecurityCredentialAccessView audit,
      String message) {}

  // ================================================================ 会话

  /** 会话策略的单个维度（量不到的维度显式标 available=false）。 */
  public record AccessSecuritySessionDimension(
      String code,
      String label,
      boolean available,
      String value,
      String ownerModule,
      String impact) {}

  /** 当前调用方的会话现状 + 适用的会话策略。 */
  public record AccessSecuritySessionView(
      boolean known,
      String sessionState,
      String note,
      @Schema(nullable = true) AccessSecurityPolicyView policy,
      List<AccessSecuritySessionDimension> dimensions) {}

  // ================================================================ 展示映射（与服务端同一套值域）

  /** 严重度标签。 */
  public static String severityLabel(String severity) {
    for (Severity value : Severity.values()) {
      if (value.name().equals(severity)) return value.label();
    }
    return severity;
  }

  /** 告警状态标签。 */
  public static String alertStatusLabel(String status) {
    for (AlertStatus value : AlertStatus.values()) {
      if (value.name().equals(status)) return value.label();
    }
    return status;
  }

  /** 告警类型标签。 */
  public static String alertTypeLabel(String type) {
    for (AlertType value : AlertType.values()) {
      if (value.name().equals(type)) return value.label();
    }
    return type;
  }

  /** MFA 状态标签。 */
  public static String mfaStatusLabel(String status) {
    for (MfaStatus value : MfaStatus.values()) {
      if (value.name().equals(status)) return value.label();
    }
    return status;
  }

  /** MFA 因子标签。 */
  public static String mfaFactorLabel(String factor) {
    for (MfaFactor value : MfaFactor.values()) {
      if (value.name().equals(factor)) return value.label();
    }
    return factor;
  }

  /** IP 白名单口径标签。 */
  public static String ipModeLabel(String mode) {
    for (IpAllowlistMode value : IpAllowlistMode.values()) {
      if (value.name().equals(mode)) return value.label();
    }
    return mode;
  }

  /** 凭据访问动作标签。 */
  public static String credentialAccessKindLabel(String kind) {
    for (CredentialAccessKind value : CredentialAccessKind.values()) {
      if (value.name().equals(kind)) return value.label();
    }
    return kind;
  }
}
