package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.2.1.2 拦截执行的纯逻辑单测（不碰数据库）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>「判不了」与「通过」分开</b>：判不出来时 {@code releaseGranted=false}，
 *       结论聚合里判不了永远排在通过前面。</li>
 *   <li><b>词条命中的口径</b>：拉丁词按词边界（{@code best} 不命中 {@code bestseller}），
 *       中文按包含，大小写不敏感。</li>
 *   <li><b>豁免放行不参与聚合</b>：已被豁免放行的原因不再算阻断。</li>
 *   <li><b>解除方式按规则类型给</b>：认证要求说「提交资质」，禁限售说「下架或改投」。</li>
 *   <li><b>值域与 DB 层 CHECK 一致</b>：值域拼错就是接口与数据库不一致。</li>
 * </ol>
 */
class ComplianceInterceptRulesTest {

  // ==================================================================
  // 一、结论聚合：三态 + 一个「判不了」
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 没有任何原因 ⇒ PASS")
  void noReasonMeansPass() {
    assertThat(ComplianceInterceptRules.aggregate(0, 0, 0)).isEqualTo("PASS");
    assertThat(ComplianceInterceptRules.releaseGranted("PASS")).isTrue();
    assertThat(ComplianceInterceptRules.blocked("PASS")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 有一条阻断级命中 ⇒ BLOCK（优先级最高）")
  void blockingWins() {
    assertThat(ComplianceInterceptRules.aggregate(1, 3, 2)).isEqualTo("BLOCK");
    assertThat(ComplianceInterceptRules.blocked("BLOCK")).isTrue();
    assertThat(ComplianceInterceptRules.releaseGranted("BLOCK")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 没有阻断但有警告 ⇒ WARN（可以放行，但要提醒）")
  void warningIsSecond() {
    assertThat(ComplianceInterceptRules.aggregate(0, 1, 2)).isEqualTo("WARN");
    assertThat(ComplianceInterceptRules.releaseGranted("WARN")).isTrue();
    assertThat(ComplianceInterceptRules.blocked("WARN")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 判不了永远排在通过之前，且**不能放行**")
  void undecidableBeatsPass() {
    assertThat(ComplianceInterceptRules.aggregate(0, 0, 1)).isEqualTo("UNDECIDABLE");
    assertThat(ComplianceInterceptRules.releaseGranted("UNDECIDABLE"))
        .as("判不了 ≠ 放行：releaseGranted 必须是 false")
        .isFalse();
    assertThat(ComplianceInterceptRules.blocked("UNDECIDABLE"))
        .as("判不了不等于被阻断：blocked 是 false")
        .isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 「判不了」类原因码识别正确")
  void undecidableReasonCodes() {
    for (String code :
        new String[] {
          "RULE_SET_EMPTY",
          "RULE_VERSION_UNRESOLVED",
          "RULE_TERMS_MISSING",
          "REQUIREMENT_UNRESOLVED",
          "RULE_SCOPE_UNRESOLVED",
          "RULE_READ_FAILED"
        }) {
      assertThat(ComplianceInterceptRules.undecidableReason(code)).as(code).isTrue();
    }
    for (String code : new String[] {"WORD_TERM_HIT", "REQUIREMENT_NOT_SATISFIED"}) {
      assertThat(ComplianceInterceptRules.undecidableReason(code)).as(code).isFalse();
    }
  }

  // ==================================================================
  // 二、词条命中
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 拉丁词按词边界命中：best 不命中 bestseller")
  void latinTermRespectsWordBoundary() {
    assertThat(ComplianceInterceptRules.matchesTerm("best", "the best seller")).isTrue();
    assertThat(ComplianceInterceptRules.matchesTerm("best", "the bestseller")).isFalse();
    assertThat(ComplianceInterceptRules.matchesTerm("free", "free shipping")).isTrue();
    assertThat(ComplianceInterceptRules.matchesTerm("free", "freedom")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 拉丁词大小写不敏感")
  void latinTermIsCaseInsensitive() {
    assertThat(ComplianceInterceptRules.matchesTerm("Best", "the BEST one")).isTrue();
    assertThat(ComplianceInterceptRules.matchesTerm("BEST", "the best one")).isTrue();
  }

  @Test
  @DisplayName("16.2.1.2 中文词按包含命中（中文没有词边界）")
  void cjkTermMatchesByContains() {
    assertThat(ComplianceInterceptRules.matchesTerm("违禁词", "这是违禁词商品")).isTrue();
    assertThat(ComplianceInterceptRules.matchesTerm("违禁词", "普通商品")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 空词 / 空文本不命中")
  void emptyNeverMatches() {
    assertThat(ComplianceInterceptRules.matchesTerm("", "anything")).isFalse();
    assertThat(ComplianceInterceptRules.matchesTerm("best", "")).isFalse();
    assertThat(ComplianceInterceptRules.matchesTerm(null, "anything")).isFalse();
    assertThat(ComplianceInterceptRules.matchesTerm("best", null)).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 含符号的词条退回包含匹配（词边界对它们没意义）")
  void symbolTermFallsBackToContains() {
    assertThat(ComplianceInterceptRules.matchesTerm("c++", "learn c++ today")).isTrue();
  }

  // ==================================================================
  // 三、解除方式
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 解除方式按规则类型给：认证要求说「提交资质」")
  void remedyDependsOnRuleType() {
    assertThat(ComplianceInterceptRules.remedyOf("CERTIFICATION_REQUIRED")).contains("提交资质");
    assertThat(ComplianceInterceptRules.remedyOf("RESTRICTED_SALE")).contains("禁售");
    assertThat(ComplianceInterceptRules.remedyOf("PROHIBITED_WORD")).contains("违禁词");
    assertThat(ComplianceInterceptRules.remedyOf(null)).isNotBlank();
  }

  @Test
  @DisplayName("16.2.1.2 申请豁免的解除方式点明「需他人审批」")
  void exemptionRemedyMentionsApproval() {
    assertThat(ComplianceInterceptRules.EXEMPTION_REMEDY).contains("审批");
    assertThat(ComplianceInterceptRules.EXEMPTION_REMEDY).contains("有效期");
  }

  // ==================================================================
  // 四、值域
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 值域受控：三个拦截点 / 三种对象 / 四个结论")
  void valueDomainsAreControlled() {
    assertThat(ComplianceInterceptRules.SCENES)
        .containsExactly("LISTING_PUBLISH", "SHIPMENT", "WITHDRAWAL");
    assertThat(ComplianceInterceptRules.SUBJECT_TYPES)
        .containsExactly("PRODUCT", "ORDER", "SHOP");
    assertThat(ComplianceInterceptRules.CONCLUSIONS)
        .containsExactly("PASS", "WARN", "BLOCK", "UNDECIDABLE");
    assertThat(ComplianceInterceptRules.EXEMPTION_STATUSES)
        .containsExactly("PENDING", "APPROVED", "REJECTED");
    assertThat(ComplianceInterceptRules.knownScene("LISTING_PUBLISH")).isTrue();
    assertThat(ComplianceInterceptRules.knownScene("PUBLISH")).isFalse();
    assertThat(ComplianceInterceptRules.knownSubjectType("PRODUCT")).isTrue();
    assertThat(ComplianceInterceptRules.knownSubjectType("CUSTOMER")).isFalse();
    assertThat(ComplianceInterceptRules.knownConclusion("UNDECIDABLE")).isTrue();
    assertThat(ComplianceInterceptRules.knownExemptionStatus("APPROVED")).isTrue();
    assertThat(ComplianceInterceptRules.knownExemptionStatus("REVOKED")).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 人话映射齐全（前端不自己写中文标签）")
  void labelsExist() {
    assertThat(ComplianceInterceptRules.sceneLabel("LISTING_PUBLISH")).isEqualTo("刊登发布前");
    assertThat(ComplianceInterceptRules.subjectTypeLabel("PRODUCT")).isEqualTo("商品");
    assertThat(ComplianceInterceptRules.conclusionLabel("UNDECIDABLE")).isEqualTo("判不了");
    assertThat(ComplianceInterceptRules.reasonCodeLabel("RULE_SET_EMPTY")).isEqualTo("规则集为空");
    assertThat(ComplianceInterceptRules.exemptionStatusLabel("PENDING")).isEqualTo("待审批");
  }
}
