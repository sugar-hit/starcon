package com.starcon.channel;

import static com.starcon.channel.LabelTemplateModels.LabelElement;
import static com.starcon.channel.LabelTemplateModels.LabelBatteryMark;
import static com.starcon.channel.LabelTemplateModels.LabelDeclaration;
import static com.starcon.channel.LabelTemplateModels.LabelFinding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 10.2.2.2 面单模板的**真渲染**：把模板规格现算成一份可打印的热敏指令流。
 *
 * <p>蓝图的职责句里有「尺寸、条码类型、附加声明、电池标识、语言版本」五个词。本类
 * 刻意<b>不是</b>「存进库就算完」—— 每一次预览都把这五项真的算一遍：
 *
 * <ul>
 *   <li><b>尺寸</b>：毫米 → dot 用 {@link BigDecimal} 换算并四舍五入到整数，
 *       {@code mm × dpi ÷ 25.4}。不用 float：float 在 203dpi 下会攒出 ±1 dot，
 *       而 1 dot = 0.125mm，条码高度被吃掉一角，扫码枪就开始读不出。
 *   <li><b>条码类型</b>：一维码（Code 128 A/B、Code 39）在这里**真编码** ——
 *       按规范算校验位、按规范出模块宽度序列。所以「这个运单号能不能用 CODE39 印」
 *       是一个在预览时就能回答的事实，而不是等票贴出去才发现。
 *   <li><b>附加声明 / 电池标识</b>：逐条判定有没有锚定到真实打印位、锚定的那一格
 *       在预览里有没有值。🔴 这就是加粗句「漏打会导致清关问题」的落点 ——
 *       漏打在预览里表现为 BLOCK，而不是印完才发现。
 *   <li><b>语言版本</b>：写进 ZPL 的 {@code ^CI28}（UTF-8）。语言不是装饰：
 *       按 ^CI 打错的面单在部分承运商的清关系统里直接被拒收。
 * </ul>
 *
 * <p>⚠️ <b>本类不是第二个打印引擎</b>：打印机、打印任务、重复打印、打印日志全部是
 * 8.3.4.2（warehouse，V73）的主权。这里渲染出来的是**模板合规预览** —— 用来自
 * 10.1.1.2 的渠道要求 + 本模板的声明与布局，真算一遍「这张面单印出来够不够承运商收」，
 * 供人在启用前确认。自建打印任务队列会出现两份「印的是什么」。
 *
 * <p>二维码（QR / DataMatrix）刻意<b>只做长度与字符校验</b>、编码交给打印机
 * （{@code ^BQ} / {@code ^BX}）：现场机器的 QR 编码器才是最终权威，自研一套只会
 * 出现「预览里能扫、机器上扫不出」的分裂。这一点在能力声明里如实标出来，
 * 不假装本模块会生成二维码点阵。
 */
final class LabelRenderEngine {

  private LabelRenderEngine() {}

  // ==================================================================
  // 输入 / 输出
  // ==================================================================

  /** 模板规格：服务层已从库里读完的模板头 + 三张子表（渲染**不查库**）。 */
  record Spec(
      String templateCode,
      String languageCode,
      String labelFormat,
      BigDecimal paperWidthMm,
      BigDecimal paperHeightMm,
      int dpi,
      String orientation,
      BigDecimal originOffsetXMm,
      BigDecimal originOffsetYMm,
      String primarySymbology,
      BigDecimal primaryBarcodeHeightMm,
      boolean humanReadable,
      List<LabelElement> elements,
      List<LabelDeclaration> declarations,
      List<LabelBatteryMark> batteryMarks) {}

  /** 预览用的样例值（key = 打印位 elementKey）。 */
  record Sample(Map<String, String> values, BigDecimal wattHours) {}

  record Rendered(
      String format,
      String content,
      String sha256,
      String symbology,
      String encodedValue,
      String modules,
      String verdict,
      List<LabelFinding> findings) {}

  /** 预览里打印位没给值时的占位符（界面上能一眼看出「这一格没填」）。 */
  private static final String PLACEHOLDER_PREFIX = "«";
  private static final String PLACEHOLDER_SUFFIX = "»";

  // ==================================================================
  // 尺寸换算：mm → dot，全程 BigDecimal
  // ==================================================================

  /** 1 英寸 = 25.4mm（精确值，不是 25）。这是整个换算里唯一的常量来源。 */
  private static final BigDecimal MM_PER_INCH = new BigDecimal("25.4");

  /**
   * 毫米 → 打印机 dot。
   *
   * <p>{@code RoundingMode.HALF_UP} 到 0 位小数：打印机只认整数 dot，而「四舍五入」
   * 必须钉死一个模式（默认的 ROUND_HALF_EVEN 会让 0.5 进成偶数，同样输入两次渲染
   * 在不同 JVM 上可能差一个点）。
   */
  static int dots(BigDecimal mm, int dpi) {
    return mm.multiply(BigDecimal.valueOf(dpi))
        .divide(MM_PER_INCH, 0, RoundingMode.HALF_UP)
        .intValueExact();
  }

  /** 判定用：毫米加法也走 {@link BigDecimal}，不用 double。 */
  private static BigDecimal plus(BigDecimal left, BigDecimal right) {
    return left.add(right);
  }

  // ==================================================================
  // 判定结论码（前端按 code 分色，message 给人看）
  // ==================================================================

  static final String F_ELEMENT_NO_VALUE = "ELEMENT_NO_VALUE";
  static final String F_ELEMENT_OVERFLOW = "ELEMENT_OVERFLOW";
  static final String F_DECLARATION_UNANCHORED = "DECLARATION_UNANCHORED";
  static final String F_DECLARATION_NO_VALUE = "DECLARATION_NO_VALUE";
  static final String F_DECLARATION_BLANK = "DECLARATION_BLANK";
  static final String F_BARCODE_NO_VALUE = "BARCODE_NO_VALUE";
  static final String F_BARCODE_NOT_ENCODABLE = "BARCODE_NOT_ENCODABLE";
  static final String F_BARCODE_UNDERSIZE = "BARCODE_UNDERSIZE";
  static final String F_BATTERY_UN38_3 = "BATTERY_UN38_3_MISSING";
  static final String F_BATTERY_WATT_MISMATCH = "BATTERY_WATT_MISMATCH";
  static final String F_BATTERY_ORPHAN_ELEMENT = "BATTERY_ORPHAN_ELEMENT";

  private static final String BLOCK = "BLOCK";
  private static final String REVIEW = "REVIEW";

  // ==================================================================
  // 渲染主流程
  // ==================================================================

  static Rendered render(Spec spec, Sample sample) {
    List<LabelFinding> findings = new ArrayList<>();

    Map<String, String> values = sample == null ? Map.of() : sample.values();
    BigDecimal wattHours = sample == null ? null : sample.wattHours();

    // ---- ① 打印位去重与几何复核 ----
    //    DB 触发器已经挡过越界，这里再算一遍：渲染器是最后一道闸，
    //    它不该输出任何一条会印到纸外的指令（哪怕数据是绕过服务层塞进来的）。
    Set<String> keys = new LinkedHashSet<>();
    BigDecimal paperW = spec.paperWidthMm();
    BigDecimal paperH = spec.paperHeightMm();
    Map<String, LabelElement> byKey = new LinkedHashMap<>();
    for (LabelElement element : spec.elements()) {
      if (!keys.add(element.elementKey())) {
        findings.add(
            block(F_ELEMENT_OVERFLOW, "打印位「" + element.elementKey() + "」在这张模板上重复出现："
                + "同一位置只能有一个元素，否则渲染时后者会盖掉前者"));
      }
      if (element.xMm().signum() < 0
          || element.yMm().signum() < 0
          || plus(element.xMm(), element.wMm()).compareTo(paperW) > 0
          || plus(element.yMm(), element.hMm()).compareTo(paperH) > 0) {
        findings.add(
            block(F_ELEMENT_OVERFLOW, "打印位「" + element.elementKey() + "」越出纸张："
                + "右下角落在 " + plus(element.xMm(), element.wMm()) + " × "
                + plus(element.yMm(), element.hMm()) + " mm，而纸张只有 " + paperW + " × " + paperH
                + " mm —— 越出部分印不出去"));
      }
      byKey.put(element.elementKey(), element);
    }

    // ---- ② 附加声明：🔴 锚定与取值（本模块最要紧的一段判定）----
    for (LabelDeclaration declaration : spec.declarations()) {
      if (declaration.displayText().isBlank() && declaration.declarationCode().isBlank()) {
        findings.add(
            block(F_DECLARATION_BLANK, "附加声明「" + declaration.declarationKind() + "」既没有声明代码"
                + "也没有印面文字：它在面单上什么都不会出现，等于没声明"));
        continue;
      }
      LabelElement anchor = byKey.get(declaration.elementKey());
      if (anchor == null) {
        // DB 触发器本来已拒；这里是渲染器自己的兜底（清关漏打 = 最贵的一种错）
        findings.add(
            block(F_DECLARATION_UNANCHORED, "附加声明「" + declaration.declarationKind()
                + "」指向的打印位「" + declaration.elementKey() + "」不存在："
                + "无法证明它真的印出来了，漏打会导致清关问题"));
        continue;
      }
      String value = values.get(declaration.elementKey());
      if (value == null || value.isBlank()) {
        findings.add(
            (declaration.mandatory() ? BLOCK : REVIEW) == BLOCK
                ? block(F_DECLARATION_NO_VALUE, "强制附加声明「" + declaration.declarationKind()
                    + "」的打印位「" + declaration.elementKey() + "」在这次预览里没有值："
                    + "面单上这一格会是空的 —— 漏打会导致清关问题")
                : review(F_DECLARATION_NO_VALUE, "附加声明「" + declaration.declarationKind()
                    + "」的打印位「" + declaration.elementKey() + "」在这次预览里没有值"));
      }
    }

    // ---- ③ 电池标识（UN38.3 是 IATA/UN 的真实硬性口径）----
    for (LabelBatteryMark mark : spec.batteryMarks()) {
      BigDecimal wh = mark.wattHours();
      if (isLithium(mark.batteryType())
          && wh != null
          && wh.compareTo(new BigDecimal("2.7")) > 0
          && wh.compareTo(new BigDecimal("100")) <= 0
          && !mark.un38_3Attested()) {
        findings.add(
            block(F_BATTERY_UN38_3, "锂电池瓦时 " + wh + " 落在 2.7 < Wh ≤ 100 区间，"
                + "按 IATA / UN 口径必须提供 UN38.3 测试摘要：缺了它整批货会在机场被扣下退回"));
      }
      if (wattHours != null && wh != null && wattHours.compareTo(wh) != 0) {
        findings.add(
            review(F_BATTERY_WATT_MISMATCH, "预览里电池条印的是 " + wattHours + " Wh，"
                + "而模板登记的是 " + wh + " Wh：面单上的瓦时与申报口径不一致会被清关质疑"));
      }
      if (byKey.get(mark.elementKey()) == null) {
        findings.add(
            block(F_DECLARATION_UNANCHORED, "电池标识「" + mark.batteryType() + "」指向的打印位「"
                + mark.elementKey() + "」不存在：漏打会导致清关问题"));
      }
    }
    // 模板上有 BATTERY_MARK 打印位却没登记电池标识 —— 现场看得见那个格子，
    // 但没人知道该往里印什么，这是「看起来齐了其实没齐」的典型形态。
    boolean hasBatteryMarkElement =
        spec.elements().stream().anyMatch(e -> "BATTERY_MARK".equals(e.elementKind()));
    if (hasBatteryMarkElement && spec.batteryMarks().isEmpty()) {
      findings.add(
          review(F_BATTERY_ORPHAN_ELEMENT, "模板上留了 BATTERY_MARK 打印位，但没有登记电池标识："
              + "那一格印什么没有定义，现场会留下一块空白"));
    }

    // ---- ④ 组装指令流 ----
    StringBuilder zpl = new StringBuilder();
    int widthDots = dots(paperW, spec.dpi());
    int heightDots = dots(paperH, spec.dpi());
    // 零点校准：把 origin_offset 折成 ^LH（label home）。这是「热敏机走纸原点与标签
    // 物理原点的差」，量级在毫米级，与「元素越界」是两件事（见 V141 第三节）。
    int homeX = dots(spec.originOffsetXMm(), spec.dpi());
    int homeY = dots(spec.originOffsetYMm(), spec.dpi());

    zpl.append("^XA\n");
    // 🔴 语言版本落在这里：^CI28 = UTF-8。语言不是装饰，按 ^CI 打错的面单在部分
    //    承运商的清关系统里直接被拒收。
    zpl.append("^CI28\n");
    zpl.append("^LH").append(homeX).append(',').append(homeY).append('\n');
    zpl.append("^PW").append(widthDots).append('\n');
    zpl.append("^LL").append(heightDots).append('\n');
    zpl.append("^LH0,0\n");

    String symbology = spec.primarySymbology();
    String encodedValue = "";
    String modules = "";
    List<LabelElement> ordered = new ArrayList<>(spec.elements());
    ordered.sort(
        (left, right) ->
            Integer.compare(left.sequence(), right.sequence()) != 0
                ? Integer.compare(left.sequence(), right.sequence())
                : left.elementKey().compareTo(right.elementKey()));

    for (LabelElement element : ordered) {
      String value = values.get(element.elementKey());
      boolean isBarcode =
          "WAYBILL_BARCODE".equals(element.elementKind()) || "QR_CODE".equals(element.elementKind());
      if (value == null || value.isBlank()) {
        if (isBarcode) {
          // 🔴 条码位没值 = 票贴出去扫不出。宁可一条指令都不发（渲染结果里看得见
          //    少了这段），也不要发一个空条码指令让打印机打一坨黑。
          findings.add(
              block(F_BARCODE_NO_VALUE, "条码打印位「" + element.elementKey() + "」在这次预览里没有值："
                  + "面单上这一格印不出条码，票到了扫不出运单号"));
          continue;
        }
        findings.add(
            review(F_ELEMENT_NO_VALUE, "打印位「" + element.elementKey() + "」在这次预览里没有值，"
                + "会印上占位符 " + PLACEHOLDER_PREFIX + element.elementKey() + PLACEHOLDER_SUFFIX));
        value = PLACEHOLDER_PREFIX + element.elementKey() + PLACEHOLDER_SUFFIX;
      }

      int x = dots(element.xMm(), spec.dpi());
      int y = dots(element.yMm(), spec.dpi());
      int boxW = dots(element.wMm(), spec.dpi());
      int boxH = dots(element.hMm(), spec.dpi());

      if (isBarcode) {
        String kind = "QR_CODE".equals(element.elementKind()) ? "QR" : element.symbology();
        String encoded;
        try {
          encoded = oneDimensionalOrMatrix(kind, value, x, y, boxW, boxH);
        } catch (NotEncodable e) {
          findings.add(
              block(F_BARCODE_NOT_ENCODABLE, "打印位「" + element.elementKey() + "」声明条码类型 "
                  + kind + "，但它要印的值「" + value + "」" + e.getMessage()));
          continue;
        }
        if (encodedValue.isEmpty()) {
          symbology = kind;
          encodedValue = value;
          modules = moduleSummary(kind, value);
        }
        // 一维码高度低于模板声明的主条码高度 = 承运商会拒收的一类实物问题
        if (!"QR".equals(kind) && element.hMm().compareTo(spec.primaryBarcodeHeightMm()) < 0) {
          findings.add(
              review(F_BARCODE_UNDERSIZE, "条码打印位「" + element.elementKey() + "」高 "
                  + element.hMm() + " mm，低于本模板声明的主条码高度 "
                  + spec.primaryBarcodeHeightMm() + " mm：太矮的一维码在扫码枪上经常读不出"));
        }
        zpl.append(encoded);
        continue;
      }

      // 文本类元素
      int fontPt = element.fontPt() == null ? 12 : element.fontPt();
      // ZPL 的字体高度单位是 dot，不是磅，所以这里也要换算
      int fontDots = Math.max(dots(BigDecimal.valueOf(fontPt * 25.4 / 72), spec.dpi()), 8);
      char rotation = switch (element.rotation()) {
        case 90 -> 'R';
        case 180 -> 'I';
        case 270 -> 'B';
        default -> 'N';
      };
      zpl.append("^FO").append(x).append(',').append(y)
          .append("^A0").append(rotation).append(',').append(fontDots).append(',').append(fontDots)
          .append("^FD").append(escapeZpl(value)).append("^FS\n");
      // 文本元素的 h_mm 是「这一格留多高」，渲染成一条 ^GB 边框会让校对的人一眼
      // 看出这块版面被谁占了 —— 这不是装饰，是尺寸这条职责句的可视化证据。
      if (boxW > 0 && boxH > 0 && spec.humanReadable()) {
        zpl.append("^FO").append(x).append(',').append(y)
            .append("^GB").append(boxW).append(',').append(boxH).append(",1,^FS\n");
      }
    }

    zpl.append("^XZ\n");

    String verdict = LabelTemplateModels.LabelVerdict.PASS;
    for (LabelFinding finding : findings) {
      verdict = LabelTemplateModels.LabelVerdict.worse(verdict, finding.severity());
    }

    return new Rendered(
        spec.labelFormat(),
        zpl.toString(),
        sha256(zpl.toString()),
        symbology == null ? "NONE" : symbology,
        encodedValue,
        modules,
        verdict,
        findings);
  }

  // ==================================================================
  // 条码编码：一维码真编码（含校验位），二维码只做长度与字符校验
  // ==================================================================

  /** 值在该条码类型下编不出来。message 是**加在值后面**的可读理由。 */
  static final class NotEncodable extends RuntimeException {
    NotEncodable(String message) {
      super(message);
    }
  }

  /**
   * 生成一段条码的 ZPL 指令。
   *
   * <p>一维码返回 {@code ^BC}/{@code ^BY} 指令流；二维码返回 {@code ^BQ}/{@code ^BX}，
   * 并**只校验长度与字符集** —— 真正的点阵由现场打印机生成（见类注释）。
   *
   * <p>坐标与高度都是**换算好的 dot**（调用方按元素自己的 mm 尺寸换算），所以每一条
   * 指令都落在元素自己的格子里，而不是统统落在原点。
   */
  private static String oneDimensionalOrMatrix(
      String kind, String value, int x, int y, int w, int h) {
    return switch (kind == null ? "" : kind) {
      case "CODE128_B" -> {
        Code128.encodeSetB(value); // 真编码 + 真校验位：可编码性在这里就被问一次
        yield "^FO" + x + "," + y + "^BY2,3.0," + h + "^BCN," + h + ",Y,N^FD"
            + escapeZpl(value) + "^FS";
      }
      case "CODE128_A" -> {
        Code128.encodeSetA(value);
        yield "^FO" + x + "," + y + "^BY2,3.0," + h + "^BCN," + h + ",Y,N^FD"
            + escapeZpl(value) + "^FS";
      }
      case "CODE39" -> {
        Code39.encode(value);
        yield "^FO" + x + "," + y + "^BY2,3.0," + h + "^B3N,N,,N^FD"
            + escapeZpl(value) + "^FS";
      }
      case "QR" -> {
        // 二维码：字符集 + 字节长度。超限在预览就被拒，而不是印出来扫不出。
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 2953) {
          throw new NotEncodable("编码后 " + bytes + " 字节，超过 QR 在纠错等级 L 下的 2953 字节上限");
        }
        yield "^FO" + x + "," + y + "^BQN,2,4^FDQA," + escapeZpl(value) + "^FS";
      }
      case "DATAMATRIX" -> {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 2338) {
          throw new NotEncodable("编码后 " + bytes + " 字节，超过 DataMatrix 的 2338 数字/字符上限");
        }
        yield "^FO" + x + "," + y + "^BXN,6,200^FD" + escapeZpl(value) + "^FS";
      }
      default -> throw new NotEncodable("不是本模块支持的条码类型");
    };
  }

  /**
   * 条码模块宽度序列的可复核表示（界面上给现场对着条码机核对用）。
   *
   * <p>二维码返回空串 —— 它的点阵由打印机生成，本模块没有序列可以给（不编一个假的）。
   */
  private static String moduleSummary(String kind, String value) {
    return switch (kind == null ? "" : kind) {
      case "CODE128_B" -> Code128.encodeSetB(value);
      case "CODE128_A" -> Code128.encodeSetA(value);
      case "CODE39" -> Code39.encode(value);
      default -> "";
    };
  }

  // ==================================================================
  // Code 128（Set A / Set B 真编码，含模 103 校验位）
  // ==================================================================

  static final class Code128 {
    private Code128() {}

    /**
     * Code 128 的 107 个图案（0..106），每个是 6 位宽度串（条/空交替，第一个是条）。
     * 106 是 STOP（7 位）。
     */
    private static final String[] PATTERNS = {
      "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
      "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
      "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
      "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
      "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
      "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
      "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
      "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
      "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
      "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
      "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
      "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
      "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
      "211214", "211232", "2331112"
    };

    private static final int START_A = 103;
    private static final int START_B = 104;
    private static final int STOP = 106;

    /**
     * Code 128 Set B：ASCII 32..126，**中文与重音字符编不进去**。
     *
     * <p>这不是配置项，是真实的编码约束：把一个含中文的运单号声明成 CODE128_B，
     * 印出来是一串扫码枪读不出的条纹。所以在这里拒，而不是等票贴出去。
     */
    static String encodeSetB(String value) {
      if (value == null || value.isEmpty()) {
        throw new NotEncodable("是空值");
      }
      int[] data = new int[value.length() + 1];
      data[0] = START_B;
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c < 32 || c > 126) {
          throw new NotEncodable(
              "含 Code 128 Set B 编不进的字符「" + describe(c) + "」"
                  + "（Set B 只覆盖 ASCII 32-126，中文与重音字符必须改用 QR 或 DataMatrix）");
        }
        data[i + 1] = c - 32;
      }
      return toModules(withChecksumAndStop(data));
    }

    /** Code 128 Set A：ASCII 32..95，外加控制字符 0..31。 */
    static String encodeSetA(String value) {
      if (value == null || value.isEmpty()) {
        throw new NotEncodable("是空值");
      }
      int[] data = new int[value.length() + 1];
      data[0] = START_A;
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c > 95) {
          throw new NotEncodable(
              "含 Code 128 Set A 编不进的字符「" + describe(c) + "」（Set A 只覆盖 ASCII 0-95）");
        }
        // ASCII 32..95 → 0..63；ASCII 0..31 → 64..95（规范里的两段映射）
        data[i + 1] = c >= 32 ? c - 32 : c + 64;
      }
      return toModules(withChecksumAndStop(data));
    }

    /**
     * 补上<b>校验位</b>与 <b>STOP</b> 两个位置。
     *
     * <p>🔴 STOP 不是可选项：少一位 STOP 的 Code 128 条码在扫码枪上直接读不出，
     * 而在界面上看着完全正常（条纹都在、长度也对）。所以这里显式补齐，不靠调用方记得。
     *
     * <p>入参是 {@code [start, 字符值…]}，返回 {@code [start, 字符值…, 校验位, STOP]}。
     */
    private static int[] withChecksumAndStop(int[] data) {
      int[] full = new int[data.length + 2];
      System.arraycopy(data, 0, full, 0, data.length);
      full[data.length] = checksum(data);
      full[data.length + 1] = STOP;
      return full;
    }

    /**
     * 模 103 校验位：{@code (start + Σ i × value_i) mod 103}，{@code i} 从 1 开始。
     *
     * <p>算错会在扫码枪上直接读不出，所以这里老老实实按规范算，不用「差不多」的公式。
     * 入参是 {@code [start, 字符值…]}（不含校验位与 STOP 本身）。
     */
    static int checksum(int[] data) {
      long sum = data[0];
      for (int i = 1; i < data.length; i++) {
        sum += (long) i * data[i];
      }
      return (int) (sum % 103);
    }

    private static String toModules(int[] values) {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < values.length; i++) {
        int value = values[i];
        if (value < 0 || value > PATTERNS.length - 1) {
          throw new IllegalStateException("Code 128 值越界：" + value);
        }
        out.append(i == 0 ? "" : " ").append(PATTERNS[value]);
      }
      return out.toString();
    }

    static int startB() {
      return START_B;
    }

    static int startA() {
      return START_A;
    }

    static int stop() {
      return STOP;
    }
  }

  // ==================================================================
  // Code 39（真编码；字符集受限是它的定义的一部分）
  // ==================================================================

  static final class Code39 {
    private Code39() {}

    /** 每个字符 9 个元素（5 条 4 空），'n' 窄 'w' 宽；字符间 1 个窄空。 */
    private static final String[] PATTERNS = {
      "nnnwwnwnn", "wnnwnnnnw", "nnwwnnnnw", "wnwwnnnnn", "nnnwwnnnw",
      "wnnwwnnnn", "nnwwwnnnn", "nnnwnnwnw", "wnnwnnwnn", "nnwwnnwnn",
      "wnnnnwnnw", "nnwnnwnnw", "wnwnnwnnn", "nnnnwwnnw", "wnnnwwnnn",
      "nnwnwwnnn", "nnnnnwwnw", "wnnnnwwnn", "nnwnnwwnn", "nnnnwwwnn",
      "wnnnnnnww", "nnwnnnnww", "wnwnnnnwn", "nnnnwnnww", "wnnnwnnwn",
      "nnwnwnnwn", "nnnnnnwww", "wnnnnnwwn", "nnwnnnwwn", "nnnnwnwwn",
      "wwnnnnnnw", "nwwnnnnnw", "wwwnnnnnn", "nwnnwnnnw", "wwnnwnnnn",
      "nwwnwnnnn", "nwnnnnwnw", "wwnnnnwnn", "nwwnnnwnn", "nwnwnwnnn",
      "nwnwnnnwn", "nwnnnwnwn", "nnnwnwnwn"
    };

    /** Code 39 的合法字符集：数字、大写字母、- . 空格 $ / + %。 */
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";

    static String encode(String value) {
      if (value == null || value.isEmpty()) {
        throw new NotEncodable("是空值");
      }
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        // ⚠️ 刻意**不**自动转大写。Code 39 的非标准扩展确实能印小写，但那印出来的是
        //    另一个字符 —— 承运商系统按大写入库，运单号就对不上。这里宁可当场拒，
        //    也不要静默把 de1234 变成 DE1234 让现场以为条码内容没变。
        int index = ALPHABET.indexOf(c);
        if (index < 0) {
          throw new NotEncodable(
              "含 Code 39 编不进的字符「" + describe(c) + "」"
                  + "（Code 39 只有数字、大写字母与 - . 空格 $ / + %，小写字母必须改用 CODE128）");
        }
        if (i > 0) {
          out.append(' ');
        }
        out.append(PATTERNS[index]);
      }
      return out.toString();
    }
  }

  // ==================================================================
  // 工具
  // ==================================================================

  /** 控制字符与 ZPL 的 {@code ^} / {@code ~} 必须转义，否则会被当成指令。 */
  static String escapeZpl(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '^' -> out.append("^5E");
        case '~' -> out.append("^7E");
        default -> {
          if (c < 0x20 || c == 0x7F) {
            out.append('^').append(String.format("%02X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  static String sha256(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isLithium(String batteryType) {
    return "LITHIUM_ION".equals(batteryType)
        || "LITHIUM_METAL".equals(batteryType)
        || "LITHIUM_POLYMER".equals(batteryType);
  }

  /** 把控制字符变成可读的描述（界面上不能显示一坨乱码方块）。 */
  private static String describe(char c) {
    return c < 0x20 || c == 0x7F ? String.format("\\u%04X", (int) c) : String.valueOf(c);
  }

  private static LabelFinding block(String code, String message) {
    return new LabelFinding(BLOCK, code, message);
  }

  private static LabelFinding review(String code, String message) {
    return new LabelFinding(REVIEW, code, message);
  }

  static String normalizeSymbology(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
  }
}
