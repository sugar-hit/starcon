package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PaymentExecutionModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
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
 * 12.2.2.2 付款执行的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 12.2.2.2 里那两句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「<b>对接支付渠道/银企直连，发送付款指令，跟踪结果，失败重试与异常处理</b>」
 *       ⇒ 发送 / 查证 / 重发三件事逐条落地，且**渠道侧没有凭据时必须明确报「未配置」**，
 *       绝不能假装成功；</li>
 *   <li>「<b>处理汇率锁汇</b>」⇒ 锁汇记录真落库（锁的是单据冻结的汇率），付款成功后释放；</li>
 *   <li>「输出：<b>付款结果 + 渠道流水号 + 失败原因</b>」⇒ 三样都要落库可查；</li>
 *   <li>「<b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故」
 *       ⇒ 本文件最重的一条：
 *       <b>超时后查证发现已成功 ⇒ 不重发</b>（断言 POST 次数仍然是 1）。</li>
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentExecutionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID supplier = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of(
          "finance.paymentexecution.read",
          "finance.paymentexecution.create",
          "finance.paymentexecution.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成
   * {@code double}，于是 {@code 100.0000} 变成 {@code 100.0} —— 断言就分不出
   * 「金额是四位小数」和「金额丢了一位」。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 渠道的发送行为：SUCCESS / TIMEOUT / REJECTED / UNAVAILABLE / MISMATCH。 */
  static final AtomicReference<String> sendMode = new AtomicReference<>("SUCCESS");

  /** 渠道的查证行为：SUCCESS / NOT_FOUND / FAILED / TIMEOUT。 */
  static final AtomicReference<String> queryMode = new AtomicReference<>("NOT_FOUND");

  /** 密钥管理器里有没有这条凭据。 */
  static final AtomicBoolean secretAvailable = new AtomicBoolean(true);

  /** 渠道收到的**发送**次数（用来证明「没有重复付款」）。 */
  static final AtomicInteger sendCount = new AtomicInteger();

  /** 渠道收到的**查证**次数。 */
  static final AtomicInteger queryCount = new AtomicInteger();

  /** 渠道回单里的流水号。 */
  static final AtomicReference<String> serialNo = new AtomicReference<>("CH-20261024-0001");

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
      stubExchangeRate();
      stubSecrets();
      stubChannelSend();
      stubChannelQuery();
      upstream.setExecutor(Executors.newFixedThreadPool(8));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // ================================================================ 上游桩

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

  /** catalog：GET /catalog/exchange-rates/latest（汇率事实）。 */
  static void stubExchangeRate() {
    upstream.createContext(
        "/catalog/exchange-rates/latest",
        e -> {
          var query = e.getRequestURI().getQuery();
          var base = param(query, "baseCurrency");
          var quote = param(query, "quoteCurrency");
          if (!"USD".equals(base) || !"CNY".equals(quote)) {
            write(e, 404, "{\"message\":\"没有这条汇率\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "baseCurrency", base,
                  "quoteCurrency", quote,
                  "rate", new BigDecimal("7.20000000"),
                  "effectiveDate", "2026-10-20",
                  "sourceCode", "MANUAL",
                  "sourceName", "手工录入",
                  "display", "1 USD = 7.2 CNY");
          write(e, 200, json.writeValueAsBytes(body));
        });
  }

  /** 密钥管理器：GET /secrets/{path}。 */
  static void stubSecrets() {
    upstream.createContext(
        "/secrets/",
        e -> {
          if (!secretAvailable.get()) {
            write(e, 404, "{\"message\":\"没有这条密钥\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          write(e, 200, "test-credential".getBytes(StandardCharsets.UTF_8));
        });
  }

  /** 渠道：POST /channel/payments（发送付款指令）。 */
  static void stubChannelSend() {
    upstream.createContext(
        "/channel/payments",
        e -> {
          if (!"POST".equals(e.getRequestMethod())) {
            write(e, 405, "{}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          e.getRequestBody().readAllBytes();
          sendCount.incrementAndGet();
          switch (sendMode.get()) {
            case "TIMEOUT" -> {
              // 真的睡过去：客户端的读超时（1 秒）会先到 ⇒ 这是一次**真的**超时。
              try {
                Thread.sleep(2500);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              write(e, 200, "{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8));
            }
            case "REJECTED" ->
                write(
                    e,
                    400,
                    "{\"status\":\"FAILED\",\"reason\":\"收款账号校验不通过\"}"
                        .getBytes(StandardCharsets.UTF_8));
            case "UNAVAILABLE" -> write(e, 503, "{\"message\":\"渠道繁忙\"}".getBytes(StandardCharsets.UTF_8));
            case "MISMATCH" ->
                write(
                    e,
                    200,
                    ("{\"status\":\"SUCCESS\",\"serialNo\":\"" + serialNo.get()
                            + "\",\"paidAmount\":\"99.0000\",\"currency\":\"USD\"}")
                        .getBytes(StandardCharsets.UTF_8));
            default ->
                write(
                    e,
                    200,
                    ("{\"status\":\"SUCCESS\",\"serialNo\":\"" + serialNo.get()
                            + "\",\"paidAmount\":\"100.0000\",\"currency\":\"USD\"}")
                        .getBytes(StandardCharsets.UTF_8));
          }
        });
  }

  /** 渠道：GET /channel/payments/{idempotencyKey}（状态查证）。 */
  static void stubChannelQuery() {
    upstream.createContext(
        "/channel/payments/",
        e -> {
          queryCount.incrementAndGet();
          switch (queryMode.get()) {
            case "SUCCESS" ->
                write(
                    e,
                    200,
                    ("{\"status\":\"SUCCESS\",\"serialNo\":\"" + serialNo.get()
                            + "\",\"paidAmount\":\"100.0000\",\"currency\":\"USD\"}")
                        .getBytes(StandardCharsets.UTF_8));
            case "FAILED" ->
                write(
                    e,
                    200,
                    "{\"status\":\"FAILED\",\"reason\":\"渠道侧记录为失败\"}".getBytes(StandardCharsets.UTF_8));
            case "TIMEOUT" -> {
              try {
                Thread.sleep(2500);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              write(e, 200, "{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8));
            }
            default -> write(e, 404, "{\"message\":\"没有这笔\"}".getBytes(StandardCharsets.UTF_8));
          }
        });
  }

  static String param(String query, String key) {
    if (query == null) return "";
    for (var part : query.split("&")) {
      var kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) {
        return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
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
    // 渠道主机白名单（fail-closed 闸）与密钥管理器。
    r.add("PAYMENT_CHANNEL_ALLOWED_HOSTS", () -> "127.0.0.1");
    r.add(
        "PAYMENT_SECRET_MANAGER_URL",
        () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("PAYMENT_SECRET_MANAGER_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired PaymentExecutionService executions;
  @Autowired MockMvc http;
  @Autowired AccessService access;
  @Autowired PaymentChannelPort channelPort;

  // ================================================================ 夹具

  static UUID payableId() {
    return UUID.nameUUIDFromBytes(("payable-" + tenant).getBytes());
  }

  static UUID payableLine1() {
    return UUID.nameUUIDFromBytes(("payable-line-1-" + tenant).getBytes());
  }

  static UUID payableLine2() {
    return UUID.nameUUIDFromBytes(("payable-line-2-" + tenant).getBytes());
  }

  static UUID orderId(String tag) {
    return UUID.nameUUIDFromBytes(("order-" + tag + "-" + tenant).getBytes());
  }

  /** 清库 + 播种。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payment_execution_event, finance_payment_execution_attempt,"
              + " finance_payment_execution_allocation, finance_rate_lock, finance_payment_execution,"
              + " finance_payment_channel, finance_payment_approval_record, finance_payment_order_event,"
              + " finance_payment_order_installment, finance_payment_order_line, finance_payment_order,"
              + " finance_payment_plan_event, finance_payment_reminder, finance_payment_plan,"
              + " finance_payment_batch, finance_payment_milestone, finance_payment_milestone_rule,"
              + " finance_payment_term_rule, finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy"
              + " restart identity cascade");
    }
    sendMode.set("SUCCESS");
    queryMode.set("NOT_FOUND");
    secretAvailable.set(true);
    sendCount.set(0);
    queryCount.set(0);
    serialNo.set("CH-20261024-0001");
    seedPayable();
    seedChannel();
  }

  /** 种一张带两条明细的应付单（100 + 50，均未付）。 */
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
          // 确认应付单（OPEN），这样它才能被付款。
          sql.execute(
              "update finance_payable set status='OPEN', confirmed_at=?::timestamptz,"
                  + " updated_at=?::timestamptz where id=?",
              now,
              now,
              payableId());
          return null;
        });
  }

  /** 种一个支付渠道配置（凭据只存引用）。 */
  void seedChannel() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () ->
            sql.execute(
                "insert into finance_payment_channel(id,tenant_id,code,name,payment_method,base_url,"
                    + " secret_ref,enabled,timeout_seconds,max_attempts,created_at,updated_at)"
                    + " values(?,?,'BANK_TEST','测试银行电汇','BANK_TRANSFER',?,"
                    + " 'vault://starcon/finance/bank-test',true,1,3,?::timestamptz,?::timestamptz)",
                UUID.nameUUIDFromBytes(("channel-" + tenant).getBytes()),
                tenant,
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/channel",
                now,
                now));
  }

  /**
   * 种一张**已批**的付款单（12.2.2.1 的状态机：草稿 → 待审 → 已批）。
   *
   * @param withRate 是否带汇率快照（带 ⇒ 会锁汇）
   */
  UUID seedApprovedOrder(String tag, BigDecimal amount, boolean withRate) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = orderId(tag);
    var instanceId = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_payment_order(id,tenant_id,order_no,counterparty_type,counterparty_ref,"
                  + " counterparty_name,currency,amount,base_currency,exchange_rate,payment_method,"
                  + " payment_account_name,payment_account_currency,status,approval_status,"
                  + " approval_instance_id,approval_instance_no,submitted_by,submitted_at,"
                  + " note,created_at,updated_at)"
                  + " values(?,?,?,'SUPPLIER',?,'测试供应商','USD',?,?,?,'BANK_TRANSFER',"
                  + " '测试账户','USD','DRAFT','NOT_SUBMITTED',?,'AP-1',?,?::timestamptz,'',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "PO-" + tag.toUpperCase(),
              supplier,
              amount,
              withRate ? "CNY" : null,
              withRate ? new BigDecimal("7.20000000") : null,
              instanceId,
              actors.get("session").userId(),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_payment_order_line(id,tenant_id,order_id,line_no,payable_line_id,"
                  + " description,currency,amount,payable_amount_snapshot,payable_paid_snapshot,active,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,1,?,'第一批货','USD',?,?,0,true,?::timestamptz,?::timestamptz)",
              UUID.nameUUIDFromBytes(("order-line-" + tag + "-" + tenant).getBytes()),
              tenant,
              id,
              payableLine1(),
              amount,
              amount,
              now,
              now);
          // 草稿 → 待审
          sql.execute(
              "update finance_payment_order set status='PENDING_APPROVAL', approval_status='PENDING',"
                  + " approval_instance_no='AP-1', submitted_by=?, submitted_at=?::timestamptz,"
                  + " updated_at=?::timestamptz where id=?",
              actors.get("session").userId(),
              now,
              now,
              id);
          // 待审 → 已批
          sql.execute(
              "update finance_payment_order set status='APPROVED', approval_status='APPROVED',"
                  + " approved_by=?, approved_at=?::timestamptz, updated_at=?::timestamptz where id=?",
              actors.get("session").userId(),
              now,
              now,
              id);
          return null;
        });
    return id;
  }

  // ================================================================ 工具

  Map<String, Object> postJson(String path, Object body, int expectedStatus) throws Exception {
    var result =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andReturn();
    var raw = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("POST %s ⇒ %s", path, raw)
        .isEqualTo(expectedStatus);
    return raw.isEmpty() ? Map.of() : json.readValue(raw, Map.class);
  }

  Map<String, Object> getJson(String path, int expectedStatus) throws Exception {
    var result =
        http.perform(get(path).header("Authorization", "session")).andReturn();
    var raw = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("GET %s ⇒ %s", path, raw)
        .isEqualTo(expectedStatus);
    return raw.isEmpty() ? Map.of() : json.readValue(raw, Map.class);
  }

  /** 执行单结果里嵌套的那张执行单。 */
  @SuppressWarnings("unchecked")
  static Map<String, Object> exec(Map<String, Object> result) {
    return (Map<String, Object>) result.get("execution");
  }

  /** 取一个返回**数组**的端点。 */
  List<?> getList(String path, int expectedStatus) throws Exception {
    var result =
        http.perform(get(path).header("Authorization", "session")).andReturn();
    var raw = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("GET %s ⇒ %s", path, raw)
        .isEqualTo(expectedStatus);
    return json.readValue(raw, List.class);
  }

  /** 在**租户事务**里调准备阶段（服务层断言用）。 */
  void prepareAs(UUID order) {
    var actor = actors.get("session");
    access.run(
        actor,
        "finance.paymentexecution.create",
        () ->
            executions.prepare(
                new FinancePaymentExecutionInput(order, null, "BANK_TEST", null),
                actor));
  }

  String orderStatus(UUID id) {
    return tenants.run(
        tenant, () -> sql.fetchOne("select status from finance_payment_order where id = ?", id).get(0, String.class));
  }

  BigDecimal paidAmount(UUID payableLine) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne(
                    "select paid_amount from finance_payable_line where id = ?", payableLine)
                .get(0, BigDecimal.class));
  }

  // ================================================================ 用例

  @Test
  @DisplayName("已批付款单执行付款后：写渠道流水号、付款单转已付、行级已付金额回写")
  void successfulPayment() throws Exception {
    var order = seedApprovedOrder("ok", new BigDecimal("100.0000"), true);

    var body = postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    assertThat(exec(body).get("status")).isEqualTo("SUCCEEDED");
    assertThat(exec(body).get("channelSerialNo")).isEqualTo("CH-20261024-0001");
    assertThat(exec(body).get("attemptCount")).isEqualTo(1);
    assertThat(exec(body).get("idempotencyKey")).asString().startsWith("pay-" + order);
    assertThat(sendCount.get()).isEqualTo(1);

    // 付款单转「已付」。
    assertThat(orderStatus(order)).isEqualTo("PAID");

    // 行级已付金额真的回写了。
    assertThat(paidAmount(payableLine1())).isEqualByComparingTo("100.0000");
    assertThat(paidAmount(payableLine2())).isEqualByComparingTo("0");

    // 分摊留痕。
    var allocations =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select payable_line_id, amount from finance_payment_execution_allocation"
                        + " where execution_id = ?",
                    UUID.fromString((String) exec(body).get("id"))));
    assertThat(allocations).hasSize(1);
    assertThat(allocations.get(0).get("amount", BigDecimal.class)).isEqualByComparingTo("100.0000");

    // 锁汇：锁的是**单据冻结的汇率**，付款成功后释放。
    var locks =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select rate, base_amount, status, locked_at, expires_at from finance_rate_lock"
                        + " where order_id = ?",
                    order));
    assertThat(locks).hasSize(1);
    assertThat(locks.get(0).get("rate", BigDecimal.class)).isEqualByComparingTo("7.20000000");
    assertThat(locks.get(0).get("base_amount", BigDecimal.class)).isEqualByComparingTo("720.0000");
    assertThat(locks.get(0).get("status", String.class)).isEqualTo("RELEASED");

    // 时间字段真的写进去了（写的是 UTC，不是偏八点的本地时间）。
    var execution =
        tenants.run(
            tenant,
            () -> sql.fetchOne("select * from finance_payment_execution where id = ?",
                UUID.fromString((String) exec(body).get("id"))));
    assertThat(execution.get("sent_at", OffsetDateTime.class)).isNotNull();
    assertThat(execution.get("responded_at", OffsetDateTime.class)).isNotNull();
    assertThat(execution.get("settled_at", OffsetDateTime.class)).isNotNull();
    assertThat(execution.get("created_at", OffsetDateTime.class)).isNotNull();
    // ⚠️ **断言写回的值**（不只是「没抛异常」）：写进去的那一刻必须就是那一刻。
    //    只看「有没有值」抓不到 `Timestamp.valueOf(LocalDateTime)` 那种静默偏 −28800s。
    var sentAt = execution.get("sent_at", OffsetDateTime.class);
    assertThat(sentAt.toInstant())
        .isCloseTo(
            OffsetDateTime.now(ZoneOffset.UTC).toInstant(), within(120, ChronoUnit.SECONDS));
    // API 侧返回的就是同一个瞬间（不多不少不少八小时）。
    var apiSentAt = OffsetDateTime.parse((String) exec(body).get("sentAt"));
    assertThat(apiSentAt.toInstant()).isEqualTo(sentAt.toInstant());
  }

  @Test
  @DisplayName("🔴 超时后查证发现渠道侧已成功 ⇒ 不重发（重复付款是严重资损事故）")
  void timeoutThenVerifyFindsSuccessDoesNotResend() throws Exception {
    sendMode.set("TIMEOUT");
    queryMode.set("SUCCESS");
    var order = seedApprovedOrder("timeout", new BigDecimal("100.0000"), false);

    var body =
        postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    // 超时 ⇒ 结果未知，**不是失败**。
    assertThat(exec(body).get("status")).isEqualTo("UNKNOWN");
    assertThat(exec(body).get("failureReason")).asString().contains("超时");
    // 付款单停在「执行中」，**没有**变成「失败」。
    assertThat(orderStatus(order)).isEqualTo("EXECUTING");
    assertThat(sendCount.get()).isEqualTo(1);

    // 重发端点：第一步永远是查证。
    var retried = postJson("/finance/payment-executions/" + exec(body).get("id") + "/retry", Map.of(), 200);

    // 🔴 查证发现渠道侧已成功 ⇒ **没有再发一次**（发送次数仍然是 1）。
    assertThat(sendCount.get()).isEqualTo(1);
    assertThat(queryCount.get()).isEqualTo(1);
    assertThat(exec(retried).get("status")).isEqualTo("SUCCEEDED");
    assertThat(retried.get("resent")).isEqualTo(false);
    assertThat(retried.get("message")).asString().contains("渠道确认付款成功");

    // 收敛成「已付」，行级已付金额回写。
    assertThat(orderStatus(order)).isEqualTo("PAID");
    assertThat(paidAmount(payableLine1())).isEqualByComparingTo("100.0000");
  }

  @Test
  @DisplayName("超时后查证确认渠道侧没有这笔 ⇒ 才允许重发（同一个幂等键）")
  void timeoutThenVerifyNotFoundAllowsResend() throws Exception {
    sendMode.set("TIMEOUT");
    queryMode.set("NOT_FOUND");
    var order = seedApprovedOrder("resend", new BigDecimal("100.0000"), false);

    var body =
        postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);
    assertThat(exec(body).get("status")).isEqualTo("UNKNOWN");
    assertThat(sendCount.get()).isEqualTo(1);

    // 第二次发送改成成功。
    sendMode.set("SUCCESS");
    var retried = postJson("/finance/payment-executions/" + exec(body).get("id") + "/retry", Map.of(), 200);

    assertThat(queryCount.get()).isEqualTo(1);
    assertThat(sendCount.get()).isEqualTo(2);
    assertThat(exec(retried).get("status")).isEqualTo("SUCCEEDED");
    assertThat(exec(retried).get("attemptCount")).isEqualTo(2);
    // 重发用的是**同一个幂等键** ⇒ 渠道侧可以据此去重。
    assertThat(exec(retried).get("idempotencyKey")).isEqualTo(exec(body).get("idempotencyKey"));
    assertThat(orderStatus(order)).isEqualTo("PAID");
    assertThat(paidAmount(payableLine1())).isEqualByComparingTo("100.0000");
  }

  @Test
  @DisplayName("🔴 渠道没有凭据时明确报「未配置」，绝不假装成功（付款单停在已批）")
  void channelWithoutCredentialFailsClosed() throws Exception {
    secretAvailable.set(false);
    var order = seedApprovedOrder("nocred", new BigDecimal("100.0000"), false);

    http.perform(
            post("/finance/payment-executions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("orderId", order, "channelCode", "BANK_TEST"))))
        .andExpect(status().isConflict());

    // 业务理由走**服务层**（HTTP 层只断言状态码 —— 本仓既有纪律）。
    assertThatThrownBy(() -> prepareAs(order))
        .hasStackTraceContaining("渠道不可用")
        .hasStackTraceContaining("凭据");
    // **没有**发出任何请求，**没有**建执行单，付款单还停在「已批」。
    assertThat(sendCount.get()).isEqualTo(0);
    assertThat(orderStatus(order)).isEqualTo("APPROVED");
    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetchOne(
                            "select count(*) from finance_payment_execution where order_id = ?", order)
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  @DisplayName("渠道明确拒绝时：付款单转失败并写明失败原因")
  void channelRejectionFails() throws Exception {
    sendMode.set("REJECTED");
    var order = seedApprovedOrder("reject", new BigDecimal("100.0000"), false);

    var body = postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    assertThat(exec(body).get("status")).isEqualTo("FAILED");
    assertThat(exec(body).get("failureReason")).asString().contains("收款账号校验不通过");
    assertThat(exec(body).get("failureClass")).isEqualTo("CHANNEL_REJECTED");
    assertThat(orderStatus(order)).isEqualTo("FAILED");
    // 没有付出去 ⇒ 行级已付金额不变。
    assertThat(paidAmount(payableLine1())).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("🔴 直连 SQL 也写不出「已付」：没有成功执行记录就不行")
  void cannotWritePaidWithoutSucceededExecution() throws Exception {
    // 超时 ⇒ 执行单 UNKNOWN、付款单「执行中」⇒ EXECUTING → PAID 的**路径**是允许的，
    // 这样才轮到「必须有**成功**执行记录」那道闸。
    sendMode.set("TIMEOUT");
    queryMode.set("SUCCESS");
    var order = seedApprovedOrder("guard", new BigDecimal("100.0000"), false);
    postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);
    assertThat(orderStatus(order)).isEqualTo("EXECUTING");
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_payment_order set status='PAID', approved_by=?,"
                                + " approved_at=?::timestamptz, updated_at=?::timestamptz where id=?",
                            actors.get("session").userId(),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            order)))
        .hasStackTraceContaining("成功");
  }

  @Test
  @DisplayName("🔴 直连 SQL 也写不出「失败」：结果未知（UNKNOWN）不等于失败")
  void cannotWriteFailedWhileUnknown() throws Exception {
    sendMode.set("TIMEOUT");
    queryMode.set("SUCCESS");
    var order = seedApprovedOrder("guard2", new BigDecimal("100.0000"), false);
    var body = postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);
    assertThat(exec(body).get("status")).isEqualTo("UNKNOWN");

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_payment_order set status='FAILED', failure_reason='手工改的',"
                                + " updated_at=?::timestamptz where id=?",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            order)))
        .hasStackTraceContaining("确定性失败");
  }

  @Test
  @DisplayName("同一笔付款不能重复发起执行（幂等键唯一）")
  void duplicateExecutionRejected() throws Exception {
    // 用「超时」让付款单停在「执行中」（不是已付）⇒ 第二次发起才会走到幂等键那道闸。
    sendMode.set("TIMEOUT");
    var order = seedApprovedOrder("dup", new BigDecimal("100.0000"), false);
    postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    http.perform(
            post("/finance/payment-executions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("orderId", order, "channelCode", "BANK_TEST"))))
        .andExpect(status().isConflict());

    assertThatThrownBy(() -> prepareAs(order)).hasStackTraceContaining("已经有执行单");
    assertThat(sendCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("渠道回单金额与指令金额不一致 ⇒ 停在执行中等人工核对，不记成功")
  void amountMismatchIsNotSuccess() throws Exception {
    sendMode.set("MISMATCH");
    var order = seedApprovedOrder("mismatch", new BigDecimal("100.0000"), false);

    var body = postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    assertThat(exec(body).get("status")).isEqualTo("UNKNOWN");
    assertThat(exec(body).get("failureClass")).isEqualTo("AMOUNT_MISMATCH");
    assertThat(orderStatus(order)).isEqualTo("EXECUTING");
    // 没有回写行级已付金额（渠道只确认了 99，我们不敢自己拍板）。
    assertThat(paidAmount(payableLine1())).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("渠道未启用时拒绝发起执行")
  void disabledChannelRejected() throws Exception {
    tenants.run(
        tenant,
        () ->
            sql.execute("update finance_payment_channel set enabled=false where code='BANK_TEST'"));
    var order = seedApprovedOrder("disabled", new BigDecimal("100.0000"), false);

    http.perform(
            post("/finance/payment-executions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("orderId", order, "channelCode", "BANK_TEST"))))
        .andExpect(status().isConflict());

    assertThatThrownBy(() -> prepareAs(order)).hasStackTraceContaining("未启用");
    assertThat(sendCount.get()).isZero();
  }

  @Test
  @DisplayName("执行单列表真分页真筛选，汇总按币种分开")
  void listIsReal() throws Exception {
    var order = seedApprovedOrder("list", new BigDecimal("100.0000"), false);
    postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    var page = getJson("/finance/payment-executions?page=0&size=10", 200);
    assertThat((Integer) page.get("total")).isEqualTo(1);
    var items = (List<?>) page.get("items");
    assertThat(items).hasSize(1);
    var summary = (List<?>) page.get("summaryByCurrency");
    assertThat(summary).hasSize(1);
    assertThat(((Map<?, ?>) summary.get(0)).get("currency")).isEqualTo("USD");
    assertThat(((Number) ((Map<?, ?>) summary.get(0)).get("succeededCount")).intValue())
        .isEqualTo(1);

    // 按状态筛选真的筛。
    var filtered = getJson("/finance/payment-executions?status=SUCCEEDED", 200);
    assertThat((Integer) filtered.get("total")).isEqualTo(1);
    var empty = getJson("/finance/payment-executions?status=FAILED", 200);
    assertThat((Integer) empty.get("total")).isZero();
  }

  @Test
  @DisplayName("执行单详情带尝试记录、事件流、分摊与锁汇")
  void detailIsComplete() throws Exception {
    var order = seedApprovedOrder("detail", new BigDecimal("100.0000"), true);
    var body = postJson("/finance/payment-executions", Map.of("orderId", order, "channelCode", "BANK_TEST"), 200);

    var detail = getJson("/finance/payment-executions/" + exec(body).get("id"), 200);
    assertThat((List<?>) detail.get("attempts")).hasSize(1);
    assertThat(((Map<?, ?>) ((List<?>) detail.get("attempts")).get(0)).get("outcome"))
        .isEqualTo("SUCCESS");
    assertThat((List<?>) detail.get("events")).isNotEmpty();
    assertThat((List<?>) detail.get("allocations")).hasSize(1);
    assertThat((List<?>) detail.get("rateLocks")).hasSize(1);
  }

  @Test
  @DisplayName("能力声明如实报出资金账户与凭证未交付")
  void capabilitiesAreHonest() throws Exception {
    var caps = getJson("/finance/payment-executions/capabilities", 200);
    var list = (List<?>) caps.get("capabilities");
    assertThat(list).isNotEmpty();
    var gaps = (List<?>) caps.get("downstreamGaps");
    assertThat(gaps).isNotEmpty();
    var gapText = gaps.toString();
    assertThat(gapText).contains("12.7.1.1");
    assertThat(gapText).contains("12.5.2.1");
  }

  @Test
  @DisplayName("渠道配置只接受密钥引用，明文密钥写不进去")
  void channelSecretMustBeReference() throws Exception {
    http.perform(
            post("/finance/payment-executions/channels")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "code", "PAYPAL_TEST",
                            "name", "测试 PayPal",
                            "paymentMethod", "PAYPAL",
                            "baseUrl", "https://paypal.example.com",
                            "secretRef", "sk_live_明文密钥"))))
        .andExpect(status().isBadRequest());

    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                access.run(
                    actor,
                    "finance.paymentexecution.create",
                    () ->
                        executions.registerChannel(
                            new FinancePaymentChannelInput(
                                "PAYPAL_TEST",
                                "测试 PayPal",
                                "PAYPAL",
                                "https://paypal.example.com",
                                "sk_live_明文密钥",
                                null,
                                null,
                                null,
                                null),
                            actor)))
        .hasStackTraceContaining("引用");
  }

  @Test
  @DisplayName("渠道配置可以登记，凭据只存引用")
  void channelCanBeRegistered() throws Exception {
    var created =
        postJson(
            "/finance/payment-executions/channels",
            Map.of(
                "code", "PAYPAL_TEST",
                "name", "测试 PayPal",
                "paymentMethod", "PAYPAL",
                "baseUrl", "https://paypal.example.com",
                "secretRef", "vault://starcon/finance/paypal"),
            200);

    assertThat(created.get("code")).isEqualTo("PAYPAL_TEST");
    assertThat(created.get("secretRef")).isEqualTo("vault://starcon/finance/paypal");
    var list = getList("/finance/payment-executions/channels", 200);
    assertThat(list).hasSize(2);
  }
}
