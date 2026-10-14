package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 7.2.1.3 释放与过期的传输模型。
 *
 * <p>蓝图：「处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与批量过期清理，
 * 释放时同步恢复可用量。」输入「释放请求（业务事件或人工）；过期时间规则；批量清理任务」，
 * 输出「预占终态记录 + 余额恢复分录」。
 *
 * <p><b>命名纪律</b>：本类每一个 record / enum 都以 {@code ReservationRelease} 起头。
 * springdoc 用 record 的**简单类名**做 {@code components.schemas} 的键，同名而不同形状的 record
 * 会让先注册的那份被<em>静默覆盖</em> —— 别人的字段形状被改掉，而后端测试全绿、只有前端 typecheck 会红。
 * 已被占用的通用名（{@code StatusOption} / {@code SourceOption} / {@code Row} / {@code Page} /
 * {@code Summary} / {@code Occupancy} / {@code LocationRow} / {@code LocationTotals} / {@code Turnover} …）
 * 本类一个都不复用。
 *
 * <p>表与服务归属：{@code reservation_release_record} 与 {@code reservation_expiry_run} 落 warehouse
 * （V112 文件头第零节），不跨服务读写 trade 的表。可用量口径由 7.1.1.3 裁决，本模块**不重复实现**。
 */
public final class ReservationReleaseModels {

  private ReservationReleaseModels() {}

  // ------------------------------------------------------------ 词表

  /**
   * 释放方式（受控词表，与 V112 的 CHECK 逐字一致）。
   *
   * <p>刻意分成 RELEASE 与 EXPIRE 而不是只留一个「结束」标志：事后要能回答「这笔是被取消了，
   * 还是等太久了」。这两个数在运营上要分开看 —— 前者是业务动作，后者是流程失败。
   */
  public enum ReservationReleaseMode {
    /** 显式释放：订单取消 / 支付超时 / 拆单减少 / 人工。 */
    RELEASE("释放"),
    /** 超时回收：批量过期清理判定 HELD 且已过 expires_at。 */
    EXPIRE("超时回收");

    private final String label;

    ReservationReleaseMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 释放来源（受控词表，与 V112 的 CHECK 逐字一致）。
   *
   * <p>蓝图点名三类：订单取消、支付超时、拆单减少。三者都是**业务事件**（上游 trade 回传），
   * 补上 {@link #MANUAL} 人工入口与 {@link #EXPIRY_SWEEP} 本模块自己的批量回收。
   */
  public enum ReservationReleaseTriggerSource {
    /** 09 订单取消事件回传。 */
    ORDER_CANCELLED("订单取消"),
    /** 支付超时未完成。 */
    PAYMENT_TIMEOUT("支付超时"),
    /** 拆单后需求减少。 */
    SPLIT_REDUCTION("拆单减少"),
    /** 运营人工释放。 */
    MANUAL("人工释放"),
    /** 批量过期清理（由本模块写入，不可用于手动入口）。 */
    EXPIRY_SWEEP("超时回收");

    private final String label;

    ReservationReleaseTriggerSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否只能由批量过期清理产生。手动入口用它挡住「把超时回收写成人工释放」。 */
    public boolean sweepOnly() {
      return this == EXPIRY_SWEEP;
    }
  }

  // ------------------------------------------------------------ 候选（过期规则）

  /**
   * 过期候选查询条件。
   *
   * @param sourceType 来源类型过滤；null = 全部
   * @param keyword 关键字：匹配预占单号 / 上游单号 / SKU 编码 / SKU 名称 / 备注
   * @param sort 排序键（白名单）：EXPIRES_AT / OVERDUE / RESTORABLE / RESERVATION_NO
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   * @param evaluatedAt 规则判定时刻；null = 服务端当前时刻。显式传入可做「按某时刻预演」，
   *     调度器重跑同一批作业时也必须用同一个时刻，否则候选集不可复现
   */
  public record ReservationReleaseQuery(
      @Schema(nullable = true) String sourceType,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) OffsetDateTime evaluatedAt) {}

  /**
   * 一条分配的可释放明细（拆单减少时按这一行选量）。
   *
   * @param remainingQuantity 该分配现在还占着多少（= 分配量 − 已消耗 − 已释放，DB 生成列）。
   *     这就是「还回去就等于释放多少」的上限
   */
  public record ReservationReleaseAllocationLine(
      UUID allocationId,
      String status,
      @Schema(nullable = true) String statusLabel,
      UUID warehouseId,
      String warehouseCode,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal remainingQuantity,
      OffsetDateTime allocatedAt) {}

  /**
   * 一条过期候选行。
   *
   * @param restorableQuantity 这笔预占全部释放时可还回可用池的量（各分配剩余量之和）。
   *     与「需求量 − 已消耗」**不是同一个数**：没圈到 bucket 的部分从没占过可用量
   * @param overdueSeconds 已超时多久（秒）。**恒从 expires_at 算**，不给「没超时」的 0 ——
   *     0 会把「刚刚到期 0 秒」与「不参与超时」混成同一个数
   * @param allocations 该预占下仍在占额的分配明细（供按分配选量）
   */
  public record ReservationReleaseCandidateRow(
      UUID reservationId,
      String reservationNo,
      String sourceSystem,
      @Schema(nullable = true) String sourceSystemLabel,
      String sourceType,
      @Schema(nullable = true) String sourceTypeLabel,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) String referenceCode,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      BigDecimal requestedQuantity,
      BigDecimal allocatedQuantity,
      BigDecimal consumedQuantity,
      BigDecimal releasedQuantity,
      BigDecimal restorableQuantity,
      String status,
      @Schema(nullable = true) String statusLabel,
      OffsetDateTime heldAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      long overdueSeconds,
      List<ReservationReleaseAllocationLine> allocations,
      @Schema(nullable = true) String note) {}

  /**
   * 候选汇总。
   *
   * <p><b>汇总不受分页与筛选影响</b>，与 7.2.1.1 / 7.2.1.2 同一条纪律。
   *
   * @param overdueCount 已过 expires_at 且仍在 HELD 的条数（真正会被清理的那些）
   * @param overdueRestorableQuantity 上述候选可还回的量合计
   * @param unmanagedHeldCount 仍在 HELD 但**没有** expires_at 的条数 ——
   *     它们永远不会自动过期。单独报出来，是因为「登记预占时忘了传过期时刻」这件事
   *     只能靠这个数被发现，而它在列表页上看起来和正常的预占一模一样
   * @param committedCount 已承诺（COMMITTED）且**永不自动过期**的条数 ——
   *     蓝图 7.2.1.3 的红线就在这里，把它显示出来是为了让这条规则可见而不是靠文档
   * @param mixedCommitmentSkippedCount 已超时、但因名下仍有 COMMITTED 分配而**不会被自动回收**的条数。
   *     它们在列表上与普通候选长得一模一样，不报出来的话运营永远发现不了「这批货为什么一直占着」
   * @param terminalReleasedCount / terminalExpiredCount 已被释放 / 已过期的终态条数
   */
  public record ReservationReleaseSummary(
      long totalHeldCount,
      long overdueCount,
      BigDecimal overdueRestorableQuantity,
      long unmanagedHeldCount,
      long committedCount,
      long mixedCommitmentSkippedCount,
      long terminalReleasedCount,
      long terminalExpiredCount,
      BigDecimal releasedQuantityTotal,
      Map<String, Long> modeCounts) {}

  /**
   * 过期候选页。
   *
   * @param evaluatedAt 本次判定时刻（**逐字返回**：判定时刻是这份清单的一部分，
   *     页面上必须显示「按这个时刻算的」，否则用户会把列表当成实时状态）
   * @param ruleNote 规则说明：判据、COMMITTED 不参与、dry_run 语义
   */
  public record ReservationReleaseCandidatePage(
      ReservationReleaseSummary summary,
      List<ReservationReleaseCandidateRow> rows,
      long total,
      int page,
      int size,
      OffsetDateTime evaluatedAt,
      String ruleNote,
      String note) {}

  // ------------------------------------------------------------ 手动释放

  /** 指定一条分配上的释放量（可空 = 该分配全量）。 */
  public record ReservationReleaseAllocationInput(
      @jakarta.validation.constraints.NotNull UUID allocationId,
      @Schema(nullable = true) BigDecimal quantity) {}

  /**
   * 手动释放入参。
   *
   * <p>三个数量参数的组合规则（服务层裁决，这里写清以便调用方不试错）：
   *
   * <ul>
   *   <li>{@code allocations} 为空 + {@code quantity} 为空 → 释放全部仍在占额的分配
   *   <li>{@code allocations} 为空 + {@code quantity} 有值 → 按圈定时间先后（FIFO）释放到该量为止，
   *       对应蓝图的「拆单减少」
   *   <li>{@code allocations} 非空 → 只作用于列出的分配，逐条按各自的 {@code quantity}（空 = 全量）
   * </ul>
   *
   * @param triggerSource 释放来源（受控词表）。**不接受 EXPIRY_SWEEP** —— 那是批量清理的来源，
   *     人工把它写进凭据会让「等太久」与「被取消」这两个数分不开
   * @param occurredAt 释放发生时刻；不传则取服务端当前时刻。跨服务回传**必须显式传**：
   *     订单取消发生在上游，用服务端当前时间会把「什么时候取消的」换成「本服务什么时候知道的」
   * @param idempotencyKey 幂等键（≤64）。同键同参 → 回放既有凭据；同键不同参 → 409。
   *     上游事件重投（at-least-once）必须带键，否则重放会与第一次的意图混淆
   * @param finalizeReservation 部分释放时是否同时终结整笔预占；不传 = false。
   *     仍有未终结分配时强制为 false —— 预占还占着额就不能说它结束了
   */
  public record ReservationReleaseInput(
      @Schema(nullable = true) List<@jakarta.validation.Valid ReservationReleaseAllocationInput>
          allocations,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 24)
          String triggerSource,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 200)
          String reason,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 64)
          String idempotencyKey,
      @Schema(nullable = true) Boolean finalizeReservation) {}

  /**
   * 手动释放结果。
   *
   * @param restoredQuantity 真正还回可用池的量（各分配本次释放量之和）
   * @param releasedDemandQuantity 预占口径的已释放量。整笔终结时 = 需求量 − 已消费量，
   *     所以它通常**大于等于** restoredQuantity：没圈到 bucket 的部分同样是不再需要的需求，
   *     但它从没占过可用量。两个数分开报，是因为合并会让可用量的恢复量凭空多出来
   * @param replayed true = 这次调用命中了幂等键或预占已是同种终态，**没有产生新的释放**。
   *     重放不是错误：上游事件至少投递一次，第二次到达必须成功而不是把货再还一次
   * @param entryId append-only 流水中对应那一条的 id；部分释放时为最后写入的那一条
   */
  public record ReservationReleaseOutcome(
      UUID reservationId,
      String reservationNo,
      String mode,
      @Schema(nullable = true) String modeLabel,
      String triggerSource,
      @Schema(nullable = true) String triggerSourceLabel,
      boolean terminal,
      String statusBefore,
      String statusAfter,
      @Schema(nullable = true) String statusAfterLabel,
      BigDecimal restoredQuantity,
      BigDecimal releasedDemandQuantity,
      int allocationCount,
      UUID releaseRecordId,
      @Schema(nullable = true) UUID entryId,
      boolean replayed,
      String message) {}

  // ------------------------------------------------------------ 批量过期清理

  /**
   * 批量过期清理入参。
   *
   * @param dryRun 只判定不落库。干跑**只写一条运行凭据**，不碰预占 / 分配 / 流水
   * @param evaluatedAt 规则判定时刻；不传则取服务端当前时刻。
   *     调度器重跑同一批作业必须用同一个时刻（配幂等键），否则「同一批」两次算出来不一样
   * @param limitCount 单批上限，服务端还有一层硬上限（1000，与 V112 的 CHECK 同口径）
   */
  public record ReservationReleaseRunRequest(
      @Schema(nullable = true) Boolean dryRun,
      @Schema(nullable = true) OffsetDateTime evaluatedAt,
      @Schema(nullable = true) Integer limitCount,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 200)
          String reason,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 64)
          String idempotencyKey) {}

  /** 运行凭据行（列表与详情共用同一形状，避免两页显示不同的数）。 */
  public record ReservationReleaseRunRow(
      UUID id,
      boolean dryRun,
      OffsetDateTime evaluatedAt,
      int limitCount,
      long candidateCount,
      long processedCount,
      long skippedCount,
      long releaseCount,
      BigDecimal restoredQuantity,
      BigDecimal releasedDemandQuantity,
      String status,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String idempotencyKey,
      OffsetDateTime createdAt,
      OffsetDateTime completedAt,
      boolean replayed) {}

  /** 运行凭据分页。 */
  public record ReservationReleaseRunPage(
      List<ReservationReleaseRunRow> rows, long total, int page, int size, String note) {}

  /** 释放凭据分页。刻意与运行凭据分页分开：两者的行形状不同，共用一个泛化分页 record 只会把可选字段变成可空。 */
  public record ReservationReleaseRecordPage(
      List<ReservationReleaseRecordRow> rows, long total, int page, int size, String note) {}

  /** 单条释放凭据。 */
  public record ReservationReleaseRecordRow(
      UUID id,
      UUID reservationId,
      @Schema(nullable = true) String reservationNo,
      String mode,
      @Schema(nullable = true) String modeLabel,
      String triggerSource,
      @Schema(nullable = true) String triggerSourceLabel,
      boolean terminal,
      String statusBefore,
      String statusAfter,
      @Schema(nullable = true) String statusAfterLabel,
      int allocationCount,
      BigDecimal restoredQuantity,
      BigDecimal releasedDemandQuantity,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) UUID expiryRunId,
      @Schema(nullable = true) String idempotencyKey,
      OffsetDateTime createdAt) {}

  /** 运行详情 = 运行凭据 + 它产生的释放凭据。 */
  public record ReservationReleaseRunDetail(
      ReservationReleaseRunRow run,
      List<ReservationReleaseRecordRow> releases,
      long releaseTotal,
      String note) {}

  // ------------------------------------------------------------ 过期时刻

  /**
   * 补写 / 调整过期时刻。
   *
   * <p>7.2.1.1 的登记入参注释逐字写着「不传则该预占不参与超时筛选（可稍后由 7.2.1.3 补）」——
   * 补的就是这个时刻。传空值表示「取消过期时刻」，该预占从此不参与批量清理。
   *
   * @param expiresAt 新的过期时刻；不传表示不改
   * @param clear 传 true 表示清空过期时刻（该预占不再参与自动回收）
   */
  public record ReservationReleaseExpiryInput(
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) Boolean clear,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 200)
          String reason) {}

  /**
   * 补写结果。
   *
   * @param expiresAt 写回库里的真实值（逐字可比，不是「没抛异常」）。
   *     清空时为 null —— 「取消过期时刻」与「设了一个未来时刻」是两件事
   * @param overdueSeconds 按新时刻算出的超时秒数；未过期或已清空时为 null
   */
  public record ReservationReleaseExpiryOutcome(
      UUID reservationId,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      boolean updated,
      @Schema(nullable = true) Long overdueSeconds,
      String message) {}

  // ------------------------------------------------------------ 下拉项

  /**
   * 筛选与录入用的下拉项。
   *
   * @param modes 两种释放方式
   * @param triggers 五个释放来源（EXPIRY_SWEEP 标注了仅批量可用）
   * @param sorts 四个排序键
   * @param ruleNote 过期规则说明：判据、COMMITTED 红线、committed 不参与
   */
  public record ReservationReleaseOptions(
      List<ReservationReleaseOption> modes,
      List<ReservationReleaseOption> triggers,
      List<ReservationReleaseOption> sorts,
      String ruleNote,
      String timezoneNote,
      String ownerNote) {}

  /**
   * 一个下拉项。
   *
   * @param code 取值
   * @param label 中文标签
   * @param sweepOnly 是否仅批量过期清理可用
   * @param description 补充说明
   */
  public record ReservationReleaseOption(
      String code, String label, boolean sweepOnly, @Schema(nullable = true) String description) {}
}