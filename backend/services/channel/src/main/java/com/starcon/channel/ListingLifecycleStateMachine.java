package com.starcon.channel;

import static com.starcon.channel.ListingLifecycleModels.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 4.1.1.3 七态生命周期状态机（纯函数 · 可单测）。
 *
 * <p>蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。」
 *
 * <p>🔴 状态机分**两条路径**，两者的严格程度**故意不同**：
 *
 * <ol>
 *   <li><b>人工路径（严格）</b>：内部运营的一个动作能改内部状态。它必须落在
 *       {@link #ACTION_FROM} 规定的合法起始状态上，且落点是该动作的固定目标状态。
 *       不落在那儿 ⇒ <b>显式拒绝并给出可读原因</b>，不是 500、也不是「默默不动」。
 *       人工路径的错误点击是**有资损风险**的（下错了商品、平台正在卖的东西被下架）。
 *   <li><b>平台路径（宽松）</b>：平台侧状态变更被动同步。平台说的话是**事实**
 *       （蓝图的加粗句：「回流是平台状态的权威来源」），内部状态要做的是**跟上**，
 *       而不是「因为不合我的状态机就拒绝」。它只受两条约束：
 *       <ul>
 *         <li>目标必须是平台能表达的四个状态之一（{@link #PLATFORM_TARGETS}）；
 *         <li>不能是自身（那是没有变化的「同步」，显式拒绝并说明）。
 *       </ul>
 *       平台路径若被无脑拒绝，后果就是蓝图点名的**漏单**：平台把商品下架了，
 *       内部还挂着「在售」，而系统说「这个迁移不合法」。
 * </ol>
 *
 * <p>🔴 状态机**不负责**「内部状态与平台状态冲突时谁说了算」—— 那是 4.3.2.2 的判定口径
 * （{@code ListingStatusSyncService.detectConflict}）。本类只管「能不能这么迁、为什么不能」。
 *
 * <p>🔴 本类**不碰数据库**：所有判断都是纯函数，因此可以在单元测试里把每一条边都走一遍，
 * 不必起容器。
 */
final class ListingLifecycleStateMachine {

  private ListingLifecycleStateMachine() {}

  // ================================================================
  // 人工动作 → 目标状态
  // ================================================================

  /**
   * 人工动作的固定目标状态。
   *
   * <p>「主动下架」⇒ DELISTED_BY_US，「重新上架」⇒ ON_SALE，
   * 「提交平台审核」⇒ UNDER_REVIEW，「标记被封禁」⇒ BLOCKED。
   *
   * <p>🔴 动作**不能**自带目标状态：目标由动作决定，调用方说了不算 ——
   * 否则「主动下架」这个动作就能把商品改成「平台下架」，那两个事实就分不开了。
   */
  static final Map<String, String> ACTION_TARGET =
      Map.of(
          "DELIST", "DELISTED_BY_US",
          "REPUBLISH", "ON_SALE",
          "SUBMIT_REVIEW", "UNDER_REVIEW",
          "MARK_BLOCKED", "BLOCKED");

  /**
   * 每个动作的**合法起始状态**。
   *
   * <p>这张表就是「为什么不能这么迁」的答案本身。例如：
   *
   * <ul>
   *   <li>「重新上架」在「在售」上不合法 —— 已经在售的东西不需要重新上架。
   *   <li>「重新上架」在「平台审核中」上不合法 —— 上不上架由平台裁决决定，
   *       等平台结果（4.3.2.2 回流）或等驳回后再上架。
   *   <li>「主动下架」在「主动下架」上不合法 —— 重复下架不会产生第二次平台动作。
   * </ul>
   */
  static final Map<String, Set<String>> ACTION_FROM =
      Map.of(
          // 主动下架：除了「已经在主动下架」，其余都可以下架（含平台下架 ⇒ 内部跟进下架）
          "DELIST",
              Set.of(
                  "DRAFT", "ON_SALE", "UNDER_REVIEW", "REJECTED",
                  "DELISTED_BY_PLATFORM", "BLOCKED"),
          // 重新上架：草稿（直接上架）/ 平台驳回（修正后上架）/ 两种下架 / 被封禁（申诉成功）
          "REPUBLISH",
              Set.of("DRAFT", "REJECTED", "DELISTED_BY_US", "DELISTED_BY_PLATFORM", "BLOCKED"),
          // 提交平台审核：除「已在审核中」外都可以
          "SUBMIT_REVIEW",
              Set.of(
                  "DRAFT", "ON_SALE", "REJECTED",
                  "DELISTED_BY_US", "DELISTED_BY_PLATFORM", "BLOCKED"),
          // 标记被封禁：除「已被封禁」外都可以
          "MARK_BLOCKED",
              Set.of(
                  "DRAFT", "ON_SALE", "UNDER_REVIEW", "REJECTED",
                  "DELISTED_BY_US", "DELISTED_BY_PLATFORM"));

  /**
   * 结构迁移表（人工路径用）：哪些「从 → 到」在结构上成立。
   *
   * <p>🔴 <b>没有任何状态可以迁回 DRAFT</b>：草稿是商品**登记时**的初始态，
   * 不是一种可以「回到」的状态。一个已登记的商品退不回草稿 ——
   * 退回去等于把它从平台侧的历史里抹掉。
   */
  static final Map<String, Set<String>> TRANSITIONS =
      Map.of(
          "DRAFT", Set.of("ON_SALE", "UNDER_REVIEW", "DELISTED_BY_US", "BLOCKED"),
          "ON_SALE",
              Set.of(
                  "UNDER_REVIEW", "REJECTED", "DELISTED_BY_US",
                  "DELISTED_BY_PLATFORM", "BLOCKED"),
          "UNDER_REVIEW",
              Set.of(
                  "ON_SALE", "REJECTED", "DELISTED_BY_US",
                  "DELISTED_BY_PLATFORM", "BLOCKED"),
          "REJECTED",
              Set.of(
                  "ON_SALE", "UNDER_REVIEW", "DELISTED_BY_US",
                  "DELISTED_BY_PLATFORM", "BLOCKED"),
          "DELISTED_BY_US",
              Set.of("ON_SALE", "UNDER_REVIEW", "DELISTED_BY_PLATFORM", "BLOCKED"),
          "DELISTED_BY_PLATFORM",
              Set.of("ON_SALE", "UNDER_REVIEW", "DELISTED_BY_US", "BLOCKED"),
          "BLOCKED",
              Set.of(
                  "ON_SALE", "UNDER_REVIEW", "DELISTED_BY_US", "DELISTED_BY_PLATFORM"));

  // ================================================================
  // 平台路径
  // ================================================================

  /**
   * 平台事件类型 → 内部目标状态。
   *
   * <p>只映射**平台能直接表达的四态**：
   *
   * <ul>
   *   <li>APPROVED → ON_SALE（平台审核通过 ⇒ 内部在售）
   *   <li>REJECTED → REJECTED（平台驳回 ⇒ 内部平台驳回）
   *   <li>DELISTED → DELISTED_BY_PLATFORM（平台下架 ⇒ 内部**平台下架**）
   *   <li>SUPPRESSED → DELISTED_BY_PLATFORM（平台抑制：内部没有独立状态；
   *       它与「平台下架」在 4.3.2.2 的可售性口径上同为「不在售」，
   *       所以落 DELISTED_BY_PLATFORM 而不是 BLOCKED —— BLOCKED 是**封禁**，不是抑制）
   * </ul>
   *
   * <p>🔴 THROTTLED / CATEGORY_REVIEW / PRICE_ALERT **不改变内部状态**：商品仍在售，
   * 平台只是加了限制。在 4.3.2.2 的可售性口径里它们是「受限」——
   * 受限的��品仍是可售的，把它改成「不在售」是编事实。
   */
  static final Map<String, String> PLATFORM_TARGET =
      Map.of(
          "APPROVED", "ON_SALE",
          "REJECTED", "REJECTED",
          "DELISTED", "DELISTED_BY_PLATFORM",
          "SUPPRESSED", "DELISTED_BY_PLATFORM");

  /** 平台路径的目标集合（{@link #PLATFORM_TARGET} 的值域）。 */
  static final Set<String> PLATFORM_TARGETS = Set.copyOf(PLATFORM_TARGET.values());

  /**
   * 平台事件类型 → 内部状态是否变化。
   *
   * @return 目标状态；{@code null} 表示「平台说了这件事，但内部状态不变」
   */
  static String platformTarget(String eventType) {
    return PLATFORM_TARGET.get(eventType == null ? "" : eventType);
  }

  /** 平台事件是否会让内部状态发生变化。 */
  static boolean platformChangesState(String eventType) {
    return platformTarget(eventType) != null;
  }

  /**
   * 平台路径的合法性判定。
   *
   * @return null = 允许；非 null = 可读的拒绝原因
   */
  static String platformRejection(String eventType, String currentStatus) {
    if (eventType == null || !PLATFORM_EVENT_TYPES.contains(eventType)) {
      return "未知的平台事件类型「"
          + eventType
          + "」。平台状态只认这七种："
          + String.join(" / ", PLATFORM_EVENT_TYPES)
          + "（拼错一个词就是平台侧的一个新状态，猜一个最像的去执行比报错危险得多）";
    }
    String target = platformTarget(eventType);
    if (target == null) {
      return null; // 受限类事件：内部状态不变，不是拒绝
    }
    if (target.equals(currentStatus)) {
      return "内部状态已经是「"
          + currentStatus
          + "」，平台又确认了一次同样的状态 —— 状态没有变化，本次联动不成立。";
    }
    return null;
  }

  // ================================================================
  // 人工路径
  // ================================================================

  /** 动作的目标状态。未知动作 ⇒ 422（带可读原因）。 */
  static String targetOf(String action) {
    String target = ACTION_TARGET.get(action == null ? "" : action);
    if (target == null) {
      throw new IllegalArgumentException(
          "未知的人工动作「"
              + action
              + "」。可选："
              + String.join(" / ", ACTION_TARGET.keySet().stream().sorted().toList()));
    }
    return target;
  }

  /**
   * 人工动作在当前状态下的合法性判定。
   *
   * @return null = 允许；非 null = 可读的拒绝原因
   */
  static String actionRejection(String action, String currentStatus) {
    if (!ACTION_TARGET.containsKey(action == null ? "" : action)) {
      return "未知的人工动作「"
          + action
          + "」。可选："
          + String.join(" / ", ACTION_TARGET.keySet().stream().sorted().toList());
    }
    Set<String> from = ACTION_FROM.get(action);
    if (from.contains(currentStatus)) {
      // 结构表是最后一道防线：动作的合法起始状态集合与结构迁移表必须一致。
      if (!TRANSITIONS.get(currentStatus).contains(ACTION_TARGET.get(action))) {
        return "从「"
            + currentStatus
            + "」迁移到「"
            + ACTION_TARGET.get(action)
            + "」不是合法迁移。";
      }
      return null;
    }
    return switch (action) {
      case "DELIST" ->
          "当前已经是「"
              + currentStatus
              + "」（主动下架），无需重复下架。";
      case "REPUBLISH" ->
          "「重新上架」要求当前处于「"
              + String.join(" / ", sorted(ACTION_FROM.get("REPUBLISH")))
              + "」之一，当前是「"
              + currentStatus
              + "」—— "
              + (currentStatus.equals("UNDER_REVIEW")
                  ? "上架与否由平台裁决决定，请等平台审核结果（4.3.2.2 会把结果回流），或在审核被驳回后重新上架。"
                  : currentStatus.equals("ON_SALE")
                      ? "已经在售的 listing 不需要重新上架。"
                      : "当前状态不支持这个动作。");
      case "SUBMIT_REVIEW" ->
          currentStatus.equals("UNDER_REVIEW")
              ? "当前已经在「"
                  + currentStatus
                  + "」（平台审核中），重复提交审核不会产生新的平台动作。"
              : "当前状态「" + currentStatus + "」不支持提交平台审核。";
      case "MARK_BLOCKED" ->
          currentStatus.equals("BLOCKED")
              ? "当前已经是「" + currentStatus + "」（被封禁）。"
              : "当前状态「" + currentStatus + "」不支持标记被封禁。";
      default -> "当前状态「" + currentStatus + "」不支持这个动作。";
    };
  }

  /** 当前状态下合法的人工动作（页面据此渲染按钮，**不**自己判断）。 */
  static List<String> allowedActions(String currentStatus) {
    return ACTION_FROM.entrySet().stream()
        .filter(e -> e.getValue().contains(currentStatus))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  /** 结构上的合法后继状态。 */
  static List<String> allowedTargets(String currentStatus) {
    return sorted(TRANSITIONS.getOrDefault(currentStatus, Set.of()));
  }

  /** 结构迁移是否成立。 */
  static boolean allows(String from, String to) {
    return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }

  // ================================================================
  // 规则说明（随能力声明与详情一起下发，前端原样显示）
  // ================================================================

  /** 状态机规则的完整说明。 */
  static String rule() {
    StringBuilder sb = new StringBuilder();
    sb.append("七态：");
    sb.append(
        "草稿 DRAFT → 在售 ON_SALE → 平台审核中 UNDER_REVIEW → 平台驳回 REJECTED → ")
        .append("主动下架 DELISTED_BY_US / 平台下架 DELISTED_BY_PLATFORM / 被封禁 BLOCKED。");
    sb.append("没有状态可以迁回「草稿」—— 草稿是登记时的初始态，退回去等于抹掉平台侧的历史。");
    sb.append("人工动作的合法起始状态：");
    for (Map.Entry<String, Set<String>> e : ACTION_FROM.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      sb.append(e.getKey()).append(" ← ").append(String.join(" / ", sorted(e.getValue()))).append("；");
    }
    sb.append("平台路径：平台能表达的四态（ON_SALE / REJECTED / DELISTED_BY_PLATFORM / BLOCKED）");
    sb.append("可以从任何当前状态直接落到（自身除外）；限流 / 类目审核 / 价格异常**不改变内部状态**");
    sb.append("（商品仍在售，平台只是加了限制）。");
    sb.append("🔴 平台路径比人工路径宽松，是因为平台说的是事实：内部状态要做的是跟上，而不是拒绝 —— ");
    sb.append("把「平台已下架」判成非法迁移，后果就是蓝图点名的漏单。");
    return sb.toString();
  }

  /** 「平台下架 vs 内部下架」的判定口径。 */
  static String divergenceRule() {
    return "差异 = 平台侧事实与内部事实**对同一件商品的说法相反**。"
        + "内部可售性：ON_SALE → 在售；UNDER_REVIEW / REJECTED → 在等平台裁决（没有明确主张，不算差异）；"
        + "DRAFT / DELISTED_BY_US / DELISTED_BY_PLATFORM / BLOCKED → 不在售。"
        + "平台可售性：APPROVED → 在售；REJECTED / DELISTED / SUPPRESSED → 不在售；"
        + "THROTTLED / CATEGORY_REVIEW / PRICE_ALERT → 受限（仍在售，不算差异）。"
        + "🔴 「内部已主动下架但没回写平台」也算差异：内部说下了、平台还不知道，平台可能还在卖。"
        + "判定结论由服务端算好后下发（divergence / divergenceKind / divergenceNote），前端原样显示。";
  }

  // ================================================================
  // 小工具
  // ================================================================

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  /** 动作 → 目标状态的可读映射（供前端与文档使用）。 */
  static Map<String, String> actionTargets() {
    return new LinkedHashMap<>(ACTION_TARGET);
  }
}
