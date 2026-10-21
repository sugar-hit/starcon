package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 外部货源平台采集适配器的<b>共用底座</b>：接入登记的读取、HTTP 调用、错误映射与数字解析。
 *
 * <p>刻意**不**做成一个「万能 vendor」：平台标识、请求路径、报文形状与默认币种仍由每个 vendor 各自实现
 * （{@link Alibaba1688SourceGateway} / {@link YiwuGouSourceGateway}），
 * 这里只收拢那些**每个 vendor 都一样、且错了会静默出事**的部分：
 *
 * <ul>
 *   <li><b>接入登记的读取</b>：凭证**引用**来自 {@code supplier_source_collector_endpoint}（租户数据），
 *       本仓不持有任何明文凭证（AGENTS.md 安全条）；
 *   <li><b>超时有上界</b>：外部平台不可达时要有明确的时间上界，而不是让一次同步无限期挂着；
 *   <li><b>错误显性</b>：HTTP 错误与网络错误一律转成 {@link SupplierSourceGateway.SourceUnavailableException}，
 *       带上平台给的信息，绝不返回一个空批次冒充「这个档口一个货都没有」；
 *   <li><b>数字解析不丢精度</b>：价格与起订量一律 {@link BigDecimal}，禁 float / double。
 * </ul>
 */
abstract class HttpSupplierSourceGateway implements SupplierSourceGateway {

  /** 价格刻度：与 DB 的 numeric(19,4) 逐字对齐。 */
  static final int PRICE_SCALE = 4;

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  static final int QTY_SCALE = 6;

  protected final DSLContext sql;
  private final RestClient client;
  private final ObjectMapper json;

  protected HttpSupplierSourceGateway(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时 10 秒：一次采集是一个列表请求，比单条读慢，但仍然必须有上界。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /** 该 vendor 的采集请求路径（挂在租户登记的网关地址之后）。 */
  protected abstract String collectPath();

  /** 该平台的默认币种（响应没带币种时用；这是**平台的事实**，不是猜的）。 */
  protected abstract String defaultCurrency();

  /** 采集失败时的人话说明（各 vendor 措辞不同，因为缺口不同）。 */
  @Override
  public abstract String wiringRequirement();

  /** 已登记的采集接入（当前 RLS 租户）。未登记返回 null。 */
  protected Endpoint endpoint() {
    var row =
        sql.fetchOne(
            "select base_url,credential_ref from supplier_source_collector_endpoint"
                + " where platform = ? and status = 'ACTIVE' and wired",
            platform());
    return row == null
        ? null
        : new Endpoint(row.get("base_url", String.class), row.get("credential_ref", String.class));
  }

  @Override
  public boolean wired() {
    return endpoint() != null;
  }

  /** 已登记的采集接入。 */
  protected record Endpoint(String baseUrl, String credentialRef) {}

  /**
   * 发一次采集请求并把响应体读成字符串。
   *
   * <p>失败一律 {@link SupplierSourceGateway.SourceUnavailableException}：平台返回 4xx/5xx 与网络不可达都是
   * 「这次没采到」，必须让服务层落 FAILED 批次并写明原因，而不是静默返回一个空批次。
   */
  protected String postCollect(Endpoint endpoint, Object payload) {
    try {
      String body =
          client
              .post()
              .uri(endpoint.baseUrl() + collectPath())
              // 鉴权头由 credential_ref 指向的 secret 在运行期注入（本仓不持有明文凭证，
              // 因此这里只传引用名）—— 与 Alibaba1688SupplierAdapter 的代发下单同一纪律。
              .header("Authorization", "Bearer " + endpoint.credentialRef())
              .body(payload)
              .retrieve()
              .body(String.class);
      if (body == null || body.isBlank()) {
        throw new SupplierSourceGateway.SourceUnavailableException(
            displayName() + " 返回了空响应体，这次没有采到任何货源");
      }
      return body;
    } catch (RestClientResponseException e) {
      throw new SupplierSourceGateway.SourceUnavailableException(
          displayName() + " 返回 HTTP " + e.getStatusCode().value() + "，这次采集没有拿到货源");
    } catch (RestClientException e) {
      throw new SupplierSourceGateway.SourceUnavailableException(
          displayName() + " 暂时不可达（" + e.getMessage() + "），这次采集没有拿到货源");
    }
  }

  /** 把响应体读成指定形状。 */
  protected <T> T parse(String body, Class<T> shape) {
    try {
      return json.readValue(body, shape);
    } catch (Exception e) {
      throw new SupplierSourceGateway.SourceUnavailableException(
          displayName() + " 返回的报文无法解析（" + e.getMessage() + "），这次采集没有拿到货源");
    }
  }

  /**
   * 解析价格区间：外部可能只给一个价（此时上下限相同），也可能给反了的区间（**跳过并写明原因**）。
   *
   * <p>返回 null 表示这条数据不能用，调用方负责记入 skipped。
   */
  protected static BigDecimal[] priceRange(String rawMin, String rawMax) {
    BigDecimal min = decimalOrNull(rawMin);
    BigDecimal max = decimalOrNull(rawMax);
    if (min == null) {
      max = null;
    }
    if (max == null) {
      max = min;
    }
    if (min == null || max == null) {
      return null;
    }
    min = min.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    max = max.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    if (min.signum() <= 0) {
      return null;
    }
    if (max.compareTo(min) < 0) {
      // 区间写反：不是「取小的那个」，是**这条数据有问题**。
      return null;
    }
    return new BigDecimal[] {min, max};
  }

  /** 解析起订量。非法返回 null（调用方记入 skipped）。 */
  protected static BigDecimal quantity(String raw) {
    BigDecimal value = decimalOrNull(raw);
    if (value == null || value.signum() <= 0) {
      return null;
    }
    return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 字符串 → BigDecimal；不是数字就返回 null（不抛 —— 坏数据由调用方记入 skipped）。 */
  protected static BigDecimal decimalOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 组装批次结果（items 与 skipped 都是不可变列表）。 */
  protected static CollectedBatch batch(List<CollectedItem> items, List<String> skipped) {
    return new CollectedBatch(new ArrayList<>(items), new ArrayList<>(skipped));
  }
}
