package com.starcon.finance;

import static com.starcon.finance.FilingDraftModels.*;

import com.starcon.finance.FilingOrderTaxPort.OrderDetail;
import com.starcon.finance.TaxRateModels.FinanceTaxJurisdictionOption;
import com.starcon.finance.TaxRateModels.FinanceTaxResolution;
import io.swagger.v3.oas.annotations.media.Schema;
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
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.6.1.2 VAT/IOSS 申报数据。
 *
 * <p>蓝图 12-finance.md：「按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS
 * 订单与代 collected 金额、可抵扣进项，导出申报文件或对接申报接口。」输入：「订单税务数据；
 * 结算与收付款数据；汇率快照；辖区规则。」输出：「申报底稿 + 汇总报表 + 可导出文件。」
 * 关系：「上游 = 12.6.1.1、9 订单、12.2.1.1；下游 = 16 合规（申报日历提醒）。<b>本系统提供
 * 申报底稿，不替代税务申报动作</b>——申报主体与责任在用户，系统保证数据完整可核。」
 *
 * <p><b>🔴 本模块不做「向税务机构提交申报」这个动作</b>，也不假装提交成功。产出只有三样：
 * 申报底稿（{@code finance_filing_draft} + 汇总行）、汇总报表（同一份汇总行的结构化呈现）、
 * 可导出文件（CSV / JSON，真文件、有留痕）。申报主体与责任在用户。
 *
 * <p><b>🔴 多币种绝不相加</b>：汇总行按（底稿 × 币种）唯一，底稿头**没有任何金额列**。
 * 折算是**附加**的一列，只在汇率真的取到时才有值。
 *
 * <p><b>🔴 读不到 ≠ 是零</b>：IOSS 清单 / 可抵扣进项 / 汇率 / 实际到账在读不到时是 {@code null}
 * 配一句写明原因的 note，**不退化成 0**。
 *
 * <p><b>🔴 辖区码不是自由文本</b>：落库前经 channel 16.3.1.1 的受控码表**实时**校验；
 * 美国辖区（联邦与 50 州）**录不进去**（州销售税归 channel 16.3.2.3，本模块不重复建表）。
 *
 * <p><b>🔴 税率按生效时间取</b>：走 12.6.1.1 的「按生效时间取税率」，**命中不到就报错**
 * （不退回最新一条、不按 0% 处理），并把**当时命中的那一版**快照进底稿行。
 */
@Service
public class FilingDraftService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单次取数最多处理多少张订单（超出部分记 order_truncated，**不假装全取到了**）。 */
  static final int MAX_ORDERS = 500;

  /** 金额小数位（金额列是 numeric(19,4)）。 */
  static final int AMOUNT_SCALE = 4;

  /** 汇率小数位（汇率列是 numeric(19,8)）。 */
  static final int RATE_SCALE = 8;

  /** 🔴 美国辖区归 channel 16.3.2.3，本模块不重复建表。 */
  static final String CHANNEL_OWNED_COUNTRY = "US";

  /** 底稿的币种分组累加器（一个币种一份）。 */
  private static final class CurrencyBucket {
    BigDecimal taxableSales = BigDecimal.ZERO;
    BigDecimal vatCollected = BigDecimal.ZERO;
    int orderCount;
    Integer iossOrderCount;
    BigDecimal iossCollected;
  }

  private final DSLContext sql;
  private final TaxRateService taxRates;
  private final ChannelTaxPort channelTax;
  private final ChannelIossPort channelIoss;
  private final FilingOrderTaxPort orderTax;
  private final ExchangeRatePort exchangeRates;

  public FilingDraftService(
      DSLContext sql,
      TaxRateService taxRates,
      ChannelTaxPort channelTax,
      ChannelIossPort channelIoss,
      FilingOrderTaxPort orderTax,
      ExchangeRatePort exchangeRates) {
    this.sql = sql;
    this.taxRates = taxRates;
    this.channelTax = channelTax;
    this.channelIoss = channelIoss;
    this.orderTax = orderTax;
    this.exchangeRates = exchangeRates;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 本模块**不产出任何「申报状态」**：申报是用户对税务机构做的动作，本系统既不做、
   * 也不假装做。能力声明里把这条写进 {@code productBoundary}。
   */
  public FinanceFilingCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceFilingCapability>();
    var gaps = new ArrayList<FinanceFilingGap>();

    capabilities.add(
        new FinanceFilingCapability(
            "12.6.1.2",
            "申报底稿（按辖区 × 周期 × 币种分组的汇总快照）",
            true,
            "finance_filing_draft + finance_filing_draft_line（V364，纯 CREATE TABLE）",
            "",
            "底稿头**没有任何金额列**，金额只在**按币种分组**的汇总行上 ⇒ 页面上不存在"
                + "一个「跨币种总额」这种没有意义的数。底稿是不可改写的快照（DB 触发器钉死）："
                + "重新取数 = 旧底稿置 SUPERSEDED + 新起一版，历史版本仍然查得到、导得出。"));

    capabilities.add(
        new FinanceFilingCapability(
            "12.6.1.1",
            "按生效时间取税率（继承 12.6.1.1 的结构语义）",
            true,
            "finance_tax_rate 的生效区间查询（TaxRateService.resolve）",
            "",
            "取数走 12.6.1.1 的「按生效时间取税率」，**命中不到一律报错**，"
                + "不退回最新一条、不按 0% 处理；底稿行上快照的是**当时命中的那一版**"
                + "（税率 id + 税码 + 生效区间），所以这份底稿能自证「按哪一版税率算的」。"));

    capabilities.add(
        new FinanceFilingCapability(
            "16.3.1.1",
            "受控辖区码表（目的国/地区的值域）",
            true,
            "channel GET /channel/tax-registration/jurisdictions（ChannelTaxPort 只读）",
            "16.3.1.1",
            "辖区码**实时**读 channel，本模块**不复制**那张码表、**不建**指向它的外键。"
                + "落库前校验辖区码真实存在 ⇒ 辖区码**不是自由文本**。"
                + "**美国辖区（联邦与 50 州）录不进去** —— 州级销售税归 channel 16.3.2.3，"
                + "本模块不重复建表。channel 读不到一律 503 并写明「读不到」。"));

    capabilities.add(
        new FinanceFilingCapability(
            "9",
            "订单税务数据（应税销售额 / 已收 VAT 的来源）",
            true,
            "trade GET /trade/sales-orders/list + /trade/sales-orders/detail/{id}"
                + "（FilingOrderTaxPort 只读）",
            "",
            "订单在 trade 服务，**跨服务只走授权 HTTP API**（禁跨库 SQL / 跨库 FK / 跨库视图）。"
                + "收货国决定辖区归属；订单列表读不到时**整个取数失败（503）**，"
                + "因为没有订单事实的申报底稿比没有底稿更糟。单张订单明细读不到时"
                + "**如实计数**（unmapped）而不是悄悄丢掉。"));

    capabilities.add(
        new FinanceFilingCapability(
            "16.3.2.1",
            "IOSS 订单清单与平台代收金额",
            false,
            "channel GET " + ChannelIossPort.ORDERS_PATH + "（ChannelIossPort 只读）",
            "16.3.2.1",
            "🔴 **16.3.2.1 与本模块同批开发中，集成头里还没有**。读不到时底稿里 IOSS 两列"
                + "**留成 NULL**（不是 0）并写明「读不到 IOSS 订单清单」，"
                + "底稿的其它部分照常产出。**绝不因为上游没交付就编一份 IOSS 清单出来**。"));

    capabilities.add(
        new FinanceFilingCapability(
            "12.5.2.1",
            "可抵扣进项（从已过账凭证 + 科目属性取）",
            true,
            "finance_voucher + finance_voucher_line + finance_account_subject",
            "",
            "🔴 仓库里**没有**「进项发票」这个概念 ⇒ 本模块**不建**任何进项表"
                + "（建一张空表再从里面读出 0，是**假装有数据**）。口径 = 覆盖期间内"
                + "**已过账（POSTED）**凭证中，应交税费类科目（category = 'LIABILITY'）且"
                + "税务属性为应税口径（TAXABLE / REVERSE_CHARGE）的**借方**发生额。"
                + "取不到就是**取不到**：deductible_available = false 且金额留 NULL，"
                + "**不退化成 0**（0 会被当成「可抵扣进项是零」这个业务结论）。"));

    capabilities.add(
        new FinanceFilingCapability(
            "5.2.2.1",
            "汇率快照（折算用）",
            true,
            "catalog GET /catalog/exchange-rates/latest（ExchangeRatePort 只读）",
            "",
            "🔴 汇率取不到时**不折算**（converted 留 NULL），**绝不按 1:1 兜底** —— "
                + "1:1 兜底 = 编一个汇率出来。折算是**附加**列，底稿的主口径永远是按币种分组。"));

    capabilities.add(
        new FinanceFilingCapability(
            "12.2.1.1",
            "结算与收付款数据（期间实际到账，用于与「已收 VAT」对照）",
            true,
            "finance_receipt（本服务同库，只读）",
            "",
            "覆盖期间内**已登记**收款单按币种分组的到账金额，写在汇总行的 received_amount 上，"
                + "让底稿能对照「已收 VAT」与「钱到没到」。本模块**只读**收款单，不写。"));

    gaps.add(
        new FinanceFilingGap(
            "16.3.1.2",
            "申报日历（申报期提醒）",
            "channel",
            false,
            "下游",
            "🔴 **未交付**。它是本模块的**下游**：本模块产出底稿，提醒归它。"
                + "本模块**不建**任何日历表、也不自己发提醒。"));

    gaps.add(
        new FinanceFilingGap(
            "12.5.2.2",
            "财务报表",
            "finance",
            false,
            "下游",
            "🔴 **同批在途**（迁移 V360）。报表是底稿的下游消费方；本模块**不产出**报表口径，"
                + "只产出底稿与汇总。"));

    gaps.add(
        new FinanceFilingGap(
            "16.3.2.1",
            "VAT/IOSS 事实（IOSS 订单清单）",
            "channel",
            false,
            "上游",
            "🔴 **同批开发中**（迁移 V365）。本模块是它的**下游消费者**："
                + "读不到时如实报「读不到 IOSS 订单清单」，**不假造数据**。"));

    gaps.add(
        new FinanceFilingGap(
            "TAX_FILING_SUBMISSION",
            "向税务机构提交申报",
            "（无归属模块）",
            false,
            "产品边界",
            "🔴 **本模块不做这个动作，也不假装做**。申报主体与责任在用户；"
                + "系统保证的是**数据完整可核**（底稿 + 口径 + 税率版本 + 导出留痕）。"));

    return new FinanceFilingCapabilities(
        capabilities,
        gaps,
        "🔴 本系统提供**申报底稿**，不替代税务申报动作："
            + "底稿 + 汇总报表 + 可导出文件（CSV / JSON），"
            + "**不向税务机构提交申报**，也不产生任何「已申报 / 已受理」的结论。");
  }

  // ================================================================ 选项

  /**
   * 筛选项：申报线与周期来自本模块的词表，辖区**实时**读 channel 的受控码表，
   * 币种来自**真实底稿数据**。
   */
  public FinanceFilingOptions options(String bearer) {
    var taxTypes = new ArrayList<FinanceFilingTypeOption>();
    for (var t : FilingTaxType.values()) {
      taxTypes.add(new FinanceFilingTypeOption(t.name(), t.label(), t.basis()));
    }
    var cycles = new ArrayList<FinanceFilingCycleOption>();
    for (var c : FilingCycle.values()) {
      cycles.add(new FinanceFilingCycleOption(c.name(), c.label()));
    }
    var statuses = new ArrayList<String>();
    for (var s : DraftStatus.values()) statuses.add(s.name());

    var currencies =
        sql.fetch(
                "select distinct currency from finance_filing_draft_line order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .filter(java.util.Objects::nonNull)
            .toList();

    // 辖区选项复用 12.6.1.1 的那一份（**同一个受控码表，不复制**）。
    var jurisdictions = taxRates.jurisdictionOptions(bearer);
    return new FinanceFilingOptions(taxTypes, cycles, statuses, currencies, jurisdictions);
  }

  // ================================================================ 底稿列表

  /** 底稿列表（真分页 + 真筛选）。 */
  public FinanceFilingDraftPage drafts(
      String jurisdictionCode,
      String taxType,
      String status,
      LocalDate periodFrom,
      LocalDate periodTo,
      String keyword,
      int page,
      int size) {
    var type = taxType == null || taxType.isBlank() ? null : FilingTaxType.parse(taxType);
    if (type == null && taxType != null && !taxType.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "申报线只能是 VAT 或 IOSS");
    }
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (jurisdictionCode != null && !jurisdictionCode.isBlank()) {
      where.append(" and jurisdiction_code = ?");
      binds.add(jurisdictionCode.trim().toUpperCase(Locale.ROOT));
    }
    if (type != null) {
      where.append(" and tax_type = ?");
      binds.add(type.name());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (periodFrom != null) {
      where.append(" and period_end >= ?");
      binds.add(periodFrom);
    }
    if (periodTo != null) {
      where.append(" and period_start <= ?");
      binds.add(periodTo);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (jurisdiction_code ilike ? or jurisdiction_name ilike ? or note ilike ?"
              + " or generation_basis ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_filing_draft" + where, binds.toArray())
            .get(0, Long.class);
    var liveCount =
        sql.fetchOne(
                "select count(*) from finance_filing_draft where tenant_id = ? and status = 'GENERATED'",
                currentTenant())
            .get(0, Long.class);

    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,jurisdiction_code,jurisdiction_name,country_code,tax_type,period_start,"
                + " period_end,filing_cycle,base_currency,order_source,status,order_count,"
                + " included_count,out_of_scope_count,unmapped_count,order_truncated,"
                + " generation_basis,generated_at,generated_by,note,created_at"
                + " from finance_filing_draft" + where
                + " order by generated_at desc, period_start desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceFilingDraftRow>();
    for (var row : rows) items.add(draftRow(row));
    return new FinanceFilingDraftPage(items, Math.max(page, 0), limit, total, liveCount);
  }

  // ================================================================ 生成底稿

  /**
   * 生成一份申报底稿。
   *
   * <p>🔴 顺序是刻意的：<b>先校验、再取数、最后落库</b>。
   *
   * <ol>
   *   <li>辖区码<b>实时</b>校验（channel 受控码表）⇒ 辖区码不是自由文本；
   *       美国辖区在这里被挡下并写明归 channel 16.3.2.3。</li>
   *   <li>税率<b>按生效时间取</b>（12.6.1.1）⇒ 命中不到就报错，不退回最新一条、不按 0% 处理。</li>
   *   <li>订单税务数据（trade 只读端口）⇒ 读不到就**整个取数失败**，不做成「0 张订单」。</li>
   *   <li>可抵扣进项（已过账凭证 + 科目属性）⇒ 取不到就如实报「无可抵扣进项数据」。</li>
   *   <li>汇率（catalog 只读端口）⇒ 取不到就**不折算**，绝不按 1:1 兜底。</li>
   * </ol>
   */
  public FinanceFilingDraftDetail generate(
      FinanceFilingDraftInput input, AccessService.Access actor, String bearer) {
    var type = FilingTaxType.parse(input.taxType());
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "申报线只能是 VAT 或 IOSS");
    }
    var cycle = FilingCycle.parse(input.filingCycle());
    if (cycle == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "申报周期只能是 MONTHLY 或 QUARTERLY");
    }
    if (input.jurisdictionCode() == null || input.jurisdictionCode().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区（目的国/地区）");
    }
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出覆盖期间的起止日");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "覆盖期间的结束日不能早于起始日");
    }
    if (input.taxCode() == null || input.taxCode().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给出税码：不同税档是不同税率，取数时不能替你猜一个");
    }
    var taxCode = input.taxCode().trim();
    if (taxCode.length() > 32) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "税码最长 32 个字符");
    }
    var baseCurrency = normalizeBaseCurrency(input.baseCurrency());
    var orderSource = input.orderSource() == null ? "" : input.orderSource().trim().toUpperCase(Locale.ROOT);

    // ① 辖区码实时校验（受控码表）。
    var jurisdiction = requireJurisdiction(bearer, input.jurisdictionCode().trim().toUpperCase(Locale.ROOT));

    // ② 税率按生效时间取：命中不到就报错（12.6.1.1 的结构语义，本模块继承）。
    var asOf =
        input
            .periodEnd()
            .atTime(23, 59, 59, 999_000_000)
            .atOffset(ZoneOffset.UTC);
    var resolution =
        taxRates.resolve(type.name(), asOf, null, jurisdiction.code(), taxCode);

    // ③ 订单税务数据（trade 只读端口）。列表读不到 ⇒ 整个取数失败。
    var orders =
        orderTax.orders(
            orderSource.isEmpty() ? null : orderSource,
            FilingOrderTaxPort.periodStart(input.periodStart()),
            FilingOrderTaxPort.periodEnd(input.periodEnd()),
            bearer);
    var orderRows = orders.rows();
    var truncated = orders.total() > orderRows.size();
    if (orderRows.size() > MAX_ORDERS) {
      orderRows = orderRows.subList(0, MAX_ORDERS);
      truncated = true;
    }

    // 收货国 → 受控辖区码表里的**国家一级**辖区。
    var upstream = channelTax.jurisdictions(bearer);
    var countryToJurisdiction = new LinkedHashMap<String, String>();
    for (var j : upstream) {
      if (j.countryCode() == null || j.countryCode().isBlank()) continue;
      if (j.subdivisionCode() != null && !j.subdivisionCode().isBlank()) continue;
      countryToJurisdiction.putIfAbsent(
          j.countryCode().trim().toUpperCase(Locale.ROOT), j.code());
    }

    var buckets = new LinkedHashMap<String, CurrencyBucket>();
    var included = 0;
    var outOfScope = 0;
    var unmapped = 0;
    var unreadable = 0;

    for (var order : orderRows) {
      OrderDetail detail;
      try {
        detail = orderTax.detail(order.id(), bearer);
      } catch (ResponseStatusException e) {
        // 单张订单明细读不到：**如实计数**，不悄悄丢掉，也不让整份底稿作废。
        unreadable++;
        unmapped++;
        continue;
      }
      var country = FilingOrderTaxPort.shippingCountry(detail);
      if (country.isEmpty()) {
        unmapped++;
        continue;
      }
      var mapped = countryToJurisdiction.get(country);
      if (mapped == null) {
        unmapped++;
        continue;
      }
      if (!mapped.equals(jurisdiction.code())) {
        outOfScope++;
        continue;
      }
      var header = detail.header();
      var currency = header.currency() == null ? "" : header.currency().trim().toUpperCase(Locale.ROOT);
      if (currency.isEmpty()) {
        unmapped++;
        continue;
      }
      var bucket = buckets.computeIfAbsent(currency, c -> new CurrencyBucket());
      var subtotal = nz(header.subtotalAmount());
      var discount = nz(header.discountAmount());
      // 应税销售额 = 小计 − 折扣（**不含税净额**）；订单头折扣是非负口径。
      bucket.taxableSales = bucket.taxableSales.add(subtotal.subtract(discount));
      bucket.vatCollected = bucket.vatCollected.add(nz(header.taxAmount()));
      bucket.orderCount++;
      included++;
    }

    // ④ IOSS 订单清单（channel 16.3.2.1，同批开发中 ⇒ 读不到是常态之一）。
    var iossAvailable = false;
    var iossDetail = "";
    List<ChannelIossPort.IossOrderRow> iossRows = List.of();
    if (type == FilingTaxType.IOSS) {
      try {
        iossRows =
            channelIoss.orders(jurisdiction.code(), input.periodStart(), input.periodEnd(), bearer);
        iossAvailable = true;
        iossDetail =
            "IOSS 订单清单来自 channel 16.3.2.1（实时只读），本次读到 "
                + iossRows.size()
                + " 张。";
        for (var row : iossRows) {
          var currency =
              row.currency() == null ? "" : row.currency().trim().toUpperCase(Locale.ROOT);
          if (currency.isEmpty()) continue;
          var bucket = buckets.computeIfAbsent(currency, c -> new CurrencyBucket());
          bucket.iossOrderCount = (bucket.iossOrderCount == null ? 0 : bucket.iossOrderCount) + 1;
          bucket.iossCollected =
              (bucket.iossCollected == null ? BigDecimal.ZERO : bucket.iossCollected)
                  .add(nz(row.vatAmount()));
        }
      } catch (ResponseStatusException e) {
        iossAvailable = false;
        iossDetail =
            "🔴 读不到 IOSS 订单清单（channel 16.3.2.1）："
                + (e.getReason() == null ? "上游不可用" : e.getReason())
                + " ⇒ 本底稿的 IOSS 订单数与代收金额**留空**（不是 0），"
                + "请在 16.3.2.1 交付后重新取数。";
      }
    } else {
      iossDetail = "本底稿是 VAT 申报线，不涉及 IOSS 清单（IOSS 口径是另一条申报线）。";
    }

    // ⑤ 可抵扣进项（已过账凭证 + 科目属性）。取不到 ⇒ 如实报「无可抵扣进项数据」。
    var deductible = deductibleInputs(input.periodStart(), input.periodEnd());

    // ⑥ 期间实际到账（12.2.1.1 收款单，只读）。
    var received = receivedAmounts(input.periodStart(), input.periodEnd());

    // ⑦ 汇率快照（折算用）。取不到 ⇒ 不折算，**绝不按 1:1 兜底**。
    var exchange = new LinkedHashMap<String, ExchangeRatePort.RateSnapshot>();
    var exchangeFailures = new LinkedHashMap<String, String>();
    if (!baseCurrency.isEmpty()) {
      for (var currency : buckets.keySet()) {
        if (currency.equals(baseCurrency)) continue;
        try {
          exchange.put(
              currency,
              exchangeRates.latest(currency, baseCurrency, input.periodEnd(), bearer));
        } catch (ResponseStatusException e) {
          exchangeFailures.put(
              currency, e.getReason() == null ? "汇率不可得" : e.getReason());
        }
      }
    }

    // ⑧ 口径说明（**写进库里**，不只写在页面上）。
    var basis = buildBasis(
        type,
        jurisdiction.code(),
        jurisdiction.name(),
        input.periodStart(),
        input.periodEnd(),
        cycle,
        taxCode,
        resolution,
        orderSource,
        baseCurrency,
        included,
        outOfScope,
        unmapped,
        unreadable,
        truncated,
        iossAvailable,
        iossDetail,
        deductible.isEmpty());

    // ⑨ 落库。旧的生效底稿先置 SUPERSEDED（部分唯一索引保证同时只有一份生效底稿）。
    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_filing_draft set status = 'SUPERSEDED', updated_at = ?::timestamptz"
            + " where tenant_id = ? and status = 'GENERATED' and jurisdiction_code = ?"
            + " and tax_type = ? and period_start = ? and period_end = ? and filing_cycle = ?",
        now,
        tenant,
        jurisdiction.code(),
        type.name(),
        input.periodStart(),
        input.periodEnd(),
        cycle.name());

    var draftId = UUID.randomUUID();
    sql.execute(
        "insert into finance_filing_draft(id,tenant_id,jurisdiction_code,jurisdiction_name,"
            + " country_code,tax_type,period_start,period_end,filing_cycle,base_currency,"
            + " order_source,status,order_count,included_count,out_of_scope_count,unmapped_count,"
            + " order_truncated,generation_basis,generated_at,generated_by,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,'GENERATED',?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
        draftId,
        tenant,
        jurisdiction.code(),
        jurisdiction.name(),
        jurisdiction.countryCode(),
        type.name(),
        input.periodStart(),
        input.periodEnd(),
        cycle.name(),
        baseCurrency,
        orderSource,
        orderRows.size(),
        included,
        outOfScope,
        unmapped,
        truncated,
        basis,
        now,
        actor == null ? null : actor.userId(),
        input.note() == null ? "" : input.note().trim(),
        now,
        now);

    var taxRateRange = effectiveRange(resolution.taxRateId());
    for (var entry : buckets.entrySet()) {
      var currency = entry.getKey();
      var bucket = entry.getValue();
      var deductibleAmount = deductible.get(currency);
      var deductibleAvailable = deductibleAmount != null;
      BigDecimal netPayable = null;
      if (deductibleAvailable) {
        netPayable = bucket.vatCollected.subtract(deductibleAmount);
      }
      var rate = exchange.get(currency);
      var rateFailed = exchangeFailures.get(currency);
      BigDecimal exchangeRate = null;
      BigDecimal converted = null;
      boolean exchangeAvailable = false;
      String exchangeSource = "";
      LocalDate exchangeAsOf = null;
      if (!baseCurrency.isEmpty()) {
        if (currency.equals(baseCurrency)) {
          exchangeRate = BigDecimal.ONE;
          exchangeAvailable = true;
          exchangeSource = "同币种（1:1，不涉及折算）";
          exchangeAsOf = input.periodEnd();
          if (netPayable != null) converted = netPayable;
        } else if (rate != null) {
          exchangeRate = rate.rate();
          exchangeAvailable = true;
          exchangeSource = rate.display() == null ? "catalog 5.2.2.1" : rate.display();
          exchangeAsOf = rate.effectiveDate();
          if (netPayable != null) {
            converted =
                netPayable
                    .multiply(exchangeRate)
                    .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
          }
        } else {
          exchangeSource =
              "🔴 汇率不可得，未折算（"
                  + (rateFailed == null ? "上游未返回汇率" : rateFailed)
                  + "）—— **绝不按 1:1 兜底**";
        }
      } else {
        exchangeSource = "未指定折算目标币种（base_currency 为空）⇒ 按币种分组呈现，不折算。";
      }

      var deductibleNote =
          deductibleAvailable
              ? "可抵扣进项取自覆盖期间内**已过账**凭证中应交税费类科目的**借方**发生额。"
              : "🔴 无可抵扣进项数据：覆盖期间内没有已过账凭证的应交税费借方发生额"
                  + "（仓库里没有「进项发票」概念，本模块不建进项表来假装有数据）"
                  + " ⇒ 本行**不产出应缴**，而不是产出「应缴 = 已收 VAT」的假数。";

      sql.execute(
          "insert into finance_filing_draft_line(id,tenant_id,draft_id,currency,taxable_sales,"
              + " vat_collected,ioss_order_count,ioss_collected,deductible_input,"
              + " deductible_available,deductible_note,net_payable,tax_rate_id,tax_code,tax_rate,"
              + " tax_rate_effective_from,tax_rate_effective_to,exchange_rate,exchange_rate_as_of,"
              + " exchange_rate_source,exchange_rate_available,converted_net_payable,"
              + " received_amount,order_count,note,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          draftId,
          currency,
          bucket.taxableSales,
          bucket.vatCollected,
          bucket.iossOrderCount,
          bucket.iossCollected,
          deductibleAmount,
          deductibleAvailable,
          deductibleNote,
          netPayable,
          resolution.taxRateId(),
          taxCode,
          resolution.rate(),
          taxRateRange.effectiveFrom(),
          taxRateRange.effectiveTo(),
          exchangeRate,
          exchangeAsOf,
          exchangeSource,
          exchangeAvailable,
          converted,
          received.get(currency),
          bucket.orderCount,
          "",
          now);
    }

    return detail(draftId);
  }

  // ================================================================ 底稿详情 / 汇总

  /** 底稿详情（头 + 汇总行 + 数据来源可用性 + 口径）。 */
  public FinanceFilingDraftDetail detail(UUID id) {
    var draft = draftRow(id);
    var lines = lines(id);
    return new FinanceFilingDraftDetail(
        draft, lines, sources(lines), CURRENCY_NOTE, PRODUCT_BOUNDARY);
  }

  /** 汇总报表（**与底稿行是同一份数据**的另一种呈现，不建第二张表）。 */
  public FinanceFilingSummary summary(UUID id) {
    var draft = draftRow(id);
    var lines = lines(id);
    return new FinanceFilingSummary(
        draft.id(),
        draft.jurisdictionCode(),
        draft.jurisdictionName(),
        draft.taxType(),
        draft.periodStart(),
        draft.periodEnd(),
        draft.filingCycle(),
        draft.baseCurrency(),
        draft.generatedAt(),
        lines,
        sources(lines),
        CURRENCY_NOTE,
        PRODUCT_BOUNDARY);
  }

  // ================================================================ 导出

  /** 一次导出的结果：真文件内容 + 留痕事实。 */
  public record ExportResult(
      UUID exportId,
      UUID draftId,
      String format,
      String fileName,
      String contentType,
      byte[] content,
      int rowCount,
      String contentHash,
      int byteSize) {}

  /**
   * 导出底稿（**真文件**：CSV / JSON 的真实字节，不是占位文件）。
   *
   * <p>🔴 导出是**取证动作** ⇒ 每次导出都写一条留痕（行数 / 字节数 / 内容 sha-256）。
   */
  public ExportResult export(UUID id, String rawFormat, AccessService.Access actor) {
    var format = ExportFormat.parse(rawFormat);
    if (format == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导出格式只能是 CSV 或 JSON");
    }
    var draft = draftRow(id);
    var lines = lines(id);

    var content =
        format == ExportFormat.CSV
            ? csv(draft, lines).getBytes(StandardCharsets.UTF_8)
            : json(draft, lines).getBytes(StandardCharsets.UTF_8);
    var text = new String(content, StandardCharsets.UTF_8);
    var rowCount =
        format == ExportFormat.CSV
            ? Math.max(lines.size(), 0)
            : Math.max(lines.size(), 0);
    var hash = sha256(content);
    var fileName =
        "filing-draft-"
            + draft.jurisdictionCode()
            + "-"
            + draft.taxType()
            + "-"
            + draft.periodStart()
            + "-"
            + draft.periodEnd()
            + "."
            + format.name().toLowerCase(Locale.ROOT);

    var exportId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_filing_draft_export(id,tenant_id,draft_id,format,file_name,content_type,"
            + " row_count,byte_size,content_hash,exported_at,exported_by,note,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        exportId,
        currentTenant(),
        id,
        format.name(),
        fileName,
        format.contentType(),
        rowCount,
        content.length,
        hash,
        now,
        actor == null ? null : actor.userId(),
        "",
        now);
    return new ExportResult(
        exportId,
        id,
        format.name(),
        fileName,
        format.contentType(),
        content,
        rowCount,
        hash,
        content.length);
  }

  /** 导出留痕列表（真分页 + 真筛选）。 */
  public FinanceFilingExportPage exports(UUID draftId, String format, int page, int size) {
    var where = new StringBuilder(" where e.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (draftId != null) {
      where.append(" and e.draft_id = ?");
      binds.add(draftId);
    }
    if (format != null && !format.isBlank()) {
      var parsed = ExportFormat.parse(format);
      if (parsed == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导出格式只能是 CSV 或 JSON");
      }
      where.append(" and e.format = ?");
      binds.add(parsed.name());
    }
    var total =
        sql.fetchOne("select count(*) from finance_filing_draft_export e" + where, binds.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select e.id,e.draft_id,e.format,e.file_name,e.content_type,e.row_count,e.byte_size,"
                + " e.content_hash,e.exported_at,e.exported_by,e.note,"
                + " d.jurisdiction_code,d.tax_type,d.period_start,d.period_end"
                + " from finance_filing_draft_export e"
                + " join finance_filing_draft d on d.tenant_id = e.tenant_id and d.id = e.draft_id"
                + where
                + " order by e.exported_at desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceFilingExportRow>();
    for (var row : rows) {
      items.add(
          new FinanceFilingExportRow(
              row.get("id", UUID.class),
              row.get("draft_id", UUID.class),
              row.get("jurisdiction_code", String.class),
              row.get("tax_type", String.class),
              row.get("period_start", LocalDate.class),
              row.get("period_end", LocalDate.class),
              row.get("format", String.class),
              row.get("file_name", String.class),
              row.get("content_type", String.class),
              row.get("row_count", Integer.class),
              row.get("byte_size", Integer.class),
              row.get("content_hash", String.class),
              row.get("exported_at", OffsetDateTime.class),
              row.get("exported_by", UUID.class),
              row.get("note", String.class)));
    }
    return new FinanceFilingExportPage(items, Math.max(page, 0), limit, total);
  }

  // ================================================================ 内部工具

  /** 辖区码必须真实落在上游受控码表里，且**不是**美国辖区。 */
  private FinanceTaxJurisdictionOption requireJurisdiction(String bearer, String code) {
    for (var j : channelTax.jurisdictions(bearer)) {
      if (j.code() == null || !j.code().equalsIgnoreCase(code)) continue;
      if (CHANNEL_OWNED_COUNTRY.equalsIgnoreCase(
          j.countryCode() == null ? "" : j.countryCode().trim())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "美国辖区（联邦与 50 州）的销售税归 channel 16.3.2.3 维护（sales_tax_rate_rule），"
                + "本模块不重复建表、也不出美国辖区的申报底稿 —— 请在 channel 侧处理。");
      }
      return new FinanceTaxJurisdictionOption(
          j.code(),
          j.name(),
          j.region(),
          j.countryCode(),
          j.subdivisionCode(),
          j.taxRegime(),
          j.vatRequired(),
          j.requiresEpr(),
          j.defaultFilingCycle(),
          j.derivationScope(),
          true,
          "");
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "辖区码 " + code + " 不在上游受控辖区码表里（channel 16.3.1.1）—— 辖区码不是自由文本");
  }

  /**
   * 命中那一版税率的<b>生效区间</b>（同库同服务，直接读 12.6.1.1 的表）。
   *
   * <p>底稿行上要快照「当时那一版的生效区间」⇒ 底稿能自证「这个数是按哪一版税率算的」。
   * 12.6.1.1 的传输模型里没有这两个字段（那是**别的模块**的模型，本模块不改它），
   * 所以这里按 id 直接查表取。
   */
  private EffectiveRange effectiveRange(UUID taxRateId) {
    if (taxRateId == null) return new EffectiveRange(null, null);
    var row =
        sql.fetchOne(
            "select effective_from, effective_to from finance_tax_rate where tenant_id = ? and id = ?",
            currentTenant(),
            taxRateId);
    if (row == null) return new EffectiveRange(null, null);
    return new EffectiveRange(
        row.get("effective_from", OffsetDateTime.class), row.get("effective_to", OffsetDateTime.class));
  }

  /** 税率版本的生效区间。 */
  private record EffectiveRange(
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo) {}

  /** 折算目标币种：空 = 不折算；给了必须是 ISO 4217 alpha-3。 */
  private String normalizeBaseCurrency(String raw) {
    if (raw == null || raw.isBlank()) return "";
    var value = raw.trim().toUpperCase(Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折算目标币种必须是三位大写字母（ISO 4217）");
    }
    return value;
  }

  /**
   * 可抵扣进项：覆盖期间内**已过账**凭证中应交税费类科目的**借方**发生额，按币种分组。
   *
   * <p>🔴 返回空 Map ⇒ **无可抵扣进项数据**（服务层如实报出来，**不退化成 0**）。
   */
  private Map<String, BigDecimal> deductibleInputs(LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            "select l.currency as currency, sum(l.amount) as amount"
                + " from finance_voucher v"
                + " join finance_voucher_line l on l.tenant_id = v.tenant_id and l.voucher_id = v.id"
                + " join finance_account_subject s on s.tenant_id = l.tenant_id"
                + "   and s.subject_code = l.subject_code"
                + " where v.tenant_id = ? and v.status = 'POSTED'"
                + " and v.voucher_date >= ? and v.voucher_date <= ?"
                + " and l.line_type = 'DEBIT'"
                + " and s.category = 'LIABILITY'"
                + " and s.tax_treatment in ('TAXABLE','REVERSE_CHARGE')"
                + " group by l.currency order by l.currency",
            currentTenant(),
            from,
            to);
    var result = new LinkedHashMap<String, BigDecimal>();
    for (var row : rows) {
      var currency = row.get("currency", String.class);
      var amount = row.get("amount", BigDecimal.class);
      if (currency == null || amount == null) continue;
      result.put(currency.trim().toUpperCase(Locale.ROOT), amount);
    }
    return result;
  }

  /** 期间实际到账（12.2.1.1 收款单，只读）：按币种分组。 */
  private Map<String, BigDecimal> receivedAmounts(LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            "select currency, sum(amount) as amount from finance_receipt"
                + " where tenant_id = ? and status = 'RECORDED'"
                + " and received_at >= ?::timestamptz and received_at <= ?::timestamptz"
                + " group by currency order by currency",
            currentTenant(),
            from.atStartOfDay().atOffset(ZoneOffset.UTC),
            to.atTime(23, 59, 59, 999_000_000).atOffset(ZoneOffset.UTC));
    var result = new LinkedHashMap<String, BigDecimal>();
    for (var row : rows) {
      var currency = row.get("currency", String.class);
      var amount = row.get("amount", BigDecimal.class);
      if (currency == null || amount == null) continue;
      result.put(currency.trim().toUpperCase(Locale.ROOT), amount);
    }
    return result;
  }

  /** 口径说明（写进库里的可核信息）。 */
  private String buildBasis(
      FilingTaxType type,
      String jurisdictionCode,
      String jurisdictionName,
      LocalDate periodStart,
      LocalDate periodEnd,
      FilingCycle cycle,
      String taxCode,
      FinanceTaxResolution resolution,
      String orderSource,
      String baseCurrency,
      int included,
      int outOfScope,
      int unmapped,
      int unreadable,
      boolean truncated,
      boolean iossAvailable,
      String iossDetail,
      boolean noDeductible) {
    var basis = new StringBuilder();
    basis
        .append("申报线=")
        .append(type.label())
        .append("；辖区=")
        .append(jurisdictionCode)
        .append(" ")
        .append(jurisdictionName)
        .append("；覆盖期间=")
        .append(periodStart)
        .append("~")
        .append(periodEnd)
        .append("（")
        .append(cycle.label())
        .append("）；")
        .append("应税销售额=订单（小计−折扣）之和，**不含税**；已收 VAT=订单税额之和；")
        .append("税率取 12.6.1.1「按生效时间取税率」在 ")
        .append(resolution.asOf())
        .append(" 命中的那一版（税率 id=")
        .append(resolution.taxRateId())
        .append("，税码=")
        .append(resolution.taxCode())
        .append("，税率=")
        .append(strip(resolution.rate()))
        .append("）—— 命中不到会直接报错，不退回最新一条、不按 0% 处理；")
        .append("订单来源过滤=")
        .append(orderSource.isEmpty() ? "全部来源" : orderSource)
        .append("；")
        .append("折算目标币种=")
        .append(baseCurrency.isEmpty() ? "（未指定 ⇒ 不折算，按币种分组）" : baseCurrency)
        .append("；")
        .append("订单覆盖：本辖区纳入 ")
        .append(included)
        .append(" 张，归属到其它辖区 ")
        .append(outOfScope)
        .append(" 张，归属不到国家一级辖区 ")
        .append(unmapped)
        .append(" 张")
        .append(unreadable > 0 ? "（其中 " + unreadable + " 张的订单明细读不到）" : "")
        .append(truncated ? "；🔴 **本次取数被上限截断，底稿不是该期间的全部销售**" : "")
        .append("；")
        .append(iossDetail);
    if (noDeductible) {
      basis.append(
          "；🔴 无可抵扣进项数据（覆盖期间内没有已过账凭证的应交税费借方发生额）"
              + " ⇒ 本底稿**不产出应缴**");
    }
    basis.append("。🔴 本底稿只提供申报数据，**不替代税务申报动作**。");
    return basis.toString();
  }

  /** 数据来源可用性（界面据此显示「读不到」而不是「没有数据」）。 */
  private List<FinanceFilingSource> sources(List<FinanceFilingDraftLine> lines) {
    var sources = new ArrayList<FinanceFilingSource>();
    sources.add(
        new FinanceFilingSource(
            "TAX_RATE",
            "12.6.1.1",
            "按生效时间取税率",
            true,
            "finance（本地）",
            "税率来自 12.6.1.1 的生效区间查询，底稿行上快照的是当时命中的那一版。"));
    sources.add(
        new FinanceFilingSource(
            "JURISDICTION",
            "16.3.1.1",
            "受控辖区码表",
            true,
            "channel",
            "辖区码实时读 channel 的受控码表，落库前校验 ⇒ 辖区码不是自由文本。"));
    sources.add(
        new FinanceFilingSource(
            "ORDER_TAX",
            "9",
            "订单税务数据",
            true,
            "trade",
            "订单经授权只读 API 读取；收货国决定辖区归属。"));
    var iossLine = lines.stream().filter(l -> l.iossOrderCount() != null).findFirst().orElse(null);
    sources.add(
        new FinanceFilingSource(
            "IOSS_ORDER_LIST",
            "16.3.2.1",
            "IOSS 订单清单与代收金额",
            iossLine != null,
            "channel",
            iossLine != null
                ? "本次读到了 IOSS 订单清单。"
                : "🔴 读不到 IOSS 订单清单（channel 16.3.2.1 与本模块同批开发中）"
                    + " ⇒ IOSS 两列留空（不是 0）。"));
    var deductibleAvailable = lines.stream().anyMatch(FinanceFilingDraftLine::deductibleAvailable);
    sources.add(
        new FinanceFilingSource(
            "DEDUCTIBLE_INPUT",
            "12.5.2.1",
            "可抵扣进项（已过账凭证 + 科目属性）",
            deductibleAvailable,
            "finance（本地）",
            deductibleAvailable
                ? "覆盖期间内已过账凭证的应交税费借方发生额。"
                : "🔴 无可抵扣进项数据（仓库里没有「进项发票」概念，本模块不建进项表来假装有数据）。"));
    var exchangeAvailable = lines.stream().anyMatch(FinanceFilingDraftLine::exchangeRateAvailable);
    sources.add(
        new FinanceFilingSource(
            "EXCHANGE_RATE",
            "5.2.2.1",
            "汇率快照",
            exchangeAvailable,
            "catalog",
            exchangeAvailable
                ? "汇率快照来自 catalog 5.2.2.1（带 asOf）。"
                : "🔴 汇率不可得 ⇒ 未折算（**绝不按 1:1 兜底**）。"));
    var receivedAvailable =
        lines.stream().anyMatch(l -> l.receivedAmount() != null);
    sources.add(
        new FinanceFilingSource(
            "RECEIVED_AMOUNT",
            "12.2.1.1",
            "期间实际到账（收款单）",
            receivedAvailable,
            "finance（本地）",
            receivedAvailable
                ? "覆盖期间内已登记收款单按币种分组的到账金额（与「已收 VAT」对照用）。"
                : "覆盖期间内没有已登记的收款单 ⇒ 没有可对照的到账金额。"));
    return sources;
  }

  /** 一行 → 底稿头。 */
  private FinanceFilingDraftRow draftRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,jurisdiction_code,jurisdiction_name,country_code,tax_type,period_start,"
                + " period_end,filing_cycle,base_currency,order_source,status,order_count,"
                + " included_count,out_of_scope_count,unmapped_count,order_truncated,"
                + " generation_basis,generated_at,generated_by,note,created_at"
                + " from finance_filing_draft where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申报底稿不存在或不属于本租户");
    }
    return draftRow(row);
  }

  /** 一行 → 底稿头。 */
  private FinanceFilingDraftRow draftRow(org.jooq.Record row) {
    var type = FilingTaxType.parse(row.get("tax_type", String.class));
    var cycle = FilingCycle.parse(row.get("filing_cycle", String.class));
    var status = DraftStatus.parse(row.get("status", String.class));
    return new FinanceFilingDraftRow(
        row.get("id", UUID.class),
        row.get("jurisdiction_code", String.class),
        row.get("jurisdiction_name", String.class),
        row.get("country_code", String.class),
        row.get("tax_type", String.class),
        type == null ? row.get("tax_type", String.class) : type.label(),
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("filing_cycle", String.class),
        cycle == null ? row.get("filing_cycle", String.class) : cycle.label(),
        row.get("base_currency", String.class),
        row.get("order_source", String.class),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("order_count", Integer.class),
        row.get("included_count", Integer.class),
        row.get("out_of_scope_count", Integer.class),
        row.get("unmapped_count", Integer.class),
        row.get("order_truncated", Boolean.class),
        row.get("generation_basis", String.class),
        row.get("generated_at", OffsetDateTime.class),
        row.get("generated_by", UUID.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 底稿的汇总行（按币种）。 */
  private List<FinanceFilingDraftLine> lines(UUID draftId) {
    var rows =
        sql.fetch(
            "select id,draft_id,currency,taxable_sales,vat_collected,ioss_order_count,"
                + " ioss_collected,deductible_input,deductible_available,deductible_note,"
                + " net_payable,tax_rate_id,tax_code,tax_rate,tax_rate_effective_from,"
                + " tax_rate_effective_to,exchange_rate,exchange_rate_as_of,exchange_rate_source,"
                + " exchange_rate_available,converted_net_payable,received_amount,order_count,note"
                + " from finance_filing_draft_line where tenant_id = ? and draft_id = ?"
                + " order by currency",
            currentTenant(),
            draftId);
    var items = new ArrayList<FinanceFilingDraftLine>();
    for (var row : rows) {
      items.add(
          new FinanceFilingDraftLine(
              row.get("id", UUID.class),
              row.get("draft_id", UUID.class),
              row.get("currency", String.class),
              row.get("taxable_sales", BigDecimal.class),
              row.get("vat_collected", BigDecimal.class),
              row.get("ioss_order_count", Integer.class),
              row.get("ioss_collected", BigDecimal.class),
              row.get("deductible_input", BigDecimal.class),
              row.get("deductible_available", Boolean.class),
              row.get("deductible_note", String.class),
              row.get("net_payable", BigDecimal.class),
              row.get("tax_rate_id", UUID.class),
              row.get("tax_code", String.class),
              row.get("tax_rate", BigDecimal.class),
              row.get("tax_rate_effective_from", OffsetDateTime.class),
              row.get("tax_rate_effective_to", OffsetDateTime.class),
              row.get("exchange_rate", BigDecimal.class),
              row.get("exchange_rate_as_of", LocalDate.class),
              row.get("exchange_rate_source", String.class),
              row.get("exchange_rate_available", Boolean.class),
              row.get("converted_net_payable", BigDecimal.class),
              row.get("received_amount", BigDecimal.class),
              row.get("order_count", Integer.class),
              row.get("note", String.class)));
    }
    return items;
  }

  /** CSV 导出（**元数据以 `#` 注释行开头**，随后是表头行与数据行）。 */
  private String csv(FinanceFilingDraftRow draft, List<FinanceFilingDraftLine> lines) {
    var out = new StringBuilder();
    out.append("# 12.6.1.2 VAT/IOSS 申报底稿（本系统提供底稿，不替代税务申报动作）\n");
    out.append("# jurisdiction_code=")
        .append(draft.jurisdictionCode())
        .append(", jurisdiction_name=")
        .append(draft.jurisdictionName())
        .append(", tax_type=")
        .append(draft.taxType())
        .append(", period_start=")
        .append(draft.periodStart())
        .append(", period_end=")
        .append(draft.periodEnd())
        .append(", filing_cycle=")
        .append(draft.filingCycle())
        .append(", base_currency=")
        .append(draft.baseCurrency() == null ? "" : draft.baseCurrency())
        .append("\n");
    out.append("# generated_at=")
        .append(draft.generatedAt())
        .append(", generation_basis=")
        .append(draft.generationBasis())
        .append("\n");
    out.append(
        "jurisdiction_code,jurisdiction_name,tax_type,period_start,period_end,filing_cycle,currency,"
            + "taxable_sales,vat_collected,ioss_order_count,ioss_collected,deductible_input,"
            + "net_payable,tax_code,tax_rate,exchange_rate,exchange_rate_as_of,"
            + "converted_net_payable,received_amount,order_count\n");
    for (var line : lines) {
      out
          .append(csvCell(draft.jurisdictionCode()))
          .append(",")
          .append(csvCell(draft.jurisdictionName()))
          .append(",")
          .append(csvCell(draft.taxType()))
          .append(",")
          .append(csvCell(draft.periodStart().toString()))
          .append(",")
          .append(csvCell(draft.periodEnd().toString()))
          .append(",")
          .append(csvCell(draft.filingCycle()))
          .append(",")
          .append(csvCell(line.currency()))
          .append(",")
          .append(csvCell(strip(line.taxableSales())))
          .append(",")
          .append(csvCell(strip(line.vatCollected())))
          .append(",")
          .append(csvCell(line.iossOrderCount() == null ? "" : String.valueOf(line.iossOrderCount())))
          .append(",")
          .append(csvCell(strip(line.iossCollected())))
          .append(",")
          .append(csvCell(strip(line.deductibleInput())))
          .append(",")
          .append(csvCell(strip(line.netPayable())))
          .append(",")
          .append(csvCell(line.taxCode()))
          .append(",")
          .append(csvCell(strip(line.taxRate())))
          .append(",")
          .append(csvCell(strip(line.exchangeRate())))
          .append(",")
          .append(csvCell(line.exchangeRateAsOf() == null ? "" : line.exchangeRateAsOf().toString()))
          .append(",")
          .append(csvCell(strip(line.convertedNetPayable())))
          .append(",")
          .append(csvCell(strip(line.receivedAmount())))
          .append(",")
          .append(csvCell(String.valueOf(line.orderCount())))
          .append("\n");
    }
    return out.toString();
  }

  /** JSON 导出（元数据 + 汇总行 + 数据来源可用性）。 */
  private String json(FinanceFilingDraftRow draft, List<FinanceFilingDraftLine> lines) {
    var out = new StringBuilder();
    out.append("{\n");
    out.append("  \"module\": \"12.6.1.2\",\n");
    out.append("  \"name\": \"VAT/IOSS 申报底稿\",\n");
    out.append("  \"productBoundary\": \"")
        .append(escapeJson(PRODUCT_BOUNDARY))
        .append("\",\n");
    out.append("  \"draft\": {\n");
    out.append("    \"id\": \"").append(draft.id()).append("\",\n");
    out.append("    \"jurisdictionCode\": \"").append(escapeJson(draft.jurisdictionCode())).append("\",\n");
    out.append("    \"jurisdictionName\": \"").append(escapeJson(draft.jurisdictionName())).append("\",\n");
    out.append("    \"countryCode\": \"").append(escapeJson(draft.countryCode())).append("\",\n");
    out.append("    \"taxType\": \"").append(escapeJson(draft.taxType())).append("\",\n");
    out.append("    \"periodStart\": \"").append(draft.periodStart()).append("\",\n");
    out.append("    \"periodEnd\": \"").append(draft.periodEnd()).append("\",\n");
    out.append("    \"filingCycle\": \"").append(escapeJson(draft.filingCycle())).append("\",\n");
    out.append("    \"baseCurrency\": \"").append(escapeJson(draft.baseCurrency() == null ? "" : draft.baseCurrency())).append("\",\n");
    out.append("    \"status\": \"").append(escapeJson(draft.status())).append("\",\n");
    out.append("    \"generationBasis\": \"").append(escapeJson(draft.generationBasis())).append("\",\n");
    out.append("    \"generatedAt\": \"").append(draft.generatedAt()).append("\",\n");
    out.append("    \"orderCount\": ").append(draft.orderCount()).append(",\n");
    out.append("    \"includedCount\": ").append(draft.includedCount()).append(",\n");
    out.append("    \"outOfScopeCount\": ").append(draft.outOfScopeCount()).append(",\n");
    out.append("    \"unmappedCount\": ").append(draft.unmappedCount()).append(",\n");
    out.append("    \"orderTruncated\": ").append(draft.orderTruncated()).append("\n");
    out.append("  },\n");
    out.append("  \"lines\": [\n");
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      out.append("    {\n");
      out.append("      \"currency\": \"").append(escapeJson(line.currency())).append("\",\n");
      out.append("      \"taxableSales\": ").append(nullable(line.taxableSales())).append(",\n");
      out.append("      \"vatCollected\": ").append(nullable(line.vatCollected())).append(",\n");
      out.append("      \"iossOrderCount\": ").append(nullable(line.iossOrderCount())).append(",\n");
      out.append("      \"iossCollected\": ").append(nullable(line.iossCollected())).append(",\n");
      out.append("      \"deductibleInput\": ").append(nullable(line.deductibleInput())).append(",\n");
      out.append("      \"deductibleAvailable\": ").append(line.deductibleAvailable()).append(",\n");
      out.append("      \"netPayable\": ").append(nullable(line.netPayable())).append(",\n");
      out.append("      \"taxCode\": \"").append(escapeJson(line.taxCode())).append("\",\n");
      out.append("      \"taxRate\": ").append(nullable(line.taxRate())).append(",\n");
      out.append("      \"taxRateId\": \"").append(line.taxRateId() == null ? "" : line.taxRateId()).append("\",\n");
      out.append("      \"exchangeRate\": ").append(nullable(line.exchangeRate())).append(",\n");
      out.append("      \"exchangeRateAsOf\": \"")
          .append(line.exchangeRateAsOf() == null ? "" : line.exchangeRateAsOf())
          .append("\",\n");
      out.append("      \"exchangeRateAvailable\": ").append(line.exchangeRateAvailable()).append(",\n");
      out.append("      \"convertedNetPayable\": ").append(nullable(line.convertedNetPayable())).append(",\n");
      out.append("      \"receivedAmount\": ").append(nullable(line.receivedAmount())).append(",\n");
      out.append("      \"orderCount\": ").append(line.orderCount()).append("\n");
      out.append("    }").append(i + 1 < lines.size() ? "," : "").append("\n");
    }
    out.append("  ]\n");
    out.append("}\n");
    return out.toString();
  }

  /** CSV 单元格转义（含逗号 / 引号 / 换行时加引号）。 */
  private static String csvCell(String value) {
    if (value == null || value.isEmpty()) return "";
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  /** JSON 字符串转义。 */
  private static String escapeJson(String value) {
    if (value == null) return "";
    var out = new StringBuilder();
    for (var ch : value.toCharArray()) {
      switch (ch) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (ch < 0x20) {
            out.append(String.format("\\u%04x", (int) ch));
          } else {
            out.append(ch);
          }
        }
      }
    }
    return out.toString();
  }

  /** 金额的 JSON 呈现：null 就是 null（**不写 0**），数值去掉无意义的尾零。 */
  private static String nullable(BigDecimal value) {
    return value == null ? "null" : strip(value);
  }

  private static String nullable(Integer value) {
    return value == null ? "null" : String.valueOf(value);
  }

  /** 去掉无意义的尾零（numeric 读出来是 0.190000 / 12.0000，展示要用人读的那个数）。 */
  private static String strip(BigDecimal value) {
    if (value == null) return "";
    return value.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 内容 sha-256（十六进制）—— 导出留痕的内容指纹。 */
  private static String sha256(byte[] content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var bytes = digest.digest(content);
      var out = new StringBuilder(bytes.length * 2);
      for (var b : bytes) {
        out.append(Character.forDigit((b >> 4) & 0xf, 16));
        out.append(Character.forDigit(b & 0xf, 16));
      }
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 必须有 SHA-256", e);
    }
  }

  /** 当前租户（由 backend/libraries/tenancy 在事务里绑定的 app.current_tenant）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：申报底稿必须在绑定租户的事务里执行");
    }
    return raw;
  }

  /** 多币种说明（界面上照着显示，别让人以为可以跨币种求和）。 */
  static final String CURRENCY_NOTE =
      "🔴 金额**按币种分组**呈现：不同币种的金额**绝不相加**（USD 与 EUR 加成一个数"
          + "不是任何一个金额）。折算（converted_net_payable）只是**附加**信息，"
          + "只在汇率真的取到时才有值 —— 汇率不可得时不折算，**绝不按 1:1 兜底**。";

  /** 产品边界（界面上照着显示）。 */
  static final String PRODUCT_BOUNDARY =
      "🔴 本系统提供**申报底稿**，不替代税务申报动作："
          + "本模块产出底稿 + 汇总报表 + 可导出文件（CSV / JSON），"
          + "**不向税务机构提交申报**，也不产生任何「已申报 / 已受理」的结论。"
          + "申报主体与责任在用户；系统保证的是**数据完整可核**。";
}
