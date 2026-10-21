package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.BrandAuthorizationReason;

import com.starcon.channel.BrandAuthorizationModels.BrandAuthorizationScopeLine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16.4.2.1 品牌授权的**判定规则**（纯函数，无 IO）。
 *
 * <p>蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。……<b>授权过期是「平时没事、上新时出事」的典型</b>，
 * 需到期提醒与刊登阻断。」
 *
 * <p>🔴 本类是「哪些结论可以下、哪些下不了」的**唯一出处**。它有几条硬纪律：
 *
 * <ol>
 *   <li><b>没有原因的 false 是不允许出现的</b>：每一次「不覆盖」都必须落到一条原因上
 *       （{@link BrandAuthorizationReason}），否则运营看到「不合规」三个字却不知道该去改哪一条授权。
 *   <li><b>「判不了」不等于「合格」</b>：上下文缺维度时结论是 {@code UNKNOWN}，
 *       <b>不是</b> {@code IN_SCOPE}。发布闸门只在 {@code IN_SCOPE} 时放行 ——
 *       「无法证明在授权范围内」与「已证明在授权范围内」是两回事。
 *   <li><b>期限是区间</b>：{@code effectiveFrom <= now <= effectiveTo} 才算在有效期内。
 *   <li><b>同一维度上没有登记范围 = 该维度不限制</b>：授权只登记了国家而没登记店铺，
 *       意思是「这家店在哪个国家卖都行」，不是「这家店不许卖」。
 * </ol>
 *
 * <p>🔴 <b>国家维度的口径</b>：一个店铺往往同时在多个站点销售。
 * 授权只覆盖 DE 而店铺同时卖 DE + FR 时，这家店的品牌使用**在 FR 上是越界的**，
 * 所以本模块要求授权覆盖店铺的**全部**目标国家，缺一个就是不覆盖。
 * 判不了的场景（店铺没有启用站点、或调用方没给国家）落到 {@code UNKNOWN} 而不是默默放行。
 */
public final class BrandAuthorizationRules {

  private BrandAuthorizationRules() {}

  // ---------------------------------------------------------------- 词表

  /** 授权范围的三个维度。 */
  public static final List<String> SCOPE_KINDS = List.of("SHOP", "COUNTRY", "CATEGORY");

  /** 校验结论词表。 */
  public static final List<String> CONCLUSIONS =
      List.of("IN_SCOPE", "OUT_OF_SCOPE", "EXPIRED", "NO_AUTHORIZATION", "UNKNOWN");

  /** 分级词表：BLOCKING 明确违规 / REVIEW 判不了（仍不放行）/ NONE 通过。 */
  public static final List<String> SEVERITIES = List.of("BLOCKING", "REVIEW", "NONE");

  /** 到期状态词表。 */
  public static final List<String> EXPIRY_STATES =
      List.of("ACTIVE", "EXPIRING", "EXPIRED", "NOT_YET_EFFECTIVE", "DISABLED");

  /** 被授权主体类型词表。 */
  public static final List<String> LICENSEE_TYPES =
      List.of("SUPPLIER", "DISTRIBUTOR", "AGENT", "SELF");

  /** 默认「临期」窗口：剩余天数 ≤ 30 天算临期。 */
  public static final int DEFAULT_EXPIRING_WINDOW_DAYS = 30;

  /**
   * 🔴 当前判定规则的版本号。
   *
   * <p>卷首约束第二条：「规则要有生效时间与版本，规则变更不能追溯改变历史判定」。
   * 本模块的判定规则口径改动时，把这个常量往前推一格 ——
   * 新判定会写下新的版本号，而**旧的判定记录保持原样**（追加式，不 UPDATE）。
   */
  public static final long CURRENT_RULE_VERSION = 1L;

  // ---------------------------------------------------------------- 原因码

  /** 授权已停用。 */
  public static final String REASON_DISABLED = "AUTHORIZATION_DISABLED";
  /** 授权尚未生效。 */
  public static final String REASON_NOT_YET_EFFECTIVE = "AUTHORIZATION_NOT_YET_EFFECTIVE";
  /** 授权已过期。 */
  public static final String REASON_EXPIRED = "AUTHORIZATION_EXPIRED";
  /** 授权不覆盖该店铺。 */
  public static final String REASON_SHOP_NOT_IN_SCOPE = "SHOP_NOT_IN_SCOPE";
  /** 授权不覆盖该国家。 */
  public static final String REASON_COUNTRY_NOT_IN_SCOPE = "COUNTRY_NOT_IN_SCOPE";
  /** 授权不覆盖该类目。 */
  public static final String REASON_CATEGORY_NOT_IN_SCOPE = "CATEGORY_NOT_IN_SCOPE";
  /** 店铺维度判不了（没给店铺）。 */
  public static final String REASON_SHOP_UNRESOLVED = "SHOP_UNRESOLVED";
  /** 国家维度判不了（没给国家，且店铺没有可解析的国家）。 */
  public static final String REASON_COUNTRY_UNRESOLVED = "COUNTRY_UNRESOLVED";
  /** 类目维度判不了（没给类目）。 */
  public static final String REASON_CATEGORY_UNRESOLVED = "CATEGORY_UNRESOLVED";
  /** 本租户下该品牌没有任何授权档案。 */
  public static final String REASON_NO_AUTHORIZATION = "NO_AUTHORIZATION";

  // ---------------------------------------------------------------- 词表文案

  /** 结论的人话。 */
  public static String conclusionLabel(String conclusion) {
    return switch (conclusion) {
      case "IN_SCOPE" -> "在授权范围内";
      case "OUT_OF_SCOPE" -> "不在授权范围内";
      case "EXPIRED" -> "授权已过期";
      case "NO_AUTHORIZATION" -> "没有授权档案";
      case "UNKNOWN" -> "判不了（上下文不全）";
      default -> conclusion;
    };
  }

  /** 分级的人话。 */
  public static String severityLabel(String severity) {
    return switch (severity) {
      case "BLOCKING" -> "阻断刊登";
      case "REVIEW" -> "需人工复核";
      case "NONE" -> "通过";
      default -> severity;
    };
  }

  /** 到期状态的人话。 */
  public static String expiryStateLabel(String state) {
    return switch (state) {
      case "ACTIVE" -> "生效中";
      case "EXPIRING" -> "即将到期";
      case "EXPIRED" -> "已过期";
      case "NOT_YET_EFFECTIVE" -> "尚未生效";
      case "DISABLED" -> "已停用";
      default -> state;
    };
  }

  /** 被授权主体类型的人话。 */
  public static String licenseeTypeLabel(String type) {
    return switch (type) {
      case "SUPPLIER" -> "供应商";
      case "DISTRIBUTOR" -> "经销商";
      case "AGENT" -> "代理商";
      case "SELF" -> "品牌自有主体";
      default -> type;
    };
  }

  // ---------------------------------------------------------------- 输入形状

  /**
   * 一条授权（判定用的最小事实集）。
   *
   * @param authorizationId 授权 id
   * @param label 授权标题（授权方 / 被授权主体）
   * @param enabled 是否启用
   * @param effectiveFrom 期限起点
   * @param effectiveTo 期限终点
   * @param scopes 范围（维度 → 取值集合）；某维度缺 key 或空集合 = 该维度不限制
   */
  public record AuthorizationFact(
      String authorizationId,
      String label,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      Map<String, Set<String>> scopes) {}

  /**
   * 一次刊登的上下文（判定用的最小事实集）。
   *
   * @param brandId 被校验的品牌
   * @param shopId 目标店铺（可空 = 没给店铺）
   * @param countryCode 调用方显式给的国家（可空）
   * @param categoryId 目标类目（可空）
   * @param shopCountries 店铺覆盖的国家集合（从店铺的启用站点还原；空 = 未解析出国家）
   */
  public record CheckContext(
      String brandId,
      String shopId,
      String countryCode,
      String categoryId,
      Set<String> shopCountries) {}

  /**
   * 判定结论。
   *
   * @param inScope 是否在授权范围内
   * @param severity 分级
   * @param conclusion 结论
   * @param matchedAuthorizationId 命中了哪条授权（IN_SCOPE 时非空）
   * @param reasons 逐条原因（**非 IN_SCOPE 时必非空**）
   */
  public record Evaluation(
      boolean inScope,
      String severity,
      String conclusion,
      String matchedAuthorizationId,
      List<BrandAuthorizationReason> reasons) {}

  /** 一条授权的单维度判定结果。 */
  private record DimensionOutcome(boolean failed, boolean unresolved, BrandAuthorizationReason reason) {}

  // ---------------------------------------------------------------- 核心判定

  /**
   * 🔴 判定一次刊登的品牌使用是否落在授权范围内。
   *
   * <p>算法（顺序即结论优先级）：
   *
   * <ol>
   *   <li>逐条授权评估：先看启用状态与期限，再逐维度看范围。
   *   <li>只要有<b>一条</b>授权在所有维度上都满足 ⇒ 结论 {@code IN_SCOPE}（并列出命中的那条）。
   *   <li>否则若有授权<b>只是判不了</b>（上下文缺维度）⇒ {@code UNKNOWN}：
   *       不放行，但也不谎称「越权」。
   *   <li>否则若所有失败原因里存在「已过期」⇒ {@code EXPIRED}。
   *   <li>否则 ⇒ {@code OUT_OF_SCOPE}。
   *   <li>一条授权都没有 ⇒ {@code NO_AUTHORIZATION}。
   * </ol>
   *
   * @param authorizations 该品牌在本租户下的全部授权（**已按有效期倒序排好**）
   * @param context 刊登上下文
   * @param now 判定时刻（**DB 时钟**取一次，同一事务内复用）
   */
  public static Evaluation evaluate(
      List<AuthorizationFact> authorizations, CheckContext context, OffsetDateTime now) {
    if (authorizations.isEmpty()) {
      return new Evaluation(
          false,
          "BLOCKING",
          "NO_AUTHORIZATION",
          null,
          List.of(
              new BrandAuthorizationReason(
                  REASON_NO_AUTHORIZATION,
                  "没有授权档案",
                  "本租户下这个品牌没有任何品牌授权档案，刊登该品牌前必须先录入授权。",
                  "BLOCKING",
                  null,
                  "")));
    }

    List<BrandAuthorizationReason> failures = new ArrayList<>();
    List<BrandAuthorizationReason> unresolved = new ArrayList<>();
    boolean sawExpired = false;

    for (AuthorizationFact authorization : authorizations) {
      List<DimensionOutcome> outcomes = new ArrayList<>();

      // ---- 状态与期限 ----
      if (!authorization.enabled()) {
        outcomes.add(
            blocking(
                REASON_DISABLED,
                "授权已停用",
                "授权「" + authorization.label() + "」已被停用，不参与覆盖判定。",
                authorization));
      }
      if (now.isBefore(authorization.effectiveFrom())) {
        outcomes.add(
            blocking(
                REASON_NOT_YET_EFFECTIVE,
                "授权尚未生效",
                "授权「"
                    + authorization.label()
                    + "」自 "
                    + authorization.effectiveFrom()
                    + " 起生效，当前尚未生效。",
                authorization));
      }
      if (now.isAfter(authorization.effectiveTo())) {
        sawExpired = true;
        outcomes.add(
            blocking(
                REASON_EXPIRED,
                "授权已过期",
                "授权「"
                    + authorization.label()
                    + "」已于 "
                    + authorization.effectiveTo()
                    + " 到期。",
                authorization));
      }

      // ---- 范围：店铺 / 国家 / 类目 ----
      outcomes.addAll(shopOutcome(authorization, context));
      outcomes.addAll(countryOutcome(authorization, context));
      outcomes.addAll(categoryOutcome(authorization, context));

      boolean failed = outcomes.stream().anyMatch(DimensionOutcome::failed);
      boolean undetermined = outcomes.stream().anyMatch(DimensionOutcome::unresolved);
      if (!failed && !undetermined) {
        return new Evaluation(
            true, "NONE", "IN_SCOPE", authorization.authorizationId(), List.of());
      }
      for (DimensionOutcome outcome : outcomes) {
        if (outcome.failed()) {
          failures.add(outcome.reason());
        } else if (outcome.unresolved()) {
          unresolved.add(outcome.reason());
        }
      }
    }

    if (!unresolved.isEmpty()) {
      List<BrandAuthorizationReason> reasons = new ArrayList<>(unresolved);
      reasons.addAll(failures);
      return new Evaluation(false, "REVIEW", "UNKNOWN", null, reasons);
    }
    String conclusion = sawExpired ? "EXPIRED" : "OUT_OF_SCOPE";
    return new Evaluation(false, "BLOCKING", conclusion, null, failures);
  }

  // ---------------------------------------------------------------- 维度判定

  private static List<DimensionOutcome> shopOutcome(
      AuthorizationFact authorization, CheckContext context) {
    Set<String> scope = scopeOf(authorization, "SHOP");
    if (scope.isEmpty()) {
      return List.of();
    }
    if (context.shopId() == null || context.shopId().isBlank()) {
      return List.of(
          unresolved(
              REASON_SHOP_UNRESOLVED,
              "店铺范围判不了",
              "授权「"
                  + authorization.label()
                  + "」限定了店铺范围，但这次校验没有指定店铺，无法判定是否覆盖。",
              authorization));
    }
    if (!scope.contains(context.shopId())) {
      return List.of(
          blocking(
              REASON_SHOP_NOT_IN_SCOPE,
              "不覆盖该店铺",
              "授权「" + authorization.label() + "」不覆盖店铺 " + context.shopId() + "。",
              authorization));
    }
    return List.of();
  }

  private static List<DimensionOutcome> countryOutcome(
      AuthorizationFact authorization, CheckContext context) {
    Set<String> scope = scopeOf(authorization, "COUNTRY");
    if (scope.isEmpty()) {
      return List.of();
    }
    Set<String> targets = targetCountries(context);
    if (targets.isEmpty()) {
      return List.of(
          unresolved(
              REASON_COUNTRY_UNRESOLVED,
              "国家范围判不了",
              "授权「"
                  + authorization.label()
                  + "」限定了国家范围，但这次校验没有指定国家、店铺也没有可解析的国家，无法判定是否覆盖。",
              authorization));
    }
    for (String target : targets) {
      if (!scope.contains(target)) {
        return List.of(
            blocking(
                REASON_COUNTRY_NOT_IN_SCOPE,
                "不覆盖该国家",
                "授权「" + authorization.label() + "」不覆盖国家 " + target + "。",
                authorization));
      }
    }
    return List.of();
  }

  private static List<DimensionOutcome> categoryOutcome(
      AuthorizationFact authorization, CheckContext context) {
    Set<String> scope = scopeOf(authorization, "CATEGORY");
    if (scope.isEmpty()) {
      return List.of();
    }
    if (context.categoryId() == null || context.categoryId().isBlank()) {
      return List.of(
          unresolved(
              REASON_CATEGORY_UNRESOLVED,
              "类目范围判不了",
              "授权「"
                  + authorization.label()
                  + "」限定了类目范围，但这次校验没有指定类目，无法判定是否覆盖。",
              authorization));
    }
    if (!scope.contains(context.categoryId())) {
      return List.of(
          blocking(
              REASON_CATEGORY_NOT_IN_SCOPE,
              "不覆盖该类目",
              "授权「" + authorization.label() + "」不覆盖类目 " + context.categoryId() + "。",
              authorization));
    }
    return List.of();
  }

  // ---------------------------------------------------------------- 辅助

  /**
   * 🔴 目标国家集合。
   *
   * <p>调用方显式给了国家就以它为准；没给就取店铺覆盖的国家。
   * 店铺同时在多个站点销售时，授权必须覆盖**全部**目标国家才算覆盖 ——
   * 只覆盖 DE 的授权不能让这家店在 FR 上继续用这个品牌。
   */
  static Set<String> targetCountries(CheckContext context) {
    if (context.countryCode() != null && !context.countryCode().isBlank()) {
      return new LinkedHashSet<>(List.of(context.countryCode().trim().toUpperCase()));
    }
    if (context.shopCountries() == null || context.shopCountries().isEmpty()) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String country : context.shopCountries()) {
      if (country != null && !country.isBlank()) {
        normalized.add(country.trim().toUpperCase());
      }
    }
    return normalized;
  }

  private static Set<String> scopeOf(AuthorizationFact authorization, String kind) {
    if (authorization.scopes() == null) {
      return Set.of();
    }
    Set<String> values = authorization.scopes().get(kind);
    return values == null ? Set.of() : values;
  }

  private static DimensionOutcome blocking(
      String code, String label, String message, AuthorizationFact authorization) {
    return new DimensionOutcome(
        true,
        false,
        new BrandAuthorizationReason(
            code, label, message, "BLOCKING", authorization.authorizationId(), authorization.label()));
  }

  private static DimensionOutcome unresolved(
      String code, String label, String message, AuthorizationFact authorization) {
    return new DimensionOutcome(
        false,
        true,
        new BrandAuthorizationReason(
            code, label, message, "REVIEW", authorization.authorizationId(), authorization.label()));
  }

  /** 把一组范围行（维度 → 取值）归并成判定用的形状。 */
  public static Map<String, Set<String>> groupScopes(List<BrandAuthorizationScopeLine> lines) {
    Map<String, Set<String>> grouped = new LinkedHashMap<>();
    for (BrandAuthorizationScopeLine line : lines) {
      grouped
          .computeIfAbsent(line.scopeKind(), key -> new LinkedHashSet<>())
          .add(line.scopeValue());
    }
    return grouped;
  }

  /**
   * 到期状态（<b>纯函数</b>，判定时刻由调用方给出）。
   *
   * @param enabled 是否启用
   * @param effectiveFrom 期限起点
   * @param effectiveTo 期限终点
   * @param daysToExpiry 剩余天数（负 = 已过期）
   * @param now 判定时刻
   * @param windowDays 临期窗口天数
   */
  public static String expiryState(
      boolean enabled,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      int daysToExpiry,
      OffsetDateTime now,
      int windowDays) {
    if (!enabled) {
      return "DISABLED";
    }
    if (now.isBefore(effectiveFrom)) {
      return "NOT_YET_EFFECTIVE";
    }
    if (now.isAfter(effectiveTo)) {
      return "EXPIRED";
    }
    return daysToExpiry <= windowDays ? "EXPIRING" : "ACTIVE";
  }
}
