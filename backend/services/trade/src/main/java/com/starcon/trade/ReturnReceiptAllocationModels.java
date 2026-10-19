package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.2.2 实收分配的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：把实收退货分配到对应的退货授权行与原订单行，处理实收多于授权（<b>按实际退回</b>）、少收、超期退回等情况。
 * 输入：实收记录；授权行可收量；分配操作。
 * 输出：分配记录 + <b>超收/少收说明</b>；<b>订单行已退数量更新</b>。
 * 关系：上游 = 11.2.2.1；下游 = 9 订单（已退数量）、11.2.3.3。<b>按收货事件去重分配</b>，同一批退货不重复记已收数量。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>按收货事件去重</b>（蓝图加粗句）：{@link ReturnAllocationCreate} <b>没有</b>任何
 *       「要不要重复分配」的开关 —— 传参里出现一个 {@code force} 或 {@code allowDuplicate}
 *       就等于在问「这次要不要把已退数量再加一次」，而那一次就是资损。
 *       幂等键在传输层是<b>派生</b>的（恒为 {@code RECEIPT: + receiptId}），不接受调用方给定。</li>
 *   <li><b>超收按实际退回，不静默截断</b>：{@link ReturnAllocationLine#allocatedQty()} 是
 *       <b>实收数</b>而不是 min(实收, 授权)，差额落在 {@code overReceivedQty} /
 *       {@code shortReceivedQty} / {@code varianceAmount} 三列上，
 *       并且一定伴随一条 {@link ReturnAllocationVariance} 说明。</li>
 *   <li><b>数量与金额分列</b>：{@code varianceQty} 回答「多退/少退几件」，
 *       {@code varianceAmount} 回答「多退/少退多少钱」。财务侧只认后者。
 *       两者都是 {@code BigDecimal} + {@code NUMERIC}，<b>禁浮点</b>。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>：11.2.3.3 财务对账、
 *       9 订单已退数量回写<b>均未交付</b>，<b>不建影子表、不编状态</b>。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ReturnAllocation*} / {@code ReturnVariance*} 前缀，
 * 全仓 grep 确认与既有类型零重叠 —— 契约被 codegen 合并进一个扁平文件、
 * 同名后者覆盖前者且不报冲突。
 */
public final class ReturnReceiptAllocationModels {

  private ReturnReceiptAllocationModels() {}

  // ================================================================ 枚举

  /**
   * 分配单状态机：一个状态列到底，转换规则由 DB 触发器
   * {@code guard_return_allocation_frozen} 显式表达。
   *
   * <p>ALLOCATED → RECONCILED（超收/少收/超期已结转）
   * <br>ALLOCATED → REVERSED（分配被撤回，已退数量回冲）
   * <br>RECONCILED / REVERSED 均为<b>终态</b>，不可再转（含不可转回 ALLOCATED）。
   *
   * <p>⚠️ <b>没有 PARTIAL / PENDING</b>：分配是一次性动作（一个收货事件一张分配单），
   * 不存在「分一半」的中间态 —— 实收行的数量要么全部落到订单行上，要么整张撤销。
   * 编一个 PARTIAL 会让「这批货退了一半」成为一个没有金额口径的状态。
   */
  public enum ReturnAllocationStatus {
    ALLOCATED("已分配，订单行已退数量已更新"),
    RECONCILED("已对账，超收/少收/超期已结转"),
    REVERSED("已撤销，已退数量已回冲");

    private final String label;

    ReturnAllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 终态：不能再改（DB 触发器同一条纪律）。 */
    public boolean terminal() {
      return this == RECONCILED || this == REVERSED;
    }

    /** 还可流转（只有 ALLOCATED）。 */
    public boolean open() {
      return this == ALLOCATED;
    }
  }

  /**
   * 差额类型：超收 / 少收 / 超期退回 / 撤销。
   *
   * <p>⚠️ {@code OVER_RECEIVED} 与 {@code UNDER_RECEIVED} <b>刻意分开</b>：
   * 合成一个 {@code VARIANCE} 会让「授权 3 实收 5」与「授权 3 实收 1」
   * 在库里长成同一个形状，而这两件事的处理动作完全相反（一个要退钱、一个要催件）。
   */
  public enum ReturnVarianceType {
    OVER_RECEIVED("超收：实收多于授权，按实际退回记账"),
    UNDER_RECEIVED("少收：实收少于授权，差额已结转"),
    OVERDUE_RETURNED("超期退回：收货时刻晚于授权的寄回时限"),
    REVERSED("撤销：分配被撤回");

    private final String label;

    ReturnVarianceType(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }

  /**
   * 差额的处置结论。
   *
   * <p>⚠️ <b>刻意没有「已解决」</b>：差异需要人工核实是真的 ——
   * 超收多退的钱要追回还是认了，是财务决定而不是本模块能写下的结论。
   * 编一个 {@code RESOLVED} 会让财务侧以为这笔钱已经结清了。
   */
  public enum ReturnVarianceResolution {
    PENDING_REVIEW("待人工核实"),
    ACCEPTED_AS_ACTUAL("按实际退回认可");

    private final String label;

    ReturnVarianceResolution(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 「认可」必须写清依据（表级 CHECK）。 */
    public boolean needsNote() {
      return this == ACCEPTED_AS_ACTUAL;
    }
  }

  /**
   * 排序白名单。
   *
   * <p>排序键会进 SQL 文本，放任调用方传表达式就是一条注入。
   */
  public enum ReturnAllocationSort {
    ALLOCATED_DESC("a.allocated_at desc, a.id desc"),
    RECEIVED_ASC("a.received_at asc nulls last, a.id desc"),
    STATUS_ASC("a.allocation_status asc, a.allocated_at desc"),
    OVERDUE_FIRST("a.overdue_returned desc, a.received_at asc, a.id desc");

    private final String sql;

    ReturnAllocationSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return this.sql;
    }

    public static ReturnAllocationSort parse(String raw) {
      if (raw == null) {
        return ALLOCATED_DESC;
      }
      return switch (raw) {
        case "allocated_desc" -> ALLOCATED_DESC;
        case "received_asc" -> RECEIVED_ASC;
        case "status_asc" -> STATUS_ASC;
        case "overdue_first" -> OVERDUE_FIRST;
        default -> ALLOCATED_DESC;
      };
    }
  }

  // ================================================================ 入参

  /**
   * 按收货事件分配。
   *
   * <p>⚠️ <b>没有 force / allowDuplicate 之类的开关</b>：蓝图加粗句「按收货事件去重分配，
   * 同一批退货不重复记已收数量」。传参里出现一个「这次要不要重复记」的开关，
   * 就等于把一次资损做成了一次点击。重复提交同一收货事件时**返回同一张分配单**。
   */
  public record ReturnAllocationCreate(
      UUID receiptId, List<ReturnAllocationLineDraft> lines) {}

  /**
   * 一条分配行：这一行货分到哪个授权行 + 哪条原订单行、分多少。
   *
   * <p>⚠️ {@code targetRmaLineId} 与 {@code orderLineId} <b>可省略</b>：
   * 省略时真值实时读 {@code rma_authorization_line → aftersales_case_line → sales_order_line}，
   * 而<b>跨行分配</b>（V172 第四节把「合并/拆分」划给本模块）时显式指定。
   *
   * <p>⚠️ {@code allocatedQty} 是<b>实收数</b>，允许大于目标授权行的剩余可收量 ——
   * 那正是「实收多于授权（按实际退回）」的入口，<b>不截断</b>。
   */
  public record ReturnAllocationLineDraft(
      UUID receiptLineId,
      @Schema(nullable = true) UUID targetRmaLineId,
      @Schema(nullable = true) UUID orderLineId,
      BigDecimal allocatedQty) {}

  /**
   * 对账：把超收 / 少收 / 超期退回三种情形结转成说明。
   *
   * <p>⚠️ 三种情形<b>各自独立</b>给出处置结论与说明正文 ——
   * 合成一个 {@code note} 会让「超收按实际退回认可了，但少收还没核实」显示成一团。
   */
  public record ReturnAllocationReconcile(
      @Schema(nullable = true) Boolean acceptOverReceived,
      @Schema(nullable = true) String overReceivedNote,
      @Schema(nullable = true) Boolean acceptUnderReceived,
      @Schema(nullable = true) String underReceivedNote,
      @Schema(nullable = true) Boolean acceptOverdue,
      @Schema(nullable = true) String overdueNote,
      String reconcileNote,
      Long expectedVersion) {}

  /** 撤销分配（跨行分配选错商品行 / 隔壁包裹串了台）。必须写理由。 */
  public record ReturnAllocationReverse(String reverseReason, Long expectedVersion) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record ReturnAllocationListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID receiptId,
      @Schema(nullable = true) UUID rmaId,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) Boolean varianceOnly,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 分配单头。 */
  public record ReturnAllocationHead(
      UUID id,
      String allocationNo,
      /** 恒为 {@code RECEIPT: + receiptId}：幂等键是派生的，不接受调用方给定。 */
      String idempotencyKey,
      UUID receiptId,
      String receiptNo,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime postedAt,
      UUID rmaId,
      String rmaNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      /** 寄回时限快照：超期退回的判定锚点。 */
      @Schema(nullable = true) OffsetDateTime returnByAt,
      boolean overdueReturned,
      int daysOverdue,
      String allocationStatus,
      String allocationStatusLabel,
      BigDecimal allocatedQty,
      BigDecimal overReceivedQty,
      BigDecimal shortReceivedQty,
      /** 金额差（NUMERIC(19,4)，禁浮点记账）。 */
      BigDecimal varianceAmount,
      int lineCount,
      int varianceCount,
      UUID allocatedBy,
      OffsetDateTime allocatedAt,
      @Schema(nullable = true) UUID reconciledBy,
      @Schema(nullable = true) OffsetDateTime reconciledAt,
      @Schema(nullable = true) UUID reversedBy,
      @Schema(nullable = true) OffsetDateTime reversedAt,
      @Schema(nullable = true) String reverseReason,
      @Schema(nullable = true) String reconcileNote,
      long version) {}

  /**
   * 分配行：实收行 →（目标授权行 + 原订单行）的一次落到账。
   *
   * <p>⚠️ {@code allocatedQty} 是<b>实收数</b>：它可以大于
   * {@code targetAuthorizedQty − targetTakenQty}，差额落在
   * {@code overReceivedQty} 上（硬规则②「按实际退回、不静默截断」）。
   */
  public record ReturnAllocationLine(
      UUID id,
      UUID receiptLineId,
      UUID sourceRmaLineId,
      UUID targetRmaLineId,
      UUID caseLineId,
      UUID orderLineId,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal allocatedQty,
      /** 分配时刻的目标授权量快照（事后授权行会变，这里是「当时」的口径）。 */
      BigDecimal targetAuthorizedQty,
      /** 已被**其它**收货事件占用的量（同一收货事件内的行彼此不占用）。 */
      BigDecimal targetTakenQty,
      BigDecimal remainingBeforeAlloc,
      BigDecimal overReceivedQty,
      BigDecimal shortReceivedQty,
      BigDecimal varianceAmount,
      boolean overdueReturned,
      @Schema(nullable = true) String varianceNote,
      UUID allocatedBy,
      OffsetDateTime allocatedAt) {}

  /**
   * 超收 / 少收 / 超期退回说明（追加式，运行角色 UPDATE/DELETE 已收回）。
   *
   * <p>⚠️ {@code explanation} <b>必须有正文</b>（表级 CHECK）——
   * 一条没有正文的差额说明在纠纷与对账里都答不出「为什么有这笔钱」。
   */
  public record ReturnAllocationVariance(
      UUID id,
      @Schema(nullable = true) UUID allocationLineId,
      String varianceType,
      String varianceTypeLabel,
      BigDecimal varianceQty,
      /** 金额差（NUMERIC(19,4)，禁浮点记账）：正 = 超收要退钱，负 = 少收要补钱。 */
      BigDecimal varianceAmount,
      @Schema(nullable = true) String currency,
      String explanation,
      String resolution,
      String resolutionLabel,
      @Schema(nullable = true) String resolutionNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 分配留痕（追加式，运行角色 UPDATE/DELETE 已收回）。 */
  public record ReturnAllocationEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /**
   * 订单行已退数量（蓝图输出句「订单行已退数量更新」的唯一落点）。
   *
   * <p>⚠️ 本表是 11.2.2.2 的<b>写入所有权</b>，运行角色的 UPDATE/DELETE 已在 DB 层收回 ——
   * 服务层**没有任何**直接写 {@code returnedQty} 的代码路径，它只由 DB 触发器
   * {@code trg_return_allocation_order_qty} 改。
   */
  public record OrderLineReturnedQty(
      UUID orderLineId,
      UUID orderId,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) BigDecimal orderedQty,
      BigDecimal returnedQty,
      BigDecimal allocatedQty,
      /** 已分配但不在任何有效分配单上的量（= allocatedQty − returnedQty）。 */
      BigDecimal unreconciledQty,
      /** 退得比订购还多（= max(returnedQty − orderedQty, 0)）：最严重的一类超收。 */
      BigDecimal overOrderedQty,
      boolean exceedsOrderedQty,
      @Schema(nullable = true) UUID lastAllocationId,
      @Schema(nullable = true) OffsetDateTime lastAllocatedAt,
      int allocationCount,
      long version) {}

  /** 某张订单的已退数量（下游 9 订单的取数面）。 */
  public record OrderReturnedQtyPage(
      UUID orderId,
      List<OrderLineReturnedQty> lines,
      BigDecimal orderedQty,
      BigDecimal returnedQty,
      BigDecimal allocatedQty,
      int overOrderedLineCount,
      OffsetDateTime generatedAt,
      String note) {}

  /** 列表行。 */
  public record ReturnAllocationRow(
      UUID id,
      String allocationNo,
      UUID receiptId,
      String receiptNo,
      UUID rmaId,
      String rmaNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      boolean overdueReturned,
      int daysOverdue,
      String allocationStatus,
      String allocationStatusLabel,
      BigDecimal allocatedQty,
      BigDecimal overReceivedQty,
      BigDecimal shortReceivedQty,
      BigDecimal varianceAmount,
      int lineCount,
      int varianceCount,
      long version) {}

  /** 单张分配全景。 */
  public record ReturnAllocationDetail(
      ReturnAllocationHead head,
      List<ReturnAllocationLine> lines,
      List<ReturnAllocationVariance> variances,
      List<OrderLineReturnedQty> orderLineReturns,
      List<ReturnAllocationEvent> events,
      BigDecimal allocatedQty,
      BigDecimal overReceivedQty,
      BigDecimal shortReceivedQty,
      BigDecimal varianceAmount,
      boolean overdueReturned,
      int daysOverdue,
      @Schema(nullable = true) String hardRuleNote,
      List<ReturnAllocationAction> actions,
      List<ReturnAllocationRelatedModule> relatedModules,
      OffsetDateTime generatedAt,
      String note) {}

  /** 列表页汇总（真分页之外的全量口径）。 */
  public record ReturnAllocationSummary(
      long total,
      long allocated,
      long reconciled,
      long reversed,
      long overdue,
      long withOverReceived,
      long withUnderReceived,
      BigDecimal allocatedQty,
      BigDecimal overReceivedQty,
      BigDecimal shortReceivedQty,
      BigDecimal varianceAmount,
      /** 已分配但不在任何有效分配单上的量（= allocatedQty − returnedQty）。 */
      BigDecimal unreconciledQty) {}

  /** 列表页。 */
  public record ReturnAllocationPage(
      List<ReturnAllocationRow> rows,
      int page,
      int size,
      long total,
      ReturnAllocationSummary summary,
      OffsetDateTime generatedAt,
      String paginationNote,
      String hardRuleNote,
      String sortNote) {}

  // ================================================================ 能力声明

  /** 能力区块（职责句的每一块 + 未交付的下游）。 */
  public record ReturnAllocationSectionCapability(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String landsIn) {}

  /** 上下游模块。 */
  public record ReturnAllocationRelatedModule(
      String id,
      String label,
      boolean delivered,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String href) {}

  /** 当前可执行动作。 */
  public record ReturnAllocationAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason) {}

  /** 刻意不做的事。 */
  public record ReturnAllocationBoundary(
      String boundary, @Schema(nullable = true) String ownerModule, String reason) {}

  /** 能力声明。 */
  public record ReturnAllocationCapabilities(
      List<String> dataSources,
      List<ReturnAllocationSectionCapability> sections,
      List<ReturnAllocationRelatedModule> relatedModules,
      List<ReturnAllocationAction> actions,
      List<ReturnAllocationBoundary> boundaries,
      String stateMachine,
      String dedupRule,
      String overReceiveRule,
      String overdueRule,
      String returnedQtyRule) {}
}
