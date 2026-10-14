package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.2.2.2 安全库存的传输模型。
 *
 * <p>蓝图原话：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成补货建议。 输入：阈值配置；当前可用量投影；日均销量。输出：安全库存预警 + 可选自动触发
 * 6.2.1.2 补货建议。」
 *
 * <p><b>那句加粗红线「安全库存要区分『平台断货风险』和『自有仓耗尽』：FBA 断货造成的损失远大于自有仓」 在本模块落成三件可计算的事</b>（V114 文件头有完整论证）：
 *
 * <ol>
 *   <li>预警类型 {@link SafetyStockAlertType} 由仓的 {@code warehouse_kind} 派生，**不是**一个可选标签；
 *   <li>一档阈值挂的仓必须同 kind（V114 的延迟约束触发器），FBA 与自有仓永远是两个独立的池子；
 *   <li>{@link SafetyStockAlertRow#effectiveSeverity()}：平台断货一律按<em>上一级</em>严重度处理。
 * </ol>
 *
 * <p>第 3 条是关键 —— 损失更大的一方必须在**排序与展示**上排到前面，否则红线只剩注释。 配置上仍是 {@code severity}（运营自己填的级别），{@code
 * effectiveSeverity} 是本模块按风险类型 提级后的结果，两者都返回：只返回提级后的会让人以为自己配了 CRITICAL，只返回配置值又会让 INFO 的 FBA 预警排在自有仓
 * WARNING 后面。
 *
 * <p><b>算不出来就留 null，不拿 0 兜底</b>：没登记日均销量时 {@code daysOfCover} 与 {@code suggestedQuantity} 恒为
 * null，并带上 {@code reviewNote} / {@code unquantifiedReason} 说明原因。0 会被读成「永远不会断货」与「不用补货」，那是最贵的两种错误。
 *
 * <p>数量与比例一律 {@link BigDecimal}（DB 侧 numeric），比较一律 {@code compareTo} 而非 {@code equals}（后者把 0 与
 * 0.0000 判成不等）。
 */
public final class SafetyStockModels {

  private SafetyStockModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 阈值生效范围。解析优先级 SKU &gt; CATEGORY &gt; DEFAULT（{@link #priority()} 升序即更优先）， 与 7.2.2.1 的 SHOP &gt;
   * CHANNEL &gt; DEFAULT、7.8.1.1 的 SKU &gt; SHOP &gt; DEFAULT 同一套约定。
   */
  public enum SafetyStockScopeType {
    DEFAULT("全租户兜底"),
    CATEGORY("按分类"),
    SKU("按 SKU");

    private final String label;
    private final int priority;

    SafetyStockScopeType(String label) {
      this.label = label;
      this.priority = ordinal();
    }

    public String label() {
      return label;
    }

    /** 解析优先级：数值越小越优先（SKU 最具体，DEFAULT 兜底）。 */
    public int priority() {
      return priority;
    }
  }

  /**
   * 预警级别 —— 蓝图原文「配置安全库存阈值与<em>预警级别</em>」的那一档。
   *
   * <p>三档受控词表，不接受自由文本：级别要参与排序与提级运算，自由文本在这一步就失效了。
   */
  public enum AlertSeverity {
    CRITICAL("严重", 0),
    WARNING("警告", 1),
    INFO("提示", 2);

    private final String label;
    private final int rank;

    AlertSeverity(String label, int rank) {
      this.label = label;
      this.rank = rank;
    }

    public String label() {
      return label;
    }

    /** 排序权重：越危险的排越前（名册打开第一眼就是最该处理的那个）。 */
    public int rank() {
      return rank;
    }

    /**
     * 提一级。{@link #CRITICAL} 已是最高档，提到顶为止。
     *
     * <p>用于「平台断货按上一级严重度处理」：损失更大的一方必须在展示与排序上压过同级。
     */
    public AlertSeverity escalate() {
      return switch (this) {
        case CRITICAL -> CRITICAL;
        case WARNING -> CRITICAL;
        case INFO -> WARNING;
      };
    }
  }

  /**
   * 预警类型 —— 蓝图那句红线的落点。
   *
   * <p>由仓的 {@code warehouse_kind} 派生，**调用方不能自选**：选了就等于把「平台断货」和
   * 「自有仓耗尽」重新混成同一种事，而这两件事的处置路径完全不同（前者要紧急空运/调仓， 后者走常规补货）。
   */
  public enum SafetyStockAlertType {
    /** 平台仓（FBA）断货风险。蓝图原话：FBA 断货造成的损失远大于自有仓。 */
    PLATFORM_STOCKOUT_RISK("平台断货风险"),
    /** 自有仓耗尽。 */
    OWN_WAREHOUSE_DEPLETION("自有仓耗尽");

    private final String label;

    SafetyStockAlertType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否平台侧风险。true 时严重度按 {@link AlertSeverity#escalate()} 提级。 */
    public boolean platformRisk() {
      return this == PLATFORM_STOCKOUT_RISK;
    }
  }

  /** 预警状态机：OPEN → ACKNOWLEDGED → RESOLVED，单向（V114 §7.3 触发器钉死）。 */
  public enum SafetyStockAlertStatus {
    OPEN("待处理"),
    ACKNOWLEDGED("已认领"),
    RESOLVED("已处置");

    private final String label;

    SafetyStockAlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 补货建议状态：PROPOSED → ACKNOWLEDGED / DISMISSED，单向。 */
  public enum SafetyStockSuggestionStatus {
    PROPOSED("待处理"),
    ACKNOWLEDGED("已采纳"),
    DISMISSED("已忽略");

    private final String label;

    SafetyStockSuggestionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 阈值查询

  /** 阈值配置列表查询。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  public record SafetyStockQuery(
      String scopeType,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) UUID skuId,
      String alertSeverity,
      @Schema(nullable = true) UUID warehouseId,
      boolean effectiveOnly,
      String keyword,
      int page,
      int size) {}

  // ------------------------------------------------------------ 阈值配置

  /**
   * 一档安全库存阈值。
   *
   * <p>内容列写定后不可修改（V114 触发器钉死），改阈值 = 收口旧档 + 开新档。 {@code effectiveTo} 为 null 表示至今仍在生效。
   *
   * @param thresholdQuantity 安全库存阈值（件）；0 是合法取值，与「没配阈值」不是一回事
   * @param alertSeverity 预警级别（阈值配置的一部分）
   * @param reorderCoverageDays 补货建议的目标覆盖天数；null = 不推算建议量
   * @param autoSuggestReplenishment 是否在预警生成时自动关联补货建议
   */
  public record SafetyStockPolicyRow(
      UUID id,
      SafetyStockScopeType scopeType,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) UUID skuId,
      String skuLabel,
      BigDecimal thresholdQuantity,
      AlertSeverity alertSeverity,
      @Schema(nullable = true) BigDecimal reorderCoverageDays,
      boolean autoSuggestReplenishment,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt) {

    /** 该档是否在给定时刻生效（左闭右开，与 17.5.1.1 / 7.2.2.1 同一套区间约定）。 */
    public boolean covers(OffsetDateTime at) {
      return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
  }

  /** 阈值挂的仓：它算自有仓耗尽还是平台断货风险。 */
  public record SafetyStockWarehouseLink(
      UUID policyId,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String warehouseKind,
      String warehouseKindLabel,
      boolean platformRisk) {}

  /** 阈值详情 = 分档本身 + 它吃哪几个仓。 */
  public record SafetyStockPolicyDetail(
      SafetyStockPolicyRow policy, List<SafetyStockWarehouseLink> warehouses) {}

  /** 阈值配置列表（真分页，total 不受分页影响）。 */
  public record SafetyStockPolicyPage(
      List<SafetyStockPolicyRow> policies, long total, int page, int size) {}

  // ------------------------------------------------------------ 阈值写入

  /**
   * 新建一档阈值。
   *
   * <p>{@code scopeType} 非 DEFAULT 时必须给对应的 {@code categoryId} / {@code skuId}： SKU 与分类都在 catalog
   * 服务，本服务不建外键也不查它的表（AGENTS.md 模块边界纪律）， 只存逻辑 ID + 写入时的可读名快照。
   *
   * @param thresholdQuantity 阈值（件）；越界由 DB CHECK 拒绝
   * @param reorderCoverageDays 覆盖天数；{@code autoSuggestReplenishment=true} 时必填
   * @param warehouses 挂仓；**必须同 warehouseKind**（V114 延迟约束触发器拒绝混挂）
   */
  public record SafetyStockPolicyInput(
      @NotBlank @Size(max = 16) String scopeType,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) UUID skuId,
      @Size(max = 150) String skuLabel,
      @NotNull @DecimalMin(value = "0") BigDecimal thresholdQuantity,
      @NotBlank @Size(max = 10) String alertSeverity,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal reorderCoverageDays,
      boolean autoSuggestReplenishment,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String note,
      List<@NotNull @Valid SafetyStockWarehouseBinding> warehouses) {

    /**
     * 挂仓关系的一行。
     *
     * <p>{@code warehouseKind} 必须逐个声明 —— 它决定这条阈值最终产出的是 「平台断货风险」还是「自有仓耗尽」，不声明就没有预警类型。
     */
    public record SafetyStockWarehouseBinding(
        @NotNull UUID warehouseId, @NotBlank @Size(max = 16) String warehouseKind) {}
  }

  /** 写入结果：新档 / 被换掉的那一档、以及为什么这么改。 */
  public record SafetyStockPolicySaved(
      UUID policyId,
      BigDecimal thresholdQuantity,
      String alertSeverity,
      long warehouseCount,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID supersededPolicyId,
      String message) {}

  /**
   * 某一时刻真正生效的阈值。
   *
   * <p>{@code policy} 为 null 表示连兜底档都没配 —— 这是一个**必须如实报出来**的状态， 而不是「按阈值 0 处理」：没有兜底档时任何 SKU
   * 都不会被安全库存保护， 运营看到「0 条预警」会误以为库存健康。
   */
  public record SafetyStockPolicyEffective(
      @Schema(nullable = true) SafetyStockPolicyRow policy,
      SafetyStockScopeType resolvedScopeType,
      boolean usedFallback,
      OffsetDateTime at,
      String note) {}

  // ------------------------------------------------------------ 日均销量登记

  /**
   * 登记一条 SKU 日均销量。
   *
   * <p>它是本服务**唯一的销量真相来源**：库存流水没有渠道维度，出库量不等于销量， 而 13 销量域与 06 采购域均未交付。销量因此是登记进来的事实， {@code
   * /capabilities} 会把这件事如实讲给调用方而不是假装它是从订单算出来的 （与 7.8.1.1 的 allocation_shop_sales 同一纪律，但本表粒度是 SKU ×
   * 期间，不按店铺拆）。
   *
   * @param periodStart/periodEnd 业务日期闭区间
   */
  public record SafetyStockSalesInput(
      @NotBlank @Size(max = 32) String sourceSystem,
      @NotNull UUID skuId,
      @Size(max = 150) String skuLabel,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotNull @DecimalMin(value = "0") BigDecimal unitsSold) {}

  /** 销量登记结果。 */
  public record SafetyStockSalesSaved(
      UUID salesId,
      UUID skuId,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal unitsSold,
      BigDecimal dailyAverage,
      long coveredDays,
      String message) {}

  /**
   * 一段登记销量的汇总 —— 日均销量的来源可核对。
   *
   * <p>{@code dailyAverage} = ΣunitsSold / 覆盖天数。{@code records} 是汇总自几条登记行：
   * 「这段时间到底登记了几次」是排查日均销量偏差时的第一个问题。
   */
  public record SafetyStockSalesRow(
      UUID skuId,
      String skuLabel,
      BigDecimal unitsSold,
      BigDecimal dailyAverage,
      LocalDate firstPeriodStart,
      LocalDate lastPeriodEnd,
      long coveredDays,
      long records) {}

  /** 销量登记列表（真分页）。 */
  public record SafetyStockSalesPage(
      List<SafetyStockSalesRow> sales, long total, int page, int size) {}

  // ------------------------------------------------------------ 评估

  /**
   * 发起一次安全库存评估。
   *
   * <p>用 POST 而不是 GET：它有副作用（落评估批次 + 生成预警 + 建建议），不是查询。
   *
   * <p><b>为什么 SKU 清单要由调用方随请求带上分类上下文</b>：阈值可以按分类配（蓝图原话 「按 SKU/分类/仓库配置」），但**分类 → SKU 的展开在 catalog
   * 服务**，本服务跨库读不到 （AGENTS.md 禁止跨服务 SQL）。所以评估请求带 {@link SafetyStockEvaluationSkuContext}：调用方按 SKU
   * 清单把 每个 SKU 的分类一并给出，分类档才能落到具体的 SKU 上。 {@code /capabilities} 的 {@code
   * skuCategoryExpansionAvailable=false} 会把这件事讲清楚。
   *
   * <p>{@code skus} 留空时**只扫 SKU 档**：分类档与兜底档需要 SKU 清单才能展开成具体对象， 此时它们被计入 {@code policiesNotScannable}
   * 并在评估结论里如实报出，而不是静默跳过 让运营以为「扫过了没风险」。
   *
   * @param warehouseId 只扫这个仓；不传则扫全部在效阈值挂的仓
   */
  public record SafetyStockEvaluationRequest(
      List<@NotNull @Valid SafetyStockEvaluationSkuContext> skus,
      @Schema(nullable = true) UUID warehouseId,
      @Size(max = 500) String note) {

    /**
     * 一个待扫描 SKU 及其分类上下文。
     *
     * @param categoryId 该 SKU 所属分类；null 表示未归类，只有 SKU 档与兜底档能覆盖它
     */
    public record SafetyStockEvaluationSkuContext(
        @NotNull UUID skuId, @Schema(nullable = true) UUID categoryId) {}
  }

  /**
   * 一次评估的结论 —— 「扫了多少、缺了什么、生成了什么」逐项可核对。
   *
   * <p>{@code policiesNotScannable} 是本模块**不假装扫过**的凭据：需要 SKU 清单才能展开
   * （分类档/兜底档）而调用方没给时，它们被计在这里。没有这一列，一次漏扫就会显示成 「扫了 3 档、0 条预警」，而真实情况是另外 2 档压根没被评估。
   */
  public record SafetyStockEvaluationRow(
      UUID id,
      OffsetDateTime evaluatedAt,
      UUID triggeredByMembershipId,
      long policiesEvaluated,
      long policiesNotScannable,
      long warehousesEvaluated,
      long skusEvaluated,
      long skusWithoutSales,
      long alertsOpened,
      long alertsSuppressed,
      long suggestionsCreated,
      @Schema(nullable = true) UUID scopeWarehouseId,
      String note) {}

  /** 评估批次列表（真分页）。 */
  public record SafetyStockEvaluationPage(
      List<SafetyStockEvaluationRow> evaluations, long total, int page, int size) {}

  // ------------------------------------------------------------ 预警

  /** 预警列表查询。 */
  public record SafetyStockAlertQuery(
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID skuId,
      String alertType,
      String severity,
      String status,
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) UUID evaluationId,
      boolean platformRiskOnly,
      String keyword,
      int page,
      int size) {}

  /**
   * 一条安全库存预警。
   *
   * <p>{@code effectiveSeverity} 是本模块对蓝图那句红线的落地：平台断货风险一律提一级。 {@code severity} 是配置里的原值，两者都返回 ——
   * 只给提级后的会让人以为自己配了 CRITICAL， 只给配置值又会让 INFO 的 FBA 预警排在自有仓 WARNING 后面。
   *
   * @param availableQuantity 检测那一刻的可用量投影（inventory_balance 的 SELLABLE 口径）
   * @param shortfallQuantity threshold − available，恒 &ge; 0
   * @param dailyAverageSales 日均销量；null = 该 SKU 尚未登记销量
   * @param daysOfCover 可售天数 = available ÷ dailyAverage；销量未登记或为 0 时 null
   * @param suggestedQuantity 关联的补货建议量；null = 不可推算，原因见 {@code reviewNote}
   * @param reviewNote 需要人看一眼的提示（销量未登记 / 不可推算建议量）
   */
  public record SafetyStockAlertRow(
      UUID id,
      UUID evaluationId,
      UUID policyId,
      SafetyStockScopeType policyScopeType,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID skuId,
      String skuCode,
      String skuLabel,
      SafetyStockAlertType alertType,
      AlertSeverity severity,
      AlertSeverity effectiveSeverity,
      BigDecimal availableQuantity,
      BigDecimal thresholdQuantity,
      BigDecimal shortfallQuantity,
      @Schema(nullable = true) BigDecimal dailyAverageSales,
      @Schema(nullable = true) BigDecimal daysOfCover,
      @Schema(nullable = true) BigDecimal suggestedQuantity,
      SafetyStockAlertStatus status,
      OffsetDateTime detectedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      String reviewNote) {}

  /** 预警详情 = 预警本身 + 它关联的补货建议（可能为 null：没开自动建议或不可推算）。 */
  public record SafetyStockAlertDetail(
      SafetyStockAlertRow alert, @Schema(nullable = true) SafetyStockSuggestionRow suggestion) {}

  /** 预警列表（真分页，total 不受分页影响）。默认按 effectiveSeverity 降序。 */
  public record SafetyStockAlertPage(
      List<SafetyStockAlertRow> alerts, long total, int page, int size) {}

  /** 预警状态流转的结果（认领 / 处置完成）。 */
  public record SafetyStockAlertActionResult(
      UUID alertId, SafetyStockAlertStatus status, OffsetDateTime at, String message) {}

  // ------------------------------------------------------------ 补货建议

  /** 补货建议列表查询。 */
  public record SafetyStockSuggestionQuery(
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID skuId,
      String status,
      boolean unquantifiedOnly,
      int page,
      int size) {}

  /**
   * 一条补货建议。
   *
   * <p>蓝图说「可关联自动生成补货建议」，输出指向 6.2.1.2 —— 但 6.2.1.2 未交付且属 06 采购域 （跨服务、独立数据库）。因此本模块生成并落库建议，{@code
   * externalPurchaseReference} 留空 等它交付后回写；{@code /capabilities} 会报 {@code
   * replenishmentTargetDelivered=false}。
   *
   * @param suggestedQuantity 建议补货量；null = 不可推算，原因见 {@code unquantifiedReason}
   * @param targetStockQuantity 目标库存 = 阈值 + 日均销量 × 覆盖天数
   * @param externalPurchaseReference 6.2.1.2 回写的采购单号；未交付前恒为 null
   */
  public record SafetyStockSuggestionRow(
      UUID id,
      UUID alertId,
      UUID policyId,
      UUID warehouseId,
      String warehouseCode,
      UUID skuId,
      String skuCode,
      String skuLabel,
      @Schema(nullable = true) BigDecimal suggestedQuantity,
      @Schema(nullable = true) BigDecimal dailyAverageSales,
      @Schema(nullable = true) BigDecimal coverageDays,
      @Schema(nullable = true) BigDecimal targetStockQuantity,
      String unquantifiedReason,
      @Schema(nullable = true) String externalPurchaseReference,
      SafetyStockSuggestionStatus status,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime dismissedAt,
      @Schema(nullable = true) String dismissalReason) {}

  /** 补货建议列表（真分页）。 */
  public record SafetyStockSuggestionPage(
      List<SafetyStockSuggestionRow> suggestions, long total, int page, int size) {}

  /** 建议采纳 / 忽略的结果。 */
  public record SafetyStockSuggestionAction(
      UUID suggestionId,
      SafetyStockSuggestionStatus status,
      @Schema(nullable = true) String dismissalReason,
      String message) {}

  // ------------------------------------------------------------ 能力声明

  /** 一个可选项（范围档 / 预警级别 / 预警类型 / 状态），带一句前端可直接显示的说明。 */
  public record SafetyStockCapabilityOption(
      String value, String label, boolean available, String description) {}

  /**
   * 安全库存能力清单 —— 以及三条必须**如实讲清楚**的边界。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上；规则改了就该只改一处。 三个 {@code *Available} 布尔位让「哪一环没通」变成一个可读字段，而不是让人从空数组里猜。
   *
   * @param salesSourceDerived 日均销量能否自动派生（恒 false：销量是登记进来的事实）
   * @param skuCategoryExpansionAvailable 分类 → SKU 能否在本地展开（恒 false：分类在 catalog 服务）
   * @param replenishmentTargetDelivered 6.2.1.2 是否已交付（恒 false：建议只落库不回写采购单）
   * @param workbenchAlertFeedDelivered 1 工作台预警是否已交付（恒 false：预警只在本服务可查）
   * @param platformStockoutEscalation 平台断货提级规则的说明
   */
  public record SafetyStockCapabilities(
      List<SafetyStockCapabilityOption> scopeTypes,
      List<SafetyStockCapabilityOption> severities,
      List<SafetyStockCapabilityOption> alertTypes,
      List<SafetyStockCapabilityOption> alertStatuses,
      List<SafetyStockCapabilityOption> warehouseKinds,
      List<SafetyStockCapabilityOption> suggestionStatuses,
      boolean salesSourceDerived,
      boolean skuCategoryExpansionAvailable,
      boolean replenishmentTargetDelivered,
      boolean workbenchAlertFeedDelivered,
      String salesSourceNote,
      String categoryExpansionNote,
      String replenishmentNote,
      String workbenchNote,
      String platformStockoutEscalation,
      String note) {}
}
