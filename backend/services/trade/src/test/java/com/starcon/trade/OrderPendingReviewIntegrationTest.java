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
 * 9.2.1.1 待审队列。
 *
 * <p>本测试是 <b>V209 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉住的十几条</b>：
 *
 * <ol>
 *   <li><b>队列成员是「已提交且未审」的订单</b>：平台单落 SUBMITTED 即进队列，
 *       已通过 / 已驳回的单离开队列，已挂起的单**留在**队列里等人。</li>
 *   <li><b>排序是真的</b>：风险分高的在前 → 时效分高的在前 → 提交时刻 → id；
 *       风险分没评估过的排在有分的之后（不兜 0）。</li>
 *   <li><b>加粗句「批量通过必须基于明确的规则，而不是默认全过」</b>：
 *       缺规则 / 规则不存在 / 版本对不上 / 不在生效期 / 动作是转人工 / 强制转人工 ——
 *       六种情况全部显式拒绝，**没有一条兜底路径**。</li>
 *   <li><b>通过必须真的由指名的那条规则判出来</b>：判成转人工的、命中的是别的规则的，
 *       一律跳过并写清原因（逐条回执里能读到）。</li>
 *   <li><b>幂等</b>：同一个幂等键重复提交只生效一次，第二次回放上一次的回执，
 *       状态不再被改；同一个键配另一份请求则 409。</li>
 *   <li><b>回执写明本批实际通过/驳回/挂起/跳过各几条</b>，而不是「请求了几条」。</li>
 *   <li><b>DB 层兜底</b>：绕过服务层直写一条「没有规则的通过」被 CHECK 挡住；
 *       终态不可回退；逐条明细不可改写。</li>
 *   <li><b>时间</b>：带 +08:00 偏移量的 timestamptz 写入后，<b>断言读回的值</b>
 *       等于同一绝对时刻（而不是「没抛异常」）—— 这正是 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 −28800s 的那一处。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPendingReviewIntegrationTest {
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
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.update", work);
  }

  // ------------------------------------------------------------ 夹具常量（时间全部钉死）

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00Z");
  /** 刻意用 +08:00 偏移：写回时必须是**同一绝对时刻**，否则会静默偏 −28800s。 */
  static final OffsetDateTime T_PLUS8 = OffsetDateTime.parse("2026-03-02T16:00:00+08:00");

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
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

  /** 一张已提交（进队列）的订单。头与行两边的汇总必须一致，否则提交期的延迟触发器会挡下。 */
  UUID seedOrder(UUID tenant, String orderNo, BigDecimal total, OffsetDateTime submittedAt) {
    UUID orderId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?,?,?,?,?,'PREPAID','SUBMITTED',?::timestamptz,?::numeric,0,0,0,0,"
                  + "?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "AMAZON", "EXT-" + orderNo, "USD", submittedAt, total,
              total, submittedAt, submittedAt);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?::uuid,?,?::uuid,1,?::uuid,?,?,1,?::numeric,0,0,?::numeric,'',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", total,
              total, submittedAt);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo, "买家",
              submittedAt);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?,?::uuid,'SHIPPING',?,?,?::text,'','US','CA','LOS ANGELES','',"
                  + "'90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "收件人", "+1-213-555-0100",
              "buyer-" + orderNo + "@example.com", submittedAt);
          return null;
        });
    return orderId;
  }

  /** 登记一条审单规则并启用。返回规则 id。 */
  UUID seedRule(
      String code, ReviewAction action, int priority, boolean forced, BigDecimal amountThreshold,
      boolean atOrBelow, OffsetDateTime effectiveFrom) {
    return asA(
        () -> {
          var saved =
              rules.saveRule(
                  new ReviewRuleInput(
                      code,
                      "规则 " + code,
                      "",
                      priority,
                      action,
                      forced,
                      "ALL",
                      effectiveFrom,
                      null,
                      "",
                      "",
                      List.of(
                          new ReviewRuleConditionInput(
                              1,
                              ConditionDimension.AMOUNT,
                              atOrBelow ? ConditionOperator.LTE : ConditionOperator.GTE,
                              null,
                              amountThreshold,
                              "金额条件")),
                      null),
                  user);
          rules.changeStatus(
              saved.id(), new ReviewRuleStatusInput(ReviewRuleStatus.ACTIVE, null, null, "", null), user);
          return saved.id();
        });
  }

  /** 当前规则版本（新建后是 0，改一次 +1）。 */
  long ruleVersion(String code) {
    return asA(
        () ->
            sql.fetchOne(
                    "select version from order_review_rule where tenant_id = ? and rule_code = ?", a, code)
                .get("version", Long.class));
  }

  /** 造一次风控评估（五个维度都出分，分数由 normalized 决定）。 */
  void seedRiskAssessment(UUID orderId, String orderNo, BigDecimal total, int normalized) {
    UUID ruleId =
        asA(
            () -> {
              var existing =
                  sql.fetchOne("select id from order_risk_rule where tenant_id = ?", a);
              if (existing != null) {
                return existing.get("id", UUID.class);
              }
              UUID id = UUID.randomUUID();
              sql.execute(
                  "insert into order_risk_rule(id,tenant_id,rule_name,enabled,weight_buyer_history,"
                      + "weight_device_ip_link,weight_order_frequency,weight_amount_anomaly,"
                      + "weight_address_concentration,manual_review_threshold,hold_shipment_threshold,"
                      + "frequency_window_hours,frequency_max_orders,device_link_max_links,"
                      + "amount_anomaly_ratio,amount_anomaly_min_orders,"
                      + "address_concentration_window_hours,address_concentration_max_orders,"
                      + "signal_max_age_hours,missing_signal_policy,version,created_at)"
                      + " values(?::uuid,?,'默认风控',true,0.2000,0.2000,0.2000,0.2000,0.2000,40.0000,"
                      + "90.0000,24,10,5,3.0000,2,720,5,720,'SCORE_KNOWN_ONLY',0,?::timestamptz)",
                  id, a, T1);
              return id;
            });
    asA(
        () -> {
          UUID assessmentId = UUID.randomUUID();
          sql.execute(
              "insert into order_risk_assessment(id,tenant_id,order_id,order_no,buyer_fingerprint,"
                  + "order_currency,order_amount,order_submitted_at,rule_id,rule_version,rule_name,"
                  + "evaluated_reference_at,risk_score,recommended_action,manual_review_required,"
                  + "evaluation_status,scored_dimension_count,missing_dimension_count,created_by,"
                  + "created_at) values(?::uuid,?,?::uuid,?,null,'USD',?::numeric,?::timestamptz,?::uuid,0,"
                  + "'默认风控',?::timestamptz,null,'MANUAL_REVIEW',true,'INSUFFICIENT_SIGNALS',0,5,"
                  + "?::uuid,?::timestamptz)",
              assessmentId, a, orderId, orderNo, total, T1, ruleId, T1, user, T1);
          BigDecimal weight = new BigDecimal("0.2000");
          BigDecimal norm = new BigDecimal(normalized);
          BigDecimal contribution = weight.multiply(norm);
          for (String dim :
              List.of(
                  "BUYER_HISTORY", "DEVICE_IP_LINK", "ORDER_FREQUENCY", "AMOUNT_ANOMALY",
                  "ADDRESS_CONCENTRATION")) {
            sql.execute(
                "insert into order_risk_assessment_component(id,tenant_id,assessment_id,dimension,"
                    + "status,weight,raw_value,normalized_score,contribution,signal_source,"
                    + "signal_captured_at,explanation,created_at) values(?::uuid,?,?::uuid,?,'SCORED',"
                    + "?::numeric,?::numeric,?::numeric,?::numeric,'TENANT_MAINTAINED',"
                    + "?::timestamptz,'夹具',?::timestamptz)",
                UUID.randomUUID(), a, assessmentId, dim, weight, norm, norm, contribution, T1, T1);
          }
          return null;
        });
  }

  /** 把整条 cause 链的文案拼起来：jOOQ 与 Spring 翻译后，可读原因在**根因**上。 */
  static String messages(Throwable error) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = error; c != null && c != c.getCause(); c = c.getCause()) {
      sb.append(c.getMessage()).append('\n');
    }
    return sb.toString();
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
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table)
          .isTrue();
    }
    // 时效函数必须 IMMUTABLE —— 否则它进不了 ORDER BY 的稳定排序。
    assertThat(
            sql.fetchOne(
                    "select provolatile from pg_proc where proname='order_review_timeliness_score'")
                .get(0, String.class))
        .as("时效分函数必须 IMMUTABLE")
        .isEqualTo("i");
    assertThat(
            sql.fetchOne(
                    "select provolatile from pg_proc where proname='order_review_timeliness_state'")
                .get(0, String.class))
        .isEqualTo("i");
  }

  // ==========================================================================
  // 队列成员与排序
  // ==========================================================================
  @Test
  void 队列只列已提交且未终结的订单_已挂起的仍留在队列里() {
    UUID open = seedOrder(a, "MO-OPEN", new BigDecimal("50.0000"), T1);
    UUID suspended = seedOrder(a, "MO-SUSP", new BigDecimal("50.0000"), T1);
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?,?,'AMAZON','EXT-MO-DRAFT','USD','PREPAID','DRAFT',null,"
                  + "0,0,0,0,0,0,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), a, "MO-DRAFT", T1, T1);
          return null;
        });

    // 把其中一单挂起
    asA(
        () -> {
          service.decide(
              new BatchDecisionInput(
                  "key-suspend",
                  BatchAction.SUSPEND,
                  List.of(suspended),
                  null,
                  null,
                  "等买家补地址"),
              user);
          return null;
        });

    var page = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)));
    var orderNos = page.items().stream().map(PendingReviewRow::orderNo).toList();
    assertThat(orderNos).contains("MO-OPEN", "MO-SUSP");
    assertThat(orderNos).as("草稿单不在队列里").doesNotContain("MO-DRAFT");
    assertThat(page.items().stream().filter(r -> r.orderNo().equals("MO-SUSP")).findFirst().get()
            .queueStatus())
        .isEqualTo("SUSPENDED");
  }

  @Test
  void 排序是风险分优先_其次时效分_未评估的排在有分的之后() {
    // 风险分 80 的单、风险分 20 的单、没有评估的单，各一。
    UUID high = seedOrder(a, "MO-HIGH", new BigDecimal("50.0000"), T1);
    UUID low = seedOrder(a, "MO-LOW", new BigDecimal("50.0000"), T1);
    UUID none = seedOrder(a, "MO-NONE", new BigDecimal("50.0000"), T1);
    seedRiskAssessment(high, "MO-HIGH", new BigDecimal("50.0000"), 80);
    seedRiskAssessment(low, "MO-LOW", new BigDecimal("50.0000"), 20);

    var page = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)));
    var orderNos = page.items().stream().map(PendingReviewRow::orderNo).toList();
    assertThat(orderNos)
        .as("风险分高的在前，未评估的排在最后（不兜 0）")
        .containsExactly("MO-HIGH", "MO-LOW", "MO-NONE");

    var highRow = page.items().get(0);
    assertThat(highRow.riskScore()).isEqualByComparingTo("80.0000");
    var noneRow = page.items().get(2);
    assertThat(noneRow.riskScore()).as("没评估过必须是 null，不是 0").isNull();
    assertThat(noneRow.timelinessScore()).as("时效分恒有值").isNotNull();
  }

  @Test
  void 风险分相同时按时效分排序_等得越久越靠前() {
    UUID old1 = seedOrder(a, "MO-OLD1", new BigDecimal("50.0000"), T1);
    UUID old2 = seedOrder(a, "MO-OLD2", new BigDecimal("50.0000"), T1);
    seedRiskAssessment(old1, "MO-OLD1", new BigDecimal("50.0000"), 50);
    seedRiskAssessment(old2, "MO-OLD2", new BigDecimal("50.0000"), 50);
    // 把两单的提交时刻拉开：老1 提交更早 ⇒ 等得更久 ⇒ 时效分更高。
    asA(
        () -> {
          sql.execute(
              "update sales_order set submitted_at = ?::timestamptz where id = ?::uuid",
              T1.minusDays(2), old1);
          sql.execute(
              "update sales_order set submitted_at = ?::timestamptz where id = ?::uuid", T1, old2);
          return null;
        });
    var page = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)));
    var orderNos = page.items().stream().map(PendingReviewRow::orderNo).toList();
    assertThat(orderNos).containsExactly("MO-OLD1", "MO-OLD2");
    assertThat(page.items().get(0).waitingHours()).isGreaterThan(page.items().get(1).waitingHours());
  }

  @Test
  void 分页不重不漏且筛选真的生效() {
    for (int i = 0; i < 7; i++) {
      seedOrder(a, "MO-" + i, new BigDecimal("50.0000"), T1);
    }
    seedOrder(a, "MO-SHOPIFY", new BigDecimal("50.0000"), T1);
    var page1 = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 3)));
    var page2 = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 1, 3)));
    var page3 = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 2, 3)));
    assertThat(page1.total()).isEqualTo(8);
    var all =
        java.util.stream.Stream.of(page1, page2, page3)
            .flatMap(p -> p.items().stream())
            .map(PendingReviewRow::orderId)
            .toList();
    assertThat(all).as("三页不重不漏").hasSize(8).doesNotHaveDuplicates();

    var byKeyword = asA(() -> service.page(new QueueQuery("SHOPIFY", null, null, null, null, 0, 50)));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).orderNo()).isEqualTo("MO-SHOPIFY");
  }

  // ==========================================================================
  // 加粗句：批量通过必须基于明确的规则
  // ==========================================================================
  @Test
  void 批量通过缺规则被显式拒绝_不给默认全过() {
    UUID order = seedOrder(a, "MO-NORULE", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k1", BatchAction.APPROVE, List.of(order), null, null, null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("必须指明依据的审单规则");
    // 一条结论都不许留下。
    assertThat(asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)).total()))
        .isEqualTo(1);
  }

  @Test
  void 批量通过规则不存在被拒() {
    UUID order = seedOrder(a, "MO-X", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k2", BatchAction.APPROVE, List.of(order), "NO-SUCH-RULE", 0L, null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("不存在");
  }

  @Test
  void 批量通过规则版本对不上被拒() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-V", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k3", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", 99L, null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("版");
  }

  @Test
  void 批量通过规则不在生效期被拒() {
    seedRule(
        "FUTURE-RULE", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true,
        OffsetDateTime.now().plusDays(30));
    UUID order = seedOrder(a, "MO-F", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k4", BatchAction.APPROVE, List.of(order), "FUTURE-RULE", ruleVersion("FUTURE-RULE"), null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("生效期");
  }

  @Test
  void 批量通过规则动作是转人工被拒() {
    seedRule("MANUAL-RULE", ReviewAction.MANUAL_REVIEW, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-M", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k5", BatchAction.APPROVE, List.of(order), "MANUAL-RULE", ruleVersion("MANUAL-RULE"), null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("自动通过");
  }

  @Test
  void 驳回与挂起必须带理由() {
    UUID order = seedOrder(a, "MO-R", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k6", BatchAction.REJECT, List.of(order), null, null, "  "),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("理由");
  }

  // ==========================================================================
  // 通过必须真的由指名的那条规则判出来
  // ==========================================================================
  @Test
  void 批量通过只放行被指名规则判出来的单_其余跳过并写清原因() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID small = seedOrder(a, "MO-SMALL", new BigDecimal("50.0000"), T1);
    UUID big = seedOrder(a, "MO-BIG", new BigDecimal("5000.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    var receipt =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k7", BatchAction.APPROVE, List.of(small, big), "AUTO-SMALL", version, null),
                    user));

    assertThat(receipt.approvedCount()).as("只有小额单被放行").isEqualTo(1);
    assertThat(receipt.skippedCount()).isEqualTo(1);
    assertThat(receipt.requestedCount()).isEqualTo(2);
    assertThat(receipt.items()).hasSize(2);
    assertThat(receipt.items().get(0).outcome()).isEqualTo(BatchOutcome.APPROVED);
    assertThat(receipt.items().get(0).seqNo()).as("逐条结果按请求下标").isEqualTo(1);
    assertThat(receipt.items().get(1).outcome()).isEqualTo(BatchOutcome.SKIPPED);
    assertThat(receipt.items().get(1).reason()).as("跳过必须写清原因").contains("转人工");

    // 结论行：只有小额单有，且带规则与判定留痕。
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select status, rule_code, rule_version, evaluation_id from"
                        + " order_review_queue_case where tenant_id = ? and order_id = ?",
                    a, small));
    assertThat(row.get("status", String.class)).isEqualTo("APPROVED");
    assertThat(row.get("rule_code", String.class)).isEqualTo("AUTO-SMALL");
    assertThat(row.get("rule_version", Long.class)).isEqualTo(version);
    assertThat(row.get("evaluation_id", UUID.class)).as("通过必须指向一次具体判定").isNotNull();
  }

  @Test
  void 命中的规则不是指名的那条就跳过() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    seedRule("AUTO-ANY", ReviewAction.AUTO_APPROVE, 200, false, new BigDecimal("100000"), true, T1);
    UUID order = seedOrder(a, "MO-P", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    var receipt =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k8", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                    user));

    assertThat(receipt.approvedCount()).isZero();
    assertThat(receipt.skippedCount()).isEqualTo(1);
    assertThat(receipt.items().get(0).reason()).contains("AUTO-ANY").contains("AUTO-SMALL");
  }

  @Test
  void 强制转人工的规则压过自动通过_批量通过必须跳过() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    seedRule(
        "FORCE-HUMAN", ReviewAction.MANUAL_REVIEW, 300, true, new BigDecimal("0"), false, T1);
    UUID order = seedOrder(a, "MO-FH", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    var receipt =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k9", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                    user));
    assertThat(receipt.approvedCount()).isZero();
    assertThat(receipt.items().get(0).reason()).contains("转人工");
  }

  // ==========================================================================
  // 幂等
  // ==========================================================================
  @Test
  void 批量审单幂等_同一个键重复提交只生效一次() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-IDEM", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");
    var input =
        new BatchDecisionInput(
            "same-key", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null);

    var first = asA(() -> service.decide(input, user));
    var second = asA(() -> service.decide(input, user));

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).as("第二次是幂等回放").isTrue();
    assertThat(second.approvedCount()).isEqualTo(first.approvedCount());
    assertThat(second.batchId()).as("回放的是同一个批次").isEqualTo(first.batchId());

    // 状态没有被改第二次：只有一条结论行、一个批次、一个批次。
    assertThat(
            asA(
                () ->
                    sql.fetch(
                            "select id from order_review_batch where tenant_id = ?", a)
                        .size()))
        .isEqualTo(1);
    assertThat(
            asA(
                () ->
                    sql.fetch(
                            "select id from order_review_queue_case where tenant_id = ?", a)
                        .size()))
        .isEqualTo(1);
    assertThat(
            asA(
                () ->
                    sql.fetch(
                            "select id from order_review_batch_item where tenant_id = ?", a)
                        .size()))
        .isEqualTo(1);
  }

  @Test
  void 同一个幂等键配另一份请求被拒() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID one = seedOrder(a, "MO-1", new BigDecimal("50.0000"), T1);
    UUID two = seedOrder(a, "MO-2", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");
    asA(
        () ->
            service.decide(
                new BatchDecisionInput(
                    "dup", BatchAction.APPROVE, List.of(one), "AUTO-SMALL", version, null),
                user));
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "dup", BatchAction.APPROVE, List.of(two), "AUTO-SMALL", version, null),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(409);
  }

  @Test
  void 同一批里重复的订单id被拒() {
    UUID order = seedOrder(a, "MO-DUP", new BigDecimal("50.0000"), T1);
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "dup2", BatchAction.SUSPEND, List.of(order, order), null, null, "x"),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(e.getMessage()).contains("重复");
  }

  // ==========================================================================
  // 回执与队列状态推进
  // ==========================================================================
  @Test
  void 回执写明本批实际各几条() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID small = seedOrder(a, "MO-C1", new BigDecimal("50.0000"), T1);
    UUID big = seedOrder(a, "MO-C2", new BigDecimal("5000.0000"), T1);
    UUID other = seedOrder(a, "MO-C3", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    var receipt =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k10", BatchAction.APPROVE, List.of(small, big), "AUTO-SMALL", version, null),
                    user));
    assertThat(receipt.requestedCount()).isEqualTo(2);
    assertThat(receipt.approvedCount()).isEqualTo(1);
    assertThat(receipt.skippedCount()).isEqualTo(1);
    assertThat(receipt.approvedCount() + receipt.rejectedCount() + receipt.suspendedCount()
            + receipt.skippedCount())
        .isEqualTo(receipt.requestedCount());

    // 驳回与挂起各一批
    var rejected =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k11", BatchAction.REJECT, List.of(big), null, null, "金额异常"),
                    user));
    assertThat(rejected.rejectedCount()).isEqualTo(1);

    var suspended =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k12", BatchAction.SUSPEND, List.of(other), null, null, "等买家回邮"),
                    user));
    assertThat(suspended.suspendedCount()).isEqualTo(1);

    // 回执列表能查到这三批
    var page = asA(() -> service.pageBatches(new BatchQuery(null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  void 挂起的单仍在队列里_可以再推进成通过() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-S", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    asA(
        () ->
            service.decide(
                new BatchDecisionInput(
                    "k13", BatchAction.SUSPEND, List.of(order), null, null, "等补地址"),
                user));
    var open = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)));
    assertThat(open.total()).as("挂起的单仍在队列里").isEqualTo(1);

    var approved =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k14", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                    user));
    assertThat(approved.approvedCount()).isEqualTo(1);
    var after = asA(() -> service.page(new QueueQuery(null, null, null, null, null, 0, 50)));
    assertThat(after.total()).as("通过之后离开队列").isZero();
  }

  @Test
  void 已审过的单不再重复处理() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-DONE", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");
    asA(
        () ->
            service.decide(
                new BatchDecisionInput(
                    "k15", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                user));
    var again =
        asA(
            () ->
                service.decide(
                    new BatchDecisionInput(
                        "k16", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                    user));
    assertThat(again.approvedCount()).isZero();
    assertThat(again.skippedCount()).isEqualTo(1);
    assertThat(again.items().get(0).reason()).contains("已经审过");
  }

  @Test
  void 详情给出完整命中链_能回溯到规则id() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-DET", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");
    asA(
        () ->
            service.decide(
                new BatchDecisionInput(
                    "k17", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                user));

    var detail = asA(() -> service.detail(order));
    assertThat(detail.reviewDecision()).isNotNull();
    assertThat(detail.reviewDecision().winningRuleCode()).isEqualTo("AUTO-SMALL");
    assertThat(detail.hits()).as("命中链不是空的").isNotEmpty();
    var hit = detail.hits().get(0);
    assertThat(hit.ruleId()).as("命中链能回溯到具体规则 id").isNotNull();
    assertThat(hit.ruleCode()).isEqualTo("AUTO-SMALL");
    assertThat(hit.conditions()).as("逐条件明细也在").isNotEmpty();
    assertThat(hit.conditions().get(0).actualField()).contains("grand_total_amount");
    assertThat(hit.conditions().get(0).actualValue()).contains("50");
    // 列表行里也带着命中的规则编号
    var page = asA(() -> service.page(new QueueQuery(null, null, "APPROVED", null, null, 0, 50)));
    assertThat(page.items().get(0).hitRuleCodes()).contains("AUTO-SMALL");
  }

  // ==========================================================================
  // DB 级护栏：绕过服务层直写也挡得住
  // ==========================================================================
  @Test
  void db层挡住没有规则的通过() {
    UUID order = seedOrder(a, "MO-DB", new BigDecimal("50.0000"), T1);
    // 绕过服务层直接写：一条「通过」却没有指名规则、没有判定留痕 ⇒ 必须被 CHECK 挡住。
    var error =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into order_review_queue_case(id,tenant_id,order_id,status,enqueued_at,"
                              + "decided_at,decided_by_membership_id,decision_reason,version,created_at,"
                              + "updated_at) values(?::uuid,?,?::uuid,'APPROVED',?::timestamptz,"
                              + "?::timestamptz,?::uuid,'绕过服务层',0,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(), a, order, T1, T1, user, T1, T1);
                      return null;
                    }));
    assertThat(error).as("没有依据的通过必须被 DB 挡住").isNotNull();
    assertThat(messages(error)).contains("order_review_queue_case_approve_needs");
    // 真正该守住的是「这一行没进去」。
    assertThat(
            asA(
                () ->
                    sql.fetch(
                            "select id from order_review_queue_case where tenant_id = ? and order_id = ?::uuid",
                            a, order)
                        .size()))
        .as("被挡住的结论绝不能落库")
        .isZero();
  }

  @Test
  void db层挡住没有依据的通过批次() {
    var error =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into order_review_batch(id,tenant_id,idempotency_key,action,"
                              + "rule_code,rule_version,requested_count,reason,created_by_membership_id)"
                              + " values(?::uuid,?,'db-key','APPROVE','',null,1,'x',?::uuid)",
                          UUID.randomUUID(), a, user);
                      return null;
                    }));
    assertThat(error).as("没有指名规则的通过批次必须被 DB 挡住").isNotNull();
    assertThat(messages(error)).contains("order_review_batch_approve_needs_rule");
    assertThat(
            asA(
                () ->
                    sql.fetch("select id from order_review_batch where tenant_id = ? and idempotency_key = 'db-key'", a)
                        .size()))
        .isZero();
  }

  @Test
  void 终态不可回退() {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-TERM", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");
    asA(
        () ->
            service.decide(
                new BatchDecisionInput(
                    "k18", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null),
                user));
    var error =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_review_queue_case set status='REJECTED'"
                              + " where tenant_id = ? and order_id = ?::uuid",
                          a, order);
                      return null;
                    }));
    assertThat(error).as("终态不允许回退").isNotNull();
    assertThat(messages(error)).contains("终态");
    // 结论仍然是「已通过」。
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select status from order_review_queue_case where tenant_id = ? and order_id = ?::uuid",
                            a, order)
                        .get("status", String.class)))
        .isEqualTo("APPROVED");
  }

  @Test
  void 逐条明细不可改写() {
    UUID order = seedOrder(a, "MO-IMM", new BigDecimal("50.0000"), T1);
    asA(
        () ->
            service.decide(
                new BatchDecisionInput("k19", BatchAction.SUSPEND, List.of(order), null, null, "等"),
                user));
    // 第一道：运行角色根本没有 UPDATE 权（REVOKE）。
    var error =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_review_batch_item set reason='改一改' where tenant_id = ?", a);
                      return null;
                    }));
    assertThat(error).as("回执明细是留痕，运行角色改不动").isNotNull();
    assertThat(messages(error)).contains("order_review_batch_item");
    // 第二道：连迁移账号（超级用户）直连也改不动 —— DB 触发器兜住。
    var triggerError =
        catchThrowable(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("update order_review_batch_item set reason='改一改'");
              }
            });
    assertThat(triggerError).as("绕过权限直写也改不动").isNotNull();
    assertThat(messages(triggerError)).contains("不可改写");
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select reason from order_review_batch_item where tenant_id = ?", a)
                        .get("reason", String.class)))
        .isEqualTo("等");
  }

  @Test
  void 回执的统计数可以写_但身份列不能改() {
    UUID order = seedOrder(a, "MO-GRANT", new BigDecimal("50.0000"), T1);
    asA(
        () ->
            service.decide(
                new BatchDecisionInput("k20", BatchAction.SUSPEND, List.of(order), null, null, "等"),
                user));
    // 统计数可写（列级授权授回了这几列）
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','order_review_batch',"
                                + "'approved_count','UPDATE')")
                        .get(0, Boolean.class)))
        .isTrue();
    // 身份列不可写
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','order_review_batch',"
                                + "'idempotency_key','UPDATE')")
                        .get(0, Boolean.class)))
        .as("幂等键一旦写入就不可改")
        .isFalse();
  }

  // ==========================================================================
  // 时间
  // ==========================================================================
  @Test
  void 带时区偏移的时间写入后读回的是同一绝对时刻() {
    UUID order = seedOrder(a, "MO-TZ", new BigDecimal("50.0000"), T_PLUS8);
    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select submitted_at from sales_order where tenant_id = ? and id = ?::uuid", a,
                        order)
                    .get("submitted_at", OffsetDateTime.class));
    assertThat(stored.toInstant())
        .as("写进去的绝对时刻必须一模一样（否则就是静默偏了 8 小时）")
        .isEqualTo(T_PLUS8.toInstant());
  }

  @Test
  void 时效分按等候时长分档() {
    // 0..2 小时 = 0 分；6..12 小时 = 50 分；超过 24 小时 = 100 分。
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select order_review_timeliness_score(?::timestamptz,?::timestamptz)",
                            T1, T1.plusHours(1))
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("0");
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select order_review_timeliness_score(?::timestamptz,?::timestamptz)",
                            T1, T1.plusHours(8))
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("50");
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select order_review_timeliness_score(?::timestamptz,?::timestamptz)",
                            T1, T1.plusHours(30))
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("100");
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select order_review_timeliness_state(?::timestamptz,?::timestamptz)", T1,
                            T1.plusHours(30))
                        .get(0, String.class)))
        .isEqualTo("OVERDUE");
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void http边界_队列与批量审单都跑得通() throws Exception {
    seedRule("AUTO-SMALL", ReviewAction.AUTO_APPROVE, 100, false, new BigDecimal("100"), true, T1);
    UUID order = seedOrder(a, "MO-HTTP", new BigDecimal("50.0000"), T1);
    long version = ruleVersion("AUTO-SMALL");

    var list =
        http.perform(get("/trade/order-pending-review/queue").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(list);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    assertThat(parsed.get("items").get(0).get("orderNo").asText()).isEqualTo("MO-HTTP");

    var body =
        json.writeValueAsString(
            new BatchDecisionInput(
                "http-key", BatchAction.APPROVE, List.of(order), "AUTO-SMALL", version, null));
    var receipt =
        json.readTree(
            http.perform(
                    post("/trade/order-pending-review/batches")
                        .header("Authorization", "Bearer tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(receipt.get("approvedCount").asInt()).isEqualTo(1);
    assertThat(receipt.get("replayed").asBoolean()).isFalse();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/order-pending-review/queue")).andExpect(status().isUnauthorized());
  }

  @Test
  void 批量上限一百条() {
    var ids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
    var e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.decide(
                            new BatchDecisionInput(
                                "k21", BatchAction.SUSPEND, ids, null, null, "太多"),
                            user)));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(400);
  }
}
