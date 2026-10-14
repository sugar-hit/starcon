package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.4.2.2 盘点差异处理的传输模型。
 *
 * <p>蓝图原话：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单 走审批，盘盈盘亏入账并可追责。」输入：「盘点差异明细；差异原因；处理决定（调整/复盘/忽略）。」
 * 输出：「调整单 + 盘点完成报告（含盘盈率、差异率指标）。」以及本模块的红线： 「**差异不直接改余额**，必须经调整单与审批，这是实体目录明确的红线。」
 *
 * <p><b>差异只有一份真相，而且不在本模块</b>：账面 / 实盘 / 差异三列是 7.4.2.1 的 {@code stocktake_line}，其中差异是 {@code
 * counted_qty − book_on_hand} 的生成列。 本模块存的是**处置**（谁、为什么、怎么处理、批没批、入没入账）， 一行差异都不自己造 ——
 * 否则库里就有两份差异，从此无法判断该信谁。
 *
 * <p><b>调整单实体落在这里的理由</b>（蓝图把 7.4.1.1 列为下游，而它尚未交付）： 没有下游就不能把差异处理停摆，账面与实物只会越差越远。所以本模块自落地一个调整单
 * 实体走完闭环，并明确声明它**不是** 7.4.1.1 的实现；两者的交接靠 V1 既有的定位键 {@code
 * inventory_transaction(ref_type='STOCKTAKE_VARIANCE', ref_id=<调整单 id>)}， 跨服务不建 FK、不查对方的表（AGENTS.md
 * 同一纪律）。
 *
 * <p><b>审批没有接入 17.4</b>：审批中心尚未交付，本模块用的是可审计的替代机制 —— 状态机（含 DB 级转移约束）+ 提交人≠审批人的职责分离 + append-only 审计。
 */
public final class StocktakeVarianceModels {

  private StocktakeVarianceModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 差异原因（五类，蓝图原文逐字对应）。
   *
   * <p>五类的语义差别是**要不要动账**，不是措辞：
   *
   * <ul>
   *   <li>{@link #SHRINKAGE} 损耗 —— 实物损耗、破损、过期报废。货确实少了，账要减。
   *   <li>{@link #MISSHIP} 错发 —— 发错货造成的账实错位。要纠正的是错发的那笔，不是把差额抹平。
   *   <li>{@link #THEFT} 偷盗 —— 舞弊类，追责对象是人。数量口径与损耗一样，但留痕要求最高。
   *   <li>{@link #UNRECORDED} 未入账 —— <b>漏记流水</b>：货到了/发出去了，系统没记这一笔。 账错了，该补一笔流水。
   *   <li>{@link #TIMING} 时点差 —— 账面快照时点与实际不符：冻结之后仓库照常收发， 或有在途/未达账务。**账面数本身是对的**，错的是拿冻结值当今天的余额看。
   * </ul>
   *
   * <p>后两类必须分开：把「账错了」与「账没错」合成一类「其他差异」，等于把最需要回答的 那个问题（这条差异要不要动账）提前绕过去。
   */
  public enum StocktakeVarianceReason {
    SHRINKAGE("损耗", "实物损耗/破损/过期报废，货确实少了"),
    MISSHIP("错发", "发错货导致的账实错位，要纠正的是错发的那笔"),
    THEFT("偷盗", "舞弊类，追责对象是人，留痕要求最高"),
    UNRECORDED("未入账", "漏记流水：货到了或发出去了，系统没记这一笔，账错了"),
    TIMING("时点差", "冻结时点与实际不符，账面数本身是对的，账没错");

    private final String label;
    private final String description;

    StocktakeVarianceReason(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /**
   * 处理决定（三选，蓝图原文「调整/复盘/忽略」），三者的后果完全不同：
   *
   * <ul>
   *   <li>{@link #ADJUST} 调整 —— 生成调整单行 → 审批通过后才入账。**唯一会改余额的路径**。
   *   <li>{@link #RECOUNT} 复盘 —— 把该行实盘数退回未录（counted_* 三列一起清空）， 这一行重新变成「还没盘」，等人再去数一次。
   *   <li>{@link #IGNORE} 忽略 —— 不入账不复盘，但必须留痕（谁、何时、为什么）。 忽略过的行不允许再被归因（DB 级终态），否则「先忽略再偷偷改成调整」就是消音器。
   * </ul>
   */
  public enum StocktakeVarianceDecision {
    ADJUST("调整", "生成调整单，审批通过后盘盈盘亏入账"),
    RECOUNT("复盘", "把实盘数退回未录，这一行重新去数一次"),
    IGNORE("忽略", "不入账不复盘，但必须写明为什么放过（留痕终态）");

    private final String label;
    private final String description;

    StocktakeVarianceDecision(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** 调整单状态机：DRAFT → PENDING_APPROVAL → APPROVED → POSTED，另有 REJECTED / CANCELLED。 */
  public enum StocktakeVarianceAdjustmentStatus {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审批"),
    APPROVED("已批待入账"),
    REJECTED("已驳回"),
    POSTED("已入账"),
    CANCELLED("已作废");

    private final String label;

    StocktakeVarianceAdjustmentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 差异明细筛选：这是「我现在要处理哪一批差异」的五个互斥口径。
   *
   * <p>{@link #VARIANCE} 有差异（未归因与已归因都算），{@link #UNATTRIBUTED} 还没归因， {@link #IN_ADJUSTMENT}
   * 已归因为调整且还没过账，{@link #POSTED} 已入账， {@link #RECOUNTED} 归因为复盘。
   */
  public enum StocktakeVarianceLineFilter {
    ALL("全部差异"),
    UNATTRIBUTED("未归因"),
    IN_ADJUSTMENT("调整中"),
    POSTED("已入账"),
    RECOUNTED("已复盘");

    private final String label;

    StocktakeVarianceLineFilter(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 归因（五类原因 × 三种决定 + 说明）。
   *
   * <p>没有 {@code variance} 参数：调整量恒等于 7.4.2.1 算出来的差异， 人手填一个「调整量」等于允许调整量与差异量对不上，而那正是虚增/虚减的入口。
   */
  public record StocktakeVarianceAttributionInput(
      String reason, String decision, @Schema(nullable = true) String note) {}

  /**
   * 生成调整单。
   *
   * <p>{@code lineIds} 显式给行；{@code allAttributed=true} 则一次收走这张盘点单里 所有「已归因为调整、还没进任何单据」的行（批量调整，蓝图要求）。
   * 两者都为空时拒绝 —— 悄悄建一张零行单据会让台账上看起来像「处理过了」。
   */
  public record StocktakeVarianceAdjustCreateInput(
      @Schema(nullable = true) List<UUID> lineIds,
      @Schema(nullable = true) Boolean allAttributed,
      @Schema(nullable = true) String note) {}

  /** 提交 / 批准 / 驳回 / 作废 / 入账都共用它：一句人读得懂的话 + 当时的判断。 */
  public record StocktakeVarianceDecisionInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 差异明细

  /**
   * 一行待处理差异 = 7.4.2.1 的一行盘点明细 + 它当前的处置。
   *
   * @param bookOnHand 账面数（冻结值，永不随库存变动而改写）
   * @param countedQty 实盘数；null = 复盘后退回未录
   * @param variance 差异 = 实盘 − 账面；**只读**，不接受人工填写
   * @param reason 归因后的差异原因；未归因为 null
   * @param decision 归因后的处理决定；未归因为 null
   * @param adjustmentId 归因生成的调整单；未生成或非调整决定为 null
   * @param adjustmentStatus 那张调整单的状态
   * @param resolvedAt 处置结案时刻；未结为 null
   * @param postedAt 这条差异过账入账的时刻；未入账为 null
   */
  public record StocktakeVarianceLine(
      UUID id,
      UUID sheetId,
      UUID bucketId,
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      String warehouseCode,
      UUID locationId,
      String locationCode,
      @Schema(nullable = true) UUID lotId,
      String lotCode,
      String qualityStatusCode,
      BigDecimal bookOnHand,
      @Schema(nullable = true) BigDecimal countedQty,
      @Schema(nullable = true) BigDecimal variance,
      @Schema(nullable = true) OffsetDateTime countedAt,
      @Schema(nullable = true) UUID countedByUserId,
      @Schema(nullable = true) String varianceReason,
      @Schema(nullable = true) String varianceDecision,
      @Schema(nullable = true) String varianceNote,
      @Schema(nullable = true) UUID attributedByUserId,
      @Schema(nullable = true) OffsetDateTime attributedAt,
      @Schema(nullable = true) UUID adjustmentId,
      @Schema(nullable = true) String adjustmentStatus,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      @Schema(nullable = true) String resolvedBy,
      @Schema(nullable = true) OffsetDateTime postedAt) {}

  /** 差异明细分页（真分页，total 是该筛选下的全量行数）。 */
  public record StocktakeVarianceLinePage(
      List<StocktakeVarianceLine> lines, long total, int page, int size) {}

  // ------------------------------------------------------------ 调整单

  /**
   * 一张调整单的头部。
   *
   * <p>三个数量与行数**全部现算**，表里没有对应列：它们是明细行的函数， 另存一份就要在每次加行/过账后同步，而漏同步一次就会出现 「单据显示已入账但还差几行」这种说不清的错。
   *
   * @param status 状态机当前位置；POSTED 只能从 APPROVED 到达（DB 级约束）
   * @param gainQty 盘盈合计（正数）
   * @param lossQty 盘亏合计（正数，绝对值）
   * @param netQty 净调整量 = 盘盈 − 盘亏，带符号
   */
  public record StocktakeVarianceAdjustment(
      UUID id,
      UUID sheetId,
      String sheetCode,
      String code,
      StocktakeVarianceAdjustmentStatus status,
      long lineCount,
      long gainLineCount,
      long lossLineCount,
      BigDecimal gainQty,
      BigDecimal lossQty,
      BigDecimal netQty,
      String note,
      @Schema(nullable = true) UUID submittedByUserId,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID decidedByUserId,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) String decisionNote,
      @Schema(nullable = true) UUID postedByUserId,
      @Schema(nullable = true) OffsetDateTime postedAt,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /** 调整单一行 = 一条差异明细的一次性数量快照。 */
  public record StocktakeVarianceAdjustmentLine(
      UUID id,
      UUID lineId,
      UUID bucketId,
      UUID balanceId,
      String skuCode,
      String skuName,
      String warehouseCode,
      String locationCode,
      String lotCode,
      String qualityStatusCode,
      BigDecimal qtyDelta,
      StocktakeVarianceReason reason,
      String note,
      @Schema(nullable = true) UUID attributedByUserId,
      @Schema(nullable = true) OffsetDateTime attributedAt,
      @Schema(nullable = true) OffsetDateTime postedAt,
      @Schema(nullable = true) UUID entryId,
      @Schema(nullable = true) UUID transactionId) {}

  /** 调整单列表（真分页）。 */
  public record StocktakeVarianceAdjustmentPage(
      List<StocktakeVarianceAdjustment> adjustments, long total, int page, int size) {}

  /** 调整单详情 = 头部 + 全部行 + 这张单上的完整审计时间线。 */
  public record StocktakeVarianceAdjustmentDetail(
      StocktakeVarianceAdjustment adjustment,
      List<StocktakeVarianceAdjustmentLine> lines,
      List<StocktakeVarianceAuditEntry> auditTrail) {}

  /**
   * 入账结果。
   *
   * @param transactionId 写进 {@code inventory_transaction} 的业务单 id（ADJUSTMENT 类型）
   * @param entryCount 写进 {@code inventory_entry} 的分录条数
   * @param balanceIds 被改动的余额行（去重）——「动了哪几行账」必须能立刻答出来
   */
  public record StocktakeVariancePostResult(
      StocktakeVarianceAdjustment adjustment,
      UUID transactionId,
      int entryCount,
      List<UUID> balanceIds,
      BigDecimal postedQty,
      String note) {}

  // ------------------------------------------------------------ 报告与审计

  /**
   * 完成报告的指标。
   *
   * <p><b>分母口径写在字段注释里，也写在报告的 {@code rateBasis} 上</b>：报一个没有口径的 百分比等于报一个无法复核的数。
   *
   * @param lineVarianceRate 行差异率 = 有差异行数 ÷ 已盘行数
   * @param qtyVarianceRate 数量差异率 = (盘盈量 + 盘亏量) ÷ 账面合计（冻结值）
   * @param gainRate 盘盈率 = 盘盈量 ÷ 账面合计（冻结值）
   * @param lossRate 盘亏率 = 盘亏量 ÷ 账面合计（冻结值）
   * @param attributionProgress 归因完成度 = 已归因差异行数 ÷ 差异行数
   * @param 各率在**分母为 0 时为 null**，不是 0 —— 0 会被读成「差异率 0%」，那是谎报
   */
  public record StocktakeVarianceMetrics(
      long lineCount,
      long countedLineCount,
      long pendingLineCount,
      long varianceLineCount,
      long matchLineCount,
      BigDecimal bookTotal,
      BigDecimal countedTotal,
      BigDecimal gainQty,
      BigDecimal lossQty,
      BigDecimal netVarianceQty,
      @Schema(nullable = true) BigDecimal lineVarianceRate,
      @Schema(nullable = true) BigDecimal qtyVarianceRate,
      @Schema(nullable = true) BigDecimal gainRate,
      @Schema(nullable = true) BigDecimal lossRate,
      long attributedLineCount,
      long openLineCount,
      @Schema(nullable = true) BigDecimal attributionProgress,
      long pendingAdjustmentCount,
      long postedLineCount) {}

  /**
   * 按原因分组。**五种原因恒定各占一行**，没有差异的那一类计数为 0。
   *
   * <p>理由与调整单列表同一条：漏掉的类目会被读成「没有这种差异」，而 「盘完了没有一条错发」与「盘完了错发还没归因」在报告上是完全不同的两件事。
   *
   * <p>口径：只统计**仍有差异**（variance 非空）的行。复盘后的行 variance 归 NULL， 它已经回到「还没盘」，不算待处理差异。复盘与忽略的次数在 {@link
   * StocktakeVarianceDecisionBreakdown} 里 —— 那张表统计的是「做过的决定」， 口径不同，两张表不混。
   */
  public record StocktakeVarianceReasonBreakdown(
      StocktakeVarianceReason reason,
      String label,
      long lineCount,
      long gainLineCount,
      long lossLineCount,
      BigDecimal gainQty,
      BigDecimal lossQty,
      BigDecimal netQty,
      long inAdjustmentCount,
      long postedCount) {}

  /** 按处理决定分组（三种决定恒定各占一行）。 */
  public record StocktakeVarianceDecisionBreakdown(
      StocktakeVarianceDecision decision, String label, long lineCount, long openLineCount) {}

  /**
   * 盘点完成报告。
   *
   * @param rateBasis 各率的分母口径原文，界面上要能展示给用户看
   * @param amountBasisAvailable 金额口径指标是否可算。**当前恒为 false**：全库 （warehouse 全部迁移 +
   *     catalog）不存在任何库存成本列，catalog 侧只有供应商报价 分层（跨服务、且不是库存成本），所以差异率/盘盈率只能按**数量**出。编一个金额数 比不给出金额数危险得多
   * @param amountBasisNote 如实说明金额口径为什么算不出来
   * @param approvalNote 如实说明审批用的是替代机制（未接入 17.4）
   */
  public record StocktakeVarianceReport(
      UUID sheetId,
      String sheetCode,
      String scopeType,
      String scopeLabel,
      OffsetDateTime frozenAt,
      String sheetStatus,
      StocktakeVarianceMetrics metrics,
      List<StocktakeVarianceReasonBreakdown> byReason,
      List<StocktakeVarianceDecisionBreakdown> byDecision,
      String rateBasis,
      boolean amountBasisAvailable,
      String amountBasisNote,
      String approvalNote,
      String adjustmentHandoffNote,
      String note) {}

  /** 审计时间线上的一条：谁在什么时候把哪条差异判成了什么。 */
  public record StocktakeVarianceAuditEntry(
      UUID id,
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) UUID adjustmentId,
      String action,
      String actionLabel,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  // ------------------------------------------------------------ 能力与选项

  /** 一个可选项（五类原因 / 三种决定共用一个形状）。 */
  public record VarianceHandlingOption(
      String value,
      String label,
      String description,
      boolean noteRequired,
      boolean changesBalance) {}

  /**
   * 差异处理能力说明。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面里 —— 规则改了就该只改一处。
   */
  public record StocktakeVarianceCapabilities(
      List<VarianceHandlingOption> reasons,
      List<VarianceHandlingOption> decisions,
      List<String> lineFilters,
      boolean amountBasisAvailable,
      String amountBasisNote,
      String approvalNote,
      String adjustmentHandoffNote) {}
}
