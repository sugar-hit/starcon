package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **14 广告（channel）**事实的**跨服务只读端口**（channel 服务，端口 8083）。
 *
 * <p>蓝图 13.1.1.1 的上游里写着「14 广告」。广告花费的真值在 channel 的
 * {@code GET /channel/ad-reports/rows}（14.3.1.1 活动报表）。
 *
 * <p>⚠️ <b>口径纪律</b>：本端口取的是<b>花费（spend）</b>，**不是销售额（sales）**。
 * 销售额是收入侧的事（12 财务应收 / 9 订单），把它当广告侧的口径会把一笔收入记成广告花费。
 *
 * <p>⚠️ 上游**按币种分开**（channel 侧没有汇率源）：USD 与 EUR 的花费加在一起不是任何一个金额。
 * 本服务原样保留上游的币种，**不跨币种相加**。
 */
@Service
public class ReportingChannelPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingChannelPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = ReportingUpstreamClient.to("channel 服务", url);
  }

  /** 上游一行广告报表（只声明本服务真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      String platformName,
      String shopName,
      String externalCampaignId,
      BigDecimal spend,
      LocalDate metricFrom,
      LocalDate metricTo) {}

  /** 上游一页广告报表。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdPage(List<AdRow> items, int page, int size, long total) {}

  /**
   * 拉一页广告花费。
   *
   * <p>分组维度固定 {@code CAMPAIGN}（按活动）：按活动分组才有「一笔花费对应一个对象」的粒度，
   * 按天分组会把同一个活动拆成很多条。
   */
  public AdPage adRows(LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-reports/rows")
                .queryParam("dimension", "CAMPAIGN")
                .queryParam("granularity", "DAY")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdPage.class);
  }
}
