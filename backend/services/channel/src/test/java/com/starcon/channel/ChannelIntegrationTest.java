package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 02 渠道与店铺 integration coverage.
 *
 * <p>Beyond CRUD behaviour this pins the three tenancy guarantees AGENTS.md makes
 * non-negotiable: two tenants never see each other, a forged tenant header changes nothing,
 * and returning a connection to the pool never leaks the previous tenant's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "channel." + p + "." + op))
          .toList();
  static final List<String> shopReadOnly = List.of("channel.shop.read", "channel.registry.read");

  static AccessService.Access actor(UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
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
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 角色可能已存在（服务迁移用条件创建，容器也可能被复用），避免重复建角色报错
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
            AccessService.Access a2 = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (a2 == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(a2);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
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
    // A single connection makes the "connection returned to the pool" test meaningful:
    // the next borrower physically gets the same session, so leaked settings would persist.
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService service;
  @Autowired DSLContext sql;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  /**
   * Runs work under an explicit permission.
   *
   * <p>The data-scope policy compares {@code app.permission} against the caller's grants, so a test
   * must declare the permission it is actually exercising — running a connection write under a shop
   * permission would make RLS reject legitimate rows and hide real regressions.
   */
  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T runA(java.util.function.Supplier<T> work) {
    return as(actorA, "channel.shop.update", work);
  }

  <T> T runConn(java.util.function.Supplier<T> work) {
    return as(actorA, "channel.connection.update", work);
  }

  <T> T runB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "channel.shop.update", work);
  }

  static AccessService.Access tenantActor(UUID t) {
    return actor(user, t, null, List.of(grant("ALL", allPermissions)));
  }

  UUID shop(UUID tenant, AccessService.Access who, String code, String mode) {
    return runA(
        () ->
            service.createShop(
                who,
                new ShopInput(
                    code,
                    "Shop " + code,
                    "AMAZON",
                    null,
                    UUID.randomUUID(),
                    mode == null ? "SELF_FULFILLED" : mode,
                    0L)));
  }

  // ------------------------------------------------------------------
  // 2.1 平台目录
  // ------------------------------------------------------------------

  @Test
  void platformCatalogIsGlobalAndCarriesCapabilityMatrixAndSites() {
    List<PlatformView> platforms = runA(service::platforms);
    assertThat(platforms).isNotEmpty();
    PlatformView amazon =
        platforms.stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow();
    assertThat(amazon.marketplaces()).isNotEmpty();
    // 2.1.1.2 能力矩阵必须真的带能力声明，供 09/07/10/11 禁用按钮
    assertThat(amazon.capabilities()).contains("fba_fulfillment");
    assertThat(service.supports(amazon.id(), "fba_fulfillment")).isTrue();
    assertThat(service.supports(amazon.id(), "creator_attribution")).isFalse();
  }

  @Test
  void platformRegistryIsReadableByEveryTenantWithoutTenantContext() {
    // 全局码表不带 tenant_id：两个租户看到同一份注册表，且都不是对方的私有数据
    assertThat(runA(service::platforms)).hasSameSizeAs(runB(service::platforms));
  }

  // ------------------------------------------------------------------
  // 2.1.2 平台枚举映射
  // ------------------------------------------------------------------

  /**
   * 平台默认是全局只读码表：随平台适配器发布，运行时角色写不了，所以只能用迁移账号落。
   *
   * <p>这正是"全局默认不可被租户改写"这条纪律的验证方式——如果哪天 V3 的 REVOKE 被去掉，
   * {@link #globalEnumDefaultsAreNotWritableByTheRuntimeRole} 会先红。
   */
  private UUID seedPlatformDefault(String enumKind, String external, String internal) {
    UUID platformId =
        sql.fetchOne("select id from platform where code='AMAZON'").get("id", UUID.class);
    UUID id = UUID.randomUUID();
    try (var connection =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var statement =
            connection.prepareStatement(
                "insert into platform_enum_mapping(id,platform_id,enum_kind,external_value,internal_value)"
                    + " values (?,?,?,?,?)")) {
      statement.setObject(1, id);
      statement.setObject(2, platformId);
      statement.setString(3, enumKind);
      statement.setString(4, external);
      statement.setString(5, internal);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("平台默认映射落库失败", e);
    }
    return id;
  }

  @Test
  void globalEnumDefaultsAreNotWritableByTheRuntimeRole() {
    UUID platformId = sql.fetchOne("select id from platform where code='AMAZON'").get("id", UUID.class);
    // 服务以 channel_app 运行；它必须改不了全局默认表，否则一个租户能污染所有租户的状态机
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_enum_mapping(id,platform_id,enum_kind,external_value,internal_value)"
                        + " values (?,?,?,?,?)",
                    UUID.randomUUID(),
                    platformId,
                    "ORDER_STATUS_" + UUID.randomUUID(),
                    "x",
                    "y"))
        // Spring 会把权限错误翻译成 BadSqlGrammarException，真正的 PG 报错在 cause 里
        .hasStackTraceContaining("permission denied for table platform_enum_mapping");
  }

  @Test
  void enumOverrideBeatsPlatformDefaultAndDeletionFallsBack() {
    String kind = "ORDER_STATUS_" + UUID.randomUUID();
    UUID platformId = sql.fetchOne("select id from platform where code='AMAZON'").get("id", UUID.class);
    seedPlatformDefault(kind, "Canceled", "CANCELLED");

    // 默认命中：没有覆盖时解析到平台默认
    assertThat(runA(() -> service.resolveEnum(a, platformId, kind, "Canceled"))).isEqualTo("CANCELLED");

    // 登记覆盖：生效值改为租户口径，来源标记 TENANT
    UUID override =
        as(
            actorA,
            "channel.registry.update",
            () -> service.putEnumOverride(actorA, "AMAZON", new EnumOverrideInput(kind, "Canceled", "USER_CANCELLED")));
    assertThat(runA(() -> service.resolveEnum(a, platformId, kind, "Canceled"))).isEqualTo("USER_CANCELLED");

    List<EnumMappingView> effective = runA(() -> service.enumMappings(a, "AMAZON", kind));
    assertThat(effective).hasSize(1);
    assertThat(effective.get(0).source()).isEqualTo("TENANT");
    assertThat(effective.get(0).internalValue()).isEqualTo("USER_CANCELLED");
    assertThat(effective.get(0).id()).isEqualTo(override);

    // 删除覆盖后回落到平台默认，而不是变成"未映射"
    as(
            actorA,
            "channel.registry.delete",
            () -> {
              service.deleteEnumOverride(actorA, "AMAZON", override);
              return null;
            });
    assertThat(runA(() -> service.resolveEnum(a, platformId, kind, "Canceled"))).isEqualTo("CANCELLED");
    assertThat(runA(() -> service.enumMappings(a, "AMAZON", kind)))
        .singleElement()
        .satisfies(m -> assertThat(m.source()).isEqualTo("PLATFORM"));
  }

  @Test
  void unmappedEnumStaysUnmappedInsteadOfFallingBack() {
    String kind = "RETURN_REASON_" + UUID.randomUUID();
    UUID platformId = sql.fetchOne("select id from platform where code='AMAZON'").get("id", UUID.class);
    // 2.1.2.1 铁律：没有映射就是 null，调用方必须显式报"未映射"，不许兜底
    assertThat(runA(() -> service.resolveEnum(a, platformId, kind, "UNKNOWN_CODE"))).isNull();
  }

  @Test
  void enumOverrideIsTenantScopedAndNeverLeaks() {
    String kind = "ORDER_STATUS_" + UUID.randomUUID();
    UUID platformId = sql.fetchOne("select id from platform where code='AMAZON'").get("id", UUID.class);
    as(
        actorA,
        "channel.registry.update",
        () -> service.putEnumOverride(actorA, "AMAZON", new EnumOverrideInput(kind, "Pending", "A_ONLY")));

    // 租户 B 看不到 A 的覆盖，解析也不会命中
    assertThat(runB(() -> service.enumMappings(b, "AMAZON", kind))).isEmpty();
    assertThat(runB(() -> service.resolveEnum(b, platformId, kind, "Pending"))).isNull();

    // B 不能删 A 的覆盖：删除带平台与租户双重条件
    UUID override =
        runA(
            () ->
                service.enumMappings(a, "AMAZON", kind).get(0).id());
    assertThatThrownBy(
            () ->
                as(
                    actorB,
                    "channel.registry.delete",
                    () -> {
                      service.deleteEnumOverride(actorB, "AMAZON", override);
                      return null;
                    }))
        .hasMessageContaining("不存在");
    assertThat(runA(() -> service.resolveEnum(a, platformId, kind, "Pending"))).isEqualTo("A_ONLY");
  }

  @Test
  void repeatingAnOverrideRewritesItInsteadOfFailing() {
    String kind = "ORDER_STATUS_" + UUID.randomUUID();
    UUID first =
        as(
            actorA,
            "channel.registry.update",
            () -> service.putEnumOverride(actorA, "AMAZON", new EnumOverrideInput(kind, "x", "FIRST")));
    UUID second =
        as(
            actorA,
            "channel.registry.update",
            () -> service.putEnumOverride(actorA, "AMAZON", new EnumOverrideInput(kind, "x", "SECOND")));
    assertThat(second).isEqualTo(first);
    assertThat(runA(() -> service.enumMappings(a, "AMAZON", kind)))
        .singleElement()
        .satisfies(m -> assertThat(m.internalValue()).isEqualTo("SECOND"));
  }

  // ------------------------------------------------------------------
  // 2.2 授权与连接
  // ------------------------------------------------------------------

  @Test
  void connectionNeverExposesCredentialSecrets() throws Exception {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID id = runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "Main")));
    runA(
        () -> {
          service.rotateCredential(
              actorA,
              id,
              new CredentialRotateInput(
                  "vault://starcon/" + UUID.randomUUID(), "orders inventory",
                  Instant.now(), Instant.now().plus(180, ChronoUnit.DAYS)));
          return null;
        });
    ConnectionView c =
        runConn(
            () ->
                service.connections("").stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow());
    assertThat(c.credential()).isNotNull();
    assertThat(c.credential().provider()).isEqualTo("SECRET_MANAGER");
    assertThat(c.credential().grantedScopes()).isEqualTo("orders inventory");
    // 明文令牌既不在模型里也不可能在响应里出现
    String json = http.perform(get("/channel/connections").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
    assertThat(json).doesNotContain("access_token").doesNotContain("refresh_token");
  }

  @Test
  void rotationComputesExpiryStatusSoRemindersCanFire() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID id = runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    runA(
        () -> {
          service.rotateCredential(
              actorA,
              id,
              new CredentialRotateInput(
                  "vault://starcon/" + UUID.randomUUID(),
                  "orders",
                  Instant.now(),
                  Instant.now().plus(2, ChronoUnit.DAYS)));
          return null;
        });
    ConnectionView c =
        runConn(
            () ->
                service.connections("").stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow());
    assertThat(c.status()).isEqualTo("EXPIRING");
    // 2.2.2.2 临期连接必须进入提醒队列；严重度按剩余天数分级，2 天内为 CRITICAL
    ReauthorizationReminderView reminder =
        runConn(() -> service.reauthorizationReminders(7)).stream()
            .filter(r -> r.connectionId().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(reminder.daysUntilExpiry()).isLessThanOrEqualTo(2);
    assertThat(reminder.severity()).isEqualTo("CRITICAL");
  }

  @Test
  void reminderSeverityDegradesAsExpiryApproaches() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID id = runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    runConn(
        () -> {
          service.rotateCredential(
              actorA,
              id,
              new CredentialRotateInput(
                  "vault://starcon/" + UUID.randomUUID(),
                  "orders",
                  Instant.now(),
                  Instant.now().plus(6, ChronoUnit.DAYS)));
          return null;
        });
    // 还剩 6 天：提示级别而非紧急
    assertThat(runConn(() -> service.reauthorizationReminders(7)).stream()
            .filter(r -> r.connectionId().equals(id))
            .findFirst()
            .orElseThrow()
            .severity())
        .isEqualTo("NOTICE");
  }

  @Test
  void healthExplainsWhySyncCannotProceedAndWhatToDo() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID id = runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    runA(
        () -> {
          service.rotateCredential(
              actorA,
              id,
              new CredentialRotateInput(
                  "vault://starcon/" + UUID.randomUUID(), "orders", Instant.now(), Instant.now().minus(1, ChronoUnit.DAYS)));
          return null;
        });
    ConnectionHealthView health = runConn(() -> service.health(actorA, id));
    assertThat(health.canSync()).isFalse();
    assertThat(health.diagnosis()).contains("过期");
    assertThat(health.recommendation()).isNotBlank();
  }

  @Test
  void revokeStopsSyncButKeepsHistoryQueryable() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID id = runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    ConnectionView before =
        runConn(() -> service.connections("").stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow());
    runConn(
        () -> {
          service.revokeConnection(actorA, id, before.version());
          return null;
        });
    ConnectionView after =
        runConn(() -> service.connections("").stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow());
    assertThat(after.status()).isEqualTo("REVOKED");
    assertThat(after.syncPaused()).isTrue();
    // 断开不删除历史：记录仍在列表里
    assertThat(after.authorizedAccount()).isEqualTo(account);
  }

  // ------------------------------------------------------------------
  // 2.3 店铺与站点
  // ------------------------------------------------------------------

  @Test
  void shopLifecycleCarriesOperatingModeAndEnabledSites() {
    String code = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = shop(a, actorA, code, "FULL_CONSIGNMENT");
    String mk = runA(() -> service.platforms().stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow()
        .marketplaces().get(0).code());
    runA(
        () -> {
          service.enableMarketplace(actorA, id, new ShopMarketplaceInput(mk, "ACTIVE"));
          return null;
        });
    ShopView v = runA(() -> service.shop(a, id));
    // 2.3.2.1 经营模式是 09/12/13 的成本口径第一分叉点，必须原样保留
    assertThat(v.operatingMode()).isEqualTo("FULL_CONSIGNMENT");
    assertThat(v.marketplaces()).hasSize(1);
    assertThat(v.marketplaceCount()).isEqualTo(1);
  }

  @Test
  void shopCannotBeDeletedWhileSitesAreStillEnabled() {
    String code = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = shop(a, actorA, code, null);
    String mk = runA(() -> service.platforms().stream().filter(p -> p.code().equals("AMAZON")).findFirst().orElseThrow()
        .marketplaces().get(0).code());
    runA(
        () -> {
          service.enableMarketplace(actorA, id, new ShopMarketplaceInput(mk, "ACTIVE"));
          return null;
        });
    ShopView v = runA(() -> service.shop(a, id));
    assertThatThrownBy(() -> runA(() -> { service.deleteShop(actorA, id, v.version()); return null; }))
        .hasMessageContaining("站点");
    // 停用后即可删除
    UUID mkId = runA(() -> service.shop(a, id).marketplaces().get(0).marketplaceId());
    runA(
        () -> {
          service.disableMarketplace(actorA, id, mkId);
          return null;
        });
    ShopView after = runA(() -> service.shop(a, id));
    runA(
        () -> {
          service.deleteShop(actorA, id, after.version());
          return null;
        });
    assertThatThrownBy(() -> runA(() -> service.shop(a, id))).hasMessageContaining("不存在");
  }

  @Test
  void ownershipIsImmutableUnlessTransferredExplicitly() {
    String code = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = shop(a, actorA, code, null);
    ShopView v = runA(() -> service.shop(a, id));
    // 直接改 owner_id 会被触发器拒绝
    assertThatThrownBy(
            () ->
                runA(
                    () -> {
                      sql.execute("update shop set owner_id=? where id=?", UUID.randomUUID(), id);
                      return null;
                    }))
        .hasMessageContaining("transfer");
    // 显式转让端点允许
    UUID newOwner = UUID.randomUUID();
    runA(
        () -> {
          service.transferOwner(actorA, id, new ShopOwnerTransferInput(newOwner, UUID.randomUUID()));
          return null;
        });
    assertThat(runA(() -> service.shop(a, id)).ownerId()).isEqualTo(newOwner);
  }

  @Test
  void staleVersionIsRejectedSoConcurrentEditsCannotSilentlyOverwrite() {
    String code = "SHP-" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = shop(a, actorA, code, null);
    ShopView v = runA(() -> service.shop(a, id));
    ShopInput edit =
        new ShopInput(
            v.code(), v.name(), v.platformCode(), v.connectionId(), v.legalEntityId(), "OVERSEAS_WAREHOUSE", v.version());
    runA(
        () -> {
          service.updateShop(actorA, id, edit);
          return null;
        });
    // 拿着旧版本号再提交一次必须失败
    assertThatThrownBy(
            () ->
                runA(
                    () -> {
                      service.updateShop(actorA, id, edit);
                      return null;
                    }))
        .hasMessageContaining("他人修改");
  }

  @Test
  void shopRequiresAConnectionBelongingToTheSamePlatformAndTenant() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    UUID conn =
        runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    // 平台不匹配的连接不能挂到另一个平台的店铺上
    assertThatThrownBy(
            () ->
                runA(
                    () ->
                        service.createShop(
                            actorA,
                            new ShopInput(
                                "SHP-" + UUID.randomUUID().toString().substring(0, 8),
                                "Bad",
                                "SHOPIFY",
                                conn,
                                UUID.randomUUID(),
                                "SELF_FULFILLED",
                                0L))))
        .hasMessageContaining("连接");
  }

  // ------------------------------------------------------------------
  // 多租户：AGENTS.md 要求的三条断言
  // ------------------------------------------------------------------

  @Test
  void tenantsCannotSeeEachOthersConnectionsOrShops() {
    String account = "seller-" + UUID.randomUUID() + "@example.com";
    runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    assertThat(runConn(() -> service.connections(account))).hasSize(1);
    // 租户 B 用同样的查询条件必须什么都看不到
    assertThat(as(actorB, "channel.connection.read", () -> service.connections(account))).isEmpty();

    shop(a, actorA, "SHP-" + UUID.randomUUID().toString().substring(0, 8), null);
    assertThat(runA(() -> service.shops(""))).isNotEmpty();
    long tenantBShops = runB(() -> service.shops("")).size();
    long tenantAShops = runA(() -> service.shops("")).size();
    assertThat(tenantBShops).isLessThan(tenantAShops);
  }

  @Test
  void forgedTenantHeaderCannotReachAnotherTenantsRows() throws Exception {
    String account = "forged-" + UUID.randomUUID() + "@example.com";
    runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    // 用 A 的令牌 + 伪造的租户头：B 的数据仍然不可见，因为租户来自已认证成员关系
    http.perform(get("/channel/connections").param("q", account)
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    // 换成 B 的令牌，即使带上 A 的租户头也拿不到 A 的数据
    http.perform(get("/channel/connections").param("q", account)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void connectionReturnedToPoolDoesNotLeakThePreviousTenant() {
    String account = "pool-" + UUID.randomUUID() + "@example.com";
    runConn(() -> service.createConnection(actorA, new ConnectionInput("AMAZON", account, "")));
    // 借出 A 的连接读一次，再借出 B 的连接读一次：池大小为 1，因此是同一条物理会话
    assertThat(runConn(() -> service.connections(account))).hasSize(1);
    assertThat(as(actorB, "channel.connection.read", () -> service.connections(account))).isEmpty();
    // 再借一次 A：必须仍然只看到 A 自己的数据，说明 app.current_tenant 被重新绑定
    assertThat(runConn(() -> service.connections(account))).hasSize(1);
  }

  @Test
  void unauthenticatedRequestIsRejected() throws Exception {
    http.perform(get("/channel/shops")).andExpect(status().isUnauthorized());
  }

  @Test
  void readOnlyRoleCannotWriteShops() throws Exception {
    String code = "RO-" + UUID.randomUUID().toString().substring(0, 8);
    actors.put("Bearer limited", actor(user, a, null, List.of(grant("ALL", shopReadOnly))));
    // 有读权限：列表可读
    http.perform(get("/channel/shops").header("Authorization", "Bearer limited")).andExpect(status().isOk());
    // 缺 channel.shop.create：写操作必须被拒，而不是写进去再报错
    http.perform(post("/channel/shops")
            .header("Authorization", "Bearer limited")
            .contentType("application/json")
            .content(
                """
                {"code":"%s","name":"n","platformCode":"AMAZON","operatingMode":"SELF_FULFILLED","version":0}"""
                    .formatted(code)))
        .andExpect(status().isForbidden());
    // 被拒的写入没有留下任何数据
    assertThat(runA(() -> service.shops(code))).isEmpty();
  }

  @Test
  void roleWithoutAnyChannelPermissionIsRejected() throws Exception {
    actors.put("Bearer noperm", actor(user, a, null, List.of(grant("ALL", List.of()))));
    http.perform(get("/channel/shops").header("Authorization", "Bearer noperm"))
        .andExpect(status().isForbidden());
  }

  @Test
  void httpEndpointsExposeTheShopSurface() throws Exception {
    http.perform(get("/channel/platforms").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists())
        .andExpect(jsonPath("$[0].marketplaces").isArray());
    http.perform(get("/channel/shops").header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());
  }
}
