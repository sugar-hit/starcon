package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.2.1 应付单管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、应付对象、金额与币种、到期日、已付金额与未付余额。 输入：采购收货确认（8.2.1.1）；服务费用（物流、平台、广告）；供应商对账单（6.5.1）；人工录入。 输出：应付单 + 明细 + 未付清单。 关系：上游 = 6 采购、10 物流、14 广告；下游 = 12.2.2.1 付款。
 * <b>应付、预付款与采购承诺分开建模</b>；确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinancePayable*} / {@code FinancePrepayment*} 前缀，与既有七个服务的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。数量 {@code numeric(19,6)}、金额 {@code
 * numeric(19,4)}、汇率 {@code numeric(19,8)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinancePayableCurrencySummary}），USD 与 EUR 加起来不是任何一个金额 ——
 * 这是全仓一致的纪律。
 */
public final class PayableModels {

  private PayableModels() {}

  /** 金额小数位（与 trade / crm 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 应付来源（与 DB {@code finance_payable.source} 同一词表）。
   *
   * <p>⚠️ <b>词表里有两个值当前不可产生</b>：
   *
   * <ul>
   *   <li>{@link #SUPPLIER_STATEMENT}（供应商对账单）—— 财务侧的最终确认属 <b>12.1.2.3 供应商对账</b>（未交付）。
   *       采购侧 6.5.1 的对账单不能直接变成应付：**财务是最终确认方，采购不能自行确认应付**（职责分离）。
   *   <li>{@link #PLATFORM_FEE}（平台费用）—— 平台账单事实属 <b>12.3.1.1 结算单管理</b>（未交付）。
   * </ul>
   *
   * <p>它们的值在 DB 与本枚举里都存在（上游交付后不需要改结构），但服务层会拒绝用它们建单，
   * 并在能力声明里写明归属模块 —— <b>绝不填 0、也绝不造一条假账单</b>。
   */
  public enum PayableSource {
    PURCHASE_RECEIPT("采购收货"),
    LOGISTICS_FEE("物流费用"),
    ADVERTISING_FEE("广告费"),
    SUPPLIER_STATEMENT("供应商对账单"),
    PLATFORM_FEE("平台费用"),
    MANUAL("人工录入"),
    OTHER("其他");

    private final String label;

    PayableSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 这个来源**当前**能不能在本服务里产生应付。
     *
     * <p>⇒ 可产：{@link #PURCHASE_RECEIPT}（6.3.2.2 到货应付部分确认）、{@link #LOGISTICS_FEE}
     * （10.4.1.1 头程发运单）、{@link #ADVERTISING_FEE}（14.3.1.1 活动花费）、{@link #MANUAL} 与
     * {@link #OTHER}（人工录入）。供应商对账与平台费用等各自的上游落地。
     */
    public boolean producible() {
      return this == PURCHASE_RECEIPT
          || this == LOGISTICS_FEE
          || this == ADVERTISING_FEE
          || this == MANUAL
          || this == OTHER;
    }

    /** 这个来源的义务类型（**恒为应付**；预付款与采购承诺是另外两个词表值）。 */
    public ObligationKind obligationKind() {
      return ObligationKind.PAYABLE;
    }
  }

  /**
   * 义务类型 —— 蓝图加粗句「应付、预付款与采购承诺分开建模」在**词表**上的落点。
   *
   * <p>三种义务类型**不是**一张表上的一个 type 字段：
   *
   * <ul>
   *   <li>{@link #PAYABLE} 应付 —— 本服务的 {@code finance_payable} 聚合。
   *   <li>{@link #PREPAYMENT} 预付款 —— 本服务的 {@code finance_prepayment} 聚合（**另一张表**）。
   *   <li>{@link #COMMITMENT} 采购承诺 —— <b>本服务不建模</b>：事实所有者是 6 采购（trade 的采购单）。
   * </ul>
   */
  public enum ObligationKind {
    PAYABLE("应付"),
    PREPAYMENT("预付款"),
    COMMITMENT("采购承诺");

    private final String label;

    ObligationKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 应付单状态。 */
  public enum PayableStatus {
    /** 内部状态：建单事务里的中间态（先落单头、落明细，再确认）。不会出现在列表里。 */
    DRAFT("草稿"),
    OPEN("未付"),
    PARTIALLY_PAID("部分已付"),
    PAID("已付清"),
    /** 作废（红冲的等价物）：终态，整单冻结。 */
    VOID("已作废");

    private final String label;

    PayableStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改，只能新建一张冲销单。 */
    public boolean terminal() {
      return this == PAID || this == VOID;
    }
  }

  /** 应付对象类型。 */
  public enum CounterpartyType {
    SUPPLIER("供应商"),
    CARRIER("承运商"),
    PLATFORM("平台"),
    OTHER("其他");

    private final String label;

    CounterpartyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预付款状态。 */
  public enum PrepaymentStatus {
    OPEN("未抵扣"),
    PARTIALLY_ALLOCATED("部分抵扣"),
    ALLOCATED("已抵扣完"),
    VOID("已作废");

    private final String label;

    PrepaymentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预付款抵扣目标类型。 */
  public enum PrepaymentTargetType {
    /** 抵扣到本服务的一张应付单。 */
    PAYABLE("应付单"),
    /** 抵扣到外部的采购单 / 合同（自由文本引用）。 */
    PURCHASE_ORDER("采购单");

    private final String label;

    PrepaymentTargetType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinancePayableCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinancePayableDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.1.2.1 的能力声明。 */
  public record FinancePayableCapabilities(
      List<FinancePayableCapability> capabilities,
      List<FinancePayableDownstreamGap> downstreamGaps) {}

  // ================================================================ 政策

  /**
   * 应付确认政策（**租户自己维护**）。
   *
   * <p>这是「确认依据由业务合同和已批准政策决定」这条红线的物化形态：什么业务确认为应付、账期多少天，
   * 由租户配置；系统不替用户选。
   */
  public record FinancePayablePolicyRow(
      UUID id,
      PayableSource source,
      ObligationKind obligationKind,
      boolean enabled,
      int dueDays,
      String note,
      String ownerModule,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 政策视图：**每个（来源 × 义务类型）都列**，没配过的也列出来（configured=false）。
   *
   * <p>⚠️ 没配过的**不是「禁用」，而是「未配置」** —— 两者在界面上必须能分开：
   * 「未配置」意味着「你还没决定」，「禁用」意味着「你决定了这个来源不产生应付」。
   */
  public record FinancePayablePolicyView(
      PayableSource source,
      String sourceLabel,
      ObligationKind obligationKind,
      String obligationKindLabel,
      boolean configured,
      boolean enabled,
      int dueDays,
      String note,
      String ownerModule,
      boolean producible,
      String detail) {}

  /** 配置一个来源的确认政策。 */
  public record FinancePayablePolicyInput(
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Integer dueDays,
      @Schema(nullable = true) String note) {}

  // ================================================================ 应付单

  /** 应付单头。 */
  public record FinancePayableRow(
      UUID id,
      String payableNo,
      PayableSource source,
      String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) String baseCurrency,
      /** 本位币金额快照（**没有汇率时为 null**，不是 0）。 */
      @Schema(nullable = true) BigDecimal baseAmount,
      BigDecimal paidAmount,
      BigDecimal unpaidAmount,
      PayableStatus status,
      LocalDate dueDate,
      OffsetDateTime confirmedAt,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 应付明细行。 */
  public record FinancePayableLineRow(
      UUID id,
      int lineNo,
      String description,
      String skuCode,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      BigDecimal amount,
      BigDecimal paidAmount,
      String paymentStatus) {}

  /** 单据事件（append-only 举证凭据）。 */
  public record FinancePayableEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 应付单详情：一次调用返回整屏。 */
  public record FinancePayableDetail(
      FinancePayableRow payable, List<FinancePayableLineRow> lines, List<FinancePayableEventRow> events) {}

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinancePayableCurrencySummary(
      String currency,
      BigDecimal amount,
      BigDecimal paidAmount,
      BigDecimal unpaidAmount,
      long count,
      long overdueCount,
      BigDecimal overdueAmount) {}

  /** 应付单列表页。 */
  public record FinancePayablePage(
      List<FinancePayableRow> items,
      int page,
      int size,
      long total,
      List<FinancePayableCurrencySummary> summaryByCurrency,
      long overdueCount) {}

  // ================================================================ 未付清单

  /**
   * 未付清单的一行（12.1.2.1 的输出之一：「应付单 + 明细 + 未付清单」）。
   *
   * <p>清单口径：**未付清**的单（OPEN / PARTIALLY_PAID），按到期日排。
   */
  public record FinancePayableUnpaidItem(
      UUID payableId,
      String payableNo,
      PayableSource source,
      CounterpartyType counterpartyType,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      BigDecimal paidAmount,
      BigDecimal unpaidAmount,
      LocalDate dueDate,
      long daysOverdue,
      PayableStatus status) {}

  /** 未付清单：按币种分开的小计 + 明细行。 */
  public record FinancePayableUnpaidList(
      LocalDate asOfDate,
      List<FinancePayableCurrencySummary> summaryByCurrency,
      List<FinancePayableUnpaidItem> items,
      long itemCount) {}

  // ================================================================ 查询参数

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，少了它的话「省略」与「显式传
   * null」在类型上不是一回事。
   */
  public record PayableQuery(
      @Schema(nullable = true) PayableStatus status,
      @Schema(nullable = true) PayableSource source,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) LocalDate dueFrom,
      @Schema(nullable = true) LocalDate dueTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  // ================================================================ 筛选项

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinancePayableOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * <p>⚠️ {@code statuses} 与 {@code sources} 返回的是**实际出现过的**取值，
   * 空的项前端就显示成没有该选项 —— 而不是把整张词表倒出来。
   */
  public record FinancePayableOptions(
      List<String> sources,
      List<String> statuses,
      List<String> currencies,
      List<FinancePayableOptionRef> counterparties) {}

  // ================================================================ 输入

  /**
   * 人工录入一行应付明细。
   *
   * <p>⚠️ <b>没有「单头金额」字段</b>：单头金额由明细合计派生（DB 触发器），
   * 少一个入口就少一种「单头与明细对不上」的可能。
   */
  public record FinancePayableLineInput(
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      BigDecimal amount) {}

  /**
   * 人工录入一张应付单。
   *
   * <p><b>source 只允许 MANUAL / OTHER</b>：采购收货 / 物流费用 / 广告费三个来源
   * **只能**由上游事实产生（手工录入等于自己编一条上游事实）。
   */
  public record FinancePayableInput(
      @Schema(nullable = true) PayableSource source,
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      /** 汇率快照：建单时写死，之后不可改。没有就不折算（baseAmount 为 null）。 */
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) LocalDate dueDate,
      @Schema(nullable = true) String note,
      List<FinancePayableLineInput> lines) {}

  /** 作废（红冲）。理由必填。 */
  public record FinancePayableVoidInput(String reason) {}

  // ================================================================ 导入

  /**
   * 从上游导入应付的结果。
   *
   * @param scanned 这次从上游读到多少条
   * @param imported 新建了多少张
   * @param skipped 已经导入过（幂等跳过）多少条
   * @param truncated 上游翻页是否触到上限（触到了就是**只扫了前 N 条**）
   * @param warnings 逐条说明：哪几条没进来、为什么。**不能吞掉** —— 「导入 0 条」如果不说清原因，
   *     现场会以为上游没有数据。
   */
  public record FinancePayableImportResult(
      int scanned,
      int imported,
      int skipped,
      boolean truncated,
      List<FinancePayableRow> importedRows,
      List<String> warnings) {}

  // ================================================================ 预付款

  /** 预付款单头（**独立聚合**，不是应付单的一部分）。 */
  public record FinancePrepaymentRow(
      UUID id,
      String prepaymentNo,
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      BigDecimal allocatedAmount,
      BigDecimal unallocatedAmount,
      PrepaymentStatus status,
      LocalDate expectedPaidDate,
      @Schema(nullable = true) String sourceReference,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 预付款抵扣明细。 */
  public record FinancePrepaymentAllocationRow(
      UUID id,
      PrepaymentTargetType targetType,
      @Schema(nullable = true) UUID payableId,
      @Schema(nullable = true) String payableNo,
      @Schema(nullable = true) String targetReference,
      BigDecimal amount,
      OffsetDateTime allocatedAt,
      String note) {}

  /** 预付款详情。 */
  public record FinancePrepaymentDetail(
      FinancePrepaymentRow prepayment, List<FinancePrepaymentAllocationRow> allocations) {}

  /** 一个币种的可抵扣余额。 */
  public record FinancePrepaymentCurrencyTotal(String currency, BigDecimal unallocatedAmount) {}

  /**
   * 预付款列表页。
   *
   * @param unallocatedByCurrency 可抵扣余额**按币种分组**（⚠️ 不跨币种相加：
   *     USD 与 EUR 加起来不是任何一个金额）。
   */
  public record FinancePrepaymentPage(
      List<FinancePrepaymentRow> items,
      int page,
      int size,
      long total,
      List<FinancePrepaymentCurrencyTotal> unallocatedByCurrency) {}

  /** 登记一笔预付款。 */
  public record FinancePrepaymentInput(
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      LocalDate expectedPaidDate,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) String note) {}

  /** 登记一笔预付款抵扣。 */
  public record FinancePrepaymentAllocationInput(
      PrepaymentTargetType targetType,
      @Schema(nullable = true) UUID payableId,
      @Schema(nullable = true) String targetReference,
      BigDecimal amount,
      @Schema(nullable = true) String note) {}

  /** 作废预付款。理由必填。 */
  public record FinancePrepaymentVoidInput(String reason) {}
}
