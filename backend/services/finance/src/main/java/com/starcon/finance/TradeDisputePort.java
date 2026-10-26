package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **11.4.1.1 纠纷列表**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12-finance.md 把 11.4.1.1 列为 12.1.1.3 的上游：「关系：上游 = 12.1.1.2、11.4.1.1 纠纷」。
 *
 * <p>⚠️ 纠纷事实在 **trade 服务**（卷 11 售后与退货），**不在 finance**。
 * AGENTS.md 模块边界纪律写死：跨服务必须走授权 API / event，**禁止跨服务 SQL / 跨库 FK / 共享视图**。
 * ⇒ 本端口**只经 trade 的授权 HTTP API** 读，**不直连对方库、不建跨库视图、不建跨库外键**。
 *
 * <p><b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>（finance 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p><b>取不到就显式失败</b>：上游不可达 / 无权 / 不存在，一律 503 或 404，**绝不返回空对象冒充「上游没有纠纷」** ——
 * 那会让「依据链里写着一个不存在的纠纷号」的坏账单通过审批。
 */
@Service
public class TradeDisputePort {

  /** 上游只读形状：{@code GET /trade/platform-disputes/{id}}（11.4.1.1 纠纷列表）。 */
  static final String DISPUTES_PATH = "/trade/platform-disputes";

  /**
   * 上游纠纷单头（本模块真正用到的字段）。
   *
   * <p>⚠️ {@code disputeAmount} 是上游的**争议金额**，本模块把它当**快照**存下来，
   * 不拿它当核销金额 —— 核销金额由目标应收的未核销余额决定。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeDisputeHead(
      String disputeNo,
      String platformCode,
      String platformDisputeNo,
      String disputeType,
      String disputeCurrency,
      BigDecimal disputeAmount,
      String disputeStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime resolvedAt) {}

  /** 上游纠纷详情的形状（只取 {@code head}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeDisputeDetail(TradeDisputeHead head) {}

  /** 上游 JSON 的 mapper：关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不该把本模块撑爆；类型不匹配照样炸 —— 那种是真读错了）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public TradeDisputePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一张上游纠纷单。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   * @throws ResponseStatusException 上游不可达（503）/ 上游没有这张纠纷（404）/ 调用方无权（401）
   */
  public TradeDisputeHead dispute(UUID id, String bearer) {
    try {
      TradeDisputeDetail detail =
          client
              .get()
              .uri(DISPUTES_PATH + "/" + id)
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeDisputeDetail.class);
      if (detail == null || detail.head() == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 11.4.1.1 纠纷 " + id + " 不存在或无权读取");
      }
      return detail.head();
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 11.4.1.1 纠纷 " + id + " 不存在");
      }
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游纠纷事实（需要 trade 侧的 trade.aftersales.read 权限）", e);
      }
      throw unavailable("trade 服务返回 " + code + "：纠纷事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：11.4.1.1 纠纷事实读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
