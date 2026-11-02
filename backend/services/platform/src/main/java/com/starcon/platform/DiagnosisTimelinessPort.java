package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **6 采购 / 9 履约（trade 服务，端口 8085）**的跨服务只读端口：发货时效事实。
 *
 * <p>蓝图 18.4.2.3 的发货延迟归因要回答「这票货为什么晚了」。最硬的依据是
 * **逐段耗时与 SLA 状态**：订单→出库、出库→交接、发货超时秒数、承诺到货是否达成。
 * 这些事实住在 trade 的 {@code order_timeliness_fact} 表里（9.5.1.1 时效分析），
 * 本端口只读它，<b>不抄它</b>。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。
 *
 * <p>⚠️ <b>取不到就 503</b>，绝不返回空集合冒充「这票货没有延迟」——
 * 空集合在诊断页面上会长成「时效正常」，而真相是「这一格没读出来」。
 */
@Service
public class DiagnosisTimelinessPort {

  private final PlatformUpstreamClient client;

  public DiagnosisTimelinessPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = PlatformUpstreamClient.to("trade 服务", url);
  }

  /** 上游一行发货时效事实（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TimelinessFact(
      String id,
      String factNo,
      String salesOrderNo,
      String warehouseRef,
      String carrierCode,
      OffsetDateTime orderedAt,
      OffsetDateTime packedAt,
      OffsetDateTime handedOverAt,
      Long orderToPackSeconds,
      Long packToHandoverSeconds,
      Long orderToHandoverSeconds,
      OffsetDateTime shipSlaDeadlineAt,
      Long shipOverdueSeconds,
      String shipSlaState,
      OffsetDateTime promisedDeliveryAt,
      OffsetDateTime actualDeliveredAt,
      String promiseVsDeliveryState,
      Long promiseVsDeliverySeconds) {}

  /** 上游时效事实页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FactPage(List<TimelinessFact> items, int page, int size, long total, long totalPages) {}

  /**
   * 拉一页发货时效事实。
   *
   * @param keyword 上游的模糊匹配词（订单号 / 事实号）；本端口**不**假定它一定命中，
   *     服务层会按订单号在结果里再精确挑一遍。
   */
  public List<TimelinessFact> facts(String keyword, int page, int size, String bearer) {
    FactPage batch =
        client.get(
            uriBuilder -> {
              uriBuilder
                  .path("/trade/fulfillment/timeliness/facts")
                  .queryParam("keyword", keyword)
                  .queryParam("page", Math.max(page, 0))
                  .queryParam("size", Math.max(size, 1));
              return uriBuilder;
            },
            bearer,
            FactPage.class);
    return batch.items() == null ? List.<TimelinessFact>of() : batch.items();
  }

  /**
   * 翻页拉取一页发货时效事实（最多 {@link PlatformUpstreamClient#MAX_PAGES} 页）。
   *
   * <p>⚠️ 上游的 {@code keyword} 是模糊匹配：**可能命中也可能落空**。
   *   所以这里把命中与否交给服务层去**按订单号精确挑**，端口只负责如实搬运。
   */
  public List<TimelinessFact> factsAll(String keyword, String bearer) {
    var out = new ArrayList<TimelinessFact>();
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      var items = facts(keyword, page, PlatformUpstreamClient.PAGE_SIZE, bearer);
      out.addAll(items);
      if (items.isEmpty()) break;
      if (items.size() < PlatformUpstreamClient.PAGE_SIZE) break;
      page++;
    }
    return out;
  }
}
