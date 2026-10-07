package com.starcon.catalog;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 3.9.2.2 僵尸主数据清理的判定规则（纯函数，无 IO）。
 *
 * <p>与 {@code SupplierQuoteRules} / {@code PartyRules} 同一形态：候选清单、概览统计、
 * 批量停用三处出口**全部**调这里，页面只把原因码翻成话、不重算。
 *
 * <p>实现的正是 {@code V39__zombie_master_data_cleanup.sql} 第八节钉死的 Z1~Z7：
 *
 * <ul>
 *   <li><b>Z1</b> 未知 ≠ 零：三态判定，缺观测判 {@link ZombieVerdict#INCOMPLETE}；
 *   <li><b>Z2</b> 「长期未维护」用 catalog 自有的 {@code updated_at}，四个引用维度一律走快照；
 *   <li><b>Z4</b> 批量停用拒收不可判定的条目；
 *   <li><b>Z6</b> 已经是非活跃状态的主体不重复停用；
 *   <li><b>Z7</b> 快照过期判 UNKNOWN 而不是沿用旧值，且过期信息**显式返回**。
 * </ul>
 */
public final class ZombieCandidateRules {

  private ZombieCandidateRules() {}

  /** 规则配置在本类里的投影（与 {@code master_data_cleanup_rule} 一一对应）。 */
  public record Config(
      ZombieEntityType entityType,
      int inactiveDays,
      int snapshotStaleDays,
      Map<ZombieMetric, Boolean> requiredDimensions,
      boolean enabled,
      long version) {

    public Config {
      requiredDimensions = Map.copyOf(requiredDimensions);
    }

    /** 规则是否启用了某个维度。 */
    public boolean requires(ZombieMetric metric) {
      return Boolean.TRUE.equals(requiredDimensions.get(metric));
    }

    /** 四个维度里启用了几个 —— 概览里"这条规则依赖几个上游"的那一格。 */
    public int requiredCount() {
      return (int) requiredDimensions.values().stream().filter(Boolean.TRUE::equals).count();
    }
  }

  /**
   * 一次引用观测。
   *
   * <p>{@code value} 与 {@code observedAt} 允许为 null 表示**没有观测** —— 那是快照表里
   * 缺行的情形（Z1），与"观测到 0"必须能在类型上区分开。
   */
  public record Observation(BigDecimal value, OffsetDateTime observedAt) {
    static Observation missing() {
      return new Observation(null, null);
    }

    public boolean present() {
      return value != null && observedAt != null;
    }
  }

  /** 单个维度的判定明细。界面逐条渲染"凭什么"用的就是它。 */
  public record DimensionStatus(
      ZombieMetric metric,
      String state,
      BigDecimal value,
      OffsetDateTime observedAt,
      boolean stale,
      String note) {

    /** 该维度是否让这条候选"无法判定"。 */
    public boolean unknown() {
      return "UNKNOWN".equals(state);
    }

    public static final String SATISFIED = "SATISFIED";
    public static final String VIOLATED = "VIOLATED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
  }

  /** 判定结果。 */
  public record Evaluation(
      ZombieVerdict verdict, List<DimensionStatus> dimensions, List<String> reasons) {}

  /**
   * 判定一条主数据。
   *
   * <p>顺序是刻意的，**先看违反再谈未知**：
   *
   * <ol>
   *   <li>Z6：主体不是活跃状态 → {@link ZombieVerdict#NOT_ZOMBIE}（结论确定，与引用维度无关）。
   *   <li>Z2：还在维护期内 → {@link ZombieVerdict#NOT_ZOMBIE}。这一步**必须在未知之前** ——
   *       一条上周刚改过的主数据不需要任何引用统计就能确定它不是僵尸，
   *       先报"无法判定"会让界面上刚维护完的那批 SKU 全部变灰。
   *   <li>任一启用维度被观测到且**非零** → {@link ZombieVerdict#NOT_ZOMBIE}，立即返回。
   *       同样先于未知：已经观测到"有 3 个订单"的主数据，它的其它维度有没有观测
   *       根本不影响结论，先报"无法判定"会让界面把一个确凿无疑的非僵尸标成灰的。
   *   <li>任一启用维度**缺观测或过期** → {@link ZombieVerdict#INCOMPLETE}。
   *   <li>全部启用维度都是已观测到的零 → {@link ZombieVerdict#CANDIDATE}。
   * </ol>
   *
   * @param lastMaintainedAt 主体最后一次维护时刻（{@code sku.updated_at} / {@code party.updated_at}）；
   *     null 视为"从未维护"（按 updated_at 取不到只有一种情况：直连 SQL 绕过服务层写的行）
   */
  public static Evaluation evaluate(
      Config rule,
      String currentStatus,
      OffsetDateTime lastMaintainedAt,
      OffsetDateTime now,
      Map<ZombieMetric, Observation> observations) {

    var dimensions = new ArrayList<DimensionStatus>(ZombieMetric.values().length);
    var reasons = new ArrayList<String>();

    // Z6：已经是非活跃状态的主体不是候选。这里只说明原因并给出 NOT_ZOMBIE ——
    // 刻意**不**在这里报 INCOMPLETE：它的判定结论是确定的（不需要再停用），
    // 只是这个结论与引用维度无关。
    if (!rule.entityType().activeStatus().equals(currentStatus)) {
      dimensions.add(
          new DimensionStatus(
              null,
              DimensionStatus.NOT_REQUIRED,
              null,
              lastMaintainedAt,
              false,
              "当前状态为 " + currentStatus + "，不是待清理的 " + rule.entityType().activeStatus()));
      return new Evaluation(
          ZombieVerdict.NOT_ZOMBIE, List.copyOf(dimensions), List.of("主体当前不是活跃状态，无需停用"));
    }

    // Z2：长期未维护。四个引用维度全关时，这一条就是整个判定。
    long daysSince = daysSince(lastMaintainedAt, now);
    boolean staleByTime = lastMaintainedAt == null || daysSince > rule.inactiveDays();

    // Z2 先行返回：还在维护期内的主数据不需要任何引用统计就能确定它不是僵尸。
    if (!staleByTime) {
      dimensions.add(
          new DimensionStatus(
              null,
              DimensionStatus.SATISFIED,
              null,
              lastMaintainedAt,
              false,
              "距最后维护 " + daysSince + " 天，未超过阈值 " + rule.inactiveDays() + " 天"));
      return new Evaluation(
          ZombieVerdict.NOT_ZOMBIE,
          List.copyOf(dimensions),
          List.of("距最后维护 " + daysSince + " 天，仍在 " + rule.inactiveDays() + " 天维护期内"));
    }

    boolean violated = false;
    boolean unknown = false;

    for (var metric : ZombieMetric.ordered()) {
      if (!rule.requires(metric)) {
        // 未启用也要把**已观测到的值显示出来**：运营正是靠这一格判断"要不要把这个维度打开"，
        // 把它藏起来的话，界面对"接入了哪些上游"就完全没有任何提示，
        // 于是每个维度只能靠猜着开、开着发现全是"无法判定"再关掉。
        var observedButUnused = observations.getOrDefault(metric, Observation.missing());
        dimensions.add(
            new DimensionStatus(
                metric,
                DimensionStatus.NOT_REQUIRED,
                observedButUnused.value(),
                observedButUnused.observedAt(),
                false,
                observedButUnused.present()
                    ? "规则未启用该维度，但上游已有观测值（可作为是否启用的参考）"
                    : "规则未启用该维度，且上游尚未推送观测"));
        continue;
      }
      var observation =
          observations.getOrDefault(metric, Observation.missing());
      if (!observation.present()) {
        unknown = true;
        dimensions.add(
            new DimensionStatus(
                metric,
                DimensionStatus.UNKNOWN,
                null,
                null,
                false,
                "上游 " + metric.owner() + " 尚未推送该维度的观测值（未知不等于零）"));
        continue;
      }
      boolean isStale = isStale(observation.observedAt(), now, rule.snapshotStaleDays());
      if (isStale) {
        unknown = true;
        dimensions.add(
            new DimensionStatus(
                metric,
                DimensionStatus.UNKNOWN,
                observation.value(),
                observation.observedAt(),
                true,
                "观测值已过期（超过 " + rule.snapshotStaleDays() + " 天），不能作为判定依据"));
        continue;
      }
      boolean isZero = observation.value().compareTo(ZombieMetric.zero()) == 0;
      if (isZero) {
        dimensions.add(
            new DimensionStatus(
                metric,
                DimensionStatus.SATISFIED,
                observation.value(),
                observation.observedAt(),
                false,
                metric.label() + "为 0"));
      } else {
        violated = true;
        dimensions.add(
            new DimensionStatus(
                metric,
                DimensionStatus.VIOLATED,
                observation.value(),
                observation.observedAt(),
                false,
                metric.label() + "为 " + observation.value().toPlainString() + "，这条主数据有人在用"));
      }
    }

    // 违反优先于未知（见本方法 javadoc 的顺序说明）。
    if (violated) {
      return new Evaluation(ZombieVerdict.NOT_ZOMBIE, List.copyOf(dimensions), List.copyOf(reasons));
    }
    if (unknown) {
      reasons.add("启用的引用维度缺少有效的上游观测，无法判定");
      return new Evaluation(ZombieVerdict.INCOMPLETE, List.copyOf(dimensions), List.copyOf(reasons));
    }

    reasons.add(
        "已 "
            + daysSince
            + " 天未维护（阈值 "
            + rule.inactiveDays()
            + " 天）"
            + (rule.requiredCount() > 0 ? "，且启用的引用维度均为 0" : "（规则未启用引用维度）"));
    return new Evaluation(ZombieVerdict.CANDIDATE, List.copyOf(dimensions), List.copyOf(reasons));
  }

  /**
   * 批量停用前的准入判定（规则 Z4）。
   *
   * <p>返回的 {@code blocked} 逐条给出原因，服务层据此 409。**刻意整批拒收而不是跳过**：
   * "跳过"会让运营以为这一批都处理完了，而被跳过的那几条不会有人回来处理。
   */
  public record BatchEntry(String code, String name, ZombieVerdict verdict) {}

  public record BlockedEntry(String code, String name, String reason) {}

  public record BatchAdmission(List<BatchEntry> accepted, List<BlockedEntry> blocked) {
    public boolean admissible() {
      return blocked.isEmpty();
    }
  }

  /** 准入判定：只有 {@link ZombieVerdict#CANDIDATE} 通过，其余逐条给出可读原因。 */
  public static BatchAdmission admit(List<BatchEntry> entries) {
    var accepted = new ArrayList<BatchEntry>(entries.size());
    var blocked = new ArrayList<BlockedEntry>();
    for (var entry : entries) {
      if (entry.verdict().actionable()) {
        accepted.add(entry);
      } else {
        blocked.add(
            new BlockedEntry(
                entry.code(),
                entry.name(),
                switch (entry.verdict()) {
                  case NOT_ZOMBIE -> "判定为在用或有订单/库存/刊登/成本引用，不属于僵尸";
                  case INCOMPLETE -> "引用维度缺少有效观测，无法判定（未知不等于零）";
                  case CANDIDATE -> "";
                }));
      }
    }
    return new BatchAdmission(List.copyOf(accepted), List.copyOf(blocked));
  }

  /**
   * 快照是否已过期（规则 Z7）。
   *
   * <p>过期判 UNKNOWN 而不是沿用旧值：沿用意味着"三个月前没人引用它"被当成
   * "现在也没人引用它"。
   */
  public static boolean isStale(OffsetDateTime observedAt, OffsetDateTime now, int staleDays) {
    if (observedAt == null) return true;
    return Duration.between(observedAt, now).toDays() > staleDays;
  }

  /** 距今天数。{@code lastMaintainedAt} 为 null 时返回 -1（从未维护）。 */
  public static long daysSince(OffsetDateTime lastMaintainedAt, OffsetDateTime now) {
    if (lastMaintainedAt == null) return -1L;
    return Duration.between(lastMaintainedAt, now).toDays();
  }

  /** 四个维度全关的默认规则（概览在某个类型还没配过时用它占位，不落库）。 */
  public static Config defaultConfig(ZombieEntityType type) {
    var required = new EnumMap<ZombieMetric, Boolean>(ZombieMetric.class);
    for (var m : ZombieMetric.values()) required.put(m, false);
    return new Config(type, 365, 90, required, true, 0L);
  }
}