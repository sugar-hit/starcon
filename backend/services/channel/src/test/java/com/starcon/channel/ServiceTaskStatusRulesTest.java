package com.starcon.channel;

import static com.starcon.channel.ServiceTaskStatusRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.channel.ServiceTaskStatusRules.ServiceTaskStatusEvaluation;
import com.starcon.channel.ServiceTaskStatusRules.ServiceTaskStatusFacts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 1.5.2.1 服务与任务状态的<b>规则层</b>单测。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>失败原因到分类的映射逐值断言</b>（六个取值一个不漏）——
 *       「系统故障 vs 业务数据问题」是这个模块存在的理由，分类错了整个模块就没了；</li>
 *   <li><b>「停滞」的阈值边界逐个断言</b>（等于阈值**不算**停滞，超过才算）；</li>
 *   <li><b>「判不了」是一个独立结论</b>，不是「没卡住」；</li>
 *   <li><b>停滞与失败是两个不同的规则码</b>，不会互相盖掉；</li>
 *   <li><b>规则表与实现一致</b>：表里写的条件必须与代码用的阈值是同一组数字。</li>
 * </ol>
 */
class ServiceTaskStatusRulesTest {

  private static final int WARN = 30;
  private static final int CRITICAL = 120;

  /** 便捷构造：默认「没有失败分类」。 */
  private static ServiceTaskStatusEvaluation eval(String state, Long elapsed, boolean inFlight) {
    return ServiceTaskStatusRules.evaluate(
        new ServiceTaskStatusFacts(state, elapsed, inFlight, FAILURE_CLASS_NONE),
        WARN,
        CRITICAL);
  }

  // ================================================================
  // 失败分类
  // ================================================================

  @Test
  @DisplayName("六个失败原因逐值归类：业务三类 / 系统三类")
  void reasonsAreClassified() {
    assertThat(classifyReason("AUTHORIZATION_EXPIRED")).isEqualTo(FAILURE_CLASS_BUSINESS);
    assertThat(classifyReason("MAPPING_MISSING")).isEqualTo(FAILURE_CLASS_BUSINESS);
    assertThat(classifyReason("FIELD_PARSE_FAILED")).isEqualTo(FAILURE_CLASS_BUSINESS);
    assertThat(classifyReason("NETWORK_ERROR")).isEqualTo(FAILURE_CLASS_SYSTEM);
    assertThat(classifyReason("RATE_LIMITED")).isEqualTo(FAILURE_CLASS_SYSTEM);
    assertThat(classifyReason("PLATFORM_ERROR")).isEqualTo(FAILURE_CLASS_SYSTEM);
  }

  @Test
  @DisplayName("分类表与词表互为补集：六个取值一个不落、一个不多")
  void classificationCoversEveryReason() {
    assertThat(BUSINESS_FAILURE_REASONS).hasSize(3);
    assertThat(SYSTEM_FAILURE_REASONS).hasSize(3);
    assertThat(FAILURE_REASONS).hasSize(6);
    assertThat(List.copyOf(BUSINESS_FAILURE_REASONS).stream().toList())
        .doesNotContainAnyElementsOf(SYSTEM_FAILURE_REASONS);
    for (String reason : FAILURE_REASONS) {
      assertThat(classifyReason(reason)).as(reason + " 必须能归类").isNotNull();
    }
  }

  @Test
  @DisplayName("词表之外的原因码归类为 null（不是「系统故障」）")
  void unknownReasonIsNotSilentlySystem() {
    assertThat(classifyReason("SOMETHING_ELSE")).isNull();
    assertThat(classifyReason("")).isNull();
    assertThat(classifyReason(null)).isNull();
  }

  // ================================================================
  // 停滞
  // ================================================================

  @Test
  @DisplayName("停滞红线：超过红线 ⇒ 红 + STALL_CRITICAL")
  void stalledBeyondCritical() {
    ServiceTaskStatusEvaluation evaluation = eval(STATE_RUNNING, 121L, true);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_RED);
    assertThat(evaluation.stalled()).isTrue();
    assertThat(evaluation.stallSeverity()).isEqualTo(SEVERITY_RED);
    assertThat(evaluation.reasons()).extracting(r -> r.code()).contains(RULE_STALL_CRITICAL);
  }

  @Test
  @DisplayName("停滞预警：超过预警线但未过红线 ⇒ 黄 + STALL_WARNING")
  void stalledBeyondWarning() {
    ServiceTaskStatusEvaluation evaluation = eval(STATE_RUNNING, 31L, true);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_YELLOW);
    assertThat(evaluation.stalled()).isTrue();
    assertThat(evaluation.stallSeverity()).isEqualTo(SEVERITY_YELLOW);
    assertThat(evaluation.reasons()).extracting(r -> r.code()).contains(RULE_STALL_WARNING);
  }

  @Test
  @DisplayName("阈值边界：判据是「超过」—— 恰好等于预警线**不算**停滞，恰好等于红线算黄不算红")
  void thresholdIsExclusive() {
    assertThat(eval(STATE_RUNNING, (long) WARN, true).stalled()).isFalse();
    assertThat(eval(STATE_RUNNING, (long) WARN + 1, true).stalled()).isTrue();
    // 🔴 恰好等于红线：还是「停滞」，但严重度是黄 —— 红线的判据是「超过」。
    assertThat(eval(STATE_RUNNING, (long) CRITICAL, true).stalled()).isTrue();
    assertThat(eval(STATE_RUNNING, (long) CRITICAL, true).stallSeverity())
        .isEqualTo(SEVERITY_YELLOW);
    assertThat(eval(STATE_RUNNING, (long) CRITICAL + 1, true).stalled()).isTrue();
    assertThat(eval(STATE_RUNNING, (long) CRITICAL + 1, true).stallSeverity())
        .isEqualTo(SEVERITY_RED);
  }

  @Test
  @DisplayName("在跑但拿不到开始时刻 ⇒ 判不了（黄），**不是**「没卡住」")
  void undeterminableIsNotHealthy() {
    ServiceTaskStatusEvaluation evaluation = eval(STATE_RUNNING, null, true);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_YELLOW);
    assertThat(evaluation.stalled()).isFalse();
    assertThat(evaluation.stallDeterminable()).isFalse();
    assertThat(evaluation.reasons()).extracting(r -> r.code()).contains(RULE_STALL_UNDETERMINED);
    assertThat(evaluation.reasons()).extracting(r -> r.code()).doesNotContain(RULE_TASK_RUNNING);
  }

  @Test
  @DisplayName("在跑且没超时 ⇒ 绿 + TASK_RUNNING")
  void runningWithinThreshold() {
    ServiceTaskStatusEvaluation evaluation = eval(STATE_RUNNING, 5L, true);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_GREEN);
    assertThat(evaluation.stalled()).isFalse();
    assertThat(evaluation.stallDeterminable()).isTrue();
    assertThat(evaluation.reasons()).extracting(r -> r.code()).contains(RULE_TASK_RUNNING);
  }

  // ================================================================
  // 失败
  // ================================================================

  @Test
  @DisplayName("失败 ⇒ 红 + TASK_FAILED，且不会同时挂上一条绿")
  void failedIsRedAndNotAlsoGreen() {
    ServiceTaskStatusEvaluation evaluation =
        ServiceTaskStatusRules.evaluate(
            new ServiceTaskStatusFacts(STATE_FAILED, null, false, FAILURE_CLASS_BUSINESS),
            WARN,
            CRITICAL);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_RED);
    assertThat(evaluation.reasons()).extracting(r -> r.code()).containsExactly(RULE_TASK_FAILED);
    assertThat(evaluation.reasons()).noneMatch(r -> SEVERITY_GREEN.equals(r.severity()));
  }

  @Test
  @DisplayName("失败依据按分类分四种说法：业务 / 系统 / 两类 / 判不了")
  void failedDetailReflectsFailureClass() {
    assertThat(detailOf(FAILURE_CLASS_BUSINESS)).contains("业务 / 配置问题");
    assertThat(detailOf(FAILURE_CLASS_SYSTEM)).contains("系统 / 外部问题");
    assertThat(detailOf(FAILURE_CLASS_MIXED)).contains("两类都有");
    assertThat(detailOf(FAILURE_CLASS_UNDETERMINED)).contains("判不了");
    assertThat(detailOf(FAILURE_CLASS_UNCLASSIFIED)).contains("判不了");
  }

  private static String detailOf(String failureClass) {
    return ServiceTaskStatusRules.evaluate(
            new ServiceTaskStatusFacts(STATE_FAILED, null, false, failureClass), WARN, CRITICAL)
        .reasons()
        .get(0)
        .detail();
  }

  @Test
  @DisplayName("停滞与失败是两个不同的规则码，不会互相盖掉")
  void stalledAndFailedAreDistinctRules() {
    assertThat(RULE_STALL_CRITICAL).isNotEqualTo(RULE_TASK_FAILED);
    assertThat(RULE_STALL_WARNING).isNotEqualTo(RULE_STALL_CRITICAL);
    assertThat(RULE_STALL_UNDETERMINED).isNotEqualTo(RULE_TASK_RUNNING);
  }

  // ================================================================
  // 其它状态
  // ================================================================

  @Test
  @DisplayName("PARTIAL ⇒ 黄；SUCCEEDED / CANCELLED / PENDING ⇒ 绿")
  void terminalStates() {
    assertThat(eval(STATE_PARTIAL, null, false).severity()).isEqualTo(SEVERITY_YELLOW);
    assertThat(eval(STATE_PARTIAL, null, false).reasons())
        .extracting(r -> r.code())
        .containsExactly(RULE_TASK_PARTIAL);
    assertThat(eval(STATE_SUCCEEDED, null, false).severity()).isEqualTo(SEVERITY_GREEN);
    assertThat(eval(STATE_CANCELLED, null, false).severity()).isEqualTo(SEVERITY_GREEN);
    assertThat(eval(STATE_PENDING, null, false).severity()).isEqualTo(SEVERITY_GREEN);
  }

  @Test
  @DisplayName("不认识的状态 ⇒ 黄（不当成正常）")
  void unknownStateIsNotHealthy() {
    ServiceTaskStatusEvaluation evaluation = eval(STATE_UNKNOWN, null, false);
    assertThat(evaluation.severity()).isEqualTo(SEVERITY_YELLOW);
    assertThat(evaluation.reasons()).extracting(r -> r.code()).contains(RULE_TASK_STATE_UNKNOWN);
  }

  // ================================================================
  // 规则表
  // ================================================================

  @Test
  @DisplayName("规则表逐条列出了每个规则码，且严重度与实现一致")
  void ruleTableMatchesImplementation() {
    List<ServiceTaskStatusModels.ServiceTaskStatusRuleDoc> table = ruleTable(WARN, CRITICAL);
    assertThat(table).extracting(r -> r.code())
        .contains(
            RULE_TASK_FAILED,
            RULE_TASK_PARTIAL,
            RULE_STALL_CRITICAL,
            RULE_STALL_WARNING,
            RULE_STALL_UNDETERMINED,
            RULE_TASK_RUNNING,
            RULE_TASK_SUCCEEDED,
            RULE_TASK_CANCELLED,
            RULE_TASK_PENDING,
            RULE_TASK_STATE_UNKNOWN);
    Map<String, String> severities = severityOfRules(WARN, CRITICAL);
    assertThat(severities.get(RULE_STALL_CRITICAL)).isEqualTo(SEVERITY_RED);
    assertThat(severities.get(RULE_STALL_WARNING)).isEqualTo(SEVERITY_YELLOW);
    assertThat(severities.get(RULE_STALL_UNDETERMINED)).isEqualTo(SEVERITY_YELLOW);
    assertThat(severities.get(RULE_TASK_FAILED)).isEqualTo(SEVERITY_RED);
    assertThat(severities.get(RULE_TASK_PARTIAL)).isEqualTo(SEVERITY_YELLOW);
  }

  @Test
  @DisplayName("规则表里的阈值必须与代码用的是同一组数字（口径不能写岔）")
  void ruleTableStatesTheSameThresholds() {
    List<ServiceTaskStatusModels.ServiceTaskStatusRuleDoc> table = ruleTable(WARN, CRITICAL);
    String critical =
        table.stream()
            .filter(r -> RULE_STALL_CRITICAL.equals(r.code()))
            .findFirst()
            .orElseThrow()
            .condition();
    String warning =
        table.stream()
            .filter(r -> RULE_STALL_WARNING.equals(r.code()))
            .findFirst()
            .orElseThrow()
            .condition();
    assertThat(critical).contains("> " + CRITICAL).contains("now()");
    assertThat(warning).contains("> " + WARN);
    // 🔴 规则表必须写明阈值**可配**，否则就是写死的神秘数字。
    assertThat(critical).contains("channel.service-task-status.stall-critical-minutes");
    assertThat(warning).contains("channel.service-task-status.stall-warn-minutes");
  }

  @Test
  @DisplayName("失败分类表逐类列出了理由词表")
  void failureClassTableIsComplete() {
    List<ServiceTaskStatusModels.ServiceTaskStatusFailureClassDoc> classes = failureClasses();
    assertThat(classes).extracting(c -> c.failureClass())
        .contains(
            FAILURE_CLASS_BUSINESS,
            FAILURE_CLASS_SYSTEM,
            FAILURE_CLASS_MIXED,
            FAILURE_CLASS_UNCLASSIFIED,
            FAILURE_CLASS_UNDETERMINED,
            FAILURE_CLASS_NONE);
    assertThat(classes)
        .filteredOn(c -> FAILURE_CLASS_BUSINESS.equals(c.failureClass()))
        .singleElement()
        .satisfies(c -> assertThat(c.reasons()).containsExactlyElementsOf(BUSINESS_FAILURE_REASONS));
    assertThat(classes)
        .filteredOn(c -> FAILURE_CLASS_SYSTEM.equals(c.failureClass()))
        .singleElement()
        .satisfies(c -> assertThat(c.reasons()).containsExactlyElementsOf(SYSTEM_FAILURE_REASONS));
  }
}
