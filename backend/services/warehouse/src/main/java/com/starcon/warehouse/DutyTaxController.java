package com.starcon.warehouse;

import static com.starcon.warehouse.DutyTaxModels.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * 10.5.1.3 关税与税费。
 *
 * <p>命名空间 {@code /warehouse/duty-taxes}：与 10.5.1.1 报关单 （{@code
 * /warehouse/customs-declarations}）、10.4.1.1 头程发运单 （{@code /warehouse/first-mile-shipments}）同一
 * {@code /warehouse} 前缀，浏览器只经 /api/warehouse 代理访问。放在 {@code /inventory/} 下会让「8.2 现场作业」与「10 物流税费」
 * 两套语义混在一个前缀里，而本模块的输入就是报关单与头程发运单。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}， 全部写动作（配税率 / 建估算 / 改基础 / 重算 / 锁定
 * / 作废 / 录实缴）挂 {@code warehouse.inventory.update} —— 锁定那一刻估算就成为对外报价依据，权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点是 **POST**：录实缴、锁定、作废、重算都是「发生一次业务事件」， 用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，而实际发生的是一笔钱付出去。
 */
@RestController
@RequestMapping("/warehouse/duty-taxes")
public class DutyTaxController {

  private final AccessService access;
  private final DutyTaxService dutyTax;

  public DutyTaxController(AccessService access, DutyTaxService dutyTax) {
    this.access = access;
    this.dutyTax = dutyTax;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 关税与税费能力：税种、承担方、贸易条款、状态机、归因词表、**未交付依赖逐条声明** 与四条必须讲清的口径。
   *
   * <p>前端据此渲染各下拉与提示 —— 3.7.1.1 HS 编码字典、16.3.1.1 税则、16.3.2.1 VAT/IOSS
   * 与外部海关缴款接口**显式报不可用并给出归属模块**，而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public DutyTaxCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> dutyTax.capabilities());
  }

  // ================================================================ 税率表

  /** 税则税率表分页。税率是真实落库数据，按目的国 / 税种 / 生效日检索。 */
  @GetMapping("/rates")
  public List<DutyRateRow> rates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String taxKind,
      @RequestParam(required = false) String rateScope,
      @RequestParam(required = false) String hsCodePrefix,
      @RequestParam(required = false) Boolean preferentialOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate effectiveOn,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            dutyTax.rates(
                a.tenantId(),
                new DutyRateQuery(
                    destinationCountryCode,
                    taxKind,
                    rateScope,
                    hsCodePrefix,
                    preferentialOnly,
                    effectiveOn,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /** 录入税率。出处必填：没有出处的税率无法复核，而税率算错是要按海关核定补税的。 */
  @PostMapping("/rates")
  public DutyRateSaved createRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody DutyRateCreateRequest request) {
    return call(auth, "inventory.update", a -> dutyTax.createRate(a.tenantId(), request));
  }

  /**
   * 改税率（带行级乐观锁）。
   *
   * <p>已算出的估算单明细行里抄着自己当时用的税率值，**不受本次改动影响** —— 那正是「当时按 3.25% 算的」能被回答的原因。
   */
  @PutMapping("/rates/{rateId}")
  public DutyRateSaved updateRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID rateId,
      @RequestBody DutyRateUpdateRequest request) {
    return call(auth, "inventory.update", a -> dutyTax.updateRate(a.tenantId(), rateId, request));
  }

  // ================================================================ 估算单

  /** 估算单列表 + 全量汇总（**合计不受分页影响**）。 */
  @GetMapping
  public DutyEstimatePage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String dutyBearer,
      @RequestParam(required = false) String incoterm,
      @RequestParam(required = false) UUID declarationId,
      @RequestParam(required = false) Boolean unmatchedRateOnly,
      @RequestParam(required = false) Boolean varianceOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            dutyTax.page(
                a.tenantId(),
                new DutyEstimateQuery(
                    status,
                    destinationCountryCode,
                    dutyBearer,
                    incoterm,
                    declarationId,
                    unmatchedRateOnly,
                    varianceOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 估算单详情：头 + 逐行明细（含命中的税率快照）+ 实缴 + 差异 + 状态机下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{estimateId}")
  public DutyEstimateDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId) {
    return call(auth, "inventory.read", a -> dutyTax.detail(a.tenantId(), estimateId));
  }

  /** 逐行明细 —— 蓝图那句「差异大说明 HS 归类或税率配置有误」要落到具体行，就是看它。 */
  @GetMapping("/{estimateId}/lines")
  public List<DutyEstimateLineRow> lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          // 详情先行：单不存在时 404，而不是返回一个空数组冒充「没有明细」。
          dutyTax.detail(a.tenantId(), estimateId);
          return dutyTax.lines(a.tenantId(), estimateId);
        });
  }

  /** 实际缴纳记录（append-only，多缴/错缴走新增退汇与调整行）。 */
  @GetMapping("/{estimateId}/payments")
  public List<DutyPaymentRow> payments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          dutyTax.detail(a.tenantId(), estimateId);
          return dutyTax.payments(a.tenantId(), estimateId);
        });
  }

  /** 估算与实缴的差异快照 —— 蓝图加粗要求「差异要记录」，这里是它的读出口。 */
  @GetMapping("/{estimateId}/variances")
  public List<DutyVarianceRow> variances(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          dutyTax.detail(a.tenantId(), estimateId);
          return dutyTax.variances(a.tenantId(), estimateId);
        });
  }

  /**
   * 建估算单：取报关单申报价值快照 → 逐行匹配税率 → 算关税与增值税/GST → 算到岸成本。
   *
   * <p>没匹配到税率的行会落在 {@code NO_MATCH} 并在返回消息里点名， 且**锁不了这张估算单** —— 按 0 税锁定的估算单会让报价与成本核算少算税。
   */
  @PostMapping
  public DutySaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody DutyEstimateCreateRequest request) {
    return call(auth, "inventory.update", a -> dutyTax.create(a.tenantId(), request));
  }

  /** 改基础（完税价格 / 运费 / 保险 / 承担方 / 汇率）并按当前税率重算。已锁定后不可改。 */
  @PutMapping("/{estimateId}")
  public DutySaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId,
      @RequestBody DutyEstimateUpdateRequest request) {
    return call(auth, "inventory.update", a -> dutyTax.update(a.tenantId(), estimateId, request));
  }

  /**
   * 重算：按当前（或指定生效日的）税率表重新逐行算税。
   *
   * @param rateDate 按哪一天的税率算；不传就是今天。指定它是为了「当时按哪个税率算的」可复现。
   */
  @PostMapping("/{estimateId}/recalculate")
  public DutySaved recalculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate rateDate) {
    return call(
        auth, "inventory.update", a -> dutyTax.recalculate(a.tenantId(), estimateId, rateDate));
  }

  /**
   * 锁定：把估算固定为对外报价/成本依据。
   *
   * <p>带未匹配关税税率或未配目的国增值税税率的行会被拒绝 —— 这是本模块最重要的一道闸。
   */
  @PostMapping("/{estimateId}/lock")
  public DutySaved lock(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId,
      @RequestBody(required = false) DutyLockRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            dutyTax.lock(
                a.tenantId(), estimateId, request == null ? new DutyLockRequest("") : request));
  }

  /** 作废：终态。已有实缴的不许作废 —— 钱付出去了，作废估算会让「缴了多少税」在账上消失。 */
  @PostMapping("/{estimateId}/void")
  public DutySaved voidEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId,
      @RequestBody DutyVoidRequest request) {
    return call(
        auth, "inventory.update", a -> dutyTax.voidEstimate(a.tenantId(), estimateId, request));
  }

  /**
   * 记录实际缴纳，并在**同一事务里**写下差异快照与归因。
   *
   * <p>蓝图原文：「**税费估算与实际缴纳差异要记录**」。这不是可选的补充动作 —— DB 触发器会在提交时核对「有实缴却没有差异记录」并拒收，所以先存实缴、差异以后再补做不到。
   */
  @PostMapping("/{estimateId}/payments")
  public DutySaved recordPayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID estimateId,
      @RequestBody DutyPaymentCreateRequest request) {
    return call(
        auth, "inventory.update", a -> dutyTax.recordPayment(a.tenantId(), estimateId, request));
  }

  /**
   * 差异分析 —— 蓝图那句「长期差异大说明 HS 归类或税率配置有误」的查询出口。
   *
   * <p>按归因、按 HS 码、以及未匹配税率的 HS 码（领先指标）三个维度给出口。
   */
  @GetMapping("/variance-analysis")
  public DutyVarianceAnalysis varianceAnalysis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "inventory.read",
        a -> dutyTax.varianceAnalysis(a.tenantId(), destinationCountryCode, from, to));
  }
}
