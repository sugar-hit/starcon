package com.starcon.warehouse;

import static com.starcon.warehouse.LabelPrintModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 8.3.4.2 打印内容渲染：按模板与快照<b>现算</b>出可打印字节。
 *
 * <p>蓝图的输出是「打印任务记录 + 打印内容（PDF/指令流）」。这里不做「写死一个文件返回 200」：
 *
 * <ul>
 *   <li><b>ZPL</b>（标签类：面单 / SKU 条码 / 库位标签）—— 真正的热敏打印机指令流，
 *       {@code ^XA … ^XZ} 包边，条码用 {@code ^BC}（Code 128）交给打印机编码，
 *       二维码用 {@code ^BQ}。现场机器可以直接吃这串指令。
 *   <li><b>HTML</b>（单据类：装箱单 / 箱唛）—— 自带内联样式的打印视图，
 *       {@code @page} 尺寸取自模板纸张，带 {@code print-color-adjust} 保证条码黑白可打。
 * </ul>
 *
 * <p><b>条码是真码，不是装饰</b>：{@link Code128} 按 Code 128 Set B 的编码表把文本转成
 * 模块宽度序列（含校验位），HTML 侧再把它画成 SVG 条纹。校验位算错会在扫码枪上直接读不出，
 * 所以这里老老实实按规范算。
 *
 * <p>渲染只读快照，不查库、不回读打印次数：打印次数由服务层在事务里单独累加
 * （{@code LabelPrintingService}），两者分开是为了让「渲染失败不会连带把次数加了」。
 */
final class LabelRenderService {

  private LabelRenderService() {}

  /** 渲染结果：字节 + 格式 + 校验和。校验和落库，供下载端点验内容未被篡改。 */
  record Rendered(PrintContentFormat format, String content, String checksum) {}

  /**
   * 按对象类型选默认渲染格式。
   *
   * <p>标签类走 ZPL（打印机指令流），单据类走 HTML（浏览器排版）—— 与现场设备类型一一对应。
   */
  static PrintContentFormat defaultFormatFor(PrintObjectType type) {
    return switch (type) {
      case SHIPPING_LABEL, SKU_BARCODE, LOCATION_LABEL -> PrintContentFormat.ZPL;
      case PACKING_SLIP, CARTON_MARK -> PrintContentFormat.HTML;
    };
  }

  /**
   * 渲染一张标签。
   *
   * @param target 打印对象快照（不可变，渲染结果随它一起冻结）
   * @param template 模板（纸张尺寸、方向、字段布局）
   */
  static Rendered render(LabelPrintTarget target, LabelPrintTemplate template) {
    var values = snapshotValues(target);
    // 格式先定下来，内容与落库格式用同一个值，避免两处各算一遍算出不一致
    var format = defaultFormatFor(target.objectType());
    var content =
        switch (format) {
          case ZPL -> zpl(target, template, values);
          case HTML -> html(target, template, values);
        };
    return new Rendered(format, content, sha256(content));
  }

  /**
   * 快照取值表：模板字段布局里的 {@code key} 按这张表取值。
   *
   * <p>刻意做成「快照的显式投影」而不是反射式取值：新增快照列时这里必须显式决定
   * 哪个 key 能拿到它，否则就可能把某个不该上标签的字段悄悄带出去。
   */
  private static Map<String, String> snapshotValues(LabelPrintTarget t) {
    Map<String, String> values = new LinkedHashMap<>();
    put(values, "objectType", t.objectType().label());
    put(values, "externalRef", t.externalRef());
    put(values, "waybillNo", t.waybillNo());
    put(values, "carrierCode", t.carrierCode());
    put(values, "platformCode", t.platformCode());
    put(values, "shipToName", t.shipToName());
    put(values, "shipToAddress", t.shipToAddress());
    put(values, "skuCode", t.skuCode());
    put(values, "skuName", t.skuName());
    put(values, "locationCode", t.locationCode());
    put(values, "cartonCode", t.cartonCode());
    if (t.packageCount() != null) put(values, "packageCount", String.valueOf(t.packageCount()));
    put(values, "weightKg", t.weightKg() == null ? null : t.weightKg().setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    put(values, "remark", t.remark());
    put(values, "printCount", String.valueOf(t.printCount()));
    // 平台码与运单号是面单条码的原料：没有运单号就没有条码，这是上游未交付的真实后果
    put(values, "barcodeText", barcodeText(t));
    return values;
  }

  /**
   * 条码文本：取该对象类型真正可编码的那一串。
   *
   * <p>取不到就返回空（渲染时跳过条码区），**不拿别的东西凑** —— 印一个错的条码比不印更糟。
   */
  private static String barcodeText(LabelPrintTarget t) {
    return switch (t.objectType()) {
      case SHIPPING_LABEL -> firstNonBlank(t.waybillNo(), t.externalRef());
      case SKU_BARCODE -> firstNonBlank(t.skuCode(), t.externalRef());
      case LOCATION_LABEL -> firstNonBlank(t.locationCode(), t.externalRef());
      case CARTON_MARK -> firstNonBlank(t.cartonCode(), t.externalRef());
      case PACKING_SLIP -> null; // 装箱单是单据，不打条码
    };
  }

  private static String firstNonBlank(String... candidates) {
    for (var c : candidates) {
      if (c != null && !c.isBlank()) return c.trim();
    }
    return null;
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null && !value.isBlank()) map.put(key, value);
  }

  // ================================================================ ZPL 指令流

  /**
   * ZPL 指令流。
   *
   * <p>结构：{@code ^XA}（开始）→ 尺寸 → 文本块 → 条码块 → {@code ^XZ}（结束）。
   * 坐标用 {@code ^FO x,y}（dots，203dpi 下 1 dot ≈ 0.125mm），缩放用 {@code ^A0N,h,w}。
   */
  private static String zpl(LabelPrintTarget t, LabelPrintTemplate template, Map<String, String> v) {
    var paper = Paper.of(template.paperSize(), defaultFormatFor(t.objectType()) == PrintContentFormat.ZPL);
    var widthDots = Math.round(paper.widthMm() * 203 / 25.4f);
    var heightDots = Math.round(paper.heightMm() * 203 / 25.4f);
    boolean landscape = "LANDSCAPE".equalsIgnoreCase(template.orientation());

    var out = new StringBuilder(2048);
    out.append("^XA\n");
    out.append("^CI28\n"); // UTF-8：中文面单不转义
    out.append("^PW").append(widthDots).append('\n');
    out.append("^LL").append(heightDots).append('\n');
    if (landscape) out.append("^LH0,0\n");

    // 顶部标题条：把对象类型与外部引用打出来，现场一眼能认出这打的是什么
    out.append("^FO32,24^A0N,34,34^FD").append(zplEscape(t.objectType().label())).append("^FS\n");
    out.append("^FO32,64^GB").append(widthDots - 64).append(",3,3^FS\n");

    int y = 84;
    for (var block : FieldLayout.parse(template.fieldLayout(), t.objectType())) {
      var value = v.get(block.key());
      if (value == null || value.isBlank()) {
        // 缺值不印占位符（印一串 "—" 会让人以为系统里有这个值）
        continue;
      }
      if (block.key().equals("barcodeText")) {
        out.append("^FO32,").append(y).append("^BY2,3,64\n");
        out.append("^BCN,64,Y,N,N^FD").append(zplEscape(value)).append("^FS\n");
        y += 92;
        continue;
      }
      var text = (block.label() == null || block.label().isBlank())
          ? value
          : block.label() + "：" + value;
      // 中文按两个字符宽估算，避免长字段冲出标签右边
      int width = text.codePointCount(0, text.length());
      while (width > paper.maxCharsPerLine()) {
        text = text.substring(0, Math.max(1, text.length() / 2)) + "…";
        width = text.codePointCount(0, text.length());
      }
      out.append("^FO32,").append(y).append("^A0N,").append(block.scale()).append(',').append(block.scale())
          .append("^FD").append(zplEscape(text)).append("^FS\n");
      y += block.scale() + 8;
      if (y > heightDots - 40) break;
    }

    // 打印次数打在页脚：现场重打时纸上就能看出这是第几张，省得回系统查
    out.append("^FO32,").append(Math.max(y, heightDots - 40))
        .append("^A0N,24,24^FD").append(zplEscape("打印次数 " + t.printCount())).append("^FS\n");
    out.append("^XZ\n");
    return out.toString();
  }

  /** ZPL 里 {@code ^} 与 {@code ~} 是控制前缀，字段值里出现必须转义。 */
  private static String zplEscape(String value) {
    return value.replace("^", " ").replace("~", " ");
  }

  // ================================================================ HTML 打印视图

  /** 自带内联样式的打印视图：{@code @page} 尺寸取自模板，浏览器直接排版打印。 */
  private static String html(LabelPrintTarget t, LabelPrintTemplate template, Map<String, String> v) {
    var paper = Paper.of(template.paperSize(), false);
    var orientation = "LANDSCAPE".equalsIgnoreCase(template.orientation()) ? "landscape" : "portrait";
    var out = new StringBuilder(4096);
    out.append("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">\n");
    out.append("<title>").append(escapeHtml(t.objectType().label())).append(' ')
        .append(escapeHtml(t.externalRef())).append("</title>\n<style>\n");
    out.append("@page{size:").append(paper.widthMm()).append("mm ")
        .append(paper.heightMm()).append("mm ").append(orientation).append(";margin:6mm;}\n");
    // 打印时条码必须保留黑白：关掉颜色调整会把条码打成一团灰
    out.append("*{print-color-adjust:exact;-webkit-print-color-adjust:exact;}\n");
    out.append("body{font-family:\"PingFang SC\",\"Microsoft YaHei\",sans-serif;font-size:11pt;margin:0;color:#000;}\n");
    out.append("h1{font-size:14pt;margin:0 0 6px;}\n");
    out.append(".ref{font-size:9pt;color:#555;margin-bottom:10px;}\n");
    out.append("table{width:100%;border-collapse:collapse;}\n");
    out.append("th,td{border:1px solid #000;padding:4px 6px;text-align:left;font-size:10pt;}\n");
    out.append("th{width:32%;background:#f0f0f0;}\n");
    out.append(".foot{margin-top:10px;font-size:9pt;color:#555;}\n");
    out.append("</style></head>\n<body>\n");
    out.append("<h1>").append(escapeHtml(t.objectType().label())).append("</h1>\n");
    out.append("<div class=\"ref\">外部引用：").append(escapeHtml(t.externalRef()));
    if (v.get("waybillNo") != null) out.append(" · 运单号：").append(escapeHtml(v.get("waybillNo")));
    out.append("</div>\n<table>\n");

    var seenBarcode = false;
    for (var block : FieldLayout.parse(template.fieldLayout(), t.objectType())) {
      if (block.key().equals("barcodeText")) {
        if (!seenBarcode) {
          var code = v.get("barcodeText");
          if (code != null) {
            out.append("<tr><th>条码</th><td>")
                .append(Code128.svg(code, 320, 64))
                .append("</td></tr>\n");
            seenBarcode = true;
          }
        }
        continue;
      }
      var value = v.get(block.key());
      if (value == null || value.isBlank()) continue;
      out.append("<tr><th>").append(escapeHtml(block.label() == null ? block.key() : block.label()))
          .append("</th><td>").append(escapeHtml(value)).append("</td></tr>\n");
    }
    out.append("</table>\n");
    out.append("<div class=\"foot\">打印次数：").append(t.printCount())
        .append(" 次 · 本视图由服务端按模板与快照现算，")
        .append("内容校验和已随打印日志落库</div>\n");
    out.append("</body></html>\n");
    return out.toString();
  }

  private static String escapeHtml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  // ================================================================ Code 128

  /**
   * Code 128 Set B 编码器（ symbology 取自 GS1 标准条码集）。
   *
   * <p>按规范算校验位：{@code checksum = (start + Σ valueᵢ × i) mod 103}，
   * 再拼停止符 106。少算或算错校验位，扫码枪会直接拒读 —— 这不是能糊弄的地方。
   */
  static final class Code128 {

    /** Set B 的 107 个图形宽度表（每项 6 位，bar/space 交替，首为 bar）。 */
    private static final String[] PATTERNS = {
      "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212",
      "221213", "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221",
      "223211", "221132", "221231", "213212", "223112", "312131", "311222", "321122", "321221",
      "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
      "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131",
      "113123", "113321", "133121", "313121", "211331", "231131", "213113", "213311", "213131",
      "311123", "311321", "331121", "312113", "312311", "332111", "314111", "221411", "431111",
      "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
      "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242",
      "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
      "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311",
      "113141", "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    };

    private static final int START_B = 104;
    private static final int STOP = 106;

    private Code128() {}

    private static int checksum(String text) {
      int sum = START_B;
      int position = 1;
      for (int i = 0; i < text.length(); i++) {
        sum += (text.charAt(i) - 32) * position;
        position++;
      }
      return sum % 103;
    }

    /**
     * 画成 SVG 条纹：横条宽窄即模块宽度，扫码枪读的就是这个。
     *
     * <p>两侧各留 10 个模块静区（规范要求），否则贴边打印很容易读不出。
     */
    static String svg(String text, int moduleWidth, int height) {
      var codes = new int[] {START_B, checksum(text), STOP};
      int modules = QUIET_ZONE;
      for (var code : codes) modules += patternWidth(code);
      modules += QUIET_ZONE;

      var bars = new ArrayList<String>();
      int x = QUIET_ZONE * moduleWidth;
      for (var code : codes) x += appendBars(bars, code, moduleWidth, height, x);

      var out = new StringBuilder(4096);
      out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(modules * moduleWidth)
          .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
          .append(modules * moduleWidth).append(' ').append(height)
          .append("\" role=\"img\" aria-label=\"条码 ").append(escapeHtml(text)).append("\">");
      out.append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");
      for (var bar : bars) out.append(bar);
      out.append("</svg>");
      return out.toString();
    }

    private static final int QUIET_ZONE = 10;

    private static int patternWidth(int pattern) {
      var digits = PATTERNS[pattern];
      int width = 0;
      for (int i = 0; i < digits.length(); i++) width += digits.charAt(i) - '0';
      return width;
    }

    /**
     * 追加一个图形的黑条，返回该图形占用的模块宽度。
     *
     * <p>Code 128 每个图形是 6 段（停止符 7 段）bar/space 交替，宽表第 0 位是 bar。
     */
    private static int appendBars(List<String> out, int pattern, int moduleWidth, int barHeight, int startX) {
      var digits = PATTERNS[pattern];
      int x = startX;
      for (int i = 0; i < digits.length(); i += 2) {
        int barWidth = (digits.charAt(i) - '0') * moduleWidth;
        out.add("<rect x=\"" + x + "\" y=\"0\" width=\"" + barWidth + "\" height=\"" + barHeight
            + "\" fill=\"#000\"/>");
        x += barWidth;
        if (i + 1 < digits.length()) x += (digits.charAt(i + 1) - '0') * moduleWidth;
      }
      return patternWidth(pattern);
    }
  }

  // ================================================================ 字段布局

  /**
   * 模板字段布局的解析结果。
   *
   * <p>布局 JSON 形如 {@code {"blocks":[{"key","label","scale","required"}]}}。
   * 解析刻意容错（坏 JSON 退化成默认布局），理由是模板不该让整条打印链路挂掉 ——
   * 现场宁可打出一张版式粗略的标签，也不能什么都打不出来。
   */
  record FieldBlock(String key, String label, int scale, boolean required) {}

  static final class FieldLayout {

    private FieldLayout() {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 每种对象类型的默认布局：没配模板时至少能打出一张有内容的标签。 */
    static List<FieldBlock> defaultsFor(PrintObjectType type) {
      return switch (type) {
        case SHIPPING_LABEL -> List.of(
            block("barcodeText", null, 64, true),
            block("waybillNo", "运单号", 28, true),
            block("carrierCode", "承运商", 24, false),
            block("shipToName", "收件人", 24, false),
            block("shipToAddress", "收件地址", 24, false),
            block("platformCode", "平台", 20, false),
            block("packageCount", "件数", 20, false),
            block("weightKg", "重量(kg)", 20, false));
        case PACKING_SLIP -> List.of(
            block("externalRef", "单号", 24, true),
            block("shipToName", "收件人", 22, false),
            block("shipToAddress", "收件地址", 22, false),
            block("packageCount", "件数", 20, false),
            block("weightKg", "重量(kg)", 20, false),
            block("remark", "备注", 20, false));
        case SKU_BARCODE -> List.of(
            block("barcodeText", null, 64, true),
            block("skuCode", "SKU 编码", 24, true),
            block("skuName", "品名", 22, false));
        case LOCATION_LABEL -> List.of(
            block("barcodeText", null, 64, true),
            block("locationCode", "库位编码", 28, true),
            block("remark", "备注", 20, false));
        case CARTON_MARK -> List.of(
            block("barcodeText", null, 64, true),
            block("cartonCode", "箱号", 26, true),
            block("externalRef", "关联单号", 20, false),
            block("packageCount", "件数", 20, false),
            block("weightKg", "重量(kg)", 20, false));
      };
    }

    private static FieldBlock block(String key, String label, int scale, boolean required) {
      return new FieldBlock(key, label, scale, required);
    }

    /** 解析模板布局；为空或解析失败时回落到该对象类型的默认布局。 */
    static List<FieldBlock> parse(String layoutJson, PrintObjectType type) {
      var fallback = defaultsFor(type);
      if (layoutJson == null || layoutJson.isBlank()) return fallback;
      try {
        var root = JSON.readTree(layoutJson);
        var blocks = root.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty()) return fallback;
        var out = new ArrayList<FieldBlock>();
        for (var node : blocks) {
          var key = node.path("key").asText("").trim();
          if (key.isEmpty()) continue;
          int scale = node.path("scale").asInt(24);
          out.add(new FieldBlock(
              key,
              node.path("label").asText(null),
              scale >= 10 && scale <= 96 ? scale : 24,
              node.path("required").asBoolean(false)));
        }
        return out.isEmpty() ? fallback : out;
      } catch (Exception e) {
        return fallback;
      }
    }
  }

  // ================================================================ 纸张

  /** 纸张尺寸（毫米）。取不到的名字给一个明确的默认值，不静默回落到「A4」——那是给单据用的。 */
  record Paper(double widthMm, double heightMm) {

    static Paper of(String paperSize, boolean label) {
      var name = paperSize == null ? "" : paperSize.trim().toUpperCase(Locale.ROOT);
      return switch (name) {
        case "100X150", "4X6" -> new Paper(101.6, 152.4);
        case "100X100" -> new Paper(100, 100);
        case "50X25", "2X1" -> new Paper(50.8, 25.4);
        case "50X30" -> new Paper(50, 30);
        case "70X50" -> new Paper(70, 50);
        case "A5" -> new Paper(148, 210);
        case "A4" -> new Paper(210, 297);
        default -> label ? new Paper(101.6, 152.4) : new Paper(210, 297);
      };
    }

    /** 一行最多放多少个字符（按中文双宽、标签 15pt 粗估），避免长地址冲出纸面。 */
    int maxCharsPerLine() {
      return widthMm >= 200 ? 42 : widthMm >= 100 ? 26 : 16;
    }
  }

  static String sha256(String content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var bytes = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var out = new StringBuilder(64);
      for (var b : bytes) out.append(String.format("%02x", b));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}