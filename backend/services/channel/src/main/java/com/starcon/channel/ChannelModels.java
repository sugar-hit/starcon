package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire contracts for 02 渠道与店铺.
 *
 * <p>Module references follow docs/product/capability-blueprint/02-channel-and-shop.md.
 *
 * <p>Deliberately absent: any type that can hold a plaintext token. 2.2.1.3 requires the database to
 * store only a secret-manager reference, so {@code CredentialRefView} exposes provider, reference,
 * validity window and scopes — never a secret. Making that unrepresentable at the type level means a
 * careless future endpoint cannot start echoing credentials.
 */
public final class ChannelModels {
  private ChannelModels() {}

  // ---- 2.1 平台目录（全局码表，无 tenant_id） ----

  /** 2.1.1.1 平台目录 / 2.1.1.2 能力矩阵 / 2.1.1.3 站点与国家参数。 */
  public record PlatformView(
      UUID id,
      String code,
      String name,
      String integration,
      String supportStatus,
      /** 2.1.1.2：平台能力声明，被 09/07/10/11 引用来禁用不支持的按钮。 */
      List<String> capabilities,
      List<MarketplaceView> marketplaces) {}

  public record MarketplaceView(
      UUID id,
      String code,
      String name,
      String countryCode,
      String defaultCurrency,
      String defaultTimezone,
      String defaultLocale,
      int currencyScale,
      boolean vatRequired) {}

  /** 2.1.2.1 平台枚举映射：平台枚举 → 内部枚举。映射缺失必须显式暴露，不落兜底。 */
  public record EnumMappingView(
      UUID id, String platformCode, String enumKind, String externalValue, String internalValue) {}

  public record EnumMappingInput(
      @NotBlank String platformCode,
      @NotBlank String enumKind,
      @NotBlank String externalValue,
      @NotBlank String internalValue) {}

  // ---- 2.2 授权与连接 ----

  /**
   * 2.2.1.1 连接列表。
   *
   * <p>No credential field exists here on purpose — see the class comment.
   */
  public record ConnectionView(
      UUID id,
      UUID platformId,
      String platformCode,
      String platformName,
      String authorizedAccount,
      String displayName,
      String status,
      boolean syncPaused,
      Instant lastSyncAt,
      String lastError,
      long version,
      /** 2.2.2.1 诊断：令牌剩余有效期与到期状态，不含令牌本身。 */
      CredentialMetaView credential,
      long shopCount) {}

  /** 2.2.1.3 凭据引用管理：只暴露"是否存在、有效期、权限范围"。 */
  public record CredentialMetaView(
      UUID id,
      String provider,
      String tokenType,
      String grantedScopes,
      Instant authorizedAt,
      Instant expiresAt,
      Instant rotatedAt,
      String status) {}

  public record ConnectionInput(
      @NotBlank String platformCode,
      @NotBlank @Pattern(regexp = ".+@.+\\..+|^[0-9]{6,}$", message = "平台账号格式不正确") String authorizedAccount,
      String displayName) {}

  /**
   * 2.2.1.3 凭据轮换：调用方只提交密钥管理器写入后的引用，绝不接受明文令牌。
   */
  public record CredentialRotateInput(
      @NotBlank String externalRef,
      @NotBlank String grantedScopes,
      Instant authorizedAt,
      Instant expiresAt) {}

  /** 2.2.2.1 健康诊断：一屏判断"现在能不能正常同步"。 */
  public record ConnectionHealthView(
      UUID connectionId,
      String status,
      String diagnosis,
      String recommendation,
      boolean canSync,
      Instant tokenExpiresAt,
      long daysUntilExpiry,
      Instant lastSyncAt,
      String lastError) {}

  /** 2.2.2.2 重新授权提醒：按平台差异化阈值（Shopee 等令牌有效期短）。 */
  public record ReauthorizationReminderView(
      UUID connectionId, String platformCode, String authorizedAccount, Instant expiresAt, long daysUntilExpiry, String severity) {}

  // ---- 2.3 店铺与站点 ----

  /** 2.3.1.1 店铺列表 / 2.3.1.2 店铺详情。 */
  public record ShopView(
      UUID id,
      UUID platformId,
      String platformCode,
      String platformName,
      UUID connectionId,
      String connectionAccount,
      UUID legalEntityId,
      String code,
      String name,
      String platformShopId,
      /** 2.3.2.1 经营模式：09/12/13 的成本口径第一分叉点。 */
      String operatingMode,
      /** 2.3.2.2 负责人：用于业绩归属与绩效，不等于数据可见性。 */
      UUID ownerId,
      UUID departmentId,
      String status,
      long version,
      int marketplaceCount,
      List<ShopMarketplaceView> marketplaces,
      Instant lastSyncAt) {}

  public record ShopMarketplaceView(UUID marketplaceId, String marketplaceCode, String marketplaceName, String status, Instant enabledAt) {}

  public record ShopInput(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "店铺编码只允许字母数字下划线中划线") String code,
      @NotBlank @Size(max = 150) String name,
      @NotBlank String platformCode,
      // 连接与法人可留空：新建店铺时往往先建档再授权，连接可稍后在详情页绑定
      @Schema(nullable = true) UUID connectionId,
      @Schema(nullable = true) UUID legalEntityId,
      @NotBlank String operatingMode,
      /**
       * 乐观锁版本号。
       *
       * <p>更新时必填且必须与库中当前版本一致，否则服务端拒绝写入——防止两个运营同时改同一家
       * 店铺时后者的修改被静默覆盖。创建时传 0 或忽略。
       */
      Long version) {}

  public record ShopPatch(
      @NotBlank @Size(max = 150) String name,
      UUID connectionId,
      UUID legalEntityId,
      @NotBlank String operatingMode,
      @NotNull Long version) {}

  public record ShopMarketplaceInput(@NotBlank String marketplaceCode, String status) {}

  /** 2.3.2.2 负责人与数据范围：负责人用于业绩归属，可见性由 17 的数据范围单独控制。 */
  public record ShopOwnerTransferInput(@NotNull UUID newOwnerId, @NotNull UUID departmentId) {}

  // ---- 2.4 同步运行 ----

  /**
   * 2.4.1.1 同步任务。
   *
   * <p>粒度固定为（连接, 站点, 资源类型, 分区）——不允许整店一把梭，否则部分失败无法续传。
   */
  public record SyncCursorView(
      UUID id,
      UUID connectionId,
      String connectionAccount,
      String platformCode,
      @Schema(nullable = true) String marketplaceCode,
      String resourceType,
      String partitionKey,
      String cursorValue,
      String status,
      int consecutiveFailures,
      @Schema(nullable = true) String lastRunStatus,
      int openFailures,
      @Schema(nullable = true) Instant lastSuccessAt,
      @Schema(nullable = true) Instant lastFailureAt,
      long version) {}

  public record SyncCursorInput(
      @NotNull UUID connectionId,
      @Schema(nullable = true) UUID marketplaceId,
      @NotBlank String resourceType,
      @Pattern(regexp = "[A-Za-z0-9_.:-]{0,64}") String partitionKey) {}

  /** 2.4.1.2 运行日志。已结束的运行不可修改，只能创建新的重放运行。 */
  public record SyncRunView(
      UUID id,
      UUID cursorId,
      String platformCode,
      @Schema(nullable = true) String marketplaceCode,
      String resourceType,
      String partitionKey,
      String triggerSource,
      String status,
      Instant startedAt,
      @Schema(nullable = true) Instant finishedAt,
      int fetchedCount,
      int writtenCount,
      int skippedCount,
      int failedCount,
      String cursorAfter,
      @Schema(nullable = true) Integer durationMs) {}

  public record SyncRunResult(
      String status,
      int fetchedCount,
      int writtenCount,
      int skippedCount,
      String cursorAfter,
      @Schema(nullable = true) Integer durationMs) {}

  /** 2.4.1.3 回退目标游标值。 */
  public record CursorRewindInput(@NotBlank String cursorValue) {}

  /** 结束一次运行：结果计数 + 本次失败明细。 */
  public record SyncRunCompletionInput(
      @NotNull @Valid SyncRunResult result, List<@Valid SyncFailureInput> failures) {}

  /**
   * 2.4.2.1 失败记录。
   *
   * <p>{@code externalKey} 是外部系统主键，重放去重靠它——同一 external_key 在同一游标下
   * 只会有一条失败记录，因此重复重放不会产生第二张订单。
   */
  public record SyncFailureInput(
      @NotBlank @Size(max = 200) String externalKey,
      @NotBlank String resourceType,
      @NotNull FailureReason reason,
      @Size(max = 2000) String detail,
      @Size(max = 64) String payloadDigest,
      @Size(max = 500) String lastError) {}

  public enum FailureReason {
    /** 授权失效会导致持续漏单，必须升级为工作台待办。 */
    AUTHORIZATION_EXPIRED,
    FIELD_PARSE_FAILED,
    MAPPING_MISSING,
    RATE_LIMITED,
    NETWORK_ERROR,
    PLATFORM_ERROR
  }

  public record SyncFailureView(
      UUID id,
      UUID cursorId,
      String platformCode,
      @Schema(nullable = true) String marketplaceCode,
      String externalKey,
      String resourceType,
      String reason,
      String detail,
      String payloadDigest,
      int attempts,
      String status,
      String lastError,
      Instant occurredAt) {}

  /** 2.4.2.1 失败原因分布。 */
  public record SyncFailureSummary(String reason, long total, @Schema(nullable = true) Instant oldest) {}

  // ---- 2.5 平台回写 ----

  /**
   * 2.5.1.1 回写任务。
   *
   * <p>状态 UNKNOWN 表示"请求已发出但没收到明确结果"，它不是失败：
   * 必须先核验平台实际状态再决定重发，否则会造成重复发货或库存错扣。
   */
  public record PlatformOperationView(
      UUID id,
      UUID shopId,
      String shopName,
      String platformCode,
      @Schema(nullable = true) String marketplaceCode,
      String operation,
      String idempotencyKey,
      String payload,
      String status,
      String platformRequestId,
      String verification,
      @Schema(nullable = true) Instant verifiedAt,
      int attempts,
      String lastError,
      Instant occurredAt) {}

  public record PlatformOperationInput(
      @NotNull UUID shopId,
      @Schema(nullable = true) UUID marketplaceId,
      @NotNull OperationKind operation,
      /** 业务幂等键：重发同一条回写不会产生第二次平台侧动作。 */
      @NotBlank @Size(max = 128) String idempotencyKey,
      @Size(max = 20000) String payload) {}

  public enum OperationKind {
    PUBLISH,
    UPDATE_PRICE,
    UPDATE_STOCK,
    SHIP_UPDATE,
    CANCEL_ORDER
  }

  /** 2.5.1.2 核验结论。 */
  public enum Verification {
    CONFIRMED_APPLIED,
    CONFIRMED_NOT_APPLIED,
    STILL_UNKNOWN
  }

  public record OperationAckInput(@Size(max = 128) String platformRequestId) {}

  public record OperationFailInput(@NotBlank @Size(max = 500) String reason) {}

  public record VerificationInput(@NotNull Verification verification) {}
}
