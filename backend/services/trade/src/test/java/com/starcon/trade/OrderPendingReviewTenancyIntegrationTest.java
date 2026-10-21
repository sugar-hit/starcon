package com.starcon.trade;

import static com.starcon.trade.OrderPendingReviewModels.*;
import static com.starcon.trade.OrderReviewRuleModels.*;
import static org.assertj.core.api.Assertions.*;
// 显式单条静态导入压过 AssertJ 的通配导入：assertThrows 在 JUnit 与 AssertJ 里各有一份
// 签名不同的重载，通配导入 + 值兼容 lambda 会二义。仓库既有测试也走这条路。
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 9.2.1.1 待审队列的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （order_review_batch / order_review_queue_case / order_review_batch_item），
 * 不是占位探针表 —— 探针表能证明 RLS 开着，证明不了**队列查询与批量审单本身**没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：审单结论直接决定一单发不发。
 * 漏掉租户谓词 = A 租户的审单员能看见 B 租户的待审队列、能驳回 B 租户的订单，
 * 而 B 租户连自己的货被扣下了都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPendingReviewTenancyIntegrationTest {
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

  @Autowired OrderPendingReviewService service;
  @Autowired OrderReviewRuleService rules;
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
          "truncate order_review_batch_item, order_review_queue_case, order_review_batch,"
              + " order_review_rule_hit_condition, order_review_rule_hit,"
              + " order_review_rule_evaluation, order_review_rule_condition,"
              + " order_review_buyer_profile, order_review_rule,"
              + " order_risk_review, order_risk_assessment_component, order_risk_assessment,"
              + " order_risk_signal, order_risk_rule,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张已提交（进队列）的订单。 */
  UUID seedOrder(UUID tenant, String orderNo) {
    UUID orderId = UUID.randomUUID();
    BigDecimal total = new BigDecimal("50.0000");
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?,?,?,?,?,'PREPAID','SUBMITTED',?::timestamptz,?::numeric,0,0,0,0,"
                  + "?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "AMAZON", "EXT-" + orderNo, "USD", T1, total, total, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?::uuid,?,?::uuid,1,?::uuid,?,?,1,?::numeric,0,0,?::numeric,'',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", total,
              total, T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo, "买家", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?,?::uuid,'SHIPPING',?,?,?::text,'','US','CA','LOS ANGELES','',"
                  + "'90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "收件人", "+1-213-555-0100",
              "buyer-" + orderNo + "@example.com", T1);
          return null;
        });
    return orderId;
  }

  /** 给某个租户登记一条自动通过规则（否则批量通过没有依据）。 */
  void seedRule(UUID tenant) {
    as(
        tenant,
        () -> {
          var saved =
              rules.saveRule(
                  new ReviewRuleInput(
                      "AUTO-SMALL",
                      "小额自动通过",
                      "",
                      100,
                      ReviewAction.AUTO_APPROVE,
                      false,
                      "ALL",
                      T1,
                      null,
                      "",
                      "",
                      List.of(
                          new ReviewRuleConditionInput(
                              1,
                              ConditionDimension.AMOUNT,
                              ConditionOperator.LTE,
                              null,
                              new BigDecimal("100"),
                              "金额条件")),
                      null),
                  user);
          rules.changeStatus(
              saved.id(), new ReviewRuleStatusInput(ReviewRuleStatus.ACTIVE, null, null, "", null), user);
          return null;
        });
  }

  long ruleVersion(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select version from order_review_rule where tenant_id = ? and rule_code = ?",
                    tenant, "AUTO-SMALL")
                .get("version", Long.class));
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 三张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of("order_review_batch", "order_review_queue_case", "order_review_batch_item")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V209 必须真的建出 " + table).isNotNull();
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
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var orderA = seedOrder(a, "MO-A");
    var orderB = seedOrder(b, "MO-B");
    seedRule(a);
    seedRule(b);

    // 队列互不可见。
    assertThat(asA(() -> service.summary().total())).isEqualTo(1);
    assertThat(asB(() -> service.summary().total())).isEqualTo(1);
    assertThat(asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)).total()))
        .isEqualTo(1);
    assertThat(asB(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)).total()))
        .isEqualTo(1);

    // 更强的一格：按主键直查对方的单也拿不到（不只是列表过滤）—— 必须是 404，不是空详情。
    var crossRead =
        org.assertj.core.api.Assertions.catchThrowable(() -> asB(() -> service.detail(orderA)));
    assertThat(crossRead)
        .as("B 不能读到 A 的订单详情")
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(
            ((org.springframework.web.server.ResponseStatusException) crossRead).getStatusCode().value())
        .isEqualTo(404);

    // 批量审单：两边各审各的，回执与结论互不可见。
    var receiptA =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "a-key",
                        BatchAction.APPROVE,
                        List.of(orderA),
                        "AUTO-SMALL",
                        ruleVersion(a),
                        null),
                    user));
    assertThat(receiptA.approvedCount()).isEqualTo(1);

    // B 看不到 A 的批次（按主键直查）。
    assertThat(
            asB(
                () ->
                    sql.fetch(
                            "select id from order_review_batch where id = ?::uuid",
                            receiptA.batchId())
                        .size()))
        .as("按主键直查也必须被 RLS 挡住")
        .isZero();
    // B 看不到 A 的结论行。
    assertThat(
            asB(
                () ->
                    sql.fetch(
                            "select id from order_review_queue_case where order_id = ?::uuid", orderA)
                        .size()))
        .isZero();
    // B 的回执列表里没有 A 的批次。
    assertThat(asB(() -> service.pageBatches(new BatchQuery(null, null, 0, 50)).total())).isZero();
    assertThat(asA(() -> service.pageBatches(new BatchQuery(null, null, 0, 50)).total())).isEqualTo(1);
  }

  // ==========================================================================
  // 断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var orderA = seedOrder(a, "MO-HDR");
    seedRule(a);

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据。
    var body =
        http.perform(
                get("/trade/order-pending-review/queue")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    assertThat(parsed.get("items").get(0).get("orderNo").asText()).isEqualTo("MO-HDR");

    // 行确实落在 a 名下：用 B 的事务按 B 的租户谓词查，必须查不到。
    assertThat(
            asB(
                () ->
                    sql.fetch(
                            "select id from order_review_queue_case where order_id = ?::uuid", orderA)
                        .size()))
        .as("伪造请求头不得把行带进 B 的租户")
        .isZero();

    // 伪造 X-Tenant-Id 也不能给 B 开通写入口：B 拿 A 的订单 id 去审单，
    // 只会得到「跳过」，不会改掉 A 的单。
    var receipt =
        json.readTree(
            http.perform(
                    post("/trade/order-pending-review/batches")
                        .header("Authorization", "Bearer tenant-b")
                        .header("X-Tenant-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                new BatchDecisionInput(
                                    "cross2",
                                    BatchAction.SUSPEND,
                                    List.of(orderA),
                                    null,
                                    null,
                                    "越权"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(receipt.get("suspendedCount").asInt()).isZero();
    assertThat(receipt.get("skippedCount").asInt()).isEqualTo(1);
    assertThat(receipt.get("items").get(0).get("reason").asText()).contains("不属于当前租户");

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/order-pending-review/queue")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    var orderA = seedOrder(a, "MO-PA");
    var orderB = seedOrder(b, "MO-PB");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.summary().total())).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(asB(() -> service.summary().total())).as("第 %d 轮租户 B", i).isEqualTo(1);
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(asA(() -> service.summary().total())).isEqualTo(1);
    assertThat(asB(() -> service.summary().total())).isEqualTo(1);
  }

  // ==========================================================================
  // 额外一条：跨租户拿别人的订单改结论必须失败（不是给一个空结果）
  // ==========================================================================
  @Test
  void 跨租户拿别人的订单审单会被跳过而不是改掉别人的单() {
    var orderA = seedOrder(a, "MO-X");
    seedRule(a);
    var receipt =
        asB(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "cross3", BatchAction.SUSPEND, List.of(orderA), null, null, "越权"),
                    user));
    assertThat(receipt.suspendedCount()).isZero();
    assertThat(receipt.skippedCount()).isEqualTo(1);
    // A 的单仍然是「没审过」：队列里还在，结论表里没有行。
    assertThat(asA(() -> service.summary().pending())).isEqualTo(1);
    assertThat(
            asA(
                () ->
                    sql.fetch(
                            "select id from order_review_queue_case where order_id = ?::uuid", orderA)
                        .size()))
        .isZero();
  }
}
