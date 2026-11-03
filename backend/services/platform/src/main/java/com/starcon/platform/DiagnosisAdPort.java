package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **14 广告（channel 服务，端口 8083）**的跨服务只读端口：活动日报表。
 *
 * <p>蓝图 18.4.2.3 的广告亏损归因要回答「这个活动为什么亏」。最硬的依据是
 * **ACOS / ROAS / 花费 / 销售额 / CTR**。这些事实住在 channel 的
 * {@code ad_campaign_daily_metric} 表里（14.3.1.1 活动报表），本端口只读它，<b>不抄它</b>。
 *
 * <p>⚠️ <b>口径跟随上游，不自己另算一套</b>：ACOS = Σ花费 / Σ销售额、
 *   ROAS = Σ销售额 / Σ花费（**先汇总再相除**，不是各行比率的平均）——
 *   上游已把口径写死在那张表的 GENERATED 列里，本端口原样搬运。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。
 *
 * <p>⚠️ <b>取不到就 503</b>，绝不返回空集合冒充「没有广告亏损」——
 * 空集合在诊断页面上会长成「广告没问题」，而真相是「这一格没读出来」。
 */
@Service
public class DiagnosisAdPort {

  private final PlatformUpstreamClient client;

  public DiagnosisAdPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = PlatformUpstreamClient.to("channel 服务", url);
  }

  /** 上游一行活动日报表（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      String externalCampaignId,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales,
      BigDecimal ctr,
      BigDecimal acos,
      BigDecimal roas,
      LocalDate metricFrom,
      LocalDate metricTo) {}

  /** 上游报表页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdPage(
      List<AdRow> items,
      long total,
      int page,
      int size,
      String granularity,
      String dimension,
      OffsetDateTime dataAsOf,
      String emptyReason,
      String emptyNote) {}

  /**
   * 拉一页按**活动**（CAMPAIGN）分组的广告日报表。
   *
   * <p>⚠️ 上游 {@code dimension=CAMPAIGN} 的分组键是 {@code c.id::text}（活动 id），
   *   一行 = 一个活动在窗口内的**汇总**（不是逐日行）。
   */
  public AdPage campaignRows(LocalDate from, LocalDate to, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/channel/ad-reports/rows")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("dimension", "CAMPAIGN")
              .queryParam("granularity", "DAY")
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          return uriBuilder;
        },
        bearer,
        AdPage.class);
  }

  /**
   * 翻页拉取按活动分组的广告日报表（最多 {@link PlatformUpstreamClient#MAX_PAGES} 页）。
   *
   * <p>⚠️ 上游可能返回 {@code emptyReason}（三种「空」含义不同：没同步过 / 被筛掉 /
   *   区间内真的没有投放）—— 本端口**如实搬运**，由服务层写进运行记录的说明里。
   */
  public AdPage campaignRowsAll(LocalDate from, LocalDate to, String bearer) {
    var rows = new ArrayList<AdRow>();
    String emptyReason = null;
    String emptyNote = "";
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      AdPage batch = campaignRows(from, to, page, PlatformUpstreamClient.PAGE_SIZE, bearer);
      if (emptyReason == null && batch.emptyReason() != null) {
        emptyReason = batch.emptyReason();
        emptyNote = batch.emptyNote();
      }
      var items = batch.items() == null ? List.<AdRow>of() : batch.items();
      rows.addAll(items);
      if (items.isEmpty()) break;
      if (items.size() < PlatformUpstreamClient.PAGE_SIZE) break;
      page++;
    }
    return new AdPage(
        rows, rows.size(), 0, rows.size(), "DAY", "CAMPAIGN", null, emptyReason, emptyNote);
  }
}
