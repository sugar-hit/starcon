package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.5.2.1 采购分析的**跨服务只读端口**（catalog / trade 两路）。
 *
 * <p>蓝图 13.5.2.1 的输入句写着「采购数据（6）；供应商评级」，关系句写着
 * 「上游 = 6 采购与供应」。本端口负责**这两侧**：
 *
 * <ul>
 *   <li>{@code GET /trade/purchase-analytics/facts} —— **采购到货流水**
 *       （6.5.2.1 的物化台账，{@code fact_kind=COST} 且
 *       {@code source_type=ARRIVAL_RECEIPT}）：金额、数量、单价、币种、
 *       供应商、SKU、类目 / 店铺维度、承诺到货日、**准时判定**。
 *       ⇒ 本模块的**全部金额口径**都取自它。</li>
 *   <li>{@code GET /trade/supplier-ratings/ratings} —— **供应商评级**（6.1.1.3）：
 *       最终分、评级档位与五个维度的得分。</li>
 *   <li>{@code GET /catalog/supply/vendors} —— **供应商档案**（6.1.1.1）：
 *       编码、名称、合作等级、采购侧状态、主体状态、国别。</li>
 *   <li>{@code GET /catalog/supply/vendors/{roleId}} —— **供应商到货记录**
 *       （6.1.1.1 的采购侧事实）：下单时刻、承诺到货日、实际到货日
 *       ⇒ 本模块的**采购周期**（到货日 − 下单日）的唯一来源。</li>
 * </ul>
 *
 * <p>⚠️ **金额只取 trade 一处**：catalog 的到货记录**也带金额**，但本模块只从它取
 * **采购周期**（时长）。两处金额都进合计 ⇒ 采购金额翻一倍，而报表不会报错。
 * 口径边界写在 {@code V405__procurement_analysis.sql} 第二节。
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本端口只经授权 HTTP API 读，
 * 不建跨库 FK、不建跨库视图、不直连对方 schema。
 *
 * <p>⚠️ **字段名必须与上游逐字一致**：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 写错字段名不会报错，只会静默变成 null —— 那会让采购金额恒为 0，比报错还危险。
 */
@Service
public class ReportingProcurementAnalysisPort {

  /** 上游分页上限（trade / catalog 都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient catalog;
  private final ReportingUpstreamClient trade;

  public ReportingProcurementAnalysisPort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
  }

  // ==================================================================
  // trade：采购到货流水（6.5.2.1 物化台账）
  // ==================================================================

  /**
   * 上游一行采购事实。
   *
   * <p>⚠️ {@code factKind} 有三种（COST 带金额 / RECEIPT 实收量 / PLAN 计划量），
   * 本模块只取 {@code COST}；{@code sourceType} 有四种，本模块只取
   * {@code ARRIVAL_RECEIPT}（到货流水）—— 计划量不是采购金额，混进来会让
   * 「采购金额」比实际花出去的钱多一倍。
   *
   * <p>⚠️ {@code amount} 与 {@code currency} 都可能为 null（上游没登记币种时），
   * 本模块原样搬运这个区分：**金额算不出来 ≠ 金额是 0**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradePurchaseFactRow(
      UUID id,
      String factKey,
      String factKind,
      String sourceType,
      String sourceDocNo,
      @Schema(nullable = true) UUID sourceDocId,
      String sourceRefNo,
      OffsetDateTime occurredAt,
      LocalDate occurredDate,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) UUID skuRefId,
      String skuCode,
      String skuName,
      String categoryCode,
      String categoryName,
      String shopCode,
      String warehouseCode,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) LocalDate promisedArrivalDate,
      String onTimeStatus) {}

  /** 上游事实台账页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradePurchaseFactPage(List<TradePurchaseFactRow> items, long total, int page, int size) {}

  /**
   * 拉一页采购事实（**已按窗口过滤**，上游支持 from / to）。
   *
   * @param from 窗口起（含）；null = 不限
   * @param to 窗口止（含）；null = 不限
   */
  public TradePurchaseFactPage purchaseFacts(LocalDate from, LocalDate to, int page, String bearer) {
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/purchase-analytics/facts")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("factKind", "COST")
                .queryParam("sourceType", "ARRIVAL_RECEIPT")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        TradePurchaseFactPage.class);
  }

  // ==================================================================
  // trade：供应商评级（6.1.1.3）
  // ==================================================================

  /**
   * 上游一行供应商评级。
   *
   * <p>⚠️ {@code finalScore}（最终分）是**客观分 + 人工调整量**（上游 DB 层 CHECK 钉死），
   * 而 {@code objectiveScore} 与 {@code manualAdjustment} 是它的两个分量 ——
   * 本模块只搬最终分（人工调整是上游留痕，复制过来会造出第二个「谁调过」的真相）。
   *
   * <p>⚠️ 五个维度的得分**可空** = 这个周期没有可算的依据（不是 0 分）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeSupplierRatingRow(
      UUID id,
      String periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      @Schema(nullable = true) UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) BigDecimal objectiveScore,
      @Schema(nullable = true) BigDecimal manualAdjustment,
      @Schema(nullable = true) BigDecimal finalScore,
      @Schema(nullable = true) String grade,
      @Schema(nullable = true) Integer rankInPeriod,
      @Schema(nullable = true) BigDecimal deliveryScore,
      @Schema(nullable = true) BigDecimal qualityScore,
      @Schema(nullable = true) BigDecimal responseScore,
      @Schema(nullable = true) BigDecimal priceScore,
      @Schema(nullable = true) BigDecimal serviceScore,
      OffsetDateTime computedAt) {}

  /** 上游评级分页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeSupplierRatingPage(
      List<TradeSupplierRatingRow> items, long total, int page, int size) {}

  /** 拉一页供应商评级。 */
  public TradeSupplierRatingPage supplierRatings(int page, String bearer) {
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/supplier-ratings/ratings")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        TradeSupplierRatingPage.class);
  }

  // ==================================================================
  // catalog：供应商档案（6.1.1.1）
  // ==================================================================

  /** 上游一个币种上的采购金额累计。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogVendorPurchaseAmount(String currency, BigDecimal amount) {}

  /**
   * 上游一行供应商。
   *
   * <p>⚠️ {@code roleId} 是 catalog 的 {@code party_role.id}（供应商**角色** id），
   * 与 trade 的 {@code supplier_ref_id} **同源** ⇒ 可以按 id 直接对齐，
   * **不需要按名称模糊匹配**（按名称匹配会把「同名两家供应商」并成一家）。
   *
   * <p>⚠️ {@code purchaseAmounts} 是**逐币种**的累计（本库没有汇率表）——
   * 本模块**不用**它做金额口径（金额口径取 trade 的到货流水），
   * 只用它交叉验证供应商身份。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogVendorRow(
      UUID roleId,
      UUID partyId,
      String partyCode,
      String partyName,
      String partyType,
      @Schema(nullable = true) String countryCode,
      String partyStatus,
      @Schema(nullable = true) String cooperationLevel,
      @Schema(nullable = true) String supplyStatus,
      List<CatalogVendorPurchaseAmount> purchaseAmounts) {}

  /** 上游供应商列表页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogVendorPage(List<CatalogVendorRow> items, long total, int page, int size) {}

  /** 拉一页供应商档案。 */
  public CatalogVendorPage vendors(int page, String bearer) {
    return catalog.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/supply/vendors")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CatalogVendorPage.class);
  }

  // ==================================================================
  // catalog：供应商到货记录（6.1.1.1 的采购侧事实）—— 采购周期的唯一来源
  // ==================================================================

  /**
   * 上游一条到货记录。
   *
   * <p>⚠️ {@code orderedAt} 在上游是 **String**（不是时间类型）—— 契约里就是
   * {@code String orderedAt}。本模块按 ISO-8601 解析；解析不了就**跳过这一行**
   * （拿一个解析不了的时刻算周期，只会得到一个看起来正常、实际错的数字）。
   *
   * <p>⚠️ {@code isVoid} 的行**不计入任何指标**（上游作废的行仍留在库里，可追溯）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogDeliveryRow(
      UUID id,
      String deliveryRef,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      BigDecimal quantity,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) String orderedAt,
      LocalDate dueDate,
      LocalDate receivedDate,
      boolean onTime,
      int daysLate,
      boolean isVoid,
      @Schema(nullable = true) String note) {}

  /** 上游供应商采购视角详情（头 + 到货记录 + 质检记录）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogVendorPurchaseView(
      @JsonIgnoreProperties(ignoreUnknown = true) CatalogVendorRow summary,
      List<CatalogDeliveryRow> deliveries) {}

  /**
   * 拉一个供应商的到货记录（**采购周期的唯一来源**）。
   *
   * @param roleId 供应商角色 id（catalog 的 {@code party_role.id}）
   */
  public CatalogVendorPurchaseView vendorPurchaseView(UUID roleId, String bearer) {
    return catalog.get(
        uriBuilder -> uriBuilder.path("/catalog/supply/vendors/" + roleId),
        bearer,
        CatalogVendorPurchaseView.class);
  }
}
