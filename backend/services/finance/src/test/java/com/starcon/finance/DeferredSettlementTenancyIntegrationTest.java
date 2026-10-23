package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
 * finance 服务的**租户隔离**证明（12.3.1.3 延迟结算这一段），跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的业务表上</b>：隔离断言直接跑在 {@code finance_deferred_settlement} /
 * {@code finance_deferred_settlement_rule} / {@code finance_deferred_settlement_event} 上 ——
 * 证明的是**本模块真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeferredSettlementTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();

  /** 上游 trade 的平台订单（固定 id，测试里好引用）。 */
  static final UUID PLATFORM_ORDER_ID = UUID.randomUUID();
  static final UUID SALES_ORDER_ID = UUID.randomUUID();

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** trade 桩的响应体。 */
  static final AtomicReference<String> ORDERS_JSON = new AtomicReference<>("{\"rows\":[],\"total\":0}");

  static final AtomicReference<String> SHIPMENTS_JSON =
      new AtomicReference<>("{\"items\":[],\"totalElements\":0}");

  static HttpServer identity;
  static HttpServer trade;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-deferred-settlement-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    actors.put(
        "session-a",
        actor(
            userA,
            a,
            List.of(
                "finance.deferred_settlement.read",
                "finance.deferred_settlement.create",
                "finance.deferred_settlement.update")));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 在可信租户事务里插入一条延迟结算台账行（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离。
   * 派生列（结算周期区间 / 预计到账时间 / 已结算口径金额）由 V353 的触发器算，插入时传 null。
   */
  void insertDeferred(UUID tenant, UUID id, UUID ruleId, UUID platformOrderId, String orderNo) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_deferred_settlement_rule"
                  + "(id,tenant_id,platform_code,shop_code,shop_id,settlement_mode,cycle_days,payout_lag_days,"
                  + " currency,condition_note,status,created_at,updated_at)"
                  + " values(?,?,'amazon','SHOP-1',null,'PERIODIC',7,3,null,'','ACTIVE',"
                  + "?::timestamptz,?::timestamptz)",
              ruleId,
              tenant,
              now,
              now);
          sql.execute(
              "insert into finance_deferred_settlement"
                  + "(id,tenant_id,rule_id,platform_order_id,sales_order_id,order_no,external_order_no,"
                  + " platform_code,shop_code,currency,order_amount,shipped_at,shipment_status,"
                  + " settlement_mode,cycle_days,payout_lag_days,settlement_cycle_start,settlement_cycle_end,"
                  + " expected_arrived_at,condition_note,source,scanned_at,created_at,updated_at)"
                  + " values(?,?,?,?,null,?,?,'amazon','SHOP-1','USD',100.0000,?::timestamptz,'SHIPPED',"
                  + " 'PERIODIC',7,3,null,null,null,'','TRADE_SCAN',?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              ruleId,
              platformOrderId,
              orderNo,
              orderNo + "-EXT",
              OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.UTC),
              now,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读台账行号。 */
  List<String> orderNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select order_no from finance_deferred_settlement order by order_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select order_no from finance_deferred_settlement").size();
  }

  /**
   * 用**应用账号**直连数据库，按主键查某一行能看见几行。
   *
   * <p>⚠️ 这一格不能省：用迁移用户（超管）直连查会**绕过** FORCE RLS，让断言变成空断言 ——
   * 必须用运行角色（{@code finance_app}，nobypassrls）才能证明策略真的在挡。
   */
  int visibleToAppRole(UUID tenant, UUID rowId) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "finance_app", "test-only")) {
      try (var s = c.createStatement()) {
        s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      }
      try (var ps =
          c.prepareStatement("select count(*) from finance_deferred_settlement where id = ?")) {
        ps.setObject(1, rowId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    }
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一行。
   *
   * <p>清空与播种必须在同一个方法里：写成两个 {@code @BeforeEach} 时，JUnit 5
   * <b>不保证</b>它们的执行顺序。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
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
    insertDeferred(a, idFor(a, 1), idFor(a, 2), idFor(a, 3), "tenant-a-order");
    insertDeferred(b, idFor(b, 1), idFor(b, 2), idFor(b, 3), "tenant-b-order");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() throws Exception {
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
  void 断言1_两租户互不可见() throws Exception {
    assertThat(orderNosOf(a)).containsExactly("tenant-a-order");
    assertThat(orderNosOf(b)).containsExactly("tenant-b-order");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select order_no from finance_deferred_settlement where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 用**应用账号**直连数据库查也一样查不到（不能靠超管绕过 FORCE RLS 让断言空转）。
    assertThat(visibleToAppRole(a, idFor(b, 1))).as("应用账号按主键查对方的行也必须查不到").isZero();
    assertThat(visibleToAppRole(a, idFor(a, 1))).as("应用账号能查到自己的行").isEqualTo(1);
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_deferred_settlement_rule"
                        + "(id,tenant_id,platform_code,shop_code,settlement_mode,cycle_days,payout_lag_days,"
                        + " condition_note,status,created_at,updated_at)"
                        + " values(?,?,'amazon','SHOP-X','PERIODIC',7,3,'','ACTIVE',"
                        + "?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(visibleWithoutTenant()).isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // ① 登记结算规则（走服务层 ⇒ 真走鉴权 + 事务 + RLS）。
    http.perform(
            post("/finance/deferred-settlement/rules")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"platformCode":"ebay","shopCode":"SHOP-FORGED","settlementMode":"PERIODIC",
                     "cycleDays":7,"payoutLagDays":3,"conditionNote":"伪造头的写入"}
                    """))
        .andExpect(status().isOk());

    // ② 扫描入台账（trade 桩返回一笔已发货订单）。
    ORDERS_JSON.set(
        """
        {"rows":[{"id":"%s","salesOrderId":"%s","orderNo":"SO-FORGED","platformCode":"amazon",
          "shopCode":"SHOP-1","externalOrderNo":"EXT-FORGED","normalizedStatus":"IN_FULFILLMENT",
          "currency":"USD","platformGrandTotalAmount":100.0000,"platformPlacedAt":"2026-09-30T10:00:00Z"}],
         "page":0,"size":200,"total":1}
        """
            .formatted(PLATFORM_ORDER_ID, SALES_ORDER_ID));
    SHIPMENTS_JSON.set(
        """
        {"items":[{"id":"%s","recordNo":"SR-1","salesOrderId":"%s","salesOrderNo":"SO-FORGED",
          "shopRef":"amazon","shippedAt":"2026-10-01T08:30:00Z","status":"SHIPPED"}],
         "page":0,"size":200,"totalElements":1,"totalPages":1}
        """
            .formatted(UUID.randomUUID(), SALES_ORDER_ID));
    http.perform(
            post("/finance/deferred-settlement/scan")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged"))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    // 读取**必须在某个租户事务里**做：事务外没有任何租户上下文，RLS 会诚实地把
    // 所有行都挡掉 —— 那恰好又是一次隔离生效的证明，但会让「行到底写到哪去了」
    // 这个问题答不了。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from finance_deferred_settlement"
                        + " where external_order_no = 'EXT-FORGED'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    var visibleToB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from finance_deferred_settlement"
                            + " where external_order_no = 'EXT-FORGED'")
                    .size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 规则行同样落在会话租户 a 名下。
    var rules =
        tenants.run(
            a, () -> sql.fetch("select tenant_id from finance_deferred_settlement_rule where shop_code = 'SHOP-FORGED'"));
    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).get("tenant_id", UUID.class)).isEqualTo(a).isNotEqualTo(b);
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(orderNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-order");
      assertThat(orderNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-order");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(orderNosOf(a)).containsExactly("tenant-a-order");
    assertThat(orderNosOf(b)).containsExactly("tenant-b-order");
  }

  // ==========================================================================
  // 隔离口径：金额与汇总也必须分租户
  // ==========================================================================
  @Test
  void 汇总口径也分租户() throws Exception {
    var summaryOfA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select currency, sum(order_amount) as amount from finance_deferred_settlement"
                            + " group by currency")
                    .map(r -> r.get("amount", BigDecimal.class)));
    assertThat(summaryOfA).hasSize(1);
    assertThat(summaryOfA.get(0)).isEqualByComparingTo("100.0000");
    var summaryOfB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select currency, sum(order_amount) as amount from finance_deferred_settlement"
                            + " group by currency")
                    .map(r -> r.get("amount", BigDecimal.class)));
    assertThat(summaryOfB).hasSize(1);
    assertThat(summaryOfB.get(0)).isEqualByComparingTo("100.0000");
  }
}
