package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.1.3 可用量计算。
 *
 * <p>蓝图原文：「统一计算并展示可售量（available），明确其口径：bucket 可用 = 在手 − 预占；
 * 可售 = 合格且允许销售的 bucket 可用量之和，再按策略扣一次安全缓冲。待检、残次、未上架等被排除的
 * bucket 不重复扣减；支持查看构成拆解。」「输入：bucket 明细；质量状态；预占状态；渠道可售量策略
 * （7.2.2.1）。输出：可售量数值 + 构成拆解（每一项扣了多少）。」「<b>可售量是全系统的单一口径来源</b>，
 * 不允许各系统各自算 —— 这是超卖的第一根源。」
 *
 * <p><b>本模块不建任何表。</b>可售量是 stock_bucket（V2）× inventory_quality_status（V1）×
 * warehouse_location / inventory_lot（V2）× inventory_policy（V1）四份**已在库的事实**在读时派生出来的
 * 一个数。存一张 {@code available_quantity} 表等于把一份派生数据复制一份，而每一次入库 / 预占 / 释放 /
 * 盘点都要去同步它 —— 漏同步一次，界面上就会出现一个比库存更笃定的错数，而错的那个恰好是防超卖用的。
 * 蓝图要求的是「统一计算并展示」，统一的办法是**只有一个地方算**，不是多一张表。
 * 同 8.5.2.1 库位利用率的判断（建议与占用都是读时派生，存下来只会立刻变陈旧）。
 *
 * <p><b>口径（整条算式，逐项可审阅）</b>：
 *
 * <pre>
 *   ① bucket 可用  = on_hand − reserved          ← stock_bucket.available 生成列，没有第三个来源
 *   ② 可售基数      = Σ available，限「合格且允许销售」的 bucket
 *                     合格       : inventory_quality_status.role = 'SELLABLE'
 *                     允许销售   : warehouse_location.pickable = true 且 status = 'ACTIVE'
 *                     批次未冻结 : lot 为空或 inventory_lot.status = 'ACTIVE'
 *   ③ 可售量        = max(0, ② − ④)
 *   ④ 安全缓冲      = Σ_SKU 按 SKU &gt; WAREHOUSE &gt; DEFAULT 解析出的 inventory_policy.safety_stock
 * </pre>
 *
 * <p><b>「待检、残次、未上架不重复扣减」在算式里的确切含义</b>：② 是一次<b>筛选</b>，不是一次减法。
 * 所以扣减区被切成互斥且穷尽的五片，同一批 bucket 的 available 在五片上**各自只出现一次**：
 *
 * <pre>
 *   合格可售 + 待检 + 残次 + 未上架/不可发货 + 批次冻结 == available（合计恒等）
 * </pre>
 *
 * 这条恒等式是本模块最该被测试钉住的一条：它一旦不成立，就说明某批 bucket 被扣了两次或漏了一片，
 * 而那正是蓝图这句话要防的事。响应里的 {@link AvailableQuantityTotals#sliceBalanced()} 就是它的判定位。
 *
 * <p><b>命名空间：全部 {@code AvailableQuantity*} 前缀。</b>springdoc 用 record 的
 * <b>简单类名</b>做 {@code components.schemas} 的键，重名会被<b>静默覆盖</b> —— 别人的字段形状被改掉、
 * 单测全绿、只有跨服务的契约比对才红。本仓库已有 {@code AvailabilityQuery} / {@code AvailabilityRow} /
 * {@code AvailabilityView}（3.5.2.2 BOM 批量可用量查询）与 {@code Occupancy} / {@code LocationRow} /
 * {@code LocationTotals} / {@code Turnover}（8.1.x 与 InventoryDistributionService）这些裸名，
 * 这里一个都不复用。
 *
 * <p><b>算不出来的量一律 null，绝不 0。</b>安全缓冲在 {@code inventory_policy} 里没有配就是 null
 * （含义是「这个 SKU 没有留缓冲」而不是「缓冲是 0」）；按日期的效期判定本模块<b>不做</b>（属 7.3.1.2），
 * 所以也不存在一个假装算过的效期数字。
 */
public final class AvailableQuantityModels {

  private AvailableQuantityModels() {}

  /** 单页最大行数：SKU 行数可以很多，一次拉太多会把 JVM 拖垮。 */
  public static final int MAX_PAGE_SIZE = 200;

  /** 构成拆解里的片数（五片恒等式）。定长是为了界面上拆解表的行序稳定。 */
  public static final int SLICE_COUNT = 5;

  // ------------------------------------------------------------ 查询

  /**
   * 可用量查询条件。全部可空，null = 不限。
   *
   * <p>{@code categoryIds} / {@code brandIds} / {@code shopIds} **刻意不提供**：这三个维度属 catalog /
   * channel，7.1.1.1 已经用 {@link CatalogProjectionPort} 如实报出「未接入」。本模块不重复维护一份
   * 「不可用维度」的清单 —— 那份清单会与 7.1.1.1 漂移，而可售量是防超卖用的数，
   * 两个页面给出不同的可售范围比没有更糟。
   *
   * @param channelScopeType 渠道口径档位（DEFAULT / CHANNEL / SHOP）。传非法值由 7.2.2.1 自己抛 400 ——
   *     本模块不维护第二份档位词表，那种「同一个词表两处写、后来漂移」的起点正是各系统各算各的根源
   * @param channelReferenceId 渠道/店铺的逻辑 ID（属 channel 服务，不建外键也不查它的表）
   */
  public record AvailableQuantityQuery(
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID qualityStatusId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String channelScopeType,
      @Schema(nullable = true) UUID channelReferenceId,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) @jakarta.validation.constraints.Min(0) Integer page,
      @Schema(nullable = true) @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(MAX_PAGE_SIZE)
          Integer size) {}

  // ------------------------------------------------------------ 合计与构成拆解

  /**
   * 范围内的可售量合计与构成拆解。
   *
   * @param onHand 在手合计（{@code Σ stock_bucket.on_hand}）
   * @param reserved 预占合计（{@code Σ stock_bucket.reserved}）
   * @param available 可用合计（{@code Σ stock_bucket.available}，生成列，没有第三个来源）
   * @param sellableBeforeBuffer 可售基数 = Σ 合格且允许销售的 bucket 可用量，<b>尚未扣安全缓冲</b>
   * @param safetyBuffer 安全缓冲合计。<b>一个 SKU 都没配过就是 null</b> ——
   *     「没配缓冲」与「缓冲是 0」是两件事，把前者渲染成 0 会让运营以为系统替他留了 0 件
   * @param safetyBufferDeducted 实际从可售基数里扣掉的量。没配缓冲时它是 0，
   *     但它<b>不是</b> {@code safetyBuffer}：前者是这次算式的减数，后者是租户的经营配置
   * @param bufferedSkuCount 配了缓冲的 SKU 数（{@code safetyStock} 非 null 的档命中数）
   * @param skuCount 范围内 SKU 数
   * @param bucketCount 范围内 bucket 事实行数。与 {@code available} 同一条 where 算出，
   *     所以「有多少条明细」与「这些明细加起来是多少」永远是同一个范围
   * @param warehouseCount 范围内仓库数
   * @param locationCount 范围内库位数
   * @param lotCount 范围内批次数（无批次的货不计入）
   * @param sellable <b>可售量</b> = {@code max(0, sellableBeforeBuffer − safetyBufferDeducted)}。
   *     钳到 0 是因为负的可售量回写平台就是超卖；钳的事实由 {@code bufferExceedsBase} 如实报出
   * @param bufferExceedsBase 安全缓冲比可售基数还大（true 时 {@code sellable} 被钳到 0）
   * @param slices 构成拆解，**互斥且穷尽**，顺序固定（见 {@link AvailableQuantitySlice#code()}）
   * @param sliceBalanced 五片之和是否恰好等于 {@code available}。这是「不重复扣减」的判定位，
   *     库里有守卫约束钉着，但仍随响应返回 —— 一个恒等式坏了要能当场看见，而不是等超卖之后
   */
  public record AvailableQuantityTotals(
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellableBeforeBuffer,
      @Schema(nullable = true) BigDecimal safetyBuffer,
      BigDecimal safetyBufferDeducted,
      long bufferedSkuCount,
      long skuCount,
      long bucketCount,
      long warehouseCount,
      long locationCount,
      long lotCount,
      BigDecimal sellable,
      boolean bufferExceedsBase,
      List<AvailableQuantitySlice> slices,
      boolean sliceBalanced) {}

  /**
   * 构成拆解里的一片。
   *
   * @param code 片的口径键（受控词表：{@code SELLABLE} / {@code PENDING_INSPECTION} /
   *     {@code DEFECTIVE} / {@code UNLISTED} / {@code BLOCKED_LOT}）
   * @param amount 这一片的 available 合计（**不是**相对上一片的差额 —— 五片是并列的切分，
   *     界面上不能画成瀑布图，否则会让人把「排除」读成「再扣一次」）
   * @param bucketCount 落在这片上的 bucket 行数
   * @param countedInSellable 是否计入可售基数。只有 {@code SELLABLE} 那一片是 true，
   *     其余四片都是<b>被排除</b>而不是被扣减 —— 这正是蓝图那句「不重复扣减」
   */
  public record AvailableQuantitySlice(
      String code, String label, BigDecimal amount, long bucketCount, boolean countedInSellable,
      String basis) {}

  // ------------------------------------------------------------ 分 SKU 行

  /**
   * 一个 SKU 的可售量。合计不受分页影响（合计来自同一条 FROM 独立聚合）。
   *
   * @param safetyBuffer 该 SKU 的缓冲配置值。未配置时 null，理由同
   *     {@link AvailableQuantityTotals#safetyBuffer()}
   * @param bufferScopeType 缓冲命中的档位（SKU / WAREHOUSE / DEFAULT / NONE）。
   *     没有配就是 {@code NONE}，不是 {@code DEFAULT} —— 「没配」与「配了兜底档」不同
   */
  public record AvailableQuantityRow(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellableBeforeBuffer,
      @Schema(nullable = true) BigDecimal safetyBuffer,
      String bufferScopeType,
      @Schema(nullable = true) String bufferScopeLabel,
      BigDecimal sellable,
      boolean bufferExceedsBase,
      long bucketCount,
      long warehouseCount,
      long locationCount,
      @Schema(nullable = true) Long lotCount) {}

  /** 分 SKU 行页。{@code totals} 是全范围合计，**不受分页与筛选影响**。 */
  public record AvailableQuantityPage(
      AvailableQuantityTotals totals,
      List<AvailableQuantityRow> rows,
      long total,
      int page,
      int size,
      String note) {}

  // ------------------------------------------------------------ 渠道口径（7.2.2.1）

  /**
   * 生效的渠道可售量口径（7.2.2.1）。本模块<b>只读取与展示</b>，平台回写额仍由 7.2.2.1 算。
   *
   * @param requested 未解析的请求档位（DEFAULT / CHANNEL / SHOP）
   * @param resolvedScopeType 实际命中的档位
   * @param bufferRatio 该口径的缓冲<b>比例</b>。与本模块的 {@code safetyBuffer}（安全缓冲<b>数量</b>）
   *     不是同一个数，界面上必须分开显示：一个是「留多少件」，一个是「给平台留百分之几」
   * @param configured 是否真的配了口径。全租户都没配时 false，此时不按 0 处理
   */
  public record AvailableChannelPolicy(
      boolean configured,
      @Schema(nullable = true) UUID policyId,
      String requestedScopeType,
      String resolvedScopeType,
      @Schema(nullable = true) String poolMode,
      @Schema(nullable = true) String poolKey,
      @Schema(nullable = true) BigDecimal bufferRatio,
      boolean oversellAllowed,
      @Schema(nullable = true) BigDecimal oversellTolerance,
      @Schema(nullable = true) String platformStockScope,
      boolean includeInTransit,
      boolean usedFallback,
      String ownerModule,
      String note) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 接入情况。前端据此禁用不可用的维度并说明归属模块，而不是让用户填完再收一个 422。
   *
   * @param expiryByDateAvailable 按日期的效期剔除（{@code lot.expires_on < 今天}）。
   *     恒为 false：效期策略属 7.3.1.2，本模块<b>刻意不做</b> —— 凭一条「过期即不可售」的直觉
   *     在没有策略的情况下剔货，会让一个还没设效期的租户凭空少一批可售
   * @param reservationReleaseAvailable 预占过期自动释放。恒为 false，属 7.2.1.3（并行车道）。
   *     所以本模块读到的预占量<b>可能包含本该已被释放的预占</b>，{@code releaseNote} 如实说明
   * @param availableFormula 可售量算式原文。**随响应返回而不是只写在文档里** ——
   *     「可售量是全系统的单一口径来源」，那口径本身就要能被每个消费端读到
   * @param sliceIdentity 五片恒等式原文，同上
   */
  public record AvailableQuantityCapabilities(
      boolean quantityRoleAvailable,
      String quantityRoleOwnerModule,
      String quantityRoleNote,
      boolean locationPickableAvailable,
      String locationPickableOwnerModule,
      String locationPickableNote,
      boolean lotStatusAvailable,
      String lotStatusOwnerModule,
      String lotStatusNote,
      boolean safetyStockAvailable,
      String safetyStockOwnerModule,
      String safetyStockNote,
      boolean channelPolicyAvailable,
      String channelPolicyOwnerModule,
      String channelPolicyNote,
      boolean expiryByDateAvailable,
      String expiryByDateOwnerModule,
      String expiryByDateNote,
      boolean reservationReleaseAvailable,
      String reservationReleaseOwnerModule,
      String reservationReleaseNote,
      String releaseNote,
      String availableFormula,
      String sliceIdentity,
      String note) {}

  // ------------------------------------------------------------ 组合响应

  /**
   * 7.1.1.3 的完整产出：可售量数值 + 构成拆解 + 能力声明 + 生效渠道口径。
   *
   * @param computedAt 本次计算时刻（服务端取的，**不接受调用方传时刻**）：
   *     「按昨天那一刻的可售量」这个问法本身没有定义 —— 预占与释放在动，回看一个时刻
   *     等于要求系统保留一份历史快照，那与「不存派生数据」直接矛盾
   */
  public record AvailableQuantityReport(
      AvailableQuantityTotals totals,
      AvailableChannelPolicy channelPolicy,
      AvailableQuantityCapabilities capabilities,
      OffsetDateTime computedAt,
      String note) {}
}