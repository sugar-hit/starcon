package com.starcon.trade;

import static com.starcon.trade.OrderConsolidationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 9.3.1.2 合单。
 *
 * <p>本测试是 <b>V144 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条
 * 连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉住的九条</b>：
 *
 * <ol>
 *   <li><b>候选集合是真 SQL</b>：同一买家 + 同一收货地址命中多张单，且每张单的排除都带原因码；
 *   <li><b>「同一地址」不等于「同一买家」</b>：家庭成员 / 同地址不同人必须被判 BUYER_MISMATCH，
 *       而有同一 catalog customer_party_id 的两张单即使姓名不同也要判同一买家；
 *   <li><b>跨店铺同买家</b>：cross_shop / distinct_platform_count 由 DB 实算，
 *       且**每个平台订单各有一条回传任务**（蓝图加粗句的落点）；
 *   <li><b>非平台单不建回传任务</b>：手工单没有平台可回传；
 *   <li><b>运费节省是真实相减</b>：没报价是 null 而不是 0；报价了必须等于「分开发 − 合并发」；
 *   <li><b>一个订单不能进两个未作废的合并</b>（双发是最贵的一种错），作废之后可以再合；
 *   <li><b>时间钉死并断言写回值</b>：合单时刻与提交时刻都用显式时刻，断言写回值而不是「没抛异常」；
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户，
 *       外加**跨租户不可合单**（A 会话拿 B 的订单 id 必须失败）；
 *   <li><b>没有合单规则就不许落合并</b>：不给隐含默认值。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderConsolidationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID partySame = UUID.randomUUID(), partyOther = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
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

  @Autowired OrderConsolidationService service;
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
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  // ------------------------------------------------------------ 夹具常量（时间全部钉死）

  /** 钉死的提交时刻。用 now() 喂一个会被断言的分片键，只会在别的时段才炸。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:15:30Z");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-03-01T10:45:00Z");
  static final OffsetDateTime T3 = OffsetDateTime.parse("2026-03-01T21:00:00Z");

  static final String CITY = "LOS ANGELES";
  static final String COUNTRY = "US";
  static final String PROVINCE = "CA";
  static final String POSTCODE = "90001";
  static final String LINE1 = "100 Main St";
  static final String RECIPIENT = "Ada Lovelace";
  static final String PHONE = "+1-213-555-0100";
  static final String EMAIL = "ada@example.com";

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_consolidation_event, order_consolidation_writeback_task,"
              + " order_consolidation_member, order_consolidation, order_consolidation_rule,"
              + " order_fulfillment_line_progress, order_charge, order_address_snapshot,"
              + " sales_order_party_snapshot, sales_order_line, sales_order cascade");
    }
  }

  /** 一张订单夹具。参数齐了才能覆盖币种 / 支付方式 / 平台来源 / 地址这些判定维度。 */
  record OrderSpec(
      String orderNo,
      String orderSource,
      String externalOrderNo,
      String currency,
      String paymentMethod,
      boolean submitted,
      OffsetDateTime submittedAt,
      BigDecimal qty,
      String shippingAmount,
      UUID partyId,
      String recipient,
      String phone,
      String email,
      String city,
      String line1,
      String province,
      BigDecimal shippedQty) {}

  static OrderSpec spec(String orderNo) {
    return spec(orderNo, "AMAZON");
  }

  /**
   * 默认夹具：同一买家（partySame）、同一地址、AMAZON、USD、PREPAID、T1 提交。
   *
   * <p>默认就带 {@code customer_party_id} 不是可选的：V99 的 guard_sales_order_submitted
   * 触发器**要求 SUBMITTED 订单必须有客户快照**（缺了它订单挂不到任何对手方名下）。
   * 换句话说可合单的订单在生产里一定带客户逻辑 ID，「同一买家」的首选口径必然是它，
   * 地址身份只是退回路径。
   */
  static OrderSpec spec(String orderNo, String source) {
    return new OrderSpec(
        orderNo, source, "EXT-" + orderNo, "USD", "PREPAID", true, T1, new BigDecimal("2.000000"),
        "8.5000", partySame, RECIPIENT, PHONE, EMAIL, CITY, LINE1, PROVINCE, null);
  }

  static OrderSpec with(OrderSpec s, Consumer1 c) {
    return c.apply(s);
  }

  /** 极小的 with-copy 辅助（避免为一堆可选参数写十个重载）。 */
  interface Consumer1 {
    OrderSpec apply(OrderSpec s);
  }

  static OrderSpec copy(OrderSpec s) {
    return new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), s.partyId(), s.recipient(),
        s.phone(), s.email(), s.city(), s.line1(), s.province(), s.shippedQty());
  }

  UUID order(UUID tenant, OrderSpec s) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          OffsetDateTime submittedAt = s.submitted() ? s.submittedAt() : null;
          // 头金额必须与明细行和费用行真值一致：V99 的 guard_sales_order_totals
          // 会在提交时独立复算（头与行两个答案会让订单总额无法对账）。
          BigDecimal lineTotal = s.qty().multiply(new BigDecimal("10.0000"));
          BigDecimal shipping =
              s.shippingAmount() == null ? BigDecimal.ZERO : new BigDecimal(s.shippingAmount());
          BigDecimal grand = lineTotal.add(shipping);
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,0,?,?::timestamptz,?::numeric,0,0,?::numeric,0,"
                  + "?::numeric,'',0,?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              s.orderNo(),
              s.orderSource(),
              "MANUAL".equals(s.orderSource()) ? "OFFLINE_STORE" : "",
              s.externalOrderNo(),
              s.currency(),
              s.paymentMethod(),
              s.submitted() ? "SUBMITTED" : "DRAFT",
              submittedAt,
              lineTotal,
              shipping,
              grand,
              s.submittedAt(),
              s.submittedAt());
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,?,10.0000,0,0,?::numeric,'',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              UUID.randomUUID(),
              "SKU-" + s.orderNo(),
              "测试商品 " + s.orderNo(),
              s.qty(),
              s.qty().multiply(new BigDecimal("10.0000")).setScale(4),
              s.submittedAt());
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING',?,?,?,'',?,?,?,'',?,?,'','','',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              s.recipient(),
              s.phone(),
              s.email(),
              COUNTRY,
              s.province(),
              s.city(),
              POSTCODE,
              s.line1(),
              s.submittedAt());
          if (s.shippingAmount() != null) {
            sql.execute(
                "insert into order_charge(id,tenant_id,order_id,charge_type,label,amount,"
                    + "charge_kind,note,created_at)"
                    + " values(?,?,?,'SHIPPING','运费',?::numeric,'ORDER','',?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                orderId,
                new BigDecimal(s.shippingAmount()),
                s.submittedAt());
          }
          if (s.partyId() != null) {
            sql.execute(
                "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                    + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                    + " values(?,?,?,?,?,?,'INDIVIDUAL','USD',?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                orderId,
                s.partyId(),
                "C-" + s.partyId().toString().substring(0, 8),
                s.recipient(),
                s.submittedAt());
          }
          if (s.shippedQty() != null && s.shippedQty().signum() > 0) {
            UUID lineId =
                sql.fetchOne(
                        "select id from sales_order_line where order_id = ?", orderId)
                    .get("id", UUID.class);
            sql.execute(
                "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
                    + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
                    + "observed_at,version,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,0,?,?::timestamptz,'MODULE_9_3_3_1',?::timestamptz,0,"
                    + "?::timestamptz,?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                orderId,
                lineId,
                s.qty(),
                s.shippedQty(),
                s.shippedQty(),
                s.submittedAt(),
                s.submittedAt(),
                s.submittedAt(),
                s.submittedAt());
          }
          return null;
        });
    return orderId;
  }

  /** 常见夹具：一批同地址同买家的订单。 */
  void rule(int maxOrders, boolean crossShop, Integer windowHours) {
    asA(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(
                  maxOrders, crossShop, windowHours, true, true, true),
              actorA.membershipId());
          return null;
        });
  }

  Map<String, Object> candidateBody() {
    return Map.of(
        "recipientName", RECIPIENT,
        "recipientPhone", PHONE,
        "recipientEmail", EMAIL,
        "addressLine1", LINE1,
        "countryCode", COUNTRY,
        "province", PROVINCE,
        "city", CITY,
        "postcode", POSTCODE);
  }

  /**
   * 候选检索（**必须**包在可信事务里）。
   *
   * <p>裸调用 {@code service.candidates(...)} 会恒返回空集：FORCE RLS 在没有
   * {@code app.current_tenant} 时看不到任何 sales_order 行，而候选集合一条都查不出来。
   * 这正是「看起来测试通过了、其实什么都没验到」的那类陷阱。
   */
  CandidateSet candidates() {
    return asA(
        () ->
            service.candidates(
                new CandidateQuery(RECIPIENT, PHONE, EMAIL, LINE1, "", COUNTRY, PROVINCE, CITY,
                    "", POSTCODE, true)));
  }

  // ================================================================ 能力与规则

  @Test
  void 能力声明如实报出下游缺口与本租户是否已配规则() {
    // 必须包在可信事务里：capabilities() 会读 ruleConfigured，
    // RLS 在没有 app.current_tenant 时一行都看不到，裸调用恒返回 false。
    var caps = asA(() -> service.capabilities());
    assertThat(caps.ruleConfigured()).as("夹具起始没有规则").isFalse();
    assertThat(caps.platformShopResolved())
        .as("D5 平台单同步未交付，shop_code 不能真正区分店铺 —— 必须如实报 false")
        .isFalse();
    var dimensions = caps.dimensions().stream().map(d -> d.code()).toList();
    assertThat(dimensions)
        .as("候选订单集合这一维必须 available=true（9.1.1.1 已交付）")
        .contains("CANDIDATE_ORDERS");
    assertThat(caps.downstreamGaps())
        .as("下游缺口要点名 8.3.3.2 打包 / 9.3.3.1 履约请求 / 9.4.2.1 回传")
        .contains("8.3.3.2")
        .contains("9.3.3.1")
        .contains("9.4.2.1");
    rule(5, true, null);
    assertThat(asA(() -> service.capabilities()).ruleConfigured()).isTrue();
  }

  @Test
  void 合单规则是一租户一条的upsert且校验真实() {
    asA(
        () -> {
          var r1 =
              service.saveRule(
                  new ConsolidationRuleInput(3, true, 6, true, true, true), actorA.membershipId());
          assertThat(r1.maxOrdersPerConsolidation()).isEqualTo(3);
          assertThat(r1.maxWindowHours()).isEqualTo(6);
          var r2 =
              service.saveRule(
                  new ConsolidationRuleInput(8, false, null, true, false, true),
                  actorA.membershipId());
          assertThat(r2.maxOrdersPerConsolidation()).isEqualTo(8);
          assertThat(r2.allowCrossShop()).isFalse();
          assertThat(r2.maxWindowHours()).isNull();
          assertThat(r2.version()).as("upsert 必须真的更新版本而不是新起一行").isEqualTo(1);
          assertThat(
                  sql.fetchOne(
                          "select count(*) c from order_consolidation_rule where tenant_id = ?",
                          actorA.tenantId())
                      .get("c", Integer.class))
              .isEqualTo(1);
          return null;
        });
    // 两个租户各一条，互不影响。
    asB(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(4, true, null, true, true, true), actorB.membershipId());
          assertThat(service.rule().maxOrdersPerConsolidation()).isEqualTo(4);
          return null;
        });
    assertThat(asA(() -> service.rule()).maxOrdersPerConsolidation())
        .as("A 的规则不被 B 覆盖")
        .isEqualTo(8);
  }

  @Test
  void 合单规则拒绝把功能关掉或把不限写成零() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveRule(
                            new ConsolidationRuleInput(1, true, null, true, true, true),
                            actorA.membershipId())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("至少是 2");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveRule(
                            new ConsolidationRuleInput(5, true, 0, true, true, true),
                            actorA.membershipId())))
        .hasMessageContaining("不限就留空");
    assertThat(ruleCount()).as("被拒的规则不许落库").isZero();
  }

  long ruleCount() {
    return asA(
        () ->
            sql.fetchOne("select count(*) c from order_consolidation_rule where tenant_id = ?",
                    actorA.tenantId())
                .get("c", Long.class));
  }

  // ================================================================ 候选检索

  @Test
  void 候选集合是真SQL并按同买家同地址命中() {
    rule(10, true, null);
    order(a, spec("SO-1"));
    order(a, spec("SO-2"));
    // 地址不同的另一张单：不该出现。
    order(a, with(spec("SO-FAR"), s -> {
      OrderSpec c = copy(s);
      return new OrderSpec(c.orderNo(), c.orderSource(), c.externalOrderNo(), c.currency(),
          c.paymentMethod(), c.submitted(), c.submittedAt(), c.qty(), c.shippingAmount(),
          c.partyId(), c.recipient(), c.phone(), c.email(), "BERKELEY", c.line1(), c.province(),
          c.shippedQty());
    }));

    var set = candidates();
    assertThat(set.candidates()).extracting(CandidateOrder::orderNo).containsExactlyInAnyOrder("SO-1", "SO-2");
    assertThat(set.excluded()).isEmpty();
    assertThat(set.candidateCount()).isEqualTo(2);
    assertThat(set.separateShippingTotal())
        .as("运费合计来自 order_charge 的 SHIPPING 行实算，不是代码常量")
        .isEqualByComparingTo(new BigDecimal("17.0000"));
    assertThat(set.candidates().get(0).buyerFingerprint())
        .as("买家指纹要与候选行一致")
        .isEqualTo(set.buyerFingerprint());
  }

  @Test
  void 同地址不同客户判买家不符而不合并() {
    rule(10, true, null);
    order(a, spec("SO-MINE"));
    // 同一收货地址、姓名电话邮箱**全都一样**，只是 catalog 客户 ID 不同
    // （家人 / 同事代收 / 合租最常见的就是这种）。必须判为不同买家。
    order(a, with(spec("SO-NEIGHBOR"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), partyOther,
        s.recipient(), s.phone(), s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));

    var set = candidates();
    assertThat(set.candidates()).extracting(CandidateOrder::orderNo).containsExactly("SO-MINE");
    assertThat(set.excluded())
        .as("同地址的另一家人必须被显式排除并带原因，否则会被合进别人的包裹")
        .singleElement()
        .satisfies(e -> {
          assertThat(e.orderNo()).isEqualTo("SO-NEIGHBOR");
          assertThat(e.reason()).isEqualTo(ExclusionReason.BUYER_MISMATCH);
        });
  }

  @Test
  void 同一catalog客户ID的两张单即使姓名不同也判同一买家() {
    rule(10, true, null);
    // 两张单的 customer_party_id 相同，但姓名 / 电话 / 邮箱写法完全不同（换了称呼）。
    // 「同一买家」的口径是客户逻辑 ID，所以必须判同一买家 —— 反过来用姓名电话去判
    // 就会把同一客户的重复订单拆成两箱，正好与合单省运费的目的相反。
    order(a, with(spec("SO-A"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), partySame,
        "Ada Lovelace", PHONE, EMAIL, s.city(), s.line1(), s.province(), s.shippedQty()))));
    order(a, with(spec("SO-B"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), partySame,
        "A. Lovelace", "+1-213-555-7788", "ada.l@example.com", s.city(), s.line1(), s.province(),
        s.shippedQty()))));
    // 另一个客户 ID 住在同一地址：必须被排除。
    order(a, with(spec("SO-C"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), partyOther,
        RECIPIENT, PHONE, EMAIL, s.city(), s.line1(), s.province(), s.shippedQty()))));

    var set = candidates();
    assertThat(set.candidates())
        .as("同一 customer_party_id 就是同一买家，不受姓名电话邮箱写法影响")
        .extracting(CandidateOrder::orderNo)
        .containsExactlyInAnyOrder("SO-A", "SO-B");
    assertThat(set.excluded()).extracting(ExcludedOrder::reason)
        .containsExactly(ExclusionReason.BUYER_MISMATCH);
  }

  @Test
  void 未提交已发货已合并超窗各带自己的原因码() {
    rule(10, true, 2);
    order(a, spec("SO-OK1", "AMAZON"));                      // T1 提交
    order(a, with(spec("SO-LATE"), s -> copy(new OrderSpec(    // T3，超出 2 小时窗
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), T3, s.qty(), s.shippingAmount(), s.partyId(), s.recipient(), s.phone(),
        s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));
    order(a, with(spec("SO-SHIPPED"), s -> copy(new OrderSpec( // 已发 1 件
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), s.partyId(), s.recipient(),
        s.phone(), s.email(), s.city(), s.line1(), s.province(), new BigDecimal("1.000000")))));
    order(a, with(spec("SO-DRAFT"), s -> copy(new OrderSpec(    // 未提交
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        false, T1, s.qty(), s.shippingAmount(), s.partyId(), s.recipient(), s.phone(), s.email(),
        s.city(), s.line1(), s.province(), s.shippedQty()))));

    var set = candidates();
    assertThat(set.excluded())
        .extracting(ExcludedOrder::orderNo, ExclusionReasonLabel())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("SO-DRAFT", "订单未提交"),
            org.assertj.core.groups.Tuple.tuple("SO-SHIPPED", "已发货部分数量"),
            org.assertj.core.groups.Tuple.tuple("SO-LATE", "超出可合单时间窗"));
    assertThat(set.candidates()).extracting(CandidateOrder::orderNo).containsExactly("SO-OK1");
  }

  private static java.util.function.Function<ExcludedOrder, String> ExclusionReasonLabel() {
    return ExcludedOrder::reasonLabel;
  }

  @Test
  void 时间窗锚点是数据里的最早提交时刻而不是服务器当前时间() {
    rule(10, true, 2);
    // 全部是 2026-03-01 的单：若误用 now() 当锚点，这三张都会被判「超窗」。
    order(a, spec("SO-W1"));
    order(a, with(spec("SO-W2"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), T2, s.qty(), s.shippingAmount(), s.partyId(), s.recipient(), s.phone(),
        s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));
    order(a, with(spec("SO-W3"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), T3, s.qty(), s.shippingAmount(), s.partyId(), s.recipient(), s.phone(),
        s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));
    // T1 → T3 跨越 13 小时 > 2 小时窗，只有 T1 那张可合。
    assertThat(candidates().candidates()).extracting(CandidateOrder::orderNo).containsExactly("SO-W1");
  }

  // ================================================================ 落合并

  @Test
  void 没有规则时落合并被拒且不给隐含默认值() {
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.consolidate(
                            new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                            actorA.membershipId())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("尚未登记合单规则");
    assertThat(consolidationCount(a)).isZero();
  }

  @Test
  void 落合并写入头成员与逐平台回传任务并由DB实算汇总() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1", "AMAZON"));
    UUID o2 = order(a, spec("SO-2", "SHOPIFY"));
    UUID o3 = order(a, with(spec("SO-3", "MANUAL"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), s.partyId(), s.recipient(),
        s.phone(), s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));

    var saved =
        asA(
            () ->
                service.consolidate(
                    new ConsolidationInput(List.of(o1, o2, o3), T2, null, "同买家同地址"),
                    actorA.membershipId()));

    var detail = asA(() -> service.consolidation(saved.id()));
    var c = detail.consolidation();
    assertThat(c.orderCount()).as("order_count 由 DB 实算").isEqualTo(3);
    assertThat(c.totalQty()).isEqualByComparingTo(new BigDecimal("6.000000"));
    assertThat(c.separateShippingAmount())
        .as("separate_shipping 是 order_charge 实算的合计")
        .isEqualByComparingTo(new BigDecimal("25.5000"));
    assertThat(c.distinctPlatformCount())
        .as("AMAZON + SHOPIFY = 2 个平台；手工单不产生平台")
        .isEqualTo(2);
    assertThat(c.crossShop()).as("跨店铺同买家必须被识别出来").isTrue();
    assertThat(c.freightSavingAmount()).as("没报价合并运费时是 null，不是 0").isNull();
    assertThat(c.currencyCode()).isEqualTo("USD");
    assertThat(detail.members()).hasSize(3);

    // 蓝图加粗句：一个包裹里有多少个平台订单就有多少个回传任务；手工单不建。
    assertThat(detail.writebackTasks())
        .as("逐个平台订单一条回传任务，手工单不建")
        .hasSize(2)
        .extracting(WritebackTask::platformCode)
        .containsExactlyInAnyOrder("AMAZON", "SHOPIFY");
    assertThat(detail.writebackTasks()).allSatisfy(t -> {
      assertThat(t.status()).isEqualTo(WritebackStatus.PENDING);
      assertThat(t.statusLabel()).isEqualTo("待回传");
    });
    assertThat(detail.events()).isNotEmpty();
  }

  @Test
  void 报价合并运费时节省额等于真实相减而不是拍数() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2")); // 两单运费 8.5 + 8.5 = 17.0
    var saved =
        asA(
            () ->
                service.consolidate(
                    new ConsolidationInput(List.of(o1, o2), T2, new BigDecimal("11.25"), ""),
                    actorA.membershipId()));
    var c = asA(() -> service.consolidation(saved.id())).consolidation();
    assertThat(c.consolidatedShippingAmount()).isEqualByComparingTo(new BigDecimal("11.2500"));
    assertThat(c.freightSavingAmount())
        .as("17.00 − 11.25 = 5.75，由 DB 触发器独立复算")
        .isEqualByComparingTo(new BigDecimal("5.7500"));
  }

  @Test
  void 合单时刻按钉死值写回且能被断言() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    OffsetDateTime mergedAt = OffsetDateTime.parse("2026-03-01T12:34:56Z");
    var saved =
        asA(
            () ->
                service.consolidate(
                    new ConsolidationInput(List.of(o1, o2), mergedAt, null, ""),
                    actorA.membershipId()));
    var c = asA(() -> service.consolidation(saved.id())).consolidation();
    assertThat(c.consolidatedAt())
        .as("timestamptz 必须原样写回（OffsetDateTime 绑定，不是 Timestamp.valueOf 的 −28800s）")
        .isEqualTo(mergedAt);
    assertThat(c.createdAt()).as("created_at 由 now() 生成，落在合理区间").isNotNull();
  }

  @Test
  void 合单时刻早于成员提交时刻被拒() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));   // T1
    UUID o2 = order(a, spec("SO-2"));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.consolidate(
                            new ConsolidationInput(
                                List.of(o1, o2), OffsetDateTime.parse("2026-02-28T00:00:00Z"), null, ""),
                            actorA.membershipId())))
        .hasMessageContaining("不能早于最早一笔");
  }

  @Test
  void 一个订单不能进两个未作废的合并但作废后可以再来() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    var first =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                            actorA.membershipId())))
        .as("同一张单进两个包裹 = 两个包裹都会发货 = 双发")
        .hasMessageContaining("不能重复合单");

    asA(
        () -> {
          service.advanceStatus(
              first.id(),
              new ConsolidationStatusInput(ConsolidationStatus.CANCELLED, "买家要求分开发",
                  OffsetDateTime.parse("2026-03-01T13:00:00Z")),
              actorA.membershipId());
          return null;
        });
    var second =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    assertThat(second.id()).isNotEqualTo(first.id());
  }

  @Test
  void 跨店铺规则关闭时拒绝合并并由DB独立复核() {
    rule(10, false, null);
    UUID o1 = order(a, spec("SO-1", "AMAZON"));
    UUID o2 = order(a, spec("SO-2", "SHOPIFY"));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                            actorA.membershipId())))
        .hasMessageContaining("不允许跨店铺");

    // 绕过服务层直接写：DB 的 guard_order_consolidation_shape 也必须挡住。
    // 关键：要让 DB 实算出 cross_shop=true，就得挂**两个不同平台**的成员行；
    // 只挂一个成员时 head.cross_shop 自然是 false，触发器没有可拒的东西，
    // 那种用例会「通过」但其实什么都没验到。
    assertThatThrownBy(
        () ->
            asA(
                () -> {
                  UUID cid = UUID.randomUUID();
                  sql.execute(
                      "insert into order_consolidation(id,tenant_id,consolidation_no,status,"
                          + "buyer_fingerprint,address_fingerprint,currency_code,"
                          + "consolidated_at,note,version,created_by_membership_id,created_at,"
                          + "updated_at) values(?,'"
                          + actorA.tenantId()
                          + "',?,  'PLANNED','A:x','A:y','USD',?::timestamptz,'',0,?,"
                          + "?::timestamptz,?::timestamptz)",
                      cid,
                      "CO-FORGED-" + cid.toString().substring(0, 6),
                      T2,
                      actorA.membershipId(),
                      OffsetDateTime.now(),
                      OffsetDateTime.now());
                  for (String[] pair :
                      new String[][] {{o1.toString(), "AMAZON"}, {o2.toString(), "SHOPIFY"}}) {
                    sql.execute(
                        "insert into order_consolidation_member(id,tenant_id,consolidation_id,"
                            + "order_id,order_no,order_source,platform_code,currency_code,"
                            + "member_status,created_at)"
                            + " values(?,?,?,?::uuid,?,?,?,'USD','PLANNED',?::timestamptz)",
                        UUID.randomUUID(),
                        actorA.tenantId(),
                        cid,
                        UUID.fromString(pair[0]),
                        "FORGED-" + pair[1],
                        pair[1],
                        pair[1],
                        OffsetDateTime.now());
                  }
                  return null;
                }))
        .hasMessageContaining("不允许跨店铺合并");
  }

  @Test
  void 状态机单向且终态不可离开() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    var saved =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    OffsetDateTime at = OffsetDateTime.parse("2026-03-01T14:00:00Z");

    // PLANNED → DISPATCHED 越级：拒绝。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(),
                            new ConsolidationStatusInput(ConsolidationStatus.DISPATCHED, null, at),
                            actorA.membershipId())))
        .hasMessageContaining("非法状态流转");

    var confirmed =
        asA(
            () ->
                service.advanceStatus(
                    saved.id(),
                    new ConsolidationStatusInput(ConsolidationStatus.CONFIRMED, null, at),
                    actorA.membershipId()));
    assertThat(confirmed.status()).isEqualTo(ConsolidationStatus.CONFIRMED);
    assertThat(asA(() -> service.consolidation(saved.id())).consolidation().confirmedAt())
        .as("确认时刻按显式值写回并可断言")
        .isEqualTo(at);

    var dispatched =
        asA(
            () ->
                service.advanceStatus(
                    saved.id(),
                    new ConsolidationStatusInput(ConsolidationStatus.DISPATCHED, null, at),
                    actorA.membershipId()));
    assertThat(dispatched.status()).isEqualTo(ConsolidationStatus.DISPATCHED);
    assertThat(dispatched.message()).as("提示要说清下一步仍要逐单回传").contains("逐单登记");

    // DISPATCHED 之后不可作废：包裹已进打包。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(),
                            new ConsolidationStatusInput(ConsolidationStatus.CANCELLED, "晚了", at),
                            actorA.membershipId())))
        .hasMessageContaining("非法状态流转");

    assertThat(asA(() -> service.consolidation(saved.id())).members())
        .allSatisfy(m -> assertThat(m.memberStatus()).isEqualTo(MemberStatus.DISPATCHED));
  }

  @Test
  void 作废必须给原因() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    var saved =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(),
                            new ConsolidationStatusInput(
                                ConsolidationStatus.CANCELLED, "  ", OffsetDateTime.now()),
                            actorA.membershipId())))
        .hasMessageContaining("作废必须给原因");
  }

  // ================================================================ 回传

  @Test
  void 物流号按平台订单逐单登记且成功必须带号() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1", "AMAZON"));
    UUID o2 = order(a, spec("SO-2", "SHOPIFY"));
    var saved =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    var detail = asA(() -> service.consolidation(saved.id()));
    assertThat(detail.writebackTasks()).hasSize(2);
    UUID memberId = detail.members().get(0).id();
    OffsetDateTime at = OffsetDateTime.parse("2026-03-01T15:00:00Z");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordWriteback(
                            saved.id(),
                            memberId,
                            new WritebackInput(WritebackStatus.CONFIRMED, "", "UPS", null, at),
                            actorA.membershipId())))
        .as("回传成功必须带物流号")
        .hasMessageContaining("必须带物流号");

    var task =
        asA(
            () ->
                service.recordWriteback(
                    saved.id(),
                    memberId,
                    new WritebackInput(WritebackStatus.CONFIRMED, "1Z999AA10123456784", "UPS", null, at),
                    actorA.membershipId()));
    assertThat(task.trackingNumber()).isEqualTo("1Z999AA10123456784");
    assertThat(task.status()).isEqualTo(WritebackStatus.CONFIRMED);
    assertThat(task.settledAt()).as("结算时刻按显式值写回").isEqualTo(at);
    assertThat(task.attemptCount()).isEqualTo(1);

    // 已回传是终态：改写会毁掉「这张平台单确实已经回传过」这个事实。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordWriteback(
                            saved.id(),
                            memberId,
                            new WritebackInput(WritebackStatus.REJECTED, null, null, "平台说错", at),
                            actorA.membershipId())))
        .hasMessageContaining("终态不可改写");

    // 另一张平台单仍是待回传 —— 逐单口径不允许一单成功带成全部成功。
    var page = asA(() -> service.pageWritebacks(new WritebackQuery(saved.id(), null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.pendingCount()).isEqualTo(1);
  }

  @Test
  void 手工单没有回传任务可登记() {
    rule(10, true, null);
    UUID o1 = order(a, with(spec("SO-1", "MANUAL"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), s.partyId(), s.recipient(),
        s.phone(), s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));
    UUID o2 = order(a, with(spec("SO-2", "MANUAL"), s -> copy(new OrderSpec(
        s.orderNo(), s.orderSource(), s.externalOrderNo(), s.currency(), s.paymentMethod(),
        s.submitted(), s.submittedAt(), s.qty(), s.shippingAmount(), s.partyId(), s.recipient(),
        s.phone(), s.email(), s.city(), s.line1(), s.province(), s.shippedQty()))));
    var saved =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    var detail = asA(() -> service.consolidation(saved.id()));
    assertThat(detail.writebackTasks()).isEmpty();
    UUID memberId = detail.members().get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordWriteback(
                            saved.id(), memberId,
                            new WritebackInput(WritebackStatus.CONFIRMED, "TN1", "UPS", null, T3),
                            actorA.membershipId())))
        .hasMessageContaining("没有回传任务");
  }

  // ================================================================ 列表

  @Test
  void 列表是真分页且汇总额不受分页影响() {
    rule(10, true, null);
    List<UUID> ids = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      UUID x = order(a, spec("SO-" + i));
      UUID y = order(a, spec("SO-" + i + "B"));
      ids.add(x);
      ids.add(y);
      asA(
          () ->
              service.consolidate(
                  new ConsolidationInput(
                      List.of(x, y), T2, new BigDecimal("9.00"), ""),
                  actorA.membershipId()));
    }
    var page = asA(() -> service.pageConsolidations(new ConsolidationQuery(null, null, null, null, 0, 2)));
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.page()).isZero();
    assertThat(page.freightSavingTotal())
        .as("5 单 × (17.00−9.00)=8.00 → 40.00")
        .isEqualByComparingTo(new BigDecimal("40.0000"));

    var filtered =
        asA(() -> service.pageConsolidations(new ConsolidationQuery(null, "PLANNED", null, null, 0, 50)));
    assertThat(filtered.total()).isEqualTo(5);
    var none = asA(() -> service.pageConsolidations(new ConsolidationQuery("ZZZ", null, null, null, 0, 50)));
    assertThat(none.total()).as("关键字不命中时明细为空").isZero();
    assertThat(none.freightSavingTotal())
        .as("汇总额是**本租户口径**而不是当前筛选口径：筛选命中 0 行时汇总仍是 40，"
            + "这正是「汇总不受分页/筛选影响」的定义")
        .isEqualByComparingTo(new BigDecimal("40.0000"));
  }

  // ================================================================ 多租户三断言

  // 断言 1：两租户互不可见
  @Test
  void 断言1_两租户互不可见() {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-A1"));
    UUID o2 = order(a, spec("SO-A2"));
    // 候选断言必须在**合并之前**：合完之后这两张单就进了 ALREADY_CONSOLIDATED，
    // 候选集自然空了 —— 那样断言会变成在测「已合并的单不再出现」，而不是「两租户互不可见」。
    assertThat(candidates().candidates()).extracting(CandidateOrder::orderNo)
        .as("A 只看得到自己的两张单，B 的同地址同买家单一条都不出现")
        .containsExactlyInAnyOrder("SO-A1", "SO-A2");
    var savedA =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));

    UUID p1 = order(b, spec("SO-B1"));
    UUID p2 = order(b, spec("SO-B2"));
    asB(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(10, true, null, true, true, true), actorB.membershipId());
          service.consolidate(new ConsolidationInput(List.of(p1, p2), T2, null, ""),
              actorB.membershipId());
          return null;
        });

    var listA = asA(() -> service.pageConsolidations(new ConsolidationQuery(null, null, null, null, 0, 50)));
    assertThat(listA.total()).as("A 只看得到自己那一单").isEqualTo(1);
    assertThat(listA.items()).allSatisfy(c -> assertThat(c.id()).isEqualTo(savedA.id()));
    var listB = asB(() -> service.pageConsolidations(new ConsolidationQuery(null, null, null, null, 0, 50)));
    assertThat(listB.total()).isEqualTo(1);
    assertThat(listB.items()).allSatisfy(c -> assertThat(c.id()).isNotEqualTo(savedA.id()));

    assertThatThrownBy(() -> asB(() -> service.consolidation(savedA.id())))
        .as("跨租户按 id 取合并单 → 404")
        .hasMessageContaining("不存在");

    // 候选检索同样隔离：B 的同地址订单不该出现在 A 的候选集里。
    // 这一步在 A 合并**之后**做也成立 —— A 的两张单已被自己合掉，B 的两张对 A 不可见，
    // 所以 A 看到的是一个空候选集而不是「B 的那两张」。
    assertThat(candidates().candidates())
        .as("A 看不到 B 的订单：合掉自己的两张之后候选集就空了")
        .isEmpty();
  }

  // 断言 1b：跨租户不可合单
  @Test
  void 断言1b_跨租户不可合单() {
    rule(10, true, null);
    // B 也必须有规则：否则请求会先被「未登记规则」409 挡掉，
    // 根本走不到跨租户那一层，断言就成了在测别的东西。
    asB(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(10, true, null, true, true, true), actorB.membershipId());
          return null;
        });
    UUID a1 = order(a, spec("SO-A1"));
    UUID a2 = order(a, spec("SO-A2"));
    UUID b1 = order(b, spec("SO-B1"));
    // 同买家指纹、同地址指纹，但分属两个租户。
    // 合单是严重的跨租户数据泄露：两家公司的货会进同一个包裹并互相看到对方买家信息。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.consolidate(
                            new ConsolidationInput(List.of(a1, a2, b1), T2, null, ""),
                            actorA.membershipId())))
        .as("A 的会话不得把 B 的订单合进来")
        .hasMessageContaining("不存在");
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.consolidate(
                            new ConsolidationInput(List.of(b1, a1), T2, null, ""),
                            actorB.membershipId())))
        .hasMessageContaining("不存在");
    assertThat(consolidationCount(a)).as("A 租户没有落下任何合并单").isZero();
    assertThat(consolidationCount(b)).as("B 租户也没有落下任何合并单").isZero();
  }

  // 断言 2：伪造租户上下文无效
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-A1"));
    UUID o2 = order(a, spec("SO-A2"));
    var savedA =
        asA(
            () ->
                service.consolidate(new ConsolidationInput(List.of(o1, o2), T2, null, ""),
                    actorA.membershipId()));
    UUID b1 = order(b, spec("SO-B1"));
    UUID b2 = order(b, spec("SO-B2"));
    asB(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(10, true, null, true, true, true), actorB.membershipId());
          service.consolidate(new ConsolidationInput(List.of(b1, b2), T2, null, ""),
              actorB.membershipId());
          return null;
        });

    // (1) DB 层：没有 app.current_tenant 上下文时 FORCE RLS 什么都看不到。
    assertThat(
            sql.fetchOne("select count(*) c from order_consolidation").get("c", Integer.class))
        .as("RLS 之外无租户上下文 = 一行都读不到").isZero();

    // (2) HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 仍然只看得到会话租户的行。
    http.perform(get("/trade/fulfillment/consolidation/consolidations")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(savedA.id().toString()));

    // (3) B 的会话 + 伪造指向 A 的头 → 依然只看得到 B 自己的合并单。
    String bOwnId =
        asB(
                () ->
                    service.pageConsolidations(
                            new ConsolidationQuery(null, null, null, null, 0, 50))
                        .items()
                        .get(0)
                        .id()
                        .toString());
    http.perform(get("/trade/fulfillment/consolidation/consolidations")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(bOwnId));
    assertThat(bOwnId).as("B 看到的必须是自己那一单，不是 A 的").isNotEqualTo(savedA.id().toString());
    http.perform(get("/trade/fulfillment/consolidation/consolidations/" + savedA.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // (4) 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B。
    // 用**全新的两张** B 单：b1/b2 已经在 B 自己的合并里，拿它们去合会被
    // 「不能重复合单」的 409 挡掉 —— 那是正确行为，却会让这条断言测错东西。
    UUID b3 = order(b, spec("SO-B3"));
    UUID b4 = order(b, spec("SO-B4"));
    http.perform(post("/trade/fulfillment/consolidation/consolidations")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "orderIds", List.of(b3, b4),
                        "consolidatedAt", "2026-03-01T10:45:00Z"))))
        .andExpect(status().isOk());
    assertThat(asA(() -> service.pageConsolidations(
            new ConsolidationQuery(null, null, null, null, 0, 50))).total())
        .as("伪造头不得把数据写进别的租户")
        .isEqualTo(1);
  }

  // 断言 3：连接池归还后不串租户
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    rule(10, true, null);
    UUID a1 = order(a, spec("SO-A1"));
    UUID a2 = order(a, spec("SO-A2"));
    asA(
        () ->
            service.consolidate(new ConsolidationInput(List.of(a1, a2), T2, null, ""),
                actorA.membershipId()));
    UUID b1 = order(b, spec("SO-B1"));
    UUID b2 = order(b, spec("SO-B2"));
    // 候选断言放在合并之前：合完之后 B 的两张单就进了 ALREADY_CONSOLIDATED，
    // 那样断言会变成在测「已合并不再出现」，而不是「连接池不串租户」。
    assertThat(
            asB(
                    () ->
                        service.candidates(
                            new CandidateQuery(
                                RECIPIENT, PHONE, EMAIL, LINE1, "", COUNTRY, PROVINCE, CITY, "",
                                POSTCODE, true))
                        .candidates())
                .size())
        .as("B 的会话只看得到自己的两张单")
        .isEqualTo(2);
    asB(
        () -> {
          service.saveRule(
              new ConsolidationRuleInput(10, true, null, true, true, true), actorB.membershipId());
          service.consolidate(new ConsolidationInput(List.of(b1, b2), T2, null, ""),
              actorB.membershipId());
          return null;
        });

    // 交替经由 HTTP 连来连去：连接池只有 1 条连接，
    // 每次请求后连接都归还过，若 app.current_tenant 泄漏就会串租户。
    for (int i = 0; i < 6; i++) {
      http.perform(get("/trade/fulfillment/consolidation/consolidations")
              .header("Authorization", "Bearer tenant-a"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].buyerName").value(RECIPIENT));
      http.perform(get("/trade/fulfillment/consolidation/consolidations")
              .header("Authorization", "Bearer tenant-b"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1));
      http.perform(post("/trade/fulfillment/consolidation/candidates")
              .header("Authorization", "Bearer tenant-b")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(candidateBody())))
          .andExpect(status().isOk())
          // B 的两张单此刻已合并 → 候选集为空。**空**才是这里要断言的：
          // 若连接池串了租户，A 的行会漏进来，候选集就不是空的。
          .andExpect(jsonPath("$.candidates.length()").value(0));
    }
    assertThat(asA(() -> service.pageConsolidations(
            new ConsolidationQuery(null, null, null, null, 0, 50))).total())
        .isEqualTo(1);
    assertThat(asB(() -> service.pageConsolidations(
            new ConsolidationQuery(null, null, null, null, 0, 50))).total())
        .isEqualTo(1);
  }

  // ================================================================ HTTP 边界

  @Test
  void 只读权限不能落合并() throws Exception {
    rule(10, true, null);
    UUID o1 = order(a, spec("SO-1"));
    UUID o2 = order(a, spec("SO-2"));
    http.perform(post("/trade/fulfillment/consolidation/consolidations")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of("orderIds", List.of(o1, o2), "consolidatedAt", "2026-03-01T10:45:00Z"))))
        .andExpect(status().isForbidden());
    assertThat(consolidationCount(a)).isZero();
  }

  @Test
  void 候选检索拒绝空地址而不是当成无匹配() throws Exception {
    http.perform(post("/trade/fulfillment/consolidation/candidates")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("recipientName", "x"))))
        .andExpect(status().isBadRequest());
  }

  /** 合并单条数（按租户）。刻意走可信事务：RLS 在没有 app.current_tenant 时读不到任何行。 */
  long consolidationCount(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne("select count(*) c from order_consolidation").get("c", Long.class));
  }
}
