package com.starcon.finance;

import static com.starcon.finance.PayableModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * 12.1.2.1 应付单管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/payables}</b>：与 12.1.1.1 的 {@code /finance/receivables}
 * 以及卷 12 的其余模块（12.2 收付款 / 12.3 结算 / 12.5 总账 …）各自独立前缀 ——
 * 「哪个模块的哪个端点」必须说得清。
 *
 * <p><b>预付款是另一个前缀 {@code /finance/prepayments}</b>：它**不是**应付单的子资源 ——
 * 蓝图加粗句「应付、预付款与采购承诺分开建模」在 URL 上也要分开，
 * 否则前端会把两个不同的东西当同一个东西的后代资源。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.payable.read / .create / .update} 与
 * {@code finance.prepayment.read / .create / .update}：
 * 确认应付、登记预付、改确认政策都不是读权限能带过去的动作。
 */
@RestController
public class PayableController {

  private final AccessService access;
  private final PayableService payables;

  public PayableController(AccessService access, PayableService payables) {
    this.access = access;
    this.payables = payables;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 应付单

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/finance/payables/capabilities")
  public FinancePayableCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "read", actor -> payables.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/finance/payables/options")
  public FinancePayableOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "read", actor -> payables.options());
  }

  /** 应付单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping("/finance/payables")
  public FinancePayablePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.payable",
        "read",
        actor ->
            payables.page(
                new PayableQuery(
                    enumOf(status, PayableStatus.class, "应付状态"),
                    enumOf(source, PayableSource.class, "应付来源"),
                    currency,
                    counterpartyRef,
                    overdueOnly,
                    dueFrom,
                    dueTo,
                    keyword,
                    page,
                    size)));
  }

  /**
   * 未付清单（12.1.2.1 的输出之一：「应付单 + 明细 + 未付清单」）。
   *
   * <p>⚠️ **按币种分开**：USD 与 EUR 加起来不是任何一个金额。
   */
  @GetMapping("/finance/payables/unpaid")
  public FinancePayableUnpaidList unpaid(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "read", actor -> payables.unpaid());
  }

  /** 应付确认政策：每个（来源 × 义务类型）全列（含未配置的）。 */
  @GetMapping("/finance/payables/policies")
  public java.util.List<FinancePayablePolicyView> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "read", actor -> payables.policies());
  }

  /**
   * 配置一个（来源 × 义务类型）的确认政策（幂等 upsert）。
   *
   * <p>这是「什么业务确认为应付由租户决定」的操作入口：
   * 一个来源没有启用的政策 ⇒ 这个来源一张应付都产生不了。
   */
  @PutMapping("/finance/payables/policies/{source}")
  public FinancePayablePolicyRow updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String source,
      @RequestParam(required = false) String obligationKind,
      @RequestBody FinancePayablePolicyInput input) {
    var parsedSource = enumOf(source, PayableSource.class, "应付来源");
    var parsedKind =
        obligationKind == null || obligationKind.isBlank()
            ? ObligationKind.PAYABLE
            : enumOf(obligationKind, ObligationKind.class, "义务类型");
    return call(
        auth,
        "finance.payable",
        "update",
        actor -> payables.updatePolicy(parsedSource, parsedKind, input, actor));
  }

  /** 应付单详情：单头 + 明细行 + 事件流。 */
  @GetMapping("/finance/payables/{id}")
  public FinancePayableDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.payable", "read", actor -> payables.detail(id));
  }

  /**
   * 人工录入一张应付单（建单 + 确认在同一个事务里完成）。
   *
   * <p>⚠️ 只有 MANUAL / OTHER 能手工建单；采购收货 / 物流费用 / 广告费三个来源
   * 只能由上游事实产生。
   */
  @PostMapping("/finance/payables")
  public FinancePayableRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePayableInput input) {
    return call(auth, "finance.payable", "create", actor -> payables.create(input, actor));
  }

  /**
   * 从上游 **6.3.2.2 到货跟踪的应付部分确认**导入应付（幂等），并把本服务的应付单号写回上游。
   *
   * <p>⚠️ 上游读不到时**整体失败**（503），绝不返回「导入 0 条」冒充「上游没有数据」。
   */
  @PostMapping("/finance/payables/import-purchase-arrivals")
  public FinancePayableImportResult importFromPurchaseArrivals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "finance.payable", "create", actor -> payables.importFromPurchaseArrivals(auth, actor));
  }

  /** 从上游 **10.4.1.1 头程发运单**导入物流费用应付（幂等）。 */
  @PostMapping("/finance/payables/import-first-mile")
  public FinancePayableImportResult importFromFirstMile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "create", actor -> payables.importFromFirstMile(auth, actor));
  }

  /** 从上游 **14.3.1.1 活动报表**导入广告费应付（幂等）。 */
  @PostMapping("/finance/payables/import-ad-spend")
  public FinancePayableImportResult importFromAdSpend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.payable", "create", actor -> payables.importFromAdSpend(auth, actor));
  }

  /** 作废（红冲的等价物）：终态，整单冻结。已付的不可作废。 */
  @PostMapping("/finance/payables/{id}/void")
  public FinancePayableRow voidPayable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinancePayableVoidInput input) {
    return call(auth, "finance.payable", "update", actor -> payables.voidPayable(id, input, actor));
  }

  // ================================================================ 预付款（独立聚合）

  /** 预付款列表：真分页 + 真筛选。 */
  @GetMapping("/finance/prepayments")
  public FinancePrepaymentPage prepayments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.prepayment",
        "read",
        actor ->
            payables.prepayments(
                enumOf(status, PrepaymentStatus.class, "预付款状态"),
                currency,
                counterpartyRef,
                keyword,
                page,
                size));
  }

  /** 预付款详情：单头 + 抵扣明细。 */
  @GetMapping("/finance/prepayments/{id}")
  public FinancePrepaymentDetail prepaymentDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.prepayment", "read", actor -> payables.prepaymentDetail(id));
  }

  /**
   * 登记一笔预付款。
   *
   * <p>⚠️ 它**不产生任何「已付款」回执**：登记的是「这笔钱预付给谁、约定什么时候付」，
   * 实际付款走 12.2.2.1（未交付）。
   */
  @PostMapping("/finance/prepayments")
  public FinancePrepaymentRow createPrepayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePrepaymentInput input) {
    return call(auth, "finance.prepayment", "create", actor -> payables.createPrepayment(input, actor));
  }

  /** 登记一笔预付款抵扣（**不改**应付单的已付金额 —— 那是 12.2.2.1 的写入口）。 */
  @PostMapping("/finance/prepayments/{id}/allocations")
  public FinancePrepaymentDetail allocatePrepayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinancePrepaymentAllocationInput input) {
    return call(
        auth, "finance.prepayment", "update", actor -> payables.allocatePrepayment(id, input, actor));
  }

  /** 作废预付款。已有抵扣的不可作废（撤销抵扣属 12.2.2.1 域，未交付）。 */
  @PostMapping("/finance/prepayments/{id}/void")
  public FinancePrepaymentRow voidPrepayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinancePrepaymentVoidInput input) {
    return call(auth, "finance.prepayment", "update", actor -> payables.voidPrepayment(id, input, actor));
  }

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
