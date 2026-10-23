package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.SyncHealthModels.SyncHealthReason;
import com.starcon.channel.SyncHealthRules.SyncHealthEvaluation;
import com.starcon.channel.SyncHealthRules.SyncHealthFacts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 1.5.1.1 同步与 API 健康的<b>红黄绿规则层</b>单测。
 *
 * <p>🔴 本组用例守的是「红黄绿必须显式、可审阅、可测」这一条：
 * 规则表里的**每一条规则各一条断言**（枚举值 → 严重度 → 依据），
 * 少一条就说明那条规则没人管 —— 而规则恰恰是运营判断红黄绿的唯一依据。
 *
 * <p>另外两条：
 *
 * <ul>
 *   <li><b>「读不到」不判成「没有」</b>：同步维度不可见时，依赖它的规则**一条都不判</b>
 *       （尤其不能判成「从来没有同步过」—— 那是彻头彻尾的撒谎）。</li>
 *   <li><b>规则表与实现一致</b>：能力声明吐出去的规则表必须覆盖代码里真正会触发的规则。</li>
 * </ul>
 */
class SyncHealthRulesTest {

  static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZoneOffset.UTC);

  /** 造一份「一切正常」的事实。 */
  static SyncHealthFacts facts() {
    return new SyncHealthFacts(
        "ACTIVE",
        false,
        NOW.minusHours(1),
        NOW.minusHours(1),
        NOW,
        "SUCCEEDED",
        Map.of(),
        Map.of());
  }

  static SyncHealthEvaluation evaluate(SyncHealthFacts facts) {
    return SyncHealthRules.evaluate(facts, true);
  }

  /** 取规则码列表。 */
  static List<String> codes(List<SyncHealthReason> reasons) {
    return reasons.stream().map(SyncHealthReason::code).toList();
  }

  // ==================================================================
  // 逐条规则
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 EXPIRED ⇒ 红")
  void expiredConnectionIsRed() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "EXPIRED", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "SUCCEEDED", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_CONNECTION_INVALID);
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 REVOKED ⇒ 红")
  void revokedConnectionIsRed() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "REVOKED", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "SUCCEEDED", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_CONNECTION_INVALID);
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 EXPIRING ⇒ 黄")
  void expiringConnectionIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "EXPIRING", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "SUCCEEDED", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_CONNECTION_EXPIRING);
  }

  @Test
  @DisplayName("1.5.1.1 规则：连接状态 PAUSED ⇒ 黄")
  void pausedConnectionIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "PAUSED", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "SUCCEEDED", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_CONNECTION_PAUSED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：sync_paused=true ⇒ 黄")
  void syncPausedIsYellow() {
    SyncHealthFacts base = facts();
    SyncHealthFacts facts =
        new SyncHealthFacts(
            base.connectionStatus(),
            true,
            base.lastSyncAt(),
            base.effectiveLastSyncAt(),
            base.evaluatedAt(),
            base.lastRunStatus(),
            base.failureCountsWindow(),
            base.failureCountsTotal());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_SYNC_PAUSED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：从来没有同步过 ⇒ 黄（不是绿，也不是红）")
  void neverSyncedIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts("ACTIVE", false, null, null, NOW, null, Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_NEVER_SYNCED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：超过预警线没同步 ⇒ 黄")
  void staleSyncIsYellow() {
    int hours = SyncHealthRules.STALE_SYNC_WARN_HOURS + 1;
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(hours),
            NOW.minusHours(hours),
            NOW,
            "SUCCEEDED",
            Map.of(),
            Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_SYNC_STALE_WARNING);
    assertThat(codes(result.reasons())).doesNotContain(SyncHealthRules.RULE_SYNC_STALE_CRITICAL);
  }

  @Test
  @DisplayName("1.5.1.1 规则：超过红线没同步 ⇒ 红")
  void staleSyncIsRed() {
    int hours = SyncHealthRules.STALE_SYNC_CRITICAL_HOURS + 1;
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(hours),
            NOW.minusHours(hours),
            NOW,
            "SUCCEEDED",
            Map.of(),
            Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_SYNC_STALE_CRITICAL);
  }

  @Test
  @DisplayName("1.5.1.1 规则：预警线与红线都恰好不超过时不触发停滞规则")
  void staleSyncBelowThresholdIsGreen() {
    int hours = SyncHealthRules.STALE_SYNC_WARN_HOURS;
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(hours),
            NOW.minusHours(hours),
            NOW,
            "SUCCEEDED",
            Map.of(),
            Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(codes(result.reasons())).doesNotContain(SyncHealthRules.RULE_SYNC_STALE_WARNING);
    assertThat(codes(result.reasons())).doesNotContain(SyncHealthRules.RULE_SYNC_STALE_CRITICAL);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_GREEN);
  }

  @Test
  @DisplayName("1.5.1.1 规则：最近一次运行 FAILED ⇒ 红")
  void lastRunFailedIsRed() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "FAILED", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_LAST_RUN_FAILED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：最近一次运行 PARTIAL ⇒ 黄")
  void lastRunPartialIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE", false, NOW.minusHours(1), NOW.minusHours(1), NOW, "PARTIAL", Map.of(), Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_LAST_RUN_INCOMPLETE);
  }

  @Test
  @DisplayName("1.5.1.1 规则：最近一次运行 CANCELLED ⇒ 黄")
  void lastRunCancelledIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(1),
            NOW.minusHours(1),
            NOW,
            "CANCELLED",
            Map.of(),
            Map.of());
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_LAST_RUN_INCOMPLETE);
  }

  @Test
  @DisplayName("1.5.1.1 规则：窗口内授权失效失败 ⇒ 红")
  void authorizationExpiredFailureIsRed() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(1),
            NOW.minusHours(1),
            NOW,
            "SUCCEEDED",
            Map.of("AUTHORIZATION_EXPIRED", 3L),
            Map.of("AUTHORIZATION_EXPIRED", 3L));
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_FAILURE_AUTHORIZATION_EXPIRED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：窗口内命中限流 ⇒ 黄（不是红）")
  void rateLimitedIsYellowNotRed() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(1),
            NOW.minusHours(1),
            NOW,
            "SUCCEEDED",
            Map.of("RATE_LIMITED", 7L),
            Map.of("RATE_LIMITED", 7L));
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_FAILURE_RATE_LIMITED);
    assertThat(codes(result.reasons())).doesNotContain(SyncHealthRules.RULE_FAILURE_AUTHORIZATION_EXPIRED);
  }

  @Test
  @DisplayName("1.5.1.1 规则：窗口内其它失败 ⇒ 黄")
  void otherFailureIsYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            NOW.minusHours(1),
            NOW.minusHours(1),
            NOW,
            "SUCCEEDED",
            Map.of("NETWORK_ERROR", 2L),
            Map.of("NETWORK_ERROR", 2L));
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_YELLOW);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_FAILURE_OTHER);
  }

  @Test
  @DisplayName("1.5.1.1 规则：一条都没命中 ⇒ 绿，且给一条 ALL_CLEAR 依据")
  void allClearIsGreen() {
    SyncHealthEvaluation result = evaluate(facts());
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_GREEN);
    assertThat(codes(result.reasons())).containsExactly(SyncHealthRules.RULE_ALL_CLEAR);
  }

  @Test
  @DisplayName("1.5.1.1 红优先于黄：命中红规则时整体是红")
  void redWinsOverYellow() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "EXPIRING",
            true,
            NOW.minusHours(1),
            NOW.minusHours(1),
            NOW,
            "FAILED",
            Map.of("RATE_LIMITED", 1L),
            Map.of("RATE_LIMITED", 1L));
    SyncHealthEvaluation result = evaluate(facts);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
  }

  // ==================================================================
  // 「读不到」≠「没有」
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 同步维度读不到时：依赖它的规则一条都不判（尤其不判「从来没有同步过」）")
  void invisibleSyncFactsAreNotJudged() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "ACTIVE",
            false,
            null,
            null,
            NOW,
            null,
            Map.of(),
            Map.of());
    SyncHealthEvaluation result = SyncHealthRules.evaluate(facts, false);
    assertThat(codes(result.reasons()))
        .as("读不到的时候判「从来没有同步过」是一次彻头彻尾的撒谎")
        .doesNotContain(SyncHealthRules.RULE_NEVER_SYNCED);
    assertThat(codes(result.reasons()))
        .doesNotContain(SyncHealthRules.RULE_LAST_RUN_FAILED);
    assertThat(codes(result.reasons()))
        .doesNotContain(SyncHealthRules.RULE_FAILURE_RATE_LIMITED);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_GREEN);
  }

  @Test
  @DisplayName("1.5.1.1 同步维度读不到时：连接状态仍然可判（连接级事实不依赖同步维度）")
  void invisibleSyncFactsStillJudgeConnectionStatus() {
    SyncHealthFacts facts =
        new SyncHealthFacts(
            "EXPIRED", false, null, null, NOW, null, Map.of(), Map.of());
    SyncHealthEvaluation result = SyncHealthRules.evaluate(facts, false);
    assertThat(result.severity()).isEqualTo(SyncHealthRules.SEVERITY_RED);
    assertThat(codes(result.reasons())).contains(SyncHealthRules.RULE_CONNECTION_INVALID);
  }

  // ==================================================================
  // 规则表与实现一致
  // ==================================================================

  @Test
  @DisplayName("1.5.1.1 能力声明的规则表覆盖每一条会被触发的规则")
  void ruleTableCoversEveryTriggeredRule() {
    List<String> declared =
        SyncHealthRules.ruleTable().stream()
            .map(SyncHealthModels.SyncHealthRuleDoc::code)
            .toList();
    assertThat(declared)
        .contains(
            SyncHealthRules.RULE_CONNECTION_INVALID,
            SyncHealthRules.RULE_CONNECTION_EXPIRING,
            SyncHealthRules.RULE_CONNECTION_PAUSED,
            SyncHealthRules.RULE_SYNC_PAUSED,
            SyncHealthRules.RULE_NEVER_SYNCED,
            SyncHealthRules.RULE_SYNC_STALE_CRITICAL,
            SyncHealthRules.RULE_SYNC_STALE_WARNING,
            SyncHealthRules.RULE_LAST_RUN_FAILED,
            SyncHealthRules.RULE_LAST_RUN_INCOMPLETE,
            SyncHealthRules.RULE_FAILURE_AUTHORIZATION_EXPIRED,
            SyncHealthRules.RULE_FAILURE_RATE_LIMITED,
            SyncHealthRules.RULE_FAILURE_OTHER,
            SyncHealthRules.RULE_ALL_CLEAR);
  }

  @Test
  @DisplayName("1.5.1.1 每条规则都有人话理由与事实来源（可审阅）")
  void everyRuleHasReasonAndSource() {
    for (SyncHealthModels.SyncHealthRuleDoc rule : SyncHealthRules.ruleTable()) {
      assertThat(rule.condition()).as("%s 的命中条件", rule.code()).isNotBlank();
      assertThat(rule.reason()).as("%s 的人话理由", rule.code()).isNotBlank();
      assertThat(rule.source()).as("%s 的事实来源", rule.code()).isNotBlank();
      assertThat(rule.severity())
          .as("%s 的严重度", rule.code())
          .isIn(SyncHealthRules.SEVERITY_LEVELS);
    }
  }

  @Test
  @DisplayName("1.5.1.1 阈值：红线比预警线大，窗口是 24 小时")
  void thresholdsAreCoherent() {
    assertThat(SyncHealthRules.STALE_SYNC_CRITICAL_HOURS)
        .isGreaterThan(SyncHealthRules.STALE_SYNC_WARN_HOURS);
    assertThat(SyncHealthRules.FAILURE_WINDOW_HOURS).isEqualTo(24);
    SyncHealthModels.SyncHealthThresholds thresholds = SyncHealthRules.thresholds();
    assertThat(thresholds.staleSyncWarnHours()).isEqualTo(SyncHealthRules.STALE_SYNC_WARN_HOURS);
    assertThat(thresholds.staleSyncCriticalHours()).isEqualTo(SyncHealthRules.STALE_SYNC_CRITICAL_HOURS);
    assertThat(thresholds.failureWindowHours()).isEqualTo(SyncHealthRules.FAILURE_WINDOW_HOURS);
  }

  @Test
  @DisplayName("1.5.1.1 最近同步时刻的来源：连接级声明值优先，其次运行级，都没有才是 NONE")
  void effectiveLastSyncAtPrefersDeclaredValue() {
    OffsetDateTime declared = NOW.minusDays(2);
    OffsetDateTime finished = NOW.minusHours(1);
    Object[] withDeclared = SyncHealthRules.effectiveLastSyncAt(declared, finished);
    assertThat(withDeclared[0]).isEqualTo(declared);
    assertThat(withDeclared[1]).isEqualTo(SyncHealthRules.SOURCE_CONNECTION_DECLARED);

    Object[] withRun = SyncHealthRules.effectiveLastSyncAt(null, finished);
    assertThat(withRun[0]).isEqualTo(finished);
    assertThat(withRun[1]).isEqualTo(SyncHealthRules.SOURCE_RUN_FINISHED);

    Object[] none = SyncHealthRules.effectiveLastSyncAt(null, null);
    assertThat(none[0]).isNull();
    assertThat(none[1]).isEqualTo(SyncHealthRules.SOURCE_NONE);
  }
}
