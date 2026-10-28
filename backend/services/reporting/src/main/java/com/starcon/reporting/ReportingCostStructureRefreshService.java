package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.2.1 成本结构的**投影摄取**（蓝图「输入」句：成本分摊结果；历史基线）。
 *
 * <p>本服务把**上游算好的**成本事实拉成本服务的投影表
 * （{@code reporting_cost_structure_fact}），并按蓝图职责句的**七类**归桶。
 *
 * <p>⚠️ **本服务不重算任何口径**：费率在 catalog、归集在 finance、分摊在 catalog / finance，
 * 本服务只做**搬运与归桶**。重算就会出现第二个真相 —— 上游规则一改，本模块的结构就变成假的。
 *
 * <p>⚠️ **跨服务调用一律走只读端口并转发调用方自己的 bearer**：不传租户号，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **幂等**：写入走 {@code on conflict ... do update}（幂等键 = 租户 + 类别 + 维度 +
 * 期间 + 币种 + 来源服务 + 上游行标识），同一批上游事实重放**不会翻倍**。
 */
@Service
public class ReportingCostStructureRefreshService {

  /** 一路上游最多翻多少页（没有上限时上游一慢就把请求线程全占住）。 */
  static final int MAX_PAGES = 25;

  /** 可刷新的上游源（= 七类成本）。 */
  static final List<String> SOURCES =
      List.of("PURCHASE", "HEAD_MILE", "PLATFORM_FEE", "LOGISTICS_FEE", "AD_FEE", "RETURN", "OTHER");

  /** 「其它」这一类成本对应的 finance 费用项（订阅 / 人员 / 办公）。 */
  static final List<String> OTHER_FEE_CATEGORIES =
      List.of("SUBSCRIPTION_FEE", "PERSONNEL_COST", "OFFICE_COST");

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingCostStructurePort costStructurePort;
  private final ReportingFinancePort finance;
  private final ReportingExpensePort expense;
  private final ReportingSettlementPort settlement;
  private final ReportingTradePort trade;

  public ReportingCostStructureRefreshService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingCostStructurePort costStructurePort,
      ReportingFinancePort finance,
      ReportingExpensePort expense,
      ReportingSettlementPort settlement,
      ReportingTradePort trade) {
    this.sql = sql;
    this.tenants = tenants;
    this.costStructurePort = costStructurePort;
    this.finance = finance;
    this.expense = expense;
    this.settlement = settlement;
    this.trade = trade;
  }

  // ==========================================================================
  // 入口
  // ==========================================================================

  /**
   * 刷新成本事实。
   *
   * @param sources 要刷的源（空 ⇒ 全部七类）
   */
  public List<ReportingCostStructureModels.CostStructureRefreshSummary> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, List<String> sources) {
    var wanted = resolveSources(sources);
    // ⚠️ 上游拉取**放在事务外**：HTTP 往返期间不该占着数据库连接。
    var categories = loadCategories(bearer);
    var summaries = new ArrayList<ReportingCostStructureModels.CostStructureRefreshSummary>();
    for (var source : wanted) {
      List<Fact> facts;
      String note;
      try {
        facts = pull(source, bearer, from, to, categories);
        note = "上游事实已拉取并归桶";
      } catch (RuntimeException e) {
        summaries.add(
            new ReportingCostStructureModels.CostStructureRefreshSummary(
                source, source, 0, "FAILED", "上游读不到：" + e.getMessage()));
        continue;
      }
      var written = writeAll(tenantId, facts);
      summaries.add(
          new ReportingCostStructureModels.CostStructureRefreshSummary(
              source, source, written, "OK", note + "（写入 " + written + " 条成本事实）"));
    }
    return summaries;
  }

  /** 要刷的源（空 ⇒ 全部；非法取值给一句能直接展示的话）。 */
  private List<String> resolveSources(List<String> sources) {
    if (sources == null || sources.isEmpty()) return SOURCES;
    var out = new ArrayList<String>();
    for (var raw : sources) {
      if (raw == null || raw.isBlank()) continue;
      var value = raw.trim().toUpperCase();
      if (!SOURCES.contains(value)) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "sources 只能是 " + String.join(" / ", SOURCES) + "，收到：" + raw);
      }
      out.add(value);
    }
    return out.isEmpty() ? SOURCES : out;
  }

  // ==========================================================================
  // 事实模型（搬运途中的一行）
  // ==========================================================================

  /** 一条待落库的成本事实。 */
  private record Fact(
      String costCategory,
      String costSubType,
      String platformCode,
      String siteCode,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String shopCode,
      String skuCode,
      String categoryCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  // ==========================================================================
  // 七路上游
  // ==========================================================================

  private List<Fact> pull(
      String source, String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    return switch (source) {
      case "PURCHASE" -> pullPurchase(bearer, from, to, categories);
      case "HEAD_MILE" -> pullHeadMile(bearer, from, to, categories);
      case "PLATFORM_FEE" -> pullPlatformFee(bearer, from, to, categories);
      case "LOGISTICS_FEE" -> pullLogisticsFee(bearer, from, to, categories);
      case "AD_FEE" -> pullAdFee(bearer, from, to, categories);
      case "RETURN" -> pullReturn(bearer, from, to, categories);
      case "OTHER" -> pullOther(bearer, from, to, categories);
      default -> List.of();
    };
  }

  /**
   * 采购成本 ← 12.4.1.2 成本计算条目（销售出库）。
   *
   * <p>⚠️ **含 {@code INCOMPLETE}（有缺口）的分录**：有缺口意味着成本层没覆盖全部出库量，
   * 已结转的那部分金额**仍然是真实发生的成本** —— 把它丢掉会低估采购成本。
   */
  private List<Fact> pullPurchase(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged = finance.costEntries("SALES_OUTBOUND", page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null) continue;
        var occurredOn = date(row.occurredAt());
        if (occurredOn == null || occurredOn.isBefore(from) || occurredOn.isAfter(to)) continue;
        var currency = currency(row.currency());
        var amount = row.costAmount();
        if (currency == null || amount == null) continue;
        var skuCode = trim(row.skuCode());
        facts.add(
            new Fact(
                "PURCHASE",
                "SALES_OUTBOUND",
                "",
                "",
                "",
                "",
                "",
                "",
                skuCode,
                categoryOf(categories, skuCode),
                occurredOn,
                occurredOn,
                currency,
                amount,
                row.issueQuantity(),
                "finance",
                "cost-entry:" + row.id()));
      }
      if (paged.items().size() < ReportingFinancePort.PAGE_SIZE) break;
    }
    return facts;
  }

  /**
   * 头程分摊 ← 5.2.1.3 成本分摊明细（逐 SKU 一行）。
   *
   * <p>⚠️ **只取「计算分摊额」**（{@code allocatedAmount}），**不含人工微调**
   * （{@code adjustmentAmount}）：微调是运营**已经**做过的修正，算进来会让结构失真。
   *
   * <p>⚠️ **期间 = 分摊的生效日**（上游不提供分摊期间终点）⇒ 只在**生效日落在窗口内**时计入，
   * 跨期分摊不会被重复计入，也不会向后平移。
   */
  private List<Fact> pullHeadMile(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged = expense.costAllocations(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.allocationId() == null) continue;
        var effectiveOn = row.effectiveFrom() == null ? null : row.effectiveFrom().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        if (effectiveOn != null && (effectiveOn.isBefore(from) || effectiveOn.isAfter(to))) continue;
        var detail = expense.costAllocation(row.allocationId(), bearer);
        if (detail == null || detail.currentRevision() == null || detail.lines() == null) continue;
        var currency = currency(detail.currentRevision().currency());
        if (currency == null) continue;
        var revisionNo = detail.currentRevision().revisionNo();
        for (var line : detail.lines()) {
          if (line == null || line.allocatedAmount() == null) continue;
          var skuCode = trim(line.skuCode());
          facts.add(
              new Fact(
                  "HEAD_MILE",
                  trim(detail.sourceType()) == null ? "" : detail.sourceType().trim().toUpperCase(),
                  "",
                  "",
                  "",
                  "",
                  "",
                  "",
                  skuCode,
                  categoryOf(categories, skuCode),
                  effectiveOn == null ? from : effectiveOn,
                  effectiveOn == null ? to : effectiveOn,
                  currency,
                  line.allocatedAmount(),
                  line.baseQuantity(),
                  "catalog",
                  "cost-allocation-line:"
                      + detail.allocationId()
                      + ":"
                      + revisionNo
                      + ":"
                      + skuCode));
        }
      }
      if (paged.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    return facts;
  }

  /** 平台费 ← 5.2.3.1 实际台账 + 12.3.1.2 结算佣金。 */
  private List<Fact> pullPlatformFee(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged =
          expense.platformFeeActuals(null, null, null, from, to, page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.isVoid()) continue;
        var occurredOn = ReportingExpensePort.date(row.occurredOn());
        var currency = currency(row.currency());
        var amount = ReportingExpensePort.decimal(row.amount());
        if (occurredOn == null || currency == null || amount == null) continue;
        facts.add(
            new Fact(
                "PLATFORM_FEE",
                trim(row.feeType()) == null ? "" : row.feeType().trim().toUpperCase(),
                trim(row.platformCode()) == null ? "" : row.platformCode().trim(),
                trim(row.siteCode()) == null ? "" : row.siteCode().trim(),
                "",
                "",
                "",
                "",
                "",
                "",
                occurredOn,
                occurredOn,
                currency,
                amount,
                ReportingExpensePort.decimal(row.quantity()),
                "catalog",
                "platform-fee-actual:" + row.id()));
      }
      if (paged.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    // 12.3.1.2 结算明细里的佣金（**费用在结算侧是负贡献 ⇒ 取负**才是费用）。
    facts.addAll(settlementFacts(bearer, from, to, "COMMISSION", "PLATFORM_FEE", categories));
    return facts;
  }

  /** 物流费 ← 5.2.3.2 实际运费台账 + 12.3.1.2 FBA 费用。 */
  private List<Fact> pullLogisticsFee(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged =
          expense.freightActuals(null, null, null, from, to, page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.isVoid()) continue;
        var occurredOn = ReportingExpensePort.date(row.occurredOn());
        var currency = currency(row.currency());
        var amount = ReportingExpensePort.decimal(row.amount());
        if (occurredOn == null || currency == null || amount == null) continue;
        facts.add(
            new Fact(
                "LOGISTICS_FEE",
                trim(row.chargeBasis()) == null ? "" : row.chargeBasis().trim().toUpperCase(),
                "",
                "",
                trim(row.channelCode()) == null ? "" : row.channelCode().trim(),
                trim(row.carrierCode()) == null ? "" : row.carrierCode().trim(),
                trim(row.shippingMethodCode()) == null ? "" : row.shippingMethodCode().trim(),
                "",
                "",
                "",
                occurredOn,
                occurredOn,
                currency,
                amount,
                ReportingExpensePort.decimal(row.quantity()),
                "catalog",
                "freight-actual:" + row.id()));
      }
      if (paged.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    facts.addAll(settlementFacts(bearer, from, to, "FBA_FEE", "LOGISTICS_FEE", categories));
    return facts;
  }

  /** 广告费 ← 12.3.1.2 广告费明细。 */
  private List<Fact> pullAdFee(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    return settlementFacts(bearer, from, to, "ADVERTISING", "AD_FEE", categories);
  }

  /**
   * 退货 ← 11.1.1.1 售后单。
   *
   * <p>⚠️ **分桶用 {@code receivedAt}（受理时刻），不是创建时刻**：退货成本发生在货退回并受理时。
   */
  private List<Fact> pullReturn(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged = trade.aftersalesCases(from, to, page, bearer);
      if (paged == null || paged.rows() == null || paged.rows().isEmpty()) break;
      for (var row : paged.rows()) {
        if (row == null) continue;
        var receivedOn = date(row.receivedAt());
        var currency = currency(row.currency());
        var amount = row.requestedAmount();
        if (receivedOn == null || currency == null || amount == null) continue;
        facts.add(
            new Fact(
                "RETURN",
                trim(row.caseType()) == null ? "" : row.caseType().trim().toUpperCase(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                receivedOn,
                receivedOn,
                currency,
                amount,
                row.requestedQuantity(),
                "trade",
                "aftersales:" + row.id()));
      }
      if (paged.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }
    return facts;
  }

  /** 其它 ← 12.4.2.1 费用归集条目（订阅 / 人员 / 办公）。 */
  private List<Fact> pullOther(
      String bearer, LocalDate from, LocalDate to, Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (var feeCategory : OTHER_FEE_CATEGORIES) {
      for (int page = 0; page < MAX_PAGES; page++) {
        var paged = costStructurePort.costCollectionEntries(feeCategory, from, to, page, bearer);
        if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
        for (var row : paged.items()) {
          if (row == null) continue;
          var currency = currency(row.currency());
          var amount = row.amount();
          if (currency == null || amount == null) continue;
          var periodStart = row.periodStart() == null ? row.baseDate() : row.periodStart();
          var periodEnd = row.periodEnd() == null ? periodStart : row.periodEnd();
          if (periodStart == null) continue;
          if (periodEnd.isBefore(from) || periodStart.isAfter(to)) continue;
          facts.add(
              new Fact(
                  "OTHER",
                  feeCategory,
                  "",
                  "",
                  "",
                  "",
                  "",
                  "",
                  "",
                  "",
                  periodStart,
                  periodEnd,
                  currency,
                  amount,
                  row.quantity(),
                  "finance",
                  "collection-entry:" + row.id()));
        }
        if (paged.items().size() < ReportingCostStructurePort.PAGE_SIZE) break;
      }
    }
    return facts;
  }

  // ==========================================================================
  // 共用取数
  // ==========================================================================

  /**
   * 12.3.1.2 结算明细里的某一类费用。
   *
   * <p>⚠️ **费用在结算侧是「负贡献」**（与收入冲抵）⇒ 取负才是费用金额。
   * 这一条与 13.4.2.2 的实际侧**逐字同口径**（同一个 negate）。
   */
  private List<Fact> settlementFacts(
      String bearer,
      LocalDate from,
      LocalDate to,
      String settlementCategory,
      String costCategory,
      Map<String, String> categories) {
    var facts = new ArrayList<Fact>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged = settlement.transactions(settlementCategory, from, to, page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null) continue;
        var occurredOn = row.occurredOn();
        var currency = currency(row.currency());
        var amount = row.amount();
        if (occurredOn == null || currency == null || amount == null) continue;
        facts.add(
            new Fact(
                costCategory,
                settlementCategory,
                trim(row.platformCode()) == null ? "" : row.platformCode().trim(),
                "",
                "",
                "",
                "",
                trim(row.shopCode()) == null ? "" : row.shopCode().trim(),
                "",
                "",
                occurredOn,
                occurredOn,
                currency,
                amount.negate(),
                row.quantity(),
                "finance",
                "settlement:" + row.id()));
      }
      if (paged.items().size() < ReportingSettlementPort.PAGE_SIZE) break;
    }
    return facts;
  }

  /**
   * SKU → 类目映射（上游成本事实里**没有**类目这一维，本模块自己补）。
   *
   * <p>⚠️ **映射不到就留空串**（不猜一个类目）。
   */
  private Map<String, String> loadCategories(String bearer) {
    var map = new HashMap<String, String>();
    for (int page = 0; page < MAX_PAGES; page++) {
      var paged = costStructurePort.skus(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null) continue;
        var code = trim(row.code());
        var category = trim(row.category());
        if (code == null || category == null) continue;
        map.putIfAbsent(code, category);
      }
      if (paged.items().size() < ReportingCostStructurePort.PAGE_SIZE) break;
    }
    return map;
  }

  /** 类目取值（映射不到 ⇒ 空串，**不猜**）。 */
  private static String categoryOf(Map<String, String> categories, String skuCode) {
    if (skuCode == null || skuCode.isBlank()) return "";
    var category = categories.get(skuCode);
    return category == null ? "" : category;
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /**
   * 落库（幂等 upsert）。
   *
   * <p>⚠️ **幂等键与 V399 的 identity 唯一索引逐字一致** —— 写错列 ⇒ upsert 退化成插入 ⇒
   * 同一批上游事实重放会**翻倍**。
   */
  private int writeAll(UUID tenantId, List<Fact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_cost_structure_fact
                  (id,tenant_id,cost_category,cost_sub_type,
                   platform_code,site_code,channel_code,carrier_code,shipping_method_code,
                   shop_code,sku_code,category_code,
                   period_start,period_end,currency,amount,quantity,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                on conflict (tenant_id,cost_category,cost_sub_type,
                             platform_code,site_code,channel_code,carrier_code,shipping_method_code,
                             shop_code,sku_code,category_code,
                             period_start,period_end,currency,source_service,source_reference)
                do update set amount = excluded.amount,
                              quantity = excluded.quantity,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.costCategory(),
                fact.costSubType(),
                fact.platformCode(),
                fact.siteCode(),
                fact.channelCode(),
                fact.carrierCode(),
                fact.shippingMethodCode(),
                fact.shopCode(),
                fact.skuCode(),
                fact.categoryCode(),
                fact.periodStart(),
                fact.periodEnd(),
                fact.currency(),
                fact.amount(),
                fact.quantity() == null ? BigDecimal.ZERO : fact.quantity(),
                fact.sourceService(),
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 币种规范化（空 / 非 ISO alpha-3 ⇒ null，即「这一行不算」）。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  /** 上游时间 → 日期（按 UTC 截断）。 */
  private static LocalDate date(OffsetDateTime moment) {
    return moment == null ? null : moment.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  /** 上游本地日期 → 日期（空 ⇒ null）。 */
  private static LocalDate date(LocalDate day) {
    return day;
  }

  /** 字符串去空白（空 ⇒ null）。 */
  private static String trim(String raw) {
    if (raw == null) return null;
    var value = raw.trim();
    return value.isEmpty() ? null : value;
  }
}
