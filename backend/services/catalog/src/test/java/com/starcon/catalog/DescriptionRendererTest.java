package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.DescriptionRenderer.Issue;
import com.starcon.catalog.DescriptionRenderer.PhrasePools;
import com.starcon.catalog.DescriptionRenderer.Rendered;
import com.starcon.catalog.DescriptionRenderer.SkuFacts;
import com.starcon.catalog.DescriptionRenderer.TemplateSpec;
import com.starcon.catalog.DescriptionRenderer.VariableSpec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 3.4.2.2 填充引擎单测。
 *
 * <p><b>本文件是蓝图硬要求「变量缺失必须在填充时报告，不能静默留下占位符」的主要验收点</b>，
 * 所以「结果里不含 {@code {}」这条断言在几乎每个用例里都出现：
 * 缺变量时正文里那个位置被剥掉，而不是留一个字面量。
 *
 * <p>引擎是纯函数（不碰数据库、时钟、随机数），所以这里能逐条断言同一输入的同一输出。
 */
class DescriptionRendererTest {

  private static final UUID SKU = UUID.randomUUID();

  private static SkuFacts facts() {
    return new SkuFacts(
        SKU, "SPK-001", "桌面款", "蓝牙音箱", "星云", "音频", "黑色/32G", List.of("SPK-001", "SPK-002"));
  }

  private static PhrasePools pools() {
    return new PhrasePools(
        List.of("双单元立体声", "高保真音质"), List.of("IP67 防水"), List.of("露营 / 泳池"), List.of("请勿在充电时拆解"));
  }

  private static PhrasePools emptyPools() {
    return new PhrasePools(List.of(), List.of(), List.of(), List.of());
  }

  private static TemplateSpec spec(String body, VariableSpec... variables) {
    return new TemplateSpec("DESCRIPTION", body, List.of(variables));
  }

  // ------------------------------------------------------------ 正常填充

  @Test
  void allVariablesResolvedProducesCleanTextAndNoIssues() {
    var spec =
        spec(
            "{brand} {category}音箱，{selling}，{feature}。",
            new VariableSpec("brand", "SPU_BRAND", 0),
            new VariableSpec("category", "SPU_CATEGORY", 1),
            new VariableSpec("selling", "PHRASE_SELLING", 2),
            new VariableSpec("feature", "PHRASE_FEATURE", 3));
    var rendered = DescriptionRenderer.render(spec, facts(), pools(), 0);

    assertThat(rendered.complete()).isTrue();
    assertThat(rendered.issues()).isEmpty();
    assertThat(rendered.text()).isEqualTo("星云 音频音箱，双单元立体声，IP67 防水。");
    assertThat(rendered.text()).doesNotContain("{").doesNotContain("}");
    assertThat(rendered.charLength()).isEqualTo(rendered.text().length());
    assertThat(rendered.usedPhrases()).containsExactly("双单元立体声", "IP67 防水");
  }

  /** 同一个变量被提及多次时，快照里只记一次。 */
  @Test
  void repeatedPlaceholderKeepsOneBindingAndOneSnapshot() {
    var spec = spec("{selling}，而且 {selling}。", new VariableSpec("selling", "PHRASE_SELLING", 0));
    var rendered = DescriptionRenderer.render(spec, facts(), pools(), 0);

    assertThat(rendered.complete()).isTrue();
    assertThat(rendered.text()).isEqualTo("双单元立体声，而且 双单元立体声。");
    assertThat(rendered.usedPhrases()).containsExactly("双单元立体声");
  }

  // ------------------------------------------------------------ 缺失变量：核心断言

  /**
   * 蓝图硬要求的主用例：商品字段为空 → 变量被报告，**占位符不留在正文里**。
   *
   * <p>这是本模块最关键的一条：正文里不能出现 {@code {brand}} 字面量， 而缺的是什么、为什么缺、怎么修，都必须出现在 issues 上。
   */
  @Test
  void missingFactIsReportedAndPlaceholderIsStrippedFromText() {
    var noBrand = new SkuFacts(SKU, "SPK-001", "桌面款", "蓝牙音箱", "", "音频", "黑色", List.of());
    var spec =
        spec(
            "{brand} 音箱，{selling}。",
            new VariableSpec("brand", "SPU_BRAND", 0),
            new VariableSpec("selling", "PHRASE_SELLING", 1));

    var rendered = DescriptionRenderer.render(spec, noBrand, pools(), 0);

    assertThat(rendered.complete()).isFalse();
    // 硬要求：正文里绝不能残留占位符。
    assertThat(rendered.text()).doesNotContain("{brand}").doesNotContain("{").doesNotContain("}");
    assertThat(rendered.text()).isEqualTo("音箱，双单元立体声。");
    // 硬要求：缺了什么必须被报告。
    assertThat(rendered.issues()).hasSize(1);
    Issue issue = rendered.issues().get(0);
    assertThat(issue.variableName()).isEqualTo("brand");
    assertThat(issue.issueKind()).isEqualTo("EMPTY_FACT");
    // 报告要可行动：指到具体字段与具体 SKU，并给出两条修法。
    assertThat(issue.detail()).contains("SPU 品牌").contains("SPK-001").contains("补上这个字段");
  }

  /** 短语池为空是与「商品字段为空」完全不同的修法，所以必须分开报。 */
  @Test
  void emptyPhrasePoolIsReportedSeparatelyWithItsOwnFix() {
    var spec = spec("本产品 {selling}。", new VariableSpec("selling", "PHRASE_SELLING", 0));
    var rendered = DescriptionRenderer.render(spec, facts(), emptyPools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.text()).doesNotContain("{").isEqualTo("本产品。");
    assertThat(rendered.issues()).hasSize(1);
    assertThat(rendered.issues().get(0).issueKind()).isEqualTo("EMPTY_PHRASE_POOL");
    assertThat(rendered.issues().get(0).detail()).contains("卖点短语池").contains("建至少一条");
  }

  /** 多个变量缺失时逐个报告，不是压成一条「有问题」。 */
  @Test
  void everyMissingVariableGetsItsOwnIssue() {
    var bare = new SkuFacts(SKU, "SPK-009", "", "", "", "", "", List.of());
    var spec =
        spec(
            "{brand}{category}{sku_name}，{selling}。",
            new VariableSpec("brand", "SPU_BRAND", 0),
            new VariableSpec("category", "SPU_CATEGORY", 1),
            new VariableSpec("sku_name", "SKU_NAME", 2),
            new VariableSpec("selling", "PHRASE_SELLING", 3));

    var rendered = DescriptionRenderer.render(spec, bare, pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.issues())
        .extracting(Issue::variableName)
        .containsExactly("brand", "category", "sku_name");
    assertThat(rendered.issues())
        .allSatisfy(i -> assertThat(i.issueKind()).isIn("EMPTY_FACT", "EMPTY_PHRASE_POOL"));
    assertThat(rendered.text()).doesNotContain("{").doesNotContain("}");
  }

  /**
   * 同一个缺失变量在一段正文里出现多次，只报一条。
   *
   * <p>否则「brand 在这段里出现 3 次」会变成 3 条一模一样的原因， 而 UNIQUE(tenant_id,section_id,variable_name) 也会直接撞约束。
   */
  @Test
  void repeatedMissingVariableIsReportedOnlyOnce() {
    var noBrand = new SkuFacts(SKU, "SPK-001", "桌面款", "蓝牙音箱", "", "音频", "", List.of());
    var spec =
        spec("{brand} 音箱，{brand} 值得信赖，{brand} 品质可靠。", new VariableSpec("brand", "SPU_BRAND", 0));

    var rendered = DescriptionRenderer.render(spec, noBrand, pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.issues()).hasSize(1);
    assertThat(rendered.text()).doesNotContain("{").isEqualTo("音箱， 值得信赖， 品质可靠。");
  }

  /** 同 SPU 只有自己时，「适配型号」变量没法填 —— 报出来并说明怎么修。 */
  @Test
  void siblingListIsReportedWhenSpuHasNoOtherSku() {
    var alone = new SkuFacts(SKU, "SPK-001", "桌面款", "蓝牙音箱", "星云", "音频", "", List.of());
    var spec = spec("适配型号：{siblings}。", new VariableSpec("siblings", "SKU_SIBLING_LIST", 0));

    var rendered = DescriptionRenderer.render(spec, alone, pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.issues().get(0).issueKind()).isEqualTo("EMPTY_FACT");
    assertThat(rendered.issues().get(0).detail()).contains("再加几个 SKU");
    assertThat(rendered.text()).isEqualTo("适配型号：。");

    // 有兄弟 SKU 时正常填充，按 code 排序拼接。
    var withSiblings =
        new SkuFacts(SKU, "SPK-001", "", "", "星云", "", "", List.of("SPK-002", "SPK-003"));
    assertThat(DescriptionRenderer.render(spec, withSiblings, pools(), 0).text())
        .isEqualTo("适配型号：SPK-002 / SPK-003。");
  }

  /**
   * 正文里有占位符但模板没声明绑定源 —— 报告它，不静默。
   *
   * <p>保存模板时 {@code variableBindingProblem} 已拒，所以这只能由「模板改过而译文没跟上」 造成；引擎仍然必须守住最后一道，否则 {@code
   * {color}} 会原样进正文。
   */
  @Test
  void unboundPlaceholderIsReportedInsteadOfLeakingIntoText() {
    var spec = spec("{brand} 音箱 {color}。", new VariableSpec("brand", "SPU_BRAND", 0));
    var rendered = DescriptionRenderer.render(spec, facts(), pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.issues()).hasSize(1);
    assertThat(rendered.issues().get(0).issueKind()).isEqualTo("UNBOUND_PLACEHOLDER");
    assertThat(rendered.issues().get(0).variableName()).isEqualTo("color");
    assertThat(rendered.text()).doesNotContain("{").doesNotContain("}");
  }

  /** INCOMPLETE 也必须给出一段正文（运营要看「现在长什么样」才能判断影响面）。 */
  @Test
  void incompleteSectionStillHasReadableTextWithoutPlaceholders() {
    var noBrand = new SkuFacts(SKU, "SPK-001", "桌面款", "蓝牙音箱", "", "音频", "", List.of());
    var spec =
        spec(
            "【{brand}】{category}，{selling}，适用于 {siblings}。",
            new VariableSpec("brand", "SPU_BRAND", 0),
            new VariableSpec("category", "SPU_CATEGORY", 1),
            new VariableSpec("selling", "PHRASE_SELLING", 2),
            new VariableSpec("siblings", "SKU_SIBLING_LIST", 3));

    var rendered = DescriptionRenderer.render(spec, noBrand, pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.text()).doesNotContain("{").doesNotContain("}");
    // 剥空之后不留双空格，也不留「，」这种悬空标点。
    assertThat(rendered.text()).doesNotContain("  ");
    assertThat(rendered.text()).doesNotContain("，,");
    assertThat(rendered.charLength()).isGreaterThan(0);
  }

  // ------------------------------------------------------------ 确定性与批量

  /** 同样输入永远同样输出：短语按序号错开取，不掷骰子。 */
  @Test
  void outputIsDeterministicAndPhrasePoolsRotateByOrdinal() {
    var spec = spec("{selling}", new VariableSpec("selling", "PHRASE_SELLING", 0));

    var first = DescriptionRenderer.render(spec, facts(), pools(), 0);
    var again = DescriptionRenderer.render(spec, facts(), pools(), 0);
    assertThat(again.text()).isEqualTo(first.text());
    assertThat(again.usedPhrases()).isEqualTo(first.usedPhrases());

    // 不同 SKU 序号拿到不同短语：一批描述读起来有变化，但完全可复现。
    assertThat(DescriptionRenderer.render(spec, facts(), pools(), 0).text()).isEqualTo("双单元立体声");
    assertThat(DescriptionRenderer.render(spec, facts(), pools(), 1).text()).isEqualTo("高保真音质");
    assertThat(DescriptionRenderer.render(spec, facts(), pools(), 2).text()).isEqualTo("双单元立体声");
    // 负数序号也不该抛（Math.floorMod 的语义）。
    assertThat(DescriptionRenderer.render(spec, facts(), pools(), -1).text()).isEqualTo("高保真音质");
  }

  /**
   * 批量填充不提前退出：前面缺变量时后面的 SKU 照样要填。
   *
   * <p>否则「第 3 个 SKU 有问题」会掩盖第 47 个也缺变量的事实， 而运营恰恰要知道整批缺了什么才能一次性修完。
   */
  @Test
  void renderAllDoesNotStopAtTheFirstMissingVariable() {
    var spec = spec("{brand} 音箱。", new VariableSpec("brand", "SPU_BRAND", 0));
    var skus =
        List.of(
            new SkuFacts(SKU, "A", "", "", "", "", "", List.of()),
            new SkuFacts(SKU, "B", "", "", "星云", "", "", List.of()),
            new SkuFacts(SKU, "C", "", "", "", "", "", List.of()));

    var rendered = DescriptionRenderer.renderAll(spec, skus, pools());

    assertThat(rendered).hasSize(3);
    assertThat(rendered.get(0).complete()).isFalse();
    assertThat(rendered.get(1).complete()).isTrue();
    assertThat(rendered.get(2).complete()).isFalse();
    assertThat(rendered).allSatisfy(r -> assertThat(r.text()).doesNotContain("{"));
  }

  /** 每个 SKU 一段，序号就是批次内的下标 —— 落库后短语错开可复现。 */
  @Test
  void renderAllGivesEachSkuItsOwnOrdinal() {
    var spec = spec("{selling}", new VariableSpec("selling", "PHRASE_SELLING", 0));
    var skus = List.of(facts(), facts(), facts());

    var rendered = DescriptionRenderer.renderAll(spec, skus, pools());

    assertThat(rendered).extracting(Rendered::text).containsExactly("双单元立体声", "高保真音质", "双单元立体声");
  }

  // ------------------------------------------------------------ 固定文本与不变量

  /** 无变量的注意事项原样通过（不要求至少一个占位符）。 */
  @Test
  void fixedTextWithoutVariablesPassesThroughUntouched() {
    var rendered = DescriptionRenderer.render(spec("请勿在充电时拆解本机。"), facts(), emptyPools(), 0);

    assertThat(rendered.complete()).isTrue();
    assertThat(rendered.issues()).isEmpty();
    assertThat(rendered.text()).isEqualTo("请勿在充电时拆解本机。");
    assertThat(rendered.usedPhrases()).isEmpty();
  }

  /** 模板没声明变量时正文里的占位符全部按未绑定报告，一个都不留在正文。 */
  @Test
  void templateWithNoVariableDeclarationsStillReportsEveryPlaceholder() {
    var rendered = DescriptionRenderer.render(spec("{brand} {selling}"), facts(), pools(), 0);

    assertThat(rendered.complete()).isFalse();
    assertThat(rendered.text()).doesNotContain("{").doesNotContain("}");
    assertThat(rendered.issues())
        .extracting(Issue::variableName)
        .containsExactlyInAnyOrder("brand", "selling");
  }

  /** 英文与 emoji 按码点算长度，不被 UTF-16 切成两倍。 */
  @Test
  void charLengthIsCountedInCodePoints() {
    var spec = spec("Model {sku_name}", new VariableSpec("sku_name", "SKU_NAME", 0));
    var rendered = DescriptionRenderer.render(spec, facts(), pools(), 0);

    assertThat(rendered.text()).isEqualTo("Model 桌面款");
    assertThat(rendered.charLength()).isEqualTo("Model 桌面款".length());
  }

  /** 模板结构与模板管理器共用同一套规范化，标题里多个空格不会变成多个空洞。 */
  @Test
  void whitespaceIsNormalizedBeforeSubstitution() {
    var spec =
        spec(
            "  {brand}\n\n  音箱  {selling}  ",
            new VariableSpec("brand", "SPU_BRAND", 0),
            new VariableSpec("selling", "PHRASE_SELLING", 1));
    var rendered = DescriptionRenderer.render(spec, facts(), pools(), 0);

    assertThat(rendered.complete()).isTrue();
    assertThat(rendered.text()).isEqualTo("星云 音箱 双单元立体声");
  }
}
