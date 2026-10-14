package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.starcon.catalog.MediaRules.Probe;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.1.1 媒体库的纯规则单测：词表一致性、内容探测、存储键、校验出口。
 *
 * <p>这些是「服务层拒绝时给出的话」与「前端表单校验」共用的同一份实现， 单测钉住边界比在集成测试里绕一圈更快也更全（集成测试只覆盖用得上的那几个）。
 */
class MediaRulesTest {

  // ------------------------------------------------------------ 词表

  @Test
  void vocabulariesHaveNoDuplicatesAndLabelsCoverEveryMember() {
    assertThat(MediaRules.MEDIA_TYPES).containsExactly("IMAGE", "VIDEO");
    assertThat(MediaRules.PURPOSES)
        .containsExactly("PRIMARY", "GALLERY", "DETAIL", "VIDEO", "OTHER");
    for (String value : MediaRules.MEDIA_TYPES)
      assertThat(MediaRules.mediaTypeLabel(value)).as(value).isNotEqualTo(value);
    for (String value : MediaRules.PURPOSES)
      assertThat(MediaRules.purposeLabel(value)).as(value).isNotEqualTo(value);
    for (String value : MediaRules.INTEGRITY_STATUSES)
      assertThat(MediaRules.integrityLabel(value)).as(value).isNotEqualTo(value);
    // UNKNOWN 没有可判定的结论，不算「有问题」。
    assertThat(MediaRules.integrityFailing("UNKNOWN")).isFalse();
    assertThat(MediaRules.integrityFailing("VERIFIED")).isFalse();
    assertThat(MediaRules.integrityFailing("MISSING")).isTrue();
    assertThat(MediaRules.integrityFailing("SIZE_MISMATCH")).isTrue();
    assertThat(MediaRules.integrityFailing("CONTENT_MISMATCH")).isTrue();
  }

  // ------------------------------------------------------------ 内容探测

  @Test
  void pngIsProbedFromBytesNotFromTheFilename() {
    Probe probe = MediaRules.probe(png(64, 32, new Color(1, 2, 3)), "IMAGE", "完全不相关的名字.dat");
    assertThat(probe.mediaType()).isEqualTo("IMAGE");
    assertThat(probe.fileFormat()).isEqualTo("png");
    assertThat(probe.mimeType()).isEqualTo("image/png");
    assertThat(probe.width()).isEqualTo(64);
    assertThat(probe.height()).isEqualTo(32);
    // 同一个字节永远是同一个哈希：内容寻址的锚点。
    assertThat(probe.sha256()).isEqualTo(MediaRules.sha256(png(64, 32, new Color(1, 2, 3))));
    assertThat(probe.sha256()).hasSize(64);
  }

  @Test
  void jpegIsDetectedByItsSignature() {
    byte[] jpeg = jpeg(20, 10);
    Probe probe = MediaRules.probe(jpeg, "IMAGE", "photo.png");
    // 扩展名说 png，字节说 jpeg：以字节为准。
    assertThat(probe.fileFormat()).isEqualTo("jpeg");
    assertThat(probe.mimeType()).isEqualTo("image/jpeg");
  }

  @Test
  void webpIsRejectedWithAnActionableReasonInsteadOfAGuess() {
    byte[] webp = new byte[32];
    System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
    System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
    var error =
        assertThrows(
            ResponseStatusException.class, () -> MediaRules.probe(webp, "IMAGE", "photo.webp"));
    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // 原因要能执行：说清为什么不行、要做什么。
    assertThat(error.getMessage()).contains("WEBP").contains("PNG");
  }

  @Test
  void videoIsAcceptedWithoutGuessedDimensions() {
    // 一个最小的 MP4 容器头（ftyp box）。
    byte[] mp4 = new byte[24];
    System.arraycopy(new byte[] {0, 0, 0, 24}, 0, mp4, 0, 4);
    System.arraycopy("ftypisom".getBytes(), 0, mp4, 4, 8);
    Probe probe = MediaRules.probe(mp4, "VIDEO", "clip.mp4");
    assertThat(probe.mediaType()).isEqualTo("VIDEO");
    assertThat(probe.mimeType()).isEqualTo("video/mp4");
    // 宽高与时长**留空**而不是填 0 或从文件名猜的值。
    assertThat(probe.width()).isNull();
    assertThat(probe.height()).isNull();
    assertThat(probe.note()).contains("不做探测").contains("猜");
  }

  @Test
  void unsupportedContentEmptyAndOversizedAreRejected() {
    assertThatThrownBy(() -> MediaRules.probe("文本".getBytes(), "IMAGE", "a.txt"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> MediaRules.probe(new byte[0], "IMAGE", "a.png"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> MediaRules.probe(null, "IMAGE", "a.png"))
        .isInstanceOf(ResponseStatusException.class);

    // 超大文件在探测前就被拒：先收 500MB 进内存再报错就是把误操作放大成 OOM。
    var tooBig = new byte[(int) MediaRules.MAX_UPLOAD_BYTES + 1];
    assertThatThrownBy(() -> MediaRules.probe(tooBig, "IMAGE", "big.png"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("MiB");
  }

  // ------------------------------------------------------------ 存储键

  @Test
  void storageKeyCarriesTheTenantSegmentAndRejectsTraversal() {
    UUID tenant = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, ZoneOffset.UTC);
    String key = MediaRules.storageKey(tenant, now, "png");
    assertThat(key).startsWith("media/" + tenant + "/202610/");
    assertThat(key).endsWith(".png");
    // 同一个租户两次生成的键不同：内容相同应该复用素材，而不是复用键。
    assertThat(MediaRules.storageKey(tenant, now, "png")).isNotEqualTo(key);
    // 空扩展名兜底，不生成「以点结尾」的文件名。
    assertThat(MediaRules.storageKey(tenant, now, "")).endsWith(".bin");
    assertThat(MediaRules.storageKey(tenant, now, null)).endsWith(".bin");

    assertThat(MediaRules.storageKeyProblem(null)).isNotNull();
    assertThat(MediaRules.storageKeyProblem("  ")).isNotNull();
    assertThat(MediaRules.storageKeyProblem("/etc/passwd")).contains("绝对路径");
    assertThat(MediaRules.storageKeyProblem("media/../../etc/passwd")).contains("..");
    assertThat(MediaRules.storageKeyProblem("media\\a.png")).contains("反斜杠");
    assertThat(MediaRules.storageKeyProblem(key)).isNull();
  }

  // ------------------------------------------------------------ 校验

  @Test
  void validationRejectsOutOfVocabularyValues() {
    assertThat(MediaRules.nameProblem(null)).contains("不能为空");
    assertThat(MediaRules.nameProblem("  ")).contains("不能为空");
    assertThat(MediaRules.nameProblem("x".repeat(201))).contains("200");
    assertThat(MediaRules.nameProblem("正常名称")).isNull();

    assertThat(MediaRules.mediaTypeProblem("AUDIO")).contains("IMAGE");
    assertThat(MediaRules.mediaTypeProblem(null)).isNotNull();
    assertThat(MediaRules.mediaTypeProblem("IMAGE")).isNull();

    assertThat(MediaRules.purposeProblem("BANNER")).contains("用途");
    assertThat(MediaRules.purposeProblem("PRIMARY")).isNull();

    assertThat(MediaRules.tagCodeProblem("中文编码")).isNotNull();
    assertThat(MediaRules.tagCodeProblem("ok-1")).isNull();
    assertThat(MediaRules.tagCodeProblem("x".repeat(33))).isNotNull();
    assertThat(MediaRules.tagNameProblem("")).isNotNull();
    assertThat(MediaRules.tagNameProblem("白底")).isNull();

    assertThat(MediaRules.tagsSizeProblem(51)).isNotNull();
    assertThat(MediaRules.tagsSizeProblem(50)).isNull();
  }

  @Test
  void referenceShapeIsEnforced() {
    UUID p = UUID.randomUUID(), k = UUID.randomUUID();
    assertThat(MediaRules.referenceProblem("SPU", p, null)).isNull();
    assertThat(MediaRules.referenceProblem("SKU", null, k)).isNull();
    assertThat(MediaRules.referenceProblem("SPU", null, null)).contains("spuId");
    assertThat(MediaRules.referenceProblem("SPU", p, k)).contains("只能指向一个对象");
    assertThat(MediaRules.referenceProblem("SKU", p, k)).contains("只能指向一个对象");
    // listing 引用刻意不在这里：04 刊登交付后走 event 写入。
    assertThat(MediaRules.referenceProblem("LISTING", null, null)).contains("04 刊登");
  }

  @Test
  void normalizersTrimAndUpper() {
    assertThat(MediaRules.normalize("  a  ")).isEqualTo("a");
    assertThat(MediaRules.normalize(null)).isEmpty();
    assertThat(MediaRules.normalizeUpper(" prImary ")).isEqualTo("PRIMARY");
  }

  // ------------------------------------------------------------ 夹具

  byte[] png(int width, int height, Color color) {
    try {
      var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(color);
      g.fillRect(0, 0, width, height);
      g.dispose();
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  byte[] jpeg(int width, int height) {
    try {
      var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(new Color(9, 8, 7));
      g.fillRect(0, 0, width, height);
      g.dispose();
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "jpeg", out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
