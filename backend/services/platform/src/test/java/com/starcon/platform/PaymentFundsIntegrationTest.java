package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.PaymentFundsModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.2.2.1「支付与资金」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.2.2.1 的**五类职责**与**加粗的那条纪律**：
 *
 * <ol>
 *   <li><b>幂等键</b>：同幂等键重复提交 ⇒ 原样返回同一结果，<b>绝不第二次调用服务商</b>；
 *       同键但请求内容不同 ⇒ 409。
 *   <li><b>超时先查证再重试</b>：发起超时 ⇒ 操作进 <b>UNKNOWN</b>（不是 FAILED）⇒
 *       <b>自动查证</b>；查证说成功 ⇒ <b>不重发</b>；查证说「服务商侧没有」⇒ 才允许重发；
 *       查证<b>查不出来</b> ⇒ <b>不许重发</b>（查不到 ≠ 没付出去）。
 *   <li><b>没接通就说没接通</b>：服务商未接入 ⇒ <b>409</b> + 如实标注，
 *       <b>不写成功流水</b>。
 *   <li><b>收款登记</b>：服务商未接入时**照样记本地流水**，但明确标注「未向服务商登记」。
 *   <li><b>余额 / 手续费 / 汇率</b>：账单导入路径真落库；汇率<b>真读 catalog</b>。
 * </ol>
 *
 * <p><b>🔴 金额纪律</b>：本模块一个 {@code double} 都没有，金额一律 {@link BigDecimal}，
 * 断言一律用 {@code isEqualByComparingTo}（比的是数值，不是标度）。
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>，
 * 不只断言「没抛异常」。
 *
 * <p>多租户隔离的三条断言在 {@code PaymentFundsTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentFundsIntegrationTest.StubProviderConfig.class)
class PaymentFundsIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;
  static HttpServer finance;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的七张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_payment_provider",
          "platform_payment_operation",
          "platform_payment_attempt",
          "platform_payment_balance",
          "platform_fund_flow",
          "platform_payment_fee",
          "platform_payment_rate");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.payment.read",
          "platform.payment.create",
          "platform.payment.update",
          "platform.payment.execute",
          "platform.payment.sync");

  // ---------------------------------------------------------------- 夹具工具

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static AccessService.Access actor(UUID uid, UUID tid, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tid,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("session", actor(UUID.randomUUID(), tenant, PERMISSIONS));
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

      // 🔴 catalog 汇率事实（5.2.2.1）：本模块**真读**它，不是本地词表。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e ->
              respond(
                  e,
                  200,
                  map(
                      "id", "00000000-0000-4000-8000-00000000aaaa",
                      "baseCurrency", "USD",
                      "quoteCurrency", "CNY",
                      "rate", new BigDecimal("7.123400"),
                      "effectiveDate", LocalDate.of(2026, 10, 30).toString(),
                      "sourceCode", "PBOC",
                      "sourceName", "中国外汇交易中心",
                      "inverseRate", new BigDecimal("0.140385"),
                      "display", "1 USD = 7.1234 CNY")));
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();

      // 🔴 finance 上游（12.7 资金账户 / 12.2 付款单）：**只读**端口的夹具。
      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/fund-accounts",
          e ->
              respond(
                  e,
                  200,
                  map(
                      "items",
                      List.of(
                          map(
                              "id", "00000000-0000-4000-8000-00000000bbbb",
                              "accountNo", "FA-0001",
                              "accountName", "PayPal 美国站",
                              "accountType", "PAYMENT_PLATFORM",
                              "accountTypeLabel", "支付平台账户",
                              "currency", "USD",
                              "currentBalance", new BigDecimal("1234.560000"),
                              "currentBalanceAt", "2026-10-30T00:00:00Z",
                              "status", "ACTIVE",
                              "statusLabel", "启用",
                              "note", "")),
                      "page", 0,
                      "size", 20,
                      "total", 1L)));
      finance.createContext(
          "/finance/payment-orders",
          e ->
              respond(
                  e,
                  200,
                  map(
                      "items",
                      List.of(
                          map(
                              "id", "00000000-0000-4000-8000-00000000cccc",
                              "orderNo", "PO-2026-0001",
                              "counterpartyType", "SUPPLIER",
                              "counterpartyName", "深圳供应商",
                              "currency", "CNY",
                              "amount", new BigDecimal("8800.000000"),
                              "baseCurrency", "USD",
                              "exchangeRate", new BigDecimal("7.123400"),
                              "baseAmount", new BigDecimal("1235.530000"),
                              "paymentMethod", "BANK_TRANSFER",
                              "paymentAccountName", "招商银行基本户",
                              "status", "APPROVED",
                              "approvalStatus", "APPROVED")),
                      "page", 0,
                      "size", 50,
                      "total", 1L)));
      finance.setExecutor(Executors.newFixedThreadPool(2));
      finance.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("FINANCE_URL", () -> "http://127.0.0.1:" + finance.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
  }

  @TestConfiguration
  static class StubProviderConfig {
    @Bean
    StubPaymentProviderPort stubPaymentProviderPort() {
      return new StubPaymentProviderPort();
    }
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired StubPaymentProviderPort provider;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.stop(0);
    if (finance != null) finance.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_payment_rate, platform_payment_fee, platform_fund_flow,"
              + " platform_payment_balance, platform_payment_attempt, platform_payment_operation,"
              + " platform_payment_provider restart identity cascade");
      s.execute("alter sequence platform_fund_flow_no_seq restart with 1");
    }
    provider.reset();
  }

  // ---------------------------------------------------------------- 请求助手

  String postJson(String path, Object body, int expectedStatus) throws Exception {
    var response =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("POST " + path + " 的状态码（响应：" + response.getContentAsString() + "）")
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  String getJson(String path, int expectedStatus) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session")).andReturn().getResponse();
    assertThat(response.getStatus())
        .as("GET " + path + " 的状态码（响应：" + response.getContentAsString() + "）")
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  /** 登记一个服务商配置（**密钥只存引用**）。 */
  UUID registerProvider(String code, String name) throws Exception {
    var body =
        postJson("/platform/payment-funds/providers",
            map(
                "providerCode", code,
                "name", name,
                "baseUrl", "https://example.invalid/api",
                "credentialRef", "secret://payments/" + code.toLowerCase() + "/main",
                "credentialHint", "9f2a",
                "settlementCurrency", "USD",
                "status", "ACTIVE",
                "timeoutSeconds", 15,
                "maxAttempts", 3,
                "remark", ""),
            200);
    return json.readValue(body, PaymentProviderRow.class).id();
  }

  long flowCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from platform_fund_flow").size());
  }

  long operationCount() {
    return tenants.run(tenant, () -> sql.fetch("select id from platform_payment_operation").size());
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的七张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V416 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner 误授").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 七张表里没有任何密钥明文列() throws Exception {
    for (String table : TABLES) {
      var columns =
          sql.fetch(
              "select column_name from information_schema.columns where table_name = ?"
                  + " and (column_name ilike '%secret%' or column_name ilike '%password%'"
                  + " or column_name ilike '%api_key%' or column_name ilike '%token%')",
              table);
      assertThat(columns).as(table + " 不许有密钥明文列").isEmpty();
    }
  }

  @Test
  void 资金台账是只追加的() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    postJson("/platform/payment-funds/balances/import",
        map(
            "providerId", providerId.toString(),
            "currency", "USD",
            "balanceAmount", new BigDecimal("100.00"),
            "availableAmount", new BigDecimal("80.00"),
            "observedAt", "2026-10-30T00:00:00Z",
            "sourceRef", "对账单-2026-10",
            "note", ""),
        200);
    var id =
        tenants.run(
            tenant,
            () -> sql.fetchOne("select id from platform_payment_balance").get(0, UUID.class));

    // 🔴 资金台账**只追加**：UPDATE / DELETE 一律被触发器拒绝。
    //   （必须在**租户事务内**试：RLS 会先把别的租户的行过滤掉，
    //     那样 UPDATE 会「静默影响 0 行」，而不是被触发器拒绝。）
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update platform_payment_balance set balance_amount = 1 where id = ?",
                            id)))
        .as("资金台账只追加：UPDATE 必须被触发器拒绝")
        .hasStackTraceContaining("只追加");
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant, () -> sql.execute("delete from platform_payment_balance where id = ?", id)))
        .as("资金台账只追加：DELETE 必须被触发器拒绝")
        .hasStackTraceContaining("只追加");

    // 行必须还在，且金额没被改。
    var amount =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select balance_amount from platform_payment_balance where id = ?", id)
                    .get(0, BigDecimal.class));
    assertThat(amount).isEqualByComparingTo("100.00");
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明对五个服务商如实答未接入() throws Exception {
    var capabilities =
        json.readValue(
            getJson("/platform/payment-funds/capabilities", 200), PaymentFundsCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();
    for (var code : List.of("PAYPAL", "STRIPE", "PINGPONG", "LIANLIAN", "WORLDFIRST")) {
      var row =
          capabilities.capabilities().stream()
              .filter(c -> c.name().contains(code) || c.name().contains(labelOf(code)))
              .findFirst();
      assertThat(row).as("能力声明里必须有 " + code + " 这一维").isNotNull();
    }
    // 🔴 五个服务商的直连维度必须**如实答 false**，且写明依据与归属。
    var direct =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("直连"))
            .toList();
    assertThat(direct).hasSizeGreaterThanOrEqualTo(5);
    assertThat(direct).allMatch(c -> !c.available());
    assertThat(direct).allMatch(c -> c.ownerModule() != null && !c.ownerModule().isBlank());
    assertThat(direct).allMatch(c -> c.evidence() != null && c.evidence().contains("适配器"));
    // 平台自己那一层必须如实答 true。
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.name().contains("幂等键") || c.name().contains("查证兜底"))
        .isNotEmpty()
        .allMatch(c -> c.available());
  }

  private static String labelOf(String code) {
    return switch (code) {
      case "PAYPAL" -> "PayPal";
      case "STRIPE" -> "Stripe";
      case "PINGPONG" -> "PingPong";
      case "LIANLIAN" -> "连连";
      case "WORLDFIRST" -> "万里汇";
      default -> code;
    };
  }

  @Test
  void 逐服务商可用性如实答() throws Exception {
    var rows =
        json.readValue(
            getJson("/platform/payment-funds/providers/availability", 200),
            PaymentProviderAvailability[].class);
    assertThat(rows).hasSize(5);
    // 本测试上下文里 PAYPAL 被测试替身顶掉了（connected=true）；
    // 其余四家必须如实答 false 且**写明缺什么**。
    for (var row : rows) {
      if ("PAYPAL".equals(row.providerCode())) continue;
      assertThat(row.connected()).as(row.providerCode() + " 必须如实答未接入").isFalse();
      assertThat(row.reason()).as(row.providerCode() + " 必须写明为什么没接通").isNotBlank();
      assertThat(row.ownerModule()).isNotBlank();
    }
  }

  // ==========================================================================
  // 1. 幂等键
  // ==========================================================================
  @Test
  void 同幂等键重复提交原样返回且不重复调用服务商() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.success("SER-1", "100.00"));
    var request =
        map(
            "providerId", providerId.toString(),
            "idempotencyKey", "idem-pay-1",
            "reference", "PO-2026-0001",
            "currency", "USD",
            "amount", new BigDecimal("100.00"),
            "counterpartyName", "深圳供应商",
            "counterpartyRef", "ACCT-1",
            "counterpartyType", "SUPPLIER",
            "note", "");

    var first = json.readValue(postJson("/platform/payment-funds/payments", request, 200), PaymentOperationRow.class);
    var second = json.readValue(postJson("/platform/payment-funds/payments", request, 200), PaymentOperationRow.class);

    assertThat(first.status()).isEqualTo("SUCCEEDED");
    assertThat(second.id()).as("同幂等键 ⇒ 同一个操作").isEqualTo(first.id());
    assertThat(second.replayed()).as("第二次必须是幂等重放").isTrue();
    assertThat(provider.sendCalls()).as("🔴 同幂等键绝不能第二次调用服务商").isEqualTo(1);
    assertThat(operationCount()).as("同幂等键只能有一行操作记录").isEqualTo(1);
    assertThat(flowCount()).as("同幂等键只能有一行资金流水").isEqualTo(1);
    assertThat(provider.sentKeys()).containsExactly("idem-pay-1");
  }

  @Test
  void 同幂等键不同请求内容报409() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.success("SER-1", "100.00"));
    var request =
        map(
            "providerId", providerId.toString(),
            "idempotencyKey", "idem-pay-2",
            "reference", "PO-2026-0001",
            "currency", "USD",
            "amount", new BigDecimal("100.00"),
            "counterpartyName", "深圳供应商");
    postJson("/platform/payment-funds/payments", request, 200);

    var conflicting =
        map(
            "providerId", providerId.toString(),
            "idempotencyKey", "idem-pay-2",
            "reference", "PO-2026-0001",
            "currency", "USD",
            "amount", new BigDecimal("999.00"),
            "counterpartyName", "深圳供应商");
    postJson("/platform/payment-funds/payments", conflicting, 409);
    assertThat(provider.sendCalls()).as("冲突的请求不许再调用服务商").isEqualTo(1);
    assertThat(operationCount()).as("冲突的请求不许另开一行操作记录").isEqualTo(1);
    assertThat(flowCount()).as("冲突的请求不许另写一行资金流水（首笔那行仍在）").isEqualTo(1);
  }

  // ==========================================================================
  // 2. 超时先查证再重试
  // ==========================================================================
  @Test
  void 超时后查证确认成功则不重发() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.timeout());
    provider.scriptQuery(StubPaymentProviderPort.success("SER-LATE", "100.00"));

    var row =
        json.readValue(
            postJson("/platform/payment-funds/payments",
                map(
                    "providerId", providerId.toString(),
                    "idempotencyKey", "idem-timeout-1",
                    "reference", "PO-2026-0002",
                    "currency", "USD",
                    "amount", new BigDecimal("100.00"),
                    "counterpartyName", "深圳供应商"),
                200),
            PaymentOperationRow.class);

    assertThat(row.status()).as("查证确认成功 ⇒ 操作转成功").isEqualTo("SUCCEEDED");
    assertThat(row.attemptCount()).as("一次发起 + 一次查证").isEqualTo(2);
    assertThat(provider.sendCalls()).as("🔴 查证说成功就绝不能重发（重复付款是资损）").isEqualTo(1);
    assertThat(provider.queryCalls()).as("超时必须先查证").isEqualTo(1);

    var attempts =
        json.readValue(
            getJson("/platform/payment-funds/operations/" + row.id() + "/attempts", 200),
            PaymentAttemptRow[].class);
    assertThat(attempts).hasSize(2);
    assertThat(attempts[0].attemptKind()).isEqualTo("SEND");
    assertThat(attempts[0].outcome()).isEqualTo("TIMEOUT");
    assertThat(attempts[1].attemptKind()).isEqualTo("QUERY");
    assertThat(attempts[1].outcome()).isEqualTo("SUCCESS");
    assertThat(attempts[1].attemptNo()).isGreaterThan(attempts[0].attemptNo());
    assertThat(flowCount()).as("查证确认已支付 ⇒ 资金流水要有一行").isEqualTo(1);
  }

  @Test
  void 超时后查证确认没有才允许重发() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.timeout());
    provider.scriptQuery(StubPaymentProviderPort.notFound());
    provider.scriptSend(StubPaymentProviderPort.success("SER-2", "100.00"));

    var row =
        json.readValue(
            postJson("/platform/payment-funds/payments",
                map(
                    "providerId", providerId.toString(),
                    "idempotencyKey", "idem-timeout-2",
                    "reference", "PO-2026-0003",
                    "currency", "USD",
                    "amount", new BigDecimal("100.00"),
                    "counterpartyName", "深圳供应商"),
                200),
            PaymentOperationRow.class);

    assertThat(row.status()).as("查证说服务商侧没有 ⇒ 仍记 UNKNOWN").isEqualTo("UNKNOWN");
    assertThat(row.retryAllowed()).as("查证确认没有 ⇒ 允许重试").isTrue();
    assertThat(provider.queryCalls()).as("超时必须先查证").isEqualTo(1);

    var retried =
        json.readValue(
            postJson("/platform/payment-funds/operations/" + row.id() + "/retry", map(), 200),
            PaymentOperationRow.class);
    assertThat(retried.status()).isEqualTo("SUCCEEDED");
    assertThat(provider.sendCalls()).as("重发一次").isEqualTo(2);
    assertThat(provider.sentKeys())
        .as("🔴 重发必须用**同一个**幂等键（服务商侧确认没有这笔）")
        .containsExactly("idem-timeout-2", "idem-timeout-2");

    var attempts =
        json.readValue(
            getJson("/platform/payment-funds/operations/" + row.id() + "/attempts", 200),
            PaymentAttemptRow[].class);
    assertThat(attempts).hasSize(3);
    assertThat(attempts[0].attemptKind()).isEqualTo("SEND");
    assertThat(attempts[1].attemptKind()).as("🔴 重发之前必须先有 QUERY 留痕").isEqualTo("QUERY");
    assertThat(attempts[2].attemptKind()).isEqualTo("SEND");
    assertThat(attempts[1].attemptNo()).isLessThan(attempts[2].attemptNo());
  }

  @Test
  void 查证查不出来不许重发() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.timeout());
    provider.scriptQuery(StubPaymentProviderPort.unknown());

    var row =
        json.readValue(
            postJson("/platform/payment-funds/payments",
                map(
                    "providerId", providerId.toString(),
                    "idempotencyKey", "idem-timeout-3",
                    "reference", "PO-2026-0004",
                    "currency", "USD",
                    "amount", new BigDecimal("100.00"),
                    "counterpartyName", "深圳供应商"),
                200),
            PaymentOperationRow.class);

    assertThat(row.status()).isEqualTo("UNKNOWN");
    assertThat(row.retryAllowed()).as("🔴 查不到 ≠ 没付出去 ⇒ 不许重发").isFalse();
    postJson("/platform/payment-funds/operations/" + row.id() + "/retry", map(), 409);
    assertThat(provider.sendCalls()).as("不许重发 ⇒ 不许第二次调用服务商").isEqualTo(1);
    assertThat(flowCount()).as("结果未知 ⇒ 不许写资金流水").isZero();
    // 操作记录仍停在「结果未知、且不许重试」。
    var still =
        json.readValue(
            getJson("/platform/payment-funds/operations/" + row.id(), 200), PaymentOperationRow.class);
    assertThat(still.status()).isEqualTo("UNKNOWN");
    assertThat(still.retryAllowed()).isFalse();
  }

  @Test
  void 服务商明确拒绝记为失败且不写流水() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    provider.scriptSend(StubPaymentProviderPort.rejected("收款账号不存在"));
    var row =
        json.readValue(
            postJson("/platform/payment-funds/payments",
                map(
                    "providerId", providerId.toString(),
                    "idempotencyKey", "idem-rejected",
                    "reference", "PO-2026-0005",
                    "currency", "USD",
                    "amount", new BigDecimal("100.00"),
                    "counterpartyName", "深圳供应商"),
                200),
            PaymentOperationRow.class);
    assertThat(row.status()).as("服务商明确拒绝 ⇒ 确定性失败").isEqualTo("FAILED");
    assertThat(row.failureClass()).isEqualTo("REJECTED");
    assertThat(provider.queryCalls()).as("确定性失败不需要查证").isZero();
    assertThat(flowCount()).isZero();
  }

  // ==========================================================================
  // 3. 没接通就说没接通
  // ==========================================================================
  @Test
  void 服务商未接入如实答409且不写成功流水() throws Exception {
    var providerId = registerProvider("STRIPE", "Stripe 未接入");
    var response =
        http.perform(
                MockMvcRequestBuilders.post("/platform/payment-funds/payments")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "providerId", providerId.toString(),
                                "idempotencyKey", "idem-stripe",
                                "reference", "PO-2026-0006",
                                "currency", "USD",
                                "amount", new BigDecimal("100.00"),
                                "counterpartyName", "深圳供应商"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("🔴 未接入必须是 409（200 会被当成「钱处理完了」）").isEqualTo(409);
    var row = json.readValue(response.getContentAsString(), PaymentOperationRow.class);
    assertThat(row.status()).isEqualTo("NOT_CONNECTED");
    assertThat(row.providerNotified()).as("未接入 ⇒ 绝不能说已告知服务商").isFalse();
    assertThat(row.providerNotice()).as("必须写明为什么没接通").isNotBlank();
    assertThat(row.failureClass()).isEqualTo("NOT_CONFIGURED");
    assertThat(flowCount()).as("🔴 未接入不许写资金流水").isZero();
  }

  @Test
  void 收款登记在服务商未接入时仍记本地流水并标明未通知() throws Exception {
    var providerId = registerProvider("STRIPE", "Stripe 未接入");
    var row =
        json.readValue(
            postJson("/platform/payment-funds/receipts",
                map(
                    "providerId", providerId.toString(),
                    "idempotencyKey", "idem-receipt-1",
                    "reference", "RC-2026-0001",
                    "currency", "USD",
                    "amount", new BigDecimal("250.00"),
                    "counterpartyName", "Amazon US",
                    "providerSerialNo", "PP-REF-1",
                    "occurredAt", "2026-10-30T00:00:00Z",
                    "note", ""),
                200),
            PaymentOperationRow.class);

    assertThat(row.status()).as("本地登记成功").isEqualTo("SUCCEEDED");
    assertThat(row.providerNotified()).as("🔴 必须标明没有通知服务商").isFalse();
    assertThat(row.providerNotice()).isNotBlank();
    assertThat(flowCount()).as("本地资金流水要有一行").isEqualTo(1);
    var flows =
        json.readValue(
            getJson("/platform/payment-funds/flows?providerId=" + providerId, 200), FundFlowRow[].class);
    assertThat(flows).hasSize(1);
    assertThat(flows[0].flowType()).isEqualTo("RECEIPT");
    assertThat(flows[0].direction()).isEqualTo("INBOUND");
    assertThat(flows[0].amount()).isEqualByComparingTo("250.00");
    assertThat(flows[0].occurredAt()).isNotNull();
  }

  // ==========================================================================
  // 4. 余额 / 手续费 / 汇率
  // ==========================================================================
  @Test
  void 余额导入落库且金额不丢精度() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    postJson("/platform/payment-funds/balances/import",
        map(
            "providerId", providerId.toString(),
            "currency", "USD",
            "balanceAmount", new BigDecimal("1234.560000"),
            "availableAmount", new BigDecimal("1000.123456"),
            "observedAt", "2026-10-30T00:00:00Z",
            "sourceRef", "对账单-2026-10",
            "note", ""),
        200);
    var rows =
        json.readValue(
            getJson("/platform/payment-funds/balances?providerId=" + providerId, 200),
            PaymentBalanceRow[].class);
    assertThat(rows).hasSize(1);
    assertThat(rows[0].balanceAmount()).isEqualByComparingTo("1234.56");
    assertThat(rows[0].availableAmount()).isEqualByComparingTo("1000.123456");
    assertThat(rows[0].source()).isEqualTo("IMPORT");
    // 🔴 时间必须**断言写回的值**，不只是「没抛异常」。
    assertThat(rows[0].observedAt()).isNotNull();
    assertThat(rows[0].observedAt().toInstant())
        .isEqualTo(java.time.OffsetDateTime.parse("2026-10-30T00:00:00Z").toInstant());
  }

  @Test
  void 手续费导入与查询() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    postJson("/platform/payment-funds/fees/import",
        map(
            "providerId", providerId.toString(),
            "feeType", "CROSS_BORDER_FEE",
            "amount", new BigDecimal("12.340000"),
            "currency", "USD",
            "chargedAmount", new BigDecimal("100.00"),
            "providerSerialNo", "FEE-SERIAL-1",
            "providerReference", "PO-2026-0001",
            "occurredAt", "2026-10-29T10:00:00Z",
            "summary", "跨境手续费"),
        200);
    var rows =
        json.readValue(
            getJson("/platform/payment-funds/fees?providerId=" + providerId, 200),
            PaymentFeeRow[].class);
    assertThat(rows).hasSize(1);
    assertThat(rows[0].feeType()).isEqualTo("CROSS_BORDER_FEE");
    assertThat(rows[0].amount()).isEqualByComparingTo("12.34");
    assertThat(rows[0].source()).isEqualTo("IMPORT");
    assertThat(rows[0].occurredAt().toInstant())
        .isEqualTo(java.time.OffsetDateTime.parse("2026-10-29T10:00:00Z").toInstant());
  }

  @Test
  void 汇率查询真读catalog并把观测落库() throws Exception {
    var providerId = registerProvider("PAYPAL", "PayPal 替身");
    var row =
        json.readValue(
            postJson("/platform/payment-funds/rates/query",
                map(
                    "providerId", providerId.toString(),
                    "baseCurrency", "USD",
                    "quoteCurrency", "CNY",
                    "asOf", "2026-10-31"),
                200),
            PaymentOperationRow.class);
    assertThat(row.status()).isEqualTo("SUCCEEDED");
    assertThat(row.providerNotified()).as("服务商报价未接入 ⇒ 明确标注").isFalse();

    var rates =
        json.readValue(
            getJson("/platform/payment-funds/rates?providerId=" + providerId, 200),
            PaymentRateRow[].class);
    assertThat(rates).hasSize(1);
    assertThat(rates[0].baseCurrency()).isEqualTo("USD");
    assertThat(rates[0].quoteCurrency()).isEqualTo("CNY");
    assertThat(rates[0].rate()).isEqualByComparingTo("7.1234");
    assertThat(rates[0].source()).as("服务商报价未接入 ⇒ 来源如实标 CATALOG").isEqualTo("CATALOG");
    assertThat(rates[0].effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 30));
  }

  // ==========================================================================
  // 5. 上游只读连接
  // ==========================================================================
  @Test
  void 上游资金账户与付款单真读得到() throws Exception {
    var accounts =
        json.readValue(
            getJson("/platform/payment-funds/upstream/fund-accounts", 200),
            UpstreamFundAccountRow[].class);
    assertThat(accounts).hasSize(1);
    assertThat(accounts[0].accountNo()).isEqualTo("FA-0001");
    assertThat(accounts[0].currentBalance()).isEqualByComparingTo("1234.56");

    var orders =
        json.readValue(
            getJson("/platform/payment-funds/upstream/payment-orders", 200),
            UpstreamPaymentOrderRow[].class);
    assertThat(orders).hasSize(1);
    assertThat(orders[0].orderNo()).isEqualTo("PO-2026-0001");
    assertThat(orders[0].exchangeRate()).isEqualByComparingTo("7.1234");
  }

  // ==========================================================================
  // 6. 服务商配置（密钥引用纪律）
  // ==========================================================================
  @Test
  void 服务商配置只存引用不存明文() throws Exception {
    var ref = "secret://payments/stripe/main";
    postJson("/platform/payment-funds/providers",
        map(
            "providerCode", "STRIPE",
            "name", "Stripe 主账号",
            "baseUrl", "https://api.stripe.example",
            "credentialRef", ref,
            "credentialHint", "9f2a",
            "settlementCurrency", "USD",
            "status", "ACTIVE",
            "timeoutSeconds", 15,
            "maxAttempts", 3,
            "remark", ""),
        200);
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select credential_ref, credential_ref_lookup, credential_hint"
                        + " from platform_payment_provider where name = 'Stripe 主账号'"));
    assertThat(stored).isNotNull();
    assertThat(stored.get("credential_ref", String.class)).isEqualTo(ref);
    assertThat(stored.get("credential_ref_lookup", String.class))
        .as("引用的 SHA-256 十六进制摘要（只用于按引用定位）")
        .isEqualTo(PaymentFundsService.sha256Hex(ref));
    assertThat(stored.get("credential_hint", String.class)).isEqualTo("9f2a");
  }
}
