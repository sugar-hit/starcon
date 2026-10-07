package com.starcon.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.4.2.1 标题库的纯规则层：词表、容量上限、模板与词根校验、<b>标题相似度</b>。
 *
 * <p>与 {@link MediaRules} / {@link ImageProcessRules} 同一形状：无状态静态方法。
 * 服务层拒绝时给出的话、前端表单校验、集成测试断言的边界都引同一份实现。
 *
 * <p><b>标题渲染与生成不在这里。</b>那是 {@link TitleGenerator} 的职责（要模板解析与候选轮转），
 * 这里只管「参数合不合法」与「两条标题有多像」——两类逻辑混在一起会让「相似度」这个
 * 蓝图标着要显式控制的纯函数被候选循环污染。
 */
public final class TitleRules {
  private TitleRules() {}

  // ============================================================ 词表（与 V20 的 CHECK 逐字一致）

  /** 变量绑定源的事实部分：从 SPU / SKU 取。 */
  public static final List<String> FACT_SOURCES =
      List.of("SPU_BRAND", "SPU_CATEGORY", "SPU_NAME", "SKU_NAME", "SKU_CODE", "SKU_SPEC");

  /** 变量绑定源的词根部分：从 {@code title_keyword} 取。 */
  public static final List<String> KEYWORD_SOURCES =
      List.of("KEYWORD_CORE", "KEYWORD_SELLING", "KEYWORD_MODIFIER");

  public static final List<String> KEYWORD_TYPES = List.of("CORE", "SELLING", "MODIFIER");
  public static final List<String> GENERATION_STATUSES = List.of("SUCCEEDED", "PARTIAL", "FAILED");
  public static final List<String> RECORD_STATUSES = List.of("CANDIDATE", "SELECTED", "REJECTED");
  public static final List<String> SKU_OUTCOMES = List.of("GENERATED", "REJECTED");

  private static final java.util.Map<String, String> KEYWORD_TYPE_LABELS =
      java.util.Map.of("CORE", "核心词", "SELLING", "卖点", "MODIFIER", "修饰词");

  private static final java.util.Map<String, String> SOURCE_LABELS =
      java.util.Map.of(
          "SPU_BRAND", "SPU 品牌",
          "SPU_CATEGORY", "SPU 分类",
          "SPU_NAME", "SPU 名称",
          "SKU_NAME", "SKU 名称",
          "SKU_CODE", "SKU 编码",
          "SKU_SPEC", "SKU 规格",
          "KEYWORD_CORE", "核心词池",
          "KEYWORD_SELLING", "卖点词池",
          "KEYWORD_MODIFIER", "修饰词池");

  private static final java.util.Map<String, String> GENERATION_STATUS_LABELS =
      java.util.Map.of("SUCCEEDED", "已生成", "PARTIAL", "部分 SKU 未产出", "FAILED", "未产出");

  private static final java.util.Map<String, String> RECORD_STATUS_LABELS =
      java.util.Map.of("CANDIDATE", "待选用", "SELECTED", "已选用", "REJECTED", "已淘汰");

  private static final java.util.Map<String, String> OUTCOME_LABELS =
      java.util.Map.of("GENERATED", "已产出候选", "REJECTED", "未产出候选");

  public static String keywordTypeLabel(String value) {
    return KEYWORD_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String sourceLabel(String value) {
    return SOURCE_LABELS.getOrDefault(value, value);
  }

  public static String generationStatusLabel(String value) {
    return GENERATION_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String recordStatusLabel(String value) {
    return RECORD_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String outcomeLabel(String value) {
    return OUTCOME_LABELS.getOrDefault(value, value);
  }

  /** 词根绑定源 → 词库类型（渲染时按这个类型取词池）。事实源返回 null。 */
  public static String keywordTypeOfSource(String source) {
    String value = MediaRules.normalizeUpper(source);
    if ("KEYWORD_CORE".equals(value)) return "CORE";
    if ("KEYWORD_SELLING".equals(value)) return "SELLING";
    if ("KEYWORD_MODIFIER".equals(value)) return "MODIFIER";
    return null;
  }

  // ============================================================ 容量上限

  /**
   * 单次批量最多 200 个 SKU。
   *
   * <p>不是算力上限（重复度比较范围限定在单个 SKU 内，几百个 SKU 只是几十毫秒），
   * 而是**可用性**上限：一次生成 2000 个 SKU × 3 平台 × 5 候选 = 3 万条标题，
   * 候选集页面根本没法用，运营也没法逐条选用。要大批量应该分批并分平台发。
   */
  public static final int MAX_SKUS_PER_GENERATION = 200;

  /** 单次批量最多 5 个平台。同一个标题跨 6 个平台发已经不像「一次批量生成」了。 */
  public static final int MAX_PLATFORMS_PER_GENERATION = 5;

  /**
   * 每个 SKU 每个平台最多 8 条候选。
   *
   * <p>上界是词序轮转档数与词池大小的量级：模板变量通常 3~5 个（轮转档数就是变量个数），
   * 再乘修饰词池，超过 8 条后候选之间开始出现肉眼可见的「凑数」——
   * 而凑数出来的候选恰恰是重复铺货判定最爱抓的。
   */
  public static final int MAX_CANDIDATES_PER_SKU = 8;

  /** 一个模板最多 12 个变量占位。再多就不是标题了，是描述（那是 3.4.2.2 的范围）。 */
  public static final int MAX_VARIABLES_PER_TEMPLATE = 12;

  /** 一个模板最多配 12 个平台长度上限。 */
  public static final int MAX_PLATFORMS_PER_TEMPLATE = 12;

  /** 平台标题长度硬上限 255（Shopify 的口径，是主流平台里最宽的）。 */
  public static final int MAX_PLATFORM_TITLE_LENGTH = 255;

  /** 模板文本长度上限 2000（蓝图说标题库，也可以存长一点的模板再截断，但别无限长）。 */
  public static final int MAX_PATTERN_LENGTH = 2000;

  /** 词根文本长度上限 60（短词根；长文本卖点归 3.4.2.2）。 */
  public static final int MAX_KEYWORD_LENGTH = 60;

  /** 一次批量最多指定 20 个词根 code 做过滤。 */
  public static final int MAX_KEYWORD_FILTER = 20;

  /**
   * 每条候选在重复度闸门前的最大确定性尝试次数。
   *
   * <p>算法不掷骰子（那会让「重复度已受控」变成不可断言的事实），
   * 而是按固定顺序枚举「词序轮转档 × 词池下标」的组合，取第一个过闸的。
   * 上限 24 = 8 条候选 × 3 轮：词池太小时宁可拒掉并给出原因，
   * 也不要靠无限轮转凑出一条与已有标题雷同的。
   */
  public static final int MAX_ATTEMPTS_PER_CANDIDATE = 24;

  /**
   * 重复度上限的**上界是 999 而不是 1000**。
   *
   * <p>1000‰ 表示「允许与已有标题完全一样」，那等于没有重复度控制。
   * V20 的 CHECK 与这里的校验都把 999 当天花板，让完全雷同在配置层就不可能被配出来。
   */
  public static final int MAX_SIMILARITY_PERMILLE = 999;

  /** 默认重复度上限 850‰：既有 15% 的差异空间，又不至于要求每个词都换。 */
  public static final int DEFAULT_SIMILARITY_PERMILLE = 850;

  // ============================================================ 相似度算法
  //
  // 蓝图：「批量生成要保证"相似但不雷同"，需在生成算法中显式控制重复度。」
  //
  // 相似度用**两个确定性成分**的加权和（千分比整数，全程无浮点）：
  //
  //   dice   = 2·|A∩B| / (|A|+|B|)   —— 分词多重集上的 Dice 系数
  //   adj    = |G∩H| / |G∪H|         —— 字符二元组集合上的 Jaccard
  //   sim    = (700·dice + 300·adj) / 1000
  //
  // 为什么两个成分：只算 dice 时，「词序换了但词没换」在多重集上完全等价——
  // 而平台判重看的是整句话，不只是词袋。只算 adj 时，中文改一个字会波及一片二元组、
  // 惩罚过重。0.7/0.3 是刻意的偏重：词袋是主体，相邻关系用来区分「同样这批词的两种排法」。
  //
  // 全部是整数除法（正数即向下取整），所以**同样的输入永远得到同样的输出**，
  // 可以被单元测试逐条断言，也可以被 DB 里的 similarity_permille 列存档。

  /** 分词权重（千分比）。 */
  private static final int WEIGHT_DICE = 700;

  /** 二元组邻接权重（千分比）。两权重之和为 1000。 */
  private static final int WEIGHT_ADJACENT = 300;

  /**
   * 两条标题的相似度（千分比，0~1000）。
   *
   * <p>两侧为空串时返回 0：两个空标题「完全相同」，但空标题根本不会被生成
   * （渲染结果为空的组合在 {@link TitleGenerator} 里就被拒了），
   * 这里给 0 只是为了让这个纯函数对任意入参都有定义。
   */
  public static int similarityPermille(String left, String right) {
    String a = normalizeForCompare(left);
    String b = normalizeForCompare(right);
    if (a.isEmpty() || b.isEmpty()) return 0;
    int dice = dicePermille(tokenize(a), tokenize(b));
    int adjacent = jaccardPermille(bigramSet(a), bigramSet(b));
    return (WEIGHT_DICE * dice + WEIGHT_ADJACENT * adjacent) / 1000;
  }

  /** 一组标题与另一组标题之间的最大两两相似度（空集合返回 0）。 */
  public static int maxSimilarityPermille(String candidate, List<String> others) {
    int max = 0;
    if (others == null) return max;
    for (String other : others) {
      if (other == null) continue;
      max = Math.max(max, similarityPermille(candidate, other));
      // 相似度上界是 999，不可能再更高，提前退出省掉后面的比较。
      if (max >= MAX_SIMILARITY_PERMILLE) return max;
    }
    return max;
  }

  /**
   * 分词口径（确定性，三步）：
   *
   * <ol>
   *   <li>NFKC 规范化并转小写——全角「ＡＢＣ」与半角「abc」是同一个词，
   *       不归一的话「换全角避重复」会成为一种假差异化。
   *   <li>按 Unicode 码点遍历：拉丁/数字连续段合并成一个词元（「bluetooth5」是一个词元）。
   *   <li>CJK 字符**逐字**成词元——中文没有空格，逐字切分是唯一确定性的口径，
   *       且它能捕捉「换一个字」这种最典型的差异化手段。
   * </ol>
   */
  public static List<String> tokenize(String normalizedValue) {
    List<String> tokens = new ArrayList<>();
    if (normalizedValue == null || normalizedValue.isEmpty()) return tokens;
    StringBuilder latin = new StringBuilder();
    int[] cps = normalizedValue.codePoints().toArray();
    for (int cp : cps) {
      if (isCjk(cp)) {
        flush(latin, tokens);
        tokens.add(new String(Character.toChars(cp)));
      } else if (Character.isLetterOrDigit(cp)) {
        latin.appendCodePoint(cp);
      } else {
        flush(latin, tokens);
      }
    }
    flush(latin, tokens);
    return tokens;
  }

  /** 字符二元组集合（捕捉相邻关系与分隔风格）。不足两个码点时返回空集合。 */
  public static Set<String> bigramSet(String normalizedValue) {
    Set<String> out = new HashSet<>();
    if (normalizedValue == null) return out;
    int[] cps = normalizedValue.codePoints().toArray();
    for (int i = 0; i + 1 < cps.length; i++)
      out.add(new String(cps, i, 2));
    return out;
  }

  /** 归一化：NFKC + 小写。相似度的一切比较都在归一化之后做。 */
  public static String normalizeForCompare(String value) {
    if (value == null) return "";
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
  }

  /** 字符长度按**码点**算，不是 UTF-16 的 char 数（emoji 与 CJK 扩展 B 都是 1 个码点）。 */
  public static int charLength(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }

  /** 在码点边界上裁剪到最多 maxLength 个码点（不会切出半个字符）。 */
  public static String clampByCharLength(String value, int maxLength) {
    if (value == null) return "";
    if (charLength(value) <= maxLength) return value;
    int[] cps = value.codePoints().toArray();
    return new String(cps, 0, Math.max(0, maxLength));
  }

  private static void flush(StringBuilder latin, List<String> tokens) {
    if (latin.length() > 0) {
      tokens.add(latin.toString());
      latin.setLength(0);
    }
  }

  /**
   * CJK 判定：汉字（基本区 + 扩展 A + 扩展 B~F + 兼容区）、假名、谚文。
   *
   * <p>「汉字兼容表意文字」（U+F900~U+FAFF）刻意**不**算 CJK：NFKC 之后它们已经
   * 归一成基本区汉字了，留在这一段会让同一个字被切成两个词元。
   */
  static boolean isCjk(int cp) {
    return (cp >= 0x4E00 && cp <= 0x9FFF)
        || (cp >= 0x3400 && cp <= 0x4DBF)
        || (cp >= 0x20000 && cp <= 0x2A6DF)
        || (cp >= 0x2A700 && cp <= 0x2EBEF)
        || (cp >= 0x2F800 && cp <= 0x2FA1F)
        || (cp >= 0x3040 && cp <= 0x30FF)
        || (cp >= 0xAC00 && cp <= 0xD7AF);
  }

  /** Dice 系数（千分比）。按多重集算，所以「同一个词出现两次」会体现为更高的相似度。 */
  static int dicePermille(List<String> a, List<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    Map<String, Integer> left = new HashMap<>();
    for (String token : a) left.merge(token, 1, Integer::sum);
    int shared = 0;
    for (Map.Entry<String, Integer> e : left.entrySet())
      shared += Math.min(e.getValue(), countOf(b, e.getKey()));
    // 2000·shared/(a+b)：整数除法即向下取整，全程无浮点。
    return (int) (2000L * shared / (a.size() + b.size()));
  }

  private static int countOf(List<String> tokens, String token) {
    int n = 0;
    for (String t : tokens) if (t.equals(token)) n++;
    return n;
  }

  /** Jaccard 系数（千分比）。按集合算，二元组重复出现不重复计数。 */
  static int jaccardPermille(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    int shared = 0;
    for (String gram : a) if (b.contains(gram)) shared++;
    int union = a.size() + b.size() - shared;
    return (int) (1000L * shared / union);
  }

  // ============================================================ 模板校验

  public static String templateCodeProblem(String code) {
    String value = MediaRules.normalize(code).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "模板编码不能为空";
    if (value.length() > 32) return "模板编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}"))
      return "模板编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String templateNameProblem(String name) {
    String value = MediaRules.normalize(name);
    if (value.isEmpty()) return "模板名称不能为空";
    if (value.length() > 100) return "模板名称最多 100 个字符";
    return null;
  }

  /** 规范化模板文本：折叠连续空白 + 去首尾空白。空白形态不该影响「同一个模板」。 */
  public static String normalizePattern(String pattern) {
    String value = MediaRules.normalize(pattern);
    if (value.isEmpty()) return "";
    return value.replaceAll("\\s+", " ");
  }

  /**
   * 模板文本校验。
   *
   * <p>要求至少有一个占位符：一个完全没有变量的模板渲染出来就是一句固定的话，
   * 批量生成会产出 N 条一模一样的标题——而 3.4.2.1 存在的理由正是「不要一模一样」。
   */
  public static String patternProblem(String pattern) {
    String value = normalizePattern(pattern);
    if (value.isEmpty()) return "标题模板不能为空";
    if (value.length() > MAX_PATTERN_LENGTH) return "标题模板最多 " + MAX_PATTERN_LENGTH + " 个字符";
    List<String> placeholders = placeholdersOf(value);
    if (placeholders.isEmpty())
      return "标题模板里至少要有一个变量占位（例如 {brand} {category} {core}）："
          + "没有变量的模板渲染出来每条候选都一样，正是 3.4.2.1 要避免的重复铺货";
    for (String name : placeholders) if (variableNameProblem(name) != null) return variableNameProblem(name);
    return null;
  }

  /**
   * 提取模板里的占位符名（不含花括号），按出现顺序，**去重后**返回。
   *
   * <p>去重是必要的：{@code {brand} {category} {brand}} 里 brand 出现两次，
   * 服务层解析成变量列表时只保留第一个绑定源，第二个是同一个词的重复提及。
   */
  public static List<String> placeholdersOf(String pattern) {
    String value = normalizePattern(pattern);
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < value.length()) {
      int open = value.indexOf('{', i);
      if (open < 0) break;
      int close = value.indexOf('}', open + 1);
      if (close < 0) break;
      String name = value.substring(open + 1, close).trim();
      if (!name.isEmpty() && !out.contains(name)) out.add(name);
      i = close + 1;
    }
    return out;
  }

  public static String variableNameProblem(String name) {
    String value = MediaRules.normalize(name);
    if (value.isEmpty()) return "变量名不能为空";
    if (value.length() > 32) return "变量名最多 32 个字符";
    if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,31}"))
      return "变量名要以英文字母开头，只含字母、数字或下划线，长度 1~32 位（例如 brand / core / selling）";
    return null;
  }

  public static String sourceProblem(String source) {
    String value = MediaRules.normalizeUpper(source);
    if (!FACT_SOURCES.contains(value) && !KEYWORD_SOURCES.contains(value))
      return "变量绑定源只能是 " + String.join(" / ", FACT_SOURCES) + " 或 " + String.join(" / ", KEYWORD_SOURCES);
    return null;
  }

  /** 变量数上限。 */
  public static String variablesSizeProblem(int size) {
    if (size > MAX_VARIABLES_PER_TEMPLATE) return "一个模板最多 " + MAX_VARIABLES_PER_TEMPLATE + " 个变量占位";
    return null;
  }

  /**
   * 模板声明的变量与模板文本里的占位符必须**互相对得上**。
   *
   * <p>两个方向都要查：文本里写了 {@code {core}} 却没声明 core 的绑定源，
   * 渲染时会拿不到词而留一个 {@code {core}} 字面量（那正是 3.4.2.2 明令禁止的
   * 「静默留下占位符」）；反过来声明了 core 而文本里没有，那条变量是死配置。
   */
  public static String variableBindingProblem(String pattern, List<String> declaredNames) {
    List<String> placeholders = placeholdersOf(pattern);
    List<String> declared = declaredNames == null ? List.of() : declaredNames;
    for (String placeholder : placeholders)
      if (!declared.contains(placeholder))
        return "模板里的占位符 {" + placeholder + "} 没有声明绑定源："
            + "请在变量列表里加一条 " + placeholder + "，并选择它取自 SPU/SKU 字段还是词池";
    for (String name : declared)
      if (!placeholders.contains(name))
        return "变量 " + name + " 在模板文本里没有出现（应为 {" + name + "}）：死配置会让模板含义与实际渲染对不上";
    return null;
  }

  /**
   * 至少要有一个词根变量。
   *
   * <p>只绑 SPU/SKU 事实字段的模板（{@code {brand} {category} {sku_name}}）渲染出来
   * 每个 SKU 只有一种可能文本，候选之间的差异只能来自词序轮转——那最多也就
   * 「变量个数」种排法，几轮就用完了。词根变量才是差异化的主力。
   */
  public static String needsKeywordVariableProblem(List<String> sources) {
    if (sources == null || sources.isEmpty()) return "模板至少要声明一个变量";
    for (String source : sources) if (keywordTypeOfSource(source) != null) return null;
    return "模板至少要有一个词根变量（核心词 / 卖点 / 修饰词）："
        + "只绑 SPU/SKU 字段的话每个 SKU 只有一种文本，批量生成产出的候选会彼此雷同";
  }

  public static String maxSimilarityProblem(Integer permille) {
    if (permille == null) return "重复度上限不能为空";
    if (permille < 0 || permille > MAX_SIMILARITY_PERMILLE)
      return "重复度上限必须在 0~" + MAX_SIMILARITY_PERMILLE
          + "‰ 之间（1000‰ 就是允许完全一样，等于没有重复度控制，所以上界是 999）";
    return null;
  }

  public static String candidateCountProblem(Integer count) {
    if (count == null) return "每个 SKU 的候选条数不能为空";
    if (count < 1 || count > MAX_CANDIDATES_PER_SKU)
      return "每个 SKU 每个平台的候选条数必须在 1~" + MAX_CANDIDATES_PER_SKU + " 之间";
    return null;
  }

  /** 平台长度上限。 */
  public static String platformNameProblem(String platform) {
    String value = MediaRules.normalize(platform);
    if (value.isEmpty()) return "平台不能为空";
    if (value.length() > 64) return "平台名称最多 64 个字符";
    return null;
  }

  public static String maxLengthProblem(Integer maxLength) {
    if (maxLength == null) return "平台标题长度上限不能为空";
    if (maxLength < 1 || maxLength > MAX_PLATFORM_TITLE_LENGTH)
      return "平台标题长度上限必须在 1~" + MAX_PLATFORM_TITLE_LENGTH + " 个字符之间";
    return null;
  }

  public static String templatePlatformsSizeProblem(int size) {
    if (size > MAX_PLATFORMS_PER_TEMPLATE) return "一个模板最多配 " + MAX_PLATFORMS_PER_TEMPLATE + " 个平台长度上限";
    return null;
  }

  /** 同一模板内平台重复。 */
  public static String duplicatePlatformProblem(List<String> platforms) {
    if (platforms == null) return null;
    var seen = new HashSet<String>();
    for (String raw : platforms) {
      String value = MediaRules.normalizeUpper(raw);
      if (!seen.add(value)) return "平台重复：" + value + "（同一模板内每个平台只能配一个长度上限）";
    }
    return null;
  }

  // ============================================================ 词根校验

  public static String keywordCodeProblem(String code) {
    String value = MediaRules.normalize(code).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "词根编码不能为空";
    if (value.length() > 32) return "词根编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}"))
      return "词根编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String keywordTextProblem(String text) {
    String value = MediaRules.normalize(text);
    if (value.isEmpty()) return "词根内容不能为空";
    if (value.length() > MAX_KEYWORD_LENGTH) return "词根内容最多 " + MAX_KEYWORD_LENGTH + " 个字符";
    // 词根里带占位符会让渲染出的标题出现「{x}」这种半成品，禁掉。
    if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0)
      return "词根内容不能包含花括号：它是直接填进标题里的文本，不是另一个模板";
    return null;
  }

  public static String keywordTypeProblem(String type) {
    String value = MediaRules.normalizeUpper(type);
    if (!KEYWORD_TYPES.contains(value))
      return "词根类型只能是 " + String.join(" / ", KEYWORD_TYPES) + "（核心词 / 卖点 / 修饰词）";
    return null;
  }

  // ============================================================ 批量生成参数校验

  public static String skusSizeProblem(int size) {
    if (size == 0) return "请至少选择一个要生成标题的 SKU";
    if (size > MAX_SKUS_PER_GENERATION) return "单次最多为 " + MAX_SKUS_PER_GENERATION + " 个 SKU 生成标题";
    return null;
  }

  public static String platformsSizeProblem(int size) {
    if (size == 0) return "请至少选择一个目标平台";
    if (size > MAX_PLATFORMS_PER_GENERATION) return "单次最多为 " + MAX_PLATFORMS_PER_GENERATION + " 个平台生成标题";
    return null;
  }

  /** 词根过滤上限。 */
  public static String keywordFilterSizeProblem(int size) {
    if (size > MAX_KEYWORD_FILTER) return "一次最多限定 " + MAX_KEYWORD_FILTER + " 个词根";
    return null;
  }

  /** 词表自检：Set.of 的重复元素会直接抛，这里显式断言没有笔误。 */
  static {
    Set<String> f = Set.copyOf(FACT_SOURCES);
    if (f.size() != FACT_SOURCES.size()) throw new IllegalStateException("FACT_SOURCES 有重复项");
    Set<String> k = Set.copyOf(KEYWORD_SOURCES);
    if (k.size() != KEYWORD_SOURCES.size()) throw new IllegalStateException("KEYWORD_SOURCES 有重复项");
    Set<String> t = Set.copyOf(KEYWORD_TYPES);
    if (t.size() != KEYWORD_TYPES.size()) throw new IllegalStateException("KEYWORD_TYPES 有重复项");
    Set<String> g = Set.copyOf(GENERATION_STATUSES);
    if (g.size() != GENERATION_STATUSES.size()) throw new IllegalStateException("GENERATION_STATUSES 有重复项");
    Set<String> r = Set.copyOf(RECORD_STATUSES);
    if (r.size() != RECORD_STATUSES.size()) throw new IllegalStateException("RECORD_STATUSES 有重复项");
    Set<String> o = Set.copyOf(SKU_OUTCOMES);
    if (o.size() != SKU_OUTCOMES.size()) throw new IllegalStateException("SKU_OUTCOMES 有重复项");
    if (WEIGHT_DICE + WEIGHT_ADJACENT != 1000)
      throw new IllegalStateException("相似度两个权重之和必须是 1000");
  }
}