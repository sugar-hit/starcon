package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 11.4.2.2 客服知识库与话术的纯规则。
 *
 * <p>蓝图 11.4.2.2：「职责：维护客服知识库与回复模板：常见问题标准答法、平台政策说明、 退换货话术、多语言版本；记录使用效果。」「输出：话术库 + 客服一键引用；命中率统计。」
 * 「<b>话术需与平台规则版本挂钩</b>，平台政策变化时能定位哪些话术需要更新。」
 *
 * <p>本类只放**不需要数据库就能判定**的规则，所以它能被单元测试逐条钉死； 需要读写库的部分在 {@link KnowledgeBaseService}。分界与 {@link
 * DescriptionRules} 一致。
 *
 * <p>本类自己钉死的口径（R1~R5）：
 *
 * <ol>
 *   <li><b>R1 占位符集合必须相等。</b>译文与源语言的 {@code {name}} 集合必须完全一致， 两个方向都查（与 {@code DescriptionRules}
 *       同一纪律）：译文漏一个 → 答复里出现裸 {@code {buyer_name}}；译文多一个 → 每条答复都缺变量。DB 层有触发器兜底。
 *   <li><b>R2 命中率 = 有引用事件的启用话术 / 启用话术总数。</b>分子<b>不是</b> 「被引用且 RESOLVED 的条数」——那样一条被引用 1 次的烂话术和一条被引用
 *       800 次的 好话术都得 1，而「多少条话术根本没人用」恰恰是要回答的问题。 分母只算启用的：停用的话术没被引用是预期的，不该拉低命中率。
 *   <li><b>R3 REJECTED 与 ABANDONED 不计入命中。</b>前者是「照话术答复被驳回」， 后者是「打开话术但最终没发出去」——两者都是负信号，计进分子会奖励坏话术。
 *   <li><b>R4 命中率分母为零时报 null 而不是 0。</b>「一条话术都没有」与 「话术一条都没被用过」是两件不同的事，页面必须能区分。
 *   <li><b>R5 触发词是短词不是长句。</b>每个词 ≤ 40 字符、不含逗号、不含花括号。 客服检索时打的是「退」「换」，不是整句。
 * </ol>
 */
public final class KnowledgeBaseRules {
  private KnowledgeBaseRules() {}

  /** 占位符：{@code {name}}。与 V21 同一口径 —— 宽松取花括号之间的内容，不限定 ASCII。 */

  /** 词表：与 V100 的 CHECK 逐字对应。 */
  public static final List<String> ENTRY_TYPES = List.of("FAQ", "POLICY", "RETURN_EX", "OTHER");

  public static final List<String> PLATFORMS =
      List.of("AMAZON", "SHOPIFY", "EBAY", "TIKTOK", "ALI1688");

  public static final List<String> USAGE_OUTCOMES =
      List.of("RESOLVED", "PARTIAL", "UNRESOLVED", "ABANDONED", "REJECTED");

  public static final List<String> REVIEW_STATUSES = List.of("PENDING", "REVIEWING", "DONE");

  public static final List<String> TRANSLATION_ORIGINS = List.of("MANUAL", "AI", "IMPORTED");

  /** 正文上限 8000 字符：客服会话窗口里的话术是一屏，不是说明书（V21 的 20000 是详情页）。 */
  public static final int MAX_BODY = 8000;

  public static final int MAX_TITLE = 120;

  /** 单个触发词上限。R5。 */
  public static final int MAX_KEYWORD = 40;

  /** 触发词最多几个：再多就成全文检索了，不该由「话术库」这个入口承担。 */
  public static final int MAX_KEYWORDS = 12;

  public static final int MAX_CHANGE_SUMMARY = 2000;

  /** 词表选项，供前端下拉（与 {@code KnowledgeBaseController.options} 同源）。 */
  public record Option(String value, String label) {}

  public static List<Option> entryTypeOptions() {
    return List.of(
        new Option("FAQ", "常见问题标准答法"),
        new Option("POLICY", "平台政策说明"),
        new Option("RETURN_EX", "退换货话术"),
        new Option("OTHER", "其它话术"));
  }

  public static List<Option> outcomeOptions() {
    return List.of(
        new Option("RESOLVED", "一次解决"),
        new Option("PARTIAL", "部分解决"),
        new Option("UNRESOLVED", "未解决"),
        new Option("ABANDONED", "放弃引用"),
        new Option("REJECTED", "话术被驳回"));
  }

  public static List<Option> reviewStatusOptions() {
    return List.of(
        new Option("PENDING", "待复核"), new Option("REVIEWING", "复核中"), new Option("DONE", "已复核"));
  }

  public static String entryTypeLabel(String value) {
    return switch (value) {
      case "FAQ" -> "常见问题标准答法";
      case "POLICY" -> "平台政策说明";
      case "RETURN_EX" -> "退换货话术";
      case "OTHER" -> "其它话术";
      default -> value;
    };
  }

  public static String outcomeLabel(String value) {
    return switch (value) {
      case "RESOLVED" -> "一次解决";
      case "PARTIAL" -> "部分解决";
      case "UNRESOLVED" -> "未解决";
      case "ABANDONED" -> "放弃引用";
      case "REJECTED" -> "话术被驳回";
      default -> value;
    };
  }

  public static String reviewStatusLabel(String value) {
    return switch (value) {
      case "PENDING" -> "待复核";
      case "REVIEWING" -> "复核中";
      case "DONE" -> "已复核";
      default -> value;
    };
  }

  // ================================================================ 规范化

  /** 去首尾空白并把连续空白折叠成一个空格（与 {@code MediaRules.normalize} 同一形状）。 */
  public static String normalize(String value) {
    if (value == null) return "";
    return value.trim().replaceAll("\\s+", " ");
  }

  /** 正文折叠内部连续空白（含换行）成单个空格：客服话术是一段话，不靠排版承载语义。 */
  public static String normalizeBody(String value) {
    if (value == null) return "";
    return value.trim().replaceAll("\\s+", " ");
  }

  public static String normalizeUpper(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  // ================================================================ 条目校验

  public static String codeProblem(String code) {
    String value = normalizeUpper(code);
    if (value.isEmpty()) return "话术编码不能为空";
    if (value.length() > 32) return "话术编码最长 32 个字符（现在 " + value.length() + " 个）";
    if (!value.matches("[A-Z0-9_]+")) return "话术编码只能用大写字母、数字与下划线（便于客服与运营互相口头引用）";
    return null;
  }

  public static String titleProblem(String title) {
    String value = normalize(title);
    if (value.isEmpty()) return "标题不能为空";
    if (value.length() > MAX_TITLE)
      return "标题最长 " + MAX_TITLE + " 个字符（现在 " + value.length() + " 个）";
    return null;
  }

  public static String entryTypeProblem(String entryType) {
    if (entryType == null || entryType.isBlank()) return "话术类型不能为空";
    if (!ENTRY_TYPES.contains(normalizeUpper(entryType)))
      return "话术类型只能是 " + String.join(" / ", ENTRY_TYPES);
    return null;
  }

  public static String bodyProblem(String body) {
    String value = body == null ? "" : body.trim();
    if (value.isEmpty()) return "标准答法正文不能为空";
    if (value.length() > MAX_BODY)
      return "正文最长 " + MAX_BODY + " 个字符（现在 " + value.length() + " 个）—— 客服会话窗口里的话术是一屏，不是说明书";
    return null;
  }

  public static String localeProblem(String locale) {
    String value = normalize(locale);
    if (value.isEmpty()) return "语言不能为空";
    if (value.length() > 16) return "语言代码最长 16 个字符";
    if (!value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?"))
      return "语言代码请用 BCP-47 简写，如 zh-CN / en-US";
    return null;
  }

  /** 源语言规范化成 BCP-47 的规范大小写：zh-cn → zh-CN，en-us → en-US。 */
  public static String normalizeLocale(String locale) {
    String value = normalize(locale);
    int dash = value.indexOf('-');
    if (dash < 0) return value.toLowerCase(Locale.ROOT);
    String lang = value.substring(0, dash).toLowerCase(Locale.ROOT);
    String region = value.substring(dash + 1);
    // 地区段 2 位或 3 位数字按小写（zh-Hans-CN 的 script 段不参与本判断），其余按大写。
    return region.matches("\\d{3}")
        ? lang + "-" + region
        : lang + "-" + region.toUpperCase(Locale.ROOT);
  }

  // ================================================================ R5 触发词

  public static String keywordsProblem(String keywords) {
    if (keywords == null || keywords.isBlank()) return null;
    List<String> words = splitKeywords(keywords);
    if (words.isEmpty()) return "触发词不能只由逗号或空格组成";
    if (words.size() > MAX_KEYWORDS)
      return "触发词最多 " + MAX_KEYWORDS + " 个（现在 " + words.size() + " 个）";
    for (String word : words) {
      if (word.length() > MAX_KEYWORD)
        return "触发词「"
            + abbreviate(word)
            + "」最长 "
            + MAX_KEYWORD
            + " 个字符：触发词是短词（客服检索时打的是「退」「换」），不是整句";
      if (word.contains("{") || word.contains("}")) return "触发词不能含花括号（那是正文里的占位符写法）";
      if (word.contains(",") || word.contains("，")) return "触发词之间用英文逗号分隔，单个词内不能含逗号";
    }
    return null;
  }

  /**
   * 触发词规范化：按逗号切，逐个去空白与首尾逗号，再丢掉空段，落库时用「,」连接。
   *
   * <p>**重复词会去掉**：同一个词写两遍（运营复制粘贴很容易发生）会让列表页显示 「退, 退」并在检索词表里占两格。
   */
  public static String normalizeKeywords(String keywords) {
    List<String> words = splitKeywords(keywords);
    Set<String> unique = new LinkedHashSet<>(words);
    return String.join(",", unique);
  }

  private static List<String> splitKeywords(String keywords) {
    List<String> out = new ArrayList<>();
    if (keywords == null) return out;
    for (String part : keywords.split("[,，]")) {
      String word = normalize(part);
      if (!word.isEmpty()) out.add(word);
    }
    return out;
  }

  // ================================================================ R1 占位符一致性

  /**
   * 取出一段正文里的全部占位符名（去重、排序）。
   *
   * <p><b>判定口径与 3.4.2.2 的 {@code DescriptionRules.placeholdersOf} 逐字一致</b>：
   * 一对花括号之间的内容去掉首尾空白后非空即是一个占位符（不限定是 ASCII 标识符）。 两边都是「文案资产」，同一句话在两条线上必须判定成同一个结果 —— 本模块要是只认 {@code
   * {order_no}} 而 3.4.2.2 还认 {@code {有空格}}， 那么「这段话里的变量到底有哪些」在两个页面上会给出不同答案。 所以这里刻意用宽松解析，与 V21
   * 保持同一口径（返回类型不同只因本模块要做集合差）。
   */
  public static Set<String> placeholdersOf(String body) {
    Set<String> out = new TreeSet<>();
    if (body == null) return out;
    int i = 0;
    while (i < body.length()) {
      int open = body.indexOf('{', i);
      if (open < 0) break;
      int close = body.indexOf('}', open + 1);
      if (close < 0) break;
      String name = body.substring(open + 1, close).trim();
      if (!name.isEmpty()) out.add(name);
      i = close + 1;
    }
    return out;
  }

  /**
   * R1：译文的占位符集合必须与源语言完全一致。返回可读原因，一致则返回 null。
   *
   * <p>两个方向都查并**分别报出**：只报「不一致」的话，运营不知道自己漏的还是多的， 而这两种的修法不同（漏 = 译文没抄全；多 = 译文里有源语言没有的占位符，永远填不上）。
   */
  public static String placeholderProblem(String sourceBody, String translationBody) {
    Set<String> source = placeholdersOf(sourceBody);
    Set<String> translation = placeholdersOf(translationBody);
    Set<String> missing = new TreeSet<>(source);
    missing.removeAll(translation);
    Set<String> extra = new TreeSet<>(translation);
    extra.removeAll(source);
    if (missing.isEmpty() && extra.isEmpty()) return null;
    StringBuilder reason = new StringBuilder("占位符与源语言正文不一致");
    if (!missing.isEmpty()) reason.append("：源语言多出 ").append(missing).append("（译文漏抄，补上即可）");
    if (!extra.isEmpty()) reason.append("；译文多出 ").append(extra).append("（源语言没有这个占位符，永远填不上，删掉）");
    return reason.toString();
  }

  // ================================================================ R2~R4 命中率

  /**
   * R2/R3：命中率。
   *
   * @param enabledEntries 启用的话术总条数（分母，R2：只算启用的）
   * @param hitEntries 命中过的话术条数（分子；调用方须已按 R3 过滤 outcome）
   * @return 0~1 的比率，保留 4 位小数；{@code enabledEntries} 为 0 时返回 {@code null}（R4）
   */
  public static BigDecimal hitRate(long enabledEntries, long hitEntries) {
    if (enabledEntries <= 0) return null;
    return BigDecimal.valueOf(hitEntries)
        .divide(BigDecimal.valueOf(enabledEntries), 4, RoundingMode.HALF_UP);
  }

  /** R3：这次引用算不算「命中这条话术」。只有这两种结果算。 */
  public static boolean countsAsHit(String outcome) {
    return "RESOLVED".equals(outcome) || "PARTIAL".equals(outcome);
  }

  /** R3 的负信号：这两种结果在页面上要单独盯（最该被发现的是 REJECTED）。 */
  public static boolean isProblemOutcome(String outcome) {
    return "REJECTED".equals(outcome) || "ABANDONED".equals(outcome);
  }

  // ================================================================ 规则版本校验

  public static String policyKeyProblem(String policyKey) {
    String value = normalizeUpper(policyKey);
    if (value.isEmpty()) return "政策编码不能为空";
    if (value.length() > 48) return "政策编码最长 48 个字符（现在 " + value.length() + " 个）";
    if (!value.matches("[A-Z0-9_]+")) return "政策编码只能用大写字母、数字与下划线（如 AMAZON_RETURN_POLICY）";
    return null;
  }

  public static String versionLabelProblem(String versionLabel) {
    String value = normalize(versionLabel);
    if (value.isEmpty()) return "版本标签不能为空";
    if (value.length() > 32) return "版本标签最长 32 个字符（现在 " + value.length() + " 个）";
    return null;
  }

  public static String platformProblem(String platform) {
    String value = normalizeUpper(platform);
    if (value.isEmpty()) return "平台不能为空";
    if (!PLATFORMS.contains(value)) return "平台只能是 " + String.join(" / ", PLATFORMS);
    return null;
  }

  /** 生效区间：左闭右开 [from, to)，与 V23/V25 同一口径。终点必须严格晚于起点。 */
  public static String intervalProblem(OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {
    if (effectiveFrom == null) return "生效起点不能为空：没有生效起点的政策版本无法回答「这条话术当时依据的是哪版」";
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))
      return "生效终点必须晚于起点（区间是左闭右开的 [起点, 终点)，终点等于起点意味着这一版从未生效过）";
    return null;
  }

  /**
   * 改版时变更摘要必填。
   *
   * <p>接替一版之后客服要靠这句话知道该复核什么。没有摘要的话，待复核任务只有 「政策改版了，去看看」这一句话，等于没派工。
   */
  public static String changeSummaryProblem(String changeSummary, boolean superseding) {
    if (!superseding) return null;
    String value = normalize(changeSummary);
    if (value.isEmpty()) return "标记为「已被接替」时必须写变更摘要：客服看到待复核任务时要知道这一版改了什么";
    if (value.length() > MAX_CHANGE_SUMMARY)
      return "变更摘要最长 " + MAX_CHANGE_SUMMARY + " 个字符（现在 " + value.length() + " 个）";
    return null;
  }

  public static String abbreviate(String value) {
    String text = value == null ? "" : value;
    return text.length() <= 40 ? text : text.substring(0, 40) + "…";
  }
}
