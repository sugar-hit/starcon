package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 3.9.2.2 判定规则的纯函数测试。
 *
 * <p>重点钉死三件在实现里最容易做错的事：
 *
 * <ol>
 *   <li><b>未知不等于零</b>（Z1）：缺观测必须是 INCOMPLETE，绝不能折成 CANDIDATE。
 *   <li><b>违反优先于未知</b>：已观测到"有订单"的主体一定 NOT_ZOMBIE， 不能因为另一个维度缺观测就标成"无法判定"。
 *   <li><b>过期不沿用旧值</b>（Z7）：过期观测与没有观测同样判 UNKNOWN， 但两者在明细里必须**可区分**（stale=true + 有 observedAt）。
 * </ol>
 */
class ZombieCandidateRulesTest {

  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private static ZombieCandidateRules.Config config(
      int inactiveDays, int staleDays, boolean... dims) {
    var required = new EnumMap<ZombieMetric, Boolean>(ZombieMetric.class);
    var ordered = ZombieMetric.ordered();
    for (var metric : ordered) required.put(metric, false);
    for (int i = 0; i < dims.length; i++) required.put(ordered.get(i), dims[i]);
    return new ZombieCandidateRules.Config(
        ZombieEntityType.SKU, inactiveDays, staleDays, required, true, 1L);
  }

  private static ZombieCandidateRules.Observation at(BigDecimal value, long daysAgo) {
    return new ZombieCandidateRules.Observation(value, NOW.minusDays(daysAgo));
  }

  private static ZombieCandidateRules.Observation missing() {
    return new ZombieCandidateRules.Observation(null, null);
  }

  private static Map<ZombieMetric, ZombieCandidateRules.Observation> obs(
      ZombieCandidateRules.Observation... values) {
    var map = new EnumMap<ZombieMetric, ZombieCandidateRules.Observation>(ZombieMetric.class);
    var ordered = ZombieMetric.ordered();
    for (int i = 0; i < values.length; i++) map.put(ordered.get(i), values[i]);
    return map;
  }

  // ------------------------------------------------------------ Z2 时间

  @Test
  void 只看时间维度时超出阈值即候选() {
    var result =
        ZombieCandidateRules.evaluate(config(365, 90), "ACTIVE", NOW.minusDays(400), NOW, obs());
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.CANDIDATE);
    assertThat(result.reasons()).anyMatch(r -> r.contains("400"));
  }

  @Test
  void 仍在维护期内的主数据不是候选() {
    var result =
        ZombieCandidateRules.evaluate(config(365, 90), "ACTIVE", NOW.minusDays(10), NOW, obs());
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.NOT_ZOMBIE);
  }

  @Test
  void 恰好等于阈值仍是维护期内() {
    // 边界：days_since > inactiveDays 才算超期，等于不算。
    var result =
        ZombieCandidateRules.evaluate(config(365, 90), "ACTIVE", NOW.minusDays(365), NOW, obs());
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.NOT_ZOMBIE);
  }

  @Test
  void 从未维护的主体算超期() {
    var result = ZombieCandidateRules.evaluate(config(365, 90), "ACTIVE", null, NOW, obs());
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.CANDIDATE);
  }

  // ------------------------------------------------------------ Z1 未知不等于零

  @Test
  void 启用维度缺观测判无法判定而不是候选() {
    var result =
        ZombieCandidateRules.evaluate(
            config(365, 90, true), "ACTIVE", NOW.minusDays(400), NOW, obs(missing()));
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.INCOMPLETE);
    assertThat(result.verdict().actionable()).isFalse();
    var dimension = result.dimensions().get(0);
    assertThat(dimension.state()).isEqualTo(ZombieCandidateRules.ZombieDimensionStatus.UNKNOWN);
    assertThat(dimension.note()).contains("未知不等于零");
  }

  @Test
  void 未启用的维度缺观测不影响判定() {
    // order 关闭（缺观测无所谓）、inventory 开启且观测为 0 → 候选。
    var result =
        ZombieCandidateRules.evaluate(
            config(365, 90, false, true),
            "ACTIVE",
            NOW.minusDays(400),
            NOW,
            obs(missing(), at(BigDecimal.ZERO, 1)));
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.CANDIDATE);
    assertThat(result.dimensions().get(0).state())
        .isEqualTo(ZombieCandidateRules.ZombieDimensionStatus.NOT_REQUIRED);
    assertThat(result.dimensions().get(1).state())
        .isEqualTo(ZombieCandidateRules.ZombieDimensionStatus.SATISFIED);
  }

  // ------------------------------------------------------------ 违反优先于未知

  @Test
  void 已观测到非零时判在用_即使另一维度缺观测() {
    var result =
        ZombieCandidateRules.evaluate(
            config(365, 90, true, true),
            "ACTIVE",
            NOW.minusDays(400),
            NOW,
            obs(at(new BigDecimal("3"), 1), missing()));
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.NOT_ZOMBIE);
  }

  @Test
  void 全部启用维度观测到零时判候选() {
    var result =
        ZombieCandidateRules.evaluate(
            config(365, 90, true, true, true, true),
            "ACTIVE",
            NOW.minusDays(400),
            NOW,
            obs(
                at(BigDecimal.ZERO, 1), at(new BigDecimal("0.000000"), 1),
                at(BigDecimal.ZERO, 1), at(new BigDecimal("0"), 1)));
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.CANDIDATE);
    assertThat(result.dimensions())
        .allMatch(d -> d.state().equals(ZombieCandidateRules.ZombieDimensionStatus.SATISFIED));
  }

  // ------------------------------------------------------------ Z7 过期

  @Test
  void 过期观测判未知且与从未观测可区分() {
    var rule = config(365, 90, true);
    var expired =
        ZombieCandidateRules.evaluate(
            rule, "ACTIVE", NOW.minusDays(400), NOW, obs(at(BigDecimal.ZERO, 200)));
    assertThat(expired.verdict()).isEqualTo(ZombieVerdict.INCOMPLETE);
    var dimension = expired.dimensions().get(0);
    assertThat(dimension.stale()).isTrue();
    assertThat(dimension.observedAt()).isNotNull();
    assertThat(dimension.value()).isEqualByComparingTo(BigDecimal.ZERO);

    var never =
        ZombieCandidateRules.evaluate(rule, "ACTIVE", NOW.minusDays(400), NOW, obs(missing()));
    var neverDimension = never.dimensions().get(0);
    assertThat(neverDimension.stale()).isFalse();
    assertThat(neverDimension.observedAt()).isNull();
    // 关键：两者都是 UNKNOWN，但"过期"带着观测值与时刻，"从未观测"两者都没有。
    assertThat(neverDimension.value()).isNull();
  }

  @Test
  void 过期边界按天数判断() {
    assertThat(ZombieCandidateRules.isStale(NOW.minusDays(90), NOW, 90)).isFalse();
    assertThat(ZombieCandidateRules.isStale(NOW.minusDays(91), NOW, 90)).isTrue();
    assertThat(ZombieCandidateRules.isStale(null, NOW, 90)).isTrue();
  }

  // ------------------------------------------------------------ Z6 状态

  @Test
  void 已经停用的主体不是候选() {
    var result =
        ZombieCandidateRules.evaluate(
            config(365, 90, true),
            "INACTIVE",
            NOW.minusDays(400),
            NOW,
            obs(at(BigDecimal.ZERO, 1)));
    assertThat(result.verdict()).isEqualTo(ZombieVerdict.NOT_ZOMBIE);
    assertThat(result.reasons()).anyMatch(r -> r.contains("不是活跃状态"));
  }

  // ------------------------------------------------------------ Z4 批量准入

  @Test
  void 批量准入拒收非候选并逐条给出原因() {
    var admission =
        ZombieCandidateRules.admit(
            List.of(
                new ZombieCandidateRules.BatchEntry("S-1", "僵尸 A", ZombieVerdict.CANDIDATE),
                new ZombieCandidateRules.BatchEntry("S-2", "在用 B", ZombieVerdict.NOT_ZOMBIE),
                new ZombieCandidateRules.BatchEntry("S-3", "未知 C", ZombieVerdict.INCOMPLETE)));
    assertThat(admission.admissible()).isFalse();
    assertThat(admission.accepted()).hasSize(1);
    assertThat(admission.blocked()).hasSize(2);
    assertThat(admission.blocked().get(0).reason()).contains("在用");
    assertThat(admission.blocked().get(1).reason()).contains("未知不等于零");
  }

  @Test
  void 全部是候选时准入通过() {
    var admission =
        ZombieCandidateRules.admit(
            List.of(new ZombieCandidateRules.BatchEntry("S-1", "僵尸", ZombieVerdict.CANDIDATE)));
    assertThat(admission.admissible()).isTrue();
    assertThat(admission.blocked()).isEmpty();
  }

  @Test
  void 只有候选可停用() {
    assertThat(ZombieVerdict.CANDIDATE.actionable()).isTrue();
    assertThat(ZombieVerdict.NOT_ZOMBIE.actionable()).isFalse();
    assertThat(ZombieVerdict.INCOMPLETE.actionable()).isFalse();
  }

  // ------------------------------------------------------------ 默认规则

  @Test
  void 默认规则四个维度全关() {
    var defaults = ZombieCandidateRules.defaultConfig(ZombieEntityType.SKU);
    assertThat(defaults.requiredCount()).isZero();
    for (var metric : ZombieMetric.values()) assertThat(defaults.requires(metric)).isFalse();
    // 全关时判定退化成"只看时间"，必须是候选而不是恒定的 INCOMPLETE。
    assertThat(
            ZombieCandidateRules.evaluate(defaults, "ACTIVE", NOW.minusDays(400), NOW, obs())
                .verdict())
        .isEqualTo(ZombieVerdict.CANDIDATE);
  }

  @Test
  void 供应商与客户的维护阈值可分别配置() {
    assertThat(ZombieEntityType.SUPPLIER.partyBacked()).isTrue();
    assertThat(ZombieEntityType.CUSTOMER.partyBacked()).isTrue();
    assertThat(ZombieEntityType.SKU.partyBacked()).isFalse();
    assertThat(ZombieEntityType.SUPPLIER.table()).isEqualTo("party");
  }
}
