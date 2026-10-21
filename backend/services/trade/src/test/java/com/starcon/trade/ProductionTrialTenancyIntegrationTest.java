package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 6.6.2.1 试产单的**多租户隔离**复验。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>catalog 起本地 stub 服务</b>：BOM 版本在 catalog 独立库，本模块跨服务一律走端口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTrialTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of("trade.production-trial.read", "trade.production-trial.update");

  /** catalog 侧的 BOM 版本（stub；两个租户共用同一份 catalog 事实）。 */
  static final UUID bomVersionId = UUID.randomUUID();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/bom-versions",
          e -> {
            String path = e.getRequestURI().getPath();
            String query = e.getRequestURI().getQuery();
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("id", bomVersionId);
            version.put("bomId", UUID.randomUUID());
            version.put("bomCode", "BOM-TEN");
            version.put("outputSkuId", UUID.randomUUID());
            version.put("outputSkuCode", "SKU-TEN-001");
            version.put("outputSkuName", "折叠水杯");
            version.put("versionNo", "v1");
            version.put("name", "折叠水杯 v1");
            version.put("status", "PUBLISHED");
            version.put("statusLabel", "已发布");
            version.put("componentCount", 1);
            version.put("effective", true);
            if (path.equals("/catalog/bom-versions")) {
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "items", List.of(version), "page", 0, "size", 50, "total", 1,
                      "totalPages", 1));
              return;
            }
            String rest = path.substring("/catalog/bom-versions/".length());
            if (rest.endsWith("/requirement")) {
              BigDecimal outputQuantity = new BigDecimal("1");
              if (query != null) {
                for (String pair : query.split("&")) {
                  if (pair.startsWith("outputQuantity=")) {
                    outputQuantity = new BigDecimal(pair.substring("outputQuantity=".length()));
                  }
                }
              }
              BigDecimal required =
                  new BigDecimal("2.000000")
                      .multiply(outputQuantity)
                      .setScale(6, RoundingMode.HALF_UP);
              Map<String, Object> line = new LinkedHashMap<>();
              line.put("skuId", UUID.randomUUID());
              line.put("skuCode", "MAT-TEN-001");
              line.put("skuName", "杯盖");
              line.put("unitQuantity", new BigDecimal("2.000000"));
              line.put("scrapRate", BigDecimal.ZERO);
              line.put("requiredQuantity", required);
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("bomId", version.get("bomId"));
              body.put("versionId", bomVersionId);
              body.put("versionNo", "v1");
              body.put("outputSkuId", version.get("outputSkuId"));
              body.put("outputSkuCode", "SKU-TEN-001");
              body.put("outputQuantity", outputQuantity);
              body.put("lines", List.of(line));
              body.put("singleLevel", true);
              write(catalog, e, 200, body);
              return;
            }
            write(catalog, e, 200, Map.of("version", version));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate production_trial_mass_production_recommendation, production_trial_cost_line,"
              + " production_trial_cost, production_trial_result, production_trial_inspection_item,"
              + " production_trial_component_requirement, production_trial_order_transition,"
              + " production_trial_order cascade");
    }
  }

  // ================================================================ 工具

  /** 在指定租户下建一张试产单。 */
  String createTrial(String bearer, String suffix) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-" + suffix);
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
    String response =
        http.perform(
                post("/trade/trial-productions")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("order").get("id").asText();
  }

  String listJson(String bearer) throws Exception {
    return http.perform(get("/trade/trial-productions").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String idA = createTrial("Bearer tenant-a", "A-1");
    String idB = createTrial("Bearer tenant-b", "B-1");

    var rowsA = json.readTree(listJson("Bearer tenant-a"));
    var rowsB = json.readTree(listJson("Bearer tenant-b"));
    assertThat(rowsA.get("total").asLong()).as("A 只看得到自己那条").isEqualTo(1);
    assertThat(rowsB.get("total").asLong()).as("B 只看得到自己那条").isEqualTo(1);
    assertThat(rowsA.get("rows").get(0).get("trialNo").asText()).isEqualTo("TP-A-1");
    assertThat(rowsB.get("rows").get(0).get("trialNo").asText()).isEqualTo("TP-B-1");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/trial-productions/" + idB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 组件领用需求同样按租户隔离：A 连 B 的单子都读不到（404），更别说它的需求行。
    http.perform(
            get("/trade/trial-productions/" + idB + "/component-requirements")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 成本视图同样按租户隔离：A 看 B 的单子拿不到成本头。
    String costBody =
        http.perform(
                get("/trade/trial-productions/" + idB + "/cost")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(costBody).as("A 不该看到 B 的成本").doesNotContain("totalAmount");
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    createTrial("Bearer tenant-a", "A-2");
    createTrial("Bearer tenant-b", "B-2");

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = listJson("Bearer tenant-a");
    String forged =
        http.perform(
                get("/trade/trial-productions")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(json.readTree(forged).get("rows").get(0).get("trialNo").asText())
        .isEqualTo("TP-A-2");

    // 伪造头也不能让写入落到别的租户。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-FORGED");
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
    http.perform(
            post("/trade/trial-productions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    http.perform(get("/trade/trial-productions").header("Authorization", "Bearer tenant-b"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].trialNo").value("TP-B-2"));

    // (2) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (3) 事务内绑定的值精确等于可信参数（来自已认证成员关系），提交后立刻失效。
    var bound =
        access.run(
            actors.get("Bearer tenant-b"),
            "trade.production-trial.read",
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    createTrial("Bearer tenant-a", "A-3");
    createTrial("Bearer tenant-b", "B-3");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = json.readTree(listJson("Bearer tenant-a"));
      var rowsB = json.readTree(listJson("Bearer tenant-b"));
      assertThat(rowsA.get("total").asLong()).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(rowsB.get("total").asLong()).as("第 %d 轮租户 B", i).isEqualTo(1);
      assertThat(rowsA.get("rows").get(0).get("trialNo").asText()).isEqualTo("TP-A-3");
      assertThat(rowsB.get("rows").get(0).get("trialNo").asText()).isEqualTo("TP-B-3");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(json.readTree(listJson("Bearer tenant-a")).get("rows").get(0).get("trialNo").asText())
        .isEqualTo("TP-A-3");
    assertThat(json.readTree(listJson("Bearer tenant-b")).get("rows").get(0).get("trialNo").asText())
        .isEqualTo("TP-B-3");
  }

  // ================================================================ 工具

  /** 当前连接上 {@code app.current_tenant} 的**原始**值。 */
  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from production_trial_order").size();
  }
}
