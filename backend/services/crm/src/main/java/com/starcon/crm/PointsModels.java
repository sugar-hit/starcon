package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * 15.6.1.2 积分与权益的传输模型。
 *
 * <p>蓝图 15 卷 15.6.1.2 原话：
 *
 * <blockquote>
 * 职责：管理积分与权益：积分获取（消费/评价/签到）与消耗（抵扣/兑换）、积分余额与有效期、权益核销；积分负债需财务确认。
 * 输入：积分规则；获取/消耗事件；权益核销。
 * 输出：积分账户与流水；权益核销记录；积分负债汇总（→ 12）。
 * 关系：上游 = 15.6.1.1、9 订单；下游 = 5 定价（积分抵扣）、12 财务（积分负债）。**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>积分比率是租户数据</b>：拿多少分、抵多少比例、单积分值多少，
 *       全部落在 {@code crm_points_rule} —— 本模块<b>没有</b>任何写死的积分比率。</li>
 *   <li><b>负债如实标注</b>：{@link CrmPointsLiability#bookedInFinance()} 恒为 {@code false} ——
 *       12 财务未交付积分负债科目，本模块<b>只输出汇总</b>，<b>不假装</b>已经入账。</li>
 *   <li><b>余额与流水都可审阅</b>：流水行带「扣前余额 → 扣后余额」；
 *       出参的积分与金额一律是<b>十进制文本</b>。</li>
 *   <li><b>不跨币种算</b>：抵扣上限要拿订单金额乘抵扣率，订单币种与积分估值币种
 *       不一致时<b>直接拒绝</b>，而不是偷偷按一个汇率折算。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmPoints*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 *
 * <p><b>🔴 十进制一律走文本</b>（与仓里 1.3.2.1 预警阈值、15.6.1.1 会员等级同一口径）：
 * 出参里的积分与金额是 <b>{@code String}</b>（十进制文本），不是 JSON number。
 * 理由不是洁癖，是 JSON number <b>装不下</b>「显示标度」——
 * {@code 300.0000} 走 JSON number 会被解析成 {@code 3E+2}，
 * 前端拿到的是「数值」而不是「那个数的十进制写法」；而积分抵扣配置要交给
 * <b>5 定价</b>用，{@code 3E+2} 这种写法会一路传下去。
 * 入参仍是 {@link BigDecimal}（Jackson 按 token 原文解析，<b>不经过 double</b>）。
 */
public final class PointsModels {

  private PointsModels() {}

  // ================================================================ 枚举

  /** 积分规则类型。 */
  public enum PointsRuleType {
    EARN("获取规则"),
    SPEND("消耗规则（抵扣）"),
    VALUATION("估值规则（单积分价值 / 负债口径）");

    private final String label;

    PointsRuleType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 积分获取来源（蓝图点名的三种 + 人工调整）。 */
  public enum PointsEarnSource {
    PURCHASE("消费获取"),
    REVIEW("评价获取"),
    CHECKIN("签到获取"),
    MANUAL("人工发放");

    private final String label;

    PointsEarnSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 积分流水方向。 */
  public enum PointsDirection {
    EARN("获取"),
    SPEND("消耗"),
    EXPIRE("过期");

    private final String label;

    PointsDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 积分流水事由。 */
  public enum PointsReasonCode {
    PURCHASE("消费获取"),
    REVIEW("评价获取"),
    CHECKIN("签到获取"),
    MANUAL("人工发放"),
    DEDUCT("订单抵扣"),
    REDEEM("权益兑换"),
    EXPIRE("到期过期");

    private final String label;

    PointsReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 权益形态。 */
  public enum PointsBenefitType {
    DISCOUNT("折扣"),
    FREE_SHIPPING("免邮"),
    PHYSICAL_GIFT("实物"),
    COUPON("优惠券");

    private final String label;

    PointsBenefitType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 权益核销状态（**单向**：REDEEMED → USED）。 */
  public enum PointsRedemptionStatus {
    REDEEMED("已兑换待核销"),
    USED("已核销");

    private final String label;

    PointsRedemptionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 积分获取的入参。
   *
   * @param memberKey 会员主键（15.6.1.1 的 member_key）—— 积分挂在**真实会员**身份上
   * @param earnSource PURCHASE 消费 / REVIEW 评价 / CHECKIN 签到 / MANUAL 人工
   * @param referenceId 外部事实引用：PURCHASE = 订单 id；REVIEW = 评价号；其余留空
   * @param ruleCode 指定用哪条获取规则（留空 = 按来源取当前生效的那条）
   * @param points 仅 MANUAL 用：人工指定的积分数；其余来源由规则算出来
   * @param occurredAt 事件发生时刻（ISO-8601 带偏移量）；留空 = 现在
   *
   * <p>🔴 请求体**不接受** tenantId / userId：租户来自已认证成员关系。
   */
  public record CrmPointsEarnInput(
      @NotBlank @Size(max = 160) String memberKey,
      @NotBlank String earnSource,
      @Schema(nullable = true) @Size(max = 128) String referenceId,
      @Schema(nullable = true) @Size(max = 48) String ruleCode,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal points,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /**
   * 积分消耗（订单抵扣）的入参。
   *
   * @param memberKey 会员主键
   * @param orderId 用于抵扣的订单 id（**上游 trade 的事实**，模块会回源核对）
   * @param points 请求抵扣的积分数；留空 = 用满额（抵扣率上限）
   */
  public record CrmPointsSpendInput(
      @NotBlank @Size(max = 160) String memberKey,
      @NotBlank @Size(max = 128) String orderId,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal points,
      @Schema(nullable = true) @Size(max = 48) String ruleCode,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /** 权益兑换的入参。 */
  public record CrmPointsRedeemInput(
      @NotBlank @Size(max = 160) String memberKey,
      @NotBlank @Size(max = 48) String entitlementCode,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /** 权益核销（把已兑换的权益标成已核销）的入参。 */
  public record CrmPointsUseInput(
      @Schema(nullable = true) @Size(max = 64) String orderRef,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /**
   * 积分规则的写入入参。
   *
   * @param ruleType EARN 获取 / SPEND 消耗 / VALUATION 估值
   * @param pointsPerUnit 每「一单位金额」换多少积分（仅 PURCHASE 用）
   * @param fixedPoints 固定积分（REVIEW / CHECKIN / MANUAL 用）
   * @param thresholdAmount 消费门槛（仅 PURCHASE 用）；**必须**同时给币种
   * @param validMonths 积分有效期（月数）；留空 = 永不过期
   * @param maxPerDay 同一会员同一自然日（UTC）最多拿多少分
   * @param maxDeductRate 抵扣率上限（0.05 = 订单金额最多抵掉 5%）
   * @param pointValueRate 每 1 积分折算多少金额（VALUATION 用）
   * @param liabilityCurrency 负债币种（VALUATION 用，与 pointValueRate 同生共死）
   */
  public record CrmPointsRuleInput(
      @NotBlank @Size(max = 48) String ruleCode,
      @NotBlank @Size(max = 96) String ruleName,
      @NotBlank String ruleType,
      @Schema(nullable = true) String earnSource,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal pointsPerUnit,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal fixedPoints,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal thresholdAmount,
      @Schema(nullable = true) @Pattern(regexp = "[A-Za-z]{3}") String thresholdCurrency,
      @Schema(nullable = true) @Min(1) @Max(1200) Integer validMonths,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal maxPerDay,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("1") BigDecimal maxDeductRate,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal minDeductPoints,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal pointValueRate,
      @Schema(nullable = true) @Pattern(regexp = "[A-Za-z]{3}") String liabilityCurrency,
      @Schema(nullable = true) Boolean enabled,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /** 权益目录的写入入参。 */
  public record CrmPointsEntitlementInput(
      @NotBlank @Size(max = 48) String entitlementCode,
      @NotBlank @Size(max = 96) String entitlementName,
      @NotNull @DecimalMin("0") BigDecimal pointCost,
      @NotBlank String benefitType,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal benefitValue,
      @Schema(nullable = true) @Pattern(regexp = "[A-Za-z]{3}") String benefitCurrency,
      @Schema(nullable = true) @Min(0) Integer stock,
      @Schema(nullable = true) @Min(1) Integer perMemberLimit,
      @Schema(nullable = true) Boolean enabled,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  // ================================================================ 出参

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmPointsCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  /** 能力声明：前端在渲染前就知道哪些维度有、哪些没有。 */
  public record CrmPointsCapabilities(
      List<CrmPointsCapabilityDimension> dimensions,
      List<String> ruleTypes,
      List<String> earnSources,
      List<String> directions,
      String pointsPolicy,
      String liabilityPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 积分账户的一行。
   *
   * @param balance 当前可用积分，**十进制文本**（如 {@code 300}）
   * @param earnedTotal / spentTotal / expiredTotal 累计口径，**十进制文本**
   */
  public record CrmPointsAccountRow(
      UUID id,
      String accountKey,
      String memberKey,
      String customerRef,
      String sourceShop,
      String customerName,
      String balance,
      String earnedTotal,
      String spentTotal,
      String expiredTotal,
      @Schema(nullable = true) String lastEarnedAt,
      @Schema(nullable = true) String lastSpentAt,
      String createdAt) {}

  /**
   * 积分批次的一行 —— 有效期挂在它身上。
   *
   * @param amount 本批获取的积分，**十进制文本**
   * @param remaining 剩余可消耗积分，**十进制文本**
   * @param expiresAt 过期时刻（**绝对时刻**，统一换算成 UTC）；null = 永不过期
   * @param expired 该批次此刻是否已过期（按数据库时钟判定）
   */
  public record CrmPointsLotRow(
      UUID id,
      String accountKey,
      String amount,
      String remaining,
      @Schema(nullable = true) String expiresAt,
      boolean expired,
      String createdAt) {}

  /**
   * 积分流水的一行 —— **只追加**。
   *
   * @param amount 变动数量（恒为正），**十进制文本**
   * @param balanceBefore 变动前余额，**十进制文本**
   * @param balanceAfter 变动后余额，**十进制文本**
   */
  public record CrmPointsLedgerRow(
      UUID id,
      String accountKey,
      String memberKey,
      String customerName,
      String direction,
      String directionLabel,
      String amount,
      String balanceBefore,
      String balanceAfter,
      @Schema(nullable = true) String ruleCode,
      String reasonCode,
      String reasonLabel,
      @Schema(nullable = true) String referenceType,
      @Schema(nullable = true) String referenceId,
      String occurredAt,
      @Schema(nullable = true) String operatorId,
      String note) {}

  /** 积分账户详情 = 账户 + 批次 + 最近流水。 */
  public record CrmPointsAccountDetail(
      CrmPointsAccountRow account, List<CrmPointsLotRow> lots, List<CrmPointsLedgerRow> recentLedger) {}

  /**
   * 积分统计。
   *
   * <p>🔴 **与分页无关**：这些数字来自**单独一条**统计查询（不是拿当前页聚合出来的）。
   */
  public record CrmPointsStats(
      long totalAccounts,
      long accountsWithBalance,
      String outstandingPoints,
      long expiringLotCount,
      String expiringPoints,
      long ledgerCount,
      String note) {}

  /** 积分账户列表页：真分页（LIMIT/OFFSET + 稳定全序排序）+ 与分页无关的统计。 */
  public record CrmPointsAccountPage(
      List<CrmPointsAccountRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      CrmPointsStats stats,
      String dataScope,
      String dataScopeNote) {}

  /** 积分流水列表页（真分页）。 */
  public record CrmPointsLedgerPage(
      List<CrmPointsLedgerRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 积分规则的一行。
   *
   * @param pointsPerUnit 每单位金额换多少积分，**十进制文本**（如 {@code 1.000000}）
   * @param fixedPoints 固定积分，**十进制文本**
   * @param thresholdAmount 消费门槛，**十进制文本**
   * @param maxDeductRate 抵扣率上限，**十进制文本**（如 {@code 0.050000}）
   * @param pointValueRate 单积分价值，**十进制文本**
   * @param effectiveNow 该规则此刻是否生效（**按数据库的 now() 判定**）
   */
  public record CrmPointsRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String ruleType,
      @Schema(nullable = true) String earnSource,
      @Schema(nullable = true) String pointsPerUnit,
      @Schema(nullable = true) String fixedPoints,
      @Schema(nullable = true) String thresholdAmount,
      @Schema(nullable = true) String thresholdCurrency,
      @Schema(nullable = true) Integer validMonths,
      @Schema(nullable = true) String maxPerDay,
      @Schema(nullable = true) String maxDeductRate,
      @Schema(nullable = true) String minDeductPoints,
      @Schema(nullable = true) String pointValueRate,
      @Schema(nullable = true) String liabilityCurrency,
      boolean enabled,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean effectiveNow,
      String note,
      String createdAt) {}

  /** 积分规则列表页（真分页）。 */
  public record CrmPointsRulePage(List<CrmPointsRuleRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 权益目录的一行。
   *
   * @param pointCost 兑换所需积分，**十进制文本**
   * @param benefitValue 权益内容（折扣率 / 面额），**十进制文本**
   */
  public record CrmPointsEntitlementRow(
      UUID id,
      String entitlementCode,
      String entitlementName,
      String pointCost,
      String benefitType,
      String benefitTypeLabel,
      @Schema(nullable = true) String benefitValue,
      @Schema(nullable = true) String benefitCurrency,
      @Schema(nullable = true) Integer stock,
      @Schema(nullable = true) Integer perMemberLimit,
      boolean enabled,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean effectiveNow,
      String note,
      String createdAt) {}

  /** 权益目录列表页（真分页）。 */
  public record CrmPointsEntitlementPage(
      List<CrmPointsEntitlementRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 权益核销记录的一行。
   *
   * @param status REDEEMED 已兑换待核销 / USED 已核销
   * @param benefitSnapshot 兑换当时的权益内容快照
   */
  public record CrmPointsRedemptionRow(
      UUID id,
      String redemptionNo,
      String accountKey,
      String memberKey,
      String entitlementCode,
      String entitlementName,
      String pointCost,
      Map<String, Object> benefitSnapshot,
      String status,
      String statusLabel,
      String redeemedAt,
      @Schema(nullable = true) String usedAt,
      @Schema(nullable = true) String orderRef,
      String note) {}

  /** 权益核销记录列表页（真分页）。 */
  public record CrmPointsRedemptionPage(
      List<CrmPointsRedemptionRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 积分获取的结果。
   *
   * @param points 本次获得的积分，**十进制文本**
   * @param balanceBefore / balanceAfter 变动前后的余额，**十进制文本**
   */
  public record CrmPointsEarnResult(
      UUID accountId,
      String accountKey,
      String memberKey,
      String points,
      String balanceBefore,
      String balanceAfter,
      @Schema(nullable = true) String expiresAt,
      @Schema(nullable = true) String ruleCode,
      String note) {}

  /**
   * 积分消耗（抵扣）的结果。
   *
   * @param points 本次消耗的积分，**十进制文本**
   * @param maxDeductPoints 该订单允许抵扣的积分上限，**十进制文本**
   */
  public record CrmPointsSpendResult(
      UUID accountId,
      String accountKey,
      String memberKey,
      String points,
      String balanceBefore,
      String balanceAfter,
      @Schema(nullable = true) String orderId,
      @Schema(nullable = true) String maxDeductPoints,
      String note) {}

  /** 权益兑换的结果。 */
  public record CrmPointsRedeemResult(
      UUID redemptionId,
      String redemptionNo,
      UUID accountId,
      String accountKey,
      String memberKey,
      String entitlementCode,
      String entitlementName,
      String pointCost,
      String balanceBefore,
      String balanceAfter,
      Map<String, Object> benefitSnapshot,
      String note) {}

  /** 到期结算的结果。 */
  public record CrmPointsExpireResult(
      int expiredLotCount, String expiredPoints, String totalExpiredPoints, String note) {}

  /**
   * 积分负债汇总 —— **这是交给 12 财务的东西**。
   *
   * @param outstandingPoints 未兑付积分（Σ 账户余额），**十进制文本**
   * @param liabilityAmount 未兑付积分折算的负债金额，**十进制文本**；
   *     🔴 **可空**：还没有配置积分估值规则时**算不出来**（那是 null，不是 0）
   * @param bookedInFinance 🔴 恒为 {@code false} —— 12 财务侧未交付积分负债科目，
   *     本模块<b>只输出汇总</b>，<b>不代劳</b>建科目、<b>不假装</b>已经入账
   * @param financeNote 财务侧现状的一句话说明
   */
  public record CrmPointsLiability(
      String asOf,
      @Schema(nullable = true) String pointValueRate,
      @Schema(nullable = true) String liabilityCurrency,
      String outstandingPoints,
      @Schema(nullable = true) String liabilityAmount,
      long accountsWithBalance,
      String expiringPoints,
      @Schema(nullable = true) String expiringWithin,
      boolean bookedInFinance,
      String financeNote,
      String note) {}
}
