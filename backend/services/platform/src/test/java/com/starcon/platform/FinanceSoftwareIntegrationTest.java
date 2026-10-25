package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.FinanceSoftwareModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.2.1.1「财务软件」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.2.1.1 的**五类职责**，每一类都必须是「真在生效」而不是「端点存在」：
 *
 * <ol>
 *   <li><b>凭证推送</b>：外部财务软件适配器**未接入** ⇒ 端点如实答 <b>409</b>，
 *       且<b>一次数据都不写</b>（不落推送记录、不落回执、不留任何「已经推给金蝶了」的痕迹）；
 *   <li><b>科目映射</b>：真落库、真参与导出（本地科目被换成外部科目编码）、
 *       没配映射的行被标成 UNMAPPED 而<b>不是静默丢行</b>；
 *   <li><b>主数据同步</b>：映射本身真落库并参与导出（辅助核算带出外部编码）；
 *       <b>直连同步</b>未接入 ⇒ 如实答 409；
 *   <li><b>余额核对</b>：本地余额真读上游 12.5.2.2 试算平衡表，外部余额真从导入快照来，
 *       两者同口径后逐科目相减，四种差异原因都能被算出来；
 *   <li><b>标准凭证文件导出</b>：真内容 + 真 sha256 + 真留痕，事后能<b>原样取回</b>当初那一份。
 * </ol>
 *
 * <p><b>🔴 金额纪律</b>：本模块一个 {@code double} 都没有，金额一律 {@link BigDecimal}，
 * 断言一律用 {@code isEqualByComparingTo}（比的是数值，不是标度 ——
 * 库里是 {@code numeric(20,6)}，Java 侧可能是 {@code 1300.00}）。
 *
 * <p><b>🔴 上游纪律</b>：取不到就 <b>503</b>，不返回空集合冒充「上游没有凭证」；
 * 区间里没有已过账凭证 ⇒ 400 并说清「草稿不是账」。
 *
 * <p>租户隔离的三条断言在 {@code FinanceSoftwareTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceSoftwareIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer finance;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  // ---------------------------------------------------------------- 上游夹具常量

  /** 上游两张**已过账**凭证。 */
  static final UUID V1 = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID V2 = UUID.fromString("00000000-0000-4000-8000-000000000002");

  /** 上游两个会计期间（12.5.2.1 的期间表）。 */
  static final UUID PERIOD_FROM = UUID.fromString("11111111-1111-4111-8111-111111111111");
  static final UUID PERIOD_TO = UUID.fromString("22222222-2222-4222-8222-222222222222");

  /**
   * 上游故障的**触发口令**（写死日期 ⇒ 测试不依赖任何可变全局状态）。
   *
   * <ul>
   *   <li>{@code 1998-01-01} 起始 ⇒ 上游返回超过导出上限的凭证 ⇒ 本服务必须 400（不偷偷截断）；
   *   <li>{@code 1999-01-01} 起始 ⇒ 上游 500 ⇒ 本服务必须如实答 503。
   * </ul>
   */
  static final String FAIL_RANGE = "1999-01-01";
  static final String OVERSIZE_RANGE = "1998-01-01";

  /** 造一个允许 **null 值**的 JSON 对象（{@code Map.of} 遇到 null 直接抛 NPE）。 */
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

  static void fail(HttpExchange e, int status) throws IOException {
    e.sendResponseHeaders(status, -1);
    e.close();
  }

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

  // ---------------------------------------------------------------- 上游夹具内容

  /** 一张上游凭证行。 */
  static Map<String, Object> line(
      int lineNo,
      String lineType,
      String subjectCode,
      String subjectName,
      String summary,
      String amount,
      String auxiliaryType,
      String auxiliaryName) {
    return map(
        "lineNo", lineNo,
        "lineType", lineType,
        "subjectCode", subjectCode,
        "subjectName", subjectName,
        "summary", summary,
        "amount", new BigDecimal(amount),
        "currency", "USD",
        "quantity", null,
        "auxiliaryType", auxiliaryType,
        "auxiliaryName", auxiliaryName);
  }

  /** 上游凭证头。 */
  static Map<String, Object> voucherHead(
      UUID id, String no, String period, LocalDate date, String debit, String credit, int lines) {
    return map(
        "id", id.toString(),
        "voucherNo", no,
        "periodCode", period,
        "voucherDate", date.toString(),
        "currency", "USD",
        "status", "POSTED",
        "description", no,
        "totalDebit", new BigDecimal(debit),
        "totalCredit", new BigDecimal(credit),
        "lineCount", lines);
  }

  /** V1 的分录行（辅助核算带客户）。 */
  static List<Map<String, Object>> linesOfV1() {
    return List.of(
        line(1, "DEBIT", "1122", "应收账款", "Amazon 货款", "1000.00", "CUSTOMER", "Acme US"),
        line(2, "CREDIT", "6001", "主营业务收入", "Amazon 货款", "1000.00", null, null));
  }

  /** V2 的分录行（6602 故意**不配**科目映射 ⇒ 导出时必须是 UNMAPPED 而不是被丢掉）。 */
  static List<Map<String, Object>> linesOfV2() {
    return List.of(
        line(1, "DEBIT", "6602", "管理费用", "头程运费", "300.00", null, null),
        line(2, "CREDIT", "1002", "银行存款", "头程运费", "300.00", null, null));
  }

  /** 上游凭证总集（V1 + V2）。 */
  static List<Map<String, Object>> allVouchers() {
    return List.of(
        voucherHead(V1, "V-2026-0001", "2026-01", LocalDate.of(2026, 1, 15), "1000.00", "1000.00", 2),
        voucherHead(V2, "V-2026-0002", "2026-02", LocalDate.of(2026, 2, 10), "300.00", "300.00", 2));
  }

  /** 上游试算平衡表：本地一侧的余额（**按科目余额方向取正负**的口径）。 */
  static Map<String, Object> trialBalance() {
    return map(
        "reportType", "TRIAL_BALANCE",
        "currencyScope", "USD",
        "currencyGroups",
            List.of(
                map(
                    "currency", "USD",
                    "rows",
                        List.of(
                            map(
                                "subjectCode", "1122",
                                "subjectName", "应收账款",
                                "currency", "USD",
                                "balanceDirection", "DEBIT",
                                "openingBalance", new BigDecimal("0"),
                                "closingBalance", new BigDecimal("1000"),
                                "totalRow", false),
                            map(
                                "subjectCode", "6001",
                                "subjectName", "主营业务收入",
                                "currency", "USD",
                                "balanceDirection", "CREDIT",
                                "openingBalance", new BigDecimal("0"),
                                "closingBalance", new BigDecimal("-1000"),
                                "totalRow", false),
                            map(
                                "subjectCode", "6602",
                                "subjectName", "管理费用",
                                "currency", "USD",
                                "balanceDirection", "DEBIT",
                                "openingBalance", new BigDecimal("0"),
                                "closingBalance", new BigDecimal("300"),
                                "totalRow", false),
                            // 合计行必须被跳过（否则它会变成一个「差异科目」）。
                            map(
                                "subjectCode", "",
                                "subjectName", "合计",
                                "currency", "USD",
                                "balanceDirection", "",
                                "openingBalance", new BigDecimal("0"),
                                "closingBalance", new BigDecimal("0"),
                                "totalRow", true)))),
        "voucherCount", 2,
        "lineCount", 4,
        "draftExcludedCount", 0,
        "reversedVoucherCount", 0);
  }

  // ---------------------------------------------------------------- 静态启动

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

    var all =
        List.of(
            "platform.finance.read",
            "platform.finance.create",
            "platform.finance.update",
            "platform.finance.export",
            "platform.finance.reconcile",
            "platform.finance.push");
    var tenantA = UUID.randomUUID();
    actors.put("session-a", actor(UUID.randomUUID(), tenantA, all));
    // 🔴 只读会话与「只给导出码」的会话**与 session-a 同一个租户** ——
    //   这一组断言的是「权限码彼此独立」，不是「租户隔离」（租户隔离在另一个测试类里）。
    //   换租户的话，请求会先被 RLS 挡掉，测出来的是租户隔离而不是权限。
    actors.put("session-readonly", actor(UUID.randomUUID(), tenantA, List.of("platform.finance.read")));
    // 能导出但不能推送：三个权限码是**独立**的。
    actors.put(
        "session-export",
        actor(
            UUID.randomUUID(),
            tenantA,
            List.of("platform.finance.read", "platform.finance.export")));

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
      identity.setExecutor(Executors.newFixedThreadPool(4));
      identity.start();

      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext("/finance/vouchers/options", e -> respond(e, 200, options()));
      finance.createContext(
          "/finance/vouchers",
          e -> {
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            if (query.contains("dateFrom=" + FAIL_RANGE)) {
              // 上游 500 —— 本服务必须如实报 503（读不到），不是返回空集合。
              fail(e, 500);
              return;
            }
            if (query.contains("dateFrom=" + OVERSIZE_RANGE)) {
              // 超过导出上限 ⇒ 本服务必须 400 并让调用方缩小区间，**不偷偷只导一部分**。
              var many = new ArrayList<Map<String, Object>>();
              for (int i = 0; i < 501; i++) {
                many.add(voucherHead(UUID.randomUUID(), "V-BULK-" + i, "2026-01", LocalDate.of(2026, 1, 1), "1.00", "1.00", 1));
              }
              respond(e, 200, map("items", many, "page", 0, "size", 200, "total", many.size()));
              return;
            }
            var status = param(query, "status");
            var currency = param(query, "currency");
            var from = param(query, "dateFrom");
            var to = param(query, "dateTo");
            var matched = new ArrayList<Map<String, Object>>();
            for (var v : allVouchers()) {
              if (status != null && !status.isBlank() && !status.equals(v.get("status"))) continue;
              if (currency != null && !currency.isBlank() && !currency.equals(v.get("currency"))) continue;
              var date = LocalDate.parse((String) v.get("voucherDate"));
              if (from != null && !from.isBlank() && date.isBefore(LocalDate.parse(from))) continue;
              if (to != null && !to.isBlank() && date.isAfter(LocalDate.parse(to))) continue;
              matched.add(v);
            }
            respond(
                e,
                200,
                map(
                    "items", matched,
                    "page", 0,
                    "size", 200,
                    "total", matched.size(),
                    "draftCount", 0,
                    "postedCount", matched.size()));
          });
      finance.createContext(
          "/finance/vouchers/",
          e -> {
            var id = e.getRequestURI().getPath().substring("/finance/vouchers/".length());
            Map<String, Object> head = null;
            List<Map<String, Object>> lines = List.of();
            if (V1.toString().equals(id)) {
              head = voucherHead(V1, "V-2026-0001", "2026-01", LocalDate.of(2026, 1, 15), "1000.00", "1000.00", 2);
              lines = linesOfV1();
            } else if (V2.toString().equals(id)) {
              head = voucherHead(V2, "V-2026-0002", "2026-02", LocalDate.of(2026, 2, 10), "300.00", "300.00", 2);
              lines = linesOfV2();
            }
            if (head == null) {
              fail(e, 404);
              return;
            }
            respond(e, 200, map("voucher", head, "lines", lines));
          });
      finance.createContext(
          "/finance/financial-reports/trial-balance",
          e -> {
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            var currency = param(query, "currency");
            if ("EUR".equals(currency)) {
              // 上游 500 ⇒ 本服务必须如实答 503（读不到），不是「本地余额为 0」。
              fail(e, 500);
              return;
            }
            respond(e, 200, trialBalance());
          });
      finance.setExecutor(Executors.newFixedThreadPool(4));
      finance.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 取一个查询参数值（没有就是 {@code null}）。 */
  static String param(String query, String name) {
    for (var part : query.split("&")) {
      if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
    }
    return null;
  }

  /** 上游筛选项（**真实数据**，不是写死的词表）。 */
  static Map<String, Object> options() {
    return map(
        "currencies", List.of("USD"),
        "statuses", List.of("POSTED", "DRAFT"),
        "sourceTypes", List.of("MANUAL"),
        "periods",
            List.of(
                map("id", PERIOD_FROM.toString(), "name", "2026-01"),
                map("id", PERIOD_TO.toString(), "name", "2026-02")),
        "subjectCodes", List.of("1002", "1122", "6001", "6602"));
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
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (finance != null) finance.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  /** 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。 */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_finance_reconcile_diff, platform_finance_reconcile_balance,"
              + " platform_finance_reconcile_task, platform_finance_voucher_file,"
              + " platform_finance_master_map, platform_finance_subject_map, platform_finance_account,"
              + " platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
      s.execute("alter sequence platform_finance_voucher_file_no_seq restart with 1");
      s.execute("alter sequence platform_finance_reconcile_no_seq restart with 1");
    }
  }

  // ================================================================ HTTP 小工具

  private MockHttpServletResponse getOf(String path, String session) throws Exception {
    return http.perform(get("/platform/finance-software" + path).header("Authorization", session))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse send(String method, String path, Object body, String session)
      throws Exception {
    var request =
        method.equals("GET")
            ? get("/platform/finance-software" + path)
            : post("/platform/finance-software" + path);
    request.header("Authorization", session);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON);
      request.content(json.writeValueAsString(body));
    }
    return http.perform(request).andReturn().getResponse();
  }

  /** 错误文案：MockMvc 走 sendError 时落在 errorMessage，走 ProblemDetail 时落在响应体。 */
  private static String detail(MockHttpServletResponse r)
      throws java.io.UnsupportedEncodingException {
    var message = r.getErrorMessage();
    var body = r.getContentAsString();
    return (message == null ? "" : message) + " " + body;
  }

  private <T> T read(MockHttpServletResponse r, Class<T> type) throws Exception {
    return json.readValue(r.getContentAsString(), type);
  }

  // ================================================================ 业务夹具

  /** 建一个连接档案。 */
  private FinanceSoftwareAccountRow createAccount() throws Exception {
    var r =
        send(
            "POST",
            "/accounts",
            new FinanceSoftwareAccountInput(
                "美国主体 - 金蝶", "KINGDEE", "BOOK-001", "Acme US Inc.", "USD", 1, "ACTIVE", ""),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareAccountRow.class);
  }

  /** 建一条科目映射。 */
  private FinanceSoftwareSubjectMapRow createSubjectMap(
      UUID accountId, String local, String external) throws Exception {
    var r =
        send(
            "POST",
            "/subject-maps",
            new FinanceSoftwareSubjectMapInput(
                accountId, local, "本地" + local, external, "外部" + external, "", "", true, ""),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareSubjectMapRow.class);
  }

  /** 建一条主数据映射。 */
  private FinanceSoftwareMasterMapRow createMasterMap(
      UUID accountId, String type, String localCode, String localName, String external)
      throws Exception {
    var r =
        send(
            "POST",
            "/master-maps",
            new FinanceSoftwareMasterMapInput(
                accountId, type, localCode, localName, external, "外部" + localName, true, ""),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareMasterMapRow.class);
  }

  /** 配好三张映射（1122 / 6001 / 1002），6602 故意留空。 */
  private void configureMappings(UUID accountId) throws Exception {
    createSubjectMap(accountId, "1122", "EX-AR-1122");
    createSubjectMap(accountId, "6001", "EX-REV-6001");
    createSubjectMap(accountId, "1002", "EX-BANK-1002");
    createMasterMap(accountId, "CUSTOMER", "C-001", "Acme US", "CUST-EX-001");
  }

  private FinanceSoftwareVoucherExportResult export(
      UUID accountId, String format, String from, String to) throws Exception {
    var r =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                accountId, format, LocalDate.parse(from), LocalDate.parse(to), ""),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareVoucherExportResult.class);
  }

  /** 独立算一遍 sha256（**不复用被测代码**，否则断言是自证的）。 */
  private static String sha256Of(String content) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
    var sb = new StringBuilder();
    for (var b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  // ==========================================================================
  // 1. 能力声明：直连推送 / 直连同步如实答「未接入」
  // ==========================================================================

  @Test
  void 能力声明把直连推送与直连同步如实标成未接入() throws Exception {
    var r = getOf("/capabilities", "session-a");
    assertThat(r.getStatus()).isEqualTo(200);
    var caps = read(r, FinanceSoftwareCapabilities.class);

    var unavailable =
        caps.capabilities().stream().filter(c -> !c.available()).toList();
    assertThat(unavailable).as("直连推送与直连同步必须被如实标成未接入").hasSize(2);
    assertThat(unavailable).allSatisfy(c -> assertThat(c.ownerModule()).isEqualTo("18.2"));
    assertThat(unavailable).allSatisfy(c -> assertThat(c.evidence()).isNotBlank());
    assertThat(unavailable)
        .anySatisfy(c -> assertThat(c.name()).contains("直连推送凭证"));
    assertThat(unavailable).anySatisfy(c -> assertThat(c.name()).contains("直连同步主数据"));

    // 已交付的六格必须是真的可用（不能为了「统一」一起标成没有）。
    assertThat(caps.capabilities()).filteredOn(c -> c.available()).hasSize(6);
    // 缺口清单也要在（缺在谁身上是可查的事实）。
    assertThat(caps.gaps()).isNotEmpty();
  }

  // ==========================================================================
  // 2. 凭证推送：未接入 ⇒ 409 且一次数据都不写
  // ==========================================================================

  @Test
  void 凭证推送未接入时如实答409且一次数据都不写() throws Exception {
    var account = createAccount();
    configureMappings(account.id());

    var r =
        send(
            "POST",
            "/push",
            new FinanceSoftwarePushInput(
                account.id(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)),
            "session-a");
    assertThat(r.getStatus()).as("外部财务软件适配器未接入 ⇒ 必须 409").isEqualTo(409);
    assertThat(detail(r)).contains("未接入");

    // 🔴 **一次数据都不写**：没有推送记录、没有回执、没有任何「已经推给金蝶了」的痕迹。
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file")))
        .as("推送失败不得留下凭证文件痕迹")
        .isEmpty();
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_task"))).isEmpty();
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_balance"))).isEmpty();
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_diff"))).isEmpty();

    // 连接档案与映射是**推送之前**就有的，推送不得改动它们。
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_account"))).hasSize(1);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_subject_map"))).hasSize(3);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_master_map"))).hasSize(1);
  }

  @Test
  void 推送端点对每个厂商都如实答未接入() throws Exception {
    for (var vendor : List.of("KINGDEE", "YONYOU", "SAP", "ODOO")) {
      var account =
          read(
              send(
                  "POST",
                  "/accounts",
                  new FinanceSoftwareAccountInput(
                      "连接-" + vendor, vendor, "BOOK-" + vendor, "", "USD", 1, "ACTIVE", ""),
                  "session-a"),
              FinanceSoftwareAccountRow.class);
      var r =
          send(
              "POST",
              "/push",
              new FinanceSoftwarePushInput(
                  account.id(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
              "session-a");
      assertThat(r.getStatus()).as(vendor + " 未接入 ⇒ 409").isEqualTo(409);
    }
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file"))).isEmpty();
  }

  @Test
  void 直连推送的权限码是独立的能导出的不能推送() throws Exception {
    var account = createAccount();
    // session-export 只有 read + export，没有 push。
    var r =
        send(
            "POST",
            "/push",
            new FinanceSoftwarePushInput(
                account.id(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            "session-export");
    assertThat(r.getStatus()).as("能导出文件的人不该顺手把凭证推出去").isEqualTo(403);
  }

  // ==========================================================================
  // 3. 科目映射
  // ==========================================================================

  @Test
  void 科目映射真落库并能被关键词筛到() throws Exception {
    var account = createAccount();
    var map = createSubjectMap(account.id(), "1122", "EX-AR-1122");

    assertThat(map.localSubjectCode()).isEqualTo("1122");
    assertThat(map.externalSubjectCode()).isEqualTo("EX-AR-1122");
    assertThat(map.accountName()).isEqualTo("美国主体 - 金蝶");
    assertThat(map.enabled()).isTrue();

    var rows = read(getOf("/subject-maps", "session-a"), FinanceSoftwareSubjectMapRow[].class);
    assertThat(rows).hasSize(1);

    var hit = read(getOf("/subject-maps?keyword=EX-AR", "session-a"), FinanceSoftwareSubjectMapRow[].class);
    assertThat(hit).hasSize(1);
    var miss = read(getOf("/subject-maps?keyword=EX-NOPE", "session-a"), FinanceSoftwareSubjectMapRow[].class);
    assertThat(miss).isEmpty();
  }

  @Test
  void 一个外部科目不能映射到两个本系统科目() throws Exception {
    var account = createAccount();
    createSubjectMap(account.id(), "1122", "EX-AR-1122");

    // 同一个外部科目挂到别的本地科目上 ⇒ 必须被挡住（导出会串科目）。
    var r =
        send(
            "POST",
            "/subject-maps",
            new FinanceSoftwareSubjectMapInput(
                account.id(), "2202", "其他应收", "EX-AR-1122", "", "", "", true, ""),
            "session-a");
    assertThat(r.getStatus()).as("一个外部科目不能映射到两个本系统科目").isEqualTo(400);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_subject_map"))).hasSize(1);
  }

  @Test
  void 科目映射的修改与删除都真落库() throws Exception {
    var account = createAccount();
    var map = createSubjectMap(account.id(), "1122", "EX-AR-1122");

    // 改（同一个本地科目 → 新的外部编码）。
    var updated =
        read(
            send(
                "POST",
                "/subject-maps",
                new FinanceSoftwareSubjectMapInput(
                    account.id(), "1122", "应收账款", "EX-AR-NEW", "外部新", "", "", true, "改过了"),
                "session-a"),
            FinanceSoftwareSubjectMapRow.class);
    assertThat(updated.id()).isEqualTo(map.id());
    assertThat(updated.externalSubjectCode()).isEqualTo("EX-AR-NEW");
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_subject_map"))).hasSize(1);

    // 删。
    var deleted = http.perform(delete("/platform/finance-software/subject-maps/" + map.id())
            .header("Authorization", "session-a"))
        .andReturn()
        .getResponse();
    assertThat(deleted.getStatus()).isEqualTo(200);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_subject_map"))).isEmpty();
  }

  // ==========================================================================
  // 4. 主数据映射（同步的「映射」这一半是真的；直连同步那一半未接入）
  // ==========================================================================

  @Test
  void 主数据映射真落库并能按类型筛选() throws Exception {
    var account = createAccount();
    var m = createMasterMap(account.id(), "CUSTOMER", "C-001", "Acme US", "CUST-EX-001");
    assertThat(m.masterType()).isEqualTo("CUSTOMER");
    assertThat(m.masterTypeLabel()).isEqualTo("客户");
    assertThat(m.externalCode()).isEqualTo("CUST-EX-001");

    var rows = read(getOf("/master-maps", "session-a"), FinanceSoftwareMasterMapRow[].class);
    assertThat(rows).hasSize(1);
    var customer =
        read(getOf("/master-maps?masterType=CUSTOMER", "session-a"), FinanceSoftwareMasterMapRow[].class);
    assertThat(customer).hasSize(1);
    var supplier =
        read(getOf("/master-maps?masterType=SUPPLIER", "session-a"), FinanceSoftwareMasterMapRow[].class);
    assertThat(supplier).isEmpty();
  }

  @Test
  void 同类主数据重名被挡住因为导出按名称匹配() throws Exception {
    var account = createAccount();
    createMasterMap(account.id(), "CUSTOMER", "C-001", "Acme US", "CUST-EX-001");

    var r =
        send(
            "POST",
            "/master-maps",
            new FinanceSoftwareMasterMapInput(
                account.id(), "CUSTOMER", "C-002", "Acme US", "CUST-EX-002", "", true, ""),
            "session-a");
    assertThat(r.getStatus()).as("重名会让导出匹配到多条映射").isEqualTo(400);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_master_map"))).hasSize(1);
  }

  @Test
  void 非法的主数据类型被挡() throws Exception {
    var account = createAccount();
    var r =
        send(
            "POST",
            "/master-maps",
            new FinanceSoftwareMasterMapInput(
                account.id(), "NOT_A_TYPE", "C-001", "Acme US", "CUST-EX-001", "", true, ""),
            "session-a");
    assertThat(r.getStatus()).isEqualTo(400);
  }

  // ==========================================================================
  // 5. 标准凭证文件导出：内容 + sha256 + 唯一性
  // ==========================================================================

  @Test
  void 凭证文件导出产出真内容并把sha256与字节数算对() throws Exception {
    var account = createAccount();
    configureMappings(account.id());

    var result = export(account.id(), "CSV", "2026-01-01", "2026-02-28");

    // 🔴 内容是**真内容**：本地科目被换成外部科目编码，辅助核算带出外部编码，
    //   没配映射的行**留在这里**（外部科目列为空）而不是被丢掉。
    var expected =
        "﻿凭证号,凭证日期,会计期间,行号,本系统科目编码,本系统科目名称,外部科目编码,摘要,"
            + "借方金额,贷方金额,币种,辅助核算类型,辅助核算名称,外部辅助核算编码\n"
            + "V-2026-0001,2026-01-15,2026-01,1,1122,应收账款,EX-AR-1122,Amazon 货款,1000,0,USD,CUSTOMER,Acme US,CUST-EX-001\n"
            + "V-2026-0001,2026-01-15,2026-01,2,6001,主营业务收入,EX-REV-6001,Amazon 货款,0,1000,USD,,,\n"
            + "V-2026-0002,2026-02-10,2026-02,1,6602,管理费用,,头程运费,300,0,USD,,,\n"
            + "V-2026-0002,2026-02-10,2026-02,2,1002,银行存款,EX-BANK-1002,头程运费,0,300,USD,,,\n";
    assertThat(result.content()).as("导出内容必须是真内容").isEqualTo(expected);
    assertThat(result.content()).startsWith("﻿").as("CSV 带 UTF-8 BOM（Excel 双击不乱码）");

    // 真数：两张凭证、四行分录、一行没配映射（6602）。
    assertThat(result.voucherCount()).isEqualTo(2);
    assertThat(result.lineCount()).isEqualTo(4);
    assertThat(result.unmappedLineCount()).as("没配映射的行要被标出来，不许静默丢行").isEqualTo(1);
    assertThat(result.totalDebit()).isEqualByComparingTo("1300");
    assertThat(result.totalCredit()).isEqualByComparingTo("1300");
    assertThat(result.currency()).isEqualTo("USD");
    assertThat(result.filename()).isEqualTo("vouchers-BOOK-001-2026-01-01-2026-02-28.csv");

    // 🔴 sha256 与字节数是**独立算出来**的（不复用被测代码）。
    assertThat(result.contentSha256()).isEqualTo(sha256Of(result.content()));
    assertThat(result.contentSha256()).hasSize(64);
    assertThat(result.contentBytes()).isEqualTo(result.content().getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void 导出的文件能原样取回且sha256指的就是那一份() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var result = export(account.id(), "CSV", "2026-01-01", "2026-02-28");

    var detail =
        read(
            getOf("/voucher-files/" + result.fileId(), "session-a"),
            FinanceSoftwareVoucherFileDetail.class);
    assertThat(detail.content()).as("事后必须能原样取回当初导出的那一份").isEqualTo(result.content());
    assertThat(detail.contentType()).isEqualTo("text/csv; charset=utf-8");
    assertThat(detail.file().contentSha256()).isEqualTo(result.contentSha256());
    assertThat(detail.file().fileNo()).isEqualTo(result.fileNo());

    // 库里落的那一份，内容与哈希必须与返回的一致。
    var row =
        inTenant(
            () ->
                sql.fetchOne(
                    "select content, content_sha256, content_bytes from platform_finance_voucher_file where id = ?",
                    result.fileId()));
    assertThat(row.get("content", String.class)).isEqualTo(result.content());
    assertThat(row.get("content_sha256", String.class)).isEqualTo(sha256Of(result.content()));
    assertThat(row.get("content_bytes", Integer.class)).isEqualTo(result.contentBytes());
  }

  @Test
  void 每次导出有唯一单号而内容变化时sha256跟着变() throws Exception {
    var account = createAccount();
    configureMappings(account.id());

    var first = export(account.id(), "CSV", "2026-01-01", "2026-02-28");
    var second = export(account.id(), "CSV", "2026-01-01", "2026-02-28");

    // 记录唯一：两次导出是两条不同的留痕（单号不同、id 不同）。
    assertThat(second.fileId()).isNotEqualTo(first.fileId());
    assertThat(second.fileNo()).isNotEqualTo(first.fileNo());
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file"))).hasSize(2);

    // 同内容 ⇒ 同哈希（指纹指认的是内容，不是记录）。
    assertThat(second.contentSha256()).isEqualTo(first.contentSha256());

    // 内容变了 ⇒ 哈希必须跟着变（否则 sha256 指认不了任何东西）。
    createSubjectMap(account.id(), "1122", "EX-AR-CHANGED");
    var third = export(account.id(), "CSV", "2026-01-01", "2026-02-28");
    assertThat(third.content()).contains("EX-AR-CHANGED").doesNotContain("EX-AR-1122");
    assertThat(third.contentSha256()).isNotEqualTo(first.contentSha256());
    assertThat(third.contentSha256()).isEqualTo(sha256Of(third.content()));
  }

  @Test
  void 三种文件格式都产出各自形状的真内容() throws Exception {
    var account = createAccount();
    configureMappings(account.id());

    var csv = export(account.id(), "CSV", "2026-01-01", "2026-02-28");
    assertThat(csv.content()).contains("EX-AR-1122").contains("CUST-EX-001");
    assertThat(csv.contentType()).isEqualTo("text/csv; charset=utf-8");

    var excel = export(account.id(), "EXCEL", "2026-01-01", "2026-02-28");
    assertThat(excel.content()).contains("urn:schemas-microsoft-com:office:spreadsheet");
    assertThat(excel.content()).contains("EX-AR-1122").contains("CUST-EX-001");
    assertThat(excel.contentType()).isEqualTo("application/vnd.ms-excel");
    assertThat(excel.filename()).endsWith(".xml");

    var xml = export(account.id(), "XML", "2026-01-01", "2026-02-28");
    assertThat(xml.content()).contains("<Vouchers account=\"美国主体 - 金蝶\"");
    assertThat(xml.content()).contains("subjectCode=\"EX-AR-1122\"");
    assertThat(xml.content()).contains("auxiliaryCode=\"CUST-EX-001\"");
    assertThat(xml.content()).contains("type=\"DEBIT\"").contains("type=\"CREDIT\"");
    assertThat(xml.contentType()).isEqualTo("application/xml; charset=utf-8");
  }

  @Test
  void 区间里没有已过账凭证时如实报错而不是导出空文件() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var r =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                account.id(), "CSV", LocalDate.parse("2030-01-01"), LocalDate.parse("2030-12-31"), ""),
            "session-a");
    assertThat(r.getStatus()).as("草稿不是账 —— 区间为空要报错").isEqualTo(400);
    assertThat(detail(r)).contains("已过账");
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file"))).isEmpty();
  }

  @Test
  void 超过导出上限时让调用方缩小区间而不是偷偷截断() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var r =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                account.id(), "CSV", LocalDate.parse(OVERSIZE_RANGE), LocalDate.parse(OVERSIZE_RANGE), ""),
            "session-a");
    assertThat(r.getStatus()).as("超上限必须报错，不许只导一部分").isEqualTo(400);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file"))).isEmpty();
  }

  @Test
  void 上游读不到时如实答503而不是返回空集合() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var r =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                account.id(), "CSV", LocalDate.parse(FAIL_RANGE), LocalDate.parse(FAIL_RANGE), ""),
            "session-a");
    assertThat(r.getStatus()).as("取不到就 503，不是「上游没有凭证」").isEqualTo(503);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_voucher_file"))).isEmpty();
  }

  @Test
  void 导出凭证文件与推送是两个独立的权限码() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    // session-export 有 read + export ⇒ 导出必须放行。
    var r =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                account.id(), "CSV", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28"), ""),
            "session-export");
    assertThat(r.getStatus()).as("有 export 码就应当放行导出").isEqualTo(200);

    // 只有 read 的会话连导出都不行。
    var denied =
        send(
            "POST",
            "/voucher-files/export",
            new FinanceSoftwareVoucherExportInput(
                account.id(), "CSV", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28"), ""),
            "session-readonly");
    assertThat(denied.getStatus()).isEqualTo(403);
  }

  // ==========================================================================
  // 6. 余额核对：差异是**算出来的**
  // ==========================================================================

  /** 建一个核对任务（期间必须是上游真有的）。 */
  private FinanceSoftwareReconcileTaskRow createReconcileTask(UUID accountId, String currency)
      throws Exception {
    var r =
        send(
            "POST",
            "/reconcile-tasks",
            new FinanceSoftwareReconcileTaskInput(
                accountId, PERIOD_FROM, PERIOD_TO, currency, "月度核对"),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareReconcileTaskRow.class);
  }

  /** 算差异（失败时把错误原文带出来）。 */
  private FinanceSoftwareReconcileTaskRow compute(UUID taskId) throws Exception {
    var r = send("POST", "/reconcile-tasks/" + taskId + "/compute", null, "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);
    return read(r, FinanceSoftwareReconcileTaskRow.class);
  }

  private FinanceSoftwareReconcileBalanceInput balance(
      String code, String ccy, String direction, String amount) {
    return new FinanceSoftwareReconcileBalanceInput(code, "外部" + code, ccy, direction, new BigDecimal(amount));
  }

  @Test
  void 余额核对算出四种差异且差异方向是借正贷负() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var task = createReconcileTask(account.id(), "USD");
    assertThat(task.status()).isEqualTo("DRAFT");
    assertThat(task.reconcileNo()).startsWith("RC-");

    var r =
        send(
            "POST",
            "/reconcile-tasks/" + task.id() + "/balances",
            new FinanceSoftwareBalanceImportInput(
                List.of(
                    balance("EX-AR-1122", "USD", "DEBIT", "1000"),
                    balance("EX-REV-6001", "USD", "CREDIT", "1000"),
                    balance("EX-BANK-1002", "USD", "DEBIT", "250"),
                    // 外部有、本地没有，且金额为 0 ⇒ 两边都是 0，**不是差异**。
                    balance("EX-ZERO-000", "USD", "DEBIT", "0"),
                    balance("EX-EUR-0001", "EUR", "DEBIT", "70"))),
            "session-a");
    assertThat(r.getStatus()).as(detail(r)).isEqualTo(200);

    var computed = compute(task.id());
    assertThat(computed.status()).isEqualTo("COMPUTED");
    assertThat(computed.computedAt()).isNotNull();
    assertThat(computed.matchedCount()).as("1122 与 6001 两边一致").isEqualTo(2);
    assertThat(computed.differenceCount()).as("三处真差异").isEqualTo(3);
    assertThat(computed.localSubjectCount()).isEqualTo(3);
    assertThat(computed.externalSubjectCount()).isEqualTo(5);
    // 差异总额取**绝对值**口径（净额口径会让正负相抵，看起来「对上了」）。
    assertThat(computed.totalDifference()).isEqualByComparingTo("620");

    var detail =
        read(getOf("/reconcile-tasks/" + task.id(), "session-a"), FinanceSoftwareReconcileDetail.class);
    var diffs = detail.diffs();
    assertThat(diffs).hasSize(3);

    // 币种不一致：不能相减。
    var mismatch = diffs.get(0);
    assertThat(mismatch.reason()).isEqualTo("CURRENCY_MISMATCH");
    assertThat(mismatch.externalSubjectCode()).isEqualTo("EX-EUR-0001");
    assertThat(mismatch.difference()).isEqualByComparingTo("70");
    assertThat(mismatch.detail()).contains("币种");

    // 仅外部有：本地试算平衡表里没有 1002。
    var onlyExternal = diffs.get(1);
    assertThat(onlyExternal.reason()).isEqualTo("ONLY_EXTERNAL");
    assertThat(onlyExternal.externalSubjectCode()).isEqualTo("EX-BANK-1002");
    assertThat(onlyExternal.externalBalance()).isEqualByComparingTo("250");
    assertThat(onlyExternal.localBalance()).isEqualByComparingTo("0");
    assertThat(onlyExternal.difference()).isEqualByComparingTo("250");

    // 仅本地有：本地 6602 有 300，外部余额表里没有它。
    var onlyLocal = diffs.get(2);
    assertThat(onlyLocal.reason()).isEqualTo("ONLY_LOCAL");
    assertThat(onlyLocal.localSubjectCode()).isEqualTo("6602");
    // 🔴 外部那边**根本没有对应科目** ⇒ 外部科目列放的是**带前缀的占位符**，
    //   不是空串（迁移的 CHECK 与 UNIQUE 都要求它非空），也不是裸本地编码
    //   （那会与「对方恰好用同一个编码、而我们没配映射」的外部科目撞唯一键）。
    assertThat(onlyLocal.externalSubjectCode()).isEqualTo("LOCAL:6602");
    assertThat(onlyLocal.localBalance()).isEqualByComparingTo("300");
    assertThat(onlyLocal.difference()).isEqualByComparingTo("-300");

    // 🔴 金额为 0 的外部科目**不进差异表**（噪声淹没真差异）。
    assertThat(diffs.stream().map(d -> d.externalSubjectCode()))
        .doesNotContain("EX-ZERO-000");
  }

  @Test
  void 两边方向不同会被标出来而不是被翻正抹平() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var task = createReconcileTask(account.id(), "USD");

    // 本地 1122 是借方 1000，外部说是贷方 1000 ⇒ 事实就是差 200。
    send(
        "POST",
        "/reconcile-tasks/" + task.id() + "/balances",
        new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "USD", "CREDIT", "1000"))),
        "session-a");

    var computed = compute(task.id());
    assertThat(computed.matchedCount()).isZero();
    // 三处差异：1122 两边方向不同（差 2000）+ 6001 / 6602 只在本地有（1000 + 300）。
    assertThat(computed.differenceCount()).isEqualTo(3);
    assertThat(computed.totalDifference()).isEqualByComparingTo("3300");

    var detail =
        read(getOf("/reconcile-tasks/" + task.id(), "session-a"), FinanceSoftwareReconcileDetail.class);
    var diff =
        detail.diffs().stream()
            .filter(d -> "BALANCE_DIFF".equals(d.reason()))
            .findFirst()
            .orElseThrow();
    // 🔴 **不把外部余额翻到本地方向**：两边方向不同就是事实上的差，
    //   翻一下就把 2000 的差额抹成 0 —— 那是在抹平一个真实存在的差异。
    assertThat(diff.externalSubjectCode()).isEqualTo("EX-AR-1122");
    assertThat(diff.externalBalance()).isEqualByComparingTo("-1000");
    assertThat(diff.localBalance()).isEqualByComparingTo("1000");
    assertThat(diff.difference()).isEqualByComparingTo("-2000");
    assertThat(diff.detail()).contains("方向");
  }

  @Test
  void 没导入外部余额就计算会被挡住() throws Exception {
    var account = createAccount();
    var task = createReconcileTask(account.id(), "USD");
    var r = send("POST", "/reconcile-tasks/" + task.id() + "/compute", null, "session-a");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(detail(r)).contains("外部余额");
  }

  @Test
  void 导入的余额必须是绝对值且必须带方向() throws Exception {
    var account = createAccount();
    var task = createReconcileTask(account.id(), "USD");

    var negative =
        send(
            "POST",
            "/reconcile-tasks/" + task.id() + "/balances",
            new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "USD", "DEBIT", "-100"))),
            "session-a");
    assertThat(negative.getStatus()).as("负数会让核对凭空差出一倍").isEqualTo(400);

    var noDirection =
        send(
            "POST",
            "/reconcile-tasks/" + task.id() + "/balances",
            new FinanceSoftwareBalanceImportInput(
                List.of(new FinanceSoftwareReconcileBalanceInput("EX-AR-1122", "", "USD", "", new BigDecimal("100")))),
            "session-a");
    assertThat(noDirection.getStatus()).as("方向缺了同一个余额会差出一倍").isEqualTo(400);
  }

  @Test
  void 重复导入同一科目是覆盖而不是追加() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var task = createReconcileTask(account.id(), "USD");

    send(
        "POST",
        "/reconcile-tasks/" + task.id() + "/balances",
        new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "USD", "DEBIT", "1000"))),
        "session-a");
    send(
        "POST",
        "/reconcile-tasks/" + task.id() + "/balances",
        new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "USD", "DEBIT", "1200"))),
        "session-a");

    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_balance"))).hasSize(1);
    var detail =
        read(getOf("/reconcile-tasks/" + task.id(), "session-a"), FinanceSoftwareReconcileDetail.class);
    assertThat(detail.balances()).hasSize(1);
    assertThat(detail.balances().get(0).externalBalance()).isEqualByComparingTo("1200");
  }

  @Test
  void 上游试算平衡表读不到时如实答503() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    // EUR 口径的上游会 500（夹具约定）⇒ 本服务必须 503。
    var task = createReconcileTask(account.id(), "EUR");
    send(
        "POST",
        "/reconcile-tasks/" + task.id() + "/balances",
        new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "EUR", "DEBIT", "1000"))),
        "session-a");

    var r = send("POST", "/reconcile-tasks/" + task.id() + "/compute", null, "session-a");
    assertThat(r.getStatus()).as("取不到就 503，不是「本地余额为 0」").isEqualTo(503);
  }

  @Test
  void 算过差异的核对任务不能删() throws Exception {
    var account = createAccount();
    configureMappings(account.id());
    var task = createReconcileTask(account.id(), "USD");
    send(
        "POST",
        "/reconcile-tasks/" + task.id() + "/balances",
        new FinanceSoftwareBalanceImportInput(List.of(balance("EX-AR-1122", "USD", "DEBIT", "1000"))),
        "session-a");
    send("POST", "/reconcile-tasks/" + task.id() + "/compute", null, "session-a");

    var r =
        http.perform(delete("/platform/finance-software/reconcile-tasks/" + task.id())
                .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).as("已算过的结论要留着").isEqualTo(409);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_task"))).hasSize(1);
  }

  @Test
  void 还没算的核对任务可以删() throws Exception {
    var account = createAccount();
    var task = createReconcileTask(account.id(), "USD");
    var r =
        http.perform(delete("/platform/finance-software/reconcile-tasks/" + task.id())
                .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_reconcile_task"))).isEmpty();
  }

  @Test
  void 期间必须是上游真有的() throws Exception {
    var account = createAccount();
    var r =
        send(
            "POST",
            "/reconcile-tasks",
            new FinanceSoftwareReconcileTaskInput(
                account.id(),
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                PERIOD_TO,
                "USD",
                ""),
            "session-a");
    assertThat(r.getStatus()).as("上游没有的期间要当场被挡住").isEqualTo(400);
    assertThat(detail(r)).contains("12.5.2.1");
  }

  // ==========================================================================
  // 7. 连接档案 / 概览 / 上游筛选项
  // ==========================================================================

  @Test
  void 连接档案的增改停用与概览计数都是真的() throws Exception {
    var account = createAccount();
    assertThat(account.vendor()).isEqualTo("KINGDEE");
    assertThat(account.vendorLabel()).isEqualTo("金蝶");
    assertThat(account.statusLabel()).isEqualTo("启用");
    assertThat(account.subjectMapCount()).isZero();
    assertThat(account.currency()).isEqualTo("USD");

    configureMappings(account.id());

    var rows = read(getOf("/accounts", "session-a"), FinanceSoftwareAccountRow[].class);
    assertThat(rows).hasSize(1);
    assertThat(rows[0].subjectMapCount()).isEqualTo(3);
    assertThat(rows[0].masterMapCount()).isEqualTo(1);

    // 厂商筛选是真筛选。
    var kingdee = read(getOf("/accounts?vendor=KINGDEE", "session-a"), FinanceSoftwareAccountRow[].class);
    assertThat(kingdee).hasSize(1);
    var sap = read(getOf("/accounts?vendor=SAP", "session-a"), FinanceSoftwareAccountRow[].class);
    assertThat(sap).isEmpty();

    // 停用（**不删**：历史凭证文件要能复现）。
    var disabled =
        read(
            send("POST", "/accounts/" + account.id() + "/disable", null, "session-a"),
            FinanceSoftwareAccountRow.class);
    assertThat(disabled.status()).isEqualTo("DISABLED");
    assertThat(disabled.statusLabel()).isEqualTo("停用");
    assertThat(inTenant(() -> sql.fetch("select id from platform_finance_account"))).hasSize(1);

    var overview = read(getOf("/overview", "session-a"), FinanceSoftwareOverview.class);
    assertThat(overview.accounts()).isEqualTo(1);
    assertThat(overview.activeAccounts()).isZero();
    assertThat(overview.subjectMaps()).isEqualTo(3);
    assertThat(overview.masterMaps()).isEqualTo(1);
    assertThat(overview.generatedAt()).isNotNull();
  }

  @Test
  void 非法厂商与非法币种被挡住() throws Exception {
    var badVendor =
        send(
            "POST",
            "/accounts",
            new FinanceSoftwareAccountInput("X", "TALLY", "B1", "", "USD", 1, "ACTIVE", ""),
            "session-a");
    assertThat(badVendor.getStatus()).isEqualTo(400);

    var badCurrency =
        send(
            "POST",
            "/accounts",
            new FinanceSoftwareAccountInput("Y", "SAP", "B1", "", "US", 1, "ACTIVE", ""),
            "session-a");
    assertThat(badCurrency.getStatus()).isEqualTo(400);
  }

  @Test
  void 上游筛选项里的期间与币种是真读上去的() throws Exception {
    var periods = read(getOf("/periods", "session-a"), FinanceSoftwarePeriodOption[].class);
    assertThat(periods).hasSize(2);
    assertThat(periods[0].id()).isEqualTo(PERIOD_FROM.toString());
    assertThat(periods[0].periodCode()).isEqualTo("2026-01");

    var currencies = read(getOf("/currencies", "session-a"), String[].class);
    assertThat(currencies).containsExactly("USD");
  }

  // ==========================================================================
  // 8. 数据层纪律
  // ==========================================================================

  @Test
  void 本模块的金额列一律是numeric没有一个浮点列() {
    var floating =
        sql.fetch(
            "select table_name, column_name, data_type from information_schema.columns"
                + " where table_name in ('platform_finance_account','platform_finance_subject_map',"
                + "'platform_finance_master_map','platform_finance_voucher_file',"
                + "'platform_finance_reconcile_task','platform_finance_reconcile_balance',"
                + "'platform_finance_reconcile_diff')"
                + " and data_type in ('double precision','real','float4','float8')");
    assertThat(floating).as("金额/余额/差额必须是 numeric").isEmpty();

    var amountColumns =
        sql.fetch(
            "select table_name, column_name, data_type, numeric_precision, numeric_scale"
                + " from information_schema.columns"
                + " where table_name in ('platform_finance_voucher_file','platform_finance_reconcile_balance',"
                + "'platform_finance_reconcile_diff','platform_finance_reconcile_task')"
                + " and column_name in ('total_debit','total_credit','external_balance','local_balance',"
                + "'difference','total_difference')");
    assertThat(amountColumns).as("七列金额必须都在").hasSize(7);
    assertThat(amountColumns)
        .allSatisfy(
            c -> {
              assertThat(c.get("data_type", String.class)).isEqualTo("numeric");
              assertThat(c.get("numeric_precision", Integer.class)).isEqualTo(20);
              assertThat(c.get("numeric_scale", Integer.class)).isEqualTo(6);
            });
  }

  @Test
  void 时间列一律是timestamptz() {
    var timestamps =
        sql.fetch(
            "select table_name, column_name, data_type from information_schema.columns"
                + " where table_name like 'platform_finance_%'"
                + " and column_name in ('created_at','updated_at','computed_at')");
    assertThat(timestamps).isNotEmpty();
    assertThat(timestamps)
        .allSatisfy(c -> assertThat(c.get("data_type", String.class)).isEqualTo("timestamp with time zone"));
  }
}
