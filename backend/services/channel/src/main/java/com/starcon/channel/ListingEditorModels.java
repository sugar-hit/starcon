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
 * 4.2.1.1 刊登编辑器 的传输模型。
 *
 * <p>蓝图：「单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、A+
 * 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。输入：源商品（03
 * SKU/SPU）、目标店铺与站点、平台字段要求（02 能力矩阵）；用户编辑内容；已填内容的草稿。输出：内容草稿（按平台 +
 * 站点 + 语言分别保存）；提交发布或保存模板的动作；校验结果。关系：上游 = 03 商品主数据、03 媒体与文案、02 平台字段要求；下游 =
 * 4.3.1.1 发布。<b>表单字段由平台要求驱动生成</b>，不是固定一套表单套所有平台——平台能力矩阵变化时表单自动变化。」
 *
 * <p>🔴 模型层就把四件事钉死：
 *
 * <ol>
 *   <li><b>字段集是数据不是代码</b>：{@link EditorFieldSchema} 的每一项都带 requirement /
 *       constraintState / capabilityState —— 前端照这三项渲染，<b>不自己判断必填</b>。
 *   <li><b>没有"直接保存到线上"的入口</b>：本模块唯一的持久化写路径是保存草稿与提交入队，
 *       产出都是 {@link EditorDraftRow} / {@link EditorSubmissionRow}，**没有任何一个改线上的端点**。
 *   <li><b>"不知道"是一个状态</b>：constraintState / capabilityState / requirementsState 都有
 *       {@code UNKNOWN} 取值，配 ownerModule —— <b>不回退成"没有约束"</b>。
 *   <li><b>提交审核只是入队</b>：{@link EditorSubmissionRow#state()} 的 QUEUED 语义是"已入队"，
 *       不是"已发布"。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，
 * codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。动态字段集用
 * {@link EditorFieldValue}（field + 四种值形状之一）表达，与 4.4.2.1 的
 * {@code ListingClaimFieldDecision} 同一手法。
 */
public final class ListingEditorModels {
  private ListingEditorModels() {}

  // ================================================================ 能力声明

  public record EditorCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record EditorCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<EditorCapabilityDimension> dimensions,
      @Schema(description = "表单字段键取值") List<String> fieldKeys,
      @Schema(description = "字段要求状态取值：KNOWN / DELEGATED / UNKNOWN") List<String> constraintStates,
      @Schema(description = "能力状态取值：SUPPORTED / UNSUPPORTED / UNKNOWN / NOT_APPLICABLE")
          List<String> capabilityStates,
      @Schema(description = "平台要求整体状态取值：KNOWN / PARTIAL / UNKNOWN") List<String> requirementsStates,
      @Schema(description = "🔴 草稿态与线上态分离的说明；前端据此禁用任何「直接上线」按钮")
          String draftIsolationNote,
      @Schema(description = "🔴 提交审核语义说明（只是入队）") String submitNote,
      @Schema(description = "🔴 刊登模板套用未交付时的界面措辞（前端原样展示）") String templateNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 表单 schema（平台要求驱动）

  /**
   * 一栏字段的渲染契约。
   *
   * <p>🔴 {@code requirement} / {@code constraintState} / {@code capabilityState} 三者**互不推导**：
   *
   * <ul>
   *   <li>{@code requirement} = 这一栏必不必填（来自平台字段要求注册表）。
   *   <li>{@code constraintState} = 长度 / 条数这类细则**知不知道**：KNOWN（知道）/ DELEGATED（细则在别的模块）/ UNKNOWN（不知道）。
   *   <li>{@code capabilityState} = 平台**支不支持**这一栏：SUPPORTED / UNSUPPORTED / UNKNOWN（能力矩阵没声明）/ NOT_APPLICABLE。
   * </ul>
   */
  public record EditorFieldSchema(
      @Schema(description = "字段键：TITLE / BULLETS / DESCRIPTION / VARIANT_DIMENSIONS / IMAGES / VIDEO / "
          + "A_PLUS / CATEGORY_ATTRIBUTES / COMPLIANCE_STATEMENTS") String field,
      @Schema(description = "字段中文名") String label,
      @Schema(description = "值形状：TEXT / LONG_TEXT / TEXT_LIST / IMAGE_LIST / VIDEO_REF / MODULE_LIST / "
          + "ATTRIBUTE_MAP / STATEMENT_MAP") String valueKind,
      @Schema(description = "必填性：REQUIRED / RECOMMENDED / OPTIONAL / UNSUPPORTED") String requirement,
      @Schema(description = "约束状态：KNOWN / DELEGATED / UNKNOWN") String constraintState,
      @Schema(description = "能力状态：SUPPORTED / UNSUPPORTED / UNKNOWN / NOT_APPLICABLE") String capabilityState,
      @Schema(description = "最小长度；null 表示无下限约束", nullable = true) Integer minLength,
      @Schema(description = "最大长度；null 表示上限未知（不是 0）", nullable = true) Integer maxLength,
      @Schema(description = "最少条数；null 表示无下限约束", nullable = true) Integer minItems,
      @Schema(description = "最多条数；null 表示上限未知（不是 0）", nullable = true) Integer maxItems,
      @Schema(description = "是否可由运营编辑（变体维度归映射域，只读）") boolean editable,
      @Schema(description = "取值来源：OPERATOR 运营填写 / LISTING_VARIANT 由变体派生") String valueSource,
      @Schema(description = "🔴 约束细则的归属模块（DELEGATED / UNKNOWN 时非空）", nullable = true)
          String ownerModule,
      @Schema(description = "平台侧原文备注；前端原样展示") String note,
      @Schema(description = "🔴 平台明确不支持时的原因；前端原样展示", nullable = true)
          String unavailableReason) {}

  /** 一个语言页签。 */
  public record EditorLocaleTab(
      @Schema(description = "语言代码") String locale,
      @Schema(description = "该语言是否已有草稿") boolean hasDraft,
      @Schema(description = "草稿状态：DRAFT / SUBMITTED / DISCARDED；没有草稿时为空串") String draftState,
      @Schema(description = "草稿 id；没有草稿时为空串") String draftId) {}

  /**
   * 表单 schema：**由平台要求推导出来的**字段集。
   *
   * <p>🔴 {@code requirementsState} = UNKNOWN 时 {@code fields} 可能是空的 —— 那是"平台要求取不到"，
   * <b>不是</b>"这个平台没有要填的字段"。前端据此显示"要求未知"而不是一个空表单。
   */
  public record EditorFormSchema(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "平台接入状态：AVAILABLE / BETA / PLANNED（来自 2.1.1.1 平台注册表）")
          String supportStatus,
      @Schema(description = "接入类型：OFFICIAL_API / INTERMEDIARY / THIRD_PARTY_ISV") String integration,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "站点名") String marketplaceName,
      @Schema(description = "站点国家码") String countryCode,
      @Schema(description = "站点默认语言") String defaultLocale,
      @Schema(description = "🔴 平台要求整体状态：KNOWN / PARTIAL / UNKNOWN") String requirementsState,
      @Schema(description = "🔴 要求未知时的归属模块；非空表示这一段没有取到", nullable = true)
          String requirementsOwnerModule,
      @Schema(description = "要渲染的字段（字段集由平台要求推导，不是固定一套）") List<EditorFieldSchema> fields,
      @Schema(description = "平台明确不支持的字段（带原因，前端原样展示）") List<EditorFieldSchema> unsupportedFields,
      @Schema(description = "🔴 约束细则不在本模块的字段（DELEGATED）") List<EditorFieldSchema> delegatedFields,
      @Schema(description = "🔴 平台是否支持未知 / 约束未知的字段（UNKNOWN）") List<EditorFieldSchema> unknownFields,
      @Schema(description = "多语言页签") List<EditorLocaleTab> locales,
      @Schema(description = "🔴 草稿隔离说明；前端据此禁用任何「直接上线」按钮") String draftIsolationNote) {}

  // ================================================================ 字段值（动态字段集的载荷）

  /** 键值对（类目属性 / 合规声明 / 变体维度）。 */
  public record EditorKeyValue(
      @Schema(description = "键") @NotBlank @Size(max = 128) String key,
      @Schema(description = "值") @Size(max = 2000) String value) {}

  /** A+ 模块。 */
  public record EditorModule(
      @Schema(description = "模块类型（平台侧原文）") @Size(max = 64) String type,
      @Schema(description = "模块标题") @Size(max = 500) String heading,
      @Schema(description = "模块正文") @Size(max = 5000) String body,
      @Schema(description = "模块配图引用（素材存储键 / URL）") @Size(max = 1024) String imageRef) {}

  /**
   * 一栏字段的值。
   *
   * <p>🔴 四个值形状**按 valueKind 择一**：TEXT / LONG_TEXT / VIDEO_REF 用 {@code text}；
   * TEXT_LIST / IMAGE_LIST 用 {@code items}；MODULE_LIST 用 {@code modules}；ATTRIBUTE_MAP /
   * STATEMENT_MAP 用 {@code pairs}。前端按 schema 给出的 valueKind 决定用哪一个。
   */
  public record EditorFieldValue(
      @Schema(description = "字段键") @NotBlank @Size(max = 64) String field,
      @Schema(description = "文本值（TEXT / LONG_TEXT / VIDEO_REF）", nullable = true) @Size(max = 20000)
          String text,
      @Schema(description = "列表值（TEXT_LIST / IMAGE_LIST）", nullable = true)
          @Valid List<@Size(max = 2000) String> items,
      @Schema(description = "模块列表值（MODULE_LIST）", nullable = true) @Valid List<EditorModule> modules,
      @Schema(description = "键值对（ATTRIBUTE_MAP / STATEMENT_MAP）", nullable = true)
          @Valid List<EditorKeyValue> pairs) {}

  // ================================================================ 校验结果

  /** 一条违规。🔴 每条都指到具体字段（{@code field}）。 */
  public record EditorViolation(
      @Schema(description = "🔴 违规字段键") String field,
      @Schema(description = "字段中文名") String label,
      @Schema(description = "BLOCKING 阻断提交审核 / WARNING 提醒") String severity,
      @Schema(description = "违规码：REQUIRED_MISSING / LENGTH_EXCEEDED / ITEM_COUNT_EXCEEDED / "
          + "ITEM_COUNT_UNDER / RECOMMENDED_EMPTY / FIELD_NOT_DECLARED / REQUIREMENTS_DELEGATED / "
          + "REQUIREMENTS_UNKNOWN / CAPABILITY_UNKNOWN")
          String code,
      @Schema(description = "人话说明（前端原样展示）") String message,
      @Schema(description = "🔴 细则归属模块；UNKNOWN / DELEGATED 时非空", nullable = true)
          String ownerModule,
      @Schema(description = "触发上限；null 表示不是上限类违规", nullable = true) Integer limit,
      @Schema(description = "实际值；null 表示不是上限类违规", nullable = true) Integer actual) {}

  /** 校验结果（输出三）。 */
  public record EditorValidation(
      @Schema(description = "是否没有阻断项") boolean valid,
      @Schema(description = "阻断项数量") int blockingCount,
      @Schema(description = "提醒项数量") int warningCount,
      @Schema(description = "🔴 逐条违规（每条指到具体字段）") List<EditorViolation> violations,
      @Schema(description = "一句话结论") String summary) {}

  // ================================================================ 草稿

  /** 保存草稿请求（输入三：用户编辑内容）。 */
  public record EditorDraftRequest(
      @Schema(description = "目标店铺 id") @NotNull UUID shopId,
      @Schema(description = "目标站点 id") @NotNull UUID marketplaceId,
      @Schema(description = "语言代码（内容按平台 + 站点 + 语言分别保存）") @NotBlank @Size(max = 16) String locale,
      @Schema(description = "源商品 SKU id（03，跨服务逻辑 id）；与 listingId 至少给一个", nullable = true)
          UUID sourceSkuId,
      @Schema(description = "关联的线上 listing id；与 sourceSkuId 至少给一个", nullable = true) UUID listingId,
      @Schema(description = "目标平台类目路径（平台侧原文）") @Size(max = 500) String categoryPath,
      @Schema(description = "逐栏内容") @NotNull @Valid List<EditorFieldValue> fieldValues,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /**
   * 草稿行（输出一：内容草稿）。
   *
   * <p>🔴 {@code state} 只会是 DRAFT（编辑中）/ SUBMITTED（已入队待派发）/ DISCARDED（已废弃）。
   * {@code submitted} 为 true 只代表**已入队**，不代表已发布。
   */
  public record EditorDraftRow(
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "目标店铺 id") UUID shopId,
      @Schema(description = "目标店铺编码") String shopCode,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点 id") UUID marketplaceId,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "源商品 SKU id", nullable = true) UUID sourceSkuId,
      @Schema(description = "关联的线上 listing id", nullable = true) UUID listingId,
      @Schema(description = "线上平台商品 ID 原文") String externalItemId,
      @Schema(description = "目标平台类目路径") String categoryPath,
      @Schema(description = "逐栏内容") List<EditorFieldValue> fieldValues,
      @Schema(description = "DRAFT / SUBMITTED / DISCARDED") String state,
      @Schema(description = "草稿派生的线上版本") long baseVersion,
      @Schema(description = "🔴 线上在草稿生成后被改过（发布前必须复核）") boolean stale,
      @Schema(description = "🔴 最近一次校验结果（提交入队时的证据）") EditorValidation validation,
      @Schema(description = "🔴 线上当前内容（对照列；来自 4.1.1.2 的内容快照与 listing 本身）")
          List<EditorFieldValue> liveValues,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt,
      @Schema(description = "入队时刻；仅 SUBMITTED 非空", nullable = true) OffsetDateTime submittedAt) {}

  // ================================================================ 提交审核（入队）

  /** 提交审核请求（草稿 id 走路径，body 只带幂等键）。 */
  public record EditorSubmitRequest(
      @Schema(description = "🔴 幂等键：同一个键重复提交返回同一条入队记录，不产生第二条")
          @NotBlank @Size(max = 128) String idempotencyKey) {}

  /**
   * 入队记录（输出二：提交发布动作）。
   *
   * <p>🔴 {@code state} = QUEUED 的含义是**已入队**：真正发给平台是 4.3.1.1 一键刊登的职责
   * （集成头未交付），所以这里没有、也不该有"已发布"这个状态。
   */
  public record EditorSubmissionRow(
      @Schema(description = "入队记录 id") UUID submissionId,
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "QUEUED 已入队 / DISPATCHED 已派发 / FAILED 派发失败 / CANCELLED 已撤销")
          String state,
      @Schema(description = "🔴 入队那一刻的内容快照") List<EditorFieldValue> content,
      @Schema(description = "🔴 入队那一刻的校验证据") EditorValidation validation,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "入队时刻") OffsetDateTime queuedAt,
      @Schema(description = "派发时刻；仅 DISPATCHED 非空", nullable = true) OffsetDateTime dispatchedAt,
      @Schema(description = "🔴 派发说明（谁负责真正发给平台）") String dispatchNote) {}

  // ================================================================ 校验（不落库）

  /** 试算校验请求：对给定内容算一次校验结果，不保存。 */
  public record EditorValidateRequest(
      @Schema(description = "目标店铺 id") @NotNull UUID shopId,
      @Schema(description = "目标站点 id") @NotNull UUID marketplaceId,
      @Schema(description = "语言代码") @NotBlank @Size(max = 16) String locale,
      @Schema(description = "目标平台类目路径") @Size(max = 500) String categoryPath,
      @Schema(description = "逐栏内容") @NotNull @Valid List<EditorFieldValue> fieldValues) {}

  // ================================================================ 上游只读投影（03）

  /** 源商品（03 SKU 主数据）投影。 */
  public record EditorSourceProduct(
      @Schema(description = "SKU id") String skuId,
      @Schema(description = "SKU 编码") String code,
      @Schema(description = "SKU 名称") String name,
      @Schema(description = "SKU 状态") String status,
      @Schema(description = "SPU 编码") String spuCode,
      @Schema(description = "SPU 名称") String spuName,
      @Schema(description = "品牌") String brand,
      @Schema(description = "类目") String category,
      @Schema(description = "规格（变体维度候选）") List<EditorKeyValue> specs,
      @Schema(description = "属性（可用于类目属性预填）") List<EditorKeyValue> attributes,
      @Schema(description = "🔴 该 SKU 挂着的素材（预填图片集用）") List<EditorMediaAsset> media) {}

  /** 媒体库素材（03 媒体库）投影。 */
  public record EditorMediaAsset(
      @Schema(description = "素材 id") String id,
      @Schema(description = "素材名") String name,
      @Schema(description = "素材类型（IMAGE / VIDEO …）") String mediaType,
      @Schema(description = "存储键（图片集里填的就是它）") String storageKey,
      @Schema(description = "宽（像素）", nullable = true) Integer widthPx,
      @Schema(description = "高（像素）", nullable = true) Integer heightPx,
      @Schema(description = "完整性状态：VERIFIED / MISSING / UNVERIFIED") String integrityStatus) {}
}
