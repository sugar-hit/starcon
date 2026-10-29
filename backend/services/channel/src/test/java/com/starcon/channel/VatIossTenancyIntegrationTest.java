package com.starcon.channel;

import static com.starcon.channel.VatIossModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 16.3.2.1 VAT/IOSS 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（IOSS 登记、申报周期、申报期、订单判定都不可见）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系），
 *       <b>并断言行确实落在会话租户名下</b>（不是只断言 A 看不见）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VatIossTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired VatIossService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access actor, String permission, Supplier<T> work) {
    return access.run(actor, permission, work);
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

  VatIossRegistrationView register(AccessService.Access actor, String number) {
    return as(
        actor,
        "channel.operation.create",
        () ->
            service.create(
                new VatIossRegistrationInput(
                    number,
                    "ACTIVE",
                    OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    null,
                    "",
                    "150.000000",
                    "EUR",
                    List.of(new VatIossShopScope("AMAZON", "SHOP-" + number)),
                    List.of(),
                    "多租户测试")));
  }

  /** 造一行订单判定结果（直插，省掉上游依赖）。 */
  void seedOrder(UUID tenantId, String orderNo, String scope) {
    execAsTenant(
        tenantId,
        "insert into vat_ioss_order(id,tenant_id,source_order_id,source_sales_order_id,"
            + " source_order_no,platform_code,shop_code,order_source,order_status,customer_type,"
            + " destination_country,jurisdiction_code,order_date,currency,goods_amount,"
            + " shipping_amount,tax_base,tax_amount,tax_rate,tax_rate_source,filing_scope,"
            + " reason_code,reason_detail,synced_at) values('"
            + UUID.randomUUID()
            + "','"
            + tenantId
            + "','"
            + UUID.randomUUID()
            + "','"
            + UUID.randomUUID()
            + "','"
            + orderNo
            + "','AMAZON','SHOP-1','AMAZON','COMPLETED','INDIVIDUAL','DE','EU-DE','2026-10-05',"
            + "'EUR',100.000000,0.000000,100.000000,19.000000,0.190000,'FINANCE','"
            + scope
            + "','IOSS_ELIGIBLE','测试',now())");
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 断言一：两租户互不可见（登记 / 申报期 / 订单判定都不可见）")
  void tenantsAreIsolated() {
    VatIossRegistrationView regA = register(actorA, "IM-A-1");
    VatIossRegistrationView regB = register(actorB, "IM-B-1");
    seedOrder(a, "SO-A-1", "IOSS");
    seedOrder(b, "SO-B-1", "GENERAL_VAT");

    // B 读不到 A 的登记
    assertThat(
            as(actorB, "channel.operation.read", () -> service.registrations(0, 50)).items())
        .extracting(VatIossRegistrationView::registrationNo)
        .containsExactly("IM-B-1");

    // 按 id 直取 A 的登记 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.registration(regA.id()))))
        .as("B 按 id 直取 A 的登记必须失败")
        .isNotNull();

    // B 读不到 A 的订单判定
    assertThat(
            as(actorB, "channel.operation.read", () -> service.orders(null, null, null, null, null, null, null, null, 0, 50))
                .items())
        .extracting(VatIossOrderView::sourceOrderNo)
        .containsExactly("SO-B-1");

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from vat_ioss_registration"))
        .as("B 的上下文里只能看到 B 自己的登记")
        .isEqualTo(1);
    assertThat(
            countAsTenant(
                b, "select count(*) from vat_ioss_registration where id = '" + regA.id() + "'"))
        .as("B 的上下文里直查 A 的登记必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from vat_ioss_order"))
        .as("B 的上下文里读不到 A 的订单判定")
        .isEqualTo(1);

    // 表是 FORCE RLS
    for (String table :
        new String[] {
          "vat_ioss_registration",
          "vat_ioss_registration_shop",
          "vat_ioss_registration_goods",
          "vat_ioss_filing_cycle",
          "vat_filing_period",
          "vat_ioss_order",
          "vat_ioss_order_line"
        }) {
      assertThat(
              scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from vat_ioss_registration"))
        .as("A 的上下文必须能直查 A 自己的登记（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(regB.id()).isNotEqualTo(regA.id());
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    // 只给 B 登记一条；A 这边空着，好观察「伪造头下的写入落到谁名下」
    VatIossRegistrationView regB = register(actorB, "IM-B-1");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的登记
    http.perform(get("/channel/vat-ioss/registrations").header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.items[?(@.registrationNo == 'IM-B-1')]").isEmpty());

    // 🔴 伪造头下的写入**仍然落在会话租户名下**（不是只断言 A 看不见）
    http.perform(post("/channel/vat-ioss/registrations")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                "{\"registrationNo\":\"IM-FORGED\",\"status\":\"ACTIVE\","
                    + "\"thresholdAmount\":\"150.000000\",\"thresholdCurrency\":\"EUR\","
                    + "\"shopScopes\":[],\"goodsScopes\":[],\"note\":\"伪造头\"}"))
        .andExpect(status().isOk());

    assertThat(countAsTenant(a, "select count(*) from vat_ioss_registration where registration_no = 'IM-FORGED'"))
        .as("伪造头下的写入必须落在会话租户 A 名下")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from vat_ioss_registration where registration_no = 'IM-FORGED'"))
        .as("伪造头不许写到 B 名下")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from vat_ioss_registration"))
        .as("B 名下仍然只有它自己那一条")
        .isEqualTo(1);
    assertThat(regB.id()).isNotNull();
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.3.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void poolDoesNotLeakTenants() {
    VatIossRegistrationView regA = register(actorA, "IM-A-1");
    VatIossRegistrationView regB = register(actorB, "IM-B-1");

    for (int round = 0; round < 3; round++) {
      assertThat(
              as(actorA, "channel.operation.read", () -> service.registrations(0, 50)).items())
          .extracting(VatIossRegistrationView::registrationNo)
          .as("第 " + round + " 轮：A 只能看到 A 自己的登记")
          .containsExactly("IM-A-1");

      assertThat(
              as(actorB, "channel.operation.read", () -> service.registrations(0, 50)).items())
          .extracting(VatIossRegistrationView::registrationNo)
          .as("第 " + round + " 轮：B 只能看到 B 自己的登记")
          .containsExactly("IM-B-1");
    }

    // 池压到 1 时也不能串：A 的上下文里直查 B 的登记必须零行
    assertThat(
            countAsTenant(
                a, "select count(*) from vat_ioss_registration where id = '" + regB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from vat_ioss_registration")).isEqualTo(1);
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
