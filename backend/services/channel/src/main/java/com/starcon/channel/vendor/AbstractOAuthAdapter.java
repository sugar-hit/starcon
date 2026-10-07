package com.starcon.channel.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.vendor.OAuthProtocol.AccountPayload;
import com.starcon.channel.vendor.OAuthProtocol.Authorization;
import com.starcon.channel.vendor.OAuthProtocol.AuthorizeRequest;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.OAuthProtocol.PlatformShop;
import com.starcon.channel.vendor.OAuthProtocol.TokenRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各 vendor 适配器的共同实现。
 *
 * <p>把"所有平台的 OAuth 大致都长这样"的部分收在这里，把"这个平台特别不一样"的部分留给
 * 子类覆写。这样每个 vendor 包里只留下真正属于它的差异，审阅时能一眼看出该平台的协议形状。
 *
 * <p>这里刻意没有 Spring 注解：适配器是纯协议逻辑，不碰数据库、不碰租户上下文，
 * 由 {@link VendorRegistry} 统一装配。
 */
public abstract class AbstractOAuthAdapter implements PlatformOAuthAdapter {

  protected static final ObjectMapper JSON = new ObjectMapper();
  /** 换令牌与查账号的出网超时；平台不可用时要尽快失败，让向导给出可重试的原因而不是挂住。 */
  protected static final Duration TIMEOUT = Duration.ofSeconds(10);
  protected static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * 换令牌响应解析的中间结果。
   *
   * <p>只在 {@link #authorize} 内部存在，出了这个方法就并进 {@link Authorization}，
   * 不会成为一个可以单独传、单独打日志的对象。
   */
  protected record TokenResult(
      String accessToken, String refreshToken, Instant expiresAt, List<String> grantedScopes) {}

  /** 授权 URL 的通用拼法：按顺序追加查询参数，空值不拼。 */
  protected URI uri(String base, Map<String, String> params) {
    StringBuilder url = new StringBuilder(base);
    char sep = base.contains("?") ? '&' : '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (e.getValue() == null || e.getValue().isBlank()) continue;
      url.append(sep).append(e.getKey()).append('=');
      url.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
      sep = '&';
    }
    return URI.create(url.toString());
  }

  /** 平台配置里登记的静态授权参数（Shopify 的 grant_options、Shopee 的 partner_id 等）。 */
  protected Map<String, String> authorizeParams(OAuthProfile profile, AuthorizeRequest request) {
    Map<String, String> params = new LinkedHashMap<>(profile.extraAuthorizeParams());
    params.put("client_id", request.clientId());
    params.put("redirect_uri", request.redirectUri());
    params.put("state", request.state());
    if (!request.loginHint().isBlank() && !profile.loginHintParam().isBlank())
      params.put(profile.loginHintParam(), request.loginHint());
    if (!profile.accountPromptParam().isBlank() && !profile.accountPromptValue().isBlank())
      params.put(profile.accountPromptParam(), profile.accountPromptValue());
    if (request.codeChallengeMethod() == OAuthProtocol.CodeChallengeMethod.S256) {
      params.put("code_challenge_method", "S256");
      params.put("code_challenge", request.codeChallenge());
    }
    String scopes = joinScopes(request.scopes());
    if (!scopes.isBlank() && !profile.scopeParam().isBlank()) params.put(profile.scopeParam(), scopes);
    return params;
  }

  @Override
  public URI authorizeUri(OAuthProfile profile, AuthorizeRequest request) {
    return uri(profile.authorizeUrl(), authorizeParams(profile, request));
  }

  /** 换令牌的公共请求体字段。子类按平台要求追加或删减。 */
  protected Map<String, String> tokenBody(OAuthProfile profile, TokenRequest request) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("grant_type", "authorization_code");
    body.put("code", request.code());
    body.put("redirect_uri", request.redirectUri());
    if (request.codeVerifier() != null && !request.codeVerifier().isBlank())
      body.put("code_verifier", request.codeVerifier());
    body.putAll(request.extra());
    return body;
  }

  @Override
  public Authorization authorize(OAuthProfile profile, TokenRequest request) {
    Map<String, String> body = tokenBody(profile, request);
    if (request.authMethod() == OAuthProtocol.TokenAuthMethod.CLIENT_SECRET_POST) {
      body.put("client_id", request.clientId());
      body.put("client_secret", request.clientSecret());
    }
    HttpRequest.Builder http =
        HttpRequest.newBuilder(URI.create(request.tokenUrl()))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .POST(
                request.style() == OAuthProtocol.TokenRequestStyle.JSON
                    ? HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.ofString(form(body), StandardCharsets.UTF_8));
    if (request.style() == OAuthProtocol.TokenRequestStyle.JSON)
      http.header("Content-Type", "application/json");
    if (request.authMethod() == OAuthProtocol.TokenAuthMethod.CLIENT_SECRET_BASIC)
      http.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (request.clientId() + ":" + request.clientSecret())
                          .getBytes(StandardCharsets.UTF_8)));
    HttpResponse<String> response;
    try {
      response = HTTP.send(http.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure("NETWORK_ERROR", "无法连接平台授权服务器，请检查网络后重试"));
    }
    // 失败响应里常带 error / error_description，直接归一化成失败原因：
    // 凭据错、授权码过期、redirect_uri 不匹配在平台侧长得都一样，不翻译就只能说"失败"。
    if (response.statusCode() / 100 != 2)
      throw new PlatformException(translateTokenError(profile, response.body(), response.statusCode()));
    String tokenBody = response.body();
    TokenResult token = readToken(profile, tokenBody);
    // 账号信息来源因平台而异：Shopify 随令牌响应一起给，其余平台要另调账号接口
    String accountBody =
        profile.accountInfoUrl().isBlank() ? tokenBody : fetchAccount(profile, token.accessToken());
    AccountPayload account = readAccount(profile, accountBody);
    return new Authorization(
        token.accessToken(),
        token.refreshToken(),
        token.expiresAt(),
        token.grantedScopes(),
        account.accountId(),
        account.accountName(),
        account.shops());
  }

  /** 把平台 token 端点的错误响应翻译成可执行原因。默认按 OAuth2 标准字段解析。 */
  protected OAuthProtocol.OAuthFailure translateTokenError(OAuthProfile profile, String body, int status) {
    String code = "";
    String description = "";
    try {
      JsonNode node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
      JsonNode error = node.get("error");
      if (error != null) code = error.isTextual() ? error.asText() : error.path("code").asText("");
      JsonNode message = node.get("error_description");
      if (message == null) message = node.get("error_message");
      if (message == null) message = node.get("message");
      description = message == null ? "" : message.asText("");
    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
      // 非 JSON 错误页（网关 502 之类）没有结构可解析，用状态码兜底。
    }
    if (description.isBlank() && code.isBlank()) description = "平台返回 HTTP " + status;
    return translatePlatformError(code, description);
  }

  /** 从 token 响应里取令牌；字段名来自配置，各平台命名不同。 */
  protected TokenResult readToken(OAuthProfile profile, String body) {
    JsonNode node = parse(body);
    String access = text(node, profile.tokenAccessField());
    if (access.isBlank())
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure("TOKEN_EXCHANGE_FAILED", "平台未返回访问令牌，请重新发起授权"));
    Instant expiresAt = null;
    String expires = text(node, profile.tokenExpiresField());
    if (!expires.isBlank()) {
      try {
        expiresAt = Instant.now().plusSeconds(Long.parseLong(expires.trim()));
      } catch (NumberFormatException ignored) {
        // 有些平台返回绝对时间戳而不是秒数，解析不了就按"无到期"处理，
        // 2.2.2.2 的提醒对无到期凭据不触发，而不是误报。
      }
    }
    String scope = text(node, profile.tokenScopeField());
    List<String> scopes =
        scope.isBlank() ? List.of() : Arrays.stream(scope.split("[ ,]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return new TokenResult(access, text(node, profile.tokenRefreshField()), expiresAt, scopes);
  }

  /** 解析账号信息；字段名来自配置，嵌套壳由子类处理。 */
  @Override
  public AccountPayload readAccount(OAuthProfile profile, String body) {
    JsonNode node = parse(body);
    String accountId = text(node, profile.accountIdField());
    if (accountId.isBlank()) accountId = accountIdFromShops(node, profile);
    if (accountId.isBlank())
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure("ACCOUNT_LOOKUP_FAILED", "无法从平台响应中识别商家账号，请检查应用权限范围"));
    List<PlatformShop> shops = new ArrayList<>();
    JsonNode array = node.get(profile.shopsField());
    if (array == null) array = node;
    if (array.isArray())
      for (JsonNode item : array) {
        String shopId = text(item, profile.shopIdField());
        if (shopId.isBlank()) continue;
        shops.add(
            new PlatformShop(
                shopId,
                fallback(text(item, profile.shopNameField()), shopId),
                text(item, profile.shopMarketplaceField())));
      }
    return new AccountPayload(accountId, fallback(text(node, profile.accountNameField()), accountId), List.copyOf(shops));
  }

  /** 平台没有独立的账号 ID 字段时，用它下面第一个店铺的 ID 作为账号标识。 */
  protected String accountIdFromShops(JsonNode node, OAuthProfile profile) {
    JsonNode array = node.get(profile.shopsField());
    if (array != null && array.isArray() && !array.isEmpty())
      return text(array.get(0), profile.shopIdField());
    return "";
  }

  /** 带令牌访问平台账号接口。 */
  protected String fetchAccount(OAuthProfile profile, String accessToken) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(profile.accountInfoUrl()))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
    HttpResponse<String> response;
    try {
      response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure("NETWORK_ERROR", "无法连接平台接口读取商家账号，请检查网络后重试"));
    }
    if (response.statusCode() / 100 != 2)
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure(
              "ACCOUNT_LOOKUP_FAILED", "读取平台商家账号失败（HTTP " + response.statusCode() + "），请检查应用权限范围"));
    return response.body();
  }

  /** 平台错误码 → 可执行原因。子类覆写以给出更贴合该平台的文案。 */
  @Override
  public OAuthProtocol.OAuthFailure translatePlatformError(String errorCode, String description) {
    return switch (errorCode == null ? "" : errorCode) {
      case "access_denied" ->
          new OAuthProtocol.OAuthFailure("PLATFORM_REJECTED", "你已取消授权，未选择任何店铺。重新发起即可继续。");
      case "invalid_client" ->
          new OAuthProtocol.OAuthFailure("CREDENTIAL_REJECTED", "平台拒绝了应用凭据（client_id / client_secret 不正确），请联系管理员检查配置。");
      case "unauthorized_client" ->
          new OAuthProtocol.OAuthFailure("INSUFFICIENT_SCOPE", "该应用未被授权此权限范围，请在平台后台调整应用权限后重试。");
      case "invalid_scope" ->
          new OAuthProtocol.OAuthFailure("INSUFFICIENT_SCOPE", "申请的权限范围不被平台接受，请调整授权范围后重试。");
      default ->
          new OAuthProtocol.OAuthFailure(
              "PLATFORM_REJECTED",
              description.isBlank() ? "平台拒绝了本次授权（" + errorCode + "）。" : description);
    };
  }

  protected JsonNode parse(String body) {
    try {
      return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new PlatformException(
          new OAuthProtocol.OAuthFailure("PLATFORM_REJECTED", "平台返回了无法解析的响应，请稍后重试"));
    }
  }

  /** 读字段：先按配置名，再按小驼峰/下划线两种常见写法兜底，减少平台改名带来的无谓失败。 */
  protected static String text(JsonNode node, String field) {
    if (node == null || field == null || field.isBlank()) return "";
    JsonNode value = node.get(field);
    if (value == null) value = node.get(field.toLowerCase());
    if (value == null) value = node.get(toSnake(field));
    if (value == null || value.isNull()) return "";
    return value.isTextual() ? value.asText() : value.asText("");
  }

  private static String toSnake(String camel) {
    StringBuilder out = new StringBuilder();
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) out.append('_');
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  protected static String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  protected static String form(Map<String, String> body) {
    return body.entrySet().stream()
        .filter(e -> e.getValue() != null)
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .reduce((a, b) -> a + "&" + b)
        .orElse("");
  }

  protected static String toJson(Map<String, String> body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
