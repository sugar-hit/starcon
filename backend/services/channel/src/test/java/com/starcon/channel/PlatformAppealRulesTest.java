package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.1.2.2 申诉管理的纯规则单测（**无 IO**，秒级）。
 *
 * <p>🔴 这组用例守的是「值域真相源只有一处」：本类的值域必须与迁移 V310 的 CHECK
 * **逐字一致** —— 应用层写错，DB 会拒；应用层漏写，DB 会放行一个界面上没人认识的值。
 * 所以值域本身必须被钉住。
 */
class PlatformAppealRulesTest {

  @Test
  @DisplayName("16.1.2.2 申诉类型值域与 DB 的 CHECK 逐字一致（6 个）")
  void appealTypeDomainIsPinned() {
    assertThat(ComplianceAppealRules.APPEAL_TYPES)
        .containsExactly(
            "POLICY_MISINTERPRETATION",
            "EVIDENCE_PROVIDED",
            "PROCESS_ERROR",
            "DUPLICATE_PENALTY",
            "RESTORATION_REQUEST",
            "OTHER");
    for (String code : ComplianceAppealRules.APPEAL_TYPES) {
      assertThat(ComplianceAppealRules.knownAppealType(code)).isTrue();
      assertThat(ComplianceAppealRules.appealTypeLabel(code)).isNotBlank();
    }
    assertThat(ComplianceAppealRules.knownAppealType("NOT_A_TYPE")).isFalse();
    assertThat(ComplianceAppealRules.knownAppealType(null)).isFalse();
  }

  @Test
  @DisplayName("16.1.2.2 申诉状态值域与状态机（通过 / 撤回是终态，驳回可回到草稿）")
  void statusDomainAndStateMachineArePinned() {
    assertThat(ComplianceAppealRules.STATUSES)
        .containsExactly("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN");

    assertThat(ComplianceAppealRules.canTransition("DRAFT", "SUBMITTED")).isTrue();
    assertThat(ComplianceAppealRules.canTransition("DRAFT", "WITHDRAWN")).isTrue();
    assertThat(ComplianceAppealRules.canTransition("SUBMITTED", "APPROVED")).isTrue();
    assertThat(ComplianceAppealRules.canTransition("SUBMITTED", "REJECTED")).isTrue();
    assertThat(ComplianceAppealRules.canTransition("REJECTED", "DRAFT"))
        .as("驳回后必须能回到草稿补充材料再提 —— 否则「申诉次数统计」与「配额」都是摆设")
        .isTrue();

    assertThat(ComplianceAppealRules.canTransition("APPROVED", "DRAFT"))
        .as("通过是终态").isFalse();
    assertThat(ComplianceAppealRules.canTransition("WITHDRAWN", "DRAFT"))
        .as("撤回是终态").isFalse();
    assertThat(ComplianceAppealRules.canTransition("DRAFT", "APPROVED"))
        .as("不能跳过提交直接裁定").isFalse();
    assertThat(ComplianceAppealRules.canTransition("SUBMITTED", "SUBMITTED")).isFalse();
    assertThat(ComplianceAppealRules.canTransition(null, "SUBMITTED")).isFalse();
    assertThat(ComplianceAppealRules.canTransition("DRAFT", null)).isFalse();

    assertThat(ComplianceAppealRules.nextStatuses("APPROVED")).isEmpty();
    assertThat(ComplianceAppealRules.nextStatuses("WITHDRAWN")).isEmpty();
  }

  @Test
  @DisplayName("16.1.2.2 举证材料类型与结果值域被钉死")
  void evidenceAndOutcomeDomainsArePinned() {
    assertThat(ComplianceAppealRules.EVIDENCE_TYPES)
        .containsExactly(
            "DOCUMENT", "IMAGE", "VIDEO", "LINK", "NOTE", "INVOICE", "CERTIFICATE");
    for (String code : ComplianceAppealRules.EVIDENCE_TYPES) {
      assertThat(ComplianceAppealRules.knownEvidenceType(code)).isTrue();
      assertThat(ComplianceAppealRules.evidenceTypeLabel(code)).isNotBlank();
    }
    assertThat(ComplianceAppealRules.knownEvidenceType("AUDIO")).isFalse();

    assertThat(ComplianceAppealRules.OUTCOMES).containsExactly("APPROVED", "REJECTED");
    assertThat(ComplianceAppealRules.knownOutcome("APPROVED")).isTrue();
    assertThat(ComplianceAppealRules.knownOutcome("REJECTED")).isTrue();
    assertThat(ComplianceAppealRules.knownOutcome("PENDING")).isFalse();
    assertThat(ComplianceAppealRules.outcomeLabel("APPROVED")).isEqualTo("通过");
    assertThat(ComplianceAppealRules.outcomeLabel("REJECTED")).isEqualTo("驳回");
  }

  @Test
  @DisplayName("16.1.2.2 申诉文本只有草稿态能改")
  void textIsEditableOnlyInDraft() {
    assertThat(ComplianceAppealRules.editableText("DRAFT")).isTrue();
    assertThat(ComplianceAppealRules.editableText("SUBMITTED")).isFalse();
    assertThat(ComplianceAppealRules.editableText("APPROVED")).isFalse();
    assertThat(ComplianceAppealRules.editableText("REJECTED")).isFalse();
    assertThat(ComplianceAppealRules.editableText("WITHDRAWN")).isFalse();
  }

  @Test
  @DisplayName("16.1.2.2 影响档位的人话**复用上游 16.1.2.1**，同一个档位不允许有两套说法")
  void impactLabelIsReusedFromUpstream() {
    // 上游把「尚无处罚措施」单独做成一个档位；本模块照抄，不另造「无措施」这种说法。
    assertThat(ComplianceAppealRules.impactLabel("NONE")).isEqualTo("尚无处罚措施");
    assertThat(ComplianceAppealRules.impactLabel("CRITICAL")).isEqualTo("致命（停号级）");
    assertThat(ComplianceAppealRules.impactLabel("HIGH")).isEqualTo("严重（下架级）");
    assertThat(ComplianceAppealRules.impactLabel("MEDIUM")).isEqualTo("中等（限制销售 / 扣款级）");
    assertThat(ComplianceAppealRules.impactLabel("LOW")).isEqualTo("轻微（警告级）");
  }

  @Test
  @DisplayName("16.1.2.2 影响档位的派生口径与上游逐档位一致（本模块不重写一套）")
  void impactDerivationMatchesUpstream() {
    // 本模块的 DB 函数 platform_appeal_impact_level 必须与上游的 Java 规则同口径；
    // 集成测试会把两者逐档位对拍，这里先钉住上游这一侧的取值。
    assertThat(PlatformViolationRules.impactLevel(List.of("SUSPEND_ACCOUNT"))).isEqualTo("CRITICAL");
    assertThat(PlatformViolationRules.impactLevel(List.of("DELIST"))).isEqualTo("HIGH");
    assertThat(PlatformViolationRules.impactLevel(List.of("RESTRICT_SALES"))).isEqualTo("MEDIUM");
    assertThat(PlatformViolationRules.impactLevel(List.of("DEDUCTION"))).isEqualTo("MEDIUM");
    assertThat(PlatformViolationRules.impactLevel(List.of("WARNING"))).isEqualTo("LOW");
    assertThat(PlatformViolationRules.impactLevel(List.of())).isEqualTo("NONE");
    assertThat(
            PlatformViolationRules.impactLevel(
                List.of("WARNING", "SUSPEND_ACCOUNT", "DELIST")))
        .as("取措施集合里的最高档").isEqualTo("CRITICAL");
  }
}
