package com.starcon.warehouse;

import static com.starcon.warehouse.VmiModels.*;

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
 * 7.7.2.1 VMI 供应商管理库存。
 *
 * <p>蓝图：「支持供应商管理库存（VMI）：货权属供应商、我方享有可销售权，按约定补货或由系统触发供应商备货；
 * 记录安全水位与补货信号，售出后与供应商对账结算。」
 * 输入「VMI 协议（SKU、水位、补货倍数、结算价、结算周期）；实时销量与在库数据。」
 * 输出「VMI 水位监控与补货信号（推给供应商或 6.2.1.2 备货）；VMI 结算对账单。」
 *
 * <p>命名空间沿用 {@code /warehouse/inventory/vmi}，权限码 {@code warehouse.inventory.read} /
 * {@code warehouse.inventory.update}。端点独立成 {@code VmiController} 而不是并进
 * {@code InventoryController}：那是 7.4.2.1 的文件，各车道并行时各改各的才不会撞。
 *
 * <p><b>本模块没有「按在库量出应付」的端点，而且数据库也不让这么算</b>：应付只从
 * {@code /settlements} 出，且每条对账明细都指回一条具体售出事实；V115 把明细金额做成
 * 生成列（数量 × 结算价）、对 {@code sale_id} 加唯一约束，并要求一条售出事实最多进一张
 * 对账单。蓝图那句「售出后与供应商对账结算」因此是可执行的，而不是一句承诺。
 *
 * <p><b>「可售 ≠ 自有」在端点层的落法</b>：{@code /water-levels} 一次返回两张分开的卡片 ——
 * {@code ownershipSplit}（VMI 货：可售额计入、{@code ownedAssetValue} 恒 null）与
 * {@code selfOwnedSplit}（同一 SKU×仓 在我方 SELF 货主下的量）。没有「库存价值」这种合并字段。
 */
@RestController
@RequestMapping("/warehouse")
public class VmiController {

  private final AccessService access;
  private final VmiService vmi;

  public VmiController(AccessService access, VmiService vmi) {
    this.access = access;
    this.vmi = vmi;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * VMI 能力：补货模式、信号去向、结算周期、触发原因、在库量来源，以及
   * **未接入的上游/下游环节清单**（13 销量、6.2.1.2 采购、12 结算、9 订单、供应商侧采集）。
   *
   * <p>上游未交付是事实，但必须**显式报出**：靠 404 或空数组让人猜哪一环没通，
   * 迟早会有人把「没数据」当成「没问题」。
   */
  @GetMapping("/inventory/vmi/capabilities")
  public VmiCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> vmi.capabilities());
  }

  // ------------------------------------------------------------ 协议

  /** 建 VMI 协议（草稿）并同时写入明细行（水位、补货倍数、结算价都在行上）。 */
  @PostMapping("/inventory/vmi/agreements")
  public VmiAgreementDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody VmiAgreementInput input) {
    return call(auth, "inventory.update", a -> vmi.createAgreement(input, a.userId()));
  }

  /** 协议列表（真分页；行数与合计由明细聚合得出，不另存列）。 */
  @GetMapping("/inventory/vmi/agreements")
  public VmiAgreementPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String replenishmentMode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "inventory.read",
        a -> vmi.agreements(status, replenishmentMode, keyword, page, size));
  }

  /** 协议详情 = 头部 + 全部明细（水位与分账）+ 审计时间线。 */
  @GetMapping("/inventory/vmi/agreements/{agreementId}")
  public VmiAgreementDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> vmi.agreement(agreementId));
  }

  /** 给草稿协议补一个 SKU（水位 + 倍数 + 结算价）。已在跑的协议不能中途改价。 */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/lines")
  public VmiAgreementDetail addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody VmiAddLineInput input) {
    return call(auth, "inventory.update", a -> vmi.addLine(agreementId, input, a.userId()));
  }

  /** 启用协议：DRAFT → ACTIVE。水位巡检、售出、对账都要求协议已启用。 */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/activate")
  public VmiAgreement activate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.activate(agreementId, input, a.userId()));
  }

  /** 暂停协议：ACTIVE → SUSPENDED。暂停后不得再记售出。 */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/suspend")
  public VmiAgreement suspend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.suspend(agreementId, input, a.userId()));
  }

  /** 关闭协议：→ CLOSED。关闭后全部明细行被数据库冻结，数量不能再动。 */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/close")
  public VmiAgreement close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.close(agreementId, input, a.userId()));
  }

  // ------------------------------------------------------------ 水位监控

  /**
   * VMI 水位监控：逐行水位（安全水位 / 缓冲线 / 建议补货量 / 覆盖率）+
   * **可售与自有分开的两张卡片** + 缺来源行数。
   *
   * <p>{@code ownershipSplit.ownedAssetValue} 恒为 {@code null}（数据库里就没有这个口径），
   * {@code selfOwnedSplit} 是我方自有货的对照量，两者并排展示、页面不做相加。
   */
  @GetMapping("/inventory/vmi/agreements/{agreementId}/water-levels")
  public VmiWaterLevelMonitor waterLevels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> vmi.waterLevelMonitor(agreementId));
  }

  // ------------------------------------------------------------ 补货信号

  /**
   * 按协议批量出补货信号：逐行按安全水位与补货倍数判定，出多少张、每行为什么不出
   * 都在返回值里说清楚（跳过原因不报出来的话，「我出了 0 张」会被读成「系统没发现问题」）。
   */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/replenishment-signals")
  public VmiSignalBatchResult generateSignals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody(required = false) VmiSignalInput input) {
    return call(auth, "inventory.update", a -> vmi.generateSignals(agreementId, input, a.userId()));
  }

  /** 补货信号列表（真分页，可跨协议按状态与去向筛）。 */
  @GetMapping("/inventory/vmi/replenishment-signals")
  public VmiSignalPage signals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID agreementId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String signalTarget,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> vmi.signals(agreementId, status, signalTarget, page, size));
  }

  /** 受理信号：OPEN → ACKNOWLEDGED。 */
  @PostMapping("/inventory/vmi/replenishment-signals/{signalId}/acknowledge")
  public VmiSignal acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID signalId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.acknowledgeSignal(signalId, input, a.userId()));
  }

  /** 下发信号（推给供应商 / 交给 6 采购）：ACKNOWLEDGED → DISPATCHED，只写交接定位键。 */
  @PostMapping("/inventory/vmi/replenishment-signals/{signalId}/dispatch")
  public VmiSignal dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID signalId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.dispatchSignal(signalId, input, a.userId()));
  }

  /** 登记到货：DISPATCHED → FULFILLED，并把到货数累加进协议行的累计供货。 */
  @PostMapping("/inventory/vmi/replenishment-signals/{signalId}/fulfil")
  public VmiSignal fulfil(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID signalId,
      @RequestBody VmiSignalFulfilInput input) {
    return call(auth, "inventory.update", a -> vmi.fulfilSignal(signalId, input, a.userId()));
  }

  /** 作废信号：→ CANCELLED。终态不可翻案。 */
  @PostMapping("/inventory/vmi/replenishment-signals/{signalId}/cancel")
  public VmiSignal cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID signalId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.cancelSignal(signalId, input, a.userId()));
  }

  // ------------------------------------------------------------ 在库观测

  /**
   * 登记在库观测 —— VMI 货物理上在供应商自己仓、我方账本没有桶时，**唯一**的落点。
   *
   * <p>{@code WAREHOUSE_BUCKET} 是系统算的口径，不接受手工登记（会被 400 拒绝）：
   * 手工往里写数会让「系统算的」与「人报的」混在一起，来源标注就失去意义。
   */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/snapshots")
  public VmiSnapshot recordSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody VmiSnapshotInput input) {
    return call(auth, "inventory.update", a -> vmi.recordSnapshot(agreementId, input, a.userId()));
  }

  /** 在库观测列表（真分页；每条都带来源与观测时刻）。 */
  @GetMapping("/inventory/vmi/agreements/{agreementId}/snapshots")
  public VmiSnapshotPage snapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestParam(required = false) UUID lineId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> vmi.snapshots(agreementId, lineId, page, size));
  }

  // ------------------------------------------------------------ 售出事实

  /**
   * 记一条售出事实 —— 这是「售出后才与供应商对账结算」的唯一来源。
   *
   * <p>协议必须已启用；本次售出不得超过累计供货；{@code (协议行, saleRef)} 唯一，
   * 所以重复推送不会卖两次、也就不会对账两次。
   */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/lines/{lineId}/sales")
  public VmiSale recordSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @PathVariable UUID lineId,
      @RequestBody VmiSaleInput input) {
    return call(auth, "inventory.update", a -> vmi.recordSale(agreementId, lineId, input, a.userId()));
  }

  /** 售出事实列表（真分页；{@code unsettleableOnly=true} 只看还没进对账单的）。 */
  @GetMapping("/inventory/vmi/agreements/{agreementId}/sales")
  public VmiSalePage sales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestParam(required = false) Boolean unsettleableOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> vmi.sales(agreementId, unsettleableOnly, page, size));
  }

  // ------------------------------------------------------------ 结算对账单（→ 12 结算）

  /** 按协议的结算周期算出当前默认对账区间（WEEKLY / SEMI_MONTHLY / MONTHLY 是真算的）。 */
  @GetMapping("/inventory/vmi/agreements/{agreementId}/settlement-window")
  public VmiSettlementWindow settlementWindow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId) {
    return call(auth, "inventory.read", a -> vmi.settlementWindow(agreementId));
  }

  /**
   * 出 VMI 结算对账单：把区间内**已售但还没进任何对账单**的售出事实收成一张交给 12。
   *
   * <p>区间内没有未对账售出事实时显式拒绝（409）—— 没有售出就没有应付，
   * 不该靠一张空单来「显得处理过了」。
   */
  @PostMapping("/inventory/vmi/agreements/{agreementId}/settlements")
  public VmiSettlementDetail createSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestBody VmiSettlementInput input) {
    return call(auth, "inventory.update", a -> vmi.createSettlement(agreementId, input, a.userId()));
  }

  /** 对账单列表（真分页；表头应付由数据库触发器保证 == round(Σ明细金额, 2)）。 */
  @GetMapping("/inventory/vmi/settlements")
  public VmiSettlementPage settlements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID agreementId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> vmi.settlements(agreementId, status, page, size));
  }

  /** 对账单详情：每行都指回触发它的那条售出事实，「应付从哪来」一眼可追。 */
  @GetMapping("/inventory/vmi/settlements/{settlementId}")
  public VmiSettlementDetail settlementDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID settlementId) {
    return call(auth, "inventory.read", a -> vmi.settlementDetail(settlementId));
  }

  /** 确认对账单 → 应付正式交 12 结算（写 reference_id 定位键）。确认后单据被数据库冻结。 */
  @PostMapping("/inventory/vmi/settlements/{settlementId}/confirm")
  public VmiSettlement confirmSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID settlementId,
      @RequestBody(required = false) VmiNoteInput input) {
    return call(auth, "inventory.update", a -> vmi.confirmSettlement(settlementId, input, a.userId()));
  }

  // ------------------------------------------------------------ 审计

  /** 审计时间线：谁启用/暂停/关闭了协议、谁记了售出、谁出了信号与对账单（append-only）。 */
  @GetMapping("/inventory/vmi/agreements/{agreementId}/audit")
  public List<VmiAuditEntry> audit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID agreementId,
      @RequestParam(required = false) Integer limit) {
    return call(auth, "inventory.read", a -> vmi.auditTrail(agreementId, limit));
  }
}