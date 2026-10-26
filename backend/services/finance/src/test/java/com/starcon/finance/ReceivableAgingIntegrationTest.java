package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.ReceivableAgingModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.1.1.2 应收账龄的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.1.1.2 那几条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险」
 *       ⇒ 五档分桶正确、三个维度各自成立、集中度指向真正集中的那一维；
 *   <li>「**跨境应收的最大风险是平台争议冻结**，账龄报表需单独标识"争议中"金额」
 *       ⇒ 争议金额单列一栏，不混进总额；
 *   <li>「输入：应收单集合；**账龄区间配置**」 ⇒ 区间可配置、配置后立即生效、非法的配置被拒；
 *   <li>「输出：账龄分布报表 + **逾期清单**」 ⇒ 逾期清单只给真逾期，且汇总覆盖整个筛选结果集。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**，HTTP 层只断言**状态码**。
 *
 * <p><b>金额逐位相等</b>：JSON mapper 开了 {@code USE_BIG_DECIMAL_FOR_FLOATS}，
 * 否则 {@code 20.0000} 会被读成 {@code 20.0} —— 那样就分不出「金额是四位小数」和「金额丢了一位」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceivableAgingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID shopA = UUID.randomUUID();
  static final UUID shopB = UUID.randomUUID();
  static final UUID customer1 = UUID.randomUUID();
  static final UUID customer2 = UUID.randomUUID();
  static final UUID customer3 = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.receivable.read", "finance.receivable.create", "finance.receivable.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

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

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired ReceivableAgingService aging;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_receivable_event, finance_collection_task,"
              + " finance_receivable_line, finance_receivable, finance_receivable_policy,"
              + " finance_aging_bucket restart identity cascade");
    }
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行。 */
  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  /** 测试用的可信 actor。 */
  AccessService.Access actor() {
    return actors.get("session");
  }

  /** 账龄基准日（UTC 日历日）—— 与服务层用的是同一个口径。 */
  static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /**
   * 种一张应收单。
   *
   * <p>走**数据层**（DRAFT → 落明细 → OPEN）而不是服务层：这里要控制的是**到期日**与**核销金额**，
   * 而这两样在服务层是由政策账期与核销写路径决定的（核销属 12.2.1.2，未交付）。
   *
   * @param settled 行级已核销金额（null = 全额未核销）
   */
  void seed(
      String no,
      String currency,
      LocalDate dueDate,
      UUID counterpartyRef,
      String counterpartyName,
      UUID shopId,
      String shopName,
      boolean dispute,
      String amount,
      String settled) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var id = UUID.randomUUID();
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_ref,counterparty_name,shop_id,shop_name,currency,"
                  + " due_date,confirmed_at,status,dispute_flag,dispute_note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER',?,?,?,?,?,?::date,?::timestamptz,'DRAFT',"
                  + "?,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              counterpartyRef,
              counterpartyName,
              shopId,
              shopName,
              currency,
              dueDate,
              now,
              dispute,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
                  + " amount,settled_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'测试明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_receivable set status='OPEN' where id = ?", id);
          if (settled != null) {
            sql.execute(
                "update finance_receivable_line set settled_amount = ? where receivable_id = ?",
                new BigDecimal(settled),
                id);
          }
          return null;
        });
  }

  /** 种一张**草稿**应收单（没有明细行 ⇒ 永远进不了报表）。 */
  void seedDraft(String no, String currency, LocalDate dueDate) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER','草稿客户',?,?::date,?::timestamptz,"
                  + "'DRAFT',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              no,
              currency,
              dueDate,
              now,
              now,
              now);
          return null;
        });
  }

  /** 固定场景（每次用例都重种一遍）：
   *
   * <pre>
   *   单号   币种  到期日        店铺   客户    金额   已核销   争议
   *   R1     USD   今天+10       A      C1      100    0        否     未到期
   *   R2     USD   今天-10       A      C1      200    0        否     1-30
   *   R3     USD   今天-45       A      C2      300    0        否     31-60
   *   R4     USD   今天-75       B      C2      400    0        否     61-90
   *   R5     USD   今天-200      B      C3      500    0        否     90+
   *   R6     USD   今天-5        B      C3      1000   400      否     1-30（未核销余额 600）
   *   R7     USD   今天-20       A      C1      250    0        是     1-30（争议 250）
   *   R8     EUR   今天-3        A      C1      700    0        否     1-30
   *   R9     USD   今天+5        -      -       -      -        -      草稿（不进报表）
   *   R10    USD   今天-7        -      -       999    0        否     作废（不进报表）
   * </pre>
   */
  void seedScenario() {
    var t = today();
    seed("R1", "USD", t.plusDays(10), customer1, "客户一", shopA, "店铺 A", false, "100", null);
    seed("R2", "USD", t.minusDays(10), customer1, "客户一", shopA, "店铺 A", false, "200", null);
    seed("R3", "USD", t.minusDays(45), customer2, "客户二", shopA, "店铺 A", false, "300", null);
    seed("R4", "USD", t.minusDays(75), customer2, "客户二", shopB, "店铺 B", false, "400", null);
    seed("R5", "USD", t.minusDays(200), customer3, "客户三", shopB, "店铺 B", false, "500", null);
    seed("R6", "USD", t.minusDays(5), customer3, "客户三", shopB, "店铺 B", false, "1000", "400");
    seed("R7", "USD", t.minusDays(20), customer1, "客户一", shopA, "店铺 A", true, "250", null);
    seed("R8", "EUR", t.minusDays(3), customer1, "客户一", shopA, "店铺 A", false, "700", null);
    seedDraft("R9", "USD", t.plusDays(5));
    seed("R10", "USD", t.minusDays(7), customer1, "客户一", shopA, "店铺 A", false, "999", null);
    // 把 R10 作废（终态，不进报表）。
    as(
        () -> {
          sql.execute(
              "update finance_receivable set status='VOID' where receivable_no = 'R10'");
          return null;
        });
  }

  /** 跑一次分布查询。 */
  FinanceReceivableAgingDistribution distribution(
      String dimension, String currency, UUID shopId, UUID counterpartyRef, String bucket, Boolean disputeOnly) {
    return as(
        () ->
            aging.distribution(
                new ReceivableAgingQuery(currency, shopId, counterpartyRef, bucket, disputeOnly, dimension, 0, 50)));
  }

  /** 跑一次逾期清单查询。 */
  FinanceReceivableAgingOverduePage overdue(
      String currency, UUID shopId, UUID counterpartyRef, String bucket, Boolean disputeOnly, int page, int size) {
    return as(
        () ->
            aging.overdue(
                new ReceivableAgingQuery(currency, shopId, counterpartyRef, bucket, disputeOnly, null, page, size)));
  }

  /** 从分布的币种合计里取一行。 */
  FinanceAgingCurrencyTotal totalOf(FinanceReceivableAgingDistribution d, String currency) {
    return d.totals().stream()
        .filter(t -> t.currency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + currency + " 的合计行"));
  }

  // ================================================================ 分桶

  @Test
  void 五档分桶正确且按币种分开() {
    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, null);

    assertThat(d.asOfDate()).isEqualTo(today());
    assertThat(d.bucketSource()).isEqualTo("BLUEPRINT_DEFAULT");
    assertThat(d.totalCount()).as("草稿与作废的单都不进报表").isEqualTo(8);

    var usd = totalOf(d, "USD");
    assertThat(usd.openAmount()).isEqualByComparingTo("2350.0000");
    assertThat(usd.overdueAmount()).isEqualByComparingTo("2250.0000");
    assertThat(usd.count()).isEqualTo(7);

    var eur = totalOf(d, "EUR");
    assertThat(eur.openAmount()).isEqualByComparingTo("700.0000");
    assertThat(eur.count()).isEqualTo(1);

    // ⚠️ 不跨币种相加：两个币种各自一行，**没有**一个跨币种的合计行。
    assertThat(d.totals()).hasSize(2);

    // 五档各归其位。
    assertThat(usd.buckets())
        .extracting(FinanceAgingBucketAmount::code, FinanceAgingBucketAmount::amount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("NOT_DUE", new BigDecimal("100.0000")),
            org.assertj.core.groups.Tuple.tuple("D1_30", new BigDecimal("1050.0000")),
            org.assertj.core.groups.Tuple.tuple("D31_60", new BigDecimal("300.0000")),
            org.assertj.core.groups.Tuple.tuple("D61_90", new BigDecimal("400.0000")),
            org.assertj.core.groups.Tuple.tuple("D90_PLUS", new BigDecimal("500.0000")));
    assertThat(eur.buckets())
        .extracting(FinanceAgingBucketAmount::code, FinanceAgingBucketAmount::amount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("D1_30", new BigDecimal("700.0000")));
  }

  @Test
  void 统计的是未核销余额而不是单头全额() {
    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, null);
    // R6：单头 1000，已核销 400 ⇒ 账龄里只算 600。
    var usd = totalOf(d, "USD");
    assertThat(usd.openAmount()).isEqualByComparingTo("2350.0000");
    var d1 = usd.buckets().stream().filter(b -> b.code().equals("D1_30")).findFirst().orElseThrow();
    assertThat(d1.amount()).isEqualByComparingTo("1050.0000");
    assertThat(d1.count()).isEqualTo(3);
  }

  @Test
  void 金额是四位小数的BigDecimal而不是浮点() {
    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, null);
    var usd = totalOf(d, "USD");
    assertThat(usd.openAmount().scale()).isEqualTo(4);
    assertThat(usd.openAmount().toPlainString()).isEqualTo("2350.0000");
    // 占比也是定点数（比例，不是浮点）。
    assertThat(usd.overdueShareTop().scale()).isEqualTo(4);
  }

  // ================================================================ 争议单列

  @Test
  void 争议中金额单列不混进总额() {
    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, null);
    var usd = totalOf(d, "USD");
    // R7 是唯一一张争议中的单：250.0000。
    assertThat(usd.disputeAmount()).isEqualByComparingTo("250.0000");
    assertThat(usd.disputeOverdueAmount()).isEqualByComparingTo("250.0000");
    // 争议金额**在**未核销余额里（不是被扣掉的另一笔钱），但单独一列可查。
    assertThat(usd.openAmount()).isEqualByComparingTo("2350.0000");
  }

  @Test
  void 只看争议中时只给争议的单() {
    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, true);
    assertThat(d.totalCount()).isEqualTo(1);
    assertThat(totalOf(d, "USD").openAmount()).isEqualByComparingTo("250.0000");
  }

  // ================================================================ 维度下钻

  @Test
  void 三个维度各自成立() {
    seedScenario();

    var byShop = distribution("SHOP", null, null, null, null, null);
    assertThat(byShop.rows())
        .extracting(FinanceReceivableAgingRow::dimensionName)
        .containsExactlyInAnyOrder("店铺 A", "店铺 B", "店铺 A");
    var shopAUsd =
        byShop.rows().stream()
            .filter(r -> r.dimensionName().equals("店铺 A") && r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(shopAUsd.openAmount()).isEqualByComparingTo("850.0000");
    assertThat(shopAUsd.overdueAmount()).isEqualByComparingTo("750.0000");
    assertThat(shopAUsd.count()).isEqualTo(4);

    var byCustomer = distribution("COUNTERPARTY", null, null, null, null, null);
    assertThat(byCustomer.rows())
        .extracting(FinanceReceivableAgingRow::dimensionName)
        .containsExactlyInAnyOrder("客户一", "客户二", "客户三", "客户一");
    var customer1 =
        byCustomer.rows().stream()
            .filter(r -> r.dimensionName().equals("客户一") && r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(customer1.openAmount()).isEqualByComparingTo("550.0000");
    assertThat(customer1.count()).isEqualTo(3);

    var byCurrency = distribution("CURRENCY", null, null, null, null, null);
    assertThat(byCurrency.rows())
        .extracting(FinanceReceivableAgingRow::dimensionKey)
        .containsExactlyInAnyOrder("USD", "EUR");
  }

  @Test
  void 集中度指出逾期集中的那一维() {
    seedScenario();
    var d = distribution("SHOP", null, null, null, null, null);
    var usd = totalOf(d, "USD");
    // 店铺 B 的逾期 1500 / USD 逾期 2250 = 0.6667。
    assertThat(usd.topDimensionName()).isEqualTo("店铺 B");
    assertThat(usd.topDimensionKey()).isEqualTo(shopB.toString());
    assertThat(usd.overdueShareTop()).isEqualByComparingTo("0.6667");
    // 每行的占比也按同币种算（不跨币种）。
    var shopBUsd =
        d.rows().stream()
            .filter(r -> r.dimensionName().equals("店铺 B") && r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(shopBUsd.overdueShare()).isEqualByComparingTo("0.6667");
  }

  // ================================================================ 逾期清单

  @Test
  void 逾期清单只给真逾期且按逾期天数排序() {
    seedScenario();
    var page = overdue(null, null, null, null, null, 0, 50);
    assertThat(page.total()).isEqualTo(7);
    assertThat(page.items())
        .extracting(FinanceReceivableAgingOverdueRow::receivableNo)
        .containsExactly("R5", "R4", "R3", "R7", "R2", "R6", "R8");
    assertThat(page.items().get(0).overdueDays()).isEqualTo(200);
    assertThat(page.items().get(0).bucketCode()).isEqualTo("D90_PLUS");
    assertThat(page.items().get(0).bucketLabel()).isEqualTo("逾期 90 天以上");
    // 未到期与草稿、作废的单都不在清单里。
    assertThat(page.items())
        .extracting(FinanceReceivableAgingOverdueRow::receivableNo)
        .doesNotContain("R1", "R9", "R10");
  }

  @Test
  void 汇总覆盖整个筛选结果集而不是当前页() {
    seedScenario();
    var page = overdue(null, null, null, null, null, 0, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(7);
    // 汇总按币种分开，且覆盖**全部** 7 张（不是当前页的 2 张）。
    assertThat(page.summaryByCurrency())
        .extracting(FinanceAgingCurrencySummary::currency, FinanceAgingCurrencySummary::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("EUR", 1L),
            org.assertj.core.groups.Tuple.tuple("USD", 6L));
    var usd =
        page.summaryByCurrency().stream()
            .filter(s -> s.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.openAmount()).isEqualByComparingTo("2250.0000");
    assertThat(usd.disputeAmount()).isEqualByComparingTo("250.0000");
    assertThat(usd.disputeCount()).isEqualTo(1);
    assertThat(page.disputeCount()).isEqualTo(1);
  }

  @Test
  void 筛选是真筛选且汇总跟着变() {
    seedScenario();

    var usdOnly = overdue("USD", null, null, null, null, 0, 50);
    assertThat(usdOnly.total()).isEqualTo(6);
    assertThat(usdOnly.summaryByCurrency()).hasSize(1);

    var disputed = overdue(null, null, null, null, true, 0, 50);
    assertThat(disputed.total()).isEqualTo(1);
    assertThat(disputed.items().get(0).receivableNo()).isEqualTo("R7");
    assertThat(disputed.items().get(0).disputeFlag()).isTrue();

    var bucket = overdue(null, null, null, "D31_60", null, 0, 50);
    assertThat(bucket.total()).isEqualTo(1);
    assertThat(bucket.items().get(0).receivableNo()).isEqualTo("R3");

    // 店铺 A 的逾期单：R2(USD 10 天) / R3(USD 45 天) / R7(USD 20 天) / R8(EUR 3 天)。
    var shop = overdue(null, shopA, null, null, null, 0, 50);
    assertThat(shop.total()).isEqualTo(4);
    assertThat(shop.items())
        .extracting(FinanceReceivableAgingOverdueRow::receivableNo)
        .containsExactlyInAnyOrder("R2", "R3", "R7", "R8");

    var customer = overdue(null, null, customer3, null, null, 0, 50);
    assertThat(customer.total()).isEqualTo(2);
  }

  @Test
  void 逾期清单的翻页是真的翻页() {
    seedScenario();
    var first = overdue(null, null, null, null, null, 0, 3);
    var second = overdue(null, null, null, null, null, 1, 3);
    assertThat(first.items()).hasSize(3);
    assertThat(second.items()).hasSize(3);
    assertThat(first.items())
        .extracting(FinanceReceivableAgingOverdueRow::receivableNo)
        .doesNotContainAnyElementsOf(
            second.items().stream()
                .map(FinanceReceivableAgingOverdueRow::receivableNo)
                .toList());
  }

  // ================================================================ 账龄区间配置

  @Test
  void 没配过时生效的是蓝图五档且如实标出来() {
    var config = as(() -> aging.bucketConfig());
    assertThat(config.source()).isEqualTo("BLUEPRINT_DEFAULT");
    assertThat(config.configured()).isFalse();
    assertThat(config.buckets())
        .extracting(FinanceAgingBucketDef::code)
        .containsExactly("NOT_DUE", "D1_30", "D31_60", "D61_90", "D90_PLUS");
    assertThat(config.detail()).contains("蓝图");
  }

  @Test
  void 租户配置后立即生效且区间按配置分档() {
    as(
        () ->
            aging.updateBuckets(
                new FinanceAgingBucketConfigInput(
                    List.of(
                        new FinanceAgingBucketInput("NOT_DUE", "未到期", null, null, 0),
                        new FinanceAgingBucketInput("D1_15", "逾期 1-15 天", 1, 15, 1),
                        new FinanceAgingBucketInput("D16_45", "逾期 16-45 天", 16, 45, 2),
                        new FinanceAgingBucketInput("D46_PLUS", "逾期 46 天以上", 46, null, 3))),
                actor()));

    var config = as(() -> aging.bucketConfig());
    assertThat(config.source()).isEqualTo("TENANT_CONFIG");
    assertThat(config.configured()).isTrue();

    seedScenario();
    var d = distribution("CURRENCY", null, null, null, null, null);
    assertThat(d.bucketSource()).isEqualTo("TENANT_CONFIG");
    var usd = totalOf(d, "USD");
    // R1(未到期,100) / R2(10 天,200) / R7(20 天,250) / R6(5 天,600 未核销) / R3(45 天,300)
    // / R4(75 天,400) / R5(200 天,500)
    assertThat(usd.buckets())
        .extracting(FinanceAgingBucketAmount::code, FinanceAgingBucketAmount::amount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("NOT_DUE", new BigDecimal("100.0000")),
            org.assertj.core.groups.Tuple.tuple("D1_15", new BigDecimal("800.0000")),
            org.assertj.core.groups.Tuple.tuple("D16_45", new BigDecimal("550.0000")),
            org.assertj.core.groups.Tuple.tuple("D46_PLUS", new BigDecimal("900.0000")));
  }

  @Test
  void 传空列表回到蓝图五档() {
    as(
        () ->
            aging.updateBuckets(
                new FinanceAgingBucketConfigInput(
                    List.of(
                        new FinanceAgingBucketInput("NOT_DUE", "未到期", null, null, 0),
                        new FinanceAgingBucketInput("D1_PLUS", "逾期 1 天以上", 1, null, 1))),
                actor()));
    assertThat(as(() -> aging.bucketConfig()).source()).isEqualTo("TENANT_CONFIG");

    as(() -> aging.updateBuckets(new FinanceAgingBucketConfigInput(List.of()), actor()));
    var config = as(() -> aging.bucketConfig());
    assertThat(config.source()).isEqualTo("BLUEPRINT_DEFAULT");
    assertThat(config.buckets()).hasSize(5);
  }

  @Test
  void 非法的区间配置被拒绝() {
    // 留缝：1-30 之后直接跳到 61。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        aging.updateBuckets(
                            new FinanceAgingBucketConfigInput(
                                List.of(
                                    new FinanceAgingBucketInput("NOT_DUE", "未到期", null, null, 0),
                                    new FinanceAgingBucketInput("D1_30", "逾期 1-30 天", 1, 30, 1),
                                    new FinanceAgingBucketInput("D61_PLUS", "逾期 61 天以上", 61, null, 2))),
                            actor())))
        .hasStackTraceContaining("连续");
    // 缺少「未到期」档。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        aging.updateBuckets(
                            new FinanceAgingBucketConfigInput(
                                List.of(new FinanceAgingBucketInput("D1_PLUS", "逾期 1 天以上", 1, null, 0))),
                            actor())))
        .hasStackTraceContaining("未到期");
    // 没有无上界的那一档。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        aging.updateBuckets(
                            new FinanceAgingBucketConfigInput(
                                List.of(
                                    new FinanceAgingBucketInput("NOT_DUE", "未到期", null, null, 0),
                                    new FinanceAgingBucketInput("D1_30", "逾期 1-30 天", 1, 30, 1))),
                            actor())))
        .hasStackTraceContaining("上界");
    // 非法编码。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        aging.updateBuckets(
                            new FinanceAgingBucketConfigInput(
                                List.of(
                                    new FinanceAgingBucketInput("not due", "未到期", null, null, 0),
                                    new FinanceAgingBucketInput("D1_PLUS", "逾期 1 天以上", 1, null, 1))),
                            actor())))
        .hasStackTraceContaining("编码");
    // 一条都不许落库。
    assertThat(as(() -> sql.fetchOne("select count(*) from finance_aging_bucket")).get(0, Long.class))
        .isZero();
  }

  // ================================================================ 能力声明与时间

  @Test
  void 能力声明如实报出上游与下游() {
    var caps = as(() -> aging.capabilities());
    var self =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.1.1.2") && c.name().contains("应收账龄"))
            .findFirst()
            .orElseThrow();
    assertThat(self.available()).isTrue();
    assertThat(self.basis()).contains("finance_aging_bucket");

    // 下游 1.3 催收预警与 12.1.1.3 坏账核销**均未交付**，必须写明归属模块。
    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("1.3.2.1"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.available()).isFalse();
              assertThat(c.ownerModule()).isEqualTo("1.3.2.1");
            });
    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("12.1.1.3"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.available()).isFalse();
              assertThat(c.ownerModule()).isEqualTo("12.1.1.3");
            });
    assertThat(caps.downstreamGaps())
        .extracting(FinanceReceivableAgingDownstreamGap::module)
        .contains("1.3.2.1", "12.1.1.3", "12.2.1.2");
  }

  @Test
  void 写入的timestamptz零漂移() {
    as(
        () ->
            aging.updateBuckets(
                new FinanceAgingBucketConfigInput(
                    List.of(
                        new FinanceAgingBucketInput("NOT_DUE", "未到期", null, null, 0),
                        new FinanceAgingBucketInput("D1_PLUS", "逾期 1 天以上", 1, null, 1))),
                actor()));
    var row =
        one(
            "select created_at, updated_at from finance_aging_bucket where code = 'D1_PLUS'");
    var created = row.get("created_at", OffsetDateTime.class);
    var updated = row.get("updated_at", OffsetDateTime.class);
    // 写进去的是同一个时刻 ⇒ 读回来必须还是同一个时刻（没有 -28800s 那种静默偏移）。
    assertThat(created.toInstant()).isEqualTo(updated.toInstant());
    // 且确实是「刚刚」写的，不是被平移了八小时。
    assertThat(Duration.between(created.toInstant(), Instant.now()).abs())
        .as("写入的 timestamptz 必须落在当下附近")
        .isLessThan(Duration.ofMinutes(5));
  }

  // ================================================================ HTTP 层

  @Test
  void 没有身份就拿不到报表() throws Exception {
    http.perform(get("/finance/receivable-aging/distribution")).andExpect(status().isUnauthorized());
    http.perform(get("/finance/receivable-aging/overdue")).andExpect(status().isUnauthorized());
    http.perform(get("/finance/receivable-aging/buckets")).andExpect(status().isUnauthorized());
  }

  @Test
  void 非法配置在http层也是400() throws Exception {
    String body =
        """
        {"buckets":[{"code":"NOT_DUE","label":"未到期","overdueFromDays":null,"overdueToDays":null,"sortOrder":0},
                    {"code":"D1_30","label":"逾期 1-30 天","overdueFromDays":1,"overdueToDays":30,"sortOrder":1}]}
        """;
    http.perform(
            put("/finance/receivable-aging/buckets")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 报表端点真的返回数据() throws Exception {
    seedScenario();
    var response =
        http.perform(get("/finance/receivable-aging/distribution").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readValue(response.getContentAsString(), FinanceReceivableAgingDistribution.class);
    assertThat(body.totalCount()).isEqualTo(8);
    assertThat(body.totals()).hasSize(2);
  }
}
