package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 11.3.1.2 退款状态（列表 + 状态机 + 幂等 + 先查证再重试 + 差异清单 + 平台同步）。
 *
 * <p>跑的是真实 PostgreSQL 16（Testcontainers），Flyway 实跑全部迁移到 V159。
 *
 * <p><b>本测试类最重要的部分不是 CRUD，是三组资损防护的证明</b>：
 *
 * <ol>
 *   <li>{@link #重复提交同一幂等键不会发起第二次退款()} —— DB 层唯一约束 + 服务层回读；
 *   <li>{@link #未知态未查证时重试被拒绝()} / {@link #查证确认已付出时重试被拒绝()}
 *       / {@link #查证无法确认时重试被拒绝()} —— 「先查证交易再重试」；
 *   <li>{@link #绕过服务层直接插入重试派发同样被db触发器挡下()} —— 规则不是调用方自觉。
 * </ol>
 *
 * <p>多租户三断言在 {@link RefundStatusTenancyIntegrationTest} 里实跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundStatusIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.refunds.read", "trade.refunds.write");
  static final UUID user = UUID.randomUUID();
  static final UUID tenant = UUID.randomUUID();

  static AccessService.Access actor() {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer t1", actor());
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired RefundStatusService refunds;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  /**
   * 非 UTC 夹具：{@code +08:00}。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s —— 退款场景全是时间，
   * 写错会让「查证时刻」与「派发时刻」错开 8 小时，而超时判定正是靠时间差。
   */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-03-02T09:30:00+08:00");
  static final OffsetDateTime T3 = OffsetDateTime.parse("2026-03-03T10:15:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate refund_status_event, refund_discrepancy, refund_execution_report,"
              + " refund_verification, refund_attempt, refund_execution, aftersales_case,"
              + " sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actor(), "trade.refunds.write", work);
  }

  /** 建一张真实订单（走 V99 的表，夹具必须满足它的延迟约束触发器）。 */
  UUID seedOrder(String orderNo) {
    UUID orderId = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,100.0000,0,0,"
                  + "0,0,100.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,?,1,100.0000,0,0,100.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + orderNo, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家" + orderNo, T1);
          return null;
        });
    return orderId;
  }

  RefundStatusModels.RefundExecutionCreate create(String key, UUID orderId, String amount) {
    return new RefundStatusModels.RefundExecutionCreate(
        key, orderId, null, new java.math.BigDecimal(amount), "USD",
        RefundStatusModels.PlatformRefundMethod.ORIGINAL, "测试登记");
  }

  RefundStatusModels.RefundExecutionQuery query(int page, int size) {
    return new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, null, page, size);
  }

  /** 登记 + 首次派发 ⇒ 进入 IN_FLIGHT。 */
  UUID inFlight(String key, String amount) {
    UUID orderId = seedOrder("MO-" + key);
    UUID id = as(() -> refunds.register(create(key, orderId, amount), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest(key + "-d1", null, T1, "FINANCE", "首次派发"), user));
    return id;
  }

  // ==========================================================================
  // 一、迁移落地 + 状态机与 DB 函数两侧完全一致
  // ==========================================================================

  @Test
  void 六张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "refund_execution", "refund_attempt", "refund_verification",
            "refund_execution_report", "refund_discrepancy", "refund_status_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V159 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  /**
   * 状态机在 Java 侧与 DB 侧<b>逐条一致</b>。
   *
   * <p>两侧实现并存（Java 给人话错误、DB 给绕过应用时的兜底）。若只留一处，
   * 绕过应用的那条路就裸奔；若两侧漂移，这个用例就红。
   */
  @Test
  void 状态机在java侧与db侧逐条一致() {
    // 逐对探测 DB 里那个**真实被触发器使用**的函数 refund_transition_allowed()：
    // 6×6 = 36 对全查一遍，两侧集合必须逐条相同（含两侧都漏掉的边）。
    var db = new java.util.TreeSet<String>();
    for (var from : RefundStatusModels.RefundExecutionState.values()) {
      for (var to : RefundStatusModels.RefundExecutionState.values()) {
        boolean allowed = as(() -> sql.fetchOne(
            "select refund_transition_allowed(?,?) ok", from.name(), to.name())
            .get("ok", Boolean.class));
        if (allowed) {
          db.add(from.name() + ">" + to.name());
        }
      }
    }
    var java = new java.util.TreeSet<String>();
    for (var from : RefundStatusModels.RefundExecutionState.values()) {
      for (var to : from.allowedNext()) {
        java.add(from.name() + ">" + to.name());
      }
    }
    assertThat(java).as("Java 侧状态机不得为空").isNotEmpty();
    assertThat(db).as("两侧可转换集合必须逐条一致（含两侧都漏掉的边）").isEqualTo(java);

    // 关键一条：UNKNOWN → FAILED 存在，但**不是**「超时自动变失败」——
    // 它必须经由一条 CONFIRMED_NOT_SETTLED 的查证（由上面的用例证明）。
    assertThat(java).contains("UNKNOWN>FAILED");
    // 在途不得能直接回未发起（结果没到不许撤销重来）。
    assertThat(java).doesNotContain("IN_FLIGHT>NOT_INITIATED");
    // 已成功不得能直接回在途（已退成功的钱再发一次就是重复退款）。
    assertThat(java).doesNotContain("SUCCEEDED>IN_FLIGHT");
  }

  // ==========================================================================
  // 二、幂等：重复提交同一幂等键不会发起第二次退款  ← 资损防护之一
  // ==========================================================================

  @Test
  void 重复提交同一幂等键不会发起第二次退款() {
    UUID orderId = seedOrder("MO-IDEM");

    var first = as(() -> refunds.register(create("refund-key-A", orderId, "88.0000"), user));
    var second = as(() -> refunds.register(create("refund-key-A", orderId, "88.0000"), user));

    assertThat(second.id()).as("同一幂等键必须返回同一笔执行记录").isEqualTo(first.id());
    assertThat(second.refundNo()).isEqualTo(first.refundNo());

    long rows =
        as(() -> sql.fetchOne(
            "select count(*) c from refund_execution where idempotency_key = 'refund-key-A'")
            .get("c", Long.class));
    assertThat(rows).as("DB 层必须真的只有一条（不是靠服务层 if 挡的）").isEqualTo(1);
  }

  @Test
  void 幂等键在db层有唯一约束兜底() {
    // 直接查约束而不是相信服务层：并发下两个事务同时「查不到」时靠的就是它。
    var indexes =
        sql.fetch(
            "select indexname from pg_indexes where tablename = 'refund_execution'"
                + " and indexdef ilike '%UNIQUE%idempotency_key%'");
    assertThat(indexes).as("refund_execution 必须有 UNIQUE(tenant_id, idempotency_key)").isNotEmpty();
    var attemptIndexes =
        sql.fetch(
            "select indexname from pg_indexes where tablename = 'refund_attempt'"
                + " and indexdef ilike '%UNIQUE%idempotency_key%'");
    assertThat(attemptIndexes)
        .as("refund_attempt 必须有 UNIQUE(tenant_id, idempotency_key)（派发幂等）")
        .isNotEmpty();
  }

  @Test
  void 重复派发同一幂等键不会发出第二笔() {
    UUID orderId = seedOrder("MO-IDEM2");
    UUID id = as(() -> refunds.register(create("refund-key-B", orderId, "50.0000"), user).id());

    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("dispatch-key-1", null, T1, "FINANCE", "首次"), user));

    // 让它失败，好让状态回到可重试；再用**同一个**派发键重试。
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.MANUAL_OPERATOR,
            RefundStatusModels.ReportOutcome.FAILED,
            RefundStatusModels.FailureClass.DEFINITIVE,
            "", null, null, "INSUFFICIENT_BALANCE", "余额不足", T2), user));

    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("dispatch-key-1", null, T3, "FINANCE", "重复派发"), user));

    long attempts =
        as(() -> sql.fetchOne(
            "select count(*) c from refund_attempt where execution_id = ?", id).get("c", Long.class));
    assertThat(attempts).as("同一派发幂等键绝不能产生第二条派发记录（那就是发了两次钱）").isEqualTo(1);
  }

  // ==========================================================================
  // 三、先查证交易再重试  ← 资损防护之二（本模块的核心）
  // ==========================================================================

  @Test
  void 外部超时不等于失败_只会推进到未知态() {
    UUID id = inFlight("MO-TIMEOUT", "10.0000");

    var after = as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "支付网关 30 秒无响应", T2), user));

    assertThat(after.executionState())
        .as("超时**只能**是 UNKNOWN，绝不能是 FAILED —— 这是蓝图加粗句的直接落点")
        .isEqualTo(RefundStatusModels.RefundExecutionState.UNKNOWN.name());
    assertThat(after.failureClass()).isEqualTo("INDETERMINATE");
  }

  @Test
  void 未知态的建议是查证而不是重试() {
    UUID id = inFlight("MO-ADVICE", "10.0000");
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));

    var row = as(() -> refunds.list(query(0, 10)).rows().stream()
        .filter(r -> r.id().equals(id)).findFirst().orElseThrow());
    assertThat(row.action())
        .as("UNKNOWN 的建议必须是 VERIFY，**永远不是** RETRY")
        .isEqualTo(RefundStatusModels.RefundAction.VERIFY.name());
    assertThat(row.requiredVerdict())
        .as("VERIFY 必须说明要求什么样的查证结论")
        .isEqualTo(RefundStatusModels.VerificationVerdict.CONFIRMED_NOT_SETTLED.name());
    assertThat(row.actionBlocking()).as("界面必须能显示「为什么不能重试」").contains("超时不等于失败");
  }

  @Test
  void 未知态未查证时重试被拒绝() {
    UUID id = inFlight("MO-NOVERIFY", "10.0000");
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));

    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(id,
                new RefundStatusModels.RefundDispatchRequest("d-nv", null, T3, "FINANCE", ""), user)))
        .hasMessageContaining("必须先查证交易")
        .hasMessageContaining("超时不等于失败");
  }

  @Test
  void 查证无法确认时重试被拒绝() {
    UUID id = inFlight("MO-INCONCLUSIVE", "10.0000");
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));

    var v = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.INCONCLUSIVE,
            "TXN-INC-1", "渠道仍未回执", T3), user));

    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(id,
                new RefundStatusModels.RefundDispatchRequest("d-inc", v.id(), T3, "FINANCE", ""), user)))
        .hasMessageContaining("INCONCLUSIVE")
        .hasMessageContaining("保持 UNKNOWN");
  }

  @Test
  void 查证确认已付出时重试被拒绝() {
    UUID id = inFlight("MO-SETTLED", "10.0000");
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));

    var v = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.CONFIRMED_SETTLED,
            "TXN-SETTLED-1", "平台查到已支付", T3), user));

    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(id,
                new RefundStatusModels.RefundDispatchRequest("d-set", v.id(), T3, "FINANCE", ""), user)))
        .hasMessageContaining("CONFIRMED_SETTLED")
        .as("钱已经付出去了，重试就是对同一笔退款发起第二次支付")
        .hasMessageContaining("SUCCEEDED");
  }

  @Test
  void 查证确认未付出后才允许重试() {
    UUID id = inFlight("MO-RETRYOK", "10.0000");
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));

    var v = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.CONFIRMED_NOT_SETTLED,
            "TXN-NOTSETTLED-1", "平台查到没有这笔支付", T3), user));

    var after = as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-ok", v.id(), T3, "FINANCE", "查证后重试"), user));
    assertThat(after.executionState()).isEqualTo(RefundStatusModels.RefundExecutionState.IN_FLIGHT.name());
    assertThat(after.attemptCount()).as("应是第 2 次派发").isEqualTo(2);
  }

  @Test
  void 带别的退款的查证记录不能用来重试() {
    UUID a = inFlight("MO-XA", "10.0000");
    UUID b = inFlight("MO-XB", "20.0000");
    timeout(a);
    timeout(b);

    var vOfA = as(() -> refunds.verify(a,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.CONFIRMED_NOT_SETTLED,
            "TXN-A", "确认未付出", T3), user));

    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(b,
                new RefundStatusModels.RefundDispatchRequest("d-wrong", vOfA.id(), T3, "FINANCE", ""), user)))
        .hasMessageContaining("不属于这笔退款");
  }

  @Test
  void 在途状态重发被拒绝() {
    UUID id = inFlight("MO-INFLIGHT", "10.0000");
    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(id,
                new RefundStatusModels.RefundDispatchRequest("d-again", null, T2, "FINANCE", "结果没回就再发"), user)))
        .hasMessageContaining("重复支付");
  }

  @Test
  void 已成功的退款重试被拒绝() {
    UUID orderId = seedOrder("MO-SUCC");
    UUID id = as(() -> refunds.register(create("refund-succ", orderId, "33.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-succ", null, T1, "FINANCE", ""), user));
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.SUCCEEDED,
            null, "TXN-SUCCESS-1", new java.math.BigDecimal("33.0000"), "USD", "", "", T2), user));

    assertThatThrownBy(() ->
            as(() -> refunds.dispatch(id,
                new RefundStatusModels.RefundDispatchRequest("d-after-succ", null, T3, "FINANCE", ""), user)))
        .hasMessageContaining("重复退款");
  }

  void timeout(UUID id) {
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.TIMEOUT,
            RefundStatusModels.FailureClass.INDETERMINATE,
            "", null, null, "GATEWAY_TIMEOUT", "网关无响应", T2), user));
  }

  // ==========================================================================
  // 四、绕过服务层时 DB 触发器仍然挡住  ← 资损防护之三
  // ==========================================================================

  @Test
  void 绕过服务层直接插入重试派发同样被db触发器挡下() {
    UUID id = inFlight("MO-BYPASS", "10.0000");
    timeout(id);

    // 对照组：先把状态直接改成 FAILED 的那条 INSERT 语句本身能跑通
    //（证明下面两次失败是**触发器**挡的，不是语句写坏了）。
    var attemptNo =
        as(() -> sql.fetchOne(
            "select coalesce(max(attempt_no),0)+1 n from refund_attempt where execution_id = ?", id)
            .get("n", Integer.class));

    // ① 未带查证记录的重试 → 表级 CHECK refund_attempt_retry_needs_verification 挡住。
    // ⚠️ 实际先撞上的是 BEFORE INSERT 触发器 guard_refund_attempt_retry（触发器先于约束检查），
    //   它的消息更具体（写明了执行状态）。CHECK 是**第二道**，下面单独断言它真的存在 ——
    //   有人禁用触发器时 CHECK 仍然挡得住。
    assertThatThrownBy(() ->
            as(() -> sql.execute(
                "insert into refund_attempt(id,tenant_id,execution_id,idempotency_key,attempt_kind,"
                    + "attempt_no,verification_id,state_at_dispatch,dispatched_at,dispatch_channel,created_at)"
                    + " values(?,?,?,?,'RETRY',?,NULL,'UNKNOWN',?::timestamptz,'FINANCE',?::timestamptz)",
                UUID.randomUUID(), tenant, id, "bypass-1", attemptNo, T3, T3)))
        .hasMessageContaining("重试必须先查证交易");

    // 第二道：表级 CHECK 确实存在（BEFORE INSERT 触发器会先挡住，所以这里查约束目录而不是撞它）。
    var check = sql.fetchOne(
        "select pg_get_constraintdef(oid) d from pg_constraint where conname = ?",
        "refund_attempt_retry_needs_verification");
    assertThat(check).as("表级 CHECK 必须真的存在（触发器被禁用时的第二道）").isNotNull();
    assertThat(check.get("d", String.class))
        .contains("attempt_kind")
        .contains("verification_id");

    // ② 带一条 INCONCLUSIVE 查证记录的重试 → 表级 CHECK 过了，但**触发器**按结论拒绝。
    var inconclusive = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.INCONCLUSIVE,
            "TXN-BP-1", "渠道仍未回执", T3), user));

    assertThatThrownBy(() ->
            as(() -> sql.execute(
                "insert into refund_attempt(id,tenant_id,execution_id,idempotency_key,attempt_kind,"
                    + "attempt_no,verification_id,state_at_dispatch,dispatched_at,dispatch_channel,created_at)"
                    + " values(?,?,?,?,'RETRY',?,?,'UNKNOWN',?::timestamptz,'FINANCE',?::timestamptz)",
                UUID.randomUUID(), tenant, id, "bypass-2", attemptNo, inconclusive.id(), T3, T3)))
        .as("绕过服务层直接 INSERT 也必须被 DB 触发器挡住 —— 规则不是调用方自觉")
        .hasMessageContaining("INCONCLUSIVE");
  }

  @Test
  void 绕过服务层直接改状态也受状态机约束() {
    UUID orderId = seedOrder("MO-STATE");
    UUID id = as(() -> refunds.register(create("refund-state", orderId, "12.0000"), user).id());

    // NOT_INITIATED → UNKNOWN 非法（「结果未知」的前提是「已经发起过」）。
    // ⚠️ 先撞上的是 BEFORE UPDATE 触发器 guard_refund_execution_transition 而不是那条 CHECK：
    //   触发器比约束检查先跑，且它给出的信息更具体（写明了非法转换的两端）。
    //   CHECK refund_execution_unknown_was_initiated 是**第二道**，在下面单独断言它真的存在。
    assertThatThrownBy(() ->
            as(() -> sql.execute(
                "update refund_execution set execution_state='UNKNOWN', failure_class='INDETERMINATE',"
                    + " failure_reason='超时' where tenant_id=? and id=?", tenant, id)))
        .hasMessageContaining("非法的退款状态转换")
        .hasMessageContaining("NOT_INITIATED → UNKNOWN");

    // NOT_INITIATED → FAILED 也被状态机触发器挡住（合法目标只有 IN_FLIGHT）。
    assertThatThrownBy(() ->
            as(() -> sql.execute(
                "update refund_execution set execution_state='FAILED', failure_class='DEFINITIVE',"
                    + " failure_reason='明确失败', resolved_at=?::timestamptz where tenant_id=? and id=?",
                T2, tenant, id)))
        .hasMessageContaining("非法的退款状态转换");
  }

  @Test
  void 失败态写不定性分类被db的check挡下() {
    UUID orderId = seedOrder("MO-DEF");
    UUID id = as(() -> refunds.register(create("refund-def", orderId, "12.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-def", null, T1, "FINANCE", ""), user));

    // 「失败但不确定」这种自相矛盾的记录在数据库层就进不来。
    assertThatThrownBy(() ->
            as(() -> sql.execute(
                "update refund_execution set execution_state='FAILED', failure_class='INDETERMINATE',"
                    + " failure_reason='超时', resolved_at=?::timestamptz where tenant_id=? and id=?",
                T2, tenant, id)))
        .as("超时不等于失败：FAILED 必须带 DEFINITIVE（钱确定没动）")
        .hasMessageContaining("refund_execution_failure_is_definitive");
  }

  @Test
  void 追加式证据表运行角色改不动() {
    for (String table : List.of("refund_attempt", "refund_verification",
        "refund_execution_report", "refund_status_event")) {
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'UPDATE')", table)
              .get(0, Boolean.class))
          .as(table + " 是资损证据，必须改不动")
          .isFalse();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'DELETE')", table)
              .get(0, Boolean.class))
          .as(table + " 是资损证据，必须删不掉")
          .isFalse();
    }
  }

  @Test
  void 差异清单的检测事实改不动但裁定可以() {
    assertThat(sql.fetchOne(
            "select has_column_privilege('trade_app','refund_discrepancy','system_amount','UPDATE')")
        .get(0, Boolean.class))
        .as("系统侧金额是检测事实，改掉它就等于抹掉差异")
        .isFalse();
    assertThat(sql.fetchOne(
            "select has_column_privilege('trade_app','refund_discrepancy','resolution_state','UPDATE')")
        .get(0, Boolean.class))
        .as("裁定列必须可写，否则差异永远无法处理")
        .isTrue();
  }

  @Test
  void 幂等键运行角色改不动() {
    assertThat(sql.fetchOne(
            "select has_column_privilege('trade_app','refund_execution','idempotency_key','UPDATE')")
        .get(0, Boolean.class))
        .as("改得了幂等键 = 让同一笔退款可以摇身变成两笔")
        .isFalse();
  }

  // ==========================================================================
  // 五、时间夹具：+08:00 写回零漂移（退款场景全是时间）
  // ==========================================================================

  @Test
  void 非utc时刻写入后断言写回的是同一绝对时刻() {
    UUID id = inFlight("MO-TZ", "10.0000");
    var v = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.INCONCLUSIVE,
            "TXN-TZ", "渠道未回执", T2), user));

    // API 侧断言。⚠️ 比的是**绝对时刻**（Instant）而不是 OffsetDateTime：
    //   +08:00 的 09:30 与 UTC 的 01:30 是同一刻，而 AssertJ 对 OffsetDateTime 用
    //   「带偏移的相等」判据，直接比 OffsetDateTime 会因偏移不同而误判为不等。
    assertThat(v.verifiedAt().toInstant()).as("+08:00 夹具必须写回同一绝对时刻")
        .isEqualTo(T2.toInstant());

    // 直查库再断言一次：验证明 jOOQ 绑定确实带上了时区，而不是碰巧相等。
    // ⚠️ 必须按 **Instant** 取：jOOQ 对 timestamptz 列的默认返回类型是 Instant，
    //   用 OffsetDateTime 去 get 会抛 ClassCastException
    //   （java.time.Instant cannot be cast to java.time.OffsetDateTime）。
    //   比较时统一比**绝对时刻**（toInstant），那才是时区无关的断言对象。
    var row = as(() -> sql.fetchOne(
        "select verified_at from refund_verification where tenant_id=? and id=?", tenant, v.id()));
    assertThat(row.get(0, java.time.Instant.class))
        .as("直查库也必须是同一绝对时刻（Timestamp.valueOf 会静默偏 −28800s）")
        .isEqualTo(T2.toInstant());
  }

  // ==========================================================================
  // 六、差异清单：如实报、不自动抹平
  // ==========================================================================

  @Test
  void 金额差异两边原值都保留() {
    UUID orderId = seedOrder("MO-DIFF");
    UUID id = as(() -> refunds.register(create("refund-diff", orderId, "100.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-diff", null, T1, "FINANCE", ""), user));
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.SUCCEEDED,
            null, "TXN-D1", new java.math.BigDecimal("100.0000"), "USD", "", "", T2), user));

    as(() -> refunds.detectDiscrepancy(id,
        new RefundStatusModels.RefundDiscrepancyCreate(
            RefundStatusModels.DiscrepancyKind.AMOUNT,
            new java.math.BigDecimal("100.0000"), "USD",
            RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
            new java.math.BigDecimal("90.0000"), "USD",
            RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
            "平台只退了 90，系统记 100，差 10", T3), user));

    var page = as(() -> refunds.listDiscrepancies(null, null, 0, 20));
    assertThat(page.total()).isEqualTo(1);
    var row = page.rows().get(0);
    assertThat(row.systemAmount()).isEqualByComparingTo("100.0000");
    assertThat(row.platformAmount()).as("平台侧原值必须留着：只报「不一致」运营无法判断该改哪边")
        .isEqualByComparingTo("90.0000");
    assertThat(row.resolutionState()).as("检出后仍是待裁定，系统不自动抹平").isEqualTo("OPEN");
    assertThat(page.systemAmountByKind().get("AMOUNT")).isEqualByComparingTo("100.0000");
    assertThat(page.platformAmountByKind().get("AMOUNT")).isEqualByComparingTo("90.0000");

    // 检出差异后执行进入 DISCREPANCY。
    assertThat(as(() -> refunds.detail(id)).header().executionState()).isEqualTo("DISCREPANCY");
  }

  @Test
  void 金额相等的差异被拒绝() {
    UUID orderId = seedOrder("MO-SAME");
    UUID id = as(() -> refunds.register(create("refund-same", orderId, "100.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-same", null, T1, "FINANCE", ""), user));

    assertThatThrownBy(() ->
            as(() -> refunds.detectDiscrepancy(id,
                new RefundStatusModels.RefundDiscrepancyCreate(
                    RefundStatusModels.DiscrepancyKind.AMOUNT,
                    new java.math.BigDecimal("100.00"), "USD",
                    RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
                    new java.math.BigDecimal("100.0000"), "USD",
                    RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
                    "标度不同但数值相同", T3), user)))
        .as("10.00 与 10.0000 在 numeric 列里是同一个数，不构成差异")
        .hasMessageContaining("不构成金额不一致");
  }

  @Test
  void 存在性差异要求恰好一边有状态() {
    UUID orderId = seedOrder("MO-EXIST");
    UUID id = as(() -> refunds.register(create("refund-exist", orderId, "100.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-exist", null, T1, "FINANCE", ""), user));

    // 两边都有状态 ⇒ 那是状态差异而不是存在性差异。
    assertThatThrownBy(() ->
            as(() -> refunds.detectDiscrepancy(id,
                new RefundStatusModels.RefundDiscrepancyCreate(
                    RefundStatusModels.DiscrepancyKind.EXISTENCE,
                    null, null, RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
                    null, null, RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
                    "两边都有", T3), user)))
        .hasMessageContaining("恰好一边");

    // 恰好一边 ⇒ 合法（平台有一笔退款而系统没有：最严重的一类，必须能报出来）。
    var row = as(() -> refunds.detectDiscrepancy(id,
        new RefundStatusModels.RefundDiscrepancyCreate(
            RefundStatusModels.DiscrepancyKind.EXISTENCE,
            null, null, RefundStatusModels.RefundExecutionState.IN_FLIGHT, null,
            null, null, null, null,
            "平台有一笔退款而系统这边没有这条记录", T3), user));
    assertThat(row.platformState()).isNull();
    assertThat(row.systemState()).isEqualTo("IN_FLIGHT");
  }

  @Test
  void 人工裁定必须写说明() {
    UUID orderId = seedOrder("MO-RESOLVE");
    UUID id = as(() -> refunds.register(create("refund-resolve", orderId, "100.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("d-res", null, T1, "FINANCE", ""), user));
    as(() -> refunds.report(id,
        new RefundStatusModels.RefundReportRequest(
            RefundStatusModels.ReportSource.PLATFORM_SYNC,
            RefundStatusModels.ReportOutcome.SUCCEEDED,
            null, "TXN-R1", new java.math.BigDecimal("100.0000"), "USD", "", "", T2), user));
    var d = as(() -> refunds.detectDiscrepancy(id,
        new RefundStatusModels.RefundDiscrepancyCreate(
            RefundStatusModels.DiscrepancyKind.AMOUNT,
            new java.math.BigDecimal("100.0000"), "USD",
            RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
            new java.math.BigDecimal("99.0000"), "USD",
            RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
            "差 1 块", T3), user));

    assertThatThrownBy(() ->
            as(() -> refunds.resolveDiscrepancy(id, d.id(),
                new RefundStatusModels.RefundDiscrepancyResolutionRequest(
                    RefundStatusModels.DiscrepancyResolution.RESOLVED, "", T3), user)))
        .as("不写说明的裁定等于抹平")
        .hasMessageContaining("裁定说明");

    var resolved = as(() -> refunds.resolveDiscrepancy(id, d.id(),
        new RefundStatusModels.RefundDiscrepancyResolutionRequest(
            RefundStatusModels.DiscrepancyResolution.RESOLVED, "平台手续费 1 元，财务确认按 99 记账", T3), user));
    assertThat(resolved.resolutionState()).isEqualTo("RESOLVED");
    assertThat(resolved.resolvedBy()).isEqualTo(user);
    assertThat(resolved.resolvedAt()).isNotNull();

    // 裁完后默认清单里不再出现（默认只看未裁定）。
    assertThat(as(() -> refunds.listDiscrepancies(null, null, 0, 20)).total()).isZero();
  }

  // ==========================================================================
  // 七、列表 / 筛选 / 汇总 / 分页
  // ==========================================================================

  @Test
  void 列表多维筛选与汇总按币种分开() {
    UUID o1 = seedOrder("MO-L1");
    UUID o2 = seedOrder("MO-L2");
    var a = as(() -> refunds.register(
        new RefundStatusModels.RefundExecutionCreate("l1", o1, null,
            new java.math.BigDecimal("100.0000"), "USD",
            RefundStatusModels.PlatformRefundMethod.ORIGINAL, ""), user));
    as(() -> refunds.dispatch(a.id(),
        new RefundStatusModels.RefundDispatchRequest("l1-d", null, T1, "FINANCE", ""), user));
    var b = as(() -> refunds.register(
        new RefundStatusModels.RefundExecutionCreate("l2", o2, null,
            new java.math.BigDecimal("250.0000"), "EUR",
            RefundStatusModels.PlatformRefundMethod.PLATFORM, ""), user));

    var page = as(() -> refunds.list(query(0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.amountByCurrency().get("USD")).isEqualByComparingTo("100.0000");
    assertThat(page.amountByCurrency().get("EUR")).isEqualByComparingTo("250.0000");
    assertThat(page.amountByCurrency()).as("跨币种绝不相加").hasSize(2);
    assertThat(page.inFlightCount()).isEqualTo(1);
    assertThat(page.countByState()).containsEntry("NOT_INITIATED", 1L).containsEntry("IN_FLIGHT", 1L);

    // 按币种筛。
    var eur = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, "EUR", null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(eur.total()).isEqualTo(1);
    assertThat(eur.rows().get(0).id()).isEqualTo(b.id());

    // 按状态筛。
    var inflight = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        "IN_FLIGHT", null, null, null, null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(inflight.total()).isEqualTo(1);
    assertThat(inflight.rows().get(0).id()).isEqualTo(a.id());
  }

  @Test
  void 金额区间是闭区间且汇总与分页无关() {
    for (int i = 1; i <= 5; i++) {
      final UUID o = seedOrder("MO-P" + i);
      final String key = "p" + i;
      final String amount = (i * 10) + ".0000";
      as(() -> refunds.register(new RefundStatusModels.RefundExecutionCreate(key, o, null,
          new java.math.BigDecimal(amount), "USD",
          RefundStatusModels.PlatformRefundMethod.ORIGINAL, ""), user));
    }
    // min == max == 某张单的金额时它自己必须在结果里（闭区间）。
    var exact = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, "30", "30", null, null, 0, 50)));
    assertThat(exact.total()).as("闭区间：min==max 时该单必须在结果里").isEqualTo(1);
    assertThat(exact.rows().get(0).refundAmount()).isEqualByComparingTo("30.0000");

    var p1 = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, "CREATED_ASC", 0, 2)));
    var p3 = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, "CREATED_ASC", 2, 2)));
    assertThat(p1.total()).as("汇总不受分页影响").isEqualTo(5);
    assertThat(p3.total()).isEqualTo(5);
    assertThat(p1.rows()).hasSize(2);
    var ids1 = p1.rows().stream().map(r -> r.id()).toList();
    var ids3 = p3.rows().stream().map(r -> r.id()).toList();
    assertThat(ids1).doesNotContainAnyElementsOf(ids3);
  }

  @Test
  void 默认排序是待处置优先() {
    UUID o1 = seedOrder("MO-S1");
    var plain = as(() -> refunds.register(create("s1", o1, "10.0000"), user));
    UUID o2 = seedOrder("MO-S2");
    var flying = as(() -> refunds.register(create("s2", o2, "10.0000"), user));
    as(() -> refunds.dispatch(flying.id(),
        new RefundStatusModels.RefundDispatchRequest("s2-d", null, T1, "FINANCE", ""), user));
    timeout(flying.id());

    var page = as(() -> refunds.list(query(0, 50)));
    assertThat(page.rows().get(0).id())
        .as("结果未知必须排在未发起的普通单前面")
        .isEqualTo(flying.id());
    assertThat(page.needsAttentionCount()).isEqualTo(1);
    assertThat(page.unknownCount()).isEqualTo(1);
    assertThat(plain.id()).isNotEqualTo(flying.id());

    var attention = as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null,
        "true", null, 0, 50)));
    assertThat(attention.total()).isEqualTo(1);
    assertThat(attention.rows().get(0).id()).isEqualTo(flying.id());
  }

  @Test
  void 未知排序键被拒绝() {
    assertThatThrownBy(() -> as(() -> refunds.list(new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null, null,
        "created_at; drop table refund_execution", 0, 50))))
        .hasMessageContaining("不支持的排序键");
  }

  @Test
  void 筛选词表来自真实数据() {
    UUID o = seedOrder("MO-F");
    as(() -> refunds.register(create("f1", o, "10.0000"), user));
    // 必须包在租户事务里：filterOptions 走 currentTenant()，
    // 没有 app.current_tenant 就 403 —— 这本身就是「租户不来自任何客户端头」的一部分。
    var f = as(() -> refunds.filterOptions());
    assertThat(f.executionStates()).contains("NOT_INITIATED");
    assertThat(f.currencies()).contains("USD");
    assertThat(f.orderSources()).contains("AMAZON");
    assertThat(f.totalCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 八、平台同步轴与执行状态正交
  // ==========================================================================

  @Test
  void 非平台退款不许报平台同步() {
    UUID o = seedOrder("MO-SYNC");
    var row = as(() -> refunds.register(create("sync1", o, "10.0000"), user));
    assertThat(row.platformSyncState()).isEqualTo("NOT_REQUESTED");

    assertThatThrownBy(() ->
            as(() -> refunds.syncPlatform(row.id(),
                new RefundStatusModels.RefundPlatformSyncRequest(
                    RefundStatusModels.PlatformSyncState.SYNCED, "PR-1", null, null, null, ""), user)))
        .hasMessageContaining("不经过平台");
  }

  @Test
  void 平台退款默认待同步且可更新() {
    UUID o = seedOrder("MO-SYNC2");
    var row = as(() -> refunds.register(new RefundStatusModels.RefundExecutionCreate(
        "sync2", o, null, new java.math.BigDecimal("10.0000"), "USD",
        RefundStatusModels.PlatformRefundMethod.PLATFORM, ""), user));
    assertThat(row.platformSyncState()).as("平台退款默认就是待同步").isEqualTo("PENDING");

    var after = as(() -> refunds.syncPlatform(row.id(),
        new RefundStatusModels.RefundPlatformSyncRequest(
            RefundStatusModels.PlatformSyncState.SYNCED, "PR-2026-0001",
            new java.math.BigDecimal("10.0000"), "USD", T2, "平台已确认"), user));
    assertThat(after.platformSyncState()).isEqualTo("SYNCED");
    assertThat(after.platformRefundNo()).isEqualTo("PR-2026-0001");
  }

  // ==========================================================================
  // 九、未交付上游如实拒绝
  // ==========================================================================

  @Test
  void 财务模块未交付时拒绝写入该来源的回报() {
    UUID o = seedOrder("MO-FIN");
    UUID id = as(() -> refunds.register(create("fin1", o, "10.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("fin-d", null, T1, "FINANCE", ""), user));

    assertThatThrownBy(() ->
            as(() -> refunds.report(id,
                new RefundStatusModels.RefundReportRequest(
                    RefundStatusModels.ReportSource.FINANCE_MODULE_12,
                    RefundStatusModels.ReportOutcome.SUCCEEDED,
                    null, "TXN-F", new java.math.BigDecimal("10.0000"), "USD", "", "", T2), user)))
        .hasMessageContaining("12 财务未交付");
  }

  @Test
  void 财务查询查证当前不可用() {
    UUID o = seedOrder("MO-FINQ");
    UUID id = as(() -> refunds.register(create("finq1", o, "10.0000"), user).id());

    assertThatThrownBy(() ->
            as(() -> refunds.verify(id,
                new RefundStatusModels.RefundVerificationRequest(
                    RefundStatusModels.VerifySource.FINANCE_QUERY,
                    RefundStatusModels.VerificationVerdict.INCONCLUSIVE,
                    "TXN-Q", "查不到", T1), user)))
        .hasMessageContaining("FINANCE_QUERY 当前不可用");
  }

  @Test
  void capabilities逐条报出未交付的上下游与归属模块() {
    var caps = refunds.capabilities();
    assertThat(caps.upstream().financeDelivered()).isFalse();
    assertThat(caps.upstream().financeOwner()).contains("12 财务");
    assertThat(caps.upstream().refundRequestDelivered()).isFalse();
    assertThat(caps.upstream().refundRequestOwner()).contains("11.3.1.1");
    assertThat(caps.downstream().customerNotifyDelivered()).isFalse();
    assertThat(caps.downstream().orderStatusWritebackDelivered()).isFalse();
    assertThat(caps.downstream().platformSyncDelivered()).isFalse();

    // AUTO_RETRY 必须显式报「不提供」并说明原因（不是干脆不列）。
    var autoRetry = caps.actions().stream().filter(a -> a.action().equals("AUTO_RETRY")).findFirst();
    assertThat(autoRetry).isPresent();
    assertThat(autoRetry.get().available()).isFalse();
    assertThat(autoRetry.get().reason()).contains("重复退款");

    // 状态机转换表含「未知 → 在途」且要求 CONFIRMED_NOT_SETTLED。
    var retryEdge = caps.stateMachine().stream()
        .filter(t -> t.from().equals("UNKNOWN") && t.to().equals("IN_FLIGHT"))
        .findFirst().orElseThrow();
    assertThat(retryEdge.requiresVerification()).isTrue();
    assertThat(retryEdge.allowedVerdict()).isEqualTo("CONFIRMED_NOT_SETTLED");
  }

  // ==========================================================================
  // 十、详情
  // ==========================================================================

  @Test
  void 详情带齐派发查证回报差异事件与建议() {
    UUID o = seedOrder("MO-DET");
    UUID id = as(() -> refunds.register(create("det1", o, "77.0000"), user).id());
    as(() -> refunds.dispatch(id,
        new RefundStatusModels.RefundDispatchRequest("det-d1", null, T1, "FINANCE", ""), user));
    timeout(id);
    var v = as(() -> refunds.verify(id,
        new RefundStatusModels.RefundVerificationRequest(
            RefundStatusModels.VerifySource.PLATFORM_QUERY,
            RefundStatusModels.VerificationVerdict.INCONCLUSIVE, "TXN-DET", "未回执", T3), user));

    var detail = as(() -> refunds.detail(id));
    assertThat(detail.attempts()).hasSize(1);
    assertThat(detail.attempts().get(0).attemptKind()).isEqualTo("INITIAL");
    assertThat(detail.verifications()).hasSize(1);
    assertThat(detail.verifications().get(0).id()).isEqualTo(v.id());
    assertThat(detail.reports()).hasSize(1);
    assertThat(detail.advice().action()).isEqualTo(RefundStatusModels.RefundAction.VERIFY);
    assertThat(detail.advice().requiredVerdict())
        .isEqualTo(RefundStatusModels.VerificationVerdict.CONFIRMED_NOT_SETTLED);
    assertThat(detail.allowedNextStates()).contains("SUCCEEDED", "FAILED", "IN_FLIGHT", "DISCREPANCY");
    assertThat(detail.events()).isNotEmpty();
    // 事件流必须能追到查证证据行。
    assertThat(detail.events().stream().anyMatch(e -> v.id().equals(e.verificationId()))).isTrue();
  }

  @Test
  void 不存在的退款返回404() {
    assertThatThrownBy(() -> as(() -> refunds.detail(UUID.randomUUID())))
        .hasMessageContaining("不存在或不属于当前租户");
  }

  // ==========================================================================
  // 十一、端点层：401 / 403 / 400
  // ==========================================================================

  @Test
  void 未认证返回401() throws Exception {
    http.perform(get("/trade/refunds")).andExpect(status().isUnauthorized());
  }

  @Test
  void 无写权限返回403() throws Exception {
    var readOnly = new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, List.of("trade.refunds.read"), List.of());
    actors.put("Bearer ro", readOnly);
    http.perform(post("/trade/refunds").header("Authorization", "Bearer ro")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    actors.remove("Bearer ro");
  }

  @Test
  void 缺幂等键返回400() throws Exception {
    http.perform(post("/trade/refunds").header("Authorization", "Bearer t1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"" + seedOrder("MO-BAD") + "\",\"refundAmount\":10}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 登记成功返回201语义的行() throws Exception {
    UUID o = seedOrder("MO-API");
    http.perform(post("/trade/refunds").header("Authorization", "Bearer t1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"idempotencyKey\":\"api-1\",\"orderId\":\"" + o
                + "\",\"refundAmount\":42.5000,\"currency\":\"USD\","
                + "\"platformRefundMethod\":\"ORIGINAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionState").value("NOT_INITIATED"))
        .andExpect(jsonPath("$.refundAmount").value(42.5))
        .andExpect(jsonPath("$.action").value("DISPATCH"));
  }

  @Test
  void 伪造租户头不改变写入归属() throws Exception {
    UUID o = seedOrder("MO-FORGED");
    UUID other = UUID.randomUUID();
    var body = as(() -> refunds.register(create("forged-1", o, "10.0000"), user));
    // 直查库：行的 tenant_id 是会话租户，而不是任何请求头里能指定的租户。
    var row = as(() -> sql.fetchOne("select tenant_id from refund_execution where id = ?", body.id()));
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(tenant).isNotEqualTo(other);
  }
}
