package com.starcon.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.4.1.2 图片处理的纯规则层：词表、枚举合法性、配方校验、容量与耗时口径。
 *
 * <p>与 {@link MediaRules} / {@link BrandRules} / {@link UnitGroupRules} 同一形状：无状态静态方法，
 * 服务层拒绝时给出的话、前端表单校验、集成测试断言的边界都引同一份实现。
 *
 * <p><b>像素加工本身不在这里。</b>去背景 / 白底 / 水印 / 裁剪 / 格式转换 / 多比例是 {@link ImageProcessor} 的职责（要 ImageIO 与
 * Graphics2D），这里只管「参数合不合法」—— 两类逻辑混在一起会让「校验」这个能单测的纯函数被像素代码污染。
 */
public final class ImageProcessRules {
  private ImageProcessRules() {}

  // ============================================================ 词表（与 V19 的 CHECK 逐字一致）

  public static final List<String> TARGET_FORMATS = List.of("png", "jpeg", "gif", "bmp");
  public static final List<String> CROP_MODES = List.of("FIT", "CROP_FILL");
  public static final List<String> WATERMARK_POSITIONS =
      List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT", "CENTER", "TILED");
  public static final List<String> JOB_STATUSES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");
  public static final List<String> ITEM_STATUSES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");

  /** 终态：任务到达这些状态后不会再被工作线程改动。 */
  public static final List<String> TERMINAL_JOB_STATUSES =
      List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");

  private static final java.util.Map<String, String> JOB_STATUS_LABELS =
      java.util.Map.of(
          "PENDING", "排队中",
          "RUNNING", "处理中",
          "SUCCEEDED", "全部成功",
          "PARTIAL", "部分失败",
          "FAILED", "全部失败",
          "CANCELLED", "已取消");
  private static final java.util.Map<String, String> ITEM_STATUS_LABELS =
      java.util.Map.of(
          "PENDING", "待处理",
          "RUNNING", "处理中",
          "SUCCEEDED", "成功",
          "FAILED", "失败",
          "SKIPPED", "已跳过",
          "CANCELLED", "已取消");

  public static String jobStatusLabel(String value) {
    return JOB_STATUS_LABELS.getOrDefault(value, value);
  }

  public static String itemStatusLabel(String value) {
    return ITEM_STATUS_LABELS.getOrDefault(value, value);
  }

  public static boolean terminal(String status) {
    return TERMINAL_JOB_STATUSES.contains(status);
  }

  /** 部分失败：有成功也有失败。任务汇总用。 */
  public static boolean partial(String status) {
    return "PARTIAL".equals(status);
  }

  // ============================================================ 容量上限

  /**
   * 单次提交最多 500 个素材。
   *
   * <p>取 500 是因为一次任务会为每个素材、每个比例档写一个新版本行：500 素材 × 3 档 就是 1500 个新对象。更大的批量应该让调用方按批次提交，而不是把一个跑几小时、
   * 中途失败还要重跑几千次的任务塞进来。进度是可见的，所以分批不会丢信息。
   */
  public static final int MAX_ASSETS_PER_JOB = 500;

  /** 单个配方最多 8 个比例档。档位再多就不是「主图 + 缩略图」，而是批量导出工具。 */
  public static final int MAX_RATIOS_PER_RECIPE = 8;

  /**
   * 处理产物的像素面积上限 80 megapixel。
   *
   * <p>裁剪放大到极大尺寸时，每多一像素就多一次 RGB 读写。80MP（≈10000×8000） 在内存里按 ARGB 是
   * 320MB，一个批次并行几条就会把堆吃满。所以不是「能不能做」的问题， 而是给出上限并让服务层在**开工前**就拒绝——先分配再失败会连带整批回滚。
   */
  public static final long MAX_OUTPUT_PIXELS = 80_000_000L;

  /** 目标边长上限 12000 px，与上面的面积上限配套（12000×6666 就已接近 80MP）。 */
  public static final int MAX_TARGET_EDGE_PX = 12_000;

  /** 前景与背景的颜色距离超过容差就算背景。容差 0 表示只吃完全同色。 */
  public static final int MAX_BACKGROUND_TOLERANCE = 255;

  /** 超时保护：一个素材处理超过这个秒数就记失败，避免一张图把整批拖住。 */
  public static final long PER_ASSET_TIMEOUT_SECONDS = 60;

  // ============================================================ 校验

  public static String recipeCodeProblem(String code) {
    String value = MediaRules.normalize(code).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "配方编码不能为空";
    if (value.length() > 32) return "配方编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) return "配方编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String recipeNameProblem(String name) {
    String value = MediaRules.normalize(name);
    if (value.isEmpty()) return "配方名称不能为空";
    if (value.length() > 100) return "配方名称最多 100 个字符";
    return null;
  }

  /** 目标格式：JDK ImageIO 写得出的图片格式。WEBP 不在内（JDK 无 WEBP 写入器）。 */
  public static String targetFormatProblem(String format) {
    String value = MediaRules.normalize(format).toLowerCase(Locale.ROOT);
    if (value.isEmpty()) return "目标格式不能为空";
    if (!TARGET_FORMATS.contains(value))
      return "目标格式只能是 "
          + String.join(" / ", TARGET_FORMATS)
          + "（服务端用 JDK ImageIO 写出，WEBP 没有内置写入器，写它只会得到空产物）";
    return null;
  }

  public static String cropModeProblem(String mode) {
    String value = MediaRules.normalizeUpper(mode);
    if (!CROP_MODES.contains(value)) return "适配方式只能是 FIT（等比缩放补白边）或 CROP_FILL（填满后居中裁掉溢出）";
    return null;
  }

  public static String watermarkPositionProblem(String position) {
    String value = MediaRules.normalizeUpper(position);
    if (!WATERMARK_POSITIONS.contains(value))
      return "水印位置只能是 " + String.join(" / ", WATERMARK_POSITIONS);
    return null;
  }

  public static String toleranceProblem(Integer tolerance) {
    if (tolerance == null) return "去背景容差不能为空";
    if (tolerance < 0 || tolerance > MAX_BACKGROUND_TOLERANCE)
      return "去背景容差必须在 0~" + MAX_BACKGROUND_TOLERANCE + " 之间";
    return null;
  }

  public static String opacityProblem(Integer opacity) {
    if (opacity == null) return "水印不透明度不能为空";
    if (opacity < 0 || opacity > 100) return "水印不透明度必须在 0~100 之间";
    return null;
  }

  public static String watermarkTextProblem(String text) {
    String value = MediaRules.normalize(text);
    if (value.length() > 120) return "水印文字最多 120 个字符";
    return null;
  }

  /**
   * 目标尺寸校验：要么两列都空（不缩放），要么两列都有值且在上限内。
   *
   * <p>「只给宽或只给高」被拒：那种行的含义不唯一，而它一旦落库， 后面每个产物都会按一个说不清的尺寸裁。
   */
  public static String targetSizeProblem(Integer widthPx, Integer heightPx) {
    boolean noWidth = widthPx == null;
    boolean noHeight = heightPx == null;
    if (noWidth && noHeight) return null;
    if (noWidth) return "目标尺寸要同时给宽和高：只给一个尺寸时裁剪结果不唯一";
    if (noHeight) return "目标尺寸要同时给宽和高：只给一个尺寸时裁剪结果不唯一";
    if (widthPx <= 0 || heightPx <= 0) return "目标尺寸必须大于 0";
    if (widthPx > MAX_TARGET_EDGE_PX || heightPx > MAX_TARGET_EDGE_PX)
      return "目标边长最多 " + MAX_TARGET_EDGE_PX + " px（再大就该用原图而不是放大）";
    return null;
  }

  public static String ratioLabelProblem(String label) {
    String value = MediaRules.normalize(label);
    if (value.isEmpty()) return "比例档标签不能为空";
    if (value.length() > 32) return "比例档标签最多 32 个字符";
    return null;
  }

  public static String ratioSizeProblem(Integer widthPx, Integer heightPx) {
    if (widthPx == null || heightPx == null) return "比例档必须同时给出宽和高";
    if (widthPx <= 0 || heightPx <= 0) return "比例档的宽高必须大于 0";
    if (widthPx > MAX_TARGET_EDGE_PX || heightPx > MAX_TARGET_EDGE_PX)
      return "比例档边长最多 " + MAX_TARGET_EDGE_PX + " px";
    return null;
  }

  public static String ratiosSizeProblem(int size) {
    if (size > MAX_RATIOS_PER_RECIPE) return "一个配方最多 " + MAX_RATIOS_PER_RECIPE + " 个比例档";
    return null;
  }

  /** 比例档标签在配方内重复。 */
  public static String duplicateRatioProblem(List<String> labels) {
    if (labels == null) return null;
    var seen = new java.util.HashSet<String>();
    for (String raw : labels) {
      String label = MediaRules.normalize(raw).toUpperCase(Locale.ROOT);
      if (!seen.add(label)) return "比例档标签重复：" + label + "（同一配方内标签必须唯一，否则产物无法区分）";
    }
    return null;
  }

  public static String assetsSizeProblem(int size) {
    if (size > MAX_ASSETS_PER_JOB)
      return "单次最多处理 " + MAX_ASSETS_PER_JOB + " 个素材（每个素材、每个比例档都会写一个新版本）";
    if (size == 0) return "请至少选择一个要处理的素材";
    return null;
  }

  /**
   * 去背景 + 输出格式的组合校验。
   *
   * <p>与 V19 那个 CHECK 同一语义，在服务层先给可读原因（DB 抛出来只有约束名）： 只有 png 有真正的 alpha 通道，gif 只有 1 位透明，bmp / jpeg 没有
   * alpha。
   */
  public static String backgroundFormatProblem(boolean removeBackground, String targetFormat) {
    if (!removeBackground) return null;
    if ("png".equals(MediaRules.normalize(targetFormat).toLowerCase(Locale.ROOT))) return null;
    return "去背景会把背景抠成透明，而目标格式 "
        + MediaRules.normalize(targetFormat).toLowerCase(Locale.ROOT)
        + " 不支持透明（jpeg/bmp 没有 alpha 通道，gif 只有 1 位）。请改用 png 目标格式，或勾选白底"
        + "（先抠背景再铺白底）";
  }

  /** 词表自检：Set.of 的重复元素会直接抛，这里显式断言没有笔误。 */
  static {
    Set<String> f = Set.copyOf(TARGET_FORMATS);
    if (f.size() != TARGET_FORMATS.size()) throw new IllegalStateException("TARGET_FORMATS 有重复项");
    Set<String> c = Set.copyOf(CROP_MODES);
    if (c.size() != CROP_MODES.size()) throw new IllegalStateException("CROP_MODES 有重复项");
    Set<String> w = Set.copyOf(WATERMARK_POSITIONS);
    if (w.size() != WATERMARK_POSITIONS.size())
      throw new IllegalStateException("WATERMARK_POSITIONS 有重复项");
    Set<String> j = Set.copyOf(JOB_STATUSES);
    if (j.size() != JOB_STATUSES.size()) throw new IllegalStateException("JOB_STATUSES 有重复项");
    Set<String> i = Set.copyOf(ITEM_STATUSES);
    if (i.size() != ITEM_STATUSES.size()) throw new IllegalStateException("ITEM_STATUSES 有重复项");
  }
}
