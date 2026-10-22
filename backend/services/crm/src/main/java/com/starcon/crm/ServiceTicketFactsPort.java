package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.4.1.1 工单列表的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>工单的**事实来源在 trade 服务**（11.4.2.1 工单管理，V270
 * {@code aftersales_service_ticket} 及其四张附属事实表），
 * 而本模块要把它呈现成一张<b>客服列表</b>：类型 / 优先级 / 状态 / SLA 剩余 /
 * 关联买家与订单 / 分配客服 / 转派与升级。
 *
 * <p>AGENTS.md 模块边界纪律：<b>禁止跨服务 SQL / 跨库 FK / 共享视图</b>，
 * 所以本端口<b>只经 trade 的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（crm 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p><b>取不到就显式失败</b>：上游不可达 / 返回非 2xx 一律抛 503（写明「读不到」而不是
 * 「没有数据」），绝不返回空集合冒充「上游没有工单」——
 * 那会让客服看到 0 张工单，照着空清单以为这批工单根本没建过。
 *
 * <p>用法与同服务既有的 {@link MessageContextPort} / {@link CustomerFactsPort} 逐字同构：
 * RestClient + 显式连接/读超时 + 转发调用方 bearer + 取不到就抛。
 */
@Service
public class ServiceTicketFactsPort {

  /** 上游只读形状：工单列表。 */
  static final String TICKETS_PATH = "/trade/service-tickets";

  /** 上游只读形状：售后单列表（工单挂在售后单上，订单号要从那里取）。 */
  static final String CASES_PATH = "/trade/aftersales/cases";

  /** 上游只读形状：订单列表。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游翻页上限（trade 侧把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 单次扫描最多翻多少页（{@code 200 × 25 = 5000} 张工单）。 */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行工单（只声明本模块真正用到的字段）。
   *
   * <p>⚠️ {@code replySlaState} / {@code resolveSlaState} 是上游<b>自然时间</b>口径的判定，
   * 本模块<b>不用</b>它们做展示（展示用的是营业时间口径），
   * 只保留形状以便将来对照。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TicketView(
      UUID id,
      String ticketNo,
      String ticketType,
      String ticketStatus,
      String priority,
      String sourceChannel,
      String sourceRef,
      String subject,
      String reasonNote,
      String assignedToRef,
      UUID assignedToUserId,
      OffsetDateTime assignedAt,
      UUID caseId,
      String caseNo,
      String caseStatus,
      String caseType,
      String platformScope,
      UUID slaPolicyId,
      Integer slaReplyHours,
      Integer slaResolveHours,
      String slaBasis,
      OffsetDateTime replyDueAt,
      OffsetDateTime resolveDueAt,
      OffsetDateTime firstReplyAt,
      OffsetDateTime waitingBuyerAt,
      OffsetDateTime closedAt,
      String closeReason,
      String closeNote,
      UUID escalatedCaseId,
      String escalatedCaseNo,
      UUID escalatedDisputeId,
      String escalatedDisputeNo,
      Integer satisfactionRating,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 上游一页工单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TicketPage(
      List<TicketView> rows, int page, int size, long total, TicketSummary summary) {}

  /**
   * 上游的汇总条（与分页无关，口径 = 上游的筛选条件）。
   *
   * <p>⚠️ 上游的 {@code overdue} 是<b>自然时间</b>口径，本模块<b>不用</b>它 ——
   * 营业时间口径的逾期数由本模块自己算（否则「夜里的单全逾期」的误判会被原样继承）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TicketSummary(
      long total,
      long open,
      long inProgress,
      long waitingBuyer,
      long resolved,
      long cancelled,
      long unassigned,
      long overdue,
      long complaint,
      long inquiry) {}

  /** 上游一行售后单（工单 → 售后单 → 订单这条链的中间一环）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CaseView(
      UUID id,
      String caseNo,
      UUID orderId,
      String orderNo,
      String platformSource,
      String platformShopCode,
      String caseType,
      String status) {}

  /** 上游一页售后单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CasePage(List<CaseView> rows, int page, int size, long total) {}

  /**
   * 上游一行订单（订单事实的唯一真身）。
   *
   * <p>金额用 {@link BigDecimal}（上游是 numeric）：禁浮点，前端原样展示。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderView(
      UUID id,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      UUID customerPartyId,
      String buyerName,
      String platformStatus,
      String paymentStatus,
      String fulfillmentStatus,
      String aftersalesStatus) {}

  /** 上游一页订单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderPage(List<OrderView> rows, int page, int size, long total) {}

  /** 上游一次派单动作（转派历史的一行，append-only）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssignmentView(
      UUID id,
      String assignAction,
      String assignMode,
      String fromOwnerRef,
      String toOwnerRef,
      String reasonNote,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 上游工单详情（头 + 转派历史 + 事件流 + …）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TicketDetail(TicketView head, List<AssignmentView> assignments) {}

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient trade;

  public ServiceTicketFactsPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次列表要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.trade =
        RestClient.builder()
            .baseUrl(tradeUrl)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页工单（<b>事实来源</b>：分页 / 筛选 / 排序都由上游执行）。
   *
   * <p>⚠️ 本方法<b>不传</b> {@code slaState} / {@code overdueOnly} ——
   * 上游的同名筛选走的是<b>自然时间</b>口径，与本模块的营业时间口径
   * 不是同一个判定；把它们下推过去会得到「按自然时间筛出来的子集」，
   * 漏掉「自然时间还没到、营业时间已经超了」的单（夜间积压正是这类）。
   */
  public TicketPage tickets(
      String status,
      String ticketType,
      String priority,
      String sourceChannel,
      String assignedToRef,
      UUID caseId,
      Boolean unassignedOnly,
      OffsetDateTime from,
      OffsetDateTime to,
      String keyword,
      String sort,
      int page,
      int size,
      String bearer) {
    try {
      TicketPage result =
          trade
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(TICKETS_PATH);
                    putIfSet(b, "status", status);
                    putIfSet(b, "ticketType", ticketType);
                    putIfSet(b, "priority", priority);
                    putIfSet(b, "sourceChannel", sourceChannel);
                    putIfSet(b, "assignedToRef", assignedToRef);
                    if (caseId != null) {
                      b.queryParam("caseId", caseId.toString());
                    }
                    putIfSet(b, "unassignedOnly", unassignedOnly);
                    if (from != null) {
                      b.queryParam("from", from.toString());
                    }
                    if (to != null) {
                      b.queryParam("to", to.toString());
                    }
                    putIfSet(b, "keyword", keyword);
                    putIfSet(b, "sort", sort);
                    return b.queryParam("page", Math.max(page, 0))
                        .queryParam("size", Math.max(size, 1))
                        .build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(TicketPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回工单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "调用方没有读取上游工单的权限（trade.aftersales.read）：工单列表读不到",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：工单列表读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /**
   * 按售后单号取上游售后单（工单 → 售后单 → 订单这条链的中间一环）。
   *
   * <p>⚠️ 上游的 {@code caseNo} 过滤是<b>模糊</b>匹配（ilike），
   * 所以本方法<b>只认完全相等</b>的 {@code caseNo} ——
   * 拿一条「相近」的售后单去补订单上下文，比显示「订单不可用」更糟：
   * 客服会照着错的单子去处理。
   *
   * @return 命中的售后单；没有完全相等的行时返回 {@code null}（<b>不是</b>空集合冒充「没有」）
   */
  public CaseView caseByNo(String caseNo, String bearer) {
    if (caseNo == null || caseNo.isBlank()) {
      return null;
    }
    CasePage page;
    try {
      page =
          trade
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(CASES_PATH)
                          .queryParam("caseNo", caseNo)
                          .queryParam("page", 0)
                          .queryParam("size", 20)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(CasePage.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "调用方没有读取上游售后单的权限（trade.aftersales.read）：关联订单读不到",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：关联订单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
    if (page == null || page.rows() == null) {
      throw unavailable("trade 服务没有返回售后单列表");
    }
    for (CaseView row : page.rows()) {
      if (caseNo.equals(row.caseNo())) {
        return row;
      }
    }
    return null;
  }

  /**
   * 按订单号取上游订单（订单事实的唯一真身）。
   *
   * <p>⚠️ 上游的 {@code keyword} 是<b>模糊</b>匹配（内部单号 / 外部单号 / 买家名），
   * 所以本方法<b>只认完全相等</b>的 {@code orderNo} / {@code externalOrderNo}。
   */
  public OrderView orderByNo(String orderNo, String bearer) {
    if (orderNo == null || orderNo.isBlank()) {
      return null;
    }
    OrderPage page;
    try {
      page =
          trade
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("keyword", orderNo)
                          .queryParam("page", 0)
                          .queryParam("size", 50)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderPage.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "调用方没有读取上游订单的权限（trade.orders.read）：关联订单读不到",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：关联订单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
    if (page == null || page.rows() == null) {
      throw unavailable("trade 服务没有返回订单列表");
    }
    for (OrderView row : page.rows()) {
      if (orderNo.equals(row.orderNo()) || orderNo.equals(row.externalOrderNo())) {
        return row;
      }
    }
    return null;
  }

  /**
   * 取一张工单的<b>转派历史</b>（上游 {@code aftersales_ticket_assignment}，append-only）。
   *
   * <p>⚠️ 上游没有「批量取转派历史」的接口，只有单张详情里带着它 ——
   * 所以本模块的列表行是<b>逐行</b>取转派历史的（取不到时如实标
   * {@code historyAvailable=false}，<b>不编一个 0</b>）。
   */
  public TicketDetail ticketDetail(UUID ticketId, String bearer) {
    try {
      TicketDetail detail =
          trade
              .get()
              .uri(TICKETS_PATH + "/" + ticketId)
              .header("Authorization", bearer)
              .retrieve()
              .body(TicketDetail.class);
      if (detail == null) {
        throw unavailable("trade 服务没有返回工单详情");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return null;
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "调用方没有读取上游工单详情的权限：转派历史读不到", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：转派历史读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  private static void putIfSet(
      org.springframework.web.util.UriBuilder builder, String name, String value) {
    if (value != null && !value.isBlank()) {
      builder.queryParam(name, value);
    }
  }

  private static void putIfSet(
      org.springframework.web.util.UriBuilder builder, String name, Boolean value) {
    if (value != null) {
      builder.queryParam(name, value);
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
