package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.1.3.1 库存风险的**只读投影**数据形状（卷 01 工作台与门户）。
 *
 * <p>蓝图原文：「集中呈现缺货、超卖风险、呆滞、超储四类库存问题，区分本地仓/海外仓/FBA/在途。」
 * 输入「07 库存的可用量投影（按 bucket 汇总）；销售速度；06 采购的在途量；10 的头程在途。」
 * 输出「风险清单（SKU、仓库、可用量、日均销、可用天数、风险类型、建议动作如"生成采购单"）。」
 *
 * <p><b>本端点只提供 warehouse 自己拥有的事实，不替调用方下判定。</b>
 *
 * <ul>
 *   <li>可用量 / 在手量 / 预占量来自 {@code stock_bucket}（V2）按 (仓 × SKU) 汇总；
 *   <li>阈值来自 {@code inventory_policy}（V1，min/max/reorder/safety）与 {@code inventory_aging_policy}
 *       （V65，呆滞天数）—— <b>阈值一律随行返回并标注解析档位</b>，调用方不必再猜「这个数是谁定的」；
 *   <li>活跃预占来自 {@code reservation_allocation}（V107）+ {@code inventory_reservation}（V102）；
 *   <li>在途来自 {@code inventory_transfer}（V1 / V63 扩列），口径与 7.5.1.2 的在途清单<b>逐字一致</b>
 *       （在途量 = 仍在途的腿的 {@code quantity} 之和）。
 * </ul>
 *
 * <p><b>销售速度不在这里</b>：日均销登记在 trade 服务的 {@code stockout_sales_velocity}（V234），
 * 属另一个服务的事实表。按 AGENTS.md 模块边界纪律，本服务不读对方的表，卷 01 的跨服务聚合在前端完成。
 */
public final class InventoryRiskProjectionModels {

  private InventoryRiskProjectionModels() {}

  /** 查询参数。全部可空：空 = 不过滤。 */
  public record ProjectionQuery(
      UUID warehouseId,
      UUID skuId,
      String warehouseKind,
      String keyword,
      Integer page,
      Integer size) {}

  /**
   * 一行 = 一个 (仓库 × SKU) 的库存事实。
   *
   * <p><b>数量口径</b>：{@code onHand} / {@code reserved} / {@code available} 三者恒满足
   * {@code available = onHand − reserved}（{@code stock_bucket.available} 是生成列，本端点只做汇总，
   * 不重算、不重定义）。{@code available} 就是蓝图输出句里的「可用量」。
   *
   * <p><b>阈值与档位</b>：{@code minOnHand} / {@code maxOnHand} / {@code reorderPoint} / {@code safetyStock}
   * 各自可能为 {@code null} —— <b>「没配」与「配成 0」是两件事</b>，前者必须能被区分。
   * {@code thresholdScopeType} 报出解析命中的档位（SKU / WAREHOUSE / DEFAULT / NONE）。
   */
  public record RiskProjectionRow(
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String warehouseKind,
      String warehouseKindLabel,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellableBeforeBuffer,
      long bucketCount,
      long locationCount,
      long lotCount,
      LocalDate inboundDate,
      Integer agingDays,
      String agingBasis,
      OffsetDateTime lastMovementAt,
      @Schema(nullable = true) BigDecimal minOnHand,
      @Schema(nullable = true) BigDecimal maxOnHand,
      @Schema(nullable = true) BigDecimal reorderPoint,
      @Schema(nullable = true) BigDecimal safetyStock,
      String thresholdScopeType,
      String thresholdScopeKey,
      Integer obsoleteDays,
      String obsoleteThresholdSource,
      BigDecimal activeReservedQuantity,
      long activeReservationCount,
      BigDecimal inTransitQuantity,
      BigDecimal inTransitFirstMileQuantity,
      BigDecimal inTransitPurchaseQuantity,
      BigDecimal inTransitTransferQuantity,
      long inTransitLegCount) {}

  /** 仓库类型下拉（前端按仓库类型筛选时用）。 */
  public record WarehouseKindOption(String value, String label, long warehouseCount) {}

  /** 仓库下拉（按类型筛选用）。 */
  public record WarehouseOption(UUID id, String code, String name, String kind, String kindLabel) {}

  /**
   * 投影页。
   *
   * @param total (仓 × SKU) 组合的**全量**条数，不受分页影响
   * @param asOfDate 评估基准日（数据库时钟，UTC）。销售速度的「当前生效」判定要用它，
   *     避免浏览器本地时区与数据库时区各算各的
   */
  public record RiskProjectionPage(
      List<RiskProjectionRow> rows,
      long total,
      int page,
      int size,
      String asOfDate,
      OffsetDateTime computedAt,
      List<WarehouseKindOption> warehouseKindOptions,
      List<WarehouseOption> warehouseOptions,
      String truncationNote) {}

  /**
   * 能力声明。
   *
   * <p>本模块是卷 01 的只读聚合层，**不产生任何新的业务事实**，所以它自己没有迁移、没有新表。
   * 每一个输入的归属与可用性都要在这里报出来。
   */
  public record RiskProjectionCapabilities(
      boolean stockProjectionAvailable,
      String stockProjectionSource,
      String stockProjectionNote,
      boolean thresholdAvailable,
      String thresholdSource,
      String thresholdNote,
      boolean agingAvailable,
      String agingSource,
      String agingNote,
      boolean reservationAvailable,
      String reservationSource,
      String reservationNote,
      boolean inTransitAvailable,
      String inTransitSource,
      String inTransitNote,
      boolean salesVelocityAvailable,
      String salesVelocityOwnerModule,
      String salesVelocityImpact,
      boolean purchaseArrivalAvailable,
      String purchaseArrivalOwnerModule,
      String purchaseArrivalImpact,
      String riskRuleNote,
      String availableDaysNote,
      String writeScopeNote) {}
}
