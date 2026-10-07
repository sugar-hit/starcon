package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.3.1 多仓分布视图的传输模型。
 *
 * <p>蓝图原文：「输出：多仓对照矩阵（行=仓库，列=在手/预占/可用/在途/周转天数）」
 * 「<b>FBA 库存是平台仓观测值，不是自有账本</b>，需标注来源与采集时间」。
 *
 * <p>所以本模块最核心的一条纪律是：<b>矩阵里没有一个数是「自有 + 平台」加出来的</b>。
 * 每一行的六个数量列按来源分成两组，各组内部自洽：
 *
 * <ul>
 *   <li><b>自有账本量</b>（{@code onHand} / {@code reserved} / {@code available} / {@code inTransit}）
 *       来自 {@code inventory_balance} 与 {@code inventory_transfer}，平台仓整行为 {@code null}。
 *   <li><b>平台观测量</b>（{@code platformOnHand} / {@code platformReserved} /
 *       {@code platformAvailable} / {@code platformInbound}）来自
 *       {@code platform_stock_observation}，自有仓整行为 {@code null}，
 *       且 {@code source} 与 {@code collectedAt} <b>必填</b>（DB 层 NOT NULL）。
 * </ul>
 *
 * <p>这么分组不是为了界面上好看，是因为把 FBA 观测量和自有账本量放进同一个求和，
 * 会让「这批货到底有多少」在系统里出现两个都能显示的数 —— 而这两个数的**权威性不同**：
 * 一个是逐笔可审阅的账，一个是某时刻某接口报回来的快照。
 *
 * <p>周转天数给不出数时返回 {@code null} 并附 {@code turnoverNote} 说明原因，
 * 不用 0 也不用假数：0 会被读成「瞬间卖完」，假数会被读成「算过了」。
 */
public final class DistributionModels {

  private DistributionModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 矩阵查询条件。
   *
   * <p>{@code skuId} 与 {@code spuId} 至少给一个：蓝图输入就是「SKU 或 SPU」，
   * 两者都不给等于要求把全租户所有 SKU 的全仓分布一次拉出来，那既不是矩阵也不是分页。
   *
   * @param skuId 按单个 SKU 看（精确到一行的主用法）
   * @param spuId 按 SPU 看该 SPU 下所有 SKU 的多仓分布
   * @param legalEntityId 货权法人过滤；跨法人的货不能并成一列
   * @param lookbackDays 周转天数的出库速度回看天数，默认 30，服务端有上下限
   */
  public record DistributionQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) Integer lookbackDays) {}

  // ------------------------------------------------------------ 响应

  /**
   * 多仓对照矩阵。
   *
   * @param subject 被查看的商品：给了 spuId 时 {@code skuId} 为空，矩阵按 SPU 展开为多行 SKU
   * @param window 周转天数实际使用的回看区间（把 lookbackDays 换算成真实起止时刻返回，
   *     否则界面无法说明「按哪段时间算的」）
   * @param totals 合计，**自有账本与平台观测分列**，两组各自相加，绝不相加
   * @param rows 矩阵行，一行 = 一个仓库
   * @param skuScope SPU 过滤的口径边界（有多少 SKU 没有登记 spu_id 而无法参与 SPU 口径）
   * @param actions 下游操作入口
   */
  public record MultiWarehouseDistribution(
      DistributionSubject subject,
      DistributionWindow window,
      DistributionTotals totals,
      List<DistributionRow> rows,
      DistributionSkuScope skuScope,
      List<DistributionAction> actions) {}

  /** 被查看的商品主体。 */
  public record DistributionSubject(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) UUID spuId) {}

  /**
   * 周转天数的计算窗口。
   *
   * @param lookbackDays 实际生效的回看天数（可能被服务端上下限收敛过）
   * @param outboundKinds 计入「出库」的业务类型：只有 SHIPMENT。
   *     TRANSFER_OUT 刻意**不**计 —— 调拨出库不是消耗，把它算进去会让周转天数假性变差，
   *     而这正是本模块下游 7.5.1.1 调拨要拿它做决策的列
   * @param from 区间起点（含）
   * @param to 区间终点（不含）
   */
  public record DistributionWindow(
      int lookbackDays, List<String> outboundKinds, OffsetDateTime from, OffsetDateTime to) {}

  /**
   * 矩阵合计。
   *
   * <p>两组数字相邻但不相加：{@code own*} 来自自有账本，{@code platform*} 来自平台观测。
   * 「总库存」这个数在本模块**不存在**，因为它没有唯一口径。
   */
  public record DistributionTotals(
      BigDecimal ownOnHand,
      BigDecimal ownReserved,
      BigDecimal ownAvailable,
      BigDecimal ownInTransit,
      int ownWarehouseCount,
      int ownZeroWarehouseCount,
      BigDecimal platformOnHand,
      BigDecimal platformReserved,
      BigDecimal platformAvailable,
      BigDecimal platformInbound,
      int platformWarehouseCount,
      @Schema(nullable = true) OffsetDateTime earliestCollectedAt) {}

  /**
   * 矩阵行 = 一个仓库。
   *
   * <p>关键点：{@code onHand} 等四个自有账本列与 {@code platformOnHand} 等四个平台观测列
   * <b>互斥有值</b>。看 {@code warehouseKind} 就知道该读哪一组；不存在两组都有值的行。
   *
   * @param warehouseKind LOCAL / OVERSEAS / PLATFORM_FBA / THIRD_PARTY
   * @param onHand 自有账本在手；平台仓为 null
   * @param reserved 自有账本预占；平台仓为 null
   * @param available 自有账本可用（生成列 on_hand-reserved）；平台仓为 null
   * @param inTransit 在途（已发货未入该仓）= Σ inventory_transfer.status='IN_TRANSIT'
   *     且 to_warehouse_id 为本仓；平台仓为 null（在途由 platformInbound 单独给出）
   * @param platformOnHand 平台观测在手；自有仓为 null
   * @param platformReserved 平台观测预留；自有仓为 null
   * @param platformAvailable 平台观测可售；自有仓为 null
   * @param platformInbound 平台观测的入库在途；自有仓为 null
   * @param source 观测来源；平台仓必填（DB NOT NULL），自有仓为 null
   * @param collectedAt 观测采集时间；平台仓必填，自有仓为 null
   * @param observationAgeSeconds 观测距今多少秒，界面据此提示「数据有多旧」。
   *     自有账本量为 null，因为账本没有「采集时间」这个概念
   * @param turnoverDays 周转天数；算不出时为 null，原因见 turnoverNote
   * @param turnoverComputable 是否算得出（false 时 turnoverDays 必为 null）
   * @param turnoverBasis 计算依据：OWN_OUTBOUND（自有出库速度）/ PLATFORM_OBSERVATION_DIFF
   *     （平台观测快照差分）/ NONE
   * @param turnoverNote 算不出或口径需要说明时的中文说明
   * @param bucketCount 拆到 bucket 的条数；0 表示该仓库存尚未拆到库位（7.1.1.2 的未分解状态）
   * @param locationCount 涉及库位数
   * @param lotCount 涉及批次数（不含无批次）
   * @param lastMovementAt 最近一次数量变动时间（来自 stock_bucket，无 bucket 时为 null）
   */
  public record DistributionRow(
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String warehouseKind,
      String countryCode,
      String timezone,
      @Schema(nullable = true) BigDecimal onHand,
      @Schema(nullable = true) BigDecimal reserved,
      @Schema(nullable = true) BigDecimal available,
      @Schema(nullable = true) BigDecimal inTransit,
      @Schema(nullable = true) BigDecimal platformOnHand,
      @Schema(nullable = true) BigDecimal platformReserved,
      @Schema(nullable = true) BigDecimal platformAvailable,
      @Schema(nullable = true) BigDecimal platformInbound,
      @Schema(nullable = true) String source,
      @Schema(nullable = true) OffsetDateTime collectedAt,
      @Schema(nullable = true) Long observationAgeSeconds,
      @Schema(nullable = true) BigDecimal turnoverDays,
      boolean turnoverComputable,
      String turnoverBasis,
      String turnoverNote,
      long bucketCount,
      long locationCount,
      long lotCount,
      @Schema(nullable = true) OffsetDateTime lastMovementAt) {}

  /**
   * SPU 口径的边界说明。
   *
   * <p>warehouse 的 {@code sku_ref.spu_id} 是**引用登记**（catalog 才是 SPU 归属的权威），
   * 本迁移之前登记的 SKU 都没有这一列。于是「按 SPU 看」天然无法覆盖它们 —— 这一点如实
   * 报出来，比在界面上假装 SPU 筛选是完整的要好。
   *
   * @param matchedSkuCount 命中本次口径的 SKU 数
   * @param unregisteredSpuCount 参与查询的 SKU 里有多少个没有登记 spu_id
   *     （按 skuId 查询时恒为 0，因为给了精确 skuId 就不依赖 SPU 登记）
   */
  public record DistributionSkuScope(long matchedSkuCount, long unregisteredSpuCount) {}

  /**
   * SPU 下拉项。
   *
   * <p>刻意**不**往共用的 {@code SkuRef} 上加 {@code spuId}：那个 DTO 被 7.1.1.1 总览等
   * 多个模块共用，而 SPU 引用登记是 7.1.3.1 引入的列。改动共用 DTO 会让所有依赖方都被动升级，
   * 所以这里给矩阵一个自己的下拉，不扩大影响面。
   *
   * @param skuCount 该 SPU 下在本租户登记的 SKU 数（0 不可能出现：登记了就至少有一个 SKU）
   */
  public record DistributionSpuRef(UUID spuId, long skuCount) {}

  /**
   * 下游操作入口。语义与 7.1.1.2 的 {@code BucketAction} 一致：
   * {@code available=false} 时 href 仍是**路由契约**而不是死链。
   */
  public record DistributionAction(
      String action, String label, String href, boolean available, String ownerModule, String note) {}

  // ------------------------------------------------------------ 平台仓观测写入

  /**
   * 平台仓观测录入。
   *
   * <p>{@code source} 与 {@code collectedAt} 必填且无默认值 —— 蓝图要求 FBA 行必须能标注
   * 来源与采集时间，允许留空就等于允许出现一行「不知道哪来的、什么时候的」平台数量。
   *
   * @param warehouseId 必须是 {@code kind='PLATFORM_FBA'} 的仓，否则 DB 触发器拒绝
   * @param skuId SKU 逻辑 ID（引用登记）
   * @param legalEntityId 货权法人
   * @param source 采集来源，如 AMAZON_FBA
   * @param collectedAt 采集时间（带时区的 ISO-8601），是观测的发生时点而非写入时刻
   * @param observedOnHand 平台报出的在手量
   * @param observedReserved 平台报出的预留量
   * @param observedAvailable 平台报出的可售量
   * @param observedInbound 平台报出的入库在途量
   * @param rawReference 平台侧原始单号/批次号，便于回溯核对
   */
  public record PlatformObservationInput(
      UUID warehouseId,
      UUID skuId,
      UUID legalEntityId,
      String source,
      OffsetDateTime collectedAt,
      BigDecimal observedOnHand,
      BigDecimal observedReserved,
      BigDecimal observedAvailable,
      BigDecimal observedInbound,
      @Schema(nullable = true) String rawReference) {}

  /** 观测录入结果。回显落库后的真实 id 与采集时间，便于调用方确认写回的是预期值。 */
  public record PlatformObservationSaved(
      UUID observationId,
      UUID warehouseId,
      UUID skuId,
      String warehouseKind,
      String source,
      OffsetDateTime collectedAt,
      OffsetDateTime storedAt) {}
}
