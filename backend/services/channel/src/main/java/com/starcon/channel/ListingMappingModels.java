package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.2.1 映射管理 的传输模型。
 *
 * <p>蓝图：「建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。输入：映射建立操作；匹配规则；批量候选。输出：{@code listing_mapping_version} 记录（当前生效版本 + 历史版本）；未映射清单回流 02 映射缺失队列。关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单（订单行按映射解析 SKU）、07 库存（回写可售量）。<b>映射未建立时订单进入待映射状态，绝不按名称猜 SKU</b>（实体目录 §6）。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过，4.1.1.1 同款）。
 *
 * <p>🔴 模型层就把蓝图加粗句钉死，它们是最容易被赶工做丢的：
 *
 * <ol>
 *   <li><b>「绝不按名称猜 SKU」在 {@link MappingResolveResult} 上是一个<b>显式的两态</b>：
 *       {@code RESOLVED} 给内部 SKU，{@code PENDING_MAPPING} <b>不给任何候选</b>。
 *       模型里不存在「猜一个 + 标个置信度」的形状 —— 那个形状一存在，调用方就会在赶
 *       订单时选它。
 *   <li><b>标题相似度候选恒带 {@code requiresConfirmation=true}</b>（{@link MappingCandidate}），
 *       且 {@code decision} 默认 {@code PENDING}。DB 层另有 CHECK 钉住（V176）。
 *   <li><b>没有金额字段</b>：映射不碰钱。有字段就得填值，填 0 会被读成"映射错了 0 块钱"。
 *   <li><b>数量只有 {@code componentQuantity}</b>，{@link BigDecimal} + NUMERIC(19,6)（AGENTS.md）。
 * </ol>
 */
public final class ListingMappingModels {
  private ListingMappingModels() {}

  // ================================================================ 能力声明

  public record MappingCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingMappingCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<MappingCapabilityDimension> dimensions,
      @Schema(description = "映射基数取值：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") List<String> relationTypes,
      @Schema(description = "映射状态取值：MAPPED / BUNDLE（UNMAPPED 由「没有映射行」表达）")
          List<String> mappingStatuses,
      @Schema(description = "匹配规则取值：SKU_CODE / BARCODE / TITLE_SIMILARITY") List<String> matchRules,
      @Schema(description = "候选裁决取值") List<String> candidateDecisions,
      @Schema(description = "订单行解析结果取值：RESOLVED / PENDING_MAPPING") List<String> resolveStatuses,
      @Schema(description = "🔴 单次批量候选的变体上限") int maxVariantsPerBatch,
      @Schema(description = "🔴 单次批量候选从 catalog 拉取的 SKU 上限；超出时 truncated=true 如实告知")
          int maxCandidateSkus,
      @Schema(description = "🔴 标题相似度的收录下限（PG pg_trgm similarity）") double similarityThreshold,
      @Schema(description = "🔴 未交付段说明（前端原样展示，不要自己编）") String gapNote,
      @Schema(description = "上游 / 下游依赖说明") String dependencyNote) {}

  // ================================================================ 映射 CRUD

  /**
   * 映射列表的一行。
   *
   * <p>{@code mappingStatus} 与 4.1.1.1 的列表级聚合（UNMAPPED / PARTIAL / MAPPED）<b>不是同一个东西</b>：
   * 这里答的是「<b>这一条</b>映射指向什么」，列表级聚合答的是「这个 listing 的变体映射全了没」。
   * 两者混成一个字段就会出现"单条是 MAPPED，列表筛 PARTIAL 却筛不出来"。
   */
  public record MappingRow(
      @Schema(description = "映射 id") UUID id,
      @Schema(description = "上游 4.1.1.1 listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "平台标题原文") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台变体 id（4.1.1.1 listing_variant）") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "🔴 映射基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") String relationType,
      @Schema(description = "ACTIVE 生效中 / REMOVED 已解除") String status,
      @Schema(description = "MAPPED / BUNDLE") String mappingStatus,
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 拥有，跨服务不建 FK）") UUID internalSkuId,
      @Schema(description = "套装组件数量；仅 ONE_TO_MANY 有值，NUMERIC(19,6)") BigDecimal componentQuantity,
      @Schema(description = "当前生效版本 id") UUID currentVersionId,
      @Schema(description = "当前生效版本号") Integer currentVersionNo,
      @Schema(description = "当前生效版本起点（4.1.2.2 生效时间点字段，本模块只写不查范围）")
          OffsetDateTime effectiveFrom,
      @Schema(description = "同一内部 SKU 还被哪些平台变体共用（多对一的反查计数）") int sharedWithVariants,
      @Schema(description = "版本历史条数") int versionCount,
      @Schema(description = "建立时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 映射详情：当前映射 + 完整版本历史（4.1.2.2 的生效时间点与变更原因就在这里）。 */
  public record MappingDetail(
      @Schema(description = "当前映射") MappingRow mapping,
      @Schema(description = "🔴 版本历史（新 → 旧）；effectiveTo 为 null 即当前生效版本")
          List<MappingVersionView> versions,
      @Schema(description = "🔴 4.1.2.2 未交付段的说明：本模块不返回「受影响订单范围」") String affectedOrdersNote) {}

  public record MappingVersionView(
      @Schema(description = "版本 id") UUID id,
      @Schema(description = "版本号（同一条映射内从 1 递增）") int versionNo,
      @Schema(description = "该版本的映射基数") String relationType,
      @Schema(description = "该版本指向的内部 SKU 逻辑 id；解除映射的版本为 null") UUID internalSkuId,
      @Schema(description = "该版本的套装组件数量") BigDecimal componentQuantity,
      @Schema(description = "🔴 生效时间点：区间为 [effectiveFrom, effectiveTo)") OffsetDateTime effectiveFrom,
      @Schema(description = "生效时间点终点；null = 当前生效版本") OffsetDateTime effectiveTo,
      @Schema(description = "🔴 变更原因") String changeReason,
      @Schema(description = "MANUAL 人工建立 / BATCH_CONFIRMED 批量候选人工确认") String changeSource,
      @Schema(description = "操作者 id（DB 触发器盖上，服务层写不出）") UUID actorId,
      @Schema(description = "落库时刻") OffsetDateTime createdAt) {}

  public record MappingPage(
      @Schema(description = "当前页") List<MappingRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 本页被解除映射的条数（REMOVED 也是真实结果，不藏）") int removedInPage) {}

  // ================================================================ 建立 / 修改 / 解除

  public record SaveMappingRequest(
      @Schema(description = "映射 id；为空 = 新建，非空 = 改这一条（改会生成新版本，不原地改）")
          UUID id,
      @Schema(description = "平台变体 id（4.1.1.1 listing_variant）") @NotNull UUID listingVariantId,
      @Schema(description = "🔴 映射基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") @NotBlank String relationType,
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 拥有，跨服务不建 FK）") @NotNull UUID internalSkuId,
      @Schema(
              description = "套装组件数量；ONE_TO_MANY 必填且 > 0，其余基数必须为 null",
              nullable = true)
          @DecimalMin(value = "0.000001", inclusive = false)
          BigDecimal componentQuantity,
      @Schema(description = "🔴 变更原因（4.1.2.2 的字段，本模块必填以保证历史可读）") @NotBlank @Size(max = 500)
          String changeReason,
      @Schema(description = "🔴 生效时间点起点；不传 = 此刻。传未来值 = 预约生效（4.1.2.2 的区间语义）")
          OffsetDateTime effectiveFrom,
      @Schema(description = "预期版本号；不匹配则 409（乐观锁，防两个人同时改）") Integer expectedVersionNo) {}

  public record RemoveMappingRequest(
      @Schema(description = "🔴 解除原因（必填：解除后变体退回未映射，订单行会重新进入待映射状态）")
          @NotBlank @Size(max = 500)
          String changeReason) {}

  // ================================================================ 批量候选

  public record BatchCandidateRequest(
      @Schema(description = "🔴 匹配规则：SKU_CODE / BARCODE / TITLE_SIMILARITY") @NotBlank String rule,
      @Schema(description = "要为其推荐候选的 listing id 列表") @NotEmpty @Size(max = 50)
          List<@NotNull UUID> listingIds,
      @Schema(description = "标题相似度收录下限；不传用模块默认值（PG pg_trgm similarity）")
          @DecimalMin("0.0")
          @DecimalMax("1.0")
          Double minSimilarity,
      @Schema(description = "每条 listing 最多返回几个候选；不传用模块默认值") @Min(1) @Max(20)
          Integer topPerListing) {}

  public record BatchCandidateResult(
      @Schema(description = "批量匹配任务 id") UUID batchTaskId,
      @Schema(description = "本次使用的匹配规则") String rule,
      @Schema(description = "请求的 listing 条数") int requestedCount,
      @Schema(description = "🔴 实际展开并检索的变体条数（变体展开后可能多于 listing 条数）")
          int scannedVariantCount,
      @Schema(description = "候选条数；0 是真状态（确实没有可推荐项），不是查不到") int candidateCount,
      @Schema(description = "🔴 从 catalog 拉取并参与比对的 SKU 条数") int scannedSkus,
      @Schema(description = "🔴 候选池是否被上限截断；true = 只比对了前 maxCandidateSkus 条，不是全集")
          boolean truncated,
      @Schema(description = "🔴 全部标题相似度候选都需人工确认；本字段把它逐条带出来") int requiresConfirmationCount,
      @Schema(description = "候选明细") List<MappingCandidate> candidates,
      @Schema(description = "🔴 人类可读的检索口径说明（用了哪些 listing 的哪些变体文本）") String reason) {}

  /**
   * 一个候选。
   *
   * <p>🔴 {@code score} 是 PostgreSQL {@code pg_trgm similarity()} 的真算结果；
   * {@code matchedText} 是命中依据原文（编码本身 / 条码本身 / 用于比对的平台文本）。
   * 两者与 {@code internalSkuCode} / {@code internalSkuName} 一起**冻结了"当时推荐了什么"**，
   * 事后可复核人工确认那一道签字当时看到的东西。
   */
  public record MappingCandidate(
      @Schema(description = "候选 id") UUID id,
      @Schema(description = "批量匹配任务 id") UUID batchTaskId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "产出本候选的规则") String rule,
      @Schema(description = "内部 SKU 逻辑 id") UUID internalSkuId,
      @Schema(description = "🔴 内部 SKU 编码快照（展示用，不参与匹配计算）") String internalSkuCode,
      @Schema(description = "🔴 内部 SKU 名称快照（展示用，不参与匹配计算）") String internalSkuName,
      @Schema(description = "🔴 命中依据原文") String matchedText,
      @Schema(description = "🔴 分数：SKU_CODE / BARCODE 为精确匹配 1.0，标题相似度为 PG similarity 真算值")
          BigDecimal score,
      @Schema(description = "🔴 是否需人工确认；标题相似度规则恒为 true（DB CHECK 钉住）")
          boolean requiresConfirmation,
      @Schema(description = "PENDING / CONFIRMED / REJECTED") String decision,
      @Schema(description = "裁决人 id") UUID decidedBy,
      @Schema(description = "裁决时刻") OffsetDateTime decidedAt,
      @Schema(description = "裁决理由") String decisionReason,
      @Schema(description = "落库时刻") OffsetDateTime createdAt) {}

  public record CandidateDecisionRequest(
      @Schema(description = "🔴 裁决：CONFIRMED 确认并建立映射 / REJECTED 否决") @NotBlank String decision,
      @Schema(description = "裁决理由；CONFIRMED 时会被写进版本的 changeReason") @Size(max = 500)
          String reason) {}

  /**
   * 确认候选的结果。
   *
   * <p>🔴 「一个候选只裁决一次」由 UNIQUE + 服务层的 PENDING 校验双重保证，
   * 重复裁决返回 409 而不是静默改写已落库的映射。
   */
  public record CandidateDecisionResult(
      @Schema(description = "候选 id") UUID candidateId,
      @Schema(description = "裁决结果：CONFIRMED / REJECTED") String decision,
      @Schema(description = "确认后建立的映射 id；否决时为 null") UUID mappingId,
      @Schema(description = "确认后生成的版本 id；否决时为 null") UUID versionId,
      @Schema(description = "生成的版本号；否决时为 null") Integer versionNo,
      @Schema(description = "结果说明") String reason) {}

  public record CandidatePage(
      @Schema(description = "候选明细") List<MappingCandidate> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 未映射清单 / 解析

  /**
   * 未映射清单（回流 02 映射缺失队列的数据源）。
   *
   * <p>🔴 蓝图输出句是「未映射清单回流 02 映射缺失队列」。2.4.2.3「映射缺失队列」**未交付**，
   * 所以本端点提供<b>清单本身</b>并显式声明回流目标未接入 —— 用一个空队列或一个假
   * 队列表冒充「已经回流了」比不回流更糟。
   */
  public record UnmappedVariantRow(
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "平台标题原文") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "平台变体属性原文 json") String attributes,
      @Schema(description = "变体在 listing 下的序号（0 起）") int ordinal,
      @Schema(description = "🔴 未映射天数（按 listing 的 last_synced_at 算；无同步记录则按创建时刻）")
          long unmappedDays) {}

  public record UnmappedPage(
      @Schema(description = "未映射变体清单") List<UnmappedVariantRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 回流目标：02 映射缺失队列（2.4.2.3）的交付状态") boolean backflowDelivered,
      @Schema(description = "🔴 回流目标模块") String backflowOwnerModule,
      @Schema(description = "🔴 回流说明") String backflowNote) {}

  /**
   * 订单行按映射解析 SKU 的结果（09 订单的解析入口）。
   *
   * <p>🔴 蓝图加粗句「映射未建立时订单进入待映射状态，<b>绝不按名称猜 SKU</b>」在模型层
   * 就是一个<b>两态联合</b>：{@code PENDING_MAPPING} 时 {@code internalSkuId} 必为 null。
   * 没有「带置信度的猜测」这个形状 —— 加一个 {@code guessed} 字段就已经在给"猜"开门了。
   */
  public record MappingResolveResult(
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "🔴 RESOLVED 已解析 / PENDING_MAPPING 未建立映射") String status,
      @Schema(description = "解析出的内部 SKU 逻辑 id；PENDING_MAPPING 时为 null") UUID internalSkuId,
      @Schema(description = "解析用的映射基数；PENDING_MAPPING 时为 null") String relationType,
      @Schema(description = "套装组件数量；非套装为 null") BigDecimal componentQuantity,
      @Schema(description = "解析所用的映射 id；PENDING_MAPPING 时为 null") UUID mappingId,
      @Schema(description = "解析所用的版本号；PENDING_MAPPING 时为 null") Integer versionNo,
      @Schema(description = "🔴 版本生效时间点；PENDING_MAPPING 时为 null") OffsetDateTime effectiveFrom,
      @Schema(description = "待映射原因；PENDING_MAPPING 时说明为什么答不出来") String pendingReason) {}

  public record MappingResolveRequest(
      @Schema(description = "要解析的平台变体 id 列表（09 订单行）") @NotEmpty @Size(max = 500)
          List<@NotNull UUID> listingVariantIds,
      @Schema(description = "🔴 解析时点；不传 = 此刻。传历史时刻可回溯当时生效的版本（4.1.2.2 的时间点语义）")
          OffsetDateTime at) {}

  // ================================================================ 筛选项 / 多对一反查

  public record MappingFilterOptions(
      @Schema(description = "映射基数取值") List<String> relationTypes,
      @Schema(description = "映射状态取值") List<String> mappingStatuses,
      @Schema(description = "映射存续取值：ACTIVE / REMOVED") List<String> statuses,
      @Schema(description = "匹配规则取值") List<String> matchRules,
      @Schema(description = "候选裁决取值") List<String> candidateDecisions,
      @Schema(description = "排序取值") List<String> sorts,
      @Schema(description = "单次批量候选的变体上限") int maxVariantsPerBatch) {}

  public record MappedVariantRow(
      @Schema(description = "映射 id") UUID mappingId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台变体 id") UUID listingVariantId,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "映射基数") String relationType,
      @Schema(description = "套装组件数量") BigDecimal componentQuantity,
      @Schema(description = "当前生效版本号") Integer versionNo) {}

  /**
   * 多对一（MANY_TO_ONE）的反查结果。
   *
   * <p>🔴 「多对一」如果不留读出口就只是一句声明：没有任何端点能证明「这个内部 SKU 到底被
   * 哪些平台变体共用」，运营无法回答"改这个映射会波及哪几个平台商品"。
   */
  public record SkuUsage(
      @Schema(description = "内部 SKU 逻辑 id") UUID internalSkuId,
      @Schema(description = "共用它的平台变体条数（生效中的映射）") int variantCount,
      @Schema(description = "明细") List<MappedVariantRow> variants,
      @Schema(description = "🔴 说明：本模块只按 03 的逻辑 id 反查，不复制 catalog 的编码 / 名称") String note) {}
}
