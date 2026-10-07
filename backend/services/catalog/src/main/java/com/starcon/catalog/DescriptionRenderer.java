package com.starcon.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 3.4.2.2 描述与卖点库的纯函数填充引擎。
 *
 * <p>蓝图硬要求：「<b>变量缺失必须在填充时报告，不能静默留下占位符。</b>」
 * 这个类就是那条要求的执行点，行为上钉死三件事：
 *
 * <ol>
 *   <li><b>每个占位符都必须被替换掉，一个都不留在正文里。</b>
 *       取不到值时正文里那个位置被<b>剥空</b>（不是留 {@code {x}} 字面量），
 *       同时产出一条 {@link Issue}。所以「缺变量」在结果里是一个可枚举、可查的行，
 *       而不是混在文本里等人眼发现。
 *   <li><b>缺变量的段落整体标成 INCOMPLETE</b>。V21 的 CHECK 与触发器从 DB 层保证
 *       「既缺变量又标成填好了」这个组合不存在。
 *   <li><b>同样的输入永远同样的输出</b>：不碰数据库、不读时钟、不掷骰子。
 *       所以「变量缺失已被报告」可以被单元测试逐条断言。
 * </ol>
 *
 * <p>与 {@link TitleGenerator} 的差别值得说清：标题库的「拒绝也是一种输出」是
 * <b>不产出</b>候选并给出原因；这里相反——<b>照样产出</b>正文（剥掉占位符），
 * 但把缺的那些变量逐个报告出来。原因是长文本的运营诉求不同：
 * 「这段描述里 brand 没填上」比「这段描述失败了」有用得多，
 * 运营看到报告就能去补模板变量或短语池，然后只重跑受影响的那批。
 */
public final class DescriptionRenderer {
  private DescriptionRenderer() {}

  /** 一个变量绑定（与 {@code content_template_variable} 一行对应）。 */
  public record VariableSpec(String name, String source, int slotNo) {}

  /** 模板结构：正文 + 变量声明。 */
  public record TemplateSpec(String contentType, String body, List<VariableSpec> variables) {}

  /** 一个 SKU 的事实（含 SPU 的品牌与分类，以及同 SPU 下的型号清单）。 */
  public record SkuFacts(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuName,
      String brand,
      String category,
      String spec,
      List<String> siblingCodes) {}

  /** 四类短语池。空池是常态（很多租户还没建卖点短语），那时对应变量会被报告为缺失。 */
  public record PhrasePools(
      List<String> selling, List<String> feature, List<String> scene, List<String> caution) {}

  /** 一个缺失变量。{@code variableName} 是正文里那个占位符的名字。 */
  public record Issue(String variableName, String issueKind, String detail) {}

  /**
   * 一个变量的填充结果：要么有值，要么是一条缺失报告。
   *
   * <p>{@code fromPhrase} 单独记一位而不是靠「值很长」去猜：
   * 短语快照要精确到「这段当时用了哪几句卖点」，而按长度猜会把一段很长的
   * 商品规格文字误当成短语写进溯源字段。
   */
  private record Binding(String value, boolean fromPhrase, Issue issue) {
    static Binding resolved(String value, boolean fromPhrase) {
      return new Binding(value, fromPhrase, null);
    }

    static Binding missing(String name, String kind, String detail) {
      return new Binding("", false, new Issue(name, kind, detail));
    }

    boolean missing() {
      return issue != null;
    }
  }

  /**
   * 一段填充结果。
   *
   * @param text 填充后的正文（缺变量的位置被剥空并已整理空白，<b>永远不含占位符</b>）
   * @param complete 是否全部变量都取到值
   * @param issues 缺失变量报告，一个变量一条
   * @param usedPhrases 实际用到的短语文本快照（改了短语库之后仍能解释「这段当时填了什么」）
   */
  public record Rendered(
      String text, int charLength, boolean complete, List<Issue> issues, List<String> usedPhrases) {}

  /**
   * 填充一段正文。
   *
   * @param ordinal 同一批次里这个 SKU 的序号（0 起）。短语池按它取下标，
   *     所以同一个 SKU 在同一批里拿到固定的短语，而不同 SKU 之间会错开——
   *     这让「一批描述」读起来有变化，又完全可复现。
   */
  public static Rendered render(TemplateSpec spec, SkuFacts sku, PhrasePools pools, int ordinal) {
    Map<String, Binding> bindings = bindVariables(spec, sku, pools, ordinal);

    // 替换：按占位符在**正文里**的出现顺序扫描，遇到 {name} 就换成绑定值（缺失则剥空）。
    // 不能遍历 bindings：bindings 按变量声明的 slot_no 排，而正文里同一个变量
    // 可能被提及多次、顺序也可能与声明顺序不同。
    String body = DescriptionRules.normalizeBody(spec.body());
    StringBuilder out = new StringBuilder(body.length());
    List<Issue> issues = new ArrayList<>();
    List<String> usedPhrases = new ArrayList<>();
    Set<String> reported = new HashSet<>();

    int i = 0;
    while (i < body.length()) {
      int open = body.indexOf('{', i);
      if (open < 0) {
        out.append(body, i, body.length());
        break;
      }
      out.append(body, i, open);
      int close = body.indexOf('}', open + 1);
      if (close < 0) {
        // 理论不可达：V21 的模板保存时 bodyProblem 已拒掉不配平的花括号，
        // 译文保存时也有同样的校验。这里仍然不把原文吐出去——
        // 宁可正文少一段，也不能留半个占位符混进刊登内容。
        break;
      }
      String name = body.substring(open + 1, close).trim();
      Binding binding = name.isEmpty() ? null : bindings.get(name);
      if (binding == null) {
        // 正文里有占位符而模板没声明绑定源：保存时 variableBindingProblem 已拒，
        // 走到这里说明模板改过而译文没跟上（译文可以引用源语言没有的占位符吗？不能，
        // 那是另一处校验）。报告它，不静默。
        if (reported.add(name))
          issues.add(
              new Issue(
                  name,
                  "UNBOUND_PLACEHOLDER",
                  "正文里的占位符 {" + name + "} 在模板变量列表里没有声明绑定源："
                      + "请补上它的绑定后重新填充"));
      } else if (binding.missing()) {
        // 缺失只报一次：同一段正文里 {brand} 出现三次也只是同一个缺失。
        if (reported.add(name)) issues.add(binding.issue());
      } else {
        out.append(binding.value());
        if (binding.fromPhrase() && !usedPhrases.contains(binding.value()))
          usedPhrases.add(binding.value());
      }
      i = close + 1;
    }

    String text = tidy(out.toString());
    boolean complete = issues.isEmpty();
    // 三重不变量。任一不成立就抛，而不是把不一致的结果落库——
    // 那正是本模块要消灭的那类静默失败：
    //   1. 声称完整就必须有正文（空文本不可能是「填好了」）；
    //   2. 结果里绝不能有占位符（V21 的 CHECK 与触发器在 DB 层也钉了同一条）；
    //   3. INCOMPLETE 必须至少有一条报告（否则「缺变量」这个状态无法解释）。
    if (complete && text.isEmpty())
      throw new IllegalStateException(
          "模板（类型 " + spec.contentType() + "）全部变量都有值，但填充结果是空文本");
    if (text.indexOf('{') >= 0)
      throw new IllegalStateException(
          "填充结果里残留了占位符，这违反 3.4.2.2 的硬要求（变量缺失必须报告，不能静默留下占位符）：" + text);
    if (!complete && issues.isEmpty())
      throw new IllegalStateException("段落被降级成缺变量但没有产出任何报告，状态无法解释");
    return new Rendered(text, DescriptionRules.charLength(text), complete, issues, usedPhrases);
  }

  /**
   * 批量填充：一个 SKU 一段，逐段收集。
   *
   * <p>不提前退出：第一个 SKU 缺变量时后面的 SKU 照样要填——
   * 否则「前 3 个 SKU 有问题」会掩盖第 47 个 SKU 也缺变量的事实，
   * 而运营恰恰要知道整批缺了什么才能一次性修完。
   */
  public static List<Rendered> renderAll(
      TemplateSpec spec, List<SkuFacts> skus, PhrasePools pools) {
    List<Rendered> out = new ArrayList<>(skus.size());
    for (int i = 0; i < skus.size(); i++) out.add(render(spec, skus.get(i), pools, i));
    return out;
  }

  // ============================================================ 变量取值

  private static Map<String, Binding> bindVariables(
      TemplateSpec spec, SkuFacts sku, PhrasePools pools, int ordinal) {
    List<VariableSpec> declared = spec.variables() == null ? List.of() : spec.variables();
    Map<String, Binding> out = new LinkedHashMap<>();
    for (VariableSpec variable : declared)
      out.put(variable.name(), bindOne(variable.name(), variable.source(), sku, pools, ordinal));
    return out;
  }

  private static Binding bindOne(
      String name, String source, SkuFacts sku, PhrasePools pools, int ordinal) {
    String normalized = MediaRules.normalizeUpper(source);
    // SKU_SIBLING_LIST 必须**先**判：它以 "SKU_" 开头，落到下面的
    // startsWith("SKU_") 分支里会被当成 SKU_SPEC 之类的普通事实字段，
    // 于是报出「商品字段为空，补上这个字段」——而 SKU 上根本没有「型号清单」这个字段，
    // 运营照着这句话去填是找不到地方的。修法完全不同，必须分开报。
    if ("SKU_SIBLING_LIST".equals(normalized)) return siblingList(name, sku);
    if (normalized.startsWith("SPU_") || normalized.startsWith("SKU_")) return fact(name, normalized, sku);
    return phrase(name, DescriptionRules.phraseTypeOfSource(normalized), pools, ordinal);
  }

  /** 事实字段取值。空值**不是**「渲染成空串就算了」，而是一条报告。 */
  private static Binding fact(String name, String source, SkuFacts sku) {
    String value =
        switch (source) {
          case "SPU_BRAND" -> sku.brand();
          case "SPU_CATEGORY" -> sku.category();
          case "SPU_NAME" -> sku.spuName();
          case "SKU_NAME" -> sku.skuName();
          case "SKU_CODE" -> sku.skuCode();
          case "SKU_SPEC" -> sku.spec();
          default -> "";
        };
    String trimmed = MediaRules.normalize(value);
    if (trimmed.isEmpty())
      return Binding.missing(
          name,
          "EMPTY_FACT",
          "商品字段「"
              + DescriptionRules.sourceLabel(source)
              + "」在这个 SKU 上是空的（"
              + sku.skuCode()
              + "）：补上这个字段，或把这个变量从模板里去掉");
    return Binding.resolved(trimmed, false);
  }

  /** 同 SPU 型号清单：使用说明里「适配型号」那一行。 */
  private static Binding siblingList(String name, SkuFacts sku) {
    if (sku.siblingCodes() == null || sku.siblingCodes().isEmpty())
      return Binding.missing(
          name,
          "EMPTY_FACT",
          "SKU "
              + sku.skuCode()
              + " 所在 SPU 下只有它自己，没有可列的同系列型号："
              + "「适配型号」那行要么换成别的变量，要么给这个 SPU 再加几个 SKU");
    return Binding.resolved(String.join(" / ", sku.siblingCodes()), false);
  }

  /**
   * 短语池取值。
   *
   * <p>按 ordinal 取下标而不是「随机一条」：同样输入必须得到同样输出，
   * 否则「变量缺失已被报告」只能靠人肉比对两次结果才能验证。
   * 池子为空时报 {@code EMPTY_PHRASE_POOL}——那是「变量压根没绑上」
   * 最常见的成因，而它的修法（去建短语）与「商品字段为空」完全不同，必须分开报。
   */
  private static Binding phrase(String name, String phraseType, PhrasePools pools, int ordinal) {
    List<String> pool =
        switch (phraseType == null ? "" : phraseType) {
          case "SELLING" -> pools.selling();
          case "FEATURE" -> pools.feature();
          case "SCENE" -> pools.scene();
          case "CAUTION" -> pools.caution();
          default -> List.of();
        };
    if (pool == null || pool.isEmpty())
      return Binding.missing(
          name,
          "EMPTY_PHRASE_POOL",
          "「"
              + DescriptionRules.sourceLabel(
                  "PHRASE_" + (phraseType == null ? "SELLING" : phraseType))
              + "」里一条启用的短语都没有，这个变量填不出值。"
              + "请先在卖点短语库里建至少一条该类型的短语");
    return Binding.resolved(pool.get(Math.floorMod(ordinal, pool.size())), true);
  }

  /**
   * 剥掉缺失占位符之后整理空白。
   *
   * <p>缺变量会在正文里留一个空洞，直接落库会出现三种机械性的脏输出：
   * 「本产品采用  材质」的双空格、「材质，」的标点前空格、
   * 以及连续两个变量都缺时的「音箱，，。」。三种都是**剥空造成的机械痕迹**，
   * 这里只做这三件事把它们收掉。
   *
   * <p>刻意<b>不</b>重排句子、不换词、不动标点结构：润色是人的活，
   * 引擎擅自「通顺化」会让运营无法预测输出，review 的时候也说不清改了什么。
   */
  private static String tidy(String raw) {
    String collapsed = raw.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
    // 标点前的空格（剥空留下的）。
    String spaced = collapsed.replaceAll(" +([，。；：、！？,.;:!?])", "$1");
    // 连续重复的同一标点（相邻两个变量都缺时留下）。
    return spaced.replaceAll("([，。；：、！？,.;:!?])\\1+", "$1").trim();
  }
}
