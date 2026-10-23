package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.2.1.1 收款单的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：登记与跟踪资金收入：平台回款、银行到账、PayPal/Stripe/PingPong 等第三方支付到账、其他收入；记录资金账户、金额币种、到账时间与来源。 输入：到账流水导入或手工登记；结算数据。 输出：收款单 +
 * 未核销余额。 关系：上游 = 12.3.1 结算；下游 = 12.2.1.2 核销、12.7.1 资金账户。 <b>一笔收款可核销多笔应收</b>（合并回款），也可能是预收。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceReceipt*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinanceReceiptCurrencySummary}），
 * USD 与 EUR 加起来不是任何一个金额 —— 这是全仓一致的纪律。
 */
public final class ReceiptModels {

  private ReceiptModels() {}

  /** 金额小数位（与同服务 12.1.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 收款来源（与 DB {@code finance_receipt.source} 同一词表）。
   *
   * <p>⚠️ <b>词表里有一个值当前不可产生</b>：{@link #PLATFORM_SETTLEMENT}（平台回款）的上游是
   * 12.3.1.1 结算单管理，**尚未交付**。它的值在 DB 与本枚举里都存在（上游交付时不需要改结构），
   * 但服务层会拒绝用它建单，并在能力声明里写明归属模块 —— <b>绝不填 0、也绝不造一条假结算</b>。
   */
  public enum ReceiptSource {
    PLATFORM_SETTLEMENT("平台回款"),
    BANK_TRANSFER("银行到账"),
    PAYMENT_GATEWAY("第三方支付到账"),
    MANUAL("人工登记"),
    OTHER("其他收入");

    private final String label;

    ReceiptSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 这个来源**当前**能不能在本服务里产生收款。
     *
     * <p>⇒ 四个来源可产：{@link #BANK_TRANSFER}（银行到账）、{@link #PAYMENT_GATEWAY}
     * （PayPal / Stripe / PingPong 等第三方支付到账）、{@link #MANUAL} 与 {@link #OTHER}。
     * 平台回款等 12.3.1.1 结算交付。
     */
    public boolean producible() {
      return this != PLATFORM_SETTLEMENT;
    }
  }

  /** 收款单状态。 */
  public enum ReceiptStatus {
    /** 已登记（在册）。 */
    RECORDED("已登记"),
    /** 已作废（红冲的等价物）：终态，整单冻结。 */
    VOID("已作废");

    private final String label;

    ReceiptStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改，只能新建一张收款单。 */
    public boolean terminal() {
      return this == VOID;
    }
  }

  /** 付款方类型。 */
  public enum PayerType {
    PLATFORM("平台"),
    CUSTOMER("客户"),
    PARTNER("合作方"),
    OTHER("其他");

    private final String label;

    PayerType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 预留核销关系的状态。 */
  public enum AllocationStatus {
    /** 已预留（**不是**已核销）。 */
    RESERVED("已预留"),
    /** 已释放。 */
    RELEASED("已释放");

    private final String label;

    AllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 到账流水导入的格式。 */
  public enum ImportFormat {
    CSV("CSV"),
    JSON("JSON");

    private final String label;

    ImportFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceReceiptCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceReceiptDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.2.1.1 的能力声明。 */
  public record FinanceReceiptCapabilities(
      List<FinanceReceiptCapability> capabilities,
      List<FinanceReceiptDownstreamGap> downstreamGaps) {}

  // ================================================================ 收款单

  /** 收款单头。 */
  public record FinanceReceiptRow(
      UUID id,
      String receiptNo,
      ReceiptSource source,
      String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      String currency,
      BigDecimal amount,
      /** 已核销金额：**核销（12.2.1.2）的唯一入口**，本模块不写 ⇒ 当前恒为 0。 */
      BigDecimal settledAmount,
      /** 未核销余额（**生成列**：amount − settledAmount）。核销未交付前恒等于收款金额。 */
      BigDecimal unsettledAmount,
      /** 预收：**还没有对应应收**的资金。 */
      boolean advanceFlag,
      ReceiptStatus status,
      /** 到账时间（核心字段，timestamptz）。 */
      OffsetDateTime receivedAt,
      PayerType payerType,
      String payerName,
      @Schema(nullable = true) UUID payerRef,
      /** 受管资金账户引用：**恒为 null**（12.7.1.1 未交付）。 */
      @Schema(nullable = true) UUID fundAccountRef,
      /** 资金账户标识（**自由文本**，不是受管资金账户）。 */
      String fundAccountLabel,
      /** 结算单号：**恒为 null**（12.3.1.1 未交付）。 */
      @Schema(nullable = true) String settlementNo,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 预留核销关系（收款 ↔ 应收）。
   *
   * <p>⚠️ {@code reservedAmount} 是**预留金额**，不是核销金额：它**不减少**未核销余额。
   */
  public record FinanceReceiptAllocationRow(
      UUID id,
      UUID receiptId,
      String receiptNo,
      UUID receivableId,
      String receivableNo,
      BigDecimal reservedAmount,
      AllocationStatus status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 单据事件（append-only 举证凭据）。 */
  public record FinanceReceiptEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 收款单详情：一次调用返回整屏。 */
  public record FinanceReceiptDetail(
      FinanceReceiptRow receipt,
      List<FinanceReceiptAllocationRow> allocations,
      List<FinanceReceiptEventRow> events) {}

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinanceReceiptCurrencySummary(
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal unsettledAmount,
      long count,
      long advanceCount,
      BigDecimal advanceAmount) {}

  /** 收款单列表页。 */
  public record FinanceReceiptPage(
      List<FinanceReceiptRow> items,
      int page,
      int size,
      long total,
      List<FinanceReceiptCurrencySummary> summaryByCurrency) {}

  /**
   * 未核销余额（12.2.1.1 的输出之二）。
   *
   * @param asOf 余额基准时刻（UTC）。
   * @param byCurrency **按币种分开**的未核销余额。
   * @param receiptCount 在册收款单数（与币种无关的计数）。
   * @param advanceCount 预收单数（尚无对应应收的资金）。
   */
  public record FinanceReceiptBalance(
      OffsetDateTime asOf,
      List<FinanceReceiptCurrencySummary> byCurrency,
      long receiptCount,
      long advanceCount) {}

  // ================================================================ 查询参数

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}，
   * 少了它的话「省略」与「显式传 null」在类型上不是一回事。
   */
  public record ReceiptQuery(
      @Schema(nullable = true) ReceiptStatus status,
      @Schema(nullable = true) ReceiptSource source,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) PayerType payerType,
      @Schema(nullable = true) Boolean advanceOnly,
      @Schema(nullable = true) LocalDate receivedFrom,
      @Schema(nullable = true) LocalDate receivedTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceReceiptOptions(
      List<String> sources,
      List<String> sourceSystems,
      List<String> statuses,
      List<String> currencies,
      List<String> payerTypes) {}

  // ================================================================ 输入

  /**
   * 手工登记一张收款单。
   *
   * <p>⚠️ {@code source} 只允许 BANK_TRANSFER / PAYMENT_GATEWAY / MANUAL / OTHER：
   * 平台回款的上游（12.3.1.1 结算）**未交付**，服务端会拒绝并写明归属模块。
   */
  public record FinanceReceiptInput(
      @Schema(nullable = true) ReceiptSource source,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      String currency,
      BigDecimal amount,
      /** 到账时间（**必填**）：默认填当前时刻会把「昨天到账的钱」记成今天到。 */
      OffsetDateTime receivedAt,
      PayerType payerType,
      String payerName,
      @Schema(nullable = true) UUID payerRef,
      /** 资金账户标识（自由文本，**不是**受管资金账户）。 */
      @Schema(nullable = true) String fundAccountLabel,
      /** 是否预收（**还没有对应应收**的资金）。 */
      @Schema(nullable = true) Boolean advanceFlag,
      @Schema(nullable = true) String note) {}

  /** 作废（红冲）。理由必填。 */
  public record FinanceReceiptVoidInput(String reason) {}

  /**
   * 预留一笔收款到某笔应收（**登记**合并回款意图，**不是**核销）。
   *
   * @param receivableId 目标应收单。
   * @param reservedAmount 预留金额（**不是**核销金额；不减少未核销余额）。
   */
  public record FinanceReceiptAllocationInput(
      UUID receivableId, BigDecimal reservedAmount, @Schema(nullable = true) String note) {}

  /**
   * 到账流水导入。
   *
   * @param source 这一批流水的来源（平台回款当前不可用）。
   * @param sourceSystem 来源系统（bank / paypal / stripe / pingpong …），与来源流水号组成幂等键。
   * @param format 解析格式。
   * @param content 原始内容（CSV 文本或 JSON 文本）。
   */
  public record FinanceReceiptImportInput(
      @Schema(nullable = true) ReceiptSource source,
      @Schema(nullable = true) String sourceSystem,
      ImportFormat format,
      String content) {}

  /**
   * 到账流水导入的结果。
   *
   * @param scanned 这次解析出多少条
   * @param imported 新建了多少张
   * @param skipped 没进来多少条（幂等命中 / 行内数据有问题）
   * @param importedRows 新建的收款单
   * @param warnings 逐条说明：哪几条没进来、为什么。**不能吞掉** ——
   *     「导入 0 条」如果不说清原因，现场会以为文件是空的。
   */
  public record FinanceReceiptImportResult(
      int scanned,
      int imported,
      int skipped,
      List<FinanceReceiptRow> importedRows,
      List<String> warnings) {}
}
