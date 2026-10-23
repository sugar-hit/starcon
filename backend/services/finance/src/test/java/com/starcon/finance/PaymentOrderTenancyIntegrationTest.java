package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PaymentOrderModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * finance 服务的**租户隔离**证明（12.2.2.1 付款单与审批这一批表）。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见
 *       （只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的表上</b>：12.2.2.1 的表是 {@code finance_payment_order} /
 * {@code finance_payment_order_line} / {@code finance_payment_order_installment} /
 * {@code finance_payment_order_event} / {@code finance_payment_approval_record} ——
 * 证明的是**真的要用的那几张表**被隔离了，而不是只在别人的表上做过一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final UUID supplier = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer upstream;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-paymentorder-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    var permissions =
        List.of(
            "finance.paymentorder.read",
            "finance.paymentorder.create",
            "finance.paymentorder.update",
            // 伪造头那条用例要先造一张应付（走 12.1.2.1 的服务层），所以要它的写权限。
            "finance.payable.read",
            "finance.payable.create",
            "finance.payable.update");
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, List.of("finance.paymentorder.read")));
    try {
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext(
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
      upstream.setExecutor(Executors.newFixedThreadPool(2));
      upstream.start();
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
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 在可信租户事务里插入一张应付单 + 一条明细 + 一张付款单（timestamptz 一律绑 OffsetDateTime +
   * {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertOrder(UUID tenant, UUID payableId, UUID payableLineId, UUID orderId, String no, String orderNo) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_payable"
                  + "(id,tenant_id,payable_no,source,source_system,counterparty_type,counterparty_ref,"
                  + " counterparty_name,currency,due_date,confirmed_at,status,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','manual','SUPPLIER',?,'供应商','USD',?::date,?::timestamptz,'DRAFT',"
                  + " ?::timestamptz,?::timestamptz)",
              payableId,
              tenant,
              no,
              supplier,
              LocalDate.now(ZoneOffset.UTC).plusDays(30),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_payable_line"
                  + "(id,tenant_id,payable_id,line_no,description,amount,paid_amount,created_at,updated_at)"
                  + " values (?,?,?,1,'明细',100.0000,0,?::timestamptz,?::timestamptz)",
              payableLineId,
              tenant,
              payableId,
              now,
              now);
          // 12.1.2.1 的守卫：应付单必须走 DRAFT → OPEN。
          sql.execute(
              "update finance_payable set status = 'OPEN' where id = ?", payableId);
          sql.execute(
              "insert into finance_payment_order"
                  + "(id,tenant_id,order_no,counterparty_type,counterparty_ref,counterparty_name,currency,"
                  + " amount,payment_method,status,approval_status,created_at,updated_at)"
                  + " values (?,?,?,'SUPPLIER',?,'供应商','USD',100.0000,'BANK_TRANSFER','DRAFT','NOT_SUBMITTED',"
                  + " ?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              orderNo,
              supplier,
              now,
              now);
          sql.execute(
              "insert into finance_payment_order_line"
                  + "(id,tenant_id,order_id,line_no,payable_line_id,description,currency,amount,"
                  + " payable_amount_snapshot,payable_paid_snapshot,active,created_at,updated_at)"
                  + " values (?,?,?,1,?,'明细','USD',100.0000,100.0000,0,true,?::timestamptz,?::timestamptz)",
              UUID.nameUUIDFromBytes(("line-" + orderId).getBytes()),
              tenant,
              orderId,
              payableLineId,
              now,
              now);
          sql.execute(
              "insert into finance_payment_order_event"
                  + "(id,tenant_id,order_id,event_type,from_status,to_status,reason,actor_id,occurred_at)"
                  + " values (?,?,?,'CREATED','','DRAFT','建单',null,?::timestamptz)",
              UUID.nameUUIDFromBytes(("event-" + orderId).getBytes()),
              tenant,
              orderId,
              now);
          return null;
        });
  }

  /** 以指定租户身份读付款单号。 */
  List<String> orderNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select order_no from finance_payment_order order by order_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select order_no from finance_payment_order").size();
  }

  /**
   * 每个用例前清空本模块的表，再种入两租户各一行。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，
   * JUnit 5 <b>不保证</b>它们的执行顺序，可能先播种再清空。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payment_approval_record, finance_payment_order_event,"
              + " finance_payment_order_installment, finance_payment_order_line,"
              + " finance_payment_order, finance_payment_plan_event, finance_payment_reminder,"
              + " finance_payment_plan, finance_payment_batch, finance_payment_milestone,"
              + " finance_payment_milestone_rule, finance_payment_term_rule,"
              + " finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy"
              + " restart identity cascade");
    }
    insertOrder(a, idFor(a, 1), idFor(a, 2), idFor(a, 3), "tenant-a-payable", "tenant-a-order");
    insertOrder(b, idFor(b, 1), idFor(b, 2), idFor(b, 3), "tenant-b-payable", "tenant-b-order");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_payment_order",
            "finance_payment_order_line",
            "finance_payment_order_installment",
            "finance_payment_order_event",
            "finance_payment_approval_record")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V346 必须真的建出 " + table).isNotNull();
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
    assertThat(orderNosOf(a)).containsExactly("tenant-a-order");
    assertThat(orderNosOf(b)).containsExactly("tenant-b-order");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select order_no from finance_payment_order where id = ?", idFor(b, 3))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 明细行同样隔离。
    var lines =
        tenants.run(
            a,
            () -> sql.fetch("select order_id from finance_payment_order_line").size());
    assertThat(lines).as("明细行也必须被隔离").isEqualTo(1);
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
                    "insert into finance_payment_order"
                        + "(id,tenant_id,order_no,counterparty_type,counterparty_name,currency,amount,"
                        + " payment_method,status,approval_status,created_at,updated_at)"
                        + " values (?,?,?,'SUPPLIER','伪造','USD',1.0000,'BANK_TRANSFER','DRAFT','NOT_SUBMITTED',"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    "forged-tenant-order",
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
    // 前提：租户 a 名下要有可付款的应付（用服务层建：12.1.2.1 的应付确认政策是它的闸门）。
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into finance_payable_policy(id,tenant_id,source,obligation_kind,enabled,due_days,"
                  + " owner_module,created_at,updated_at)"
                  + " values(?,?,'MANUAL','PAYABLE',true,30,'',now(),now())",
              UUID.randomUUID(),
              a);
          return null;
        });
    var today = LocalDate.now(ZoneOffset.UTC);
    http.perform(
            post("/finance/payables")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new com.starcon.finance.PayableModels.FinancePayableInput(
                            com.starcon.finance.PayableModels.PayableSource.MANUAL,
                            com.starcon.finance.PayableModels.CounterpartyType.SUPPLIER,
                            supplier,
                            "伪造头供应商",
                            "USD",
                            null,
                            null,
                            today.plusDays(10),
                            "伪造头夹具",
                            List.of(
                                new com.starcon.finance.PayableModels.FinancePayableLineInput(
                                    "伪造头明细",
                                    null,
                                    null,
                                    null,
                                    null,
                                    new BigDecimal("12.5")))))))
        .andExpect(status().isOk());
    var payableId =
        tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from finance_payable where counterparty_name = ?", "伪造头供应商")
                        .get(0)
                        .get(0, UUID.class));
    var payableLineId =
        tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from finance_payable_line where payable_id = ?", payableId)
                        .get(0)
                        .get(0, UUID.class));

    // 伪造租户头 + 伪造内部键：请求照样成功，但写入归属必须是**会话租户**。
    http.perform(
            post("/finance/payment-orders")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinancePaymentOrderInput(
                            PaymentCounterpartyType.SUPPLIER,
                            supplier,
                            "伪造头供应商",
                            "USD",
                            new BigDecimal("12.50"),
                            null,
                            null,
                            PaymentMethod.BANK_TRANSFER,
                            null,
                            null,
                            null,
                            null,
                            "伪造头夹具",
                            List.of(
                                new FinancePaymentOrderLineInput(
                                    payableLineId, "伪造头明细", "USD", new BigDecimal("12.50"))),
                            null))))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from finance_payment_order where note = ?", "伪造头夹具"));
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
                sql.fetch("select id from finance_payment_order where note = ?", "伪造头夹具").size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(orderNosOf(a)).contains("tenant-a-order");
    assertThat(orderNosOf(b)).containsExactly("tenant-b-order");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
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
}
