package com.starcon.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.4.1.2 工单处理的**跨服务写端口**（trade 服务，端口 8085）。
 *
 * <p>🔴 <b>工单的写动作归 11.4.2.1（trade 服务）所有</b>：
 * 回复（首次）/ 状态流转 / 关闭 / 满意度 / 升级 全部经这里的**授权 HTTP API** 做，
 * 本服务**一个字都不写对方的表**、不建跨库 FK、不建共享视图、不直连对方库
 * （AGENTS.md 模块边界纪律）。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（crm 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p><b>为什么与 {@link ServiceTicketFactsPort} 分成两个端口</b>：
 * 那个端口的职责是<b>只读</b>（它自己的 javadoc 承诺了这一点），
 * 本端口是<b>只写</b>。方向分开 ⇒ 两边的契约不会互相漂移。
 * 但<b>上游的响应形状只有一份</b>（{@link ServiceTicketFactsPort.TicketView} /
 * {@link ServiceTicketFactsPort.TicketDetail}）——
 * 两边各写一份形状的话，早晚会长歪（长歪之后「读到的状态」和「写完的状态」对不上）。
 *
 * <p><b>上游的拒绝原样透出</b>：上游说「关闭原因非法」，
 * 本服务就说「关闭原因非法」，不吞掉也不改写成「操作失败」——
 * 客服看到一句人话才知道该改什么。
 */
@Service
public class ServiceTicketProcessPort {

  /** 上游的工单写动作路径（事实所有者在 trade 服务）。 */
  static final String TICKETS_PATH = "/trade/service-tickets";

  private final RestClient trade;
  private final ObjectMapper json = new ObjectMapper();

  public ServiceTicketProcessPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 写动作比读慢一截（上游要落库 + 记事件），但仍然必须有上限 ——
    // 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.trade =
        RestClient.builder()
            .baseUrl(tradeUrl)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(
                    new MappingJackson2HttpMessageConverter(
                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                            .disable(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES)
                            .disable(
                                com.fasterxml.jackson.databind.SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS)
                            .build())))
            .build();
  }

  // ================================================================ 写动作

  /**
   * 记录首次回复（回复时限的完成时刻）。
   *
   * <p>⚠️ 上游只允许记一次（重复记会被拒）—— 这正是「回复时限的完成时刻」的定义。
   */
  public ServiceTicketFactsPort.TicketDetail firstReply(
      UUID ticketId, String replyNote, Long expectedVersion, String bearer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("replyNote", replyNote);
    if (expectedVersion != null) {
      body.put("expectedVersion", expectedVersion);
    }
    return post(ticketId, "first-reply", body, bearer, "首次回复");
  }

  /** 状态流转（待处理 → 处理中 ⇄ 待买家）。 */
  public ServiceTicketFactsPort.TicketDetail changeStatus(
      UUID ticketId, String targetStatus, String note, Long expectedVersion, String bearer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetStatus", targetStatus);
    body.put("note", note);
    if (expectedVersion != null) {
      body.put("expectedVersion", expectedVersion);
    }
    return post(ticketId, "status", body, bearer, "状态流转");
  }

  /**
   * 关闭工单（落终态）。
   *
   * <p>🔴 <b>关闭不等于解决</b>：这里只让工单不再跟了；
   * 「实际结果到了没有」由本模块的**解决确认**回答（另一张表、另一个动作）。
   */
  public ServiceTicketFactsPort.TicketDetail close(
      UUID ticketId, String closeReason, String closeNote, Long expectedVersion, String bearer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("closeReason", closeReason);
    body.put("closeNote", closeNote);
    if (expectedVersion != null) {
      body.put("expectedVersion", expectedVersion);
    }
    return post(ticketId, "close", body, bearer, "关闭工单");
  }

  /**
   * 满意度回收（1~5 分，只对已关闭的工单开放）。
   *
   * <p>🔴 <b>不另存一份满意度</b>：上游已有这个事实（11.4.2.1 拥有），
   * 同一件事两个事实源迟早打架 ⇒ 本模块只转发。
   */
  public ServiceTicketFactsPort.TicketDetail satisfaction(
      UUID ticketId, Integer score, String comment, String bearer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("score", score);
    body.put("comment", comment);
    return post(ticketId, "satisfaction", body, bearer, "满意度回收");
  }

  /**
   * 升级：咨询 → 投诉（新建售后单）/ 投诉 → 纠纷（新建平台纠纷）。
   *
   * <p>⚠️ 升级物**真的**落在 trade 库里（那边的唯一写入所有者干的活），
   * 本模块只把**关系**记在自己的关联单据表里。
   */
  public ServiceTicketFactsPort.TicketDetail escalate(
      UUID ticketId, Map<String, Object> body, String bearer) {
    return post(ticketId, "escalate", body, bearer, "升级");
  }

  /** 通用 POST：把上游的错误原样透出（4xx → 同码 + 上游原话；5xx / 不可达 → 503）。 */
  private ServiceTicketFactsPort.TicketDetail post(
      UUID ticketId, String action, Map<String, Object> body, String bearer, String what) {
    try {
      ServiceTicketFactsPort.TicketDetail detail =
          trade
              .post()
              .uri(TICKETS_PATH + "/" + ticketId + "/" + action)
              .header("Authorization", bearer)
              .body(body)
              .retrieve()
              .body(ServiceTicketFactsPort.TicketDetail.class);
      if (detail == null || detail.head() == null) {
        throw unavailable("trade 服务没有返回工单详情（" + what + "后）");
      }
      return detail;
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String detail = upstreamDetail(e);
      if (status == 401 || status == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "调用方没有处理上游工单的权限（trade.aftersales.write）：" + what + "被拒" + suffix(detail),
            e);
      }
      if (status == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 trade 里没有这张工单（" + what + "）" + suffix(detail), e);
      }
      if (status >= 400 && status < 500) {
        throw new ResponseStatusException(
            HttpStatus.valueOf(status),
            what + "被上游拒绝：" + (detail == null || detail.isBlank() ? "上游未给出原因" : detail),
            e);
      }
      throw unavailable("trade 服务返回 " + status + "（" + what + "）" + suffix(detail));
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达（" + what + "）");
    }
  }

  /** 上游 ProblemDetail 里的 {@code detail}（人话）。取不到就返回 null。 */
  private String upstreamDetail(RestClientResponseException e) {
    try {
      String body = e.getResponseBodyAsString();
      if (body == null || body.isBlank()) {
        return null;
      }
      var node = json.readTree(body);
      var detail = node.get("detail");
      return detail == null || detail.isNull() ? null : detail.asText();
    } catch (Exception ignored) {
      // 上游的错误体不是 JSON 时不猜：返回 null，让调用方看到「上游未给出原因」。
      return null;
    }
  }

  private static String suffix(String detail) {
    return detail == null || detail.isBlank() ? "" : "：" + detail;
  }

  /** 上游不可用一律 503，理由写明「做不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
