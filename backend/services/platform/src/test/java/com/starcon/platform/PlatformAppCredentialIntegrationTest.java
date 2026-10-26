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
 * 18.1.1.1 应用与凭证的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>覆盖蓝图点名的五项职责：应用注册、API Key/Secret 生成与轮换、授权范围、IP 白名单、调用配额；
 * 外加「明文只展示一次」「访问有审计」「泄露可一键吊销」三条硬约束。
 *
 * <p>租户隔离的三条断言在 {@code PlatformAppCredentialTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAppCredentialIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of("platform.app.read", "platform.app.create", "platform.app.update", "platform.app.revoke");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  /**
   * 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。
   *
   * <p>这不是「测试写错了」，而是本模块的真实行为：读路径永远带着租户上下文。
   */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential, platform_app"
              + " restart identity cascade");
    }
  }

  // ================================================================ 夹具

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  private IntegrationAppRow registerApp(String code, String name) throws Exception {
    var body = json.writeValueAsString(new IntegrationAppInput(code, name, "THIRD_PARTY", "测试应用", "张三", "a@b.c"));
    var response =
        http.perform(
                post("/platform/apps")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, IntegrationAppRow.class);
  }

  // ================================================================ 应用注册

  @Test
  void 注册应用后档案真实落库() throws Exception {
    var app = registerApp("erp-gateway", "ERP 网关");
    assertThat(app.appCode()).isEqualTo("erp-gateway");
    assertThat(app.appTypeLabel()).isEqualTo("第三方系统");
    assertThat(app.status()).isEqualTo("ACTIVE");

    // 库里真的有一行（不是只在响应里编出来的）。
    var rows = inTenant(() -> sql.fetch("select app_code, tenant_id, status from platform_app"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("app_code", String.class)).isEqualTo("erp-gateway");
    assertThat(rows.get(0).get("tenant_id", UUID.class)).isEqualTo(tenant());
  }

  @Test
  void 应用标识租户内唯一且重复注册被挡() throws Exception {
    registerApp("dup", "第一个");
    var body = json.writeValueAsString(new IntegrationAppInput("dup", "第二个", "THIRD_PARTY", "", "", ""));
    http.perform(
            post("/platform/apps")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void 应用列表是真分页真筛选() throws Exception {
    registerApp("alpha", "阿尔法");
    registerApp("beta", "贝塔");
    registerApp("gamma", "伽马");

    var page =
        json.readValue(
            http.perform(get("/platform/apps").header("Authorization", "session-a").param("size", "2"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationAppPage.class);
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);

    var filtered =
        json.readValue(
            http.perform(get("/platform/apps").header("Authorization", "session-a").param("keyword", "贝"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationAppPage.class);
    assertThat(filtered.items()).hasSize(1);
    assertThat(filtered.items().get(0).appCode()).isEqualTo("beta");
  }

  // ================================================================ 凭证：明文只展示一次

  @Test
  void 凭证明文只在生成时出现一次_库里只有摘要() throws Exception {
    var app = registerApp("keys", "密钥应用");

    var issued =
        json.readValue(
            http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationCredentialIssued.class);
    assertThat(issued.apiKey()).startsWith("sk_");
    assertThat(issued.secret()).startsWith("ss_");
    assertThat(issued.notice()).contains("唯一一次");

    // 库里只有摘要：明文一个字都没有落库。
    var row =
        inTenant(
            () ->
                sql.fetchOne(
                    "select api_key_digest, api_key_lookup, secret_digest, secret_hint from platform_app_credential"));
    assertThat(row).isNotNull();
    assertThat(row.get("api_key_digest", String.class)).doesNotContain(issued.apiKey());
    assertThat(row.get("secret_digest", String.class)).doesNotContain(issued.secret());
    assertThat(row.get("api_key_digest", String.class)).startsWith("$2");
    assertThat(row.get("secret_digest", String.class)).startsWith("$2");
    assertThat(row.get("secret_hint", String.class)).isEqualTo(issued.secret().substring(issued.secret().length() - 4));

    // 读路径再也取不回明文。
    var list =
        json.readValue(
            http.perform(get("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<IntegrationCredentialRow>>() {});
    assertThat(list).hasSize(1);
    assertThat(list.get(0).apiKeyPrefix()).startsWith("sk_");
    assertThat(list.get(0).secretHint()).hasSize(4);
    assertThat(json.writeValueAsString(list)).doesNotContain(issued.apiKey());
    assertThat(json.writeValueAsString(list)).doesNotContain(issued.secret());
  }

  @Test
  void 轮换让旧摘要失效且旧版留痕() throws Exception {
    var app = registerApp("rotate", "轮换应用");
    var first =
        json.readValue(
            http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationCredentialIssued.class);
    var second =
        json.readValue(
            http.perform(post("/platform/apps/" + app.id() + "/credentials/rotate").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationCredentialIssued.class);

    assertThat(second.apiKey()).isNotEqualTo(first.apiKey());
    assertThat(second.credential().version()).isEqualTo(2);
    assertThat(second.credential().rotatedFromId()).isEqualTo(first.credential().id());

    // 旧凭证状态收口成 ROTATED（留痕，不删）。
    var old =
        inTenant(
            () ->
                sql.fetchOne(
                    "select status from platform_app_credential where id = ?",
                    first.credential().id()));
    assertThat(old.get("status", String.class)).isEqualTo("ROTATED");

    // 旧明文验签不过，新明文验签过。
    var verifyOld = verify(app, first.apiKey(), first.secret());
    assertThat(verifyOld.credentialMatched()).isFalse();
    var verifyNew = verify(app, second.apiKey(), second.secret());
    assertThat(verifyNew.credentialMatched()).isTrue();
  }

  @Test
  void 已有生效凭证时不允许再生成_必须走轮换() throws Exception {
    var app = registerApp("single", "单凭证");
    http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
        .andExpect(status().isOk());
    http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 一键吊销

  @Test
  void 吊销应用会一键吊销全部凭证() throws Exception {
    var app = registerApp("leak", "泄露应用");
    http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/apps/" + app.id() + "/revoke")
                .header("Authorization", "session-a")
                .param("reason", "密钥疑似泄露"))
        .andExpect(status().isOk());

    var appRow =
        inTenant(
            () ->
                sql.fetchOne(
                    "select status, revoked_at, revoke_reason from platform_app where id = ?", app.id()));
    assertThat(appRow.get("status", String.class)).isEqualTo("REVOKED");
    assertThat(appRow.get("revoked_at", OffsetDateTime.class)).isNotNull();
    assertThat(appRow.get("revoke_reason", String.class)).isEqualTo("密钥疑似泄露");

    var credential =
        inTenant(
            () ->
                sql.fetchOne(
                    "select status, revoked_at from platform_app_credential where app_id = ?", app.id()));
    assertThat(credential.get("status", String.class)).isEqualTo("REVOKED");
    assertThat(credential.get("revoked_at", OffsetDateTime.class)).isNotNull();

    // 吊销后不能再签发凭证。
    http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 吊销应用是幂等的_时间戳不漂移() throws Exception {
    var app = registerApp("idem", "幂等应用");
    http.perform(
            post("/platform/apps/" + app.id() + "/revoke")
                .header("Authorization", "session-a")
                .param("reason", "第一次"))
        .andExpect(status().isOk());
    var first =
        inTenant(
                () ->
                    sql.fetchOne("select revoked_at from platform_app where id = ?", app.id())
                        .get("revoked_at", OffsetDateTime.class));
    http.perform(
            post("/platform/apps/" + app.id() + "/revoke")
                .header("Authorization", "session-a")
                .param("reason", "第二次"))
        .andExpect(status().isOk());
    var second =
        inTenant(
                () ->
                    sql.fetchOne("select revoked_at from platform_app where id = ?", app.id())
                        .get("revoked_at", OffsetDateTime.class));
    assertThat(second).isEqualTo(first);
  }

  // ================================================================ 授权范围

  @Test
  void 授权范围可增可撤且重复授权幂等() throws Exception {
    var app = registerApp("scope", "范围应用");
    var scope =
        json.readValue(
            http.perform(
                    post("/platform/apps/" + app.id() + "/scopes")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new IntegrationScopeInput("order", "READ"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationScopeRow.class);
    assertThat(scope.actionCodeLabel()).isEqualTo("读");

    // 重复授权不叠加。
    http.perform(
            post("/platform/apps/" + app.id() + "/scopes")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationScopeInput("order", "READ"))))
        .andExpect(status().isOk());
    assertThat(inTenant(() -> sql.fetch("select id from platform_app_scope"))).hasSize(1);

    // 写操作不在授权内。
    var verify = verify(app, null, null, "order", "WRITE", null);
    assertThat(verify.scopeChecked()).isTrue();
    assertThat(verify.scopeGranted()).isFalse();

    http.perform(
            delete("/platform/apps/" + app.id() + "/scopes/" + scope.id())
                .header("Authorization", "session-a"))
        .andExpect(status().isOk());
    assertThat(inTenant(() -> sql.fetch("select id from platform_app_scope"))).isEmpty();
  }

  @Test
  void 非法操作类型被拒() throws Exception {
    var app = registerApp("badscope", "非法范围");
    http.perform(
            post("/platform/apps/" + app.id() + "/scopes")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationScopeInput("order", "DELETE"))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ IP 白名单

  @Test
  void 白名单接受合法cidr且拒绝非法cidr() throws Exception {
    var app = registerApp("ip", "白名单应用");
    http.perform(
            post("/platform/apps/" + app.id() + "/ip-allowlist")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationIpAllowlistInput("203.0.113.0/24", "办公网"))))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/apps/" + app.id() + "/ip-allowlist")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationIpAllowlistInput("203.0.113.0/33", "越界"))))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/platform/apps/" + app.id() + "/ip-allowlist")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationIpAllowlistInput("not-an-ip", "乱写"))))
        .andExpect(status().isBadRequest());
    assertThat(inTenant(() -> sql.fetch("select cidr from platform_app_ip_allowlist"))).hasSize(1);
  }

  @Test
  void 白名单判定按cidr包含() throws Exception {
    var app = registerApp("ipcheck", "白名单判定");
    http.perform(
            post("/platform/apps/" + app.id() + "/ip-allowlist")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationIpAllowlistInput("203.0.113.0/24", ""))))
        .andExpect(status().isOk());
    var issued = issue(app);
    var inside = verify(app, issued.apiKey(), issued.secret(), null, null, "203.0.113.7");
    assertThat(inside.ipChecked()).isTrue();
    assertThat(inside.ipAllowed()).isTrue();
    var outside = verify(app, issued.apiKey(), issued.secret(), null, null, "198.51.100.9");
    assertThat(outside.ipAllowed()).isFalse();
  }

  // ================================================================ 调用配额

  @Test
  void 配额配置与额度判定真实落库() throws Exception {
    var app = registerApp("quota", "配额应用");
    http.perform(
            post("/platform/apps/" + app.id() + "/quotas")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationQuotaInput("MINUTE", new BigDecimal("100"), true))))
        .andExpect(status().isOk());
    assertThat(inTenant(() -> sql.fetch("select limit_count from platform_app_quota")))
        .singleElement()
        .satisfies(r -> assertThat(r.get("limit_count", BigDecimal.class)).isEqualByComparingTo("100"));

    var status =
        json.readValue(
            http.perform(get("/platform/apps/" + app.id() + "/quota-status").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<IntegrationQuotaStatusRow>>() {});
    assertThat(status).hasSize(5);
    var minute = status.stream().filter(s -> s.windowType().equals("MINUTE")).findFirst().orElseThrow();
    assertThat(minute.configured()).isTrue();
    assertThat(minute.usedCount()).isEqualByComparingTo("0");
    assertThat(minute.decision()).isEqualTo("ALLOW");
    // 没配过的窗口 = 不限，不是限 0 次。
    var day = status.stream().filter(s -> s.windowType().equals("DAY")).findFirst().orElseThrow();
    assertThat(day.configured()).isFalse();
    assertThat(day.decision()).isEqualTo("NOT_CONFIGURED");
  }

  @Test
  void 用量回报是累加且超限能被判定() throws Exception {
    var app = registerApp("usage", "用量应用");
    http.perform(
            post("/platform/apps/" + app.id() + "/quotas")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationQuotaInput("HOUR", new BigDecimal("2"), true))))
        .andExpect(status().isOk());
    var usage =
        new IntegrationQuotaUsageInput(tenant(), app.id(), "HOUR", new BigDecimal("1"), OffsetDateTime.now(ZoneOffset.UTC));
    var body = json.writeValueAsString(usage);
    http.perform(
            post("/platform/app-quota-usages")
                .header("X-Internal-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/app-quota-usages")
                .header("X-Internal-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 用量是累加，不是覆盖。
    var used =
        inTenant(
                () ->
                    sql.fetchOne("select used_count from platform_app_quota_usage")
                        .get("used_count", BigDecimal.class));
    assertThat(used).isEqualByComparingTo("2");

    var status =
        json.readValue(
            http.perform(get("/platform/apps/" + app.id() + "/quota-status").header("Authorization", "session-a"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<IntegrationQuotaStatusRow>>() {});
    var hour = status.stream().filter(s -> s.windowType().equals("HOUR")).findFirst().orElseThrow();
    assertThat(hour.exceeded()).isTrue();
    assertThat(hour.decision()).isEqualTo("EXCEEDED");
    assertThat(hour.remainingCount()).isEqualByComparingTo("0");
  }

  @Test
  void 用量回报缺内部密钥被拒() throws Exception {
    var app = registerApp("usagekey", "用量密钥");
    var body =
        json.writeValueAsString(
            new IntegrationQuotaUsageInput(tenant(), app.id(), "HOUR", BigDecimal.ONE, OffsetDateTime.now(ZoneOffset.UTC)));
    http.perform(
            post("/platform/app-quota-usages")
                .header("X-Internal-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    assertThat(inTenant(() -> sql.fetch("select id from platform_app_quota_usage"))).isEmpty();
  }

  // ================================================================ 审计

  @Test
  void 每次写动作都留下审计且审计不可改不可删() throws Exception {
    var app = registerApp("audit", "审计应用");
    http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/apps/" + app.id() + "/scopes")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new IntegrationScopeInput("order", "READ"))))
        .andExpect(status().isOk());

    var events =
        json.readValue(
            http.perform(get("/platform/apps/" + app.id() + "/audit-events").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IntegrationAuditEventPage.class);
    assertThat(events.total()).isGreaterThanOrEqualTo(3);
    assertThat(events.items().stream().map(IntegrationAuditEventRow::eventType))
        .contains("APP_REGISTERED", "CREDENTIAL_GENERATED", "SCOPE_CHANGED");

    // 审计表只追加：UPDATE / DELETE 都被触发器拒绝。
    // ⚠️ 必须在**可信租户事务里**做：否则 RLS 会先挡下来（那证明的是隔离，不是只追加）。
    var eventId = events.items().get(0).id();
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> sql.execute("update platform_app_audit_event set detail = '改一下' where id = ?", eventId)))
        .hasStackTraceContaining("只追加");
    assertThatThrownBy(
            () -> inTenant(() -> sql.execute("delete from platform_app_audit_event where id = ?", eventId)))
        .hasStackTraceContaining("只追加");
  }

  @Test
  void 应用不能被删除_审计留痕要保留() throws Exception {
    var app = registerApp("nodelete", "不可删");
    assertThatThrownBy(() -> inTenant(() -> sql.execute("delete from platform_app where id = ?", app.id())))
        .hasStackTraceContaining("platform_app_audit_event");
    assertThat(inTenant(() -> sql.fetch("select id from platform_app"))).hasSize(1);
  }

  // ================================================================ 时间不漂移

  @Test
  void 签发时刻写回不漂移() throws Exception {
    var app = registerApp("clock", "时钟应用");
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    var issued = issue(app);
    var stored =
        inTenant(
                () ->
                    sql.fetchOne(
                            "select issued_at from platform_app_credential where id = ?",
                            issued.credential().id())
                        .get("issued_at", OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant()).isBetween(before.toInstant(), OffsetDateTime.now(ZoneOffset.UTC).toInstant());
    // 响应里的时刻与库里的一致。
    assertThat(issued.credential().issuedAt().toInstant()).isEqualTo(stored.toInstant());
  }

  // ================================================================ 辅助

  private IntegrationCredentialIssued issue(IntegrationAppRow app) throws Exception {
    return json.readValue(
        http.perform(post("/platform/apps/" + app.id() + "/credentials").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        IntegrationCredentialIssued.class);
  }

  private IntegrationCredentialVerifyResult verify(
      IntegrationAppRow app, String apiKey, String secret) throws Exception {
    return verify(app, apiKey, secret, null, null, null);
  }

  private IntegrationCredentialVerifyResult verify(
      IntegrationAppRow app,
      String apiKey,
      String secret,
      String resourceCode,
      String actionCode,
      String clientIp)
      throws Exception {
    var body =
        json.writeValueAsString(
            new IntegrationCredentialVerifyInput(apiKey, secret, clientIp, resourceCode, actionCode));
    return json.readValue(
        http.perform(
                post("/platform/apps/" + app.id() + "/credentials/verify")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        IntegrationCredentialVerifyResult.class);
  }

}
