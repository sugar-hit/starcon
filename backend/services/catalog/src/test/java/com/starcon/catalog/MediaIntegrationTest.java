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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.4.1.1 媒体库：上传与内容探测、同内容复用、分类标签、引用计数、版本、 失效检测、删除保护，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS + 真实文件系统存储，不使用任何内存替身： 失效检测要能验出「文件确实不在了」，用内存 Map 存就永远验不出 MISSING。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static Path storageRoot;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      }
      // 存储根目录放在项目的 target 下（仓库内的临时产物，不落家目录也不落 /tmp）。
      storageRoot = Path.of("target", "media-store-test").toAbsolutePath();
      if (Files.exists(storageRoot))
        try (var walk = Files.walk(storageRoot)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
          // 清不清旧目录都不影响本轮断言：每个素材的键都带随机 uuid。
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      // 只有素材读权限：写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.assets.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 测试角色需要的权限。
   *
   * <p>必须**同时**给 assets 与 spu：V3 的 {@code data_scope} 策略在 spu 上是 RESTRICTIVE， 当操作是 {@code
   * catalog.assets.*} 时它要求 grant 里含 {@code catalog.spu.read} （那条策略把 assets 域映射到 spu 的数据范围）。只给
   * assets 的话， 素材服务去读被引用的 SPU 会得到 404 —— 症状是「商品不存在或不属于当前租户」， 而真正原因是授权词表缺了 spu 那一段。
   */
  static List<String> allPermissions() {
    return java.util.stream.Stream.of("assets", "spu", "sku", "attribute")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("MEDIA_STORAGE_ROOT", () -> storageRoot.toString());
  }

  @Autowired MediaService media;
  @Autowired CatalogService catalog;
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
    access.run(
        who,
        "catalog.assets.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /** 造一张**真的** PNG：不同颜色得到不同字节，所以不同颜色的两张图 sha 不同， 同色的两张图 sha 相同（用于验证同内容复用）。宽高由画布尺寸决定，可断言。 */
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

  /** 一段不是图片也不是视频的字节：用来验证「探测不出来就拒」。 */
  byte[] garbage() {
    return "这不是一个媒体文件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  MockMultipartFile file(String filename, byte[] content) {
    return new MockMultipartFile("file", filename, null, content);
  }

  MediaUpload uploadInput(String name, String type, String purpose, List<String> tags) {
    return new MediaUpload(name, type, purpose, "测试素材", "alt text", tags, true);
  }

  UUID upload(AccessService.Access who, String name, byte[] content, List<String> tags) {
    return write(
            who,
            () ->
                media.upload(
                    who, file(name + ".png", content), uploadInput(name, "IMAGE", "GALLERY", tags)))
        .id();
  }

  /** 建一个 SPU（3.1.1.1 的写入所有权），只用作引用目标。 */
  UUID spu(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            catalog.saveSpu(
                who, null, new SpuInput(code, "SPU " + code, "", "Brand", "", "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  // ------------------------------------------------------------ 上传与内容探测

  @Test
  void uploadProbesMetadataFromBytesInsteadOfTrustingTheCaller() throws Exception {
    byte[] content = png(120, 80, new Color(10, 20, 30));
    var saved =
        write(
            actor,
            () ->
                media.upload(
                    actor,
                    file("测试主图.png", content),
                    uploadInput("测试主图", "IMAGE", "PRIMARY", List.of("WHITE-BG", "HERO"))));
    assertThat(saved.deduplicated()).isFalse();

    var detail = tx(actor, () -> media.detail(saved.id()));
    var asset = detail.asset();
    // 宽高与字节数都是从字节探测的：调用方根本没有填这些字段的入口。
    assertThat(asset.widthPx()).isEqualByComparingTo("120");
    assertThat(asset.heightPx()).isEqualByComparingTo("80");
    assertThat(asset.byteSize()).isEqualByComparingTo(BigDecimal.valueOf(content.length));
    assertThat(asset.fileFormat()).isEqualTo("png");
    assertThat(asset.mimeType()).isEqualTo("image/png");
    assertThat(asset.contentSha256()).isEqualTo(MediaRules.sha256(content));
    assertThat(asset.mediaType()).isEqualTo("IMAGE");
    assertThat(asset.currentVersion()).isEqualTo(1);
    // 存储键带租户段：一个租户算不出另一个租户的键。
    assertThat(asset.storageKey()).startsWith("media/" + a + "/");
    assertThat(asset.storageKey()).endsWith(".png");

    // 字节真的落盘了，而且内容与上传的一致（不是只写了个元数据行）。
    Path onDisk = storageRoot.resolve(asset.storageKey());
    assertThat(Files.exists(onDisk)).isTrue();
    assertThat(MediaRules.sha256(Files.readAllBytes(onDisk))).isEqualTo(asset.contentSha256());

    // 时长是「未知」而不是 0：视频才有时长，图片留空。
    assertThat(asset.durationSeconds()).isNull();
    // 标签按编码整体挂上。
    assertThat(detail.tags()).containsKey("WHITE-BG").containsKey("HERO");
  }

  @Test
  void nonMediaContentAndMismatchedDeclaredTypeAreRejected() {
    // 探测不出来就拒，不猜一个类型：猜出来的类型要到刊登那天才暴露。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.upload(
                            actor,
                            file("x.png", garbage()),
                            uploadInput("假图片", "IMAGE", "PRIMARY", List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    // 声明类型与内容不符：拿 IMAGE 的表单传一张真 PNG 之外的字节，或反过来。
    byte[] content = png(10, 10, new Color(1, 2, 3));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.upload(
                            actor,
                            file("x.png", content),
                            new MediaUpload("谎报", "VIDEO", "VIDEO", null, null, List.of(), true))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("与文件内容不符"));

    // 空文件。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.upload(
                            actor,
                            file("x.png", new byte[0]),
                            uploadInput("空文件", "IMAGE", "PRIMARY", List.of()))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void sameContentIsReusedInsteadOfDuplicated() {
    // 「素材库复用」：同租户内相同字节只有一行素材，不重复写对象。
    byte[] content = png(60, 40, new Color(200, 100, 50));
    var first =
        write(
            actor,
            () ->
                media.upload(
                    actor,
                    file("复用.png", content),
                    uploadInput("复用-第一次", "IMAGE", "GALLERY", List.of())));
    var second =
        write(
            actor,
            () ->
                media.upload(
                    actor,
                    file("复用-again.png", content),
                    uploadInput("复用-第二次", "IMAGE", "GALLERY", List.of())));
    assertThat(second.deduplicated()).isTrue();
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery("复用", null, null, null, null, null, null, 0, 200)))
                .total())
        .isEqualTo(1);

    // 不同内容则是两条。
    var other =
        write(
            actor,
            () ->
                media.upload(
                    actor,
                    file("不同.png", png(60, 40, new Color(1, 2, 3))),
                    uploadInput("不同内容", "IMAGE", "GALLERY", List.of())));
    assertThat(other.id()).isNotEqualTo(first.id());
    assertThat(other.deduplicated()).isFalse();

    // 跨租户的相同内容**不复用**：素材是租户级资产，B 租户不能引用 A 租户的存储键。
    var otherTenant =
        write(
            actorB,
            () ->
                media.upload(
                    actorB,
                    file("B租户.png", content),
                    uploadInput("B 租户素材", "IMAGE", "GALLERY", List.of())));
    assertThat(otherTenant.id()).isNotEqualTo(first.id());
    assertThat(tx(actorB, () -> media.detail(otherTenant.id())).asset().storageKey())
        .startsWith("media/" + b + "/");
  }

  // ------------------------------------------------------------ 版本

  @Test
  void versionsAccumulateAndOldOnesSurvive() {
    byte[] v1 = png(30, 20, new Color(9, 9, 9));
    UUID id = upload(actor, "版本素材", v1, List.of());
    assertThat(tx(actor, () -> media.versions(id))).hasSize(1);

    byte[] v2 = png(40, 20, new Color(8, 8, 8));
    var after =
        write(
            actor,
            () ->
                media.addVersion(actor, id, file("v2.png", v2), new MediaVersionUpload("换成 40 宽")));
    assertThat(after.currentVersion()).isEqualTo(2);

    var versions = tx(actor, () -> media.versions(id));
    assertThat(versions).hasSize(2);
    // 倒序：v2 在前，且标记为当前版本。
    assertThat(versions.get(0).versionNo()).isEqualTo(2);
    assertThat(versions.get(0).current()).isTrue();
    assertThat(versions.get(0).note()).isEqualTo("换成 40 宽");
    assertThat(versions.get(1).versionNo()).isEqualTo(1);
    assertThat(versions.get(1).current()).isFalse();

    // 素材本体的当前版本字段由 DB 触发器同步过来了（不是服务层自己回写的）。
    var asset = tx(actor, () -> media.detail(id)).asset();
    assertThat(asset.currentVersion()).isEqualTo(2);
    assertThat(asset.contentSha256()).isEqualTo(MediaRules.sha256(v2));
    assertThat(asset.widthPx()).isEqualByComparingTo("40");
    // 旧版本的对象还在盘上：3.4.1.2 要求「原图始终保留」。
    assertThat(Files.exists(storageRoot.resolve(versions.get(1).storageKey()))).isTrue();

    // 版本表结构断言：版本号在同一素材内唯一。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into media_asset_version(id,tenant_id,asset_id,version_no,storage_key,"
                                + "original_filename,file_format,mime_type,byte_size,content_sha256)"
                                + " values(?,?,?,?,?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            a,
                            id,
                            2,
                            "media/x/y.png",
                            "y.png",
                            "png",
                            "image/png",
                            BigDecimal.TEN,
                            "0".repeat(64))))
        .isInstanceOf(Exception.class);
  }

  @Test
  void bypassingTheServiceCannotMakeMediaDriftFromItsVersions() {
    UUID id = upload(actor, "防漂移", png(12, 12, new Color(4, 4, 4)), List.of());
    // 直接改 media_asset 的字节数：V18 的 guard_media_asset_current_version 必须撞下来。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () -> sql.execute("update media_asset set byte_size=999999 where id=?", id)))
        .hasMessageContaining("当前版本字段与版本表不一致");
  }

  // ------------------------------------------------------------ 引用计数与删除保护

  @Test
  void referencesAreCountedAndBlockDeletion() {
    UUID id = upload(actor, "被引用的素材", png(15, 15, new Color(7, 7, 7)), List.of());
    UUID p = spu(actor, unique("SPU"));
    UUID k = sku(actor, p, unique("SKU"));

    // 无引用时 deletable=true。
    var detail = tx(actor, () -> media.detail(id));
    assertThat(detail.deletable()).isTrue();
    assertThat(detail.references()).isEmpty();

    var ref =
        write(
            actor,
            () -> media.addReference(actor, id, new MediaReferenceInput("SPU", p, null, "主图位")));
    assertThat(ref.referenceCount()).isEqualTo(1);
    assertThat(ref.deletable()).isFalse();
    assertThat(ref.deletableReason()).contains("仍被 1 处引用");

    // 引用列表能回答「被谁引用」，而不是只给一个数字。
    var references = tx(actor, () -> media.references(id));
    assertThat(references).hasSize(1);
    assertThat(references.getFirst().ownerType()).isEqualTo("SPU");
    assertThat(references.getFirst().spuId()).isEqualTo(p);
    assertThat(references.getFirst().ownerName()).isNotBlank();

    // 再加一条 SKU 引用。
    write(
        actor, () -> media.addReference(actor, id, new MediaReferenceInput("SKU", null, k, null)));
    assertThat(tx(actor, () -> media.references(id))).hasSize(2);
    // 列表行上就能看出删不掉。
    var row =
        tx(
                actor,
                () -> media.list(new MediaQuery(null, null, null, null, null, null, null, 0, 200)))
            .items()
            .stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(row.referenceCount()).isEqualTo(2);

    // 删除保护：应用层给可读原因。
    long currentVersion = tx(actor, () -> media.detail(id)).asset().version();
    var blocked =
        assertThrows(
            ResponseStatusException.class,
            () -> writeVoid(actor, () -> media.delete(actor, id, currentVersion)));
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(blocked.getMessage()).contains("仍被 2 处引用");
    assertThat(tx(actor, () -> media.detail(id))).isNotNull();

    // DB 层兜底：绕过应用层直接删（这模拟并发窗口：判完之后、删除之前被挂上引用）。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> sql.execute("delete from media_asset where id=?", id)))
        .hasMessageContaining("仍被引用");

    // 解除一条引用后可删；两条都解除后删除成功，且**所有版本对象**一并消失。
    var refs = tx(actor, () -> media.references(id));
    for (var each : refs) write(actor, () -> media.removeReference(actor, id, each.id()));
    assertThat(tx(actor, () -> media.references(id))).isEmpty();

    var keys = tx(actor, () -> media.versions(id)).stream().map(MediaVersion::storageKey).toList();
    long version = tx(actor, () -> media.detail(id)).asset().version();
    writeVoid(actor, () -> media.delete(actor, id, version));
    assertThatThrownBy(() -> tx(actor, () -> media.detail(id)))
        .isInstanceOf(ResponseStatusException.class);
    for (String key : keys) assertThat(Files.exists(storageRoot.resolve(key))).isFalse();
  }

  @Test
  void duplicateReferencesAndCrossTenantTargetsAreRejected() {
    UUID id = upload(actor, "引用校验", png(16, 16, new Color(5, 5, 5)), List.of());
    UUID p = spu(actor, unique("SPU"));
    write(
        actor, () -> media.addReference(actor, id, new MediaReferenceInput("SPU", p, null, null)));
    // 重复引用必须被拒：引用计数要是被引对象的真实条数，翻倍会让删除保护看到假数字。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.addReference(
                            actor, id, new MediaReferenceInput("SPU", p, null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(tx(actor, () -> media.references(id))).hasSize(1);

    // 形状不对：SPU 引用带 skuId。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.addReference(
                            actor, id, new MediaReferenceInput("SPU", p, UUID.randomUUID(), null))))
        .isInstanceOf(ResponseStatusException.class);
    // 不支持的引用类型：listing 由 04 交付后走 event 写入。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.addReference(
                            actor, id, new MediaReferenceInput("LISTING", null, null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("04 刊登"));

    // 指向不存在 / 不属于本租户的 SPU。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.addReference(
                            actor,
                            id,
                            new MediaReferenceInput("SPU", UUID.randomUUID(), null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("商品不存在或不属于当前租户"));
  }

  @Test
  void referencesOfAnOwnerCanBeQueriedInReverse() {
    UUID id = upload(actor, "反向查询", png(18, 18, new Color(3, 3, 3)), List.of());
    UUID p = spu(actor, unique("SPU"));
    write(
        actor, () -> media.addReference(actor, id, new MediaReferenceInput("SPU", p, null, null)));
    assertThat(tx(actor, () -> media.referencesOf("SPU", p, null)))
        .extracting(MediaRow::id)
        .contains(id);
    assertThat(tx(actor, () -> media.referencesOf("SPU", spu(actor, unique("OTHER")), null)))
        .isEmpty();
  }

  // ------------------------------------------------------------ 失效检测

  @Test
  void integrityCheckReadsTheObjectBackAndCatchesRealDrift() throws Exception {
    byte[] content = png(24, 24, new Color(6, 6, 6));
    UUID id = upload(actor, "失效检测", content, List.of());
    var asset = tx(actor, () -> media.detail(id)).asset();
    assertThat(asset.integrityStatus()).isEqualTo("UNKNOWN");

    // 一切正常时验出 VERIFIED。
    var ok = write(actor, () -> media.verify(id));
    assertThat(ok.integrityStatus()).isEqualTo("VERIFIED");
    assertThat(ok.healthy()).isTrue();
    assertThat(ok.check().sha256Actual()).isEqualTo(asset.contentSha256());

    // 真的把文件删掉：再验必须报 MISSING（这是「失效检测」存在的意义）。
    Files.delete(storageRoot.resolve(asset.storageKey()));
    var missing = write(actor, () -> media.verify(id));
    assertThat(missing.integrityStatus()).isEqualTo("MISSING");
    assertThat(missing.healthy()).isFalse();
    assertThat(missing.check().sha256Actual()).isNull();
    assertThat(tx(actor, () -> media.detail(id)).asset().integrityStatus()).isEqualTo("MISSING");

    // 覆盖成同尺寸但不同内容 → CONTENT_MISMATCH。
    // 刻意用「复制原字节再翻转中间一个字节」而不是换一种颜色：PNG 是内容相关的压缩，
    // 换颜色很可能连字节数都变（那就变成 SIZE_MISMATCH，测不到我们要测的那条分支）。
    byte[] other = content.clone();
    other[other.length / 2] = (byte) (other[other.length / 2] ^ 0xFF);
    assertThat(other.length).isEqualTo(content.length);
    Files.write(storageRoot.resolve(asset.storageKey()), other);
    var swapped = write(actor, () -> media.verify(id));
    assertThat(swapped.integrityStatus()).isEqualTo("CONTENT_MISMATCH");

    // 校验历史不覆盖：三次结果都在，按时间倒序。
    var checks = tx(actor, () -> media.integrityChecks(id));
    assertThat(checks).hasSize(3);
    assertThat(checks.get(0).integrityStatus()).isEqualTo("CONTENT_MISMATCH");
    assertThat(checks.get(1).integrityStatus()).isEqualTo("MISSING");
    assertThat(checks.get(2).integrityStatus()).isEqualTo("VERIFIED");

    // 概览把失效数与缺失数报出来。
    var overview = tx(actor, () -> media.overview());
    assertThat(overview.failingIntegrityCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.missingCount()).isGreaterThanOrEqualTo(0);
    assertThat(overview.storageHealthy()).isEqualTo(1);
  }

  @Test
  void sweepReportsPerStatusCounts() throws Exception {
    UUID ok = upload(actor, "巡检-正常", png(21, 21, new Color(11, 11, 11)), List.of());
    UUID bad = upload(actor, "巡检-损坏", png(22, 22, new Color(12, 12, 12)), List.of());
    Files.delete(storageRoot.resolve(tx(actor, () -> media.detail(bad)).asset().storageKey()));
    var sweep = write(actor, () -> media.verifyAll());
    assertThat(sweep.checkedCount()).isGreaterThanOrEqualTo(2);
    assertThat(
            sweep.results().stream()
                .filter(r -> r.assetId().equals(ok))
                .findFirst()
                .orElseThrow()
                .integrityStatus())
        .isEqualTo("VERIFIED");
    assertThat(
            sweep.results().stream()
                .filter(r -> r.assetId().equals(bad))
                .findFirst()
                .orElseThrow()
                .integrityStatus())
        .isEqualTo("MISSING");
    assertThat(sweep.healthyCount()).isGreaterThanOrEqualTo(1);
    assertThat(sweep.missingCount()).isGreaterThanOrEqualTo(1);
  }

  // ------------------------------------------------------------ 分类标签

  @Test
  void tagsAreADictionaryAndCannotBeDeletedWhileInUse() {
    MediaTagSaved tag =
        write(
            actor,
            () ->
                media.saveTag(
                    actor, null, new MediaTagInput(unique("TAG"), "白底", "拍摄要求", true, 0)));
    assertThat(tag.usageCount()).isZero();

    UUID id = upload(actor, "带标签素材", png(19, 19, new Color(13, 13, 13)), List.of(tag.code()));
    assertThat(tx(actor, () -> media.detail(id)).tags()).containsKey(tag.code());

    // 按标签筛选走的是关联表，不是字符串比。
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(null, null, null, tag.code(), null, null, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .contains(id);

    // 仍被使用时不允许删除，要清理用停用。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> media.deleteTag(actor, tag.id(), tag.version())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("仍被 1 个素材使用");
    writeVoid(actor, () -> media.setTagEnabled(actor, tag.id(), false));
    assertThat(
            tx(actor, () -> media.tags()).stream()
                .filter(t -> t.id().equals(tag.id()))
                .findFirst()
                .orElseThrow()
                .enabled())
        .isFalse();

    // 编码建后不可改（筛选条件按 code 对齐）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.saveTag(
                            actor,
                            tag.id(),
                            new MediaTagInput(unique("OTHER"), "改名", null, true, 1))))
        .isInstanceOf(ResponseStatusException.class);
    // 编码重复 409。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        media.saveTag(
                            actor, null, new MediaTagInput(tag.code(), "重复", null, true, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // 停用后删除（此时删得掉：引用是素材侧的关联，标签已解绑）。
    write(
        actor,
        () ->
            media.update(
                actor,
                id,
                new MediaUpdate(
                    "带标签素材",
                    "GALLERY",
                    null,
                    null,
                    List.of(),
                    true,
                    tx(actor, () -> media.detail(id)).asset().version())));
    var fresh =
        tx(actor, () -> media.tags()).stream()
            .filter(t -> t.id().equals(tag.id()))
            .findFirst()
            .orElseThrow();
    writeVoid(actor, () -> media.deleteTag(actor, tag.id(), fresh.version()));
    assertThat(tx(actor, () -> media.tags()).stream().map(MediaTagRow::id))
        .doesNotContain(tag.id());
  }

  @Test
  void uploadingWithAnUnknownTagCodeCreatesTheTag() {
    String code = unique("AUTO");
    UUID id = upload(actor, "自动建标签", png(17, 17, new Color(14, 14, 14)), List.of(code));
    assertThat(tx(actor, () -> media.detail(id)).tags()).containsKey(code);
    assertThat(tx(actor, () -> media.tags()).stream().map(MediaTagRow::code)).contains(code);
  }

  // ------------------------------------------------------------ 列表：分页 / 搜索 / 筛选

  @Test
  void listPagesSearchesAndFiltersOnRealRows() {
    String mark = unique("MK");
    UUID gallery =
        upload(actor, mark + "-附图", png(31, 31, new Color(21, 21, 21)), List.of("GALLERY-TAG"));
    UUID primary =
        write(
                actor,
                () ->
                    media.upload(
                        actor,
                        file(mark + "-主图.png", png(32, 32, new Color(22, 22, 22))),
                        uploadInput(mark + "-主图", "IMAGE", "PRIMARY", List.of("PRIMARY-TAG"))))
            .id();
    UUID off = upload(actor, mark + "-停用", png(33, 33, new Color(23, 23, 23)), List.of());
    writeVoid(actor, () -> media.setEnabled(actor, off, false));

    // 关键词命中名称。
    var hit =
        tx(
            actor,
            () -> media.list(new MediaQuery(mark, null, null, null, null, null, null, 0, 200)));
    assertThat(hit.total()).isEqualTo(3);
    assertThat(hit.items()).extracting(MediaRow::id).contains(gallery, primary, off);
    // 原始文件名也能搜到。
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(
                                mark + "-主图.png", null, null, null, null, null, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .containsExactly(primary);
    // LIKE 通配符按字面处理。
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(new MediaQuery("%", null, null, null, null, null, null, 0, 200)))
                .items())
        .isEmpty();

    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(mark, "IMAGE", null, null, null, null, null, 0, 200)))
                .items())
        .hasSize(3);
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(
                                mark, "IMAGE", "PRIMARY", null, null, null, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .containsExactly(primary);
    // enabled=TRUE：第三个素材已停用，不该出现（注意 referenced 位传 null）。
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(
                                mark, "IMAGE", null, null, null, null, Boolean.TRUE, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .containsExactlyInAnyOrder(gallery, primary);
    // 非法筛选值是 400 而不是「筛不出东西」。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(mark, "AUDIO", null, null, null, null, null, 0, 200))))
        .isInstanceOf(ResponseStatusException.class);

    // 引用状态筛选：无引用的三条都在，引用一条后只剩两条。
    UUID p = spu(actor, unique("SPU"));
    write(
        actor,
        () -> media.addReference(actor, primary, new MediaReferenceInput("SPU", p, null, null)));
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(
                                mark, null, null, null, null, Boolean.FALSE, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .containsExactlyInAnyOrder(gallery, off);
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(
                                mark, null, null, null, null, Boolean.TRUE, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .containsExactly(primary);

    // 分页是真的分页。
    var page =
        tx(actor, () -> media.list(new MediaQuery(mark, null, null, null, null, null, null, 0, 1)));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(3);
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(mark, null, null, null, null, null, null, 0, 9999)))
                .size())
        .isEqualTo(200);
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    String mark = unique("ISO");
    byte[] mineContent = png(41, 41, new Color(31, 31, 31));
    UUID mine = upload(actor, mark + "-A 租户", mineContent, List.of());
    UUID mineSpu = spu(actor, unique("SPU"));
    write(
        actor,
        () -> media.addReference(actor, mine, new MediaReferenceInput("SPU", mineSpu, null, null)));

    UUID theirs = upload(actorB, mark + "-B 租户", png(42, 42, new Color(32, 32, 32)), List.of());
    UUID theirSpu = spu(actorB, unique("SPU"));
    write(
        actorB,
        () ->
            media.addReference(
                actorB, theirs, new MediaReferenceInput("SPU", theirSpu, null, null)));

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> media.detail(mine)).asset().name()).isEqualTo(mark + "-A 租户");
    assertThat(tx(actorB, () -> media.detail(theirs)).asset().name()).isEqualTo(mark + "-B 租户");
    assertThatThrownBy(() -> tx(actorB, () -> media.detail(mine)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> media.detail(theirs)))
        .isInstanceOf(ResponseStatusException.class);
    // 列表不串。
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery(mark, null, null, null, null, null, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .contains(mine)
        .doesNotContain(theirs);
    assertThat(
            tx(
                    actorB,
                    () ->
                        media.list(
                            new MediaQuery(mark, null, null, null, null, null, null, 0, 200)))
                .items())
        .extracting(MediaRow::id)
        .contains(theirs)
        .doesNotContain(mine);
    // 引用不串：B 租户不能给 A 租户的素材挂引用。
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        media.addReference(
                            actorB, mine, new MediaReferenceInput("SPU", theirSpu, null, null))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> media.references(mine))).hasSize(1);
    // 跨租户改 / 删都不该动到对方的行。
    long mineVersion = tx(actor, () -> media.detail(mine)).asset().version();
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        media.update(
                            actorB,
                            mine,
                            new MediaUpdate(
                                "被改", "GALLERY", null, null, List.of(), true, mineVersion))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> media.detail(mine)).asset().name()).isEqualTo(mark + "-A 租户");
    // 失效检测也不能跨租户去动对方的对象存储键。
    assertThatThrownBy(() -> tx(actorB, () -> media.verify(mine)))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/media/" + mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/media/overview")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(
            multipart("/catalog/media")
                .file(file("forged.png", png(9, 9, new Color(90, 90, 90))))
                .file(
                    new MockMultipartFile(
                        "meta",
                        "",
                        "application/json",
                        "{\"name\":\"伪造\",\"mediaType\":\"IMAGE\",\"purpose\":\"GALLERY\",\"tagCodes\":[],\"enabled\":true}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk());
    // 伪造头写入的内容进的是 B 租户（凭据说的那个），不是 X-Tenant-Id 声称的 A。
    assertThat(
            tx(
                    actorB,
                    () ->
                        media.list(
                            new MediaQuery("伪造", null, null, null, null, null, null, 0, 200)))
                .total())
        .isEqualTo(1);
    assertThat(
            tx(
                    actor,
                    () ->
                        media.list(
                            new MediaQuery("伪造", null, null, null, null, null, null, 0, 200)))
                .total())
        .isZero();

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> media.detail(mine)).asset().name()).isEqualTo(mark + "-A 租户");
      assertThat(tx(actorB, () -> media.detail(theirs)).asset().name()).isEqualTo(mark + "-B 租户");
      assertThatThrownBy(() -> tx(actorB, () -> media.detail(mine)))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> media.references(mine))).hasSize(1);
      assertThat(tx(actorB, () -> media.references(theirs))).hasSize(1);
      // 标签字典同样不串：给 A 建一个标签，B 的字典里不能出现它。
      String tenantATag = unique("TAGA");
      writeVoid(
          actor,
          () -> media.saveTag(actor, null, new MediaTagInput(tenantATag, "A 租户标签", null, true, 0)));
      assertThat(tx(actor, () -> media.tags()).stream().map(MediaTagRow::code))
          .contains(tenantATag);
      assertThat(tx(actorB, () -> media.tags()).stream().map(MediaTagRow::code))
          .doesNotContain(tenantATag);
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    byte[] content = png(51, 51, new Color(41, 41, 41));
    var upload = new MockMultipartFile("file", "工作流.png", null, content);
    var meta =
        new MockMultipartFile(
            "meta",
            "",
            "application/json",
            ("{\"name\":\"工作流素材\",\"mediaType\":\"IMAGE\",\"purpose\":\"GALLERY\","
                    + "\"description\":\"端到端\",\"altText\":\"白底正面\",\"tagCodes\":[\""
                    + unique("WT")
                    + "\"],"
                    + "\"enabled\":true}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var created =
        http.perform(
                multipart("/catalog/media")
                    .file(upload)
                    .file(meta)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deduplicated").value(false))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.referenceCount").value(0))
            .andReturn();
    String body = created.getResponse().getContentAsString();
    var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    String id = parsed.get("id").asText();

    // 详情：探测出的元数据 + 标签。
    http.perform(get("/catalog/media/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asset.widthPx").value(51))
        .andExpect(jsonPath("$.asset.heightPx").value(51))
        .andExpect(jsonPath("$.asset.fileFormat").value("png"))
        .andExpect(jsonPath("$.asset.mediaTypeLabel").value("图片"))
        .andExpect(jsonPath("$.asset.integrityStatus").value("UNKNOWN"))
        .andExpect(jsonPath("$.deletable").value(true))
        .andExpect(jsonPath("$.versions.length()").value(1));

    // 列表与概览。
    http.perform(get("/catalog/media").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").isNumber());
    http.perform(get("/catalog/media/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assetCount").isNumber())
        .andExpect(jsonPath("$.storageHealthy").value(1));
    http.perform(get("/catalog/media/tags").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    // 校验。
    http.perform(
            post("/catalog/media/" + id + "/verify").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.integrityStatus").value("VERIFIED"))
        .andExpect(jsonPath("$.healthy").value(true));
    http.perform(post("/catalog/media/verify-sweep").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkedCount").isNumber());
    http.perform(
            get("/catalog/media/" + id + "/integrity-checks")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].integrityStatus").value("VERIFIED"));

    // 未登录 401。
    http.perform(get("/catalog/media")).andExpect(status().isUnauthorized());
    // 只有读权限不能写。
    http.perform(post("/catalog/media/" + id + "/verify").header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    // 探测不出来是 400，不是 500。
    http.perform(
            multipart("/catalog/media")
                .file(new MockMultipartFile("file", "bad.png", null, garbage()))
                .file(
                    new MockMultipartFile(
                        "meta",
                        "",
                        "application/json",
                        "{\"name\":\"坏文件\",\"mediaType\":\"IMAGE\",\"purpose\":\"GALLERY\",\"tagCodes\":[],\"enabled\":true}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void readEndpointsWorkForReadOnlyRole() throws Exception {
    UUID id = upload(actor, "只读可见", png(52, 52, new Color(42, 42, 42)), List.of());
    http.perform(get("/catalog/media/overview").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/media/" + id).header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/media/tags").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            put("/catalog/media/" + id + "/enabled?enabled=false")
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    http.perform(
            delete("/catalog/media/" + id + "?version=0").header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
  }

  static org.springframework.test.web.servlet.result.JsonPathResultMatchers jsonPath(String path) {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(path);
  }
}
