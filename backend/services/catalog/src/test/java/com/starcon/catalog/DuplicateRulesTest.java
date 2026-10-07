package com.starcon.catalog;

import static com.starcon.catalog.DuplicateRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;

/**
 * 3.9.1.1 重复 SKU 识别的<b>纯规则</b>单测：归一化、相似度与四条判定线。
 *
 * <p>相似度是本模块最容易「看起来在算、其实是常数」的地方，所以这里不用「不抛异常」当断言，
 * 而是把<b>算得出来的具体数值</b>钉死：中文二元组切词、编辑距离、Jaccard 的交并比、
 * 加权混合的舍入位置，任何一处被改成拍脑袋的常数或调错权重都会在这里变红。
 */
class DuplicateRulesTest {

  // ================================================================ 归一化

  @Test
  void 条码只做确定无歧义的两件事_不剥连字符() {
    assertThat(normalizeBarcode("  6901234567892 ")).isEqualTo("6901234567892");
    assertThat(normalizeBarcode("abc-123")).isEqualTo("ABC-123");
    // 连字符是 GTIN-14 的标准打印形态，剥掉会把两条不同的条码判成同一个。
    assertThat(normalizeBarcode("012345678905")).isNotEqualTo(normalizeBarcode("012-345678905"));
    assertThat(normalizeBarcode(null)).isEmpty();
    assertThat(normalizeBarcode("   ")).isEmpty();
    // 幂等：已经是归一化结果再过一次不改变（服务层会在分组键与判定处各调一次）。
    assertThat(normalizeBarcode(normalizeBarcode(" Ab-1 "))).isEqualTo("AB-1");
  }

  @Test
  void 标题归一化吃掉全角_大小写_标点_空白() {
    assertThat(normalizeTitle("Ｓｏｎｙ ＷＨ－１０００ＸＭ５")).isEqualTo("sonywh1000xm5");
    // 汉字是「字母」的一部分所以被保留（isLetterOrDigit 对汉字为真），
    // 这正是同一段标题要能跨语言对上：中文标题里的汉字本来就是要参与比较的。
    assertThat(normalizeTitle("Sony WH-1000XM5（黑色）")).isEqualTo("sonywh1000xm5黑色");
    assertThat(normalizeTitle("黑色 Sony")).isEqualTo("黑色sony");
    // 只剩标点的标题归一化成空串（后面 titleSimilarity 会按「空标题不算数」处理）。
    assertThat(normalizeTitle("!!!")).isEmpty();
    assertThat(normalizeTitle("  A/B_C  ")).isEqualTo("abc");
    assertThat(normalizeTitle(null)).isEmpty();
    assertThat(normalizeTitle("——")).isEmpty();
  }

  @Test
  void 标题切词对汉字用二元组对拉丁用整词() {
    // 「红色无线耳机」按空格切只有 1 个 token，与词序调换版会算成 0；二元组把它变成 5 个。
    assertThat(titleTokens("红色无线耳机")).containsExactlyInAnyOrder("红色", "色无", "无线", "线耳", "耳机");
    assertThat(titleTokens("pro")).containsExactly("pro");
    // 单个汉字（没有可切的二元组）本身就是 token。
    assertThat(titleTokens("红")).containsExactly("红");
    assertThat(titleTokens("红色 Pro")).contains("红色", "pro");
    assertThat(titleTokens("")).isEmpty();
    assertThat(titleTokens("!!!")).isEmpty();
  }

  // ================================================================ 相似度

  @Test
  void jaccard按交并比算且两边都空时为1() {
    assertThat(jaccard(Set.of("a", "b"), Set.of("a", "b"))).isEqualByComparingTo("1.0000");
    assertThat(jaccard(Set.of("a", "b"), Set.of("b", "c"))).isEqualByComparingTo("0.3333");
    assertThat(jaccard(Set.of("a"), Set.of("b"))).isEqualByComparingTo("0.0000");
    assertThat(jaccard(Set.of(), Set.of())).isEqualByComparingTo("1.0000");
    // 一边空一边不空：并集非空，交集 0 → 0，而不是除零。
    assertThat(jaccard(Set.of(), Set.of("a"))).isEqualByComparingTo("0.0000");
  }

  @Test
  void 所有得分都是四位小数() {
    assertThat(jaccard(Set.of("a", "b", "c"), Set.of("a", "x"))).scale().isEqualTo(SCORE_SCALE);
    assertThat(titleSimilarity("红色无线耳机", "无线耳机 红色")).scale().isEqualTo(SCORE_SCALE);
    assertThat(editSimilarity("abc", "abd")).scale().isEqualTo(SCORE_SCALE);
    assertThat(variantSimilarity("A=1", "A=1|B=2")).scale().isEqualTo(SCORE_SCALE);
    assertThat(scale(new BigDecimal("0.123456"))).isEqualByComparingTo("0.1235");
  }

  @Test
  void 编辑距离是标准两行DP() {
    assertThat(levenshtein("", "")).isEqualTo(0);
    assertThat(levenshtein("abc", "abc")).isEqualTo(0);
    assertThat(levenshtein("", "abc")).isEqualTo(3);
    assertThat(levenshtein("abc", "abd")).isEqualTo(1);
    assertThat(levenshtein("kitten", "sitting")).isEqualTo(3);
    assertThat(editSimilarity("abc", "abc")).isEqualByComparingTo("1.0000");
    assertThat(editSimilarity("abc", "abd")).isEqualByComparingTo("0.6667");
    assertThat(editSimilarity("", "")).isEqualByComparingTo("1.0000");
  }

  @Test
  void 标题相似度取Jaccard与编辑距离的较大者() {
    // 词序调换：二元组仍重合 4/6（无线/线耳/耳机/红色），编辑距离更差(0.3333)，
    // 取大者 → 0.6667。这就是二元组切词换来的东西：词序无关的重合能被算出来，
    // 而按空格切词会得到 0（两个整句 token 完全不同）。
    assertThat(titleSimilarity("红色无线耳机", "无线耳机 红色")).isEqualByComparingTo("0.6667");
    // 只差一个字：二元组几乎不重合，Jaccard 很低，靠编辑距离救回来。
    var oneCharOff = titleSimilarity("无线蓝牙耳机5代", "无线蓝牙耳机5代2");
    assertThat(oneCharOff).isGreaterThan(new BigDecimal("0.80"));
    // 完全不同的东西必须低：不然全是候选。
    assertThat(titleSimilarity("红色无线耳机", "婴儿纸尿裤L码")).isLessThan(new BigDecimal("0.30"));
    assertThat(titleSimilarity("", "abc")).isEqualByComparingTo("0.0000");
    assertThat(titleSimilarity("红色无线耳机", "红色无线耳机")).isEqualByComparingTo("1.0000");
  }

  @Test
  void 图片与规格组合相似度() {
    assertThat(imageSimilarity(Set.of("h1", "h2"), Set.of("h2", "h3"))).isEqualByComparingTo("0.3333");
    assertThat(imageSimilarity(Set.of("h1"), Set.of("h1"))).isEqualByComparingTo("1.0000");
    assertThat(variantSimilarity("COLOR=红|SIZE=大", "COLOR=红|SIZE=大")).isEqualByComparingTo("1.0000");
    // 顺序不同但内容相同：签名按轴编码排序过，所以两者本来就该相同。
    assertThat(variantSimilarity("COLOR=红|SIZE=大", "SIZE=大|COLOR=红")).isGreaterThan(new BigDecimal("0.90"));
    // 只有一个轴值相同 → Jaccard = 1/3。
    assertThat(variantSimilarity("COLOR=红", "COLOR=红|SIZE=大")).isEqualByComparingTo("0.5000");
    assertThat(variantSimilarity("", "COLOR=红")).isEqualByComparingTo("0.0000");
    assertThat(variantSimilarity(null, null)).isEqualByComparingTo("0.0000");
  }

  // ================================================================ 四条判定线

  @Test
  void 条码线_撞了就是满分且带可核对证据() {
    var signal =
        barcodeSignal(Set.of("6901234567892"), Set.of("6901234567892"), DEFAULT_MIN_SIMILARITY);
    assertThat(signal).isNotNull();
    assertThat(signal.dimension()).isEqualTo(Dimension.BARCODE);
    assertThat(signal.score()).isEqualByComparingTo("1.0000");
    // 证据里必须写明撞的是哪个条码值，否则页面只给一个孤零零的 1.0000。
    assertThat(signal.evidence()).contains("6901234567892");
    // 归一化后相等也算（大小写/空白差异来自人工录入）。
    assertThat(barcodeSignal(Set.of(" abc-1 "), Set.of("ABC-1"), DEFAULT_MIN_SIMILARITY)).isNotNull();
  }

  @Test
  void 条码线_不撞或撞在空值上不命中() {
    assertThat(barcodeSignal(Set.of("a"), Set.of("b"), DEFAULT_MIN_SIMILARITY)).isNull();
    // 两个 SKU 都没填条码：空值不是「同一个条码」。
    assertThat(barcodeSignal(Set.of(), Set.of(), DEFAULT_MIN_SIMILARITY)).isNull();
    assertThat(barcodeSignal(Set.of(""), Set.of(), DEFAULT_MIN_SIMILARITY)).isNull();
  }

  @Test
  void 条码线_即使阈值调到满分也命中() {
    // 阈值 1.0 时 BARCODE 仍得 1.0000：条码撞了是事实，不能因为阈值高就消失。
    assertThat(barcodeSignal(Set.of("x"), Set.of("x"), ONE)).isNotNull();
  }

  @Test
  void 规格组合线_相同得满分_任一侧无轴值不命中() {
    var same =
        variantSignal("COLOR=红|SIZE=大", "COLOR=红|SIZE=大", DEFAULT_MIN_SIMILARITY);
    assertThat(same).isNotNull();
    assertThat(same.dimension()).isEqualTo(Dimension.VARIANT_COMBINATION);
    assertThat(same.score()).isEqualByComparingTo("1.0000");
    // 一个轴值都解析不出来的 SKU（导入占位签名）不参与这一维：空签名与任何签名都不相等。
    assertThat(variantSignal("", "COLOR=红", DEFAULT_MIN_SIMILARITY)).isNull();
    assertThat(variantSignal("COLOR=红", "", DEFAULT_MIN_SIMILARITY)).isNull();
    // 阈值高于部分重叠得分时不命中。
    assertThat(variantSignal("COLOR=红", "COLOR=红|SIZE=大", ONE)).isNull();
  }

  @Test
  void 跨SPU线_三个坐标全同才算且品牌空不算相等() {
    var hit =
        crossSpuSignal(
            "spu-a", "spu-b", "无线耳机", "无线耳机", "Sony", "sony", "数码", "数码",
            DEFAULT_MIN_SIMILARITY);
    assertThat(hit).isNotNull();
    assertThat(hit.dimension()).isEqualTo(Dimension.CROSS_SPU);
    assertThat(hit.score()).isEqualByComparingTo("1.0000");

    // 同一 SPU 内不判（那是规格组合那一维的事）
    assertThat(
            crossSpuSignal("spu-a", "spu-a", "无线耳机", "无线耳机", "Sony", "Sony", "数码", "数码",
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    // 品牌为空不算「品牌相同」
    assertThat(
            crossSpuSignal("spu-a", "spu-b", "无线耳机", "无线耳机", "", "Sony", "数码", "数码",
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    assertThat(
            crossSpuSignal("spu-a", "spu-b", "无线耳机", "无线耳机", "Sony", "", "数码", "数码",
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    // 类目不同不算
    assertThat(
            crossSpuSignal("spu-a", "spu-b", "无线耳机", "无线耳机", "Sony", "Sony", "数码", "服饰",
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    // 标题只是近似不算（近似那条线归 SIMILAR_PRODUCT，要连图片一起看）
    assertThat(
            crossSpuSignal("spu-a", "spu-b", "无线耳机", "无线耳机 Pro", "Sony", "Sony", "数码", "数码",
                DEFAULT_MIN_SIMILARITY))
        .isNull();
  }

  @Test
  void 近似同品线_标题与图片两条证据缺一不可() {
    var hit =
        similarProductSignal(
            "红色无线耳机", "红色无线耳机 Pro", Set.of("h1", "h2"), Set.of("h2", "h3"),
            DEFAULT_MIN_SIMILARITY);
    // 标题 0.8333（二元组 5/6，编辑距离 1-3/9=0.6667，取大者）× 0.7
    // + 图片 0.3333（{h2} 1/3）× 0.3 = 0.6833 —— 低于默认阈值 0.8，
    // 所以这一对<b>不成候选</b>：证据不够强就不该占用人工确认的时间。
    assertThat(hit).isNull();

    // 标题完全相同 + 一张图重合 → 0.7×1.0000 + 0.3×(1/2) = 0.8500 ≥ 0.8，命中。
    var strong =
        similarProductSignal(
            "红色无线耳机", "红色无线耳机", Set.of("h1", "h2"), Set.of("h2"), DEFAULT_MIN_SIMILARITY);
    assertThat(strong).isNotNull();
    assertThat(strong.dimension()).isEqualTo(Dimension.SIMILAR_PRODUCT);
    assertThat(strong.score()).isEqualByComparingTo("0.8500");
    // 没有共享图片 → 一律不命中（哪怕标题完全相同：白底通用主图满地都是）
    assertThat(
            similarProductSignal("红色无线耳机", "红色无线耳机", Set.of("h1"), Set.of("h9"),
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    // 标题差太远 → 不命中（图片不能单独当同品证据）
    assertThat(
            similarProductSignal("婴儿纸尿裤L码", "宠物狗粮5kg", Set.of("h1"), Set.of("h1"),
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    // 权重是可验证的：0.7 与 0.3 写死在两个常量里，这里按同一公式反算一遍。
    assertThat(strong.evidence()).contains("1 张相同素材");
  }

  @Test
  void 阈值调低会真的把弱证据的候选放进来() {
    var weak = new LinkedHashSet<>(Set.of("h1", "h2", "h3"));
    var other = new LinkedHashSet<>(Set.of("h2", "h9", "h8"));
    // 标题 0.8333、图片 1/5（两侧各 3 张，只有 1 张重合）→ 0.7×0.8333 + 0.3×0.2000 = 0.6433：
    // 0.8 阈值下不命中，0.6 阈值下命中。
    assertThat(
            similarProductSignal("红色无线耳机", "红色无线耳机 Pro", weak, other,
                DEFAULT_MIN_SIMILARITY))
        .isNull();
    var relaxed =
        similarProductSignal("红色无线耳机", "红色无线耳机 Pro", weak, other, new BigDecimal("0.6000"));
    assertThat(relaxed).isNotNull();
    assertThat(relaxed.score()).isEqualByComparingTo("0.6433");
  }

  // ================================================================ 候选装配

  @Test
  void 候选取最大得分_重叠维度数与信号条数一致_维度按固定顺序() {
    var barcode = barcodeSignal(Set.of("x"), Set.of("x"), DEFAULT_MIN_SIMILARITY);
    var similar =
        similarProductSignal("红色无线耳机", "红色无线耳机", Set.of("h1"), Set.of("h1"),
            DEFAULT_MIN_SIMILARITY);
    // 传进去的顺序是「近似 → 条码」，装配后必须按词表顺序（条码在前）返回。
    var candidate = candidate(List.of(similar, barcode));

    assertThat(candidate.overlapDimensionCount()).isEqualTo(2);
    assertThat(candidate.signals()).extracting(Signal::dimension)
        .containsExactly(Dimension.BARCODE, Dimension.SIMILAR_PRODUCT);
    // 总体相似度取最大值（1.0000），不是加权平均 —— 否则条码撞了会被稀释成 0.9x。
    assertThat(candidate.similarity()).isEqualByComparingTo("1.0000");
    assertThat(candidate.reason()).contains("同条码多 SKU").contains("疑似同品").contains("命中 2 条线");
  }

  @Test
  void 维度词表与V36的CHECK一致且标签齐全() {
    assertThat(Dimension.values())
        .extracting(Enum::name)
        .containsExactly("BARCODE", "VARIANT_COMBINATION", "CROSS_SPU", "SIMILAR_PRODUCT");
    for (Dimension d : Dimension.values()) assertThat(d.label()).isNotBlank();
    assertThat(dimensionByName("barcode")).isEqualTo(Dimension.BARCODE);
    assertThat(dimensionByName(" CROSS_SPU ")).isEqualTo(Dimension.CROSS_SPU);
    assertThatThrownBy(() -> dimensionByName("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 阈值默认值与非法值不静默夹取() {
    assertThat(minSimilarity(null)).isEqualByComparingTo("0.8000");
    assertThat(minSimilarity(new BigDecimal("0.75"))).isEqualByComparingTo("0.7500");
    // 越界由服务层显式拒绝（400），这里不做夹取 —— 夹取会让「阈值 2」悄悄变成「阈值 1」。
    assertThat(minSimilarity(new BigDecimal("2.0"))).isEqualByComparingTo("2.0000");
  }

  @Test
  void 决定中文名() {
    assertThat(decisionLabel(null)).isEqualTo("待确认");
    assertThat(decisionLabel("MERGE")).isEqualTo("确认合并");
    assertThat(decisionLabel("keep")).isEqualTo("保留两条");
  }

  @Test
  void 复合键分隔符不可能出现在归一化坐标里() {
    // 分组键用它拼「标题|品牌|类目」，所以它必须与归一化的输出字母表不相交。
    assertThat(KEY_SEPARATOR).isEqualTo("|");
    for (String sample : List.of("红色无线耳机", "Sony", "数码/家电", "a-b_c")) {
      String normalized = normalizeTitle(sample);
      assertThat(normalized).doesNotContain(KEY_SEPARATOR);
      // 复合键因此不会出现「拼在一起撞车」：品牌 A|B 与类目 C 不会和品牌 A、类目 B|C 同键。
      assertThat(normalizeTitle("a|b") + KEY_SEPARATOR + normalizeTitle("c"))
          .isNotEqualTo(normalizeTitle("a") + KEY_SEPARATOR + normalizeTitle("b|c"));
    }
  }
}