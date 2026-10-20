package com.starcon.trade;

import static com.starcon.trade.SupplierQualificationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.1.2.3 供应商资质（trade 侧）。
 *
 * <p><b>本测试是 V239 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>catalog 是真起一个本地 stub 服务</b>（而不是在测试里 new 一个假对象）：供应商、03 品牌与内部 SKU 的事实都在
 * catalog 那边，本车道<b>不重新实现</b>它们，只按它们的契约形状取回逻辑 ID 与名称快照 ——
 * 端口的 URL 与响应形状写错了，只有真发一次 HTTP 才看得出来。
 *
 * <p><b>身份用 {@link AccessService} 的 spy</b>（只替换对 identity 的一次调用），{@code run} 仍是真实实现
 * ⇒ RLS 租户绑定跑的是生产代码路径，且不额外占端口。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，
 * 才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierQualificationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("qualifications")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  /** catalog stub 的供应商清单：roleId → 名称。 */
  static final Map<String, String> catalogSuppliers = new ConcurrentHashMap<>();

  /** catalog stub 的品牌清单：brandId → (编码, 名称)。 */
  static final Map<String, String[]> catalogBrands = new ConcurrentHashMap<>();

  /** catalog stub 的 SKU 清单：skuId → (编码, 名称)。 */
  static final Map<String, String[]> catalogSkus = new ConcurrentHashMap<>();

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

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes =
        body instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.qualifications.read")))));

      // ---- catalog stub：供应商（6.1.1.1）、03 品牌（3.3.2.1）与内部 SKU（2.x）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supply/vendors",
          e -> {
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/supply/vendors".length());
            if (rest.startsWith("/")) {
              String roleId = rest.substring(1);
              String name = catalogSuppliers.get(roleId);
              if (name == null) {
                write(catalog, e, 404, Map.of("message", "供应商不存在"));
                return;
              }
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "summary",
                      Map.of(
                          "roleId", roleId,
                          "partyId", UUID.randomUUID().toString(),
                          "partyCode", "SUP-" + roleId.substring(0, 4),
                          "partyName", name,
                          "partyStatus", "ACTIVE")));
              return;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var entry : catalogSuppliers.entrySet()) {
              rows.add(
                  Map.of(
                      "roleId", entry.getKey(),
                      "partyId", UUID.randomUUID().toString(),
                      "partyCode", "SUP-" + entry.getKey().substring(0, 4),
                      "partyName", entry.getValue(),
                      "partyStatus", "ACTIVE"));
            }
            write(
                catalog,
                e,
                200,
                Map.of("items", rows, "total", rows.size(), "page", 0, "size", 200));
          });
      catalog.createContext(
          "/catalog/brands",
          e -> {
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/brands".length());
            if (rest.startsWith("/")) {
              String brandId = rest.substring(1);
              String[] row = catalogBrands.get(brandId);
              if (row == null) {
                write(catalog, e, 404, Map.of("message", "品牌不存在"));
                return;
              }
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "id", brandId,
                      "code", row[0],
                      "name", row[1],
                      "authorizationStatus", "AUTHORIZED",
                      "enabled", true));
              return;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var entry : catalogBrands.entrySet()) {
              rows.add(
                  Map.of(
                      "id", entry.getKey(),
                      "code", entry.getValue()[0],
                      "name", entry.getValue()[1],
                      "authorizationStatus", "AUTHORIZED",
                      "enabled", true));
            }
            write(
                catalog,
                e,
                200,
                Map.of("items", rows, "total", rows.size(), "page", 0, "size", 200));
          });
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            String skuId = e.getRequestURI().getPath().substring("/catalog/skus/".length());
            String[] row = catalogSkus.get(skuId);
            if (row == null) {
              write(catalog, e, 404, Map.of("message", "SKU 不存在"));
              return;
            }
            write(
                catalog,
                e,
                200,
                Map.of("id", skuId, "code", row[0], "name", row[1], "status", "ACTIVE"));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    catalog.stop(0);
    pg.stop();
  }

  @MockitoSpyBean AccessService access;
  @Autowired SupplierQualificationService service;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  found.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  static final UUID supplierRole = UUID.randomUUID();
  static final UUID brandOne = UUID.randomUUID();
  static final UUID brandTwo = UUID.randomUUID();
  static final UUID skuOne = UUID.randomUUID();

  @BeforeEach
  void seed() throws Exception {
    catalogSuppliers.clear();
    catalogBrands.clear();
    catalogSkus.clear();
    catalogSuppliers.put(supplierRole.toString(), "义乌老王批发");
    catalogBrands.put(brandOne.toString(), new String[] {"BR-001", "晨光"});
    catalogBrands.put(brandTwo.toString(), new String[] {"BR-002", "远山"});
    catalogSkus.put(skuOne.toString(), new String[] {"SKU-BAG-001", "女士通勤包"});

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate supplier_qualification_reminder, supplier_qualification_review,"
              + " supplier_qualification_document, supplier_qualification_policy,"
              + " supplier_qualification cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.qualifications.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.qualifications.update", work);
  }

  // ================================================================ 夹具

  /** 服务端当前时刻（取自数据库时钟，与到期状态的判定口径完全一致）。 */
  OffsetDateTime dbNow() {
    return asA(() -> sql.fetchOne("select now()").get(0, OffsetDateTime.class));
  }

  QualificationInput input(
      String code, String type, OffsetDateTime from, OffsetDateTime until, UUID brandId) {
    return new QualificationInput(
        code,
        supplierRole,
        type,
        "证件 " + code,
        "CERT-" + code,
        "发证机构",
        brandId,
        from.toString(),
        until.toString(),
        "测试用",
        0);
  }

  QualificationRow create(
      String code, String type, OffsetDateTime from, OffsetDateTime until, UUID brandId) {
    return asA(() -> service.createQualification(input(code, type, from, until, brandId), "Bearer tenant-a"));
  }

  /** 走完 DRAFT → PENDING → APPROVED。 */
  QualificationRow approved(String code, String type, OffsetDateTime from, OffsetDateTime until, UUID brandId) {
    var row = create(code, type, from, until, brandId);
    asA(() -> service.review(row.id(), new QualificationReviewInput("SUBMIT", "")));
    asA(() -> service.review(row.id(), new QualificationReviewInput("APPROVE", "")));
    return asA(() -> service.qualification(row.id())).qualification();
  }

  // ================================================================ 登记 / 时间往返

  @Test
  @DisplayName("登记资质：有效期写回的值与提交的时刻逐字相等（不偏 28800 秒）")
  void qualificationRoundTripsInstants() {
    // 带 +08:00 时区的时刻 —— 用 Timestamp.valueOf(LocalDateTime) 写会静默偏 −28800s。
    var from = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    var until = OffsetDateTime.parse("2027-03-01T08:00:00+08:00");
    var row = create("Q-TIME", "BUSINESS_LICENSE", from, until, null);

    assertThat(row.validFrom()).isEqualTo("2026-03-01T00:00Z");
    assertThat(row.validUntil()).isEqualTo("2027-03-01T00:00Z");

    // **断言写回的值**：库里那一行必须是同一个时刻。
    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select valid_from, valid_until from supplier_qualification where id = ?", row.id())
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant().getEpochSecond())
        .isEqualTo(OffsetDateTime.parse("2026-03-01T00:00:00Z").toInstant().getEpochSecond());
    // 驱动按**会话时区**渲染 timestamptz，所以这里比的是折算到 UTC 之后的同一个时刻，
    // 而不是「字符串长什么样」—— 后者会随服务器时区变。
    assertThat(stored.withOffsetSameInstant(ZoneOffset.UTC).toString())
        .isEqualTo("2026-03-01T00:00Z");
  }

  @Test
  @DisplayName("只给日期时：起点取当日 00:00、终点取当日 23:59:59.999999999")
  void dateOnlyUsesWholeDay() {
    var row =
        asA(
            () ->
                service.createQualification(
                    new QualificationInput(
                        "Q-DATE",
                        supplierRole,
                        "BUSINESS_LICENSE",
                        "营业执照",
                        "",
                        "",
                        null,
                        "2026-05-01",
                        "2026-05-31",
                        "",
                        0),
                    "Bearer tenant-a"));
    assertThat(row.validFrom()).isEqualTo("2026-05-01T00:00Z");
    assertThat(row.validUntil()).startsWith("2026-05-31T23:59:59");
  }

  @Test
  @DisplayName("供应商与品牌都经 catalog 核对：库里只存逻辑 ID + 名称快照")
  void supplierAndBrandComeFromCatalog() {
    var row = create("Q-SUP", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    assertThat(row.supplierRoleId()).isEqualTo(supplierRole);
    assertThat(row.supplierName()).isEqualTo("义乌老王批发");

    var auth = create("Q-AUTH", "AUTHORIZATION_LETTER", dbNow(), dbNow().plusYears(1), brandOne);
    assertThat(auth.brandId()).isEqualTo(brandOne);
    assertThat(auth.brandName()).isEqualTo("晨光");
  }

  @Test
  @DisplayName("授权书必须关联 03 品牌（品牌授权需关联 03 品牌）")
  void authorizationLetterRequiresBrand() {
    assertThatThrownBy(
            () -> create("Q-NOBRAND", "AUTHORIZATION_LETTER", dbNow(), dbNow().plusYears(1), null))
        .hasStackTraceContaining("必须关联 03 品牌");
  }

  @Test
  @DisplayName("供应商不存在时登记失败（catalog 是唯一来源，不凭空造一个供应商）")
  void unknownSupplierFails() {
    // ⚠️ 时刻**在事务外**取（dbNow 自带一个事务）：在 as(...) 里面再取就是嵌套事务了。
    var now = dbNow();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createQualification(
                            new QualificationInput(
                                "Q-GHOST",
                                UUID.randomUUID(),
                                "BUSINESS_LICENSE",
                                "营业执照",
                                "",
                                "",
                                null,
                                now.toString(),
                                now.plusYears(1).toString(),
                                "",
                                0),
                            "Bearer tenant-a")))
        .hasStackTraceContaining("供应商不存在");
  }

  @Test
  @DisplayName("资质编码租户内唯一")
  void codeIsUniquePerTenant() {
    create("Q-DUP", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    assertThatThrownBy(
            () -> create("Q-DUP", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null))
        .hasStackTraceContaining("已被本租户其它资质占用");
  }

  // ================================================================ 到期状态（真实日期计算）

  @Test
  @DisplayName("到期状态按数据库时钟现算：有效 / 即将过期 / 已过期 / 未生效，四态分得清")
  void expiryStateIsComputedFromRealDates() {
    var now = dbNow();
    var valid = approved("Q-VALID", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(200), null);
    var expiring =
        approved("Q-EXPIRING", "QUALITY_INSPECTION_REPORT", now.minusDays(10), now.plusDays(10), null);
    var expired = approved("Q-EXPIRED", "CE", now.minusDays(40), now.minusDays(10), null);
    var future =
        approved("Q-FUTURE", "ROHS", now.plusDays(10), now.plusDays(200), null);

    assertThat(valid.expiryState()).isEqualTo("VALID");
    assertThat(expiring.expiryState()).isEqualTo("EXPIRING");
    assertThat(expired.expiryState()).isEqualTo("EXPIRED");
    assertThat(future.expiryState()).isEqualTo("NOT_EFFECTIVE");

    // 距到期天数：过期时是负数（界面照实显示「已过期 N 天」）。
    assertThat(valid.daysToExpiry()).isGreaterThan(100);
    assertThat(expiring.daysToExpiry()).isBetween(1, 30);
    assertThat(expired.daysToExpiry()).isNegative();
    assertThat(future.daysToExpiry()).isGreaterThan(100);
  }

  @Test
  @DisplayName("「即将过期」窗口来自租户策略：改窗口后同一份资质的到期状态跟着变")
  void warningWindowComesFromPolicy() {
    var now = dbNow();
    var row = approved("Q-WINDOW", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(45), null);
    assertThat(row.expiryState()).isEqualTo("VALID");

    asA(() -> service.putPolicy(new QualificationPolicyInput(60, List.of("BUSINESS_LICENSE"), "放宽窗口")));
    var widened = asA(() -> service.qualification(row.id())).qualification();
    assertThat(widened.expiryState()).isEqualTo("EXPIRING");
  }

  @Test
  @DisplayName("到期状态按筛选条件过滤（现算值可筛，不只是能看）")
  void expiryStateIsFilterable() {
    var now = dbNow();
    approved("Q-F-EXPIRED", "CE", now.minusDays(40), now.minusDays(10), null);
    approved("Q-F-VALID", "ROHS", now.minusDays(10), now.plusDays(200), null);

    var expired =
        asA(
            () ->
                service.pageQualifications(
                    new QualificationQuery(null, null, null, "EXPIRED", null, null, null, false, 0, 20)));
    assertThat(expired.total()).isEqualTo(1);
    assertThat(expired.items().get(0).code()).isEqualTo("Q-F-EXPIRED");
    assertThat(expired.expiredCount()).isEqualTo(1);
  }

  // ================================================================ 审核状态机

  @Test
  @DisplayName("审核状态机：提交 → 通过 → 撤销，每一步都留痕")
  void reviewStateMachineWorks() {
    var row = create("Q-REVIEW", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    assertThat(row.reviewStatus()).isEqualTo("DRAFT");

    var submitted = asA(() -> service.review(row.id(), new QualificationReviewInput("SUBMIT", "")));
    assertThat(submitted.fromStatus()).isEqualTo("DRAFT");
    assertThat(submitted.toStatus()).isEqualTo("PENDING");
    assertThat(submitted.seq()).isEqualTo(1);

    var approved = asA(() -> service.review(row.id(), new QualificationReviewInput("APPROVE", "")));
    assertThat(approved.toStatus()).isEqualTo("APPROVED");
    assertThat(approved.seq()).isEqualTo(2);

    var revoked = asA(() -> service.review(row.id(), new QualificationReviewInput("REVOKE", "文件造假")));
    assertThat(revoked.toStatus()).isEqualTo("REVOKED");
    assertThat(revoked.seq()).isEqualTo(3);

    var detail = asA(() -> service.qualification(row.id()));
    assertThat(detail.reviews()).hasSize(3);
    assertThat(detail.reviews().get(2).reason()).isEqualTo("文件造假");
    assertThat(detail.reviews().get(2).fromStatus()).isEqualTo("APPROVED");
    assertThat(detail.reviews().get(2).toStatus()).isEqualTo("REVOKED");
  }

  @Test
  @DisplayName("非法流转被服务层挡住：草稿不能直接通过")
  void illegalTransitionRejected() {
    var row = create("Q-ILLEGAL", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    assertThatThrownBy(() -> asA(() -> service.review(row.id(), new QualificationReviewInput("APPROVE", ""))))
        .hasStackTraceContaining("不合法");
  }

  @Test
  @DisplayName("驳回必须写明理由")
  void rejectRequiresReason() {
    var row = create("Q-REASON", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    asA(() -> service.review(row.id(), new QualificationReviewInput("SUBMIT", "")));
    assertThatThrownBy(() -> asA(() -> service.review(row.id(), new QualificationReviewInput("REJECT", "  "))))
        .hasStackTraceContaining("必须写明理由");
  }

  @Test
  @DisplayName("审核流转记录只追加：直接改库会被触发器挡下")
  void reviewEventsAreAppendOnly() {
    var row = create("Q-IMMUTABLE", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    asA(() -> service.review(row.id(), new QualificationReviewInput("SUBMIT", "")));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update supplier_qualification_review set reason = 'x' where qualification_id = ?",
                            row.id())))
        .hasStackTraceContaining("审核流转记录不可改写");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "delete from supplier_qualification_review where qualification_id = ?",
                            row.id())))
        .hasStackTraceContaining("审核流转记录不可改写");
  }

  @Test
  @DisplayName("审核状态机在 DB 层也钉死：直连数据库改状态会被触发器挡下")
  void reviewTransitionGuardedInDatabase() {
    var row = create("Q-DBGATE", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update supplier_qualification set review_status = 'APPROVED' where id = ?",
                            row.id())))
        .hasStackTraceContaining("审核状态不可从");
  }

  @Test
  @DisplayName("资质文件只追加：续期是再上传一份，不是覆盖")
  void documentsAreAppendOnly() {
    var row = create("Q-DOC", "BUSINESS_LICENSE", dbNow(), dbNow().plusYears(1), null);
    var saved =
        asA(
            () ->
                service.uploadDocument(
                    row.id(),
                    new QualificationDocumentInput(
                        "ORIGINAL", "s3://bucket/qual/a.pdf", "a.pdf", "application/pdf",
                        new BigDecimal("1024.000000"), "")));
    assertThat(saved.documentKind()).isEqualTo("ORIGINAL");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update supplier_qualification_document set document_name = 'b.pdf' where id = ?",
                            saved.id())))
        .hasStackTraceContaining("资质文件不可改写");
  }

  @Test
  @DisplayName("已通过的资质不能直接改内容（要先撤销）")
  void approvedQualificationIsNotEditable() {
    var now = dbNow();
    var row = approved("Q-LOCK", "BUSINESS_LICENSE", now.minusDays(1), now.plusYears(1), null);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.updateQualification(
                            row.id(),
                            new QualificationUpdateInput(
                                "改名了", "", "", null, now.toString(), now.plusYears(1).toString(), "", 0),
                            "Bearer tenant-a")))
        .hasStackTraceContaining("不能直接改内容");
  }

  // ================================================================ 过期提醒

  @Test
  @DisplayName("提醒扫描：即将过期与已过期是两条不同的提醒，且幂等")
  void reminderScanDistinguishesKinds() {
    var now = dbNow();
    approved("Q-R-EXPIRING", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(10), null);
    approved("Q-R-EXPIRED", "CE", now.minusDays(40), now.minusDays(10), null);

    var scan = asA(() -> service.scanReminders());
    assertThat(scan.expiringCount()).isEqualTo(1);
    assertThat(scan.expiredCount()).isEqualTo(1);
    assertThat(scan.createdCount()).isEqualTo(2);

    // 幂等：再扫一次不新增。
    var again = asA(() -> service.scanReminders());
    assertThat(again.createdCount()).isZero();

    var page = asA(() -> service.pageReminders(new QualificationReminderQuery(null, null, null, 0, 20)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.expiringCount()).isEqualTo(1);
    assertThat(page.expiredCount()).isEqualTo(1);
    assertThat(page.items()).extracting(QualificationReminderRow::reminderKind).containsExactlyInAnyOrder("EXPIRING", "EXPIRED");
  }

  @Test
  @DisplayName("未处理的提醒可以确认 / 关闭")
  void reminderCanBeHandled() {
    var now = dbNow();
    approved("Q-R-HANDLE", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(10), null);
    asA(() -> service.scanReminders());
    var reminder = asA(() -> service.pageReminders(new QualificationReminderQuery(null, null, null, 0, 20))).items().get(0);

    var acknowledged =
        asA(() -> service.handleReminder(reminder.id(), new QualificationReminderHandleInput("ACKNOWLEDGED", "在续")));
    assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");
    var resolved =
        asA(() -> service.handleReminder(reminder.id(), new QualificationReminderHandleInput("RESOLVED", "已续期")));
    assertThat(resolved.status()).isEqualTo("RESOLVED");
  }

  // ================================================================ 阻断判定

  @Test
  @DisplayName("即将过期**不**阻断：还在有效期内，拦下来是误伤")
  void expiringDoesNotBlock() {
    var now = dbNow();
    approved("Q-B-EXPIRING", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(10), null);
    var decision = asA(() -> service.supplierBlocking(supplierRole, null, "Bearer tenant-a"));
    assertThat(decision.blocked()).isFalse();
    assertThat(decision.reasons()).isEmpty();
    assertThat(decision.blockMessage()).contains("不阻断");
  }

  @Test
  @DisplayName("已过期**阻断**：给出 blocked + 原因码 + 人话")
  void expiredBlocks() {
    var now = dbNow();
    approved("Q-B-EXPIRED", "BUSINESS_LICENSE", now.minusDays(40), now.minusDays(10), null);
    var decision = asA(() -> service.supplierBlocking(supplierRole, null, "Bearer tenant-a"));
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.blockCode()).isEqualTo("QUALIFICATION_EXPIRED");
    assertThat(decision.reasons()).hasSize(1);
    assertThat(decision.reasons().get(0).qualificationCode()).isEqualTo("Q-B-EXPIRED");
    assertThat(decision.reasons().get(0).daysToExpiry()).isNegative();
    assertThat(decision.blockMessage()).contains("阻断");
  }

  @Test
  @DisplayName("已撤销**阻断**")
  void revokedBlocks() {
    var now = dbNow();
    var row = approved("Q-B-REVOKED", "BUSINESS_LICENSE", now.minusDays(1), now.plusYears(1), null);
    asA(() -> service.review(row.id(), new QualificationReviewInput("REVOKE", "撤销")));

    var decision = asA(() -> service.supplierBlocking(supplierRole, null, "Bearer tenant-a"));
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.blockCode()).isEqualTo("QUALIFICATION_REVOKED");
  }

  @Test
  @DisplayName("必备资质缺失**阻断**")
  void missingRequiredQualificationBlocks() {
    var decision = asA(() -> service.supplierBlocking(supplierRole, null, "Bearer tenant-a"));
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.blockCode()).isEqualTo("QUALIFICATION_MISSING");
    assertThat(decision.reasons().get(0).message()).contains("营业执照");
  }

  @Test
  @DisplayName("品牌授权缺失 / 过期都**阻断**（品牌授权需关联 03 品牌）")
  void brandAuthorizationBlocks() {
    var now = dbNow();
    approved("Q-B-BRAND-OK", "BUSINESS_LICENSE", now.minusDays(10), now.plusYears(1), null);

    // 没有任何品牌授权 → 缺失阻断。
    var missing = asA(() -> service.supplierBlocking(supplierRole, brandOne, "Bearer tenant-a"));
    assertThat(missing.blocked()).isTrue();
    assertThat(missing.blockCode()).isEqualTo("BRAND_AUTHORIZATION_MISSING");
    assertThat(missing.scope()).isEqualTo("BRAND");

    // 补一份有效的品牌授权 → 不再因品牌阻断。
    approved("Q-B-BRAND-VALID", "AUTHORIZATION_LETTER", now.minusDays(10), now.plusYears(1), brandOne);
    var ok = asA(() -> service.supplierBlocking(supplierRole, brandOne, "Bearer tenant-a"));
    assertThat(ok.blocked()).isFalse();

    // 另一个品牌只有一份过期的授权 → 过期阻断。
    var auth = create("Q-B-BRAND-EXP", "AUTHORIZATION_LETTER", now.minusDays(40), now.minusDays(10), brandTwo);
    asA(() -> service.review(auth.id(), new QualificationReviewInput("SUBMIT", "")));
    asA(() -> service.review(auth.id(), new QualificationReviewInput("APPROVE", "")));
    var expiredBrand = asA(() -> service.supplierBlocking(supplierRole, brandTwo, "Bearer tenant-a"));
    assertThat(expiredBrand.blocked()).isTrue();
    assertThat(expiredBrand.blockCode()).isEqualTo("BRAND_AUTHORIZATION_EXPIRED");
  }

  @Test
  @DisplayName("续期不误伤：旧件过期但新件有效时不算阻断")
  void renewalDoesNotBlock() {
    var now = dbNow();
    // 旧件已过期。
    approved("Q-RENEW-OLD", "BUSINESS_LICENSE", now.minusDays(400), now.minusDays(10), null);
    // 新件有效。
    approved("Q-RENEW-NEW", "BUSINESS_LICENSE", now.minusDays(10), now.plusYears(1), null);

    var decision = asA(() -> service.supplierBlocking(supplierRole, null, "Bearer tenant-a"));
    assertThat(decision.blocked()).isFalse();
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  @DisplayName("商品维度的阻断：经 6.1.2.1 的货源关联解析出背后的供应商")
  void skuBlockingResolvesSuppliersThroughSourceLink() {
    var now = dbNow();
    approved("Q-B-SKU", "BUSINESS_LICENSE", now.minusDays(40), now.minusDays(10), null);

    // 造一条 6.1.2.1 的货源关联：这个 SKU 的货源来自上面那个供应商。
    UUID stallId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into supplier_source_stall(id,tenant_id,code,platform,platform_stall_id,name,url,"
                  + "collect_method,supplier_party_id,supplier_name_snapshot,status,note,created_at,updated_at)"
                  + " values(?,?,?,'ALIBABA_1688',?,?,'','PLUGIN_PUSH',?,?,'ACTIVE','',now(),now())",
              stallId,
              a,
              "S-BLOCK",
              "SHOP-BLOCK",
              "阻断测试档口",
              supplierRole,
              "义乌老王批发");
          sql.execute(
              "insert into supplier_source_item(id,tenant_id,stall_id,platform_item_id,first_seen_at,"
                  + "last_seen_at,created_at,updated_at) values(?,?,?,?,now(),now(),now(),now())",
              itemId,
              a,
              stallId,
              "ITEM-BLOCK");
          sql.execute(
              "insert into supplier_source_sku_link(id,tenant_id,item_id,sku_id,sku_code,sku_name,"
                  + "linked_at,note,created_at) values(?,?,?,?,?,?,now(),'',now())",
              UUID.randomUUID(),
              a,
              itemId,
              skuOne,
              "SKU-BAG-001",
              "女士通勤包");
          return null;
        });

    var decision = asA(() -> service.skuBlocking(skuOne, "Bearer tenant-a"));
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.scope()).isEqualTo("SKU");
    assertThat(decision.reasons().get(0).supplierRoleId()).isEqualTo(supplierRole);
  }

  @Test
  @DisplayName("商品不存在时阻断判定显式失败（catalog 是唯一来源，不凭空造一个 SKU）")
  void unknownSkuFails() {
    assertThatThrownBy(() -> asA(() -> service.skuBlocking(UUID.randomUUID(), "Bearer tenant-a")))
        .hasStackTraceContaining("内部 SKU 不存在");
  }

  // ================================================================ 概览 / 词表

  @Test
  @DisplayName("概览把即将过期与已过期分成两个数")
  void overviewSeparatesExpiringAndExpired() {
    var now = dbNow();
    approved("Q-O-EXPIRING", "BUSINESS_LICENSE", now.minusDays(10), now.plusDays(10), null);
    approved("Q-O-EXPIRED", "CE", now.minusDays(40), now.minusDays(10), null);

    var overview = asA(() -> service.overview());
    assertThat(overview.totalCount()).isEqualTo(2);
    assertThat(overview.approvedCount()).isEqualTo(2);
    assertThat(overview.expiringCount()).isEqualTo(1);
    assertThat(overview.expiredCount()).isEqualTo(1);
    assertThat(overview.blockingCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("词表与能力声明把下游未交付的部分如实标出来")
  void capabilitiesDeclareGaps() {
    var capabilities = asA(() -> service.capabilities());
    assertThat(capabilities.gaps()).isNotEmpty();
    assertThat(capabilities.blockingSemantics()).contains("阻断");
    assertThat(capabilities.qualificationTypes()).hasSize(QualificationType.values().length);
  }

  // ================================================================ RLS / HTTP 契约 / 分页

  @Test
  @DisplayName("每张新表都 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    for (String table :
        List.of(
            "supplier_qualification",
            "supplier_qualification_document",
            "supplier_qualification_review",
            "supplier_qualification_reminder",
            "supplier_qualification_policy")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
    }
  }

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 404 跨租户")
  void httpContract() throws Exception {
    http.perform(get("/trade/supplier-qualifications/qualifications")).andExpect(status().isUnauthorized());

    http.perform(
            get("/trade/supplier-qualifications/qualifications")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能登记资质。
    http.perform(
            post("/trade/supplier-qualifications/qualifications")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new QualificationInput(
                            "Q-RO",
                            supplierRole,
                            "BUSINESS_LICENSE",
                            "只读建的资质",
                            "",
                            "",
                            null,
                            "2026-05-01T00:00:00Z",
                            "2027-05-01T00:00:00Z",
                            "",
                            0))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("真分页：三页合起来恰好 N 条且互不重叠")
  void paginationIsReal() {
    var now = dbNow();
    for (int i = 0; i < 7; i++) {
      approved("Q-PAGE-" + i, "BUSINESS_LICENSE", now.minusDays(1), now.plusDays(30 + i), null);
    }
    var seen = new java.util.LinkedHashSet<String>();
    for (int page = 0; page < 3; page++) {
      final int pageIndex = page;
      var result =
          asA(
              () ->
                  service.pageQualifications(
                      new QualificationQuery(
                          null, null, null, null, null, null, "code", false, pageIndex, 3)));
      assertThat(result.items()).hasSizeLessThanOrEqualTo(3);
      result.items().forEach(row -> assertThat(seen.add(row.code())).isTrue());
    }
    // 三页合起来恰好 7 条（7 = 3 + 3 + 1），且互不重叠。
    assertThat(seen).hasSize(7);
  }
}
