package com.starcon.channel;

import static com.starcon.channel.EprRegistrationModels.*;
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
 * 16.3.2.2 EPR/包装法 的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十二件事：
 *
 * <ol>
 *   <li><b>未注册 ⇒ 真的阻断刊登</b>（不是页面上的一句提醒）；</li>
 *   <li><b>不适用 ⇒ 真的放行</b>（美国不要求 EPR ⇒ 不拦）；</li>
 *   <li><b>一个制度一个号</b>：注册了 LUCID 不等于注册了 WEEE / 电池法；</li>
 *   <li><b>「判不了」不许默认放行</b>：没记有效期 / 没有生效版本 / 已停用 ⇒ 仍然阻断；</li>
 *   <li><b>不适用声明能把阻断项变成放行</b>（理由必填）；</li>
 *   <li><b>制度与品类的值域钉死在 DB 层</b>（自由文本被触发器拒）；</li>
 *   <li><b>改登记 = 新版本</b>，历史版本不可回改（DB 拒）；</li>
 *   <li><b>不许回填</b>（生效时刻早于当下 ⇒ 422）；</li>
 *   <li><b>DB 层真的拦得住刊登</b>（listing_publish_job_target 进 SUBMITTED 被拒）；</li>
 *   <li><b>费用按币种分组</b>（EUR + USD 不相加），金额禁浮点；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）；</li>
 *   <li><b>「这个国家这个品类有没有覆盖」能反查出来</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EprRegistrationIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired EprRegistrationService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以租户身份执行 SQL（超级用户连接，只用于播种）。 */
  void execAsTenant(UUID tenantId, String statement) {
    exec(pg.getUsername(), pg.getPassword(), tenantId, statement);
  }

  /** 🔴 以**应用角色 channel_app** 执行 SQL（超级用户绕过 RLS，会让断言永远「通过」）。 */
  void execAsApp(UUID tenantId, String statement) {
    exec("channel_app", "test-only", tenantId, statement);
  }

  private void exec(String dbUser, String dbPassword, UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), dbUser, dbPassword);
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

  /** 以应用角色直查一行计数（RLS 真实生效）。 */
  long countAsApp(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
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

  /** 站点 ID（V1 用 md5 派生，这里直接查，不写死）。 */
  UUID marketplace(String code) {
    String raw =
        scalar("select id from marketplace where platform_id='" + AMAZON + "' and code='" + code + "'");
    assertThat(raw).as("站点 " + code + " 必须存在于 V1 的站点目录里").isNotNull();
    return UUID.fromString(raw);
  }

  /** 造一家店，并把它挂到指定的站点上（= 它在那些国家卖）。 */
  UUID seedShop(String code, String marketplaceCode) {
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
    if (marketplaceCode != null) {
      execAsTenant(
          tenant,
          "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
              + tenant
              + "','"
              + id
              + "','"
              + marketplace(marketplaceCode)
              + "','ACTIVE')");
    }
    return id;
  }

  /** 登记一条 EPR（默认：制度级注册号 + 长期有效 + 年度申报）。 */
  EprRegistrationView register(
      String regime,
      String number,
      List<String> countries,
      List<String> categories,
      String validityKind,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil) {
    return as(
        "channel.operation.create",
        () ->
            service.create(
                new EprRegistrationInput(
                    regime,
                    number,
                    "ACTIVE",
                    validityKind,
                    validFrom == null ? null : validFrom.toString(),
                    validUntil == null ? null : validUntil.toString(),
                    "ANNUAL",
                    1,
                    3,
                    null,
                    countries,
                    categories,
                    "集成测试登记")));
  }

  EprPublishGate gate(String country, String category) {
    return as(
        "channel.operation.read",
        () -> service.publishGate(new EprPublishGateRequest(country, category, null)));
  }

  // ==================================================================
  // 1. 未注册 ⇒ 真的阻断
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 未注册的 EPR 国家 ⇒ 刊登闸门真的阻断（不是一句提醒）")
  void unregisteredCountryBlocksPublish() {
    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).as("德国要求 LUCID，没注册就必须阻断").isFalse();
    assertThat(result.conclusion()).isEqualTo("BLOCKED");
    assertThat(result.reasons())
        .extracting(EprGateReason::regimeCode)
        .contains("LUCID", "WEEE", "BATTERY");
    assertThat(result.reasons())
        .filteredOn(reason -> "LUCID".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("NOT_REGISTERED");
    assertThat(result.reasons())
        .allSatisfy(
            reason -> {
              assertThat(reason.blocking()).isTrue();
              assertThat(reason.message()).isNotBlank();
            });
    // 国家维度与品类维度都算了一遍，DB 层强制的是国家维度
    assertThat(result.countryScope().allowed()).isFalse();
    assertThat(result.countryScope().conclusion()).isEqualTo("BLOCKED");
  }

  @Test
  @DisplayName("16.3.2.2 🔴 不适用 ≠ 未注册：美国不要求 EPR ⇒ 放行，不该拦的不拦")
  void countryWithoutEprIsNotApplicable() {
    EprPublishGate result = gate("US", null);

    assertThat(result.allowed()).as("美国不在 EPR 制度参考表里 ⇒ NOT_APPLICABLE ⇒ 放行").isTrue();
    assertThat(result.conclusion()).isEqualTo("ALLOWED");
    assertThat(result.reasons()).isEmpty();
    assertThat(result.note()).contains("不要求任何 EPR 制度");
  }

  // ==================================================================
  // 2. 一个制度一个号
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 一个制度一个号：注册了 LUCID 不等于注册了 WEEE / 电池法")
  void oneRegimePerNumber() {
    register(
        "LUCID",
        "DE-LUCID-1",
        List.of("DE"),
        List.of("PACKAGING"),
        "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC),
        null);

    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasons())
        .filteredOn(reason -> "LUCID".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("OK");
    assertThat(result.reasons())
        .filteredOn(reason -> "WEEE".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("NOT_REGISTERED");
    assertThat(result.reasons())
        .filteredOn(reason -> "BATTERY".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("NOT_REGISTERED");
  }

  @Test
  @DisplayName("16.3.2.2 不适用声明（理由必填）⇒ 阻断项变成放行")
  void exemptionTurnsBlockingIntoNotApplicable() {
    register(
        "LUCID",
        "DE-LUCID-1",
        List.of("DE"),
        List.of("PACKAGING"),
        "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC),
        null);
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput(
                    "DE", "WEEE", "我们只卖包装类商品，不销售电子电器", null, "集成测试")));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput(
                    "DE", "BATTERY", "我们只卖包装类商品，不销售电池", null, "集成测试")));

    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).as("三条义务都覆盖了 ⇒ 放行").isTrue();
    assertThat(result.conclusion()).isEqualTo("ALLOWED");
    assertThat(result.reasons())
        .extracting(EprGateReason::reasonCode)
        .containsExactlyInAnyOrder("OK", "EXEMPTED", "EXEMPTED");
  }

  @Test
  @DisplayName("16.3.2.2 不适用声明的理由必填（没有理由就是漏登记的遮羞布）")
  void exemptionRequiresReason() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.createExemption(
                                new EprExemptionInput("DE", "WEEE", "   ", null, "")))))
        .as("空理由必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 3. 判不了 ⇒ 不许默认放行
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 没记有效期 ⇒ 判不了 ⇒ 仍然阻断（不许默认放行）")
  void unknownValidityBlocks() {
    register("LUCID", "DE-LUCID-1", List.of("DE"), List.of("PACKAGING"), "UNKNOWN", null, null);
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "WEEE", "不卖电子电器", null, "")));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "BATTERY", "不卖电池", null, "")));

    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.conclusion()).isEqualTo("INDETERMINATE");
    assertThat(result.reasons())
        .filteredOn(reason -> "LUCID".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("VALIDITY_UNKNOWN");
  }

  @Test
  @DisplayName("16.3.2.2 过期登记 ⇒ EXPIRED（过期与没注册是两回事）")
  void expiredRegistrationBlocks() {
    register(
        "LUCID",
        "DE-LUCID-1",
        List.of("DE"),
        List.of("PACKAGING"),
        "FIXED",
        OffsetDateTime.now(ZoneOffset.UTC).minusYears(2),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "WEEE", "不卖电子电器", null, "")));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "BATTERY", "不卖电池", null, "")));

    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.conclusion()).isEqualTo("BLOCKED");
    assertThat(result.reasons())
        .filteredOn(reason -> "LUCID".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("EXPIRED");
  }

  @Test
  @DisplayName("16.3.2.2 停用的登记不作为覆盖依据 ⇒ DISABLED")
  void disabledRegistrationBlocks() {
    EprRegistrationView created =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);
    as(
        "channel.operation.update",
        () ->
            service.update(
                created.id(),
                new EprRegistrationInput(
                    "LUCID",
                    "DE-LUCID-1",
                    "DISABLED",
                    "OPEN_ENDED",
                    OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    null,
                    "ANNUAL",
                    1,
                    3,
                    null,
                    List.of("DE"),
                    List.of("PACKAGING"),
                    "停用")));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "WEEE", "不卖电子电器", null, "")));
    as(
        "channel.operation.create",
        () ->
            service.createExemption(
                new EprExemptionInput("DE", "BATTERY", "不卖电池", null, "")));

    EprPublishGate result = gate("DE", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasons())
        .filteredOn(reason -> "LUCID".equals(reason.regimeCode()))
        .extracting(EprGateReason::reasonCode)
        .containsExactly("DISABLED");
  }

  // ==================================================================
  // 4. 值域钉死在 DB 层
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 自由文本制度码在 DB 层被拒（触发器钉死，不留自由文本）")
  void freeTextRegimeIsRejectedByDb() {
    UUID id = UUID.randomUUID();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into epr_registration (id,tenant_id,registration_key,version_no,"
                            + "regime_code,registration_no,status,validity_kind,filing_cycle,"
                            + "effective_from) values ('"
                            + id
                            + "','"
                            + tenant
                            + "','"
                            + id
                            + "',1,'SOMETHING_ELSE','X-1','ACTIVE','OPEN_ENDED','ANNUAL',now())")))
        .as("不在制度码表里的制度码必须被 DB 拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.3.2.2 🔴 自由文本品类码在 DB 层被拒")
  void freeTextCategoryIsRejectedByDb() {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into epr_registration (id,tenant_id,registration_key,version_no,regime_code,"
            + "registration_no,status,validity_kind,filing_cycle,effective_from) values ('"
            + id
            + "','"
            + tenant
            + "','"
            + id
            + "',1,'LUCID','X-1','ACTIVE','UNKNOWN','ANNUAL',now())");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into epr_registration_category (tenant_id,registration_id,category_code)"
                            + " values ('"
                            + tenant
                            + "','"
                            + id
                            + "','NOT_A_CATEGORY')")))
        .as("不在适用品类码表里的品类码必须被 DB 拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.3.2.2 自由文本制度码在服务层也被拒（人话报错，不是裸 SQL 错误）")
  void freeTextRegimeIsRejectedByApi() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                new EprRegistrationInput(
                                    "SOMETHING_ELSE",
                                    "X-1",
                                    "ACTIVE",
                                    "OPEN_ENDED",
                                    null,
                                    null,
                                    "ANNUAL",
                                    1,
                                    3,
                                    null,
                                    List.of("DE"),
                                    List.of("PACKAGING"),
                                    "")))))
        .isNotNull();
  }

  // ==================================================================
  // 5. 版本化
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 改登记 = 新版本：旧版本被关掉、历史留得住")
  void updateCreatesNewVersionAndKeepsHistory() {
    EprRegistrationView created =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);

    EprRegistrationView updated =
        as(
            "channel.operation.update",
            () ->
                service.update(
                    created.id(),
                    new EprRegistrationInput(
                        "LUCID",
                        "DE-LUCID-2",
                        "ACTIVE",
                        "OPEN_ENDED",
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        null,
                        "ANNUAL",
                        1,
                        3,
                        null,
                        List.of("DE"),
                        List.of("PACKAGING"),
                        "订正注册号")));

    assertThat(updated.id()).isNotEqualTo(created.id());
    assertThat(updated.registrationKey()).isEqualTo(created.registrationKey());
    assertThat(updated.versionNo()).isEqualTo(2);
    assertThat(updated.registrationNo()).isEqualTo("DE-LUCID-2");

    EprVersionList versions = as("channel.operation.read", () -> service.versions(created.id()));
    assertThat(versions.items()).hasSize(2);
    assertThat(versions.items().get(0).versionNo()).isEqualTo(2);
    assertThat(versions.items().get(0).effectiveTo()).isEmpty();
    assertThat(versions.items().get(1).versionNo()).isEqualTo(1);
    assertThat(versions.items().get(1).effectiveTo())
        .as("旧版本必须被关掉（否则「当时那版」说不清）")
        .isNotEmpty();
    assertThat(versions.items().get(1).registrationNo()).isEqualTo("DE-LUCID-1");
  }

  @Test
  @DisplayName("16.3.2.2 🔴 已关闭的版本不许回改事实（DB 触发器直接拒）")
  void closedVersionFactsCannotBeRewritten() {
    EprRegistrationView created =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);
    as(
        "channel.operation.update",
        () ->
            service.update(
                created.id(),
                new EprRegistrationInput(
                    "LUCID",
                    "DE-LUCID-2",
                    "ACTIVE",
                    "OPEN_ENDED",
                    OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    null,
                    "ANNUAL",
                    1,
                    3,
                    null,
                    List.of("DE"),
                    List.of("PACKAGING"),
                    "订正")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update epr_registration set registration_no = 'TAMPERED'"
                            + " where tenant_id = '"
                            + tenant
                            + "' and id = '"
                            + created.id()
                            + "'")))
        .as("回改已关闭版本的事实在 DB 层必须被拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.3.2.2 🔴 不许回填：生效时刻早于当下 ⇒ 422")
  void backdatingIsRejected() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.create(
                                new EprRegistrationInput(
                                    "LUCID",
                                    "DE-LUCID-1",
                                    "ACTIVE",
                                    "OPEN_ENDED",
                                    null,
                                    null,
                                    "ANNUAL",
                                    1,
                                    3,
                                    OffsetDateTime.now(ZoneOffset.UTC).minusYears(1).toString(),
                                    List.of("DE"),
                                    List.of("PACKAGING"),
                                    "")))))
        .as("回填必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 6. 🔴 DB 层真的拦得住刊登
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 DB 层真的拦得住刊登：listing_publish_job_target 进 SUBMITTED 被拒")
  void publishTriggerBlocksUnregisteredCountry() {
    UUID shopId = seedShop("EPR-DE-1", "DE");
    UUID jobId = seedPublishJob(shopId);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        tenant,
                        "insert into listing_publish_job_target (id,tenant_id,job_id,position,"
                            + "platform_id,shop_id,marketplace_id,locale,category_path,state,"
                            + "content_source,content_source_id,content_snapshot,dispatched_at) values ('"
                            + UUID.randomUUID()
                            + "','"
                            + tenant
                            + "','"
                            + jobId
                            + "',0,'"
                            + AMAZON
                            + "','"
                            + shopId
                            + "','"
                            + marketplace("DE")
                            + "','de-DE','Elektronik','SUBMITTED','EDITOR_SUBMISSION','"
                            + UUID.randomUUID()
                            + "','{}'::jsonb,now())")))
        .as("德国没 EPR 登记 ⇒ 数据库必须直接拒掉这次刊登")
        .isNotNull();
  }

  @Test
  @DisplayName("16.3.2.2 DB 层不误伤：美国不在 EPR 制度范围 ⇒ 刊登照常")
  void publishTriggerAllowsNonEprCountry() {
    UUID shopId = seedShop("EPR-US-1", "US");
    UUID jobId = seedPublishJob(shopId);

    execAsApp(
        tenant,
        "insert into listing_publish_job_target (id,tenant_id,job_id,position,"
            + "platform_id,shop_id,marketplace_id,locale,category_path,state,"
            + "content_source,content_source_id,content_snapshot,dispatched_at) values ('"
            + UUID.randomUUID()
            + "','"
            + tenant
            + "','"
            + jobId
            + "',0,'"
            + AMAZON
            + "','"
            + shopId
            + "','"
            + marketplace("US")
            + "','en-US','Electronics','SUBMITTED','EDITOR_SUBMISSION','"
            + UUID.randomUUID()
            + "','{}'::jsonb,now())");

    assertThat(
            countAsApp(
                tenant,
                "select count(*) from listing_publish_job_target where tenant_id = '"
                    + tenant
                    + "' and state = 'SUBMITTED'"))
        .as("美国刊登必须真的写进去了")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.3.2.2 DB 层拦的是「真发平台」那一刻，不是建草稿")
  void publishTriggerOnlyBlocksOnSubmit() {
    UUID shopId = seedShop("EPR-DE-2", "DE");
    UUID jobId = seedPublishJob(shopId);

    // DRAFT（草稿）必须能建 —— 阻断只发生在真发平台那一刻
    execAsApp(
        tenant,
        "insert into listing_publish_job_target (id,tenant_id,job_id,position,"
            + "platform_id,shop_id,marketplace_id,locale,category_path,state,"
            + "content_source,content_source_id,content_snapshot) values ('"
            + UUID.randomUUID()
            + "','"
            + tenant
            + "','"
            + jobId
            + "',0,'"
            + AMAZON
            + "','"
            + shopId
            + "','"
            + marketplace("DE")
            + "','de-DE','Elektronik','DRAFT','EDITOR_SUBMISSION','"
            + UUID.randomUUID()
            + "','{}'::jsonb)");

    assertThat(
            countAsApp(
                tenant,
                "select count(*) from listing_publish_job_target where tenant_id = '"
                    + tenant
                    + "' and state = 'DRAFT'"))
        .isEqualTo(1);
  }

  /** 造一个刊登作业（4.3.1.1 一键刊登的作业行）。 */
  UUID seedPublishJob(UUID shopId) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into listing_publish_job (id,tenant_id,source_sku_id,content_source,price_source,"
            + "stock_source,idempotency_key,state,content_snapshot,primary_shop_id) values ('"
            + id
            + "','"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','EDITOR_SUBMISSION','MANUAL','KEEP_UNSET','epr-test-"
            + id
            + "','DRAFT','{}'::jsonb,'"
            + shopId
            + "')");
    return id;
  }

  // ==================================================================
  // 7. 费用
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 费用按币种分组：EUR + USD 不相加")
  void feeSummaryGroupsByCurrency() {
    EprRegistrationView registration =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);

    as(
        "channel.operation.create",
        () ->
            service.createFee(
                new EprFeeInput(
                    registration.registrationKey(),
                    "REGISTRATION_FEE",
                    "100.50",
                    "EUR",
                    "ANNUAL",
                    null,
                    null,
                    "PAID",
                    "SELLER",
                    "第一笔")));
    as(
        "channel.operation.create",
        () ->
            service.createFee(
                new EprFeeInput(
                    registration.registrationKey(),
                    "FILING_FEE",
                    "20.25",
                    "EUR",
                    "ANNUAL",
                    null,
                    null,
                    "PAID",
                    "SELLER",
                    "第二笔")));
    as(
        "channel.operation.create",
        () ->
            service.createFee(
                new EprFeeInput(
                    registration.registrationKey(),
                    "REGISTRATION_FEE",
                    "50.00",
                    "USD",
                    "ANNUAL",
                    null,
                    null,
                    "RECORDED",
                    "PLATFORM",
                    "第三笔")));

    EprFeeSummary summary = as("channel.operation.read", () -> service.feeSummary(null, null, null));

    assertThat(summary.totalRecords()).isEqualTo(3);
    assertThat(summary.currencies()).containsExactly("EUR", "USD");
    assertThat(summary.totals()).hasSize(2);
    // 🔴 金额是**十进制字符串**（列是 numeric(19,4) ⇒ 读回来就是 4 位小数，不经浮点）
    assertThat(summary.totals().get(0).currency()).isEqualTo("EUR");
    assertThat(summary.totals().get(0).total()).isEqualTo("120.7500");
    assertThat(summary.totals().get(0).count()).isEqualTo(2);
    assertThat(summary.totals().get(1).currency()).isEqualTo("USD");
    assertThat(summary.totals().get(1).total()).isEqualTo("50.0000");
    assertThat(summary.note()).contains("不同币种绝不直接相加");
  }

  @Test
  @DisplayName("16.3.2.2 🔴 金额禁浮点：负数 / 科学计数法 / 空值都被拒")
  void feeAmountRejectsNonDecimal() {
    EprRegistrationView registration =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);

    for (String bad : new String[] {"-1", "1e5", "", "abc"}) {
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () ->
                      as(
                          "channel.operation.create",
                          () ->
                              service.createFee(
                                  new EprFeeInput(
                                      registration.registrationKey(),
                                      "FILING_FEE",
                                      bad,
                                      "EUR",
                                      "ANNUAL",
                                      null,
                                      null,
                                      "RECORDED",
                                      "SELLER",
                                      "")))))
          .as("金额 " + bad + " 必须被拒")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("16.3.2.2 费用必须带币种")
  void feeRequiresCurrency() {
    EprRegistrationView registration =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "OPEN_ENDED",
            OffsetDateTime.now(ZoneOffset.UTC),
            null);
    // 不是三个大写字母的币种一律拒（列是 char(3) + CHECK ^[A-Z]{3}$）
    for (String bad : new String[] {"EURO", "E1R", "  "}) {
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () ->
                      as(
                          "channel.operation.create",
                          () ->
                              service.createFee(
                                  new EprFeeInput(
                                      registration.registrationKey(),
                                      "FILING_FEE",
                                      "10.00",
                                      bad,
                                      "ANNUAL",
                                      null,
                                      null,
                                      "RECORDED",
                                      "SELLER",
                                      "")))))
          .as("币种 " + bad + " 必须被拒")
          .isNotNull();
    }
  }

  // ==================================================================
  // 8. 覆盖反查 + 申报期
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 「这个国家这个品类有没有覆盖」能反查出来")
  void coverageReverseLookupAnswersCountryAndCategory() {
    register(
        "LUCID",
        "DE-LUCID-1",
        List.of("DE"),
        List.of("PACKAGING"),
        "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC),
        null);

    EprCoveragePage packaging =
        as(
            "channel.operation.read",
            () -> service.coverage("DE", null, "PACKAGING", 0, 50));

    assertThat(packaging.items())
        .extracting(EprCoverageRow::regimeCode)
        .contains("LUCID");
    assertThat(packaging.items())
        .filteredOn(row -> "LUCID".equals(row.regimeCode()))
        .extracting(EprCoverageRow::reasonCode)
        .containsExactly("OK");
    // 电子电器品类下，LUCID 不适用（德国电子电器适用的是 WEEE）
    assertThat(packaging.items())
        .filteredOn(row -> "LUCID".equals(row.regimeCode()))
        .extracting(EprCoverageRow::categoryCodes)
        .allSatisfy(codes -> assertThat(codes).containsExactly("PACKAGING"));

    EprCoveragePage electronics =
        as(
            "channel.operation.read",
            () -> service.coverage("DE", null, "ELECTRONICS", 0, 50));
    assertThat(electronics.items())
        .filteredOn(row -> "LUCID".equals(row.regimeCode()))
        .singleElement()
        .satisfies(
            row -> {
              // 🔴 电子电器品类下 LUCID 在德国**不适用**（德国电子电器适用的是 WEEE）
              //    ⇒ 这条仍会出现，但必须标成「不适用」，不能标成「未注册」——
              //    把 NOT_APPLICABLE 当成未注册，会把不该拦的全拦了。
              assertThat(row.applicable()).isFalse();
              assertThat(row.reasonCode()).isEqualTo("NOT_APPLICABLE");
              assertThat(row.covered()).isFalse();
            });
    assertThat(electronics.items())
        .filteredOn(row -> "WEEE".equals(row.regimeCode()))
        .extracting(EprCoverageRow::reasonCode)
        .containsExactly("NOT_REGISTERED");
  }

  @Test
  @DisplayName("16.3.2.2 申报期是算出来的：当前是否在申报期内")
  void filingStatusIsComputed() {
    register(
        "LUCID",
        "DE-LUCID-1",
        List.of("DE"),
        List.of("PACKAGING"),
        "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC),
        null);

    EprFilingPage filings = as("channel.operation.read", () -> service.filings(null, null, 0, 50));

    assertThat(filings.items()).hasSize(1);
    EprFilingRow row = filings.items().get(0);
    assertThat(row.filingCycle()).isEqualTo("ANNUAL");
    assertThat(row.filingStartMonth()).isEqualTo(1);
    assertThat(row.filingEndMonth()).isEqualTo(3);
    // 起止月份是 1..3 ⇒ 申报期在 Q1；今天不在 Q1 ⇒ OUT_OF_PERIOD
    assertThat(row.filingStatus()).isEqualTo("OUT_OF_PERIOD");
    assertThat(row.daysToNextFiling()).isNotNull();
    assertThat(filings.note()).contains("判不了");
  }

  @Test
  @DisplayName("16.3.2.2 没记申报月份 ⇒ 判不了（不是「不在申报期内」）")
  void filingWithoutMonthsIsUnknown() {
    as(
        "channel.operation.create",
        () ->
            service.create(
                new EprRegistrationInput(
                    "LUCID",
                    "DE-LUCID-1",
                    "ACTIVE",
                    "UNKNOWN",
                    null,
                    null,
                    "ANNUAL",
                    null,
                    null,
                    null,
                    List.of("DE"),
                    List.of("PACKAGING"),
                    "")));

    EprFilingPage filings = as("channel.operation.read", () -> service.filings(null, null, 0, 50));
    assertThat(filings.items().get(0).filingStatus()).isEqualTo("UNKNOWN");
  }

  // ==================================================================
  // 9. timestamptz 往返
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 🔴 timestamptz 往返断言写回的值（不只断言没抛异常）")
  void timestamptzRoundTripKeepsInstant() {
    OffsetDateTime validFrom = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    OffsetDateTime validUntil = OffsetDateTime.of(2027, 2, 28, 23, 59, 59, 0, ZoneOffset.ofHours(8));

    EprRegistrationView created =
        register(
            "LUCID",
            "DE-LUCID-1",
            List.of("DE"),
            List.of("PACKAGING"),
            "FIXED",
            validFrom,
            validUntil);

    EprRegistrationView read = as("channel.operation.read", () -> service.registration(created.id()));

    assertThat(read.validFrom()).isEqualTo(validFrom.toString());
    assertThat(read.validUntil()).isEqualTo(validUntil.toString());
    // 写回 DB 的是同一个**时刻**（+08:00 与 UTC 表示的是同一瞬间，不是差 8 小时的两天）
    assertThat(
            scalar(
                "select extract(epoch from valid_from)::bigint from epr_registration where id = '"
                    + created.id()
                    + "'"))
        .as("valid_from 的 epoch 必须与写入时刻一致")
        .isEqualTo(String.valueOf(validFrom.toEpochSecond()));
    assertThat(
            scalar(
                "select extract(epoch from valid_until)::bigint from epr_registration where id = '"
                    + created.id()
                    + "'"))
        .isEqualTo(String.valueOf(validUntil.toEpochSecond()));
  }

  // ==================================================================
  // 10. 能力声明 + 端点
  // ==================================================================

  @Test
  @DisplayName("16.3.2.2 能力声明如实报出未交付的下游")
  void capabilitiesReportUnavailableDownstreams() {
    EprCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.gaps())
        .filteredOn(gap -> "12".equals(gap.moduleId()))
        .singleElement()
        .satisfies(
            gap -> {
              assertThat(gap.available()).isFalse();
              assertThat(gap.ownerModule()).isEqualTo("12");
              assertThat(gap.impact()).contains("尚未落地");
            });
    assertThat(caps.gaps())
        .filteredOn(gap -> "16.3.2.1".equals(gap.moduleId()))
        .singleElement()
        .satisfies(
            gap -> {
              assertThat(gap.available()).isFalse();
              assertThat(gap.ownerModule()).isEqualTo("16.3.2.1");
            });
    assertThat(caps.regimeTypes())
        .containsExactlyInAnyOrder("LUCID", "PACKAGING_REGISTRATION", "BATTERY", "WEEE");
    assertThat(caps.upstreams())
        .filteredOn(upstream -> "16.3.1.1".equals(upstream.moduleId()))
        .singleElement()
        .satisfies(
            upstream -> {
              assertThat(upstream.available()).isTrue();
              assertThat(upstream.impact()).contains("粗 / 细两层");
            });
    assertThat(caps.enforcementNote()).contains("listing_publish_job_target").contains("publish_task");
  }

  @Test
  @DisplayName("16.3.2.2 店铺目录从真实店铺表推导「需要 EPR 的国家」")
  void shopListDerivesEprCountries() {
    seedShop("EPR-SHOP-DE", "DE");
    seedShop("EPR-SHOP-US", "US");

    EprShopList shops = as("channel.operation.read", () -> service.shops(null, null));

    assertThat(shops.items())
        .filteredOn(shop -> "EPR-SHOP-DE".equals(shop.code()))
        .singleElement()
        .satisfies(shop -> assertThat(shop.eprCountryCodes()).contains("DE"));
    assertThat(shops.items())
        .filteredOn(shop -> "EPR-SHOP-US".equals(shop.code()))
        .singleElement()
        .satisfies(shop -> assertThat(shop.eprCountryCodes()).isEmpty());
  }

  @Test
  @DisplayName("16.3.2.2 档案清单是真分页 + 真筛选")
  void registrationListIsRealPagingAndFiltering() {
    register(
        "LUCID", "DE-LUCID-1", List.of("DE"), List.of("PACKAGING"), "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC), null);
    register(
        "WEEE", "DE-WEEE-1", List.of("DE"), List.of("ELECTRONICS"), "OPEN_ENDED",
        OffsetDateTime.now(ZoneOffset.UTC), null);

    EprRegistrationPage all = as("channel.operation.read", () -> service.registrations(null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);

    EprRegistrationPage firstPage =
        as("channel.operation.read", () -> service.registrations(null, null, null, null, 0, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(2);

    EprRegistrationPage filtered =
        as("channel.operation.read", () -> service.registrations("WEEE", null, null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).regimeCode()).isEqualTo("WEEE");

    EprRegistrationPage byCountry =
        as("channel.operation.read", () -> service.registrations(null, null, null, "DE", 0, 50));
    assertThat(byCountry.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.3.2.2 HTTP 端点真的通：能力声明 / 闸门 / 覆盖")
  void httpEndpointsWork() throws Exception {
    http.perform(get("/channel/epr-registrations/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.regimeTypes").isArray());

    http.perform(
            post("/channel/epr-registrations/publish-gate")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"countryCode\":\"DE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.countryScope.allowed").value(false));

    http.perform(
            get("/channel/epr-registrations/coverage").param("countryCode", "DE")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    // 未认证 ⇒ 401（租户只来自已认证成员关系）
    http.perform(get("/channel/epr-registrations/capabilities")).andExpect(status().isUnauthorized());
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
