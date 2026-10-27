package com.starcon.channel;

import static com.starcon.channel.TrackingQueryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.carrier.CarrierTrackingAdapter;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.3.1.1 轨迹查询。
 *
 * <p>本测试类钉六组事：
 *
 * <ol>
 *   <li><b>🔴 承运商事件 ID 去重</b>（蓝图加粗句「同一节点重复推送不产生重复记录」）：
 *       服务层重复推送不产生第二条；<b>绕过服务层直接写 SQL 插同一条事件同样被 DB 拒</b>
 *       （应用账号与迁移超级账号<b>各试一次</b>）。</li>
 *   <li><b>轨迹事件 append-only</b>：UPDATE / DELETE 被拒（应用账号与超级账号各试一次）——
 *       轨迹是「当时它在哪」的举证，事后改写等于伪造物流史。</li>
 *   <li><b>跨服务只读</b>：运单真值在 trade，本服务只经授权 HTTP 读并<b>转发调用方 bearer</b>；
 *       上游不可达时 503（写「读不到」），<b>不是</b>空清单。</li>
 *   <li><b>预计时效对比</b>：命中时效规则 ⇒ 承诺天数与承诺到达时刻落库；
 *       ON_TIME / AT_RISK / DELAYED / NO_PROMISE 四档各有用例。</li>
 *   <li><b>异常节点只标记不处置</b>：EXCEPTION 节点缺异常码被拒；带码则主体异常聚合更新。</li>
 *   <li><b>按承运商批量拉取</b>：有 adapter ⇒ 真落库 + 任务账本 + 逐票结果；
 *       无 adapter ⇒ 如实落 NO_CHANNEL 并写明理由，<b>不返回空轨迹冒充「没轨迹」</b>。</li>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户（连接池压到 1）。</li>
 * </ol>
 *
 * <p><b>时间一律 pin 住</b>：发货时刻、事件发生时刻、承诺时效窗口都是夹具里的固定值，
 * 不取墙上时钟。写回的时刻<b>断言实际读回的值</b>，不只断言没抛异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TrackingQueryIntegrationTest.TestAdapters.class)
class TrackingQueryIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID();
  static final UUID b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 承运商：SF 有渠道与时效承诺但**没有**轨迹通道；TST 有轨迹通道（测试替身）。 */
  static final String CARRIER = "SF";
  static final String CARRIER_WITH_CHANNEL = "TST";

  /** 夹具 pin 住的时间点。 */
  static final OffsetDateTime SHIPPED_RECENT = OffsetDateTime.parse("2026-10-01T08:00:00Z");
  static final OffsetDateTime SHIPPED_OLD = OffsetDateTime.parse("2020-01-01T08:00:00Z");
  static final OffsetDateTime PICKED_UP_AT = OffsetDateTime.parse("2026-10-02T09:15:00Z");
  static final OffsetDateTime IN_TRANSIT_AT = OffsetDateTime.parse("2026-10-03T11:00:00Z");
  static final OffsetDateTime CUSTOMS_AT = OffsetDateTime.parse("2026-10-05T22:40:00Z");
  static final OffsetDateTime DELIVERED_AT = OffsetDateTime.parse("2026-10-12T16:30:00Z");
  static final OffsetDateTime CARRIER_ETA = OffsetDateTime.parse("2026-10-25T12:00:00Z");
  static final OffsetDateTime LATE_ETA = OffsetDateTime.parse("2028-01-01T00:00:00Z");

  /** 上游运单集合：**按 bearer 分租户**（真实 trade 侧也有自己的 RLS）。 */
  static final Map<String, List<Map<String, Object>>> upstreamByBearer = new ConcurrentHashMap<>();
  static final List<String> upstreamBears = new ArrayList<>();

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
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();

      // ---- 上游 9.4.1.2 的**真实 API 形状** ----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/fulfillment/shipment-records/records",
          e -> {
            String bear = e.getRequestHeaders().getFirst("Authorization");
            if (bear != null) {
              upstreamBears.add(bear);
            }
            // 上游异常的两条路径：503（上游自己挂了）与 401（调用方无权读取）。
            if ("Bearer tenant-down".equals(bear)) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            if ("Bearer tenant-forbidden".equals(bear)) {
              e.sendResponseHeaders(403, -1);
              e.close();
              return;
            }
            List<Map<String, Object>> mine =
                upstreamByBearer.getOrDefault(String.valueOf(bear), List.of());
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", mine);
            page.put("page", 0);
            page.put("size", 200);
            page.put("totalElements", mine.size());
            byte[] body = new ObjectMapper().writeValueAsBytes(page);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 承运商轨迹通道的**测试替身**。
   *
   * <p>⚠️ 替的是<b>外部系统</b>（承运商），不是本模块的数据：
   * 承运商那侧的运单集合、轨迹事件、预计到达时刻都是它给的，
   * 本模块的落库与去重全部真跑 PostgreSQL 16。
   */
  static class TestTrackingAdapter implements CarrierTrackingAdapter {
    @Override
    public String carrierCode() {
      return CARRIER_WITH_CHANNEL;
    }

    @Override
    public PullOutcome pull(TrackingQuery query) {
      List<CarrierEvent> events = new ArrayList<>();
      List<String> noEvent = new ArrayList<>();
      for (String waybill : query.waybillNos()) {
        if (waybill.endsWith("-NOEVENT")) {
          noEvent.add(waybill);
          continue;
        }
        events.add(
            new CarrierEvent(
                "PULL-" + waybill + "-1",
                waybill,
                "IN_TRANSIT",
                "IT",
                "运输中",
                "上海转运中心",
                IN_TRANSIT_AT,
                "",
                "",
                CARRIER_ETA));
      }
      return new PullOutcome(events, noEvent);
    }
  }

  @TestConfiguration
  static class TestAdapters {
    @Bean
    CarrierTrackingAdapter testTrackingAdapter() {
      return new TestTrackingAdapter();
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
    r.add("TRADE_URL", () -> "http://localhost:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired TrackingQueryService tracking;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    upstreamByBearer.clear();
    upstreamBears.clear();
    truncateAll();
    seedCarrier(a, CARRIER);
    seedChannelAndRules(a, CARRIER);
    seedCarrier(a, CARRIER_WITH_CHANNEL);
    // 租户 B 也要有自己的承运商档案与渠道：否则 B 的轨迹主体没有渠道口径，
    // 「两租户互不可见」就退化成「B 什么都没写」—— 那证明不了隔离。
    seedCarrier(b, CARRIER);
    seedChannelAndRules(b, CARRIER);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    trade.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate logistics_tracking_pull_task_item, logistics_tracking_pull_task,"
              + " logistics_tracking_event, logistics_tracking_subject,"
              + " logistics_transit_rule, logistics_channel_scope, logistics_channel_config,"
              + " carrier_business_scope, carrier_country_coverage, carrier_logistics_profile"
              // CASCADE：渠道配置被 10.1.1.3 / 10.2.2.2 的表引用，本类独占一个容器，
              // 一并清干净才能让「命中几条时效承诺」这类断言是确定的。
              + " cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 一条承运商物流档案（照抄 10.1.1.3 集成测试的登记口径：先 NO_COVERAGE 再补齐）。 */
  void seedCarrier(UUID tenant, String code) {
    exec(
        "insert into carrier_logistics_profile (id,tenant_id,role_id,party_id,party_code,"
            + "party_name,party_usable,interface_type,carrier_category,service_tier,"
            + "secret_ref,secret_masked,readiness)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + UUID.randomUUID() + "','"
            + UUID.randomUUID() + "','" + code + "','" + code + " 承运商',true,"
            + "'API','EXPRESS_COURIER','STANDARD',"
            + "'kv://starcon/test/carriers/" + code.toLowerCase() + "','sv_live_****3f2a',"
            + "'NO_COVERAGE')");
    exec(
        "insert into carrier_country_coverage (tenant_id,profile_id,country_code)"
            + " select '" + tenant + "',id,'US' from carrier_logistics_profile"
            + " where tenant_id='" + tenant + "' and party_code='" + code + "'");
    exec(
        "insert into carrier_business_scope (tenant_id,profile_id,business_type)"
            + " select '" + tenant + "',id,'OWN_FULFILLMENT' from carrier_logistics_profile"
            + " where tenant_id='" + tenant + "' and party_code='" + code + "'");
    exec(
        "update carrier_logistics_profile set readiness='READY' where tenant_id='" + tenant
            + "' and party_code='" + code + "'");
  }

  /**
   * 一条渠道配置 + 三条时效承诺。
   *
   * <p>三条承诺按国家分开（重量段触发器禁止同一渠道同一国家同一服务等级的段重叠）：
   * <ul>
   *   <li>US：300~320 天 ⇒ 承诺落在很远的将来，测 ON_TIME / AT_RISK；</li>
   *   <li>DE：5~8 天 ⇒ 配合 2020 年的发货时刻，承诺早已过期 ⇒ DELAYED；</li>
   *   <li>GB：<b>不建规则</b> ⇒ NO_PROMISE（没有承诺就判不了延误）。</li>
   * </ul>
   */
  void seedChannelAndRules(UUID tenant, String code) {
    String channelId = UUID.randomUUID().toString();
    exec(
        "insert into logistics_channel_config (id,tenant_id,carrier_profile_id,channel_code,"
            + "channel_name,status,billing_mode,billing_currency,base_rate,min_charge,readiness)"
            + " select '" + channelId + "','" + tenant + "',id,'" + code + "-STANDARD','" + code
            + " 标准线','ACTIVE','BY_WEIGHT','USD',28.50,0,'NO_SCOPE'"
            + " from carrier_logistics_profile where tenant_id='" + tenant + "' and party_code='"
            + code + "'");
    exec(
        "insert into logistics_channel_scope (tenant_id,channel_id,scope_kind,scope_value)"
            + " values ('" + tenant + "','" + channelId + "','COUNTRY','US'),('" + tenant + "','"
            + channelId + "','COUNTRY','DE'),('" + tenant + "','" + channelId
            + "','COUNTRY','FR')");
    exec(
        "update logistics_channel_config set readiness='READY' where tenant_id='" + tenant
            + "' and id='" + channelId + "'");
    exec(
        "insert into logistics_transit_rule (id,tenant_id,channel_id,country_code,service_level,"
            + "weight_min_kg,weight_max_kg,transit_days_min,transit_days_max,cutoff_time,readiness)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + channelId
            + "','US','STANDARD',0.5,30,300,320,'18:00','READY'),('" + UUID.randomUUID() + "','"
            + tenant + "','" + channelId + "','DE','STANDARD',0.5,30,5,8,'18:00','READY'),('"
            + UUID.randomUUID() + "','" + tenant + "','" + channelId
            + "','FR','STANDARD',0.5,30,300,320,'18:00','READY')");
  }

  /** 上游运单集合的一行（字段与 trade 的 ShipmentRecordRow 同名）。 */
  Map<String, Object> shipment(
      UUID recordId,
      String waybill,
      String carrier,
      String country,
      OffsetDateTime shippedAt,
      String weightGrams) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", recordId.toString());
    row.put("recordNo", "SR-2026-000001");
    row.put("outboundOrderNo", "OB-2026-0001");
    row.put("salesOrderNo", "SO-2026-0001");
    row.put("warehouseRef", "WH-DE-1");
    row.put("carrierCode", carrier);
    row.put("carrierName", carrier + " 承运商");
    row.put("masterWaybillNo", waybill);
    row.put("destinationCountry", country);
    row.put("shippedAt", shippedAt.toString());
    row.put("parcelCount", 1);
    row.put("totalQuantity", new BigDecimal("3.000000"));
    row.put("totalGrossWeightG", new BigDecimal(weightGrams));
    row.put("status", "SHIPPED");
    row.put("trackingStatus", "UNSET");
    return row;
  }

  void upstreamA(String carrier, List<Map<String, Object>> rows) {
    upstreamByBearer.put("Bearer tenant-a", rows);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 能力声明：未交付段 available=false + ownerModule + impact 三者齐备")
  void capabilitiesDeclareGaps() {
    var caps = as(actorA, "channel.operation.read", () -> tracking.capabilities());
    assertThat(caps.dimensions()).isNotEmpty();
    assertThat(caps.nodeTypes()).contains("PICKED_UP", "IN_TRANSIT", "CUSTOMS", "TRANSFER",
        "OUT_FOR_DELIVERY", "DELIVERED");
    assertThat(caps.etaStatuses()).contains("ON_TIME", "AT_RISK", "DELAYED", "NO_PROMISE");

    for (var dimension : caps.dimensions()) {
      if (!dimension.available()) {
        assertThat(dimension.ownerModule())
            .as("未交付段必须点名 ownerModule：" + dimension.dimension())
            .isNotBlank();
        assertThat(dimension.impact())
            .as("未交付段必须写明影响：" + dimension.dimension())
            .isNotBlank();
      }
    }
    // 10.3.1.2 异常件未交付 ⇒ 处置段必须 available=false 并点名它
    var disposition =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("exception_disposition"))
            .findFirst()
            .orElseThrow();
    assertThat(disposition.available()).isFalse();
    assertThat(disposition.ownerModule()).isEqualTo("10.3.1.2");
  }

  // ==================================================================
  // 跨服务只读
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 同步运单集合：真落库 + 转发调用方 bearer + 幂等")
  void syncImportsShipments() {
    UUID recordId = UUID.randomUUID();
    upstreamA(
        CARRIER,
        List.of(shipment(recordId, "WB-SYNC-1", CARRIER, "US", SHIPPED_RECENT, "2000")));

    var result =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.sync(
                    new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.created()).isEqualTo(1);
    assertThat(result.updated()).isZero();

    // 转发的是**调用方自己的** bearer（租户隔离的第一道：上游按成员关系解析租户）
    assertThat(upstreamBears).contains("Bearer tenant-a");

    // 克 → 公斤换算走 BigDecimal，2kg 就是 2.000000
    var page = as(actorA, "channel.operation.read", () -> tracking.subjects(null, null, null, null,
        null, null, false, "LATEST_DESC", 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).grossWeightKg()).isEqualByComparingTo("2.000000");
    assertThat(page.items().get(0).masterWaybillNo()).isEqualTo("WB-SYNC-1");
    assertThat(page.items().get(0).sourceShipmentRecordId()).isEqualTo(recordId);

    // 重复同步是幂等更新，不插出第二条
    var again =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.sync(
                    new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));
    assertThat(again.created()).isZero();
    assertThat(again.updated()).isEqualTo(1);
    assertThat(
            as(actorA, "channel.operation.read", () -> tracking.subjects(null, null, null, null, null,
                null, false, "LATEST_DESC", 0, 50))
                .items())
        .hasSize(1);
  }

  @Test
  @DisplayName("10.3.1.1 上游读不到时抛 503（写「读不到」），不是空清单")
  void upstreamUnavailableIs503() {
    var throwable =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        tracking.sync(
                            new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-down")));
    assertThat(throwable).isNotNull();
    // 理由写「读不到」而不是「没有数据」—— 空清单会让现场以为货全没发
    assertThat(throwable.getMessage()).contains("读不到");
    assertThat(throwable.getMessage()).doesNotContain("没有运单");
  }

  @Test
  @DisplayName("10.3.1.1 上游 401/403 映射成 401 并写明「调用方无权读取」")
  void upstreamForbiddenIs401() {
    var throwable =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        tracking.sync(
                            new TrackingSyncInput(null, "STANDARD", 0, 50),
                            "Bearer tenant-forbidden")));
    assertThat(throwable).isNotNull();
    assertThat(throwable.getMessage()).contains("无权读取");
  }

  // ==================================================================
  // 🔴 承运商事件 ID 去重
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 🔴 同一节点重复推送不产生重复记录（服务层 + DB 唯一约束双保险）")
  void duplicateCarrierEventIsRejected() {
    seedOneSubject("WB-DUP-1");

    var first = push(actorA, "EVT-1", "WB-DUP-1", "PICKED_UP", PICKED_UP_AT, "", "");
    assertThat(first.inserted()).isEqualTo(1);
    assertThat(first.duplicated()).isZero();

    // 同一承运商事件 ID 再次推送 ⇒ 不产生第二条
    var second = push(actorA, "EVT-1", "WB-DUP-1", "PICKED_UP", PICKED_UP_AT, "", "");
    assertThat(second.inserted()).isZero();
    assertThat(second.duplicated()).isEqualTo(1);

    assertThat(countEvents("WB-DUP-1")).isEqualTo(1L);

    // 🔴 绕过服务层直接写 SQL 插同一条事件 ⇒ DB 唯一约束挡住（应用账号）
    var asApp =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () -> {
                      rawInsertEvent("WB-DUP-1", "EVT-1");
                      return null;
                    }));
    assertThat(asApp).isNotNull();
    assertThat(sqlStateOf(asApp)).as("应用账号直插重复事件必须撞唯一约束").isEqualTo("23505");

    // 🔴 迁移超级账号（绕过 RLS）插同一条 ⇒ 约束本身也生效
    var asSuper =
        org.assertj.core.api.Assertions.catchThrowable(() -> rawInsertEventAsSuper("WB-DUP-1", "EVT-1"));
    assertThat(asSuper).isNotNull();
    assertThat(sqlStateOf(asSuper)).as("超级账号直插重复事件必须撞唯一约束").isEqualTo("23505");

    assertThat(countEvents("WB-DUP-1")).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.3.1.1 🔴 不同承运商的同一事件号不是重复（去重键带 carrier_code）")
  void dedupeKeyIncludesCarrier() {
    seedOneSubject("WB-DUP-1");
    seedOneSubject("WB-DUP-1", CARRIER_WITH_CHANNEL);
    var first = push(actorA, "EVT-SAME", "WB-DUP-1", "PICKED_UP", PICKED_UP_AT, "", "");
    var second =
        push(actorA, "EVT-SAME", "WB-DUP-1", "PICKED_UP", PICKED_UP_AT, "", "", CARRIER_WITH_CHANNEL);
    assertThat(first.inserted()).isEqualTo(1);
    assertThat(second.inserted()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.3.1.1 🔴 轨迹事件 append-only：应用账号与超级账号各试一次都被拒")
  void trackingEventIsAppendOnly() {
    seedOneSubject("WB-IMM-1");
    push(actorA, "EVT-IMM-1", "WB-IMM-1", "PICKED_UP", PICKED_UP_AT, "", "");

    // 应用账号：REVOKE UPDATE,DELETE ⇒ 权限层挡住
    var asApp =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () -> {
                      rawUpdateEvent("EVT-IMM-1");
                      return null;
                    }));
    assertThat(asApp).isNotNull();
    assertThat(sqlStateOf(asApp)).as("应用账号改写事件必须被权限层拒").isEqualTo("42501");

    // 超级账号：触发器挡住（权限层对超级用户无效）
    var asSuper =
        org.assertj.core.api.Assertions.catchThrowable(() -> rawUpdateEventAsSuper("EVT-IMM-1"));
    assertThat(asSuper).isNotNull();
    assertThat(asSuper.getMessage()).contains("append-only");
    assertThat(sqlStateOf(asSuper)).isEqualTo("23514");
  }

  // ==================================================================
  // 时间线 + 预计时效对比 + 异常标记
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 轨迹时间线按发生时刻倒序 + 节点标签")
  void timelineIsOrderedByOccurrence() {
    seedOneSubject("WB-TL-1");
    push(actorA, "EVT-A", "WB-TL-1", "PICKED_UP", PICKED_UP_AT, "", "");
    push(actorA, "EVT-B", "WB-TL-1", "IN_TRANSIT", IN_TRANSIT_AT, "", "");
    push(actorA, "EVT-C", "WB-TL-1", "CUSTOMS", CUSTOMS_AT, "", "");

    var detail = as(actorA, "channel.operation.read", () -> tracking.subject(subjectIdOf("WB-TL-1").toString()));
    assertThat(detail.timeline()).hasSize(3);
    assertThat(detail.timeline().get(0).nodeType()).isEqualTo("CUSTOMS");
    assertThat(detail.timeline().get(0).nodeLabel()).isEqualTo("清关中");
    assertThat(detail.timeline().get(2).nodeType()).isEqualTo("PICKED_UP");
    // 发生时刻是承运商给的，不是写入时刻
    assertThat(detail.timeline().get(0).occurredAt().toInstant()).isEqualTo(CUSTOMS_AT.toInstant());
    assertThat(detail.subject().latestNode()).isEqualTo("CUSTOMS");
    assertThat(detail.subject().eventCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("10.3.1.1 预计时效对比：命中承诺 ⇒ 承诺天数与承诺到达时刻落库（克→公斤，禁浮点）")
  void promiseIsSnapshot() {
    UUID recordId = UUID.randomUUID();
    upstreamA(
        CARRIER,
        List.of(shipment(recordId, "WB-PROMISE-1", CARRIER, "US", SHIPPED_RECENT, "2000")));
    as(
        actorA,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));

    var row = subjectRow("WB-PROMISE-1");
    assertThat(row.promisedTransitDaysMin()).isEqualTo(300);
    assertThat(row.promisedTransitDaysMax()).isEqualTo(320);
    assertThat(row.promisedEtaEarliestAt().toInstant())
        .isEqualTo(SHIPPED_RECENT.plusDays(300).toInstant());
    assertThat(row.promisedEtaAt().toInstant())
        .isEqualTo(SHIPPED_RECENT.plusDays(320).toInstant());
    assertThat(row.matchedPromiseCount()).isEqualTo(1);
    assertThat(row.channelId()).isNotNull();
  }

  @Test
  @DisplayName("10.3.1.1 时效状态四档：ON_TIME / AT_RISK / DELAYED / NO_PROMISE")
  void etaStatusesAreComputed() {
    // ON_TIME：承运商预计 ≤ 承诺最晚到达
    upstreamA(
        CARRIER,
        List.of(
            shipment(UUID.randomUUID(), "WB-ON-TIME", CARRIER, "US", SHIPPED_RECENT, "2000"),
            shipment(UUID.randomUUID(), "WB-AT-RISK", CARRIER, "FR", SHIPPED_RECENT, "2000"),
            shipment(UUID.randomUUID(), "WB-DELAYED", CARRIER, "DE", SHIPPED_OLD, "2000"),
            shipment(UUID.randomUUID(), "WB-NO-PROMISE", CARRIER, "GB", SHIPPED_RECENT, "2000")));
    as(
        actorA,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));

    push(actorA, "EVT-ON", "WB-ON-TIME", "IN_TRANSIT", IN_TRANSIT_AT, "", "", CARRIER, CARRIER_ETA);
    push(actorA, "EVT-RISK", "WB-AT-RISK", "IN_TRANSIT", IN_TRANSIT_AT, "", "", CARRIER, LATE_ETA);
    push(actorA, "EVT-LATE", "WB-DELAYED", "IN_TRANSIT", IN_TRANSIT_AT, "", "", CARRIER, null);

    assertThat(subjectRow("WB-ON-TIME").etaStatus()).isEqualTo("ON_TIME");
    assertThat(subjectRow("WB-AT-RISK").etaStatus()).isEqualTo("AT_RISK");
    assertThat(subjectRow("WB-DELAYED").etaStatus()).isEqualTo("DELAYED");
    assertThat(subjectRow("WB-NO-PROMISE").etaStatus()).isEqualTo("NO_PROMISE");
    assertThat(subjectRow("WB-NO-PROMISE").promisedEtaAt()).isNull();
  }

  @Test
  @DisplayName("10.3.1.1 异常节点只标记：缺异常码被拒，带码则主体异常聚合更新")
  void exceptionMarkingOnly() {
    seedOneSubject("WB-EXC-1");
    // 标了异常却说不清是什么异常 ⇒ 拒
    var noCode = push(actorA, "EVT-EXC-1", "WB-EXC-1", "EXCEPTION", IN_TRANSIT_AT, "", "");
    assertThat(noCode.rejected()).isEqualTo(1);
    assertThat(noCode.rejections().get(0).reason()).contains("异常码");
    assertThat(countEvents("WB-EXC-1")).isZero();

    var withCode =
        push(actorA, "EVT-EXC-1", "WB-EXC-1", "EXCEPTION", IN_TRANSIT_AT, "CUSTOMS_HOLD", "清关滞留");
    assertThat(withCode.inserted()).isEqualTo(1);

    var row = subjectRow("WB-EXC-1");
    assertThat(row.exceptionFlag()).isTrue();
    assertThat(row.exceptionCount()).isEqualTo(1);
    assertThat(row.lastExceptionCode()).isEqualTo("CUSTOMS_HOLD");
    assertThat(row.lastExceptionNote()).isEqualTo("清关滞留");
    assertThat(row.latestNode()).isEqualTo("EXCEPTION");
  }

  @Test
  @DisplayName("10.3.1.1 推送的未知运单与未知节点类型逐条如实列出，不静默丢弃")
  void pushRejectsBadItemsExplicitly() {
    seedOneSubject("WB-OK-1");
    var result =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.push(
                    new TrackingEventPushInput(
                        CARRIER,
                        List.of(
                            new TrackingEventPushItem(
                                "EVT-X1", "WB-UNKNOWN", "PICKED_UP", "", "", "", PICKED_UP_AT, "",
                                "", null),
                            new TrackingEventPushItem(
                                "EVT-X2", "WB-OK-1", "TELEPORTED", "", "", "", PICKED_UP_AT, "",
                                "", null))),
                    actorA.membershipId()));
    assertThat(result.received()).isEqualTo(2);
    assertThat(result.inserted()).isZero();
    assertThat(result.rejected()).isEqualTo(2);
    assertThat(result.rejections()).anyMatch(r -> r.reason().contains("轨迹主体"));
    assertThat(result.rejections()).anyMatch(r -> r.reason().contains("未知节点类型"));
  }

  // ==================================================================
  // 按承运商批量拉取
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 按承运商批量拉取：有通道 ⇒ 真落库 + 任务账本 + 逐票结果")
  void batchPullStoresEvents() {
    upstreamA(
        CARRIER_WITH_CHANNEL,
        List.of(
            shipment(UUID.randomUUID(), "WB-PULL-1", CARRIER_WITH_CHANNEL, "US", SHIPPED_RECENT,
                "2000"),
            shipment(UUID.randomUUID(), "WB-PULL-NOEVENT", CARRIER_WITH_CHANNEL, "US",
                SHIPPED_RECENT, "2000")));
    as(
        actorA,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));

    var detail =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.createPullTask(
                    new TrackingPullTaskInput(CARRIER_WITH_CHANNEL, null, "SCHEDULED"),
                    actorA.membershipId()));

    assertThat(detail.task().status()).isEqualTo("SUCCEEDED");
    assertThat(detail.task().triggerSource()).isEqualTo("SCHEDULED");
    assertThat(detail.task().taskNo()).startsWith("TRK-");
    assertThat(detail.task().insertedEventCount()).isEqualTo(1);
    assertThat(detail.task().noEventCount()).isEqualTo(1);

    assertThat(detail.items()).anyMatch(i -> i.waybillNo().equals("WB-PULL-1") && i.status().equals("PULLED"));
    assertThat(detail.items())
        .anyMatch(i -> i.waybillNo().equals("WB-PULL-NOEVENT") && i.status().equals("NO_EVENT"));

    // 事件真落库
    assertThat(countEvents("WB-PULL-1")).isEqualTo(1L);
    var row = subjectRow("WB-PULL-1");
    assertThat(row.latestNode()).isEqualTo("IN_TRANSIT");
    assertThat(row.carrierEtaAt().toInstant()).isEqualTo(CARRIER_ETA.toInstant());
  }

  @Test
  @DisplayName("10.3.1.1 再拉一次同一批：事件全部重复，不产生新记录（DUPLICATE 如实记账）")
  void repeatedPullCountsDuplicates() {
    upstreamA(
        CARRIER_WITH_CHANNEL,
        List.of(
            shipment(UUID.randomUUID(), "WB-PULL-2", CARRIER_WITH_CHANNEL, "US", SHIPPED_RECENT,
                "2000")));
    as(
        actorA,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));

    as(
        actorA,
        "channel.operation.create",
        () ->
            tracking.createPullTask(
                new TrackingPullTaskInput(CARRIER_WITH_CHANNEL, null, "MANUAL"),
                actorA.membershipId()));
    var second =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.createPullTask(
                    new TrackingPullTaskInput(CARRIER_WITH_CHANNEL, null, "MANUAL"),
                    actorA.membershipId()));

    assertThat(second.task().status()).isEqualTo("SUCCEEDED");
    assertThat(second.task().insertedEventCount()).isZero();
    assertThat(second.task().duplicateCount()).isEqualTo(1);
    assertThat(second.items()).anyMatch(i -> i.status().equals("DUPLICATE"));
    assertThat(countEvents("WB-PULL-2")).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.3.1.1 没有承运商通道时如实落 NO_CHANNEL 并写明理由（不返回空轨迹冒充没轨迹）")
  void pullWithoutChannelIsHonest() {
    upstreamA(
        CARRIER,
        List.of(shipment(UUID.randomUUID(), "WB-NOCHAN-1", CARRIER, "US", SHIPPED_RECENT, "2000")));
    as(
        actorA,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));

    var detail =
        as(
            actorA,
            "channel.operation.create",
            () ->
                tracking.createPullTask(
                    new TrackingPullTaskInput(CARRIER, null, "MANUAL"), actorA.membershipId()));

    assertThat(detail.task().status()).isEqualTo("NO_CHANNEL");
    assertThat(detail.task().failureReason()).contains("没有已接通的轨迹通道");
    assertThat(detail.task().insertedEventCount()).isZero();
    // 关键：没有轨迹 ≠ 「拉了但承运商说没轨迹」
    assertThat(detail.items()).noneMatch(i -> i.status().equals("NO_EVENT"));
    assertThat(countEvents("WB-NOCHAN-1")).isZero();
  }

  // ==================================================================
  // 读：分页与筛选
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 清单真分页 + 真筛选，汇总不受分页影响")
  void listPaginatesAndFilters() {
    for (int i = 1; i <= 5; i++) {
      upstreamA(
          CARRIER,
          List.of(
              shipment(
                  UUID.randomUUID(),
                  "WB-PAGE-" + i,
                  CARRIER,
                  i % 2 == 0 ? "US" : "DE",
                  SHIPPED_RECENT,
                  "2000")));
      as(
          actorA,
          "channel.operation.create",
          () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), "Bearer tenant-a"));
    }

    var page1 = as(actorA, "channel.operation.read", () -> tracking.subjects(null, null, null, null,
        null, null, false, "LATEST_DESC", 0, 2));
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.totalElements()).isEqualTo(5L);
    assertThat(page1.totalPages()).isEqualTo(3);
    // 汇总不受分页影响
    assertThat(page1.summary().totalCount()).isEqualTo(5L);
    assertThat(page1.summary().byCarrier()).anyMatch(b -> b.carrierCode().equals(CARRIER));

    // 按**目的国**筛（不是按节点类型）：跨境延误常常集中在某些国家。
    // 夹具 5 票按 i%2 交替：i=1/3/5 是 DE（3 票），i=2/4 是 US（2 票）。
    // 两侧都断言，是为了证明这一维**真的在筛**而不是把全量退回来。
    var filtered = as(actorA, "channel.operation.read", () -> tracking.subjects(null, "DE", null,
        null, null, null, false, "LATEST_DESC", 0, 50));
    assertThat(filtered.totalElements()).isEqualTo(3L);
    assertThat(filtered.items()).allMatch(r -> r.destinationCountry().equals("DE"));

    var filteredUs = as(actorA, "channel.operation.read", () -> tracking.subjects(null, "US", null,
        null, null, null, false, "LATEST_DESC", 0, 50));
    assertThat(filteredUs.totalElements()).isEqualTo(2L);
    assertThat(filteredUs.items()).allMatch(r -> r.destinationCountry().equals("US"));

    var byKeyword = as(actorA, "channel.operation.read", () -> tracking.subjects(null, null, null,
        null, null, "WB-PAGE-3", false, "LATEST_DESC", 0, 50));
    assertThat(byKeyword.totalElements()).isEqualTo(1L);
    assertThat(byKeyword.items().get(0).masterWaybillNo()).isEqualTo("WB-PAGE-3");
  }

  @Test
  @DisplayName("10.3.1.1 端点：轨迹清单 / 时间线 / 事件流 / 能力声明都真的转发")
  void endpointsRespond() throws Exception {
    seedOneSubject("WB-HTTP-1");
    push(actorA, "EVT-HTTP-1", "WB-HTTP-1", "PICKED_UP", PICKED_UP_AT, "", "");

    http.perform(get("/channel/logistics-tracking/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    http.perform(get("/channel/logistics-tracking/subjects")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    http.perform(get("/channel/logistics-tracking/events")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].carrierEventId").value("EVT-HTTP-1"));

    http.perform(get("/channel/logistics-tracking/pull-tasks")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 无会话一律 401
    http.perform(get("/channel/logistics-tracking/subjects")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.3.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedOneSubject("WB-TENANT-A", CARRIER, actorA, "Bearer tenant-a");
    seedOneSubject("WB-TENANT-B", CARRIER, actorB, "Bearer tenant-b");

    assertThat(listSubjects(actorA).items()).extracting(TrackingSubjectRow::masterWaybillNo)
        .containsExactly("WB-TENANT-A");
    assertThat(listSubjects(actorB).items()).extracting(TrackingSubjectRow::masterWaybillNo)
        .containsExactly("WB-TENANT-B");

    // B 用 A 的 id 读详情：跨租户与不存在返回**逐字相同**的 404
    UUID aSubjectId = subjectRow(actorA, "WB-TENANT-A").id();
    assertThatThrownBy(
            () ->
                as(
                    actorB,
                    "channel.operation.read",
                    () -> tracking.subject(aSubjectId.toString())))
        .hasMessageContaining("轨迹主体不存在");

    // B 往 A 的运单推事件 ⇒ 推不进去（运单不在 B 的轨迹主体里）
    var cross = push(actorB, "EVT-CROSS", "WB-TENANT-A", "PICKED_UP", PICKED_UP_AT, "", "");
    assertThat(cross.inserted()).isZero();
    assertThat(cross.rejections()).singleElement()
        .satisfies(r -> assertThat(r.reason()).contains("轨迹主体"));

    // 两边各一条事件都没有：A 没被 B 写脏，B 也没凭空多出记录
    assertThat(countEvents(actorA, "WB-TENANT-A")).isZero();
    assertThat(countEvents(actorB, "WB-TENANT-B")).isZero();
  }

  @Test
  @DisplayName("10.3.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedOneSubject("WB-TENANT-A", CARRIER, actorA, "Bearer tenant-a");
    seedOneSubject("WB-TENANT-B", CARRIER, actorB, "Bearer tenant-b");

    UUID aId = subjectRow(actorA, "WB-TENANT-A").id();
    UUID bId = subjectRow(actorB, "WB-TENANT-B").id();

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据
    http.perform(get("/channel/logistics-tracking/subjects")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(aId.toString())))
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bId.toString()))));

    // 伪造头不能替代会话：无会话一律 401
    http.perform(get("/channel/logistics-tracking/subjects")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.3.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedOneSubject("WB-TENANT-A", CARRIER, actorA, "Bearer tenant-a");
    seedOneSubject("WB-TENANT-B", CARRIER, actorB, "Bearer tenant-b");
    push(actorB, "EVT-B", "WB-TENANT-B", "PICKED_UP", PICKED_UP_AT, "", "");

    for (int round = 0; round < 3; round++) {
      assertThat(listSubjects(actorA).items()).extracting(TrackingSubjectRow::masterWaybillNo)
          .containsExactly("WB-TENANT-A");
      assertThat(listSubjects(actorB).items()).extracting(TrackingSubjectRow::masterWaybillNo)
          .containsExactly("WB-TENANT-B");
      assertThat(listSubjects(actorA).items()).extracting(TrackingSubjectRow::masterWaybillNo)
          .containsExactly("WB-TENANT-A");
    }

    // 事件流同样不串租户：B 的那条事件只在 B 那边看得见
    assertThat(eventsOf(actorA).items()).extracting(CarrierTrackingEventRow::carrierEventId)
        .doesNotContain("EVT-B");
    assertThat(eventsOf(actorB).items()).extracting(CarrierTrackingEventRow::carrierEventId)
        .contains("EVT-B");
  }

  @Test
  @DisplayName("10.3.1.1 RLS：表级策略存在且 FORCE，业务表 tenant_id NOT NULL")
  void rlsPoliciesExist() throws Exception {
    for (String table :
        List.of(
            "logistics_tracking_subject",
            "logistics_tracking_event",
            "logistics_tracking_pull_task",
            "logistics_tracking_pull_task_item")) {
      List<Map<String, Object>> rows =
          queryRows(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = '"
                  + table
                  + "'");
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("relrowsecurity")).isEqualTo(true);
      assertThat(rows.get(0).get("relforcerowsecurity")).isEqualTo(true);
      // tenant_id NOT NULL
      List<Map<String, Object>> notNull =
          queryRows(
              "select is_nullable from information_schema.columns"
                  + " where table_name = '"
                  + table
                  + "' and column_name = 'tenant_id'");
      assertThat(notNull).singleElement()
          .satisfies(r -> assertThat(r.get("is_nullable")).isEqualTo("NO"));
    }
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  /** 按租户种一条轨迹主体（上游按 bearer 分租户，与真实 trade 侧同构）。 */
  void seedOneSubject(
      String waybill, String carrier, AccessService.Access who, String bearer) {
    upstreamByBearer.put(
        bearer,
        List.of(shipment(UUID.randomUUID(), waybill, carrier, "US", SHIPPED_RECENT, "2000")));
    as(
        who,
        "channel.operation.create",
        () -> tracking.sync(new TrackingSyncInput(null, "STANDARD", 0, 50), bearer));
  }

  TrackingSubjectPage listSubjects(AccessService.Access who) {
    return as(
        who,
        "channel.operation.read",
        () -> tracking.subjects(null, null, null, null, null, null, false, "LATEST_DESC", 0, 50));
  }

  TrackingEventPage eventsOf(AccessService.Access who) {
    return as(
        who,
        "channel.operation.read",
        () -> tracking.events(null, null, null, false, 0, 50));
  }

  List<Map<String, Object>> queryRows(String sql) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var rs = s.executeQuery(sql);
      var meta = rs.getMetaData();
      var out = new ArrayList<Map<String, Object>>();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        out.add(row);
      }
      return out;
    }
  }

  void seedOneSubject(String waybill) {
    seedOneSubject(waybill, CARRIER);
  }

  void seedOneSubject(String waybill, String carrier) {
    seedOneSubject(waybill, carrier, actorA, "Bearer tenant-a");
  }

  TrackingEventPushResult push(
      AccessService.Access who,
      String carrierEventId,
      String waybill,
      String nodeType,
      OffsetDateTime occurredAt,
      String exceptionCode,
      String exceptionNote) {
    return push(who, carrierEventId, waybill, nodeType, occurredAt, exceptionCode, exceptionNote,
        CARRIER, null);
  }

  TrackingEventPushResult push(
      AccessService.Access who,
      String carrierEventId,
      String waybill,
      String nodeType,
      OffsetDateTime occurredAt,
      String exceptionCode,
      String exceptionNote,
      String carrier) {
    return push(who, carrierEventId, waybill, nodeType, occurredAt, exceptionCode, exceptionNote,
        carrier, null);
  }

  TrackingEventPushResult push(
      AccessService.Access who,
      String carrierEventId,
      String waybill,
      String nodeType,
      OffsetDateTime occurredAt,
      String exceptionCode,
      String exceptionNote,
      String carrier,
      OffsetDateTime carrierEta) {
    return as(
        who,
        "channel.operation.create",
        () ->
            tracking.push(
                new TrackingEventPushInput(
                    carrier,
                    List.of(
                        new TrackingEventPushItem(
                            carrierEventId, waybill, nodeType, "CODE", "描述", "上海",
                            occurredAt, exceptionCode, exceptionNote, carrierEta))),
                who.membershipId()));
  }

  UUID subjectIdOf(String waybill) {
    return subjectRow(waybill).id();
  }

  TrackingSubjectRow subjectRow(String waybill) {
    return subjectRow(actorA, waybill);
  }

  TrackingSubjectRow subjectRow(AccessService.Access who, String waybill) {
    var page = as(who, "channel.operation.read", () -> tracking.subjects(null, null, null, null,
        null, waybill, false, "LATEST_DESC", 0, 50));
    return page.items().stream()
        .filter(r -> r.masterWaybillNo().equals(waybill))
        .findFirst()
        .orElseThrow(() -> new AssertionError("找不到轨迹主体 " + waybill));
  }

  long countEvents(String waybill) {
    return countEvents(actorA, waybill);
  }

  long countEvents(AccessService.Access who, String waybill) {
    return as(
            who,
            "channel.operation.read",
            () ->
                sql.fetchOne(
                        "select count(*) from logistics_tracking_event e join"
                            + " logistics_tracking_subject s on s.tenant_id=e.tenant_id"
                            + " and s.id=e.subject_id where s.master_waybill_no = ?",
                        waybill)
                    .get(0, Long.class))
        ;
  }

  /** 绕过服务层直接写 SQL 插事件（用应用角色 + 租户上下文）。 */
  void rawInsertEvent(String waybill, String carrierEventId) {
    as(
        actorA,
        "channel.operation.create",
        () -> {
          sql.execute(
              "insert into logistics_tracking_event (id,tenant_id,subject_id,carrier_code,"
                  + "carrier_event_id,node_type,event_code,description,location,occurred_at,"
                  + "received_at,source,exception_code,exception_note,payload,created_at)"
                  + " select '" + UUID.randomUUID() + "',s.tenant_id,s.id,s.carrier_code,'"
                  + carrierEventId + "','IN_TRANSIT','X','x','x','" + IN_TRANSIT_AT
                  + "','" + IN_TRANSIT_AT + "','MANUAL_ATTESTATION','','','{}'::jsonb,now()"
                  + " from logistics_tracking_subject s where s.master_waybill_no = ?",
              waybill);
          return null;
        });
  }

  /** 用迁移超级账号直插（绕过 RLS 与权限层，只剩唯一约束与触发器）。 */
  void rawInsertEventAsSuper(String waybill, String carrierEventId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "insert into logistics_tracking_event (id,tenant_id,subject_id,carrier_code,"
              + "carrier_event_id,node_type,event_code,description,location,occurred_at,"
              + "received_at,source,exception_code,exception_note,payload,created_at)"
              + " select '" + UUID.randomUUID() + "',s.tenant_id,s.id,s.carrier_code,'"
              + carrierEventId + "','IN_TRANSIT','X','x','x','" + IN_TRANSIT_AT + "','"
              + IN_TRANSIT_AT + "','MANUAL_ATTESTATION','','','{}'::jsonb,now()"
              + " from logistics_tracking_subject s where s.master_waybill_no = '"
              + waybill + "'");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void rawUpdateEvent(String carrierEventId) {
    as(
        actorA,
        "channel.operation.update",
        () -> {
          sql.execute(
              "update logistics_tracking_event set description='改写' where carrier_event_id = ?",
              carrierEventId);
          return null;
        });
  }

  void rawUpdateEventAsSuper(String carrierEventId) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "update logistics_tracking_event set description='改写' where carrier_event_id = '"
              + carrierEventId + "'");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 取异常链里第一个非空 SQLSTATE（jOOQ 把 SQLException 包在 DataAccessException 里）。 */
  static String sqlStateOf(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof SQLException se && se.getSQLState() != null) {
        return se.getSQLState();
      }
    }
    return null;
  }

  static void exec(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
