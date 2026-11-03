package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * warehouse 服务（端口 8084）的**跨服务只读端口**：库存头寸清单（7.x）。
 *
 * <p>蓝图 18.1.3.1 写「上游 = 全部业务域」—— 库存是其中一格。
 * 库存余量由入库 / 出库 / 调拨 / 盘点推导，事实住在 warehouse 的库里，本端口只读它。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>列表字段是 {@code rows}，不是 {@code items}</b>。
 *
 * <p>⚠️ <b>{@code maxOnHand} 可能为 null</b> —— 「没配超储阈值」与「配了阈值但没超储」是两件事，
 * 原样搬运这个区分（导出成空串表示未设阈值，不写成 0）。
 */
@Service
public class WarehouseInventoryPort {

  private final PlatformUpstreamClient client;

  public WarehouseInventoryPort(
      @Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    this.client = PlatformUpstreamClient.to("warehouse 服务", url);
  }

  /** 上游一行库存头寸。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalanceRow(
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      boolean zeroStock,
      boolean thresholdConfigured,
      BigDecimal maxOnHand,
      boolean overstock) {}

  /** 上游库存总览页（列表字段是 {@code rows}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalancePage(List<BalanceRow> rows, long total, int page, int size) {}

  /** 拉一页在手库存。 */
  public BalancePage balances(int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/warehouse/inventory/overview")
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          return uriBuilder;
        },
        bearer,
        BalancePage.class);
  }
}
