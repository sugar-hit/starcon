package com.starcon.finance;

import static com.starcon.finance.InvoiceModels.*;

import com.starcon.finance.TaxRateModels.FinanceTaxJurisdictionOption;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.6.2.1 发票管理。
 *
 * <p>蓝图 12-finance.md：「管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、
 * 编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。」输入：「订单与结算数据；
 * 发票模板；编号规则；作废申请。」输出：「发票与贷项单 + 编号序列 + 状态记录。」
 * 关系：「上游 = 9 订单、12.3.1；下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。
 * <b>各国发票要求差异大</b>，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。」
 *
 * <p><b>🔴 本模块只产出发票事实，不重复实现别的模块的职责</b>：
 *
 * <ul>
 *   <li><b>税率 / 税码 / 辖区</b>：一律经 12.6.1.1（{@code TaxRateService}）<b>实时校验</b>，
 *       本模块<b>不抄一份税率表</b>；命中不到一律报错，不按 0% 处理。</li>
 *   <li><b>凭证</b>：发票<b>不写凭证</b>。过账走 12.5.2.1 的真实接口。</li>
 *   <li><b>退货开票调整</b>：= <b>红冲</b>，金额从原发票<b>逐行带出</b>，不许手填。</li>
 *   <li><b>订单 / 结算</b>：<b>只读</b>。订单侧经 trade 的授权只读 API 读「开票需求」，
 *       结算侧同库只读；本模块<b>不写</b>订单表、<b>不写</b>结算表。</li>
 * </ul>
 *
 * <p><b>🔴 多币种绝不相加</b>：一张发票只有一个币种；汇总<b>按币种分组</b>，
 * 不做本位币折算；汇率不可得时如实报「汇率不可得」，<b>绝不静默按 1:1 相加</b>。
 *
 * <p><b>🔴 编号并发安全</b>：取号是<b>一条原子语句</b>
 * （{@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}），
 * 绝不「先查 max 再 +1」。
 *
 * <p><b>时间字段</b>：{@code issued_at} / {@code voided_at} / {@code occurred_at} 是
 * {@code timestamptz}，一律 OffsetDateTime + {@code ?::timestamptz}；
 * {@code issue_date} 是 {@code date}（发票日是日历日，不是某一瞬间）。
 */
@Service
public class InvoiceService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 发票号里的流水号位数。 */
  static final int SEQUENCE_DIGITS = 6;

  private static final String INVOICE_COLUMNS =
      "id,invoice_number,document_type,status,template_id,template_code,title,country_code,"
          + " jurisdiction_code,jurisdiction_name,platform_code,shop_id,shop_code,source_receivable_id,"
          + " source_settlement_id,source_order_no,customer_name,currency,issue_date,"
          + " subtotal_amount,tax_amount,total_amount,original_invoice_id,original_invoice_number,"
          + " reversal_invoice_id,reason,issued_by,issued_at,voided_by,voided_at,reversed_by,"
          + " reversed_at,platform_sync_status,platform_invoice_ref,created_by,created_at,updated_at";

  private final DSLContext sql;
  private final TaxRateService taxRates;
  private final InvoiceRequestPort invoiceRequests;
  private final PlatformInvoicePort platformInvoices;
  private final ExchangeRatePort exchangeRates;
  private final ObjectMapper json;

  public InvoiceService(
      DSLContext sql,
      TaxRateService taxRates,
      InvoiceRequestPort invoiceRequests,
      PlatformInvoicePort platformInvoices,
      ExchangeRatePort exchangeRates,
      ObjectMapper json) {
    this.sql = sql;
    this.taxRates = taxRates;
    this.invoiceRequests = invoiceRequests;
    this.platformInvoices = platformInvoices;
    this.exchangeRates = exchangeRates;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 「各国发票要求差异大，模板必须按国家配置；<b>本系统不承诺覆盖所有国家税务规则</b>」——
   * 这一句由 {@link FinanceInvoiceCountryCoverage} 逐个辖区落地：支持哪些、缺口归谁，
   * 不假装全支持。
   */
  public FinanceInvoiceCapabilities capabilities(String bearer) {
    var capabilities = new ArrayList<FinanceInvoiceCapability>();
    var gaps = new ArrayList<FinanceInvoiceGap>();

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "发票模板（按平台 / 国家配置，含编号规则）",
            true,
            "finance_invoice_template + DB 层生效区间守卫（V366）",
            "",
            "模板按（国家 × 平台 × 单据类型）配置，同一时刻只能有一张生效模板"
                + "（生效区间不许重叠 + 部分唯一索引）⇒ 「按模板生成」永远有唯一答案。"
                + "🔴 **模板零预置行** —— 系统只提供结构与受控辖区选项，"
                + "不替用户决定任何国家的发票长什么样。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "税码 / 税率 / 税额（落库前实时校验）",
            true,
            "12.6.1.1 GET /finance/tax-rates/resolve + /jurisdictions（同服务直连）",
            "",
            "每一行都在落库前经 12.6.1.1 **实时取**当时那一版税率，"
                + "并把**税率版本 id** 快照进行 ⇒ 发票自带当时的税率口径，"
                + "之后政策改税率不影响这张发票。"
                + "🔴 命中不到一律报错，**不按 0% 处理**。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "编号与编号序列（**并发安全**）",
            true,
            "finance_invoice_sequence + ON CONFLICT DO UPDATE 取号",
            "",
            "发票号按「租户 + 号段（国家 / 平台）+ 期间」生成。"
                + "取号是**一条原子语句**，两个并发请求不会拿到同一个号 —— "
                + "「先查 max 再 +1」在并发下会让两张发票同号，税务上直接是事故。"
                + "🔴 编号只在**开具**时分配（草稿不占号）。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "状态机：草稿 / 开具 / 作废 / 红冲（**作废与红冲留痕**）",
            true,
            "finance_invoice + DB 层转换规则守卫（V366）",
            "",
            "转换规则由 DB 触发器钉死（草稿→开具/作废，开具→作废/红冲，终态不可再转）；"
                + "作废与红冲**必须**留下谁、何时、为什么；"
                + "🔴 **红冲成对** —— 贷项通知单必须指向一张已开具的原发票，不允许孤立红冲。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "批量生成（从上游待开票需求）",
            true,
            "trade GET /trade/b2b-orders/receivables（InvoiceRequestPort 只读）",
            "",
            "上游 9 订单的**开票需求**（b2b_receivable.invoice_state='REQUESTED'）"
                + "经授权只读 API 读；本模块**只读**订单侧数据，**不回写** invoice_state"
                + "（那张表的写入所有权在 trade）。批量里失败的那几条**逐条说清原因**。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "16.3.1.1",
            "受控辖区码表（辖区码不是自由文本）",
            true,
            "12.6.1.1 的辖区选项（真值源：channel 16.3.1.1）",
            "16.3.1.1",
            "辖区码**实时**校验后才允许落库；🔴 **美国辖区（联邦与 50 州）录不进去** —— "
                + "州级销售税归 channel 16.3.2.3，本模块不重复建表。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "平台开票对接（报文生成 + 批量递交 + 回执登记）",
            true,
            "finance_invoice_platform_submission + PlatformInvoicePort（channel）",
            PlatformInvoicePort.ownerModule(),
            "报文按发票 + 模板快照**确定性生成**（金额在报文里是字符串，不走浮点）；"
                + "批量递交逐条留痕，**失败也留痕**并写上游原话。"
                + "⚠️ **实际递交**走 channel（平台适配器的归属服务）；"
                + "channel 尚未提供开票接口时提交**失败并如实留痕**，"
                + "缺口归 channel " + PlatformInvoicePort.ownerModule() + "。"));

    capabilities.add(
        new FinanceInvoiceCapability(
            "12.6.2.1",
            "状态记录（append-only 留痕）",
            true,
            "finance_invoice_event（运行角色 UPDATE / DELETE 已收回）",
            "",
            "每次状态变化写一条：谁、何时、为什么、从什么状态到什么状态。"
                + "留痕表能改就等于没有留痕。"));

    gaps.add(
        new FinanceInvoiceGap(
            "12.5.2.1",
            "凭证过账（发票 → 会计凭证）",
            "finance",
            true,
            "下游",
            "✅ **已交付**。发票与凭证是**两个模块**：本模块只负责发票事实，"
                + "**不重复实现凭证过账**；要过账走 12.5.2.1 的真实接口。"));

    gaps.add(
        new FinanceInvoiceGap(
            "11.4",
            "退货开票调整（红冲的触发）",
            "trade",
            true,
            "下游",
            "✅ **已交付**。退货开票调整 = **红冲**；"
                + "🔴 红冲金额**从原发票逐行带出**（DB 层镜像守卫钉死），不许手填。"));

    gaps.add(
        new FinanceInvoiceGap(
            "12.6.1.2",
            "VAT/IOSS 申报数据",
            "finance",
            false,
            "下游",
            "🔴 **未交付**（在途）。本模块只产出**发票事实**，"
                + "不产出任何申报底稿 / 汇总数字 / 申报文件。"));

    gaps.add(
        new FinanceInvoiceGap(
            "9",
            "订单数据（开票需求来源）",
            "trade",
            true,
            "上游",
            "✅ **只读**。订单侧的**开票需求**（invoice_state='REQUESTED'）"
                + "经授权只读 API 读；本模块**不回写** invoice_state"
                + "（那张表的写入所有权在 trade）。"));

    gaps.add(
        new FinanceInvoiceGap(
            "12.3.1",
            "结算数据（发票的另一个来源）",
            "finance",
            true,
            "上游",
            "✅ **只读**。结算单与发票同库，读取即可；本模块**不写**结算表。"));

    gaps.add(
        new FinanceInvoiceGap(
            PlatformInvoicePort.ownerModule(),
            "平台开票的实际递交接口（channel）",
            "channel",
            false,
            "上游",
            "🔴 **channel 尚未提供**平台开票接口（"
                + PlatformInvoicePort.submissionsPath()
                + "）。本模块的报文生成、批量递交编排与回执登记**都已实现**，"
                + "递交本身会**失败并如实留痕**（写上游原话），"
                + "**不假装提交成功**。缺口归 channel。"));

    return new FinanceInvoiceCapabilities(capabilities, gaps, countryCoverage(bearer));
  }

  /**
   * 各国覆盖情况：逐个辖区说清「模板配了没、税率取得到没」。
   *
   * <p>🔴 这一格是「不承诺覆盖所有国家税务规则」的落地：不是一句免责声明，
   * 而是**逐个辖区**给出事实 —— 模板与税率到位了没有。
   */
  private List<FinanceInvoiceCountryCoverage> countryCoverage(String bearer) {
    var jurisdictions = taxRates.jurisdictionOptions(bearer).items();
    var templates =
        sql.fetch(
            "select distinct country_code, jurisdiction_code from finance_invoice_template where tenant_id = ?",
            currentTenant());
    var templateKeys =
        templates.stream()
            .map(r -> r.get("country_code", String.class) + "|" + r.get("jurisdiction_code", String.class))
            .collect(java.util.stream.Collectors.toSet());
    var rates =
        sql.fetch(
            "select distinct jurisdiction_code from finance_tax_rate where tenant_id = ? and status = 'ACTIVE'",
            currentTenant());
    var rateKeys =
        rates.stream()
            .map(r -> r.get("jurisdiction_code", String.class))
            .collect(java.util.stream.Collectors.toSet());

    var items = new ArrayList<FinanceInvoiceCountryCoverage>();
    for (var j : jurisdictions) {
      var key = j.countryCode() + "|" + j.code();
      var hasTemplate = templateKeys.contains(key);
      var hasRate = rateKeys.contains(j.code());
      String note;
      if (!j.usableForTaxRate()) {
        note = "美国州级销售税归 channel 16.3.2.3，本模块不重复建表 —— 该辖区不可用于本模块。";
      } else if (!hasTemplate) {
        note = "该辖区**还没有配发票模板** —— 按平台/国家模板生成在这里落空，先去登记模板。";
      } else if (!hasRate) {
        note = "该辖区**还没有生效税率** —— 发票行取不到税率（不按 0% 处理），先去 12.6.1.1 登记。";
      } else {
        note = "模板与税率都已到位，可以按该辖区生成发票。";
      }
      items.add(
          new FinanceInvoiceCountryCoverage(
              j.countryCode(), j.code(), j.name(), hasTemplate, hasRate, note));
    }
    return items;
  }

  // ================================================================ 选项

  /** 筛选项下拉（词表来自本模块自己；币种 / 模板来自真实数据）。 */
  public Map<String, Object> options() {
    var documentTypes = new ArrayList<String>();
    for (var d : DocumentType.values()) documentTypes.add(d.name());
    var statuses = new ArrayList<String>();
    for (var s : InvoiceStatus.values()) statuses.add(s.name());
    var syncStatuses = new ArrayList<String>();
    for (var s : PlatformSyncStatus.values()) syncStatuses.add(s.name());

    var currencies =
        sql.fetch("select distinct currency from finance_invoice order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .filter(java.util.Objects::nonNull)
            .toList();
    var templates =
        sql.fetch(
                "select template_code, template_name from finance_invoice_template"
                    + " where tenant_id = ? and status = 'ACTIVE' order by template_code",
                currentTenant())
            .stream()
            .map(r -> Map.of("code", r.get("template_code", String.class), "name", r.get("template_name", String.class)))
            .toList();

    var result = new LinkedHashMap<String, Object>();
    result.put("documentTypes", documentTypes);
    result.put("statuses", statuses);
    result.put("platformSyncStatuses", syncStatuses);
    result.put("currencies", currencies);
    result.put("templates", templates);
    return result;
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  public FinanceInvoiceTemplatePage templates(
      String countryCode, String platformCode, String documentType, String status, String keyword,
      int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (countryCode != null && !countryCode.isBlank()) {
      where.append(" and country_code = ?");
      binds.add(countryCode.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and platform_code = ?");
      binds.add(platformCode.trim().toUpperCase(Locale.ROOT));
    }
    if (documentType != null && !documentType.isBlank()) {
      where.append(" and document_type = ?");
      binds.add(documentType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (template_code ilike ? or template_name ilike ? or note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_invoice_template" + where, binds.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,template_code,template_name,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
                + " document_type,tax_type,tax_code,currency,title,sequence_prefix,sequence_scope,"
                + " sequence_scope_value,sequence_period,effective_from,effective_to,status,note,"
                + " created_by,created_at,updated_at"
                + " from finance_invoice_template" + where
                + " order by country_code,platform_code,document_type,effective_from desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceInvoiceTemplateRow>();
    for (var row : rows) items.add(templateRow(row, ""));
    return new FinanceInvoiceTemplatePage(items, Math.max(page, 0), limit, total);
  }

  /**
   * 登记一张发票模板。
   *
   * <p>🔴 辖区码**实时**校验（12.6.1.1 的辖区选项）：辖区码不是自由文本，
   * 美国辖区录不进去。
   */
  public FinanceInvoiceTemplateRow upsertTemplate(
      FinanceInvoiceTemplateInput input, AccessService.Access actor, String bearer) {
    var code = require(input.templateCode(), "模板编码");
    var name = require(input.templateName(), "模板名称");
    var country = require(input.countryCode(), "目的国").toUpperCase(Locale.ROOT);
    if (country.length() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目的国必须是两位 ISO 国家码");
    }
    var docType = DocumentType.parse(input.documentType());
    if (docType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单据类型只能是 INVOICE / CREDIT_NOTE");
    }
    var taxType = require(input.taxType(), "默认税种").toUpperCase(Locale.ROOT);
    if (!Set.of("SALES_TAX", "VAT", "IOSS", "WITHHOLDING").contains(taxType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税种只能是 SALES_TAX / VAT / IOSS / WITHHOLDING");
    }
    var currency = require(input.currency(), "模板币种").toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位 ISO 4217 代码");
    }
    var prefix = require(input.sequencePrefix(), "编号前缀");
    var scope = require(input.sequenceScope(), "编号范围").toUpperCase(Locale.ROOT);
    if (!Set.of("TENANT", "COUNTRY", "PLATFORM").contains(scope)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "编号范围只能是 TENANT / COUNTRY / PLATFORM");
    }
    var period = input.sequencePeriod() == null || input.sequencePeriod().isBlank()
        ? "NONE"
        : input.sequencePeriod().trim().toUpperCase(Locale.ROOT);
    if (!Set.of("NONE", "MONTHLY", "YEARLY").contains(period)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "编号期间口径只能是 NONE / MONTHLY / YEARLY");
    }
    var platformCode =
        input.platformCode() == null ? "" : input.platformCode().trim().toUpperCase(Locale.ROOT);
    var scopeValue = scopeValue(scope, country, platformCode);

    // 🔴 辖区实时校验（美国辖区录不进去 —— 州级销售税归 channel 16.3.2.3）
    var jurisdiction = requireJurisdiction(bearer, require(input.jurisdictionCode(), "辖区"));
    if (input.effectiveFrom() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给出生效时间：没有生效时间的模板等于没有");
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into finance_invoice_template(id,tenant_id,template_code,template_name,country_code,"
              + " jurisdiction_code,jurisdiction_name,platform_code,document_type,tax_type,tax_code,currency,title,"
              + " sequence_prefix,sequence_scope,sequence_scope_value,sequence_period,effective_from,"
              + " effective_to,status,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          code,
          name,
          country,
          jurisdiction.code(),
          jurisdiction.name(),
          platformCode,
          docType.name(),
          taxType,
          input.taxCode() == null ? "" : input.taxCode().trim(),
          currency,
          input.title() == null ? "" : input.title().trim(),
          prefix,
          scope,
          scopeValue,
          period,
          input.effectiveFrom(),
          input.note() == null ? "" : input.note().trim(),
          actor == null ? null : actor.userId(),
          now,
          now);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(e, "模板编码或编号前缀与已有模板冲突");
    }
    return templateRow(templateRowOf(id), jurisdiction.name());
  }

  /** 编号范围对应的号段取值。 */
  private String scopeValue(String scope, String countryCode, String platformCode) {
    return switch (scope) {
      case "COUNTRY" -> countryCode;
      case "PLATFORM" -> platformCode;
      default -> "ALL";
    };
  }

  // ================================================================ 发票列表与详情

  /** 发票列表（真分页 + 真筛选）。 */
  public FinanceInvoicePage invoices(
      String status, String documentType, String currency, String templateCode,
      String jurisdictionCode, String platformCode, UUID sourceReceivableId, String keyword,
      int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (documentType != null && !documentType.isBlank()) {
      where.append(" and document_type = ?");
      binds.add(documentType.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (templateCode != null && !templateCode.isBlank()) {
      where.append(" and template_code = ?");
      binds.add(templateCode.trim());
    }
    if (jurisdictionCode != null && !jurisdictionCode.isBlank()) {
      where.append(" and jurisdiction_code = ?");
      binds.add(jurisdictionCode.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and platform_code = ?");
      binds.add(platformCode.trim().toUpperCase(Locale.ROOT));
    }
    if (sourceReceivableId != null) {
      where.append(" and source_receivable_id = ?");
      binds.add(sourceReceivableId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (invoice_number ilike ? or customer_name ilike ? or source_order_no ilike ?"
          + " or title ilike ? or template_code ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_invoice" + where, binds.toArray()).get(0, Long.class);
    var draftCount =
        sql.fetchOne(
                "select count(*) from finance_invoice where tenant_id = ? and status = 'DRAFT'",
                currentTenant())
            .get(0, Long.class);
    var issuedCount =
        sql.fetchOne(
                "select count(*) from finance_invoice where tenant_id = ? and status = 'ISSUED'",
                currentTenant())
            .get(0, Long.class);

    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select " + INVOICE_COLUMNS + " from finance_invoice" + where
                + " order by issue_date desc, created_at desc, id limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceInvoiceRow>();
    for (var row : rows) items.add(invoiceRow(row, ""));
    return new FinanceInvoicePage(items, Math.max(page, 0), limit, total, draftCount, issuedCount);
  }

  /** 按 id 取一张发票（**单头**对外模型）。 */
  public FinanceInvoiceRow invoiceOf(UUID id) {
    return invoice(id);
  }

  /** 发票详情（头 + 明细）。 */
  public FinanceInvoiceDetail invoiceDetail(UUID id) {
    var row =
        sql.fetchOne(
            "select " + INVOICE_COLUMNS + " from finance_invoice where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或不属于本租户");
    }
    return new FinanceInvoiceDetail(invoiceRow(row, ""), linesOf(id));
  }

  /** 一张发票的明细行。 */
  public List<FinanceInvoiceLineRow> linesOf(UUID invoiceId) {
    var rows =
        sql.fetch(
            "select id,invoice_id,line_no,sku_code,product_name,quantity,unit_price,line_amount,"
                + " tax_type,tax_code,tax_rate,tax_rate_id,tax_amount,original_line_id,"
                + " original_line_amount,note"
                + " from finance_invoice_line where tenant_id = ? and invoice_id = ? order by line_no",
            currentTenant(),
            invoiceId);
    var items = new ArrayList<FinanceInvoiceLineRow>();
    for (var row : rows) items.add(lineRow(row));
    return items;
  }

  // ================================================================ 建单

  /**
   * 建一张发票草稿。
   *
   * <p>🔴 **税率不是入参**：每一行的税率都在落库前经 12.6.1.1 实时取，
   * 调用方**填不出**一个假的税额。🔴 **税额不是入参**：行金额与税额是 DB 生成列。
   */
  public FinanceInvoiceRow create(
      FinanceInvoiceCreateInput input, AccessService.Access actor, String bearer) {
    if (input.templateId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定发票模板");
    }
    var template = templateRowOf(input.templateId());
    if (template == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票模板不存在或不属于本租户");
    }
    if (!"ACTIVE".equals(template.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该模板已停用，不能用于生成发票");
    }
    var issueDate = input.issueDate() == null ? LocalDate.now(ZoneOffset.UTC) : input.issueDate();
    var effectiveFrom = template.get("effective_from", OffsetDateTime.class);
    var effectiveTo = template.get("effective_to", OffsetDateTime.class);
    var moment = issueDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    if (effectiveFrom != null && moment.isBefore(effectiveFrom)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该模板在 " + issueDate + " 尚未生效（生效起点 " + effectiveFrom + "）");
    }
    if (effectiveTo != null && !moment.isBefore(effectiveTo)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该模板在 " + issueDate + " 已失效（失效时间 " + effectiveTo + "）");
    }

    // 🔴 辖区实时校验（不是自由文本；美国辖区录不进去）
    var jurisdiction =
        requireJurisdiction(bearer, template.get("jurisdiction_code", String.class));

    var currency = template.get("currency", String.class);
    // 结算单（同库只读）：币种必须与模板一致，否则这张发票是另一个口径的钱
    if (input.sourceSettlementId() != null) {
      var settlement =
          sql.fetchOne(
              "select settlement_no, currency, status from finance_settlement"
                  + " where tenant_id = ? and id = ?",
              currentTenant(),
              input.sourceSettlementId());
      if (settlement == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "结算单不存在或不属于本租户");
      }
      var settlementCurrency = settlement.get("currency", String.class);
      if (!currency.equals(settlementCurrency)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "结算单 " + settlement.get("settlement_no", String.class) + " 的币种是 " + settlementCurrency
                + "，与模板币种 " + currency + " 不一致 —— 发票不能按另一个口径的币种开");
      }
    }

    var lines = input.lines();
    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要有一行明细");
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_invoice(id,tenant_id,invoice_number,document_type,status,template_id,"
            + " template_code,title,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
            + " shop_id,shop_code,source_receivable_id,source_settlement_id,source_order_no,"
            + " customer_name,currency,issue_date,subtotal_amount,tax_amount,total_amount,reason,"
            + " created_by,created_at,updated_at)"
            + " values(?,?,null,'INVOICE','DRAFT',?,?,?,?,?,?,?,?,?,null,?,?,?,?,?::date,0,0,0,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        template.get("id", UUID.class),
        template.get("template_code", String.class),
        template.get("title", String.class),
        template.get("country_code", String.class),
        jurisdiction.code(),
        jurisdiction.name(),
        template.get("platform_code", String.class),
        input.shopId(),
        input.shopCode() == null ? "" : input.shopCode().trim(),
        input.sourceSettlementId(),
        input.sourceOrderNo() == null ? "" : input.sourceOrderNo().trim(),
        input.customerName() == null ? "" : input.customerName().trim(),
        currency,
        issueDate,
        input.note() == null ? "" : input.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);

    var lineNo = 0;
    for (var line : lines) {
      lineNo++;
      insertLine(id, lineNo, line, template, issueDate, actor, now);
    }

    recordEvent(
        id,
        "CREATED",
        "",
        "DRAFT",
        input.note() == null ? "" : input.note().trim(),
        actor,
        now,
        Map.of("lines", lineNo, "currency", currency, "templateCode", template.get("template_code", String.class)));
    return invoice(id);
  }

  /**
   * 写一行明细（税率在落库前**实时取**，取不到直接报错）。
   *
   * <p>🔴 税率取自 12.6.1.1 的 {@code resolve}：命中不到一律 404，
   * **不退回最新一条、不按 0% 处理**。
   */
  private void insertLine(
      UUID invoiceId,
      int lineNo,
      FinanceInvoiceLineInput line,
      Record template,
      LocalDate issueDate,
      AccessService.Access actor,
      OffsetDateTime now) {
    if (line.quantity() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + lineNo + " 行缺少数量");
    }
    if (line.quantity().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + lineNo + " 行的数量必须为正");
    }
    if (line.unitPrice() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + lineNo + " 行缺少单价");
    }
    if (line.unitPrice().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + lineNo + " 行的单价不能为负");
    }
    var taxType =
        line.taxType() == null || line.taxType().isBlank()
            ? template.get("tax_type", String.class)
            : line.taxType().trim().toUpperCase(Locale.ROOT);
    var taxCode =
        line.taxCode() == null || line.taxCode().isBlank()
            ? template.get("tax_code", String.class)
            : line.taxCode().trim();
    if (taxCode == null || taxCode.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "第 " + lineNo + " 行没有税码 —— 发票行必须落到一条具体的税档上");
    }

    var asOf = issueDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    var resolution =
        taxRates.resolve(
            taxType,
            asOf,
            null,
            template.get("jurisdiction_code", String.class),
            taxCode);

    sql.execute(
        "insert into finance_invoice_line(id,tenant_id,invoice_id,line_no,sku_code,product_name,"
            + " quantity,unit_price,tax_type,tax_code,tax_rate,tax_rate_id,note,created_at)"
            + " values(?,?,?,?,?,?,?::numeric,?::numeric,?,?,?::numeric,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        invoiceId,
        lineNo,
        line.skuCode() == null ? "" : line.skuCode().trim(),
        line.productName() == null ? "" : line.productName().trim(),
        line.quantity(),
        line.unitPrice(),
        taxType,
        taxCode,
        resolution.rate(),
        resolution.taxRateId(),
        line.note() == null ? "" : line.note().trim(),
        now);
  }

  // ================================================================ 开具

  /**
   * 开具：分配正式编号（DRAFT → ISSUED）。
   *
   * <p>🔴 取号是**一条原子语句**（ON CONFLICT DO UPDATE + RETURNING）——
   * 两个并发请求不会拿到同一个号。
   *
   * <p>🔴 贷项单开具时**同时**把原发票收口为「已红冲」（成对）。
   */
  public FinanceInvoiceRow issue(UUID id, AccessService.Access actor) {
    var invoice = invoiceRowOf(id);
    if (invoice == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或不属于本租户");
    }
    var status = invoice.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿能开具（当前状态 " + status + "）");
    }
    var lines = linesOf(id);
    if (lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "空单不能开具：至少要有一行明细");
    }

    var template = templateRowOf(invoice.get("template_id", UUID.class));
    if (template == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张发票引用的模板已不存在，不能开具");
    }
    var issueDate = invoice.get("issue_date", LocalDate.class);
    var number = nextInvoiceNumber(template, issueDate);

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "update finance_invoice set invoice_number = ?, status = 'ISSUED', issued_by = ?,"
              + " issued_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ? and status = 'DRAFT'",
          number,
          actor == null ? null : actor.userId(),
          now,
          now,
          tenant,
          id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(e, "发票号 " + number + " 已被占用（号段规则冲突：请检查模板的编号前缀）");
    }

    recordEvent(
        id,
        "ISSUED",
        "DRAFT",
        "ISSUED",
        "",
        actor,
        now,
        Map.of("invoiceNumber", number, "currency", invoice.get("currency", String.class)));

    // 🔴 贷项单开具 ⇒ 原发票收口为「已红冲」（成对，不允许孤立红冲）
    var originalId = invoice.get("original_invoice_id", UUID.class);
    if (originalId != null) {
      var original = invoiceRowOf(originalId);
      if (original == null || !"ISSUED".equals(original.get("status", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "原发票已不处于「已开具」状态，红冲不能成对，请先确认原发票状态");
      }
      if (original.get("reversal_invoice_id", UUID.class) != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "原发票已被红冲过，不允许重复红冲");
      }
      sql.execute(
          "update finance_invoice set status = 'REVERSED', reversal_invoice_id = ?,"
              + " reversed_by = ?, reversed_at = ?::timestamptz, reason = ?, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          id,
          actor == null ? null : actor.userId(),
          now,
          invoice.get("reason", String.class),
          now,
          tenant,
          originalId);
      recordEvent(
          originalId,
          "REVERSED",
          "ISSUED",
          "REVERSED",
          invoice.get("reason", String.class),
          actor,
          now,
          Map.of("creditNoteId", id.toString(), "creditNoteNumber", number));
    }
    return invoice(id);
  }

  /**
   * 取下一个发票号（**原子**）。
   *
   * <p>🔴 号段键 = 「编号范围 + 号段取值」（租户 + 期间 / 国家），
   * 期间口径由模板决定（NONE / YYYY / YYYY-MM）。
   */
  private String nextInvoiceNumber(Record template, LocalDate issueDate) {
    var scope = template.get("sequence_scope", String.class);
    var scopeValue = template.get("sequence_scope_value", String.class);
    var period = template.get("sequence_period", String.class);
    var prefix = template.get("sequence_prefix", String.class);

    var periodCode =
        switch (period) {
          case "MONTHLY" -> String.format("%04d-%02d", issueDate.getYear(), issueDate.getMonthValue());
          case "YEARLY" -> String.format("%04d", issueDate.getYear());
          default -> "NONE";
        };
    var sequenceKey = scope + ":" + scopeValue;

    // 🔴 一条原子语句：并发下两个请求不会拿到同一个号
    var row =
        sql.fetchOne(
            "insert into finance_invoice_sequence(id,tenant_id,sequence_key,period_code,next_value,"
                + " last_issued_at,created_at,updated_at)"
                + " values(?,?,?,?,1,?::timestamptz,?::timestamptz,?::timestamptz)"
                + " on conflict (tenant_id, sequence_key, period_code) do update"
                + " set next_value = finance_invoice_sequence.next_value + 1,"
                + "     last_issued_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " returning next_value",
            UUID.randomUUID(),
            currentTenant(),
            sequenceKey,
            periodCode,
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC));
    var next = row.get(0, Long.class);

    var head = periodCode.equals("NONE") ? prefix : prefix + "-" + periodCode;
    return head + "-" + String.format("%0" + SEQUENCE_DIGITS + "d", next);
  }

  // ================================================================ 作废

  /**
   * 作废（ISSUED → VOID，或 DRAFT → VOID）。
   *
   * <p>🔴 作废**必须**留痕：谁、何时、为什么（原因必填）。
   *
   * <p>🔴 作废一张**已开具的贷项通知单**时，原发票同步回到「已开具」——
   * 否则「原发票说自己被红冲了、贷项单说自己作废了」，这一对就自相矛盾。
   */
  public FinanceInvoiceRow voidInvoice(UUID id, FinanceInvoiceVoidInput input, AccessService.Access actor) {
    var reason = input == null ? "" : input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明原因");
    }
    var invoice = invoiceRowOf(id);
    if (invoice == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或不属于本租户");
    }
    var status = invoice.get("status", String.class);
    if (!"DRAFT".equals(status) && !"ISSUED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿或已开具的发票能作废（当前状态 " + status + "）");
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_invoice set status = 'VOID', reason = ?, voided_by = ?, voided_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        reason,
        actor == null ? null : actor.userId(),
        now,
        now,
        tenant,
        id);
    recordEvent(id, "VOID", status, "VOID", reason, actor, now, Map.of());

    // 贷项单作废 ⇒ 原发票回到「已开具」（配对不能只断一半）
    var originalId = invoice.get("original_invoice_id", UUID.class);
    if (originalId != null && "ISSUED".equals(status)) {
      var original = invoiceRowOf(originalId);
      if (original != null && "REVERSED".equals(original.get("status", String.class))) {
        sql.execute(
            "update finance_invoice set status = 'ISSUED', reversal_invoice_id = null,"
                + " reversed_by = null, reversed_at = null, reason = ?, updated_at = ?::timestamptz"
                + " where tenant_id = ? and id = ?",
            reason,
            now,
            tenant,
            originalId);
        recordEvent(
            originalId,
            "VOID",
            "REVERSED",
            "ISSUED",
            reason,
            actor,
            now,
            Map.of("creditNoteId", id.toString(), "creditNoteVoided", true));
      }
    }
    return invoice(id);
  }

  // ================================================================ 红冲

  /**
   * 红冲：按原发票**逐行带出**生成一张成对的贷项通知单（草稿）。
   *
   * <p>🔴 红冲金额**从原发票逐行带出，不许手填**：贷项行的数量 / 单价 / 税码 / 税率 /
   * 金额由 DB 守卫逐项镜像原发票行，手填一个「差不多」的数**写不进去**。
   *
   * <p>⚠️ 贷项单是**草稿**：原发票此时**仍是「已开具」** —— 直到贷项单被开具，
   * 原发票才收口为「已红冲」。
   */
  public FinanceInvoiceRow reverse(UUID id, FinanceInvoiceReverseInput input, AccessService.Access actor) {
    var reason = input == null ? "" : input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "红冲必须写明原因");
    }
    var original = invoiceRowOf(id);
    if (original == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原发票不存在或不属于本租户");
    }
    if (!"INVOICE".equals(original.get("document_type", String.class))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能红冲销售发票");
    }
    if (!"ISSUED".equals(original.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只能红冲**已开具**的发票（当前状态 " + original.get("status", String.class) + "）");
    }
    if (original.get("reversal_invoice_id", UUID.class) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该发票已被红冲过，不允许重复红冲");
    }
    // 已有一张**未终结**的贷项通知单（草稿或已开具）⇒ 不允许再开第二张。
    // （DB 层有部分唯一索引兜底，但这里给一句能直接看的话，而不是把约束报错原样抛出去。）
    var openCredit =
        sql.fetchOne(
            "select id from finance_invoice where tenant_id = ? and document_type = 'CREDIT_NOTE'"
                + " and original_invoice_id = ? and status in ('DRAFT','ISSUED') limit 1",
            currentTenant(),
            id);
    if (openCredit != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该发票已有一张未终结的贷项通知单，不允许再开第二张");
    }

    var all = linesOf(id);
    if (all.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "原发票没有明细行，无法红冲");
    }
    var wanted =
        input.lineNos() == null || input.lineNos().isEmpty()
            ? null
            : new TreeSet<>(input.lineNos());
    var selected = new ArrayList<FinanceInvoiceLineRow>();
    for (var line : all) {
      if (wanted == null || wanted.contains(line.lineNo())) selected.add(line);
    }
    if (selected.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要红冲的行在原发票上不存在");
    }
    if (wanted != null && selected.size() != wanted.size()) {
      var found = new TreeSet<Integer>();
      for (var line : selected) found.add(line.lineNo());
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "要红冲的行号在原发票上不存在：" + difference(wanted, found));
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var creditId = UUID.randomUUID();
    var issueDate = LocalDate.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_invoice(id,tenant_id,invoice_number,document_type,status,template_id,"
            + " template_code,title,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
            + " shop_id,shop_code,source_receivable_id,source_settlement_id,source_order_no,"
            + " customer_name,currency,issue_date,subtotal_amount,tax_amount,total_amount,"
            + " original_invoice_id,original_invoice_number,reversal_invoice_id,reason,created_by,"
            + " created_at,updated_at)"
            + " values(?,?,null,'CREDIT_NOTE','DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::date,0,0,0,?,?,null,?,?,?::timestamptz,?::timestamptz)",
        creditId,
        tenant,
        original.get("template_id", UUID.class),
        original.get("template_code", String.class),
        original.get("title", String.class),
        original.get("country_code", String.class),
        original.get("jurisdiction_code", String.class),
        original.get("jurisdiction_name", String.class),
        original.get("platform_code", String.class),
        original.get("shop_id", UUID.class),
        original.get("shop_code", String.class),
        original.get("source_receivable_id", UUID.class),
        original.get("source_settlement_id", UUID.class),
        original.get("source_order_no", String.class),
        original.get("customer_name", String.class),
        original.get("currency", String.class),
        issueDate,
        original.get("id", UUID.class),
        original.get("invoice_number", String.class) == null ? "" : original.get("invoice_number", String.class),
        reason,
        actor == null ? null : actor.userId(),
        now,
        now);

    var lineNo = 0;
    for (var line : selected) {
      lineNo++;
      // 🔴 逐行镜像：数量取负（红冲方向），其余逐项带出
      sql.execute(
          "insert into finance_invoice_line(id,tenant_id,invoice_id,line_no,sku_code,product_name,"
              + " quantity,unit_price,tax_type,tax_code,tax_rate,tax_rate_id,original_line_id,"
              + " original_line_amount,note,created_at)"
              + " values(?,?,?,?,?,?,?::numeric,?::numeric,?,?,?::numeric,?,?,?::numeric,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          creditId,
          lineNo,
          line.skuCode(),
          line.productName(),
          line.quantity().negate(),
          line.unitPrice(),
          line.taxType(),
          line.taxCode(),
          line.taxRate(),
          line.taxRateId(),
          line.id(),
          line.lineAmount().abs(),
          line.note(),
          now);
    }

    recordEvent(
        creditId,
        "CREDIT_NOTE_CREATED",
        "",
        "DRAFT",
        reason,
        actor,
        now,
        Map.of("originalInvoiceId", id.toString(), "lines", lineNo));
    recordEvent(
        id,
        "CREDIT_NOTE_CREATED",
        "ISSUED",
        "ISSUED",
        reason,
        actor,
        now,
        Map.of("creditNoteId", creditId.toString(), "lines", lineNo));
    return invoice(creditId);
  }

  // ================================================================ 状态记录

  /** 状态记录（append-only 留痕，新到旧）。 */
  public List<FinanceInvoiceEventRow> events(UUID invoiceId) {
    if (invoiceRowOf(invoiceId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或不属于本租户");
    }
    var rows =
        sql.fetch(
            "select id,invoice_id,event_type,from_status,to_status,reason,actor_id,"
                + " occurred_at,detail from finance_invoice_event where tenant_id = ? and invoice_id = ?"
                + " order by occurred_at desc, id desc",
            currentTenant(),
            invoiceId);
    var items = new ArrayList<FinanceInvoiceEventRow>();
    for (var row : rows) items.add(eventRow(row));
    return items;
  }

  /** 写一条状态记录。 */
  private void recordEvent(
      UUID invoiceId,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      AccessService.Access actor,
      OffsetDateTime occurredAt,
      Map<String, Object> detail) {
    sql.execute(
        "insert into finance_invoice_event(id,tenant_id,invoice_id,event_type,from_status,to_status,"
            + " reason,actor_id,occurred_at,detail,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        invoiceId,
        eventType,
        fromStatus == null ? "" : fromStatus,
        toStatus == null ? "" : toStatus,
        reason == null ? "" : reason,
        actor == null ? null : actor.userId(),
        occurredAt,
        jsonText(detail),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 待开票需求（上游 9 订单）

  /**
   * 待开票需求清单（上游 trade，**只读**）。
   *
   * <p>上游读不到一律 503 并写明「读不到」—— 绝不返回空集合冒充「没有待开票需求」。
   */
  public FinanceInvoiceRequestPage requests(int page, int size, String bearer) {
    var upstream = invoiceRequests.invoiceRequests(Math.max(page, 0), bearer);
    var items = new ArrayList<FinanceInvoiceRequestRow>();
    for (var r : upstream.items()) {
      var lines = new ArrayList<FinanceInvoiceRequestLine>();
      if (r.lines() != null) {
        for (var l : r.lines()) {
          lines.add(
              new FinanceInvoiceRequestLine(
                  l.skuCode() == null ? "" : l.skuCode(),
                  l.quantity(),
                  l.unitPrice(),
                  l.lineAmount()));
        }
      }
      items.add(
          new FinanceInvoiceRequestRow(
              r.id(),
              r.receivableNo() == null ? "" : r.receivableNo(),
              r.orderId(),
              r.orderNo() == null ? "" : r.orderNo(),
              r.customerName() == null ? "" : r.customerName(),
              r.currency() == null ? "" : r.currency(),
              r.amount(),
              r.invoiceRequestedAt(),
              r.invoiceState() == null ? "" : r.invoiceState(),
              lines));
    }
    return new FinanceInvoiceRequestPage(
        items,
        Math.max(page, 0),
        InvoiceRequestPort.PAGE_SIZE,
        upstream.total(),
        true,
        "9.5.1.2",
        "开票需求来自 9 订单（trade）的 b2b_receivable.invoice_state='REQUESTED'；"
            + "本模块**只读**，不回写 invoice_state。");
  }

  /**
   * 批量建单：从上游待开票需求批量生成草稿。
   *
   * <p>🔴 失败的那几条**逐条说清原因**，不静默吞掉。
   */
  public FinanceInvoiceBatchResult batchCreate(
      FinanceInvoiceBatchInput input, AccessService.Access actor, String bearer) {
    if (input.templateId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定发票模板");
    }
    if (input.requestIds() == null || input.requestIds().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要选一条待开票需求");
    }
    var template = templateRowOf(input.templateId());
    if (template == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票模板不存在或不属于本租户");
    }
    var wanted = Set.copyOf(input.requestIds());
    var found = new LinkedHashMap<UUID, InvoiceRequestPort.TradeReceivableRow>();
    for (var page = 0; page < InvoiceRequestPort.MAX_PAGES; page++) {
      var upstream = invoiceRequests.invoiceRequests(page, bearer);
      if (upstream.items().isEmpty()) break;
      for (var r : upstream.items()) {
        if (r.id() != null && wanted.contains(r.id())) found.put(r.id(), r);
      }
      if (found.size() == wanted.size()) break;
    }

    var created = new ArrayList<FinanceInvoiceRow>();
    var failed = new ArrayList<FinanceInvoiceBatchFailure>();
    for (var id : input.requestIds()) {
      var request = found.get(id);
      if (request == null) {
        failed.add(
            new FinanceInvoiceBatchFailure(id, "上游的待开票需求里找不到这一条（可能已被别的单据开过）"));
        continue;
      }
      try {
        var lines = new ArrayList<FinanceInvoiceLineInput>();
        if (request.lines() != null) {
          for (var l : request.lines()) {
            lines.add(
                new FinanceInvoiceLineInput(
                    l.skuCode() == null ? "" : l.skuCode(),
                    "",
                    l.quantity(),
                    l.unitPrice(),
                    null,
                    null,
                    "来自订单 " + (request.orderNo() == null ? "" : request.orderNo())));
          }
        }
        if (lines.isEmpty()) {
          failed.add(new FinanceInvoiceBatchFailure(id, "上游这条待开票需求没有明细行，无法生成发票"));
          continue;
        }
        var row =
            create(
                new FinanceInvoiceCreateInput(
                    input.templateId(),
                    input.issueDate(),
                    null,
                    "",
                    null,
                    request.orderNo() == null ? "" : request.orderNo(),
                    request.customerName() == null ? "" : request.customerName(),
                    lines,
                    input.note()),
                actor,
                bearer);
        // 关联上游需求（同一张单据只能被开一次）
        sql.execute(
            "update finance_invoice set source_receivable_id = ?, updated_at = ?::timestamptz"
                + " where tenant_id = ? and id = ?",
            id,
            OffsetDateTime.now(ZoneOffset.UTC),
            currentTenant(),
            row.id());
        created.add(invoice(row.id()));
      } catch (RuntimeException e) {
        failed.add(new FinanceInvoiceBatchFailure(id, reasonOf(e)));
      }
    }
    return new FinanceInvoiceBatchResult(created, failed);
  }

  // ================================================================ 汇总（按币种分组）

  /**
   * 发票汇总（**按币种分组**）。
   *
   * <p>🔴 草稿不进合计（草稿不是对外凭据）。🔴 **没有跨币种的合计数字**。
   *
   * @param baseCurrency 折算到的本位币（空 = 不折算）
   */
  public FinanceInvoiceSummary summary(String baseCurrency, String bearer) {
    var rows =
        sql.fetch(
            "select currency,"
                + " sum(subtotal_amount) as subtotal, sum(tax_amount) as tax,"
                + " sum(total_amount) as total,"
                + " count(*) filter (where document_type = 'INVOICE') as invoice_count,"
                + " count(*) filter (where document_type = 'CREDIT_NOTE') as credit_count"
                + " from finance_invoice where tenant_id = ? and status <> 'DRAFT'"
                + " group by currency order by currency",
            currentTenant());
    var base = baseCurrency == null || baseCurrency.isBlank() ? null : baseCurrency.trim().toUpperCase(Locale.ROOT);
    var items = new ArrayList<FinanceInvoiceCurrencySummary>();
    var allRatesAvailable = true;
    for (var row : rows) {
      var currency = row.get("currency", String.class);
      BigDecimal rate = null;
      BigDecimal baseAmount = null;
      String rateNote = "";
      if (base != null && !base.equals(currency)) {
        try {
          var snapshot = exchangeRates.latest(currency, base, null, bearer);
          rate = snapshot.rate();
          baseAmount = row.get("total", BigDecimal.class).multiply(rate).setScale(4, RoundingMode.HALF_UP);
          rateNote = snapshot.display();
        } catch (RuntimeException e) {
          // 🔴 汇率不可得 ⇒ 折算值为 null，**绝不按 1:1 兜底**
          allRatesAvailable = false;
          rateNote = "汇率不可得（catalog 查不到 " + currency + " → " + base + "）";
        }
      } else if (base != null) {
        rate = BigDecimal.ONE;
        baseAmount = row.get("total", BigDecimal.class).setScale(4, RoundingMode.HALF_UP);
        rateNote = "同币种，无需折算";
      } else {
        rateNote = "未请求本位币折算";
      }
      items.add(
          new FinanceInvoiceCurrencySummary(
              currency,
              row.get("subtotal", BigDecimal.class),
              row.get("tax", BigDecimal.class),
              row.get("total", BigDecimal.class),
              row.get("invoice_count", Long.class),
              row.get("credit_count", Long.class),
              baseAmount,
              rate,
              rateNote));
    }

    var counts =
        sql.fetchOne(
            "select count(*) filter (where document_type = 'INVOICE') as invoices,"
                + " count(*) filter (where document_type = 'CREDIT_NOTE') as credit_notes,"
                + " count(*) filter (where status = 'DRAFT') as drafts,"
                + " count(*) filter (where status = 'VOID') as voids,"
                + " count(*) filter (where status = 'REVERSED') as reversed"
                + " from finance_invoice where tenant_id = ?",
            currentTenant());

    String note;
    if (base == null) {
      note = "🔴 合计**按币种分组**呈现 —— 本模块不做本位币折算，也**绝不**跨币种相加"
          + "（按 1:1 相加出来的「总额」是假的）。";
    } else if (allRatesAvailable) {
      note = "已按本位币 " + base + " 折算（汇率取自 catalog 5.2.2.1；同币种按 1 处理）。";
    } else {
      note = "🔴 部分币种的**汇率不可得** —— 这些分组的本位币金额是空的，"
          + "**没有**按 1:1 兜底。";
    }
    return new FinanceInvoiceSummary(
        items,
        counts.get("invoices", Long.class),
        counts.get("credit_notes", Long.class),
        counts.get("drafts", Long.class),
        counts.get("voids", Long.class),
        counts.get("reversed", Long.class),
        base,
        base != null && allRatesAvailable,
        note);
  }

  // ================================================================ 平台开票对接

  /**
   * 生成平台开票报文（确定性）。
   *
   * <p>🔴 金额在报文里是**字符串**：报文是给平台与人工核对的凭据，
   * 走一遍 JSON 浮点解析就可能变成 23.799999999999997。
   */
  public FinanceInvoicePlatformPayload platformPayload(UUID id) {
    var detail = invoiceDetail(id);
    var invoice = detail.invoice();
    var lines = detail.lines();
    var payloadLines = new ArrayList<FinanceInvoicePayloadLine>();
    for (var line : lines) {
      payloadLines.add(
          new FinanceInvoicePayloadLine(
              line.lineNo(),
              line.skuCode(),
              line.productName(),
              plain(line.quantity()),
              plain(line.unitPrice()),
              plain(line.lineAmount()),
              line.taxType(),
              line.taxCode(),
              plain(line.taxRate()),
              plain(line.taxAmount())));
    }
    var totals =
        new FinanceInvoicePayloadTotals(
            plain(invoice.subtotalAmount()), plain(invoice.taxAmount()), plain(invoice.totalAmount()));
    var digest =
        digestOf(
            Map.of(
                "platformCode", invoice.platformCode(),
                "documentType", invoice.documentType(),
                "invoiceNumber", invoice.invoiceNumber() == null ? "" : invoice.invoiceNumber(),
                "currency", invoice.currency(),
                "issueDate", invoice.issueDate().toString(),
                "lines", payloadLines.stream().map(Object::toString).toList(),
                "totals", totals.toString()));
    return new FinanceInvoicePlatformPayload(
        invoice.platformCode(),
        invoice.documentType(),
        invoice.invoiceNumber(),
        invoice.issueDate(),
        invoice.currency(),
        invoice.title(),
        invoice.jurisdictionCode(),
        invoice.jurisdictionName(),
        invoice.countryCode(),
        invoice.customerName(),
        invoice.sourceOrderNo(),
        payloadLines,
        totals,
        digest);
  }

  /**
   * 批量递交平台开票。
   *
   * <p>🔴 成功与失败**逐条**留痕；失败写**上游原话**，不只给一个「失败」。
   */
  public FinanceInvoicePlatformSubmitResult submitToPlatform(
      FinanceInvoicePlatformSubmitInput input, AccessService.Access actor, String bearer) {
    if (input.invoiceIds() == null || input.invoiceIds().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要选一张发票");
    }
    var submitted = new ArrayList<FinanceInvoicePlatformSubmissionRow>();
    var failed = new ArrayList<FinanceInvoicePlatformSubmitFailure>();
    for (var id : input.invoiceIds()) {
      var invoice = invoiceRowOf(id);
      if (invoice == null) {
        failed.add(new FinanceInvoicePlatformSubmitFailure(id, "发票不存在或不属于本租户"));
        continue;
      }
      if (!"ISSUED".equals(invoice.get("status", String.class))) {
        failed.add(
            new FinanceInvoicePlatformSubmitFailure(
                id, "只有已开具的发票能递交平台（当前状态 " + invoice.get("status", String.class) + "）"));
        continue;
      }
      var platformCode =
          input.platformCode() == null || input.platformCode().isBlank()
              ? invoice.get("platform_code", String.class)
              : input.platformCode().trim().toUpperCase(Locale.ROOT);
      if (platformCode == null || platformCode.isBlank()) {
        failed.add(
            new FinanceInvoicePlatformSubmitFailure(
                id, "这张发票没有平台编码（模板未限定平台），无法递交 —— 请指定平台"));
        continue;
      }
      var payload = platformPayload(id);
      var payloadMap = payloadMap(payload);
      var now = OffsetDateTime.now(ZoneOffset.UTC);
      var outcome = platformInvoices.submit(bearer, platformCode, payloadMap);

      var submissionId = UUID.randomUUID();
      sql.execute(
          "insert into finance_invoice_platform_submission(id,tenant_id,invoice_id,platform_code,"
              + " status,payload,payload_digest,attempts,platform_ref,error_message,submitted_at,"
              + " acknowledged_at,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?::jsonb,?,1,?,?,?::timestamptz,null,?,?::timestamptz,?::timestamptz)",
          submissionId,
          currentTenant(),
          id,
          platformCode,
          outcome.accepted() ? "SUBMITTED" : "FAILED",
          jsonText(payloadMap),
          payload.digest(),
          outcome.platformRef() == null ? "" : outcome.platformRef(),
          outcome.reason() == null ? "" : outcome.reason(),
          now,
          actor == null ? null : actor.userId(),
          now,
          now);

      sql.execute(
          "update finance_invoice set platform_sync_status = ?, platform_invoice_ref = ?,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          outcome.accepted() ? "SUBMITTED" : "FAILED",
          outcome.platformRef() == null ? "" : outcome.platformRef(),
          now,
          currentTenant(),
          id);

      recordEvent(
          id,
          outcome.accepted() ? "PLATFORM_SUBMITTED" : "PLATFORM_FAILED",
          invoice.get("status", String.class),
          invoice.get("status", String.class),
          outcome.reason() == null ? "" : outcome.reason(),
          actor,
          now,
          Map.of("platformCode", platformCode, "submissionId", submissionId.toString()));

      if (outcome.accepted()) {
        submitted.add(submission(submissionId));
      } else {
        failed.add(new FinanceInvoicePlatformSubmitFailure(id, outcome.reason()));
      }
    }
    return new FinanceInvoicePlatformSubmitResult(submitted, failed);
  }

  /** 平台提交记录清单。 */
  public FinanceInvoicePlatformSubmissionPage submissions(int page, int size) {
    var total =
        sql.fetchOne("select count(*) from finance_invoice_platform_submission where tenant_id = ?",
                currentTenant())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    var rows =
        sql.fetch(
            "select s.id,s.invoice_id,s.platform_code,s.status,s.payload_digest,s.attempts,"
                + " s.platform_ref,s.error_message,s.submitted_at,s.acknowledged_at,s.created_at,"
                + " i.invoice_number"
                + " from finance_invoice_platform_submission s"
                + " left join finance_invoice i on i.tenant_id = s.tenant_id and i.id = s.invoice_id"
                + " where s.tenant_id = ? order by s.created_at desc, s.id desc limit ? offset ?",
            currentTenant(),
            limit,
            offset);
    var items = new ArrayList<FinanceInvoicePlatformSubmissionRow>();
    for (var row : rows) items.add(submissionRow(row));
    return new FinanceInvoicePlatformSubmissionPage(items, Math.max(page, 0), limit, total);
  }

  /**
   * 登记平台回执。
   *
   * <p>⚠️ 平台**回执**与平台**受理**是两件事：受理只是「平台收下了」，
   * 回执是「平台确认了这张发票」。两者都留痕。
   */
  public FinanceInvoicePlatformSubmissionRow acknowledge(
      UUID submissionId, FinanceInvoicePlatformAcknowledgeInput input, AccessService.Access actor) {
    var row =
        sql.fetchOne(
            "select id,invoice_id,platform_code,status,payload_digest,attempts,platform_ref,"
                + " error_message,submitted_at,acknowledged_at,created_at"
                + " from finance_invoice_platform_submission where tenant_id = ? and id = ?",
            currentTenant(),
            submissionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台提交记录不存在或不属于本租户");
    }
    var result = input == null || input.result() == null ? "" : input.result().trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ACKNOWLEDGED", "FAILED").contains(result)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "回执结果只能是 ACKNOWLEDGED（平台已确认）或 FAILED（平台驳回）");
    }
    var platformRef =
        input.platformRef() == null || input.platformRef().isBlank()
            ? row.get("platform_ref", String.class)
            : input.platformRef().trim();
    var message = input.message() == null ? "" : input.message().trim();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_invoice_platform_submission set status = ?, platform_ref = ?,"
            + " error_message = ?, acknowledged_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        result,
        platformRef,
        "ACKNOWLEDGED".equals(result) ? "" : message,
        now,
        now,
        currentTenant(),
        submissionId);
    sql.execute(
        "update finance_invoice set platform_sync_status = ?, platform_invoice_ref = ?,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        result,
        platformRef,
        now,
        currentTenant(),
        row.get("invoice_id", UUID.class));
    recordEvent(
        row.get("invoice_id", UUID.class),
        "ACKNOWLEDGED".equals(result) ? "PLATFORM_ACKNOWLEDGED" : "PLATFORM_FAILED",
        "ISSUED",
        "ISSUED",
        message,
        actor,
        now,
        Map.of("platformRef", platformRef, "submissionId", submissionId.toString()));
    return submission(submissionId);
  }

  // ================================================================ 内部工具

  /** 辖区码必须真实落在 12.6.1.1 的受控辖区码表里，且**不是**美国辖区。 */
  private FinanceTaxJurisdictionOption requireJurisdiction(String bearer, String code) {
    var upstream = taxRates.jurisdictionOptions(bearer);
    for (var j : upstream.items()) {
      if (j.code() == null || !j.code().equalsIgnoreCase(code)) continue;
      if (!j.usableForTaxRate()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, j.unusableReason());
      }
      return j;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "辖区码 " + code + " 不在 12.6.1.1 的受控辖区码表里 —— 辖区码不是自由文本");
  }

  /** 按 id 取一张发票（原始行）。 */
  private Record invoiceRowOf(UUID id) {
    return sql.fetchOne(
        "select " + INVOICE_COLUMNS + " from finance_invoice where tenant_id = ? and id = ?",
        currentTenant(),
        id);
  }

  /** 按 id 取一张模板（原始行）。 */
  private Record templateRowOf(UUID id) {
    return sql.fetchOne(
        "select id,template_code,template_name,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
            + " document_type,tax_type,tax_code,currency,title,sequence_prefix,sequence_scope,"
            + " sequence_scope_value,sequence_period,effective_from,effective_to,status,note,"
            + " created_by,created_at,updated_at"
            + " from finance_invoice_template where tenant_id = ? and id = ?",
        currentTenant(),
        id);
  }

  /** 按 id 取一张发票（对外模型）。 */
  private FinanceInvoiceRow invoice(UUID id) {
    var row = invoiceRowOf(id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在或不属于本租户");
    }
    return invoiceRow(row, "");
  }

  /** 按 id 取一条提交记录（对外模型）。 */
  private FinanceInvoicePlatformSubmissionRow submission(UUID id) {
    var row =
        sql.fetchOne(
            "select s.id,s.invoice_id,s.platform_code,s.status,s.payload_digest,s.attempts,"
                + " s.platform_ref,s.error_message,s.submitted_at,s.acknowledged_at,s.created_at,"
                + " i.invoice_number"
                + " from finance_invoice_platform_submission s"
                + " left join finance_invoice i on i.tenant_id = s.tenant_id and i.id = s.invoice_id"
                + " where s.tenant_id = ? and s.id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台提交记录不存在或不属于本租户");
    }
    return submissionRow(row);
  }

  /** 一行 → 发票（辖区名取单据上的快照）。 */
  private FinanceInvoiceRow invoiceRow(Record row, String ignoredJurisdictionName) {
    var docType = DocumentType.parse(row.get("document_type", String.class));
    var status = InvoiceStatus.parse(row.get("status", String.class));
    var sync = PlatformSyncStatus.parse(row.get("platform_sync_status", String.class));
    return new FinanceInvoiceRow(
        row.get("id", UUID.class),
        row.get("invoice_number", String.class),
        row.get("document_type", String.class),
        docType == null ? row.get("document_type", String.class) : docType.label(),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("template_code", String.class),
        row.get("title", String.class),
        row.get("country_code", String.class),
        row.get("jurisdiction_code", String.class),
        row.get("jurisdiction_name", String.class),
        row.get("platform_code", String.class),
        row.get("shop_id", UUID.class),
        row.get("shop_code", String.class),
        row.get("source_receivable_id", UUID.class),
        row.get("source_settlement_id", UUID.class),
        row.get("source_order_no", String.class),
        row.get("customer_name", String.class),
        row.get("currency", String.class),
        row.get("issue_date", LocalDate.class),
        row.get("subtotal_amount", BigDecimal.class),
        row.get("tax_amount", BigDecimal.class),
        row.get("total_amount", BigDecimal.class),
        row.get("original_invoice_id", UUID.class),
        row.get("original_invoice_number", String.class),
        row.get("reversal_invoice_id", UUID.class),
        row.get("reason", String.class),
        row.get("issued_by", UUID.class),
        row.get("issued_at", OffsetDateTime.class),
        row.get("voided_by", UUID.class),
        row.get("voided_at", OffsetDateTime.class),
        row.get("reversed_by", UUID.class),
        row.get("reversed_at", OffsetDateTime.class),
        row.get("platform_sync_status", String.class),
        sync == null ? row.get("platform_sync_status", String.class) : sync.label(),
        row.get("platform_invoice_ref", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 一行 → 模板（辖区名取表里的快照）。 */
  private FinanceInvoiceTemplateRow templateRow(Record row, String ignoredJurisdictionName) {
    var docType = DocumentType.parse(row.get("document_type", String.class));
    return new FinanceInvoiceTemplateRow(
        row.get("id", UUID.class),
        row.get("template_code", String.class),
        row.get("template_name", String.class),
        row.get("country_code", String.class),
        row.get("jurisdiction_code", String.class),
        row.get("jurisdiction_name", String.class),
        row.get("platform_code", String.class),
        row.get("document_type", String.class),
        docType == null ? row.get("document_type", String.class) : docType.label(),
        row.get("tax_type", String.class),
        row.get("tax_code", String.class),
        row.get("currency", String.class),
        row.get("title", String.class),
        row.get("sequence_prefix", String.class),
        row.get("sequence_scope", String.class),
        row.get("sequence_period", String.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 一行 → 明细行。 */
  private FinanceInvoiceLineRow lineRow(Record row) {
    return new FinanceInvoiceLineRow(
        row.get("id", UUID.class),
        row.get("invoice_id", UUID.class),
        row.get("line_no", Integer.class),
        row.get("sku_code", String.class),
        row.get("product_name", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("unit_price", BigDecimal.class),
        row.get("line_amount", BigDecimal.class),
        row.get("tax_type", String.class),
        row.get("tax_code", String.class),
        row.get("tax_rate", BigDecimal.class),
        row.get("tax_rate_id", UUID.class),
        row.get("tax_amount", BigDecimal.class),
        row.get("original_line_id", UUID.class),
        row.get("original_line_amount", BigDecimal.class),
        row.get("note", String.class));
  }

  /** 一行 → 状态记录。 */
  private FinanceInvoiceEventRow eventRow(Record row) {
    Map<String, Object> detail = Map.of();
    var raw = row.get("detail", String.class);
    if (raw != null && !raw.isBlank()) {
      try {
        detail = json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      } catch (Exception e) {
        detail = Map.of("raw", raw);
      }
    }
    return new FinanceInvoiceEventRow(
        row.get("id", UUID.class),
        row.get("invoice_id", UUID.class),
        row.get("event_type", String.class),
        eventTypeLabel(row.get("event_type", String.class)),
        row.get("from_status", String.class),
        row.get("to_status", String.class),
        row.get("reason", String.class),
        row.get("actor_id", UUID.class),
        row.get("occurred_at", OffsetDateTime.class),
        detail);
  }

  /** 一行 → 平台提交记录。 */
  private FinanceInvoicePlatformSubmissionRow submissionRow(Record row) {
    var status = row.get("status", String.class);
    return new FinanceInvoicePlatformSubmissionRow(
        row.get("id", UUID.class),
        row.get("invoice_id", UUID.class),
        row.get("invoice_number", String.class),
        row.get("platform_code", String.class),
        status,
        platformStatusLabel(status),
        row.get("payload_digest", String.class),
        row.get("attempts", Integer.class),
        row.get("platform_ref", String.class),
        row.get("error_message", String.class),
        row.get("submitted_at", OffsetDateTime.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private String eventTypeLabel(String raw) {
    return switch (raw == null ? "" : raw) {
      case "CREATED" -> "已创建";
      case "ISSUED" -> "已开具";
      case "VOID" -> "已作废";
      case "REVERSED" -> "已红冲";
      case "PLATFORM_SUBMITTED" -> "已提交平台";
      case "PLATFORM_ACKNOWLEDGED" -> "平台已确认";
      case "PLATFORM_FAILED" -> "平台提交失败";
      case "CREDIT_NOTE_CREATED" -> "已生成贷项通知单";
      default -> raw;
    };
  }

  private String platformStatusLabel(String raw) {
    return switch (raw == null ? "" : raw) {
      case "PENDING" -> "待提交";
      case "SUBMITTED" -> "已提交";
      case "ACKNOWLEDGED" -> "平台已确认";
      case "FAILED" -> "提交失败";
      default -> raw;
    };
  }

  /** 报文 → 可落库的 Map（顺序固定 ⇒ 摘要稳定）。 */
  private Map<String, Object> payloadMap(FinanceInvoicePlatformPayload payload) {
    var map = new LinkedHashMap<String, Object>();
    map.put("platformCode", payload.platformCode());
    map.put("documentType", payload.documentType());
    map.put("invoiceNumber", payload.invoiceNumber());
    map.put("issueDate", payload.issueDate().toString());
    map.put("currency", payload.currency());
    map.put("title", payload.title());
    map.put("jurisdictionCode", payload.jurisdictionCode());
    map.put("jurisdictionName", payload.jurisdictionName());
    map.put("countryCode", payload.countryCode());
    map.put("customerName", payload.customerName());
    map.put("sourceOrderNo", payload.sourceOrderNo());
    var lines = new ArrayList<Map<String, Object>>();
    for (var line : payload.lines()) {
      var l = new LinkedHashMap<String, Object>();
      l.put("lineNo", line.lineNo());
      l.put("skuCode", line.skuCode());
      l.put("productName", line.productName());
      l.put("quantity", line.quantity());
      l.put("unitPrice", line.unitPrice());
      l.put("lineAmount", line.lineAmount());
      l.put("taxType", line.taxType());
      l.put("taxCode", line.taxCode());
      l.put("taxRate", line.taxRate());
      l.put("taxAmount", line.taxAmount());
      lines.add(l);
    }
    map.put("lines", lines);
    var totals = new LinkedHashMap<String, Object>();
    totals.put("subtotal", payload.totals().subtotal());
    totals.put("tax", payload.totals().tax());
    totals.put("total", payload.totals().total());
    map.put("totals", totals);
    return map;
  }

  /** 摘要（sha-256）。 */
  private String digestOf(Object value) {
    try {
      var bytes = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
      var hex = new StringBuilder();
      for (var b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** JSON 文本（落 jsonb 用）。 */
  private String jsonText(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 金额 / 数量的展示形态：去无意义的尾零。 */
  private String plain(BigDecimal value) {
    if (value == null) return "0";
    return value.stripTrailingZeros().toPlainString();
  }

  /** 必填字符串。 */
  private String require(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须填写" + label);
    }
    return value.trim();
  }

  /** 行号差集（给「要红冲的行不存在」一句能直接看的话）。 */
  private String difference(Set<Integer> wanted, Set<Integer> found) {
    var missing = new ArrayList<Integer>();
    for (var n : wanted) {
      if (!found.contains(n)) missing.add(n);
    }
    return missing.toString();
  }

  /** 异常里的原因（批量里逐条说清）。 */
  private String reasonOf(RuntimeException e) {
    if (e instanceof ResponseStatusException status && status.getReason() != null) {
      return status.getReason();
    }
    var message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  /** 唯一约束冲突 ⇒ 一句能直接看的话。 */
  private ResponseStatusException conflict(org.jooq.exception.DataAccessException e, String detail) {
    return new ResponseStatusException(HttpStatus.CONFLICT, detail, e);
  }

  /** 当前租户（由 backend/libraries/tenancy 在事务里绑定的 app.current_tenant）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：发票管理必须在绑定租户的事务里执行");
    }
    return raw;
  }
}
