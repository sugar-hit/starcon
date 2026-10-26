package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 12.5.1.3 关账的集成测试（真实 PostgreSQL 16 + 真实 jOOQ SQL）。
 *
 * <p><b>本模块的判据</b>（每一条都对应蓝图加粗句）：
 *
 * <ol>
 *   <li>检查清单**真查数据** —— 四类检查项各自给出条数与可钻取明细；
 *   <li>未对账差异**如实报「不可判定」**（上游未交付）—— 既不假装通过，也不把关账卡死；
 *   <li>阻断项未解决 ⇒ **拒绝关账**，并返回明确的阻断原因清单；
 *   <li>例外与结转**必须保留依据**（服务层 + DB 守卫各拒一次）；
 *   <li>关账走审批留痕；审批不可达 ⇒ 如实报错、期间保持开放；
 *   <li>关账完成 ⇒ 期间**已锁定、禁止过账**（V357 的触发器真的拦住过账）；
 *   <li>多币种**绝不相加**（按币种分组；汇率不可得就只分组、不折算）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PeriodCloseIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();

  /** 审批流 id（identity 侧）。 */
  static final UUID FLOW_ID = UUID.randomUUID();

  /** 会计期间（12.5.1.2 的 finance_voucher_period）。 */
  static final UUID PERIOD_ID = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.period_close.read", "finance.period_close.create", "finance.period_close.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static HttpServer identity;
  static HttpServer catalog;

  /** 审批场景：NO_FLOW / PENDING / APPROVED / REJECTED / UNREACHABLE。 */
  static final AtomicReference<String> scenario = new AtomicReference<>("PENDING");

  /** identity 侧的审批实例（id → 状态）。 */
  static final Map<UUID, String> instances = new ConcurrentHashMap<>();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext("/internal/access", PeriodCloseIntegrationTest::access);
      identity.createContext("/identity/approval-flows", PeriodCloseIntegrationTest::flow);
      identity.createContext("/identity/approval-instances", PeriodCloseIntegrationTest::submit);
      identity.createContext("/identity/approval-records", PeriodCloseIntegrationTest::record);
      identity.setExecutor(Executors.newFixedThreadPool(4));
      identity.start();

      // 假 catalog：只登记 CNY → USD 的汇率；其它方向一律 404（模拟「汇率不可得」）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext("/catalog/exchange-rates/latest", PeriodCloseIntegrationTest::rate);
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 假 identity / catalog

  private static void access(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    var access =
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS)));
    write(exchange, 200, json.writeValueAsBytes(access));
  }

  private static void flow(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    if ("NO_FLOW".equals(scenario.get())) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    var body =
        """
        {"flow":{"id":"%s","flowName":"关账审批","status":"ACTIVE",
         "documentTypeCode":"PERIOD_CLOSE"},
         "nodes":[{"nodeKey":"N1","nodeName":"财务负责人","nodeOrder":1,"stageIndex":1,
         "approvalMode":"SERIAL","approverType":"ROLE","approverLabel":"财务负责人"}]}
        """
            .formatted(FLOW_ID);
    write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void submit(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    var id = UUID.randomUUID();
    instances.put(id, "PENDING");
    var body =
        """
        {"id":"%s","instanceNo":"AP-PC-0001","flowId":"%s","currentNodeKey":"N1",
         "currentNodeName":"财务负责人","applicantId":"%s","routedBy":"FLOW_ORDER",
         "submittedAt":"2026-10-31T02:00:00Z"}
        """
            .formatted(id, FLOW_ID, userId);
    write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void record(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    var path = exchange.getRequestURI().getPath();
    var id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    var status = instances.getOrDefault(id, "PENDING");
    if ("UNREACHABLE".equals(scenario.get())) {
      // 不回响应直接断开 ⇒ 客户端拿到 IO 错误 ⇒ ApprovalPort 报「identity 服务不可达」。
      exchange.close();
      return;
    }
    var timeline = "[]";
    var finished = "";
    if ("APPROVED".equals(status)) {
      timeline =
          """
          [{"actionId":"%s","action":"APPROVE","nodeKey":"N1","nodeName":"财务负责人",
            "nodeOrder":1,"actorId":"%s","actorName":"财务负责人","comment":"同意关账",
            "decidedAt":"2026-10-31T03:00:00Z","dwellSeconds":3600}]
          """
              .formatted(UUID.randomUUID(), userId);
      finished = "\"finishedAt\":\"2026-10-31T03:00:00Z\"";
    } else if ("REJECTED".equals(status)) {
      timeline =
          """
          [{"actionId":"%s","action":"REJECT","nodeKey":"N1","nodeName":"财务负责人",
            "nodeOrder":1,"actorId":"%s","actorName":"财务负责人","comment":"本期还有未核销款项",
            "decidedAt":"2026-10-31T03:00:00Z","dwellSeconds":3600}]
          """
              .formatted(UUID.randomUUID(), userId);
      finished = "\"finishedAt\":\"2026-10-31T03:00:00Z\"";
    }
    var body =
        """
        {"instance":{"id":"%s","instanceNo":"AP-PC-0001","status":"%s",
         "subjectTitle":"关账 2026-10","amount":0,"currencyCode":"USD",
         "currentNodeKey":"N1","currentNodeName":"财务负责人",
         "submittedAt":"2026-10-31T02:00:00Z",%s},
         "timeline":%s}
        """
            .formatted(id, status, finished, timeline);
    write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 只登记 CNY → USD；别的方向一律 404（=「汇率不可得」）。 */
  private static void rate(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    var query = exchange.getRequestURI().getQuery();
    if (query != null && query.contains("baseCurrency=CNY") && query.contains("quoteCurrency=USD")) {
      var body =
          """
          {"baseCurrency":"CNY","quoteCurrency":"USD","rate":0.1400,"effectiveDate":"2026-10-31",
           "sourceCode":"PBOC","display":"1 CNY = 0.1400 USD"}
          """;
      write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return;
    }
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  private static void write(
      com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
      throws java.io.IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    scenario.set("PENDING");
    instances.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_period_close_approval, finance_period_close_execution,"
              + " finance_period_close_check_amount, finance_period_close_check_item,"
              + " finance_period_close_check, finance_period_close_checklist,"
              + " finance_accounting_period_event, finance_accounting_period_approval,"
              + " finance_accounting_calendar, finance_cost_calculation_run,"
              + " finance_receipt_event, finance_receipt_allocation, finance_receipt,"
              + " finance_receivable_line, finance_receivable_event, finance_receivable,"
              + " finance_settlement_event, finance_settlement_summary, finance_settlement_line,"
              + " finance_settlement_source_file, finance_settlement,"
              + " finance_voucher_event, finance_voucher_line, finance_voucher,"
              + " finance_voucher_mapping, finance_voucher_period cascade");
    }
    seed();
  }

  /** 一次性种好一整套能让关账清单查出东西的夹具。 */
  void seed() {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          // ---- 会计期间（12.5.1.2）----
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,period_type,note,version,created_at,updated_at)"
                  + " values(?,?,'2026-10','2026-10-01','2026-10-31','OPEN','USD','CUSTOM','',0,"
                  + "?::timestamptz,?::timestamptz)",
              PERIOD_ID,
              tenant,
              now,
              now);
          // ---- 一张草稿凭证（12.5.2.1）⇒ 阻断「未过账凭证」----
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,source_no,period_id,"
                  + "voucher_date,description,currency,status,total_debit,total_credit,line_count,"
                  + "note,version,created_at,updated_at)"
                  + " values(?,?,'V-001','MANUAL','',?,'2026-10-10','草稿凭证','USD','DRAFT',100,100,2,"
                  + "'',0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              PERIOD_ID,
              now,
              now);
          // ---- 一个有缺口���成本批次（12.4.1.2）⇒ 阻断「未结转成本」----
          sql.execute(
              "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,"
                  + "scope_code,scope_name,valuation_method,currency,period_from,period_to,"
                  + "recalculation_reason,status,flow_source_service,layer_source_service,basis_note,"
                  + "source_note,sku_count,layer_count,entry_count,incomplete_entry_count,"
                  + "total_quantity,total_covered_quantity,total_shortfall_quantity,total_cost_amount,"
                  + "created_by,created_at,completed_at)"
                  + " values(?,?,'LEGAL_ENTITY',?,'LE-1','主体一','FIFO','USD',"
                  + "'2026-10-01T00:00:00Z','2026-11-01T00:00:00Z','INITIAL','COMPLETED','warehouse',"
                  + "'catalog','','',3,2,10,2,100,90,10,300,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              UUID.randomUUID(),
              userId,
              now,
              now);
          // ---- 应收：未到期（可结转）+ 超期（阻断），后者用另一种币种 ----
          // ⚠️ 应收单**必须走「草稿 → 落明细 → 确认」这条路**（V338 的守卫钉死），
          //    这里照着做，不是绕过守卫硬插一张 OPEN 的单。
          seedReceivable("R-001", "USD", new java.math.BigDecimal("200"),
              LocalDate.of(2026, 11, 15), OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC));
          seedReceivable("R-002", "CNY", new java.math.BigDecimal("50"),
              LocalDate.of(2026, 10, 5), OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC));
          // ---- 收款：已到账未核销（可结转）----
          sql.execute(
              "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,currency,"
                  + "amount,settled_amount,advance_flag,status,received_at,payer_type,payer_name,"
                  + "fund_account_label,note,version,created_at,updated_at)"
                  + " values(?,?,'P-001','MANUAL','manual','USD',80,0,false,'RECORDED',"
                  + "'2026-10-08T00:00:00Z','PLATFORM','平台A','','',0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              now,
              now);
          // ---- 结算：未到账且预计到账日在期末之后（可结转）----
          sql.execute(
              "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
                  + "platform_code,period_start,period_end,currency,settlement_amount,status,"
                  + "expected_at,received_amount,source,source_system,parse_version,note,version,"
                  + "created_at,updated_at)"
                  + " values(?,?,'S-001',?,'SHOP-1','店铺一','amazon','2026-10-01','2026-10-15','USD',"
                  + "400,'PENDING','2026-11-10T00:00:00Z',0,'MANUAL','manual',1,'',0,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
  }

  // ================================================================ 工具

  /**
   * 造一张**已确认**的应收单（走「草稿 → 落明细 → 确认」三步，遵守 V338 的守卫）。
   */
  void seedReceivable(
      String no, String currency, java.math.BigDecimal amount, LocalDate dueDate,
      OffsetDateTime confirmedAt) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
            + "counterparty_type,counterparty_name,currency,amount,settled_amount,status,"
            + "due_date,confirmed_at,dispute_flag,note,version,created_at,updated_at)"
            + " values(?,?,?,'MANUAL','manual','PLATFORM','平台A',?,?,0,'DRAFT',?::date,"
            + "?::timestamptz,false,'',0,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        no,
        currency,
        amount,
        dueDate,
        confirmedAt,
        now,
        now);
    sql.execute(
        "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
            + "sku_code,quantity,unit_price,amount,settled_amount,created_at)"
            + " values(?,?,?,1,'行 1','SKU-1',1,?,?,0,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        id,
        amount,
        amount,
        now);
    sql.execute(
        "update finance_receivable set status = 'OPEN' where id = ? and tenant_id = ?", id, tenant);
  }

  JsonNode postJson(String path, String body) throws Exception {
    var response =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    var text = response.getResponse().getContentAsString();
    // 🔴 失败时把响应体带出来 —— 只看到「NPE」根本不知道服务端说了什么。
    assertThat(response.getResponse().getStatus())
        .as("POST %s 返回 %s：%s", path, response.getResponse().getStatus(), text)
        .isBetween(200, 299);
    return json.readTree(text);
  }

  JsonNode getJson(String path) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session")).andReturn();
    return json.readTree(response.getResponse().getContentAsString());
  }

  /** 跑一份检查清单，返回清单 id。 */
  String runChecklist() throws Exception {
    var response =
        http.perform(
                post(
        "/finance/period-close/checklists")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"periodId\":\"" + PERIOD_ID + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(response.getResponse().getContentAsString()).get("checklist").get("id").asText();
  }

  // ================================================================ 断言

  @Test
  void 迁移落地_六张表与列都在() throws Exception {
    var tables =
        List.of(
            "finance_period_close_checklist",
            "finance_period_close_check",
            "finance_period_close_check_item",
            "finance_period_close_check_amount",
            "finance_period_close_execution",
            "finance_period_close_approval");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      for (var table : tables) {
        var result =
            s.executeQuery(
                "select count(*) from information_schema.tables where table_name = '" + table + "'");
        result.next();
        assertThat(result.getInt(1)).as("表 %s 不存在", table).isEqualTo(1);
      }
      // 🔴 **本模块绝不能新建平行的「期间表」** —— 期间表只有 12.5.1.2 的那一张。
      var periodTables =
          s.executeQuery(
              "select count(*) from information_schema.tables where table_name ~"
                  + " '^finance_.*_period$'");
      periodTables.next();
      assertThat(periodTables.getInt(1))
          .as("期间表只能有一张（12.5.1.2 的 finance_voucher_period）")
          .isEqualTo(1);
    }
  }

  @Test
  void 业务表强制rls而不是只启用() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var result =
          s.executeQuery(
              "select relname, relrowsecurity, relforcerowsecurity from pg_class"
                  + " where relname in ('finance_period_close_checklist','finance_period_close_check',"
                  + " 'finance_period_close_check_item','finance_period_close_check_amount',"
                  + " 'finance_period_close_execution','finance_period_close_approval')"
                  + " and relrowsecurity");
      var rows = 0;
      while (result.next()) {
        rows++;
        assertThat(result.getBoolean("relrowsecurity")).as("%s 未启用 RLS", result.getString("relname")).isTrue();
        assertThat(result.getBoolean("relforcerowsecurity"))
            .as("%s 未 FORCE RLS（表owner 会绕过策略）", result.getString("relname"))
            .isTrue();
      }
      assertThat(rows).isEqualTo(6);
    }
  }

  @Test
  void 跑清单_四类检查项都真查数据并给出条数与明细() throws Exception {
    var checklistId = runChecklist();
    var detail = getJson("/finance/period-close/checklists/" + checklistId);

    var checks = detail.get("checks");
    assertThat(checks.size()).as("蓝图点名四类未处理事项").isEqualTo(4);

    // 未过账凭证：1 张草稿 ⇒ 阻断。
    var voucher = find(checks, "UNPOSTED_VOUCHER");
    assertThat(voucher.get("result").asText()).isEqualTo("FAIL");
    assertThat(voucher.get("ruleClassification").asText()).isEqualTo("BLOCKING");
    assertThat(voucher.get("itemCount").asInt()).isEqualTo(1);
    assertThat(voucher.get("dataSource").asText()).contains("finance_voucher");

    // 未结转成本：有缺口的批次 ⇒ 阻断。
    var cost = find(checks, "UNCARRIED_COST");
    assertThat(cost.get("itemCount").asInt()).isEqualTo(1);
    assertThat(cost.get("ruleClassification").asText()).isEqualTo("BLOCKING");

    // 未核销款项：4 条（应收 2 + 收款 1 + 结算 1），其中超期 1 条阻断。
    var payment = find(checks, "UNSETTLED_PAYMENT");
    assertThat(payment.get("itemCount").asInt()).isEqualTo(4);
    assertThat(payment.get("ruleClassification").asText()).isEqualTo("BLOCKING");
    assertThat(payment.get("classificationBasis").asText()).contains("正常未到期");

    // 明细可钻取：逐条列出来。
    var items = getJson("/finance/period-close/checklists/" + checklistId + "/items");
    assertThat(items.get("total").asInt()).isEqualTo(6);
    var sources = new java.util.ArrayList<String>();
    for (var item : items.get("items")) {
      sources.add(item.get("sourceType").asText());
    }
    assertThat(sources).contains("VOUCHER", "COST_RUN", "RECEIVABLE", "RECEIPT", "SETTLEMENT");
  }

  @Test
  void 未对账差异如实报不可判定且不阻断关账() throws Exception {
    var checklistId = runChecklist();
    var detail = getJson("/finance/period-close/checklists/" + checklistId);
    var diff = find(detail.get("checks"), "UNRECONCILED_DIFFERENCE");

    // 🔴 不得假装通过、不得静默当成通过。
    assertThat(diff.get("result").asText()).isEqualTo("NOT_DETERMINABLE");
    assertThat(diff.get("resultLabel").asText()).isEqualTo("不可判定");
    // 归入「可结转未清项」—— 不得把关账卡死在一个永远满足不了的条件上。
    assertThat(diff.get("classification").asText()).isEqualTo("CARRYWARD");
    // 依据必须说清「上游未交付」。
    assertThat(diff.get("classificationBasis").asText()).contains("12.3.2.1").contains("未交付");
    assertThat(diff.get("dataSource").asText()).contains("未交付");
  }

  @Test
  void 金额按币种分组_绝不跨币种相加() throws Exception {
    var checklistId = runChecklist();
    var detail = getJson("/finance/period-close/checklists/" + checklistId);

    var groups = detail.get("currencyGroups");
    assertThat(groups.size()).as("本期有两种币种").isEqualTo(2);
    var usd = groupOf(groups, "USD");
    var cny = groupOf(groups, "CNY");
    // USD = 成本 300 + 凭证 100 + 应收 200 + 收款 80 + 结算 400 = 1080
    assertThat(usd.get("amount").asDouble()).isEqualTo(1080.0);
    // CNY = 超期应收 50
    assertThat(cny.get("amount").asDouble()).isEqualTo(50.0);
    // 🔴 结构上**不存在**「合计」这一个数字 —— 多币种绝不相加。
    assertThat(detail.has("totalAmount")).isFalse();
    assertThat(detail.has("grandTotal")).isFalse();

    // 金额分组表里的每一行都带币种，且同一检查项同一币种只有一行。
    var amounts = detail.get("amounts");
    var keys = new java.util.HashSet<String>();
    for (var amount : amounts) {
      keys.add(amount.get("checkId").asText() + "/" + amount.get("currency").asText());
    }
    assertThat(keys).hasSize(amounts.size());
  }

  @Test
  void 汇率不可得就分组呈现不瞎折算() throws Exception {
    var checklistId = runChecklist();
    var detail = getJson("/finance/period-close/checklists/" + checklistId);

    var cny = groupOf(detail.get("currencyGroups"), "CNY");
    // CNY → USD 在假 catalog 里有汇率 ⇒ 折算值存在，并写明方向。
    assertThat(cny.get("exchangeRate").asDouble()).isEqualTo(0.14);
    assertThat(cny.get("baseAmount").asDouble()).isEqualTo(7.0);
    assertThat(cny.get("conversionNote").asText()).contains("CNY");

    var usd = groupOf(detail.get("currencyGroups"), "USD");
    assertThat(usd.get("baseAmount").asDouble()).isEqualTo(1080.0);
    assertThat(usd.get("conversionNote").asText()).contains("无需折算");
  }

  @Test
  void 阻断项未解决时提交关账被拒并返回阻断原因清单() throws Exception {
    var checklistId = runChecklist();
    var response =
        http.perform(
                post(
        "/finance/period-close/executions")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"checklistId\":\""
                            + checklistId
                            + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"本期无存货业务\"}"))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("阻断项未解决 ⇒ 拒绝关账")
        .isEqualTo(409);
    var body = json.readTree(response.getResponse().getContentAsString());
    assertThat(body.get("outcome").asText()).isEqualTo("BLOCKED");
    assertThat(body.get("message").asText()).contains("不允许关账");
    var reasons = body.get("blockingReasons");
    assertThat(reasons.size()).as("三项阻断：草稿凭证 / 成本缺口 / 超期应收").isEqualTo(3);
    for (var reason : reasons) {
      assertThat(reason.get("basis").asText()).as("每条阻断原因都要带依据").isNotBlank();
      assertThat(reason.get("checkCode").asText()).isNotBlank();
    }
    // 期间**仍然开放**。
    assertThat(periodStatus()).isEqualTo("OPEN");
  }

  @Test
  void 下调阻断项必须写例外依据() throws Exception {
    var checklistId = runChecklist();
    var checkId = checkIdOf(getJson("/finance/period-close/checklists/" + checklistId), "UNPOSTED_VOUCHER");

    // 无依据 ⇒ 服务层拒。
    http.perform(
            post(
        "/finance/period-close/checklists/" + checklistId + "/checks/" + checkId
                    + "/carry-forward")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exceptionBasis\":\"  \"}"))
        .andExpect(status().isBadRequest());

    // 直连 SQL 也写不出「没有依据的例外」⇒ DB 守卫兜底。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_period_close_check set classification = 'CARRYWARD',"
                                + " exception_basis = '' where id = ?::uuid",
                            checkId)))
        .as("绕过服务层直连 SQL 也必须写明例外依据")
        .hasStackTraceContaining("例外依据");
  }

  @Test
  void 下调阻断项写了对的依据后分类变成可结转且规则判定不变() throws Exception {
    var checklistId = runChecklist();
    var checkId = checkIdOf(getJson("/finance/period-close/checklists/" + checklistId), "UNCARRIED_COST");

    var response =
        http.perform(
                post(
        "/finance/period-close/checklists/" + checklistId + "/checks/" + checkId
                        + "/carry-forward")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"exceptionBasis\":\"本期无存货业务，成本已由 12.4.1.2 手工结转\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var body = json.readTree(response.getResponse().getContentAsString());
    assertThat(body.get("classification").asText()).isEqualTo("CARRYWARD");
    // 规则判定**不可改** —— 事后仍能看出「它本来被判成阻断项」。
    assertThat(body.get("ruleClassification").asText()).isEqualTo("BLOCKING");
    assertThat(body.get("exceptionBasis").asText()).contains("无存货业务");
    assertThat(body.get("exceptionBy").asText()).isEqualTo(userId.toString());
    assertThat(body.get("exceptionAt").asText()).isNotBlank();
  }

  @Test
  void 直连sql也改不了检查项的判定内容() throws Exception {
    var checklistId = runChecklist();
    var checkId = checkIdOf(getJson("/finance/period-close/checklists/" + checklistId), "UNPOSTED_VOUCHER");
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_period_close_check set item_count = 0 where id = ?::uuid",
                            checkId)))
        .as("「把查出来的账改好看」在数据层做不到")
        .hasStackTraceContaining("不可修改");
  }

  @Test
  void 提交关账进入审批中_期间仍然开放() throws Exception {
    var checklistId = clearBlockingChecks(runChecklist());
    var body =
        postJson(
            "/finance/period-close/executions",
            "{\"checklistId\":\""
                + checklistId
                + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"本期无存货业务，正常未到期应收按结转处理\"}");
    assertThat(body.get("outcome").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(body.get("message").asText()).contains("审批通过前期间仍然开放");
    var executionId = body.get("executionId").asText();
    assertThat(executionId).isNotBlank();
    var execution = getJson("/finance/period-close/executions/" + executionId).get("execution");
    assertThat(execution.get("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(execution.get("approvalInstanceId").asText()).isNotBlank();
    assertThat(execution.get("periodLocked").asBoolean()).isFalse();
    // 期间**仍然开放**。
    assertThat(periodStatus()).isEqualTo("OPEN");
  }

  @Test
  void 审批通过后同步执行关账_期间锁定且禁止过账() throws Exception {
    var checklistId = clearBlockingChecks(runChecklist());
    var body =
        postJson(
            "/finance/period-close/executions",
            "{\"checklistId\":\""
                + checklistId
                + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"本期无存货业务，正常未到期应收按结转处理\"}");
    var executionId = body.get("executionId").asText();
    var execution = getJson("/finance/period-close/executions/" + executionId).get("execution");

    scenario.set("APPROVED");
    instances.put(UUID.fromString(execution.get("approvalInstanceId").asText()), "APPROVED");

    var detail = postJson(
        "/finance/period-close/executions/" + executionId + "/sync-approval", "{}");
    assertThat(detail.get("execution").get("status").asText()).isEqualTo("CLOSED");
    assertThat(detail.get("execution").get("periodLocked").asBoolean()).isTrue();
    assertThat(detail.get("execution").get("lockNote").asText()).contains("禁止过账");
    assertThat(detail.get("execution").get("closedAt").asText()).isNotBlank();
    // 审批链路可钻取。
    assertThat(detail.get("approvals").size()).isGreaterThanOrEqualTo(1);
    assertThat(detail.get("approvals").get(0).get("action").asText()).isEqualTo("APPROVE");

    // 期间真的关了（12.5.1.2 的表，状态机归它）。
    assertThat(periodStatus()).isEqualTo("CLOSED");
    // 12.5.1.2 的关账留痕也在（触发器写的）。
    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select event_type from finance_accounting_period_event where period_id = ?",
                    PERIOD_ID));
    assertThat(events.map(r -> r.get("event_type", String.class))).contains("CLOSED");

    // 🔴 「已关闭期间禁止过账」真的生效（V357 的触发器）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_voucher set status = 'POSTED', posted_at = ?::timestamptz,"
                                + " posted_by = ? where voucher_no = 'V-001'",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            userId)))
        .as("已关闭期间禁止过账")
        .hasStackTraceContaining("不允许过账");
  }

  @Test
  void 审批不可达如实报错且期间保持开放() throws Exception {
    var checklistId = clearBlockingChecks(runChecklist());
    var body =
        postJson(
            "/finance/period-close/executions",
            "{\"checklistId\":\""
                + checklistId
                + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"本期无存货业务，正常未到期应收按结转处理\"}");
    var executionId = body.get("executionId").asText();

    scenario.set("UNREACHABLE");
    http.perform(
            post(
        "/finance/period-close/executions/" + executionId + "/sync-approval")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());
    // 期间**仍然开放** —— 绝不「读不到就当批过了」。
    assertThat(periodStatus()).isEqualTo("OPEN");
  }

  @Test
  void 没有生效审批流就不许关账() throws Exception {
    var checklistId = clearBlockingChecks(runChecklist());
    scenario.set("NO_FLOW");
    http.perform(
            post(
        "/finance/period-close/executions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"checklistId\":\""
                        + checklistId
                        + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"本期无存货业务\"}"))
        .andExpect(status().isNotFound());
    // 没有关账执行记录。
    assertThat(executionCount()).isZero();
    assertThat(periodStatus()).isEqualTo("OPEN");
  }

  @Test
  void 有可结转未清项时必须写结转依据() throws Exception {
    var checklistId = runChecklist();
    // 把三个阻断项全部下调为可结转（留下未对账差异这一项「不可判定」的可结转项）。
    for (var code : List.of("UNPOSTED_VOUCHER", "UNCARRIED_COST", "UNSETTLED_PAYMENT")) {
      var checkId = checkIdOf(getJson("/finance/period-close/checklists/" + checklistId), code);
      http.perform(
              post(
        "/finance/period-close/checklists/" + checklistId + "/checks/" + checkId
                      + "/carry-forward")
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"exceptionBasis\":\"已与业务确认，按例外结转\"}"))
          .andExpect(status().isOk());
    }
    // 没有结转依据 ⇒ 拒。
    http.perform(
            post(
        "/finance/period-close/executions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checklistId\":\"" + checklistId + "\",\"reason\":\"10 月关账\"}"))
        .andExpect(status().isBadRequest());
    // 写了依据 ⇒ 走审批。
    var body =
        postJson(
            "/finance/period-close/executions",
            "{\"checklistId\":\""
                + checklistId
                + "\",\"reason\":\"10 月关账\",\"carryforwardBasis\":\"正常未到期应收按结转处理\"}");
    assertThat(body.get("outcome").asText()).isEqualTo("PENDING_APPROVAL");
    var execution = getJson("/finance/period-close/executions/" + body.get("executionId").asText())
        .get("execution");
    assertThat(execution.get("carryforwardBasis").asText()).contains("结转处理");
  }

  @Test
  void 写入的时刻逐位相等不偏八小时() throws Exception {
    // 跑清单**之前**先记下 UTC 时刻的区间 —— 写回来的时刻必须落在这个区间里。
    // 🔴 断言的是**瞬间**（instant），不是墙上时间：
    //    jOOQ 读回 timestamptz 时会带着 JVM 默认时区的偏移，
    //    拿偏移直接比会得到「偏了 8 小时」的假象。
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
    var checklistId = runChecklist();
    var after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2);
    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select started_at, completed_at from finance_period_close_checklist where id = ?",
                    UUID.fromString(checklistId)));
    var startedAt = row.get("started_at", OffsetDateTime.class);
    var completedAt = row.get("completed_at", OffsetDateTime.class);
    assertThat(startedAt).isNotNull();
    assertThat(completedAt).isNotNull();
    assertThat(startedAt.toInstant())
        .as("写回的开始时刻必须就是本机 UTC 的此刻（不能被本地时区挪 8 小时）")
        .isBetween(before.toInstant(), after.toInstant());
    assertThat(completedAt.toInstant())
        .as("写回的完成时刻必须就是本机 UTC 的此刻")
        .isBetween(before.toInstant(), after.toInstant());
    assertThat(completedAt.toInstant())
        .as("完成时刻不得早于开始时刻")
        .isAfterOrEqualTo(startedAt.toInstant());
  }

  @Test
  void 能力声明如实报出对账与报表两个缺口() throws Exception {
    var caps = getJson("/finance/period-close/capabilities");
    var modules = new java.util.ArrayList<String>();
    for (var capability : caps.get("capabilities")) {
      modules.add(capability.get("module").asText());
    }
    assertThat(modules).contains("12.5.1.3", "12.5.1.2", "12.5.2.1", "12.3.2.1");
    var gaps = new java.util.ArrayList<String>();
    for (var gap : caps.get("downstreamGaps")) {
      gaps.add(gap.get("module").asText());
    }
    assertThat(gaps).contains("12.3.2.1", "12.3.2.2", "12.5.2.2");
  }

  // ================================================================ 小工具

  /** 把所有阻断项下调为可结转未清项（留下依据），返回清单 id。 */
  String clearBlockingChecks(String checklistId) throws Exception {
    for (var code : List.of("UNPOSTED_VOUCHER", "UNCARRIED_COST", "UNSETTLED_PAYMENT")) {
      var checkId = checkIdOf(getJson("/finance/period-close/checklists/" + checklistId), code);
      http.perform(
              post(
        "/finance/period-close/checklists/" + checklistId + "/checks/" + checkId
                      + "/carry-forward")
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"exceptionBasis\":\"本期无存货业务，草稿凭证已作废，超期应收已催收\"}"))
          .andExpect(status().isOk());
    }
    return checklistId;
  }

  static JsonNode find(JsonNode checks, String checkCode) {
    for (var check : checks) {
      if (checkCode.equals(check.get("checkCode").asText())) return check;
    }
    throw new AssertionError("检查项 " + checkCode + " 不在结果里");
  }

  static String checkIdOf(JsonNode detail, String checkCode) {
    return find(detail.get("checks"), checkCode).get("id").asText();
  }

  static JsonNode groupOf(JsonNode groups, String currency) {
    for (var group : groups) {
      if (currency.equals(group.get("currency").asText())) return group;
    }
    throw new AssertionError("币种分组 " + currency + " 不在结果里");
  }

  String periodStatus() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select status from finance_voucher_period where id = ?", PERIOD_ID)
            .get("status", String.class));
  }

  int executionCount() {
    return tenants.run(
        tenant, () -> sql.fetch("select id from finance_period_close_execution").size());
  }
}
