package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.3.1.3 延迟结算的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。 输入：结算规则；订单与结算状态。 输出：延迟结算明细 +
 * 在途资金统计 + 预计到账时间。 关系：上游 = 12.3.1.1、9 订单；下游 = 13 利润（按已结算/未结算区分口径）。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceDeferredSettlement*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}，数量 {@code numeric(19,6)}。
 *
 * <p><b>两套口径并存，绝不相加。</b>下游 13 利润要求「按已结算 / 未结算区分口径」，本模块
 * <b>同时</b>给出两套口径并<b>按币种分组</b>（USD 与 EUR 加起来不是任何一个金额）：
 *
 * <ul>
 *   <li><b>订单口径</b>：平台订单总额（毛额）—— 按「订单已发生」确认收入。
 *   <li><b>已结算口径</b>：该订单在<b>已到账</b>结算单里的<b>销售额行</b>合计 —— 按「钱已到账」确认收入。
 * </ul>
 *
 * <p>两套口径的基数不同（毛额 vs 结算销售额行），差额里含税与运费等尚未单独结算的部分。本模块
 * <b>不替下游做口径取舍</b>，只把两套口径分币种同时给出；口径定义写在
 * {@link FinanceDeferredSettlementBasisNote} 里，随每个汇总响应一起返回。
 */
public final class DeferredSettlementModels {

  private DeferredSettlementModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.2.1.1 / 12.3.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 结算模式。
   *
   * <p>蓝图原文：「部分平台按周期后结算或滚动结算」—— 本枚举就是这句话的**逐项**落地。
   */
  public enum DeferredSettlementMode {
    /** 周期后结算：订单按发货日各自进入一个结算周期，周期结束后统一打款。 */
    PERIODIC("周期后结算"),
    /** 滚动结算：平台按固定窗口滚动结算，订单落进它发货日之后的下一个窗口。 */
    ROLLING("滚动结算");

    private final String label;

    DeferredSettlementMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 延迟结算台账状态。 */
  public enum DeferredSettlementStatus {
    /** 在途未结算（**钱未到账**：还没出现在任何已到账结算单里）。 */
    PENDING("在途未结算"),
    /** 已结算（**钱已到账**：已出现在某张已到账结算单里）。 */
    SETTLED("已结算"),
    /** 已作废（终态，不进任何口径）。 */
    VOID("已作废");

    private final String label;

    DeferredSettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改。 */
    public boolean terminal() {
      return this == VOID;
    }

    /** 进汇总口径（作废的台账行在资金口径上不存在）。 */
    public boolean counted() {
      return this != VOID;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceDeferredSettlementCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceDeferredSettlementDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.3.1.3 的能力声明。 */
  public record FinanceDeferredSettlementCapabilities(
      List<FinanceDeferredSettlementCapability> capabilities,
      List<FinanceDeferredSettlementDownstreamGap> downstreamGaps) {}

  // ================================================================ 结算规则

  /** 结算规则（蓝图输入里的「结算规则」，本模块自己的输入）。 */
  public record FinanceDeferredSettlementRuleRow(
      UUID id,
      String platformCode,
      /** 店铺 code；为空 = 平台级默认规则。 */
      @Schema(nullable = true) String shopCode,
      @Schema(nullable = true) UUID shopId,
      DeferredSettlementMode settlementMode,
      String settlementModeLabel,
      int cycleDays,
      int payoutLagDays,
      /** 规则限定币种；为空 = 不限。 */
      @Schema(nullable = true) String currency,
      String conditionNote,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 改写一条结算规则。
   *
   * <p>⚠️ {@code shopCode} 为空表示**平台级默认规则**（该平台下所有店铺都用它）；店铺级规则优先。
   */
  public record FinanceDeferredSettlementRuleInput(
      String platformCode,
      @Schema(nullable = true) String shopCode,
      @Schema(nullable = true) UUID shopId,
      DeferredSettlementMode settlementMode,
      int cycleDays,
      int payoutLagDays,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String conditionNote,
      @Schema(nullable = true) String status) {}

  // ================================================================ 延迟结算明细

  /** 延迟结算台账行（一行 = 一个平台订单）。 */
  public record FinanceDeferredSettlementRow(
      UUID id,
      UUID ruleId,
      /** 平台订单（trade 服务的逻辑 ID，跨服务不建 FK）。 */
      UUID platformOrderId,
      @Schema(nullable = true) UUID salesOrderId,
      String orderNo,
      String externalOrderNo,
      String platformCode,
      String shopCode,
      String currency,
      /** **订单口径金额**：平台订单总额（毛额）。 */
      BigDecimal orderAmount,
      /** **已结算口径金额**：派生自已到账结算单的销售额行。 */
      BigDecimal settledAmount,
      /** **在途未结算金额**：max(订单口径 − 已结算口径, 0)。 */
      BigDecimal unsettledAmount,
      /** **发货时间**（来自 trade 的发货记录）。 */
      OffsetDateTime shippedAt,
      String shipmentStatus,
      DeferredSettlementMode settlementMode,
      String settlementModeLabel,
      int cycleDays,
      int payoutLagDays,
      /** **结算周期区间**（派生）。 */
      OffsetDateTime settlementCycleStart,
      /** **结算周期区间**（派生）。 */
      OffsetDateTime settlementCycleEnd,
      /** **预计到账时间**（派生）。 */
      OffsetDateTime expectedArrivedAt,
      /** 结算条件快照（人话，明细自解释）。 */
      String conditionNote,
      DeferredSettlementStatus status,
      String statusLabel,
      /** 覆盖该订单的已到账结算单（最近一张）。 */
      @Schema(nullable = true) UUID settlementId,
      @Schema(nullable = true) String settlementNo,
      int settledSettlementCount,
      String source,
      OffsetDateTime scannedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 台账事件（append-only 举证凭据）。 */
  public record FinanceDeferredSettlementEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 台账明细：一次调用返回整屏。 */
  public record FinanceDeferredSettlementDetail(
      FinanceDeferredSettlementRow deferredSettlement,
      List<FinanceDeferredSettlementEventRow> events) {}

  // ================================================================ 在途资金统计（两套口径）

  /**
   * 按币种的**两套口径**汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinanceDeferredSettlementCurrencySummary(
      String currency,
      /** **订单口径**：平台订单总额合计（毛额）。 */
      BigDecimal orderBasisAmount,
      /** **已结算口径**：已到账结算单里的销售额行合计。 */
      BigDecimal settledBasisAmount,
      /** **在途未结算**：max(订单口径 − 已结算口径, 0) 合计。 */
      BigDecimal inTransitAmount,
      /**
       * **口径基数差**：订单口径 − 已结算口径 − 在途未结算。
       *
       * <p>不为 0 说明「已结算口径 > 订单口径」的订单存在（结算单按含税口径记销售额），
       * 在途未结算被截到了 0 —— 这一格**如实报出来**，不藏。
       */
      BigDecimal basisGapAmount,
      long count,
      /** 钱未到账的笔数。 */
      long pendingCount,
      /** 钱已到账的笔数。 */
      long settledCount,
      /** 预计到账时间已过、但钱仍未到账的笔数（催款名单）。 */
      long overdueCount) {}

  /**
   * 两套口径的定义说明。
   *
   * <p>⚠️ 口径定义**必须随数据一起返回**：下游 13 利润拿到的数字如果脱离口径说明，
   * 半年后没人说得清「订单口径」到底是毛额还是净额。
   */
  public record FinanceDeferredSettlementBasisNote(
      String orderBasis,
      String settledBasis,
      String inTransitBasis,
      String basisDifference,
      String currencyPolicy) {}

  /** 在途资金统计（蓝图输出之一：「在途资金统计 + 预计到账时间」）。 */
  public record FinanceDeferredSettlementSummary(
      List<FinanceDeferredSettlementCurrencySummary> summaryByCurrency,
      /** 口径定义说明。 */
      FinanceDeferredSettlementBasisNote basis,
      /** 预计到账时间最近的一笔（催款起点）。 */
      @Schema(nullable = true) OffsetDateTime earliestExpectedArrival,
      /** 距离最近一笔预计到账的天数。 */
      long daysToEarliestExpectedArrival,
      /** 统计口径时刻（UTC）。 */
      OffsetDateTime computedAt) {}

  /** 延迟结算明细列表页。 */
  public record FinanceDeferredSettlementPage(
      List<FinanceDeferredSettlementRow> items,
      int page,
      int size,
      long total,
      List<FinanceDeferredSettlementCurrencySummary> summaryByCurrency,
      FinanceDeferredSettlementBasisNote basis) {}

  // ================================================================ 筛选项 / 查询

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record DeferredSettlementQuery(
      @Schema(nullable = true) DeferredSettlementStatus status,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String shopCode,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) OffsetDateTime shippedFrom,
      @Schema(nullable = true) OffsetDateTime shippedTo,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceDeferredSettlementOptions(
      List<String> statuses,
      List<String> platforms,
      List<String> shops,
      List<String> currencies,
      List<String> modes) {}

  // ================================================================ 扫描

  /**
   * 扫描结果：从 trade 的平台订单 + 发货记录里挑出「已发货」的订单写进台账。
   *
   * @param fetchedOrders 读到多少条平台订单
   * @param fetchedShipments 读到多少条发货记录
   * @param matched 命中「已发货」且有生效规则的订单笔数
   * @param created 新建了多少行台账
   * @param updated 更新了多少行台账
   * @param skipped 没进来的笔数（订单已取消 / 没有发货记录 / 没有生效规则 / 缺金额 …）
   * @param truncated 是否触到翻页上限（上游还有数据没读完）
   * @param warnings 逐条说明：哪几笔没进来、为什么。**不能吞掉** ——
   *     「扫了 0 笔」如果不说清原因，现场会以为平台没有订单。
   */
  public record FinanceDeferredSettlementScanResult(
      int fetchedOrders,
      int fetchedShipments,
      int matched,
      int created,
      int updated,
      int skipped,
      boolean truncated,
      List<String> warnings) {}

  // ================================================================ 输入

  /** 作废（红冲的等价物）。理由必填。 */
  public record FinanceDeferredSettlementVoidInput(String reason) {}
}
