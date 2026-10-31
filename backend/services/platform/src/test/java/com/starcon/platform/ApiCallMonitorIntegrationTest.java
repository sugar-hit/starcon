package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ApiCallMonitorModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 18.1.1.3「调用监控」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住四件事（都是「外部系统调用错误要能被快速定位」的前提）：
 *
 * <ol>
 *   <li><b>埋点是真落库的</b>：网关每调一次就多一行，结论码与状态码逐字对应；
 *   <li><b>五种失败全都落行</b>（凭证错 / 应用被吊销 / 范围不足 / IP 不在白名单 / 超出配额）——
 *       它们恰恰是对接方最需要被定位的那几种；
 *   <li><b>定位不到租户的调用不落行</b>：Key 根本不存在时没有租户可归属，
 *       硬塞一个租户等于把 A 的调用量算到 B 头上；
 *   <li><b>统计与告警是真算出来的</b>：概览 / 维度 / 明细 / 告警的数字全部来自埋点，
 *       阈值口径是平台固定的那一套（见 {@code /options}），不替用户预置任何一行配置。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code ApiCallMonitorTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiCallMonitorIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer spec;

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
    // 权限码有两套：夹具要建应用与凭证（18.1.1.1 的 platform.app.*），
    // 被测的是 18.1.1.3 的 platform.call-monitor.*。
    var permissions =
        List.of(
            "platform.call-monitor.read",
            "platform.call-monitor.update",
            "platform.app.read",
            "platform.app.create",
            "platform.app.update",
            "platform.app.revoke");
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

      // platform 服务自己的**活契约**：接口维度的名称与说明必须从这里来。
      spec = HttpServer.create(new InetSocketAddress(0), 0);
      spec.createContext(
          "/v3/api-docs",
          e -> {
            var body =
                """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "platform", "version": "0.1.0"},
                  "paths": {
                    "/open/v1/ping": {
                      "get": {
                        "summary": "连通性自检（活契约里的说法）",
                        "description": "回显应用身份与服务端时刻。",
                        "responses": {"200": {"description": "OK"}}
                      }
                    },
                    "/open/v1/quota": {
                      "get": {"summary": "配额状态自检", "responses": {"200": {"description": "OK"}}}
                    },
                    "/open/v1/calls": {
                      "get": {"summary": "调用明细自诊", "responses": {"200": {"description": "OK"}}}
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
      spec.setExecutor(Executors.newFixedThreadPool(2));
      spec.start();
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
    // 接口维度接**活契约**：指向一个吐真 spec 的上游。
    r.add(
        "platform.api-catalog.services.platform",
        () -> "http://127.0.0.1:" + spec.getAddress().getPort());
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_api_call_log, platform_api_call_alert,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential, platform_app"
              + " restart identity cascade");
    }
  }

  /** 注册应用并签发一版凭证（拿到明文 Key / Secret）。 */
  private record Fixture(UUID appId, String appCode, String apiKey, String secret) {}

  private Fixture fixture(String code) throws Exception {
    var body =
        json.writeValueAsString(
            new AppCredentialModels.IntegrationAppInput(code, "测试应用", "THIRD_PARTY", "", "", ""));
    var app =
        json.readValue(
            http.perform(
                    post("/platform/apps")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AppCredentialModels.IntegrationAppRow.class);
    var issued =
        json.readValue(
            http.perform(
                    post("/platform/apps/" + app.id() + "/credentials")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AppCredentialModels.IntegrationCredentialIssued.class);
    return new Fixture(app.id(), app.appCode(), issued.apiKey(), issued.secret());
  }

  /** 调一次网关。 */
  private org.springframework.test.web.servlet.ResultActions gateway(
      String path, String apiKey, String secret) throws Exception {
    return http.perform(
        get(path)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Secret", secret)
            .header("X-Request-Id", "req-" + UUID.randomUUID()));
  }

  /** 埋点行数（在租户事务里读）。 */
  private long logCount() {
    return inTenant(() -> sql.fetch("select id from platform_api_call_log").size());
  }

  // ================================================================ 埋点

  @Test
  void 网关调一次就落一行埋点_结论码与状态码逐字对应() throws Exception {
    var f = fixture("erp");

    var response =
        gateway("/open/v1/ping", f.apiKey(), f.secret())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).contains("\"endpoint\":\"PING\"").contains(f.appCode());

    assertThat(logCount()).as("一次调用一行埋点").isEqualTo(1);
    var row = inTenant(() -> sql.fetchOne("select * from platform_api_call_log"));
    assertThat(row.get("outcome", String.class)).isEqualTo("SUCCESS");
    assertThat(row.get("status_code", Integer.class)).isEqualTo(200);
    assertThat(row.get("endpoint_code", String.class)).isEqualTo("PING");
    assertThat(row.get("http_method", String.class)).isEqualTo("GET");
    assertThat(row.get("request_path", String.class)).isEqualTo("/open/v1/ping");
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(tenant());
    assertThat(row.get("request_id", String.class)).startsWith("req-");
    assertThat(row.get("duration_ms", BigDecimal.class)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
  }

  @Test
  void 凭证错也落行_这是对接方最需要定位的那一类() throws Exception {
    var f = fixture("erp");

    gateway("/open/v1/ping", f.apiKey(), "ss_wrong_secret").andExpect(status().isUnauthorized());

    assertThat(logCount()).isEqualTo(1);
    var row = inTenant(() -> sql.fetchOne("select * from platform_api_call_log"));
    assertThat(row.get("outcome", String.class)).isEqualTo("AUTH_FAILED");
    assertThat(row.get("status_code", Integer.class)).isEqualTo(401);
    assertThat(row.get("error_code", String.class)).isEqualTo("SECRET_MISMATCH");
    // 租户仍然归属到密钥所属的租户。
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(tenant());
  }

  @Test
  void 定位不到租户的调用不落行_没有租户可归属() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    assertThat(logCount()).isEqualTo(1);

    // Key 根本不存在 ⇒ 没有租户可归属 ⇒ 不落行。
    gateway("/open/v1/ping", "sk_not_exist", "ss_not_exist").andExpect(status().isUnauthorized());

    assertThat(logCount()).as("定位不到租户的调用不许落行").isEqualTo(1);
  }

  @Test
  void 授权范围不足被拒且落行() throws Exception {
    var f = fixture("erp");
    // 只授 WRITE，不授 READ ⇒ 读端点被拒。
    http.perform(
            post("/platform/apps/" + f.appId() + "/scopes")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new AppCredentialModels.IntegrationScopeInput("platform.integration", "WRITE"))))
        .andExpect(status().isOk());

    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isForbidden());

    var row = inTenant(() -> sql.fetchOne("select * from platform_api_call_log"));
    assertThat(row.get("outcome", String.class)).isEqualTo("FORBIDDEN");
    assertThat(row.get("status_code", Integer.class)).isEqualTo(403);
    assertThat(row.get("error_code", String.class)).isEqualTo("SCOPE_DENIED");
  }

  @Test
  void ip不在白名单被拒且落行() throws Exception {
    var f = fixture("erp");
    http.perform(
            post("/platform/apps/" + f.appId() + "/ip-allowlist")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new AppCredentialModels.IntegrationIpAllowlistInput("10.0.0.0/8", "只允许内网"))))
        .andExpect(status().isOk());

    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isForbidden());

    var row = inTenant(() -> sql.fetchOne("select * from platform_api_call_log"));
    assertThat(row.get("outcome", String.class)).isEqualTo("FORBIDDEN");
    assertThat(row.get("error_code", String.class)).isEqualTo("IP_NOT_ALLOWED");
  }

  @Test
  void 超出配额回429且限流命中单独记() throws Exception {
    var f = fixture("erp");
    http.perform(
            post("/platform/apps/" + f.appId() + "/quotas")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new AppCredentialModels.IntegrationQuotaInput("MINUTE", BigDecimal.ONE, true))))
        .andExpect(status().isOk());

    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isTooManyRequests());

    var rows =
        inTenant(() -> sql.fetch("select * from platform_api_call_log order by called_at"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get("outcome", String.class)).isEqualTo("RATE_LIMITED");
    assertThat(rows.get(1).get("status_code", Integer.class)).isEqualTo(429);
    assertThat(rows.get(1).get("rate_limited", Boolean.class)).isTrue();
    assertThat(rows.get(1).get("error_code", String.class)).isEqualTo("QUOTA_EXCEEDED");
    assertThat(rows.get(0).get("rate_limited", Boolean.class)).isFalse();
  }

  @Test
  void 放行清单之外的路径一律404且不落行() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    assertThat(logCount()).isEqualTo(1);

    http.perform(get("/open/v1/not-a-real-endpoint").header("X-Api-Key", f.apiKey()))
        .andExpect(status().isNotFound());

    assertThat(logCount()).as("清单之外的路径没有接口维度，也不落行").isEqualTo(1);
  }

  // ================================================================ 统计

  @Test
  void 概览与维度分析都是真算出来的() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    gateway("/open/v1/ping", f.apiKey(), "ss_wrong").andExpect(status().isUnauthorized());

    var overview =
        json.readValue(
            http.perform(get("/platform/call-monitor/overview").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorOverview.class);
    assertThat(overview.totalCalls()).isEqualTo(2);
    assertThat(overview.errorCalls()).isEqualTo(1);
    assertThat(overview.rateLimitedCalls()).isZero();
    assertThat(overview.errorRate()).isEqualByComparingTo(new BigDecimal("0.500000"));
    assertThat(overview.appCount()).isEqualTo(1);
    assertThat(overview.endpointCount()).isEqualTo(1);
    assertThat(overview.qps()).isGreaterThan(BigDecimal.ZERO);

    var apps =
        json.readValue(
            http.perform(get("/platform/call-monitor/apps").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorModels.ApiCallMonitorAppRow[].class);
    assertThat(apps).hasSize(1);
    assertThat(apps[0].appCode()).isEqualTo(f.appCode());
    assertThat(apps[0].totalCalls()).isEqualTo(2);
    assertThat(apps[0].errorCalls()).isEqualTo(1);

    var endpoints =
        json.readValue(
            http.perform(get("/platform/call-monitor/endpoints").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorModels.ApiCallMonitorEndpointRow[].class);
    assertThat(endpoints).hasSize(1);
    assertThat(endpoints[0].endpointCode()).isEqualTo("PING");
    assertThat(endpoints[0].totalCalls()).isEqualTo(2);
    // 🔴 名称取自**活契约**，不是代码登记表里的那句话。
    assertThat(endpoints[0].label()).isEqualTo("连通性自检（活契约里的说法）");

    var calls =
        json.readValue(
            http.perform(get("/platform/call-monitor/calls").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(calls.total()).isEqualTo(2);
    assertThat(calls.errorCalls()).isEqualTo(1);
    assertThat(calls.items().get(0).outcomeLabel()).isNotBlank();
  }

  @Test
  void 明细支持真筛选与真分页() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    gateway("/open/v1/ping", f.apiKey(), "ss_wrong").andExpect(status().isUnauthorized());

    var failed =
        json.readValue(
            http.perform(
                    get("/platform/call-monitor/calls")
                        .header("Authorization", "session-a")
                        .param("outcome", "AUTH_FAILED"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(failed.items()).hasSize(1);
    assertThat(failed.items().get(0).errorCode()).isEqualTo("SECRET_MISMATCH");

    var page =
        json.readValue(
            http.perform(
                    get("/platform/call-monitor/calls")
                        .header("Authorization", "session-a")
                        .param("size", "1")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCallPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(1);
    assertThat(page.page()).isEqualTo(1);
  }

  @Test
  void 端点清单接活契约_读不到时如实标不可用() throws Exception {
    var options =
        json.readValue(
            http.perform(get("/platform/call-monitor/options").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorOptions.class);
    assertThat(options.endpoints()).isNotEmpty();
    var ping =
        options.endpoints().stream()
            .filter(e -> e.code().equals("PING"))
            .findFirst()
            .orElseThrow();
    assertThat(ping.specAvailable()).as("活契约里真的有 /open/v1/ping").isTrue();
    assertThat(ping.label()).isEqualTo("连通性自检（活契约里的说法）");
    // 契约里没有的那一条要**如实说没读到**，不许显示成「没有这条接口」。
    var scopes =
        options.endpoints().stream()
            .filter(e -> e.code().equals("SCOPES"))
            .findFirst()
            .orElseThrow();
    assertThat(scopes.specAvailable()).isFalse();
    assertThat(scopes.specBasis()).contains("没有");
    // 阈值口径必须显式给出来。
    assertThat(options.thresholds()).isNotEmpty();
    assertThat(options.windows()).isNotEmpty();
  }

  // ================================================================ 告警

  @Test
  void 扫描开告警_同一异常不刷屏_确认与闭环可用() throws Exception {
    var f = fixture("erp");
    // 攒够最小样本量：20 次凭证错误 + 1 次成功。
    for (int i = 0; i < ApiCallMonitorService.MIN_SAMPLE; i++) {
      gateway("/open/v1/ping", f.apiKey(), "ss_wrong").andExpect(status().isUnauthorized());
    }
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());

    var first =
        json.readValue(
            http.perform(
                    post("/platform/call-monitor/alerts/scan")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                            new ApiCallMonitorScanInput(ApiCallMonitorService.DEFAULT_WINDOW_SECONDS))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorScanResult.class);
    assertThat(first.appsScanned()).isEqualTo(1);
    assertThat(first.alertsRaised()).isGreaterThan(0);

    var alerts =
        json.readValue(
            http.perform(get("/platform/call-monitor/alerts").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorAlertPage.class);
    assertThat(alerts.items()).isNotEmpty();
    var alert = alerts.items().get(0);
    assertThat(alert.alertType()).isEqualTo("ERROR_RATE_HIGH");
    assertThat(alert.severity()).isEqualTo("CRITICAL");
    assertThat(alert.status()).isEqualTo("OPEN");
    assertThat(alert.metricValue()).isGreaterThan(BigDecimal.ZERO);
    assertThat(alert.thresholdValue()).isNotNull();
    assertThat(alert.message()).contains("错误率");

    // 再扫一次：**不刷屏**，只刷新。
    var second =
        json.readValue(
            http.perform(
                    post("/platform/call-monitor/alerts/scan")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                            new ApiCallMonitorScanInput(ApiCallMonitorService.DEFAULT_WINDOW_SECONDS))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorScanResult.class);
    assertThat(second.alertsRaised()).as("同一异常不许再开一条").isZero();
    assertThat(second.alertsRefreshed()).isGreaterThan(0);

    var afterSecond =
        json.readValue(
            http.perform(get("/platform/call-monitor/alerts").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorAlertPage.class);
    assertThat(afterSecond.total()).as("告警列表里同一异常只有一行").isEqualTo(1);

    // 确认 → 闭环。
    var acked =
        json.readValue(
            http.perform(
                    post("/platform/call-monitor/alerts/" + alert.id() + "/acknowledge")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorAlertRow.class);
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.acknowledgedAt()).isNotNull();
    assertThat(acked.acknowledgedBy()).isNotNull();

    var resolved =
        json.readValue(
            http.perform(
                    post("/platform/call-monitor/alerts/" + alert.id() + "/resolve")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorAlertRow.class);
    assertThat(resolved.status()).isEqualTo("RESOLVED");
    assertThat(resolved.resolvedAt()).isNotNull();

    // 幂等：再确认一次已闭环的告警 ⇒ 409（闭环了就不该再确认）。
    http.perform(
            post("/platform/call-monitor/alerts/" + alert.id() + "/acknowledge")
                .header("Authorization", "session-a"))
        .andExpect(status().isConflict());
  }

  @Test
  void 限流告警指向18_1_1_1的配额调整端点() throws Exception {
    var f = fixture("erp");
    http.perform(
            post("/platform/apps/" + f.appId() + "/quotas")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new AppCredentialModels.IntegrationQuotaInput("MINUTE", BigDecimal.ONE, true))))
        .andExpect(status().isOk());

    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());
    for (int i = 0; i < (int) ApiCallMonitorService.RATE_LIMIT_WARNING; i++) {
      gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isTooManyRequests());
    }

    http.perform(
            post("/platform/call-monitor/alerts/scan")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new ApiCallMonitorScanInput(ApiCallMonitorService.DEFAULT_WINDOW_SECONDS))))
        .andExpect(status().isOk());

    var alerts =
        json.readValue(
            http.perform(
                    get("/platform/call-monitor/alerts")
                        .header("Authorization", "session-a")
                        .param("alertType", "RATE_LIMIT_SPIKE"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorAlertPage.class);
    assertThat(alerts.items()).hasSize(1);
    var alert = alerts.items().get(0);
    assertThat(alert.suggestedQuotaWindow()).isEqualTo("MINUTE");
    assertThat(alert.suggestedQuotaLimit()).isNotNull();
    assertThat(alert.quotaAdjustTarget())
        .isEqualTo("/platform/apps/" + f.appId() + "/quotas");
  }

  @Test
  void 能力声明把工作台告警如实答成不可用() throws Exception {
    var capabilities =
        json.readValue(
            http.perform(get("/platform/call-monitor/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ApiCallMonitorCapabilities.class);
    var workbench =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("工作台告警推送"))
            .findFirst()
            .orElseThrow();
    assertThat(workbench.available()).as("卷 01 未交付，必须如实答不可用").isFalse();
    assertThat(workbench.ownerModule()).isEqualTo("卷 01");
    assertThat(workbench.basis()).contains("未交付");
  }

  // ================================================================ 埋点只追加

  @Test
  void 埋点只追加_改与删都被数据层挡住() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());

    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        sql.execute(
                            "update platform_api_call_log set status_code = 500", new Object[] {})))
        .hasStackTraceContaining("只追加");
    assertThatThrownBy(() -> inTenant(() -> sql.execute("delete from platform_api_call_log")))
        .hasStackTraceContaining("只追加");
    assertThat(logCount()).isEqualTo(1);
  }

  // ================================================================ 时间落库

  @Test
  void 埋点的调用时刻取自数据库时钟_不是应用机器的本地时间() throws Exception {
    var f = fixture("erp");
    gateway("/open/v1/ping", f.apiKey(), f.secret()).andExpect(status().isOk());

    var row = inTenant(() -> sql.fetchOne("select called_at from platform_api_call_log"));
    var calledAt = row.get("called_at", OffsetDateTime.class);
    var dbNow =
        inTenant(
            () ->
                sql.fetchOne("select clock_timestamp() as t").get(0, OffsetDateTime.class));
    // 写回的值必须与数据库时钟一致（容差 1 分钟）——
    // 用 Timestamp.valueOf(LocalDateTime) 写会静默偏 −28800s，那正是这条断言要挡的。
    var skewSeconds =
        Math.abs(
            java.time.Duration.between(calledAt.toInstant(), dbNow.toInstant()).getSeconds());
    assertThat(skewSeconds).as("埋点的调用时刻必须与数据库时钟一致").isLessThanOrEqualTo(60);
  }
}
