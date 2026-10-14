package com.starcon.channel.oauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

/**
 * 2.2.1.2 平台应用凭据的运行时配置。
 *
 * <p>AGENTS.md 规定任何 secret 都不入仓库，因此 client_id / client_secret 只在这里从环境变量读：
 *
 * <pre>
 * CHANNEL_OAUTH_SHOPIFY_CLIENT_ID / CHANNEL_OAUTH_SHOPIFY_CLIENT_SECRET
 * CHANNEL_OAUTH_AMAZON_CLIENT_ID  / CHANNEL_OAUTH_AMAZON_CLIENT_SECRET
 * ... 每个平台一对
 * CHANNEL_OAUTH_PUBLIC_BASE_URL    平台回跳用的对外地址，例如 https://erp.example.com
 * </pre>
 *
 * <p>端点、scope、字段名全在 {@code platform_oauth_profile} 表里；这里只管"这个平台有没有配应用"。
 * 平台没配应用不是代码缺陷，向导会明确告诉用户该配哪个环境变量，而不是跳到一个必然失败的地址。
 */
@Component
public class PlatformOAuthProperties {

  private final String publicBaseUrl;
  private final String uiReturnUrl;
  private final Duration stateTtl;
  private final Map<String, PlatformClient> clients = new LinkedHashMap<>();

  public PlatformOAuthProperties(
      @Value("${CHANNEL_OAUTH_PUBLIC_BASE_URL:http://127.0.0.1:8083}") String publicBaseUrl,
      @Value("${CHANNEL_OAUTH_UI_RETURN_URL:http://127.0.0.1:3000/channels}") String uiReturnUrl,
      @Value("${CHANNEL_OAUTH_STATE_TTL:PT15M}") Duration stateTtl,
      ConfigurableEnvironment env) {
    this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    this.uiReturnUrl = uiReturnUrl;
    this.stateTtl = stateTtl;
    for (String key : propertyNames(env)) {
      if (!key.startsWith("CHANNEL_OAUTH_") || !key.endsWith("_CLIENT_ID")) continue;
      String code = key.substring("CHANNEL_OAUTH_".length(), key.length() - "_CLIENT_ID".length());
      String secret = env.getProperty("CHANNEL_OAUTH_" + code + "_CLIENT_SECRET");
      if (secret != null && !secret.isBlank())
        clients.put(
            code.toUpperCase(Locale.ROOT), new PlatformClient(env.getProperty(key), secret));
    }
  }

  private static List<String> propertyNames(ConfigurableEnvironment env) {
    List<String> names = new ArrayList<>();
    for (org.springframework.core.env.PropertySource<?> source : env.getPropertySources())
      if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)
        Collections.addAll(names, enumerable.getPropertyNames());
    return names;
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** 平台应用凭据。{@code toString} 不含 secret，日志里打出来是安全的。 */
  public record PlatformClient(String clientId, String clientSecret) {
    @Override
    public String toString() {
      return "PlatformClient[clientId=" + clientId + "]";
    }
  }

  public PlatformClient clientFor(String platformCode) {
    return clients.get(platformCode.toUpperCase(Locale.ROOT));
  }

  /** 平台没配应用时给用户看的那句话：说清缺哪个变量，而不是只说"配置错误"。 */
  public String missingCredentialHint(String platformCode) {
    return "平台 "
        + platformCode
        + " 尚未配置应用凭据：请设置 CHANNEL_OAUTH_"
        + platformCode
        + "_CLIENT_ID 与 CHANNEL_OAUTH_"
        + platformCode
        + "_CLIENT_SECRET 后重启服务。";
  }

  public String publicBaseUrl() {
    return publicBaseUrl;
  }

  /**
   * 平台回调完成后把浏览器送回的前端地址。
   *
   * <p>平台的 redirect_uri 只能指向后端（应用注册时就固定了），所以用户从授权页回来时先落在 后端；后端处理完再 302 回这个地址并带上 sessionId，向导据此继续第 4
   * 步。 直接把用户停在后端 JSON 上不是可用的交互。
   */
  public String uiReturnUrl() {
    return uiReturnUrl;
  }

  public Duration stateTtl() {
    return stateTtl;
  }
}
