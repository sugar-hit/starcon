package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 1.2.1.1 全局搜索框 —— trade 侧只读检索的集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>前缀语义</b>：蓝图写死「只做前缀/精确匹配」；子串不该命中。</li>
 *   <li><b>数据范围真的生效</b>：SELF 范围的用户<b>搜不到</b>自己没经手过的订单
 *       （sales_order_status_history 里没有本人留痕）；采购单 SELF 落在 owner_user_id 上。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。检索是最容易漏租户过滤的地方。</li>
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
      List.of("identity.global_search.read", "trade.orders.read", "trade.purchase.read");

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
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
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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

  /** 一对每个用例独有的租户，避免夹具互相污染。 */
  record Pair(String a, String b, UUID tenantA, UUID tenantB) {}

  static Pair newPair() {
    return newPair("ALL", searchPermissions, null);
  }

  static Pair newPair(String scope, List<String> permissions, UUID department) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, department, List.of(grant(scope, permissions))));
    actors.put(bearerB, actor(user, tenantB, department, List.of(grant(scope, permissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  // ================================================================ 夹具

  /** 种一张销售订单（草稿），可选地带一条「本人经手过」的留痕。 */
  void seedOrder(UUID tenant, String orderNo, UUID actorId, boolean touchedByActor) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
    access.run(
        actor,
        "trade.orders.read",
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,payment_terms_days,status,submitted_at,subtotal_amount,"
                  + "discount_amount,tax_amount,shipping_amount,other_amount,grand_total_amount,"
                  + "version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::varchar,'AMAZON',?::varchar,'USD','PREPAID',0,'DRAFT',"
                  + " null,0,0,0,0,0,0,0,now(),now())",
              orderId,
              tenant,
              orderNo,
              "EXT-" + orderNo);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,'C-1',?::varchar,'INDIVIDUAL','',now())",
              UUID.randomUUID(),
              tenant,
              orderId,
              UUID.randomUUID(),
              orderNo + " 买家");
          if (touchedByActor) {
            sql.execute(
                "insert into sales_order_status_history(id,tenant_id,order_id,event_type,from_status,"
                    + "to_status,reason,actor_id,occurred_at,created_at)"
                    + " values(?::uuid,?::uuid,?::uuid,'CREATED','DRAFT','DRAFT','',?::uuid,now(),now())",
                UUID.randomUUID(),
                tenant,
                orderId,
                actorId);
          }
          return null;
        });
  }

  /** 种一张采购单（草稿），负责人 = ownerUserId。 */
  void seedPurchase(UUID tenant, String orderNo, UUID ownerUserId) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
    access.run(
        actor,
        "trade.purchase.read",
        () -> {
          sql.execute(
              "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_id,"
                  + "source_request_no,supplier_ref_id,supplier_code,supplier_name,legal_entity_id,"
                  + "legal_entity_code,legal_entity_name,currency,total_amount,line_count,status,"
                  + "promised_arrival_date,target_warehouse_id,target_warehouse_code,"
                  + "target_warehouse_name,owner_user_id,created_by,created_at,updated_at,version)"
                  + " values(?::uuid,?::uuid,?::varchar,'MANUAL',null,'',?::uuid,'SUP-1','供应商一',"
                  + " ?::uuid,'','','CNY',0,0,'DRAFT',?::date,?::uuid,'WH-1','一号仓',?::uuid,"
                  + " ?::uuid,now(),now(),0)",
              UUID.randomUUID(),
              tenant,
              orderNo,
              UUID.randomUUID(),
              UUID.randomUUID(),
              LocalDate.now().plusDays(30),
              UUID.randomUUID(),
              ownerUserId,
              ownerUserId);
          return null;
        });
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> search(String bearer, String keyword) throws Exception {
    String body =
        http.perform(
                get("/trade/global-search").header("Authorization", bearer).param("q", keyword))
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
      if (domain.equals(group.get("domain"))) {
        Object hits = group.get("hits");
        return hits instanceof List ? (List<Map<String, Object>>) hits : List.of();
      }
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  List<String> primaryOf(Map<String, Object> response, String domain) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> hit : hits(response, domain)) out.add((String) hit.get("primary"));
    return out;
  }

  // ================================================================ 检索语义

  @Test
  void 搜订单按单号前缀命中且不含子串() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-AB-001", user, true);

    assertThat(primaryOf(search(p.a(), "SO-AB"), "ORDER")).contains("SO-AB-001");
    assertThat(primaryOf(search(p.a(), "AB-001"), "ORDER")).isEmpty();
  }

  @Test
  void 搜采购单按单号前缀命中() throws Exception {
    Pair p = newPair();
    seedPurchase(p.tenantA(), "PO-AB-001", user);

    assertThat(primaryOf(search(p.a(), "PO-AB"), "PURCHASE")).contains("PO-AB-001");
    assertThat(primaryOf(search(p.a(), "AB-001"), "PURCHASE")).isEmpty();
  }

  @Test
  void 关键词少于两个字符后端直接400() throws Exception {
    Pair p = newPair();
    http.perform(get("/trade/global-search").header("Authorization", p.a()).param("q", "S"))
        .andExpect(status().isBadRequest());
    http.perform(get("/trade/global-search").header("Authorization", p.a()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void SELF范围搜不到本人没经手过的订单() throws Exception {
    UUID other = UUID.randomUUID();
    Pair p = newPair("SELF", searchPermissions, null);
    seedOrder(p.tenantA(), "SO-SELF-001", other, true);

    // 会话用户是 user，订单的留痕人是 other ⇒ SELF 范围下搜不到。
    assertThat(primaryOf(search(p.a(), "SO-SELF"), "ORDER")).isEmpty();
  }

  @Test
  void ALL范围能搜到全部订单() throws Exception {
    UUID other = UUID.randomUUID();
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-ALL-001", other, true);
    assertThat(primaryOf(search(p.a(), "SO-ALL"), "ORDER")).contains("SO-ALL-001");
  }

  @Test
  void 采购单的SELF范围落在owner_user_id上() throws Exception {
    UUID other = UUID.randomUUID();
    Pair p = newPair("SELF", searchPermissions, null);
    seedPurchase(p.tenantA(), "PO-SELF-001", other);
    assertThat(primaryOf(search(p.a(), "PO-SELF"), "PURCHASE")).isEmpty();

    Pair q = newPair("SELF", searchPermissions, null);
    seedPurchase(q.tenantA(), "PO-SELF-002", user);
    assertThat(primaryOf(search(q.a(), "PO-SELF"), "PURCHASE")).contains("PO-SELF-002");
  }

  @Test
  void 没有域权限时域进unavailableDomains而不是整体403() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, null, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SO-AB");
    assertThat(response.get("groups")).isEqualTo(List.of());
    @SuppressWarnings("unchecked")
    List<String> unavailable = (List<String>) response.get("unavailableDomains");
    assertThat(unavailable).contains("ORDER", "PURCHASE");
  }

  @Test
  void 能力声明逐条报出接通与未接通的域() throws Exception {
    Pair p = newPair();
    String body =
        http.perform(get("/trade/global-search/capabilities").header("Authorization", p.a()))
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
    assertThat(domains)
        .anyMatch(d -> "SUPPLIER".equals(d.get("domain")) && !d.get("available").equals(true));
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-ISO-A", user, true);
    seedOrder(p.tenantB(), "SO-ISO-B", user, true);
    seedPurchase(p.tenantA(), "PO-ISO-A", user);
    seedPurchase(p.tenantB(), "PO-ISO-B", user);

    assertThat(primaryOf(search(p.a(), "SO-ISO"), "ORDER")).containsExactly("SO-ISO-A");
    assertThat(primaryOf(search(p.b(), "SO-ISO"), "ORDER")).containsExactly("SO-ISO-B");
    assertThat(primaryOf(search(p.a(), "PO-ISO"), "PURCHASE")).containsExactly("PO-ISO-A");
    assertThat(primaryOf(search(p.b(), "PO-ISO"), "PURCHASE")).containsExactly("PO-ISO-B");
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-FORGED-A", user, true);
    seedOrder(p.tenantB(), "SO-FORGED-B", user, true);

    String honest =
        http.perform(
                get("/trade/global-search")
                    .header("Authorization", p.a())
                    .param("q", "SO-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forged =
        http.perform(
                get("/trade/global-search")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .param("q", "SO-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份结果").isEqualTo(honest);
    assertThat(primaryOf(search(p.a(), "SO-FORGED"), "ORDER")).containsExactly("SO-FORGED-A");
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-POOL-A", user, true);
    seedOrder(p.tenantB(), "SO-POOL-B", user, true);

    for (int i = 0; i < 5; i++) {
      assertThat(primaryOf(search(p.a(), "SO-POOL"), "ORDER"))
          .as("第 %d 轮租户 A", i)
          .containsExactly("SO-POOL-A");
      assertThat(primaryOf(search(p.b(), "SO-POOL"), "ORDER"))
          .as("第 %d 轮租户 B", i)
          .containsExactly("SO-POOL-B");
    }
  }
}
