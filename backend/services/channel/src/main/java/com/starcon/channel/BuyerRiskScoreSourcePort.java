package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.5.1.2 风险评分的<b>跨服务只读端口</b>：09 订单（trade 服务）与 11 售后（trade 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 trade 的
 * 授权 HTTP API</b>读，不直连对方库。与 {@link CompliancePrecheckSourcePort}（16.2.2.1）、
 * {@link PendingShipmentQueryPort}、{@link ReturnReceiptQueryPort} 同一手法。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 理由与合规预检一致：
 * 本模块的产出是<b>评分</b>，一条上游读面读不到时，正确答案是「这个维度<b>量不到</b>」
 * （不参与加权 + 归属模块 + 原因），而不是「整次评分失败」。
 * 把读不到折成 0 分是这类评分系统最致命的一种撒谎：界面会显示「退款率 0%」，
 * 而真相是「我们没查到退款数据」。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>端口号</b>：trade 是 <b>8085</b>。本文件**刻意不抄**
 * {@code PendingShipmentQueryPort.java:80} 与 {@code ReturnReceiptQueryPort.java:95}
 * 那个把 trade 写成 {@code 127.0.0.1:8082}（catalog 的端口）的既有 bug，
 * 也不抄 {@code SellableQuantityQueryPort.java:71} 把 warehouse 写成 8086 的那个。
 *
 * <p>🔴 <b>买家身份键的算法在这里现算</b>：{@code md5(utf8(姓名 || E'\x1f' || 电话 || E'\x1f' || 邮箱))}
 * —— 与 trade 的 {@code order_review_buyer_profile.buyer_key}（V149）和
 * {@code OrderInterceptService.buyerKey}（V153）<b>逐字同一公式</b>。
 * 分隔符用<b>不可见字符</b> {@code U+001F} 而不是 {@code '|'}：收件人字段由运营自由录入，
 * {@code '|'} 会让「Smith|John」与「Smith」「John」两组字段拼出同一个串 ——
 * 那是一次<b>跨买家的错误合并</b>，比不合单严重得多。
 * 本模块<b>刻意不抽公共类</b>：trade 侧那两个文件是别的车道的所有权，改它们会造成跨车道代码冲突；
 * 两处各自写死并互相注释指向，代价是同一段 md5 出现两次，换来并入时零冲突。
 */
@Service
public class BuyerRiskScoreSourcePort {

  /** 09 订单 / 11 售后（trade 服务）。 */
  static final String TRADE_URL = "http://127.0.0.1:8085";

  /** 买家画像（9.2.1.2 的租户自维护画像；带 buyer_key）。 */
  static final String BUYER_PROFILES_PATH = "/trade/order-review/buyer-profiles";

  /** 全渠道统一订单列表（9.1.1.1）。 */
  static final String SALES_ORDERS_PATH = "/trade/sales-orders/list";

  /** 订单全景详情（9.1.1.2）—— 收货地址快照（买家身份与地址模式的唯一来源）。 */
  static final String SALES_ORDER_DETAIL_PATH = "/trade/sales-orders/detail";

  /** 退款状态列表（11.3.1.2）—— 行里带 orderId。 */
  static final String REFUNDS_PATH = "/trade/refunds";

  /** 售后单主列表（11.1.1.1）—— 行里带 orderId 与 reasonCode。 */
  static final String AFTERSALES_CASES_PATH = "/trade/aftersales/cases";

  /** 9.2.2.2 风控信号登记（写）。 */
  static final String RISK_SIGNALS_PATH = "/trade/orders/risk-validation/signals";

  /** 单页条数上限（与 trade 侧各列表的 MAX_PAGE_SIZE 同一档）。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多读多少页。
   *
   * <p>🔴 <b>达到上限时结果标成「不完整」而不是悄悄截断</b>：截断出来的退款率会是一个
   * 看起来有数、实则缺了尾巴的分数 —— 那比「读不到」更危险。
   */
  static final int MAX_PAGES = 100;

  /** 买家身份键的分隔符（不可见字符 U+001F）。 */
  private static final char SEP_CHAR = '\u001F';

  // ================================================================ 结果形状

  /** trade 侧的买家画像一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceBuyerProfile(
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason) {}

  /** 买家画像读出结果。 */
  public record SourceBuyerProfiles(
      boolean available, List<SourceBuyerProfile> profiles, long total, String note) {}

  /** 一张订单的列表行（只要评分需要的几个字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceOrder(
      String orderId, String orderNo, String orderSource, OffsetDateTime createdAt) {}

  /** 订单列表读出结果。 */
  public record SourceOrders(boolean available, List<SourceOrder> orders, long total, String note) {}

  /**
   * 一张订单的收货地址事实。
   *
   * @param buyerKey 由收货地址快照三件套实算的买家身份键（与 trade 同一公式）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceOrderFacts(
      boolean available,
      String buyerKey,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String countryCode,
      String postcode,
      String city,
      String district,
      String addressLine1,
      String note) {}

  /** 退款读出结果：**只要「哪些订单发起过退款」**。 */
  public record SourceRefundRefs(
      boolean available, Set<String> refundedOrderIds, long total, String note) {}

  /** 售后单读出结果：**只要「哪些订单有欺诈退货（reason_code=FRAUD_RETURN）」**。 */
  public record SourceFraudCaseRefs(
      boolean available, Set<String> fraudReturnOrderIds, long total, String note) {}

  /** 回写 9.2.2.2 风控信号的结果。 */
  public record SignalWritebackResult(boolean succeeded, String note) {}

  private final RestClient client;

  public BuyerRiskScoreSourcePort(@Value("${TRADE_URL:" + TRADE_URL + "}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ 买家身份键

  /**
   * 买家身份键：与 trade 的 {@code order_review_buyer_profile.buyer_key}（V149）
   * 与 {@code OrderInterceptService.buyerKey}（V153）<b>逐字同一公式</b>。
   *
   * <p>必须一致，否则会出现「9.2.1.2 拉黑了这个人、16.5.1.2 判定不是黑名单买家」
   * 这种无法排查的分歧 —— 而这种分歧在真实场景里就是一次漏拦。
   */
  static String buyerKey(String name, String phone, String email) {
    try {
      var digest = java.security.MessageDigest.getInstance("MD5");
      var bytes =
          digest.digest(
              (nz(name) + SEP_CHAR + nz(phone) + SEP_CHAR + nz(email))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 不可用（买家身份键依赖它）", e);
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  // ================================================================ 买家画像

  /** 租户自维护的买家画像名册（**带 buyer_key**，是本模块最有价值的上游读面）。 */
  public SourceBuyerProfiles buyerProfiles(String bearer) {
    List<SourceBuyerProfile> all = new ArrayList<>();
    long total = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      BuyerProfilePage result;
      try {
        result =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(BUYER_PROFILES_PATH)
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(BuyerProfilePage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          return new SourceBuyerProfiles(
              false, List.of(), 0, "调用方无权读取买家画像（trade 拒绝）");
        }
        return new SourceBuyerProfiles(false, List.of(), 0, "trade 服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceBuyerProfiles(false, List.of(), 0, "trade 服务不可达");
      }
      if (result == null || result.items() == null) {
        return new SourceBuyerProfiles(false, List.of(), 0, "trade 服务没有返回买家画像列表");
      }
      for (BuyerProfileRow row : result.items()) {
        if (row == null || row.buyerKey() == null || row.buyerKey().isBlank()) continue;
        all.add(
            new SourceBuyerProfile(
                row.buyerKey().trim().toLowerCase(java.util.Locale.ROOT),
                nz(row.buyerName()),
                nz(row.buyerPhone()),
                nz(row.buyerEmail()),
                nz(row.buyerLevel()),
                row.blacklisted(),
                nz(row.blacklistReason())));
      }
      total = result.total();
      if (all.size() >= total || result.items().isEmpty()) {
        return new SourceBuyerProfiles(true, all, total, "");
      }
    }
    return new SourceBuyerProfiles(
        false,
        List.of(),
        total,
        "买家画像读到的页数达到上限 " + MAX_PAGES + "，结果不完整 —— 本轮不据此评分");
  }

  // ================================================================ 订单

  /** 统计窗口内的订单列表（**不按买家筛**：逐单实算买家身份键，见服务层）。 */
  public SourceOrders orders(String bearer, OffsetDateTime since) {
    List<SourceOrder> all = new ArrayList<>();
    long total = 0;
    // 🔴 **一律换算成 UTC 再拼进查询串**：ISO 串里的「+08:00」带一个 '+'，
    //   '+' 在查询串里是空格（URL 编码的老规矩）⇒ 上游会收到
    //   「2026-10-18T19:21:38.123 08:00」这种解析不了的字面量，400 掉整条查询。
    //   换算成 UTC 之后串里没有 '+'（形如 2026-10-18T19:21:38.123Z），任何上游都能解析。
    String sinceParam = since.withOffsetSameInstant(java.time.ZoneOffset.UTC).toString();
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      SalesOrderPage result;
      try {
        result =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(SALES_ORDERS_PATH)
                            .queryParam("orderedFrom", sinceParam)
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(SalesOrderPage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          return new SourceOrders(false, List.of(), 0, "调用方无权读取订单列表（trade 拒绝）");
        }
        return new SourceOrders(false, List.of(), 0, "trade 服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceOrders(false, List.of(), 0, "trade 服务不可达");
      }
      if (result == null || result.rows() == null) {
        return new SourceOrders(false, List.of(), 0, "trade 服务没有返回订单列表");
      }
      for (SalesOrderRow row : result.rows()) {
        if (row == null || row.id() == null) continue;
        all.add(
            new SourceOrder(
                row.id(),
                nz(row.orderNo()),
                nz(row.orderSource()),
                row.createdAt() != null ? row.createdAt() : row.submittedAt()));
      }
      total = result.total();
      if (all.size() >= total || result.rows().isEmpty()) {
        return new SourceOrders(true, all, total, "");
      }
    }
    return new SourceOrders(
        false, List.of(), total, "订单读到的页数达到上限 " + MAX_PAGES + "，结果不完整 —— 本轮不据此评分");
  }

  /**
   * 一张订单的收货地址事实（**买家身份与地址模式的唯一来源**）。
   *
   * <p>🔴 这里必须取<b>订单上冻结的那一份地址快照</b>（V99 的
   * {@code order_address_snapshot_frozen} 触发器钉死），不是活地址：地址会变，
   * 下单当时的那一份才是「这个人当时寄到哪里」。
   */
  public SourceOrderFacts orderFacts(String bearer, UUID orderId) {
    try {
      SalesOrderOverview result =
          client
              .get()
              .uri(SALES_ORDER_DETAIL_PATH + "/" + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SalesOrderOverview.class);
      if (result == null || result.addresses() == null) {
        return new SourceOrderFacts(
            false, "", "", "", "", "", "", "", "", "", "trade 服务没有返回订单地址快照");
      }
      SalesOrderAddress shipping = null;
      for (SalesOrderAddress address : result.addresses()) {
        if (address == null) continue;
        if ("SHIPPING".equals(address.addressRole())) {
          shipping = address;
          break;
        }
      }
      if (shipping == null) {
        return new SourceOrderFacts(
            false, "", "", "", "", "", "", "", "", "", "这张订单没有收货地址快照");
      }
      String name = nz(shipping.recipientName());
      String phone = nz(shipping.recipientPhone());
      String email = nz(shipping.recipientEmail());
      return new SourceOrderFacts(
          true,
          buyerKey(name, phone, email),
          name,
          phone,
          email,
          nz(shipping.countryCode()).toUpperCase(java.util.Locale.ROOT),
          nz(shipping.postcode()),
          nz(shipping.city()),
          nz(shipping.district()),
          nz(shipping.addressLine1()),
          "");
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        return new SourceOrderFacts(false, "", "", "", "", "", "", "", "", "", "订单不存在或不可见");
      }
      if (code == 401 || code == 403) {
        return new SourceOrderFacts(
            false, "", "", "", "", "", "", "", "", "", "调用方无权读取订单详情（trade 拒绝）");
      }
      return new SourceOrderFacts(
          false, "", "", "", "", "", "", "", "", "", "trade 服务返回 " + code);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SourceOrderFacts(false, "", "", "", "", "", "", "", "", "", "trade 服务不可达");
    }
  }

  // ================================================================ 退款

  /** 「哪些订单发起过退款」。**只认 orderId**，不认单号字符串。 */
  public SourceRefundRefs refundedOrderIds(String bearer) {
    Set<String> ids = new LinkedHashSet<>();
    long total = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      RefundPage result;
      try {
        result =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(REFUNDS_PATH)
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(RefundPage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          return new SourceRefundRefs(false, Set.of(), 0, "调用方无权读取退款数据（trade 拒绝）");
        }
        return new SourceRefundRefs(false, Set.of(), 0, "trade 服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceRefundRefs(false, Set.of(), 0, "trade 服务不可达");
      }
      if (result == null || result.rows() == null) {
        return new SourceRefundRefs(false, Set.of(), 0, "trade 服务没有返回退款列表");
      }
      for (RefundRow row : result.rows()) {
        if (row == null || row.orderId() == null) continue;
        ids.add(row.orderId().toString());
      }
      total = result.total();
      if (ids.size() >= total || result.rows().isEmpty()) {
        return new SourceRefundRefs(true, ids, total, "");
      }
    }
    return new SourceRefundRefs(
        false, Set.of(), total, "退款读到的页数达到上限 " + MAX_PAGES + "，结果不完整 —— 本轮不据此评分");
  }

  // ================================================================ 售后（欺诈退货）

  /**
   * 「哪些订单有以「欺诈退货」为由的售后单」。
   *
   * <p>🔴 <b>reason_code 是受控值域</b>（V156）：QUALITY_DEFECT / DESCRIPTION_MISMATCH /
   * SIZE_MISMATCH / LOGISTICS_DAMAGE / BUYER_REASON / MISTAKE_BUY / <b>FRAUD_RETURN</b>。
   * 本模块只取 {@code FRAUD_RETURN} 那一类 —— 它是「欺诈特征」的真来源。
   *
   * <p>🔴 <b>注意这不是「纠纷率」</b>：{@code case_type} 的受控值域里没有「纠纷」
   * （RETURN_REFUND / REFUND_ONLY / EXCHANGE / REISSUE / REPAIR 都不是平台纠纷），
   * 平台纠纷在 {@code platform_dispute}，而那张表<b>没有买家 / 订单 join 键</b>
   * ⇒ 「纠纷率」这一维度本模块<b>量不到</b>（见能力声明）。
   */
  public SourceFraudCaseRefs fraudReturnOrderIds(String bearer) {
    Set<String> ids = new LinkedHashSet<>();
    long total = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      AftersalesPage result;
      try {
        result =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(AFTERSALES_CASES_PATH)
                            .queryParam("page", pageIndex)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(AftersalesPage.class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          return new SourceFraudCaseRefs(
              false, Set.of(), 0, "调用方无权读取售后单数据（trade 拒绝）");
        }
        return new SourceFraudCaseRefs(false, Set.of(), 0, "trade 服务返回 " + code);
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new SourceFraudCaseRefs(false, Set.of(), 0, "trade 服务不可达");
      }
      if (result == null || result.rows() == null) {
        return new SourceFraudCaseRefs(false, Set.of(), 0, "trade 服务没有返回售后单列表");
      }
      for (AftersalesRow row : result.rows()) {
        if (row == null || row.orderId() == null) continue;
        if (!"FRAUD_RETURN".equals(row.reasonCode())) continue;
        ids.add(row.orderId().toString());
      }
      total = result.total();
      if (ids.size() >= total || result.rows().isEmpty()) {
        return new SourceFraudCaseRefs(true, ids, total, "");
      }
    }
    return new SourceFraudCaseRefs(
        false,
        Set.of(),
        total,
        "售后单读到的页数达到上限 " + MAX_PAGES + "，结果不完整 —— 本轮不据此评分");
  }

  // ================================================================ 回写 9.2.2.2

  /**
   * 把一条比率信号回写到 9.2.2.2 的风控信号表。
   *
   * <p>🔴 <b>回写失败不许阻塞本模块交付</b>：这个端点是**用户权限端点**
   * （要 {@code trade.orders.update} 权限、转发调用方自己的 bearer），
   * <b>不是签名的服务间内部 API</b> —— 调用方是否真有那个 trade 权限<b>未经运行时验证</b>。
   * 所以失败时返回 {@code succeeded=false} + 人话原因，由调用方记成「未回写」。
   *
   * <p>🔴 {@code signalSource} 只能用 {@code TENANT_MAINTAINED}：
   * trade 枚举里的 {@code MODULE_15_CUSTOMER_PROFILE} 与 {@code MODULE_16_RISK_LINKAGE}
   * 是<b>别的模块的预留槽</b>，占用它们等于抢别人的登记位。
   */
  public SignalWritebackResult writeSignal(
      String bearer,
      String signalCode,
      String subjectKey,
      java.math.BigDecimal measuredValue,
      String sourceReference,
      OffsetDateTime capturedAt,
      String note) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("signalCode", signalCode);
    body.put("subjectKind", "BUYER_FINGERPRINT");
    body.put("subjectKey", subjectKey);
    body.put("measuredValue", measuredValue);
    body.put("signalSource", "TENANT_MAINTAINED");
    body.put("sourceReference", sourceReference);
    body.put("capturedAt", capturedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC).toString());
    body.put("note", note);
    try {
      client
          .post()
          .uri(RISK_SIGNALS_PATH)
          .header("Authorization", bearer)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return new SignalWritebackResult(true, "");
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new SignalWritebackResult(
            false, "调用方没有 trade.orders.update 权限，信号未回写（trade 拒绝）");
      }
      if (code == 400) {
        return new SignalWritebackResult(false, "trade 拒绝了这条信号（形状不合），信号未回写");
      }
      return new SignalWritebackResult(false, "trade 服务返回 " + code + "，信号未回写");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SignalWritebackResult(false, "trade 服务不可达，信号未回写");
    }
  }

  // ================================================================ 内部形状

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record BuyerProfileRow(
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record BuyerProfilePage(List<BuyerProfileRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SalesOrderRow(
      String id,
      String orderNo,
      String orderSource,
      OffsetDateTime createdAt,
      OffsetDateTime submittedAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SalesOrderPage(
      List<SalesOrderRow> rows, int page, int size, long total) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SalesOrderAddress(
      String addressRole,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String countryCode,
      String city,
      String district,
      String postcode,
      String addressLine1) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SalesOrderOverview(List<SalesOrderAddress> addresses) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RefundRow(String orderId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RefundPage(List<RefundRow> rows, int page, int size, long total) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AftersalesRow(String orderId, String reasonCode) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AftersalesPage(List<AftersalesRow> rows, int page, int size, long total) {}
}
