package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 读取上游 **6 采购（3.2.2 到货与逾期）**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.1.2.1 的输入写的是「采购收货确认（8.2.1.1）」，上游写的是「6 采购」。 本端口读的是
 * <b>trade 的到货跟踪（6.3.2.2）</b>，理由是它才是「采购收货确认」在采购侧的**那一半**：
 *
 * <ul>
 *   <li><b>warehouse 的 8.2.1.1 收货单</b>记的是「货到了没有」：SKU × 数量 × 批次 × 库位，
 *       **它没有供应商、没有单价、没有币种**（收货单的能力声明里明写「采购来源当前不接受」）。
 *       ⇒ 拿收货单直接推应付，等于凭空发明金额 —— 蓝图加粗句「不能仅凭下单或收货统一推导」说的正是这件事。
 *   <li><b>trade 的到货跟踪</b>记的是「哪张采购单、哪个供应商、什么单价、什么币种、到了多少」，
 *       并且**已经有一张 {@code purchase_arrival_payable_confirmation}（应付部分确认）**：
 *       金额 = 单价快照 × 本次到货数量，由 DB 算出，调用方传不进来。
 *       它的状态恒为「待财务确认」、生效金额恒为 0 ——
 *       **trade 侧明写「财务确认走独立动作 + 独立权限」，且下游 finance 服务此前不存在**。
 * </ul>
 *
 * <p>⇒ 本模块与上游的接缝就是这张表：<b>读</b>待财务确认的应付部分确认 → 建本服务的应付单 →
 * <b>写回</b>确认（带上本服务的应付单号）。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code TradeReceivablePort} 逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有数据」—— 那会让现场看到 0 张应付，照着空名单以为这批货根本没到。
 */
@Service
public class PurchaseArrivalPort {

  /** 上游只读形状：{@code GET /trade/purchase-arrivals}（6.3.2.2 到货与逾期）。 */
  static final String ARRIVALS_PATH = "/trade/purchase-arrivals";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个大租户能把本服务的请求线程占满。触到上限时<b>如实报 truncated</b>。
   */
  static final int MAX_PAGES = 25;

  /** 上游「待财务确认」的状态值。 */
  static final String PENDING_FINANCE = "PENDING_FINANCE_CONFIRM";

  /** 上游一行到货跟踪（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeArrivalRow(
      UUID id,
      String trackingNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String purchaseOrderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID supplierRefId,
      @JsonIgnoreProperties(ignoreUnknown = true) String supplierCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String supplierName,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) String status,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal pendingPayableAmount) {}

  /** 上游一页到货跟踪。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeArrivalPage(List<TradeArrivalRow> rows, int page, int size, long total) {}

  /** 上游一笔应付部分确认。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradePayableConfirmation(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String confirmationNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String confirmationStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal confirmationAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal arrivedQty,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal effectiveAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) String financeReference,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime createdAt) {}

  /** 上游到货跟踪详情。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeArrivalDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) TradeArrivalRow head,
      @JsonIgnoreProperties(ignoreUnknown = true) List<TradePayableConfirmation> payableConfirmations) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code createdAt} 等时间字段），
   * 以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本模块撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public PurchaseArrivalPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次批量扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游到货跟踪。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public TradeArrivalPage arrivals(int page, String bearer) {
    try {
      TradeArrivalPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ARRIVALS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeArrivalPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回到货跟踪列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游到货跟踪事实（需要 trade 侧的 trade.purchase.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：到货跟踪事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 拉一张到货跟踪详情（含它的应付部分确认）。 */
  public TradeArrivalDetail arrival(UUID id, String bearer) {
    try {
      TradeArrivalDetail result =
          client
              .get()
              .uri(ARRIVALS_PATH + "/" + id)
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeArrivalDetail.class);
      if (result == null) throw unavailable("trade 服务没有返回到货跟踪详情");
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游到货跟踪事实（需要 trade 侧的 trade.purchase.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：到货跟踪详情读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /**
   * 写回「财务已确认」并带上本服务的应付单号。
   *
   * <p>⚠️ 上游把这一动作的权限单列为 {@code trade.purchase.finance} ——
   * 「财务确认走独立动作 + 独立权限」是上游自己写死的纪律，本端口只是转发调用方的 bearer。
   */
  public void confirmPayable(UUID arrivalId, UUID confirmationId, String financeReference, String bearer) {
    try {
      client
          .post()
          .uri(ARRIVALS_PATH + "/" + arrivalId + "/payable-confirmations/" + confirmationId + "/confirm")
          .header("Authorization", bearer)
          .body(new FinanceReference(financeReference))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "上游要求独立的财务确认权限（trade.purchase.finance），当前会话没有",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：应付确认写回失败");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 写回体：只带本服务的应付单号。 */
  record FinanceReference(String financeReference) {}

  /**
   * 把一笔「待财务确认」的应付部分确认压成上游事实形状。
   *
   * <p>⚠️ **金额、币种、数量、单价全部取上游的值**，本模块一个都不自己算 ——
   * 上游的金额是 DB 按「单价快照 × 本次到货数量」算出来的，改动它就是篡改依据。
   */
  public static PayableSourceFact toFact(
      TradeArrivalRow arrival, TradePayableConfirmation confirmation) {
    String name =
        arrival.supplierName() == null || arrival.supplierName().isBlank()
            ? "未知供应商"
            : arrival.supplierName();
    String currency =
        confirmation.currency() != null && !confirmation.currency().isBlank()
            ? confirmation.currency()
            : arrival.currency();
    String description =
        "到货应付部分确认 "
            + confirmation.confirmationNo()
            + "（到货单 "
            + arrival.trackingNo()
            + "）";
    return new PayableSourceFact(
        confirmation.confirmationNo(),
        confirmation.id(),
        PayableModels.CounterpartyType.SUPPLIER,
        arrival.supplierRefId(),
        name,
        currency,
        confirmation.confirmationAmount(),
        confirmation.arrivedQty(),
        confirmation.unitPrice(),
        description,
        confirmation.createdAt(),
        "由 6.3.2.2 到货跟踪的应付部分确认导入");
  }

  /** 把上游一页到货跟踪里「待财务确认」的应付部分确认逐个取出来。 */
  public static List<TradePayableConfirmation> pendingConfirmations(TradeArrivalDetail detail) {
    var out = new ArrayList<TradePayableConfirmation>();
    if (detail.payableConfirmations() == null) return out;
    for (var confirmation : detail.payableConfirmations()) {
      if (PENDING_FINANCE.equals(confirmation.confirmationStatus())) out.add(confirmation);
    }
    return out;
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
