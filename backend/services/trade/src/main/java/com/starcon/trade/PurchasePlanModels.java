package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.2.1.3 采购计划的传输模型。
 *
 * <p>蓝图原话：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
 * 支持调整、锁定、导出给采购员。」输入「补货建议集合；计划周期；大促日历；锁库策略」。
 * 输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、优先级）」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code PurchasePlan} 开头。
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>计划类型是结构，不是标签</b>：{@code planType} 取 {@code ROUTINE}（日常补货）/ {@code CAMPAIGN}
 *       （大促备货）。蓝图加粗句「大促备货与日常补货要分开」的第一层就是这一列 ——
 *       混在一张表里再靠人眼分辨，迟早会把大促的单混进日常补货。</li>
 *   <li><b>「答不出来」与「不需要」不折叠</b>：{@code NO_DATA_TEXT} 一律给「无数据」，
 *       绝不显示成 0 —— 0 会被读成「不用备」。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)；系数 / 费率 6 位 HALF_UP。</li>
 *   <li><b>算式随结论一起返回</b>（{@code quantityBasis}）：大促与日常两条不同的算式原文写在这里，
 *       「这个计划量为什么比建议量大」不用点进去再猜。</li>
 *   <li><b>锁定状态如实报</b>：{@code lockStatus} 只在 warehouse 真的确认了预占之后才是
 *       {@code CONFIRMED}；端口未接线 / 上游拒了 ⇒ {@code FAILED} + 失败原因。</li>
 * </ul>
 */
public final class PurchasePlanModels {

  private PurchasePlanModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 计划类型 —— 「这份计划是日常补货还是大促备货」。
   *
   * <p>两种取值走**不同的算式**（见 {@link PurchasePlanRules#campaignQuantity}）：
   * 日常计划量 = 建议量；大促计划量 = 建议量 × 销量系数 × 备货系数。
   * 期望到货日也不同：日常用补货建议给出的预计到货日，大促用「大促开始日 − 提前天数」。
   */
  public enum PurchasePlanType implements Labelled {
    ROUTINE("日常补货"),
    CAMPAIGN("大促备货");

    private final String label;

    PurchasePlanType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 大促计划的两个系数；日常计划恒为 1（等价于「不放大」）。 */
    public boolean isCampaign() {
      return this == CAMPAIGN;
    }
  }

  /** 计划周期类型（蓝图「按周期（月/季）」）。 */
  public enum PurchasePlanPeriodType implements Labelled {
    MONTHLY("月度"),
    QUARTERLY("季度");

    private final String label;

    PurchasePlanPeriodType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 计划状态：DRAFT 草稿 → CONFIRMED 已确认 → 锁定中 → 已锁定 / 部分锁定 / 锁定失败。 */
  public enum PurchasePlanStatus implements Labelled {
    DRAFT("草稿"),
    CONFIRMED("已确认"),
    LOCKING("锁定中"),
    LOCKED("已锁定"),
    PARTIALLY_LOCKED("部分锁定"),
    LOCK_FAILED("锁定失败");

    private final String label;

    PurchasePlanStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 计划行状态。 */
  public enum PurchasePlanLineStatus implements Labelled {
    PLANNED("待锁定"),
    ADJUSTED("已调整"),
    LOCK_REQUESTED("已提交锁定请求"),
    LOCKED("已确认锁定"),
    LOCK_FAILED("锁定失败");

    private final String label;

    PurchasePlanLineStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 锁定状态 —— 「这行的货到底锁上没有」。
   *
   * <p>{@code CONFIRMED} <b>只</b>在 warehouse 的预占接口真的返回了预占 id 之后才写。
   * 端口未接线、上游拒了、超时 ⇒ {@code FAILED} + 失败原因原文。
   */
  public enum PurchasePlanLockStatus implements Labelled {
    NOT_REQUESTED("未请求"),
    REQUESTED("已提交请求"),
    CONFIRMED("已确认"),
    FAILED("锁定失败");

    private final String label;

    PurchasePlanLockStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 优先级（蓝图输出句逐字对应的一列）。 */
  public enum PurchasePlanPriority implements Labelled {
    CRITICAL("紧急"),
    HIGH("高"),
    NORMAL("中"),
    LOW("低");

    private final String label;

    private final int rank;

    PurchasePlanPriority(String label) {
      this.label = label;
      this.rank = ordinal();
    }

    @Override
    public String label() {
      return label;
    }

    /** 排序位次：越小越靠前。 */
    public int rank() {
      return rank;
    }
  }

  /** 大促日历的档期状态。 */
  public enum PurchasePlanCampaignStatus implements Labelled {
    PLANNED("筹备中"),
    ACTIVE("进行中"),
    CLOSED("已结束");

    private final String label;

    PurchasePlanCampaignStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 锁定留痕的动作：REQUEST 已提交请求 / CONFIRM 已确认 / FAIL 失败。 */
  public enum PurchasePlanLockAction implements Labelled {
    REQUEST("已提交锁定请求"),
    CONFIRM("已确认锁定"),
    FAIL("锁定失败");

    private final String label;

    PurchasePlanLockAction(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：本模块的输入分属三个服务 / 模块 —— 补货建议在 6.2.1.2（trade）、
   * 目标仓与法人在 warehouse、锁库（预占）的事实由 warehouse 持有。哪一个接通了、
   * 哪一个没接通，必须由接口自己报出，而不是让调用方从空数组里猜。
   *
   * @param suggestionAvailable 6.2.1.2 补货建议是否可用
   * @param suggestionOwnerModule 补货建议的归属模块
   * @param suggestionNote 补货建议怎么被汇总进计划行
   * @param warehouseReferenceAvailable warehouse 的仓 / 法人参照是否已接入
   * @param warehouseReferenceNote 目标仓与法人从哪来
   * @param lockPortAvailable 锁库端口是否已接线
   * @param lockPortNote 锁库端口接线状态与「没接线时怎么办」的原文
   * @param campaignCalendarAvailable 大促日历是否可用
   * @param campaignCalendarNote 大促日历为什么由本模块自带
   * @param purchaseRequestAvailable 6.2.2.1 采购申请是否可用。**恒为 false** —— 下游未交付
   * @param purchaseRequestNote 采购申请未交付时的说明
   * @param quantityFormula 计划量的算式原文（日常 / 大促两条）
   * @param arrivalFormula 期望到货日的推导原文（日常 / 大促两条）
   * @param note 总体说明
   */
  public record PurchasePlanCapabilities(
      boolean suggestionAvailable,
      String suggestionOwnerModule,
      String suggestionNote,
      boolean warehouseReferenceAvailable,
      String warehouseReferenceNote,
      boolean lockPortAvailable,
      String lockPortNote,
      boolean campaignCalendarAvailable,
      String campaignCalendarNote,
      boolean purchaseRequestAvailable,
      String purchaseRequestNote,
      String quantityFormula,
      String arrivalFormula,
      String note) {}

  // ================================================================ 大促日历

  /**
   * 登记 / 更新一条大促档期。
   *
   * @param code 档期编码（租户内唯一）
   * @param name 档期名
   * @param shopCode 店铺引用码。为空 = 全店铺通用
   * @param startDate 档期开始日
   * @param endDate 档期结束日
   * @param salesMultiplier 预期销量系数（相对日常）。为空 = 1
   * @param stockFactor 备货系数。为空 = 1
   * @param arrivalLeadDays 期望到货提前天数。为空 = 0
   * @param status 档期状态。为空 = PLANNED
   * @param note 备注
   */
  public record PurchasePlanCampaignInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String name,
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal salesMultiplier,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal stockFactor,
      @Schema(nullable = true) @Min(0) @Max(180) Integer arrivalLeadDays,
      @Schema(nullable = true) PurchasePlanCampaignStatus status,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 大促档期行。 */
  public record PurchasePlanCampaignRow(
      UUID id,
      String code,
      String name,
      @Schema(nullable = true) String shopCode,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal salesMultiplier,
      BigDecimal stockFactor,
      int arrivalLeadDays,
      String status,
      String statusLabel,
      @Schema(nullable = true) String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 大促日历列表（真分页）。 */
  public record PurchasePlanCampaignPage(
      List<PurchasePlanCampaignRow> rows, long total, int page, int size) {}

  // ================================================================ 采购计划

  /**
   * 建一张采购计划。
   *
   * @param planType 计划类型（日常 / 大促）
   * @param campaignId 大促 id。planType=CAMPAIGN 时必填
   * @param periodType 周期类型（月 / 季）
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param targetWarehouseId 目标仓
   * @param legalEntityId 法人（锁库预占要落法人货权）
   * @param priority 计划优先级。为空 = NORMAL
   * @param note 备注
   */
  public record PurchasePlanCreateInput(
      @NotNull PurchasePlanType planType,
      @Schema(nullable = true) UUID campaignId,
      @NotNull PurchasePlanPeriodType periodType,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotNull UUID targetWarehouseId,
      @NotNull UUID legalEntityId,
      @Schema(nullable = true) PurchasePlanPriority priority,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 采购计划行（头）。 */
  public record PurchasePlanRow(
      UUID id,
      String planNo,
      String planType,
      String planTypeLabel,
      @Schema(nullable = true) UUID campaignId,
      @Schema(nullable = true) String campaignCode,
      @Schema(nullable = true) String campaignName,
      String periodType,
      String periodTypeLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String status,
      String statusLabel,
      String priority,
      String priorityLabel,
      int lineCount,
      int lockedLineCount,
      int failedLineCount,
      @Schema(nullable = true) BigDecimal totalPlanQuantity,
      @Schema(nullable = true) BigDecimal totalSuggestedQuantity,
      @Schema(nullable = true) String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 采购计划列表（真分页 + 真筛选 + 真排序）。 */
  public record PurchasePlanPage(
      List<PurchasePlanRow> rows, long total, int page, int size, String note) {}

  /** 计划详情：头 + 计划行。 */
  public record PurchasePlanDetail(PurchasePlanRow plan, List<PurchasePlanLineRow> lines, String note) {}

  // ================================================================ 计划行

  /** 计划行（蓝图输出句：SKU、数量、目标仓、期望到货日、优先级）。 */
  public record PurchasePlanLineRow(
      UUID id,
      UUID planId,
      String planNo,
      String planType,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) UUID sourceSuggestionId,
      @Schema(nullable = true) UUID sourceBatchId,
      @Schema(nullable = true) BigDecimal suggestedQuantity,
      BigDecimal planQuantity,
      String quantityBasis,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      LocalDate expectedArrivalDate,
      String priority,
      String priorityLabel,
      String status,
      String statusLabel,
      String lockStatus,
      String lockStatusLabel,
      @Schema(nullable = true) UUID lockReservationId,
      @Schema(nullable = true) String lockReservationNo,
      @Schema(nullable = true) OffsetDateTime lockRequestedAt,
      @Schema(nullable = true) OffsetDateTime lockConfirmedAt,
      String lockFailureReason,
      @Schema(nullable = true) String note,
      int sortRank) {}

  /** 计划行列表（真分页 + 真筛选）。 */
  public record PurchasePlanLinePage(
      List<PurchasePlanLineRow> rows, long total, int page, int size, String note) {}

  /** 计划行详情：行 + 调整留痕 + 锁定留痕。 */
  public record PurchasePlanLineDetail(
      PurchasePlanLineRow line,
      List<PurchasePlanAdjustmentRow> adjustments,
      List<PurchasePlanLockLogRow> lockLogs,
      String note) {}

  // ================================================================ 汇总

  /**
   * 从补货建议汇总成计划行。
   *
   * @param sourceBatchId 来源的 6.2.1.2 计算批次
   * @param skuCode 只汇总某个 SKU 编码。为空 = 全部
   * @param keyword 关键词（SKU 编码 / 名称）
   * @param defaultPriority 汇总进来的行的默认优先级。为空 = NORMAL
   * @param note 备注
   */
  public record PurchasePlanAggregateInput(
      @NotNull UUID sourceBatchId,
      @Schema(nullable = true) @Size(max = 64) String skuCode,
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) PurchasePlanPriority defaultPriority,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 一次汇总的结果。 */
  public record PurchasePlanAggregateResult(
      UUID planId,
      String planNo,
      UUID sourceBatchId,
      String sourceBatchNo,
      int scannedSuggestionCount,
      int createdLineCount,
      int skippedExistingCount,
      int skippedNotReplenishCount,
      boolean truncated,
      String truncatedNote,
      String note) {}

  // ================================================================ 调整

  /**
   * 调整一个计划行。**至少要给一个要改的字段**，且理由必填。
   *
   * @param planQuantity 新的计划量
   * @param expectedArrivalDate 新的期望到货日
   * @param priority 新的优先级
   * @param targetWarehouseId 新的目标仓
   * @param note 新的行备注
   * @param reason 调整理由。**必填**
   */
  public record PurchasePlanLineAdjustInput(
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal planQuantity,
      @Schema(nullable = true) LocalDate expectedArrivalDate,
      @Schema(nullable = true) PurchasePlanPriority priority,
      @Schema(nullable = true) UUID targetWarehouseId,
      @Schema(nullable = true) @Size(max = 500) String note,
      @NotBlank @Size(max = 500) String reason) {}

  /** 调整留痕的一行（append-only）。 */
  public record PurchasePlanAdjustmentRow(
      UUID id,
      UUID planLineId,
      String field,
      @Schema(nullable = true) String beforeValue,
      @Schema(nullable = true) String afterValue,
      String reason,
      UUID adjustedBy,
      OffsetDateTime adjustedAt) {}

  // ================================================================ 锁定

  /**
   * 发起一次锁库。
   *
   * @param note 备注（会随预占登记一起送到 warehouse）
   */
  public record PurchasePlanLockInput(@Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 一次锁定的结果。
   *
   * <p><b>失败也要返回（而不是抛异常）</b>：锁定失败必须**落库留痕**（行状态 → 锁定失败、
   * 留痕表 → FAIL 一行）。而整条调用链跑在一个事务里（identity 的租户上下文绑定就在事务内），
   * 一旦抛异常，事务回滚会把「已提交请求」「失败原因」一起抹掉 —— 那样就变成
   * 「仓库拒了，但本地什么痕迹都没有」，下一次没人知道发生过什么。
   * 所以服务层<b>记完再返回</b>，由控制器在事务提交之后把失败翻成对应的 HTTP 状态码。
   *
   * @param failed 本次锁定是否失败
   * @param errorStatus 失败时的 HTTP 状态码（供控制器翻成响应）。成功时为 null
   */
  public record PurchasePlanLockResult(
      UUID planLineId,
      String lockStatus,
      String lockStatusLabel,
      String lineStatus,
      @Schema(nullable = true) UUID reservationId,
      @Schema(nullable = true) String reservationNo,
      @Schema(nullable = true) OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      boolean failed,
      @Schema(nullable = true) Integer errorStatus,
      String message) {}

  /** 锁定留痕的一行（append-only）。 */
  public record PurchasePlanLockLogRow(
      UUID id,
      UUID planLineId,
      String action,
      String actionLabel,
      @Schema(nullable = true) UUID reservationId,
      @Schema(nullable = true) String reservationNo,
      String message,
      UUID requestedBy,
      OffsetDateTime requestedAt) {}

  // ================================================================ 锁库策略

  /**
   * 登记一条锁库策略（版本化：同一类型已有未收口的策略时，新策略把旧的<b>收口</b>）。
   *
   * @param planType 作用范围（日常 / 大促）
   * @param lockEnabled 是否允许锁库
   * @param holdDays 预占持有天数（换算成 warehouse 的 expires_at）。0 = 不过期
   * @param requireConfirmed 是否要求计划已确认才允许锁
   * @param effectiveFrom 生效起点（含）
   * @param effectiveTo 生效终点（不含）。null = 当前生效
   * @param note 备注
   */
  public record PurchasePlanLockPolicyInput(
      @NotNull PurchasePlanType planType,
      @NotNull Boolean lockEnabled,
      @NotNull @Min(0) @Max(365) Integer holdDays,
      @NotNull Boolean requireConfirmed,
      @NotNull LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 锁库策略行。 */
  public record PurchasePlanLockPolicyRow(
      UUID id,
      String planType,
      String planTypeLabel,
      boolean lockEnabled,
      int holdDays,
      boolean requireConfirmed,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) String note,
      OffsetDateTime createdAt,
      boolean effective) {}

  /** 锁库策略列表（真分页）。 */
  public record PurchasePlanLockPolicyPage(
      List<PurchasePlanLockPolicyRow> rows, long total, int page, int size) {}

  // ================================================================ 参照下拉

  /** 目标仓下拉项（来自 warehouse 的授权只读接口）。 */
  public record PurchasePlanWarehouseOption(UUID id, String code, String name, String status) {}

  /** 法人下拉项（来自 warehouse 的授权只读接口）。 */
  public record PurchasePlanLegalEntityOption(UUID id, String code, String name, String status) {}

  /** 大促下拉项（本模块自带日历）。 */
  public record PurchasePlanCampaignOption(UUID id, String code, String name, LocalDate startDate, LocalDate endDate) {}

  /** 建计划时需要的全部参照项。 */
  public record PurchasePlanOptions(
      List<PurchasePlanWarehouseOption> warehouses,
      List<PurchasePlanLegalEntityOption> legalEntities,
      List<PurchasePlanCampaignOption> campaigns,
      String warehouseNote,
      String note) {}

  // ================================================================ 汇总视图

  /**
   * 一段汇总（日常 / 大促**各一段**）。
   *
   * <p>蓝图加粗句「大促备货与日常补货要分开」的第三层：汇总视图**不给**一个把两种备货量
   * 加在一起的总数字 —— 那个数字会让日常补货失焦，正是这条加粗句要防的事。
   */
  public record PurchasePlanSummaryBucket(
      String planType,
      String planTypeLabel,
      long planCount,
      long lineCount,
      BigDecimal totalPlanQuantity,
      BigDecimal totalSuggestedQuantity,
      long adjustedLineCount,
      long lockedLineCount,
      long failedLineCount,
      @Schema(nullable = true) LocalDate earliestArrivalDate,
      @Schema(nullable = true) LocalDate latestArrivalDate) {}

  /**
   * 汇总视图：日常一段、大促一段。
   *
   * @param routine 日常补货段
   * @param campaign 大促备货段
   * @param note 说明
   */
  public record PurchasePlanSummary(
      PurchasePlanSummaryBucket routine,
      PurchasePlanSummaryBucket campaign,
      @Schema(nullable = true) LocalDate periodStart,
      @Schema(nullable = true) LocalDate periodEnd,
      @Schema(nullable = true) UUID targetWarehouseId,
      String note) {}
}
