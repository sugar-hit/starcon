package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.4.1.1 多模式利润报表的**读模型契约**（蓝图「输出」句：利润报表 + 多维下钻 + 口径说明）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：本文件里凡是可以「算不出来」的数值
 * 一律用 {@code null} 表示，**不用 0 冒充**。毛利算不出来时返回 {@code null}
 * + 一句原因（缺哪几个成本项、归谁管），页面上渲染成「—（不可比）」。
 *
 * <p>⚠️ **不跨币种相加**：每一行都带币种；只有调用方**显式**指定本位币、
 * 且 {@code reporting_currency_rate_snapshot} 里有生效日不晚于窗口末的汇率时，
 * 才额外给出本位币合计，并把**汇率与时点**一起返回。
 *
 * <p>⚠️ **不同经营模式的利润含义完全不同** ⇒ 每一行都带 {@code operatingMode}，
 * 模式之间**不合并**（报表里也不提供「全部模式合计」）。
 */
public final class ReportingProfitReportModels {

  private ReportingProfitReportModels() {}

  // ================================================================ 经营模式

  /**
   * 经营模式（2.3.2.1 的 {@code shop.operating_mode}）。
   *
   * <p>⚠️ **词表原样来自上游**：channel 的 CHECK 约束就是这五个值，
   * 本模块**不另起一套**（两处词表对不上时报表与上游会各说各话）。
   * {@code UNKNOWN} 不是上游的模式，是本模块对「挂不上店铺 / 上游没给模式」的记号。
   */
  public enum OperatingMode {
    /** 自发货。 */
    SELF_FULFILLED("自发货"),
    /** 全托管。 */
    FULL_CONSIGNMENT("全托管"),
    /** 半托管。 */
    SEMI_CONSIGNMENT("半托管"),
    /** 平台仓 / 海外仓。 */
    OVERSEAS_WAREHOUSE("平台仓"),
    /** 本地（自提 / 本地仓）。 */
    LOCAL("本地"),
    /** 未归属：挂不上店铺，或这笔钱上游没有给经营模式。 */
    UNKNOWN("未归属");

    private final String label;

    OperatingMode(String label) {
      this.label = label;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }

    /** 解析失败返回 null（调用方据此给一句可读错误，而不是抛枚举异常）。 */
    public static OperatingMode parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  // ================================================================ 成本项

  /**
   * 成本项（长表：每个成本项独立成行）。
   *
   * <p>⚠️ **词表穷尽蓝图职责句点名的成本费用项**。缺哪一项 ⇒ 毛利算不出来，
   * 而不是「那项是 0」。
   */
  public enum Component {
    /** 订单收入（trade 订单行金额）。 */
    REVENUE("订单收入"),
    /** 商品成本（12.4 成本归集 + 12.4.2.2 分摊结果）。 */
    COGS("商品成本"),
    /** 平台费（12.3.1.2 结算明细 · 佣金）。 */
    PLATFORM_FEE("平台费"),
    /** 物流费（12.3.1.2 结算明细 · FBA 费用）。 */
    LOGISTICS_FEE("物流费"),
    /** 广告费（12.3.1.2 结算明细 · 广告）。 */
    AD_FEE("广告费"),
    /** 头程费（12.4 归集池 · HEAD_MILE_FEE 类别）。 */
    HEAD_FREIGHT("头程费"),
    /** 其它归集成本（人员 / 办公 / 订阅等 12.4 归集池）。 */
    OTHER_COST("其它成本");

    private final String label;

    Component(String label) {
      this.label = label;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }

    /** 是不是减项（收入不是减项）。 */
    public boolean isCost() {
      return this != REVENUE;
    }

    /** 解析失败返回 null。 */
    public static Component parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  // ================================================================ 维度与粒度

  /** 核算维度（蓝图职责句点名的多维度）。 */
  public enum Dimension {
    /** 经营模式（必有的那一维）。 */
    MODE("经营模式"),
    /** 店铺。 */
    SHOP("店铺"),
    /** SKU。 */
    SKU("SKU"),
    /** SPU。 */
    SPU("SPU"),
    /** MSKU（平台商品编码）。 */
    MSKU("MSKU"),
    /** 订单。 */
    ORDER("订单");

    private final String label;

    Dimension(String label) {
      this.label = label;
    }

    /** 中文名。 */
    public String label() {
      return label;
    }

    /** 解析失败返回 null。 */
    public static Dimension parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /** 期间粒度：按天 / 按月。 */
  public enum Granularity {
    /** 按天。 */
    DAY("按天"),
    /** 按月。 */
    MONTH("按月");

    private final String label;

    Granularity(String label) {
      this.label = label;
    }

    /** 中文名。 */
    public String label() {
      return label;
    }

    /** 解析失败返回 null。 */
    public static Granularity parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var g : values()) {
        if (g.name().equalsIgnoreCase(raw.trim())) return g;
      }
      return null;
    }
  }

  // ================================================================ 报表行

  /** 一个成本项在某一行的取值。 */
  public record ProfitComponentAmount(
      String component,
      String label,
      @Schema(nullable = true) BigDecimal amount,
      boolean available,
      @Schema(nullable = true) String reason) {}

  /**
   * 利润报表的一行。
   *
   * @param dimension 核算维度
   * @param dimensionCode 维度取值（MODE 维度为空串）
   * @param dimensionLabel 维度取值的中文名（取不到就显示编码，**不编名字**）
   * @param operatingMode 经营模式（2.3.2.1 的原值）
   * @param operatingModeLabel 经营模式中文名
   * @param periodStart 期间起（桶）
   * @param periodEnd 期间止（桶）
   * @param currency 币种（**不跨币种相加**）
   * @param components 各项成本（null = 算不出来，不是 0）
   * @param totalCost 成本合计（null = 有成本项算不出来）
   * @param grossProfit 毛利 = 收入 − 成本合计（null = 算不出来）
   * @param grossMargin 毛利率 = 毛利 ÷ 收入（null = 收入缺失或为 0 ⇒ **不可比**）
   * @param grossProfitComparable 毛利是否可比（false ⇒ 页面显示「—（不可比）」）
   * @param missingComponents 算不出毛利的成本项（中文名）
   * @param baseCurrency 本位币（给了汇率快照时才有）
   * @param baseRevenue 本位币收入（null = 没有可用汇率）
   * @param baseGrossProfit 本位币毛利（null = 没有可用汇率）
   * @param baseRate 折算汇率（1 = 本位币自己）
   * @param baseRateAsOf 汇率时点（**必须显示**）
   * @param factCount 构成事实条数
   * @param note 这一行的口径说明
   */
  public record ProfitReportRow(
      String dimension,
      String dimensionCode,
      String dimensionLabel,
      String operatingMode,
      String operatingModeLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      List<ProfitComponentAmount> components,
      @Schema(nullable = true) BigDecimal totalCost,
      @Schema(nullable = true) BigDecimal grossProfit,
      @Schema(nullable = true) BigDecimal grossMargin,
      boolean grossProfitComparable,
      List<String> missingComponents,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal baseRevenue,
      @Schema(nullable = true) BigDecimal baseGrossProfit,
      @Schema(nullable = true) BigDecimal baseRate,
      @Schema(nullable = true) OffsetDateTime baseRateAsOf,
      long factCount,
      String note) {}

  /**
   * 利润报表响应。
   *
   * @param from 窗口起
   * @param to 窗口止
   * @param granularity 期间粒度
   * @param dimension 核算维度
   * @param rows 报表行（**按经营模式分行，不合并**）
   * @param modes 本租户的经营模式清单（每个模式带店铺数）
   * @param dataUpdatedAt 数据更新时间（两张表都空时为 null —— **「尚未投影任何利润事实」**）
   * @param dataUpdatedNote 数据更新时间的口径
   * @param caliberNote 口径说明（收入怎么算、成本取自哪一路上游、汇率用哪个快照）
   * @param warnings 警告（如实报截断 / 缺项）
   */
  public record ProfitReportResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      List<ProfitReportRow> rows,
      List<ProfitModeView> modes,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 一个经营模式的概况（店铺数来自 channel 2.3.2.1 的店铺档案投影）。 */
  public record ProfitModeView(
      String operatingMode,
      String operatingModeLabel,
      long shopCount,
      List<String> shopCodes,
      String note) {}

  // ================================================================ 下钻

  /** 下钻构成明细的一行（一行 = 一个成本项事实）。 */
  public record ProfitDrilldownRow(
      String component,
      String componentLabel,
      String operatingMode,
      String operatingModeLabel,
      String shopCode,
      String skuCode,
      String spuCode,
      String mskuCode,
      String orderRef,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String sourceService,
      String sourceReference,
      String note) {}

  /** 下钻构成明细分页（真分页 + 真计数）。 */
  public record ProfitDrilldownPage(
      List<ProfitDrilldownRow> items,
      int page,
      int size,
      long total,
      String dimension,
      String dimensionCode,
      String operatingMode,
      String note) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些上游真接通、哪些确实算不出来、归谁管。
   *
   * @param upstreamConnected 上游是否真接通
   * @param components 本模块覆盖的成本项
   * @param dimensions 本模块支持的核算维度
   * @param upstreams 真接通的上游（逐条写明取哪条数据）
   * @param notDelivered **确实算不出来**的部分（每条写清原因与影响）
   * @param ownerNote 口径归属说明（谁拥有哪一段口径）
   */
  public record ProfitReportCapabilities(
      boolean upstreamConnected,
      List<String> components,
      List<String> dimensions,
      List<String> upstreams,
      List<String> notDelivered,
      String ownerNote) {}

  // ================================================================ 刷新结果

  /** 一路上游的刷新结果。 */
  public record ProfitRefreshResult(
      String source,
      long fetched,
      long projected,
      String status,
      String note) {}
}
