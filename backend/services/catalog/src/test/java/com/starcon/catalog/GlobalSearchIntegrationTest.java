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
 * 1.2.1.1 全局搜索框 —— catalog 侧只读检索的集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>（不只是「能返回数据」）：
 *
 * <ol>
 *   <li><b>前缀语义</b>：搜「SKU-AX」命中 {@code SKU-AX-001}，搜「X-001」**命中不到** ——
 *       蓝图写死「只做前缀/精确匹配，不做全文语义检索」，
 *       一旦有人把 {@code ilike 'kw%'} 改成 {@code ilike '%kw%'}，这条断言立刻变红。</li>
 *   <li><b>≥2 字符的后端校验</b>：前端也挡一道，但后端是权威，所以后端必须自己 400。</li>
 *   <li><b>没权限的域 ≠ 没有数据的域</b>：域权限缺失时该域进 {@code unavailableDomains}，
 *       而不是让整个搜索 403，也不是静默返回一个空组。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。检索是最容易漏租户过滤的地方（一个 {@code ilike} 就跨租户了）。</li>
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
      List.of("identity.global_search.read", "catalog.sku.read", "catalog.spu.read");

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

  static Pair newPair(List<String> permissions) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String bearerA = "Bearer a-" + tenantA;
    String bearerB = "Bearer b-" + tenantB;
    actors.put(bearerA, actor(user, tenantA, List.of(grant("ALL", permissions))));
    actors.put(bearerB, actor(user, tenantB, List.of(grant("ALL", permissions))));
    return new Pair(bearerA, bearerB, tenantA, tenantB);
  }

  static Pair newPair() {
    return newPair(searchPermissions);
  }

  // ================================================================ 夹具

  /** 种一个 SPU + 一个 SKU（走应用账号，RLS 与 owner 触发器都生效）。 */
  void seedProduct(UUID tenant, String spuCode, String skuCode) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
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
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
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
  void 搜SKU按编码前缀命中且不含子串() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-AX-100", "SKU-AX-001");

    var hit = search(p.a(), "SKU-AX");
    assertThat(primaryOf(hit, "PRODUCT")).contains("SKU-AX-001");

    // 前缀语义：子串不该命中（蓝图：只做前缀/精确匹配）。
    assertThat(primaryOf(search(p.a(), "X-001"), "PRODUCT")).isEmpty();
    // 大小写不敏感（ilike），但仍然是前缀。
    assertThat(primaryOf(search(p.a(), "sku-ax"), "PRODUCT")).contains("SKU-AX-001");
  }

  @Test
  void 搜SPU按编码前缀命中() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-BR-200", "SKU-BR-002");

    var hit = search(p.a(), "SPU-BR");
    assertThat(primaryOf(hit, "PRODUCT")).contains("SPU-BR-200");
  }

  @Test
  void 搜客户按名称前缀命中且不串到供应商() throws Exception {
    Pair p = newPair();
    seedParty(p.tenantA(), "CUS-001", "北京客户科技", "CUSTOMER");
    seedParty(p.tenantA(), "SUP-001", "北京供应商", "SUPPLIER");

    assertThat(primaryOf(search(p.a(), "北京客户"), "CUSTOMER")).contains("CUS-001");
    assertThat(primaryOf(search(p.a(), "北京客户"), "SUPPLIER")).isEmpty();
    assertThat(primaryOf(search(p.a(), "SUP-001"), "SUPPLIER")).contains("SUP-001");
  }

  @Test
  void 关键词少于两个字符后端直接400() throws Exception {
    Pair p = newPair();
    http.perform(
            get("/catalog/global-search").header("Authorization", p.a()).param("q", "S"))
        .andExpect(status().isBadRequest());
    http.perform(get("/catalog/global-search").header("Authorization", p.a()).param("q", "  "))
        .andExpect(status().isBadRequest());
    // 缺参数同样是 400（前端会挡，但后端是权威）。
    http.perform(get("/catalog/global-search").header("Authorization", p.a()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 没有域权限时域进unavailableDomains而不是整体403() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(bearer, actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SKU-AX");
    assertThat(response.get("groups")).as("没有任何域权限时不该有命中组").isEqualTo(List.of());
    @SuppressWarnings("unchecked")
    List<String> unavailable = (List<String>) response.get("unavailableDomains");
    assertThat(unavailable).contains("PRODUCT_SKU", "PRODUCT_SPU", "CUSTOMER", "SUPPLIER");
  }

  @Test
  void 能力声明逐条报出接通与未接通的域() throws Exception {
    Pair p = newPair();
    String body =
        http.perform(get("/catalog/global-search/capabilities").header("Authorization", p.a()))
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
      assertThat((String) domain.get("ownerModule")).as("每条都要写明归属模块").isNotBlank();
      assertThat((String) domain.get("impact")).as("每条都要写明影响").isNotBlank();
      if (Boolean.TRUE.equals(domain.get("available"))) {
        assertThat((String) domain.get("source")).as("接通的域必须写出表 + 列依据").isNotBlank();
      } else {
        assertThat((String) domain.get("source")).as("未接通的域不该有依据").isNull();
      }
    }
    assertThat(domains).anyMatch(d -> "LISTING".equals(d.get("domain")) && !d.get("available").equals(true));
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedProduct(p.tenantA(), "SPU-ISO-A", "SKU-ISO-A");
    seedProduct(p.tenantB(), "SPU-ISO-B", "SKU-ISO-B");

    assertThat(primaryOf(search(p.a(), "SKU-ISO"), "PRODUCT")).containsExactly("SKU-ISO-A");
    assertThat(primaryOf(search(p.b(), "SKU-ISO"), "PRODUCT")).containsExactly("SKU-ISO-B");

    seedParty(p.tenantA(), "CUS-ISO-A", "隔离客户甲", "CUSTOMER");
    seedParty(p.tenantB(), "CUS-ISO-B", "隔离客户乙", "CUSTOMER");
    assertThat(primaryOf(search(p.a(), "隔离客户"), "CUSTOMER")).containsExactly("CUS-ISO-A");
    assertThat(primaryOf(search(p.b(), "隔离客户"), "CUSTOMER")).containsExactly("CUS-ISO-B");
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

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 N 次读到的仍然是 B 而不是上一轮残留的 A。
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
