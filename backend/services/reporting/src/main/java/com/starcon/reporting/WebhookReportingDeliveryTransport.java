package com.starcon.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 企业 IM 投递的**真实 Webhook 客户端**（13.6.2.1 的 IM 通道）。
 *
 * <p>配置全部来自环境变量：
 *
 * <ul>
 *   <li>{@code REPORTING_IM_WEBHOOK_URL} —— 必填；**没配就 configured()=false**，投递会记 FAILED；</li>
 *   <li>{@code REPORTING_IM_WEBHOOK_TOKEN} —— 可选，配了就带 {@code Authorization: Bearer ...}。</li>
 * </ul>
 *
 * <p>🔴 **这是真的 HTTP 调用**：对方返回 2xx 才算投递成功；4xx / 5xx / 连不上
 * 一律算失败并把对方的响应写进失败原因（运维要能看出是「被拒收」还是「连不上」）。
 */
@Component
public class WebhookReportingDeliveryTransport implements ReportingDeliveryTransport {

  private static final Logger log = LoggerFactory.getLogger(WebhookReportingDeliveryTransport.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private final Environment env;
  private final HttpClient client;

  public WebhookReportingDeliveryTransport(Environment env) {
    this.env = env;
    this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /**
   * 读配置（**每次投递时读**，不在构造时读）。
   *
   * <p>🔴 这样运维改完环境变量/配置后**不用重启**就能生效，
   * 集成测试也能逐例翻转配置（配了 Webhook 的那一条与没配的那一条要在同一个上下文里跑）。
   */
  private String url() {
    return prop("REPORTING_IM_WEBHOOK_URL", "reporting.im.webhook-url", "");
  }

  /**
   * 读一个配置项（**两种写法都试**：环境变量名与 Spring 的宽松写法）。
   *
   * <p>⚠️ 不同 PropertySource 的命中方式不同，两个都试一遍 ⇒
   * 容器注入、{@code @TestPropertyValues}、系统属性三种来源都能生效。
   */
  private String prop(String envName, String relaxedName, String fallback) {
    var value = env.getProperty(envName);
    if (value == null || value.isBlank()) value = env.getProperty(relaxedName);
    return value == null ? fallback : value;
  }

  private String token() {
    return prop("REPORTING_IM_WEBHOOK_TOKEN", "reporting.im.webhook-token", "");
  }

  @Override
  public String channel() {
    return "IM";
  }

  @Override
  public boolean configured() {
    return !url().isEmpty();
  }

  /** 没配时写清缺什么（页面与台账上照着显示）。 */
  public String unconfiguredReason() {
    return "未配置企业 IM 通道（REPORTING_IM_WEBHOOK_URL 为空）—— 本次投递没有发出，"
        + "请配置 Webhook 后重试。";
  }

  @Override
  public Outcome send(Message message) throws IOException {
    if (!configured()) {
      return Outcome.failed(unconfiguredReason());
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put("channel", channel());
    payload.put("target", message.target());
    payload.put("subject", message.subject());
    payload.put("body", message.bodyText());
    payload.put("attachmentName", message.attachmentName());
    payload.put("rows", message.rows() == null ? List.of() : message.rows());

    byte[] body;
    try {
      body = MAPPER.writeValueAsBytes(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IOException("企业 IM 消息序列化失败", e);
    }

    var builder =
        HttpRequest.newBuilder(URI.create(url()))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (!token().isEmpty()) {
      builder.header("Authorization", "Bearer " + token());
    }
    HttpResponse<String> response;
    try {
      response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("企业 IM 投递被中断", e);
    } catch (java.net.http.HttpTimeoutException e) {
      throw new IOException("企业 IM 投递超时（" + TIMEOUT.toSeconds() + " 秒）", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "企业 IM 拒绝了投递：HTTP " + response.statusCode() + " " + brief(response.body()));
    }
    return Outcome.ok("已通过企业 IM Webhook 发出（HTTP " + response.statusCode() + "）。");
  }

  /** 响应体可能很长，截断一下再写进失败原因。 */
  private String brief(String body) {
    if (body == null) return "";
    var trimmed = body.trim();
    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
  }
}
