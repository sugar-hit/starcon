package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>对接海外仓/第三方仓服务商：连接配置、仓账户、库存同步记录、外部库存观测。
 *
 * <p>🔴 本模块最重要的纪律来自蓝图 §10.4.2.1 加粗句：「外部仓库存是**观测值不是自有账本**， 必须与自营库存**分开存储与对账，不能覆盖**」。因此：
 *
 * <ul>
 *   <li>{@code ExternalStockObservationView} 是**快照**，每行带 source 与 observedAt，没有「当前余额」语义
 *   <li>本模块的写路径只有 INSERT 观测快照，永远不 UPDATE——DB 触发器与权限面各挡一层
 *   <li>自营库存账本在 warehouse 服务（独立库），本模块**够不到**它，也不试图够
 * </ul>
 *
 * <p>与 02 卷同款纪律：任何类型都装不下明文凭据。{@code WarehouseConnectionView} 只暴露 凭据的**引用**（provider / 引用串 / 有效期 /
 * 状态），不暴露秘密本身。
 */
public final class WarehouseConnectionModels {
  private WarehouseConnectionModels() {}

  // ---- 10.4.2.1 服务商目录 ----

  /** 服务商目录（全局码表，无租户维度）。能力位决定前端哪些按钮可点。 */
  public record WarehouseProviderView(
      UUID id,
      String code,
      String name,
      String kind,
      boolean supportsInventorySync,
      boolean supportsForecast,
      boolean supportsStatusCallback,
      boolean supportsBilling) {}

  // ---- 10.4.2.1 连接 ----

  /**
   * 凭据引用视图：只有引用与有效期，没有秘密。
   *
   * <p>type 层面就不存在能装明文 token 的字段，所以将来任何端点都不会误把凭据吐回前端。
   */
  public record CredentialReferenceView(
      String provider, String externalRef, String tokenType, String status, Instant expiresAt) {}

  public record WarehouseConnectionView(
      UUID id,
      UUID providerId,
      String providerCode,
      String providerName,
      String authorizedAccount,
      String displayName,
      String apiEndpointRef,
      String status,
      boolean syncPaused,
      Instant lastSyncAt,
      String lastError,
      long version,
      CredentialReferenceView credential,
      int accountCount) {}

  public record WarehouseConnectionInput(
      @NotNull UUID providerId,
      /** 只填 secret manager 的引用串，绝不接受明文密钥。 */
      @NotBlank @Size(max = 200) String credentialExternalRef,
      @NotBlank @Size(max = 255) String authorizedAccount,
      @Size(max = 150) String displayName,
      @Size(max = 200) String apiEndpointRef) {}

  // ---- 10.4.2.1 仓账户 ----

  /** 仓账户：多仓服务商下服务商给的单个物理仓账号。 */
  public record WarehouseAccountView(
      UUID id,
      UUID connectionId,
      String connectionName,
      String providerCode,
      String externalAccountCode,
      String name,
      String countryCode,
      String timezone,
      String warehouseKind,
      String status,
      long version,
      Instant lastSyncAt,
      /** 最新一次观测时点，用于识别观测陈旧（观测值过期比没有更危险）。 */
      Instant latestObservedAt,
      BigDecimal observedSkuCount) {}

  public record WarehouseAccountInput(
      @NotBlank @Size(max = 64) String externalAccountCode,
      @Size(max = 150) String name,
      @Pattern(regexp = "^[A-Za-z]{2}$", message = "国家码必须是两位字母") String countryCode,
      @Size(max = 64) String timezone,
      @NotNull WarehouseAccountKind warehouseKind) {}

  /**
   * 服务商库存报送行（适配器的输入）。
   *
   * <p>{@code reportedAt} 必须是 OffsetDateTime：时点语义要保留偏移， 换算成本地时间再存会把跨时区仓的观测时刻算错。
   */
  public record InboundStockLineInput(
      @NotBlank @Size(max = 128) String externalSku,
      @NotNull @DecimalMin(value = "0", message = "报送数量不能为负") BigDecimal quantity,
      @Size(max = 16) String unit,
      @NotBlank @Size(max = 64) String source,
      @NotNull OffsetDateTime reportedAt) {}

  public enum WarehouseAccountKind {
    OVERSEAS,
    PLATFORM_FBA,
    THIRD_PARTY
  }

  // ---- 10.4.2.1 同步 ----

  /** 同步运行记录（append-only）。 */
  public record WarehouseSyncRunView(
      UUID id,
      UUID connectionId,
      UUID accountId,
      String accountName,
      String triggerSource,
      String status,
      Instant startedAt,
      Instant finishedAt,
      int fetchedCount,
      int observedCount,
      int failedCount,
      String errorDetail) {}

  /** 触发一次库存同步。 */
  public record SyncTriggerInput(@NotNull UUID accountId) {}

  // ---- 10.4.2.1 外部库存观测 ----

  /**
   * 外部库存观测快照。
   *
   * <p>刻意**不含** available/onHand/committed 这类「当前余额」字段：观测是带时点的事实， 余额是随时间变化的派生量。给了余额字段就会有人去原地 UPDATE
   * 它， 那正是蓝图禁止的「覆盖」。
   */
  @Schema(description = "外部仓库存观测快照（append-only，非账本）")
  public record ExternalStockObservationView(
      UUID id,
      UUID accountId,
      String accountName,
      String externalSku,
      BigDecimal quantity,
      String unit,
      String source,
      Instant observedAt) {}

  /**
   * 最新观测视图：按 SKU 取该账户最新一行。
   *
   * <p>「最新」是查询出来的（observed_at 最大），不是存出来的——这正是快照语义与账本语义的 分界线，也是为什么本模块不需要、也不允许对观测表做 UPDATE。
   */
  public record ExternalStockLatestView(
      UUID accountId,
      String accountName,
      String externalSku,
      BigDecimal quantity,
      String unit,
      String source,
      Instant observedAt) {}
}
