package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * 12.3.1.3 延迟结算的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.3.1.3 那几条：
 *
 * <ul>
 *   <li>「管理平台延迟结算（部分平台按周期后结算或滚动结算）」⇒ 两种模式都真的算出结算周期区间；
 *   <li>「**单列在途未结算金额**」⇒ 在途未结算金额是**派生**的，服务层写不进去；
 *   <li>「**结算条件**」⇒ 规则快照随行落库，明细自解释；
 *   <li>「**预计到账时间**」⇒ 由 DB 触发器派生，**写回的值逐位相等**；
 *   <li>「支持查询「**订单已发货但钱未到账**」的明细」⇒ 状态由「有没有出现在已到账结算单里」判定；
 *   <li>「输出：…**在途资金统计**」⇒ 两套口径（订单口径 / 已结算口径）**分币种**同时给出、**绝不相加**；
 *   <li>「关系：下游 = 13 利润（按已结算/未结算区分口径）」⇒ 口径定义随响应返回。
 * </ul>
 *
 * <p><b>上游 trade 用本地 HttpServer 桩</b>（与本仓其它模块同一手法）：平台订单与发货记录
 * 都经**授权 HTTP 只读端口**取，跨服务不直连对方库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeferredSettlementIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  /** 上游 trade 的平台订单（固定 id，测试里好引用）。 */
  static final UUID PLATFORM_ORDER_ID = UUID.randomUUID();

  /** 上游 trade 的销售订单。 */
  static final UUID SALES_ORDER_ID = UUID.randomUUID();

  /** 发货时间：刻意带时分秒（08:30Z），才能证明没有被截成 00:00 或偏 8 小时。 */
  static final OffsetDateTime SHIPPED_AT = OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.UTC);

  static final String ORDER_NO = "SO-2026-0001";
  static final String EXTERNAL_ORDER_NO = "AMZ-2026-0001";

  static final List<String> PERMISSIONS =
      List.of(
          "finance.deferred_settlement.read",
          "finance.deferred_settlement.create",
          "finance.deferred_settlement.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** trade 桩的响应体（每个用例改自己要的）。 */
  static final AtomicReference<String> ORDERS_JSON = new AtomicReference<>("{\"rows\":[],\"total\":0}");

  static final AtomicReference<String> SHIPMENTS_JSON =
      new AtomicReference<>("{\"items\":[],\"totalElements\":0}");

  /** 置 true 时 trade 一律 500 —— 用来证明「读不到」是 503 而不是空集合。 */
  static final AtomicBoolean TRADE_DOWN = new AtomicBoolean(false);

  static HttpServer identity;
  static HttpServer trade;

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

      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/platform-orders/orders",
          e -> {
            if (TRADE_DOWN.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            if (actors.get(e.getRequestHeaders().getFirst("Authorization")) == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = ORDERS_JSON.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.createContext(
          "/trade/fulfillment/shipment-records/records",
          e -> {
            if (TRADE_DOWN.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            if (actors.get(e.getRequestHeaders().getFirst("Authorization")) == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = SHIPMENTS_JSON.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
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
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired DeferredSettlementService deferredSettlements;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    TRADE_DOWN.set(false);
    ORDERS_JSON.set("{\"rows\":[],\"total\":0}");
    SHIPMENTS_JSON.set("{\"items\":[],\"totalElements\":0}");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_deferred_settlement_event, finance_deferred_settlement,"
              + " finance_deferred_settlement_rule, finance_settlement_event, finance_settlement_summary,"
              + " finance_settlement_line, finance_settlement, finance_settlement_source_file,"
              + " finance_settlement_cycle restart identity cascade");
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

  /** 在**租户事务里**计数。 */
  long count(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds).get(0, Long.class));
  }

  /** 统一到 UTC 再比：jOOQ 读 timestamptz 时用的是 JVM 本地偏移，直接比 OffsetDateTime 会误判。 */
  static OffsetDateTime utc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** 登记一条平台级默认规则（店铺级规则优先于它）。 */
  void platformRule(String mode, int cycleDays, int lagDays) throws Exception {
    http.perform(
            post("/finance/deferred-settlement/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"platformCode":"amazon","settlementMode":"%s","cycleDays":%d,"payoutLagDays":%d,
                     "conditionNote":"发货后进入结算周期，周期结束后到账"}
                    """
                        .formatted(mode, cycleDays, lagDays)))
        .andExpect(status().isOk());
  }

  /** trade 桩：一笔平台订单 + 一条发货记录（已发货）。 */
  void tradeOneShippedOrder() throws Exception {
    ORDERS_JSON.set(
        """
        {"rows":[{"id":"%s","salesOrderId":"%s","orderNo":"%s","platformCode":"amazon","shopCode":"SHOP-1",
          "externalOrderNo":"%s","normalizedStatus":"IN_FULFILLMENT","currency":"USD",
          "platformGrandTotalAmount":100.0000,"platformPlacedAt":"2026-09-30T10:00:00Z"}],
         "page":0,"size":200,"total":1}
        """
            .formatted(PLATFORM_ORDER_ID, SALES_ORDER_ID, ORDER_NO, EXTERNAL_ORDER_NO));
    SHIPMENTS_JSON.set(
        """
        {"items":[{"id":"%s","recordNo":"SR-1","salesOrderId":"%s","salesOrderNo":"%s",
          "shopRef":"amazon","shippedAt":"2026-10-01T08:30:00Z","status":"SHIPPED"}],
         "page":0,"size":200,"totalElements":1,"totalPages":1}
        """
            .formatted(UUID.randomUUID(), SALES_ORDER_ID, ORDER_NO));
  }

  /** 造一张 12.3.1.1 的结算单 + 一条带订单号的销售额行（**未到账**：status=PENDING）。 */
  UUID settlementWithOrderLine(String orderReference, BigDecimal amount) {
    var settlementId = UUID.randomUUID();
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_settlement"
                  + "(id,tenant_id,settlement_no,shop_id,shop_code,platform_code,period_start,period_end,"
                  + " currency,settlement_amount,status,received_amount,source,source_system,source_reference,"
                  + " parse_version,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,'amazon',?::date,?::date,'USD',0,'PENDING',0,'MANUAL','manual',?,1,0,"
                  + "?::timestamptz,?::timestamptz)",
              settlementId,
              tenant,
              "SETTLE-" + settlementId.toString().substring(0, 8),
              UUID.randomUUID(),
              "SHOP-1",
              LocalDate.of(2026, 10, 1),
              LocalDate.of(2026, 10, 15),
              "ref-" + settlementId,
              now,
              now);
          sql.execute(
              "insert into finance_settlement_line"
                  + "(id,tenant_id,settlement_id,line_no,category,amount,raw_amount,currency,order_reference,"
                  + " description,created_at)"
                  + " values(?,?,?,1,'SALES',?,?,'USD',?,'订单结算行',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              settlementId,
              amount,
              amount,
              orderReference,
              now);
          return null;
        });
    return settlementId;
  }

  /** 把结算单推进到「已到账」（12.3.1.1 的登记到账路径）。 */
  void markSettlementArrived(UUID settlementId, BigDecimal received) {
    as(
        () -> {
          sql.execute(
              "update finance_settlement set status = 'SETTLED', received_amount = ?,"
                  + " arrived_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
              received,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              settlementId);
          return null;
        });
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 迁移落地且三张表强制rls() throws Exception {
    for (String table :
        List.of(
            "finance_deferred_settlement_rule",
            "finance_deferred_settlement",
            "finance_deferred_settlement_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V353 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住运行角色被误授 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
    // 事件流是 append-only：运行角色不许改、不许删。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_deferred_settlement_event','UPDATE')")
                .get(0, Boolean.class))
        .as("事件流只能追加")
        .isFalse();
  }

  // ==========================================================================
  // 结算规则
  // ==========================================================================
  @Test
  void 结算规则登记后真的落库() throws Exception {
    platformRule("PERIODIC", 7, 3);
    var rules = as(() -> deferredSettlements.rules());
    assertThat(rules).hasSize(1);
    var rule = rules.get(0);
    assertThat(rule.platformCode()).isEqualTo("amazon");
    assertThat(rule.shopCode()).as("没填店铺就是平台级默认规则").isNull();
    assertThat(rule.cycleDays()).isEqualTo(7);
    assertThat(rule.payoutLagDays()).isEqualTo(3);
    assertThat(rule.conditionNote()).contains("结算周期");
    assertThat(rule.status()).isEqualTo("ACTIVE");
  }

  @Test
  void 店铺级规则与平台级规则可以并存() throws Exception {
    platformRule("PERIODIC", 7, 3);
    http.perform(
            post("/finance/deferred-settlement/rules")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"platformCode":"amazon","shopCode":"SHOP-1","settlementMode":"ROLLING",
                     "cycleDays":15,"payoutLagDays":1,"conditionNote":"滚动结算"}
                    """))
        .andExpect(status().isOk());
    var rules = as(() -> deferredSettlements.rules());
    assertThat(rules).hasSize(2);
    assertThat(rules.stream().filter(r -> r.shopCode() != null).count()).isEqualTo(1);
  }

  // ==========================================================================
  // 扫描入台账
  // ==========================================================================
  @Test
  void 没有结算规则时扫描不进货并说明原因() throws Exception {
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skipped").value(1))
        .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("结算规则")));
    assertThat(count("select count(*) from finance_deferred_settlement")).isZero();
  }

  @Test
  void 扫描把已发货订单写进台账() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fetchedOrders").value(1))
        .andExpect(jsonPath("$.fetchedShipments").value(1))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.skipped").value(0));
    assertThat(count("select count(*) from finance_deferred_settlement")).isEqualTo(1);
    assertThat(count("select count(*) from finance_deferred_settlement_event")).isEqualTo(1);
  }

  @Test
  void 上游读不到时返回503而不是空集合() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    TRADE_DOWN.set(true);
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
    assertThat(count("select count(*) from finance_deferred_settlement")).isZero();
  }

  // ==========================================================================
  // 核心三个时间字段：发货时间 / 结算周期区间 / 预计到账时间
  // ==========================================================================
  @Test
  void 周期后结算的时间字段真的落库且值逐位相等() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var row = one("select * from finance_deferred_settlement");
    // 发货时间：逐位相等（偏 8 小时就是错账）。
    assertThat(utc(row.get("shipped_at", OffsetDateTime.class)))
        .as("发货时间必须原样落库")
        .isEqualTo(SHIPPED_AT);
    // 结算周期区间：发货日 00:00Z 起，7 天后结束。
    assertThat(utc(row.get("settlement_cycle_start", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(utc(row.get("settlement_cycle_end", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 10, 8, 0, 0, 0, 0, ZoneOffset.UTC));
    // 预计到账时间 = 周期结束 + 到账偏移 3 天。
    assertThat(utc(row.get("expected_arrived_at", OffsetDateTime.class)))
        .as("预计到账时间必须由触发器派生并落库")
        .isEqualTo(OffsetDateTime.of(2026, 10, 11, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void 滚动结算的周期区间与预计到账时间也逐位相等() throws Exception {
    platformRule("ROLLING", 15, 1);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var row = one("select * from finance_deferred_settlement");
    assertThat(utc(row.get("shipped_at", OffsetDateTime.class))).isEqualTo(SHIPPED_AT);
    // 2026-10-01 的 epoch 日 = 20727；20727 / 15 = 1381，下一个边界 = 1382 × 15 = 20730 → 2026-10-04。
    assertThat(utc(row.get("settlement_cycle_end", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 10, 4, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(utc(row.get("settlement_cycle_start", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 9, 19, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(utc(row.get("expected_arrived_at", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void 服务层写不进预计到账时间() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());
    var id = one("select id from finance_deferred_settlement").get(0, UUID.class);

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_deferred_settlement set expected_arrived_at = ?::timestamptz"
                              + " where id = ?",
                          OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                          id);
                      return null;
                    }))
        .as("预计到账时间是派生量：直连 SQL 也写不进去（触发器拒绝）")
        .hasStackTraceContaining("预计到账时间");

    var row = one("select expected_arrived_at from finance_deferred_settlement where id = ?", id);
    assertThat(utc(row.get(0, OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.of(2026, 10, 11, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  // ==========================================================================
  // 两套口径（在途未结算金额 / 已结算口径金额）
  // ==========================================================================
  @Test
  void 未到账时在途未结算金额等于订单口径金额() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var summary =
        as(
            () ->
                deferredSettlements.summary(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(summary.summaryByCurrency()).hasSize(1);
    var usd = summary.summaryByCurrency().get(0);
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(usd.orderBasisAmount()).isEqualByComparingTo("100.0000");
    assertThat(usd.settledBasisAmount()).isEqualByComparingTo("0.0000");
    assertThat(usd.inTransitAmount()).as("钱没到账 ⇒ 在途未结算金额 = 订单口径金额").isEqualByComparingTo("100.0000");
    assertThat(usd.basisGapAmount()).isEqualByComparingTo("0.0000");
    assertThat(usd.pendingCount()).isEqualTo(1);
    assertThat(usd.settledCount()).isZero();
  }

  @Test
  void 结算单到账后已结算口径与在途未结算金额自动跟着变() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    // 结算单**还没到账**（status=PENDING）：钱仍未到账，在途金额不变。
    var settlementId = settlementWithOrderLine(EXTERNAL_ORDER_NO, new BigDecimal("100.0000"));
    var before = one("select settled_amount, unsettled_amount, status from finance_deferred_settlement");
    assertThat(before.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(before.get("unsettled_amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(before.get("status", String.class)).isEqualTo("PENDING");

    // 结算单**到账**（12.3.1.1 的登记到账）：派生链自动把口径改过来。
    markSettlementArrived(settlementId, new BigDecimal("100.0000"));
    var after = one("select settled_amount, unsettled_amount, status, settlement_id from finance_deferred_settlement");
    assertThat(after.get("settled_amount", BigDecimal.class))
        .as("已结算口径金额由结算明细行派生")
        .isEqualByComparingTo("100.0000");
    assertThat(after.get("unsettled_amount", BigDecimal.class))
        .as("钱到账了 ⇒ 在途未结算金额归零")
        .isEqualByComparingTo("0.0000");
    assertThat(after.get("status", String.class)).isEqualTo("SETTLED");
    assertThat(after.get("settlement_id", UUID.class)).isEqualTo(settlementId);
  }

  @Test
  void 已结算口径金额写不进去() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());
    var id = one("select id from finance_deferred_settlement").get(0, UUID.class);

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_deferred_settlement set settled_amount = 999 where id = ?", id);
                      return null;
                    }))
        .as("已结算口径金额由结算明细行派生，服务层与直连 SQL 都写不进去")
        .hasStackTraceContaining("已结算口径金额");
  }

  @Test
  void 两套口径随响应返回且不跨币种相加() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    http.perform(get("/finance/deferred-settlement/summary").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basis.orderBasis").value(org.hamcrest.Matchers.containsString("订单口径")))
        .andExpect(jsonPath("$.basis.settledBasis").value(org.hamcrest.Matchers.containsString("已结算口径")))
        .andExpect(jsonPath("$.basis.currencyPolicy").value(org.hamcrest.Matchers.containsString("绝不跨币种相加")))
        .andExpect(jsonPath("$.summaryByCurrency[0].currency").value("USD"))
        .andExpect(jsonPath("$.summaryByCurrency[0].orderBasisAmount").value(100.0000))
        .andExpect(jsonPath("$.summaryByCurrency[0].inTransitAmount").value(100.0000));
  }

  // ==========================================================================
  // 查询：真分页 / 真筛选 / 真排序
  // ==========================================================================
  @Test
  void 列表是真分页且汇总覆盖整个筛选集() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var page =
        as(
            () ->
                deferredSettlements.page(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        null, null, null, null, null, null, null, null, 0, 1)));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.summaryByCurrency()).hasSize(1);
    assertThat(page.items().get(0).orderNo()).isEqualTo(ORDER_NO);
    assertThat(page.items().get(0).externalOrderNo()).isEqualTo(EXTERNAL_ORDER_NO);
    assertThat(page.items().get(0).shippedAt()).isNotNull();
    assertThat(page.items().get(0).expectedArrivedAt()).isNotNull();
  }

  @Test
  void 关键词筛选真的生效() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var hit =
        as(
            () ->
                deferredSettlements.page(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        null, null, null, null, null, null, null, "AMZ-2026", 0, 50)));
    assertThat(hit.total()).isEqualTo(1);
    var miss =
        as(
            () ->
                deferredSettlements.page(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        null, null, null, null, null, null, null, "不存在的单号", 0, 50)));
    assertThat(miss.total()).isZero();
  }

  @Test
  void 只查在途未结算时能看到已发货未到账的明细() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());

    var pending =
        as(
            () ->
                deferredSettlements.page(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        DeferredSettlementModels.DeferredSettlementStatus.PENDING,
                        null, null, null, null, null, null, null, 0, 50)));
    assertThat(pending.total()).isEqualTo(1);
    assertThat(pending.items().get(0).statusLabel()).isEqualTo("在途未结算");
  }

  // ==========================================================================
  // 作废
  // ==========================================================================
  @Test
  void 作废必须写明理由() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());
    var id = one("select id from finance_deferred_settlement").get(0, UUID.class);

    http.perform(
            post("/finance/deferred-settlement/" + id + "/void")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 作废后不进任何口径() throws Exception {
    platformRule("PERIODIC", 7, 3);
    tradeOneShippedOrder();
    http.perform(post("/finance/deferred-settlement/scan").header("Authorization", "session"))
        .andExpect(status().isOk());
    var id = one("select id from finance_deferred_settlement").get(0, UUID.class);

    http.perform(
            post("/finance/deferred-settlement/" + id + "/void")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"平台撤单\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOID"));

    var summary =
        as(
            () ->
                deferredSettlements.summary(
                    new DeferredSettlementModels.DeferredSettlementQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(summary.summaryByCurrency()).as("作废的行不进资金口径").isEmpty();
    assertThat(count("select count(*) from finance_deferred_settlement_event where event_type = 'VOIDED'"))
        .isEqualTo(1);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明把未交付的下游说清楚() throws Exception {
    var caps = deferredSettlements.capabilities();
    assertThat(caps.capabilities()).isNotEmpty();
    assertThat(caps.capabilities().stream().anyMatch(c -> "12.3.1.3".equals(c.module()))).isTrue();
    assertThat(caps.downstreamGaps().stream().anyMatch(g -> "13.1.1.1".equals(g.module()))).isTrue();
    assertThat(caps.downstreamGaps().stream().anyMatch(g -> "12.3.2.1".equals(g.module()))).isTrue();
  }
}
