package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.2.3.1「其他对接」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：对接其他外部系统：1688/淘宝货源平台、海外仓服务商、物流商、聚合认证服务、自建 BI。统一管理连接配置与同步健康。
 * 输入：连接配置；同步任务配置。 输出：连接状态 + 同步记录。
 * 关系：上游 = 2 渠道机制；下游 = 外部服务。
 * <b>每个对接都有独立连接与独立健康状态</b>，一个外部服务故障不应影响其他集成。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code ExternalIntegration}**：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 channel / trade / warehouse 三个服务里已经有 {@code ShopView} / {@code Warehouse*} /
 * {@code SyncRun*} 一族类型。前缀撞车会让读代码的人以为是一件事。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：条数（records_total 等）一律
 * {@link BigDecimal}（库里 {@code NUMERIC(19,6)}）。条数也是数量，同样不许用浮点。
 *
 * <p><b>🔴 三组取值域刻意分开</b>，它们混在一起就会丢掉本模块最核心的那条纪律：
 * {@link ExternalHealthStatus}（<b>这一个对接</b>的健康）、
 * {@link ExternalSyncStatus}（一次同步的结论）、
 * 与 {@code providerNotified}（<b>有没有与外部系统核对过</b>）。
 * 「本地台账成功」永远不等于「外部侧已登记」。
 */
public final class ExternalIntegrationModels {

  private ExternalIntegrationModels() {}

  /** 数量小数位（与本服务 18.2.1.1 / 18.2.2.1 同一口径）。 */
  public static final int COUNT_SCALE = 6;

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（表 / 端点 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   * @param note 缺了会怎样 / 缺了归谁
   */
  public record ExternalIntegrationCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record ExternalIntegrationGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record ExternalIntegrationCapabilities(
      List<ExternalIntegrationCapability> capabilities, List<ExternalIntegrationGap> gaps) {}

  /** 概览：几个数。 */
  public record ExternalIntegrationOverview(
      long connections,
      long activeConnections,
      long healthyConnections,
      long degradedConnections,
      long unhealthyConnections,
      long notConnectedConnections,
      long unknownConnections,
      long syncTasks,
      long enabledSyncTasks,
      long syncRecords,
      long failedSyncRecords,
      OffsetDateTime generatedAt) {}

  // ================================================================ 类别与适配器

  /** 对接类别（蓝图点名的五类外部系统）。 */
  public enum ExternalIntegrationCategory {
    /** 1688 / 淘宝货源平台。 */
    SUPPLY_SOURCE("1688 / 淘宝货源平台"),
    /** 海外仓服务商。 */
    OVERSEAS_WAREHOUSE("海外仓服务商"),
    /** 物流商。 */
    LOGISTICS_CARRIER("物流商"),
    /** 聚合认证服务。 */
    CERTIFICATION_AGGREGATOR("聚合认证服务"),
    /** 自建 BI。 */
    SELF_HOSTED_BI("自建 BI");

    private final String label;

    ExternalIntegrationCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalIntegrationCategory parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 某个适配器（外部系统通道）的可用性。 */
  public record ExternalAdapterAvailability(
      String adapterCode,
      String adapterLabel,
      String category,
      String categoryLabel,
      boolean connected,
      String reason,
      String ownerModule) {}

  // ================================================================ 连接配置

  /** 对接连接一行（**密钥引用**，不是密钥）。 */
  public record ExternalConnectionRow(
      UUID id,
      String category,
      String categoryLabel,
      String adapterCode,
      String adapterLabel,
      String name,
      String baseUrl,
      /** 密钥管理器里的**引用 ID**（不是密钥本体）。 */
      String credentialRef,
      /** 末四位 / 人眼分辨用（非机密）。 */
      String credentialHint,
      boolean credentialConfigured,
      /** 上游 2 渠道机制的平台代码（**只读引用**，不建跨库 FK）。 */
      String channelPlatformCode,
      @Schema(nullable = true) UUID channelShopId,
      String status,
      String statusLabel,
      /** 🔴 **这一个对接自己**的健康状态（不与别的对接合并）。 */
      String healthStatus,
      String healthStatusLabel,
      @Schema(nullable = true) OffsetDateTime lastHealthAt,
      @Schema(nullable = true) OffsetDateTime lastSyncAt,
      @Schema(nullable = true) UUID lastSyncRecordId,
      int timeoutSeconds,
      int maxAttempts,
      long taskCount,
      long recordCount,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 对接连接配置（新增 / 修改）。 */
  public record ExternalConnectionInput(
      @Schema(nullable = true) UUID id,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) String adapterCode,
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String baseUrl,
      @Schema(nullable = true) String credentialRef,
      @Schema(nullable = true) String credentialHint,
      @Schema(nullable = true) String channelPlatformCode,
      @Schema(nullable = true) UUID channelShopId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Integer timeoutSeconds,
      @Schema(nullable = true) Integer maxAttempts,
      @Schema(nullable = true) String remark) {}

  // ================================================================ 健康状态

  /**
   * **一个对接自己**的健康状态。
   *
   * <p>🔴 这五个值描述的是<b>一个 connection_id 自己的</b>同步历史，
   * <b>永远不是</b>「所有对接的整体状态」—— 蓝图加粗句要求
   * 「一个外部服务故障不应影响其他集成」，而一旦存在一个「整体健康」，
   * 一个对接故障就会把所有对接显示成不健康。
   */
  public enum ExternalHealthStatus {
    /** 最近一次同步成功。 */
    HEALTHY("健康"),
    /** 最近一次失败，但**这个连接**历史上成功过（还能用，但已经不稳）。 */
    DEGRADED("亚健康"),
    /** 这个连接的同步**从未**成功过，且最近一次失败。 */
    UNHEALTHY("不健康"),
    /** 还没跑过（**「没跑过」不等于「坏了」**）。 */
    UNKNOWN("未验证"),
    /** 外部通道未接入（适配器未交付 / 没有凭据），或最近一次同步根本没发出去。 */
    NOT_CONNECTED("未接入");

    private final String label;

    ExternalHealthStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalHealthStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /**
   * **一个对接自己的**健康状态。
   *
   * <p>🔴 这个对象里出现的每一个数都来自**同一个 connection_id**：
   * 「别的对接挂了几个」不在这里 —— 混进来就等于把「一个外部服务故障」
   * 传染成「所有集成都不健康」。
   */
  public record ExternalConnectionHealth(
      UUID connectionId,
      String connectionName,
      String adapterCode,
      String adapterLabel,
      String healthStatus,
      String healthStatusLabel,
      boolean healthy,
      String diagnosis,
      String recommendation,
      long recordCount,
      long successCount,
      long failureCount,
      @Schema(nullable = true) OffsetDateTime lastHealthAt,
      @Schema(nullable = true) OffsetDateTime lastSyncAt,
      @Schema(nullable = true) UUID lastSyncRecordId,
      List<ExternalSyncRecordRow> recentRecords) {}

  // ================================================================ 同步任务

  /** 同步任务类型（按类别各自成立）。 */
  public enum ExternalTaskType {
    /** 货源平台：商品 / 货源清单同步。 */
    CATALOG_SYNC("货源清单同步"),
    /** 海外仓：库存观测同步。 */
    INVENTORY_SYNC("库存观测同步"),
    /** 物流商：运单 / 轨迹同步。 */
    SHIPMENT_TRACKING_SYNC("运单轨迹同步"),
    /** 聚合认证：认证状态同步。 */
    CERTIFICATE_SYNC("认证状态同步"),
    /** 自建 BI：数据集同步。 */
    BI_DATASET_SYNC("数据集同步");

    private final String label;

    ExternalTaskType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalTaskType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }

    /** 这个任务类型只属于哪些类别（一个类型**只能**配在相容的类别上）。 */
    public boolean supports(ExternalIntegrationCategory category) {
      return switch (this) {
        case CATALOG_SYNC -> category == ExternalIntegrationCategory.SUPPLY_SOURCE;
        case INVENTORY_SYNC -> category == ExternalIntegrationCategory.OVERSEAS_WAREHOUSE;
        case SHIPMENT_TRACKING_SYNC -> category == ExternalIntegrationCategory.LOGISTICS_CARRIER;
        case CERTIFICATE_SYNC -> category == ExternalIntegrationCategory.CERTIFICATION_AGGREGATOR;
        case BI_DATASET_SYNC -> category == ExternalIntegrationCategory.SELF_HOSTED_BI;
      };
    }
  }

  /** 同步方向。 */
  public enum ExternalSyncDirection {
    /** 从外部系统拉数据回来。 */
    PULL("拉取"),
    /** 向外部系统推数据。 */
    PUSH("推送");

    private final String label;

    ExternalSyncDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalSyncDirection parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 同步任务一行。 */
  public record ExternalSyncTaskRow(
      UUID id,
      UUID connectionId,
      String connectionName,
      String adapterCode,
      String category,
      String taskType,
      String taskTypeLabel,
      String direction,
      String directionLabel,
      String name,
      String resourceRef,
      String scheduleKind,
      String scheduleExpression,
      @Schema(nullable = true) BigDecimal intervalSeconds,
      boolean enabled,
      String status,
      String statusLabel,
      String lastRunStatus,
      String lastRunStatusLabel,
      @Schema(nullable = true) OffsetDateTime lastRunAt,
      @Schema(nullable = true) UUID lastRecordId,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 同步任务配置（新增 / 修改）。 */
  public record ExternalSyncTaskInput(
      @Schema(nullable = true) UUID id,
      @Schema(nullable = true) UUID connectionId,
      @Schema(nullable = true) String taskType,
      @Schema(nullable = true) String direction,
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String resourceRef,
      @Schema(nullable = true) String scheduleKind,
      @Schema(nullable = true) String scheduleExpression,
      @Schema(nullable = true) Integer intervalSeconds,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String remark) {}

  // ================================================================ 同步记录

  /**
   * 一次同步的结论。
   *
   * <p>🔴 {@code providerNotified} 与 {@code status} **刻意分开**：
   * 「本系统台账里成功」≠「外部侧已经登记过」。
   */
  public enum ExternalSyncStatus {
    /** 同步成功。 */
    SUCCEEDED("成功"),
    /** 外部系统明确失败。 */
    FAILED("失败"),
    /** 超时（**结果未知**：外部侧可能已经动过）。 */
    TIMEOUT("超时"),
    /** 外部通道未接入（**根本没发出去**）。 */
    NOT_CONNECTED("外部通道未接入");

    private final String label;

    ExternalSyncStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalSyncStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 同步记录来源。 */
  public enum ExternalSyncSource {
    /** 经由本系统的外部通道发起。 */
    DIRECT("经由外部通道"),
    /** 外部侧**已经**完成、人工登记进本系统台账（**没有**经过本系统的通道）。 */
    EXTERNAL("外部侧已完成 · 人工登记");

    private final String label;

    ExternalSyncSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static ExternalSyncSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 同步记录一行（只追加台账）。 */
  public record ExternalSyncRecordRow(
      UUID id,
      UUID connectionId,
      String connectionName,
      UUID taskId,
      String taskName,
      int runNo,
      String status,
      String statusLabel,
      String source,
      String sourceLabel,
      BigDecimal recordsTotal,
      BigDecimal recordsSucceeded,
      BigDecimal recordsFailed,
      String externalRef,
      String failureReason,
      String failureClass,
      /** 本次同步**是否已与外部系统通道核对过**。 */
      boolean providerNotified,
      /** 外部侧没被告知 / 没核对时的说明。 */
      String providerNotice,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /**
   * 登记一条「外部侧已完成」的同步记录（**人工登记**）。
   *
   * <p>🔴 这一条走的是**本系统自己的账**：外部系统那边已经跑完了同步，
   * 运营在本系统里补一条记录，好让**这个对接**的健康状态如实反映现状。
   * 记进去的行 {@code providerNotified=false} 且 {@code source=EXTERNAL} ——
   * **不可能**被误读成「本系统与外部系统核对过」。
   */
  public record ExternalSyncRecordInput(
      @Schema(nullable = true) UUID taskId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) BigDecimal recordsTotal,
      @Schema(nullable = true) BigDecimal recordsSucceeded,
      @Schema(nullable = true) BigDecimal recordsFailed,
      @Schema(nullable = true) String externalRef,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) String failureClass,
      @Schema(nullable = true) String providerNotice,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  // ================================================================ 上游（2 渠道机制）

  /** 上游 2.1 平台目录（**只读**，来自 channel 服务的授权端点）。 */
  public record UpstreamChannelPlatformRow(
      UUID id, String code, String name, String integration, String supportStatus) {}

  /** 上游 2.3 店铺（**只读**，来自 channel 服务的授权端点）。 */
  public record UpstreamChannelShopRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      @Schema(nullable = true) OffsetDateTime lastSyncAt) {}

  /** 上游只读端口的一次调用结果（读到了什么 / 读不到的原因）。 */
  public record UpstreamReadResult<T>(boolean readable, @Schema(nullable = true) T data, String reason) {}
}
