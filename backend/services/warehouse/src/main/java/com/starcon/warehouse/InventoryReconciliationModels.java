package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.2.2 账实核对的传输模型。
 *
 * <p>蓝图原话：「定期核对『按流水累计的余额』与『当前余额快照』是否一致，差异时列出可疑区间
 * （漏记、重复记、并发丢失）」「输出：核对结果 + 差异明细 + 可疑流水定位」。
 *
 * <p><b>核对公式（整个模块只有这一套口径，别处再写一遍就会算错）</b>：
 *
 * <pre>
 *   期初（区间之前累计的分录）  opening
 * + 区间内累计                period
 * + 区间后累计                afterWindow   ← 正常为 0；不为 0 说明有流水被错期记在区间之后
 * = 应当等于的余额            expected
 * − 当前余额快照              snapshot
 * = 差异                      diff
 * </pre>
 *
 * <p>必须带期初，是因为流水是 append-only 的事实表，而余额快照只有"现在"这一个值 ——
 * 拿区间内的流水直接去比当前快照，会凭空差出期初那一段。区间覆盖全部历史时 opening 自然为 0，
 * 公式自动退化成教科书式的「累计 vs 快照」。
 *
 * <p>区间后那一档刻意单列而不是并进期初：流水落在核对区间**之后**本身就是异常信号
 * （错期录入、事后补记），藏进别的桶里就没人看得见了。
 *
 * <p><b>为什么不建"累计值"表</b>：两个来源都现算，各只有一个真相来源。再存一份累计值，
 * 核对时反而无法判断该信谁。落库的只有"这次核对的结论"（{@link ReconciliationRun} +
 * {@link ReconciliationFinding}），它是审计凭据而不是账。
 *
 * <p><b>差异为零也是有效输出</b>：{@link ReconciliationStatus#CONSISTENT} 与 DISCREPANT 同等重要，
 * 一次什么都没查出来的核对同样要留下可复现的记录（核了哪个区间、多少笔分录、什么结论）。
 */
public final class InventoryReconciliationModels {

  private InventoryReconciliationModels() {}

  // ------------------------------------------------------------ 枚举

  /** 核对范围。范围只有一个维度，避免"仓+SKU"这种组合筛选让结果无法复现。 */
  public enum ReconciliationScopeType {
    ALL("全部"),
    WAREHOUSE("仓库"),
    SKU("SKU"),
    LEGAL_ENTITY("货权法人");

    private final String label;

    ReconciliationScopeType(String label) {
      this.label = label;
    }

    /** 中文标签。界面上用；契约与库里仍然是 ASCII 码值。 */
    public String label() {
      return label;
    }
  }

  /**
   * 核对结论。
   *
   * <p>{@code CONSISTENT} 不是"没有结论"，而是"核过了，账实一致"：它必须和 DISCREPANT 一样被记录，
   * 否则没人能证明某个区间被核过。
   */
  public enum ReconciliationStatus {
    CONSISTENT("账实一致"),
    DISCREPANT("存在差异");

    private final String label;

    ReconciliationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 发现类型：差异明细（哪一行对不上）与可疑流水定位（为什么对不上）。 */
  public enum ReconciliationFindingKind {
    DISCREPANCY("差异明细"),
    SUSPECT("可疑流水");

    private final String label;

    ReconciliationFindingKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 可疑类别 —— 蓝图点名的三类，外加漏记的一个晚期形态。
   *
   * <p>四类都**能落到具体的余额行或具体的业务单**，不是"总感觉最近对不上"这种没法查的说法：
   *
   * <ul>
   *   <li>{@link #MISSING} 漏记：快照非零，但核对区间里一条能解释它的流水都没有。
   *   <li>{@link #BACKFILL} 迟到补记：分录的入库时刻比它声称的发生时刻晚了一整天以上 ——
   *       这是漏记被"事后补上"的痕迹，补记本身合法，但补记区间正是账最容易失控的区间。
   *   <li>{@link #DUPLICATE} 重复记：同一余额行上，同一操作人、同一类型、同一数量、
   *       彼此相隔一分钟内的两笔不同业务。V1 的 UNIQUE(tenant_id,ref_type,ref_id) 挡住了
   *       同一来源单据重投，挡不住"同一批货被两张不同的单据各记一次"。
   *   <li>{@link #LOST_UPDATE} 并发丢失：同一余额行在写入时刻上撞在一起（相隔一分钟内多次写入）。
   *       丢失更新必须要有并发才会发生，单写入者的漂移归 MISSING —— 两者不要混着报。
   * </ul>
   */
  public enum ReconciliationSuspectCategory {
    MISSING("漏记"),
    BACKFILL("迟到补记"),
    DUPLICATE("重复记"),
    LOST_UPDATE("并发丢失");

    private final String label;

    ReconciliationSuspectCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 严重度。
   *
   * <p>HIGH 与 WARN 的分界是"有没有直接证据"：DUPLICATE 与 LOST_UPDATE 有具体的两笔流水互相印证，
   * 属于 HIGH；MISSING 与 BACKFILL 只能证明"解释不了"，还可能是有意的期初导入，属于 WARN。
   * 把它做成显式枚举而不是让前端自己猜，是因为下游 1 工作台预警要按严重度决定是否阻断。
   */
  public enum ReconciliationSeverity {
    INFO,
    WARN,
    HIGH
  }

  // ------------------------------------------------------------ 输入

  /**
   * 发起一次核对的请求。
   *
   * <p>{@code from} / {@code to} 是核对区间，左闭右开。区间决定两件事：流水累计值里
   * 哪一段算"本期"、可疑流水在哪个时间范围里找。**差异判定永远覆盖该余额行的全部历史**
   * （否则就是拿区间流水去比当前快照，见类注释的公式），区间只影响期初/本期的拆分与可疑定位。
   *
   * <p>三个范围筛选互斥：只允许传其中一个，范围越宽结论覆盖面越大。传两个会让"这次核了什么"
   * 变得无法一句话说清，也让 scope_key 存不下两个 id。
   */
  public record ReconciliationRequest(
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID legalEntityId) {}

  // ------------------------------------------------------------ 核对批次

  /**
   * 一次核对的结论。
   *
   * <p>不可变：写下之后不允许 UPDATE / DELETE（V54 已 REVOKE）。查出来的差异要作废，
   * 只能重跑一次核对，新旧两批并存 —— 这正是"可追溯"的意思。
   *
   * @param checkedBalanceCount 纳入核对的余额行数
   * @param checkedEntryCount 核对区间内的分录条数
   * @param checkedTransactionCount 核对区间内的业务单数
   * @param status 结论；CONSISTENT 表示区间内所有余额行的累计都等于快照
   * @param discrepancyCount 对不上的余额行数
   * @param suspectCount 定位到的可疑条目数（含差异明细之外的可疑流水）
   * @param verdictNote 结论全文说明；差异为零时写明"核对了哪个区间、多少笔分录"
   */
  public record ReconciliationRun(
      UUID id,
      ReconciliationScopeType scopeType,
      @Schema(nullable = true) UUID scopeKey,
      String scopeLabel,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      ReconciliationStatus status,
      long checkedBalanceCount,
      long checkedEntryCount,
      long checkedTransactionCount,
      BigDecimal openingOnHand,
      BigDecimal periodOnHand,
      BigDecimal afterWindowOnHand,
      BigDecimal expectedOnHand,
      BigDecimal snapshotOnHand,
      BigDecimal onHandDiff,
      BigDecimal openingReserved,
      BigDecimal periodReserved,
      BigDecimal afterWindowReserved,
      BigDecimal expectedReserved,
      BigDecimal snapshotReserved,
      BigDecimal reservedDiff,
      long discrepancyCount,
      long suspectCount,
      @Schema(nullable = true) OffsetDateTime firstSuspectAt,
      @Schema(nullable = true) OffsetDateTime lastSuspectAt,
      String verdictNote,
      UUID triggeredBy,
      OffsetDateTime createdAt) {}

  /** 核对批次列表（真分页，合计不受分页影响）。 */
  public record ReconciliationRunPage(
      List<ReconciliationRun> runs, long total, int page, int size) {}

  // ------------------------------------------------------------ 核对发现

  /**
   * 一条核对发现。差异明细与可疑流水共用这一个形状，用 {@code kind} 区分 ——
   * 两者共用 SKU / 仓库 / 法人 / 质量状态四组定位维度，放在同一条回查路径上。
   *
   * <p>数量列的口径随 {@code kind} 变化，看的时候必须先看 kind：
   *
   * <ul>
   *   <li>{@code DISCREPANCY}：{@code onHandSum} 是这条余额行**全部历史**的累计分录之和，
   *       {@code snapshotOnHand} 是它现在的值，两者之差就是 {@code onHandDiff}。
   *   <li>{@code SUSPECT}：{@code onHandSum} 是**可疑那一笔分录**自己的带符号值（不是累计），
   *       旁边的 snapshot/diff 仍然是对应余额行的，用来判断这笔可疑流水值不值得紧张。
   * </ul>
   *
   * @param category 仅 SUSPECT 有值
   * @param relatedTransactionId 重复记/并发丢失的**另一笔**流水：怀疑永远要成对给出，光说"重复了"
   *     而不告诉你是跟谁重复，人还是得回去翻流水
   * @param gapSeconds 与 {@code relatedTransactionId} 相差多少秒（重复记/并发丢失用）
   * @param latenessSeconds 分录入库时刻比声称的发生时刻晚多少秒（迟到补记用）
   * @param windowStart 可疑区间起点；SUSPECT 是该笔所属时间桶，DISCREPANCY 是该余额行流水的发生跨度
   */
  public record ReconciliationFinding(
      UUID id,
      ReconciliationFindingKind kind,
      @Schema(nullable = true) ReconciliationSuspectCategory category,
      ReconciliationSeverity severity,
      String reason,
      UUID balanceId,
      UUID skuId,
      String skuCode,
      UUID warehouseId,
      String warehouseCode,
      UUID legalEntityId,
      String legalEntityCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      @Schema(nullable = true) UUID transactionId,
      String refType,
      @Schema(nullable = true) UUID refId,
      String txKind,
      @Schema(nullable = true) UUID relatedTransactionId,
      @Schema(nullable = true) Long gapSeconds,
      @Schema(nullable = true) Long latenessSeconds,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime entryCreatedAt,
      BigDecimal onHandSum,
      BigDecimal reservedSum,
      BigDecimal snapshotOnHand,
      BigDecimal snapshotReserved,
      BigDecimal onHandDiff,
      BigDecimal reservedDiff,
      long entryCount,
      long transactionCount,
      @Schema(nullable = true) OffsetDateTime windowStart,
      @Schema(nullable = true) OffsetDateTime windowEnd) {}

  /**
   * 可疑区间汇总：把发现按时间桶聚起来，回答"是哪**一段**流水可疑"。
   *
   * <p>单个差额数字没法派工 —— 运营需要的是"先去看 10 月 3 日到 10 月 5 日那一段"。
   * 时间桶按天对齐，与查账的自然粒度一致；一个时间桶内的可疑条数越多、净变动绝对值越大，
   * 它排在越前面。
   *
   * @param suspectCount 该时间桶内的可疑流水条数
   * @param discrepancyCount 该时间桶内对不上的余额行数
   * @param onHandDelta 该时间桶内的净在手变动（用来判断"这一段动得多不多"）
   * @param severity 该时间桶的最高严重度
   */
  public record ReconciliationSuspectWindow(
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      long suspectCount,
      long discrepancyCount,
      BigDecimal onHandDelta,
      ReconciliationSeverity severity) {}

  /**
   * 一次核对的完整结果。
   *
   * <p>批次 + 差异明细 + 可疑流水 + 可疑区间排名，四件都在同一份响应里：
   * 差异为零时后三件为空列表，但 {@code run} 照样带完整的核对规模（区间、分录笔数、结论），
   * 这就是"一致的结果同样是有效输出"的落地形态。
   */
  public record ReconciliationResult(
      ReconciliationRun run,
      List<ReconciliationFinding> discrepancies,
      List<ReconciliationFinding> suspects,
      List<ReconciliationSuspectWindow> suspectWindows) {}

  /** 一次核对的发现分页（差异明细 / 可疑流水各自翻页，合计为全量）。 */
  public record ReconciliationFindingPage(
      List<ReconciliationFinding> findings, long total, int page, int size) {}
}
