package com.starcon.channel;

import static com.starcon.channel.ListingCollectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.4.1.1 链接采集 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本测试类也用**真实的本地 HTTP 服务**当"外部站点"：采集是真的抓真的页面，
 * 这样隔离断言是在**真实写入**之后做的，而不是在手工插的行上做的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCollectTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer shop;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();

      // 🔴 真实的"外部站点"：每个租户一个路径，页面标题带上租户标记，
      //    这样"读到的是不是自己的数据"可以逐字断言，而不是只看行数。
      shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      shop.createContext(
          "/",
          e -> {
            String path = e.getRequestURI().getPath();
            String id = path.replaceAll("[^0-9]", "");
            if (id.isEmpty()) id = "1";
            byte[] body =
                ("<html><head><meta property=\"og:title\" content=\"租户商品-"
                        + id
                        + "\">"
                        + "<meta property=\"og:image\" content=\"https://cdn.example.com/"
                        + id
                        + ".jpg\">"
                        + "<meta property=\"og:description\" content=\"描述 "
                        + id
                        + "\">"
                        + "</head><body>商品 "
                        + id
                        + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      shop.start();
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingCollectService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);

    // 🔴 白名单里补一行指向本地测试服务的来源（必须在 Flyway 之后做）
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0003-0000-4000-8000-000000000001','TEST_LOOPBACK_IP','本地测试站点',"
            + "'127.0.0.1','PLATFORM',0,true,900)"
            + " on conflict (id) do update set min_interval_seconds=0");
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>🔴 必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
   * 那不是隔离被破坏，而是这个断言本身失效。
   */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String url(String path) {
    return "http://127.0.0.1:" + shop.getAddress().getPort() + path;
  }

  CollectListingResult collect(AccessService.Access who, String path) {
    return as(who, "channel.operation.create", () -> service.collect(new CollectListingRequest(url(path), ""), user));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 断言一：两租户互不可见（采集任务 / 采集草稿都不可见）")
  void tenantsAreIsolated() {
    var resultA = collect(actorA, "/product/1");
    assertThat(resultA.task().state()).isEqualTo("SUCCEEDED");

    // B 看不到 A 的采集历史
    assertThat(as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50)).items())
        .as("B 的租户上下文里读不到 A 的采集记录").isEmpty();
    // B 拿 A 的采集任务 id 直查详情 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.detail(resultA.task().taskId().toString()))))
        .as("B 不能读到 A 的采集任务详情").isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from listing_collect_task where id='" + resultA.task().taskId() + "'"))
        .as("B 的上下文里直查 A 的采集任务必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_collect_draft where id='" + resultA.draft().draftId() + "'"))
        .as("B 的上下文里直查 A 的采集草稿必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_collect_task"))
        .as("B 的上下文里看不到任何采集任务").isZero();

    // 反向同理
    var resultB = collect(actorB, "/product/2");
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task where id='" + resultB.task().taskId() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft where id='" + resultB.draft().draftId() + "'"))
        .isZero();

    // 两张新表都是 FORCE RLS
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_collect_task'"))
        .as("listing_collect_task 必须 FORCE RLS").isEqualTo("true");
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_collect_draft'"))
        .as("listing_collect_draft 必须 FORCE RLS").isEqualTo("true");

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task where id='" + resultA.task().taskId() + "'"))
        .as("A 的上下文必须能直查 A 自己的采集任务（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft where id='" + resultA.draft().draftId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var resultA = collect(actorA, "/product/3");
    var resultB = collect(actorB, "/product/4");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的采集任务
    http.perform(get("/channel/listing-collects/" + resultB.task().taskId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-collects")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].taskId").value(resultA.task().taskId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/listing-collects")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-collects/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    var resultA = collect(actorA, "/product/5");
    var resultB = collect(actorB, "/product/6");

    for (int i = 0; i < 3; i++) {
      // A 读 A 的、B 读 B 的，各三轮
      var listA = as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50));
      assertThat(listA.items()).hasSize(1);
      assertThat(listA.items().get(0).taskId()).isEqualTo(resultA.task().taskId());
      assertThat(listA.items().get(0).title()).isEqualTo("租户商品-5");

      var listB = as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50));
      assertThat(listB.items()).hasSize(1);
      assertThat(listB.items().get(0).taskId()).isEqualTo(resultB.task().taskId());
      assertThat(listB.items().get(0).title()).isEqualTo("租户商品-6");

      // 草稿内容也必须跟着租户走：串了就会读到别人的草稿
      var detailA = as(actorA, "channel.operation.read", () -> service.detail(resultA.task().taskId().toString()));
      assertThat(detailA.draft().title()).isEqualTo("租户商品-5");
      var detailB = as(actorB, "channel.operation.read", () -> service.detail(resultB.task().taskId().toString()));
      assertThat(detailB.draft().title()).isEqualTo("租户商品-6");
    }
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    if (shop != null) shop.stop(0);
    pg.stop();
  }
}
