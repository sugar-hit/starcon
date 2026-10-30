package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.6.1.2 看板搭建的**出入参模型**。
 *
 * <p>蓝图原文：「搭建自定义经营看板：拖拽组件、选择指标与筛选、布局保存、按角色共享、
 * 设置默认值。」输出「看板视图 + 共享配置。」
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.6.1.1 同一纪律）：
 * 组件的分母为 0、事实不存在、指标未登记，一律返回 {@code null} / {@code NO_DATA} /
 * {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：组件的涉币指标按币种分别报（与 13.6.1.1 同一纪律）。
 *
 * <p>⚠️ **名字必须带 {@code Board} 前缀**：13.6.1.1 的 {@code ReportingCustomReportModels}
 * 里已经有 {@code CustomReportView} / {@code CustomReportFilterView} 等记录，
 * 13.1.1.1 的 {@code ReportingOverviewModels} 里还有 {@code MetricDefinitionView}。
 * SpringDoc 用**简单类名**当 schema 名，同名时只能加序号消歧（{@code MetricDefinitionView1}）
 * —— 那个序号取决于**发现顺序**，换个服务先启动就换一个名，生成出来的契约跟着漂。
 * 所以这里用一个**不重名**的名字。
 */
public final class ReportingBoardModels {

  private ReportingBoardModels() {}

  // ==================================================================
  // 能力声明与目录
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（语义层指标口径表与 13.6.1.1 的报表引擎都在本服务内，已交付）
   * @param boardAvailable 看板的搭建 / 布局 / 共享 / 视图是否可用
   * @param templateAvailable 看板模板是否可用
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则与口径纪律（页面上照着显示）
   */
  public record BoardCapabilities(
      boolean upstreamConnected,
      boolean boardAvailable,
      boolean templateAvailable,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<ReportingBoardCatalog.BoardRuleNote> rules) {}

  /**
   * 一个可用指标的口径定义（**语义层指标清单**，口径唯一的展示形态）。
   *
   * @param currencyAware 涉币指标**不跨币种相加**，只按币种分别报
   * @param ownerModule 口径归属模块（空串 = 上游已在本服务内闭环）
   */
  public record BoardMetricView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /** 一个可用维度（**白名单**，页面上照着显示）。 */
  public record BoardDimensionView(
      String dimensionCode,
      String label,
      /** 取值形态：TEXT / UUID / DATE。 */
      String valueKind,
      /** 口径说明：这个维度出的是什么、为什么没有名称。 */
      String note) {}

  // ==================================================================
  // 看板配置
  // ==================================================================

  /** 一条筛选条件（维度码 + 运算符 + 值）。 */
  public record BoardFilterView(
      String dimensionCode,
      String operator,
      String valueText,
      /** BETWEEN 的上界（其余运算符恒为空串）。 */
      String valueText2) {}

  /** 栅格布局（拖拽的结果）：位置与尺寸。 */
  public record BoardLayoutView(int x, int y, int w, int h) {}

  /** 一个看板组件（**已登记的 metric_code** + 白名单维度码）。 */
  public record BoardComponentView(
      String componentId,
      String boardId,
      String metricCode,
      /** 指标中文名（来自口径表，页面照着显示）。 */
      String metricDisplayName,
      /** 指标单位（AMOUNT / QUANTITY / COUNT / PERCENT）。 */
      String unit,
      /** 涉币指标不跨币种相加。 */
      boolean currencyAware,
      String dimensionCode,
      String chartType,
      BoardLayoutView layout,
      List<BoardFilterView> filter,
      /** 看得懂这个组件所需的权限码（空串 = 谁都能看）。 */
      String requiredPermission,
      int sortOrder,
      String createdAt,
      String updatedAt) {}

  /** 看板配置（**不含组件数据**，组件数据走 {@link BoardComponentData}）。 */
  public record BoardView(
      String boardId,
      String boardCode,
      String displayName,
      String description,
      /** 可见范围：PRIVATE 个人 / TEAM 团队。 */
      String visibility,
      /** 创建者（已认证会话的 user_id）。 */
      String ownerUserId,
      /** 我是不是这张看板的创建者（个人看板只有创建者能改）。 */
      boolean ownedByMe,
      /** 默认筛选（看板级；组件自己的筛选优先）。 */
      List<BoardFilterView> defaultFilters,
      /** 默认时间窗口（空 = 运行时用「最近 30 天」）。 */
      @Schema(nullable = true) LocalDate defaultWindowFrom,
      @Schema(nullable = true) LocalDate defaultWindowTo,
      /** 组件（**已按当前用户的权限过滤**）。 */
      List<BoardComponentView> components,
      /** 共享条目数。 */
      int shareCount,
      String createdAt,
      String updatedAt) {}

  /** 看板清单的一行。 */
  public record BoardSummary(
      String boardId,
      String boardCode,
      String displayName,
      String description,
      String visibility,
      String ownerUserId,
      boolean ownedByMe,
      /** 组件总数（租户内这份看板一共摆了多少张卡片）。 */
      int componentCount,
      /** 🔴 **我看得见的**组件数（按角色权限过滤后的真实值）。 */
      int visibleComponentCount,
      int shareCount,
      String updatedAt) {}

  /** 看板清单（真分页 + 真计数）。 */
  public record BoardPage(
      List<BoardSummary> rows,
      long total,
      int page,
      int size,
      /** 筛选条件回显（页面上原样显示）。 */
      String keyword,
      String visibilityFilter) {}

  // ==================================================================
  // 共享
  // ==================================================================

  /** 一条按角色 / 权限码的共享。 */
  public record BoardShareView(
      String shareId,
      String boardId,
      /** 角色 id（与 permissionCode 二选一）。 */
      @Schema(nullable = true) String roleId,
      /** 权限码（与 roleId 二选一）。 */
      String permissionCode,
      /** 可见范围：ROLE 仅命中角色 / 权限码的人 / TENANT 租户内成员。 */
      String scope,
      String createdBy,
      String createdAt,
      /** 撤销时刻（null = 有效）。 */
      @Schema(nullable = true) String revokedAt) {}

  // ==================================================================
  // 默认值
  // ==================================================================

  /** 看板的默认值（默认筛选 + 默认时间窗口）。 */
  public record BoardDefaultsView(
      String boardId,
      List<BoardFilterView> defaultFilters,
      @Schema(nullable = true) LocalDate defaultWindowFrom,
      @Schema(nullable = true) LocalDate defaultWindowTo,
      /** 默认值属于创建者，改它需要创建者权限。 */
      boolean ownedByMe) {}

  // ==================================================================
  // 模板
  // ==================================================================

  /** 一份看板模板（含模板里的组件）。 */
  public record BoardTemplateView(
      String templateId,
      String templateCode,
      String displayName,
      String description,
      List<BoardComponentView> components,
      String createdBy,
      String createdAt,
      String updatedAt) {}

  // ==================================================================
  // 看板视图（输出）
  // ==================================================================

  /** 一个币种上的取值。 */
  public record BoardComponentValue(
      /** 币种。**空串 = 与币种无关的跨币种合计**；不是「币种未知」。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      /** 可用性：AVAILABLE / NOT_COMPARABLE（算不出来，不是 0）。 */
      String availability,
      /** 算不出来的原因（availability != AVAILABLE 时必填）。 */
      String availabilityNote) {}

  /** 组件数据里的一个点（一个维度取值）。 */
  public record BoardComponentPoint(String dimensionValue, List<BoardComponentValue> values) {}

  /** 一个组件的数据（**按登记口径实时算出来的**，不落库）。 */
  public record BoardComponentData(
      String componentId,
      String metricCode,
      String metricDisplayName,
      String unit,
      boolean currencyAware,
      String dimensionCode,
      String dimensionLabel,
      String chartType,
      List<BoardComponentPoint> points,
      /** 本组件整体状态：AVAILABLE / NO_DATA。 */
      String status,
      String statusNote,
      /** 空态原因：NO_FACTS / null。 */
      @Schema(nullable = true) String emptyReason,
      /** 空态说明。 */
      String emptyNote,
      /** 数据更新时间（最近一次投影落库时刻）；空表时是 null。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      /** 数据更新时间的口径说明。 */
      String dataUpdatedNote,
      /** 口径说明（页面上原样显示）。 */
      List<String> caliberNotes) {}

  /** 看板视图（**输出**：看板视图 + 共享配置的载体）。 */
  public record BoardViewResponse(
      String boardId,
      String boardCode,
      String displayName,
      String description,
      String visibility,
      /** 本次实际使用的窗口。 */
      LocalDate from,
      LocalDate to,
      /** 🔴 **已按权限过滤**的组件 + 它们的实时数据。 */
      List<BoardComponentData> components,
      /** 本次看到的组件数 / 看板上的组件总数（分母是租户内的真实总数）。 */
      int visibleComponentCount,
      int totalComponentCount,
      /** 数据更新时间（最近一次投影落库时刻）—— 卷 13 卷首「数据延迟要显示」。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      /** 数据更新时间的口径说明。 */
      String dataUpdatedNote,
      /** 口径说明（页面上原样显示）。 */
      List<String> caliberNotes,
      /** 整体状态：AVAILABLE / NO_DATA。 */
      String status,
      String statusNote) {}

  /** 我的看板（工作台视图）：**我能打开**的看板 + 各自看得见的组件数。 */
  public record BoardWorkbenchItem(
      String boardId,
      String boardCode,
      String displayName,
      String description,
      String visibility,
      boolean ownedByMe,
      /** 我看得见的组件数。 */
      int visibleComponentCount,
      int totalComponentCount,
      String updatedAt) {}

  /** 我的看板（工作台视图）。 */
  public record BoardWorkbenchResponse(
      List<BoardWorkbenchItem> boards,
      /** 数据更新时间（最近一次投影落库时刻）—— 卷 13 卷首「数据延迟要显示」。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote) {}

  // ==================================================================
  // 写入请求
  // ==================================================================

  /** 保存一份看板配置（创建者来自**已认证会话**，不在入参里）。 */
  public record BoardSaveRequest(
      String boardCode,
      String displayName,
      String description,
      /** 可见范围：PRIVATE 个人 / TEAM 团队。 */
      String visibility,
      /** 默认筛选（默认 = 覆盖组件的同维度筛选）。 */
      List<BoardFilterRequest> defaultFilters,
      @Schema(nullable = true) LocalDate defaultWindowFrom,
      @Schema(nullable = true) LocalDate defaultWindowTo) {}

  /** 一条筛选条件（写入用）。 */
  public record BoardFilterRequest(
      String dimensionCode,
      String operator,
      String valueText,
      @Schema(nullable = true) String valueText2) {}

  /** 保存一个组件。 */
  public record BoardComponentSaveRequest(
      /** 指标码（**必须已登记**）。 */
      String metricCode,
      /** 维度码（**必须命中白名单**）。 */
      String dimensionCode,
      /** 图表类型：TABLE / BAR / LINE / PIE。 */
      String chartType,
      /** 栅格布局（位置与尺寸；非法形状**直接报错**）。 */
      BoardLayoutView layout,
      /** 组件自己的筛选。 */
      List<BoardFilterRequest> filter,
      /** 看得懂这个组件所需的权限码（空串 = 谁都能看）。 */
      String requiredPermission,
      /** 展示顺序（空 = 追加到末尾）。 */
      @Schema(nullable = true) Integer sortOrder) {}

  /** 批量保存布局时的一条（拖拽结果）。 */
  public record BoardLayoutItem(
      String componentId, int x, int y, int w, int h, @Schema(nullable = true) Integer sortOrder) {}

  /** 批量保存布局（**拖拽一次 ⇒ 一次提交**）。 */
  public record BoardLayoutSaveRequest(List<BoardLayoutItem> components) {}

  /** 新增一条共享（按角色 / 权限码）。 */
  public record BoardShareRequest(
      /** 角色 id（与 permissionCode 二选一）。 */
      @Schema(nullable = true) String roleId,
      /** 权限码（与 roleId 二选一）。 */
      @Schema(nullable = true) String permissionCode,
      /** 可见范围：ROLE / TENANT（空 = ROLE）。 */
      @Schema(nullable = true) String scope) {}

  /** 保存默认值。 */
  public record BoardDefaultsRequest(
      List<BoardFilterRequest> defaultFilters,
      @Schema(nullable = true) LocalDate defaultWindowFrom,
      @Schema(nullable = true) LocalDate defaultWindowTo) {}

  /** 保存一份模板。 */
  public record BoardTemplateSaveRequest(
      String templateCode,
      String displayName,
      String description,
      /** 模板里的组件（形状与真组件同构）。 */
      List<BoardComponentSaveRequest> components) {}

  /** 用模板实例化一张看板。 */
  public record BoardTemplateInstantiateRequest(
      String boardCode,
      String displayName,
      String description,
      /** 可见范围：PRIVATE / TEAM（空 = PRIVATE）。 */
      @Schema(nullable = true) String visibility) {}
}
