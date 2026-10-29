package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.1.2.2 店铺概览的**出入参模型**（蓝图 01 卷 1.1.2.2「输出」句：店铺对比表 / 卡片；
 * 点击进入该店铺在 13 的分析页）。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 1.1.2.1 核心指标卡、13.1.1.2 多店对比同纪律）：
 * 事实不存在、口径未登记、上游读不到，三种情况一律用 {@code null} / {@code NO_DATA} /
 * {@code NOT_CONNECTED} 表达，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：涉币维度按币种分别报，一个店铺出现两个币种就是两行取值 ——
 * 界面上永远没有跨币种的「总计」。
 *
 * <p>⚠️ **名字不与既有类型重名**（codegen 的同名闸会中止契约生成）：
 * 13.1.1.2 已有 {@code StoreMetricValue} / {@code StoreComparisonRow} / {@code StoreSummary}，
 * 本文件统一用 {@code StoreOverview*} 前缀。
 */
public final class StoreOverviewModels {

  private StoreOverviewModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 一个维度的能力声明（**逐条**，不是一句「都接通了」）。
   *
   * @param key 维度标识
   * @param displayName 显示名
   * @param available 该维度**有没有可引用的口径**。false ⇒ {@code unavailableReason} 写明归谁，
   *     {@code calibers} 为空 —— **不给公式也不给分母**
   * @param ownerModule 口径归属模块（接不通时写「这个口径该归谁」）
   * @param caliber 口径说明（页面上原样显示）
   * @param unit 单位（CURRENCY / COUNT / PERCENT / DAYS）
   * @param currencyAware 是否涉币（涉币维度**不跨币种相加**）
   * @param sourceKind 取数来源：STORE_METRIC / NOT_CONNECTED
   * @param calibers 该维度背后的口径码**逐条**（运行时真相来自 reporting_store_metric_definition）
   * @param unavailableReason 接不通的原因（available=false 时必填）
   * @param note 补充说明
   */
  public record StoreOverviewDimensionCapability(
      String key,
      String displayName,
      boolean available,
      String ownerModule,
      String caliber,
      String unit,
      boolean currencyAware,
      String sourceKind,
      List<StoreOverviewCaliber> calibers,
      String unavailableReason,
      String note) {}

  /**
   * 一个口径码在本租户口径表里的登记情况。
   *
   * @param metricCode 口径码
   * @param displayName 口径显示名
   * @param unit 单位
   * @param aggregation 聚合方式（SUM / LAST / DERIVED_DIFF / DERIVED_RATIO / WINDOW_GROWTH）
   * @param numeratorMetricCode 派生型的分子（非派生型为 null）
   * @param denominatorMetricCode 派生型的分母（非派生型为 null）
   * @param ownerModule 口径归属模块
   * @param description 口径说明
   * @param registered 该口径码**是否已在本租户的口径表里登记**（口径唯一：没登记就没有这个指标）
   */
  public record StoreOverviewCaliber(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      String ownerModule,
      String description,
      boolean registered) {}

  /**
   * 能力声明的完整响应。
   *
   * @param upstreamConnected 上游是否真接通
   * @param dimensions 逐个维度的能力声明
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param rules 判定规则（掉队店铺的阈值与依据）
   * @param notes 口径纪律说明
   */
  public record StoreOverviewCapabilities(
      boolean upstreamConnected,
      List<StoreOverviewDimensionCapability> dimensions,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<StoreOverviewRule> rules,
      List<String> notes) {}

  /** 一条判定规则。 */
  public record StoreOverviewRule(String ruleCode, String description) {}

  // ==================================================================
  // 店铺概览
  // ==================================================================

  /**
   * 一个口径码在某个店铺、某个币种下的取值。
   *
   * @param metricCode 口径码
   * @param displayName 口径显示名
   * @param unit 单位
   * @param ownerModule 该口径码的归属模块
   * @param currency 币种
   * @param value 取值。**算不出来时为 null**（不是 0）
   * @param availability 可用性：AVAILABLE / NO_DATA / NOT_REGISTERED
   * @param availabilityNote 没有值的原因（availability != AVAILABLE 时必填）
   */
  public record StoreOverviewMetricValue(
      String metricCode,
      String displayName,
      String unit,
      String ownerModule,
      String currency,
      @Schema(nullable = true) BigDecimal value,
      String availability,
      String availabilityNote) {}

  /**
   * 一个店铺在一个维度上的取值。
   *
   * @param key 维度标识
   * @param displayName 显示名
   * @param unit 单位
   * @param currencyAware 是否涉币
   * @param available 该维度有没有可引用的口径
   * @param ownerModule 口径归属模块
   * @param caliber 口径说明
   * @param status 维度状态：AVAILABLE（有值）/ NO_DATA（口径已接通但这个窗口没有事实）/
   *     NOT_CONNECTED（口径尚未接通）
   * @param values 按口径码 × 币种的取值
   * @param statusNote 状态说明（为什么是这个状态）
   */
  public record StoreOverviewDimension(
      String key,
      String displayName,
      String unit,
      boolean currencyAware,
      boolean available,
      String ownerModule,
      String caliber,
      String status,
      List<StoreOverviewMetricValue> values,
      String statusNote) {}

  /**
   * 店铺概览里的一行 = 一个店铺。
   *
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码（空串 = 尚未确认归属）
   * @param platformName 平台名
   * @param currencies 该店铺在这个窗口里出现过的币种
   * @param hasFactsInWindow 该店铺在这个窗口里有没有店铺维度事实
   * @param dimensions 五个维度的取值
   * @param classification 分类：GROWTH（增长）/ LAGGARD（掉队）/ STABLE（其余）/ UNCLASSIFIED（不可比）
   * @param classificationNote 分类依据（为什么判成这一类）
   * @param analysisPath 该店铺在 13 的分析页路径（**已交付的路由**）
   * @param analysisTargetName 分析页名称
   * @param ordersPath 该店铺订单页路径（**已交付的路由**）
   * @param note 补充说明
   */
  public record StoreOverviewRow(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      List<String> currencies,
      boolean hasFactsInWindow,
      List<StoreOverviewDimension> dimensions,
      String classification,
      String classificationNote,
      @Schema(nullable = true) String analysisPath,
      String analysisTargetName,
      @Schema(nullable = true) String ordersPath,
      String note) {}

  /**
   * 一个掉队店铺（蓝图 1.1.2.2 职责句：识别掉队店铺）。
   *
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param reason 判定依据（逐条给出，不只给一个结论）
   */
  public record StoreOverviewLaggard(
      String shopCode, String shopName, String platformCode, String reason) {}

  /**
   * 可见店铺集合（蓝图 1.1.2.2「输入」句：店铺列表（02）；权限过滤后的可见店铺集合）。
   *
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param hasFactsInWindow 该店铺在这个窗口里有没有店铺维度事实
   * @param lastFactAt 该店铺在这个窗口里最后一次事实落库时刻（null = 这个窗口没有事实）
   */
  public record StoreOverviewShop(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      boolean hasFactsInWindow,
      @Schema(nullable = true) OffsetDateTime lastFactAt) {}

  /**
   * 一次店铺概览的完整结果。
   *
   * @param windowPreset 时间口径选择：TODAY / YESTERDAY / THIS_MONTH / CUSTOM
   * @param from 窗口起
   * @param to 窗口止
   * @param asOfDate 「今日」在这个租户时区里的日期（窗口按它推导，不是按 UTC）
   * @param timezone 窗口边界所用的时区
   * @param timezoneSource 时区来源：PROJECTION（取自本租户店铺投影事实的分桶时区）/
   *     FALLBACK_UTC（本租户还没有投影数据，暂按 UTC）
   * @param rows 店铺对比行（按平台侧成交金额规模排序）
   * @param laggards 掉队店铺（判定规则与 13.1.1.2 一致）
   * @param caliberNotes 跨平台口径差异说明
   * @param dataUpdatedAt 数据更新时间（卷 13 卷首「数据延迟要显示」）
   * @param dataUpdatedNote 这个时刻具体指什么
   * @param status 本次取数状态：PROJECTED / NOT_PROJECTED / PARTIAL
   * @param statusNote 状态说明
   * @param emptyReason 空态原因：NO_STORES / NOT_PROJECTED / null
   * @param emptyNote 空态说明
   * @param warnings 异常提示
   */
  public record StoreOverviewResponse(
      String windowPreset,
      LocalDate from,
      LocalDate to,
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      List<StoreOverviewRow> rows,
      List<StoreOverviewLaggard> laggards,
      List<String> caliberNotes,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote,
      List<String> warnings) {}
}
