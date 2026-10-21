package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAdjustmentModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.4.1.1 调整单管理。
 *
 * <p>蓝图：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输入：「调整请求（bucket、数量、原因码、附件）；{@code expected_version}。」
 * 输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <p>命名空间 {@code /warehouse/inventory/adjustments}（蓝图路由 {@code /inventory/adjustments}）：
 * 刻意**独立**于 7.4.2.2 盘点差异处理的 {@code /warehouse/inventory/stocktake-variances} ——
 * 那套调整单要求先有盘点表，本模块是通用调整单，两者不共用表也不共用端点。
 *
 * <p><b>本模块一个「直接改余额」的端点都没有，而且数据库也不让绕过流程改</b>：
 * 唯一会动余额的是 {@code /adjustments/{id}/post}，它要求单据已经 APPROVED，
 * 而 APPROVED 只能由**另一个人**批出来（自提自批被拒）。V260 还有一道入账闸门：
 * 未审批的调整单在 DB 层写不进 {@code inventory_entry}。
 */
@RestController
@RequestMapping("/warehouse")
public class InventoryAdjustmentController {

  private final AccessService access;
  private final InventoryAdjustmentService adjustments;

  public InventoryAdjustmentController(
      AccessService access, InventoryAdjustmentService adjustments) {
    this.access = access;
    this.adjustments = adjustments;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 需要把 bearer 透传给跨服务端口的端点用这个变体。
   *
   * <p>端口转发的是<b>调用方自己的</b> bearer（6.4.2.2 那边仍按已认证成员关系解析租户并强制 RLS），
   * 不是本服务替对方决定租户。
   */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor, bearer));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 调整单能力：原因码词表（含不可用原因码的归属）、状态机、合法转移，
   * 以及必须如实讲清楚的边界（阈值审批归 7.4.1.2、平台仓差异为什么不可用、凭证是逻辑引用）。
   */
  @GetMapping("/inventory/adjustments/capabilities")
  public InventoryAdjustmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "inventory.read", (a, bearer) -> adjustments.capabilities(bearer));
  }

  // ------------------------------------------------------------ 调整单

  /**
   * 调整单列表（真分页；行数与增/减调数量由明细现算，不另存列）。
   *
   * <p>筛选：状态、原因码、关键字（单号/备注/来源单号/SKU/库位/批次）。
   */
  @GetMapping("/inventory/adjustments")
  public InventoryAdjustmentOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> adjustments.orders(status, reasonCode, keyword, page, size));
  }

  /**
   * 发起调整单（草稿）：一次给多行 = 批量调整。
   *
   * <p>它是**草稿**：此刻一行余额都还没动。每行带 {@code expected_version}（读这行时的
   * 库位明细版本），与当前不一致返 409 —— 拿旧数字改新余额就是在改一个不存在的数。
   */
  @PostMapping("/inventory/adjustments")
  public InventoryAdjustmentOrderDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentCreateInput input) {
    return callWithBearer(
        auth, "inventory.update", (a, bearer) -> adjustments.create(input, a.userId(), bearer));
  }

  /** 调整单详情 = 头部 + 全部行 + 凭证清单 + 完整审计时间线。 */
  @GetMapping("/inventory/adjustments/{orderId}")
  public InventoryAdjustmentOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> adjustments.detail(orderId));
  }

  /** 往草稿单上加一行（批量调整的增量入口；已提交的单据行是证据，不许动）。 */
  @PostMapping("/inventory/adjustments/{orderId}/lines")
  public InventoryAdjustmentOrderDetail addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody InventoryAdjustmentLineInput input) {
    return call(auth, "inventory.update", a -> adjustments.addLine(orderId, input, a.userId()));
  }

  /** 从草稿单上摘掉一行（只有草稿能摘；已提交的行是证据，DB 层也会挡）。 */
  @PostMapping("/inventory/adjustments/{orderId}/lines/{lineId}/remove")
  public InventoryAdjustmentOrderDetail removeLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @PathVariable UUID lineId) {
    return call(auth, "inventory.update", a -> adjustments.removeLine(orderId, lineId, a.userId()));
  }

  /** 给草稿单挂一份凭证（catalog 媒体库的逻辑引用 + 快照元数据；本模块不提供上传端点）。 */
  @PostMapping("/inventory/adjustments/{orderId}/attachments")
  public InventoryAdjustmentOrderDetail attach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody InventoryAdjustmentAttachmentInput input) {
    return call(auth, "inventory.update", a -> adjustments.attach(orderId, input, a.userId()));
  }

  /** 从草稿单上摘掉一份凭证（只有草稿能摘）。 */
  @PostMapping("/inventory/adjustments/{orderId}/attachments/{attachmentId}/remove")
  public InventoryAdjustmentOrderDetail detach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @PathVariable UUID attachmentId) {
    return call(auth, "inventory.update", a -> adjustments.detach(orderId, attachmentId, a.userId()));
  }

  /** 提交待审：DRAFT → PENDING_APPROVAL。此刻余额仍未改动。 */
  @PostMapping("/inventory/adjustments/{orderId}/submit")
  public InventoryAdjustmentOrder submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentNoteInput input) {
    return call(auth, "inventory.update", a -> adjustments.submit(orderId, input, a.userId()));
  }

  /**
   * 批准：PENDING_APPROVAL → APPROVED。
   *
   * <p><b>提交人不能批自己提的单</b>，且必须写理由。批准**仍然不改余额** ——
   * 它只是允许入账，入账是独立的 {@code /post} 一步。
   *
   * <p>阈值审批规则（金额/数量阈值、批量审批、17.4 审批中心）归 7.4.1.2，本模块不实现审批人角色表。
   */
  @PostMapping("/inventory/adjustments/{orderId}/approve")
  public InventoryAdjustmentOrder approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentNoteInput input) {
    return call(auth, "inventory.update", a -> adjustments.approve(orderId, input, a.userId()));
  }

  /** 驳回：PENDING_APPROVAL → REJECTED，意见必填；可改完重提。 */
  @PostMapping("/inventory/adjustments/{orderId}/reject")
  public InventoryAdjustmentOrder reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentNoteInput input) {
    return call(auth, "inventory.update", a -> adjustments.reject(orderId, input, a.userId()));
  }

  /** 作废：任何未过账状态 → CANCELLED。已过账的单据不能作废（账已经动了）。 */
  @PostMapping("/inventory/adjustments/{orderId}/cancel")
  public InventoryAdjustmentOrder cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentNoteInput input) {
    return call(auth, "inventory.update", a -> adjustments.cancel(orderId, input, a.userId()));
  }

  /**
   * 过账：APPROVED → POSTED。**本模块唯一会改库存余额的端点。**
   *
   * <p>一个事务里写业务单（kind=ADJUSTMENT、ref_type=INVENTORY_ADJUSTMENT 定位键）、
   * 逐行写带符号分录、改余额行与库位明细、标记行已过账、写审计。
   *
   * <p>未审批的单据在这里被 409 挡住，而 V260 的入账闸门在数据库层再挡一次 ——
   * 蓝图那句「调整必须走单据」因此是可执行的，而不是一句承诺。
   */
  @PostMapping("/inventory/adjustments/{orderId}/post")
  public InventoryAdjustmentPostResult post(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentNoteInput input) {
    return call(auth, "inventory.update", a -> adjustments.post(orderId, input, a.userId()));
  }

  // ------------------------------------------------------------ 6.4.2.2 来源

  /**
   * 6.4.2.2 不良品处置单候选（跨服务只读：trade 是那张单的事实所有者）。
   *
   * <p>上游不可达时如实返 503 并说明原因，**不返回空清单** —— 空清单会被读成
   * 「这批货还没登记不良品」，而真实情况是 trade 服务挂了。
   */
  @GetMapping("/inventory/adjustment-sources/defective-dispositions")
  public InventoryAdjustmentDefectiveSourcePage defectiveDispositions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return callWithBearer(
        auth,
        "inventory.read",
        (a, bearer) -> adjustments.defectiveSources(bearer, keyword, page, size));
  }

  /** 一张 6.4.2.2 处置单的库存处置指令（调整单可引用其中一条作为上游来源）。 */
  @GetMapping("/inventory/adjustment-sources/defective-dispositions/{orderId}/instructions")
  public List<InventoryAdjustmentDefectiveInstruction> defectiveInstructions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return callWithBearer(
        auth, "inventory.read", (a, bearer) -> adjustments.defectiveInstructions(orderId, bearer));
  }
}
