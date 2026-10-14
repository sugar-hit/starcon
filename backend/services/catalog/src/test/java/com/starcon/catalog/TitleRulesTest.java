package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.4.2.1 标题库纯规则层单测：相似度算法、模板与词根校验、容量上限。
 *
 * <p>不依赖 Testcontainers：这些是纯函数，相似度是蓝图唯一点名要求的算法， 必须能被逐条断言，而不是只能靠集成测试间接观察。
 */
class TitleRulesTest {

  // ============================================================ 相似度：这是本模块被点名的算法

  @Test
  void identicalTitlesScoreExactlyOneThousandPermille() {
    // 完全相同 = 1000‰。这也是 V20 把阈值上界钉在 999 的原因：
    // 一旦允许 1000，就等于允许完全雷同，重复度控制形同虚设。
    assertThat(TitleRules.similarityPermille("星云 便携 蓝牙音箱", "星云 便携 蓝牙音箱")).isEqualTo(1000);
    // 全角与半角、大小写差异是同一个标题：NFKC 归一之后仍然 1000。
    assertThat(TitleRules.similarityPermille("StarCloud Speaker", "ｓｔａｒｃｌｏｕｄ　Speaker"))
        .isEqualTo(1000);
  }

  @Test
  void completelyDifferentTitlesScoreNearZero() {
    // 词袋与二元组都没有交集 → 0‰。分母为 0 时也要有定义，不能抛异常。
    assertThat(TitleRules.similarityPermille("无线蓝牙音箱", "厨房不锈钢砧板")).isZero();
    assertThat(TitleRules.similarityPermille("", "无线蓝牙音箱")).isZero();
    assertThat(TitleRules.similarityPermille(null, null)).isZero();
  }

  @Test
  void reorderingTheSameWordsReducesSimilarityButKeepsItHigh() {
    int same = TitleRules.similarityPermille("星云 蓝牙 音箱 降噪", "星云 蓝牙 音箱 降噪");
    int reordered = TitleRules.similarityPermille("星云 蓝牙 音箱 降噪", "降噪 音箱 蓝牙 星云");
    // 词袋完全相同（dice=1000），但相邻关系变了，所以整体分低于 1000 而仍很高。
    assertThat(reordered).isLessThan(same);
    assertThat(reordered).isGreaterThan(700);
  }

  @Test
  void oneWordChangedMovesSimilarityButNotToZero() {
    String base = "星云 无线 蓝牙 音箱 长续航";
    int unchanged = TitleRules.similarityPermille(base, "星云 无线 蓝牙 音箱 长续航");
    int oneChanged = TitleRules.similarityPermille(base, "星云 无线 蓝牙 音箱 超长续航");
    int other = TitleRules.similarityPermille(base, "厨房 无线 蓝牙 音箱 长续航");
    assertThat(oneChanged).isLessThan(unchanged).isGreaterThan(500);
    assertThat(other).isLessThan(oneChanged);
  }

  @Test
  void similarityIsDeterministicAndWithinRange() {
    String[] samples = {
      "星云 便携 蓝牙音箱 40W 长续航",
      "AOOSH 桌面 显示器支架 铝合金",
      "Kitchen Scale Digital 10kg",
      "无线蓝牙音箱 长续航 便携 40W",
      "厨房 不锈钢 砧板 防滑"
    };
    for (String a : samples)
      for (String b : samples) {
        int once = TitleRules.similarityPermille(a, b);
        int twice = TitleRules.similarityPermille(a, b);
        // 确定性是「重复度已受控」可被断言的前提：同样输入永远同样输出，全程无随机。
        assertThat(twice).as("确定性：%s vs %s", a, b).isEqualTo(once);
        // 取值域是 0~1000：**完全相同的标题就是 1000**。
        // 999 是**阈值**的天花板（TitleRules.MAX_SIMILARITY_PERMILLE），
        // 两者不是一回事——相似度函数如实报出 1000，由闸门去拒绝它。
        assertThat(once).isBetween(0, 1000);
        assertThat(TitleRules.MAX_SIMILARITY_PERMILLE).isLessThan(1000);
        // 对称性：相似度不能因为比较方向不同而变。
        assertThat(TitleRules.similarityPermille(b, a)).isEqualTo(once);
      }
  }

  @Test
  void maxSimilarityTakesTheWorstOfTheComparisonSet() {
    String candidate = "星云 便携 蓝牙音箱 40W";
    int max =
        TitleRules.maxSimilarityPermille(
            candidate, List.of("厨房 不锈钢 砧板", "星云 便携 蓝牙音箱 40W 长续航", "AOOSH 显示器支架"));
    // 与第二条共享全部词，只多一个「长续航」——所以最大值来自那条。
    assertThat(max).isGreaterThan(700);
    assertThat(max).isEqualTo(TitleRules.similarityPermille(candidate, "星云 便携 蓝牙音箱 40W 长续航"));
    assertThat(TitleRules.maxSimilarityPermille(candidate, List.of())).isZero();
    assertThat(TitleRules.maxSimilarityPermille(candidate, null)).isZero();
  }

  // ============================================================ 分词口径

  @Test
  void tokenizerSplitsCjkByCharacterAndKeepsLatinRunsWhole() {
    // 中文逐字（中文没有空格，逐字切是唯一确定性的口径），
    // 拉丁连续段合并成一个词元（bluetooth5 是一个词，不该被拆成九个）。
    assertThat(TitleRules.tokenize(TitleRules.normalizeForCompare("蓝牙 音箱 bluetooth5")))
        .containsExactly("蓝", "牙", "音", "箱", "bluetooth5");
    // 分隔符被丢掉：它不是词元。
    assertThat(TitleRules.tokenize(TitleRules.normalizeForCompare("a-b_c、d")))
        .containsExactly("a", "b", "c", "d");
    assertThat(TitleRules.tokenize("")).isEmpty();
  }

  @Test
  void bigramsCoverAdjacentCharactersIncludingSeparators() {
    assertThat(TitleRules.bigramSet(TitleRules.normalizeForCompare("ab c")))
        .containsExactlyInAnyOrder("ab", "b ", " c");
    assertThat(TitleRules.bigramSet(TitleRules.normalizeForCompare("a"))).isEmpty();
  }

  @Test
  void charLengthCountsCodePointsNotUtf16Chars() {
    // 字符数按码点算：平台上限是按「人看到的字数」定的，
    // 用 UTF-16 char 数会把 emoji 与 CJK 扩展 B 算成两个字，于是裁剪会切出半个字符。
    assertThat(TitleRules.charLength("蓝牙音箱")).isEqualTo(4);
    assertThat(TitleRules.charLength("🔊蓝牙")).isEqualTo(3);
    assertThat(TitleRules.charLength("")).isZero();
  }

  @Test
  void clampCutsOnCodePointBoundaryAndNeverExceedsTheLimit() {
    assertThat(TitleRules.clampByCharLength("星云蓝牙音箱", 4)).isEqualTo("星云蓝牙");
    assertThat(TitleRules.clampByCharLength("星云蓝牙音箱", 100)).isEqualTo("星云蓝牙音箱");
    // emoji 是代理对的一半时不能切出半个代理对——这里按码点裁，所以剩一个完整的 🔊。
    assertThat(TitleRules.clampByCharLength("🔊🔊🔊", 2)).isEqualTo("🔊🔊");
    assertThat(TitleRules.clampByCharLength("abc", 0)).isEmpty();
  }

  // ============================================================ 重复度阈值

  @Test
  void similarityThresholdUpperBoundIsNineHundredNinetyNine() {
    assertThat(TitleRules.maxSimilarityProblem(null)).contains("不能为空");
    assertThat(TitleRules.maxSimilarityProblem(-1)).isNotNull();
    assertThat(TitleRules.maxSimilarityProblem(1000)).contains("999");
    assertThat(TitleRules.maxSimilarityProblem(0)).isNull();
    assertThat(TitleRules.maxSimilarityProblem(999)).isNull();
    assertThat(TitleRules.MAX_SIMILARITY_PERMILLE).isEqualTo(999);
  }

  @Test
  void candidateCountIsBounded() {
    assertThat(TitleRules.candidateCountProblem(null)).isNotNull();
    assertThat(TitleRules.candidateCountProblem(0)).isNotNull();
    assertThat(TitleRules.candidateCountProblem(9)).contains("1~8");
    assertThat(TitleRules.candidateCountProblem(1)).isNull();
    assertThat(TitleRules.candidateCountProblem(8)).isNull();
  }

  // ============================================================ 模板文本与占位符

  @Test
  void patternMustContainAtLeastOnePlaceholder() {
    // 没有变量的模板渲染出来每条候选都一样——那正是本模块要消灭的重复铺货。
    assertThat(TitleRules.patternProblem("星云蓝牙音箱")).contains("至少要有一个变量占位");
    assertThat(TitleRules.patternProblem("  ")).contains("不能为空");
    assertThat(TitleRules.patternProblem("{brand} {category} {core} {selling}")).isNull();
    assertThat(TitleRules.patternProblem("a".repeat(2001))).contains("最多 2000");
  }

  @Test
  void patternNormalisationCollapsesWhitespaceSoOneTemplateStaysOneTemplate() {
    // 「同一个模板写成两种空白」不该变成两个模板，变量解析也不该被多空格坑到。
    assertThat(TitleRules.normalizePattern("  {brand}   {core}  ")).isEqualTo("{brand} {core}");
    assertThat(TitleRules.patternProblem("{brand}    {core}")).isNull();
  }

  @Test
  void placeholdersAreExtractedInOrderAndDeduplicated() {
    assertThat(TitleRules.placeholdersOf("{brand} {category} {brand} {core}"))
        .containsExactly("brand", "category", "core");
    assertThat(TitleRules.placeholdersOf("没有变量")).isEmpty();
    assertThat(TitleRules.placeholdersOf("{未闭合")).isEmpty();
    assertThat(TitleRules.placeholdersOf("{} {ok}")).containsExactly("ok");
  }

  @Test
  void variableNamesAndSourcesAreValidated() {
    assertThat(TitleRules.variableNameProblem("")).isNotNull();
    assertThat(TitleRules.variableNameProblem("1brand")).contains("英文字母开头");
    assertThat(TitleRules.variableNameProblem("brand-x")).contains("英文字母开头");
    assertThat(TitleRules.variableNameProblem("brand")).isNull();
    assertThat(TitleRules.variableNameProblem("core_word_2")).isNull();

    assertThat(TitleRules.sourceProblem("spu_brand")).isNull();
    assertThat(TitleRules.sourceProblem("KEYWORD_MODIFIER")).isNull();
    assertThat(TitleRules.sourceProblem("LISTING_TITLE")).contains("绑定源只能是");

    assertThat(TitleRules.keywordTypeOfSource("KEYWORD_CORE")).isEqualTo("CORE");
    assertThat(TitleRules.keywordTypeOfSource("KEYWORD_SELLING")).isEqualTo("SELLING");
    assertThat(TitleRules.keywordTypeOfSource("KEYWORD_MODIFIER")).isEqualTo("MODIFIER");
    assertThat(TitleRules.keywordTypeOfSource("SPU_BRAND")).isNull();
  }

  @Test
  void placeholdersAndDeclaredVariablesMustMatchInBothDirections() {
    // 文本里写了却没声明绑定源 → 渲染时拿不到词，会静默留下占位符。
    assertThat(TitleRules.variableBindingProblem("{brand} {core}", List.of("brand")))
        .contains("{core}")
        .contains("没有声明绑定源");
    // 声明了却没在文本里出现 → 死配置，模板含义与实际渲染对不上。
    assertThat(
            TitleRules.variableBindingProblem(
                "{brand} {core}", List.of("brand", "core", "selling")))
        .contains("selling")
        .contains("死配置");
    assertThat(TitleRules.variableBindingProblem("{brand} {core}", List.of("brand", "core")))
        .isNull();
  }

  @Test
  void templateMustBindAtLeastOneKeywordVariable() {
    // 只绑事实字段的话每个 SKU 只有一种文本，候选之间只能靠词序轮转，
    // 轮转档数 = 变量个数，批量生成必然产出彼此雷同的候选。
    assertThat(TitleRules.needsKeywordVariableProblem(List.of("SPU_BRAND", "SPU_CATEGORY")))
        .contains("至少要有一个词根变量");
    assertThat(TitleRules.needsKeywordVariableProblem(List.of())).contains("至少要声明一个变量");
    assertThat(TitleRules.needsKeywordVariableProblem(List.of("SPU_BRAND", "KEYWORD_MODIFIER")))
        .isNull();
  }

  @Test
  void templateCodeNameAndPlatformValidation() {
    assertThat(TitleRules.templateCodeProblem("")).isNotNull();
    assertThat(TitleRules.templateCodeProblem("a b")).contains("英文字母");
    assertThat(TitleRules.templateCodeProblem("amz-main-1")).isNull();
    assertThat(TitleRules.templateNameProblem("x".repeat(101))).contains("最多 100");
    assertThat(TitleRules.platformNameProblem("")).isNotNull();
    assertThat(TitleRules.maxLengthProblem(0)).isNotNull();
    assertThat(TitleRules.maxLengthProblem(256)).contains("1~255");
    assertThat(TitleRules.maxLengthProblem(200)).isNull();
    assertThat(TitleRules.duplicatePlatformProblem(List.of("AMAZON", "ebay", "AMAZON")))
        .contains("平台重复");
  }

  // ============================================================ 词根

  @Test
  void keywordValidation() {
    assertThat(TitleRules.keywordTextProblem("")).isNotNull();
    assertThat(TitleRules.keywordTextProblem("x".repeat(61))).contains("最多 60");
    // 词根里带花括号会让渲染出的标题出现半成品 {x}，禁掉。
    assertThat(TitleRules.keywordTextProblem("{core}")).contains("不能包含花括号");
    assertThat(TitleRules.keywordTextProblem("长续航")).isNull();
    assertThat(TitleRules.keywordTypeProblem("noun")).contains("核心词 / 卖点 / 修饰词");
    assertThat(TitleRules.keywordTypeProblem("modifier")).isNull();
  }

  // ============================================================ 批量参数与词表自检

  @Test
  void batchSizeLimits() {
    assertThat(TitleRules.skusSizeProblem(0)).contains("至少选择");
    assertThat(TitleRules.skusSizeProblem(201)).contains("最多为 200");
    assertThat(TitleRules.skusSizeProblem(200)).isNull();
    assertThat(TitleRules.platformsSizeProblem(0)).contains("至少选择");
    assertThat(TitleRules.platformsSizeProblem(6)).contains("最多为 5");
    assertThat(TitleRules.keywordFilterSizeProblem(21)).contains("最多限定 20");
    assertThat(TitleRules.variablesSizeProblem(13)).contains("最多 12");
  }

  @Test
  void vocabulariesHaveNoDuplicatesAndWeightsSumToOneThousand() {
    // 类初始化里的断言：词表有笔误会在类加载时炸掉，这里再显式确认一次上界。
    assertThat(TitleRules.MAX_ATTEMPTS_PER_CANDIDATE).isEqualTo(24);
    assertThat(TitleRules.DEFAULT_SIMILARITY_PERMILLE)
        .isBetween(0, TitleRules.MAX_SIMILARITY_PERMILLE);
    assertThat(TitleRules.KEYWORD_TYPES).hasSize(3);
    assertThat(TitleRules.RECORD_STATUSES).hasSize(3);
    assertThat(TitleRules.GENERATION_STATUSES).hasSize(3);
  }

  @Test
  void labelsAreReadableAndFallBackToTheRawValue() {
    assertThat(TitleRules.keywordTypeLabel("CORE")).isEqualTo("核心词");
    assertThat(TitleRules.sourceLabel("KEYWORD_MODIFIER")).isEqualTo("修饰词池");
    assertThat(TitleRules.recordStatusLabel("SELECTED")).isEqualTo("已选用");
    assertThat(TitleRules.generationStatusLabel("PARTIAL")).isEqualTo("部分 SKU 未产出");
    assertThat(TitleRules.outcomeLabel("GENERATED")).isEqualTo("已产出候选");
    // 未知值回退成原值而不是空白：界面上不能出现没有名字的状态。
    assertThat(TitleRules.keywordTypeLabel("WHAT")).isEqualTo("WHAT");
  }
}
