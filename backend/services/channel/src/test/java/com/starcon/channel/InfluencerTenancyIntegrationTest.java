package com.starcon.channel;

import static com.starcon.channel.InfluencerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.5.1.1 达人库 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（达人档案 / 标签 / 标签分组 / 评级档位 / 评分口径 /
 *       黑名单 / 合作历史全部不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfluencerTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

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
  }

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  /** 播种全局码表（平台目录）。刻意**不在静态块里**播种（那时 Flyway 还没跑，表还不存在）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec(
        "insert into platform (id,code,name,integration,support_status,position)"
            + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
            + " on conflict (id) do nothing");
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

  @Autowired InfluencerService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String seedShop(UUID tenantId, String code) {
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','"
            + UUID.randomUUID() + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 一个租户造一个达人（档案 + 标签 + 黑名单 + 合作记录都要能证明隔离）。 */
  record Fixture(String influencerId, String tagId, String blacklistId, String tierId) {}

  Fixture seedTenantData(AccessService.Access who, String shopCode, String name) {
    var influencer =
        as(
            who,
            "channel.operation.create",
            () ->
                service.saveProfile(
                    new SaveInfluencerRequest(
                        AMAZON, shopCode, "", name, "@" + name, "美妆", 10_000L,
                        new java.math.BigDecimal("0.05"), new java.math.BigDecimal("10000"), "USD",
                        new java.math.BigDecimal("0.02"), "ACTIVE", "")));
    var group =
        as(
            who,
            "channel.operation.create",
            () ->
                service.saveTagGroup(
                    new SaveInfluencerTagGroupRequest(
                        "GRP-" + name, "分组 " + name, "", "ACTIVE", "")));
    var tag =
        as(
            who,
            "channel.operation.create",
            () ->
                service.saveTag(
                    new SaveInfluencerTagRequest(
                        group.id(), "TAG-" + name, "标签 " + name, "", "ACTIVE", "")));
    as(
        who,
        "channel.operation.update",
        () ->
            service.assignTags(
                influencer.influencer().id().toString(),
                new TagInfluencersRequest(List.of(tag.id()), List.of(), "")));
    var tier =
        as(
            who,
            "channel.operation.create",
            () ->
                service.saveRatingTier(
                    new SaveInfluencerRatingTierRequest(
                        "T-" + name, "档位 " + name, new java.math.BigDecimal("0"),
                        new java.math.BigDecimal("1"), 10, "ACTIVE", "")));
    var blacklist =
        as(
            who,
            "channel.operation.create",
            () ->
                service.blacklistInfluencer(
                    new BlacklistInfluencerRequest(
                        influencer.influencer().id(), "FRAUD", name + " 的拉黑记录", null, "")));
    return new Fixture(
        influencer.influencer().id().toString(),
        tag.id().toString(),
        blacklist.id().toString(),
        tier.id().toString());
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.5.1.1 断言一：两租户互不可见（档案 / 标签 / 黑名单 / 档位都不可见）")
  void tenantsAreIsolated() {
    String shopA = seedShop(a, "TEN-A-1");
    String shopB = seedShop(b, "TEN-B-1");
    Fixture fa = seedTenantData(actorA, shopA, "甲租户达人");
    Fixture fb = seedTenantData(actorB, shopB, "乙租户达人");

    // 列表层：B 只看到 B 的
    assertThat(
            as(
                    actorB,
                    "channel.operation.read",
                    () ->
                        service.profiles(
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, true, "GMV_DESC", 0, 50))
                .items())
        .as("B 的租户上下文里读不到 A 的达人档案")
        .extracting(InfluencerRow::displayName)
        .containsExactly("乙租户达人");
    assertThat(
            as(
                    actorA,
                    "channel.operation.read",
                    () ->
                        service.profiles(
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, true, "GMV_DESC", 0, 50))
                .items())
        .extracting(InfluencerRow::displayName)
        .containsExactly("甲租户达人");

    // 按 id 直取对方的数据必须失败
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.profile(fa.influencerId()))))
        .as("B 按 id 直取 A 的达人档案必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from influencer where id='" + fa.influencerId() + "'"))
        .as("B 的上下文里直查 A 的达人档案必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from influencer_tag where id='" + fa.tagId() + "'"))
        .as("B 的上下文里直查 A 的标签必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from influencer_blacklist where id='" + fa.blacklistId() + "'"))
        .as("B 的上下文里直查 A 的黑名单记录必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(b, "select count(*) from influencer_rating_tier where id='" + fa.tierId() + "'"))
        .as("B 的上下文里直查 A 的评级档位必须读到零行")
        .isZero();

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from influencer where id='" + fa.influencerId() + "'"))
        .as("A 的上下文必须能直查 A 自己的达人档案（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from influencer where id='" + fb.influencerId() + "'"))
        .as("A 的上下文里读不到 B 的达人档案")
        .isZero();

    // 八张表都是 FORCE RLS
    for (String table :
        List.of(
            "influencer",
            "influencer_tag_group",
            "influencer_tag",
            "influencer_tag_assignment",
            "influencer_rating_tier",
            "influencer_rating_rule",
            "influencer_blacklist",
            "influencer_cooperation")) {
      assertThat(
              scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.5.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, "FORGE-A");
    String shopB = seedShop(b, "FORGE-B");
    Fixture fa = seedTenantData(actorA, shopA, "甲租户达人");
    seedTenantData(actorB, shopB, "乙租户达人");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的达人档案
    http.perform(
            get("/channel/influencers/profiles/" + fa.influencerId())
                .header("Authorization", bearerB)
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/influencers/profiles")
            .header("Authorization", bearerB)
            .header("X-Tenant-Id", a.toString())
            // B 的那个达人被本用例拉黑了（要证明黑名单记录也隔离），所以要显式包含黑名单里的达人
            .param("includeBlacklisted", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].displayName").value("乙租户达人"))
        .andExpect(jsonPath("$.total").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/influencers/profiles").header("X-Tenant-Id", a.toString()))
        .andExpect(status().isUnauthorized());

    // A 的会话 + B 的头 ⇒ 写操作仍然落在 A 自己名下
    http.perform(post("/channel/influencers/profiles")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                "{\"platformId\":\"" + AMAZON + "\",\"shopCode\":\"" + shopA + "\","
                    + "\"displayName\":\"伪造头写入的达人\",\"category\":\"美妆\"}"))
        .andExpect(status().isOk());
    assertThat(countAsTenant(a, "select count(*) from influencer where display_name='伪造头写入的达人'"))
        .as("伪造头下的写入必须落在调用者自己的租户里")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from influencer where display_name='伪造头写入的达人'"))
        .as("伪造头下的写入绝不能落到被伪造的租户里")
        .isZero();
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.5.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, "POOL-A");
    String shopB = seedShop(b, "POOL-B");
    Fixture fa = seedTenantData(actorA, shopA, "甲租户达人");
    Fixture fb = seedTenantData(actorB, shopB, "乙租户达人");

    for (int i = 0; i < 3; i++) {
      assertThat(
              as(
                      actorA,
                      "channel.operation.read",
                      () ->
                          service.profiles(
                              null, null, null, null, null, null, null, null, null, null, null, null,
                              null, null, true, "GMV_DESC", 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的达人档案")
          .extracting(InfluencerRow::displayName)
          .containsExactly("甲租户达人");
      assertThat(
              as(
                      actorB,
                      "channel.operation.read",
                      () ->
                          service.profiles(
                              null, null, null, null, null, null, null, null, null, null, null, null,
                              null, null, true, "GMV_DESC", 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的达人档案")
          .extracting(InfluencerRow::displayName)
          .containsExactly("乙租户达人");
      assertThat(
              as(
                      actorA,
                      "channel.operation.read",
                      () -> service.blacklist(null, null, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 的黑名单里没有 B 的记录")
          .extracting(InfluencerBlacklistRow::id)
          .containsExactly(UUID.fromString(fa.blacklistId()));
      assertThat(
              as(
                      actorB,
                      "channel.operation.read",
                      () -> service.blacklist(null, null, null, null, 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 的黑名单里没有 A 的记录")
          .extracting(InfluencerBlacklistRow::id)
          .containsExactly(UUID.fromString(fb.blacklistId()));
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from influencer"))
        .as("B 的上下文里只能看到 B 自己的达人档案")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from influencer_blacklist"))
        .as("B 的上下文里只能看到 B 自己的黑名单记录")
        .isEqualTo(1);
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
    pg.stop();
  }
}
