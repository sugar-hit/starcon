package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.3.1.2 批量测算：对一批 SKU 批量跑利润测算，输出利润表 + 异常标记，支持筛选与导出。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对一批 SKU 批量测算，按当前成本与规则输出利润表，标记亏损品与低毛利品，支持导出并筛选（毛利率 &lt; X%、售价 &lt; Y）。
 *
 * **输入**：SKU 范围（按分类/品牌/店铺）；测算参数。
 *
 * **输出**：批量测算结果表 + 异常标记。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.2。**批量测算是选品与清库存决策的依据**：亏损品要么涨价要么清仓，人工逐个测算不可行。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块复用 5.3.1.1 的测算引擎，不重写第二套算术。</b>
 * 批量测算 = 把 {@code ProfitCalculatorService.calculate} 跑 N 次（每次换一个 SKU 与它的售价），
 * 把结果落成一张可筛选、可导出的表。所有金额口径（4 位小数）、费率口径（6 位小数）、
 * 决策就绪闸（成本不完整就不给毛利率数字）都与 5.3.1.1 **逐字相同** ——
 * 下游 5.4.1.2 批量调价会拿这里的数字直接改价，两套口径必然改错价。
 *
 * <p><b>🔴 售价的来源是本模块最贵的一个坑。</b>5.1 价目表未交付 ⇒ 本仓唯一的真实售价事实是
 * 5.5.2.1 异常告警的**价格观测台账**（V222 {@code price_anomaly_observation}）。
 * 取不到价的 SKU **跳过**（{@code SKIPPED_NO_PRICE}），绝不用 0 或估算值代替 ——
 * 0 会被算成「亏损品」，那是凭空造出来的清库存依据。
 */
public final class BatchCalculationModels {

  private BatchCalculationModels() {}

  // ================================================================ 词表

  /** 结果行状态。 */
  public enum ResultStatus {
    /** 测出来了。 */
    CALCULATED("已测算"),
    /** 没有可用售价 ⇒ 跳过（不是「亏损」，是「没测」）。 */
    SKIPPED_NO_PRICE("跳过（无价格观测）");

    private final String label;

    ResultStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ResultStatus parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /**
   * 异常标记（蓝图的「标记亏损品与低毛利品」）。
   *
   * <p><b>🔴 两个标记互斥</b>：亏损品（毛利额 &lt; 0）单列，低毛利品只标「还没亏、但毛利率低于阈值」。
   * 两者重叠的话一个 SKU 会被计两次，「问题品总数」就多算了。
   */
  public enum Flag {
    /** 亏损品：毛利额 &lt; 0。 */
    LOSS("亏损品"),
    /** 低毛利品：毛利额 ≥ 0 但毛利率低于阈值。 */
    LOW_MARGIN("低毛利品");

    private final String label;

    Flag(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Flag parse(String raw) {
      if (raw == null) return null;
      for (var f : values()) {
        if (f.name().equalsIgnoreCase(raw.trim())) return f;
      }
      return null;
    }
  }

  /** 结果排序。 */
  public enum Sort {
    /** 毛利率升序（最差的排前面）—— 默认。 */
    MARGIN_ASC("毛利率 从低到高"),
    /** 毛利率降序。 */
    MARGIN_DESC("毛利率 从高到低"),
    /** 售价升序。 */
    PRICE_ASC("售价 从低到高"),
    /** 售价降序。 */
    PRICE_DESC("售价 从高到低");

    private final String label;

    Sort(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Sort parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 输入

  /**
   * 批量测算请求（{@code POST /catalog/batch-calculations/runs}）。
   *
   * <p>🔴 跨币种（售价币种 ≠ 成本币种）时 {@code fxRate} 必填 ——
   * 绝不按 1:1 折算，那会把两个币种的金额加在一起（沿用 5.3.1.1 的纪律）。
   */
  public record BatchCalculationRequest(
      /** 平台码（来自 2.1.1.1 平台目录）。 */
      @NotBlank(message = "必须指定平台") String platformCode,
      /** 站点码（= 店铺）。必填：没有店铺就没有售价。 */
      @NotBlank(message = "必须指定店铺（站点）") String siteCode,
      /** 售价币种（ISO 4217 三位大写码）= 店铺的计价币种。 */
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 成本币种。空 = 与售价同币种。 */
      @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String costCurrency,
      /** 分类（spu.category 精确匹配）。空 = 不限定。 */
      String category,
      /** 品牌（spu.brand 精确匹配）。空 = 不限定。 */
      String brand,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 汇率：1 成本币种 = fx_rate 售价币种。跨币种时必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      /** 平台费率（售价的小数比例，0.15 = 15%）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 平台固定费（每单固定金额）。🔴 5.2.3.1 未交付 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 物流每单位运费。🔴 5.2.3.2 未开发 ⇒ 手工假设。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 月度固定成本（盈亏平衡销量的固定费用基数）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal monthlyFixedCost,
      /** 低毛利阈值（小数口径：0.15 = 15%）。空 = 0.15。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal lowMarginThreshold,
      /** 成本口径窗口起（含）。空 = 默认最近 90 天。 */
      @Schema(nullable = true) LocalDate costWindowFrom,
      /** 成本口径窗口止（含）。空 = 今天。 */
      @Schema(nullable = true) LocalDate costWindowTo,
      /** 备注：这一批测算是为了什么（选品 / 清库存 / 调价前评估）。 */
      String note) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>页面顶部固定显示它 —— 蓝图那句「批量测算是选品与清库存决策的依据」意味着
   * 运营会拿这一页的数字去决定「涨价还是清仓」，所以**口径必须写在数字旁边**，
   * 而不是藏在文档里。
   */
  public record BatchCalculationCapabilities(
      List<ProfitCalculatorModels.SiteOption> sites,
      String priceSourceNote,
      String upstreamNote,
      String downstreamNote,
      String decisionGateNote,
      String scopeNote,
      String flagNote,
      String filterNote,
      String exportNote,
      String skipNote,
      String limitNote,
      String precisionNote) {}

  // ================================================================ 结果

  /**
   * 批量测算结果的一行。
   *
   * <p>🔴 {@code status='SKIPPED_NO_PRICE'} 时所有金额列都是 <b>null</b>，
   * {@code loss} / {@code lowMargin} 都是 <b>false</b> ——
   * 「没测」与「测出来是亏的」是两件事，混起来会让清库存的人清掉一批没被测过的品。
   */
  public record BatchResultRow(
      UUID id,
      UUID runId,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) String brand,
      ResultStatus status,
      String skipReason,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) String priceSource,
      @Schema(nullable = true) OffsetDateTime priceObservedAt,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal knownCostTotal,
      @Schema(nullable = true) BigDecimal platformFeeAmount,
      @Schema(nullable = true) BigDecimal logisticsAmount,
      @Schema(nullable = true) BigDecimal totalDeductions,
      @Schema(nullable = true) BigDecimal grossProfit,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      @Schema(nullable = true) BigDecimal breakEvenPrice,
      String decisionBasis,
      boolean decisionReady,
      boolean loss,
      boolean lowMargin,
      List<String> blockingGaps) {}

  /**
   * 批次的汇总数字。
   *
   * <p>🔴 全部**读时聚合**，不落库：落库就会与结果表漂移，
   * 而且汇总数字天然受当前租户的数据可见范围约束（落库的汇总做不到这一点）。
   */
  public record BatchSummary(
      /** 范围内命中并写入结果的行数。 */
      long total,
      /** 真正测出结果的行数。 */
      long calculated,
      /** 因没有价格观测而跳过的行数。 */
      long skippedNoPrice,
      /** 测出来了但成本不完整（决策未就绪 ⇒ 没有毛利率数字）的行数。 */
      long notReady,
      /** 亏损品数（毛利额 &lt; 0）。 */
      long loss,
      /** 低毛利品数（还没亏，但毛利率低于阈值）。 */
      long lowMargin,
      /** 问题品总数 = 亏损品 + 低毛利品（两个标记互斥，所以可以直接相加）。 */
      long problem,
      /** 平均毛利率（只在有毛利率的行上取平均；没有则为 null，不是 0）。 */
      @Schema(nullable = true) BigDecimal avgGrossMarginRate,
      /** 售价合计（每单位口径的行合计，不是订单金额）。 */
      @Schema(nullable = true) BigDecimal totalUnitPrice,
      /** 毛利额合计（同上）。 */
      @Schema(nullable = true) BigDecimal totalGrossProfit) {}

  /** 一次批量测算（列表与详情共用同一个形状）。 */
  public record BatchRunDetail(
      UUID id,
      String platformCode,
      String siteCode,
      String currency,
      String costCurrency,
      String scopeCategory,
      String scopeBrand,
      String priceSource,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) BigDecimal monthlyFixedCost,
      BigDecimal lowMarginThreshold,
      @Schema(nullable = true) LocalDate costWindowFrom,
      @Schema(nullable = true) LocalDate costWindowTo,
      String note,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime createdAt,
      BatchSummary summary) {}

  /** 批次列表（分页）。 */
  public record BatchRunPage(List<BatchRunDetail> items, int page, int size, long total) {}

  /** 结果列表（分页）。 */
  public record BatchResultPage(List<BatchResultRow> items, int page, int size, long total) {}

  /** 结果筛选（列表与导出共用同一套筛选 —— 导出的就是查到的那些行）。 */
  public record ResultQuery(
      /** 毛利率 &lt; X（小数口径）。 */
      @Schema(nullable = true) BigDecimal marginBelow,
      /** 售价 &lt; Y。 */
      @Schema(nullable = true) BigDecimal priceBelow,
      /** 异常标记筛选：LOSS / LOW_MARGIN / 空 = 全部。 */
      @Schema(nullable = true) String flag,
      /** 状态筛选：CALCULATED / SKIPPED_NO_PRICE / 空 = 全部。 */
      @Schema(nullable = true) String status,
      /** 关键字（SKU 编码 / 名称模糊匹配）。 */
      @Schema(nullable = true) String keyword,
      /** 排序。 */
      @Schema(nullable = true) String sort,
      int page,
      int size) {}
}
