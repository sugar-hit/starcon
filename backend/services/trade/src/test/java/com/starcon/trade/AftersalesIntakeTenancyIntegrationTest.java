package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 11.1.1.3 售后受理的<b>多租户隔离</b>三条断言。
 *
 * <p>本模块的隔离风险比兄弟模块更高一层：判定凭据里<b>冻结着政策参数原文与必退结论</b> ——
 * 漏掉租户谓词的后果不是「看到别人的订单号」，而是「看到别人的必退口径与判责风险」。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>，否则测的是幂等不是隔离：
 * 拿上一轮已经写过的单再读一遍，命中与否和租户无关。
 *
 * <p><b>断言 2 的预期是「仍然 200」而不是 4xx</b>：伪造的 {@code X-Tenant-Id} 头
 * 根本进不来（租户来自已认证成员关系），所以请求会**按会话里的真实成员关系正常执行**，
 * 写入落在**会话租户**名下，而对方租户的数据**逐字节未变**。
 * 断言的是两侧数据库，不是状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesIntakeTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tenant) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
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
      identity.createContext(
          "/identity/business-parameters/effective",
          e -> {
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("parameterCode", AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS);
            window.put("valueType", "INTEGER");
            window.put("value", "30");
            window.put("valueNumeric", java.math.BigDecimal.valueOf(30));
            window.put("valueCodes", List.of());
            window.put("sourceScope", "GLOBAL");
            out.add(window);
            Map<String, Object> mustRefund = new LinkedHashMap<>();
            mustRefund.put("parameterCode", AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS);
            mustRefund.put("valueType", "MULTI_ENUM");
            mustRefund.put("value", "QUALITY_DEFECT");
            mustRefund.put("valueNumeric", null);
            mustRefund.put("valueCodes", List.of("QUALITY_DEFECT"));
            mustRefund.put("sourceScope", "GLOBAL");
            out.add(mustRefund);
            byte[] body = json.writeValueAsBytes(out);
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

  @Autowired DSLContext sql;
  @Autowired com.starcon.tenancy.TenantTransactions tenants;
  @Autowired MockMvc http;

  static final OffsetDateTime ORDER_AT = OffsetDateTime.parse("2026-02-01T10:00:00+08:00");
  static final OffsetDateTime ORDER_CREATED_AT = OffsetDateTime.parse("2026-01-20T08:00:00+08:00");
  static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-02-11T09:30:00+08:00");

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_intake_verdict, aftersales_reply_sla, aftersales_owner_roster,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  <T> T asTenant(UUID tenant, java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在指定租户下铺一张订单 + 一条 SLA（SLA 是受理回复时限的必需前置）。 */
  UUID seed(UUID tenant, String orderNo) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'SHOPIFY',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,0,0,"
                  + "10.0000,0,?::timestamptz,?::timestamptz)")) {
        p.setObject(1, id);
        p.setObject(2, tenant);
        p.setString(3, orderNo);
        p.setString(4, "EXT-" + orderNo);
        p.setObject(5, ORDER_AT);
        p.setObject(6, ORDER_CREATED_AT);
        p.setObject(7, ORDER_CREATED_AT);
        p.executeUpdate();
      }
      try (var l =
          c.prepareStatement(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)")) {
        l.setObject(1, UUID.randomUUID());
        l.setObject(2, tenant);
        l.setObject(3, id);
        l.setObject(4, UUID.randomUUID());
        l.setString(5, "SKU-" + orderNo);
        l.setString(6, "商品");
        l.setObject(7, ORDER_CREATED_AT);
        l.executeUpdate();
      }
      try (var ad =
          c.prepareStatement(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
                  + "postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US','CA',"
                  + "'LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)")) {
        ad.setObject(1, UUID.randomUUID());
        ad.setObject(2, tenant);
        ad.setObject(3, id);
        ad.setString(4, "买家" + orderNo);
        ad.setString(5, "buyer@example.com");
        ad.setObject(6, ORDER_CREATED_AT);
        ad.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant);
        ps.setObject(3, id);
        ps.setObject(4, UUID.randomUUID());
        ps.setString(5, "C-" + orderNo);
        ps.setString(6, "买家" + orderNo);
        ps.setObject(7, ORDER_CREATED_AT);
        ps.executeUpdate();
      }
      c.commit();
    }
    // 受理必需的回复时限策略（每个租户各配各的 —— SLA 也是租户隔离面）。
    // ⚠️ 先清掉该租户已有的**生效中**档再插：同一个平台档同一时刻只能有一档生效
    //   （V164 的部分唯一索引 + 区间重叠触发器），seed 被同一个用例调两次时
    //   第二档会被正确地拒掉 —— 那是夹具没幂等，不是产品行为有问题。
    asTenant(
        tenant,
        () -> {
          sql.execute(
              "delete from aftersales_reply_sla where tenant_id = ?::uuid", tenant);
          sql.execute(
              "insert into aftersales_reply_sla(id,tenant_id,platform_scope,reply_sla_hours,"
                  + " resolve_sla_hours,effective_from,created_by,created_at)"
                  + " values (?::uuid,?::uuid,'',24,?,?::timestamptz,?::uuid,now())",
              UUID.randomUUID(),
              tenant,
              72,
              ORDER_AT.minusDays(1),
              userA);
          return null;
        });
    return id;
  }

  /** 以某个租户受理一张单，返回解析后的响应。 */
  com.fasterxml.jackson.databind.JsonNode intake(String bearer, UUID tenant, UUID orderId, String orderNo)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", orderId.toString());
    body.put("caseType", "RETURN_REFUND");
    body.put("reasonCode", "QUALITY_DEFECT");
    body.put("requestedAmount", "88.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    body.put("intakeSource", "INTERNAL_ENTRY");
    body.put("intakeChannel", "INTERNAL_NOTE");
    body.put("handlingPlan", "RETURN_REFUND");
    body.put("buyerRequest", "买家要求退货退款 " + orderNo);
    body.put("receivedAt", RECEIVED_AT.toString());
    String resp =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(resp);
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见_按主键直查对方记录返回零行且自己那行查得到() throws Exception {
    // ⚠️ 每轮**新建实体**：拿已写过的单再读一遍，命中与否和租户无关，测的是幂等不是隔离。
    UUID orderA = seed(a, "SO-TEN-A1");
    UUID orderB = seed(b, "SO-TEN-B1");
    var resA = intake("Bearer tenant-a", a, orderA, "SO-TEN-A1");
    var resB = intake("Bearer tenant-b", b, orderB, "SO-TEN-B1");
    UUID caseA = UUID.fromString(resA.get("verdict").get("caseId").asText());
    UUID caseB = UUID.fromString(resB.get("verdict").get("caseId").asText());
    assertThat(caseA).isNotEqualTo(caseB);

    // **正对照**：用同样写法先证明「查自己那行」查得到 —— 否则「查对方返回零行」可能只是写法错了。
    assertThat(
            asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseA)
                .get(0, Long.class)))
        .as("正对照：A 家必须查得到自己那行")
        .isEqualTo(1L);
    assertThat(
            asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseB)
                .get(0, Long.class)))
        .as("A 家按主键直查 B 家的单必须零行")
        .isZero();

    // 判定凭据同样隔离：凭据里冻结着必退结论与政策原文，泄露后果更重。
    assertThat(
            asTenant(
                a,
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_intake_verdict where tenant_id = ?::uuid"
                                + " and case_id = ?::uuid",
                            a,
                            caseA)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            asTenant(
                a,
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_intake_verdict"
                                + " where tenant_id = ?::uuid and case_id = ?::uuid",
                            a,
                            caseB)
                        .get(0, Long.class)))
        .isZero();

    // 列表侧：A 家只看得到自己那一张。
    var listA =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/verdicts")
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listA.get("total").asLong()).isEqualTo(1);
    assertThat(listA.get("items").get(0).get("caseId").asText()).isEqualTo(caseA.toString());

    var listB =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/verdicts")
                    .header("Authorization", "Bearer tenant-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listB.get("total").asLong()).isEqualTo(1);
    assertThat(listB.get("items").get(0).get("caseId").asText()).isEqualTo(caseB.toString());

    // 拿 B 家的单 ID 去 A 家重新判定 ⇒ 判成「没有生效中的受理判定」，拿不到任何 B 的数据。
    http.perform(
            post("/trade/aftersales/intake/cases/" + caseB + "/rejudge")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效_仍然200但写入落在会话租户名下且对方逐字节未变() throws Exception {
    UUID orderB = seed(b, "SO-TEN-B2");
    UUID orderB2 = seed(b, "SO-TEN-B3");
    // B 家先有一张单（下面要断言它**逐字节未变**，所以必须先真的存在）。
    intake("Bearer tenant-b", b, orderB, "SO-TEN-B2");
    intake("Bearer tenant-b", b, orderB2, "SO-TEN-B3");
    String beforeB = snapshotB();
    // 正对照：B 家确实有单（快照非空且逐行带着 B 的租户号）。
    // ⚠️ 快照里是**售后号**（AS 序列发号）而不是订单号 —— 断言订单号会永远失败。
    assertThat(beforeB).isNotBlank();
    assertThat(beforeB.split("\n")).hasSize(2);
    assertThat(beforeB).contains(b.toString());

    // 用 **B 家订单** + **A 家身份** + **伪造 X-Tenant-Id: B** 发起受理。
    // 预期：仍按会话里的真实成员关系（A 家）执行并 200；A 家拿不到 B 的订单 ⇒
    // 订单不存在报错；这里改用 A 家自己的订单，才能走到 200 那条路上。
    UUID orderA = seed(a, "SO-TEN-A2");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", orderA.toString());
    body.put("caseType", "RETURN_REFUND");
    body.put("reasonCode", "QUALITY_DEFECT");
    body.put("requestedAmount", "50.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    body.put("intakeSource", "INTERNAL_ENTRY");
    body.put("intakeChannel", "INTERNAL_NOTE");
    body.put("handlingPlan", "RETURN_REFUND");
    body.put("receivedAt", RECEIVED_AT.toString());

    String resp =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b.toString()) // 伪造：B 家租户号
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk()) // ⚠️ 期望 200，不是 4xx
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID newCase = UUID.fromString(json.readTree(resp).get("verdict").get("caseId").asText());

    // 写入落在**会话租户 A** 名下，不是伪造的 B。
    assertThat(asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", newCase)
            .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(asTenant(b, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", newCase)
            .get(0, Long.class)))
        .as("伪造的头不能把数据写进 B 家")
        .isZero();

    // B 家的既有数据**逐字节未变**。
    assertThat(snapshotB()).isEqualTo(beforeB);

    // 正对照：A 家自己刚写的那张查得到（否则上面几条可能全是假绿）。
    assertThat(asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", newCase)
            .get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户_单连接下交替读写两侧各自可见() throws Exception {
    UUID orderA = seed(a, "SO-TEN-A3");
    UUID orderB = seed(b, "SO-TEN-B3");
    var resA = intake("Bearer tenant-a", a, orderA, "SO-TEN-A3");
    var resB = intake("Bearer tenant-b", b, orderB, "SO-TEN-B3");
    UUID caseA = UUID.fromString(resA.get("verdict").get("caseId").asText());
    UUID caseB = UUID.fromString(resB.get("verdict").get("caseId").asText());

    // 交替 5 轮：每轮都重新取连接（池大小 = 1 ⇒ 必然是同一条连接归还后复用）。
    for (int i = 0; i < 5; i++) {
      assertThat(asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseA)
                  .get(0, Long.class)))
          .as("第 %d 轮：A 家仍只看得到自己那行", i)
          .isEqualTo(1L);
      assertThat(asTenant(a, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseB)
                  .get(0, Long.class)))
          .as("第 %d 轮：A 家看不到 B 家的行", i)
          .isZero();
      assertThat(asTenant(b, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseB)
                  .get(0, Long.class)))
          .as("第 %d 轮：B 家只看得到自己那行", i)
          .isEqualTo(1L);
      assertThat(asTenant(b, () -> sql.fetchOne("select count(*) from aftersales_case where id = ?::uuid", caseA)
                  .get(0, Long.class)))
          .as("第 %d 轮：B 家看不到 A 家的行", i)
          .isZero();
      // 判定凭据同样不能串（凭据冻结着必退结论）。
      assertThat(asTenant(a, () -> sql.fetchOne(
                      "select count(*) from aftersales_intake_verdict where case_id = ?::uuid", caseB)
                  .get(0, Long.class)))
          .isZero();
    }
  }

  /**
   * B 家售后单的一张「指纹」：把每一行的关键列拼成字符串。
   *
   * <p>刻意逐行逐列拼接而不是求 COUNT/求和：伪造租户头之后要证明的是
   * 「对方租户**逐字节未变**」，而「行数没变」这种弱断言会把
   * 「状态被改了但行数没变」判成通过。
   */
  String snapshotB() {
    return asTenant(
        b,
        () -> {
          StringBuilder sb = new StringBuilder();
          for (var r :
              sql.fetch(
                  "select id,tenant_id,case_no,status,reply_due_at,reason_code"
                      + " from aftersales_case order by id")) {
            sb.append(r.get(0, UUID.class))
                .append('|')
                .append(r.get(1, UUID.class))
                .append('|')
                .append(r.get(2, String.class))
                .append('|')
                .append(r.get(3, String.class))
                .append('|')
                .append(r.get(4, OffsetDateTime.class))
                .append('|')
                .append(r.get(5, String.class))
                .append('\n');
          }
          return sb.toString();
        });
  }

  // ==========================================================================
  // 补充：没有租户上下文时读不到任何一行
  // ==========================================================================

  @Test
  void 没有租户上下文时一张也读不到() throws Exception {
    UUID orderA = seed(a, "SO-TEN-A4");
    intake("Bearer tenant-a", a, orderA, "SO-TEN-A4");

    assertThat(sql.fetchOne("select count(*) from aftersales_intake_verdict").get(0, Long.class))
        .as("事务外（无 app.current_tenant）必须被 RLS 挡住")
        .isZero();
    assertThat(sql.fetchOne("select count(*) from aftersales_case").get(0, Long.class)).isZero();
  }
}
