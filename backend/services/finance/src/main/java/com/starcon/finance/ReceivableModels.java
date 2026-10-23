package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.1.1 应收单管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、应收对象、金额与币种、到期日、账龄、明细核销状态。 输入：应收确认规则（什么业务确认为应收）；结算数据；B2B
 * 订单发货确认；人工录入。 输出：应收单 + 明细行 + 账龄分布；生成催收任务。 关系：上游 = 12.3.1 结算、9 订单（发货确认）；下游 = 12.2.1.2 核销、13 分析。
 * <b>未发货订单不默认等于收入</b>——应收确认政策需单独配置（实体目录 finance 段明确）。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceReceivable*} / {@code FinanceCollectionTask*} 前缀， 与既有六个服务的类型名零重叠
 * —— 契约被 codegen 合并进一个扁平文件， 同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。 数量 {@code numeric(19,6)}、金额 {@code
 * numeric(24,4)}、汇率 {@code numeric(19,8)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinanceReceivableCurrencySummary}）， USD 与 EUR 加起来不是任何一个金额 ——
 * 这是全仓一致的纪律。
 */
public final class ReceivableModels {

  private ReceivableModels() {}

  /** 金额小数位（与 trade / crm 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 应收来源（与 DB {@code finance_receivable.source} 同一词表）。
   *
   * <p>⚠️ <b>词表里有两个值当前不可产生</b>：{@link #PLATFORM_SETTLEMENT}（平台结算）与 {@link
   * #PLATFORM_WITHHOLDING}（平台代扣）的上游是 12.3.1 结算，**尚未交付**。 它们的值在 DB 与本枚举里都存在（12.3.1 交付时不需要改结构），
   * 但服务层会拒绝用它们建单，并在能力声明里写明归属模块 —— <b>绝不填 0、绝不造一条假结算</b>。
   */
  public enum ReceivableSource {
    PLATFORM_SETTLEMENT("平台结算"),
    PLATFORM_WITHHOLDING("平台代扣"),
    B2B_CREDIT_TERM("B2B 客户账期"),
    MANUAL("人工录入"),
    OTHER("其他");

    private final String label;

    ReceivableSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 这个来源**当前**能不能在本服务里产生应收。
     *
     * <p>⇒ 只有三个来源可产：{@link #B2B_CREDIT_TERM}（发货确认事实）、 {@link #MANUAL} 与 {@link #OTHER}（人工录入）。
     * 平台两个来源等 12.3.1 结算交付。
     */
    public boolean producible() {
      return this == B2B_CREDIT_TERM || this == MANUAL || this == OTHER;
    }
  }

  /** 应收单状态。 */
  public enum ReceivableStatus {
    /** 内部状态：建单事务里的中间态（先落单头、落明细，再确认）。不会出现在列表里。 */
    DRAFT("草稿"),
    OPEN("未核销"),
    PARTIALLY_SETTLED("部分核销"),
    SETTLED("已核销"),
    /** 作废（红冲的等价物）：终态，整单冻结。 */
    VOID("已作废");

    private final String label;

    ReceivableStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改，只能新建一张冲销单。 */
    public boolean terminal() {
      return this == SETTLED || this == VOID;
    }
  }

  /** 应收对象类型。 */
  public enum CounterpartyType {
    PLATFORM("平台"),
    CUSTOMER("客户"),
    OTHER("其他");

    private final String label;

    CounterpartyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 账龄区间（卷 12 12.1.1.2 写死的五档）。 */
  public enum AgingBucket {
    NOT_DUE("未到期"),
    D1_30("逾期 1-30 天"),
    D31_60("逾期 31-60 天"),
    D61_90("逾期 61-90 天"),
    D90_PLUS("逾期 90 天以上");

    private final String label;

    AgingBucket(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 催收任务级别。 */
  public enum CollectionTaskLevel {
    REMINDER("到期提醒"),
    OVERDUE("逾期催收"),
    ESCALATION("升级处理");

    private final String label;

    CollectionTaskLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 催收任务状态。 */
  public enum CollectionTaskStatus {
    OPEN("待处理"),
    DONE("已完成"),
    CANCELLED("已取消");

    private final String label;

    CollectionTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceReceivableCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceReceivableDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.1.1.1 的能力声明。 */
  public record FinanceReceivableCapabilities(
      List<FinanceReceivableCapability> capabilities,
      List<FinanceReceivableDownstreamGap> downstreamGaps) {}

  // ================================================================ 政策

  /**
   * 应收确认政策（**租户自己维护**）。
   *
   * <p>这是「不自己定会计政策」这条红线的物化形态：什么业务确认为应收、账期多少天， 由租户配置；系统不替用户选。
   */
  public record FinanceReceivablePolicyRow(
      UUID id,
      ReceivableSource source,
      boolean enabled,
      int dueDays,
      String note,
      String ownerModule,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 政策视图：**五个来源全列**，没配过的来源也列出来（configured=false）。
   *
   * <p>⚠️ 没配过的来源**不是「禁用」，而是「未配置」** —— 两者在界面上必须能分开： 「未配置」意味着「你还没决定」，「禁用」意味着「你决定了这个来源不产生应收」。
   */
  public record FinanceReceivablePolicyView(
      ReceivableSource source,
      String sourceLabel,
      boolean configured,
      boolean enabled,
      int dueDays,
      String note,
      String ownerModule,
      boolean producible,
      String detail) {}

  /** 配置一个来源的确认政策。 */
  public record FinanceReceivablePolicyInput(
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Integer dueDays,
      @Schema(nullable = true) String note) {}

  // ================================================================ 应收单

  /** 应收单头。 */
  public record FinanceReceivableRow(
      UUID id,
      String receivableNo,
      ReceivableSource source,
      String sourceSystem,
      @Schema(nullable = true) String sourceReference,
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) String baseCurrency,
      /** 本位币金额快照（**没有汇率时为 null**，不是 0）。 */
      @Schema(nullable = true) BigDecimal baseAmount,
      BigDecimal settledAmount,
      ReceivableStatus status,
      LocalDate dueDate,
      OffsetDateTime confirmedAt,
      boolean disputeFlag,
      String disputeNote,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 应收明细行。 */
  public record FinanceReceivableLineRow(
      UUID id,
      int lineNo,
      String description,
      String skuCode,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      BigDecimal amount,
      BigDecimal settledAmount,
      String settlementStatus) {}

  /** 单据事件（append-only 举证凭据）。 */
  public record FinanceReceivableEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 催收任务。 */
  public record FinanceCollectionTaskRow(
      UUID id,
      String taskNo,
      UUID receivableId,
      String receivableNo,
      CollectionTaskLevel level,
      LocalDate dueDate,
      CollectionTaskStatus status,
      String assigneeName,
      String reason,
      @Schema(nullable = true) OffsetDateTime completedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 应收单详情：一次调用返回整屏。 */
  public record FinanceReceivableDetail(
      FinanceReceivableRow receivable,
      List<FinanceReceivableLineRow> lines,
      List<FinanceReceivableEventRow> events,
      List<FinanceCollectionTaskRow> collectionTasks) {}

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public record FinanceReceivableCurrencySummary(
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal openAmount,
      long count,
      long overdueCount,
      BigDecimal overdueAmount,
      BigDecimal disputeAmount) {}

  /** 应收单列表页。 */
  public record FinanceReceivablePage(
      List<FinanceReceivableRow> items,
      int page,
      int size,
      long total,
      List<FinanceReceivableCurrencySummary> summaryByCurrency,
      long overdueCount) {}

  // ================================================================ 查询参数

  /**
   * 列表查询条件。
   *
   * <p>可选属性显式带 {@code | null}：仓库启用了 {@code exactOptionalPropertyTypes}， 少了它的话「省略」与「显式传
   * null」在类型上不是一回事。
   */
  public record ReceivableQuery(
      @Schema(nullable = true) ReceivableStatus status,
      @Schema(nullable = true) ReceivableSource source,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) Boolean disputeOnly,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) LocalDate dueFrom,
      @Schema(nullable = true) LocalDate dueTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 催收任务查询条件。 */
  public record CollectionTaskQuery(
      @Schema(nullable = true) CollectionTaskStatus status,
      @Schema(nullable = true) CollectionTaskLevel level,
      @Schema(nullable = true) UUID receivableId,
      int page,
      int size) {}

  /** 催收任务列表页。 */
  public record FinanceCollectionTaskPage(
      List<FinanceCollectionTaskRow> items, int page, int size, long total) {}

  // ================================================================ 筛选项

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinanceReceivableOptionRef(String id, String name) {}

  /** 账龄区间选项。 */
  public record FinanceReceivableBucketOption(String code, String label) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * <p>⚠️ {@code statuses} 与 {@code sources} 返回的是**实际出现过的**取值， 空的项前端就显示成没有该选项 —— 而不是把整张词表倒出来。
   */
  public record FinanceReceivableOptions(
      List<String> sources,
      List<String> statuses,
      List<String> currencies,
      List<FinanceReceivableOptionRef> counterparties,
      List<FinanceReceivableOptionRef> shops,
      List<FinanceReceivableBucketOption> agingBuckets) {}

  // ================================================================ 账龄

  /** 一个账龄区间 × 一个币种。 */
  public record FinanceReceivableAgingBucket(
      AgingBucket bucket,
      String bucketLabel,
      String currency,
      BigDecimal amount,
      long count,
      BigDecimal disputeAmount) {}

  /**
   * 账龄分布。
   *
   * @param asOfDate 账龄基准日（UTC）。**写进响应**是为了让「这张报表是哪天算的」可追溯。
   * @param currencyTotals 每个币种的未核销总额（分母）。
   */
  public record FinanceReceivableAging(
      LocalDate asOfDate,
      List<FinanceReceivableAgingBucket> buckets,
      List<FinanceReceivableCurrencySummary> currencyTotals,
      long openCount) {}

  // ================================================================ 输入

  /**
   * 人工录入一行应收明细。
   *
   * <p>⚠️ <b>没有「单头金额」字段</b>：单头金额由明细合计派生（DB 触发器）， 少一个入口就少一种「单头与明细对不上」的可能。
   */
  public record FinanceReceivableLineInput(
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      BigDecimal amount) {}

  /**
   * 人工录入一张应收单。
   *
   * <p><b>source 只允许 MANUAL / OTHER</b>：B2B 账期应收**只能**由发货确认事实产生 （手工录入等于自己编一个发货确认），平台两个来源的上游还没交付。
   */
  public record FinanceReceivableInput(
      @Schema(nullable = true) ReceivableSource source,
      CounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopName,
      String currency,
      /** 汇率快照：建单时写死，之后不可改。没有就不折算（baseAmount 为 null）。 */
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) LocalDate dueDate,
      @Schema(nullable = true) String note,
      List<FinanceReceivableLineInput> lines) {}

  /** 作废（红冲）。理由必填。 */
  public record FinanceReceivableVoidInput(String reason) {}

  /** 标注 / 取消「争议中」。 */
  public record FinanceReceivableDisputeInput(
      boolean flagged, @Schema(nullable = true) String note) {}

  /** 建催收任务。 */
  public record FinanceCollectionTaskInput(
      CollectionTaskLevel level,
      @Schema(nullable = true) LocalDate dueDate,
      @Schema(nullable = true) String assigneeName,
      @Schema(nullable = true) String reason) {}

  /** 关闭催收任务。 */
  public record FinanceCollectionTaskCloseInput(@Schema(nullable = true) String reason) {}

  // ================================================================ 导入

  /**
   * 从上游（trade 发货确认）导入应收的结果。
   *
   * @param scanned 这次从上游读到多少条
   * @param imported 新建了多少张
   * @param skipped 已经导入过（幂等跳过）多少条
   * @param truncated 上游翻页是否触到上限（触到了就是**只扫了前 N 条**）
   * @param warnings 逐条说明：哪几条没进来、为什么。**不能吞掉** —— 「导入 0 条」如果不说清原因，现场会以为上游没有数据。
   */
  public record FinanceReceivableImportResult(
      int scanned,
      int imported,
      int skipped,
      boolean truncated,
      List<FinanceReceivableRow> importedRows,
      List<String> warnings) {}
}
