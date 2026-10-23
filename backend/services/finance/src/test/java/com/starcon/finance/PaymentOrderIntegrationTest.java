package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PaymentOrderModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.2.2.1 付款单与审批的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 12.2.2.1 里那两句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「<b>发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、分批安排，
 *       走分级审批</b>」⇒ 五件事逐条落地：应付明细（精确到行）、付款账户（引用 + 快照）、
 *       币种与汇率（快照冻结）、付款方式、分批安排（分批合计 = 单头金额）；
 *   <li>「输出：<b>付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录</b>」⇒
 *       六态状态机齐全，但 <b>EXECUTING / PAID / FAILED 本模块写不出来</b>
 *       （表级 CHECK + 触发器钉死，写入权属 12.2.2.2）；
 *   <li>「<b>走分级审批</b>」⇒ 审批真的走 identity 的 17 审批：
 *       提交 ⇒ 按金额阈值分支选起始节点 ⇒ 读回链路 ⇒ 镜像成审批记录；
 *   <li>「<b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批」
 *       ⇒ 审批流未注册时**拒绝提交**（不按「未注册即默认放行」处理）。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID supplier = UUID.randomUUID();
  static final UUID flowId = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of("finance.paymentorder.read", "finance.paymentorder.create", "finance.paymentorder.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，
   * 于是 {@code 20.0000} 变成 {@code 20.0} —— 断言就分不出「金额是四位小数」和「金额丢了一位」。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(
              com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** identity 侧审批实例的当前状态：测试靠它切换「还在审 / 已批 / 已驳回」三种结局。 */
  static final AtomicReference<String> instanceStatus = new AtomicReference<>("PENDING");

  /** identity 侧审批实例 id。 */
  static final AtomicReference<UUID> instanceId = new AtomicReference<>(UUID.randomUUID());

  /** identity 侧是否已注册生效审批流。 */
  static final AtomicReference<Boolean> flowRegistered = new AtomicReference<>(true);

  /** identity 侧审批动作的**稳定** id（上游同一次动作每次读回来都是同一个 id）。 */
  static final UUID APPROVE_ACTION_ID = UUID.randomUUID();

  /** catalog 侧的汇率表：key = "base|quote"。 */
  static final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

  /** stub 收到的最后一个提交请求体（用来断言 sourceSystem / sourceReferenceId）。 */
  static final AtomicReference<Map<String, Object>> lastSubmitBody = new AtomicReference<>();

  static HttpServer upstream;

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
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      stubAccess();
      stubApprovalFlow();
      stubApprovalSubmit();
      stubApprovalRecord();
      stubExchangeRate();
      upstream.setExecutor(Executors.newFixedThreadPool(4));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** identity：/internal/access（租户与权限的解析入口）。 */
  static void stubAccess() {
    upstream.createContext(
        "/internal/access",
        e -> {
          var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
          if (access == null) {
            e.sendResponseHeaders(401, -1);
            e.close();
            return;
          }
          write(e, 200, json.writeValueAsBytes(access));
        });
  }

  /** identity：GET /identity/approval-flows/active（生效审批流 + 节点结构）。 */
  static void stubApprovalFlow() {
    upstream.createContext(
        "/identity/approval-flows/active",
        e -> {
          if (!Boolean.TRUE.equals(flowRegistered.get())) {
            write(e, 404, "{\"message\":\"该单据类型尚未注册生效审批流\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              Map.of(
                  "flow",
                  Map.of(
                      "id", flowId.toString(),
                      "flowName", "付款审批流",
                      "status", "ACTIVE",
                      "documentTypeCode", "PAYMENT_ORDER"),
                  "nodes",
                  List.of(
                      Map.of(
                          "nodeKey", "manager",
                          "nodeName", "经理审批",
                          "nodeOrder", 1,
                          "stageIndex", 1,
                          "approvalMode", "SERIAL",
                          "approverType", "ROLE",
                          "approverLabel", "财务经理"),
                      Map.of(
                          "nodeKey", "cfo",
                          "nodeName", "财务总监复核",
                          "nodeOrder", 2,
                          "stageIndex", 2,
                          "approvalMode", "PARALLEL",
                          "approverType", "ROLE",
                          "approverLabel", "CFO")));
          write(e, 200, json.writeValueAsBytes(body));
        });
  }

  /** identity：POST /identity/approval-instances（提交审批）。 */
  static void stubApprovalSubmit() {
    upstream.createContext(
        "/identity/approval-instances",
        e -> {
          if (!"test-key".equals(e.getRequestHeaders().getFirst("X-Internal-Key"))) {
            write(e, 403, "{\"message\":\"拒绝内部调用\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastSubmitBody.set(json.readValue(body, Map.class));
          var id = instanceId.get();
          var result =
              Map.of(
                  "id", id.toString(),
                  "instanceNo", "AP-20261024-0001",
                  "flowId", flowId.toString(),
                  "currentNodeKey", "manager",
                  "currentNodeName", "经理审批",
                  "applicantId", actors.get("session").userId().toString(),
                  "routedBy", "AMOUNT_BRANCH",
                  "submittedAt", "2026-10-24T01:00:00Z");
          write(e, 200, json.writeValueAsBytes(result));
        });
  }

  /** identity：GET /identity/approval-records/{id}（审批链路读回）。 */
  static void stubApprovalRecord() {
    upstream.createContext(
        "/identity/approval-records/",
        e -> {
          var id = instanceId.get();
          var status = instanceStatus.get();
          var finished = "APPROVED".equals(status) || "REJECTED".equals(status);
          var timeline = new ArrayList<Map<String, Object>>();
          if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
            var approved = "APPROVED".equals(status);
            timeline.add(
                Map.of(
                    // ⚠️ actionId 必须**稳定**：上游的同一个动作每次读回来都是同一个 id，
                    //    镜像的去重（按 action_id）才成立。
                    "actionId", APPROVE_ACTION_ID.toString(),
                    "action", approved ? "APPROVE" : "REJECT",
                    "nodeKey", "manager",
                    "nodeName", "经理审批",
                    "nodeOrder", 1,
                    "actorId", UUID.randomUUID().toString(),
                    "actorName", "财务经理",
                    "comment", approved ? "同意付款" : "金额偏大，请复核后再提交",
                    "decidedAt", "2026-10-24T02:00:00Z",
                    "dwellSeconds", 3600));
          }
          var body =
              Map.of(
                  "instance",
                  Map.of(
                      "id", id.toString(),
                      "instanceNo", "AP-20261024-0001",
                      "status", status,
                      "subjectTitle", "付款单",
                      "amount", new BigDecimal("100.0000"),
                      "currencyCode", "USD",
                      "currentNodeKey", finished ? "" : "manager",
                      "currentNodeName", finished ? "" : "经理审批",
                      "submittedAt", "2026-10-24T01:00:00Z",
                      "finishedAt", finished ? "2026-10-24T02:00:00Z" : null),
                  "timeline", timeline);
          write(e, 200, json.writeValueAsBytes(body));
        });
  }

  /** catalog：GET /catalog/exchange-rates/latest（汇率事实）。 */
  static void stubExchangeRate() {
    upstream.createContext(
        "/catalog/exchange-rates/latest",
        e -> {
          var query = e.getRequestURI().getQuery();
          String base = param(query, "baseCurrency");
          String quote = param(query, "quoteCurrency");
          var rate = rates.get(base + "|" + quote);
          if (rate == null) {
            write(e, 404, "{\"message\":\"没有这条汇率\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "baseCurrency", base,
                  "quoteCurrency", quote,
                  "rate", rate,
                  "effectiveDate", "2026-10-20",
                  "sourceCode", "MANUAL",
                  "sourceName", "手工录入",
                  "display", "1 " + base + " = " + rate + " " + quote);
          write(e, 200, json.writeValueAsBytes(body));
        });
  }

  static String param(String query, String key) {
    if (query == null) return "";
    for (var part : query.split("&")) {
      var kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
    }
    return "";
  }

  static void write(com.sun.net.httpserver.HttpExchange e, int code, byte[] body) {
    try {
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(code, body.length);
      e.getResponseBody().write(body);
    } catch (Exception ignored) {
      // stub 只服务于断言，异常不掩盖被测行为
    } finally {
      e.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired PaymentOrderService orders;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库 + 播种。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payment_approval_record, finance_payment_order_event,"
              + " finance_payment_order_installment, finance_payment_order_line,"
              + " finance_payment_order, finance_payment_plan_event, finance_payment_reminder,"
              + " finance_payment_plan, finance_payment_batch, finance_payment_milestone,"
              + " finance_payment_milestone_rule, finance_payment_term_rule,"
              + " finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy"
              + " restart identity cascade");
    }
    instanceStatus.set("PENDING");
    flowRegistered.set(true);
    rates.clear();
    lastSubmitBody.set(null);
    seedPayable();
  }

  /** 种一张带两条明细的应付单（金额 100 + 50，均未付）。 */
  void seedPayable() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_payable(id,tenant_id,payable_no,source,source_system,"
                  + " counterparty_type,counterparty_ref,counterparty_name,currency,due_date,"
                  + " confirmed_at,status,created_at,updated_at)"
                  + " values(?,?,'AP-1','MANUAL','manual','SUPPLIER',?,'测试供应商','USD',?::date,"
                  + " ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              payableId(),
              tenant,
              supplier,
              LocalDate.now(ZoneOffset.UTC).plusDays(30),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,"
                  + " amount,paid_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'第一批货',100.0000,0,?::timestamptz,?::timestamptz)",
              payableLine1(),
              tenant,
              payableId(),
              now,
              now);
          sql.execute(
              "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,"
                  + " amount,paid_amount,created_at,updated_at)"
                  + " values(?,?,?,2,'第二批货',50.0000,0,?::timestamptz,?::timestamptz)",
              payableLine2(),
              tenant,
              payableId(),
              now,
              now);
          // 12.1.2.1 的守卫：应付单必须走 DRAFT → OPEN（确认时才校验明细行与合计）。
          sql.execute(
              "update finance_payable set status = 'OPEN' where id = ?", payableId());
          return null;
        });
  }

  UUID payableId() {
    return UUID.nameUUIDFromBytes(("po-payable-" + tenant).getBytes());
  }

  UUID payableLine1() {
    return UUID.nameUUIDFromBytes(("po-line1-" + tenant).getBytes());
  }

  UUID payableLine2() {
    return UUID.nameUUIDFromBytes(("po-line2-" + tenant).getBytes());
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行。 */
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

  /** 造一份付款单入参（默认：明细 1 付 100）。 */
  FinancePaymentOrderInput input(BigDecimal amount, UUID... payableLines) {
    var lines = new ArrayList<FinancePaymentOrderLineInput>();
    for (var id : payableLines) {
      lines.add(new FinancePaymentOrderLineInput(id, "明细", "USD", amount));
    }
    return new FinancePaymentOrderInput(
        PaymentCounterpartyType.SUPPLIER,
        supplier,
        "测试供应商",
        "USD",
        amount.multiply(BigDecimal.valueOf(payableLines.length)),
        null,
        null,
        PaymentMethod.BANK_TRANSFER,
        null,
        null,
        null,
        null,
        "测试夹具",
        lines,
        null);
  }

  // ================================================================
  // 一、付款申请：选择应付明细
  // ================================================================

  @Test
  void 建单必须精确到应付明细行且金额与明细合计一致() {
    // 明细付 100，但单头写 90 ⇒ 拒绝（总账必须等于明细）。
    var mismatched =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("90.00"),
            null,
            null,
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "金额错配",
            List.of(
                new FinancePaymentOrderLineInput(
                    payableLine1(), "明细", "USD", new BigDecimal("100.00"))),
            null);
    assertThatThrownBy(() -> as(() -> orders.create(mismatched, actor())))
        .hasStackTraceContaining("与明细合计");

    // 一条明细都不选 ⇒ 拒绝。
    assertThatThrownBy(
            () ->
                as(() -> orders.create(
                    new FinancePaymentOrderInput(
                        PaymentCounterpartyType.SUPPLIER,
                        supplier,
                        "测试供应商",
                        "USD",
                        new BigDecimal("100"),
                        null,
                        null,
                        PaymentMethod.BANK_TRANSFER,
                        null,
                        null,
                        null,
                        null,
                        "无明细",
                        List.of(),
                        null),
                    actor())))
        .hasStackTraceContaining("必须选择至少一条应付明细");
  }

  @Test
  void 付款金额不得超过应付明细的未付余额() {
    assertThatThrownBy(() -> as(() -> orders.create(input(new BigDecimal("200.00"), payableLine1()), actor())))
        .hasStackTraceContaining("超出未付余额");
  }

  @Test
  void 币种不一致的应付明细不能被付款() {
    var eur = new FinancePaymentOrderInput(
        PaymentCounterpartyType.SUPPLIER,
        supplier,
        "测试供应商",
        "EUR",
        new BigDecimal("100"),
        null,
        null,
        PaymentMethod.BANK_TRANSFER,
        null,
        null,
        null,
        null,
        "币种错配",
        List.of(new FinancePaymentOrderLineInput(payableLine1(), "明细", "EUR", new BigDecimal("100"))),
        null);
    assertThatThrownBy(() -> as(() -> orders.create(eur, actor()))).hasStackTraceContaining("不一致");
  }

  @Test
  void 一张付款单只能付给一个对象() {
    // 第二张应付属于另一个供应商 ⇒ 拒绝（要分单）。
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var otherSupplier = UUID.randomUUID();
    var otherPayable = UUID.randomUUID();
    var otherLine = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_payable(id,tenant_id,payable_no,source,source_system,"
                  + " counterparty_type,counterparty_ref,counterparty_name,currency,due_date,"
                  + " confirmed_at,status,created_at,updated_at)"
                  + " values(?,?,'AP-2','MANUAL','manual','SUPPLIER',?,'另一个供应商','USD',?::date,"
                  + " ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              otherPayable,
              tenant,
              otherSupplier,
              LocalDate.now(ZoneOffset.UTC).plusDays(30),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,"
                  + " amount,paid_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'别的货',80.0000,0,?::timestamptz,?::timestamptz)",
              otherLine,
              tenant,
              otherPayable,
              now,
              now);
          sql.execute(
              "update finance_payable set status = 'OPEN' where id = ?", otherPayable);
          return null;
        });
    var two = new FinancePaymentOrderInput(
        PaymentCounterpartyType.SUPPLIER,
        supplier,
        "测试供应商",
        "USD",
        new BigDecimal("180"),
        null,
        null,
        PaymentMethod.BANK_TRANSFER,
        null,
        null,
        null,
        null,
        "跨对象",
        List.of(
            new FinancePaymentOrderLineInput(payableLine1(), "明细1", "USD", new BigDecimal("100")),
            new FinancePaymentOrderLineInput(otherLine, "明细2", "USD", new BigDecimal("80"))),
        null);
    assertThatThrownBy(() -> as(() -> orders.create(two, actor()))).hasStackTraceContaining("不同的付款对象");
  }

  @Test
  void 建单成功落草稿并写事件与明细() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.DRAFT);
    assertThat(order.orderNo()).startsWith("PO-");
    assertThat(order.approvalStatus()).isEqualTo(ApprovalStatus.NOT_SUBMITTED);
    assertThat(count("select count(*) from finance_payment_order")).isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_order_line")).isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_order_event where event_type='CREATED'"))
        .isEqualTo(1);
    // 明细快照冻结了建单那一刻的应付事实。
    var line = one("select * from finance_payment_order_line");
    assertThat(line.get("payable_amount_snapshot", BigDecimal.class))
        .isEqualByComparingTo("100.0000");
    assertThat(line.get("payable_paid_snapshot", BigDecimal.class)).isEqualByComparingTo("0");
  }

  @Test
  void 同一条应付明细行不能被两张在册付款单占用() {
    as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    // 第二张单占用同一明细 ⇒ 数据层挡住（部分唯一索引）。
    assertThatThrownBy(() -> as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor())))
        .hasStackTraceContaining("uq_finance_payment_order_line_active_payable_line");
  }

  // ================================================================
  // 二、币种与汇率快照
  // ================================================================

  @Test
  void 汇率快照建单时冻结且本位币金额是生成列() {
    var withRate =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("100.00"),
            "CNY",
            new BigDecimal("7.12340000"),
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "带汇率",
            List.of(
                new FinancePaymentOrderLineInput(payableLine1(), "明细", "USD", new BigDecimal("100"))),
            null);
    var order = as(() -> orders.create(withRate, actor()));
    assertThat(order.baseCurrency()).isEqualTo("CNY");
    assertThat(order.exchangeRate()).isEqualByComparingTo("7.12340000");
    // 生成列：round(100.0000 * 7.12340000, 4) = 712.3400
    assertThat(order.baseAmount()).isEqualByComparingTo("712.3400");
    var stored = one("select base_amount from finance_payment_order");
    assertThat(stored.get("base_amount", BigDecimal.class)).isEqualByComparingTo("712.3400");
  }

  @Test
  void 本位币与汇率必须成对出现() {
    var onlyCurrency =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("100.00"),
            "CNY",
            null,
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "只填本位币",
            List.of(
                new FinancePaymentOrderLineInput(payableLine1(), "明细", "USD", new BigDecimal("100"))),
            null);
    assertThatThrownBy(() -> as(() -> orders.create(onlyCurrency, actor())))
        .hasStackTraceContaining("必须成对提供");
  }

  @Test
  void 汇率快照只在上游登记反方向时取倒数并如实标注() {
    rates.put("CNY|USD", new BigDecimal("0.14000000"));
    var view = as(() -> orders.exchangeRate("USD", "CNY", "session"));
    assertThat(view.inverted()).isTrue();
    assertThat(view.rate()).isEqualByComparingTo("7.14285714");
    assertThat(view.display()).contains("推导");
  }

  @Test
  void 两个方向都查不到汇率时显式失败而不是回退() {
    assertThatThrownBy(() -> as(() -> orders.exchangeRate("USD", "JPY", "session")))
        .hasStackTraceContaining("查不到");
  }

  // ================================================================
  // 三、分批安排
  // ================================================================

  @Test
  void 分批合计必须等于单头金额() {
    var bad =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("100.00"),
            null,
            null,
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "分批不齐",
            List.of(
                new FinancePaymentOrderLineInput(payableLine1(), "明细", "USD", new BigDecimal("100"))),
            List.of(
                new FinancePaymentOrderInstallmentInput(
                    1, LocalDate.now(ZoneOffset.UTC).plusDays(10), "USD", new BigDecimal("60"))));
    assertThatThrownBy(() -> as(() -> orders.create(bad, actor()))).hasStackTraceContaining("分批合计");
  }

  @Test
  void 分批安排真落库且顺序稳定() {
    var today = LocalDate.now(ZoneOffset.UTC);
    var good =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("100.00"),
            null,
            null,
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "两批",
            List.of(
                new FinancePaymentOrderLineInput(payableLine1(), "明细", "USD", new BigDecimal("100"))),
            List.of(
                new FinancePaymentOrderInstallmentInput(
                    1, today.plusDays(10), "USD", new BigDecimal("40")),
                new FinancePaymentOrderInstallmentInput(
                    2, today.plusDays(20), "USD", new BigDecimal("60"))));
    var order = as(() -> orders.create(good, actor()));
    var installments = as(() -> orders.detail(order.id())).installments();
    assertThat(installments).hasSize(2);
    assertThat(installments.get(0).seq()).isEqualTo(1);
    assertThat(installments.get(0).amount()).isEqualByComparingTo("40.0000");
    assertThat(installments.get(1).seq()).isEqualTo(2);
    assertThat(installments.get(1).plannedPaidDate()).isEqualTo(today.plusDays(20));
    // 分批的执行态同样归 12.2.2.2。
    assertThat(installments.get(0).executionAvailable()).isFalse();
    assertThat(installments.get(0).executionOwnerModule()).isEqualTo("12.2.2.2");
  }

  // ================================================================
  // 四、已付写不出来（12.2.2.2 未交付）
  // ================================================================

  @Test
  void 已付与执行中本模块写不出来() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    for (var target : List.of("EXECUTING", "PAID", "FAILED")) {
      assertThatThrownBy(
              () ->
                  as(
                      () ->
                          sql.execute(
                              "update finance_payment_order set status = ? where id = ?",
                              target,
                              order.id())))
          .as("付款单的状态写不进 " + target)
          .hasStackTraceContaining("写入权属 12.2.2.2");
    }
    // 状态原样。
    assertThat(one("select status from finance_payment_order").get(0, String.class))
        .isEqualTo("DRAFT");
  }

  @Test
  void 分批的已付同样写不出来() {
    var today = LocalDate.now(ZoneOffset.UTC);
    var good =
        new FinancePaymentOrderInput(
            PaymentCounterpartyType.SUPPLIER,
            supplier,
            "测试供应商",
            "USD",
            new BigDecimal("100.00"),
            null,
            null,
            PaymentMethod.BANK_TRANSFER,
            null,
            null,
            null,
            null,
            "一批",
            List.of(
                new FinancePaymentOrderLineInput(payableLine1(), "明细", "USD", new BigDecimal("100"))),
            List.of(
                new FinancePaymentOrderInstallmentInput(
                    1, today.plusDays(10), "USD", new BigDecimal("100"))));
    var order = as(() -> orders.create(good, actor()));
    var installmentId = as(() -> orders.detail(order.id())).installments().get(0).id();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update finance_payment_order_installment set status = 'PAID' where id = ?",
                            installmentId)))
        .hasStackTraceContaining("写入权属 12.2.2.2");
  }

  @Test
  void 付款单不可删除() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    assertThatThrownBy(
            () -> as(() -> sql.execute("delete from finance_payment_order where id = ?", order.id())))
        .hasStackTraceContaining("不可删除");
  }

  // ================================================================
  // 五、分级审批（对接 17 审批）
  // ================================================================

  @Test
  void 提交审批后付款单转待审并回写审批实例() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    var result = as(() -> orders.submit(order.id(), actor(), "session"));
    assertThat(result.order().status()).isEqualTo(PaymentOrderStatus.PENDING_APPROVAL);
    assertThat(result.order().approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    assertThat(result.approvalInstanceId()).isEqualTo(instanceId.get());
    // 起始节点是 identity 按金额阈值分支选出来的，不是本模块选的。
    assertThat(result.approvalRoutedBy()).isEqualTo("AMOUNT_BRANCH");
    assertThat(result.approvalCurrentNodeKey()).isEqualTo("manager");
    // 提交那一刻也留了一条审批记录。
    assertThat(count("select count(*) from finance_payment_approval_record where action='SUBMIT'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_order_event where event_type='SUBMITTED'"))
        .isEqualTo(1);
    // 请求体里带的是**单据 id**（幂等键），不是租户号。
    assertThat(lastSubmitBody.get().get("sourceReferenceId")).isEqualTo(order.id().toString());
    assertThat(lastSubmitBody.get().get("sourceSystem")).isEqualTo("finance");
  }

  @Test
  void 未注册生效审批流时拒绝提交() {
    flowRegistered.set(false);
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    assertThatThrownBy(() -> as(() -> orders.submit(order.id(), actor(), "session")))
        .hasStackTraceContaining("尚未注册生效审批流");
    // 单据状态没动。
    assertThat(one("select status from finance_payment_order").get(0, String.class)).isEqualTo("DRAFT");
  }

  @Test
  void 同步审批结果为已批时付款单转已批() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.submit(order.id(), actor(), "session"));
    instanceStatus.set("APPROVED");
    var result = as(() -> orders.sync(order.id(), actor(), "session"));
    assertThat(result.order().status()).isEqualTo(PaymentOrderStatus.APPROVED);
    assertThat(result.order().approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(result.order().approvedBy()).isNotNull();
    assertThat(result.order().approvedAt()).isNotNull();
    // 审批动作被镜像成本地审批记录。
    assertThat(count("select count(*) from finance_payment_approval_record where action='APPROVE'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_order_event where event_type='APPROVED'"))
        .isEqualTo(1);
  }

  @Test
  void 同步审批结果为已驳回时付款单退回草稿() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.submit(order.id(), actor(), "session"));
    instanceStatus.set("REJECTED");
    var result = as(() -> orders.sync(order.id(), actor(), "session"));
    assertThat(result.order().status()).isEqualTo(PaymentOrderStatus.DRAFT);
    assertThat(result.order().approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
    assertThat(count("select count(*) from finance_payment_approval_record where action='REJECT'"))
        .isEqualTo(1);
    assertThat(
            count("select count(*) from finance_payment_order_event where event_type='RETURNED_TO_DRAFT'"))
        .isEqualTo(1);
  }

  @Test
  void 同步是幂等的不会灌出重复审批记录() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.submit(order.id(), actor(), "session"));
    instanceStatus.set("APPROVED");
    as(() -> orders.sync(order.id(), actor(), "session"));
    as(() -> orders.sync(order.id(), actor(), "session"));
    assertThat(count("select count(*) from finance_payment_approval_record")).isEqualTo(2);
  }

  @Test
  void 提交之后不可再改单且明细被冻结() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.submit(order.id(), actor(), "session"));
    assertThatThrownBy(() -> as(() -> orders.update(order.id(), input(new BigDecimal("50.00"), payableLine1()), actor())))
        .hasStackTraceContaining("不可再改");
    // 直连 SQL 改明细也一样被挡。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update finance_payment_order_line set amount = 1.0000 where order_id = ?",
                            order.id())))
        .hasStackTraceContaining("明细与分批不可再改");
  }

  @Test
  void 提交时刻写回逐位相等不偏八小时() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.submit(order.id(), actor(), "session"));
    var stored =
        one(
                "select submitted_at from finance_payment_order where id = ?", order.id())
            .get("submitted_at", OffsetDateTime.class);
    // jOOQ 读 timestamptz 出来是本机时区 ⇒ 先转 UTC 再比。
    var utc = stored.withOffsetSameInstant(ZoneOffset.UTC);
    var expected = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    assertThat(utc).isAfterOrEqualTo(expected).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
  }

  // ================================================================
  // 六、列表 / 能力声明
  // ================================================================

  @Test
  void 列表真分页真筛选且汇总按币种() {
    as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    as(() -> orders.create(input(new BigDecimal("50.00"), payableLine2()), actor()));
    var page = as(() -> orders.page(new PaymentOrderQuery(null, null, null, null, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.summaryByCurrency()).hasSize(1);
    assertThat(page.summaryByCurrency().get(0).currency()).isEqualTo("USD");
    assertThat(page.summaryByCurrency().get(0).amount()).isEqualByComparingTo("150.0000");
    // 筛选：只取草稿 ⇒ 两张都是草稿；取待审 ⇒ 零。
    assertThat(as(() -> orders.page(new PaymentOrderQuery(PaymentOrderStatus.DRAFT, null, null, null, null, null, null, 0, 50))).total())
        .isEqualTo(2);
    assertThat(as(() -> orders.page(new PaymentOrderQuery(PaymentOrderStatus.PENDING_APPROVAL, null, null, null, null, null, null, 0, 50))).total())
        .isEqualTo(0);
    // 真分页：每页 1 条 ⇒ 两页。
    assertThat(as(() -> orders.page(new PaymentOrderQuery(null, null, null, null, null, null, null, 0, 1))).items())
        .hasSize(1);
    assertThat(as(() -> orders.page(new PaymentOrderQuery(null, null, null, null, null, null, null, 1, 1))).items())
        .hasSize(1);
  }

  @Test
  void 能力声明如实报出付款执行与资金账户未交付() {
    var caps = as(() -> orders.capabilities());
    var execution =
        caps.capabilities().stream()
            .filter(c -> "12.2.2.2".equals(c.ownerModule()))
            .findFirst()
            .orElseThrow();
    assertThat(execution.available()).isFalse();
    assertThat(execution.ownerModule()).isEqualTo("12.2.2.2");
    assertThat(caps.downstreamGaps()).anyMatch(g -> "12.2.2.2".equals(g.module()) && !g.available());
    assertThat(caps.downstreamGaps()).anyMatch(g -> "12.7.1.1".equals(g.module()) && !g.available());
    // 审批那一条必须是「已接通」。
    var approval =
        caps.capabilities().stream()
            .filter(c -> "分级审批（对接 17 审批）".equals(c.name()))
            .findFirst()
            .orElseThrow();
    assertThat(approval.available()).isTrue();
  }

  @Test
  void 可选应付明细带未付余额与占用情况() {
    var order = as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    var options = as(() -> orders.payableLines(payableId()));
    assertThat(options).hasSize(2);
    var first = options.get(0);
    assertThat(first.unpaidAmount()).isEqualByComparingTo("100.0000");
    assertThat(first.lockedByOrderId()).isEqualTo(order.id());
    assertThat(first.lockedByOrderNo()).isEqualTo(order.orderNo());
    var second = options.get(1);
    assertThat(second.lockedByOrderId()).isNull();
    assertThat(second.unpaidAmount()).isEqualByComparingTo("50.0000");
  }

  // ================================================================ HTTP 层

  @Test
  void 端点真分页真筛选() throws Exception {
    as(() -> orders.create(input(new BigDecimal("100.00"), payableLine1()), actor()));
    http.perform(get("/finance/payment-orders").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
        .andExpect(jsonPath("$.summaryByCurrency[0].currency").value("USD"));
    http.perform(
            get("/finance/payment-orders")
                .header("Authorization", "session")
                .param("status", "PENDING_APPROVAL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(get("/finance/payment-orders/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities").isArray());
    http.perform(get("/finance/payment-orders/options").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  @Test
  void 非法状态取值给一句能展示的话() throws Exception {
    http.perform(
            get("/finance/payment-orders")
                .header("Authorization", "session")
                .param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest());
  }
}
