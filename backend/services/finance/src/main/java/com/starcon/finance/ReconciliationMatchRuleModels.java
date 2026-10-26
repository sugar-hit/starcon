package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.3.2.3 自动匹配规则的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。 输入：规则配置；历史对账结果用于规则调优。 输出：规则集 + 匹配率统计 + 未匹配原因分布。 关系：上游 = 12.3.2.1；下游 = 匹配引擎。 <b>匹配率是财务自动化的核心指标</b>，持续低于目标时应排查上游数据质量而非调规则。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceMatchRule*} 前缀 —— 与 12.3.2.1 的
 * {@code FinanceReconTask*}、12.3.2.2 的 {@code FinanceReconDifference*}、
 * 12.7.1.2 的 {@code FinanceBankRecon*} 零重叠。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都有对应实现，不是口号）：
 *
 * <ol>
 *   <li><b>🔴 金额不在匹配键里。</b>蓝图把匹配键写成「订单号 + 金额 + 日期 + 类型」，
 *       但卷 12 的加粗硬约束是「金额相同只是候选，必须结合订单号、日期、类型」——
 *       ⇒ {@code matchKeys} 只能取 {REFERENCE, DATE, TYPE} 且**至少两个**；
 *       {@code minDimensionCount}（至少对上几个维度才算候选）**≥ 2**。
 *       金额只以**容差**（金额 / 手续费 / 汇率差）出现在配平与残差归因上。</li>
 *   <li><b>容差分三种，各有各的口径。</b>金额容差 = 候选配对带；
 *       手续费容差 / 汇率差容差 = 残差**归因**带（「这批没对上的主要是手续费」）。</li>
 *   <li><b>模糊兜底放松「怎么比」，不放松「要比几个」。</b>模糊阶段订单号改按前缀比、
 *       日期窗口放宽，但**仍要求对上 ≥ 2 个维度** ⇒ 兜底不会退化成「金额相同即匹配」。</li>
 *   <li><b>优先级可判定。</b>激活中的规则在同一租户内优先级唯一（DB partial UNIQUE index）
 *       ⇒ 「哪条规则先试」是确定的，不依赖 id / created_at 的偶然次序。</li>
 *   <li><b>匹配率按币种分组，绝不跨币种合计。</b>汇率不可得时差额恒为 {@code null}
 *       （<b>不按 1:1 兜底</b>）；匹配率分母为 0 时比率恒为 {@code null}
 *       （<b>不写 0</b> —— 0 会被读成「一条都没配上」）。</li>
 * </ol>
 */
public final class ReconciliationMatchRuleModels {

  private ReconciliationMatchRuleModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x / 12.3.x / 12.7.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /** 规则状态机：DRAFT → ACTIVE → DISABLED（DISABLED 可以回到 DRAFT 重编辑）。 */
  public enum FinanceMatchRuleStatus {
    /** 建了还没启用。 */
    DRAFT("未启用"),
    /** 启用中（引擎按优先级依次试）。 */
    ACTIVE("已启用"),
    /** 停用。 */
    DISABLED("已停用");

    private final String label;

    FinanceMatchRuleStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 未匹配原因 —— **受控词表**。
   *
   * <p>前五种来自 12.3.2.1 的差异类型；后两种是本模块**残差归因**出来的
   * （残差落在规则的手续费 / 汇率差容差带内）⇒ 运营能一眼看出
   * 「这批没对上的主要是手续费，不是账错了」。
   */
  public enum FinanceMatchRuleReason {
    /** 平台账单多出。 */
    EXTERNAL_ONLY("平台账单多出"),
    /** 我方账多出。 */
    BOOK_ONLY("我方账多出"),
    /** 金额对不齐（超出全部容差）。 */
    AMOUNT_MISMATCH("金额对不齐"),
    /** 币种不一致 ⇒ 汇率不可得，差额恒为 null。 */
    CURRENCY_MISMATCH("币种不一致"),
    /** 维度对不上（只对上 1 个维度 ⇒ 不构成匹配）。 */
    DIMENSION_MISMATCH("维度对不上"),
    /** 手续费差异（残差落在手续费容差带内）。 */
    FEE_DIFFERENCE("手续费差异"),
    /** 汇率差（残差落在汇率差容差带内）。 */
    EXCHANGE_RATE_DIFFERENCE("汇率差");

    private final String label;

    FinanceMatchRuleReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力声明的一条。 */
  public record FinanceMatchRuleCapability(
      String moduleId,
      String name,
      boolean delivered,
      String source,
      String ownerModule,
      String note) {}

  /** 下游 / 上游缺口的一条。 */
  public record FinanceMatchRuleGap(
      String moduleId, String name, String domain, boolean delivered, String owner, String note) {}

  /** 能力声明（页面上固定显示在顶部）。 */
  public record FinanceMatchRuleCapabilities(
      List<FinanceMatchRuleCapability> capabilities, List<FinanceMatchRuleGap> gaps) {}

  // ================================================================ 筛选项

  /** 通用下拉项。 */
  public record FinanceMatchRuleOption(String value, String label) {}

  /** 可选的资金账户（12.7.1.1 的真实账户，不是常量）。 */
  public record FinanceMatchRuleAccount(
      UUID id,
      String accountNo,
      String accountName,
      String currency,
      String legalEntityName) {}

  /** 筛选项。 */
  public record FinanceMatchRuleOptions(
      List<FinanceMatchRuleOption> statuses,
      List<FinanceMatchRuleOption> matchKeys,
      List<FinanceMatchRuleOption> reasons,
      List<String> currencies,
      List<FinanceMatchRuleAccount> accounts,
      String note) {}

  // ================================================================ 规则

  /** 一行匹配规则。 */
  public record FinanceMatchRuleRow(
      UUID id,
      String ruleNo,
      String ruleName,
      String status,
      String statusLabel,
      int priority,
      List<String> matchKeys,
      int minDimensionCount,
      int dateWindowDays,
      BigDecimal amountTolerance,
      BigDecimal feeTolerance,
      BigDecimal exchangeRateTolerance,
      boolean fuzzyEnabled,
      int fuzzyReferencePrefixLength,
      int fuzzyDateWindowDays,
      BigDecimal fuzzyAmountTolerance,
      BigDecimal matchRateTarget,
      /**
       * 适用币种。
       *
       * <p>⚠️ <b>可空</b>：空 = 全部币种。必须标 {@code nullable = true}，
       * 否则生成的契约说「非空」而实际可能给 {@code null} ⇒ 前端会显示出 {@code null}。
       */
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID accountId,
      @Schema(nullable = true) UUID shopId,
      String note,
      long version,
      @Schema(nullable = true) OffsetDateTime activatedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则列表（真分页）。 */
  public record FinanceMatchRulePage(List<FinanceMatchRuleRow> items, int page, int size, long total) {}

  /** 建 / 改规则的入参。 */
  public record FinanceMatchRuleInput(
      @NotBlank String ruleName,
      @Min(1) @Max(9999) Integer priority,
      @NotEmpty List<String> matchKeys,
      @Min(2) @Max(3) Integer minDimensionCount,
      @Min(1) @Max(31) Integer dateWindowDays,
      @DecimalMin("0") BigDecimal amountTolerance,
      @DecimalMin("0") BigDecimal feeTolerance,
      @DecimalMin("0") BigDecimal exchangeRateTolerance,
      Boolean fuzzyEnabled,
      @Min(4) @Max(32) Integer fuzzyReferencePrefixLength,
      @Min(1) @Max(31) Integer fuzzyDateWindowDays,
      @DecimalMin("0") BigDecimal fuzzyAmountTolerance,
      @DecimalMin("0") @DecimalMax("100") BigDecimal matchRateTarget,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID accountId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String note) {}

  // ================================================================ 事件流

  /** 一条规则事件（append-only 举证）。 */
  public record FinanceMatchRuleEventRow(
      UUID id,
      UUID ruleId,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String summary,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 规则事件流页（真分页）。 */
  public record FinanceMatchRuleEventPage(
      List<FinanceMatchRuleEventRow> items, int page, int size, long total) {}

  // ================================================================ 匹配率统计

  /**
   * 一个币种的匹配率。
   *
   * <p>🔴 <b>按币种分开呈现，绝不跨币种合计。</b>
   */
  public record FinanceMatchRateCurrencyRow(
      String currency,
      long matchedLineCount,
      long unmatchedLineCount,
      long totalLineCount,
      /** 匹配率（0..1）。分母为 0 时为 {@code null}（**不是 0**）。 */
      @Schema(nullable = true) BigDecimal matchRate) {}

  /** 未匹配原因分布的一行（🔴 **按币种**给出，绝不跨币种合计）。 */
  public record FinanceMatchRateReasonRow(
      String currency,
      String reasonType,
      String reasonLabel,
      long lineCount,
      BigDecimal ratio) {}

  /**
   * 匹配率监控视图。
   *
   * <p>🔴 蓝图原话：「<b>匹配率是财务自动化的核心指标</b>，持续低于目标时应排查
   * 上游数据质量而非调规则。」⇒ {@code note} 里写清这条处置口径。
   */
  public record FinanceMatchRateMonitoring(
      /** 对账任务（12.3.2.1）。**空 = 租户级汇总**。 */
      @Schema(nullable = true) UUID taskId,
      String taskNo,
      /** 口径规则（提供匹配率目标的那条）。**空 = 没有启用中的规则**。 */
      @Schema(nullable = true) UUID ruleId,
      String ruleNo,
      List<FinanceMatchRateCurrencyRow> currencies,
      List<FinanceMatchRateReasonRow> reasons,
      long matchedLineCount,
      long unmatchedLineCount,
      long totalLineCount,
      /** 匹配率（0..1）。分母为 0 时为 {@code null}（**不是 0**）。 */
      @Schema(nullable = true) BigDecimal matchRate,
      /** 匹配率目标（百分数）。没有启用中的规则时为 {@code null}。 */
      @Schema(nullable = true) BigDecimal matchRateTarget,
      /** 是否达标。**判不了时为 {@code null}**（不是 false）。 */
      @Schema(nullable = true) Boolean targetMet,
      String note) {}

  // ================================================================ 快照

  /** 一条匹配率快照（一次监控事实）。 */
  public record FinanceMatchRateSnapshotRow(
      UUID id,
      UUID ruleId,
      String ruleNo,
      /** 对账任务（12.3.2.1）。**空 = 租户级汇总**。 */
      @Schema(nullable = true) UUID taskId,
      String currency,
      long matchedLineCount,
      long matchedGroupCount,
      long unmatchedLineCount,
      long totalLineCount,
      /** 匹配率（0..1）。分母为 0 时为 {@code null}（**不是 0**）。 */
      @Schema(nullable = true) BigDecimal matchRate,
      BigDecimal matchRateTarget,
      boolean targetMet,
      /** 规则口径快照（规则后来被改了也不影响历史）。 */
      List<String> matchKeys,
      int minDimensionCount,
      int dateWindowDays,
      BigDecimal amountTolerance,
      BigDecimal feeTolerance,
      BigDecimal exchangeRateTolerance,
      boolean fuzzyEnabled,
      OffsetDateTime computedAt,
      UUID computedBy) {}

  /** 快照页（真分页）。 */
  public record FinanceMatchRateSnapshotPage(
      List<FinanceMatchRateSnapshotRow> items, int page, int size, long total) {}

  /** 算一次快照的入参（规则 id 在路径上）。 */
  public record FinanceMatchRateSnapshotInput(@Schema(nullable = true) UUID taskId) {}
}
