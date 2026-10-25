package com.starcon.reporting;

import com.starcon.reporting.ReportingProjectionModels.ProjectionExpenseFact;
import com.starcon.reporting.ReportingProjectionModels.ProjectionEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 13.4.2.2 费用差异的**拉取适配器**：把上游的授权只读端点读成事实，归一化成 envelope，
 * 交给**同一个**投影器（{@link ReportingProjectionService}）。
 *
 * <p>⚠️ **幂等、口径、重建三条纪律仍然只有一处实现**（投影器里）：本类只负责
 * 「读上游 → 归一化 → 交给投影器」，**不自己写事实表**。
 *
 * <p><b>五路上游</b>（每一路对应一个费用类型的一侧）：
 *
 * <ol>
 *   <li>{@code catalog-expense-platform} —— 平台费：catalog 5.2.3.1 的**实际台账**（实际侧）
 *       + 同一份台的**规则测算**（预计侧）；
 *   <li>{@code catalog-expense-freight} —— 物流费：catalog 5.2.3.2 的**实际运费台账** +
 *       **规则测算**；
 *   <li>{@code catalog-expense-head-freight} —— 头程分摊：catalog 5.2.1.3 成本分摊的
 *       **逐 SKU 分摊额**（实际侧）+ catalog 5.2.1.1 成本构成的 {@code FIRST_MILE} 分项（预计侧）；
 *   <li>{@code finance-expense-settlement} —— 12.3.1.2 结算明细里的三类费用
 *       （佣金 / FBA 费用 / 广告费），这是蓝图点名的**实际费用来源**；
 *   <li>{@code channel-expense-ad-budget} —— 14 广告预算分配方案（广告费的预计侧）。
 * </ol>
 *
 * <p>⚠️ **预计值一律由上游的规则算出来**（catalog 的 {@code /estimate}），
 * 本服务**不重算费率** —— 重算就会出现第二个真相（规则一改，本模块的差异就变成假的）。
 */
@Service
public class ReportingExpenseRefreshService {

  /** 本模块的五路上游（**空的 ⇒ 全部**）。 */
  static final List<String> SOURCES =
      List.of(
          "catalog-expense-platform",
          "catalog-expense-freight",
          "catalog-expense-head-freight",
          "finance-expense-settlement",
          "channel-expense-ad-budget");

  /**
   * 一次刷新里最多测算多少次「预计值」。
   *
   * <p>⚠️ 预计值**必须逐行测算**（阶梯规则按**这一行**的金额选档，
   * 按天合计再测算会落到另一档 ⇒ 差异是假的）。逐行测算就是 N+1，
   * 所以这里有上限：触顶时**如实报 truncated**，剩下的行只有实际值、
   * 预计值算不出来（页面上显示「—（超过测算上限）」，不是 0）。
   */
  static final int MAX_ESTIMATE_CALLS = 200;

  /**
   * 一次刷新里最多取多少个 SKU 的成本构成（头程分摊的预计侧，逐 SKU 一条）。
   *
   * <p>同 {@link #MAX_ESTIMATE_CALLS}：触顶时如实报 truncated。
   */
  static final int MAX_COST_DETAIL_FETCHES = 100;

  private final ReportingExpensePort expense;
  private final ReportingSettlementPort settlement;
  private final ReportingAdBudgetPort adBudget;
  private final ReportingProjectionService projection;

  public ReportingExpenseRefreshService(
      ReportingExpensePort expense,
      ReportingSettlementPort settlement,
      ReportingAdBudgetPort adBudget,
      ReportingProjectionService projection) {
    this.expense = expense;
    this.settlement = settlement;
    this.adBudget = adBudget;
    this.projection = projection;
  }

  /**
   * 刷新费用事实。
   *
   * @param sources 要刷新的来源（空 ⇒ 全部）
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   */
  public List<ReportingProjectionModels.RefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, List<String> sources, ZoneId timezone) {
    var wanted = sources == null || sources.isEmpty() ? SOURCES : sources;
    var results = new ArrayList<ReportingProjectionModels.RefreshResult>();
    for (var source : wanted) {
      try {
        results.add(
            switch (source) {
              case "catalog-expense-platform" -> refreshPlatformFee(tenantId, bearer, from, to, timezone);
              case "catalog-expense-freight" -> refreshLogisticsFee(tenantId, bearer, from, to, timezone);
              case "catalog-expense-head-freight" -> refreshHeadFreight(tenantId, bearer, timezone);
              case "finance-expense-settlement" -> refreshSettlement(tenantId, bearer, from, to, timezone);
              case "channel-expense-ad-budget" -> refreshAdBudget(tenantId, bearer, timezone);
              default ->
                  new ReportingProjectionModels.RefreshResult(
                      source, source, 0, 0, 0, "SKIPPED", "未知的刷新来源：" + source);
            });
      } catch (org.springframework.web.server.ResponseStatusException e) {
        // ⚠️ 上游读不到 ⇒ 这一路**失败**，不是「这一路没有数据」。
        results.add(
            new ReportingProjectionModels.RefreshResult(
                source, source, 0, 0, 0, "FAILED", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    return results;
  }

  // ==========================================================================
  // 平台费（catalog 5.2.3.1）
  // ==========================================================================

  /**
   * 平台费：catalog 的实际费用台账（实际侧）+ 按规则测算（预计侧）。
   *
   * <p>⚠️ **预计值逐行测算**：这一行的 {@code sale_amount} 就是「比例规则要乘的基数」，
   * 把它按天加起来再测算会落到另一档阶梯 ⇒ 差异会变成假的。
   */
  private ReportingProjectionModels.RefreshResult refreshPlatformFee(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    int estimateCalls = 0;
    boolean truncated = false;
    for (int page = 0; page < ReportingExpensePort.MAX_PAGES; page++) {
      var rows = expense.platformFeeActuals(null, null, null, from, to, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        // ⚠️ 作废行不是费用事实（作废是「录错了」，不是「这笔没发生」）。
        if (row.isVoid()) continue;
        var amount = ReportingExpensePort.decimal(row.amount());
        if (amount == null) continue;
        var occurredOn = ReportingExpensePort.date(row.occurredOn());
        if (occurredOn == null) continue;
        var currency = currency(row.currency());
        if (currency == null) continue;
        var saleAmount = ReportingExpensePort.decimal(row.saleAmount());
        var quantity = ReportingExpensePort.decimal(row.quantity());

        BigDecimal estimated = null;
        if (estimateCalls < MAX_ESTIMATE_CALLS) {
          estimateCalls++;
          var estimate =
              expense.platformFeeEstimate(
                  row.platformCode(),
                  row.siteCode(),
                  row.internalCategoryId(),
                  row.platformCategoryId(),
                  saleAmount,
                  quantity,
                  currency,
                  occurredOn + "T00:00:00Z",
                  bearer);
          var item = itemOf(estimate, row.feeType());
          if (item != null && item.applicable()) {
            estimated = ReportingExpensePort.decimal(item.amount());
          }
        } else {
          truncated = true;
        }

        var facts = new ArrayList<ProjectionExpenseFact>();
        if (estimated != null) {
          facts.add(
              expenseFact(
                  "ESTIMATE",
                  "PLATFORM_FEE",
                  row.feeType(),
                  row.platformCode(),
                  row.siteCode(),
                  "",
                  "",
                  "",
                  "",
                  "",
                  occurredOn,
                  occurredOn,
                  currency,
                  estimated,
                  saleAmount,
                  quantity,
                  row.id().toString()));
        }
        facts.add(
            expenseFact(
                "ACTUAL",
                "PLATFORM_FEE",
                row.feeType(),
                row.platformCode(),
                row.siteCode(),
                "",
                "",
                "",
                "",
                "",
                occurredOn,
                occurredOn,
                currency,
                amount,
                saleAmount,
                quantity,
                row.id().toString()));
        events.add(
            expenseEvent(
                "catalog",
                "catalog:platform-fees:" + row.id(),
                row.id(),
                occurredOn.atStartOfDay(timezone).toOffsetDateTime(),
                timezone,
                facts));
      }
      if (rows.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "平台费按 catalog 5.2.3.1 的实际费用台账逐行取数，**预计值逐行按规则测算**"
            + "（阶梯规则按这一行的金额选档，按天合计再测会落到另一档）"
            + (truncated ? "；⚠️ 测算调用次数达到上限（" + MAX_ESTIMATE_CALLS + "），超出的行只有实际值" : "")
            + "。";
    return result("catalog", "catalog:platform-fees", events.size(), applied, note);
  }

  // ==========================================================================
  // 物流费（catalog 5.2.3.2）
  // ==========================================================================

  /**
   * 物流费：catalog 的实际运费台账（实际侧）+ 按规则测算（预计侧）。
   *
   * <p>⚠️ **计费口径决定测算输入**：上游的测算接口按「重量 / 长宽高 / 件数」取输入，
   * 而实际运费台账只记了**计费量**。因此：
   *
   * <ul>
   *   <li>{@code BY_WEIGHT} ⇒ 传 {@code weightKg = 计费量}；
   *   <li>{@code BY_PIECE} ⇒ 传 {@code pieces = 件数}；
   *   <li>{@code BY_VOLUME} ⇒ 传 {@code 长 = 宽 = 100cm、高 = 计费量(m³) × 100cm}
   *       （上游按「长 × 宽 × 高 ÷ 1,000,000」算体积，这样**精确**等于台账上的计费量）。
   * </ul>
   */
  private ReportingProjectionModels.RefreshResult refreshLogisticsFee(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    int estimateCalls = 0;
    boolean truncated = false;
    for (int page = 0; page < ReportingExpensePort.MAX_PAGES; page++) {
      var rows = expense.freightActuals(null, null, null, from, to, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.isVoid()) continue;
        var amount = ReportingExpensePort.decimal(row.amount());
        if (amount == null) continue;
        var occurredOn = ReportingExpensePort.date(row.occurredOn());
        if (occurredOn == null) continue;
        var currency = currency(row.currency());
        if (currency == null) continue;
        var chargeable = ReportingExpensePort.decimal(row.chargeableQuantity());
        var pieces = ReportingExpensePort.decimal(row.quantity());

        BigDecimal estimated = null;
        if (estimateCalls < MAX_ESTIMATE_CALLS) {
          estimateCalls++;
          var inputs = freightInputs(row.chargeBasis(), chargeable, pieces);
          if (inputs != null) {
            var estimate =
                expense.freightEstimate(
                    row.channelCode(),
                    row.carrierCode(),
                    row.shippingMethodCode(),
                    row.chargeBasis(),
                    inputs.weightKg(),
                    inputs.lengthCm(),
                    inputs.widthCm(),
                    inputs.heightCm(),
                    inputs.pieces(),
                    occurredOn + "T00:00:00Z",
                    bearer);
            if (estimate.complete()) {
              estimated = ReportingExpensePort.decimal(estimate.totalAmount());
            }
          }
        } else {
          truncated = true;
        }

        var facts = new ArrayList<ProjectionExpenseFact>();
        if (estimated != null) {
          facts.add(
              expenseFact(
                  "ESTIMATE",
                  "LOGISTICS_FEE",
                  row.chargeBasis(),
                  "",
                  "",
                  row.channelCode(),
                  row.carrierCode(),
                  row.shippingMethodCode(),
                  "",
                  "",
                  occurredOn,
                  occurredOn,
                  currency,
                  estimated,
                  chargeable,
                  pieces,
                  row.id().toString()));
        }
        facts.add(
            expenseFact(
                "ACTUAL",
                "LOGISTICS_FEE",
                row.chargeBasis(),
                "",
                "",
                row.channelCode(),
                row.carrierCode(),
                row.shippingMethodCode(),
                "",
                "",
                occurredOn,
                occurredOn,
                currency,
                amount,
                chargeable,
                pieces,
                row.id().toString()));
        events.add(
            expenseEvent(
                "catalog",
                "catalog:logistics-freight:" + row.id(),
                row.id(),
                occurredOn.atStartOfDay(timezone).toOffsetDateTime(),
                timezone,
                facts));
      }
      if (rows.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "物流费按 catalog 5.2.3.2 的实际运费台账逐行取数，**预计值逐行按规则测算**"
            + "（计费口径决定测算输入：按重量传计费重、按件数传件数、按体积用长宽高还原计费量）"
            + (truncated ? "；⚠️ 测算调用次数达到上限（" + MAX_ESTIMATE_CALLS + "），超出的行只有实际值" : "")
            + "。";
    return result("catalog", "catalog:logistics-freight", events.size(), applied, note);
  }

  // ==========================================================================
  // 头程分摊（catalog 5.2.1.1 + 5.2.1.3）
  // ==========================================================================

  /**
   * 头程分摊：catalog 5.2.1.3 成本分摊的**逐 SKU 分摊额**（实际侧）
   * + catalog 5.2.1.1 成本构成的 {@code FIRST_MILE} 分项（预计侧）。
   *
   * <p>⚠️ **实际侧取「计算分摊额」而不是「最终分摊额」**：人工微调恰恰是运营
   * **已经**对分摊偏差做的修正，把它算进来会把这个模块要找的信号（系统性偏差）抹掉。
   *
   * <p>⚠️ **币种不一致就不写预计值**：跨币种绝不相加（仓库铁律），
   * 两边币种不同的那一行只能是「有实际、无预计」。
   */
  private ReportingProjectionModels.RefreshResult refreshHeadFreight(
      UUID tenantId, String bearer, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    int costLookups = 0;
    boolean truncated = false;
    for (int page = 0; page < ReportingExpensePort.MAX_PAGES; page++) {
      var rows = expense.costAllocations(page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.allocationId() == null) continue;
        var detail = expense.costAllocation(row.allocationId(), bearer);
        if (detail == null || detail.lines() == null || detail.lines().isEmpty()) continue;
        var revision = detail.currentRevision();
        var period = revision == null ? null : date(revision.effectiveFrom());
        if (period == null) continue;
        var actualCurrency = currency(revision == null ? row.currency() : revision.currency());
        if (actualCurrency == null) continue;

        for (var line : detail.lines()) {
          if (line.skuCode() == null || line.skuCode().isBlank()) continue;
          var actual = line.allocatedAmount();
          if (actual == null) continue;

          BigDecimal estimated = null;
          String estimateCurrency = null;
          if (line.skuId() != null && costLookups < MAX_COST_DETAIL_FETCHES) {
            costLookups++;
            var cost = expense.skuCost(line.skuId(), bearer);
            if (cost != null && cost.components() != null) {
              estimateCurrency = currency(cost.currency());
              for (var component : cost.components()) {
                if (!"FIRST_MILE".equals(component.code())) continue;
                estimated = component.amount();
                break;
              }
            }
          } else {
            truncated = true;
          }

          var facts = new ArrayList<ProjectionExpenseFact>();
          // ⚠️ 预计侧的币种取成本构成的币种；与实际侧不一致就**不写预计值**。
          if (estimated != null && estimateCurrency != null && estimateCurrency.equals(actualCurrency)) {
            facts.add(
                expenseFact(
                    "ESTIMATE",
                    "HEAD_FREIGHT",
                    "FIRST_MILE",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    line.skuCode(),
                    period,
                    period,
                    estimateCurrency,
                    estimated,
                    line.basisValue(),
                    line.baseQuantity(),
                    row.allocationId() + ":" + line.skuId()));
          }
          facts.add(
              expenseFact(
                  "ACTUAL",
                  "HEAD_FREIGHT",
                  "FIRST_MILE",
                  "",
                  "",
                  "",
                  "",
                  "",
                  "",
                  line.skuCode(),
                  period,
                  period,
                  actualCurrency,
                  actual,
                  line.basisValue(),
                  line.baseQuantity(),
                  row.allocationId() + ":" + line.skuId()));
          events.add(
              expenseEvent(
                  "catalog",
                  "catalog:cost-allocations:" + row.allocationId() + ":" + line.skuId(),
                  row.allocationId(),
                  period.atStartOfDay(timezone).toOffsetDateTime(),
                  timezone,
                  facts));
        }
      }
      if (rows.items().size() < ReportingExpensePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "头程分摊取 catalog 5.2.1.3 成本分摊单的**计算分摊额**（不含人工微调）作为实际侧，"
            + "预计侧取 catalog 5.2.1.1 成本构成的 FIRST_MILE 分项；币种不一致的行只报实际值。"
            + (truncated ? "；⚠️ SKU 成本构成读取次数达到上限（" + MAX_COST_DETAIL_FETCHES + "），超出的 SKU 只有实际值" : "")
            + "。";
    return result("catalog", "catalog:cost-allocations", events.size(), applied, note);
  }

  // ==========================================================================
  // 12.3.1.2 结算明细里的三类费用（实际侧）
  // ==========================================================================

  /**
   * 12.3.1.2 结算明细：佣金 ⇒ 平台费、FBA 费用 ⇒ 物流费、广告费 ⇒ 广告费。
   *
   * <p>⚠️ **符号要翻过来**：上游的 {@code amount} 是「归一后的贡献」（正 = 增加结算金额，
   * 负 = 减少），而费用在结算里是**减项** ⇒ 费用金额 = {@code -amount}。
   * 不翻符号的话，平台费会变成负数，差异率会整个反过来。
   *
   * <p>⚠️ **这一侧没有预计值**：结算明细只给费用金额，**不给销售额 / 数量基数**，
   * 而比例规则与按件规则重算预计值**必须**有基数 ⇒ 算不出来，如实报「有实际、无预计」。
   */
  private ReportingProjectionModels.RefreshResult refreshSettlement(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    var mapping =
        List.of(
            new SettlementFee("COMMISSION", "PLATFORM_FEE"),
            new SettlementFee("FBA_FEE", "LOGISTICS_FEE"),
            new SettlementFee("ADVERTISING", "AD_FEE"));
    for (var mappingRow : mapping) {
      for (int page = 0; page < ReportingSettlementPort.MAX_PAGES; page++) {
        var rows = settlement.transactions(mappingRow.category(), from, to, page, bearer);
        if (rows.items() == null || rows.items().isEmpty()) break;
        for (var row : rows.items()) {
          if (row.amount() == null) continue;
          var occurredOn = row.occurredOn();
          if (occurredOn == null) continue;
          var currency = currency(row.currency());
          if (currency == null) continue;
          var facts = new ArrayList<ProjectionExpenseFact>();
          facts.add(
              expenseFact(
                  "ACTUAL",
                  mappingRow.feeType(),
                  mappingRow.category(),
                  row.platformCode(),
                  "",
                  "",
                  "",
                  "",
                  row.shopCode(),
                  "",
                  occurredOn,
                  occurredOn,
                  currency,
                  row.amount().negate(),
                  null,
                  row.quantity(),
                  row.id().toString()));
          events.add(
              expenseEvent(
                  "finance",
                  "finance:transactions:" + row.id(),
                  row.id(),
                  occurredOn.atStartOfDay(timezone).toOffsetDateTime(),
                  timezone,
                  facts));
        }
        if (rows.items().size() < ReportingSettlementPort.PAGE_SIZE) break;
      }
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "12.3.1.2 结算明细里的三类费用（佣金 / FBA 费用 / 广告费）作为**实际侧**；"
            + "符号已翻正（上游是「结算贡献」，费用是减项）。"
            + "这一侧**没有预计值** —— 结算明细不带销售额 / 数量基数，比例规则与按件规则重算不出来。";
    return result("finance", "finance:transactions", events.size(), applied, note);
  }

  // ==========================================================================
  // 14 广告预算分配方案（广告费的预计侧）
  // ==========================================================================

  /**
   * 14 广告预算分配方案：广告费的**预计侧**。
   *
   * <p>⚠️ **广告费没有「费用规则」**：5.2.3 交付的是平台费与物流头程费的规则，
   * 广告那边交付的是**预算分配方案**。所以广告费的预计值取方案里**逐活动的分配额**
   * （渠道侧本来就以预算为计划口径），期间取**方案的统计窗口**。
   *
   * <p>⚠️ **SUPERSEDED 的方案不参与**：被取代的方案与取代它的新方案窗口重叠，
   * 两份都算就会把预计费用算两遍。
   */
  private ReportingProjectionModels.RefreshResult refreshAdBudget(UUID tenantId, String bearer, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    int superseded = 0;
    for (int page = 0; page < ReportingAdBudgetPort.MAX_PAGES; page++) {
      var rows = adBudget.plans(page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.id() == null) continue;
        if ("SUPERSEDED".equals(row.status())) {
          superseded++;
          continue;
        }
        var detail = adBudget.plan(row.id(), bearer);
        if (detail == null || detail.lines() == null || detail.lines().isEmpty()) continue;
        var periodStart = row.windowStart();
        var periodEnd = row.windowEnd();
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) continue;

        // 逐「平台 + 店铺 + 币种」聚合（一个活动一行 ⇒ 同一平台店铺下多行要加起来）。
        var totals = new java.util.LinkedHashMap<String, BigDecimal>();
        var currencies = new java.util.LinkedHashMap<String, String>();
        for (var line : detail.lines()) {
          var amount = line.allocatedBudget();
          if (amount == null) continue;
          var lineCurrency = currency(line.currency());
          if (lineCurrency == null) continue;
          var key =
              (line.platformCode() == null ? "" : line.platformCode())
                  + "|"
                  + (line.shopCode() == null ? "" : line.shopCode())
                  + "|"
                  + lineCurrency;
          totals.merge(key, amount, BigDecimal::add);
          currencies.put(key, lineCurrency);
        }
        for (var entry : totals.entrySet()) {
          var parts = entry.getKey().split("\\|", -1);
          var facts = new ArrayList<ProjectionExpenseFact>();
          facts.add(
              expenseFact(
                  "ESTIMATE",
                  "AD_FEE",
                  "BUDGET_PLAN",
                  parts[0],
                  "",
                  "",
                  "",
                  "",
                  parts[1],
                  "",
                  periodStart,
                  periodEnd,
                  currencies.get(entry.getKey()),
                  entry.getValue(),
                  null,
                  null,
                  row.id() + ":" + entry.getKey()));
          events.add(
              expenseEvent(
                  "channel",
                  "channel:ad-budgets:" + row.id() + ":" + entry.getKey(),
                  row.id(),
                  periodStart.atStartOfDay(timezone).toOffsetDateTime(),
                  timezone,
                  facts));
        }
      }
      if (rows.items().size() < ReportingAdBudgetPort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "广告费的预计值取 14 广告预算分配方案的**逐活动分配额**（按平台 + 店铺聚合），"
            + "期间取方案的统计窗口。"
            + (superseded > 0 ? "已跳过 " + superseded + " 份被取代（SUPERSEDED）的方案（窗口重叠会重复计算）。" : "");
    return result("channel", "channel:ad-budgets", events.size(), applied, note);
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 结算类别 → 本模块的费用类型。 */
  private record SettlementFee(String category, String feeType) {}

  /** 运费测算的输入（按计费口径不同而不同）。 */
  private record FreightInputs(
      BigDecimal weightKg,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      BigDecimal pieces) {}

  /**
   * 按计费口径把「实际运费台账上的计费量」翻译成上游测算接口的输入。
   *
   * <p>⚠️ 口径不认识 ⇒ 返回 null（**算不出来**，不是 0）。
   */
  private FreightInputs freightInputs(String chargeBasis, BigDecimal chargeable, BigDecimal pieces) {
    if (chargeBasis == null || chargeBasis.isBlank()) return null;
    switch (chargeBasis) {
      case "BY_WEIGHT" -> {
        if (chargeable == null) return null;
        return new FreightInputs(chargeable, null, null, null, null);
      }
      case "BY_PIECE" -> {
        if (pieces == null) return null;
        return new FreightInputs(null, null, null, null, pieces);
      }
      case "BY_VOLUME" -> {
        if (chargeable == null) return null;
        // 上游按「长 × 宽 × 高 ÷ 1,000,000」算体积：取长 = 宽 = 100cm、高 = 计费量(m³) × 100cm
        // ⇒ 体积**精确**等于台账上的计费量（不引入开方误差）。
        return new FreightInputs(
            null, new BigDecimal("100"), new BigDecimal("100"), chargeable.multiply(new BigDecimal("100")), null);
      }
      default -> {
        return null;
      }
    }
  }

  /** 币种规范化（空 / 非 ISO alpha-3 ⇒ null，即「这一行不算」）。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  /** 上游时间 → 日期（按 UTC 截断；上游的成本构成有效期是带时区的时刻）。 */
  private static LocalDate date(OffsetDateTime moment) {
    return moment == null ? null : moment.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  /** 测算结果里挑出某个费用类型的项（找不到 ⇒ null ⇒ 「没有生效的规则」）。 */
  private static ReportingExpensePort.PlatformFeeEstimateItem itemOf(
      ReportingExpensePort.PlatformFeeEstimate estimate, String feeType) {
    if (estimate == null || estimate.items() == null || feeType == null) return null;
    for (var item : estimate.items()) {
      if (feeType.equals(item.feeType())) return item;
    }
    return null;
  }

  /** 一条费用事实。 */
  private static ProjectionExpenseFact expenseFact(
      String factKind,
      String feeType,
      String feeSubType,
      String platformCode,
      String siteCode,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String shopCode,
      String skuCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal baseAmount,
      BigDecimal baseQuantity,
      String sourceReference) {
    return new ProjectionExpenseFact(
        factKind,
        feeType,
        feeSubType == null ? "" : feeSubType,
        platformCode == null ? "" : platformCode,
        siteCode == null ? "" : siteCode,
        channelCode == null ? "" : channelCode,
        carrierCode == null ? "" : carrierCode,
        shippingMethodCode == null ? "" : shippingMethodCode,
        shopCode == null ? "" : shopCode,
        skuCode == null ? "" : skuCode,
        periodStart,
        periodEnd,
        currency,
        amount,
        baseAmount,
        baseQuantity,
        sourceReference);
  }

  /**
   * 事件 id = 服务:流:上游行标识:内容摘要（**内容寻址**：内容不变 ⇒ id 不变 ⇒ 幂等跳过）。
   *
   * <p>⚠️ **摘要里必须含预计值**：规则改了（费率从 15% 调到 12%）而上游那一行没变时，
   * 只按「行内容」算摘要会算出同一个 id ⇒ 事件被判重跳过 ⇒ **预计值永远更新不了**。
   */
  private ProjectionEvent expenseEvent(
      String sourceService,
      String key,
      UUID aggregateId,
      OffsetDateTime occurredAt,
      ZoneId timezone,
      List<ProjectionExpenseFact> expenseFacts) {
    var signature = new StringBuilder();
    for (var fact : expenseFacts) {
      signature
          .append(fact.factKind())
          .append('|')
          .append(fact.feeType())
          .append('|')
          .append(fact.feeSubType())
          .append('|')
          .append(fact.platformCode())
          .append('|')
          .append(fact.siteCode())
          .append('|')
          .append(fact.channelCode())
          .append('|')
          .append(fact.carrierCode())
          .append('|')
          .append(fact.shippingMethodCode())
          .append('|')
          .append(fact.shopCode())
          .append('|')
          .append(fact.skuCode())
          .append('|')
          .append(fact.periodStart())
          .append('|')
          .append(fact.periodEnd())
          .append('|')
          .append(fact.currency())
          .append('|')
          .append(fact.amount())
          .append('|')
          .append(fact.baseAmount())
          .append('|')
          .append(fact.baseQuantity())
          .append(';');
    }
    return new ProjectionEvent(
        sourceService,
        sourceService + ":" + key + ":" + digest(signature.toString()),
        ReportingProjectionModels.ReportingEventTypes.EXPENSE_FACT,
        aggregateId,
        0,
        occurredAt,
        timezone.getId(),
        List.of(),
        List.of(),
        expenseFacts);
  }

  /** 内容摘要（SHA-256 前 16 字节，十六进制）。 */
  private static String digest(String content) {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ReportingProjectionModels.RefreshResult result(
      String sourceService,
      String stream,
      int eventCount,
      ReportingProjectionModels.ProjectionBatchResult applied,
      String note) {
    return new ReportingProjectionModels.RefreshResult(
        sourceService, stream, eventCount, applied.appliedCount(), applied.skippedCount(), "OK", note);
  }
}
