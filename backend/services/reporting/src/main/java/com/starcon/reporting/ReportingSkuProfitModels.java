package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.4.1.2 SKU 利润的**读模型契约**（蓝图「输出」句：SKU 利润表 + 瀑布图 + 订单明细钻取）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：本文件里凡是可以「算不出来」的数值
 * 一律用 {@code null} 表示，**不用 0 冒充**。净利算不出来时返回 {@code null}
 * + 一句原因（缺哪几项成本、归谁管），页面上渲染成「—（不可比）」。
 *
 * <p>⚠️ **本模块拥有 SKU 级的口径**：13.4.1.1 的利润报表按**经营模式**分行，而 SKU 维度的成本
 * 由上游 12.4.2.2 **按 SKU 维度**分摊（**不落到经营模式**）⇒ SKU 利润表的一行是
 * 「一个 SKU × 一个币种」，**收入按经营模式给出构成**（{@code modeBreakdown}），
 * 成本是 SKU 口径的合计。多模式混卖的 SKU 会明确标注「跨模式口径」。
 *
 * <p>⚠️ **不跨币种相加**：每一行都带币种，跨币种的合计不存在。
 */
public final class ReportingSkuProfitModels {

  private ReportingSkuProfitModels() {}

  // ================================================================ 成本项

  /**
   * 成本项（SKU 利润的七项，**逐字对齐蓝图职责句**）。
   *
   * <p>⚠️ **本模块不报「其它成本」**：它不在 13.4.1.2 的职责句里，归 13.4.1.1 ——
   * 两个模块各报各的，一个模块塞进别人的成本项就是第二个真相。
   */
  public enum Component {
    /** 单品收入（13.4.1.1 投影的**订单行金额**口径 —— SKU 维度只有这一个收入口径）。 */
    REVENUE("单品收入"),
    /** 采购成本（12.4 成本归集 + 12.4.2.2 分摊结果 · SKU 维度）。 */
    COGS("采购成本"),
    /** 头程（12.4 归集池 · HEAD_MILE_FEE 类别 · SKU 维度）。 */
    HEAD_FREIGHT("头程"),
    /** 平台费（12.3.1.2 结算明细 · 佣金）。 */
    PLATFORM_FEE("平台费"),
    /** 物流费（12.3.1.2 结算明细 · FBA 费用）。 */
    LOGISTICS_FEE("物流费"),
    /** 广告费（12.3.1.2 结算明细 · 广告）。 */
    AD_FEE("广告费"),
    /** 退款损失（9/11 售后域的**已成功退款**金额 · 13.4.1.2 自己拥有的一笔钱）。 */
    REFUND_LOSS("退款损失");

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

  // ================================================================ 取值状态

  /**
   * 一个成本项在某个 SKU 上的**取值状态**（四种，**没有第五种**）。
   *
   * <p>⚠️ **这四种必须分开说**：它们长得像「都是空」，含义却完全不同 ——
   *
   * <ul>
   *   <li>{@link #AVAILABLE} —— 有 SKU 级事实，取得到值；
   *   <li>{@link #MISSING} —— 上游在 SKU 维度上有这类成本（别的 SKU 有），**这个 SKU 没有**
   *       ⇒ **算不出来**（不是 0）；
   *   <li>{@link #NOT_ATTRIBUTABLE} —— 上游有这笔成本，但只分摊到**店铺 / 订单**，
   *       **SKU 维度拿不到** ⇒ 本模块**不重算分摊**，这一格不可归属；
   *   <li>{@link #NOT_APPLICABLE} —— 本租户在这个窗口内**压根没有这类成本** ⇒ 不参与净利计算。
   * </ul>
   */
  public enum Availability {
    /** 有 SKU 级事实。 */
    AVAILABLE("有事实"),
    /** 上游在 SKU 维度上有这类成本，这个 SKU 没有 ⇒ 算不出来。 */
    MISSING("缺这一项"),
    /** 上游只分摊到店铺 / 订单 ⇒ SKU 维度不可归属。 */
    NOT_ATTRIBUTABLE("不可归属到 SKU"),
    /** 本租户没有这类成本 ⇒ 不适用。 */
    NOT_APPLICABLE("不适用");

    private final String label;

    Availability(String label) {
      this.label = label;
    }

    /** 中文名。 */
    public String label() {
      return label;
    }
  }

  // ================================================================ 报表行

  /** 一个成本项在某个 SKU 上的取值。 */
  public record SkuProfitComponentAmount(
      String component,
      String label,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      String status,
      boolean available,
      @Schema(nullable = true) String reason) {}

  /** 一个 SKU 的收入构成（**按经营模式拆开**：不合并）。 */
  public record SkuProfitModeAmount(
      String operatingMode,
      String operatingModeLabel,
      BigDecimal revenue,
      @Schema(nullable = true) String note) {}

  /**
   * SKU 利润表的一行（一行 = 一个 SKU × 一个币种）。
   *
   * @param skuCode SKU 编码
   * @param currency 币种（**不跨币种相加**）
   * @param components 各项成本与收入（null = 算不出来，不是 0）
   * @param totalCost 成本合计（null = 有成本项算不出来）
   * @param netProfit 净利 = 单品收入 − 成本合计（null = 算不出来）
   * @param netMargin 净利率 = 净利 ÷ 单品收入（null = 收入缺失或为 0 ⇒ **不可比**）
   * @param netProfitComparable 净利是否可比（false ⇒ 页面显示「—（不可比）」）
   * @param missingComponents 算不出净利的成本项（中文名）
   * @param modeBreakdown **收入的经营模式构成**（成本不落到经营模式，见类注释）
   * @param factCount 构成事实条数
   * @param note 这一行的口径说明
   */
  public record SkuProfitRow(
      String skuCode,
      String currency,
      List<SkuProfitComponentAmount> components,
      @Schema(nullable = true) BigDecimal totalCost,
      @Schema(nullable = true) BigDecimal netProfit,
      @Schema(nullable = true) BigDecimal netMargin,
      boolean netProfitComparable,
      List<String> missingComponents,
      List<SkuProfitModeAmount> modeBreakdown,
      long factCount,
      String note) {}

  /**
   * SKU 利润响应。
   *
   * @param from 窗口起
   * @param to 窗口止
   * @param dimension 核算维度（恒为 {@code SKU}）
   * @param rows 报表行（**按 SKU × 币种**，不跨币种合并）
   * @param skuCount 有事实的 SKU 数
   * @param comparableCount 净利可比的 SKU 数
   * @param notComparableCount 净利不可比的 SKU 数（**不是「亏损的 SKU 数」**）
   * @param dataUpdatedAt 数据更新时间（没投影过任何事实时为 null）
   * @param dataUpdatedNote 数据更新时间的口径
   * @param caliberNote 口径说明（收入怎么算、成本取自哪一路上游、哪些算不出来）
   * @param warnings 警告（如实报缺项 / 不可归属 / 截断）
   */
  public record SkuProfitResponse(
      LocalDate from,
      LocalDate to,
      String dimension,
      List<SkuProfitRow> rows,
      long skuCount,
      long comparableCount,
      long notComparableCount,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 瀑布图

  /** 瀑布图的一步（收入 → 逐项成本 → 净利）。 */
  public record SkuProfitWaterfallStep(
      int step,
      String component,
      String label,
      @Schema(nullable = true) BigDecimal amount,
      String sign,
      @Schema(nullable = true) BigDecimal cumulative,
      boolean available,
      @Schema(nullable = true) String reason) {}

  /**
   * 构成瀑布。
   *
   * @param steps 逐步（顺序固定：单品收入 → 采购成本 → 头程 → 平台费 → 物流费 → 广告费 → 退款损失 → 净利）
   * @param netProfit 净利（null = 算不出来）
   * @param netProfitComparable 净利是否可比
   * @param note 口径说明
   */
  public record SkuProfitWaterfall(
      String skuCode,
      String currency,
      List<SkuProfitWaterfallStep> steps,
      @Schema(nullable = true) BigDecimal netProfit,
      boolean netProfitComparable,
      String note) {}

  // ================================================================ 订单明细钻取

  /** 订单级明细的一行（一行 = 一条事实：收入 / 成本 / 退款）。 */
  public record SkuProfitOrderRow(
      String component,
      String componentLabel,
      String operatingMode,
      String operatingModeLabel,
      String orderRef,
      String skuCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String sourceService,
      String sourceReference,
      String note) {}

  /** 订单级明细分页（真分页 + 真计数）。 */
  public record SkuProfitOrderPage(
      List<SkuProfitOrderRow> items,
      int page,
      int size,
      long total,
      String skuCode,
      String currency,
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
  public record SkuProfitCapabilities(
      boolean upstreamConnected,
      List<String> components,
      List<String> dimensions,
      List<String> upstreams,
      List<String> notDelivered,
      String ownerNote) {}

  // ================================================================ 刷新结果

  /** 退款事实投影的刷新结果。 */
  public record SkuProfitRefreshResult(
      String source,
      long fetched,
      long projected,
      long unattributable,
      String status,
      String note) {}
}
