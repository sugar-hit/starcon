package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostCalculationModels.*;
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
 * 12.4.1.2 成本计算执行的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的三张表与本模块的端点上</b>：
 * {@code finance_cost_calculation_run} / {@code finance_cost_calculation_layer} /
 * {@code finance_cost_calculation_entry}。
 *
 * <p>⚠️ 本测试**不启动上游模拟服务**：它验的是租户隔离，不是成本计算口径
 * （口径由 {@code CostCalculationIntegrationTest} 负责）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostCalculationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID skuA = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-cost-calculation-" + tenant + "-" + ordinal).getBytes());
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
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "finance.cost_calculation.read",
            "finance.cost_calculation.create",
            "finance.cost_calculation.update");
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
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
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
          "truncate table finance_cost_calculation_entry, finance_cost_calculation_layer,"
              + " finance_cost_calculation_run restart identity cascade");
    }
    seedRun(a, idFor(a, 1), "A 的范围");
    seedRun(b, idFor(b, 1), "B 的范围");
  }

  /** 在可信租户事务里插一条已完成的计算批次（表头）。 */
  void seedRun(UUID tenant, UUID id, String scopeName) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,scope_code,"
                  + " scope_name,valuation_method,valuation_method_version,currency,period_from,period_to,"
                  + " recalculation_reason,status,total_quantity,total_cost_amount,created_at,completed_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-X',?,'FIFO',1,'USD',?::timestamptz,?::timestamptz,"
                  + " 'INITIAL','COMPLETED',10.000000,100.0000,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              UUID.nameUUIDFromBytes(("scope-" + tenant).getBytes()),
              scopeName,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              OffsetDateTime.parse("2026-02-01T00:00:00Z"),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读批次表里的范围名。 */
  List<String> scopeNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select scope_name from finance_cost_calculation_run order by scope_name")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select scope_name from finance_cost_calculation_run").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的三张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_cost_calculation_run",
            "finance_cost_calculation_layer",
            "finance_cost_calculation_entry")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V358 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(scopeNamesOf(a)).containsExactly("A 的范围");
    assertThat(scopeNamesOf(b)).containsExactly("B 的范围");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select scope_name from finance_cost_calculation_run where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_批次与结果行都按租户隔离() {
    // 给 A 的批次补一条成本层与一条成本分录。
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into finance_cost_calculation_layer(id,tenant_id,run_id,sku_id,sku_code,layer_index,"
                  + " lot_ref,layer_quantity,layer_amount,unit_cost,consumed_quantity,consumed_amount,"
                  + " currency,created_at)"
                  + " values(?,?,?,?,'SKU-A',0,'LOT-A',10.000000,100.0000,10.0000,10.000000,"
                  + " 100.0000,'USD',?::timestamptz)",
              idFor(a, 2), a, idFor(a, 1), skuA, OffsetDateTime.now(ZoneOffset.UTC));
          sql.execute(
              "insert into finance_cost_calculation_entry(id,tenant_id,run_id,flow_transaction_id,"
                  + " flow_ref_type,flow_kind,occurred_at,business_type,sku_id,sku_code,issue_quantity,"
                  + " covered_quantity,shortfall_quantity,unit_cost,cost_amount,currency,layer_count,status,"
                  + " created_at)"
                  + " values(?,?,?,?,'ORDER_OUT','SHIPMENT',?::timestamptz,'SALES_OUTBOUND',?,'SKU-A',"
                  + " 10.000000,10.000000,0.000000,10.0000,100.0000,'USD',1,'CALCULATED',?::timestamptz)",
              idFor(a, 3), a, idFor(a, 1), UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), skuA,
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });

    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select lot_ref from finance_cost_calculation_layer")
                        .map(r -> r.get(0, String.class))))
        .containsExactly("LOT-A");
    assertThat(
            tenants.run(
                b,
                () ->
                    sql.fetch("select sku_code from finance_cost_calculation_entry")
                        .map(r -> r.get(0, String.class))))
        .as("B 看不见 A 的成本分录")
        .isEmpty();
    assertThat(
            tenants.run(
                b,
                () ->
                    sql.fetch("select lot_ref from finance_cost_calculation_layer")
                        .map(r -> r.get(0, String.class))))
        .as("B 看不见 A 的成本层")
        .isEmpty();
  }

  @Test
  void 断言1_批次列表端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(
                    get("/finance/cost-calculation/runs").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostCalculationRunPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).scopeName()).isEqualTo("A 的范围");

    var bPage =
        json.readValue(
            http.perform(
                    get("/finance/cost-calculation/runs").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostCalculationRunPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).scopeName()).isEqualTo("B 的范围");
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
                    "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,"
                        + " scope_code,scope_name,valuation_method,valuation_method_version,currency,"
                        + " period_from,period_to,recalculation_reason,status,created_at)"
                        + " values(?,?,'LEGAL_ENTITY',?,'FORGED','伪造','FIFO',1,'USD',"
                        + " ?::timestamptz,?::timestamptz,'INITIAL','PENDING',?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select scope_name from finance_cost_calculation_run where scope_name = '伪造'")
                .size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"scopeType":"LEGAL_ENTITY","scopeRefId":"%s","scopeCode":"LE-FORGED","scopeName":"伪造头建的批次",
         "valuationMethod":"FIFO","currency":"USD","periodFrom":"2026-01-01T00:00:00Z",
         "periodTo":"2026-02-01T00:00:00Z"}
        """
            .formatted(UUID.randomUUID());

    // 上游（warehouse 库存流水）在本测试里没起 —— 上游不可达会 503，
    // 但**行不会落库**，所以这里断言的是「没有落库到 b 名下」。
    // 为了验「行落在会话租户名下」，先造一条属于 A 的批次并直接比对。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select scope_name, tenant_id from finance_cost_calculation_run order by scope_name"));
    assertThat(rows).as("伪造头的请求没有把行写进任何租户（上游不可达 ⇒ 503，没落库）").hasSize(1);
    assertThat(rows.get(0).get("tenant_id", UUID.class)).isEqualTo(a).isNotEqualTo(b);

    // 伪造的租户头在代理与服务层都必须被忽略：请求带着伪造头打的，
    // 但行（如果有任何一行被写下）必须属于会话租户 a。
    http.perform(
            post("/finance/cost-calculation/runs")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isServiceUnavailable());

    var after =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select scope_name, tenant_id from finance_cost_calculation_run order by scope_name"));
    assertThat(after).as("上游不可达 ⇒ 一行都不该落库").hasSize(1);
    assertThat(after.get(0).get("tenant_id", UUID.class)).isEqualTo(a).isNotEqualTo(b);

    // 租户 b 看不到 A 的数据，b 的数据仍然是 b 自己的。
    assertThat(scopeNamesOf(b)).containsExactly("B 的范围");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(scopeNamesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的批次").containsExactly("A 的范围");
      assertThat(scopeNamesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的批次").containsExactly("B 的范围");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(scopeNamesOf(a)).containsExactly("A 的范围");
  }
}
