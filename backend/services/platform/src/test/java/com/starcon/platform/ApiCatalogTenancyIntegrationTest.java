package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ApiCatalogModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 18.1.1.2「接口目录」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>本模块的租户数据只有一张表</b>：{@code platform_api_sandbox_run}（沙箱测试留痕）。
 * 接口目录本身是**从 spec 解析**的投影（与租户无关），所以它天然不跨租户泄露；
 * 真正需要 RLS 兜住的是「谁在什么时候发了什么测试」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiCatalogTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer upstream;

  /** 本模块的表。 */
  static final List<String> TABLES = List.of("platform_api_sandbox_run");

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-api-catalog-" + tenant + "-" + ordinal).getBytes());
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
    var permissions = List.of("platform.api-catalog.read", "platform.api-catalog.test");
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

      // 一个「上游服务」：吐契约 + 吐真响应（沙箱要真打一次）。
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext(
          "/v3/api-docs",
          e -> {
            var body =
                """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "tenant 服务", "version": "0.1.0"},
                  "paths": {
                    "/tenant/things": {
                      "get": {"summary": "东西列表", "responses": {"200": {"description": "OK"}}}
                    },
                    "/tenant/forged-header": {
                      "get": {"summary": "伪造头用", "responses": {"200": {"description": "OK"}}}
                    }
                  }
                }
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      upstream.createContext(
          "/tenant/things",
          e -> {
            var body = "{\"items\":[],\"total\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      upstream.setExecutor(Executors.newFixedThreadPool(2));
      upstream.start();
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
    r.add("platform.api-catalog.services.tenant", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("truncate table platform_api_sandbox_run restart identity cascade");
    }
    seedRun(a, idFor(a, 1), "/tenant/a-only");
    seedRun(b, idFor(b, 1), "/tenant/b-only");
  }

  /** 在可信租户事务里插一条沙箱测试留痕。 */
  void seedRun(UUID tenant, UUID id, String path) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_api_sandbox_run(id,tenant_id,target_service,http_method,target_path,"
                  + " query_string,request_body,status_code,response_summary,response_bytes,duration_ms,"
                  + " outcome,message,created_at)"
                  + " values(?,?,?,'GET',?,'','',200,'{\"total\":0}',13,7,'COMPLETED','夹具',"
                  + " ?::timestamptz)",
              id,
              tenant,
              "tenant",
              path,
              now);
          return null;
        });
  }

  /** 以指定租户身份读测试留痕的路径。 */
  List<String> pathsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select target_path from platform_api_sandbox_run order by target_path")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_api_sandbox_run").size();
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
      assertThat(row).as("V411 必须真的建出 " + table).isNotNull();
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
  void 本模块不落任何金额() {
    var amountColumns =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_name = 'platform_api_sandbox_run'"
                + " and (column_name like '%amount%' or column_name like '%price%' or column_name like '%fee%'"
                + " or column_name like '%balance%' or column_name like '%tax%' or column_name like '%currency%')");
    assertThat(amountColumns).as("接口目录模块不落金额").isEmpty();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(pathsOf(a)).containsExactly("/tenant/a-only");
    assertThat(pathsOf(b)).containsExactly("/tenant/b-only");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () -> sql.fetch("select id from platform_api_sandbox_run where id = ?", idFor(b, 1)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的测试历史() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/api-catalog/sandbox/runs").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogSandboxRunPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).targetPath()).isEqualTo("/tenant/a-only");

    var bPage =
        json.readValue(
            http.perform(get("/platform/api-catalog/sandbox/runs").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCatalogSandboxRunPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).targetPath()).isEqualTo("/tenant/b-only");
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
                    "insert into platform_api_sandbox_run(id,tenant_id,target_service,http_method,"
                        + " target_path,status_code,outcome,created_at)"
                        + " values(?,?,'tenant','GET','/tenant/forged',200,'COMPLETED',?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select target_path from platform_api_sandbox_run where target_path = '/tenant/forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var response =
        http.perform(
                post("/platform/api-catalog/sandbox/runs")
                    .header("Authorization", "session-a")
                    // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                    .header("X-Tenant-Id", b.toString())
                    .header("X-Internal-Key", "forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"targetService":"tenant","httpMethod":"GET","targetPath":"/tenant/forged-header"}
                        """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select target_path, tenant_id from platform_api_sandbox_run order by target_path"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> r.get("target_path", String.class).equals("/tenant/forged-header"))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(pathsOf(b)).containsExactly("/tenant/b-only");
    assertThat(response.getContentAsString()).contains("COMPLETED");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(pathsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的留痕").containsExactly("/tenant/a-only");
      assertThat(pathsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的留痕").containsExactly("/tenant/b-only");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(pathsOf(a)).containsExactly("/tenant/a-only");
    assertThat(pathsOf(b)).containsExactly("/tenant/b-only");
  }
}
