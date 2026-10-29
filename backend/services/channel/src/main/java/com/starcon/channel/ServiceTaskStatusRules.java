package com.starcon.channel;

import static com.starcon.channel.ServiceTaskStatusModels.ServiceTaskStatusReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.5.2.1 服务与任务状态的<b>规则层</b>：停滞判定、失败分类、严重度。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link SyncHealthRules}、
 * {@link AccountHealthRules} 同一手法）。
 *
 * <p>🔴🔴 本类守的是五件事：
 *
 * <ol>
 *   <li><b>「停滞」是一个具名、可测、有口径的定义</b>，不是一个拍脑袋的判断。
 *       判据写死在这里：<b>任务处于执行中，且已执行时长超过阈值</b>。
 *       阈值是<b>代码常量</b>（可被 Spring 属性覆盖），并在能力声明里原样公布。</li>
 *   <li><b>时间基准是数据库的 {@code now()}</b>：判定用的「已执行多少分钟」由
 *       SQL 用 {@code now()} 算好（{@code elapsed_minutes}），本类只拿这个数字做比较 ——
 *       绝不用应用层墙钟。本仓已经两次因为墙钟与事务时钟分叉造成假红。</li>
 *   <li><b>「停滞」与「失败」是两类提示</b>：一个是「没在跑」，一个是「跑挂了」。
 *       两者在规则层就是两个不同的规则码，在端点上就是两个不同的列表。</li>
 *   <li><b>「系统故障」与「业务数据问题」必须分得开</b>：
 *       {@code sync_failure.reason} 的六个取值天然分两类，
 *       {@link #classifyReason(String)} 是唯一的归类入口，<b>不允许在别处再分一次</b>。</li>
 *   <li><b>「判不了」是一个独立的状态</b>：在跑但拿不到开始时刻（{@code backfill_task}
 *       的 {@code started_at} 可空）、失败队列因数据范围读不到 —— 这两种都判不了，
 *       判不了就写判不了，**不许**折成「正常」。</li>
 * </ol>
 */
public final class ServiceTaskStatusRules {

  private ServiceTaskStatusRules() {}

  // ================================================================ 严重度

  /** 严重度：红（先处理）。 */
  public static final String SEVERITY_RED = "RED";

  /** 严重度：黄（预警）。 */
  public static final String SEVERITY_YELLOW = "YELLOW";

  /** 严重度：绿（正常）。 */
  public static final String SEVERITY_GREEN = "GREEN";

  /** 严重度词表（筛选时只接受这三个值）。 */
  public static final List<String> SEVERITY_LEVELS =
      List.of(SEVERITY_RED, SEVERITY_YELLOW, SEVERITY_GREEN);

  // ================================================================ 任务状态（规范化）

  /** 规范化状态：执行中。 */
  public static final String STATE_RUNNING = "RUNNING";

  /** 规范化状态：待执行。 */
  public static final String STATE_PENDING = "PENDING";

  /** 规范化状态：成功。 */
  public static final String STATE_SUCCEEDED = "SUCCEEDED";

  /** 规范化状态：部分完成。 */
  public static final String STATE_PARTIAL = "PARTIAL";

  /** 规范化状态：失败。 */
  public static final String STATE_FAILED = "FAILED";

  /** 规范化状态：已中止。 */
  public static final String STATE_CANCELLED = "CANCELLED";

  /** 规范化状态：未知（那张表出现了词表之外的值）。 */
  public static final String STATE_UNKNOWN = "UNKNOWN";

  /** 规范化状态词表。 */
  public static final List<String> STATE_LEVELS =
      List.of(
          STATE_RUNNING,
          STATE_PENDING,
          STATE_SUCCEEDED,
          STATE_PARTIAL,
          STATE_FAILED,
          STATE_CANCELLED,
          STATE_UNKNOWN);

  // ================================================================ 失败分类

  /** 失败分类：业务 / 配置问题（人要处理，但系统没坏）。 */
  public static final String FAILURE_CLASS_BUSINESS = "BUSINESS";

  /** 失败分类：系统 / 外部问题（系统或对方的问题）。 */
  public static final String FAILURE_CLASS_SYSTEM = "SYSTEM";

  /** 失败分类：两类都有。 */
  public static final String FAILURE_CLASS_MIXED = "MIXED";

  /** 失败分类：有失败，但来源表没有原因词表 ⇒ 判不出是系统故障还是业务数据问题。 */
  public static final String FAILURE_CLASS_UNCLASSIFIED = "UNCLASSIFIED";

  /** 失败分类：失败队列读不到（数据范围受限）⇒ 判不了。 */
  public static final String FAILURE_CLASS_UNDETERMINED = "UNDETERMINED";

  /** 失败分类：没有失败。 */
  public static final String FAILURE_CLASS_NONE = "NONE";

  /** 失败分类词表。 */
  public static final List<String> FAILURE_CLASS_LEVELS =
      List.of(
          FAILURE_CLASS_BUSINESS,
          FAILURE_CLASS_SYSTEM,
          FAILURE_CLASS_MIXED,
          FAILURE_CLASS_UNCLASSIFIED,
          FAILURE_CLASS_UNDETERMINED,
          FAILURE_CLASS_NONE);

  /**
   * 🔴 <b>业务 / 配置类失败原因</b>。
   *
   * <p>这三个值的共同点：<b>系统没坏，是数据 / 配置不对</b>。
   * {@code AUTHORIZATION_EXPIRED} 按蓝图 2.4.2.1 还会自动升级为工作台待办 ——
   * 它导致持续漏单，但它**不是**系统故障。
   */
  public static final List<String> BUSINESS_FAILURE_REASONS =
      List.of("AUTHORIZATION_EXPIRED", "MAPPING_MISSING", "FIELD_PARSE_FAILED");

  /**
   * 🔴 <b>系统 / 外部类失败原因</b>。
   *
   * <p>这三个值的共同点：<b>毛病在平台侧或网络侧</b>，用户的数据本身没问题。
   */
  public static final List<String> SYSTEM_FAILURE_REASONS =
      List.of("NETWORK_ERROR", "RATE_LIMITED", "PLATFORM_ERROR");

  /** 全部失败原因词表（{@code sync_failure.reason} 的取值域）。 */
  public static final List<String> FAILURE_REASONS =
      List.of(
          "AUTHORIZATION_EXPIRED",
          "FIELD_PARSE_FAILED",
          "MAPPING_MISSING",
          "NETWORK_ERROR",
          "PLATFORM_ERROR",
          "RATE_LIMITED");

  /**
   * 把一个失败原因归到「业务 / 配置」或「系统 / 外部」两类。
   *
   * @param reason 失败原因码
   * @return {@link #FAILURE_CLASS_BUSINESS} / {@link #FAILURE_CLASS_SYSTEM}；
   *     不在词表里则返回 null（**不是** UNCLASSIFIED —— 那是「有失败但没有词表」，
   *     与「原因码不认识」是两件事，调用方自己决定怎么报）
   */
  public static String classifyReason(String reason) {
    String value = reason == null ? "" : reason.trim();
    if (BUSINESS_FAILURE_REASONS.contains(value)) {
      return FAILURE_CLASS_BUSINESS;
    }
    if (SYSTEM_FAILURE_REASONS.contains(value)) {
      return FAILURE_CLASS_SYSTEM;
    }
    return null;
  }

  /** 失败原因的人话。 */
  public static String failureReasonLabel(String reason) {
    switch (reason == null ? "" : reason) {
      case "AUTHORIZATION_EXPIRED":
        return "授权已失效";
      case "MAPPING_MISSING":
        return "映射缺失";
      case "FIELD_PARSE_FAILED":
        return "字段解析失败";
      case "NETWORK_ERROR":
        return "网络错误";
      case "RATE_LIMITED":
        return "命中限流";
      case "PLATFORM_ERROR":
        return "平台侧错误";
      default:
        return reason;
    }
  }

  // ================================================================ 阈值

  /**
   * 🔴 <b>停滞预警阈值（分钟）的默认值</b>。
   *
   * <p>这是**本模块的默认政策**，不是任何平台下发的口径。可用 Spring 属性
   * {@code channel.service-task-status.stall-warn-minutes} 覆盖。
   */
  public static final int DEFAULT_STALL_WARN_MINUTES = 30;

  /**
   * 🔴 <b>停滞严重阈值（分钟）的默认值</b>。
   *
   * <p>可用 Spring 属性 {@code channel.service-task-status.stall-critical-minutes} 覆盖。
   */
  public static final int DEFAULT_STALL_CRITICAL_MINUTES = 120;

  /** 失败计数窗口（小时）的默认值。 */
  public static final int DEFAULT_FAILURE_WINDOW_HOURS = 24;

  /** 每页行数上限。 */
  public static final int MAX_PAGE_SIZE = 200;

  // ================================================================ 规则码

  /** 任务失败 ⇒ 红。 */
  public static final String RULE_TASK_FAILED = "TASK_FAILED";

  /** 任务部分完成 ⇒ 黄。 */
  public static final String RULE_TASK_PARTIAL = "TASK_PARTIAL";

  /** 停滞超过红线 ⇒ 红。 */
  public static final String RULE_STALL_CRITICAL = "STALL_CRITICAL";

  /** 停滞超过预警线 ⇒ 黄。 */
  public static final String RULE_STALL_WARNING = "STALL_WARNING";

  /** 在跑但判不了停滞（拿不到开始时刻）⇒ 黄（判不了 ≠ 正常）。 */
  public static final String RULE_STALL_UNDETERMINED = "STALL_UNDETERMINED";

  /** 任务在跑且没超时 ⇒ 绿。 */
  public static final String RULE_TASK_RUNNING = "TASK_RUNNING";

  /** 任务成功 ⇒ 绿。 */
  public static final String RULE_TASK_SUCCEEDED = "TASK_SUCCEEDED";

  /** 任务被人工中止 ⇒ 绿（不是故障）。 */
  public static final String RULE_TASK_CANCELLED = "TASK_CANCELLED";

  /** 任务待执行 ⇒ 绿。 */
  public static final String RULE_TASK_PENDING = "TASK_PENDING";

  /** 状态落在词表之外 ⇒ 黄（不认识的状态不当成正常）。 */
  public static final String RULE_TASK_STATE_UNKNOWN = "TASK_STATE_UNKNOWN";

  // ================================================================ 判定

  /**
   * 一条任务的判定输入。
   *
   * @param state 规范化后的状态
   * @param elapsedMinutes 已执行多少分钟（{@code null} = 判不了；由数据库 {@code now()} 算好）
   * @param inFlight 是否处于执行中
   * @param failureClass 失败分类
   */
  public record ServiceTaskStatusFacts(
      String state, Long elapsedMinutes, boolean inFlight, String failureClass) {}

  /**
   * 判定结果。
   *
   * @param severity 综合严重度
   * @param reasons 逐条依据
   * @param stalled 是否已判为停滞
   * @param stallSeverity 停滞的严重度（未停滞时为 null）
   * @param stallDeterminable 停滞是否判得了
   */
  public record ServiceTaskStatusEvaluation(
      String severity,
      List<ServiceTaskStatusReason> reasons,
      boolean stalled,
      String stallSeverity,
      boolean stallDeterminable) {}

  /**
   * 🔴 <b>判定一个任务。</b>
   *
   * @param facts 判定输入
   * @param stallWarnMinutes 停滞预警阈值（分钟）
   * @param stallCriticalMinutes 停滞严重阈值（分钟）
   * @return 判定结果
   */
  public static ServiceTaskStatusEvaluation evaluate(
      ServiceTaskStatusFacts facts, int stallWarnMinutes, int stallCriticalMinutes) {
    List<ServiceTaskStatusReason> reasons = new ArrayList<>();
    String state = facts == null || facts.state() == null ? STATE_UNKNOWN : facts.state();
    boolean inFlight = facts != null && facts.inFlight();
    Long elapsed = facts == null ? null : facts.elapsedMinutes();

    // ---- 1. 失败 ----
    if (STATE_FAILED.equals(state)) {
      reasons.add(new ServiceTaskStatusReason(RULE_TASK_FAILED, SEVERITY_RED, "任务失败", failureDetail(facts)));
    } else if (STATE_PARTIAL.equals(state)) {
      reasons.add(
          new ServiceTaskStatusReason(
              RULE_TASK_PARTIAL,
              SEVERITY_YELLOW,
              "任务只完成了一部分",
              "这条任务的状态是 PARTIAL：有一部分处理完了、有一部分没有 —— "
                  + "数据可能不完整，先看这条任务自己的明细再决定要不要重跑。"));
    }

    // ---- 2. 停滞 ----
    boolean stalled = false;
    String stallSeverity = null;
    boolean stallDeterminable = true;
    if (inFlight) {
      if (elapsed == null) {
        // 🔴 判不了就写判不了：折成「正常」等于替这张表撒谎（它压根没记开始时刻）。
        stallDeterminable = false;
        reasons.add(
            new ServiceTaskStatusReason(
                RULE_STALL_UNDETERMINED,
                SEVERITY_YELLOW,
                "在跑，但判不了停滞",
                "这条任务的状态是「执行中」，但它**没有开始时刻**（这张表的那一列可空且为空）—— "
                    + "所以「它是不是卡住了」这一条**判不了**。判不了不等于没卡住。"));
      } else if (elapsed > stallCriticalMinutes) {
        stalled = true;
        stallSeverity = SEVERITY_RED;
        reasons.add(
            new ServiceTaskStatusReason(
                RULE_STALL_CRITICAL,
                SEVERITY_RED,
                "任务已停滞",
                "这条任务已经执行了 "
                    + elapsed
                    + " 分钟，超过红线 "
                    + stallCriticalMinutes
                    + " 分钟：基本可以当成「卡住了」（时间基准 = 数据库 now()）。"));
      } else if (elapsed > stallWarnMinutes) {
        stalled = true;
        stallSeverity = SEVERITY_YELLOW;
        reasons.add(
            new ServiceTaskStatusReason(
                RULE_STALL_WARNING,
                SEVERITY_YELLOW,
                "任务跑得有点久",
                "这条任务已经执行了 "
                    + elapsed
                    + " 分钟，超过预警线 "
                    + stallWarnMinutes
                    + " 分钟（红线 "
                    + stallCriticalMinutes
                    + " 分钟）：还没到卡死，但该看看是不是卡住了。"));
      } else {
        reasons.add(
            new ServiceTaskStatusReason(
                RULE_TASK_RUNNING,
                SEVERITY_GREEN,
                "任务在跑",
                "这条任务处于执行中，已经跑了 " + elapsed + " 分钟，在阈值之内。"));
      }
    } else {
      switch (state) {
        // 🔴 FAILED / PARTIAL 上面已经判过了 —— 这里**不能再补一条绿**，
        //    否则一条失败任务会同时挂上「红」和「绿」两条依据，自相矛盾。
        case STATE_FAILED, STATE_PARTIAL -> {
          // 已在上文判定
        }
        case STATE_SUCCEEDED ->
            reasons.add(
                new ServiceTaskStatusReason(
                    RULE_TASK_SUCCEEDED, SEVERITY_GREEN, "任务已完成", "这条任务已经跑完，状态是 SUCCEEDED。"));
        case STATE_CANCELLED ->
            reasons.add(
                new ServiceTaskStatusReason(
                    RULE_TASK_CANCELLED,
                    SEVERITY_GREEN,
                    "任务被中止",
                    "这条任务的状态是 CANCELLED：人工中止的，不是故障。"));
        case STATE_PENDING ->
            reasons.add(
                new ServiceTaskStatusReason(
                    RULE_TASK_PENDING,
                    SEVERITY_GREEN,
                    "任务待执行",
                    "这条任务还没被执行（状态 PENDING）：它是排队等派发，不是卡住。"));
        case STATE_UNKNOWN ->
            reasons.add(
                new ServiceTaskStatusReason(
                    RULE_TASK_STATE_UNKNOWN,
                    SEVERITY_YELLOW,
                    "任务状态不认识",
                    "这条任务的状态落在本模块的词表之外 —— "
                        + "不能当成「正常」，本模块的判定对它**不适用**。"));
        default ->
            reasons.add(
                new ServiceTaskStatusReason(
                    RULE_TASK_SUCCEEDED, SEVERITY_GREEN, "任务已结束", "这条任务已经结束，状态是 " + state + "。"));
      }
    }

    String severity =
        reasons.stream().anyMatch(r -> SEVERITY_RED.equals(r.severity()))
            ? SEVERITY_RED
            : reasons.stream().anyMatch(r -> SEVERITY_YELLOW.equals(r.severity()))
                ? SEVERITY_YELLOW
                : SEVERITY_GREEN;
    return new ServiceTaskStatusEvaluation(
        severity, List.copyOf(reasons), stalled, stallSeverity, stallDeterminable);
  }

  /** 失败依据的人话说明 —— 🔴 必须把「系统故障」和「业务数据问题」说清楚。 */
  private static String failureDetail(ServiceTaskStatusFacts facts) {
    String failureClass = facts == null ? null : facts.failureClass();
    if (FAILURE_CLASS_BUSINESS.equals(failureClass)) {
      return "这条任务失败了，但失败原因属于**业务 / 配置问题**（授权失效 / 映射缺失 / 字段解析失败）—— "
          + "系统本身没坏，是数据或配置不对，要人去处理。";
    }
    if (FAILURE_CLASS_SYSTEM.equals(failureClass)) {
      return "这条任务失败了，失败原因属于**系统 / 外部问题**（网络错误 / 限流 / 平台侧错误）—— "
          + "平台或网络侧的毛病，用户的数据本身没问题。";
    }
    if (FAILURE_CLASS_MIXED.equals(failureClass)) {
      return "这条任务失败了，失败原因里**两类都有**（既有业务 / 配置问题，也有系统 / 外部问题）—— "
          + "两件事都要处理，别只盯着其中一类。";
    }
    if (FAILURE_CLASS_UNDETERMINED.equals(failureClass)) {
      return "这条任务失败了，但**失败队列读不到**（当前角色的数据范围看不到）—— "
          + "所以「是系统故障还是业务数据问题」这一条**判不了**，别把它当成已归类。";
    }
    if (FAILURE_CLASS_UNCLASSIFIED.equals(failureClass)) {
      return "这条任务失败了，但**这张表没有失败原因词表** —— "
          + "所以「是系统故障还是业务数据问题」这一条**判不了**，要去归属系统看失败明细。";
    }
    return "这条任务的状态是 FAILED：它跑挂了。";
  }

  // ================================================================ 规则表

  /**
   * 🔴 <b>规则表</b>：枚举 → 严重度的逐条映射，能力声明原样吐出。
   *
   * <p>每条都写清了命中条件（**含阈值与时间基准**）、严重度、人话理由与事实来源。
   */
  public static List<ServiceTaskStatusModels.ServiceTaskStatusRuleDoc> ruleTable(
      int stallWarnMinutes, int stallCriticalMinutes) {
    List<ServiceTaskStatusModels.ServiceTaskStatusRuleDoc> rules = new ArrayList<>();
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_FAILED,
            SEVERITY_RED,
            "规范化后的 state = 'FAILED'",
            "任务跑挂了。到底是系统故障还是业务数据问题，看 failureClass —— 这一条只说「它失败了」。",
            "各来源表自己的状态列"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_PARTIAL,
            SEVERITY_YELLOW,
            "规范化后的 state = 'PARTIAL'",
            "任务只完成了一部分：数据可能不完整。",
            "各来源表自己的状态列"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_STALL_CRITICAL,
            SEVERITY_RED,
            "在执行中（state = 'RUNNING'）且 elapsed_minutes > "
                + stallCriticalMinutes
                + "（阈值可用 channel.service-task-status.stall-critical-minutes 调整；"
                + "elapsed_minutes 由数据库 now() 算出）",
            "任务卡住了：执行时长超过红线。",
            "sync_run.started_at / backfill_task.started_at / publish_task.dispatched_at"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_STALL_WARNING,
            SEVERITY_YELLOW,
            "在执行中（state = 'RUNNING'）且 elapsed_minutes > "
                + stallWarnMinutes
                + " 且未超过红线（阈值可用 channel.service-task-status.stall-warn-minutes 调整）",
            "任务跑得有点久：该看看是不是卡住了。",
            "sync_run.started_at / backfill_task.started_at / publish_task.dispatched_at"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_STALL_UNDETERMINED,
            SEVERITY_YELLOW,
            "在执行中（state = 'RUNNING'）但 elapsed_minutes 为空（来源表没有开始时刻或那一列为空）",
            "判不了停滞：判不了不等于没卡住。",
            "backfill_task.started_at（可空）"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_RUNNING,
            SEVERITY_GREEN,
            "在执行中且 elapsed_minutes ≤ " + stallWarnMinutes,
            "任务在跑且没超时。",
            "同上"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_SUCCEEDED,
            SEVERITY_GREEN,
            "规范化后的 state = 'SUCCEEDED'",
            "任务已完成。",
            "各来源表自己的状态列"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_CANCELLED,
            SEVERITY_GREEN,
            "规范化后的 state = 'CANCELLED'",
            "人工中止，不是故障。",
            "各来源表自己的状态列"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_PENDING,
            SEVERITY_GREEN,
            "规范化后的 state = 'PENDING'",
            "排队等派发，不是卡住。",
            "各来源表自己的状态列"));
    rules.add(
        new ServiceTaskStatusModels.ServiceTaskStatusRuleDoc(
            RULE_TASK_STATE_UNKNOWN,
            SEVERITY_YELLOW,
            "规范化后的 state = 'UNKNOWN'（来源表出现了词表之外的状态值）",
            "状态不认识，本模块的判定对它不适用 —— 不当成正常。",
            "各来源表自己的状态列"));
    return List.copyOf(rules);
  }

  /** 生效阈值。 */
  public static ServiceTaskStatusModels.ServiceTaskStatusThresholds thresholds(
      int stallWarnMinutes, int stallCriticalMinutes, int failureWindowHours) {
    return new ServiceTaskStatusModels.ServiceTaskStatusThresholds(
        stallWarnMinutes, stallCriticalMinutes, failureWindowHours, MAX_PAGE_SIZE);
  }

  /**
   * 🔴 <b>失败分类表</b>：能力声明原样吐出。
   *
   * <p>这是本模块「区分系统故障与业务数据问题」的物理落点。
   */
  public static List<ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc> failureClasses() {
    return List.of(
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_BUSINESS,
            "业务 / 配置问题",
            BUSINESS_FAILURE_REASONS,
            "系统没坏，是数据或配置不对：授权过期会让这条连接**持续**漏单（不是偶发抖动），"
                + "映射缺失与字段解析失败要人去补数据。",
            "找运营 / 客服补授权、补映射 —— 不用找技术排查平台。"),
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_SYSTEM,
            "系统 / 外部问题",
            SYSTEM_FAILURE_REASONS,
            "毛病在平台侧或网络侧：网络错误、限流、平台自己报错 —— 用户的数据本身没问题。",
            "先看是不是平台在抖 / 被限流；这类自己会恢复，不需要逐条处理。"),
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_MIXED,
            "两类都有",
            List.of(),
            "同一次失败里既有业务 / 配置原因，也有系统 / 外部原因 —— 两件事都要处理。",
            "先按业务类那条处理掉，再看系统类那条是不是还在持续。"),
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_UNCLASSIFIED,
            "有失败但判不了分类",
            List.of(),
            "来源表没有受控的失败原因词表（只有自由文本或 HTTP 状态码）—— "
                + "**本模块不替它编一个分类**，标成判不了。",
            "去归属系统看失败明细，别把「判不了」读成「系统故障」。"),
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_UNDETERMINED,
            "失败队列读不到",
            List.of(),
            "当前角色的数据范围看不到失败队列 —— 分类所需的事实根本读不到。",
            "换一个数据范围更大的角色再看；**别**把「读不到」读成「没有失败」。"),
        new ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc(
            FAILURE_CLASS_NONE,
            "没有失败",
            List.of(),
            "这条任务没有失败记录。",
            "无需处理。"));
  }

  /** 规则码 → 严重度的映射（供页面渲染）。 */
  public static Map<String, String> severityOfRules(
      int stallWarnMinutes, int stallCriticalMinutes) {
    Map<String, String> map = new LinkedHashMap<>();
    for (ServiceTaskStatusModels.ServiceTaskStatusRuleDoc rule : ruleTable(stallWarnMinutes, stallCriticalMinutes)) {
      map.put(rule.code(), rule.severity());
    }
    return map;
  }
}
