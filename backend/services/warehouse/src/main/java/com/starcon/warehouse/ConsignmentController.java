package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentModels.*;

import java.util.List;
import java.util.UUID;
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
 * 7.7.1.2 寄售与代销。
 *
 * <p>蓝图：「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、
 * 销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」
 * 输出：「寄售库存视图（寄售量/已售/剩余）；结算触发单（→ 12 应付）；期末退回或转入采购的单据。」
 *
 * <p>命名空间沿用 {@code /warehouse/inventory/...}，权限码 {@code warehouse.inventory.read} /
 * {@code warehouse.inventory.update}。端点独立成 {@code ConsignmentController} 而不是并进
 * {@code InventoryController}：那是 7.4.2.1 的文件，两条车道并行时各改各的才不会撞。
 *
 * <p><b>本模块没有「按寄售量出应付」的端点，而且数据库也不让这么算</b>：
 * 应付只从 {@code /settlements} 出，且每条结算明细都指回一条具体销售事实；
 * V71 把应付额做成生成列（已售数量 × 结算价）并对 sale_id 加唯一约束。
 * 蓝图那句「寄售未售出不产生采购成本与应付」因此是可执行的，而不是一句承诺。
 */
@RestController
@RequestMapping("/warehouse")
public class ConsignmentController {

  private final AccessService access;
  private final ConsignmentService consignments;

  public ConsignmentController(AccessService access, ConsignmentService consignments) {
    this.access = access;
    this.consignments = consignments;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.4.2.1 同一个 call）。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 寄售能力：经营模式、结算周期、期末处理、退货性质，以及五条必须如实讲清楚的说明
   * （应付口径、采购成本为什么算不出来、货主口径、销售事实来源、交接口径）。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   */
  @GetMapping("/inventory/consignments/capabilities")
  public ConsignmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> consignments.capabilities());
  }

  // ------------------------------------------------------------ 协议

  /** 建寄售协议（草稿）并同时写入明细行。协议与明细一起提交，没有明细的协议无法结算。 */
  @PostMapping("/inventory/consignments")
  public ConsignmentAgreementDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ConsignmentAgreementInput input) {
    return call(auth, "inventory.update", a -> consignments.createAgreement(input, a.userId()));
  }

  /** 协议列表（真分页；行数与五个数量由明细聚合得出，不另存列）。 */
  @GetMapping("/inventory/consignments")
  public ConsignmentAgreementPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> consignments.agreements(status, mode, keyword, page, size));
  }

  /** 协议详情 = 头部 + 全部明细（寄售量/已售/退回/转采购/剩余）+ 审计时间线。 */
  @GetMapping("/inventory/consignments/{agreementId}")
  public ConsignmentAgreementDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> consignments.agreement(agreementId));
  }

  /** 给草稿协议补一个 SKU（寄售量 + 结算价）。已在跑的协议不能中途改价。 */
  @PostMapping("/inventory/consignments/{agreementId}/lines")
  public ConsignmentAgreementDetail addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody ConsignmentAddLineInput input) {
    return call(auth, "inventory.update", a -> consignments.addLine(agreementId, input, a.userId()));
  }

  /** 启用协议：DRAFT → ACTIVE。售出、结算都要求协议已启用。 */
  @PostMapping("/inventory/consignments/{agreementId}/activate")
  public ConsignmentAgreement activate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.activate(agreementId, input, a.userId()));
  }

  /** 暂停协议：ACTIVE → SUSPENDED。暂停后不得再记销售。 */
  @PostMapping("/inventory/consignments/{agreementId}/suspend")
  public ConsignmentAgreement suspend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.suspend(agreementId, input, a.userId()));
  }

  /** 关闭协议：→ CLOSED。关闭后全部明细行被数据库冻结，数量不能再动。 */
  @PostMapping("/inventory/consignments/{agreementId}/close")
  public ConsignmentAgreement close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.close(agreementId, input, a.userId()));
  }

  // ------------------------------------------------------------ 寄售可用量

  /**
   * 寄售库存视图：寄售量 / 已售 / 退回 / 转采购 / 剩余，以及「尚未结算的已售量与应付预估」。
   *
   * <p>{@code ownedAssetValue} 恒为 null、{@code countsAsOwnedAsset} 恒为 false：
   * 寄售货主是供应商，我方只记持有位置与可销售权，不记为自有资产。
   */
  @GetMapping("/inventory/consignments/{agreementId}/stock-view")
  public ConsignmentStockView stockView(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> consignments.stockView(agreementId));
  }

  // ------------------------------------------------------------ 销售事实

  /**
   * 记一条销售事实 —— 这是「销售触发结算数量」的唯一来源。
   *
   * <p>协议必须已启用；本次售出量不得超过该行剩余；{@code (协议行, saleRef)} 唯一，
   * 所以重复推送不会卖两次、也就不会算两次应付。
   */
  @PostMapping("/inventory/consignments/{agreementId}/lines/{lineId}/sales")
  public ConsignmentSale recordSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @PathVariable UUID lineId,
      @RequestBody ConsignmentSaleInput input) {
    return call(auth, "inventory.update", a -> consignments.recordSale(agreementId, lineId, input, a.userId()));
  }

  /** 销售事实列表（真分页；{@code unsettleableOnly=true} 只看还没进结算单的）。 */
  @GetMapping("/inventory/consignments/{agreementId}/sales")
  public ConsignmentSalePage sales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestParam(required = false) Boolean unsettleableOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> consignments.sales(agreementId, unsettleableOnly, page, size));
  }

  // ------------------------------------------------------------ 结算触发单（→ 12 应付）

  /** 按协议的结算周期算出当前默认结算区间（WEEKLY / SEMI_MONTHLY / MONTHLY 是真算的）。 */
  @GetMapping("/inventory/consignments/{agreementId}/settlement-window")
  public SettlementWindow settlementWindow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> consignments.settlementWindow(agreementId));
  }

  /**
   * 出结算触发单：把区间内**已售但还没进任何结算单**的销售事实收成一张交给 12 应付。
   *
   * <p>区间内没有未结算销售事实时显式拒绝 —— 未售出不产生应付，这正是寄售该有的结果，
   * 不该靠一张空单来「显得处理过了」。
   */
  @PostMapping("/inventory/consignments/{agreementId}/settlements")
  public ConsignmentSettlementDetail createSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody ConsignmentSettlementInput input) {
    return call(auth, "inventory.update", a -> consignments.createSettlement(agreementId, input, a.userId()));
  }

  /** 结算触发单列表（真分页；合计由数据库延迟约束保证 == Σ 明细）。 */
  @GetMapping("/inventory/consignments/settlements")
  public ConsignmentSettlementPage settlements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID agreementId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> consignments.settlements(agreementId, status, page, size));
  }

  /** 结算单详情：每行都指回触发它的那条销售事实，「应付从哪来」一眼可追。 */
  @GetMapping("/inventory/consignments/settlements/{settlementId}")
  public ConsignmentSettlementDetail settlementDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID settlementId) {
    return call(auth, "inventory.read", a -> consignments.settlementDetail(settlementId));
  }

  /** 确认结算触发单 → 应付正式交 12 应付（写 reference_id 定位键）。确认后单据被冻结。 */
  @PostMapping("/inventory/consignments/settlements/{settlementId}/confirm")
  public ConsignmentSettlement confirmSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID settlementId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.confirmSettlement(settlementId, input, a.userId()));
  }

  // ------------------------------------------------------------ 未售退货与期末清理

  /**
   * 建未售退货 / 期末清理单（草稿）。
   *
   * <p>{@code disposition} 决定这张单会不会产生应付：退回供应商不产生；
   * 转入采购才产生，且必须与协议约定的期末处理一致。
   */
  @PostMapping("/inventory/consignments/{agreementId}/returns")
  public ConsignmentReturnDetail createReturn(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody ConsignmentReturnInput input) {
    return call(auth, "inventory.update", a -> consignments.createReturn(agreementId, input, a.userId()));
  }

  /** 退货/清理单列表（真分页）。 */
  @GetMapping("/inventory/consignments/returns")
  public ConsignmentReturnPage returns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID agreementId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> consignments.returns(agreementId, status, page, size));
  }

  /** 退货/清理单详情 = 头部 + 全部明细。 */
  @GetMapping("/inventory/consignments/returns/{returnId}")
  public ConsignmentReturnDetail returnDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID returnId) {
    return call(auth, "inventory.read", a -> consignments.returnDetail(returnId));
  }

  /** 执行退货/清理单：DRAFT → EXECUTED。**这一步才真正改动寄售剩余量。** */
  @PostMapping("/inventory/consignments/returns/{returnId}/execute")
  public ConsignmentReturn executeReturn(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID returnId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.executeReturn(returnId, input, a.userId()));
  }

  /** 作废退货/清理单：DRAFT → CANCELLED。草稿没动过数量，作废是无损的。 */
  @PostMapping("/inventory/consignments/returns/{returnId}/cancel")
  public ConsignmentReturn cancelReturn(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID returnId,
      @RequestBody(required = false) ConsignmentNoteInput input) {
    return call(auth, "inventory.update", a -> consignments.cancelReturn(returnId, input, a.userId()));
  }

  // ------------------------------------------------------------ 审计

  /** 审计时间线：谁启用/暂停/关闭了协议、谁记了售出、谁出了结算单、谁执行了退回（append-only）。 */
  @GetMapping("/inventory/consignments/{agreementId}/audit")
  public List<ConsignmentAuditEntry> audit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestParam(required = false) Integer limit) {
    return call(auth, "inventory.read", a -> consignments.auditTrail(agreementId, limit));
  }
}
