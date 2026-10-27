package com.starcon.channel;

import static com.starcon.channel.ListingCollectBoxModels.*;
import static com.starcon.channel.ListingCollectModels.CollectListingRequest;
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
 * 4.4.1.3 采集箱 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
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
 * <p>🔴 本测试类也用**真实的本地 HTTP 服务**当"外部站点"：采集草稿是真的抓出来的，
 * 隔离断言是在**真实写入**之后做的，而不是在手工插的行上做的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCollectBoxTenancyIntegrationTest {

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

      // 🔴 真实的"外部站点"：页面标题带上路径里的编号，这样"读到的是不是自己的数据"可以逐字断言
      shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      shop.createContext(
          "/",
          e -> {
            String id = e.getRequestURI().getPath().replaceAll("[^0-9]", "");
            if (id.isEmpty()) id = "1";
            byte[] body =
                ("<html><head>"
                        + "<meta property=\"og:title\" content=\"租户采集商品-"
                        + id
                        + "\">"
                        + "<meta property=\"og:image\" content=\"https://cdn.example.com/"
                        + id
                        + ".jpg\">"
                        + "<meta property=\"product:brand\" content=\"Acme\">"
                        + "<script type=\"application/ld+json\">"
                        + "{\"@context\":\"https://schema.org/\",\"@type\":\"Product\","
                        + "\"name\":\"租户采集商品-"
                        + id
                        + "\",\"category\":\"女装\","
                        + "\"additionalProperty\":[{\"@type\":\"PropertyValue\",\"name\":\"颜色\","
                        + "\"value\":\"红\"}]}"
                        + "</script></head><body>商品 "
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

  @Autowired ListingCollectService collectService;
  @Autowired ListingCollectBoxService box;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer box-a-" + a;
    bearerB = "Bearer box-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);

    // 🔴 白名单里补一行指向本地测试服务的来源（必须在 Flyway 之后做）
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0004-0000-4000-8000-000000000011','TEST_BOX','本地测试站点','127.0.0.1',"
            + "'PLATFORM',0,true,910)"
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

  /** 真抓 → 进箱 → 建规则 → 认领，一步到位（两个租户各做一遍）。 */
  UUID boxItem(AccessService.Access who, String path) {
    var collect =
        as(
            who,
            "channel.operation.create",
            () -> collectService.collect(new CollectListingRequest(url(path), ""), user));
    assertThat(collect.task().state()).isEqualTo("SUCCEEDED");
    UUID draftId = collect.draft().draftId();
    var enroll = as(who, "channel.operation.create", () -> box.enroll(List.of(draftId)));
    assertThat(enroll.succeeded()).isEqualTo(1);
    return enroll.outcomes().get(0).itemId();
  }

  ListingCollectBoxClaimRuleRow rule(AccessService.Access who, String name) {
    return as(
        who,
        "channel.operation.create",
        () ->
            box.createRule(
                new CreateClaimRuleRequest(
                    name, "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}", null, null)));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 断言一：两租户互不可见（条目 / 标签 / 规则 / 认领结果都不可见）")
  void tenantsAreIsolated() {
    UUID itemA = boxItem(actorA, "/product/11");
    UUID itemB = boxItem(actorB, "/product/12");
    var ruleA = rule(actorA, "A 的规则");
    var ruleB = rule(actorB, "B 的规则");
    var tagA = as(actorA, "channel.operation.create", () -> box.createTag(new CreateBoxTagRequest("A 的标签", "")));
    as(actorA, "channel.operation.update", () -> box.tag(new TagBoxItemsRequest(List.of(itemA), tagA.tagId(), "ADD")));
    as(actorA, "channel.operation.update", () -> box.claim(List.of(itemA), ruleA.ruleId().toString()));

    // ---- 服务层：B 的上下文里**只有 B 自己的**，A 的一条都看不到
    var listB = as(actorB, "channel.operation.read", () -> box.listItems(null, null, null, null, 0, 50));
    assertThat(listB.items()).hasSize(1);
    assertThat(listB.items().get(0).itemId()).as("B 只能看到自己的条目").isEqualTo(itemB);
    assertThat(listB.items().get(0).title()).isEqualTo("租户采集商品-12");
    assertThat(listB.total()).isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> box.groups()).total()).isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> box.listTags()))
        .as("B 没有建过标签")
        .isEmpty();
    var rulesB = as(actorB, "channel.operation.read", () -> box.listRules(0, 50));
    assertThat(rulesB.items()).hasSize(1);
    assertThat(rulesB.items().get(0).ruleId()).isEqualTo(ruleB.ruleId());
    assertThat(as(actorB, "channel.operation.read", () -> box.candidates(null, null, 0, 50)).items())
        .as("B 的上下文里看不到 A 的采集草稿（草稿本身也是租户隔离的）")
        .isEmpty();

    // B 拿 A 的条目 id 直查详情 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> box.itemDetail(itemA.toString()))))
        .as("B 不能读到 A 的采集箱条目详情")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from listing_collect_box_item where id='" + itemA + "'"))
        .as("B 的上下文里直查 A 的采集箱条目必须读到零行")
        .isZero();
    // B 的上下文里**只有 B 自己那一条**（B 也确实有一条 —— 零行说明查询整体失效）
    assertThat(countAsTenant(b, "select count(*) from listing_collect_box_item"))
        .as("B 的上下文里只看得到 B 自己的那一条")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from listing_collect_box_item where id='" + itemB + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from listing_collect_box_item_tag")).isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_collect_claim_result")).isZero();
    // B 自己也建了一条规则 ⇒ 看到的是**自己那一条**，不是零行（零行说明查询整体失效）
    assertThat(countAsTenant(b, "select count(*) from listing_collect_claim_rule")).isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_collect_claim_rule where id='" + ruleB.ruleId() + "'"))
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_collect_claim_rule where id='" + ruleA.ruleId() + "'"))
        .as("B 的上下文里直查 A 的认领规则必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_collect_box_tag")).isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from listing_collect_box_item where id='" + itemB + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_collect_claim_rule where id='" + ruleB.ruleId() + "'"))
        .isZero();

    // 五张新表都是 FORCE RLS
    for (String table :
        List.of(
            "listing_collect_box_tag",
            "listing_collect_box_item",
            "listing_collect_box_item_tag",
            "listing_collect_claim_rule",
            "listing_collect_claim_result")) {
      assertThat(
              scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from listing_collect_box_item where id='" + itemA + "'"))
        .as("A 的上下文必须能直查 A 自己的采集箱条目（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_claim_result")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_claim_rule")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_box_tag")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_box_item_tag")).isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID itemA = boxItem(actorA, "/product/21");
    UUID itemB = boxItem(actorB, "/product/22");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的条目
    http.perform(get("/channel/listing-collect-box/items/" + itemB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-collect-box/items")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].itemId").value(itemA.toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/listing-collect-box/items")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-collect-box/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID itemA = boxItem(actorA, "/product/31");
    UUID itemB = boxItem(actorB, "/product/32");
    var ruleA = rule(actorA, "A 的规则");
    as(actorA, "channel.operation.update", () -> box.claim(List.of(itemA), ruleA.ruleId().toString()));

    for (int i = 0; i < 3; i++) {
      // A 读 A 的、B 读 B 的，各三轮
      var listA = as(actorA, "channel.operation.read", () -> box.listItems(null, null, null, null, 0, 50));
      assertThat(listA.items()).hasSize(1);
      assertThat(listA.items().get(0).itemId()).isEqualTo(itemA);
      assertThat(listA.items().get(0).title()).isEqualTo("租户采集商品-31");

      var listB = as(actorB, "channel.operation.read", () -> box.listItems(null, null, null, null, 0, 50));
      assertThat(listB.items()).hasSize(1);
      assertThat(listB.items().get(0).itemId()).isEqualTo(itemB);
      assertThat(listB.items().get(0).title()).isEqualTo("租户采集商品-32");

      // 认领结果也必须跟着租户走：串了就会读到别人的认领结果
      var claimB = as(actorB, "channel.operation.read", () -> box.listRules(0, 50));
      assertThat(claimB.items()).as("B 的上下文里看不到 A 的认领规则").isEmpty();

      // 详情也必须跟着租户走
      var detailA = as(actorA, "channel.operation.read", () -> box.itemDetail(itemA.toString()));
      assertThat(detailA.title()).isEqualTo("租户采集商品-31");
      var detailB = as(actorB, "channel.operation.read", () -> box.itemDetail(itemB.toString()));
      assertThat(detailB.title()).isEqualTo("租户采集商品-32");
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
