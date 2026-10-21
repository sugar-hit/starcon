package com.starcon.channel;

import static com.starcon.channel.ReturnShipbackModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.7.1.2 退运回国。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器服务于蓝图加粗句「退运是批量、长周期、高单票成本操作，需合单以摊薄成本」：
 * {@code POST /consignments} 一次接受<b>多条</b>处置单（凑单 / 合箱），
 * {@code POST /consignments/{id}/costs} 记完成本<b>立刻把成本分摊到每一行</b>，
 * {@code GET /consignments/{id}/allocations} 把分摊明细（权重 / 比例 / 金额 / 尾差）摊开给人看。
 *
 * <p>🔴 本模块**不跨服务调用**：待退运清单来自 10.7.1.1（**同服务**，直接读处置单），
 * 报关资料是本服务自持的快照（10.5.1 / 3.7 在 warehouse / trade / catalog）。
 * 下游 7 库存与 12 财务未交付 ⇒ 重新入库分录只出事实不消费。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.3.2.1 交运批次、10.7.1.1 退货处置同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/return-shipbacks")
public class ReturnShipbackController {
  private final AccessService access;
  private final ReturnShipbackService service;

  public ReturnShipbackController(AccessService access, ReturnShipbackService service) {
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

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public ReturnShipbackCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public ReturnShipbackOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 待退运清单（上游 10.7.1.1） ----

  /**
   * 待退运清单：只取 10.7.1.1 决策为「退运回国」的处置单。
   *
   * <p>🔴 转销售 / 弃置销毁的处置单**根本不会**出现在这里。
   */
  @GetMapping("/candidates")
  public ShipbackCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.candidates(keyword, page, size));
  }

  // ---- 退运单 ----

  /** 退运单列表：真分页 + 真筛选。 */
  @GetMapping("/consignments")
  public ReturnShipbackPage consignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String transportMode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.consignments(keyword, status, transportMode, page, size));
  }

  /**
   * 建一张退运单（合单）。
   *
   * <p>🔴 请求体里的 {@code lines} 就是「凑单 / 合箱」：一次可以把多条处置单的货装进
   * 同一张退运单，从而摊薄单票成本。
   */
  @PostMapping("/consignments")
  public ReturnShipbackDetail createConsignment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateConsignmentRequest request) {
    return call(auth, "channel.operation.create", a -> service.createConsignment(request));
  }

  @GetMapping("/consignments/{id}")
  public ReturnShipbackDetail consignmentDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth, "channel.operation.read", a -> service.consignmentDetail(UUID.fromString(id)));
  }

  /** 往既有退运单里再加入一行（继续凑单）。 */
  @PostMapping("/consignments/{id}/lines")
  public ReturnShipbackDetail addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AddLineRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.addLine(UUID.fromString(id), request));
  }

  // ---- 报关资料 ----

  /** 登记报关资料（申报品名 / HS 编码 / 货值 / 关税）。 */
  @PostMapping("/consignments/{id}/customs")
  public ReturnShipbackDeclarationRow recordDeclaration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordShipbackDeclarationRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.recordDeclaration(UUID.fromString(id), request));
  }

  // ---- 🔴 成本与分摊 ----

  /**
   * 登记一笔退运成本，并**立刻把成本分摊到每一行**。
   *
   * <p>🔴 返回值里是逐行分摊（权重 / 权重合计 / 比例 / 分摊金额 / 尾差调整额），
   * 合计恒等于该笔成本金额。
   */
  @PostMapping("/consignments/{id}/costs")
  public ShipbackAllocationResult recordCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordShipbackCostRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.recordCost(UUID.fromString(id), request));
  }

  /** 成本分摊明细（逐行）。 */
  @GetMapping("/consignments/{id}/allocations")
  public List<ReturnShipbackAllocationRow> allocations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.consignmentDetail(UUID.fromString(id)).allocations());
  }

  // ---- 状态推进 ----

  /** 确认退运单（货已凑齐、报关齐备）。 */
  @PostMapping("/consignments/{id}/confirm")
  public ReturnShipbackDetail confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) AdvanceShipbackRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.confirm(UUID.fromString(id), request));
  }

  /** 发出退运单（已交承运商）。 */
  @PostMapping("/consignments/{id}/dispatch")
  public ReturnShipbackDetail dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) AdvanceShipbackRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.dispatch(UUID.fromString(id), request));
  }

  /** 登记回国收货（国内收货仓签收）。 */
  @PostMapping("/consignments/{id}/arrival")
  public ReturnShipbackDetail recordArrival(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordShipbackArrivalRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.recordArrival(UUID.fromString(id), request));
  }

  /**
   * 登记重新入库 / 报废分录（→ 7 库存 / 10.7.1.3）。
   *
   * <p>🔴 消费方均未交付 ⇒ 分录恒为 PENDING，本模块只出事实。
   */
  @PostMapping("/consignments/{id}/entries")
  public ReturnShipbackDetail recordEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordShipbackEntryRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.recordEntry(UUID.fromString(id), request));
  }

  /** 关闭退运单（账已结清）。 */
  @PostMapping("/consignments/{id}/close")
  public ReturnShipbackDetail close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) AdvanceShipbackRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.close(UUID.fromString(id), request));
  }

  /** 取消退运单。 */
  @PostMapping("/consignments/{id}/cancel")
  public ReturnShipbackDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) AdvanceShipbackRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.cancel(UUID.fromString(id), request));
  }

  // ---- 留痕 ----

  @GetMapping("/events")
  public ReturnShipbackEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String consignmentId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.events(
                consignmentId == null ? null : UUID.fromString(consignmentId), page, size));
  }
}
