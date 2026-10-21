package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.6.1.1 开发立项的传输模型。
 *
 * <p>蓝图原话：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
 * 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与试产单，
 * 并作为 SKU 建档的前置。」输入「立项申请与评审结论；目标定价与成本估算（5.3.1.1）」。
 * 输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code Development} / {@code DevelopmentProject} 开头。
 *
 * <p><b>六条建模纪律</b>：
 *
 * <ul>
 *   <li><b>目标毛利率是服务端算出来的</b>：{@code targetMarginRate} 只出现在**响应**里，
 *       任何**输入**模型里都**没有**毛利率字段 —— 调用方不许自己报一个毛利率。
 *       成本取不到时毛利率是 {@code null}（连带 {@code marginBasis} 说明为什么没有），
 *       **绝不**拿 0 或 100% 顶替。</li>
 *   <li><b>「答不出来」与「确实是 0」不折叠</b>：{@code targetCost} / {@code costAvailable} /
 *       {@code costOwnerModule} 三件一起说明成本这件事的状态。</li>
 *   <li><b>金额全程 BigDecimal</b>，DB 侧 numeric(19,4)；毛利率 numeric(19,6)，
 *       单次 HALF_UP 舍入（口径写在 {@link DevelopmentProjectRules#MARGIN_RATE_SCALE} 与
 *       {@code marginBasis} 里）。</li>
 *   <li><b>时间一律 timestamptz</b>：用 {@link OffsetDateTime} 传输，不传本地时刻。</li>
 *   <li><b>状态是字符串 + 中文名成对返回</b>（{@code status} / {@code statusLabel}），
 *       前端不必自己再写一遍映射 —— 各写一遍迟早漂移。</li>
 *   <li><b>「跳过打样 / 试产」必须留豁免记录</b>：{@code exemptionCode} + {@code exemptionNote}
 *       成对出现，数据库层也钉死（见 V257 迁移的 CHECK 与守卫触发器）。</li>
 * </ul>
 */
public final class DevelopmentProjectModels {

  private DevelopmentProjectModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 立项状态 —— 蓝图职责句点名的七个状态。
   *
   * <p>🔴 状态机**不是**「前端传什么就存什么」：允许的迁移在
   * {@link DevelopmentProjectRules#allowedTargets}（服务层）与 V257 迁移的守卫触发器
   * （数据库层）**两处**都钉死。
   *
   * <p>🔴 {@link #TERMINATED} 是**终态**：没有出边，不提供「恢复」——
   * 「恢复」会让「这个立项为什么停了」永远查不回来，而那正是终止事件存在的理由。
   */
  public enum DevelopmentStatus implements Labelled {
    EVALUATION("评估"),
    APPROVED("立项"),
    IN_DEVELOPMENT("开发中"),
    SAMPLING("打样"),
    PILOT("试产"),
    MASS_PRODUCTION("量产"),
    TERMINATED("终止");

    private final String label;

    DevelopmentStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 里程碑类型：打样 / 试产 / 量产 / 上市。 */
  public enum DevelopmentMilestoneType implements Labelled {
    SAMPLING("打样"),
    PILOT("试产"),
    MASS_PRODUCTION("量产"),
    LAUNCH("上市");

    private final String label;

    DevelopmentMilestoneType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 目标成本的来源。
   *
   * <p>🔴 {@link #UNAVAILABLE} 与「成本是 0」是**两件事**：前者是「答不出来」，
   * 后者是一个看起来很漂亮但彻头彻尾假的数字（成本 0 的毛利率是 100%）。
   */
  public enum DevelopmentCostSource implements Labelled {
    CATALOG_ESTIMATE("5.3.1.1 利润测算器（catalog）"),
    MANUAL_ESTIMATE("手工估算（供应商报价 / BOM）"),
    UNAVAILABLE("取不到");

    private final String label;

    DevelopmentCostSource(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 交接单类型 —— 本模块**只产出**交接单，不代下游建单。 */
  public enum DevelopmentHandoffType implements Labelled {
    SAMPLING_ORDER("打样单", "6.6.1.2"),
    PILOT_ORDER("试产单", "6.6.2.1"),
    SKU_ONBOARDING("SKU 建档", "3.1.1.1");

    private final String label;
    private final String targetModule;

    DevelopmentHandoffType(String label, String targetModule) {
      this.label = label;
      this.targetModule = targetModule;
    }

    @Override
    public String label() {
      return label;
    }

    /** 目标模块 ID（本模块**不**在该模块建单，只交接）。 */
    public String targetModule() {
      return targetModule;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>上游分属两个地方：目标成本在 5.3.1.1（catalog，已交付）、市场数据在 14 广告
   * （**reporting 服务在本仓不存在**）。下游三个模块（6.6.1.2 / 6.6.2.1 / 3.1.1.1）
   * 本模块**只产出交接单**，不代它们建单。
   */
  public record DevelopmentProjectCapabilities(
      boolean costEstimateAvailable,
      String costEstimateSource,
      String costEstimateNote,
      boolean marketDataAvailable,
      String marketDataOwnerModule,
      String marketDataNote,
      boolean samplingModuleDelivered,
      String samplingModuleNote,
      boolean pilotModuleDelivered,
      String pilotModuleNote,
      boolean skuOnboardingDelivered,
      String skuOnboardingNote,
      String stateMachineNote,
      String marginFormula,
      String marginRoundingNote,
      String boundaryNote) {}

  // ================================================================ 下拉项

  /** 建计划参照项。 */
  public record DevelopmentProjectOptions(
      List<DevelopmentStatusOption> statuses,
      List<MilestoneOption> milestoneTypes,
      List<CostSourceOption> costSources,
      List<HandoffOption> handoffTypes) {}

  /** 一个状态选项。 */
  public record DevelopmentStatusOption(String value, String label, boolean terminal, List<String> allowedTargets) {}

  /** 一个里程碑类型选项。 */
  public record MilestoneOption(String value, String label) {}

  /** 一个成本来源选项。 */
  public record CostSourceOption(String value, String label) {}

  /** 一个交接单类型选项。 */
  public record HandoffOption(String value, String label, String targetModule) {}

  // ================================================================ 列表

  /** 立项清单的一行。 */
  public record DevelopmentProjectRow(
      UUID id,
      String projectNo,
      String name,
      String category,
      String status,
      String statusLabel,
      UUID ownerId,
      String ownerName,
      BigDecimal targetPrice,
      String currency,
      @Schema(nullable = true) BigDecimal targetCost,
      @Schema(nullable = true) String costCurrency,
      boolean costAvailable,
      String costSource,
      String costSourceLabel,
      @Schema(nullable = true) BigDecimal targetMarginRate,
      @Schema(nullable = true) String marginBasis,
      int marketCount,
      int milestoneCount,
      @Schema(nullable = true) OffsetDateTime launchTargetAt,
      @Schema(nullable = true) OffsetDateTime samplingPlannedAt,
      @Schema(nullable = true) OffsetDateTime massProductionPlannedAt,
      @Schema(nullable = true) String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 立项清单页（真分页）。 */
  public record DevelopmentProjectPage(List<DevelopmentProjectRow> rows, long total, int page, int size) {}

  /** 按状态分组的概览。 */
  public record DevelopmentProjectSummary(
      long total,
      List<DevelopmentStatusCount> statuses,
      List<DevelopmentProjectRow> recent) {}

  /** 一个状态的立项数。 */
  public record DevelopmentStatusCount(String status, String label, long count) {}

  // ================================================================ 详情

  /** 立项详情：立项记录 + 里程碑计划 + 目标市场 + 规格候选 + 市场观察 + 迁移历史 + 交接单。 */
  public record DevelopmentProjectDetail(
      DevelopmentProjectRow project,
      List<DevelopmentMilestoneRow> milestones,
      List<DevelopmentMarketRow> markets,
      List<DevelopmentSpecCandidateRow> specCandidates,
      List<DevelopmentMarketObservationRow> marketObservations,
      List<DevelopmentTransitionRow> transitions,
      List<DevelopmentHandoffRow> handoffs,
      List<String> allowedTargets) {}

  // ================================================================ 子资源

  /** 里程碑计划的一行。 */
  public record DevelopmentMilestoneRow(
      UUID id,
      UUID projectId,
      String milestoneType,
      String milestoneTypeLabel,
      OffsetDateTime plannedAt,
      @Schema(nullable = true) OffsetDateTime actualAt,
      String status,
      @Schema(nullable = true) String note) {}

  /** 目标市场与平台的一行。 */
  public record DevelopmentMarketRow(
      UUID id,
      UUID projectId,
      String marketCode,
      String platformCode,
      BigDecimal targetPrice,
      String currency,
      int priority,
      int sortRank) {}

  /** 规格候选的一行。 */
  public record DevelopmentSpecCandidateRow(
      UUID id, UUID projectId, String specName, String specValue, int sortRank) {}

  /** 手工录入的市场数据的一行。 */
  public record DevelopmentMarketObservationRow(
      UUID id,
      UUID projectId,
      String marketCode,
      String platformCode,
      String metricCode,
      @Schema(nullable = true) BigDecimal metricValue,
      String valueUnit,
      String source,
      OffsetDateTime collectedAt,
      @Schema(nullable = true) String note) {}

  /** 状态迁移事件的一行（不可改写）。 */
  public record DevelopmentTransitionRow(
      UUID id,
      UUID projectId,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      @Schema(nullable = true) String exemptionCode,
      @Schema(nullable = true) String exemptionNote,
      UUID actorId,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime appliedAt) {}

  /** 交接单的一行。 */
  public record DevelopmentHandoffRow(
      UUID id,
      UUID projectId,
      String handoffType,
      String handoffTypeLabel,
      String targetModule,
      String status,
      String payload,
      @Schema(nullable = true) String note,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  // ================================================================ 输入

  /** 立项输入（建单）。 */
  public record DevelopmentProjectCreateInput(
      @NotBlank @Size(max = 64) String projectNo,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String category,
      @NotNull UUID ownerId,
      @Size(max = 100) String ownerName,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice,
      @NotBlank @Size(min = 3, max = 3) String currency,
      /** 目标成本（手工估算）。留空 = 取不到 ⇒ 毛利率为 null。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal targetCost,
      @Schema(nullable = true) @Size(max = 500) String costBasis,
      @Schema(nullable = true) OffsetDateTime costEstimatedAt,
      /** 参考 SKU（成本估算基准）：有它才有系统来源的成本。 */
      @Schema(nullable = true) UUID referenceSkuId,
      @Schema(nullable = true) @Size(max = 64) String referenceSkuCode,
      @Schema(nullable = true) @Size(max = 2000) String decisionNote,
      @Schema(nullable = true) OffsetDateTime launchTargetAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 立项更新输入（可改的字段）。 */
  public record DevelopmentProjectUpdateInput(
      @Schema(nullable = true) @Size(max = 200) String name,
      @Schema(nullable = true) @Size(max = 64) String category,
      @Schema(nullable = true) UUID ownerId,
      @Schema(nullable = true) @Size(max = 100) String ownerName,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice,
      @Schema(nullable = true) @Size(min = 3, max = 3) String currency,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal targetCost,
      @Schema(nullable = true) @Size(max = 500) String costBasis,
      @Schema(nullable = true) OffsetDateTime costEstimatedAt,
      @Schema(nullable = true) UUID referenceSkuId,
      @Schema(nullable = true) @Size(max = 64) String referenceSkuCode,
      @Schema(nullable = true) @Size(max = 2000) String decisionNote,
      @Schema(nullable = true) OffsetDateTime launchTargetAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 状态迁移输入。
   *
   * <p>🔴 **没有「目标状态可以随便传」的余地**：{@code toStatus} 只在状态机允许的出边上有效，
   * 服务层与数据库层都会拒绝非法迁移。
   *
   * <p>🔴 {@code reason} **必填**：迁移事件要回答「为什么」，一句「推进一下」不是留痕。
   */
  public record DevelopmentTransitionInput(
      @NotNull DevelopmentStatus toStatus,
      @NotBlank @Size(max = 500) String reason,
      /** 豁免码（跳过打样 / 试产时必填）。 */
      @Schema(nullable = true) @Size(max = 32) String exemptionCode,
      /** 豁免说明（跳过打样 / 试产时必填）。 */
      @Schema(nullable = true) @Size(max = 500) String exemptionNote,
      /** 迁移发生时刻。留空 = 服务端当前时刻。 */
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /** 里程碑计划输入。 */
  public record DevelopmentMilestoneInput(
      @NotNull DevelopmentMilestoneType milestoneType,
      @NotNull OffsetDateTime plannedAt,
      @Schema(nullable = true) OffsetDateTime actualAt,
      @Schema(nullable = true) DevelopmentMilestoneStatus status,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 里程碑状态。 */
  public enum DevelopmentMilestoneStatus implements Labelled {
    PLANNED("计划中"),
    REACHED("已达成"),
    MISSED("错过");

    private final String label;

    DevelopmentMilestoneStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 目标市场与平台输入。 */
  public record DevelopmentMarketInput(
      @NotBlank @Size(max = 32) String marketCode,
      @NotBlank @Size(max = 32) String platformCode,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice,
      @NotBlank @Size(min = 3, max = 3) String currency,
      @Schema(nullable = true) Integer priority,
      @Schema(nullable = true) Integer sortRank) {}

  /** 规格候选输入。 */
  public record DevelopmentSpecCandidateInput(
      @NotBlank @Size(max = 120) String specName,
      @NotBlank @Size(max = 200) String specValue,
      @Schema(nullable = true) Integer sortRank) {}

  /** 手工录入的市场数据输入。🔴 来源与采集时间**必填**。 */
  public record DevelopmentMarketObservationInput(
      @NotBlank @Size(max = 32) String marketCode,
      @Schema(nullable = true) @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 32) String metricCode,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal metricValue,
      @Schema(nullable = true) @Size(max = 32) String valueUnit,
      @NotBlank @Size(max = 200) String source,
      @NotNull OffsetDateTime collectedAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 交接单输入。 */
  public record DevelopmentHandoffInput(
      @NotNull DevelopmentHandoffType handoffType, @Schema(nullable = true) @Size(max = 500) String note) {}

  // ================================================================ 成本估算 / SKU 候选

  /**
   * 从 5.3.1.1（catalog）取到的成本估算视图。
   *
   * <p>🔴 {@code available=false} 时 {@code unitCost} 必为 {@code null}，
   * 且 {@code ownerModule} 点名归谁管 —— 取不到就说取不到，不拿 0 顶替。
   */
  public record DevelopmentCostEstimateView(
      boolean available,
      @Schema(nullable = true) BigDecimal unitCost,
      @Schema(nullable = true) String costCurrency,
      String source,
      String sourceLabel,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason,
      @Schema(nullable = true) String basis,
      @Schema(nullable = true) OffsetDateTime estimatedAt) {}

  /**
   * 可转 SKU 建档的**候选参数**（蓝图输出句逐字对应）。
   *
   * <p>🔴 本模块**只产出候选参数**，**不建 SKU、不写 catalog 的表** ——
   * SKU 的唯一写入所有者是 catalog（3.1.1.1）。
   */
  public record DevelopmentSkuCandidate(
      UUID projectId,
      String projectNo,
      String name,
      String category,
      List<DevelopmentMarketRow> targetMarkets,
      @Schema(nullable = true) BigDecimal targetPriceMin,
      @Schema(nullable = true) BigDecimal targetPriceMax,
      @Schema(nullable = true) String currency,
      List<DevelopmentSpecCandidateRow> specCandidates,
      @Schema(nullable = true) OffsetDateTime launchTargetAt,
      @Schema(nullable = true) OffsetDateTime samplingPlannedAt,
      @Schema(nullable = true) OffsetDateTime massProductionPlannedAt,
      String status,
      String statusLabel,
      String handoffType,
      String targetModule,
      boolean handoffReady,
      String note) {}
}
