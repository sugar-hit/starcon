package com.starcon.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 4.2.2.2 描述模板的纯规则层：段落类型词表、插图位词表、容量上限、模板与段落校验、HTML 转义。
 *
 * <p>与 {@link DescriptionRules} / {@link TitleRules} 同一形状：无状态静态方法。
 * 服务层拒绝时给出的话、前端表单校验、集成测试断言的边界都引同一份实现， 所以前端那份 {@code description-template-model.ts} 是逐条照抄这里的。
 *
 * <p><b>本类不碰数据库，也不碰渲染</b>。渲染在 {@link DescriptionTemplateRenderer}， 取值（变量绑到哪个字段、短语池取哪句）在 {@link
 * DescriptionRenderer}（3.4.2.2 复用）。 三者分开的理由与 V21 相同：把「参数合不合法」和「填出什么」混在一起，
 * 会让「缺变量必须被显式提示」这条蓝图标着要显式控制的纯函数被校验代码污染。
 */
public final class DescriptionTemplateRules {
  private DescriptionTemplateRules() {}

  // ============================================================ 词表（与 V96 的 CHECK 逐字一致）

  /**
   * 五类段落。与 V96 的 description_template_section.section_type CHECK 逐字一致。
   *
   * <p>蓝图原文「段落结构（产品介绍、使用场景、规格参数、注意事项、售后承诺）」—— 五类**显式建模**而不是一列自由文本，也不是糊成一个正文大字符串： 「规格参数这一段还没配英文」必须在
   * SQL 里一次问得出来。
   */
  public static final List<String> SECTION_TYPES =
      List.of("INTRO", "SCENE", "SPEC", "CAUTION", "SERVICE_PROMISE");

  /** 图片插入位的取图来源。与 V96 的 description_image_slot.bind_source CHECK 逐字一致。 */
  public static final List<String> IMAGE_BIND_SOURCES =
      List.of("RENDER_INPUT", "SKU_PRIMARY", "SKU_GALLERY");

  public static final List<String> RENDER_STATUSES = List.of("SUCCEEDED", "PARTIAL", "FAILED");

  public static final List<String> SECTION_STATUSES = List.of("COMPLETE", "INCOMPLETE");

  /**
   * 缺失报告的成因。
   *
   * <p>前四项与 V21 的 {@code content_render_issue.issue_kind} **逐字一致**——共用取值口径， 「brand
   * 缺了」在这两个模块里说的是同一件事；第五项 IMAGE_UNBOUND 是本模块新增的 （V21 全程纯文本，没有图片概念）。
   */
  public static final List<String> ISSUE_KINDS =
      List.of(
          "EMPTY_FACT",
          "EMPTY_PHRASE_POOL",
          "TRANSLATION_MISSING",
          "UNBOUND_PLACEHOLDER",
          "IMAGE_UNBOUND");

  private static final Map<String, String> SECTION_TYPE_LABELS =
      Map.of(
          "INTRO", "产品介绍",
          "SCENE", "使用场景",
          "SPEC", "规格参数",
          "CAUTION", "注意事项",
          "SERVICE_PROMISE", "售后承诺");

  private static final Map<String, String> BIND_SOURCE_LABELS =
      Map.of(
          "RENDER_INPUT", "渲染时由编辑器给图",
          "SKU_PRIMARY", "取该 SKU 的主图",
          "SKU_GALLERY", "取该 SKU 的附图");

  private static final Map<String, String> RENDER_STATUS_LABELS =
      Map.of(
          "SUCCEEDED", "五段全填好",
          "PARTIAL", "有段落缺变量或缺图",
          "FAILED", "未渲染出可用段落");

  private static final Map<String, String> SECTION_STATUS_LABELS =
      Map.of("COMPLETE", "已填好", "INCOMPLETE", "缺变量或缺图");

  private static final Map<String, String> ISSUE_KIND_LABELS =
      Map.of(
          "EMPTY_FACT", "商品字段为空",
          "EMPTY_PHRASE_POOL", "短语池为空",
          "TRANSLATION_MISSING", "该语言没有译文",
          "UNBOUND_PLACEHOLDER", "占位符没有绑定源",
          "IMAGE_UNBOUND", "必填插图位没有图");

  public static String sectionTypeLabel(String value) {
    return SECTION_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String bindSourceLabel(String value) {
    return BIND_SOURCE_LABELS.getOrDefault(value, value);
  }

  public static String renderStatusLabel(String value) {
    return RENDER_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String sectionStatusLabel(String value) {
    return SECTION_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String issueKindLabel(String value) {
    return ISSUE_KIND_LABELS.getOrDefault(value, value);
  }

  /** 段落类型的默认小标题（渲染成 {@code <h3>}）。空标题时用它。 */
  public static String defaultHeading(String sectionType) {
    return SECTION_TYPE_LABELS.getOrDefault(sectionType, sectionType);
  }

  /**
   * 段落类型 → 建议的短语绑定源。
   *
   * <p>只作建议不强制：规格参数段通常绑事实字段（SKU_SPEC / SKU_SIBLING_LIST）而不是短语池，
   * 强行按类型定死会让「这段落要引一句卖点」配不出来。页面上用它给出初始变量建议。
   */
  public static String suggestedPhraseSource(String sectionType) {
    return switch (MediaRules.normalizeUpper(sectionType)) {
      case "INTRO" -> "PHRASE_SELLING";
      case "SCENE" -> "PHRASE_SCENE";
      case "CAUTION" -> "PHRASE_CAUTION";
      default -> "";
    };
  }

  // ============================================================ 容量上限

  /**
   * 一个描述模板最多 8 个段落。
   *
   * <p>蓝图给的是五类，所以上限定 8 而不是 5：留出「同类再拆一段」的余地 （一个类目既要「产品介绍」又要「使用场景」，但也可能要两段注意事项：安装 / 保养）。 同类超过一段需要显式改
   * sort_order，而不是让 DB 的 UNIQUE(tenant_id,template_id,section_type) 拦下。
   */
  public static final int MAX_SECTIONS = 8;

  /** 一个段落最多 8 个图片插入位。详情页正文超过 8 张图会拖垮平台审核与首屏加载。 */
  public static final int MAX_IMAGE_SLOTS_PER_SECTION = 8;

  /** 一次渲染最多给 32 张图（八段 × 八个位）。 */
  public static final int MAX_IMAGES_PER_RENDER = 32;

  // ============================================================ 校验

  public static String templateCodeProblem(String code) {
    // 编码规则与 3.4.2.2 的 content_template 完全一致（同一套租户编码约定）。
    return DescriptionRules.templateCodeProblem(code);
  }

  public static String templateNameProblem(String name) {
    return DescriptionRules.templateNameProblem(name);
  }

  public static String localeProblem(String locale) {
    return DescriptionRules.localeProblem(locale);
  }

  /** 规范化后的语言标签。与 3.4.2.2 共用同一份实现，入库与查询必须走同一份。 */
  public static String normalizeLocale(String locale) {
    return DescriptionRules.normalizeLocale(locale);
  }

  public static String sectionTypeProblem(String sectionType) {
    String value = MediaRules.normalizeUpper(sectionType);
    if (!SECTION_TYPES.contains(value))
      return "段落类型只能是 "
          + String.join(
              " / ",
              SECTION_TYPES.stream().map(DescriptionTemplateRules::sectionTypeLabel).toList())
          + "（产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺）";
    return null;
  }

  public static String sectionsSizeProblem(int size) {
    if (size > MAX_SECTIONS) return "一个描述模板最多 " + MAX_SECTIONS + " 个段落";
    return null;
  }

  public static String imageSlotsSizeProblem(int size) {
    if (size > MAX_IMAGE_SLOTS_PER_SECTION)
      return "一个段落最多 " + MAX_IMAGE_SLOTS_PER_SECTION + " 个图片插入位";
    return null;
  }

  /**
   * 段落重复与缺口校验。
   *
   * <p>重复：同一模板里两段「产品介绍」会让渲染出两个同名 section， 页面与平台上都分不清哪段是哪段（所以 DB 上还有 {@code
   * UNIQUE(tenant_id,template_id,section_type)} 兜底）。
   *
   * <p><b>缺口不拦</b>：五类段落不是全必填。很多商品没有独立售后承诺段 （承诺写在「注意事项」里），硬要求五段齐全只会逼运营填废话。 缺口由概览的 {@code
   * missingSectionTypeCount} 提示，而不是在保存时拒绝。
   */
  public static String sectionTypesProblem(List<String> sectionTypes) {
    List<String> seen = new ArrayList<>();
    if (sectionTypes != null)
      for (String raw : sectionTypes) {
        String value = MediaRules.normalizeUpper(raw);
        if (seen.contains(value))
          return "段落类型「"
              + sectionTypeLabel(value)
              + "」重复了：同一模板里同一类只能有一段。"
              + "确实要两段同类内容，请拆成两个描述模板（平台详情页本来也只认一套骨架）";
        seen.add(value);
      }
    return null;
  }

  public static String bindSourceProblem(String bindSource) {
    String value = MediaRules.normalizeUpper(bindSource);
    if (!IMAGE_BIND_SOURCES.contains(value))
      return "取图来源只能是 "
          + String.join(" / ", IMAGE_BIND_SOURCES)
          + "（渲染时给图 / 取该 SKU 的主图 / 取该 SKU 的附图）";
    return null;
  }

  /**
   * 图片 URL 校验。
   *
   * <p><b>只允许 http/https</b>：渲染出的 HTML 是要直接交给平台上传的， {@code javascript:alert(1)} 落在 {@code <img
   * src>} 上是一条真实注入路径 （{@code src} 上 javascript: 在部分浏览器仍然会执行）。 与 {@link DescriptionTemplateRenderer}
   * 里 {@link #escapeAttribute} 的转义互为纵深。
   */
  public static String imageUrlProblem(String url) {
    String value = MediaRules.normalize(url);
    if (value.isEmpty()) return "图片地址不能为空";
    if (value.length() > 1000) return "图片地址最多 1000 个字符";
    if (!value.matches("(?i)https?://[^\\s\"'<>]+"))
      return "图片地址必须以 http:// 或 https:// 开头：" + "渲染出的 HTML 是直接交给平台上传的，javascript: 之类的协议会变成一条注入路径";
    return null;
  }

  public static String imageAltProblem(String alt) {
    String value = alt == null ? "" : alt.trim();
    if (value.length() > 500) return "图片替代文本最多 500 个字符";
    return null;
  }

  public static String imagesSizeProblem(int size) {
    if (size > MAX_IMAGES_PER_RENDER) return "一次渲染最多给 " + MAX_IMAGES_PER_RENDER + " 张图";
    return null;
  }

  /**
   * 渲染提交的校验（locales 与 skuId 之外的可读理由）。
   *
   * <p>缺译文**不**在这里拒：那与 3.4.2.2 的批量填充不同——那边一次填几十个 SKU， 缺译文会让整批报废所以要提交前拦；这边渲染的是一个 SKU， 缺一段译文按「该段落报
   * TRANSLATION_MISSING 并降级 INCOMPLETE」处理更有用 （运营能立刻看到「就差英文」而不是提交失败）。
   */
  public static String renderStatusProblem(String status) {
    String value = MediaRules.normalizeUpper(status);
    if (!RENDER_STATUSES.contains(value))
      return "渲染状态只能是 " + String.join(" / ", RENDER_STATUSES) + "（已填好 / 有段落缺变量或缺图 / 未渲染出可用段落）";
    return null;
  }

  // ============================================================ HTML 转义

  /**
   * 正文转义。
   *
   * <p>段落正文里除了变量占位符，允许运营写**行内富文本**（蓝图要求「富文本描述模板」）： {@code <b> <em> <strong> <ul> <li> <br>
   * <p>}。这些标签原样保留， 其余标签（{@code <script>}、{@code <iframe>}、{@code onload=} 之类）一律转义成文本。
   *
   * <p>白名单而不是「全部转义」：全部转义等于只支持纯文本，与蓝图「富文本」矛盾； 全部放行等于把 XSS 交给运营。两头都不能要，所以显式列白名单。
   */
  private static final List<String> INLINE_TAGS =
      List.of("b", "strong", "em", "i", "u", "br", "ul", "ol", "li", "p", "span");

  /** 转义成纯文本（去掉所有标签语义）后的可见文字，用于关键词与标题提取。 */
  public static String stripTags(String html) {
    String text = html == null ? "" : html;
    return text.replaceAll("(?s)<[^>]*>", " ");
  }

  /**
   * HTML 转义：白名单标签原样保留，其余全部转义。
   *
   * <p>变量绑定值（品牌名、SKU 规格、短语文本）会先经过这里再拼进 HTML—— 一个叫 {@code <img onerror=...>} 的品牌名不该变成一个标签。
   * 短语文本尤其危险：它是租户可编辑的长文本。
   */
  public static String escapeHtml(String raw) {
    String value = raw == null ? "" : raw;
    StringBuilder out = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c != '<') {
        out.append(c);
        i++;
        continue;
      }
      int close = value.indexOf('>', i);
      if (close < 0) {
        // 半个标签：连 '<' 一起转义，绝不放行未闭合的标签。
        out.append("&lt;").append(value.substring(i + 1));
        break;
      }
      String tag = value.substring(i, close + 1);
      if (isAllowedTag(tag)) out.append(tag);
      else out.append(escapeText(tag));
      i = close + 1;
    }
    return out.toString();
  }

  private static boolean isAllowedTag(String tag) {
    String inner = tag.substring(1, tag.length() - 1).trim();
    if (inner.isEmpty()) return false;
    // 只放行开标签与自闭合标签；闭合标签的 / 前缀剥掉后同样判定。
    String body = inner.startsWith("/") ? inner.substring(1) : inner;
    int space = indexOfWhitespace(body);
    String name = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
    if (!INLINE_TAGS.contains(name)) return false;
    // 带属性的标签一律不放行：on* 事件、style、src、href 全在属性里。
    // <br/> 与 </b> 这类无属性形式由上面 INLINE_TAGS 的名字匹配放行。
    return space < 0;
  }

  private static int indexOfWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return i;
    }
    return -1;
  }

  /** 文本转义（用于正文里的非白名单片段与属性值）。 */
  public static String escapeText(String raw) {
    String value = raw == null ? "" : raw;
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /** 属性值转义（src / alt 里任何引号与尖括号都要切断，否则能逃出属性）。 */
  public static String escapeAttribute(String raw) {
    String value = raw == null ? "" : raw;
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  // ============================================================ 词表选项（前端下拉的数据源）

  public static List<Option> sectionTypeOptions() {
    List<Option> out = new ArrayList<>();
    for (String type : SECTION_TYPES) out.add(new Option(type, sectionTypeLabel(type)));
    return out;
  }

  public static List<Option> imageBindSourceOptions() {
    List<Option> out = new ArrayList<>();
    for (String source : IMAGE_BIND_SOURCES) out.add(new Option(source, bindSourceLabel(source)));
    return out;
  }

  public static List<Option> renderStatusOptions() {
    List<Option> out = new ArrayList<>();
    for (String status : RENDER_STATUSES) out.add(new Option(status, renderStatusLabel(status)));
    return out;
  }

  public static List<Option> issueKindOptions() {
    List<Option> out = new ArrayList<>();
    for (String kind : ISSUE_KINDS) out.add(new Option(kind, issueKindLabel(kind)));
    return out;
  }

  /** 五类段落各自的建议短语绑定源，页面配变量时用来给初值。 */
  public static Map<String, String> defaultPhrasesBySectionType() {
    Map<String, String> out = new LinkedHashMap<>();
    for (String type : SECTION_TYPES) out.put(type, suggestedPhraseSource(type));
    return out;
  }

  /** 词表选项行（与 {@link SupplierQuoteRules.Option} 同一形状）。 */
  public record Option(String code, String label) {}
}
