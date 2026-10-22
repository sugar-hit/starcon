package com.starcon.crm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 15.2.1.2 的<b>措辞校验器</b>：蓝图加粗句「模板必须符合平台措辞规范」的**执行者**。
 *
 * <p>🔴 这一句不能只落在「页面上写一句提示」上，必须**真的拦得住**：
 * 不合规的模板存不进来，不合规的回复发不出去。
 *
 * <p><b>校验规则集（全部可在 {@code crm_review_reply_policy} 里逐平台配置）</b>：
 *
 * <ol>
 *   <li><b>长度</b> —— 按 Unicode <b>码点</b>计（按 UTF-16 单元计的话中文与 emoji 会多算一倍）。
 *       区间由租户配置的 {@code minLength} / {@code maxLength} 决定。</li>
 *   <li><b>导流联系方式</b> —— 邮箱、电话、微信等。公开回复里留联系方式是平台违规的常见来源。</li>
 *   <li><b>外链</b> —— http(s):// 或 www. 开头。</li>
 *   <li><b>促销措辞</b> —— 「下单」「折扣」「包邮」「coupon」等。评价回复是**公开**的，
 *       在里面推销等于当着所有人的面打广告。</li>
 *   <li><b>租户自定义禁用词</b> —— 与内置禁用词<b>叠加</b>，不互相覆盖。</li>
 *   <li><b>质疑买家</b> —— 「你错了」「你在撒谎」「you are wrong」等。
 *       🔴 这条<b>不可关闭</b>：质疑买家是平台与法律风险最高的一类措辞，
 *       与「多卖一单」相比，代价完全不成比例。</li>
 *   <li><b>标点上限</b> —— 感叹号 / 问号超量 = 情绪化措辞。</li>
 *   <li><b>差评必致歉</b> —— 差评（1-2 星）回复里必须出现致歉表达。</li>
 * </ol>
 *
 * <p><b>⚠️ 内置词表是「规则」不是「数据源」</b>：它们描述的是<b>语言层面</b>的合规红线，
 * 与租户、店铺、平台、评价内容都无关。租户可配的那部分（长度上限、开关、禁用词）
 * 落在 {@code crm_review_reply_policy} 表里 —— 那是<b>数据</b>，本类不碰。
 *
 * <p><b>判据偏严是刻意的</b>：合规校验宁可误伤（运营改一句话就能过），
 * 也不可放过（一条违规的公开回复撤不回来，平台会记违规次数）。
 */
final class ReplyWordingChecker {

  private ReplyWordingChecker() {}

  /** 一条违规。{@code rule} 是稳定的机器可读标识，{@code detail} 是给人看的话。 */
  record Violation(String rule, String detail) {}

  /** 校验所需的上下文。 */
  record Context(
      int minLength,
      int maxLength,
      boolean allowContactInfo,
      boolean allowPromotion,
      boolean allowExternalLink,
      int maxExclamation,
      int maxQuestionMarks,
      boolean requireApology,
      List<String> bannedTerms,
      boolean negativeReview) {}

  // ---------------------------------------------------------------- 内置规则

  /** 质疑买家 / 指责买家的措辞 —— **不可关闭**。 */
  private static final List<String> MOTIVE_QUESTIONING_TERMS =
      List.of(
          "你错了",
          "你误会",
          "你在骗",
          "你在撒谎",
          "撒谎",
          "欺诈",
          "你凭什么",
          "不属实",
          "你在污蔑",
          "纯属捏造",
          "you are wrong",
          "you're wrong",
          "you misunderstood",
          "you are lying",
          "you're lying",
          "cheating",
          "not true",
          "fake review");

  /** 促销 / 推销措辞 —— 公开回复里推销是平台违规的高发区。 */
  private static final List<String> PROMOTION_TERMS =
      List.of(
          "优惠",
          "折扣",
          "促销",
          "包邮",
          "免运费",
          "优惠券",
          "限时",
          "秒杀",
          "下单",
          "加购",
          "好评返现",
          "下单即送",
          "discount",
          "coupon",
          "promo code",
          "free shipping",
          "best price",
          "cheapest",
          "limited time offer",
          "special offer",
          "on sale",
          "buy now",
          "shop now");

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

  /** 致歉表达（差评回复必须命中其一）。 */
  private static final List<String> APOLOGY_TERMS =
      List.of("抱歉", "对不起", "很遗憾", "遗憾", "不好意思", "sorry", "apologi", "regret");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** 候选电话片段：数字 + 常见分隔符。真正的判定交给 {@link #looksLikePhone}。 */
  private static final Pattern PHONE_CANDIDATE = Pattern.compile("\\+?[0-9][0-9\\s().\\-]{5,}[0-9]");

  /** 纯日期形态（2026-10-22 / 2026/10/22）—— 不是电话号码。 */
  private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}$");

  private static final Pattern EXTERNAL_LINK =
      Pattern.compile("(?i)(https?://|www\\.)");

  /** ASCII 词的词边界正则缓存（同一个词会被检查很多次）。 */
  private static final Map<String, Pattern> ASCII_PATTERNS = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------- 校验

  /**
   * 校验一条正文。
   *
   * @return 违规列表（**空列表 = 合规**）；绝不返回 null。
   */
  static List<Violation> check(String body, Context context) {
    String text = body == null ? "" : body;
    List<Violation> violations = new ArrayList<>();

    // 1) 长度：按码点计（中文 / emoji 按 UTF-16 单元算会多一倍）。
    int length = text.codePointCount(0, text.length());
    if (length < context.minLength()) {
      violations.add(
          new Violation(
              "length_too_short",
              "正文只有 "
                  + length
                  + " 个字符，低于该平台措辞规范要求的下限 "
                  + context.minLength()
                  + " 个：太短的回复在公开页面上没有说服力。"));
    }
    if (length > context.maxLength()) {
      violations.add(
          new Violation(
              "length_too_long",
              "正文有 " + length + " 个字符，超过该平台措辞规范的上限 " + context.maxLength() + " 个。"));
    }

    // 2) 导流联系方式。
    if (!context.allowContactInfo()) {
      Matcher mail = EMAIL.matcher(text);
      if (mail.find()) {
        violations.add(
            new Violation(
                "contact_info",
                "正文里出现了邮箱（"
                    + mail.group()
                    + "）：公开评价回复里留联系方式是平台违规的常见来源，平台会据此记违规。"));
      }
      for (String phone : phoneLike(text)) {
        violations.add(
            new Violation(
                "contact_info",
                "正文里疑似出现了电话号码（"
                    + phone
                    + "）：公开评价回复里留联系方式是平台违规的常见来源。"));
      }
      for (String term : CONTACT_TERMS) {
        if (containsIgnoreCase(text, term)) {
          violations.add(
              new Violation(
                  "contact_info", "正文里出现了导流联系方式的说法「" + term + "」。"));
        }
      }
    }

    // 3) 外链。
    if (!context.allowExternalLink() && EXTERNAL_LINK.matcher(text).find()) {
      violations.add(
          new Violation("external_link", "正文里出现了外链：该平台措辞规范不允许在公开回复里放链接。"));
    }

    // 4) 促销措辞。
    if (!context.allowPromotion()) {
      for (String term : PROMOTION_TERMS) {
        if (containsIgnoreCase(text, term)) {
          violations.add(
              new Violation(
                  "promotion", "正文里出现了促销措辞「" + term + "」：评价回复是公开的，在里面推销等于当着所有人的面打广告。"));
        }
      }
    }

    // 5) 租户自定义禁用词（与内置词叠加）。
    for (String term : context.bannedTerms() == null ? List.<String>of() : context.bannedTerms()) {
      String trimmed = term == null ? "" : term.trim();
      if (trimmed.isEmpty()) continue;
      if (containsIgnoreCase(text, trimmed)) {
        violations.add(
            new Violation("banned_term", "正文里出现了本租户禁用的措辞「" + trimmed + "」。"));
      }
    }

    // 6) 质疑买家 —— 不可关闭。
    for (String term : MOTIVE_QUESTIONING_TERMS) {
      if (containsIgnoreCase(text, term)) {
        violations.add(
            new Violation(
                "motive_questioning",
                "正文里出现了质疑买家的措辞「"
                    + term
                    + "」：这是平台与法律风险最高的一类措辞，平台会直接判违规，且撤不回来。"));
      }
    }

    // 7) 标点上限。
    int exclamations = count(text, '!', '！');
    if (exclamations > context.maxExclamation()) {
      violations.add(
          new Violation(
              "exclamation",
              "正文里有 " + exclamations + " 个感叹号，超过上限 " + context.maxExclamation() + " 个：过度标点会被读成情绪化措辞。"));
    }
    int questions = count(text, '?', '？');
    if (questions > context.maxQuestionMarks()) {
      violations.add(
          new Violation(
              "question_mark",
              "正文里有 " + questions + " 个问号，超过上限 " + context.maxQuestionMarks() + " 个。"));
    }

    // 8) 差评必致歉。
    if (context.negativeReview() && context.requireApology()) {
      boolean apologized = false;
      for (String term : APOLOGY_TERMS) {
        if (containsIgnoreCase(text, term)) {
          apologized = true;
          break;
        }
      }
      if (!apologized) {
        violations.add(
            new Violation(
                "apology_required",
                "这是差评（1-2 星）回复，却没有一句致歉：差评回复是转化机会，不致歉就是把唯一的转化窗口关掉了。"));
      }
    }

    return violations;
  }

  // ---------------------------------------------------------------- 小工具

  /** 找出正文里疑似电话的片段（排除纯日期形态）。 */
  private static List<String> phoneLike(String text) {
    List<String> found = new ArrayList<>();
    Matcher matcher = PHONE_CANDIDATE.matcher(text);
    while (matcher.find()) {
      String raw = matcher.group().trim();
      // ⚠️ 先按**原样**排除日期形态（2026-10-22）：剥掉分隔符后再判就认不出来了。
      if (DATE_ONLY.matcher(raw).matches()) continue;
      String digits = raw.replaceAll("[^0-9]", "");
      if (digits.length() < 7) continue;
      if (!found.contains(raw)) found.add(raw);
      if (found.size() >= 3) break;
    }
    return found;
  }

  /**
   * 忽略大小写的包含判断。
   *
   * <p>⚠️ ASCII 词按<b>词边界</b>匹配（允许复数），中文按子串匹配 ——
   * 否则 {@code "sale"} 会命中 {@code "wholesale"}，{@code "coupon"} 也会误伤
   * {@code "couponery"} 这类普通词：误伤一次，运营就会把整条校验当成噪音关掉。
   */
  private static boolean containsIgnoreCase(String text, String term) {
    if (term == null || term.isEmpty()) return false;
    if (isAscii(term)) {
      return asciiTermPattern(term).matcher(text).find();
    }
    return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
  }

  /** 词是否只含 ASCII（是 ⇒ 按词边界匹配）。 */
  private static boolean isAscii(String term) {
    for (int i = 0; i < term.length(); i++) {
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

  /** 统计若干字符出现的总次数。 */
  private static int count(String text, char... chars) {
    int total = 0;
    for (char c : chars) {
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == c) total++;
      }
    }
    return total;
  }

  /** 把违规列表拼成一句人能读的话（给 400 的 reason 用）。 */
  static String describe(List<Violation> violations) {
    if (violations == null || violations.isEmpty()) return "";
    StringBuilder sb = new StringBuilder("回复内容不符合该平台的措辞规范：");
    for (int i = 0; i < violations.size(); i++) {
      if (i > 0) sb.append('；');
      sb.append(violations.get(i).detail());
    }
    return sb.toString();
  }
}
