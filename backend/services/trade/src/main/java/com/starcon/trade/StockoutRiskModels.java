package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.2.1.1 缺货与断货风险的传输模型。
 *
 * <p>蓝图原话：「识别断货风险 SKU，输出补货优先级与缺口量。」输入「销售速度（日均销量）、
 * 当前可用库存、在途量、备货周期」。输出「断货风险清单（SKU、可用量、日均销、可支撑天数、
 * 预计断货日、缺口量、涉及店铺），按紧急度排序」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code Stockout} 开头。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>「无数据」是一等状态</b>：{@link StockoutRiskItemRow#dailySalesQuantity()}、
 *       {@link StockoutRiskItemRow#coverageDays()}、{@link StockoutRiskItemRow#expectedStockoutDate()}
 *       在没有销售速度登记时都是 <b>null</b>，不是 0。0 日均销量会让可支撑天数变成无穷、
 *       健康度被高估 —— 那正是要防的事。</li>
 *   <li><b>可支撑天数是整数天</b>（{@link StockoutRiskItemRow#coverageDays()}），与 {@code BigDecimal}
 *       的数量字段分离：天数是「几天」这个口径，不是「几天零几小时」。</li>
 *   <li><b>排序依据随结论一起返回</b>（{@link StockoutRiskItemRow#urgencyBasis()}）：清单页直接显示这行
 *       就能复现判定，不必点进详情再猜。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额本模块不涉及（缺货风险不碰钱）。</li>
 * </ul>
 */
public final class StockoutRiskModels {

  private StockoutRiskModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 紧急度分档。
   *
   * <p>分档判据（服务层 {@code StockoutRiskService#urgency} 一处实现，迁移里由 CHECK 钉住取值域）：
   *
   * <ol>
   *   <li>{@code NO_DATA} —— 没有销售速度登记，答不出断货日。<b>这不是「不紧急」</b>，
   *       而是一个必须有人去补数据的洞。</li>
   *   <li>{@code CRITICAL} —— 预计断货日就是评估日（可用量已不足一天的消耗）。</li>
   *   <li>{@code HIGH} —— 断货日落在备货周期内，<b>且缺口量 &gt; 0</b>（即断货时没有足够的在途顶上）。</li>
   *   <li>{@code MEDIUM} —— 断货日落在两倍备货周期内，且缺口量 &gt; 0。</li>
   *   <li>{@code LOW} —— 其余（含日均销量登记为 0 的情况：登记事实是「本 SKU 无消耗」）。</li>
   * </ol>
   */
  public enum StockoutUrgency implements Labelled {
    CRITICAL("紧急"),
    HIGH("高"),
    MEDIUM("中"),
    LOW("低"),
    NO_DATA("无数据");

    private final String label;

    StockoutUrgency(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 清单排序位次（越小越靠前）。{@code NO_DATA} 排在最后 —— 它要人补数据，不是要人下单。 */
    public int rank() {
      return switch (this) {
        case CRITICAL -> 0;
        case HIGH -> 1;
        case MEDIUM -> 2;
        case LOW -> 3;
        case NO_DATA -> 4;
      };
    }
  }

  /** 销售速度登记的来源。「这个数是谁给的」是补货决策的凭据，不是可省略的字段。 */
  public enum StockoutVelocitySource implements Labelled {
    MANUAL("人工登记"),
    IMPORT("批量导入"),
    EXTERNAL_SYNC("外部同步");

    private final String label;

    StockoutVelocitySource(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 计算过程的一个步骤。拆解表逐行记下算式里的每一项，让结论可复算。 */
  public enum StockoutDeductionStep implements Labelled {
    ON_HAND("在手量"),
    RESERVED("预占量"),
    SAFETY_BUFFER("安全缓冲"),
    SELLABLE("有效可用量"),
    IN_TRANSIT_TOTAL("在途合计"),
    IN_TRANSIT_ARRIVING("备货周期内可到达在途"),
    IN_TRANSIT_NO_ETA("无预计到达日的在途"),
    DAILY_SALES("日均销量"),
    LEAD_TIME_CONSUMPTION("备货周期预期消耗"),
    SHORTAGE("缺口量");

    private final String label;

    StockoutDeductionStep(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：四个输入里有两个来自别的服务（warehouse 的库存与在途），一个来自 channel
   * （涉及店铺），还有一个（本服务自建的）销售速度登记。哪一个接通了、哪一个没接通，
   * 必须由接口自己报出，而不是让调用方从空数组里猜。
   *
   * @param salesVelocityAvailable 销售速度登记是否可用。恒为 true —— 它是本服务自建的输入事实表
   * @param salesVelocityOwnerModule 销售速度登记的归属模块（13 销量/报表服务在本仓不存在）
   * @param salesVelocityNote 销售速度为什么由本服务维护
   * @param warehouseQuantityAvailable warehouse 7.1.1.3 可用量是否已接入
   * @param warehouseInTransitAvailable warehouse 7.5.1.2 在途是否已接入
   * @param channelShopMappingAvailable channel 4.1.1.1 映射（SKU → 店铺）是否已接入
   * @param shopNameAvailable 店铺名称是否可用。恒为 false：店铺主数据属 channel 2.3.1.1，
   *     本模块只存 shop_code 快照，不复制别人的主数据
   * @param shopNameNote 店铺名称为什么不给
   * @param purchaseOrderAvailable 采购单（6.3.1.x）是否可用。恒为 false —— 在途只落来源单据号
   * @param purchaseOrderNote 采购单未交付时的说明
   * @param urgencyBasisFormula 紧急度分档的算式原文（随响应返回）
   * @param coverageFormula 可支撑天数的算式原文
   * @param shortageFormula 缺口量的算式原文
   * @param note 总体说明
   */
  public record StockoutCapabilities(
      boolean salesVelocityAvailable,
      String salesVelocityOwnerModule,
      String salesVelocityNote,
      boolean warehouseQuantityAvailable,
      boolean warehouseInTransitAvailable,
      boolean channelShopMappingAvailable,
      boolean shopNameAvailable,
      String shopNameNote,
      boolean purchaseOrderAvailable,
      String purchaseOrderNote,
      String urgencyBasisFormula,
      String coverageFormula,
      String shortageFormula,
      String note) {}

  // ================================================================ 销售速度登记（输入事实）

  /**
   * 登记一条销售速度。
   *
   * @param skuId SKU 的逻辑 id（catalog 拥有，跨服务不建外键）
   * @param skuCode SKU 编码（快照）
   * @param dailySalesQuantity 日均销量（数量，numeric(19,6)）。允许 0（登记事实），不允许负数
   * @param windowStart 统计窗口起（这个日均数是在哪段销售数据上算出来的）
   * @param windowEnd 统计窗口止
   * @param effectiveFrom 生效起点（含）。生效期区间语义 [effectiveFrom, effectiveTo)
   * @param effectiveTo 生效终点（不含）。null = 当前生效；同一 SKU 同时只允许一条未收口的登记
   * @param sourceKind 来源
   */
  public record StockoutVelocityInput(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 64) String skuCode,
      @Schema(nullable = true) @Size(max = 200) String skuName,
      @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal dailySalesQuantity,
      @NotNull LocalDate windowStart,
      @NotNull LocalDate windowEnd,
      @NotNull LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @NotNull StockoutVelocitySource sourceKind,
      @Schema(nullable = true) @Size(max = 128) String sourceRef,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 销售速度登记行。 */
  public record StockoutVelocityRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal dailySalesQuantity,
      LocalDate windowStart,
      LocalDate windowEnd,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      String sourceKind,
      String sourceKindLabel,
      String sourceRef,
      String note,
      OffsetDateTime createdAt,
      boolean effective) {}

  /** 销售速度登记列表（真分页 + 真筛选）。 */
  public record StockoutVelocityPage(List<StockoutVelocityRow> rows, long total, int page, int size) {}

  // ================================================================ 计算

  /**
   * 发起一次风险计算。
   *
   * @param asOfDate 评估基准日。null = 取数据库当天（{@code current_date}）。可显式指定以便回溯复算
   * @param leadTimeDays 备货周期（天），必填且 &gt; 0
   * @param horizonDays 评估窗口（天）。null = 备货周期 × 2
   * @param note 备注
   */
  public record StockoutComputeInput(
      @Schema(nullable = true) LocalDate asOfDate,
      @NotNull @jakarta.validation.constraints.Min(1) Integer leadTimeDays,
      @Schema(nullable = true) @jakarta.validation.constraints.Min(1) Integer horizonDays,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 一次计算的结果。
   *
   * @param truncated 上游分页是否被页数上限截断。true 时清单**不完整**，
   *     界面上必须说清楚 —— 一个被悄悄截断的断货清单比一个明确报出截断的清单危险得多
   */
  public record StockoutComputeResult(
      UUID snapshotId,
      String snapshotNo,
      LocalDate asOfDate,
      OffsetDateTime computedAt,
      int leadTimeDays,
      int horizonDays,
      int itemCount,
      int criticalCount,
      int noDataCount,
      boolean truncated,
      String truncatedNote,
      String note) {}

  // ================================================================ 快照

  /** 风险清单快照行。 */
  public record StockoutSnapshotRow(
      UUID id,
      String snapshotNo,
      LocalDate asOfDate,
      OffsetDateTime computedAt,
      int leadTimeDays,
      int horizonDays,
      int itemCount,
      int criticalCount,
      int noDataCount,
      String note,
      OffsetDateTime createdAt) {}

  /** 快照列表（真分页）。 */
  public record StockoutSnapshotPage(List<StockoutSnapshotRow> rows, long total, int page, int size) {}

  // ================================================================ 风险清单

  /**
   * 断货风险清单行 —— 蓝图输出句的七项全部在这里：
   * SKU（{@code skuId/skuCode/skuName}）、可用量（{@code availableQuantity}）、
   * 日均销（{@code dailySalesQuantity}）、可支撑天数（{@code coverageDays}）、
   * 预计断货日（{@code expectedStockoutDate}）、缺口量（{@code shortageQuantity}）、
   * 涉及店铺（{@code shopCodes} / {@code shopCount}）。
   */
  public record StockoutRiskItemRow(
      UUID id,
      UUID snapshotId,
      String snapshotNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal safetyBufferQuantity,
      BigDecimal availableQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal inTransitArrivingQuantity,
      BigDecimal inTransitNoEtaQuantity,
      @Schema(nullable = true) BigDecimal dailySalesQuantity,
      @Schema(nullable = true) Integer coverageDays,
      @Schema(nullable = true) LocalDate expectedStockoutDate,
      LocalDate replenishmentArrivalDate,
      BigDecimal shortageQuantity,
      String urgency,
      String urgencyLabel,
      String urgencyBasis,
      int shopCount,
      List<String> shopCodes,
      int sortRank) {}

  /** 风险清单分页。 */
  public record StockoutRiskItemPage(
      List<StockoutRiskItemRow> rows,
      long total,
      int page,
      int size,
      UUID snapshotId,
      @Schema(nullable = true) String snapshotNo,
      @Schema(nullable = true) LocalDate asOfDate,
      int leadTimeDays,
      String note) {}

  /** 清单查询条件。全部可空，null = 不限。 */
  public record StockoutRiskItemQuery(
      UUID snapshotId,
      String urgency,
      String skuCode,
      String keyword,
      Boolean onlyShortage,
      Boolean onlyWithoutVelocity,
      int page,
      int size) {}

  /** 涉及店铺明细行。 */
  public record StockoutShopRow(String shopCode, int listingCount) {}

  /** 计算过程的一步（逐项拆解）。 */
  public record StockoutDeductionRow(
      int stepOrder,
      String stepKind,
      String stepKindLabel,
      @Schema(nullable = true) BigDecimal quantity,
      String basis,
      String sourceRef) {}

  /** 风险行详情：结论 + 涉及店铺 + 计算过程。 */
  public record StockoutRiskItemDetail(
      StockoutRiskItemRow item, List<StockoutShopRow> shops, List<StockoutDeductionRow> deductions) {}
}
