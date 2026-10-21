package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 6.5.1.1 采购对账的**多租户隔离**复验。
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
 * <p><b>直查断言用应用账号（trade_app）连</b>：用超级用户会绕过 RLS，断言本身失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseReconciliationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID supplierA = UUID.randomUUID(), supplierB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-10T08:00:00Z");

  static final List<String> allPermissions =
      List.of("trade.purchase-reconciliation.read", "trade.purchase-reconciliation.update");

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

  static void exec(Connection c, String sql, Object... params) throws Exception {
    try (var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      ps.execute();
    }
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  @BeforeEach
  void seed() throws Exception {
    try (Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      exec(
          c,
          "truncate purchase_reconciliation_event, purchase_reconciliation_confirmation,"
              + " purchase_reconciliation_discrepancy, purchase_reconciliation_line,"
              + " purchase_reconciliation cascade");
      exec(
          c,
          "truncate purchase_arrival_event, purchase_arrival_payable_confirmation,"
              + " purchase_arrival_receipt, purchase_arrival_tracking cascade");
      exec(c, "truncate quality_supplier_ref cascade");
      seedTenant(c, a, supplierA, "SUP-A", "A 供应商");
      seedTenant(c, b, supplierB, "SUP-B", "B 供应商");
    }
  }

  /** 给一个租户铺一份「到货流水」事实（应收基数的来源）。 */
  void seedTenant(Connection c, UUID tenant, UUID supplier, String code, String name)
      throws Exception {
    exec(
        c,
        "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
            + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,'TENANT_MAINTAINED','TEST','ACTIVE','',?::uuid,"
            + "?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        supplier,
        code,
        name,
        user,
        T1,
        T1);
    UUID trackingId = UUID.randomUUID();
    exec(
        c,
        "insert into purchase_arrival_tracking(id,tenant_id,tracking_no,purchase_order_no,"
            + "source_doc_type,supplier_ref_id,supplier_code,supplier_name,receivable_qty,"
            + "received_qty,outstanding_qty,unit_price,currency,promised_arrival_date,"
            + "promise_source,arrival_status,created_by,created_at,updated_at,version)"
            + " values(?::uuid,?::uuid,?,?,'PURCHASE_ORDER',?::uuid,?,?,100,0,100,?::numeric,'USD',"
            + "'2026-11-05','手工登记','PENDING_SHIPMENT',?::uuid,?::timestamptz,?::timestamptz,0)",
        trackingId,
        tenant,
        "TRK-" + code,
        "PO-" + code,
        supplier,
        code,
        name,
        new BigDecimal("2.5000"),
        user,
        T1,
        T1);
    exec(
        c,
        "insert into purchase_arrival_receipt(id,tenant_id,tracking_id,arrived_qty,arrived_at,"
            + "recorded_by,note) values(?::uuid,?::uuid,?::uuid,?::numeric,?::timestamptz,?::uuid,'首批到货')",
        UUID.randomUUID(),
        tenant,
        trackingId,
        new BigDecimal("40"),
        T1,
        user);
  }

  // ================================================================ 辅助

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("rows", List.of());
  }

  String createStatement(String bearer, UUID supplier) throws Exception {
    var response =
        http.perform(
                post("/trade/purchase-reconciliations")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "supplierRefId", supplier.toString(),
                                "periodType", "MONTHLY",
                                "periodStart", "2026-11-01",
                                "statementCurrency", "USD"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Map<String, Object> parsed = json.readValue(response.getContentAsString(), Map.class);
    return String.valueOf(((Map<String, Object>) parsed.get("head")).get("id"));
  }

  String listStatements(String bearer) throws Exception {
    return http.perform(get("/trade/purchase-reconciliations").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
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
    return sql.fetch("select id from purchase_reconciliation").size();
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "purchase_reconciliation",
            "purchase_reconciliation_line",
            "purchase_reconciliation_discrepancy",
            "purchase_reconciliation_confirmation",
            "purchase_reconciliation_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V250 必须真的建出 %s 这张表", table).isNotNull();
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
    String reconA = createStatement("Bearer tenant-a", supplierA);
    String reconB = createStatement("Bearer tenant-b", supplierB);

    var rowsA = rowsOf(listStatements("Bearer tenant-a"));
    var rowsB = rowsOf(listStatements("Bearer tenant-b"));
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(reconA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(reconB);
    assertThat(rowsA.get(0).get("supplierCode")).isEqualTo("SUP-A");
    assertThat(rowsB.get(0).get("supplierCode")).isEqualTo("SUP-B");

    // 更强的一格：A 明确按 ID 查 B 的单也拿不到（不只是列表过滤）。
    http.perform(get("/trade/purchase-reconciliations/" + reconB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 明细行与差异清单同样按租户分开。
    http.perform(
            get("/trade/purchase-reconciliations/" + reconB + "/lines")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/trade/purchase-reconciliations/" + reconB + "/discrepancies")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 写入口同样：B 拿着 A 的对账单 id 去发 ⇒ 404（RLS 下查不到），不是 403。
    http.perform(
            post("/trade/purchase-reconciliations/" + reconA + "/send")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    createStatement("Bearer tenant-a", supplierA);
    createStatement("Bearer tenant-b", supplierB);

    // (1) HTTP 边界上没有伪造入口：X-Tenant-Id 不改任何东西。
    String honest = listStatements("Bearer tenant-a");
    String forged =
        http.perform(
                get("/trade/purchase-reconciliations")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 逐行比较（不比较整份报文：报文里的 evaluatedAt 是「本次评估时刻」，两次调用本就不同）。
    assertThat(rowsOf(forged)).as("伪造 X-Tenant-Id 不得改变看到的那份清单").isEqualTo(rowsOf(honest));
    assertThat(rowsOf(forged)).hasSize(1);

    // 伪造头也不能让写入落到别的租户：带伪造头的写入仍然落在**自己**的租户名下。
    http.perform(
            post("/trade/purchase-reconciliations")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "supplierRefId", supplierA.toString(),
                            "periodType", "MONTHLY",
                            "periodStart", "2026-11-01",
                            "statementCurrency", "USD"))))
        .andExpect(status().isOk());
    var rowsA = rowsOf(listStatements("Bearer tenant-a"));
    var rowsB = rowsOf(listStatements("Bearer tenant-b"));
    assertThat(rowsA).as("伪造头的那次写入应落在 A 名下").hasSize(2);
    assertThat(rowsA).allMatch(r -> "SUP-A".equals(r.get("supplierCode")));
    assertThat(rowsB).as("B 不得看到 A 的写入").hasSize(1);
    assertThat(rowsB).allMatch(r -> "SUP-B".equals(r.get("supplierCode")));

    // (2) **在 RLS 上直接验证**：绑定 A 租户后，B 的行读不出来 ——
    //     即使**不带 tenant_id 谓词**也读不到，证明是真隔离不是应用层过滤。
    var bound =
        access.run(
            actors.get("Bearer tenant-a"),
            "trade.purchase-reconciliation.read",
            () -> {
              assertThat(effectiveTenant()).isEqualTo(a);
              var rows = sql.fetch("select id, supplier_code from purchase_reconciliation");
              assertThat(rows).as("不带 tenant_id 谓词也必须只看到本租户的行").isNotEmpty();
              assertThat(rows)
                  .as("不得看到别的租户的行（真隔离，不是应用层过滤）")
                  .allMatch(r -> "SUP-A".equals(r.get("supplier_code", String.class)));
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(a.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();

    // (3) 事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    createStatement("Bearer tenant-a", supplierA);
    createStatement("Bearer tenant-b", supplierB);

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var rowsA = rowsOf(listStatements("Bearer tenant-a"));
      var rowsB = rowsOf(listStatements("Bearer tenant-b"));
      assertThat(rowsA).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(rowsB).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(rowsA.get(0).get("id")).isEqualTo(rowsA.get(0).get("id"));
      assertThat(rowsB.get(0).get("id")).isEqualTo(rowsB.get(0).get("id"));
      assertThat(rowsA.get(0).get("id")).isNotEqualTo(rowsB.get(0).get("id"));
      assertThat(rowsA.get(0).get("supplierCode")).isEqualTo("SUP-A");
      assertThat(rowsB.get(0).get("supplierCode")).isEqualTo("SUP-B");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rowsOf(listStatements("Bearer tenant-a")).get(0).get("id"))
        .isNotEqualTo(rowsOf(listStatements("Bearer tenant-b")).get(0).get("id"));
  }
}
