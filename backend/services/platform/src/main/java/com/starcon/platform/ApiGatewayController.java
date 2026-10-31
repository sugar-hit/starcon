package com.starcon.platform;

import com.starcon.platform.ApiCallMonitorModels.ApiGatewayError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.1.3 的<b>上游</b>：API 网关入口（命名空间 {@code /open/v1/**}）。
 *
 * <p>蓝图写死「<b>上游 = API 网关</b>」，而本仓今天没有网关 —— 本车道把它补上，
 * 因为不补就没有调用埋点，没有埋点，QPS / 错误率 / 限流命中 / 慢调用四维就只能是编出来的。
 *
 * <p><b>🔴 这个命名空间与平台内部端点刻意分开</b>：
 *
 * <ul>
 *   <li>{@code /platform/**} —— 浏览器路径：租户来自**已认证会话**（AccessService 向 identity 确认），
 *       鉴权用平台权限码。
 *   <li>{@code /open/v1/**} —— 外部系统路径：租户来自**API Key 反查**，鉴权用 18.1.1.1 的凭证 /
 *       授权范围 / IP 白名单 / 配额。
 * </ul>
 *
 * 两条路径的租户来源不同，混在一起就会出现「一个端点有两种租户口径」的问题 ——
 * 那是本仓最不愿意出现的形状。
 *
 * <p><b>🔴 不进浏览器的 api-proxy 白名单</b>：网关是给**外部系统**调的，
 * 放行它等于让任意浏览器带着别人的 Key 来打。浏览器的调用监控页走 {@code /platform/call-monitor/**}。
 *
 * <p><b>🔴 放行清单是封闭的</b>（见 {@link ApiGatewayEndpoints}）：清单之外的路径一律 404，
 * 这里**不做转发**——放行后端不存在的映射比 404 更糟。
 */
@RestController
@RequestMapping("/open/v1")
public class ApiGatewayController {

  private final ApiGatewayService gateway;

  public ApiGatewayController(ApiGatewayService gateway) {
    this.gateway = gateway;
  }

  // ================================================================ 端点

  /** 连通性自检：回显应用身份与服务端时刻。 */
  @GetMapping("/ping")
  @Operation(
      summary = "连通性自检",
      description =
          "回显应用身份与服务端时刻。用来确认「网络通了、凭证对了、租户认得」。")
  public ResponseEntity<Object> ping(
      @RequestHeader(value = "X-Api-Key", defaultValue = "")
          @Parameter(description = "应用 API Key（18.1.1.1 签发的那一版）")
          String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "")
          @Parameter(description = "应用 API Secret")
          String secret,
      @RequestHeader(value = "X-Request-Id", required = false)
          @Parameter(description = "调用方自带的请求号；不传由网关生成，回调时原样带回")
          String requestId,
      HttpServletRequest request) {
    return dispatch(
        ApiGatewayEndpoints.PING,
        request,
        apiKey,
        secret,
        requestId,
        session -> gateway.pingView(session.identity()));
  }

  /** 配额状态自检：各窗口的上限 / 已用 / 剩余。 */
  @GetMapping("/quota")
  @Operation(
      summary = "配额状态自检",
      description =
          "回显本应用各窗口的配额上限、已用量与剩余额度。没配过的窗口显示「未配置 = 不限」，"
              + "不是「限 0 次」。")
  public ResponseEntity<Object> quota(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      HttpServletRequest request) {
    return dispatch(
        ApiGatewayEndpoints.QUOTA,
        request,
        apiKey,
        secret,
        requestId,
        // 🔴 配额视图按**网关自己的时钟**算窗口起点（与判限流时同一个）——
        //    否则对接方看到的「还剩几次」与实际被挡的那个窗口不是同一个。
        session -> gateway.quotaView(session.identity().appId()));
  }

  /** 授权范围自检：外部调用方被授了哪些资源 × 操作。 */
  @GetMapping("/scopes")
  @Operation(summary = "授权范围自检", description = "回显本应用被授予的资源与操作。")
  public ResponseEntity<Object> scopes(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      HttpServletRequest request) {
    return dispatch(
        ApiGatewayEndpoints.SCOPES,
        request,
        apiKey,
        secret,
        requestId,
        session -> gateway.scopeView(session.identity().appId()));
  }

  /** 凭证状态自检：当前这一版凭证的状态（**没有明文**）。 */
  @GetMapping("/credential")
  @Operation(
      summary = "凭证状态自检",
      description = "回显当前凭证的版本、状态、签发时刻与最后使用时刻。没有明文，库里也没有。")
  public ResponseEntity<Object> credential(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      HttpServletRequest request) {
    return dispatch(
        ApiGatewayEndpoints.CREDENTIAL,
        request,
        apiKey,
        secret,
        requestId,
        session -> gateway.credentialView(session.identity().credentialId()));
  }

  /** 写路径回声：POST 同样被计量、限流与审计。 */
  @PostMapping("/echo")
  @Operation(
      summary = "写路径回声自检",
      description = "回显请求体，用来确认写路径同样被计量、限流与审计。")
  public ResponseEntity<Object> echo(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      @RequestBody(required = false) ApiCallMonitorModels.ApiGatewayEchoInput input,
      HttpServletRequest request) {
    var message = input == null || input.message() == null ? "" : input.message();
    return dispatch(
        ApiGatewayEndpoints.ECHO,
        request,
        apiKey,
        secret,
        requestId,
        session ->
            Map.of(
                "message", message,
                "endpointCode", session.endpointCode(),
                "receivedAt", gateway.now().toString()));
  }

  /** 调用明细自诊：对接方自己看自己的最近调用。 */
  @GetMapping("/calls")
  @Operation(
      summary = "调用明细自诊",
      description =
          "回显本应用最近的调用记录（状态码、结论码、错误码、耗时、请求号）。"
              + "对接方自己就能回答「刚才那次为什么被拒」。只看得到本应用的调用。")
  public ResponseEntity<Object> calls(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
      @RequestHeader(value = "X-Call-Limit", required = false)
          @Parameter(description = "回显条数（默认 50，最多 200）")
          String limit,
      HttpServletRequest request) {
    var size = parseLimit(limit);
    return dispatch(
        ApiGatewayEndpoints.CALLS,
        request,
        apiKey,
        secret,
        requestId,
        session -> gateway.recentCalls(session.identity().appId(), size));
  }

  /** 单次调用自诊：按请求号定位到那一次调用。 */
  @GetMapping("/calls/{requestId}")
  @Operation(
      summary = "单次调用自诊",
      description = "按请求号回显那一次调用的明细。请求号在每次响应里都会回给调用方。")
  public ResponseEntity<Object> callDetail(
      @RequestHeader(value = "X-Api-Key", defaultValue = "") String apiKey,
      @RequestHeader(value = "X-Api-Secret", defaultValue = "") String secret,
      @RequestHeader(value = "X-Request-Id", required = false) String callerRequestId,
      @PathVariable String requestId,
      HttpServletRequest request) {
    return dispatch(
        ApiGatewayEndpoints.CALL_DETAIL,
        request,
        apiKey,
        secret,
        callerRequestId,
        session -> {
          var call = gateway.callByRequestId(session.identity().appId(), requestId);
          if (call == null) {
            throw new ApiGatewayService.NotFound("本应用没有这个请求号的调用记录");
          }
          return call;
        });
  }

  // ================================================================ 清单之外的路径

  /**
   * 放行清单之外的路径一律 404。
   *
   * <p>🔴 **不做转发**：把未知路径转给别处等于把网关变成开放代理（能探测内网）。
   * 这一格**不落埋点** —— 路径都没进清单，就没有接口维度可言，也没有租户可归属。
   */
  @RequestMapping(
      value = "/**",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.DELETE,
        RequestMethod.PATCH,
        RequestMethod.HEAD,
        RequestMethod.OPTIONS
      })
  @Operation(
      summary = "网关放行清单之外的路径",
      description = "一律 404。网关只放行 ApiGatewayEndpoints 里逐条列出的端点，不做路径转发。")
  public ResponseEntity<Object> notInAllowlist(
      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
    var id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    return ResponseEntity.status(404)
        .body(new ApiGatewayError("ENDPOINT_NOT_FOUND", "网关不提供该路径（放行清单是封闭的）", id));
  }

  // ================================================================ 内部

  /** 一次调用的上下文（从请求里取出来的原始事实）。 */
  private record Call(
      String method, String path, String apiKey, String secret, String clientIp, String requestId) {}

  /** 统一入口：解析调用上下文 → 交给服务层走「定位 → 四道判定 → 执行 → 埋点」。 */
  private ResponseEntity<Object> dispatch(
      ApiGatewayEndpoints.Endpoint endpoint,
      HttpServletRequest request,
      String apiKey,
      String secret,
      String requestId,
      Function<ApiGatewayService.GatewaySession, Object> work) {
    var call =
        new Call(
            request.getMethod(),
            request.getRequestURI(),
            apiKey,
            secret,
            clientIpOf(request),
            normalizeRequestId(requestId));
    var result =
        gateway.invoke(
            endpoint,
            call.method(),
            call.path(),
            call.apiKey(),
            call.secret(),
            call.clientIp(),
            call.requestId(),
            work);
    return ResponseEntity.status(result.statusCode()).body(result.body());
  }

  /** 请求号：调用方没带就生成一个，带了就截到 64 字符（与埋点列宽一致）。 */
  private static String normalizeRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) return UUID.randomUUID().toString();
    var trimmed = requestId.trim();
    return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
  }

  /** 来源 IP：有反向代理时取 {@code X-Forwarded-For} 的第一跳，否则取远端地址。 */
  private static String clientIpOf(HttpServletRequest request) {
    var forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      var first = forwarded.split(",")[0].trim();
      if (!first.isEmpty()) return first.length() > 64 ? first.substring(0, 64) : first;
    }
    var remote = request.getRemoteAddr();
    return remote == null ? "" : remote;
  }

  /** 回显条数（默认 50，最多 200）。 */
  private static int parseLimit(String raw) {
    if (raw == null || raw.isBlank()) return 50;
    try {
      var value = Integer.parseInt(raw.trim());
      if (value <= 0) return 50;
      return Math.min(value, 200);
    } catch (NumberFormatException e) {
      return 50;
    }
  }
}
