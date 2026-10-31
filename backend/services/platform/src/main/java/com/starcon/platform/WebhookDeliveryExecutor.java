package com.starcon.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 18.1.2.2 的**投递执行器**：把事件**真的**发到订阅配置的 targetUrl。
 *
 * <p>🔴 **本类存在的全部理由**：18.1.2.1 的 {@code UnavailableEventSubscriptionDeliveryPort}
 * 如实答「未接入」，端点恒 409、一次包都不发 ——
 * 理由写得很清楚：「投递执行器与投递日志同属那一格（18.1.2.2）」。
 * 本类就是那一格的执行侧。
 *
 * <p><b>三条纪律：</b>
 *
 * <ol>
 *   <li><b>真的发出去，返回真的响应码</b>。不写「已投递」的假回执 ——
 *       一条假的成功回执会让「对方系统已经收到并处理了这个事件」在数据上看起来发生过，
 *       而现场一个包都没发出去。{@code statusCode = 0} 是合法结果，
 *       它的含义是「**根本没拿到响应**」（连接被拒 / 超时 / 没发出去）。
 *   <li><b>不跟随重定向</b>（{@link HttpClient.Redirect#NEVER}）。
 *       跟随重定向会让「我们投到了哪个地址」变得不可审阅 ——
 *       投递记录里记的是订阅配置的 target_url，返回的却是别处的响应码。
 *       3xx 就**如实**记成 3xx，由订阅方自己决定要不要改地址。
 *   <li><b>响应体只读前 1 KB</b>。对方回一个 10 MB 的页面不该把本服务撑爆；
 *       投递记录里只需要留个片段供人看清「对方回了什么」。
 * </ol>
 *
 * <p><b>签名头</b>：头名来自 18.1.2.1 的策略（默认 {@code X-Starcon-Signature}），
 * 头值格式 {@code t=<时间戳>,v1=<HMAC-SHA256>} ——
 * 与 18.1.2.1「签名试算」端点返回的 {@code headerValue} **逐字同构**，
 * 对接方只需要实现一种验签。
 */
final class WebhookDeliveryExecutor {

  /** 响应体片段的上限（1 KB）。 */
  private static final int SNIPPET_BYTES = 1024;

  /** 一次投递的执行结果。 */
  record ExecutionResult(
      boolean connected,
      boolean sent,
      int statusCode,
      String reason,
      long durationMs,
      String responseSnippet) {

    /** 成功 = 拿到了 2xx 的响应。 */
    boolean succeeded() {
      return sent && statusCode >= 200 && statusCode <= 299;
    }
  }

  private final HttpClient client;

  WebhookDeliveryExecutor() {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // 🔴 不跟随重定向：投递记录要能回答「我们投到了哪个地址」。
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  /**
   * 投一次。
   *
   * @param targetUrl 订阅配置的外部端点
   * @param body **实际发出去的报文字节**（签名盖的就是它，重放时复用同一份）
   * @param signatureHeader 签名头名称（来自策略）
   * @param signatureValue 签名头值（{@code t=…,v1=…}）
   * @param timeoutSeconds 单次请求超时（来自策略）
   * @param headers 附加头（事件 ID、事件类型等）
   * @return 执行结果（**永远不抛异常**：发不出去也是一种结果，要被记下来）
   */
  ExecutionResult send(
      String targetUrl,
      byte[] body,
      String signatureHeader,
      String signatureValue,
      int timeoutSeconds,
      Map<String, String> headers) {
    var started = System.nanoTime();
    HttpRequest request;
    try {
      var builder =
          HttpRequest.newBuilder()
              .uri(URI.create(targetUrl))
              .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
              .header("Content-Type", "application/json")
              .header("User-Agent", "starcon-platform-webhook/1")
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      if (signatureHeader != null && !signatureHeader.isBlank() && signatureValue != null) {
        builder.header(signatureHeader, signatureValue);
      }
      for (var entry : headers.entrySet()) {
        if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
          builder.header(entry.getKey(), entry.getValue());
        }
      }
      request = builder.build();
    } catch (RuntimeException e) {
      // 地址根本不是一个合法的 URI ⇒ 连一次都没发。
      return new ExecutionResult(false, false, 0, "端点地址不合法：" + e.getMessage(), 0, "");
    }

    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      var snippet = readSnippet(response.body());
      var duration = elapsedMs(started);
      var status = response.statusCode();
      // 🔴 3xx **不算送达**：对方没接受这个事件（而且我们不跟随重定向）。
      var ok = status >= 200 && status <= 299;
      return new ExecutionResult(
          true,
          true,
          status,
          ok ? "" : "对方返回 HTTP " + status,
          duration,
          snippet);
    } catch (java.net.http.HttpTimeoutException e) {
      return new ExecutionResult(
          true, false, 0, "投递超时（超过 " + timeoutSeconds + " 秒）", elapsedMs(started), "");
    } catch (IOException e) {
      // 连接被拒 / DNS 解析失败 / 对端断了 ⇒ **没有响应码**，如实记 0。
      return new ExecutionResult(
          false, false, 0, "连不上对方端点：" + e.getMessage(), elapsedMs(started), "");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecutionResult(
          false, false, 0, "投递被中断", elapsedMs(started), "");
    } catch (RuntimeException e) {
      return new ExecutionResult(
          false, false, 0, "投递异常：" + e.getMessage(), elapsedMs(started), "");
    }
  }

  /** 读响应体的前 1 KB（**有界**：不让对方的超大响应把本服务撑爆）。 */
  private static String readSnippet(InputStream stream) {
    if (stream == null) return "";
    try (stream) {
      var buffer = new byte[SNIPPET_BYTES];
      var read = stream.read(buffer);
      if (read <= 0) return "";
      return new String(buffer, 0, read, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  /** 耗时（毫秒，整数计数；不是数量也不是金额 ⇒ 零浮点）。 */
  private static long elapsedMs(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }
}
