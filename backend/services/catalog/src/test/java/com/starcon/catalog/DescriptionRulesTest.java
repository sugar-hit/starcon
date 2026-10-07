package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.4.2.2 描述与卖点库纯规则层单测。
 *
 * <p>这些断言与 V21 的 CHECK、前端表单校验、服务层拒绝时给出的话是同一份口径：
 * 服务层 {@code firstNonNull(...)} 报出去的第一条原因，用户在页面上看到的也应当是同一句。
 */
class DescriptionRulesTest {

  // ------------------------------------------------------------ 模板

  @Test
  void templateCodeMustBeAsciiStable() {
    assertThat(DescriptionRules.templateCodeProblem(null)).isEqualTo("模板编码不能为空");
    assertThat(DescriptionRules.templateCodeProblem("  ")).isEqualTo("模板编码不能为空");
    assertThat(DescriptionRules.templateCodeProblem("详情描述")).contains("英文字母");
    assertThat(DescriptionRules.templateCodeProblem("_leading")).contains("英文字母");
    assertThat(DescriptionRules.templateCodeProblem("A".repeat(33))).contains("最多 32");
    assertThat(DescriptionRules.templateCodeProblem("desc-main")).isNull();
    assertThat(DescriptionRules.templateCodeProblem(" DESC_MAIN ")).isNull();
  }

  @Test
  void templateNameMustBePresentAndBounded() {
    assertThat(DescriptionRules.templateNameProblem(" ")).isEqualTo("模板名称不能为空");
    assertThat(DescriptionRules.templateNameProblem("名".repeat(101))).contains("最多 100");
    assertThat(DescriptionRules.templateNameProblem("蓝牙音箱详情描述")).isNull();
  }

  @Test
  void contentTypeIsTheFourBlueprintTypes() {
    for (String type : List.of("DESCRIPTION", "INSTRUCTION", "CAUTION", "SELLING_POINT"))
      assertThat(DescriptionRules.contentTypeProblem(type)).isNull();
    assertThat(DescriptionRules.contentTypeProblem("listing")).contains("内容类型只能是");
    // 四类都要有中文标签：页面上不能把枚举值直接甩给运营。
    assertThat(DescriptionRules.contentTypeLabel("DESCRIPTION")).isEqualTo("详情描述");
    assertThat(DescriptionRules.contentTypeLabel("INSTRUCTION")).isEqualTo("使用说明");
    assertThat(DescriptionRules.contentTypeLabel("CAUTION")).isEqualTo("注意事项");
    assertThat(DescriptionRules.contentTypeLabel("SELLING_POINT")).isEqualTo("卖点段落");
  }

  // ------------------------------------------------------------ 正文与占位符

  @Test
  void bodyNormalizesWhitespaceSoOneTemplateHasOneForm() {
    assertThat(DescriptionRules.normalizeBody("  本产品\n采用   {brand} 材质  "))
        .isEqualTo("本产品 采用 {brand} 材质");
  }

  /**
   * 正文**不要求**至少一个占位符 —— 与 3.4.2.1 的标题模板相反。
   *
   * <p>「请勿进水」「适用于 0~40℃」这种注意事项就是一个变量都没有的固定句子，
   * 它完全成立；强制加占位符只会逼运营写废话占位。
   */
  @Test
  void fixedCautionTextNeedsNoPlaceholder() {
    assertThat(DescriptionRules.bodyProblem("请勿在充电时拆解本机")).isNull();
    assertThat(DescriptionRules.bodyProblem("")).isEqualTo("模板正文不能为空");
    assertThat(DescriptionRules.bodyProblem("文".repeat(20001))).contains("最多 20000");
  }

  /**
   * 花括号必须配平。
   *
   * <p>漏写右括号会让占位符解析把后面一整段都吞成变量名，
   * 而填充时看起来「只是少了几个字」——那是「静默留下占位符」的另一种形态。
   */
  @Test
  void unbalancedBracesAreRejectedBecauseTheySwallowFollowingText() {
    String problem = DescriptionRules.bodyProblem("本产品采用 {brand 材质，可回收。");
    assertThat(problem).contains("花括号没有配平");
    assertThat(problem).contains("吞");
    // 「本产品采用 」是 6 个字符（5 个汉字 + 1 个空格），所以花括号落在下标 6。
    assertThat(DescriptionRules.unbalancedBraceIndex("本产品采用 {brand 材质")).isEqualTo(6);
    assertThat(DescriptionRules.unbalancedBraceIndex("本产品采用 } 材质")).isEqualTo(6);
    assertThat(DescriptionRules.unbalancedBraceIndex("本产品采用 {brand} 材质")).isEqualTo(-1);
  }

  @Test
  void placeholdersAreExtractedInOrderAndDeduped() {
    assertThat(DescriptionRules.placeholdersOf("{brand} 搭配 {selling}，{brand} 更好"))
        .containsExactly("brand", "selling");
    assertThat(DescriptionRules.placeholdersOf("没有变量的固定句子")).isEmpty();
  }

  @Test
  void variableNameMustBeAsciiIdentifier() {
    assertThat(DescriptionRules.variableNameProblem("1brand")).contains("英文字母开头");
    assertThat(DescriptionRules.variableNameProblem("brand-name")).contains("英文字母开头");
    assertThat(DescriptionRules.variableNameProblem("b".repeat(33))).contains("最多 32");
    assertThat(DescriptionRules.variableNameProblem("brand_2")).isNull();
  }

  @Test
  void sourcesCoverFactsPhrasePoolsAndSiblingList() {
    for (String source : DescriptionRules.FACT_SOURCES)
      assertThat(DescriptionRules.sourceProblem(source)).isNull();
    for (String source : DescriptionRules.PHRASE_SOURCES)
      assertThat(DescriptionRules.sourceProblem(source)).isNull();
    assertThat(DescriptionRules.sourceProblem("SPU_PRICE")).contains("变量绑定源只能是");
    // 短语绑定源 → 短语库类型的映射是渲染器取池子用的，逐个断言。
    assertThat(DescriptionRules.phraseTypeOfSource("PHRASE_SELLING")).isEqualTo("SELLING");
    assertThat(DescriptionRules.phraseTypeOfSource("PHRASE_FEATURE")).isEqualTo("FEATURE");
    assertThat(DescriptionRules.phraseTypeOfSource("PHRASE_SCENE")).isEqualTo("SCENE");
    assertThat(DescriptionRules.phraseTypeOfSource("PHRASE_CAUTION")).isEqualTo("CAUTION");
    // SKU_SIBLING_LIST 是第五种绑定源，但它取的不是短语池，所以没有对应类型。
    assertThat(DescriptionRules.phraseTypeOfSource("SKU_SIBLING_LIST")).isNull();
    assertThat(DescriptionRules.phraseTypeOfSource("SPU_BRAND")).isNull();
  }

  /** 双向校验：少声明会在填充时变成「缺变量」，多声明是死配置。 */
  @Test
  void variableBindingIsCheckedInBothDirections() {
    String body = "{brand} 采用 {material} 材质";
    assertThat(DescriptionRules.variableBindingProblem(body, List.of("brand", "material"))).isNull();
    String missing = DescriptionRules.variableBindingProblem(body, List.of("brand"));
    assertThat(missing).contains("{material}").contains("没有声明绑定源");
    // 报出来的原因要点明「会被降级成缺变量」——那正是蓝图禁止的静默占位符。
    assertThat(missing).contains("缺变量");
    String dead =
        DescriptionRules.variableBindingProblem("{brand}", List.of("brand", "material"));
    assertThat(dead).contains("material").contains("死配置");
  }

  // ------------------------------------------------------------ 语言与译文

  @Test
  void localeMustUseBcp47SoOneLanguageHasOneSpelling() {
    // 与本类其它 *Problem 同一契约：合法返回 null，规范化值走 normalizeLocale。
    assertThat(DescriptionRules.localeProblem("zh-CN")).isNull();
    assertThat(DescriptionRules.localeProblem("ja")).isNull();
    assertThat(DescriptionRules.normalizeLocale(" en-US ")).isEqualTo("en-US");
    assertThat(DescriptionRules.normalizeLocale("zh-CN")).isEqualTo("zh-CN");
    // 下划线写法会让同一个语言建出两条译文，locale 唯一键因此形同虚设。
    assertThat(DescriptionRules.localeProblem("en_US")).contains("BCP-47");
    assertThat(DescriptionRules.localeProblem("中文")).contains("BCP-47");
    assertThat(DescriptionRules.localeProblem("e")).contains("BCP-47");
    assertThat(DescriptionRules.localeProblem("")).isEqualTo("语言不能为空（例如 zh-CN / en-US）");
    assertThat(DescriptionRules.localeProblem("zh-CN-very-long-region")).contains("最多 16");
  }

  /**
   * 译文的占位符必须与源语言**完全一致**，三个方向都拒。
   *
   * <p>这三条正是 AI 预填最典型的失败模式，所以要在保存译文时就拦住，
   * 而不是等填充时才发现「每个 SKU 的这段话都一样」。
   */
  @Test
  void translationPlaceholdersMustMatchSourceExactly() {
    String source = "{brand} 采用 {material} 材质";
    assertThat(DescriptionRules.translationPlaceholdersProblem(source, "{brand} uses {material}")).isNull();

    String leaked = DescriptionRules.translationPlaceholdersProblem(source, "{brand} uses material");
    assertThat(leaked).contains("{material}").contains("漏掉");

    String invented = DescriptionRules.translationPlaceholdersProblem(source, "{brand} {material} {color}");
    assertThat(invented).contains("{color}").contains("没有");

    String typo = DescriptionRules.translationPlaceholdersProblem(source, "{brnad} {material}");
    assertThat(typo).contains("{brnad}");
  }

  @Test
  void translationOriginAndStatusAreClosedSets() {
    for (String origin : DescriptionRules.TRANSLATION_ORIGINS)
      assertThat(DescriptionRules.translationOriginProblem(origin)).isNull();
    assertThat(DescriptionRules.translationOriginProblem("MACHINE")).contains("译文来源只能是");
    for (String status : DescriptionRules.TRANSLATION_STATUSES)
      assertThat(DescriptionRules.translationStatusProblem(status)).isNull();
    assertThat(DescriptionRules.translationStatusProblem("PUBLISHED")).contains("译文状态只能是");
    // AI 预填是蓝图点名的来源，标签要能显示出来。
    assertThat(DescriptionRules.originLabel("AI")).isEqualTo("AI 预填");
    assertThat(DescriptionRules.translationStatusLabel("DRAFT")).isEqualTo("草稿");
  }

  @Test
  void localeCountIsBounded() {
    assertThat(DescriptionRules.localesSizeProblem(10)).isNull();
    assertThat(DescriptionRules.localesSizeProblem(11)).contains("一次最多检查 10 种语言");
  }

  // ------------------------------------------------------------ 短语

  @Test
  void phraseTextIsLongButNotAnEntireManual() {
    assertThat(DescriptionRules.phraseTextProblem("")).isEqualTo("短语内容不能为空");
    assertThat(DescriptionRules.phraseTextProblem("句".repeat(2001))).contains("最多 2000");
    // 短语是直接填进正文的文本，不能再带占位符——那会造出「占位符里套占位符」。
    assertThat(DescriptionRules.phraseTextProblem("采用 {material} 材质")).contains("不能包含花括号");
    assertThat(DescriptionRules.phraseTextProblem("双单元立体声，低音下潜到 20Hz")).isNull();
  }

  @Test
  void phraseTypesAreClosedSetWithLabels() {
    for (String type : DescriptionRules.PHRASE_TYPES)
      assertThat(DescriptionRules.phraseTypeProblem(type)).isNull();
    assertThat(DescriptionRules.phraseTypeProblem("KEYWORD")).contains("短语类型只能是");
    assertThat(DescriptionRules.phraseTypeLabel("SCENE")).isEqualTo("适用场景");
  }

  // ------------------------------------------------------------ 容量与工具

  @Test
  void renderCapacityIsBoundedWithActionableReasons() {
    assertThat(DescriptionRules.skusSizeProblem(0)).contains("至少选择一个");
    assertThat(DescriptionRules.skusSizeProblem(200)).isNull();
    assertThat(DescriptionRules.skusSizeProblem(201)).contains("单次最多为 200 个 SKU");
    assertThat(DescriptionRules.variablesSizeProblem(16)).isNull();
    assertThat(DescriptionRules.variablesSizeProblem(17)).contains("最多 16 个变量占位");
  }

  /** 字符数按码点算：emoji 与 CJK 扩展 B 都算一个字，不是两个。 */
  @Test
  void charLengthCountsCodePoints() {
    assertThat(DescriptionRules.charLength("蓝牙音箱")).isEqualTo(4);
    assertThat(DescriptionRules.charLength("")).isEqualTo(0);
    assertThat(DescriptionRules.charLength(null)).isEqualTo(0);
    // 家族 emoji 是多个码点合成一个字符，按码点算等于 1；按 UTF-16 char 数会算成 2。
    assertThat(DescriptionRules.charLength("👨‍👩‍👧")).isGreaterThan(1);
    assertThat(DescriptionRules.charLength("👍🏽")).isEqualTo(2);
  }

  @Test
  void labelsExistForEveryEnumTheApiCanReturn() {
    // 枚举值来自库里，标签查不到就说明漏了一个：页面上会直接显示英文枚举。
    for (String status : DescriptionRules.RENDER_STATUSES)
      assertThat(DescriptionRules.renderStatusLabel(status)).doesNotContain(status);
    for (String status : DescriptionRules.SECTION_STATUSES)
      assertThat(DescriptionRules.sectionStatusLabel(status)).doesNotContain(status);
    for (String outcome : DescriptionRules.SKU_OUTCOMES)
      assertThat(DescriptionRules.outcomeLabel(outcome)).doesNotContain(outcome);
    for (String kind : DescriptionRules.ISSUE_KINDS)
      assertThat(DescriptionRules.issueKindLabel(kind)).doesNotContain(kind);
    for (String source : DescriptionRules.FACT_SOURCES)
      assertThat(DescriptionRules.sourceLabel(source)).doesNotContain(source);
    for (String source : DescriptionRules.PHRASE_SOURCES)
      assertThat(DescriptionRules.sourceLabel(source)).doesNotContain(source);
  }
}
