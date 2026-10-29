package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.5.2.1 采购分析的**投影摄取**（蓝图「输入」句：采购数据（6）；供应商评级）。
 *
 * <p>本服务把**上游算好的**事实拉成本服务的投影表：
 *
 * <table>
 *   <caption>两路上游</caption>
 *   <tr><th>上游</th><th>端点</th><th>落到哪张表</th></tr>
 *   <tr><td>6 采购（trade）</td><td>{@code /trade/purchase-analytics/facts}</td>
 *       <td>{@code reporting_procurement_analysis_purchase_fact}</td></tr>
 *   <tr><td>6.1.1.3 供应商评级（trade）</td><td>{@code /trade/supplier-ratings/ratings}</td>
 *       <td>{@code reporting_procurement_analysis_supplier_fact}（评级列）</td></tr>
 *   <tr><td>6.1.1.1 供应商档案（catalog）</td><td>{@code /catalog/supply/vendors}</td>
 *       <td>{@code reporting_procurement_analysis_supplier_fact}（档案列）</td></tr>
 *   <tr><td>6.1.1.1 到货记录（catalog）</td><td>{@code /catalog/supply/vendors/{roleId}}</td>
 *       <td>{@code reporting_procurement_analysis_delivery_fact}（**只有周期**）</td></tr>
 * </table>
 *
 * <p>⚠️ **本服务不重算任何上游口径**：准时判定在 trade 6.3.2.2、金额与单价在
 * trade 6.5.2.1 的物化台账、供应商档案在 catalog 6.1.1.1、评级在 trade 6.1.1.3 ——
 * 本服务只做**搬运与聚合**。重算就会出现第二个真相。
 *
 * <p>⚠️ **金额只从 trade 一处来**：catalog 的到货记录也带金额，但本服务**只从它取
 * 采购周期**（时长）。两处金额都落进合计 ⇒ 采购金额翻一倍，而报表不会报错。
 *
 * <p>⚠️ **跨服务调用一律走只读端口并转发调用方自己的 bearer**：不传租户号，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **幂等**：写入走 {@code on conflict ... do update}（幂等键见 V405 的唯一索引），
 * 同一批上游事实重放**不会翻倍**。
 *
 * <p>⚠️ **上游读不到就如实报 FAILED**：绝不返回空集合冒充「上游没有数据」——
 * 一个空集合在报表里长成「采购金额 0」，而真相是「这一格没算出来」。
 */
@Service
public class ReportingProcurementAnalysisRefreshService {

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  /** 评分刻度（对齐 {@code numeric(19,4)}）。 */
  static final int SCORE_SCALE = 4;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingProcurementAnalysisPort procurementPort;

  public ReportingProcurementAnalysisRefreshService(
      DSLContext sql, TenantTransactions tenants, ReportingProcurementAnalysisPort procurementPort) {
    this.sql = sql;
    this.tenants = tenants;
    this.procurementPort = procurementPort;
  }

  // ==========================================================================
  // 入口
  // ==========================================================================

  /**
   * 刷新采购分析投影。
   *
   * @param from 采购流水窗口起（含）
   * @param to 采购流水窗口止（含）
   * @param zone 采购周期的「下单日」换算时区（随行落库，报表可复盘）
   */
  public ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshResult refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId zone) {
    var summaries = new ArrayList<ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshSummary>();
    var truncated = false;

    // ---- ① 采购到货流水（**金额口径的唯一来源**）----
    try {
      var pulled = pullPurchaseFacts(bearer, from, to);
      var written = writePurchaseFacts(tenantId, pulled.facts());
      truncated = truncated || pulled.truncated();
      var note =
          "trade 6.5.2.1 的到货流水（fact_kind=COST / source_type=ARRIVAL_RECEIPT）——"
              + "**分批到货按流水累加**，不压成「一张采购单一行」。";
      if (pulled.truncated()) {
        note +=
            " ⚠️ 取到 "
                + ReportingProcurementAnalysisPort.MAX_PAGES
                + " 页为止，超出的未投影。";
      }
      summaries.add(
          summary(
              "TRADE_PURCHASE_FACT",
              "采购到货流水（金额 / 数量 / 单价 / 准时判定）",
              pulled.facts().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "TRADE_PURCHASE_FACT",
              "采购到货流水（金额 / 数量 / 单价 / 准时判定）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ② 供应商档案（catalog 6.1.1.1）----
    try {
      var vendors = pullVendors(bearer);
      var written = writeVendors(tenantId, vendors);
      summaries.add(
          summary(
              "CATALOG_VENDOR",
              "供应商档案（编码 / 名称 / 合作等级 / 采购侧状态）",
              vendors.size(),
              written,
              "OK",
              "catalog 6.1.1.1 的供应商列表 —— 本模块**只读**档案，不写它。"));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CATALOG_VENDOR",
              "供应商档案（编码 / 名称 / 合作等级 / 采购侧状态）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ③ 供应商评级（trade 6.1.1.3）----
    try {
      var ratings = pullRatings(bearer);
      var written = writeRatings(tenantId, ratings);
      summaries.add(
          summary(
              "TRADE_SUPPLIER_RATING",
              "供应商评级（最终分 / 档位 / 五个维度）",
              ratings.size(),
              written,
              "OK",
              "trade 6.1.1.3 的评级结果 —— **每个供应商取最新一期**；"
                  + "评级历史仍归 6.1.1.3，本服务不复制。"));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "TRADE_SUPPLIER_RATING",
              "供应商评级（最终分 / 档位 / 五个维度）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ④ 到货记录（catalog 6.1.1.1）→ **只有采购周期**----
    try {
      var vendors = pullVendors(bearer);
      var deliveries = pullDeliveries(bearer, vendors);
      var written = writeDeliveries(tenantId, deliveries.facts(), zone);
      truncated = truncated || deliveries.truncated();
      var note =
          "catalog 6.1.1.1 的到货记录 —— **只用于采购周期（到货日 − 下单日）**，"
              + "它的金额**不参与任何合计**（否则同一笔采购会被算两次）。";
      if (deliveries.unparsable() > 0) {
        note += " ⚠️ 跳过 " + deliveries.unparsable() + " 条下单时刻解析不了的记录。";
      }
      if (deliveries.truncated()) {
        note += " ⚠️ 供应商数量取到上限为止，超出的未投影。";
      }
      summaries.add(
          summary(
              "CATALOG_DELIVERY",
              "到货记录（**只有采购周期**，不含金额）",
              deliveries.facts().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CATALOG_DELIVERY",
              "到货记录（**只有采购周期**，不含金额）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    var totalWritten = summaries.stream().mapToInt(s -> s.written()).sum();
    var note =
        "采购分析是**投影**：与业务系统冲突时以业务系统为准"
            + "（trade 的采购事实与评级、catalog 的供应商档案与到货记录）。";
    if (truncated) {
      note += " ⚠️ 本次刷新**触到了取数上限**，超出的部分未投影。";
    }
    return new ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshResult(
        summaries, totalWritten, truncated, note);
  }

  private static ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshSummary summary(
      String source, String label, int factCount, int written, String status, String note) {
    return new ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshSummary(
        source, label, factCount, written, status, note);
  }

  // ==========================================================================
  // ① 采购到货流水
  // ==========================================================================

  /** 一条待落库的采购流水。 */
  private record PurchaseFact(
      LocalDate occurredOn,
      OffsetDateTime occurredAt,
      String sourceFactKey,
      String sourceDocNo,
      String sourceRefNo,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      UUID skuId,
      String skuCode,
      String skuName,
      String categoryCode,
      String categoryName,
      String shopCode,
      String warehouseCode,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String currency,
      LocalDate promisedArrivalDate,
      String onTimeStatus) {}

  /** 拉取结果（带上「触到上限」的标记）。 */
  private record PurchaseFacts(List<PurchaseFact> facts, boolean truncated) {}

  private PurchaseFacts pullPurchaseFacts(String bearer, LocalDate from, LocalDate to) {
    var facts = new ArrayList<PurchaseFact>();
    var truncated = false;
    for (int page = 0; page < ReportingProcurementAnalysisPort.MAX_PAGES; page++) {
      var paged = procurementPort.purchaseFacts(from, to, page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.factKey() == null || row.factKey().isBlank()) continue;
        // ⚠️ **occurred_at 必须有**：它是 timestamptz 列，缺了这一列就没法落库。
        //   occurred_on 用**上游算好的**业务日（上游已按租户时区换算，本服务不再换算）。
        if (row.occurredAt() == null || row.occurredDate() == null) continue;
        facts.add(
            new PurchaseFact(
                row.occurredDate(),
                row.occurredAt(),
                row.factKey(),
                text(row.sourceDocNo()),
                text(row.sourceRefNo()),
                row.supplierRefId(),
                text(row.supplierCode()),
                text(row.supplierName()),
                row.skuRefId(),
                text(row.skuCode()),
                text(row.skuName()),
                text(row.categoryCode()),
                text(row.categoryName()),
                text(row.shopCode()),
                text(row.warehouseCode()),
                quantity(row.quantity()),
                money(row.unitPrice()),
                money(row.amount()),
                currency(row.currency()),
                row.promisedArrivalDate(),
                onTimeStatus(row.onTimeStatus())));
      }
      if (paged.items().size() < ReportingProcurementAnalysisPort.PAGE_SIZE) break;
      truncated = true;
    }
    return new PurchaseFacts(facts, truncated);
  }

  private int writePurchaseFacts(UUID tenantId, List<PurchaseFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_procurement_analysis_purchase_fact
                  (id,tenant_id,occurred_on,occurred_at,source_fact_key,source_doc_no,source_ref_no,
                   supplier_id,supplier_code,supplier_name,
                   sku_id,sku_code,sku_name,
                   category_code,category_name,shop_code,warehouse_code,
                   quantity,unit_price,amount,currency,
                   promised_arrival_date,on_time_status,
                   source_service,source_reference,updated_at)
                values (?,?,?,?::timestamptz,?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?,?,?,?,
                         ?,?,?,?,
                         ?,?,
                         'trade',?,now())
                on conflict (tenant_id,source_fact_key)
                do update set occurred_on = excluded.occurred_on,
                              occurred_at = excluded.occurred_at,
                              source_doc_no = excluded.source_doc_no,
                              source_ref_no = excluded.source_ref_no,
                              supplier_id = excluded.supplier_id,
                              supplier_code = excluded.supplier_code,
                              supplier_name = excluded.supplier_name,
                              sku_id = excluded.sku_id,
                              sku_code = excluded.sku_code,
                              sku_name = excluded.sku_name,
                              category_code = excluded.category_code,
                              category_name = excluded.category_name,
                              shop_code = excluded.shop_code,
                              warehouse_code = excluded.warehouse_code,
                              quantity = excluded.quantity,
                              unit_price = excluded.unit_price,
                              amount = excluded.amount,
                              currency = excluded.currency,
                              promised_arrival_date = excluded.promised_arrival_date,
                              on_time_status = excluded.on_time_status,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.occurredOn(),
                fact.occurredAt(),
                fact.sourceFactKey(),
                fact.sourceDocNo(),
                fact.sourceRefNo(),
                fact.supplierId(),
                fact.supplierCode(),
                fact.supplierName(),
                fact.skuId(),
                fact.skuCode(),
                fact.skuName(),
                fact.categoryCode(),
                fact.categoryName(),
                fact.shopCode(),
                fact.warehouseCode(),
                fact.quantity(),
                fact.unitPrice(),
                fact.amount(),
                fact.currency(),
                fact.promisedArrivalDate(),
                fact.onTimeStatus(),
                fact.sourceFactKey());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ② 供应商档案（catalog）
  // ==========================================================================

  /** 一条待落库的供应商档案。 */
  private record VendorFact(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      String partyStatus,
      String cooperationLevel,
      String supplyStatus,
      String countryCode) {}

  private List<VendorFact> pullVendors(String bearer) {
    var facts = new ArrayList<VendorFact>();
    for (int page = 0; page < ReportingProcurementAnalysisPort.MAX_PAGES; page++) {
      var paged = procurementPort.vendors(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.roleId() == null) continue;
        facts.add(
            new VendorFact(
                row.roleId(),
                text(row.partyCode()),
                text(row.partyName()),
                text(row.partyStatus()),
                text(row.cooperationLevel()),
                text(row.supplyStatus()),
                text(row.countryCode())));
      }
      if (paged.items().size() < ReportingProcurementAnalysisPort.PAGE_SIZE) break;
    }
    return facts;
  }

  private int writeVendors(UUID tenantId, List<VendorFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_procurement_analysis_supplier_fact
                  (id,tenant_id,supplier_id,supplier_code,supplier_name,
                   party_status,cooperation_level,supply_status,country_code,
                   rating_score,rating_grade,rating_period_start,rating_period_end,
                   rating_delivery_score,rating_quality_score,rating_price_score,
                   rating_response_score,rating_service_score,rating_computed_at,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,
                         ?,?,?,?,
                         null,'',null,null,
                         null,null,null,null,null,null,
                         'catalog',?,now())
                on conflict (tenant_id,supplier_id)
                do update set supplier_code = excluded.supplier_code,
                              supplier_name = excluded.supplier_name,
                              party_status = excluded.party_status,
                              cooperation_level = excluded.cooperation_level,
                              supply_status = excluded.supply_status,
                              country_code = excluded.country_code,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.supplierId(),
                fact.supplierCode(),
                fact.supplierName(),
                fact.partyStatus(),
                fact.cooperationLevel(),
                fact.supplyStatus(),
                fact.countryCode(),
                "catalog:vendor:" + fact.supplierId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ③ 供应商评级（trade）
  // ==========================================================================

  /** 一条待落库的评级快照。 */
  private record RatingFact(
      UUID supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal score,
      String grade,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal deliveryScore,
      BigDecimal qualityScore,
      BigDecimal responseScore,
      BigDecimal priceScore,
      BigDecimal serviceScore,
      OffsetDateTime computedAt) {}

  /**
   * 拉评级并**每个供应商只留最新一期**。
   *
   * <p>⚠️ 「最新」的判据是**评级周期结束日**，其次是**计算时刻** ——
   * 只按计算时刻排会被「补算了一个旧周期」的行顶掉。
   */
  private List<RatingFact> pullRatings(String bearer) {
    var latest = new HashMap<UUID, RatingFact>();
    for (int page = 0; page < ReportingProcurementAnalysisPort.MAX_PAGES; page++) {
      var paged = procurementPort.supplierRatings(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.supplierRefId() == null) continue;
        var candidate =
            new RatingFact(
                row.supplierRefId(),
                text(row.supplierCode()),
                text(row.supplierName()),
                row.finalScore(),
                text(row.grade()),
                row.periodStart(),
                row.periodEnd(),
                row.deliveryScore(),
                row.qualityScore(),
                row.responseScore(),
                row.priceScore(),
                row.serviceScore(),
                row.computedAt());
        var existing = latest.get(candidate.supplierId());
        if (existing == null || newer(candidate, existing)) {
          latest.put(candidate.supplierId(), candidate);
        }
      }
      if (paged.items().size() < ReportingProcurementAnalysisPort.PAGE_SIZE) break;
    }
    return new ArrayList<>(latest.values());
  }

  /** 一期评级是否比另一期新。 */
  private static boolean newer(RatingFact candidate, RatingFact existing) {
    var candidateEnd = candidate.periodEnd();
    var existingEnd = existing.periodEnd();
    if (candidateEnd != null && existingEnd != null && !candidateEnd.equals(existingEnd)) {
      return candidateEnd.isAfter(existingEnd);
    }
    if (candidateEnd != null && existingEnd == null) return true;
    if (candidateEnd == null && existingEnd != null) return false;
    var candidateAt = candidate.computedAt();
    var existingAt = existing.computedAt();
    if (candidateAt == null) return false;
    if (existingAt == null) return true;
    return candidateAt.isAfter(existingAt);
  }

  private int writeRatings(UUID tenantId, List<RatingFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            // ⚠️ **先读后写，而不是在 ON CONFLICT 里引用目标表**：
            //   `do update set x = case when ... else <目标表>.x end` 这种写法
            //   既难读，又会被 jOOQ 的 SQL 解析器判成「bad SQL grammar」。
            //   这里先把档案里已有的编码 / 名称读出来，再决定写哪个值 ——
            //   评级那一路没带编码时**不能**把 catalog 档案里的编码抹成空串。
            var existing =
                sql.fetchOne(
                    "select supplier_code, supplier_name"
                        + " from reporting_procurement_analysis_supplier_fact"
                        + " where tenant_id = ? and supplier_id = ?",
                    tenantId,
                    fact.supplierId());
            var code =
                fact.supplierCode().isBlank()
                    ? (existing == null ? "" : nullToEmpty(existing.get(0, String.class)))
                    : fact.supplierCode();
            var name =
                fact.supplierName().isBlank()
                    ? (existing == null ? "" : nullToEmpty(existing.get(1, String.class)))
                    : fact.supplierName();
            sql.execute(
                """
                insert into reporting_procurement_analysis_supplier_fact
                  (id,tenant_id,supplier_id,supplier_code,supplier_name,
                   party_status,cooperation_level,supply_status,country_code,
                   rating_score,rating_grade,rating_period_start,rating_period_end,
                   rating_delivery_score,rating_quality_score,rating_price_score,
                   rating_response_score,rating_service_score,rating_computed_at,
                   source_service,source_reference,updated_at)
                -- ⚠️ rating_computed_at 必须写 `?::timestamptz`：不写显式 cast 时
                --   PG 会把这个占位符按 varchar 送过去，列类型却是 timestamptz ⇒ 落库直接炸。
                values (?,?,?,?,?,
                         '','','','',
                         ?,?,?,?,
                         ?,?,?,?,?,?::timestamptz,
                         'trade',?,now())
                on conflict (tenant_id,supplier_id)
                do update set supplier_code = excluded.supplier_code,
                              supplier_name = excluded.supplier_name,
                              rating_score = excluded.rating_score,
                              rating_grade = excluded.rating_grade,
                              rating_period_start = excluded.rating_period_start,
                              rating_period_end = excluded.rating_period_end,
                              rating_delivery_score = excluded.rating_delivery_score,
                              rating_quality_score = excluded.rating_quality_score,
                              rating_price_score = excluded.rating_price_score,
                              rating_response_score = excluded.rating_response_score,
                              rating_service_score = excluded.rating_service_score,
                              rating_computed_at = excluded.rating_computed_at,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.supplierId(),
                code,
                name,
                fact.score(),
                fact.grade(),
                fact.periodStart(),
                fact.periodEnd(),
                fact.deliveryScore(),
                fact.qualityScore(),
                fact.responseScore(),
                fact.priceScore(),
                fact.serviceScore(),
                fact.computedAt(),
                "trade:rating:" + fact.supplierId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ④ 到货记录（catalog）→ 采购周期
  // ==========================================================================

  /** 一条待落库的到货记录（**只有周期，没有金额**）。 */
  private record DeliveryFact(
      UUID deliveryId,
      String deliveryRef,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      UUID skuId,
      String skuCode,
      String skuName,
      OffsetDateTime orderedAt,
      LocalDate dueDate,
      LocalDate receivedDate,
      boolean isVoid) {}

  /** 到货记录的拉取结果。 */
  private record Deliveries(
      List<DeliveryFact> facts, int unparsable, boolean truncated) {}

  private Deliveries pullDeliveries(String bearer, List<VendorFact> vendors) {
    var facts = new ArrayList<DeliveryFact>();
    var unparsable = 0;
    var truncated = false;
    for (var vendor : vendors) {
      if (vendor.supplierId() == null) continue;
      var view = procurementPort.vendorPurchaseView(vendor.supplierId(), bearer);
      if (view == null || view.deliveries() == null) continue;
      for (var row : view.deliveries()) {
        if (row == null || row.id() == null) continue;
        if (row.receivedDate() == null || row.dueDate() == null) {
          unparsable++;
          continue;
        }
        var orderedAt = parseOffsetDateTime(row.orderedAt());
        if (orderedAt == null) {
          // ⚠️ **解析不了就跳过这一行**：拿一个坏时刻算周期，
          //   只会得到一个看起来正常、实际错的数字。
          unparsable++;
          continue;
        }
        facts.add(
            new DeliveryFact(
                row.id(),
                text(row.deliveryRef()),
                vendor.supplierId(),
                vendor.supplierCode(),
                vendor.supplierName(),
                row.skuId(),
                text(row.skuCode()),
                text(row.skuName()),
                orderedAt,
                row.dueDate(),
                row.receivedDate(),
                row.isVoid()));
      }
    }
    return new Deliveries(facts, unparsable, truncated);
  }

  private int writeDeliveries(UUID tenantId, List<DeliveryFact> facts, ZoneId zone) {
    if (facts.isEmpty()) return 0;
    var zoneName = zone == null ? "UTC" : zone.getId();
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            // ⚠️ **下单日按本行所用的时区换算**：到货日与下单日必须落在同一时区下做差，
            //   否则会出现 0 天或负数天。
            var orderedOn = fact.orderedAt().atZoneSameInstant(zone).toLocalDate();
            var cycleDays = ChronoUnit.DAYS.between(orderedOn, fact.receivedDate());
            if (cycleDays < 0) {
              // ⚠️ **负周期是脏数据**：宁可跳过，也不要把它算成 0 天。
              continue;
            }
            sql.execute(
                """
                insert into reporting_procurement_analysis_delivery_fact
                  (id,tenant_id,delivery_id,delivery_ref,
                   supplier_id,supplier_code,supplier_name,
                   sku_id,sku_code,sku_name,
                   ordered_at,ordered_on,due_date,received_date,cycle_days,
                   timezone,is_void,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?::timestamptz,?,?,?,?,
                         ?,?,
                         'catalog',?,now())
                on conflict (tenant_id,delivery_id)
                do update set delivery_ref = excluded.delivery_ref,
                              supplier_code = excluded.supplier_code,
                              supplier_name = excluded.supplier_name,
                              sku_id = excluded.sku_id,
                              sku_code = excluded.sku_code,
                              sku_name = excluded.sku_name,
                              ordered_at = excluded.ordered_at,
                              ordered_on = excluded.ordered_on,
                              due_date = excluded.due_date,
                              received_date = excluded.received_date,
                              cycle_days = excluded.cycle_days,
                              timezone = excluded.timezone,
                              is_void = excluded.is_void,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.deliveryId(),
                fact.deliveryRef(),
                fact.supplierId(),
                fact.supplierCode(),
                fact.supplierName(),
                fact.skuId(),
                fact.skuCode(),
                fact.skuName(),
                fact.orderedAt(),
                orderedOn,
                fact.dueDate(),
                fact.receivedDate(),
                (int) cycleDays,
                zoneName,
                fact.isVoid(),
                "catalog:delivery:" + fact.deliveryId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }

  private static String nullToEmpty(String raw) {
    return raw == null ? "" : raw;
  }

  /** 数量：null ⇒ 0（**只有数量可以这样**，金额不行）。 */
  private static BigDecimal quantity(BigDecimal raw) {
    if (raw == null) return BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    return raw.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额：null 保持 null（**算不出来不是 0**）。 */
  private static BigDecimal money(BigDecimal raw) {
    if (raw == null) return null;
    return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 评分：null 保持 null。 */
  private static BigDecimal score(BigDecimal raw) {
    if (raw == null) return null;
    return raw.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** 币种：null / 非法 ⇒ null（金额与币种要么都在，要么都不在）。 */
  private static String currency(String raw) {
    if (raw == null) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  /** 准时状态：只认上游的三态，其余一律 NOT_APPLICABLE。 */
  private static String onTimeStatus(String raw) {
    if (raw == null) return "NOT_APPLICABLE";
    var value = raw.trim().toUpperCase();
    return switch (value) {
      case "ON_TIME", "LATE", "PENDING" -> value;
      default -> "NOT_APPLICABLE";
    };
  }

  /**
   * 解析上游的 ISO-8601 时刻。
   *
   * <p>⚠️ catalog 的 {@code orderedAt} 在契约里是 **String**，这里按 ISO-8601 解析；
   * 解析不了返回 null（调用方跳过这一行），**绝不**退化成「现在」。
   */
  static OffsetDateTime parseOffsetDateTime(String raw) {
    if (raw == null) return null;
    var value = raw.trim();
    if (value.isEmpty()) return null;
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException ignored) {
      // 退一步：本地日期时间（不带时区）按 UTC 解释。
      try {
        return java.time.LocalDateTime.parse(value).atOffset(java.time.ZoneOffset.UTC);
      } catch (RuntimeException ignoredToo) {
        return null;
      }
    }
  }
}
