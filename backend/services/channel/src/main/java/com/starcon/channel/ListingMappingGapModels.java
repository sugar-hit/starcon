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
 * 2.4.2.3 映射缺失队列 的传输模型。
 *
 * <p>蓝图：「**职责**：平台商品已同步但未映射到内部 SKU 的记录，等待人工建立映射或创建新 SKU。
 * **输入**：{@code listing_variant} 无映射的记录；{@code listing_mapping_version} 的错误状态。
 * **输出**：待映射清单（平台商品、变体、店铺、价格）；操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。
 * **关系**：上游 = 04 刊登与内容、09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。
 * <b>不按名称猜 SKU</b>；映射建立后回填受影响订单，历史订单冻结当时映射版本。」
 *
 * <p>🔴 模型层就把三条边界钉死（它们是最容易被赶工做丢的）：
 *
 * <ol>
 *   <li><b>「不按名称猜 SKU」</b> → 队列行<b>没有任何"建议 / 疑似匹配"字段</b>。
 *       模型里不存在 {@code suggestedSkuId} / {@code confidence} 这种形状 —— 那个形状一存在，
 *       调用方就会在赶订单时选它。候选与相似度归 4.1.2.1 的 {@code listing_mapping_candidate}，
 *       那里恒带 {@code requiresConfirmation}；本队列只摆缺口，决策由人给。
 *   <li><b>「历史订单冻结当时映射版本」</b> → {@link MappingGapRow#mappingVersionId} 记的是
 *       <b>当时解决这个缺口的那一版</b>，且它<b>不可改写</b>（DB 触发器钉死）。
 *       🔴 本模型<b>没有</b>「受影响订单条数」字段：09 订单尚未接入，
 *       填 0 会被读成"没有订单受影响"，而真相是"订单还没接进来" ——
 *       缺口落在 {@link MappingGapCapabilities#orderSourceWired} 与
 *       {@link MappingGapDetail#orderBackfillNote} 上。
 *   <li><b>跨服务纪律</b> → {@code internalSkuId} 是 <b>03 商品的逻辑 ID</b>，
 *       跨服务不建 FK、不复制 catalog 任何一列（与 4.1.2.1 同一纪律）。
 * </ol>
 *
 * <p>🔴 价格：{@code price} 是**平台侧售价快照**（{@code numeric(19,4)} + {@link BigDecimal}，禁浮点），
 * 与 05 定价的「系统价」是**两个事实**。本批次 channel 侧没有平台售价事实，
 * 所以平台商品来源恒为 null，能力声明里 {@code priceAvailable=false} + 归属 05 定价 + 影响。
 */
public final class ListingMappingGapModels {
  private ListingMappingGapModels() {}

  // ================================================================ 能力声明

  public record MappingGapCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record MappingGapCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<MappingGapCapabilityDimension> dimensions,
      @Schema(description = "队列状态取值：PENDING / MAPPED / IGNORED") List<String> statuses,
      @Schema(description = "人工决策取值：NONE / MAPPED / CREATE_SKU / IGNORED") List<String> decisions,
      @Schema(description = "入队原因取值") List<String> gapReasons,
      @Schema(description = "来源取值：LISTING_SYNC / ORDER") List<String> sources,
      @Schema(description = "🔴 平台侧售价是否可用（false = 归 05 定价，本模块不画假价格）")
          boolean priceAvailable,
      @Schema(description = "🔴 价格未接入时的措辞") String priceNote,
      @Schema(description = "🔴 价格未接入的责任模块") String priceOwnerModule,
      @Schema(description = "🔴 09 订单来源是否已接线（false = trade 侧尚未推事件）")
          boolean orderSourceWired,
      @Schema(description = "🔴 未交付段说明（前端原样展示，不要自己编）") String gapNote,
      @Schema(description = "上游 / 下游依赖说明") String dependencyNote) {}

  // ================================================================ 清单

  /**
   * 待映射清单的一行。
   *
   * <p>🔴 这一行<b>没有</b>任何"建议 SKU / 相似度"字段 —— 见类注释的第一条边界。
   */
  public record MappingGapRow(
      @Schema(description = "缺口 id") UUID id,
      @Schema(description = "上游 4.1.1.1 listing id") UUID listingId,
      @Schema(description = "平台变体 id（4.1.1.1 listing_variant）") UUID listingVariantId,
      @Schema(description = "来源：LISTING_SYNC 平台商品同步 / ORDER 订单缺映射（待接线）")
          String source,
      @Schema(description = "入队原因：VARIANT_UNMAPPED / MAPPING_RELEASED / ORDER_UNMAPPED")
          String gapReason,
      @Schema(description = "队列状态：PENDING 在队 / MAPPED 已建映射 / IGNORED 已忽略") String status,
      @Schema(description = "人工决策：NONE / MAPPED / CREATE_SKU / IGNORED") String decision,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "🔴 平台标题快照（入队那一刻冻结）") String titleSnapshot,
      @Schema(description = "平台变体 ID 原文") String externalVariantId,
      @Schema(description = "🔴 平台变体属性快照（jsonb 原文）") String attributesSnapshot,
      @Schema(description = "🔴 平台侧售价快照；NUMERIC(19,4)，本批次平台商品来源恒为 null")
          BigDecimal price,
      @Schema(description = "币种；与 price 同生共死") String currency,
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 拥有，跨服务不建 FK）") UUID internalSkuId,
      @Schema(description = "建立映射后回填到的映射 id") UUID mappingId,
      @Schema(description = "🔴 回填到的那一版（历史事实，不可改写）") UUID mappingVersionId,
      @Schema(description = "🔴 解决这个缺口的那一版的生效时间点（timestamptz 往返逐字相等）")
          OffsetDateTime mappedEffectiveFrom,
      @Schema(description = "决策人 id") UUID decidedBy,
      @Schema(description = "决策时刻") OffsetDateTime decidedAt,
      @Schema(description = "决策备注") String decisionNote,
      @Schema(description = "忽略原因（status=IGNORED 时有值）") String ignoreReason,
      @Schema(description = "🔴 回填时刻；映射建立时由 DB 触发器盖上") OffsetDateTime backfilledAt,
      @Schema(description = "订单来源的外部单号（本地来源为空串）") String orderRef,
      @Schema(description = "在队天数（入队至今）") long waitingDays,
      @Schema(description = "入队时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record MappingGapPage(
      @Schema(description = "当前页") List<MappingGapRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 本页在队条数") int pendingInPage,
      @Schema(description = "🔴 本页带平台价快照的条数（其余价格位显示「未接入」）")
          int priceSnapshotInPage) {}

  /** 队列详情：清单行 + 建档请求 + 订单回填的如实说明。 */
  public record MappingGapDetail(
      @Schema(description = "缺口") MappingGapRow gap,
      @Schema(description = "🔴 「新建 SKU」的建档请求（当前 03 侧未接线，只有登记没有派发）")
          List<MappingGapSkuRequestView> skuRequests,
      @Schema(description = "🔴 09 订单回填的如实说明（本批次未接线，不编造受影响订单数）")
          String orderBackfillNote) {}

  public record MappingGapSkuRequestView(
      @Schema(description = "请求 id") UUID id,
      @Schema(description = "期望的 SKU 编码") String requestedCode,
      @Schema(description = "期望的 SKU 名称") String requestedName,
      @Schema(description = "期望的条码") String requestedBarcode,
      @Schema(description = "期望的基本单位") String requestedBaseUnit,
      @Schema(description = "PENDING 已登记待派发 / DISPATCHED 已派发 / FAILED 失败 / CANCELLED 已撤单")
          String status,
      @Schema(description = "派发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "🔴 03 建出来的 SKU 逻辑 id（未派发时为 null）") UUID createdSkuId,
      @Schema(description = "失败原因") String lastError,
      @Schema(description = "登记时刻") OffsetDateTime createdAt) {}

  /** 队列概览：状态分布 / 原因分布 / 店铺分布 / 最老的一条。 */
  public record MappingGapSummary(
      @Schema(description = "按状态分布") List<MappingGapCount> byStatus,
      @Schema(description = "按入队原因分布") List<MappingGapCount> byReason,
      @Schema(description = "按店铺分布（只列在队的）") List<MappingGapShopCount> byShop,
      @Schema(description = "🔴 在队总条数") long pendingTotal,
      @Schema(description = "🔴 最老的一条在队时刻") OffsetDateTime oldestPendingAt,
      @Schema(description = "🔴 带平台价快照的条数（其余价格位未接入）") long priceSnapshotCount,
      @Schema(description = "🔴 09 订单来源是否已接线") boolean orderSourceWired) {}

  public record MappingGapCount(
      @Schema(description = "分组键") String key, @Schema(description = "条数") long total) {}

  public record MappingGapShopCount(
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "在队条数") long total) {}

  /** 筛选面板词表（不硬编码在前端）。 */
  public record MappingGapFilterOptions(
      @Schema(description = "店铺编码（仅本租户可见的）") List<MappingGapShopOption> shops,
      @Schema(description = "平台编码") List<String> platformCodes,
      @Schema(description = "队列状态") List<String> statuses,
      @Schema(description = "人工决策") List<String> decisions,
      @Schema(description = "入队原因") List<String> gapReasons,
      @Schema(description = "来源") List<String> sources,
      @Schema(description = "排序取值：RECENT 最近入队 / OLDEST 最久未处理 / PRICE 高价优先 / SHOP 按店铺")
          List<String> sorts) {}

  public record MappingGapShopOption(
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名称") String shopName,
      @Schema(description = "平台编码") String platformCode) {}

  // ================================================================ 三个操作入口

  /**
   * 操作一：映射到已有 SKU。
   *
   * <p>🔴 {@code internalSkuId} 必须是**人给的**（03 的逻辑 id），本模块**不提供任何自动匹配**。
   */
  public record MappingGapMapRequest(
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 拥有）；必须由人明确给出")
          @NotNull UUID internalSkuId,
      @Schema(description = "映射基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE") @NotBlank String relationType,
      @Schema(description = "套装组件数量；仅 ONE_TO_MANY 有值，NUMERIC(19,6) 口径")
          @DecimalMin(value = "0", inclusive = false) BigDecimal componentQuantity,
      @Schema(description = "🔴 变更原因（必填）：映射版本历史靠它回溯「当时为什么这么建」")
          @NotBlank @Size(max = 500) String changeReason,
      @Schema(description = "🔴 生效时间点起点；不传 = 此刻。传了就必须逐字往返（timestamptz）")
          OffsetDateTime effectiveFrom) {}

  /**
   * 操作二：新建 SKU。
   *
   * <p>🔴 本操作**不在本服务建档**：03 建档需要 SPU 归属 + 编码规则 + 属性值，
   * channel 一个都没有。本端点只把意图**登记**下来（落库、可查、可撤），
   * 派发到 03 是**待接线**（{@code status=PENDING}）—— 不假装"SKU 已创建"。
   */
  public record MappingGapCreateSkuRequest(
      @Schema(description = "期望的 SKU 编码（登记到 03 建档请求里）") @Size(max = 64) String requestedCode,
      @Schema(description = "期望的 SKU 名称") @Size(max = 200) String requestedName,
      @Schema(description = "期望的条码") @Size(max = 128) String requestedBarcode,
      @Schema(description = "期望的基本单位") @Size(max = 32) String requestedBaseUnit,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 操作三：标记忽略。 */
  public record MappingGapIgnoreRequest(
      @Schema(description = "🔴 忽略原因（必填）：不写原因就没人知道为什么这条不用映射")
          @NotBlank @Size(max = 500) String reason,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 三个操作入口的统一结果。 */
  public record MappingGapActionResult(
      @Schema(description = "缺口（操作后的状态）") MappingGapRow gap,
      @Schema(description = "🔴 本次动作的如实说明") String message) {}

  /**
   * 订单来源的**接入点**（事件入口）。
   *
   * <p>🔴 当前 trade 侧**没有**推这条事件，所以本端点目前没有生产者。
   * 它是**接入点本身**：字段与校验都是真的，事件来了就能直接进队列；
   * 能力声明里的 {@code orderSourceWired=false} 说清"还没接"。
   */
  public record MappingGapOrderRequest(
      @Schema(description = "🔴 订单外部单号（trade 侧的原单号）") @NotBlank @Size(max = 128) String orderRef,
      @Schema(description = "平台变体 id（4.1.1.1 listing_variant）") @NotNull UUID listingVariantId,
      @Schema(description = "🔴 平台侧售价（事件载荷携带的平台价，可为空）")
          @DecimalMin(value = "0") BigDecimal price,
      @Schema(description = "币种（与 price 同生共死）") @Size(max = 3) String currency,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 事件入口的受理结果。 */
  public record MappingGapOrderAccepted(
      @Schema(description = "缺口 id") UUID gapId,
      @Schema(description = "🔴 受理说明（含「当前 trade 侧未推这条事件」的如实声明）") String message) {}

  /** 「新建 SKU」请求的登记结果。 */
  public record MappingGapSkuRequestResult(
      @Schema(description = "建档请求") MappingGapSkuRequestView request,
      @Schema(description = "🔴 派发状态说明（当前 03 侧未接线 ⇒ PENDING）") String message) {}
}
