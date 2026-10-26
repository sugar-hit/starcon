package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.finance.ReconciliationDifferenceModels.*;
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
 * 12.3.2.2 差异处理的**功能**集成测试。
 *
 * <p><b>真实 PostgreSQL 16（Testcontainers）+ 真实 HTTP 端点</b>，没有内存替身：
 * 差异清单、调整分录、处理时间线**真的落库**，页面读的是**真的**数据。
 *
 * <p>⚠️ 本模块**只推进上游差异的 status**，其余事实字段一律不碰 ⇒ 测试里专门有一条
 * 「直连 SQL 也写不出自相矛盾的状态」与「直连 SQL 也写不出没有调整分录的已调整」。
 *
 * <p>⚠️ identity 桩只回答「这个会话是谁」（认证）；**业务数据全部来自真实数据库**
 * （账户 / 对账任务 / 差异清单都是真的插进 PostgreSQL 的）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationDifferenceIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  static final String BEARER = "session-a";

  static UUID accountId = UUID.randomUUID();
  static UUID taskId = UUID.randomUUID();
  /** 平台账单多出（USD）。 */
  static UUID differenceExternal = UUID.randomUUID();
  /** 我方账多出（USD）。 */
  static UUID differenceBook = UUID.randomUUID();
  /** 币种不一致（EUR）⇒ 汇率不可得，差额恒为 null。 */
  static UUID differenceCurrency = UUID.randomUUID();

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
            "/finance/reconciliation-differences" + path,
            HttpMethod.GET,
            new HttpEntity<>(headers()),
            String.class);
    assertThat(response.getStatusCode().value())
        .as("GET %s ⇒ %s", path, response.getBody())
        .isEqualTo(200);
    return read(response.getBody(), type);
  }

  private ResponseEntity<String> postRaw(String path, Object body) {
    return http.exchange(
        "/finance/reconciliation-differences" + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers()),
        String.class);
  }

  private <T> T post(String path, Object body, Class<T> type) {
    var response = postRaw(path, body);
    assertThat(response.getStatusCode().value())
        .as("POST %s ⇒ %s", path, response.getBody())
        .isEqualTo(200);
    return read(response.getBody(), type);
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_reconciliation_handling_event, finance_reconciliation_adjustment,"
              + " finance_reconciliation_handling, finance_reconciliation_event,"
              + " finance_reconciliation_difference, finance_reconciliation_match,"
              + " finance_reconciliation_task, finance_fund_account_balance,"
              + " finance_fund_account restart identity cascade");
    }
    seed();
  }

  /**
   * 种子数据（**全部真插进 PostgreSQL**）。
   *
   * <p>三条差异，覆盖三种口径：
   *
   * <ul>
   *   <li><b>平台账单多出</b>（USD $100）⇒ 差额 −100（我方账 − 平台账单）；</li>
   *   <li><b>我方账多出</b>（USD $25）⇒ 差额 +25；</li>
   *   <li><b>币种不一致</b>（EUR €30）⇒ **汇率不可得**，差额恒为 null（不按 1:1 兜底）。</li>
   * </ul>
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

          sql.execute(
              "insert into finance_reconciliation_task(id,tenant_id,task_no,account_id,account_no,"
                  + " account_name,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                  + " shop_id,shop_code,shop_name,platform_code,period_start,period_end,"
                  + " book_sources,date_window_days,amount_tolerance,status,external_line_count,"
                  + " matched_group_count,matched_line_count,difference_count,matched_at,matched_by,"
                  + " note,version,created_by,created_at,updated_at)"
                  + " values (?,?,'RT-A',?,'FA-A','美国一店收款户','USD',?,'LE-A','美国主体',null,"
                  + " '','','','2026-10-01','2026-10-31',?::text[],7,0,'MATCHED',3,0,0,3,"
                  + " ?::timestamptz,?,'测试任务',0,?,?::timestamptz,?::timestamptz)",
              taskId,
              tenant,
              accountId,
              UUID.randomUUID(),
              new String[] {"RECEIVABLE"},
              now,
              UUID.randomUUID(),
              UUID.randomUUID(),
              now,
              now);

          difference(
              differenceExternal,
              "EXTERNAL_ONLY",
              "EXTERNAL",
              "USD",
              "100",
              null,
              "-100",
              true,
              "平台账单 · 结算单 ST-A",
              "平台账单有、我方账没有");
          difference(
              differenceBook,
              "BOOK_ONLY",
              "BOOK",
              "USD",
              null,
              "25",
              "25",
              true,
              "我方账 · 应收明细 AR-1",
              "我方账有、平台账单没有");
          // 跨币种 ⇒ 汇率不可得 ⇒ 差额恒为 null（DB CHECK 钉死）。
          difference(
              differenceCurrency,
              "CURRENCY_MISMATCH",
              "EXTERNAL",
              "EUR",
              "30",
              null,
              null,
              false,
              "平台账单 · 结算单 ST-EUR",
              "币种不一致 ⇒ 汇率不可得");
          return null;
        });
  }

  /** 一条上游差异（**只写 status='OPEN'** —— 12.3.2.1 只产出 OPEN 的差异）。 */
  void difference(
      UUID id,
      String type,
      String side,
      String currency,
      String externalAmount,
      String bookAmount,
      String differenceAmount,
      boolean rateAvailable,
      String attribution,
      String reason) {
    sql.execute(
        "insert into finance_reconciliation_difference(id,tenant_id,task_id,difference_type,side,"
            + " book_source,group_key,external_kind,external_line_id,book_line_id,occurred_on,"
            + " reference,transaction_type,external_amount,book_amount,currency,direction,"
            + " difference_amount,exchange_rate_available,attribution,reason,status,created_at)"
            + " values (?,?,?,?,?,null,null,'SETTLEMENT_LINE',?,null,'2026-10-05','ORD-A','SALE',"
            + " ?,?,?,'IN',?,?,?,?,'OPEN',?::timestamptz)",
        id,
        tenant,
        taskId,
        type,
        side,
        UUID.randomUUID(),
        externalAmount == null ? null : new BigDecimal(externalAmount),
        bookAmount == null ? null : new BigDecimal(bookAmount),
        currency,
        differenceAmount == null ? null : new BigDecimal(differenceAmount),
        rateAvailable,
        attribution,
        reason,
        OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  // ==========================================================================
  // 队列 / 汇总
  // ==========================================================================

  @Test
  void 队列列出上游差异并按币种分组_绝不跨币种合计() {
    var page = get("", ReconciliationDiffQueuePage.class);

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).extracting(ReconciliationDiffRow::differenceId)
        .containsExactlyInAnyOrder(differenceExternal, differenceBook, differenceCurrency);

    // 两个币种各自成组 —— **没有第三个「合计」组**。
    assertThat(page.currencies()).extracting(ReconciliationDiffCurrencySummary::currency)
        .containsExactly("EUR", "USD");

    var usd = page.currencies().stream()
        .filter(s -> "USD".equals(s.currency()))
        .findFirst()
        .orElseThrow();
    // USD 内部：−100 + 25 = −75（同币种才相加）。
    assertThat(usd.differenceAmount()).isEqualByComparingTo("-75");
    assertThat(usd.totalCount()).isEqualTo(2);
    assertThat(usd.openCount()).isEqualTo(2);
    assertThat(usd.adjustedAmount()).as("还没登记调整分录").isNull();

    var eur = page.currencies().stream()
        .filter(s -> "EUR".equals(s.currency()))
        .findFirst()
        .orElseThrow();
    // 🔴 跨币种差异的差额恒为 null —— **不按 1:1 兜底**。
    assertThat(eur.differenceAmount()).isNull();
    assertThat(eur.exchangeRateAvailable()).as("EUR 这一组里有汇率不可得的差异").isFalse();
    // ⚠️ EUR 的 30 绝不能被并进 USD 的 −75 里。
    assertThat(usd.differenceAmount()).isNotEqualByComparingTo("-45");
  }

  @Test
  void 跨币种差异的差额显示为不可得而不是零() {
    var page = get("", ReconciliationDiffQueuePage.class);
    var row =
        page.items().stream()
            .filter(r -> differenceCurrency.equals(r.differenceId()))
            .findFirst()
            .orElseThrow();
    assertThat(row.differenceAmount()).isNull();
    assertThat(row.exchangeRateAvailable()).isFalse();
    assertThat(row.differenceTypeLabel()).isEqualTo("币种不一致");
  }

  @Test
  void 队列真分页真筛选真搜索() {
    var usd = get("?currency=USD", ReconciliationDiffQueuePage.class);
    assertThat(usd.total()).isEqualTo(2);
    assertThat(usd.items()).extracting(ReconciliationDiffRow::currency).containsOnly("USD");

    var book = get("?differenceType=BOOK_ONLY", ReconciliationDiffQueuePage.class);
    assertThat(book.total()).isEqualTo(1);

    var keyword = get("?keyword=ORD-A", ReconciliationDiffQueuePage.class);
    assertThat(keyword.total()).isEqualTo(3);

    var none = get("?keyword=不存在的单号", ReconciliationDiffQueuePage.class);
    assertThat(none.total()).isZero();
    assertThat(none.items()).isEmpty();
  }

  @Test
  void 筛选项来自真实数据而不是常量() {
    var options = get("/options", ReconciliationDiffOptions.class);
    assertThat(options.currencies()).containsExactly("EUR", "USD");
    assertThat(options.tasks()).hasSize(1);
    assertThat(options.tasks().get(0).taskNo()).isEqualTo("RT-A");
    assertThat(options.categories()).extracting(ReconciliationDiffOption::value)
        .containsExactly(
            "BOOK_OVER_RECORDED",
            "COUNTERPARTY_OVER_RECORDED",
            "TIMING_DIFFERENCE",
            "FX_DIFFERENCE",
            "FEE_OMISSION");
  }

  @Test
  void 能力声明如实报出下游缺口() {
    var capabilities = get("/capabilities", ReconciliationDiffCapabilities.class);
    assertThat(capabilities.capabilities())
        .filteredOn(c -> "12.3.2.2".equals(c.moduleId()))
        .isNotEmpty();
    assertThat(capabilities.gaps())
        .extracting(ReconciliationDiffGap::moduleId)
        .contains("12.5.2.1", "1.3.2.1");
  }

  // ==========================================================================
  // 认领 / 改派
  // ==========================================================================

  @Test
  void 认领把上游状态推进到claimed_并建起处理记录() {
    var handling = post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);

    assertThat(handling.status()).isEqualTo("CLAIMED");
    assertThat(handling.assigneeId()).isEqualTo(actorId());
    assertThat(handling.claimedAt()).isNotNull();

    // 上游差异的状态**同相**推进了。
    assertThat(upstreamStatus(differenceExternal)).isEqualTo("CLAIMED");
    // 处理记录真的落库了。
    assertThat(handlingRows(differenceExternal)).isEqualTo(1);
  }

  @Test
  void 认领可以显式指定责任人() {
    var other = UUID.randomUUID();
    var handling =
        post("/" + differenceExternal + "/claim", Map.of("assigneeId", other),
            ReconciliationDiffHandling.class);
    assertThat(handling.assigneeId()).isEqualTo(other);
  }

  @Test
  void 已处理的差异不能再认领() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    var second = postRaw("/" + differenceExternal + "/claim", Map.of());
    assertThat(second.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void 改派责任人只允许在已认领态() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    var other = UUID.randomUUID();
    var handling =
        post("/" + differenceExternal + "/assign", Map.of("assigneeId", other),
            ReconciliationDiffHandling.class);
    assertThat(handling.assigneeId()).isEqualTo(other);
    assertThat(handling.status()).isEqualTo("CLAIMED");

    // 未认领的差异改派 ⇒ 409。
    var rejected = postRaw("/" + differenceBook + "/assign", Map.of("assigneeId", other));
    assertThat(rejected.getStatusCode().value()).isEqualTo(409);
  }

  // ==========================================================================
  // 分类归因
  // ==========================================================================

  @Test
  void 分类必须带理由_未知分类被拒() {
    var noReason =
        postRaw(
            "/" + differenceExternal + "/classify",
            Map.of("category", "FEE_OMISSION", "reason", "   "));
    assertThat(noReason.getStatusCode().value()).isEqualTo(400);

    var unknown =
        postRaw(
            "/" + differenceExternal + "/classify",
            Map.of("category", "NOT_A_CATEGORY", "reason", "随手写的"));
    assertThat(unknown.getStatusCode().value()).isEqualTo(400);

    var ok =
        post(
            "/" + differenceExternal + "/classify",
            Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金，我方账没记"),
            ReconciliationDiffHandling.class);
    assertThat(ok.category()).isEqualTo("FEE_OMISSION");
    assertThat(ok.categoryLabel()).isEqualTo("手续费遗漏");
    assertThat(ok.categoryReason()).isEqualTo("平台扣了佣金，我方账没记");
    assertThat(ok.categorySetBy()).isEqualTo(actorId());
    assertThat(ok.categorySetAt()).isNotNull();
  }

  @Test
  void 分类可以在认领之前做_但没责任人没分类都不许闭环() {
    var handling =
        post(
            "/" + differenceExternal + "/classify",
            Map.of("category", "TIMING_DIFFERENCE", "reason", "跨了结算周期，下期已到账"),
            ReconciliationDiffHandling.class);
    assertThat(handling.status()).isEqualTo("OPEN");
    assertThat(handling.assigneeId()).isNull();
    assertThat(upstreamStatus(differenceExternal)).isEqualTo("OPEN");

    // 没有责任人 ⇒ 闭环被拒（还没认领就不许进闭环流程）。
    var rejected = postRaw("/" + differenceExternal + "/write-off", Map.of("conclusion", "先挂着"));
    assertThat(rejected.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void 已认领但没分类也不许闭环() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    var rejected = postRaw("/" + differenceExternal + "/write-off", Map.of("conclusion", "先挂着"));
    assertThat(rejected.getStatusCode().value()).isEqualTo(400);
    assertThat(rejected.getBody()).contains("分类归因");
  }

  @Test
  void 分类可以修正_每次修正都留时间线() {
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "TIMING_DIFFERENCE", "reason", "先按时间差"),
        ReconciliationDiffHandling.class);
    var corrected =
        post(
            "/" + differenceExternal + "/classify",
            Map.of("category", "BOOK_OVER_RECORDED", "reason", "查了流水，是我方多记"),
            ReconciliationDiffHandling.class);
    assertThat(corrected.category()).isEqualTo("BOOK_OVER_RECORDED");

    var events = get("/" + differenceExternal + "/events", ReconciliationDiffEventPage.class);
    assertThat(events.total()).isGreaterThanOrEqualTo(2);
    assertThat(events.items()).extracting(ReconciliationDiffEventRow::eventType)
        .contains("CLASSIFIED");
  }

  // ==========================================================================
  // 调整分录
  // ==========================================================================

  @Test
  void 调整分录的凭证与说明至少要有一个() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);

    var neither =
        postRaw(
            "/" + differenceExternal + "/adjustments",
            Map.of("adjustmentAmount", "10.0000", "direction", "OUT"));
    assertThat(neither.getStatusCode().value()).isEqualTo(400);

    var voucherOnly =
        post(
            "/" + differenceExternal + "/adjustments",
            Map.of(
                "voucherNo", "VCH-2026-0001",
                "adjustmentAmount", "10.0000",
                "direction", "OUT"),
            ReconciliationDiffAdjustmentRow.class);
    assertThat(voucherOnly.voucherNo()).isEqualTo("VCH-2026-0001");
    assertThat(voucherOnly.adjustmentAmount()).isEqualByComparingTo("10.0000");
    assertThat(voucherOnly.currency()).isEqualTo("USD");
    assertThat(voucherOnly.entryNo()).isEqualTo(1);

    var explanationOnly =
        post(
            "/" + differenceExternal + "/adjustments",
            Map.of(
                "adjustmentAmount", "2.5000",
                "direction", "IN",
                "explanation", "平台补了一笔手续费"),
            ReconciliationDiffAdjustmentRow.class);
    assertThat(explanationOnly.voucherNo()).isNull();
    assertThat(explanationOnly.entryNo()).isEqualTo(2);
  }

  @Test
  void 未认领的差异不能登记调整分录() {
    var rejected =
        postRaw(
            "/" + differenceExternal + "/adjustments",
            Map.of("adjustmentAmount", "10.0000", "direction", "OUT", "explanation", "x"));
    assertThat(rejected.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void 调整分录可以指定币种_但绝不跨币种相加() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    var eur =
        post(
            "/" + differenceExternal + "/adjustments",
            Map.of(
                "adjustmentAmount", "7.0000",
                "currency", "EUR",
                "direction", "OUT",
                "explanation", "按欧元记账"),
            ReconciliationDiffAdjustmentRow.class);
    assertThat(eur.currency()).isEqualTo("EUR");

    var page = get("", ReconciliationDiffQueuePage.class);
    var usd = page.currencies().stream().filter(s -> "USD".equals(s.currency())).findFirst().orElseThrow();
    var eurSummary =
        page.currencies().stream().filter(s -> "EUR".equals(s.currency())).findFirst().orElseThrow();
    // EUR 的调整额落在 EUR 那一组，**没有**并进 USD。
    assertThat(eurSummary.adjustedAmount()).isEqualByComparingTo("7.0000");
    assertThat(usd.adjustedAmount()).as("USD 组里没有调整分录").isNull();
  }

  @Test
  void 调整分录不可改写() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/adjustments",
        Map.of("adjustmentAmount", "10.0000", "direction", "OUT", "explanation", "一次性事实"),
        ReconciliationDiffAdjustmentRow.class);

    // ① 运行角色**根本没有** UPDATE 权限（权限是第一道保险）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_adjustment"
                              + " set adjustment_amount = 999 where tenant_id = ?",
                          tenant);
                      return null;
                    }))
        .as("运行角色不得改写调整分录")
        .hasStackTraceContaining("permission denied");

    // ② 就算用**表属主**（绕过权限）写，触发器仍然拒绝（第二道保险）。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
                s.execute(
                    "update finance_reconciliation_adjustment set adjustment_amount = 999"
                        + " where tenant_id = '" + tenant + "'");
              }
            })
        .as("调整分录是一次性事实，不可改写")
        .hasStackTraceContaining("调整分录");
  }

  // ==========================================================================
  // 人工判定与闭环
  // ==========================================================================

  @Test
  void 判定已调整必须至少有一条调整分录() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);

    var rejected = postRaw("/" + differenceExternal + "/adjust", Map.of("conclusion", "按平台调整"));
    assertThat(rejected.getStatusCode().value()).isEqualTo(400);

    post(
        "/" + differenceExternal + "/adjustments",
        Map.of("adjustmentAmount", "10.0000", "direction", "OUT", "explanation", "补记手续费"),
        ReconciliationDiffAdjustmentRow.class);

    var handling =
        post("/" + differenceExternal + "/adjust", Map.of("conclusion", "按平台账单调整我方账"),
            ReconciliationDiffHandling.class);
    assertThat(handling.status()).isEqualTo("ADJUSTED");
    assertThat(handling.conclusion()).isEqualTo("按平台账单调整我方账");
    assertThat(handling.closedAt()).isNotNull();
    assertThat(upstreamStatus(differenceExternal)).isEqualTo("ADJUSTED");
  }

  @Test
  void 判定已核销不要求调整分录() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "TIMING_DIFFERENCE", "reason", "下期已到账，无需调整"),
        ReconciliationDiffHandling.class);

    var handling =
        post("/" + differenceExternal + "/write-off", Map.of("conclusion", "下期已到账，核销"),
            ReconciliationDiffHandling.class);
    assertThat(handling.status()).isEqualTo("WRITTEN_OFF");
    assertThat(upstreamStatus(differenceExternal)).isEqualTo("WRITTEN_OFF");
  }

  @Test
  void 闭环之后记录冻结() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/adjustments",
        Map.of("adjustmentAmount", "10.0000", "direction", "OUT", "explanation", "补记"),
        ReconciliationDiffAdjustmentRow.class);
    post("/" + differenceExternal + "/adjust", Map.of("conclusion", "已调整"), ReconciliationDiffHandling.class);

    assertThat(postRaw("/" + differenceExternal + "/claim", Map.of()).getStatusCode().value())
        .isEqualTo(409);
    assertThat(
            postRaw(
                    "/" + differenceExternal + "/classify",
                    Map.of("category", "FX_DIFFERENCE", "reason", "想改判"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            postRaw("/" + differenceExternal + "/adjust", Map.of("conclusion", "再调一次"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            postRaw("/" + differenceExternal + "/adjustments",
                    Map.of("adjustmentAmount", "1.0000", "direction", "IN", "explanation", "补"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void 详情把可执行动作和阻断理由都说清楚() {
    var before = get("/" + differenceExternal, ReconciliationDiffDetail.class);
    assertThat(before.actions().canClaim()).isTrue();
    assertThat(before.actions().canClassify()).isTrue();
    assertThat(before.actions().canRecordAdjustment()).isFalse();
    assertThat(before.actions().recordAdjustmentBlockReason()).contains("已认领");

    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);
    var after = get("/" + differenceExternal, ReconciliationDiffDetail.class);
    assertThat(after.actions().canRecordAdjustment()).isTrue();
    assertThat(after.actions().canAdjust()).isFalse();
    assertThat(after.actions().adjustBlockReason()).contains("调整分录");
    assertThat(after.actions().canWriteOff()).isTrue();
    assertThat(after.adjustments()).isEmpty();
  }

  // ==========================================================================
  // 分类分布（改进闭环的输入）
  // ==========================================================================

  @Test
  void 分类分布是改进闭环的输入() {
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);
    post(
        "/" + differenceBook + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "又一笔佣金没记"),
        ReconciliationDiffHandling.class);
    post(
        "/" + differenceCurrency + "/classify",
        Map.of("category", "FX_DIFFERENCE", "reason", "折算口径不同"),
        ReconciliationDiffHandling.class);

    var page = get("", ReconciliationDiffQueuePage.class);
    assertThat(page.categories())
        .filteredOn(s -> "FEE_OMISSION".equals(s.category()))
        .hasSize(1);
    var fee =
        page.categories().stream()
            .filter(s -> "FEE_OMISSION".equals(s.category()))
            .findFirst()
            .orElseThrow();
    assertThat(fee.currency()).isEqualTo("USD");
    assertThat(fee.count()).isEqualTo(2);
    assertThat(fee.differenceAmount()).isEqualByComparingTo("-75");

    var fx =
        page.categories().stream()
            .filter(s -> "FX_DIFFERENCE".equals(s.category()))
            .findFirst()
            .orElseThrow();
    assertThat(fx.currency()).isEqualTo("EUR");
    assertThat(fx.count()).isEqualTo(1);
    assertThat(fx.differenceAmount()).as("跨币种的差额恒为 null").isNull();
  }

  // ==========================================================================
  // 时间线
  // ==========================================================================

  @Test
  void 时间线记录了每一步() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/adjustments",
        Map.of("adjustmentAmount", "10.0000", "direction", "OUT", "explanation", "补记手续费"),
        ReconciliationDiffAdjustmentRow.class);
    post("/" + differenceExternal + "/adjust", Map.of("conclusion", "按平台调整"), ReconciliationDiffHandling.class);

    var events = get("/" + differenceExternal + "/events", ReconciliationDiffEventPage.class);
    // 建记录 + 认领 + 分类 + 登记分录 + 判定 = 5 条。
    assertThat(events.total()).isEqualTo(5);
    assertThat(events.items()).extracting(ReconciliationDiffEventRow::eventType)
        .containsExactlyInAnyOrder(
            "HANDLING_CREATED",
            "ASSIGNED",
            "CLASSIFIED",
            "ADJUSTMENT_RECORDED",
            "ADJUSTED");
    assertThat(events.items()).allSatisfy(e -> assertThat(e.summary()).isNotBlank());
  }

  @Test
  void 时间线不可改写() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);

    // ① 运行角色**根本没有** UPDATE 权限（权限是第一道保险）。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_handling_event set summary = 'x'"
                              + " where tenant_id = ?",
                          tenant);
                      return null;
                    }))
        .as("运行角色不得改写时间线")
        .hasStackTraceContaining("permission denied");

    // ② 就算用**表属主**（绕过权限）写，触发器仍然拒绝（第二道保险）。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
                s.execute(
                    "update finance_reconciliation_handling_event set summary = 'x'"
                        + " where tenant_id = '" + tenant + "'");
              }
            })
        .as("时间线是追加式举证，不可改写")
        .hasStackTraceContaining("追加式举证");
  }

  // ==========================================================================
  // 数据层守卫（直连 SQL 也绕不过）
  // ==========================================================================

  @Test
  void 直连sql也写不出没有调整分录的已调整() {
    post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);
    post(
        "/" + differenceExternal + "/classify",
        Map.of("category", "FEE_OMISSION", "reason", "平台扣了佣金"),
        ReconciliationDiffHandling.class);

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_difference set status = 'ADJUSTED'"
                              + " where id = ? and tenant_id = ?",
                          differenceExternal,
                          tenant);
                      sql.execute(
                          "update finance_reconciliation_handling set status = 'ADJUSTED',"
                              + " conclusion = '手工改的', closed_by = ?, closed_at = ?::timestamptz"
                              + " where difference_id = ? and tenant_id = ?",
                          actorId(),
                          OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC),
                          differenceExternal,
                          tenant);
                      return null;
                    }))
        .as("直连 SQL 也写不出「没有调整分录的已调整」")
        .hasStackTraceContaining("调整分录");
  }

  @Test
  void 直连sql也写不出与上游不同相的处理状态() {
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into finance_reconciliation_handling(id,tenant_id,"
                              + " difference_id,task_id,currency,difference_type,reference,"
                              + " category,category_reason,category_set_by,category_set_at,"
                              + " assignee_id,status,conclusion,version,created_by,created_at,"
                              + " updated_at)"
                              + " values (?,?,?,?,?,?,'',null,'',null,null,null,'ADJUSTED','x',0,?,"
                              + " ?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          differenceExternal,
                          taskId,
                          "USD",
                          "EXTERNAL_ONLY",
                          actorId(),
                          OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC),
                          OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC));
                      return null;
                    }))
        .as("处理记录的状态必须与上游差异状态同相")
        .hasStackTraceContaining("一致");
  }

  @Test
  void 上游差异的事实字段不可被本模块改写() {
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "update finance_reconciliation_difference"
                              + " set difference_amount = 999 where id = ? and tenant_id = ?",
                          differenceExternal,
                          tenant);
                      return null;
                    }))
        .as("差异是执行时的事实，只有 status 能向前推进")
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 运行角色不能删除处理记录也不能改写分录与时间线() {
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app',"
                        + "'finance_reconciliation_handling','DELETE')")
                .get(0, Boolean.class))
        .as("闭环记录删掉 = 差异凭空消失 ⇒ 运行角色不能 DELETE")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app',"
                        + "'finance_reconciliation_adjustment','UPDATE')")
                .get(0, Boolean.class))
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app',"
                        + "'finance_reconciliation_handling_event','DELETE')")
                .get(0, Boolean.class))
        .isFalse();
  }

  // ==========================================================================
  // 时间口径
  // ==========================================================================

  @Test
  void 提交时刻写回逐位相等不偏八小时() {
    var handling = post("/" + differenceExternal + "/claim", Map.of(), ReconciliationDiffHandling.class);

    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select claimed_at, created_at from finance_reconciliation_handling"
                            + " where difference_id = ? and tenant_id = ?",
                        differenceExternal,
                        tenant)
                    .get("claimed_at", OffsetDateTime.class));

    assertThat(stored).as("写回的值必须与接口返回的值逐位相等").isEqualTo(handling.claimedAt());
    // 🔴 写进去的是**当前时刻**（UTC 瞬时），不是「东八区减 8 小时」的错值 ——
    //   `Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s，到账时间偏 8 小时就是错账。
    assertThat(stored.toInstant().getEpochSecond())
        .isEqualTo(handling.claimedAt().toInstant().getEpochSecond());
    assertThat(
            Math.abs(
                stored.toInstant().getEpochSecond()
                    - OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()))
        .as("认领时刻必须是当前时刻附近，不是偏移 8 小时的错值")
        .isLessThan(300L);
  }

  // ==========================================================================
  // 帮手
  // ==========================================================================

  UUID actorId() {
    return actors.get(BEARER).userId();
  }

  String upstreamStatus(UUID differenceId) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne(
                    "select status from finance_reconciliation_difference"
                        + " where id = ? and tenant_id = ?",
                    differenceId,
                    tenant)
                .get("status", String.class));
  }

  long handlingRows(UUID differenceId) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select id from finance_reconciliation_handling"
                        + " where difference_id = ? and tenant_id = ?",
                    differenceId,
                    tenant)
                .size());
  }
}
