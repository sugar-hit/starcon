package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **7 库存（warehouse）**事实的**跨服务只读端口**（warehouse 服务，端口 8084）。
 *
 * <p>蓝图 13.1.1.1 的上游里写着「7 库存」。在手库存的真值在 warehouse 的
 * {@code GET /warehouse/inventory/overview}。
 *
 * <p>⚠️ **上游只给数量，不给金额**：{@code inventory_balance} 里只有 {@code on_hand /
 * reserved / available} 这样的数量列，**没有成本列**。所以「库存总额」必须由
 * 「在手数量 × 成本单价」得到，而成本单价归 <b>catalog 5.2.1.1 成本构成</b>所有 ——
 * 由 {@link ReportingCatalogPort} 读。两边都是授权 HTTP API，**不跨库**。
 */
@Service
public class ReportingWarehousePort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingWarehousePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    this.client = ReportingUpstreamClient.to("warehouse 服务", url);
  }

  /** 上游一行库存余额（只声明本服务真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalanceRow(
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /** 上游一页库存余额。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalancePage(List<BalanceRow> rows, long total, int page, int size) {}

  /** 拉一页在手库存。 */
  public BalancePage balances(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/warehouse/inventory/overview")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        BalancePage.class);
  }
}
