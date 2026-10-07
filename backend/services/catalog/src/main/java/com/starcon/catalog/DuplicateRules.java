package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.9.1.1 重复 SKU 识别的<b>纯计算</b>：归一化、相似度与逐维度判定。
 *
 * <p><b>本类不碰数据库</b>，所以每一条判定口径都能被单测逐个钉死：
 * 服务层只负责把事实读上来（本类需要的全部输入：标题、条码、规格组合、图片内容哈希），
 * 再把本类的判定结果落库。两边都不重新实现相似度。
 *
 * <h2>相似度为什么不是「拍脑袋的权重」</h2>
 *
 * <p>四个维度各用一种**可核对**的算法，没有一个是常数或随机数：
 *
 * <ul>
 *   <li><b>BARCODE</b> —— 归一化后<b>精确相等</b>。条码是外部唯一标识，撞了就是 1.0000，
 *       不打折扣：打了折扣就会让「条码已经撞了」看起来比「只是标题像」轻，
 *       而事实恰好相反（V36 文件头有同一段理由）。
 *   <li><b>VARIANT_COMBINATION</b> —— 按轴值现算的组合签名精确相等（1.0000）；
 *       只部分重叠时取规格轴 token 集的 <b>Jaccard</b>。
 *   <li><b>CROSS_SPU</b> —— 归一化标题 + 品牌 + 类目<b>三者全同</b>才算（1.0000）。
 *       刻意<b>不</b>收进「标题近似」：结构完全一致才是「同一个商品被建了两遍」，
 *       标题近似那部分归 SIMILAR_PRODUCT，两条线的含义不重叠。
 *   <li><b>SIMILAR_PRODUCT</b> —— 标题近似（Jaccard 与编辑距离取大者）<b>且</b>至少重合一张图片，
 *       两条同时成立才算「疑似同品」，得分按标题 0.7 / 图片 0.3 加权。
 * </ul>
 *
 * <h2>中文标题为什么要切二元组</h2>
 *
 * <p>「红色无线耳机」按空格切词只得到 1 个 token，与「无线耳机 红色」的 Jaccard 会算成 0/1 = 0 ——
 * 而这两句是同一个商品。中文没有空格分词，所以本类对<b>连续汉字串</b>切<b>二元组</b>
 * （红色/色无/无线/线耳/耳机），让词序无关的重合能被算出来；拉丁字母与数字仍按整词切。
 * 这是近似重复检测的标准做法，也让「红色无线耳机」与「红色无线耳机 Pro」的 Jaccard = 5/6 ≈ 0.8333，
 * 而不是一个靠调参碰出来的数字。
 *
 * <p><b>归一化做四件事</b>（顺序固定）：NFKC（全角转半角、兼容字符折叠）→ 转小写 →
 * 去掉所有空白 → 只保留汉字/字母/数字（标点、连字符、括号全丢）。
 * 「Sony WH-1000XM5（黑色）/ Sony-WH1000XM5 黑色」因此落到同一个归一化串上，
 * 而这正是「同商品不同 SPU 重复建档」最常见的形态。
 */
final class DuplicateRules {
  private DuplicateRules() {}

  /** 相似度一律 4 位小数（{@code NUMERIC(5,4)}），与 V36 的列精度同一口径。 */
  static final int SCORE_SCALE = 4;

  /** 相似度上限。 */
  static final BigDecimal ONE = BigDecimal.ONE.setScale(SCORE_SCALE, RoundingMode.UNNECESSARY);

  /** 默认判定阈值：低于它不成候选。 */
  static final BigDecimal DEFAULT_MIN_SIMILARITY = new BigDecimal("0.8000");

  /**
   * SIMILAR_PRODUCT 的标题门槛（<b>低于</b>用户选的 minSimilarity）。
   *
   * <p>它是「标题至少得像到值得再看看图片」的下限，与最终阈值分开：
   * 一对商品标题 Jaccard 0.6、图片重合两张，混进 0.8 以上的候选清单只会污染人工确认，
   * 而 0.6 的标题相似度在电商标题里通常是「同类不同款」（同系列不同容量）而不是同品。
   */
  static final BigDecimal TITLE_GATE = new BigDecimal("0.7500");

  /** SIMILAR_PRODUCT 的两个证据的权重（相加为 1，缺一不可但可不等权）。 */
  static final BigDecimal TITLE_WEIGHT = new BigDecimal("0.7");

  static final BigDecimal IMAGE_WEIGHT = new BigDecimal("0.3");

  /**
   * CROSS_SPU 分组键的分隔符。
   *
   * <p>三个坐标都过了 {@link #normalizeTitle}，归一化后只剩汉字/字母/数字，
   * 所以这个符号<b>不可能</b>出现在任何一个坐标里 —— 用它拼复合键不会有「拼在一起撞车」
   * （品牌「A|B」与类目「C」不会和品牌「A」类目「B|C」拼成同一个键）。
   */
  static final String KEY_SEPARATOR = "|";

  // ================================================================ 四个检测维度

  /** 检测维度。与蓝图的四类重复一一对应，词表与 V36 的 CHECK 约束严格相同。 */
  enum Dimension {
    BARCODE("同条码多 SKU"),
    VARIANT_COMBINATION("同 SPU 重复规格组合"),
    CROSS_SPU("同商品不同 SPU 重复建档"),
    SIMILAR_PRODUCT("近似标题+相似图片疑似同品");

    final String label;

    Dimension(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  /** 由词表名取回维度（候选页按 {@code ?dimension=} 筛选时用）。未知词表显式失败，不兜底。 */
  static Dimension dimensionByName(String name) {
    return Dimension.valueOf(name.trim().toUpperCase(Locale.ROOT));
  }

  // ================================================================ 归一化

  /**
   * 条码归一化：去首尾空白 + 转大写。
   *
   * <p><b>刻意不剥连字符与空格</b>：GTIN-14 的标准打印形态就是带连字符的，
   * 剥掉之后 {@code 012345678905} 与 {@code 012-345678905} 会被判成同一个条码，
   * 而它们在真实条码体系里可能是两条不同的记录。归一化只做确定无歧义的两件事。
   */
  static String normalizeBarcode(String raw) {
    if (raw == null) return "";
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  /** 标题归一化：NFKC → 小写 → 去空白 → 只留汉字/字母/数字。 */
  static String normalizeTitle(String raw) {
    if (raw == null) return "";
    String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(folded.length());
    for (int i = 0; i < folded.length(); ) {
      int cp = folded.codePointAt(i);
      i += Character.charCount(cp);
      if (isHan(cp) || Character.isLetterOrDigit(cp)) out.appendCodePoint(cp);
    }
    return out.toString();
  }

  /** 标题 token 集：连续汉字切二元组，拉丁字母/数字串按整词切。 */
  static Set<String> titleTokens(String raw) {
    String normalized = normalizeTitle(raw);
    Set<String> tokens = new LinkedHashSet<>();
    if (normalized.isEmpty()) return tokens;
    int i = 0;
    while (i < normalized.length()) {
      int start = i;
      boolean han = isHan(normalized.codePointAt(i));
      while (i < normalized.length() && isHan(normalized.codePointAt(i)) == han) i++;
      String run = normalized.substring(start, i);
      if (!han) {
        tokens.add(run);
      } else if (run.length() == 1) {
        tokens.add(run);
      } else {
        // 汉字二元组：让「红色无线耳机」与「无线耳机红色」的词序无关重合能被算出来。
        for (int k = 0; k + 1 < run.length(); k++) tokens.add(run.substring(k, k + 2));
      }
    }
    return tokens;
  }

  /** 汉字（含扩展区与兼容区），用于分词而不是用于判定「是不是中文」。 */
  private static boolean isHan(int cp) {
    return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
  }

  /**
   * 规格组合签名归一化：{@link CombinationRules} 已按轴编码排序，这里只做空白收敛。
   *
   * <p>直接复用 {@link CombinationRules#signature} 现算，不自己再实现一套轴值拼接规则 ——
   * 两套拼法一旦不同步，本模块判出的「重复组合」与 3.1.3.1 页面显示的组合就对不上，
   * 而运营是在两个页面之间来回核对的。
   */
  static String normalizeVariantSignature(String signature) {
    return signature == null ? "" : signature.trim();
  }

  // ================================================================ 相似度

  /** Jaccard 相似度 = 交集 / 并集。两个都为空时取 1.0（两个空标题确实「一样空」）。 */
  static BigDecimal jaccard(Set<String> left, Set<String> right) {
    if (left.isEmpty() && right.isEmpty()) return ONE;
    Set<String> intersection = new LinkedHashSet<>(left);
    intersection.retainAll(right);
    if (intersection.isEmpty()) return BigDecimal.ZERO.setScale(SCORE_SCALE);
    int union = left.size() + right.size() - intersection.size();
    // 必须给 scale 与舍入：1/3 这类除不尽的比值在无 scale 的精确除法下会抛
    // ArithmeticException（非终止小数展开），而相似度恰恰最常出现 1/3、2/3。
    return new BigDecimal(intersection.size())
        .divide(new BigDecimal(union), SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** Levenshtein 编辑距离。用于「标题整体几乎一样但 token 集不同」的近重复。 */
  static int levenshtein(String left, String right) {
    if (left.equals(right)) return 0;
    if (left.isEmpty()) return right.length();
    if (right.isEmpty()) return left.length();
    // 只保留上一行：编辑距离的标准两行 DP，空间 O(min(n,m))。
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) previous[j] = j;
    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()];
  }

  /** 归一化编辑距离相似度 = 1 − 距离 / 最长串长。两串都空时取 1.0。 */
  static BigDecimal editSimilarity(String leftRaw, String rightRaw) {
    String left = normalizeTitle(leftRaw);
    String right = normalizeTitle(rightRaw);
    int longest = Math.max(left.length(), right.length());
    if (longest == 0) return ONE;
    return scale(BigDecimal.ONE.subtract(new BigDecimal(levenshtein(left, right))
        .divide(new BigDecimal(longest), 10, RoundingMode.HALF_UP)));
  }

  /**
   * 标题相似度：Jaccard 与编辑距离<b>取大者</b>。
   *
   * <p>两个都要的理由：Jaccard 抓「词换了但说的是同一件事」（降噪、词序调换），
   * 编辑距离抓「只差一两个字」（漏字、多字、型号抄错）。
   * 只用 Jaccard 的话「无线蓝牙耳机5代」对「无线蓝牙耳机5代2」会因二元组几乎不重合而得 0；
   * 只用编辑距离的话「红米 Note 12」对「红米 Note 13 Pro」又会被判得太像。
   */
  static BigDecimal titleSimilarity(String leftTitle, String rightTitle) {
    String left = normalizeTitle(leftTitle);
    String right = normalizeTitle(rightTitle);
    if (left.isEmpty() || right.isEmpty()) return BigDecimal.ZERO.setScale(SCORE_SCALE);
    if (left.equals(right)) return ONE;
    BigDecimal byTokens = jaccard(titleTokens(leftTitle), titleTokens(rightTitle));
    BigDecimal byEdit = editSimilarity(left, right);
    return byTokens.compareTo(byEdit) >= 0 ? byTokens : byEdit;
  }

  /** 图片相似度：两个 SKU 的素材内容哈希集合的 Jaccard。 */
  static BigDecimal imageSimilarity(Set<String> leftHashes, Set<String> rightHashes) {
    return jaccard(leftHashes, rightHashes);
  }

  /** 规格组合相似度：签名相同取 1.0，否则取轴值 token 集的 Jaccard。 */
  static BigDecimal variantSimilarity(String leftSignature, String rightSignature) {
    String left = normalizeVariantSignature(leftSignature);
    String right = normalizeVariantSignature(rightSignature);
    if (left.isEmpty() || right.isEmpty()) return BigDecimal.ZERO.setScale(SCORE_SCALE);
    if (left.equals(right)) return ONE;
    return jaccard(new LinkedHashSet<>(List.of(left.split("\\|"))), new LinkedHashSet<>(List.of(right.split("\\|"))));
  }

  // ================================================================ 逐维度判定

  /** 一条命中：维度 + 得分 + 可核对的证据。 */
  record Signal(Dimension dimension, BigDecimal score, String evidence) {}

  /**
   * BARCODE：同条码多 SKU。
   *
   * <p><b>同源与跨源都算</b>：两个 SKU 的 {@code sku.barcode} 相同是一个信号，
   * A 的 {@code sku.barcode} 与 B 的 {@code sku_identifier}（V7 的物料标识表）里同一个值
   * 是<b>另一个</b>信号 —— 后者说明「同一条码被录进了两个地方」，而这正是批量导入最常见的错。
   * 两者都得 1.0000：条码撞了就是撞了，不因为来源不同而打折。
   *
   * @param threshold 低于它不成候选
   * @return 命中返回一条信号；未命中返回 {@code null}
   */
  static Signal barcodeSignal(Set<String> leftBarcodes, Set<String> rightBarcodes, BigDecimal threshold) {
    Set<String> left = normalizedSet(leftBarcodes);
    Set<String> right = normalizedSet(rightBarcodes);
    Set<String> shared = new LinkedHashSet<>(left);
    shared.retainAll(right);
    if (shared.isEmpty() || shared.contains("")) return null;
    if (ONE.compareTo(threshold) < 0) return null;
    String values = String.join("、", shared);
    return new Signal(
        Dimension.BARCODE,
        ONE,
        "两个 SKU 命中同一组条码「"
            + values
            + "」（归一化后精确相等）；条码是外部唯一标识，撞了必须处理");
  }

  /**
   * VARIANT_COMBINATION：同 SPU 下重复规格组合。
   *
   * <p>入参是<b>按轴值现算</b>的组合签名（服务层复用 {@link CombinationRules#signature}），
   * 而不是 {@code sku.variant_signature} 列 —— 理由见 V36 文件头：导入向导写的是
   * {@code IMPORT:<编码>} 占位签名，那条列上的唯一约束拦不住「真实规格取值相同」的重复。
   *
   * <p>任一侧签名为空（一个轴值都解析不出来的 SKU）时<b>不命中</b>：
   * 空签名与任何签名都「不相等」，拿它去比会得到一堆没有依据的候选。
   */
  static Signal variantSignal(String leftSignature, String rightSignature, BigDecimal threshold) {
    BigDecimal score = variantSimilarity(leftSignature, rightSignature);
    if (score.signum() <= 0 || score.compareTo(threshold) < 0) return null;
    String left = normalizeVariantSignature(leftSignature);
    String right = normalizeVariantSignature(rightSignature);
    String evidence =
        score.compareTo(ONE) == 0
            ? "同一 SPU 下两个 SKU 的规格组合完全相同：" + left
            : "同一 SPU 下两个 SKU 的规格组合部分重叠（重合度 " + score.toPlainString() + "）："
                + left
                + " ↔ "
                + right;
    return new Signal(Dimension.VARIANT_COMBINATION, score, evidence);
  }

  /**
   * CROSS_SPU：同商品不同 SPU 重复建档。
   *
   * <p><b>要求归一化标题、品牌、类目三者全同</b>，且两侧属于不同 SPU。
   * 三者全同才算「同一个商品被建了两遍」：标题是商品身份，品牌与类目是它的坐标，
   * 三个坐标都对上还不同 SPU，剩下只有重复建档一种解释。
   *
   * <p>品牌或类目为空时<b>不命中</b>（不拿空值当相等）：
   * 两个都没填品牌的商品标题相同，只能说标题相同，说不清是不是同一个商品 ——
   * 那正是 SIMILAR_PRODUCT 要连图片一起看的场景。
   */
  static Signal crossSpuSignal(
      String leftSpuId,
      String rightSpuId,
      String leftSpuName,
      String rightSpuName,
      String leftBrand,
      String rightBrand,
      String leftCategory,
      String rightCategory,
      BigDecimal threshold) {
    if (leftSpuId == null || rightSpuId == null || leftSpuId.equals(rightSpuId)) return null;
    // 三个坐标一律用 normalizeTitle（品牌/类目与标题同一套归一化），
    // 与服务层 CROSS_SPU 的分组键**逐字同源**：分组键若与判定用的归一化不是同一套，
    // 就会出现「分组把本该命中的对分开了、判定却又认可它」的漏检，
    // 而漏检是这里最不能犯的错（页面上会显示「已治理干净」）。
    String leftBrandKey = normalizeTitle(leftBrand);
    String rightBrandKey = normalizeTitle(rightBrand);
    String leftCategoryKey = normalizeTitle(leftCategory);
    String rightCategoryKey = normalizeTitle(rightCategory);
    // 空值不算相等：两个都没填品牌不是「品牌相同」。
    if (leftBrandKey.isEmpty()
        || rightBrandKey.isEmpty()
        || leftCategoryKey.isEmpty()
        || rightCategoryKey.isEmpty()) return null;
    if (!leftBrandKey.equals(rightBrandKey) || !leftCategoryKey.equals(rightCategoryKey)) return null;
    if (!normalizeTitle(leftSpuName).equals(normalizeTitle(rightSpuName))) return null;
    if (ONE.compareTo(threshold) < 0) return null;
    return new Signal(
        Dimension.CROSS_SPU,
        ONE,
        "两个不同 SPU 的名称（归一化后完全相同）、品牌「"
            + leftBrandKey
            + "」与类目「"
            + leftCategoryKey
            + "」三者全同：同一个商品被建了两遍档案");
  }

  /**
   * SIMILAR_PRODUCT：近似标题 + 相似图片，疑似同品。
   *
   * <p><b>两条证据必须同时成立</b>，这是这一维存在的理由：
   * 只凭标题像就把两条判成重复，跨境电商的标题里「同系列不同容量」「换壳版」满地都是；
   * 只凭图片像则会把白底通用主图判成同品。两条都成立才是可人工确认的<b>疑似</b>。
   *
   * @param sharedImages 两侧重合的内容哈希数；<b>必须 ≥ 1</b>（本类不拿「图片相似」当可有可无的加分项）
   */
  static Signal similarProductSignal(
      String leftTitle, String rightTitle, Set<String> leftHashes, Set<String> rightHashes,
      BigDecimal threshold) {
    BigDecimal titleScore = titleSimilarity(leftTitle, rightTitle);
    Set<String> shared = new LinkedHashSet<>(leftHashes);
    shared.retainAll(rightHashes);
    shared.removeIf(java.util.Objects::isNull);
    if (shared.isEmpty()) return null;
    if (titleScore.compareTo(TITLE_GATE) < 0) return null;
    BigDecimal imageScore = imageSimilarity(leftHashes, rightHashes);
    BigDecimal score =
        scale(titleScore.multiply(TITLE_WEIGHT).add(imageScore.multiply(IMAGE_WEIGHT)));
    if (score.compareTo(threshold) < 0) return null;
    return new Signal(
        Dimension.SIMILAR_PRODUCT,
        score,
        "标题近似（相似度 "
            + titleScore.toPlainString()
            + "）且图片重合（"
            + shared.size()
            + " 张相同素材，图片相似度 "
            + imageScore.toPlainString()
            + "）");
  }

  // ================================================================ 候选装配

  /** 一个候选的全部命中（按维度固定顺序）。 */
  record Candidate(List<Signal> signals) {
    /** 总体相似度 = 各维度得分的最大值（理由见 V36 文件头）。 */
    BigDecimal similarity() {
      return signals.stream()
          .map(Signal::score)
          .max(BigDecimal::compareTo)
          .orElse(BigDecimal.ZERO.setScale(SCORE_SCALE));
    }

    /** 重叠维度数 = 命中了几条线。 */
    int overlapDimensionCount() {
      return signals.size();
    }

    /** 一句话结论（候选清单上那一栏）。 */
    String reason() {
      String labels = String.join(" + ", signals.stream().map(s -> s.dimension().label()).toList());
      return labels + "（命中 " + signals.size() + " 条线，最高相似度 " + similarity().toPlainString() + "）";
    }
  }

  /** 候选按维度固定顺序返回：条码 → 规格组合 → 跨 SPU → 近似同品，页面列序与本词表一致。 */
  static Candidate candidate(List<Signal> signals) {
    List<Signal> ordered = new ArrayList<>();
    for (Dimension d : Dimension.values())
      signals.stream().filter(s -> s.dimension() == d).findFirst().ifPresent(ordered::add);
    return new Candidate(List.copyOf(ordered));
  }

  // ================================================================ 小工具

  /** 归一化一个条码集合（去空白、去空值；传进来已归一化的也无妨，normalizeBarcode 幂等）。 */
  private static Set<String> normalizedSet(Set<String> values) {
    Set<String> out = new LinkedHashSet<>();
    if (values == null) return out;
    for (String value : values) {
      String normalized = normalizeBarcode(value);
      if (!normalized.isEmpty()) out.add(normalized);
    }
    return out;
  }

  /** 四位小数（{@code NUMERIC(5,4)}）。所有得分出口都必须过它，避免各算各的精度。 */
  static BigDecimal scale(BigDecimal value) {
    return value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** 决定的中文名。 */
  static String decisionLabel(String decision) {
    if (decision == null) return "待确认";
    return switch (decision.trim().toUpperCase(Locale.ROOT)) {
      case "MERGE" -> "确认合并";
      case "KEEP" -> "保留两条";
      default -> decision;
    };
  }

  /** 扫描阈值归一化：空 → 默认；越界由服务层拒（这里不做静默夹取）。 */
  static BigDecimal minSimilarity(BigDecimal raw) {
    return scale(raw == null ? DEFAULT_MIN_SIMILARITY : raw);
  }
}