package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.4.2.1 平台价格同步的契约形状。
 *
 * <p>蓝图原文：
 * 「**职责**：把生效价格回写到平台，处理平台定价规则（平台可能有最低价 / 自动比价 /
 * 多站点联动），记录回写结果与被平台调整后的实际价。
 * **输入**：价格变更事件；平台定价规则；{@code idempotency_key}。
 * **输出**：回写任务（经 02 队列）；实际价与期望价的差异记录。
 * **关系**：上游 = 5.4.1.1 / 5.4.1.2；下游 = 2.5.1.1 平台操作。
 * **平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，不能假设回写即成功。」
 *
 * <p>🔴 <b>本模块的全部 DTO 一律以 {@code PlatformPriceSync} 开头。</b>
 * 契约 codegen 会把各服务的 schema 合并进同一个 {@code packages/api-contracts/src/catalog.ts}
 * （同名的后者按字母序覆盖前者）—— 裸名 {@code Rule} / {@code Diff} / {@code SyncResult}
 * 这种通用词迟早与别的模块撞名，把对方的类型打掉。所以这里用模块前缀钉死。
 *
 * <p>金额一律 {@link BigDecimal}（JSON number），前端只原样展示，不做算术。
 */
public final class PlatformPriceSyncModels {
  private PlatformPriceSyncModels() {}

  // ==================================================================
  // 词表
  // ==================================================================

  /**
   * 平台定价规则类型 —— 蓝图点名的三种平台行为。
   *
   * <ul>
   *   <li>{@link #MIN_PRICE} 最低价：期望价低于它 ⇒ 平台拒收（REJECTED）
   *   <li>{@link #AUTO_COMPARE} 自动比价：高出对比平台太多 ⇒ 平台压到对比价（ADJUSTED）
   *   <li>{@link #MULTI_SITE_LINK} 多站点联动：越出参照站点价格带 ⇒ 平台拉到带边界（ADJUSTED）
   * </ul>
   */
  public enum PlatformPriceRuleType {
    MIN_PRICE,
    AUTO_COMPARE,
    MULTI_SITE_LINK
  }

  /** 规则状态：停用的规则保留历史口径，不再参与判定。 */
  public enum PlatformPriceRuleStatus {
    ACTIVE,
    DISABLED
  }

  /**
   * 回写的最终处置。
   *
   * <p>🔴 {@link #PENDING} 是「已投递、等平台回执」，**既不是成功也不是失败** ——
   * 把它并进成功就是蓝图那句「不能假设回写即成功」的字面反面。
   */
  public enum PlatformPriceSyncOutcome {
    PENDING,
    ACCEPTED,
    ADJUSTED,
    REJECTED,
    FAILED
  }

  /** 规则判定结果。与 {@link PlatformPriceSyncOutcome} 分开：平台回执可能推翻规则判定。 */
  public enum PlatformPriceRuleOutcome {
    NOT_EVALUATED,
    NO_RULE,
    ACCEPTED,
    ADJUSTED,
    REJECTED
  }

  /** 差异记录的结局。 */
  public enum PlatformPriceDiffOutcome {
    MATCHED,
    ADJUSTED,
    REJECTED,
    FAILED
  }

  /** 差异是哪一步产生的。 */
  public enum PlatformPriceDiffSource {
    /** 派发时按平台定价规则算出来的承接价。 */
    RULE_EVALUATION,
    /** 平台回执给的真实价。 */
    PLATFORM_RECEIPT
  }

  // ==================================================================
  // 平台定价规则
  // ==================================================================

  /**
   * 一条平台定价规则。
   *
   * <p>🔴 参数按类型分列（而不是一个 {@code Map}）：Map 的值类型会被 springdoc 推成
   * {@code object}，codegen 出一个空对象 {@code {}}，前端既拿不到最低价也过不了类型检查。
   * 没用到的列是 null。
   */
  public record PlatformPriceRuleView(
      UUID id,
      PlatformPriceRuleType ruleType,
      String platformCode,
      String siteCode,
      @Schema(nullable = true) UUID skuId,
      PlatformPriceRuleStatus status,
      int priority,
      @Schema(nullable = true) BigDecimal minPrice,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String comparePlatformCode,
      @Schema(nullable = true) String compareSiteCode,
      @Schema(nullable = true) BigDecimal maxGapRatio,
      @Schema(nullable = true) String referenceSiteCode,
      @Schema(nullable = true) BigDecimal minRatio,
      @Schema(nullable = true) BigDecimal maxRatio,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建 / 改规则的入参。 */
  public record PlatformPriceRuleInput(
      @NotNull PlatformPriceRuleType ruleType,
      @NotBlank @Size(max = 32) String platformCode,
      /** 空串 = 该平台全部站点。 */
      @Size(max = 32) String siteCode,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) PlatformPriceRuleStatus status,
      @Schema(nullable = true) @Min(0) @Max(100000) Integer priority,
      @Schema(nullable = true) @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4)
          BigDecimal minPrice,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写 ISO 4217 代码")
          String currency,
      @Schema(nullable = true) @Size(max = 32) String comparePlatformCode,
      @Schema(nullable = true) @Size(max = 32) String compareSiteCode,
      @Schema(nullable = true) @DecimalMin(value = "0") @Digits(integer = 3, fraction = 6)
          BigDecimal maxGapRatio,
      @Schema(nullable = true) @Size(max = 32) String referenceSiteCode,
      @Schema(nullable = true) @DecimalMin(value = "0.000001") @Digits(integer = 3, fraction = 6)
          BigDecimal minRatio,
      @Schema(nullable = true) @DecimalMin(value = "0.000001") @Digits(integer = 3, fraction = 6)
          BigDecimal maxRatio,
      @Size(max = 400) String note) {}

  /** 规则列表（真分页）。 */
  public record PlatformPriceRulePage(
      List<PlatformPriceRuleView> items, int page, int size, long totalElements, int totalPages) {}

  // ==================================================================
  // 回写结果
  // ==================================================================

  /**
   * 一条待回写事实在本模块的处置与结果。
   *
   * <p>🔴 三个价各司其职，**不合并**：
   * <ul>
   *   <li>{@code expectedPrice} —— 期望价 = 调价单的新价（我们**要求**平台收的价）
   *   <li>{@code predictedPrice} —— 规则判定价（平台定价规则算出来的**承接价**，判不出来时 null）
   *   <li>{@code actualPrice} —— 平台回执的**实际价**（还没回执时 null）
   *   <li>{@code priceDelta} —— {@code actualPrice - expectedPrice}，**显性反馈的核心数字**
   * </ul>
   */
  public record PlatformPriceSyncRecordView(
      UUID id,
      UUID writebackId,
      UUID orderId,
      UUID orderItemId,
      UUID skuId,
      String skuCode,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal expectedPrice,
      @Schema(nullable = true) BigDecimal predictedPrice,
      @Schema(nullable = true) BigDecimal actualPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal deviationRatio,
      PlatformPriceSyncOutcome outcome,
      PlatformPriceRuleOutcome ruleOutcome,
      @Schema(nullable = true) UUID ruleId,
      String ruleReason,
      @Schema(nullable = true) UUID platformOperationId,
      String platformRequestId,
      String idempotencyKey,
      String lastError,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 回写结果详情：记录 + 它产生的全部差异记录。 */
  public record PlatformPriceSyncRecordDetail(
      PlatformPriceSyncRecordView record, List<PlatformPriceDiffView> diffs) {}

  /** 回写结果列表（真分页）。 */
  public record PlatformPriceSyncRecordPage(
      List<PlatformPriceSyncRecordView> items, int page, int size, long totalElements, int totalPages) {}

  // ==================================================================
  // 差异记录
  // ==================================================================

  /**
   * 一条差异记录：实际价与期望价的差。
   *
   * <p>只追加 —— 一次回写可能留下多条（规则判定一条、平台回执一条），
   * 后来的结论**不覆盖**前面的历史。
   */
  public record PlatformPriceDiffView(
      UUID id,
      UUID syncId,
      UUID writebackId,
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal expectedPrice,
      @Schema(nullable = true) BigDecimal actualPrice,
      @Schema(nullable = true) BigDecimal priceDelta,
      @Schema(nullable = true) BigDecimal deviationRatio,
      PlatformPriceDiffOutcome outcome,
      PlatformPriceDiffSource source,
      String reasonCode,
      String reasonMessage,
      OffsetDateTime createdAt) {}

  /** 差异台账（真分页）。 */
  public record PlatformPriceDiffPage(
      List<PlatformPriceDiffView> items, int page, int size, long totalElements, int totalPages) {}

  // ==================================================================
  // 输入
  // ==================================================================

  /**
   * 一次同步的请求：消费上游的待回写事实并投递。
   *
   * <p>不给筛选条件就是「把本租户全部待回写事实都过一遍」。
   */
  public record PlatformPriceSyncRequest(
      @Schema(nullable = true) @Size(max = 32) String platformCode,
      @Schema(nullable = true) @Size(max = 32) String siteCode,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) @Min(1) @Max(500) Integer limit) {}

  /**
   * 平台回执。
   *
   * <p>🔴 这是**平台适配器 / 核验通道**回来说「平台最后收了多少」的入口
   * （与 2.5.2.1 / 4.1 刊登流程里「平台回执」端点同构）。
   * 收不到回执时记录停在 PENDING —— <b>不假设回写即成功</b>。
   */
  public record PlatformPriceReceiptRequest(
      @NotNull PlatformPriceSyncOutcome outcome,
      @Schema(nullable = true) @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4)
          BigDecimal actualPrice,
      @Schema(nullable = true) @Size(max = 128) String platformRequestId,
      @Schema(nullable = true) @Size(max = 500) String reason) {}

  // ==================================================================
  // 输出
  // ==================================================================

  /** 一次同步的结果汇总。 */
  public record PlatformPriceSyncRunView(
      int scanned,
      int created,
      int dispatched,
      int rejected,
      int skipped,
      int failed,
      List<PlatformPriceSyncRecordView> items) {}

  /** 能力声明。 */
  public record PlatformPriceSyncCapabilities(
      String module,
      List<String> ruleTypes,
      List<String> ruleTypeSemantics,
      List<String> outcomes,
      List<String> diffSources,
      String upstreamOwner,
      String upstreamResource,
      String upstreamWriteOwner,
      String queueService,
      String queueResource,
      String queueOperation,
      String comparisonPriceSource,
      List<String> notes) {}
}
