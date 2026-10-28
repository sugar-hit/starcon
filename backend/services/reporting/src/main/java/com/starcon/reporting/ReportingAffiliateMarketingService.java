package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.2 关联营销效果的**查询侧**（营销位效果报表 + 归因规则清单 + 归因明细下钻）。
 *
 * <p>⚠️ <b>口径唯一</b>：指标定义从 {@code reporting_affiliate_marketing_metric_definition} 读，
 * 查询 SQL <b>不重算口径</b>，只按定义聚合。派生指标（ROI / 点击率 / 客单价）<b>只写引用</b>
 * （分子分母的指标码），口径改一处，引用它的地方自动跟着改。
 *
 * <p>⚠️ <b>本模块的第一性纪律</b>：
 *
 * <ul>
 *   <li><b>归因规则是行的一部分</b>：同一对达人 × 活动如果历史上用过两条不同的归因规则，
 *       报表里就是<b>两行</b>，各带自己的规则快照 ⇒ <b>ROI 只在同一条规则内部相除</b>，
 *       两条规则的钱绝不会被先加到一起再除。
 *   <li><b>两套口径分列</b>：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径
 *       （曝光 / 点击 / 点击率）是<b>两回事</b>，各带自己的口径标签，**绝不混算</b>。
 *   <li><b>「算不出来」与「等于 0」必须分开</b>：本轮不可用、分母缺失、事实不存在，
 *       一律返回 {@code NOT_AVAILABLE} / {@code NOT_COMPARABLE} / {@code NO_DATA}，
 *       <b>不用 0 冒充</b>。
 * </ul>
 */
@Service
public class ReportingAffiliateMarketingService {

  /** 比率型指标的显示精度。 */
  private static final int PERCENT_SCALE = 4;

  /** 金额型派生指标的显示精度。 */
  private static final int MONEY_SCALE = 4;

  /** 「这个窗口里没有这个指标的事实」的说明。 */
  private static final String NO_DATA_NOTE = "这个窗口里没有这个指标的事实数据。";

  /** 「分母缺失或为 0」的说明。 */
  private static final String NOT_COMPARABLE_NOTE = "分母缺失或为 0，算不出来（不是 0）。";

  /** 「本轮不可用」的说明（**不是 0**）。 */
  private static final String NOT_AVAILABLE_NOTE =
      "🔴 本轮不可用：这一类营销位没有这个口径的数据（归谁管见能力声明），**不是 0**。";

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingAffiliateMarketingRefreshService refresh;

  public ReportingAffiliateMarketingService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingAffiliateMarketingRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }


  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /**
   * 能力声明（**逐条对着上游的授权只读 API 核过**）。
   *
   * <p>⚠️ **不列没读过的上游**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  public ReportingAffiliateMarketingModels.AffiliateMarketingCapabilities capabilities(UUID tenantId) {
    return new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilities(
        true,
        true,
        true,
        true,
        UPSTREAM_MODULES,
        NOT_DELIVERED,
        RULES,
        slotTypeViews(),
        List.of(
            ReportingAffiliateMarketingCatalog.IMPRESSIONS,
            ReportingAffiliateMarketingCatalog.CLICKS,
            ReportingAffiliateMarketingCatalog.ADD_TO_CART,
            ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV,
            ReportingAffiliateMarketingCatalog.ATTRIBUTED_ORDERS,
            ReportingAffiliateMarketingCatalog.SPEND,
            ReportingAffiliateMarketingCatalog.CTR,
            ReportingAffiliateMarketingCatalog.ROI,
            ReportingAffiliateMarketingCatalog.ATTRIBUTED_AOV),
        ReportingAffiliateMarketingCatalog.ATTRIBUTION_MODELS,
        ReportingAffiliateMarketingCatalog.POOL_WEIGHT_BASES,
        ReportingAffiliateMarketingCatalog.REFUND_TREATMENTS,
        ReportingAffiliateMarketingCatalog.COMMISSION_BASES,
        ReportingAffiliateMarketingCatalog.ATTRIBUTION_RULE_NOTE,
        CURRENCY_NOTE,
        CALIBER_NOTE);
  }

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /** 指标口径目录（**运行时唯一的真相是那张表**）。 */
  public List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricDefinitionView>
      metricDefinitions(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ 口径表是空的时先播种一份标准口径（与 13.2.2.1 同纪律）——
          //   否则新租户第一次打开口径目录看到的是空列表。
          refresh.seedDefinitions(tenantId);
          return definitionViews(definitionMap(tenantId));
        });
  }

  // ==========================================================================
  // 主报表
  // ==========================================================================

  /** 营销位效果报表（**按 (营销位, 归因规则) 分列**）。 */
  public ReportingAffiliateMarketingModels.AffiliateMarketingResponse report(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var slots = slotViews(tenantId, from, to);
          var facts = loadFacts(tenantId, from, to);
          var rules = ruleUsages(tenantId, from, to);

          var rows = slotRows(slots, facts, rules, definitions);
          var updatedAt = dataUpdatedAt(tenantId);

          var warnings = new ArrayList<String>();
          var slotTypes = slotTypeViews();
          for (var slotType : slotTypes) {
            if (!slotType.available()) {
              warnings.add(
                  "⚠️ **"
                      + slotType.displayName()
                      + " 营销位本轮不可用**（"
                      + slotType.owner()
                      + "）："
                      + slotType.note());
              continue;
            }
            var hasRows =
                rows.stream().anyMatch(row -> slotType.slotType().equals(row.slotType()));
            if (!hasRows) {
              warnings.add(
                  "⚠️ 这个窗口**没有 "
                      + slotType.displayName()
                      + " 营销位的任何事实** —— 可能是这段时间没有投放，"
                      + "也可能是**还没跑过投影刷新**（两者页面上长得一样，所以这里如实说明）。");
            }
          }
          if (!rules.isEmpty()) {
            var models = new LinkedHashSet<String>();
            for (var rule : rules) models.add(rule.attributionModel());
            if (models.size() > 1) {
              warnings.add(
                  "🔴 这个窗口里**用过 "
                      + models.size()
                      + " 种不同的归因模型**（"
                      + String.join("、", models)
                      + "）⇒ 报表已按 (营销位, 归因规则) **分列**，"
                      + "**ROI 只在同一条规则内部相除** —— 两条规则的钱不会被加到一起再除。");
            }
          }

          var empty = rows.isEmpty();
          return new ReportingAffiliateMarketingModels.AffiliateMarketingResponse(
              from,
              to,
              rows,
              rules,
              slotTypes,
              definitionViews(definitions),
              updatedAt,
              updatedAt == null
                  ? "本租户还没有任何关联营销投影数据（空表时**不显示**假更新时间）。"
                  : "数据更新时间 = 最近一次投影落库时刻（报表是投影，不是实时的）。",
              empty ? "EMPTY" : "OK",
              empty
                  ? "这个窗口内没有任何关联营销事实。"
                  : "已按 (营销位, 归因规则) 分列给出效果与 ROI；🔴 **两套口径不混算**。",
              empty ? "NO_FACTS_IN_WINDOW" : null,
              empty
                  ? "这个窗口内没有可分析的关联营销事实。可能是这段时间没有投放，"
                      + "也可能是**还没跑过投影刷新** —— 两者页面上长得一样，所以这里如实说明。"
                  : "",
              warnings,
              caliberNotes());
        });
  }

  // ==========================================================================
  // 归因明细下钻
  // ==========================================================================

  /**
   * 归因明细下钻（**带规则快照**）。
   *
   * @param slotType 营销位类型（空 = 全部）
   * @param ruleCode 归因规则码（空 = 全部）
   */
  public ReportingAffiliateMarketingModels.AffiliateMarketingAttributionPage attributions(
      UUID tenantId, LocalDate from, LocalDate to, String slotType, String ruleCode, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var type = blankToNull(slotType);
          var rule = blankToNull(ruleCode);
          var safePage = Math.max(page, 0);
          var safeSize = size <= 0 ? 50 : Math.min(size, 200);

          var total =
              sql.fetch(
                      """
                      select count(*) from reporting_affiliate_marketing_attribution_fact
                       where tenant_id = ? and stat_date >= ? and stat_date <= ?
                         and (?::text is null or slot_type = ?::text)
                         and (?::text is null or rule_code = ?::text)
                      """,
                      tenantId,
                      from,
                      to,
                      type,
                      type,
                      rule,
                      rule)
                  .stream()
                  .findFirst()
                  .map(r -> r.get(0, Long.class))
                  .orElse(0L);

          var rows =
              sql.fetch(
                      """
                      select a.attribution_id, a.slot_type, a.slot_key, s.slot_label,
                             a.influencer_id, a.influencer_name, a.campaign_id, a.campaign_name,
                             a.cooperation_id, a.shop_code, a.currency,
                             a.rule_code, a.attribution_model, a.pool_weight_basis,
                             a.attribution_window_days, a.refund_treatment, a.commission_basis,
                             a.commission_rate, a.attribution_weight, a.attributed_gmv,
                             a.attributed_order_count, a.effective_order_count, a.commission_amount,
                             a.attributed_at
                        from reporting_affiliate_marketing_attribution_fact a
                        left join reporting_affiliate_marketing_slot s
                          on s.tenant_id = a.tenant_id
                         and s.slot_type = a.slot_type
                         and s.slot_key = a.slot_key
                       where a.tenant_id = ? and a.stat_date >= ? and a.stat_date <= ?
                         and (?::text is null or a.slot_type = ?::text)
                         and (?::text is null or a.rule_code = ?::text)
                       order by a.attributed_at desc nulls last, a.attribution_id
                       limit ? offset ?
                      """,
                      tenantId,
                      from,
                      to,
                      type,
                      type,
                      rule,
                      rule,
                      safeSize,
                      safePage * safeSize);

          var items = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingAttributionRow>();
          for (var row : rows) {
            items.add(
                new ReportingAffiliateMarketingModels.AffiliateMarketingAttributionRow(
                    row.get(0, java.util.UUID.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, java.util.UUID.class),
                    row.get(5, String.class),
                    row.get(6, java.util.UUID.class),
                    row.get(7, String.class),
                    row.get(8, java.util.UUID.class),
                    row.get(9, String.class),
                    row.get(10, String.class),
                    row.get(11, String.class),
                    row.get(12, String.class),
                    row.get(13, String.class),
                    row.get(14, Integer.class),
                    row.get(15, String.class),
                    row.get(16, String.class),
                    row.get(17, BigDecimal.class),
                    row.get(18, BigDecimal.class),
                    row.get(19, BigDecimal.class),
                    row.get(20, BigDecimal.class),
                    row.get(21, BigDecimal.class),
                    row.get(22, BigDecimal.class),
                    row.get(23, OffsetDateTime.class)));
          }
          return new ReportingAffiliateMarketingModels.AffiliateMarketingAttributionPage(
              List.copyOf(items),
              safePage,
              safeSize,
              total,
              "🔴 每行都带**规则快照**（归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径）—— "
                  + "这是**上游归因记录自带的那份**，不是「当前规则」。"
                  + "⚠️ **统计日按「归因发生日（UTC 自然日）」切桶**：上游归因记录**不带订单日期**。");
        });
  }

  // ==========================================================================
  // 营销位行
  // ==========================================================================

  /** 一个营销位（台账里的那一行）。 */
  private record SlotView(
      String slotType,
      String slotKey,
      String slotLabel,
      String caliber,
      String shopCode,
      String platformCode) {}

  /** 一个 (营销位, 归因规则) 的聚合结果。 */
  private record Agg(String metricCode, String currency, BigDecimal value, BigDecimal quantity) {}

  /** 载入门户台账里**本窗口有事实**的营销位。 */
  private List<SlotView> slotViews(UUID tenantId, LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            """
            select s.slot_type, s.slot_key, s.slot_label, s.caliber, s.shop_code, s.platform_code
              from reporting_affiliate_marketing_slot s
             where s.tenant_id = ?
               and ( exists (select 1 from reporting_affiliate_marketing_fact f
                              where f.tenant_id = s.tenant_id and f.slot_type = s.slot_type
                                and f.slot_key = s.slot_key and f.stat_date >= ? and f.stat_date <= ?)
                     or exists (select 1 from reporting_affiliate_marketing_attribution_fact a
                              where a.tenant_id = s.tenant_id and a.slot_type = s.slot_type
                                and a.slot_key = s.slot_key and a.stat_date >= ? and a.stat_date <= ?) )
             order by s.slot_type, s.slot_label
            """,
            tenantId,
            from,
            to,
            from,
            to);
    var out = new ArrayList<SlotView>();
    for (var row : rows) {
      out.add(
          new SlotView(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class)));
    }
    return List.copyOf(out);
  }

  /** 载入窗口内的事实（**按 (营销位, 归因规则, 指标, 币种) 聚合**）。 */
  private Map<String, List<Agg>> loadFacts(UUID tenantId, LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            """
            select slot_type, slot_key, attribution_rule_code, metric_code, currency,
                   sum(value) as value, sum(quantity) as quantity
              from reporting_affiliate_marketing_fact
             where tenant_id = ? and stat_date >= ? and stat_date <= ?
             group by slot_type, slot_key, attribution_rule_code, metric_code, currency
            """,
            tenantId,
            from,
            to);
    var out = new LinkedHashMap<String, List<Agg>>();
    for (var row : rows) {
      var slotType = row.get(0, String.class);
      var slotKey = row.get(1, String.class);
      var ruleCode = row.get(2, String.class);
      out.computeIfAbsent(rowKey(slotType, slotKey, ruleCode), k -> new ArrayList<>())
          .add(
              new Agg(
                  row.get(3, String.class),
                  row.get(4, String.class),
                  row.get(5, BigDecimal.class),
                  row.get(6, BigDecimal.class)));
    }
    return out;
  }

  /** 载入窗口内**实际用到的归因规则**（规则快照 + 记录条数）。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingRuleUsage> ruleUsages(
      UUID tenantId, LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            """
            select rule_code, attribution_model, pool_weight_basis, attribution_window_days,
                   refund_treatment, commission_basis, count(*)
              from reporting_affiliate_marketing_attribution_fact
             where tenant_id = ? and stat_date >= ? and stat_date <= ?
             group by rule_code, attribution_model, pool_weight_basis, attribution_window_days,
                      refund_treatment, commission_basis
             order by rule_code
            """,
            tenantId,
            from,
            to);
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingRuleUsage>();
    for (var row : rows) {
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleUsage(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, Integer.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, Integer.class),
              "🔴 这是**上游归因记录自带的那份规则快照**，**不是**「当前生效规则」—— "
                  + "规则改了以后历史这一格仍然说得清它是怎么算出来的。"));
    }
    return List.copyOf(out);
  }

  /** 拼装营销位效果行（**按 (营销位, 归因规则) 分列**）。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow> slotRows(
      List<SlotView> slots,
      Map<String, List<Agg>> facts,
      List<ReportingAffiliateMarketingModels.AffiliateMarketingRuleUsage> rules,
      Map<String, Definition> definitions) {
    var ruleByCode = new LinkedHashMap<String, ReportingAffiliateMarketingModels.AffiliateMarketingRuleUsage>();
    for (var rule : rules) ruleByCode.put(rule.ruleCode(), rule);

    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow>();
    for (var slot : slots) {
      var slotType = ReportingAffiliateMarketingCatalog.SlotType.valueOf(slot.slotType());
      // 这个营销位在窗口内出现过的归因规则（**行键的一部分**）。
      var ruleCodes = new LinkedHashSet<String>();
      for (var entry : facts.entrySet()) {
        var parts = entry.getKey().split("\\|", 3);
        if (parts.length < 3) continue;
        if (!slot.slotType().equals(parts[0]) || !slot.slotKey().equals(parts[1])) continue;
        ruleCodes.add(parts[2]);
      }
      if (ruleCodes.isEmpty()) ruleCodes.add("");

      for (var ruleCode : ruleCodes) {
        var aggs = facts.getOrDefault(rowKey(slot.slotType(), slot.slotKey(), ruleCode), List.of());
        var rule = ruleByCode.get(ruleCode);
        out.add(
            new ReportingAffiliateMarketingModels.AffiliateMarketingSlotRow(
                slot.slotType(),
                slotType.displayName(),
                slot.slotKey(),
                slot.slotLabel(),
                slot.caliber(),
                caliberName(slot.caliber()),
                slot.shopCode(),
                slot.platformCode(),
                ruleCode,
                rule == null ? "" : rule.attributionModel(),
                rule == null ? "" : rule.poolWeightBasis(),
                rule == null ? null : rule.attributionWindowDays(),
                rule == null ? "" : rule.refundTreatment(),
                rule == null ? "" : rule.commissionBasis(),
                rule == null ? 0 : rule.recordCount(),
                metricValues(slotType, aggs, definitions)));
      }
    }
    return List.copyOf(out);
  }

  // ==========================================================================
  // 指标取值
  // ==========================================================================

  private Map<String, Definition> definitionMap(UUID tenantId) {
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_aware, attribution_bound, available,
                   owner_module, description
              from reporting_affiliate_marketing_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    var out = new LinkedHashMap<String, Definition>();
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Definition(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, Boolean.class),
              row.get(7, Boolean.class),
              row.get(8, Boolean.class),
              row.get(9, String.class),
              row.get(10, String.class)));
    }
    return out;
  }

  /** 一个指标的口径定义（查询期读出来的形状）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      boolean attributionBound,
      boolean available,
      String ownerModule,
      String description) {}

  /** 口径目录的展示形态。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricDefinitionView> definitionViews(
      Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingMetricDefinitionView>();
    for (var definition : definitions.values()) {
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingMetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.currencyAware(),
              definition.attributionBound(),
              definition.available(),
              definition.ownerModule(),
              definition.description()));
    }
    return List.copyOf(out);
  }

  /** 一个营销位行上的逐指标取值。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue> metricValues(
      ReportingAffiliateMarketingCatalog.SlotType slotType, List<Agg> aggs, Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue>();
    for (var definition : definitions.values()) {
      var metricCode = definition.metricCode();
      // 🔴 **本轮不可用**：这一类营销位没有这个口径（**不是 0**）。
      if (!ReportingAffiliateMarketingCatalog.computable(slotType, metricCode)) {
        out.add(
            new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
                metricCode,
                definition.displayName(),
                definition.unit(),
                "",
                null,
                null,
                "NOT_AVAILABLE",
                NOT_AVAILABLE_NOTE
                    + "（"
                    + ReportingAffiliateMarketingCatalog.slotTypeNote(slotType)
                    + "）"));
        continue;
      }
      if ("SUM".equals(definition.aggregation())) {
        out.addAll(sumValues(definition, aggs));
      } else {
        out.addAll(ratioValues(definition, aggs, definitions));
      }
    }
    return List.copyOf(out);
  }

  /** 汇总型指标的取值（**涉币的按币种分列；与货币无关的跨币种合计**）。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue> sumValues(
      Definition definition, List<Agg> aggs) {
    var mine = aggs.stream().filter(a -> definition.metricCode().equals(a.metricCode())).toList();
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue>();
    if (definition.currencyAware()) {
      // 🔴 **绝不跨币种相加**：金额按币种分别报。
      var currencies = new LinkedHashSet<String>();
      for (var agg : mine) currencies.add(agg.currency());
      if (currencies.isEmpty()) {
        out.add(sumValue(definition, "", null));
        return out;
      }
      for (var currency : currencies) {
        BigDecimal value = null;
        BigDecimal quantity = null;
        for (var agg : mine) {
          if (!currency.equals(agg.currency())) continue;
          value = value == null ? agg.value() : value.add(agg.value());
          quantity = quantity == null ? agg.quantity() : quantity.add(agg.quantity());
        }
        out.add(sumValue(definition, currency, value, quantity));
      }
      return out;
    }
    // 与货币无关（曝光 / 点击 / 订单数）⇒ 跨币种合计（件数相加不会把两种货币的钱加起来）。
    BigDecimal value = null;
    BigDecimal quantity = null;
    for (var agg : mine) {
      value = value == null ? agg.value() : value.add(agg.value());
      quantity = quantity == null ? agg.quantity() : quantity.add(agg.quantity());
    }
    out.add(sumValue(definition, "", value, quantity));
    return out;
  }

  private ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue sumValue(
      Definition definition, String currency, BigDecimal value) {
    return sumValue(definition, currency, value, null);
  }

  private ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue sumValue(
      Definition definition, String currency, BigDecimal value, BigDecimal quantity) {
    boolean isCurrency = "CURRENCY".equals(definition.unit());
    // 🔴 **可用性按单位判定**：金额型看金额、数量型看数量 —— 不能用「两个都 null」来判，
    //   否则金额型指标的金额缺失会被数量列的 0 顶成「可用」。
    var present = isCurrency ? value : quantity;
    return new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
        definition.metricCode(),
        definition.displayName(),
        definition.unit(),
        currency,
        isCurrency ? value : null,
        isCurrency ? null : quantity,
        present == null ? "NO_DATA" : "AVAILABLE",
        present == null ? NO_DATA_NOTE : "");
  }

  /**
   * 派生型指标的取值（**只在查询期算** ⇒ 落库就成了第二个真相）。
   *
   * <p>🔴 <b>分子分母必须同币种</b>：涉币的派生指标按<b>同一个币种桶</b>取分子分母。
   */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue> ratioValues(
      Definition definition, List<Agg> aggs, Map<String, Definition> definitions) {
    var numerator = definitions.get(definition.numeratorMetricCode());
    var denominator = definitions.get(definition.denominatorMetricCode());
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue>();
    if (numerator == null || denominator == null) {
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              "",
              null,
              null,
              "NOT_COMPARABLE",
              "口径表里缺分子或分母指标，口径不成立。"));
      return out;
    }
    if (definition.currencyAware()) {
      // 🔴 **按同一个币种桶取分子分母** ⇒ 币种不同的行**不能相除**。
      var currencies = new LinkedHashSet<String>();
      for (var agg : aggs) {
        if (numerator.metricCode().equals(agg.metricCode())
            || denominator.metricCode().equals(agg.metricCode())) {
          currencies.add(agg.currency());
        }
      }
      if (currencies.isEmpty()) {
        out.add(
            new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
                definition.metricCode(),
                definition.displayName(),
                definition.unit(),
                "",
                null,
                null,
                "NO_DATA",
                NO_DATA_NOTE));
        return out;
      }
      for (var currency : currencies) {
        var numeratorValue = sumOf(aggs, numerator, currency);
        var denominatorValue = sumOf(aggs, denominator, currency);
        if (numeratorValue == null || denominatorValue == null) {
          out.add(
              new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
                  definition.metricCode(),
                  definition.displayName(),
                  definition.unit(),
                  currency,
                  null,
                  null,
                  "NOT_COMPARABLE",
                  "🔴 分子或分母在这个币种（"
                      + currency
                      + "）上没有事实 ⇒ **不可算**（不是 0）。"
                      + "分子分母必须**同币种**。"));
          continue;
        }
        if (denominatorValue.signum() == 0) {
          out.add(
              new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
                  definition.metricCode(),
                  definition.displayName(),
                  definition.unit(),
                  currency,
                  null,
                  null,
                  "NOT_COMPARABLE",
                  NOT_COMPARABLE_NOTE));
          continue;
        }
        var value =
            numeratorValue.divide(denominatorValue, MONEY_SCALE, RoundingMode.HALF_UP);
        out.add(
            new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
                definition.metricCode(),
                definition.displayName(),
                definition.unit(),
                currency,
                value,
                null,
                "AVAILABLE",
                "派生值 = "
                    + numerator.metricCode()
                    + " ÷ "
                    + denominator.metricCode()
                    + "（**同币种**、**同一条归因规则**，查询期算）。"));
      }
      return out;
    }
    // 与货币无关的派生指标（点击率）：分子分母都取**跨币种合计**。
    var numeratorValue = crossSum(aggs, numerator);
    var denominatorValue = crossSum(aggs, denominator);
    if (numeratorValue == null || denominatorValue == null) {
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              "",
              null,
              null,
              "NO_DATA",
              NO_DATA_NOTE));
      return out;
    }
    if (denominatorValue.signum() == 0) {
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              "",
              null,
              null,
              "NOT_COMPARABLE",
              NOT_COMPARABLE_NOTE));
      return out;
    }
    out.add(
        new ReportingAffiliateMarketingModels.AffiliateMarketingMetricValue(
            definition.metricCode(),
            definition.displayName(),
            definition.unit(),
            "",
            null,
            numeratorValue.divide(denominatorValue, PERCENT_SCALE, RoundingMode.HALF_UP),
            "AVAILABLE",
            "派生值 = "
                + numerator.metricCode()
                + " ÷ "
                + denominator.metricCode()
                + "（查询期算）。"));
    return out;
  }

  /** 某个指标在某个币种桶里的合计（**金额型取金额列，数量型取数量列**）。 */
  private static BigDecimal sumOf(List<Agg> aggs, Definition definition, String currency) {
    BigDecimal total = null;
    for (var agg : aggs) {
      if (!definition.metricCode().equals(agg.metricCode())) continue;
      if (!currency.equals(agg.currency())) continue;
      var amount = "CURRENCY".equals(definition.unit()) ? agg.value() : agg.quantity();
      total = total == null ? amount : total.add(amount);
    }
    return total;
  }

  /** 某个指标的跨币种合计（**只用于与货币无关的量**）。 */
  private static BigDecimal crossSum(List<Agg> aggs, Definition definition) {
    BigDecimal total = null;
    for (var agg : aggs) {
      if (!definition.metricCode().equals(agg.metricCode())) continue;
      var amount = "CURRENCY".equals(definition.unit()) ? agg.value() : agg.quantity();
      total = total == null ? amount : total.add(amount);
    }
    return total;
  }

  // ==========================================================================
  // 辅助
  // ==========================================================================

  /** 四类营销位逐类状态。 */
  private List<ReportingAffiliateMarketingModels.AffiliateMarketingSlotTypeView> slotTypeViews() {
    var out = new ArrayList<ReportingAffiliateMarketingModels.AffiliateMarketingSlotTypeView>();
    for (var slotType : ReportingAffiliateMarketingCatalog.SlotType.values()) {
      var caliber = slotType.caliber();
      out.add(
          new ReportingAffiliateMarketingModels.AffiliateMarketingSlotTypeView(
              slotType.name(),
              slotType.displayName(),
              slotType.deliveredThisRound(),
              caliber == null ? "" : caliber.name(),
              ReportingAffiliateMarketingCatalog.slotTypeOwner(slotType),
              ReportingAffiliateMarketingCatalog.slotTypeNote(slotType)));
    }
    return List.copyOf(out);
  }

  /** 口径说明（页面上常驻显示）。 */
  private List<String> caliberNotes() {
    var notes = new ArrayList<String>();
    notes.add(ReportingAffiliateMarketingCatalog.ATTRIBUTION_RULE_NOTE);
    notes.add(
        "🔴 **两套口径分列、不混算**：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径"
            + "（曝光 / 点击 / 点击率）是**两个统计范围**，各有各的标签，**绝不相加**。");
    notes.add(
        "🔴 **「算不出来」≠「等于 0」**：本轮不可用 / 分母缺失 / 事实不存在，"
            + "一律显示「不可算 / 没有数据」，**不用 0 冒充** —— 假 0 会被读成「这段时间没投放」。");
    notes.add(
        "🔴 **绝不跨币种相加**：金额按币种分别列出；ROI 的分子分母**必须同币种**，"
            + "币种不同的行**不能相除**（那一格显示「不可算」）。");
    notes.add(
        "🔴 **统计日口径**：达人归因口径按**归因发生日（UTC 自然日）**切桶"
            + "（上游归因记录**不带订单日期**）；落地商品口径按**平台自然日**切桶。两者**不是同一个日**。");
    notes.add(
        "**派生值只在查询期算**：ROI / 点击率 / 客单价在口径表里只写**分子分母引用**，"
            + "DB 触发器拒绝给它们落事实行 ⇒ 库里没有第二个真相。");
    return List.copyOf(notes);
  }

  /** 数据更新时间（两张事实表里最近的一次落库时刻）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row =
        sql.fetchOne(
            """
            select max(updated_at) from (
              select updated_at from reporting_affiliate_marketing_fact where tenant_id = ?
              union all
              select updated_at from reporting_affiliate_marketing_attribution_fact where tenant_id = ?
            ) t
            """,
            tenantId,
            tenantId);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  /** 数据口径的显示名。 */
  private static String caliberName(String caliber) {
    if (ReportingAffiliateMarketingCatalog.Caliber.INFLUENCER_ATTRIBUTION.name().equals(caliber)) {
      return "达人归因口径";
    }
    if (ReportingAffiliateMarketingCatalog.Caliber.LANDING_LISTING.name().equals(caliber)) {
      return "落地商品口径";
    }
    return caliber == null ? "" : caliber;
  }

  /** 行键（营销位 + 归因规则）。 */
  private static String rowKey(String slotType, String slotKey, String ruleCode) {
    return slotType + "|" + slotKey + "|" + ruleCode;
  }

  /** 空串 ⇒ null（不把空串当成一个真的筛选值）。 */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
  // ==========================================================================
  // 能力声明的静态事实（**逐条对着上游的授权只读 API 核过**）
  // ==========================================================================

  /**
   * 本服务**真的读过**的上游模块。
   *
   * <p>⚠️ **不列没读过的**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "14.5.2.1 带货归因（channel /channel/attributions/records：**规则快照** + 归因成交金额 / 归因订单数 / 佣金成本 / 币种 / 归因时刻）",
          "4.3.2.1 内容回写（channel /channel/listing-content-writeback/writebacks + .../content-history：**A+ 营销位存在**的证据）",
          "4.5.1.1 平台表现（channel /channel/listing-performance：落地商品口径的曝光 / 点击，平台自然日）");

  /**
   * **确实拿不到**的部分（每条都写清原因、归谁管与当前替代口径）。
   */
  private static final List<ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension>
      NOT_DELIVERED =
          List.of(
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "ADD_TO_CART",
                  "加购",
                  false,
                  "（上游无）",
                  "2.1.2.1",
                  "🔴 平台侧的加购行为数据上游未接入（channel 的表现 / 广告 / 归因三路报表都没有加购列）。"
                      + "CRM 15.6.2.2 只覆盖**独立站购物车**（Shopify 等），与亚马逊等平台的关联营销"
                      + "**不是同一口径**，**不拿它顶替**。当前替代口径：用「曝光 → 点击 → 成交归因」"
                      + "三段漏斗代替，页面上这一格显示「本轮不可用 + 归 2.1.2.1 管」，**不是 0**。"),
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "RELATED_VIDEO",
                  "关联视频营销位",
                  false,
                  "（上游无）",
                  "4.2.1.1",
                  "🔴 上游**没有**关联视频的营销位配置表，也**没有**营销位级的曝光 / 点击 / 归因数据"
                      + "（channel 的内容回写只支持 TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS "
                      + "五类变更，**没有 VIDEO**）。当前替代口径：以**同落地 listing** 的落地页曝光 / "
                      + "点击作参考，但**不计入**关联视频这一类（那会把别人的流量算到它头上）。"),
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "STORE_TRAFFIC",
                  "Store 导流营销位",
                  false,
                  "（上游无）",
                  "2.1.2.1",
                  "🔴 上游**没有**Store 导流的营销位配置，也**没有**导流位的曝光 / 点击 / 归因数据。"
                      + "当前替代口径：以**同落地 listing** 的落地页曝光 / 点击作参考，"
                      + "但**不计入**Store 导流这一类。"),
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "SOCIAL_MEDIA_TRAFFIC",
                  "社交媒体（达人）站外曝光与点击",
                  false,
                  "（上游无）",
                  "2.1.2.1",
                  "🔴 上游的带货归因只给**成交归因**，**没有站外曝光 / 点击** ⇒ 社交媒体营销位的"
                      + "曝光与点击**不可算**（不是 0）；它的点击率与「归因成交 ÷ 点击」这类比值"
                      + "也随之**不可算**。当前替代口径：用**落地商品口径**的曝光 / 点击（同一批 listing "
                      + "的落地页流量）作参考，并**标明口径不同**。"),
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "CONTENT_SLOT_ATTRIBUTION",
                  "内容营销位（A+）的成交归因与投入",
                  false,
                  "（上游无）",
                  "14.5.2.1",
                  "🔴 上游的归因**只覆盖达人带货**，内容营销位（A+ 等）**没有**归因与投入数据 ⇒ "
                      + "那一类的**成交归因与 ROI 不可算**（不是 0）。当前替代口径：用落地页曝光 / "
                      + "点击 + 点击率衡量引流效果，成交侧留待 14 广告的点击归因交付。"),
              new ReportingAffiliateMarketingModels.AffiliateMarketingCapabilityDimension(
                  "ORDER_DATE_ATTRIBUTION",
                  "按订单日期切桶的归因",
                  false,
                  "（上游无）",
                  "14.5.2.1",
                  "🔴 上游的归因记录**不带订单日期**，只带**归因发生时刻** ⇒ 本模块的统计日按"
                      + "**归因发生日（UTC 自然日）**切桶（口径写在页面上）。归因窗口由上游规则的"
                      + "**归因窗口天数**决定。"));

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote> RULES =
      List.of(
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "ATTRIBUTION_RULE_EXPLICIT",
              ReportingAffiliateMarketingCatalog.ATTRIBUTION_RULE_NOTE),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "RULE_SNAPSHOT_NOT_CURRENT",
              "🔴 **规则快照 ≠ 当前规则**：报表里每一行归因数据带的都是**那条归因记录自带的快照**。"
                  + "上游后来改了规则，历史那一格**不会**跟着变 —— 这正是「各人看到的数不一样」"
                  + "要防的事：口径变了，历史必须还说得清它当时是怎么算的。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "NOT_LAST_CLICK",
              "⚠️ **本模块不提供末次点击 / 首次点击归因**：上游的归因模型词表是「合作全额归因 / "
                  + "活动池分摊」。**广告点击归因（末次点击 vs 首次点击）归 14 广告所有**，"
                  + "本模块既不提供也不假装提供。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "CALIBER_SEPARATION",
              "🔴 **两套口径分列、不混算**：达人归因口径与落地商品口径各有各的指标，"
                  + "每个值旁边都带自己的口径标签；两者的数**不能相加、也不能互相替代**。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "CURRENCY",
              "🔴 **绝不跨币种相加**：金额按币种分别列出；**ROI 的分子分母必须同币种**，"
                  + "币种不同的行**不能相除**（那一格显示「不可算」）。与货币无关的量（曝光 / 点击 / "
                  + "订单数）跨币种合计，币种显示为空串 —— 空串 = 与币种无关，不是「币种未知」。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "NOT_COMPARABLE",
              "🔴 **「算不出来」不等于 0**：分母缺失、分母为 0、事实不存在，"
                  + "一律返回「不可算 / 没有数据」，**不用 0 冒充** —— 假 0 会被读成「这段时间没投放」。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "NOT_AVAILABLE",
              "🔴 **「本轮不可用」也不等于 0**：这一类营销位没有这个口径时显示「本轮不可用 + 归谁管」，"
                  + "**不是 0**，也不是不出现。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "DERIVED_AT_QUERY_TIME",
              "**派生值只在查询期算**：ROI / 点击率 / 归因客单价在口径表里只写**分子分母引用**，"
                  + "DB 触发器拒绝给它们落事实行 ⇒ 库里没有第二个真相。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "BUCKET_BASIS",
              "🔴 **统计日的日历口径也是口径**：达人归因口径按**归因发生日（UTC 自然日）**；"
                  + "落地商品口径按**平台自然日**。两者**不是同一个日**。"),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));

  /** 币种口径说明。 */
  private static final String CURRENCY_NOTE =
      "🔴 涉币指标（归因成交金额 / 营销投入 / ROI / 归因客单价）的事实行**必须**带 3 位大写币种码；"
          + "上游**没给币种**的金额**不落库**（算进某个币种是编造），那一格显示「没有数据」而不是 0。"
          + "与货币无关的量（曝光 / 点击 / 归因订单数）跨币种合计，币种显示为空串 —— "
          + "空串 = 与币种无关，不是「币种未知」。";

  /** 口径边界说明。 */
  private static final String CALIBER_NOTE =
      "🔴 达人归因口径（INFLUENCER_ATTRIBUTION）：成交归因 / 归因订单数 / 佣金投入 / ROI / 归因客单价。"
          + "落地商品口径（LANDING_LISTING）：曝光 / 点击 / 点击率。"
          + "⚠️ 落地商品口径的曝光与点击是**落地页收到的全部流量**（含自然、广告与其它营销位），"
          + "**不是该营销位自身的曝光** —— 页面上的口径标签写的就是这件事。";

}
