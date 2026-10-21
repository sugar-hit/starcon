package com.starcon.channel;

import static com.starcon.channel.AdBulkOperationModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.1.1.3 批量操作的**纯逻辑**：参数校验、逐条预演、护栏裁决。
 *
 * <p>🔴 为什么单独一个类：预览与执行都要算同一份「会变成什么样」。
 * 两处各写一遍，迟早会出现「预览的是一个意思、执行的是另一个意思」——
 * 而批量操作是**钱**，这种偏差的代价是一次烧掉整月预算。
 * 所以这里**不碰数据库**：给定当前值与参数，算出结果与结论，两边共用。
 *
 * <p>🔴 护栏的两级语义（本模块与 14.1.1.1 的批量操作**刻意不同**）：
 *
 * <ul>
 *   <li><b>护栏越限 ⇒ 整批拒绝</b>。蓝图那句「误操作一次可能烧掉整月预算」要的就是这个：
 *       宁可一条都不做，也不要「做了一半」—— 半个批次比零个批次更难解释。
 *   <li><b>逐条业务规则不满足 ⇒ 这一条被拒</b>（比如「已归档的活动不能暂停」）。
 *       这是事实层面的不匹配，不是「这次操作太危险」。
 * </ul>
 */
final class AdBulkOperationRules {

  private AdBulkOperationRules() {}

  /** 六种操作：预算增减 / 设为预算 / 暂停 / 启用 / 结束时间 / 竞价。 */
  static final List<String> OPERATIONS =
      List.of("ADJUST_BUDGET", "SET_BUDGET", "PAUSE", "ENABLE", "SET_END_AT", "ADJUST_BID");

  /** 调整落点：活动（预算 / 状态 / 结束时间）或广告组（竞价）。 */
  static final List<String> TARGET_TYPES = List.of("CAMPAIGN", "AD_GROUP");

  /** 归一后的状态全集（与 V273 的 ad_campaign.status 同口径）。 */
  static final List<String> STATUSES =
      List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN");

  /** 结论取值：会执行 / 无需执行 / 业务规则被拒 / **护栏越限（整批拒绝）**。 */
  static final String VERDICT_APPLY = "APPLY";

  static final String VERDICT_SKIP = "SKIP";
  static final String VERDICT_REJECT = "REJECT";
  static final String VERDICT_REJECT_LIMIT = "REJECT_LIMIT";

  // ==================================================================
  // 参数校验
  // ==================================================================

  /**
   * 校验请求参数。
   *
   * <p>🔴 参数层面的错（操作类型不认识、ADJUST_BUDGET 没给增减额……）是**客户端错误**，
   * 直接 400 —— 这类错不该变成一个「被拒的任务」，那会让任务列表里堆满垃圾。
   * 护栏越限才走「整批拒绝」那条路（那是**合法但危险**的请求，必须留痕）。
   */
  static AdBulkValidatedRequest validate(AdBulkOperationRequest request) {
    String operation = text(request.operation()).toUpperCase(Locale.ROOT);
    if (!OPERATIONS.contains(operation)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "操作类型只能是 " + String.join(" / ", OPERATIONS));
    }

    String targetType = text(request.targetType()).toUpperCase(Locale.ROOT);
    if (targetType.isEmpty()) targetType = "CAMPAIGN";
    if (!TARGET_TYPES.contains(targetType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "调整落点只能是 CAMPAIGN / AD_GROUP");
    }

    // 🔴 竞价落在**广告组**上 —— 把竞价记成活动级是维度错乱，
    //   而活动本身没有竞价列，写到活动上只能落到活动预算那一列去（那是另一种操作）。
    if ("ADJUST_BID".equals(operation) && !"AD_GROUP".equals(targetType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "竞价调整的落点必须是广告组（targetType=AD_GROUP）");
    }
    if (!"ADJUST_BID".equals(operation) && "AD_GROUP".equals(targetType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "广告组落点只用于竞价调整（ADJUST_BID）");
    }

    BigDecimal budget = request.budget();
    BigDecimal delta = request.delta();
    BigDecimal bid = request.bid();
    BigDecimal bidDelta = request.bidDelta();
    OffsetDateTime endAt = request.endAt();
    boolean clearEndAt = Boolean.TRUE.equals(request.clearEndAt());

    switch (operation) {
      case "ADJUST_BUDGET" -> {
        if (delta == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算增减必须给出增减额");
        }
        if (delta.signum() == 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算增减额不能是 0");
        }
      }
      case "SET_BUDGET" -> {
        if (budget == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设为预算必须给出目标预算");
        }
        if (budget.signum() < 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标预算不能为负");
        }
      }
      case "ADJUST_BID" -> {
        boolean hasBid = bid != null;
        boolean hasDelta = bidDelta != null;
        if (hasBid == hasDelta) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "竞价调整必须给出目标竞价（bid）或竞价增减额（bidDelta），二选一");
        }
        if (hasBid && bid.signum() < 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标竞价不能为负");
        }
        if (hasDelta && bidDelta.signum() == 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "竞价增减额不能是 0");
        }
      }
      case "SET_END_AT" -> {
        if (clearEndAt == (endAt != null)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "设置结束时间必须给出目标时刻（endAt）或清空标记（clearEndAt），二选一");
        }
      }
      default -> {}
    }

    return new AdBulkValidatedRequest(
        operation,
        targetType,
        budget,
        delta,
        bid,
        bidDelta,
        endAt,
        clearEndAt,
        text(request.reason()));
  }

  // ==================================================================
  // 逐条预演
  // ==================================================================

  /**
   * 算一条目标「会变成什么样」。
   *
   * @param now 判定时刻（结束时间不能设在过去）；由调用方传入，**不取墙钟** ——
   *     同一个任务在创建与执行时用同一个判定基准，预览与执行才可能一致。
   */
  static AdBulkPlanItem plan(AdBulkTarget target, AdBulkValidatedRequest params, OffsetDateTime now) {
    return switch (params.operation()) {
      case "ADJUST_BUDGET" -> planBudget(target, params, now);
      case "SET_BUDGET" -> planBudget(target, params, now);
      case "PAUSE" -> planStatus(target, params, "PAUSED");
      case "ENABLE" -> planStatus(target, params, "ENABLED");
      case "SET_END_AT" -> planEndAt(target, params, now);
      case "ADJUST_BID" -> planBid(target, params, now);
      default -> throw new IllegalStateException("unreachable operation " + params.operation());
    };
  }

  private static AdBulkPlanItem planBudget(
      AdBulkTarget target, AdBulkValidatedRequest params, OffsetDateTime now) {
    BigDecimal before = target.dailyBudget();
    // 🔴 daily_budget 为 null = **平台侧没有预算**（不是 0）。
    //   算术上按 0 起算（从「没有预算」加 100 就是 100），但 before 仍然如实记 null。
    BigDecimal current = before == null ? BigDecimal.ZERO : before;
    BigDecimal wanted =
        "SET_BUDGET".equals(params.operation()) ? params.budget() : current.add(params.delta());
    BigDecimal delta = wanted.subtract(current);

    String verdict = VERDICT_APPLY;
    String message = "会生效（本地），尚未回写平台";

    if (wanted.signum() < 0) {
      verdict = VERDICT_REJECT;
      message = "调整后预算不能为负";
    } else if (wanted.compareTo(AdBulkOperationService.MAX_CAMPAIGN_BUDGET) > 0) {
      // 🔴 护栏 ⇒ 整批拒绝
      verdict = VERDICT_REJECT_LIMIT;
      message = "调整后预算超过单活动预算上限 " + AdBulkOperationService.MAX_CAMPAIGN_BUDGET;
    } else if (delta.abs().compareTo(AdBulkOperationService.MAX_SINGLE_BUDGET_DELTA) > 0) {
      // 🔴 护栏 ⇒ 整批拒绝（不是只拒这一条）
      verdict = VERDICT_REJECT_LIMIT;
      message =
          "单个活动一次预算调整超过上限 "
              + AdBulkOperationService.MAX_SINGLE_BUDGET_DELTA
              + "（本次调整 "
              + delta.abs()
              + "）";
    } else if (before != null && before.compareTo(wanted) == 0) {
      verdict = VERDICT_SKIP;
      message = "已经是目标预算";
    }

    return new AdBulkPlanItem(
        target.targetType(),
        target.campaignId(),
        target.campaignName(),
        target.groupId(),
        target.groupName(),
        target.status(),
        target.status(),
        before,
        wanted,
        delta,
        null,
        null,
        null,
        null,
        null,
        verdict,
        message);
  }

  private static AdBulkPlanItem planStatus(
      AdBulkTarget target, AdBulkValidatedRequest params, String wanted) {
    String before = target.status();
    String verdict = VERDICT_APPLY;
    String message = "会生效（本地），尚未回写平台";
    String after = wanted;

    if (before.equals(wanted)) {
      verdict = VERDICT_SKIP;
      message = "已处于" + ("PAUSED".equals(wanted) ? "暂停" : "投放中") + "状态";
    } else if ("ENDED".equals(before) || "ARCHIVED".equals(before)) {
      // 🔴 这是**逐条的业务规则**，不是护栏 ⇒ 只拒这一条，不整批拒绝。
      verdict = VERDICT_REJECT;
      message = "已结束或已归档的活动不能" + ("PAUSED".equals(wanted) ? "暂停" : "启用");
    }

    return new AdBulkPlanItem(
        target.targetType(),
        target.campaignId(),
        target.campaignName(),
        target.groupId(),
        target.groupName(),
        before,
        after,
        target.dailyBudget(),
        target.dailyBudget(),
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        null,
        verdict,
        message);
  }

  private static AdBulkPlanItem planEndAt(
      AdBulkTarget target, AdBulkValidatedRequest params, OffsetDateTime now) {
    OffsetDateTime before = target.endAt();
    OffsetDateTime after = params.clearEndAt() ? null : params.endAt();
    String verdict = VERDICT_APPLY;
    String message = "会生效（本地），尚未回写平台";

    if (!params.clearEndAt() && after != null && !after.isAfter(now)) {
      // 🔴 逐条业务规则：把结束时间设到过去 = 让活动立刻停投。
      verdict = VERDICT_REJECT;
      message = "结束时间必须晚于当前时刻（设到过去等于让活动立刻停止投放）";
    } else if (before == null && after == null) {
      verdict = VERDICT_SKIP;
      message = "本来就没有结束时间";
    } else if (before != null && before.equals(after)) {
      verdict = VERDICT_SKIP;
      message = "已经是目标结束时间";
    }

    return new AdBulkPlanItem(
        target.targetType(),
        target.campaignId(),
        target.campaignName(),
        target.groupId(),
        target.groupName(),
        target.status(),
        target.status(),
        target.dailyBudget(),
        target.dailyBudget(),
        BigDecimal.ZERO,
        null,
        null,
        null,
        before,
        after,
        verdict,
        message);
  }

  private static AdBulkPlanItem planBid(
      AdBulkTarget target, AdBulkValidatedRequest params, OffsetDateTime now) {
    BigDecimal before = target.defaultBid();
    // 🔴 default_bid 为 null = 平台侧没有给默认竞价（不是 0）。
    BigDecimal current = before == null ? BigDecimal.ZERO : before;
    BigDecimal wanted =
        params.bid() != null ? params.bid() : current.add(params.bidDelta());
    BigDecimal delta = wanted.subtract(current);

    String verdict = VERDICT_APPLY;
    String message = "会生效（本地），尚未回写平台";

    if (wanted.signum() < 0) {
      verdict = VERDICT_REJECT;
      message = "调整后竞价不能为负";
    } else if (wanted.compareTo(AdBulkOperationService.MAX_BID) > 0) {
      verdict = VERDICT_REJECT_LIMIT;
      message = "目标竞价超过单组竞价上限 " + AdBulkOperationService.MAX_BID;
    } else if (delta.abs().compareTo(AdBulkOperationService.MAX_SINGLE_BID_DELTA) > 0) {
      verdict = VERDICT_REJECT_LIMIT;
      message =
          "单个广告组一次竞价调整超过上限 "
              + AdBulkOperationService.MAX_SINGLE_BID_DELTA
              + "（本次调整 "
              + delta.abs()
              + "）";
    } else if (before != null && before.compareTo(wanted) == 0) {
      verdict = VERDICT_SKIP;
      message = "已经是目标竞价";
    }

    return new AdBulkPlanItem(
        target.targetType(),
        target.campaignId(),
        target.campaignName(),
        target.groupId(),
        target.groupName(),
        target.status(),
        target.status(),
        target.dailyBudget(),
        target.dailyBudget(),
        BigDecimal.ZERO,
        before,
        wanted,
        delta,
        null,
        null,
        verdict,
        message);
  }

  // ==================================================================
  // 汇总与护栏裁决
  // ==================================================================

  /** 一次批量的总额（净增量带方向，绝对敞口是护栏判的那个数）。 */
  record Totals(
      BigDecimal budgetSigned,
      BigDecimal budgetAbs,
      BigDecimal bidSigned,
      BigDecimal bidAbs,
      int limitViolations) {}

  /**
   * 汇总一批预演结果。
   *
   * <p>🔴 **净增量与绝对敞口分开报**：「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」。
   * 只看净变化的话，一批「有的加有的减」的批量就能把总额上限绕过去 ——
   * 那正是「误操作一次烧掉整月预算」最典型的形状。
   */
  static Totals summarize(List<AdBulkPlanItem> items) {
    BigDecimal budgetSigned = BigDecimal.ZERO;
    BigDecimal budgetAbs = BigDecimal.ZERO;
    BigDecimal bidSigned = BigDecimal.ZERO;
    BigDecimal bidAbs = BigDecimal.ZERO;
    int violations = 0;
    for (AdBulkPlanItem item : items) {
      if (item.budgetDelta() != null) {
        budgetSigned = budgetSigned.add(item.budgetDelta());
        budgetAbs = budgetAbs.add(item.budgetDelta().abs());
      }
      if (item.bidDelta() != null) {
        bidSigned = bidSigned.add(item.bidDelta());
        bidAbs = bidAbs.add(item.bidDelta().abs());
      }
      if (VERDICT_REJECT_LIMIT.equals(item.verdict())) violations++;
    }
    return new Totals(budgetSigned, budgetAbs, bidSigned, bidAbs, violations);
  }

  /**
   * 护栏裁决：**整批**拒绝的理由。
   *
   * <p>返回 null 表示放行。返回非 null 时**一条都不许执行**。
   */
  static String guardrailRejection(
      Totals totals, int targetCount, int maxCampaigns, int maxGroups, boolean groupTarget) {
    if (groupTarget) {
      if (targetCount > maxGroups) {
        return "本次批量解析出 " + targetCount + " 个广告组，超过单任务上限 " + maxGroups
            + " —— 整批拒绝，不做部分执行（请缩小筛选范围或分批处理）";
      }
    } else if (targetCount > maxCampaigns) {
      return "本次批量解析出 " + targetCount + " 个活动，超过单任务上限 " + maxCampaigns
          + " —— 整批拒绝，不做部分执行（请缩小筛选范围或分批处理）";
    }
    if (totals.limitViolations() > 0) {
      return "有 " + totals.limitViolations() + " 条越过了单次上限 —— 整批拒绝，不做部分执行"
          + "（单次预算调整上限 "
          + AdBulkOperationService.MAX_SINGLE_BUDGET_DELTA
          + "，单次竞价调整上限 "
          + AdBulkOperationService.MAX_SINGLE_BID_DELTA
          + "，单活动预算上限 "
          + AdBulkOperationService.MAX_CAMPAIGN_BUDGET
          + "，单组竞价上限 "
          + AdBulkOperationService.MAX_BID
          + "）";
    }
    if (totals.budgetAbs().compareTo(AdBulkOperationService.MAX_TOTAL_BUDGET_DELTA) > 0) {
      return "本次批量总预算调整绝对值 " + totals.budgetAbs() + "，超过总额上限 "
          + AdBulkOperationService.MAX_TOTAL_BUDGET_DELTA
          + " —— 整批拒绝，不做部分执行";
    }
    if (totals.bidAbs().compareTo(AdBulkOperationService.MAX_TOTAL_BID_DELTA) > 0) {
      return "本次批量总竞价调整绝对值 " + totals.bidAbs() + "，超过总额上限 "
          + AdBulkOperationService.MAX_TOTAL_BID_DELTA
          + " —— 整批拒绝，不做部分执行";
    }
    return null;
  }

  static String text(String value) {
    return value == null ? "" : value.trim();
  }
}
