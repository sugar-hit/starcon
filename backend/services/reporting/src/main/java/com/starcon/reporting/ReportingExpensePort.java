package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

/**
 * 读取 **5 费用规则（catalog）**事实的**跨服务只读端口**（catalog 服务，端口 8082）。
 *
 * <p>13.4.2.2 费用差异的**预计侧**全部来自这个端口：
 *
 * <ul>
 *   <li>{@code GET /catalog/platform-fees/actuals} —— 平台费**实际台账**（人工登记的平台账单观察值，
 *       带销售额 / 数量基数 ⇒ 可以按规则重算「预计值」）；
 *   <li>{@code GET /catalog/platform-fees/estimate} —— **按规则测算**（本模块「预计费用」的唯一来源）；
 *   <li>{@code GET /catalog/logistics-freight/actuals} —— 物流费**实际台账**；
 *   <li>{@code GET /catalog/logistics-freight/estimate} —— 渠道运费**按规则测算**；
 *   <li>{@code GET /catalog/cost-allocations} + {@code /{id}} —— 5.2.1.3 成本分摊（**实际头程分摊**，
 *       逐 SKU 一行）；
 *   <li>{@code GET /catalog/sku-cost-compositions/{skuId}} —— 5.2.1.1 成本构成的
 *       {@code FIRST_MILE} 分项（**预计头程分摊**，逐 SKU）。
 * </ul>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，catalog 侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **口径纪律：规则只在 catalog 一处**。本端口**只读**上游算好的测算结果，
 * **不在本服务重算费率**（重算就会出现第二个真相 —— 规则一改，本模块的差异就变成假的）。
 */
@Service
public class ReportingExpensePort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingExpensePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    this.client = ReportingUpstreamClient.to("catalog 服务", url);
  }

  // ================================================================ 平台费

  /** 平台费实际费用的一行（上游把金额写成字符串，逐字照抄上游的形状）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformFeeActualRow(
      UUID id,
      String platformCode,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      String feeType,
      String feeTypeLabel,
      String amount,
      String currency,
      String saleAmount,
      String quantity,
      String occurredOn,
      String source,
      String sourceLabel,
      String sourceRef,
      String note,
      boolean isVoid,
      String voidReason) {}

  /** 平台费实际费用台账分页结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformFeeActualPage(List<PlatformFeeActualRow> items, long total, int page, int size) {}

  /** 拉一页平台费实际费用台账。 */
  public PlatformFeeActualPage platformFeeActuals(
      String platformCode, String siteCode, String feeType, LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder -> {
          var b = uriBuilder.path("/catalog/platform-fees/actuals");
          b = param(b, "platformCode", platformCode);
          b = param(b, "siteCode", siteCode);
          b = param(b, "feeType", feeType);
          b = param(b, "from", from);
          b = param(b, "to", to);
          return b.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        PlatformFeeActualPage.class);
  }

  /** 平台费测算里的一个费用项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformFeeEstimateItem(
      String feeType,
      String feeTypeLabel,
      String chargeBasis,
      String chargeBasisLabel,
      UUID ruleId,
      String scopeBasis,
      String amount,
      String currency,
      String rate,
      String basis,
      boolean applicable,
      String note) {}

  /**
   * 平台费测算结果。
   *
   * <p>⚠️ {@code totalAmount} 只在**全部费用项都算得出来**时才是合计；
   * 任何一个费用项算不出来（没有规则 / 规则不适用），{@code complete=false} 且
   * {@code totalAmount=null} —— 把「算不出来」当 0 加进去，就是在报一个偏低的费用。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformFeeEstimate(
      String platformCode,
      String siteCode,
      String saleAmount,
      String quantity,
      String evaluatedAt,
      List<PlatformFeeEstimateItem> items,
      String totalAmount,
      String currency,
      boolean complete,
      String note) {}

  /** 按规则测算一次平台费（**预计侧的唯一来源**）。 */
  public PlatformFeeEstimate platformFeeEstimate(
      String platformCode,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      BigDecimal saleAmount,
      BigDecimal quantity,
      String currency,
      String at,
      String bearer) {
    return client.get(
        uriBuilder -> {
          var b = uriBuilder.path("/catalog/platform-fees/estimate");
          b = param(b, "platformCode", platformCode);
          b = param(b, "siteCode", siteCode);
          b = param(b, "internalCategoryId", internalCategoryId);
          b = param(b, "platformCategoryId", platformCategoryId);
          b = param(b, "saleAmount", saleAmount);
          b = param(b, "quantity", quantity);
          b = param(b, "currency", currency);
          b = param(b, "at", at);
          return b;
        },
        bearer,
        PlatformFeeEstimate.class);
  }

  // ================================================================ 物流费

  /** 物流费实际运费的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FreightActualRow(
      UUID id,
      String channelCode,
      String channelName,
      String carrierCode,
      String carrierName,
      String shippingMethodCode,
      String shippingMethodName,
      String chargeBasis,
      String chargeBasisLabel,
      String amount,
      String baseAmount,
      String surchargeAmount,
      String currency,
      String chargeableQuantity,
      String quantity,
      String occurredOn,
      String source,
      String sourceLabel,
      String sourceRef,
      String note,
      boolean isVoid,
      String voidReason) {}

  /** 物流费实际运费台账分页结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FreightActualPage(List<FreightActualRow> items, long total, int page, int size) {}

  /** 拉一页物流费实际运费台账。 */
  public FreightActualPage freightActuals(
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      LocalDate from,
      LocalDate to,
      int page,
      String bearer) {
    return client.get(
        uriBuilder -> {
          var b = uriBuilder.path("/catalog/logistics-freight/actuals");
          b = param(b, "channelCode", channelCode);
          b = param(b, "carrierCode", carrierCode);
          b = param(b, "shippingMethodCode", shippingMethodCode);
          b = param(b, "from", from);
          b = param(b, "to", to);
          return b.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        FreightActualPage.class);
  }

  /** 运费测算里的一步。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FreightEstimateStep(
      String step, String label, String basis, String amount, String currency, boolean applicable, String note) {}

  /** 渠道运费测算结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FreightEstimate(
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String chargeBasis,
      String currency,
      String evaluatedAt,
      String chargeableQuantity,
      String quantityUnit,
      String quantity,
      List<FreightEstimateStep> steps,
      String baseAmount,
      String surchargeAmount,
      String totalAmount,
      boolean complete,
      String note) {}

  /**
   * 按规则测算一次渠道运费（**预计侧的唯一来源**）。
   *
   * <p>⚠️ **输入必须按计费口径给**：{@code BY_WEIGHT} 要重量、{@code BY_VOLUME} 要长宽高、
   * {@code BY_PIECE} 要件数 —— 少给一个上游就返回「算不出来」（{@code totalAmount=null}），
   * 本模块如实报成「预计值算不出来」，**不用 0 冒充**。
   */
  public FreightEstimate freightEstimate(
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String chargeBasis,
      BigDecimal weightKg,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      BigDecimal pieces,
      String at,
      String bearer) {
    return client.get(
        uriBuilder -> {
          var b = uriBuilder.path("/catalog/logistics-freight/estimate");
          b = param(b, "channelCode", channelCode);
          b = param(b, "carrierCode", carrierCode);
          b = param(b, "shippingMethodCode", shippingMethodCode);
          b = param(b, "chargeBasis", chargeBasis);
          b = param(b, "weightKg", weightKg);
          b = param(b, "lengthCm", lengthCm);
          b = param(b, "widthCm", widthCm);
          b = param(b, "heightCm", heightCm);
          b = param(b, "pieces", pieces);
          b = param(b, "at", at);
          return b;
        },
        bearer,
        FreightEstimate.class);
  }

  // ================================================================ 头程分摊

  /** 成本分摊单的一行（列表形状）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostAllocationRow(
      UUID allocationId,
      String sourceType,
      String sourceTypeLabel,
      String sourceRef,
      String sourceLabel,
      String currency,
      BigDecimal totalCost,
      String basisRule,
      String basisRuleLabel,
      Integer revisionNo,
      int revisionCount,
      int lineCount,
      BigDecimal allocatedTotal,
      BigDecimal adjustmentTotal,
      BigDecimal finalTotal,
      BigDecimal variance,
      java.time.OffsetDateTime effectiveFrom) {}

  /** 成本分摊单分页结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostAllocationPage(List<CostAllocationRow> items, long total, int page, int size) {}

  /** 分摊单的一个版本（表头 + 汇总）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostAllocationRevision(
      UUID revisionId,
      int revisionNo,
      String basisRule,
      String basisRuleLabel,
      BigDecimal totalCost,
      String currency,
      int lineCount,
      int eligibleLineCount,
      BigDecimal basisTotal,
      BigDecimal allocatedTotal,
      BigDecimal adjustmentTotal,
      BigDecimal finalTotal,
      BigDecimal variance,
      java.time.OffsetDateTime effectiveFrom,
      String changeReason,
      java.time.OffsetDateTime createdAt) {}

  /** 分摊单的一行（**一个 SKU 一行**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostAllocationLine(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal baseQuantity,
      BigDecimal weightGrams,
      BigDecimal volumeCm3,
      BigDecimal amountBasis,
      BigDecimal basisValue,
      boolean basisAvailable,
      String basisNote,
      BigDecimal allocatedAmount,
      BigDecimal adjustmentAmount,
      String adjustmentReason,
      BigDecimal finalAmount) {}

  /** 分摊单明细（表头 + 当前版本 + 当前版本的行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostAllocationDetail(
      UUID allocationId,
      String sourceType,
      String sourceTypeLabel,
      String sourceRef,
      String sourceLabel,
      CostAllocationRevision currentRevision,
      List<CostAllocationLine> lines,
      List<String> revisions,
      String note) {}

  /** 拉一页成本分摊单（**实际头程分摊**的来源）。 */
  public CostAllocationPage costAllocations(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/cost-allocations")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CostAllocationPage.class);
  }

  /** 取一张分摊单的明细（逐 SKU 一行）。 */
  public CostAllocationDetail costAllocation(UUID allocationId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/catalog/cost-allocations/" + allocationId),
        bearer,
        CostAllocationDetail.class);
  }

  /** 成本构成里的一个分项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostComponentRow(
      String code, String label, String source, String kind, String unavailableReason, BigDecimal amount, String basis, String note) {}

  /** 成本构成明细（**预计头程分摊**的来源：{@code FIRST_MILE} 分项）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuCostDetail(
      UUID skuId,
      String skuCode,
      String skuName,
      String costMethod,
      String costMethodLabel,
      String currency,
      List<CostComponentRow> components,
      BigDecimal totalAmount,
      boolean totalComplete,
      List<String> missingComponents,
      LocalDate windowFrom,
      LocalDate windowTo,
      String basis,
      String note) {}

  /** 取一个 SKU 的成本构成（五个分项，含 FIRST_MILE 头程分摊）。 */
  public SkuCostDetail skuCost(UUID skuId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/catalog/sku-cost-compositions/" + skuId),
        bearer,
        SkuCostDetail.class);
  }

  // ================================================================ 工具

  /**
   * 条件查询参数。
   *
   * <p>⚠️ **空值不带进 URL**：{@code UriBuilder.queryParam(name, null)} 会渲染成
   * {@code ?name}（没有 {@code =}），上游按日期绑定时会直接 400。
   */
  private static UriBuilder param(UriBuilder builder, String name, Object value) {
    if (value == null) return builder;
    if (value instanceof String s && s.isBlank()) return builder;
    return builder.queryParam(name, value);
  }

  /**
   * 上游字符串 → BigDecimal。
   *
   * <p>⚠️ **解析失败必须炸**：上游把金额写成字符串是为了避免 JSON 数字丢精度，
   * 但一个解析不出来的金额说明**上游读错了** —— 静默当 0 会把「读不到」变成
   * 「这笔费用是 0」，那正是本模块要消灭的现象。
   */
  static BigDecimal decimal(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "catalog 服务返回的金额不是数字：" + raw);
    }
  }

  /**
   * 上游字符串 → LocalDate。
   *
   * <p>⚠️ 同 {@link #decimal}：解析失败必须炸，不能静默变成「今天」。
   */
  static LocalDate date(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return LocalDate.parse(raw.trim().substring(0, 10));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "catalog 服务返回的日期不是 ISO 日期：" + raw);
    }
  }
}
