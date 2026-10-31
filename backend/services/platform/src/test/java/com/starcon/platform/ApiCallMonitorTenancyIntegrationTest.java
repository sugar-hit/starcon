package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ApiCallMonitorModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 18.1.1.3「调用监控」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>本模块比兄弟模块多一条隔离面</b>：网关入口的租户**不是**来自请求头或会话，
 * 而是来自 <b>API Key 反查</b>。所以断言 2 在这里有两格：
 *
 * <ul>
 *   <li>伪造 {@code X-Tenant-Id} 头的网关调用，行必须仍然落在**密钥所属**的租户名下；
 *   <li>伪造 {@code X-Tenant-Id} 头的监控端点调用，只能看到**会话租户**的数据。
 * </ul>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiCallMonitorTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 本模块的表。 */
  static final List<String> TABLES = List.of("platform_api_call_log", "platform_api_call_alert");

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

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
    var permissions = List.of("platform.call-monitor.read", "platform.call-monitor.update");
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

  /** 一个应用 + 一版凭证（明文 Key / Secret 拿在手里）。 */
  private record Fixture(UUID tenant, UUID appId, String appCode, String apiKey, String secret) {}

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_api_call_log, platform_api_call_alert,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential, platform_app"
              + " restart identity cascade");
    }
  }

  /** 直接在可信租户事务里造一个应用与一版凭证。 */
  private Fixture seed(UUID tenant, String code, String apiKey, String secret) {
    var appId = UUID.randomUUID();
    var credentialId = UUID.randomUUID();
    var encoder = new BCryptPasswordEncoder();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name) values(?,?,?,?)",
              appId,
              tenant,
              code,
              "夹具应用 " + code);
          sql.execute(
              "insert into platform_app_credential(id,tenant_id,app_id,api_key_prefix,api_key_digest,"
                  + " api_key_lookup,secret_hint,secret_digest) values(?,?,?,?,?,?,?,?)",
              credentialId,
              tenant,
              appId,
              apiKey.substring(0, Math.min(8, apiKey.length())),
              encoder.encode(apiKey),
              AppCredentialService.sha256Hex(apiKey),
              "**" + secret.substring(Math.max(0, secret.length() - 4)),
              encoder.encode(secret));
          return null;
        });
    return new Fixture(tenant, appId, code, apiKey, secret);
  }

  /** 调一次网关。 */
  private void callGateway(Fixture fixture, String requestId) throws Exception {
    http.perform(
            get("/open/v1/ping")
                .header("X-Api-Key", fixture.apiKey())
                .header("X-Api-Secret", fixture.secret())
                .header("X-Request-Id", requestId))
        .andExpect(status().isOk());
  }

  /** 以指定租户身份读埋点。 */
  List<String> requestIdsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select request_id from platform_api_call_log order by request_id")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_api_call_log").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V414 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 埋点表只授读与写_改与删由数据层触发器钉死() {
    for (String privilege : List.of("UPDATE", "DELETE")) {
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('platform_app','platform_api_call_log',?)",
                      privilege)
                  .get(0, Boolean.class))
          .as("埋点表不该给 platform_app " + privilege + " 权限")
          .isFalse();
    }
    // 触发器真的在（属主绕开权限码也照样被拦下）。
    var triggers =
        sql.fetch(
            "select tgname from pg_trigger where tgrelid = 'platform_api_call_log'::regclass"
                + " and not tgisinternal");
    assertThat(triggers).hasSize(2);
  }

  @Test
  void 本模块不落任何金额() {
    var amountColumns =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_name in ('platform_api_call_log','platform_api_call_alert')"
                + " and (column_name like '%amount%' or column_name like '%price%' or column_name like '%fee%'"
                + " or column_name like '%balance%' or column_name like '%tax%' or column_name like '%currency%')");
    assertThat(amountColumns).as("调用监控模块不落金额").isEmpty();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var fa = seed(a, "app-a", "sk_tenant_a_key", "ss_tenant_a_secret");
    var fb = seed(b, "app-b", "sk_tenant_b_key", "ss_tenant_b_secret");
    try {
      callGateway(fa, "req-a");
      callGateway(fb, "req-b");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    assertThat(requestIdsOf(a)).containsExactly("req-a");
    assertThat(requestIdsOf(b)).containsExactly("req-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from platform_api_call_log where request_id = ?",
                        "req-b")
                    .size());
    assertThat(byId).as("按事实列直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的调用() throws Exception {
    var fa = seed(a, "app-a", "sk_tenant_a_key", "ss_tenant_a_secret");
    var fb = seed(b, "app-b", "sk_tenant_b_key", "ss_tenant_b_secret");
    callGateway(fa, "req-a");
    callGateway(fb, "req-b");

    var aPage =
        json.readValue(
            http.perform(get("/platform/call-monitor/calls").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).requestId()).isEqualTo("req-a");

    var bPage =
        json.readValue(
            http.perform(get("/platform/call-monitor/calls").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).requestId()).isEqualTo("req-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_api_call_log(id,tenant_id,app_id,endpoint_code,http_method,"
                        + " request_path,status_code,outcome,called_at)"
                        + " values(?,?,'00000000-0000-4000-8000-000000000000','PING','GET',"
                        + " '/open/v1/ping',200,'SUCCESS',now())",
                    UUID.randomUUID(),
                    forged))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select id from platform_api_call_log where tenant_id = ?", forged).size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在密钥所属租户名下() throws Exception {
    var fa = seed(a, "app-a", "sk_tenant_a_key", "ss_tenant_a_secret");

    http.perform(
            get("/open/v1/ping")
                .header("X-Api-Key", fa.apiKey())
                .header("X-Api-Secret", fa.secret())
                .header("X-Request-Id", "req-forged")
                // 伪造的租户头：网关**不**信任它，租户只由密钥反查。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged"))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch("select request_id, tenant_id from platform_api_call_log order by request_id"));
    assertThat(rows).as("伪造头的网关调用必须仍然写入成功 —— 头不是租户依据").hasSize(1);
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它。
    assertThat(requestIdsOf(b)).isEmpty();
  }

  @Test
  void 断言2_伪造租户头无效且监控端点只看得到会话租户() throws Exception {
    var fa = seed(a, "app-a", "sk_tenant_a_key", "ss_tenant_a_secret");
    var fb = seed(b, "app-b", "sk_tenant_b_key", "ss_tenant_b_secret");
    callGateway(fa, "req-a");
    callGateway(fb, "req-b");

    var page =
        json.readValue(
            http.perform(
                    get("/platform/call-monitor/calls")
                        .header("Authorization", "session-a")
                        // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                        .header("X-Tenant-Id", b.toString())
                        .header("X-Internal-Key", "forged"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).requestId()).isEqualTo("req-a");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    var fa = seed(a, "app-a", "sk_tenant_a_key", "ss_tenant_a_secret");
    var fb = seed(b, "app-b", "sk_tenant_b_key", "ss_tenant_b_secret");
    callGateway(fa, "req-a");
    callGateway(fb, "req-b");

    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(requestIdsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的埋点").containsExactly("req-a");
      assertThat(requestIdsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的埋点").containsExactly("req-b");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(requestIdsOf(a)).containsExactly("req-a");
    assertThat(requestIdsOf(b)).containsExactly("req-b");
  }

  // ==========================================================================
  // 网关的跨租户出口：只有一个，且它自己判闸门
  // ==========================================================================
  @Test
  void 跨租户定位只走那一个函数_裸sql读不到任何行() {
    // 运行角色自己写的裸 SQL：一条都读不到（FORCE RLS + 运行角色 NOBYPASSRLS）。
    assertThat(visibleWithoutTenant()).isZero();

    // 函数是唯一的跨租户出口，且只接受「按值定位」，不接受调用方传租户。
    var signature =
        sql.fetch(
            "select p.proname, p.prosecdef, pg_get_function_identity_arguments(p.oid) as args"
                + " from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
                + " where n.nspname = 'public' and p.proname = 'platform_api_credential_lookup'");
    assertThat(signature).hasSize(1);
    assertThat(signature.get(0).get("prosecdef", Boolean.class))
        .as("跨租户出口必须是 SECURITY DEFINER（否则 FORCE RLS 会把它也挡住）")
        .isTrue();
    assertThat(signature.get(0).get("args", String.class)).isEqualTo("p_lookup text");
  }

  @Test
  void 同一个密钥登记在两个租户下时判为歧义_不落行也不猜() throws Exception {
    // V377 的唯一约束是 UNIQUE(tenant_id, api_key_lookup)：同一个 Key 值可以在两个租户下各登记一次。
    var fa = seed(a, "app-a", "sk_shared_key", "ss_shared_secret");
    var fb = seed(b, "app-b", "sk_shared_key", "ss_shared_secret");
    assertThat(fa.appId()).isNotEqualTo(fb.appId());

    var response =
        http.perform(
                get("/open/v1/ping")
                    .header("X-Api-Key", "sk_shared_key")
                    .header("X-Api-Secret", "ss_shared_secret"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).contains("API_KEY_AMBIGUOUS");

    // 歧义时**不落行**：落给谁都是猜的。
    assertThat(requestIdsOf(a)).isEmpty();
    assertThat(requestIdsOf(b)).isEmpty();
    assertThat(visibleWithoutTenant()).isZero();
  }
}
