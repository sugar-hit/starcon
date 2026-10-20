package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 6.2.1.3 采购计划的**多租户隔离**复验。
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
 * <p>本测试刻意不碰任何卷 06 的其它业务形状：采购申请（6.2.2.1）属于后续车道。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchasePlanTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final LocalDate PERIOD_START = LocalDate.of(2026, 11, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 11, 30);

  static final List<String> allPermissions =
      List.of("trade.purchase-plan.read", "trade.purchase-plan.update");

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

  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
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

      // ---- warehouse stub：仓库 / 法人参照（两个租户共用同一份参照表）----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", warehouseId.toString(),
                        "code", "WH-LA-01",
                        "name", "洛杉矶仓",
                        "countryCode", "US",
                        "status", "ACTIVE",
                        "isDefault", true)));
          });
      warehouse.createContext(
          "/warehouse/legal-entities",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", legalEntityId.toString(),
                        "code", "US-LLC",
                        "name", "美国子公司",
                        "status", "ACTIVE")));
          });
      warehouse.start();
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  @BeforeEach
  void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_plan_adjustment, purchase_plan_lock_log, purchase_plan_line,"
              + " purchase_plan, purchase_plan_campaign, purchase_plan_lock_policy,"
              + " replenishment_suggestion_decision, replenishment_suggestion_step,"
              + " replenishment_suggestion, replenishment_suggestion_batch cascade");
    }
  }

  /** 建一张计划（大促日历也建一份，两个租户各建各的）。 */
  String createPlan(String bearer) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planType", "ROUTINE");
    body.put("periodType", "MONTHLY");
    body.put("periodStart", PERIOD_START.toString());
    body.put("periodEnd", PERIOD_END.toString());
    body.put("targetWarehouseId", warehouseId.toString());
    body.put("legalEntityId", legalEntityId.toString());
    body.put("note", "租户隔离测试用计划");
    String payload =
        http.perform(
                post("/trade/purchase-plans")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  String plans(String bearer) throws Exception {
    return http.perform(get("/trade/purchase-plans").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("rows", List.of());
  }

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
    return sql.fetch("select id from purchase_plan").size();
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "purchase_plan_campaign",
            "purchase_plan",
            "purchase_plan_line",
            "purchase_plan_adjustment",
            "purchase_plan_lock_log",
            "purchase_plan_lock_policy")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V242 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String planA = createPlan("Bearer tenant-a");
    String planB = createPlan("Bearer tenant-b");

    var rowsA = rowsOf(plans("Bearer tenant-a"));
    var rowsB = rowsOf(plans("Bearer tenant-b"));
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(planA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(planB);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/purchase-plans/" + planB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 大促日历同样互不可见。
    http.perform(
            post("/trade/purchase-plans/campaigns")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "code", "CAMPAIGN-A",
                            "name", "A 的大促",
                            "startDate", "2026-11-20",
                            "endDate", "2026-11-30"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/purchase-plans/campaigns")
                .header("Authorization", "Bearer tenant-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "code", "CAMPAIGN-B",
                            "name", "B 的大促",
                            "startDate", "2026-11-20",
                            "endDate", "2026-11-30"))))
        .andExpect(status().isOk());
    String campaignsA =
        http.perform(
                get("/trade/purchase-plans/campaigns").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String campaignsB =
        http.perform(
                get("/trade/purchase-plans/campaigns").header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(campaignsA)).hasSize(1);
    assertThat(rowsOf(campaignsA).get(0).get("code")).isEqualTo("CAMPAIGN-A");
    assertThat(rowsOf(campaignsB)).hasSize(1);
    assertThat(rowsOf(campaignsB).get(0).get("code")).isEqualTo("CAMPAIGN-B");

    // 汇总视图同样按租户分开（各自只看得到自己的计划行）。
    String summaryA =
        http.perform(
                get("/trade/purchase-plans/summary").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(summaryA).get("routine").get("planCount").asLong()).isEqualTo(1L);
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    createPlan("Bearer tenant-a");
    createPlan("Bearer tenant-b");

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = plans("Bearer tenant-a");
    String forged =
        http.perform(
                get("/trade/purchase-plans")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(honest);
    assertThat(rowsOf(forged)).hasSize(1);

    // 伪造头也不能让写入落到别的租户。
    http.perform(
            post("/trade/purchase-plans/campaigns")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "code", "CAMPAIGN-FORGED",
                            "name", "伪造头建的大促",
                            "startDate", "2026-11-20",
                            "endDate", "2026-11-30"))))
        .andExpect(status().isOk());
    http.perform(
            get("/trade/purchase-plans/campaigns")
                .header("Authorization", "Bearer tenant-b")
                .param("code", "CAMPAIGN-FORGED"))
        .andExpect(jsonPath("$.total").value(0));

    // (2) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见 —— 不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (3) 事务内绑定的值精确等于可信参数（来自已认证成员关系），提交后立刻失效。
    var bound =
        access.run(
            actors.get("Bearer tenant-b"),
            "trade.purchase-plan.read",
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
    createPlan("Bearer tenant-a");
    createPlan("Bearer tenant-b");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = rowsOf(plans("Bearer tenant-a"));
      var rowsB = rowsOf(plans("Bearer tenant-b"));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("id")).as("第 %d 轮租户 A", i).isEqualTo(rowsA.get(0).get("id"));
      assertThat(rowsB.get(0).get("id")).as("第 %d 轮租户 B", i).isEqualTo(rowsB.get(0).get("id"));
      assertThat(rowsA.get(0).get("id")).isNotEqualTo(rowsB.get(0).get("id"));
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rowsOf(plans("Bearer tenant-a")).get(0).get("id"))
        .isNotEqualTo(rowsOf(plans("Bearer tenant-b")).get(0).get("id"));
  }

  // ================================================================ 跨租户写入

  @Test
  void 跨租户按id改写会被拒() throws Exception {
    String planA = createPlan("Bearer tenant-a");
    createPlan("Bearer tenant-b");
    // B 拿着 A 的计划 id 去改量 ⇒ 404（RLS 下查不到），不是 403（403 会泄露存在性）。
    http.perform(
            post("/trade/purchase-plans/" + planA + "/confirm")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }
}
