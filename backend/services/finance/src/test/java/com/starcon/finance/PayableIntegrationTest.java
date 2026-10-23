package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PayableModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.1.2.1 应付单管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里那两条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「**应付、预付款与采购承诺分开建模**」⇒ 预付款在**另一张表**上、它没有「已付金额」、
 *       采购承诺本服务**不建模**（能力声明里如实写出）；
 *   <li>「**确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导**」⇒ 没有启用的政策就没有任何应付能产生，
 *       手工录入**不能**冒充上游来源，缺单价快照的上游事实**导不进来**；
 *   <li>「已付金额与未付余额」⇒ 已付是**派生值**（行级 paid_amount 之和），
 *       未付余额是**生成列**，本模块**不提供**任何改已付金额的端点；
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *   <li>「币种不许跨币种相加」⇒ 汇总与未付清单按币种分开。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayableIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of(
          "finance.payable.read",
          "finance.payable.create",
          "finance.payable.update",
          "finance.prepayment.read",
          "finance.prepayment.create",
          "finance.prepayment.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，
   * 于是 {@code 20.0000} 变成 {@code 20.0} —— 断言就分不出「金额是四位小数」和「金额丢了一位」。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer tradeUpstream;
  static HttpServer warehouseUpstream;
  static HttpServer channelUpstream;

  /** trade 的到货跟踪列表响应。 */
  static volatile String arrivalsPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** trade 的到货跟踪详情响应（按 id 返回同一份）。 */
  static volatile String arrivalDetailPayload = "{\"head\":{},\"payableConfirmations\":[]}";

  /** warehouse 的头程发运单列表响应。 */
  static volatile String firstMilePayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** channel 的广告报表响应。 */
  static volatile String adReportPayload = "{\"items\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** 上游打故障：模拟「上游读不到」。 */
  static volatile boolean upstreamDown = false;

  /** 记录上游收到的「财务确认」写回：key = 应付部分确认 id，value = 本服务的应付单号。 */
  static final Map<String, String> upstreamConfirmations = new ConcurrentHashMap<>();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    try {
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
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      tradeUpstream = HttpServer.create(new InetSocketAddress(0), 0);
      tradeUpstream.createContext(
          "/trade/purchase-arrivals",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String path = e.getRequestURI().getPath();
            String body;
            if (path.endsWith("/confirm")) {
              // 写回确认：记下「哪个应付部分确认被确认成了哪张应付单」。
              var requestBody = new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
              var reference = json.readTree(requestBody).path("financeReference").asText("");
              var parts = path.split("/");
              upstreamConfirmations.put(parts[parts.length - 2], reference);
              body = "{}";
            } else if (path.endsWith("/purchase-arrivals")) {
              body = arrivalsPayload;
            } else {
              body = arrivalDetailPayload;
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      tradeUpstream.setExecutor(Executors.newFixedThreadPool(2));
      tradeUpstream.start();

      warehouseUpstream = HttpServer.create(new InetSocketAddress(0), 0);
      warehouseUpstream.createContext(
          "/warehouse/first-mile-shipments",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] bytes = firstMilePayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      warehouseUpstream.setExecutor(Executors.newFixedThreadPool(2));
      warehouseUpstream.start();

      channelUpstream = HttpServer.create(new InetSocketAddress(0), 0);
      channelUpstream.createContext(
          "/channel/ad-reports",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] bytes = adReportPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      channelUpstream.setExecutor(Executors.newFixedThreadPool(2));
      channelUpstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + tradeUpstream.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouseUpstream.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channelUpstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired PayableService payables;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy"
              + " restart identity cascade");
    }
    arrivalsPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";
    arrivalDetailPayload = "{\"head\":{},\"payableConfirmations\":[]}";
    firstMilePayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";
    adReportPayload = "{\"items\":[],\"page\":0,\"size\":200,\"total\":0}";
    upstreamDown = false;
    upstreamConfirmations.clear();
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行（事务外 RLS 会诚实地把所有行都挡掉）。 */
  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  /** 在**租户事务里**计数。 */
  long count(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds).get(0, Long.class));
  }

  /** 测试用的可信 actor。 */
  AccessService.Access actor() {
    return actors.get("session");
  }

  /**
   * 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息。
   *
   * <p>触发器断言必须这么跑：超管不受 RLS 干扰，报出来的错就是**触发器**的错。
   */
  String dbError(String statement, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
      ps.execute();
      return "NO_ERROR";
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  /** 配置一份应付确认政策（走服务层）。 */
  void enablePolicy(String source, int dueDays) {
    as(
        () ->
            payables.updatePolicy(
                PayableSource.valueOf(source),
                ObligationKind.PAYABLE,
                new FinancePayablePolicyInput(true, dueDays, "测试夹具"),
                actor()));
  }

  /** 配置一份预付款确认政策（走服务层）。 */
  void enablePrepaymentPolicy() {
    as(
        () ->
            payables.updatePolicy(
                PayableSource.MANUAL,
                ObligationKind.PREPAYMENT,
                new FinancePayablePolicyInput(true, 0, "测试夹具"),
                actor()));
  }

  /** 人工录入一张应付单（默认两行明细，合计 20.0000）。 */
  FinancePayableRow createManual(String extraJson) {
    var input =
        inputOf(
            """
            {"counterpartyType":"SUPPLIER","counterpartyName":"供应商甲","currency":"USD"%s,
             "lines":[{"description":"明细一","amount":12.5},{"description":"明细二","amount":7.5}]}
            """
                .formatted(extraJson == null ? "" : extraJson));
    return as(() -> payables.create(input, actor()));
  }

  // ================================================================ 政策闸门

  @Test
  void 没有启用的确认政策就产生不了任何应付() {
    var input = manualInput(null);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .as("必须说清是「政策没配」，而不是含糊的「参数错误」")
        .hasStackTraceContaining("应付确认政策");
  }

  @Test
  void 政策禁用时同样产生不了应付() {
    as(
        () ->
            payables.updatePolicy(
                PayableSource.MANUAL,
                ObligationKind.PAYABLE,
                new FinancePayablePolicyInput(false, 30, null),
                actor()));
    var input = manualInput(null);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .hasStackTraceContaining("禁用");
  }

  @Test
  void 政策列表把每个来源与义务类型都列出来并区分未配置与已禁用() {
    var views = as(() -> payables.policies());
    // 7 个来源 × 2 个义务类型 + 1 条采购承诺（不建模）= 15 行。
    assertThat(views).hasSize(7 * 2 + 1);
    assertThat(views).allMatch(v -> !v.configured());
    // 供应商对账当前不可产生 —— 必须写明归属模块 12.1.2.3。
    var statement =
        views.stream()
            .filter(v -> v.source() == PayableSource.SUPPLIER_STATEMENT)
            .filter(v -> v.obligationKind() == ObligationKind.PAYABLE)
            .findFirst()
            .orElseThrow();
    assertThat(statement.producible()).isFalse();
    assertThat(statement.ownerModule()).isEqualTo("12.1.2.3");
    assertThat(statement.detail()).contains("未交付");
    // 平台费用当前不可产生 —— 归属 12.3.1.1。
    var platform =
        views.stream()
            .filter(v -> v.source() == PayableSource.PLATFORM_FEE)
            .filter(v -> v.obligationKind() == ObligationKind.PAYABLE)
            .findFirst()
            .orElseThrow();
    assertThat(platform.producible()).isFalse();
    assertThat(platform.ownerModule()).isEqualTo("12.3.1.1");
    // 采购承诺：词表保留，但本服务不建模。
    var commitment =
        views.stream()
            .filter(v -> v.obligationKind() == ObligationKind.COMMITMENT)
            .findFirst()
            .orElseThrow();
    assertThat(commitment.producible()).isFalse();
    assertThat(commitment.detail()).contains("不建模");
  }

  // ================================================================ 建单

  @Test
  void 建单后单头金额等于明细合计且已付为零未付等于金额() {
    enablePolicy("MANUAL", 30);
    var payable = createManual(null);
    assertThat(payable.amount()).isEqualByComparingTo("20.0000");
    assertThat(payable.status()).isEqualTo(PayableStatus.OPEN);
    assertThat(payable.paidAmount()).as("12.2.2.1 未交付 ⇒ 已付恒为 0").isEqualByComparingTo("0.0000");
    assertThat(payable.unpaidAmount()).isEqualByComparingTo("20.0000");
    assertThat(payable.source()).isEqualTo(PayableSource.MANUAL);
    // 到期日 = 确认日 + 政策账期 30 天。
    assertThat(payable.dueDate()).isEqualTo(payable.confirmedAt().toLocalDate().plusDays(30));
  }

  @Test
  void 金额落库是四位小数的numeric而不是浮点() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    var column =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_payable' and column_name = 'amount'");
    assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(4);
    // 逐位相等：金额是 20.0000，不是 20、也不是 20.0。
    assertThat(one("select amount from finance_payable").get(0, BigDecimal.class).toPlainString())
        .isEqualTo("20.0000");
    var quantityColumn =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_payable_line' and column_name = 'quantity'");
    assertThat(quantityColumn.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(quantityColumn.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  @Test
  void 明细行必须有金额且不能为零() {
    enablePolicy("MANUAL", 0);
    var input =
        inputOf(
            """
            {"counterpartyType":"SUPPLIER","counterpartyName":"供应商甲","currency":"USD",
             "lines":[{"description":"明细一","amount":0}]}
            """);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .hasStackTraceContaining("必须大于 0");
  }

  @Test
  void 没有明细行的应付单建不出来() {
    enablePolicy("MANUAL", 0);
    var input =
        inputOf(
            """
            {"counterpartyType":"SUPPLIER","counterpartyName":"供应商甲","currency":"USD","lines":[]}
            """);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .hasStackTraceContaining("至少要有一条明细行");
  }

  @Test
  void 采购收货来源不能手工录入() {
    enablePolicy("PURCHASE_RECEIPT", 30);
    var input =
        inputOf(
            """
            {"source":"PURCHASE_RECEIPT","counterpartyType":"SUPPLIER","counterpartyName":"供应商甲",
             "currency":"USD","lines":[{"description":"明细一","amount":12.5}]}
            """);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .as("采购收货的应付只能由上游事实产生，手工录入等于自己编一条上游事实")
        .hasStackTraceContaining("上游事实");
  }

  @Test
  void 供应商对账来源当前不可产生且必须写明归属模块() {
    enablePolicy("SUPPLIER_STATEMENT", 30);
    var input =
        inputOf(
            """
            {"source":"SUPPLIER_STATEMENT","counterpartyType":"SUPPLIER","counterpartyName":"供应商甲",
             "currency":"USD","lines":[{"description":"明细一","amount":12.5}]}
            """);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .hasStackTraceContaining("12.1.2.3");
    assertThat(count("select count(*) from finance_payable"))
        .as("被拒的建单不得留下任何行（绝不填 0、也绝不造假对账）")
        .isZero();
  }

  @Test
  void 平台费用来源当前不可产生且必须写明归属模块() {
    enablePolicy("PLATFORM_FEE", 30);
    var input =
        inputOf(
            """
            {"source":"PLATFORM_FEE","counterpartyType":"PLATFORM","counterpartyName":"Amazon",
             "currency":"USD","lines":[{"description":"明细一","amount":12.5}]}
            """);
    assertThatThrownBy(() -> as(() -> payables.create(input, actor())))
        .hasStackTraceContaining("12.3.1.1");
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  // ================================================================ 汇率快照

  @Test
  void 汇率快照建单时定死且本位币金额等于金额乘汇率() {
    enablePolicy("MANUAL", 0);
    var payable = createManual(",\"exchangeRate\":7.2,\"baseCurrency\":\"CNY\"");
    assertThat(payable.exchangeRate()).isEqualByComparingTo("7.20000000");
    assertThat(payable.baseCurrency()).isEqualTo("CNY");
    assertThat(payable.baseAmount()).isEqualByComparingTo("144.0000");
  }

  @Test
  void 没有汇率时本位币金额是null而不是零() {
    enablePolicy("MANUAL", 0);
    var payable = createManual(null);
    assertThat(payable.exchangeRate()).isNull();
    assertThat(payable.baseAmount()).as("「没折算」与「折算了 0 块」必须能分开").isNull();
  }

  @Test
  void 确认之后汇率快照改不动() {
    enablePolicy("MANUAL", 0);
    createManual(",\"exchangeRate\":7.2,\"baseCurrency\":\"CNY\"");
    UUID id = one("select id from finance_payable").get(0, UUID.class);
    assertThat(dbError("update finance_payable set exchange_rate = 7.3 where id = ?", id))
        .contains("快照");
  }

  @Test
  void 确认之后明细行改不动() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID lineId =
        one("select id from finance_payable_line order by line_no limit 1").get(0, UUID.class);
    assertThat(dbError("update finance_payable_line set amount = 99 where id = ?", lineId))
        .contains("不可再改");
  }

  // ================================================================ 已付 / 未付派生（12.2.2.1 未交付，写路径未开放）

  @Test
  void 明细行的已付金额把单头状态与未付余额派生出来() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID lineId =
        one("select id from finance_payable_line order by line_no limit 1").get(0, UUID.class);
    // 12.2.2.1 交付后写的就是这一列。本模块不开放写端点，所以这里直接写 SQL，
    // 验证「单头已付与未付余额由明细派生」这条不变量真的成立。
    as(() -> sql.execute("update finance_payable_line set paid_amount = 5 where id = ?", lineId));
    var head = one("select status, paid_amount, unpaid_amount from finance_payable");
    assertThat(head.get("status", String.class)).isEqualTo("PARTIALLY_PAID");
    assertThat(head.get("paid_amount", BigDecimal.class)).isEqualByComparingTo("5.0000");
    assertThat(head.get("unpaid_amount", BigDecimal.class)).isEqualByComparingTo("15.0000");

    as(() -> sql.execute("update finance_payable_line set paid_amount = amount where id = ?", lineId));
    head = one("select status, paid_amount, unpaid_amount from finance_payable");
    assertThat(head.get("status", String.class)).isEqualTo("PARTIALLY_PAID");
  }

  @Test
  void 全部付清后单头是已付清且未付余额是零() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    as(() -> sql.execute("update finance_payable_line set paid_amount = amount"));
    var head = one("select status, amount, paid_amount, unpaid_amount from finance_payable");
    assertThat(head.get("status", String.class)).isEqualTo("PAID");
    assertThat(head.get("paid_amount", BigDecimal.class))
        .isEqualByComparingTo(head.get("amount", BigDecimal.class));
    assertThat(head.get("unpaid_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
  }

  @Test
  void 已付的应付不能作废() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    as(() -> sql.execute("update finance_payable_line set paid_amount = 5"));
    UUID id = one("select id from finance_payable").get(0, UUID.class);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        payables.voidPayable(
                            id, new FinancePayableVoidInput("试试作废"), actor())))
        .hasStackTraceContaining("12.2.2.1");
  }

  // ================================================================ 未付清单

  @Test
  void 未付清单只列未付清的并按币种分开() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    // 第二张单：另一个币种 + 已付清。
    var eur =
        inputOf(
            """
            {"counterpartyType":"SUPPLIER","counterpartyName":"供应商乙","currency":"EUR",
             "lines":[{"description":"明细一","amount":30}]}
            """);
    as(() -> payables.create(eur, actor()));
    // 只把 EUR 那张单付清（USD 那张仍待付）。
    as(
        () ->
            sql.execute(
                "update finance_payable_line set paid_amount = amount"
                    + " where payable_id = (select id from finance_payable where currency = 'EUR')"));

    var list = as(() -> payables.unpaid());
    assertThat(list.items()).hasSize(1);
    assertThat(list.items().get(0).currency()).isEqualTo("USD");
    assertThat(list.items().get(0).unpaidAmount()).isEqualByComparingTo("20.0000");
    // 汇总按币种分开：EUR 已付清不在未付清单里。
    assertThat(list.summaryByCurrency()).extracting(FinancePayableCurrencySummary::currency)
        .containsExactly("USD");
    assertThat(list.asOfDate()).isNotNull();
  }

  @Test
  void 不同币种的单不会合进同一个合计() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    var eur =
        inputOf(
            """
            {"counterpartyType":"SUPPLIER","counterpartyName":"供应商乙","currency":"EUR",
             "lines":[{"description":"明细一","amount":30}]}
            """);
    as(() -> payables.create(eur, actor()));
    var page = as(() -> payables.page(new PayableQuery(null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(page.summaryByCurrency()).hasSize(2);
    assertThat(page.summaryByCurrency()).extracting(FinancePayableCurrencySummary::currency)
        .containsExactly("EUR", "USD");
    for (var summary : page.summaryByCurrency()) {
      assertThat(summary.amount()).isEqualByComparingTo(summary.unpaidAmount());
    }
  }

  // ================================================================ 导入：采购收货（6.3.2.2 到货应付部分确认）

  @Test
  void 从到货应付部分确认导入应付并把应付单号写回上游() {
    enablePolicy("PURCHASE_RECEIPT", 30);
    UUID arrivalId = UUID.randomUUID();
    UUID confirmationId = UUID.randomUUID();
    arrivalsPayload =
        ("{\"rows\":[{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierRefId\":null,"
                + "\"supplierCode\":\"S1\",\"supplierName\":\"供应商甲\",\"unitPrice\":12.5,"
                + "\"currency\":\"USD\",\"status\":\"PARTIALLY_ARRIVED\",\"pendingPayableAmount\":25.0}],"
                + "\"page\":0,\"size\":200,\"total\":1}")
            .formatted(arrivalId);
    arrivalDetailPayload =
        ("{\"head\":{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierName\":\"供应商甲\","
                + "\"currency\":\"USD\"},\"payableConfirmations\":[{\"id\":\"%s\",\"confirmationNo\":\"PC-1\","
                + "\"confirmationStatus\":\"PENDING_FINANCE_CONFIRM\",\"confirmationAmount\":25.0,"
                + "\"currency\":\"USD\",\"arrivedQty\":2,\"unitPrice\":12.5,\"effectiveAmount\":0}]}")
            .formatted(arrivalId, confirmationId);

    var result = as(() -> payables.importFromPurchaseArrivals("session", actor()));
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.warnings()).isEmpty();

    var payable = one("select * from finance_payable");
    assertThat(payable.get("source", String.class)).isEqualTo("PURCHASE_RECEIPT");
    assertThat(payable.get("source_system", String.class)).isEqualTo("trade");
    assertThat(payable.get("source_reference", String.class)).isEqualTo("PC-1");
    assertThat(payable.get("counterparty_name", String.class)).isEqualTo("供应商甲");
    assertThat(payable.get("currency", String.class)).isEqualTo("USD");
    assertThat(payable.get("amount", BigDecimal.class)).isEqualByComparingTo("25.0000");
    // 上游确认写回：本服务的应付单号被带回去了。
    assertThat(upstreamConfirmations)
        .as("财务确认必须写回上游（上游把这一动作单列为 trade.purchase.finance 权限）")
        .containsEntry(confirmationId.toString(), payable.get("payable_no", String.class));
  }

  @Test
  void 重复导入同一个到货事实不会长出第二张单() {
    enablePolicy("PURCHASE_RECEIPT", 0);
    UUID arrivalId = UUID.randomUUID();
    arrivalsPayload =
        ("{\"rows\":[{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierName\":\"供应商甲\","
                + "\"unitPrice\":12.5,\"currency\":\"USD\",\"status\":\"PARTIALLY_ARRIVED\"}],"
                + "\"page\":0,\"size\":200,\"total\":1}")
            .formatted(arrivalId);
    arrivalDetailPayload =
        ("{\"head\":{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierName\":\"供应商甲\","
                + "\"currency\":\"USD\"},\"payableConfirmations\":[{\"id\":\"%s\",\"confirmationNo\":\"PC-1\","
                + "\"confirmationStatus\":\"PENDING_FINANCE_CONFIRM\",\"confirmationAmount\":25.0,"
                + "\"currency\":\"USD\",\"arrivedQty\":2,\"unitPrice\":12.5,\"effectiveAmount\":0}]}")
            .formatted(arrivalId, UUID.randomUUID());

    as(() -> payables.importFromPurchaseArrivals("session", actor()));
    var second = as(() -> payables.importFromPurchaseArrivals("session", actor()));
    assertThat(second.imported()).isZero();
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(count("select count(*) from finance_payable")).isEqualTo(1);
  }

  @Test
  void 缺单价快照的上游事实导不进来且必须说清原因() {
    enablePolicy("PURCHASE_RECEIPT", 0);
    UUID arrivalId = UUID.randomUUID();
    arrivalsPayload =
        ("{\"rows\":[{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierName\":\"供应商甲\","
                + "\"currency\":\"USD\"}],\"page\":0,\"size\":200,\"total\":1}")
            .formatted(arrivalId);
    arrivalDetailPayload =
        ("{\"head\":{\"id\":\"%s\",\"trackingNo\":\"PO-ARR-1\",\"supplierName\":\"供应商甲\","
                + "\"currency\":\"USD\"},\"payableConfirmations\":[{\"id\":\"%s\",\"confirmationNo\":\"PC-2\","
                + "\"confirmationStatus\":\"PENDING_FINANCE_CONFIRM\",\"confirmationAmount\":25.0,"
                + "\"currency\":\"USD\",\"arrivedQty\":2,\"unitPrice\":null,\"effectiveAmount\":0}]}")
            .formatted(arrivalId, UUID.randomUUID());

    var result = as(() -> payables.importFromPurchaseArrivals("session", actor()));
    assertThat(result.imported()).isZero();
    assertThat(result.warnings()).anyMatch(w -> w.contains("合同依据"));
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  @Test
  void 上游读不到时导入整体失败而不是返回零条() {
    enablePolicy("PURCHASE_RECEIPT", 0);
    upstreamDown = true;
    assertThatThrownBy(() -> as(() -> payables.importFromPurchaseArrivals("session", actor())))
        .hasStackTraceContaining("503")
        .hasStackTraceContaining("读不到");
  }

  // ================================================================ 导入：物流费用（10.4.1.1 头程发运单）

  @Test
  void 从头程发运单导入物流费用应付() {
    enablePolicy("LOGISTICS_FEE", 15);
    firstMilePayload =
        ("{\"rows\":[{\"id\":\"%s\",\"shipmentCode\":\"FM-1\",\"carrierName\":\"顺丰国际\","
                + "\"currency\":\"USD\",\"freightCost\":100.0,\"insuranceCost\":5.0,\"handlingCost\":0,"
                + "\"totalCost\":105.0,\"status\":\"DISPATCHED\"}],\"page\":0,\"size\":200,\"total\":1}")
            .formatted(UUID.randomUUID());

    var result = as(() -> payables.importFromFirstMile("session", actor()));
    assertThat(result.imported()).isEqualTo(1);
    var payable = one("select * from finance_payable");
    assertThat(payable.get("source", String.class)).isEqualTo("LOGISTICS_FEE");
    assertThat(payable.get("counterparty_type", String.class)).isEqualTo("CARRIER");
    assertThat(payable.get("counterparty_name", String.class)).isEqualTo("顺丰国际");
    // 运费 + 保险费 + 处理费（关税估算不算）。
    assertThat(payable.get("amount", BigDecimal.class)).isEqualByComparingTo("105.0000");
  }

  @Test
  void 没有承运商或费用为零的头程发运单不产生应付() {
    enablePolicy("LOGISTICS_FEE", 0);
    firstMilePayload =
        ("{\"rows\":[{\"id\":\"%s\",\"shipmentCode\":\"FM-2\",\"carrierName\":\"\","
                + "\"currency\":\"USD\",\"freightCost\":0,\"insuranceCost\":0,\"handlingCost\":0,"
                + "\"totalCost\":0,\"status\":\"DRAFT\"}],\"page\":0,\"size\":200,\"total\":1}")
            .formatted(UUID.randomUUID());
    var result = as(() -> payables.importFromFirstMile("session", actor()));
    assertThat(result.imported()).isZero();
    assertThat(result.warnings()).anyMatch(w -> w.contains("0 元应付"));
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  // ================================================================ 导入：广告费（14.3.1.1 活动报表）

  @Test
  void 从活动报表导入广告费应付且取的是花费不是销售额() {
    enablePolicy("ADVERTISING_FEE", 7);
    adReportPayload =
        "{\"items\":[{\"dimension\":\"CAMPAIGN\",\"dimensionKey\":\"C-1\",\"dimensionLabel\":\"活动一\","
            + "\"currency\":\"USD\",\"platformName\":\"Amazon\",\"shopName\":\"美国店\","
            + "\"externalCampaignId\":\"EXT-1\",\"spend\":88.8,\"sales\":500.0}],"
            + "\"page\":0,\"size\":200,\"total\":1}";
    var result = as(() -> payables.importFromAdSpend("session", actor()));
    assertThat(result.imported()).isEqualTo(1);
    var payable = one("select * from finance_payable");
    assertThat(payable.get("source", String.class)).isEqualTo("ADVERTISING_FEE");
    assertThat(payable.get("counterparty_type", String.class)).isEqualTo("PLATFORM");
    assertThat(payable.get("source_reference", String.class)).isEqualTo("EXT-1");
    assertThat(payable.get("amount", BigDecimal.class)).isEqualByComparingTo("88.8000");
  }

  @Test
  void 花费为零的广告行不产生应付() {
    enablePolicy("ADVERTISING_FEE", 0);
    adReportPayload =
        "{\"items\":[{\"dimension\":\"CAMPAIGN\",\"dimensionKey\":\"C-2\",\"currency\":\"USD\","
            + "\"platformName\":\"Amazon\",\"externalCampaignId\":\"EXT-2\",\"spend\":0}],"
            + "\"page\":0,\"size\":200,\"total\":1}";
    var result = as(() -> payables.importFromAdSpend("session", actor()));
    assertThat(result.imported()).isZero();
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  // ================================================================ 预付款（独立聚合）

  @Test
  void 预付款在另一张表上且没有已付金额字段() {
    enablePrepaymentPolicy();
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        "PO-2024-001",
                        "定金"),
                    actor()));
    assertThat(created.amount()).isEqualByComparingTo("1000.0000");
    assertThat(created.allocatedAmount()).isEqualByComparingTo("0.0000");
    assertThat(created.unallocatedAmount()).isEqualByComparingTo("1000.0000");
    assertThat(created.status()).isEqualTo(PrepaymentStatus.OPEN);
    // 预付款**不在**应付单表里。
    assertThat(count("select count(*) from finance_payable")).isZero();
    assertThat(count("select count(*) from finance_prepayment")).isEqualTo(1);
  }

  @Test
  void 预付款登记也需要一份已批准的预付政策() {
    var input =
        new FinancePrepaymentInput(
            CounterpartyType.SUPPLIER,
            null,
            "供应商甲",
            "USD",
            new BigDecimal("1000"),
            LocalDate.now(ZoneOffset.UTC).plusDays(10),
            null,
            null);
    assertThatThrownBy(() -> as(() -> payables.createPrepayment(input, actor())))
        .hasStackTraceContaining("应付确认政策");
  }

  @Test
  void 预付款抵扣登记后可抵扣余额按币种分开() {
    enablePrepaymentPolicy();
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        "PO-2024-001",
                        null),
                    actor()));
    var detail =
        as(
            () ->
                payables.allocatePrepayment(
                    created.id(),
                    new FinancePrepaymentAllocationInput(
                        PrepaymentTargetType.PURCHASE_ORDER,
                        null,
                        "PO-2024-001",
                        new BigDecimal("400"),
                        "定金"),
                    actor()));
    assertThat(detail.prepayment().allocatedAmount()).isEqualByComparingTo("400.0000");
    assertThat(detail.prepayment().unallocatedAmount()).isEqualByComparingTo("600.0000");
    assertThat(detail.prepayment().status()).isEqualTo(PrepaymentStatus.PARTIALLY_ALLOCATED);
    assertThat(detail.allocations()).hasSize(1);
  }

  @Test
  void 预付款抵扣超过可抵扣余额被拒() {
    enablePrepaymentPolicy();
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        null,
                        null),
                    actor()));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        payables.allocatePrepayment(
                            created.id(),
                            new FinancePrepaymentAllocationInput(
                                PrepaymentTargetType.PURCHASE_ORDER,
                                null,
                                "PO-2024-002",
                                new BigDecimal("1200"),
                                null),
                            actor())))
        .hasStackTraceContaining("超过可抵扣余额");
  }

  @Test
  void 预付款抵扣不改应付单的已付金额() {
    enablePolicy("MANUAL", 0);
    enablePrepaymentPolicy();
    var payable = createManual(null);
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        null,
                        null),
                    actor()));
    as(
        () ->
            payables.allocatePrepayment(
                created.id(),
                new FinancePrepaymentAllocationInput(
                    PrepaymentTargetType.PAYABLE,
                    payable.id(),
                    null,
                    new BigDecimal("400"),
                    null),
                actor()));
    var head = one("select status, paid_amount, unpaid_amount from finance_payable");
    assertThat(head.get("status", String.class))
        .as("预付款抵扣不是付款：应付的已付金额只有一个来源 —— 12.2.2.1 付款")
        .isEqualTo("OPEN");
    assertThat(head.get("paid_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(head.get("unpaid_amount", BigDecimal.class)).isEqualByComparingTo("20.0000");
  }

  @Test
  void 已有抵扣的预付款不能作废() {
    enablePrepaymentPolicy();
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        null,
                        null),
                    actor()));
    as(
        () ->
            payables.allocatePrepayment(
                created.id(),
                new FinancePrepaymentAllocationInput(
                    PrepaymentTargetType.PURCHASE_ORDER,
                    null,
                    "PO-2024-003",
                    new BigDecimal("400"),
                    null),
                actor()));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        payables.voidPrepayment(
                            created.id(), new FinancePrepaymentVoidInput("试试作废"), actor())))
        .hasStackTraceContaining("12.2.2.1");
  }

  @Test
  void 预付款作废必须写理由() {
    enablePrepaymentPolicy();
    var created =
        as(
            () ->
                payables.createPrepayment(
                    new FinancePrepaymentInput(
                        CounterpartyType.SUPPLIER,
                        null,
                        "供应商甲",
                        "USD",
                        new BigDecimal("1000"),
                        LocalDate.now(ZoneOffset.UTC).plusDays(10),
                        null,
                        null),
                    actor()));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        payables.voidPrepayment(
                            created.id(), new FinancePrepaymentVoidInput(""), actor())))
        .hasStackTraceContaining("理由");
    var voided =
        as(
            () ->
                payables.voidPrepayment(
                    created.id(), new FinancePrepaymentVoidInput("登记错了"), actor()));
    assertThat(voided.status()).isEqualTo(PrepaymentStatus.VOID);
  }

  // ================================================================ 作废

  @Test
  void 作废必须写理由且作废后整单冻结() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID id = one("select id from finance_payable").get(0, UUID.class);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        payables.voidPayable(
                            id, new FinancePayableVoidInput(""), actor())))
        .hasStackTraceContaining("理由");
    var voided =
        as(
            () ->
                payables.voidPayable(id, new FinancePayableVoidInput("供应商不供货了"), actor()));
    assertThat(voided.status()).isEqualTo(PayableStatus.VOID);
    assertThat(dbError("update finance_payable set note = '改一下' where id = ?", id))
        .contains("终态");
  }

  // ================================================================ 时间字段

  @Test
  void 确认时刻写回的值与写进去的一致而不是差八小时() {
    enablePolicy("MANUAL", 0);
    var payable = createManual(null);
    var stored = one("select confirmed_at, created_at from finance_payable");
    // 断言写回的值（timestamptz 绑定用 OffsetDateTime + ?::timestamptz）。
    assertThat(stored.get("confirmed_at", OffsetDateTime.class).toInstant())
        .isEqualTo(payable.confirmedAt().toInstant());
    assertThat(stored.get("created_at", OffsetDateTime.class).toInstant())
        .isEqualTo(payable.createdAt().toInstant());
  }

  // ================================================================ HTTP 层

  @Test
  void 端点命名空间与认证() throws Exception {
    http.perform(get("/finance/payables")).andExpect(status().isUnauthorized());
    http.perform(get("/finance/prepayments")).andExpect(status().isUnauthorized());
    http.perform(get("/finance/payables/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payables/unpaid").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payables/policies").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payables/options").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 非法来源取值报四百而不是把枚举名糊给用户() throws Exception {
    http.perform(
            get("/finance/payables").param("source", "NOT_A_SOURCE").header("Authorization", "session"))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 夹具工具

  private FinancePayableInput manualInput(String extraJson) {
    return inputOf(
        """
        {"counterpartyType":"SUPPLIER","counterpartyName":"供应商甲","currency":"USD"%s,
         "lines":[{"description":"明细一","amount":12.5}]}
        """
            .formatted(extraJson == null ? "" : extraJson));
  }

  private FinancePayableInput inputOf(String raw) {
    try {
      return json.readValue(raw, FinancePayableInput.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
