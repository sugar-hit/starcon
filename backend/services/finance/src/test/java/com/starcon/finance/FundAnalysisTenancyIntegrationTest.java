package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
 * 12.7.2.1 资金分析的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的表与端点上</b>：断言直接跑在
 * {@code finance_liquidity_threshold} / {@code finance_fund_exposure_snapshot}
 * 与本模块的读端点上，证明的是**真的要用的那两张表**被隔离了，
 * 不只是「上游表被隔离了」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FundAnalysisTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-fund-analysis-" + tenant + "-" + ordinal).getBytes());
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

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions = List.of("finance.receivable.read", "finance.receivable.update");
    actors.put("session-a", actor(UUID.randomUUID(), a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, permissions));
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

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            byte[] body =
                ("{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"CNY\",\"rate\":7,"
                        + "\"effectiveDate\":\"2026-01-01\",\"sourceCode\":\"TEST\","
                        + "\"display\":\"1 USD = 7 CNY\"}")
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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /**
   * 每个用例前清空业务表，再种入两租户各一条阈值配置与一张收款单。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5 <b>不保证</b>它们的执行顺序。
   */
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
    insertThreshold(a, idFor(a, 1), "PAYMENT_CONCENTRATION", "A 的集中度阈值");
    insertThreshold(b, idFor(b, 1), "PAYMENT_CONCENTRATION", "B 的集中度阈值");
    insertReceipt(a, idFor(a, 2), "tenant-a-receipt", "USD", "100");
    insertReceipt(b, idFor(b, 2), "tenant-b-receipt", "EUR", "500");
  }

  /** 在可信租户事务里插一条阈值配置。 */
  void insertThreshold(UUID tenant, UUID id, String code, String label) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_liquidity_threshold(id,tenant_id,code,label,threshold_value,"
                  + " enabled,note,version,created_at,updated_at)"
                  + " values(?,?,?,?,0.4,true,'',0,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              code,
              label,
              now,
              now);
          return null;
        });
  }

  /** 在可信租户事务里插一张收款单。 */
  void insertReceipt(UUID tenant, UUID id, String no, String currency, String amount) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,currency,"
                  + " amount,settled_amount,advance_flag,status,received_at,payer_type,payer_name,"
                  + " fund_account_label,note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual',?,?,0,false,'RECORDED',?::timestamptz,'CUSTOMER',"
                  + " '客户','','',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              currency,
              new BigDecimal(amount),
              now,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读阈值配置的名称。 */
  List<String> thresholdLabelsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select label from finance_liquidity_threshold order by label")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读收款单号。 */
  List<String> receiptNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select receipt_no from finance_receipt order by receipt_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读敞口快照的币种。 */
  List<String> snapshotCurrenciesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select currency from finance_fund_exposure_snapshot order by currency")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select label from finance_liquidity_threshold").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的两张表强制rls而不是只启用() {
    for (String table : List.of("finance_liquidity_threshold", "finance_fund_exposure_snapshot")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V347 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(thresholdLabelsOf(a)).containsExactly("A 的集中度阈值");
    assertThat(thresholdLabelsOf(b)).containsExactly("B 的集中度阈值");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select label from finance_liquidity_threshold where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 上游表同样隔离（资金分布读的是它们）。
    assertThat(receiptNosOf(a)).containsExactly("tenant-a-receipt");
    assertThat(receiptNosOf(b)).containsExactly("tenant-b-receipt");
  }

  @Test
  void 断言1_报表端点只看得到本租户的数据() throws Exception {
    var aDistribution =
        json.readValue(
            http.perform(
                    get("/finance/fund-analysis/currency-distribution")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundCurrencyDistribution.class);
    assertThat(aDistribution.positions())
        .extracting(FinanceFundCurrencyPosition::currency)
        .containsExactly("USD");

    var bDistribution =
        json.readValue(
            http.perform(
                    get("/finance/fund-analysis/currency-distribution")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundCurrencyDistribution.class);
    assertThat(bDistribution.positions())
        .extracting(FinanceFundCurrencyPosition::currency)
        .containsExactly("EUR");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_liquidity_threshold(id,tenant_id,code,label,threshold_value,"
                        + " enabled,note,version,created_at,updated_at)"
                        + " values(?,?,'FORGED','伪造',0.4,true,'',0,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select label from finance_liquidity_threshold where label = '伪造'").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    String body =
        """
        {"thresholds":[
          {"code":"PAYMENT_CONCENTRATION","label":"A 改过的阈值","thresholdValue":0.9,"enabled":true,"note":""},
          {"code":"NET_OUTFLOW_GAP","label":"净流出缺口","thresholdValue":1,"enabled":true,"note":""},
          {"code":"RECEIVABLE_OVERDUE","label":"应收逾期","thresholdValue":0.3,"enabled":true,"note":""},
          {"code":"CASH_BUFFER_DAYS","label":"现金覆盖天数","thresholdValue":7,"enabled":true,"note":""}
        ]}
        """;

    http.perform(
            put("/finance/fund-analysis/thresholds")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select code, tenant_id from finance_liquidity_threshold order by code"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(4);
    // ...且它们落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它们（它们不属于 b），并且 b 的配置仍然是 b 自己的口径。
    assertThat(thresholdLabelsOf(b)).containsExactly("B 的集中度阈值");
    var bConfig =
        json.readValue(
            http.perform(
                    get("/finance/fund-analysis/thresholds").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundLiquidityThresholdConfig.class);
    assertThat(bConfig.source()).as("b 只有一条自己的配置 ⇒ 其余仍是蓝图默认档").isEqualTo("TENANT_CONFIG");
    assertThat(bConfig.thresholds())
        .filteredOn(t -> t.code().equals("PAYMENT_CONCENTRATION"))
        .first()
        .satisfies(
            t ->
                assertThat(t.thresholdValue())
                    .as("b 的集中度阈值必须还是 b 自己的 0.4")
                    .isEqualByComparingTo("0.4"));
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的资金() throws Exception {
    var aDistribution =
        json.readValue(
            http.perform(
                    get("/finance/fund-analysis/currency-distribution")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundCurrencyDistribution.class);
    // A 只看得到自己的 USD 那一笔。
    assertThat(aDistribution.positions())
        .extracting(FinanceFundCurrencyPosition::currency)
        .containsExactly("USD");
  }

  @Test
  void 断言2_敞口快照也按租户隔离() {
    // A 与 B 各自测一次：A 的快照只有 A 能看见。
    tenants.run(
        a,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_fund_exposure_snapshot(id,tenant_id,snapshot_no,as_of_date,"
                  + " base_currency,currency,direction,receivable_open_amount,payable_open_amount,"
                  + " net_exposure_amount,exchange_rate,rate_effective_date,base_exposure_amount,"
                  + " sensitivity_per_1pct,sensitivity_per_5pct,note,created_at)"
                  + " values(?,?,'EXPO-A','2026-01-01','CNY','USD','LONG_ASSET',0,0,0,7,"
                  + " '2026-01-01',0,0,0,'',?::timestamptz)",
              idFor(a, 9),
              a,
              now);
          return null;
        });
    assertThat(snapshotCurrenciesOf(a)).containsExactly("USD");
    assertThat(snapshotCurrenciesOf(b)).isEmpty();
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(thresholdLabelsOf(a)).as("第 %d 轮租户 A", i).containsExactly("A 的集中度阈值");
      assertThat(thresholdLabelsOf(b)).as("第 %d 轮租户 B", i).containsExactly("B 的集中度阈值");
      assertThat(receiptNosOf(a)).as("第 %d 轮 A 的收款单", i).containsExactly("tenant-a-receipt");
      assertThat(receiptNosOf(b)).as("第 %d 轮 B 的收款单", i).containsExactly("tenant-b-receipt");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(thresholdLabelsOf(a)).containsExactly("A 的集中度阈值");
    assertThat(thresholdLabelsOf(b)).containsExactly("B 的集中度阈值");
  }
}
