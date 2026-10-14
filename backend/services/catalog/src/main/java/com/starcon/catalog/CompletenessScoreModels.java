package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 3.9.2.1 完整度评分的请求与响应记录。
 *
 * <p><b>为什么单独成文件而不是并进 {@code CatalogModels}</b>：{@code CatalogModels} 是 并行车道都会追加的公共文件，且它<b>不在</b>
 * {@code merge-union} 驱动器的按键求并范围内 （驱动器只覆盖 {@code packages/api-contracts} 与台账）。本模块的记录放进公共文件会让
 * 两条车道在同一处各追加一段，行级三方合并必然冲突。模块自成一个文件则天然无交集， 与 {@code PhysicalSafetyModels} / {@code
 * CategoryAttributeRequirementForms} 同一做法。
 *
 * <p>蓝图 3.9.2.1： 「给每个 SPU/SKU 打主数据完整度分：必填属性、类目必填、图片数量与尺寸、报关属性 （HS 编码、申报品名）、包装尺寸重量、认证；按分数分档，驱动补全清单。
 * 输出：完整度评分卡 + 缺失项清单 + 批量补全入口。」
 */
public final class CompletenessScoreModels {
  private CompletenessScoreModels() {}

  // ================================================================ 词表与阈值

  /** 词表选项：前端下拉与阈值展示的数据源。与服务层枚举同源（规则 R8）。 */
  public record CompletenessOptions(
      List<String> dimensions,
      List<CompletenessScoreRules.Option> dimensionOptions,
      List<String> grades,
      List<CompletenessScoreRules.Option> gradeOptions,
      List<String> ownerTypes,
      List<String> certificationStatuses,
      List<CompletenessScoreRules.Option> certificationStatusOptions,
      Integer minImageCount,
      Integer minPrimaryImageEdgePx,
      String listingGateScore,
      String customsGateScore,
      String note) {}

  // ================================================================ 评分卡

  /**
   * 单个维度的得分（评分卡的一行）。
   *
   * <p>{@code ratio} 与 {@code total}/{@code satisfied} 的关系是 {@code satisfied /
   * total}，<b>但是分维度给而不是只给总分</b>： 「80 分」答不出「该先补哪一块」，而补全清单要按维度组织（规则 R3）。
   *
   * <p>{@code weight} 是该维度在总分里的权重（六项合计 1.0000）。 {@code blockedFor} 标出这条维度卡住哪条链（LISTING / CUSTOMS /
   * 空 = 两条都不卡）。
   */
  public record CompletenessDimension(
      String dimension,
      String dimensionLabel,
      BigDecimal weight,
      long satisfied,
      long total,
      BigDecimal ratio,
      boolean complete,
      List<String> blockedFor) {}

  /**
   * 一条检查项 —— 评分是<b>可解释</b>的最小单位。
   *
   * <p>刻意给 {@code subjectLabel} 而不是只给一个计数：「缺 3 条必填属性」没法让人去补， 而「缺 颜色 / 尺码 /
   * 材质」可以直接点进那三个属性。缺失项清单按它分组。
   *
   * <p>{@code code} 是稳定机器码（前端与 3.8 批量导入按它归集缺失项， 文案可以改而 {@code code} 不变 —— 改了 {@code code}
   * 旧清单就对不上新清单）。
   */
  public record CompletenessCheck(
      String code,
      String dimension,
      String subjectType,
      String subjectLabel,
      String detail,
      String remedy,
      boolean satisfied) {}

  /**
   * 完整度评分卡：蓝图「输出」那一行的读出口。
   *
   * <p><b>{@code score} 是 0..1 的比值，不是百分数</b>（V37 第一节续）： {@code scorePercent} 是给界面看的百分数串。两个都给出是因为
   * 前端只要一个的话迟早会在某处除以 100。
   *
   * <p>{@code listingReady} 与 {@code customsReady} 是<b>两个独立结论</b>（规则 R3）： 一个带图有属性但没有 HS
   * 编码的商品完全可能刊登就绪、清关未就绪， 合并成一个「完整度 78%」之后运营就看不出自己被哪一条链拦住。
   */
  public record CompletenessScorecard(
      String ownerType,
      UUID ownerId,
      String ownerCode,
      String ownerName,
      String ownerStatus,
      String categoryCode,
      String categoryName,
      BigDecimal score,
      String scorePercent,
      String grade,
      String gradeLabel,
      List<CompletenessDimension> dimensions,
      List<CompletenessCheck> checks,
      List<CompletenessCheck> missing,
      long missingCount,
      long skuCount,
      boolean listingReady,
      boolean customsReady,
      List<String> listingBlockers,
      List<String> customsBlockers,
      String evaluatedAt,
      String note) {}

  /** 评分卡在列表里的轻量投影（不含 checks —— 一个 200 变体的 SPU 会有上千条检查项）。 */
  public record CompletenessRow(
      String ownerType,
      UUID ownerId,
      UUID spuId,
      UUID skuId,
      String ownerCode,
      String ownerName,
      String ownerStatus,
      String categoryName,
      BigDecimal score,
      String scorePercent,
      String grade,
      String gradeLabel,
      long missingCount,
      long skuCount,
      boolean listingReady,
      boolean customsReady) {}

  /**
   * 评分列表的分页体。
   *
   * <p>{@code gradeCounts} 是<b>全量</b>分档计数（不受分档筛选影响）—— 治理页顶部那四个数字必须回答"全店各档有多少"，而分页体里的计数在筛选后
   * 就只反映当前这一页，那不是治理数字。
   */
  public record CompletenessPage(
      List<CompletenessRow> items,
      long total,
      int page,
      int size,
      long listingReadyCount,
      long customsReadyCount,
      long blockedCount,
      List<CompletenessScoreRules.GradeCount> gradeCounts,
      String note) {}

  /** 概览：台账顶部的治理数字。 */
  public record CompletenessOverview(
      long spuCount,
      long skuCount,
      long listingReadyCount,
      long customsReadyCount,
      long listingBlockedCount,
      long customsBlockedCount,
      long bothBlockedCount,
      List<CompletenessScoreRules.GradeCount> gradeCounts,
      List<CompletenessScoreRules.DimensionGap> dimensionGaps,
      String note) {}

  // ================================================================ 缺失项清单（批量补全入口）

  /** 列表查询条件。全部可空，null = 不按该项筛选。 */
  public record CompletenessQuery(
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String ownerType,
      @Schema(nullable = true) String grade,
      @Schema(nullable = true) String dimension,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) String categoryName,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Boolean listingReadyOnly,
      @Schema(nullable = true) Boolean missingOnly,
      @Schema(nullable = true) String orderBy,
      @Schema(nullable = true) Boolean ascending,
      @Schema(nullable = true) @PositiveOrZero Integer page,
      @Schema(nullable = true) @PositiveOrZero Integer size) {}

  /** 缺失项（批量补全清单的一行）：按缺失码聚合，列出受影响的全部主体。 */
  public record CompletenessGap(
      String code,
      String dimension,
      String dimensionLabel,
      String subjectType,
      String detail,
      String remedy,
      long affectedCount,
      List<String> sampleOwners,
      String nextEntry) {}

  /** 缺失项清单的分页体。{@code byCode} 是**全量**聚合（不受分页影响）。 */
  public record CompletenessGapPage(
      List<CompletenessGap> items,
      long total,
      int page,
      int size,
      long affectedOwnerCount,
      List<CompletenessScoreRules.DimensionGap> byDimension,
      String note) {}

  // ================================================================ 评估快照

  /**
   * 批量评估的请求体（蓝图的「批量补全入口」）。
   *
   * <p>{@code ownerType} + {@code ownerIds} 而不是一批评分结果： 服务端**现算**每一条（规则 R1），调用方无法伪造分数 ——
   * 一个"把分数传进来让我存一下"的端点等于给完整度发了一张可自填的通行证， 而完整度直接决定能否刊登与能否清关。
   */
  public record CompletenessEvaluationInput(
      @Schema(nullable = true) @NotNull String ownerType,
      @Schema(nullable = true) @NotNull @Size(max = 500) List<UUID> ownerIds,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 一条评估快照（读出口，省略两个 jsonb 的原始串）。 */
  public record CompletenessEvaluation(
      UUID id,
      String ownerType,
      UUID ownerId,
      String ownerCode,
      String ownerName,
      BigDecimal score,
      String grade,
      String gradeLabel,
      boolean listingReady,
      boolean customsReady,
      long missingCount,
      List<CompletenessScoreRules.DimensionGap> dimensionGaps,
      List<CompletenessCheck> missing,
      UUID evaluatedBy,
      String evaluatedAt,
      String note,
      long version) {}

  public record CompletenessEvaluationPage(
      List<CompletenessEvaluation> items, long total, int page, int size, String note) {}

  /** 批量评估的结果：逐条成功与失败都带出来，不做"整体成功/失败"。 */
  public record CompletenessEvaluationResult(
      int requested,
      int succeeded,
      int failed,
      List<CompletenessEvaluation> evaluations,
      List<CompletenessScoreRules.EvaluationFailure> failures,
      String note) {}

  // ================================================================ 报关属性（catalog 侧声明数据）

  /**
   * SKU 报关属性。
   *
   * <p>{@code hsCode} / {@code declaredName} 可空但**会被评分判为缺失**： 刻意不是 {@code NOT NULL} ——
   * 「还没登记」与「登记了但没填全」是两种缺失原因， 缺失项文案不同（一个该去建档，一个该去补字段），而 NOT NULL 会让前者无法表达。
   */
  public record CustomsDeclaration(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String hsCode,
      String declaredName,
      String originCountry,
      String legalUnit,
      List<String> declarationElements,
      boolean enabled,
      long version,
      String updatedAt) {}

  /** 报关属性的写入请求。{@code version} 是行级乐观锁；无行时传 0 表示新建。 */
  public record CustomsDeclarationInput(
      @Schema(nullable = true) @Size(max = 32) String hsCode,
      @Schema(nullable = true) @Size(max = 200) String declaredName,
      @Schema(nullable = true) @Size(max = 2) String originCountry,
      @Schema(nullable = true) @Size(max = 32) String legalUnit,
      @Schema(nullable = true) @Size(max = 32) List<@Size(max = 200) String> declarationElements,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) @PositiveOrZero Long version) {}

  // ================================================================ 认证（catalog 侧声明数据）

  /**
   * 一条认证声明。
   *
   * <p>{@code status} 的四个取值（规则 R5）里 {@code NOT_REQUIRED} 与另外三个 语义相反：它表达「压根不需要这张证书」。它是让认证维度可用的关键 ——
   * 没有它，每一个不需要认证的商品都会永远停在"认证缺失"。
   */
  public record Certification(
      UUID id,
      UUID skuId,
      String skuCode,
      String certificationCode,
      String certificationName,
      String status,
      String certificateNo,
      LocalDate issuedOn,
      LocalDate expiresOn,
      boolean scored,
      String note,
      long version,
      String updatedAt) {}

  /** 认证的写入请求。 */
  public record CertificationInput(
      @NotNull @Size(max = 32) String certificationCode,
      @Schema(nullable = true) @Size(max = 100) String certificationName,
      @NotNull @Size(max = 16) String status,
      @Schema(nullable = true) @Size(max = 100) String certificateNo,
      @Schema(nullable = true) LocalDate issuedOn,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) Boolean scored,
      @Schema(nullable = true) @Size(max = 500) String note,
      @Schema(nullable = true) @PositiveOrZero Long version) {}

  /** 某个 SKU 的认证集合 + 现算的该 SKU 认证维度结论（规则 R5 的四种缺失原因）。 */
  public record CertificationSet(
      UUID skuId,
      List<Certification> items,
      String completeness,
      String completenessLabel,
      long expiredCount,
      String note) {}

  // ================================================================ 预检出口（供 4.2.3.2）

  /**
   * 刊登预检结论（供下游 4.2.3.2 调用）。
   *
   * <p>刻意用 {@code allowed} + {@code blockers} 而不是只给一个布尔： 布尔答不出「那 3 个 SKU 为什么被拦住」，而预检页要把缺失项直接指到字段上。
   */
  public record ListingPrecheck(
      boolean allowed,
      int checkedCount,
      int blockedCount,
      List<String> blockedOwners,
      List<CompletenessScoreRules.PrecheckBlocker> blockers,
      String note) {}

  /** 清关预检结论（供下游报关流程调用）。同一口径，链换成 CUSTOMS。 */
  public record CustomsPrecheck(
      boolean allowed,
      int checkedCount,
      int blockedCount,
      List<String> blockedOwners,
      List<CompletenessScoreRules.PrecheckBlocker> blockers,
      String note) {}

  /** 批量补全的统计（{@code POST /catalog/completeness/gaps/summary}）。 */
  public record GapSummary(
      long ownerCount,
      long missingCheckCount,
      List<CompletenessScoreRules.DimensionGap> byDimension,
      List<CompletenessScoreRules.GradeCount> byGrade,
      long cleanCount,
      String note) {}
}
