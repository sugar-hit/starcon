package com.starcon.trade;

import static com.starcon.trade.OrderInterceptModels.*;
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
 * 9.2.1.3 异常拦截的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在真实业务表</b>上
 * （order_intercept_policy / restriction / sku_cost / check / finding / disposition），
 * 不是占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>判定查询本身</b>没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：判定查询是「同一 buyer_key」「同一收货国」的<b>跨订单宽条件</b>，
 * 漏掉租户谓词就是<b>跨租户归因</b> —— 两家互不相识的公司会共用一份拦截结论，
 * A 家的黑名单会拦下 B 家的订单，而 B 家连自己被拦都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderInterceptTenancyIntegrationTest {
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

  @Autowired OrderInterceptService service;
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
          "truncate order_intercept_disposition, order_intercept_finding, order_intercept_check,"
              + " order_intercept_sku_cost, order_intercept_restriction, order_intercept_policy,"
              + " order_review_buyer_profile,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张可检查的订单（已提交 + 收货地址快照 + 一行商品）。 */
  UUID seedOrder(UUID tenant, String orderNo, String recipient) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,'SUBMITTED',?::timestamptz,100.0000,0,0,0,0,100.0000,0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "AMAZON", "EXT-" + orderNo, "USD", "PREPAID", T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,100.0000,0,0,100.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING',?,?,?,'','US','CA','LOS ANGELES','','90001',"
                  + "'100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, recipient, "+1-213-555-0100",
              recipient + "@example.com", T1);
          // V99 的 guard_sales_order_submitted 要求已提交订单必须有客户快照，
          // 缺了它提交会被挡下（"没有客户快照"），那测到的就不是隔离而是建单失败。
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,?,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-1", recipient, "INDIVIDUAL",
              T1);
          return null;
        });
    return orderId;
  }

  void seedBlacklist(UUID tenant, String recipient, String reason) {
    String key = OrderInterceptService.buyerKey(recipient, "+1-213-555-0100", recipient + "@example.com");
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into order_review_buyer_profile(id,tenant_id,buyer_key,buyer_name,buyer_phone,"
                  + "buyer_email,buyer_level,blacklisted,blacklist_reason,blacklisted_at,note,version,"
                  + "created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'RISK',true,?,?::timestamptz,'',0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tenant, key, recipient, "+1-213-555-0100",
              recipient + "@example.com", reason, T1, T1, T1);
          return null;
        });
  }

  void seedPolicyAndCheck(UUID tenant, String orderNo, String recipient) {
    as(
        tenant,
        () -> {
          service.savePolicy(
              new InterceptPolicyInput(
                  "默认拦截策略", 24, 3, new BigDecimal("0.0000"), new BigDecimal("60.0000"),
                  MissingReferencePolicy.ASSESS_KNOWN_ONLY));
          service.assess(new InterceptCheckInput(seedOrder(tenant, orderNo, recipient)), user);
          return null;
        });
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 六张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "order_intercept_policy",
            "order_intercept_restriction",
            "order_intercept_sku_cost",
            "order_intercept_check",
            "order_intercept_finding",
            "order_intercept_disposition")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V153 必须真的建出 " + table).isNotNull();
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
    seedPolicyAndCheck(a, "MO-A", "alice");
    seedPolicyAndCheck(b, "MO-B", "bob");

    assertThat(asA(service::overview).total()).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(service::overview).total()).as("B 只看得到自己那一份").isEqualTo(1);

    // 更强的一格：按主键直查对方的检查也拿不到（不只是列表过滤）。
    var listA =
        asA(() -> service.pageChecks(new InterceptCheckQuery(null, null, null, null, null, 0, 50)));
    var idA = listA.items().get(0).id();
    var byIdB =
        asB(() -> sql.fetch("select id from order_intercept_check where id = ?", idA).size());
    assertThat(byIdB).as("按主键直查也必须被 RLS 挡住").isZero();

    // 详情端点同样跨租户不可见（RLS 下查不到 → 404，不是空详情）。
    var e =
        org.assertj.core.api.Assertions.catchThrowable(() -> asB(() -> service.check(idA)));
    assertThat(e).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(404);

    // **本模块最要紧的一格**：黑名单互不可见，而且用**同一个买家**验。
    // 两家租户都有一张收件人 alice 的单，A 把 alice 拉黑了、B 没有 ——
    // 若 B 能读到 A 的档案，B 的这张单就会被 A 的黑名单拦下，
    // 而 B 根本不知道自己「有条黑名单在生效」。
    seedBlacklist(a, "alice", "A 家的黑名单");
    seedPolicyAndCheck(b, "MO-B-SAME", "alice");

    var listB =
        asB(() -> service.pageChecks(new InterceptCheckQuery(null, null, null, null, null, 0, 50)));
    var sameBuyerB =
        listB.items().stream().filter(c -> c.orderNo().equals("MO-B-SAME")).findFirst().orElseThrow();
    var fB =
        asB(
            () ->
                service.check(sameBuyerB.id()).findings().stream()
                    .filter(f -> f.checkCode() == InterceptCheckCode.BLACKLISTED_BUYER)
                    .findFirst()
                    .orElseThrow());
    assertThat(fB.status())
        .as("B 读不到 A 的黑名单，只能是「缺参照」，不能是「命中」")
        .isNotEqualTo(FindingStatus.FLAGGED);
    assertThat(fB.explanation()).doesNotContain("A 家的黑名单");
  }

  // ==========================================================================
  // 断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    seedPolicyAndCheck(a, "MO-HDR", "alice");

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据，
    // 而且行确实落在会话租户 a 名下（不是只断言「B 看不见」）。
    var body =
        http.perform(
                get("/trade/order-intercept/checks")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    assertThat(parsed.get("items").get(0).get("orderNo").asText()).isEqualTo("MO-HDR");

    // 行确实落在 a 名下：用 B 的事务按 B 的租户谓词按订单号查，必须查不到。
    var visibleToB =
        asB(
            () ->
                sql.fetch("select id from order_intercept_check where order_no = ?", "MO-HDR")
                    .size());
    assertThat(visibleToB).as("伪造请求头不得把行带进 B 的租户").isZero();

    // 伪造 X-Tenant-Id 也不能替 B 开通任何入口：B 仍然没有自己的策略，
    // B 读策略必须是 404（没登记过），而不是读到 A 那条。
    http.perform(get("/trade/order-intercept/policy").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    assertThat(asA(service::policy).policyName()).isEqualTo("默认拦截策略");

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/order-intercept/checks")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    seedPolicyAndCheck(a, "MO-PA", "alice");
    seedPolicyAndCheck(b, "MO-PB", "bob");

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
  // 额外一条：跨租户**检查**必须失败（不是给一个空清单）
  // ==========================================================================
  @Test
  void 跨租户拿别人的订单检查会失败() {
    // 先给 A、B 都登记策略，否则 B 会先撞上「本租户尚未登记拦截策略」的 409，
    // 测到的就不是「读不到 A 的订单」这一条了。
    asA(
        () -> {
          service.savePolicy(
              new InterceptPolicyInput(
                  "A 的策略", 24, 3, new BigDecimal("0.0000"), new BigDecimal("60.0000"),
                  MissingReferencePolicy.ASSESS_KNOWN_ONLY));
          return null;
        });
    asB(
        () -> {
          service.savePolicy(
              new InterceptPolicyInput(
                  "B 的策略", 24, 3, new BigDecimal("0.0000"), new BigDecimal("60.0000"),
                  MissingReferencePolicy.ASSESS_KNOWN_ONLY));
          return null;
        });
    var orderA = seedOrder(a, "MO-ASSESS", "alice");

    // B 会话拿 A 的订单 ID 去检查：RLS 下读不到订单 → 404，不是「悄悄判成放行」。
    var e =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> asB(() -> service.assess(new InterceptCheckInput(orderA), user)));
    assertThat(e).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(404);
  }
}
