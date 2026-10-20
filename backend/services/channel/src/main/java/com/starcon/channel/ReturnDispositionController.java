package com.starcon.channel;

import static com.starcon.channel.ReturnDispositionModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.7.1.1 退货接收与处置决策。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器<b>刻意没有「处置方式带默认值」的入口</b>：{@code POST /decisions} 的
 * {@code dispositionMethod} 是<b>必填</b>字段，少给一个值就 422 / DB 拒 ——
 * 绝不因为「没填就当转销售」而落库。蓝图加粗句「默认按"可再售"处理会高估回收价值，
 * 转卖/弃置决策必须显式」在这里是<b>结构性</b>的：请求模型没有默认值、
 * DB 列没有 DEFAULT、服务层不替调用方挑一个。
 *
 * <p>🔴 {@code POST /registrations} 与 {@code GET /eligibility}、{@code POST /decisions}
 * 要读上游（trade 的 11.2 退货管理），所以走 {@link #callWithBearer}：
 * 转发的是<b>调用方自己的</b> bearer，租户仍由上游按已认证成员关系解析（隔离两道）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.2.2.2 面单模板、10.3.2.1 交运批次同族），不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/return-dispositions")
public class ReturnDispositionController {
  private final AccessService access;
  private final ReturnDispositionService service;

  public ReturnDispositionController(AccessService access, ReturnDispositionService service) {
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

  /**
   * 需要把调用方 bearer 透传给上游端口的入口（读 trade 的退货实收与质检结论）。
   *
   * <p>🔴 租户<b>不由本服务传给上游</b>：转发的是调用方自己的 bearer。
   */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public ReturnDispositionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public ReturnDispositionOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 接收登记 ----

  /** 接收登记列表：真分页 + 真筛选。 */
  @GetMapping("/registrations")
  public ReturnReceivingPage registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String warehouseType,
      @RequestParam(required = false) String dispositionState,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.registrations(keyword, warehouseType, dispositionState, page, size));
  }

  /**
   * 登记一次接收（本地仓 / 海外仓 / 平台仓）。
   *
   * <p>🔴 上游实收单必须**已入库（POSTED）**：还在录的实收单随时会改行，
   * 拿它做处置决策等于给一个会变的数签字。
   */
  @PostMapping("/registrations")
  public ReturnReceivingRow register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RegisterReceivingRequest request) {
    return callWithBearer(
        auth, "channel.operation.create", (a, bearer) -> service.register(request, bearer));
  }

  @GetMapping("/registrations/{id}")
  public ReturnReceivingDetail registrationDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth, "channel.operation.read", a -> service.registrationDetail(UUID.fromString(id)));
  }

  // ---- 再销售限制（品牌方要求 / 平台政策） ----

  @GetMapping("/restrictions")
  public ReturnResaleRestrictionPage restrictions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.restrictions(subjectType, status, page, size));
  }

  @PostMapping("/restrictions")
  public ReturnResaleRestrictionRow saveRestriction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveRestrictionRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRestriction(request));
  }

  @PostMapping("/restrictions/{id}/revoke")
  public ReturnResaleRestrictionRow revokeRestriction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RevokeRestrictionRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.revokeRestriction(UUID.fromString(id), request));
  }

  // ---- 🔴 可行性判定（只读，无副作用） ----

  /**
   * 判定这批退货的三个去向各自允许还是被挡。
   *
   * <p>🔴 它**不替人做决定**：处置方式仍然必须由人显式给出。
   * 它的作用是让人在下决定之前看到「凭什么允许 / 凭什么挡住」。
   */
  @GetMapping("/eligibility")
  public ReturnDispositionEligibility eligibility(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String registrationId) {
    return callWithBearer(
        auth,
        "channel.operation.read",
        (a, bearer) -> service.eligibility(UUID.fromString(registrationId), bearer));
  }

  // ---- 处置决策 ----

  @GetMapping("/decisions")
  public ReturnDispositionDecisionPage decisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dispositionMethod,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.decisions(dispositionMethod, status, page, size));
  }

  /**
   * 记录一条处置决策。
   *
   * <p>🔴 {@code dispositionMethod} 必填且无默认值；转销售还要过服务端的两道路
   * （上游质检结论 + 本租户再销售限制），过不去直接 422 并写明被谁挡住。
   */
  @PostMapping("/decisions")
  public ReturnDispositionDecisionRow decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DecideDispositionRequest request) {
    return callWithBearer(
        auth, "channel.operation.create", (a, bearer) -> service.decide(request, bearer));
  }

  @GetMapping("/decisions/{id}")
  public ReturnDispositionDecisionDetail decisionDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth, "channel.operation.read", a -> service.decisionDetail(UUID.fromString(id)));
  }

  // ---- 处置单 ----

  @GetMapping("/orders")
  public ReturnDispositionOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dispositionMethod,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.orders(dispositionMethod, status, page, size));
  }

  /**
   * 下发一张处置单。
   *
   * <p>🔴 请求体里<b>没有</b>处置方式：它只能来自被引用的那条决策。
   * 处置单与决策的「去向相同 / 数量相同 / 决策只能被消费一次」由 DB 触发器钉死。
   */
  @PostMapping("/orders")
  public ReturnDispositionOrderRow issueOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IssueDispositionOrderRequest request) {
    return call(auth, "channel.operation.create", a -> service.issueOrder(request));
  }

  @GetMapping("/orders/{id}")
  public ReturnDispositionOrderDetail orderDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.orderDetail(UUID.fromString(id)));
  }

  /** 追加一条处置成本（销毁费常常是事后才拿到单据）。 */
  @PostMapping("/orders/{id}/costs")
  public ReturnDispositionCostRow recordCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordCostRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.recordCost(UUID.fromString(id), request));
  }

  /**
   * 撤销一张处置单。
   *
   * <p>🔴 撤销**不撤回**已产出的库存状态变更指令：指令是「当时下达过什么」的事实，
   * 消费方 7.4.1.1 未交付，此刻没有任何库存被改动。
   */
  @PostMapping("/orders/{id}/cancel")
  public ReturnDispositionOrderRow cancelOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CancelDispositionOrderRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.cancelOrder(UUID.fromString(id), request));
  }

  // ---- 留痕 ----

  @GetMapping("/events")
  public ReturnDispositionEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String registrationId,
      @RequestParam(required = false) String decisionId,
      @RequestParam(required = false) String orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.events(
                registrationId == null ? null : UUID.fromString(registrationId),
                decisionId == null ? null : UUID.fromString(decisionId),
                orderId == null ? null : UUID.fromString(orderId),
                page,
                size));
  }
}
