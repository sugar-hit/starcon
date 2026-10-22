package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.6.1.1 PII 管理的**跨服务只读端口**。
 *
 * <p>蓝图写明上游 = **15 客户**（crm 服务）与 **9 订单**（trade 服务），而**这两样真值都不在本服务**：
 * 买家姓名 / 电话 / 邮箱 / 地址住在 crm 的 {@code crm_buyer_email} 与 trade 的
 * {@code order_address_snapshot}。AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>🔴 <b>上游读不到与「查无此人」是两件事，本端口把它们分得开</b>：
 *
 * <ul>
 *   <li><b>上游不可达 / 报错</b> ⇒ 抛 503（人话写明「读不到」）。
 *       绝不返回空集合或空字符串冒充「上游一个数据都没有」——
 *       那个空值会被读成「这个人没有 PII」，而真相是「我们没读到」。</li>
 *   <li><b>上游正常但查无此人</b> ⇒ 返回 {@code known=false}（无记录 ≠ 无 PII）。</li>
 * </ul>
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * 上游仍按已认证成员关系解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>
 * （本服务 RLS + 上游 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code BuyerRiskScoreSourcePort} / crm 侧 {@code CustomerFactsPort} 逐字同构。
 */
@org.springframework.stereotype.Service
public class PiiAccessSourcePort {

  /** trade 服务默认地址。 */
  static final String TRADE_URL = "http://127.0.0.1:8085";

  /** crm 服务默认地址。 */
  static final String CRM_URL = "http://127.0.0.1:8086";

  /** 上游订单列表（trade）。 */
  static final String TRADE_ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游订单详情（trade）—— 取**订单上冻结的那一份地址快照**。 */
  static final String TRADE_ORDER_DETAIL_PATH = "/trade/sales-orders/detail";

  /** 上游买家邮件列表（crm）—— 买家姓名与邮箱。 */
  static final String CRM_BUYER_EMAILS_PATH = "/crm/buyer-emails";

  /** 上游接触记录（crm）—— 接触人 operatorId（SELF 成员范围要用）。 */
  static final String CRM_CONTACTS_PATH = "/crm/customer-contacts/contacts";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 100;

  /** 最多翻多少页（{@code 100 × 10 = 1000} 件）。翻页无界会把本服务的请求线程占满。 */
  static final int MAX_PAGES = 10;

  // ================================================================ 上游形状

  /** 一张订单的列表行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceOrderRow(
      String id, String orderNo, String orderSource, OffsetDateTime createdAt) {}

  /** 一页订单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceOrderPage(List<SourceOrderRow> rows, int page, int size, long total) {}

  /** 一张订单的收货地址快照（**本模块要脱敏的对象**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceAddress(
      String addressRole,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String companyName,
      String countryCode,
      String province,
      String city,
      String district,
      String postcode,
      String addressLine1,
      String addressLine2) {}

  /** 订单详情（trade 的 sales-orders/detail）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceOrderOverview(List<SourceAddress> addresses) {}

  /** 一个买家的邮箱行（crm）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceBuyerEmail(
      String buyerRef, String buyerName, String buyerEmail, String salesOrderNo) {}

  /** 一页买家邮箱（crm）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceBuyerEmailPage(
      List<SourceBuyerEmail> items, int page, int size, long totalElements, int totalPages) {}

  /** 一条接触记录（crm）—— 只要 operatorId 与 customerRef。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceContact(String customerRef, String customerName, String operatorId) {}

  /** 一页接触记录（crm）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceContactPage(
      List<SourceContact> items, int page, int size, long totalElements, int totalPages) {}

  // ================================================================ 结果形状

  /**
   * 一次上游读取的结果。
   *
   * @param known 上游**有没有这个主体**（false ⇒ 查无此人，**不是**「这个人没有 PII」）
   */
  public record SourceSubject(
      boolean known, String subjectRef, String subjectLabel, SourceAddress address, String note) {}

  /** 一个买家的联系方式（crm 侧）。 */
  public record SourceCustomer(
      boolean known,
      String buyerRef,
      String buyerName,
      String buyerEmail,
      String note) {}

  /** 上游读面结果（列表）。 */
  public record SourceOrders(List<SourceOrderRow> orders, long total, boolean truncated, String note) {}

  /** 上游买家读面结果（列表）。 */
  public record SourceCustomers(
      List<SourceCustomer> customers, long total, boolean truncated, String note) {}

  /**
   * 接触人判定结果。
   *
   * @param handledByActor 主体是否由本人经手；<b>null = 判不了</b>
   */
  public record SourceHandling(Boolean handledByActor, String note) {}

  // ================================================================ 客户端

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient trade;
  private final RestClient crm;

  public PiiAccessSourcePort(
      @Value("${TRADE_URL:" + TRADE_URL + "}") String tradeUrl,
      @Value("${CRM_URL:" + CRM_URL + "}") String crmUrl) {
    this.trade = client(tradeUrl, 10);
    this.crm = client(crmUrl, 10);
  }

  private static RestClient client(String url, int readTimeoutSeconds) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
        .build();
  }

  // ================================================================ trade（9 订单）

  /**
   * 拉一页订单列表。
   *
   * <p>🔴 **触到翻页上限时如实报 truncated**，不悄悄少给 —— 调用方要能知道
   * 「这一批订单只读到了前 N 件」。
   */
  public SourceOrders orders(String bearer, int page, int size) {
    List<SourceOrderRow> all = new ArrayList<>();
    long total = 0;
    for (int p = 0; p < MAX_PAGES; p++) {
      final int index = p;
      SourceOrderPage page1;
      try {
        page1 =
            trade
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(TRADE_ORDERS_PATH)
                            .queryParam("page", index)
                            .queryParam("size", Math.min(size, PAGE_SIZE))
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(SourceOrderPage.class);
      } catch (RestClientResponseException e) {
        throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单列表读不到");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        throw unavailable("trade 服务不可达：订单列表读不到");
      }
      if (page1 == null || page1.rows() == null) {
        throw unavailable("trade 服务没有返回订单列表");
      }
      for (SourceOrderRow row : page1.rows()) {
        if (row != null && row.id() != null) {
          all.add(row);
        }
      }
      total = page1.total();
      if (all.size() >= total || page1.rows().isEmpty()) {
        return new SourceOrders(all, total, false, "");
      }
    }
    return new SourceOrders(all, total, true, "订单读到的页数达到上限 " + MAX_PAGES + "，结果不完整");
  }

  /**
   * 读一张订单的收货地址快照（**买家 PII 的真值**）。
   *
   * <p>🔴 取的是**订单上冻结的那一份地址快照**（V99 的 {@code order_address_snapshot_frozen}
   * 触发器钉死），不是活地址：地址会变，下单当时的那一份才是「这个人当时寄到哪里」。
   */
  public SourceSubject orderFacts(String bearer, String orderId) {
    SourceOrderOverview overview;
    try {
      overview =
          trade
              .get()
              .uri(TRADE_ORDER_DETAIL_PATH + "/" + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SourceOrderOverview.class);
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        // 查无此人 —— 与「读不到」是两件事，**不抛 503**。
        return new SourceSubject(false, orderId, "", null, "订单不存在或不可见");
      }
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "调用方无权读取订单详情（trade 拒绝）：PII 读不到");
      }
      throw unavailable("trade 服务返回 " + code + "：订单详情读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：订单详情读不到");
    }
    if (overview == null || overview.addresses() == null) {
      throw unavailable("trade 服务没有返回订单地址快照");
    }
    SourceAddress shipping = null;
    for (SourceAddress address : overview.addresses()) {
      if (address == null) {
        continue;
      }
      if ("SHIPPING".equals(address.addressRole())) {
        shipping = address;
        break;
      }
    }
    if (shipping == null) {
      return new SourceSubject(false, orderId, "", null, "这张订单没有收货地址快照");
    }
    return new SourceSubject(
        true, orderId, PiiAccessRules.maskName(shipping.recipientName()), shipping, "");
  }

  // ================================================================ crm（15 客户）

  /** 拉一页买家邮件（crm）—— 买家姓名与邮箱。 */
  public SourceCustomers customers(String bearer, String buyerRef, int page, int size) {
    List<SourceCustomer> all = new ArrayList<>();
    long total = 0;
    for (int p = 0; p < MAX_PAGES; p++) {
      final int index = p;
      SourceBuyerEmailPage page1;
      try {
        var builder =
            crm.get()
                .uri(
                    uriBuilder -> {
                      uriBuilder
                          .path(CRM_BUYER_EMAILS_PATH)
                          .queryParam("page", index)
                          .queryParam("size", Math.min(size, PAGE_SIZE));
                      if (buyerRef != null && !buyerRef.isBlank()) {
                        uriBuilder.queryParam("buyerRef", buyerRef.trim());
                      }
                      return uriBuilder.build();
                    })
                .header("Authorization", bearer);
        page1 = builder.retrieve().body(SourceBuyerEmailPage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN, "调用方无权读取买家联系方式（crm 拒绝）：PII 读不到");
        }
        throw unavailable("crm 服务返回 " + code + "：买家联系方式读不到");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        throw unavailable("crm 服务不可达：买家联系方式读不到");
      }
      if (page1 == null || page1.items() == null) {
        throw unavailable("crm 服务没有返回买家邮件列表");
      }
      for (SourceBuyerEmail row : page1.items()) {
        if (row == null || row.buyerRef() == null || row.buyerRef().isBlank()) {
          continue;
        }
        all.add(
            new SourceCustomer(
                true,
                row.buyerRef(),
                PiiAccessRules.nz(row.buyerName()),
                PiiAccessRules.nz(row.buyerEmail()),
                ""));
      }
      total = page1.totalElements();
      if (all.size() >= total || page1.items().isEmpty()) {
        return new SourceCustomers(all, total, false, "");
      }
    }
    return new SourceCustomers(all, total, true, "买家读到的页数达到上限 " + MAX_PAGES + "，结果不完整");
  }

  /**
   * 读一个买家的联系方式（crm）—— 查无此人时返回 {@code known=false}。
   */
  public SourceCustomer customer(String bearer, String buyerRef) {
    SourceCustomers list = customers(bearer, buyerRef, 0, 1);
    for (SourceCustomer customer : list.customers()) {
      if (buyerRef.equals(customer.buyerRef())) {
        return customer;
      }
    }
    return new SourceCustomer(false, buyerRef, "", "", "查无此买家");
  }

  /**
   * 判定「这个主体是不是本人经手」（SELF 成员范围用）。
   *
   * <p>🔴 <b>判不了就返回 null，不返回 false</b>：false 是「确定不是本人经手」，
   * null 是「判不了」—— 两者在授权判定里的后果完全不同（前者拒绝，后者也拒绝，
   * 但**理由不同**，审计里必须能看出来）。
   *
   * <p>🔴 <b>ORDER 主体判不了</b>：trade 的 {@code sales_order} / {@code order_address_snapshot}
   * <b>没有经手人字段</b> ⇒ SELF 范围对订单一律拒绝，并在理由里写明「判不了」。
   */
  public SourceHandling customerHandledBy(String bearer, String buyerRef, UUID actorId) {
    List<SourceContact> contacts = new ArrayList<>();
    for (int p = 0; p < MAX_PAGES; p++) {
      final int index = p;
      SourceContactPage page1;
      try {
        page1 =
            crm
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(CRM_CONTACTS_PATH)
                            .queryParam("customerRef", buyerRef)
                            .queryParam("page", index)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(SourceContactPage.class);
      } catch (RestClientResponseException e) {
        throw unavailable("crm 服务返回 " + e.getStatusCode().value() + "：接触记录读不到");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        throw unavailable("crm 服务不可达：接触记录读不到");
      }
      if (page1 == null || page1.items() == null) {
        throw unavailable("crm 服务没有返回接触记录列表");
      }
      contacts.addAll(page1.items());
      if (contacts.size() >= page1.totalElements() || page1.items().isEmpty()) {
        break;
      }
    }
    if (contacts.isEmpty()) {
      // 没有接触记录 ⇒ **判不了**（不是「不是本人经手」）。
      return new SourceHandling(null, "这个买家没有任何接触记录，经手人判不了");
    }
    boolean handled = false;
    for (SourceContact contact : contacts) {
      if (contact == null || contact.operatorId() == null) {
        continue;
      }
      if (actorId != null && actorId.toString().equals(contact.operatorId().trim())) {
        handled = true;
        break;
      }
    }
    return new SourceHandling(handled, handled ? "本人经手" : "这个买家由他人经手");
  }

  // ================================================================ 工具

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
