package com.starcon.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.4.3.1 多语言内容的纯规则层单元测试。
 *
 * <p>这些是**不依赖数据库**的边界：语言标签规范化、三态与正文的一致性、 状态流转合法性、占位符拒绝、容量上限、驳回理由必填。
 *
 * <p>刻意写成不碰 Spring 容器：规则层要是能单测，就不要为了测它去起一个 Testcontainers（那会让「改一个 if 的成本」高到没人愿意再碰）。
 */
class TranslationRulesTest {

  // ------------------------------------------------------------ 语言标签

  @Test
  void localeNormalizesCaseSoLookupAndStorageAgree() {
    // 入库走 normalizeLocale，查询也走它 —— 否则入库存 "zh-CN"、查询变成 "ZH-CN"，
    // 筛选永远匹配不到，页面表现为「明明有数据却查不出来」。
    assertThat(TranslationRules.normalizeLocale("ZH-cn")).isEqualTo("zh-CN");
    // 下划线写法 normalizeLocale 只会把它折叠成全小写（它**不是**合法 BCP-47），
    // 真正挡住它的是 localeProblem —— 两条路径都要有断言。
    assertThat(TranslationRules.normalizeLocale("en_US")).isEqualTo("en_us");
    assertThat(TranslationRules.localeProblem("en_US")).contains("BCP-47");
    assertThat(TranslationRules.normalizeLocale("EN-us")).isEqualTo("en-US");
    assertThat(TranslationRules.normalizeLocale("ja")).isEqualTo("ja");
    assertThat(TranslationRules.normalizeLocale("  ")).isEmpty();
  }

  @Test
  void localeRejectsUnderscoreAndOversizedTags() {
    // 下划线写法会让同一个语言的两条拼法各建一行，locale 唯一键形同虚设。
    assertThat(TranslationRules.localeProblem("en_US")).contains("BCP-47");
    assertThat(TranslationRules.localeProblem("中文")).contains("BCP-47");
    assertThat(TranslationRules.localeProblem("e")).contains("BCP-47");
    assertThat(TranslationRules.localeProblem("zh-CN")).isNull();
    assertThat(TranslationRules.localeProblem("pt-BR")).isNull();
    assertThat(TranslationRules.localeProblem("zh-CN-extra-long-region")).isNotNull();
  }

  @Test
  void localeLabelsAreStable() {
    assertThat(TranslationRules.statusLabel("PENDING")).isEqualTo("待翻");
    assertThat(TranslationRules.statusLabel("TRANSLATED")).isEqualTo("已翻");
    assertThat(TranslationRules.statusLabel("REVIEWED")).isEqualTo("已校对");
    // 未知值原样返回而不是编一个标签。
    assertThat(TranslationRules.statusLabel("WAT")).isEqualTo("WAT");
  }

  // ------------------------------------------------------------ 占位符

  @Test
  void productTextRejectsTemplatePlaceholders() {
    // 占位符是 3.4.2.2 的机制，商品多语言内容是**已填好的成品**。
    assertThat(TranslationRules.nameProblem("Kaffeemaschine {brand}")).contains("花括号");
    assertThat(TranslationRules.descriptionProblem("Material: {material}")).contains("花括号");
    // 孤立的右花括号同样拒：漏写左括号也是模板没填的形态。
    assertThat(TranslationRules.nameProblem("Kaffee}")).contains("花括号");
    assertThat(TranslationRules.nameProblem("Kaffeemaschine")).isNull();
  }

  @Test
  void productTextEnforcesLengthCaps() {
    assertThat(TranslationRules.nameProblem("n".repeat(201))).contains("200");
    assertThat(TranslationRules.nameProblem("n".repeat(200))).isNull();
    assertThat(TranslationRules.descriptionProblem("d".repeat(8001))).contains("8000");
    assertThat(TranslationRules.descriptionProblem("d".repeat(8000))).isNull();
  }

  // ------------------------------------------------------------ 三态与正文的一致性

  @Test
  void pendingRequiresEmptyText() {
    assertThat(TranslationRules.translationTextProblem("PENDING", "", "")).isNull();
    assertThat(TranslationRules.translationTextProblem("PENDING", "Kaffee", ""))
        .contains("待翻")
        .contains("已翻");
  }

  @Test
  void reviewedRequiresName() {
    assertThat(TranslationRules.translationTextProblem("REVIEWED", "Kaffee", "")).isNull();
    // 描述有内容但名称为空：空名称进不了刊登，标成已校对是自欺。
    assertThat(TranslationRules.translationTextProblem("REVIEWED", "", "Beschreibung"))
        .contains("已校对")
        .contains("名称");
  }

  @Test
  void translatedNeedsSomeText() {
    assertThat(TranslationRules.translationTextProblem("TRANSLATED", "Kaffee", "")).isNull();
    assertThat(TranslationRules.translationTextProblem("TRANSLATED", "", "")).contains("已翻");
  }

  // ------------------------------------------------------------ 状态流转

  @Test
  void pendingCannotJumpStraightToReviewed() {
    // 中间那一步「已翻」正是有没有人真的读过的信号，跳级等于把校对抹掉。
    String problem = TranslationRules.statusTransitionProblem("PENDING", "REVIEWED", "", "");
    assertThat(problem).contains("已翻");
    // 先填译文再标校对是合法的。
    assertThat(TranslationRules.statusTransitionProblem("TRANSLATED", "REVIEWED", "Kaffee", ""))
        .isNull();
  }

  @Test
  void textBearingEntryCannotRegressToPending() {
    // 正文不会因为改状态就消失；静默退回待翻会让内容从刊登可取范围里消失。
    assertThat(
            TranslationRules.statusTransitionProblem(
                "REVIEWED", "PENDING", "Kaffee", "Beschreibung"))
        .contains("待翻");
    // 同状态重写（改正文不改状态）是正常编辑，放行。
    assertThat(TranslationRules.statusTransitionProblem("TRANSLATED", "TRANSLATED", "Kaffee2", ""))
        .isNull();
    assertThat(TranslationRules.statusTransitionProblem("REVIEWED", "TRANSLATED", "Kaffee", ""))
        .isNull();
  }

  @Test
  void statusProblemRejectsUnknownStatus() {
    assertThat(TranslationRules.statusProblem("PUBLISHED")).contains("待翻");
    assertThat(TranslationRules.statusProblem("reviewed")).isNull();
  }

  // ------------------------------------------------------------ 建议处置

  @Test
  void rejectReasonIsMandatory() {
    assertThat(TranslationRules.rejectReasonProblem("")).contains("理由");
    assertThat(TranslationRules.rejectReasonProblem("  ")).contains("理由");
    assertThat(TranslationRules.rejectReasonProblem("r".repeat(501))).contains("500");
    assertThat(TranslationRules.rejectReasonProblem("机翻把型号也译了")).isNull();
  }

  @Test
  void vocabulariesMatchTheMigrationChecks() {
    assertThat(TranslationRules.STATUSES).containsExactly("PENDING", "TRANSLATED", "REVIEWED");
    assertThat(TranslationRules.ORIGINS).containsExactly("MANUAL", "AI", "IMPORTED");
    assertThat(TranslationRules.SUGGESTION_STATUSES)
        .containsExactly("PENDING", "ADOPTED", "REJECTED");
    assertThat(TranslationRules.BATCH_ACTIONS)
        .containsExactly("CREATE_PENDING", "ADOPT_SUGGESTIONS", "MARK_REVIEWED");
    assertThat(TranslationRules.BATCH_OUTCOMES).containsExactly("SUCCEEDED", "SKIPPED", "REJECTED");
    assertThat(TranslationRules.LOCALE_SOURCES).containsExactly("MANUAL", "SHOP_SITE");
    // 与 V22 的 translation_locale.source CHECK 逐字一致。
    assertThat(TranslationRules.localeSourceProblem("SITE")).isNotNull();
    assertThat(TranslationRules.localeSourceProblem("SHOP_SITE")).isNull();
  }

  // ------------------------------------------------------------ 批量与容量上限

  @Test
  void batchLimitsAreProductDecisionsNotComputeOnes() {
    assertThat(TranslationRules.itemsSizeProblem(0)).contains("至少");
    assertThat(TranslationRules.itemsSizeProblem(201)).contains("200");
    assertThat(TranslationRules.itemsSizeProblem(200)).isNull();
    assertThat(TranslationRules.batchLocalesSizeProblem(11)).contains("10");
    assertThat(TranslationRules.coverageLocalesSizeProblem(21)).contains("20");
    assertThat(TranslationRules.suggestionsSizeProblem(501)).contains("500");
  }

  @Test
  void normalizationCollapsesWhitespaceSoOneTranslationLooksLikeOne() {
    // 同一句德语译文有人写成两行有人写成一行，不折叠就会被当成两份不同内容。
    assertThat(TranslationRules.normalizeText("  Kaffee-\n  Maschine  "))
        .isEqualTo("Kaffee- Maschine");
    assertThat(TranslationRules.normalizeText(null)).isEmpty();
  }

  @Test
  void coverageLocaleListDeduplicatesAfterNormalization() {
    // 这是服务层 normalizeLocales 依赖的行为前提。
    assertThat(TranslationRules.normalizeLocale("de-DE")).isEqualTo("de-DE");
    assertThat(TranslationRules.normalizeLocale("DE-de")).isEqualTo("de-DE");
    assertThat(List.of("de-DE").stream().map(TranslationRules::normalizeLocale).toList())
        .containsExactly("de-DE");
  }
}
