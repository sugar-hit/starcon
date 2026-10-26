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
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.2.2 SKU/SPU/Listing 的**读模型**（蓝图「输出」句：商品销量报表 + 层级钻取）。
 *
 * <p>⚠️ **本类不重算口径**：口径（单位 / 聚合方式 / 分子分母 / 分子分母取自哪一层 /
 * 币种口径）全部来自 {@code reporting_product_metric_definition}，本类只**按定义聚合**。
 * 派生型指标（件均价 / 客单价 / 变体销量占比）是**递归求值**出来的：先求分子分母，再相除 ——
 * 而不是在这里写死一句 SQL 公式。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：分母为 0、事实不存在、指标未登记，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：金额按币种分别报。**只有件数与单数**（与货币无关的量）
 * 才给出一个跨币种合计，它的 {@code currency} 是**空串** —— 空串 = 与币种无关，
 * 不是「币种未知」。
 *
 * <p>⚠️ **SPU 是跨平台经营的基本单位**（蓝图关系句）：SPU 层把名下的 SKU 全部合并，
 * 不按平台拆 —— 只按平台 SKU 看会误判商品整体表现。
 */
@Service
public class ReportingProductSalesService {

  /** 每页最多多少行。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingProductSalesRefreshService refresh;

  public ReportingProductSalesService(
      DSLContext sql, TenantTransactions tenants, ReportingProductSalesRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 口径目录
  // ==========================================================================

  /** 一个口径定义（内部形状）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String numeratorDimensionType,
      String denominatorDimensionType,
      String currencyScope,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /** 读该租户的**商品维度指标口径目录**（口径唯一的展示形态）。 */
  public List<ReportingProductSalesModels.ProductMetricDefinitionView> metricDefinitions(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var rows =
              sql.fetch(
                  """
                  select metric_code, display_name, unit, aggregation,
                         numerator_metric_code, denominator_metric_code,
                         numerator_dimension_type, denominator_dimension_type,
                         currency_scope, currency_aware, description, owner_module
                    from reporting_product_metric_definition
                   where tenant_id = ? and enabled
                   order by metric_code
                  """,
                  tenantId);
          var out = new ArrayList<ReportingProductSalesModels.ProductMetricDefinitionView>();
          for (var row : rows) {
            out.add(
                new ReportingProductSalesModels.ProductMetricDefinitionView(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, String.class),
                    Boolean.TRUE.equals(row.get(9, Boolean.class)),
                    row.get(10, String.class),
                    row.get(11, String.class)));
          }
          return out;
        });
  }

  // ==========================================================================
  // 商品销量报表
  // ==========================================================================

  /**
   * 商品销量报表（三层维度 + 真分页 + 真计数）。
   *
   * @param level 维度层：SKU / SPU / LISTING
   * @param mergeSpu 输入「是否合并 SPU」：true ⇒ 按 SPU 合并看（等价于 level=SPU）
   * @param currency 只看某个币种（空 = 全部币种分别列）
   * @param spuKey 只看某个 SPU 名下的商品（空 = 全部）
   * @param listingKey 只看某个 Listing（空 = 全部）
   */
  public ReportingProductSalesModels.ProductSalesResponse report(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String level,
      boolean mergeSpu,
      String currency,
      String spuKey,
      String listingKey,
      int page,
      int size) {
    var effectiveLevel = mergeSpu ? "SPU" : level;
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var dimensions = dimensionMap(tenantId);
          var facts = factMap(tenantId, from, to);
          var listingCounts = listingCountBySku(tenantId);

          var safePage = Math.max(page, 0);
          var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

          var ids = pageDimensionIds(tenantId, from, to, effectiveLevel, currency, spuKey, listingKey,
              safePage, safeSize);
          var total = countDimensionIds(tenantId, from, to, effectiveLevel, currency, spuKey, listingKey);

          var rows = new ArrayList<ReportingProductSalesModels.ProductRow>();
          for (var dimensionId : ids) {
            var dimension = dimensions.get(effectiveLevel + "|" + dimensionId);
            if (dimension == null) continue;
            rows.add(row(dimension, definitions, facts, dimensions, listingCounts, tenantId));
          }

          var unattributed = unattributed(tenantId, from, to, facts, listingCounts);
          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var timezones = timezones(tenantId, from, to);
          var caliberNotes = caliberNotes(effectiveLevel, unattributed, timezones);

          var status = "PROJECTED";
          var statusNote = "";
          String emptyReason = null;
          var emptyNote = "";
          if (rows.isEmpty()) {
            status = "NOT_PROJECTED";
            emptyReason = "NO_FACTS";
            emptyNote =
                "这个租户在 " + from + " ~ " + to + " 这个窗口里没有投影任何商品维度事实 —— "
                    + "请先刷新该窗口的商品维度投影（POST /reporting/product-sales/refresh）。";
            statusNote = emptyNote;
          }

          return new ReportingProductSalesModels.ProductSalesResponse(
              from,
              to,
              effectiveLevel,
              mergeSpu,
              rows,
              total,
              safePage,
              safeSize,
              definitionViews(definitions),
              unattributed,
              caliberNotes,
              timezones,
              dataUpdatedAt,
              "最近一次商品维度投影落库时刻（reporting_product_metric_fact.updated_at 取较晚者）。",
              status,
              statusNote,
              emptyReason,
              emptyNote);
        });
  }

  // ==========================================================================
  // SPU 汇总（多平台多 SKU 合并看）
  // ==========================================================================

  /**
   * SPU 汇总：一个 SPU 一行，名下多平台多 SKU 合并看。
   *
   * <p>🔴 **这是蓝图里「SPU 口径是跨平台经营的基本单位」那一句的落点** ——
   * 一行 SPU 的销量 = 它名下所有 SKU 的销量之和，**不按平台拆**。
   */
  public ReportingProductSalesModels.SpuSummaryResponse spuSummary(
      UUID tenantId, LocalDate from, LocalDate to, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var dimensions = dimensionMap(tenantId);
          var facts = factMap(tenantId, from, to);
          var listingCounts = listingCountBySku(tenantId);
          var platformsBySku = platformsBySku(tenantId);
          var listingsBySku = listingIdsBySku(tenantId);

          var safePage = Math.max(page, 0);
          var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var ids = pageDimensionIds(tenantId, from, to, "SPU", null, null, null, safePage, safeSize);
          var total = countDimensionIds(tenantId, from, to, "SPU", null, null, null);

          var rows = new ArrayList<ReportingProductSalesModels.SpuSummaryRow>();
          for (var dimensionId : ids) {
            var spu = dimensions.get("SPU|" + dimensionId);
            if (spu == null) continue;
            var variants = new ArrayList<ReportingProductSalesModels.SpuVariantSummary>();
            var platforms = new LinkedHashSet<String>();
            var listingIds = new LinkedHashSet<UUID>();
            for (var sku : skusOf(spu, dimensions)) {
              var skuPlatforms = platformsBySku.getOrDefault(sku.dimensionId(), List.of());
              platforms.addAll(skuPlatforms);
              listingIds.addAll(listingsBySku.getOrDefault(sku.dimensionId(), List.of()));
              variants.add(
                  new ReportingProductSalesModels.SpuVariantSummary(
                      sku.dimensionId().toString(),
                      sku.dimensionKey(),
                      sku.dimensionName(),
                      sku.variantSignature(),
                      skuPlatforms,
                      listingCounts.getOrDefault(sku.dimensionId(), 0),
                      metricValues(sku, definitions, facts, dimensions)));
            }
            rows.add(
                new ReportingProductSalesModels.SpuSummaryRow(
                    spu.dimensionId().toString(),
                    spu.dimensionKey(),
                    spu.dimensionName(),
                    variants.size(),
                    new ArrayList<>(platforms),
                    listingIds.size(),
                    currenciesOf(spu, facts),
                    metricValues(spu, definitions, facts, dimensions),
                    variants));
          }

          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var timezones = timezones(tenantId, from, to);
          var status = "PROJECTED";
          var statusNote = "";
          String emptyReason = null;
          var emptyNote = "";
          if (rows.isEmpty()) {
            status = "NOT_PROJECTED";
            emptyReason = "NO_FACTS";
            emptyNote =
                "这个窗口里没有任何 SPU 维度的事实 —— 商品主数据（catalog）与订单（trade）还没投影过来。";
            statusNote = emptyNote;
          }

          return new ReportingProductSalesModels.SpuSummaryResponse(
              from,
              to,
              rows,
              total,
              safePage,
              safeSize,
              definitionViews(definitions),
              List.of(
                  "SPU 汇总把名下**所有平台、所有 SKU** 的销量合并成一行 —— "
                      + "这正是「SPU 口径是跨平台经营的基本单位」的含义："
                      + "只按平台 SKU 看会误判商品整体表现。",
                  "🔴 **金额按币种分别列出**，绝不跨币种相加；只有件数与单数（与货币无关）给跨币种合计。"),
              timezones,
              dataUpdatedAt,
              "最近一次商品维度投影落库时刻（reporting_product_metric_fact.updated_at 取较晚者）。",
              status,
              statusNote,
              emptyReason,
              emptyNote);
        });
  }

  // ==========================================================================
  // 变体销量分布
  // ==========================================================================

  /**
   * 变体销量分布：某个 SPU 名下各变体（SKU）的销量与占比。
   *
   * <p>⚠️ **占比的分母是 SPU 的总销量**，分子是该变体的销量 ——
   * 口径写在 {@code reporting_product_metric_definition}
   * （{@code PRODUCT_VARIANT_SHARE}，分子 SKU 层 / 分母 SPU 层），这里只按定义求值。
   */
  public ReportingProductSalesModels.VariantDistributionResponse variants(
      UUID tenantId, LocalDate from, LocalDate to, String spuKey) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var dimensions = dimensionMap(tenantId);
          var facts = factMap(tenantId, from, to);
          var listingCounts = listingCountBySku(tenantId);
          var platformsBySku = platformsBySku(tenantId);

          var spu = findByKey(dimensions, "SPU", spuKey);
          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var timezones = timezones(tenantId, from, to);

          if (spu == null) {
            return new ReportingProductSalesModels.VariantDistributionResponse(
                from, to, null, spuKey, "",
                null, null, List.of(), definitionViews(definitions),
                List.of("找不到 SPU「" + spuKey + "」—— 商品主数据（catalog）里没有这个 SPU，或它的编码变了。"),
                timezones, dataUpdatedAt,
                "最近一次商品维度投影落库时刻（reporting_product_metric_fact.updated_at 取较晚者）。",
                "NOT_PROJECTED", "SPU 不存在。", "NO_SPU", "找不到这个 SPU。");
          }

          var variants = new ArrayList<ReportingProductSalesModels.VariantRow>();
          var shareTotal = BigDecimal.ZERO;
          var totalQuantity = BigDecimal.ZERO;
          for (var sku : skusOf(spu, dimensions)) {
            var values = metricValues(sku, definitions, facts, dimensions);
            var quantity = valueOf(values, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY);
            var orderCount = valueOf(values, ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT);
            var share = valueOf(values, ReportingProductSalesCatalog.PRODUCT_VARIANT_SHARE);
            if (quantity != null) totalQuantity = totalQuantity.add(quantity);
            if (share != null) shareTotal = shareTotal.add(share);
            var amounts = new ArrayList<ReportingProductSalesModels.CurrencyAmount>();
            for (var value : values) {
              if (!ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT.equals(value.metricCode())) continue;
              if (value.value() == null) continue;
              amounts.add(new ReportingProductSalesModels.CurrencyAmount(value.currency(), value.value()));
            }
            variants.add(
                new ReportingProductSalesModels.VariantRow(
                    sku.dimensionId().toString(),
                    sku.dimensionKey(),
                    sku.dimensionName(),
                    sku.variantSignature(),
                    quantity,
                    amounts,
                    orderCount,
                    share,
                    share == null
                        ? "所属 SPU 的销量为 0 或没有事实 ⇒ 算不出占比（不是 0%）。"
                        : "",
                    platformsBySku.getOrDefault(sku.dimensionId(), List.of()),
                    listingCounts.getOrDefault(sku.dimensionId(), 0),
                    values));
          }
          variants.sort(
              java.util.Comparator.comparing(
                      (ReportingProductSalesModels.VariantRow v) -> v.quantity() == null ? BigDecimal.ZERO : v.quantity())
                  .reversed()
                  .thenComparing(ReportingProductSalesModels.VariantRow::skuKey));

          var status = variants.isEmpty() ? "NOT_PROJECTED" : "PROJECTED";
          String emptyReason = variants.isEmpty() ? "NO_VARIANTS" : null;
          var emptyNote =
              variants.isEmpty()
                  ? "这个 SPU 名下没有投影任何 SKU 维度的事实 —— 商品主数据（catalog）里还没有它的变体，"
                      + "或者订单（trade）还没投影过来。"
                  : "";

          return new ReportingProductSalesModels.VariantDistributionResponse(
              from, to, spu.dimensionId().toString(), spu.dimensionKey(), spu.dimensionName(),
              totalQuantity, shareTotal.setScale(4, RoundingMode.HALF_UP), variants,
              definitionViews(definitions),
              List.of(
                  "变体销量分布按**变体签名**（catalog 的 variant_signature）分组：同一签名的是同一个变体。",
                  "占比 = 该变体销量 ÷ **所属 SPU 的销量**（分母为 0 时算不出来，不是 0%）。",
                  "🔴 **金额按币种分别列出**，绝不跨币种相加；占比只对**件数**做跨币种合计。"),
              timezones, dataUpdatedAt,
              "最近一次商品维度投影落库时刻（reporting_product_metric_fact.updated_at 取较晚者）。",
              status, emptyNote, emptyReason, emptyNote);
        });
  }

  // ==========================================================================
  // 层级钻取
  // ==========================================================================

  /**
   * 层级钻取：某个维度、某个指标的构成明细（哪个订单、哪个 SKU）。
   *
   * <p>🔴 **真分页 + 真计数**（计数与明细同一个分组口径，否则 total 与 rows 对不上）。
   */
  public ReportingProductSalesModels.ProductDrilldownResponse drilldown(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String level,
      String dimensionKey,
      String metricCode,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var dimensions = dimensionMap(tenantId);
          var dimension = findByKey(dimensions, level, dimensionKey);
          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var safePage = Math.max(page, 0);
          var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

          if (dimension == null) {
            return new ReportingProductSalesModels.ProductDrilldownResponse(
                from, to, level, null, dimensionKey, "",
                metricCode == null ? "" : metricCode,
                List.of(), 0, safePage, safeSize, List.of(),
                List.of("找不到维度「" + dimensionKey + "」—— 商品档案里没有它。"),
                dataUpdatedAt,
                "最近一次商品维度投影落库时刻（reporting_product_drilldown_fact.updated_at 取较晚者）。",
                "NOT_PROJECTED", "维度不存在。", "NO_DIMENSION", "找不到这个维度。");
          }

          var metricFilter = " and metric_code = ?";
          // ⚠️ **没指定指标时看「销量」那一组构成明细**（本模块的职责句就是「拆解销量」）。
          //   每条构成明细**同时带件数与金额**，所以看销量的明细也能看到那笔订单的金额。
          //   不指定就返回两组（销量 + 金额）会让同一行订单出现两遍 —— 那是重复，不是信息。
          var effectiveMetricCode =
              metricCode == null || metricCode.isBlank()
                  ? ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY
                  : metricCode;
          var countParams = new ArrayList<Object>();
          countParams.add(tenantId);
          countParams.add(dimension.dimensionType());
          countParams.add(dimension.dimensionId());
          countParams.add(from);
          countParams.add(to);
          countParams.add(effectiveMetricCode);

          var total =
              sql.fetchOne(
                      "select count(*) from reporting_product_drilldown_fact "
                          + "where tenant_id = ? and dimension_type = ? and dimension_id = ? "
                          + "and bucket_date >= ? and bucket_date <= ?" + metricFilter,
                      countParams.toArray())
                  .get(0, Long.class);

          var rows = new ArrayList<ReportingProductSalesModels.ProductDrilldownRow>();
          var skuCodes = new LinkedHashSet<String>();
          var pageParams = new ArrayList<Object>(countParams);
          pageParams.add(safeSize);
          pageParams.add(safePage * safeSize);
          var fetched =
              sql.fetch(
                  """
                  select source_kind, reference_id, reference_label, order_no, occurred_at,
                         sku_code, sku_name, quantity, value, currency, bucket_date, timezone
                    from reporting_product_drilldown_fact
                   where tenant_id = ? and dimension_type = ? and dimension_id = ?
                     and bucket_date >= ? and bucket_date <= ?
                  """
                      + metricFilter
                      + " order by bucket_date desc, reference_id asc limit ? offset ?",
                  pageParams.toArray());
          for (var row : fetched) {
            var skuCode = row.get(5, String.class);
            if (skuCode != null && !skuCode.isBlank()) skuCodes.add(skuCode);
            rows.add(
                new ReportingProductSalesModels.ProductDrilldownRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, OffsetDateTime.class),
                    skuCode == null ? "" : skuCode,
                    row.get(6, String.class) == null ? "" : row.get(6, String.class),
                    row.get(7, BigDecimal.class),
                    row.get(8, BigDecimal.class),
                    row.get(9, String.class),
                    row.get(10, LocalDate.class),
                    row.get(11, String.class)));
          }

          var status = rows.isEmpty() ? "NOT_PROJECTED" : "PROJECTED";
          String emptyReason = rows.isEmpty() ? "NO_DRILLDOWN" : null;
          var emptyNote =
              rows.isEmpty()
                  ? "这个维度在这个窗口里没有构成明细 —— 它的数字可能来自别的窗口，或还没有投影明细。"
                  : "";

          return new ReportingProductSalesModels.ProductDrilldownResponse(
              from, to, dimension.dimensionType(), dimension.dimensionId().toString(),
              dimension.dimensionKey(), dimension.dimensionName(),
              effectiveMetricCode,
              rows, total == null ? 0 : total, safePage, safeSize, new ArrayList<>(skuCodes),
              List.of(
                  "构成明细记着**哪张订单的哪一行**贡献了这笔数字（订单号 + 行序号）—— "
                      + "同一张订单里同一款 SKU 出现两行时，两行分别列出，不会被合并。"),
              dataUpdatedAt,
              "最近一次商品维度投影落库时刻（reporting_product_drilldown_fact.updated_at 取较晚者）。",
              status, emptyNote, emptyReason, emptyNote);
        });
  }

  // ==========================================================================
  // 行组装
  // ==========================================================================

  /** 组装报表的一行。 */
  private ReportingProductSalesModels.ProductRow row(
      Dimension dimension,
      Map<String, Definition> definitions,
      Map<String, Fact> facts,
      Map<String, Dimension> dimensions,
      Map<UUID, Integer> listingCounts,
      UUID tenantId) {
    var platforms = platformsOf(dimension, dimensions, tenantId);
    var skuCount = "SPU".equals(dimension.dimensionType()) ? skusOf(dimension, dimensions).size() : 0;
    if ("LISTING".equals(dimension.dimensionType())) {
      skuCount = listingSkuCount(dimension, tenantId);
    }
    var listingCount =
        "LISTING".equals(dimension.dimensionType())
            ? 1
            : listingsOf(dimension, dimensions, tenantId).size();
    var attributionStatus = "NOT_APPLICABLE";
    var attributionNote = "";
    if ("LISTING".equals(dimension.dimensionType())) {
      var counts = listingSkuCounts(dimension, tenantId);
      var shared = counts.stream().filter(c -> c > 1).count();
      var none = counts.stream().filter(c -> c == 0).count();
      if (shared > 0) {
        attributionStatus = "SHARED_SKU";
        attributionNote =
            "该 Listing 上的 " + shared + " 个内部 SKU 还挂在别的 Listing 上 ⇒ "
                + "这些 SKU 的销量**无法唯一归属**到任何 Listing，本模块不摊给任何一个。";
      } else if (none > 0) {
        attributionStatus = "NO_LISTING";
        attributionNote = "该 Listing 上的 " + none + " 个内部 SKU 没有任何 Listing 绑定（映射缺失）。";
      } else {
        attributionStatus = "ATTRIBUTED";
        attributionNote = "该 Listing 上的每个内部 SKU 都只挂在这一个 Listing 上 ⇒ 销量可唯一归属。";
      }
    }
    return new ReportingProductSalesModels.ProductRow(
        dimension.dimensionType(),
        dimension.dimensionId().toString(),
        dimension.dimensionKey(),
        dimension.dimensionName(),
        dimension.parentType() == null ? "" : dimension.parentType(),
        dimension.parentKey() == null ? "" : dimension.parentKey(),
        dimension.parentName() == null ? "" : dimension.parentName(),
        dimension.variantSignature(),
        platforms,
        skuCount,
        listingCount,
        currenciesOf(dimension, facts),
        metricValues(dimension, definitions, facts, dimensions),
        attributionStatus,
        attributionNote);
  }

  // ==========================================================================
  // 指标求值
  // ==========================================================================

  /** 一条事实桶（金额合计与件数合计）。 */
  private record Fact(BigDecimal value, BigDecimal quantity) {}

  /**
   * 求一个维度上所有指标的取值。
   *
   * <p>⚠️ **币种口径决定报告的粒度**：
   * {@code SAME} ⇒ 每个币种一条（金额类指标永远走这一支）；
   * {@code ANY} ⇒ 一条跨币种合计（只给件数 / 单数这类与货币无关的量，currency 是空串）。
   */
  private List<ReportingProductSalesModels.ProductMetricValue> metricValues(
      Dimension dimension,
      Map<String, Definition> definitions,
      Map<String, Fact> facts,
      Map<String, Dimension> dimensions) {
    var out = new ArrayList<ReportingProductSalesModels.ProductMetricValue>();
    var currencies = currenciesOf(dimension, facts);
    for (var definition : definitions.values()) {
      switch (definition.aggregation()) {
        case "SUM" -> {
          var perCurrency = buckets(dimension, definition, facts);
          if (perCurrency.isEmpty()) {
            out.add(
                new ReportingProductSalesModels.ProductMetricValue(
                    definition.metricCode(), "", null, "NO_DATA",
                    "这个窗口里该维度没有这个指标的事实。"));
            continue;
          }
          if ("ANY".equals(definition.currencyScope())) {
            var total = BigDecimal.ZERO;
            for (var value : perCurrency.values()) total = total.add(value);
            out.add(
                new ReportingProductSalesModels.ProductMetricValue(
                    definition.metricCode(), "", total, "AVAILABLE",
                    "跨币种合计（件数 / 单数与货币无关）。"));
          } else {
            for (var entry : perCurrency.entrySet()) {
              out.add(
                  new ReportingProductSalesModels.ProductMetricValue(
                      definition.metricCode(), entry.getKey(), entry.getValue(), "AVAILABLE", ""));
            }
          }
        }
        case "DERIVED_RATIO" -> {
          var numeratorDefinition = definitions.get(definition.numeratorMetricCode());
          var denominatorDefinition = definitions.get(definition.denominatorMetricCode());
          if (numeratorDefinition == null || denominatorDefinition == null) continue;
          var numeratorDimension = resolve(dimension, definition.numeratorDimensionType(), dimensions);
          var denominatorDimension = resolve(dimension, definition.denominatorDimensionType(), dimensions);
          // ⚠️ 该层不适用（例如变体销量占比只在 SKU 层有定义）⇒ **不出现这个指标**，
          //   而不是报一个「算不出来」的假指标。
          if (numeratorDimension == null || denominatorDimension == null) continue;
          var numerator = buckets(numeratorDimension, numeratorDefinition, facts);
          var denominator = buckets(denominatorDimension, denominatorDefinition, facts);
          if ("ANY".equals(definition.currencyScope())) {
            var numeratorTotal = BigDecimal.ZERO;
            var denominatorTotal = BigDecimal.ZERO;
            var hasNumerator = false;
            var hasDenominator = false;
            for (var value : numerator.values()) {
              numeratorTotal = numeratorTotal.add(value);
              hasNumerator = true;
            }
            for (var value : denominator.values()) {
              denominatorTotal = denominatorTotal.add(value);
              hasDenominator = true;
            }
            if (!hasNumerator || !hasDenominator || denominatorTotal.signum() == 0) {
              out.add(
                  new ReportingProductSalesModels.ProductMetricValue(
                      definition.metricCode(), "", null, "NOT_COMPARABLE",
                      "分母（所属 SPU 的销量）为 0 或没有事实 ⇒ 算不出占比（不是 0%）。"));
              continue;
            }
            out.add(
                new ReportingProductSalesModels.ProductMetricValue(
                    definition.metricCode(), "", ratio(numeratorTotal, denominatorTotal, definition),
                    "AVAILABLE", ""));
          } else {
            for (var currency : currencies) {
              var numeratorValue = numerator.get(currency);
              var denominatorValue = denominator.get(currency);
              if (numeratorValue == null || denominatorValue == null || denominatorValue.signum() == 0) {
                out.add(
                    new ReportingProductSalesModels.ProductMetricValue(
                        definition.metricCode(), currency, null, "NOT_COMPARABLE",
                        "该币种下分母为 0 或没有事实 ⇒ 算不出来（不是 0）。"));
                continue;
              }
              out.add(
                  new ReportingProductSalesModels.ProductMetricValue(
                      definition.metricCode(), currency,
                      ratio(numeratorValue, denominatorValue, definition), "AVAILABLE", ""));
            }
          }
        }
        default -> {}
      }
    }
    return out;
  }

  /** 求值：分子 / 分母取自哪一层（空 = 本行；否则沿父链向上找那一层）。 */
  private Dimension resolve(
      Dimension dimension, String targetType, Map<String, Dimension> dimensions) {
    if (targetType == null || targetType.isBlank()) return dimension;
    if (targetType.equals(dimension.dimensionType())) return dimension;
    var current = dimension;
    for (int depth = 0; depth < 3; depth++) {
      if (current.parentType() == null || current.parentId() == null) return null;
      var parent = dimensions.get(current.parentType() + "|" + current.parentId());
      if (parent == null) return null;
      if (targetType.equals(parent.dimensionType())) return parent;
      current = parent;
    }
    return null;
  }

  /** 某个维度某个指标的事实（按币种分桶）。 */
  private Map<String, BigDecimal> buckets(
      Dimension dimension, Definition definition, Map<String, Fact> facts) {
    var out = new LinkedHashMap<String, BigDecimal>();
    var prefix = dimension.dimensionType() + "|" + dimension.dimensionId() + "|";
    for (var entry : facts.entrySet()) {
      var key = entry.getKey();
      if (!key.startsWith(prefix)) continue;
      // ⚠️ 事实索引的键是「维度类型 | 维度 id | 指标码 | 币种」——
      //   指标码在**倒数第二段**，不是最后一段（最后一段是币种）。
      var parts = key.split("\\|");
      if (parts.length < 4) continue;
      if (!parts[2].equals(definition.metricCode())) continue;
      var currency = parts[3];
      var fact = entry.getValue();
      // ⚠️ **件数走 quantity 列（numeric(19,6)），金额 / 单数走 value 列（numeric(24,4)）** ——
      //   件数塞进 4 位小数会丢精度。
      var value = "QUANTITY".equals(definition.unit()) ? fact.quantity() : fact.value();
      out.merge(currency, value == null ? BigDecimal.ZERO : value, BigDecimal::add);
    }
    return out;
  }

  /** 派生比（PERCENT 换算成百分数）。 */
  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, Definition definition) {
    var result = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    if ("PERCENT".equals(definition.unit())) {
      return result.movePointRight(2).setScale(4, RoundingMode.HALF_UP);
    }
    return result.setScale(4, RoundingMode.HALF_UP);
  }

  // ==========================================================================
  // 数据读取
  // ==========================================================================

  private Map<String, Definition> definitionMap(UUID tenantId) {
    var out = new LinkedHashMap<String, Definition>();
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation,
                   numerator_metric_code, denominator_metric_code,
                   numerator_dimension_type, denominator_dimension_type,
                   currency_scope, currency_aware, description, owner_module
              from reporting_product_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
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
              row.get(6, String.class),
              row.get(7, String.class),
              row.get(8, String.class),
              Boolean.TRUE.equals(row.get(9, Boolean.class)),
              row.get(10, String.class),
              row.get(11, String.class)));
    }
    return out;
  }

  private List<ReportingProductSalesModels.ProductMetricDefinitionView> definitionViews(
      Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingProductSalesModels.ProductMetricDefinitionView>();
    for (var definition : definitions.values()) {
      out.add(
          new ReportingProductSalesModels.ProductMetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.numeratorDimensionType(),
              definition.denominatorDimensionType(),
              definition.currencyScope(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return out;
  }

  /** 一个商品维度档案。 */
  private record Dimension(
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      String dimensionName,
      String parentType,
      UUID parentId,
      String parentKey,
      String parentName,
      String variantSignature,
      String platformCode,
      String shopCode,
      String externalItemId,
      String mappingStatus) {}

  private Map<String, Dimension> dimensionMap(UUID tenantId) {
    var out = new LinkedHashMap<String, Dimension>();
    var rows =
        sql.fetch(
            """
            select dimension_type, dimension_id, dimension_key, dimension_name,
                   parent_dimension_type, parent_dimension_id, parent_dimension_key,
                   variant_signature, platform_code, shop_code, external_item_id, mapping_status
              from reporting_product_dimension
             where tenant_id = ?
            """,
            tenantId);
    for (var row : rows) {
      var dimensionType = row.get(0, String.class);
      var dimensionId = row.get(1, UUID.class);
      var parentType = row.get(4, String.class);
      var parentId = row.get(5, UUID.class);
      out.put(
          dimensionType + "|" + dimensionId,
          new Dimension(
              dimensionType,
              dimensionId,
              row.get(2, String.class),
              row.get(3, String.class) == null ? "" : row.get(3, String.class),
              parentType,
              parentId,
              row.get(6, String.class),
              "",
              row.get(7, String.class) == null ? "" : row.get(7, String.class),
              row.get(8, String.class) == null ? "" : row.get(8, String.class),
              row.get(9, String.class) == null ? "" : row.get(9, String.class),
              row.get(10, String.class) == null ? "" : row.get(10, String.class),
              row.get(11, String.class) == null ? "" : row.get(11, String.class)));
    }
    // 父层名字（SKU 行的父是 SPU）—— 第二遍补，避免自引用顺序问题。
    for (var dimension : out.values()) {
      if (dimension.parentType() == null || dimension.parentId() == null) continue;
      var parent = out.get(dimension.parentType() + "|" + dimension.parentId());
      if (parent == null) continue;
      out.put(
          dimension.dimensionType() + "|" + dimension.dimensionId(),
          new Dimension(
              dimension.dimensionType(),
              dimension.dimensionId(),
              dimension.dimensionKey(),
              dimension.dimensionName(),
              dimension.parentType(),
              dimension.parentId(),
              dimension.parentKey(),
              parent.dimensionName(),
              dimension.variantSignature(),
              dimension.platformCode(),
              dimension.shopCode(),
              dimension.externalItemId(),
              dimension.mappingStatus()));
    }
    return out;
  }

  /** 读一个窗口内的事实（按 维度 + 指标 + 币种 建索引）。 */
  private Map<String, Fact> factMap(UUID tenantId, LocalDate from, LocalDate to) {
    var out = new LinkedHashMap<String, Fact>();
    var rows =
        sql.fetch(
            """
            select dimension_type, dimension_id, metric_code, currency,
                   sum(value), sum(quantity)
              from reporting_product_metric_fact
             where tenant_id = ? and bucket_date >= ? and bucket_date <= ?
             group by dimension_type, dimension_id, metric_code, currency
            """,
            tenantId,
            from,
            to);
    for (var row : rows) {
      out.put(
          row.get(0, String.class) + "|" + row.get(1, UUID.class) + "|" + row.get(2, String.class)
              + "|" + row.get(3, String.class),
          new Fact(row.get(4, BigDecimal.class), row.get(5, BigDecimal.class)));
    }
    return out;
  }

  // ==========================================================================
  // 维度分页
  // ==========================================================================

  /** 一段筛选条件（SQL 片段 + 参数）。 */
  private record Filtered(String where, List<Object> params) {}

  private Filtered filter(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String level,
      String currency,
      String spuKey,
      String listingKey) {
    var where = new StringBuilder();
    var params = new ArrayList<Object>();
    where.append(" f.tenant_id = ? and f.dimension_type = ? and f.bucket_date >= ? and f.bucket_date <= ?");
    params.add(tenantId);
    params.add(level);
    params.add(from);
    params.add(to);
    if (currency != null && !currency.isBlank()) {
      where.append(" and f.currency = ?");
      params.add(currency.trim().toUpperCase());
    }
    if (spuKey != null && !spuKey.isBlank()) {
      if ("LISTING".equals(level)) {
        where.append(
            " and f.dimension_id in (select l.listing_id from reporting_product_listing_sku l"
                + " join reporting_product_dimension sd on sd.tenant_id = l.tenant_id"
                + " and sd.dimension_type = 'SKU' and sd.dimension_id = l.sku_id"
                + " where l.tenant_id = ? and sd.parent_dimension_key = ?)");
        params.add(tenantId);
        params.add(spuKey.trim());
      } else if ("SKU".equals(level)) {
        where.append(
            " and f.dimension_id in (select d2.dimension_id from reporting_product_dimension d2"
                + " where d2.tenant_id = ? and d2.dimension_type = 'SKU' and d2.parent_dimension_key = ?)");
        params.add(tenantId);
        params.add(spuKey.trim());
      } else {
        where.append(
            " and f.dimension_id in (select d3.dimension_id from reporting_product_dimension d3"
                + " where d3.tenant_id = ? and d3.dimension_type = 'SPU' and d3.dimension_key = ?)");
        params.add(tenantId);
        params.add(spuKey.trim());
      }
    }
    if (listingKey != null && !listingKey.isBlank() && "LISTING".equals(level)) {
      where.append(
          " and f.dimension_id in (select d4.dimension_id from reporting_product_dimension d4"
              + " where d4.tenant_id = ? and d4.dimension_type = 'LISTING' and d4.dimension_key = ?)");
      params.add(tenantId);
      params.add(listingKey.trim());
    }
    return new Filtered(where.toString(), params);
  }

  /** 取一页维度 id（按销量倒序；跨币种只对**件数**排序是合法的）。 */
  private List<UUID> pageDimensionIds(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String level,
      String currency,
      String spuKey,
      String listingKey,
      int page,
      int size) {
    var filtered = filter(tenantId, from, to, level, currency, spuKey, listingKey);
    var params = new ArrayList<Object>(filtered.params());
    params.add(size);
    params.add(page * size);
    var rows =
        sql.fetch(
            """
            select f.dimension_id
              from reporting_product_metric_fact f
             where %s
             group by f.dimension_id, f.dimension_key
             order by sum(f.quantity) desc, f.dimension_key asc
             limit ? offset ?
            """
                .formatted(filtered.where()),
            params.toArray());
    var out = new ArrayList<UUID>();
    for (var row : rows) out.add(row.get(0, UUID.class));
    return out;
  }

  /** 维度总数（真计数，与明细同一个分组口径）。 */
  private long countDimensionIds(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String level,
      String currency,
      String spuKey,
      String listingKey) {
    var filtered = filter(tenantId, from, to, level, currency, spuKey, listingKey);
    var row =
        sql.fetchOne(
            "select count(*) from (select f.dimension_id from reporting_product_metric_fact f where "
                + filtered.where()
                + " group by f.dimension_id) t",
            filtered.params().toArray());
    return row == null ? 0L : row.get(0, Long.class);
  }

  // ==========================================================================
  // 辅助
  // ==========================================================================

  /** 某个 SKU 挂在几个 Listing 上。 */
  private Map<UUID, Integer> listingCountBySku(UUID tenantId) {
    var out = new LinkedHashMap<UUID, Integer>();
    var rows =
        sql.fetch(
            "select sku_id, count(*) from reporting_product_listing_sku where tenant_id = ? group by sku_id",
            tenantId);
    for (var row : rows) out.put(row.get(0, UUID.class), row.get(1, Integer.class));
    return out;
  }

  /** 某个 SKU 挂在哪些 Listing 上（id 列表）。 */
  private Map<UUID, List<UUID>> listingIdsBySku(UUID tenantId) {
    var out = new LinkedHashMap<UUID, List<UUID>>();
    var rows =
        sql.fetch(
            "select sku_id, listing_id from reporting_product_listing_sku where tenant_id = ? order by listing_id",
            tenantId);
    for (var row : rows) {
      out.computeIfAbsent(row.get(0, UUID.class), k -> new ArrayList<>()).add(row.get(1, UUID.class));
    }
    return out;
  }

  /** 某个 SKU 覆盖哪些平台。 */
  private Map<UUID, List<String>> platformsBySku(UUID tenantId) {
    var out = new LinkedHashMap<UUID, List<String>>();
    var rows =
        sql.fetch(
            "select sku_id, platform_code from reporting_product_listing_sku where tenant_id = ?"
                + " and platform_code <> '' order by sku_id, platform_code",
            tenantId);
    for (var row : rows) {
      var list = out.computeIfAbsent(row.get(0, UUID.class), k -> new ArrayList<>());
      var platform = row.get(1, String.class);
      if (!list.contains(platform)) list.add(platform);
    }
    return out;
  }

  /** 某个 Listing 挂了几个 SKU（以及各自挂在几个 Listing 上）。 */
  private List<Integer> listingSkuCounts(Dimension listing, UUID tenantId) {
    var out = new ArrayList<Integer>();
    var rows =
        sql.fetch(
            """
            select l.sku_id, (select count(*) from reporting_product_listing_sku x
                               where x.tenant_id = l.tenant_id and x.sku_id = l.sku_id)
              from reporting_product_listing_sku l
             where l.tenant_id = ? and l.listing_id = ?
            """,
            tenantId,
            listing.dimensionId());
    for (var row : rows) out.add(row.get(1, Integer.class));
    return out;
  }

  /** 某个 Listing 挂了几个 SKU。 */
  private int listingSkuCount(Dimension listing, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select count(*) from reporting_product_listing_sku where tenant_id = ? and listing_id = ?",
            tenantId,
            listing.dimensionId());
    return row == null ? 0 : row.get(0, Integer.class);
  }

  /** 某个 SPU 名下的 SKU。 */
  private List<Dimension> skusOf(Dimension spu, Map<String, Dimension> dimensions) {
    var out = new ArrayList<Dimension>();
    for (var dimension : dimensions.values()) {
      if (!"SKU".equals(dimension.dimensionType())) continue;
      if (!spu.dimensionId().equals(dimension.parentId())) continue;
      out.add(dimension);
    }
    out.sort(java.util.Comparator.comparing(Dimension::dimensionKey));
    return out;
  }

  /** 某个维度覆盖哪些平台。 */
  private List<String> platformsOf(
      Dimension dimension, Map<String, Dimension> dimensions, UUID tenantId) {
    var out = new LinkedHashSet<String>();
    if ("LISTING".equals(dimension.dimensionType())) {
      if (!dimension.platformCode().isBlank()) out.add(dimension.platformCode());
      return new ArrayList<>(out);
    }
    if ("SPU".equals(dimension.dimensionType())) {
      for (var sku : skusOf(dimension, dimensions)) {
        out.addAll(platformsOf(sku, dimensions, tenantId));
      }
      return new ArrayList<>(out);
    }
    var rows =
        sql.fetch(
            "select distinct platform_code from reporting_product_listing_sku"
                + " where tenant_id = ? and sku_id = ? and platform_code <> ''",
            tenantId,
            dimension.dimensionId());
    for (var row : rows) out.add(row.get(0, String.class));
    return new ArrayList<>(out);
  }

  /** 某个维度关联到几个 Listing。 */
  private Set<UUID> listingsOf(Dimension dimension, Map<String, Dimension> dimensions, UUID tenantId) {
    var out = new LinkedHashSet<UUID>();
    if ("LISTING".equals(dimension.dimensionType())) {
      out.add(dimension.dimensionId());
      return out;
    }
    if ("SPU".equals(dimension.dimensionType())) {
      for (var sku : skusOf(dimension, dimensions)) {
        out.addAll(listingsOf(sku, dimensions, tenantId));
      }
      return out;
    }
    var rows =
        sql.fetch(
            "select listing_id from reporting_product_listing_sku where tenant_id = ? and sku_id = ?",
            tenantId,
            dimension.dimensionId());
    for (var row : rows) out.add(row.get(0, UUID.class));
    return out;
  }

  /** 按业务键找一个维度。 */
  private Dimension findByKey(Map<String, Dimension> dimensions, String level, String key) {
    if (key == null || key.isBlank()) return null;
    for (var dimension : dimensions.values()) {
      if (!dimension.dimensionType().equals(level)) continue;
      if (dimension.dimensionKey().equals(key.trim())) return dimension;
    }
    return null;
  }

  /** 某个维度出现过的币种。 */
  private List<String> currenciesOf(Dimension dimension, Map<String, Fact> facts) {
    var out = new TreeSet<String>();
    var prefix = dimension.dimensionType() + "|" + dimension.dimensionId() + "|";
    for (var key : facts.keySet()) {
      if (!key.startsWith(prefix)) continue;
      out.add(key.substring(key.lastIndexOf('|') + 1));
    }
    return new ArrayList<>(out);
  }

  /** 某个指标在这行里的取值（第一个匹配的）。 */
  private BigDecimal valueOf(
      List<ReportingProductSalesModels.ProductMetricValue> values, String metricCode) {
    for (var value : values) {
      if (!metricCode.equals(value.metricCode())) continue;
      return value.value();
    }
    return null;
  }

  /**
   * 无法唯一归属到 Listing 的销量。
   *
   * <p>🔴 **一个 SKU 挂在多个 Listing 上（或一个都没挂）时，它的销量归不了** ——
   * 订单行不带平台商品 id，摊给谁都是造假 ⇒ 单列，不摊给任何 Listing。
   */
  private ReportingProductSalesModels.UnattributedSummary unattributed(
      UUID tenantId, LocalDate from, LocalDate to, Map<String, Fact> facts, Map<UUID, Integer> listingCounts) {
    var quantity = BigDecimal.ZERO;
    var orderCount = BigDecimal.ZERO;
    var amounts = new LinkedHashMap<String, BigDecimal>();
    var skuCodes = new LinkedHashSet<String>();
    var prefix = "SKU|";
    for (var entry : facts.entrySet()) {
      var key = entry.getKey();
      if (!key.startsWith(prefix)) continue;
      var parts = key.split("\\|");
      if (parts.length < 4) continue;
      var dimensionId = UUID.fromString(parts[1]);
      var metricCode = parts[2];
      var currency = parts[3];
      var listingCount = listingCounts.getOrDefault(dimensionId, 0);
      if (listingCount == 1) continue;
      var fact = entry.getValue();
      if (ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY.equals(metricCode)) {
        quantity = quantity.add(fact.quantity() == null ? BigDecimal.ZERO : fact.quantity());
        skuCodes.add(parts[1]);
      } else if (ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT.equals(metricCode)) {
        orderCount = orderCount.add(fact.value() == null ? BigDecimal.ZERO : fact.value());
      } else if (ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT.equals(metricCode)) {
        amounts.merge(currency, fact.value() == null ? BigDecimal.ZERO : fact.value(), BigDecimal::add);
      }
    }
    var currencyAmounts = new ArrayList<ReportingProductSalesModels.CurrencyAmount>();
    for (var entry : amounts.entrySet()) {
      currencyAmounts.add(new ReportingProductSalesModels.CurrencyAmount(entry.getKey(), entry.getValue()));
    }
    return new ReportingProductSalesModels.UnattributedSummary(
        quantity,
        orderCount,
        skuCodes.size(),
        currencyAmounts,
        "订单行只带 SKU 码，**不带平台商品 id**（trade 的订单明细里没有它）⇒ "
            + "同一款内部 SKU 挂在多个 Listing 上时，它的销量无法唯一归属到某个 Listing；"
            + "这类销量**不摊给任何 Listing**，在这里单列。");
  }

  /** 数据更新时间（卷 13 卷首「数据延迟要显示」）。**空表时是 null**。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var factRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_product_metric_fact where tenant_id = ?", tenantId);
    var drillRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_product_drilldown_fact where tenant_id = ?", tenantId);
    var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
    var drillAt = drillRow == null ? null : drillRow.get(0, OffsetDateTime.class);
    if (factAt == null) return drillAt;
    if (drillAt == null) return factAt;
    return factAt.isAfter(drillAt) ? factAt : drillAt;
  }

  /** 本次窗口内出现过的分桶时区（跨时区店铺汇总必须显示所用时区）。 */
  private List<String> timezones(UUID tenantId, LocalDate from, LocalDate to) {
    var out = new ArrayList<String>();
    var rows =
        sql.fetch(
            "select distinct timezone from reporting_product_metric_fact"
                + " where tenant_id = ? and bucket_date >= ? and bucket_date <= ? order by timezone",
            tenantId,
            from,
            to);
    for (var row : rows) out.add(row.get(0, String.class));
    return out;
  }

  /** 口径说明（页面上原样显示）。 */
  private List<String> caliberNotes(
      String level, ReportingProductSalesModels.UnattributedSummary unattributed, List<String> timezones) {
    var notes = new ArrayList<String>();
    notes.add(
        "🔴 **绝不跨币种相加**：成交金额按币种分别列出；只有件数与单数（与货币无关）给跨币种合计，"
            + "它的币种显示为空串（= 与币种无关，不是「币种未知」）。");
    if ("SPU".equals(level)) {
      notes.add(
          "SPU 层把名下**所有平台、所有 SKU** 合并成一行 —— 这是「SPU 口径是跨平台经营的基本单位」"
              + "的落点：只按平台 SKU 看会误判商品整体表现。");
    }
    if (unattributed != null && unattributed.skuCount() > 0) {
      notes.add(
          "有 " + unattributed.skuCount() + " 个 SKU 的销量**无法唯一归属**到某个 Listing"
              + "（同一款内部 SKU 挂在多个 Listing 上，或没有 Listing 绑定）—— "
              + "订单行不带平台商品 id，摊给谁都是造假，所以这部分销量不摊给任何 Listing。");
    }
    if (timezones.size() > 1) {
      notes.add(
          "本窗口的数据跨越 " + String.join("、", timezones) + " 多个分桶时区，"
              + "跨时区汇总的时间口径以各行自己的时区为准。");
    }
    notes.add("报表是投影，数据更新时间取自投影落库时刻；与业务系统冲突时以业务系统为准。");
    return notes;
  }
}
