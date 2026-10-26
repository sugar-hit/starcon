package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.1.1.1 应用与凭证的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理第三方接入的应用：应用注册、API Key/Secret 生成与轮换、授权范围（可访问的资源与操作）、
 * IP 白名单、调用配额。 输入：应用注册；凭证轮换；授权范围配置。 输出：应用档案 + 凭证
 * （<b>明文仅在生成时展示一次</b>，之后只存摘要）。 关系：上游 = 17 权限；下游 = 18.1.1.2 接口目录。
 * <b>对外凭证与平台凭据同等对待</b>：不落库明文、访问有审计、泄露可一键吊销。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code Integration*} 前缀 —— **刻意不用 {@code Platform*} 前缀**：
 * channel 服务那一族 {@code PlatformAppeal* / PlatformPriceRule* / PlatformViolation*} 说的是
 * 「Amazon / eBay / TikTok 这些**电商平台**」，与本模块的「开放平台」是两回事；
 * 契约被 codegen 合并进同一个扁平命名空间，同前缀会让读代码的人以为是一件事。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：配额上限与用量是数量（{@code BigDecimal} +
 * {@code NUMERIC(19,6)}），本模块不落任何金额。
 *
 * <p><b>🔴 明文只出现在 {@link IntegrationCredentialIssued} 这一个模型里</b> —— 它是
 * 「生成 / 轮换」那一次响应。其余所有模型**永远不含明文**。
 */
public final class AppCredentialModels {

  private AppCredentialModels() {}

  /** 配额窗口类型（UTC 截断）。 */
  public enum QuotaWindow {
    SECOND("秒"),
    MINUTE("分钟"),
    HOUR("小时"),
    DAY("天"),
    MONTH("月");

    private final String label;

    QuotaWindow(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析窗口类型；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static QuotaWindow parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var w : values()) {
        if (w.name().equalsIgnoreCase(raw.trim())) return w;
      }
      return null;
    }
  }

  /** 对外授权的操作类型。 */
  public enum ScopeAction {
    READ("读"),
    WRITE("写");

    private final String label;

    ScopeAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析操作类型；非法取值返回 {@code null}。 */
    public static ScopeAction parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var a : values()) {
        if (a.name().equalsIgnoreCase(raw.trim())) return a;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record IntegrationAppCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record IntegrationAppGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 18.1.1.1 的能力声明。 */
  public record IntegrationAppCapabilities(
      List<IntegrationAppCapability> capabilities, List<IntegrationAppGap> downstreamGaps) {}

  // ================================================================ 选项

  /** 一个词表选项（值 + 名称 + 口径说明）。 */
  public record IntegrationAppOption(String value, String label, String basis) {}

  /**
   * 筛选项下拉。
   *
   * @param scopeResources 已授权过的资源码（**来自真实数据**，不是写死的清单）
   */
  public record IntegrationAppOptions(
      List<IntegrationAppOption> appTypes,
      List<IntegrationAppOption> statuses,
      List<IntegrationAppOption> scopeActions,
      List<IntegrationAppOption> quotaWindows,
      List<String> scopeResources) {}

  // ================================================================ 应用档案

  /** 一个应用档案行。 */
  public record IntegrationAppRow(
      UUID id,
      String appCode,
      String appName,
      String appType,
      String appTypeLabel,
      String description,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID ownerUserId,
      String contactName,
      String contactEmail,
      long activeCredentialCount,
      long scopeCount,
      long ipAllowlistCount,
      long quotaCount,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      String revokeReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 注册应用。
   *
   * <p>🔴 {@code ownerUserId} **不在入参里**：负责人来自**已认证会话**的 user_id，
   * 不接受请求参数传入。
   */
  public record IntegrationAppInput(
      String appCode, String appName, String appType, String description, String contactName, String contactEmail) {}

  /** 应用列表页（真分页 + 真筛选）。 */
  public record IntegrationAppPage(
      List<IntegrationAppRow> items, int page, int size, long total, long activeCount, long revokedCount) {}

  /** 概览：几个数。 */
  public record IntegrationAppOverview(
      long totalApps,
      long activeApps,
      long revokedApps,
      long activeCredentials,
      long appsWithIpAllowlist,
      long appsWithQuota,
      OffsetDateTime generatedAt) {}

  /** 应用档案详情（五块拼起来）。 */
  public record IntegrationAppDetail(
      IntegrationAppRow app,
      List<IntegrationCredentialRow> credentials,
      List<IntegrationScopeRow> scopes,
      List<IntegrationIpAllowlistRow> ipAllowlist,
      List<IntegrationQuotaRow> quotas,
      List<IntegrationQuotaStatusRow> quotaStatus) {}

  // ================================================================ 凭证

  /**
   * 一个凭证（版本链上的一版）。
   *
   * <p>🔴 **这里永远没有明文**：只有展示用前缀与末四位提示 + 状态 + 生效时刻。
   */
  public record IntegrationCredentialRow(
      UUID id,
      UUID appId,
      int version,
      String status,
      String statusLabel,
      String apiKeyPrefix,
      String secretHint,
      OffsetDateTime issuedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) UUID rotatedFromId,
      @Schema(nullable = true) OffsetDateTime rotatedAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      String revokeReason,
      @Schema(nullable = true) OffsetDateTime lastUsedAt,
      OffsetDateTime createdAt) {}

  /**
   * **明文只在这里出现一次**。
   *
   * <p>🔴 这是全仓唯一一处把 API Key / Secret 明文放进响应的地方 —— 生成与轮换那一次。
   * 之后再也读不出来（库里只有摘要）。
   */
  public record IntegrationCredentialIssued(
      IntegrationCredentialRow credential, String apiKey, String secret, String notice) {}

  // ================================================================ 授权范围

  /** 一条对外授权（资源 × 操作）。 */
  public record IntegrationScopeRow(
      UUID id,
      UUID appId,
      String resourceCode,
      String actionCode,
      String actionCodeLabel,
      OffsetDateTime grantedAt,
      @Schema(nullable = true) UUID grantedBy) {}

  /** 授权入参。 */
  public record IntegrationScopeInput(String resourceCode, String actionCode) {}

  // ================================================================ IP 白名单

  /** 一条 IP 白名单。 */
  public record IntegrationIpAllowlistRow(
      UUID id,
      UUID appId,
      String cidr,
      String note,
      OffsetDateTime createdAt,
      @Schema(nullable = true) UUID createdBy) {}

  /** 白名单入参。 */
  public record IntegrationIpAllowlistInput(String cidr, String note) {}

  // ================================================================ 调用配额

  /** 一条配额配置。 */
  public record IntegrationQuotaRow(
      UUID id,
      UUID appId,
      String windowType,
      String windowTypeLabel,
      BigDecimal limitCount,
      boolean enabled,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 配额配置入参。 */
  public record IntegrationQuotaInput(String windowType, BigDecimal limitCount, Boolean enabled) {}

  /**
   * 一个窗口的配额判定。
   *
   * @param configured 这个窗口**配过**没有（没配 = 不限，不是「限 0 次」）
   * @param remainingCount 剩余额度（已用超过上限时为 0，不出现负数）
   * @param exceeded 已超限
   * @param decision {@code ALLOW} / {@code EXCEEDED} / {@code NOT_CONFIGURED}
   */
  public record IntegrationQuotaStatusRow(
      String windowType,
      String windowTypeLabel,
      BigDecimal limitCount,
      boolean configured,
      boolean enabled,
      BigDecimal usedCount,
      BigDecimal remainingCount,
      boolean exceeded,
      @Schema(nullable = true) OffsetDateTime windowStartedAt,
      @Schema(nullable = true) OffsetDateTime windowEndsAt,
      String decision,
      String note) {}

  // ================================================================ 审计

  /** 一条审计留痕。 */
  public record IntegrationAuditEventRow(
      UUID id,
      UUID appId,
      @Schema(nullable = true) UUID credentialId,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) UUID actorUserId,
      String actorKind,
      String detail,
      OffsetDateTime createdAt) {}

  /** 审计留痕页（真分页）。 */
  public record IntegrationAuditEventPage(
      List<IntegrationAuditEventRow> items, int page, int size, long total) {}

  // ================================================================ 凭证自检

  /**
   * 凭证自检入参。
   *
   * @param clientIp 调用方 IP（可空）：白名单判定要用它
   * @param resourceCode / actionCode 要访问的资源与操作（可空）：授权范围判定要用它
   */
  public record IntegrationCredentialVerifyInput(
      String apiKey, String secret, String clientIp, String resourceCode, String actionCode) {}

  /**
   * 凭证自检结论。
   *
   * <p>🔴 **四个判定是分开的**（凭证 / 授权范围 / IP 白名单 / 配额），
   * 不合成一个布尔值 —— 「凭证对但没授权」和「凭证错」要修的地方完全不同。
   */
  public record IntegrationCredentialVerifyResult(
      boolean credentialMatched,
      String credentialStatus,
      String appStatus,
      boolean scopeGranted,
      boolean scopeChecked,
      boolean ipAllowed,
      boolean ipChecked,
      boolean quotaExceeded,
      List<IntegrationQuotaStatusRow> quotaStatus,
      String conclusion,
      String note) {}

  // ================================================================ 用量回报

  /**
   * 网关回报一次调用用量（**服务端到服务端**，内部密钥路径）。
   *
   * @param tenantId 网关解析出来的租户（**不是**浏览器路径；浏览器路径一律用会话租户）
   */
  public record IntegrationQuotaUsageInput(
      UUID tenantId, UUID appId, String windowType, BigDecimal count, OffsetDateTime at) {}

  /** 用量回报结果。 */
  public record IntegrationQuotaUsageResult(
      UUID appId,
      String windowType,
      BigDecimal usedCount,
      @Schema(nullable = true) BigDecimal limitCount,
      OffsetDateTime windowStartedAt,
      boolean exceeded) {}
}
