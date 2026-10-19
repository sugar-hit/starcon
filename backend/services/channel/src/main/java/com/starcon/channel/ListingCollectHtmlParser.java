package com.starcon.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 4.4.1.1 链接采集 的 HTML 解析器。
 *
 * <p>🔴 <b>为什么不引入 jsoup</b>：channel 服务的依赖树里没有 jsoup，而本车道<b>不得</b>修改
 * {@code backend/services/channel/pom.xml}（4 条并行车道共用它，撞 pom = 整批返工）。Spring Boot 的
 * web starter 只带来 {@code RestClient} 与 Jackson，不带 HTML 解析器。
 * ⇒ 本类用 JDK 自带的正则 + Jackson 手写解析，**不新增任何依赖**。
 *
 * <p>🔴 <b>只认稳定结构，不认任何平台的私有 DOM 类名</b>：1688 / 淘宝的类名随时会变，
 * 写死 {@code .detail-desc} 这类选择器等于写一个假实现 —— 站点改版即失效，而测试里的
 * 假 HTML 仍会绿。真实商品页普遍提供的稳定结构有三类，本类只认这三类：
 *
 * <ol>
 *   <li><b>JSON-LD</b>（{@code <script type="application/ld+json">} 里的 schema.org {@code Product}）
 *       —— 最结构化，优先级最高。
 *   <li><b>Open Graph / meta 标签</b>（{@code og:title} / {@code og:image} / {@code og:description} /
 *       {@code product:price:amount} / {@code product:price:currency} / {@code product:brand} 等）
 *       —— 社交与电商站点几乎都发。
 *   <li><b>{@code <title>} 与规格表</b>（{@code <table>} 里的 {@code <th>} / {@code <td>} 键值行）
 *       —— 兜底。
 * </ol>
 *
 * <p>解析结果是<b>有就写、没有就空</b>：抓不到的价格就是空，**不猜、不填占位默认值**。
 */
public final class ListingCollectHtmlParser {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** {@code <script type="application/ld+json"> ... </script>}（引号单双都吃）。 */
  private static final Pattern JSON_LD_BLOCK =
      Pattern.compile(
          "<script\\b[^>]*\\btype\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 任意 {@code <meta ...>} 标签。 */
  private static final Pattern META_TAG =
      Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);

  /** {@code <title> ... </title>}。 */
  private static final Pattern TITLE_TAG =
      Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** {@code <img ...>} 标签。 */
  private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);

  /** 规格表：{@code <table> ... </table>}。 */
  private static final Pattern TABLE_BLOCK =
      Pattern.compile("<table\\b[^>]*>(.*?)</table\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 表行。 */
  private static final Pattern TABLE_ROW =
      Pattern.compile("<tr\\b[^>]*>(.*?)</tr\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 表头单元格。 */
  private static final Pattern TABLE_HEADER =
      Pattern.compile("<th\\b[^>]*>(.*?)</th\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 表数据单元格。 */
  private static final Pattern TABLE_CELL =
      Pattern.compile("<td\\b[^>]*>(.*?)</td\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 属性： {@code name="value"} / {@code name='value'} / {@code name=value}。 */
  private static final Pattern ATTRIBUTE =
      Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");

  /** 标签本身（用于把富文本压成纯文本）。 */
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** 脚本与样式块（先摘掉，免得里面的字串被当成正文）。 */
  private static final Pattern SCRIPT_STYLE =
      Pattern.compile(
          "<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private ListingCollectHtmlParser() {}

  /**
   * 抓取结果（解析层）。
   *
   * @param title 标题（JSON-LD name → og:title → {@code <title>}）
   * @param description 描述（JSON-LD description → og:description → meta description）
   * @param mainImageUrl 主图 URL（相对地址已按页面 URL 解析成绝对地址）
   * @param imageUrls 附图 URL 列表（去重、保序）
   * @param specs 规格键值对（JSON-LD additionalProperty + 页面规格表）
   * @param attributes 属性键值对（品牌 / 货号 / 类目等）
   * @param priceAmount 外部页面原文标价（**不是我方售价**）
   * @param priceCurrency 标价币种
   * @param productSignal 页面是否带有**商品信号**（JSON-LD Product / og:title / og:image）。
   *     光有 {@code <title>} 不算 —— 404 页、列表页、登录页也都有 {@code <title>}，
   *     拿它们的标题当商品标题会往采集箱里塞一堆垃圾草稿。
   */
  public record ParsedProduct(
      String title,
      String description,
      String mainImageUrl,
      List<String> imageUrls,
      Map<String, String> specs,
      Map<String, String> attributes,
      BigDecimal priceAmount,
      String priceCurrency,
      boolean productSignal) {}

  /**
   * 解析一段商品页 HTML。
   *
   * @param html 页面 HTML 原文
   * @param baseUrl 页面 URL（用于把相对图片地址解析成绝对地址；可为空）
   */
  public static ParsedProduct parse(String html, String baseUrl) {
    String source = html == null ? "" : html;
    URI base = baseUrl == null ? null : URI.create(baseUrl);

    Map<String, String> meta = readMeta(source);
    JsonNode product = findJsonLdProduct(source);

    String title = firstNonBlank(
        jsonLdText(product, "name"),
        meta.get("og:title"),
        meta.get("twitter:title"),
        meta.get("title"),
        stripTags(firstGroup(TITLE_TAG, source)));

    String description = firstNonBlank(
        jsonLdText(product, "description"),
        meta.get("og:description"),
        meta.get("twitter:description"),
        meta.get("description"));

    // ---- 图片：JSON-LD 的 image 优先，其次 og:image，最后页面 <img>
    List<String> images = new ArrayList<>();
    String jsonLdMain = jsonLdImage(product);
    if (jsonLdMain != null) images.add(jsonLdMain);
    for (String extra : jsonLdImages(product)) {
      if (!images.contains(extra)) images.add(extra);
    }
    // 🔴 og:image 只在 JSON-LD 没给图时补位 —— JSON-LD 的 image 数组是**这个商品**的图，
    //    og:image 常常是站点通用的大图（banner / 店铺头图），混进来会污染附图列表。
    String ogImage = meta.get("og:image");
    if (ogImage != null && !ogImage.isBlank() && images.isEmpty()) {
      String resolved = resolve(base, ogImage);
      if (resolved != null && !resolved.isBlank()) images.add(resolved);
    }
    if (images.isEmpty()) {
      for (String src : readImageTags(source)) {
        String resolved = resolve(base, src);
        if (resolved != null && !images.contains(resolved)) images.add(resolved);
        if (images.size() >= 20) break;
      }
    }
    List<String> resolvedImages = new ArrayList<>();
    for (String image : images) {
      String resolved = resolve(base, image);
      if (resolved != null && !resolved.isBlank()) resolvedImages.add(resolved);
    }
    String mainImage = resolvedImages.isEmpty() ? "" : resolvedImages.get(0);

    // ---- 规格与属性
    Map<String, String> specs = new LinkedHashMap<>();
    readJsonLdProperties(product, specs);
    readTableSpecs(source, specs);
    Map<String, String> attributes = new LinkedHashMap<>();
    readJsonLdAttributes(product, attributes);
    putIfBlank(attributes, "品牌", jsonLdBrand(product));
    putIfBlank(attributes, "品牌", meta.get("product:brand"));
    putIfBlank(attributes, "类目", jsonLdText(product, "category"));

    // ---- 标价（外部页面原文，**不是我方售价**）
    String priceText = jsonLdPrice(product);
    if (priceText == null || priceText.isBlank()) {
      priceText = firstNonBlank(meta.get("product:price:amount"), meta.get("og:price:amount"));
    }
    String currency = jsonLdCurrency(product);
    if (currency == null || currency.isBlank()) {
      currency = firstNonBlank(meta.get("product:price:currency"), meta.get("og:price:currency"));
    }
    BigDecimal price = parseAmount(priceText);

    // 🔴 商品信号：JSON-LD Product / og:title / og:image —— 三者命中其一即认为这是商品页
    boolean productSignal =
        product != null
            || (meta.get("og:title") != null && !meta.get("og:title").isBlank())
            || (meta.get("og:image") != null && !meta.get("og:image").isBlank());

    return new ParsedProduct(
        title == null ? "" : title,
        description == null ? "" : description,
        mainImage,
        resolvedImages,
        specs,
        attributes,
        price,
        currency == null ? "" : currency,
        productSignal);
  }

  // ==================================================================
  // JSON-LD
  // ==================================================================

  /**
   * 在所有 {@code application/ld+json} 块里找 schema.org 的 {@code Product} 节点。
   *
   * <p>块内容可能是：单个对象、对象数组、或带 {@code @graph} 的对象 —— 三种都要认。
   */
  static JsonNode findJsonLdProduct(String html) {
    Matcher matcher = JSON_LD_BLOCK.matcher(html);
    while (matcher.find()) {
      String raw = matcher.group(1).trim();
      if (raw.isEmpty()) continue;
      JsonNode root;
      try {
        root = JSON.readTree(raw);
      } catch (Exception e) {
        // 一段 JSON-LD 坏掉不代表整页坏掉：换下一段（真实页面里常有半截的 LD 块）
        continue;
      }
      JsonNode found = searchProduct(root);
      if (found != null) return found;
    }
    return null;
  }

  /** 递归找 {@code @type == "Product"} 的节点。 */
  private static JsonNode searchProduct(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isArray()) {
      for (JsonNode item : node) {
        JsonNode found = searchProduct(item);
        if (found != null) return found;
      }
      return null;
    }
    if (!node.isObject()) return null;
    if (isProductType(node.get("@type"))) return node;
    JsonNode graph = node.get("@graph");
    if (graph != null) {
      JsonNode found = searchProduct(graph);
      if (found != null) return found;
    }
    // 有些站点把 LD 挂在 mainEntity / itemListElement 之类的容器里
    for (String key : List.of("mainEntity", "item", "itemListElement")) {
      JsonNode child = node.get(key);
      if (child != null) {
        JsonNode found = searchProduct(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static boolean isProductType(JsonNode type) {
    if (type == null) return false;
    if (type.isTextual()) return "Product".equalsIgnoreCase(type.asText());
    if (type.isArray()) {
      for (JsonNode item : type) {
        if (item.isTextual() && "Product".equalsIgnoreCase(item.asText())) return true;
      }
    }
    return false;
  }

  private static String jsonLdText(JsonNode product, String field) {
    if (product == null) return null;
    JsonNode node = product.get(field);
    if (node == null || node.isNull()) return null;
    if (node.isTextual()) return node.asText();
    if (node.isNumber()) return node.asText();
    return null;
  }

  /** JSON-LD 的 {@code image}：可能是字符串、字符串数组，或 {@code {"url": ...}} 对象。 */
  private static String jsonLdImage(JsonNode product) {
    List<String> all = jsonLdImages(product);
    return all.isEmpty() ? null : all.get(0);
  }

  private static List<String> jsonLdImages(JsonNode product) {
    List<String> out = new ArrayList<>();
    if (product == null) return out;
    JsonNode node = product.get("image");
    if (node == null || node.isNull()) return out;
    if (node.isTextual()) {
      out.add(node.asText());
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        String value = imageNodeValue(item);
        if (value != null && !value.isBlank()) out.add(value);
      }
    } else {
      String value = imageNodeValue(node);
      if (value != null && !value.isBlank()) out.add(value);
    }
    return out;
  }

  private static String imageNodeValue(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isTextual()) return node.asText();
    if (node.isObject()) {
      JsonNode url = node.get("url");
      if (url != null && url.isTextual()) return url.asText();
      JsonNode contentUrl = node.get("contentUrl");
      if (contentUrl != null && contentUrl.isTextual()) return contentUrl.asText();
    }
    return null;
  }

  /** JSON-LD 的 {@code offers.price}（Offer 可能是对象也可能是数组）。 */
  private static String jsonLdPrice(JsonNode product) {
    if (product == null) return null;
    JsonNode offers = product.get("offers");
    if (offers == null || offers.isNull()) return null;
    if (offers.isArray()) {
      for (JsonNode offer : offers) {
        String value = offerPrice(offer);
        if (value != null) return value;
      }
      return null;
    }
    return offerPrice(offers);
  }

  private static String offerPrice(JsonNode offer) {
    if (offer == null || !offer.isObject()) return null;
    String value = jsonLdText(offer, "price");
    if (value == null || value.isBlank()) value = jsonLdText(offer, "lowPrice");
    return value;
  }

  private static String jsonLdCurrency(JsonNode product) {
    if (product == null) return null;
    JsonNode offers = product.get("offers");
    if (offers == null || offers.isNull()) return null;
    if (offers.isArray()) {
      for (JsonNode offer : offers) {
        String value = jsonLdText(offer, "priceCurrency");
        if (value != null && !value.isBlank()) return value;
      }
      return null;
    }
    return jsonLdText(offers, "priceCurrency");
  }

  private static String jsonLdBrand(JsonNode product) {
    if (product == null) return null;
    JsonNode brand = product.get("brand");
    if (brand == null || brand.isNull()) return null;
    if (brand.isTextual()) return brand.asText();
    return jsonLdText(brand, "name");
  }

  /** JSON-LD 的 {@code additionalProperty}（规格/属性）→ 键值对。 */
  private static void readJsonLdProperties(JsonNode product, Map<String, String> target) {
    if (product == null) return;
    JsonNode props = product.get("additionalProperty");
    if (props == null || props.isNull()) return;
    List<JsonNode> list = props.isArray() ? toList(props) : List.of(props);
    for (JsonNode prop : list) {
      if (!prop.isObject()) continue;
      String name = jsonLdText(prop, "name");
      if (name == null || name.isBlank()) name = jsonLdText(prop, "propertyID");
      String value = jsonLdText(prop, "value");
      if (name == null || name.isBlank() || value == null) continue;
      putIfBlank(target, name, value);
    }
  }

  /** 品牌 / 货号 / 类目 / 条码等 → 属性。 */
  private static void readJsonLdAttributes(JsonNode product, Map<String, String> target) {
    if (product == null) return;
    putIfBlank(target, "货号", jsonLdText(product, "sku"));
    putIfBlank(target, "型号", jsonLdText(product, "mpn"));
    putIfBlank(target, "条码", firstNonBlank(jsonLdText(product, "gtin13"), jsonLdText(product, "gtin")));
    putIfBlank(target, "品牌", jsonLdBrand(product));
  }

  private static List<JsonNode> toList(JsonNode array) {
    List<JsonNode> out = new ArrayList<>();
    array.forEach(out::add);
    return out;
  }

  // ==================================================================
  // meta / 标签
  // ==================================================================

  /** 读所有 {@code <meta>} 标签的 {@code property} / {@code name} → {@code content}。 */
  static Map<String, String> readMeta(String html) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher matcher = META_TAG.matcher(html);
    while (matcher.find()) {
      Map<String, String> attributes = readAttributes(matcher.group());
      String key = attributes.get("property");
      if (key == null) key = attributes.get("name");
      if (key == null) key = attributes.get("itemprop");
      if (key == null) continue;
      String content = attributes.get("content");
      if (content == null) continue;
      String value = decodeEntities(content).trim();
      if (value.isEmpty()) continue;
      out.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
    }
    return out;
  }

  /** 读所有 {@code <img>} 标签的 {@code src} / {@code data-src}。 */
  static List<String> readImageTags(String html) {
    List<String> out = new ArrayList<>();
    Matcher matcher = IMG_TAG.matcher(html);
    while (matcher.find()) {
      Map<String, String> attributes = readAttributes(matcher.group());
      for (String key : List.of("src", "data-src", "data-original", "data-lazy-src")) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) continue;
        value = decodeEntities(value).trim();
        if (value.isEmpty() || value.startsWith("data:")) continue;
        out.add(value);
        break;
      }
    }
    return out;
  }

  /** 读页面里的规格表：{@code <th>名称</th><td>值</td>} 这类键值行。 */
  static void readTableSpecs(String html, Map<String, String> target) {
    Matcher tableMatcher = TABLE_BLOCK.matcher(html);
    while (tableMatcher.find()) {
      String table = tableMatcher.group(1);
      Matcher rowMatcher = TABLE_ROW.matcher(table);
      while (rowMatcher.find()) {
        String row = rowMatcher.group(1);
        List<String> headers = new ArrayList<>();
        Matcher headerMatcher = TABLE_HEADER.matcher(row);
        while (headerMatcher.find()) {
          headers.add(cleanText(headerMatcher.group(1)));
        }
        List<String> cells = new ArrayList<>();
        Matcher cellMatcher = TABLE_CELL.matcher(row);
        while (cellMatcher.find()) {
          cells.add(cleanText(cellMatcher.group(1)));
        }
        // 一行里既没有 th 也没有 td ⇒ 不是规格行
        if (headers.isEmpty() && cells.isEmpty()) continue;
        // 形态 A：<th>名称</th><td>值</td>
        if (!headers.isEmpty() && !cells.isEmpty()) {
          putIfBlank(target, headers.get(0), cells.get(0));
          continue;
        }
        // 形态 B：<td>名称</td><td>值</td>（两格都是 td）
        if (headers.isEmpty() && cells.size() >= 2) {
          putIfBlank(target, cells.get(0), cells.get(1));
        }
      }
    }
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  /** 解析一个 HTML 标签的属性表。 */
  static Map<String, String> readAttributes(String tag) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher matcher = ATTRIBUTE.matcher(tag);
    while (matcher.find()) {
      String name = matcher.group(1).toLowerCase(Locale.ROOT);
      String value = matcher.group(3);
      if (value == null) value = matcher.group(4);
      if (value == null) value = matcher.group(5);
      if (value == null) value = "";
      out.putIfAbsent(name, decodeEntities(value));
    }
    return out;
  }

  /** 富文本 → 纯文本：摘掉脚本样式、剥标签、解实体、压空白。 */
  static String cleanText(String html) {
    if (html == null) return "";
    String withoutBlocks = SCRIPT_STYLE.matcher(html).replaceAll(" ");
    String withoutTags = TAG.matcher(withoutBlocks).replaceAll(" ");
    return decodeEntities(withoutTags).replaceAll("\\s+", " ").trim();
  }

  /** 只剥标签（保留实体解码）。 */
  static String stripTags(String html) {
    if (html == null) return "";
    String withoutBlocks = SCRIPT_STYLE.matcher(html).replaceAll(" ");
    String withoutTags = TAG.matcher(withoutBlocks).replaceAll(" ");
    return decodeEntities(withoutTags).replaceAll("\\s+", " ").trim();
  }

  /** HTML 实体解码（命名实体 + 十进制 / 十六进制数字实体）。 */
  static String decodeEntities(String text) {
    if (text == null || text.indexOf('&') < 0) return text;
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c != '&') {
        out.append(c);
        i++;
        continue;
      }
      int end = text.indexOf(';', i + 1);
      if (end < 0 || end - i > 12) {
        out.append(c);
        i++;
        continue;
      }
      String entity = text.substring(i + 1, end);
      String replacement = decodeEntity(entity);
      if (replacement == null) {
        out.append(c);
        i++;
      } else {
        out.append(replacement);
        i = end + 1;
      }
    }
    return out.toString();
  }

  private static String decodeEntity(String entity) {
    if (entity.startsWith("#")) {
      try {
        int code =
            entity.startsWith("#x") || entity.startsWith("#X")
                ? Integer.parseInt(entity.substring(2), 16)
                : Integer.parseInt(entity.substring(1));
        return new String(Character.toChars(code));
      } catch (RuntimeException e) {
        return null;
      }
    }
    return switch (entity.toLowerCase(Locale.ROOT)) {
      case "amp" -> "&";
      case "lt" -> "<";
      case "gt" -> ">";
      case "quot" -> "\"";
      case "apos" -> "'";
      case "nbsp" -> " ";
      case "ldquo" -> "“";
      case "rdquo" -> "”";
      case "lsquo" -> "‘";
      case "rsquo" -> "’";
      case "middot" -> "·";
      case "hellip" -> "…";
      case "mdash" -> "—";
      case "ndash" -> "–";
      case "yen" -> "¥";
      case "copy" -> "©";
      case "reg" -> "®";
      case "deg" -> "°";
      default -> null;
    };
  }

  /** 相对地址 → 绝对地址；不是 http(s) 的（data:、javascript:）一律丢掉。 */
  static String resolve(URI base, String reference) {
    if (reference == null) return null;
    String value = reference.trim();
    if (value.isEmpty()) return null;
    if (value.startsWith("//")) {
      String scheme = base == null ? "https" : base.getScheme();
      value = (scheme == null ? "https" : scheme) + ":" + value;
    }
    try {
      URI resolved = base == null ? URI.create(value) : base.resolve(value);
      String scheme = resolved.getScheme();
      if (scheme == null) return null;
      String lower = scheme.toLowerCase(Locale.ROOT);
      if (!"http".equals(lower) && !"https".equals(lower)) return null;
      return resolved.toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 外部页面的标价：只接受正常的价格文本，其余（"面议"、"起"）当作没有价格。 */
  static BigDecimal parseAmount(String raw) {
    if (raw == null) return null;
    String value = raw.trim();
    if (value.isEmpty()) return null;
    // 去掉币种符号与千分位
    value = value.replaceAll("[^0-9.\\-]", "");
    if (value.isEmpty() || value.equals("-") || value.equals(".")) return null;
    try {
      BigDecimal amount = new BigDecimal(value);
      if (amount.scale() > 4) amount = amount.setScale(4, java.math.RoundingMode.HALF_UP);
      return amount;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String firstGroup(Pattern pattern, String html) {
    Matcher matcher = pattern.matcher(html);
    if (!matcher.find()) return null;
    return matcher.group(1);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static void putIfBlank(Map<String, String> target, String key, String value) {
    if (key == null || key.isBlank()) return;
    if (value == null || value.isBlank()) return;
    if (target.containsKey(key)) return;
    target.put(key.trim(), value.trim());
  }

  /** 供测试用：把若干候选值去重保序。 */
  static Set<String> dedup(List<String> values) {
    return new LinkedHashSet<>(values);
  }
}
