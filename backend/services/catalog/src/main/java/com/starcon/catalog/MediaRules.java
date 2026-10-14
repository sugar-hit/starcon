package com.starcon.catalog;

import com.starcon.tenancy.Ids;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.1.1 媒体库的纯规则层：校验、枚举词表、内容探测、存储键生成。
 *
 * <p>刻意做成**无状态静态方法**（与 {@link BrandRules} / {@link UnitGroupRules} 同一形状）：
 * 这些规则要在三个地方一致——服务层拒绝时给出的话、前端表单校验、以及集成测试断言的 边界。放进一个可单测的纯类里，三处引同一份实现；复制到浏览器里的那份必然漂移 （详见 {@code
 * brand-model.ts} 头部对「刊登判定不能在前端重算」的同一理由）。
 *
 * <p><b>媒体元数据一律从字节探测，不接受调用方填写。</b>格式、MIME、字节数、宽高、 时长、哈希全部由 {@link #probe}
 * 从上传的字节里算出来。理由：这些是「这份文件是什么」 的客观事实，让前端或接口调用方填就等于允许它声称「一个 1KB 的 jpg 有 4000×3000」，
 * 而刊登侧会拿这个尺寸去校验平台规格（3.4.1.2 的职责），错的尺寸会静默导致刊登失败。
 */
public final class MediaRules {
  private MediaRules() {}

  // ============================================================ 词表（与 V18 的 CHECK 逐字一致）

  public static final List<String> MEDIA_TYPES = List.of("IMAGE", "VIDEO");
  public static final List<String> PURPOSES =
      List.of("PRIMARY", "GALLERY", "DETAIL", "VIDEO", "OTHER");
  public static final List<String> INTEGRITY_STATUSES =
      List.of("UNKNOWN", "VERIFIED", "MISSING", "SIZE_MISMATCH", "CONTENT_MISMATCH");

  /** 可删除的完整性结果：UNKNOWN 只存在于「还没验过」，没有可判定的结论。 */
  public static final List<String> CHECKED_STATUSES =
      List.of("VERIFIED", "MISSING", "SIZE_MISMATCH", "CONTENT_MISMATCH");

  private static final java.util.Map<String, String> MEDIA_TYPE_LABELS =
      java.util.Map.of("IMAGE", "图片", "VIDEO", "视频");
  private static final java.util.Map<String, String> PURPOSE_LABELS =
      java.util.Map.of(
          "PRIMARY", "主图", "GALLERY", "附图", "DETAIL", "详情图", "VIDEO", "视频", "OTHER", "其他");
  private static final java.util.Map<String, String> INTEGRITY_LABELS =
      java.util.Map.of(
          "UNKNOWN", "未校验",
          "VERIFIED", "已校验",
          "MISSING", "文件缺失",
          "SIZE_MISMATCH", "大小不符",
          "CONTENT_MISMATCH", "内容不符");

  public static String mediaTypeLabel(String value) {
    return MEDIA_TYPE_LABELS.getOrDefault(value, value);
  }

  public static String purposeLabel(String value) {
    return PURPOSE_LABELS.getOrDefault(value, value);
  }

  public static String integrityLabel(String value) {
    return INTEGRITY_LABELS.getOrDefault(value, value);
  }

  /** 失效检测结果是否属于「有问题」的一类（概览的治理缺口指标用它）。 */
  public static boolean integrityFailing(String status) {
    return "MISSING".equals(status)
        || "SIZE_MISMATCH".equals(status)
        || "CONTENT_MISMATCH".equals(status);
  }

  // ============================================================ 上传大小上限

  /**
   * 单文件上限 50 MiB。
   *
   * <p>取 50 MiB 是因为主流平台的商品视频上限普遍在 100 MB 以内， 而更大的文件在上传前就该被拒：先收 500MB 进内存再报错， 是把一次用户的误操作放大成一次 OOM。
   */
  public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

  // ============================================================ 内容探测

  /**
   * 一次探测的结果。全部字段来自字节本身，没有一个是调用方填的。
   *
   * <p><b>没有时长字段</b>：视频时长需要解容器（读 moov/mvhd 或 EBML Timecode）， JDK 不提供。与其写一个只覆盖 MP4 的半吊子解析器（对 WEBM
   * 会给出错的秒数， 而刊登侧会拿它算视频长度限制），不如不探测——{@code duration_seconds} 留空， 在库里的语义是「未知」，不是 0 秒。需要时长的由 3.4.1.2
   * 在转码时算出。
   */
  public record Probe(
      String mediaType,
      String fileFormat,
      String mimeType,
      long byteSize,
      Integer width,
      Integer height,
      String sha256,
      String note) {}

  /**
   * 从字节探测媒体元数据。
   *
   * <p>探测不出来就**抛 400**，不返回带空的 Probe 让上层「猜一个类型」—— 猜出来的类型会让错误在刊登那天才暴露。
   *
   * <p>宽高来自 {@link ImageIO}（PNG / JPEG / GIF / BMP 等 JDK 内置读取器）； 视频不探测宽高（那需要解容器与轨道矩阵，JDK 没有），
   * 时长也不探测：宁可留空，也不填一个从文件名猜的秒数。
   */
  public static Probe probe(byte[] content, String declaredMediaType, String filename) {
    if (content == null || content.length == 0) throw bad("上传的内容为空，请确认文件已正确选择");
    if (content.length > MAX_UPLOAD_BYTES)
      throw bad(
          "文件超过 "
              + (MAX_UPLOAD_BYTES / 1024 / 1024)
              + " MiB 上限（当前 "
              + (content.length / 1024 / 1024)
              + " MiB）。请压缩后再上传");
    String sha = sha256(content);
    String ext = extension(filename);

    if (looksLikeImage(content)) {
      String signature = imageSignature(content);
      // WEBP 的尺寸在 VP8/VP8L/VP8X 头里，位置随变体而变，JDK 的 ImageIO 也没有内置
      // WEBP 读取器。与其写一个只覆盖部分变体的半吊子解析器（错的宽高比不填还糟），
      // 不如明确拒掉并说清要做什么。
      // 注意：3.4.1.2 图片处理同样没有 WEBP 能力——它复用 JDK ImageIO 做解码，
      // 读不了 WEBP 就进不了流水线，所以**不能**在这里承诺「处理上线后就能直传」。
      // 要支持得先引第三方解码器（如 imageio-webp / libwebp），那是引入依赖的决策。
      if ("webp".equals(signature))
        throw bad(
            "暂不支持直接上传 WEBP：服务端无法从字节里可靠读出它的宽高"
                + "（JDK 图片解码器不含 WEBP），而入库要求尺寸必须探测得出。"
                + "请先用任意工具转成 PNG 或 JPEG 再上传；图片处理模块同样读不了 WEBP，"
                + "走那条路也绕不过去");
      try {
        var image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null)
          throw bad("无法解析这张图片的内容：文件已损坏，或它不是 JPEG / PNG / GIF / BMP 之一" + "（仅凭文件扩展名不算数，格式以文件头为准）");
        // 格式取自**字节签名**而不是 ImageIO 的读取器名：签名是我们自己判定的，
        // 不受 JDK 版本里装了哪些读取器插件影响（同一份文件在装了 webp 读取器的
        // 机器与没装的机器上会报出不同格式，那会让媒体库在不同环境下自相矛盾）。
        return new Probe(
            "IMAGE",
            signature,
            "image/" + signature,
            content.length,
            image.getWidth(),
            image.getHeight(),
            sha,
            "宽高与格式由字节签名与 ImageIO 从文件内容探测得出，非调用方填写");
      } catch (IOException e) {
        throw bad("无法读取图片内容：" + e.getMessage());
      }
    }
    if (looksLikeVideo(content)) {
      return new Probe(
          "VIDEO",
          ext.isEmpty() ? "bin" : ext,
          videoMime(ext),
          content.length,
          null,
          null,
          sha,
          "视频的宽高与时长不做探测（需要解容器），因此留空而不是填一个猜出来的值；" + "图片处理模块只处理静态图片，视频转码需要独立编解码器，尚未支持");
    }
    throw bad(
        "只接受图片或视频（探测到的文件类型不支持）。支持：PNG / JPEG / GIF / BMP，"
            + "视频：MP4 / MOV / WEBM / AVI。当前文件名为 "
            + (filename == null ? "(未提供)" : filename));
  }

  /** 从字节签名判定图片格式。与 {@link #looksLikeImage} 的判据一一对应。 */
  private static String imageSignature(byte[] c) {
    if (c.length > 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G')
      return "png";
    if (c.length > 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF)
      return "jpeg";
    if (c.length > 6 && c[0] == 'G' && c[1] == 'I' && c[2] == 'F' && c[3] == '8') return "gif";
    if (c.length > 2 && c[0] == 'B' && c[1] == 'M') return "bmp";
    if (c.length > 12 && ascii(c, 0, "RIFF") && ascii(c, 8, "WEBP")) return "webp";
    return "png";
  }

  private static boolean looksLikeImage(byte[] c) {
    // PNG 签名
    if (c.length > 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G')
      return true;
    // JPEG: FF D8 FF
    if (c.length > 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF)
      return true;
    // GIF87a / GIF89a
    if (c.length > 6 && c[0] == 'G' && c[1] == 'I' && c[2] == 'F' && c[3] == '8') return true;
    // BMP
    if (c.length > 2 && c[0] == 'B' && c[1] == 'M') return true;
    // WEBP: "RIFF"...."WEBP"
    if (c.length > 12 && ascii(c, 0, "RIFF") && ascii(c, 8, "WEBP")) return true;
    return false;
  }

  private static boolean looksLikeVideo(byte[] c) {
    if (c.length > 12 && ascii(c, 4, "ftyp")) return true; // MP4 / MOV
    if (c.length > 4
        && (c[0] & 0xFF) == 0x1A
        && (c[1] & 0xFF) == 0x45
        && (c[2] & 0xFF) == 0xDF
        && (c[3] & 0xFF) == 0xA3) return true; // WebM (EBML)
    if (c.length > 12 && ascii(c, 0, "RIFF") && ascii(c, 8, "AVI ")) return true;
    return false;
  }

  private static boolean ascii(byte[] c, int offset, String text) {
    if (c.length < offset + text.length()) return false;
    for (int i = 0; i < text.length(); i++) if (c[offset + i] != text.charAt(i)) return false;
    return true;
  }

  private static String videoMime(String ext) {
    return switch (ext) {
      case "mp4", "m4v" -> "video/mp4";
      case "mov" -> "video/quicktime";
      case "webm" -> "video/webm";
      case "avi" -> "video/x-msvideo";
      default -> "application/octet-stream";
    };
  }

  private static String extension(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    if (dot < 0 || dot == filename.length() - 1) return "";
    return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  // ============================================================ 存储键

  /**
   * 生成存储键：{@code media/<tenant>/<yyyyMM>/<uuid>.<ext>}。
   *
   * <p>租户段在键里，所以一个租户算不出另一个租户的键——隔离在**键的构造**上就成立， 不依赖调用方记得加前缀。按年月分目录是为了让「按月份清理冷存储」不必扫全表。 用 UUID
   * 而不是内容哈希做文件名：内容相同应该**复用同一个素材**（UNIQUE 约束拦下）， 而不是产生两份同内容不同键的对象。
   */
  public static String storageKey(UUID tenantId, OffsetDateTime now, String extension) {
    String month =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMM")
            .format(now.withOffsetSameInstant(ZoneOffset.UTC));
    String ext = extension == null || extension.isBlank() ? "bin" : extension;
    return "media/" + tenantId + "/" + month + "/" + Ids.next() + "." + ext;
  }

  /** 校验存储键：不允许空、不允许 `..` 路径段、不允许绝对路径（LocalMediaStorage 再挡一次）。 */
  public static String storageKeyProblem(String key) {
    if (key == null || key.isBlank()) return "存储键不能为空";
    String trimmed = key.trim();
    if (trimmed.length() > 500) return "存储键最多 500 个字符";
    if (trimmed.startsWith("/")) return "存储键不能是绝对路径";
    if (trimmed.contains("..")) return "存储键不能包含路径回退段（..）";
    if (trimmed.contains("\\")) return "存储键不能包含反斜杠";
    return null;
  }

  // ============================================================ 校验（出口是可执行的原因，不是约束名）

  public static String nameProblem(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty()) return "素材名称不能为空";
    if (value.length() > 200) return "素材名称最多 200 个字符";
    return null;
  }

  public static String mediaTypeProblem(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) return "媒体类型不能为空";
    if (!MEDIA_TYPES.contains(mediaType.trim())) return "媒体类型只能是 IMAGE（图片）或 VIDEO（视频）";
    return null;
  }

  public static String purposeProblem(String purpose) {
    if (purpose == null || purpose.isBlank()) return "用途不能为空";
    if (!PURPOSES.contains(purpose.trim())) return "用途只能是 " + String.join(" / ", PURPOSES);
    return null;
  }

  /** 标签编码校验（与 brand.code / unit_group.code 同一口径）。 */
  public static String tagCodeProblem(String code) {
    String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return "标签编码不能为空";
    if (value.length() > 32) return "标签编码最多 32 个字符";
    if (!value.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) return "标签编码请使用英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String tagNameProblem(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty()) return "标签名称不能为空";
    if (value.length() > 100) return "标签名称最多 100 个字符";
    return null;
  }

  /** 引用对象校验：ownerType 必须是 SPU / SKU，且对应 id 不能为空。 */
  public static String referenceProblem(String ownerType, UUID spuId, UUID skuId) {
    if ("SPU".equals(ownerType)) {
      if (spuId == null) return "SPU 引用必须提供 spuId";
      if (skuId != null) return "SPU 引用不能同时带 skuId：一条引用只能指向一个对象";
      return null;
    }
    if ("SKU".equals(ownerType)) {
      if (skuId == null) return "SKU 引用必须提供 skuId";
      if (spuId != null) return "SKU 引用不能同时带 spuId：一条引用只能指向一个对象";
      return null;
    }
    return "引用对象只能是 SPU 或 SKU；listing 引用由 04 刊登在交付后通过 event 写入，不在catalog 侧直接填";
  }

  /** 标签归属校验：一次最多挂 N 个标签（整体替换语义）。 */
  public static String tagsSizeProblem(int size) {
    if (size > 50) return "单个素材最多挂 50 个标签";
    return null;
  }

  public static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeUpper(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  /** 统一的 400：带可执行原因。 */
  public static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static {
    // Set.of 的重复元素会直接抛，这里显式断言词表与 V18 的 CHECK 没有笔误。
    Set<String> t = Set.copyOf(MEDIA_TYPES);
    if (t.size() != MEDIA_TYPES.size()) throw new IllegalStateException("MEDIA_TYPES 有重复项");
    Set<String> p = Set.copyOf(PURPOSES);
    if (p.size() != PURPOSES.size()) throw new IllegalStateException("PURPOSES 有重复项");
    Set<String> i = Set.copyOf(INTEGRITY_STATUSES);
    if (i.size() != INTEGRITY_STATUSES.size())
      throw new IllegalStateException("INTEGRITY_STATUSES 有重复项");
  }

  /** 供测试断言：把一段文本转成 sha256（验证探测路径确实在算哈希）。 */
  public static String sha256Of(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
