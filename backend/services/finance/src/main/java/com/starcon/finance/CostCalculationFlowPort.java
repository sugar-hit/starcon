package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.4.1.2 成本计算执行的**跨服务只读端口**：库存流水（warehouse 7.1.2.1）。
 *
 * <p>蓝图 12-finance.md：「输入：库存流水（7.1.2.1）；计价方法；成本层数据；计算期间。」，
 * 关系里写着「<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，
 * <b>重算不能写库存</b>」。
 *
 * <p>🔴 <b>为什么必须走 HTTP 而不是直接查 warehouse 的表</b>：
 *
 * <ul>
 *   <li>AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图；
 *   <li>「数量账在 warehouse」意味着**数量是 warehouse 的真相** ——
 *       本模块只把出库量**读**过来算价值，一个字节都不回写；
 *   <li>台账（7.1.2.1）是 append-only 的，且已被 REVOKE UPDATE,DELETE ——
 *       即使本模块想写也写不进去，这正是我们想要的。
 * </ul>
 *
 * <p>🔴 <b>读的是「出库」而不是「全部流水」</b>：蓝图点名的三类是销售出库、发货、
 * 平台仓转出 —— 它们在 warehouse 的 {@code inventory_transaction.kind} 里对应
 * {@code SHIPMENT}（出库 / 发货）与 {@code TRANSFER_OUT}（调拨出 / 平台仓转出）。
 * 入库、预占、释放、调整**不是**成本结转事件，不进本模块。
 *
 * <p>🔴 <b>上游读不到一律 503</b>，理由写明「读不到」而不是「这个期间没有出库」——
 * 前者说明你看到的成本是假的，后者说明库存确实没卖。
 */
@Service
public class CostCalculationFlowPort {

  /** 上游只读形状：{@code GET /warehouse/inventory/ledger}（7.1.2.1 库存流水台账）。 */
  static final String LEDGER_PATH = "/warehouse/inventory/ledger";

  /** 分录下钻：{@code GET /warehouse/inventory/ledger/{transactionId}}。 */
  static final String DRILL_PATH = "/warehouse/inventory/ledger/{transactionId}";

  /**
   * 上游 JSON 的解析器。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成
   * {@code double}，于是上游的 {@code 12.3456} 变成 {@code 12.345600000000001} ——
   * 数量被悄悄改了一位（成本金额也会跟着错）。
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 一笔库存业务（台账列表行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlowRow(
      UUID transactionId,
      @JsonIgnoreProperties(ignoreUnknown = true) String kind,
      @JsonIgnoreProperties(ignoreUnknown = true) String refType,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID refId,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime occurredAt,
      @JsonIgnoreProperties(ignoreUnknown = true) Long entryCount) {}

  /** 一条带符号的库存分录。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlowEntry(
      UUID entryId,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID skuId,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal onHandDelta) {}

  /** 台账页（本模块只取 rows 与 total）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlowPage(
      @JsonIgnoreProperties(ignoreUnknown = true) List<FlowRow> rows,
      @JsonIgnoreProperties(ignoreUnknown = true) Long total) {}

  /** 分录下钻响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlowDrill(
      @JsonIgnoreProperties(ignoreUnknown = true) FlowRow transaction,
      @JsonIgnoreProperties(ignoreUnknown = true) List<FlowEntry> entries) {}

  private final RestClient warehouse;

  public CostCalculationFlowPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String warehouseUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.warehouse =
        RestClient.builder()
            .baseUrl(warehouseUrl)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(
                    new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一段期间的库存流水（只取出库类业务）。
   *
   * @param from 期间起（含）
   * @param to 期间止（不含）：左闭右开，避免相邻区间在边界那一瞬重复计数
   * @param kinds 要取的 {@code kind}（本模块固定 SHIPMENT + TRANSFER_OUT）
   * @param bearer 调用方自己的 bearer（转发它，租户由 warehouse 按成员关系解析）
   * @param page 页码，从 0 开始
   * @param size 每页行数
   */
  public FlowPage ledger(
      OffsetDateTime from, OffsetDateTime to, List<String> kinds, String bearer, int page, int size) {
    try {
      FlowPage result =
          warehouse
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(LEDGER_PATH)
                          .queryParam("from", from)
                          .queryParam("to", to)
                          .queryParam("kinds", kinds.toArray(new String[0]))
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(FlowPage.class);
      if (result == null) {
        throw unavailable("warehouse 服务没有返回库存流水（期间 " + from + " ~ " + to + "）");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游库存流水（需要 warehouse 侧的 warehouse.inventory.read 权限）",
            e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：库存流水读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达：库存流水读不到");
    }
  }

  /**
   * 下钻一笔业务的全部分录（取 SKU 级出库量）。
   *
   * @param bearer 调用方自己的 bearer
   */
  public List<FlowEntry> drill(UUID transactionId, String bearer) {
    try {
      FlowDrill result =
          warehouse
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(DRILL_PATH.replace("{transactionId}", transactionId.toString()))
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(FlowDrill.class);
      if (result == null) {
        throw unavailable("warehouse 服务没有返回库存分录（流水 " + transactionId + "）");
      }
      return result.entries() == null ? List.of() : result.entries();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游库存分录（需要 warehouse 侧的 warehouse.inventory.read 权限）",
            e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：库存分录读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达：库存分录读不到");
    }
  }

  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
