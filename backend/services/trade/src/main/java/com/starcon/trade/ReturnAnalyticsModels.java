package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 11.5.1.1 退货分析的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、退货金额趋势、拒退率、处理时效、
 * 平均退款金额与原因分布。
 * 输入：售后与退货数据；销售数据。
 * 输出：<b>退货分析报表 + 维度下钻 + 异常识别</b>。
 * 关系：上游 = 11.1、11.2、11.3；下游 = 13 分析、6 采购（质量改进）。
 * <b>退货率必须与销售额同口径比较</b>，只看绝对数会误判（退货率从 3% 升到 5% 严重，
 * 但绝对数可能没变）。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>币种是报表的第一维</b>（{@link ReturnCurrencySummary}）。蓝图加粗句要求
 *       「退货率必须与销售额同口径比较」，而「同口径」的第一层就是<b>同币种</b>：
 *       分子（退货金额）与分母（销售额）必须落在同一个币种里才允许相除。
 *       跨币种只有两种做法 —— 按币种分组，或者拒绝。本模块<b>只做前者</b>：
 *       每个币种各自算自己的退货率，响应里<b>没有跨币种的合计金额与合计退货率</b>，
 *       因为「100 元退货 ÷ 1000 日元销售额 = 10%」这个数没有意义。</li>
 *   <li><b>每个比值都是显式算出来的 {@link BigDecimal}</b>（标度 4，即 0.0500 = 5%），
 *       <b>绝不让数据库返回 float8</b>：{@code avg()} / {@code sum()} 落在 numeric 上，
 *       比值在服务层用 {@code divide(…, 4, HALF_UP)} 算。
 *       分母为 0 时返回 {@code null} 而不是 0 —— 没有销售额就没有退货率，
 *       报 0 会被读成「这个维度退货率 0%，很健康」。</li>
 *   <li><b>口径标记逐行带</b>（{@link ReturnCaliber}）：单头口径（售后单申请金额）、
 *       行级口径（售后商品行金额）、件数口径（实收件数 ÷ 销量件数）——
 *       三者的分子分母<b>不是同一个东西</b>，不标出来的话读的人会把两个口径的数直接比大小。</li>
 *   <li><b>每一份报表都带 {@code generatedAt} 与口径说明</b>：
 *       退货率最容易被误读成「这个月的数字」，而「分子取哪张表哪一列、
 *       分母取哪张表哪一列、期间怎么切」不定死，同一张表今天和下个月导出就会对不上。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ReturnAnalysis*} 前缀，与 {@code ReturnQuality*}（11.2.3.1）、
 * {@code ReturnReceipt*}（11.2.2.1）、{@code ReturnAllocation*}（11.2.2.2）零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class ReturnAnalyticsModels {

  private ReturnAnalyticsModels() {}

  // ================================================================ 枚举

  /**
   * 下钻维度 —— 蓝图职责句点名的五个（店铺/SKU/类目/原因/批次）。
   *
   * <p>⚠️ 五个维度的<b>分子分母各不相同</b>，所以每个维度都必须带 {@link ReturnCaliber}：
   *
   * <ul>
   *   <li>{@code SHOP} —— 店铺（platform_order.shop_code 经销售订单关联）
   *       单头口径：按售后单聚合，金额分母是同店铺同期的订单总额。</li>
   *   <li>{@code SKU} —— SKU（sales_order_line 的下单快照）
   *       行级口径：按售后商品行聚合，金额分母是同 SKU 同期的行金额。</li>
   *   <li>{@code CATEGORY} —— 类目（trade 侧唯一的 SKU→类目映射）
   *       行级口径，与 SKU 同口径聚合后按类目汇总。</li>
   *   <li>{@code REASON} —— 原因（售后单的七类内联词表）
   *       单头口径；分母是<b>全量</b>同期销售额 —— 原因的退货率是「这个原因占销售额的多少」，
   *       不是「这个原因内部退了多少」。</li>
   *   <li>{@code BATCH} —— 批次（return_receipt_line.batch_no）
   *       件数口径：分子是实收件数，分母是<b>该批次关联 SKU 同期销量件数</b>
   *       （批次本身不是销售切片，库里没有「这个批次卖了多少」）。</li>
   * </ul>
   */
  public enum ReturnDimension {
    SHOP("店铺"),
    SKU("SKU"),
    CATEGORY("类目"),
    REASON("原因"),
    BATCH("批次");

    private final String label;

    ReturnDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 口径标记 —— 一行数字的分子分母是按哪个口径算出来的。
   *
   * <p>⚠️ 三者<b>不可比</b>：单头口径的分子是「售后单上买家申请退的金额」，
   * 行级口径的分子是「售后商品行上逐行申请的金额」，件数口径的分子是「实收件数」。
   * 同一张表里混着三种口径又不标出来，读的人会把 3% 和 30% 直接比大小。
   */
  public enum ReturnCaliber {
    /** 单头口径：售后单申请的金额 ÷ 同期订单总额。 */
    CASE_LEVEL("单头口径"),
    /** 行级口径：售后商品行申请的金额 ÷ 同期订单行金额。 */
    LINE_LEVEL("行级口径"),
    /** 件数口径：实收件数 ÷ 关联 SKU 同期销量件数。 */
    QTY_LEVEL("件数口径");

    private final String label;

    ReturnCaliber(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 口径来源：这行数字用的是租户登记的口径，还是本模块的默认口径。 */
  public enum CaliberSource {
    /** 默认口径（文档化的内置口径，租户没有登记自己的口径）。 */
    DEFAULT("默认口径"),
    /** 租户自维护的口径（return_analysis_basis 的生效版本）。 */
    TENANT_BASIS("租户口径");

    private final String label;

    CaliberSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 趋势分桶粒度。**桶边界一律按 UTC**（换一条连接就换一套分桶的坑）。 */
  public enum TrendGranularity {
    DAY("日"),
    WEEK("周"),
    MONTH("月");

    private final String label;

    TrendGranularity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 异常等级。 */
  public enum AnomalySeverity {
    LOW("提示"),
    MEDIUM("关注"),
    HIGH("严重");

    private final String label;

    AnomalySeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 请求

  /**
   * 报表的期间过滤（半开区间：[from, to)）。
   *
   * <p>⚠️ 期间边界按 <b>UTC 日界</b>切，与 11.5.2.1 的质量分析同款 ——
   * 用会话时区切分桶的话，换一条连接就换一套分桶，趋势图会凭空多出空桶。
   */
  public record ReturnAnalysisWindow(
      @Schema(description = "起始日（含），null 表示不限", nullable = true) LocalDate from,
      @Schema(description = "截止日（**含当天**），null 表示不限", nullable = true) LocalDate to) {

    /**
     * 期间的人话描述，原样出现在每个响应的口径说明里。
     *
     * <p>⚠️ {@code to} <b>含当天</b>：前端是日期选择器，用户选 2026-01-01 到 2026-01-31
     * 期望的就是含 1 月 31 号的 31 天。实际落到 SQL 上是半开区间
     * {@code [from 00:00, to+1 00:00)}（UTC 日界）。
     */
    public String note() {
      if (from == null && to == null) {
        return "**未限定期间** —— 统计范围是本租户全部数据。";
      }
      String f = from == null ? "最早" : from.toString();
      String t = to == null ? "至今" : to.toString() + "（含当天）";
      return "统计期间 " + f + " → " + t + "（半开区间 [from, to+1 天)，按 **UTC 日界**切）。";
    }
  }

  /**
   * 建退货分析口径。
   *
   * <p>⚠️ <b>不传</b> {@code basisStatus} / {@code effectiveTo} / {@code supersededBy}：
   * 建行恒为 {@code ACTIVE} 且不预设截止日，截止日由换版时写。
   *
   * <p>⚠️ 阈值<b>全部必填</b>：缺省值由前端表单显式带上来（表单上写着这些数是多少），
   * 提交后服务端按提交的值落库 —— 「这次算异常用的是哪套阈值」在快照与留痕里查得到。
   */
  public record ReturnAnalysisBasisCreate(
      @Schema(description = "口径名（租户自己起的名字）", required = true) String basisName,
      @Schema(description = "口径适用的币种（三字母大写）", required = true) String currency,
      @Schema(description = "分子是否含「仅退款」类售后单（默认 false）", required = true)
          boolean includeRefundOnly,
      @Schema(description = "退货率异常倍数（维度率 ≥ 总体率 × 本值）", required = true)
          BigDecimal rateAlertMultiplier,
      @Schema(description = "退货率异常的最小销售额占比（0.01 = 总体销售额的 1%）", required = true)
          BigDecimal rateAlertMinSalesShare,
      @Schema(description = "拒退率异常的百分点差", required = true) BigDecimal rejectionAlertGapPp,
      @Schema(description = "拒退率异常的最小已决定单数", required = true) long rejectionAlertMinCases,
      @Schema(description = "批次件数异常倍数", required = true) BigDecimal batchAlertMultiplier,
      @Schema(description = "批次件数异常的最小件数", required = true) BigDecimal batchAlertMinQty,
      @Schema(description = "原因集中异常的最小占比（0.6 = 60%）", required = true)
          BigDecimal reasonAlertShare,
      @Schema(description = "原因集中异常的最小案件数", required = true) long reasonAlertMinCases,
      @Schema(description = "趋势恶化异常的百分点差", required = true) BigDecimal trendAlertGapPp,
      @Schema(description = "口径生效日（报表窗口完全早于这一天时用默认口径）", required = true)
          LocalDate effectiveFrom,
      @Schema(description = "口径说明（这些阈值是怎么定的）") String sourceNote) {}

  /**
   * 口径换版（终态：换版即留痕，V271 守卫①）。
   *
   * <p>⚠️ <b>换版是原子的</b>：请求体里带的是**新口径的完整内容**，
   * 服务层在一个事务里「老版本置为已换版 + 新版本建成生效」——
   * 分两步做（先换版再新建）会在两步之间留下「这个币种没有生效口径」的窗口，
   * 而那段时间里算出来的退货率用的是默认口径，却没有人知道。
   *
   * <p>⚠️ <b>不传</b> {@code basisStatus} / {@code effectiveTo} / {@code supersededBy}：
   * 新行恒为生效且不预设截止日，截止日由换版时写。
   */
  public record ReturnAnalysisBasisSupersede(
      @Schema(description = "新口径名（租户自己起的名字）", required = true) String basisName,
      @Schema(description = "新口径适用的币种（三字母大写，须与老口径同币种）", required = true)
          String currency,
      @Schema(description = "分子是否含「仅退款」类售后单", required = true)
          boolean includeRefundOnly,
      @Schema(description = "退货率异常倍数", required = true) BigDecimal rateAlertMultiplier,
      @Schema(description = "退货率异常的最小销售额占比", required = true)
          BigDecimal rateAlertMinSalesShare,
      @Schema(description = "拒退率异常的百分点差", required = true) BigDecimal rejectionAlertGapPp,
      @Schema(description = "拒退率异常的最小已决定单数", required = true) long rejectionAlertMinCases,
      @Schema(description = "批次件数异常倍数", required = true) BigDecimal batchAlertMultiplier,
      @Schema(description = "批次件数异常的最小件数", required = true) BigDecimal batchAlertMinQty,
      @Schema(description = "原因集中异常的最小占比", required = true) BigDecimal reasonAlertShare,
      @Schema(description = "原因集中异常的最小案件数", required = true) long reasonAlertMinCases,
      @Schema(description = "趋势恶化异常的百分点差", required = true) BigDecimal trendAlertGapPp,
      @Schema(description = "新口径生效日", required = true) LocalDate effectiveFrom,
      @Schema(description = "新口径说明") String sourceNote,
      @Schema(description = "换版理由（必填）", required = true) String supersedeReason,
      @Schema(description = "乐观锁版本号", required = true) long version) {}

  /**
   * 保存一份退货分析报表快照。
   *
   * <p>⚠️ <b>不传</b> {@code snapshotNo} / {@code computedAt} / {@code createdBy}：
   * 单号由服务层生成，计算时刻取参考时刻。
   */
  public record ReturnAnalysisSnapshotRequest(
      @Schema(description = "起始日（含），null 表示不限", nullable = true) LocalDate from,
      @Schema(description = "截止日（**含当天**），null 表示不限", nullable = true) LocalDate to,
      @Schema(description = "币种（三字母大写），null 表示该窗口内出现过的全部币种", required = true)
          String currency,
      @Schema(description = "维度 SHOP/SKU/CATEGORY/REASON/BATCH", required = true) String dimension) {}

  // ================================================================ 响应：能力声明

  /** 能力声明。职责句点名的每一项分析都要在这里出现一次 —— 少一块就是少做一个承诺。 */
  public record ReturnAnalysisCapabilities(
      List<String> dataSources,
      List<ReturnAnalysisSection> sections,
      List<ReturnAnalysisRelatedModule> relatedModules,
      List<ReturnAnalysisAction> actions,
      List<String> boundaries,
      String caliberNote,
      String currencyNote,
      String rejectionNote,
      String processingNote,
      String batchNote) {}

  /** 一块分析。 */
  public record ReturnAnalysisSection(
      String key,
      String title,
      boolean available,
      String moduleId,
      String basis) {}

  /** 上下游模块。 */
  public record ReturnAnalysisRelatedModule(
      String moduleId,
      String title,
      boolean delivered,
      String ownerSystem,
      String relation,
      @Schema(nullable = true) String path) {}

  /** 一个动作（端点）。 */
  public record ReturnAnalysisAction(
      String key,
      String title,
      boolean available,
      String method,
      String path,
      boolean readOnly,
      String precondition,
      @Schema(nullable = true) String permission) {}

  // ================================================================ 响应：报表

  /**
   * 退货分析总览。
   *
   * <p>⚠️ {@link #currencies} 是**按币种分组**的：每个币种各自算自己的退货率，
   * 跨币种<b>没有</b>合计金额与合计退货率（{@link #totals} 里只有与币种无关的计数）。
   */
  public record ReturnAnalysisOverview(
      OffsetDateTime generatedAt,
      String windowNote,
      List<ReturnCurrencySummary> currencies,
      ReturnAnalysisTotals totals,
      ReturnRejectionSummary rejection,
      ReturnProcessingSummary processing,
      List<ReturnReasonSlice> reasonDistribution,
      List<ReturnAttributionSlice> attributionDistribution,
      long attributedCaseCount,
      @Schema(description = "归因覆盖率（百分比）；期内没有售后单时为 null", nullable = true)
          BigDecimal attributionCoverage,
      List<ReturnAnomalyThresholds> thresholds,
      String note) {}

  /**
   * 一个币种的退货概况 —— **本模块的头号数字都在这里**。
   *
   * <p>⚠️ {@link #returnRate} 是比值（标度 4，0.0500 = 5%），分母为 0 时为 {@code null}。
   */
  public record ReturnCurrencySummary(
      String currency,
      String caliberSource,
      String basisNo,
      String basisName,
      boolean includeRefundOnly,
      long caseCount,
      BigDecimal returnQty,
      BigDecimal returnAmount,
      long salesOrderCount,
      BigDecimal salesAmount,
      @Schema(description = "退货率（比值，标度 4；分母为 0 时为 null）", nullable = true)
          BigDecimal returnRate,
      long rejectedCount,
      long decidedCount,
      @Schema(description = "拒退率（比值，标度 4）", nullable = true) BigDecimal rejectionRate,
      long refundCount,
      BigDecimal refundTotal,
      @Schema(description = "平均退款金额；没有成功退款时为 null", nullable = true)
          BigDecimal avgRefundAmount,
      String note) {}

  /**
   * 与币种无关的计数合计。
   *
   * <p>⚠️ <b>只有计数</b>：金额与比率<b>跨币种不可加</b>，
   * 所以这个合计里刻意<b>没有</b>退货金额、销售额与退货率。
   */
  public record ReturnAnalysisTotals(long caseCount, BigDecimal returnQty, long salesOrderCount) {}

  /**
   * 拒退率 —— 职责句点名的「拒退率」。
   *
   * <p>口径：分子 = {@code rma_status='REJECTED'} 的退货授权单数；
   * 分母 = <b>已做出决定</b>的退货授权单数（授权 / 部分授权 / 拒绝）。
   * 草稿与超时关闭的单<b>不进分母</b> —— 它们还没有结论，
   * 把它们算进分母等于把「还没批」当成「批了不批」。
   */
  public record ReturnRejectionSummary(
      long decidedCount,
      long rejectedCount,
      long partiallyAuthorizedCount,
      long authorizedCount,
      @Schema(description = "拒退率（比值，标度 4）", nullable = true) BigDecimal rejectionRate,
      BigDecimal requestedQty,
      BigDecimal authorizedQty,
      @Schema(description = "件数口径的未授权占比（比值，标度 4）", nullable = true)
          BigDecimal unapprovedQtyRate,
      String note) {}

  /**
   * 处理时效 —— 职责句点名的「处理时效」。
   *
   * <p>四个时长分别来自四份不同的单据，<b>互不混算</b>：
   * 售后单结案时长、授权审批时长、买家回寄时长、退款出结果时长。
   */
  public record ReturnProcessingSummary(
      @Schema(description = "售后单结案平均时长（小时）；无样本时为 null", nullable = true)
          BigDecimal avgCaseResolutionHours,
      @Schema(description = "售后单结案最长时长（小时）；无样本时为 null", nullable = true)
          BigDecimal maxCaseResolutionHours,
      long caseSampleCount,
      @Schema(description = "退货授权平均审批时长（小时）；无样本时为 null", nullable = true)
          BigDecimal avgRmaApprovalHours,
      long rmaSampleCount,
      @Schema(description = "买家回寄平均时长（小时）；无样本时为 null", nullable = true)
          BigDecimal avgReceiptHours,
      long receiptSampleCount,
      @Schema(description = "退款出结果平均时长（小时）；无样本时为 null", nullable = true)
          BigDecimal avgRefundHours,
      long refundSampleCount,
      String note) {}

  /** 退货原因分布（七类内联词表，11.1.2.1 的归因口径另见 {@link ReturnAttributionSlice}）。 */
  public record ReturnReasonSlice(
      String currency,
      String reasonCode,
      String label,
      long caseCount,
      BigDecimal returnQty,
      BigDecimal returnAmount,
      BigDecimal share) {}

  /**
   * 归因口径的原因分布（11.1.2.1 的原因分类树）。
   *
   * <p>⚠️ 与 {@link ReturnReasonSlice} 是<b>两个不同的轴</b>：
   * 前者是售后单上的<b>七类内联词表</b>（每单必填，口径完整）；
   * 后者是<b>当前生效的归因</b>（可能没归因，所以必须和覆盖率一起读）。
   *
   * <p>⚠️ 一单可以归多个原因（多选），所以各分类的 {@code caseCount} 之和
   * <b>大于</b>归因单数 —— 这是多选归因的定义，不是重复计数。
   * {@code primaryCaseCount} 是「该分类被选为主因」的单数，两列分别读。
   */
  public record ReturnAttributionSlice(
      String currency,
      String categoryCode,
      String categoryName,
      String rootKind,
      long caseCount,
      long primaryCaseCount,
      BigDecimal weightSum,
      BigDecimal share) {}

  // ================================================================ 响应：下钻

  /** 维度下钻的一行。 */
  public record ReturnBreakdownRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      String caliber,
      long caseCount,
      BigDecimal returnQty,
      @Schema(description = "退货金额；批次维度不报金额（为 null）", nullable = true)
          BigDecimal returnAmount,
      long salesOrderCount,
      @Schema(description = "同期销售额；批次维度没有销售额分母（为 null）", nullable = true)
          BigDecimal salesAmount,
      @Schema(description = "退货率（比值，标度 4；分母为 0 时为 null）", nullable = true)
          BigDecimal returnRate,
      @Schema(description = "实收件数（只有批次维度有）", nullable = true) BigDecimal receivedQty,
      @Schema(description = "关联 SKU 同期销量件数（只有批次维度有）", nullable = true)
          BigDecimal salesQty,
      long linkedSkuCount,
      String note) {}

  /** 维度下钻报表。 */
  public record ReturnBreakdownReport(
      OffsetDateTime generatedAt,
      String windowNote,
      String dimension,
      String currency,
      String caliber,
      List<ReturnBreakdownRow> rows,
      @Schema(description = "退货金额合计；**本期出现多个币种时为 null**（跨币种不可合计）", nullable = true)
          BigDecimal totalReturnAmount,
      @Schema(description = "销售额合计；**本期出现多个币种时为 null**（跨币种不可合计）", nullable = true)
          BigDecimal totalSalesAmount,
      @Schema(description = "总体退货率（比值，标度 4）；批次维度无金额口径、跨币种时为 null", nullable = true)
          BigDecimal totalReturnRate,
      long totalCaseCount,
      @Schema(description = "行级金额覆盖率（百分比）；只有 SKU 与类目维度有", nullable = true)
          BigDecimal lineAmountCoverage,
      String note) {}

  /** 趋势上的一个时间桶。 */
  public record ReturnTrendPoint(
      String bucket,
      OffsetDateTime bucketStart,
      String currency,
      long caseCount,
      BigDecimal returnQty,
      BigDecimal returnAmount,
      BigDecimal salesAmount,
      @Schema(description = "该桶的退货率（比值，标度 4；分母为 0 时为 null）", nullable = true)
          BigDecimal returnRate) {}

  /** 退货金额与退货率趋势。 */
  public record ReturnTrendReport(
      OffsetDateTime generatedAt,
      String windowNote,
      String granularity,
      String currency,
      List<ReturnTrendPoint> points,
      String note) {}

  // ================================================================ 响应：异常

  /** 异常识别的一条。 */
  public record ReturnAnomalyRow(
      String code,
      String title,
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      String severity,
      BigDecimal metricValue,
      BigDecimal baselineValue,
      BigDecimal delta,
      String note) {}

  /**
   * 异常识别的阈值 —— <b>逐条报出来</b>。
   *
   * <p>⚠️ 阈值不报出来的话，「这个异常是怎么判出来的」就答不出来，
   * 而一张说不清判据的异常清单，没人会当真去处理。
   */
  public record ReturnAnomalyThresholds(
      String currency,
      String source,
      BigDecimal rateMultiplier,
      BigDecimal rateMinSalesShare,
      BigDecimal rejectionGapPp,
      long rejectionMinCases,
      BigDecimal batchMultiplier,
      BigDecimal batchMinQty,
      BigDecimal reasonShare,
      long reasonMinCases,
      BigDecimal trendGapPp) {}

  /** 异常识别报表。 */
  public record ReturnAnomalyReport(
      OffsetDateTime generatedAt,
      String windowNote,
      String currency,
      ReturnAnomalyThresholds thresholds,
      List<ReturnAnomalyRow> rows,
      String note) {}

  // ================================================================ 响应：快照

  /**
   * 一行快照。
   *
   * <p>⚠️ 拒退与退款相关的三列是<b>可空</b>的：它们是**总体口径**的数字，
   * 维度行里**不适用** —— 填 0 会被读成「这一行没有拒退 / 没有退款」，
   * 而真相是「这一行是维度明细，拒退率是总体口径，另有一行 OVERVIEW」。
   */
  public record ReturnAnalysisSnapshotRow(
      UUID id,
      String snapshotNo,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      String currency,
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String caliber,
      long caseCount,
      BigDecimal returnQty,
      BigDecimal returnAmount,
      BigDecimal salesAmount,
      @Schema(nullable = true) BigDecimal returnRate,
      @Schema(nullable = true) Long rejectedCount,
      @Schema(nullable = true) Long decidedCount,
      @Schema(nullable = true) BigDecimal rejectionRate,
      @Schema(nullable = true) Long refundCount,
      @Schema(nullable = true) BigDecimal refundTotal,
      @Schema(nullable = true) BigDecimal avgRefundAmount,
      @Schema(nullable = true) BigDecimal avgResolutionHours,
      String anomalyCode,
      String anomalyNote,
      String basisNote,
      OffsetDateTime computedAt) {}

  /** 快照分页。 */
  public record ReturnAnalysisSnapshotPage(
      List<ReturnAnalysisSnapshotRow> rows, long total, int page, int size) {}

  // ================================================================ 响应：口径

  /** 一行口径。 */
  public record ReturnAnalysisBasisRow(
      UUID id,
      String basisNo,
      String basisName,
      String currency,
      boolean includeRefundOnly,
      BigDecimal rateAlertMultiplier,
      BigDecimal rateAlertMinSalesShare,
      BigDecimal rejectionAlertGapPp,
      long rejectionAlertMinCases,
      BigDecimal batchAlertMultiplier,
      BigDecimal batchAlertMinQty,
      BigDecimal reasonAlertShare,
      long reasonAlertMinCases,
      BigDecimal trendAlertGapPp,
      String basisStatus,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      String sourceNote,
      long version,
      OffsetDateTime createdAt,
      @Schema(nullable = true) UUID supersededBy,
      @Schema(nullable = true) OffsetDateTime supersededAt,
      @Schema(nullable = true) String supersedeReason) {}

  /** 口径分页。 */
  public record ReturnAnalysisBasisPage(
      List<ReturnAnalysisBasisRow> rows, long total, int page, int size) {}

  // ================================================================ 工具

  /**
   * 构造一个「空但不为 null」的列表。
   *
   * <p>⚠️ 响应里的列表<b>不许是 null</b>：前端拿到 null 就得自己判空，
   * 而「这张报表没有异常」与「异常字段没接上」在 JSON 里长得一模一样。
   */
  static <T> List<T> emptyIfNull(List<T> rows) {
    return rows == null ? new ArrayList<>() : rows;
  }
}
