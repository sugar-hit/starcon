package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.4.3.1 利润预警的**读模型契约**（蓝图「输出」句：预警清单 + 通知 + 处置入口）。
 *
 * <p>⚠️ **「判不出来」与「没异常」是两件事**：本文件里凡是判不了的场合一律是
 * {@code null} + 一句原因（{@code undecided} 清单），**绝不写成「正常」**。
 * 阈值历史不够、成本项缺失、订单毛利算不出来 —— 这些都会让判定做不成，
 * 而把它们悄悄写成「无异常」比误报更危险（误报会被处理，无异常会被忽略）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：占比、倍数、基线在算不出来时一律
 * {@code null}（页面上是「—」），**不用 0 冒充**。
 *
 * <p>⚠️ **多币种绝不相加**：每一行都带自己的币种。
 *
 * <p>⚠️ **阈值不写死在代码里**：它落在 {@code reporting_profit_alert_rule}，
 * 由租户自己定；服务层只播种一份**默认**规则。
 */
public final class ReportingProfitAlertModels {

  private ReportingProfitAlertModels() {}

  // ================================================================ 预警类型

  /**
   * 预警类型（**穷尽蓝图职责句点名的五类**）。
   *
   * <p>⚠️ **阈值单位由 (类型, 口径) 派生**（{@link #thresholdUnit}），
   * **不在库里存副本** —— 存一份副本就会与口径漂移（页面上「10」到底是 10% 还是 10 个百分点，
   * 是两种完全不同的含义）。
   */
  public enum AlertType {
    /** SKU 毛利转负（归属维度 = SKU）。 */
    SKU_NEGATIVE_GROSS_PROFIT(
        "SKU 毛利转负",
        "SKU",
        "FIXED",
        new BigDecimal("0"),
        1,
        "CRITICAL",
        "毛利率跌破阈值线（默认 0 = 毛利转负）。**毛利率是窄口径的**："
            + "SKU 维度常常只有收入与商品成本，平台费 / 物流费 / 广告费不参与 —— "
            + "所以这是「按现有分摊口径转负」，不是「全口径转负」。"),
    /** 店铺利润率骤降（归属维度 = SHOP）。 */
    SHOP_MARGIN_DROP(
        "店铺利润率骤降",
        "SHOP",
        "BASELINE",
        new BigDecimal("10"),
        2,
        "WARNING",
        "店铺毛利率相对**历史基线**（前 N 期同口径窗口均值）下降的百分点。"
            + "也支持 FIXED 口径（毛利率跌破一条固定线）。"),
    /** 费用占比异常（归属维度 = SHOP）。 */
    FEE_RATIO_ANOMALY(
        "费用占比异常",
        "SHOP",
        "FIXED",
        new BigDecimal("80"),
        2,
        "WARNING",
        "费用（平台费 + 物流费 + 广告费 + 头程 + 其它成本）占**收入**的百分比。"
            + "也支持 BASELINE 口径（相对基线上升的百分点）。"),
    /** 广告亏损超标（归属维度 = SHOP）。 */
    AD_LOSS_EXCEEDED(
        "广告亏损超标",
        "SHOP",
        "FIXED",
        new BigDecimal("1"),
        1,
        "CRITICAL",
        "广告费 ÷ 店铺毛利的**倍数**。倍数 ≥ 阈值 = 广告这一项已经吃掉了全部毛利。"
            + "⚠️ **本模块不拉广告归因成交**（归因口径归 14 广告），"
            + "这里用的是 12.3.1.2 结算明细里的店铺级广告费。"),
    /** 订单级亏损订单占比突增（归属维度 = SHOP，指标在订单级算）。 */
    LOSS_ORDER_RATIO_SPIKE(
        "亏损订单占比突增",
        "SHOP",
        "BASELINE",
        new BigDecimal("10"),
        2,
        "WARNING",
        "店铺内**亏损订单数 ÷ 订单总数**。分母只算**毛利算得出来**的订单，"
            + "所以这个占比是**下界**（算不出毛利的订单不会算成盈利）。"
            + "蓝图关系句：「亏损订单占比是广告投放失控的早期信号，比 ROAS 下降更早出现」。");

    private final String label;
    private final String scopeType;
    private final String defaultMode;
    private final BigDecimal defaultValue;
    private final int defaultMinHistory;
    private final String defaultSeverity;
    private final String description;

    AlertType(
        String label,
        String scopeType,
        String defaultMode,
        BigDecimal defaultValue,
        int defaultMinHistory,
        String defaultSeverity,
        String description) {
      this.label = label;
      this.scopeType = scopeType;
      this.defaultMode = defaultMode;
      this.defaultValue = defaultValue;
      this.defaultMinHistory = defaultMinHistory;
      this.defaultSeverity = defaultSeverity;
      this.description = description;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }

    /** 归属维度（SKU / SHOP）。 */
    public String scopeType() {
      return scopeType;
    }

    /** 默认阈值口径（服务层播种用）。 */
    public String defaultMode() {
      return defaultMode;
    }

    /** 默认阈值取值（服务层播种用）。 */
    public BigDecimal defaultValue() {
      return defaultValue;
    }

    /** 默认最少历史期数（服务层播种用）。 */
    public int defaultMinHistory() {
      return defaultMinHistory;
    }

    /** 默认严重度（服务层播种用）。 */
    public String defaultSeverity() {
      return defaultSeverity;
    }

    /** 这类预警在算什么（页面上原样显示）。 */
    public String description() {
      return description;
    }

    /** 这类预警支不支持 BASELINE 口径。 */
    public boolean supportsBaseline() {
      return !this.equals(SKU_NEGATIVE_GROSS_PROFIT) && !this.equals(AD_LOSS_EXCEEDED);
    }

    /**
     * 阈值单位（**由类型与口径派生**）。
     *
     * <p>⚠️ 「骤降 10」与「骤降 10%」是两回事：BASELINE 口径的阈值单位是**百分点**，
     * FIXED 口径看类型而定 —— 毛利率 / 占比是百分数，广告倍数是倍数。
     */
    public String thresholdUnit(String thresholdMode) {
      if ("BASELINE".equals(thresholdMode)) return "PERCENTAGE_POINTS";
      if (this.equals(AD_LOSS_EXCEEDED)) return "MULTIPLE";
      return "PERCENT";
    }

    /** 解析失败返回 null（调用方据此给一句可读错误，而不是抛枚举异常）。 */
    public static AlertType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  // ================================================================ 严重度

  /** 命中后的严重度。 */
  public enum Severity {
    /** 提示。 */
    INFO("提示"),
    /** 警告。 */
    WARNING("警告"),
    /** 严重。 */
    CRITICAL("严重");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    /** 中文名。 */
    public String label() {
      return label;
    }

    /** 解析失败返回 null。 */
    public static Severity parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 预警清单

  /**
   * 一条预警。
   *
   * @param alertType 预警类型
   * @param alertTypeLabel 预警类型中文名
   * @param severity 严重度（来自租户自己的规则）
   * @param severityLabel 严重度中文名
   * @param scopeType 归属维度：{@code SKU} / {@code SHOP}
   * @param scopeKey 归属取值（SKU 编码 / 店铺编码）
   * @param scopeLabel 归属取值的中文说明
   * @param currency 币种（**不跨币种相加**）
   * @param metricCode 观测指标的代码（前端据此决定单位，不自己猜）
   * @param observedValue 观测值（百分数 / 百分点 / 倍数；null = 算不出来）
   * @param observedUnit 观测值单位：{@code PERCENT} / {@code PERCENTAGE_POINTS} / {@code MULTIPLE}
   * @param thresholdMode 阈值口径：{@code FIXED} / {@code BASELINE}
   * @param thresholdValue 配置的阈值取值
   * @param thresholdUnit 阈值单位
   * @param baselineValue 基线值（BASELINE 口径 = 前 N 期均值；FIXED 口径 = 上一窗口值，仅作参考）
   * @param baselineMode 基线口径说明
   * @param delta 与基线之差（null = 任一侧不可比）
   * @param revenue 该范围的收入（**仅供对照**，null = 没有收入事实）
   * @param grossProfit 该范围的毛利（null = **算不出来**，不是 0）
   * @param fingerprint 预警指纹（通知幂等键）
   * @param reason 为什么命中（**写清命中的是哪一条阈值**）
   * @param note 口径说明（**这一格是怎么算出来的**）
   * @param actions 处置入口（去哪儿处理）
   */
  public record ProfitAlert(
      String alertType,
      String alertTypeLabel,
      String severity,
      String severityLabel,
      String scopeType,
      String scopeKey,
      String scopeLabel,
      String currency,
      String metricCode,
      @Schema(nullable = true) BigDecimal observedValue,
      String observedUnit,
      String thresholdMode,
      BigDecimal thresholdValue,
      String thresholdUnit,
      @Schema(nullable = true) BigDecimal baselineValue,
      String baselineMode,
      @Schema(nullable = true) BigDecimal delta,
      @Schema(nullable = true) BigDecimal revenue,
      @Schema(nullable = true) BigDecimal grossProfit,
      String fingerprint,
      String reason,
      String note,
      List<ProfitAlertAction> actions) {}

  /**
   * 处置入口（**去哪儿处理**）。
   *
   * <p>⚠️ 链接指向**已经存在的**站内页面；本模块**不替下游模块写任何数据**
   * （5 调价 / 14 广告 / 1 工作台各自做自己的事）。
   *
   * @param label 入口名称
   * @param href 站内路径
   * @param description 这个入口能做什么（**写清边界**：本模块只给定位，不代替对方页面）
   */
  public record ProfitAlertAction(String label, String href, String description) {}

  /**
   * 一条**判不出来**的记录（**不是「没异常」**）。
   *
   * @param alertType 预警类型
   * @param scopeType 归属维度
   * @param scopeKey 归属取值
   * @param currency 币种
   * @param reason 为什么判不了（**一句能直接展示的话**）
   */
  public record ProfitAlertUndecided(
      String alertType,
      String alertTypeLabel,
      String scopeType,
      String scopeKey,
      String scopeLabel,
      String currency,
      String reason) {}

  /**
   * 预警清单的统计（按类型 / 按严重度 + 判不出来的数量）。
   *
   * @param total 命中的预警条数
   * @param byTypeCount 命中里出现了几种预警类型
   * @param bySeverityCount 命中里出现了几种严重度
   * @param undecidedCount **判不出来**的条数（**不是「没异常」**）
   * @param truncated 是否有内容因超上限被截断（**如实报截断**，不静默丢行）
   */
  public record ProfitAlertSummary(
      int total,
      int byTypeCount,
      int bySeverityCount,
      int undecidedCount,
      int criticalCount,
      int warningCount,
      int infoCount,
      boolean truncated) {}

  /**
   * 预警清单的一页（**真分页 + 真计数**）。
   *
   * @param from 窗口起
   * @param to 窗口止
   * @param alerts 本页的预警
   * @param undecided 判不出来的记录（**上限截断时如实报截断**）
   * @param summary 统计
   * @param page 页码（从 0 起）
   * @param size 每页条数
   * @param total 命中总数（**真计数**）
   * @param dataUpdatedAt 数据更新时间（卷 13 卷首「数据延迟要显示」）
   * @param dataUpdatedNote 这个时刻**具体指什么**
   * @param warnings 口径层面的提醒
   * @param caliberNote 口径说明
   */
  public record ProfitAlertPage(
      LocalDate from,
      LocalDate to,
      List<ProfitAlert> alerts,
      List<ProfitAlertUndecided> undecided,
      ProfitAlertSummary summary,
      int page,
      int size,
      long total,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      List<String> warnings,
      String caliberNote) {}

  // ================================================================ 阈值规则

  /**
   * 某类预警的判定阈值（**租户自己的口径**）。
   *
   * @param thresholdUnit 阈值单位（**由 (类型, 口径) 派生**，不落库）
   * @param updatedAt 最后修改时刻（没有 ⇒ null）
   */
  public record ProfitAlertRule(
      String alertType,
      String alertTypeLabel,
      String thresholdMode,
      BigDecimal thresholdValue,
      String thresholdUnit,
      int minHistoryPeriods,
      String severity,
      String severityLabel,
      boolean enabled,
      String description,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 改阈值的入参（**全部可选**：只改写进来的那几个字段）。 */
  public record ProfitAlertRuleInput(
      String thresholdMode,
      @Schema(nullable = true) BigDecimal thresholdValue,
      @Schema(nullable = true) Integer minHistoryPeriods,
      String severity,
      @Schema(nullable = true) Boolean enabled) {}

  // ================================================================ 通知

  /**
   * 一条通知投递记录。
   *
   * @param status 状态：{@code PENDING} / {@code ACKNOWLEDGED} / {@code DISMISSED}
   * @param acknowledgedBy 确认人（没确认 ⇒ null）
   */
  public record ProfitAlertNotification(
      String id,
      String alertType,
      String alertTypeLabel,
      String severity,
      String severityLabel,
      String scopeType,
      String scopeKey,
      String scopeLabel,
      String currency,
      String channel,
      String status,
      String statusLabel,
      String title,
      String detail,
      @Schema(nullable = true) BigDecimal observedValue,
      String observedUnit,
      BigDecimal thresholdValue,
      LocalDate windowStart,
      LocalDate windowEnd,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) String acknowledgedBy,
      String alertFingerprint) {}

  /** 通知台账的一页（**真分页 + 真计数**）。 */
  public record ProfitAlertNotificationPage(
      List<ProfitAlertNotification> notifications,
      int page,
      int size,
      long total,
      long pendingCount,
      String note) {}

  /** 登记通知的结果（**幂等**：已登记过的不会重复登记）。 */
  public record ProfitAlertNotificationSummary(
      int matched,
      int created,
      int skipped,
      LocalDate from,
      LocalDate to,
      String note) {}

  /** 改通知状态的入参。 */
  public record ProfitAlertNotificationInput(String status) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通
   * @param alertTypes 本模块覆盖的预警类型
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param note 总口径说明
   */
  public record ProfitAlertCapabilities(
      boolean upstreamConnected,
      List<String> alertTypes,
      List<String> upstreamModules,
      List<String> notDelivered,
      String note) {}
}
