package com.starcon.channel;

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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.4.1.2 优惠券与秒杀 的**多租户三断言**（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 的券 / 券码 / 核销记录在 B 的租户上下文里一律读不到。
 *   <li><b>伪造 tenant header 无效</b>：租户只来自已认证成员关系，
 *       {@code X-Tenant-Id} 头进不来也改不动它。
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1，A→B→A 三轮读不到对方的行。
 * </ol>
 *
 * <p>🔴 断言要打在**表**上，不能只打服务层返回值 ——
 * 服务层返回 404 可能只是「忘了加筛选条件」，只有直查表才能证明隔离真的生效。
 * 直查用 {@code channel_app} 连接（超级用户绕过 RLS 会让这个断言永远「通过」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdCouponTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
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
    exec("insert into platform (id,code,name,integration,support_status,position)"
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

  @Autowired AdCouponService service;
  @Autowired AdFlashSaleService flashService;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

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

  /** 以租户上下文直查（用 **channel_app** 连接 ⇒ RLS 真的生效）。 */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void seedShop(UUID tenant, String code) {
    // 🔴 店铺有 stamp_shop_owner 触发器：必须带上 app.actor，否则插入直接被拒。
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(
          "insert into shop (tenant_id,id,platform_id,code,name)"
              + " values ('"
              + tenant
              + "','"
              + UUID.randomUUID()
              + "','"
              + AMAZON
              + "','"
              + code
              + "','测试店铺 "
              + code
              + "') on conflict (tenant_id,code) do nothing");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 建一张生效中的券并生成 2 张码，返回券 id。 */
  UUID seedCoupon(AccessService.Access who, UUID tenant, String code, String shopCode) {
    var created =
        as(
            who,
            "channel.operation.create",
            () ->
                service.saveCoupon(
                    new AdCouponModels.SaveCouponRequest(
                        code,
                        "券 " + code,
                        "DISCOUNT_CODE",
                        AMAZON,
                        shopCode,
                        null,
                        new java.math.BigDecimal("0.100000"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "USD",
                        null,
                        null,
                        new java.math.BigDecimal("5"),
                        java.math.BigDecimal.ONE,
                        "TEN",
                        6,
                        null,
                        "租户隔离测试")));
    as(
        who,
        "channel.operation.update",
        () ->
            service.changeStatus(
                created.coupon().id().toString(),
                new AdCouponModels.ChangeCouponStatusRequest("ACTIVE", "测试上线")));
    as(
        who,
        "channel.operation.update",
        () ->
            service.generateCodes(
                created.coupon().id().toString(),
                new AdCouponModels.GenerateCouponCodesRequest(2, null, null, null)));
    return created.coupon().id();
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 断言一：两租户互不可见（券 / 券码 / 核销记录都读不到）")
  void tenantsAreIsolated() {
    seedShop(a, "TEN-A-1");
    seedShop(b, "TEN-B-1");
    UUID couponA = seedCoupon(actorA, a, "C-TEN-A", "TEN-A-1");

    // 领一张并核销，让 A 侧有券码 + 核销记录
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.claim(
                couponA.toString(),
                new AdCouponModels.ClaimCouponRequest("PULL", "user-a", "ON_SITE", null, null)));
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.redeem(
                couponA.toString(),
                new AdCouponModels.RedeemCouponRequest(
                    "SO-TEN-A", null, new java.math.BigDecimal("100"))));

    // 🔴 B 读不到 A 的券
    assertThat(as(actorB, "channel.operation.read", () -> service.coupons(null, null, null, null, null, null, null, 0, 50)).items())
        .as("B 的租户上下文里读不到 A 的券").isEmpty();

    var thrown =
        catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.coupon(couponA.toString())));
    assertThat(thrown).as("B 按 id 直读 A 的券必须失败").isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from ad_coupon where id='" + couponA + "'"))
        .as("B 的上下文里直查 A 的券必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from ad_coupon_code where coupon_id='" + couponA + "'"))
        .as("B 的上下文里直查 A 的券码必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from ad_coupon_claim where coupon_id='" + couponA + "'"))
        .as("B 的上下文里直查 A 的领取记录必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from ad_coupon_redemption where coupon_id='" + couponA + "'"))
        .as("B 的上下文里直查 A 的核销记录必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from ad_coupon_channel where coupon_id='" + couponA + "'"))
        .as("B 的上下文里直查 A 的投放渠道必须读到零行").isZero();

    // 反向同理
    UUID couponB = seedCoupon(actorB, b, "C-TEN-B", "TEN-B-1");
    assertThat(countAsTenant(a, "select count(*) from ad_coupon where id='" + couponB + "'"))
        .as("A 的上下文里直查 B 的券必须读到零行").isZero();

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from ad_coupon where id='" + couponA + "'"))
        .as("A 的上下文必须能直查 A 自己的券（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from ad_coupon_code where coupon_id='" + couponA + "'"))
        .isEqualTo(2);
    assertThat(countAsTenant(a, "select count(*) from ad_coupon_redemption where coupon_id='" + couponA + "'"))
        .isEqualTo(1);

    // 七张表全部 FORCE RLS
    for (String table :
        List.of(
            "ad_coupon",
            "ad_coupon_code",
            "ad_coupon_channel",
            "ad_coupon_claim",
            "ad_coupon_redemption",
            "ad_flash_sale",
            "ad_flash_sale_claim")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedShop(a, "FORGE-A");
    seedShop(b, "FORGE-B");
    // 两个租户各有一张券 ⇒ 「看得见对方的」会表现为 total=2
    UUID couponA = seedCoupon(actorA, a, "C-FORGE-A", "FORGE-A");
    UUID couponB = seedCoupon(actorB, b, "C-FORGE-B", "FORGE-B");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的券
    http.perform(get("/channel/ad-coupons/coupons/" + couponB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    // 🔴 A 的会话 + B 的头 ⇒ 只能看到 A 自己那 1 张（头生效的话会是 2）
    http.perform(get("/channel/ad-coupons/coupons")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // A 的会话 + A 的头 ⇒ 同样只有 A 自己那 1 张
    http.perform(get("/channel/ad-coupons/coupons")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 🔴 B 的会话 + A 的头 ⇒ 也只能看到 B 自己那 1 张
    http.perform(get("/channel/ad-coupons/coupons/" + couponA)
            .header("Authorization", bearerB)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/ad-coupons/coupons")
            .header("Authorization", bearerB)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/ad-coupons/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.4.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedShop(a, "POOL-A");
    seedShop(b, "POOL-B");
    UUID couponA = seedCoupon(actorA, a, "C-POOL-A", "POOL-A");
    UUID couponB = seedCoupon(actorB, b, "C-POOL-B", "POOL-B");

    for (int i = 0; i < 3; i++) {
      // A 读 A 的、B 读 B 的，各三轮
      var listA =
          as(actorA, "channel.operation.read", () -> service.coupons(null, null, null, null, null, null, null, 0, 50));
      assertThat(listA.items()).hasSize(1);
      assertThat(listA.items().get(0).id()).isEqualTo(couponA);

      var listB =
          as(actorB, "channel.operation.read", () -> service.coupons(null, null, null, null, null, null, null, 0, 50));
      assertThat(listB.items()).hasSize(1);
      assertThat(listB.items().get(0).id()).isEqualTo(couponB);

      // 🔴 直查也必须各归各
      assertThat(countAsTenant(a, "select count(*) from ad_coupon where id='" + couponA + "'")).isEqualTo(1);
      assertThat(countAsTenant(a, "select count(*) from ad_coupon where id='" + couponB + "'")).isZero();
      assertThat(countAsTenant(b, "select count(*) from ad_coupon where id='" + couponB + "'")).isEqualTo(1);
      assertThat(countAsTenant(b, "select count(*) from ad_coupon where id='" + couponA + "'")).isZero();
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
