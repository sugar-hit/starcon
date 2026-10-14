package com.starcon.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3.4.2.2 描述与卖点库的纯规则层：词表、容量上限、模板与变量校验、译文一致性。
 *
 * <p>与 {@link TitleRules} / {@link MediaRules} 同一形状：无状态静态方法。 服务层拒绝时给出的话、前端表单校验、集成测试断言的边界都引同一份实现。
 *
 * <p><b>变量缺失报告不在这里产生</b>。那是 {@link DescriptionRenderer} 的职责（要模板解析与
 * 短语池取值），这里只管「参数合不合法」——两类逻辑混在一起会让「缺变量必须被报告」 这条蓝图标着要显式控制的纯函数被校验代码污染。
 */
public final class DescriptionRules {
  private DescriptionRules() {}

  // ============================================================ 词表（与 V21 的 CHECK 逐字一致）

  /** 变量绑定源的事实部分：从 SPU / SKU 取。 */
  public static final List<String> FACT_SOURCES =
      List.of("SPU_BRAND", "SPU_CATEGORY", "SPU_NAME", "SKU_NAME", "SKU_CODE", "SKU_SPEC");

  /** 变量绑定源的短语池部分：从 {@code content_phrase} 取。 */
  public static final List<String> PHRASE_SOURCES =
      List.of(
          "PHRASE_SELLING", "PHRASE_FEATURE", "PHRASE_SCENE", "PHRASE_CAUTION", "SKU_SIBLING_LIST");

  public static final List<String> CONTENT_TYPES =
      List.of("DESCRIPTION", "INSTRUCTION", "CAUTION", "SELLING_POINT");
  public static final List<String> PHRASE_TYPES = List.of("SELLING", "FEATURE", "SCENE", "CAUTION");
  public static final List<String> RENDER_STATUSES = List.of("SUCCEEDED", "PARTIAL", "FAILED");
  public static final List<String> SECTION_STATUSES = List.of("COMPLETE", "INCOMPLETE");
  public static final List<String> SKU_OUTCOMES = List.of("RENDERED", "INCOMPLETE", "REJECTED");
  public static final List<String> TRANSLATION_ORIGINS = List.of("MANUAL", "AI", "IMPORTED");
  public static final List<String> TRANSLATION_STATUSES = List.of("DRAFT", "REVIEWED");

  /** 缺失变量的四种成因。与 V21 的 content_render_issue.issue_kind CHECK 逐字一致。 */
  public static final List<String> ISSUE_KINDS =
      List.of("EMPTY_FACT", "EMPTY_PHRASE_POOL", "TRANSLATION_MISSING", "UNBOUND_PLACEHOLDER");

  private static final Map<String, String> CONTENT_TYPE_LABELS =
      Map.of(
          "DESCRIPTION", "详情描述",
          "INSTRUCTION", "使用说明",
          "CAUTION", "注意事项",
          "SELLING_POINT", "卖点段落");

  private static final Map<String, String> PHRASE_TYPE_LABELS =
      Map.of(
          "SELLING", "卖点陈述",
          "FEATURE", "特性描述",
          "SCENE", "适用场景",
          "CAUTION", "注意事项短语");

  private static final Map<String, String> SOURCE_LABELS =
      Map.ofEntries(
          Map.entry("SPU_BRAND", "SPU 品牌"),
          Map.entry("SPU_CATEGORY", "SPU 分类"),
          Map.entry("SPU_NAME", "SPU 名称"),
          Map.entry("SKU_NAME", "SKU 名称"),
          Map.entry("SKU_CODE", "SKU 编码"),
          Map.entry("SKU_SPEC", "SKU 规格"),
          Map.entry("PHRASE_SELLING", "卖点短语池"),
          Map.entry("PHRASE_FEATURE", "特性短语池"),
          Map.entry("PHRASE_SCENE", "场景短语池"),
          Map.entry("PHRASE_CAUTION", "注意事项短语池"),
          Map.entry("SKU_SIBLING_LIST", "同 SPU 型号清单"));

  private static final Map<String, String> RENDER_STATUS_LABELS =
      Map.of("SUCCEEDED", "已填充", "PARTIAL", "部分段落缺变量", "FAILED", "未填充出可用段落");

  private static final Map<String, String> SECTION_STATUS_LABELS =
      Map.of("COMPLETE", "已填好", "INCOMPLETE", "缺变量");

  private static final Map<String, String> OUTCOME_LABELS =
      Map.of("RENDERED", "已填出可用段落", "INCOMPLETE", "有段落缺变量", "REJECTED", "未填出可用段落");

  private static final Map<String, String> ORIGIN_LABELS =
      Map.of("MANUAL", "人工撰写", "AI", "AI 预填", "IMPORTED", "导入");

  private static final Map<String, String> TRANSLATION_STATUS_LABELS =
      Map.of("DRAFT", "草稿", "REVIEWED", "已复核");

  private static final Map<String, String> ISSUE_KIND_LABELS =
      Map.of(
          "EMPTY_FACT", "商品字段为空",
          "EMPTY_PHRASE_POOL", "短语池为空",
          "TRANSLATION_MISSING", "该语言没有译文",
          "UNBOUND_PLACEHOLDER", "占位符没有绑定源");

  public static String contentTypeLabel(String value) {
    return CONTENT_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String phraseTypeLabel(String value) {
    return PHRASE_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String sourceLabel(String value) {
    return SOURCE_LABELS.getOrDefault(value, value);
  }

  public static String renderStatusLabel(String value) {
    return RENDER_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String sectionStatusLabel(String value) {
    return SECTION_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String outcomeLabel(String value) {
    return OUTCOME_LABELS.getOrDefault(value, value);
  }

  public static String originLabel(String value) {
    return ORIGIN_LABELS.getOrDefault(value, value);
  }

  public static String translationStatusLabel(String value) {
    return TRANSLATION_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String issueKindLabel(String value) {
    return ISSUE_KIND_LABELS.getOrDefault(value, value);
  }

  /** 短语绑定源 → 短语库类型（渲染时按这个类型取池子）。事实源返回 null。 */
  public static String phraseTypeOfSource(String source) {
    String value = MediaRules.normalizeUpper(source);
    if ("PHRASE_SELLING".equals(value)) return "SELLING";
    if ("PHRASE_FEATURE".equals(value)) return "FEATURE";
    if ("PHRASE_SCENE".equals(value)) return "SCENE";
    if ("PHRASE_CAUTION".equals(value)) return "CAUTION";
    return null;
  }

  // ============================================================ 容量上限

  /**
   * 单次批量最多 200 个 SKU。
   *
   * <p>不是算力上限（填充是纯字符串计算，每个 SKU 一次替换），而是**可用性**上限： 一次填充 2000 个 SKU × 每个商品要填详情描述 / 使用说明 / 注意事项三段，
   * 段落列表根本没法逐条核对，而「这段少了哪个变量」正是运营要看的。
   */
  public static final int MAX_SKUS_PER_RENDER = 200;

  /** 一个模板最多 16 个变量占位。 */
  public static final int MAX_VARIABLES_PER_TEMPLATE = 16;

  /**
   * 模板正文长度上限 20000 字符。
   *
   * <p>与 3.4.2.1 的标题模板（2000）差一个数量级：那边受平台标题硬上限约束， 这边是**长文本**且蓝图明确没有字符上限，20000 足够一整页详情描述与一份说明书。
   */
  public static final int MAX_BODY_LENGTH = 20000;

  /**
   * 短语文本长度上限 2000 字符。
   *
   * <p>长句（可含标点与换行），但不是整页：一条卖点陈述超过 2000 字那是在粘说明书， 而说明书属于 {@code content_type='INSTRUCTION'} 的模板。
   */
  public static final int MAX_PHRASE_LENGTH = 2000;

  /** 语言标签长度上限 16（BCP-47 简写如 zh-CN / en-US）。 */
  public static final int MAX_LOCALE_LENGTH = 16;

  /** 一次批量最多指定 10 种语言做翻译完整度检查。 */
  public static final int MAX_LOCALES = 10;

  // ============================================================ 模板校验

  public static String templateCodeProblem(String code) {
    String value = MediaRules.normalize(code).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "模板编码不能为空";
    if (value.length() > 32) return "模板编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) return "模板编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String templateNameProblem(String name) {
    String value = MediaRules.normalize(name);
    if (value.isEmpty()) return "模板名称不能为空";
    if (value.length() > 100) return "模板名称最多 100 个字符";
    return null;
  }

  public static String contentTypeProblem(String contentType) {
    String value = MediaRules.normalizeUpper(contentType);
    if (!CONTENT_TYPES.contains(value))
      return "内容类型只能是 " + String.join(" / ", CONTENT_TYPES) + "（详情描述 / 使用说明 / 注意事项 / 卖点段落）";
    return null;
  }

  /** 规范化正文：折叠连续空白 + 去首尾空白。空白形态不该影响「同一个模板」。 */
  public static String normalizeBody(String body) {
    String value = MediaRules.normalize(body);
    if (value.isEmpty()) return "";
    return value.replaceAll("\\s+", " ");
  }

  /**
   * 模板正文校验。
   *
   * <p>与 3.4.2.1 的 patternProblem 有一个**刻意的差别**：那边要求至少一个占位符 （没有变量的标题模板渲染出来每条候选都一样，正是标题库要避免的重复铺货），
   * 这边**不要求**——「请勿进水」「适用于 0~40℃ 环境」这种注意事项就是一个变量都没有的 固定句子，它完全成立，强制加占位符只会逼运营写废话占位。
   *
   * <p>但花括号必须配平：{@code {brand} 采用了} 这种漏写右括号的正文会让 占位符解析器把后面一大段都吞掉，而填充时看起来「只是少了几个字」——
   * 这正是「静默留下占位符」的另一种形态，所以在校验层就拒。
   */
  public static String bodyProblem(String body) {
    String value = normalizeBody(body);
    if (value.isEmpty()) return "模板正文不能为空";
    if (value.length() > MAX_BODY_LENGTH)
      return "模板正文最多 " + MAX_BODY_LENGTH + " 个字符（长文本，但仍不是整本说明书）";
    if (unbalancedBraceIndex(value) >= 0)
      return "正文里的花括号没有配平：位置 "
          + (unbalancedBraceIndex(value) + 1)
          + " 附近有一个孤立的 { 或 }。"
          + "漏写右括号会让占位符解析把后面一整段都吞成变量名，填充时看不出来却会漏内容";
    for (String name : placeholdersOf(value))
      if (variableNameProblem(name) != null) return variableNameProblem(name);
    return null;
  }

  /**
   * 第一个未配平花括号的位置，没有则返回 -1。
   *
   * <p>逐字符扫并记录哪个括号是「开着的」：遇到 {@code }} 遇到孤立右括号报它，
   * 遇到 {@code {} 遇到孤立左括号报它。
   */
  static int unbalancedBraceIndex(String value) {
    int open = -1;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '{') {
        if (open < 0) open = i;
      } else if (c == '}') {
        if (open < 0) return i;
        open = -1;
      }
    }
    return open;
  }

  /**
   * 提取正文里的占位符名（不含花括号），按出现顺序，**去重后**返回。
   *
   * <p>去重是必要的：{@code {brand} 与 {brand}} 里 brand 出现两次， 服务层解析成变量列表时只保留第一个绑定源，另一个是同一个词的重复提及。
   */
  public static List<String> placeholdersOf(String body) {
    String value = normalizeBody(body);
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
      return "变量名要以英文字母开头，只含字母、数字或下划线，长度 1~32 位（例如 brand / material / selling）";
    return null;
  }

  public static String sourceProblem(String source) {
    String value = MediaRules.normalizeUpper(source);
    if (!FACT_SOURCES.contains(value) && !PHRASE_SOURCES.contains(value))
      return "变量绑定源只能是 "
          + String.join(" / ", FACT_SOURCES)
          + " 或 "
          + String.join(" / ", PHRASE_SOURCES);
    return null;
  }

  /** 变量数上限。 */
  public static String variablesSizeProblem(int size) {
    if (size > MAX_VARIABLES_PER_TEMPLATE) return "一个模板最多 " + MAX_VARIABLES_PER_TEMPLATE + " 个变量占位";
    return null;
  }

  /**
   * 模板声明的变量与模板正文里的占位符必须**互相对得上**。
   *
   * <p>两个方向都要查：正文里写了 {@code {material}} 却没声明 material 的绑定源，
   * 填充时那个占位符拿不到值——而蓝图明令「变量缺失必须报告，不能静默留下占位符」， 所以这里直接把配置错误挡在保存时；反过来声明了 material 而正文里没有，
   * 那条变量是死配置，会让模板含义与实际填充对不上。
   */
  public static String variableBindingProblem(String body, List<String> declaredNames) {
    List<String> placeholders = placeholdersOf(body);
    List<String> declared = declaredNames == null ? List.of() : declaredNames;
    for (String placeholder : placeholders)
      if (!declared.contains(placeholder))
        return "正文里的占位符 {"
            + placeholder
            + "} 没有声明绑定源："
            + "请在变量列表里加一条 "
            + placeholder
            + "，并选择它取自 SPU/SKU 字段、短语池还是型号清单。"
            + "没声明的占位符填不出值，会被降级成「缺变量」并报告——那正是 3.4.2.2 明令禁止的静默占位符";
    for (String name : declared)
      if (!placeholders.contains(name))
        return "变量 " + name + " 在模板正文里没有出现（应为 {" + name + "}）：死配置会让模板含义与实际填充对不上";
    return null;
  }

  // ============================================================ 语言与译文

  /**
   * 语言标签校验（BCP-47 简写）。
   *
   * <p>只校验形状不校验语义：本模块不维护语言字典（那是 3.4.3.1 / 18 的事）， 所以 {@code zh-CN} / {@code en-US} / {@code pt-BR}
   * 都被接受， 而 {@code 中文} / {@code en_US}（下划线）/ {@code e}（太短）被拒—— 下划线写法会让「同一个语言的两种拼法」各建一条译文，locale
   * 唯一键因此形同虚设。
   *
   * <p><b>与本类其它 {@code *Problem} 方法同一契约：合法返回 null，非法返回原因文本。</b> 规范化后的值请用 {@link
   * #normalizeLocale(String)} 取。 （早先这里让本方法「合法时返回规范化值」，于是它被顺手塞进 {@code firstNonNull(...)} 时，"zh-CN"
   * 本身被当成问题报了出来—— 一条方法两种返回语义是这类 bug 的温床。）
   */
  public static String localeProblem(String locale) {
    String value = MediaRules.normalize(locale);
    if (value.isEmpty()) return "语言不能为空（例如 zh-CN / en-US）";
    if (value.length() > MAX_LOCALE_LENGTH) return "语言标签最多 " + MAX_LOCALE_LENGTH + " 个字符";
    if (!value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?"))
      return "语言标签请用 BCP-47 简写（zh-CN / en-US / ja-JP）：" + "不要用中文名或下划线写法，同一个语言的两种拼法会各建一条译文";
    return null;
  }

  /**
   * 规范化后的语言标签（BCP-47 规范写法：语言小写 + 地区大写）。
   *
   * <p><b>入库与查询必须走同一份规范化</b>，否则筛选会静默失效： 入库存 "zh-CN"、查询用 {@code toUpperCase} 变成 "ZH-CN"，那条筛选就永远匹配不到，
   * 而页面上表现为「明明有数据却查不出来」——最难查的一类症状。 规范化同时让 "ZH-cn" / "zh-CN" 收敛成同一个语言， 这正是 {@link #localeProblem}
   * 拒绝下划线写法的同一个目的。
   */
  public static String normalizeLocale(String locale) {
    String value = MediaRules.normalize(locale);
    if (value.isEmpty()) return "";
    int dash = value.indexOf('-');
    if (dash < 0) return value.toLowerCase(Locale.ROOT);
    return value.substring(0, dash).toLowerCase(Locale.ROOT)
        + "-"
        + value.substring(dash + 1).toUpperCase(Locale.ROOT);
  }

  public static String translationNameProblem(String name) {
    String value = MediaRules.normalize(name);
    if (value.isEmpty()) return "译文名称不能为空";
    if (value.length() > 100) return "译文名称最多 100 个字符";
    return null;
  }

  public static String translationOriginProblem(String origin) {
    String value = MediaRules.normalizeUpper(origin);
    if (!TRANSLATION_ORIGINS.contains(value))
      return "译文来源只能是 " + String.join(" / ", TRANSLATION_ORIGINS) + "（人工 / AI 预填 / 导入）";
    return null;
  }

  public static String translationStatusProblem(String status) {
    String value = MediaRules.normalizeUpper(status);
    if (!TRANSLATION_STATUSES.contains(value))
      return "译文状态只能是 " + String.join(" / ", TRANSLATION_STATUSES) + "（草稿 / 已复核）";
    return null;
  }

  /**
   * 译文的占位符必须与源语言正文**完全一致**。
   *
   * <p>三个方向都要查，因为机器译文最典型的失败模式就是这三个：
   *
   * <ul>
   *   <li>译文漏了一个占位符（{brand} 直接被译成了品牌名而不是占位符） → 填充时那句话对所有 SKU 都一样，正是「变量没参与填充」的静默形态；
   *   <li>译文多了一个占位符（{color} 是译者自己加的，源模板没有） → 填充时必然缺变量，每一批都降级成 INCOMPLETE；
   *   <li>占位符拼错（{brnad}）→ 同样多出一个没绑定源的占位符。
   * </ul>
   *
   * <p>所以译文保存前就按集合相等校验，而不是等到填充时才发现。
   */
  public static String translationPlaceholdersProblem(String sourceBody, String translatedBody) {
    List<String> source = placeholdersOf(sourceBody);
    List<String> translated = placeholdersOf(translatedBody);
    for (String name : translated)
      if (!source.contains(name))
        return "译文里的占位符 {"
            + name
            + "} 在源语言正文里没有："
            + "译文必须与源语言的占位符集合完全一致。多出来的占位符（常见于 AI 预填时自己加的）"
            + "填不出值，会让每次填充都降级成「缺变量」";
    for (String name : source)
      if (!translated.contains(name))
        return "译文漏掉了源语言的占位符 {"
            + name
            + "}："
            + "译文必须与源语言的占位符集合完全一致。漏掉的占位符会让那句话对所有 SKU 都一样，"
            + "而变量其实压根没参与填充";
    return null;
  }

  /** 一次翻译完整度检查最多指定 10 种语言。 */
  public static String localesSizeProblem(int size) {
    if (size > MAX_LOCALES) return "一次最多检查 " + MAX_LOCALES + " 种语言的译文完整度";
    return null;
  }

  // ============================================================ 短语校验

  public static String phraseCodeProblem(String code) {
    String value = MediaRules.normalize(code).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "短语编码不能为空";
    if (value.length() > 32) return "短语编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) return "短语编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String phraseTextProblem(String text) {
    String value = MediaRules.normalize(text);
    if (value.isEmpty()) return "短语内容不能为空";
    if (value.length() > MAX_PHRASE_LENGTH) return "短语内容最多 " + MAX_PHRASE_LENGTH + " 个字符";
    // 短语里带占位符会让填充出的段落出现「{x}」这种半成品，禁掉。
    if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0)
      return "短语内容不能包含花括号：它是直接填进正文里的文本，不是另一个模板";
    return null;
  }

  public static String phraseTypeProblem(String type) {
    String value = MediaRules.normalizeUpper(type);
    if (!PHRASE_TYPES.contains(value))
      return "短语类型只能是 " + String.join(" / ", PHRASE_TYPES) + "（卖点陈述 / 特性描述 / 适用场景 / 注意事项短语）";
    return null;
  }

  // ============================================================ 批量填充参数校验

  public static String skusSizeProblem(int size) {
    if (size == 0) return "请至少选择一个要填充描述的 SKU";
    if (size > MAX_SKUS_PER_RENDER) return "单次最多为 " + MAX_SKUS_PER_RENDER + " 个 SKU 填充描述";
    return null;
  }

  /** 字符长度按**码点**算，不是 UTF-16 的 char 数（emoji 与 CJK 扩展 B 都是 1 个码点）。 */
  public static int charLength(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }

  /** 词表自检：List.of 的重复元素会直接抛，这里显式断言没有笔误。 */
  static {
    requireNoDuplicates(FACT_SOURCES, "FACT_SOURCES");
    requireNoDuplicates(PHRASE_SOURCES, "PHRASE_SOURCES");
    requireNoDuplicates(CONTENT_TYPES, "CONTENT_TYPES");
    requireNoDuplicates(PHRASE_TYPES, "PHRASE_TYPES");
    requireNoDuplicates(RENDER_STATUSES, "RENDER_STATUSES");
    requireNoDuplicates(SECTION_STATUSES, "SECTION_STATUSES");
    requireNoDuplicates(SKU_OUTCOMES, "SKU_OUTCOMES");
    requireNoDuplicates(TRANSLATION_ORIGINS, "TRANSLATION_ORIGINS");
    requireNoDuplicates(TRANSLATION_STATUSES, "TRANSLATION_STATUSES");
    requireNoDuplicates(ISSUE_KINDS, "ISSUE_KINDS");
  }

  private static void requireNoDuplicates(List<String> values, String name) {
    if (new HashSet<>(values).size() != values.size())
      throw new IllegalStateException(name + " 有重复项");
  }
}
