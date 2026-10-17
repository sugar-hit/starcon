package com.starcon.trade;

import static com.starcon.trade.FulfillmentQualityModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.5.2.1 履约质量入口。
 *
 * <p>命名空间 {@code /trade/fulfillment/quality}，与 routing / allocation /
 * pending-shipment / shipment-records / timeliness <b>并列</b>而非嵌套：
 * 那几组回答「怎么发 / 发没发 / 发得快不快」，本组回答「做得对不对」，
 * 在权限与日志上该分得开。
 *
 * <p>刻意<b>不挂</b>在 {@code timeliness} 之下：那是 9.5.1.1 的「时效」命名空间，
 * 本模块只读它的投影算质量指标；一个只读度量模块挂在被度量对象路径下，
 * 会让人以为改这里能改时效事实。
 *
 * <p>十四个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游可用性（错发 SKU 源 / 售后真实数据 /
 *       shop_code / 16 与 17 联动四个维度 available=false 并点名 ownerModule）</li>
 *   <li>{@code GET /report} 质量指标卡（六个指标，每个都带分子与分母）</li>
 *   <li>{@code GET /dimensions} 维度对比（店铺（平台级）/ 人员 二选一）</li>
 *   <li>{@code GET /drilldown} 指标明细下钻（<b>构成该指标的明细单</b>）</li>
 *   <li>{@code GET /after-sale-signals} / {@code POST /after-sale-signals} 纠纷与退货登记</li>
 *   <li>{@code GET /exceptions} / {@code POST /exceptions} 错发与漏发登记</li>
 *   <li>{@code GET /alert-rules} / {@code PUT /alert-rules/{metric}} 预警阈值</li>
 *   <li>{@code POST /alerts/evaluate} 评估预警（分母 0 不产生预警）</li>
 *   <li>{@code GET /alerts} / {@code POST /alerts/{id}/acknowledge} 预警清单与确认</li>
 *   <li>{@code GET /events} 事件流（append-only，真分页）</li>
 * </ul>
 *
 * <p>刻意<b>没有</b> DELETE：信号/缺陷/预警/事件都是举证或留痕，
 * V155 已经用 REVOKE 与触发器双层钉死。要更正就追加一条新的登记。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.quality.*}：identity 只注册了既有的 fulfillment 页面
 * 资源权限码，用一个<b>没有被注册</b>的码会让连 OWNER 都 403。
 */
@RestController
@RequestMapping("/trade/fulfillment/quality")
public class FulfillmentQualityController {

  private final AccessService access;
  private final FulfillmentQualityService quality;

  public FulfillmentQualityController(AccessService access, FulfillmentQualityService quality) {
    this.access = access;
    this.quality = quality;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /** 能力声明：四个不可用维度逐个点名 ownerModule 与本模块的处理方式。 */
  @GetMapping("/capabilities")
  public QualityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> quality.capabilities());
  }

  /** 质量指标卡：六个指标，每个都同时给分子、分母与口径。 */
  @GetMapping("/report")
  public QualityReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return call(auth, "fulfillment.read", a -> quality.report(from, to));
  }

  /** 维度对比：店铺（平台级）/ 人员 二选一，真 GROUP BY。 */
  @GetMapping("/dimensions")
  public QualityDimensionPage dimensions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "PLATFORM") String dimensionBy,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false, defaultValue = "50") int limit) {
    QualityDimension dimension = enumOf(dimensionBy, QualityDimension.class, "dimensionBy 只能是 PLATFORM / PERSON");
    return call(auth, "fulfillment.read", a -> quality.dimensions(dimension, from, to, limit));
  }

  /**
   * 指标明细下钻：<b>构成该指标的明细单</b>，不是只有数字。
   *
   * <p>刻意能按维度值下钻（{@code dimensionBy} + {@code dimensionValue}），
   * 这样从维度对比点进来看到的就是那一格自己的明细。
   */
  @GetMapping("/drilldown")
  public QualityDrilldownPage drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "SHIP_ON_TIME_RATE") String metric,
      @RequestParam(required = false) String dimensionBy,
      @RequestParam(required = false) String dimensionValue,
      @RequestParam(required = false, defaultValue = "OCCURRED_AT_DESC") String sort,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    QualityMetric m = enumOf(metric, QualityMetric.class, "metric 不在六个质量指标内");
    QualitySort srt = enumOf(sort, QualitySort.class, "sort 不在白名单内");
    return call(
        auth,
        "fulfillment.read",
        a -> quality.drilldown(m, dimensionBy, dimensionValue, srt, from, to, page, size));
  }

  // ------------------------------------------------------------ 售后信号

  /** 纠纷 / 退货清单（真分页 + 汇总）。 */
  @GetMapping("/after-sale-signals")
  public AfterSaleSignalPage afterSaleSignals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String signalType,
      @RequestParam(required = false) String signalState,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> quality.afterSaleSignals(signalType, signalState, keyword, page, size));
  }

  /**
   * 登记一笔纠纷 / 退货 —— 11 售后未交付，本模块逐笔登记，不假装读到了平台售后数据。
   *
   * <p>带 {@code sourceReference} 视为来自外部同步（来源标成 MODULE_11_*），
   * 不带则视为租户人工登记：两种来源在表里分得开，不靠猜。
   */
  @PostMapping("/after-sale-signals")
  public AfterSaleSignalRow registerAfterSaleSignal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AfterSaleSignalInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> quality.registerAfterSaleSignal(input, a.membershipId()));
  }

  // ------------------------------------------------------------ 履约缺陷

  /** 错发 / 漏发清单（真分页 + 汇总）。 */
  @GetMapping("/exceptions")
  public FulfillmentExceptionPage exceptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String exceptionType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> quality.exceptions(exceptionType, keyword, page, size));
  }

  /** 登记一次错发 / 漏发 —— 错发没有 SKU 级上游，只能由人举证登记。 */
  @PostMapping("/exceptions")
  public FulfillmentExceptionRow registerException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FulfillmentExceptionInput input) {
    return call(auth, "fulfillment.update", a -> quality.registerException(input, a.membershipId()));
  }

  // ------------------------------------------------------------ 预警

  /** 阈值清单（六条上限，缺行即「还没配阈值」而不是 0）。 */
  @GetMapping("/alert-rules")
  public List<AlertRuleRow> alertRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> quality.alertRules());
  }

  /** 配置某指标的阈值（幂等 upsert：一个指标一条生效规则）。 */
  @PutMapping("/alert-rules/{metric}")
  public AlertRuleRow upsertAlertRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String metric,
      @Valid @RequestBody AlertRuleInput input) {
    QualityMetric m = enumOf(metric, QualityMetric.class, "metric 不在六个质量指标内");
    return call(auth, "fulfillment.update", a -> quality.upsertAlertRule(m, input, a.membershipId()));
  }

  /** 评估预警。分母为 0 的指标<b>不产生</b>预警 —— 没有样本不是指标变差。 */
  @PostMapping("/alerts/evaluate")
  public AlertEvaluateResult evaluateAlerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) AlertEvaluateInput input) {
    return call(
        auth,
        "fulfillment.update",
        a ->
            quality.evaluateAlerts(
                input == null ? new AlertEvaluateInput(null, null, null) : input));
  }

  /** 预警清单（真分页；汇总给出未确认数）。 */
  @GetMapping("/alerts")
  public AlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String metric,
      @RequestParam(required = false) String dimensionCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> quality.alerts(metric, dimensionCode, page, size));
  }

  /** 确认一条预警。append-only ⇒ 确认落事件，不改预警行。 */
  @PostMapping("/alerts/{id}/acknowledge")
  public AlertPage acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "fulfillment.update",
        a -> {
          quality.acknowledgeAlert(id, a.membershipId());
          return quality.alerts(null, null, 0, 50);
        });
  }

  // ------------------------------------------------------------ 事件流

  /** 事件流（append-only，真分页；**刻意没有**任何写形状与 DELETE）。 */
  @GetMapping("/events")
  public QualityEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> quality.events(kind, orderId, page, size));
  }

  /**
   * 枚举参数校验：非法值一律 400 并把合法值域写在消息里，
   * 而不是让它落成「查不到任何数据」。
   */
  private static <E extends Enum<E>> E enumOf(String raw, Class<E> type, String message) {
    try {
      return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
  }
}
