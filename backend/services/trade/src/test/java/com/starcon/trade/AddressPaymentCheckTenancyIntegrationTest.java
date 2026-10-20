package com.starcon.trade;

import static com.starcon.trade.AddressPaymentCheckModels.*;
import static org.assertj.core.api.Assertions.*;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.2.2.1 地址与支付校验的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在真实业务表</b>上
 * （order_address_check_policy / embargo_rule / country_rule / payment_rule /
 * check / item），不是占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>判定查询本身</b>没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：禁运清单与国别规则是<b>跨订单宽条件</b>的判定源，
 * 漏掉租户谓词就是<b>跨租户归因</b> —— A 家的禁运国清单会拦下 B 家的订单，
 * 而 B 家连自己被拦都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AddressPaymentCheckTenancyIntegrationTest {
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

  @Autowired AddressPaymentCheckService service;
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
          "truncate order_address_check_item, order_address_check,"
              + " order_address_check_payment_rule, order_address_check_country_rule,"
              + " order_address_check_embargo_rule, order_address_check_policy,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张可校验的订单（已提交 + 收货地址快照 + 一行商品）。 */
  UUID seedOrder(UUID tenant, String orderNo, String country) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'PREPAID','SUBMITTED',?::timestamptz,100.0000,0,0,0,0,"
                  + "100.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "AMAZON", "EXT-" + orderNo, "USD", T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,100.0000,0,0,100.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING','收件人','+1-213-555-0100','buyer@example.com','',"
                  + "?,'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, country, T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-1", "收件人", T1);
          return null;
        });
    return orderId;
  }

  void seedPolicy(UUID tenant) {
    as(
        tenant,
        () ->
            service.savePolicy(
                new AddressPaymentPolicyInput(
                    "默认校验策略", AddressPaymentMissingReferencePolicy.ASSESS_KNOWN_ONLY)));
  }

  void seedEmbargo(UUID tenant, String country, AddressPaymentEmbargoLevel level) {
    as(
        tenant,
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    country, level, AddressPaymentRuleSource.TENANT_MAINTAINED, null, "测试", null,
                    null, null)));
  }

  AddressPaymentCheckDetail assess(UUID tenant, UUID orderId) {
    return as(
            tenant,
            () ->
                service.assess(
                        new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                            AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                        user)
                    .detail())
        ;
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 六张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "order_address_check_policy",
            "order_address_check_embargo_rule",
            "order_address_check_country_rule",
            "order_address_check_payment_rule",
            "order_address_check",
            "order_address_check_item")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V210 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as(table + " 运行角色没有读权限")
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'INSERT')", table)
                  .get(0, Boolean.class))
          .as(table + " 运行角色没有写权限")
          .isTrue();
    }
  }

  @Test
  void 头表的更新权限真的收紧了() {
    // ⚠️ 列级 REVOKE 在表级 GRANT 之后是**空操作**：必须收回**表级** UPDATE 才算真收紧。
    // 这条断言盯住的是「看起来收紧了、其实没收紧」那种假绿。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('trade_app','order_address_check','UPDATE')")
                .get(0, Boolean.class))
        .as("运行角色不该能 UPDATE 头表（风险等级由触发器写）")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('trade_app','order_address_check_item','UPDATE')")
                .get(0, Boolean.class))
        .as("明细是留痕表，运行角色不该能改")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('trade_app','order_address_check_item','DELETE')")
                .get(0, Boolean.class))
        .as("明细是留痕表，运行角色不该能删")
        .isFalse();
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 两租户互不可见() {
    seedPolicy(a);
    UUID orderA = seedOrder(a, "SO-A", "US");
    AddressPaymentCheckDetail detailA = assess(a, orderA);
    assertThat(detailA.check().orderId()).isEqualTo(orderA);

    // B 租户按**主键**直查 A 的校验记录 ⇒ 查不到（RLS 下 404）。
    assertThatThrownBy(() -> asB(() -> service.check(detailA.check().id())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于本租户");

    // B 租户按订单查最近一次校验 ⇒ 也查不到。
    assertThatThrownBy(() -> asB(() -> service.latestForOrder(orderA)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("还没有跑过");

    // B 租户列表里看不到 A 的任何一行。
    assertThat(asB(() -> service.pageChecks(null, null, null, null, 0, 50)).total()).isZero();
    // B 租户概览也是空的。
    assertThat(asB(() -> service.overview()).totalChecks()).isZero();
  }

  @Test
  void 两租户的禁运清单互不影响() {
    seedPolicy(a);
    seedPolicy(b);
    // A 把 US 禁运了；B 没有。
    seedEmbargo(a, "US", AddressPaymentEmbargoLevel.BLOCK);
    UUID orderA = seedOrder(a, "SO-A", "US");
    UUID orderB = seedOrder(b, "SO-B", "US");

    AddressPaymentCheckDetail detailA = assess(a, orderA);
    AddressPaymentCheckDetail detailB = assess(b, orderB);

    // A 的单被判不可投递，B 的单照样可投递 —— 判定源是**租户自己的**清单。
    assertThat(detailA.check().deliveryStatus()).isEqualTo("NOT_DELIVERABLE");
    assertThat(detailB.check().deliveryStatus()).isEqualTo("DELIVERABLE");
    // 两边的码表也各自独立。
    assertThat(asA(() -> service.pageEmbargoRules(null, null, 0, 50)).total()).isEqualTo(1);
    assertThat(asB(() -> service.pageEmbargoRules(null, null, 0, 50)).total()).isZero();
  }

  @Test
  void 一家的基线码表不会装到另一家头上() {
    seedPolicy(a);
    // B 没登记策略 ⇒ B 的码表是空的（A 的基线安装没有越界）。
    assertThat(asB(() -> service.pageCountryRules(null, 0, 50)).total()).isZero();
    assertThat(asB(() -> service.pagePaymentRules(null, null, 0, 50)).total()).isZero();
    assertThat(asA(() -> service.pageCountryRules(null, 0, 50)).total()).isEqualTo(34);
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================
  @Test
  void 伪造租户上下文无效() {
    seedPolicy(a);
    UUID orderA = seedOrder(a, "SO-A", "US");
    AddressPaymentCheckDetail detailA = assess(a, orderA);

    // 伪造一个 app.current_tenant（等价于伪造 X-Tenant-Id 头）⇒ 读不到 A 的行。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "trade.orders.read",
                    () -> {
                      sql.execute(
                          "select set_config('app.current_tenant','"
                              + b
                              + "',true)");
                      return service.check(detailA.check().id());
                    }))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于本租户");
  }

  @Test
  void 伪造租户上下文写出来的行仍落在会话租户名下() {
    seedPolicy(a);
    UUID orderA = seedOrder(a, "SO-A", "US");
    // 在 A 的会话里伪造 B 的租户上下文后写入 ⇒ RLS 的 WITH CHECK 把它按**会话租户**落库。
    var e =
        catchThrowable(
            () ->
                access.run(
                    actorA,
                    "trade.orders.update",
                    () -> {
                      sql.execute(
                          "select set_config('app.current_tenant','" + b + "',true)");
                      return service.assess(
                          new AddressPaymentCheckInput(orderA, AddressPaymentStatus.PAID,
                              AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                          user);
                    }));
    // 要么被 RLS 的 WITH CHECK 挡下（落不进 B），要么落在 A 名下 —— 两种都不该落在 B 名下。
    if (e == null) {
      long inB =
          sql.fetchOne(
                  "select count(*) c from order_address_check where tenant_id = ?", b)
              .get("c", Long.class);
      assertThat(inB).as("伪造租户上下文不该把行写进 B 名下").isZero();
    }
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 连接池固定一条时交替使用不串租户() {
    seedPolicy(a);
    seedPolicy(b);
    UUID orderA = seedOrder(a, "SO-A", "US");
    UUID orderB = seedOrder(b, "SO-B", "US");
    assess(a, orderA);
    assess(b, orderB);

    for (int round = 0; round < 5; round++) {
      // A → B → A 交替：连接池只有 1 条连接，租户上下文必须随事务一起换掉。
      assertThat(asA(() -> service.pageChecks(null, null, null, null, 0, 50)).total()).isEqualTo(1);
      assertThat(asA(() -> service.pageChecks(null, null, null, orderA, 0, 50)).total()).isEqualTo(1);
      assertThat(asA(() -> service.pageChecks(null, null, null, orderB, 0, 50)).total()).isZero();

      assertThat(asB(() -> service.pageChecks(null, null, null, null, 0, 50)).total()).isEqualTo(1);
      assertThat(asB(() -> service.pageChecks(null, null, null, orderB, 0, 50)).total()).isEqualTo(1);
      assertThat(asB(() -> service.pageChecks(null, null, null, orderA, 0, 50)).total()).isZero();
    }
  }

  @Test
  void 事务外读零行() {
    seedPolicy(a);
    UUID orderA = seedOrder(a, "SO-A", "US");
    assess(a, orderA);
    // 不带租户上下文读 ⇒ 一行都读不到（RLS 的 USING 谓词不成立）。
    assertThat(
            sql.fetchOne("select count(*) c from order_address_check").get("c", Long.class))
        .as("没有租户上下文时不该读到任何行")
        .isZero();
  }
}
