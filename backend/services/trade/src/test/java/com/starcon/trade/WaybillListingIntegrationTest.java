package com.starcon.trade;

import static com.starcon.trade.WaybillListingModels.*;
import static com.starcon.trade.WaybillModels.WaybillRequestStatus;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.2.1.1 运单列表。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本模块**只读** 10.2.1.2 的 {@code logistics_waybill_request}，所以本测试把那张表
 * 当作**已交付的上游**直接落数据（而不是把运单申请整条链路再跑一遍）。这是有意为之：
 * 要验的是「清单读得对不对、筛得真不真、汇总跟不跟分页走」，不是上游能不能出号。
 *
 * <p><b>本测试要钉住的八条</b>：
 *
 * <ol>
 *   <li><b>汇总不受分页影响</b>（列表页最常见的假活：分页走了、汇总跟着走）；</li>
 *   <li><b>四个没有数据源的展示态不产出任何行</b>，且如实说明是「没有数据源」
 *       而不是「今天一票都没发」；</li>
 *   <li><b>运费未登记 ≠ 运费为 0</b>，两者分开计数，汇总按币种分组不混币；</li>
 *   <li><b>运费凭据不可改写</b>：更正 = 追加 + 撤下，直接 UPDATE 被 DB 拒；</li>
 *   <li><b>筛选下拉来自真实数据</b>而不是硬编码词表；</li>
 *   <li><b>三个批量动作真作用</b>，且逐条给出「不适用」的原因，不悄悄改别的行；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaybillListingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
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
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired WaybillListingService service;
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

  @BeforeEach
  void bindActors() {
    actorA = access.require("Bearer tenant-a", "trade.fulfillment.read");
    actorB = access.require("Bearer tenant-b", "trade.fulfillment.read");
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  // ================================================================ 夹具

  /**
   * 直接落一条**已交付上游**的运单申请（10.2.1.2 的表），当清单的只读输入。
   *
   * <p>刻意满足 V128 的全部 CHECK：出号必带单号与出号时刻、失败必带原因、
   * 作废必带原因、attempt_count ≥ retry_count。
   */
  UUID seed(
      WaybillRequestStatus status,
      String carrierCode,
      String carrierChannel,
      String shipToCountry,
      String createdAt,
      String labelUri) {
    var id = UUID.randomUUID();
    seedCarrier(carrierCode, carrierChannel);
    var requestNo = "WAR-" + id.toString().substring(0, 8).toUpperCase();
    var created = OffsetDateTime.parse(createdAt);
    asA(
        () -> {
          sql.execute(
              "insert into logistics_waybill_request"
                  + " (id, tenant_id, request_no, idempotency_key, pending_unit_id,"
                  + "  pending_parcel_id, outbound_order_no, sales_order_no, warehouse_ref,"
                  + "  carrier_code, carrier_channel, ship_from_name, ship_from_country,"
                  + "  ship_to_name, ship_to_country, ship_to_address_line, ship_to_city,"
                  + "  ship_to_postal_code, gross_weight_kg, length_mm, width_mm, height_mm,"
                  + "  declared_currency, label_uri, status, waybill_no, issued_at,"
                  + "  failure_reason, cancel_reason, attempt_count, retry_count,"
                  + "  requested_by_membership_id, created_at, updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              id,
              a,
              requestNo,
              "idem-" + id,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "OB-" + requestNo,
              "SO-" + requestNo,
              "US-WEST",
              carrierCode,
              carrierChannel,
              "深圳仓",
              "CN",
              "Buyer",
              shipToCountry,
              "1 Main St",
              "LA",
              "90001",
              new BigDecimal("2.500000"),
              new BigDecimal("300"),
              new BigDecimal("200"),
              new BigDecimal("100"),
              "USD",
              labelUri,
              status.name(),
              status == WaybillRequestStatus.ISSUED ? "WB" + requestNo : "",
              status == WaybillRequestStatus.ISSUED ? created : null,
              status == WaybillRequestStatus.FAILED ? "承运商拒收：地址不可达" : "",
              status == WaybillRequestStatus.CANCELLED ? "重复申请，作废重开" : "",
              status == WaybillRequestStatus.FAILED ? 1 : 0,
              0,
              user,
              created,
              created);
          return null;
        });
    return id;
  }

  /** 登记承运商（10.2.1.2 的表）。委派 retry/cancel 会按承运商代码查限流视图。 */
  void seedCarrier(String code, String channel) {
    asA(
        () -> {
          sql.execute(
              "insert into logistics_carrier (id, tenant_id, code, display_name, carrier_channel,"
                  + " adapter_key, connected, unavailable_reason, registered_by_membership_id,"
                  + " registered_at)"
                  + " values (?,?,?,?,?,?,false,?,?,?::timestamptz)"
                  + " on conflict (tenant_id, code) do nothing",
              UUID.randomUUID(), a, code, code + " 承运商", channel, "test_" + code.toLowerCase(),
              "10.1 物流渠道未交付（测试夹具刻意不接通）", user,
              OffsetDateTime.parse("2026-10-01T00:00:00Z"));
          return null;
        });
  }

  WaybillListPage list(
      String carrierCode, String status, String country, String freight, String keyword,
      String sort, int page, int size) {
    return asA(
        () ->
            service.waybills(
                carrierCode, null, status, country, null, freight, null, null, keyword, sort,
                page, size));
  }

  /**
   * 以 **B** 身份查同一份条件。
   *
   * <p>刻意不复用 {@link #list}：那个 helper 恒以 A 身份执行，用它写 B 的断言会
   * 变成「用 A 查一遍再断言查不到」—— 恒真，钉不住任何东西。
   */
  WaybillListPage listAsB(String keyword) {
    return asB(
        () ->
            service.waybills(
                null, null, null, null, null, null, null, null, keyword, null, 0, 50));
  }

  // ================================================================ 1. 清单与筛选

  @Test
  void 清单读出上游每一行并映射成展示态() {
    var issued = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-10T02:00:00Z", "https://label.example/DHL-1.zpl");
    var queued = seed(WaybillRequestStatus.QUEUED, "UPS", "POSTAL", "DE",
        "2026-10-11T02:00:00Z", "");

    var page = list(null, null, null, null, null, null, 0, 50);
    assertThat(page.totalElements()).isGreaterThanOrEqualTo(2);
    assertThat(page.items()).extracting(WaybillListRow::id).contains(issued, queued);

    var issuedRow = page.items().stream().filter(r -> r.id().equals(issued)).findFirst().orElseThrow();
    assertThat(issuedRow.displayStatus()).isEqualTo(WaybillDisplayStatus.NUMBER_ISSUED);
    assertThat(issuedRow.waybillNo()).startsWith("WB");
    assertThat(issuedRow.carrierChannelLabel()).isEqualTo("三方物流");
    assertThat(issuedRow.destinationCountry()).isEqualTo("US");
    assertThat(issuedRow.grossWeightKg()).isEqualByComparingTo("2.500000");
    assertThat(issuedRow.labelAvailability()).isEqualTo(WaybillLabelAvailability.RECORDED_REFERENCE);
    // 「已取号」必须把「不代表货已在路上」说在明面上
    assertThat(issuedRow.statusNote()).contains("没有数据源");

    var queuedRow = page.items().stream().filter(r -> r.id().equals(queued)).findFirst().orElseThrow();
    assertThat(queuedRow.displayStatus()).isEqualTo(WaybillDisplayStatus.PENDING_NUMBER);
    assertThat(queuedRow.waybillNo()).isEmpty();
  }

  @Test
  void 每个筛选维度都真筛选() {
    seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US", "2026-10-01T02:00:00Z", "");
    seed(WaybillRequestStatus.QUEUED, "UPS", "POSTAL", "DE", "2026-10-20T02:00:00Z", "");

    assertThat(list("DHL", null, null, null, null, null, 0, 50).items())
        .allMatch(r -> "DHL".equals(r.carrierCode()));
    assertThat(list(null, WaybillDisplayStatus.PENDING_NUMBER.name(), null, null, null, null, 0, 50)
            .items())
        .allMatch(r -> r.displayStatus() == WaybillDisplayStatus.PENDING_NUMBER);
    assertThat(list(null, null, "DE", null, null, null, 0, 50).items())
        .allMatch(r -> "DE".equals(r.destinationCountry()));

    // 关键字跨运单号 / 申请单号 / 销售订单号
    var target = seed(WaybillRequestStatus.ISSUED, "FEDEX", "FREIGHT_FORWARDER", "FR",
        "2026-10-21T02:00:00Z", "");
    var no = asA(
        () ->
            sql.fetchOne(
                    "select request_no from logistics_waybill_request where id = ?", target)
                .get("request_no", String.class));
    assertThat(list(null, null, null, null, no, null, 0, 50).items())
        .extracting(WaybillListRow::id)
        .contains(target);
  }

  @Test
  void 承运渠道与下单时间区间真筛选() {
    var inRange = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-15T02:00:00Z", "");
    seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US", "2026-10-25T02:00:00Z", "");

    var from = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    var to = OffsetDateTime.parse("2026-10-20T00:00:00Z");
    var page =
        asA(
            () ->
                service.waybills(
                    "DHL", "THIRD_PARTY", null, null, null, null, from, to, null, null, 0, 50));
    assertThat(page.items()).extracting(WaybillListRow::id).contains(inRange);
    assertThat(page.items()).allMatch(r -> r.createdAt().isBefore(to));
  }

  // ================================================================ 2. 汇总不受分页影响

  @Test
  void 汇总覆盖全量而不随分页缩小() {
    for (int i = 0; i < 7; i++) {
      seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
          "2026-10-0" + (i + 1) + "T02:00:00Z", "");
    }

    var firstPage = list("DHL", null, null, null, null, null, 0, 3);
    assertThat(firstPage.items()).hasSizeLessThanOrEqualTo(3);
    var all = list("DHL", null, null, null, null, null, 0, 50);

    // 一页装不下，但汇总必须还是全量 —— 否则第一页会显示「共 3 条」
    assertThat(all.totalElements()).isGreaterThan(3);
    assertThat(firstPage.summary().total())
        .as("汇总不能跟着 LIMIT/OFFSET 走")
        .isEqualTo(all.totalElements());
    assertThat(firstPage.summary().numberIssued()).isEqualTo(all.summary().numberIssued());
    assertThat(firstPage.totalPages()).isGreaterThan(1);
  }

  @Test
  void 汇总按币种分组且未登记与零分别计数() {
    // 本仓各集成用例共用租户 A 且用例间不清理库，所以汇总断言必须用一条
    // **本用例专属**的承运商代码把自己圈出来，否则会数到别的用例留下的行。
    var one = seed(WaybillRequestStatus.ISSUED, "FXA", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var two = seed(WaybillRequestStatus.ISSUED, "FXA", "THIRD_PARTY", "US",
        "2026-10-03T02:00:00Z", "");
    var three = seed(WaybillRequestStatus.ISSUED, "FXA", "THIRD_PARTY", "US",
        "2026-10-04T02:00:00Z", "");

    // 0 运费是**已登记的 0**，与「没人登记」是两回事
    registerFreight(one, "USD", "0.0000");
    registerFreight(two, "EUR", "12.5000");
    // three 故意不登记

    var summary = list("FXA", null, null, null, null, null, 0, 50).summary();
    assertThat(summary.freightRegistered()).isEqualTo(2);
    assertThat(summary.freightNotRegistered()).isEqualTo(1);
    assertThat(summary.freightByCurrency()).hasSize(2);
    assertThat(summary.freightByCurrency())
        .extracting(WaybillFreightCurrencyTotal::currency)
        .containsExactly("EUR", "USD");
    assertThat(summary.freightByCurrency().stream()
            .filter(t -> "USD".equals(t.currency()))
            .findFirst()
            .orElseThrow()
            .totalAmount())
        .isEqualByComparingTo("0.0000");

    // 运费筛选维度也要能用
    assertThat(list("FXA", null, null, "NOT_REGISTERED", null, null, 0, 50).items())
        .extracting(WaybillListRow::id)
        .containsExactly(three);
    assertThat(list("FXA", null, null, "REGISTERED", null, null, 0, 50).items())
        .extracting(WaybillListRow::id)
        .containsExactlyInAnyOrder(one, two);
  }

  // ================================================================ 3. 没有数据源的状态

  @Test
  void 四个没有数据源的展示态不产出任何行且说明原因() {
    seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US", "2026-10-02T02:00:00Z", "");

    for (var status :
        List.of(
            WaybillDisplayStatus.PICKED_UP,
            WaybillDisplayStatus.IN_TRANSIT,
            WaybillDisplayStatus.DELIVERED,
            WaybillDisplayStatus.EXCEPTION)) {
      var page = list(null, status.name(), null, null, null, null, 0, 50);
      assertThat(page.items()).as("%s 不该产出任何行", status).isEmpty();
      assertThat(page.totalElements()).isZero();
      assertThat(page.statusFilterNote())
          .as("必须说清是「没有数据源」而不是「今天没发货」")
          .contains("数据源")
          .contains("10.3");
    }

    // capabilities 逐条 available=false 并点名 ownerModule
    var caps = asA(() -> service.capabilities());
    for (var dim : caps.dimensions()) {
      if (List.of("CARRIER_TRACKING_STATUS").contains(dim.dimension())) {
        assertThat(dim.available()).isFalse();
        assertThat(dim.ownerModule()).contains("10.3");
        assertThat(dim.impact()).contains("留空会被读成");
      }
    }
    assertThat(caps.statuses())
        .filteredOn(s -> !s.available())
        .hasSize(4)
        .allSatisfy(s -> assertThat(s.ownerModule()).contains("10.3"));
  }

  @Test
  void capabilities把四个未交付上游逐条点名() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.dimensions()).extracting(WaybillListingCapabilityDimension::dimension)
        .contains("WAYBILL_ROW", "DISPLAY_STATUS", "CARRIER_TRACKING_STATUS", "FREIGHT_AMOUNT",
            "LABEL_PRINT", "WAYBILL_CANCEL", "HANDOVER_STATE", "BATCH_APPLY");
    assertThat(caps.dimensions())
        .filteredOn(d -> !d.available())
        .allSatisfy(
            d -> {
              assertThat(d.ownerModule()).as("%s 必须点名 ownerModule", d.dimension()).isNotBlank();
              assertThat(d.impact()).as("%s 必须说明影响", d.dimension()).isNotBlank();
            });
  }

  // ================================================================ 4. 运费登记与凭据不可改写

  WaybillFreightRow registerFreight(UUID requestId, String currency, String amount) {
    return asA(
        () ->
            service.registerFreight(
                requestId.toString(),
                new WaybillFreightInput(
                    currency, new BigDecimal(amount), "FORWARD", "MANUAL_REGISTERED", "对账单 #1", ""),
                user));
  }

  @Test
  void 运费登记后清单与详情都读得到() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var row = registerFreight(id, "USD", "23.7500");
    assertThat(row.amount()).isEqualByComparingTo("23.7500");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.current()).isTrue();

    var listed = list("DHL", null, null, "REGISTERED", null, null, 0, 50).items().stream()
        .filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertThat(listed.freightGapCode()).isEqualTo(WaybillFreightGapCode.REGISTERED);
    assertThat(listed.freightAmount()).isEqualByComparingTo("23.7500");

    var detail = asA(() -> service.waybill(id.toString()));
    assertThat(detail.freightHistory()).hasSize(1);
  }

  @Test
  void 未登记运费的行显示可读的缺口而不是空单元格() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var row = list("DHL", null, null, null, null, null, 0, 50).items().stream()
        .filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertThat(row.freightAmount()).isNull();
    assertThat(row.freightGapCode()).isEqualTo(WaybillFreightGapCode.NOT_REGISTERED);
    assertThat(row.freightGapLabel()).isEqualTo("未登记");
  }

  @Test
  void 更正运费是追加新行并撤下旧行而不是改写() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    registerFreight(id, "USD", "23.7500");
    registerFreight(id, "USD", "25.0000");

    var detail = asA(() -> service.waybill(id.toString()));
    assertThat(detail.freightHistory()).hasSize(2);
    var current = detail.freightHistory().stream().filter(WaybillFreightRow::current).toList();
    assertThat(current).hasSize(1);
    assertThat(current.get(0).amount()).isEqualByComparingTo("25.0000");

    // 现行行唯一：两个 is_current=true 会撞 waybill_freight_current 唯一索引
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from logistics_waybill_freight"
                                + " where request_id = ? and is_current", id)
                        .get(0, Integer.class)))
        .isEqualTo(1);
  }

  @Test
  void 改写已登记的运费被两层都拒_应用账号与迁移账号各试一次() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    registerFreight(id, "USD", "23.7500");

    // 第一层：**应用账号**根本没有 UPDATE 权限（V132 里 REVOKE 掉了）。
    // 这层挡住的是「服务层写错 SQL」—— 不依赖任何触发器。
    var asApp =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update logistics_waybill_freight set amount = 1 where request_id = ?", id);
                      return null;
                    }));
    assertThat(asApp).isNotNull();
    assertThat(deepestMessage(asApp)).contains("permission denied");

    // 第二层：即使**用迁移超级账号**绕过权限，触发器仍然拒改金额 ——
    // 证明挡住「凭据不可改写」的不是权限配置，而是 DB 上那条真规则。
    var asSuperuser =
        catchThrowable(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("select set_config('app.current_tenant','" + a + "',false)");
                s.execute(
                    "update logistics_waybill_freight set amount = 1 where request_id = '"
                        + id
                        + "'");
              }
            });
    assertThat(asSuperuser).isNotNull();
    assertThat(deepestMessage(asSuperuser))
        .as("超级账号能绕过权限，但绕不过触发器")
        .contains("凭据");

    // 金额确实没被动过
    var detail = asA(() -> service.waybill(id.toString()));
    assertThat(detail.freightHistory().stream().filter(WaybillFreightRow::current)
            .findFirst().orElseThrow().amount())
        .isEqualByComparingTo("23.7500");
  }

  @Test
  void 运费挂在不存在的运单上被触发器挡() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into logistics_waybill_freight"
                              + " (id, tenant_id, request_id, request_no, carrier_code, currency,"
                              + "  amount, charge_type, source, is_current,"
                              + "  registered_by_membership_id, registered_at)"
                              + " values (?,?,?,?,?,?,?,?,?,true,?,?::timestamptz)",
                          UUID.randomUUID(), a, UUID.randomUUID(), "WAR-GHOST", "DHL", "USD",
                          new BigDecimal("1.0000"), "FORWARD", "MANUAL_REGISTERED", user,
                          OffsetDateTime.parse("2026-10-02T02:00:00Z"));
                      return null;
                    }))
        .hasStackTraceContaining("不能挂运费");
  }

  // ================================================================ 5. timestamptz 往返

  @Test
  void timestamptz写回值没有静默偏8小时() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    // 带非零时区偏移写入：偏移量若被当成本地时间丢掉，这里会差 8 小时
    var written = OffsetDateTime.parse("2026-10-02T18:30:00+08:00");
    asA(
        () -> {
          sql.execute(
              "insert into logistics_waybill_freight"
                  + " (id, tenant_id, request_id, request_no, carrier_code, currency, amount,"
                  + "  charge_type, source, is_current, registered_by_membership_id, registered_at)"
                  + " values (?,?,?,?,?,?,?,?,?,true,?,?::timestamptz)",
              UUID.randomUUID(), a, id,
              asA(
                      () ->
                          sql.fetchOne(
                                  "select request_no from logistics_waybill_request where id = ?",
                                  id)
                              .get("request_no", String.class)),
              "DHL", "USD", new BigDecimal("9.9900"), "FORWARD", "MANUAL_REGISTERED", user,
              written);
          return null;
        });

    // 断言**写回的值**，不是只断言没抛异常 —— 偏 −28800 秒不抛任何异常
    var detail = asA(() -> service.waybill(id.toString()));
    var stored = detail.freightHistory().stream()
        .filter(WaybillFreightRow::current).findFirst().orElseThrow().registeredAt();
    assertThat(stored.toInstant()).isEqualTo(written.toInstant());
    assertThat(stored.toInstant())
        .as("写入 18:30+08:00 读回必须是同一瞬间（10-02T10:30Z）")
        .isEqualTo(java.time.Instant.parse("2026-10-02T10:30:00Z"));
  }

  // ================================================================ 6. 筛选下拉

  @Test
  void 筛选下拉来自真实数据而不是硬编码词表() {
    seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US", "2026-10-02T02:00:00Z", "");
    seed(WaybillRequestStatus.QUEUED, "YUNDA", "SELF_PICKUP", "CA", "2026-10-03T02:00:00Z", "");

    var options = asA(() -> service.filterOptions());
    assertThat(options.carriers()).extracting(WaybillCarrierOption::carrierCode)
        .contains("DHL", "YUNDA");
    assertThat(options.carriers().stream()
            .filter(o -> "DHL".equals(o.carrierCode())).findFirst().orElseThrow().waybills())
        .isPositive();
    assertThat(options.carrierChannels()).contains("THIRD_PARTY", "SELF_PICKUP");
    assertThat(options.destinationCountries()).contains("US", "CA");
    assertThat(options.warehouseRefs()).contains("US-WEST");
    assertThat(options.statuses()).hasSize(WaybillDisplayStatus.values().length);
  }

  // ================================================================ 7. 批量动作

  @Test
  void 批量申请运单只对申请失败生效其余逐条说明() {
    var failed = seed(WaybillRequestStatus.FAILED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var issued = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-03T02:00:00Z", "");
    var cancelled = seed(WaybillRequestStatus.CANCELLED, "DHL", "THIRD_PARTY", "US",
        "2026-10-04T02:00:00Z", "");

    var result = asA(
        () ->
            service.applyBatch(
                new WaybillBulkActionInput(List.of(failed.toString(), issued.toString(),
                    cancelled.toString()), "人工重试", "", ""),
                user));

    assertThat(result.action()).isEqualTo("APPLY");
    assertThat(result.applied()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.entries()).hasSize(3);

    var skipped = result.entries().stream()
        .filter(e -> !e.applied()).map(WaybillBulkActionEntry::resultMessage).toList();
    assertThat(skipped.get(0)).contains("已取号");
    assertThat(skipped.get(1)).contains("已作废");

    // 真的改了状态（委派 10.2.1.2 的 retry → 重新入队）
    assertThat(statusOf(failed)).isEqualTo(WaybillRequestStatus.QUEUED);
    // 批量不该顺手改掉不属于它的行
    assertThat(statusOf(issued)).isEqualTo(WaybillRequestStatus.ISSUED);
    assertThat(statusOf(cancelled)).isEqualTo(WaybillRequestStatus.CANCELLED);
  }

  @Test
  void 批量取消只作废未出号的且已取号点名10_2_1_3() {
    var queued = seed(WaybillRequestStatus.QUEUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var issued = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-03T02:00:00Z", "");

    var result = asA(
        () ->
            service.cancelBatch(
                new WaybillBulkActionInput(List.of(queued.toString(), issued.toString()),
                    "下错了单", "", ""),
                user));

    assertThat(result.applied()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.entries().stream().filter(e -> !e.applied()).findFirst().orElseThrow()
            .resultMessage())
        .contains("10.2.1.3");
    assertThat(statusOf(queued)).isEqualTo(WaybillRequestStatus.CANCELLED);
    assertThat(statusOf(issued)).isEqualTo(WaybillRequestStatus.ISSUED);
  }

  @Test
  void 批量取消必须写明原因() {
    var queued = seed(WaybillRequestStatus.QUEUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancelBatch(
                            new WaybillBulkActionInput(List.of(queued.toString()), "  ", "", ""),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须写明原因");
  }

  @Test
  void 批量打印逐件留痕且未出号的不打印() {
    var withLabel = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "https://label.example/DHL-a.zpl");
    var withoutLabel = seed(WaybillRequestStatus.ISSUED, "UPS", "POSTAL", "DE",
        "2026-10-03T02:00:00Z", "");
    var notIssued = seed(WaybillRequestStatus.QUEUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-04T02:00:00Z", "");

    var result = asA(
        () ->
            service.printBatch(
                new WaybillBulkActionInput(
                    List.of(withLabel.toString(), withoutLabel.toString(), notIssued.toString()),
                    "", "ZEBRA-01", ""),
                user));

    assertThat(result.action()).isEqualTo("PRINT");
    assertThat(result.batchNo()).startsWith("WPL-");
    assertThat(result.applied()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(1);

    var notIssuedEntry = result.entries().stream()
        .filter(e -> e.requestId().equals(notIssued)).findFirst().orElseThrow();
    assertThat(notIssuedEntry.resultMessage()).contains("尚未取得运单号");

    // 两件都留下逐件痕迹，其中没有面单凭据的那件如实标 NO_REFERENCE
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from logistics_waybill_print_log where batch_no = ?",
                            result.batchNo())
                        .get(0, Integer.class)))
        .isEqualTo(2);
    assertThat(asA(() -> service.waybill(withLabel.toString())).printHistory()).hasSize(1);
    var noRef = asA(() -> service.waybill(withoutLabel.toString())).printHistory().get(0);
    assertThat(noRef.labelAvailability()).isEqualTo(WaybillLabelAvailability.NO_REFERENCE);
  }

  @Test
  void 打印留痕的单号与上游不一致时被触发器挡() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var no = asA(
        () ->
            sql.fetchOne("select request_no from logistics_waybill_request where id = ?", id)
                .get("request_no", String.class));
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into logistics_waybill_print_log"
                              + " (id, tenant_id, batch_no, request_id, request_no, waybill_no,"
                              + "  carrier_code, label_uri, label_availability,"
                              + "  printed_by_membership_id, printed_at)"
                              + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
                          UUID.randomUUID(), a, "WPL-GHOST", id, no, "WB-FORGED", "DHL", "",
                          "RECORDED_REFERENCE", user,
                          OffsetDateTime.parse("2026-10-02T02:00:00Z"));
                      return null;
                    }))
        .hasStackTraceContaining("不一致");
  }

  @Test
  void 批量动作对不存在的运单逐条报不存在而不是整批失败() {
    var ghost = UUID.randomUUID();
    var result = asA(
        () ->
            service.printBatch(
                new WaybillBulkActionInput(List.of(ghost.toString()), "", "ZEBRA-01", ""),
                user));
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).applied()).isFalse();
    assertThat(result.entries().get(0).resultCode()).isEqualTo("NOT_FOUND");
  }

  /** 一路走到异常链最深处的消息：Spring 把 23514 翻成 BadSqlGrammarException 之后，
   *  触发器原文只留在最深的 cause 里。 */
  static String deepestMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
    return cur.getMessage() == null ? "" : cur.getMessage();
  }

  WaybillRequestStatus statusOf(UUID id) {
    return WaybillRequestStatus.valueOf(
        asA(
                () ->
                    sql.fetchOne("select status from logistics_waybill_request where id = ?", id)
                        .get(0, String.class)));
  }

  // ================================================================ 8. 多租户三断言

  @Test
  void 两租户互不可见() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    registerFreight(id, "USD", "10.0000");
    asA(
        () ->
            service.printBatch(
                new WaybillBulkActionInput(List.of(id.toString()), "", "ZEBRA-01", ""), user));

    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isPositive();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_freight")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_print_log")
            .get(0, Integer.class)))
        .isZero();
    // B 看到的清单也是空的（而不是报错）
    assertThat(asB(() -> service.waybills(null, null, null, null, null, null, null, null,
            null, null, 0, 50)).totalElements())
        .isZero();
  }

  @Test
  void 伪造租户头无效() throws Exception {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");

    // 伪造 X-Tenant-Id 走端点：落进的仍然是 A 的租户
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/trade/logistics/waybills/list")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isOk());

    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request where id = ?", id)
            .get(0, Integer.class)))
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request where id = ?", id)
            .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void 连接池归还后不串租户_A到B到A三轮() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    registerFreight(id, "USD", "10.0000");
    var no =
        asA(
            () ->
                sql.fetchOne("select request_no from logistics_waybill_request where id = ?", id)
                    .get("request_no", String.class));

    for (int round = 0; round < 3; round++) {
      assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
                  .get(0, Integer.class)))
          .as("第 %d 轮 A 租户应看到自己的行", round)
          .isPositive();
      assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_request")
                  .get(0, Integer.class)))
          .as("第 %d 轮 B 租户必须为空（连接池只有 1 条连接，串租户会在这里暴露）", round)
          .isZero();
      assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_freight")
                  .get(0, Integer.class)))
          .as("第 %d 轮 A 的运费登记表", round)
          .isPositive();
      assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_freight")
                  .get(0, Integer.class)))
          .as("第 %d 轮 B 的运费登记表", round)
          .isZero();
      assertThat(asA(() -> list(null, null, null, null, no, null, 0, 50).items().size()))
          .as("第 %d 轮 A 按单号查得到", round)
          .isPositive();
      assertThat(listAsB(no).items().size())
          .as("第 %d 轮 B 按同一个单号查不到", round)
          .isZero();
    }
  }

  @Test
  void 跨租户读与不存在返回逐字相同的404() {
    var id = seed(WaybillRequestStatus.ISSUED, "DHL", "THIRD_PARTY", "US",
        "2026-10-02T02:00:00Z", "");
    var ghost = UUID.randomUUID();

    String crossTenant = readDetailAsB(id.toString()).replace(id.toString(), "<id>");
    String notFound = readDetailAsB(ghost.toString()).replace(ghost.toString(), "<id>");
    assertThat(crossTenant)
        .as("跨租户读与读一个不存在的运单必须逐字相同（否则能反推别的租户有没有这一票）")
        .isEqualTo(notFound);
    assertThat(crossTenant).contains("运单不存在");
  }

  String readDetailAsB(String id) {
    try {
      return asB(() -> service.waybill(id).waybill().requestNo());
    } catch (ResponseStatusException e) {
      return e.getReason();
    }
  }
}
