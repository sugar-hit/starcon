package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.6.1.1 自定义报表的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.2.2.2 同一纪律）：
 * 分母为 0、事实不存在、指标未登记，三种情况一律返回 {@code null} / {@code NO_DATA}，
 * **不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：涉币指标按币种分别报。**只有与货币无关的量**
 * （笔数 / 件数）才给出一个跨币种合计，它的 {@code currency} 是**空串** ——
 * 空串 = 与币种无关，不是「币种未知」。
 *
 * <p>⚠️ **名字必须带 {@code CustomReport} 前缀**：13.1.1.1 的 {@code ReportingOverviewModels}
 * 与 13.2.2.2 的 {@code ReportingProductSalesModels} 里已经有 {@code MetricDefinitionView}
 * 等同名记录。SpringDoc 用**简单类名**当 schema 名，同名时只能加序号消歧
 * （{@code MetricDefinitionView1}）—— 那个序号取决于**发现顺序**，换个服务先启动就换一个名，
 * 生成出来的契约跟着漂。所以这里用一个**不重名**的名字。
 */
public final class ReportingCustomReportModels {

  private ReportingCustomReportModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /**
   * 一个可用指标的口径定义（**语义层指标清单**，口径唯一的展示形态）。
   *
   * @param currencyAware 涉币指标**不跨币种相加**，只按币种分别报
   * @param ownerModule 口径归属模块（空串 = 上游已在本服务内闭环）
   */
  public record CustomReportMetricView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 维度白名单
  // ==================================================================

  /** 一个可用维度（**白名单**，页面上照着显示）。 */
  public record CustomReportDimensionView(
      String dimensionCode,
      String label,
      /** 取值形态：TEXT / UUID / DATE。 */
      String valueKind,
      /** 口径说明：这个维度出的是什么、为什么没有名称。 */
      String note) {}

  // ==================================================================
  // 报表配置
  // ==================================================================

  /** 一条筛选条件。 */
  public record CustomReportFilterView(
      String dimensionCode,
      String operator,
      String valueText,
      /** BETWEEN 的上界（其余运算符恒为空串）。 */
      String valueText2) {}

  /** 报表配置（**不含结果**，结果走 {@link CustomReportRunResponse}）。 */
  public record CustomReportView(
      String reportId,
      String reportCode,
      String displayName,
      String description,
      /** 可见范围：PRIVATE 个人 / TEAM 团队。 */
      String visibility,
      String chartType,
      /** 默认时间窗口（空 = 运行时用「最近 30 天」）。 */
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      /** 度量（**已登记的 metric_code**）。 */
      List<CustomReportMetricView> measures,
      /** 维度（白名单维度码）。 */
      List<String> dimensions,
      /** 筛选条件。 */
      List<CustomReportFilterView> filters,
      /** 创建者（已认证会话的 user_id）。 */
      String ownerUserId,
      String createdAt,
      String updatedAt) {}

  /** 报表清单的一行。 */
  public record CustomReportSummary(
      String reportId,
      String reportCode,
      String displayName,
      String description,
      String visibility,
      String chartType,
      int measureCount,
      int dimensionCount,
      String ownerUserId,
      /** 我是不是这份报表的创建者（个人报表只有创建者能改）。 */
      boolean ownedByMe,
      String updatedAt) {}

  /** 报表清单（真分页 + 真计数）。 */
  public record CustomReportPage(
      List<CustomReportSummary> rows,
      long total,
      int page,
      int size,
      /** 筛选条件回显（页面上原样显示）。 */
      String keyword,
      String visibilityFilter) {}

  // ==================================================================
  // 报表结果
  // ==================================================================

  /** 报表结果的一列维度。 */
  public record CustomReportDimensionCell(
      String dimensionCode,
      String label,
      /** 取值（**空串 = 该维度在这行上不适用**，例如公司级行的法人维度）。 */
      String value) {}

  /** 一个币种上的取值。 */
  public record CustomReportCurrencyValue(
      /** 币种。**空串 = 与币种无关的跨币种合计**；不是「币种未知」。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      /** 可用性：AVAILABLE / NOT_COMPARABLE（算不出来，不是 0）。 */
      String availability,
      /** 算不出来的原因（availability != AVAILABLE 时必填）。 */
      String availabilityNote) {}

  /** 报表结果里一个度量的取值。 */
  public record CustomReportMeasureCell(
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      /** 取值（涉币指标按币种分列；与货币无关的量给一个空串币种的合计）。 */
      List<CustomReportCurrencyValue> values,
      /** 可用性：AVAILABLE / NO_DATA / NOT_COMPARABLE。 */
      String availability,
      /** 不可比或没有数据的原因（availability != AVAILABLE 时必填）。 */
      String availabilityNote) {}

  /** 报表结果的一行 = 一个维度组合。 */
  public record CustomReportRow(
      List<CustomReportDimensionCell> dimensions,
      List<CustomReportMeasureCell> measures) {}

  /** 报表结果（**真分页**）。 */
  public record CustomReportRunResponse(
      String reportId,
      String reportCode,
      String displayName,
      String description,
      String chartType,
      /** 本次实际使用的窗口。 */
      LocalDate from,
      LocalDate to,
      /** 维度列（白名单维度码）。 */
      List<CustomReportDimensionCell> dimensions,
      /** 度量列（**已登记的 metric_code** + 口径说明）。 */
      List<CustomReportMetricView> measures,
      List<CustomReportRow> rows,
      /** 符合筛选条件的维度组合总数（不受分页影响）。 */
      long total,
      int page,
      int size,
      /** 口径说明（页面上原样显示）。 */
      List<String> caliberNotes,
      /** 数据更新时间（最近一次投影落库时刻）—— 卷 13 卷首「数据延迟要显示」。
   *  **空表时是 null**（不是一个长得像时间的假值）。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      /** 数据更新时间的口径说明。 */
      String dataUpdatedNote,
      /** 本次报表整体状态：AVAILABLE / NO_DATA。 */
      String status,
      /** 状态说明。 */
      String statusNote,
      /** 空态原因：NO_FACTS / null。 */
      @Schema(nullable = true) String emptyReason,
      /** 空态说明。 */
      String emptyNote) {}

  // ==================================================================
  // 分享
  // ==================================================================

  /** 一条分享链接。 */
  public record CustomReportShareView(
      String shareId,
      String reportId,
      /** 分享令牌（32 位十六进制）。 */
      String shareToken,
      /** 分享链接路径（前端拼成完整 URL）。 */
      String sharePath,
      /** 分享范围：PRIVATE 仅创建者 / TEAM 团队内成员。 */
      String visibility,
      String createdBy,
      String createdAt,
      /** 撤销时刻（null = 有效）。 */
      @Schema(nullable = true) String revokedAt) {}

  /**
   * 通过分享链接读到的报表。
   *
   * <p>🔴 **未授权访问与「报表不存在」返回逐字相同的 404** —— 这个响应体
   * 只在**确实有权看**的时候才会出现。
   */
  public record CustomReportSharedResponse(
      CustomReportView report,
      CustomReportRunResponse result,
      /** 分享链接的可见范围。 */
      String shareVisibility,
      String sharedAt) {}

  // ==================================================================
  // 订阅与投递
  // ==================================================================

  /** 一条订阅计划。 */
  public record CustomReportSubscriptionView(
      String subscriptionId,
      String reportId,
      String reportCode,
      String reportName,
      /** 5 段 cron 表达式（分 时 日 月 周）。 */
      String cronExpression,
      /** 投递通道：EMAIL / IM（**实际投递属 13.6.2.1，本模块只登记计划**）。 */
      String channel,
      /** 收件目标（邮箱 / IM 群 id）。 */
      String target,
      boolean enabled,
      /** 下一次该执行的时刻（UTC）；cron 解析不出来时是 null。 */
      @Schema(nullable = true) OffsetDateTime nextRunAt,
      /** 最近一次被落成待投递记录的时刻。 */
      @Schema(nullable = true) OffsetDateTime lastEnqueuedAt,
      String createdAt) {}

  /** 一条待投递记录（**队列**，不是「已发送」的凭据）。 */
  public record CustomReportDeliveryView(
      String deliveryId,
      String reportId,
      String reportCode,
      String subscriptionId,
      /** 计划投递的时刻（UTC）。 */
      OffsetDateTime scheduledFor,
      /** PENDING 待投递 / DELIVERED 已投递 / FAILED 投递失败 / CANCELLED 已取消。 */
      String status,
      int attemptCount,
      String lastError,
      String createdAt) {}

  /** 待投递记录台账（真分页 + 真计数）。 */
  public record CustomReportDeliveryPage(
      List<CustomReportDeliveryView> rows,
      long total,
      int page,
      int size,
      String statusFilter) {}

  /**
   * 跑一轮「到点落成待投递记录」的结果。
   *
   * @param enqueued 本轮落成的待投递记录条数
   * @param advanced 推进了下一次执行时刻的订阅条数
   */
  public record CustomReportTickResult(int enqueued, int advanced, String note) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（语义层指标口径表 + 投影事实都在本服务内，已交付）
   * @param reportAvailable 报表配置 / 保存 / 共享 / 结果是否可用
   * @param subscriptionAvailable 订阅**配置**与待投递记录是否可用（**实际投递未交付**）
   * @param exportAvailable 大文件导出是否可用（**未交付** ⇒ false）
   * @param exportOwnerModule 导出归属模块
   * @param exportUnavailableReason 导出为什么拿不到（available=false 时必填）
   * @param deliveryAvailable 邮件 / IM 的**实际投递**是否可用（**未交付** ⇒ false）
   * @param deliveryOwnerModule 投递归属模块
   * @param deliveryUnavailableReason 投递为什么拿不到（available=false 时必填）
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 口径与判定规则（阈值写清楚，页面上照着显示）
   */
  public record CustomReportCapabilities(
      boolean upstreamConnected,
      boolean reportAvailable,
      boolean subscriptionAvailable,
      boolean exportAvailable,
      String exportOwnerModule,
      String exportUnavailableReason,
      boolean deliveryAvailable,
      String deliveryOwnerModule,
      String deliveryUnavailableReason,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<ReportingCustomReportCatalog.CustomReportRuleNote> rules) {}

  // ==================================================================
  // 写入请求
  // ==================================================================

  /**
   * 保存 / 更新一份报表配置。
   *
   * <p>⚠️ **度量只能是已登记的 metric_code，维度只能是白名单维度码** ——
   * 服务层会逐项校验，DB 层还有一道触发器（度量指标码必须已登记）。
   */
  public record CustomReportSaveRequest(
      String reportCode,
      String displayName,
      String description,
      /** 可见范围：PRIVATE 个人 / TEAM 团队。 */
      String visibility,
      /** 图表类型：TABLE / BAR / LINE / PIE。 */
      String chartType,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      /** 度量指标码（**必须已登记**）。 */
      List<String> measureMetricCodes,
      /** 维度码（**必须命中白名单**）。 */
      List<String> dimensionCodes,
      /** 筛选条件。 */
      List<CustomReportFilterRequest> filters) {}

  /** 一条筛选条件（写入用）。 */
  public record CustomReportFilterRequest(
      String dimensionCode,
      String operator,
      String valueText,
      @Schema(nullable = true) String valueText2) {}

  /** 跑一份报表的入参（窗口 + 分页）。 */
  public record CustomReportRunRequest(
      @Schema(nullable = true) LocalDate from,
      @Schema(nullable = true) LocalDate to,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /** 生成分享链接的入参。 */
  public record CustomReportShareRequest(
      /** 分享范围：PRIVATE 仅创建者 / TEAM 团队内成员（空 = TEAM）。 */
      @Schema(nullable = true) String visibility) {}

  /** 保存一份订阅计划。 */
  public record CustomReportSubscriptionRequest(
      /** 5 段 cron 表达式（分 时 日 月 周）。 */
      String cronExpression,
      /** 投递通道：EMAIL / IM。 */
      String channel,
      /** 收件目标（邮箱 / IM 群 id）。 */
      String target,
      Boolean enabled) {}

  /** 切换一条订阅的启停。 */
  public record CustomReportSubscriptionToggleRequest(Boolean enabled) {}
}
