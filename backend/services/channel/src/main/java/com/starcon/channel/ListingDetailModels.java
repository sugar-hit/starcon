package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.1.2 Listing 详情 的传输模型。
 *
 * <p>蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs
 * 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输入：{@code listing_id}；聚合平台快照与内部数据；用户权限。输出：左右对照视图 +
 * 编辑入口 + 差异高亮 + 回写按钮。关系：上游 = 4.1.1.1；下游 = 4.2.1.1 编辑、4.3.2.1 回写。<b>差异对照是本模块的核心价值</b>：让运营一眼看出「平台上卖的价格/库存和我系统里设的不一样」。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象 {@code
 * {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 模型层就把最容易在赶工时被做丢的四件事钉死：
 *
 * <ol>
 *   <li><b>差异必须能区分三种结局</b>：{@code DIFFERENT} / {@code SAME} / {@code UNAVAILABLE}。
 *       蓝图原话是「平台上卖的价格/库存<b>和我系统里设的不一样</b>」—— 这是<b>双边</b>断言，而「系统里设的价格」归 05 定价、
 *       内部可售量归 07 库存，两者在本批次都<b>不存在</b>。所以 {@link ListingDiffRow} 里没有把「只有一侧」粉饰成 SAME，
 *       而是 {@code available=false} + {@code ownerModule} + {@code impact}。**把缺一侧写成 SAME 比不写更糟** ——
 *       那会让运营以为「平台上卖 0 块，系统里也是 0，一致」。
 *   <li><b>平台侧可售状态与内部可售量是两个字段、两个来源、两个归属</b>：{@code platformStockState} 是 04 自己拥有的平台事实，
 *       内部可售量在 {@code internalStock} 维度里 {@code available=false}。合成一个「库存」字段就看不见「平台已断货但内部还有货」。
 *   <li><b>内容对照只有左列有值</b>：内部草稿归 4.2.1.1（尚未交付），{@link ListingContentField#internalValue()} 恒为 null 且
 *       {@code internalKnown=false}，**不给草稿留一个「以后会填」的默认字符串**。
 *   <li><b>回写登记簿快照不是售价</b>：{@link ListingWritebackRegistryView} 带 source 与 limitation 两个字段，
 *       前端必须把它标成「回写登记簿当前值快照」，不许显示成「平台售价」。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：平台侧可售量与登记簿快照直接参与差异判定，
 * 浮点不可接受。本模型里<b>没有</b>「售价」字段 —— 卷 05 未交付，没有第二个价格事实来源。
 */
public final class ListingDetailModels {
  private ListingDetailModels() {}

  // ================================================================ 能力声明

  public record ListingDetailCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>价格 / 销量 / 内部可售量 / 内容草稿四个维度 {@code available=false} 并写明归属与影响。前端据此显示「未接入」，<b>不回 0、不显示假
   * 草稿</b>。
   */
  public record ListingDetailCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingDetailCapabilityDimension> dimensions,
      @Schema(
              description =
                  "🔴 售价是否已接入：卷 05 定价与价目系统整卷未交付，恒为 false，前端据此显示「价格能力未接入」")
          boolean priceAvailable,
      @Schema(description = "🔴 售价维度的归属模块") String priceOwnerModule,
      @Schema(description = "🔴 未接入售价时的界面措辞（前端原样展示，不要自己编）") String priceNote,
      @Schema(description = "🔴 内部可售量是否已接入：归 07 库存，恒为 false") boolean internalStockAvailable,
      @Schema(description = "🔴 内部可售量的归属模块") String internalStockOwnerModule,
      @Schema(description = "🔴 未接入内部可售量时的界面措辞（前端原样展示）") String internalStockNote,
      @Schema(description = "🔴 内容草稿是否已接入：归 4.2.1.1 刊登编辑器，恒为 false")
          boolean contentDraftAvailable,
      @Schema(description = "🔴 内容草稿的归属模块") String contentDraftOwnerModule,
      @Schema(description = "🔴 未接入内容草稿时的界面措辞（前端原样展示）") String contentDraftNote,
      @Schema(description = "🔴 销量是否已接入：归 09 订单，恒为 false") boolean salesAvailable,
      @Schema(description = "🔴 销量的归属模块") String salesOwnerModule,
      @Schema(description = "🔴 未接入销量时的界面措辞（前端原样展示）") String salesNote,
      @Schema(description = "内容对照字段清单（左右两列的字段顺序）") List<String> contentFields,
      @Schema(description = "差异分组：CONTENT / PRICE_STOCK / PLATFORM_SIDE / REGISTRY / MAPPING")
          List<String> diffGroups,
      @Schema(description = "编辑入口能否执行（本批次恒 false）") boolean editSupported,
      @Schema(description = "编辑入口归属模块") String editOwnerModule,
      @Schema(description = "编辑入口不可用的原因（前端原样展示）") String editReason,
      @Schema(description = "内容回写按钮能否执行（本批次恒 false）") boolean writebackSupported,
      @Schema(description = "内容回写归属模块") String writebackOwnerModule,
      @Schema(description = "内容回写不可用的原因（前端原样展示）") String writebackReason,
      @Schema(description = "🔴 平台操作记录的完整性说明") String platformOperationNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 平台原始信息

  /** 职责句里的「平台原始信息（ASIN/item_id、标题、状态、评分评论数）」。全部来自 V171 的 listing + 同库上游码表。 */
  public record ListingPlatformProfile(
      @Schema(description = "listing id") UUID id,
      @Schema(description = "上游 02 店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "上游 02 站点 id；为空表示未归站点") UUID marketplaceId,
      @Schema(description = "站点编码；为空表示未归站点") String marketplaceCode,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN / item_id），不折叠大小写") String externalItemId,
      @Schema(description = "平台标题原文") String title,
      @Schema(description = "平台类目路径原文") String categoryPath,
      @Schema(description = "平台类目 id") String platformCategoryId,
      @Schema(description = "平台商品状态") String status,
      @Schema(description = "审核状态") String reviewStatus,
      @Schema(description = "平台驳回原因 / 审核备注") String reviewMessage,
      @Schema(description = "负责人 id（登记时的归属快照）") UUID ownerId,
      @Schema(description = "平台评分；平台未给则为 null") BigDecimal rating,
      @Schema(description = "平台评论数") BigDecimal ratingCount,
      @Schema(description = "币种；仅作展示标签，本模块不据此做任何换算") String currency,
      @Schema(
              description =
                  "🔴 缺货标记（平台侧事实）：IN_STOCK / OUT_OF_STOCK / UNKNOWN。**与 07 的内部可售量不是一回事**")
          String platformStockState,
      @Schema(description = "平台侧可售量（02 同步 / 4.3.2.2 回流回报）；UNKNOWN 时为 null")
          BigDecimal platformStockQty,
      @Schema(description = "上架时刻") OffsetDateTime launchedAt,
      @Schema(description = "最近一次平台同步时刻") OffsetDateTime lastSyncedAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  // ================================================================ 内部映射

  /** 职责句里的「内部映射」。internalSkuId 是跨服务逻辑 id（03 拥有），本模块不跨库查它的编码 / 名称。 */
  public record ListingVariantMapping(
      @Schema(description = "变体 id") UUID id,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "🔴 映射状态：UNMAPPED / MAPPED / BUNDLE") String mappingStatus,
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 catalog 拥有）；未映射时为 null") UUID internalSkuId,
      @Schema(description = "组件数量（仅 BUNDLE 有意义）") BigDecimal componentQuantity,
      @Schema(description = "平台侧变体属性原文（json 对象字符串）") String attributes,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  // ================================================================ 内容对照

  /**
   * 🔴 内容对照的一行（左右两列）。
   *
   * <p>左列（平台当前内容）来自 {@code listing_content_snapshot}（本模块自己的表）；右列（内部草稿）归 4.2.1.1，
   * <b>恒为 {@code internalKnown=false}</b> 且 {@code internalValue=null}。
   */
  public record ListingContentField(
      @Schema(description = "字段键（title / bullet_points / description / images …）") String field,
      @Schema(description = "字段中文名") String label,
      @Schema(description = "🔴 左列标题：恒为「平台当前内容」") String platformLabel,
      @Schema(description = "平台侧取值原文；平台没给该字段时为 null") String platformValue,
      @Schema(description = "🔴 平台侧是否有值（false ≠ 空字符串：平台写了空标题和没写过标题是两件事）")
          boolean platformKnown,
      @Schema(description = "🔴 右列标题：恒为「内部草稿内容」") String internalLabel,
      @Schema(description = "🔴 内部草稿取值；4.2.1.1 未交付 ⇒ 恒为 null") String internalValue,
      @Schema(description = "🔴 内部侧是否有值；4.2.1.1 未交付 ⇒ 恒为 false") boolean internalKnown,
      @Schema(description = "🔴 该字段能否比对（恒 false，归属 4.2.1.1）") boolean available,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /** 平台当前内容快照的元信息（对照的左列载体是哪一份快照，必须能说清）。 */
  public record ListingContentSnapshotView(
      @Schema(description = "快照 id") UUID id,
      @Schema(description = "语言") String language,
      @Schema(description = "来源：SYNC / COLLECTION / MANUAL") String source,
      @Schema(description = "是否当前快照") boolean current,
      @Schema(description = "平台侧内容采集时刻") OffsetDateTime capturedAt,
      @Schema(description = "🔴 该 listing 共有多少份历史快照（追加写，不是就地覆盖）") long historyCount) {}

  // ================================================================ 差异

  /**
   * 🔴 差异对照的一行 —— 本模块的核心产出。
   *
   * <p>{@code status} 的取值域刻意把「两侧都有且不同」「两侧都有且相同」「只有一侧存在」「两侧里有一侧根本没接入」<b>分成四类</b>：
   * 把第四类写成 SAME 会让运营以为「平台上卖 0 块、系统里也是 0，一致」—— 那比不显示更糟。
   */
  public record ListingDiffRow(
      @Schema(description = "分组：CONTENT / PRICE_STOCK / PLATFORM_SIDE / REGISTRY / MAPPING") String group,
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度中文名") String label,
      @Schema(description = "🔴 左列标题（平台侧 / 系统登记簿侧）") String platformLabel,
      @Schema(description = "左列取值原文；无值时为 null") String platformValue,
      @Schema(description = "🔴 左列是否有值") boolean platformKnown,
      @Schema(description = "🔴 右列标题（内部侧）") String internalLabel,
      @Schema(description = "右列取值原文；无值时为 null") String internalValue,
      @Schema(description = "🔴 右列是否有值") boolean internalKnown,
      @Schema(
              description =
                  "DIFFERENT 不同 / SAME 相同 / PLATFORM_ONLY 只有左侧有 / INTERNAL_ONLY 只有右侧有 / UNAVAILABLE 有一侧未接入 / UNKNOWN 两侧都没值")
          String status,
      @Schema(description = "🔴 该维度能否比对（false 时 status 恒为 UNAVAILABLE）") boolean available,
      @Schema(description = "未交付段的责任模块；available=true 时为空") String ownerModule,
      @Schema(description = "不交付的影响；available=true 时为空") String impact,
      @Schema(description = "该行的口径说明（前端 tooltip / 小字，原样展示）") String note) {}

  public record ListingDiffReport(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "全部差异行（含未接入的行 —— 隐藏未接入的行等于隐藏缺口）")
          List<ListingDiffRow> rows,
      @Schema(description = "可比对且不同的条数（差异高亮计数）") int differentCount,
      @Schema(description = "可比对且相同的条数") int sameCount,
      @Schema(description = "🔴 因一侧未接入而不可比对的条数") int unavailableCount,
      @Schema(description = "🔴 差异口径总说明") String note) {}

  // ================================================================ 发布历史 / 平台操作记录

  /** 职责句里的「发布历史」。追加写，不可改写（V175 触发器兜底）。 */
  public record ListingPublishRecordView(
      @Schema(description = "发布记录 id") UUID id,
      @Schema(description = "本次发布所用的平台内容快照 id；无对应快照时为 null") UUID snapshotId,
      @Schema(description = "PUBLISH 4.3.1.1 发布 / CONTENT_WRITEBACK 4.3.2.1 内容回写") String action,
      @Schema(description = "SUCCEEDED 成功 / FAILED 平台失败 / REJECTED 生成前被拒") String outcome,
      @Schema(description = "平台返回的请求 ID") String platformRequestId,
      @Schema(description = "失败 / 被拒原因；成功时为空") String reason,
      @Schema(description = "操作者 id") UUID actorId,
      @Schema(description = "动作发生时刻") OffsetDateTime occurredAt) {}

  /** 职责句里的「平台操作记录」：直接读 V2 的 {@code platform_operation}，不复制任何一列。 */
  public record ListingPlatformOperationView(
      @Schema(description = "platform_operation id") UUID id,
      @Schema(description = "PUBLISH / UPDATE_PRICE / UPDATE_STOCK / SHIP_UPDATE / CANCEL_ORDER")
          String operation,
      @Schema(
              description =
                  "PENDING / SENDING / SUCCEEDED / FAILED / UNKNOWN。UNKNOWN 是「不知道平台上到底成没成」")
          String status,
      @Schema(description = "未知结果核验结论（2.5.1.2：先查证再决定重发）") String verification,
      @Schema(description = "已重试次数") int attempts,
      @Schema(description = "平台返回的请求 ID") String platformRequestId,
      @Schema(description = "最后一次错误") String lastError,
      @Schema(description = "payload 原文（json 字符串）") String payload,
      @Schema(description = "动作发生时刻") OffsetDateTime occurredAt) {}

  // ================================================================ 回写登记簿（只读）

  /**
   * 🔴 回写登记簿状态 —— <b>只读</b>，来自 V169 {@code writeback_target}（2.5.2.1 已交付）。
   *
   * <p>⚠️ {@code currentPrice} / {@code currentStock} 是「平台上当前在售的值」的<b>登记簿快照</b>，
   * <b>不是</b>售价的权威来源（卷 05 未交付），也<b>不是</b>内部可售量（归 07 库存）。前端必须带 source + limitation 显示，
   * 把它显示成「平台售价」就是本模块最典型的假事实。
   */
  public record ListingWritebackRegistryView(
      @Schema(description = "🔴 该 listing 是否已登记为可回写目标（false ⇒ 下面两列都是 null，不回 0）")
          boolean registered,
      @Schema(description = "writeback_target id；未登记时为 null") UUID targetId,
      @Schema(description = "登记簿里的当前库存快照；未登记或未填时为 null") BigDecimal currentStock,
      @Schema(description = "🔴 登记簿里的当前价快照；未登记或未填时为 null（**这不是售价**）")
          BigDecimal currentPrice,
      @Schema(description = "币种；仅作展示标签") String currency,
      @Schema(description = "ACTIVE / BLOCKED（BLOCKED = 已被封禁，不允许发起回写）") String status,
      @Schema(description = "封禁原因") String blockedReason,
      @Schema(description = "登记时刻") OffsetDateTime updatedAt,
      @Schema(description = "🔴 该快照的来源（前端原样展示）") String source,
      @Schema(description = "🔴 该快照的局限说明（前端原样展示）") String limitation) {}

  // ================================================================ 全景

  /** 「左右对照视图 + 编辑入口 + 差异高亮 + 回写按钮」在一次响应里给全。 */
  public record ListingDetail(
      @Schema(description = "职责句：平台原始信息") ListingPlatformProfile profile,
      @Schema(description = "🔴 内部映射状态：UNMAPPED / PARTIAL / MAPPED（列表级聚合）") String mappingStatus,
      @Schema(description = "平台变体条数") int variantCount,
      @Schema(description = "已映射到内部 SKU 的变体条数") int mappedVariantCount,
      @Schema(description = "职责句：内部映射") List<ListingVariantMapping> variants,
      @Schema(description = "职责句：内容对照（平台当前内容 vs 内部草稿内容）")
          List<ListingContentField> contentComparison,
      @Schema(description = "当前平台内容快照的元信息；从未登记过快照时为 null")
          ListingContentSnapshotView contentSnapshot,
      @Schema(description = "职责句：差异对照（本模块的核心价值）") ListingDiffReport diff,
      @Schema(description = "职责句：发布历史（新 → 旧）") List<ListingPublishRecordView> publishHistory,
      @Schema(description = "职责句：平台操作记录（新 → 旧）")
          List<ListingPlatformOperationView> platformOperations,
      @Schema(description = "状态变更轨迹（与发布历史是两回事）")
          List<ListingModels.ListingStatusChange> statusChanges,
      @Schema(description = "🔴 回写登记簿状态（只读，2.5.2.1 已交付）") ListingWritebackRegistryView writebackRegistry,
      @Schema(description = "🔴 差异高亮计数（便于列表页角标复用）") int differentCount,
      @Schema(description = "🔴 未接入维度清单") List<String> unavailableDimensions) {}

  // ================================================================ 写入（同步 / 发布入口登记结果）

  /**
   * 登记平台当前内容快照（02 同步 / 4.4.1 链接采集的写入入口）。
   *
   * <p>🔴 只有<b>平台侧</b>内容。内部草稿归 4.2.1.1，本请求体<b>没有任何草稿字段</b> ——
   * 留一个将来会填的草稿字段，会让人以为「草稿功能已交付，只是没填」。
   */
  public record SaveContentSnapshotRequest(
      @Schema(description = "语言，如 en-US / zh-CN；同一 listing 的一种语言只有一份当前快照")
          @NotBlank
          @Size(max = 16)
          String language,
      @Schema(description = "平台标题原文") @Size(max = 500) String title,
      @Schema(description = "平台侧要点数组（json 数组字符串，如 [\"轻便\",\"耐用\"]）") String bulletPoints,
      @Schema(description = "平台侧描述原文") String description,
      @Schema(description = "平台侧图片数组（json 数组字符串，元素为图片 url）") String images,
      @Schema(description = "平台侧视频 url") String videoUrl,
      @Schema(description = "平台侧 A+ 模块数组（json 数组字符串）") String aPlusModules,
      @Schema(description = "平台侧类目属性（json 对象字符串）") String categoryAttributes,
      @Schema(description = "平台侧合规声明（json 对象字符串）") String complianceStatements,
      @Schema(description = "来源：SYNC / COLLECTION / MANUAL；为空按 SYNC") String source,
      @Schema(description = "平台侧内容采集时刻；为空按 now") OffsetDateTime capturedAt,
      @Schema(description = "是否登记为该语言下的当前快照；为空按 true") Boolean current) {}

  /** 登记一次发布动作的结果（4.3.1.1 发布 / 4.3.2.1 内容回写调用）。本模块不代它们发起发布。 */
  public record SavePublishRecordRequest(
      @Schema(description = "本次发布所用的平台内容快照 id；人工在平台后台改的可以为空")
          UUID snapshotId,
      @Schema(description = "PUBLISH / CONTENT_WRITEBACK；为空按 PUBLISH") String action,
      @Schema(description = "SUCCEEDED / FAILED / REJECTED") @NotBlank String outcome,
      @Schema(description = "平台返回的请求 ID") @Size(max = 128) String platformRequestId,
      @Schema(description = "失败 / 被拒原因；FAILED / REJECTED 时必填") @Size(max = 500) String reason,
      @Schema(description = "动作发生时刻；为空按 now") OffsetDateTime occurredAt) {}

  /** 写入端点的返回值：登记后的当前快照 / 发布记录，供调用方拿到落库结果。 */
  public record SavePublishRecordResult(
      @Schema(description = "发布记录 id") UUID id,
      @Schema(description = "本次引用的内容快照 id") UUID snapshotId,
      @Schema(description = "动作") String action,
      @Schema(description = "结果") String outcome,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt,
      @Schema(description = "🔴 写回值逐字回显（AGENTS.md：timestamptz 必须断言写回的值）") String occurredAtEcho) {}

  /** 内容对照的完整响应：左列载体是哪一份快照 + 逐字段的左右两列。 */
  public record ListingContentComparison(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "当前平台内容快照的元信息；从未登记过快照时为 null")
          ListingContentSnapshotView snapshot,
      @Schema(description = "🔴 逐字段左右对照（右列恒为未接入，归属 4.2.1.1）")
          List<ListingContentField> fields,
      @Schema(description = "🔴 内部草稿是否已接入（4.2.1.1，恒 false）") boolean internalDraftAvailable,
      @Schema(description = "内部草稿的归属模块") String internalDraftOwnerModule,
      @Schema(description = "🔴 内容对照口径说明（前端原样展示）") String note) {}

  /** 内容快照登记结果。 */
  public record SaveContentSnapshotResult(
      @Schema(description = "快照 id") UUID id,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "语言") String language,
      @Schema(description = "是否当前快照") boolean current,
      @Schema(description = "采集时刻") OffsetDateTime capturedAt,
      @Schema(description = "🔴 采集时刻写回值逐字回显") String capturedAtEcho,
      @Schema(description = "该 listing 该语言的历史快照总数") long historyCount) {}
}
