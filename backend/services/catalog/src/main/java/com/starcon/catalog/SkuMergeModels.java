package com.starcon.catalog;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 3.9.1.2 SKU 合并的传输对象。
 *
 * <p>蓝图：「<b>输出：合并结果；引用迁移报告；原记录合并标记。</b>」三样东西在本文件里各有一个形状：
 *
 * <ul>
 *   <li>{@link SkuMergePreview} —— <b>执行前</b>的只读推演（合并结果先给人看，再落地）；
 *   <li>{@link SkuMergeRow} —— 已执行的一次合并（合并结果 + 报告摘要 + 标记状态）；
 *   <li>{@link SkuMergeReferenceRow} —— 报告的**逐维度**一行；
 *   <li>{@link SkuMergeTrace} —— 「可回溯合并来源」：任意一条 SKU 被并进了哪一条、
 *       又有哪几条被并进了它。
 * </ul>
 *
 * <p><b>预演与执行用同一个 {@link SkuMergeInput}、同一套 {@code SkuMergeRules}</b>：
 * 合并是本仓库里少数几个会改写十几张表的写操作，页面必须能在按下确认之前
 * 看到「会搬多少行、哪几行搬不动、哪些是刻意不动的历史」，而不是执行完才知道。
 * 预演与执行共用一条代码路径，所以报告里不会出现「预演说 3 行冲突、实际搬了 5 行」这种事。
 *
 * <p><b>报告的六个计数不折叠成一个数</b>：{@code migrated} / {@code summed} /
 * {@code deduplicated} / {@code conflict} / {@code frozen} 各自的处理动作不同
 * （搬走 / 相加 / 去重 / 搬不动留下 / 刻意不动），运营要判断的正是
 * 「哪几类引用在挡路」，而一个合计数答不了这个问题。
 */
final class SkuMergeModels {
  private SkuMergeModels() {}

  // ================================================================ 执行

  /**
   * 执行一次合并。
   *
   * <p><b>为什么这里没有 {@code expectedVersion} 字段</b>：蓝图把 {@code expected_version}
   * 写成输入，但它指的是 <b>3.9.1.1 登记决定那一刻两个 SKU 的 {@code sku.version}</b> ——
   * 那个值已经存在 {@code sku_duplicate_decision.primary_version / secondary_version} 里，
   * 由本模块<b>读回来</b>与此刻的真实版本比对。让客户端再传一遍等于多一个能被改错的入口：
   * 传 0 就能跳过「登记之后有没有人改过商品」这道检查。
   * 所以 {@code expected_version} 是本模块**执行的判据**而不是请求体的一个字段。
   *
   * <p>并发保护靠两处，都不接受客户端传值：
   * <ol>
   *   <li>{@code update sku ... where version=<读取时的值>} 的行数断言（被合并记录）；
   *   <li>V41 的 {@code UNIQUE(tenant_id,decision_id)}（一份决定只执行一次）。
   * </ol>
   *
   * @param note 合并备注（留档用，不参与任何判定）
   * @param confirm {@code true} 表示确实要执行。为 {@code false}/缺省时走预演，
   *     <b>一个字节都不写</b> —— 预演不是「执行再回滚」，它从不出现在写事务里。
   *     缺省为 false 而不是 true：破坏性操作必须显式确认
   */
  public record SkuMergeInput(@Size(max = 1000) String note, Boolean confirm) {}

  /**
   * 引用迁移报告的**一个维度**（蓝图「引用迁移报告」的最小单元）。
   *
   * @param action MOVE（改指主记录）/ SUM（用量相加并入主记录已有行）/ FROZEN（历史事实，刻意不动）/ NONE（无行）
   * @param conflictCount 因唯一约束或业务规则搬不动、<b>留在被合并记录上</b>的行数。
   *     这些行不是「合并失败」：主记录本身已有同类数据时，被合并记录上的那一行继续挂着，
   *     而被合并记录是保留的、可以回查的，所以这些引用仍然答得出来
   * @param frozenCount 属于历史事实、刻意不搬的行数（采购单行冻结快照、状态事件、导入历史…）。
   *     蓝图：「历史订单行冻结当时 SKU 快照，不随合并回溯改写」
   * @param blocked 整维度被拒绝迁移（只可能因为跨 SPU 时该维度的复合外键不允许改指），给出原因
   */
  public record SkuMergeReferenceRow(
      String dimension,
      String label,
      String action,
      String actionLabel,
      int sourceCount,
      int migratedCount,
      int summedCount,
      int deduplicatedCount,
      int conflictCount,
      int frozenCount,
      boolean blocked,
      String note) {}

  /**
   * 引用迁移报告（逐维度展开 + 合计）。
   *
   * @param references 逐维度行，顺序即页面展示顺序（先搬得动的、再搬不动的、最后冻结的历史）
   * @param totalMigrated / totalSummed / totalDeduplicated / totalConflict / totalFrozen 五个合计
   * @param blockedDimensions 被整维度拒绝的维度数（跨 SPU 时的属性值），
   *     页面要把它显示成一句明确的警告而不是混进 conflict 里
   */
  public record SkuMergeReport(
      List<SkuMergeReferenceRow> references,
      int totalMigrated,
      int totalSummed,
      int totalDeduplicated,
      int totalConflict,
      int totalFrozen,
      int blockedDimensions,
      String note) {}

  /**
   * 执行前的推演结果（一个字节都不写）。
   *
   * <p>除了报告，它还带上 {@code versionCheck} 与两端身份：
   * 页面要能在确认之前就回答「这份决定还成立吗」与「到底要把哪两条并起来」。
   *
   * @param versionCheck MATCHED 可以执行 / STALE 决定已过期 / ALREADY_EXECUTED 已执行过 /
   *     NOT_DECIDED 还没有 MERGE 决定 / ALREADY_MERGED 记录已被合并过
   * @param executable 只有 MATCHED 才为 {@code true}；其余情况下按钮必须禁用
   */
  public record SkuMergePreview(
      UUID candidateId,
      UUID decisionId,
      String decision,
      String versionCheck,
      String versionCheckLabel,
      boolean executable,
      String blockReason,
      String scope,
      String scopeLabel,
      UUID primarySkuId,
      String primaryCode,
      String primaryName,
      UUID primarySpuId,
      String primarySpuCode,
      String primaryStatus,
      long primaryVersion,
      UUID secondarySkuId,
      String secondaryCode,
      String secondaryName,
      UUID secondarySpuId,
      String secondarySpuCode,
      String secondaryStatus,
      long secondaryVersion,
      Long expectedPrimaryVersion,
      Long expectedSecondaryVersion,
      int secondarySpuRemainingSkuCount,
      SkuMergeReport report) {}

  /**
   * 已执行的一次合并。
   *
   * @param scope SKU（两条 SKU 同 SPU，只并掉一条）/ SPU（跨 SPU，源 SPU 连带停产）
   * @param secondaryMarked 原记录的合并标记：{@code mergedIntoSkuId} 指向主记录、状态已落 INACTIVE。
   *     蓝图：「原记录标记合并并停用而非物理删除」——这两个数就是那件事的证据
   * @param secondarySpuRemainingSkuCount 源 SPU 上还剩几条没被这次合并处理的 SKU（scope='SKU' 时为 0）
   */
  public record SkuMergeRow(
      UUID id,
      UUID candidateId,
      UUID decisionId,
      UUID scanId,
      String scope,
      String scopeLabel,
      UUID primarySkuId,
      String primaryCode,
      String primaryName,
      UUID secondarySkuId,
      String secondaryCode,
      String secondaryName,
      String secondaryStatusBefore,
      String secondaryStatusAfter,
      /** 原记录此刻的合并标记：指向主记录的 id。uuid 列，所以是 UUID 而不是 Long。 */
      UUID mergedIntoSkuId,
      Instant mergedAt,
      UUID mergedBy,
      UUID secondarySpuId,
      String secondarySpuStatusBefore,
      String secondarySpuStatusAfter,
      int secondarySpuRemainingSkuCount,
      int migratedCount,
      int summedCount,
      int deduplicatedCount,
      int conflictCount,
      int frozenCount,
      String note,
      UUID executedBy,
      Instant executedAt,
      SkuMergeReport report) {}

  public record SkuMergePage(List<SkuMergeRow> items, int page, int size, long total, int pendingMergeDecisions) {}

  // ================================================================ 回溯

  /**
   * 「可回溯合并来源」：一条 SKU 的合并血缘。
   *
   * @param mergedInto 本 SKU 被并进了哪一条；{@code null} = 它不是被合并方
   * @param mergedIntoCode 被并进那条的编码（合并发生时可能已改名，这里是合并那一刻的快照）
   * @param absorbed 被并进本 SKU 的记录清单（它作为主记录吸收过谁）
   * @param chain 合并链的每一跳（主记录 → …），保持一层是因为服务层拒绝链式合并，
   *     所以正常只有一跳；仍按清单返回是为了页面不必假设这个不变量
   */
  public record SkuMergeTrace(
      UUID skuId,
      String skuCode,
      String skuName,
      String status,
      boolean mergedAway,
      UUID mergedIntoSkuId,
      String mergedIntoCode,
      String mergedIntoName,
      Instant mergedAt,
      UUID mergedBy,
      List<SkuMergeTrace> absorbed,
      List<SkuMergeTrace> chain,
      SkuMergeReport lastReport,
      String note) {}

  /** 合并历史列表的计数：已登记 MERGE 但还没执行的决定数（页面上「待执行」那一格）。 */
  public record SkuMergeOverview(
      UUID latestScanId,
      int totalMerges,
      int mergedAwaySkuCount,
      int pendingMergeDecisions,
      int conflictReferences,
      int frozenReferences,
      long mergedSince,
      String note) {}
}
