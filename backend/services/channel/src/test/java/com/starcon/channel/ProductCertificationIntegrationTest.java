package com.starcon.channel;

import static com.starcon.channel.ProductCertificationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.2.2.2 认证与资质。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 认证类型是受控词表</b>：内置类型来自<b>数据库</b>（迁移种子），租户可新增 / 改名 /
 *       停用；停用后新登记被拒，<b>直连数据库插一个不在词表里的类型也被 DB 触发器拒</b>。
 *   <li><b>🔴 有效期往返逐字相等</b>：写入用带偏移的 {@link OffsetDateTime}，读回断言
 *       <b>时刻</b>完全相等（不偏 28800 秒）—— 这是本模块最容易出时间陷阱的字段。
 *   <li><b>🔴 到期状态是算出来的</b>：已过期 / 即将过期 / 有效 / 尚未生效 / 已撤销五个状态各自算对，
 *       判据是<b>数据库时钟</b>；到期提醒按<b>剩余天数升序</b>。
 *   <li><b>🔴 扫描件真存</b>：文件本体真的落盘、读回来逐字节相同，元数据（文件名 / MIME / 字节数 /
 *       上传时刻）入库；文件行<b>只追加</b>（直连数据库改 / 删都被拒）。
 *   <li><b>🔴 覆盖统计真的算得出来</b>：要求侧对上覆盖侧，按 SKU / 类目 / 类型三个口径；
 *       缺认证的 SKU 被点名，撤销一张认证后覆盖立刻掉下去。
 *   <li><b>🔴 上游读不到就显式报读不到</b>：catalog 挂掉时候选端点报 503，<b>不是</b>空列表。
 *   <li><b>🔴 乐观锁</b>：版本不符 409；台账是<b>真分页</b>；HTTP 面 401 / 404。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductCertificationIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static ProductCertificationCatalogStub catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
      catalog = new ProductCertificationCatalogStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> catalog.base());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 扫描件落在仓库内的 target 下，不写家目录也不写 /tmp。
    r.add("CERTIFICATION_STORAGE_ROOT", () -> "target/test-certification-store");
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    catalog.listStatus = 0;
    catalog.detailStatus = 0;
    catalog.treesStatus = 0;
  }

  @Autowired ProductCertificationService service;
  @Autowired AccessService access;
  @Autowired CertificationDocumentStorage storage;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 无返回值的写操作（删除类）。 */
  void run(String permission, Runnable work) {
    access.run(actor, permission, () -> {
      work.run();
      return null;
    });
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var rs = c.createStatement().executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 从受管存储里读回一份文件（把 IOException 翻成运行时异常，便于断言）。 */
  byte[] readStorage(String key) {
    try {
      return storage.read(key);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  static OffsetDateTime at(int plusDays) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(plusDays).truncatedTo(ChronoUnit.MILLIS);
  }

  CertificationDetail register(String code, String type, int validFromDays, int validUntilDays) {
    return as(
        "channel.operation.create",
        () ->
            service.createCertification(
                new ProductCertificationInput(
                    code,
                    type,
                    "证书 " + code,
                    "CERT-" + code,
                    "发证机构",
                    "DE",
                    at(validFromDays),
                    at(validUntilDays),
                    "",
                    null)));
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 能力声明：口径、边界与上游行为都写出来了")
  void capabilitiesAreDeclared() {
    ProductCertificationCapabilities capabilities = as("channel.operation.read", service::capabilities);

    assertThat(capabilities.statuses()).containsExactly("ACTIVE", "REVOKED");
    assertThat(capabilities.expiryStates())
        .containsExactlyInAnyOrder("REVOKED", "FUTURE", "EXPIRED", "EXPIRING", "VALID");
    assertThat(capabilities.documentKinds()).containsExactly("ORIGINAL", "RENEWAL", "ATTACHMENT");
    assertThat(capabilities.expirySemantics()).contains("不落库").contains("数据库时钟");
    assertThat(capabilities.coverageSemantics()).contains("要求侧").contains("覆盖侧");
    assertThat(capabilities.vocabularyNote()).contains("受控词表").contains("租户行优先");
    assertThat(capabilities.upstreamNote()).contains("绝不").contains("空列表");
    assertThat(capabilities.boundaryNote()).contains("6.1.2.3").contains("16.2.2.1");
    assertThat(capabilities.defaultWarningDays()).isEqualTo(30);
  }

  // ==================================================================
  // 2. 认证类型词表
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 认证类型是受控词表：内置类型来自数据库，租户可新增 / 改名 / 停用")
  void vocabularyIsTenantMaintainable() {
    List<CertificationTypeRow> builtin = as("channel.operation.read", service::types);
    assertThat(builtin).as("内置词表必须来自迁移种子数据").isNotEmpty();
    assertThat(builtin).extracting(CertificationTypeRow::code).contains("CE", "FCC", "ROHS");
    assertThat(builtin).allMatch(row -> !row.tenantDefined());

    // 租户新增一个内置词表里没有的类型
    as(
        "channel.operation.update",
        () ->
            service.upsertType(
                "ul",
                new CertificationTypeInput("ul", "UL 认证（美国保险商）", "marketplace", "美国 UL", true, 200)));

    CertificationTypeRow added =
        as("channel.operation.read", () -> service.types()).stream()
            .filter(row -> row.code().equals("UL"))
            .findFirst()
            .orElseThrow();
    assertThat(added.name()).isEqualTo("UL 认证（美国保险商）");
    assertThat(added.scope()).isEqualTo("MARKETPLACE");
    assertThat(added.tenantDefined()).isTrue();

    // 改名内置类型 ⇒ 租户行优先
    as(
        "channel.operation.update",
        () ->
            service.upsertType(
                "CE",
                new CertificationTypeInput("CE", "CE 认证（欧盟）", "GLOBAL", "", true, 10)));
    assertThat(as("channel.operation.read", () -> service.types()).stream()
            .filter(row -> row.code().equals("CE"))
            .findFirst()
            .orElseThrow()
            .name())
        .isEqualTo("CE 认证（欧盟）");

    // 停用内置类型 ⇒ 新登记被拒（服务层）
    as(
        "channel.operation.update",
        () ->
            service.upsertType(
                "PSE", new CertificationTypeInput("PSE", "PSE 认证（日本电器用品）", "MARKETPLACE", "", false, 50)));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> register("PSE-1", "PSE", -10, 100)))
        .as("停用后的认证类型不能挂到新档案上")
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在生效词表内");

    // 🔴 直连数据库写一个不在词表里的类型 ⇒ DB 触发器拒（服务层不是唯一一道闸）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into product_certification (id,tenant_id,code,certification_type_code,title,"
                            + "cert_no,issuer,issuing_country,status,valid_from,valid_until,note,version,"
                            + "created_at,updated_at) values ('"
                            + UUID.randomUUID()
                            + "','"
                            + tenant
                            + "','RAW-SQL-1','NOT_IN_VOCAB','直写','x','y','','ACTIVE',now(),now()+interval '1 day','',0,now(),now())")))
        .as("不在受控词表内的认证类型必须被 DB 触发器拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.2.2 租户字典行被引用时不能删（DB 触发器），停用则可以")
  void tenantTypeCannotBeDeletedWhileInUse() {
    as(
        "channel.operation.update",
        () ->
            service.upsertType(
                "UL", new CertificationTypeInput("UL", "UL 认证", "MARKETPLACE", "", true, 200)));
    register("UL-1", "UL", -10, 100);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> run("channel.operation.delete", () -> service.deleteType("UL"))))
        .as("仍被认证档案引用的租户类型不能删")
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能删除");
  }

  // ==================================================================
  // 3. 有效期往返（时间陷阱）
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 有效期往返逐字相等：不偏 28800 秒")
  void validityWindowRoundTripsExactly() {
    // 🔴 故意用一个非 UTC 偏移：Timestamp.valueOf(LocalDateTime) 那种写法会在这里偏 8 小时。
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(5).truncatedTo(ChronoUnit.MILLIS);
    OffsetDateTime until = from.plusYears(2);

    CertificationDetail created =
        as(
            "channel.operation.create",
            () ->
                service.createCertification(
                    new ProductCertificationInput(
                        "TZ-1", "CE", "带时区的证书", "", "", "", from, until, "", null)));

    CertificationDetail reloaded =
        as("channel.operation.read", () -> service.certification(created.id()));

    assertThat(reloaded.validFrom().toInstant())
        .as("有效期起点的时刻必须完全一致（不偏 28800 秒）")
        .isEqualTo(from.toInstant());
    assertThat(reloaded.validUntil().toInstant())
        .as("有效期终点的时刻必须完全一致（不偏 28800 秒）")
        .isEqualTo(until.toInstant());
    assertThat(Duration.between(reloaded.validUntil().toInstant(), until.toInstant()).getSeconds())
        .isZero();

    // DB 里的原值也逐字相等
    String raw =
        scalar(
            "select to_char(valid_until at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"
                + " from product_certification where id = '"
                + created.id()
                + "'");
    assertThat(raw).isEqualTo(until.withOffsetSameInstant(ZoneOffset.UTC).format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")));
  }

  // ==================================================================
  // 4. 到期状态是算出来的
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 到期状态按数据库时钟现算：五个状态各自算对")
  void expiryStateIsComputed() {
    CertificationDetail valid = register("E-VALID", "CE", -10, 100);
    CertificationDetail expiring = register("E-EXPIRING", "CE", -10, 5);
    CertificationDetail expired = register("E-EXPIRED", "CE", -100, -1);
    CertificationDetail future = register("E-FUTURE", "CE", 10, 100);

    assertThat(as("channel.operation.read", () -> service.certification(valid.id())).expiryState())
        .isEqualTo("VALID");
    assertThat(as("channel.operation.read", () -> service.certification(expiring.id())).expiryState())
        .isEqualTo("EXPIRING");
    assertThat(as("channel.operation.read", () -> service.certification(expired.id())).expiryState())
        .isEqualTo("EXPIRED");
    assertThat(as("channel.operation.read", () -> service.certification(future.id())).expiryState())
        .isEqualTo("FUTURE");

    // 撤销是第四种状态，与过期分得清
    as(
        "channel.operation.update",
        () -> service.revokeCertification(valid.id(), new CertificationRevokeInput("被发证机构撤销")));
    CertificationDetail revoked = as("channel.operation.read", () -> service.certification(valid.id()));
    assertThat(revoked.status()).isEqualTo("REVOKED");
    assertThat(revoked.expiryState()).isEqualTo("REVOKED");
    assertThat(revoked.revokedAt()).isNotNull();
    assertThat(revoked.revokedReason()).isEqualTo("被发证机构撤销");

    // 撤销必须写理由
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.revokeCertification(
                                expiring.id(), new CertificationRevokeInput("  ")))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("16.2.2.2 到期提醒按剩余天数升序：剩得少的排前面")
  void expiryRemindersAreSortedByDaysRemaining() {
    register("R-EXPIRED", "CE", -100, -5);
    register("R-SOON", "CE", -10, 2);
    register("R-LATER", "CE", -10, 20);
    register("R-FAR", "CE", -10, 200);

    ExpiryReminderPage page = as("channel.operation.read", () -> service.expiryReminders(0, 50));

    assertThat(page.items()).extracting(ExpiryReminderRow::code)
        .containsExactly("R-EXPIRED", "R-SOON", "R-LATER");
    // 🔴 剩余天数按 floor 算，而测试里的 at() 与 SQL 的 now() 差几毫秒 ⇒ 允许 ±1 天。
    //    真正要钉住的是**顺序**（剩得少的排前面），不是某个绝对天数。
    assertThat(page.items().get(0).daysRemaining()).isBetween(-6, -5);
    assertThat(page.items().get(1).daysRemaining()).isBetween(1, 2);
    assertThat(page.items().get(2).daysRemaining()).isBetween(19, 20);
    assertThat(page.items().get(0).expiryState()).isEqualTo("EXPIRED");
    assertThat(page.items().get(1).expiryState()).isEqualTo("EXPIRING");
    assertThat(page.warningDays()).isEqualTo(30);
  }

  @Test
  @DisplayName("16.2.2.2 租户级「即将过期」窗口可调，改完提醒立刻跟着变")
  void warningDaysAreTenantLevel() {
    register("W-1", "CE", -10, 20);
    assertThat(as("channel.operation.read", () -> service.expiryReminders(0, 50)).items())
        .as("默认 30 天窗口内 ⇒ 进提醒清单")
        .hasSize(1);

    as(
        "channel.operation.update",
        () -> service.updatePolicy(new CertificationPolicyInput(5, "只提醒 5 天内"), user));
    assertThat(as("channel.operation.read", () -> service.expiryReminders(0, 50)).items())
        .as("窗口收窄到 5 天 ⇒ 20 天后到期的不再提醒")
        .isEmpty();
  }

  // ==================================================================
  // 5. 扫描件真存
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 扫描件真存：文件落盘、元数据入库、只追加")
  void documentIsReallyStored() {
    CertificationDetail certification = register("DOC-1", "CE", -10, 100);
    byte[] content = "假的水扫描件内容".getBytes(StandardCharsets.UTF_8);

    CertificationDocumentRow row =
        as(
            "channel.operation.create",
            () ->
                service.uploadDocument(
                    certification.id(),
                    content,
                    "ce-cert.pdf",
                    "application/pdf",
                    "ORIGINAL",
                    "首次上传",
                    user));

    assertThat(row.documentName()).isEqualTo("ce-cert.pdf");
    assertThat(row.mimeType()).isEqualTo("application/pdf");
    assertThat(row.sizeBytes()).isEqualByComparingTo(String.valueOf(content.length));
    assertThat(row.uploadedAt()).isNotNull();
    assertThat(row.storageKey()).contains(certification.id()).startsWith("certifications/" + tenant + "/");

    // 🔴 文件本体真的在受管存储里，读回来逐字节相同
    assertThat(storage.exists(row.storageKey())).isTrue();
    assertThat(readStorage(row.storageKey())).isEqualTo(content);

    // 元数据真的入库
    assertThat(scalar("select document_name from product_certification_document where id = '"
            + row.id() + "'"))
        .isEqualTo("ce-cert.pdf");
    // size_bytes 是 NUMERIC(19,6) ⇒ 读出来带 6 位小数，按数值比而不是按字符串比。
    assertThat(new java.math.BigDecimal(
            scalar("select size_bytes from product_certification_document where id = '"
                + row.id()
                + "'")))
        .isEqualByComparingTo(String.valueOf(content.length));
    assertThat(scalar("select uploaded_at from product_certification_document where id = '"
            + row.id() + "'"))
        .isNotNull();

    // 🔴 文件行只追加：直连数据库改 / 删都被拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update product_certification_document set document_name='改过的.pdf' where id = '"
                            + row.id()
                            + "'")))
        .as("扫描件不可改写")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant, "delete from product_certification_document where id = '" + row.id() + "'")))
        .as("扫描件不可删")
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.2.2 扫描件走 base64 JSON 形状也能真存（浏览器代理走这条）")
  void documentUploadsThroughBase64Json() {
    CertificationDetail certification = register("DOC-2", "CE", -10, 100);
    byte[] content = "base64 上传的内容".getBytes(StandardCharsets.UTF_8);

    CertificationDocumentRow row =
        as(
            "channel.operation.create",
            () ->
                service.uploadDocument(
                    certification.id(),
                    new CertificationDocumentInput(
                        "cert.txt",
                        "text/plain",
                        Base64.getEncoder().encodeToString(content),
                        "RENEWAL",
                        "续期件"),
                    user));

    assertThat(row.documentKind()).isEqualTo("RENEWAL");
    assertThat(readStorage(row.storageKey())).isEqualTo(content);
  }

  // ==================================================================
  // 6. 适用 SKU 与对应类目
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 适用 SKU 与对应类目要求能真的关联与查询")
  void skuAndCategoryBindingsWork() {
    UUID skuId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID treeId = UUID.randomUUID();
    catalog.putSku(skuId, "SKU-CE-01", "带电水壶", "厨房电器");
    catalog.putTree(treeId, "INTERNAL", "内部类目");
    catalog.putNode(categoryId, treeId, "KITCHEN", "厨房电器", "厨房电器 / 小家电");

    CertificationDetail certification = register("BIND-1", "CE", -10, 100);

    as(
        "channel.operation.create",
        () ->
            service.attachSku(
                certification.id(),
                new CertificationSkuInput(skuId.toString(), "", "", ""),
                bearer));
    as(
        "channel.operation.create",
        () ->
            service.attachCategory(
                certification.id(),
                new CertificationCategoryInput(
                    categoryId.toString(), "KITCHEN", "厨房电器", "小家电须持 CE 证书")));

    CertificationDetail reloaded =
        as("channel.operation.read", () -> service.certification(certification.id()));
    assertThat(reloaded.skus()).hasSize(1);
    assertThat(reloaded.skus().get(0).skuCode()).isEqualTo("SKU-CE-01");
    assertThat(reloaded.skus().get(0).skuName()).isEqualTo("带电水壶");
    assertThat(reloaded.categories()).hasSize(1);
    assertThat(reloaded.categories().get(0).categoryCode()).isEqualTo("KITCHEN");
    assertThat(reloaded.categories().get(0).requirementNote()).isEqualTo("小家电须持 CE 证书");

    // 解挂
    run("channel.operation.delete", () -> service.detachSku(certification.id(), skuId.toString()));
    run(
        "channel.operation.delete",
        () -> service.detachCategory(certification.id(), categoryId.toString()));
    CertificationDetail after =
        as("channel.operation.read", () -> service.certification(certification.id()));
    assertThat(after.skus()).isEmpty();
    assertThat(after.categories()).isEmpty();
  }

  // ==================================================================
  // 7. 覆盖情况统计
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 覆盖统计真的算出「哪些 SKU 缺认证」")
  void coverageReallyComputesMissingSkus() {
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    catalog.putSku(skuA, "SKU-A", "商品 A", "厨房电器");
    catalog.putSku(skuB, "SKU-B", "商品 B", "厨房电器");

    as(
        "channel.operation.create",
        () ->
            service.createRequirement(
                new RequirementInput(
                    skuA.toString(), "SKU-A", "商品 A", "CE", categoryId.toString(), "KITCHEN", "厨房电器", ""),
                bearer));
    as(
        "channel.operation.create",
        () ->
            service.createRequirement(
                new RequirementInput(
                    skuA.toString(), "SKU-A", "商品 A", "FCC", categoryId.toString(), "KITCHEN", "厨房电器", ""),
                bearer));
    as(
        "channel.operation.create",
        () ->
            service.createRequirement(
                new RequirementInput(
                    skuB.toString(), "SKU-B", "商品 B", "CE", categoryId.toString(), "KITCHEN", "厨房电器", ""),
                bearer));

    CoverageReport before = as("channel.operation.read", service::coverage);
    assertThat(before.requiredRequirementCount()).isEqualTo(3);
    assertThat(before.missingRequirementCount()).isEqualTo(3);
    assertThat(before.missingSkuCount()).isEqualTo(2);
    assertThat(before.missingSkus()).extracting(CoverageMissingSku::skuCode)
        .containsExactlyInAnyOrder("SKU-A", "SKU-B");

    // 给 SKU-A 补一张当前有效的 CE 认证
    CertificationDetail certification = register("COV-1", "CE", -10, 100);
    as(
        "channel.operation.create",
        () ->
            service.attachSku(
                certification.id(),
                new CertificationSkuInput(skuA.toString(), "SKU-A", "商品 A", ""),
                bearer));

    CoverageReport after = as("channel.operation.read", service::coverage);
    assertThat(after.coveredRequirementCount()).isEqualTo(1);
    assertThat(after.missingRequirementCount()).isEqualTo(2);
    assertThat(after.missingSkuCount()).isEqualTo(2);
    assertThat(after.missingSkus()).hasSize(2);
    assertThat(after.missingSkus().stream()
            .filter(missing -> missing.skuCode().equals("SKU-A"))
            .findFirst()
            .orElseThrow()
            .missingTypeCodes())
        .as("SKU-A 补了 CE，仍缺 FCC")
        .containsExactly("FCC");

    // 按类型
    assertThat(after.byType()).extracting(CoverageByType::certificationTypeCode)
        .containsExactlyInAnyOrder("CE", "FCC");
    CoverageByType ce = after.byType().stream()
        .filter(row -> row.certificationTypeCode().equals("CE"))
        .findFirst()
        .orElseThrow();
    assertThat(ce.requiredCount()).isEqualTo(2);
    assertThat(ce.coveredCount()).isEqualTo(1);
    assertThat(ce.missingCount()).isEqualTo(1);

    // 按类目
    assertThat(after.byCategory()).hasSize(1);
    CoverageByCategory category = after.byCategory().get(0);
    assertThat(category.categoryCode()).isEqualTo("KITCHEN");
    assertThat(category.requiredCount()).isEqualTo(3);
    assertThat(category.missingSkuCount()).isEqualTo(2);

    // 单 SKU 明细
    CoverageSkuDetail detail = as("channel.operation.read", () -> service.coverageForSku(skuA.toString()));
    assertThat(detail.coveredTypeCodes()).containsExactly("CE");
    assertThat(detail.missingTypeCodes()).containsExactly("FCC");
    assertThat(detail.certificationIds()).containsExactly(certification.id());

    // 🔴 撤销那张认证 ⇒ 覆盖立刻掉下去
    as(
        "channel.operation.update",
        () -> service.revokeCertification(certification.id(), new CertificationRevokeInput("撤销")));
    CoverageReport revoked = as("channel.operation.read", service::coverage);
    assertThat(revoked.coveredRequirementCount()).isZero();
    assertThat(revoked.missingRequirementCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("16.2.2.2 已过期的认证不算覆盖（有效期到了就是没覆盖）")
  void expiredCertificationDoesNotCover() {
    UUID sku = UUID.randomUUID();
    catalog.putSku(sku, "SKU-EXP", "过期商品的 SKU", "厨房电器");
    as(
        "channel.operation.create",
        () ->
            service.createRequirement(
                new RequirementInput(sku.toString(), "SKU-EXP", "过期商品的 SKU", "CE", null, "", "", ""),
                bearer));

    CertificationDetail expired = register("EXP-1", "CE", -100, -1);
    as(
        "channel.operation.create",
        () ->
            service.attachSku(
                expired.id(),
                new CertificationSkuInput(sku.toString(), "SKU-EXP", "过期商品的 SKU", ""),
                bearer));

    CoverageReport coverage = as("channel.operation.read", service::coverage);
    assertThat(coverage.coveredRequirementCount())
        .as("过期认证不覆盖（但认证本身仍在档案里）")
        .isZero();
    assertThat(coverage.missingRequirementCount()).isEqualTo(1);
  }

  // ==================================================================
  // 8. 上游读不到 ⇒ 显式报读不到
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 catalog 读不到时显式报错，绝不用空列表冒充")
  void upstreamUnreachableIsReported() {
    catalog.putSku(UUID.randomUUID(), "SKU-X", "商品 X", "厨房电器");
    catalog.listStatus = 500;

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.read", () -> service.skuCandidates(null, 0, 20, bearer)));
    assertThat(thrown).as("catalog 挂掉时必须报错而不是给空列表").isNotNull();
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(thrown.getMessage()).contains("读不到 catalog");

    // 类目候选同理
    catalog.treesStatus = 500;
    Throwable categoryThrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.read", () -> service.categoryCandidates(null, bearer)));
    assertThat(categoryThrown).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("16.2.2.2 候选清单真的来自 catalog（不是本地假数据）")
  void candidatesComeFromCatalog() {
    UUID skuId = UUID.randomUUID();
    catalog.putSku(skuId, "SKU-CAND", "候选商品", "厨房电器");

    CandidatePage<ProductCertificationSkuCandidate> page =
        as("channel.operation.read", () -> service.skuCandidates(null, 0, 20, bearer));
    assertThat(page.source()).isEqualTo("catalog");
    assertThat(page.items()).extracting(ProductCertificationSkuCandidate::code).contains("SKU-CAND");

    UUID treeId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    catalog.putTree(treeId, "INTERNAL", "内部类目");
    catalog.putNode(nodeId, treeId, "KITCHEN", "厨房电器", "厨房电器");
    CandidatePage<CategoryCandidate> categories =
        as("channel.operation.read", () -> service.categoryCandidates(null, bearer));
    assertThat(categories.items()).extracting(CategoryCandidate::code).contains("KITCHEN");
  }

  // ==================================================================
  // 9. 乐观锁 / 分页 / HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.2.2.2 乐观锁：版本不符 409")
  void staleVersionIsRejected() {
    CertificationDetail created = register("LOCK-1", "CE", -10, 100);
    as(
        "channel.operation.update",
        () ->
            service.updateCertification(
                created.id(),
                new ProductCertificationInput(
                    "LOCK-1", "CE", "改名后的证书", "", "", "", at(-10), at(100), "", 0L)));

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.updateCertification(
                            created.id(),
                            new ProductCertificationInput(
                                "LOCK-1", "CE", "再改一次", "", "", "", at(-10), at(100), "", 0L))));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("16.2.2.2 台账是真分页：total 与 items 同源")
  void listingIsReallyPaginated() {
    register("PAGE-1", "CE", -10, 100);
    register("PAGE-2", "CE", -10, 101);
    register("PAGE-3", "CE", -10, 102);

    CertificationPage page = as("channel.operation.read", () -> service.certifications(null, null, null, null, null, null, 0, 2));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(2);
    assertThat(page.size()).isEqualTo(2);

    CertificationPage second = as("channel.operation.read", () -> service.certifications(null, null, null, null, null, null, 1, 2));
    assertThat(second.items()).hasSize(1);

    // 筛选也真的生效
    CertificationPage filtered = as("channel.operation.read", () -> service.certifications("FCC", null, null, null, null, null, 0, 20));
    assertThat(filtered.total()).isZero();
  }

  @Test
  @DisplayName("16.2.2.2 HTTP 面：401 / 404 / 200")
  void httpSurfaceBehaves() throws Exception {
    http.perform(get("/channel/product-certifications/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/product-certifications/certifications"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/product-certifications/capabilities")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiryStates").isArray());

    http.perform(get("/channel/product-certifications/certifications/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    CertificationDetail created = register("HTTP-1", "CE", -10, 100);
    http.perform(get("/channel/product-certifications/certifications/" + created.id())
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("HTTP-1"))
        .andExpect(jsonPath("$.expiryState").value("VALID"));
  }

  @Test
  @DisplayName("16.2.2.2 概览把四张卡的数算出来了")
  void overviewCountsAreComputed() {
    register("OV-VALID", "CE", -10, 100);
    register("OV-EXPIRING", "CE", -10, 5);
    register("OV-EXPIRED", "CE", -100, -1);

    CertificationOverview overview = as("channel.operation.read", service::overview);
    assertThat(overview.certificationCount()).isEqualTo(3);
    assertThat(overview.activeCount()).isEqualTo(3);
    assertThat(overview.expiringCount()).isEqualTo(1);
    assertThat(overview.expiredCount()).isEqualTo(1);
    assertThat(overview.warningDays()).isEqualTo(30);
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.close();
    pg.stop();
  }
}
