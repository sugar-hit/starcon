package com.starcon.channel;

import static com.starcon.channel.SyncHealthModels.SyncHealthReason;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.5.1.1 同步与 API 健康的<b>规则层</b>：红黄绿判定。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link AccountHealthRules}、
 * {@link CompliancePrecheckRules} 同一手法）。
 *
 * <p>🔴🔴 本类守的是四件事：
 *
 * <ol>
 *   <li><b>红黄绿是一张显式的枚举 → 严重度映射表</b>，不是一个不可解释的综合分。
 *       每条规则都有：命中条件（逐字写清）、严重度、人话理由、事实来源（表 + 列）。
 *       规则表在 {@code GET /channel/sync-health/capabilities} 里原样吐出去，
 *       页面上的红黄绿**不是**前端算的。</li>
 *   <li><b>「读不到」≠「没有」</b>：本类只负责判定「读到了的事实有多严重」。
 *       「平台压根没有连接」不是本类的判定结果（那是另一个集合，见
 *       {@code platformsWithoutConnection}）；「最近同步时刻读不到」也不等于「没同步过」——
 *       「有没有过」由 {@code effectiveLastSyncAt == null} 判定，两者不混。</li>
 *   <li><b>阈值写在服务端</b>：默认阈值是<b>本模块的默认政策</b>（不是任何平台下发的口径），
 *       写进代码常量并在能力声明里公布。</li>
 *   <li><b>限流命中单列</b>：{@code reason='RATE_LIMITED'} 是**限流**，
 *       它和「授权失效导致持续漏单」不是一回事，严重度也不同（黄 vs 红）。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么限流是黄不是红</b>：限流意味着平台在限速，同步**变慢但没有断**；
 * 而 {@code AUTHORIZATION_EXPIRED} 意味着这条连接**再也拉不到数据**，会持续漏单。
 * 把两者都判成红，运营就会对着一堆限流去重授权 —— 那是错的方向。
 */
public final class SyncHealthRules {

  private SyncHealthRules() {}

  // ================================================================ 词表

  /** 严重度：红（先处理）。 */
  public static final String SEVERITY_RED = "RED";

  /** 严重度：黄（预警）。 */
  public static final String SEVERITY_YELLOW = "YELLOW";

  /** 严重度：绿（正常）。 */
  public static final String SEVERITY_GREEN = "GREEN";

  /** 严重度词表（筛选时只接受这三个值）。 */
  public static final List<String> SEVERITY_LEVELS =
      List.of(SEVERITY_RED, SEVERITY_YELLOW, SEVERITY_GREEN);

  /** 严重度排序权重（数字越小越靠前）。 */
  public static int severityRank(String severity) {
    switch (severity == null ? "" : severity) {
      case SEVERITY_RED:
        return 0;
      case SEVERITY_YELLOW:
        return 1;
      case SEVERITY_GREEN:
        return 2;
      default:
        return 3;
    }
  }

  /** 严重度的人话。 */
  public static String severityLabel(String severity) {
    switch (severity == null ? "" : severity) {
      case SEVERITY_RED:
        return "红（先处理）";
      case SEVERITY_YELLOW:
        return "黄（预警）";
      case SEVERITY_GREEN:
        return "绿（正常）";
      default:
        return severity;
    }
  }

  // ================================================================ 规则码

  /** 连接已失效（EXPIRED / REVOKED）⇒ 红。 */
  public static final String RULE_CONNECTION_INVALID = "CONNECTION_INVALID";

  /** 连接即将过期（EXPIRING）⇒ 黄。 */
  public static final String RULE_CONNECTION_EXPIRING = "CONNECTION_EXPIRING";

  /** 连接已暂停（PAUSED）⇒ 黄。 */
  public static final String RULE_CONNECTION_PAUSED = "CONNECTION_PAUSED";

  /** 同步被暂停（sync_paused=true）⇒ 黄。 */
  public static final String RULE_SYNC_PAUSED = "SYNC_PAUSED";

  /** 从来没有同步过 ⇒ 黄。 */
  public static final String RULE_NEVER_SYNCED = "NEVER_SYNCED";

  /** 同步停滞超过红线 ⇒ 红。 */
  public static final String RULE_SYNC_STALE_CRITICAL = "SYNC_STALE_CRITICAL";

  /** 同步停滞超过预警线 ⇒ 黄。 */
  public static final String RULE_SYNC_STALE_WARNING = "SYNC_STALE_WARNING";

  /** 最近一次运行失败 ⇒ 红。 */
  public static final String RULE_LAST_RUN_FAILED = "LAST_RUN_FAILED";

  /** 最近一次运行没跑完（PARTIAL / CANCELLED）⇒ 黄。 */
  public static final String RULE_LAST_RUN_INCOMPLETE = "LAST_RUN_INCOMPLETE";

  /** 窗口内有授权失效失败 ⇒ 红。 */
  public static final String RULE_FAILURE_AUTHORIZATION_EXPIRED = "FAILURE_AUTHORIZATION_EXPIRED";

  /** 窗口内命中限流 ⇒ 黄。 */
  public static final String RULE_FAILURE_RATE_LIMITED = "FAILURE_RATE_LIMITED";

  /** 窗口内其它失败 ⇒ 黄。 */
  public static final String RULE_FAILURE_OTHER = "FAILURE_OTHER";

  /** 全部检查项通过 ⇒ 绿。 */
  public static final String RULE_ALL_CLEAR = "ALL_CLEAR";

  // ================================================================ 阈值

  /**
   * 🔴 <b>模块内置默认阈值</b>。
   *
   * <p>这些数字是<b>本模块的默认政策</b>，**不是任何平台下发的口径** ——
   * 各平台的同步节奏不一样，写死成代码里的常量（而不是散落在前端），
   * 是为了让「阈值归服务端算」这件事成立；能力声明里原样公布。
   */
  public static final int STALE_SYNC_WARN_HOURS = 24;

  /** 超过这个小时数没有同步 ⇒ 红。 */
  public static final int STALE_SYNC_CRITICAL_HOURS = 72;

  /** 失败计数窗口（小时）。 */
  public static final int FAILURE_WINDOW_HOURS = 24;

  // ================================================================ 失败原因

  /** 授权失效（红）。 */
  public static final String FAILURE_AUTHORIZATION_EXPIRED = "AUTHORIZATION_EXPIRED";

  /** 限流命中（黄）。 */
  public static final String FAILURE_RATE_LIMITED = "RATE_LIMITED";

  /** 其它失败原因的人话名。 */
  public static final Map<String, String> FAILURE_REASON_LABELS =
      Map.of(
          "AUTHORIZATION_EXPIRED", "授权已失效",
          "FIELD_PARSE_FAILED", "字段解析失败",
          "MAPPING_MISSING", "映射缺失",
          "RATE_LIMITED", "命中限流",
          "NETWORK_ERROR", "网络错误",
          "PLATFORM_ERROR", "平台侧错误");

  /** 失败原因词表（与 {@code sync_failure.reason} 的 CHECK 一致）。 */
  public static final List<String> FAILURE_REASONS =
      List.of(
          "AUTHORIZATION_EXPIRED",
          "FIELD_PARSE_FAILED",
          "MAPPING_MISSING",
          "RATE_LIMITED",
          "NETWORK_ERROR",
          "PLATFORM_ERROR");

  // ================================================================ 事实

  /**
   * 判定所需的事实（一条连接的全部输入）。
   *
   * @param connectionStatus 连接状态
   * @param syncPaused 同步是否被暂停
   * @param lastSyncAt 连接级声明的最后同步时刻（可能为 null）
   * @param effectiveLastSyncAt 生效的最近同步时刻（可能为 null = 从来没有过）
   * @param evaluatedAt 判定时刻
   * @param lastRunStatus 最近一次运行的状态（null = 这条连接还没有任何运行记录）
   * @param failureCountsWindow 近窗口逐原因失败计数
   * @param failureCountsTotal 累计逐原因失败计数
   */
  public record SyncHealthFacts(
      String connectionStatus,
      boolean syncPaused,
      OffsetDateTime lastSyncAt,
      OffsetDateTime effectiveLastSyncAt,
      OffsetDateTime evaluatedAt,
      String lastRunStatus,
      Map<String, Long> failureCountsWindow,
      Map<String, Long> failureCountsTotal) {}

  /**
   * 判定结果。
   *
   * @param severity RED / YELLOW / GREEN
   * @param reasons 逐条依据（绿时给一条 ALL_CLEAR）
   */
  public record SyncHealthEvaluation(String severity, List<SyncHealthReason> reasons) {}

  // ================================================================ 判定

  /**
   * 🔴 <b>红黄绿判定</b>（服务端算，前端只显示结论与依据）。
   *
   * <p>口径：
   *
   * <ul>
   *   <li>命中任一<b>红</b>规则 ⇒ 红；</li>
   *   <li>否则命中任一<b>黄</b>规则 ⇒ 黄；</li>
   *   <li>一条都没命中 ⇒ 绿（给一条 ALL_CLEAR 依据，页面上不出现「没理由」的空白）。</li>
   * </ul>
   *
   * <p>🔴 <b>「读不到」不在这里判定</b>：本方法只吃已经读到的事实。
   * 读不到上游时服务层报 503（整个端点失败），**不会**折成 0 或空集合冒充「一切正常」。
   *
   * @param facts 判定事实
   * @param syncFactsVisible 同步维度（游标 / 运行 / 失败队列）**是否真的可见**。
   *     为 false 时，依赖同步事实的规则**一条都不判** —— 看不见的时候判「从来没有同步过」
   *     是一次彻头彻尾的撒谎（真相是「没权限看到」，不是「没有」）。
   */
  public static SyncHealthEvaluation evaluate(
      SyncHealthFacts facts, boolean syncFactsVisible) {
    List<SyncHealthReason> reasons = new ArrayList<>();
    String status = facts == null ? "" : nullToEmpty(facts.connectionStatus());

    // ---- 1. 连接状态 ----
    if ("EXPIRED".equals(status) || "REVOKED".equals(status)) {
      reasons.add(
          new SyncHealthReason(
              RULE_CONNECTION_INVALID,
              SEVERITY_RED,
              "连接已失效",
              "连接状态是 "
                  + status
                  + "：平台侧已经不再接受这条连接的调用，重新授权之前它不会再同步，"
                  + "这条连接下的店铺会持续漏单。"));
    } else if ("EXPIRING".equals(status)) {
      reasons.add(
          new SyncHealthReason(
              RULE_CONNECTION_EXPIRING,
              SEVERITY_YELLOW,
              "授权即将过期",
              "连接状态是 EXPIRING：授权快到期了。到期那一刻它会变成一条红色连接，"
                  + "现在补授权比事后补救便宜。"));
    } else if ("PAUSED".equals(status)) {
      reasons.add(
          new SyncHealthReason(
              RULE_CONNECTION_PAUSED,
              SEVERITY_YELLOW,
              "连接已暂停",
              "连接状态是 PAUSED：这条连接当前是人为暂停的，同步不会发起。"));
    }

    // ---- 2. 同步被暂停 ----
    if (facts != null && facts.syncPaused()) {
      reasons.add(
          new SyncHealthReason(
              RULE_SYNC_PAUSED,
              SEVERITY_YELLOW,
              "同步已暂停",
              "这条连接的 sync_paused=true：游标不会再推进，数据会一直停在原地。"));
    }

    // ---- 3. 最近同步时间 ----
    // 🔴 同步维度看不见时，这一段**整段跳过** —— 不是「没同步过」，是「没看到」。
    OffsetDateTime effective =
        facts == null ? null : facts.effectiveLastSyncAt();
    if (!syncFactsVisible) {
      // 一条都不判：NEVER_SYNCED / SYNC_STALE_* / LAST_RUN_* / FAILURE_* 全部依赖同步事实。
    } else if (effective == null) {
      reasons.add(
          new SyncHealthReason(
              RULE_NEVER_SYNCED,
              SEVERITY_YELLOW,
              "从来没有同步过",
              "这条连接既没有连接级同步时刻，也没有任何一次已完成的同步运行 —— "
                  + "也就是说它**一次都没有同步成功过**。这不等于「同步失败」，"
                  + "更不等于「一切正常」。"));
    } else {
      long staleHours = staleHours(facts, effective);
      if (staleHours > STALE_SYNC_CRITICAL_HOURS) {
        reasons.add(
            new SyncHealthReason(
                RULE_SYNC_STALE_CRITICAL,
                SEVERITY_RED,
                "同步已停滞",
                "距上一次同步已经 "
                    + staleHours
                    + " 小时，超过红线 "
                    + STALE_SYNC_CRITICAL_HOURS
                    + " 小时：这条连接基本可以当成「没在同步」。"));
      } else if (staleHours > STALE_SYNC_WARN_HOURS) {
        reasons.add(
            new SyncHealthReason(
                RULE_SYNC_STALE_WARNING,
                SEVERITY_YELLOW,
                "同步偏旧",
                "距上一次同步已经 "
                    + staleHours
                    + " 小时，超过预警线 "
                    + STALE_SYNC_WARN_HOURS
                    + " 小时：还没到红线，但该看看是不是调度停了。"));
      }
    }

    // ---- 4. 最近一次运行 ----
    String runStatus = facts == null ? "" : nullToEmpty(facts.lastRunStatus());
    if (!syncFactsVisible) {
      // 同步维度不可见 ⇒ 这一段整段不判（不是「没跑过」，是「没看到」）。
    } else if ("FAILED".equals(runStatus)) {
      reasons.add(
          new SyncHealthReason(
              RULE_LAST_RUN_FAILED,
              SEVERITY_RED,
              "最近一次同步运行失败",
              "最近一次同步运行的 status=FAILED：这一轮没跑通，先去同步运行日志看失败原因。"));
    } else if ("PARTIAL".equals(runStatus) || "CANCELLED".equals(runStatus)) {
      reasons.add(
          new SyncHealthReason(
              RULE_LAST_RUN_INCOMPLETE,
              SEVERITY_YELLOW,
              "最近一次同步运行没跑完",
              "最近一次同步运行的 status="
                  + runStatus
                  + "：这一轮只处理了一部分（或被中止），数据可能不完整。"));
    }

    // ---- 5. 失败计数 ----
    Map<String, Long> window = facts == null ? null : facts.failureCountsWindow();
    long authorizationExpired = syncFactsVisible ? countOf(window, FAILURE_AUTHORIZATION_EXPIRED) : 0L;
    long rateLimited = syncFactsVisible ? countOf(window, FAILURE_RATE_LIMITED) : 0L;
    long totalWindow = syncFactsVisible ? sum(window) : 0L;
    long otherWindow = Math.max(0L, totalWindow - authorizationExpired - rateLimited);

    if (authorizationExpired > 0) {
      reasons.add(
          new SyncHealthReason(
              RULE_FAILURE_AUTHORIZATION_EXPIRED,
              SEVERITY_RED,
              "授权失效导致失败",
              "近 "
                  + FAILURE_WINDOW_HOURS
                  + " 小时有 "
                  + authorizationExpired
                  + " 条 AUTHORIZATION_EXPIRED 失败：这类失败会**持续**发生"
                  + "（不是偶发抖动），不重新授权就一直漏。"));
    }
    if (rateLimited > 0) {
      reasons.add(
          new SyncHealthReason(
              RULE_FAILURE_RATE_LIMITED,
              SEVERITY_YELLOW,
              "命中限流",
              "近 "
                  + FAILURE_WINDOW_HOURS
                  + " 小时有 "
                  + rateLimited
                  + " 条 RATE_LIMITED 失败：平台在限速，同步**变慢但没断**，"
                  + "和授权失效不是一回事。"));
    }
    if (otherWindow > 0) {
      reasons.add(
          new SyncHealthReason(
              RULE_FAILURE_OTHER,
              SEVERITY_YELLOW,
              "有其它失败",
              "近 "
                  + FAILURE_WINDOW_HOURS
                  + " 小时有 "
                  + otherWindow
                  + " 条其它原因的失败（解析失败 / 映射缺失 / 网络错误 / 平台侧错误）。"));
    }

    if (reasons.isEmpty()) {
      reasons.add(
          new SyncHealthReason(
              RULE_ALL_CLEAR,
              SEVERITY_GREEN,
              "检查项全部通过",
              "连接状态正常、同步在跑、窗口内没有失败、没有命中限流。"));
      return new SyncHealthEvaluation(SEVERITY_GREEN, List.copyOf(reasons));
    }

    String severity =
        reasons.stream()
            .anyMatch(r -> SEVERITY_RED.equals(r.severity()))
            ? SEVERITY_RED
            : SEVERITY_YELLOW;
    return new SyncHealthEvaluation(severity, List.copyOf(reasons));
  }

  /** 距最近同步时刻的小时数（向下取整；判定时刻早于同步时刻时算 0）。 */
  static long staleHours(SyncHealthFacts facts, OffsetDateTime effective) {
    if (facts == null || facts.evaluatedAt() == null || effective == null) {
      return 0L;
    }
    long minutes = Duration.between(effective, facts.evaluatedAt()).toMinutes();
    return minutes <= 0 ? 0L : minutes / 60L;
  }

  private static long countOf(Map<String, Long> counts, String reason) {
    if (counts == null) {
      return 0L;
    }
    Long value = counts.get(reason);
    return value == null ? 0L : value;
  }

  private static long sum(Map<String, Long> counts) {
    if (counts == null) {
      return 0L;
    }
    long total = 0L;
    for (Long value : counts.values()) {
      if (value != null) {
        total += value;
      }
    }
    return total;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  // ================================================================ 规则表

  /**
   * 🔴 <b>红黄绿规则表</b>：枚举 → 严重度的逐条映射，能力声明原样吐出。
   *
   * <p>这是本模块「红黄绿可审阅」的物理落点：每条规则都写清了命中条件、
   * 严重度、人话理由与事实来源（表 + 列）。
   */
  public static List<SyncHealthModels.SyncHealthRuleDoc> ruleTable() {
    List<SyncHealthModels.SyncHealthRuleDoc> rules = new ArrayList<>();
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_CONNECTION_INVALID,
            SEVERITY_RED,
            "channel_connection.status ∈ {EXPIRED, REVOKED}",
            "连接已失效：平台侧不再接受这条连接的调用，不重新授权就一直漏单。",
            "channel_connection.status"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_CONNECTION_EXPIRING,
            SEVERITY_YELLOW,
            "channel_connection.status = 'EXPIRING'",
            "授权即将过期：到期后会变成一条红色连接，现在补授权更便宜。",
            "channel_connection.status"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_CONNECTION_PAUSED,
            SEVERITY_YELLOW,
            "channel_connection.status = 'PAUSED'",
            "连接被暂停：人为停掉的，同步不会发起。",
            "channel_connection.status"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_SYNC_PAUSED,
            SEVERITY_YELLOW,
            "channel_connection.sync_paused = true",
            "同步已暂停：游标不再推进，数据会一直停在原地。",
            "channel_connection.sync_paused"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_NEVER_SYNCED,
            SEVERITY_YELLOW,
            "生效的最近同步时刻为空（连接级声明值与最近一次已完成的运行都没有）",
            "从来没有同步成功过 —— 不等于「同步失败」，更不等于「一切正常」。",
            "channel_connection.last_sync_at + sync_run.finished_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_SYNC_STALE_CRITICAL,
            SEVERITY_RED,
            "距生效的最近同步时刻 > " + STALE_SYNC_CRITICAL_HOURS + " 小时",
            "同步已停滞：这条连接基本可以当成「没在同步」。",
            "channel_connection.last_sync_at + sync_run.finished_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_SYNC_STALE_WARNING,
            SEVERITY_YELLOW,
            "距生效的最近同步时刻 > " + STALE_SYNC_WARN_HOURS + " 小时（且未超过红线）",
            "同步偏旧：还没到红线，但该看看是不是调度停了。",
            "channel_connection.last_sync_at + sync_run.finished_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_LAST_RUN_FAILED,
            SEVERITY_RED,
            "该连接最近一次 sync_run.status = 'FAILED'",
            "最近一次同步运行失败：这一轮没跑通。",
            "sync_run.status"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_LAST_RUN_INCOMPLETE,
            SEVERITY_YELLOW,
            "该连接最近一次 sync_run.status ∈ {PARTIAL, CANCELLED}",
            "最近一次同步运行没跑完：数据可能不完整。",
            "sync_run.status"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_FAILURE_AUTHORIZATION_EXPIRED,
            SEVERITY_RED,
            "近 " + FAILURE_WINDOW_HOURS + " 小时内 sync_failure.reason = 'AUTHORIZATION_EXPIRED' 的条数 > 0",
            "授权失效导致的失败会持续发生，不重新授权就一直漏。",
            "sync_failure.reason + sync_failure.occurred_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_FAILURE_RATE_LIMITED,
            SEVERITY_YELLOW,
            "近 " + FAILURE_WINDOW_HOURS + " 小时内 sync_failure.reason = 'RATE_LIMITED' 的条数 > 0",
            "命中限流：平台在限速，同步变慢但没断（不是授权失效那类红灯）。",
            "sync_failure.reason + sync_failure.occurred_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_FAILURE_OTHER,
            SEVERITY_YELLOW,
            "近 "
                + FAILURE_WINDOW_HOURS
                + " 小时内其余原因的 sync_failure 条数 > 0",
            "还有其它原因的失败：解析失败 / 映射缺失 / 网络错误 / 平台侧错误。",
            "sync_failure.reason + sync_failure.occurred_at"));
    rules.add(
        new SyncHealthModels.SyncHealthRuleDoc(
            RULE_ALL_CLEAR,
            SEVERITY_GREEN,
            "上面一条规则都没命中",
            "检查项全部通过。",
            "（派生结论，不是某一张表的列）"));
    return List.copyOf(rules);
  }

  /** 生效阈值。 */
  public static SyncHealthModels.SyncHealthThresholds thresholds() {
    return new SyncHealthModels.SyncHealthThresholds(
        STALE_SYNC_WARN_HOURS, STALE_SYNC_CRITICAL_HOURS, FAILURE_WINDOW_HOURS);
  }

  /** 「最近同步时刻」的来源标记。 */
  public static final String SOURCE_CONNECTION_DECLARED = "CONNECTION_DECLARED";

  /** 「最近同步时刻」的来源标记：来自最近一次已完成的运行。 */
  public static final String SOURCE_RUN_FINISHED = "RUN_FINISHED";

  /** 「最近同步时刻」的来源标记：从来没有过。 */
  public static final String SOURCE_NONE = "NONE";

  /**
   * 算出生效的「最近同步时刻」与它的来源。
   *
   * @param declared 连接级声明值（{@code channel_connection.last_sync_at}）
   * @param runFinished 最近一次已完成的运行的完成时刻（{@code sync_run.finished_at}）
   * @return [生效时刻, 来源标记]
   */
  public static Object[] effectiveLastSyncAt(OffsetDateTime declared, OffsetDateTime runFinished) {
    if (declared != null) {
      return new Object[] {declared, SOURCE_CONNECTION_DECLARED};
    }
    if (runFinished != null) {
      return new Object[] {runFinished, SOURCE_RUN_FINISHED};
    }
    return new Object[] {null, SOURCE_NONE};
  }

  /** 规则表按严重度排序（红在前），供页面渲染。 */
  public static Map<String, String> severityOfRules() {
    Map<String, String> map = new LinkedHashMap<>();
    for (SyncHealthModels.SyncHealthRuleDoc rule : ruleTable()) {
      map.put(rule.code(), rule.severity());
    }
    return map;
  }
}
