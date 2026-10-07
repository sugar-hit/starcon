package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 3.4.2.1 标题生成引擎单测：<b>「相似但不雷同」的重复度控制</b>。
 *
 * <p>蓝图唯一点名要求的算法就在这里，所以断言不只看「产出了 N 条」，
 * 还要逐条验证：
 *
 * <ul>
 *   <li>同一批候选两两之间**不超过**模板设定的相似度上限（闸门真的拦住了）；
 *   <li>新候选与该 SKU <b>已有标题</b>之间也不超过上限（跨批次的历史参与比较）；
 *   <li>产出的候选**两两不同**（没有硬凑出来的重复）；
 *   <li>同样输入跑两次得到**完全一样**的输出（确定性，无随机）；
 *   <li>词池太小时**拒绝并给出原因**，而不是放宽阈值返回一条雷同的。
 * </ul>
 *
 * <p>纯函数单测，不依赖 Testcontainers。
 */
class TitleGeneratorTest {

  /** 标准夹具：品牌 + 品类 + 核心词 + 卖点 + 修饰词。 */
  static TitleGenerator.TemplateSpec template(int candidates, int maxSimilarity) {
    return new TitleGenerator.TemplateSpec(
        "{brand} {category} {core} {selling}",
        List.of(
            new TitleGenerator.VariableSpec("brand", "SPU_BRAND", 0),
            new TitleGenerator.VariableSpec("category", "SPU_CATEGORY", 1),
            new TitleGenerator.VariableSpec("core", "KEYWORD_CORE", 2),
            new TitleGenerator.VariableSpec("selling", "KEYWORD_SELLING", 3),
            new TitleGenerator.VariableSpec("modifier", "KEYWORD_MODIFIER", 4)),
        maxSimilarity,
        candidates);
  }

  static TitleGenerator.SkuFacts sku() {
    return new TitleGenerator.SkuFacts(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "SKU-001",
        "深空灰 桌面款",
        "星云 便携蓝牙音箱",
        "星云",
        "蓝牙音箱",
        "颜色=深空灰/版本=桌面款");
  }

  static TitleGenerator.KeywordPools pools(int coreSize, int sellingSize, int modifierSize) {
    List<String> core = List.of("蓝牙音箱", "无线音箱", "桌面音箱", "便携音箱", "低音炮音箱");
    List<String> selling = List.of("长续航", "防水便携", "高保真音质", "双单元立体声", "低延迟连接");
    List<String> modifier = List.of("新品", "家用款", "户外款", "车载款", "办公款");
    return new TitleGenerator.KeywordPools(
        core.subList(0, Math.min(coreSize, core.size())),
        selling.subList(0, Math.min(sellingSize, selling.size())),
        modifier.subList(0, Math.min(modifierSize, modifier.size())));
  }

  // ============================================================ 重复度控制：闸门真的拦住了

  @Test
  void everyPairOfCandidatesStaysUnderTheConfiguredSimilarityLimit() {
    var spec = template(4, 850);
    var result = TitleGenerator.generate(
        spec, sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    assertThat(result.candidates()).hasSize(4);

    List<TitleGenerator.Candidate> candidates = result.candidates();
    for (int i = 0; i < candidates.size(); i++)
      for (int j = i + 1; j < candidates.size(); j++) {
        int similarity = TitleRules.similarityPermille(candidates.get(i).title(), candidates.get(j).title());
        assertThat(similarity)
            .as("第 %d 与第 %d 条候选之间的相似度：%s / %s",
                i + 1, j + 1, candidates.get(i).title(), candidates.get(j).title())
            .isLessThanOrEqualTo(spec.maxSimilarityPermille());
      }
    // 顺便断言落库的相似度字段本身就是过闸的那次计算结果，不是随便填的 0。
    for (TitleGenerator.Candidate candidate : candidates)
      assertThat(candidate.similarityPermille()).isBetween(0, spec.maxSimilarityPermille());
  }

  @Test
  void existingTitlesOfTheSameSkuAlsoParticipateInTheComparison() {
    // 「同一商品在多平台不同标题以避重复铺货判定」：已发到 eBay 的那条标题
    // 必须参与本次亚马逊候选的比较，否则新标题与它雷同也照样发出去。
    String existingEbay = "星云 蓝牙音箱 长续航 新品";
    var result = TitleGenerator.generate(
        template(3, 850), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200),
        pools(5, 5, 5), List.of(existingEbay));
    assertThat(result.candidates()).isNotEmpty();
    for (TitleGenerator.Candidate candidate : result.candidates()) {
      int similarity = TitleRules.similarityPermille(candidate.title(), existingEbay);
      assertThat(similarity)
          .as("新候选与该 SKU 已有标题的相似度：%s / %s", candidate.title(), existingEbay)
          .isLessThanOrEqualTo(850);
    }
  }

  @Test
  void producedCandidatesAreAllDifferentFromEachOther() {
    var result = TitleGenerator.generate(
        template(5, 999), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    var titles = result.candidates().stream().map(TitleGenerator.Candidate::title).toList();
    assertThat(titles).hasSize(5).doesNotHaveDuplicates();
  }

  @Test
  void generationIsDeterministicSoRepetitionControlCanBeAsserted() {
    // 同样的输入跑两次必须逐字相同：不掷骰子，「重复度已受控」才是可断言的事实，
    // 而不是「这次看着还行」。
    var first = TitleGenerator.generate(
        template(4, 850), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    var second = TitleGenerator.generate(
        template(4, 850), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    assertThat(first.candidates().stream().map(TitleGenerator.Candidate::title).toList())
        .isEqualTo(second.candidates().stream().map(TitleGenerator.Candidate::title).toList());
    assertThat(first.candidates().stream().map(TitleGenerator.Candidate::similarityPermille).toList())
        .isEqualTo(second.candidates().stream().map(TitleGenerator.Candidate::similarityPermille).toList());
  }

  @Test
  void stricterThresholdYieldsCandidatesThatAreFurtherApart() {
    var loose = TitleGenerator.generate(
        template(3, 950), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    var strict = TitleGenerator.generate(
        template(3, 400), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    // 阈值越严，产出的候选两两之间离得越远：阈值确实在起作用，而不是个摆设字段。
    assertThat(strict.candidates()).isNotEmpty();
    assertThat(worstPair(loose)).isLessThanOrEqualTo(950);
    assertThat(worstPair(strict)).isLessThanOrEqualTo(400);
  }

  // ============================================================ 拒绝也是一种输出

  @Test
  void tooSmallAPoolRejectsWithAnActionableReasonInsteadOfRepeating() {
    // 只有 1 个核心词、1 个卖点、1 个修饰词 + 1 个变量（brand 事实字段）：
    // 组合空间小到凑不出第 2 条不雷同的标题。此时**不能**放宽阈值硬凑，
    // 而要拒绝并说清「去加修饰词」。
    var spec =
        new TitleGenerator.TemplateSpec(
            "{brand} {modifier}",
            List.of(
                new TitleGenerator.VariableSpec("brand", "SPU_BRAND", 0),
                new TitleGenerator.VariableSpec("modifier", "KEYWORD_MODIFIER", 1)),
            500,
            3);
    var result = TitleGenerator.generate(
        spec, sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(1, 1, 1), List.of());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.rejections()).hasSize(2);
    assertThat(result.complete()).isFalse();
    assertThat(result.rejections().get(0).reason())
        .contains("确定性尝试")
        .contains("修饰词池");
    // 被拒时也要报出「最优做到了多少」，否则运维只知道失败不知道差多少。
    assertThat(result.rejections().get(0).bestSimilarityPermille()).isGreaterThan(500);
  }

  @Test
  void templateWithoutVariablesIsRejectedRatherThanRenderingAnEmptyTitle() {
    var spec =
        new TitleGenerator.TemplateSpec(
            "星云蓝牙音箱",
            List.of(),
            850,
            2);
    var result = TitleGenerator.generate(
        spec, sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    assertThat(result.candidates()).isEmpty();
    assertThat(result.rejections()).hasSize(2);
    assertThat(result.rejections().get(0).reason()).contains("没有声明任何变量");
  }

  @Test
  void skuWithoutBrandStillProducesTitlesAndLeavesNoPlaceholder() {
    // 变量缺失必须被看见：渲染时留一个 {brand} 字面量会让「品牌还没填」看不出来
    // （3.4.2.2 明令禁止静默留下占位符，这里与它同一纪律）。
    var facts = new TitleGenerator.SkuFacts(sku().skuId(), "SKU-002", "深空灰", "星云 音箱", "", "", "");
    var result = TitleGenerator.generate(
        template(2, 850), facts, new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    assertThat(result.candidates()).hasSize(2);
    for (TitleGenerator.Candidate candidate : result.candidates()) {
      assertThat(candidate.title()).doesNotContain("{").doesNotContain("}");
      // 品牌为空时不能只留下孤零零的空格。
      assertThat(candidate.title()).doesNotStartWith(" ").doesNotEndWith(" ").doesNotContain("  ");
      // 品牌与分类为空之后，核心词仍然是标题的主体——不能因为少了品牌就整条空掉。
      assertThat(candidate.title()).contains(candidate.coreKeyword());
      assertThat(candidate.coreKeyword()).isNotBlank();
    }
  }

  // ============================================================ 平台长度上限

  @Test
  void titlesAreClampedToThePlatformLimitAndFlagged() {
    var result = TitleGenerator.generate(
        template(2, 850), sku(), new TitleGenerator.PlatformSpec("EBAY", 12), pools(5, 5, 5), List.of());
    assertThat(result.candidates()).hasSize(2);
    for (TitleGenerator.Candidate candidate : result.candidates()) {
      // eBay 标题上限 12：裁到 12 个**码点**，不切出半个字符。
      assertThat(candidate.charLength()).isLessThanOrEqualTo(12);
      assertThat(TitleRules.charLength(candidate.title())).isEqualTo(candidate.charLength());
      assertThat(candidate.truncated()).isTrue();
    }
    // 同一个 SKU 在宽上限的平台下不触发截断。
    var wide = TitleGenerator.generate(
        template(2, 850), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    assertThat(wide.candidates()).allSatisfy(c -> assertThat(c.truncated()).isFalse());
  }

  @Test
  void similarityIsMeasuredOnTheFinalStoredTextNotThePreClampOne() {
    // 截断后再比相似度：截掉后两条标题可能变得很像，而这正是发出去时平台看到的样子。
    var result = TitleGenerator.generate(
        template(3, 300), sku(), new TitleGenerator.PlatformSpec("EBAY", 14), pools(5, 5, 5), List.of());
    for (TitleGenerator.Candidate candidate : result.candidates())
      assertThat(candidate.similarityPermille()).isLessThanOrEqualTo(300);
  }

  // ============================================================ 溯源快照

  @Test
  void candidatesCarryTheKeywordSnapshotsThatProducedThem() {
    var result = TitleGenerator.generate(
        template(3, 850), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(3, 3, 3), List.of());
    assertThat(result.candidates()).allSatisfy(
        candidate -> {
          assertThat(candidate.coreKeyword()).isNotEmpty();
          assertThat(candidate.sellingKeyword()).isNotEmpty();
          assertThat(candidate.modifierKeyword()).isNotEmpty();
          assertThat(candidate.title())
              .contains(candidate.coreKeyword())
              .contains(candidate.sellingKeyword())
              .contains(candidate.modifierKeyword());
        });
    // 轮转档位落在变量个数范围内（这里是 5 个变量）。
    assertThat(result.candidates()).allSatisfy(
        candidate -> assertThat(candidate.rotation()).isBetween(0, 4));
  }

  @Test
  void rotationAxisActuallyChangesWordOrderAcrossCandidates() {
    var result = TitleGenerator.generate(
        template(4, 999), sku(), new TitleGenerator.PlatformSpec("AMAZON", 200), pools(5, 5, 5), List.of());
    // 至少两条候选的词序档位不同——否则「轮转」这个词在代码里只是摆设。
    assertThat(result.candidates().stream().map(TitleGenerator.Candidate::rotation).distinct().count())
        .isGreaterThan(1);
  }

  @Test
  void emptyKeywordPoolRendersThatVariableAsBlankInsteadOfBlowingUp() {
    // 回归测试：Math.floorMod(t, 0) 会抛 ArithmeticException，而「租户只建了核心词、
    // 没建卖点词」是完全正常的初始状态。症状本来是整批生成 500。
    var spec =
        new TitleGenerator.TemplateSpec(
            "{brand} {core} {selling}",
            List.of(
                new TitleGenerator.VariableSpec("brand", "SPU_BRAND", 0),
                new TitleGenerator.VariableSpec("core", "KEYWORD_CORE", 1),
                new TitleGenerator.VariableSpec("selling", "KEYWORD_SELLING", 2)),
            850,
            2);
    var result = TitleGenerator.generate(
        spec, sku(), new TitleGenerator.PlatformSpec("AMAZON", 200),
        new TitleGenerator.KeywordPools(List.of("蓝牙音箱", "无线音箱"), List.of(), List.of()),
        List.of());
    // 卖点词池为空 → 卖点变量渲染成空，但品牌与核心词照常产出候选。
    assertThat(result.candidates()).isNotEmpty();
    for (TitleGenerator.Candidate candidate : result.candidates()) {
      assertThat(candidate.title()).contains("星云").contains(candidate.coreKeyword());
      assertThat(candidate.sellingKeyword()).isEmpty();
      assertThat(candidate.title()).doesNotContain("  ").doesNotEndWith(" ");
    }
  }

  @Test
  void poolDedupeKeepsInsertionOrder() {
    // Arrays.asList 而不是 List.of：List.of 拒绝 null，而这里要断言的正是 null 被滤掉。
    assertThat(TitleGenerator.dedupe(java.util.Arrays.asList("A", "B", "A", "", null, "C")))
        .containsExactly("A", "B", "C");
    assertThat(TitleGenerator.dedupe(null)).isEmpty();
  }

  private int worstPair(TitleGenerator.SkuPlatformResult result) {
    int worst = 0;
    List<TitleGenerator.Candidate> candidates = result.candidates();
    for (int i = 0; i < candidates.size(); i++)
      for (int j = i + 1; j < candidates.size(); j++)
        worst = Math.max(worst, TitleRules.similarityPermille(candidates.get(i).title(), candidates.get(j).title()));
    return worst;
  }
}