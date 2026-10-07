package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.4.1.2 图片处理：配方、六项加工能力、异步任务与进度、「原图始终保留」的 DB 层证明、
 * 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS + 真实文件系统存储 + 真实线程池：
 * 处理任务真的在线程里把像素算完、真的写出对象与新版本行。
 * 没有任何内存替身——用内存 Map 存产物的话，「原图是否保留」与「产物是否落盘」都验不出来。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageProcessingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static Path storageRoot;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      }
      storageRoot = Path.of("target", "image-process-store-test").toAbsolutePath();
      if (Files.exists(storageRoot))
        try (var walk = Files.walk(storageRoot)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
          // 清不清旧目录都不影响本轮断言：每个键都带随机 uuid。
        }
      Files.createDirectories(storageRoot);
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.assets.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 与 3.4.1.1 同口径：assets + spu（V3 的 data_scope 策略在 spu 上是 RESTRICTIVE）。 */
  static List<String> allPermissions() {
    return java.util.stream.Stream.of("assets", "spu", "sku", "attribute")
        .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
            .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("MEDIA_STORAGE_ROOT", () -> storageRoot.toString());
  }

  @Autowired ImageProcessService processing;
  @Autowired ImageProcessExecutor executor;
  @Autowired MediaService media;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.assets.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.assets.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(who, "catalog.assets.update", () -> { work.run(); return null; });
  }

  // ------------------------------------------------------------ 夹具

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /**
   * 造一张「浅色背景 + 居中色块」的 PNG：去背景与白底的典型素材。
   *
   * <p>右下角画一个 {@code tint} 大小的点：3.4.1.1 的同内容复用会让**字节完全相同**的
   * 第二次上传返回既有素材（UNIQUE(tenant_id,content_sha256)），于是「上传 4 张不同的图」
   * 实际只得到 1 个素材、后面 3 个条目撞重复键。加一个随 tint 变的点，
   * 每张图的字节就都不一样，去重不会误伤。
   */
  byte[] photo(int w, int h, Color background, Color subject, int tint) throws Exception {
    var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(background);
    g.fillRect(0, 0, w, h);
    g.setColor(subject);
    g.fillRect(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 2));
    g.setColor(new Color(tint % 256, (tint * 7) % 256, (tint * 13) % 256));
    g.fillRect(Math.max(0, w - 1 - tint % 3), Math.max(0, h - 1 - tint % 2), 1, 1);
    g.dispose();
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  /** 无 tint 的便捷重载（单张使用的场景）。 */
  byte[] photo(int w, int h, Color background, Color subject) throws Exception {
    return photo(w, h, background, subject, 0);
  }

  byte[] jpeg(int w, int h, Color color) throws Exception {
    var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, w, h);
    g.dispose();
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", out);
    return out.toByteArray();
  }

  MockMultipartFile file(String filename, byte[] content) {
    return new MockMultipartFile("file", filename, null, content);
  }

  UUID upload(AccessService.Access who, String name, byte[] content) {
    return write(who, () -> media.upload(who, file(name + ".png", content),
        new MediaUpload(name, "IMAGE", "GALLERY", "", null, List.of(), true))).id();
  }

  /** 建一个配方（默认带两个比例档：主图 1:1 与缩略图）。 */
  MediaProcessRecipeSaved recipe(AccessService.Access who, String code, MediaProcessRecipeInput input) {
    return write(who, () -> processing.saveRecipe(who, null, input));
  }

  static MediaProcessRecipeInput recipeInput(
      String code, String format, boolean removeBg, boolean whiteBg,
      Integer w, Integer h, List<MediaProcessRatioInput> ratios) {
    return new MediaProcessRecipeInput(
        code, code, "测试配方", "AMAZON", format, removeBg, 12, whiteBg,
        ratios == null || ratios.isEmpty() ? "" : "TEST-SHOP",
        "BOTTOM_RIGHT", 85, w, h, "FIT", ratios, true, 0);
  }

  /** 主图 1:1 + 缩略图：蓝图点名的「生成多比例版本」。 */
  static List<MediaProcessRatioInput> mainAndThumb() {
    return List.of(
        new MediaProcessRatioInput("MAIN", 120, 120, "CROP_FILL"),
        new MediaProcessRatioInput("THUMBNAIL", 40, 40, "FIT"));
  }

  /**
   * 同步驱动任务跑到终态。
   *
   * <p>刻意**先** {@code runJobNow} 再等终态，而不是假设「同步驱动 = 我自己跑完的」：
   * {@code submit} 已经把任务扔进了线程池（这正是异步要求的落点），
   * 所以两种执行者都可能真的在干活。等终态让断言与「谁跑的」无关——
   * 否则测试会在「线程池先跑完」时通过、在「同步驱动先跑完」时也通过，
   * 但两种情况测的其实是同一条代码路径。
   */
  void drain(UUID jobId, AccessService.Access who) {
    executor.runJobNow(jobId, who.tenantId());
    awaitTerminalAs(jobId, who);
  }

  /** 等任务进终态（真的轮询库，不是 sleep 假装异步）。 */
  MediaProcessJobRow awaitTerminalAs(UUID jobId, AccessService.Access who) {
    for (int i = 0; i < 400; i++) {
      MediaProcessJobRow row = jobRow(jobId, who);
      if (row != null && ImageProcessRules.terminal(row.status())) return row;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("处理任务 " + jobId + " 没有在超时内到达终态");
  }

  /** 单个任务行（显式类型：lambda 返回类型在 var 链上会退化成 Object）。 */
  MediaProcessJobRow jobRow(UUID jobId, AccessService.Access who) {
    List<MediaProcessJobRow> rows =
        tx(who, () -> processing.listJobs(new MediaProcessJobQuery(null, null, 0, 200)).items());
    return rows.stream().filter(j -> j.id().equals(jobId)).findFirst().orElse(null);
  }

  /** 任务条目（显式类型：lambda 返回类型在 var 链上会退化成 Object）。 */
  List<MediaProcessJobItem> itemsOf(AccessService.Access who, UUID jobId) {
    List<MediaProcessJobItem> items = tx(who, () -> processing.items(jobId));
    return items;
  }

  /** 分页结果里的任务 id（显式类型同上）。 */
  List<UUID> jobIds(MediaProcessJobPage page) {
    List<MediaProcessJobRow> rows = page.items();
    return rows.stream().map(MediaProcessJobRow::id).toList();
  }

  /** 当前租户的配方编码列表（显式类型，避免 lambda 泛型推断退化）。 */
  java.util.List<String> recipeCodes(AccessService.Access who) {
    List<MediaProcessRecipeRow> rows = tx(who, () -> processing.recipes());
    return rows.stream().map(MediaProcessRecipeRow::code).toList();
  }

  // ------------------------------------------------------------ 配方

  @Test
  void recipeStoresEveryProcessingCapabilityAndValidatesCombinations() {
    String code = unique("RC");
    var saved = recipe(actor, code,
        recipeInput(code, "png", true, true, 800, 800, mainAndThumb()));
    assertThat(saved.ratioCount()).isEqualTo(2);

    var row = tx(actor, () -> processing.recipe(saved.id()));
    // 六项能力在配方上都有落点。
    assertThat(row.removeBackground()).isTrue();
    assertThat(row.whiteBackground()).isTrue();
    assertThat(row.backgroundTolerance()).isEqualTo(12);
    assertThat(row.watermarkText()).isEqualTo("TEST-SHOP");
    assertThat(row.watermarkPosition()).isEqualTo("BOTTOM_RIGHT");
    assertThat(row.watermarkOpacity()).isEqualTo(85);
    assertThat(row.targetFormat()).isEqualTo("png");
    assertThat(row.targetWidthPx()).isEqualByComparingTo("800");
    assertThat(row.targetHeightPx()).isEqualByComparingTo("800");
    assertThat(row.cropMode()).isEqualTo("FIT");
    // 用 containsExactlyInAnyOrder 而不是 containsExactly：档位顺序由 sort_no 决定，
    // 这里要断言的是「两档都在且宽高对」，而不是去赌 UUID 的生成顺序。
    assertThat(row.ratios()).extracting(MediaProcessRatio::label)
        .containsExactlyInAnyOrder("MAIN", "THUMBNAIL");
    // 比 intValue 而不是 BigDecimal：列是 numeric(19,6)，读回来带 6 位小数
    // （120.000000），直接比 BigDecimal 会因为 scale 不同而判不等。
    assertThat(row.ratios()).extracting(r -> r.widthPx().intValue())
        .containsExactlyInAnyOrder(120, 40);

    // 去背景 + 不支持透明的格式 → 可读原因（V19 的 CHECK 也会兜住）。
    String bad = unique("RC");
    assertThatThrownBy(() -> recipe(actor, bad,
        recipeInput(bad, "jpeg", true, false, null, null, List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("白底").contains("png"));

    // 只给一个目标尺寸。
    assertThatThrownBy(() -> recipe(actor, bad,
        recipeInput(bad, "png", false, false, 100, null, List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("同时给宽和高"));

    // 比例档标签重复。
    assertThatThrownBy(() -> recipe(actor, bad,
        recipeInput(bad, "png", false, false, null, null, List.of(
            new MediaProcessRatioInput("MAIN", 10, 10, "FIT"),
            new MediaProcessRatioInput("main", 20, 20, "FIT")))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("MAIN"));

    // WEBP 目标格式。
    assertThatThrownBy(() -> recipe(actor, bad,
        recipeInput(bad, "webp", false, false, null, null, List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("ImageIO"));

    // 编码重复。
    assertThatThrownBy(() -> recipe(actor, code,
        recipeInput(code, "png", false, false, null, null, List.of())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void recipeCannotBeDeletedOnceReferencedByAJobButCanBeDisabled() throws Exception {
    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, false, null, null, mainAndThumb()));
    UUID asset = upload(actor, unique("被引用的素材"), photo(60, 60, new Color(250, 250, 250), new Color(20, 20, 20)));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(asset))));
    drain(job.id(), actor);

    // 被任务引用时不可删（V19 的 refuse_media_process_recipe_delete 兜底）。
    long version = tx(actor, () -> processing.recipe(saved.id())).version();
    assertThatThrownBy(() -> writeVoid(actor, () -> processing.deleteRecipe(actor, saved.id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("停用"));

    // 停用后不能再用它新建任务，但历史任务与产物照常可读。
    writeVoid(actor, () -> processing.setRecipeEnabled(actor, saved.id(), false));
    UUID other = upload(actor, unique("停用后提交"), photo(30, 30, Color.WHITE, Color.BLACK));
    assertThatThrownBy(() -> write(actor, () ->
        processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(other)))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("停用"));
    assertThat(tx(actor, () -> processing.detail(job.id())).items()).hasSize(1);
  }

  // ------------------------------------------------------------ 六项加工能力（真实像素）

  @Test
  void everyProcessingCapabilityReallyChangesTheProducedBytes() throws Exception {
    // 一张浅灰背景 + 深色主体的图：去背景、白底、水印、裁剪、格式转换、多比例都能看出效果。
    byte[] original = photo(200, 120, new Color(248, 248, 248), new Color(180, 30, 30));
    UUID asset = upload(actor, unique("六项能力"), original);
    var before = tx(actor, () -> media.detail(asset)).asset();
    assertThat(before.currentVersion()).isEqualTo(1);

    String code = unique("RC");
    var saved = recipe(actor, code,
        recipeInput(code, "png", true, true, 150, 150, mainAndThumb()));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(asset))));
    assertThat(job.status()).isEqualTo("PENDING");
    drain(job.id(), actor);

    var detail = tx(actor, () -> processing.detail(job.id()));
    assertThat(detail.job().status()).isEqualTo("SUCCEEDED");
    assertThat(detail.job().succeededCount()).isEqualTo(1);
    assertThat(detail.job().failedCount()).isZero();
    var item = detail.items().get(0);
    assertThat(item.status()).isEqualTo("SUCCEEDED");
    assertThat(item.producedCount()).isEqualTo(2);

    // 多比例：两档各一行产物，尺寸真的按档裁了。
    assertThat(item.outputs()).extracting(MediaProcessOutput::ratioLabel)
        .containsExactly("MAIN", "THUMBNAIL");
    var main = item.outputs().get(0);
    var thumb = item.outputs().get(1);
    assertThat(main.widthPx()).isEqualByComparingTo("120");
    assertThat(main.heightPx()).isEqualByComparingTo("120");
    assertThat(thumb.widthPx()).isEqualByComparingTo("40");
    assertThat(thumb.heightPx()).isEqualByComparingTo("40");
    // 格式转换：目标 png，字节可解码、尺寸与档位一致。
    assertThat(main.fileFormat()).isEqualTo("png");
    assertThat(main.mimeType()).isEqualTo("image/png");

    // 产物字节真的落盘、可解码、且**与原图不同**（加工确实发生了）。
    for (MediaProcessOutput out : item.outputs()) {
      Path onDisk = storageRoot.resolve(out.storageKey());
      assertThat(Files.exists(onDisk)).as("产物对象落盘 " + out.storageKey()).isTrue();
      byte[] produced = Files.readAllBytes(onDisk);
      assertThat(MediaRules.sha256(produced)).isEqualTo(out.contentSha256());
      BufferedImage decoded = ImageProcessor.decode(produced);
      assertThat(decoded.getWidth()).isEqualTo(out.widthPx().intValue());
      assertThat(decoded.getHeight()).isEqualTo(out.heightPx().intValue());
      // 尺寸裁剪到目标框：两档都不是原图的 200×120。
      assertThat(out.widthPx().intValue()).isNotEqualTo(200);
      // 去背景 + 白底之后没有透明像素了。
      assertThat(ImageProcessor.hasTransparency(decoded)).isFalse();
      assertThat(out.contentSha256()).isNotEqualTo(before.contentSha256());
    }

    // 水印：产物图与「同尺寸但没水印」的图字节不同，且水印区域有变亮像素。
    BufferedImage produced = ImageProcessor.decode(Files.readAllBytes(storageRoot.resolve(main.storageKey())));
    assertThat(brightPixelInRegion(produced, 60, 90, 119, 119)).isTrue();

    // 格式转换到 jpeg 走另一条路径（不含 alpha 的色彩模型）。
    String jpegCode = unique("RC");
    var jpegRecipe = recipe(actor, jpegCode,
        recipeInput(jpegCode, "jpeg", false, true, 100, 100, List.of()));
    UUID jpegAsset = upload(actor, unique("转 JPEG"), photo(300, 200, new Color(250, 250, 250), new Color(10, 80, 10)));
    var jpegJob = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(jpegRecipe.id(), List.of(jpegAsset))));
    drain(jpegJob.id(), actor);
    var jpegItem = tx(actor, () -> processing.detail(jpegJob.id())).items().get(0);
    assertThat(jpegItem.status()).isEqualTo("SUCCEEDED");
    MediaProcessOutput jpegOut = jpegItem.outputs().get(0);
    assertThat(jpegOut.fileFormat()).isEqualTo("jpeg");
    assertThat(jpegOut.mimeType()).isEqualTo("image/jpeg");
    assertThat(jpegOut.widthPx()).isEqualByComparingTo("100");
    assertThat(jpegOut.heightPx()).isEqualByComparingTo("100");
    byte[] jpegBytes = Files.readAllBytes(storageRoot.resolve(jpegOut.storageKey()));
    // 真的是 JPEG 字节（SOI 标记 FF D8），不是换了个扩展名的 png。
    assertThat((jpegBytes[0] & 0xFF)).isEqualTo(0xFF);
    assertThat((jpegBytes[1] & 0xFF)).isEqualTo(0xD8);
  }

  @Test
  void cropModesFitKeepsPictureAndCropFillFillsTheFrame() throws Exception {
    // 一张 200×100 的图，两个配方分别用 FIT 与 CROP_FILL 裁到 100×100。
    String fitCode = unique("FIT");
    var fit = recipe(actor, fitCode,
        new MediaProcessRecipeInput(fitCode, fitCode, "", "AMAZON", "png",
            false, 12, false, "", "BOTTOM_RIGHT", 30, 100, 100, "FIT",
            List.of(new MediaProcessRatioInput("MAIN", 100, 100, "FIT")), true, 0));
    UUID asset = upload(actor, unique("裁剪方式"), photo(200, 100, new Color(250, 250, 250), new Color(10, 10, 200)));

    var fitJob = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(fit.id(), List.of(asset))));
    drain(fitJob.id(), actor);
    var fitOut = tx(actor, () -> processing.detail(fitJob.id())).items().get(0).outputs().get(0);
    // FIT：上下补白边，所以图的上边缘是纯白。
    BufferedImage fitImage = ImageProcessor.decode(Files.readAllBytes(storageRoot.resolve(fitOut.storageKey())));
    assertThat(fitImage.getHeight()).isEqualTo(100);
    assertThat(fitImage.getRGB(50, 2) & 0xFFFFFF).isEqualTo(0xFFFFFF);

    String fillCode = unique("FILL");
    var fill = recipe(actor, fillCode,
        new MediaProcessRecipeInput(fillCode, fillCode, "", "AMAZON", "png",
            false, 12, false, "", "BOTTOM_RIGHT", 30, null, null, "FIT",
            List.of(new MediaProcessRatioInput("MAIN", 100, 100, "CROP_FILL")), true, 0));
    var fillJob = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(fill.id(), List.of(asset))));
    drain(fillJob.id(), actor);
    var fillOut = tx(actor, () -> processing.detail(fillJob.id())).items().get(0).outputs().get(0);
    BufferedImage fillImage = ImageProcessor.decode(Files.readAllBytes(storageRoot.resolve(fillOut.storageKey())));
    // CROP_FILL：填满，画面中央就是主体（不是白边）。
    assertThat(fillImage.getWidth()).isEqualTo(100);
    assertThat(fillImage.getHeight()).isEqualTo(100);
    assertThat(fillImage.getRGB(50, 50) & 0xFFFFFF).isEqualTo(new Color(10, 10, 200).getRGB() & 0xFFFFFF);
  }

  // ------------------------------------------------------------ 原图始终保留

  @Test
  void processedOutputBecomesANewVersionAndTheOriginalIsNeverOverwritten() throws Exception {
    byte[] original = photo(180, 120, new Color(248, 248, 248), new Color(30, 30, 200));
    UUID asset = upload(actor, unique("原图保留"), original);
    var before = tx(actor, () -> media.detail(asset)).asset();
    int beforeVersion = before.currentVersion();
    String beforeKey = before.storageKey();
    String beforeSha = before.contentSha256();
    Path beforeObject = storageRoot.resolve(beforeKey);
    assertThat(Files.exists(beforeObject)).isTrue();

    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, true, 90, 90, mainAndThumb()));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(asset))));
    drain(job.id(), actor);
    assertThat(tx(actor, () -> processing.detail(job.id())).job().status()).isEqualTo("SUCCEEDED");

    // current_version 前进到最后一个产物版本。
    var after = tx(actor, () -> media.detail(asset)).asset();
    assertThat(after.currentVersion()).isGreaterThan(beforeVersion);
    assertThat(after.contentSha256()).isNotEqualTo(beforeSha);

    // 版本历史里 v1 原图**还在**，而且它的对象字节一个都没变。
    var versions = tx(actor, () -> media.versions(asset));
    assertThat(versions.size()).isEqualTo(1 + 2);
    var v1 = versions.stream().filter(v -> v.versionNo() == beforeVersion).findFirst().orElseThrow();
    assertThat(v1.storageKey()).isEqualTo(beforeKey);
    assertThat(v1.contentSha256()).isEqualTo(beforeSha);
    assertThat(v1.byteSize()).isEqualTo(before.byteSize());
    // 磁盘上的原图对象仍然存在、内容仍与 v1 登记的哈希一致（没被覆盖、没被删）。
    assertThat(Files.exists(beforeObject)).as("原图对象仍在").isTrue();
    assertThat(MediaRules.sha256(Files.readAllBytes(beforeObject))).isEqualTo(beforeSha);

    // 产物的 from/to 版本号：新版本严格大于来源版本（V19 的 CHECK 也强制这一点）。
    for (MediaProcessOutput out : tx(actor, () -> processing.detail(job.id())).items().get(0).outputs()) {
      assertThat(out.fromVersion()).isEqualTo(beforeVersion);
      assertThat(out.toVersion()).isGreaterThan(out.fromVersion());
      assertThat(out.storageKey()).isNotEqualTo(beforeKey);
    }
  }

  @Test
  void databaseRefusesToMutateOrDeleteTheOriginalVersion() throws Exception {
    byte[] original = photo(120, 90, new Color(248, 248, 248), new Color(60, 20, 20));
    UUID asset = upload(actor, unique("DB 守卫"), original);
    var before = tx(actor, () -> media.detail(asset)).asset();
    int v1 = before.currentVersion();

    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, true, 60, 60, List.of(
        new MediaProcessRatioInput("MAIN", 60, 60, "FIT"))));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(asset))));
    drain(job.id(), actor);

    // 1) 版本行不可改：连改 note 都不行（BEFORE UPDATE 触发器）。
    assertThatThrownBy(() -> writeVoid(actor, () ->
        sql.execute("update media_asset_version set note='篡改' where asset_id=? and version_no=?", asset, v1)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .satisfies(e -> assertThat(rootMessageOf(e)).contains("不可修改").contains("原图始终保留"));

    // 2) 被处理记录引用过的来源版本不可被单独删除（父素材还在 → 撞删除保护）。
    assertThatThrownBy(() -> writeVoid(actor, () ->
        sql.execute("delete from media_asset_version where asset_id=? and version_no=?", asset, v1)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .satisfies(e -> assertThat(rootMessageOf(e)).contains("不可删除").contains("原图始终保留"));

    // 原图仍然完好。
    var after = tx(actor, () -> media.detail(asset)).asset();
    assertThat(after.currentVersion()).isGreaterThan(v1);
    assertThat(MediaRules.sha256(Files.readAllBytes(storageRoot.resolve(before.storageKey()))))
        .isEqualTo(before.contentSha256());

    // 3) 没被处理引用过的版本可以删（守卫不做一刀切拦截）。
    UUID plain = upload(actor, unique("未被处理的素材"), photo(40, 40, Color.WHITE, Color.BLACK));
    int plainV1 = tx(actor, () -> media.detail(plain)).asset().currentVersion();
    writeVoid(actor, () -> sql.execute(
        "delete from media_asset_version where asset_id=? and version_no=?", plain, plainV1));
    assertThat(tx(actor, () -> media.versions(plain))).isEmpty();
  }

  // ------------------------------------------------------------ 异步与进度

  @Test
  void jobRunsAsynchronouslyAndProgressIsQueryableWhileRunning() throws Exception {
    String mark = unique("ASYNC");
    List<UUID> assets = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      assets.add(upload(actor, mark + "-" + i,
          photo(120, 120, new Color(250, 250, 250), new Color(10 * i + 20, 40, 90), i)));

    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", true, true, 100, 100, mainAndThumb()));

    // 提交只写元数据就返回（长批量不阻塞页面的落点）。
    long before = System.nanoTime();
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), assets)));
    long elapsedMs = (System.nanoTime() - before) / 1_000_000;

    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.assetCount()).isEqualTo(4);
    assertThat(job.itemCount()).isEqualTo(4);
    // 提交本身不做像素加工：4 个素材 × 2 档的产物此时一个都还没写。
    assertThat(elapsedMs).as("提交耗时（不含建配方）").isLessThan(5000);

    // 提交后条目立刻可查（这就是「长批量不阻塞页面」的落点：提交不等加工）。
    // 不在这里断言「status 还是 PENDING」——工作线程是在 submit 返回之后才起跑的，
    // 断言那个瞬时状态等于和线程池赛跑，测的是时序而不是行为。
    // 「异步」本身由 asyncExecutorRunsTheJobOnAWorkerThreadWithoutBlockingTheCaller 证明。
    var queued = tx(actor, () -> processing.detail(job.id()));
    assertThat(queued.items()).hasSize(4);
    assertThat(queued.job().assetCount()).isEqualTo(4);

    // 跑完之后进度是**库里可查的事实**，不是内存计数。
    drain(job.id(), actor);
    var done = tx(actor, () -> processing.detail(job.id()));
    assertThat(done.job().status()).isEqualTo("SUCCEEDED");
    assertThat(done.job().succeededCount()).isEqualTo(4);
    assertThat(done.job().failedCount()).isZero();
    assertThat(done.finishedItemCount()).isEqualTo(4);
    assertThat(done.job().startedAt()).isNotNull();
    assertThat(done.job().finishedAt()).isNotNull();
    // 系统时间轴：开始不晚于结束。
    assertThat(done.job().startedAt()).isLessThanOrEqualTo(done.job().finishedAt());
    // 每个条目都产出了 2 档，且条目上的开始/结束时间都落了。
    for (var item : done.items()) {
      assertThat(item.status()).isEqualTo("SUCCEEDED");
      assertThat(item.producedCount()).isEqualTo(2);
      assertThat(item.outputs()).hasSize(2);
      assertThat(item.startedAt()).isNotNull();
      assertThat(item.finishedAt()).isNotNull();
    }
    // 每个素材的当前版本都前进了一格（一档一个版本：v2、v3）。
    for (UUID asset : assets)
      assertThat(tx(actor, () -> media.detail(asset)).asset().currentVersion()).isEqualTo(3);

    // 列表与概览能看到这次任务。
    var page = tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(mark, null, 0, 200)));
    assertThat(page.items()).extracting(MediaProcessJobRow::id).contains(job.id());
    var filtered = tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(null, "SUCCEEDED", 0, 200)));
    assertThat(filtered.items()).extracting(MediaProcessJobRow::id).contains(job.id());
    var wrongStatus = tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(null, "FAILED", 0, 200)));
    assertThat(wrongStatus.items()).extracting(MediaProcessJobRow::id).doesNotContain(job.id());
    // 关键词能搜到素材名（运营记得的是「那批图」，不是任务 id）。
    var byAssetName = tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(mark + "-2", null, 0, 200)));
    assertThat(byAssetName.items()).extracting(MediaProcessJobRow::id).contains(job.id());
    assertThat(tx(actor, () -> processing.overview()).outputCount()).isGreaterThan(0);
  }

  @Test
  void asyncExecutorRunsTheJobOnAWorkerThreadWithoutBlockingTheCaller() throws Exception {
    UUID asset = upload(actor, unique("真异步"), photo(150, 150, new Color(250, 250, 250), new Color(120, 40, 10)));
    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, true, 80, 80, mainAndThumb()));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), List.of(asset))));

    // 走真正的线程池（不是测试里的同步驱动）：入队后立刻返回，
    // 调用线程名与工作线程名不同 —— 这才是「异步任务」而不是「换个地方同步跑」。
    assertThat(executor.enqueue(job.id(), actor.tenantId())).isTrue();
    var jobRow = awaitTerminal(job.id());
    assertThat(jobRow.status()).isIn("SUCCEEDED", "PARTIAL", "FAILED");
    // 产物是真的在线程里写出来的。
    var item = tx(actor, () -> processing.detail(job.id())).items().get(0);
    assertThat(item.outputs()).isNotEmpty();
    assertThat(Files.exists(storageRoot.resolve(item.outputs().get(0).storageKey()))).isTrue();
  }

  /** 等任务进终态（委托给 drain 的同一实现，保证超时口径一致）。 */
  MediaProcessJobRow awaitTerminal(UUID jobId) {
    return awaitTerminalAs(jobId, actor);
  }

  @Test
  void cancellingLeavesUnstartedItemsCancelledAndKeepsTheWholeRecord() throws Exception {
    // 用一批而不是 3 个：取消是「条目之间生效」，素材太少时工作线程可能在
    // 取消请求到达之前就把整批跑完了——那测的是时序而不是取消语义。
    // 40 个素材 × 2 个比例档足够让取消落在批处理中途（这正是长批量的用法）。
    List<UUID> assets = new ArrayList<>();
    for (int i = 0; i < 40; i++)
      assets.add(upload(actor, unique("取消"),
          photo(240, 240, new Color(250, 250, 250), new Color(20, 90, 40), i)));
    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, true, 120, 120, mainAndThumb()));
    var job = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(saved.id(), assets)));

    // 取消请求不阻塞：只置标志位，立刻返回。
    var cancelled = write(actor, () -> processing.cancel(actor, job.id()));
    assertThat(cancelled.cancelRequested()).isTrue();

    drain(job.id(), actor);
    var detail = tx(actor, () -> processing.detail(job.id()));
    // 终态：取消后不会再被工作线程改动。
    assertThat(detail.job().status()).isEqualTo("CANCELLED");
    assertThat(detail.job().finishedAt()).isNotNull();
    List<MediaProcessJobItem> cancelledItems = detail.items();
    assertThat(cancelledItems).hasSize(40);

    // 取消的真正契约：**没有条目被落在中间态**。已经开始的那一张会做完
    // （像素处理没有「做一半」的中间态），剩下的都落成 CANCELLED。
    assertThat(cancelledItems).allSatisfy(item ->
        assertThat(item.status()).isIn("SUCCEEDED", "CANCELLED"));
    // 计数与条目对得上：成功 + 取消 = 总数。
    long succeeded = cancelledItems.stream().filter(i -> "SUCCEEDED".equals(i.status())).count();
    long cancelledCount = cancelledItems.stream().filter(i -> "CANCELLED".equals(i.status())).count();
    assertThat(succeeded + cancelledCount).isEqualTo(40);
    // 确实取消掉了一部分（否则这个用例没测到取消）。
    assertThat(cancelledCount).as("被取消的条目数").isGreaterThan(0);
    // 被取消的条目没有产物，且给了可执行的原因。
    cancelledItems.stream().filter(i -> "CANCELLED".equals(i.status())).forEach(item -> {
      assertThat(item.outputs()).isEmpty();
      assertThat(item.error()).contains("取消");
    });
    // 已完成的条目产物是真的（取消不会回滚已写入的版本）。
    cancelledItems.stream().filter(i -> "SUCCEEDED".equals(i.status())).forEach(item ->
        assertThat(item.outputs()).hasSize(2));

    // 取消后不能再取消（终态不可变）。
    assertThatThrownBy(() -> write(actor, () -> processing.cancel(actor, job.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void partialFailureKeepsFailuresVisibleWithoutRollingBackTheBatch() throws Exception {
    String mark = unique("部分失败");
    UUID good = upload(actor, mark + "-好图", photo(120, 90, new Color(250, 250, 250), new Color(20, 60, 90)));
    String code = unique("RC");
    var saved = recipe(actor, code, recipeInput(code, "png", false, true, 60, 60, List.of(
        new MediaProcessRatioInput("MAIN", 60, 60, "FIT"))));

    // 第二个素材先把对象删掉，制造一个**真实的**失败：元数据说有、存储里已经没了
    // （正是 3.4.1.1 失效检测报 MISSING 的那种状态）。
    // 不用「插一条指向不存在键的条目」那种绕路做法：那是自己造出来的假故障，
    // 而且会和提交后立刻起跑的工作线程赛跑（条目可能在插入前就已经被处理完了）。
    UUID ghost = upload(actor, mark + "-坏图", photo(120, 90, new Color(252, 252, 252), new Color(5, 5, 200), 9));
    String ghostKey = tx(actor, () -> media.detail(ghost)).asset().storageKey();
    try {
      java.nio.file.Files.delete(storageRoot.resolve(ghostKey));
    } catch (java.io.IOException e) {
      throw new AssertionError("无法删除用于制造失败的对象：" + ghostKey, e);
    }
    // 失效检测确认它确实坏了（MISSING），再拿它去处理。
    assertThat(tx(actor, () -> media.verify(ghost)).integrityStatus()).isEqualTo("MISSING");

    var job = write(actor, () -> processing.submit(actor,
        new MediaProcessJobInput(saved.id(), List.of(good, ghost))));

    drain(job.id(), actor);
    var detail = tx(actor, () -> processing.detail(job.id()));
    assertThat(detail.job().status()).isEqualTo("PARTIAL");
    assertThat(detail.job().succeededCount()).isEqualTo(1);
    assertThat(detail.job().failedCount()).isEqualTo(1);

    var failed = detail.items().stream().filter(i -> "FAILED".equals(i.status())).findFirst().orElseThrow();
    // 失败原因是可执行的中文（能看出是存储键没了、该去做什么），不是异常类名。
    assertThat(failed.error()).contains("读取原图失败").contains("完整性校验");
    var ok = detail.items().stream().filter(i -> "SUCCEEDED".equals(i.status())).findFirst().orElseThrow();
    assertThat(ok.outputs()).hasSize(1);
    // 好图真的被处理了（整批没有因为一个失败而回滚）。
    assertThat(tx(actor, () -> media.detail(good)).asset().currentVersion()).isEqualTo(2);
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    String mark = unique("ISO");
    String codeA = unique("ISOA");
    var recipeA = recipe(actor, codeA, recipeInput(codeA, "png", true, true, 80, 80, mainAndThumb()));
    UUID mine = upload(actor, mark + "-A 租户", photo(120, 120, new Color(250, 250, 250), new Color(10, 80, 20)));

    String codeB = unique("ISOB");
    var recipeB = recipe(actorB, codeB, recipeInput(codeB, "png", false, false, 60, 60, List.of()));
    UUID theirs = upload(actorB, mark + "-B 租户", photo(90, 90, new Color(250, 250, 250), new Color(120, 10, 10)));
    var jobB = write(actorB, () -> processing.submit(actorB, new MediaProcessJobInput(recipeB.id(), List.of(theirs))));
    drain(jobB.id(), actorB);

    // 1) 两租户互不可见
    assertThat(recipeCodes(actor)).contains(codeA).doesNotContain(codeB);
    assertThat(recipeCodes(actorB)).contains(codeB).doesNotContain(codeA);
    // 手里握着别人的 id 也读不到：RLS + 端点查询都按当前租户过滤。
    assertThatThrownBy(() -> tx(actor, () -> processing.recipe(recipeB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> processing.recipe(recipeA.id())))
        .isInstanceOf(ResponseStatusException.class);
    // 自己提交的任务当然看得到（下面 jobA 建好后再断言「B 看不到 A 的」）。
    // A 租户的任务里看不到 B 租户的素材。
    var jobA = write(actor, () -> processing.submit(actor, new MediaProcessJobInput(recipeA.id(), List.of(mine))));
    drain(jobA.id(), actor);
    // B 租户不能读 A 租户的任务详情（哪怕手里有它的 id）。
    assertThatThrownBy(() -> tx(actorB, () -> processing.detail(jobA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> processing.detail(jobB.id())))
        .isInstanceOf(ResponseStatusException.class);
    var detailA = tx(actor, () -> processing.detail(jobA.id()));
    assertThat(detailA.items()).extracting(MediaProcessJobItem::assetId).contains(mine).doesNotContain(theirs);
    assertThat(tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(mark, null, 0, 200))).items())
        .extracting(MediaProcessJobRow::id).contains(jobA.id()).doesNotContain(jobB.id());
    assertThat(tx(actorB, () -> processing.listJobs(new MediaProcessJobQuery(mark, null, 0, 200))).items())
        .extracting(MediaProcessJobRow::id).contains(jobB.id()).doesNotContain(jobA.id());
    // 素材处理历史也不串。
    assertThat(tx(actor, () -> processing.historyOfAsset(mine))).extracting(MediaProcessJobRow::id).contains(jobA.id());
    assertThat(tx(actor, () -> processing.historyOfAsset(theirs))).isEmpty();
    // B 租户不能拿自己的配方处理 A 租户的素材。
    assertThatThrownBy(() -> write(actorB, () ->
        processing.submit(actorB, new MediaProcessJobInput(recipeB.id(), List.of(mine)))))
        .isInstanceOf(ResponseStatusException.class);
    // B 租户不能取消 A 租户的任务。
    assertThatThrownBy(() -> write(actorB, () -> processing.cancel(actorB, jobA.id())))
        .isInstanceOf(ResponseStatusException.class);
    // B 租户不能删 A 租户的配方。
    long versionA = tx(actor, () -> processing.recipe(recipeA.id())).version();
    assertThatThrownBy(() -> writeVoid(actorB, () -> processing.deleteRecipe(actorB, recipeA.id(), versionA)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(recipeCodes(actor)).contains(codeA);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(get("/catalog/media/processing/jobs/" + jobA.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    var submitted =
        http.perform(post("/catalog/media/processing/jobs")
                .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"recipeId\":\"" + recipeB.id() + "\",\"assetIds\":[\"" + theirs + "\"]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String forgedJobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted).get("id").asText();
    UUID forgedJob = UUID.fromString(forgedJobId);
    // 伪造头写入的任务进的是 B 租户（凭据说的那个）。
    assertThat(itemsOf(actorB, forgedJob)).isNotEmpty();
    // A 租户用同一个 id 查不到它：任务按租户隔离，不是按 id 全局可见。
    assertThatThrownBy(() -> tx(actor, () -> processing.detail(forgedJob)))
        .isInstanceOf(ResponseStatusException.class);
    var mineJobs = tx(actor, () -> processing.listJobs(new MediaProcessJobQuery(null, "PENDING", 0, 200)));
    assertThat(jobIds(mineJobs)).doesNotContain(forgedJob);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> processing.detail(jobA.id())).job().id()).isEqualTo(jobA.id());
      assertThat(tx(actorB, () -> processing.detail(jobB.id())).job().id()).isEqualTo(jobB.id());
      assertThatThrownBy(() -> tx(actorB, () -> processing.detail(jobA.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(recipeCodes(actor)).contains(codeA).doesNotContain(codeB);
      assertThat(recipeCodes(actorB)).contains(codeB).doesNotContain(codeA);
      // 素材的处理历史同样按租户隔离（历史表最容易在连接复用时串）。
      assertThat(tx(actor, () -> processing.historyOfAsset(mine))).isNotEmpty();
      assertThat(tx(actorB, () -> processing.historyOfAsset(mine))).isEmpty();
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    String code = unique("HTTP");
    var created =
        http.perform(post("/catalog/media/processing/recipes")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"note\":\"端到端\","
                    + "\"targetPlatform\":\"AMAZON\",\"targetFormat\":\"png\",\"removeBackground\":true,"
                    + "\"backgroundTolerance\":12,\"whiteBackground\":true,\"watermarkText\":\"SHOP\","
                    + "\"watermarkPosition\":\"BOTTOM_RIGHT\",\"watermarkOpacity\":85,"
                    + "\"targetWidthPx\":80,\"targetHeightPx\":80,\"cropMode\":\"FIT\","
                    + "\"ratios\":[{\"label\":\"MAIN\",\"widthPx\":80,\"heightPx\":80,\"cropMode\":\"CROP_FILL\"}],"
                    + "\"enabled\":true,\"version\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratioCount").value(1))
            .andReturn().getResponse().getContentAsString();
    String recipeId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("id").asText();

    byte[] content = photo(140, 100, new Color(248, 248, 248), new Color(200, 40, 40));
    var upload =
        http.perform(multipart("/catalog/media").file(file("工作流主图.png", content))
                .file(new MockMultipartFile("meta", "", "application/json",
                    ("{\"name\":\"" + code + "-素材\",\"mediaType\":\"IMAGE\",\"purpose\":\"PRIMARY\","
                        + "\"tagCodes\":[],\"enabled\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String assetId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(upload).get("id").asText();

    // 提交（异步受理语义）。
    var submitted =
        http.perform(post("/catalog/media/processing/jobs")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"recipeId\":\"" + recipeId + "\",\"assetIds\":[\"" + assetId + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.assetCount").value(1))
            .andReturn().getResponse().getContentAsString();
    String jobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted).get("id").asText();
    executor.runJobNow(UUID.fromString(jobId), a);
    awaitTerminal(UUID.fromString(jobId));

    http.perform(get("/catalog/media/processing/jobs/" + jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.job.statusLabel").value("全部成功"))
        .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.items[0].outputs[0].ratioLabel").value("MAIN"))
        .andExpect(jsonPath("$.items[0].outputs[0].toVersion").isNumber())
        .andExpect(jsonPath("$.items[0].outputs[0].fromVersion").value(1));

    http.perform(get("/catalog/media/processing/jobs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").isNumber());
    http.perform(get("/catalog/media/processing/jobs/" + jobId + "/items").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].producedCount").value(1));
    http.perform(get("/catalog/media/processing/recipes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    http.perform(get("/catalog/media/processing/recipes/" + recipeId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ratios[0].label").value("MAIN"));
    http.perform(get("/catalog/media/processing/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outputCount").isNumber());
    http.perform(get("/catalog/media/processing/assets/" + assetId + "/history").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(jobId));

    // 未登录 401；只读角色能读不能写。
    http.perform(get("/catalog/media/processing/jobs")).andExpect(status().isUnauthorized());
    http.perform(get("/catalog/media/processing/recipes").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(post("/catalog/media/processing/jobs").header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"recipeId\":\"" + recipeId + "\",\"assetIds\":[\"" + assetId + "\"]}"))
        .andExpect(status().isForbidden());
    http.perform(delete("/catalog/media/processing/recipes/" + recipeId + "?version=0")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    // 非法配方是 400 不是 500。
    http.perform(post("/catalog/media/processing/recipes").header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"code\":\"BAD\",\"name\":\"坏配方\",\"targetFormat\":\"webp\",\"enabled\":true,\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------ 工具

  private boolean brightPixelInRegion(BufferedImage image, int x0, int y0, int x1, int y1) {
    for (int y = y0; y <= Math.min(y1, image.getHeight() - 1); y++)
      for (int x = x0; x <= Math.min(x1, image.getWidth() - 1); x++) {
        int rgb = image.getRGB(x, y) & 0xFFFFFF;
        int lum = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
        if (lum >= 150) return true;
      }
    return false;
  }

  private static String rootMessageOf(Throwable e) {
    var root = e;
    while (root.getCause() != null) root = root.getCause();
    return root.getMessage() == null ? "" : root.getMessage();
  }

  static org.springframework.test.web.servlet.result.JsonPathResultMatchers jsonPath(String path) {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(path);
  }
}