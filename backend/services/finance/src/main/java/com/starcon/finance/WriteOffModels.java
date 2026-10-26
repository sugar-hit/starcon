package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.2.1.2 核销的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：把收款核销到应收：自动匹配（金额+订单号+日期）或人工指定，支持部分核销、合并核销、预收挂账、核销撤销。 输入：收款单；待核销应收；匹配规则或人工分配。 输出：核销记录 +
 * 应收余额更新 + 预收余额。 关系：上游 = 12.2.1.1、12.1.1.1；下游 = 13 利润（回款口径）。 <b>核销必须精确到明细行</b>，只核销到订单头无法处理部分回款与折让。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceWriteOff*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回，USD 与 EUR 加起来不是任何一个金额。
 */
public final class WriteOffModels {

  private WriteOffModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.2.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /** 核销方式。 */
  public enum WriteOffMode {
    /** 自动匹配（金额 + 订单号 + 日期三者对上）。 */
    AUTO_MATCH("自动匹配"),
    /** 人工指定分配。 */
    MANUAL("人工指定"),
    /** 预收挂账（钱到了但还没有对应应收）。 */
    ADVANCE_PARKING("预收挂账");

    private final String label;

    WriteOffMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 核销记录状态。 */
  public enum WriteOffStatus {
    /** 已核销（记账事实已成立）。 */
    POSTED("已核销"),
    /** 已撤销（终态，整单冻结）。 */
    REVERSED("已撤销");

    private final String label;

    WriteOffStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改。 */
    public boolean terminal() {
      return this == REVERSED;
    }
  }

  /** 分配类型。 */
  public enum AllocationType {
    /** 核销到某条应收**明细行**。 */
    SETTLEMENT("核销到应收明细行"),
    /** 预收挂账：钱到了，但还没有对应应收。 */
    ADVANCE("预收挂账");

    private final String label;

    AllocationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分配明细状态。 */
  public enum AllocationStatus {
    /** 已核销。 */
    POSTED("已核销"),
    /** 已撤销（终态）。 */
    REVERSED("已撤销");

    private final String label;

    AllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == REVERSED;
    }
  }

  /** 预收挂账状态。 */
  public enum AdvanceLedgerStatus {
    /** 还挂在预收账上（**预收余额**算的就是它）。 */
    OPEN("挂账中"),
    /** 已释放：这笔钱已被核销用掉。 */
    RELEASED("已释放"),
    /** 已撤销：挂账本身被撤销了。 */
    REVERSED("已撤销");

    private final String label;

    AdvanceLedgerStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 还在预收余额里吗？ */
    public boolean open() {
      return this == OPEN;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceWriteOffCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceWriteOffDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.2.1.2 的能力声明。 */
  public record FinanceWriteOffCapabilities(
      List<FinanceWriteOffCapability> capabilities,
      List<FinanceWriteOffDownstreamGap> downstreamGaps) {}

  // ================================================================ 核销记录

  /** 核销记录（单头）。 */
  public record FinanceWriteOffRow(
      UUID id,
      String writeOffNo,
      UUID receiptId,
      String receiptNo,
      String currency,
      WriteOffMode mode,
      WriteOffStatus status,
      /** 核销到应收明细行的金额（**派生**：分配明细里 SETTLEMENT 的合计）。 */
      BigDecimal settlementAmount,
      /** 挂到预收账的金额（**派生**：分配明细里 ADVANCE 的合计）。 */
      BigDecimal advanceAmount,
      /** 本次核销总额（**派生**：上面两项之和）。 */
      BigDecimal totalAmount,
      String note,
      @Schema(nullable = true) UUID actorId,
      /** 核销时刻（timestamptz）。 */
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime reversedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 核销分配明细。
   *
   * <p>⚠️ {@code receivableLineId} 是**明细行**的 id —— 「核销必须精确到明细行」，
   * 只核销到订单头无法处理部分回款与折让。
   */
  public record FinanceWriteOffAllocationRow(
      UUID id,
      UUID writeOffId,
      UUID receiptId,
      @Schema(nullable = true) UUID receivableId,
      @Schema(nullable = true) String receivableNo,
      @Schema(nullable = true) UUID receivableLineId,
      @Schema(nullable = true) Integer lineNo,
      @Schema(nullable = true) String orderNo,
      AllocationType allocationType,
      BigDecimal amount,
      AllocationStatus status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 核销事件（append-only 举证凭据）。 */
  public record FinanceWriteOffEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 核销记录详情：一次调用返回整屏。 */
  public record FinanceWriteOffDetail(
      FinanceWriteOffRow writeOff,
      List<FinanceWriteOffAllocationRow> allocations,
      List<FinanceWriteOffEventRow> events) {}

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinanceWriteOffCurrencySummary(
      String currency,
      BigDecimal settlementAmount,
      BigDecimal advanceAmount,
      BigDecimal totalAmount,
      long count) {}

  /** 核销记录列表页。 */
  public record FinanceWriteOffPage(
      List<FinanceWriteOffRow> items,
      int page,
      int size,
      long total,
      List<FinanceWriteOffCurrencySummary> summaryByCurrency) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceWriteOffOptions(
      List<String> statuses, List<String> modes, List<String> currencies) {}

  // ================================================================ 待核销视图

  /** 待核销的收款单。 */
  public record FinanceWriteOffPendingReceipt(
      UUID id,
      String receiptNo,
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal unsettledAmount,
      boolean advanceFlag,
      /** 挂在这笔收款上、还没释放的预收挂账额（**专用**，不能拿去核销别的应收）。 */
      BigDecimal openAdvanceAmount,
      /** 这次能拿去核销的金额 = 未核销余额 − 未释放挂账额。 */
      BigDecimal availableAmount,
      OffsetDateTime receivedAt,
      String payerName) {}

  /** 待核销的应收**明细行**（核销的入口就是它）。 */
  public record FinanceWriteOffPendingLine(
      UUID id,
      UUID receivableId,
      String receivableNo,
      int lineNo,
      @Schema(nullable = true) String orderNo,
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal openAmount,
      LocalDate dueDate,
      OffsetDateTime confirmedAt) {}

  /** 待核销视图：收款单 + 应收明细行（两侧都要能选，才可能核销）。 */
  public record FinanceWriteOffPending(
      List<FinanceWriteOffPendingReceipt> receipts,
      List<FinanceWriteOffPendingLine> lines) {}

  // ================================================================ 预收余额

  /** 预收挂账行。 */
  public record FinanceWriteOffAdvanceRow(
      UUID id,
      UUID receiptId,
      String receiptNo,
      UUID writeOffId,
      String writeOffNo,
      String currency,
      BigDecimal amount,
      AdvanceLedgerStatus status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 按币种的预收余额。
   *
   * @param currency 币种
   * @param parkedAmount 本模块挂账的预收（ledger 里 OPEN 的合计）
   * @param flaggedAmount 12.2.1.1 预收标记收款单的未核销余额
   * @param totalAmount 两者之和（两个口径**互不重复计数**）
   * @param count 挂账笔数
   */
  public record FinanceWriteOffAdvanceCurrencySummary(
      String currency,
      BigDecimal parkedAmount,
      BigDecimal flaggedAmount,
      BigDecimal totalAmount,
      long count) {}

  /** 预收余额（12.2.1.2 的输出之一）。 */
  public record FinanceWriteOffAdvanceBalance(
      OffsetDateTime asOf, List<FinanceWriteOffAdvanceCurrencySummary> byCurrency, long ledgerCount) {}

  // ================================================================ 自动匹配

  /**
   * 自动匹配命中的一条应收明细行。
   *
   * @param matchedAmount 本次核销金额（= 该行的未核销余额）
   * @param matchDate 用于比对的日期（收款单到账日期或调用方指定）
   * @param confirmedDate 应收单的确认日期
   * @param reasons 命中原因（逐条列出，便于人工复核「凭什么匹配上」）
   */
  public record FinanceWriteOffMatchCandidate(
      UUID receivableLineId,
      UUID receivableId,
      String receivableNo,
      int lineNo,
      @Schema(nullable = true) String orderNo,
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal openAmount,
      BigDecimal matchedAmount,
      LocalDate confirmedDate,
      LocalDate matchDate,
      List<String> reasons) {}

  /** 自动匹配的结果。 */
  public record FinanceWriteOffAutoMatchResult(
      FinanceWriteOffRow writeOff,
      List<FinanceWriteOffMatchCandidate> candidates) {}

  // ================================================================ 查询与输入

  /** 列表查询条件。 */
  public record WriteOffQuery(
      @Schema(nullable = true) WriteOffStatus status,
      @Schema(nullable = true) WriteOffMode mode,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID receiptId,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /**
   * 人工指定核销。
   *
   * @param receiptId 资金来源（收款单）。
   * @param allocations 分配明细：**至少一条**；SETTLEMENT 必须给 receivableLineId。
   * @param occurredAt 核销时刻（不填 = 当前时刻）。
   */
  public record FinanceWriteOffInput(
      UUID receiptId,
      List<FinanceWriteOffAllocationInput> allocations,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /** 一条分配明细。 */
  public record FinanceWriteOffAllocationInput(
      AllocationType type,
      @Schema(nullable = true) UUID receivableLineId,
      BigDecimal amount,
      @Schema(nullable = true) String note) {}

  /**
   * 自动匹配核销。
   *
   * @param receiptId 资金来源（收款单）。
   * @param orderNo 订单号（**必填**：明细行的 order_no 必须完全相等）。
   * @param amount 目标金额（不填 = 该收款单的未核销余额）。
   * @param matchDate 比对日期（不填 = 该收款单的到账日期，UTC 日期）。
   * @param dateToleranceDays 日期容差（0..90，不填 = 0，即必须完全相等）。
   */
  public record FinanceWriteOffAutoMatchInput(
      UUID receiptId,
      String orderNo,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) LocalDate matchDate,
      @Schema(nullable = true) Integer dateToleranceDays,
      @Schema(nullable = true) String note) {}

  /** 核销撤销。 */
  public record FinanceWriteOffReverseInput(String reason) {}
}
