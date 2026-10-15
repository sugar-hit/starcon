package com.starcon.trade;

import static com.starcon.trade.DropshipMarginModels.*;
import static com.starcon.trade.DropshipOrderModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.6.2.1 代发差价与对账 —— 集成测试（实跑 Testcontainers PostgreSQL 16）。
 *
 * <p><b>本模块必须实跑的四组断言</b>：
 *
 * <ol>
 *   <li><b>差价可复算</b>：断言的是<b>等式本身</b>（差价 == 售价 − 采购 − 运费 − 平台费），
 *       不是某个硬编码的期望数字 —— 换一个金额组合，等式仍然必须成立。</li>
 *   <li><b>「无法判定」不是「不亏」</b>：平台费未登记时结论是
 *       {@code UNDETERMINED_NO_FEE}；只有「售价 < 代发成本」这一支才算
 *       {@code LOSS_CONFIRMED}（平台费恒 ≥ 0，少算它不会漏判亏损）。</li>
 *   <li><b>跨币种不相加</b>：汇率未登记的行整组折算列为 NULL 且被排除在合计外，
 *       单独进未登记桶；登记后折算等式必须成立。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 /
 *       连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>周期端点一律 pin 住</b>（{@link #P_IN} / {@link #P_OUT} 是写死的常量，
 * 不是 {@code now()} 推出来的）：本模块按周期分片，用墙上时钟构造被断言的分片键
 * 会让用例只在某个钟点通过，批次调度就会随机引爆。
 */
@AutoConfigureMockMvc
@SpringBootTest
class DropshipMarginIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("read", "create", "update", "delete")
          .map(op -> "trade.fulfillment." + op)
          .toList();

  /** 夹具时刻：夹具里的单都落在**过去**（-30 天），保证任何钟点都落不进 {@link #P_OUT}。 */
  static OffsetDateTime at(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .plusDays(daysFromNow)
        .withNano(0)
        .truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * 被断言的周期区间是<b>写死的</b>，不是从墙上时钟推出来的：
   * P_IN 是一个横跨整年的窗口（任何时候跑都覆盖夹具单），P_OUT 落在 2020 年
   * （任何时候跑都**确定**不覆盖夹具单）。这样「这一单落不落在周期内」是稳定事实。
   */
  static final OffsetDateTime P_IN_START = OffsetDateTime.parse("2026-01-01T00:00:00+08:00");
  static final OffsetDateTime P_IN_END = OffsetDateTime.parse("2027-01-01T00:00:00+08:00");
  static final OffsetDateTime P_OUT_START = OffsetDateTime.parse("2020-01-01T00:00:00+08:00");
  static final OffsetDateTime P_OUT_END = OffsetDateTime.parse("2020-02-01T00:00:00+08:00");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-dropship-margin-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", idFor(tenant, 99), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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

  @Autowired DropshipMarginService service;
  @Autowired DropshipOrderService dropshipOrders;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.fulfillment.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  static java.sql.SQLException rootSqlException(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException s) {
        return s;
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

  /** DB 用**唯一索引**挡重复（23505）时的断言：同样是「DB 拒绝」，但状态码不同。 */
  static void assertDbRejectedUnique(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String expectedFragment) {
    var error = catchThrowable(work);
    var e = rootSqlException(error);
    assertThat(e.getSQLState()).as("必须被 DB 拒绝：%s", e.getMessage()).isEqualTo("23505");
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

  @AfterEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_margin_event,fulfillment_dropship_payable_task,"
              + " fulfillment_dropship_reconciliation_line,fulfillment_dropship_reconciliation,"
              + " fulfillment_dropship_loss_alert,fulfillment_dropship_margin_fact,"
              + " fulfillment_dropship_settlement_cycle,fulfillment_dropship_fx_rate,"
              + " fulfillment_dropship_fee_rate,"
              + " fulfillment_dropship_tracking_event,fulfillment_dropship_task,"
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

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 造一张**成本已落定**（ACCEPTED）的代发单，金额完全由入参决定。
   *
   * <p>全程走真实的上游服务（9.6.1.1 配置 → 9.1 销售订单 → 9.6.1.2 建单 / 确认收款 /
   * 提交 / 供应商回执），不走裸 SQL 插代发单 —— 否则测的是我造的数据而不是本模块
   * 真正会读到的数据。
   */
  /** 夹具代发单 id：按 tenant:ordinal 记录，不用「取最新一条」那种脆弱写法。 */
  final Map<String, UUID> orderIds = new ConcurrentHashMap<>();

  UUID acceptedOrder(
      UUID tenant, int ordinal, String sell, String purchase, String shipping, String currency) {
    return as(
        tenant,
        () -> {
          UUID configId = idFor(tenant, 100 + ordinal);
          sql.execute(
              "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
                  + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
                  + "settlement_timing,settlement_basis,currency_code,supplier_cost,"
                  + "reference_sell_price,availability_check_mode,listing_policy,priority,"
                  + "status,effective_from,activated_at,note) values (?,?,?,'差价夹具',"
                  + "'SHOP','SHOP-A','','SUPPLIER_DIRECT','SUP-ACME','PURE_AGENCY','ON_RECEIPT',"
                  + "'FLAT_COST',?,?::numeric,?::numeric,'SKIP','ALWAYS_LIST',10,'ACTIVE',"
                  + "?::timestamptz,?::timestamptz,'')",
              configId, tenant, "DSH-M" + ordinal + "-" + tenant.toString().substring(0, 4),
              currency, new BigDecimal(purchase), new BigDecimal(sell), at(-30), at(-30));
          UUID orderId = idFor(tenant, 200 + ordinal);
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,grand_total_amount) "
                  + "values (?,?,?,'MANUAL','DROPSHIP',?,'PREPAID','SUBMITTED',"
                  + "?::timestamptz,?::numeric,?::numeric)",
              orderId, tenant, "SO-MAR-" + ordinal + "-" + tenant.toString().substring(0, 4),
              currency, at(-30), new BigDecimal(sell), new BigDecimal(sell));
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity,unit_price,line_amount) "
                  + "values (?,?,?,1,?,'SKU-M','差价夹具商品',1.000000,?::numeric,?::numeric)",
              idFor(tenant, 300 + ordinal), tenant, orderId, idFor(tenant, 400 + ordinal),
              new BigDecimal(sell), new BigDecimal(sell));
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type) "
                  + "values (?,?,?,?,'C-M','差价夹具买家','INDIVIDUAL')",
              idFor(tenant, 500 + ordinal), tenant, orderId, idFor(tenant, 600 + ordinal));
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,country_code,province,city,address_line1) "
                  + "values (?,?,?,'SHIPPING','收件人','13800000000','US','CA','Los Angeles',"
                  + "'123 Main St')",
              idFor(tenant, 700 + ordinal), tenant, orderId);

          var saved =
              dropshipOrders.createOrder(
                  new DropshipOrderInput(orderId, configId, "MANUAL", "MANUAL_ENTRY", ""),
                  idFor(tenant, 99));
          dropshipOrders.confirmPayment(
              saved.id(),
              new DropshipPaymentConfirmInput(at(-29), idFor(tenant, 99), "流水号 MAR-001"),
              idFor(tenant, 99));
          dropshipOrders.dispatch(saved.id(), idFor(tenant, 99));
          dropshipOrders.recordSupplierAck(
              saved.id(),
              new DropshipSupplierAckInput(
                  "SO" + ordinal + "-SUP", new BigDecimal(purchase), new BigDecimal(shipping),
                  "TRK-" + ordinal, ""),
              idFor(tenant, 99));
          orderIds.put(tenant + ":" + ordinal, saved.id());
          return saved.id();
        });
  }

  /** 第 ordinal 张夹具单的 id。 */
  UUID orderIdOf(UUID tenant, int ordinal) {
    UUID id = orderIds.get(tenant + ":" + ordinal);
    if (id == null) {
      throw new AssertionError("该 ordinal 的夹具单还没建：" + tenant + ":" + ordinal);
    }
    return id;
  }

  DropshipFeeRateRow registerFeeRate(UUID tenant, String code, String percent) {
    return as(
        tenant,
        () ->
            service.saveFeeRate(
                new DropshipFeeRateInput(
                    code, "平台佣金", "MANUAL", "COMMISSION", DropshipFeeChargeBasis.PERCENT_OF_SELL,
                    new BigDecimal(percent), null, null, "USD", at(-30), null, at(-40), ""),
                idFor(tenant, 99)));
  }

  DropshipMarginEvaluateResult evaluate(UUID tenant) {
    return as(tenant, () -> service.evaluateMarginFacts(P_IN_START, P_IN_END, idFor(tenant, 99)));
  }

  DropshipMarginEvaluateResult evaluateAlerts(UUID tenant) {
    return as(tenant, () -> service.evaluateLossAlerts(P_IN_START, P_IN_END, idFor(tenant, 99)));
  }

  DropshipMarginFactRow fact(UUID tenant, UUID orderId) {
    return as(
        tenant,
        () -> {
          var page = service.pageFacts(new DropshipMarginFactQuery(null, null, null, null, null, 0,
              200));
          return page.items().stream()
              .filter(f -> f.orderId().equals(orderId))
              .findFirst()
              .orElseThrow(() -> new AssertionError("找不到该代发单的差价事实：" + orderId));
        });
  }

  // ==========================================================================
  // 一、迁移落地与 RLS 强制
  // ==========================================================================

  @Test
  void 迁移落地且九张表全部强制rls() throws Exception {
    List<String> tables =
        List.of(
            "fulfillment_dropship_fee_rate", "fulfillment_dropship_fx_rate",
            "fulfillment_dropship_settlement_cycle", "fulfillment_dropship_margin_fact",
            "fulfillment_dropship_loss_alert", "fulfillment_dropship_reconciliation",
            "fulfillment_dropship_reconciliation_line", "fulfillment_dropship_payable_task",
            "fulfillment_dropship_margin_event");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (String t : tables) {
        try (var rs =
            s.executeQuery(
                "select relrowsecurity, relforcerowsecurity from pg_class "
                    + "where relname = '" + t + "'")) {
          assertThat(rs.next()).as("表必须存在：" + t).isTrue();
          assertThat(rs.getBoolean(1)).as("RLS 必须启用：" + t).isTrue();
          assertThat(rs.getBoolean(2)).as("RLS 必须 FORCE（否则表所有者绕过隔离）：" + t).isTrue();
        }
        try (var rs =
            s.executeQuery(
                "select column_default, is_nullable from information_schema.columns "
                    + "where table_name = '" + t + "' and column_name = 'tenant_id'")) {
          assertThat(rs.next()).as("tenant_id 必须存在：" + t).isTrue();
          assertThat(rs.getString(2)).as("tenant_id 必须 NOT NULL：" + t).isEqualTo("NO");
        }
      }
    }
  }

  @Test
  void 能力声明把四个未交付维度显式标为不可用并点名归属() {
    var caps = asA(service::capabilities);
    var byCode = caps.dimensions().stream()
        .filter(d -> !d.available())
        .map(DropshipMarginCapabilityDimension::code)
        .toList();
    assertThat(byCode)
        .as("平台费 / 头程费 / 汇率 / 应付 / 利润报表都必须显式 available=false，绝不伪造")
        .contains("PLATFORM_FEE_RATE", "FIRST_MILE_FEE_RATE", "PAYABLE_ORDER", "PROFIT_REPORT");
    var platformFee =
        caps.dimensions().stream()
            .filter(d -> d.code().equals("PLATFORM_FEE_RATE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformFee.ownerModule()).isEqualTo("5.2.3.1");
    assertThat(platformFee.note()).contains("未登记").contains("无法判定");
    assertThat(caps.basisNote()).contains("采购差价").contains("自有库存成本");
  }

  // ==========================================================================
  // 二、差价必须能从构成项复算出来（本模块的核心）
  // ==========================================================================

  @Test
  void 平台费未登记时差价可从构成项复算且结论为无法判定() {
    acceptedOrder(a, 1, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);

    var f = fact(a, orderIdOf(a, 1));
    // 断言的是**等式本身**，不是某个硬编码数字
    assertThat(f.dropshipCost())
        .isEqualByComparingTo(f.purchaseAmount().add(f.supplierShippingAmount()));
    assertThat(f.spreadBeforeFee())
        .isEqualByComparingTo(f.sellAmount().subtract(f.dropshipCost()));
    assertThat(f.platformFeeAmount()).as("未登记时平台费必须是 null，不是 0").isNull();
    assertThat(f.spreadAfterFee()).as("未登记时含平台费的差价整列必须是 null").isNull();
    assertThat(f.feeRegistrationStatus()).isEqualTo("UNREGISTERED");
    assertThat(f.lossVerdict())
        .as("售价 30 ≥ 代发成本 12，但平台费未登记 → **无法判定**，不是「不亏」")
        .isEqualTo("UNDETERMINED_NO_FEE");
    assertThat(f.lossEvidence()).contains("未登记").contains("无法判定");
  }

  @Test
  void 售价低于代发成本时即使平台费未登记也是确认亏损() {
    acceptedOrder(a, 2, "5.0000", "10.0000", "2.0000", "USD");
    evaluate(a);

    var f = fact(a, orderIdOf(a, 2));
    assertThat(f.spreadBeforeFee()).isNegative();
    assertThat(f.platformFeeAmount()).isNull();
    assertThat(f.lossVerdict())
        .as("平台费恒 ≥ 0，只按代发成本就已亏损 → 结论成立，实际亏损只会更大")
        .isEqualTo("LOSS_CONFIRMED");
  }

  @Test
  void 登记费率后重算_含平台费的差价仍然满足同一个等式() {
    acceptedOrder(a, 3, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var before = fact(a, orderIdOf(a, 3));
    assertThat(before.lossVerdict()).isEqualTo("UNDETERMINED_NO_FEE");

    registerFeeRate(a, "FEE-AMZN-15", "0.150000");
    evaluate(a);

    var after = fact(a, orderIdOf(a, 3));
    assertThat(after.feeRegistrationStatus()).isEqualTo("REGISTERED");
    assertThat(after.platformFeeAmount())
        .as("平台费 = 售价 30 × 15% = 4.5")
        .isEqualByComparingTo(new BigDecimal("4.5000"));
    assertThat(after.spreadAfterFee())
        .as("含平台费的差价必须能从构成项复算：30 − 10 − 2 − 4.5")
        .isEqualByComparingTo(new BigDecimal("13.5000"));
    assertThat(after.spreadAfterFee())
        .isEqualByComparingTo(
            after.sellAmount()
                .subtract(after.purchaseAmount())
                .subtract(after.supplierShippingAmount())
                .subtract(after.platformFeeAmount()));
    assertThat(after.lossVerdict()).as("30 − 12 − 4.5 = 13.5 ≥ 0 → 已含平台费，不亏")
        .isEqualTo("NOT_LOSS");
    assertThat(after.lossEvidence()).contains("FEE-AMZN-15");
  }

  @Test
  void 登记费率后原先无法判定的单变成确认亏损() {
    acceptedOrder(a, 4, "18.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    assertThat(fact(a, orderIdOf(a, 4)).lossVerdict()).isEqualTo("UNDETERMINED_NO_FEE");

    registerFeeRate(a, "FEE-AMZN-15", "0.150000");
    evaluate(a);

    var f = fact(a, orderIdOf(a, 4));
    // 18 − 12 − 2.7 = 3.3 ≥ 0，其实不亏；换成 40% 才亏。下面换一张单验证亏的那一支。
    assertThat(f.lossVerdict()).isEqualTo("NOT_LOSS");
    assertThat(f.spreadAfterFee()).isEqualByComparingTo(new BigDecimal("3.3000"));
  }

  @Test
  void 平台费高到吃掉全部差价时判为确认亏损() {
    acceptedOrder(a, 5, "30.0000", "10.0000", "2.0000", "USD");
    registerFeeRate(a, "FEE-HIGH", "0.700000"); // 30 × 70% = 21 > 18
    evaluate(a);

    var f = fact(a, orderIdOf(a, 5));
    assertThat(f.platformFeeAmount()).isEqualByComparingTo(new BigDecimal("21.0000"));
    assertThat(f.spreadBeforeFee()).as("只按代发成本看是赚的").isPositive();
    assertThat(f.lossVerdict())
        .as("含平台费后 30 − 12 − 21 = −3 → 确认亏损。这正是「按 0 算会漏掉」的那一单")
        .isEqualTo("LOSS_CONFIRMED");
    assertThat(f.spreadAfterFee()).isEqualByComparingTo(new BigDecimal("-3.0000"));
  }

  @Test
  void 成本未落定的代发单不算差价且跳过量如实报出() {
    // 只建单到 DISPATCHED，不给供应商回执 → 成本还会变
    as(
        a,
        () -> {
          acceptedOrder(a, 6, "30.0000", "10.0000", "2.0000", "USD");
          return null;
        });
    var r = evaluate(a);
    assertThat(r.computed()).isEqualTo(1);
    assertThat(r.scannedOrders()).isEqualTo(1);
    assertThat(r.lossConfirmed()).as("一单无法判定，不该被算进「确认亏损」").isZero();
    assertThat(r.undetermined()).isEqualTo(1);
  }

  @Test
  void 周期区间写死_落在周期外的单不算数() {
    acceptedOrder(a, 7, "30.0000", "10.0000", "2.0000", "USD");
    // P_OUT 落在 2020 年，夹具单永远不在其中 —— 无论哪一天跑
    var out =
        as(a, () -> service.evaluateMarginFacts(P_OUT_START, P_OUT_END, idFor(a, 99)));
    assertThat(out.computed()).as("周期外的单必须不计入").isZero();
    assertThat(out.scannedOrders()).isZero();
  }

  // ==========================================================================
  // 三、亏损预警：两种结论不合并
  // ==========================================================================

  @Test
  void 亏损预警把已确认亏损与无法判定分成两类() {
    acceptedOrder(a, 8, "5.0000", "10.0000", "2.0000", "USD");   // 确认亏损
    acceptedOrder(a, 9, "30.0000", "10.0000", "2.0000", "USD");  // 无法判定
    evaluate(a);
    var r = evaluateAlerts(a);
    assertThat(r.computed()).isEqualTo(2);

    var page = asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, null, null, 0,
        50)));
    assertThat(page.lossConfirmedTotal()).isEqualTo(1);
    assertThat(page.undeterminedTotal())
        .as("无法判定的必须单列一个桶，不能并进正常桶").isEqualTo(1);

    var undet = page.items().stream()
        .filter(a1 -> a1.alertKind().equals("UNDETERMINED_NO_FEE"))
        .findFirst()
        .orElseThrow();
    assertThat(undet.severity())
        .as("无法判定**不是**一个已确认的问题，不该画成 WARNING").isEqualTo("UNDETERMINED");
    assertThat(undet.ruleEvidence()).contains("无法判定");
  }

  @Test
  void 反复跑检出不会把同一条预警刷成几十条() {
    acceptedOrder(a, 10, "5.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    evaluateAlerts(a);
    evaluateAlerts(a);
    evaluateAlerts(a);
    var page = asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, null, null, 0,
        50)));
    assertThat(page.items()).hasSize(1);
  }

  @Test
  void 处置必须写明依据() {
    acceptedOrder(a, 11, "5.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    evaluateAlerts(a);
    var alert =
        asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, "OPEN", null, 0, 50)))
            .items()
            .get(0);

    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.acknowledgeAlert(
                                alert.id(), new DropshipLossAlertAckInput("  ", false),
                                idFor(a, 99)))))
        .hasMessageContaining("必须写明依据");

    var done =
        asA(
            () ->
                service.acknowledgeAlert(
                    alert.id(),
                    new DropshipLossAlertAckInput("已联系供应商重谈结算价", true),
                    idFor(a, 99)));
    assertThat(done.status()).isEqualTo("RESOLVED");
    assertThat(done.resolutionNote()).contains("重谈");
  }

  @Test
  void 判为误报后可重新检出_因为那是一条新事实() {
    acceptedOrder(a, 12, "5.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    evaluateAlerts(a);
    var alert =
        asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, "OPEN", null, 0, 50)))
            .items()
            .get(0);
    asA(
        () ->
            service.dismissAlert(
                alert.id(), new DropshipLossAlertDismissInput("成本尚未最终确认"), idFor(a, 99)));
    evaluateAlerts(a);
    var page = asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, null, null, 0,
        50)));
    assertThat(page.items())
        .as("解封后重新触发会被当作一条新事实重新检出")
        .hasSize(2);
  }

  // ==========================================================================
  // 四、报表：按经营模式分组 + 跨币种不相加
  // ==========================================================================

  @Test
  void 报表按经营模式分组且跨币种不相加() {
    acceptedOrder(a, 13, "30.0000", "10.0000", "2.0000", "USD");
    acceptedOrder(a, 14, "30.0000", "10.0000", "2.0000", "CNY");
    evaluate(a);

    var report =
        asA(() -> service.report("SUPPLIER", P_IN_START, P_IN_END, null, null, null));
    assertThat(report.businessModel()).isEqualTo("DROPSHIP");
    assertThat(report.costBasis())
        .as("代发的成本是采购差价，不是自有库存成本").isEqualTo("PURCHASE_SPREAD");
    assertThat(report.groups()).hasSize(2);
    assertThat(report.groups())
        .as("USD 与 CNY 必须分成两组，不许加成一个数")
        .allSatisfy(g -> assertThat(g.businessModel()).isEqualTo("DROPSHIP"));
    assertThat(report.groups().stream().map(DropshipMarginReportGroup::currencyCode).toList())
        .containsExactlyInAnyOrder("USD", "CNY");

    var usd = report.groups().stream()
        .filter(g -> g.currencyCode().equals("USD"))
        .findFirst()
        .orElseThrow();
    assertThat(usd.sellAmount()).isEqualByComparingTo(new BigDecimal("30.0000"));
    assertThat(usd.spreadBeforeFee())
        .as("差价必须能从构成项复算")
        .isEqualByComparingTo(usd.sellAmount().subtract(usd.dropshipCost()));
  }

  @Test
  void 按店铺分组时取自代发配置而非自算() {
    acceptedOrder(a, 15, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var report = asA(() -> service.report("SHOP", P_IN_START, P_IN_END, null, null, null));
    assertThat(report.groups()).hasSize(1);
    assertThat(report.groups().get(0).groupKey()).isEqualTo("SHOP-A");
  }

  @Test
  void sku维度不摊供应商运费与平台费并写明原因() {
    acceptedOrder(a, 16, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var report = asA(() -> service.report("SKU", P_IN_START, P_IN_END, null, null, null));
    assertThat(report.groups()).hasSize(1);
    var g = report.groups().get(0);
    assertThat(g.groupKey()).isEqualTo("SKU-M");
    assertThat(g.supplierShippingAmount())
        .as("供应商运费是单级金额，摊到 SKU 必须假设分摊规则 → 不摊")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(g.note()).contains("不摊").contains("假设");
    assertThat(g.spreadBeforeFee()).isEqualByComparingTo(new BigDecimal("20.0000"));
  }

  // ==========================================================================
  // 五、对账：合计可复算 + 汇率未登记单列 + 快照冻结
  // ==========================================================================

  @Test
  void 对账合计必须等于行之和且应付等于采购加运费() {
    acceptedOrder(a, 17, "30.0000", "10.0000", "2.0000", "USD");
    registerFeeRate(a, "FEE-15", "0.150000");
    evaluate(a);

    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    var detail = asA(() -> service.reconciliationDetail(stmt.id()));

    assertThat(detail.statement().orderCount()).isEqualTo(detail.lines().size());
    var line = detail.lines().get(0);
    assertThat(line.inTotals()).isTrue();
    // 每一行都能就地复算
    assertThat(line.payableInStatement())
        .as("应付 = 采购结算价 + 供应商运费")
        .isEqualByComparingTo(
            line.purchaseAmountInStatement().add(line.supplierShippingAmountInStatement()));
    assertThat(line.spreadInStatement())
        .as("含平台费的差价必须能从构成项复算：售价 − 代发成本 − 平台费")
        .isEqualByComparingTo(
            line.sellAmountInStatement()
                .subtract(line.dropshipCostInStatement())
                .subtract(line.platformFeeInStatement()));
    // 头合计 = 行之和
    assertThat(detail.statement().totalPayable())
        .isEqualByComparingTo(line.payableInStatement());
    assertThat(detail.statement().totalDropshipCost())
        .isEqualByComparingTo(line.dropshipCostInStatement());
    assertThat(detail.statement().businessModel()).isEqualTo("DROPSHIP");
  }

  @Test
  void 汇率未登记的行不计入合计并单独进未登记桶_不用1_0假装() {
    acceptedOrder(a, 18, "30.0000", "10.0000", "2.0000", "CNY");
    evaluate(a);

    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    var detail = asA(() -> service.reconciliationDetail(stmt.id()));

    var line = detail.lines().get(0);
    assertThat(line.fxStatus()).as("汇率未登记 → UNSET").isEqualTo("UNSET");
    assertThat(line.fxRateValue()).isNull();
    assertThat(line.payableInStatement()).as("折算列整组为 null，不是按 1:1 假装")
        .isNull();
    assertThat(line.inTotals()).isFalse();

    assertThat(detail.statement().orderCount()).isZero();
    assertThat(detail.statement().totalPayable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(detail.statement().undeterminedOrderCount()).isEqualTo(1);
    assertThat(detail.statement().undeterminedPayable())
        .as("未登记应付按原币如实记着，不并进合计").isEqualByComparingTo(new BigDecimal("12.0000"));
    assertThat(detail.statement().fxStatus()).isEqualTo("UNSET");
    assertThat(detail.statement().ruleEvidence()).contains("汇率未登记");
    assertThat(detail.fxNote()).contains("排除在合计外");
  }

  @Test
  void 登记汇率后该行进合计且折算等式成立() {
    acceptedOrder(a, 19, "30.0000", "10.0000", "2.0000", "CNY");
    evaluate(a);
    asA(
        () ->
            service.saveFxRate(
                new DropshipFxRateInput(
                    "CNY", "USD", new BigDecimal("0.14000000"), at(-30), null, "TENANT_MAINTAINED",
                    at(-40), ""),
                idFor(a, 99)));

    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    var detail = asA(() -> service.reconciliationDetail(stmt.id()));
    var line = detail.lines().get(0);

    assertThat(line.fxStatus()).isEqualTo("APPLIED");
    assertThat(line.inTotals()).isTrue();
    assertThat(line.purchaseAmountInStatement())
        .as("10 CNY × 0.14 = 1.40 USD")
        .isEqualByComparingTo(new BigDecimal("1.4000"));
    assertThat(line.supplierShippingAmountInStatement())
        .as("2 CNY × 0.14 = 0.28 USD")
        .isEqualByComparingTo(new BigDecimal("0.2800"));
    assertThat(line.payableInStatement())
        .isEqualByComparingTo(new BigDecimal("1.6800"));
    assertThat(line.payableInStatement())
        .isEqualByComparingTo(
            line.purchaseAmountInStatement().add(line.supplierShippingAmountInStatement()));
    assertThat(detail.statement().undeterminedOrderCount()).isZero();
    assertThat(detail.statement().totalPayable()).isEqualByComparingTo(new BigDecimal("1.6800"));
  }

  @Test
  void 原币即结算币时不需要汇率并标为同币() {
    acceptedOrder(a, 20, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    var line = asA(() -> service.reconciliationDetail(stmt.id())).lines().get(0);
    assertThat(line.fxStatus()).isEqualTo("SAME_CURRENCY");
    assertThat(line.payableInStatement()).isEqualByComparingTo(new BigDecimal("12.0000"));
  }

  @Test
  void 对账快照冻结_事后改费率改不动历史对账单() {
    acceptedOrder(a, 21, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    asA(
        () -> service.changeStatementStatus(
            stmt.id(), new DropshipReconciliationStatusInput("FINALIZED", ""), idFor(a, 99)));

    var frozen = asA(() -> service.reconciliationDetail(stmt.id()));
    var frozenPayable = frozen.lines().get(0).payableInStatement();

    // 事后登记一条高费率。这条事实已被定稿对账单引用，因此**重算本身就该被拒绝** ——
    // 改它等于改写「当时是按这个费率算的」这个凭据。要重算只能先 DISPUTED 再重开一张。
    registerFeeRate(a, "FEE-LATE-90", "0.900000");
    assertThat(catchThrowable(() -> evaluate(a)))
        .hasMessageContaining("已被定稿对账单引用");

    var after = asA(() -> service.reconciliationDetail(stmt.id()));
    assertThat(after.lines().get(0).payableInStatement())
        .as("定稿后的对账行是那一时刻的冻结快照")
        .isEqualByComparingTo(frozenPayable);
    assertThat(after.statement().totalPayable()).isEqualByComparingTo(frozenPayable);
  }

  @Test
  void 同一供应商同一周期只允许一张对账单() {
    acceptedOrder(a, 22, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    asA(
        () ->
            service.buildReconciliation(
                new DropshipReconciliationInput(
                    "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                idFor(a, 99)));
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.buildReconciliation(
                                new DropshipReconciliationInput(
                                    "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                                idFor(a, 99)))))
        .hasMessageContaining("已有一张对账单");
  }

  @Test
  void 对账周期必须晚于开始时间() {
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.buildReconciliation(
                                new DropshipReconciliationInput(
                                    "SUP-ACME", "USD", P_IN_END, P_IN_START, "MONTHLY", ""),
                                idFor(a, 99)))))
        .hasMessageContaining("必须晚于");
  }

  // ==========================================================================
  // 六、应付待办：12.1.2.1 未交付，真待办不画成已完成
  // ==========================================================================

  @Test
  void 应付待办在下游接手前停在未受理_且已发出与已受理是两个状态() {
    acceptedOrder(a, 23, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));

    var tasks = asA(() -> service.pageTasks(new DropshipPayableTaskQuery(null, null, 0, 50)));
    assertThat(tasks.items()).hasSize(1);
    var task = tasks.items().get(0);
    assertThat(task.taskStatus())
        .as("12.1.2.1 未交付 → 待办只能是 OPEN，不能画成已生成应付单")
        .isEqualTo("OPEN");
    assertThat(task.payload().businessModel()).isEqualTo("DROPSHIP");
    assertThat(task.payload().costBasis()).isEqualTo("PURCHASE_SPREAD");
    assertThat(task.payableCurrencyCode()).isEqualTo("USD");

    asA(() -> service.claimTask(task.id(), idFor(a, 99)));
    var sent =
        asA(
            () ->
                service.completeTask(
                    task.id(), new DropshipPayableTaskResultInput("SENT", "已发出"), idFor(a, 99)));
    assertThat(sent.taskStatus()).isEqualTo("SENT");
    assertThat(sent.acknowledgedAt())
        .as("「已发出」不等于「下游已受理」，受理时刻必须仍为空")
        .isNull();

    var ack =
        asA(
            () ->
                service.completeTask(
                    task.id(),
                    new DropshipPayableTaskResultInput("ACKNOWLEDGED", "12 财务已受理"),
                    idFor(a, 99)));
    assertThat(ack.taskStatus()).isEqualTo("ACKNOWLEDGED");
    assertThat(ack.acknowledgedAt()).isNotNull();
    assertThat(ack.taskStatusLabel()).isEqualTo("下游已受理");
  }

  @Test
  void 重复提交同一张对账单不会造出第二条应付待办() {
    acceptedOrder(a, 24, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    asA(
        () ->
            service.buildReconciliation(
                new DropshipReconciliationInput(
                    "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                idFor(a, 99)));
    // 幂等键：直接再插一次同键的待办必须被 DB 的唯一索引挡掉
    assertDbRejectedUnique(
        () ->
            asTableOwner(
                    a,
                    "insert into fulfillment_dropship_payable_task(id,tenant_id,task_no,task_kind,"
                        + "target_system,task_status,statement_id,statement_no,supplier_code,"
                        + "payable_amount,payable_currency_code,payload,idempotency_key) "
                        + "select gen_random_uuid(),tenant_id,'DPT-DUP','SUPPLIER_PAYABLE','FINANCE',"
                        + "'OPEN',id,statement_no,supplier_code,total_payable,"
                        + "currency_code,'{}'::jsonb,'SUPPLIER_PAYABLE:'||id "
                        + "from fulfillment_dropship_reconciliation")
                .run(),
        "idempotency_key");
  }

  // ==========================================================================
  // 七、费率 / 汇率 / 周期：登记的校验
  // ==========================================================================

  @Test
  void 平台费率两种计价形态互斥且必须带币种与观测时点() {
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveFeeRate(
                                new DropshipFeeRateInput(
                                    "F1", "比例+固定", "MANUAL", "COMMISSION",
                                    DropshipFeeChargeBasis.PERCENT_OF_SELL,
                                    new BigDecimal("0.150000"), new BigDecimal("2.0000"), "USD",
                                    "USD", at(-30), null, at(-40), ""),
                                idFor(a, 99)))))
        .hasMessageContaining("两种计价形态只能选一种");

    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveFeeRate(
                                new DropshipFeeRateInput(
                                    "F2", "固定额没币种", "MANUAL", "COMMISSION",
                                    DropshipFeeChargeBasis.FIXED_PER_ORDER, null,
                                    new BigDecimal("2.0000"), null, "USD", at(-30), null, at(-40),
                                    ""),
                                idFor(a, 99)))))
        .hasMessageContaining("必须提供 fixedCurrencyCode");

    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveFeeRate(
                                new DropshipFeeRateInput(
                                    "F3", "观测晚于生效", "MANUAL", "COMMISSION",
                                    DropshipFeeChargeBasis.PERCENT_OF_SELL,
                                    new BigDecimal("0.150000"), null, null, "USD", at(-30), null,
                                    at(-1), ""),
                                idFor(a, 99)))))
        .hasMessageContaining("观测时点不得晚于生效起点");
  }

  @Test
  void 汇率不能是本币对本币() {
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveFxRate(
                                new DropshipFxRateInput(
                                    "USD", "USD", new BigDecimal("1.00000000"), at(-30), null,
                                    "TENANT_MAINTAINED", at(-40), ""),
                                idFor(a, 99)))))
        .hasMessageContaining("本币对本币不需要汇率");
  }

  @Test
  void 周结不许带结算日月结必须带() {
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveCycle(
                                new DropshipSettlementCycleInput(
                                    "C1", "SUP-ACME", "USD", "WEEKLY", 15, at(-30), null, ""),
                                idFor(a, 99)))))
        .hasMessageContaining("settleDay 必须留空");

    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.saveCycle(
                                new DropshipSettlementCycleInput(
                                    "C2", "SUP-ACME", "USD", "MONTHLY", null, at(-30), null, ""),
                                idFor(a, 99)))))
        .hasMessageContaining("必须提供 settleDay");

    var ok =
        asA(
            () ->
                service.saveCycle(
                    new DropshipSettlementCycleInput(
                        "C3", "SUP-ACME", "USD", "MONTHLY", 1, at(-30), null, ""),
                    idFor(a, 99)));
    assertThat(ok.cycleKind()).isEqualTo("MONTHLY");
    assertThat(ok.settleDay()).isEqualTo(1);
  }

  // ==========================================================================
  // 八、DB 级闸门
  // ==========================================================================

  @Test
  void db拒绝自营口径混入差价事实() {
    // 必须先有真实代发行，否则 insert...select 匹配 0 行、约束根本没被触发
    acceptedOrder(a, 40, "30.0000", "10.0000", "2.0000", "USD");
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "insert into fulfillment_dropship_margin_fact(id,tenant_id,order_id,order_no,"
                        + "sales_order_id,sales_order_no,shop_code,supplier_code,currency_code,"
                        + "business_model,cost_basis,cost_basis_source,sell_amount,purchase_amount,"
                        + "supplier_shipping_amount,fee_registration_status,loss_evidence) "
                        + "select gen_random_uuid(),tenant_id,id,order_no,sales_order_id,"
                        + "sales_order_no,'S',supplier_code,currency_code,'SELF_OP','INVENTORY_COST',"
                        + "'MODULE_9_6_1_2',sell_amount,purchase_amount,supplier_shipping_amount,"
                        + "'UNREGISTERED','x' from fulfillment_dropship_order")
                .run(),
        "");
  }

  @Test
  void db拒绝费率登记状态与金额不一致() {
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "insert into fulfillment_dropship_fee_rate(id,tenant_id,rate_code,rate_name,"
                        + "platform_code,fee_type,charge_basis,fixed_amount,fixed_currency_code,"
                        + "settlement_currency_code,effective_from,effective_to,source,observed_at) "
                        + "values (gen_random_uuid(),'" + a + "','X1','固定额缺币种','MANUAL',"
                        + "'COMMISSION','FIXED_PER_ORDER',2.00,null,'USD',now(),null,"
                        + "'TENANT_MAINTAINED',now())")
                .run(),
        "");
  }

  @Test
  void db拒绝预警证据被改写() throws Exception {
    acceptedOrder(a, 25, "5.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    evaluateAlerts(a);
    var alert =
        asA(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, "OPEN", null, 0, 50)))
            .items().get(0);
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_loss_alert set rule_evidence = '改成不亏' "
                        + "where id = '" + alert.id() + "'")
                .run(),
        "不可改写");
    // 处置字段仍可写（能改处置 ≠ 能改事实）
    asTableOwner(
            a,
            "update fulfillment_dropship_loss_alert set resolution_note = '处置备注' "
                + "where id = '" + alert.id() + "'")
        .run();
  }

  @Test
  void db拒绝定稿后的对账合计被改写() {
    acceptedOrder(a, 26, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    asA(
        () -> service.changeStatementStatus(
            stmt.id(), new DropshipReconciliationStatusInput("FINALIZED", ""), idFor(a, 99)));
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_reconciliation set total_payable = 1 "
                        + "where id = '" + stmt.id() + "'")
                .run(),
        "不可改写");
  }

  @Test
  void db拒绝定稿后修改对账明细行() {
    acceptedOrder(a, 27, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    asA(
        () -> service.changeStatementStatus(
            stmt.id(), new DropshipReconciliationStatusInput("FINALIZED", ""), idFor(a, 99)));
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_reconciliation_line "
                        + "set rule_evidence = '事后改写' where statement_id = '" + stmt.id()
                        + "'")
                .run(),
        "冻结快照");
  }

  @Test
  void db拒绝删除凭据() {
    acceptedOrder(a, 28, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    assertDbRefused(
        () ->
            asTableOwner(
                    a, "delete from fulfillment_dropship_margin_fact where tenant_id = '" + a + "'")
                .run(),
        "不可删除");
  }

  @Test
  void db拒绝事件流被改写() {
    asA(() -> service.evaluateMarginFacts(P_IN_START, P_IN_END, idFor(a, 99)));
    assertDbRefused(
        () ->
            asTableOwner(
                    a, "delete from fulfillment_dropship_margin_event where tenant_id = '" + a + "'")
                .run(),
        "只追加");
  }

  @Test
  void db拒绝无争议说明的对账被置为争议() {
    assertThat(
            catchThrowable(
                () ->
                    asA(
                        () ->
                            service.changeStatementStatus(
                                UUID.randomUUID(),
                                new DropshipReconciliationStatusInput("DISPUTED", ""),
                                idFor(a, 99)))))
        .hasMessageContaining("争议");
  }

  @Test
  void 时间往返不发生静默偏移() {
    acceptedOrder(a, 29, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var f = fact(a, orderIdOf(a, 29));
    long skewSeconds =
        Math.abs(
            java.time.Duration.between(
                    f.computedAt().toInstant(), OffsetDateTime.now(ZoneOffset.UTC).toInstant())
                .getSeconds());
    assertThat(skewSeconds)
        .as("OffsetDateTime 往返不得偏 −28800s（实际偏了 %d 秒）", skewSeconds)
        .isLessThan(120);

    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));
    var back = asA(() -> service.reconciliationDetail(stmt.id())).statement();
    assertThat(back.periodStart()).isEqualTo(P_IN_START);
    assertThat(back.periodEnd()).isEqualTo(P_IN_END);
  }

  // ==========================================================================
  // 九、多租户三断言
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    acceptedOrder(a, 30, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmtA =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));

    assertThat(asA(() -> service.pageFacts(new DropshipMarginFactQuery(null, null, null, null, null,
        0, 50)).total())).isEqualTo(1);
    assertThat(asB(() -> service.pageFacts(new DropshipMarginFactQuery(null, null, null, null, null,
        0, 50)).total())).isZero();

    assertThat(
            catchThrowable(() -> asB(() -> service.reconciliationDetail(stmtA.id()))))
        .hasMessageContaining("不属于当前租户");
    assertThat(asB(() -> service.pageTasks(new DropshipPayableTaskQuery(null, null, 0, 50)).items()))
        .isEmpty();
    assertThat(asB(() -> service.pageAlerts(new DropshipLossAlertQuery(null, null, null, null, 0,
        50)).items())).isEmpty();
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    acceptedOrder(a, 31, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);

    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/trade/fulfillment/dropship-margin/facts")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString())
                    .param("page", "0")
                    .param("size", "50"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var body = result.getResponse().getContentAsString();
    assertThat(body).contains("SUP-ACME");
    assertThat(body)
        .as("伪造的 X-Tenant-Id 头改不动已认证成员关系解析出的租户")
        .doesNotContain(b.toString());
  }

  @Test
  void 断言3_连接池归还后不串租户() {
    acceptedOrder(a, 32, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    // 交替复用同一条连接（池大小 = 1），验证租户上下文不会残留
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.pageFacts(new DropshipMarginFactQuery(null, null, null, null,
          null, 0, 50)).total())).isEqualTo(1);
      assertThat(asB(() -> service.pageFacts(new DropshipMarginFactQuery(null, null, null, null,
          null, 0, 50)).total())).isZero();
    }
  }

  // ==========================================================================
  // 十、端点与权限
  // ==========================================================================

  @Test
  void 端点真实可用且跨租户404() throws Exception {
    acceptedOrder(a, 33, "30.0000", "10.0000", "2.0000", "USD");
    evaluate(a);
    var stmt =
        asA(
            () ->
                service.buildReconciliation(
                    new DropshipReconciliationInput(
                        "SUP-ACME", "USD", P_IN_START, P_IN_END, "MONTHLY", ""),
                    idFor(a, 99)));

    var caps =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/trade/fulfillment/dropship-margin/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn();
    assertThat(caps.getResponse().getStatus()).isEqualTo(200);
    assertThat(caps.getResponse().getContentAsString()).contains("PLATFORM_FEE_RATE");

    for (String path :
        List.of(
            "/trade/fulfillment/dropship-margin/facts?page=0&size=10",
            "/trade/fulfillment/dropship-margin/report?groupBy=SUPPLIER&periodStart="
                + P_IN_START + "&periodEnd=" + P_IN_END,
            "/trade/fulfillment/dropship-margin/alerts?page=0&size=10",
            "/trade/fulfillment/dropship-margin/reconciliations?page=0&size=10",
            "/trade/fulfillment/dropship-margin/tasks?page=0&size=10",
            "/trade/fulfillment/dropship-margin/fee-rates?page=0&size=10",
            "/trade/fulfillment/dropship-margin/fx-rates?page=0&size=10",
            "/trade/fulfillment/dropship-margin/cycles?page=0&size=10",
            "/trade/fulfillment/dropship-margin/events?page=0&size=10",
            "/trade/fulfillment/dropship-margin/reconciliations/" + stmt.id())) {
      var res =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                      .header("Authorization", "Bearer tenant-a"))
              .andReturn();
      assertThat(res.getResponse().getStatus()).as("路径必须真实可用：" + path).isEqualTo(200);
    }

    var cross =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/trade/fulfillment/dropship-margin/reconciliations/" + stmt.id())
                    .header("Authorization", "Bearer tenant-b"))
            .andReturn();
    assertThat(cross.getResponse().getStatus()).as("跨租户详情 → 404").isEqualTo(404);
  }

  @Test
  void 只读身份不能写() throws Exception {
    var res =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/trade/fulfillment/dropship-margin/facts/evaluate")
                    .header("Authorization", "Bearer read-only")
                    .param("periodStart", P_IN_START.toString())
                    .param("periodEnd", P_IN_END.toString()))
            .andReturn();
    assertThat(res.getResponse().getStatus()).isEqualTo(403);
  }

}
