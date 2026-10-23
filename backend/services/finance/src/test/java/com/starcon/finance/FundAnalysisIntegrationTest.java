package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FundAnalysisModels.*;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.7.2.1 资金分析的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.7.2.1 的**六项能力**，每一项都断言**真算出来的数**，
 * 不是常量、不是 mock：
 *
 * <ol>
 *   <li>现金流入流出趋势 —— 三个口径（实际 / 预期 / 计划）分开，且按币种分开；</li>
 *   <li>各币种资金分布 —— 四路真实聚合；</li>
 *   <li>汇率敞口测算 —— 真读 catalog 的汇率（本地 stub 模拟 catalog 的 HTTP 契约），
 *       敏感度与集中度都对得上；</li>
 *   <li>资金周转天数 —— DSO / DPO 同币种同口径，存货段**恒为 null**（不用 0 冒充）；</li>
 *   <li>付款集中度 —— 计划口径 + 真实占比；</li>
 *   <li>流动性预警 —— 四条规则跑在真实聚合上。</li>
 * </ol>
 *
 * <p><b>上游数据怎么来的</b>：用**上游模块的服务层**能建的就用服务层（收款单），
 * 其余（应收 / 应付 / 付款计划）在**租户事务里**直接落库 ——
 * 本模块是只读投影，断言的重点是「我的聚合算得对不对」，不是「上游单据怎么建」。
 * 落库时**遵守上游自己的触发器**：先建草稿头 → 落明细行 → 再 DRAFT→OPEN。
 *
 * <p>⚠️ <b>UTC 日历日</b>：断言日历日时，从库里读回的时刻**先转 UTC 再取日期**；
 * 趋势分桶断言的是「UTC 日历日」而不是「本地日期」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FundAnalysisIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID supplier = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.receivable.read", "finance.receivable.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  /** catalog 的汇率事实（真值在这里，不在 finance 服务里）。 */
  static final Map<String, BigDecimal> RATES = new ConcurrentHashMap<>();

  static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

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

      // catalog 的 5.2.2.1 汇率查询：**只有真的登记过的币种对**才有汇率。
      // 查不到一律 404 —— 与真服务一致（本模块必须显式失败，不许拿空集合冒充）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            var query = e.getRequestURI().getRawQuery();
            var base = param(query, "baseCurrency");
            var quote = param(query, "quoteCurrency");
            var rate = base == null || quote == null ? null : RATES.get(base + ">" + quote);
            if (rate == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            byte[] body =
                ("{\"baseCurrency\":\""
                        + base
                        + "\",\"quoteCurrency\":\""
                        + quote
                        + "\",\"rate\":"
                        + rate.toPlainString()
                        + ",\"effectiveDate\":\""
                        + TODAY
                        + "\",\"sourceCode\":\"TEST\",\"display\":\"1 "
                        + base
                        + " = "
                        + rate.toPlainString()
                        + " "
                        + quote
                        + "\"}")
                    .getBytes();
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static String param(String query, String key) {
    if (query == null) return null;
    for (var part : query.split("&")) {
      var eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).equals(key)) {
        return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired FundAnalysisService analysis;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_fund_exposure_snapshot, finance_liquidity_threshold,"
              + " finance_payment_plan_event, finance_payment_reminder,"
              + " finance_payment_plan, finance_payment_batch, finance_payment_milestone,"
              + " finance_payment_milestone_rule, finance_payment_term_rule,"
              + " finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy,"
              + " finance_receipt_event, finance_receipt_allocation, finance_receipt,"
              + " finance_receivable_event, finance_receivable_line, finance_receivable,"
              + " finance_receivable_policy, finance_collection_task"
              + " restart identity cascade");
    }
    RATES.clear();
    RATES.put("USD>CNY", new BigDecimal("7"));
    RATES.put("EUR>CNY", new BigDecimal("7.5"));

    // ---- 收款单（实际流入）----
    receipt("RCPT-A", "USD", "1000", TODAY.minusDays(8).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(), false);
    receipt("RCPT-B", "USD", "500", TODAY.minusDays(2).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(), false);
    receipt("RCPT-C", "EUR", "200", TODAY.minusDays(5).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(), false);
    receipt("RCPT-D", "EUR", "100", TODAY.minusDays(4).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(), true);

    // ---- 应收单（预期流入）----
    receivable("AR-A", "USD", "800", TODAY.plusDays(5));
    receivable("AR-B", "USD", "300", TODAY.minusDays(2)); // 已过到期日 ⇒ 逾期
    receivable("AR-C", "EUR", "400", TODAY.plusDays(10));

    // ---- 应付单（负债端）----
    var payA = payable("AP-A", "USD", "600", TODAY.plusDays(7), TODAY.minusDays(5));
    var payB = payable("AP-B", "USD", "200", TODAY.plusDays(20), TODAY.minusDays(6));
    var payC = payable("AP-C", "EUR", "250", TODAY.plusDays(12), TODAY.minusDays(5));

    // ---- 付款计划（计划流出；execution_available 被 CHECK 钉死为 false）----
    plan("PLAN-A", payA, "USD", "600", TODAY.plusDays(7));
    plan("PLAN-B", payB, "USD", "200", TODAY.plusDays(20));
    plan("PLAN-C", payC, "EUR", "250", TODAY.plusDays(12));
  }

  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  AccessService.Access actor() {
    return actors.get("session");
  }

  /** 落一张收款单（实际已到账）。 */
  void receipt(String no, String currency, String amount, OffsetDateTime receivedAt, boolean advance) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    as(
        () -> {
          sql.execute(
              "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,currency,"
                  + " amount,settled_amount,advance_flag,status,received_at,payer_type,payer_name,"
                  + " fund_account_label,note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual',?,?,0,?,'RECORDED',?::timestamptz,'CUSTOMER','客户',"
                  + " '测试账户','',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              no,
              currency,
              new BigDecimal(amount),
              advance,
              receivedAt,
              now,
              now);
          return null;
        });
  }

  /** 落一张应收单：草稿头 → 明细行 → DRAFT→OPEN（遵守上游自己的触发器）。 */
  void receivable(String no, String currency, String amount, LocalDate dueDate) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER','客户',?,?::date,?::timestamptz,'DRAFT',"
                  + " '',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              currency,
              dueDate,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
                  + " amount,settled_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_receivable set status = 'OPEN' where id = ?", id);
          return null;
        });
  }

  /** 落一张应付单：草稿头 → 明细行 → DRAFT→OPEN。返回单头 id。 */
  UUID payable(String no, String currency, String amount, LocalDate dueDate, LocalDate confirmedOn) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    var confirmedAt = confirmedOn.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    as(
        () -> {
          sql.execute(
              "insert into finance_payable(id,tenant_id,payable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','SUPPLIER','供应商甲',?,?::date,?::timestamptz,"
                  + " 'DRAFT','',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              currency,
              dueDate,
              confirmedAt,
              now,
              now);
          sql.execute(
              "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,"
                  + " amount,paid_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_payable set status = 'OPEN' where id = ?", id);
          return null;
        });
    return id;
  }

  /** 落一条付款计划（计划口径：execution_available 由 CHECK 钉死为 false）。 */
  void plan(String no, UUID payableId, String currency, String amount, LocalDate plannedPaidDate) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    as(
        () -> {
          sql.execute(
              "insert into finance_payment_plan(id,tenant_id,plan_no,payable_id,payment_condition,"
                  + " condition_date,planned_paid_date,currency,planned_amount,unpaid_snapshot,"
                  + " status,note,created_at,updated_at)"
                  + " values(?,?,?,?,'DUE_DATE',?::date,?::date,?,?,?,'PLANNED','',"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              no,
              payableId,
              plannedPaidDate,
              plannedPaidDate,
              currency,
              new BigDecimal(amount),
              new BigDecimal(amount),
              now,
              now);
          return null;
        });
  }

  // ================================================================ HTTP 辅助

  <T> T readJson(String path, Class<T> type) throws Exception {
    var body =
        http.perform(get(path).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, type);
  }

  // ==========================================================================
  // 能力声明：12.7.1 的缺口必须**如实标注**
  // ==========================================================================

  @Test
  void 能力声明把12_7_1的缺口标成不可用并写明归属模块() throws Exception {
    var caps = readJson("/finance/fund-analysis/capabilities", FinanceFundAnalysisCapabilities.class);

    var account =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.7.1.1"))
            .findFirst()
            .orElseThrow();
    assertThat(account.available()).as("12.7.1.1 账户管理未交付 ⇒ 账户级余额不可用").isFalse();
    assertThat(account.ownerModule()).isEqualTo("12.7.1.1");

    var bank =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.7.1.2"))
            .findFirst()
            .orElseThrow();
    assertThat(bank.available()).as("12.7.1.2 银行对账未交付 ⇒ 银行流水不可用").isFalse();
    assertThat(bank.ownerModule()).isEqualTo("12.7.1.2");

    // 本模块自己的六项能力**全部**已交付 —— 不能因为上游缺了两个口径就全标不可用。
    var own =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.7.2.1"))
            .filter(c -> c.available())
            .count();
    assertThat(own).as("12.7.2.1 的六项能力都应已交付").isGreaterThanOrEqualTo(6);
  }

  @Test
  void 缺口在DB层被钉死而不是只写在文档里() throws Exception {
    // 受管资金账户归属：恒 NULL + 归属模块写死。
    var row =
        one(
            "select fund_account_ref, fund_account_owner_module, bank_statement_ref,"
                + " bank_statement_owner_module, account_level_available"
                + " from finance_fund_exposure_snapshot limit 1");
    // 此刻还没有快照 ⇒ 断言的是**表定义**上的 CHECK：直接塞一个值必须被拒。
    assertThat(row).as("还没有快照是正常的（本测试先验表约束）").isNull();

    var error =
        dbError(
            "insert into finance_fund_exposure_snapshot(id,tenant_id,snapshot_no,as_of_date,"
                + " base_currency,currency,direction,receivable_open_amount,payable_open_amount,"
                + " net_exposure_amount,exchange_rate,rate_effective_date,base_exposure_amount,"
                + " sensitivity_per_1pct,sensitivity_per_5pct,fund_account_ref,"
                + " fund_account_owner_module,bank_statement_ref,bank_statement_owner_module,"
                + " account_level_available,note,created_at)"
                + " values(?,?,?,'2026-01-01','CNY','USD','LONG_ASSET',0,0,0,7,'2026-01-01',0,0,0,?,"
                + " '12.7.1.1',null,'12.7.1.2',false,'',?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            "EXPO-FORGED-" + UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC));
    assertThat(error).as("受管账户归属必须被 CHECK 钉死恒为 NULL").contains("fund_account_ref");
  }

  /** 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息。 */
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

  // ==========================================================================
  // ① 现金流入流出趋势
  // ==========================================================================

  @Test
  void 趋势的三个口径分开且按币种分开() throws Exception {
    var trend =
        readJson(
            "/finance/fund-analysis/cash-flow-trend?from=" + TODAY.minusDays(29) + "&to="
                + TODAY.plusDays(29) + "&granularity=DAY",
            FinanceFundFlowTrend.class);

    var usd =
        trend.totals().stream()
            .filter(t -> t.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.actualInflowAmount()).as("USD 实际流入 = 1000 + 500").isEqualByComparingTo("1500");
    assertThat(usd.expectedInflowAmount())
        .as("USD 预期流入 = 800 + 300")
        .isEqualByComparingTo("1100");
    assertThat(usd.plannedOutflowAmount()).as("USD 计划流出 = 600 + 200").isEqualByComparingTo("800");
    assertThat(usd.netExpectedAmount())
        .as("同口径预测净额 = 预期流入 − 计划流出")
        .isEqualByComparingTo("300");

    var eur =
        trend.totals().stream()
            .filter(t -> t.currency().equals("EUR"))
            .findFirst()
            .orElseThrow();
    assertThat(eur.actualInflowAmount()).as("EUR 实际流入 = 200 + 100").isEqualByComparingTo("300");
    assertThat(eur.expectedInflowAmount()).isEqualByComparingTo("400");
    assertThat(eur.plannedOutflowAmount()).isEqualByComparingTo("250");

    // 币种分开：绝没有跨币种求和的字段。
    assertThat(trend.totals()).hasSize(2);
    assertThat(trend.actualOutflowBasis()).as("实际流出没有日期维度，必须写明").contains("12.2.2.1");
    assertThat(trend.actualOutflowOwnerModule()).isEqualTo("12.2.2.1");
  }

  @Test
  void 趋势按UTC日历日分桶而不是按会话时区() throws Exception {
    // 一笔在 UTC 23:30 到账的款：UTC 日历日是 D，+08:00 会把它算成 D+1。
    var day = TODAY.minusDays(4);
    receipt(
        "RCPT-TZ",
        "JPY",
        "100",
        day.atTime(23, 30).atOffset(ZoneOffset.UTC),
        false);

    var trend =
        readJson(
            "/finance/fund-analysis/cash-flow-trend?from=" + TODAY.minusDays(9) + "&to="
                + TODAY.plusDays(9) + "&granularity=DAY&currency=JPY",
            FinanceFundFlowTrend.class);

    var bucket =
        trend.buckets().stream()
            .filter(b -> b.actualInflowAmount().signum() > 0)
            .findFirst()
            .orElseThrow();
    assertThat(bucket.periodStart()).as("必须落在 UTC 日历日上").isEqualTo(day);
    assertThat(bucket.actualInflowAmount()).isEqualByComparingTo("100");
  }

  // ==========================================================================
  // ② 各币种资金分布
  // ==========================================================================

  @Test
  void 币种分布是四路真实聚合() throws Exception {
    var distribution =
        readJson("/finance/fund-analysis/currency-distribution", FinanceFundCurrencyDistribution.class);

    var usd =
        distribution.positions().stream()
            .filter(p -> p.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.receivedCashAmount()).as("USD 累计已到账 = 1000 + 500").isEqualByComparingTo("1500");
    assertThat(usd.unsettledReceivedAmount()).isEqualByComparingTo("1500");
    assertThat(usd.advanceAmount()).as("USD 没有预收").isEqualByComparingTo("0");
    assertThat(usd.receivableOpenAmount()).as("USD 未核销应收 = 800 + 300").isEqualByComparingTo("1100");
    assertThat(usd.payableUnpaidAmount()).as("USD 未付应付 = 600 + 200").isEqualByComparingTo("800");
    assertThat(usd.paidOutAmount())
        .as("12.2.2.1 未交付 ⇒ 累计已付恒为 0")
        .isEqualByComparingTo("0");
    assertThat(usd.plannedOutflowAmount()).isEqualByComparingTo("800");
    assertThat(usd.netSettlementAmount())
        .as("未来结算净额 = 未核销应收 − 未付应付")
        .isEqualByComparingTo("300");

    var eur =
        distribution.positions().stream()
            .filter(p -> p.currency().equals("EUR"))
            .findFirst()
            .orElseThrow();
    assertThat(eur.receivedCashAmount()).isEqualByComparingTo("300");
    assertThat(eur.advanceAmount()).as("EUR 预收 100").isEqualByComparingTo("100");
    assertThat(eur.receivableOpenAmount()).isEqualByComparingTo("400");
    assertThat(eur.payableUnpaidAmount()).isEqualByComparingTo("250");

    // 账户级不可用必须写在响应里，而不是让人以为「账户余额是 0」。
    assertThat(distribution.accountLevelAvailable()).isFalse();
    assertThat(distribution.accountOwnerModule()).isEqualTo("12.7.1.1");
    assertThat(distribution.cashBasisNote()).contains("不是");
  }

  @Test
  void 币种筛选真的生效() throws Exception {
    var distribution =
        readJson(
            "/finance/fund-analysis/currency-distribution?currency=EUR",
            FinanceFundCurrencyDistribution.class);
    assertThat(distribution.positions()).hasSize(1);
    assertThat(distribution.positions().get(0).currency()).isEqualTo("EUR");
  }

  // ==========================================================================
  // ③ 汇率敞口
  // ==========================================================================

  @Test
  void 汇率敞口用真汇率折算并给出敏感度与集中度() throws Exception {
    var exposure =
        readJson("/finance/fund-analysis/exposure?baseCurrency=CNY", FinanceFundExposureReport.class);

    assertThat(exposure.baseCurrency()).isEqualTo("CNY");
    assertThat(exposure.baseCurrencyBasis()).as("显式指定要说清").isEqualTo("调用方显式指定");

    var usd =
        exposure.rows().stream()
            .filter(r -> r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.receivableOpenAmount()).isEqualByComparingTo("1100");
    assertThat(usd.payableOpenAmount()).isEqualByComparingTo("800");
    assertThat(usd.netExposureAmount()).as("净敞口 = 资产端 − 负债端").isEqualByComparingTo("300");
    assertThat(usd.direction()).isEqualTo("LONG_ASSET");
    assertThat(usd.exchangeRate()).isEqualByComparingTo("7");
    assertThat(usd.baseExposureAmount()).as("本位币敞口 = 净敞口 × 汇率").isEqualByComparingTo("2100");
    assertThat(usd.sensitivityPer1Pct()).as("汇率每动 1% 的影响").isEqualByComparingTo("21");
    assertThat(usd.sensitivityPer5Pct()).isEqualByComparingTo("105");

    var eur =
        exposure.rows().stream()
            .filter(r -> r.currency().equals("EUR"))
            .findFirst()
            .orElseThrow();
    assertThat(eur.netExposureAmount()).isEqualByComparingTo("150");
    assertThat(eur.baseExposureAmount()).isEqualByComparingTo("1125");
    assertThat(eur.sensitivityPer1Pct()).isEqualByComparingTo("11.2500");

    // 集中度：2100 / (2100 + 1125)。
    assertThat(usd.exposureShare()).isEqualByComparingTo("0.651163");
    assertThat(eur.exposureShare()).isEqualByComparingTo("0.348837");
    assertThat(exposure.totalBaseExposureAmount()).isEqualByComparingTo("3225");
    assertThat(exposure.inventoryOwnerModule()).isEqualTo("13.1.1.1");
  }

  @Test
  void 汇率取不到就显式失败而不是返回空集合() throws Exception {
    // JPY 有余额但**没有登记汇率** ⇒ 上游 404 ⇒ 本模块必须整体失败。
    receivable("AR-JPY", "JPY", "1000", TODAY.plusDays(3));

    // 服务层：拒绝理由要说清是「读不到汇率」（HTTP 层只断言状态码）。
    assertThatThrownBy(() -> as(() -> analysis.exposure(new FinanceFundExposureQuery("CNY", null), "session")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("汇率");

    http.perform(get("/finance/fund-analysis/exposure?baseCurrency=CNY").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 读不到本位币时显式失败而不是默默挑一个() throws Exception {
    // 夹具里的单据都没有 base_currency ⇒ 没有本位币 ⇒ 必须显式失败
    //（默默挑一个会产出「看着像真的」的敞口数字）。
    assertThatThrownBy(() -> as(() -> analysis.exposure(new FinanceFundExposureQuery(null, null), "session")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("本位币");

    http.perform(get("/finance/fund-analysis/exposure").header("Authorization", "session"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 敞口测算落库成快照并钉住汇率与归属模块() throws Exception {
    var response =
        http.perform(
                post("/finance/fund-analysis/exposure/measure?baseCurrency=CNY")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var result =
        json.readValue(response.getContentAsString(), FinanceFundExposureMeasureResult.class);
    assertThat(result.snapshots()).as("USD 与 EUR 各一行；本位币那行不落库").hasSize(2);
    assertThat(result.snapshots()).allMatch(s -> s.baseCurrency().equals("CNY"));

    var row =
        one(
            "select exchange_rate, rate_effective_date, fund_account_ref, fund_account_owner_module,"
                + " bank_statement_ref, bank_statement_owner_module, account_level_available"
                + " from finance_fund_exposure_snapshot where currency = 'USD'");
    assertThat(row.get("exchange_rate", BigDecimal.class)).isEqualByComparingTo("7");
    assertThat(row.get("rate_effective_date", LocalDate.class)).isEqualTo(TODAY);
    assertThat(row.get("fund_account_ref", UUID.class)).as("受管账户归属恒为 NULL").isNull();
    assertThat(row.get("fund_account_owner_module", String.class)).isEqualTo("12.7.1.1");
    assertThat(row.get("bank_statement_ref", UUID.class)).as("银行流水归属恒为 NULL").isNull();
    assertThat(row.get("bank_statement_owner_module", String.class)).isEqualTo("12.7.1.2");
    assertThat(row.get("account_level_available", Boolean.class)).isFalse();

    // 快照历史能回看（真分页 + 真排序）。
    var page =
        readJson("/finance/fund-analysis/exposure/snapshots", FinanceFundExposureSnapshotPage.class);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
  }

  @Test
  void 快照只能追加不能改() throws Exception {
    http.perform(
            post("/finance/fund-analysis/exposure/measure?baseCurrency=CNY")
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    // ⚠️ 用**运行角色**的权限来判，不能用超管直连试写：
    //   超管绕过一切授权（RLS 也绕过），拿它试 UPDATE 永远「成功」，那种断言是假的。
    //   快照是举证凭据（一次测算的输入与结论）⇒ 运行角色只能追加，不能改、不能删。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_fund_exposure_snapshot','UPDATE')")
                .get(0, Boolean.class))
        .as("快照的 UPDATE 权限必须对运行角色收回")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_fund_exposure_snapshot','DELETE')")
                .get(0, Boolean.class))
        .as("快照的 DELETE 权限必须对运行角色收回")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_fund_exposure_snapshot','INSERT')")
                .get(0, Boolean.class))
        .as("追加仍然要允许")
        .isTrue();
  }

  // ==========================================================================
  // ④ 资金周转天数
  // ==========================================================================

  @Test
  void 周转天数用同币种同日历日口径且存货段恒为空() throws Exception {
    var report =
        readJson(
            "/finance/fund-analysis/turnover-days?from=" + TODAY.minusDays(9) + "&to=" + TODAY,
            FinanceFundTurnoverReport.class);
    assertThat(report.periodDays()).as("期间天数 = 自然日历日（含首尾）").isEqualTo(10);

    var usd =
        report.rows().stream()
            .filter(r -> r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.receivableOpenAmount()).isEqualByComparingTo("1100");
    assertThat(usd.actualInflowInPeriod()).as("窗口内实际流入 = 1000 + 500").isEqualByComparingTo("1500");
    assertThat(usd.payableUnpaidAmount()).isEqualByComparingTo("800");
    assertThat(usd.payableOccurrenceInPeriod())
        .as("期间应付发生额 = 600 + 200（确认日落在窗口内）")
        .isEqualByComparingTo("800");
    // DSO = 1100 / 1500 × 10 = 7.3333
    assertThat(usd.dsoDays()).isEqualByComparingTo("7.3333");
    // DPO = 800 / 800 × 10 = 10
    assertThat(usd.dpoDays()).isEqualByComparingTo("10");
    assertThat(usd.fundTurnoverDays()).as("资金周转天数 = DSO − DPO").isEqualByComparingTo("-2.6667");

    // 存货段：**恒为 null**，不用 0 冒充。
    assertThat(usd.dioDays()).as("存货不在本服务 ⇒ 不能给 0 天").isNull();
    assertThat(usd.inventoryBasisAvailable()).isFalse();
    assertThat(usd.inventoryOwnerModule()).isEqualTo("13.1.1.1");
    assertThat(report.actualOutflowOwnerModule()).isEqualTo("12.2.2.1");
  }

  @Test
  void 期间没有流入时周转天数是空而不是零天() throws Exception {
    // 一个**完全没有收款**的币种：分母为 0 ⇒ DSO 算不出 ⇒ 给 null。
    receivable("AR-X", "GBP", "100", TODAY.plusDays(3));
    var report =
        readJson(
            "/finance/fund-analysis/turnover-days?from=" + TODAY.minusDays(9) + "&to=" + TODAY,
            FinanceFundTurnoverReport.class);
    var gbp =
        report.rows().stream()
            .filter(r -> r.currency().equals("GBP"))
            .findFirst()
            .orElseThrow();
    assertThat(gbp.actualInflowInPeriod()).isEqualByComparingTo("0");
    assertThat(gbp.dsoDays()).as("「算不出」≠「0 天」").isNull();
    assertThat(gbp.fundTurnoverDays()).isNull();
  }

  // ==========================================================================
  // ⑤ 付款集中度
  // ==========================================================================

  @Test
  void 付款集中度是计划口径且给出真实占比() throws Exception {
    var report =
        readJson(
            "/finance/fund-analysis/payment-concentration?from=" + TODAY + "&to="
                + TODAY.plusDays(29) + "&granularity=WEEK",
            FinanceFundPaymentConcentrationReport.class);

    var usd =
        report.rows().stream()
            .filter(r -> r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.totalPlannedAmount()).as("USD 计划流出 = 600 + 200").isEqualByComparingTo("800");
    assertThat(usd.planCount()).isEqualTo(2);
    // 两个计划隔了两周 ⇒ **桶之间不留缝**（中间那个空桶必须在，否则集中度会被少算一格）。
    assertThat(usd.buckets()).hasSize(3);
    assertThat(usd.buckets().get(1).amount()).as("中间那个空桶必须是 0").isEqualByComparingTo("0");
    assertThat(usd.buckets().get(1).share()).isEqualByComparingTo("0");
    assertThat(usd.topBucketShare()).as("最集中的一桶 = 600 / 800").isEqualByComparingTo("0.75");
    assertThat(usd.topCounterpartyShare()).isEqualByComparingTo("1");
    assertThat(report.basis()).as("口径必须写明是「计划」").contains("计划付款");
  }

  // ==========================================================================
  // ⑥ 流动性预警
  // ==========================================================================

  @Test
  void 流动性预警按真实聚合触发且阈值可配() throws Exception {
    var report =
        readJson(
            "/finance/fund-analysis/liquidity-alerts?from=" + TODAY + "&to=" + TODAY.plusDays(29),
            FinanceFundLiquidityAlertReport.class);

    assertThat(report.thresholdSource()).as("没配过时用蓝图默认档").isEqualTo("BLUEPRINT_DEFAULT");
    assertThat(report.thresholds()).hasSize(4);

    var usdAlerts =
        report.alerts().stream()
            .filter(a -> a.currency().equals("USD"))
            .map(a -> a.rule())
            .toList();
    assertThat(usdAlerts).as("付款集中度 0.75 ≥ 0.40 ⇒ 预警").contains("PAYMENT_CONCENTRATION");
    assertThat(usdAlerts)
        .as("计划流出 800 / 流入 800 = 1.00 ≥ 1.00 ⇒ 预警")
        .contains("NET_OUTFLOW_GAP");
    assertThat(usdAlerts).as("现金覆盖天数 0 ≤ 7 ⇒ 预警").contains("CASH_BUFFER_DAYS");
    assertThat(usdAlerts).as("逾期 300/1100 = 0.27 < 0.30 ⇒ 不预警").doesNotContain("RECEIVABLE_OVERDUE");

    // 观测值必须落在响应里（页面不做算术）。
    var concentration =
        report.alerts().stream()
            .filter(a -> a.rule().equals("PAYMENT_CONCENTRATION") && a.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(concentration.observedValue()).isEqualByComparingTo("0.75");
    assertThat(concentration.thresholdValue()).isEqualByComparingTo("0.40");
    assertThat(concentration.severity()).isEqualTo("WARN");
  }

  @Test
  void 阈值配置整体替换且缺规则要被拒绝() throws Exception {
    // 缺一条规则 ⇒ 整体拒绝（部分配置会让预警集合没有确定含义）。
    var partial =
        """
        {"thresholds":[
          {"code":"PAYMENT_CONCENTRATION","label":"付款集中度","thresholdValue":0.5,"enabled":true,"note":""},
          {"code":"NET_OUTFLOW_GAP","label":"净流出缺口","thresholdValue":1,"enabled":true,"note":""},
          {"code":"RECEIVABLE_OVERDUE","label":"应收逾期","thresholdValue":0.3,"enabled":true,"note":""}
        ]}
        """;
    http.perform(
            put("/finance/fund-analysis/thresholds")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(partial))
        .andExpect(status().isBadRequest());

    // 四条齐 ⇒ 落库，且生效的是租户自己的值。
    var full =
        """
        {"thresholds":[
          {"code":"PAYMENT_CONCENTRATION","label":"付款集中度","thresholdValue":0.9,"enabled":true,"note":"放宽"},
          {"code":"NET_OUTFLOW_GAP","label":"净流出缺口","thresholdValue":1,"enabled":true,"note":""},
          {"code":"RECEIVABLE_OVERDUE","label":"应收逾期","thresholdValue":0.3,"enabled":true,"note":""},
          {"code":"CASH_BUFFER_DAYS","label":"现金覆盖天数","thresholdValue":7,"enabled":true,"note":""}
        ]}
        """;
    http.perform(
            put("/finance/fund-analysis/thresholds")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(full))
        .andExpect(status().isOk());

    var config =
        readJson("/finance/fund-analysis/thresholds", FinanceFundLiquidityThresholdConfig.class);
    assertThat(config.source()).isEqualTo("TENANT_CONFIG");
    assertThat(config.configured()).isTrue();
    var concentration =
        config.thresholds().stream()
            .filter(t -> t.code().equals("PAYMENT_CONCENTRATION"))
            .findFirst()
            .orElseThrow();
    assertThat(concentration.thresholdValue()).isEqualByComparingTo("0.9");

    // 阈值放宽到 0.9 ⇒ 0.75 不再超阈值 ⇒ 预警消失（预警真的跟着阈值走）。
    var report =
        readJson(
            "/finance/fund-analysis/liquidity-alerts?from=" + TODAY + "&to=" + TODAY.plusDays(29),
            FinanceFundLiquidityAlertReport.class);
    assertThat(report.alerts().stream()
            .filter(a -> a.rule().equals("PAYMENT_CONCENTRATION") && a.currency().equals("USD")))
        .as("阈值放宽后不该再报集中度预警")
        .isEmpty();
  }

  @Test
  void 未知规则码要被拒绝() throws Exception {
    var body =
        """
        {"thresholds":[
          {"code":"NOT_A_RULE","label":"瞎写的","thresholdValue":1,"enabled":true,"note":""},
          {"code":"NET_OUTFLOW_GAP","label":"净流出缺口","thresholdValue":1,"enabled":true,"note":""},
          {"code":"RECEIVABLE_OVERDUE","label":"应收逾期","thresholdValue":0.3,"enabled":true,"note":""},
          {"code":"CASH_BUFFER_DAYS","label":"现金覆盖天数","thresholdValue":7,"enabled":true,"note":""}
        ]}
        """;
    http.perform(
            put("/finance/fund-analysis/thresholds")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 筛选项与其它基本形状
  // ==========================================================================

  @Test
  void 筛选项来自真实数据() throws Exception {
    var options = readJson("/finance/fund-analysis/options", FinanceFundAnalysisOptions.class);
    assertThat(options.currencies()).contains("USD", "EUR");
    assertThat(options.currencies()).doesNotContain("JPY");
    assertThat(options.asOfDate()).isEqualTo(TODAY);
  }

  @Test
  void 没有权限就拿不到数据() throws Exception {
    var response =
        http.perform(get("/finance/fund-analysis/currency-distribution")).andReturn().getResponse();
    assertThat(response.getStatus()).as("没有会话 ⇒ 401").isEqualTo(401);
  }
}
