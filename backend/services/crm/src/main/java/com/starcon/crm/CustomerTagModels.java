package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.3.2.1 客户标签的传输模型。
 *
 * <p>蓝图 15 卷 15.3.2.1 原话：
 *
 * <blockquote>
 * 职责：维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），支持自动规则打标（按订单行为）与手工打标，用于筛选与营销。
 * 输入：标签定义；自动打标规则；手工操作。
 * 输出：标签记录 + 标签分布统计。
 * 关系：上游 = 9 订单、11 售后；下游 = 15.3.1.1 筛选、14.4 促销定向、15.5.2 关怀邮件。
 * <b>"退货率高"标签要谨慎使用</b>，避免对正常买家产生歧视性动作。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>「不可判定」是一等状态</b>（{@link CrmCustomerTagEvaluationOutcome#INDETERMINATE}）：
 *       退货率高的判定必须先过**样本量门槛**，样本不足时的结论是
 *       <b>「不可判定」</b>而不是「是」。一个人的一单退货率是 100%，
 *       那不是「退货率高的买家」，那是「样本不足」。</li>
 *   <li><b>来源可分</b>（{@code source} = MANUAL / RULE）：自动重跑<b>只覆盖自己产出的那部分</b>，
 *       <b>绝不覆盖人工标的标签</b>；人工已撤销的那一枚，自动重跑也<b>不恢复</b>。</li>
 *   <li><b>判定依据必须带得出</b>（{@code evidence}）：这次是依据<b>哪几笔订单 / 哪几张售后单</b>
 *       算出来的。运营要能点开看「凭什么给他打这枚标签」，也要能据此纠正。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCustomerTag*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class CustomerTagModels {

  private CustomerTagModels() {}

  // ================================================================ 枚举

  /**
   * 标签分类 —— 照蓝图点名的六类标签归位。
   *
   * <p>VIP / 高客单 → VALUE；复购 → BEHAVIOR；风险 / 退货率高 → RISK；
   * 渠道来源 → SOURCE；其余 → CUSTOM。
   */
  public enum CustomerTagCategory {
    VALUE("价值分层"),
    BEHAVIOR("行为"),
    RISK("风险与售后"),
    SOURCE("渠道来源"),
    CUSTOM("自定义");

    private final String label;

    CustomerTagCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 标签 / 规则状态。 */
  public enum CustomerTagStatus {
    ACTIVE("启用"),
    DISABLED("停用");

    private final String label;

    CustomerTagStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 自动打标的判定口径（五种）。
   *
   * <p>🔴 {@link #RETURN_RATE} 带着两道闸：<b>最低样本量</b>（不足即「不可判定」）
   * 与 <b>必须显著高于租户基线</b>。另外四个口径是「够不够」的判定，天然没有比例，
   * 因此**不带**基线倍率。
   */
  public enum CustomerTagMetric {
    ORDER_COUNT("窗口内订单数达到阈值"),
    REPEAT_PURCHASE("窗口内订单数达到阈值（复购）"),
    HIGH_VALUE("窗口内该币种累计消费达到阈值"),
    RETURN_RATE("退货率显著高于租户基线"),
    CHANNEL_SOURCE("窗口内出现过指定渠道来源");

    private final String label;

    CustomerTagMetric(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 标签记录的来源。
   *
   * <p>🔴 人工与自动**分开记账**：自动重跑只动 RULE 那一类。
   */
  public enum CustomerTagSource {
    MANUAL("人工打标"),
    RULE("规则打标");

    private final String label;

    CustomerTagSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 标签记录状态。REVOKED = 已被人工复核撤销（**保留记录**，不物理删）。 */
  public enum CustomerTagAssignmentState {
    ACTIVE("生效"),
    REVOKED("已撤销");

    private final String label;

    CustomerTagAssignmentState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 逐客户判定结论。
   *
   * <p>🔴 {@link #INDETERMINATE} 与 {@link #NOT_MATCHED} 是**两件不同的事**：
   * 前者是「样本不足，判不了」，后者是「判了，不命中」。
   * 把它们合成一个「否」会让「样本不足」看起来像「这个人没问题」。
   */
  public enum CrmCustomerTagEvaluationOutcome {
    TAGGED("命中并打标"),
    ALREADY_TAGGED("命中且此前已打标（幂等，未重复打）"),
    REVOKED("本次不再命中，已撤销自动标签"),
    SKIPPED_REVOKED("本次仍命中，但人工已撤销，未恢复"),
    INDETERMINATE("不可判定（样本不足）"),
    NOT_MATCHED("判定为不命中");

    private final String label;

    CrmCustomerTagEvaluationOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 规则运行状态。FAILED 一律带原因（上游读不到 / 中途出错）。 */
  public enum CustomerTagRunStatus {
    SUCCEEDED("已完成"),
    FAILED("失败");

    private final String label;

    CustomerTagRunStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 人工复核的两种决定。 */
  public enum CustomerTagReviewDecision {
    /** 确认这枚标签是对的（pending_review 清掉）。 */
    CONFIRM("确认"),
    /** 撤销这枚标签（state → REVOKED，必须写原因）。 */
    REVOKE("撤销");

    private final String label;

    CustomerTagReviewDecision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 标签定义入参。
   *
   * <p>🔴 {@code contestable} / {@code reviewRequired} 是加粗句
   * 「"退货率高"标签要谨慎使用」在**配置层**的开关：勾上之后，
   * 打这枚标签时**必须**带原因（DB 触发器强制），并进入待复核。
   */
  public record CrmCustomerTagInput(
      @NotBlank @Size(max = 64) String tagCode,
      @NotBlank @Size(max = 120) String name,
      @NotBlank String category,
      @Size(max = 600) String description,
      @Size(max = 32) String color,
      String status,
      Boolean contestable,
      Boolean reviewRequired) {}

  /**
   * 自动打标规则入参。
   *
   * <p>⚠️ 阈值口径的互斥性由**服务层 + DB CHECK** 双重保证：
   * 数值型口径必须给 {@code threshold}，字符串型口径（CHANNEL_SOURCE）必须给
   * {@code conditionValue} 且**不得**给阈值 —— 给了就是两套条件，得说清哪套说了算。
   */
  public record CrmCustomerTagRuleInput(
      @NotBlank @Size(max = 64) String ruleCode,
      @NotBlank @Size(max = 120) String name,
      @NotNull UUID tagId,
      @NotBlank String metric,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal threshold,
      @Schema(nullable = true) @Size(max = 16) String currency,
      @Schema(nullable = true) @Size(max = 120) String conditionValue,
      @DecimalMin(value = "1") BigDecimal minSample,
      @DecimalMin(value = "1") BigDecimal windowDays,
      @DecimalMin(value = "1.000001") BigDecimal baselineMultiplier,
      String status,
      @Size(max = 600) String note) {}

  /**
   * 手工打标入参。
   *
   * <p>⚠️ {@code reason} 对**可争议标签**（退货率高 / 风险）是**必填**的 ——
   * DB 触发器 {@code guard_crm_customer_tag_assignment_reason} 也会拒空原因。
   * 「他退了 3 次货」是事实，**「他是个退货率高的买家」是判断** ——
   * 前者可以由事实自动得出，后者必须有人负责。
   */
  public record CrmCustomerTagAssignmentInput(
      @NotBlank @Size(max = 128) String customerRef,
      @Schema(nullable = true) @Size(max = 200) String customerName,
      @NotNull UUID tagId,
      @Schema(nullable = true) @Size(max = 600) String reason) {}

  /**
   * 人工复核入参。
   *
   * <p>🔴 {@code REVOKE} 时 {@code reason} **必填**：撤销一条「退货率高」的标签，
   * 必须说清「凭什么撤销」—— 否则这次纠正就变成了另一次没有依据的判断。
   */
  public record CrmCustomerTagReviewInput(
      @NotBlank String decision,
      @Schema(nullable = true) @Size(max = 600) String reason) {}

  /**
   * 跑一次规则的入参。
   *
   * <p>⚠️ 时间窗**留空即用规则自己的窗口天数**（从「现在」往回推）。
   * 给了就必须成对给（起止都要），单给一个会让窗口的两端对不上。
   */
  public record CrmCustomerTagRunInput(
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo) {}

  // ================================================================ 出参

  /**
   * 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>字段名与兄弟模块一致：{@code dimension / description / available / source /
   * ownerModule / impact}。
   */
  public record CrmCustomerTagCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * @param metricNote 判定口径的说明（含「不可判定」的含义与基线口径）
   * @param actionPolicy 🔴 明确声明「标签不触发任何对买家的动作」
   */
  public record CrmCustomerTagCapabilities(
      List<CrmCustomerTagCapabilityDimension> dimensions,
      List<String> categories,
      List<String> metrics,
      List<String> sources,
      List<String> assignmentStates,
      List<String> evaluationOutcomes,
      String metricNote,
      String actionPolicy,
      String boundaryNote,
      String highlight) {}

  /** 一枚标签定义。 */
  public record CrmCustomerTagRow(
      UUID id,
      String tagCode,
      String name,
      String category,
      String categoryLabel,
      String description,
      String color,
      String status,
      boolean contestable,
      boolean reviewRequired,
      UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 标签定义分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmCustomerTagPage(
      List<CrmCustomerTagRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一条自动打标规则。 */
  public record CrmCustomerTagRuleRow(
      UUID id,
      String ruleCode,
      String name,
      UUID tagId,
      String tagCode,
      String tagName,
      String metric,
      String metricLabel,
      BigDecimal threshold,
      String currency,
      String conditionValue,
      BigDecimal minSample,
      BigDecimal windowDays,
      BigDecimal baselineMultiplier,
      String status,
      String note,
      @Schema(nullable = true) UUID lastRunId,
      @Schema(nullable = true) String lastRunNo,
      @Schema(nullable = true) String lastRunStatus,
      @Schema(nullable = true) String lastRunAt,
      UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 规则分页（真分页）。 */
  public record CrmCustomerTagRulePage(
      List<CrmCustomerTagRuleRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条标签记录（客户 × 标签）。
   *
   * @param evidence 判定依据快照：依据哪几笔订单 / 哪几张售后单算出来的
   */
  public record CrmCustomerTagAssignmentRow(
      UUID id,
      String customerRef,
      String customerName,
      UUID tagId,
      String tagCode,
      String tagName,
      String tagCategory,
      boolean contestable,
      String source,
      String sourceLabel,
      String originKey,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String ruleCode,
      String state,
      String stateLabel,
      String reason,
      Map<String, Object> evidence,
      boolean pendingReview,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) String decidedAt,
      UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 标签记录分页（真分页）。 */
  public record CrmCustomerTagAssignmentPage(
      List<CrmCustomerTagAssignmentRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** 一次规则评估的运行记录。 */
  public record CrmCustomerTagRunRow(
      UUID id,
      String runNo,
      UUID ruleId,
      String ruleCode,
      String status,
      String statusLabel,
      String windowFrom,
      String windowTo,
      int customerCount,
      int taggedCount,
      int unchangedCount,
      int revokedCount,
      int indeterminateCount,
      int revokedSkippedCount,
      boolean truncated,
      @Schema(nullable = true) BigDecimal baseline,
      Map<String, Object> facts,
      String failureReason,
      @Schema(nullable = true) UUID operatorId,
      String startedAt,
      String finishedAt) {}

  /** 运行记录分页（真分页）。 */
  public record CrmCustomerTagRunPage(
      List<CrmCustomerTagRunRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 逐客户判定结论。 */
  public record CrmCustomerTagEvaluationRow(
      UUID id,
      UUID runId,
      String customerRef,
      String customerName,
      String outcome,
      String outcomeLabel,
      String reason,
      Map<String, Object> evidence,
      String createdAt) {}

  /** 判定结论分页（真分页）。 */
  public record CrmCustomerTagEvaluationPage(
      List<CrmCustomerTagEvaluationRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /**
   * 一枚标签的分布统计。
   *
   * @param share 占全部「被打过标签的客户」的比重（0..1，**服务端算**，前端只原样展示）
   * @param sharePercent 同一个比重的百分数（0..100，**服务端算**）——
   *     之所以多给一个，是因为「占比 × 100」属于前端算术，
   *     而本仓的纪律是金额与数量在 JS 侧**只原样展示**。
   * @param activeCustomerCount 持有这枚**生效**标签的客户数（去重）
   */
  public record CrmCustomerTagDistributionRow(
      UUID tagId,
      String tagCode,
      String tagName,
      String category,
      String categoryLabel,
      boolean contestable,
      int activeCustomerCount,
      int manualCustomerCount,
      int ruleCustomerCount,
      int pendingReviewCount,
      int revokedCount,
      @Schema(nullable = true) BigDecimal share,
      @Schema(nullable = true) BigDecimal sharePercent) {}

  /**
   * 标签分布统计（蓝图输出句的第二项）。
   *
   * @param totalTaggedCustomers 被打过**任意**标签的客户数（分母）
   * @param totalActiveAssignments 生效中的标签记录条数（客户 × 标签，人工 + 自动）
   * @param note 口径说明（分母是什么、金额为何不跨币种）
   */
  public record CrmCustomerTagDistribution(
      int totalTaggedCustomers,
      int totalActiveAssignments,
      List<CrmCustomerTagDistributionRow> rows,
      String note) {}

  /**
   * 一位候选客户（由上游订单事实聚合而来）。
   *
   * @param amountsByCurrency 累计消费**按币种分开**：USD 与 EUR 加起来不是任何一个金额
   */
  public record CrmCustomerTagCustomerCandidate(
      String customerRef,
      String customerName,
      int orderCount,
      Map<String, BigDecimal> amountsByCurrency,
      List<String> channelSources,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      int taggedCount) {}

  /**
   * 候选客户分页。
   *
   * @param truncated 🔴 上游扫描**被截断**（扫到上限了）—— 静默少算比报错更糟，必须报出来
   * @param scannedOrderCount 本次实际读到的订单条数
   * @param scannedCaseCount 本次实际读到的售后单条数
   * @param unattributedCaseCount 读到但**归不到客户**的售后单条数（其订单不在扫描窗口内）
   */
  public record CrmCustomerTagCustomerCandidatePage(
      List<CrmCustomerTagCustomerCandidate> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      boolean truncated,
      int scannedOrderCount,
      int scannedCaseCount,
      int unattributedCaseCount,
      String note) {}
}
