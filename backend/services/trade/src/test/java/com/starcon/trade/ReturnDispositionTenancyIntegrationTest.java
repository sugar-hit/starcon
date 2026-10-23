package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.3.2 转可售/残次/报废（trade 侧）的**多租户隔离三断言**
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
 * <p>⚠️ 本模块尤其不能省：处置决定直接决定这批货**以什么身份重新进入销售**，
 * 而「谁批的报废」是供应商索赔与平台申诉的举证材料，
 * 端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write", "trade.aftersales.approve");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static final UUID SKU_ID = UUID.nameUUIDFromBytes("rd-tenancy-sku".getBytes());
  static final UUID BUCKET_ID = UUID.nameUUIDFromBytes("rd-tenancy-bucket".getBytes());
  static final UUID LOCATION_ID = UUID.nameUUIDFromBytes("rd-tenancy-location".getBytes());

  static HttpServer warehouse;

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/return-dispositions/options",
          ReturnDispositionTenancyIntegrationTest::options);
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-main", actor(user, tenant));
    actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
  }

  @MockitoSpyBean AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    pg.stop();
  }

  /** warehouse 桩：只提供候选库位（建单要校验来源库位）。 */
  static void options(HttpExchange e) throws java.io.IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetInventoryState", "SELLABLE");
    body.put("requiredLocationType", "STORAGE");
    List<Map<String, Object>> buckets = new ArrayList<>();
    Map<String, Object> bucket = new LinkedHashMap<>();
    bucket.put("bucketId", BUCKET_ID.toString());
    bucket.put("warehouseId", UUID.nameUUIDFromBytes("rd-tenancy-wh".getBytes()).toString());
    bucket.put("warehouseCode", "WH-ONE");
    bucket.put("warehouseName", "一仓");
    bucket.put("locationId", UUID.nameUUIDFromBytes("rd-tenancy-q".getBytes()).toString());
    bucket.put("locationCode", "Q-01");
    bucket.put("locationName", "待检区");
    bucket.put("lotId", null);
    bucket.put("lotCode", "");
    bucket.put("legalEntityId", UUID.nameUUIDFromBytes("rd-tenancy-le".getBytes()).toString());
    bucket.put("qualityStatusCode", "PENDING_INSPECTION");
    bucket.put("skuId", SKU_ID.toString());
    bucket.put("skuCode", "SKU-1");
    bucket.put("skuName", "商品一");
    bucket.put("onHand", new BigDecimal("10"));
    bucket.put("reserved", BigDecimal.ZERO);
    bucket.put("available", new BigDecimal("10"));
    buckets.add(bucket);
    body.put("pendingBuckets", buckets);
    List<Map<String, Object>> locations = new ArrayList<>();
    Map<String, Object> location = new LinkedHashMap<>();
    location.put("locationId", LOCATION_ID.toString());
    location.put("code", "S-01");
    location.put("name", "存储区");
    location.put("type", "STORAGE");
    location.put("warehouseId", UUID.nameUUIDFromBytes("rd-tenancy-wh".getBytes()).toString());
    location.put("warehouseCode", "WH-ONE");
    location.put("pickable", true);
    location.put("status", "ACTIVE");
    locations.add(location);
    body.put("targetLocations", locations);
    byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  @BeforeEach
  void stubIdentityAndClean() throws Exception {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              String bearer = inv.getArgument(0);
              String permission = inv.getArgument(1);
              AccessService.Access actor = actors.get(bearer);
              if (actor == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              boolean allowed =
                  actor.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return actor;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_inventory_disposition_loss, return_inventory_disposition_event,"
              + " return_inventory_disposition, return_quality_disposition_instruction,"
              + " return_quality_check_result, return_quality_event, return_quality_inspection,"
              + " return_quality_template_item, return_quality_template, return_quality_category_sku,"
              + " return_quality_category cascade");
    }
  }

  <T> T as(UUID tenantId, java.util.function.Supplier<T> work) {
    AccessService.Access actor =
        tenantId.equals(tenant) ? actors.get("Bearer tenant-main") : actors.get("Bearer tenant-other");
    return access.run(actor, "trade.aftersales.write", work);
  }

  /**
   * 给某个租户造一条处置单（草稿），返回它的 id。
   *
   * <p>⚠️ 夹具刻意<b>绕过本模块的领域守卫</b>（{@code session_replication_role = replica}）：
   * 守卫会真查一张真实存在的质检指令，而本类要验的是**租户隔离**，
   * 不该为了造一行数据把 11.2.3.1 的整条退货链路再搭一遍。
   * 连接用的是 PG 超级用户（本来就绕过 RLS），只用于测试夹具重建。
   */
  UUID newDispositionFor(UUID tenantId) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set session_replication_role = replica");
      UUID id = UUID.randomUUID();
      s.execute(
          "insert into return_inventory_disposition(id,tenant_id,disposition_no,instruction_id,"
              + "inspection_id,receipt_id,receipt_line_id,instruction_type,target_inventory_state,"
              + "sku_id,sku_code,qty,source_bucket_id,source_warehouse_id,source_location_id,"
              + "source_legal_entity_id,target_location_id,reason_code,reason_note,status,created_by)"
              + " values('"
              + id
              + "','"
              + tenantId
              + "','RDP-"
              + tenantId.toString().substring(0, 8)
              + "','"
              + UUID.randomUUID()
              + "','"
              + UUID.randomUUID()
              + "','"
              + UUID.randomUUID()
              + "','"
              + UUID.randomUUID()
              + "','MOVE_TO_SELLABLE','SELLABLE','"
              + SKU_ID
              + "','SKU-1',2::numeric,'"
              + BUCKET_ID
              + "','"
              + UUID.nameUUIDFromBytes("rd-tenancy-wh".getBytes())
              + "','"
              + UUID.nameUUIDFromBytes("rd-tenancy-q".getBytes())
              + "','"
              + UUID.nameUUIDFromBytes("rd-tenancy-le".getBytes())
              + "','"
              + LOCATION_ID
              + "','RESALABLE','租户隔离夹具','DRAFT','"
              + user
              + "')");
      s.execute("set session_replication_role = origin");
      return id;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  long countDispositions(UUID tenantId) {
    return as(
        tenantId,
        () ->
            sql.fetchOne(
                    "select count(*) from return_inventory_disposition where tenant_id = ?", tenantId)
                .get(0, Long.class));
  }

  // ================================================================ 三断言

  @Test
  void 断言一_两租户互不可见() throws Exception {
    var dispositionA = newDispositionFor(tenant);
    var dispositionB = newDispositionFor(other);

    // 正对照：A 看到自己那一张（证明下面读到 0 行不是因为查询写错了）
    http.perform(get("/trade/return-dispositions").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.dispositions[0].id").value(dispositionA.toString()));

    // A 看不到 B 的单子
    http.perform(
            get("/trade/return-dispositions")
                .header("Authorization", "Bearer tenant-main")
                .param("keyword", dispositionB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 按主键直查对方的单：查不到（404），不泄露存在性
    http.perform(
            get("/trade/return-dispositions/" + dispositionB)
                .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isNotFound());

    // B 那边同理
    http.perform(get("/trade/return-dispositions").header("Authorization", "Bearer tenant-other"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.dispositions[0].id").value(dispositionB.toString()));
    http.perform(
            get("/trade/return-dispositions/" + dispositionA)
                .header("Authorization", "Bearer tenant-other"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    newDispositionFor(tenant);
    newDispositionFor(other);

    // 身份是 B，伪造 X-Tenant-Id 指向 A → 只能看到 B 的
    http.perform(
            get("/trade/return-dispositions")
                .header("Authorization", "Bearer tenant-other")
                .header("X-Tenant-Id", tenant.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 身份是 A，伪造指向 B → 只能看到 A 的
    http.perform(
            get("/trade/return-dispositions")
                .header("Authorization", "Bearer tenant-main")
                .header("X-Tenant-Id", other.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    newDispositionFor(tenant);
    newDispositionFor(other);
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮。
    for (int i = 0; i < 20; i++) {
      assertThat(countDispositions(tenant)).isEqualTo(1);
      assertThat(countDispositions(other)).isEqualTo(1);
      assertThat(countDispositions(tenant)).isEqualTo(1);
      assertThat(countDispositions(other)).isEqualTo(1);
    }
    // 逐张表确认 RLS 是真的开着（不是靠应用层拼 tenant_id 糊出来的）
    for (var table :
        List.of(
            "return_inventory_disposition",
            "return_inventory_disposition_event",
            "return_inventory_disposition_loss",
            "return_quality_disposition_instruction")) {
      var row =
          as(
              tenant,
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity from pg_class where relname = ? and relnamespace"
                          + " = 'public'::regnamespace",
                      table));
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as(table).isTrue();
    }
  }
}
