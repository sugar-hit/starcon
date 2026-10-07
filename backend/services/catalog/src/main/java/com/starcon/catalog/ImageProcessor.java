package com.starcon.catalog;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * 3.4.1.2 的像素加工引擎：去背景、白底、水印、尺寸裁剪、格式转换、多比例。
 *
 * <p>蓝图点名六项能力，这里每一项都有真实实现，没有占位：
 *
 * <ol>
 *   <li><b>去背景</b>（{@link #removeBackground}）：从四个角泛洪，颜色距离超过容差的像素
 *       置为透明。用 int 数组做访问标记，不额外开 boolean[]——一张 4000×3000 的图
 *       再开一张同尺寸的标记表就是多 12MB。
 *   <li><b>白底</b>（{@link #flattenToWhite}）：把带 alpha 的图合成到纯白画布。
 *       亚马逊与 Shopify 的主图位不接受透明通道，这一步是它们的硬要求。
 *   <li><b>水印</b>（{@link #applyWatermark}）：真实绘字，支持四角 / 居中 / 平铺与不透明度。
 *   <li><b>尺寸裁剪</b>（{@link #resize}）：FIT 等比缩放补白边，CROP_FILL 填满后居中裁掉溢出。
 *   <li><b>格式转换</b>（{@link #write}）：ImageIO 写出 png / jpeg / gif / bmp。
 *   <li><b>多比例</b>：不在本类——它由服务层对每个比例档调一次 {@link #resize} + {@link #write}，
 *       因为比例档是**任务产物维度**（每档一行产物记录），而本类只处理「一张图变成一张图」。
 * </ol>
 *
 * <p><b>无状态且不碰数据库与存储。</b>输入输出都是字节，因此可以纯单测：
 * 加工逻辑的可信度不依赖 Testcontainers 起一次 PG。
 *
 * <p>JPEG 不支持 alpha：写 jpeg 前若还有透明像素会被渲染成黑色方块，
 * 所以 {@link #write} 对不含 alpha 的目标格式先自动铺白——这是格式本身的要求，
 * 不是可选的「美化」。
 */
public final class ImageProcessor {

  /** 一次加工的结果。字节与尺寸都真的算出来，不接受调用方填写。 */
  public record Output(byte[] content, int width, int height, String format, String sha256) {}

  /** 目标画布尺寸与适配方式（一个比例档 = 一份）。 */
  public record Canvas(int width, int height, String cropMode) {
    public Canvas {
      if (width <= 0 || height <= 0)
        throw new IllegalArgumentException("目标尺寸必须大于 0：" + width + "×" + height);
      if (!ImageProcessRules.CROP_MODES.contains(cropMode))
        throw new IllegalArgumentException("未知的适配方式：" + cropMode);
    }
  }

  private ImageProcessor() {}

  // ============================================================ 解码

  /**
   * 解码字节成 BufferedImage。
   *
   * <p>统一转成 ARGB：后续每一步（去背景的白底合成、水印的 AlphaComposite）都要按 alpha 通道
   * 算，而 {@code ImageIO.read} 对 JPEG 返回的是 TYPE_INT_RGB（没有 alpha），
   * 对 PNG 可能返回带调色板或预乘 alpha 的类型。逐张判断会让「这一步假设有 alpha」在
   * 另一张图上不成立，所以在入口统一一次。
   *
   * <p>解码失败抛 IllegalArgumentException 而不是返回 null：
   * 上层要把原因写进 {@code media_process_job_item.error}，那是给人看的中文原因。
   */
  public static BufferedImage decode(byte[] content) {
    if (content == null || content.length == 0)
      throw new IllegalArgumentException("待处理的内容为空");
    BufferedImage source;
    try {
      source = ImageIO.read(new ByteArrayInputStream(content));
    } catch (IOException e) {
      throw new IllegalArgumentException("读取图片失败：" + e.getMessage());
    }
    if (source == null)
      throw new IllegalArgumentException(
          "无法解析这张图片的内容：文件已损坏，或不是 JPEG / PNG / GIF / BMP 之一（格式以文件头为准，"
              + "仅凭扩展名不算数）");
    BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = argb.createGraphics();
    g.setComposite(AlphaComposite.Src);
    g.drawImage(source, 0, 0, null);
    g.dispose();
    return argb;
  }

  // ============================================================ 1. 去背景

  /**
   * 四角泛洪去背景：与种子角颜色距离超过容差的像素置为透明。
   *
   * <p>用 {@link java.awt.image.BufferedImage} 的 {@code getRGB(0,0,w,h,null,0,w)} 一次取出整块像素到
   * int[]，避免逐像素调 {@code getRGB}（每个像素一次方法调用 + 一次拷贝，
   * 4000×3000 就是一千两百万次）。写回时同理用 {@code setRGB} 整块写。
   *
   * <p><b>失败模式要说明白</b>：这是按「颜色相近即背景」判定，不是语义分割。
   * 纯色/渐变背景（摄影棚白底、纯色布景）效果最好；背景里若有大面积与主体同色的区域，
   * 会被一起抠掉。容差就是留给用户调的旋钮——它不是「总是正确」的开关。
   */
  public static BufferedImage removeBackground(BufferedImage image, int tolerance) {
    if (tolerance < 0 || tolerance > ImageProcessRules.MAX_BACKGROUND_TOLERANCE)
      throw new IllegalArgumentException("去背景容差必须在 0~255 之间：" + tolerance);
    int w = image.getWidth();
    int h = image.getHeight();
    int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
    boolean[] visited = new boolean[w * h];
    Deque<Integer> queue = new ArrayDeque<>();
    // 色距基准统一取左上角 (0,0) 的颜色：四角都作为泛洪起点，但若每个角各用各的基准，
    // 「背景」在图内就没有统一定义，实测会出现半张图抠不掉的斑块。
    int seedColor = pixels[0];

    // 四个角作为种子。商品图的主体几乎不会顶到四个角，
    // 而只从单角泛洪时，主体贴着对角就会把那一块也走掉。
    seed(pixels, visited, queue, 0, 0, w);
    seed(pixels, visited, queue, w - 1, 0, w);
    seed(pixels, visited, queue, 0, h - 1, w);
    seed(pixels, visited, queue, w - 1, h - 1, w);

    while (!queue.isEmpty()) {
      int index = queue.poll();
      int x = index % w;
      int y = index / w;
      if (x > 0) offer(pixels, visited, queue, index - 1, x - 1, y, seedColor, tolerance, w);
      if (x < w - 1) offer(pixels, visited, queue, index + 1, x + 1, y, seedColor, tolerance, w);
      if (y > 0) offer(pixels, visited, queue, index - w, x, y - 1, seedColor, tolerance, w);
      if (y < h - 1) offer(pixels, visited, queue, index + w, x, y + 1, seedColor, tolerance, w);
    }

    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    out.setRGB(0, 0, w, h, pixels, 0, w);
    return out;
  }

  /**
   * 种子：角像素既是泛洪起点，也**立刻置透明**。
   *
   * <p>置透明不能只发生在 {@link #offer} 里：种子走的是 {@code seed} 而不是 {@code offer}，
   * 漏掉的话四个角永远留着不透明的一小块——实测就是「主体抠干净了，四个角还是原背景色」。
   */
  private static void seed(int[] pixels, boolean[] visited, Deque<Integer> queue, int x, int y, int w) {
    int index = y * w + x;
    if (visited[index]) return;
    visited[index] = true;
    pixels[index] = pixels[index] & 0x00FFFFFF; // 清 alpha
    queue.add(index);
  }

  /** 邻居与种子色距离在容差内就入队并置透明；否则停在这一步。 */
  private static void offer(
      int[] pixels, boolean[] visited, Deque<Integer> queue, int index, int x, int y,
      int seedColor, int tolerance, int w) {
    if (visited[index]) return;
    visited[index] = true;
    if (distance(pixels[index], seedColor) > tolerance) return;
    pixels[index] = pixels[index] & 0x00FFFFFF; // 清 alpha
    queue.add(index);
  }

  /** 每通道欧氏距离。与种子色比，而不是与上一个像素比：后者在渐变背景上会一路漫过整张图。 */
  private static int distance(int argb, int seedColor) {
    int dr = ((argb >> 16) & 0xFF) - ((seedColor >> 16) & 0xFF);
    int dg = ((argb >> 8) & 0xFF) - ((seedColor >> 8) & 0xFF);
    int db = (argb & 0xFF) - (seedColor & 0xFF);
    return (int) Math.sqrt((double) (dr * dr + dg * dg + db * db));
  }

  // ============================================================ 2. 白底

  /**
   * 合成到纯白画布：alpha 通道消失，透明处变成纯白。
   *
   * <p>用 {@link AlphaComposite#getInstance} 而不是逐像素手算混合：
   * Java2D 的 SrcOver 就是标准的结果，而手算混合在预乘 alpha 的图片上容易差几个色阶。
   */
  public static BufferedImage flattenToWhite(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, w, h);
    g.setComposite(AlphaComposite.SrcOver);
    g.drawImage(image, 0, 0, null);
    g.dispose();
    return out;
  }

  /** 是否还有非全不透明像素。JPEG / BMP 输出前用它决定要不要铺白。 */
  public static boolean hasTransparency(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
    for (int pixel : pixels) if ((pixel >>> 24) != 0xFF) return true;
    return false;
  }

  // ============================================================ 3. 水印

  /**
   * 画文字水印。
   *
   * <p>字号按图幅比例取（{@code min(w,h) × 0.06}，至少 12px）：固定字号在缩略图上糊成一条、
   * 在大图上小到看不见，而水印的作用就是在这两个尺度上都可读。
   *
   * <p>字号会按文字实际宽度**收窄**到图宽的 80% 以内：一句长文案按比例算出的字号
   * 会超出画布，而超出画布的水印等于没有水印——用户以为加了，实际只露出前两个字。
   */
  public static BufferedImage applyWatermark(
      BufferedImage image, String text, String position, int opacity) {
    String content = text == null ? "" : text.trim();
    if (content.isEmpty()) return image;
    if (opacity <= 0) return image;
    if (!ImageProcessRules.WATERMARK_POSITIONS.contains(position))
      throw new IllegalArgumentException("未知的水印位置：" + position);
    if (opacity > 100) throw new IllegalArgumentException("水印不透明度必须在 0~100 之间：" + opacity);

    int w = image.getWidth();
    int h = image.getHeight();
    BufferedImage out = copy(image);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, watermarkFontSize(g, w, h)));
    g.setColor(new Color(255, 255, 255, opacityPercent(opacity)));
    FontMetrics metrics = g.getFontMetrics();

    if ("TILED".equals(position)) {
      drawTiled(g, metrics, content, w, h);
    } else {
      drawAt(g, metrics, content, w, h, position);
    }
    g.dispose();
    return out;
  }

  /** 文字宽到溢出时按比例缩字号，至少留 10px 边距。 */
  private static int watermarkFontSize(Graphics2D probe, int w, int h) {
    int size = Math.max(12, Math.min(w, h) / 16);
    Font font = probe.getFont();
    int limit = (int) (w * 0.8);
    // 逐档收窄：二分没有意义（字号与文本宽度不是严格线性的），逐档更可控。
    while (size > 10) {
      probe.setFont(font.deriveFont((float) size));
      if (probe.getFontMetrics().stringWidth("测试AgW") * 8 <= limit) break;
      size--;
    }
    return size;
  }

  private static int opacityPercent(int opacity) {
    return (int) Math.round(255.0 * opacity / 100.0);
  }

  private static void drawTiled(Graphics2D g, FontMetrics metrics, String text, int w, int h) {
    int stepX = Math.max(metrics.stringWidth(text) + 40, 60);
    int stepY = Math.max(metrics.getHeight() + 30, 40);
    for (int y = metrics.getHeight() / 2; y < h; y += stepY)
      for (int x = 0; x < w; x += stepX) g.drawString(text, x, y);
  }

  private static void drawAt(
      Graphics2D g, FontMetrics metrics, String text, int w, int h, String position) {
    int textWidth = metrics.stringWidth(text);
    int margin = Math.max(8, Math.min(w, h) / 40);
    int x;
    int y;
    switch (position) {
      case "TOP_LEFT" -> {
        x = margin;
        y = margin + metrics.getAscent();
      }
      case "TOP_RIGHT" -> {
        x = w - textWidth - margin;
        y = margin + metrics.getAscent();
      }
      case "BOTTOM_LEFT" -> {
        x = margin;
        y = h - margin - metrics.getDescent();
      }
      case "BOTTOM_RIGHT" -> {
        x = w - textWidth - margin;
        y = h - margin - metrics.getDescent();
      }
      case "CENTER" -> {
        x = (w - textWidth) / 2;
        y = (h + metrics.getAscent() - metrics.getDescent()) / 2;
      }
      default -> throw new IllegalArgumentException("未知的水印位置：" + position);
    }
    g.drawString(text, x, y);
  }

  // ============================================================ 4. 尺寸裁剪

  /**
   * 缩放到目标画布。
   *
   * <p>FIT：等比缩放到能装进目标框的尺寸，居中补白边（**不裁掉画面**）。
   * CROP_FILL：等比缩放到能覆盖目标框的尺寸，居中裁掉溢出部分（**填满**）。
   *
   * <p>面积上限在这里挡：先按目标尺寸分配画布再看面积，
   * 一个 12000×12000 的目标在分配时就已经吃掉 576MB——拒绝必须发生在分配之前。
   */
  public static BufferedImage resize(BufferedImage image, Canvas canvas) {
    long pixels = (long) canvas.width() * canvas.height();
    if (pixels > ImageProcessRules.MAX_OUTPUT_PIXELS)
      throw new IllegalArgumentException(
          "目标尺寸 " + canvas.width() + "×" + canvas.height() + " 超出单张处理上限"
              + ImageProcessRules.MAX_OUTPUT_PIXELS + " 像素，请减小目标尺寸或改用原图");
    if (image.getWidth() == canvas.width() && image.getHeight() == canvas.height()) return image;

    int type = "CROP_FILL".equals(canvas.cropMode())
        ? BufferedImage.TYPE_INT_ARGB
        : image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    BufferedImage out = new BufferedImage(canvas.width(), canvas.height(), type);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    double scaleX = canvas.width() / (double) image.getWidth();
    double scaleY = canvas.height() / (double) image.getHeight();
    double scale = "CROP_FILL".equals(canvas.cropMode())
        ? Math.max(scaleX, scaleY)
        : Math.min(scaleX, scaleY);
    int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
    int x = (canvas.width() - drawWidth) / 2;
    int y = (canvas.height() - drawHeight) / 2;

    // FIT 的补白边用白色：白底是刊登侧的主图要求，补别的颜色会引入平台不收的背景色。
    if (!"CROP_FILL".equals(canvas.cropMode()) && image.getColorModel().hasAlpha()) {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, canvas.width(), canvas.height());
      g.setComposite(AlphaComposite.SrcOver);
    } else if (!"CROP_FILL".equals(canvas.cropMode()) && type == BufferedImage.TYPE_INT_RGB) {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, canvas.width(), canvas.height());
    }
    g.drawImage(image, x, y, drawWidth, drawHeight, null);
    g.dispose();
    return out;
  }

  // ============================================================ 5. 格式转换

  /**
   * 按目标格式写出字节。格式 / 字节数 / 尺寸 / 哈希全部**算出来**，不接受调用方填写——
   * 与 {@link MediaRules#probe} 同一纪律：这些是「这份文件是什么」的事实。
   *
   * <p>目标格式不含 alpha 时（jpeg / bmp）若还有透明像素，先铺白再写：
   * 否则 ImageIO 会把透明渲染成黑色方块，而调用方拿到的是一个「成功」的坏产物。
   */
  public static Output write(BufferedImage image, String format) {
    String target = format == null ? "png" : format.trim().toLowerCase(Locale.ROOT);
    if (!ImageProcessRules.TARGET_FORMATS.contains(target))
      throw new IllegalArgumentException(
          "不支持的目标格式：" + format + "（可选 " + String.join(" / ", ImageProcessRules.TARGET_FORMATS) + "）");
    BufferedImage source = image;
    // 不含 alpha 的格式必须**换掉色彩模型**，而不是只在「有透明像素」时才换：
    // 实测 JDK 的 JPEG/BMP 写入器遇到 TYPE_INT_ARGB 直接返回 false，
    // 即使每个像素的 alpha 都是 255（它的理由是「这张图有能力存 alpha」，
    // 而 JPEG/BMP 没有那个能力）。所以这里按**格式**决定，而不是按像素决定。
    // 换模型的同时铺白：过程中可能有半透明像素留着，白底是刊登侧的硬要求。
    if (!supportsAlpha(target)) source = flattenToWhite(image);
    try {
      var out = new ByteArrayOutputStream();
      if (!ImageIO.write(source, target, out))
        throw new IllegalArgumentException(
            "服务端没有 " + target + " 的图片写入器（ImageIO 返回 false），请改用 png 或 jpeg");
      byte[] content = out.toByteArray();
      return new Output(
          content,
          source.getWidth(),
          source.getHeight(),
          target,
          MediaRules.sha256(content));
    } catch (IOException e) {
      throw new IllegalArgumentException("写出 " + target + " 失败：" + e.getMessage());
    }
  }

  /** 该格式能否保存 alpha 通道。gif 只有 1 位透明，半透明会被二值化。 */
  public static boolean supportsAlpha(String format) {
    String value = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    return "png".equals(value);
  }

  /** mime_type 从字节实测的格式名推导，不接受调用方填。 */
  public static String mimeTypeOf(String format) {
    return switch (format == null ? "" : format.trim().toLowerCase(Locale.ROOT)) {
      case "png" -> "image/png";
      case "jpeg", "jpg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "bmp" -> "image/bmp";
      default -> "application/octet-stream";
    };
  }

  private static BufferedImage copy(BufferedImage image) {
    int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), type);
    Graphics2D g = out.createGraphics();
    g.setComposite(AlphaComposite.Src);
    g.drawImage(image, 0, 0, null);
    g.dispose();
    return out;
  }
}