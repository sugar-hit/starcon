package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * 18.3.3.1「自动审批」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>决策依据也在断言范围内</b>：决策台账里存着**当时的规则快照与风险信号** ——
 * 串租户意味着「A 的单据依据出现在 B 的台账里」，那不只是看到了别人的数据，
 * 还等于让人拿 A 的规则去解释 B 的单据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoApprovalTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_auto_approval_policy",
          "platform_auto_approval_signal",
          "platform_auto_approval_decision",
          "platform_auto_approval_escalation");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.auto_approval.read",
          "platform.auto_approval.create",
          "platform.auto_approval.update",
          "platform.auto_approval.decide");

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    actors.put("session-a", actor(UUID.randomUUID(), a, PERMISSIONS));
    actors.put("session-b", actor(UUID.randomUUID(), b, PERMISSIONS));
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-auto-approval-store-tenancy");
  }

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_auto_approval_escalation, platform_auto_approval_decision,"
              + " platform_auto_approval_signal, platform_auto_approval_policy cascade");
      s.execute("alter sequence platform_auto_approval_decision_no_seq restart with 1");
    }
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 登记一条规则 + 一条信号 + 一次判定（自动通过，不碰上游）。 */
  void seed(String session, String marker) throws Exception {
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "RULE-" + marker,
                            "ruleName", marker + " 的规则",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", new BigDecimal("1000"),
                            "amountHardLimit", new BigDecimal("10000"),
                            "status", "ACTIVE"))))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/auto-approvals/signals")
                .header("Authorization", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "signalCode", "SIGNAL-" + marker,
                            "signalName", marker + " 的信号",
                            "signalKind", "RISK",
                            "description", ""))))
        .andExpect(status().isOk());
    http.perform(
            post("/platform/auto-approvals/decisions")
                .header("Authorization", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "documentTypeCode", "PURCHASE_ORDER",
                            "documentRef", "PO-" + marker,
                            "subjectTitle", marker + " 的单据",
                            "sourceSystem", "trade",
                            "currencyCode", "USD",
                            "amount", new BigDecimal("100.00"),
                            "supplierVerified", true,
                            "riskSignals", List.of()))))
        .andExpect(status().isOk());
  }

  /** 以指定租户身份读决策单据号。 */
  List<String> decisionRefsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select document_ref from platform_auto_approval_decision order by document_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读规则编码。 */
  List<String> ruleCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select rule_code from platform_auto_approval_policy order by rule_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读信号码。 */
  List<String> signalCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select signal_code from platform_auto_approval_signal order by signal_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_auto_approval_decision").size();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    seed("session-a", "tenant-a");
    seed("session-b", "tenant-b");

    assertThat(decisionRefsOf(a)).containsExactly("PO-tenant-a").doesNotContain("PO-tenant-b");
    assertThat(decisionRefsOf(b)).containsExactly("PO-tenant-b").doesNotContain("PO-tenant-a");
    assertThat(ruleCodesOf(a)).containsExactly("RULE-tenant-a").doesNotContain("RULE-tenant-b");
    assertThat(ruleCodesOf(b)).containsExactly("RULE-tenant-b").doesNotContain("RULE-tenant-a");
    assertThat(signalCodesOf(a)).containsExactly("SIGNAL-tenant-a");
    assertThat(signalCodesOf(b)).containsExactly("SIGNAL-tenant-b");

    // 事务外一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    seed("session-a", "tenant-a");
    seed("session-b", "tenant-b");

    var aPolicies =
        json.readTree(
            http.perform(get("/platform/auto-approvals/policies").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(aPolicies).hasSize(1);
    assertThat(aPolicies.get(0).get("ruleCode").asText()).isEqualTo("RULE-tenant-a");

    var bDecisions =
        json.readTree(
            http.perform(get("/platform/auto-approvals/decisions").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(bDecisions).hasSize(1);
    assertThat(bDecisions.get(0).get("documentRef").asText()).isEqualTo("PO-tenant-b");
  }

  @Test
  void 断言1_按主键直查对方的决策也拿不到() throws Exception {
    seed("session-a", "tenant-a");
    seed("session-b", "tenant-b");
    var bDecisionId =
        json.readTree(
                http.perform(get("/platform/auto-approvals/decisions").header("Authorization", "session-b"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get(0)
            .get("id")
            .asText();

    var crossTenant =
        http.perform(
                get("/platform/auto-approvals/decisions/" + bDecisionId)
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString())
        .doesNotContain("PO-tenant-b")
        .doesNotContain("tenant-b 的单据");
  }

  @Test
  void 断言1_决策依据里的规则快照也不串租户() throws Exception {
    seed("session-a", "tenant-a");
    seed("session-b", "tenant-b");
    var bDecisionId =
        json.readTree(
                http.perform(get("/platform/auto-approvals/decisions").header("Authorization", "session-b"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get(0)
            .get("id")
            .asText();
    var crossTenant =
        http.perform(
                get("/platform/auto-approvals/decisions/" + bDecisionId)
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    // 🔴 依据快照里带的是**对方规则的 ruleCode** ⇒ 那就等于把对方的内控口径端过来了。
    assertThat(crossTenant.getContentAsString()).doesNotContain("RULE-tenant-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 拿 A 的会话，却声称自己是 B。
    http.perform(
            post("/platform/auto-approvals/policies")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "ruleCode", "FORGED",
                            "ruleName", "伪造租户登记的规则",
                            "documentTypeCode", "PURCHASE_ORDER",
                            "currencyCode", "USD",
                            "autoApproveMaxAmount", new BigDecimal("1000"),
                            "status", "ACTIVE"))))
        .andExpect(status().isOk());

    // 🔴 行确实落在**会话租户**名下（不是只断言「B 看不见」——
    //   一句「写入被全表拒掉」也能骗过那种断言）。
    assertThat(ruleCodesOf(a)).contains("FORGED");
    assertThat(ruleCodesOf(b)).doesNotContain("FORGED");
    assertThat(visibleWithoutTenant()).isZero();
  }

  @Test
  void 断言2_伪造租户上下文也不能把决策写进别人的租户() throws Exception {
    http.perform(
            post("/platform/auto-approvals/decisions")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        map(
                            "documentTypeCode", "PURCHASE_ORDER",
                            "documentRef", "PO-FORGED",
                            "sourceSystem", "trade",
                            "currencyCode", "USD",
                            "amount", new BigDecimal("100.00"),
                            "supplierVerified", true,
                            "riskSignals", List.of()))))
        .andExpect(status().isOk());
    assertThat(decisionRefsOf(a)).contains("PO-FORGED");
    assertThat(decisionRefsOf(b)).doesNotContain("PO-FORGED");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    seed("session-a", "tenant-a");
    seed("session-b", "tenant-b");
    // 池固定 1 条连接：交替 5 轮，每一轮都必须只看见自己那一份。
    for (int i = 0; i < 5; i++) {
      assertThat(decisionRefsOf(a)).as("第 " + i + " 轮 A 串了租户").containsExactly("PO-tenant-a");
      assertThat(decisionRefsOf(b)).as("第 " + i + " 轮 B 串了租户").containsExactly("PO-tenant-b");
      assertThat(ruleCodesOf(a)).containsExactly("RULE-tenant-a");
      assertThat(ruleCodesOf(b)).containsExactly("RULE-tenant-b");
    }
  }

  // ==========================================================================
  // 表本身的隔离事实
  // ==========================================================================
  @Test
  void 四张表都强制rls且带not_null租户列() {
    for (var table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V420 必须真的建出 " + table).isNotNull();
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
                  "select is_nullable from information_schema.columns"
                      + " where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 金额列一律numeric不用浮点() {
    for (var table : TABLES) {
      var floats =
          sql.fetch(
              "select column_name, data_type from information_schema.columns"
                  + " where table_name = ? and (data_type like '%real%' or data_type like '%double%')",
              table);
      assertThat(floats).as(table + " 不该有浮点列").isEmpty();
    }
    var amountType =
        sql.fetchOne(
                "select data_type from information_schema.columns"
                    + " where table_name = 'platform_auto_approval_decision' and column_name = 'amount'")
            .get(0, String.class);
    assertThat(amountType).isEqualTo("numeric");
  }

  @Test
  void 时间列一律timestamptz() {
    var types =
        sql.fetch(
            "select column_name, data_type from information_schema.columns"
                + " where table_name in ('platform_auto_approval_decision','platform_auto_approval_escalation')"
                + "   and (column_name like '%_at' or column_name = 'decided_at')");
    for (var row : types) {
      assertThat(row.get(1, String.class)).as(row.get(0, String.class) + " 必须是 timestamptz")
          .isEqualTo("timestamp with time zone");
    }
  }
}
