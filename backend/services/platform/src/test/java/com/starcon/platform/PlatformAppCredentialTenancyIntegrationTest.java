package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AppCredentialModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.1.1.1 应用与凭证的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的七张表与本模块的端点上</b>：
 * {@code platform_app} / {@code platform_app_credential} / {@code platform_app_scope} /
 * {@code platform_app_ip_allowlist} / {@code platform_app_quota} /
 * {@code platform_app_quota_usage} / {@code platform_app_audit_event}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAppCredentialTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的七张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_app",
          "platform_app_credential",
          "platform_app_scope",
          "platform_app_ip_allowlist",
          "platform_app_quota",
          "platform_app_quota_usage",
          "platform_app_audit_event");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-app-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of("platform.app.read", "platform.app.create", "platform.app.update", "platform.app.revoke");
    actors.put("session-a", actor(UUID.randomUUID(), a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, permissions));
    try {
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    // 连接池压到 1：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential, platform_app"
              + " restart identity cascade");
    }
    seedApp(a, idFor(a, 1), "app-a");
    seedApp(b, idFor(b, 1), "app-b");
  }

  /** 在可信租户事务里插一个应用 + 一条凭证 + 一条授权范围。 */
  void seedApp(UUID tenant, UUID id, String code) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,status,"
                  + " created_at,updated_at) values(?,?,?,'夹具应用','THIRD_PARTY','','ACTIVE',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              code,
              now,
              now);
          sql.execute(
              "insert into platform_app_credential(id,tenant_id,app_id,version,status,api_key_prefix,"
                  + " api_key_digest,api_key_lookup,secret_hint,secret_digest,issued_at,created_at)"
                  + " values(?,?,?,1,'ACTIVE','sk_fixture',?::text,?::text,'aaaa',?::text,"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              id,
              "$2$fixture-fixture-fixture-fixture-fixture-fixture-fixture",
              java.util.UUID.nameUUIDFromBytes(("lookup-" + code).getBytes()).toString(),
              "$2$secret-secret-secret-secret-secret-secret-secret",
              now,
              now);
          sql.execute(
              "insert into platform_app_scope(id,tenant_id,app_id,resource_code,action_code,granted_at)"
                  + " values(?,?,?,'order','READ',?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              id,
              now);
          sql.execute(
              "insert into platform_app_quota(id,tenant_id,app_id,window_type,limit_count,enabled,"
                  + " created_at,updated_at) values(?,?,?,'MINUTE',100,true,?::timestamptz,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              id,
              now,
              now);
          sql.execute(
              "insert into platform_app_audit_event(id,tenant_id,app_id,event_type,actor_kind,detail,"
                  + " created_at) values(?,?,?,'APP_REGISTERED','USER','夹具',?::timestamptz)",
              idFor(tenant, 5),
              tenant,
              id,
              now);
          return null;
        });
  }

  /** 以指定租户身份读应用码。 */
  List<String> appCodesOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select app_code from platform_app order by app_code").map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读凭证前缀。 */
  List<String> credentialPrefixesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select api_key_prefix from platform_app_credential order by api_key_prefix")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select app_code from platform_app").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的七张表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V377 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      // 每张表都必须有 tenant_id NOT NULL。
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 本模块不落任何金额() {
    var amountColumns =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_name in ('platform_app','platform_app_credential','platform_app_scope',"
                + " 'platform_app_ip_allowlist','platform_app_quota','platform_app_quota_usage',"
                + " 'platform_app_audit_event')"
                + " and (column_name like '%amount%' or column_name like '%price%' or column_name like '%fee%'"
                + " or column_name like '%balance%' or column_name like '%tax%' or column_name like '%currency%')");
    assertThat(amountColumns).as("对外凭证模块不落金额").isEmpty();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(appCodesOf(a)).containsExactly("app-a");
    assertThat(appCodesOf(b)).containsExactly("app-b");
    assertThat(credentialPrefixesOf(a)).containsExactly("sk_fixture");
    assertThat(credentialPrefixesOf(b)).containsExactly("sk_fixture");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () -> sql.fetch("select app_code from platform_app where id = ?", idFor(b, 1)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 授权范围 / 配额 / 审计同理。
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_app_scope")).size()).isEqualTo(1);
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_app_quota")).size()).isEqualTo(1);
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_app_audit_event")).size()).isEqualTo(1);
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from platform_app_scope where id = ?", idFor(b, 3))
                        .size()))
        .as("按主键直查对方租户的授权范围也必须被挡").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/apps").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationAppPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).appCode()).isEqualTo("app-a");

    var bPage =
        json.readValue(
            http.perform(get("/platform/apps").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationAppPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).appCode()).isEqualTo("app-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,"
                        + " status,created_at,updated_at)"
                        + " values(?,?,'forged','伪造应用','THIRD_PARTY','','ACTIVE',"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select app_code from platform_app where app_code = 'forged'").size()).isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"appCode":"forged-header","appName":"伪造头应用","appType":"THIRD_PARTY","description":""}
        """;

    http.perform(
            post("/platform/apps")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows = tenants.run(a, () -> sql.fetch("select app_code, tenant_id from platform_app order by app_code"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> r.get("app_code", String.class).equals("forged-header"))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(appCodesOf(b)).containsExactly("app-b");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(appCodesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的应用").containsExactly("app-a");
      assertThat(appCodesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的应用").containsExactly("app-b");
      assertThat(credentialPrefixesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的凭证").hasSize(1);
      assertThat(credentialPrefixesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的凭证").hasSize(1);
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(appCodesOf(a)).containsExactly("app-a");
    assertThat(appCodesOf(b)).containsExactly("app-b");
  }

  // ==========================================================================
  // 配额用量也按租户隔离
  // ==========================================================================
  @Test
  void 配额用量也按租户隔离() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into platform_app_quota_usage(id,tenant_id,app_id,window_type,window_started_at,"
                  + " used_count,updated_at) values(?,?,?,'MINUTE',?::timestamptz,?::numeric,?::timestamptz)",
              UUID.randomUUID(),
              a,
              idFor(a, 1),
              now.withSecond(0).withNano(0),
              new BigDecimal("7"),
              now);
          return null;
        });
    var aUsed =
        tenants.run(
            a,
            () ->
                sql.fetch("select used_count from platform_app_quota_usage")
                    .map(r -> r.get(0, BigDecimal.class)));
    assertThat(aUsed).hasSize(1);
    assertThat(aUsed.get(0)).isEqualByComparingTo("7");
    assertThat(tenants.run(b, () -> sql.fetch("select id from platform_app_quota_usage")).size())
        .as("B 看不见 A 的窗口用量")
        .isZero();
  }
}
