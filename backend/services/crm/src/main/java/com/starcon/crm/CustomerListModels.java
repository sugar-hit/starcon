package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.3.1.1 客户列表的传输模型。
 *
 * <p>蓝图 15 卷 15.3.1.1 原话：
 *
 * <blockquote>
 * 职责：客户列表：客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、退款率、客单价、标签、联系方式（脱敏）、风险标记。
 * 输入：筛选条件（店铺、标签、价值分层、风险）；用户数据范围。
 * 输出：客户列表 + 分层统计。
 * 关系：上游 = 9 订单；下游 = 15.3.1.2 详情、15.3.1.3 跨店合并结果（合并交付后列表按逻辑客户展示 ⇒ 下游增强，不是开工前置）。
 * <b>列表默认脱敏显示联系方式</b>，需要显式授权才完整展示。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>客户是「按来源店铺」的</b>：一行 = 一个来源店铺下的一个客户标识。
 *       跨店合并（15.3.1.3）拥有逻辑客户与合并映射，本模块**不提前做**。
 *       所以列表里同一个人可能出现在两行（两个来源），这是**有意的**。</li>
 *   <li><b>金额按币种分开</b>：累计消费与客单价都按币种分行给出，
 *       「USD 与 EUR 加起来不是任何一个金额」。</li>
 *   <li><b>联系方式默认脱敏</b>：{@code contactEmail} / {@code contactPhone}
 *       默认是脱敏形态；只有调用方**显式授权**（{@code crm.customers.pii.read}）
 *       并显式要求时才给明文，此时 {@code contactMasked=false}。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCustomerList*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class CustomerListModels {

  private CustomerListModels() {}

  // ================================================================ 枚举

  /**
   * 价值分层。
   *
   * <p>分档口径（服务端算，前端只原样展示）：
   *
   * <ul>
   *   <li>{@link #VIP} —— 主币种累计消费 ≥ 租户同币种人均累计消费 × 3；
   *   <li>{@link #HIGH} —— 主币种累计消费 ≥ 租户同币种人均累计消费；
   *   <li>{@link #NORMAL} —— 有消费但低于基线；
   *   <li>{@link #LOW} —— 没有有效消费（订单全是草稿或金额为 0）。
   * </ul>
   *
   * <p>「主币种」= 该客户订单数最多的那个币种（并列时取累计消费更大的那个）。
   */
  public enum CustomerValueTier {
    VIP("高价值"),
    HIGH("较高价值"),
    NORMAL("常规"),
    LOW("无有效消费");

    private final String label;

    CustomerValueTier(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 风险标记。
   *
   * <p>🔴 只由**可复核的事实**得出（退款率显著高于租户基线 / 已登记生效中的退订），
   * 每一枚都带原因；**不触发任何对买家的动作**（不屏蔽、不拒绝、不降权）。
   *
   * <p>{@link #INSUFFICIENT_SAMPLE} 与 {@link #NONE} 是**两件事**：
   * 样本不足时结论是「**不可判定**」，不是「没风险」。
   */
  public enum CustomerRiskFlag {
    NONE("无风险标记"),
    HIGH_RETURN_RATE("退款率显著高于租户基线"),
    OPT_OUT("已登记生效中的退订"),
    HIGH_RETURN_RATE_AND_OPT_OUT("退款率偏高且已退订"),
    INSUFFICIENT_SAMPLE("样本不足，不可判定");

    private final String label;

    CustomerRiskFlag(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 联系方式来源。 */
  public enum CustomerContactSource {
    NONE("无联系方式"),
    CATALOG_PARTY("catalog 客户档案带回来的"),
    MANUAL("人工登记");

    private final String label;

    CustomerContactSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 退订状态（快照自 15.3.2.2 的退订表）。 */
  public enum CustomerOptOutState {
    NONE("无退订记录"),
    ACTIVE("退订生效中"),
    REVOKED("退订已撤销");

    private final String label;

    CustomerOptOutState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 刷新客户档案的入参。
   *
   * <p>统计窗口起止必须**成对**给：只给一个会让窗口的两端对不上。
   * 都不给 ⇒ 用默认窗口（最近 365 天）。
   */
  public record CrmCustomerListSyncInput(
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo) {}

  /**
   * 登记 / 修正联系方式。
   *
   * <p>客户必须**已经存在**于本租户的客户主档里（按来源店铺 + 客户标识定位）——
   * 否则就是给一个不存在的客户建联系方式，那是别的模块的事。
   */
  public record CrmCustomerListContactInput(
      @NotBlank @Size(max = 128) String customerRef,
      @NotBlank @Size(max = 64) String sourceShop,
      @Schema(nullable = true) @Email @Size(max = 254) String email,
      @Schema(nullable = true) @Size(max = 64) String phone) {}

  // ================================================================ 出参

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmCustomerListCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  /** 筛选项下拉用的租户级词表（全部来自真实数据 DISTINCT，不是硬编码常量列表）。 */
  public record CrmCustomerListFilterOptions(
      List<String> sourceShops,
      List<String> valueTiers,
      List<String> riskFlags,
      List<String> currencies,
      List<String> tagCodes) {}

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * @param maskingPolicy 🔴 脱敏口径（默认脱敏 + 显式授权才给明文）
   * @param boundaryNote 边界说明（按来源店铺的客户 vs 逻辑客户）
   */
  public record CrmCustomerListCapabilities(
      List<CrmCustomerListCapabilityDimension> dimensions,
      CrmCustomerListFilterOptions filterOptions,
      List<String> valueTiers,
      List<String> riskFlags,
      String maskingPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 一个币种上的累计消费与客单价。
   *
   * @param totalAmount 累计消费（该币种，窗口内）
   * @param avgOrderAmount 客单价 = 累计消费 ÷ 订单数（服务端算）；分母为 0 时为 null
   */
  public record CrmCustomerListAmount(
      String currency,
      BigDecimal totalAmount,
      int orderCount,
      @Schema(nullable = true) BigDecimal avgOrderAmount) {}

  /** 客户身上的一枚标签（只读 15.3.2.1 的标签记录）。 */
  public record CrmCustomerListTag(String tagCode, String tagName, String category, String categoryLabel) {}

  /**
   * 客户列表的一行。
   *
   * @param customerRef 客户标识（**按来源店铺**）
   * @param sourceShop 来源店铺（订单来源口径）
   * @param refundRate 退款率；分母为 0 时为 null（**不可判定**，不是 0）
   * @param contactEmail 联系方式 —— 默认脱敏；显式授权且显式要求时才是明文
   * @param contactMasked 当前返回的联系方式是否脱敏
   * @param windowFrom / windowTo 这一行数据来自哪一次刷新（窗口不一致 = 数据新鲜度不同）
   */
  public record CrmCustomerListRow(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      int orderCount,
      int refundCaseCount,
      @Schema(nullable = true) BigDecimal refundRate,
      String valueTier,
      String valueTierLabel,
      String riskFlag,
      String riskFlagLabel,
      String riskReason,
      List<CrmCustomerListAmount> amounts,
      @Schema(nullable = true) BigDecimal totalOrderAmount,
      @Schema(nullable = true) String totalOrderCurrency,
      @Schema(nullable = true) BigDecimal avgOrderAmount,
      List<CrmCustomerListTag> tags,
      String contactEmail,
      String contactPhone,
      boolean contactMasked,
      String contactSource,
      String contactSourceLabel,
      String optOutState,
      String optOutStateLabel,
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo,
      @Schema(nullable = true) String syncedAt) {}

  /** 分层统计的一格：价值分层。 */
  public record CrmCustomerListTierBucket(
      String valueTier, String valueTierLabel, long customerCount, long orderCount) {}

  /** 分层统计的一格：来源店铺。 */
  public record CrmCustomerListShopBucket(String sourceShop, long customerCount, long orderCount) {}

  /** 分层统计的一格：风险标记。 */
  public record CrmCustomerListRiskBucket(
      String riskFlag, String riskFlagLabel, long customerCount, long orderCount) {}

  /** 分层统计的一格：币种上的累计消费。 */
  public record CrmCustomerListCurrencyAmount(String currency, BigDecimal amount, long customerCount) {}

  /**
   * 分层统计。
   *
   * <p>🔴 **与分页无关**：这些数字来自**单独一条**统计查询（不是拿当前页聚合出来的），
   * 所以翻到第 3 页看到的分层分布与第 1 页完全一样。
   */
  public record CrmCustomerListStats(
      long totalCustomers,
      long totalOrders,
      long totalRefundCases,
      List<CrmCustomerListTierBucket> byValueTier,
      List<CrmCustomerListShopBucket> bySourceShop,
      List<CrmCustomerListRiskBucket> byRiskFlag,
      List<CrmCustomerListCurrencyAmount> amountsByCurrency,
      String note) {}

  /**
   * 客户列表页：真分页（LIMIT/OFFSET + 稳定全序排序）+ 与分页无关的分层统计。
   *
   * @param dataScope 实际生效的数据范围：ALL / SELF / DEPARTMENT
   * @param dataScopeNote 数据范围的说明文案
   */
  public record CrmCustomerListPage(
      List<CrmCustomerListRow> rows,
      int page,
      int size,
      long total,
      int totalPages,
      CrmCustomerListStats stats,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 刷新客户档案的结果。
   *
   * @param contactResolvedCount 联系方式补齐成功的客户数
   * @param contactUnavailableCount 联系方式没取到的客户数（原因见 note）
   */
  public record CrmCustomerListSyncResult(
      UUID runId,
      String runNo,
      String status,
      String windowFrom,
      String windowTo,
      int customerCount,
      int orderCount,
      int caseCount,
      int unattributedCaseCount,
      int contactResolvedCount,
      int contactUnavailableCount,
      boolean truncated,
      @Schema(nullable = true) BigDecimal baseline,
      String failureReason,
      String startedAt,
      String finishedAt,
      String note) {}

  /** 一次刷新运行的记录。 */
  public record CrmCustomerListSyncRunRow(
      UUID id,
      String runNo,
      String status,
      String windowFrom,
      String windowTo,
      int customerCount,
      int orderCount,
      int caseCount,
      int unattributedCaseCount,
      int contactResolvedCount,
      int contactUnavailableCount,
      boolean truncated,
      @Schema(nullable = true) BigDecimal baseline,
      String failureReason,
      Map<String, Object> facts,
      @Schema(nullable = true) String operatorId,
      String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /** 刷新运行记录列表页（真分页）。 */
  public record CrmCustomerListSyncRunPage(
      List<CrmCustomerListSyncRunRow> rows, int page, int size, long total, int totalPages) {}
}
