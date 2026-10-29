package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.2.1.2 分时策略的<b>跨服务只读端口</b>：上游 = **13.2.1.2 分时与分周模式**（reporting 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经授权只读 API</b>读：
 *
 * <ul>
 *   <li>{@code GET /reporting/time-pattern/shops} —— 店铺时区档案（每个店铺按哪个时区统计）。
 *   <li>{@code GET /reporting/time-pattern?shopCodes=…&alignment=LOCAL_TIME}
 *       —— <b>某个店铺</b>的本地小时分布（推荐曲线的数据来源）。
 * </ul>
 *
 * <p>🔴 <b>必须逐店铺取分布</b>：上游的小时桶是按<b>各自店铺的本地时区</b>对齐的
 * （{@code alignment=LOCAL_TIME}）。一次拉全部店铺再聚合，会把**不同时区的小时混在一起**
 * —— 那正是蓝图加粗句「跨时区店铺用错时区会完全失效」说的那种失效。
 * ⇒ 本端口提供<b>按店铺</b>的读取形状，聚合由服务层逐店铺调用后**不合并**。
 *
 * <p>🔴 <b>上游读不到一律 503</b>：本端口<b>不返回空集合</b>冒充「上游没有数据」——
 * 「上游挂了」与「上游说这个窗口没有订单」是两件事，混起来会让运营以为这店铺没出过单。
 * ⇒ 读不到就抛 {@code ResponseStatusException(503)}，由端点原样翻成 503。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，reporting 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class AdDaypartSourcePort {

  /** reporting 服务默认端口（见 backend/services/reporting/src/main/resources/application.yml）。 */
  static final String REPORTING_URL = "http://127.0.0.1:8088";

  /** 店铺时区档案。 */
  static final String SHOPS_PATH = "/reporting/time-pattern/shops";

  /** 分时与分周模式主端点。 */
  static final String PATTERN_PATH = "/reporting/time-pattern";

  /** 一次拉取的上游超时（秒）。 */
  private static final int READ_TIMEOUT_SECONDS = 10;

  // ================================================================ 内部形状

  /** 上游的店铺时区档案（一行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ShopProfileRow(
      String shopCode,
      String shopName,
      String shopTimezone,
      String timezoneStatus,
      String timezoneReason) {}

  /** 上游的小时分布一格。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record HourBucketRow(
      int hour, BigDecimal orderCount, BigDecimal orderShare, boolean p90Peak) {}

  /** 上游的覆盖度。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CoverageRow(BigDecimal projectedOrderCount, int projectedDays, int expectedDays) {}

  /** 上游的主响应（本端口只取真用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PatternRow(
      String alignment,
      List<HourBucketRow> hourDistribution,
      CoverageRow coverage,
      OffsetDateTime dataUpdatedAt,
      String status,
      String emptyReason,
      String emptyNote) {}

  // ================================================================ 对外形状

  /**
   * 上游的店铺时区档案。
   *
   * @param shopTimezone IANA 标识；上游说「定不了」时是空串
   * @param timezoneStatus RESOLVED / UNRESOLVED
   */
  public record ShopProfile(
      String shopCode,
      String shopName,
      String shopTimezone,
      String timezoneStatus,
      String timezoneReason) {}

  /**
   * 某个店铺的本地小时分布。
   *
   * @param hourDistribution 24 个小时桶（上游固定给满 24 格，没有事实的小时是 0）
   * @param available 上游有没有给出可用的分布（false ⇒ 窗口内没有事实，**不是**「上游挂了」）
   */
  public record ShopDistribution(
      String shopCode,
      boolean available,
      List<HourBucket> hourDistribution,
      BigDecimal projectedOrderCount,
      int projectedDays,
      int expectedDays,
      OffsetDateTime dataUpdatedAt,
      String message) {}

  /** 上游一个小时桶。 */
  public record HourBucket(
      int hour, BigDecimal orderCount, BigDecimal orderShare, boolean p90Peak) {}

  // ================================================================ 客户端

  private final RestClient reporting;

  public AdDaypartSourcePort(@Value("${REPORTING_URL:" + REPORTING_URL + "}") String reportingUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS));
    this.reporting = RestClient.builder().baseUrl(reportingUrl).requestFactory(factory).build();
  }

  // ================================================================ 店铺时区档案

  /**
   * 全部店铺的时区档案（上游一次给全）。
   *
   * @throws ResponseStatusException 503 —— 上游读不到（**不返回空列表冒充「没有店铺」**）
   */
  public List<ShopProfile> shops(String bearer) {
    List<ShopProfileRow> rows;
    try {
      rows =
          reporting
              .get()
              .uri(SHOPS_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<ShopProfileRow>>() {});
    } catch (RestClientResponseException e) {
      throw upstreamUnavailable("reporting 服务返回 " + e.getStatusCode().value(), e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw upstreamUnavailable("reporting 服务不可达", e);
    }
    if (rows == null) {
      throw upstreamUnavailable("reporting 服务没有返回店铺时区档案", null);
    }
    return rows.stream()
        .map(
            row ->
                new ShopProfile(
                    row.shopCode() == null ? "" : row.shopCode(),
                    row.shopName() == null ? "" : row.shopName(),
                    row.shopTimezone() == null ? "" : row.shopTimezone(),
                    row.timezoneStatus() == null ? "UNRESOLVED" : row.timezoneStatus(),
                    row.timezoneReason() == null ? "" : row.timezoneReason()))
        .toList();
  }

  // ================================================================ 分时分布

  /**
   * 某个店铺在给定窗口的<b>本地小时</b>分布。
   *
   * @param shopCode 店铺编码（上游按它过滤）
   * @param from 窗口起（店铺本地日期）
   * @param to 窗口止（店铺本地日期，含）
   * @throws ResponseStatusException 503 —— 上游读不到
   */
  public ShopDistribution distribution(
      String shopCode, LocalDate from, LocalDate to, String bearer) {
    PatternRow pattern;
    try {
      pattern =
          reporting
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(PATTERN_PATH)
                          .queryParam("from", from.toString())
                          .queryParam("to", to.toString())
                          // 🔴 只用 LOCAL_TIME：UTC 槽位对齐回答的是「仓内同一绝对时刻的压力」，
                          //    不是「买家什么时候下单」—— 那不是分时策略要的答案。
                          .queryParam("alignment", "LOCAL_TIME")
                          .queryParam("shopCodes", shopCode)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(PatternRow.class);
    } catch (RestClientResponseException e) {
      throw upstreamUnavailable("reporting 服务返回 " + e.getStatusCode().value(), e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw upstreamUnavailable("reporting 服务不可达", e);
    }
    if (pattern == null) {
      throw upstreamUnavailable("reporting 服务没有返回分时分布", null);
    }

    var coverage = pattern.coverage();
    var buckets = pattern.hourDistribution() == null ? List.<HourBucketRow>of() : pattern.hourDistribution();
    var hours =
        buckets.stream()
            .map(
                b ->
                    new HourBucket(
                        b.hour(),
                        b.orderCount() == null ? BigDecimal.ZERO : b.orderCount(),
                        b.orderShare(),
                        b.p90Peak()))
            .toList();

    boolean available = !hours.isEmpty();
    String message = available ? "" : upstreamEmptyMessage(pattern);
    return new ShopDistribution(
        shopCode,
        available,
        hours,
        coverage == null || coverage.projectedOrderCount() == null
            ? BigDecimal.ZERO
            : coverage.projectedOrderCount(),
        coverage == null ? 0 : coverage.projectedDays(),
        coverage == null ? 0 : coverage.expectedDays(),
        pattern.dataUpdatedAt(),
        message);
  }

  /** 上游说「这个窗口没有数据」时，把原因原样带回来（**不编一个原因**）。 */
  private static String upstreamEmptyMessage(PatternRow pattern) {
    if (pattern.emptyNote() != null && !pattern.emptyNote().isBlank()) {
      return pattern.emptyNote();
    }
    if (pattern.emptyReason() != null && !pattern.emptyReason().isBlank()) {
      return "上游结论：" + pattern.emptyReason();
    }
    if (pattern.status() != null && !pattern.status().isBlank()) {
      return "上游结论：" + pattern.status();
    }
    return "上游在给定窗口内没有给出任何小时分布";
  }

  /** 🔴 上游读不到 = 503（**不是**空集合）。 */
  private static ResponseStatusException upstreamUnavailable(String detail, Exception cause) {
    var message =
        "13.2.1.2 分时与分周模式（reporting 服务）读不到："
            + detail
            + " —— 分时策略的推荐曲线依赖它，"
            + "此时返回空结果会被读成「这个店铺没出过单」，那是撒谎。";
    if (cause != null) {
      return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }
}
