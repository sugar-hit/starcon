package com.starcon.finance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.starcon.finance.PaymentExecutionModels.PaymentAttemptOutcome;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支付渠道的 HTTP 实现（{@link PaymentChannelPort} 的默认适配器）。
 *
 * <p>🔴 <b>本类没有「默认成功」路径</b>：任何一次调用，只要没能拿到渠道**明确的成功回单**，
 * 就返回失败或「结果未知」，绝不返回成功。
 *
 * <p><b>结果映射（这是本模块最重要的那张表）</b>：
 *
 * <table>
 *   <caption>渠道回单 → 执行结论</caption>
 *   <tr><th>渠道回���</th><th>结论</th><th>能不能重发</th></tr>
 *   <tr><td>2xx + status=SUCCESS + 有流水号</td><td>SUCCESS</td><td>终态</td></tr>
 *   <tr><td>2xx + status=FAILED</td><td>REJECTED</td><td>可以（查证后）</td></tr>
 *   <tr><td>4xx（明确拒绝，含凭据被拒）</td><td>REJECTED</td><td>可以（查证后）</td></tr>
 *   <tr><td>4xx 出现在**查证**里</td><td>CHANNEL_UNAVAILABLE</td><td><b>不可以</b>（查不出来 ≠ 没有）</td></tr>
 *   <tr><td>404（出现在**查证**里）</td><td>NOT_FOUND</td><td>可以（渠道侧确实没有）</td></tr>
 *   <tr><td>5xx / 超时 / 网络不可达</td><td>TIMEOUT 或 CHANNEL_UNAVAILABLE</td><td><b>不可以</b>（必须先查证）</td></tr>
 *   <tr><td>没有凭据 / 主机不在白名单</td><td>NOT_CONFIGURED</td><td>可以（本来就没发出去）</td></tr>
 * </table>
 *
 * <p><b>主机白名单</b>（{@code PAYMENT_CHANNEL_ALLOWED_HOSTS}）：租户可以登记渠道地址，
 * 而渠道地址会变成一次**由本系统发起的外呼** ⇒ 不加白名单就等于把内网变成攻击面。
 * 白名单为空 ⇒ **fail-closed**（一条渠道也用不了），并明确报出原因。
 *
 * <p>🔴 <b>凭据只出现在请求头里</b>：本类**不记录**它，请求摘要里也**不包含**它。
 */
@Component
public class HttpPaymentChannelAdapter implements PaymentChannelPort {

  /** 上游 JSON 的 mapper（只读回单，容错未知字段）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final PaymentSecretPort secrets;
  private final HttpClient client;
  private final Set<String> allowedHosts;

  public HttpPaymentChannelAdapter(
      PaymentSecretPort secrets, @Value("${PAYMENT_CHANNEL_ALLOWED_HOSTS:}") String allowedHosts) {
    this.secrets = secrets;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.allowedHosts =
        java.util.Arrays.stream((allowedHosts == null ? "" : allowedHosts).split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  // ================================================================ 预检

  @Override
  public String unavailableReason(PaymentChannelConfig config) {
    if (config == null) return "渠道配置不存在";
    if (config.baseUrl() == null || config.baseUrl().isBlank()) return "渠道地址未配置";
    var host = hostOf(config.baseUrl());
    if (host == null) return "渠道地址不合法：" + config.baseUrl();
    if (allowedHosts.isEmpty()) {
      return "未配置渠道主机白名单（PAYMENT_CHANNEL_ALLOWED_HOSTS）："
          + "不允许对任意主机发起付款，请先由运维配置白名单";
    }
    if (!allowedHosts.contains(host)) return "渠道主机 " + host + " 不在白名单内";
    if (config.secretRef() == null || config.secretRef().isBlank()) return "渠道凭据引用未配置";
    if (secrets.resolve(config.secretRef()).isEmpty()) {
      return "渠道凭据解析不到（引用 " + config.secretRef() + "）：请确认密钥管理器里有这条引用";
    }
    return null;
  }

  // ================================================================ 发送

  @Override
  public ChannelOutcome send(PaymentChannelConfig config, PaymentInstruction instruction) {
    var reason = unavailableReason(config);
    if (reason != null) {
      return new ChannelOutcome(
          PaymentAttemptOutcome.NOT_CONFIGURED, "", reason, "未发出任何请求", null, null);
    }
    var credential = secrets.resolve(config.secretRef()).orElse(null);
    if (credential == null) {
      return new ChannelOutcome(
          PaymentAttemptOutcome.NOT_CONFIGURED,
          "",
          "渠道凭据在发送前解析不到（引用 " + config.secretRef() + "）",
          "未发出任何请求",
          null,
          null);
    }
    var body = JSON.createObjectNode();
    body.put("reference", instruction.reference());
    body.put("amount", instruction.amount().toPlainString());
    body.put("currency", instruction.currency());
    if (instruction.baseCurrency() != null) body.put("baseCurrency", instruction.baseCurrency());
    if (instruction.exchangeRate() != null) body.put("exchangeRate", instruction.exchangeRate().toPlainString());
    if (instruction.baseAmount() != null) body.put("baseAmount", instruction.baseAmount().toPlainString());
    var beneficiary = body.putObject("beneficiary");
    beneficiary.put("type", instruction.beneficiaryType());
    beneficiary.put("name", instruction.beneficiaryName());
    if (instruction.beneficiaryRef() != null) beneficiary.put("ref", instruction.beneficiaryRef());
    if (instruction.note() != null && !instruction.note().isBlank()) body.put("note", instruction.note());

    var request =
        HttpRequest.newBuilder(URI.create(join(config.baseUrl(), "/payments")))
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .header("Authorization", "Bearer " + credential)
            .header("Idempotency-Key", instruction.idempotencyKey())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return interpretSend(response.statusCode(), response.body());
    } catch (HttpTimeoutException e) {
      return unknown(
          PaymentAttemptOutcome.TIMEOUT,
          "渠道调用超时（" + config.timeoutSeconds() + " 秒）：结果未知，必须先查证再重发");
    } catch (IOException e) {
      return unknown(
          PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
          "渠道不可达（" + e.getClass().getSimpleName() + "）：结果未知，必须先查证再重发");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown(PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道调用被中断：结果未知，必须先查证再重发");
    }
  }

  /** 发送侧的回单解读。 */
  private ChannelOutcome interpretSend(int status, String body) {
    if (status >= 200 && status < 300) {
      if (status == 202) {
        return unknown(
            PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
            "渠道已受理但未给出最终结果（HTTP 202）：结果未知，必须先查证再重发");
      }
      var node = parse(body);
      if (node == null) {
        return unknown(
            PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道回单无法解析：结果未知，必须先查证再重发");
      }
      var state = text(node, "status");
      if ("SUCCESS".equalsIgnoreCase(state)) {
        var serial = text(node, "serialNo");
        if (serial == null || serial.isBlank()) {
          return unknown(
              PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
              "渠道回单说成功但没有流水号：没有流水号的付款事后无从查证，按结果未知处理");
        }
        return new ChannelOutcome(
            PaymentAttemptOutcome.SUCCESS,
            serial,
            "",
            summarize(node),
            decimal(node, "paidAmount"),
            text(node, "currency"));
      }
      if ("FAILED".equalsIgnoreCase(state)) {
        return new ChannelOutcome(
            PaymentAttemptOutcome.REJECTED,
            "",
            reasonOf(node, "渠道明确返回失败"),
            summarize(node),
            decimal(node, "paidAmount"),
            text(node, "currency"));
      }
      return unknown(
          PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
          "渠道回单没有可识别的状态：结果未知，必须先查证再重发");
    }
    if (status >= 400 && status < 500) {
      var node = parse(body);
      // 409 = 同一个幂等键已经付过了（幂等重放）⇒ 当成成功，**不要再发一次**。
      if (node != null && "SUCCESS".equalsIgnoreCase(text(node, "status"))) {
        var serial = text(node, "serialNo");
        if (serial != null && !serial.isBlank()) {
          return new ChannelOutcome(
              PaymentAttemptOutcome.SUCCESS,
              serial,
              "",
              summarize(node),
              decimal(node, "paidAmount"),
              text(node, "currency"));
        }
      }
      var detail =
          status == 401 || status == 403
              ? "渠道拒绝了凭据（HTTP " + status + "）：请检查密钥引用对应的凭据是否有效"
              : "渠道明确拒绝（HTTP " + status + "）：" + reasonOf(node, "渠道未说明原因");
      return new ChannelOutcome(
          PaymentAttemptOutcome.REJECTED, "", detail, summarize(node), null, null);
    }
    return unknown(
        PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
        "渠道返回 HTTP " + status + "：结果未知，必须先查证再重发");
  }

  // ================================================================ 查证

  @Override
  public ChannelOutcome query(PaymentChannelConfig config, String idempotencyKey) {
    var reason = unavailableReason(config);
    if (reason != null) {
      return new ChannelOutcome(
          PaymentAttemptOutcome.NOT_CONFIGURED,
          "",
          reason,
          "未发出任何请求",
          null,
          null);
    }
    var credential = secrets.resolve(config.secretRef()).orElse(null);
    if (credential == null) {
      return new ChannelOutcome(
          PaymentAttemptOutcome.NOT_CONFIGURED,
          "",
          "渠道凭据在查证前解析不到（引用 " + config.secretRef() + "）",
          "未发出任何请求",
          null,
          null);
    }
    var request =
        HttpRequest.newBuilder(URI.create(join(config.baseUrl(), "/payments/" + encode(idempotencyKey))))
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .header("Authorization", "Bearer " + credential)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return interpretQuery(response.statusCode(), response.body());
    } catch (HttpTimeoutException e) {
      return unknown(
          PaymentAttemptOutcome.TIMEOUT,
          "渠道查证超时（" + config.timeoutSeconds() + " 秒）：结果仍未知，不允许重发");
    } catch (IOException e) {
      return unknown(
          PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
          "渠道查证不可达（" + e.getClass().getSimpleName() + "）：结果仍未知，不允许重发");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown(PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道查证被中断：结果仍未知，不允许重发");
    }
  }

  /**
   * 查证侧的回单解读。
   *
   * <p>🔴 <b>只有 404（渠道侧确实没有）与 200+status=FAILED（渠道侧确实失败）**
   * 才允许重发；**其它任何查不出来的情况都判「结果仍未知」** ——
   * 「查不到」不等于「没付出去」。
   */
  private ChannelOutcome interpretQuery(int status, String body) {
    if (status == 404) {
      return new ChannelOutcome(
          PaymentAttemptOutcome.NOT_FOUND, "", "渠道侧没有这笔付款（HTTP 404）", "", null, null);
    }
    if (status >= 200 && status < 300) {
      var node = parse(body);
      if (node == null) {
        return unknown(PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道查证回单无法解析：结果仍未知");
      }
      var state = text(node, "status");
      if ("SUCCESS".equalsIgnoreCase(state)) {
        var serial = text(node, "serialNo");
        if (serial == null || serial.isBlank()) {
          return unknown(
              PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道查证回单说成功但没有流水号：结果仍未知");
        }
        return new ChannelOutcome(
            PaymentAttemptOutcome.SUCCESS,
            serial,
            "",
            summarize(node),
            decimal(node, "paidAmount"),
            text(node, "currency"));
      }
      if ("FAILED".equalsIgnoreCase(state)) {
        return new ChannelOutcome(
            PaymentAttemptOutcome.REJECTED,
            "",
            reasonOf(node, "渠道侧记录为失败"),
            summarize(node),
            decimal(node, "paidAmount"),
            text(node, "currency"));
      }
      return unknown(PaymentAttemptOutcome.CHANNEL_UNAVAILABLE, "渠道查证回单没有可识别的状态：结果仍未知");
    }
    if (status >= 400 && status < 500) {
      return unknown(
          PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
          "渠道查证返回 HTTP " + status + "：查不出来不等于没付出去，结果仍未知");
    }
    return unknown(
        PaymentAttemptOutcome.CHANNEL_UNAVAILABLE,
        "渠道查证返回 HTTP " + status + "：结果仍未知，不允许重发");
  }

  // ================================================================ 工具

  /** 「结果未知」的回执。 */
  private ChannelOutcome unknown(PaymentAttemptOutcome outcome, String reason) {
    return new ChannelOutcome(outcome, "", reason, "", null, null);
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return JSON.readTree(body);
    } catch (IOException e) {
      return null;
    }
  }

  private String text(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) return null;
    return value.asText();
  }

  private BigDecimal decimal(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) return null;
    var raw = value.asText();
    if (raw == null || raw.isBlank()) return null;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String reasonOf(JsonNode node, String fallback) {
    if (node == null) return fallback;
    var reason = text(node, "reason");
    if (reason == null) reason = text(node, "message");
    if (reason == null || reason.isBlank()) return fallback;
    return reason.trim();
  }

  /** 回单摘要（**只留结论性字段**，绝不含凭据）。 */
  private String summarize(JsonNode node) {
    if (node == null) return "";
    var out = new StringBuilder();
    appendField(out, node, "status");
    appendField(out, node, "serialNo");
    appendField(out, node, "paidAmount");
    appendField(out, node, "currency");
    appendField(out, node, "reason");
    var raw = out.toString();
    return raw.length() > 900 ? raw.substring(0, 900) : raw;
  }

  private void appendField(StringBuilder out, JsonNode node, String field) {
    var value = text(node, field);
    if (value == null || value.isBlank()) return;
    if (!out.isEmpty()) out.append(' ');
    out.append(field).append('=').append(value.length() > 120 ? value.substring(0, 120) : value);
  }

  /** 取 base_url 的主机名（小写）；解析不出来返回 null。 */
  static String hostOf(String baseUrl) {
    try {
      var uri = URI.create(baseUrl.trim());
      var host = uri.getHost();
      return host == null ? null : host.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 拼接地址（去掉 base 结尾的斜杠）。 */
  static String join(String baseUrl, String path) {
    var base = baseUrl.trim();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base + path;
  }

  /** URL 路径段编码。 */
  static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}
