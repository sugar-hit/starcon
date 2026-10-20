package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.4.1.3 采集箱 的传输模型。
 *
 * <p>蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * <p>🔴 模型层把本模块最容易做丢的三条边界钉死：
 *
 * <ol>
 *   <li><b>品牌与分类映射是必填项</b>：{@link ListingCollectBoxClaimRuleRow} 里
 *       {@code brandMapping} / {@code targetCategoryCode} / {@code namingTemplate} 都**不是可选**的
 *       —— 蓝图加粗句说「认领时必须做品牌与分类映射」，没有映射就没有认领规则。
 *   <li><b>认领产出的是「商品草稿」不是商品</b>：{@link ListingCollectBoxSpuDraft} /
 *       {@link ListingCollectBoxSkuDraft} 是**载荷**（给 03 建档用），
 *       {@code buildState} 恒为 {@code PENDING_BUILD} —— 落库归 03 建档（3.1.1.1），
 *       本模块**不跨服务写 catalog 的表**，也**不假装建档已完成**。
 *   <li><b>没有我方售价</b>：{@code sourcePriceAmount} 是**外部页面原文标价**（源站报价），
 *       不是我方定价（售价归 05 定价）。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingCollectBoxModels {
  private ListingCollectBoxModels() {}

  /** 采集箱条目的状态取值域：待认领 / 已认领。 */
  public static final List<String> ITEM_STATES = List.of("PENDING", "CLAIMED");

  /** 条目来源取值域：链接采集 / 批量采集 / 插件上报。 */
  public static final List<String> ORIGINS = List.of("LINK", "BULK", "PLUGIN");

  /** 批量打标的动作。 */
  public static final List<String> TAG_ACTIONS = List.of("ADD", "REMOVE");

  /** 认领规则的状态取值域。 */
  public static final List<String> RULE_STATES = List.of("ACTIVE", "DISABLED");

  /** 命名规则支持的占位符。 */
  public static final List<String> NAMING_PLACEHOLDERS =
      List.of("{brand}", "{title}", "{model}", "{category}", "{source}");

  // ================================================================ 能力声明

  public record ListingCollectBoxCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingCollectBoxCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingCollectBoxCapabilityDimension> dimensions,
      @Schema(description = "条目状态取值域") List<String> itemStates,
      @Schema(description = "条目来源取值域") List<String> origins,
      @Schema(description = "命名规则支持的占位符") List<String> namingPlaceholders,
      @Schema(description = "🔴 认领是否强制做品牌与分类映射（恒为 true）") boolean mappingRequired,
      @Schema(description = "🔴 品牌与分类映射规则的界面措辞") String mappingNote,
      @Schema(description = "🔴 认领产出的是否只是草稿（恒为 true）") boolean draftOnly,
      @Schema(description = "🔴 草稿边界的界面措辞（前端原样展示）") String draftNote,
      @Schema(description = "🔴 认领结果是否已落进商品库（恒为 false）") boolean builtInCatalog,
      @Schema(description = "🔴 落库归属与未交付原因") String buildNote,
      @Schema(description = "🔴 图片是否已下载入媒体库（恒为 false）") boolean mediaDownloaded,
      @Schema(description = "🔴 媒体库归属与未交付原因") String mediaNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 标签

  public record ListingCollectBoxTagRow(
      @Schema(description = "标签 id") UUID tagId,
      @Schema(description = "标签名") String name,
      @Schema(description = "标签颜色（空串 = 默认色）") String color,
      @Schema(description = "🔴 挂着这个标签的条目数") long itemCount,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  public record CreateBoxTagRequest(
      @Schema(description = "标签名（同一租户内唯一）") @NotBlank @Size(max = 64) String name,
      @Schema(description = "标签颜色") @Size(max = 32) String color) {}

  // ================================================================ 采集箱条目

  /** 采集箱条目的一行（列表页用）。 */
  public record ListingCollectBoxItemRow(
      @Schema(description = "采集箱条目 id") UUID itemId,
      @Schema(description = "🔴 指向的采集草稿 id（4.4.1.1 的草稿）") UUID draftId,
      @Schema(description = "🔴 条目来源：LINK 链接采集 / BULK 批量采集 / PLUGIN 插件上报")
          String origin,
      @Schema(description = "🔴 条目状态：PENDING 待认领 / CLAIMED 已认领") String state,
      @Schema(description = "🔴 草稿标题") String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") String mainImageUrl,
      @Schema(description = "🔴 来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 命中的白名单编码（平台 / 来源分组维度）") String sourceCode,
      @Schema(description = "🔴 命中的白名单名称") String sourceName,
      @Schema(description = "🔴 外部页面原文标价（**不是我方售价**）") BigDecimal sourcePriceAmount,
      @Schema(description = "🔴 外部页面原文标价币种") String sourcePriceCurrency,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "🔴 挂着的标签") List<ListingCollectBoxTagRef> tags,
      @Schema(description = "🔴 最近一次认领失败的原因（成功时为空串）") String claimError,
      @Schema(description = "🔴 认领时间（未认领时为 null）") OffsetDateTime claimedAt,
      @Schema(description = "🔴 认领结果 id（未认领时为 null）") UUID claimResultId,
      @Schema(description = "入箱时间") OffsetDateTime createdAt) {}

  /** 条目上的一个标签引用。 */
  public record ListingCollectBoxTagRef(
      @Schema(description = "标签 id") UUID tagId, @Schema(description = "标签名") String name) {}

  /** 采集箱条目列表（真分页 + 真筛选）。 */
  public record ListingCollectBoxPage(
      @Schema(description = "当前页数据") List<ListingCollectBoxItemRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 分组统计的一行。 */
  public record ListingCollectBoxGroupRow(
      @Schema(description = "分组键（平台编码 / 标签 id / 状态）") String key,
      @Schema(description = "分组显示名") String label,
      @Schema(description = "该分组的条目数") long count) {}

  /** 采集箱分组统计（蓝图：「按平台/来源/标签分组」）。 */
  public record ListingCollectBoxGroups(
      @Schema(description = "按平台 / 来源分组") List<ListingCollectBoxGroupRow> bySource,
      @Schema(description = "按标签分组") List<ListingCollectBoxGroupRow> byTag,
      @Schema(description = "按认领状态分组") List<ListingCollectBoxGroupRow> byState,
      @Schema(description = "箱子里的条目总数") long total) {}

  // ================================================================ 待收录的采集草稿

  /** 一条还没有进箱的采集草稿（4.4.1.1 / 4.4.1.2 的产物）。 */
  public record ListingCollectBoxCandidateRow(
      @Schema(description = "🔴 采集草稿 id") UUID draftId,
      @Schema(description = "🔴 草稿标题") String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") String mainImageUrl,
      @Schema(description = "🔴 来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 命中的白名单编码") String sourceCode,
      @Schema(description = "🔴 命中的白名单名称") String sourceName,
      @Schema(description = "🔴 外部页面原文标价（**不是我方售价**）") BigDecimal sourcePriceAmount,
      @Schema(description = "🔴 外部页面原文标价币种") String sourcePriceCurrency,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt,
      @Schema(description = "采集时间") OffsetDateTime createdAt) {}

  /** 待收录草稿列表（真分页 + 真筛选）。 */
  public record ListingCollectBoxCandidatePage(
      @Schema(description = "当前页数据") List<ListingCollectBoxCandidateRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 收录 / 打标 / 删除

  /** 收录：把选中的采集草稿放进采集箱。 */
  public record EnrollBoxItemsRequest(
      @Schema(description = "🔴 要收录的采集草稿 id 列表")
          @NotEmpty
          @Size(max = 200)
          List<UUID> draftIds) {}

  /** 单条收录结果。 */
  public record ListingCollectBoxEnrollOutcome(
      @Schema(description = "采集草稿 id") UUID draftId,
      @Schema(description = "是否收录成功") boolean ok,
      @Schema(description = "失败原因（成功时为空串）") String reason,
      @Schema(description = "生成的条目 id（成功时非空）") UUID itemId) {}

  /** 批量收录结果。 */
  public record ListingCollectBoxEnrollResult(
      @Schema(description = "请求条数") int total,
      @Schema(description = "成功条数") int succeeded,
      @Schema(description = "失败条数") int failed,
      @Schema(description = "逐条结果") List<ListingCollectBoxEnrollOutcome> outcomes) {}

  /** 批量打标。 */
  public record TagBoxItemsRequest(
      @Schema(description = "🔴 要打标的条目 id 列表") @NotEmpty @Size(max = 200) List<UUID> itemIds,
      @Schema(description = "标签 id") @NotNull UUID tagId,
      @Schema(description = "🔴 动作：ADD 挂上 / REMOVE 摘掉") @NotBlank String action) {}

  /** 单条打标结果。 */
  public record ListingCollectBoxTagOutcome(
      @Schema(description = "条目 id") UUID itemId,
      @Schema(description = "是否成功") boolean ok,
      @Schema(description = "失败原因（成功时为空串）") String reason) {}

  /** 批量打标结果。 */
  public record ListingCollectBoxTagResult(
      @Schema(description = "请求条数") int total,
      @Schema(description = "成功条数") int succeeded,
      @Schema(description = "失败条数") int failed,
      @Schema(description = "逐条结果") List<ListingCollectBoxTagOutcome> outcomes) {}

  /** 批量删除（移出采集箱）。 */
  public record RemoveBoxItemsRequest(
      @Schema(description = "🔴 要移出采集箱的条目 id 列表")
          @NotEmpty
          @Size(max = 200)
          List<UUID> itemIds) {}

  /** 单条删除结果。 */
  public record ListingCollectBoxRemoveOutcome(
      @Schema(description = "条目 id") UUID itemId,
      @Schema(description = "是否删除成功") boolean ok,
      @Schema(description = "失败原因（成功时为空串）") String reason,
      @Schema(description = "🔴 上游采集草稿是否被连带清理") boolean draftRemoved) {}

  /** 批量删除结果。 */
  public record ListingCollectBoxRemoveResult(
      @Schema(description = "请求条数") int total,
      @Schema(description = "成功条数") int succeeded,
      @Schema(description = "失败条数") int failed,
      @Schema(description = "逐条结果") List<ListingCollectBoxRemoveOutcome> outcomes) {}

  // ================================================================ 认领规则

  /** 认领规则（目标分类 + 品牌映射 + 命名规则）。 */
  public record ListingCollectBoxClaimRuleRow(
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则名") String name,
      @Schema(description = "🔴 目标分类（内部分类的外部引用编码）") String targetCategoryCode,
      @Schema(description = "🔴 分类映射：源类目名 → 目标分类编码")
          List<ListingCollectBoxKeyValue> categoryMapping,
      @Schema(description = "🔴 品牌映射：源品牌名 → 目标品牌引用")
          List<ListingCollectBoxKeyValue> brandMapping,
      @Schema(description = "🔴 命名规则模板") String namingTemplate,
      @Schema(description = "从草稿的哪个属性键取品牌") String brandAttributeKey,
      @Schema(description = "从草稿的哪个属性键取类目") String categoryAttributeKey,
      @Schema(description = "规则状态：ACTIVE / DISABLED") String state,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /**
   * 新建认领规则。
   *
   * <p>🔴 <b>映射一律用具名键值项列表，不用 {@code Map<String,String>}</b>：Map 的值类型会被
   * springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过），前端在 strict 模式下
   * 按字符串键索引它会直接编译不过（与 4.4.1.1 的 {@code ListingCollectKeyValue} 同一手法）。
   */
  public record CreateClaimRuleRequest(
      @Schema(description = "规则名") @NotBlank @Size(max = 100) String name,
      @Schema(description = "🔴 目标分类编码（内部分类的外部引用）") @Size(max = 100)
          String targetCategoryCode,
      @Schema(description = "🔴 分类映射：源类目名 → 目标分类编码")
          List<@Valid ListingCollectBoxKeyValue> categoryMapping,
      @Schema(description = "🔴 品牌映射：源品牌名 → 目标品牌引用（不能为空）")
          List<@Valid ListingCollectBoxKeyValue> brandMapping,
      @Schema(description = "🔴 命名规则模板（{brand} {title} {model} {category} {source}）")
          @NotBlank
          @Size(max = 400)
          String namingTemplate,
      @Schema(description = "从草稿的哪个属性键取品牌") @Size(max = 64) String brandAttributeKey,
      @Schema(description = "从草稿的哪个属性键取类目") @Size(max = 64) String categoryAttributeKey) {}

  // ================================================================ 认领结果（商品草稿）

  /** 键值项（规格 / 属性）。 */
  public record ListingCollectBoxKeyValue(
      @Schema(description = "键") String name, @Schema(description = "值") String value) {}

  /** SPU 草稿载荷（预填内容，给 03 建档用）。 */
  public record ListingCollectBoxSpuDraft(
      @Schema(description = "🔴 命名规则产出的商品名") String name,
      @Schema(description = "🔴 目标分类编码") String category,
      @Schema(description = "🔴 目标品牌引用") String brand,
      @Schema(description = "🔴 预填描述（采集草稿的描述原文）") String description,
      @Schema(description = "🔴 预填标题（采集草稿的标题原文）") String title,
      @Schema(description = "🔴 主图 URL（只记 URL，不下载二进制）") String mainImageUrl,
      @Schema(description = "🔴 附图 URL 列表（只记 URL，不下载二进制）") List<String> imageUrls,
      @Schema(description = "🔴 预填规格") List<ListingCollectBoxKeyValue> specs,
      @Schema(description = "🔴 预填属性") List<ListingCollectBoxKeyValue> attributes,
      @Schema(description = "🔴 来源 URL 原文") String sourceUrl,
      @Schema(description = "🔴 来源平台名") String sourceName,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime capturedAt) {}

  /** SKU 草稿载荷（给 03 建档用）。 */
  public record ListingCollectBoxSkuDraft(
      @Schema(description = "🔴 SKU 草稿名") String name,
      @Schema(description = "🔴 规格取值（上游每个规格轴只有一个取值 ⇒ 一个观察到的组合）")
          List<ListingCollectBoxKeyValue> specValues,
      @Schema(description = "🔴 来源 URL 原文") String sourceUrl) {}

  /** 认领结果（= 认领后的商品草稿）。 */
  public record ListingCollectBoxClaimResultRow(
      @Schema(description = "认领结果 id") UUID claimResultId,
      @Schema(description = "采集箱条目 id") UUID itemId,
      @Schema(description = "🔴 用的规则 id") UUID ruleId,
      @Schema(description = "🔴 用的规则名") String ruleName,
      @Schema(description = "🔴 命名规则产出的商品名") String spuName,
      @Schema(description = "🔴 映射出的目标分类") String targetCategoryCode,
      @Schema(description = "🔴 映射出的目标品牌") String targetBrandCode,
      @Schema(description = "🔴 映射依据：草稿里的原始品牌") String sourceBrand,
      @Schema(description = "🔴 映射依据：草稿里的原始类目") String sourceCategory,
      @Schema(description = "🔴 SPU 草稿载荷（预填内容）") ListingCollectBoxSpuDraft spuDraft,
      @Schema(description = "🔴 SKU 草稿行") List<ListingCollectBoxSkuDraft> skuDrafts,
      @Schema(description = "🔴 SKU 草稿行数") int skuDraftCount,
      @Schema(description = "🔴 落库状态：恒为 PENDING_BUILD（落库归 03 建档）") String buildState,
      @Schema(description = "🔴 落库说明（03 建档要做什么）") String buildNote,
      @Schema(description = "🔴 认领时间") OffsetDateTime claimedAt) {}

  /** 批量认领。 */
  public record ClaimBoxItemsRequest(
      @Schema(description = "🔴 要认领的采集箱条目 id 列表")
          @NotEmpty
          @Size(max = 200)
          List<UUID> itemIds,
      @Schema(description = "🔴 用的认领规则 id（目标分类 + 品牌映射 + 命名规则）")
          @NotNull
          UUID ruleId) {}

  /** 单条认领结果。 */
  public record ListingCollectBoxClaimOutcome(
      @Schema(description = "采集箱条目 id") UUID itemId,
      @Schema(description = "是否认领成功") boolean ok,
      @Schema(description = "失败原因（成功时为空串）") String reason,
      @Schema(description = "🔴 认领结果（成功时非空）") ListingCollectBoxClaimResultRow claimResult) {}

  /** 批量认领结果。 */
  public record ListingCollectBoxClaimResult(
      @Schema(description = "🔴 用的规则") ListingCollectBoxClaimRuleRow rule,
      @Schema(description = "请求条数") int total,
      @Schema(description = "成功条数") int succeeded,
      @Schema(description = "失败条数") int failed,
      @Schema(description = "逐条结果") List<ListingCollectBoxClaimOutcome> outcomes) {}

  /** 认领规则列表。 */
  public record ListingCollectBoxClaimRulePage(
      @Schema(description = "当前页数据") List<ListingCollectBoxClaimRuleRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
