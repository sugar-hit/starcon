package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
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
}
