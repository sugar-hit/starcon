package com.starcon.trade;

import static com.starcon.trade.DropshipOrderModels.DropshipOrderInput;
import static com.starcon.trade.DropshipOrderModels.DropshipPaymentConfirmInput;
import static com.starcon.trade.DropshipTrackingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.6.1.3 代发履约跟踪与异常。
 *
 * <p>本测试是 <b>V126 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>七组断言</b>：
 * <ol>
 *   <li><b>两段物流必须分列</b>（蓝图唯一不可让步的一条）：录供应商段不写我方段，
 *       录我方段不写供应商段，两组运单号与承运商各自独立、互不覆盖；</li>
 *   <li><b>三类异常由规则检出</b>：超时未发货 / 轨迹长时间无更新 /
 *       报发货后无运单，各自有真实判据，且未过期不误报、重复跑不刷屏；</li>
 *   <li><b>三类处置各有状态流转</b>：催单 / 改自营 / 退款，落处置 + 任务 + 事件；
 *       「改自营发货」真的把生效段切过去且供应商段**保留**为 ABANDONED；</li>
 *   <li><b>回传平台只到「待办」为止</b>：未给运单号时无任务，给了才有；
 *       下游受理前 callback_status 不得显示已回传；</li>
 *   <li><b>时间写回正确</b>：timestamptz 往返不发生 −28800s 静默偏移；</li>
 *   <li><b>DB 级闸门</b>：两段不得同时在途、异常证据不可改写、事件流 append-only；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 /
 *       连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DropshipTrackingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-dropship-tracking-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        idFor(tenant, 99),
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
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired DropshipTrackingService service;
  @Autowired DropshipOrderService dropshipOrders;
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
  void setUp() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  static OffsetDateTime at(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .plusDays(daysFromNow)
        .withNano(0)
        .truncatedTo(ChronoUnit.SECONDS);
  }

  static OffsetDateTime hoursAgo(int hours) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .minusHours(hours)
        .withNano(0)
        .truncatedTo(ChronoUnit.SECONDS);
  }

  static java.sql.SQLException rootSqlException(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql) {
        return sql;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    throw new AssertionError("异常链里没有 SQLException，说明不是 DB 拒绝：", error);
  }

  static void assertDbRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String expectedFragment) {
    var error = catchThrowable(work);
    var e = rootSqlException(error);
    assertThat(e.getSQLState())
        .as("必须被 DB 拒绝，实际 SQLState=%s，消息=%s", e.getSQLState(), e.getMessage())
        .isEqualTo("23514");
    assertThat(e.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
  }

  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /** 以**表所有者**身份直连执行一条 SQL（FORCE RLS 对 owner 同样生效，仍需显式绑定租户）。 */
  static OwnerSql asTableOwner(UUID tenant, String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenant + "',false)");
        st.execute(sqlText);
      }
    };
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  @AfterEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_tracking_event,fulfillment_dropship_task,"
              + " fulfillment_dropship_disposition,fulfillment_dropship_tracking_exception,"
              + " fulfillment_dropship_tracking,fulfillment_dropship_tracking_policy,"
              + " fulfillment_dropship_order_event,fulfillment_dropship_alert,"
              + " fulfillment_dropship_order_line,fulfillment_dropship_order,"
              + " fulfillment_dropship_supplier_endpoint,"
              + " fulfillment_dropship_config_line,fulfillment_dropship_config,"
              + " order_address_snapshot,sales_order_line,sales_order_party_snapshot,"
              + " sales_order_status_history,sales_order cascade");
    }
  }

  record Fixture(UUID configId, UUID salesOrderId) {}

  /** 一个租户的三层夹具：生效的 9.6.1.1 配置 → 已提交的销售订单。 */
  Fixture seedTenant(UUID tenant) {
    return as(
        tenant,
        () -> {
          UUID configId = idFor(tenant, 11);
          sql.execute(
              "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
                  + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
                  + "ownership_reviewed_with,settlement_timing,settlement_basis,currency_code,"
                  + "supplier_cost,reference_sell_price,availability_check_mode,listing_policy,"
                  + "priority,status,effective_from,activated_at,note)"
                  + " values (?,?,'DSH-ORD-1','代发单用配置','SHOP','SHOP-US-1','',"
                  + "'SUPPLIER_DIRECT','SUP-ACME','PURE_AGENCY','','ON_RECEIPT','FLAT_COST','USD',"
                  + "10.0000,25.0000,'SKIP','ALWAYS_LIST',10,'ACTIVE',?::timestamptz,?::timestamptz,'')",
              configId, tenant, at(-1), at(-1));
          UUID orderId = idFor(tenant, 21);
          BigDecimal lineAmount = new BigDecimal("25.0000");
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,grand_total_amount)"
                  + " values (?,?,'SO-TRK-1','MANUAL','DROPSHIP','USD','PREPAID','SUBMITTED',"
                  + "?::timestamptz,?,?)",
              orderId, tenant, at(-1), lineAmount, lineAmount);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,line_amount)"
                  + " values (?,?,?,1,?,'SKU-A','测试商品',1.000000,25.0000,25.0000)",
              idFor(tenant, 22), tenant, orderId, idFor(tenant, 23));
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type)"
                  + " values (?,?,?,?,'C-1','测试买家','INDIVIDUAL')",
              idFor(tenant, 24), tenant, orderId, idFor(tenant, 25));
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,country_code,province,city,address_line1)"
                  + " values (?,?,?,'SHIPPING','收件人','13800000000','US','CA','Los Angeles',"
                  + "'123 Main St')",
              idFor(tenant, 26), tenant, orderId);
          return new Fixture(configId, orderId);
        });
  }

  /** 代发单推进到 DISPATCHED（已提交供应商），这时才谈得上「跟踪供应商发货」。 */
  UUID dispatchedOrder(UUID tenant, Fixture f) {
    return as(
        tenant,
        () -> {
          var saved =
              dropshipOrders.createOrder(
                  new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(tenant, 99));
          dropshipOrders.confirmPayment(
              saved.id(),
              new DropshipPaymentConfirmInput(at(0), idFor(tenant, 99), "银行流水号 BR-2026-001"),
              idFor(tenant, 99));
          dropshipOrders.dispatch(saved.id(), idFor(tenant, 99));
          return saved.id();
        });
  }

  UUID newTracking(UUID tenant, Fixture f, OffsetDateTime promisedShipAt) {
    UUID orderId = dispatchedOrder(tenant, f);
    return as(
        tenant,
        () ->
            service
                .createTracking(
                    new DropshipTrackingInput(orderId, promisedShipAt, "跟踪"), idFor(tenant, 99))
                .id());
  }

  DropshipTrackingRow trackingRow(UUID tenant, UUID id) {
    return as(tenant, () -> service.trackingDetail(id).tracking());
  }

  List<DropshipTrackingExceptionRow> exceptions(UUID tenant, UUID trackingId) {
    return as(tenant, () -> service.trackingDetail(trackingId).exceptions());
  }

  List<DropshipTaskRow> tasks(UUID tenant, UUID trackingId) {
    return as(tenant, () -> service.trackingDetail(trackingId).tasks());
  }

  DropshipTrackingEvaluateResult evaluate(UUID tenant) {
    return as(tenant, service::evaluateTrackingExceptions);
  }

  // ==========================================================================
  // 一、迁移落地与 RLS 强制
  // ==========================================================================
  @Test
  void 迁移落地且六张表全部强制rls() {
    for (var table :
        List.of(
            "fulfillment_dropship_tracking_policy",
            "fulfillment_dropship_tracking",
            "fulfillment_dropship_tracking_exception",
            "fulfillment_dropship_disposition",
            "fulfillment_dropship_task",
            "fulfillment_dropship_tracking_event")) {
      var flags =
          as(
              a,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity as rls, relforcerowsecurity as force"
                          + " from pg_class where relname = ?",
                      table));
      assertThat(flags.get("rls", Boolean.class)).as("%s 必须 ENABLE ROW LEVEL SECURITY", table).isTrue();
      assertThat(flags.get("force", Boolean.class))
          .as("%s 必须 FORCE ROW LEVEL SECURITY（否则表所有者绕过策略）", table)
          .isTrue();
    }
    var f = seedTenant(a);
    newTracking(a, f, at(2));
    assertThat(sql.fetch("select id from fulfillment_dropship_tracking").size())
        .as("事务外必须读不到任何行（FORCE RLS 对表所有者同样生效）")
        .isZero();
  }

  @Test
  void 策略视图随租户隔离且未登记时返回内置默认值() {
    var fA = seedTenant(a);
    // 未登记：如实报 policyRegistered=false，并给出可用的默认阈值
    var dflt = asA(service::policy);
    assertThat(dflt.policyRegistered()).as("未登记必须如实报 false").isFalse();
    assertThat(dflt.stallThresholdHours()).isEqualTo(72);
    assertThat(dflt.delayToleranceHours()).isZero();
    assertThat(dflt.requireTrackingNoOnShipped()).isTrue();

    asA(
        () -> {
          service.savePolicy(
              new DropshipTrackingPolicyInput(2, 24, 36, 100, true, "A 租户阈值"),
              idFor(a, 99));
          return null;
        });
    var set = asA(service::policy);
    assertThat(set.policyRegistered()).isTrue();
    assertThat(set.stallThresholdHours()).isEqualTo(36);
    // B 租户仍看不到 A 的策略（视图 security_invoker + 底层表 RLS）
    var fb = seedTenant(b);
    assertThat(asB(service::policy).stallThresholdHours())
        .as("A 的策略不得泄漏给 B")
        .isEqualTo(72);
  }

  // ==========================================================================
  // 二、两段物流必须分列（蓝图核心）
  // ==========================================================================
  @Test
  void 建跟踪时两段各自独立_互不预填() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    var row = trackingRow(a, id);
    assertThat(row.activeLeg()).isEqualTo("SUPPLIER");
    assertThat(row.supplierLegStatus()).isEqualTo("NOT_SHIPMENT");
    assertThat(row.ownLegStatus()).isEqualTo("NOT_HANDED_OVER");
    assertThat(row.supplierTrackingNo()).isEmpty();
    assertThat(row.ownTrackingNo()).isEmpty();
    assertThat(row.supplierPromisedShipAt().toInstant())
        .as("承诺发货时限是延迟判定的唯一基准，必须真的写进去")
        .isEqualTo(at(2).toInstant());
  }

  @Test
  void 录供应商段不写我方段_两组运单号与承运商各自独立() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "SHIPPED",
                    "SUP-CARRIER",
                    "SF-SUP-0001",
                    hoursAgo(10),
                    hoursAgo(10),
                    null,
                    "供应商已发出"),
                idFor(a, 99)));

    var afterSupplier = trackingRow(a, id);
    assertThat(afterSupplier.supplierTrackingNo()).isEqualTo("SF-SUP-0001");
    assertThat(afterSupplier.supplierCarrierCode()).isEqualTo("SUP-CARRIER");
    assertThat(afterSupplier.ownTrackingNo())
        .as("录供应商段不得写我方运单号：两段是两组列，不是一个字段")
        .isEmpty();
    assertThat(afterSupplier.ownLegStatus()).isEqualTo("NOT_HANDED_OVER");

    asA(
        () ->
            service.recordOwnLeg(
                id,
                new DropshipOwnLegInput(
                    "HANDED_OVER", "OWN-CARRIER", "OW-SUP-0002", hoursAgo(1), hoursAgo(1), null, ""),
                idFor(a, 99)));

    var both = trackingRow(a, id);
    assertThat(both.supplierTrackingNo())
        .as("录我方段不得覆盖供应商运单号 —— 跑单纠纷问的就是供应商有没有发过")
        .isEqualTo("SF-SUP-0001");
    assertThat(both.ownTrackingNo()).isEqualTo("OW-SUP-0002");
    assertThat(both.supplierCarrierCode()).isEqualTo("SUP-CARRIER");
    assertThat(both.ownCarrierCode()).isEqualTo("OWN-CARRIER");

    // DB 层直查：两组列物理存在且同时有值
    var raw =
        asA(
            () ->
                sql.fetchOne(
                    "select supplier_tracking_no,own_tracking_no,supplier_carrier_code,"
                        + "own_carrier_code from fulfillment_dropship_tracking where id = ?",
                    id));
    assertThat(raw.get("supplier_tracking_no", String.class)).isEqualTo("SF-SUP-0001");
    assertThat(raw.get("own_tracking_no", String.class)).isEqualTo("OW-SUP-0002");
    assertThat(raw.get("supplier_carrier_code", String.class)).isEqualTo("SUP-CARRIER");
    assertThat(raw.get("own_carrier_code", String.class)).isEqualTo("OWN-CARRIER");
  }

  // ==========================================================================
  // 三、三类异常由规则检出
  // ==========================================================================
  @Test
  void 延迟发货由承诺时限检出_未过期不误报() {
    var f = seedTenant(a);
    // 未过期（3 天后才承诺发货）→ 不该检出
    UUID ok = newTracking(a, f, at(3));
    assertThat(evaluate(a).detected()).as("未到承诺时限不得误报").isZero();
    assertThat(exceptions(a, ok)).isEmpty();

    // 已过期（2 天前就该发）→ 检出，且是 WARNING（逾期 48h > critical 24h → CRITICAL）
    UUID late = newTracking(a, seedTenant2(a), at(-2));
    var result = evaluate(a);
    assertThat(result.detected()).isEqualTo(1);
    assertThat(result.detectedKinds()).contains("DELAYED_SHIPMENT(1)");
    var ex = exceptions(a, late).get(0);
    assertThat(ex.exceptionKind()).isEqualTo("DELAYED_SHIPMENT");
    assertThat(ex.ruleCode()).isEqualTo("PAST_PROMISED_SHIP_NO_SHIPMENT");
    assertThat(ex.ruleThresholdHours())
        .as("阈值必须随异常一起存下来：事后改策略不能让历史异常看起来是按新规则算的")
        .isZero();
    assertThat(ex.ruleEvidence()).contains("未发货");
    assertThat(ex.severity())
        .as("逾期 48 小时超过 critical_delay_hours=24，必须是 CRITICAL")
        .isEqualTo("CRITICAL");

    // 重复跑不得刷屏（部分唯一索引 + not exists）
    assertThat(evaluate(a).detected()).as("同一 (tracking, kind) 不得重复检出").isZero();
    assertThat(exceptions(a, late)).hasSize(1);
  }

  @Test
  void 丢件由轨迹静默时长检出_有新轨迹就不报() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    // 已发货、有运单号，但轨迹 100 小时没动（默认阈值 72 小时）
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "IN_TRANSIT", "SUP-CARRIER", "SF-STALL-1", hoursAgo(120), hoursAgo(100), null, ""),
                idFor(a, 99)));
    assertThat(evaluate(a).detected()).isEqualTo(1);
    var ex = exceptions(a, id).get(0);
    assertThat(ex.exceptionKind()).isEqualTo("PARCEL_LOST");
    assertThat(ex.ruleCode()).isEqualTo("NO_TRACK_EVENT_BEYOND_THRESHOLD");
    assertThat(ex.ruleThresholdHours()).isEqualTo(72);
    assertThat(ex.ruleEvidence()).contains("SF-STALL-1").contains("无更新");
    assertThat(ex.severity())
        .as("静默 100h 未超 critical_stall_hours=120，应为 WARNING")
        .isEqualTo("WARNING");

    // 轨迹更新到最近 → 判据不再成立（已存在的异常不会被抹掉，那是历史凭据）
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "IN_TRANSIT", "SUP-CARRIER", "SF-STALL-1", hoursAgo(120), hoursAgo(1), null, ""),
                idFor(a, 99)));
    assertThat(evaluate(a).detected()).as("轨迹更新后不得重复检出同一条").isZero();
  }

  @Test
  void 丢件超过静默分界时升级为严重() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "IN_TRANSIT", "SUP-CARRIER", "SF-LOST-1", hoursAgo(200), hoursAgo(150), null, ""),
                idFor(a, 99)));
    assertThat(evaluate(a).detected()).isEqualTo(1);
    assertThat(exceptions(a, id).get(0).severity())
        .as("静默 150h 超过 critical_stall_hours=120，必须 CRITICAL")
        .isEqualTo("CRITICAL");
  }

  @Test
  void 供应商报发货却无运单号_判为跑单() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id, new DropshipSupplierLegInput("SHIPPED", "", "", hoursAgo(5), hoursAgo(5), null, ""),
                idFor(a, 99)));
    var result = evaluate(a);
    assertThat(result.detected()).isEqualTo(1);
    assertThat(result.detectedKinds()).contains("SUPPLIER_RUNNING_AWAY(1)");
    var ex = exceptions(a, id).get(0);
    assertThat(ex.exceptionKind()).isEqualTo("SUPPLIER_RUNNING_AWAY");
    assertThat(ex.ruleCode()).isEqualTo("SHIPPED_WITHOUT_TRACKING_NO");
    assertThat(ex.severity()).as("无运单号恒 CRITICAL").isEqualTo("CRITICAL");
    assertThat(ex.ruleEvidence()).contains("无法回传平台");
  }

  @Test
  void 跑了单规则可按策略关闭_有运单号不报() {
    var f = seedTenant(a);
    asA(
        () -> {
          service.savePolicy(
              new DropshipTrackingPolicyInput(0, 24, 72, 120, false, "线下发货店铺不要求运单号"),
              idFor(a, 99));
          return null;
        });
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id, new DropshipSupplierLegInput("SHIPPED", "", "", hoursAgo(5), hoursAgo(5), null, ""),
                idFor(a, 99)));
    assertThat(evaluate(a).detected())
        .as("策略关闭后跑单规则停用 —— 阈值是经营口径，不是写死的常量")
        .isZero();

    // 有运单号时永不判跑单
    UUID id2 = newTracking(a, seedTenant2(a), at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id2,
                new DropshipSupplierLegInput(
                    "SHIPPED", "C", "SF-OK-1", hoursAgo(5), hoursAgo(5), null, ""),
                idFor(a, 99)));
    assertThat(evaluate(a).detected()).isZero();
  }

  /** 同一租户第二个销售订单（跑单用例里需要第二张单）。 */
  Fixture seedTenant2(UUID tenant) {
    return as(
        tenant,
        () -> {
          UUID configId = idFor(tenant, 31);
          sql.execute(
              "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
                  + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
                  + "ownership_reviewed_with,settlement_timing,settlement_basis,currency_code,"
                  + "supplier_cost,reference_sell_price,availability_check_mode,listing_policy,"
                  + "priority,status,effective_from,activated_at,note)"
                  + " values (?,?,'DSH-ORD-2','第二张配置','SHOP','SHOP-US-2','',"
                  + "'SUPPLIER_DIRECT','SUP-BETA','PURE_AGENCY','','ON_RECEIPT','FLAT_COST','USD',"
                  + "10.0000,25.0000,'SKIP','ALWAYS_LIST',11,'ACTIVE',?::timestamptz,?::timestamptz,'')",
              configId, tenant, at(-1), at(-1));
          UUID orderId = idFor(tenant, 32);
          BigDecimal lineAmount = new BigDecimal("25.0000");
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,grand_total_amount)"
                  + " values (?,?,'SO-TRK-2','MANUAL','DROPSHIP','USD','PREPAID','SUBMITTED',"
                  + "?::timestamptz,?,?)",
              orderId, tenant, at(-1), lineAmount, lineAmount);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,line_amount)"
                  + " values (?,?,?,1,?,'SKU-B','测试商品2',1.000000,25.0000,25.0000)",
              idFor(tenant, 33), tenant, orderId, idFor(tenant, 34));
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type)"
                  + " values (?,?,?,?,'C-2','测试买家2','INDIVIDUAL')",
              idFor(tenant, 35), tenant, orderId, idFor(tenant, 36));
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,country_code,province,city,address_line1)"
                  + " values (?,?,?,'SHIPPING','收件人','13800000000','US','CA','Los Angeles',"
                  + "'123 Main St')",
              idFor(tenant, 37), tenant, orderId);
          return new Fixture(configId, orderId);
        });
  }

  @Test
  void 误报判为误报后可重新检出_因为那是一条新事实() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    assertThat(evaluate(a).detected()).isEqualTo(1);
    var ex = exceptions(a, id).get(0);

    asA(
        () -> {
          service.dismissException(
              ex.id(), new DropshipDismissInput("供应商已口头确认今天发出，平台考核暂不追究"),
              idFor(a, 99));
          return null;
        });
    assertThat(exceptions(a, id).get(0).status()).isEqualTo("DISMISSED");

    // 判为误报后重新逾期 → 必须能再次检出（否则一次误报会永久压掉后续真异常）
    assertThat(evaluate(a).detected()).isEqualTo(1);
    assertThat(exceptions(a, id)).hasSize(2);
    assertThat(exceptions(a, id).get(0).exceptionNo())
        .as("重新检出的是一条新异常，编号不同 —— 不是同一条被翻案")
        .isNotEqualTo(ex.exceptionNo());
  }

  // ==========================================================================
  // 四、三类处置
  // ==========================================================================
  @Test
  void 催供应商_落处置与待办且催单次数可查() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();

    var saved =
        asA(
            () ->
                service.dispose(
                    exId,
                    new DropshipDisposeInput("URGE_SUPPLIER", null, null, "已电话催供应商今日发出"),
                    idFor(a, 99)));
    assertThat(saved.status()).isEqualTo("PENDING");
    var row = trackingRow(a, id);
    assertThat(row.urgeCount()).as("催了几次要看得见").isEqualTo(1);
    assertThat(row.lastUrgedAt()).isNotNull();

    var taskList = tasks(a, id);
    assertThat(taskList).hasSize(1);
    assertThat(taskList.get(0).taskKind()).isEqualTo("SUPPLIER_URGE");
    assertThat(taskList.get(0).taskStatus()).isEqualTo("OPEN");
    assertThat(taskList.get(0).targetSystem()).isEqualTo("SUPPLIER");
    assertThat(taskList.get(0).payload().note()).isEqualTo("已电话催供应商今日发出");

    // 异常进入已确认，不再悬空
    assertThat(exceptions(a, id).get(0).status()).isEqualTo("ACKNOWLEDGED");

    // 再催一次 → 计数累加，幂等键不同，不会覆盖上一条
    asA(
        () -> {
          service.dispose(
              exceptions(a, id).get(0).id(),
              new DropshipDisposeInput("URGE_SUPPLIER", null, null, "再次催单"),
              idFor(a, 99));
          return null;
        });
    assertThat(trackingRow(a, id).urgeCount()).isEqualTo(2);
    assertThat(tasks(a, id)).hasSize(2);
  }

  @Test
  void 改自营发货_生效段真的切过去且供应商段保留为凭据() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    var exId = exceptions(a, id).get(0).id();

    // 先给供应商段一点历史，使「放弃」这一动作有真实的凭据意义
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "SHIPPED", "SUP-CARRIER", "SF-SW-1", hoursAgo(30), hoursAgo(30), null, ""),
                idFor(a, 99)));
    evaluate(a);

    asA(
        () -> {
          service.dispose(
              exceptions(a, id).get(0).id(),
              new DropshipDisposeInput(
                  "SWITCH_TO_SELF", null, null, "供应商连续延迟，改由我方仓发货"),
              idFor(a, 99));
          return null;
        });

    var row = trackingRow(a, id);
    assertThat(row.activeLeg()).as("蓝图：异常必须可切换").isEqualTo("OWN");
    assertThat(row.supplierLegStatus())
        .as("供应商段必须保留为 ABANDONED 凭据，不能删也不能留在途")
        .isEqualTo("ABANDONED");
    assertThat(row.supplierTrackingNo())
        .as("放弃不等于抹掉：供应商发过货这件事仍然可查")
        .isEqualTo("SF-SW-1");
    assertThat(row.switchedToOwnAt()).isNotNull();
    assertThat(row.switchedToOwnByMembershipId()).isEqualTo(idFor(a, 99));
    assertThat(row.switchReason()).contains("我方仓");

    // 该跟踪上未决异常全部结案
    assertThat(exceptions(a, id))
        .allSatisfy(e -> assertThat(e.status()).isEqualTo("DISPOSED"));

    // 切给我方仓的待办
    var taskList = tasks(a, id);
    assertThat(taskList).anySatisfy(
        t -> {
          assertThat(t.taskKind()).isEqualTo("SELF_FULFILL_SWITCH");
          assertThat(t.targetSystem()).isEqualTo("WAREHOUSE");
          assertThat(t.taskStatus()).isEqualTo("OPEN");
        });

    // 切到自有段之后，供应商段的规则不再对该单生效
    assertThat(evaluate(a).detected()).as("切段后不应再检出供应商段异常").isZero();

    // 我方段开始推进（此前 NOT_HANDED_OVER）
    asA(
        () ->
            service.recordOwnLeg(
                id,
                new DropshipOwnLegInput(
                    "HANDED_OVER", "OWN-CARRIER", "OW-SW-1", hoursAgo(2), hoursAgo(2), null, ""),
                idFor(a, 99)));
    var after = trackingRow(a, id);
    assertThat(after.ownLegStatus()).isEqualTo("HANDED_OVER");
    assertThat(after.supplierLegStatus()).isEqualTo("ABANDONED");
    assertThat(after.ownTrackingNo()).isEqualTo("OW-SW-1");
    assertThat(after.supplierTrackingNo())
        .as("切段之后两段运单号仍然并存 —— 这正是不许合成一个字段的理由")
        .isEqualTo("SF-SW-1");
  }

  @Test
  void 发起退款_落待办任务且金额按数值入库() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();

    asA(
        () -> {
          service.dispose(
              exId,
              new DropshipDisposeInput(
                  "REQUEST_REFUND", new BigDecimal("31.5678"), "usd", "供应商跑单，无法履约"),
              idFor(a, 99));
          return null;
        });

    var detail = asA(() -> service.trackingDetail(id));
    assertThat(detail.dispositions()).hasSize(1);
    var d = detail.dispositions().get(0);
    assertThat(d.actionKind()).isEqualTo("REQUEST_REFUND");
    assertThat(d.targetSystem()).isEqualTo("AFTER_SALE");
    assertThat(d.refundCurrencyCode()).as("币种统一大写").isEqualTo("USD");
    assertThat(d.refundAmount())
        .as("金额走 numeric(19,4)，不得被浮点截断")
        .isEqualByComparingTo("31.5678");
    assertThat(d.status()).isEqualTo("PENDING");

    // 下游未交付 → 只落待办，不假装退款已完成
    var refund =
        detail.tasks().stream()
            .filter(t -> t.taskKind().equals("REFUND_REQUEST"))
            .findFirst()
            .orElseThrow();
    assertThat(refund.taskStatus()).isEqualTo("OPEN");
    assertThat(refund.targetSystem()).isEqualTo("AFTER_SALE");
    assertThat(refund.payload().refundCurrencyCode()).isEqualTo("USD");
    assertThat(refund.idempotencyKey()).startsWith("RF-");
  }

  @Test
  void 退款必须给金额与币种_缺一不可() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.dispose(
                            exId,
                            new DropshipDisposeInput("REQUEST_REFUND", null, null, "退款"),
                            idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("退款金额");
  }

  @Test
  void 处置必须有依据说明() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.dispose(
                            exId, new DropshipDisposeInput("URGE_SUPPLIER", null, null, "  "),
                            idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("处置必须写明依据");
  }

  // ==========================================================================
  // 五、回传平台只到「待办」为止
  // ==========================================================================
  @Test
  void 有运单号才排回传任务_下游受理前不得显示已回传() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    assertThat(tasks(a, id))
        .as("未发货时没有回传任务 —— 拿什么回传？")
        .isEmpty();
    assertThat(trackingRow(a, id).callbackStatus()).isEqualTo("NOT_REQUIRED");

    // 供应商报了发货但没给单号 → 不排回传任务（空转的回传毫无意义）
    asA(
        () ->
            service.recordSupplierLeg(
                id, new DropshipSupplierLegInput("SHIPPED", "", "", hoursAgo(3), hoursAgo(3), null, ""),
                idFor(a, 99)));
    assertThat(tasks(a, id)).isEmpty();
    assertThat(trackingRow(a, id).callbackStatus()).isEqualTo("NOT_REQUIRED");

    // 补上运单号 → 落回传任务，状态 PENDING（**不是**已回传）
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "SHIPPED", "SUP-CARRIER", "SF-CB-1", hoursAgo(3), hoursAgo(3), null, ""),
                idFor(a, 99)));
    var tasks = tasks(a, id);
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).taskKind()).isEqualTo("PLATFORM_CALLBACK");
    assertThat(tasks.get(0).targetSystem()).isEqualTo("CHANNEL");
    assertThat(tasks.get(0).payload().supplierTrackingNo()).isEqualTo("SF-CB-1");
    var pending = trackingRow(a, id);
    assertThat(pending.callbackStatus())
        .as("2.5.1.1 未交付：只能停在待回传，不得出现「已回传」这种假结论")
        .isEqualTo("PENDING");
    assertThat(pending.callbackSentAt()).isNull();

    // 重复录同一张发货回执 → 幂等键去重，不得造出第二条待办
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "IN_TRANSIT", "SUP-CARRIER", "SF-CB-1", hoursAgo(3), hoursAgo(2), null, ""),
                idFor(a, 99)));
    assertThat(tasks(a, id)).as("同一张单的同一个回传只能有一条任务").hasSize(1);

    // 下游领取 → 回执成功 → 才允许标成平台已受理
    UUID taskId = tasks(a, id).get(0).id();
    asA(() -> service.claimTask(taskId, idFor(a, 99)));
    asA(() -> service.completeTask(taskId, new DropshipTaskResultInput(true, null), idFor(a, 99)));
    var done = trackingRow(a, id);
    assertThat(done.callbackStatus()).isEqualTo("ACKNOWLEDGED");
    assertThat(done.callbackSentAt()).isNotNull();
  }

  @Test
  void 回传失败必须写明原因且状态落到失败() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "SHIPPED", "SUP-CARRIER", "SF-CB-2", hoursAgo(3), hoursAgo(3), null, ""),
                idFor(a, 99)));
    UUID taskId = tasks(a, id).get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.completeTask(
                            taskId, new DropshipTaskResultInput(false, ""), idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须写明原因");

    asA(
        () ->
            service.completeTask(
                taskId, new DropshipTaskResultInput(false, "平台接口返回 403 鉴权失败"),
                idFor(a, 99)));
    assertThat(trackingRow(a, id).callbackStatus()).isEqualTo("FAILED");
    assertThat(trackingRow(a, id).callbackLastError()).contains("403");
  }

  // ==========================================================================
  // 六、时间写回正确（timestamptz 不得静默偏 −28800s）
  // ==========================================================================
  @Test
  void 时间往返不发生静默偏移() {
    var f = seedTenant(a);
    OffsetDateTime promised = hoursAgo(5);
    UUID id = newTracking(a, f, promised);
    OffsetDateTime shipped = hoursAgo(4);
    OffsetDateTime lastEvent = hoursAgo(3);
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "SHIPPED", "SUP-CARRIER", "SF-TIME-1", shipped, lastEvent, null, ""),
                idFor(a, 99)));

    var row = trackingRow(a, id);
    assertThat(row.supplierPromisedShipAt().toInstant())
        .as("承诺时限往返后必须还是同一时刻（写入用 ?::timestamptz）")
        .isEqualTo(promised.toInstant());
    assertThat(row.supplierShippedAt().toInstant()).isEqualTo(shipped.toInstant());
    assertThat(row.supplierLastEventAt().toInstant()).isEqualTo(lastEvent.toInstant());
    // 显式排掉「刚好偏 8 小时」这种最典型的 Timestamp.valueOf 症状
    assertThat(row.supplierShippedAt().toInstant().getEpochSecond())
        .isEqualTo(shipped.toInstant().getEpochSecond());
  }

  @Test
  void 处置流转时刻写回正确() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    UUID dId =
        asA(
                () ->
                    service.dispose(
                        exId,
                        new DropshipDisposeInput("URGE_SUPPLIER", null, null, "催单"),
                        idFor(a, 99)))
            .id();
    asA(
        () -> {
          service.changeDispositionStatus(
              dId, new DropshipDispositionStatusInput("SENT", ""), idFor(a, 99));
          return null;
        });
    asA(
        () -> {
          service.changeDispositionStatus(
              dId, new DropshipDispositionStatusInput("ACKNOWLEDGED", ""), idFor(a, 99));
          return null;
        });
    asA(
        () -> {
          service.changeDispositionStatus(
              dId, new DropshipDispositionStatusInput("COMPLETED", "供应商已确认今日发出"),
              idFor(a, 99));
          return null;
        });
    var row =
        asA(
            () ->
                service.pageTrackings(new DropshipTrackingQuery(null, null, null, null, null, null,
                        null, null, 0, 50)).items().get(0));
    assertThat(row).isNotNull();
    var d =
        asA(() -> service.trackingDetail(id)).dispositions().stream()
            .filter(x -> x.id().equals(dId))
            .findFirst()
            .orElseThrow();
    assertThat(d.status()).isEqualTo("COMPLETED");
    assertThat(d.sentAt()).isNotNull();
    assertThat(d.acknowledgedAt()).isNotNull();
    assertThat(d.completedAt()).isNotNull();
    assertThat(d.completedAt().toInstant())
        .as("流转时刻不得比发起时刻早")
        .isAfterOrEqualTo(d.sentAt().toInstant());
    assertThat(d.outcome()).contains("已确认");
  }

  // ==========================================================================
  // 七、DB 级闸门
  // ==========================================================================
  @Test
  void db拒绝两段同时在途() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    asA(
        () ->
            service.recordSupplierLeg(
                id,
                new DropshipSupplierLegInput(
                    "IN_TRANSIT", "SUP-CARRIER", "SF-EX-1", hoursAgo(10), hoursAgo(10), null, ""),
                idFor(a, 99)));
    // 绕开服务层：只把生效段翻到 OWN，供应商段却仍在途 → 必须被 DB 拒绝。
    // （刻意**不**同时把供应商段改掉：那样会被「切换必须留痕」的 CHECK 先拦下，
    //  证明不了「两段不得同时在途」这一条本身。）
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_tracking set active_leg='OWN' where id='"
                        + id
                        + "'")
                .run(),
        "两段同时在途");
  }

  @Test
  void db拒绝异常证据被改写() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_tracking_exception set exception_kind='PARCEL_LOST'"
                        + " where id='"
                        + exId
                        + "'")
                .run(),
        "判定证据不可改写");
  }

  @Test
  void db拒绝删除异常与事件流() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    assertDbRefused(
        () -> asTableOwner(a, "delete from fulfillment_dropship_tracking_exception where id='" + exId + "'").run(),
        "不可删除");
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_tracking_event set detail='篡改' where tracking_id='"
                        + id
                        + "'")
                .run(),
        "只追加");
  }

  @Test
  void db拒绝未给承运商却给出运单号() {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(2));
    assertDbRefused(
        () ->
            asA(
                () -> {
                  sql.execute(
                      "update fulfillment_dropship_tracking set supplier_leg_status='SHIPPED',"
                          + "supplier_tracking_no='SF-NO-CARRIER',supplier_shipped_at=now()"
                          + " where id = ?",
                      id);
                  return null;
                }),
        "dropship_tracking_supplier_carrier_required");
  }

  // ==========================================================================
  // 八、多租户三断言
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var fa = seedTenant(a);
    var fb = seedTenant(b);
    UUID ta = newTracking(a, fa, at(2));
    UUID tb = newTracking(b, fb, at(2));
    evaluate(a);
    evaluate(b);

    var inA = asA(() -> service.pageTrackings(new DropshipTrackingQuery(null, null, null, null, null,
            null, null, null, 0, 50)));
    var inB = asB(() -> service.pageTrackings(new DropshipTrackingQuery(null, null, null, null, null,
            null, null, null, 0, 50)));
    assertThat(inA.total()).isEqualTo(1);
    assertThat(inB.total()).isEqualTo(1);

    assertThatThrownBy(() -> asA(() -> service.trackingDetail(tb)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asB(() -> service.trackingDetail(ta)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");

    // 异常工作台与任务队列同样隔离
    var exB =
        asA(() -> service.pageExceptions(
                new DropshipTrackingExceptionQuery(tb, null, null, null, null, 0, 50)).total());
    assertThat(exB).as("对方租户的异常不可见").isZero();
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    var fa = seedTenant(a);
    UUID ta = newTracking(a, fa, at(2));

    // (1) B 的会话 + 伪造指向 A 的头 → 查不到 A 的跟踪
    http.perform(
            get("/trade/fulfillment/dropship-tracking/trackings/{id}", ta)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // (2) 写入路径同样不认头：B 会话伪造 A 的头，异常检出只作用在 B 的租户上
    http.perform(
            post("/trade/fulfillment/dropship-tracking/exceptions/evaluate")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    var inA = asA(() -> service.pageExceptions(
            new DropshipTrackingExceptionQuery(null, null, null, null, null, 0, 50)).total());
    var inB = asB(() -> service.pageExceptions(
            new DropshipTrackingExceptionQuery(null, null, null, null, null, 0, 50)).total());
    assertThat(inA).as("A 的单未逾期，伪造头不得让 B 的检出写进 A").isZero();
    assertThat(inB).as("B 没有跟踪，伪造头也不得凭空造出异常").isZero();

    // (3) 事务外读不到任何行
    assertThat(sql.fetch("select id from fulfillment_dropship_tracking_exception").size()).isZero();
  }

  @Test
  void 断言3_连接池归还后不串租户() {
    var fa = seedTenant(a);
    var fb = seedTenant(b);
    newTracking(a, fa, at(2));
    newTracking(b, fb, at(2));
    // 池大小为 1：下面每次读写都复用同一条连接。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.pageTrackings(new DropshipTrackingQuery(null, null, null, null, null,
                      null, null, null, 0, 50)).total()))
          .as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(asB(() -> service.pageTrackings(new DropshipTrackingQuery(null, null, null, null, null,
                      null, null, null, 0, 50)).total()))
          .as("第 %d 轮租户 B", i).isEqualTo(1);
      assertThat(sql.fetch("select id from fulfillment_dropship_tracking").size())
          .as("第 %d 轮事务外", i).isZero();
      // 策略也必须跟着租户切换，而不是被上一条连接的 GUC 污染
      assertThat(asA(service::policy).delayToleranceHours()).as("第 %d 轮 A 策略", i).isZero();
      assertThat(asB(service::policy).policyRegistered()).as("第 %d 轮 B 策略", i).isFalse();
    }
  }

  // ==========================================================================
  // 九、HTTP 端点与只读权限
  // ==========================================================================
  @Test
  void 端点真实可用且跨租户404() throws Exception {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);

    http.perform(get("/trade/fulfillment/dropship-tracking/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformCallbackReady").value(false))
        .andExpect(jsonPath("$.refundReady").value(false))
        .andExpect(jsonPath("$.rules.length()").value(3));

    http.perform(get("/trade/fulfillment/dropship-tracking/trackings")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    http.perform(get("/trade/fulfillment/dropship-tracking/exceptions")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].exceptionKind").value("DELAYED_SHIPMENT"));

    // 详情必须把两段都返回出来（前端据此分开画）
    http.perform(get("/trade/fulfillment/dropship-tracking/trackings/{id}", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tracking.supplierLegStatus").value("NOT_SHIPMENT"))
        .andExpect(jsonPath("$.tracking.ownLegStatus").value("NOT_HANDED_OVER"))
        .andExpect(jsonPath("$.tracking.activeLeg").value("SUPPLIER"))
        .andExpect(jsonPath("$.exceptions.length()").value(1))
        .andExpect(jsonPath("$.tasks.length()").value(0));

    http.perform(get("/trade/fulfillment/dropship-tracking/trackings/{id}", id)
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 只读身份不能处置() throws Exception {
    var f = seedTenant(a);
    UUID id = newTracking(a, f, at(-1));
    evaluate(a);
    UUID exId = exceptions(a, id).get(0).id();
    http.perform(
            post("/trade/fulfillment/dropship-tracking/exceptions/{id}/dispose", exId)
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionKind\":\"URGE_SUPPLIER\",\"note\":\"催\"}"))
        .andExpect(status().isForbidden());

    http.perform(post("/trade/fulfillment/dropship-tracking/exceptions/evaluate")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未交付给供应商的代发单不能建跟踪() {
    var f = seedTenant(a);
    // 只建单不提交 → 代发单还是 DRAFT，供应商压根没接到这票货
    UUID orderId =
        as(
            a,
            () ->
                dropshipOrders
                    .createOrder(
                        new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                        idFor(a, 99))
                    .id());
    assertThatThrownBy(
            () ->
                asA(
                    () -> service.createTracking(new DropshipTrackingInput(orderId, at(2), ""),
                        idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没提交给供应商");
  }
}