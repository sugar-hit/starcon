package com.starcon.channel;

import static com.starcon.channel.StockForecastModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 10.4.2.2 备货预报。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>权限码**复用** identity 已有的 {@code channel.operation.*}（备货预报属于「平台侧操作」，
 * 与 10.4.2.1 的 {@code channel.connection.*} / {@code channel.sync.*} 同一纪律），
 * 因此本模块**不需要改动 identity 服务**，跨车道回归面为 0。
 *
 * <p>🔴 刻意**没有 DELETE 段**：预报单、明细、货件任务、仓端回执都是凭据 ——
 * 回执是已发生的事实（DB 层 append-only），预报单靠状态机收口（作废），不靠物理删除。
 */
@RestController
@RequestMapping("/channel/stock-forecasts")
public class StockForecastController {
  private final AccessService access;
  private final StockForecastService service;

  public StockForecastController(AccessService access, StockForecastService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 能力与参照 ----

  /**
   * 能力声明：哪些能力已接通、哪些还没有及归属模块。
   *
   * <p>前端据此禁用不可用的动作（比如「平台货件接口直连」恒为 false），
   * 而不是让用户填完再收一个 422。
   */
  @GetMapping("/capabilities")
  public ForecastCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 目的仓下拉项（10.4.2.1 的仓账户，同库同服务，直接读）。 */
  @GetMapping("/accounts")
  public List<java.util.Map<String, Object>> accounts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.destinationAccounts());
  }

  // ---- 备货规格（箱型 / 贴标 / 托盘 / 混装） ----

  @GetMapping("/specs")
  public List<ForecastSpecRow> specs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId) {
    return call(auth, "channel.operation.read", a -> service.specs(accountId));
  }

  /** 新建一版规格（旧版自动停用：同一目的仓同时只能有一版生效）。 */
  @PostMapping("/specs")
  public UUID specCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateForecastSpecRequest input) {
    return call(auth, "channel.operation.create", a -> service.createSpec(input));
  }

  @PostMapping("/specs/{id}/retire")
  public void specRetire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.operation.update", a -> service.retireSpec(id));
  }

  // ---- 预报单 ----

  /** 预报单列表：真分页 + 真筛选 + 真排序（筛选与排序全在服务端）。 */
  @GetMapping
  public ForecastPage forecasts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.forecasts(status, accountId, keyword, sort, page, size));
  }

  /** 建预报单（SKU 与数量 + 目的仓 + 期望到货时间）。 */
  @PostMapping
  public UUID forecastCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateForecastRequest input) {
    return call(auth, "channel.operation.create", a -> service.createForecast(input));
  }

  @GetMapping("/{id}")
  public ForecastDetail forecast(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.forecast(id));
  }

  /** 替换明细（只有草稿态允许；离开草稿态后明细就是承诺，改不动）。 */
  @PutMapping("/{id}/lines")
  public void forecastReplaceLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReplaceForecastLinesRequest input) {
    callVoid(auth, "channel.operation.update", a -> service.replaceLines(id, input));
  }

  /** 规格校验结果（只读，不改状态）。 */
  @GetMapping("/{id}/spec-check")
  public SpecCheckResult specCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.specCheck(id));
  }

  /**
   * 过规格闸。
   *
   * <p>🔴 规格不符 → <b>422</b>，并在响应体里挂 {@code violations} 数组逐条点名违反哪一条。
   * 蓝图明说规格不符会被仓端拒收、造成额外成本与延误，所以这一步必须是提交前的硬闸。
   */
  @PostMapping("/{id}/validate")
  public SpecCheckResult validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.update", a -> service.validate(id));
  }

  /** 提交预报单 → 生成平台货件创建任务。 */
  @PostMapping("/{id}/submit")
  public UUID submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.update", a -> service.submit(id));
  }

  /** 作废预报单（草稿/已校验/已提交可作废；已收/已拒是终态）。 */
  @PostMapping("/{id}/cancel")
  public void cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String reason) {
    callVoid(auth, "channel.operation.update", a -> service.cancel(id, reason));
  }

  /**
   * 备货可用性（自营可售量 + 头程在途量）。
   *
   * <p>🔴 两个口径都经 warehouse 的**授权只读接口**读（跨服务 SQL / 跨库视图被 AGENTS.md 禁止）。
   * 读不到时返回 <b>503</b>，**绝不**用 0 冒充 —— 0 会被读成「没有库存需求」。
   */
  @GetMapping("/{id}/availability")
  public ForecastAvailabilityPage availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.availability(id, page, size, auth));
  }

  // ---- 平台货件创建任务 ----

  @GetMapping("/{id}/shipment-task")
  public ShipmentTaskRow shipmentTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.shipmentTask(id));
  }

  /**
   * 推进货件任务（{@code PENDING → SUBMITTED → SUCCEEDED / FAILED}）。
   *
   * <p>🔴 这一跳不打真实平台域名：本仓库没有真实平台账号与网络出口，
   * 适配器读的是租户/通道登记的**平台货件报送行**。平台侧没登记 → 任务 FAILED 且可重试，
   * **绝不**凭空造一个货件编号。
   *
   * <p>🔴 服务商侧失败时返回 <b>502 + 任务体</b>（不是抛异常）：抛异常会让租户事务回滚，
   * 把刚写下的 FAILED 状态一起回滚掉，现场就变成「什么都没发生过」。
   * 所以这里用状态码如实说「没成��」，任务体里带着失败原因。
   */
  @PostMapping("/{id}/shipment-task/dispatch")
  public org.springframework.http.ResponseEntity<ShipmentTaskRow> dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    ShipmentTaskRow task =
        call(auth, "channel.operation.update", a -> service.dispatchTask(id, auth));
    if ("FAILED".equals(task.status())) {
      return org.springframework.http.ResponseEntity.status(502).body(task);
    }
    return org.springframework.http.ResponseEntity.ok(task);
  }

  /** 重试失败的货件任务。 */
  @PostMapping("/{id}/shipment-task/retry")
  public ShipmentTaskRow retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.update", a -> service.retryTask(id));
  }

  /** 登记平台货件报送行（货件任务的真实输入：平台侧回报或人工登记）。 */
  @PostMapping("/{id}/shipment-reports")
  public UUID shipmentReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CreateShipmentReportRequest input) {
    return call(auth, "channel.operation.update", a -> service.registerShipmentReport(id, input));
  }

  @GetMapping("/{id}/shipment-reports")
  public List<ShipmentReportRow> shipmentReports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.shipmentReports(id));
  }

  // ---- 仓端回执（接收 / 拒收） ----

  /**
   * 登记仓端回执。
   *
   * <p>🔴 拒收是**真实业务结果**：规格不符被拒收会造成额外成本与延误，
   * 所以接收与拒收两条回执都落库并可查；拒收必须写理由。
   */
  @PostMapping("/{id}/receipts")
  public UUID receipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CreateReceiptRequest input) {
    return call(auth, "channel.operation.update", a -> service.registerReceipt(id, input));
  }

  @GetMapping("/{id}/receipts")
  public List<ForecastReceiptRow> receipts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.receipts(id));
  }
}
