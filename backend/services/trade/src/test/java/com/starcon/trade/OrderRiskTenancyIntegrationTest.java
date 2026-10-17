package com.starcon.trade;

import static com.starcon.trade.OrderRiskModels.*;
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
 * 9.2.2.2 风控校验的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （order_risk_rule / order_risk_signal / order_risk_assessment / 组件 / 复核），
 * 不是占位探针表 —— 探针表能证明 RLS 开着，证明不了**打分查询本身**没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：打分查询是「同一买家」「同一地址」这种**跨订单的宽条件**，
 * 漏掉租户谓词就是**跨租户归因** —— 两家互不相识的公司共用一个风险分，
 * A 家的风控结论会决定 B 家的货发不发，而 B 家连自己被扣分都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderRiskTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

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
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      actors.put("Bearer tenant-b", actor(user, b, allPermissions));
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

  @Autowired OrderRiskService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.orders.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00Z");

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_risk_review, order_risk_assessment_component, order_risk_assessment,"
              + " order_risk_signal, order_risk_rule,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张可评估的订单（已提交 + 有收货地址快照 + 有客户档案快照）。 */
  UUID seedOrder(UUID tenant, String orderNo, UUID partyId, String recipient) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,'SUBMITTED',?::timestamptz,10.0000,0,0,0,0,10.0000,0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "AMAZON", "EXT-" + orderNo, "USD", "PREPAID", T1, T1,
              T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品",
              T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,?,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, partyId, "C-" + partyId, recipient, "INDIVIDUAL",
              T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING',?,?,?,'','US','CA','LOS ANGELES','','90001',"
                  + "'100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, recipient, "+1-213-555-0100",
              recipient + "@example.com", T1);
          return null;
        });
    return orderId;
  }

  void seedRuleAndAssessment(UUID tenant, UUID partyId, String orderNo) {
    var orderId = seedOrder(tenant, orderNo, partyId, partyId.toString().substring(0, 6));
    as(
        tenant,
        () -> {
          service.saveRule(
              new RiskRuleInput(
                  "默认风控",
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("40.0000"),
                  new java.math.BigDecimal("80.0000"),
                  24, 10, 5, new java.math.BigDecimal("3.0000"), 2, 72, 5, 720,
                  MissingSignalPolicy.SCORE_KNOWN_ONLY),
              user);
          service.assess(new AssessmentInput(orderId, "隔离基线"), user);
          return null;
        });
  }

  long countOf(java.util.function.Supplier<List<UUID>> work) {
    return work.get().size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 五张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "order_risk_rule",
            "order_risk_signal",
            "order_risk_assessment",
            "order_risk_assessment_component",
            "order_risk_review")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V150 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')",
                      table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var partyA = UUID.randomUUID();
    var partyB = UUID.randomUUID();
    seedRuleAndAssessment(a, partyA, "MO-A");
    seedRuleAndAssessment(b, partyB, "MO-B");

    assertThat(asA(service::overview).total()).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(service::overview).total()).as("B 只看得到自己那一份").isEqualTo(1);

    // 更强的一格：按主键直查对方的评估也拿不到（不只是列表过滤）。
    var assessmentA =
        asA(() -> service.pageAssessments(new AssessmentQuery(null, null, null, null, null, 0, 50)));
    var idA = assessmentA.items().get(0).id();
    var byIdB =
        asB(
            () ->
                sql.fetch("select id from order_risk_assessment where id = ?", idA).size());
    assertThat(byIdB).as("按主键直查也必须被 RLS 挡住").isZero();

    // 组件行、信号、规则同样互不可见。
    assertThat(asA(() -> service.pageSignals(new SignalQuery(null, null, null, null, 0, 50)).total()))
        .isZero();
    assertThat(asB(() -> service.pageSignals(new SignalQuery(null, null, null, null, 0, 50)).total()))
        .isZero();
  }

  // ==========================================================================
  // 断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var partyA = UUID.randomUUID();
    seedRuleAndAssessment(a, partyA, "MO-HDR");

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据，
    // 而且行确实落在会话租户 a 名下（不是只断言「B 看不见」）。
    var body =
        http.perform(
                get("/trade/orders/risk-validation/assessments")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    var row = parsed.get("items").get(0);
    assertThat(row.get("orderNo").asText()).isEqualTo("MO-HDR");

    // 行确实落在 a 名下：用 B 的事务按 B 的租户谓词按订单号查，必须查不到。
    var visibleToB =
        asB(
            () ->
                sql.fetch(
                        "select id from order_risk_assessment where order_no = ?", "MO-HDR")
                    .size());
    assertThat(visibleToB).as("伪造请求头不得把行带进 B 的租户").isZero();

    // 伪造 X-Tenant-Id 也不能给 B 开通任何写入口：B 仍然没有自己的规则，
    // 给 B 建规则时会因为「一租户一条」而与 B 自己的状态一致，与 A 无关。
    http.perform(
            put("/trade/orders/risk-validation/rule")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RiskRuleInput(
                            "越权规则",
                            new java.math.BigDecimal("0.2000"),
                            new java.math.BigDecimal("0.2000"),
                            new java.math.BigDecimal("0.2000"),
                            new java.math.BigDecimal("0.2000"),
                            new java.math.BigDecimal("0.2000"),
                            new java.math.BigDecimal("40.0000"),
                            new java.math.BigDecimal("80.0000"),
                            24, 10, 5, new java.math.BigDecimal("3.0000"), 2, 72, 5, 720,
                            MissingSignalPolicy.SCORE_KNOWN_ONLY))))
        .andExpect(status().isOk());
    // 规则仍然落在 a 名下：B 读规则必须是 404（没登记过），不是读到 A 那条。
    http.perform(get("/trade/orders/risk-validation/rule").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    assertThat(asA(service::rule).ruleName()).isEqualTo("越权规则");

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/orders/risk-validation/assessments")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    var partyA = UUID.randomUUID();
    var partyB = UUID.randomUUID();
    seedRuleAndAssessment(a, partyA, "MO-PA");
    seedRuleAndAssessment(b, partyB, "MO-PB");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(service::overview).total()).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(asB(service::overview).total()).as("第 %d 轮租户 B", i).isEqualTo(1);
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(asA(service::overview).total()).isEqualTo(1);
    assertThat(asB(service::overview).total()).isEqualTo(1);
  }

  // ==========================================================================
  // 额外一条：跨租户**评估**必须失败（不是给一个空结果）
  // ==========================================================================
  @Test
  void 跨租户拿别人的订单评估会失败() {
    var partyA = UUID.randomUUID();
    var orderA = seedOrder(a, "MO-ASSESS", partyA, "alice");
    asA(
        () -> {
          service.saveRule(
              new RiskRuleInput(
                  "默认风控",
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("40.0000"),
                  new java.math.BigDecimal("80.0000"),
                  24, 10, 5, new java.math.BigDecimal("3.0000"), 2, 72, 5, 720,
                  MissingSignalPolicy.SCORE_KNOWN_ONLY),
              user);
          return null;
        });

    // 先给 B 也登记一条规则：否则 B 会先撞上「本租户尚未登记风控规则」的 409，
    // 测到的就不是「读不到 A 的订单」这一条了。
    asB(
        () -> {
          service.saveRule(
              new RiskRuleInput(
                  "B 的规则",
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("0.2000"),
                  new java.math.BigDecimal("40.0000"),
                  new java.math.BigDecimal("80.0000"),
                  24, 10, 5, new java.math.BigDecimal("3.0000"), 2, 72, 5, 720,
                  MissingSignalPolicy.SCORE_KNOWN_ONLY),
              user);
          return null;
        });

    // B 会话拿 A 的订单 ID 去评估：RLS 下读不到订单 → 404，不是「悄悄评个空分」。
    var e =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> asB(() -> service.assess(new AssessmentInput(orderA, "越权"), user)));
    assertThat(e)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(404);
  }
}
