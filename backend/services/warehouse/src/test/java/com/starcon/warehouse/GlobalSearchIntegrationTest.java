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
 * 1.2.1.1 全局搜索框 —— warehouse 侧只读检索的集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>三个运单号出处分别命中，且副信息带单据类型</b>：发运 / 在途 / 收货是三件不同的事，
 *       一个号落在哪张表决定了它是什么货 —— 混起来会让界面把「在途腿」显示成「已发货」。</li>
 *   <li><b>前缀语义</b>：子串不该命中。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalSearchIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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

  record Pair(String a, String b, UUID tenantA, UUID tenantB) {}

  static Pair newPair() {
    return newPair(searchPermissions);
  }

  static Pair newPair(List<String> permissions) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, List.of(grant("ALL", permissions))));
    actors.put(bearerB, actor(user, tenantB, List.of(grant("ALL", permissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  // ================================================================ 夹具

  /** 仓库 + 法人 + 质量状态 + SKU 引用（物流三张表的 FK 都要它们）。 */
  record Base(UUID warehouseId, UUID otherWarehouseId, UUID legalEntityId, UUID qualityId, UUID skuId) {}

  Base seedBase(UUID tenant) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
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
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
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

  /** 收货单的运单号（goods_receipt.waybill_no）。 */
  void seedReceipt(UUID tenant, Base base, String waybillNo) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
    access.run(
        actor,
        "warehouse.facilities.read",
        () -> {
          sql.execute(
              "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
                  + "source_ref_code,warehouse_id,legal_entity_id,status,carrier,waybill_no,"
                  + "evidence_note,close_reason,version,created_by,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'GR-1','TRANSFER',null,'TO-1',?::uuid,?::uuid,'DRAFT',"
                  + " '德邦',?::varchar,'','',0,?::uuid,now(),now())",
              UUID.randomUUID(),
              tenant,
              base.warehouseId(),
              base.legalEntityId(),
              waybillNo,
              user);
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
  List<Map<String, Object>> hits(Map<String, Object> response, String domain) {
    Object groups = response.get("groups");
    if (!(groups instanceof List)) return List.of();
    for (Object g : (List<Object>) groups) {
      Map<String, Object> group = (Map<String, Object>) g;
      if (!domain.equals(group.get("domain"))) continue;
      Object hits = group.get("hits");
      return hits instanceof List ? (List<Map<String, Object>>) hits : List.of();
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  List<String> primaryOf(Map<String, Object> response, String domain) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> hit : hits(response, domain)) out.add((String) hit.get("primary"));
    return out;
  }

  @SuppressWarnings("unchecked")
  List<String> kindsOf(Map<String, Object> response, String domain) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> hit : hits(response, domain)) out.add((String) hit.get("kind"));
    return out;
  }

  // ================================================================ 检索语义

  @Test
  void 搜仓库按编码与名称前缀命中() throws Exception {
    Pair p = newPair();
    seedBase(p.tenantA());

    assertThat(primaryOf(search(p.a(), "WH-M"), "WAREHOUSE")).contains("WH-MAIN");
    assertThat(primaryOf(search(p.a(), "主仓"), "WAREHOUSE")).contains("WH-MAIN");
    assertThat(primaryOf(search(p.a(), "H-MAIN"), "WAREHOUSE")).isEmpty();
  }

  @Test
  void 搜在途运单号命中且副信息带单据类型() throws Exception {
    Pair p = newPair();
    Base base = seedBase(p.tenantA());
    seedInTransit(p.tenantA(), base, "TRK-IN-001");

    var response = search(p.a(), "TRK-IN");
    assertThat(primaryOf(response, "LOGISTICS")).contains("TRK-IN-001");
    assertThat(kindsOf(response, "LOGISTICS")).contains("IN_TRANSIT");
    assertThat(hits(response, "LOGISTICS").get(0).get("secondary").toString()).contains("在途");
  }

  @Test
  void 搜收货运单号命中且副信息带单据类型() throws Exception {
    Pair p = newPair();
    Base base = seedBase(p.tenantA());
    seedReceipt(p.tenantA(), base, "GR-WB-001");

    var response = search(p.a(), "GR-WB");
    assertThat(primaryOf(response, "LOGISTICS")).contains("GR-WB-001");
    assertThat(kindsOf(response, "LOGISTICS")).contains("GOODS_RECEIPT");
    assertThat(hits(response, "LOGISTICS").get(0).get("secondary").toString()).contains("收货");
  }

  @Test
  void 关键词少于两个字符后端直接400() throws Exception {
    Pair p = newPair();
    http.perform(get("/warehouse/global-search").header("Authorization", p.a()).param("q", "W"))
        .andExpect(status().isBadRequest());
    http.perform(get("/warehouse/global-search").header("Authorization", p.a()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 没有域权限时域进unavailableDomains而不是整体403() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "WH-M");
    assertThat(response.get("groups")).isEqualTo(List.of());
    @SuppressWarnings("unchecked")
    List<String> unavailable = (List<String>) response.get("unavailableDomains");
    assertThat(unavailable).contains("WAREHOUSE", "LOGISTICS");
  }

  @Test
  void 能力声明逐条报出接通与未接通的域() throws Exception {
    Pair p = newPair();
    String body =
        http.perform(get("/warehouse/global-search/capabilities").header("Authorization", p.a()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> domains =
        (List<Map<String, Object>>) json.readValue(body, Map.class).get("domains");
    assertThat(domains).isNotEmpty();
    for (Map<String, Object> domain : domains) {
      assertThat(domain).containsKeys("domain", "label", "available", "ownerModule", "matchMode", "impact");
      assertThat((String) domain.get("ownerModule")).isNotBlank();
      assertThat((String) domain.get("impact")).isNotBlank();
      if (Boolean.TRUE.equals(domain.get("available"))) {
        assertThat((String) domain.get("source")).isNotBlank();
      } else {
        assertThat((String) domain.get("source")).isNull();
      }
    }
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    Base baseA = seedBase(p.tenantA());
    Base baseB = seedBase(p.tenantB());
    seedInTransit(p.tenantA(), baseA, "TRK-ISO-A");
    seedInTransit(p.tenantB(), baseB, "TRK-ISO-B");

    assertThat(primaryOf(search(p.a(), "TRK-ISO"), "LOGISTICS")).containsExactly("TRK-ISO-A");
    assertThat(primaryOf(search(p.b(), "TRK-ISO"), "LOGISTICS")).containsExactly("TRK-ISO-B");
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    Pair p = newPair();
    Base baseA = seedBase(p.tenantA());
    Base baseB = seedBase(p.tenantB());
    seedReceipt(p.tenantA(), baseA, "GR-FORGED-A");
    seedReceipt(p.tenantB(), baseB, "GR-FORGED-B");

    String honest =
        http.perform(
                get("/warehouse/global-search")
                    .header("Authorization", p.a())
                    .param("q", "GR-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forged =
        http.perform(
                get("/warehouse/global-search")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .param("q", "GR-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份结果").isEqualTo(honest);
    assertThat(primaryOf(search(p.a(), "GR-FORGED"), "LOGISTICS")).containsExactly("GR-FORGED-A");
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    Base baseA = seedBase(p.tenantA());
    Base baseB = seedBase(p.tenantB());
    seedInTransit(p.tenantA(), baseA, "TRK-POOL-A");
    seedInTransit(p.tenantB(), baseB, "TRK-POOL-B");

    for (int i = 0; i < 5; i++) {
      assertThat(primaryOf(search(p.a(), "TRK-POOL"), "LOGISTICS"))
          .as("第 %d 轮租户 A", i)
          .containsExactly("TRK-POOL-A");
      assertThat(primaryOf(search(p.b(), "TRK-POOL"), "LOGISTICS"))
          .as("第 %d 轮租户 B", i)
          .containsExactly("TRK-POOL-B");
    }
  }
}
