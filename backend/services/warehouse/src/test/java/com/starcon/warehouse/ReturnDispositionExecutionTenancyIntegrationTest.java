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
 * 11.2.3.2 转可售/残次/报废 —— warehouse 侧执行的**多租户隔离三断言**
 * （独立成类，便于与其他模块的租户测试并列回归）。
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
 * <p>⚠️ 本模块尤其不能省：执行回执决定这批货**以什么身份重新进入销售**，
 * 而「谁批的报废」是供应商索赔与平台申诉的举证材料。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionExecutionTenancyIntegrationTest {
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

  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-return-disposition-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
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
          "truncate inventory_return_disposition_execution, inventory_entry, inventory_transaction,"
              + " stock_bucket, inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "PENDING_INSPECTION",
                "待检",
                "PENDING_INSPECTION");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'QUARANTINE',false)",
                idFor(tenant, 6),
                tenant,
                idFor(tenant, 3),
                "Q-01",
                "待检区");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                    + " values(?,?,?,?,?,'STORAGE',true)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 3),
                "S-01",
                "存储区");
            UUID balanceId = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                balanceId,
                tenant,
                idFor(tenant, 2),
                idFor(tenant, 3),
                idFor(tenant, 1),
                idFor(tenant, 5),
                new BigDecimal("10"));
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                    + "quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
                    + " values(?,?,?,?,?,?,?,?,?,0,now())",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 1),
                idFor(tenant, 2),
                idFor(tenant, 3),
                idFor(tenant, 6),
                idFor(tenant, 5),
                balanceId,
                new BigDecimal("10"));
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

  /** 给某个租户执行一次处置，返回执行回执 id。 */
  UUID executeFor(UUID tenant) throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    UUID dispositionId = UUID.randomUUID();
    body.put("dispositionId", dispositionId.toString());
    body.put("dispositionNo", "RDP-TENANCY-001");
    body.put("instructionId", UUID.randomUUID().toString());
    body.put("inspectionId", UUID.randomUUID().toString());
    body.put("targetInventoryState", "SELLABLE");
    body.put("skuId", idFor(tenant, 2).toString());
    body.put("skuCode", "SKU-1");
    body.put("skuName", "商品一");
    body.put("batchNo", "");
    body.put("qty", new BigDecimal("2"));
    body.put("sourceBucketId", idFor(tenant, 8).toString());
    body.put("targetLocationId", idFor(tenant, 7).toString());
    body.put("occurredAt", now().toString());
    var result =
        http.perform(
                post("/warehouse/inventory/return-dispositions/execute")
                    .header("Authorization", "Bearer tenant-" + (tenant.equals(a) ? "a" : "b"))
                    .contentType("application/json")
                    .content(JSON.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("executionId").asText());
  }

  // ================================================================ 三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    var execA = executeFor(a);
    var execB = executeFor(b);

    // 正对照：A 看到自己那一条（证明下面读到 0 行不是因为查询写错了）
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(execA.toString()));

    // A 看不到 B 的执行回执
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions")
                .header("Authorization", "Bearer tenant-a")
                .param("dispositionId", dispositionIdOf(b)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 按主键直查对方的回执：查不到（404），不泄露存在性
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions/" + execB)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // B 那边同理
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(execB.toString()));
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions/" + execA)
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    executeFor(a);
    executeFor(b);

    // 身份是 B，伪造 X-Tenant-Id 指向 A → 只能看到 B 的
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 身份是 A，伪造指向 B → 只能看到 A 的
    http.perform(
            get("/warehouse/inventory/return-dispositions/executions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    executeQuietly(a);
    executeQuietly(b);
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      assertThat(countExecutions(a)).isEqualTo(1);
      assertThat(countExecutions(b)).isEqualTo(1);
      assertThat(countExecutions(a)).isEqualTo(1);
      assertThat(countExecutions(b)).isEqualTo(1);
    }
    // 逐张表确认 RLS 是真的开着（不是靠应用层拼 tenant_id 糊出来的）
    for (var table :
        List.of(
            "inventory_return_disposition_execution",
            "stock_bucket",
            "inventory_balance",
            "inventory_entry",
            "inventory_transaction")) {
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

  void executeQuietly(UUID tenant) {
    try {
      executeFor(tenant);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  long countExecutions(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_return_disposition_execution where tenant_id = ?",
                    tenant)
                .get(0, Long.class));
  }

  /** 取某租户最近一条执行回执的处置单 id。 */
  String dispositionIdOf(UUID tenant) {
    return access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select disposition_id from inventory_return_disposition_execution"
                        + " where tenant_id = ?",
                    tenant)
                .get("disposition_id", UUID.class)
                .toString());
  }
}
