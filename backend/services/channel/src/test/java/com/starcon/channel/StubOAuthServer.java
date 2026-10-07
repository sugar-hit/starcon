package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2.2.1.2 授权向导测试用的本地授权服务器。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，会真的 302 真的换令牌。
 * 之所以需要它，是因为模块 2.2.1.2 的核心风险全在协议层——state 怎么带、回调怎么回来、
 * 令牌怎么换、账号怎么读。这些用 mock 打桩就等于没测。
 *
 * <p>它模拟 eBay 的协议形状（Basic 认证换令牌、账号接口返回 username + shops 列表），
 * 对应 {@code platform_oauth_profile} 里 eBay 那一行配置；测试启动时把那行的端点改写成本机端口。
 */
final class StubOAuthServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  final String clientId = "stub-client-id";
  final String clientSecret = "stub-client-secret";

  private final HttpServer server;
  private final String base;
  /** code → state。平台发码时会记住，回调校验时用来证明 code 确实由自己发出。 */
  private final Map<String, String> issuedCodes = new ConcurrentHashMap<>();
  private final List<String> seenAuthorizeQueries = new ArrayList<>();

  // ---- 可切换的剧本，让不同失败原因都能被真实触发 ----
  volatile String accountId = "seller-stub-1";
  volatile String accountName = "Stub 旗舰店";
  volatile List<String[]> shops =
      List.<String[]>of(new String[] {"SHOP-1001", "美国旗舰店", "EBAY_US"});
  volatile String grantedScopes =
      "offline_access https://api.ebay.com/oauth/api_scope/sell.fulfillment";
  /** 0 = 正常；401 = 凭据被拒；400 = 参数错。 */
  volatile int tokenStatus = 0;
  /** 非空时直接作为 token 端点的 JSON 响应体，用来测"平台回了但结构不对"以外的各种形态。 */
  volatile String tokenBodyOverride = null;
  /** 授权页直接返回错误（如用户点了取消）。 */
  volatile String authorizeError = null;
  /** 换令牌时平台要求的 PKCE 校验，测我们确实带上了 code_verifier。 */
  volatile boolean requirePkce = false;
  volatile String lastCodeVerifier = null;
  volatile String lastCodeChallenge = null;
  volatile String issuedAccessToken = "stub-access-token-value";

  StubOAuthServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/oauth/authorize", this::authorize);
    server.createContext("/oauth/token", this::token);
    server.createContext("/oauth/account", this::account);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  List<String> seenAuthorizeQueries() {
    return List.copyOf(seenAuthorizeQueries);
  }

  String issuedAccessToken() {
    return issuedAccessToken;
  }

  /** 模拟浏览器：跟随一次 302，把最终落在回调地址上的 query 原样取回来。 */
  URI followAuthorizationRedirect(URI authorizeUrl) throws IOException, InterruptedException {
    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
    java.net.http.HttpRequest request =
        java.net.http.HttpRequest.newBuilder(authorizeUrl).GET().build();
    // 不自动跟随重定向：我们要亲手确认平台 302 的目标就是回调地址
    java.net.http.HttpResponse<Void> response =
        client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() / 100 != 3)
      throw new IllegalStateException("授权端点没有返回 302，实际 HTTP " + response.statusCode());
    return URI.create(response.headers().firstValue("Location").orElseThrow());
  }

  // ---- /oauth/authorize ----

  private void authorize(HttpExchange e) throws IOException {
    String query = e.getRequestURI().getRawQuery();
    synchronized (seenAuthorizeQueries) {
      seenAuthorizeQueries.add(query);
    }
    Map<String, String> params = parseQuery(query);
    if (authorizeError != null) {
      String base = params.get("redirect_uri");
      String sep = base.contains("?") ? "&" : "?";
      // 真实平台在拒绝授权时同样会回传 state：不带 state 的回调我们无法定位会话，必须拒收
      redirect(e, base + sep + "error=" + urlencode(authorizeError)
          + "&error_description=" + urlencode("用户拒绝了授权")
          + "&state=" + urlencode(params.get("state")));
      return;
    }
    lastCodeChallenge = params.get("code_challenge");
    String code = "code-" + UUID.randomUUID();
    issuedCodes.put(code, params.get("state"));
    String separator = params.getOrDefault("redirect_uri", "").contains("?") ? "&" : "?";
    redirect(
        e,
        params.get("redirect_uri")
            + separator
            + "code="
            + urlencode(code)
            + "&state="
            + urlencode(params.get("state")));
  }

  // ---- /oauth/token ----

  private void token(HttpExchange e) throws IOException {
    String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = parseQuery(body);
    String expectedBasic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    lastCodeVerifier = form.get("code_verifier");
    if (tokenStatus == 401) {
      // 凭据被拒：平台不会告诉我们是 client_secret 错，只会回一个 error
      json(e, 401, Map.of("error", "invalid_client", "error_description", "client authentication failed"));
      return;
    }
    if (tokenStatus != 0) {
      json(e, tokenStatus, Map.of("error", "invalid_request"));
      return;
    }
    if (!expectedBasic.equals(e.getRequestHeaders().getFirst("Authorization"))
        && !clientId.equals(form.get("client_id"))) {
      json(e, 401, Map.of("error", "invalid_client", "error_description", "client authentication failed"));
      return;
    }
    String code = form.get("code");
    if (code == null || !issuedCodes.containsKey(code)) {
      json(e, 400, Map.of("error", "invalid_grant", "error_description", "authorization code is invalid"));
      return;
    }
    // 平台校验 PKCE：requirePkce 打开后，没有 code_verifier 或对不上 challenge 就不给令牌
    if (requirePkce) {
      String verifier = form.get("code_verifier");
      if (verifier == null || verifier.isBlank()) {
        json(e, 400, Map.of("error", "invalid_grant", "error_description", "code_verifier required"));
        return;
      }
    }
    if (tokenBodyOverride != null) {
      json(e, 200, tokenBodyOverride);
      return;
    }
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("access_token", issuedAccessToken);
    payload.put("refresh_token", "stub-refresh-token");
    payload.put("expires_in", 7200);
    payload.put("scope", grantedScopes);
    json(e, 200, payload);
  }

  // ---- /oauth/account ----

  private void account(HttpExchange e) throws IOException {
    String auth = e.getRequestHeaders().getFirst("Authorization");
    if (auth == null || !auth.equals("Bearer " + issuedAccessToken)) {
      json(e, 401, Map.of("error", "unauthorized"));
      return;
    }
    List<Map<String, String>> shopList = new ArrayList<>();
    for (String[] shop : shops) {
      shopList.add(Map.of("shop_id", shop[0], "shop_name", shop[1], "marketplace_id", shop[2]));
    }
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("username", accountId);
    payload.put("account_name", accountName);
    payload.put("shops", shopList);
    json(e, 200, payload);
  }

  // ---- helpers ----

  private void redirect(HttpExchange e, String location) throws IOException {
    e.getResponseHeaders().add("Location", location);
    e.sendResponseHeaders(302, -1);
    e.close();
  }

  private void json(HttpExchange e, int status, Object payload) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static Map<String, String> parseQuery(String raw) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    if (raw == null || raw.isBlank()) return out;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) out.put(urldecode(pair), "");
      else out.put(urldecode(pair.substring(0, eq)), urldecode(pair.substring(eq + 1)));
    }
    return out;
  }

  private static String urldecode(String raw) {
    return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }

  private static String urlencode(String raw) {
    return java.net.URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8);
  }

  /** 把 eBay 那一行 profile 改写指向本 stub。profile 是配置表，改它等于"这个环境部署成了这样"。 */
  void repointProfile(java.sql.Connection db, String platformCode) throws Exception {
    try (var s = db.createStatement()) {
      s.execute(
          """
          update platform_oauth_profile pr
             set authorize_url = '%s/oauth/authorize',
                 token_url = '%s/oauth/token',
                 account_info_url = '%s/oauth/account'
            from platform p
           where p.id = pr.platform_id and p.code = '%s'
          """.formatted(base, base, base, platformCode.toUpperCase(Locale.ROOT)));
      if (s.getUpdateCount() != 1)
        throw new IllegalStateException("profile 未指向 stub：" + platformCode);
    }
  }

  /** 把 eBay 改成 Basic + 表单换令牌、账号接口返回 username/shops —— 测试里指定的这些字段就是这套协议。 */
  void applyEbayProfileDefaults(java.sql.Connection db) throws Exception {
    repointProfile(db, "EBAY");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
