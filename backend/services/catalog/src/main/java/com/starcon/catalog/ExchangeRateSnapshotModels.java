package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.2.2 汇率快照：冻结的汇率、换算过程与舍入规则配置。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：在单据/定价生效时冻结所用汇率，保留换算过程（原价、汇率、舍入方式、目标币金额），保证历史利润可复算。
 *
 * **输入**：定价/单据生成时的汇率；舍入规则配置。
 *
 * **输出**：汇率快照记录；利润复算能力。
 *
 * **关系**：上游 = 5.2.2.1；下游 = 09 订单、12 财务、13 利润。**汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变。
 *
 * </blockquote>
 *
 * <p><b>🔴 冻结 = 不可改写</b>：快照行里的汇率、舍入方式、目标币金额都是**当时的副本**，
 * 不 JOIN 汇率表取值 —— 汇率表改了、来源被停用了，历史快照一个数字都不动。
 *
 * <p><b>🔴 方向必须显式</b>（沿用 5.2.2.1 的纪律）：一条快照说清楚
 * 「用的是汇率表里的哪一条（{@code rateId}）、方向朝哪边（{@code conversionDirection}）、
 * 原价站在哪一侧（{@code sourceCurrency} → {@code targetCurrency}）」。
 */
public final class ExchangeRateSnapshotModels {

  private ExchangeRateSnapshotModels() {}

  // ================================================================ 词表

  /**
   * 单据类型：快照挂在哪张单据上。
   *
   * <p>刻意**不**建外键：09 订单、12 财务的单据不在本库（AGENTS.md「模块边界纪律」：
   * 禁止跨服务数据库访问），只能用逻辑引用 + 单据号定位。
   */
  public enum DocumentType {
    /** 价目表（5.1.1.1）。 */
    PRICE_LIST("价目表", "5.1.1.1 价目表：一份价目表生效时冻结该表用到的汇率。"),
    /** 价目项（5.1.1.2）。 */
    PRICE_LIST_ITEM("价目项", "5.1.1.2 价目项：单个价目项定出目标币金额时冻结当时的汇率。"),
    /** 定价模板（5.3.2.1）。 */
    PRICING_TEMPLATE("定价模板", "5.3.2.1 定价模板：模板落到具体站点价时冻结当时的汇率。"),
    /** 利润测算（5.3.1.1）。 */
    PROFIT_CALCULATION("利润测算", "5.3.1.1 利润测算：一次测算里每一项换算各自冻结自己的汇率。"),
    /** 订单（09 订单侧的单据）。 */
    ORDER("订单", "09 订单侧的文档：订单生效时冻结当时的汇率，供 12 财务与 13 利润复算。"),
    /** 结算（12 财务侧的单据）。 */
    SETTLEMENT("结算", "12 财务侧的文档：结算折算时冻结当时的汇率，历史结算不随汇率变动而变。");

    private final String label;
    private final String basis;

    DocumentType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static DocumentType parse(String raw) {
      if (raw == null) return null;
      for (var type : values()) {
        if (type.name().equalsIgnoreCase(raw.trim())) return type;
      }
      return null;
    }
  }

  /**
   * 原价站在冻结汇率的哪一侧。
   *
   * <p>方向**不是**靠约定，是靠这个枚举 + 数据库 CHECK 约束表达：
   * {@code BASE_TO_QUOTE} = 乘（原价是基准币），{@code QUOTE_TO_BASE} = 除（原价是报价币）。
   */
  public enum ConversionDirection {
    /** 基准币 → 报价币：目标金额 = 原价 × 汇率。 */
    BASE_TO_QUOTE("基准币→报价币", "目标币金额 = 原价 × 汇率（原价是这条汇率的基准币那一侧）。"),
    /** 报价币 → 基准币：目标金额 = 原价 ÷ 汇率（反向汇率由正向推导，不另存一条）。 */
    QUOTE_TO_BASE("报价币→基准币", "目标币金额 = 原价 ÷ 汇率（原价是这条汇率的报价币那一侧；反向汇率由正向推导）。");

    private final String label;
    private final String basis;

    ConversionDirection(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static ConversionDirection parse(String raw) {
      if (raw == null) return null;
      for (var direction : values()) {
        if (direction.name().equalsIgnoreCase(raw.trim())) return direction;
      }
      return null;
    }
  }

  /**
   * 舍入方式：与 {@code java.math.RoundingMode} 的六个取值逐字对应。
   *
   * <p>不另造词表 —— 服务层直接映射到 BigDecimal 的舍入模式，页面上显示中文名。
   */
  public enum RoundingModeOption {
    /** 四舍五入。 */
    HALF_UP(java.math.RoundingMode.HALF_UP, "四舍五入", "0.5 向上进位（最常用口径）。"),
    /** 五舍六入。 */
    HALF_DOWN(java.math.RoundingMode.HALF_DOWN, "五舍六入", "恰好 0.5 时向下舍（比四舍五入保守）。"),
    /** 截断。 */
    DOWN(java.math.RoundingMode.DOWN, "截断", "直接截去多余小数（向零），不做任何进位。"),
    /** 进位。 */
    UP(java.math.RoundingMode.UP, "进位", "只要有零头就进位（远离零）。"),
    /** 向上取整。 */
    CEILING(java.math.RoundingMode.CEILING, "向上取整", "向 +∞ 取整（负数也向上）。"),
    /** 向下取整。 */
    FLOOR(java.math.RoundingMode.FLOOR, "向下取整", "向 -∞ 取整（负数也向下）。");

    private final java.math.RoundingMode mode;
    private final String label;
    private final String basis;

    RoundingModeOption(java.math.RoundingMode mode, String label, String basis) {
      this.mode = mode;
      this.label = label;
      this.basis = basis;
    }

    public java.math.RoundingMode mode() {
      return mode;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static RoundingModeOption parse(String raw) {
      if (raw == null) return null;
      for (var option : values()) {
        if (option.name().equalsIgnoreCase(raw.trim())) return option;
      }
      return null;
    }
  }

  // ================================================================ 舍入规则配置

  /** 一条舍入规则（租户级配置）。 */
  public record RoundingRuleView(
      UUID id,
      String code,
      String name,
      /** 适用目标币种；null = 通用兜底规则。 */
      String targetCurrency,
      String roundingMode,
      String roundingModeLabel,
      String roundingModeBasis,
      int roundingScale,
      boolean enabled,
      int priority,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 新建舍入规则。 */
  public record RoundingRuleInput(
      @NotBlank
          @Pattern(regexp = "[A-Za-z0-9_]{2,32}", message = "规则代码请使用 2~32 位字母、数字或下划线")
          String code,
      @NotBlank @Size(max = 100) String name,
      @Pattern(regexp = "[A-Za-z]{3}", message = "适用币种请用三位大写币种代码；通用规则请留空")
          String targetCurrency,
      @NotBlank String roundingMode,
      @NotNull @Min(0) @Max(6) Integer roundingScale,
      Boolean enabled,
      Integer priority,
      @Size(max = 500) String note) {}

  /** 修改舍入规则（走乐观锁）。 */
  public record RoundingRuleUpdate(
      @NotBlank @Size(max = 100) String name,
      @Pattern(regexp = "[A-Za-z]{3}", message = "适用币种请用三位大写币种代码；通用规则请留空")
          String targetCurrency,
      @NotBlank String roundingMode,
      @NotNull @Min(0) @Max(6) Integer roundingScale,
      @NotNull Boolean enabled,
      @NotNull Integer priority,
      @Size(max = 500) String note,
      @NotNull Long version) {}

  // ================================================================ 汇率快照

  /**
   * 一条冻结的汇率快照。
   *
   * <p>{@code rateDisplay} 是**整句**（「1 USD = 7.1234 CNY」），
   * {@code conversionDisplay} 是**整句换算过程**（「100.00 USD × 7.1234 = 712.34 CNY」），
   * 页面直接用这两句，不允许把裸数字单独显示。
   */
  public record SnapshotView(
      UUID id,
      String documentType,
      String documentTypeLabel,
      UUID documentId,
      String documentNo,
      String lineKey,
      String sourceCurrency,
      BigDecimal sourceAmount,
      String targetCurrency,
      BigDecimal targetAmount,
      /** 「1 USD = 7.1234 CNY」—— 冻结的那条汇率，带完整方向。 */
      String rateDisplay,
      UUID rateId,
      String rateBaseCurrency,
      String rateQuoteCurrency,
      BigDecimal rate,
      String conversionDirection,
      String conversionDirectionLabel,
      String conversionDirectionBasis,
      LocalDate rateEffectiveDate,
      String rateSourceCode,
      String roundingRuleCode,
      String roundingMode,
      String roundingModeLabel,
      int roundingScale,
      /** 「100.00 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」—— 整条换算过程。 */
      String conversionDisplay,
      OffsetDateTime frozenAt,
      OffsetDateTime createdAt,
      String note) {}

  /**
   * 冻结一条汇率快照。
   *
   * <p>汇率可以按 {@code rateId} 指定（最明确），也可以按「币种对 + 生效日」查最新一条
   * （{@code baseCurrency} / {@code quoteCurrency} / {@code asOf}）。
   */
  public record SnapshotInput(
      /** 直接指定要冻结的汇率记录（uuid）。 */
      UUID rateId,
      /** 按币种对查汇率时用（与 quoteCurrency / asOf 一起）。 */
      @Pattern(regexp = "[A-Za-z]{3}", message = "基准币种请用三位大写币种代码")
          String baseCurrency,
      @Pattern(regexp = "[A-Za-z]{3}", message = "报价币种请用三位大写币种代码")
          String quoteCurrency,
      /** 按币种对查汇率时的截止日（含当天）；缺省为今天。 */
      LocalDate asOf,
      /** 原价币种：必须是这条汇率的基准币或报价币之一。 */
      @NotBlank
          @Pattern(regexp = "[A-Za-z]{3}", message = "原价币种请用三位大写币种代码")
          String sourceCurrency,
      @NotNull BigDecimal sourceAmount,
      @NotBlank String documentType,
      @NotNull UUID documentId,
      @Size(max = 100) String documentNo,
      @Size(max = 100) String lineKey,
      /** 显式指定舍入规则码；缺省按目标币种解析（先具体后通用）。 */
      String roundingRuleCode,
      @Size(max = 500) String note) {}

  /** 台账查询条件。 */
  public record SnapshotListQuery(
      String documentType,
      UUID documentId,
      String sourceCurrency,
      String targetCurrency,
      LocalDate dateFrom,
      LocalDate dateTo,
      String keyword,
      int page,
      int size) {}

  /** 台账分页结果。 */
  public record SnapshotListPage(
      List<SnapshotView> items, long total, int page, int size, String note) {}

  /**
   * 复算请求：复算一条快照，或复算某张单据上的全部快照。
   *
   * <p>{@code snapshotId} 与 {@code documentType + documentId} 至少给一组。
   */
  public record ReplayRequest(UUID snapshotId, String documentType, UUID documentId) {}

  /**
   * 一条复算结果。
   *
   * <p>{@code recomputedTargetAmount} 是**拿冻结的四个值重新算一遍**的结果，
   * {@code matched} 说明它与冻结时写下的 {@code targetAmount} 是否一致。
   */
  public record SnapshotReplayRow(
      UUID snapshotId,
      String documentType,
      UUID documentId,
      String documentNo,
      String lineKey,
      String sourceCurrency,
      BigDecimal sourceAmount,
      String targetCurrency,
      BigDecimal targetAmount,
      BigDecimal recomputedTargetAmount,
      boolean matched,
      BigDecimal difference,
      String rateDisplay,
      String conversionDirection,
      String roundingMode,
      String roundingModeLabel,
      int roundingScale,
      String roundingRuleCode,
      LocalDate rateEffectiveDate,
      String rateSourceCode,
      String note) {}

  /** 一次复算的结论。 */
  public record SnapshotReplayReport(
      List<SnapshotReplayRow> rows, int total, int matched, int mismatched, String note) {}

  // ================================================================ 能力声明

  /** 能力声明里的一条单据类型说明。 */
  public record DocumentTypeOption(String value, String label, String basis) {}

  /** 能力声明里的一条舍入方式说明。 */
  public record RoundingModeInfo(String value, String label, String basis) {}

  /** 能力声明：方向规则、冻结规则、舍入规则、上下游与数据归属。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SnapshotCapabilities(
      List<DocumentTypeOption> documentTypes,
      List<RoundingModeInfo> roundingModes,
      List<RoundingRuleView> roundingRules,
      /** 方向规则的完整说明（页面上原样显示）。 */
      String directionRule,
      /** 冻结与只追加的规则说明。 */
      String immutabilityRule,
      /** 舍入规则的解析顺序说明。 */
      String roundingRule,
      /** 上游 5.2.2.1 的关系说明。 */
      String upstreamNote,
      /** 下游关系说明。 */
      String downstreamNote,
      /** 数据归属说明。 */
      String ownershipNote,
      /** 复算能力的约定说明。 */
      String replayContractNote) {}
}
