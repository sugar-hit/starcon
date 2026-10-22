package com.starcon.channel;

import static com.starcon.channel.IpDetectionRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.4.1.2 检测与预警的<b>比对口径</b>单测（纯逻辑，无 IO）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>关键词按词边界匹配</b>：「air」不该命中「hair」（那是误判）；
 *       「C++」这种首尾不是字母数字的特征<b>也要</b>能匹配上（那是漏判）。</li>
 *   <li><b>品牌名只比对品牌字段</b>：品牌名对品牌，「标题里出现品牌名」是关键词的事。</li>
 *   <li><b>图片命中一律要人工复核</b>：{@code needsManualReview} 对 IMAGE 恒为 true。</li>
 *   <li><b>结论三态</b>：无命中 ⇒ CLEAR；全部待复核 ⇒ REVIEW_REQUIRED；有可判定命中 ⇒ ACTION_REQUIRED。
 *       「判不了」<b>不等于</b>「没问题」。</li>
 *   <li><b>建议动作指向正确的下游</b>：品牌命中 ⇒ 核验授权；高风险可判定命中 ⇒ 建议下游拦截。</li>
 * </ol>
 */
class IpDetectionRulesTest {

  private static IpDetectionRules.Feature feature(
      String kind, String mode, String text, String riskLevel) {
    return new IpDetectionRules.Feature(
        "ENTRY", "11111111-1111-1111-1111-111111111111", "E-1", "TRADEMARK", riskLevel, kind, mode, text);
  }

  private static IpDetectionRules.Feature external(String text, String riskLevel) {
    return new IpDetectionRules.Feature(
        "EXTERNAL", "22222222-2222-2222-2222-222222222222", "Q-1", "PATENT", riskLevel,
        "EXTERNAL_TERM", "CONTAINS", text);
  }

  // ==================================================================
  // 比对口径
  // ==================================================================

  @Test
  @DisplayName("关键词按词边界匹配：「air」不命中「hair」，但命中「air max」")
  void keywordRespectsWordBoundary() {
    assertThat(indexOf("hair dryer", "air", "KEYWORD")).isEqualTo(-1);
    assertThat(indexOf("Nike Air Max 90", "air", "KEYWORD")).isEqualTo(5);
    assertThat(indexOf("Air Max", "air", "KEYWORD")).isZero();
  }

  @Test
  @DisplayName("首尾不是字母数字的特征不查边界（「C++」也要能匹配上）")
  void keywordWithSymbolsStillMatches() {
    assertThat(indexOf("C++ 编程入门", "C++", "KEYWORD")).isZero();
  }

  @Test
  @DisplayName("短语按包含匹配，大小写不敏感")
  void phraseUsesContains() {
    assertThat(indexOf("This Is A Copyright Trap", "copyright trap", "PHRASE")).isEqualTo(10);
    assertThat(indexOf("nothing here", "copyright trap", "PHRASE")).isEqualTo(-1);
  }

  @Test
  @DisplayName("品牌名只比对品牌字段，不比对标题")
  void brandNameOnlyMatchesBrandField() {
    List<IpDetectionRules.Feature> features =
        List.of(feature("BRAND_NAME", "CONTAINS", "BrandT", "HIGH"));
    List<IpDetectionRules.Match> matches =
        matchFeatures(
            features,
            List.of(
                new IpDetectionRules.FieldText("TITLE", "BrandT running shoes"),
                new IpDetectionRules.FieldText("BRAND", "BrandT")));

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).field()).isEqualTo("BRAND");
  }

  @Test
  @DisplayName("关键词在标题 / 描述 / 卖点 / 品牌四个字段都参与比对")
  void termsMatchEveryTextField() {
    List<IpDetectionRules.Match> matches =
        matchFeatures(
            List.of(feature("TERM", "KEYWORD", "nike", "HIGH")),
            List.of(
                new IpDetectionRules.FieldText("TITLE", "nike shoes"),
                new IpDetectionRules.FieldText("DESCRIPTION", "nike brand"),
                new IpDetectionRules.FieldText("BULLET", "nike air"),
                new IpDetectionRules.FieldText("BRAND", "nike")));

    assertThat(matches).hasSize(4);
    assertThat(matches).extracting(IpDetectionRules.Match::field)
        .containsExactlyInAnyOrder("TITLE", "DESCRIPTION", "BULLET", "BRAND");
  }

  @Test
  @DisplayName("同一个特征在同一个字段上只留一条命中")
  void sameFeatureOnSameFieldHitsOnce() {
    List<IpDetectionRules.Match> matches =
        matchFeatures(
            List.of(feature("TERM", "KEYWORD", "nike", "HIGH")),
            List.of(
                new IpDetectionRules.FieldText("TITLE", "nike and nike and nike")));

    assertThat(matches).hasSize(1);
  }

  @Test
  @DisplayName("🔴 图片命中一律要人工复核（没有开关）")
  void imageAlwaysNeedsManualReview() {
    assertThat(needsManualReview("IMAGE")).isTrue();
    assertThat(needsManualReview("TITLE")).isFalse();
    assertThat(needsManualReview("DESCRIPTION")).isFalse();
    assertThat(needsManualReview("BULLET")).isFalse();
    assertThat(needsManualReview("BRAND")).isFalse();
  }

  @Test
  @DisplayName("图片命中是可命中的（标识文本比对），但结论只能是「待人工复核」")
  void imageHitIsReviewRequired() {
    List<IpDetectionRules.Match> matches =
        matchFeatures(
            List.of(feature("TERM", "KEYWORD", "nike", "HIGH")),
            List.of(new IpDetectionRules.FieldText("IMAGE", "nike-air-max.jpg")));

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).field()).isEqualTo("IMAGE");

    List<IpDetectionRules.HitSummary> summaries =
        matches.stream()
            .map(m -> new IpDetectionRules.HitSummary("HIGH", m.field(), needsManualReview(m.field())))
            .toList();
    assertThat(conclusionOf(summaries)).isEqualTo("REVIEW_REQUIRED");
    assertThat(recommendedActionOf(summaries)).isEqualTo("NOTIFY");
  }

  @Test
  @DisplayName("命中片段带上下文，人能看懂命中在哪")
  void matchedSnippetCarriesContext() {
    String text = "Super comfortable running shoes for daily training";
    int at = indexOf(text, "running", "KEYWORD");
    String snippet = snippet(text, at, "running".length());
    assertThat(snippet).contains("running").contains("Super comfortable");
  }

  // ==================================================================
  // 结论口径
  // ==================================================================

  @Test
  @DisplayName("无命中 ⇒ CLEAR")
  void noHitsMeansClear() {
    assertThat(conclusionOf(List.of())).isEqualTo("CLEAR");
    assertThat(recommendedActionOf(List.of())).isEqualTo("NONE");
  }

  @Test
  @DisplayName("全部待复核 ⇒ REVIEW_REQUIRED（「判不了」≠「没问题」）")
  void allPendingMeansReviewRequired() {
    List<IpDetectionRules.HitSummary> summaries =
        List.of(new IpDetectionRules.HitSummary("HIGH", "IMAGE", true));
    assertThat(conclusionOf(summaries)).isEqualTo("REVIEW_REQUIRED");
    assertThat(recommendedActionOf(summaries)).isEqualTo("NOTIFY");
  }

  @Test
  @DisplayName("存在可判定命中 ⇒ ACTION_REQUIRED")
  void anyDecidableMeansActionRequired() {
    List<IpDetectionRules.HitSummary> summaries =
        List.of(
            new IpDetectionRules.HitSummary("HIGH", "IMAGE", true),
            new IpDetectionRules.HitSummary("HIGH", "TITLE", false));
    assertThat(conclusionOf(summaries)).isEqualTo("ACTION_REQUIRED");
    assertThat(recommendedActionOf(summaries)).isEqualTo("INTERCEPT");
  }

  @Test
  @DisplayName("可判定的中 / 低风险命中 ⇒ REMEDIATE（要整改，不是拦截）")
  void mediumDecidableMeansRemediate() {
    List<IpDetectionRules.HitSummary> summaries =
        List.of(new IpDetectionRules.HitSummary("MEDIUM", "DESCRIPTION", false));
    assertThat(recommendedActionOf(summaries)).isEqualTo("REMEDIATE");
  }

  @Test
  @DisplayName("品牌命中 ⇒ 建议核验品牌授权（指向下游 16.4.2）")
  void brandHitMeansVerifyAuthorization() {
    List<IpDetectionRules.HitSummary> summaries =
        List.of(new IpDetectionRules.HitSummary("LOW", "BRAND", false));
    assertThat(recommendedActionOf(summaries)).isEqualTo("VERIFY_AUTHORIZATION");
  }

  // ==================================================================
  // 整改建议
  // ==================================================================

  @Test
  @DisplayName("整改动作按命中字段分派")
  void remediationActionsByField() {
    assertThat(remediationActionOf("IMAGE", "TERM")).isEqualTo("REPLACE_IMAGE");
    assertThat(remediationActionOf("BRAND", "BRAND_NAME")).isEqualTo("VERIFY_BRAND_AUTH");
    assertThat(remediationActionOf("TITLE", "TERM")).isEqualTo("REWRITE_TITLE");
    assertThat(remediationActionOf("DESCRIPTION", "TERM")).isEqualTo("REMOVE_TERM");
    assertThat(remediationActionOf("BULLET", "TERM")).isEqualTo("REMOVE_TERM");
    assertThat(remediationActionOf("TITLE", "EXTERNAL_TERM")).isEqualTo("CONTACT_RIGHTS_HOLDER");
  }

  @Test
  @DisplayName("整改建议正文点名命中了什么")
  void remediationSuggestionNamesTheMatch() {
    String suggestion = remediationSuggestionOf("TITLE", "TERM", "nike", "E-1");
    assertThat(suggestion).contains("nike").contains("标题").contains("E-1");
  }

  @Test
  @DisplayName("图片整改建议必须写明「先过人工复核」")
  void imageRemediationMentionsReview() {
    String suggestion = remediationSuggestionOf("IMAGE", "TERM", "nike", "E-1");
    assertThat(suggestion).contains("人工复核");
  }

  // ==================================================================
  // 风险等级 / 预警类型
  // ==================================================================

  @Test
  @DisplayName("风险等级权重：高 > 中 > 低 > 无命中")
  void riskWeightOrdersLevels() {
    assertThat(riskWeight("HIGH")).isGreaterThan(riskWeight("MEDIUM"));
    assertThat(riskWeight("MEDIUM")).isGreaterThan(riskWeight("LOW"));
    assertThat(riskWeight("LOW")).isGreaterThan(riskWeight("NONE"));
    assertThat(maxRisk("MEDIUM", "HIGH")).isEqualTo("HIGH");
    assertThat(maxRisk("NONE", "LOW")).isEqualTo("LOW");
  }

  @Test
  @DisplayName("预警类型：有待复核的命中 ⇒ PENDING_REVIEW")
  void alertTypePrefersPendingReview() {
    assertThat(alertTypeOf("IMAGE", "TERM", true, false)).isEqualTo("PENDING_REVIEW");
    assertThat(alertTypeOf("TITLE", "EXTERNAL_TERM", false, false)).isEqualTo("EXTERNAL_MATCH");
    assertThat(alertTypeOf("TITLE", "TERM", false, true)).isEqualTo("INSPECTION_FOUND");
    assertThat(alertTypeOf("TITLE", "TERM", false, false)).isEqualTo("INFRINGEMENT_HIT");
  }

  @Test
  @DisplayName("图片标识文本由文件名 / 替代文本 / URL 拼成")
  void imageDescriptorJoinsParts() {
    assertThat(imageDescriptor("https://x/a.jpg", "nike air", "nike-air.jpg"))
        .contains("nike-air.jpg")
        .contains("nike air")
        .contains("https://x/a.jpg");
    assertThat(imageDescriptor(null, null, null)).isEmpty();
  }

  @Test
  @DisplayName("外部查询命中与本地条目一样参与比对")
  void externalFeaturesParticipate() {
    List<IpDetectionRules.Match> matches =
        matchFeatures(
            List.of(external("Acme Corp", "HIGH")),
            List.of(new IpDetectionRules.FieldText("TITLE", "Acme Corp mug")));

    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).feature().featureKind()).isEqualTo("EXTERNAL_TERM");
    assertThat(matches.get(0).feature().sourceType()).isEqualTo("EXTERNAL");
  }
}
