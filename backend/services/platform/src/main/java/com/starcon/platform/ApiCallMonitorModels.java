package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.1.1.3「调用监控」的模型（卷 18 → 18.1 开放平台 → 18.1.1 API 管理）。
 *
 * <p>蓝图原文：「<b>职责</b>：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
 * <b>输入</b>：调用埋点；配额配置。<b>输出</b>：调用统计 + 告警。<b>关系</b>：上游 = API 网关；
 * 下游 = 1 工作台告警、18.1.1.1 配额调整。<b>外部系统调用错误要能被快速定位</b>，否则对接方只会说
 * "你们接口有问题"。」
 *
 * <p><b>四条纪律：</b>
 *
 * <ol>
 *   <li><b>比率与数量一律 numeric</b>：错误率 / QPS / 耗时都是 {@link BigDecimal}，
 *       库里是 {@code NUMERIC(19,6)} —— 禁浮点（AGENTS.md）。
 *   <li><b>「限流命中」与「错误」分开</b>：429 既是错误也是限流命中，但分析「限流命中」时问的是
 *       「有多少次调用被配额挡下来了」，不是「有多少个 4xx」。两个字段都留着。
 *   <li><b>阈值口径必须显式</b>：告警阈值是平台固定口径（不替用户预置行），
 *       但它**必须**出现在接口与页面上 —— 运营看到告警要知道是按什么判的。
 *   <li><b>告警要能指向下游</b>：限流类告警带建议配额与 18.1.1.1 的配额调整端点形状。
 * </ol>
 */
public final class ApiCallMonitorModels {

  private ApiCallMonitorModels() {}

  // ================================================================ 口径枚举

  /**
   * 一次调用的结论码。
   *
   * <p>🔴 与 HTTP 状态码**逐字对应**（迁移里的 CHECK 钉死了这一条）：
   * 错误率 = {@code outcome <> SUCCESS} 的占比，与「有多少个 4xx」不是一回事。
   */
  public enum Outcome {
    SUCCESS("成功"),
    AUTH_FAILED("凭证未通过"),
    FORBIDDEN("被拒绝"),
    RATE_LIMITED("被限流"),
    NOT_FOUND("接口不存在"),
    ERROR("服务端错误");

    private final String label;

    Outcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析结论码；非法取值返回 {@code null}。 */
    public static Outcome parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var o : values()) {
        if (o.name().equalsIgnoreCase(raw.trim())) return o;
      }
      return null;
    }
  }

  /** 告警类型。 */
  public enum AlertType {
    ERROR_RATE_HIGH("错误率越线"),
    SLOW_CALL("慢调用越线"),
    RATE_LIMIT_SPIKE("限流命中越线");

    private final String label;

    AlertType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析告警类型；非法取值返回 {@code null}。 */
    public static AlertType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 告警处置状态。 */
  public enum AlertStatus {
    OPEN("待处理"),
    ACKNOWLEDGED("已确认"),
    RESOLVED("已闭环");

    private final String label;

    AlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析处置状态；非法取值返回 {@code null}。 */
    public static AlertStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 告警级别。 */
  public enum AlertSeverity {
    WARNING("警告"),
    CRITICAL("严重");

    private final String label;

    AlertSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了归谁」。 */
  public record ApiCallMonitorCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record ApiCallMonitorGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 18.1.1.3 的能力声明。 */
  public record ApiCallMonitorCapabilities(
      List<ApiCallMonitorCapability> capabilities, List<ApiCallMonitorGap> downstreamGaps) {}

  // ================================================================ 选项与阈值口径

  /** 一个筛选项（时间窗口等）。 */
  public record ApiCallMonitorOption(String code, String label, String description) {}

  /** 一条阈值口径：名字、值、单位、判定规则原文。 */
  public record ApiCallMonitorThreshold(
      String name, String label, String value, String unit, String rule) {}

  /**
   * 网关放行的**可枚举**端点清单。
   *
   * <p>🔴 这份清单就是「网关入口放行什么」的全部 —— 它是封闭的、逐条可数的，
   * 不是「什么路径都转发」。
   *
   * @param specAvailable 活着的 spec 里是否真的有这条（false = spec 读不到，只能用代码登记表）
   * @param specBasis 这一格的依据（spec 读不到时写明原因）
   */
  public record ApiCallMonitorEndpoint(
      String code,
      String method,
      String path,
      String label,
      String description,
      String resourceCode,
      String actionCode,
      boolean specAvailable,
      String specBasis) {}

  /** 筛选项 + 阈值口径 + 网关端点清单。 */
  public record ApiCallMonitorOptions(
      List<ApiCallMonitorOption> windows,
      List<ApiCallMonitorThreshold> thresholds,
      List<ApiCallMonitorEndpoint> endpoints) {}

  // ================================================================ 概览

  /** 窗口内的总体指标。 */
  public record ApiCallMonitorOverview(
      long totalCalls,
      long errorCalls,
      long rateLimitedCalls,
      long slowCalls,
      BigDecimal errorRate,
      BigDecimal qps,
      BigDecimal avgDurationMs,
      BigDecimal maxDurationMs,
      BigDecimal p95DurationMs,
      long appCount,
      long endpointCount,
      long openAlertCount,
      int windowSeconds,
      OffsetDateTime windowStartedAt,
      OffsetDateTime windowEndedAt) {}

  // ================================================================ 趋势

  /** 趋势上的一个桶。 */
  public record ApiCallMonitorTrendPoint(
      OffsetDateTime bucket,
      long calls,
      long errors,
      long rateLimitedCalls,
      BigDecimal errorRate,
      BigDecimal qps,
      BigDecimal avgDurationMs,
      BigDecimal p95DurationMs) {}

  /** 趋势（按分钟或按小时分桶）。 */
  public record ApiCallMonitorTrend(
      List<ApiCallMonitorTrendPoint> points,
      String bucket,
      int windowSeconds,
      OffsetDateTime windowStartedAt,
      OffsetDateTime windowEndedAt) {}

  // ================================================================ 应用维度

  /** 按应用维度的一行。 */
  public record ApiCallMonitorAppRow(
      UUID appId,
      String appCode,
      String appName,
      long totalCalls,
      long errorCalls,
      long rateLimitedCalls,
      long slowCalls,
      BigDecimal errorRate,
      BigDecimal qps,
      BigDecimal avgDurationMs,
      BigDecimal p95DurationMs,
      OffsetDateTime lastCalledAt) {}

  // ================================================================ 接口维度

  /** 按接口维度（端点码）的一行。 */
  public record ApiCallMonitorEndpointRow(
      String endpointCode,
      String httpMethod,
      String requestPath,
      String label,
      long totalCalls,
      long errorCalls,
      long rateLimitedCalls,
      long slowCalls,
      BigDecimal errorRate,
      BigDecimal qps,
      BigDecimal avgDurationMs,
      BigDecimal p95DurationMs,
      OffsetDateTime lastCalledAt) {}

  // ================================================================ 慢调用

  /** 一次慢调用（Top N 里的一行）。 */
  public record ApiCallMonitorSlowCall(
      UUID id,
      UUID appId,
      String appCode,
      String endpointCode,
      String httpMethod,
      String requestPath,
      int statusCode,
      String outcome,
      BigDecimal durationMs,
      String clientIp,
      OffsetDateTime calledAt) {}

  // ================================================================ 调用明细

  /** 调用明细的一行。 */
  public record ApiCallMonitorCallRow(
      UUID id,
      UUID appId,
      String appCode,
      String endpointCode,
      String httpMethod,
      String requestPath,
      int statusCode,
      String outcome,
      String outcomeLabel,
      String errorCode,
      boolean rateLimited,
      BigDecimal durationMs,
      String clientIp,
      String requestId,
      OffsetDateTime calledAt) {}

  /** 调用明细（真分页 + 真筛选）。 */
  public record ApiCallMonitorCallPage(
      List<ApiCallMonitorCallRow> items,
      int page,
      int size,
      long total,
      long errorCalls,
      long rateLimitedCalls) {}

  // ================================================================ 告警

  /** 告警的一行。 */
  public record ApiCallMonitorAlertRow(
      UUID id,
      UUID appId,
      String appCode,
      String alertType,
      String alertTypeLabel,
      String severity,
      String status,
      String endpointCode,
      BigDecimal metricValue,
      BigDecimal thresholdValue,
      int windowSeconds,
      OffsetDateTime windowStartedAt,
      OffsetDateTime windowEndedAt,
      String message,
      String suggestedAction,
      String suggestedQuotaWindow,
      BigDecimal suggestedQuotaLimit,
      String quotaAdjustTarget,
      OffsetDateTime acknowledgedAt,
      UUID acknowledgedBy,
      OffsetDateTime resolvedAt,
      OffsetDateTime createdAt) {}

  /** 告警列表（真分页 + 真筛选）。 */
  public record ApiCallMonitorAlertPage(
      List<ApiCallMonitorAlertRow> items,
      int page,
      int size,
      long total,
      long openCount,
      long acknowledgedCount,
      long resolvedCount) {}

  // ================================================================ 扫描

  /** 一次告警扫描的入参（窗口秒数；不传用平台默认口径）。 */
  public record ApiCallMonitorScanInput(
      @Schema(nullable = true, description = "判定窗口秒数，不传用平台默认 900 秒") Integer windowSeconds) {}

  /** 一次告警扫描的结果。 */
  public record ApiCallMonitorScanResult(
      int windowSeconds,
      OffsetDateTime windowStartedAt,
      OffsetDateTime windowEndedAt,
      int appsScanned,
      int alertsRaised,
      int alertsRefreshed,
      int alertsResolved,
      int endpointsScanned) {}

  // ================================================================ 网关响应

  /** 网关回声端点的入参。 */
  public record ApiGatewayEchoInput(
      @Schema(nullable = true, description = "要回显的内容；不传则回显空串") String message) {}

  /** 网关 ping 的回显体。 */
  public record ApiGatewayPingView(
      String appCode, String appName, int credentialVersion, String apiKeyPrefix) {}

  /** 网关回显的一个配额窗口。 */
  public record ApiGatewayQuotaWindow(
      String windowType,
      String label,
      boolean configured,
      boolean enabled,
      BigDecimal limitCount,
      BigDecimal usedCount,
      BigDecimal remaining,
      boolean exceeded,
      OffsetDateTime windowStartedAt,
      OffsetDateTime windowEndedAt) {}

  /** 网关回显的配额视图。 */
  public record ApiGatewayQuotaView(List<ApiGatewayQuotaWindow> windows) {}

  /** 网关回显的授权范围视图。 */
  public record ApiGatewayScopeView(List<String> scopes) {}

  /** 网关回显的凭证视图（**没有明文**）。 */
  public record ApiGatewayCredentialView(
      int version,
      String status,
      String statusLabel,
      String apiKeyPrefix,
      String secretHint,
      OffsetDateTime issuedAt,
      OffsetDateTime expiresAt,
      OffsetDateTime lastUsedAt,
      OffsetDateTime revokedAt) {}

  /** 网关回显的一次调用。 */
  public record ApiGatewayCallView(
      String requestId,
      String endpointCode,
      String httpMethod,
      String requestPath,
      int statusCode,
      String outcome,
      String outcomeLabel,
      String errorCode,
      boolean rateLimited,
      BigDecimal durationMs,
      OffsetDateTime calledAt) {}

  /** 网关回显的调用列表。 */
  public record ApiGatewayCallList(List<ApiGatewayCallView> items) {}

  /**
   * 网关的一次成功响应。
   *
   * @param requestId 请求号（对端报障时能对上）
   */
  public record ApiGatewayResponse(
      String endpoint,
      String requestId,
      String appCode,
      OffsetDateTime serverTime,
      @Schema(nullable = true) Object data) {}

  /** 网关的一次失败响应（真实状态码 + 可读的失败码）。 */
  public record ApiGatewayError(String error, String message, String requestId) {}
}
