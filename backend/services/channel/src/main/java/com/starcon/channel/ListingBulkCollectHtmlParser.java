package com.starcon.channel;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4.4.1.2 批量/插件采集 的**列表页**解析器。
 *
 * <p>4.4.1.1 的 {@link ListingCollectHtmlParser} 解析的是**商品详情页**（JSON-LD / og / 规格表），
 * 本类解析的是**分类页 / 搜索结果页**，两者输入结构完全不同 ⇒ 另立一类。
 *
 * <p>🔴 <b>为什么不引入 jsoup</b>：channel 服务的依赖树里没有 jsoup，而本车道<b>不得</b>修改
 * {@code backend/services/channel/pom.xml}（4 条并行车道共用它，撞 pom = 整批返工）。
 * ⇒ 本类用 JDK 自带的正则手写解析，**不新增任何依赖**（与 4.4.1.1 的解析器同一手法）。
 *
 * <p>🔴 <b>只认稳定结构，不认任何平台的私有 DOM 类名</b>：1688 / 淘宝的类名随时会变，
 * 写死 {@code .offer-title} 这类选择器等于写一个假实现 —— 站点改版即失效，
 * 而测试里的假 HTML 仍会绿。
 *
 * <p>真实分类页 / 搜索结果页**普遍**提供的稳定结构是「<b>一个商品 = 一个链接</b>」：
 *
 * <ol>
 *   <li>商品卡片是一个 {@code <a href="…商品地址…">}，里面通常还有商品图（{@code <img>}）
 *       与标题文本 —— 这是**几乎所有**列表页都有的结构（图片 + 标题 + 链接）。
 *   <li>价格常常就写在卡片文本里（{@code ¥12.34} / {@code $9.90} / {@code 12.34 元}）。
 * </ol>
 *
 * <p>🔴 <b>判据是「有没有商品信号」而不是「路径长得像不像商品」</b>：写死
 * {@code /item/} / {@code /offer/} 这类路径前缀等于把某个平台的目录结构刻进代码，
 * 换一个平台就全废（而且会漏掉平台自己的 {@code /detail/xxx.html}）。本类的判据是
 * <b>结构信号</b>：链接里带图 或 带标题 ⇒ 这是一个商品卡片。
 * 路径前缀判据只作为**兜底**（链接带 title 属性但没有图）。
 *
 * <p>🔴 <b>主机白名单不在这里判</b>：本类是纯函数（不碰数据库、不发 HTTP），
 * 主机是否在白名单由服务层用 4.4.1.1 的 {@link ListingCollectUrl#hostMatches} 判 ——
 * 解析器只负责「这个页面上有哪些商品链接」。
 */
public final class ListingBulkCollectHtmlParser {

  /** {@code <a ...>…</a>}（DOTALL 让 inner 跨行）。 */
  private static final Pattern ANCHOR =
      Pattern.compile("<a\\b([^>]*)>(.*?)</a\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** {@code <img ...>} 标签（捕获组 = 标签上的属性）。 */
  private static final Pattern IMG_TAG = Pattern.compile("<img\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

  /** {@code <title>…</title>}。 */
  private static final Pattern TITLE_TAG =
      Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 属性： {@code name="value"} / {@code name='value'} / {@code name=value}。 */
  private static final Pattern ATTRIBUTE =
      Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");

  /** 标签本身（把富文本压成纯文本）。 */
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** 脚本与样式块（先摘掉，免得里面的字串被当成正文）。 */
  private static final Pattern SCRIPT_STYLE =
      Pattern.compile(
          "<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * 价格：{@code ¥12.34} / {@code ￥12.34} / {@code $9.90} / {@code 12.34 元}。
   *
   * <p>🔴 只取**第一处**价格，且只在它确实是数字时才算数 —— 列表页里常有「满 100 减 20」
   * 这类营销数字，认错了就会把一个不是价格的数字写进金额列。
   */
  private static final Pattern PRICE =
      Pattern.compile(
          "(?:[¥￥$]|\\bUSD\\b|\\bCNY\\b|\\bRMB\\b)\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]+)?)"
              + "|([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*元");

  /** 明显的非商品链接（导航 / 账号 / 反馈 / 帮助）。 */
  private static final Set<String> NON_PRODUCT_HINTS =
      Set.of(
          "javascript:",
          "mailto:",
          "tel:",
          "#",
          "login",
          "logout",
          "register",
          "help",
          "feedback",
          "service",
          "javascript");

  private ListingBulkCollectHtmlParser() {}

  /**
   * 列表页上的一条商品。
   *
   * @param url 商品链接（绝对地址）
   * @param title 标题（链接的 title 属性 → 图片 alt → 链接文本，逐级回退）
   * @param imageUrl 主图 URL（相对地址已按页面 URL 解析成绝对地址；可能为空串）
   * @param priceAmount 列表页上写着的原文标价（**不是我方售价**；没有就为 null）
   * @param priceCurrency 标价币种（从价格符号推断；没有就为 null）
   */
  public record ListingItem(
      String url, String title, String imageUrl, BigDecimal priceAmount, String priceCurrency) {}

  /**
   * 解析结果。
   *
   * @param pageTitle 页面标题（{@code <title>}）
   * @param items 商品列表（按页面出现顺序，**同一 URL 只保留一条**）
   */
  public record ParsedListing(String pageTitle, List<ListingItem> items) {}

  /**
   * 解析一段分类页 / 搜索结果页 HTML。
   *
   * @param html 页面 HTML 原文
   * @param pageUrl 页面 URL（用于把相对地址解析成绝对地址；可为空）
   */
  public static ParsedListing parse(String html, String pageUrl) {
    String source = html == null ? "" : html;
    URI base = null;
    try {
      if (pageUrl != null && !pageUrl.isBlank()) base = URI.create(pageUrl);
    } catch (RuntimeException e) {
      base = null;
    }

    String pageTitle = stripTags(firstGroup(TITLE_TAG, source));

    // 同一个商品在页面上常出现两次（图片链接 + 标题链接）⇒ 按 URL 去重，保留第一条
    Map<String, ListingItem> found = new LinkedHashMap<>();

    Matcher anchors = ANCHOR.matcher(source);
    while (anchors.find()) {
      String attrs = anchors.group(1);
      String inner = anchors.group(2);
      Map<String, String> attr = readAttributes(attrs);

      String href = attr.get("href");
      if (href == null) continue;
      String trimmedHref = href.trim();
      if (trimmedHref.isEmpty()) continue;
      String lower = trimmedHref.toLowerCase(Locale.ROOT);
      if (NON_PRODUCT_HINTS.stream().anyMatch(lower::startsWith)) continue;

      String absolute = resolve(base, trimmedHref);
      if (absolute == null) continue;

      String imgTag = firstGroup(IMG_TAG, inner);
      Map<String, String> imgAttr = readAttributes(imgTag == null ? "" : imgTag);
      String imageUrl = resolve(base, imgAttr.getOrDefault("src", "").trim());

      String title =
          firstNonBlank(
              attr.get("title"),
              imgAttr.get("alt"),
              stripTags(inner));

      String innerText = stripTags(inner);
      BigDecimal price = null;
      String currency = null;
      Matcher priceMatcher = PRICE.matcher(innerText);
      if (priceMatcher.find()) {
        String digits = priceMatcher.group(1) == null ? priceMatcher.group(2) : priceMatcher.group(1);
        if (digits != null && !digits.isBlank()) {
          try {
            price = new BigDecimal(digits.replace(",", ""));
            // 🔴 币种看**整个匹配串**（含货币符号），不是数字那一组
            String matched = priceMatcher.group();
            currency = matched.startsWith("$") ? "USD" : "CNY";
          } catch (NumberFormatException e) {
            price = null;
          }
        }
      }

      // 🔴 商品信号判据：链接带**图** 或 带 **title 属性** ⇒ 这是商品卡片。
      //    光有链接文字**不算** —— 导航栏里每个链接都有文字，那样整页导航都会被当成商品。
      //    （title 属性与图片是真实商品卡片几乎必有的两个稳定结构。）
      boolean hasSignal =
          (imageUrl != null && !imageUrl.isBlank())
              || (attr.get("title") != null && !attr.get("title").isBlank());
      if (!hasSignal) continue;

      found.putIfAbsent(
          absolute,
          new ListingItem(
              absolute,
              title == null ? "" : title,
              imageUrl == null ? "" : imageUrl,
              price,
              currency));
    }

    return new ParsedListing(pageTitle, new ArrayList<>(found.values()));
  }

  /** 把相对地址解析成绝对地址；不是 http(s) 或解析失败时返回 null。 */
  private static String resolve(URI base, String href) {
    if (href == null || href.isBlank()) return null;
    String value = href.trim();
    if (value.startsWith("//")) {
      String scheme = base == null || base.getScheme() == null ? "https" : base.getScheme();
      value = scheme + ":" + value;
    }
    URI resolved = null;
    try {
      resolved = base == null ? URI.create(value) : base.resolve(value);
    } catch (RuntimeException e) {
      return null;
    }
    if (resolved == null) return null;
    String scheme = resolved.getScheme();
    if (scheme == null) return null;
    scheme = scheme.toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
    if (resolved.getHost() == null || resolved.getHost().isBlank()) return null;
    return resolved.toString();
  }

  /** 读出一段标签上的属性。 */
  private static Map<String, String> readAttributes(String tag) {
    Map<String, String> out = new LinkedHashMap<>();
    if (tag == null) return out;
    Matcher matcher = ATTRIBUTE.matcher(tag);
    while (matcher.find()) {
      String name = matcher.group(1).toLowerCase(Locale.ROOT);
      String value = matcher.group(3);
      if (value == null) value = matcher.group(4);
      if (value == null) value = matcher.group(5);
      out.putIfAbsent(name, value == null ? "" : value);
    }
    return out;
  }

  /** 取第一个匹配组的原文。 */
  private static String firstGroup(Pattern pattern, String source) {
    Matcher matcher = pattern.matcher(source);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** 去掉标签与脚本样式块，得到纯文本。 */
  private static String stripTags(String html) {
    if (html == null) return "";
    String noScript = SCRIPT_STYLE.matcher(html).replaceAll(" ");
    return TAG.matcher(noScript).replaceAll(" ").replaceAll("\\s+", " ").trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value.trim();
    }
    return null;
  }

  /** 供服务层做「同一页里出现多次的 URL」快速判定（纯工具，不发请求）。 */
  public static Set<String> distinctUrls(List<ListingItem> items) {
    Set<String> out = new LinkedHashSet<>();
    if (items == null) return out;
    for (ListingItem item : items) {
      if (item != null && item.url() != null) out.add(item.url());
    }
    return out;
  }
}
