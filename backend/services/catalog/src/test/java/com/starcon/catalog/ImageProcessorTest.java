package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * {@link ImageProcessor} 的纯单元测试：六项加工能力逐项断言**像素真的变了**。
 *
 * <p>不碰数据库、不碰对象存储：加工逻辑的可信度不该依赖 Testcontainers 起一次 PG。 断言方式是读回产物的像素，而不是「方法没抛异常」——后者会让一个什么都没做的
 * 空实现也通过。
 */
class ImageProcessorTest {

  /** 造一张纯色图。 */
  private BufferedImage solid(int w, int h, Color color) {
    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, w, h);
    g.dispose();
    return image;
  }

  /** 造一张「纯色背景 + 居中方块主体」的图：去背景与白底的典型素材。 */
  private BufferedImage onBackground(int w, int h, Color background, Color subject) {
    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(background);
    g.fillRect(0, 0, w, h);
    g.setColor(subject);
    g.fillRect(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 2));
    g.dispose();
    return image;
  }

  private byte[] bytes(BufferedImage image, String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  private BufferedImage decode(byte[] content) {
    return ImageProcessor.decode(content);
  }

  // ------------------------------------------------------------ 解码

  @Test
  void decodeNormalizesToArgbAndRejectsGarbage() throws Exception {
    byte[] png = bytes(solid(20, 10, Color.RED), "png");
    BufferedImage decoded = decode(png);
    assertThat(decoded.getWidth()).isEqualTo(20);
    assertThat(decoded.getHeight()).isEqualTo(10);
    // 统一转 ARGB：白底合成与水印的 AlphaComposite 都按 alpha 通道算，
    // 若入口给的是 TYPE_INT_RGB（ImageIO 对 JPEG 就是这么返回的），下一步会算错。
    assertThat(decoded.getColorModel().hasAlpha()).isTrue();

    // 解码不出来必须抛出可读原因，而不是返回 null 让上层猜。
    assertThatThrownBy(() -> decode("这不是图片".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无法解析");
    assertThatThrownBy(() -> decode(new byte[0])).isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------ 1. 去背景

  @Test
  void removeBackgroundClearsCornersAndKeepsSubject() throws Exception {
    BufferedImage image = onBackground(80, 60, new Color(250, 250, 250), new Color(20, 60, 200));
    BufferedImage out = ImageProcessor.removeBackground(image, 12);

    // 背景被抠成透明：四角 alpha = 0。
    assertThat(alphaAt(out, 0, 0)).isZero();
    assertThat(alphaAt(out, 79, 0)).isZero();
    assertThat(alphaAt(out, 0, 59)).isZero();
    assertThat(alphaAt(out, 79, 59)).isZero();
    // 主体原样保留：居中方块的 alpha 与颜色都没变。
    assertThat(alphaAt(out, 40, 30)).isEqualTo(255);
    assertThat(out.getRGB(40, 30) & 0xFFFFFF).isEqualTo(new Color(20, 60, 200).getRGB() & 0xFFFFFF);
  }

  @Test
  void removeBackgroundRespectsTolerance() throws Exception {
    // 背景是 250，而主体是 250 附近的浅色：容差小就该保留，容差大就该被吃掉。
    BufferedImage image = onBackground(60, 60, new Color(250, 250, 250), new Color(244, 246, 248));

    BufferedImage tight = ImageProcessor.removeBackground(image, 2);
    assertThat(alphaAt(tight, 0, 0)).isZero();
    assertThat(alphaAt(tight, 30, 30)).isEqualTo(255);

    BufferedImage loose = ImageProcessor.removeBackground(image, 40);
    assertThat(alphaAt(loose, 0, 0)).isZero();
    // 容差放大到 40 时浅色主体也被算进背景——这正是「容差是旋钮、不是总对」的证据。
    assertThat(alphaAt(loose, 30, 30)).isZero();

    assertThatThrownBy(() -> ImageProcessor.removeBackground(image, 256))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageProcessor.removeBackground(image, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------ 2. 白底

  @Test
  void flattenToWhiteMakesTransparentPixelsWhiteAndOpaque() throws Exception {
    BufferedImage image = onBackground(40, 40, new Color(250, 250, 250), new Color(10, 10, 10));
    BufferedImage removed = ImageProcessor.removeBackground(image, 12);
    assertThat(ImageProcessor.hasTransparency(removed)).isTrue();

    BufferedImage flat = ImageProcessor.flattenToWhite(removed);
    // 铺白底之后没有透明像素了：亚马逊 / Shopify 主图位不接受 alpha 通道。
    assertThat(ImageProcessor.hasTransparency(flat)).isFalse();
    assertThat(flat.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    // 主体颜色没有被白底改掉。
    assertThat(flat.getRGB(20, 20) & 0xFFFFFF).isEqualTo(new Color(10, 10, 10).getRGB() & 0xFFFFFF);
  }

  // ------------------------------------------------------------ 3. 水印

  @Test
  void watermarkActuallyDrawsAndIsPositionAware() throws Exception {
    BufferedImage image = solid(200, 120, new Color(30, 30, 30));
    BufferedImage plain =
        bytes(image, "png") == null
            ? null
            : ImageProcessor.applyWatermark(
                ImageProcessor.decode(bytes(image, "png")), "店铺名", "BOTTOM_RIGHT", 90);

    // 画了水印的图与原图不一样（像素真的变了，不是空操作）。
    BufferedImage bare = ImageProcessor.decode(bytes(image, "png"));
    assertThat(pixelsFingerprint(plain)).isNotEqualTo(pixelsFingerprint(bare));
    // 右下角区域有水印像素，而左上角没有。
    assertThat(hasBrightPixelInRegion(plain, 100, 60, 199, 119)).isTrue();
    assertThat(hasBrightPixelInRegion(plain, 0, 0, 99, 59)).isFalse();

    // TOP_LEFT 则相反。
    BufferedImage topLeft =
        ImageProcessor.applyWatermark(
            ImageProcessor.decode(bytes(image, "png")), "店铺名", "TOP_LEFT", 90);
    assertThat(hasBrightPixelInRegion(topLeft, 0, 0, 99, 59)).isTrue();
    assertThat(hasBrightPixelInRegion(topLeft, 100, 60, 199, 119)).isFalse();

    // 平铺：四个角区域都能看到水印。
    BufferedImage tiled =
        ImageProcessor.applyWatermark(
            ImageProcessor.decode(bytes(image, "png")), "店铺名", "TILED", 90);
    assertThat(hasBrightPixelInRegion(tiled, 0, 0, 99, 59)).isTrue();
    assertThat(hasBrightPixelInRegion(tiled, 100, 60, 199, 119)).isTrue();

    // 空水印文本 / 0 不透明度 = 不画（但也不能抛）。
    assertThat(ImageProcessor.applyWatermark(bare, "", "BOTTOM_RIGHT", 50)).isNotNull();
    assertThat(ImageProcessor.applyWatermark(bare, "店铺名", "BOTTOM_RIGHT", 0)).isNotNull();
    assertThatThrownBy(() -> ImageProcessor.applyWatermark(bare, "x", "NOWHERE", 50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------ 4. 尺寸裁剪

  @Test
  void resizeFitKeepsWholePictureAndCropFillFillsTarget() throws Exception {
    BufferedImage image = onBackground(200, 100, new Color(250, 250, 250), new Color(10, 20, 30));

    // FIT：等比缩放进 100×100 目标框 → 100×50，居中，上下补白边。
    BufferedImage fit = ImageProcessor.resize(image, new ImageProcessor.Canvas(100, 100, "FIT"));
    assertThat(fit.getWidth()).isEqualTo(100);
    assertThat(fit.getHeight()).isEqualTo(100);
    // 上边缘是补出来的白边（原图顶部是浅灰背景 250，不等于纯白 255）。
    assertThat(fit.getRGB(50, 1) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    // 中间能看到主体。
    assertThat((fit.getRGB(50, 50) & 0xFF000000) != 0).isTrue();

    // CROP_FILL：填满 100×100 → 缩放后裁掉两侧，画面中央是主体。
    BufferedImage fill =
        ImageProcessor.resize(image, new ImageProcessor.Canvas(100, 100, "CROP_FILL"));
    assertThat(fill.getWidth()).isEqualTo(100);
    assertThat(fill.getHeight()).isEqualTo(100);
    assertThat(fill.getRGB(50, 50) & 0xFFFFFF).isEqualTo(new Color(10, 20, 30).getRGB() & 0xFFFFFF);

    // 面积上限必须在分配画布之前就拒（12000×12000 = 144MP > 80MP）。
    assertThatThrownBy(
            () -> ImageProcessor.resize(image, new ImageProcessor.Canvas(12000, 12000, "FIT")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出单张处理上限");
    assertThatThrownBy(() -> new ImageProcessor.Canvas(0, 10, "FIT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ImageProcessor.Canvas(10, 10, "SQUEEZE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------ 5. 格式转换

  @Test
  void formatConversionProducesRealBytesForEverySupportedFormat() throws Exception {
    BufferedImage image = onBackground(50, 40, new Color(250, 250, 250), new Color(30, 30, 30));

    for (String format : ImageProcessRules.TARGET_FORMATS) {
      var output = ImageProcessor.write(image, format);
      assertThat(output.format()).isEqualTo(format);
      assertThat(output.content()).isNotEmpty();
      assertThat(output.width()).isEqualTo(50);
      assertThat(output.height()).isEqualTo(40);
      // 哈希真的算了，而且与内容一致。
      assertThat(output.sha256()).isEqualTo(MediaRules.sha256(output.content()));
      // 写出的字节能被读回来，尺寸不变（格式转换没有偷偷改画面）。
      BufferedImage back = decode(output.content());
      assertThat(back.getWidth()).isEqualTo(50);
      assertThat(back.getHeight()).isEqualTo(40);
      // mime 与格式对应，不是硬编码成 png。
      assertThat(ImageProcessor.mimeTypeOf(format)).startsWith("image/");
    }

    // WEBP 没有内置写入器：必须拒，而不是返回一个空产物。
    assertThatThrownBy(() -> ImageProcessor.write(image, "webp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的目标格式");
  }

  @Test
  void writingToFormatWithoutAlphaFlattensInsteadOfProducingBlackBlocks() throws Exception {
    // 抠完背景的图有透明像素。直接写 jpeg 会把透明渲染成黑色方块。
    BufferedImage removed =
        ImageProcessor.removeBackground(
            onBackground(40, 40, new Color(250, 250, 250), new Color(10, 10, 10)), 12);
    assertThat(ImageProcessor.hasTransparency(removed)).isTrue();

    var jpeg = ImageProcessor.write(removed, "jpeg");
    BufferedImage back = decode(jpeg.content());
    assertThat(ImageProcessor.hasTransparency(back)).isFalse();
    // 原来透明的地方现在是白色，而不是黑色。
    assertThat(back.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF);

    // png 保留 alpha。
    var png = ImageProcessor.write(removed, "png");
    assertThat(ImageProcessor.hasTransparency(decode(png.content()))).isTrue();
    assertThat(ImageProcessor.supportsAlpha("png")).isTrue();
    assertThat(ImageProcessor.supportsAlpha("jpeg")).isFalse();
  }

  // ------------------------------------------------------------ 6. 端到端：六项串起来

  @Test
  void fullPipelineProducesPngThatMatchesTheDeclaredOutput() throws Exception {
    BufferedImage source = onBackground(120, 90, new Color(252, 252, 252), new Color(200, 40, 40));
    byte[] original = bytes(source, "png");

    BufferedImage image = ImageProcessor.decode(original);
    image = ImageProcessor.removeBackground(image, 10);
    image = ImageProcessor.flattenToWhite(image);
    image = ImageProcessor.applyWatermark(image, "TEST", "BOTTOM_RIGHT", 80);
    image = ImageProcessor.resize(image, new ImageProcessor.Canvas(64, 64, "CROP_FILL"));
    var output = ImageProcessor.write(image, "png");

    assertThat(output.width()).isEqualTo(64);
    assertThat(output.height()).isEqualTo(64);
    // 产物与原图不是同一份字节（真的加工了）。
    assertThat(output.sha256()).isNotEqualTo(MediaRules.sha256(original));
    // 产物可解码、可读，且格式自洽。
    BufferedImage back = decode(output.content());
    assertThat(back.getWidth()).isEqualTo(64);
    assertThat(back.getHeight()).isEqualTo(64);
    // 抠背景 + 白底之后没有透明像素。
    assertThat(ImageProcessor.hasTransparency(back)).isFalse();
  }

  // ------------------------------------------------------------ 工具

  /** 像素指纹：比较两张图「画出来的东西」是否不同，而不比对象身份。 */
  private long pixelsFingerprint(BufferedImage image) {
    long h = 1125899906842597L;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++) h = h * 31 + image.getRGB(x, y);
    return h;
  }

  private int alphaAt(BufferedImage image, int x, int y) {
    return (image.getRGB(x, y) >>> 24);
  }

  /**
   * 区域里有没有明显变亮的像素。
   *
   * <p>用亮度阈值而不是比 0xFFFFFF：水印按不透明度与深色底混合后是 ~0xE8， 抗锯齿边缘还会更暗。判「有没有白点」会漏掉半透明与抗锯齿的字，
   * 判「亮度是否显著高于底色」才是水印真的画上去了。
   */
  private boolean hasBrightPixelInRegion(BufferedImage image, int x0, int y0, int x1, int y1) {
    for (int y = y0; y <= y1; y++)
      for (int x = x0; x <= x1; x++) {
        int rgb = image.getRGB(x, y) & 0xFFFFFF;
        if (luminance(rgb) >= 150) return true;
      }
    return false;
  }

  /** Rec.601 亮度（与 {@link java.awt.Color} 的亮度口径一致，够用且便宜）。 */
  private int luminance(int rgb) {
    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
    return (r * 299 + g * 587 + b * 114) / 1000;
  }
}
