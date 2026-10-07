package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.ExportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.8.2.1 导出：字段选择、排序还原、异步生成、下载有效期、PII 授权、数据范围过滤，
 * 以及租户隔离三断言。真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID owner = UUID.randomUUID(), scoped = UUID.randomUUID();
  static HttpServer identity;
  static Path storageRoot;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

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
      storageRoot = Path.of("target", "export-store-test").toAbsolutePath();
      if (Files.exists(storageRoot))
        try (var walk = Files.walk(storageRoot)) {
          walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
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
      actors.put("Bearer tenant-a", actor(owner, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(owner, b, null, List.of(grant("ALL", allPermissions))));
      // SELF 数据范围用户：只能看见自己创建/拥有的记录（V3 data_scope 策略）。
      actors.put(
          "Bearer scoped",
          actor(
              scoped,
              a,
              null,
              List.of(
                  grant(
                      "SELF",
                      List.of(
                          "catalog.spu.read",
                          "catalog.spu.create",
                          "catalog.spu.update",
                          "catalog.sku.read",
                          "catalog.sku.create")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
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
    r.add("MEDIA_STORAGE_ROOT", () -> storageRoot.toString());
    // 单连接池：连接归还后复用，才能真正证明不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ExportJobService jobs;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor() {
    return actors.get("Bearer tenant-a");
  }

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ================================================================ fixture

  /** 建一个带 n 个 SKU 的 SPU，返回 (spuId, skuIds)。 */
  private record Fixture(UUID spuId, List<UUID> skuIds) {}

  private Fixture fixture(AccessService.Access who, String prefix, int skus) {
    // SPU 一律先建为 DRAFT：新商品必须先保存为草稿、补齐 SKU 后再提交待上架
    // （CatalogService 的真实规则）。导出测试不需要 SPU 本身启用。
    UUID spuId =
        call(
            who,
            "catalog.spu.create",
            () ->
                CatalogServiceHolder.catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        prefix + "-" + UUID.randomUUID(),
                        prefix + " 商品",
                        "",
                        "品牌",
                        "家居",
                        "DRAFT",
                        0)));
    // 规格轴（颜色）：每个 SKU 取一个不同的取值，于是 variant_signature 在同一 SPU 内唯一
    // —— V1 的 UNIQUE(tenant_id,spu_id,variant_signature) 是真实约束，
    // 不造出不同的规格签名就建不出第二个 SKU（而导出的行序断言需要不止一行）。
    final List<String> colors =
        List.of("黑色", "白色", "蓝色", "金色", "银色", "灰色", "灰色2", "灰色3");
    final long spuVersion0 =
        call(who, "catalog.spu.read", () -> CatalogServiceHolder.catalog.detail(spuId))
            .product()
            .version();
    call(
        who,
        "catalog.attribute.create",
        () -> {
          CatalogServiceHolder.catalog.saveAttribute(
              who,
              spuId,
              null,
              new AttributeInput(
                  "COLOR",
                  "颜色",
                  "OPTION",
                  "SKU_ONLY",
                  false,
                  true,
                  true,
                  "",
                  "规格",
                  colors,
                  List.of(),
                  spuVersion0));
          return null;
        });
    var axisId =
        call(who, "catalog.spu.read", () -> CatalogServiceHolder.catalog.attributes(spuId)).stream()
            .filter(x -> x.variantAxis())
            .findFirst()
            .orElseThrow()
            .id();

    var ids = new ArrayList<UUID>();
    for (int i = 0; i < skus; i++) {
      final int index = i;
      // 每次都重新读 SPU 版本：saveSku 会推进它，写死 0 会在第二个 SKU 上被乐观锁挡住。
      final long spuVersion =
          call(who, "catalog.spu.read", () -> CatalogServiceHolder.catalog.detail(spuId))
              .product()
              .version();
      UUID skuId =
          call(
              who,
              "catalog.sku.create",
              () ->
                  CatalogServiceHolder.catalog.saveSku(
                      who,
                      spuId,
                      null,
                      new SkuInput(
                          prefix + "-SKU" + index + "-" + UUID.randomUUID().toString().substring(0, 6),
                          prefix + " 规格" + index,
                          "BC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                          "PCS",
                          "ACTIVE",
                          List.of(
                              new ValueInput(
                                  axisId, "SET", List.of(colors.get(index % colors.size())))),
                          spuVersion)));
      ids.add(skuId);
    }
    return new Fixture(spuId, ids);
  }

  /** Spring 注入的 CatalogService 走静态持有，避免在 fixture 里再 @Autowired 一份。 */
  static final class CatalogServiceHolder {
    static CatalogService catalog;
  }

  @BeforeEach
  void injectCatalog(@Autowired CatalogService catalog) {
    CatalogServiceHolder.catalog = catalog;
  }

  // ================================================================ 等待异步完成

  /** 轮询到任务进入终态。异步是蓝图要求，不能假设 POST 返回时文件已存在。 */
  private ExportJobRow await(UUID id, AccessService.Access who) {
    ExportJobRow last = null;
    for (int i = 0; i < 200; i++) {
      last = call(who, "catalog.spu.read", () -> jobs.job(who.tenantId(), id));
      if (!List.of("PENDING", "RUNNING").contains(last.status())) return last;
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("导出任务未在预期时间内结束，状态：" + last.status());
  }

  private ExportJobRow start(AccessService.Access who, ExportJobInput input) {
    return call(who, "catalog.spu.read", () -> jobs.create(who, input));
  }

  private static ExportFilterInput noFilter() {
    return null;
  }

  private static ExportFilterInput skuFilter(UUID spuId) {
    return filter("spuId", spuId);
  }

  /**
   * 按键名构造筛选条件（与 SkuListIntegrationTest 的 query() 同一手法）。
   *
   * <p>不直接写 18 个位置参数：数错一个 null 的后果是「条件悄悄落到了另一个字段上」，
   * 而这类错误在断言里往往看不出来。
   */
  private static ExportFilterInput filter(Object... kv) {
    var map = new HashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
    return new ExportFilterInput(
        (UUID) map.get("spuId"),
        (String) map.get("category"),
        (List<String>) map.get("status"),
        (String) map.get("q"),
        (String) map.get("barcode"),
        (String) map.get("spec"),
        (String) map.get("brand"),
        (UUID) map.get("partyId"),
        (String) map.get("partyType"),
        (Boolean) map.get("primary"),
        (String) map.get("roleType"),
        (java.math.BigDecimal) map.get("onHandMin"),
        (java.math.BigDecimal) map.get("onHandMax"),
        (java.math.BigDecimal) map.get("priceMin"),
        (java.math.BigDecimal) map.get("priceMax"),
        (Boolean) map.get("outOfStock"),
        (Boolean) map.get("onSale"),
        (String) map.get("platform"));
  }

  /** 拆一行 CSV：只认不在引号内的逗号，否则值里带逗号就会被切成两半。 */
  private static List<String> splitCsvRow(String line) {
    var out = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else quoted = false;
        } else cell.append(c);
      } else if (c == '"') quoted = true;
      else if (c == ',') {
        out.add(cell.toString());
        cell.setLength(0);
      } else cell.append(c);
    }
    out.add(cell.toString());
    return out;
  }

  /** 去掉 UTF-8 BOM：它只对 Excel 有意义，断言行数时不该把它算进去。 */
  private static String stripBom(String csv) {
    return csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
  }

  // ================================================================ 能力声明

  @Test
  void everySourceDeclaresItsOwnFieldsSortsAndFilters() {
    var page = call(actor(), "catalog.spu.read", () -> jobs.sourcePage());
    assertThat(page.items()).extracting(ExportSourceInfo::key).contains("SPU", "SKU", "CONTACT");
    var sku =
        page.items().stream().filter(s -> s.key().equals("SKU")).findFirst().orElseThrow();
    // 字段与排序都由后端声明，前端不硬编码。
    assertThat(sku.fields()).extracting(ExportFieldInfo::key).contains("CODE", "NAME", "BARCODE");
    assertThat(sku.sorts()).extracting(ExportSortInfo::key).contains("updated_at", "code");
    // 依赖未接入上游投影的筛选条件必须显式标为不可用，并写明归属。
    var onHand =
        sku.filters().stream().filter(f -> f.key().equals("onHandMin")).findFirst().orElseThrow();
    assertThat(onHand.available()).isFalse();
    assertThat(onHand.note()).contains("07 库存");
    // PII 标记来自字段定义本身。
    var contact =
        page.items().stream().filter(s -> s.key().equals("CONTACT")).findFirst().orElseThrow();
    assertThat(contact.fields()).filteredOn(ExportFieldInfo::pii).extracting(ExportFieldInfo::key)
        .containsExactlyInAnyOrder("EMAIL", "PHONE", "MOBILE");
    assertThat(contact.fields()).filteredOn(f -> !f.pii()).extracting(ExportFieldInfo::key)
        .contains("PARTY_CODE", "NAME");
  }

  // ================================================================ 字段选择

  @Test
  void onlySelectedFieldsAreWrittenAndTheirOrderIsTheSourceOrder() {
    var who = actor();
    var f = fixture(who, "FLD", 2);
    var job =
        start(
            who,
            new ExportJobInput(
                "SKU",
                // 故意乱序提交：服务层按数据源定义的列顺序输出，而不是用户勾选的顺序。
                List.of("BARCODE", "CODE"),
                skuFilter(f.spuId()),
                new ExportSortInput("code", "asc"),
                null));
    var done = await(job.id(), who);

    assertThat(done.status()).isEqualTo("SUCCEEDED");
    assertThat(done.rowCount()).isEqualTo(2);
    assertThat(done.headers()).containsExactly("SKU 编码", "条码");
    assertThat(done.fields()).containsExactly("CODE", "BARCODE");

    var csv = download(who, done.id());
    var lines = stripBom(csv).strip().split("\n");
    assertThat(lines[0]).isEqualTo("\"SKU 编码\",\"条码\"");
    assertThat(lines).hasSize(3); // 表头 + 2 行
    // 逐格与库里的真实值比对：列序错了（CODE 与 BARCODE 互换）这里就会暴露。
    var expectedPairs = new java.util.HashSet<List<String>>();
    for (UUID id : f.skuIds())
      expectedPairs.add(
          List.of(
              call(who, "catalog.sku.read",
                  () -> sql.fetchOne("select code from sku where id=?", id).get("code", String.class)),
              call(who, "catalog.sku.read",
                  () -> sql.fetchOne("select barcode from sku where id=?", id).get("barcode", String.class))));
    assertThat(dataRows(lines))
        .allSatisfy(
            line -> {
              var cells = splitCsvRow(line);
              assertThat(cells).hasSize(2);
              assertThat(expectedPairs).contains(cells);
            });
  }

  @Test
  void unknownFieldOrSortIsRejectedWithTheAllowedList() {
    var who = actor();
    assertThatThrownBy(
            () ->
                start(
                    who,
                    new ExportJobInput(
                        "SKU", List.of("CODE", "NOT_A_FIELD"), noFilter(), null, null)))
        .hasMessageContaining("NOT_A_FIELD")
        .hasMessageContaining("CODE");
    assertThatThrownBy(
            () ->
                start(
                    who,
                    new ExportJobInput(
                        "SKU",
                        List.of("CODE"),
                        noFilter(),
                        new ExportSortInput("not_a_sort", "asc"),
                        null)))
        .hasMessageContaining("not_a_sort");
    // 字段来自别的数据源：必须点名，而不是静默忽略。
    assertThatThrownBy(
            () ->
                start(
                    who,
                    new ExportJobInput(
                        "SPU", List.of("BARCODE"), noFilter(), null, null)))
        .hasMessageContaining("BARCODE");
    // 一个字段都不选也是非法请求。
    assertThatThrownBy(
            () -> start(who, new ExportJobInput("SKU", List.of(), noFilter(), null, null)))
        .hasMessageContaining("字段");
  }

  // ================================================================ 排序还原

  @Test
  void sortIsRestoredInBothDirections() {
    var who = actor();
    // 三个 SKU 的编码本就互不相同，排序断言直接成立；不需要额外改动数据。
    var f = fixture(who, "SRT", 3);

    var asc = await(start(who, new ExportJobInput(
        "SKU", List.of("CODE"), skuFilter(f.spuId()), new ExportSortInput("code", "asc"), null)).id(), who);
    var desc = await(start(who, new ExportJobInput(
        "SKU", List.of("CODE"), skuFilter(f.spuId()), new ExportSortInput("code", "desc"), null)).id(), who);

    var ascCodes = codesInOrder(asc.id(), who);
    var descCodes = codesInOrder(desc.id(), who);
    assertThat(ascCodes).hasSize(3);
    assertThat(ascCodes).isSorted();
    assertThat(descCodes).containsExactlyElementsOf(new ArrayList<>(ascCodes).reversed());
    // 排序被真实还原进记录（审计要看这个），而不是只存在于生成过程里。
    assertThat(asc.sortKey()).isEqualTo("code");
    assertThat(asc.sortOrder()).isEqualTo("asc");
  }

  @Test
  void filtersAreFrozenIntoTheRecordForAudit() {
    var who = actor();
    var f = fixture(who, "FRZ", 2);
    var done = await(start(who, new ExportJobInput(
        "SKU",
        List.of("CODE"),
        filter("spuId", f.spuId(), "category", "家居", "status", List.of("ACTIVE"), "q", "FRZ"),
        new ExportSortInput("code", "asc"),
        null)).id(), who);
    assertThat(done.rowCount()).isEqualTo(2);
    assertThat(done.appliedFilters()).extracting(ExportAppliedFilter::key)
        .contains("spuId", "category", "status", "q");
    // 审计回显的是**标签**而不是键名，用户看得懂这份导出按什么条件做的。
    assertThat(done.appliedFilters()).extracting(ExportAppliedFilter::label)
        .contains("所属 SPU", "分类", "状态");
  }

  @Test
  void filterFromAnotherSourceIsRejectedNotSilentlyIgnored() {
    var who = actor();
    // brand 是 SPU 数据源的筛选条件，SKU 数据源不支持。
    assertThatThrownBy(
            () ->
                start(
                    who,
                    new ExportJobInput(
                        "SKU",
                        List.of("CODE"),
                        filter("brand", "某品牌"),
                        null,
                        null)))
        .hasMessageContaining("品牌");
    // 依赖未接入投影的条件要点名归属服务。
    assertThatThrownBy(
            () ->
                start(
                    who,
                    new ExportJobInput(
                        "SKU",
                        List.of("CODE"),
                        filter("onHandMin", new java.math.BigDecimal("1")),
                        null,
                        null)))
        .hasMessageContaining("07 库存");
  }

  // ================================================================ 异步 + 记录

  @Test
  void exportIsAsynchronousAndTheRecordIsTraceableToTheActor() {
    var who = actor();
    var f = fixture(who, "ASY", 2);
    var accepted = start(who, new ExportJobInput("SKU", List.of("CODE", "NAME"), skuFilter(f.spuId()), null, null));
    // POST 返回时文件**还没生成**：状态只能是已受理。
    assertThat(List.of("PENDING", "RUNNING")).contains(accepted.status());
    assertThat(accepted.fileName()).isNull();
    assertThat(accepted.rowCount()).isZero();
    assertThat(accepted.downloadable()).isFalse();
    // 蓝图点名的五项都在这一行上：条件、字段、行数、时间、操作人。
    assertThat(accepted.actorId()).isEqualTo(owner);
    assertThat(accepted.requestedAt()).isNotNull();

    var done = await(accepted.id(), who);
    assertThat(done.status()).isEqualTo("SUCCEEDED");
    assertThat(done.rowCount()).isEqualTo(2);
    assertThat(done.fileName()).endsWith(".csv");
    assertThat(done.contentSha256()).hasSize(64);
    assertThat(done.byteSize()).isPositive();
    assertThat(done.expiresAt()).isNotNull();
    assertThat(done.downloadable()).isTrue();
    // 内容真的是从库里查出来的：2 行 SKU 编码都在文件里。
    var csv = download(who, done.id());
    assertThat(csv).contains("SKU 编码").contains("SKU 名称");
    for (UUID id : f.skuIds())
      assertThat(csv).contains(call(who, "catalog.spu.read",
          () -> sql.fetchOne("select code from sku where id=?", id).get("code", String.class)));
  }

  // ================================================================ PII 授权

  @Test
  void piiExportRequiresAnExplicitPurposeAndIsAudited() {
    var who = actor();
    fixture(who, "PII", 1);
    // 敏感字段 + 空理由 → 拒绝。
    assertThatThrownBy(
            () -> start(who, new ExportJobInput(
                "CONTACT", List.of("PARTY_CODE", "EMAIL"), noFilter(), null, null)))
        .hasMessageContaining("敏感字段");

    var authorized = await(start(who, new ExportJobInput(
        "CONTACT", List.of("PARTY_CODE", "EMAIL", "MOBILE"), noFilter(), null, "跨境客服工单派单")
    ).id(), who);

    assertThat(authorized.includesPii()).isTrue();
    assertThat(authorized.piiFields()).containsExactly("EMAIL", "MOBILE");
    assertThat(authorized.piiPurpose()).isEqualTo("跨境客服工单派单");
    assertThat(authorized.actorId()).isEqualTo(owner);
    assertThat(authorized.requestedAt()).isNotNull();
    // 授权之后文件真的含这些列。
    assertThat(download(who, authorized.id())).contains("邮箱").contains("手机");

    // 不含敏感字段的导出不需要理由，且 includesPii=false。
    var plain = await(start(who, new ExportJobInput(
        "CONTACT", List.of("PARTY_CODE", "NAME"), noFilter(), null, null)).id(), who);
    assertThat(plain.includesPii()).isFalse();
    assertThat(plain.piiFields()).isEmpty();
  }

  @Test
  void piiExportWithoutPurposeCannotBeWrittenDirectlyIntoTheDatabase() {
    var who = actor();
    // DB 层第二道防线：绕过服务层直连 SQL 也插不进一条无理由的 PII 导出记录。
    assertThatThrownBy(
            () ->
                call(
                    who,
                    "catalog.spu.read",
                    () ->
                        sql.execute(
                            "insert into export_job(id,tenant_id,source_key,status,filter_snapshot,"
                                + "selected_fields,field_headers,sort_snapshot,includes_pii,pii_fields,"
                                + "pii_purpose,actor_id)"
                                + " values(?,?,'CONTACT','PENDING','{}'::jsonb,'[\"EMAIL\"]'::jsonb,"
                                + "'[\"邮箱\"]'::jsonb,'{}'::jsonb,true,'[\"EMAIL\"]'::jsonb,'',?)",
                            UUID.randomUUID(),
                            who.tenantId(),
                            owner)))
        .hasMessageContaining("export_job_pii_traceable");
  }

  @Test
  void snapshotsCannotBeRewrittenAfterCreation() {
    var who = actor();
    var done = await(start(who, new ExportJobInput(
        "SKU", List.of("CODE"), noFilter(), new ExportSortInput("code", "asc"), null)).id(), who);
    // 条件/字段/排序是创建那一刻的快照，事后改写会让「当时按什么导出的」没有答案。
    assertThatThrownBy(
            () ->
                call(
                    who,
                    "catalog.spu.read",
                    () ->
                        sql.execute(
                            "update export_job set selected_fields='[\"NAME\"]'::jsonb where tenant_id=? and id=?",
                            who.tenantId(),
                            done.id())))
        .hasMessageContaining("快照");
    // 操作人不可改写（审计主体）。
    assertThatThrownBy(
            () ->
                call(
                    who,
                    "catalog.spu.read",
                    () ->
                        sql.execute(
                            "update export_job set actor_id=? where tenant_id=? and id=?",
                            scoped,
                            who.tenantId(),
                            done.id())))
        .hasMessageContaining("操作人");
  }

  // ================================================================ 有效期与作废

  @Test
  void expiredExportIsGoneReturnsGoneAndDeletesTheFile() throws Exception {
    var who = actor();
    var done = await(start(who, new ExportJobInput("SKU", List.of("CODE"), noFilter(), null, null)).id(), who);
    var key = call(who, "catalog.spu.read",
        () -> sql.fetchOne("select storage_key from export_job where tenant_id=? and id=?",
            who.tenantId(), done.id()).get("storage_key", String.class));
    assertThat(call(who, "catalog.spu.read",
        () -> sql.fetchOne("select 1 from export_job where tenant_id=? and id=?",
            who.tenantId(), done.id()))).isNotNull();

    // 把有效期推到过去：走的是真实的 expires_at 列，不是测试里的假开关。
    call(who, "catalog.spu.read",
        () -> sql.execute(
            "update export_job set expires_at=now() - interval '1 hour' where tenant_id=? and id=?",
            who.tenantId(), done.id()));
    // 状态推进由服务层惰性过期触发（此时状态仍是 SUCCEEDED，可以合法迁到 EXPIRED）。
    var after = call(who, "catalog.spu.read", () -> jobs.job(who.tenantId(), done.id()));
    assertThat(after.status())
        .withFailMessage("状态=%s 执行错误=%s", after.status(), after.error())
        .isEqualTo("EXPIRED");
    assertThat(after.downloadable()).isFalse();
    assertThat(after.downloadNote()).contains("有效期");
    // 文件确实被删了，而不是只把状态改成"过期"好看一点。
    assertThat(Files.exists(storageRoot.resolve(key))).isFalse();

    http.perform(get("/catalog/export-jobs/" + done.id() + "/file")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isGone());
    // EXPIRED 是终态，不可迁回 SUCCEEDED。
    assertThatThrownBy(
            () -> call(who, "catalog.spu.read",
                () -> sql.execute(
                    "update export_job set status='SUCCEEDED' where tenant_id=? and id=?",
                    who.tenantId(), done.id())))
        .hasMessageContaining("状态不可从");
  }

  @Test
  void cancelBlocksDownloadImmediatelyButKeepsTheAuditFacts() {
    var who = actor();
    var f = fixture(who, "CAN", 2);
    var done = await(start(who, new ExportJobInput("SKU", List.of("CODE"), skuFilter(f.spuId()), null, null)).id(), who);
    assertThat(done.downloadable()).isTrue();

    var cancelled = call(who, "catalog.spu.update", () -> jobs.cancel(who, done.id()));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.downloadable()).isFalse();
    // 审计事实保留：这份导出当初导出了什么仍然可查。
    assertThat(cancelled.rowCount()).isEqualTo(2);
    assertThat(cancelled.fileName()).isNotNull();
    assertThat(cancelled.contentSha256()).isNotNull();

    assertThatThrownBy(() -> call(who, "catalog.spu.read", () -> jobs.file(who, done.id())))
        .hasMessageContaining("作废");
    // 终态不可再作废。
    assertThatThrownBy(() -> call(who, "catalog.spu.update", () -> jobs.cancel(who, done.id())))
        .hasMessageContaining("终态");
  }

  // ================================================================ 数据范围过滤（关键）

  @Test
  void dataScopeFiltersExportedRows() {
    var ownerActor = actor();
    var scopedActor = actors.get("Bearer scoped");
    var f = fixture(ownerActor, "SCP", 3);

    // ALL 范围（owner）：导得出全部 3 行。
    var full = await(start(ownerActor, new ExportJobInput(
        "SKU", List.of("CODE"), skuFilter(f.spuId()), null, null)).id(), ownerActor);
    assertThat(full.rowCount()).isEqualTo(3);

    // SELF 范围的另一个用户：同样条件导出 0 行 —— **数据范围在导出上逐行生效**。
    var scopedJob = call(scopedActor, "catalog.spu.read",
        () -> jobs.create(scopedActor, new ExportJobInput(
            "SKU", List.of("CODE"), skuFilter(f.spuId()), null, null)));
    var scopedDone = await(scopedJob.id(), scopedActor);
    assertThat(scopedDone.status()).isEqualTo("SUCCEEDED");
    assertThat(scopedDone.rowCount()).isZero();
    var csv = download(scopedActor, scopedDone.id());
    assertThat(stripBom(csv).strip().split("\n")).hasSize(1); // 只有表头
    // 行数为 0 时 row_count 记 0 而不是报错，且仍然是一份可下载的合法导出。
    assertThat(scopedDone.downloadable()).isTrue();

    // 同样地，SPU 数据源在 SELF 范围下也导不出别人的行。
    // scoped 用户从未创建过任何记录，所以它看本租户全部 SPU 的结果必须是 0 行。
    var spuScoped = await(start(scopedActor, new ExportJobInput(
        "SPU", List.of("CODE"), noFilter(), null, null)).id(), scopedActor);
    assertThat(spuScoped.status()).isEqualTo("SUCCEEDED");
    assertThat(spuScoped.rowCount()).isZero();
    // 对照：同一条件下 ALL 范围的 owner 导得出本租户的 SPU（不为 0 才说明上面不是「压根没数据」）。
    var spuOwner = await(start(ownerActor, new ExportJobInput(
        "SPU", List.of("CODE"), noFilter(), null, null)).id(), ownerActor);
    assertThat(spuOwner.rowCount()).isPositive();
  }

  // ================================================================ 多租户三断言

  @Test
  void exportIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    var mine = fixture(actor(), "ISO-MINE", 2);
    var theirs = fixture(actors.get("Bearer tenant-b"), "ISO-THEIRS", 3);

    // 1) 两租户互不可见
    var mineJob = await(start(actor(), new ExportJobInput(
        "SKU", List.of("CODE"), skuFilter(mine.spuId()), null, null)).id(), actor());
    assertThat(mineJob.rowCount()).isEqualTo(2);
    var theirFilter = filter("spuId", theirs.spuId());
    var bActor = actors.get("Bearer tenant-b");
    var theirAccepted = start(bActor, new ExportJobInput(
        "SKU", List.of("CODE"), theirFilter, null, null));
    var theirView = await(theirAccepted.id(), bActor);
    assertThat(theirView.rowCount()).isEqualTo(3);
    var theirJobId = theirAccepted.id();
    // 拿别人的 SPU ID 去导出，结果是 0 行而不是报错或数据。
    var cross = await(start(actors.get("Bearer tenant-b"), new ExportJobInput(
        "SKU", List.of("CODE"), skuFilter(mine.spuId()), null, null)).id(), actors.get("Bearer tenant-b"));
    assertThat(cross.rowCount()).isZero();
    // 拿别人的导出任务 id 去读 / 下载：不存在（RLS 下取不到），且不泄露存在性。
    assertThatThrownBy(() -> call(bActor, "catalog.spu.read", () -> jobs.job(bActor.tenantId(), mineJob.id())))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> call(bActor, "catalog.spu.read", () -> jobs.file(bActor, mineJob.id())))
        .hasMessageContaining("不存在");

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(post("/catalog/export-jobs")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a)
            .contentType("application/json")
            .content("{\"sourceKey\":\"SKU\",\"fields\":[\"CODE\"],\"filters\":{\"spuId\":\""
                + mine.spuId() + "\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actorId").value(owner.toString()));
    // 该任务属于 B 租户，导不出 A 的数据。
    var id = jsonUuid(http, mine.spuId());
    assertThat(call(bActor, "catalog.spu.read", () -> jobs.job(bActor.tenantId(), id)).rowCount()).isZero();
    // 伪造头也不能借道读别人的导出记录列表里的那条。
    http.perform(get("/catalog/export-jobs/" + id).header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(0));

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(call(actor(), "catalog.spu.read",
          () -> jobs.job(a, mineJob.id())).rowCount()).isEqualTo(2);
      assertThatThrownBy(() -> call(bActor, "catalog.spu.read", () -> jobs.job(b, mineJob.id())))
          .hasMessageContaining("不存在");
      assertThat(call(bActor, "catalog.spu.read",
          () -> jobs.job(b, theirJobId)).rowCount()).isEqualTo(3);
    }
  }


  @Test
  void everyNewTableHasForcedRls() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r' and relname='export_job'"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    var role = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    var column =
        sql.fetchOne(
            "select is_nullable from information_schema.columns where table_name='export_job' and column_name='tenant_id'");
    assertThat(column.get("is_nullable", String.class)).isEqualTo("NO");
  }

  // ================================================================ HTTP 契约

  @Test
  void httpEndpointsExposeSourcesJobsAndDownload() throws Exception {
    var who = actor();
    var f = fixture(who, "HTTP", 2);
    http.perform(get("/catalog/export-sources").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].fields").isArray())
        .andExpect(jsonPath("$.items[0].maxRows").isNumber());

    var created =
        http.perform(post("/catalog/export-jobs")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"sourceKey\":\"SKU\",\"fields\":[\"CODE\",\"NAME\"],\"filters\":{\"spuId\":\""
                    + f.spuId() + "\"},\"sort\":{\"key\":\"code\",\"order\":\"asc\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceKey").value("SKU"))
            .andReturn();
    var id = jsonPathValue(created, "$.id");
    await(UUID.fromString(id), who);

    // 记录列表：同租户历史导出会累积，这里断言形状与可追溯性，不断言总数。
    http.perform(get("/catalog/export-jobs").param("size", "5").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber())
        .andExpect(jsonPath("$.items[0].actorId").value(owner.toString()))
        .andExpect(jsonPath("$.items[0].sourceKey").value("SKU"));
    // 刚建的那条按 id 可查回，字段/条件/行数都在。
    http.perform(get("/catalog/export-jobs/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.rowCount").value(2))
        .andExpect(jsonPath("$.headers[0]").value("SKU 编码"));

    var file =
        http.perform(get("/catalog/export-jobs/" + id + "/file")
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
            .andReturn();
    var body = file.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).contains("SKU 编码").contains("SKU 名称").contains("HTTP-SKU");

    // 缺字段 → 400；未知数据源 → 404。
    http.perform(post("/catalog/export-jobs")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"sourceKey\":\"SKU\",\"fields\":[]}"))
        .andExpect(status().isBadRequest());
    http.perform(post("/catalog/export-jobs")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"sourceKey\":\"NOPE\",\"fields\":[\"CODE\"]}"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 辅助

  private String download(AccessService.Access who, UUID id) {
    try {
      var result = http.perform(get("/catalog/export-jobs/" + id + "/file")
              .header("Authorization", who.tenantId().equals(a) ? "Bearer tenant-a"
                  : who.tenantId().equals(b) ? "Bearer tenant-b" : "Bearer scoped"))
          .andExpect(status().isOk())
          .andReturn();
      return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 跳过表头：CSV 第一行是列头，不是数据。 */
  private static List<String> dataRows(String[] lines) {
    return Arrays.asList(lines).subList(1, lines.length);
  }

  private List<String> codesInOrder(UUID id, AccessService.Access who) {
    var csv = download(who, id);
    var lines = stripBom(csv).strip().split("\n");
    var out = new ArrayList<String>();
    for (var line : dataRows(lines)) {
      var cell = line.split(",");
      out.add(cell[0].replace("\"", ""));
    }
    return out;
  }

  private UUID jsonUuid(MockMvc http, UUID ignored) throws Exception {
    var latest =
        http.perform(get("/catalog/export-jobs").param("size", "1")
                .header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn();
    return UUID.fromString(jsonPathValue(latest, "$.items[0].id"));
  }

  private static String jsonPathValue(MvcResult result, String path) {
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
      var current = node;
      for (String segment : path.replace("$.", "").split("\\.")) {
        // 支持 items[0] 这种带下标的段：split 之后是 ["items[0]", "id"]。
        var bracket = segment.indexOf('[');
        if (bracket > 0) {
          current = current.get(segment.substring(0, bracket));
          while (bracket >= 0) {
            var close = segment.indexOf(']', bracket);
            current = current.get(Integer.parseInt(segment.substring(bracket + 1, close)));
            bracket = segment.indexOf('[', close);
          }
        } else {
          current = current.get(segment);
        }
      }
      return current.asText();
    } catch (Exception e) {
      throw new IllegalStateException("读取响应字段失败：" + path, e);
    }
  }

}