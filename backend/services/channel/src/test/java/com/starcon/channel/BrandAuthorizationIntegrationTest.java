package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.4.2.1 品牌授权的集成测试（**真实 PostgreSQL 16** + 本地真 HTTP 上游）。
 *
 * <p>🔴 这组用例守的是五件最容易做丢的事：
 *
 * <ol>
 *   <li><b>真落库</b>：授权档案、范围、文件、判定凭据全在 PostgreSQL 里，不是内存里的假数据；
 *   <li><b>timestamptz 往返逐字相等</b>：写进去的时刻与读出来的是同一刻（不偏 28800 秒）；
 *   <li><b>没有原因的 false 是不允许出现的</b>：每条不覆盖都带原因码 + 人话；
 *   <li><b>「判不了」不等于「合格」</b>：上下文缺维度 ⇒ UNKNOWN，闸门不放行；
 *   <li><b>判定凭据不可追溯改写</b>（DB 层触发器真的拒）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandAuthorizationIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static BrandAuthorizationUpstreamStub upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static UUID siteUs;
  static UUID siteDe;

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
      upstream = new BrandAuthorizationUpstreamStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
    siteUs = marketplaceId(AMAZON, "US");
    siteDe = marketplaceId(AMAZON, "DE");
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
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
    r.add("CATALOG_URL", () -> upstream.base());
    r.add("TRADE_URL", () -> upstream.base());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired BrandAuthorizationService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void execAsTenant(UUID tenantId, String statement) {
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
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(String code, UUID... marketplaces) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    for (UUID marketplace : marketplaces) {
      execAsTenant(
          tenant,
          "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
              + tenant
              + "','"
              + id
              + "','"
              + marketplace
              + "','ACTIVE')");
    }
    return id;
  }

  BrandAuthorizationRegistrationInput input(
      UUID brandId, String grantor, String from, String to, List<BrandAuthorizationScopeInput> scopes) {
    return new BrandAuthorizationRegistrationInput(
        brandId.toString(),
        "测试品牌",
        grantor,
        "测试被授权主体",
        "SUPPLIER",
        null,
        from,
        to,
        true,
        "集成测试录入",
        scopes,
        List.of(
            new BrandAuthorizationDocumentInput(
                "授权书.pdf", "application/pdf", 2048L, "obj://brand-authz/contract.pdf", null, "测试文件")));
  }

  String iso(OffsetDateTime value) {
    return value.toString();
  }

  BrandAuthorizationDetail create(
      UUID brandId, String grantor, OffsetDateTime from, OffsetDateTime to,
      List<BrandAuthorizationScopeInput> scopes) {
    return as(
        "channel.operation.create",
        () -> service.create(input(brandId, grantor, iso(from), iso(to), scopes)));
  }

  BrandAuthorizationCheckResult check(
      UUID brandId, UUID shopId, String countryCode, UUID categoryId) {
    return as(
        "channel.operation.create",
        () ->
            service.check(
                new BrandAuthorizationCheckRequest(
                    brandId.toString(),
                    shopId == null ? null : shopId.toString(),
                    countryCode,
                    categoryId == null ? null : categoryId.toString()),
                bearer));
  }

  BrandAuthorizationPublishGate gate(
      UUID brandId, UUID shopId, String countryCode, UUID categoryId) {
    return as(
        "channel.operation.create",
        () ->
            service.publishGate(
                new BrandAuthorizationCheckRequest(
                    brandId.toString(),
                    shopId == null ? null : shopId.toString(),
                    countryCode,
                    categoryId == null ? null : categoryId.toString()),
                bearer));
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 能力声明：五个未交付下游逐条 available=false + ownerModule + 影响说明")
  void capabilitiesAreHonest() {
    BrandAuthorizationCapabilities capabilities = as("channel.operation.read", service::capabilities);
    assertThat(capabilities.gaps()).hasSize(5);
    assertThat(capabilities.gaps())
        .extracting(BrandAuthorizationCapabilityGap::moduleId)
        .containsExactlyInAnyOrder("16.4.1.1", "16.4.1.2", "16.4.2.2", "16.2.1.1", "16.2.1.2");
    assertThat(capabilities.gaps()).allSatisfy(gap -> {
      assertThat(gap.available()).as(gap.moduleId() + " 必须标未交付").isFalse();
      assertThat(gap.ownerModule()).as(gap.moduleId() + " 必须写归属模块").isNotBlank();
      assertThat(gap.impact()).as(gap.moduleId() + " 必须写影响说明").isNotBlank();
    });
    assertThat(capabilities.conclusions()).contains("IN_SCOPE", "OUT_OF_SCOPE", "EXPIRED", "NO_AUTHORIZATION", "UNKNOWN");
    assertThat(capabilities.scopeKinds()).containsExactly("SHOP", "COUNTRY", "CATEGORY");
    assertThat(capabilities.boundaryNote()).contains("3.2.1.2");
    assertThat(capabilities.upstreamNote()).contains("trade");
  }

  // ==================================================================
  // 录入 / 读取 / 修改
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 授权档案真落库：档案 + 范围 + 文件都读得回来")
  void createAndReadAuthorization() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("AUTH-1", siteUs);
    UUID categoryId = UUID.randomUUID();
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
    OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(200);

    BrandAuthorizationDetail detail =
        create(
            brandId,
            "品牌方",
            from,
            to,
            List.of(
                new BrandAuthorizationScopeInput("SHOP", shopId.toString(), "测试店铺"),
                new BrandAuthorizationScopeInput("COUNTRY", "us", "美国"),
                new BrandAuthorizationScopeInput("CATEGORY", categoryId.toString(), "测试类目")));

    assertThat(detail.record().brandId()).isEqualTo(brandId.toString());
    assertThat(detail.record().grantor()).isEqualTo("品牌方");
    assertThat(detail.record().licenseeType()).isEqualTo("SUPPLIER");
    assertThat(detail.record().scopeCount()).isEqualTo(3);
    assertThat(detail.record().documentCount()).isEqualTo(1);
    assertThat(detail.scopes()).hasSize(3);
    // 🔴 国家码被归一化成大写（DB 的 CHECK 也钉住这一条）
    assertThat(detail.scopes())
        .filteredOn(scope -> scope.scopeKind().equals("COUNTRY"))
        .singleElement()
        .satisfies(scope -> assertThat(scope.scopeValue()).isEqualTo("US"));
    assertThat(detail.documents()).singleElement().satisfies(document -> {
      assertThat(document.fileName()).isEqualTo("授权书.pdf");
      assertThat(document.contentType()).isEqualTo("application/pdf");
      assertThat(document.sizeBytes()).isEqualTo(2048L);
      assertThat(document.storageKey()).isEqualTo("obj://brand-authz/contract.pdf");
    });

    // 真落库：直接查库（超级用户只用来确认行真的在库里）
    assertThat(
            scalar(
                "select count(*) from brand_authz_authorization where tenant_id='"
                    + tenant
                    + "' and id='"
                    + detail.record().authorizationId()
                    + "'"))
        .isEqualTo("1");
    assertThat(
            scalar(
                "select count(*) from brand_authz_scope where tenant_id='"
                    + tenant
                    + "' and authorization_id='"
                    + detail.record().authorizationId()
                    + "'"))
        .isEqualTo("3");
    assertThat(
            scalar(
                "select count(*) from brand_authz_document where tenant_id='"
                    + tenant
                    + "' and authorization_id='"
                    + detail.record().authorizationId()
                    + "'"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 timestamptz 往返逐字相等（写进去的时刻与读出来是同一刻，不偏 28800 秒）")
  void timestampsRoundTripWithoutShift() {
    UUID brandId = UUID.randomUUID();
    OffsetDateTime from = OffsetDateTime.of(2026, 3, 1, 8, 30, 0, 0, ZoneOffset.UTC);
    OffsetDateTime to = OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, ZoneOffset.UTC);
    BrandAuthorizationDetail detail = create(brandId, "品牌方", from, to, List.of());

    assertThat(OffsetDateTime.parse(detail.record().effectiveFrom()).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(OffsetDateTime.parse(detail.record().effectiveTo()).toInstant())
        .isEqualTo(to.toInstant());

    // 库里那一列也是同一个瞬间（用 epoch 比，不受会话时区影响）
    assertThat(
            scalar(
                "select extract(epoch from effective_from)::bigint from brand_authz_authorization where id='"
                    + detail.record().authorizationId()
                    + "'"))
        .isEqualTo(String.valueOf(from.toEpochSecond()));
    assertThat(
            scalar(
                "select extract(epoch from effective_to)::bigint from brand_authz_authorization where id='"
                    + detail.record().authorizationId()
                    + "'"))
        .isEqualTo(String.valueOf(to.toEpochSecond()));
  }

  @Test
  @DisplayName("16.4.2.1 入参校验是真的：期限倒挂 / 空授权方 / 非法国家码都被拒")
  void inputValidationIsReal() {
    UUID brandId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                input(brandId, "品牌方", iso(now.plusDays(10)), iso(now), List.of())))))
        .as("期限倒挂必须被拒")
        .isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                input(brandId, "  ", iso(now.minusDays(1)), iso(now.plusDays(1)), List.of())))))
        .as("授权方为空必须被拒")
        .isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                input(
                                    brandId,
                                    "品牌方",
                                    iso(now.minusDays(1)),
                                    iso(now.plusDays(1)),
                                    List.of(new BrandAuthorizationScopeInput("COUNTRY", "GERMANY", "")))))))
        .as("国家码不是两位必须被拒")
        .isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                input(
                                    brandId,
                                    "品牌方",
                                    iso(now.minusDays(1)),
                                    iso(now.plusDays(1)),
                                    List.of(
                                        new BrandAuthorizationScopeInput(
                                            "SHOP", UUID.randomUUID().toString(), "不存在的店铺")))))))
        .as("店铺范围里的店铺不存在必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 校验
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 范围覆盖 ⇒ IN_SCOPE + 命中那条授权")
  void checkInScope() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("SCOPE-OK", siteUs);
    UUID categoryId = UUID.randomUUID();
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(
            new BrandAuthorizationScopeInput("SHOP", shopId.toString(), "测试店铺"),
            new BrandAuthorizationScopeInput("COUNTRY", "US", "美国"),
            new BrandAuthorizationScopeInput("CATEGORY", categoryId.toString(), "测试类目")));

    BrandAuthorizationCheckResult result = check(brandId, shopId, "US", categoryId);
    assertThat(result.inScope()).isTrue();
    assertThat(result.conclusion()).isEqualTo("IN_SCOPE");
    assertThat(result.severity()).isEqualTo("NONE");
    assertThat(result.blocking()).isFalse();
    assertThat(result.matchedAuthorizationId()).isNotBlank();
    assertThat(result.reasons()).isEmpty();

    // 判定凭据真落库
    assertThat(
            scalar(
                "select count(*) from brand_authz_check where tenant_id='"
                    + tenant
                    + "' and id='"
                    + result.checkId()
                    + "'"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 不覆盖国家 ⇒ 逐条原因带原因码 + 人话（没有原因的 false 不允许出现）")
  void checkOutOfScopeCarriesReasons() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("SCOPE-BAD", siteDe);
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));

    BrandAuthorizationCheckResult result = check(brandId, shopId, "DE", null);
    assertThat(result.inScope()).isFalse();
    assertThat(result.conclusion()).isEqualTo("OUT_OF_SCOPE");
    assertThat(result.blocking()).isTrue();
    assertThat(result.severity()).isEqualTo("BLOCKING");
    assertThat(result.reasons()).isNotEmpty();
    assertThat(result.reasons().get(0).code()).isEqualTo("COUNTRY_NOT_IN_SCOPE");
    assertThat(result.reasons().get(0).message()).contains("DE");
    assertThat(result.reasons().get(0).authorizationId()).isNotBlank();
  }

  @Test
  @DisplayName("16.4.2.1 授权过期 ⇒ EXPIRED（平时没事、上新时出事的那一半）")
  void checkExpired() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("SCOPE-EXPIRED", siteUs);
    create(
        brandId,
        "品牌方",
        OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
        OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));

    BrandAuthorizationCheckResult result = check(brandId, shopId, "US", null);
    assertThat(result.inScope()).isFalse();
    assertThat(result.conclusion()).isEqualTo("EXPIRED");
    assertThat(result.blocking()).isTrue();
    assertThat(result.reasons()).extracting(BrandAuthorizationReason::code).contains("AUTHORIZATION_EXPIRED");
  }

  @Test
  @DisplayName("16.4.2.1 没有任何授权档案 ⇒ NO_AUTHORIZATION + 阻断")
  void checkWithoutAuthorization() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("SCOPE-NONE", siteUs);
    BrandAuthorizationCheckResult result = check(brandId, shopId, "US", null);
    assertThat(result.conclusion()).isEqualTo("NO_AUTHORIZATION");
    assertThat(result.blocking()).isTrue();
    assertThat(result.reasons()).extracting(BrandAuthorizationReason::code).contains("NO_AUTHORIZATION");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 上下文缺维度 ⇒ UNKNOWN（判不了），且闸门不放行")
  void checkUnknownWhenContextIncomplete() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("SCOPE-UNKNOWN", siteUs);
    UUID categoryId = UUID.randomUUID();
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(new BrandAuthorizationScopeInput("CATEGORY", categoryId.toString(), "测试类目")));

    // 没给类目 ⇒ 类目维度判不了
    BrandAuthorizationCheckResult result = check(brandId, shopId, "US", null);
    assertThat(result.inScope()).isFalse();
    assertThat(result.conclusion()).isEqualTo("UNKNOWN");
    assertThat(result.severity()).isEqualTo("REVIEW");
    assertThat(result.reasons()).extracting(BrandAuthorizationReason::code)
        .contains("CATEGORY_UNRESOLVED");

    // 🔴 闸门不放行（判不了 ≠ 合格）
    BrandAuthorizationPublishGate gate = gate(brandId, shopId, "US", null);
    assertThat(gate.allowed()).isFalse();
    assertThat(gate.conclusion()).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 刊登闸门在服务端强制：不在范围 ⇒ allowed=false + 逐条原因")
  void publishGateBlocksOutOfScope() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("GATE-BAD", siteDe);
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));

    BrandAuthorizationPublishGate gate = gate(brandId, shopId, "DE", null);
    assertThat(gate.allowed()).isFalse();
    assertThat(gate.blocking()).isTrue();
    assertThat(gate.severity()).isEqualTo("BLOCKING");
    assertThat(gate.reasons()).isNotEmpty();
    assertThat(gate.checkId()).isNotBlank();
  }

  @Test
  @DisplayName("16.4.2.1 刊登闸门放行的唯一条件是 IN_SCOPE")
  void publishGateAllowsInScope() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("GATE-OK", siteUs);
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));

    BrandAuthorizationPublishGate gate = gate(brandId, shopId, "US", null);
    assertThat(gate.allowed()).isTrue();
    assertThat(gate.blocking()).isFalse();
    assertThat(gate.severity()).isEqualTo("NONE");
  }

  // ==================================================================
  // 到期提醒
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 到期提醒：真筛选 + 真分页（临期 / 已过期各能筛出来）")
  void expiryRemindersFilterAndPage() {
    UUID brandId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    create(
        brandId,
        "快到期",
        now.minusDays(10),
        now.plusDays(5),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));
    create(
        brandId,
        "已过期",
        now.minusDays(100),
        now.minusDays(5),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));
    create(
        brandId,
        "生效中",
        now.minusDays(1),
        now.plusDays(200),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));

    BrandAuthorizationExpiryPage expiring =
        as("channel.operation.read", () -> service.expiryReminders("EXPIRING", null, 0, 20));
    assertThat(expiring.items()).hasSize(1);
    assertThat(expiring.items().get(0).grantor()).isEqualTo("快到期");
    assertThat(expiring.items().get(0).daysToExpiry()).isBetween(4, 5);
    assertThat(expiring.expiringCount()).isEqualTo(1);
    assertThat(expiring.expiredCount()).isEqualTo(1);

    BrandAuthorizationExpiryPage expired =
        as("channel.operation.read", () -> service.expiryReminders("EXPIRED", null, 0, 20));
    assertThat(expired.items()).hasSize(1);
    assertThat(expired.items().get(0).grantor()).isEqualTo("已过期");
    assertThat(expired.items().get(0).daysToExpiry()).isNegative();

    // 真分页
    BrandAuthorizationExpiryPage page0 =
        as("channel.operation.read", () -> service.expiryReminders(null, null, 0, 2));
    BrandAuthorizationExpiryPage page1 =
        as("channel.operation.read", () -> service.expiryReminders(null, null, 1, 2));
    assertThat(page0.total()).isEqualTo(3);
    assertThat(page0.items()).hasSize(2);
    assertThat(page1.items()).hasSize(1);
    assertThat(page0.items().get(0).authorizationId())
        .isNotEqualTo(page1.items().get(0).authorizationId());
  }

  // ==================================================================
  // DB 层不可篡改
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 🔴 判定凭据不可追溯改写（DB 触发器真的拒）")
  void verdictIsImmutable() {
    UUID brandId = UUID.randomUUID();
    UUID shopId = seedShop("IMMUTABLE", siteUs);
    create(
        brandId,
        "品牌方",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
        List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));
    BrandAuthorizationCheckResult result = check(brandId, shopId, "US", null);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update brand_authz_check set in_scope = false, blocking = true, "
                            + "severity = 'BLOCKING', conclusion = 'OUT_OF_SCOPE' where id = '"
                            + result.checkId()
                            + "'")))
        .as("改写判定结论必须被 DB 拒")
        .isNotNull();

    // 原来那条还逐字没变
    assertThat(
            scalar(
                "select conclusion from brand_authz_check where id='" + result.checkId() + "'"))
        .isEqualTo("IN_SCOPE");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 规则版本不许回退（把历史判定改回旧口径不是修订，是篡改）")
  void ruleVersionCannotGoBackwards() {
    UUID brandId = UUID.randomUUID();
    BrandAuthorizationDetail detail =
        create(
            brandId,
            "品牌方",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(100),
            List.of());

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update brand_authz_authorization set rule_version = 0 where id = '"
                            + detail.record().authorizationId()
                            + "'")))
        .as("规则版本回退必须被 DB 拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.4.2.1 🔴 没有原因的 false 写不进库（DB CHECK 兜底）")
  void verdictWithoutReasonsIsRejected() {
    UUID brandId = UUID.randomUUID();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into brand_authz_check (id,tenant_id,brand_id,evaluated_at,rule_version,"
                            + "in_scope,blocking,severity,conclusion,reasons,snapshot) values ('"
                            + UUID.randomUUID()
                            + "','"
                            + tenant
                            + "','"
                            + brandId
                            + "',now(),1,false,true,'BLOCKING','OUT_OF_SCOPE','[]'::jsonb,'{}'::jsonb)")))
        .as("没有原因的不合格判定必须被 DB 拒")
        .isNotNull();
  }

  // ==================================================================
  // 候选（跨服务只读）
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 候选真读上游：03 品牌（catalog）+ 6.1.2.3 供应商资质（trade）")
  void candidatesComeFromUpstream() {
    upstream.putBrand("候选品牌");
    upstream.putSupplier("候选供应商");
    upstream.putQualification(UUID.randomUUID(), "候选供应商", "APPROVED", "BRAND_AGENT");

    BrandAuthorizationCandidatePage brands =
        as("channel.operation.read", () -> service.brandCandidates("候选", bearer));
    assertThat(brands.items()).isNotEmpty();
    assertThat(brands.items().get(0).name()).isEqualTo("候选品牌");

    BrandAuthorizationCandidatePage suppliers =
        as("channel.operation.read", () -> service.supplierCandidates("候选", bearer));
    assertThat(suppliers.items()).isNotEmpty();
    assertThat(suppliers.items().get(0).name()).isEqualTo("候选供应商");
  }

  @Test
  @DisplayName("16.4.2.1 🔴 上游读不到 ⇒ 显式说「读不到」，不是「没有候选」")
  void upstreamFailureIsReportedAsUnavailable() {
    upstream.catalogStatus = 500;
    upstream.tradeStatus = 500;
    try {
      BrandAuthorizationCandidatePage brands =
          as("channel.operation.read", () -> service.brandCandidates("x", bearer));
      assertThat(brands.note()).isNotBlank();
      assertThat(brands.note()).contains("catalog");

      BrandAuthorizationCandidatePage suppliers =
          as("channel.operation.read", () -> service.supplierCandidates("x", bearer));
      assertThat(suppliers.note()).isNotBlank();
      assertThat(suppliers.note()).contains("trade");
    } finally {
      upstream.catalogStatus = 0;
      upstream.tradeStatus = 0;
    }
  }

  // ==================================================================
  // 分页 / 历史 / HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.4.2.1 授权列表与校验历史都是真分页")
  void listsAreReallyPaged() {
    UUID brandId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (int i = 0; i < 3; i++) {
      create(
          brandId,
          "授权方 " + i,
          now.minusDays(1),
          now.plusDays(30 + i),
          List.of(new BrandAuthorizationScopeInput("COUNTRY", "US", "美国")));
    }
    BrandAuthorizationPage page0 =
        as("channel.operation.read", () -> service.authorizations(brandId.toString(), null, null, 0, 2));
    assertThat(page0.total()).isEqualTo(3);
    assertThat(page0.items()).hasSize(2);
    BrandAuthorizationPage page1 =
        as("channel.operation.read", () -> service.authorizations(brandId.toString(), null, null, 1, 2));
    assertThat(page1.items()).hasSize(1);

    check(brandId, null, "US", null);
    check(brandId, null, "US", null);
    BrandAuthorizationCheckPage checks =
        as("channel.operation.read", () -> service.checks(brandId.toString(), 0, 1));
    assertThat(checks.total()).isEqualTo(2);
    assertThat(checks.items()).hasSize(1);
    assertThat(checks.items().get(0).reasonCount()).isZero();
  }

  @Test
  @DisplayName("16.4.2.1 HTTP 面：401（无会话）/ 404（不存在）")
  void httpSurface() throws Exception {
    http.perform(get("/channel/brand-authorizations/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/brand-authorizations/authorizations/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/brand-authorizations/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gaps.length()").value(5));
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
    if (upstream != null) upstream.close();
    pg.stop();
  }
}
