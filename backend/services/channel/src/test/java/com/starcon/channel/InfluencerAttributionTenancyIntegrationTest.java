package com.starcon.channel;

import static com.starcon.channel.InfluencerAttributionModels.*;
import static com.starcon.channel.InfluencerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * 14.5.2.1 带货归因 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（归因记录 / 归因规则都不可见）
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
class InfluencerAttributionTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-01T00:00:00+08:00");
  static final OffsetDateTime ENDED_AT = OffsetDateTime.parse("2026-09-30T23:59:59+08:00");
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

  @Autowired InfluencerAttributionService service;
  @Autowired InfluencerService influencerService;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
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

  String seedShop(UUID tenantId, String code) {
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','"
            + UUID.randomUUID() + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  UUID seedCampaign(UUID tenantId, String shopCode, String externalId, String name) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','SPONSORED_BRAND')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
    UUID profileId = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into ad_type_profile (tenant_id,id,platform_id,platform_ad_type,internal_ad_type,"
            + " display_name,auction_model,objective,mapping_source,enum_kind)"
            + " values ('" + tenantId + "','" + profileId + "','" + AMAZON + "','sponsored_products',"
            + "'SPONSORED_BRAND','赞助商品','CPC','SALES','PLATFORM_DEFAULT','AD_TYPE')"
            + " on conflict (tenant_id,id) do nothing");
    UUID campaignId = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into ad_campaign (tenant_id,id,platform_id,shop_code,ad_type_profile_id,"
            + " external_campaign_id,campaign_name,status,currency,data_as_of,last_synced_at)"
            + " values ('" + tenantId + "','" + campaignId + "','" + AMAZON + "','" + shopCode + "','"
            + profileId + "','" + externalId + "','" + name + "','ENABLED','USD',now(),now())");
    return campaignId;
  }

  /** 一个租户造一整套归因事实（档案 + 合作 + 规则 + 归因记录）。 */
  record Fixture(String influencerId, String ruleId, String attributionId) {}

  Fixture seedTenantData(AccessService.Access who, String shopCode, String name) {
    UUID campaign = seedCampaign(who.tenantId(), shopCode, "EXT-" + name, "活动 " + name);
    UUID influencer =
        as(
                who,
                "channel.operation.create",
                () ->
                    influencerService.saveProfile(
                            new SaveInfluencerRequest(
                                AMAZON, shopCode, "", name, "@" + name, "美妆", 10_000L,
                                new BigDecimal("0.05"), new BigDecimal("10000"), "USD",
                                new BigDecimal("0.02"), "ACTIVE", ""))
                        .influencer()
                        .id());
    as(
        who,
        "channel.operation.create",
        () ->
            influencerService.saveCooperation(
                new SaveCooperationRequest(
                    influencer, campaign, "COMPLETED", STARTED_AT, ENDED_AT,
                    new BigDecimal("5000"), new BigDecimal("50"), new BigDecimal("500"),
                    new BigDecimal("0.02"), "USD", "")));
    InfluencerAttributionRuleRow rule =
        as(
            who,
            "channel.operation.update",
            () ->
                service.saveRule(
                    new SaveInfluencerAttributionRuleRequest(
                        "RULE-" + name, "COOPERATION_FULL", "FOLLOWER_SHARE", 30, "GROSS",
                        "COOPERATION_COMMISSION", null, "ACTIVE", "")));
    as(
        who,
        "channel.operation.create",
        () -> service.run(new InfluencerAttributionRunRequest(null, null, null, null, null)));
    String attributionId =
        as(
                who,
                "channel.operation.read",
                () ->
                    service.attributions(
                            null, null, null, null, null, null, null, null, null,
                            "ATTRIBUTED_GMV_DESC", 0, 50)
                        .items()
                        .get(0)
                        .id()
                        .toString());
    return new Fixture(influencer.toString(), rule.id().toString(), attributionId);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 断言一：两租户互不可见（归因记录 / 归因规则都不可见）")
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
                        service.attributions(
                            null, null, null, null, null, null, null, null, null,
                            "ATTRIBUTED_GMV_DESC", 0, 50))
                .items())
        .as("B 的租户上下文里读不到 A 的归因记录")
        .extracting(InfluencerAttributionRow::influencerName)
        .containsExactly("乙租户达人");
    assertThat(
            as(
                    actorA,
                    "channel.operation.read",
                    () ->
                        service.attributions(
                            null, null, null, null, null, null, null, null, null,
                            "ATTRIBUTED_GMV_DESC", 0, 50))
                .items())
        .extracting(InfluencerAttributionRow::influencerName)
        .containsExactly("甲租户达人");

    // 按 id 直取对方的数据必须失败
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorB,
                        "channel.operation.read",
                        () -> service.attribution(fa.attributionId()))))
        .as("B 按 id 直取 A 的归因记录必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(
            countAsTenant(
                b, "select count(*) from influencer_attribution where id='" + fa.attributionId() + "'"))
        .as("B 的上下文里直查 A 的归因记录必须读到零行")
        .isZero();
    assertThat(
            countAsTenant(b, "select count(*) from influencer_attribution_rule where id='" + fa.ruleId() + "'"))
        .as("B 的上下文里直查 A 的归因规则必须读到零行")
        .isZero();

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(
            countAsTenant(
                a, "select count(*) from influencer_attribution where id='" + fa.attributionId() + "'"))
        .as("A 的上下文必须能直查 A 自己的归因记录（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                a, "select count(*) from influencer_attribution where id='" + fb.attributionId() + "'"))
        .as("A 的上下文里读不到 B 的归因记录")
        .isZero();

    // 两张表都是 FORCE RLS
    for (String table : List.of("influencer_attribution", "influencer_attribution_rule")) {
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
  @DisplayName("14.5.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    String shopA = seedShop(a, "FORGE-A");
    String shopB = seedShop(b, "FORGE-B");
    Fixture fa = seedTenantData(actorA, shopA, "甲租户达人");
    seedTenantData(actorB, shopB, "乙租户达人");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的归因记录
    http.perform(
            get("/channel/attributions/records/" + fa.attributionId())
                .header("Authorization", bearerB)
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/attributions/records")
            .header("Authorization", bearerB)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].influencerName").value("乙租户达人"))
        .andExpect(jsonPath("$.total").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/attributions/records").header("X-Tenant-Id", a.toString()))
        .andExpect(status().isUnauthorized());

    // A 的会话 + B 的头 ⇒ 写操作仍然落在 A 自己名下（归因规则）
    http.perform(post("/channel/attributions/rules")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                "{\"ruleCode\":\"FORGED-RULE\",\"attributionModel\":\"COOPERATION_FULL\","
                    + "\"attributionWindowDays\":30,\"refundTreatment\":\"GROSS\","
                    + "\"commissionBasis\":\"COOPERATION_COMMISSION\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());
    assertThat(countAsTenant(a, "select count(*) from influencer_attribution_rule where rule_code='FORGED-RULE'"))
        .as("伪造头下的写入必须落在调用者自己的租户里")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from influencer_attribution_rule where rule_code='FORGED-RULE'"))
        .as("伪造头下的写入绝不能落到被伪造的租户里")
        .isZero();
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.5.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    String shopA = seedShop(a, "POOL-A");
    String shopB = seedShop(b, "POOL-B");
    seedTenantData(actorA, shopA, "甲租户达人");
    seedTenantData(actorB, shopB, "乙租户达人");

    for (int i = 0; i < 3; i++) {
      assertThat(
              as(
                      actorA,
                      "channel.operation.read",
                      () ->
                          service.attributions(
                              null, null, null, null, null, null, null, null, null,
                              "ATTRIBUTED_GMV_DESC", 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的归因记录")
          .extracting(InfluencerAttributionRow::influencerName)
          .containsExactly("甲租户达人");
      assertThat(
              as(
                      actorB,
                      "channel.operation.read",
                      () ->
                          service.attributions(
                              null, null, null, null, null, null, null, null, null,
                              "ATTRIBUTED_GMV_DESC", 0, 50))
                  .items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的归因记录")
          .extracting(InfluencerAttributionRow::influencerName)
          .containsExactly("乙租户达人");
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
    pg.stop();
  }
}
