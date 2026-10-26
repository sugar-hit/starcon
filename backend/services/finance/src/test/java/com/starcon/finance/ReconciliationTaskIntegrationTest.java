package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.3.2.1 对账任务的**功能**集成测试。
 *
 * <p><b>真实 PostgreSQL 16（Testcontainers）+ 真实 HTTP 端点**，没有内存替身：
 * 对账任务、匹配结果、差异清单**真的落库**，页面读的是**真的**数据。
 *
 * <p>⚠️ <b>为什么用 {@code RANDOM_PORT} 而不是 MockMvc</b>：本模块要经
 * {@code GET /finance/bank-reconciliation/**} 读 12.7.1.2 的银行流水 ——
 * 那是**同一个服务里的另一个模块**，走的是真实 HTTP 端口（模块边界是真的）。
 * MockMvc 没有真实端口，那个自我调用就测不到。
 *
 * <p>⚠️ <b>两个上游桩都是「端口级」的，不是「数据级」的</b>：
 * identity 桩只回答「这个会话是谁」（认证），channel 桩只回答「这个租户有哪些店」
 * （授权范围）；**业务数据全部来自真实数据库**（账户 / 结算单 / 应收 / 银行流水
 * 都是真的插进 PostgreSQL 的）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationTaskIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID shop = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  static final String BEARER = "session-a";
  static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 10, 31);

  static UUID accountId = UUID.randomUUID();
  static UUID settlementA = UUID.randomUUID();
  static UUID settlementFee = UUID.randomUUID();
  static UUID settlementEur = UUID.randomUUID();
  static UUID bankSession = UUID.randomUUID();

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
      identity.setExecutor(Executors.newFixedThreadPool(4));
      identity.start();

      // channel 的店铺列表（2 渠道与店铺）—— 只回答「这个租户有哪些店」。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            byte[] body =
                json.writeValueAsBytes(
                    List.of(
                        Map.of(
                            "id", shop.toString(),
                            "code", "SHOP-A",
                            "name", "美国一店",
                            "platformCode", "amazon",
                            "platformName", "Amazon",
                            "status", "ACTIVE")));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TestRestTemplate http;
  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;

  // ================================================================ HTTP 帮手

  private HttpHeaders headers() {
    var h = new HttpHeaders();
    h.set("Authorization", BEARER);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return json.readValue(body, type);
    } catch (Exception e) {
      throw new AssertionError("响应解析失败：" + body, e);
    }
  }

  private <T> T get(String path, Class<T> type) {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks" + path,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);
    assertThat(response.getStatusCode().value())
        .as("GET %s ⇒ %s", path, response.getBody())
        .isEqualTo(200);
    return read(response.getBody(), type);
  }

  private <T> T post(String path, Object body, Class<T> type) {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks" + path,
            HttpMethod.POST,
            new HttpEntity<>(body, headers()),
            String.class);
    assertThat(response.getStatusCode().value())
        .as("POST %s ⇒ %s", path, response.getBody())
        .isEqualTo(200);
    return read(response.getBody(), type);
  }

  // ================================================================ 夹具

  /**
   * 种子数据（**全部真插进 PostgreSQL**）。
   *
   * <p>设计出来的对账场景：
   *
   * <ul>
   *   <li><b>一对一</b>：平台订单 ORD-A $100 ⇄ 应收 ORD-A $100；</li>
   *   <li><b>多对一</b>：平台订单 ORD-B 拆成 $60 + $40 ⇄ 我方应收 ORD-B $100（一笔）；</li>
   *   <li><b>一对多</b>：平台订单 ORD-C $50 ⇄ 我方应收 ORD-C 拆成 $30 + $20（两笔）；</li>
   *   <li><b>维度对不上</b>：平台 ORD-D $77 ⇄ 我方 ORD-E $77 —— 金额一样、日期一样，
   *       但订单号不同、类型也不同 ⇒ 只对上 1 个维度 ⇒ **不构成匹配**；</li>
   *   <li><b>跨币种</b>：一个 EUR 结算单 ⇒ 账户是 USD ⇒ 汇率不可得；</li>
   *   <li><b>银行流水</b>：银行流水 $327（对方单号 ST-A）⇄ 结算单 ST-A $327
   *       ⇒ 订单号 + 日期两个维度 ⇒ 配平；</li>
   *   <li><b>平台多出来</b>：佣金 -$15（没有订单号）⇒ 落成差异。</li>
   * </ul>
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_reconciliation_event, finance_reconciliation_difference,"
              + " finance_reconciliation_match, finance_reconciliation_task,"
              + " finance_bank_recon_event, finance_bank_recon_report, finance_bank_recon_claim,"
              + " finance_bank_recon_unmatched, finance_bank_recon_match,"
              + " finance_bank_recon_system_line, finance_bank_recon_statement_line,"
              + " finance_bank_recon_session, finance_bank_recon_rule,"
              + " finance_receivable_event, finance_receivable_line, finance_receivable,"
              + " finance_settlement_event, finance_settlement_summary, finance_settlement_line,"
              + " finance_settlement_source_file, finance_settlement_cycle, finance_settlement,"
              + " finance_fund_account_balance, finance_fund_account"
              + " restart identity cascade");
    }
    seed();
  }

  void seed() {
    var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          // ---- 资金账户（12.7.1.1）----
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
          settlement(
              settlementA,
              "ST-A",
              "USD",
              List.of(
                  line("SALES", "100", "2026-10-05", "ORD-A"),
                  line("SALES", "60", "2026-10-06", "ORD-B"),
                  line("SALES", "40", "2026-10-07", "ORD-B"),
                  line("SALES", "50", "2026-10-09", "ORD-C"),
                  line("SALES", "77", "2026-10-11", "ORD-D")));
          // 只有佣金的结算单（净额为负 ⇒ 单头不是资金事实，不进候选集）。
          settlement(
              settlementFee,
              "ST-FEE",
              "USD",
              List.of(line("COMMISSION", "-15", "2026-10-08", "")));
          // 另一个币种的结算单（账户是 USD ⇒ 汇率不可得）。
          settlement(
              settlementEur,
              "ST-EUR",
              "EUR",
              List.of(line("SALES", "25", "2026-10-12", "ORD-EUR")));

          // ---- 我方应收（12.1.1.1）----
          receivable("AR-1", "PLATFORM_SETTLEMENT", "2026-10-06", "ORD-A", "100");
          receivable("AR-2", "PLATFORM_SETTLEMENT", "2026-10-07", "ORD-B", "100");
          receivable(
              "AR-3", "PLATFORM_SETTLEMENT", "2026-10-10", "ORD-C", "30", "20");
          // 手工录入的应收 ⇒ 归一类型为空 ⇒ 类型维度不参与（只对上日期一个维度）。
          receivable("AR-4", "MANUAL", "2026-10-15", "ORD-E", "77");

          // ---- 银行流水（12.7.1.2 的表；本模块**只读**，不写）----
          sql.execute(
              "insert into finance_bank_recon_session(id,tenant_id,recon_no,account_id,account_no,"
                  + " account_name,currency,period_start,period_end,statement_opening_balance,"
                  + " statement_closing_balance,book_opening_balance,book_closing_balance,"
                  + " source_system,status,statement_line_count,system_line_count,matched_count,"
                  + " unmatched_bank_count,unmatched_system_count,reconciled_by,reconciled_at,note,"
                  + " version,created_at,updated_at)"
                  + " values (?,?,'BS-001',?,'FA-A','美国一店收款户','USD','2026-10-01','2026-10-31',"
                  + " 0,327,null,null,'bank','IMPORTED',1,0,0,1,0,null,null,'',0,?::timestamptz,"
                  + " ?::timestamptz)",
              bankSession,
              tenant,
              accountId,
              now,
              now);
          sql.execute(
              "insert into finance_bank_recon_statement_line(id,tenant_id,session_id,occurred_at,"
                  + " direction,amount,currency,bank_reference,external_reference,counterparty,memo,"
                  + " import_batch,imported_by,created_at)"
                  + " values (?,?,?,?::timestamptz,'IN',327,'USD','BK-001','ST-A','Amazon',"
                  + " '平台结算到账','IMP-1',?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              bankSession,
              OffsetDateTime.of(2026, 10, 31, 12, 0, 0, 0, ZoneOffset.UTC),
              UUID.randomUUID(),
              now);
          return null;
        });
  }

  record SeedLine(String category, String amount, String occurredOn, String reference) {}

  SeedLine line(String category, String amount, String occurredOn, String reference) {
    return new SeedLine(category, amount, occurredOn, reference);
  }

  /** 一个结算单 + 它的明细行（结算金额由触发器从明细派生）。 */
  void settlement(UUID id, String no, String currency, List<SeedLine> lines) {
    var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    sql.execute(
        "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
            + " platform_code,period_start,period_end,currency,settlement_amount,status,"
            + " expected_at,arrived_at,received_amount,source,source_system,source_reference,"
            + " source_file_id,parse_version,cycle_id,note,version,created_at,updated_at)"
            + " values (?,?,?,?,'SHOP-A','美国一店','amazon','2026-10-01','2026-10-31',?,0,"
            + " 'PENDING',null,null,0,'MANUAL_UPLOAD','manual',?,null,1,null,'',0,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        tenant,
        no,
        shop,
        currency,
        no + "-REF",
        now,
        now);
    var lineNo = 0;
    for (var line : lines) {
      lineNo++;
      sql.execute(
          "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,amount,"
              + " raw_amount,currency,quantity,occurred_on,external_reference,description,created_at)"
              + " values (?,?,?,?,?,?::numeric,?::numeric,?,null,?::date,?,'平台账单明细',"
              + " ?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          id,
          lineNo,
          line.category(),
          new BigDecimal(line.amount()),
          new BigDecimal(line.amount()),
          currency,
          LocalDate.parse(line.occurredOn()),
          line.reference(),
          now);
    }
  }

  /** 一张我方应收单 + 它的明细行（单头金额由触发器派生）。 */
  void receivable(String no, String source, String confirmedOn, String orderNo, String... amounts) {
    var confirmed = OffsetDateTime.parse(confirmedOn + "T00:00:00Z");
    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
            + " counterparty_type,counterparty_name,shop_id,shop_name,currency,due_date,"
            + " confirmed_at,status,created_at,updated_at)"
            + " values (?,?,?,?,'manual','PLATFORM','Amazon',?,'美国一店','USD',?::date,"
            + " ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
        id,
        tenant,
        no,
        source,
        shop,
        LocalDate.parse(confirmedOn).plusDays(15),
        confirmed,
        confirmed,
        confirmed);
    var lineNo = 0;
    for (var amount : amounts) {
      lineNo++;
      sql.execute(
          "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
              + " sku_code,order_no,source_reference,quantity,unit_price,amount,settled_amount,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,'平台应收','SKU-1',?,null,null,null,?::numeric,0,"
              + " ?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          id,
          lineNo,
          orderNo,
          new BigDecimal(amount),
          confirmed,
          confirmed);
    }
    sql.execute("update finance_receivable set status = 'OPEN' where id = ?", id);
  }

  /** 建一个任务。 */
  FinanceReconTaskRow createTask(List<String> bookSources) {
    return post(
        "",
        Map.of(
            "accountId", accountId.toString(),
            "shopId", shop.toString(),
            "periodStart", PERIOD_START.toString(),
            "periodEnd", PERIOD_END.toString(),
            "bookSources", bookSources,
            "dateWindowDays", 7),
        FinanceReconTaskRow.class);
  }

  // ================================================================ 1. 能力声明 / 筛选项

  @Test
  void 能力声明说清硬约束与归属模块() {
    var capabilities = get("/capabilities", FinanceReconCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();

    // 硬约束必须**写在页面上看得见的地方**。
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.moduleId().equals("12.3.2.1"))
        .anySatisfy(c -> assertThat(c.name()).contains("金额相同即匹配"));
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.moduleId().equals("12.3.2.1"))
        .anySatisfy(c -> assertThat(c.note()).contains("候选生成"));

    // 12.7.1.2 的表是它的写入所有权 ⇒ 必须说清「走授权只读 API」。
    assertThat(capabilities.capabilities())
        .filteredOn(c -> c.moduleId().equals("12.7.1.2"))
        .anySatisfy(
            c -> {
              assertThat(c.note()).contains("finance_bank_recon_*");
              assertThat(c.note()).contains("授权只读 API");
            });

    // 下游 12.3.2.2 / 12.3.2.3 如实标成未交付。
    assertThat(capabilities.gaps())
        .filteredOn(g -> g.moduleId().equals("12.3.2.2"))
        .anySatisfy(g -> assertThat(g.delivered()).isFalse());
    assertThat(capabilities.gaps())
        .filteredOn(g -> g.moduleId().equals("12.3.2.3"))
        .anySatisfy(g -> assertThat(g.delivered()).isFalse());
  }

  @Test
  void 筛选项里的账户是真的账户() {
    var options = get("/options", FinanceReconOptions.class);
    assertThat(options.accounts()).isNotEmpty();
    assertThat(options.accounts().get(0).id()).isEqualTo(accountId);
    assertThat(options.accounts().get(0).currency()).isEqualTo("USD");
    assertThat(options.bookSources()).hasSize(3);
  }

  // ================================================================ 2. 建任务

  @Test
  void 建任务_范围与快照真的落库() {
    var task = createTask(List.of("RECEIVABLE", "BANK_STATEMENT"));
    assertThat(task.taskNo()).startsWith("RT-");
    assertThat(task.status()).isEqualTo("DRAFT");
    assertThat(task.statusLabel()).isEqualTo("待执行匹配");
    assertThat(task.currency()).isEqualTo("USD");
    assertThat(task.legalEntityName()).isEqualTo("美国主体");
    assertThat(task.shopId()).isEqualTo(shop);
    assertThat(task.shopName()).isEqualTo("美国一店");
    assertThat(task.bookSources()).containsExactly("RECEIVABLE", "BANK_STATEMENT");
    assertThat(task.dateWindowDays()).isEqualTo(7);
    assertThat(task.amountTolerance()).isEqualByComparingTo("0");
  }

  @Test
  void 建任务_账户不存在就拒() {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "accountId", UUID.randomUUID().toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("RECEIVABLE")),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void 建任务_未知的我方账来源就拒() {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "accountId", accountId.toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("NOT_A_SOURCE")),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void 建任务_日期窗口超过上限就拒() {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "accountId", accountId.toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("RECEIVABLE"),
                    "dateWindowDays", 365),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void 建任务_店铺不在授权范围内就拒() {
    var response =
        http.exchange(
            "/finance/reconciliation-tasks",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "accountId", accountId.toString(),
                    "shopId", UUID.randomUUID().toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("RECEIVABLE")),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(403);
  }

  // ================================================================ 3. 自动匹配

  @Test
  void 跑匹配_一对一多对一一对多都落成多行匹配结果() {
    var task = createTask(List.of("RECEIVABLE", "BANK_STATEMENT"));
    var result = post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    // 平台账单侧：ST-A 单头 + 5 行明细 + ST-FEE 单头（负额 ⇒ 不进候选集）+ ST-EUR 单头 + 1 行。
    assertThat(result.externalLineCount()).isPositive();

    // 形态分布：至少各有一个一对一、一对多、多对一。
    assertThat(result.oneToOneCount()).isPositive();
    assertThat(result.oneToManyCount()).isPositive();
    assertThat(result.manyToOneCount()).isPositive();

    var matches = get("/" + task.id() + "/matches", FinanceReconMatchPage.class);
    assertThat(matches.total()).isEqualTo(result.matchedLineCount());

    // 🔴 每一条匹配都必须**至少两个独立维度**（卷 12 硬约束）。
    assertThat(matches.items())
        .allSatisfy(
            m -> {
              assertThat(m.dimensionCount()).isGreaterThanOrEqualTo(2);
              var nonAmount =
                  m.matchedDimensions().stream().filter(d -> !d.equals("AMOUNT")).count();
              assertThat(nonAmount)
                  .as("比对的维度里至少两个来自 {订单号, 日期, 类型}")
                  .isGreaterThanOrEqualTo(2);
              assertThat(m.matchedDimensions())
                  .allSatisfy(d -> assertThat(d).isIn("REFERENCE", "DATE", "TYPE", "AMOUNT"));
              assertThat(m.matchBasis()).isNotBlank();
            });

    // 一对多 ⇒ 同一组里有多行（**不把多张单据揉成一条**）。
    var oneToMany = matches.items().stream().filter(m -> m.shape().equals("ONE_TO_MANY")).toList();
    assertThat(oneToMany).hasSizeGreaterThan(1);
    assertThat(oneToMany.stream().map(FinanceReconMatchRow::groupKey).distinct().count())
        .as("一对多必须落在同一个组里")
        .isEqualTo(1);
    assertThat(oneToMany.stream().map(FinanceReconMatchRow::bookLineId).distinct().count())
        .as("一对多的我方账行必须各占一行")
        .isEqualTo(oneToMany.size());

    // 多对一 ⇒ 同一组里有多行平台账单行。
    var manyToOne = matches.items().stream().filter(m -> m.shape().equals("MANY_TO_ONE")).toList();
    assertThat(manyToOne).hasSizeGreaterThan(1);
    assertThat(manyToOne.stream().map(FinanceReconMatchRow::groupKey).distinct().count()).isEqualTo(1);

    // 组内合计相等（金额相等是**成组**的必要条件）。
    assertThat(matches.items())
        .allSatisfy(m -> assertThat(m.groupAmountDifference()).isEqualByComparingTo("0"));
  }

  @Test
  void 金额相同但只对一个维度_不配成一对而是落成维度对不上() {
    var task = createTask(List.of("RECEIVABLE"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var differences = get("/" + task.id() + "/differences", FinanceReconDifferencePage.class);
    var nearMiss =
        differences.items().stream()
            .filter(d -> d.differenceType().equals("DIMENSION_MISMATCH"))
            .findFirst()
            .orElse(null);
    assertThat(nearMiss)
        .as("ORD-D $77 与 ORD-E $77 金额相同但只对上日期 ⇒ 必须落成「维度对不上」")
        .isNotNull();
    assertThat(nearMiss.reason()).contains("金额相同");
    assertThat(nearMiss.reason()).contains("不构成匹配");
    assertThat(nearMiss.externalAmount()).isEqualByComparingTo("77");
    assertThat(nearMiss.bookAmount()).isEqualByComparingTo("77");

    // 🔴 而且它们**不许**出现在匹配结果里。
    var matches = get("/" + task.id() + "/matches", FinanceReconMatchPage.class);
    assertThat(matches.items())
        .noneSatisfy(m -> assertThat(m.externalAmount()).isEqualByComparingTo("77"));
  }

  @Test
  void 银行流水经授权只读_api_与结算单头配平() {
    var task = createTask(List.of("BANK_STATEMENT"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var matches = get("/" + task.id() + "/matches", FinanceReconMatchPage.class);
    assertThat(matches.items()).hasSize(1);
    var match = matches.items().get(0);
    assertThat(match.bookSource()).isEqualTo("BANK_STATEMENT");
    assertThat(match.externalKind()).isEqualTo("SETTLEMENT");
    assertThat(match.externalReference()).isEqualTo("ST-A");
    assertThat(match.bookReference()).isEqualTo("ST-A");
    assertThat(match.externalAmount()).isEqualByComparingTo("327");
    assertThat(match.bookAmount()).isEqualByComparingTo("327");
    // 银行流水没有业务类型 ⇒ 类型维度不参与；订单号 + 日期两个维度足够。
    assertThat(match.matchedDimensions()).containsExactlyInAnyOrder("REFERENCE", "DATE");
  }

  @Test
  void 差异清单按币种分组_跨币种的差额是空的而不是零() {
    var task = createTask(List.of("RECEIVABLE", "BANK_STATEMENT"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var differences = get("/" + task.id() + "/differences", FinanceReconDifferencePage.class);
    var currencyMismatch =
        differences.items().stream()
            .filter(d -> d.differenceType().equals("CURRENCY_MISMATCH"))
            .toList();
    assertThat(currencyMismatch).isNotEmpty();
    assertThat(currencyMismatch)
        .allSatisfy(
            d -> {
              assertThat(d.currency()).isEqualTo("EUR");
              assertThat(d.exchangeRateAvailable()).isFalse();
              assertThat(d.differenceAmount())
                  .as("汇率不可得 ⇒ 差额恒为 null（**不按 1:1 兜底**）")
                  .isNull();
              assertThat(d.reason()).contains("汇率不可得");
            });

    // 平台多出来的（佣金）落成 EXTERNAL_ONLY。
    assertThat(differences.items())
        .anySatisfy(
            d -> {
              assertThat(d.differenceType()).isEqualTo("EXTERNAL_ONLY");
              assertThat(d.attribution()).contains("平台账单");
            });

    // 币种筛选真的起作用。
    var eur = get("/" + task.id() + "/differences?currency=EUR", FinanceReconDifferencePage.class);
    assertThat(eur.items()).isNotEmpty();
    assertThat(eur.items()).allSatisfy(d -> assertThat(d.currency()).isEqualTo("EUR"));
  }

  @Test
  void 任务详情的汇总按币种分组_绝不跨币种合计() {
    var task = createTask(List.of("RECEIVABLE", "BANK_STATEMENT"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var detail = get("/" + task.id(), FinanceReconTaskDetail.class);
    assertThat(detail.task().status()).isEqualTo("MATCHED");
    assertThat(detail.summary().currencies()).isNotEmpty();
    assertThat(detail.summary().currencies())
        .allSatisfy(
            c -> {
              assertThat(c.currency()).isNotBlank();
              if (!c.currency().equals("USD")) {
                assertThat(c.differenceAmount())
                    .as("跨币种 ⇒ 差额不可得")
                    .isNull();
                assertThat(c.note()).contains("汇率不可得");
              }
            });
    // 一次对账只有一个币种可相加 ⇒ USD 组有合计，EUR 组单独成组。
    var usd =
        detail.summary().currencies().stream().filter(c -> c.currency().equals("USD")).findFirst();
    assertThat(usd).isPresent();
    assertThat(usd.get().externalAmount()).isPositive();
    assertThat(usd.get().bookAmount()).isPositive();
  }

  // ================================================================ 4. 不可改写

  @Test
  void 跑完匹配不许重跑_重跑得新建任务() {
    var task = createTask(List.of("RECEIVABLE"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var response =
        http.exchange(
            "/finance/reconciliation-tasks/" + task.id() + "/match",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).contains("新建一个任务");
  }

  @Test
  void 匹配结果在数据库层不可改写() {
    var task = createTask(List.of("RECEIVABLE"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var matches = get("/" + task.id() + "/matches", FinanceReconMatchPage.class);
    var id = matches.items().get(0).id();

    // 直连 SQL 也改不动（触发器 + 运行角色权限两层）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_match set match_basis = '改过了'"
                              + " where id = ?",
                          id);
                      return null;
                    }))
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 任务执行完成后范围冻结() {
    var task = createTask(List.of("RECEIVABLE"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var response =
        http.exchange(
            "/finance/reconciliation-tasks/" + task.id(),
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "accountId", accountId.toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("RECEIVABLE")),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
  }

  // ================================================================ 5. 事件流

  @Test
  void 事件流留痕_建任务与跑匹配各一条() {
    var task = createTask(List.of("RECEIVABLE"));
    post("/" + task.id() + "/match", Map.of(), FinanceReconMatchRunResult.class);

    var events = get("/" + task.id() + "/events", FinanceReconEventPage.class);
    assertThat(events.total()).isEqualTo(2);
    assertThat(events.items())
        .extracting(FinanceReconEventRow::eventType)
        .containsExactlyInAnyOrder("TASK_CREATED", "MATCH_COMPLETED");
  }

  // ================================================================ 6. 伪造租户头无效

  @Test
  void 伪造租户头无效_行仍然落在会话租户名下() {
    var headers = headers();
    headers.set("X-Tenant-Id", UUID.randomUUID().toString());
    headers.set("X-Internal-Key", "forged");
    var response =
        http.exchange(
            "/finance/reconciliation-tasks",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "accountId", accountId.toString(),
                    "shopId", shop.toString(),
                    "periodStart", PERIOD_START.toString(),
                    "periodEnd", PERIOD_END.toString(),
                    "bookSources", List.of("RECEIVABLE")),
                headers),
            FinanceReconTaskRow.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);

    var row =
        tenants.run(
            tenant,
            () -> sql.fetchOne("select tenant_id from finance_reconciliation_task where id = ?",
                response.getBody().id()));
    assertThat(row).isNotNull();
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(tenant);
  }
}
