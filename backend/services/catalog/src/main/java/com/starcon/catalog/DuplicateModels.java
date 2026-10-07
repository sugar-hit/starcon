package com.starcon.catalog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 3.9.1.1 重复 SKU 识别的传输对象。
 *
 * <p>蓝图的输出是「<b>重复候选清单（相似度、重叠维度、影响引用数）；确认合并/保留决定</b>」，
 * 所以这里的形状围绕两件事：
 *
 * <ul>
 *   <li>{@link DuplicateCandidateRow} —— 候选清单的一行：那一对 SKU、相似度、命中了几条线、
 *       两侧各自的 catalog 引用数，以及已登记的决定。
 *   <li>{@link DuplicateCandidateDetail} —— 单个候选的详情：两侧商品的完整身份与规格组合、
 *       逐维度得分与<b>可核对的证据</b>，供人工判断该不该合并。
 * </ul>
 *
 * <p><b>决定与执行分开</b>：本模块只登记决定（{@link DuplicateDecisionInput}），
 * 执行合并是 3.9.1.2。{@link DuplicateDecisionRow} 因此把
 * {@code primaryVersion} / {@code secondaryVersion} 原样带给下游 ——
 * 它们是登记那一刻的 {@code expected_version}，对上才允许合并。
 *
 * <p><b>引用数是 catalog 侧的口径</b>（条码 / 素材 / 属性值 / 报价 / 套装组件 / BOM 组件 /
 * 包装 / 安全属性 / 单位换算快照）。04 映射、07 库存、09 订单的引用在别的服务的库里，
 * 本模块不跨库读（模块边界纪律），所以那部分要由 3.9.1.2 走各自授权 API 补齐。
 */
final class DuplicateModels {
  private DuplicateModels() {}

  // ================================================================ 扫描

  /**
   * 一次扫描运行。
   *
   * @param minSimilarity 本次判定阈值（{@code NUMERIC(5,4)}，不是 float）
   * @param scopeApplied 扫描被范围收窄了 → <b>可能漏掉跨范围的重复</b>，页面必须显式提示。
   *     「0 个候选」在收窄扫描下和全量扫描下含义完全不同，不提示就等于报了个假的干净结论
   * @param candidateCount / signalCount 扫描那一刻的快照。候选行在 SKU 被硬删除时随之删除
   *     （停用不影响），所以 {@code candidateCount} 是这份扫描的**上界**，要当前值请读候选表
   */
  public record DuplicateScanRow(
      UUID id,
      BigDecimal minSimilarity,
      String scopeCategory,
      String scopeBrand,
      boolean scopeApplied,
      int scannedSkuCount,
      int scannedSpuCount,
      int candidateCount,
      int signalCount,
      Instant startedAt,
      Instant finishedAt,
      long durationMs,
      UUID actorId,
      int pendingCount,
      int mergeCount,
      int keepCount) {}

  public record DuplicateScanPage(List<DuplicateScanRow> items, int page, int size, long total, int totalCandidates) {}

  /**
   * 一个维度的命中分布（详情页与概览共用）。
   *
   * @param pairCount 命中该维度的候选对数（<b>去重后的对数</b>：同一对命中两条线，
   *     在两个维度各计一次，但 {@code pairCount} 的语义是「有几对涉及这条线」，
   *     而候选清单的行数仍然是 {@code overlapDimensionCount} 那一份）
   * @param label 给运营看的中文维度名
   */
  public record DuplicateDimensionStat(String dimension, String label, int pairCount, BigDecimal topScore) {}

  /** 扫描总览：最近一次扫描 + 四个维度的分布 + 待确认数。 */
  public record DuplicateOverview(
      DuplicateScanRow latestScan,
      List<DuplicateDimensionStat> dimensions,
      int pendingCount,
      int mergeCount,
      int keepCount,
      int totalCandidates,
      int scannedSkuCount,
      String note) {}

  /** 发起扫描的提交体。 */
  public record DuplicateScanInput(
      @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minSimilarity,
      @Size(max = 100) String category,
      @Size(max = 100) String brand) {}

  // ================================================================ 候选

  /**
   * 候选清单的一行。
   *
   * @param similarity 总体相似度（取各维度得分的最大值，见 V36 文件头）
   * @param dimensions 命中了哪几条线（蓝图的「重叠维度」），逐条带标签与得分
   * @param leftReferenceCount / rightReferenceCount 两侧各自的 catalog 引用数（蓝图「影响引用数」）
   * @param decision MERGE / KEEP；{@code null} = 还没人判
   */
  public record DuplicateCandidateRow(
      UUID id,
      UUID scanId,
      UUID leftSkuId,
      String leftSkuCode,
      String leftSkuName,
      String leftSpuCode,
      String leftSpuName,
      UUID rightSkuId,
      String rightSkuCode,
      String rightSkuName,
      String rightSpuCode,
      String rightSpuName,
      BigDecimal similarity,
      int overlapDimensionCount,
      List<DuplicateSignalView> dimensions,
      int leftReferenceCount,
      int rightReferenceCount,
      String reason,
      String decision,
      String decisionLabel,
      UUID primarySkuId,
      Instant decidedAt) {}

  public record DuplicateCandidatePage(
      List<DuplicateCandidateRow> items, int page, int size, long total, DuplicateScanRow scan) {}

  /** 一条维度信号：得分 + 可核对的证据。 */
  public record DuplicateSignalView(
      String dimension, String label, BigDecimal score, String evidence) {}

  /**
   * 单个候选的详情：两侧商品的完整身份 + 逐维度信号 + 已登记的决定。
   *
   * @param leftVariantCombination / rightVariantCombination 按轴值现算的规格组合签名。
   *     为空串表示这个 SKU 一个轴值都解析不出来（导入的占位签名 SKU 常见），
   *     这种 SKU 不参与规格组合维度，但**照样展示出来** —— 运营需要知道它为什么没被这一维命中
   * @param leftBarcodeValues / rightBarcodeValues 全部条码（{@code sku.barcode} + 物料标识表）
   */
  public record DuplicateCandidateDetail(
      DuplicateCandidateRow candidate,
      String leftVariantCombination,
      String rightVariantCombination,
      List<String> leftBarcodeValues,
      List<String> rightBarcodeValues,
      List<DuplicateReferenceBreakdown> leftReferences,
      List<DuplicateReferenceBreakdown> rightReferences,
      List<DuplicateSignalView> signals,
      DuplicateDecisionRow decision,
      String nextStepHint) {}

  /** 引用数的**逐项拆分**：条码 2 个、素材 1 张、报价 3 条…… */
  public record DuplicateReferenceBreakdown(String dimension, String label, int count) {}

  // ================================================================ 决定

  /**
   * 登记人工决定。
   *
   * @param decision MERGE（判成重复，要合并）/ KEEP（判成不是同一个，保留两条）
   * @param primarySkuId {@code decision=MERGE} 时必填，且必须是该候选两侧之一
   * @param expectedVersion 乐观锁：本次提交基于哪个版本的候选决定（首次登记传 0）
   */
  public record DuplicateDecisionInput(
      @NotBlank String decision,
      UUID primarySkuId,
      @Size(max = 1000) String note,
      Long expectedVersion) {}

  /**
   * 已登记的决定。
   *
   * @param primaryVersion / secondaryVersion 登记那一刻两个 SKU 的 {@code sku.version} ——
   *     即 3.9.1.2 说的 {@code expected_version}。执行合并前必须带着它比对，
   *     对不上说明登记之后商品被人改过，合并的前提已经不成立
   */
  public record DuplicateDecisionRow(
      UUID id,
      UUID candidateId,
      UUID scanId,
      String decision,
      String decisionLabel,
      UUID primarySkuId,
      String primarySkuCode,
      UUID secondarySkuId,
      String secondarySkuCode,
      Long primaryVersion,
      Long secondaryVersion,
      String note,
      long version,
      UUID decidedBy,
      Instant decidedAt,
      String executionHint) {}
}