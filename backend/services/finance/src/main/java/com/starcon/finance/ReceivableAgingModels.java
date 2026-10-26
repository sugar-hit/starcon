package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.1.2 应收账龄的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。 输入：应收单集合；账龄区间配置。 输出：账龄分布报表 + 逾期清单。 关系：上游 =
 * 12.1.1.1；下游 = 1.3 催收预警、12.1.1.3。 <b>跨境应收的最大风险是平台争议冻结</b>，账龄报表需单独标识"争议中"金额。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceReceivableAging*} / {@code FinanceAging*} 前缀，与既有六个服务与本服务的 12.1.1.1 类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>三条口径纪律（本文件是它们的落点）</b>：
 *
 * <ol>
 *   <li><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}；数量 {@code numeric(19,6)}、金额 {@code numeric(24,4)}。
 *   <li><b>币种分开</b>：任何汇总都按币种分组返回，USD 与 EUR 加起来不是任何一个金额。
 *   <li><b>争议中金额单列</b>：跨境应收最大的风险是平台争议冻结，争议部分不许混在总额里。
 * </ol>
 *
 * <p><b>占比是服务端算的</b>：{@code overdueShare} / {@code overdueShareTop} 在后端算完再返回 ——
 * 前端不做算术（仓库纪律：页面只原样展示服务端算好的数）。
 */
public final class ReceivableAgingModels {

  private ReceivableAgingModels() {}

  /** 金额小数位（与 12.1.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 占比小数位。 */
  public static final int RATIO_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 下钻维度（蓝图：「支持按店铺、客户、币种维度查看」）。
   *
   * <p>⚠️ 三者**不是**同一件事的三个筛选项，而是**三种分组口径**：同一批应收按店铺分组看一遍、按客户分组看一遍、按币种分组看一遍，结论往往不同。
   */
  public enum AgingDimension {
    SHOP("店铺"),
    COUNTERPARTY("客户"),
    CURRENCY("币种");

    private final String label;

    AgingDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceReceivableAgingCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceReceivableAgingDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.1.1.2 的能力声明。 */
  public record FinanceReceivableAgingCapabilities(
      List<FinanceReceivableAgingCapability> capabilities,
      List<FinanceReceivableAgingDownstreamGap> downstreamGaps) {}

  // ================================================================ 账龄区间

  /**
   * 一个账龄区间的定义。
   *
   * @param overdueFromDays 逾期天数下界（含）。{@code null} = 未到期档。
   * @param overdueToDays 逾期天数上界（含）。{@code null} = 无上界。
   */
  public record FinanceAgingBucketDef(
      String code,
      String label,
      @Schema(nullable = true) Integer overdueFromDays,
      @Schema(nullable = true) Integer overdueToDays,
      int sortOrder) {}

  /**
   * 生效中的区间配置。
   *
   * @param source {@code TENANT_CONFIG}（租户自己配的）或 {@code BLUEPRINT_DEFAULT}（蓝图给定的五档）。
   * @param configured 租户是否真的配过 —— 「用的是蓝图默认」与「这是你配的」必须能分开。
   */
  public record FinanceReceivableAgingBucketConfig(
      String source, boolean configured, List<FinanceAgingBucketDef> buckets, String detail) {}

  /** 一个区间的配置项（输入）。 */
  public record FinanceAgingBucketInput(
      String code,
      String label,
      @Schema(nullable = true) Integer overdueFromDays,
      @Schema(nullable = true) Integer overdueToDays,
      @Schema(nullable = true) Integer sortOrder) {}

  /**
   * 区间配置输入（**整体替换**）。
   *
   * <p>⚠️ 为什么是整体替换而不是逐档增删改：档位之间**必须连续覆盖 1 天起**，
   * 这条约束只有对着**整份配置**才判得出来。逐档写会出现「改到一半的中间态」，
   * 那种状态下落在缝里的应收不属于任何一档 —— 报表会**凭空少钱**。
   * 整体替换 ⇒ 配置要么合法要么不落库，不存在中间态。
   *
   * <p>空列表 = 回到**蓝图给定的五档**（不是「清空后什么都不算」）。
   */
  public record FinanceAgingBucketConfigInput(List<FinanceAgingBucketInput> buckets) {}

  // ================================================================ 分布

  /** 一个区间在一行上的金额。 */
  public record FinanceAgingBucketAmount(String code, String label, BigDecimal amount, long count) {}

  /**
   * 账龄分布的一行：一个维度值 × 一个币种。
   *
   * @param overdueShare 该行逾期额占**同币种**逾期总额的比例（服务端算好）。分母为 0 时为 {@code null}（「无逾期」≠「占比 0」）。
   */
  public record FinanceReceivableAgingRow(
      String dimension,
      String dimensionKey,
      String dimensionName,
      String currency,
      long count,
      BigDecimal openAmount,
      BigDecimal overdueAmount,
      BigDecimal disputeAmount,
      BigDecimal disputeOverdueAmount,
      @Schema(nullable = true) BigDecimal overdueShare,
      List<FinanceAgingBucketAmount> buckets) {}

  /**
   * 一个币种的合计。
   *
   * @param overdueShareTop 集中度：逾期额最多的那个维度值占该币种逾期总额的比例（服务端算好）。
   * @param topDimensionKey 集中度最高的那一维（逾期集中风险的落点）。
   */
  public record FinanceAgingCurrencyTotal(
      String currency,
      long count,
      BigDecimal openAmount,
      BigDecimal overdueAmount,
      BigDecimal disputeAmount,
      BigDecimal disputeOverdueAmount,
      @Schema(nullable = true) BigDecimal overdueShareTop,
      @Schema(nullable = true) String topDimensionKey,
      @Schema(nullable = true) String topDimensionName,
      List<FinanceAgingBucketAmount> buckets) {}

  /**
   * 账龄分布报表。
   *
   * @param asOfDate 账龄基准日（UTC 日历日）。**写进响应**是为了让「这张报表是哪天算的」可追溯。
   * @param totalCount 命中筛选的应收单张数（**整个筛选结果集**，不是当前页）。
   */
  public record FinanceReceivableAgingDistribution(
      LocalDate asOfDate,
      String bucketSource,
      List<FinanceAgingBucketDef> buckets,
      String dimension,
      long totalCount,
      List<FinanceReceivableAgingRow> rows,
      List<FinanceAgingCurrencyTotal> totals) {}

  // ================================================================ 逾期清单

  /** 逾期清单的一行。 */
  public record FinanceReceivableAgingOverdueRow(
      UUID id,
      String receivableNo,
      String currency,
      String counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      LocalDate dueDate,
      long overdueDays,
      String bucketCode,
      String bucketLabel,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal openAmount,
      boolean disputeFlag,
      String disputeNote,
      String source) {}

  /** 逾期清单的按币种汇总（覆盖**整个筛选结果集**，不是当前页）。 */
  public record FinanceAgingCurrencySummary(
      String currency,
      long count,
      BigDecimal openAmount,
      BigDecimal overdueAmount,
      BigDecimal disputeAmount,
      long disputeCount) {}

  /** 逾期清单页。 */
  public record FinanceReceivableAgingOverduePage(
      List<FinanceReceivableAgingOverdueRow> items,
      int page,
      int size,
      long total,
      LocalDate asOfDate,
      List<FinanceAgingCurrencySummary> summaryByCurrency,
      long disputeCount) {}

  // ================================================================ 筛选项

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinanceAgingOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * <p>⚠️ 账龄区间下拉返回的是**当前生效**的区间（租户配的或蓝图默认的），不是一份固定词表 ——
   * 租户改了档位，下拉必须跟着变。
   */
  public record FinanceReceivableAgingOptions(
      List<String> currencies,
      List<FinanceAgingOptionRef> shops,
      List<FinanceAgingOptionRef> counterparties,
      List<FinanceAgingBucketDef> buckets,
      LocalDate asOfDate) {}

  // ================================================================ 查询参数

  /**
   * 账龄报表的查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record ReceivableAgingQuery(
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String bucketCode,
      @Schema(nullable = true) Boolean disputeOnly,
      @Schema(nullable = true) String dimension,
      int page,
      int size) {}
}
