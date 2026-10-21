package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.4.2.1 促销效果分析 的传输模型。
 *
 * <p>蓝图：「促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，与活动前的基期对比。
 * 输入：促销数据；历史基线；利润数据。输出：效果报表 + 增量估算 + ROI。
 * 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
 * <strong>促销期间毛利可能大幅下滑</strong>，评估必须看毛利而不是销售额。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>毛利是硬指标，不是附注</b>。{@code grossProfit} / {@code grossMarginRate} 与
 *       {@code sales} 并列出现在每一行 —— 界面上必须能同时看到销售额与毛利，
 *       否则「促销期间毛利大幅下滑」这个事实根本看不见。</li>
 *   <li><b>不可比不是 0</b>。缺成本快照 ⇒ 毛利为 {@code null}（不可比），
 *       缺基准价 ⇒ 补贴成本为 {@code null}。0 会被渲染成「毛利是 0」/「没花钱」，
 *       那与「拿不到」在运营决策上完全相反。</li>
 *   <li><b>分子分母各自落库</b>。{@code roiNumerator} / {@code roiDenominator} /
 *       {@code incrementGrossProfit} 都在响应里，事后谁都能自己复核，
 *       不必相信服务端算的比率。</li>
 *   <li><b>基期口径随行返回</b>。{@code baselineFromAt} / {@code baselineToAt} /
 *       {@code baselineDays} 一起给 —— 复盘时必须知道「当时比的是哪一段」。</li>
 *   <li><b>判定看毛利</b>。{@code incrementVerdict} 的判据是增量毛利，
 *       {@code marginAlert} 专门标「放量不赚」；销售额增量只作并列指标。</li>
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：毛利率、ROI 都要
 * 直接进决策，不能让二进制浮点误差出现在「这单赚不赚钱」上。
 */
public final class AdPromotionAnalysisModels {
  private AdPromotionAnalysisModels() {}

  // ================================================================ 能力声明

  public record AdPromotionAnalysisCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 本模块的四个**已交付**维度：成本与售价快照（跨服务纪律的落点）、
   * 促销期销量/销售额（平台回报口径）、毛利（自带算式，不依赖 13.4）、增量与 ROI。
   *
   * <p>🔴 两个**不可比**维度的区别必须写清：<b>平台归因</b>（平台侧把成交归因到
   * 具体促销的接口未交付 ⇒ 本模块用「同商品活动前 vs 活动期」对比，
   * <b>不是</b>平台口径的促销归因）与 <b>13.4 利润</b>（下游利润报表未交付）。
   */
  public record AdPromotionAnalysisCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdPromotionAnalysisCapabilityDimension> dimensions,
      @Schema(description = "增量判定取值") List<String> incrementVerdicts,
      @Schema(description = "报表维度取值") List<String> reportDimensions,
      @Schema(description = "报表排序取值") List<String> sorts,
      @Schema(description = "🔴 销量与销售额口径说明") String salesNote,
      @Schema(description = "🔴 毛利口径说明") String marginNote,
      @Schema(description = "🔴 基期对比口径说明") String baselineNote,
      @Schema(description = "🔴 增量判定口径说明") String incrementNote,
      @Schema(description = "🔴 补贴成本口径说明") String subsidyNote,
      @Schema(description = "🔴 ROI 口径说明") String roiNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record AdPromotionAnalysisOption(
      @Schema(description = "取值") String code, @Schema(description = "显示名") String label) {}

  /** 促销下拉项（带 id —— 分析与筛选都要的是促销 id，不只是编码）。 */
  public record AdPromotionAnalysisPromotionOption(
      @Schema(description = "促销 id") String id,
      @Schema(description = "促销编码") String promotionCode,
      @Schema(description = "促销名称") String promotionName,
      @Schema(description = "促销类型") String promotionType,
      @Schema(description = "币种") String currency,
      @Schema(description = "开始时刻") OffsetDateTime startAt,
      @Schema(description = "结束时刻") OffsetDateTime endAt,
      @Schema(description = "状态") String status) {}

  public record AdPromotionAnalysisOptions(
      @Schema(description = "增量判定下拉项") List<AdPromotionAnalysisOption> incrementVerdicts,
      @Schema(description = "维度下拉项") List<AdPromotionAnalysisOption> dimensions,
      @Schema(description = "排序下拉项") List<AdPromotionAnalysisOption> sorts,
      @Schema(description = "促销下拉项") List<AdPromotionAnalysisPromotionOption> promotions,
      @Schema(description = "平台下拉项") List<AdPromotionAnalysisPlatformOption> platforms,
      @Schema(description = "店铺下拉项") List<AdPromotionAnalysisShopOption> shops,
      @Schema(description = "币种下拉项") List<String> currencies,
      @Schema(description = "🔴 销量与销售额口径说明") String salesNote,
      @Schema(description = "🔴 毛利口径说明") String marginNote,
      @Schema(description = "🔴 基期对比口径说明") String baselineNote) {}

  public record AdPromotionAnalysisPlatformOption(
      @Schema(description = "平台 id") String id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record AdPromotionAnalysisShopOption(
      @Schema(description = "店铺编码") String code, @Schema(description = "店铺名称") String name) {}

  // ================================================================ 成本与售价快照

  /**
   * 成本与售价快照（**跨服务纪律的物理落点**）。
   *
   * <p>🔴 catalog 的成本与售价**被复制进 channel 自己的表**，channel 永不 join catalog。
   * 本表带 {@code capturedAt} 与 {@code sourceRef}：事后能回答「这个成本是**什么时候**、
   * 按**哪条口径**登记的」，而不是「现在 catalog 里是多少」（后者早就变了）。
   */
  public record AdPromotionAnalysisCostSnapshotRow(
      @Schema(description = "快照 id") UUID id,
      @Schema(description = "促销 id") UUID promotionId,
      @Schema(description = "促销编码") String promotionCode,
      @Schema(description = "促销名称") String promotionName,
      @Schema(description = "商品 id") UUID listingId,
      @Schema(description = "商品标题") String listingTitle,
      @Schema(description = "商品编码") String externalItemId,
      @Schema(description = "币种") String currency,
      @Schema(description = "单位成本快照") BigDecimal unitCost,
      @Schema(
              description =
                  "促销前基准价快照；null = 没登记 ⇒ **百分比促销**的补贴不可比（固定额促销不需要它）")
          BigDecimal basePrice,
      @Schema(description = "来源说明") String sourceRef,
      @Schema(description = "快照时刻") OffsetDateTime capturedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "版本") long version,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  public record AdPromotionAnalysisCostSnapshotPage(
      @Schema(description = "本页数据") List<AdPromotionAnalysisCostSnapshotRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /** 登记成本与售价快照（写入口 —— 成本从 catalog 复制进来的唯一通道）。 */
  public record SaveCostSnapshotRequest(
      @Schema(description = "促销 id") @NotNull UUID promotionId,
      @Schema(description = "商品 id") @NotNull UUID listingId,
      @Schema(description = "单位成本；catalog 侧 landed cost / sku cost 口径")
          @NotNull
          @DecimalMin(value = "0", message = "单位成本不能为负")
          BigDecimal unitCost,
      @Schema(description = "促销前基准价；留空 = 没登记 ⇒ 补贴成本不可比")
          @DecimalMin(value = "0", message = "基准价不能为负")
          BigDecimal basePrice,
      @Schema(description = "币种；留空 = 取促销的币种") String currency,
      @Schema(description = "来源说明（catalog 的哪个口径 / 哪条记录）") @Size(max = 200)
          String sourceRef,
      @Schema(description = "🔴 快照时刻：成本是随时间变的，不记时刻就不知道这个数字说的是哪天")
          OffsetDateTime capturedAt,
      @Schema(description = "备注") String note) {}

  // ================================================================ 分析运行

  /** 分析运行结果：算了哪些、跳过了哪些、为什么跳过。 */
  public record AdPromotionAnalysisRunRow(
      @Schema(description = "运行 id") UUID id,
      @Schema(description = "状态：SUCCESS / PARTIAL / FAILED") String status,
      @Schema(description = "目标促销 id；null = 批量") UUID promotionId,
      @Schema(description = "算出来的促销数") int analyzedCount,
      @Schema(description = "跳过的促销数") int skippedCount,
      @Schema(description = "跳过明细（jsonb 数组：promotionId / promotionCode / reason）")
          String skipped,
      @Schema(description = "数据截至") OffsetDateTime dataAsOf,
      @Schema(description = "口径定义") String metricDefinitionNote,
      @Schema(description = "备注") String note,
      @Schema(description = "运行时刻") OffsetDateTime createdAt) {}

  /** 发起一次分析。 */
  public record RunAnalysisRequest(
      @Schema(description = "促销 id；留空 = 跑全部处于活动期的促销") UUID promotionId,
      @Schema(description = "备注") String note) {}

  // ================================================================ 效果报表

  /**
   * 效果报表行。
   *
   * <p>🔴 每一行都**同时**给出销售额与毛利（{@code sales} 与 {@code grossProfit}），
   * 且基期对照（{@code baseline*}）与增量（{@code increment*}）都在 ——
   * 「促销期间毛利大幅下滑」这个判断要能在同一行里做出来。
   */
  public record AdPromotionAnalysisRow(
      @Schema(description = "促销 id") UUID promotionId,
      @Schema(description = "促销编码") String promotionCode,
      @Schema(description = "促销名称") String promotionName,
      @Schema(description = "促销类型") String promotionType,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "商品 id；活动维度汇总时为空") UUID listingId,
      @Schema(description = "商品标题；活动维度汇总时为空") String listingTitle,
      @Schema(description = "商品编码；活动维度汇总时为空") String externalItemId,
      @Schema(description = "币种") String currency,
      @Schema(description = "促销期窗口起") OffsetDateTime periodFromAt,
      @Schema(description = "促销期窗口止") OffsetDateTime periodToAt,
      @Schema(description = "促销期天数") int periodDays,
      @Schema(description = "促销期内有平台快照的天数（覆盖度）") int periodCoveredDays,
      @Schema(description = "促销期销量") BigDecimal promoUnits,
      @Schema(description = "促销期销售额") BigDecimal promoSales,
      @Schema(description = "促销期成本；null = 不可比") BigDecimal promoCost,
      @Schema(description = "🔴 促销期毛利额；null = 不可比（缺成本快照）") BigDecimal promoGrossProfit,
      @Schema(description = "🔴 促销期毛利率；null = 不可比") BigDecimal promoGrossMarginRate,
      @Schema(description = "基期窗口起") OffsetDateTime baselineFromAt,
      @Schema(description = "基期窗口止") OffsetDateTime baselineToAt,
      @Schema(description = "基期天数（与促销期等长）") int baselineDays,
      @Schema(description = "基期内有平台快照的天数（覆盖度）") int baselineCoveredDays,
      @Schema(description = "基期销量") BigDecimal baselineUnits,
      @Schema(description = "基期销售额") BigDecimal baselineSales,
      @Schema(description = "基期成本；null = 不可比") BigDecimal baselineCost,
      @Schema(description = "基期毛利额；null = 不可比") BigDecimal baselineGrossProfit,
      @Schema(description = "基期毛利率；null = 不可比") BigDecimal baselineGrossMarginRate,
      @Schema(description = "增量销量（总量口径）") BigDecimal incrementUnits,
      @Schema(description = "增量销售额（总量口径）") BigDecimal incrementSales,
      @Schema(description = "🔴 增量毛利（总量口径）；null = 不可比") BigDecimal incrementGrossProfit,
      @Schema(description = "单件让利；null = 该促销类型无可折算价格系数或缺基准价")
          BigDecimal subsidyUnit,
      @Schema(description = "🔴 补贴成本 = 单件让利 × 实际销量；null = 不可比")
          BigDecimal subsidyCost,
      @Schema(description = "🔴 ROI 分子（增量毛利）") BigDecimal roiNumerator,
      @Schema(description = "🔴 ROI 分母（补贴成本）") BigDecimal roiDenominator,
      @Schema(description = "🔴 ROI；null = 不可比（分母 ≤ 0）") BigDecimal roiValue,
      @Schema(description = "🔴 增量判定（判据是增量毛利）") String incrementVerdict,
      @Schema(description = "🔴 放量不赚告警：销售额涨了但毛利率跌了") boolean marginAlert,
      @Schema(description = "参与汇总的商品数") int productCount,
      @Schema(description = "其中毛利可比的商品数（其余缺成本快照）") int grossProfitProductCount,
      @Schema(description = "其中毛利与补贴均可比的商品数") int comparableProductCount,
      @Schema(description = "数据截至") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 口径定义（随行返回，跨部门沟通用同一份）")
          String metricDefinitionNote) {}

  public record AdPromotionAnalysisPage(
      @Schema(description = "本页数据") List<AdPromotionAnalysisRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 口径定义") String metricDefinitionNote) {}

  // ================================================================ 导出留痕

  public record AdPromotionAnalysisExportRow(
      @Schema(description = "导出 id") UUID id,
      @Schema(description = "导出人") UUID requestedBy,
      @Schema(description = "维度：PROMOTION / PRODUCT") String dimension,
      @Schema(description = "促销筛选") UUID promotionId,
      @Schema(description = "平台筛选") UUID platformId,
      @Schema(description = "店铺筛选") String shopCode,
      @Schema(description = "币种筛选") String currency,
      @Schema(description = "判定筛选") String incrementVerdict,
      @Schema(description = "起始日") java.time.LocalDate dateFrom,
      @Schema(description = "截止日") java.time.LocalDate dateTo,
      @Schema(description = "导出行数") int rowCount,
      @Schema(description = "数据截至") OffsetDateTime dataAsOf,
      @Schema(description = "口径定义") String metricDefinitionNote,
      @Schema(description = "导出时刻") OffsetDateTime createdAt) {}

  public record AdPromotionAnalysisExportPage(
      @Schema(description = "本页数据") List<AdPromotionAnalysisExportRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /** 登记一次导出（留痕：谁、什么时候、什么筛选条件、导出了多少行）。 */
  public record AdPromotionAnalysisExportRequest(
      @Schema(description = "维度：PROMOTION / PRODUCT") @NotBlank String dimension,
      @Schema(description = "促销筛选") UUID promotionId,
      @Schema(description = "平台筛选") UUID platformId,
      @Schema(description = "店铺筛选") String shopCode,
      @Schema(description = "币种筛选") String currency,
      @Schema(description = "判定筛选") String incrementVerdict,
      @Schema(description = "起始日") java.time.LocalDate dateFrom,
      @Schema(description = "截止日") java.time.LocalDate dateTo) {}

  // ================================================================ 查询参数

  /** 效果报表的查询条件。 */
  public record AdPromotionAnalysisQuery(
      @Schema(description = "维度：PROMOTION（活动）/ PRODUCT（商品）") String dimension,
      @Schema(description = "排序键") String sort,
      @Schema(description = "促销筛选") UUID promotionId,
      @Schema(description = "平台筛选") UUID platformId,
      @Schema(description = "店铺筛选") String shopCode,
      @Schema(description = "币种筛选") String currency,
      @Schema(description = "判定筛选") String incrementVerdict,
      @Schema(description = "仅看放量不赚") Boolean marginAlertOnly,
      @Schema(description = "关键字（促销编码 / 名称 / 商品标题）") String keyword,
      @Schema(description = "起始日") java.time.LocalDate dateFrom,
      @Schema(description = "截止日") java.time.LocalDate dateTo,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}
}
