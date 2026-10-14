package com.starcon.warehouse;

import com.starcon.warehouse.WarehouseModels.QualityRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.1.2 库存明细的传输模型。
 *
 * <p>与 7.1.1.1 的关系刻意做成**可核对**的两层，而不是两套口径：
 *
 * <ul>
 *   <li>7.1.1.1 的一行 = 法人货权 × SKU × 仓库 × 质量状态（仓库级聚合，可用量决策单位）。
 *   <li>7.1.1.2 的一行 = 上面四个维度 **+ 库位 + 批次**（bucket，库存最小事实单位）。
 * </ul>
 *
 * <p>因此同一批货有两个数：在手（{@code inventory_balance.on_hand}）与 Σbucket 在手。两者必须相等，差异就是解释不掉的账 —— 本模块把它做成
 * {@link BucketReconciliation} 摆在响应里（而不是只在测试里断言），因为 「总览数字必须能下钻到明细且能核对上」是蓝图对 7.1.1.1 的原话。
 *
 * <p>口径全部来自 V1/V2 迁移文件头，Java 侧不重新定义： available 只由生成列 {@code on_hand - reserved} 得出。
 */
public final class InventoryBucketModels {

  private InventoryBucketModels() {}

  // ------------------------------------------------------------ 筛选与查询

  /**
   * 明细下钻条件。
   *
   * @param legalEntityId 货权法人过滤（不传 = 全部法人）
   * @param warehouseId 仓库过滤
   * @param locationId 库位过滤（最细一级）
   * @param skuId SKU 过滤
   * @param lotId 批次过滤（不传 = 含无批次行）
   * @param qualityStatusId 质量状态过滤
   * @param keyword 关键字：匹配 SKU / 仓库 / 库位 / 批次 / 法人 的编码或名称
   * @param onlyPickable 只看可拣库位：待检、残次、冻结库位默认不可拣
   * @param sort 排序键（白名单）：ON_HAND / AVAILABLE / SKU_CODE / LOCATION_CODE / LAST_MOVEMENT
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   */
  public record BucketDetailQuery(
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) UUID qualityStatusId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) Boolean onlyPickable,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 响应

  /**
   * 明细响应。
   *
   * @param summary 当前筛选条件下的 bucket 合计（**不受分页影响**）
   * @param rows 当前页的 bucket 行
   * @param reconciliation 与 {@code inventory_balance} 的核对结果
   * @param actions 本模块提供的行内操作入口（按 bucket 维度定位）
   * @param total 满足筛选条件的总行数
   * @param page 当前页码
   * @param size 每页行数
   */
  public record InventoryBucketDetail(
      BucketSummary summary,
      List<BucketRow> rows,
      BucketReconciliation reconciliation,
      List<BucketAction> actions,
      long total,
      int page,
      int size) {}

  /**
   * bucket 合计。
   *
   * <p>与 7.1.1.1 合计同一条纪律：合计是**全量**的，翻页不变。
   *
   * @param onHand Σ bucket.on_hand
   * @param reserved Σ bucket.reserved
   * @param available Σ bucket.available（生成列，没有第三个来源）
   * @param sellable 可售质量状态的 bucket 在手
   * @param pendingInspection 待检质量状态的 bucket 在手
   * @param defective 残次质量状态的 bucket 在手
   * @param rowCount bucket 行数
   * @param locationCount 涉及库位数
   * @param lotCount 涉及批次数（不含无批次行）
   * @param unbatchedRowCount 无批次的 bucket 行数：与 lotCount 分开显示， 0 与「没查过批次」含义不同
   * @param lastMovementAt 全部命中行中最近的一次变动时间
   */
  public record BucketSummary(
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellable,
      BigDecimal pendingInspection,
      BigDecimal defective,
      long rowCount,
      long locationCount,
      long lotCount,
      long unbatchedRowCount,
      OffsetDateTime lastMovementAt) {}

  /**
   * bucket ↔ balance 核对结果。
   *
   * <p>{@code touchedBalanceCount} 是当前筛选命中的 bucket 所归属的 balance 行数； 对这些 balance 行，{@code
   * bucketOnHand} 取它们**全部** bucket 的汇总（不受行筛选影响）， 所以任意筛选组合下这个核对都成立 —— 按库位筛掉一部分行时，核对数字不会跟着缩水，
   * 否则"只筛一个库位就必然不平"会成为误报。
   *
   * @param balanceOnHand 涉及 balance 行的在手合计
   * @param balanceReserved 涉及 balance 行的预占合计
   * @param bucketOnHand 上述 balance 行的全部 bucket 在手合计
   * @param bucketReserved 上述 balance 行的全部 bucket 预占合计
   * @param touchedBalanceCount 涉及的 balance 行数
   * @param bucketCountForTouched 上述 balance 行的 bucket 行数
   * @param onHandConserved 在手守恒
   * @param reservedConserved 预占守恒
   * @param undecomposedBalanceCount 涉及范围内**没有任何 bucket** 的 balance 行数： 这些行处于 7.1.1.1
   *     的仓库级口径，还没拆到库位。它们不是差异，但在界面上 必须单独说明，否则用户会以为明细页漏数据
   */
  public record BucketReconciliation(
      BigDecimal balanceOnHand,
      BigDecimal balanceReserved,
      BigDecimal bucketOnHand,
      BigDecimal bucketReserved,
      long touchedBalanceCount,
      long bucketCountForTouched,
      boolean onHandConserved,
      boolean reservedConserved,
      long undecomposedBalanceCount) {}

  /**
   * bucket 行 = 法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态。
   *
   * <p>{@code lotId} 可空：非批次管理品没有批次，界面显示「无批次」而不是空白。 {@code locationType} 与 {@code pickable}
   * 一起回答"这批货为什么发不出去"。
   *
   * @param expiresOn 批次效期（无批次时为 null）
   * @param expired 批次是否已过效期。效期策略属 7.3.1.2，本模块只如实判定并展示， 不做分配决策
   * @param version 乐观锁版本号：每次数量变动由 DB 自增
   * @param lastMovementAt **业务时间**：最近一次数量变动的发生时间，由库存业务写入方提供
   * @param updatedAt **系统时间**：行被写入的最后时刻
   */
  public record BucketRow(
      UUID bucketId,
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID locationId,
      String locationCode,
      String locationName,
      String locationType,
      boolean pickable,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) String lotStatus,
      boolean expired,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      QualityRole qualityRole,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      boolean zeroStock,
      long version,
      OffsetDateTime lastMovementAt,
      OffsetDateTime updatedAt) {}

  /**
   * 行内操作入口。
   *
   * <p>{@code available=false} 时 {@code href} 仍然给出，是**路由契约**而不是死链： 它指明这个操作落在哪个模块、带着哪些 bucket
   * 维度定位，模块落地后直接可用。 {@code note} 说明当前为什么不可用，避免界面上出现点下去没反应或 404 的按钮。
   *
   * @param action 操作码：ADJUST / TRANSFER / LEDGER
   * @param label 操作名
   * @param href 带 bucket 定位参数的路由
   * @param available 目标模块是否已交付
   * @param ownerModule 目标模块号（如 7.4.1.1）
   * @param note 不可用时的说明
   */
  public record BucketAction(
      String action,
      String label,
      String href,
      boolean available,
      String ownerModule,
      String note) {}

  // ------------------------------------------------------------ 下拉参照数据

  /** 库位下拉项。层级与自动分配属 08.1.2.x，本模块只读档案。 */
  public record LocationRef(
      UUID id,
      UUID warehouseId,
      String warehouseCode,
      String code,
      String name,
      String type,
      boolean pickable,
      String status) {}

  /** 批次下拉项。 */
  public record LotRef(
      UUID id,
      UUID skuId,
      String skuCode,
      String lotCode,
      @Schema(nullable = true) LocalDate expiresOn,
      @Schema(nullable = true) String status) {}
}
