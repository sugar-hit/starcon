package com.starcon.platform;

import java.util.List;

/**
 * 18.1.1.3「网关入口」的**可枚举**端点清单。
 *
 * <p>🔴 <b>这份清单就是网关放行的全部</b> —— 封闭、逐条可数、写在代码里。
 * 其余任何路径一律 404。
 *
 * <p>为什么刻意不放行「任意路径」：那既是安全洞（等于一个开放代理，能探测内网），
 * 也和 18.1.1.2 刻意不放行 spec 代理的判断相悖（放行后端不存在的映射比 404 更糟）。
 *
 * <p>七个端点分两类：
 *
 * <ul>
 *   <li><b>对接方自检</b>（ping / quota / scopes / credential）——
 *       凭证对不对、配额还剩多少、被授了什么范围、这一版凭证是什么状态。
 *   <li><b>对接方自诊</b>（echo / calls / call-detail）——
 *       蓝图原话「外部系统调用错误要能被快速定位，否则对接方只会说"你们接口有问题"」：
 *       对接方自己就能看到「我刚才那次调用被拒了，状态码 429，错误码 QUOTA_EXCEEDED」，
 *       不必先找我们、再等我们去查。
 * </ul>
 *
 * <p>⚠️ 这份清单同时是<b>监控的「接口维度」词表</b>：调用埋点里的 {@code endpoint_code}
 * 取自这里，聚合维度因此是封闭可数的 —— 不会出现「一个从没听说过的接口名」。
 */
public final class ApiGatewayEndpoints {

  private ApiGatewayEndpoints() {}

  /** 网关端点所需的授权资源码（外部调用方的 scope 维度，与平台内部权限码不是一套）。 */
  public static final String RESOURCE_CODE = "platform.integration";

  /** 一个网关端点。 */
  public record Endpoint(
      String code,
      String method,
      String path,
      String label,
      String description,
      String resourceCode,
      String actionCode) {}

  /** 连通性自检：应用身份回显 + 服务端时刻。 */
  public static final Endpoint PING =
      new Endpoint(
          "PING",
          "GET",
          "/open/v1/ping",
          "连通性自检",
          "回显应用身份与服务端时刻。用来确认「网络通了、凭证对了、租户认得」。",
          RESOURCE_CODE,
          "READ");

  /** 配额状态自检：各窗口的上限 / 已用 / 剩余。 */
  public static final Endpoint QUOTA =
      new Endpoint(
          "QUOTA",
          "GET",
          "/open/v1/quota",
          "配额状态自检",
          "回显本应用各窗口的配额上限、已用量与剩余额度。被限流时对接方自己就能看到。",
          RESOURCE_CODE,
          "READ");

  /** 授权范围自检：外部调用方被授了哪些资源 × 操作。 */
  public static final Endpoint SCOPES =
      new Endpoint(
          "SCOPES",
          "GET",
          "/open/v1/scopes",
          "授权范围自检",
          "回显本应用被授予的资源与操作。",
          RESOURCE_CODE,
          "READ");

  /** 凭证状态自检：当前这一版凭证的状态。 */
  public static final Endpoint CREDENTIAL =
      new Endpoint(
          "CREDENTIAL",
          "GET",
          "/open/v1/credential",
          "凭证状态自检",
          "回显当前凭证的版本、状态、签发时刻与最后使用时刻（**没有明文**）。",
          RESOURCE_CODE,
          "READ");

  /** 写路径回声：POST 同样被计量、限流与审计。 */
  public static final Endpoint ECHO =
      new Endpoint(
          "ECHO",
          "POST",
          "/open/v1/echo",
          "写路径回声自检",
          "回显请求体，用来确认写路径同样被计量、限流与审计。",
          RESOURCE_CODE,
          "WRITE");

  /** 调用明细自诊：对接方自己看自己的最近调用。 */
  public static final Endpoint CALLS =
      new Endpoint(
          "CALLS",
          "GET",
          "/open/v1/calls",
          "调用明细自诊",
          "回显本应用最近的调用记录（状态码、结论码、错误码、耗时、请求号）。"
              + "对接方自己就能回答「刚才那次为什么被拒」。",
          RESOURCE_CODE,
          "READ");

  /** 单次调用自诊：按请求号定位到那一次调用。 */
  public static final Endpoint CALL_DETAIL =
      new Endpoint(
          "CALL_DETAIL",
          "GET",
          "/open/v1/calls/{requestId}",
          "单次调用自诊",
          "按请求号回显那一次调用的明细。请求号在每次响应里都会回给调用方。",
          RESOURCE_CODE,
          "READ");

  /** 全部端点（放行清单）。 */
  public static final List<Endpoint> ALL =
      List.of(PING, QUOTA, SCOPES, CREDENTIAL, ECHO, CALLS, CALL_DETAIL);

  /** 按码取端点。 */
  public static Endpoint byCode(String code) {
    for (var endpoint : ALL) {
      if (endpoint.code().equals(code)) return endpoint;
    }
    return null;
  }

  /**
   * 按「方法 + 路径」取端点。
   *
   * <p>🔴 路径里的 {@code {requestId}} 是**路径参数**：匹配时按「段数相同 + 段形状」判，
   * 不是按字符串相等。所以 {@code /open/v1/calls/{requestId}} 能匹配
   * {@code /open/v1/calls/abc123}，但**不会**匹配 {@code /open/v1/calls}（段数不同）
   * 或 {@code /open/v1/calls/abc123/extra}（多一段）。
   */
  public static Endpoint byPath(String method, String path) {
    if (method == null || path == null) return null;
    var normalizedMethod = method.toUpperCase(java.util.Locale.ROOT);
    for (var endpoint : ALL) {
      if (!endpoint.method().equals(normalizedMethod)) continue;
      if (matchesPath(endpoint.path(), path)) return endpoint;
    }
    return null;
  }

  /** 端点的路径形状能否匹配上这一条实际路径。 */
  private static boolean matchesPath(String shape, String path) {
    var shapeParts = shape.split("/");
    var pathParts = path.split("/");
    if (shapeParts.length != pathParts.length) return false;
    for (int i = 0; i < shapeParts.length; i++) {
      var expected = shapeParts[i];
      var actual = pathParts[i];
      if (expected.startsWith("{") && expected.endsWith("}")) {
        // 路径参数：必须**非空**（`/open/v1/calls/` 不算命中）。
        if (actual.isBlank()) return false;
        continue;
      }
      if (!expected.equals(actual)) return false;
    }
    return true;
  }
}
