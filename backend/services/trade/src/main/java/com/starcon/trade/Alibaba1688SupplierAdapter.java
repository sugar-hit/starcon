package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 供应商下单 · <b>1688 开放平台</b>实现（默认<b>未接线</b>）。
 *
 * <p>蓝图职责句点名「向 1688 等供应商代发平台下单」，所以这个 vendor 的适配器
 * 真实存在、报文结构也按 1688 开放平台的代发下单形状写；但它
 * <b>默认未接线</b>，原因是仓库里没有它的接入信息：
 *
 * <ul>
 *   <li>没有登记 base_url（不同部署/不同区域的开放平台网关地址不同）；</li>
 *   <li>没有凭证引用 —— 1688 开放平台要 appKey/appSecret + 商家授权 accessToken，
 *       这些必须来自 secret manager（AGENTS.md 安全条：任何 secret 不入仓库）；</li>
 *   <li>没有做过一次真实联调，因此**不能声称它可用</b>。</li>
 * </ul>
 *
 * <p>因此未登记时 {@link #place} 抛 {@link SupplierOrderException}（503），
 * 服务层据此把这张代发单置为 FAILED 并落一条 ORDER_FAILED 告警。
 * 它<b>绝不</b>在未接线时返回一个编造的 supplierOrderNo —— 那会让界面显示
 * 「供应商已接单」而供应商那边根本没有这张单，是本模块最危险的一种假数据。
 *
 * <p>接上之后要改的只有 {@link #postToSupplier} 里的报文与鉴权，
 * 服务层与统一接口一个字都不用动 —— 这正是「每个 vendor 独立包」的意义。
 */
@Service
public class Alibaba1688SupplierAdapter implements SupplierOrderGateway {

  private static final Logger log = LoggerFactory.getLogger(Alibaba1688SupplierAdapter.class);

  private final DSLContext sql;
  private final RestClient client;

  public Alibaba1688SupplierAdapter(DSLContext sql) {
    this.sql = sql;
    // 连接/读超时与 BomVersionPort 同一档：供应商平台不可达时要有明确的上界，
    // 而不是让一张代发单无限期挂在 DISPATCHED。
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String vendorCode() {
    return "ALIBABA_1688";
  }

  @Override
  public String displayName() {
    return "1688 开放平台（未接线）";
  }

  @Override
  public boolean wired() {
    // 接线与否是**租户数据**（fulfillment_dropship_supplier_endpoint），
    // 不是代码常量：同一个适配器对没登记的租户未接线、对登记了的租户已接线。
    return endpoint() != null;
  }

  @Override
  public String wiringRequirement() {
    return "尚未登记 1688 开放平台网关地址与凭证引用";
  }

  @Override
  public SupplierOrderException unavailable(String reason) {
    return new SupplierOrderException(reason);
  }

  @Override
  public SupplierOrderAck place(PlacementRequest request) {
    var endpoint = endpoint();
    if (endpoint == null) {
      // 纪律：不伪造成功。显式失败 → 服务层落 ORDER_FAILED 告警 → 运营看得见。
      throw unwired();
    }
    // 报文按 1688 代发下单的形状组装；鉴权头由 credential_ref 指向的
    // secret 在运行期注入（本仓不持有明文凭证，因此这里只传引用名）。
    Map<String, Object> payload = Map.of(
        "orderNo", request.orderNo(),
        "supplierCode", request.supplierCode(),
        "currency", request.currencyCode(),
        "receiver", Map.of(
            "name", request.recipient().name(),
            "phone", request.recipient().phone(),
            "country", request.recipient().countryCode(),
            "province", request.recipient().province(),
            "city", request.recipient().city(),
            "district", request.recipient().district(),
            "address", request.recipient().addressLine1(),
            "address2", request.recipient().addressLine2(),
            "postcode", request.recipient().postcode()),
        "items", request.lines().stream()
            .map(l -> Map.of(
                "skuRef", l.skuRef(),
                "name", l.skuName(),
                "spec", l.specSnapshot(),
                "quantity", l.quantity().toPlainString(),
                "supplierSkuRef", l.supplierSkuRef()))
            .toList());
    try {
      SupplierAckBody body =
          client
              .post()
              .uri(endpoint.baseUrl() + "/open/orders")
              .header("Authorization", "Bearer " + endpoint.credentialRef())
              .body(payload)
              .retrieve()
              .body(SupplierAckBody.class);
      if (body == null || body.supplierOrderNo() == null || body.supplierOrderNo().isBlank()) {
        throw new SupplierOrderException(
            "1688 开放平台没有返回供应商单号：这单是否真的下出去了无法确认，"
                + "本模块不会用空值当作已接单");
      }
      if (body.outOfStock() != null && body.outOfStock()) {
        // 蓝图点名的「无货」：显式抛出，服务层落 NO_STOCK 告警。
        throw new SupplierOrderGateway.SupplierOutOfStockException(
            blankTo(body.message(), "1688 返回该单无可供商品"));
      }
      if (!"ACCEPTED".equals(body.status())) {
        throw new SupplierOrderGateway.SupplierRejectedException(
            blankTo(body.message(), "1688 返回状态 " + body.status()));
      }
      return new SupplierOrderAck(
          body.supplierOrderNo(),
          body.actualPurchaseAmount(),
          body.actualShippingAmount(),
          blankTo(body.trackingNo(), ""),
          blankTo(body.message(), ""));
    } catch (RestClientResponseException e) {
      log.warn("1688 代发下单返回 {}", e.getStatusCode().value());
      throw new SupplierOrderGateway.SupplierRejectedException(
          "1688 开放平台返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      log.warn("1688 开放平台不可达：{}", e.getMessage());
      throw unavailable("1688 开放平台暂时不可达（" + e.getMessage() + "）");
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** 已登记的端点（当前 RLS 租户）。未登记返回 null。 */
  private Endpoint endpoint() {
    var row =
        sql.fetchOne(
            "select base_url,credential_ref,status,wired"
                + " from fulfillment_dropship_supplier_endpoint"
                + " where vendor_code = 'ALIBABA_1688' and status = 'ACTIVE' and wired");
    return row == null ? null : new Endpoint(row.get("base_url", String.class),
        row.get("credential_ref", String.class));
  }

  private record Endpoint(String baseUrl, String credentialRef) {}

  /** 1688 回执里本模块真正用到的字段。 */
  record SupplierAckBody(
      String supplierOrderNo,
      String status,
      String trackingNo,
      String message,
      Boolean outOfStock,
      BigDecimal actualPurchaseAmount,
      BigDecimal actualShippingAmount) {}
}