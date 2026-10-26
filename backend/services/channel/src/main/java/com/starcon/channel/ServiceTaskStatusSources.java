package com.starcon.channel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.5.2.1 服务与任务状态的<b>任务来源注册表</b>。
 *
 * <p>🔴🔴 <b>本类的存在理由只有一句话：这些表的形状真的不一样。</b>
 *
 * <p>本仓 channel 服务里有三十多张任务 / 运行类表，它们的状态列名、状态取值域、
 * 有没有「执行中」、有没有开始时刻、有没有耗时列，**全都不同**：
 *
 * <table>
 *   <caption>逐表对照</caption>
 *   <tr><th>表</th><th>状态列</th><th>取值域</th><th>执行中？</th><th>耗时列？</th></tr>
 *   <tr><td>sync_run</td><td>status</td><td>RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED</td><td>RUNNING</td><td>duration_ms</td></tr>
 *   <tr><td>backfill_task</td><td>status</td><td>PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED</td><td>RUNNING</td><td>duration_ms</td></tr>
 *   <tr><td>listing_migration_batch</td><td>state</td><td>EXECUTED/PARTIAL/FAILED</td><td>无</td><td>无</td></tr>
 *   <tr><td>publish_task</td><td>state</td><td>PENDING/DISPATCHED/SUCCEEDED/FAILED</td><td>DISPATCHED</td><td>无</td></tr>
 *   <tr><td>listing_collect_task</td><td>state</td><td>SUCCEEDED/FAILED</td><td>无</td><td>无</td></tr>
 * </table>
 *
 * <p>⇒ 所以本模块**不写死一张统一的「任务表」假设**，而是每个来源一张描述：
 * 状态列叫什么、哪些取值算「执行中」、能不能判停滞、耗时从哪几列算、失败能不能分类。
 * 服务层用这份描述生成 SQL，能力声明用同一份描述公布口径 ——
 * <b>SQL 与文档不可能对不上，因为它们读的是同一份东西</b>。
 *
 * <p>🔴 <b>三条纪律</b>：
 *
 * <ol>
 *   <li><b>没有「执行中」就没有「停滞」</b>：{@code listing_migration_batch} 与
 *       {@code listing_collect_task} 的状态取值域里压根没有「还在跑」这一档，
 *       所以它们**判不了停滞**（不是「没停滞」）。</li>
 *   <li><b>没有原因词表就没有失败分类</b>：只有 {@code sync_failure.reason} 是受控词表，
 *       其余来源的失败原因要么是自由文本、要么压根没有 —— 一律标
 *       {@code UNCLASSIFIED}（判不了），**本模块不替它们编一套分类**。</li>
 *   <li><b>可空的时间列会让判定判不了</b>：{@code backfill_task.started_at} 可空，
 *       所以「在跑但没记开始时刻」的任务会落进 {@code STALL_UNDETERMINED}。</li>
 * </ol>
 */
public final class ServiceTaskStatusSources {

  private ServiceTaskStatusSources() {}

  /**
   * 一个任务来源（= 一张事实表）的描述。
   *
   * @param sourceKey 来源键（= 表名）
   * @param table 事实表名
   * @param label 人话名
   * @param ownerModule 归属模块号
   * @param ownerModuleName 归属模块名
   * @param stateColumn 状态列名
   * @param startColumn 「开始执行时刻」列名（这张表没有则 null）
   * @param finishColumn 「结束时刻」列名（这张表没有则 null）
   * @param durationColumn 耗时列名（这张表没有则 null）
   * @param canonicalStates 原始状态值 → 规范化状态的映射
   * @param inFlightValues 算「执行中」的原始状态值（空 = 这张表没有「执行中」这一档）
   * @param stallSupported 这张表能不能判停滞
   * @param stallNote 能不能判停滞的**逐字**理由
   * @param timingNote 耗时是从哪几列算出来的
   * @param failureClassSupported 失败能不能分类（只有受控原因词表才行）
   * @param failureClassNote 失败分类能力的逐字说明
   * @param triggerColumn 触发来源列名（没有则 null）
   */
  public record TaskSource(
      String sourceKey,
      String table,
      String label,
      String ownerModule,
      String ownerModuleName,
      String stateColumn,
      String startColumn,
      String finishColumn,
      String durationColumn,
      Map<String, String> canonicalStates,
      List<String> inFlightValues,
      boolean stallSupported,
      String stallNote,
      String timingNote,
      boolean failureClassSupported,
      String failureClassNote,
      String triggerColumn) {

    /** 原始状态值的取值域（按声明顺序）。 */
    public List<String> stateValues() {
      return List.copyOf(canonicalStates.keySet());
    }

    /** 某个原始状态值规范化之后是什么。 */
    public String canonical(String raw) {
      if (raw == null) {
        return ServiceTaskStatusRules.STATE_UNKNOWN;
      }
      return canonicalStates.getOrDefault(raw, ServiceTaskStatusRules.STATE_UNKNOWN);
    }

    /** 某个原始状态值算不算「执行中」。 */
    public boolean inFlight(String raw) {
      return raw != null && inFlightValues.contains(raw);
    }
  }

  // ================================================================ 注册表

  /** 同步运行（02.4.1.2 运行日志）。 */
  public static final TaskSource SYNC_RUN =
      new TaskSource(
          "sync_run",
          "sync_run",
          "同步运行",
          "2.4.1.2",
          "运行日志",
          "status",
          "started_at",
          "finished_at",
          "duration_ms",
          canonical("RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"),
          List.of("RUNNING"),
          true,
          "能判：这张表的 status 取值域里有「RUNNING」，且 started_at 是 NOT NULL —— "
              + "「已执行多少分钟」可以直接算。",
          "耗时 = sync_run.duration_ms（这张表自己记的）；这一列为空时用 finished_at - started_at 兜底。",
          true,
          "能判：这张表的失败原因落在 sync_failure.reason（受控六值词表）上，"
              + "按 run_id 关联到这次运行 ⇒ 可以分成「业务 / 配置问题」与「系统 / 外部问题」。",
          "trigger_source");

  /** 增量补拉（2.6.2.1 增量补拉）。 */
  public static final TaskSource BACKFILL_TASK =
      new TaskSource(
          "backfill_task",
          "backfill_task",
          "增量补拉",
          "2.6.2.1",
          "增量补拉",
          "status",
          "started_at",
          "finished_at",
          "duration_ms",
          canonical("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"),
          List.of("RUNNING"),
          true,
          "能判，**但有一条例外**：这张表的 started_at 是**可空**的 —— "
              + "「在跑却没记开始时刻」的任务落进 STALL_UNDETERMINED（判不了），不会伪装成「没卡住」。",
          "耗时 = backfill_task.duration_ms；这一列为空时用 finished_at - started_at 兜底。",
          false,
          "判不了：这张表**没有受控的失败原因词表** ⇒ 失败一律标 UNCLASSIFIED，"
              + "本模块不替它编一套「系统故障 / 业务问题」的分类。",
          "trigger_source");

  /** 批量搬迁（4.4.2.2 批量搬迁）—— 数据迁移任务。 */
  public static final TaskSource LISTING_MIGRATION_BATCH =
      new TaskSource(
          "listing_migration_batch",
          "listing_migration_batch",
          "批量搬迁",
          "4.4.2.2",
          "批量搬迁",
          "state",
          null,
          null,
          null,
          canonical("EXECUTED", "PARTIAL", "FAILED"),
          List.of(),
          false,
          "判不了：这张表的 state 取值域只有 EXECUTED / PARTIAL / FAILED —— "
              + "**压根没有「执行中」这一档**，所以「停滞」对它不适用（不是「没停滞」）。",
          "这张表**没有开始 / 结束时刻列，也没有耗时列** ⇒ 耗时一律是「—」。"
              + "只有 created_at / updated_at，那是记录被写入的时刻，不是执行时长，"
              + "拿它们相减算「耗时」是错的。",
          false,
          "判不了：这张表**没有受控的失败原因词表** ⇒ 失败一律标 UNCLASSIFIED。",
          null);

  /** 发布任务（4.3.1.3 发布任务）。 */
  public static final TaskSource PUBLISH_TASK =
      new TaskSource(
          "publish_task",
          "publish_task",
          "发布任务",
          "4.3.1.3",
          "发布任务",
          "state",
          "dispatched_at",
          "completed_at",
          null,
          canonical("PENDING", "DISPATCHED", "SUCCEEDED", "FAILED"),
          List.of("DISPATCHED"),
          true,
          "能判：这张表的 DISPATCHED = 「已派发但还没完成」= 在途，"
              + "且 DB 钉死了「非 PENDING 必有 dispatched_at」⇒ 开始时刻不会为空。",
          "耗时 = completed_at - dispatched_at（这张表**没有耗时列**）。",
          false,
          "判不了：这张表只有 failure_reason（**自由文本**），没有受控词表 ⇒ "
              + "失败一律标 UNCLASSIFIED。",
          null);

  /** 链接采集（4.4.1.1 链接采集）。 */
  public static final TaskSource LISTING_COLLECT_TASK =
      new TaskSource(
          "listing_collect_task",
          "listing_collect_task",
          "链接采集",
          "4.4.1.1",
          "链接采集",
          "state",
          "started_at",
          "finished_at",
          null,
          canonical("SUCCEEDED", "FAILED"),
          List.of(),
          false,
          "判不了：这张表的 state 只有 SUCCEEDED / FAILED —— "
              + "**没有「执行中」这一档**，抓取是同步完成的，所以「停滞」对它不适用。",
          "耗时 = finished_at - started_at（这张表**没有耗时列**）。",
          false,
          "判不了：这张表只有 http_status 与自由文本 error_message，没有受控原因词表 ⇒ "
              + "失败一律标 UNCLASSIFIED（http_status 会原样带给页面，但不据此编分类）。",
          null);

  /** 全部任务来源（顺序固定：页面上按这个顺序排）。 */
  public static final List<TaskSource> ALL =
      List.of(SYNC_RUN, BACKFILL_TASK, LISTING_MIGRATION_BATCH, PUBLISH_TASK, LISTING_COLLECT_TASK);

  /** 按来源键取描述。 */
  public static TaskSource byKey(String sourceKey) {
    String value = sourceKey == null ? "" : sourceKey.trim();
    for (TaskSource source : ALL) {
      if (source.sourceKey().equals(value)) {
        return source;
      }
    }
    return null;
  }

  /** 构造「原始状态 → 规范化状态」的映射（保序）。 */
  private static Map<String, String> canonical(String... values) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String value : values) {
      map.put(value, value);
    }
    // EXECUTED（批量搬迁的「已完成」）是唯一需要改写的取值。
    if (map.containsKey("EXECUTED")) {
      map.put("EXECUTED", ServiceTaskStatusRules.STATE_SUCCEEDED);
    }
    // DISPATCHED（发布任务的「在途」）也算执行中。
    if (map.containsKey("DISPATCHED")) {
      map.put("DISPATCHED", ServiceTaskStatusRules.STATE_RUNNING);
    }
    // 🔴 用 unmodifiableMap(LinkedHashMap) 而不是 Map.copyOf —— 后者**不保序**，
    //    而取值域的顺序要原样出现在能力声明与生成的 SQL 里。
    return Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }
}
