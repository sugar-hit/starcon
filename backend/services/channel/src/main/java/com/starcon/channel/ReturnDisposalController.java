package com.starcon.channel;

import static com.starcon.channel.ReturnDisposalModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.7.1.3 弃置与销毁。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器服务于蓝图加粗句「品牌化客户常有『销毁并回传证明』合约义务，
 * <b>销毁凭证缺失会触发品牌方追责</b>」：
 * {@code POST /orders/{id}/evidence} 一次登记**一类**凭证，
 * {@code POST /orders/{id}/dispose} 在凭证不齐时**直接拒绝**（422）——
 * 「销毁完了但没有凭证」是追责的起点，这条路在服务端就堵死。
 *
 * <p>🔴 本模块**不跨服务调用**：待销毁清单来自 10.7.1.1（**同服务**，直接读处置单）；
 * 下游 12 财务（服务不存在）与 11.2.3.3 供应商索赔（trade，跨服务）
 * ⇒ 费用与索赔依据只落本服务内的事实。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.3.2.1 交运批次、10.7.1.1 退货处置、10.7.1.2 退运回国同族），
 * 不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/return-disposals")
public class ReturnDisposalController {
  private final AccessService access;
  private final ReturnDisposalService service;

  public ReturnDisposalController(AccessService access, ReturnDisposalService service) {
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
  public ReturnDisposalCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public ReturnDisposalOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 待销毁清单（上游 10.7.1.1） ----

  /**
   * 待销毁清单：只取 10.7.1.1 决策为「弃置销毁」的处置单。
   *
   * <p>🔴 转销售 / 退运回国的处置单**根本不会**出现在这里。
   */
  @GetMapping("/candidates")
  public DisposalCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.candidates(keyword, page, size));
  }

  // ---- 凭证要求（配置） ----

  /** 凭证要求清单：品牌方要求 / 平台政策 / 租户默认。 */
  @GetMapping("/policies")
  public ReturnDisposalPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.policies(subjectType, status, page, size));
  }

  /** 登记一条凭证要求（🔴 只能加严不能放松）。 */
  @PostMapping("/policies")
  public ReturnDisposalPolicyRow createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateDisposalPolicyRequest request) {
    return call(auth, "channel.operation.create", a -> service.createPolicy(request));
  }

  /** 撤销一条凭证要求（必须写理由）。 */
  @PostMapping("/policies/{id}/revoke")
  public ReturnDisposalPolicyRow revokePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) RevokeDisposalPolicyRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.revokePolicy(UUID.fromString(id), request));
  }

  // ---- 销毁单 ----

  /** 销毁单列表：真分页 + 真筛选。 */
  @GetMapping("/orders")
  public ReturnDisposalPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String method,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.orders(keyword, status, method, page, size));
  }

  /** 建一张销毁单（货来自 10.7.1.1 的「弃置销毁」处置单）。 */
  @PostMapping("/orders")
  public ReturnDisposalDetail createOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateDisposalOrderRequest request) {
    return call(auth, "channel.operation.create", a -> service.createOrder(request));
  }

  @GetMapping("/orders/{id}")
  public ReturnDisposalDetail orderDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth, "channel.operation.read", a -> service.orderDetail(UUID.fromString(id)));
  }

  /** 排期：凭证要求在这一刻被冻结。 */
  @PostMapping("/orders/{id}/schedule")
  public ReturnDisposalDetail schedule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) ScheduleDisposalRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.schedule(UUID.fromString(id), request));
  }

  /**
   * 登记一类销毁凭证（照片 / 重量 / 处置证明编号 / 执行方签核）。
   *
   * <p>🔴 一类凭证一张：重复登记改写那一行，不插第二张互相矛盾的照片。
   */
  @PostMapping("/orders/{id}/evidence")
  public ReturnDisposalDetail recordEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordDisposalEvidenceRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.recordEvidence(UUID.fromString(id), request));
  }

  /** 记一笔销毁费用（合计查询时现算，不落表）。 */
  @PostMapping("/orders/{id}/costs")
  public ReturnDisposalDetail recordCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RecordDisposalCostRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.recordCost(UUID.fromString(id), request));
  }

  /** 生成索赔依据（🔴 数量与重量必须与销毁单一致）。 */
  @PostMapping("/orders/{id}/claim-basis")
  public ReturnDisposalDetail createClaimBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CreateDisposalClaimBasisRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.createClaimBasis(UUID.fromString(id), request));
  }

  /**
   * 执行销毁（完成态）。
   *
   * <p>🔴 凭证不齐 ⇒ 422 直接拒绝，不许把销毁单置为完成态。
   */
  @PostMapping("/orders/{id}/dispose")
  public ReturnDisposalDetail dispose(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) DisposeDisposalRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.dispose(UUID.fromString(id), request));
  }

  /** 取消销毁单。 */
  @PostMapping("/orders/{id}/cancel")
  public ReturnDisposalDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) CancelDisposalRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.cancel(UUID.fromString(id), request));
  }

  // ---- 索赔依据 ----

  /** 提交下游索赔（回填下游索赔单号；🔴 下游在 trade，本模块不写它的表）。 */
  @PostMapping("/claim-bases/{id}/submit")
  public ReturnDisposalDetail submitClaimBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) SubmitDisposalClaimBasisRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.submitClaimBasis(UUID.fromString(id), request));
  }

  /** 下游驳回索赔依据。 */
  @PostMapping("/claim-bases/{id}/reject")
  public ReturnDisposalDetail rejectClaimBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) RejectDisposalClaimBasisRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.rejectClaimBasis(UUID.fromString(id), request));
  }

  // ---- 留痕 ----

  @GetMapping("/events")
  public ReturnDisposalEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.events(
                orderId == null ? null : UUID.fromString(orderId), page, size));
  }
}
