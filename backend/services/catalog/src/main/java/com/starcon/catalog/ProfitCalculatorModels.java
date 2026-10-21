package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.3.1.1 利润测算器：把「售价 → 每一项扣减 → 毛利」的完整拆解落成可保存、可复算的测算方案。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：输入 SKU + 目标店铺 + 售价，输出完整利润拆解：售价 → 平台费用 → 物流成本 → 采购成本 → 头程 → 其他 → 毛利额与毛利率；反向支持输入目标毛利率倒推可承受售价。
 *
 * **输入**：SKU、店铺、币种、售价或目标毛利率、数量（用于阶梯与运费）；成本、费用规则、汇率快照。
 *
 * **输出**：利润瀑布图 + 保本价 + 目标毛利价 + 盈亏平衡销量；参数可分享为方案。
 *
 * **关系**：上游 = 5.2 全部、6 采购成本、2 平台参数；下游 = 5.3.2.1 模板、5.4 调价。**这是定价决策的核心工具**，必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字。
 *
 * </blockquote>
 *
 * <p><b>🔴 本模块最贵的一个坑：上游的「5.2 全部」是卷级引用，而 5.2 下面六个模块
 * 只有 5.2.1.1 已交付。</b>缺的两项（平台费用 5.2.3.1、物流与头程费用 5.2.3.2）
 * 恰恰是毛利里最大的两块：平台佣金通常是售价的 8%~15%，尾程物流是每单几美元量级。
 * 所以「不扣这两项算出来的毛利率」是**系统性虚高**的。
 *
 * <p>⇒ 本模块的纪律写在<b>服务层</b>而不是页面上：{@code decisionReady=false} 时
 * {@code grossProfit} 与 {@code grossMarginRate} 一律是 <b>null</b>（不是 0、不是估算值），
 * 只给一个「售价减已知扣减后还剩多少可用于覆盖缺失扣减」的余额。
 * 理由：5.3.2.1 模板与 5.4 调价会**程序化**地消费本模块的输出，
 * 页面上的一句提示拦不住一个自动改价的规则。
 */
public final class ProfitCalculatorModels {

  private ProfitCalculatorModels() {}

  // ================================================================ 词表

  /** 测算方向。两种方向共用同一套扣减结构（见 {@link ProfitCalculatorRules}）。 */
  public enum CalcMode {
    /** 给定售价，算利润拆解。 */
    PRICE("按售价测算"),
    /** 给定目标毛利率，倒推可承受售价。 */
    TARGET_MARGIN("按目标毛利率倒推售价");

    private final String label;

    CalcMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CalcMode parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /**
   * 瀑布图上的一个扣减项。
   *
   * <p><b>枚举只增不改</b>：扣减项会进测算方案（{@code profit_calculator_scenario}），
   * 改语义会让历史方案读起来是错的。
   */
  public enum Deduction {
    PLATFORM_FEE("平台费用"),
    LOGISTICS("物流成本"),
    PURCHASE("采购成本"),
    FIRST_MILE("头程分摊"),
    PACKAGING("包装成本"),
    DUTY("关税"),
    OTHER_DIRECT("其他直接成本");

    private final String label;

    Deduction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Deduction parse(String raw) {
      if (raw == null) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /** 扣减项的来源类型。 */
  public enum SourceKind {
    /** 来自上游规则模块（届时上游会接管）。 */
    RULE("上游规则"),
    /** 来自上游事实（已发生的业务单据）。 */
    FACT("上游事实"),
    /** 本服务自持的参数。 */
    PARAMETER("本服务参数"),
    /** 由事实按口径派生。 */
    DERIVED("派生值"),
    /** 人工假设：上游规则模块未交付时由人填的期望值。 */
    MANUAL_ASSUMPTION("手工假设");

    private final String label;

    SourceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 测算结果的<b>决策就绪度</b>。
   *
   * <p>这是本模块对外最重要的一个字段：下游（5.3.2.1 模板、5.4 调价）必须先看它，
   * 再决定能不能拿这个结果去改价。
   */
  public enum DecisionBasis {
    /** 每一项扣减都有上游规则来源 —— 结果可直接用于定价决策。 */
    COMPLETE_FROM_RULES("全部来自上游规则"),
    /** 每一项扣减都有值，但缺的那几项是<b>手工假设</b> —— 可用于 what-if，不等于规则口径。 */
    COMPLETE_FROM_MANUAL_ASSUMPTIONS("含手工假设"),
    /** 有扣减项取不到 —— <b>没有</b>毛利率数字，只给「剩余可覆盖缺失扣减的金额」。 */
    INCOMPLETE("成本不完整");

    private final String label;

    DecisionBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 输入

  /**
   * 测算输入（{@code POST /calculate} 与 {@code POST /scenarios} 共用同一个形状）。
   *
   * <p><b>手工假设</b>（{@code platformFeeRate} / {@code platformFeeFixed} /
   * {@code logisticsCostPerUnit} / {@code fxRate} / {@code monthlyFixedCost}）
   * 是上游规则模块未交付时**唯一合法**的来源。它们在响应里会被标成
   * {@code MANUAL_ASSUMPTION} 并点名将来由谁接管（ownerModule），
   * 能力声明里对应扣减项的 {@code available} 仍然是 {@code false}。
   */
  public record CalculateRequest(
      @NotNull UUID skuId,
      /** 目标店铺（站点 code，来自 2.1.1.1 平台目录）。可空 = 不限定站点。 */
      String siteCode,
      /** 目标平台 code。可空。 */
      String platformCode,
      /** 售价币种（ISO 4217 三位大写码）。 */
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String currency,
      /** 成本币种。空 = 与售价同币种（不需要汇率）。 */
      @Pattern(regexp = "^[A-Za-z]{3}$", message = "币种必须是三位大写字母") String costCurrency,
      @NotNull CalcMode mode,
      /** 售价（PRICE 模式必填）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal inputPrice,
      /** 目标毛利率（小数口径：0.25 = 25%）。TARGET_MARGIN 模式必填。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal targetMarginRate,
      /** 数量（用于阶梯与运费）。空 = 1。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 汇率：1 成本币种 = fx_rate 售价币种。跨币种时必填。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
      /** 手工假设：平台费率（售价的小数比例，0.15 = 15%）。5.2.3.1 未交付时的唯一来源。 */
      @Schema(nullable = true)
          @DecimalMin(value = "0")
          @DecimalMax(value = "0.999999")
          BigDecimal platformFeeRate,
      /** 手工假设：平台固定费（每单固定金额，订阅费等）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal platformFeeFixed,
      /** 手工假设：物流每单位运费（没有阶梯时用）。5.2.3.2 未交付时的唯一来源。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal logisticsCostPerUnit,
      /** 手工假设：月度固定成本（算盈亏平衡销量的固定费用基数）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal monthlyFixedCost,
      /** 物流阶梯（数量 → 每单位运费）。空 = 用 logisticsCostPerUnit。 */
      @Valid List<TierInput> logisticsTiers,
      /** 成本口径窗口起（含）。空 = 默认最近 90 天。采购成本由窗口内的到货事实派生。 */
      @Schema(nullable = true) java.time.LocalDate costWindowFrom,
      /** 成本口径窗口止（含）。空 = 今天。 */
      @Schema(nullable = true) java.time.LocalDate costWindowTo,
      /** 方案备注：这次测算为什么这样假设。 */
      String note) {}

  /** 物流阶梯的一段：覆盖到第几单（{@code upToQuantity} 为空 = 之后无限），每单位运费。 */
  public record TierInput(
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal upToQuantity,
      @NotNull @DecimalMin(value = "0") BigDecimal perUnitCost) {}

  // ================================================================ 能力声明

  /** 能力声明里的一个扣减项：能不能取到、归谁管、缺了会怎样。 */
  public record DeductionCapability(
      String code,
      String label,
      String source,
      String sourceKind,
      boolean available,
      String ownerModule,
      String impact,
      String basis,
      String note) {}

  /** 目标店铺（站点）选项：来自 2.1.1.1 平台目录（跨服务只读）。 */
  public record SiteOption(
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      String countryCode,
      String defaultCurrency,
      boolean vatRequired) {}

  /**
   * 能力声明。页面顶部固定显示它 ——
   * 蓝图那句「必须显式列出每一项扣减」的第一层落点在这里。
   */
  public record ProfitCalculatorCapabilities(
      List<DeductionCapability> deductions,
      List<SiteOption> sites,
      String fxNote,
      String decisionGateNote,
      String costBasisNote,
      String upstreamNote,
      String downstreamNote,
      String sourceNote,
      String modeNote,
      String actionNote) {}

  // ================================================================ 测算结果

  /** 一条来源明细（展开看来源时的一行「标签 / 取值」）。 */
  public record SourceDetail(String label, String value) {}

  /**
   * 一个扣减项的来源。
   *
   * <p>蓝图加粗句「<b>必须显式列出每一项扣减并允许展开查看来源</b>」的落点：
   * 每一项扣减都要能回答「哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间」。
   */
  public record DeductionSource(
      String ownerModule,
      String service,
      String table,
      String kind,
      String basis,
      @Schema(nullable = true) String effectiveFrom,
      String trace,
      List<SourceDetail> details) {}

  /** 瀑布图的一行。 */
  public record WaterfallLine(
      String code,
      String label,
      boolean deduction,
      boolean available,
      boolean partial,
      String sourceKind,
      String ownerModule,
      String basis,
      @Schema(nullable = true) String unavailableReason,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal totalAmount,
      String currency,
      DeductionSource source) {}

  /** 一个缺口：哪一项取不到、归谁管、缺了会怎样、怎么补。 */
  public record BlockingGap(
      String code,
      String label,
      String ownerModule,
      String impact,
      String remedy,
      boolean manualAssumptionAccepted) {}

  /** 汇率换算口径（方向显式 —— 5.2.2.1 的加粗句）。 */
  public record FxView(
      String costCurrency,
      String currency,
      @Schema(nullable = true) BigDecimal rate,
      String direction,
      String sourceKind,
      String ownerModule,
      boolean available,
      String note) {}

  /** 物流阶梯在给定数量下的分段结果（每一段多少单、每单位多少运费）。 */
  public record LogisticsBand(
      int seq,
      @Schema(nullable = true) BigDecimal fromQuantity,
      @Schema(nullable = true) BigDecimal upToQuantity,
      BigDecimal units,
      BigDecimal perUnitCost,
      BigDecimal bandTotal) {}

  /**
   * 一次测算的完整结果。
   *
   * <p><b>🔴 {@code decisionReady=false} 时 {@code grossProfit} 与 {@code grossMarginRate}
   * 必为 null</b>（不是 0、不是估算）。此时只有 {@code revenueLeftForMissingDeductions}
   * 有值 —— 那是「售价减掉已知扣减后还剩多少可用于覆盖缺失扣减」，
   * <b>不是毛利</b>，名字与语义都刻意不跟毛利混。
   */
  public record ProfitCalculation(
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String siteCode,
      String currency,
      String costCurrency,
      CalcMode mode,
      BigDecimal quantity,
      DecisionBasis decisionBasis,
      boolean decisionReady,
      List<BlockingGap> blockingGaps,
      FxView fx,
      List<WaterfallLine> waterfall,
      @Schema(nullable = true) BigDecimal revenue,
      @Schema(nullable = true) BigDecimal knownDeductionTotal,
      @Schema(nullable = true) BigDecimal revenueLeftForMissingDeductions,
      @Schema(nullable = true) BigDecimal grossProfit,
      @Schema(nullable = true) BigDecimal grossMarginRate,
      @Schema(nullable = true) BigDecimal breakEvenPrice,
      @Schema(nullable = true) BigDecimal targetMarginPrice,
      @Schema(nullable = true) BigDecimal breakEvenVolume,
      @Schema(nullable = true) BigDecimal unitContribution,
      List<LogisticsBand> logisticsBands,
      String costMethod,
      @Schema(nullable = true) OffsetDateTime costEffectiveFrom,
      String grossProfitSuppressedReason,
      String breakEvenPriceNote,
      String targetMarginPriceNote,
      String breakEvenVolumeNote,
      String decisionNote) {}

  // ================================================================ 测算方案

  /** 方案列表的一行。 */
  public record ScenarioSummary(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String siteCode,
      String currency,
      String costCurrency,
      CalcMode mode,
      @Schema(nullable = true) BigDecimal inputPrice,
      @Schema(nullable = true) BigDecimal targetMarginRate,
      BigDecimal quantity,
      DecisionBasis decisionBasis,
      @Schema(nullable = true) String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 方案详情 = 方案本身 + 用它复算出来的结果。 */
  public record ScenarioDetail(
      ScenarioSummary summary,
      CalculateRequest request,
      List<TierInput> logisticsTiers,
      @Schema(nullable = true) BigDecimal platformFeeRate,
      @Schema(nullable = true) BigDecimal platformFeeFixed,
      @Schema(nullable = true) BigDecimal logisticsCostPerUnit,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) BigDecimal monthlyFixedCost,
      String note,
      @Schema(nullable = true) ProfitCalculation calculation) {}

  /** 方案列表（分页）。 */
  public record ScenarioPage(List<ScenarioSummary> items, int page, int size, long total) {}
}
