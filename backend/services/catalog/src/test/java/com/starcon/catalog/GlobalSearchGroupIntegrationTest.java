package com.starcon.catalog;

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
 * 1.2.1.2 搜索结果分组 —— catalog 侧的分组元数据集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>组报出实际生效的数据范围</b>：ALL / SELF / TENANT 三种口径都要如实报出来。
 *       界面靠它标注「这一组是不是在你的数据范围内」—— 报错就是界面说谎。</li>
 *   <li><b>往来单位是租户级主数据</b>：{@code party} 表上没有归属列，
 *       所以即使授权范围是 SELF，客户这一组也只能报 TENANT（看得见全租户客户）。</li>
 *   <li><b>被权限抑制的子域要带归属业务域</b>：{@code PRODUCT_SKU} / {@code PRODUCT_SPU}
 *       归属「商品」这一个业务域，前端要按业务域计数并把提示挂到组上。</li>
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
  /** 夹具专用账号：始终带全部域权限（写入要过 RLS 策略，被测会话可能故意缺权限）。 */
  static final Map<UUID, AccessService.Access> seedActors = new ConcurrentHashMap<>();

  static final List<String> searchPermissions =
      List.of("identity.global_search.read", "catalog.sku.read", "catalog.spu.read");

  static AccessService.Access actor(
      UUID uid, UUID tenant, List<AccessService.Grant> grants) {
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
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
    r.add("DB_USER", () -> "catalog_app");
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

  /** 一对**每个用例独有**的租户，避免夹具互相污染。 */
  record Pair(String a, String b, UUID tenantA, UUID tenantB) {}

  static Pair newPair(String scope, List<String> permissions) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, List.of(grant(scope, permissions))));
    actors.put(bearerB, actor(user, tenantB, List.of(grant(scope, permissions))));
    // 夹具账号：始终带全部域权限。写入要走应用账号的 RLS 策略，
    // 而被测会话可能**故意**缺某个域权限 —— 用被测会话写夹具会被策略挡回去。
    seedActors.put(tenantA, actor(user, tenantA, List.of(grant("ALL", searchPermissions))));
    seedActors.put(tenantB, actor(user, tenantB, List.of(grant("ALL", searchPermissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  static Pair newPair() {
    return newPair("ALL", searchPermissions);
  }

  // ================================================================ 夹具

  /** 种一个 SPU + 一个 SKU（走应用账号，RLS 与 owner 触发器都生效）。 */
  void seedProduct(UUID tenant, String spuCode, String skuCode) {
    var actor = seedActors.get(tenant);
    access.run(
        actor,
        "catalog.sku.read",
        () -> {
          UUID spuId = UUID.randomUUID();
          sql.execute(
              "insert into spu(id,tenant_id,code,name,description,brand,category,status,version,"
                  + " created_at,updated_at) values(?::uuid,?::uuid,?::varchar,?::varchar,'','','','ON_SALE',0,now(),now())",
              spuId,
              tenant,
              spuCode,
              spuCode + " 名称");
          sql.execute(
              "insert into sku(id,tenant_id,spu_id,code,name,barcode,base_unit,status,variant_signature,"
                  + " version,created_at,updated_at) values(?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,"
                  + " '',?::varchar,'ACTIVE',?::varchar,0,now(),now())",
              UUID.randomUUID(),
              tenant,
              spuId,
              skuCode,
              skuCode + " 名称",
              "件",
              "sig-" + skuCode);
          return null;
        });
  }

  /** 种一个往来单位并指派角色（CUSTOMER / SUPPLIER）。 */
  void seedParty(UUID tenant, String code, String name, String roleType) {
    var actor = seedActors.get(tenant);
    access.run(
        actor,
        "catalog.spu.read",
        () -> {
          UUID partyId = UUID.randomUUID();
          sql.execute(
              "insert into party(id,tenant_id,code,name,party_type,country_code,email,phone,website,"
                  + " status,status_reason,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::varchar,?::varchar,'ORGANIZATION','CN',?::varchar,null,null,"
                  + " 'ACTIVE','',0,now(),now())",
              partyId,
              tenant,
              code,
              name,
              code + "@example.test");
          sql.execute(
              "insert into party_role(id,tenant_id,party_id,role_type,status,note,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::varchar,'ACTIVE','',0,now(),now())",
              UUID.randomUUID(),
              tenant,
              partyId,
              roleType);
          return null;
        });
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> search(String bearer, String keyword) throws Exception {
    String body =
        http.perform(
                get("/catalog/global-search")
                    .header("Authorization", bearer)
                    .param("q", keyword))
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
  void 商品组报出实际生效的数据范围() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-GRP-100", "SKU-GRP-001");

    Map<String, Object> product = group(search(p.a(), "SKU-GRP"), "PRODUCT");
    assertThat(product.get("dataScope")).as("ALL 授权 ⇒ 全租户可见").isEqualTo("ALL");
    assertThat((String) product.get("dataScopeNote")).as("范围要有说明文案").isNotBlank();
  }

  @Test
  void SELF范围时报SELF而不是ALL() throws Exception {
    Pair p = newPair("SELF", searchPermissions);
    seedProduct(p.tenantA(), "SPU-GRP-200", "SKU-GRP-002");

    assertThat(group(search(p.a(), "SKU-GRP"), "PRODUCT").get("dataScope")).isEqualTo("SELF");
  }

  @Test
  void 往来单位是租户级主数据报TENANT() throws Exception {
    Pair p = newPair("SELF", searchPermissions);
    seedParty(p.tenantA(), "CUS-GRP-001", "分组客户", "CUSTOMER");
    seedParty(p.tenantA(), "SUP-GRP-001", "分组供应商", "SUPPLIER");

    Map<String, Object> response = search(p.a(), "分组");
    // 授权范围是 SELF，但 party 表上没有归属列 ⇒ 看得见的是本租户全量，只能报 TENANT。
    assertThat(group(response, "CUSTOMER").get("dataScope")).isEqualTo("TENANT");
    assertThat(group(response, "SUPPLIER").get("dataScope")).isEqualTo("TENANT");
  }

  @Test
  void 无权限的子域带归属业务域进suppressedDomains() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer, actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SKU-GRP");
    // 权限不足 ⇒ 整组不返回（不是返回后再置灰）。
    assertThat(response.get("groups")).isEqualTo(List.of());
    assertThat(suppressed(response))
        .extracting(
            m -> m.get("domain") + "→" + m.get("group"))
        .contains("PRODUCT_SKU→PRODUCT", "PRODUCT_SPU→PRODUCT", "CUSTOMER→CUSTOMER", "SUPPLIER→SUPPLIER");
    // 不变式：被抑制的子域与 unavailableDomains 是同一次判定的两份说法，
    // 前端按业务域计数靠的就是这份对应关系。
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain"))
        .containsExactlyInAnyOrderElementsOf((List<String>) response.get("unavailableDomains"));
  }

  @Test
  void 部分子域被抑制时整组照常返回并带上归属() throws Exception {
    Pair p = newPair("ALL", List.of("identity.global_search.read", "catalog.spu.read"));
    seedProduct(p.tenantA(), "SPU-GRP-300", "SKU-GRP-003");

    Map<String, Object> response = search(p.a(), "SPU-GRP");
    // SKU 子域没权限，但商品组还有 SPU 命中 ⇒ 组照常返回。
    assertThat(primaryOf(response, "PRODUCT")).contains("SPU-GRP-300");
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain") + "→" + m.get("group"))
        .contains("PRODUCT_SKU→PRODUCT");
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain"))
        .doesNotContain("PRODUCT_SPU");
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-ISO-A", "SKU-ISO-A");
    seedProduct(p.tenantB(), "SPU-ISO-B", "SKU-ISO-B");

    assertThat(primaryOf(search(p.a(), "SKU-ISO"), "PRODUCT")).containsExactly("SKU-ISO-A");
    assertThat(primaryOf(search(p.b(), "SKU-ISO"), "PRODUCT")).containsExactly("SKU-ISO-B");
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-FORGED-A", "SKU-FORGED-A");
    seedProduct(p.tenantB(), "SPU-FORGED-B", "SKU-FORGED-B");

    String honest =
        http.perform(
                get("/catalog/global-search")
                    .header("Authorization", p.a())
                    .param("q", "SKU-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forged =
        http.perform(
                get("/catalog/global-search")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .param("q", "SKU-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份结果").isEqualTo(honest);
    assertThat(primaryOf(search(p.a(), "SKU-FORGED"), "PRODUCT")).containsExactly("SKU-FORGED-A");
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-POOL-A", "SKU-POOL-A");
    seedProduct(p.tenantB(), "SPU-POOL-B", "SKU-POOL-B");

    for (int i = 0; i < 5; i++) {
      assertThat(primaryOf(search(p.a(), "SKU-POOL"), "PRODUCT"))
          .as("第 %d 轮租户 A", i)
          .containsExactly("SKU-POOL-A");
      assertThat(primaryOf(search(p.b(), "SKU-POOL"), "PRODUCT"))
          .as("第 %d 轮租户 B", i)
          .containsExactly("SKU-POOL-B");
    }
  }
}
