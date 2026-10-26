package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PaymentExecutionModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
 * finance 服务的**租户隔离**证明（12.2.2.2 付款执行这一批表）。
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
 * <p><b>刻意跑在本模块自己的表上</b>：12.2.2.2 的表是 {@code finance_payment_channel} /
 * {@code finance_payment_execution} / {@code finance_payment_execution_allocation} /
 * {@code finance_payment_execution_attempt} / {@code finance_payment_execution_event} /
 * {@code finance_rate_lock} —— 证明的是**真的要用的那几张表**被隔离了，
 * 而不是只在别人的表上做过一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentExecutionTenancyIntegrationTest {
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
  static final AtomicInteger sendCount = new AtomicInteger();
  static HttpServer upstream;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-paymentexec-tenancy-" + tenant + "-" + ordinal).getBytes());
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
            "finance.paymentexecution.read",
            "finance.paymentexecution.create",
            "finance.paymentexecution.update");
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, List.of("finance.paymentexecution.read")));
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
      // 渠道桩：固定成功。
      upstream.createContext(
          "/channel/payments",
          e -> {
            if (!"POST".equals(e.getRequestMethod())) {
              write(e, 405, "{}".getBytes(StandardCharsets.UTF_8));
              return;
            }
            e.getRequestBody().readAllBytes();
            sendCount.incrementAndGet();
            write(
                e,
                200,
                "{\"status\":\"SUCCESS\",\"serialNo\":\"CH-TENANCY\",\"paidAmount\":\"100.0000\",\"currency\":\"USD\"}"
                    .getBytes(StandardCharsets.UTF_8));
          });
      upstream.createContext(
          "/secrets/",
          e -> write(e, 200, "test-credential".getBytes(StandardCharsets.UTF_8)));
      upstream.setExecutor(Executors.newFixedThreadPool(4));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void write(com.sun.net.httpserver.HttpExchange e, int code, byte[] body) {
    try {
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(code, body.length);
      e.getResponseBody().write(body);
    } catch (Exception ignored) {
      // stub 只服务于断言
    } finally {
      e.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    // ⚠️ **连接池压到 1**：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("PAYMENT_CHANNEL_ALLOWED_HOSTS", () -> "127.0.0.1");
    r.add(
        "PAYMENT_SECRET_MANAGER_URL",
        () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("PAYMENT_SECRET_MANAGER_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 在可信租户事务里插入一整套付款执行的数据（渠道 + 应付 + 付款单 + 执行单 + 尝试 + 事件）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertExecution(UUID tenant, int ordinal) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var channelId = idFor(tenant, 90 + ordinal);
          var payableId = idFor(tenant, 10 + ordinal);
          var payableLineId = idFor(tenant, 20 + ordinal);
          var orderId = idFor(tenant, 30 + ordinal);
          var executionId = idFor(tenant, 40 + ordinal);
          sql.execute(
              "insert into finance_payment_channel"
                  + "(id,tenant_id,code,name,payment_method,base_url,secret_ref,enabled,timeout_seconds,"
                  + "max_attempts,created_at,updated_at)"
                  + " values (?,?,?,?,'BANK_TRANSFER',?,'vault://starcon/finance/tenancy',true,5,3,"
                  + " ?::timestamptz,?::timestamptz)",
              channelId,
              tenant,
              "CH-" + ordinal,
              "租户渠道 " + ordinal,
              "http://127.0.0.1:" + upstream.getAddress().getPort() + "/channel",
              now,
              now);
          sql.execute(
              "insert into finance_payable"
                  + "(id,tenant_id,payable_no,source,source_system,counterparty_type,counterparty_ref,"
                  + "counterparty_name,currency,due_date,confirmed_at,status,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','manual','SUPPLIER',?,'供应商','USD',current_date,"
                  + " ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              payableId,
              tenant,
              "tenant-payable-" + ordinal,
              supplier,
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
          sql.execute("update finance_payable set status = 'OPEN' where id = ?", payableId);
          sql.execute(
              "insert into finance_payment_order"
                  + "(id,tenant_id,order_no,counterparty_type,counterparty_ref,counterparty_name,currency,"
                  + "amount,payment_method,status,approval_status,created_at,updated_at)"
                  + " values (?,?,?,'SUPPLIER',?,'供应商','USD',100.0000,'BANK_TRANSFER','DRAFT','NOT_SUBMITTED',"
                  + " ?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              "tenant-order-" + ordinal,
              supplier,
              now,
              now);
          sql.execute(
              "insert into finance_payment_order_line"
                  + "(id,tenant_id,order_id,line_no,payable_line_id,description,currency,amount,"
                  + "payable_amount_snapshot,payable_paid_snapshot,active,created_at,updated_at)"
                  + " values (?,?,?,1,?,'明细','USD',100.0000,100.0000,0,true,?::timestamptz,?::timestamptz)",
              idFor(tenant, 50 + ordinal),
              tenant,
              orderId,
              payableLineId,
              now,
              now);
          sql.execute(
              "insert into finance_payment_execution"
                  + "(id,tenant_id,order_id,execution_no,channel_id,channel_code,channel_name,"
                  + "payment_method,currency,amount,idempotency_key,status,attempt_count,channel_serial_no,"
                  + "created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,'BANK_TRANSFER','USD',100.0000,?, 'SUCCEEDED',1,'CH-TENANCY',"
                  + " ?::timestamptz,?::timestamptz)",
              executionId,
              tenant,
              orderId,
              "PE-TENANCY-" + ordinal,
              channelId,
              "CH-" + ordinal,
              "租户渠道 " + ordinal,
              "pay-" + orderId + "-full",
              now,
              now);
          sql.execute(
              "insert into finance_payment_execution_attempt"
                  + "(id,tenant_id,execution_id,attempt_no,attempt_kind,idempotency_key,outcome,"
                  + "channel_serial_no,started_at,finished_at)"
                  + " values (?,?,?,1,'SEND',?,'SUCCESS','CH-TENANCY',?::timestamptz,?::timestamptz)",
              idFor(tenant, 60 + ordinal),
              tenant,
              executionId,
              "pay-" + orderId + "-full",
              now,
              now);
          sql.execute(
              "insert into finance_payment_execution_event"
                  + "(id,tenant_id,execution_id,event_type,from_status,to_status,reason,occurred_at)"
                  + " values (?,?,?,'CREATED','','PENDING','建单',?::timestamptz)",
              idFor(tenant, 70 + ordinal),
              tenant,
              executionId,
              now);
          return null;
        });
  }

  /**
   * 给某个租户种一张**已批、但还没有执行单**的付款单。
   *
   * <p>伪造租户头那条用例要**真的发起一次付款执行**，所以它不能复用夹具里
   * 「已经有执行单」的那张单（那样会被幂等键挡住，测不到归属）。
   */
  UUID insertApprovedOrderWithoutExecution(UUID tenant, int ordinal) {
    var payableId = UUID.randomUUID();
    var payableLineId = UUID.randomUUID();
    var orderId = UUID.randomUUID();
    var instanceId = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_payable"
                  + "(id,tenant_id,payable_no,source,source_system,counterparty_type,counterparty_ref,"
                  + "counterparty_name,currency,due_date,confirmed_at,status,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','manual','SUPPLIER',?,'伪造头供应商','USD',current_date,"
                  + " ?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              payableId,
              tenant,
              "forged-payable-" + ordinal,
              supplier,
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
          sql.execute("update finance_payable set status = 'OPEN' where id = ?", payableId);
          sql.execute(
              "insert into finance_payment_order"
                  + "(id,tenant_id,order_no,counterparty_type,counterparty_ref,counterparty_name,currency,"
                  + "amount,payment_method,status,approval_status,approval_instance_id,"
                  + "approval_instance_no,submitted_by,submitted_at,created_at,updated_at)"
                  + " values (?,?,?,'SUPPLIER',?,'伪造头供应商','USD',100.0000,'BANK_TRANSFER','DRAFT',"
                  + " 'NOT_SUBMITTED',?,'AP-1',?,?::timestamptz,?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              "forged-order-" + ordinal,
              supplier,
              instanceId,
              userA,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_payment_order_line"
                  + "(id,tenant_id,order_id,line_no,payable_line_id,description,currency,amount,"
                  + "payable_amount_snapshot,payable_paid_snapshot,active,created_at,updated_at)"
                  + " values (?,?,?,1,?,'明细','USD',100.0000,100.0000,0,true,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              payableLineId,
              now,
              now);
          sql.execute(
              "update finance_payment_order set status='PENDING_APPROVAL', approval_status='PENDING',"
                  + " submitted_at=?::timestamptz, updated_at=?::timestamptz where id=?",
              now,
              now,
              orderId);
          sql.execute(
              "update finance_payment_order set status='APPROVED', approval_status='APPROVED',"
                  + " approved_by=?, approved_at=?::timestamptz, updated_at=?::timestamptz where id=?",
              userA,
              now,
              now,
              orderId);
          return null;
        });
    return orderId;
  }

  /** 以指定租户身份读执行单号。 */
  List<String> executionNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select execution_no from finance_payment_execution order by execution_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select execution_no from finance_payment_execution").size();
  }

  /**
   * 每个用例前清空本模块的表，再种入两租户各一整套。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，
   * JUnit 5 <b>不保证</b>它们的执行顺序，可能先播种再清空。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payment_execution_event, finance_payment_execution_attempt,"
              + " finance_payment_execution_allocation, finance_rate_lock, finance_payment_execution,"
              + " finance_payment_channel, finance_payment_approval_record, finance_payment_order_event,"
              + " finance_payment_order_installment, finance_payment_order_line, finance_payment_order,"
              + " finance_payment_plan_event, finance_payment_reminder, finance_payment_plan,"
              + " finance_payment_batch, finance_payment_milestone, finance_payment_milestone_rule,"
              + " finance_payment_term_rule, finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy"
              + " restart identity cascade");
    }
    sendCount.set(0);
    insertExecution(a, 1);
    insertExecution(b, 2);
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_payment_channel",
            "finance_payment_execution",
            "finance_payment_execution_allocation",
            "finance_payment_execution_attempt",
            "finance_payment_execution_event",
            "finance_rate_lock")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V350 必须真的建出 " + table).isNotNull();
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
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(executionNosOf(a)).containsExactly("PE-TENANCY-1");
    assertThat(executionNosOf(b)).containsExactly("PE-TENANCY-2");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select execution_no from finance_payment_execution where id = ?", idFor(b, 41))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 事件流同样隔离。
    var events =
        tenants.run(a, () -> sql.fetch("select id from finance_payment_execution_event").size());
    assertThat(events).as("执行事件流也必须被隔离").isEqualTo(1);
    // 渠道配置同样隔离。
    var channels =
        tenants.run(a, () -> sql.fetch("select code from finance_payment_channel").size());
    assertThat(channels).as("支付渠道配置也必须被隔离").isEqualTo(1);
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
                    "insert into finance_payment_execution"
                        + "(id,tenant_id,order_id,execution_no,channel_id,channel_code,channel_name,"
                        + "payment_method,currency,amount,idempotency_key,status,created_at,updated_at)"
                        + " values (?,?,?,'PE-FORGED',?,'CH-FORGED','伪造','BANK_TRANSFER','USD',1.0000,?,"
                        + " 'PENDING',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 31),
                    idFor(a, 91),
                    "pay-forged-full",
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
    // 前提：租户 a 名下要有一张**已批、且还没有执行单**的付款单。
    var orderId = insertApprovedOrderWithoutExecution(a, 1);

    // 伪造租户头 + 伪造内部键：请求照样成功，但写入归属必须是**会话租户**。
    http.perform(
            post("/finance/payment-executions")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinancePaymentExecutionInput(orderId, null, "CH-1", "伪造头夹具"))))
        .andExpect(status().isOk());

    // 行落在**会话租户**（a）名下，不在自称的租户（b）名下。
    assertThat(executionNosOf(a)).contains("PE-TENANCY-1");
    assertThat(executionNosOf(b)).containsExactly("PE-TENANCY-2");
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_payment_execution where note = ?", "伪造头夹具")
                    .get(0, Long.class));
    assertThat(landedInA).as("伪造租户头不能改变行的归属").isEqualTo(1);
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_payment_execution where note = ?", "伪造头夹具")
                    .get(0, Long.class));
    assertThat(landedInB).as("伪造租户头不能把行写进别的租户").isZero();

    // B 会话 + 伪造 A 头读 A 的执行单 ⇒ 必须 404（按 id 直查也拿不到）。
    http.perform(
            get("/finance/payment-executions/" + idFor(a, 41))
                .header("Authorization", "session-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // ⚠️ **每轮都新建实体**：否则测的是幂等，不是隔离。
    for (int round = 0; round < 3; round++) {
      var entityId = UUID.randomUUID();
      var roundNo = round;
      tenants.run(
          a,
          () -> {
            sql.execute(
                "insert into finance_payment_channel"
                    + "(id,tenant_id,code,name,payment_method,base_url,secret_ref,enabled,timeout_seconds,"
                    + "max_attempts,created_at,updated_at)"
                    + " values (?,?,?,?,'BANK_TRANSFER','https://example.com','vault://x',true,5,3,"
                    + " ?::timestamptz,?::timestamptz)",
                entityId,
                a,
                "ROUND-" + roundNo,
                "轮次渠道 " + roundNo,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
            return null;
          });
      // A 写完立刻让 B 读（连接池只有 1 条连接 ⇒ B 拿到的就是 A 刚归还的那条）。
      var seenByB =
          tenants.run(
              b,
              () ->
                  sql.fetchOne(
                          "select count(*) from finance_payment_channel where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByB).as("第 " + round + " 轮：B 必须看不见 A 刚写的行").isZero();
      // A 自己仍然看得见（没有把自己写丢）。
      var seenByA =
          tenants.run(
              a,
              () ->
                  sql.fetchOne(
                          "select count(*) from finance_payment_channel where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByA).as("第 " + round + " 轮：A 必须看得见自己刚写的行").isEqualTo(1);
      // 事务外读：一行都看不见（没有租户上下文）。
      assertThat(visibleWithoutTenant()).isZero();
    }
    // 两租户的渠道各自只有自己那一条（+ 本轮新建的一条）。
    assertThat(executionNosOf(a)).containsExactly("PE-TENANCY-1");
    assertThat(executionNosOf(b)).containsExactly("PE-TENANCY-2");
  }
}
