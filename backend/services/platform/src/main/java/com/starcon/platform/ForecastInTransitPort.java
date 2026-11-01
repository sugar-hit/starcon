package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **7 库存（warehouse 服务，端口 8084）**的跨服务只读端口：在途净额（按目的仓 × SKU）。
 *
 * <p>蓝图 18.4.2.2 的关系句点名的上游含「7 库存」。补货建议要扣的「在途」有两个候选口径：
 *
 * <ul>
 *   <li>本端口（warehouse 的「在途净额」）—— <b>按目的仓 × SKU 聚合的在途量</b>，
 *       上游的类注释写得很清楚：「<b>缺口计算扣掉它才不会重复补货</b>」；
 *   <li>6 采购（trade 的采购单计划量 − 到货量）。
 * </ul>
 *
 * <p>🔴 <b>本模块只扣一次</b>：warehouse 的在途腿**已经包含采购发运腿**，
 * 两边都扣会重复扣减 ⇒ 建议量偏小 ⇒ 该补的不补。
 * ⇒ 补货建议扣的是<b>本端口的在途净额</b>；采购侧的未到货量**只作参考**展示，
 * 不参与扣减（依据里写明这一点）。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离是<b>两道</b>（platform 侧 RLS + 上游侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>：读不到在途时，本模块**不生成补货建议**，
 * 绝不拿 0 冒充「在途为 0」。
 */
@Service
public class ForecastInTransitPort {

  private final PlatformUpstreamClient client;

  public ForecastInTransitPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    this.client = PlatformUpstreamClient.to("warehouse 服务", url);
  }

  /** 上游一行在途净额。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NettingRow(
      UUID toWarehouseId,
      String toWarehouseCode,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal outstandingQuantity,
      long legCount) {}

  /** 上游在途净额页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NettingPage(
      long total, BigDecimal totalOutstandingQuantity, List<NettingRow> rows, String usageNote) {}

  /**
   * 拉全部在途净额（分页翻页有上限 —— 不能把上游当无限流接口用）。
   *
   * <p>⚠️ 上游的净额行是「目的仓 × SKU」，同一个 SKU 可能有多个目的仓 ⇒
   * 本端口**原样返回逐行事实**，按 SKU 求和由服务层做（服务层要能说出
   * 「在途是分布在几个仓的」）。
   */
  public List<NettingRow> netting(String bearer) {
    var rows = new ArrayList<NettingRow>();
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      final int current = page;
      NettingPage batch =
          client.get(
              uriBuilder -> {
                uriBuilder
                    .path("/warehouse/inventory/in-transit/netting")
                    .queryParam("page", current)
                    .queryParam("size", PlatformUpstreamClient.PAGE_SIZE);
                return uriBuilder;
              },
              bearer,
              NettingPage.class);
      var batchRows = batch.rows() == null ? List.<NettingRow>of() : batch.rows();
      rows.addAll(batchRows);
      if (batchRows.isEmpty()) break;
      if (rows.size() >= batch.total()) break;
      page++;
    }
    return rows;
  }
}
