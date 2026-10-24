package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * **平台开票对接**的跨服务投递端口（channel 服务，端口 8083）。
 *
 * <p>AGENTS.md 写明「外部适配器（Shopify / Amazon / ...）放在 {@code backend/services/channel/}」——
 * 所以**报文的实际递交**归 channel，本模块（finance）只负责：
 *
 * <ol>
 *   <li>生成**平台开票报文**（按发票 + 模板快照，确定性产出）；</li>
 *   <li>批量递交并**逐条留痕**（成功 / 失败都写下来，失败写上游原话）；</li>
 *   <li>登记平台回执。</li>
 * </ol>
 *
 * <p>🔴 <b>本端口从不假装成功</b>：上游没接通 / 拒绝 / 报错，一律返回
 * {@code accepted=false} 并把<b>上游原话</b>带回来，由服务层写成失败留痕。
 * 「提交失败」不留痕等于没提交过 —— 现场会以为平台已经收到发票。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class PlatformInvoicePort {

  /** 上游投递形状：{@code POST /channel/platform-invoices/submissions}。 */
  static final String SUBMISSIONS_PATH = "/channel/platform-invoices/submissions";

  /**
   * 平台适配器的归属模块（channel 16.3.2.x 一族）。
   *
   * <p>用于能力声明与失败原因里写明「缺口归谁」。
   */
  static final String OWNER_MODULE = "16.3.2";

  /** 投递结果。 */
  record SubmissionOutcome(boolean accepted, String platformRef, String reason, int upstreamStatus) {

    /** 上游没提供这个接口（404）—— 缺口归 channel，不是本模块的锅。 */
    boolean endpointMissing() {
      return upstreamStatus == 404 || upstreamStatus == 501;
    }
  }

  /** 上游受理结果（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PlatformAck(
      @JsonIgnoreProperties(ignoreUnknown = true) boolean accepted,
      @JsonIgnoreProperties(ignoreUnknown = true) String platformRef,
      @JsonIgnoreProperties(ignoreUnknown = true) String message) {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public PlatformInvoicePort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 把一份平台开票报文递给平台适配器。
   *
   * <p>⚠️ <b>本方法不抛异常</b>：批量递交时一张失败不该让整批白跑。
   * 失败原因<b>原样带回</b>（含上游状态码），由服务层逐条留痕。
   *
   * @param bearer 调用方自己的会话
   * @param platformCode 目标平台
   * @param payload 报文（已经是最终形态，不再二次加工）
   */
  public SubmissionOutcome submit(String bearer, String platformCode, Map<String, Object> payload) {
    try {
      PlatformAck ack =
          client
              .post()
              .uri(SUBMISSIONS_PATH)
              .header("Authorization", bearer)
              .header("X-Platform-Code", platformCode == null ? "" : platformCode)
              .body(payload)
              .retrieve()
              .body(PlatformAck.class);
      if (ack == null) {
        return new SubmissionOutcome(false, "", "平台适配器没有返回受理结果", 0);
      }
      if (ack.accepted()) {
        return new SubmissionOutcome(
            true, ack.platformRef() == null ? "" : ack.platformRef(), "", 200);
      }
      return new SubmissionOutcome(
          false,
          "",
          ack.message() == null || ack.message().isBlank() ? "平台适配器拒绝了这份报文" : ack.message(),
          200);
    } catch (RestClientResponseException e) {
      var status = e.getStatusCode().value();
      var detail = e.getResponseBodyAsString();
      var reason =
          status == 404 || status == 501
              ? "平台适配器尚未提供开票接口（channel " + SUBMISSIONS_PATH + " 返回 " + status
                  + "）—— 缺口归 channel " + OWNER_MODULE
              : "平台适配器返回 " + status + (detail == null || detail.isBlank() ? "" : "：" + trim(detail));
      return new SubmissionOutcome(false, "", reason, status);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SubmissionOutcome(false, "", "平台适配器（channel）不可达：报文未递交", 0);
    }
  }

  /** 上游报错正文可能很长，留痕字段只有 500 —— 截断但保留原话开头。 */
  private String trim(String raw) {
    var text = raw == null ? "" : raw.strip();
    return text.length() <= 300 ? text : text.substring(0, 300) + "…";
  }

  /** 供能力声明引用：平台适配器的归属模块号。 */
  static String ownerModule() {
    return OWNER_MODULE;
  }

  /** 供能力声明引用：上游投递路径。 */
  static String submissionsPath() {
    return SUBMISSIONS_PATH;
  }
}
