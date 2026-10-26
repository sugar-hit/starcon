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
 * 1.2.1.2 搜索结果分组 —— trade 侧的分组元数据集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>组报出实际生效的数据范围</b>：ALL / SELF / DEPARTMENT 都要如实报出来，
 *       界面靠它标注「这一组是不是在你的数据范围内」。</li>
 *   <li><b>销售订单没有部门维度 ⇒ DEPARTMENT 退化为 SELF</b>，与 9.1.1.1 订单列表逐字同构。
 *       这一条同时守着<b>范围真的生效</b>：部门范围的角色**搜不到**别人经手过的订单。</li>
 *   <li><b>被权限抑制的子域要带归属业务域</b>（ORDER / PURCHASE）。</li>
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

  static Pair newPair(String scope, List<String> permissions, UUID department) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, department, List.of(grant(scope, permissions))));
    actors.put(bearerB, actor(user, tenantB, department, List.of(grant(scope, permissions))));
    seedActors.put(tenantA, actor(user, tenantA, department, List.of(grant("ALL", searchPermissions))));
    seedActors.put(tenantB, actor(user, tenantB, department, List.of(grant("ALL", searchPermissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  static Pair newPair() {
    return newPair("ALL", searchPermissions, null);
  }

  // ================================================================ 夹具

  /** 种一张销售订单（草稿），可选地带一条「某人经手过」的留痕。 */
  void seedOrder(UUID tenant, String orderNo, UUID actorId, boolean touchedByActor) {
    var actor = seedActors.get(tenant);
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

  /** 种一张采购单（草稿），负责人 / 归属部门可指定。 */
  void seedPurchase(UUID tenant, String orderNo, UUID ownerUserId, UUID ownerDepartmentId) {
    var actor = seedActors.get(tenant);
    access.run(
        actor,
        "trade.purchase.read",
        () -> {
          sql.execute(
              "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_id,"
                  + "source_request_no,supplier_ref_id,supplier_code,supplier_name,legal_entity_id,"
                  + "legal_entity_code,legal_entity_name,currency,total_amount,line_count,status,"
                  + "promised_arrival_date,target_warehouse_id,target_warehouse_code,"
                  + "target_warehouse_name,owner_user_id,owner_department_id,created_by,created_at,"
                  + "updated_at,version)"
                  + " values(?::uuid,?::uuid,?::varchar,'MANUAL',null,'',?::uuid,'SUP-1','供应商一',"
                  + " ?::uuid,'','','CNY',0,0,'DRAFT',?::date,?::uuid,'WH-1','一号仓',?::uuid,"
                  + " ?::uuid,?::uuid,now(),now(),0)",
              UUID.randomUUID(),
              tenant,
              orderNo,
              UUID.randomUUID(),
              UUID.randomUUID(),
              LocalDate.now().plusDays(30),
              UUID.randomUUID(),
              ownerUserId,
              ownerDepartmentId,
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
  void 订单组报出实际生效的数据范围() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-GRP-001", user, true);

    Map<String, Object> order = group(search(p.a(), "SO-GRP"), "ORDER");
    assertThat(order.get("dataScope")).as("ALL 授权 ⇒ 全租户可见").isEqualTo("ALL");
    assertThat((String) order.get("dataScopeNote")).as("范围要有说明文案").isNotBlank();
  }

  @Test
  void SELF范围时报SELF且搜不到别人经手过的订单() throws Exception {
    UUID other = UUID.randomUUID();
    Pair p = newPair("SELF", searchPermissions, null);
    seedOrder(p.tenantA(), "SO-GRP-002A", other, true);
    seedOrder(p.tenantA(), "SO-GRP-002B", user, true);

    Map<String, Object> response = search(p.a(), "SO-GRP");
    assertThat(group(response, "ORDER").get("dataScope")).isEqualTo("SELF");
    // 别人的订单被范围收窄掉了，本人经手的还在。
    assertThat(primaryOf(response, "ORDER")).containsExactly("SO-GRP-002B");
  }

  @Test
  void 部门范围的销售订单退化为SELF且真的收窄() throws Exception {
    UUID dept = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Pair p = newPair("DEPARTMENT", searchPermissions, dept);
    seedOrder(p.tenantA(), "SO-GRP-003A", other, true);
    seedOrder(p.tenantA(), "SO-GRP-003B", user, true);

    Map<String, Object> response = search(p.a(), "SO-GRP");
    // sales_order 没有部门列 ⇒ DEPARTMENT 退化到 SELF（与 9.1.1.1 订单列表同构）。
    assertThat(group(response, "ORDER").get("dataScope")).isEqualTo("SELF");
    // 范围必须**真的生效**：别人经手过的订单不该被部门范围的角色看到。
    assertThat(primaryOf(response, "ORDER")).containsExactly("SO-GRP-003B");
  }

  @Test
  void 采购单组按部门范围报DEPARTMENT() throws Exception {
    UUID dept = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    Pair p = newPair("DEPARTMENT", searchPermissions, dept);
    seedPurchase(p.tenantA(), "PO-GRP-001", owner, dept);

    Map<String, Object> response = search(p.a(), "PO-GRP");
    assertThat(group(response, "PURCHASE").get("dataScope")).isEqualTo("DEPARTMENT");
    assertThat(primaryOf(response, "PURCHASE")).contains("PO-GRP-001");
  }

  @Test
  void 无权限的域带归属业务域进suppressedDomains() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, null, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SO-GRP");
    assertThat(response.get("groups")).as("权限不足 ⇒ 整组不返回").isEqualTo(List.of());
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain") + "→" + m.get("group"))
        .contains("ORDER→ORDER", "PURCHASE→PURCHASE");
    // 不变式：被抑制的子域与 unavailableDomains 是同一次判定的两份说法。
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain"))
        .containsExactlyInAnyOrderElementsOf((List<String>) response.get("unavailableDomains"));
  }

  // ================================================================ 多租户三断言
  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedOrder(p.tenantA(), "SO-ISO-A", user, true);
    seedOrder(p.tenantB(), "SO-ISO-B", user, true);
    seedPurchase(p.tenantA(), "PO-ISO-A", user, null);
    seedPurchase(p.tenantB(), "PO-ISO-B", user, null);

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
