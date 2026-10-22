package com.starcon.channel;

import static com.starcon.channel.ComplianceRuleRules.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.2.1.1 规则库的受控值域（纯逻辑单测，无 IO）。
 *
 * <p>🔴 这组用例钉的是「值域要<b>可审阅</b>，不只靠应用层 enum」：
 * DB 层 CHECK（迁移 V311）与本类必须<b>逐字一致</b>，两边不可能长期漂移。
 */
class ComplianceRuleRulesTest {

  @Test
  @DisplayName("16.2.1.1 规则类型恰为蓝图点名的七种")
  void ruleTypesAreTheSevenFromTheBlueprint() {
    assertThat(RULE_TYPES)
        .containsExactly(
            "PROHIBITED_WORD",
            "SUPERLATIVE_WORD",
            "INFRINGEMENT_WORD",
            "CERTIFICATION_REQUIRED",
            "RESTRICTED_SALE",
            "LABEL_REQUIREMENT",
            "MATERIAL_REQUIREMENT");
    assertThat(RULE_TYPES).hasSize(7);
    for (String code : RULE_TYPES) {
      assertThat(knownRuleType(code)).as("%s 必须是合法规则类型", code).isTrue();
      assertThat(ruleTypeLabel(code)).as("%s 必须有人话名", code).isNotBlank();
    }
    assertThat(knownRuleType("UNKNOWN_TYPE")).isFalse();
    assertThat(knownRuleType(null)).isFalse();
  }

  @Test
  @DisplayName("16.2.1.1 只有违禁词 / 极限词 / 侵权词三类规则带词条")
  void onlyWordRuleTypesCarryTerms() {
    assertThat(WORD_RULE_TYPES)
        .containsExactly("PROHIBITED_WORD", "SUPERLATIVE_WORD", "INFRINGEMENT_WORD");
    assertThat(wordRuleType("PROHIBITED_WORD")).isTrue();
    assertThat(wordRuleType("SUPERLATIVE_WORD")).isTrue();
    assertThat(wordRuleType("INFRINGEMENT_WORD")).isTrue();
    // 另外四类不带词条 —— 认证要求 / 禁限售 / 标签要求 / 材质要求不是词表能表达的。
    assertThat(wordRuleType("CERTIFICATION_REQUIRED")).isFalse();
    assertThat(wordRuleType("RESTRICTED_SALE")).isFalse();
    assertThat(wordRuleType("LABEL_REQUIREMENT")).isFalse();
    assertThat(wordRuleType("MATERIAL_REQUIREMENT")).isFalse();
    assertThat(wordRuleType(null)).isFalse();
  }

  @Test
  @DisplayName("16.2.1.1 严重度恰为阻断 / 警告 / 提示三种")
  void severitiesAreTheThreeFromTheBlueprint() {
    assertThat(SEVERITIES).containsExactly("BLOCKING", "WARNING", "INFO");
    assertThat(SEVERITIES).hasSize(3);
    for (String code : SEVERITIES) {
      assertThat(knownSeverity(code)).isTrue();
      assertThat(severityLabel(code)).isNotBlank();
    }
    assertThat(knownSeverity("CRITICAL")).isFalse();
    assertThat(knownSeverity(null)).isFalse();
  }

  @Test
  @DisplayName("16.2.1.1 规则生命周期与词条分类值域")
  void statusAndTermKindDomains() {
    assertThat(STATUSES).containsExactly("ACTIVE", "RETIRED");
    assertThat(knownStatus("ACTIVE")).isTrue();
    assertThat(knownStatus("ARCHIVED")).isFalse();

    assertThat(TERM_KINDS).containsExactly("GENERAL", "BRAND", "CATEGORY", "SPELLING");
    for (String code : TERM_KINDS) {
      assertThat(knownTermKind(code)).isTrue();
      assertThat(termKindLabel(code)).isNotBlank();
    }
    assertThat(knownTermKind("WHATEVER")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.1 未知编码回落到原值（不编造人话）")
  void unknownCodesFallBackToThemselves() {
    assertThat(ruleTypeLabel("NOPE")).isEqualTo("NOPE");
    assertThat(severityLabel("NOPE")).isEqualTo("NOPE");
    assertThat(statusLabel("NOPE")).isEqualTo("NOPE");
    assertThat(termKindLabel("NOPE")).isEqualTo("NOPE");
  }
}
