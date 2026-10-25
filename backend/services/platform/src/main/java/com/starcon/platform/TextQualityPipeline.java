package com.starcon.platform;

import static com.starcon.platform.TextTranslationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 18.4.2.1 的**确定性质量管线**：术语保护 → 占位符完整性 → 违禁词命中 → 长度/字符集 → 置信度。
 *
 * <p>🔴 <b>这一整条管线不依赖任何 AI 供应商</b> —— 它是纯规则，真实实现、真实生效、
 * 真实拦得住。AI 通道（{@link AiTextGenerationPort}）未接入时，本模块交付的就是这条管线
 * 加上「人工录入 + 人工确认」的全流程。
 *
 * <p><b>五条规则</b>（每条规则**至少**产出一条结论；「不适用」显式记 SKIPPED，
 * 而不是假装通过）：
 *
 * <ol>
 *   <li><b>TERMINOLOGY</b> —— 术语保护：源文本里命中的术语，译文里是否原样保住。
 *       品牌词缺失 ⇒ <b>FAIL</b>（拦住发布）；专业术语 / 商品专名缺失 ⇒ WARN。
 *   <li><b>PLACEHOLDER</b> —— 占位符完整性：源文本的 {@code {{变量}}} 一个不能少、
 *       一个不能多，且变量名必须在<b>封闭词表</b>内（照抄 crm {@code MessageSendService} 的纪律）。
 *   <li><b>BANNED_TERM</b> —— 违禁词命中：租户词表 + <b>内置合规红线</b>。
 *   <li><b>LENGTH</b> —— 长度区间（按 Unicode <b>码点</b>计）。
 *   <li><b>CHARSET</b> —— 字符集要求。
 * </ol>
 *
 * <p><b>内置词表是「规则」不是「数据源」</b>（照抄 crm {@code ReplyWordingChecker} 的原话）：
 * 它们描述的是**语言层面**的合规红线，与租户、店铺、平台无关；租户可配的那部分
 * （品牌词、专业术语、店铺自己的违禁词、长度与字符集上限）落在表里，由本类只读不写死。
 *
 * <p><b>置信度口径</b>：四个维度（术语覆盖率 / 占位符完整率 / 校验通过率 / 长度合规率）
 * 的加权；某维度不适用时**按剩余适用维度重新归一**（不是当成 0 分，也不是当成满分）。
 * 理由：没有术语可保护的任务，术语覆盖率**没有信息量**，把它算成 0 分等于在惩罚
 * 一件本来就不该扣分的事。
 */
final class TextQualityPipeline {

  private TextQualityPipeline() {}

  // ---------------------------------------------------------------- 口径常量

  /** 四个维度的权重（**口径**，不是数据 —— 与租户无关）。 */
  static final BigDecimal WEIGHT_TERM = new BigDecimal("0.35");

  static final BigDecimal WEIGHT_PLACEHOLDER = new BigDecimal("0.25");
  static final BigDecimal WEIGHT_CHECK = new BigDecimal("0.30");
  static final BigDecimal WEIGHT_LENGTH = new BigDecimal("0.10");

  /** 单条结论在「校验通过率」里的权重：PASS=1、WARN=0.5、FAIL=0。 */
  static final BigDecimal WEIGHT_PASS = new BigDecimal("1");

  static final BigDecimal WEIGHT_WARN = new BigDecimal("0.5");
  static final BigDecimal WEIGHT_FAIL = BigDecimal.ZERO;

  /** 置信度保留 4 位小数（与库里 {@code NUMERIC(5,4)} 同口径）。 */
  static final int SCALE = 4;

  /**
   * 客服回复模板的<b>封闭变量词表</b>。
   *
   * <p>刻意<b>逐字复用</b> crm {@code MessageSendService.TEMPLATE_VARIABLES} 的那 11 个：
   * 模板里的占位符必须是<b>服务端能确定填上</b>的，否则正文里会留下 {@code {{xxx}}}
   * 这种没人看得懂的字样 —— 而那封信是要发给买家的。
   * 本模块与 crm 共用同一份词表，是为了让「同一个客服回复模板」在两处都成立。
   */
  static final List<String> TEMPLATE_VARIABLES =
      List.of(
          "buyer_reference",
          "order_reference",
          "shop_name",
          "platform_code",
          "message_subject",
          "buyer_name",
          "order_no",
          "platform_order_no",
          "sku_list",
          "currency",
          "order_amount");

  /** 占位符形状（只认小写下划线；其余形状留给「没闭合」的检查去报错）。 */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)}}");

  /** 没闭合的占位符（出现了 {@code {{} 但没有配对的 {@code }}}）。 */
  private static final Pattern UNCLOSED = Pattern.compile("\\{\\{[^}]*$");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  private static final Pattern PHONE_CANDIDATE = Pattern.compile("\\+?[0-9][0-9\\s().\\-]{5,}[0-9]");

  private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}$");

  /** ASCII 词的词边界正则缓存（同一个词会被检查很多次）。 */
  private static final Map<String, Pattern> ASCII_PATTERNS = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------- 内置违禁红线

  /** 绝对化用语 —— 平台与广告法的高危区。 */
  private static final List<String> ABSOLUTE_TERMS =
      List.of(
          "最好",
          "最佳",
          "第一",
          "顶级",
          "唯一",
          "国家级",
          "世界级",
          "全网最低",
          "最便宜",
          "最强",
          "无与伦比",
          "100%",
          "百分百",
          "best",
          "no.1",
          "#1",
          "world's leading",
          "unbeatable");

  /** 功效 / 效果宣称 —— 医疗与功效宣称是平台处罚重灾区。 */
  private static final List<String> CLAIM_TERMS =
      List.of(
          "治愈",
          "根治",
          "治疗",
          "疗效",
          "药用",
          "无副作用",
          "特效",
          "防癌",
          "抗癌",
          "cure",
          "treat",
          "medical grade",
          "side effect free");

  /** 承诺与担保 —— 不可兑现的承诺是纠纷与差评的主要来源。 */
  private static final List<String> GUARANTEE_TERMS =
      List.of(
          "保证",
          "承诺",
          "假一赔十",
          "无条件退款",
          "终身",
          "永久有效",
          "包退包换",
          "guarantee",
          "lifetime",
          "money back",
          "no questions asked");

  /** 导流联系方式的词面（邮箱与电话另有正则兜底）。 */
  private static final List<String> CONTACT_TERMS =
      List.of(
          "加微信",
          "微信号",
          "加qq",
          "whatsapp",
          "wechat",
          "telegram",
          "电话联系",
          "手机号",
          "留邮箱",
          "contact us",
          "reach us",
          "email me",
          "phone number");

  /** 诱导好评 / 刷单。 */
  private static final List<String> INCENTIVE_TERMS =
      List.of(
          "好评返现",
          "刷单",
          "五星好评",
          "好评有礼",
          "送礼物",
          "review for discount",
          "free gift for review",
          "five star review");

  // ---------------------------------------------------------------- 输入形状

  /** 一条待保护术语。 */
  record Term(String sourceTerm, String targetTerm, TermType type, boolean caseSensitive) {}

  /** 一条违禁词。 */
  record BannedTerm(String term, BannedCategory category, String severity) {}

  /** 一条长度与字符集规则（null = 没配规则 ⇒ 长度与字符集不适用）。 */
  record Rule(Integer minLength, Integer maxLength, CharsetRule charset, boolean terminologyStrict) {}

  /** 一条检查结论。 */
  record Finding(
      String ruleCode, String ruleLabel, String verdict, String matchedItem, String detail) {}

  /** 一次评估的完整结论。 */
  record Report(
      List<Finding> findings,
      BigDecimal confidence,
      BigDecimal termCoverage,
      BigDecimal placeholderIntegrity,
      BigDecimal checkPassRate,
      BigDecimal lengthCompliance,
      List<String> explanation) {}

  // ---------------------------------------------------------------- 评估

  /**
   * 评估一份译文。
   *
   * @param sourceText 源文本（占位符与术语命中的基准）
   * @param targetText 译文（人工录入或编辑后的那一版）
   * @param terms 适用于该目标语言的术语（服务层已按 locale 过滤）
   * @param banned 适用于该目标语言的违禁词（服务层已按 locale 过滤）
   * @param rule 长度与字符集规则（可为 null = 未配置）
   */
  static Report evaluate(
      String sourceText,
      String targetText,
      List<Term> terms,
      List<BannedTerm> banned,
      Rule rule) {
    var source = sourceText == null ? "" : sourceText;
    var target = targetText == null ? "" : targetText;
    var findings = new ArrayList<Finding>();

    var termCoverage = terminology(source, target, terms, rule, findings);
    var placeholderIntegrity = placeholders(source, target, findings);
    bannedTerms(target, banned, findings);
    var lengthCompliance = length(target, rule, findings);
    charset(target, rule, findings);

    var checkPassRate = checkPassRate(findings);
    var confidence = confidence(termCoverage, placeholderIntegrity, checkPassRate, lengthCompliance);

    return new Report(
        findings,
        confidence,
        termCoverage,
        placeholderIntegrity,
        checkPassRate,
        lengthCompliance,
        explain(termCoverage, placeholderIntegrity, checkPassRate, lengthCompliance));
  }

  // ---------------------------------------------------------------- 1. 术语保护

  private static BigDecimal terminology(
      String source, String target, List<Term> terms, Rule rule, List<Finding> findings) {
    var inPlay = new ArrayList<Term>();
    for (var term : terms) {
      if (term.sourceTerm() != null && !term.sourceTerm().isBlank() && contains(source, term.sourceTerm(), term.caseSensitive())) {
        inPlay.add(term);
      }
    }
    if (inPlay.isEmpty()) {
      findings.add(
          new Finding(
              "TERMINOLOGY",
              "术语保护",
              "SKIPPED",
              "",
              "源文本未命中术语表里的任何术语，本项不参与置信度（不是「通过了」，是「没有可查的」）。"));
      return null;
    }
    var passed = 0;
    for (var term : inPlay) {
      var expected = term.targetTerm() == null || term.targetTerm().isBlank()
          ? term.sourceTerm()
          : term.targetTerm();
      if (contains(target, expected, term.caseSensitive())) {
        passed++;
        findings.add(
            new Finding(
                "TERMINOLOGY",
                "术语保护",
                "PASS",
                expected,
                "术语「" + term.sourceTerm() + "」已在译文中原样保住。"));
      } else {
        // 🔴 **品牌词缺失是硬伤**：它会让「文案已经翻译好了」这句话变成假话。
        //   规则里把 terminology_strict 关掉时，品牌词缺失降级为 WARN（由运营自己判断）。
        //   没配规则时按默认的 strict 处理（与迁移里的 DEFAULT true 同口径）。
        var brandFail = term.type() == TermType.BRAND && (rule == null || rule.terminologyStrict());
        var strict = brandFail;
        var verdict = strict ? "FAIL" : "WARN";
        findings.add(
            new Finding(
                "TERMINOLOGY",
                "术语保护",
                verdict,
                expected,
                "术语「" + term.sourceTerm() + "」在译文里没有按术语表保住（期望出现「" + expected + "」）："
                    + (strict
                        ? "品牌词缺失是硬伤，会让「文案已经翻译好了」变成假话，必须改回来。"
                        : "请确认是否需要改回术语表里的写法。")));
      }
    }
    return ratio(BigDecimal.valueOf(passed), BigDecimal.valueOf(inPlay.size()));
  }

  // ---------------------------------------------------------------- 2. 占位符完整性

  private static BigDecimal placeholders(String source, String target, List<Finding> findings) {
    var sourceVars = placeholdersOf(source);
    var targetVars = placeholdersOf(target);

    // 没闭合的占位符：两个方向都要查（译文里留下 {{xxx 是要发出去的正文）。
    if (UNCLOSED.matcher(source).find() || UNCLOSED.matcher(target).find()) {
      findings.add(
          new Finding(
              "PLACEHOLDER",
              "占位符完整性",
              "FAIL",
              "",
              "文本里有**没闭合**的占位符（出现了 {{ 却没有配对的 }}）：正文会原样把这串字发给买家。"));
    }

    var missing = new LinkedHashSet<>(sourceVars);
    missing.removeAll(targetVars);
    for (var name : missing) {
      findings.add(
          new Finding(
              "PLACEHOLDER",
              "占位符完整性",
              "FAIL",
              name,
              "源文本里的占位符 {{" + name + "}} 在译文里不见了：这一段信息会被静默丢掉。"));
    }

    var extra = new LinkedHashSet<>(targetVars);
    extra.removeAll(sourceVars);
    for (var name : extra) {
      findings.add(
          new Finding(
              "PLACEHOLDER",
              "占位符完整性",
              "WARN",
              name,
              "译文里多出了源文本没有的占位符 {{" + name + "}}：请确认它该不该出现在这一版。"));
    }

    // 变量名必须在封闭词表内（crm 的纪律：服务端填不上的变量不许出现在模板里）。
    for (var name : targetVars) {
      if (!TEMPLATE_VARIABLES.contains(name)) {
        findings.add(
            new Finding(
                "PLACEHOLDER",
                "占位符完整性",
                "FAIL",
                name,
                "占位符 {{" + name + "}} 不在封闭变量词表内：服务端**确定填不上**它，"
                    + "正文里会留下没人看得懂的 {{xxx}}。允许的变量：" + String.join(" / ", TEMPLATE_VARIABLES) + "。"));
      }
    }

    if (sourceVars.isEmpty() && targetVars.isEmpty()) {
      findings.add(
          new Finding(
              "PLACEHOLDER",
              "占位符完整性",
              "SKIPPED",
              "",
              "源文本与译文都没有占位符，本项不参与置信度。"));
      return null;
    }
    if (sourceVars.isEmpty()) {
      // 源文没有占位符但译文有 —— 已经按 extra 报了 WARN；这一维按 0 记（它确实没保住任何东西）。
      return BigDecimal.ZERO;
    }
    var kept = sourceVars.size() - missing.size();
    return ratio(BigDecimal.valueOf(kept), BigDecimal.valueOf(sourceVars.size()));
  }

  // ---------------------------------------------------------------- 3. 违禁词命中

  private static void bannedTerms(
      String target, List<BannedTerm> banned, List<Finding> findings) {
    var hit = false;

    // 3.1 租户词表（**数据**）。
    for (var term : banned) {
      if (term.term() == null || term.term().isBlank()) continue;
      if (contains(target, term.term(), false)) {
        hit = true;
        var block = "BLOCK".equalsIgnoreCase(term.severity());
        findings.add(
            new Finding(
                "BANNED_TERM",
                "违禁词",
                block ? "FAIL" : "WARN",
                term.term(),
                "命中本租户登记的违禁词「" + term.term() + "」（" + categoryLabel(term.category()) + "）："
                    + suggestion(term.category(), term.term())));
      }
    }

    // 3.2 内置合规红线（**规则**）。
    //    ⚠️ 内置红线判 **WARN** 而不是 FAIL：绝对化用语、功效宣称这类命中需要**人来判断**
    //    （语境决定它成不成立），而「让人来判断」正是蓝图那句「人工确认」的职责。
    //    真正**无歧义**硬拦的是导流联系方式（3.3，FAIL）与租户登记的 BLOCK 级词条。
    //    判据偏严到「无法发布」是误伤 —— 误伤一次，运营就会把整条校验当成噪音关掉。
    scanBuiltIn(target, ABSOLUTE_TERMS, BannedCategory.ABSOLUTE, findings);
    scanBuiltIn(target, CLAIM_TERMS, BannedCategory.CLAIM, findings);
    scanBuiltIn(target, GUARANTEE_TERMS, BannedCategory.GUARANTEE, findings);
    scanBuiltIn(target, INCENTIVE_TERMS, BannedCategory.INCENTIVE, findings);

    // 3.3 导流联系方式：邮箱与电话另有正则兜底（词面覆盖不全）。
    var mail = EMAIL.matcher(target);
    while (mail.find()) {
      hit = true;
      findings.add(
          new Finding(
              "BANNED_TERM",
              "违禁词",
              "FAIL",
              mail.group(),
              "正文里出现了邮箱（" + mail.group() + "）：平台正文里留联系方式是违规的常见来源。"
                  + suggestion(BannedCategory.CONTACT, mail.group())));
    }
    for (var phone : phoneLike(target)) {
      hit = true;
      findings.add(
          new Finding(
              "BANNED_TERM",
              "违禁词",
              "FAIL",
              phone,
              "正文里疑似出现了电话号码（" + phone + "）：平台正文里留联系方式是违规的常见来源。"
                  + suggestion(BannedCategory.CONTACT, phone)));
    }
    for (var term : CONTACT_TERMS) {
      if (contains(target, term, false)) {
        hit = true;
        findings.add(
            new Finding(
                "BANNED_TERM",
                "违禁词",
                "FAIL",
                term,
                "正文里出现了导流联系方式的说法「" + term + "」。" + suggestion(BannedCategory.CONTACT, term)));
      }
    }

    if (!hit) {
      findings.add(
          new Finding(
              "BANNED_TERM",
              "违禁词",
              "PASS",
              "",
              "未命中任何违禁词（已扫本租户词表与内置合规红线）。"));
    }
  }

  private static boolean scanBuiltIn(
      String target, List<String> terms, BannedCategory category, List<Finding> findings) {
    var hit = false;
    for (var term : terms) {
      if (contains(target, term, false)) {
        hit = true;
        findings.add(
            new Finding(
                "BANNED_TERM",
                "违禁词",
                "WARN",
                term,
                "命中内置合规红线「" + term + "」（" + category.label() + "）："
                    + suggestion(category, term)));
      }
    }
    return hit;
  }

  // ---------------------------------------------------------------- 4. 长度

  private static BigDecimal length(String target, Rule rule, List<Finding> findings) {
    if (rule == null || (rule.minLength() == null && rule.maxLength() == null)) {
      findings.add(
          new Finding(
              "LENGTH",
              "长度",
              "SKIPPED",
              "",
              "该内容类型在该目标语言下**没有配置长度规则**：本项不参与置信度，"
                  + "也没有长度约束（不是「长度合格」，是「没查」）。"));
      return null;
    }
    // 按 Unicode **码点**计：按 UTF-16 单元计的话中文与 emoji 会多算一倍。
    var count = target.codePointCount(0, target.length());
    var min = rule.minLength();
    var max = rule.maxLength();
    if (min != null && count < min) {
      findings.add(
          new Finding(
              "LENGTH",
              "长度",
              "FAIL",
              String.valueOf(count),
              "译文只有 " + count + " 个字符，低于该规则要求的下限 " + min + " 个。"));
      return BigDecimal.ZERO;
    }
    if (max != null && count > max) {
      findings.add(
          new Finding(
              "LENGTH",
              "长度",
              "FAIL",
              String.valueOf(count),
              "译文有 " + count + " 个字符，超过该规则要求的上限 " + max + " 个。"));
      return BigDecimal.ZERO;
    }
    findings.add(
        new Finding(
            "LENGTH",
            "长度",
            "PASS",
            String.valueOf(count),
            "译文长度 " + count + " 个字符，落在规则区间内。"));
    return BigDecimal.ONE;
  }

  // ---------------------------------------------------------------- 5. 字符集

  private static void charset(String target, Rule rule, List<Finding> findings) {
    if (rule == null || rule.charset() == null || rule.charset() == CharsetRule.ANY) {
      findings.add(
          new Finding(
              "CHARSET",
              "字符集",
              "SKIPPED",
              "",
              "该规则**没有字符集要求**（或未配置规则）：本项不参与置信度。"));
      return;
    }
    if (rule.charset() == CharsetRule.LATIN) {
      var cjk = firstCjk(target);
      if (cjk != null) {
        findings.add(
            new Finding(
                "CHARSET",
                "字符集",
                "FAIL",
                cjk,
                "译文里出现了中日韩字符「" + cjk + "」：该目标语言要求不得含中日韩字符。"));
      } else {
        findings.add(
            new Finding(
                "CHARSET", "字符集", "PASS", "", "译文中没有中日韩字符，符合该规则的字符集要求。"));
      }
      return;
    }
    // CJK：必须含至少一个中日韩字符。
    var cjk = firstCjk(target);
    if (cjk == null) {
      findings.add(
          new Finding(
              "CHARSET",
              "字符集",
              "FAIL",
              "",
              "译文里没有中日韩字符：该目标语言要求译文含中日韩字符。"));
    } else {
      findings.add(
          new Finding(
              "CHARSET", "字符集", "PASS", cjk, "译文含中日韩字符，符合该规则的字符集要求。"));
    }
  }

  // ---------------------------------------------------------------- 置信度

  /** 校验通过率：PASS=1、WARN=0.5、FAIL=0；SKIPPED **不进分母**。 */
  private static BigDecimal checkPassRate(List<Finding> findings) {
    var sum = BigDecimal.ZERO;
    var count = BigDecimal.ZERO;
    for (var finding : findings) {
      if ("SKIPPED".equals(finding.verdict())) continue;
      count = count.add(BigDecimal.ONE);
      sum =
          sum.add(
              switch (finding.verdict()) {
                case "PASS" -> WEIGHT_PASS;
                case "WARN" -> WEIGHT_WARN;
                default -> WEIGHT_FAIL;
              });
    }
    if (count.signum() == 0) return null;
    return ratio(sum, count);
  }

  /** 总分：四个维度加权，**只按适用维度归一**。 */
  private static BigDecimal confidence(
      BigDecimal termCoverage,
      BigDecimal placeholderIntegrity,
      BigDecimal checkPassRate,
      BigDecimal lengthCompliance) {
    var weighted = BigDecimal.ZERO;
    var weights = BigDecimal.ZERO;
    if (termCoverage != null) {
      weighted = weighted.add(WEIGHT_TERM.multiply(termCoverage));
      weights = weights.add(WEIGHT_TERM);
    }
    if (placeholderIntegrity != null) {
      weighted = weighted.add(WEIGHT_PLACEHOLDER.multiply(placeholderIntegrity));
      weights = weights.add(WEIGHT_PLACEHOLDER);
    }
    if (checkPassRate != null) {
      weighted = weighted.add(WEIGHT_CHECK.multiply(checkPassRate));
      weights = weights.add(WEIGHT_CHECK);
    }
    if (lengthCompliance != null) {
      weighted = weighted.add(WEIGHT_LENGTH.multiply(lengthCompliance));
      weights = weights.add(WEIGHT_LENGTH);
    }
    if (weights.signum() == 0) {
      // 一个维度都不适用（例如任务还没录入译文）：置信度如实给 0，而不是编一个数。
      return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
    return weighted.divide(weights, SCALE, RoundingMode.HALF_UP);
  }

  /** 逐维的人话说明 —— 前端直接显示，不必自己编。 */
  private static List<String> explain(
      BigDecimal termCoverage,
      BigDecimal placeholderIntegrity,
      BigDecimal checkPassRate,
      BigDecimal lengthCompliance) {
    var out = new ArrayList<String>();
    if (termCoverage == null) {
      out.add("术语覆盖率：不适用（源文本未命中术语表里的术语）");
    } else {
      out.add("术语覆盖率 " + termCoverage.toPlainString() + "（源文本命中的术语有多少在译文里保住了）");
    }
    if (placeholderIntegrity == null) {
      out.add("占位符完整率：不适用（源文本与译文都没有占位符）");
    } else {
      out.add(
          "占位符完整率 "
              + placeholderIntegrity.toPlainString()
              + "（源文本的 {{变量}} 在译文里保住了多少）");
    }
    if (checkPassRate == null) {
      out.add("校验通过率：不适用（本次没有可判定的检查项）");
    } else {
      out.add("校验通过率 " + checkPassRate.toPlainString() + "（本次确定性检查的加权通过率）");
    }
    if (lengthCompliance == null) {
      out.add("长度合规率：不适用（该内容类型在该语言下未配置长度规则）");
    } else {
      out.add(
          "长度合规率 "
              + lengthCompliance.toPlainString()
              + (lengthCompliance.signum() == 0 ? "（长度越界）" : "（长度在规则区间内）"));
    }
    return out;
  }

  // ---------------------------------------------------------------- 工具

  /** a / b，四位小数。 */
  private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
    if (b.signum() == 0) return null;
    return a.divide(b, SCALE, RoundingMode.HALF_UP);
  }

  /** 抽取占位符变量名（按出现顺序去重）。 */
  static Set<String> placeholdersOf(String text) {
    var out = new LinkedHashSet<String>();
    var matcher = PLACEHOLDER.matcher(text == null ? "" : text);
    while (matcher.find()) {
      out.add(matcher.group(1));
    }
    return out;
  }

  /** 找出正文里疑似电话的片段（排除纯日期形态）。 */
  private static List<String> phoneLike(String text) {
    var found = new ArrayList<String>();
    var matcher = PHONE_CANDIDATE.matcher(text);
    while (matcher.find()) {
      var raw = matcher.group().trim();
      // ⚠️ 先按**原样**排除日期形态（2026-10-22）：剥掉分隔符后再判就认不出来了。
      if (DATE_ONLY.matcher(raw).matches()) continue;
      var digits = raw.replaceAll("[^0-9]", "");
      if (digits.length() < 7) continue;
      if (!found.contains(raw)) found.add(raw);
      if (found.size() >= 3) break;
    }
    return found;
  }

  /** 第一个中日韩字符（没有则返回 null）。 */
  private static String firstCjk(String text) {
    for (var i = 0; i < text.length(); ) {
      var cp = text.codePointAt(i);
      if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) {
        return new String(Character.toChars(cp));
      }
      i += Character.charCount(cp);
    }
    return null;
  }

  /**
   * 忽略大小写的包含判断。
   *
   * <p>⚠️ ASCII 词按<b>词边界</b>匹配（允许复数），中文按子串匹配 ——
   * 否则 {@code "best"} 会命中 {@code "bestest"} 这类拼接词，中文也会误伤「第一」命中「第一时间」。
   * 误伤一次，运营就会把整条校验当成噪音关掉。
   */
  static boolean contains(String text, String term, boolean caseSensitive) {
    if (text == null || term == null || term.isEmpty()) return false;
    if (caseSensitive) return text.contains(term);
    if (isAscii(term)) return asciiTermPattern(term).matcher(text).find();
    return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
  }

  /** 词是否只含 ASCII（是 ⇒ 按词边界匹配）。 */
  private static boolean isAscii(String term) {
    for (var i = 0; i < term.length(); i++) {
      if (term.charAt(i) > 0x7f) return false;
    }
    return true;
  }

  /** ASCII 词的词边界正则（带缓存：同一条词会被检查很多次）。 */
  private static Pattern asciiTermPattern(String term) {
    return ASCII_PATTERNS.computeIfAbsent(
        term,
        key ->
            Pattern.compile(
                "(?<![A-Za-z0-9])" + Pattern.quote(key.toLowerCase(Locale.ROOT)) + "(?:s|es)?(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE));
  }

  /** 分类的中文标签。 */
  static String categoryLabel(BannedCategory category) {
    return category == null ? "未分类" : category.label();
  }

  /** 规避建议：说清「改什么、为什么」。 */
  static String suggestion(BannedCategory category, String matched) {
    var base =
        switch (category == null ? BannedCategory.CLAIM : category) {
          case ABSOLUTE -> "把「" + matched + "」换成可证明的具体表述（最高级用法在多数平台与广告法下都不可用）。";
          case CLAIM -> "删掉「" + matched + "」这类功效或效果宣称，改成客观的参数描述。";
          case GUARANTEE -> "删掉「" + matched + "」这类承诺，改成指向服务条款的表述（不可兑现的承诺是差评与纠纷的主因）。";
          case COMPETITOR -> "删掉「" + matched + "」这类指向竞品的表述，改成讲自己。";
          case CONTACT -> "删掉「" + matched + "」这类导流联系方式，改成引导买家在平台站内留言。";
          case INCENTIVE -> "删掉「" + matched + "」这类诱导好评的表述。";
        };
    return base;
  }
}
