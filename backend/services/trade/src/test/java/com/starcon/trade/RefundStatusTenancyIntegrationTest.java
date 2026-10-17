package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 11.3.1.2 退款状态的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （refund_execution / refund_attempt / refund_verification / refund_execution_report /
 * refund_discrepancy / refund_status_event），<b>不是</b>占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的筛选与金额聚合查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：退款状态含退款金额、外部交易号与失败原因，
 * 漏掉租户谓词就是**跨租户资损泄露** —— A 家会看到 B 家「哪些买家正在退钱、卡在哪一步」，
 * 而 B 家连自己被拖住都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>金额汇总的租户独立性被单独断言</b>：退款是金额类数据，
 * 「A 家汇总里混进 B 家的钱」比「看到 B 家的退款单」更隐蔽也更致命。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundStatusTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.refunds.read", "trade.refunds.write");

  static AccessService.Access actor(UUID uid, UUID tenant) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
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
      actors.put("Bearer tenant-a", actor(userA, a));
      actors.put("Bearer tenant-b", actor(userB, b));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
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

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-04-01T08:00:00+08:00");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-04-02T09:00:00+08:00");

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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.refunds.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.refunds.write", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  /** 一张真实订单 + 一笔真实退款（走本模块自己的登记入口，不在测试里直接 INSERT 退款表）。 */
  UUID seedRefund(UUID tenant, String orderNo, String amount, String idempotencyKey) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
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
    return as(
        tenant,
        () ->
            refunds.register(
                    new RefundStatusModels.RefundExecutionCreate(
                        idempotencyKey, orderId, null, new BigDecimal(amount), "USD",
                        RefundStatusModels.PlatformRefundMethod.ORIGINAL, ""),
                    userA)
                .id());
  }

  RefundStatusModels.RefundExecutionQuery query(int page, int size) {
    return new RefundStatusModels.RefundExecutionQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, null, page, size);
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
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

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    seedRefund(a, "MO-A", "111.0000", "key-a-1");
    seedRefund(b, "MO-B", "222.0000", "key-b-1");

    assertThat(asA(() -> refunds.list(query(0, 50)).total())).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(() -> refunds.list(query(0, 50)).total())).isEqualTo(1);

    // 更强的一格：**按主键直查对方的退款单也拿不到**（不只是列表过滤）。
    var rowA = asA(() -> refunds.list(query(0, 50)).rows().get(0));
    var byIdB =
        asB(() -> sql.fetch("select id from refund_execution where id = ?", rowA.id()).size());
    assertThat(byIdB).as("按主键直查也必须被 RLS 挡住").isZero();

    // 用对方的 id 调详情同样是 404，而不是「静默成功」。
    assertThatThrownBy(() -> asB(() -> refunds.detail(rowA.id())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 外部交易号与失败原因同样不能跨租户读到（这两类字段泄露等于告诉对手谁正在退钱）。
    asA(() ->
        refunds.dispatch(
            rowA.id(),
            new RefundStatusModels.RefundDispatchRequest("d-a", null, T1, "FINANCE", ""),
            userA));
    asA(() ->
        refunds.report(
            rowA.id(),
            new RefundStatusModels.RefundReportRequest(
                RefundStatusModels.ReportSource.PLATFORM_SYNC,
                RefundStatusModels.ReportOutcome.TIMEOUT,
                RefundStatusModels.FailureClass.INDETERMINATE,
                "TXN-SECRET-A", null, null, "GATEWAY_TIMEOUT", "A 家买家的失败原因", T2),
            userA));
    var seenByB =
        asB(
            () ->
                sql.fetch(
                        "select external_transaction_ref, failure_reason from refund_execution where id = ?",
                        rowA.id())
                    .size());
    assertThat(seenByB).as("外部交易号与失败原因都不能被 B 家读到").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行仍落在会话租户名下() throws Exception {
    UUID id = seedRefund(a, "MO-FORGED", "333.0000", "key-forged");

    // ⚠️ 必须先拼好再 formatted：`.formatted()` 只作用于**紧邻的那一段**字符串字面量，
    //    写在拼接链的中间只会替换掉第一段，后面的 %s 原样进 JSON，
    //    于是报「UUID has to be represented by standard 36-char representation」——
    //    一个与「伪造租户头」毫无关系的报文，排查成本极高。
    String body =
        ("{\"idempotencyKey\":\"forged-key\",\"orderId\":\"%s\",\"refundAmount\":999.0000,"
                + "\"currency\":\"USD\",\"platformRefundMethod\":\"ORIGINAL\"}")
            .formatted(seedOrderId(a, "MO-FORGED2"));

    http.perform(post("/trade/refunds").header("Authorization", "Bearer tenant-a")
            // 伪造的租户头与内部键：后端必须完全忽略它们。
            .header("X-Tenant-Id", b.toString())
            .header("X-Internal-Key", "forged")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    // 写入的行仍属于**会话租户**（identity 解析出来的），而不是伪造头里的那个。
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select tenant_id from refund_execution where idempotency_key = ?",
                    "forged-key"));
    assertThat(row).isNotNull();
    assertThat(row.get(0, UUID.class))
        .as("伪造 X-Tenant-Id 改不动数据归属：租户来自已认证成员关系")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // B 家在自己的会话里看不到这一笔（伪造头声称是 B 家，但写进了 A 家）。
    assertThat(asB(() -> refunds.list(query(0, 50)).total()))
        .as("伪造头声称写给 B 家，B 家会话里却一笔也没有 —— 这一笔真落在 A 家名下")
        .isZero();
    assertThat(asA(() -> refunds.list(query(0, 50)).total()))
        .as("A 家会话里两笔：种子那一笔 + 伪造头那一笔")
        .isEqualTo(2);
    assertThat(id).isNotNull();
  }

  UUID seedOrderId(UUID tenant, String orderNo) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'SHOPIFY',?,'USD','PREPAID','SUBMITTED',?::timestamptz,100.0000,0,0,"
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

  // ==========================================================================
  // 断言 3：连接池归还后不串租户（连接池固定为 1 条）
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户且金额汇总各自独立() {
    seedRefund(a, "MO-P1", "10.0000", "p-a-1");
    seedRefund(a, "MO-P2", "20.0000", "p-a-2");
    seedRefund(b, "MO-P3", "30.0000", "p-b-1");
    seedRefund(b, "MO-P4", "40.0000", "p-b-2");

    // 交替 5 轮，每轮两个租户各读一次：连接池只有 1 条连接，
    // 同一连接被两个租户先后复用，读到的必须始终是自己那一份。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> refunds.list(query(0, 50)).total()))
          .as("第 " + i + " 轮：A 家在自己的连接上只看到 2 笔")
          .isEqualTo(2);
      assertThat(asB(() -> refunds.list(query(0, 50)).total()))
          .as("第 " + i + " 轮：B 家在同一连接上只看到 2 笔")
          .isEqualTo(2);

      // **金额汇总各自独立**：退款是金额类数据，A 家汇总里混进 B 家的钱
      // 比「看到 B 家的单号」更隐蔽也更致命，必须单独断言。
      assertThat(asA(() -> refunds.list(query(0, 50)).amountByCurrency().get("USD")))
          .as("第 " + i + " 轮：A 家汇总 10 + 20 = 30")
          .isEqualByComparingTo("30.0000");
      assertThat(asB(() -> refunds.list(query(0, 50)).amountByCurrency().get("USD")))
          .as("第 " + i + " 轮：B 家汇总 30 + 40 = 70")
          .isEqualByComparingTo("70.0000");
    }

    // 差异清单同样按租户隔离（它含两边的退款金额）。
    assertThat(asA(() -> refunds.listDiscrepancies(null, null, 0, 50).total())).isZero();
  }

  @Test
  void 差异清单按租户隔离() {
    UUID idA = seedRefund(a, "MO-D1", "100.0000", "d-a-1");
    asA(() ->
        refunds.dispatch(idA,
            new RefundStatusModels.RefundDispatchRequest("d-a-d", null, T1, "FINANCE", ""), userA));
    asA(() ->
        refunds.detectDiscrepancy(
            idA,
            new RefundStatusModels.RefundDiscrepancyCreate(
                RefundStatusModels.DiscrepancyKind.AMOUNT,
                new BigDecimal("100.0000"), "USD",
                RefundStatusModels.RefundExecutionState.IN_FLIGHT, null,
                new BigDecimal("90.0000"), "USD",
                RefundStatusModels.RefundExecutionState.SUCCEEDED, T2,
                "A 家平台少退了 10", T2),
            userA));

    assertThat(asA(() -> refunds.listDiscrepancies(null, null, 0, 50).total())).isEqualTo(1);
    assertThat(asB(() -> refunds.listDiscrepancies(null, null, 0, 50).total()))
        .as("B 家看不到 A 家的差异清单")
        .isZero();
  }

  @Test
  void 幂等键也按租户隔离() {
    // 同一个幂等键字符串，两个租户各自能登记一笔（不是跨租户撞键）。
    UUID oa = seedOrderId(a, "MO-KEY");
    seedRefund(b, "MO-KEY", "55.0000", "shared-key");
    UUID ba = asA(() -> refunds.register(
            new RefundStatusModels.RefundExecutionCreate(
                "shared-key", oa, null, new BigDecimal("66.0000"), "USD",
                RefundStatusModels.PlatformRefundMethod.ORIGINAL, ""),
            userA).id());
    assertThat(ba).isNotNull();
    assertThat(asA(() -> refunds.list(query(0, 50)).total())).isEqualTo(1);
    assertThat(asB(() -> refunds.list(query(0, 50)).total())).isEqualTo(1);
    assertThat(asA(() -> refunds.list(query(0, 50)).amountByCurrency().get("USD")))
        .isEqualByComparingTo("66.0000");
    assertThat(asB(() -> refunds.list(query(0, 50)).amountByCurrency().get("USD")))
        .isEqualByComparingTo("55.0000");
  }

  @Test
  void 事务外读零行() throws Exception {
    seedRefund(a, "MO-NOTX", "77.0000", "notx-1");
    // 不带租户上下文直接用运行角色查：RLS 策略里 current_setting 为空 ⇒ 零行。
    var rows = sql.fetch("select id from refund_execution");
    assertThat(rows.size()).as("没有 app.current_tenant 时必须读不到任何行").isZero();
  }

  @Test
  void 未认证与无权限返回401与403() throws Exception {
    http.perform(get("/trade/refunds")).andExpect(status().isUnauthorized());

    var readOnly = new AccessService.Access(
        userA, a, "Test", UUID.randomUUID(), null, List.of("trade.refunds.read"), List.of());
    actors.put("Bearer ro", readOnly);
    http.perform(post("/trade/refunds").header("Authorization", "Bearer ro")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    actors.remove("Bearer ro");
  }

  @Test
  void 端点在两个租户下各自返回自己的数据() throws Exception {
    seedRefund(a, "MO-API-A", "10.0000", "api-a-1");
    seedRefund(b, "MO-API-B", "20.0000", "api-b-1");

    http.perform(get("/trade/refunds").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.amountByCurrency.USD").value(10.0))
        .andExpect(jsonPath("$.rows[0].refundAmount").value(10.0));

    http.perform(get("/trade/refunds").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.amountByCurrency.USD").value(20.0))
        .andExpect(jsonPath("$.rows[0].refundAmount").value(20.0));
  }
}
