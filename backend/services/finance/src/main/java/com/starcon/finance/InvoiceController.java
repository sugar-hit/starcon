package com.starcon.finance;

import static com.starcon.finance.InvoiceModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * 12.6.2.1 发票管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/invoices}</b>：与同服务的 12.6.1.1
 * （{@code /finance/tax-rates}）、12.5.2.1（{@code /finance/vouchers}）刻意不共用前缀 ——
 * 首 token 也刻意不同，免得「发票管理」被别的模块的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.invoice.read / .create / .update}：开具与作废不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/invoices")
public class InvoiceController {

  private final AccessService access;
  private final InvoiceService invoices;

  public InvoiceController(AccessService access, InvoiceService invoices) {
    this.access = access;
    this.invoices = invoices;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.invoice." + permission);
    return access.run(actor, "finance.invoice." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（channel / trade / catalog）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.invoice." + permission);
    return access.run(
        actor, "finance.invoice." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceInvoiceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> invoices.capabilities(bearer));
  }

  // ================================================================ 选项

  /** 筛选项下拉（词表来自本模块自己；币种 / 模板来自真实数据）。 */
  @GetMapping("/options")
  public Map<String, Object> options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> invoices.options());
  }

  // ================================================================ 模板

  /** 发票模板列表（真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public FinanceInvoiceTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String documentType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            invoices.templates(
                countryCode, platformCode, documentType, status, keyword, page, size));
  }

  /** 登记发票模板（🔴 辖区码实时校验，美国辖区录不进去）。 */
  @PostMapping("/templates")
  public FinanceInvoiceTemplateRow upsertTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceInvoiceTemplateInput input) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> invoices.upsertTemplate(input, actor, bearer));
  }

  // ================================================================ 发票

  /** 发票列表（真分页 + 真筛选）。 */
  @GetMapping
  public FinanceInvoicePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String documentType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String templateCode,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) UUID sourceReceivableId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            invoices.invoices(
                status,
                documentType,
                currency,
                templateCode,
                jurisdictionCode,
                platformCode,
                sourceReceivableId,
                keyword,
                page,
                size));
  }

  /** 建一张发票草稿（🔴 税率实时校验，调用方填不出假的税额）。 */
  @PostMapping
  public FinanceInvoiceRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceInvoiceCreateInput input) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> invoices.create(input, actor, bearer));
  }

  /** 发票详情（头 + 明细）。 */
  @GetMapping("/{id}")
  public FinanceInvoiceDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> invoices.invoiceDetail(id));
  }

  /** 开具：分配正式编号（草稿 → 已开具）。 */
  @PostMapping("/{id}/issue")
  public FinanceInvoiceRow issue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> invoices.issue(id, actor));
  }

  /** 作废（🔴 原因必填：作废必须留痕「谁、何时、为什么」）。 */
  @PostMapping("/{id}/void")
  public FinanceInvoiceRow voidInvoice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceInvoiceVoidInput input) {
    return call(auth, "update", actor -> invoices.voidInvoice(id, input, actor));
  }

  /** 红冲：按原发票逐行带出生成成对的贷项通知单（草稿）。 */
  @PostMapping("/{id}/reverse")
  public FinanceInvoiceRow reverse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceInvoiceReverseInput input) {
    return call(auth, "update", actor -> invoices.reverse(id, input, actor));
  }

  /** 状态记录（append-only 留痕，新到旧）。 */
  @GetMapping("/{id}/events")
  public List<FinanceInvoiceEventRow> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> invoices.events(id));
  }

  // ================================================================ 上游待开票需求

  /** 待开票需求清单（上游 9 订单，**只读**；读不到一律 503）。 */
  @GetMapping("/requests")
  public FinanceInvoiceRequestPage requests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return callWithBearer(
        auth, "read", (actor, bearer) -> invoices.requests(page, size, bearer));
  }

  /** 批量建单：从待开票需求批量生成草稿（失败逐条说清原因）。 */
  @PostMapping("/batch")
  public FinanceInvoiceBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceInvoiceBatchInput input) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> invoices.batchCreate(input, actor, bearer));
  }

  // ================================================================ 汇总

  /** 发票汇总（🔴 **按币种分组**，没有跨币种的合计数字）。 */
  @GetMapping("/summary")
  public FinanceInvoiceSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baseCurrency) {
    return callWithBearer(
        auth, "read", (actor, bearer) -> invoices.summary(baseCurrency, bearer));
  }

  // ================================================================ 平台开票对接

  /** 平台开票报文（确定性；金额在报文里是字符串）。 */
  @GetMapping("/{id}/platform-payload")
  public FinanceInvoicePlatformPayload platformPayload(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> invoices.platformPayload(id));
  }

  /** 批量递交平台开票（成功与失败**逐条**留痕）。 */
  @PostMapping("/platform-submissions")
  public FinanceInvoicePlatformSubmitResult submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceInvoicePlatformSubmitInput input) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> invoices.submitToPlatform(input, actor, bearer));
  }

  /** 平台提交记录清单。 */
  @GetMapping("/platform-submissions")
  public FinanceInvoicePlatformSubmissionPage submissions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> invoices.submissions(page, size));
  }

  /** 登记平台回执（受理 ≠ 回执，两件事都留痕）。 */
  @PostMapping("/platform-submissions/{id}/acknowledge")
  public FinanceInvoicePlatformSubmissionRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceInvoicePlatformAcknowledgeInput input) {
    return call(auth, "update", actor -> invoices.acknowledge(id, input, actor));
  }

  /** 发票日（供前端表单用；日历日，不是某一瞬间）。 */
  static LocalDate today() {
    return LocalDate.now(java.time.ZoneOffset.UTC);
  }
}
