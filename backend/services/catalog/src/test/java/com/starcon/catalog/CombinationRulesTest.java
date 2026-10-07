package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.CombinationRules.Part;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 3.1.3.2 组合纯规则：签名、批次内重复定位、缺轴判定、矩阵展开与规模上限。
 *
 * <p>与前端 {@code combination-model.ts} 同口径。这一层刻意不碰数据库：
 * "签名怎么拼、重复怎么定位、缺哪条轴"是纯计算，可以在这里穷举完，
 * 不必为了验证一个字符串顺序去起一次真实 PostgreSQL。
 */
class CombinationRulesTest {

  @Test
  void signatureIsSortedByAxisCodeSoSubmissionOrderDoesNotMatter() {
    // 同一个组合，先写 SIZE 再写 COLOR：签名必须与先写 COLOR 再写 SIZE 完全一致。
    // 否则唯一性只在"按固定顺序提交"时成立——两个人各写一遍就成了两个不同组合。
    var forward =
        CombinationRules.signature(
            List.of(new Part("COLOR", "红色"), new Part("SIZE", "大")), Set.of());
    var backward =
        CombinationRules.signature(
            List.of(new Part("SIZE", "大"), new Part("COLOR", "红色")), Set.of());
    assertThat(forward.problem()).isNull();
    assertThat(forward.signature()).isEqualTo(backward.signature());
    assertThat(forward.signature()).isEqualTo("COLOR=红色|SIZE=大");
  }

  @Test
  void signatureSortIsByteOrderSoItMatchesTheDatabaseRecomputation() {
    // V10 的延迟约束触发器用 ORDER BY axis_code COLLATE "C" 重算同一串签名。
    // 'Z'(0x5A) < '_'(0x5F) < 'a'... 编码被限死为大写 [A-Z0-9_-]，所以逐字节序即可判定，
    // 这里的断言就是"Java 与 PG 不会分叉"的证据。
    var signature =
        CombinationRules.signature(
            List.of(new Part("A0", "x"), new Part("Z9", "y"), new Part("A_", "z"), new Part("-A", "w")),
            Set.of());
    assertThat(signature.signature()).isEqualTo("-A=w|A0=x|A_=z|Z9=y");
  }

  @Test
  void valuesContainingSlashesOrSpacesDoNotBreakTheSignature() {
    // 值源字符集允许空格与点斜杠：拼接结果必须仍然能被人读懂，不能被内容"吃掉"。
    var signature =
        CombinationRules.signature(
            List.of(new Part("COLOR", "黑色/哑光"), new Part("SIZE", "2 件装")), Set.of());
    assertThat(signature.signature()).isEqualTo("COLOR=黑色/哑光|SIZE=2 件装");
  }

  @Test
  void unresolvableCombinationIsRejectedInsteadOfGettingAnEmptySignature() {
    // 签名算不出来是一种业务结论，不能当成"签名是空串"混过去——
    // 空串会让两个不同的非法组合撞进唯一索引。
    assertThat(CombinationRules.signature(List.of(), Set.of()).signature()).isNull();
    assertThat(CombinationRules.signature(List.of(), Set.of()).problem()).contains("组合不能为空");
    assertThat(
            CombinationRules.signature(
                    List.of(new Part("COLOR", "红色"), new Part("SIZE", "")), Set.of())
                .problem())
        .contains("缺少取值");
    assertThat(CombinationRules.signature(List.of(new Part(" ", "红色")), Set.of()).problem())
        .contains("缺少编码");
  }

  @Test
  void duplicateAxisCodesAreReportedSoTheCombinationGetsNoSignature() {
    var codes = CombinationRules.duplicateAxisCodes(List.of("COLOR", "SIZE", "COLOR", "SIZE"));
    assertThat(codes).containsExactly("COLOR", "SIZE");
    var signature =
        CombinationRules.signature(
            List.of(new Part("COLOR", "红"), new Part("COLOR", "蓝")), codes);
    // 同一条轴在一个组合里取两个值，连"是不是一个组合"都谈不上，因此不给签名。
    assertThat(signature.signature()).isNull();
    assertThat(signature.problem()).contains("COLOR");
  }

  @Test
  void duplicatesAreLocatedWithTheirIndexesNotJustCounted() {
    // 运营要知道"第几条和第几条撞了"，只回一个布尔值没法干活。
    // 列表里混一个 null（算不出签名的那条）：它不参与去重，也不该让整个查询炸掉。
    var duplicates =
        CombinationRules.duplicates(
            Arrays.asList("COLOR=红色|SIZE=大", "COLOR=蓝色|SIZE=大", "COLOR=红色|SIZE=大", null));
    assertThat(duplicates).containsOnlyKeys("COLOR=红色|SIZE=大");
    assertThat(duplicates.get("COLOR=红色|SIZE=大")).containsExactly(0, 2);
  }

  @Test
  void missingAndForeignAxisCodesAreSeparated() {
    var bound = Set.of("COLOR", "SIZE");
    assertThat(CombinationRules.missingAxisCodes(bound, Set.of("COLOR"))).containsExactly("SIZE");
    assertThat(CombinationRules.missingAxisCodes(bound, Set.of("COLOR", "SIZE"))).isEmpty();
    // 混用别的 SPU 的轴要和"漏填"分开报：处理方式完全不同（一个去删，一个去补）。
    assertThat(CombinationRules.foreignAxisCodes(bound, Set.of("COLOR", "SIZE", "CAPACITY")))
        .containsExactly("CAPACITY");
    assertThat(CombinationRules.foreignAxisCodes(bound, Set.of("COLOR"))).isEmpty();
  }

  @Test
  void matrixIsTheCartesianProductOfEnabledOptions() {
    var expanded =
        CombinationRules.expand(
            List.of(List.of("红", "蓝"), List.of("S", "M", "L")), CombinationRules.MAX_DRAFT_COMBINATIONS);
    assertThat(expanded).hasSize(6);
    assertThat(expanded).contains(List.of("红", "S"), List.of("蓝", "L"));
    assertThat(
            CombinationRules.matrixSize(List.of(List.of("红", "蓝"), List.of("S", "M", "L"))))
        .isEqualTo(6);
  }

  @Test
  void axisWithoutEnabledOptionMakesTheMatrixEmptyInsteadOfOneRow() {
    // "没绑轴"与"绑了但没取值"都画成矩阵 0 行是对的，但**不能**按 1 假装有值：
    // 那会让面板显示出 1 条可生成组合，实际一条也生成不出来。
    assertThat(
            CombinationRules.expand(
                List.of(List.of("红"), List.of()), CombinationRules.MAX_DRAFT_COMBINATIONS))
        .isEmpty();
    assertThat(CombinationRules.matrixSize(List.of(List.of("红"), List.of()))).isZero();
    assertThat(CombinationRules.expand(List.of(), CombinationRules.MAX_DRAFT_COMBINATIONS)).isEmpty();
    assertThat(CombinationRules.matrixSize(List.of())).isZero();
  }

  @Test
  void oversizedMatrixIsRejectedWithTheRealRowCountInsteadOfBeingTruncated() {
    // 61 × 61 = 3721 行，超过默认上限 2000。截断出来的草稿会让人以为矩阵就这么多，
    // 所以宁可报真实行数当场拒绝。
    var options = new ArrayList<String>();
    for (int i = 0; i < 61; i++) options.add("V" + i);
    assertThatThrownBy(
            () -> CombinationRules.expand(List.of(options, options), CombinationRules.MAX_DRAFT_COMBINATIONS))
        .isInstanceOf(CombinationRules.MatrixTooLarge.class)
        .satisfies(
            e -> {
              var tooLarge = (CombinationRules.MatrixTooLarge) e;
              assertThat(tooLarge.cap()).isEqualTo(CombinationRules.MAX_DRAFT_COMBINATIONS);
              assertThat(tooLarge.actual()).isEqualTo(3721L);
            });
  }

  @Test
  void matrixSizeDoesNotOverflowIntoANegativeNumber() {
    // 10 条各 100 个选项 = 10^20，早就越过 long 上限。
    // 界面必须显示"规模过大"（钳到上限），而不是 int/long 溢出后的负数或一个错的数。
    var hundred = new ArrayList<String>();
    for (int i = 0; i < 100; i++) hundred.add("V" + i);
    var lists = new ArrayList<List<String>>();
    for (int i = 0; i < 10; i++) lists.add(hundred);
    assertThat(CombinationRules.matrixSize(lists)).isEqualTo(Long.MAX_VALUE);
    // 刚好不越界的那个数要如实算出来，不能提前钳。
    assertThat(CombinationRules.matrixSize(List.of(hundred, hundred))).isEqualTo(10_000L);
  }

  @Test
  void axisCountCapIsEnforcedSoOneCombinationCannotSwallowTheWholeDictionary() {
    // 13 条轴各 1 个取值 = 13 行矩阵，轴数超过单组合上限 12。
    // 服务层在调用 expand 之前已经先查过轴数（返回 400），这里是最后一道兜底。
    var axes = new ArrayList<List<String>>();
    for (int i = 0; i < CombinationRules.MAX_COMBINATION_AXES + 1; i++) axes.add(List.of("V" + i));
    assertThatThrownBy(() -> CombinationRules.expand(axes, 100))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(String.valueOf(CombinationRules.MAX_COMBINATION_AXES));
    // 刚好等于上限时照常展开。
    var atCap = new ArrayList<>(axes.subList(0, CombinationRules.MAX_COMBINATION_AXES));
    assertThat(CombinationRules.expand(atCap, 100)).hasSize(1);
  }
}