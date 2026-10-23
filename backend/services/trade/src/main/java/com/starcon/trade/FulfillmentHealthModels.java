package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.1.2.3 履约健康（工作台健康度卡片 + 红灯项明细）。
 *
 * <p>蓝图（{@code docs/product/capability-blueprint/01-dashboard-and-portal.md} 第 110–115 行）：
 *
 * <blockquote>
 *
 * <b>职责</b>：展示履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、
 * 平台绩效指标（ODR、迟发率、有效追踪率）。
 * <b>输入</b>：08 仓储作业的出库时效投影；10 物流的轨迹异常投影；02 渠道同步的绩效指标。
 * <b>输出</b>：健康度卡片 + 红灯项明细跳转。
 * <b>关系</b>：上游 = 08、10、02；下游 = 08 出库看板、10 轨迹异常列表。
 *
 * </blockquote>
 *
 * <p><b>卷 01 的定位决定了本模块是什么、不是什么</b>（蓝图卷首写死）：
 *
 * <blockquote>
 *
 * <b>定位</b>：starcon 的统一入口。不产生新的业务事实，只聚合各系统的投影。
 * <b>后端归属</b>：前端聚合层 + 各服务的只读投影（<b>不新增业务服务</b>）。
 * <b>不做</b>：任何写业务数据的操作。工作台只读 + 跳转；一切状态变更回到归属系统完成。
 *
 * </blockquote>
 *
 * <p>所以本模块：
 *
 * <ul>
 *   <li><b>不建新表、不造新事实</b>：所有数字都从既有事实表读出来（{@code shipment_pending_unit} /
 *       {@code shipment_record} / {@code fulfillment_dropship_tracking_exception}）；
 *   <li><b>不新增业务服务</b>：落在既有 trade 服务（它就是履约事实的归属服务）；
 *   <li><b>只读</b>：本命名空间下**没有任何 POST/PUT/DELETE**，一切状态变更回 9.4.1.1 / 9.4.1.2 /
 *       9.6.1.3 完成；
 *   <li><b>不跨服务读表</b>：「平均出库时长」由 08 的 8.5.3.1 出库时效模块自己出数
 *       （{@code /warehouse/outbound-timeliness/report}），本模块**不**去查 warehouse 的库。
 * </ul>
 *
 * <p><b>口径全部写在这里，且每张卡片都带分母</b>。工作台的数字要能点进去看明细，
 * 「没有分母的比率」没法判断「没登记」还是「真准时」。
 */
public final class FulfillmentHealthModels {

  private FulfillmentHealthModels() {}

  // ================================================================ 能力声明

  /**
   * 一个健康度维度的能力声明。
   *
   * @param key 维度键
   * @param label 人可读名
   * @param available 是否有可读上游（false = 量不到，界面显示「未接通」而不是 0）
   * @param reason {@code available=false} 时说明**为什么**拿不到
   * @param ownerModule 缺口归属模块（{@code available=false} 时点名）
   * @param impact 缺口对本模块的影响（界面要如实告诉用户少了哪一维）
   * @param formula 计算公式（人话版，界面悬浮显示）
   * @param denominator 分母定义（**必填**：没有分母的比率没法复核）
   * @param sourceTables 数据来源表
   * @param handling 本模块的处理方式（只读聚合 / 跨服务取数 / 未接通）
   */
  public record FulfillmentHealthCapabilityDimension(
      String key,
      String label,
      boolean available,
      String reason,
      String ownerModule,
      String impact,
      String formula,
      String denominator,
      String sourceTables,
      String handling) {}

  /** 能力声明。 */
  public record FulfillmentHealthCapabilities(
      List<FulfillmentHealthCapabilityDimension> dimensions,
      List<String> redFlagKinds,
      String levelRule,
      String windowBasis,
      String upstreamNote,
      String ownershipNote,
      String boundaryNote) {}

  // ================================================================ 健康度卡片

  /**
   * 一张健康度卡片。
   *
   * @param metricKind 口径类型：{@code COUNT}（计数）/ {@code RATE}（比率）/ {@code UNAVAILABLE}（未接通）。
   *     计数型卡片**没有**分子分母 —— 硬塞一个会让「3 件」显示成「3/5 = 60%」这种读不出来的东西
   * @param value 指标值（比率型是 0..1 的小数值；计数型是整数）；{@code null} = 量不到，**不是 0**
   * @param valueText 展示串（后端算好；量不到时是「未接通」）
   * @param numerator 分子（**比率型**必填；计数型为 null）
   * @param denominator 分母（**比率型**必填；计数型为 null）
   * @param alertCount 这张卡背后**具体有多少条红灯项**（可下钻的那部分）
   * @param redFlagKind 红灯项类型键（{@link FulfillmentHealthCapabilities#redFlagKinds} 里的一个）；
   *     「红灯项列表按这个 kind 过滤的 total」必须等于 {@code alertCount}
   * @param level 灯色：{@code RED} / {@code AMBER} / {@code GREEN} / {@code UNKNOWN}
   * @param levelRule 灯色的判定依据（逐字说明，可复核）
   * @param detailPath 点进去能看到的明细路径（前端路由）
   */
  public record FulfillmentHealthCard(
      String key,
      String label,
      boolean available,
      String unavailableReason,
      String ownerModule,
      String metricKind,
      String unit,
      @Schema(nullable = true) BigDecimal value,
      String valueText,
      @Schema(nullable = true) Long numerator,
      @Schema(nullable = true) Long denominator,
      @Schema(nullable = true) Long alertCount,
      @Schema(nullable = true) String redFlagKind,
      String basis,
      String level,
      String levelRule,
      String detailPath,
      List<String> sourceTables) {}

  /** 拆分桶（状态分布 / 异常类型分布 …）。 */
  public record FulfillmentHealthBucket(String key, String label, long count) {}

  /** 一组拆分。 */
  public record FulfillmentHealthBreakdown(String key, String label, List<FulfillmentHealthBucket> buckets) {}

  /**
   * 覆盖率事实。
   *
   * <p>刻意把「未登记 / 未发生 / 缺失」的量单独报出来：把没时限的当准时、没轨迹的当可追踪，
   * 迟发率与有效追踪率都会系统性偏低，而偏低的方向正好是让人以为「一切正常」的方向。
   */
  public record FulfillmentHealthCoverage(
      long pendingProjectionTotal,
      long pendingHandedOverUnits,
      long pendingCancelledUnits,
      long shipmentRecordsEvaluated,
      long shipmentRecordsUnsetSla,
      long shipmentRecordsUntrackable,
      long dropshipOpenExceptions,
      long dropshipTrackings) {}

  /** 健康度汇总。 */
  public record FulfillmentHealthSummary(
      List<FulfillmentHealthCard> cards,
      List<FulfillmentHealthBreakdown> breakdowns,
      FulfillmentHealthCoverage coverage,
      List<String> boundaryNotes) {}

  // ================================================================ 红灯项

  /**
   * 一条红灯项。
   *
   * @param kind 红灯类型键（{@code PENDING_SHIPMENT_OVERDUE} / {@code SHIP_OVERDUE} /
   *     {@code LOGISTICS_EXCEPTION} / {@code DROPSHIP_TRACKING_EXCEPTION}）
   * @param refId 归属事实的主键（点进去能定位到那一条）
   * @param linkPath 点进去能看到的明细路径（前端路由，带定位参数）
   */
  public record FulfillmentHealthRedFlag(
      String kind,
      String kindLabel,
      UUID refId,
      String refNo,
      String title,
      String detail,
      String severity,
      String status,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      String linkPath) {}

  /** 红灯项分页（真分页；{@code total} 是该筛选下的全量行数）。 */
  public record FulfillmentHealthRedFlagPage(
      List<FulfillmentHealthRedFlag> items, long total, int page, int size, List<String> boundaryNotes) {}
}
