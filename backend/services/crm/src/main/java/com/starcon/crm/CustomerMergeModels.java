package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.3.1.3 跨店合并的传输模型。
 *
 * <p>蓝图 15 卷 15.3.1.3 原话：
 *
 * <blockquote>
 * 职责：把同一自然人在不同店铺的身份合并到一个逻辑客户，支持按邮箱/电话/姓名+地址/平台买家标识匹配，给出置信度并需人工确认，保留合并前映射可拆分。
 * 输入：候选匹配（自动建议）；人工确认；拆分请求。
 * 输出：合并后的逻辑客户 + 映射关系 + 操作记录。
 * 关系：上游 = 15.3.1.1；下游 = 15.3.1.2、13 分析（客户价值跨店汇总）。**自动合并风险高**（同名不同人、同人不同邮箱），因此只给建议不自动执行，且必须可拆分。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>置信度要能解释</b>：置信度不是一个神秘分数 ——
 *       {@link CrmCustomerMergeSignal} 逐条列出命中了哪个信号、证据强度多少、
 *       比对的是什么（脱敏形态）。</li>
 *   <li><b>只给建议</b>：候选的 {@code status} 初值是 {@code SUGGESTED}，
 *       变成 {@code CONFIRMED} 只能由人工端点写入。</li>
 *   <li><b>必须可拆分</b>：拆分不是删一行，是把映射从 ACTIVE 变成 SPLIT；
 *       {@link CrmCustomerMergeMember} 里能看到被拆走的成员仍留在历史里。</li>
 *   <li><b>金额按币种分开</b>：{@link CrmCustomerMergeAmount} 一行一个币种，
 *       跨币种**不可相加**（照抄 15.3.1.1 决策 5）。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCustomerMerge*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class CustomerMergeModels {

  private CustomerMergeModels() {}

  // ================================================================ 枚举

  /**
   * 匹配信号类型 —— 证据强度**不同**，不能揉成一个分数。
   *
   * <p>蓝图点名四种依据：邮箱 / 电话 / 姓名+地址 / 平台买家标识。
   * 这里的顺序就是强度顺序：
   *
   * <ul>
   *   <li>{@link #PLATFORM_BUYER_ID} —— 平台买家标识（上游订单里的 customer_party_id）相同。
   *       同一个平台买家在两个店铺下的两行，**最强**。</li>
   *   <li>{@link #EMAIL} —— 邮箱相同。</li>
   *   <li>{@link #PHONE} —— 电话相同（比邮箱弱：家庭号 / 公司号会被多个人共用）。</li>
   *   <li>{@link #NAME_ADDRESS} —— 姓名相同**且**地址相同。
   *       只有姓名相同（{@code NAME_ONLY}）**不产出候选** —— 那正是「同名不同人」。</li>
 * </ul>
   */
  public enum CustomerMergeMatchType {
    PLATFORM_BUYER_ID("平台买家标识相同", new BigDecimal("0.95")),
    EMAIL("邮箱相同", new BigDecimal("0.85")),
    PHONE("电话相同", new BigDecimal("0.75")),
    NAME_ADDRESS("姓名与地址都相同", new BigDecimal("0.60"));

    private final String label;
    private final BigDecimal weight;

    CustomerMergeMatchType(String label, BigDecimal weight) {
      this.label = label;
      this.weight = weight;
    }

    public String label() {
      return label;
    }

    /**
     * 基础权重（0..1）。
     *
     * <p>🔴 <b>BigDecimal 而不是 double</b>：置信度是「给运营看的判定依据」，
     * 用 double 会出现 {@code 0.85 + 0.05 * 2 == 0.9500000000000001} 这种值，
     * 而它要落进 {@code numeric(5,4)} —— 舍入点上的偏差会变成「这一对到底是 0.95 还是 0.9499」的争议。
     * 全程 BigDecimal ⇒ 落库值与展示值逐位相同。
     *
     * <p>最终置信度 = 最强命中信号的权重 + 0.05 ×（其余命中信号个数），上限 0.99。
     */
    public BigDecimal weight() {
      return weight;
    }
  }

  /** 候选状态：SUGGESTED 待确认 / CONFIRMED 已合并 / REJECTED 已拒绝 / STALE 已失效。 */
  public enum CustomerMergeCandidateStatus {
    SUGGESTED("待确认"),
    CONFIRMED("已合并"),
    REJECTED("已拒绝"),
    STALE("已失效");

    private final String label;

    CustomerMergeCandidateStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 操作类型：MERGE 合并 / SPLIT 拆分 / REJECT 拒绝候选。
   *
   * <p>🔴 REJECT 也在流水里：拒绝**也是**一个人的决定 ——
   * 事后要能回答「为什么不是同一个人」。
   */
  public enum CustomerMergeOperationType {
    MERGE("合并"),
    SPLIT("拆分"),
    REJECT("拒绝候选");

    private final String label;

    CustomerMergeOperationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射状态：ACTIVE 生效中 / SPLIT 已拆分。 */
  public enum CustomerMergeMappingState {
    ACTIVE("生效中"),
    SPLIT("已拆分");

    private final String label;

    CustomerMergeMappingState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 生成候选的入参。
   *
   * @param maxCustomers 本轮最多扫描多少位来源客户（上限由服务端钳住）
   */
  public record CrmCustomerMergeScanInput(
      @Schema(nullable = true) Integer maxCustomers,
      @Schema(nullable = true) Boolean force) {}

  /**
   * 人工确认合并。
   *
   * @param reason 合并理由 —— **必填**。合并是把两个身份合成一个人的决定，
   *     必须能回答「凭什么合并」（人工看过哪几个信号）。
   * @param logicalCustomerId 合并进**已有的**逻辑客户；不给则新建一个。
   */
  public record CrmCustomerMergeConfirmInput(
      @NotBlank @Size(max = 600) String reason,
      @Schema(nullable = true) UUID logicalCustomerId) {}

  /** 拒绝候选 —— 理由必填（拒绝也是决定，事后要能回答「为什么不是同一个人」）。 */
  public record CrmCustomerMergeRejectInput(@NotBlank @Size(max = 600) String reason) {}

  /**
   * 拆分请求：把某一个成员从逻辑客户里拆出去。
   *
   * @param customerProfileId 被拆出的**来源客户行** id（15.3.1.1 的行 id）
   * @param reason 拆分理由 —— **必填**
   */
  public record CrmCustomerMergeSplitInput(
      @NotNull UUID customerProfileId, @NotBlank @Size(max = 600) String reason) {}

  // ================================================================ 出参

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmCustomerMergeCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  /** 能力声明。 */
  public record CrmCustomerMergeCapabilities(
      List<CrmCustomerMergeCapabilityDimension> dimensions,
      List<String> matchTypes,
      String signalPolicy,
      String confirmationPolicy,
      String splitPolicy,
      String maskingPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 一条命中信号 —— 置信度的**解释**，不是一个神秘分数。
   *
   * @param signalType 信号类型（EMAIL / PHONE / NAME_ADDRESS / PLATFORM_BUYER_ID）
   * @param matched 是否命中
   * @param strength 该信号的证据强度（0..1）
   * @param maskedValue 比对值的**脱敏**形态（a***@***.com / 138****1234）
   * @param detail 人话说明（为什么不命中也写清楚）
   */
  public record CrmCustomerMergeSignal(
      String signalType,
      String label,
      boolean matched,
      BigDecimal strength,
      String maskedValue,
      String detail) {}

  /**
   * 候选匹配的一行。
   *
   * @param confidence 置信度（0.60 ~ 0.99），**可解释**：见 {@link #signals}
   * @param optOutInvolved 一侧存在生效中的退订 ⇒ 合并会影响所有来源的触达
   */
  public record CrmCustomerMergeCandidateRow(
      UUID id,
      String status,
      String statusLabel,
      String matchType,
      String matchTypeLabel,
      BigDecimal confidence,
      String signalSummary,
      List<CrmCustomerMergeSignal> signals,
      List<String> exclusionFlags,
      boolean optOutInvolved,
      String leftCustomerRef,
      String leftSourceShop,
      String leftCustomerName,
      String rightCustomerRef,
      String rightSourceShop,
      String rightCustomerName,
      @Schema(nullable = true) UUID logicalCustomerId,
      @Schema(nullable = true) String logicalRef,
      @Schema(nullable = true) String reviewedBy,
      @Schema(nullable = true) String reviewedAt,
      String reviewNote,
      String detectedAt) {}

  /** 候选列表页：真分页 + 真筛选。 */
  public record CrmCustomerMergeCandidatePage(
      List<CrmCustomerMergeCandidateRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      long suggestedCount,
      long confirmedCount,
      long rejectedCount,
      String note) {}

  /**
   * 候选详情：在列表那一行之上补上**证据全文**与两侧的联系方式口径。
   *
   * @param revealContact 是否已授权并显式要求明文
   */
  public record CrmCustomerMergeCandidateDetail(
      CrmCustomerMergeCandidateRow candidate,
      List<CrmCustomerMergeSignal> signals,
      Map<String, Object> evidence,
      String leftEmail,
      String leftPhone,
      String rightEmail,
      String rightPhone,
      boolean contactMasked,
      String note) {}

  /** 逻辑客户的一行（合并后的那个「人」）。 */
  public record CrmCustomerMergeLogicalRow(
      UUID id,
      String logicalRef,
      String displayName,
      int memberCount,
      int sourceShopCount,
      int totalOrderCount,
      String optOutState,
      String optOutStateLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      List<String> sourceShops,
      String createdAt) {}

  /** 逻辑客户列表页：真分页。 */
  public record CrmCustomerMergeLogicalPage(
      List<CrmCustomerMergeLogicalRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      long mergedCustomerCount,
      long splitCustomerCount,
      String note) {}

  /**
   * 逻辑客户的一个成员（映射行）。
   *
   * @param state ACTIVE 生效中 / SPLIT 已拆分（**历史行仍然在**，这正是「可拆分」的凭据）
   */
  public record CrmCustomerMergeMember(
      UUID mappingId,
      UUID customerProfileId,
      String customerRef,
      String sourceShop,
      String customerName,
      String role,
      String state,
      String stateLabel,
      @Schema(nullable = true) String mergedAt,
      @Schema(nullable = true) String mergedBy,
      @Schema(nullable = true) String splitAt,
      @Schema(nullable = true) String splitBy,
      String splitReason) {}

  /**
   * 逻辑客户在某个币种上的累计消费 —— **一币种一行，跨币种不可相加**。
   *
   * @param avgOrderAmount 客单价 = 该币种累计消费 ÷ 该币种订单数；分母为 0 时为 null（不可判定）
   */
  public record CrmCustomerMergeAmount(
      String currency, BigDecimal totalAmount, int orderCount, @Schema(nullable = true) BigDecimal avgOrderAmount) {}

  /** 逻辑客户详情：成员 + 按币种分开的金额 + 操作记录。 */
  public record CrmCustomerMergeLogicalDetail(
      CrmCustomerMergeLogicalRow logical,
      List<CrmCustomerMergeMember> members,
      List<CrmCustomerMergeAmount> amounts,
      List<CrmCustomerMergeOperationRow> operations,
      String note) {}

  /** 一次合并 / 拆分的操作记录。 */
  public record CrmCustomerMergeOperationRow(
      UUID id,
      String operation,
      String operationLabel,
      @Schema(nullable = true) UUID logicalCustomerId,
      @Schema(nullable = true) String logicalRef,
      @Schema(nullable = true) UUID candidateId,
      @Schema(nullable = true) UUID targetCustomerId,
      int memberCount,
      String reason,
      @Schema(nullable = true) String operatorId,
      String occurredAt,
      Map<String, Object> details) {}

  /** 操作记录列表页：真分页。 */
  public record CrmCustomerMergeOperationPage(
      List<CrmCustomerMergeOperationRow> rows, int page, int size, long total, int totalPages) {}

  /** 一次候选生成运行的记录。 */
  public record CrmCustomerMergeRunRow(
      UUID id,
      String runNo,
      String status,
      int scannedCustomerCount,
      int candidateCount,
      int belowThresholdCount,
      int excludedAlreadyMergedCount,
      int excludedSameShopCount,
      int optOutCandidateCount,
      int addressResolvedCount,
      int addressUnavailableCount,
      int partyUnavailableCount,
      boolean truncated,
      String failureReason,
      Map<String, Object> facts,
      @Schema(nullable = true) String operatorId,
      String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /** 运行记录列表页：真分页。 */
  public record CrmCustomerMergeRunPage(
      List<CrmCustomerMergeRunRow> rows, int page, int size, long total, int totalPages) {}

  /**
   * 生成候选的结果。
   *
   * @param belowThresholdCount 命中了信号但置信度不足（同名不同人那一档）：**不产出候选**，只计数
   */
  public record CrmCustomerMergeScanResult(
      UUID runId,
      String runNo,
      String status,
      int scannedCustomerCount,
      int candidateCount,
      int belowThresholdCount,
      int excludedAlreadyMergedCount,
      int excludedSameShopCount,
      int optOutCandidateCount,
      int addressResolvedCount,
      int addressUnavailableCount,
      int partyUnavailableCount,
      boolean truncated,
      String failureReason,
      String startedAt,
      @Schema(nullable = true) String finishedAt,
      String note) {}
}
