package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.2.2.1 刊登模板 的传输模型。
 *
 * <p>蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」
 *
 * <p>🔴 模型层就把三条边界钉死，它们是这个模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>模板里没有值</b>：{@link ListingTemplateFieldInput} 只有 {@code sourceKind} /
 *       {@code sourceRef} / {@code valueExpression} —— 来源引用与取值表达式，**没有 resolvedValue /
 *       currentValue 之类的字段**。载荷形状本身就不许把值塞进模板。
 *   <li><b>目标恒为草稿态</b>：{@link ApplyListingTemplateRequest} 里**没有** status /
 *       publish / live 之类的字段。套用的产出是草稿，发布是 4.3.1.2 的另一条路径。
 *   <li><b>「不知道」是一个状态</b>：{@link ListingTemplateApplyFieldRow.status} 有
 *       {@code SOURCE_MISSING} / {@code UNKNOWN_SOURCE} 两值并带 {@code ownerModule}，
 *       不会退化成「没有值」。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingTemplateModels {
  private ListingTemplateModels() {}

  // ================================================================ 值域

  /** 适用范围的两个值：类目级 / 店铺级。两者**互斥**（DB 层 CHECK 钉死）。 */
  public static final List<String> SCOPES = List.of("CATEGORY", "SHOP");

  /** 模板状态：DRAFT 未启用 / ACTIVE 可套用 / DISABLED 停用。 */
  public static final List<String> STATUSES = List.of("DRAFT", "ACTIVE", "DISABLED");

  /**
   * 可填的字段集合。
   *
   * <p>与 V177 {@code listing_content_draft} 的内容列**逐字对齐** —— 模板能填的字段就是草稿能承载的字段。
   *
   * <p>🔴 <b>没有 DESCRIPTION</b>：草稿表目前只有 title / bullets / images / category_path /
   * platform_category_id 五个内容列，描述正文是 4.2.1.1 刊登编辑器的内容域（该模块在集成头尚未交付）。
   * 模板要是能填一个草稿存不下的字段，那条规则就只会永远"取到了值但落不下去"。
   */
  public static final List<String> FIELDS =
      List.of("TITLE", "BULLETS", "IMAGES", "CATEGORY_PATH", "PLATFORM_CATEGORY_ID");

  /**
   * 取值来源的五个值。
   *
   * <p>前三项逐字对应蓝图职责句：「取自商品属性」= {@code PRODUCT_ATTRIBUTE}、
   * 「引用文案库」= {@code CONTENT_TEMPLATE}、「套用图片组」= {@code IMAGE_GROUP}。
   * 另两个是兜底：{@code LISTING_FIELD} 取目标 listing 自身当前内容，
   * {@code LITERAL} 是规则里的常量。
   */
  public static final List<String> SOURCE_KINDS =
      List.of(
          "LISTING_FIELD",
          "PRODUCT_ATTRIBUTE",
          "CONTENT_TEMPLATE",
          "IMAGE_GROUP",
          "LITERAL");

  /** 求值状态五值（把「取到了」与「没取到 / 不知道为什么没取到」彻底分开）。 */
  public static final List<String> EVALUATION_STATUSES =
      List.of("RESOLVED", "EMPTY", "SOURCE_MISSING", "UNKNOWN_SOURCE", "LIMIT_EXCEEDED");

  // ================================================================ 能力声明

  public record ListingTemplateCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingTemplateCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingTemplateCapabilityDimension> dimensions,
      @Schema(description = "可填的字段集合") List<String> fields,
      @Schema(description = "取值来源取值域") List<String> sourceKinds,
      @Schema(description = "求值状态取值域") List<String> evaluationStatuses,
      @Schema(description = "适用范围取值域") List<String> scopes,
      @Schema(description = "🔴 模板是否只存规则不存值") boolean rulesOnly,
      @Schema(description = "🔴 「模板只存规则」说明（前端原样展示）") String rulesOnlyNote,
      @Schema(description = "🔴 套用是否只产出草稿") boolean draftOnly,
      @Schema(description = "🔴 草稿态说明（前端原样展示）") String draftNote,
      @Schema(description = "🔴 重复套用的语义") String idempotencyNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 请求

  /**
   * 一条字段填充规则。
   *
   * @param field 字段键，取值域见 {@link ListingTemplateModels#FIELDS}
   * @param sourceKind 取值来源，取值域见 {@link ListingTemplateModels#SOURCE_KINDS}
   * @param sourceRef 来源引用：属性码 / 内容模板编码 / 图片组编码 / listing 字段名。
   *     {@code LITERAL} 时留空。
   * @param valueExpression 取值表达式：常量前后缀 + {@code {{占位符}}}。{@code LITERAL} 时就是常量本身。
   * @param required 是否必填（套用时真的算出来，不是注释）
   */
  public record ListingTemplateFieldInput(
      @Schema(description = "字段键：TITLE / BULLETS / IMAGES / CATEGORY_PATH / PLATFORM_CATEGORY_ID")
          @NotBlank
          String field,
      @Schema(description = "取值来源：LISTING_FIELD / PRODUCT_ATTRIBUTE / CONTENT_TEMPLATE / IMAGE_GROUP / LITERAL")
          @NotBlank
          String sourceKind,
      @Schema(description = "来源引用：属性码 / 内容模板编码 / 图片组编码 / listing 字段名（LITERAL 时留空）")
          @Size(max = 200)
          String sourceRef,
      @Schema(description = "取值表达式：常量前后缀 + {{占位符}}；LITERAL 时就是常量本身")
          @Size(max = 4000)
          String valueExpression,
      @Schema(description = "是否必填（套用时真的算出来）") boolean required,
      @Schema(description = "显示顺序") Integer slotNo) {}

  /**
   * 一个图片组（套用图片组）。
   *
   * @param groupCode 组编码（模板内唯一；IMAGE_GROUP 来源按它引用）
   * @param groupKind {@code ASSET_LIST}（明确列出素材 id）/ {@code TAG}（按媒体库标签取）
   * @param tagCode 媒体库标签编码（{@code TAG} 时必填）
   * @param assetIds 素材逻辑 id 列表（03 媒体库，跨服务只读；{@code ASSET_LIST} 时必填）
   * @param maxCount 该组最多取几张（超出是显式跳过，不是静默截断）
   */
  public record ListingTemplateAssetGroupInput(
      @Schema(description = "组编码（模板内唯一）") @NotBlank @Size(max = 64) String groupCode,
      @Schema(description = "取组方式：ASSET_LIST / TAG") @NotBlank String groupKind,
      @Schema(description = "媒体库标签编码（TAG 时必填）") @Size(max = 64) String tagCode,
      @Schema(description = "素材逻辑 id 列表（ASSET_LIST 时必填）") List<UUID> assetIds,
      @Schema(description = "该组最多取几张（1~20）") Integer maxCount,
      @Schema(description = "显示顺序") Integer slotNo) {}

  /**
   * 新建 / 更新刊登模板。
   *
   * <p>🔴 载荷里**没有任何字段值**：只有适用范围、字段规则与图片组。
   */
  public record SaveListingTemplateRequest(
      @Schema(description = "模板 id；留空即新建") String id,
      @Schema(description = "模板编码（租户内唯一）") @NotBlank @Size(max = 64) String code,
      @Schema(description = "模板名") @NotBlank @Size(max = 200) String name,
      @Schema(description = "说明") @Size(max = 1000) String description,
      @Schema(description = "适用范围：CATEGORY / SHOP") @NotBlank String scope,
      @Schema(description = "平台 id（内容按平台 + 站点隔离）") @NotNull UUID platformId,
      @Schema(description = "站点 id；留空表示该平台全站点") String marketplaceId,
      @Schema(description = "类目路径（CATEGORY 作用域必填，SHOP 作用域必须留空）")
          @Size(max = 500)
          String categoryPath,
      @Schema(description = "店铺 id（SHOP 作用域必填，CATEGORY 作用域必须留空）") String shopId,
      @Schema(description = "语言（内容按语言分）") @Size(max = 16) String language,
      @Schema(description = "状态：DRAFT / ACTIVE / DISABLED") String status,
      @Schema(description = "字段填充规则") List<@Valid ListingTemplateFieldInput> fields,
      @Schema(description = "图片组") List<@Valid ListingTemplateAssetGroupInput> assetGroups) {}

  /**
   * 套用请求。
   *
   * <p>🔴 载荷里**没有**「直接上线」「发布」之类的字段 —— 套用只产出草稿。
   *
   * @param idempotencyKey 幂等键；给了则同一个键重复套用返回第一次的那一份结果（不再求值、不再写草稿）
   */
  public record ApplyListingTemplateRequest(
      @Schema(description = "模板 id") @NotNull UUID templateId,
      @Schema(description = "套用目标 listing id") @NotNull UUID listingId,
      @Schema(description = "幂等键（可选）：同一个键重复套用返回第一次的结果") @Size(max = 120)
          String idempotencyKey,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  // ================================================================ 结果

  /** 模板列表的一行。 */
  public record ListingTemplateRow(
      @Schema(description = "模板 id") UUID id,
      @Schema(description = "模板编码") String code,
      @Schema(description = "模板名") String name,
      @Schema(description = "说明") String description,
      @Schema(description = "适用范围：CATEGORY / SHOP") String scope,
      @Schema(description = "🔴 适用范围的落点（CATEGORY 是类目路径，SHOP 是店铺编码）")
          String scopeTarget,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点 id；为空表示该平台全站点") UUID marketplaceId,
      @Schema(description = "站点编码；为空表示该平台全站点") String marketplaceCode,
      @Schema(description = "语言") String language,
      @Schema(description = "状态：DRAFT / ACTIVE / DISABLED") String status,
      @Schema(description = "字段规则条数") int fieldCount,
      @Schema(description = "图片组个数") int groupCount,
      @Schema(description = "必填字段数") int requiredFieldCount,
      @Schema(description = "版本号") long version,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 一条字段规则。 */
  public record ListingTemplateFieldRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "字段键") String field,
      @Schema(description = "取值来源") String sourceKind,
      @Schema(description = "来源引用") String sourceRef,
      @Schema(description = "取值表达式") String valueExpression,
      @Schema(description = "是否必填") boolean required,
      @Schema(description = "显示顺序") int slotNo) {}

  /** 一个图片组。 */
  public record ListingTemplateAssetGroupRow(
      @Schema(description = "组 id") UUID id,
      @Schema(description = "组编码") String groupCode,
      @Schema(description = "取组方式：ASSET_LIST / TAG") String groupKind,
      @Schema(description = "媒体库标签编码") String tagCode,
      @Schema(description = "素材逻辑 id 列表") List<UUID> assetIds,
      @Schema(description = "最多取几张") int maxCount,
      @Schema(description = "显示顺序") int slotNo) {}

  /** 模板详情：本体 + 规则 + 图片组。 */
  public record ListingTemplateDetail(
      @Schema(description = "模板") ListingTemplateRow template,
      @Schema(description = "字段填充规则") List<ListingTemplateFieldRow> fields,
      @Schema(description = "图片组") List<ListingTemplateAssetGroupRow> assetGroups) {}

  /** 模板列表（真分页）。 */
  public record ListingTemplatePage(
      @Schema(description = "当前页的模板") List<ListingTemplateRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 逐字段的求值结果（套用结果明细）。
   *
   * @param status {@code RESOLVED} / {@code EMPTY} / {@code SOURCE_MISSING} / {@code UNKNOWN_SOURCE} / {@code LIMIT_EXCEEDED}
   * @param ruleRef 违规指到**具体那条规则**（形如 {@code PRODUCT_ATTRIBUTE:price}）
   * @param ownerModule 「不知道」时点名的归属模块
   */
  public record ListingTemplateApplyFieldRow(
      @Schema(description = "字段键") String field,
      @Schema(description = "取值来源") String sourceKind,
      @Schema(description = "🔴 规则引用（形如 PRODUCT_ATTRIBUTE:price）") String ruleRef,
      @Schema(description = "来源引用") String sourceRef,
      @Schema(description = "求值状态") String status,
      @Schema(description = "本次求值出的值（数组字段是 JSON 数组文本）") String value,
      @Schema(description = "🔴 不知道时的归属模块") String ownerModule,
      @Schema(description = "原因（人能读的理由，不是异常类名）") String reason,
      @Schema(description = "🔴 是否构成必填违规") boolean violation,
      @Schema(description = "🔴 违规码") String violationCode) {}

  /**
   * 一次套用的结果。
   *
   * @param idempotentReplay 这次请求是不是被幂等键挡下、返回了第一次的那一份结果
   * @param draftState 产出的草稿状态（恒为 DRAFT —— 套用不发布）
   */
  public record ListingTemplateApplyResult(
      @Schema(description = "套用记录 id") UUID applyId,
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "模板编码") String templateCode,
      @Schema(description = "模板名") String templateName,
      @Schema(description = "套用目标 listing id") UUID listingId,
      @Schema(description = "目标平台商品 ID 原文") String listingExternalItemId,
      @Schema(description = "目标店铺编码") String shopCode,
      @Schema(description = "目标平台编码") String platformCode,
      @Schema(description = "🔴 产出的草稿 id") UUID targetDraftId,
      @Schema(description = "🔴 产出的草稿状态（恒为 DRAFT）") String draftState,
      @Schema(description = "🔴 必填校验结论：PASS / FAIL") String validationStatus,
      @Schema(description = "成功求值的字段数") int appliedCount,
      @Schema(description = "🔴 处于「不知道」状态的字段数") int unknownCount,
      @Schema(description = "🔴 必填违规条数") int violationCount,
      @Schema(description = "🔴 逐字段求值明细") List<ListingTemplateApplyFieldRow> fields,
      @Schema(description = "🔴 必填违规清单（指到字段与规则）")
          List<ListingTemplateApplyFieldRow> violations,
      @Schema(description = "🔴 本次是不是幂等重放（返回了第一次的结果，没有重新求值）")
          boolean idempotentReplay,
      @Schema(description = "备注") String note,
      @Schema(description = "套用时刻") OffsetDateTime createdAt) {}

  /**
   * 套用记录的一行（列表用）。
   *
   * @param validationStatus 必填校验结论
   * @param fieldCount 逐字段明细条数
   */
  public record ListingTemplateApplyRow(
      @Schema(description = "套用记录 id") UUID applyId,
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "模板编码") String templateCode,
      @Schema(description = "模板名") String templateName,
      @Schema(description = "目标 listing id") UUID listingId,
      @Schema(description = "目标平台商品 ID 原文") String listingExternalItemId,
      @Schema(description = "🔴 产出的草稿 id") UUID targetDraftId,
      @Schema(description = "🔴 必填校验结论") String validationStatus,
      @Schema(description = "成功求值字段数") int appliedCount,
      @Schema(description = "🔴 「不知道」字段数") int unknownCount,
      @Schema(description = "🔴 必填违规条数") int violationCount,
      @Schema(description = "逐字段明细条数") int fieldCount,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "备注") String note,
      @Schema(description = "套用时刻") OffsetDateTime createdAt) {}

  /** 套用记录列表（真分页）。 */
  public record ListingTemplateApplyPage(
      @Schema(description = "当前页的套用记录") List<ListingTemplateApplyRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 落在模板适用范围内的 listing（一行 = 一条真的命中）。
   *
   * <p>这条列表是「适用范围判定有真实数据支撑」的证据：它是从库里按作用域真查出来的，
   * 不是服务层写死的字符串比较。
   */
  public record ListingTemplateMatchRow(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "类目路径") String categoryPath,
      @Schema(description = "是否已有待发布草稿") boolean hasDraft) {}

  /** 适用范围命中的 listing 列表（真分页）。 */
  public record ListingTemplateMatchPage(
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "适用范围说明") String scopeNote,
      @Schema(description = "命中的 listing") List<ListingTemplateMatchRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size) {}

  /** 筛选项词表（不硬编码在前端）。 */
  public record ListingTemplateFilterOptions(
      @Schema(description = "平台列表") List<PlatformOption> platforms,
      @Schema(description = "店铺列表") List<ShopOption> shops,
      @Schema(description = "站点列表") List<MarketplaceOption> marketplaces) {}

  public record PlatformOption(
      @Schema(description = "平台 id") UUID id, @Schema(description = "平台编码") String code,
      @Schema(description = "平台名") String name) {}

  public record ShopOption(
      @Schema(description = "店铺 id") UUID id, @Schema(description = "店铺编码") String code,
      @Schema(description = "店铺名") String name) {}

  public record MarketplaceOption(
      @Schema(description = "站点 id") UUID id, @Schema(description = "站点编码") String code,
      @Schema(description = "所属平台 id") UUID platformId) {}

}
