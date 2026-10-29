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
 * 1.2.1.2 搜索结果分组 —— channel 侧的分组元数据集成测试。
 *
 * <p><b>全部走 HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>组报出实际生效的数据范围</b>：ALL / SELF 都要如实报出来，
 *       界面靠它标注「这一组是不是在你的数据范围内」。</li>
 *   <li><b>被权限抑制的子域要带归属业务域</b>（SHOP）。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalSearchGroupIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  /** 夹具专用账号：始终带全部域权限（写入要过策略，被测会话可能故意缺权限）。 */
  static final Map<UUID, AccessService.Access> seedActors = new ConcurrentHashMap<>();

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

  /** 种一个店铺（走应用账号：owner 触发器要 app.actor，data_scope 策略要 app.grants）。 */
  void seedShop(UUID tenant, String code, String name) {
    var actor = seedActors.get(tenant);
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
  void 店铺组报出实际生效的数据范围() throws Exception {
    Pair p = newPair();
    seedShop(p.tenantA(), "SH-GRP-001", "分组旗舰店");

    Map<String, Object> shop = group(search(p.a(), "SH-GRP"), "SHOP");
    assertThat(shop.get("dataScope")).as("ALL 授权 ⇒ 全租户可见").isEqualTo("ALL");
    assertThat((String) shop.get("dataScopeNote")).as("范围要有说明文案").isNotBlank();
  }

  @Test
  void SELF范围时报SELF() throws Exception {
    Pair p = newPair("SELF", searchPermissions);
    seedShop(p.tenantA(), "SH-GRP-002", "分组自营店");

    assertThat(group(search(p.a(), "SH-GRP"), "SHOP").get("dataScope")).isEqualTo("SELF");
  }

  @Test
  void 无权限的域带归属业务域进suppressedDomains() throws Exception {
    UUID tenantA = UUID.randomUUID();
    String bearer = "Bearer search-only-" + tenantA;
    actors.put(
        bearer,
        actor(user, tenantA, List.of(grant("ALL", List.of("identity.global_search.read")))));

    Map<String, Object> response = search(bearer, "SH-GRP");
    assertThat(response.get("groups")).as("权限不足 ⇒ 整组不返回").isEqualTo(List.of());
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain") + "→" + m.get("group"))
        .contains("SHOP→SHOP");
    // 不变式：被抑制的子域与 unavailableDomains 是同一次判定的两份说法。
    assertThat(suppressed(response))
        .extracting(m -> m.get("domain"))
        .containsExactlyInAnyOrderElementsOf((List<String>) response.get("unavailableDomains"));
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
