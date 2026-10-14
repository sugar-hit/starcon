package com.starcon.warehouse;

import static com.starcon.warehouse.InTransitTrackingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
 * 10.4.1.2 在途跟踪。
 *
 * <p>命名空间 {@code /warehouse/transit-trackings}：与上游 10.4.1.1 的
 * {@code /warehouse/first-mile-shipments} 同级但**刻意不同名** —— 前者是「这批货是谁、
 * 值多少、货权在谁手上」，本模块是「它现在在哪、什么时候到、还要等几天」，
 * 挂在同一个前缀下但不同路径，避免把「单据」与「跟踪」在浏览器地址栏里混成一件事。
 *
 * <p>权限码沿用 {@code warehouse.}：读 {@code warehouse.inventory.read}，
 * 写动作（建跟踪 / 登记节点 / 收货确认 / 关闭 / 预警确认关闭 / 改阈值）统一
 * {@code warehouse.inventory.update} —— 预警确认与阈值改口径会直接影响断货判断，
 * 与调拨发货同档，不给只读账号。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点用 **POST**：登记节点、收货确认、关闭跟踪、预警确认/关闭
 * 都是「发生一次业务事件」并落一条事件行，用 PUT 会被读成「把单据设置成某状态」。
 * 改阈值用 PUT：那是真正的幂等资源替换（整行三档提前量一起换口径）。
 */
@RestController
@RequestMapping("/warehouse/transit-trackings")
public class InTransitTrackingController {

  private final AccessService access;
  private final InTransitTrackingService tracking;

  public InTransitTrackingController(AccessService access, InTransitTrackingService tracking) {
    this.access = access;
    this.tracking = tracking;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 在途跟踪能力：可跟踪来源、**未交付依赖逐条声明**、两级状态机、四张词表与四条口径。
   *
   * <p>前端据此渲染来源说明、状态/阶段/清关/档位下拉与免责提示。
   * 外部连接器（10.4.2.1）未交付这一事实**显式回报**，而不是给一个点下去没反应的
   * 「同步」按钮。
   */
  @GetMapping("/capabilities")
  public TransitTrackCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> tracking.capabilities());
  }

  /**
   * 在途看板：真分页 + **全量汇总不受分页影响**。
   *
   * <p>剩余窗口与预警档位由服务层按**本租户阈值表**现算，所以
   * {@code delayLevel} / {@code delayedOnly} 筛选口径与行上显示的档位必然一致
   * —— 不会出现「筛出来是 CRITICAL、行上却写 WATCH」。
   */
  @GetMapping
  public TransitTrackPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) List<String> stage,
      @RequestParam(required = false) List<String> customsStatus,
      @RequestParam(required = false) List<String> delayLevel,
      @RequestParam(required = false) UUID shipmentId,
      @RequestParam(required = false) UUID destinationWarehouseId,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String transportMode,
      @RequestParam(required = false) Boolean delayedOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tracking.page(
                new TrackingQuery(
                    status,
                    stage,
                    customsStatus,
                    delayLevel,
                    shipmentId,
                    destinationWarehouseId,
                    destinationCountryCode,
                    transportMode,
                    delayedOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 跟踪详情：头 + 节点事件流 + 在途腿（7.5.1.2 名下）+ 未结预警 + 合法下一步 + 当前阈值口径。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这条跟踪存在」）。
   */
  @GetMapping("/{trackingId}")
  public TransitTrackDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId) {
    return call(auth, "inventory.read", a -> tracking.detail(trackingId));
  }

  /**
   * 建跟踪记录：一张发运单一条，发运单必须**已发出**。
   *
   * <p>货还没走的单子建跟踪会让看板把草稿混进在途 —— 服务层与 DB 触发器各挡一次。
   */
  @PostMapping
  public TrackingRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TrackingCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tracking.create(a.userId(), request == null ? emptyCreate() : request));
  }

  /**
   * 登记一个节点事件：位置 / 改期 / 清关 / 阶段推进 / 备注 / 异常，五合一入口。
   *
   * <p>这是「货代/船司/清关状态同步」与「人工更新」的**同一个入口**：连接器未交付
   * （10.4.2.1），两种来源形状一样就走一条路径，用 {@code sourceSystem} 区分来源。
   * 外部来源必须带 {@code referenceId}，人工来源必须有操作人。
   *
   * <p>登记后**立刻重算预警**：改期/阶段/清关都会改变剩余窗口，
   * 不重算就会出现「ETA 改晚了但看板还挂着旧档位」。
   */
  @PostMapping("/{trackingId}/events")
  public TrackingRow recordEvent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId,
      @Valid @RequestBody TrackingEventRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tracking.recordEvent(trackingId, a.userId(), request == null ? emptyEvent() : request));
  }

  /**
   * 目的仓收货确认：**只记确认事实，不写任何库存**。
   *
   * <p>入库落账归 7.5.1.2 的 arrive。短少/破损如实写进备注，不自动抹平。
   */
  @PostMapping("/{trackingId}/receipt")
  public TrackingRow confirmReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId,
      @Valid @RequestBody TrackingReceiptRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tracking.confirmReceipt(
                trackingId, a.userId(), request == null ? emptyReceipt() : request));
  }

  /** 关闭跟踪（必须给原因）：货已闭环，或后续由别的机制接手跟踪。 */
  @PostMapping("/{trackingId}/close")
  public TrackingRow close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId,
      @Valid @RequestBody TrackingCloseRequest request) {
    return call(
        auth, "inventory.update", a -> tracking.close(trackingId, a.userId(), request == null ? emptyClose() : request));
  }

  /** 延误预警清单 + 分档汇总（全量，不受分页影响）。 */
  @GetMapping("/alerts")
  public DelayAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> level,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> tracking.alerts(level, status, keyword, page, size));
  }

  /** 确认预警（有人在跟了）。 */
  @PostMapping("/alerts/{alertId}/acknowledge")
  public DelayAlertRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody DelayAlertAckRequest request) {
    return call(auth, "inventory.update", a -> tracking.acknowledge(alertId, a.userId(), request));
  }

  /** 关闭预警（必须给原因：货到了 / 改期了 / 查明原因都算）。 */
  @PostMapping("/alerts/{alertId}/close")
  public DelayAlertRow closeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody DelayAlertCloseRequest request) {
    return call(auth, "inventory.update", a -> tracking.closeAlert(alertId, a.userId(), request));
  }

  /** 阈值清单（标出哪一行正被在途批次命中）。首次访问会惰性补出本租户默认行。 */
  @GetMapping("/thresholds")
  public List<DelayThresholdRow> thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> tracking.thresholds());
  }

  /**
   * 改阈值：运营换预警口径**不发版**。
   *
   * <p>PUT 语义：整行三档提前量一起换口径。三级必须递减，否则「进观察区」反而比
   * 「进提示区」晚，分级会静默倒挂。
   */
  @PutMapping("/thresholds/{thresholdId}")
  public DelayThresholdRow updateThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID thresholdId,
      @Valid @RequestBody DelayThresholdUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tracking.updateThreshold(thresholdId, request == null ? emptyThreshold() : request));
  }

  /** 重算单条跟踪的预警（幂等：同档位已有未结预警只刷新，不堆行）。 */
  @PostMapping("/{trackingId}/alerts/evaluate")
  public java.util.Map<String, Object> evaluateOne(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID trackingId) {
    return call(
        auth,
        "inventory.update",
        a -> java.util.Map.of("raised", tracking.raiseAlerts(trackingId, a.userId())));
  }

  /**
   * 批量重算在途预警。
   *
   * <p>**没有定时器** —— 外部同步与常驻调度均未交付，评估由调用方触发：
   * 打开看板、或运营手工跑一次都可以。返回值是**本次新落库的条数**（0 表示都在刷新，
   * 不是「没跑」）。
   */
  @PostMapping("/alerts/evaluate-all")
  public java.util.Map<String, Object> evaluateAll(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.update", a -> java.util.Map.of("raised", tracking.evaluateAll()));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  static TrackingCreateRequest emptyCreate() {
    return new TrackingCreateRequest(null, null, null, null, null, null, null);
  }

  static TrackingEventRequest emptyEvent() {
    return new TrackingEventRequest(
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  static TrackingReceiptRequest emptyReceipt() {
    return new TrackingReceiptRequest(null, null, null);
  }

  static TrackingCloseRequest emptyClose() {
    return new TrackingCloseRequest(null);
  }

  static DelayThresholdUpdateRequest emptyThreshold() {
    return new DelayThresholdUpdateRequest(null, null, null, null);
  }
}