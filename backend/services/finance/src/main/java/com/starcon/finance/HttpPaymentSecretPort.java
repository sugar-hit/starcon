package com.starcon.finance;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密钥管理器的 HTTP 实现（12.2.2.2 付款执行的凭据来源）。
 *
 * <p>AGENTS.md：「接入第三方 API（Amazon/PayPal/...）的 key 全部走 secret manager
 * （K8s External Secrets / Vault）」。本实现只经 HTTP 读密钥管理器，
 * **不落盘、不入库、不进日志**。
 *
 * <p>⚠️ <b>没接入密钥管理器 ⇒ 一律解析不到</b>：{@code PAYMENT_SECRET_MANAGER_URL} 为空时
 * {@link #resolve(String)} 直接返回 {@link Optional#empty()} ——
 * 于是渠道被判为「未配置」，付款**发不出去**，而系统**不会假装成功**。
 * 这是 fail-closed：宁可不付，也不能在凭据不明的情况下把钱打出去。
 *
 * <p>🔴 <b>明文不落任何地方</b>：解析结果只作为返回值交给调用方（放进请求头），
 * 本类**不记录**它，调用方也不记录它。
 */
@Component
public class HttpPaymentSecretPort implements PaymentSecretPort {

  /** 密钥管理器未接入时，{@link #resolve(String)} 一律解析不到。 */
  static final String NOT_CONFIGURED = "密钥管理器未接入（PAYMENT_SECRET_MANAGER_URL 未设置）";

  private final HttpClient client;
  private final String baseUrl;
  private final String key;

  public HttpPaymentSecretPort(
      @Value("${PAYMENT_SECRET_MANAGER_URL:}") String url,
      @Value("${PAYMENT_SECRET_MANAGER_KEY:}") String key) {
    this.baseUrl = url == null ? "" : url.trim();
    this.key = key == null ? "" : key.trim();
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @Override
  public Optional<String> resolve(String secretRef) {
    if (secretRef == null || secretRef.isBlank()) return Optional.empty();
    if (baseUrl.isEmpty()) return Optional.empty();
    var path = pathOf(secretRef);
    if (path == null) return Optional.empty();
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/secrets/" + path))
            .timeout(Duration.ofSeconds(5))
            .header("X-Internal-Key", key)
            .GET()
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return Optional.empty();
      var value = response.body();
      if (value == null || value.isBlank()) return Optional.empty();
      return Optional.of(value.trim());
    } catch (IOException e) {
      // 密钥管理器不可达 ⇒ 解析不到。**不打印响应体**（那可能含明文）。
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * 引用 → 密钥管理器里的路径。
   *
   * <p>{@code vault://starcon/finance/paypal} ⇒ {@code starcon/finance/paypal}。
   * 只接受已知的四个 scheme 前缀（与 V350 的表级 CHECK 同一份词表）。
   */
  static String pathOf(String secretRef) {
    var raw = secretRef.trim();
    var marker = raw.indexOf("://");
    if (marker <= 0) return null;
    var scheme = raw.substring(0, marker);
    if (!java.util.Set.of("secret", "kv", "vault", "external-secrets").contains(scheme)) return null;
    var path = raw.substring(marker + 3);
    if (path.isBlank()) return null;
    // 逐段编码：路径里的斜杠要保留，其余字符（含空格、中文）要转义。
    var parts = path.split("/");
    var encoded = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) encoded.append('/');
      encoded.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
    }
    return encoded.toString();
  }
}
