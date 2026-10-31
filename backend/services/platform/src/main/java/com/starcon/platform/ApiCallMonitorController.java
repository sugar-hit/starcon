package com.starcon.platform;

import static com.starcon.platform.ApiCallMonitorModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.1.3「调用监控」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/call-monitor}</b>：与同服务的兄弟模块（{@code /platform/apps} /
 * {@code /platform/api-catalog} / {@code /platform/webhooks} / {@code /platform/text} /
 * {@code /platform/finance-software}）刻意不共用首段 —— 前端的 api-proxy 白名单也按首 token 天然互斥。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.call-monitor.read / .update}：
 * 「确认 / 闭环告警」是<b>独立</b>的一个码 —— 能看告警的人不该顺手把告警关掉，
 * 闭环意味着「这件事处理完了」，门槛要真的拦得住人。
 *
 * <p>⚠️ <b>网关入口（{@code /open/v1/**}）刻意不在这里</b>：那是给外部系统调的路径，
 * 租户来自 API Key 反查，与这里的会话租户是两套口径，混在一起就会出现
 * 「一个端点有两种租户口径」。见 {@link ApiGatewayController}。
 */
@RestController
@RequestMapping("/platform/call-monitor")
public class ApiCallMonitorController {

  private final AccessService access;
  private final ApiCallMonitorService monitor;

  public ApiCallMonitorController(AccessService access, ApiCallMonitorService monitor) {
    this.access = access;
    this.monitor = monitor;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.call-monitor." + permission);
    return access.run(actor, "platform.call-monitor." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与选项

  /** 能力声明：哪些真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明",
      description =
          "逐维说清「有没有、依据是什么、缺了归谁」。"
              + "「工作台告警推送」如实答不可用 —— 卷 01 的工作台告警服务至今未交付。")
  public ApiCallMonitorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> monitor.capabilities());
  }

  /** 筛选项 + 阈值口径 + 网关端点清单（接口维度的词表）。 */
  @GetMapping("/options")
  @Operation(
      summary = "筛选项、阈值口径与网关端点清单",
      description =
          "告警阈值是平台固定口径（不替用户预置任何一行配置），"
              + "但必须显式给出来 —— 运营看到告警要知道是按什么判的。"
              + "端点的名称与说明取自 platform 服务活着的 OpenAPI 契约。")
  public ApiCallMonitorOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> monitor.options());
  }

  // ================================================================ 概览与趋势

  /** 概览：几个数（QPS / 错误率 / 限流命中 / 慢调用 / 未闭环告警）。 */
  @GetMapping("/overview")
  @Operation(summary = "概览", description = "窗口内的调用总数、错误数、限流命中数、慢调用数与未闭环告警数。")
  public ApiCallMonitorOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900")
          @Parameter(description = "统计窗口秒数（300 / 900 / 3600 / 86400；其它值按 900 处理）")
          int windowSeconds) {
    return call(auth, "read", actor -> monitor.overview(windowSeconds));
  }

  /** 趋势（按分钟或按小时分桶）。 */
  @GetMapping("/trend")
  @Operation(summary = "趋势", description = "按分钟或按小时分桶的调用量、错误率、限流命中与耗时。")
  public ApiCallMonitorTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900") int windowSeconds,
      @RequestParam(defaultValue = "MINUTE")
          @Parameter(description = "分桶粒度：MINUTE 或 HOUR")
          String bucket) {
    return call(auth, "read", actor -> monitor.trend(windowSeconds, bucket));
  }

  // ================================================================ 维度分析

  /** 按应用维度分析。 */
  @GetMapping("/apps")
  @Operation(summary = "按应用维度分析", description = "每个应用在窗口内的调用量、错误率、限流命中与耗时。")
  public List<ApiCallMonitorAppRow> apps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900") int windowSeconds) {
    return call(auth, "read", actor -> monitor.apps(windowSeconds));
  }

  /** 按接口维度分析。 */
  @GetMapping("/endpoints")
  @Operation(
      summary = "按接口维度分析",
      description = "每个端点在窗口内的调用量、错误率、限流命中与耗时；名称取自活着的契约。")
  public List<ApiCallMonitorEndpointRow> endpoints(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900") int windowSeconds) {
    return call(auth, "read", actor -> monitor.endpoints(windowSeconds));
  }

  /** 慢调用 Top N。 */
  @GetMapping("/slow-calls")
  @Operation(summary = "慢调用 Top N", description = "耗时超过慢调用线的调用，按耗时倒序。")
  public List<ApiCallMonitorSlowCall> slowCalls(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900") int windowSeconds,
      @RequestParam(defaultValue = "20") int limit) {
    return call(auth, "read", actor -> monitor.slowCalls(windowSeconds, limit));
  }

  // ================================================================ 调用明细

  /** 调用明细（真分页 + 真筛选）。 */
  @GetMapping("/calls")
  @Operation(
      summary = "调用明细",
      description =
          "按应用 / 接口 / 结论码 / 是否限流命中筛选；分页是真的（总数与错误数、限流命中数一并给出）。")
  public ApiCallMonitorCallPage calls(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "900") int windowSeconds,
      @RequestParam(required = false) UUID appId,
      @RequestParam(required = false) String endpointCode,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) Boolean rateLimited,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> monitor.calls(windowSeconds, appId, endpointCode, outcome, rateLimited, page, size));
  }

  // ================================================================ 告警

  /** 告警列表（真分页 + 真筛选）。 */
  @GetMapping("/alerts")
  @Operation(
      summary = "告警列表",
      description = "按状态 / 类型 / 应用筛选；三种状态各自的条数一并给出。")
  public ApiCallMonitorAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) UUID appId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> monitor.alerts(status, alertType, appId, page, size));
  }

  /** 扫一遍并开告警。 */
  @PostMapping("/alerts/scan")
  @Operation(
      summary = "扫描并开告警",
      description =
          "按平台固定口径判定三类异常（错误率 / 慢调用 p95 / 限流命中）；"
              + "未闭环期间同一异常只一行，重复扫描只刷新指标；指标回落后自动闭环。")
  public ApiCallMonitorScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) ApiCallMonitorScanInput input) {
    var seconds = input == null || input.windowSeconds() == null
        ? ApiCallMonitorService.DEFAULT_WINDOW_SECONDS
        : input.windowSeconds();
    return call(auth, "update", actor -> monitor.scan(seconds));
  }

  /** 确认一条告警。 */
  @PostMapping("/alerts/{alertId}/acknowledge")
  @Operation(summary = "确认告警", description = "把告警标为「已确认」。已确认的再确认一次不会报错。")
  public ApiCallMonitorAlertRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId) {
    return call(auth, "update", actor -> monitor.acknowledge(alertId, actor));
  }

  /** 闭环一条告警。 */
  @PostMapping("/alerts/{alertId}/resolve")
  @Operation(summary = "闭环告警", description = "把告警标为「已闭环」。已闭环的再闭环一次不会报错。")
  public ApiCallMonitorAlertRow resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId) {
    return call(auth, "update", actor -> monitor.resolve(alertId, actor));
  }
}
