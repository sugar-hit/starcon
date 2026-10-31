package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.PaymentFundsModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
 * 18.2.2.1「支付与资金」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>本测试刻意跑在「服务商未接入」的真实实现上</b>（不注入任何测试替身）：
 * 未接入 ⇒ 状态 NOT_CONNECTED、<b>不写成功流水</b> —— 这条「不写」也必须在租户维度上钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentFundsTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的七张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_payment_provider",
          "platform_payment_operation",
          "platform_payment_attempt",
          "platform_payment_balance",
          "platform_fund_flow",
          "platform_payment_fee",
          "platform_payment_rate");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.payment.read",
          "platform.payment.create",
          "platform.payment.update",
          "platform.payment.execute",
          "platform.payment.sync");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-payment-" + tenant + "-" + ordinal).getBytes());
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

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    r.add("FINANCE_URL", () -> "http://127.0.0.1:1");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_payment_rate, platform_payment_fee, platform_fund_flow,"
              + " platform_payment_balance, platform_payment_attempt, platform_payment_operation,"
              + " platform_payment_provider restart identity cascade");
      s.execute("alter sequence platform_fund_flow_no_seq restart with 1");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一个服务商配置 + 一条资金流水 + 一条手续费 + 一条汇率。
   *
   * <p>🔴 资金流水带**真金额** —— 串租户意味着「A 的钱出现在 B 的界面上」，
   * 这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_payment_provider(id,tenant_id,provider_code,name,base_url,"
                  + " credential_ref,credential_ref_lookup,credential_hint,settlement_currency,status,"
                  + " timeout_seconds,max_attempts,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,'',?,?,?,'USD','ACTIVE',15,3,?,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "PAYPAL",
              "连接-" + marker,
              "secret://payments/paypal/" + marker,
              PaymentFundsService.sha256Hex("secret://payments/paypal/" + marker),
              "9f2a",
              "",
              null,
              now,
              now);
          sql.execute(
              "insert into platform_fund_flow(id,tenant_id,flow_no,provider_id,flow_type,direction,"
                  + " amount,currency,occurred_at,source,remark,created_by,created_at)"
                  + " values(?,?,?,?,'PAYMENT','OUTBOUND',?,'USD',?::timestamptz,'IMPORT',?,?,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              "FF-" + marker,
              idFor(tenant, 1),
              new BigDecimal("100.000000"),
              now,
              marker,
              null,
              now);
          sql.execute(
              "insert into platform_payment_fee(id,tenant_id,provider_id,fee_type,amount,currency,"
                  + " occurred_at,source,summary,created_by,created_at)"
                  + " values(?,?,?,?,?,'USD',?::timestamptz,'IMPORT',?,?,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              "FEE-" + marker,
              new BigDecimal("1.230000"),
              now,
              marker,
              null,
              now);
          sql.execute(
              "insert into platform_payment_rate(id,tenant_id,provider_id,base_currency,quote_currency,"
                  + " rate,source,inverted,observed_at,source_ref,created_by,created_at)"
                  + " values(?,?,?,'USD','CNY',?,'CATALOG',false,?::timestamptz,?,?,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              idFor(tenant, 1),
              new BigDecimal("7.123400"),
              now,
              marker,
              null,
              now);
          return null;
        });
  }

  /** 以指定租户身份读资金流水的备注（串租户意味着备注也会串）。 */
  List<String> flowRemarksOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select remark from platform_fund_flow order by flow_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读资金流水的金额。 */
  List<BigDecimal> flowAmountsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select amount from platform_fund_flow order by flow_no")
                .map(r -> r.get(0, BigDecimal.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_fund_flow").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的七张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V416 必须真的建出 " + table).isNotNull();
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

  // ==========================================================================
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(flowRemarksOf(a)).containsExactly("tenant-a");
    assertThat(flowRemarksOf(b)).containsExactly("tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select remark from platform_fund_flow where id = ?", idFor(b, 2))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 服务商配置同理。
    assertThat(
            tenants.run(
                a, () -> sql.fetch("select name from platform_payment_provider").size()))
        .isEqualTo(1);
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select id from platform_payment_provider where id = ?", idFor(b, 1))
                        .size()))
        .as("按主键直查对方的服务商配置也必须被挡")
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aFlows =
        json.readValue(
            http.perform(MockMvcRequestBuilders.get("/platform/payment-funds/flows").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FundFlowRow[].class);
    assertThat(aFlows).hasSize(1);
    assertThat(aFlows[0].remark()).isEqualTo("tenant-a");

    var bFlows =
        json.readValue(
            http.perform(MockMvcRequestBuilders.get("/platform/payment-funds/flows").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FundFlowRow[].class);
    assertThat(bFlows).hasSize(1);
    assertThat(bFlows[0].remark()).isEqualTo("tenant-b");

    // 🔴 A 直接按 B 的流水 id 取详情 ⇒ 取不到（B 的金额一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                MockMvcRequestBuilders.get("/platform/payment-funds/operations/" + idFor(b, 2))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_payment_provider(id,tenant_id,provider_code,name,base_url,"
                        + " credential_ref,credential_ref_lookup,credential_hint,settlement_currency,status,"
                        + " timeout_seconds,max_attempts,remark,created_by,created_at,updated_at)"
                        + " values(?,?,'PAYPAL','forged','','','','','USD','ACTIVE',15,3,'',"
                        + " ?,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    null,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select name from platform_payment_provider where name = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                MockMvcRequestBuilders.post("/platform/payment-funds/balances/import")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "providerId", idFor(a, 1).toString(),
                                "currency", "USD",
                                "balanceAmount", "42.00",
                                "observedAt", "2026-10-30T00:00:00Z"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 行落在 A 名下（不是伪造的 B）。
    assertThat(
            tenants.run(
                a, () -> sql.fetch("select id from platform_payment_balance").size()))
        .as("行必须落在**会话租户**名下")
        .isEqualTo(1);
    assertThat(
            tenants.run(
                b, () -> sql.fetch("select id from platform_payment_balance").size()))
        .as("伪造的头不能把行写进别的租户")
        .isZero();
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池固定为 1 条连接 ⇒ 每一次读写都复用**同一条**连接。
    for (int i = 0; i < 5; i++) {
      assertThat(flowRemarksOf(a)).containsExactly("tenant-a");
      assertThat(flowRemarksOf(b)).containsExactly("tenant-b");
      assertThat(flowAmountsOf(a)).containsExactly(new BigDecimal("100.000000"));
      assertThat(flowAmountsOf(b)).containsExactly(new BigDecimal("100.000000"));
    }
    // 交替读：连续两次读不同租户，中间不夹任何事务边界。
    assertThat(flowRemarksOf(a)).containsExactly("tenant-a");
    assertThat(flowRemarksOf(b)).containsExactly("tenant-b");
    assertThat(flowRemarksOf(a)).containsExactly("tenant-a");
  }

  // ==========================================================================
  // 附：未接入的服务商不得在任何租户名下留下成功痕迹
  // ==========================================================================
  @Test
  void 未接入的服务商在两个租户名下都不留成功流水() throws Exception {
    for (var session : List.of("session-a", "session-b")) {
      var response =
          http.perform(
                  MockMvcRequestBuilders.post("/platform/payment-funds/payments")
                      .header("Authorization", session)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(
                              java.util.Map.of(
                                  "providerId",
                                  idFor(session.equals("session-a") ? a : b, 1).toString(),
                                  "idempotencyKey",
                                  "idem-" + session,
                                  "reference",
                                  "PO-TENANCY",
                                  "currency",
                                  "USD",
                                  "amount",
                                  "10.00",
                                  "counterpartyName",
                                  "供应商"))))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).as("未接入 ⇒ 409").isEqualTo(409);
    }
    assertThat(flowAmountsOf(a)).as("未接入不许写成功流水").containsExactly(new BigDecimal("100.000000"));
    assertThat(flowAmountsOf(b)).containsExactly(new BigDecimal("100.000000"));
  }
}
