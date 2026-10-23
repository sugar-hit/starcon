package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 1.1.2.3 履约健康的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在真实业务表</b>上
 * （{@code shipment_pending_unit} / {@code shipment_record} /
 * {@code fulfillment_dropship_tracking_exception}），不是占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>健康度聚合查询本身</b>没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：健康度卡片是**聚合**读（跨四张表的 UNION ALL 与多组 COUNT），
 * 聚合查询最容易在「某一支忘了写租户谓词」时把别家租户的件数混进卡片的分子/分母 ——
 * 而卡片上的数字是要给运营做发货决策的，混进别家的量比查不到还危险。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentHealthTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  /** 传给服务层的租户 = **已认证成员关系解析出的**租户，绝不是客户端头。 */
  static final UUID TENANT_A = a, TENANT_B = b;
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final OffsetDateTime SHIP_AT = OffsetDateTime.parse("2026-03-01T10:15:30+08:00");
  static final OffsetDateTime SLA_LATE = OffsetDateTime.parse("2026-02-27T00:00:00+08:00");

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      actors.put("Bearer tenant-b", actor(user, b, allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired FulfillmentHealthService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.read", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_tracking_exception, fulfillment_dropship_tracking,"
              + " fulfillment_dropship_order, fulfillment_dropship_config,"
              + " shipment_record_tracking_observation, shipment_record_parcel, shipment_record,"
              + " shipment_pending_parcel, shipment_pending_unit, sales_order cascade");
    }
    // A 租户：一件超时未发出的待发货 + 一条迟发记录 + 一条轨迹异常记录。
    seedTenant(a, 3);
    // B 租户：同样形状但数字不同 —— 混租户的聚合会立刻表现为「数字对不上」。
    seedTenant(b, 7);
  }

  /** 给某租户铺一套夹具。{@code count} 是待发货单元数，用来区分两家的聚合结果。 */
  void seedTenant(UUID tenant, int count) {
    as(
        tenant,
        () -> {
          for (int i = 0; i < count; i++) {
            sql.execute(
                """
                insert into shipment_pending_unit(
                  id, tenant_id, source_packing_order_id, source_outbound_order_id, packed_at,
                  parcel_count, total_weight_kg, warehouse_id, warehouse_ref, outbound_order_no,
                  sales_order_id, sales_order_no, shop_ref, carrier_channel, carrier_code,
                  sla_deadline_at, sla_source, sla_registered_by_membership_id, sla_registered_at,
                  status, synced_at, version, created_at, updated_at)
                values(
                  ?, ?, ?, ?, ?::timestamptz, 1, 1.000000, ?, ?, ?, ?, ?, ?,
                  'THIRD_PARTY', 'C1',
                  ?::timestamptz, 'MANUAL_ATTESTATION', ?, ?::timestamptz,
                  'PENDING', ?::timestamptz, 1, ?::timestamptz, ?::timestamptz)
                """,
                UUID.randomUUID(),
                tenant,
                UUID.randomUUID(),
                UUID.randomUUID(),
                SHIP_AT.minusDays(2),
                UUID.randomUUID(),
                "WH-" + tenant.toString().substring(0, 4),
                "OB-" + tenant.toString().substring(0, 4),
                UUID.randomUUID(),
                "SO-" + tenant.toString().substring(0, 4),
                "SHOP-1",
                OffsetDateTime.now().minusHours(2),
                user,
                SHIP_AT,
                SHIP_AT,
                SHIP_AT,
                SHIP_AT);
          }
          // 一条迟发记录（分母 1、分子 1 ⇒ 迟发率 100%）
          sql.execute(
              """
              insert into shipment_record(
                id, tenant_id, record_no, source_handover_id, source_handover_code,
                source_packing_order_id, source_outbound_order_id, outbound_order_no,
                warehouse_id, warehouse_ref, carrier_code, carrier_name, master_waybill_no,
                shipped_at, parcel_count, parcel_item_count, total_quantity, total_gross_weight_g,
                total_volume_cbm, sales_order_id, sales_order_no, shop_ref, buyer_name,
                destination_country, sla_deadline_at, sla_source, status, tracking_status,
                tracking_updated_at, buyer_visible_status, buyer_visible_source,
                platform_callback_status, platform_callback_source, synced_at, version)
              values(
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?::timestamptz, 1, 1, 1.000000, 100.000000, 0.100000,
                ?, ?, ?, 'buyer', 'US',
                ?::timestamptz, 'MANUAL_ATTESTATION', 'SHIPPED',
                'EXCEPTION', ?::timestamptz,
                'UNSET', '',
                'UNSET', '',
                ?::timestamptz, 1)
              """,
              UUID.randomUUID(),
              tenant,
              "SR-" + tenant.toString().substring(0, 8),
              UUID.randomUUID(),
              "HO-1",
              UUID.randomUUID(),
              UUID.randomUUID(),
              "OB-1",
              UUID.randomUUID(),
              "WH-1",
              "C1",
              "承运商一",
              "WB-" + tenant.toString().substring(0, 8),
              SHIP_AT,
              UUID.randomUUID(),
              "SO-1",
              "SHOP-1",
              SLA_LATE,
              SHIP_AT,
              SHIP_AT);
          return null;
        });
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() {
    var summaryA = asA(() -> service.summary(TENANT_A));
    var summaryB = asB(() -> service.summary(TENANT_B));

    var backlogA = card(summaryA, "PENDING_SHIPMENT_BACKLOG");
    var backlogB = card(summaryB, "PENDING_SHIPMENT_BACKLOG");
    // 这是计数口径，看 value（不是 numerator）。
    assertThat(backlogA.value()).isEqualByComparingTo("3");
    assertThat(backlogB.value()).isEqualByComparingTo("7");
    // 关键：A 的卡片里**不能**出现 B 的 7 件。
    assertThat(backlogA.value()).isNotEqualByComparingTo("10");
    assertThat(summaryA.coverage().pendingProjectionTotal()).isEqualTo(3L);
    assertThat(summaryB.coverage().pendingProjectionTotal()).isEqualTo(7L);
  }

  @Test
  void 红灯项也分租户() {
    var pageA = asA(() -> service.redFlags(TENANT_A, null, 0, 50));
    var pageB = asB(() -> service.redFlags(TENANT_B, null, 0, 50));
    // A 有 3 件超时未发出 + 1 条迟发 + 1 条轨迹异常 + 1 条无轨迹观测 = 6；
    // B 有 7 + 1 + 1 + 1 = 10。
    assertThat(pageA.total()).isEqualTo(6L);
    assertThat(pageB.total()).isEqualTo(10L);
  }

  @Test
  void 按主键直查别家租户的待发货单元查不到() {
    UUID unitA =
        asA(
            () ->
                sql.fetchOne(
                        "select id from shipment_pending_unit where tenant_id = ? limit 1", a)
                    .get("id", UUID.class));
    // B 的会话下按主键查 A 的单元 ⇒ 一行都查不到。
    assertThat(
            asB(
                () ->
                    sql.fetchOne(
                            "select count(*)::bigint from shipment_pending_unit where id = ?", unitA)
                        .get(0, Long.class)))
        .isZero();
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户上下文读不到别家的行() {
    // A 的会话里伪造 app.current_tenant=B ⇒ 聚合读到的必须是 A 的量，不是 B 的。
    var underForged =
        access.run(
            actorA,
            "trade.fulfillment.read",
            () -> {
              sql.execute("select set_config('app.current_tenant','" + b + "',true)");
              return service.summary(TENANT_A);
            });
    var backlog = card(underForged, "PENDING_SHIPMENT_BACKLOG");
    // 会话租户被换成 B、而服务层的显式谓词仍钉住 A ⇒ RLS 与谓词相交为空 ⇒ **零行**。
    // 关键不是「读到 0」这个数字，而是「**读不到别家的数据**」：
    // 若只靠 RLS 不写显式谓词，伪造上下文就会把 B 的 7 件读出来。
    assertThat(backlog.value()).isEqualByComparingTo("0");
    assertThat(backlog.value()).isNotEqualByComparingTo("7");
    assertThat(backlog.value()).isNotEqualByComparingTo("3");
  }

  @Test
  void 伪造租户上下文头不改变会话租户() throws Exception {
    // A 的会话 + 伪造指向 B 的 X-Tenant-Id 头 ⇒ 仍按 A 解析。
    http.perform(
            get("/trade/fulfillment/health/summary")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards[0].value").value(3));

    // B 的会话 + 伪造指向 A 的头 ⇒ 仍按 B 解析。
    http.perform(
            get("/trade/fulfillment/health/summary")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards[0].value").value(7));
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池固定一条时交替使用不串租户() {
    for (int round = 0; round < 5; round++) {
      assertThat(card(asA(() -> service.summary(TENANT_A)), "PENDING_SHIPMENT_BACKLOG").value())
          .isEqualByComparingTo("3");
      assertThat(card(asB(() -> service.summary(TENANT_B)), "PENDING_SHIPMENT_BACKLOG").value())
          .isEqualByComparingTo("7");
      assertThat(card(asA(() -> service.summary(TENANT_A)), "PENDING_SHIPMENT_BACKLOG").value())
          .isEqualByComparingTo("3");
      assertThat(asA(() -> service.redFlags(TENANT_A, null, 0, 50)).total()).isEqualTo(6L);
      assertThat(asB(() -> service.redFlags(TENANT_B, null, 0, 50)).total()).isEqualTo(10L);
    }
  }

  @Test
  void 事务外读零行() {
    // 不带租户上下文读 ⇒ 一行都读不到（RLS 的 USING 谓词不成立）。
    assertThat(
            sql.fetchOne("select count(*)::bigint from shipment_pending_unit").get(0, Long.class))
        .isZero();
    assertThat(
            sql.fetchOne("select count(*)::bigint from shipment_record").get(0, Long.class))
        .isZero();
  }

  static FulfillmentHealthModels.FulfillmentHealthCard card(
      FulfillmentHealthModels.FulfillmentHealthSummary s, String key) {
    return s.cards().stream()
        .filter(c -> c.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("健康度卡片缺 " + key));
  }
}
