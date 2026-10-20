package com.starcon.channel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 4.4.1.1 链接采集 的 URL 规范化与白名单匹配。
 *
 * <p>蓝图输入句「商品 URL（**支持平台白名单**）」，4.4.1.2 原文「抓取必须有频率限制与去重
 * （同 URL 不重复抓、已存在的商品跳过）」。本类把这两件事的**判定**部分独立出来，
 * 不碰数据库、不发 HTTP —— 判定错了会连累"抓到别人家的页面"或"同一页反复抓"，
 * 所以单列一类、纯函数、可单测。
 *
 * <p>🔴 <b>规范化 = 去重键</b>：同一个商品页常带着 {@code ?utm_source=…&spm=…&from=…}
 * 之类的跟踪参数被分享出去。不规范化就当新页面 ⇒ 同一件商品被反复抓、外部站点被打爆、
 * 采集箱里塞满重复草稿。规范化做三件事：
 *
 * <ol>
 *   <li><b>归一 host</b>：小写、去端口、{@code www.} 前缀只算同一个 host 的两种写法
 *       （但**不**把任意子域当等价 —— {@code a.1688.com} 与 {@code b.1688.com} 是两个 host）。
 *   <li><b>去跟踪参数</b>：{@code utm_*} / {@code spm} / {@code from} / {@code ref} / {@code _ga}
 *       / {@code gclid} / {@code fbclid} 等**不参与商品身份**，一律剔除。
 *   <li><b>排序剩余查询参数</b>：{@code ?b=2&a=1} 与 {@code ?a=1&b=2} 是同一个页面。
 * </ol>
 *
 * <p>🔴 <b>路径大小写保持原样</b>：路径在不同站点上的大小写语义不一致（多数不敏感，少数敏感），
 * 擅自小写会把两个不同的商品页合并成一个 —— 那是**错杀**（真商品被当成重复），
 * 比多抓一次严重得多。查询参数名同理：只排序，不改大小写。
 */
public final class ListingCollectUrl {

  /**
   * 不参与商品身份的跟踪参数（小写比较）。
   *
   * <p>这一组是**行业通用**的跟踪参数名（GA / 百度 / 阿里妈妈 / 拼多多 / 通用 utm），
   * 不是某个平台的私有约定 —— 平台私有参数随时会变，写死它们等于写一个假实现。
   */
  static final Set<String> TRACKING_PARAMS =
      Set.of(
          "utm_source",
          "utm_medium",
          "utm_campaign",
          "utm_term",
          "utm_content",
          "utm_id",
          "utm_name",
          "spm",
          "scm",
          "from",
          "ref",
          "referrer",
          "source",
          "_ga",
          "_gl",
          "gclid",
          "gclsrc",
          "fbclid",
          "msclkid",
          "yclid",
          "ttclid",
          "dclid",
          "ali_trackid",
          "trackid",
          "share_source",
          "share_token",
          "shareId",
          "share_id",
          "spm_id",
          "clickid",
          "vdSource");

  private ListingCollectUrl() {}

  /**
   * 规范化后的 URL。
   *
   * @param normalized 规范化 URL（去重键，落库）
   * @param host 归一后的 host（小写、去端口、含 {@code www.} —— 见 {@link #normalizeHost}）
   * @param scheme scheme（小写）
   */
  public record Normalized(String normalized, String host, String scheme) {}

  /**
   * 规范化一个 URL。
   *
   * @throws IllegalArgumentException URL 非法、scheme 不是 http(s)、或没有 host
   */
  public static Normalized normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("商品链接不能为空");
    }
    String trimmed = raw.trim();
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("商品链接格式不正确：" + trimmed);
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("商品链接必须以 http:// 或 https:// 开头：" + trimmed);
    }
    scheme = scheme.toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("只支持 http / https 链接，收到：" + scheme);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("商品链接缺少主机名：" + trimmed);
    }
    String normalizedHost = normalizeHost(host);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    // 🔴 路径末尾的斜杠等价（/item/123 与 /item/123/ 是同一个页面）
    if (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String query = normalizeQuery(uri.getRawQuery());

    StringBuilder out = new StringBuilder(scheme).append("://").append(normalizedHost);
    int port = uri.getPort();
    boolean defaultPort =
        port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    if (!defaultPort) {
      out.append(':').append(port);
    }
    out.append(path);
    if (!query.isEmpty()) {
      out.append('?').append(query);
    }
    // 🔴 fragment（#锚点）不参与页面身份 —— 同一个商品页的锚点跳转不是两个商品
    return new Normalized(out.toString(), normalizedHost, scheme);
  }

  /**
   * 归一 host：小写、去端口。
   *
   * <p>🔴 <b>不剥 {@code www.} 前缀</b>：{@code www.1688.com} 与 {@code 1688.com} 在白名单
   * 匹配时应当**都命中**同一条白名单（白名单按"本身或其子域"匹配，{@code www.1688.com}
   * 天然是 {@code 1688.com} 的子域），但**去重键**里保留 {@code www.} —— 不同站点上
   * {@code www} 与裸域可能是两套内容（有的站点裸域是另一个站），合并它们会错杀真商品。
   */
  public static String normalizeHost(String host) {
    String value = host.toLowerCase(Locale.ROOT).trim();
    if (value.startsWith("[") && value.endsWith("]")) return value; // IPv6 字面量
    return value;
  }

  /**
   * 取 URL 的 host（去端口、小写）。
   *
   * @throws IllegalArgumentException URL 非法或没有 host
   */
  public static String hostOf(String raw) {
    return normalize(raw).host();
  }

  /** 规范化查询串：剔除跟踪参数、排序剩余参数。 */
  static String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) return "";
    String[] parts = rawQuery.split("&");
    // 用 TreeMap 排序：同名参数保留多个值（Map 不行），按 (名, 值) 排序
    List<String[]> pairs = new ArrayList<>();
    for (String part : parts) {
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      String name = eq < 0 ? part : part.substring(0, eq);
      String value = eq < 0 ? "" : part.substring(eq + 1);
      if (name.isEmpty()) continue;
      if (TRACKING_PARAMS.contains(name.toLowerCase(Locale.ROOT))) continue;
      pairs.add(new String[] {name, value});
    }
    pairs.sort((a, b) -> {
      int byName = a[0].compareTo(b[0]);
      return byName != 0 ? byName : a[1].compareTo(b[1]);
    });
    StringBuilder out = new StringBuilder();
    for (String[] pair : pairs) {
      if (out.length() > 0) out.append('&');
      out.append(pair[0]);
      if (!pair[1].isEmpty()) {
        out.append('=').append(pair[1]);
      }
    }
    return out.toString();
  }

  /**
   * 白名单匹配：{@code host} 是否命中白名单里的 {@code allowedHost}。
   *
   * <p>规则：<b>完全相等</b>，或 {@code host} 以 {@code "." + allowedHost} 结尾
   * （⇒ {@code detail.1688.com} 命中 {@code 1688.com}）。
   *
   * <p>🔴 <b>为什么必须是"点开头"的后缀</b>：朴素的 {@code endsWith} 会让
   * {@code evil-1688.com} 命中 {@code 1688.com} —— 那是一个**攻击者控制的域名**，
   * 抓它等于把请求送进别人家。带点前缀的后缀匹配只认真正的子域。
   */
  public static boolean hostMatches(String host, String allowedHost) {
    if (host == null || allowedHost == null) return false;
    String h = normalizeHost(host);
    String a = normalizeHost(allowedHost);
    if (h.equals(a)) return true;
    return h.endsWith("." + a);
  }

  /**
   * 白名单匹配：给定 URL 与一组白名单域名，返回命中的那一个。
   *
   * @return 命中的白名单域名；没命中返回 null
   */
  public static String matchAllowedHost(String host, List<String> allowedHosts) {
    if (host == null || allowedHosts == null) return null;
    // 最长的白名单域名优先命中：否则 "1688.com" 会盖住 "detail.1688.com" 这类更具体的条目
    List<String> sorted = new ArrayList<>(allowedHosts);
    sorted.sort((a, b) -> b.length() - a.length());
    for (String allowed : sorted) {
      if (hostMatches(host, allowed)) return allowed;
    }
    return null;
  }

  /** 给前端展示用的允许域名清单（去重保序）。 */
  public static Map<String, String> describeHosts(List<String> hosts) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String host : hosts) out.putIfAbsent(normalizeHost(host), host);
    return out;
  }
}
