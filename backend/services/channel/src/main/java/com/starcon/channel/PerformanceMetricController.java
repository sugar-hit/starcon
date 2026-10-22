package com.starcon.channel;

import static com.starcon.channel.PerformanceMetricModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.1.1.2 绩效指标监控。
 *
 * <p>蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * <b>预测跌破比已跌破更有价值</b>——提前几天知道就能提前改。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：六项指标分别归谁、数据来自哪张表、
 *       哪些下游未交付、口径是什么。</li>
 *   <li>{@code GET /shops} —— 店铺范围清单（**真分页 + 真筛选**，数据来自本服务自己的店铺目录）。</li>
 *   <li>{@code GET /trends} —— 趋势序列清单（**真分页 + 真筛选**，标注区间口径与来源）。</li>
 *   <li>{@code GET /shops/{id}/trends} —— 单家店全部指标的趋势序列。</li>
 *   <li>{@code POST /shops/{id}/metrics} —— 手工录入**本模块自有**的指标
 *       （响应时间 / 账户评级，**恒标 MANUAL**）。</li>
 *   <li>{@code GET /alert-rules} —— 生效预警阈值（内置默认 + 本模块自定义 + 上游覆盖）。</li>
 *   <li>{@code PUT /alert-rules/{metricCode}} —— 改预警阈值（**服务端行为**，推进规则版本）。</li>
 *   <li>{@code GET /alerts} —— 阈值预警凭据（**真分页 + 真筛选**，带阈值快照）。</li>
 *   <li>{@code POST /shops/{id}/alert-evaluations} —— 跑一次预警评估，落**不可改写**的凭据。</li>
 *   <li>{@code GET /forecasts} —— 跌破预测凭据（**真分页 + 真筛选**，带预测口径）。</li>
 *   <li>{@code POST /shops/{id}/forecasts} —— 跑一次跌破预测，落**不可改写**的凭据。</li>
 *   <li>{@code GET /shops/{id}/forecast} —— 该店**最近一次**的预测结论。</li>
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「工作台推送」端点、没有「履约联动」端点 ——
 * 下游 1 / 9 当前<b>都未交付</b>，**放行一个不存在的入口比 404 更糟**
 * （界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/performance-metrics")
public class PerformanceMetricController {

  private final AccessService access;
  private final PerformanceMetricService service;

  public PerformanceMetricController(AccessService access, PerformanceMetricService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：六项指标的归属与来源、未交付的下游、口径。 */
  @GetMapping("/capabilities")
  public PerformanceMetricCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 趋势 ----

  /** 店铺范围清单（真分页 + 真筛选；数据来自本服务自己的店铺目录）。 */
  @GetMapping("/shops")
  public PerformanceMetricShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.shops(platformCode, keyword, page, size));
  }

  /** 趋势序列清单（真分页 + 真筛选；标注区间口径与来源）。 */
  @GetMapping("/trends")
  public PerformanceMetricTrendPage trends(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) Integer window,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "10") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.trends(platformCode, keyword, shopId, metricCode, window, page, size));
  }

  /** 单家店全部指标的趋势序列。 */
  @GetMapping("/shops/{id}/trends")
  public List<PerformanceMetricSeries> shopTrends(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) Integer window) {
    return call(auth, "channel.operation.read", a -> service.shopTrends(id, metricCode, window));
  }

  /** 手工录入一条本模块自有的指标（响应时间 / 账户评级，source 恒为 MANUAL）。 */
  @PostMapping("/shops/{id}/metrics")
  public PerformanceMetricSeries recordMetric(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PerformanceMetricMetricInput input) {
    return call(auth, "channel.operation.create", a -> service.recordMetric(id, input));
  }

  // ---- 预警阈值 ----

  /** 生效预警阈值（内置默认 + 本模块自定义 + 上游覆盖）。 */
  @GetMapping("/alert-rules")
  public PerformanceMetricAlertRuleList alertRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.alertRules());
  }

  /** 改预警阈值（服务端行为；推进规则版本，历史预警不受影响）。 */
  @PutMapping("/alert-rules/{metricCode}")
  public PerformanceMetricAlertRule saveAlertRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String metricCode,
      @RequestBody PerformanceMetricAlertRuleInput input) {
    return call(auth, "channel.operation.update", a -> service.saveAlertRule(metricCode, input));
  }

  // ---- 阈值预警 ----

  /** 阈值预警凭据（真分页 + 真筛选；带当时用到的阈值快照）。 */
  @GetMapping("/alerts")
  public PerformanceMetricAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.alerts(shopId, metricCode, severity, page, size));
  }

  /** 跑一次阈值预警评估，落一条不可改写的凭据。 */
  @PostMapping("/shops/{id}/alert-evaluations")
  public PerformanceMetricAlertEvaluation evaluateAlerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.evaluateAlerts(id));
  }

  // ---- 跌破预测 ----

  /** 跌破预测凭据（真分页 + 真筛选；带预测口径与「无法预测」的原因）。 */
  @GetMapping("/forecasts")
  public PerformanceMetricForecastPage forecasts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false) String result,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.forecasts(shopId, metricCode, result, page, size));
  }

  /** 跑一次跌破预测，落一条不可改写的凭据。 */
  @PostMapping("/shops/{id}/forecasts")
  public PerformanceMetricForecastRun forecast(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) PerformanceMetricForecastInput input) {
    return call(auth, "channel.operation.create", a -> service.forecast(id, input));
  }

  /** 该店最近一次的预测结论（每个指标一条）。 */
  @GetMapping("/shops/{id}/forecast")
  public PerformanceMetricForecastRun latestForecast(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.latestForecast(id));
  }
}
