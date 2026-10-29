package com.starcon.channel;

import static com.starcon.channel.BrandCredentialModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.4.2.2 授权凭证。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 文件本体真存</b>：文件真的落盘、读回来逐字节相同，sha256 摘要入库，
 *       版本号递增；文件行<b>只追加</b>（直连数据库改 / 删都被拒）。
 *   <li><b>🔴 真伪核验是真的</b>：把磁盘上的文件改掉再核验 ⇒ DIGEST_MISMATCH；
 *       把文件删掉再核验 ⇒ FILE_MISSING —— <b>不是</b>「通过」。
 *   <li><b>🔴 核验记录是追加式凭据</b>：直连数据库改结论被拒；不通过必有逐条原因。
 *   <li><b>🔴 有效期往返逐字相等</b>：写入用带偏移的 {@link OffsetDateTime}，
 *       读回断言<b>时刻</b>完全相等（不偏 28800 秒）。
 *   <li><b>🔴 上游 16.4.2.1 同库只读</b>：授权候选真的来自 {@code brand_authz_authorization}。
 *   <li><b>🔴 撤销必须写理由</b>；台账<b>真分页</b>；HTTP 面 401 / 404 / 200。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandCredentialIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 凭证文件落在仓库内的 target 下，不写家目录也不写 /tmp。
    r.add("CREDENTIAL_STORAGE_ROOT", () -> "target/test-credential-store");
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
  }

  @Autowired BrandCredentialService service;
  @Autowired AccessService access;
  @Autowired CredentialDocumentStorage storage;
  @Autowired LocalCredentialDocumentStorage localStorage;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
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
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(plusDays).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  /** 登记一份凭证。 */
  BrandCredentialDetail register(String grantor, String no, int fromDays, int untilDays) {
    return as(
        "channel.operation.create",
        () ->
            service.create(
                new BrandCredentialRegistrationInput(
                    null,
                    grantor,
                    no,
                    "BRAND_LICENSE",
                    "测试被授权主体",
                    null,
                    "测试品牌",
                    at(fromDays).toString(),
                    at(untilDays).toString(),
                    null,
                    "集成测试")));
  }

  /** 上传一份凭证文件。 */
  BrandCredentialDocumentRow upload(String credentialId, String fileName, String content) {
    return as(
        "channel.operation.create",
        () ->
            service.uploadDocument(
                credentialId,
                content.getBytes(StandardCharsets.UTF_8),
                fileName,
                "application/pdf",
                "原件",
                user));
  }

  /** 上游 16.4.2.1 的一条授权档案（同库写入，用作上游候选）。 */
  String seedUpstreamAuthorization() {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into brand_authz_authorization (id,tenant_id,brand_id,brand_name,grantor,licensee_name,"
            + "licensee_type,effective_from,effective_to,enabled,rule_version,note,created_at,updated_at)"
            + " values ('"
            + id
            + "','"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','上游品牌','上游品牌方','上游被授权主体','SUPPLIER',"
            + "'"
            + at(-10)
            + "','"
            + at(100)
            + "',true,1,'上游测试',now(),now())");
    return id.toString();
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 能力声明：真伪怎么判、结论口径、边界与上下游都写出来了")
  void capabilitiesAreDeclared() {
    var caps = as("channel.operation.read", service::capabilities);
    assertThat(caps.digestSemantics()).contains("sha256").contains("重算");
    assertThat(caps.verificationSemantics()).contains("VERIFIED").contains("FILE_MISSING");
    assertThat(caps.appendOnlySemantics()).contains("只追加");
    assertThat(caps.boundaryNote()).contains("16.4.2.1").contains("写入所有权");
    assertThat(caps.upstreamNote()).contains("同一个库").contains("只读");
    assertThat(caps.downstreamNote()).contains("16.4.1.2");
    assertThat(caps.conclusions()).contains("VERIFIED", "DIGEST_MISMATCH", "FILE_MISSING");
    assertThat(caps.verificationStates()).containsExactly("UNVERIFIED", "VERIFIED", "FAILED");
  }

  // ==================================================================
  // 2. 凭证档案
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 凭证档案真落库：字段逐个对得上")
  void credentialIsReallyPersisted() {
    var detail = register("测试品牌方", "BR-0001", -10, 100);
    assertThat(detail.record().grantor()).isEqualTo("测试品牌方");
    assertThat(detail.record().credentialNo()).isEqualTo("BR-0001");
    assertThat(detail.record().credentialTypeLabel()).isEqualTo("品牌授权书");
    assertThat(detail.record().status()).isEqualTo("ACTIVE");
    assertThat(detail.record().expiryState()).isEqualTo("ACTIVE");
    assertThat(detail.record().verificationState()).isEqualTo("UNVERIFIED");
    assertThat(detail.record().currentVersion()).isZero();

    // 从 DB 直查确认真的写进去了
    assertThat(
            scalar(
                "select count(*) from brand_cred_credential where tenant_id = '"
                    + tenant
                    + "' and credential_no = 'BR-0001'"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 有效期往返逐字相等：不偏 28800 秒")
  void validityWindowRoundTripsExactly() {
    // 🔴 时刻**先抓成局部变量**再注册 —— at() 每次调用都是「此刻」，
    //    拿两次 at(-10) 去比必然差几毫秒（那是测试写错，不是实现偏了）。
    OffsetDateTime from = at(-10);
    OffsetDateTime until = at(100);
    var detail =
        as(
            "channel.operation.create",
            () ->
                service.create(
                    new BrandCredentialRegistrationInput(
                        null,
                        "时间品牌方",
                        "BR-TIME",
                        "BRAND_LICENSE",
                        "被授权主体",
                        null,
                        "品牌",
                        from.toString(),
                        until.toString(),
                        null,
                        "")));
    var stored =
        as(
            "channel.operation.read",
            () -> service.credential(detail.record().credentialId()).record());
    // 与写进去的原始值逐字相等（含时区偏移）
    assertThat(stored.validFrom().toInstant()).isEqualTo(from.toInstant());
    assertThat(stored.validUntil().toInstant()).isEqualTo(until.toInstant());
    // DB 里存的是同一个时刻（用**毫秒** epoch 比，不受会话时区影响）
    // 🔴 为什么比毫秒而不是秒：`extract(epoch from ts)::bigint` 是**四舍五入**，
    //    而 `Instant.getEpochSecond()` 是**截断** —— 比秒会差 1 秒，
    //    那是断言口径写错，不是实现偏了。写入时刻截断到毫秒，所以比毫秒是精确的。
    String millis =
        scalar(
            "select (extract(epoch from valid_until)*1000)::bigint::text from brand_cred_credential"
                + " where tenant_id = '"
                + tenant
                + "' and credential_no = 'BR-TIME'");
    assertThat(stored.validUntil().toInstant().toEpochMilli()).isEqualTo(Long.parseLong(millis));
  }

  @Test
  @DisplayName("16.4.2.2 台账是真分页：total 与 items 同源")
  void listingIsReallyPaginated() {
    for (int i = 0; i < 5; i++) {
      register("品牌方 " + i, "BR-" + i, -10, 100);
    }
    var page = as("channel.operation.read", () -> service.credentials(null, null, null, null, null, null, 0, 2));
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items()).hasSize(2);
    var page2 = as("channel.operation.read", () -> service.credentials(null, null, null, null, null, null, 1, 2));
    assertThat(page2.items()).hasSize(2);
    assertThat(page2.items()).doesNotContainAnyElementsOf(page.items());
  }

  @Test
  @DisplayName("16.4.2.2 关键词筛选是真的（不是把所有行都返回）")
  void keywordFilterReallyFilters() {
    register("甲品牌方", "AAA-111", -10, 100);
    register("乙品牌方", "BBB-222", -10, 100);
    var page = as("channel.operation.read", () -> service.credentials("AAA", null, null, null, null, null, 0, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).credentialNo()).isEqualTo("AAA-111");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 撤销必须写理由，且撤销是终态")
  void revokeRequiresReason() {
    var detail = register("撤销品牌方", "BR-REV", -10, 100);
    assertThatThrownBy(() -> as("channel.operation.update", () -> service.revoke(detail.record().credentialId(), "  ")))
        .hasMessageContaining("必须写明理由");
    var revoked =
        as("channel.operation.update", () -> service.revoke(detail.record().credentialId(), "品牌方通知终止授权"));
    assertThat(revoked.record().status()).isEqualTo("REVOKED");
    assertThat(revoked.record().revokedReason()).isEqualTo("品牌方通知终止授权");
    assertThat(revoked.record().expiryState()).isEqualTo("REVOKED");
    // 撤销后不能再改
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.update(
                            detail.record().credentialId(),
                            new BrandCredentialRegistrationInput(
                                null, "x", "y", "OTHER", "", null, "", at(-1).toString(), at(1).toString(), null, ""))))
        .hasMessageContaining("已撤销");
  }

  // ==================================================================
  // 3. 凭证文件版本
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 🔴 文件本体真存：落盘、读回逐字节相同、摘要入库、版本递增")
  void documentIsReallyStored() {
    var detail = register("文件品牌方", "BR-FILE", -10, 100);
    byte[] content = "这是授权书原件内容".getBytes(StandardCharsets.UTF_8);
    var doc =
        as(
            "channel.operation.create",
            () ->
                service.uploadDocument(
                    detail.record().credentialId(),
                    content,
                    "授权书.pdf",
                    "application/pdf",
                    "原件",
                    user));

    assertThat(doc.versionNo()).isEqualTo(1);
    assertThat(doc.sha256()).isEqualTo(BrandCredentialRules.sha256Hex(content));
    assertThat(doc.storageKey()).contains(tenant.toString());

    // 真的落盘了，读回来逐字节相同
    assertThat(readStorage(doc.storageKey())).isEqualTo(content);
    assertThat(storage.exists(doc.storageKey())).isTrue();

    // 再上传一份 = 第 2 版（只追加）
    var doc2 = upload(detail.record().credentialId(), "授权书-换件.pdf", "第二版内容");
    assertThat(doc2.versionNo()).isEqualTo(2);
    assertThat(doc2.sha256()).isNotEqualTo(doc.sha256());

    // 清单按版本倒序
    var docs = as("channel.operation.read", () -> service.documents(detail.record().credentialId()));
    assertThat(docs).hasSize(2);
    assertThat(docs.get(0).versionNo()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.4.2.2 🔴 文件版本只追加：直连数据库改 / 删都被拒")
  void documentRowsAreAppendOnly() {
    var detail = register("追加品牌方", "BR-AP", -10, 100);
    var doc = upload(detail.record().credentialId(), "a.pdf", "原件");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "update brand_cred_document set file_name = '改过.pdf' where tenant_id = '"
                        + tenant
                        + "' and id = '"
                        + doc.documentId()
                        + "'"))
        .hasMessageContaining("不可改写");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "delete from brand_cred_document where tenant_id = '"
                        + tenant
                        + "' and id = '"
                        + doc.documentId()
                        + "'"))
        .hasMessageContaining("不可改写");
  }

  @Test
  @DisplayName("16.4.2.2 base64 JSON 形状也能真存（浏览器代理走这条）")
  void documentUploadsThroughBase64Json() {
    var detail = register("base64 品牌方", "BR-B64", -10, 100);
    byte[] content = "授权书原件内容".getBytes(StandardCharsets.UTF_8);
    var doc =
        as(
            "channel.operation.create",
            () ->
                service.uploadDocument(
                    detail.record().credentialId(),
                    new BrandCredentialDocumentInput(
                        "授权书.pdf",
                        "application/pdf",
                        Base64.getEncoder().encodeToString(content),
                        "原件"),
                    user));
    assertThat(readStorage(doc.storageKey())).isEqualTo(content);
    assertThat(doc.sha256()).isEqualTo(BrandCredentialRules.sha256Hex(content));
  }

  // ==================================================================
  // 4. 一键核验
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 🔴 一键核验：摘要对得上 + 有签署方 ⇒ 通过")
  void verificationPassesWhenEverythingLinesUp() {
    var detail = register("核验品牌方", "BR-OK", -10, 100);
    upload(detail.record().credentialId(), "授权书.pdf", "原件内容");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                detail.record().credentialId(),
                new BrandCredentialSignatoryInput(
                    "张三", "法务总监", "甲品牌公司", "zhangsan@example.com", true, "")));

    var result = as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));
    assertThat(result.passed()).isTrue();
    assertThat(result.conclusion()).isEqualTo("VERIFIED");
    assertThat(result.severity()).isEqualTo("PASS");
    assertThat(result.reasons()).isEmpty();
    assertThat(result.ruleVersion()).isEqualTo(BrandCredentialRules.CURRENT_RULE_VERSION);
    // 凭证档案上的「最近核验状态」跟着变
    assertThat(result.credential().verificationState()).isEqualTo("VERIFIED");
    assertThat(result.credential().lastConclusion()).isEqualTo("VERIFIED");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 文件被改过 ⇒ DIGEST_MISMATCH（真的改磁盘上的文件）")
  void tamperedFileFailsVerification() {
    var detail = register("篡改品牌方", "BR-TAMPER", -10, 100);
    var doc = upload(detail.record().credentialId(), "授权书.pdf", "原件内容");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                detail.record().credentialId(),
                new BrandCredentialSignatoryInput("李四", "经理", "甲品牌公司", "", true, "")));

    // 绕过系统直接改受管存储里的文件（模拟「登记之后被人换掉了」）
    try {
      storage.put(doc.storageKey(), "被换掉的内容".getBytes(StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

    var result = as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));
    assertThat(result.passed()).isFalse();
    assertThat(result.conclusion()).isEqualTo("DIGEST_MISMATCH");
    assertThat(result.severity()).isEqualTo("FAIL");
    assertThat(result.reasons()).isNotEmpty();
    assertThat(result.reasons().get(0).code()).isEqualTo("DIGEST_MISMATCH");
    assertThat(result.snapshot()).containsKey("expectedSha256").containsKey("actualSha256");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 文件不在受管存储 ⇒ FILE_MISSING（不是「通过」）")
  void missingFileFailsVerification() {
    var detail = register("丢件品牌方", "BR-MISS", -10, 100);
    var doc = upload(detail.record().credentialId(), "授权书.pdf", "原件内容");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                detail.record().credentialId(),
                new BrandCredentialSignatoryInput("王五", "总监", "甲品牌公司", "", true, "")));

    // 真的把文件从存储里删掉（模拟卷掉了 / 被人删了）
    Path target = localStorage.root().resolve(doc.storageKey()).normalize();
    assertThat(target).exists();
    try {
      Files.delete(target);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

    var result = as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));
    assertThat(result.passed()).isFalse();
    assertThat(result.conclusion()).isEqualTo("FILE_MISSING");
    assertThat(result.reasons().get(0).code()).isEqualTo("FILE_MISSING");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 没有文件 / 没有签署方 / 已过期 ⇒ 各自不通过，且都带原因")
  void missingPiecesFailWithReasons() {
    var noDoc = register("无文件品牌方", "BR-NODOC", -10, 100);
    var r1 = as("channel.operation.create", () -> service.verify(noDoc.record().credentialId(), user));
    assertThat(r1.conclusion()).isEqualTo("NO_DOCUMENT");
    assertThat(r1.reasons()).isNotEmpty();

    var noSign = register("无签署方品牌方", "BR-NOSIGN", -10, 100);
    upload(noSign.record().credentialId(), "a.pdf", "原件");
    var r2 = as("channel.operation.create", () -> service.verify(noSign.record().credentialId(), user));
    assertThat(r2.conclusion()).isEqualTo("SIGNATORY_INCOMPLETE");
    assertThat(r2.reasons()).isNotEmpty();

    var expired = register("过期品牌方", "BR-EXP", -100, -1);
    upload(expired.record().credentialId(), "a.pdf", "原件");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                expired.record().credentialId(),
                new BrandCredentialSignatoryInput("赵六", "", "", "", true, "")));
    var r3 = as("channel.operation.create", () -> service.verify(expired.record().credentialId(), user));
    assertThat(r3.conclusion()).isEqualTo("EXPIRED");
    assertThat(r3.reasons()).isNotEmpty();
  }

  @Test
  @DisplayName("16.4.2.2 🔴 核验记录不可改写（直连数据库改结论被拒）")
  void verificationRowsAreImmutable() {
    var detail = register("不可改品牌方", "BR-IMM", -10, 100);
    upload(detail.record().credentialId(), "a.pdf", "原件");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                detail.record().credentialId(),
                new BrandCredentialSignatoryInput("钱七", "", "", "", true, "")));
    var result = as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));

    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "update brand_cred_verification set conclusion = 'EXPIRED' where tenant_id = '"
                        + tenant
                        + "' and id = '"
                        + result.verificationId()
                        + "'"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "delete from brand_cred_verification where tenant_id = '"
                        + tenant
                        + "' and id = '"
                        + result.verificationId()
                        + "'"))
        .hasMessageContaining("不可删除");
  }

  @Test
  @DisplayName("16.4.2.2 🔴 不通过的核验必须带原因（DB 层 CHECK 兜底）")
  void failedVerificationMustCarryReasons() {
    var detail = register("无原因品牌方", "BR-NOREASON", -10, 100);
    upload(detail.record().credentialId(), "a.pdf", "原件");
    var id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into brand_cred_verification (id,tenant_id,credential_id,document_version_no,"
                        + "evaluated_at,rule_version,conclusion,severity,passed,reasons,snapshot,created_at)"
                        + " values ('"
                        + id
                        + "','"
                        + tenant
                        + "','"
                        + detail.record().credentialId()
                        + "',0,now(),1,'DIGEST_MISMATCH','FAIL',false,'[]'::jsonb,'{}'::jsonb,now())"))
        .hasMessageContaining("brand_cred_verification_reasons_required");
  }

  @Test
  @DisplayName("16.4.2.2 批量核验：逐条落凭据，通过 / 不通过分别计数")
  void batchVerificationCountsEachResult() {
    var ok = register("批量甲", "BR-B1", -10, 100);
    upload(ok.record().credentialId(), "a.pdf", "原件");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                ok.record().credentialId(),
                new BrandCredentialSignatoryInput("甲", "", "", "", true, "")));
    var bad = register("批量乙", "BR-B2", -10, 100);

    var batch =
        as(
            "channel.operation.create",
            () ->
                service.verifyBatch(
                    List.of(ok.record().credentialId(), bad.record().credentialId()), user));
    assertThat(batch.results()).hasSize(2);
    assertThat(batch.verifiedCount()).isEqualTo(1);
    assertThat(batch.failedCount()).isEqualTo(1);
    // 每条都落了不可改写的凭据
    assertThat(
            scalar(
                "select count(*) from brand_cred_verification where tenant_id = '"
                    + tenant
                    + "' and credential_id in ('"
                    + ok.record().credentialId()
                    + "','"
                    + bad.record().credentialId()
                    + "')"))
        .isEqualTo("2");
  }

  @Test
  @DisplayName("16.4.2.2 核验历史是真分页，且能按凭证筛")
  void verificationHistoryIsReallyPaginated() {
    var detail = register("历史品牌方", "BR-HIST", -10, 100);
    upload(detail.record().credentialId(), "a.pdf", "原件");
    as(
        "channel.operation.create",
        () ->
            service.addSignatory(
                detail.record().credentialId(),
                new BrandCredentialSignatoryInput("乙", "", "", "", true, "")));
    as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));
    as("channel.operation.create", () -> service.verify(detail.record().credentialId(), user));

    var page = as("channel.operation.read", () -> service.verifications(detail.record().credentialId(), null, 0, 1));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).credentialNo()).isEqualTo("BR-HIST");
  }

  // ==================================================================
  // 5. 上游 16.4.2.1（同库只读）
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 🔴 上游候选真的来自 16.4.2.1 的授权档案（同库只读）")
  void upstreamCandidatesComeFromBrandAuthorization() {
    String authorizationId = seedUpstreamAuthorization();
    var options = as("channel.operation.read", () -> service.authorizationOptions("上游"));
    assertThat(options.items()).hasSize(1);
    assertThat(options.items().get(0).authorizationId()).isEqualTo(authorizationId);
    assertThat(options.items().get(0).grantor()).isEqualTo("上游品牌方");
    assertThat(options.note()).contains("同库只读");
  }

  @Test
  @DisplayName("16.4.2.2 凭证可以挂在上游授权下（逻辑 id，不建外键）")
  void credentialCanReferenceUpstreamAuthorization() {
    String authorizationId = seedUpstreamAuthorization();
    var detail =
        as(
            "channel.operation.create",
            () ->
                service.create(
                    new BrandCredentialRegistrationInput(
                        authorizationId,
                        "挂靠品牌方",
                        "BR-LINK",
                        "BRAND_LICENSE",
                        "被授权主体",
                        null,
                        "品牌",
                        at(-10).toString(),
                        at(100).toString(),
                        null,
                        "")));
    assertThat(detail.record().authorizationId()).isEqualTo(authorizationId);
  }

  @Test
  @DisplayName("16.4.2.2 挂一个不存在���上游授权 id ⇒ 被拒（不能挂空引用）")
  void danglingUpstreamAuthorizationIsRejected() {
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.create(
                            new BrandCredentialRegistrationInput(
                                UUID.randomUUID().toString(),
                                "空挂品牌方",
                                "BR-DANGLE",
                                "BRAND_LICENSE",
                                "",
                                null,
                                "",
                                at(-10).toString(),
                                at(100).toString(),
                                null,
                                ""))))
        .hasMessageContaining("上游授权档案不存在");
  }

  // ==================================================================
  // 6. 概览与 HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.4.2.2 概览把卡片上的数算出来了")
  void overviewCountsAreComputed() {
    register("概览甲", "BR-O1", -10, 100);
    register("概览乙", "BR-O2", -100, -1);
    var overview = as("channel.operation.read", service::overview);
    assertThat(overview.credentialCount()).isEqualTo(2);
    assertThat(overview.expiredCount()).isEqualTo(1);
    assertThat(overview.activeCount()).isEqualTo(1);
    assertThat(overview.unverifiedCount()).isEqualTo(2);
    assertThat(overview.verifiedCount()).isZero();
    assertThat(overview.failedCount()).isZero();
  }

  @Test
  @DisplayName("16.4.2.2 HTTP 面：401 / 404 / 200")
  void httpSurfaceBehaves() throws Exception {
    http.perform(get("/channel/brand-credentials/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/brand-credentials/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk());
    http.perform(
            get("/channel/brand-credentials/credentials/" + UUID.randomUUID())
                .header("Authorization", bearer))
        .andExpect(status().isNotFound());
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
    pg.stop();
  }
}
