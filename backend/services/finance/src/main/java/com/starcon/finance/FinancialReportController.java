package com.starcon.finance;

import static com.starcon.finance.FinancialReportModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.2.2 财务报表的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/financial-reports}</b>：与 12.5.2.1 的 {@code /finance/vouchers}、
 * 12.4.2.1 的 {@code /finance/cost-collections} 各自独立前缀 —— 「哪个模块的哪个端点」必须说得清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 <b>没有 DELETE 端点</b>：报表模板是**口径配置**，删掉会让「当时的报表口径」无法复现 ——
 * 改口径用「停用」（enabled=false）。与 finance 其它模块的约定一致。
 */
@RestController
@RequestMapping("/finance/financial-reports")
public class FinancialReportController {

  private final AccessService access;
  private final FinancialReportService reports;

  public FinancialReportController(AccessService access, FinancialReportService reports) {
    this.access = access;
    this.reports = reports;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与筛选项

  /** 能力声明：哪些真接通、哪些还要租户自己配置（现金流量表的现金科目）、缺口归谁。 */
  @GetMapping("/capabilities")
  public FinanceReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.report", "read", actor -> reports.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceReportOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.report", "read", actor -> reports.options());
  }

  // ================================================================ 报表模板

  /** 报表模板列表（系统不预置任何一行，行由租户维护）。 */
  @GetMapping("/templates")
  public List<FinanceReportTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String reportType) {
    return call(auth, "finance.report", "read", actor -> reports.templateRows(reportType));
  }

  /** 登记一条报表模板。 */
  @PostMapping("/templates")
  public FinanceReportTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceReportTemplateInput input) {
    return call(
        auth, "finance.report", "create", actor -> reports.createTemplate(input, actor));
  }

  /** 修改一条报表模板（乐观并发）。 */
  @PutMapping("/templates/{id}")
  public FinanceReportTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceReportTemplateInput input) {
    return call(
        auth, "finance.report", "update", actor -> reports.updateTemplate(id, input, actor));
  }

  /**
   * 停用一条报表模板。
   *
   * <p>⚠️ **不提供删除**：报表模板是口径配置，停用才是改口径的方式（历史报表口径要能复现）。
   */
  @PostMapping("/templates/{id}/disable")
  public FinanceReportTemplateRow disableTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.report", "update", actor -> reports.disableTemplate(id, actor));
  }

  // ================================================================ 五张报表

  /**
   * 资产负债表。
   *
   * <p>🔴 期末余额 = 期初余额 + 本期发生额；行按科目类别归入资产 / 负债 / 所有者权益 / 损益结转。
   */
  @GetMapping("/balance-sheet")
  public FinanceReportResult balanceSheet(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.report(
                FinancialReportType.BALANCE_SHEET,
                periodFromId,
                periodToId,
                comparePeriodFromId,
                comparePeriodToId,
                currency,
                null));
  }

  /** 利润表（本期发生额口径，与对比期逐行并排）。 */
  @GetMapping("/income-statement")
  public FinanceReportResult incomeStatement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.report(
                FinancialReportType.INCOME_STATEMENT,
                periodFromId,
                periodToId,
                comparePeriodFromId,
                comparePeriodToId,
                currency,
                null));
  }

  /**
   * 现金流量表。
   *
   * <p>🔴 **需先在『报表模板』里配置现金科目**：未配置时返回空并说明原因，不虚构数据。
   */
  @GetMapping("/cash-flow")
  public FinanceReportResult cashFlow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.report(
                FinancialReportType.CASH_FLOW,
                periodFromId,
                periodToId,
                comparePeriodFromId,
                comparePeriodToId,
                currency,
                null));
  }

  /** 科目余额表（期初 / 本期 / 期末，按科目余额方向取正负）。 */
  @GetMapping("/trial-balance")
  public FinanceReportResult trialBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.report(
                FinancialReportType.TRIAL_BALANCE,
                periodFromId,
                periodToId,
                comparePeriodFromId,
                comparePeriodToId,
                currency,
                null));
  }

  /**
   * 明细账（**逐笔**展开一个科目）。
   *
   * <p>🔴 必须指定 {@code subjectCode}：明细账是「一个科目的逐笔流水」，不是全部科目的流水。
   */
  @GetMapping("/general-ledger")
  public FinanceReportResult generalLedger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String subjectCode) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.report(
                FinancialReportType.GENERAL_LEDGER,
                periodFromId,
                periodToId,
                comparePeriodFromId,
                comparePeriodToId,
                currency,
                subjectCode));
  }

  // ================================================================ 钻取与导出

  /**
   * 钻取到凭证：给出构成某个报表行的**真实凭证 + 分录行**。
   *
   * <p>🔴 这条路径就是报表行上的 {@code drillPath} —— 界面上的「钻取到凭证」按钮走的就是它。
   */
  @GetMapping("/drill")
  public FinanceReportDrill drill(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String reportType,
      @RequestParam String lineCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String currency,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId) {
    return call(
        auth,
        "finance.report",
        "read",
        actor ->
            reports.drill(
                parseReportType(reportType),
                lineCode,
                subjectCode,
                currency,
                periodFromId,
                periodToId));
  }

  /**
   * 导出一张报表（CSV / JSON 真内容，并写一条留痕）。
   *
   * <p>🔴 CSV 带 UTF-8 BOM（Excel 打开中文不乱码）。
   */
  @GetMapping("/export")
  public ResponseEntity<String> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String reportType,
      @RequestParam(defaultValue = "CSV") String format,
      @RequestParam UUID periodFromId,
      @RequestParam UUID periodToId,
      @RequestParam(required = false) UUID comparePeriodFromId,
      @RequestParam(required = false) UUID comparePeriodToId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String subjectCode) {
    var actor = access.require(auth, "finance.report.read");
    var file =
        access.run(
            actor,
            "finance.report.read",
            () ->
                reports.export(
                    parseReportType(reportType),
                    format,
                    periodFromId,
                    periodToId,
                    comparePeriodFromId,
                    comparePeriodToId,
                    currency,
                    subjectCode,
                    actor));
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
        .header("Content-Type", file.contentType())
        .body(file.content());
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static FinancialReportType parseReportType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表类型不能为空");
    }
    try {
      return FinancialReportType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表类型取值不合法：" + raw);
    }
  }
}
