package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.2 关联营销效果的**跨服务只读端口**（4 刊登 / 14 广告 ⇒ channel 服务）。
 *
 * <p>蓝图写明「上游 = 4 刊登（营销模块）、14 广告」，这两个上游都在 **channel** 服务里：
 *
 * <ul>
 *   <li>{@code GET /channel/attributions/records} —— **带货归因记录**（14.5.2.1）：
 *       每条记录自带<b>规则快照</b>（归因模型 / 分摊权重基准 / 归因窗口 / 退款口径 /
 *       佣金计提口径），还有归因成交金额 / 归因订单数 / 佣金成本 / 币种 / 归因时刻。
 *       ⇒ 本模块的<b>成交归因</b>与<b>ROI</b>全部来自这里。
 *   <li>{@code GET /channel/listing-performance} —— **平台表现快照**（4.5.1.1）：
 *       listing 级的曝光 / 点击（平台自然日）⇒ 本模块<b>落地商品口径</b>的曝光与点击。
 *   <li>{@code GET /channel/listing-content-writeback/writebacks} + {@code .../content-history}
 *       —— **内容回写**（4.3.2.1）：<b>A+ 内容确实写到了哪个 listing</b>的留痕
 *       ⇒ 这是「A+ 营销位存在」的**证据**（不是凭空登记一个营销位）。
 * </ul>
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>。
 *
 * <p>⚠️ <b>字段名必须与上游逐字一致</b>：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 所以<b>写错字段名不会报错，只会静默变成 null</b> —— 那会让整层指标恒为 0，比报错更危险。
 *
 * <p>⚠️ <b>列表字段各不相同</b>：归因记录是 {@code items}、平台表现是 {@code items}、
 * 刊登列表是 {@code items}；而 <b>trade 的销售订单是 {@code rows}</b>（另一个端口，不在本文件）。
 */
@Service
public class ReportingAffiliateMarketingPort {

  /** 上游分页上限（channel 侧把 size 钳在 200）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  /** 一次刷新里最多探测多少个 listing 的内容回写留痕（**N+1 的有界上限**）。 */
  static final int MAX_CONTENT_PROBES = 30;

  private final ReportingUpstreamClient channel;

  public ReportingAffiliateMarketingPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.channel = ReportingUpstreamClient.to("channel 服务", url);
  }

  // ==================================================================
  // 14.5.2.1 带货归因记录（**带规则快照** ⇒ 本模块的归因真相）
  // ==================================================================

  /**
   * 上游一条归因记录（channel {@code InfluencerAttributionRow} 的子集）。
   *
   * <p>🔴 {@code attributionModel} / {@code poolWeightBasis} / {@code attributionWindowDays} /
   * {@code refundTreatment} / {@code commissionBasis} 是**这条记录自带的规则快照** ——
   * 本模块**按快照落库**，**不用「当前规则」回填历史**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AttributionRow(
      UUID id,
      UUID influencerId,
      String influencerName,
      UUID campaignId,
      String campaignName,
      UUID cooperationId,
      String shopCode,
      String ruleCode,
      String attributionModel,
      String poolWeightBasis,
      Integer attributionWindowDays,
      String refundTreatment,
      String commissionBasis,
      BigDecimal commissionRate,
      BigDecimal attributionWeight,
      BigDecimal attributedGmv,
      BigDecimal attributedOrderCount,
      BigDecimal effectiveOrderCount,
      String currency,
      BigDecimal commissionAmount,
      OffsetDateTime attributedAt) {}

  /** 上游一页归因记录（**列表字段是 {@code items}**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AttributionPage(
      List<AttributionRow> items, int page, int size, long total, String caliberNote, String commissionNote) {}

  /**
   * 拉一页归因记录。
   *
   * <p>⚠️ 上游的归因记录列表**没有时间范围筛选**（只有达人 / 活动 / 店铺 / 规则 / 计提状态 /
   * 币种 / 关键字筛选）⇒ 本模块拉回后**自己按归因时刻切窗口**，并在刷新结果里如实报出
   * 「窗口外被跳过多少条」。
   */
  public AttributionPage attributionRecords(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/attributions/records")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AttributionPage.class);
  }

  // ==================================================================
  // 4.5.1.1 平台表现快照（**落地商品口径**的曝光 / 点击）
  // ==================================================================

  /** 上游一行平台表现快照（channel {@code ListingPerformanceSnapshotRow} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPerformanceRow(
      UUID listingId,
      String externalItemId,
      String title,
      String shopCode,
      String platformCode,
      String statDate,
      BigDecimal impressions,
      BigDecimal clicks,
      String currency) {}

  /** 上游一页平台表现快照。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPerformancePage(
      List<ListingPerformanceRow> items, int page, int size, long total, long syncCount) {}

  /** 拉一页平台表现快照（**平台统计日**范围）。 */
  public ListingPerformancePage listingPerformance(LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/listing-performance")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ListingPerformancePage.class);
  }

  // ==================================================================
  // 4.3.2.1 内容回写（**A+ 营销位存在的证据**）
  // ==================================================================

  /** 上游一次内容回写触发（channel {@code ContentWritebackRow} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentWritebackRow(
      UUID writebackId, String triggerKind, List<String> changeTypes, List<UUID> listingIds) {}

  /**
   * 拉一页内容回写触发（**列表直接是数组**，不是分页对象）。
   *
   * <p>⚠️ 上游这个端点返回的是 {@code List<ContentWritebackRow>}（**没有外层分页壳**）——
   * 按分页对象去读会得到 {@code null}。
   */
  public List<ContentWritebackRow> contentWritebacks(int page, String bearer) {
    return channel.list(
        uriBuilder ->
            uriBuilder
                .path("/channel/listing-content-writeback/writebacks")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<ContentWritebackRow>>() {});
  }

  /** 上游一条 listing 的内容回写变更历史里的一个字段变更。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentChangeRow(String field) {}

  /** 上游一条 listing 的内容回写变更历史。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentHistoryEntry(UUID writebackId, List<ContentChangeRow> changes) {}

  /**
   * 拉一个 listing 的内容回写变更历史。
   *
   * <p>🔴 这是「**这个 listing 真的被写过 A+ 内容**」的证据（channel 4.3.2.1）。
   * 没有它就只能拿「有人发起过一次 A_PLUS 回写」当证据 —— 那一次回写可能被跳过，
   * 拿它当营销位存在的证据就是**把没发生的事算成发生了**。
   */
  public List<ContentHistoryEntry> listingContentHistory(UUID listingId, String bearer) {
    return channel.list(
        uriBuilder ->
            uriBuilder.path("/channel/listing-content-writeback/listings/" + listingId + "/content-history"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<ContentHistoryEntry>>() {});
  }
}
