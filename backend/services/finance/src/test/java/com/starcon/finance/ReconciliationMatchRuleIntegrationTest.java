package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.finance.ReconciliationMatchRuleModels.*;
import com.starcon.finance.ReconciliationTaskModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.3.2.3 自动匹配规则的**功能**集成测试。
 *
 * <p><b>真实 PostgreSQL 16（Testcontainers）+ 真实端点**，没有内存替身：规则集真的落库，
 * 匹配率统计真的从 12.3.2.1 的匹配结果与差异清单里算出来。
 *
 * <p><b>本测试要证明的五件事</b>（对应蓝图点名的五项能力）：
 *
 * <ol>
 *   <li><b>匹配键可配置</b>：规则把匹配键收窄成 {订单号, 日期} 之后，原本能配上的一对
 *       配不上了（因为它只对上 日期 + 类型 两个维度）。</li>
 *   <li><b>容差可配置</b>：残差落在手续费 / 汇率差容差带里的差异被**归因**成
 *       「手续费差异」/「汇率差」，而不是笼统的「金额对不齐」。</li>
 *   <li><b>优先级</b>：激活中的规则优先级必须唯一（否则「哪条先试」不确定）。</li>
 *   <li><b>模糊匹配兜底</b>：订单号前缀相近的一对在兜底阶段配上（且**仍要求 ≥ 2 个维度**）。</li>
 *   <li><b>匹配率目标与监控</b>：匹配率、未匹配原因分布、快照全部来自真实结果行。</li>
 * </ol>
 *
 * <p>🔴 <b>卷 12 硬约束的负向断言</b>：「只按金额」与「金额 + 单个维度」这两类规则
 * **在服务层与 DB 层都写不出来**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationMatchRuleIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID accountId = UUID.randomUUID();
  static final UUID shop = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  static final String BEARER = "session-a";
  static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 10, 31);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions = List.of("finance.receivable.read", "finance.receivable.update");
    actors.put(
        BEARER,
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired MockMvc http;
  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_reconciliation_match_rate_reason, finance_reconciliation_match_rate,"
              + " finance_reconciliation_match_rule_event, finance_reconciliation_match_rule,"
              + " finance_reconciliation_event, finance_reconciliation_difference,"
              + " finance_reconciliation_match, finance_reconciliation_task,"
              + " finance_receivable_event, finance_receivable_line, finance_receivable,"
              + " finance_settlement_event, finance_settlement_summary, finance_settlement_line,"
              + " finance_settlement_source_file, finance_settlement_cycle, finance_settlement,"
              + " finance_fund_account_balance, finance_fund_account"
              + " restart identity cascade");
    }
    seed();
  }

  /**
   * 种子数据（**全部真插进 PostgreSQL**）。
   *
   * <p>设计出来的对账场景（三对，每对都只差在「订单号」上）：
   *
   * <ul>
   *   <li><b>ORD-A</b>：平台 $100 ⇄ 我方 $100 —— 订单号 + 日期 + 类型全对上；</li>
   *   <li><b>ORDX-1001</b>：平台 $50 ⇄ 我方 ORDX-2002 $50 —— 订单号**逐字不等**
   *       （但前 4 位相同）、日期与类型对上；</li>
   *   <li><b>ORDY-3003</b>：平台 $30 ⇄ 我方 ORDZ-4004 $30 —— 订单号既不等、前缀也不等，
   *       只对上日期与类型。</li>
   * </ul>
   *
   * <p>⇒ 默认口径（三个维度、最少对上 2 个）配成 <b>3 组</b>；
   * 规则把匹配键收窄成 {订单号, 日期} 后只剩 <b>1 组</b>；
   * 再开模糊兜底（前缀 4 位）⇒ <b>2 组</b>。三个数字互不相同 ⇒ 规则**真的**在驱动匹配。
   */
  void seed() {
    var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_fund_account(id,tenant_id,account_no,account_name,account_type,"
                  + " currency,legal_entity_id,legal_entity_code,legal_entity_name,bank_name,"
                  + " account_number_masked,account_number_tail,account_holder,status,"
                  + " current_balance,current_balance_at,balance_record_count,note,version,"
                  + " created_at,updated_at)"
                  + " values (?,?,'FA-A','美国一店收款户','BANK','USD',?,'LE-A','美国主体','测试银行',"
                  + " '****5678','5678','','ACTIVE',0,?::timestamptz,0,'',0,?::timestamptz,"
                  + " ?::timestamptz)",
              accountId,
              tenant,
              UUID.randomUUID(),
              now,
              now,
              now);

          // ---- 结算单（12.3.1.1）+ 结算明细行（12.3.1.2）----
          var settlementId = UUID.randomUUID();
          sql.execute(
              "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
                  + " platform_code,period_start,period_end,currency,settlement_amount,status,"
                  + " expected_at,arrived_at,received_amount,source,source_system,source_reference,"
                  + " source_file_id,parse_version,cycle_id,note,version,created_at,updated_at)"
                  + " values (?,?,'ST-A',?,'SHOP-A','美国一店','amazon','2026-10-01','2026-10-31','USD',0,"
                  + " 'PENDING',null,null,0,'MANUAL_UPLOAD','manual','ST-A-REF',null,1,null,'',0,"
                  + " ?::timestamptz,?::timestamptz)",
              settlementId,
              tenant,
              shop,
              now,
              now);
          settlementLine(settlementId, 1, "ORD-A", "100", "2026-10-05", now);
          settlementLine(settlementId, 2, "ORDX-1001", "50", "2026-10-05", now);
          settlementLine(settlementId, 3, "ORDY-3003", "30", "2026-10-05", now);

          // ---- 我方应收（12.1.1.1）----
          receivable("AR-1", "ORD-A", "100", "2026-10-06", now);
          receivable("AR-2", "ORDX-2002", "50", "2026-10-06", now);
          receivable("AR-3", "ORDZ-4004", "30", "2026-10-06", now);
          return null;
        });
  }

  void settlementLine(UUID settlementId, int lineNo, String reference, String amount,
      String occurredOn, OffsetDateTime now) {
    sql.execute(
        "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,amount,"
            + " raw_amount,currency,quantity,occurred_on,external_reference,description,created_at)"
            + " values (?,?,?,?, 'SALES',?::numeric,?::numeric,'USD',null,?::date,?,"
            + " '平台账单明细',?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        settlementId,
        lineNo,
        new BigDecimal(amount),
        new BigDecimal(amount),
        LocalDate.parse(occurredOn),
        reference,
        now);
  }

  void receivable(String no, String orderNo, String amount, String confirmedOn, OffsetDateTime now) {
    var id = UUID.randomUUID();
    var confirmed = OffsetDateTime.parse(confirmedOn + "T00:00:00Z");
    sql.execute(
        "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
            + " counterparty_type,counterparty_name,shop_id,shop_name,currency,due_date,"
            + " confirmed_at,status,created_at,updated_at)"
            + " values (?,?,?, 'PLATFORM_SETTLEMENT','manual','PLATFORM','Amazon',?,'美国一店','USD',"
            + " ?::date, ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        tenant,
        no,
        shop,
        confirmed.toLocalDate().plusDays(15),
        confirmed,
        confirmed,
        confirmed);
    sql.execute(
        "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
            + " sku_code,order_no,source_reference,quantity,unit_price,amount,settled_amount,"
            + " created_at,updated_at)"
            + " values (?,?,?,1,'平台应收','SKU-1',?,null,null,null,?::numeric,0,"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        id,
        orderNo,
        new BigDecimal(amount),
        confirmed,
        confirmed);
    // 新建应收单必须先是草稿；确认（OPEN）要走 DRAFT → OPEN。
    sql.execute("update finance_receivable set status = 'OPEN' where id = ?", id);
  }

  // ================================================================ HTTP 帮手

  private <T> T read(String body, Class<T> type) {
    try {
      return json.readValue(body, type);
    } catch (Exception e) {
      throw new AssertionError("响应解析失败：" + body, e);
    }
  }

  private <T> T apiGet(String path, Class<T> type) throws Exception {
    var response =
        http.perform(
                MockMvcRequestBuilders.get("/finance/reconciliation-match-rules" + path)
                    .header("Authorization", BEARER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, type);
  }

  private <T> T apiPost(String path, Object body, Class<T> type) throws Exception {
    var response =
        http.perform(
                MockMvcRequestBuilders.post("/finance/reconciliation-match-rules" + path)
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body == null ? "{}" : json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, type);
  }

  private <T> T apiPut(String path, Object body, Class<T> type) throws Exception {
    var response =
        http.perform(
                MockMvcRequestBuilders.put("/finance/reconciliation-match-rules" + path)
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, type);
  }

  /** 建规则的入参（默认值可覆盖）。 */
  private Map<String, Object> ruleInput(String name, Map<String, Object> overrides) {
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("ruleName", name);
    input.put("priority", 100);
    input.put("matchKeys", List.of("REFERENCE", "DATE", "TYPE"));
    input.put("minDimensionCount", 2);
    input.put("dateWindowDays", 7);
    input.put("amountTolerance", "0");
    input.put("feeTolerance", "0");
    input.put("exchangeRateTolerance", "0");
    input.put("fuzzyEnabled", false);
    input.put("fuzzyReferencePrefixLength", 8);
    input.put("fuzzyDateWindowDays", 31);
    input.put("fuzzyAmountTolerance", "0");
    input.put("matchRateTarget", "90");
    input.putAll(overrides);
    return input;
  }

  // ================================================================ 1. 能力声明 / 筛选项

  @Test
  void 能力声明把硬约束写在页面上看得见的地方() throws Exception {
    var capabilities = apiGet("/capabilities", FinanceMatchRuleCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();

    // 五项能力逐条在案。
    for (var keyword :
        List.of("匹配键", "容差", "优先级", "模糊", "匹配率")) {
      assertThat(capabilities.capabilities())
          .filteredOn(c -> c.moduleId().equals("12.3.2.3"))
          .anySatisfy(c -> assertThat(c.name() + c.note()).contains(keyword));
    }

    // 🔴 硬约束必须写在能力声明里（页面上要能看见「金额不在匹配键里」）。
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.moduleId().equals("12.3.2.3"))
        .anySatisfy(c -> assertThat(c.note()).contains("金额从匹配键里剔出去"));

    // 下游 12.3.2.2 如实标成未交付。
    assertThat(capabilities.gaps())
        .filteredOn(g -> g.moduleId().equals("12.3.2.2"))
        .anySatisfy(g -> assertThat(g.delivered()).isFalse());
  }

  @Test
  void 筛选项的匹配键里没有金额() throws Exception {
    var options = apiGet("/options", FinanceMatchRuleOptions.class);
    assertThat(options.matchKeys()).extracting(FinanceMatchRuleOption::value)
        .containsExactlyInAnyOrder("REFERENCE", "DATE", "TYPE");
    assertThat(options.matchKeys()).extracting(FinanceMatchRuleOption::value)
        .doesNotContain("AMOUNT");
    // 账户候选是真实档案，不是常量。
    assertThat(options.accounts()).extracting(FinanceMatchRuleAccount::accountNo)
        .contains("FA-A");
  }

  // ================================================================ 2. 规则 CRUD

  @Test
  void 规则建改查与事件流都真的落库() throws Exception {
    var created =
        apiPost("", ruleInput("严格口径", Map.of("priority", 10, "matchKeys", List.of("REFERENCE", "DATE"))),
            FinanceMatchRuleRow.class);
    assertThat(created.ruleNo()).startsWith("MR-");
    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.matchKeys()).containsExactly("REFERENCE", "DATE");
    assertThat(created.minDimensionCount()).isEqualTo(2);

    // 列表（真分页 + 真搜索）。
    var page = apiGet("", FinanceMatchRulePage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).extracting(FinanceMatchRuleRow::ruleName).containsExactly("严格口径");

    // 详情。
    var detail = apiGet("/" + created.id(), FinanceMatchRuleRow.class);
    assertThat(detail.id()).isEqualTo(created.id());
    assertThat(detail.amountTolerance()).isEqualByComparingTo("0.0000");

    // 改规则。
    var updated =
        apiPut(
            "/" + created.id(),
            ruleInput("严格口径改", Map.of("matchKeys", List.of("REFERENCE", "DATE", "TYPE"))),
            FinanceMatchRuleRow.class);
    assertThat(updated.ruleName()).isEqualTo("严格口径改");
    assertThat(updated.version()).isEqualTo(1);

    // 事件流（append-only 举证）。
    var events = apiGet("/" + created.id() + "/events", FinanceMatchRuleEventPage.class);
    assertThat(events.total()).isEqualTo(2);
    assertThat(events.items()).extracting(FinanceMatchRuleEventRow::eventType)
        .containsExactlyInAnyOrder("RULE_CREATED", "RULE_UPDATED");
  }

  // ================================================================ 3. 🔴 硬约束

  @Test
  void 匹配键只允许订单号日期类型且至少两个() throws Exception {
    // 只有一个维度 ⇒ 400。
    http.perform(
            post("/finance/reconciliation-match-rules")
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ruleInput("只按订单号", Map.of("matchKeys", List.of("REFERENCE"))))))
        .andExpect(status().isBadRequest());

    // 🔴 把「金额」塞进匹配键 ⇒ 400（卷 12 硬约束）。
    var response =
        http.perform(
                post("/finance/reconciliation-match-rules")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(ruleInput("金额加订单号", Map.of("matchKeys", List.of("AMOUNT", "REFERENCE"))))))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse();
    // ⚠️ MockMvc 下 ResponseStatusException 的正文是空的，理由在 getErrorMessage()。
    assertThat(response.getErrorMessage()).contains("金额不在匹配键里");

    // 「至少对上 1 个维度」⇒ 400（金额 + 单个维度不构成匹配）。
    http.perform(
            post("/finance/reconciliation-match-rules")
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ruleInput("只对上一个维度", Map.of("minDimensionCount", 1)))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 绕过服务层直连sql也写不出违规规则() {
    // 🔴 DB 层同样拦住：只有一个维度。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into finance_reconciliation_match_rule(id,tenant_id,rule_no,"
                              + " rule_name,status,priority,match_keys,min_dimension_count,"
                              + " date_window_days,amount_tolerance,fee_tolerance,"
                              + " exchange_rate_tolerance,fuzzy_enabled,fuzzy_reference_prefix_length,"
                              + " fuzzy_date_window_days,fuzzy_amount_tolerance,match_rate_target,"
                              + " note,version,created_by,created_at,updated_at)"
                              + " values (?,?,'MR-BAD-1','只有一个维度','DRAFT',10,'{REFERENCE}',2,7,"
                              + " 0,0,0,false,8,31,0,90,'',0,?::uuid,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID());
                      return null;
                    }))
        .as("DB 层必须拦住「只有一个匹配维度」")
        .hasStackTraceContaining("匹配键必须至少包含两个");

    // 🔴 DB 层同样拦住：把「金额」塞进匹配键。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into finance_reconciliation_match_rule(id,tenant_id,rule_no,"
                              + " rule_name,status,priority,match_keys,min_dimension_count,"
                              + " date_window_days,amount_tolerance,fee_tolerance,"
                              + " exchange_rate_tolerance,fuzzy_enabled,fuzzy_reference_prefix_length,"
                              + " fuzzy_date_window_days,fuzzy_amount_tolerance,match_rate_target,"
                              + " note,version,created_by,created_at,updated_at)"
                              + " values (?,?,'MR-BAD-2','金额加订单号','DRAFT',11,'{AMOUNT,REFERENCE}',2,"
                              + " 7,0,0,0,false,8,31,0,90,'',0,?::uuid,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID());
                      return null;
                    }))
        .as("DB 层必须拦住「金额进匹配键」")
        .hasStackTraceContaining("匹配键必须至少包含两个");

    // 🔴 DB 层同样拦住：「至少对上 1 个维度」。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into finance_reconciliation_match_rule(id,tenant_id,rule_no,"
                              + " rule_name,status,priority,match_keys,min_dimension_count,"
                              + " date_window_days,amount_tolerance,fee_tolerance,"
                              + " exchange_rate_tolerance,fuzzy_enabled,fuzzy_reference_prefix_length,"
                              + " fuzzy_date_window_days,fuzzy_amount_tolerance,match_rate_target,"
                              + " note,version,created_by,created_at,updated_at)"
                              + " values (?,?,'MR-BAD-3','只对上一个维度','DRAFT',12,'{REFERENCE,DATE}',1,"
                              + " 7,0,0,0,false,8,31,0,90,'',0,?::uuid,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID());
                      return null;
                    }))
        .as("DB 层必须拦住「至少对上一个维度」")
        .hasStackTraceContaining("至少对上的维度数不得小于 2");
  }

  // ================================================================ 4. 优先级

  @Test
  void 激活中的规则优先级必须唯一() throws Exception {
    var first =
        apiPost("", ruleInput("优先级 10", Map.of("priority", 10)), FinanceMatchRuleRow.class);
    var second =
        apiPost("", ruleInput("优先级 20", Map.of("priority", 20)), FinanceMatchRuleRow.class);

    apiPost("/" + first.id() + "/activate", null, FinanceMatchRuleRow.class);
    apiPost("/" + second.id() + "/activate", null, FinanceMatchRuleRow.class);

    // 同一个优先级再激活一条 ⇒ 409（「哪条规则先试」必须确定）。
    var third = apiPost("", ruleInput("优先级 10 又一条", Map.of("priority", 10)), FinanceMatchRuleRow.class);
    http.perform(
            post("/finance/reconciliation-match-rules/" + third.id() + "/activate")
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());

    // 停用第一条之后，同优先级的第三条就能启用了。
    apiPost("/" + first.id() + "/deactivate", null, FinanceMatchRuleRow.class);
    var activated = apiPost("/" + third.id() + "/activate", null, FinanceMatchRuleRow.class);
    assertThat(activated.status()).isEqualTo("ACTIVE");
  }

  // ================================================================ 5. 规则真的驱动匹配

  @Test
  void 规则真的驱动了匹配_匹配键收窄后配不上() throws Exception {
    // ---- 没有启用中的规则 ⇒ 默认口径：日期 + 类型也算两个维度 ⇒ 3 组 ----
    var defaultTask = createTask();
    var defaultRun = runMatch(defaultTask.id());
    assertThat(defaultRun.matchedGroupCount()).as("默认口径配成 3 组").isEqualTo(3);
    // 结算单**头**（总额，不是明细行）没有对手方 ⇒ 落成 1 条「平台账单多出」。
    assertThat(defaultRun.differenceCount()).as("默认口径只剩结算单头那一条差异").isEqualTo(1);

    // ---- 规则把匹配键收窄成 {订单号, 日期} ⇒ 只剩 1 组 ----
    var strict =
        apiPost(
            "",
            ruleInput(
                "严格口径",
                Map.of("priority", 10, "matchKeys", List.of("REFERENCE", "DATE"))),
            FinanceMatchRuleRow.class);
    apiPost("/" + strict.id() + "/activate", null, FinanceMatchRuleRow.class);

    var strictTask = createTask();
    var strictRun = runMatch(strictTask.id());
    assertThat(strictRun.matchedGroupCount()).as("收窄匹配键后只剩 1 组").isEqualTo(1);
    // 两对「金额一样、只对上日期一个维度」⇒ 落成「维度对不上」（不是 BOOK_ONLY）；
    // 结算单头 ⇒ 1 条「平台账单多出」。
    assertThat(strictRun.differenceCount()).as("其余两条落成维度对不上 + 结算单头").isEqualTo(3);

    // 🔴 匹配结果里记着「比对了哪几个维度」，且**至少两个** {订单号, 日期, 类型}。
    var matches = getTaskMatches(strictTask.id());
    assertThat(matches.items()).isNotEmpty();
    for (var match : matches.items()) {
      assertThat(match.matchedDimensions()).hasSizeGreaterThanOrEqualTo(2);
      assertThat(match.matchedDimensions())
          .allSatisfy(dim -> assertThat(dim).isIn("REFERENCE", "DATE", "TYPE"));
      assertThat(match.dimensionCount()).isGreaterThanOrEqualTo(2);
    }
  }

  @Test
  void 模糊兜底救回订单号前缀相近的一对_但仍要求至少两个维度() throws Exception {
    var fuzzy =
        apiPost(
            "",
            ruleInput(
                "模糊兜底",
                Map.of(
                    "priority", 10,
                    "matchKeys", List.of("REFERENCE", "DATE"),
                    "fuzzyEnabled", true,
                    "fuzzyReferencePrefixLength", 4)),
            FinanceMatchRuleRow.class);
    apiPost("/" + fuzzy.id() + "/activate", null, FinanceMatchRuleRow.class);

    var task = createTask();
    var run = runMatch(task.id());
    // ORD-A（订单号相等）+ ORDX-1001 ⇄ ORDX-2002（前缀 4 位相同）⇒ 2 组；
    // ORDY-3003 ⇄ ORDZ-4004（前缀也不同）⇒ 仍然配不上。
    assertThat(run.matchedGroupCount()).as("模糊兜底救回一对").isEqualTo(2);
    // ORDY-3003 ⇄ ORDZ-4004（金额一样、只对上日期）⇒「维度对不上」；结算单头 ⇒ 1 条。
    assertThat(run.differenceCount()).isEqualTo(2);

    var matches = getTaskMatches(task.id());
    var fuzzyMatched =
        matches.items().stream().filter(m -> m.matchBasis().contains("模糊兜底")).toList();
    assertThat(fuzzyMatched).as("模糊兜底配上的那一对要写进匹配依据").hasSize(1);
    assertThat(fuzzyMatched.get(0).matchedDimensions()).hasSizeGreaterThanOrEqualTo(2);
  }

  // ================================================================ 6. 匹配率监控

  @Test
  void 匹配率与未匹配原因分布都来自真实结果行() throws Exception {
    var rule =
        apiPost(
            "",
            ruleInput(
                "监控口径",
                Map.of(
                    "priority", 10,
                    "matchKeys", List.of("REFERENCE", "DATE"),
                    "feeTolerance", "5",
                    "exchangeRateTolerance", "10",
                    "matchRateTarget", "80")),
            FinanceMatchRuleRow.class);
    apiPost("/" + rule.id() + "/activate", null, FinanceMatchRuleRow.class);

    var task = createTask();
    var run = runMatch(task.id());
    assertThat(run.matchedGroupCount()).isEqualTo(1);

    // 塞三条「金额对不齐」的差异：残差 3 / 8 / 50
    //   ⇒ 3 ≤ 手续费容差 5 ⇒ 手续费差异
    //   ⇒ 8 > 5 且 ≤ 汇率差容差 10 ⇒ 汇率差
    //   ⇒ 50 > 10 ⇒ 金额对不齐
    seedAmountMismatch(task.id(), "3.0000");
    seedAmountMismatch(task.id(), "8.0000");
    seedAmountMismatch(task.id(), "50.0000");

    var monitoring =
        apiGet("/monitoring?taskId=" + task.id(), FinanceMatchRateMonitoring.class);
    assertThat(monitoring.taskId()).isEqualTo(task.id());
    assertThat(monitoring.ruleId()).isEqualTo(rule.id());
    assertThat(monitoring.matchedLineCount()).isEqualTo(1);
    // 未匹配 = 3 条真实差异（2 条维度对不上 + 1 条平台账单多出）+ 3 条补的 = 6
    assertThat(monitoring.unmatchedLineCount()).isEqualTo(6);
    assertThat(monitoring.totalLineCount()).isEqualTo(7);
    assertThat(monitoring.matchRate()).isEqualByComparingTo("0.1429");
    assertThat(monitoring.matchRateTarget()).isEqualByComparingTo("80");
    assertThat(monitoring.targetMet()).isFalse();

    // 🔴 按币种分组（一次对账只有一个币种）。
    assertThat(monitoring.currencies()).hasSize(1);
    assertThat(monitoring.currencies().get(0).currency()).isEqualTo("USD");

    // 🔴 未匹配原因分布：手续费 / 汇率差被**归因**出来。
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("FEE_DIFFERENCE"))
        .hasSize(1);
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("FEE_DIFFERENCE"))
        .allSatisfy(r -> assertThat(r.lineCount()).isEqualTo(1));
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("EXCHANGE_RATE_DIFFERENCE"))
        .hasSize(1);
    // 兜底的「金额对不齐」至少有一条（残差 50 那条）。
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("AMOUNT_MISMATCH"))
        .isNotEmpty();
    // 真实差异里的两类也在。
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("EXTERNAL_ONLY"))
        .isNotEmpty();
    // 🔴 「维度对不上」= 金额一样但只对上 1 个维度 ⇒ **不构成匹配**的可观测证据。
    assertThat(monitoring.reasons())
        .filteredOn(r -> r.reasonType().equals("DIMENSION_MISMATCH"))
        .isNotEmpty();

    // 🔴 蓝图口径：低于目标时明写「排查上游数据质量而不是调规则」。
    assertThat(monitoring.note()).contains("排查上游数据质量");
  }

  @Test
  void 没有启用中的规则时匹配率判不了而不是判成达标() throws Exception {
    var task = createTask();
    runMatch(task.id());
    var monitoring = apiGet("/monitoring?taskId=" + task.id(), FinanceMatchRateMonitoring.class);
    assertThat(monitoring.ruleId()).isNull();
    assertThat(monitoring.matchRateTarget()).isNull();
    assertThat(monitoring.targetMet()).isNull();
    assertThat(monitoring.note()).contains("判不了");
  }

  // ================================================================ 7. 快照

  @Test
  void 匹配率快照真的落库且不可改写() throws Exception {
    var rule =
        apiPost("", ruleInput("快照口径", Map.of("priority", 10)), FinanceMatchRuleRow.class);
    apiPost("/" + rule.id() + "/activate", null, FinanceMatchRuleRow.class);

    var task = createTask();
    runMatch(task.id());

    var snapshots =
        apiPost(
            "/" + rule.id() + "/rate-snapshots",
            Map.of("taskId", task.id().toString()),
            List.class);
    assertThat((List<?>) snapshots).as("算一次快照要真的返回行").isNotEmpty();

    var page = apiGet("/" + rule.id() + "/rate-snapshots", FinanceMatchRateSnapshotPage.class);
    assertThat(page.total()).isEqualTo(1);
    var snapshot = page.items().get(0);
    assertThat(snapshot.currency()).isEqualTo("USD");
    assertThat(snapshot.taskId()).isEqualTo(task.id());
    assertThat(snapshot.matchedLineCount()).isEqualTo(3);
    // 结算单头（总额）没有对手方 ⇒ 1 条「平台账单多出」。
    assertThat(snapshot.unmatchedLineCount()).isEqualTo(1);
    assertThat(snapshot.totalLineCount()).isEqualTo(4);
    assertThat(snapshot.matchRate()).isEqualByComparingTo("0.7500");
    assertThat(snapshot.matchRateTarget()).isEqualByComparingTo("90");
    assertThat(snapshot.targetMet()).isFalse();
    // 规则口径快照随快照一起存下来。
    assertThat(snapshot.matchKeys()).containsExactly("REFERENCE", "DATE", "TYPE");
    assertThat(snapshot.minDimensionCount()).isEqualTo(2);

    // 🔴 快照是 append-only ⇒ UPDATE 必须被拒。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_match_rate set match_rate = 0"
                              + " where id = ?",
                          snapshot.id());
                      return null;
                    }))
        .as("匹配率快照是一次监控事实，不可改写")
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 时间字段写回逐位相等_不偏八小时() throws Exception {
    var rule = apiPost("", ruleInput("时间口径", Map.of("priority", 10)), FinanceMatchRuleRow.class);
    var activated = apiPost("/" + rule.id() + "/activate", null, FinanceMatchRuleRow.class);
    assertThat(activated.activatedAt()).as("启用时刻必须真的写进去").isNotNull();

    var written =
        tenants.run(
            tenant,
            () -> {
              var row =
                  sql.fetchOne(
                      "select activated_at, created_at, updated_at"
                          + " from finance_reconciliation_match_rule where id = ?",
                      rule.id());
              return row;
            });
    var activatedAt = written.get("activated_at", OffsetDateTime.class);
    assertThat(activatedAt).isNotNull();
    // 写回的值与 API 返回的值**逐位相等**（按时刻比）。
    // ⚠️ 偏移量本身不参与比较：jOOQ 读 timestamptz 用的是 JVM 默认时区（本机 +08:00），
    //   而 API 返回 UTC ⇒ 偏移量字面不同是**呈现**差异；**时刻**必须一模一样
    //   （用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，那才会是真错）。
    assertThat(activatedAt.toInstant()).isEqualTo(activated.activatedAt().toInstant());
    assertThat(activatedAt.getNano()).isEqualTo(activated.activatedAt().getNano());
    assertThat(activatedAt.toInstant().getEpochSecond())
        .isEqualTo(activated.activatedAt().toInstant().getEpochSecond());
  }

  // ================================================================ 帮手

  FinanceReconTaskRow createTask() throws Exception {
    var body =
        Map.of(
            "accountId", accountId.toString(),
            "periodStart", PERIOD_START.toString(),
            "periodEnd", PERIOD_END.toString(),
            "bookSources", List.of("RECEIVABLE"),
            "dateWindowDays", 7);
    var response =
        http.perform(
                post("/finance/reconciliation-tasks")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, FinanceReconTaskRow.class);
  }

  FinanceReconMatchRunResult runMatch(UUID taskId) throws Exception {
    var response =
        http.perform(
                post("/finance/reconciliation-tasks/" + taskId + "/match")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, FinanceReconMatchRunResult.class);
  }

  FinanceReconMatchPage getTaskMatches(UUID taskId) throws Exception {
    var response =
        http.perform(
                get("/finance/reconciliation-tasks/" + taskId + "/matches")
                    .header("Authorization", BEARER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(response, FinanceReconMatchPage.class);
  }

  /** 补一条「金额对不齐」的差异（真实写进 12.3.2.1 的表）。 */
  void seedAmountMismatch(UUID taskId, String differenceAmount) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_reconciliation_difference(id,tenant_id,task_id,"
                  + " difference_type,side,currency,direction,difference_amount,"
                  + " exchange_rate_available,attribution,reason,status,created_at)"
                  + " values (?,?,?,'AMOUNT_MISMATCH','BOTH','USD','IN',?::numeric,true,'',"
                  + " '测试补的残差','OPEN',now())",
              UUID.randomUUID(),
              tenant,
              taskId,
              new BigDecimal(differenceAmount));
          return null;
        });
  }
}
