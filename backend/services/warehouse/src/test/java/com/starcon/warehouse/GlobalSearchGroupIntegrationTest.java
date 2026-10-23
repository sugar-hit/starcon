package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 1.2.1.2 搜索结果分组 —— warehouse 侧的分组元数据集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>仓库与物流是租户级数据 ⇒ 报 TENANT</b>：这几张表上没有归属列，
 *       个人 / 部门范围收窄不了它们，所以只能报「租户级」，不能报 SELF ——
 *       报 SELF 会让界面显示「只看到本人的」，实际看到的是全租户的。</li>
 *   <li><b>被权限抑制的子域要带归属业务域</b>（WAREHOUSE / LOGISTICS）。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalSearchGroupIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  /** 夹具专用账号：始终带全部域权限（写入要过策略，被测会话可能故意缺权限）。 */
  static final Map<UUID, AccessService.Access> seedActors = new ConcurrentHashMap<>();

  static final List<String> searchPermissions =
      List.of("identity.global_search.read", "warehouse.facilities.read");

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
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
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

  /** 一对每个用例独有的租户，避免夹具互相污染。 */
  record Pair(String a, String b, UUID tenantA, UUID tenantB) {}

  static Pair newPair(String scope, List<String> permissions) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, List.of(grant(scope, permissions))));
    actors.put(bearerB, actor(user, tenantB, List.of(grant(scope, permissions))));
    seedActors.put(tenantA, actor(user, tenantA, List.of(grant("ALL", searchPermissions))));
    seedActors.put(tenantB, actor(user, tenantB, List.of(grant("ALL", searchPermissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  static Pair newPair() {
    return newPair("ALL", searchPermissions);
  }

  // ================================================================ 夹具

  /** 仓库 + 法人 + 质量状态 + SKU 引用（物流三张表的 FK 都要它们）。 */
  record Base(UUID warehouseId, UUID otherWarehouseId, UUID legalEntityId, UUID qualityId, UUID skuId) {}

  Base seedBase(UUID tenant) {
    var actor = seedActors.get(tenant);
    return access.run(
        actor,
        "warehouse.facilities.read",
        () -> {
          UUID legalEntityId = UUID.randomUUID();
          sql.execute(
              "insert into legal_entity_ref(id,tenant_id,code,name,status,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'LE-1','主法人','ACTIVE',now(),now())",
              legalEntityId,
              tenant);
          UUID warehouseId = UUID.randomUUID();
          UUID otherWarehouseId = UUID.randomUUID();
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,is_default,status,"
                  + "created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'WH-MAIN','主仓','CN','Asia/Shanghai',false,'ACTIVE',now(),now())",
              warehouseId,
              tenant);
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone,is_default,status,"
                  + "created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'WH-OTHER','副仓','CN','Asia/Shanghai',false,'ACTIVE',now(),now())",
              otherWarehouseId,
              tenant);
          UUID qualityId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role,enabled,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'SELLABLE','可售','SELLABLE',true,now(),now())",
              qualityId,
              tenant);
          UUID skuId = UUID.randomUUID();
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'SKU-1','商品一',now(),now())",
              skuId,
              tenant);
          return new Base(warehouseId, otherWarehouseId, legalEntityId, qualityId, skuId);
        });
  }

  /** 在途腿的运单号（inventory_transfer.tracking_no）。 */
  void seedInTransit(UUID tenant, Base base, String trackingNo) {
    var actor = seedActors.get(tenant);
    access.run(
        actor,
        "warehouse.facilities.read",
        () -> {
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                  + "to_warehouse_id,quality_status_id,quantity,status,shipped_at,created_at,updated_at,"
                  + "transit_kind,carrier_name,tracking_no)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,1,'IN_TRANSIT',now(),"
                  + " now(),now(),'TRANSFER','顺丰',?::varchar)",
              UUID.randomUUID(),
              tenant,
              base.skuId(),
              base.legalEntityId(),
              base.warehouseId(),
              base.otherWarehouseId(),
              base.qualityId(),
              trackingNo);
          return null;
        });
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> search(String bearer, String keyword) throws Exception {
    String body =
        http.perform(
                get("/warehouse/global-search").header("Authorization", bearer).param("q", keyword))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, Map.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> group(Map<String, Object> response, String domain) {
    Object groups = response.get("groups");
    if (!(groups instanceof List)) return Map.of();
    for (Object g : (List<Object>) groups) {
      Map<String, Object> candidate = (Map<String, Object>) g;
      if (domain.equals(candidate.get("domain"))) return candidate;
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> suppressed(Map<String, Object> response) {
    Object value = response.get("suppressedDomains");
    if (!(value instanceof List)) return List.of();
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  List<String> primaryOf(Map<String, Object> response, String domain) {
    List<String> out = new ArrayList<>();
    Object hits = group(response, domain).get("hits");
    if (!(hits instanceof List)) return out;
    for (Object hit : (List<Object>) hits) {
      out.add((String) ((Map<String, Object>) hit).get("primary"));
    }
    return out;
  }

  // ================================================================ 分组元数据

  @Test
  void 仓库与物流是租户级数据报TENANT() throws Exception {
    Pair p = newPair("SELF", searchPermissions);
    Base base = seedBase(p.tenantA());
    seedInTransit(p.tenantA(), base, "YT-GRP-001");

    Map<String, Object> warehouses = search(p.a(), "WH-MAIN");
    // 授权范围是 SELF，但仓库表上没有归属列 ⇒ 看得见的是本租户全量，只能报 TENANT。
    assertThat(group(warehouses, "WAREHOUSE").get("dataScope")).isEqualTo("TENANT");
    assertThat((String) group(warehouses, "WAREHOUSE").get("dataScopeNote")).isNotBlank();

    Map<String, Object> logistics = search(p.a(), "YT-GRP");
    assertThat(group(logistics, "LOGISTICS").get("dataScope")).isEqualTo("TENANT");
    assertThat((String) group(logistics, "LOGISTICS").get("dataScopeNote")).isNotBlank();
  }

  @Test
  void 物流单据命中归在物流组() throws Exception {
    Pair p = newPair();
    Base base = seedBase(p.tenantA());
    seedInTransit(p.tenantA(), base, "YT-GRP-002");

    Map<String, Object> response = search(p.a(), "YT-GRP");
    assertThat(primaryOf(response, "LOGISTICS")).containsExactly("YT-GRP-002");
  }

  @Test
  void 无权限的域带归属业务域进suppressedDomains() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "WH-MAIN");
    assertThat(response.get("groups")).as("权限不足 ⇒ 整组不返回").isEqualTo(List.of());
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain") + "→" + m.get("group"))
        .contains("WAREHOUSE→WAREHOUSE", "LOGISTICS→LOGISTICS");
    // 不变式：被抑制的子域与 unavailableDomains 是同一次判定的两份说法。
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain"))
        .containsExactlyInAnyOrderElementsOf((List<String>) response.get("unavailableDomains"));
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedBase(p.tenantA());
    seedBase(p.tenantB());

    assertThat(primaryOf(search(p.a(), "WH-MAIN"), "WAREHOUSE")).containsExactly("WH-MAIN");
    assertThat(primaryOf(search(p.b(), "WH-MAIN"), "WAREHOUSE")).containsExactly("WH-MAIN");
    // 两租户各有自己的 WH-MAIN，命中数不该因为合并而翻倍。
    assertThat(primaryOf(search(p.a(), "WH-MAIN"), "WAREHOUSE")).hasSize(1);
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    Pair p = newPair();
    Base baseA = seedBase(p.tenantA());
    seedInTransit(p.tenantA(), baseA, "YT-FORGED-A");
    Base baseB = seedBase(p.tenantB());
    seedInTransit(p.tenantB(), baseB, "YT-FORGED-B");

    String honest =
        http.perform(
                get("/warehouse/global-search")
                    .header("Authorization", p.a())
                    .param("q", "YT-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forged =
        http.perform(
                get("/warehouse/global-search")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .param("q", "YT-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份结果").isEqualTo(honest);
    assertThat(primaryOf(search(p.a(), "YT-FORGED"), "LOGISTICS")).containsExactly("YT-FORGED-A");
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    Base baseA = seedBase(p.tenantA());
    seedInTransit(p.tenantA(), baseA, "YT-POOL-A");
    Base baseB = seedBase(p.tenantB());
    seedInTransit(p.tenantB(), baseB, "YT-POOL-B");

    for (int i = 0; i < 5; i++) {
      assertThat(primaryOf(search(p.a(), "YT-POOL"), "LOGISTICS"))
          .as("第 %d 轮租户 A", i)
          .containsExactly("YT-POOL-A");
      assertThat(primaryOf(search(p.b(), "YT-POOL"), "LOGISTICS"))
          .as("第 %d 轮租户 B", i)
          .containsExactly("YT-POOL-B");
    }
  }
}
