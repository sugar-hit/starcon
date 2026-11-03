package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.6.2.1 生命周期与流失预警的传输模型。
 *
 * <p>蓝图 15 卷 15.6.2.1 原话：
 *
 * <blockquote>
 * 职责：客户生命周期分层（RFM/新客-活跃-沉睡-流失-召回）与流失预警：按复购间隔识别本应复购未复购的客户，给出召回建议与流失风险分；支持品牌化客户的核心留存看板。
 * 输入：订单历史（9）；复购周期基线；RFM 计算配置。
 * 输出：生命周期分层结果；流失预警清单；召回建议。
 * 关系：上游 = 9 订单、15.3.1.1；下游 = 15.5.2 触达、15.6.2.2 挽回、13 分析。**复购周期因品类差异极大**（快消周级、家具月级），预警阈值需可按类目配置，不能一刀切。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>每一次判定都要能解释</b>：分层行带 {@code segmentReason}，
 *       预警行带 {@code riskFactors}（因子明细）与 {@code recallSuggestion}。
 *       一个不透明的风险分没法复核。</li>
 *   <li><b>阈值从哪来必须写在脸上</b>：{@code thresholdSource} 说的是
 *       「命中了类目策略 / 命中了全局策略 / 用的是租户真实订单历史的中位复购间隔 /
 *       压根不可判定」—— 四种里没有一种是「代码里写死的 30 天」。</li>
 *   <li><b>召回只是建议</b>：{@code suggestionOwner} 写明归属
 *       （15.5.2 触达 / 15.6.2.2 弃单挽回）—— 本模块**不发送任何消息**。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmLifecycle*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class CustomerLifecycleModels {

  private CustomerLifecycleModels() {}

  // ================================================================ 枚举

  /**
   * 生命周期分层。
   *
   * <p>分档口径（服务端算，前端只原样展示）：
   *
   * <ul>
   *   <li>{@link #NEW} 新客 —— 只下过一单，且还没到该复购的时候；
   *   <li>{@link #ACTIVE} 活跃 —— 下过两单以上，且最近还在买；
   *   <li>{@link #DORMANT} 沉睡 —— 有一阵子没买了，但**还没超过**复购阈值（还没到该复购的时候）；
   *   <li>{@link #RECALL} 召回 —— **本应复购未复购**，但逾期还不算久，值得赶紧召回；
   *   <li>{@link #CHURNED} 流失 —— **本应复购未复购**且逾期已久。
   * </ul>
   *
   * <p>🔴 「召回」与「流失」是**同一件事的两个严重程度**，不是两件事：
   * 两者的判定依据完全相同（都超期），差别只在逾期倍数。
   */
  public enum CustomerLifecycleSegment {
    NEW("新客"),
    ACTIVE("活跃"),
    DORMANT("沉睡"),
    RECALL("待召回"),
    CHURNED("已流失");

    private final String label;

    CustomerLifecycleSegment(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 阈值来源 —— 判定「本应复购未复购」用的是哪套阈值。
   *
   * <p>🔴 四个取值里**没有**「代码默认值」：要么是租户配的策略，
   * 要么是从**真实订单历史**算出来的基线，要么是明说「不可判定」。
   */
  public enum LifecycleThresholdSource {
    CATEGORY_POLICY("命中类目策略"),
    GLOBAL_POLICY("命中全局策略"),
    ORDER_HISTORY_BASELINE("无策略，用租户真实订单历史的中位复购间隔"),
    NONE("不可判定（既无策略也无订单历史基线）");

    private final String label;

    LifecycleThresholdSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 策略作用域。 */
  public enum LifecyclePolicyScope {
    CATEGORY("按类目"),
    GLOBAL("全局兜底");

    private final String label;

    LifecyclePolicyScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 流失风险等级。 */
  public enum ChurnRiskLevel {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("极高");

    private final String label;

    ChurnRiskLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预警处理状态（只能向前推进）。 */
  public enum ChurnAlertStatus {
    OPEN("待处理"),
    ACKNOWLEDGED("已知悉"),
    RESOLVED("已处理");

    private final String label;

    ChurnAlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 重算分层与预警的入参。
   *
   * <p>统计窗口起止必须**成对**给：只给一个会让窗口的两端对不上。
   * 都不给 ⇒ 用默认窗口（最近 365 天）。
   */
  public record CrmLifecycleComputeInput(
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo) {}

  /**
   * 阈值策略的写入入参。
   *
   * <p>🔴 请求体**不接受** tenantId / userId / membershipId：
   * 租户来自已认证成员关系，权限码由服务端解析。
   *
   * @param scope CATEGORY 按类目 / GLOBAL 全局兜底
   * @param categoryCode 类目编码（catalog 的类目编码）；scope=GLOBAL 时**必须**留空
   * @param expectedRepurchaseDays 该类目的预期复购间隔（天）—— 核心可配置项
   */
  public record CrmLifecyclePolicyInput(
      @NotBlank @Pattern(regexp = "CATEGORY|GLOBAL") String scope,
      @Schema(nullable = true) @Size(max = 64) String categoryCode,
      @NotNull @Min(1) @Max(3650) Integer expectedRepurchaseDays,
      @Schema(nullable = true) @Min(0) @Max(3650) Integer toleranceDays,
      @Schema(nullable = true) @Min(1) @Max(3650) Integer rfmWindowDays,
      @Schema(nullable = true) @Min(1) @Max(3650) Integer recencyScoreDays,
      @Schema(nullable = true) @Min(1) @Max(100000) Integer frequencyMinOrders,
      @Schema(nullable = true) BigDecimal monetaryMinAmount,
      @Schema(nullable = true) @Pattern(regexp = "[A-Z]{3}") String monetaryCurrency,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /** 推进预警处理状态的入参。 */
  public record CrmLifecycleAlertStatusInput(@Schema(nullable = true) @Size(max = 600) String note) {}

  // ================================================================ 出参

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmLifecycleCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  /** 能力声明：前端在渲染前就知道哪些维度有、哪些没有。 */
  public record CrmLifecycleCapabilities(
      List<CrmLifecycleCapabilityDimension> dimensions,
      List<String> segments,
      List<String> thresholdSources,
      List<String> riskLevels,
      List<String> alertStatuses,
      String thresholdPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 生命周期分层的一行。
   *
   * @param thresholdSource 阈值来源（**必须能解释用的是哪套阈值**）
   * @param customerBaselineDays 客户本人历史复购间隔的中位数（天）；没有则为 null
   * @param customerBaselineSamples 本人的间隔样本数（几段间隔）
   * @param overdueDays 超期天数（未超期为 0）
   */
  public record CrmLifecycleRow(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      int orderCount,
      @Schema(nullable = true) Integer recencyDays,
      int frequency,
      @Schema(nullable = true) BigDecimal monetary,
      @Schema(nullable = true) String currency,
      int rScore,
      int fScore,
      int mScore,
      String segment,
      String segmentLabel,
      String segmentReason,
      @Schema(nullable = true) String categoryCode,
      String categorySource,
      @Schema(nullable = true) Integer expectedRepurchaseDays,
      @Schema(nullable = true) Integer toleranceDays,
      String thresholdSource,
      String thresholdSourceLabel,
      @Schema(nullable = true) String policyId,
      @Schema(nullable = true) Integer customerBaselineDays,
      int customerBaselineSamples,
      String baselineSource,
      int overdueDays,
      Map<String, Object> scoreBasis,
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo,
      @Schema(nullable = true) String computedAt) {}

  /** 分层统计的一格：生命周期分层。 */
  public record CrmLifecycleSegmentBucket(String segment, String segmentLabel, long customerCount) {}

  /** 分层统计的一格：类目。 */
  public record CrmLifecycleCategoryBucket(
      @Schema(nullable = true) String categoryCode, long customerCount, long alertCount) {}

  /**
   * 分层统计。
   *
   * <p>🔴 **与分页无关**：这些数字来自**单独一条**统计查询（不是拿当前页聚合出来的）。
   */
  public record CrmLifecycleStats(
      long totalCustomers,
      long totalAlerts,
      long overdueCustomers,
      List<CrmLifecycleSegmentBucket> bySegment,
      List<CrmLifecycleCategoryBucket> byCategory,
      String note) {}

  /** 分层列表页：真分页（LIMIT/OFFSET + 稳定全序排序）+ 与分页无关的分层统计。 */
  public record CrmLifecyclePage(
      List<CrmLifecycleRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      CrmLifecycleStats stats,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 风险因子的一格。
   *
   * @param code 因子代码（OVERDUE_RATIO / RECENCY / FREQUENCY / VALUE_EXPOSURE）
   * @param label 因子中文名
   * @param weight 该因子的满分
   * @param rawValue 原始值（逾期倍数 / 距上次购买天数 / 订单数 / 金额）
   * @param points 该因子实际得分
   * @param explanation 一句话说明「这个分是怎么来的」
   */
  public record CrmLifecycleRiskFactor(
      String code, String label, int weight, String rawValue, int points, String explanation) {}

  /**
   * 流失预警的一行。
   *
   * @param riskFactors 因子明细 —— **风险分必须可解释**
   * @param recallSuggestion 召回建议（🔴 只是建议，不触达）
   * @param suggestionOwner 建议归属模块：15.5.2 触达 / 15.6.2.2 弃单挽回
   */
  public record CrmLifecycleAlertRow(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      @Schema(nullable = true) String lastOrderAt,
      int daysSinceLastOrder,
      int expectedRepurchaseDays,
      int toleranceDays,
      int overdueDays,
      String thresholdSource,
      String thresholdSourceLabel,
      @Schema(nullable = true) String categoryCode,
      @Schema(nullable = true) Integer customerBaselineDays,
      int customerBaselineSamples,
      BigDecimal riskScore,
      String riskLevel,
      String riskLevelLabel,
      List<CrmLifecycleRiskFactor> riskFactors,
      String recallSuggestion,
      String suggestionOwner,
      String suggestionOwnerLabel,
      String status,
      String statusLabel,
      String statusNote,
      String detectedAt) {}

  /** 预警详情 = 预警本体（行 + 因子明细）。 */
  public record CrmLifecycleAlertDetail(CrmLifecycleAlertRow alert, List<CrmLifecycleRiskFactor> riskFactors) {}

  /** 预警清单页：真分页 + 真筛选。 */
  public record CrmLifecycleAlertPage(
      List<CrmLifecycleAlertRow> rows, int page, int size, long total, int totalPages, String note) {}

  /**
   * 阈值策略的一行。
   *
   * @param scope CATEGORY 按类目 / GLOBAL 全局兜底
   * @param effectiveFrom / effectiveTo 生效区间（effectiveTo 为空 = 至今仍生效）
   */
  public record CrmLifecyclePolicyRow(
      UUID id,
      String scope,
      String scopeLabel,
      String categoryCode,
      int expectedRepurchaseDays,
      int toleranceDays,
      int rfmWindowDays,
      int recencyScoreDays,
      int frequencyMinOrders,
      @Schema(nullable = true) BigDecimal monetaryMinAmount,
      @Schema(nullable = true) String monetaryCurrency,
      boolean enabled,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean effectiveNow,
      String note,
      String createdAt) {}

  /** 阈值策略列表页（真分页）。 */
  public record CrmLifecyclePolicyPage(
      List<CrmLifecyclePolicyRow> rows, int page, int size, long total, int totalPages, String note) {}

  /**
   * 重算的结果。
   *
   * @param categoryUnresolvedCount 类目没解析出来的客户数（**如实报出**，不是 0）
   * @param thresholdUnavailableCount 阈值不可判定的客户数
   * @param truncated 本次扫描被截断（订单翻页到上限）
   */
  public record CrmLifecycleComputeResult(
      UUID runId,
      String runNo,
      String status,
      String windowFrom,
      String windowTo,
      int customerCount,
      int alertCount,
      int policyCount,
      int categoryPolicyHitCount,
      int categoryUnresolvedCount,
      int thresholdUnavailableCount,
      boolean categoryLookupTruncated,
      boolean truncated,
      String failureReason,
      String startedAt,
      @Schema(nullable = true) String finishedAt,
      String note) {}

  /** 一次重算运行的记录。 */
  public record CrmLifecycleRunRow(
      UUID id,
      String runNo,
      String status,
      String windowFrom,
      String windowTo,
      int customerCount,
      int alertCount,
      int policyCount,
      int categoryPolicyHitCount,
      int categoryUnresolvedCount,
      int thresholdUnavailableCount,
      boolean categoryLookupTruncated,
      boolean truncated,
      String failureReason,
      Map<String, Object> facts,
      @Schema(nullable = true) String operatorId,
      String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /** 重算运行记录列表页（真分页）。 */
  public record CrmLifecycleRunPage(
      List<CrmLifecycleRunRow> rows, int page, int size, long total, int totalPages) {}
}
