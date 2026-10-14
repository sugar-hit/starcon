package com.starcon.trade;

import static com.starcon.trade.DropshipOrderModels.*;
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
 * 9.6.1.2 代发单与供应商下单。
 *
 * <p>本测试是 <b>V122 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>五组断言</b>：
 * <ol>
 *   <li><b>差价毛利口径</b>：代发成本 = 采购价 + 供应商运费；差价 = 售价 − 代发成本，
 *       三列都是 DB 生成列，绕开服务层写 SQL 也改不了；</li>
 *   <li><b>涨价必须显式告警</b>（蓝图点名的那一条）：实际采购价高于下单冻结价 →
 *       PRICE_INCREASE，且**差价按实际成本重算**；涨价到差价转负时再落一条
 *       NEGATIVE_SPREAD —— 不重算就永远报不出来，正是蓝图说的静默亏损；</li>
 *   <li><b>未确认收款不许下单</b>：DB 触发器在服务层之外再挡一次；</li>
 *   <li><b>未接线 vendor 不得伪造成功</b>：1688 未登记时显式失败并落 ORDER_FAILED，
 *       绝不返回编造的供应商单号；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DropshipOrderIntegrationTest {
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
        ("starcon-trade-dropship-order-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired DropshipOrderService service;
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
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
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
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work,
      String expectedFragment,
      String... acceptableStates) {
    var error = catchThrowable(work);
    var e = rootSqlException(error);
    assertThat(e.getSQLState())
        .as("必须被 DB 拒绝，实际 SQLState=%s，消息=%s", e.getSQLState(), e.getMessage())
        .isIn(acceptableStates);
    if ("23514".equals(e.getSQLState())) {
      assertThat(e.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
    }
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

  /** 每个用例前清空本模块五张表 + 上游销售订单族。 */
  @AfterEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_order_event,fulfillment_dropship_alert,"
              + " fulfillment_dropship_order_line,fulfillment_dropship_order,"
              + " fulfillment_dropship_supplier_endpoint,"
              + " fulfillment_dropship_config_line,fulfillment_dropship_config,"
              + " order_address_snapshot,sales_order_line,sales_order_party_snapshot,"
              + " sales_order_status_history,sales_order,sales_order cascade");
    }
  }

  /**
   * 一个租户的三层夹具：生效的 9.6.1.1 配置 → 已提交的销售订单（含收货地址）。
   *
   * @param cost 配置供货价（冻结进代发单 purchase_amount）
   * @param sellPrice 销售订单行单价（冻结进代发单 sell_amount）
   * @param qty 行数量
   */
  record Fixture(UUID configId, UUID salesOrderId) {}

  Fixture seedTenant(UUID tenant, String cost, String sellPrice, String qty) {
    return seedTenant(tenant, cost, sellPrice, qty, true);
  }

  /**
   * @param submitted 销售订单是否已提交。草稿单（{@code false}）用来验证
   *     「买家还没下单的单不能向供应商采购」—— 注意 V99 的触发器
   *     {@code sales_order_submitted_terminal} **不允许**把已提交的订单退回草稿，
   *     所以这条用例必须从头就种成草稿，而不是改状态。
   */
  Fixture seedTenant(UUID tenant, String cost, String sellPrice, String qty, boolean submitted) {
    return as(
        tenant,
        () -> {
          // 9.6.1.1：纯代理 + 供应商直发配置（必须 ACTIVE 且在生效期内）
          UUID configId = idFor(tenant, 11);
          sql.execute(
              "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
                  + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
                  + "ownership_reviewed_with,settlement_timing,settlement_basis,currency_code,"
                  + "supplier_cost,reference_sell_price,availability_check_mode,listing_policy,"
                  + "priority,status,effective_from,activated_at,note)"
                  + " values (?,?,'DSH-ORD-1','代发单用配置','SHOP','SHOP-US-1','',"
                  + "'SUPPLIER_DIRECT','SUP-ACME','PURE_AGENCY','','ON_RECEIPT','FLAT_COST','USD',"
                  + "?,?,'SKIP','ALWAYS_LIST',10,'ACTIVE',?::timestamptz,?::timestamptz,'')",
              configId, tenant, new BigDecimal(cost), new BigDecimal(sellPrice), at(-1), at(-1));
          sql.execute(
              "insert into fulfillment_dropship_config_line(id,tenant_id,config_id,sku_ref,"
                  + "sku_name,active) values (?,?,?,'SKU-A','测试商品',true)",
              idFor(tenant, 12), tenant, configId);

          // 9.1：已提交的销售订单（status 必须 SUBMITTED 才允许建代发单）
          UUID orderId = idFor(tenant, 21);
          BigDecimal unitPrice = new BigDecimal(sellPrice);
          BigDecimal quantity = new BigDecimal(qty);
          BigDecimal lineAmount = unitPrice.multiply(quantity);
          if (submitted) {
            sql.execute(
                "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,currency,"
                    + "payment_method,status,submitted_at,subtotal_amount,grand_total_amount)"
                    + " values (?,?,'SO-TEST-1','MANUAL','DROPSHIP','USD','PREPAID','SUBMITTED',"
                    + "?::timestamptz,?,?)",
                orderId, tenant, at(-1), lineAmount, lineAmount);
          } else {
            sql.execute(
                "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,currency,"
                    + "payment_method,status,subtotal_amount,grand_total_amount)"
                    + " values (?,?,'SO-TEST-1','MANUAL','DROPSHIP','USD','PREPAID','DRAFT',?,?)",
                orderId, tenant, lineAmount, lineAmount);
          }
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,line_amount)"
                  + " values (?,?,?,1,?,'SKU-A','测试商品',?,?,?)",
              idFor(tenant, 22), tenant, orderId, idFor(tenant, 23), quantity, unitPrice,
              lineAmount);
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

  /** 建一张 MANUAL 代发单（DRAFT 态）。 */
  UUID newOrder(UUID tenant, Fixture f) {
    return as(
        tenant,
        () -> {
          var saved =
              service.createOrder(
                  new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(tenant, 99));
          return saved.id();
        });
  }

  /** 建单 → 确认收款（到 READY）。 */
  UUID readyOrder(UUID tenant, Fixture f) {
    return as(
        tenant,
        () -> {
          var saved =
              service.createOrder(
                  new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(tenant, 99));
          service.confirmPayment(
              saved.id(),
              new DropshipPaymentConfirmInput(at(0), idFor(tenant, 99), "银行流水号 BR-2026-001"),
              idFor(tenant, 99));
          return saved.id();
        });
  }

  DropshipOrderRow order(UUID tenant, UUID orderId) {
    return as(tenant, () -> service.orderDetail(orderId).order());
  }

  List<DropshipAlertRow> alerts(UUID tenant, UUID orderId) {
    return as(
        tenant,
        () ->
            service.pageAlerts(
                    new DropshipAlertQuery(orderId, null, null, null, null, 0, 50))
                .items());
  }

  // ==========================================================================
  // 迁移落地与 RLS 强制
  // ==========================================================================
  @Test
  void 迁移落地且五张表全部强制rls() {
    for (var table :
        List.of(
            "fulfillment_dropship_order",
            "fulfillment_dropship_order_line",
            "fulfillment_dropship_supplier_endpoint",
            "fulfillment_dropship_alert",
            "fulfillment_dropship_order_event")) {
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
    // 用行为证明 FORCE RLS 生效：**事务外**（没有 app.current_tenant）
    // 连表所有者都读不到任何一行，更别说写。
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    newOrder(a, f);
    assertThat(sql.fetch("select id from fulfillment_dropship_order").size())
        .as("事务外必须读不到任何行（FORCE RLS 对表所有者同样生效）")
        .isZero();
    // 绑了租户上下文才看得到自己的行 —— 与上一行同一张表、同一连接池，只差一个 GUC
    var seen =
        as(
            a,
            () ->
                sql.fetchOne(
                    "select count(*) c from fulfillment_dropship_order_event"));
    assertThat(seen.get("c", Long.class)).as("绑了租户才看得到行").isEqualTo(1L);
  }

  // ==========================================================================
  // 一、差价毛利口径（生成列唯一真相）
  // ==========================================================================
  @Test
  void 差价等于售价减采购价与供应商运费() {
    var f = seedTenant(a, "10.0000", "25.0000", "2.000000");
    UUID orderId = newOrder(a, f);

    var row = order(a, orderId);
    // 售价 25 × 2 = 50；采购价 10 × 2 = 20；供应商运费 0
    assertThat(row.sellAmount()).isEqualByComparingTo("50.0000");
    assertThat(row.purchaseAmount()).isEqualByComparingTo("20.0000");
    assertThat(row.dropshipCost()).isEqualByComparingTo("20.0000");
    assertThat(row.spreadAmount()).isEqualByComparingTo("30.0000");
    assertThat(row.marginRate()).isEqualByComparingTo("0.600000");
    // 「还没回执」是 null，不是 0
    assertThat(row.actualPurchaseAmount()).isNull();
  }

  @Test
  void 差价转负不被拦但必须为负() {
    // 售价 5 × 1 = 5，采购价 10 × 1 = 10 → 差价 -5（亏损单照样要能录进来）
    var f = seedTenant(a, "10.0000", "5.0000", "1.000000");
    UUID orderId = newOrder(a, f);

    var row = order(a, orderId);
    assertThat(row.spreadAmount()).isEqualByComparingTo("-5.0000");
    assertThat(row.spreadAmount()).as("亏损单必须真的建出来，否则录不进真实亏损").isNegative();
  }

  @Test
  void 金额是生成列应用层改不了() {
    var f = seedTenant(a, "10.0000", "25.0000", "2.000000");
    UUID orderId = newOrder(a, f);
    // 绕开服务层直接写 SQL 也改不了三个生成列
    assertThatThrownBy(
            () ->
                asTableOwner(
                        a,
                        "update fulfillment_dropship_order set spread_amount = 999"
                            + " where order_no = '"
                            + order(a, orderId).orderNo()
                            + "'")
                    .run())
        .as("spread_amount 是生成列")
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                asTableOwner(
                        a,
                        "update fulfillment_dropship_order set dropship_cost = 0"
                            + " where order_no = '"
                            + order(a, orderId).orderNo()
                            + "'")
                    .run())
        .as("dropship_cost 是生成列")
        .isInstanceOf(Exception.class);
  }

  @Test
  void 头金额必须等于行金额之和() {
    var f = seedTenant(a, "10.0000", "25.0000", "2.000000");
    UUID orderId = newOrder(a, f);
    String orderNo = order(a, orderId).orderNo();
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_order set sell_amount = 999 where order_no = '"
                        + orderNo
                        + "'")
                .run(),
        "金额与行不符",
        "23514");
  }

  // ==========================================================================
  // 二、涨价必须显式告警（蓝图点名的那一条）
  // ==========================================================================
  @Test
  void 供应商涨价落显式告警且差价按实际重算() {
    // 冻结采购价 10 × 2 = 20，售价 50 → 冻结差价 +30
    var f = seedTenant(a, "10.0000", "25.0000", "2.000000");
    UUID orderId = as(
        a,
        () -> {
          var saved =
              service.createOrder(
                  new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(a, 99));
          service.confirmPayment(
              saved.id(),
              new DropshipPaymentConfirmInput(at(0), idFor(a, 99), "银行流水号 BR-1"),
              idFor(a, 99));
          service.dispatch(saved.id(), idFor(a, 99));
          // 供应商实际按 13 结算（多 6），运费 2
          service.recordSupplierAck(
              saved.id(),
              new DropshipSupplierAckInput(
                  "SO-EXT-1", new BigDecimal("26.0000"), new BigDecimal("2.0000"), "", "回执"),
              idFor(a, 99));
          return saved.id();
        });

    var rows = alerts(a, orderId);
    assertThat(rows).as("涨价必须显式告警，不能静默").isNotEmpty();
    var priceAlert =
        rows.stream().filter(x -> "PRICE_INCREASE".equals(x.alertKind())).findFirst().orElseThrow();
    // 比的是**下单时冻结的价**（20），不是实时配置价
    assertThat(priceAlert.expectedAmount()).isEqualByComparingTo("20.0000");
    assertThat(priceAlert.actualAmount()).isEqualByComparingTo("26.0000");
    assertThat(priceAlert.detail()).contains("高于下单时的冻结价");

    // 差价按**实际**成本重算：50 − (26 + 2) = 22 > 0 → 不该报亏损
    assertThat(rows.stream().noneMatch(x -> "NEGATIVE_SPREAD".equals(x.alertKind())))
        .as("差价仍为正，不该报亏损")
        .isTrue();
  }

  @Test
  void 涨到差价转负必须再落一条亏损告警() {
    // 冻结采购价 10 × 1 = 10，售价 20 → 冻结差价 +10
    var f = seedTenant(a, "10.0000", "20.0000", "1.000000");
    UUID orderId = as(
        a,
        () -> {
          var saved =
              service.createOrder(
                  new DropshipOrderInput(f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(a, 99));
          service.confirmPayment(
              saved.id(),
              new DropshipPaymentConfirmInput(at(0), idFor(a, 99), "银行流水号 BR-1"),
              idFor(a, 99));
          service.dispatch(saved.id(), idFor(a, 99));
          // 供应商实际按 25 结算 → 20 − 25 = −5，差价由正转负
          service.recordSupplierAck(
              saved.id(),
              new DropshipSupplierAckInput(
                  "SO-EXT-2", new BigDecimal("25.0000"), new BigDecimal("0.0000"), "", "回执"),
              idFor(a, 99));
          return saved.id();
        });

    var rows = alerts(a, orderId);
    assertThat(rows.stream().anyMatch(x -> "PRICE_INCREASE".equals(x.alertKind())))
        .as("涨价本身要告警")
        .isTrue();
    var loss =
        rows.stream().filter(x -> "NEGATIVE_SPREAD".equals(x.alertKind())).findFirst().orElseThrow();
    assertThat(loss.severity()).isEqualTo("CRITICAL");
    assertThat(loss.actualAmount()).isEqualByComparingTo("-5.0000");
    assertThat(loss.detail()).as("必须写清是按实际成本重算的").contains("按供应商实际成本重算");
  }

  @Test
  void 人工置失败落失败告警() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    as(
        a,
        () -> {
          service.markFailed(
              orderId,
              new DropshipFailureInput("供应商回复该 SKU 已断货，拒绝接单"),
              idFor(a, 99));
          return null;
        });
    var rows = alerts(a, orderId);
    assertThat(rows.stream().anyMatch(x -> "ORDER_FAILED".equals(x.alertKind())))
        .as("失败必须显式告警")
        .isTrue();
    assertThat(rows.stream().anyMatch(x -> x.detail().contains("供应商回复该 SKU 已断货")))
        .as("失败原因必须可处置，不能只有「失败」两个字")
        .isTrue();
  }

  @Test
  void 告警不可改写也不可删除() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    as(
        a,
        () -> {
          service.markFailed(orderId, new DropshipFailureInput("供应商拒单"), idFor(a, 99));
          return null;
        });
    var alertId = alerts(a, orderId).get(0).id();
    // **判定证据**不可改写：UPDATE 权限是有的（处置要改 status），
    // 所以这里必须靠触发器逐字段挡住，改写判定数值要以 23514 失败。
    assertDbRefused(
        () -> asTableOwner(a, "update fulfillment_dropship_alert set actual_amount = 0 where id = '" + alertId + "'").run(),
        "不可改写",
        "23514");
    assertDbRefused(
        () -> asTableOwner(a, "update fulfillment_dropship_alert set alert_kind = 'NEGATIVE_SPREAD' where id = '" + alertId + "'").run(),
        "不可改写",
        "23514");
    // 但**处置字段**是允许改的（否则「已处置」无处落地）：
    // CHECK 要求处置必须带 acknowledged_at + 处置人 + 处置说明三件套。
    assertThat(
            as(
                a,
                () ->
                    sql.fetchOne(
                        "update fulfillment_dropship_alert set status = 'RESOLVED',"
                            + " acknowledged_at = now(),"
                            + " acknowledged_by_membership_id = '"
                            + idFor(a, 99)
                            + "', resolution_note = '已换供应商重下'"
                            + " where id = '"
                            + alertId
                            + "' returning id")))
        .as("处置字段必须可写")
        .isNotNull();
    // 删除被**触发器**挡住（表所有者也过不了这一层）→ 23514。
    // REVOKE DELETE 是给运行角色 trade_app 的第二层，两层都在。
    assertDbRefused(
        () -> asTableOwner(a, "delete from fulfillment_dropship_alert where id = '" + alertId + "'").run(),
        "不可删除",
        "23514");
  }

  @Test
  void 告警处置留痕且不删历史() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    as(
        a,
        () -> {
          service.markFailed(orderId, new DropshipFailureInput("供应商拒单"), idFor(a, 99));
          return null;
        });
    var before = alerts(a, orderId).get(0);
    var after =
        as(
            a,
            () ->
                service.acknowledgeAlert(
                    before.id(),
                    new DropshipAlertAckInput("已换供应商重下，并调高售价")));
    assertThat(after.status()).isEqualTo("RESOLVED");
    assertThat(after.resolutionNote()).isNotBlank();
    assertThat(after.acknowledgedAt()).as("处置必须留时刻").isNotNull();
    assertThat(alerts(a, orderId)).as("处置不删历史行").hasSize(1);
  }

  // ==========================================================================
  // 三、未确认收款不许下单（服务层 + DB 两层）
  // ==========================================================================
  @Test
  void 未确认收款不能进已提交态() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = newOrder(a, f);
    assertThatThrownBy(() -> as(a, () -> service.dispatch(orderId, idFor(a, 99))))
        .hasMessageContaining("READY");
  }

  @Test
  void 绕开服务层也提交不了未确认收款的单() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = newOrder(a, f);
    String orderNo = order(a, orderId).orderNo();
    // 强行绕过服务层直接改状态：DB 触发器必须挡住
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_order set status = 'DISPATCHED'"
                        + " where order_no = '"
                        + orderNo
                        + "'")
                .run(),
        "未确认收款",
        "23514");
  }

  @Test
  void 收款确认必须带主体与依据() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = newOrder(a, f);
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.confirmPayment(
                            orderId,
                            new DropshipPaymentConfirmInput(at(0), idFor(a, 99), "  "),
                            idFor(a, 99))))
        .as("空依据等于没凭据")
        .hasMessageContaining("必须写明确认依据");
  }

  @Test
  void 确认收款时刻按写回的值断言() {
    // 专门钉住 timestamptz 绑定：写成 Timestamp.valueOf 会静默偏 −28800s
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = newOrder(a, f);
    OffsetDateTime confirmedAt = at(0);
    as(
        a,
        () -> {
          service.confirmPayment(
              orderId, new DropshipPaymentConfirmInput(confirmedAt, idFor(a, 99), "流水 BR-9"),
              idFor(a, 99));
          return null;
        });
    var row = order(a, orderId);
    assertThat(row.paymentConfirmedAt()).isNotNull();
    assertThat(row.paymentConfirmedAt().toInstant())
        .as("写回的时刻必须与提交的时刻同一瞬间（时区偏移不对会被这里抓到）")
        .isEqualTo(confirmedAt.toInstant());
    assertThat(row.paymentConfirmedSource()).isEqualTo("MANUAL_ATTESTATION");
  }

  // ==========================================================================
  // 四、未接线 vendor 不得伪造成功
  // ==========================================================================
  @Test
  void 能力声明如实报出1688未接线状态() {
    var caps = as(a, () -> service.capabilities());
    var alibaba =
        caps.dimensions().stream()
            .filter(d -> "VENDOR_1688".equals(d.code()))
            .findFirst()
            .orElseThrow();
    assertThat(alibaba.available())
        .as("仓库里没有 1688 的接入信息，必须报未接线而不是报可用")
        .isFalse();
    // 人工录入这条路径是通的
    assertThat(caps.vendors()).anyMatch(v -> "MANUAL_ENTRY".equals(v.vendorCode()) && v.wired());
    // 支付状态必须显式报不可用（9.1 无支付状态列，12 财务未交付）
    var payment =
        caps.dimensions().stream().filter(d -> "PAYMENT".equals(d.code())).findFirst().orElseThrow();
    assertThat(payment.available()).isFalse();
    assertThat(payment.ownerModule()).isNotBlank();
    assertThat(caps.paymentStatusReadable()).isFalse();
  }

  @Test
  void vendor_1688未接线时显式失败而不是伪造单号() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    // 建成 AUTO + 1688 的单
    UUID orderId =
        as(
            a,
            () -> {
              var saved =
                  service.createOrder(
                      new DropshipOrderInput(
                          f.salesOrderId(), f.configId(), "AUTO", "ALIBABA_1688", ""),
                      idFor(a, 99));
              service.confirmPayment(
                  saved.id(),
                  new DropshipPaymentConfirmInput(at(0), idFor(a, 99), "流水 BR-1688"),
                  idFor(a, 99));
              return saved.id();
            });
    // 刻意**不**断言抛异常：@Transactional 下抛异常会把刚写的 FAILED 与告警一起回滚，
    // 而那正是蓝图要求「必须显式」的东西。所以失败以单据状态 + 告警的形式返回。
    as(a, () -> service.dispatch(orderId, idFor(a, 99)));

    var row = order(a, orderId);
    assertThat(row.status()).as("未接线必须显式失败").isEqualTo("FAILED");
    assertThat(row.supplierOrderNo())
        .as("绝不能编造一个供应商单号")
        .isEmpty();
    var failed = alerts(a, orderId).stream()
        .filter(x -> "ORDER_FAILED".equals(x.alertKind()))
        .findFirst()
        .orElseThrow();
    assertThat(failed.detail())
        .as("失败原因必须写明是「未接线」，否则运营会去查一个根本没调过的供应商")
        .contains("尚未接线");
  }

  @Test
  void 声称已接线必须给出baseUrl() {
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.saveEndpoint(
                            new DropshipEndpointInput(
                                "ALIBABA_1688", "1688 开放平台", "", "secret/ref", true, ""))))
        .as("声称可用却调不通，比明确报未接线更糟")
        .hasMessageContaining("baseUrl");
  }

  @Test
  void 端点登记不接受明文凭证() {
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.saveEndpoint(
                            new DropshipEndpointInput(
                                "ALIBABA_1688",
                                "1688",
                                "https://gw.example",
                                "appKey=abc123secret",
                                false,
                                ""))))
        .as("凭证明文一律不入库")
        .hasMessageContaining("不接受 key=value 形态的明文凭证");
  }

  @Test
  void 人工路径转待录回执且供应商单号留空() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    as(a, () -> service.dispatch(orderId, idFor(a, 99)));
    var row = order(a, orderId);
    assertThat(row.status()).isEqualTo("DISPATCHED");
    assertThat(row.supplierOrderNo())
        .as("人工路径下还没回执，单号必须空而不是填 0 或占位")
        .isEmpty();
  }

  @Test
  void 回执与发货都要真实单号() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    // 先提交供应商（→ DISPATCHED），否则先撞「状态不对」而不是「单号为空」
    as(a, () -> service.dispatch(orderId, idFor(a, 99)));
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.recordSupplierAck(
                            orderId,
                            new DropshipSupplierAckInput(
                                "  ", new BigDecimal("10.0000"), new BigDecimal("0.0000"), "", ""),
                            idFor(a, 99))))
        .as("没有供应商单号就对不上账")
        .hasMessageContaining("供应商单号");

    as(
        a,
        () -> {
          service.recordSupplierAck(
              orderId,
              new DropshipSupplierAckInput(
                  "SO-EXT-9", new BigDecimal("10.0000"), new BigDecimal("0.0000"), "", ""),
              idFor(a, 99));
          return null;
        });
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.recordShipment(
                            orderId, new DropshipShipmentInput(" ", at(0)), idFor(a, 99))))
        .as("没有运单号，10 物流与 9.6.1.3 无从接这一段")
        .hasMessageContaining("运单号");
  }

  @Test
  void 状态机不许跳步() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    String orderNo = order(a, orderId).orderNo();
    // READY 直接跳 SHIPPED：既没提交供应商也没受理，就想发货
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_order set status = 'SHIPPED',"
                        + " supplier_order_no = 'X', supplier_tracking_no = 'T1',"
                        + " supplier_shipped_at = now() where order_no = '"
                        + orderNo
                        + "'")
                .run(),
        "cannot move",
        "23514");
  }

  @Test
  void 草稿订单不许建代发单() {
    // 从头种成草稿：V99 的 sales_order_submitted_terminal 不允许已提交的订单退回草稿
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000", false);
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.createOrder(
                            new DropshipOrderInput(
                                f.salesOrderId(), f.configId(), "MANUAL", "MANUAL_ENTRY", ""),
                            idFor(a, 99))))
        .hasMessageContaining("草稿");
  }

  @Test
  void 自营发货配置不许建代发单() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    // 自营发货配置**另建一份**（而不是把已激活的代发配置改掉）：
    // V118 的 dropship_ownership_clear_on_change 在改这九个货权/结算维度的同一条
    // UPDATE 里先清空确认，confirmed 随即拒绝 —— 那是 9.6.1.1 的既定纪律，
    // 本模块不去绕它，改为按「新建一份自营配置」的真实路径来测。
    UUID selfConfig =
        as(
            a,
            () -> {
              sql.execute(
                  "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
                      + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
                      + "ownership_confirmed_at,ownership_confirmed_by_membership_id,"
                      + "ownership_confirmed_note,ownership_reviewed_with,settlement_timing,"
                      + "settlement_basis,currency_code,supplier_cost,reference_sell_price,"
                      + "availability_check_mode,listing_policy,priority,status,effective_from,"
                      + "activated_at,note)"
                      + " values (?,?,'DSH-SELF-1','自营发货配置','SHOP','SHOP-US-1','',"
                      + "'SELF_FULFILLED','','OUR_ACCOUNT',?::timestamptz,?,"
                      + "'自营发货，货权当然在我方','7.7','ON_RECEIPT','FLAT_COST','USD',"
                      + "10.0000,25.0000,'SKIP','ALWAYS_LIST',10,'ACTIVE',?::timestamptz,"
                      + "?::timestamptz,'')",
                  idFor(a, 31), a, at(-1), idFor(a, 99), at(-1), at(-1));
              return idFor(a, 31);
            });
    assertThatThrownBy(
            () ->
                as(
                    a,
                    () ->
                        service.createOrder(
                            new DropshipOrderInput(
                                f.salesOrderId(), selfConfig, "MANUAL", "MANUAL_ENTRY", ""),
                            idFor(a, 99))))
        .as("自营发货不存在「向供应商下单」这回事")
        .hasMessageContaining("自营发货");
  }

  @Test
  void 事件流记录关键节点() {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderId = readyOrder(a, f);
    var detail = as(a, () -> service.orderDetail(orderId).events());
    assertThat(detail.stream().map(DropshipOrderEventRow::eventKind))
        .contains("CREATED", "PAYMENT_CONFIRMED");
  }

  // ==========================================================================
  // 五、多租户：断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var fa = seedTenant(a, "10.0000", "25.0000", "1.000000");
    var fb = seedTenant(b, "10.0000", "25.0000", "1.000000");
    UUID orderA = newOrder(a, fa);
    UUID orderB = newOrder(b, fb);

    var inA = asA(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50)));
    var inB = asB(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(inA.total()).isEqualTo(1);
    assertThat(inB.total()).isEqualTo(1);
    // 按主键直查对方租户的行也拿不到（不只是列表过滤）
    assertThatThrownBy(() -> asA(() -> service.orderDetail(orderB)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asB(() -> service.orderDetail(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
    // 告警工作台同样隔离
    assertThat(asA(() -> service.pageAlerts(new DropshipAlertQuery(orderB, null, null, null, null, 0, 50)).total()))
        .as("对方租户的告警不可见")
        .isZero();
  }

  // ==========================================================================
  // 五、多租户：断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效() throws Exception {
    var fa = seedTenant(a, "10.0000", "25.0000", "1.000000");
    UUID orderA = newOrder(a, fa);

    // (1) B 的会话 + 伪造指向 A 的头 → 查不到 A 的代发单
    http.perform(
            get("/trade/fulfillment/dropship-orders/orders/{id}", orderA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // (2) 写入路径同样不认头：B 会话伪造 A 的头，代发单仍落进 B 的库
    //     （B 没有上游订单与配置，所以必然失败 —— 关键是它不会写进 A）
    http.perform(
            post("/trade/fulfillment/dropship-orders/orders")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"salesOrderId":"%s","configId":"%s","dispatchMode":"MANUAL",
                     "vendorCode":"MANUAL_ENTRY","note":"伪造头写入"}"""
                        .formatted(fa.salesOrderId(), fa.configId())))
        .andExpect(status().is4xxClientError());

    var inA = asA(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50)));
    var inB = asB(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(inA.total()).as("伪造头不得把数据写进别的租户").isEqualTo(1);
    assertThat(inB.total()).as("B 的写入失败，不得落到 A").isZero();

    // (3) 提交后租户上下文必须已清空：事务外读不到任何行
    assertThat(sql.fetch("select id from fulfillment_dropship_order").size()).isZero();
  }

  // ==========================================================================
  // 五、多租户：断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    var fa = seedTenant(a, "10.0000", "25.0000", "1.000000");
    var fb = seedTenant(b, "10.0000", "25.0000", "1.000000");
    newOrder(a, fa);
    newOrder(b, fb);
    // 池大小为 1：下面每次读写都复用同一条连接。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50))).total())
          .as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(asB(() -> service.pageOrders(new DropshipOrderQuery(null, null, null, null, null, null, null, 0, 50))).total())
          .as("第 %d 轮租户 B", i).isEqualTo(1);
      assertThat(sql.fetch("select id from fulfillment_dropship_order").size())
          .as("第 %d 轮事务外", i).isZero();
    }
  }

  // ==========================================================================
  // 六、只读权限不得写入
  // ==========================================================================
  @Test
  void 只读身份不能建单() throws Exception {
    var f = seedTenant(a, "10.0000", "25.0000", "1.000000");
    http.perform(
            post("/trade/fulfillment/dropship-orders/orders")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"salesOrderId":"%s","configId":"%s","dispatchMode":"MANUAL",
                     "vendorCode":"MANUAL_ENTRY"}"""
                        .formatted(f.salesOrderId(), f.configId())))
        .andExpect(status().isForbidden());
  }
}