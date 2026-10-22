package com.starcon.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.2.1.3 差评处理集成测试的<b>共享夹具</b>。
 *
 * <p><b>为什么三个测试类共用一个容器</b>：本车道的三个测试类
 * （{@link NegativeReviewIntegrationTest} / {@link NegativeReviewTenancyIntegrationTest} /
 * {@link NegativeReviewRulesTest}）跑的是**同一个业务库**上的**同一批表** ——
 * 每个类各起一个 PG 容器只会把同一套迁移跑三遍，容器数翻倍、启动时间翻倍，
 * 而断言强度一点不变。共享容器不改变任何一条断言的强度（角色仍然是
 * {@code NOSUPERUSER NOBYPASSRLS}，表仍然是 FORCE RLS 的真实表）。
 *
 * <p>⚠️ 共享容器的代价：三个测试类共用一个库 ⇒ **类之间必须自己清干净**。
 * 每个测试类的 {@code @BeforeEach} 都会 TRUNCATE 自己用到的表
 * （用迁移用户连接执行 —— RLS 对超管不生效，所以不需要先绑租户）。
 *
 * <p><b>上游 stub 真的起 HTTP 服务</b>（端口与真实 channel / trade 服务同形）：
 * 上游不可达时本模块必须显式失败（503 / available=false），
 * 用假对象替身就测不出「上游读不到」这条路径。
 */
final class NegativeReviewTestSupport {

  private NegativeReviewTestSupport() {}

  /** 本车道三个测试类共用的 PostgreSQL 16 容器。 */
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** identity 桩：按 bearer 返回已认证成员关系与权限。 */
  static final Map<String, AccessService.Access> ACTORS = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> SHOPS = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;

  static {
    PG.start();
    try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = ACTORS.get(e.getRequestHeaders().getFirst("Authorization"));
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
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext("/channel/shops", e -> write(e, List.copyOf(SHOPS)));
      channel.createContext(
          "/channel/logistics-pickup-signoff/cases",
          e -> write(e, Map.of("items", List.of(), "totalElements", 0)));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders",
          e -> write(e, Map.of("rows", List.of(), "page", 0, "size", 0, "total", 0)));
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 写一个 JSON 响应。 */
  static void write(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 建一个已认证成员（权限组必须同时含动作与对应的 {@code .read}）。 */
  static AccessService.Access actor(
      UUID userId, UUID tenantId, String tenantName, List<String> permissions) {
    return new AccessService.Access(
        userId,
        tenantId,
        tenantName,
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  /** 本模块的权限码。 */
  static final List<String> PERMISSIONS =
      List.of("crm.negative_review.read", "crm.negative_review.update");

  /** 用迁移用户（超管）连接执行一条 DDL —— RLS 对超管不生效，不需要先绑租户。 */
  static void asMigrationUser(String sql) {
    try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
