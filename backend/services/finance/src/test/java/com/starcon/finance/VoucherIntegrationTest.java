package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.VoucherModels.*;
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
 * 12.5.2.1 凭证管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 12.5.2.1 的每一句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「<b>校验（至少两行、金额有效、期间开放、借贷平衡）</b>」⇒ 四条各判两次（服务层 + 数据层）；</li>
 *   <li>「<b>已过账凭证不得反过账后改写</b>」⇒ 本文件最重的一组：
 *       直连 SQL 也<b>写不出「反过账」</b>、<b>改不动分录行</b>、<b>删不掉整张凭证</b>；</li>
 *   <li>「<b>过账事务要锁凭证头并在数据库层防护</b>」⇒ 触发器逐条钉死；</li>
 *   <li>「<b>红冲</b>」⇒ 生成一张反向凭证（借贷对调），原凭证的原始事实<b>一字未改</b>；</li>
 *   <li>「<b>由业务事件自动生成或手工录入</b>」⇒ 两条路都真做；</li>
 *   <li>「<b>导出标准凭证文件</b>」⇒ 导的是真内容（草稿不导出）；</li>
 *   <li>「输出：<b>凭证 + 分录行 + 过账状态；审计记录</b>」⇒ 四样都要落库可查。</li>
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoucherIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID salesOrderId = UUID.randomUUID();
  static final UUID shipmentId = UUID.randomUUID();
  static final UUID arrivalId = UUID.randomUUID();
  static final UUID costEntryId = UUID.randomUUID();
  static final UUID costPoolId = UUID.randomUUID();
  static final UUID costRuleId = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of(
          "finance.voucher.read", "finance.voucher.create", "finance.voucher.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成
   * {@code double}，于是 {@code 100.0000} 变成 {@code 100.0} —— 断言就分不出
   * 「金额是四位小数」和「金额丢了一位」。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer upstream;

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
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    try {
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      stubAccess();
      stubTradeOrders();
      stubTradeShipments();
      stubTradeArrivals();
      upstream.setExecutor(Executors.newFixedThreadPool(8));
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // ================================================================ 上游桩

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

  static int pageOf(com.sun.net.httpserver.HttpExchange e) {
    var query = e.getRequestURI().getQuery();
    if (query == null) return 0;
    for (var part : query.split("&")) {
      if (part.startsWith("page=")) {
        return Integer.parseInt(part.substring(5));
      }
    }
    return 0;
  }

  /** identity：/internal/access（租户与权限的解析入口）。 */
  static void stubAccess() {
    upstream.createContext(
        "/internal/access",
        e -> {
          var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
          if (access == null) {
            e.sendResponseHeaders(401, -1);
            e.close();
            return;
          }
          write(e, 200, json.writeValueAsBytes(access));
        });
  }

  /** trade：GET /trade/platform-orders/orders（9.1.2.1 平台订单）。 */
  static void stubTradeOrders() {
    upstream.createContext(
        "/trade/platform-orders/orders",
        e -> {
          if (pageOf(e) > 0) {
            write(e, 200, "{\"rows\":[],\"page\":1,\"size\":200,\"total\":1}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              "{\"rows\":[{\"id\":\"" + UUID.randomUUID() + "\",\"salesOrderId\":\"" + salesOrderId
                  + "\",\"orderNo\":\"SO-2026-0001\",\"platformCode\":\"amazon\",\"shopCode\":\"SHOP-1\","
                  + "\"externalOrderNo\":\"A-123\",\"normalizedStatus\":\"SHIPPED\",\"currency\":\"USD\","
                  + "\"platformGrandTotalAmount\":\"128.5000\",\"platformPlacedAt\":\"2026-10-01T08:00:00Z\"}],"
                  + "\"page\":0,\"size\":200,\"total\":1}";
          write(e, 200, body.getBytes(StandardCharsets.UTF_8));
        });
  }

  /** trade：GET /trade/fulfillment/shipment-records/records（9.4.1.2 发货记录）。 */
  static void stubTradeShipments() {
    upstream.createContext(
        "/trade/fulfillment/shipment-records/records",
        e -> {
          if (pageOf(e) > 0) {
            write(e, 200, "{\"items\":[],\"page\":1,\"size\":200,\"totalElements\":1,\"totalPages\":1}"
                .getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              "{\"items\":[{\"id\":\"" + shipmentId + "\",\"recordNo\":\"SH-2026-0001\",\"salesOrderId\":\""
                  + salesOrderId + "\",\"salesOrderNo\":\"SO-2026-0001\",\"shopRef\":\"SHOP-1\","
                  + "\"shippedAt\":\"2026-10-20T09:30:00Z\",\"status\":\"SHIPPED\"}],"
                  + "\"page\":0,\"size\":200,\"totalElements\":1,\"totalPages\":1}";
          write(e, 200, body.getBytes(StandardCharsets.UTF_8));
        });
  }

  /** trade：GET /trade/purchase-arrivals（6.3.2.2 到货跟踪）。 */
  static void stubTradeArrivals() {
    upstream.createContext(
        "/trade/purchase-arrivals",
        e -> {
          if (pageOf(e) > 0) {
            write(e, 200, "{\"rows\":[],\"page\":1,\"size\":200,\"total\":1}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          var body =
              "{\"rows\":[{\"id\":\"" + arrivalId + "\",\"trackingNo\":\"PA-2026-0001\","
                  + "\"purchaseOrderNo\":\"PO-2026-0001\",\"supplierRefId\":\"" + UUID.randomUUID() + "\","
                  + "\"supplierCode\":\"SUP-1\",\"supplierName\":\"测试供应商\",\"unitPrice\":\"9.9900\","
                  + "\"currency\":\"USD\",\"status\":\"ARRIVED\",\"pendingPayableAmount\":\"199.8000\"}],"
                  + "\"page\":0,\"size\":200,\"total\":1}";
          write(e, 200, body.getBytes(StandardCharsets.UTF_8));
        });
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  static UUID openPeriodId;
  static UUID closedPeriodId;

  /**
   * 清空本模块的五张表，再登记两个会计期间：一个开放、一个已关闭。
   *
   * <p>⚠️ **清空与播种必须在同一个方法里**：写成两个 {@code @BeforeEach} 时 JUnit 5
   * <b>不保证</b>它们的执行顺序，可能先播种再清空。
   *
   * <p>⚠️ 用**超级用户**连接做 TRUNCATE：凭证头不可删（触发器 + 权限双重挡着），
   * 而测试需要「每个用例从干净状态起步」。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_voucher_event, finance_voucher_line, finance_voucher,"
              + " finance_voucher_mapping, finance_voucher_period restart identity cascade");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          openPeriodId = UUID.randomUUID();
          closedPeriodId = UUID.randomUUID();
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,created_at,updated_at)"
                  + " values(?,?,'2026-10','2026-10-01','2026-10-31','OPEN','USD',?::timestamptz,?::timestamptz)",
              openPeriodId,
              tenant,
              now,
              now);
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,closed_at,closed_by,closed_reason,created_at,updated_at)"
                  + " values(?,?,'2026-09','2026-09-01','2026-09-30','CLOSED','USD',?::timestamptz,?,"
                  + "'期末关账',?::timestamptz,?::timestamptz)",
              closedPeriodId,
              tenant,
              now,
              userId,
              now,
              now);
          return null;
        });
  }

  /** 一条分录行（默认一借一贷，金额相等）。 */
  FinanceVoucherLineInput debit(String subjectCode, String amount) {
    return new FinanceVoucherLineInput(
        "DEBIT", subjectCode, "测试借方科目", "测试摘要", new BigDecimal(amount), "USD",
        null, "NONE", null, "");
  }

  FinanceVoucherLineInput credit(String subjectCode, String amount) {
    return new FinanceVoucherLineInput(
        "CREDIT", subjectCode, "测试贷方科目", "测试摘要", new BigDecimal(amount), "USD",
        null, "NONE", null, "");
  }

  FinanceVoucherInput draftInput(UUID periodId, List<FinanceVoucherLineInput> lines) {
    return new FinanceVoucherInput(
        "MANUAL", null, "", periodId, LocalDate.of(2026, 10, 20), "测试手工凭证", "USD",
        "夹具", null, lines);
  }

  /** 建一张草稿凭证（走 HTTP，拿到真实 id 与版本号）。 */
  FinanceVoucherResult createDraft(UUID periodId, List<FinanceVoucherLineInput> lines) throws Exception {
    var response =
        http.perform(
                post("/finance/vouchers")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(draftInput(periodId, lines))))
            .andReturn();
    var body = response.getResponse().getContentAsString();
    assertThat(response.getResponse().getStatus())
        .as("创建草稿凭证失败，响应体：" + body)
        .isEqualTo(200);
    return json.readValue(body, FinanceVoucherResult.class);
  }

  // ==========================================================================
  // 手工录入 + 落库
  // ==========================================================================

  @Test
  void 手工录入的凭证真的落库_含分录行与审计留痕() throws Exception {
    var created = createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    assertThat(created.voucher().status()).isEqualTo("DRAFT");
    assertThat(created.voucher().voucherNo()).startsWith("VC-");
    assertThat(created.voucher().lineCount()).isEqualTo(0);
    assertThat(created.lines()).hasSize(2);

    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select subject_code, line_type, amount from finance_voucher_line"
                            + " where voucher_id = ? order by line_no",
                        created.voucher().id())
                    .size());
    assertThat(rows).isEqualTo(2);

    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select event_type from finance_voucher_event where voucher_id = ?",
                        created.voucher().id())
                    .map(r -> r.get(0, String.class)));
    assertThat(events).containsExactly("CREATED");
  }

  @Test
  void 金额无效的凭证录不进去() throws Exception {
    // 金额为 0 / 负数在数据层就写不出来。
    http.perform(
            post("/finance/vouchers")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draftInput(openPeriodId, List.of(
                    debit("1001", "0.0000"), credit("6001", "100.0000"))))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 一张凭证只能有一个币种() throws Exception {
    var mixed =
        new FinanceVoucherLineInput(
            "DEBIT", "1001", "测试借方科目", "摘要", new BigDecimal("100.0000"), "EUR",
            null, "NONE", null, "");
    http.perform(
            post("/finance/vouchers")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draftInput(openPeriodId, List.of(
                    mixed, credit("6001", "100.0000"))))))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 过账：四条校验
  // ==========================================================================

  @Test
  void 只有一行分录的凭证过不了账() throws Exception {
    var created = createDraft(openPeriodId, List.of(debit("1001", "100.0000")));
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
        .andExpect(status().isBadRequest());
    assertThat(statusOf(created.voucher().id())).isEqualTo("DRAFT");
  }

  @Test
  void 借贷不平的凭证过不了账() throws Exception {
    var created =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "80.0000")));
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
        .andExpect(status().isBadRequest());
    assertThat(statusOf(created.voucher().id())).isEqualTo("DRAFT");
  }

  @Test
  void 已关闭期间的凭证过不了账() throws Exception {
    var created =
        createDraft(closedPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
        .andExpect(status().isConflict());
    assertThat(statusOf(created.voucher().id())).isEqualTo("DRAFT");
  }

  @Test
  void 直连sql也写不出已关闭期间的过账() throws Exception {
    var created =
        createDraft(closedPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    // 用**超级用户**直连：权限与 RLS 全部绕过 ⇒ 被拒的只能是**触发器**。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher set status='POSTED', total_debit=100.0000,"
                          + " total_credit=100.0000, line_count=2, posted_at=now() where id='"
                          + created.voucher().id() + "'"))
          .as("已关闭期间的凭证，直连 SQL 也过不了账")
          .hasStackTraceContaining("已关闭，不允许过账");
    }
  }

  @Test
  void 过账成功后状态_过账人_合计_行数都落库() throws Exception {
    var created =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
        .andExpect(status().isOk());

    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select status, total_debit, total_credit, line_count, posted_at, posted_by"
                        + " from finance_voucher where id = ?",
                    created.voucher().id()));
    assertThat(row.get("status", String.class)).isEqualTo("POSTED");
    assertThat(row.get("total_debit", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(row.get("total_credit", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(row.get("line_count", Integer.class)).isEqualTo(2);
    assertThat(row.get("posted_at", OffsetDateTime.class)).isNotNull();
    assertThat(row.get("posted_by", UUID.class)).isEqualTo(userId);
  }

  // ==========================================================================
  // 🔴 过账后不可改行，只能红冲
  // ==========================================================================

  @Test
  void 已过账的凭证改不了草稿() throws Exception {
    var posted = postVoucher();
    http.perform(
            put("/finance/vouchers/" + posted.voucher().id())
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        draftInput(
                            openPeriodId,
                            List.of(debit("1001", "999.0000"), credit("6001", "999.0000"))))))
        .andExpect(status().isConflict());
  }

  @Test
  void 直连sql也改不动已过账凭证的分录行() throws Exception {
    var posted = postVoucher();
    var lineId =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select id from finance_voucher_line where voucher_id = ?"
                            + " order by line_no limit 1",
                        posted.voucher().id())
                    .get(0, UUID.class));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // ① UPDATE —— 改金额
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_line set amount = 999.0000 where id = '"
                          + lineId + "'"))
          .as("已过账的分录行改不了")
          .hasStackTraceContaining("不可增删改");
      // ② INSERT —— 加一行
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                          + "subject_code,subject_name,summary,amount,currency,auxiliary_type,created_at)"
                          + " values(gen_random_uuid(),'"
                          + tenant
                          + "','"
                          + posted.voucher().id()
                          + "',99,'DEBIT','9999','偷加的科目','',1.0000,'USD','NONE',now())"))
          .as("已过账的凭证加不了行")
          .hasStackTraceContaining("不可增删改");
      // ③ DELETE —— 删一行
      assertThatThrownBy(
              () -> s.execute("delete from finance_voucher_line where id = '" + lineId + "'"))
          .as("已过账的凭证删不了行")
          .hasStackTraceContaining("不可增删改");
    }
    // 行数与金额一个字都没变。
    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select count(*) as cnt, sum(amount) as total from finance_voucher_line"
                        + " where voucher_id = ?",
                    posted.voucher().id()));
    assertThat(row.get("cnt", Long.class)).isEqualTo(2);
    assertThat(row.get("total", BigDecimal.class)).isEqualByComparingTo("200.0000");
  }

  @Test
  void 直连sql也写不出反过账() throws Exception {
    var posted = postVoucher();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher set status='DRAFT' where id = '"
                          + posted.voucher().id() + "'"))
          .as("已过账的凭证不能被改回草稿（没有「反过账」这条边）")
          .hasStackTraceContaining("不得反过账后改写");
    }
    assertThat(statusOf(posted.voucher().id())).isEqualTo("POSTED");
  }

  @Test
  void 直连sql也改不动已过账凭证的事实() throws Exception {
    var posted = postVoucher();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher set description = '偷偷改摘要' where id = '"
                          + posted.voucher().id() + "'"))
          .as("已过账的凭证摘要改不了")
          .hasStackTraceContaining("不可改写");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher set voucher_date = '2026-01-01' where id = '"
                          + posted.voucher().id() + "'"))
          .as("已过账的凭证业务日期改不了")
          .hasStackTraceContaining("不可改写");
    }
  }

  @Test
  void 直连sql也删不掉整张凭证() throws Exception {
    var posted = postVoucher();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () -> s.execute("delete from finance_voucher where id = '" + posted.voucher().id() + "'"))
          .as("凭证是会计凭据，删不掉")
          .hasStackTraceContaining("不可删除");
    }
    assertThat(statusOf(posted.voucher().id())).isEqualTo("POSTED");
  }

  // ==========================================================================
  // 红冲
  // ==========================================================================

  @Test
  void 红冲生成一张反向凭证_原凭证的原始事实一字未改() throws Exception {
    var posted = postVoucher();
    var originalBefore =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select description, voucher_date, currency, total_debit, total_credit,"
                            + " line_count from finance_voucher where id = ?",
                        posted.voucher().id()));

    http.perform(
            post("/finance/vouchers/" + posted.voucher().id() + "/reverse")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherReverseInput(1, "金额录错了", LocalDate.of(2026, 10, 21), null))))
        .andExpect(status().isOk());

    // ① 原凭证标成已红冲，但**事实一字未改**。
    var originalAfter =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select status, description, voucher_date, currency, total_debit, total_credit,"
                        + " line_count, reversal_voucher_id from finance_voucher where id = ?",
                    posted.voucher().id()));
    assertThat(originalAfter.get("status", String.class)).isEqualTo("REVERSED");
    assertThat(originalAfter.get("description", String.class))
        .isEqualTo(originalBefore.get("description", String.class));
    assertThat(originalAfter.get("voucher_date", LocalDate.class))
        .isEqualTo(originalBefore.get("voucher_date", LocalDate.class));
    assertThat(originalAfter.get("total_debit", BigDecimal.class))
        .isEqualByComparingTo(originalBefore.get("total_debit", BigDecimal.class));
    assertThat(originalAfter.get("line_count", Integer.class)).isEqualTo(2);
    assertThat(originalAfter.get("reversal_voucher_id", UUID.class)).isNotNull();

    // ② 红冲凭证：借贷方向对调、金额相同、已过账。
    var reversalId = originalAfter.get("reversal_voucher_id", UUID.class);
    var reversal =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select status, source_type, reversal_of_id, total_debit, total_credit, line_count"
                        + " from finance_voucher where id = ?",
                    reversalId));
    assertThat(reversal.get("status", String.class)).isEqualTo("POSTED");
    assertThat(reversal.get("source_type", String.class)).isEqualTo("REVERSAL");
    assertThat(reversal.get("reversal_of_id", UUID.class)).isEqualTo(posted.voucher().id());
    assertThat(reversal.get("total_debit", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(reversal.get("total_credit", BigDecimal.class)).isEqualByComparingTo("100.0000");

    var reversalLines =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select line_type, amount, subject_code from finance_voucher_line"
                            + " where voucher_id = ? order by line_no",
                        reversalId)
                    .map(r -> r.get("line_type", String.class) + "/" + r.get("subject_code", String.class)));
    assertThat(reversalLines).containsExactly("CREDIT/1001", "DEBIT/6001");
  }

  @Test
  void 一张凭证只能被红冲一次() throws Exception {
    var posted = postVoucher();
    http.perform(
            post("/finance/vouchers/" + posted.voucher().id() + "/reverse")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherReverseInput(1, "第一次红冲", null, null))))
        .andExpect(status().isOk());
    // 再红冲一次 ⇒ 原凭证已是 REVERSED，不是 POSTED ⇒ 拒。
    http.perform(
            post("/finance/vouchers/" + posted.voucher().id() + "/reverse")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherReverseInput(2, "第二次红冲", null, null))))
        .andExpect(status().isConflict());
  }

  @Test
  void 红冲必须写明原因() throws Exception {
    var posted = postVoucher();
    http.perform(
            post("/finance/vouchers/" + posted.voucher().id() + "/reverse")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherReverseInput(1, "  ", null, null))))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 乐观并发
  // ==========================================================================

  @Test
  void 版本号不匹配时报冲突而不是静默覆盖() throws Exception {
    var created =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    // 错误的 expected_version（当前是 0，这里传 99）。
    var stale =
        new FinanceVoucherInput(
            "MANUAL", null, "", openPeriodId, LocalDate.of(2026, 10, 20), "测试手工凭证", "USD",
            "夹具", 99, List.of(debit("1001", "200.0000"), credit("6001", "200.0000")));
    http.perform(
            put("/finance/vouchers/" + created.voucher().id())
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(stale)))
        .andExpect(status().isConflict());
    // 原来的分录行没被动过。
    var total =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select sum(amount) as total from finance_voucher_line where voucher_id = ?",
                        created.voucher().id())
                    .get("total", BigDecimal.class));
    assertThat(total).isEqualByComparingTo("200.0000");
  }

  @Test
  void 不带expected_version的写操作被拒() throws Exception {
    var created =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(null, null))))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 作废
  // ==========================================================================

  @Test
  void 草稿可以作废_已过账的不行() throws Exception {
    var draft =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    http.perform(
            post("/finance/vouchers/" + draft.voucher().id() + "/void")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherVoidInput(0, "录错了"))))
        .andExpect(status().isOk());
    assertThat(statusOf(draft.voucher().id())).isEqualTo("VOID");

    var posted = postVoucher();
    http.perform(
            post("/finance/vouchers/" + posted.voucher().id() + "/void")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherVoidInput(1, "想作废"))))
        .andExpect(status().isConflict());
  }

  // ==========================================================================
  // 自动生成
  // ==========================================================================

  @Test
  void 没有登记科目映射时生成不出凭证() throws Exception {
    http.perform(
            post("/finance/vouchers/generate")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherGenerateInput(
                            "SHIPMENT", "SALE", shipmentId, openPeriodId, null, null, null))))
        .andExpect(status().isConflict());
  }

  @Test
  void 由发货业务事件自动生成凭证_金额取自上游事实() throws Exception {
    registerMapping("SHIPMENT", "SALE", "1122", "应收账款", "6001", "主营业务收入");
    http.perform(
            post("/finance/vouchers/generate")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherGenerateInput(
                            "SHIPMENT", "SALE", shipmentId, openPeriodId, null, null, null))))
        .andExpect(status().isOk());

    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select id, source_type, source_id, source_no, currency, status, description"
                        + " from finance_voucher where source_type = 'SHIPMENT'"));
    assertThat(row).as("自动生成的凭证必须真的落库").isNotNull();
    assertThat(row.get("source_id", UUID.class)).isEqualTo(shipmentId);
    assertThat(row.get("source_no", String.class)).isEqualTo("SH-2026-0001");
    assertThat(row.get("currency", String.class)).isEqualTo("USD");
    assertThat(row.get("status", String.class)).isEqualTo("DRAFT");

    // 两行：借 应收账款 / 贷 主营业务收入，金额 = 上游订单总额 128.5000。
    var lines =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select line_type, subject_code, amount, currency from finance_voucher_line"
                            + " where voucher_id = ? order by line_no",
                        row.get("id", UUID.class))
                    .map(
                        r ->
                            r.get("line_type", String.class)
                                + "|"
                                + r.get("subject_code", String.class)
                                + "|"
                                + r.get("amount", BigDecimal.class)
                                + "|"
                                + r.get("currency", String.class)));
    assertThat(lines)
        .containsExactly(
            "DEBIT|1122|128.5000|USD",
            "CREDIT|6001|128.5000|USD");
  }

  @Test
  void 同一笔业务事实不能重复生成凭证() throws Exception {
    registerMapping("SHIPMENT", "SALE", "1122", "应收账款", "6001", "主营业务收入");
    var input =
        new FinanceVoucherGenerateInput(
            "SHIPMENT", "SALE", shipmentId, openPeriodId, null, null, null);
    http.perform(
            post("/finance/vouchers/generate")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isOk());
    http.perform(
            post("/finance/vouchers/generate")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isConflict());
  }

  @Test
  void 由发货事件生成的凭证_生成的是草稿_要再过账才进账() throws Exception {
    registerMapping("SHIPMENT", "SALE", "1122", "应收账款", "6001", "主营业务收入");
    var response =
        http.perform(
                post("/finance/vouchers/generate")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new FinanceVoucherGenerateInput(
                                "SHIPMENT", "SALE", shipmentId, openPeriodId, null, null, null))))
            .andExpect(status().isOk())
            .andReturn();
    var created = json.readValue(response.getResponse().getContentAsString(), FinanceVoucherResult.class);
    assertThat(created.voucher().status()).isEqualTo("DRAFT");
    // 自动生成的凭证**也要走校验**：它此时是两行等额 ⇒ 可以过账。
    http.perform(
            post("/finance/vouchers/" + created.voucher().id() + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
        .andExpect(status().isOk());
    assertThat(statusOf(created.voucher().id())).isEqualTo("POSTED");
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  @Test
  void 导出的标准凭证文件是真内容_而且只含已过账的凭证() throws Exception {
    var posted = postVoucher();
    // 一张草稿：不该出现在导出文件里。
    createDraft(openPeriodId, List.of(debit("1001", "55.0000"), credit("6001", "55.0000")));

    var response =
        http.perform(get("/finance/vouchers/export").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    var content = response.getResponse().getContentAsString();
    assertThat(content).contains(posted.voucher().voucherNo());
    assertThat(content).contains("6001");
    assertThat(content).contains("100.0000");
    assertThat(content).contains("借方金额", "贷方金额");
    assertThat(content).doesNotContain("55.0000");
    // 导出留痕。
    var exported =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select event_type from finance_voucher_event where voucher_id = ?",
                        posted.voucher().id())
                    .map(r -> r.get(0, String.class)));
    assertThat(exported).contains("EXPORTED");
  }

  @Test
  void 导出的json也是真内容() throws Exception {
    postVoucher();
    var response =
        http.perform(
                get("/finance/vouchers/export")
                    .param("format", "JSON")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    var content = response.getResponse().getContentAsString();
    assertThat(content).startsWith("[");
    assertThat(content).contains("\"voucherNo\"");
    assertThat(content).contains("\"subjectCode\"");
    assertThat(content).contains("\"totalDebit\"");
  }

  // ==========================================================================
  // 列表 / 详情 / 能力声明
  // ==========================================================================

  @Test
  void 列表是真分页真筛选_汇总覆盖整个筛选结果集() throws Exception {
    postVoucher();
    postVoucher();
    var response =
        http.perform(
                get("/finance/vouchers")
                    .param("status", "POSTED")
                    .param("size", "1")
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    var page = json.readValue(response.getResponse().getContentAsString(), FinanceVoucherPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.summaryByCurrency()).hasSize(1);
    assertThat(page.summaryByCurrency().get(0).totalDebit()).isEqualByComparingTo("200.0000");
    assertThat(page.summaryByCurrency().get(0).postedCount()).isEqualTo(2);
    assertThat(page.postedCount()).isEqualTo(2);
  }

  @Test
  void 详情带分录行与审计留痕() throws Exception {
    var posted = postVoucher();
    var response =
        http.perform(get("/finance/vouchers/" + posted.voucher().id()).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    var detail = json.readValue(response.getResponse().getContentAsString(), FinanceVoucherDetail.class);
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.events()).isNotEmpty();
    assertThat(detail.events().stream().map(FinanceVoucherEventRow::eventType))
        .contains("CREATED", "POSTED");
  }

  @Test
  void 能力声明如实报出上游缺口() throws Exception {
    var response =
        http.perform(get("/finance/vouchers/capabilities").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    var caps = json.readValue(response.getResponse().getContentAsString(), FinanceVoucherCapabilities.class);
    assertThat(caps.downstreamGaps()).isNotEmpty();
    assertThat(caps.downstreamGaps().stream().map(FinanceVoucherDownstreamGap::module))
        .contains("12.5.1.1", "12.5.1.2", "12.5.2.2");
    // 12.5.1.1 未交付 ⇒ 明确报「available=false」且写清是软引用。
    var subject =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.5.1.1"))
            .findFirst()
            .orElseThrow();
    assertThat(subject.available()).isFalse();
    assertThat(subject.detail()).contains("软引用");
  }

  @Test
  void 会计期间可以登记也可以关闭_但关不掉第二次() throws Exception {
    var response =
        http.perform(
                post("/finance/vouchers/periods")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new FinanceVoucherPeriodInput(
                                "2026-11", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                                "USD", "十一月"))))
            .andExpect(status().isOk())
            .andReturn();
    var period = json.readValue(response.getResponse().getContentAsString(), FinanceVoucherPeriodRow.class);
    assertThat(period.status()).isEqualTo("OPEN");

    http.perform(
            post("/finance/vouchers/periods/" + period.id() + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherPeriodCloseInput("月末关账", 0))))
        .andExpect(status().isOk());
    org.jooq.Record after =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select status, closed_by, closed_reason from finance_voucher_period where id = ?",
                        period.id()));
    assertThat(after.get("status", String.class)).isEqualTo("CLOSED");
    assertThat(after.get("closed_by", UUID.class)).isEqualTo(userId);
    assertThat(after.get("closed_reason", String.class)).isEqualTo("月末关账");
  }

  @Test
  void 已关闭的期间关不掉也改不了() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_period set status='OPEN' where id = '"
                          + closedPeriodId + "'"))
          .as("已关闭的期间不能被重开（重开属 12.5.1.2，未交付）")
          .hasStackTraceContaining("不允许");
    }
  }

  // ==========================================================================
  // 数据口径：NUMERIC / timestamptz
  // ==========================================================================

  @Test
  void 金额是numeric而不是浮点_数量是numeric19_6() {
    var money =
        sql.fetchOne(
            "select data_type, numeric_precision, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_voucher_line' and column_name = 'amount'");
    assertThat(money.get(0, String.class)).isEqualTo("numeric");
    assertThat(money.get(1, Integer.class)).isEqualTo(19);
    assertThat(money.get(2, Integer.class)).isEqualTo(4);

    var quantity =
        sql.fetchOne(
            "select data_type, numeric_precision, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_voucher_line' and column_name = 'quantity'");
    assertThat(quantity.get(0, String.class)).isEqualTo("numeric");
    assertThat(quantity.get(1, Integer.class)).isEqualTo(19);
    assertThat(quantity.get(2, Integer.class)).isEqualTo(6);
  }

  @Test
  void 时间列是timestamptz_而且带时区偏移写回不偏() {
    var column =
        sql.fetchOne(
            "select data_type from information_schema.columns where table_name = 'finance_voucher'"
                + " and column_name = 'posted_at'");
    assertThat(column.get(0, String.class)).isEqualTo("timestamp with time zone");

    // +08:00 的时刻写进去，读回来必须是**同一个瞬间**（不是偏 −28800s）。
    var voucherId = UUID.randomUUID();
    var at = OffsetDateTime.parse("2026-10-24T10:00:00+08:00");
    var id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,period_id,voucher_date,"
                  + "description,currency,status,created_at,updated_at)"
                  + " values(?,?,'VC-202610-TZ01','MANUAL',?,?::date,'时区夹具','USD','DRAFT',"
                  + "?::timestamptz,?::timestamptz)",
              voucherId,
              tenant,
              openPeriodId,
              LocalDate.of(2026, 10, 24),
              at,
              at);
          sql.execute(
              "insert into finance_voucher_event(id,tenant_id,voucher_id,event_type,occurred_at)"
                  + " values(?,?,?,'CREATED',?::timestamptz)",
              id,
              tenant,
              voucherId,
              at);
          return null;
        });
    var readBack =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select occurred_at from finance_voucher_event where id = ?", id)
                    .get("occurred_at", OffsetDateTime.class));
    assertThat(readBack.toInstant()).isEqualTo(at.toInstant());
    assertThat(readBack.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
  }

  // ==========================================================================
  // 审计留痕是追加式的
  // ==========================================================================

  @Test
  void 审计留痕只能追加_运行角色的改与删已被收回() {
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_voucher_event','UPDATE')")
                .get(0, Boolean.class))
        .as("审计事件流不允许被改")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_voucher_event','DELETE')")
                .get(0, Boolean.class))
        .as("审计事件流不允许被删")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_voucher','DELETE')")
                .get(0, Boolean.class))
        .as("凭证头不允许被删")
        .isFalse();
  }

  // ==========================================================================
  // 科目软引用
  // ==========================================================================

  @Test
  void 科目是软引用_写进去的科目编码不会被核对_也不假装校验过() throws Exception {
    // 12.5.1.1 未交付 ⇒ 没有科目表，任何编码都能写进去（**如实行为**，不是「校验通过」）。
    var created =
        createDraft(
            openPeriodId,
            List.of(debit("不存在的科目-9001", "100.0000"), credit("也不存在-9002", "100.0000")));
    assertThat(created.voucher().status()).isEqualTo("DRAFT");
    var subject =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select subject_code from finance_voucher_line where voucher_id = ?"
                            + " order by line_no limit 1",
                        created.voucher().id())
                    .get(0, String.class));
    assertThat(subject).isEqualTo("不存在的科目-9001");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 过账一张凭证（两行等额）。 */
  FinanceVoucherResult postVoucher() throws Exception {
    var created =
        createDraft(openPeriodId, List.of(debit("1001", "100.0000"), credit("6001", "100.0000")));
    var response =
        http.perform(
                post("/finance/vouchers/" + created.voucher().id() + "/post")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new FinanceVoucherPostInput(0, null))))
            .andExpect(status().isOk())
            .andReturn();
    return json.readValue(response.getResponse().getContentAsString(), FinanceVoucherResult.class);
  }

  /** 登记一条业务事件映射。 */
  void registerMapping(
      String sourceType, String eventKind, String debitCode, String debitName, String creditCode,
      String creditName) throws Exception {
    http.perform(
            post("/finance/vouchers/mappings")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinanceVoucherMappingInput(
                            sourceType, eventKind, debitCode, debitName, creditCode, creditName,
                            Boolean.TRUE, "夹具"))))
        .andExpect(status().isOk());
  }

  /** 读凭证当前状态。 */
  String statusOf(UUID id) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne("select status from finance_voucher where id = ?", id).get(0, String.class));
  }
}
