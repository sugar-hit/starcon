package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.4.1.2 调整审批的**多租户隔离三断言**（独立成类，便于与其他模块的租户测试并列回归）。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>三条断言缺一不可（AGENTS.md「多租户」）：
 *
 * <ol>
 *   <li>两租户互不可见
 *   <li>伪造 tenant header 无效
 *   <li>连接池归还后不串租户
 * </ol>
 *
 * <p>「读到零行」每一条都配一条**同样写法的正对照**读到 1 行 —— 否则「因为查询写错了所以读不到」
 * 也会显示成绿灯。
 *
 * <p>本模块比 7.4.1.1 多五张业务表（规则 / 审批人 / 审批单 / 链路记录 / 估值），
 * 每张都要各自证明 RLS 是**真的**开着（{@code relforcerowsecurity}），不是靠应用层拼 tenant_id 糊出来的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAdjustmentApprovalTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final ObjectMapper JSON = new ObjectMapper();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-adjustment-approval-tenancy-" + tenant + "-" + ordinal).getBytes());
  }

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

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
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
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_adjustment_approval_record, inventory_adjustment_approval_case,"
              + " inventory_adjustment_valuation, inventory_adjustment_approval_approver,"
              + " inventory_adjustment_approval_rule, inventory_adjustment_attachment,"
              + " inventory_adjustment_order_line, inventory_adjustment_audit,"
              + " inventory_adjustment_order, inventory_entry, inventory_transaction, stock_bucket,"
              + " inventory_balance, warehouse_location, inventory_lot, inventory_quality_status,"
              + " warehouse, sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                idFor(tenant, 3),
                "A-01",
                "A 区一排");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now();
  }

  /** 给某个租户建一张带一行的草稿调整单，返回它的 id。 */
  UUID newOrderFor(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              tenant,
              idFor(tenant, 2),
              idFor(tenant, 3),
              idFor(tenant, 1),
              idFor(tenant, 4),
              new BigDecimal("100"),
              BigDecimal.ZERO);
          UUID bucketId = UUID.randomUUID();
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              bucketId,
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 2),
              idFor(tenant, 3),
              idFor(tenant, 5),
              idFor(tenant, 4),
              balanceId,
              new BigDecimal("100"),
              now());
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_adjustment_order(id,tenant_id,code,status,reason_code,note,"
                  + "created_by_user_id,created_at) values(?,?,?,'DRAFT','DAMAGE','',?,?::timestamptz)",
              orderId,
              tenant,
              "ADJ-INV-APR-TENANCY-" + tenant.toString().substring(0, 8),
              user,
              now());
          sql.execute(
              "insert into inventory_adjustment_order_line(id,tenant_id,order_id,bucket_id,balance_id,"
                  + "legal_entity_id,sku_id,warehouse_id,location_id,quality_status_id,"
                  + "sku_code,sku_name,warehouse_code,location_code,lot_code,quality_status_code,"
                  + "qty_delta,reason_code,note,expected_version,line_no,created_at) "
                  + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              bucketId,
              balanceId,
              idFor(tenant, 1),
              idFor(tenant, 2),
              idFor(tenant, 3),
              idFor(tenant, 5),
              idFor(tenant, 4),
              "SKU-1",
              "商品一",
              "WH-ONE",
              "A-01",
              "",
              "SELLABLE",
              new BigDecimal("-1"),
              "DAMAGE",
              "",
              0L,
              1,
              now());
          return orderId;
        });
  }

  /** 给某个租户落一条阈值规则（直接落库，绕开 HTTP 以便精确控制租户）。 */
  void newRuleFor(UUID tenant, String ruleNo) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "insert into inventory_adjustment_approval_rule(id,tenant_id,rule_no,rule_name,"
                  + "reason_code,currency,min_amount,max_amount,min_qty,max_qty,required_tier,"
                  + "required_approvals,priority,status,note,created_by,created_at,updated_at,version) "
                  + "values(?,?,?,'阈值规则','ANY','ANY',0,null,0,null,'L1',1,100,'ACTIVE','',?,"
                  + "?::timestamptz,?::timestamptz,0)",
              UUID.randomUUID(),
              tenant,
              ruleNo,
              user,
              now(),
              now());
          return null;
        });
  }

  /** 提交待审（走真实端点，于是审批单 / 链路记录 / 估值三张表都落齐）。 */
  void submitFor(UUID tenant, UUID orderId) throws Exception {
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + orderId + "/submit")
                .header("Authorization", "Bearer " + (tenant.equals(a) ? "tenant-a" : "tenant-b"))
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "amount", new BigDecimal("100"),
                            "currency", "USD",
                            "note", "提交"))))
        .andExpect(status().isOk());
  }

  // ================================================================ 三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    var orderA = newOrderFor(a);
    var orderB = newOrderFor(b);
    newRuleFor(a, "RULE-A");
    newRuleFor(b, "RULE-B");
    submitFor(a, orderA);
    submitFor(b, orderB);

    // 正对照：A 看到自己那一条规则与一条审批单（证明下面读到 0 行不是因为查询写错了）。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleNo").value("RULE-A"));
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/pending")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].orderCode").value(orderCodeOf(a, orderA)));

    // A 看不到 B 的规则（按规则号点名查）。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/pending")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].orderCode").value(orderCodeOf(a, orderA)));

    // A 读 B 的审批单详情：404，不泄露存在性。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + orderB)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // A 读 B 的会计影响台账：看不到 B 登记的那一条。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/valuations")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].orderId").value(orderA.toString()));

    // B 那边同理。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleNo").value("RULE-B"));
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + orderA)
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/valuations")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].orderId").value(orderB.toString()));
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var orderA = newOrderFor(a);
    var orderB = newOrderFor(b);
    newRuleFor(a, "RULE-A");
    newRuleFor(b, "RULE-B");
    submitFor(a, orderA);
    submitFor(b, orderB);

    // 身份是 B，伪造 X-Tenant-Id 指向 A ⇒ 只能看到 B 的。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleNo").value("RULE-B"));

    // 身份是 A，伪造指向 B ⇒ 只能看到 A 的。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleNo").value("RULE-A"));

    // 伪造头也不能让 A 读到 B 的审批单。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + orderB)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 伪造头也不能让 A 批 B 的单。
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + orderB + "/approve")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "越权批准"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void 断言三_连接池归还后不串租户() throws Exception {
    var orderA = newOrderFor(a);
    var orderB = newOrderFor(b);
    newRuleFor(a, "RULE-A");
    newRuleFor(b, "RULE-B");
    submitFor(a, orderA);
    submitFor(b, orderB);

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      assertThat(countRules(a)).isEqualTo(1);
      assertThat(countRules(b)).isEqualTo(1);
      assertThat(countCases(a)).isEqualTo(1);
      assertThat(countCases(b)).isEqualTo(1);
      assertThat(countValuations(a)).isEqualTo(1);
      assertThat(countValuations(b)).isEqualTo(1);
    }

    // 逐张表确认 RLS 是真的开着（不是靠应用层拼 tenant_id 糊出来的）。
    for (var table :
        List.of(
            "inventory_adjustment_approval_rule",
            "inventory_adjustment_approval_approver",
            "inventory_adjustment_approval_case",
            "inventory_adjustment_approval_record",
            "inventory_adjustment_valuation")) {
      var row =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity from pg_class where relname = ? and relnamespace"
                          + " = 'public'::regnamespace",
                      table));
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as(table).isTrue();
    }
  }

  String orderCodeOf(UUID tenant, UUID orderId) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select code from inventory_adjustment_order where tenant_id = ? and id = ?",
                    tenant,
                    orderId)
                .get("code", String.class));
  }

  long countRules(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_adjustment_approval_rule where tenant_id = ?",
                    tenant)
                .get(0, Long.class));
  }

  long countCases(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_adjustment_approval_case where tenant_id = ?",
                    tenant)
                .get(0, Long.class));
  }

  long countValuations(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_adjustment_valuation where tenant_id = ?", tenant)
                .get(0, Long.class));
  }
}
