package com.starcon.channel;

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
 * 1.2.1.1 全局搜索框 —— channel 侧只读检索的集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>店名也能搜</b>：蓝图写的是「店铺名」，只匹配编码的话输入「北京」会一条都搜不到。</li>
 *   <li><b>前缀语义</b>：子串不该命中。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。店铺是最容易漏租户过滤的地方之一（它还有 owner / department 范围列）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalSearchIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** V1 迁移种好的 Amazon 平台行（平台是全局字典表，测试直接用它，不重复造）。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final List<String> searchPermissions =
      List.of("identity.global_search.read", "channel.shop.read");

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
        s.execute("create role channel_app login password 'test-only' nosuperuser nobypassrls");
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
    r.add("DB_USER", () -> "channel_app");
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

  /** 种一个店铺（走应用账号：owner 触发器要 app.actor，data_scope 策略要 app.grants）。 */
  void seedShop(UUID tenant, String code, String name) {
    var actor = actors.values().stream().filter(x -> x.tenantId().equals(tenant)).findFirst().orElseThrow();
    access.run(
        actor,
        "channel.shop.read",
        () -> {
          sql.execute(
              "insert into shop(id,tenant_id,platform_id,code,name,platform_shop_id,operating_mode,"
                  + "status,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,'SELF_FULFILLED',"
                  + " 'ACTIVE',0,now(),now())",
              UUID.randomUUID(),
              tenant,
              AMAZON,
              code,
              name,
              "PS-" + code);
          return null;
        });
  }

  // ================================================================ 工具

  @SuppressWarnings("unchecked")
  Map<String, Object> search(String bearer, String keyword) throws Exception {
    String body =
        http.perform(
                get("/channel/global-search").header("Authorization", bearer).param("q", keyword))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<String> primaryOf(Map<String, Object> response, String domain) {
    List<String> out = new ArrayList<>();
    Object groups = response.get("groups");
    if (!(groups instanceof List)) return out;
    for (Object g : (List<Object>) groups) {
      Map<String, Object> group = (Map<String, Object>) g;
      if (!domain.equals(group.get("domain"))) continue;
      Object hits = group.get("hits");
      if (!(hits instanceof List)) continue;
      for (Object hit : (List<Object>) hits) out.add((String) ((Map<String, Object>) hit).get("primary"));
    }
    return out;
  }

  // ================================================================ 检索语义

  @Test
  void 搜店铺按编码与店名前缀命中() throws Exception {
    Pair p = newPair();
    seedShop(p.tenantA(), "SH-BJ-001", "北京旗舰店");

    assertThat(primaryOf(search(p.a(), "SH-BJ"), "SHOP")).contains("SH-BJ-001");
    assertThat(primaryOf(search(p.a(), "北京旗舰"), "SHOP")).contains("SH-BJ-001");
    // 前缀语义：子串不该命中。
    assertThat(primaryOf(search(p.a(), "BJ-001"), "SHOP")).isEmpty();
  }

  @Test
  void 关键词少于两个字符后端直接400() throws Exception {
    Pair p = newPair();
    http.perform(get("/channel/global-search").header("Authorization", p.a()).param("q", "S"))
        .andExpect(status().isBadRequest());
    http.perform(get("/channel/global-search").header("Authorization", p.a()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 没有域权限时域进unavailableDomains而不是整体403() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SH-BJ");
    assertThat(response.get("groups")).isEqualTo(List.of());
    @SuppressWarnings("unchecked")
    List<String> unavailable = (List<String>) response.get("unavailableDomains");
    assertThat(unavailable).contains("SHOP");
  }

  @Test
  void 能力声明逐条报出接通与未接通的域() throws Exception {
    Pair p = newPair();
    String body =
        http.perform(get("/channel/global-search/capabilities").header("Authorization", p.a()))
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
        .anyMatch(d -> "LISTING".equals(d.get("domain")) && !d.get("available").equals(true));
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见() throws Exception {
    Pair p = newPair();
    seedShop(p.tenantA(), "SH-ISO-A", "隔离店铺甲");
    seedShop(p.tenantB(), "SH-ISO-B", "隔离店铺乙");

    assertThat(primaryOf(search(p.a(), "SH-ISO"), "SHOP")).containsExactly("SH-ISO-A");
    assertThat(primaryOf(search(p.b(), "SH-ISO"), "SHOP")).containsExactly("SH-ISO-B");
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    Pair p = newPair();
    seedShop(p.tenantA(), "SH-FORGED-A", "伪造甲");
    seedShop(p.tenantB(), "SH-FORGED-B", "伪造乙");

    String honest =
        http.perform(
                get("/channel/global-search")
                    .header("Authorization", p.a())
                    .param("q", "SH-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forged =
        http.perform(
                get("/channel/global-search")
                    .header("Authorization", p.a())
                    .header("X-Tenant-Id", p.tenantB().toString())
                    .param("q", "SH-FORGED"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).as("伪造 X-Tenant-Id 不得改变看到的那份结果").isEqualTo(honest);
    assertThat(primaryOf(search(p.a(), "SH-FORGED"), "SHOP")).containsExactly("SH-FORGED-A");
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    Pair p = newPair();
    seedShop(p.tenantA(), "SH-POOL-A", "池甲");
    seedShop(p.tenantB(), "SH-POOL-B", "池乙");

    for (int i = 0; i < 5; i++) {
      assertThat(primaryOf(search(p.a(), "SH-POOL"), "SHOP"))
          .as("第 %d 轮租户 A", i)
          .containsExactly("SH-POOL-A");
      assertThat(primaryOf(search(p.b(), "SH-POOL"), "SHOP"))
          .as("第 %d 轮租户 B", i)
          .containsExactly("SH-POOL-B");
    }
  }
}
