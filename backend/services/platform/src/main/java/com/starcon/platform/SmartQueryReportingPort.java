package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.1.1 自定义报表（reporting 服务）的**跨服务只读 / 委托端口**：18.4.1.2「智能查询」的执行面。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离因此是<b>两道</b>（platform 侧 RLS + reporting 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>为什么不自己写 SQL</b>：13.6.1.1 已经在
 * {@code reporting_metric_fact} 上有一套**口径唯一**的查询引擎（维度白名单 5 个、
 * 运算符白名单 5 个、值只作绑定参数）。本模块**委托**它执行 ⇒ 同一个指标在
 * 「智能查询」与「自定义报表」里给出**同一个数**；本模块自己再写一套聚合逻辑，
 * 就会长出第二个真相（13.6.1.1 的蓝图原话：「报表口径会分裂成几十个版本」）。
 *
 * <p>⚠️ <b>取不到就显式失败</b>，绝不返回空集合冒充「上游没有数据」：
 * 一个空集合在结果表里会长成「这段时间没有销售额」，而真相是「这一格没读出来」。
 *
 * <p>⚠️ <b>本类刻意不复用 {@link PlatformUpstreamClient}</b>：那个客户端只有 GET /
 * multipart / 空体 POST 三种通道，没有「带 JSON 体的 POST / PUT」。**改它会动到
 * 18.4.1.1 的文件**（并行车道合并时的冲突面），所以这里自建一个同构的小客户端。
 */
@Service
public class SmartQueryReportingPort {

  // ================================================================ 上游契约

  /** 上游登记的指标（13.6.1.1 的口径目录）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportMetric(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /** 上游的维度白名单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportDimension(String dimensionCode, String label, String valueKind) {}

  /** 一条筛选条件（写入用）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportFilterRequest(
      String dimensionCode, String operator, String valueText, String valueText2) {}

  /** 报表配置（写入用）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportSaveRequest(
      String reportCode,
      String displayName,
      String description,
      String visibility,
      String chartType,
      LocalDate windowFrom,
      LocalDate windowTo,
      List<String> measureMetricCodes,
      List<String> dimensionCodes,
      List<ReportFilterRequest> filters) {}

  /** 报表配置（读出）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportView(
      String reportId,
      String reportCode,
      String displayName,
      String description,
      String visibility,
      String chartType,
      LocalDate windowFrom,
      LocalDate windowTo,
      List<ReportMetric> measures,
      List<String> dimensions,
      List<ReportFilter> filters,
      String ownerUserId,
      String createdAt,
      String updatedAt) {}

  /** 一条筛选条件（读出）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportFilter(
      String dimensionCode, String operator, String valueText, String valueText2) {}

  /** 跑报表的入参（窗口 + 分页）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportRunRequest(
      LocalDate from, LocalDate to, Integer page, Integer size) {}

  /** 一个币种上的取值。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportCurrencyValue(
      String currency, BigDecimal value, String availability, String availabilityNote) {}

  /** 一个度量的取值。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportMeasureCell(
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      List<ReportCurrencyValue> values,
      String availability,
      String availabilityNote) {}

  /** 一个维度取值。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportDimensionCell(String dimensionCode, String label, String value) {}

  /** 结果表的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportRow(List<ReportDimensionCell> dimensions, List<ReportMeasureCell> measures) {}

  /** 报表结果（真分页）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReportRunResponse(
      String reportId,
      String reportCode,
      String displayName,
      String description,
      String chartType,
      LocalDate from,
      LocalDate to,
      List<ReportDimensionCell> dimensions,
      List<ReportMetric> measures,
      List<ReportRow> rows,
      long total,
      int page,
      int size,
      List<String> caliberNotes,
      OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      String emptyReason,
      String emptyNote) {}

  // ================================================================ 客户端

  private final RestClient client;

  public SmartQueryReportingPort(@Value("${REPORTING_URL:http://127.0.0.1:8088}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                List.of(
                    new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        PlatformUpstreamClient.JSON)))
            .build();
  }

  // ================================================================ 目录

  /** 读上游登记的指标清单（**当前租户**的；别的租户登记的指标不在这里）。 */
  public List<ReportMetric> metrics(String bearer) {
    var result = get("/reporting/custom-report/metrics", bearer, ReportMetric[].class);
    return result == null ? List.of() : List.of(result);
  }

  /** 读上游的维度白名单（5 个）。 */
  public List<ReportDimension> dimensions(String bearer) {
    var result = get("/reporting/custom-report/dimensions", bearer, ReportDimension[].class);
    return result == null ? List.of() : List.of(result);
  }

  // ================================================================ 报表配置草稿

  /** 建一份报表配置草稿（**下游 13.6.1.1 真实落库**）。 */
  public ReportView createReport(String bearer, ReportSaveRequest request) {
    return send(
        org.springframework.http.HttpMethod.POST,
        "/reporting/custom-report/reports",
        bearer,
        request,
        ReportView.class,
        "在 13.6.1.1 自定义报表里建草稿");
  }

  /** 更新一份报表配置草稿（同一份草稿换口径，不新建）。 */
  public ReportView updateReport(String bearer, String reportId, ReportSaveRequest request) {
    return send(
        org.springframework.http.HttpMethod.PUT,
        "/reporting/custom-report/reports/" + reportId,
        bearer,
        request,
        ReportView.class,
        "更新 13.6.1.1 自定义报表里的草稿");
  }

  /** 跑一份报表草稿，取回结果表。 */
  public ReportRunResponse runReport(
      String bearer, String reportId, LocalDate from, LocalDate to, int page, int size) {
    return send(
        org.springframework.http.HttpMethod.POST,
        "/reporting/custom-report/reports/" + reportId + "/run",
        bearer,
        new ReportRunRequest(from, to, page, size),
        ReportRunResponse.class,
        "跑 13.6.1.1 自定义报表里的草稿");
  }

  // ================================================================ 传输

  private <T> T get(String path, String bearer, Class<T> type) {
    try {
      var result =
          client
              .get()
              .uri(path)
              .header("Authorization", bearer)
              .retrieve()
              .body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      throw translate(e, "读 13.6.1.1 的口径目录");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("reporting 服务不可达");
    }
  }

  private <T> T send(
      org.springframework.http.HttpMethod method,
      String path,
      String bearer,
      Object body,
      Class<T> type,
      String what) {
    try {
      var request =
          client
              .method(method)
              .uri(path)
              .header("Authorization", bearer)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body);
      var result = request.retrieve().body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      throw translate(e, what);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("reporting 服务不可达");
    }
  }

  /**
   * 上游报错 → 平台侧的错误。
   *
   * <p>🔴 <b>403 单独说清楚**：建草稿需要 {@code reporting.custom-report.write} 权限 ——
   * 调用方没有这个权限时，**不能说成「上游没数据」**，也不该说成「查询失败」；
   * 要说「你在自定义报表里没有建草稿的权限」。
   */
  private ResponseStatusException translate(RestClientResponseException e, String what) {
    var code = e.getStatusCode().value();
    if (code == 401 || code == 403) {
      return new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "无权" + what + "（需要 reporting.custom-report.read / .write 权限）——"
              + "本模块把查询委托给 13.6.1.1 的查询引擎，所以要跟着那边的权限走",
          e);
    }
    if (code == 404) {
      return new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "13.6.1.1 里找不到" + what + "（是「读不到」，不是「没有数据」）", e);
    }
    return unavailable("上游返回 " + code + "：" + what + "读不到");
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "reporting 服务 " + detail + "（是「读不到」，不是「没有数据」）");
  }
}
