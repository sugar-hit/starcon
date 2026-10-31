package com.starcon.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.platform.ApiCallMonitorModels.*;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 18.1.1.3「调用监控」的服务层：把调用埋点变成**调用统计 + 告警**。
 *
 * <p>蓝图原文：「<b>职责</b>：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
 * <b>输入</b>：调用埋点；配额配置。<b>输出</b>：调用统计 + 告警。<b>关系</b>：上游 = API 网关；
 * 下游 = 1 工作台告警、18.1.1.1 配额调整。<b>外部系统调用错误要能被快速定位</b>，否则对接方只会说
 * "你们接口有问题"。」
 *
 * <p><b>四条纪律：</b>
 *
 * <ol>
 *   <li><b>阈值口径是平台固定的，且必须显式</b>：判定按什么口径判，写在
 *       {@link #options()} 里随页面一起出去 —— 运营看到告警要知道是按什么判的。
 *       平台**零预置**：不替用户预置任何一行告警配置。
 *   <li><b>比率与数量一律 numeric</b>：错误率 / QPS / 耗时都是 {@link BigDecimal}
 *       （库里 {@code NUMERIC(19,6)}）—— 禁浮点（AGENTS.md）。
 *   <li><b>「限流命中」与「错误」分开</b>：错误率是「不是成功的调用占比」，
 *       限流命中是「被配额挡下来的次数」。两个数在页面上并排显示，不混成一个。
 *   <li><b>告警要能指向下游</b>：限流类告警带建议配额窗口、建议上限与 18.1.1.1 的配额调整端点形状；
 *       卷 01 工作台告警尚未交付，这一格如实答 {@code available=false}。
 * </ol>
 */
@Service
public class ApiCallMonitorService {

  // ================================================================ 阈值口径（平台固定）

  /** 默认判定窗口：15 分钟。 */
  public static final int DEFAULT_WINDOW_SECONDS = 900;

  /** 可选窗口（秒）。 */
  public static final List<Integer> WINDOW_SECONDS = List.of(300, 900, 3600, 86400);

  /** 慢调用的线：单次耗时 ≥ 1000 ms 记一次慢调用。 */
  public static final BigDecimal SLOW_CALL_MS = new BigDecimal("1000");

  /** 错误率告警线：警告 5%，严重 15%。 */
  public static final BigDecimal ERROR_RATE_WARNING = new BigDecimal("0.05");
  public static final BigDecimal ERROR_RATE_CRITICAL = new BigDecimal("0.15");

  /** 慢调用告警线（按 p95 判定）：警告 1000 ms，严重 3000 ms。 */
  public static final BigDecimal SLOW_P95_WARNING_MS = new BigDecimal("1000");
  public static final BigDecimal SLOW_P95_CRITICAL_MS = new BigDecimal("3000");

  /** 少于此样本量不判定（1 次失败就是 100% 错误率，那是噪声不是异常）。 */
  public static final long MIN_SAMPLE = 20;

  /** 限流命中告警线：警告 10 次 / 窗口，严重 50 次 / 窗口。 */
  public static final long RATE_LIMIT_WARNING = 10;
  public static final long RATE_LIMIT_CRITICAL = 50;

  private static final int MAX_PAGE_SIZE = 100;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ApiSpecSource specs;

  public ApiCallMonitorService(DSLContext sql, TenantTransactions tenants, ApiSpecSource specs) {
    this.sql = sql;
    this.tenants = tenants;
    this.specs = specs;
  }

  // ================================================================ 能力声明

  /**
   * 18.1.1.3 的能力声明：逐维说清「有没有、依据是什么、缺了归谁」。
   *
   * <p>🔴 <b>「工作台告警推送」这一维如实答「没有」</b> —— 卷 01 的工作台告警服务至今未交付
   * （全仓只有 trade 的 {@code WORKBENCH_TODO} 与 channel 的两个 {@code workbench_alert_*}
   * 占位，都标着不可用）。本模块把告警落在自己这里并对外暴露告警列表，
   * 但**不假装**已经推给工作台。
   */
  public ApiCallMonitorCapabilities capabilities() {
    var capabilities =
        List.of(
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "调用埋点（真实落库）",
                true,
                "网关入口 /open/v1/** 每次调用都落一行 platform_api_call_log（只追加，"
                    + "迁移里的触发器钉死了改与删）；埋点含应用 / 接口 / 状态码 / 结论码 / "
                    + "限流命中 / 耗时 / 请求号",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "QPS",
                true,
                "窗口内的调用数 ÷ 窗口秒数（numeric，6 位小数）；窗口由调用方显式给出，"
                    + "不存在「默认 15 分钟」这种偷偷口径",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "错误率",
                true,
                "结论码 ≠ SUCCESS 的调用占比（不是「有多少个 4xx」）；"
                    + "结论码与状态码在迁移里逐字对应，两套口径不会打架",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "限流命中",
                true,
                "网关按 18.1.1.1 的 platform_app_quota 逐窗口判定，超限回 429 并把该行"
                    + "rate_limited 置真；「限流命中」与「错误」分列统计",
                "",
                "被限流挡下的调用不计入用量表（它没有消耗服务），命中次数只看埋点"),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "慢调用",
                true,
                "单次耗时 ≥ 1000 ms 记一次慢调用；告警按 p95 判定（警告 1000 ms / 严重 3000 ms），"
                    + "耗时是 numeric(19,6)，禁浮点",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "按应用维度分析",
                true,
                "埋点带 app_id（外键指向 platform_app），聚合真按应用分组；"
                    + "应用名取自 18.1.1.1 的应用档案",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "按接口维度分析",
                true,
                "埋点带 endpoint_code（网关放行清单里的端点码，封闭可数）；"
                    + "端点的名称与说明取自 **platform 服务活着的 OpenAPI 契约**（/v3/api-docs），"
                    + "契约读不到时如实标 specAvailable=false 并写明原因",
                "",
                "契约读不到时退回代码登记表里的说明，并在 specBasis 里写明「契约读不到」"),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "异常告警（判定 + 闭环）",
                true,
                "扫描按平台固定口径判定三类异常（错误率 / 慢调用 p95 / 限流命中），"
                    + "落 platform_api_call_alert；未闭环期间同一异常只一行（部分唯一索引），"
                    + "支持确认 / 闭环，指标回落后自动闭环",
                "",
                ""),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "**工作台告警推送（卷 01）**",
                false,
                "卷 01 的工作台告警服务至今未交付 —— 全仓只有 trade 的 WORKBENCH_TODO 与 "
                    + "channel 的 workbench_alert_notification / workbench_alert_delivery 两处占位，"
                    + "三者都标着不可用",
                "卷 01",
                "告警落在本模块的 platform_api_call_alert，卷 01 交付后由它来消费；"
                    + "在那之前，告警只在调用监控页里可见（页面上如实标出这一格不可用）"),
            new ApiCallMonitorCapability(
                "18.1.1.3",
                "**自动执行配额调整（18.1.1.1）**",
                false,
                "本模块只把「建议」算出来（建议窗口 / 建议上限 / 配额调整端点形状），"
                    + "**不**替 18.1.1.1 改配额策略 —— 改配额是写操作，不该由告警副作用触发",
                "18.1.1.1",
                "告警上直接给出 18.1.1.1 的配额调整端点与建议值，运营一键去调"));
    var gaps =
        List.of(
            new ApiCallMonitorGap(
                "18.1.1.1",
                "应用与凭证（配额配置）",
                "platform",
                true,
                "上游",
                "限流判定与「配额调整」建议的真来源：platform_app_quota / platform_app_quota_usage / "
                    + "platform_app_credential（同服务同库的直接读取）"),
            new ApiCallMonitorGap(
                "18.1.1.2",
                "接口目录（活着的契约）",
                "platform",
                true,
                "上游",
                "接口维度的名称与说明取自 platform 服务活着的 OpenAPI 契约（/v3/api-docs）"),
            new ApiCallMonitorGap(
                "卷 01",
                "工作台告警",
                "workbench",
                false,
                "下游",
                "告警推送的下游未交付：本模块把告警落在自己这里并对外暴露列表，"
                    + "待卷 01 交付后由工作台消费"),
            new ApiCallMonitorGap(
                "18.1.1.3",
                "调用埋点的其它来源",
                "platform",
                false,
                "上游",
                "本仓只有本模块自建的网关入口（/open/v1/**）产出埋点；"
                    + "若将来接入独立网关，需要它按同一口径落 platform_api_call_log"));
    return new ApiCallMonitorCapabilities(capabilities, gaps);
  }

  // ================================================================ 选项与阈值口径

  /** 筛选项 + 阈值口径 + 网关端点清单。 */
  public ApiCallMonitorOptions options() {
    var windows = new ArrayList<ApiCallMonitorOption>();
    for (var seconds : WINDOW_SECONDS) {
      windows.add(
          new ApiCallMonitorOption(
              String.valueOf(seconds),
              windowLabel(seconds),
              "统计窗口 " + seconds + " 秒（从现在往回）。窗口越短越贴近当下，越长越平滑。"));
    }
    var thresholds =
        List.of(
            new ApiCallMonitorThreshold(
                "ERROR_RATE_WARNING",
                "错误率警告线",
                ERROR_RATE_WARNING.toPlainString(),
                "比例",
                "结论码 ≠ SUCCESS 的调用占比 ≥ 5% 即开一条错误率告警（按 应用 × 接口 判定）"),
            new ApiCallMonitorThreshold(
                "ERROR_RATE_CRITICAL",
                "错误率严重线",
                ERROR_RATE_CRITICAL.toPlainString(),
                "比例",
                "错误率 ≥ 15% 时告警级别升为 CRITICAL"),
            new ApiCallMonitorThreshold(
                "SLOW_CALL_MS",
                "慢调用线",
                SLOW_CALL_MS.toPlainString(),
                "毫秒",
                "单次耗时 ≥ 1000 ms 的调用记为一次慢调用（概览里的慢调用数按这条线数）"),
            new ApiCallMonitorThreshold(
                "SLOW_P95_WARNING_MS",
                "慢调用警告线（p95）",
                SLOW_P95_WARNING_MS.toPlainString(),
                "毫秒",
                "按 应用 × 接口 的 p95 耗时 ≥ 1000 ms 即开一条慢调用告警"),
            new ApiCallMonitorThreshold(
                "SLOW_P95_CRITICAL_MS",
                "慢调用严重线（p95）",
                SLOW_P95_CRITICAL_MS.toPlainString(),
                "毫秒",
                "p95 耗时 ≥ 3000 ms 时告警级别升为 CRITICAL"),
            new ApiCallMonitorThreshold(
                "MIN_SAMPLE",
                "最小样本量",
                String.valueOf(MIN_SAMPLE),
                "次",
                "窗口内样本少于 " + MIN_SAMPLE + " 次不判定（1 次失败就是 100% 错误率，那是噪声不是异常）"),
            new ApiCallMonitorThreshold(
                "RATE_LIMIT_WARNING",
                "限流命中警告线",
                String.valueOf(RATE_LIMIT_WARNING),
                "次/窗口",
                "窗口内被配额挡下 ≥ " + RATE_LIMIT_WARNING + " 次即开一条限流命中告警（按 应用 判定）"),
            new ApiCallMonitorThreshold(
                "RATE_LIMIT_CRITICAL",
                "限流命中严重线",
                String.valueOf(RATE_LIMIT_CRITICAL),
                "次/窗口",
                "限流命中 ≥ " + RATE_LIMIT_CRITICAL + " 次时告警级别升为 CRITICAL"));
    return new ApiCallMonitorOptions(windows, thresholds, endpointCatalog());
  }

  /**
   * 网关端点清单（**接口维度**的词表）。
   *
   * <p>🔴 名称与说明优先取自 **platform 服务活着的 OpenAPI 契约**（{@code /v3/api-docs}）：
   * 契约是接口的唯一真相源，文档不手写。契约读不到时退回代码登记表里的说明，
   * 并在 {@code specBasis} 里**如实写明「契约读不到」** —— 不把「没读到」显示成「没有这条」。
   */
  private List<ApiCallMonitorEndpoint> endpointCatalog() {
    var entry = specs.byService("platform");
    var specAvailable = entry != null && entry.hasSpec();
    var specBasis =
        specAvailable
            ? "取自 platform 服务活着的 OpenAPI 契约（/v3/api-docs）"
            : (entry == null
                ? "platform 服务不在接口目录的登记表中，契约读不到"
                : "契约读不到：" + entry.unavailableReason());
    var result = new ArrayList<ApiCallMonitorEndpoint>();
    for (var endpoint : ApiGatewayEndpoints.ALL) {
      var operation = specAvailable ? findOperation(entry.spec(), endpoint) : null;
      var basis =
          operation != null
              ? "活着的契约里真的有这一条"
              : (specAvailable
                  ? "活着的契约里**没有**这一条（契约可读，但缺这条）"
                  : specBasis);
      result.add(
          new ApiCallMonitorEndpoint(
              endpoint.code(),
              endpoint.method(),
              endpoint.path(),
              operation == null ? endpoint.label() : operation.label(),
              operation == null ? endpoint.description() : operation.description(),
              endpoint.resourceCode(),
              endpoint.actionCode(),
              operation != null,
              basis));
    }
    return result;
  }

  /** 契约里的一个操作（summary + description）。 */
  private record SpecOperation(String label, String description) {}

  /** 在活着的契约里找这个端点。 */
  private static SpecOperation findOperation(JsonNode spec, ApiGatewayEndpoints.Endpoint endpoint) {
    var paths = spec.path("paths");
    var path = paths.path(endpoint.path());
    if (!path.isObject()) return null;
    var operation = path.path(endpoint.method().toLowerCase(Locale.ROOT));
    if (!operation.isObject()) return null;
    var summary = operation.path("summary").asText("");
    var description = operation.path("description").asText("");
    if (summary.isBlank() && description.isBlank()) return null;
    return new SpecOperation(summary.isBlank() ? endpoint.label() : summary, description);
  }

  // ================================================================ 概览

  /** 窗口内的总体指标。 */
  public ApiCallMonitorOverview overview(int windowSeconds) {
    var window = window(windowSeconds);
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where rate_limited) as limited,"
                + " count(*) filter (where duration_ms >= ?) as slow,"
                + " coalesce(avg(duration_ms),0) as avg_duration,"
                + " coalesce(max(duration_ms),0) as max_duration,"
                + " coalesce(round((percentile_cont(0.95) within group (order by duration_ms))::numeric,6),0)"
                + " as p95_duration,"
                + " count(distinct app_id) as apps,"
                + " count(distinct endpoint_code) as endpoints"
                + " from platform_api_call_log"
                + " where called_at >= ?::timestamptz and called_at < ?::timestamptz",
            SLOW_CALL_MS,
            window.start(),
            window.end());
    var total = row.get("total", Long.class);
    var errors = row.get("errors", Long.class);
    var limited = row.get("limited", Long.class);
    return new ApiCallMonitorOverview(
        total,
        errors,
        limited,
        row.get("slow", Long.class),
        ratio(errors, total),
        ratio(total, window.seconds()),
        row.get("avg_duration", BigDecimal.class),
        row.get("max_duration", BigDecimal.class),
        row.get("p95_duration", BigDecimal.class),
        row.get("apps", Long.class),
        row.get("endpoints", Long.class),
        openAlertCount(),
        window.seconds(),
        window.start(),
        window.end());
  }

  // ================================================================ 趋势

  /** 趋势（按分钟或按小时分桶）。 */
  public ApiCallMonitorTrend trend(int windowSeconds, String bucket) {
    var window = window(windowSeconds);
    var unit = "hour".equalsIgnoreCase(bucket == null ? "" : bucket.trim()) ? "hour" : "minute";
    var bucketSeconds = "hour".equals(unit) ? 3600 : 60;
    var rows =
        sql.fetch(
            "select (date_trunc('"
                + unit
                + "', called_at at time zone 'utc') at time zone 'utc') as bucket,"
                + " count(*) as total,"
                + " count(*) filter (where outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where rate_limited) as limited,"
                + " coalesce(avg(duration_ms),0) as avg_duration,"
                + " coalesce(round((percentile_cont(0.95) within group (order by duration_ms))::numeric,6),0)"
                + " as p95_duration"
                + " from platform_api_call_log"
                + " where called_at >= ?::timestamptz and called_at < ?::timestamptz"
                + " group by 1 order by 1",
            window.start(),
            window.end());
    var points = new ArrayList<ApiCallMonitorTrendPoint>();
    for (var row : rows) {
      var total = row.get("total", Long.class);
      var errors = row.get("errors", Long.class);
      points.add(
          new ApiCallMonitorTrendPoint(
              row.get("bucket", OffsetDateTime.class),
              total,
              errors,
              row.get("limited", Long.class),
              ratio(errors, total),
              ratio(total, bucketSeconds),
              row.get("avg_duration", BigDecimal.class),
              row.get("p95_duration", BigDecimal.class)));
    }
    return new ApiCallMonitorTrend(points, unit.toUpperCase(Locale.ROOT), window.seconds(), window.start(), window.end());
  }

  // ================================================================ 应用维度

  /** 按应用维度的一行。 */
  public List<ApiCallMonitorAppRow> apps(int windowSeconds) {
    var window = window(windowSeconds);
    var rows =
        sql.fetch(
            "select l.app_id, a.app_code, a.app_name,"
                + " count(*) as total,"
                + " count(*) filter (where l.outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where l.rate_limited) as limited,"
                + " count(*) filter (where l.duration_ms >= ?) as slow,"
                + " coalesce(avg(l.duration_ms),0) as avg_duration,"
                + " coalesce(round((percentile_cont(0.95) within group (order by l.duration_ms))::numeric,6),0)"
                + " as p95_duration,"
                + " max(l.called_at) as last_called_at"
                + " from platform_api_call_log l"
                + " join platform_app a on a.tenant_id = l.tenant_id and a.id = l.app_id"
                + " where l.called_at >= ?::timestamptz and l.called_at < ?::timestamptz"
                + " group by l.app_id, a.app_code, a.app_name"
                + " order by count(*) desc, l.app_id",
            SLOW_CALL_MS,
            window.start(),
            window.end());
    var result = new ArrayList<ApiCallMonitorAppRow>();
    for (var row : rows) {
      var total = row.get("total", Long.class);
      var errors = row.get("errors", Long.class);
      result.add(
          new ApiCallMonitorAppRow(
              row.get("app_id", UUID.class),
              row.get("app_code", String.class),
              row.get("app_name", String.class),
              total,
              errors,
              row.get("limited", Long.class),
              row.get("slow", Long.class),
              ratio(errors, total),
              ratio(total, window.seconds()),
              row.get("avg_duration", BigDecimal.class),
              row.get("p95_duration", BigDecimal.class),
              row.get("last_called_at", OffsetDateTime.class)));
    }
    return result;
  }

  // ================================================================ 接口维度

  /** 按接口维度（端点码）的一行。 */
  public List<ApiCallMonitorEndpointRow> endpoints(int windowSeconds) {
    var window = window(windowSeconds);
    var labels = endpointLabels();
    var rows =
        sql.fetch(
            "select l.endpoint_code,"
                + " min(l.http_method) as http_method, min(l.request_path) as request_path,"
                + " count(*) as total,"
                + " count(*) filter (where l.outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where l.rate_limited) as limited,"
                + " count(*) filter (where l.duration_ms >= ?) as slow,"
                + " coalesce(avg(l.duration_ms),0) as avg_duration,"
                + " coalesce(round((percentile_cont(0.95) within group (order by l.duration_ms))::numeric,6),0)"
                + " as p95_duration,"
                + " max(l.called_at) as last_called_at"
                + " from platform_api_call_log l"
                + " where l.called_at >= ?::timestamptz and l.called_at < ?::timestamptz"
                + " group by l.endpoint_code"
                + " order by count(*) desc, l.endpoint_code",
            SLOW_CALL_MS,
            window.start(),
            window.end());
    var result = new ArrayList<ApiCallMonitorEndpointRow>();
    for (var row : rows) {
      var total = row.get("total", Long.class);
      var errors = row.get("errors", Long.class);
      var code = row.get("endpoint_code", String.class);
      result.add(
          new ApiCallMonitorEndpointRow(
              code,
              row.get("http_method", String.class),
              row.get("request_path", String.class),
              labels.getOrDefault(code, code),
              total,
              errors,
              row.get("limited", Long.class),
              row.get("slow", Long.class),
              ratio(errors, total),
              ratio(total, window.seconds()),
              row.get("avg_duration", BigDecimal.class),
              row.get("p95_duration", BigDecimal.class),
              row.get("last_called_at", OffsetDateTime.class)));
    }
    return result;
  }

  /** 端点码 → 名称（来自活着的契约；契约读不到时用代码登记表）。 */
  private Map<String, String> endpointLabels() {
    var labels = new HashMap<String, String>();
    for (var endpoint : endpointCatalog()) {
      labels.put(endpoint.code(), endpoint.label());
    }
    return labels;
  }

  // ================================================================ 慢调用

  /** 慢调用 Top N（真排序、真截断）。 */
  public List<ApiCallMonitorSlowCall> slowCalls(int windowSeconds, int limit) {
    var window = window(windowSeconds);
    var size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    var rows =
        sql.fetch(
            "select l.id, l.app_id, a.app_code, l.endpoint_code, l.http_method, l.request_path,"
                + " l.status_code, l.outcome, l.duration_ms, l.client_ip, l.called_at"
                + " from platform_api_call_log l"
                + " join platform_app a on a.tenant_id = l.tenant_id and a.id = l.app_id"
                + " where l.called_at >= ?::timestamptz and l.called_at < ?::timestamptz"
                + " and l.duration_ms >= ?"
                + " order by l.duration_ms desc, l.called_at desc limit ?",
            window.start(),
            window.end(),
            SLOW_CALL_MS,
            size);
    var result = new ArrayList<ApiCallMonitorSlowCall>();
    for (var row : rows) {
      result.add(
          new ApiCallMonitorSlowCall(
              row.get("id", UUID.class),
              row.get("app_id", UUID.class),
              row.get("app_code", String.class),
              row.get("endpoint_code", String.class),
              row.get("http_method", String.class),
              row.get("request_path", String.class),
              row.get("status_code", Integer.class),
              row.get("outcome", String.class),
              row.get("duration_ms", BigDecimal.class),
              row.get("client_ip", String.class),
              row.get("called_at", OffsetDateTime.class)));
    }
    return result;
  }

  // ================================================================ 调用明细

  /** 调用明细（真分页 + 真筛选）。 */
  public ApiCallMonitorCallPage calls(
      int windowSeconds, UUID appId, String endpointCode, String outcome, Boolean rateLimited, int page, int size) {
    var window = window(windowSeconds);
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" l.called_at >= ?::timestamptz and l.called_at < ?::timestamptz");
    var params = new ArrayList<Object>();
    params.add(window.start());
    params.add(window.end());
    if (appId != null) {
      where.append(" and l.app_id = ?");
      params.add(appId);
    }
    if (endpointCode != null && !endpointCode.isBlank()) {
      where.append(" and l.endpoint_code = ?");
      params.add(endpointCode.trim());
    }
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and l.outcome = ?");
      params.add(outcome.trim().toUpperCase(Locale.ROOT));
    }
    if (rateLimited != null) {
      where.append(" and l.rate_limited = ?");
      params.add(rateLimited);
    }
    var totals =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where l.outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where l.rate_limited) as limited"
                + " from platform_api_call_log l where " + where,
            params.toArray());
    var rows =
        sql.fetch(
            "select l.id, l.app_id, a.app_code, l.endpoint_code, l.http_method, l.request_path,"
                + " l.status_code, l.outcome, l.error_code, l.rate_limited, l.duration_ms,"
                + " l.client_ip, l.request_id, l.called_at"
                + " from platform_api_call_log l"
                + " join platform_app a on a.tenant_id = l.tenant_id and a.id = l.app_id"
                + " where " + where
                + " order by l.called_at desc, l.id desc limit ? offset ?",
            append(params, size2, page2 * size2));
    var items = new ArrayList<ApiCallMonitorCallRow>();
    for (var row : rows) {
      var outcomeCode = row.get("outcome", String.class);
      var parsed = Outcome.parse(outcomeCode);
      items.add(
          new ApiCallMonitorCallRow(
              row.get("id", UUID.class),
              row.get("app_id", UUID.class),
              row.get("app_code", String.class),
              row.get("endpoint_code", String.class),
              row.get("http_method", String.class),
              row.get("request_path", String.class),
              row.get("status_code", Integer.class),
              outcomeCode,
              parsed == null ? outcomeCode : parsed.label(),
              row.get("error_code", String.class),
              row.get("rate_limited", Boolean.class),
              row.get("duration_ms", BigDecimal.class),
              row.get("client_ip", String.class),
              row.get("request_id", String.class),
              row.get("called_at", OffsetDateTime.class)));
    }
    return new ApiCallMonitorCallPage(
        items,
        page2,
        size2,
        totals.get("total", Long.class),
        totals.get("errors", Long.class),
        totals.get("limited", Long.class));
  }

  // ================================================================ 告警

  /** 告警列表（真分页 + 真筛选）。 */
  public ApiCallMonitorAlertPage alerts(String status, String alertType, UUID appId, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" 1 = 1");
    var params = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.append(" and al.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (alertType != null && !alertType.isBlank()) {
      where.append(" and al.alert_type = ?");
      params.add(alertType.trim().toUpperCase(Locale.ROOT));
    }
    if (appId != null) {
      where.append(" and al.app_id = ?");
      params.add(appId);
    }
    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where al.status = 'OPEN') as open_count,"
                + " count(*) filter (where al.status = 'ACKNOWLEDGED') as ack_count,"
                + " count(*) filter (where al.status = 'RESOLVED') as resolved_count"
                + " from platform_api_call_alert al where " + where,
            params.toArray());
    var rows =
        sql.fetch(
            "select al.id, al.app_id, a.app_code, al.alert_type, al.severity, al.status,"
                + " al.endpoint_code, al.metric_value, al.threshold_value, al.window_seconds,"
                + " al.window_started_at, al.window_ended_at, al.message, al.suggested_action,"
                + " al.suggested_quota_window, al.suggested_quota_limit, al.quota_adjust_target,"
                + " al.acknowledged_at, al.acknowledged_by, al.resolved_at, al.created_at"
                + " from platform_api_call_alert al"
                + " join platform_app a on a.tenant_id = al.tenant_id and a.id = al.app_id"
                + " where " + where
                + " order by al.created_at desc, al.id desc limit ? offset ?",
            append(params, size2, page2 * size2));
    var items = new ArrayList<ApiCallMonitorAlertRow>();
    for (var row : rows) {
      items.add(toAlertRow(row));
    }
    return new ApiCallMonitorAlertPage(
        items,
        page2,
        size2,
        counts.get("total", Long.class),
        counts.get("open_count", Long.class),
        counts.get("ack_count", Long.class),
        counts.get("resolved_count", Long.class));
  }

  private static ApiCallMonitorAlertRow toAlertRow(org.jooq.Record row) {
    var type = row.get("alert_type", String.class);
    var parsed = AlertType.parse(type);
    return new ApiCallMonitorAlertRow(
        row.get("id", UUID.class),
        row.get("app_id", UUID.class),
        row.get("app_code", String.class),
        type,
        parsed == null ? type : parsed.label(),
        row.get("severity", String.class),
        row.get("status", String.class),
        row.get("endpoint_code", String.class),
        row.get("metric_value", BigDecimal.class),
        row.get("threshold_value", BigDecimal.class),
        row.get("window_seconds", Integer.class),
        row.get("window_started_at", OffsetDateTime.class),
        row.get("window_ended_at", OffsetDateTime.class),
        row.get("message", String.class),
        row.get("suggested_action", String.class),
        row.get("suggested_quota_window", String.class),
        row.get("suggested_quota_limit", BigDecimal.class),
        row.get("quota_adjust_target", String.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("acknowledged_by", UUID.class),
        row.get("resolved_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 确认一条告警（幂等：已确认的再确认一次不报错）。 */
  public ApiCallMonitorAlertRow acknowledge(UUID alertId, AccessService.Access actor) {
    var current = requireAlert(alertId);
    if ("RESOLVED".equals(current.status())) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "告警已闭环，不能再确认");
    }
    if (!"ACKNOWLEDGED".equals(current.status())) {
      sql.execute(
          "update platform_api_call_alert set status = 'ACKNOWLEDGED', acknowledged_at = ?::timestamptz,"
              + " acknowledged_by = ? where id = ? and status = 'OPEN'",
          OffsetDateTime.now(ZoneOffset.UTC),
          actor.userId(),
          alertId);
    }
    return requireAlert(alertId);
  }

  /** 闭环一条告警（幂等：已闭环的再闭环一次不报错）。 */
  public ApiCallMonitorAlertRow resolve(UUID alertId, AccessService.Access actor) {
    var current = requireAlert(alertId);
    if (!"RESOLVED".equals(current.status())) {
      sql.execute(
          "update platform_api_call_alert set status = 'RESOLVED', resolved_at = ?::timestamptz"
              + " where id = ? and status <> 'RESOLVED'",
          OffsetDateTime.now(ZoneOffset.UTC),
          alertId);
    }
    return requireAlert(alertId);
  }

  private ApiCallMonitorAlertRow requireAlert(UUID alertId) {
    var row =
        sql.fetchOne(
            "select al.id, al.app_id, a.app_code, al.alert_type, al.severity, al.status,"
                + " al.endpoint_code, al.metric_value, al.threshold_value, al.window_seconds,"
                + " al.window_started_at, al.window_ended_at, al.message, al.suggested_action,"
                + " al.suggested_quota_window, al.suggested_quota_limit, al.quota_adjust_target,"
                + " al.acknowledged_at, al.acknowledged_by, al.resolved_at, al.created_at"
                + " from platform_api_call_alert al"
                + " join platform_app a on a.tenant_id = al.tenant_id and a.id = al.app_id"
                + " where al.id = ?",
            alertId);
    if (row == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "告警不存在或不属于当前租户");
    }
    return toAlertRow(row);
  }

  // ================================================================ 扫描

  /**
   * 扫一遍调用埋点，按平台固定口径判定三类异常并开告警。
   *
   * <p>🔴 **同一异常不刷屏**：未闭环期间同一（应用 × 告警类型 × 接口）只有一行，
   * 重复扫描只刷新指标值与文案（部分唯一索引 + ON CONFLICT DO UPDATE 钉死）。
   *
   * <p>🔴 **指标回落后自动闭环**：这次扫描里不再越线的旧告警会被置为 RESOLVED ——
   * 否则告警列表会永远堆着早就恢复的异常，运营就分不清「现在还好吗」。
   */
  public ApiCallMonitorScanResult scan(int windowSeconds) {
    var window = window(windowSeconds);
    var groups =
        sql.fetch(
            "select l.app_id, l.endpoint_code, count(*) as total,"
                + " count(*) filter (where l.outcome <> 'SUCCESS') as errors,"
                + " count(*) filter (where l.rate_limited) as limited,"
                + " coalesce(round((percentile_cont(0.95) within group (order by l.duration_ms))::numeric,6),0)"
                + " as p95_duration"
                + " from platform_api_call_log l"
                + " where l.called_at >= ?::timestamptz and l.called_at < ?::timestamptz"
                + " group by l.app_id, l.endpoint_code",
            window.start(),
            window.end());
    var appCodes = appCodes();
    var limitedByApp = new LinkedHashMap<UUID, Long>();
    var apps = new LinkedHashMap<UUID, Boolean>();
    for (var group : groups) {
      var appId = group.get("app_id", UUID.class);
      apps.put(appId, true);
      limitedByApp.merge(appId, group.get("limited", Long.class), Long::sum);
    }

    var raised = 0;
    var refreshed = 0;
    var breaching = new LinkedHashMap<String, Boolean>();

    for (var group : groups) {
      var appId = group.get("app_id", UUID.class);
      var endpointCode = group.get("endpoint_code", String.class);
      var total = group.get("total", Long.class);
      var errors = group.get("errors", Long.class);
      var p95 = group.get("p95_duration", BigDecimal.class);
      var appCode = appCodes.getOrDefault(appId, appId.toString());

      // 错误率（按 应用 × 接口）
      var errorRate = ratio(errors, total);
      if (total >= MIN_SAMPLE && errorRate.compareTo(ERROR_RATE_WARNING) >= 0) {
        var critical = errorRate.compareTo(ERROR_RATE_CRITICAL) >= 0;
        var threshold = critical ? ERROR_RATE_CRITICAL : ERROR_RATE_WARNING;
        var outcome =
            raise(
                appId,
                AlertType.ERROR_RATE_HIGH,
                endpointCode,
                critical ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
                errorRate,
                threshold,
                window,
                appCode + " 的 " + endpointCode + " 接口错误率 "
                    + errorRate.toPlainString()
                    + "（"
                    + errors
                    + "/"
                    + total
                    + " 次调用），已达"
                    + (critical ? "严重线" : "警告线")
                    + " "
                    + threshold.toPlainString(),
                "先看调用明细里这一段的失败原因（错误码与请求号），定位到具体是凭证、授权还是服务端异常。",
                "",
                null,
                "");
        breaching.put(key(appId, AlertType.ERROR_RATE_HIGH, endpointCode), true);
        if (outcome) raised++;
        else refreshed++;
      }

      // 慢调用（按 应用 × 接口 的 p95）
      if (total >= MIN_SAMPLE && p95.compareTo(SLOW_P95_WARNING_MS) >= 0) {
        var critical = p95.compareTo(SLOW_P95_CRITICAL_MS) >= 0;
        var threshold = critical ? SLOW_P95_CRITICAL_MS : SLOW_P95_WARNING_MS;
        var outcome =
            raise(
                appId,
                AlertType.SLOW_CALL,
                endpointCode,
                critical ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
                p95,
                threshold,
                window,
                appCode + " 的 " + endpointCode + " 接口 p95 耗时 " + p95.toPlainString() + " ms，已达"
                    + (critical ? "严重线" : "警告线") + " " + threshold.toPlainString() + " ms",
                "看慢调用 Top N 里这一接口的具体耗时与请求路径，再决定是查下游依赖还是查本服务的慢 SQL。",
                "",
                null,
                "");
        breaching.put(key(appId, AlertType.SLOW_CALL, endpointCode), true);
        if (outcome) raised++;
        else refreshed++;
      }
    }

    // 限流命中（按 应用）
    for (var entry : limitedByApp.entrySet()) {
      var appId = entry.getKey();
      var limited = entry.getValue();
      if (limited < RATE_LIMIT_WARNING) continue;
      var critical = limited >= RATE_LIMIT_CRITICAL;
      var threshold = BigDecimal.valueOf(critical ? RATE_LIMIT_CRITICAL : RATE_LIMIT_WARNING);
      var appCode = appCodes.getOrDefault(appId, appId.toString());
      var suggestion = quotaSuggestion(appId, limited);
      var outcome =
          raise(
              appId,
              AlertType.RATE_LIMIT_SPIKE,
              "",
              critical ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
              BigDecimal.valueOf(limited),
              threshold,
              window,
              appCode + " 在本窗口被配额挡下 " + limited + " 次，已达"
                  + (critical ? "严重线" : "警告线") + " " + threshold.toPlainString() + " 次",
              "到 18.1.1.1 的配额设置里调整对应窗口的上限，或让对接方降低调用频率。",
              suggestion.window(),
              suggestion.limit(),
              "/platform/apps/" + appId + "/quotas");
      breaching.put(key(appId, AlertType.RATE_LIMIT_SPIKE, ""), true);
      if (outcome) raised++;
      else refreshed++;
    }

    // 指标回落后自动闭环旧的未闭环告警。
    var resolved = autoResolve(breaching);

    return new ApiCallMonitorScanResult(
        window.seconds(),
        window.start(),
        window.end(),
        apps.size(),
        raised,
        refreshed,
        resolved,
        groups.size());
  }

  /** 开一条告警；已存在（未闭环）则只刷新。返回 true = 新开，false = 刷新。 */
  private boolean raise(
      UUID appId,
      AlertType type,
      String endpointCode,
      AlertSeverity severity,
      BigDecimal metricValue,
      BigDecimal thresholdValue,
      Window window,
      String message,
      String suggestedAction,
      String suggestedQuotaWindow,
      BigDecimal suggestedQuotaLimit,
      String quotaAdjustTarget) {
    var existed =
        sql.fetchOne(
                "select id from platform_api_call_alert"
                    + " where app_id = ? and alert_type = ? and endpoint_code = ? and status <> 'RESOLVED'",
                appId,
                type.name(),
                endpointCode)
            != null;
    sql.execute(
        "insert into platform_api_call_alert(id,tenant_id,app_id,alert_type,severity,status,"
            + " endpoint_code,metric_value,threshold_value,window_started_at,window_ended_at,"
            + " window_seconds,message,suggested_action,suggested_quota_window,suggested_quota_limit,"
            + " quota_adjust_target,created_at)"
            + " values(?,?,?,?,?,'OPEN',?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?::timestamptz)"
            + " on conflict (tenant_id,app_id,alert_type,endpoint_code) where status <> 'RESOLVED'"
            + " do update set severity = excluded.severity, metric_value = excluded.metric_value,"
            + " threshold_value = excluded.threshold_value, window_started_at = excluded.window_started_at,"
            + " window_ended_at = excluded.window_ended_at, window_seconds = excluded.window_seconds,"
            + " message = excluded.message, suggested_action = excluded.suggested_action,"
            + " suggested_quota_window = excluded.suggested_quota_window,"
            + " suggested_quota_limit = excluded.suggested_quota_limit,"
            + " quota_adjust_target = excluded.quota_adjust_target",
        UUID.randomUUID(),
        currentTenant(),
        appId,
        type.name(),
        severity.name(),
        endpointCode,
        metricValue,
        thresholdValue,
        window.start(),
        window.end(),
        window.seconds(),
        message,
        suggestedAction,
        suggestedQuotaWindow,
        suggestedQuotaLimit,
        quotaAdjustTarget,
        OffsetDateTime.now(ZoneOffset.UTC));
    return !existed;
  }

  /** 指标回落后自动闭环（返回闭环了几条）。 */
  private int autoResolve(Map<String, Boolean> breaching) {
    var rows =
        sql.fetch(
            "select id, app_id, alert_type, endpoint_code from platform_api_call_alert"
                + " where status <> 'RESOLVED'");
    var resolved = 0;
    for (var row : rows) {
      var key =
          key(
              row.get("app_id", UUID.class),
              AlertType.parse(row.get("alert_type", String.class)),
              row.get("endpoint_code", String.class));
      if (Boolean.TRUE.equals(breaching.get(key))) continue;
      sql.execute(
          "update platform_api_call_alert set status = 'RESOLVED', resolved_at = ?::timestamptz"
              + " where id = ? and status <> 'RESOLVED'",
          OffsetDateTime.now(ZoneOffset.UTC),
          row.get("id", UUID.class));
      resolved++;
    }
    return resolved;
  }

  private static String key(UUID appId, AlertType type, String endpointCode) {
    return appId + "|" + (type == null ? "" : type.name()) + "|" + endpointCode;
  }

  /**
   * 限流告警的配额建议：找出**用得最满**的那个已配置窗口，建议把上限提到「上限 + 本窗口被挡下的次数」。
   *
   * <p>🔴 建议值来自**真实数字**：被挡下 N 次说明这批流量本来能过去，把上限提到 上限+N 就够这批流量过。
   */
  private record QuotaSuggestion(String window, BigDecimal limit) {}

  private QuotaSuggestion quotaSuggestion(UUID appId, long blocked) {
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    var rows =
        sql.fetch("select window_type, limit_count, enabled from platform_app_quota where app_id = ?", appId);
    String bestWindow = "";
    BigDecimal bestLimit = null;
    double bestRatio = -1;
    for (var window : AppCredentialService.WINDOWS) {
      var row =
          rows.stream()
              .filter(r -> window.name().equals(r.get("window_type", String.class)))
              .findFirst()
              .orElse(null);
      if (row == null) continue;
      if (!row.get("enabled", Boolean.class)) continue;
      var limit = row.get("limit_count", BigDecimal.class);
      if (limit.signum() <= 0) continue;
      var start = AppCredentialService.windowStart(window, at);
      var usage =
          sql.fetchOne(
              "select used_count from platform_app_quota_usage where app_id = ? and window_type = ?"
                  + " and window_started_at = ?::timestamptz",
              appId,
              window.name(),
              start);
      var used = usage == null ? BigDecimal.ZERO : usage.get("used_count", BigDecimal.class);
      var ratio = used.divide(limit, 6, RoundingMode.HALF_UP).doubleValue();
      if (ratio > bestRatio) {
        bestRatio = ratio;
        bestWindow = window.name();
        bestLimit = limit;
      }
    }
    if (bestWindow.isEmpty()) return new QuotaSuggestion("", null);
    return new QuotaSuggestion(bestWindow, bestLimit.add(BigDecimal.valueOf(blocked)));
  }

  // ================================================================ 内部

  /** 判定窗口（起止都用 DB 时钟，与埋点里的 called_at 同一把尺子）。 */
  private record Window(OffsetDateTime start, OffsetDateTime end, int seconds) {}

  private Window window(int windowSeconds) {
    var seconds = WINDOW_SECONDS.contains(windowSeconds) ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    var end = dbNow();
    return new Window(end.minusSeconds(seconds), end, seconds);
  }

  /** DB 时钟（与落库的 called_at 同一口径）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp() as t").get(0, OffsetDateTime.class);
  }

  /** 未闭环告警数。 */
  private long openAlertCount() {
    return sql.fetchOne("select count(*) from platform_api_call_alert where status <> 'RESOLVED'")
        .get(0, Long.class);
  }

  /** 应用码表（告警与维度行里显示应用名用）。 */
  private Map<UUID, String> appCodes() {
    var result = new HashMap<UUID, String>();
    for (var row : sql.fetch("select id, app_code from platform_app")) {
      result.put(row.get("id", UUID.class), row.get("app_code", String.class));
    }
    return result;
  }

  /** 比率（6 位小数，分母为 0 时记 0；禁浮点）。 */
  private static BigDecimal ratio(long numerator, long denominator) {
    if (denominator <= 0) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
  }

  /** 当前事务里的租户（由 AccessService 绑定）。 */
  private UUID currentTenant() {
    var raw = sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("缺少可信租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 追加参数。 */
  private static Object[] append(List<Object> params, Object... extra) {
    var all = new ArrayList<>(params);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  /** 窗口的中文名。 */
  private static String windowLabel(int seconds) {
    return switch (seconds) {
      case 300 -> "近 5 分钟";
      case 900 -> "近 15 分钟";
      case 3600 -> "近 1 小时";
      case 86400 -> "近 24 小时";
      default -> "近 " + seconds + " 秒";
    };
  }
}
