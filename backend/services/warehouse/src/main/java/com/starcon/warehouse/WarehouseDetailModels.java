package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.1.1.2 仓库详情的传输模型。
 *
 * <p>职责原文：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的
 * 自发货/中转/FBA 备货策略、作业人员与设备。」输出是「详情视图 + 各子模块入口」。
 *
 * <p><b>刻意复用 {@link WarehouseFacilityModels} 的四个类型</b>（{@code WarehouseKind} /
 * {@code StockMetrics} / {@code PlatformStock} 以及它们承载的「平台仓观测值与自有账本
 * 永不相加」这条纪律）。该类的文件头把这个交接写成了明文契约
 * （「在 8.1.1.2 详情与 8.1.1.3 配置里都是同一份事实的子集，届时不另造一套私有结构」），
 * 所以这里不重复定义第二套仓库类型与库存口径 —— 两套同名字段各写一遍，
 * 迟早会在某一个模块里漂移，而那正是「平台仓数量被并进总库存」的入口。
 *
 * <p><b>「未交付」与「有但是 0」在本模块是两种不同的形状。</b>库位树、上架任务、拣货出库、
 * 组装、人员花名册、设备绑定、备货策略这些维度对应的表尚不存在，
 * 它们一律走 {@link DetailEntryPoint#available()} = {@code false} + {@code ownerModule}
 * + {@code note} 表达，而不是给一个 0 或空数组冒充「这个仓没有」。
 */
public final class WarehouseDetailModels {

  private WarehouseDetailModels() {}

  // ------------------------------------------------------------ 详情主体

  /**
   * 单仓库全景。
   *
   * @param base 基础信息（与 8.1.1.1 列表行同源，但只取单仓）
   * @param address 地址。**地址在仓库表上是可空列**（V77 补的），所以这里给
   *     {@code missingFields} 而不是把空地址渲染成一行空格
   * @param stock 库存分布
   * @param locations 库位结构
   * @param work 作业统计
   * @param strategies 自发货 / 中转 / FBA 备货三条策略绑定
   * @param labor 作业人员与设备
   * @param entryPoints 各子模块入口（蓝图输出的另一半）
   */
  public record WarehouseDetail(
      WarehouseBase base,
      WarehouseAddress address,
      StockDistribution stock,
      LocationStructure locations,
      WorkStatistics work,
      List<StrategyBinding> strategies,
      LaborResources labor,
      List<DetailEntryPoint> entryPoints,
      String note) {}

  /**
   * 基础信息。
   *
   * @param localTime 当地墙上时间，时区无法识别时为 null（与 8.1.1.1 同一纪律：
   *     静默退化成 UTC 会显示一个错了 8 小时却看起来正常的时刻）
   * @param legalEntityCount 该仓实际持有货物的法人数。0 与「未绑定法人」是两件事
   */
  public record WarehouseBase(
      UUID id,
      String code,
      String name,
      WarehouseFacilityModels.WarehouseKind kind,
      @Schema(nullable = true) String kindLabel,
      String countryCode,
      String timezone,
      @Schema(nullable = true) String localTime,
      String status,
      @Schema(nullable = true) String statusLabel,
      boolean defaultWarehouse,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      long legalEntityCount,
      long skuCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 地址。
   *
   * @param missingFields 缺失的字段名（中文）。**空地址要如实说缺什么**：
   *     一个什么都没有的地址栏与「这仓在境外、不填邮编」在界面上必须能区分
   * @param text 拼好的单行地址，供列表与导出直接用；全空时为 null（不是空串）
   */
  public record WarehouseAddress(
      @Schema(nullable = true) String line1,
      @Schema(nullable = true) String line2,
      @Schema(nullable = true) String city,
      @Schema(nullable = true) String region,
      @Schema(nullable = true) String postalCode,
      String countryCode,
      List<String> missingFields,
      @Schema(nullable = true) String text) {}

  // ------------------------------------------------------------ 库存分布

  /**
   * 库存分布。
   *
   * <p>三个切面各自独立聚合、互不相加：按质量状态、按货权法人、按库位类型。
   * 一个仓里同时存在多个切面是常态，所以这里给三份明细而不是逼用户选一个。
   *
   * @param onHand 自有账本在手（{@code inventory_balance} 口径，**不含**平台仓观测值）
   * @param platform 平台仓观测值，**只在 kind == PLATFORM_FBA 时非 null**。
   *     与 {@code onHand} 在任何地方都不相加 —— V55 用两条触发器在 DB 层挡住混记
   */
  public record StockDistribution(
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long skuCount,
      long bucketCount,
      List<QualitySlice> byQuality,
      List<LegalEntitySlice> byLegalEntity,
      List<LocationTypeSlice> byLocationType,
      @Schema(nullable = true) WarehouseFacilityModels.PlatformStock platform) {}

  /** 质量状态切面。待检 / 残次是租户自己的经营口径，所以来自 {@code inventory_quality_status} 行。 */
  public record QualitySlice(
      UUID qualityStatusId,
      String code,
      String name,
      String role,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long skuCount) {}

  /** 货权法人切面。一个仓同时存多个法人的货是合法的，所以这里按行分组而不是取默认法人。 */
  public record LegalEntitySlice(
      UUID legalEntityId,
      @Schema(nullable = true) String code,
      @Schema(nullable = true) String name,
      BigDecimal onHand,
      long skuCount) {}

  /**
   * 库位类型切面。
   *
   * @param onHand 落在该类型库位上的数量（{@code stock_bucket} 口径）。
   *     它与 {@code inventory_balance} 的和在库位维度上相等，但**来源不同**：
   *     balance 是仓库级权威可用量，bucket 是库位级明细。两者相等是一次核对，不是同一个数
   */
  public record LocationTypeSlice(
      String type,
      @Schema(nullable = true) String typeLabel,
      long locationCount,
      long pickableCount,
      BigDecimal onHand) {}

  // ------------------------------------------------------------ 库位结构

  /**
   * 库位结构。
   *
   * <p>{@code warehouse_location} 表（V2）确实存在并带类型 / 状态 / 可拣属性，
   * 所以「有多少个库位、分布在什么类型上、各类型装了多少」是**真实数据**。
   * 真正没交付的是**层级**：表上没有 {@code parent_id}，也没有容量与承重列，
   * 所以「区 → 巷 → 排 → 架 → 层 → 格」这棵树属 8.1.2.1，不能在这里假装有。
   *
   * @param occupiedCount 至少有一个 bucket 有量的库位数；与 {@code totalCount} 的差就是空库位
   */
  public record LocationStructure(
      long totalCount,
      long activeCount,
      long pickableCount,
      long occupiedCount,
      BigDecimal onHand,
      List<LocationTypeSlice> byType) {}

  // ------------------------------------------------------------ 作业统计

  /**
   * 作业统计。
   *
   * <p>口径说清楚：这里的「作业」= 本服务**已交付**的作业单据。收货（8.2.1.1，V70 已交付）、
   * 盘点（7.4.2.1）、调拨（7.5.1.1）、库存变动（7.1.2.1）都真的有表，所以给真数；
   * 上架（8.2.2.1）、拣货出库（8.3）、组装（8.4）没有表，
   * 它们在 {@link DetailEntryPoint} 里报 {@code available=false}，**不在这里算成 0**。
   *
   * @param receiptOpenCount 未关闭的收货单（DRAFT / PARTIAL / RECEIVED 都算未结）
   * @param receiptReceivedQuantity 已收货单的实收合计，与收货单数分开给 ——
   *     「收了 3 单」与「收了 500 件」是两个问题
   */
  public record WorkStatistics(
      long receiptTotal,
      long receiptOpenCount,
      long receiptClosedCount,
      BigDecimal receiptReceivedQuantity,
      long stocktakeTaskCount,
      long transferInCount,
      long transferOutCount,
      long inventoryMovementCount,
      long workOrderTotal,
      long openWorkOrderCount) {}

  // ------------------------------------------------------------ 备货策略

  /**
   * 备货策略绑定（自发货 / 中转 / FBA）。
   *
   * <p>三条策略的**配置**都属 8.1.1.3 仓库配置（批次序列号开关、拣货策略、复核比例、
   * 拆零发货、工作时段），本模块未交付，所以 {@code available=false}。
   * 但每条策略背后**已经有真实事实**（调拨单、在途腿、平台观测），
   * 所以 {@link #facts()} 给实数而不是一并置空 —— 「没有这条策略」与「这条策略下现在没有货」
   * 是两件不同的事，混起来会让运营以为中转能力根本没上线。
   */
  public record StrategyBinding(
      String dimension,
      String label,
      boolean available,
      String ownerModule,
      String note,
      @Schema(nullable = true) String href,
      StrategyFacts facts) {}

  /**
   * 策略背后的已交付事实。
   *
   * @param inTransitLegCount 在途腿数（{@code warehouse_in_transit_leg}，V63）——
   *     中转维度的真实在途，与自有仓的 inventory_transfer 是同一批货的两种视角
   * @param platformObservedOnHand 平台观测在手，**只在 FBA 维度非 null**
   */
  public record StrategyFacts(
      long transferInCount,
      long transferOutCount,
      long inTransitLegCount,
      @Schema(nullable = true) BigDecimal platformObservedOnHand,
      @Schema(nullable = true) Long platformSourceCount,
      String note) {}

  // ------------------------------------------------------------ 人员与设备

  /**
   * 作业人员与设备。
   *
   * <p>三个子维度分别表态，因为它们的交付状态真的不一样：
   *
   * <ul>
   *   <li>**作业任务与派工：已接入。**{@code warehouse_work_task}（V74，8.5.1.1）带
   *       {@code warehouse_id} 与 {@code operator_user_id}，所以「派了几个人、派工了没、
   *       在跑几个、异常几个」是真数。
   *   <li>**人员花名册与排班：未接入。**没有员工档案表，也没有工作时段/班次
   *       （那属 8.1.1.3 配置），所以报 available=false 而不是拿「有派工」推出「有这个人」。
   *   <li>**设备：未接入。**{@code print_device}（V73）**没有 warehouse_id** ——
   *       设备是租户级的，与仓库之间不存在绑定关系。因此按仓统计设备数在
   *       数据层就无从谈起，必须报 false，不能拿租户设备数冒充这个仓的设备。
   * </ul>
   */
  public record LaborResources(
      boolean taskAvailable,
      String taskOwnerModule,
      String taskNote,
      long taskTotal,
      long unassignedTaskCount,
      long runningTaskCount,
      long completedTaskCount,
      long exceptionTaskCount,
      long distinctOperatorCount,
      List<WorkTypeSlice> byWorkType,
      boolean rosterAvailable,
      String rosterOwnerModule,
      String rosterNote,
      boolean equipmentAvailable,
      String equipmentOwnerModule,
      String equipmentNote) {}

  public record WorkTypeSlice(String workType, String label, long taskCount, long completedCount) {}

  // ------------------------------------------------------------ 子模块入口

  /**
   * 子模块入口。
   *
   * <p>蓝图 8.1.1.2 的输出写的是「详情视图 + <b>各子模块入口</b>」，所以入口是本模块的
   * 交付物之一，不是一个顺手加的跳转列表。{@code available=false} 的入口
   * {@code href} 为 null —— 前端据此禁用按钮而不是跳到一个不存在的路由
   * （与 8.1.1.1 交付前禁用详情入口是同一条纪律）。
   *
   * @param module 归属模块号。用户问「为什么这个数是空的」时，答案要能直接指到一条路
   */
  public record DetailEntryPoint(
      String key,
      String label,
      String module,
      boolean available,
      @Schema(nullable = true) String href,
      String note) {}

  // ------------------------------------------------------------ 库位清单

  /** 库位清单查询条件。 */
  public record LocationQuery(
      @Schema(nullable = true) String type,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 库位清单页。
   *
   * @param totals 全量合计，**不受分页与筛选影响**（与 8.1.1.1 同一口径）
   */
  public record LocationPage(
      LocationTotals totals, List<LocationRow> rows, long total, int page, int size, String note) {}

  public record LocationTotals(
      long locationCount, long activeCount, long pickableCount, long occupiedCount, BigDecimal onHand) {}

  /**
   * 库位行。
   *
   * @param onHand 该库位上的数量（{@code stock_bucket} 口径）
   * @param skuCount / bucketCount 该库位涉及多少 SKU / 多少条明细
   * @param lastMovementAt 最近一次数量变动的**业务时间**（不是行更新时间）：
   *     一个三个月没动过的库位和一个昨天动过的库位，运营要一眼看出来
   */
  public record LocationRow(
      UUID id,
      String code,
      String name,
      String type,
      @Schema(nullable = true) String typeLabel,
      String status,
      boolean pickable,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      long skuCount,
      long bucketCount,
      @Schema(nullable = true) OffsetDateTime lastMovementAt) {}

  // ------------------------------------------------------------ 维护

  /**
   * 改仓库基础信息与地址。
   *
   * <p>刻意**不含** {@code code} / {@code kind} / {@code defaultLegalEntityId} / {@code isDefault}：
   *
   * <ul>
   *   <li>{@code code} 是仓库标识，改它会让所有引用它的单据失去指向
   *   <li>{@code kind} 决定记账口径（PLATFORM_FBA 走观测值、其余走自有账本，V55 的两条
   *       触发器就是按它裁决的），改它等于把已落库的数量换一个口径重算
   *   <li>{@code defaultLegalEntityId} 是**财务前提**（蓝图 8.1.1.1 红线：
   *       「不同法人的库存货权与收入归属不同」），改绑会让历史库存的货权归属变得不可追
   *   <li>{@code isDefault} 与 {@code uq_warehouse_default} 部分唯一索引联动
   * </ul>
   *
   * 所以这四件事在本模块的界面上是只读的，各自有上面的理由，不是没做完。
   *
   * @param status 目标状态。ARCHIVED 是终态：归档后不能再改任何字段，也不能取消归档
   */
  public record WarehouseUpdateInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String name,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2)
          String countryCode,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String timezone,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 200) String addressLine1,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 200) String addressLine2,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 100) String city,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 100) String region,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 20) String postalCode,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 16) String status) {}

  /** 维护结果。{@code updatedAt} 返回服务端写入后的值，前端据此判断是否需要重取。 */
  public record WarehouseUpdated(
      UUID id, String code, String name, String status, OffsetDateTime updatedAt, String note) {}
}