package com.starcon.catalog;

import static com.starcon.catalog.PriceAnomalyModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5.5.2.1 异常告警。
 *
 * <p>命名空间 {@code /catalog/price-anomaly-alerts}：与 5.5.1.1 价格历史（**未交付**）
 * 共属 L3「5.5 价格监控」，但端点各自独立 —— 本模块的端点只承载
 * **观测台账 / 判定规则 / 异常事件 / 预警通知**四件事。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 本模块不跨服务读价</b>：平台价与内部价都来自本服务的观测台账
 * （见 {@link PriceAnomalyService} 的类注释：channel 的 listing 表明确「无价格列：售价归 05 定价」，
 * 4.3.2.2 只回流状态、不回流价格）⇒ 这里**没有** {@code X-Internal-Key} 之类的跨服务调用。
 */
@RestController
@RequestMapping("/catalog/price-anomaly-alerts")
public class PriceAnomalyController {

  private final AccessService access;
  private final PriceAnomalyService anomalies;

  public PriceAnomalyController(AccessService access, PriceAnomalyService anomalies) {
    this.access = access;
    this.anomalies = anomalies;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：四个输入的来源与缺口、四类异常的判定口径、当前生效的规则。
   *
   * <p>页面上把它固定显示在顶部 —— 「读不到」与「没有」是两件事，靠它说清。
   */
  @GetMapping("/capabilities")
  public PriceAnomalyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> anomalies.capabilities());
  }

  /** 价格观测台账（分页 + 站点 / 币种 / 变动来源筛选）。 */
  @GetMapping("/observations")
  public ObservationListPage observations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String priceChangeSource,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            anomalies.listObservations(
                new ObservationQuery(
                    keyword,
                    platformCode,
                    siteCode,
                    currency,
                    priceChangeSource,
                    windowFrom,
                    windowTo,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 登记一次价格观测（平台价回流 / 内部价 / 人工补录的统一入口）。
   *
   * <p>🔴 观测是**只追加**的：同一 SKU、同一站点、同一时刻重复登记会被拒绝（409），
   * 而不是覆盖历史 —— 覆盖历史会让「这个价不一致持续了几天」永远算不出来。
   */
  @PostMapping("/observations")
  public ObservationSaved registerObservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ObservationInput input) {
    return call(auth, "spu.update", a -> anomalies.registerObservation(a, input));
  }

  /** 异常清单（分页 + 类型 / 状态 / 严重度 / 站点筛选）。 */
  @GetMapping("/events")
  public EventListPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String anomalyType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            anomalies.listEvents(
                new EventQuery(
                    keyword,
                    anomalyType,
                    status,
                    severity,
                    platformCode,
                    siteCode,
                    windowFrom,
                    windowTo,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /** 异常详情：事件 + 预警通知 + 相关观测（判定依据与观测时刻都在这里）。 */
  @GetMapping("/events/{eventId}")
  public EventDetail event(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID eventId) {
    return call(auth, "spu.read", a -> anomalies.eventDetail(eventId));
  }

  /** 确认异常（OPEN → ACKNOWLEDGED）：人已经看到并接手了。 */
  @PostMapping("/events/{eventId}/acknowledge")
  public EventStatusSaved acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID eventId) {
    return call(auth, "spu.update", a -> anomalies.acknowledge(a, eventId));
  }

  /** 消解异常（OPEN / ACKNOWLEDGED → RESOLVED）。 */
  @PostMapping("/events/{eventId}/resolve")
  public EventStatusSaved resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID eventId) {
    return call(auth, "spu.update", a -> anomalies.resolve(a, eventId));
  }

  /** 当前生效的判定规则（未配置的类型用内置默认值，并标 overridden=false）。 */
  @GetMapping("/rules")
  public List<AnomalyRuleRow> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> anomalies.effectiveRules());
  }

  /** 保存判定规则（一种异常类型一条；阈值可空 = 用内置默认值）。 */
  @PostMapping("/rules")
  public AnomalyRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AnomalyRuleInput input) {
    return call(auth, "spu.update", a -> anomalies.saveRule(a, input));
  }

  /** 预警通知清单（下游 1 工作台预警未交付 ⇒ 只落在本服务内）。 */
  @GetMapping("/notifications")
  public NotificationListPage notifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String anomalyType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            anomalies.listNotifications(
                new EventQuery(
                    null,
                    anomalyType,
                    status,
                    severity,
                    null,
                    null,
                    null,
                    null,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 运行检测：扫一个时间窗口，四类异常各算一遍。
   *
   * <p>🔴 幂等：事件用 fingerprint 做幂等键，重复检测**不会**堆出一堆重复事件，
   * 只是把同一条事件的「持续天数」往前推；新事件才发预警通知。
   */
  @PostMapping("/detect")
  public DetectionRun detect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowTo) {
    return call(auth, "spu.update", a -> anomalies.detect(a, windowFrom, windowTo));
  }
}
