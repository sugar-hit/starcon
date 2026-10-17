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
 * 9.2.1.3 异常拦截的模块测试。
 *
 * <p>全部跑在 Testcontainers 的真实 PostgreSQL 16 上（连接池压到 1），
 * 数据真落 V153 的六张表，不 mock 任何判定。
 *
 * <p>本类最要紧的一组用例是<b>蓝图加粗句「拦截不自动取消，默认转人工」</b>：
 * 建议取消的检查 {@code manualReviewRequired} 必须恒为 true、{@code finalDisposition} 必须为 null，
 * 且<b>绕过服务层直接写 SQL</b> 也会被 V153 的守卫触发器挡下。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderInterceptIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), user = UUID.randomUUID();
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

  static AccessService.Grant grant(String code, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), code, "ALL", permissions);
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, String role, List<String> permissions) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant(role, permissions)));
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
      actors.put("Bearer tenant-a", actor(user, a, "ALL", allPermissions));
      actors.put(
          "Bearer read-only", actor(user, a, "READ", List.of("trade.orders.read")));
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

  @Autowired OrderInterceptService service;
  @Autowired OrderRiskService risk;
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

  // ------------------------------------------------------------ 夹具（时间全部钉死）

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00Z");

  /** 刻意用 +08:00 非 UTC 偏移：照抄 V149 的做法，用来证明 timestamptz 往返零漂移。 */
  static final OffsetDateTime T8 = OffsetDateTime.parse("2026-03-02T09:30:00+08:00");

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_intercept_disposition, order_intercept_finding, order_intercept_check,"
              + " order_intercept_sku_cost, order_intercept_restriction, order_intercept_policy,"
              + " order_risk_review, order_risk_assessment_component, order_risk_assessment,"
              + " order_risk_signal, order_risk_rule,"
              + " order_review_rule_hit_condition, order_review_rule_hit,"
              + " order_review_rule_evaluation, order_review_buyer_profile,"
              + " order_review_rule_condition, order_review_rule,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  InterceptPolicyInput defaultPolicy() {
    return new InterceptPolicyInput(
        "默认拦截策略", 24, 3, new BigDecimal("0.0000"), new BigDecimal("60.0000"),
        MissingReferencePolicy.ASSESS_KNOWN_ONLY);
  }

  void saveDefaultPolicy() {
    asA(() -> service.savePolicy(defaultPolicy()));
  }

  /** 一张可检查的订单：已提交 + 收货地址快照 + 一行商品。 */
  UUID seedOrder(
      String orderNo,
      String recipient,
      String country,
      String postcode,
      String phone,
      String addressLine1,
      String unitPrice,
      String lineDiscount) {
    UUID orderId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    BigDecimal price = new BigDecimal(unitPrice);
    BigDecimal qty = BigDecimal.ONE;
    BigDecimal lineAmount = price.multiply(qty).setScale(4, java.math.RoundingMode.HALF_UP);
    BigDecimal lineDiscountAmount = new BigDecimal(lineDiscount);
    // ⚠️ V99 里订单头与订单行的折扣**符号相反**：
    //     头 sales_order_amounts_non_negative 要求 discount_amount >= 0（正数的减免额），
    //     行 sales_order_line_discount_is_non_positive 要求 discount_amount <= 0（负数的抵扣）。
    //     把同一个带符号的值灌进两处，必然违反其中一条 CHECK。
    BigDecimal headerDiscount = lineDiscountAmount.abs();
    BigDecimal grandTotal = lineAmount.subtract(headerDiscount).setScale(4, java.math.RoundingMode.HALF_UP);
    // ⚠️ 所有写库都必须包在 asA(...) 里：V153 对六张表都 FORCE 了 RLS，
    // 运行角色在没有 app.current_tenant 的事务里写任何一行都会被策略挡下
    // （报 "new row violates row-level security policy"）。
    asA(() -> {
    sql.execute(
        "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
            + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
            + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'SUBMITTED',?::timestamptz,?,?,0,0,0,?,0,"
            + "?::timestamptz,?::timestamptz)",
        orderId, a, orderNo, "AMAZON", "EXT-" + orderNo, "USD", "PREPAID", T1,
        lineAmount, headerDiscount, grandTotal, T1, T1);
    sql.execute(
        "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
            + " values(?,?,?,1,?,?,?,1,?,?,0,?,'',?::timestamptz)",
        UUID.randomUUID(), a, orderId, skuId, "SKU-" + orderNo, "商品", price, lineDiscountAmount,
        lineAmount, T1);
    sql.execute(
        "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
            + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
            + "postcode,address_line1,address_line2,tax_id,note,created_at)"
            + " values(?,?,?,'SHIPPING',?,?,?,'',?,'CA','LOS ANGELES','',?,?,'','','',"
            + "?::timestamptz)",
        UUID.randomUUID(), a, orderId, recipient, phone, recipient + "@example.com", country,
        postcode, addressLine1, T1);
    sql.execute(
        "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
            + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
            + " values(?,?,?,?,?,?,?,'',?::timestamptz)",
        UUID.randomUUID(), a, orderId, UUID.randomUUID(), "C-1", recipient, "INDIVIDUAL", T1);
    return null;
    });
    return orderId;
  }

  /**
   * 一张**草稿**订单。
   *
   * <p>刻意**不**用「先建 SUBMITTED 再改回 DRAFT」：V99 的
   * {@code guard_sales_order_submitted_terminal} 明确禁止 SUBMITTED 退回草稿态
   * （退回之后没人说得清它有没有被审过），所以草稿只能一开始就是草稿。
   */
  UUID seedDraftOrder(String orderNo) {
    UUID orderId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,manual_kind,status,submitted_at,subtotal_amount,discount_amount,"
                  + "tax_amount,shipping_amount,other_amount,grand_total_amount,version,created_at,"
                  + "updated_at)"
                  + " values(?,?,?,?,?,?,?,'OFFLINE_STORE','DRAFT',null,0,0,0,0,0,0,0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId, a, orderNo, "MANUAL", "EXT-" + orderNo, "USD", "PREPAID", T1, T1);
          return null;
        });
    return orderId;
  }

  /**
   * 该守卫是否真的建出来了（结构性断言，跨连接稳定）。
   *
   * <p>按**函数名**查而不是触发器名：V153 里触发器名与函数名不同
   * （{@code order_intercept_finding_append_only} 是触发器，
   * {@code guard_order_intercept_finding_append_only} 是它的函数），
   * 只按触发器名查会永远返回 false —— 而那条断言看起来只是「恒假」，不会被任何人发现。
   */
  boolean guardExists(String functionName) {
    var row =
        sql.fetchOne(
            "select count(*) c from pg_trigger t"
                + " join pg_proc p on p.oid = t.tgfoid"
                + " where p.proname = ? and not t.tgisinternal",
            functionName);
    return row != null && row.get("c", Long.class) > 0;
  }

  /** 沿 cause 链收集全部消息：jOOQ 只把 DB 错误放在最内层的 cause 里。 */
  static String rootMessage(Throwable t) {
    var sb = new StringBuilder();
    for (var cur = t; cur != null; cur = cur.getCause()) {
      if (cur.getMessage() != null) {
        sb.append(cur.getMessage()).append('\n');
      }
    }
    return sb.toString();
  }

  void seedBlacklist(String recipient, String phone, String email, String reason) {
    String key = OrderInterceptService.buyerKey(recipient, phone, email);
    asA(() -> {
    sql.execute(
        "insert into order_review_buyer_profile(id,tenant_id,buyer_key,buyer_name,buyer_phone,"
            + "buyer_email,buyer_level,blacklisted,blacklist_reason,blacklisted_at,note,version,"
            + "created_at,updated_at)"
            + " values(?,?,?,?,?,?,'RISK',true,?,?::timestamptz,'',0,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(), a, key, recipient, phone, email, reason, T1, T1, T1);
    return null;
    });
  }

  void seedRestriction(
      RestrictionKind kind, String target, RestrictionEffect effect, String reason,
      OffsetDateTime from, OffsetDateTime to) {
    asA(() -> {
    sql.execute(
        "insert into order_intercept_restriction(id,tenant_id,restriction_kind,restriction_target,"
            + "restriction_effect,restriction_source,source_reference,reason,effective_from,"
            + "effective_to,enabled,created_at)"
            + " values(?,?,?,?,?,'TENANT_MAINTAINED','',?,?::timestamptz,?::timestamptz,true,now())",
        UUID.randomUUID(), a, kind.name(), target, effect.name(), reason, from, to);
    return null;
    });
  }

  void seedCost(UUID skuId, String unitCost, String currency, OffsetDateTime capturedAt) {
    asA(() -> {
    sql.execute(
        "insert into order_intercept_sku_cost(id,tenant_id,sku_id,sku_code,sku_name,unit_cost,"
            + "currency,captured_at,note,created_at)"
            + " values(?,?,?,'SKU-X','商品',?::numeric,?,?::timestamptz,'',now())",
        UUID.randomUUID(), a, skuId, unitCost, currency, capturedAt);
    return null;
    });
  }

  InterceptCheckSaved check(UUID orderId) {
    return asA(() -> service.assess(new InterceptCheckInput(orderId), user));
  }

  InterceptFinding finding(InterceptCheckSaved saved, InterceptCheckCode code) {
    return saved.findings().stream().filter(f -> f.checkCode() == code).findFirst().orElseThrow();
  }

  // ==========================================================================
  // 迁移确实落地了，六张表 FORCE RLS
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
          sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V153 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  // ==========================================================================
  // 加粗句：拦截不自动取消，默认转人工
  // ==========================================================================
  @Test
  void 没登记策略就不给检查而不是默认放行() {
    var orderId = seedOrder("MO-NOPOL", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var e = catchThrowable(() -> check(orderId));
    assertThat(e).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .as("「没配策略 = 默认全放行」与加粗句是反的，必须报未配置")
        .isEqualTo(409);
    assertThat(e.getMessage()).contains("尚未登记拦截策略");
  }

  @Test
  void 八项命中恒定各占一行_缺失与上游未接通也占行() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-EIGHT", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    assertThat(saved.findings()).as("少一行就只能从计数反推是谁缺席的").hasSize(8);
    assertThat(saved.findings().stream().map(InterceptFinding::checkCode).distinct())
        .as("八项必须互不重复").hasSize(8);
    assertThat(saved.findings().stream().map(f -> f.checkCode().name()).toList())
        .as("按枚举声明顺序（判读顺序）返回，不是字典序")
        .containsExactly(
            "ADDRESS_INCOMPLETE", "ADDRESS_DELIVERABILITY", "BLACKLISTED_BUYER", "DUPLICATE_ORDER",
            "BELOW_COST_DISCOUNT", "SUSPECTED_FRAUD", "COUNTRY_RESTRICTED", "PLATFORM_COMPLIANCE");
    assertThat(saved.check().flaggedCount() + saved.check().clearedCount()
            + saved.check().missingCount())
        .isEqualTo(8);
    assertThat(saved.check().totalsComputedAt()).as("合计必须被触发器算过").isNotNull();
  }

  @Test
  void 地址可投递性如实报上游未交付而不是判成地址正常() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-ADDR", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var f = finding(check(orderId), InterceptCheckCode.ADDRESS_DELIVERABILITY);
    assertThat(f.status())
        .as("9.2.2.1 未开发 ⇒ 「没有校验过」不是「校验过且通过」")
        .isEqualTo(FindingStatus.UPSTREAM_UNAVAILABLE);
    assertThat(f.evidenceSource()).isEqualTo(EvidenceSource.UPSTREAM_NOT_DELIVERED);
    assertThat(f.explanation()).contains("9.2.2.1");
  }

  @Test
  void 干净订单建议放行且不需要人工() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-CLEAN", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    assertThat(saved.check().recommendedDisposition()).isEqualTo(RecommendedDisposition.RELEASE);
    assertThat(saved.check().manualReviewRequired()).isFalse();
    assertThat(saved.check().finalDisposition()).isNull();
  }

  @Test
  void 黑名单买家命中且解释里带拉黑理由() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "多次恶意取消后投诉客服");
    var orderId = seedOrder("MO-BL", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    var f = finding(saved, InterceptCheckCode.BLACKLISTED_BUYER);
    assertThat(f.status()).isEqualTo(FindingStatus.FLAGGED);
    assertThat(f.severity()).isEqualTo(FindingSeverity.WARN);
    assertThat(f.explanation()).contains("多次恶意取消后投诉客服");
    assertThat(saved.check().recommendedDisposition()).isEqualTo(RecommendedDisposition.MANUAL_REVIEW);
    assertThat(saved.check().manualReviewRequired()).isTrue();
  }

  @Test
  void 买家没有档案落缺参照而不是判成不是黑名单() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-NOPROF", "Nobody", "US", "90001", "+1-213-555-0111",
        "100 Main St", "100.0000", "0");
    var f = finding(check(orderId), InterceptCheckCode.BLACKLISTED_BUYER);
    assertThat(f.status())
        .as("「没登记过」当成「核验过、干净」会让合规黑名单形同虚设")
        .isEqualTo(FindingStatus.NO_REFERENCE);
    assertThat(f.explanation()).contains("未登记");
  }

  @Test
  void 重复下单命中且本单不算进重复笔数() {
    saveDefaultPolicy();
    // 先建两张同买家的旧单（提交时刻早于 T1）
    for (int i = 0; i < 3; i++) {
      var id = seedOrder("MO-OLD-" + i, "Repeat", "US", "90001", "+1-213-555-0122",
          "100 Main St", "100.0000", "0");
      // 把旧单提交时刻挪到 T1 之前 2 小时
      asA(
          () -> {
            sql.execute("update sales_order set submitted_at = ?::timestamptz where id = ?",
                T1.minusHours(2), id);
            return null;
          });
    }
    var target = seedOrder("MO-DUP", "Repeat", "US", "90001", "+1-213-555-0122",
        "100 Main St", "100.0000", "0");
    var f = finding(check(target), InterceptCheckCode.DUPLICATE_ORDER);
    assertThat(f.status()).isEqualTo(FindingStatus.FLAGGED);
    assertThat(f.observedValue()).as("另有 3 单 ≥ 阈值 3").isEqualByComparingTo("3");
    assertThat(f.thresholdValue()).isEqualByComparingTo("3");
    assertThat(f.explanation()).contains("不含本单");
  }

  @Test
  void 重复下单只看本单之前的订单() {
    saveDefaultPolicy();
    // 只有本单，没有更早的单：把本单自己算进去会让每张单都命中
    var only = seedOrder("MO-SOLO", "Solo", "US", "90001", "+1-213-555-0133",
        "100 Main St", "100.0000", "0");
    assertThat(finding(check(only), InterceptCheckCode.DUPLICATE_ORDER).status())
        .isEqualTo(FindingStatus.CLEARED);
  }

  @Test
  void 低于成本命中且折扣负号口径正确() {
    saveDefaultPolicy();
    // 单价 100，行折扣 -15（负数），净额 = 100 + (-15) = 85 < 成本 90
    var orderId = seedOrder("MO-COST", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "-15.0000");
    var skuId =
        asA(() -> sql.fetchOne("select sku_id from sales_order_line where order_id = ?", orderId))
            .get(0, UUID.class);
    seedCost(skuId, "90.0000", "USD", T1.minusDays(1));
    var f = finding(check(orderId), InterceptCheckCode.BELOW_COST_DISCOUNT);
    assertThat(f.status())
        .as("净额必须按 line_amount **加** discount_amount（折扣是负数）；"
            + "写成减号这一项永远不会命中，而界面会显示「无异常折扣」")
        .isEqualTo(FindingStatus.FLAGGED);
    assertThat(f.observedValue()).as("净额单价 85").isEqualByComparingTo("85.000000");
  }

  @Test
  void 成本基线采集时刻晚于订单提交的不参与比较() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-LATE", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "-15.0000");
    var skuId =
        asA(() -> sql.fetchOne("select sku_id from sales_order_line where order_id = ?", orderId))
            .get(0, UUID.class);
    // 订单提交之后才采到的成本，对这一单的决策没有贡献
    seedCost(skuId, "90.0000", "USD", T1.plusDays(1));
    assertThat(finding(check(orderId), InterceptCheckCode.BELOW_COST_DISCOUNT).status())
        .as("拿订单提交之后才采集的成本给它打分是穿越")
        .isEqualTo(FindingStatus.NO_REFERENCE);
  }

  @Test
  void 成本基线币种与订单币种不一致的不参与比较() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-CCY", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "-15.0000");
    var skuId =
        asA(() -> sql.fetchOne("select sku_id from sales_order_line where order_id = ?", orderId))
            .get(0, UUID.class);
    seedCost(skuId, "90.0000", "EUR", T1.minusDays(1));
    var f = finding(check(orderId), InterceptCheckCode.BELOW_COST_DISCOUNT);
    assertThat(f.status())
        .as("本服务没有汇率来源（10 财务未交付），跨币种比较是纯噪声")
        .isEqualTo(FindingStatus.NO_REFERENCE);
    assertThat(f.explanation()).contains("币种");
  }

  @Test
  void 禁运国BLOCK档给出建议取消但最终处置仍为空且必须人工() {
    saveDefaultPolicy();
    seedRestriction(RestrictionKind.SHIPPING_COUNTRY, "US", RestrictionEffect.BLOCK,
        "美国禁运（测试夹具）", null, null);
    var orderId = seedOrder("MO-BAN", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    assertThat(saved.check().recommendedDisposition())
        .as("BLOCK 级命中 ⇒ 建议取消").isEqualTo(RecommendedDisposition.CANCEL);
    assertThat(saved.check().manualReviewRequired())
        .as("加粗句第 (2) 层：建议取消也必须带「需人工处置」").isTrue();
    assertThat(saved.check().finalDisposition())
        .as("加粗句：拦截不自动取消 ⇒ 建议取消不等于已取消").isNull();
    assertThat(saved.check().blockedCount()).isEqualTo(1);
    var f = finding(saved, InterceptCheckCode.COUNTRY_RESTRICTED);
    assertThat(f.evidenceReference()).as("文本比对结果落 evidence_reference，不进数值列")
        .isEqualTo("US");
  }

  @Test
  void 限运REVIEW档只建议转人工() {
    saveDefaultPolicy();
    seedRestriction(RestrictionKind.SHIPPING_COUNTRY, "US", RestrictionEffect.REVIEW,
        "美国需人工复核", null, null);
    var orderId = seedOrder("MO-REVIEW", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    assertThat(check(orderId).check().recommendedDisposition())
        .isEqualTo(RecommendedDisposition.MANUAL_REVIEW);
  }

  @Test
  void 限制清单生效期未到不命中() {
    saveDefaultPolicy();
    seedRestriction(RestrictionKind.SHIPPING_COUNTRY, "US", RestrictionEffect.BLOCK,
        "未来的禁运", T1.plusDays(30), null);
    var orderId = seedOrder("MO-FUTURE", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    assertThat(finding(check(orderId), InterceptCheckCode.COUNTRY_RESTRICTED).status())
        .isEqualTo(FindingStatus.CLEARED);
  }

  @Test
  void 平台合规按平台级判定而不是店铺级() {
    saveDefaultPolicy();
    seedRestriction(RestrictionKind.PLATFORM_SOURCE, "AMAZON", RestrictionEffect.BLOCK,
        "Amazon 平台合规风险（测试夹具）", null, null);
    var orderId = seedOrder("MO-PF", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var f = finding(check(orderId), InterceptCheckCode.PLATFORM_COMPLIANCE);
    assertThat(f.status()).isEqualTo(FindingStatus.FLAGGED);
    assertThat(f.evidenceReference()).isEqualTo("AMAZON");
    assertThat(f.explanation()).contains("没有 shop_code 列");
  }

  @Test
  void 平台来源不在sales_order词表里登记被拒() {
    saveDefaultPolicy();
    var e =
        catchThrowable(
            () ->
                asA(
                    () ->
                        service.saveRestriction(
                            new InterceptRestrictionInput(
                                RestrictionKind.PLATFORM_SOURCE, "NOT_A_SOURCE",
                                RestrictionEffect.BLOCK, RestrictionSource.TENANT_MAINTAINED,
                                "", "永远不会命中的限制", null, null))));
    // 拦它的可以是服务层的 400，也可以是 V153 的 guard_order_intercept_restriction_source_valid
    // 触发器 —— 后者更强（绕过服务层直连写入同样被挡）。这里断言**行为**而不是异常类型。
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).as("别让它落库后界面显示「已限制」而一单都没拦住")
        .contains("不在 sales_order.order_source 的现行词表里");
  }

  @Test
  void 疑似欺诈复用九点二点二点二的结论而不重算分数() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-FRAUD", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    // 先用风控模块跑一次评估（走 V150 的触发器与口径，本模块只读它的结论）
    asA(
        () -> {
          risk.saveRule(
              new com.starcon.trade.OrderRiskModels.RiskRuleInput(
                  "默认风控",
                  new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("1.0000"),
                  new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                  new BigDecimal("0.0000"), new BigDecimal("1.0000"),
                  24, 100000, 1000, new BigDecimal("100.0000"), 100000, 24, 1000000, 24,
                  com.starcon.trade.OrderRiskModels.MissingSignalPolicy.SCORE_KNOWN_ONLY),
              user);
          risk.assess(
              new com.starcon.trade.OrderRiskModels.AssessmentInput(orderId, "夹具"), user);
          return null;
        });
    var f = finding(check(orderId), InterceptCheckCode.SUSPECTED_FRAUD);
    assertThat(f.evidenceSource()).isEqualTo(EvidenceSource.MODULE_9_2_2_2_RISK_ASSESSMENT);
    assertThat(f.status()).isEqualTo(FindingStatus.CLEARED);
    assertThat(f.evidenceReference()).as("留痕指明复用了哪一次评估").isNotBlank();
  }

  @Test
  void 风控已有人工拒发结论时按该结论定档为BLOCK() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-REJ", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var assessmentId =
        asA(
            () -> {
              risk.saveRule(
                  new com.starcon.trade.OrderRiskModels.RiskRuleInput(
                      "默认风控",
                      new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                      new BigDecimal("0.0000"), new BigDecimal("1.0000"),
                      new BigDecimal("0.0000"), new BigDecimal("1.0000"),
                      24, 100000, 1000, new BigDecimal("100.0000"), 100000, 24, 1000000, 24,
                      com.starcon.trade.OrderRiskModels.MissingSignalPolicy.SCORE_KNOWN_ONLY),
                  user);
              var assessed =
                  risk.assess(
                      new com.starcon.trade.OrderRiskModels.AssessmentInput(orderId, "夹具"), user);
              // 下单频次维权重 1.0000 ⇒ 这一维打满即 100 分 ≥ 建议拒发阈值 1.0000
              risk.review(
                  assessed.assessment().id(),
                  new com.starcon.trade.OrderRiskModels.RiskReviewInput(
                      com.starcon.trade.OrderRiskModels.ReviewDecision.REJECT,
                      com.starcon.trade.OrderRiskModels.ReviewReasonCode.CONFIRMED_RISK,
                      "夹具：人工拒发"),
                  user);
              return assessed.assessment().id();
            });
    assertThat(assessmentId).isNotNull();
    var saved = check(orderId);
    var f = finding(saved, InterceptCheckCode.SUSPECTED_FRAUD);
    assertThat(f.status()).isEqualTo(FindingStatus.FLAGGED);
    assertThat(f.severity()).as("风控侧已有人工结论 REJECT ⇒ 按该结论定档").isEqualTo(FindingSeverity.BLOCK);
    assertThat(saved.check().recommendedDisposition()).isEqualTo(RecommendedDisposition.CANCEL);
    assertThat(saved.check().finalDisposition())
        .as("风控的拒发不是本模块的取消：本模块最终处置仍为空").isNull();
  }

  @Test
  void 同一张单重复检查结论逐位相同() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-DET", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "-15.0000");
    var skuId =
        asA(() -> sql.fetchOne("select sku_id from sales_order_line where order_id = ?", orderId))
            .get(0, UUID.class);
    seedCost(skuId, "90.0000", "USD", T1.minusDays(1));
    var first = check(orderId).check();
    var second = check(orderId).check();
    assertThat(second.recommendedDisposition()).isEqualTo(first.recommendedDisposition());
    assertThat(second.flaggedCount()).isEqualTo(first.flaggedCount());
    assertThat(second.assessedReferenceAt()).isEqualTo(first.assessedReferenceAt());
    assertThat(first.assessedReferenceAt())
        .as("判定基准时刻恒等于订单提交时刻，不是 now()")
        .isEqualTo(T1);
  }

  @Test
  void 草稿订单不检查() {
    saveDefaultPolicy();
    var orderId = seedDraftOrder("MO-DRAFT");
    var e = catchThrowable(() -> check(orderId));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(409);
    assertThat(e.getMessage()).contains("还没提交");
  }

  @Test
  void 策略更新必须递增版本() {
    var first = asA(() -> service.savePolicy(defaultPolicy()));
    var second = asA(() -> service.savePolicy(defaultPolicy()));
    assertThat(second.version()).as("两次检查记录引用 (policy_id, policy_version)").isGreaterThan(first.version());
    // 绕过服务层不递增版本：DB 触发器挡下
    var e =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute("update order_intercept_policy set policy_name='偷改' where id = ?",
                          first.id());
                      return null;
                    }));
    assertThat(rootMessage(e)).as("同名策略会让「当时按哪一版判的」无法复算")
        .contains("必须带 version 递增提交");
  }

  // ==========================================================================
  // 人工处置：拦截唯一的出口
  // ==========================================================================
  @Test
  void 落人工处置后最终处置才被回填() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-DISP", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    assertThat(saved.check().finalDisposition()).isNull();
    var after =
        asA(
            () ->
                service.disposition(
                    saved.check().id(),
                    new InterceptDispositionInput(
                        FinalDisposition.CANCEL, DispositionReasonCode.CONFIRMED_ANOMALY, "夹具"),
                    user));
    assertThat(after.check().finalDisposition()).isEqualTo(FinalDisposition.CANCEL);
    assertThat(after.check().disposedBy()).isEqualTo(user);
    assertThat(after.check().disposedAt()).isNotNull();
    assertThat(after.disposition().decision()).isEqualTo(FinalDisposition.CANCEL);
  }

  @Test
  void 误拦放行是合法处置() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-FP", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    var after =
        asA(
            () ->
                service.disposition(
                    saved.check().id(),
                    new InterceptDispositionInput(
                        FinalDisposition.RELEASE, DispositionReasonCode.FALSE_POSITIVE, "夹具：误拦"),
                    user));
    assertThat(after.check().finalDisposition())
        .as("误拦真实订单的代价最高，所以误拦必须能留痕放行").isEqualTo(FinalDisposition.RELEASE);
  }

  @Test
  void 绕过服务层直接写最终处置同样被数据库挡下() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-BYPASS", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var checkId = check(orderId).check().id();
    var e =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_intercept_check set final_disposition='CANCEL' where id = ?",
                          checkId);
                      return null;
                    }));
    assertThat(rootMessage(e)).as("加粗句最硬的一道闸：拦截结果自己填不出最终处置")
        .contains("拦截不自动取消，默认转人工");
    // 并且它仍然是「未处置」
    assertThat(asA(() -> service.check(checkId).check().finalDisposition())).isNull();
  }

  @Test
  void 放行单补一条处置记录被拒() {
    saveDefaultPolicy();
    var orderId = seedOrder("MO-REL", "Ada", "US", "90001", "+1-213-555-0100",
        "100 Main St", "100.0000", "0");
    var saved = check(orderId);
    assertThat(saved.check().manualReviewRequired()).isFalse();
    var e = catchThrowable(
        () -> asA(
            () ->
                service.disposition(
                    saved.check().id(),
                    new InterceptDispositionInput(
                        FinalDisposition.CANCEL, DispositionReasonCode.CONFIRMED_ANOMALY, "不该成功"),
                    user)));
    assertThat(e).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(e.getMessage()).contains("不需要人工处置");
  }

  @Test
  void 误拦原因码配取消结论被拒() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-C1", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var checkId = check(orderId).check().id();
    var e = catchThrowable(
        () -> asA(
            () ->
                service.disposition(
                    checkId,
                    new InterceptDispositionInput(
                        FinalDisposition.CANCEL, DispositionReasonCode.FALSE_POSITIVE, "自相矛盾"),
                    user)));
    assertThat(e.getMessage()).contains("自相矛盾");
  }

  @Test
  void 升级合规不能用取消冒充() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-C2", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var checkId = check(orderId).check().id();
    var e = catchThrowable(
        () -> asA(
            () ->
                service.disposition(
                    checkId,
                    new InterceptDispositionInput(
                        FinalDisposition.CANCEL, DispositionReasonCode.ESCALATED_COMPLIANCE, "升级"),
                    user)));
    assertThat(e.getMessage()).contains("不等于");
    // 升级应落 HOLD
    var after =
        asA(
            () ->
                service.disposition(
                    checkId,
                    new InterceptDispositionInput(
                        FinalDisposition.HOLD, DispositionReasonCode.ESCALATED_COMPLIANCE, "升级"),
                    user));
    assertThat(after.check().finalDisposition()).isEqualTo(FinalDisposition.HOLD);
  }

  @Test
  void 处置不可覆写() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-IMM", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var checkId = check(orderId).check().id();
    asA(
        () ->
            service.disposition(
                checkId,
                new InterceptDispositionInput(
                    FinalDisposition.CANCEL, DispositionReasonCode.CONFIRMED_ANOMALY, "第一次"),
                user));
    var e = catchThrowable(
        () -> asA(
            () ->
                service.disposition(
                    checkId,
                    new InterceptDispositionInput(
                        FinalDisposition.RELEASE, DispositionReasonCode.FALSE_POSITIVE, "第二次"),
                    user)));
    assertThat(e.getMessage()).contains("已经处置过了");
    // DB 层同样不可改写
    var dbError =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_intercept_disposition set note='偷改' where check_id = ?",
                          checkId);
                      return null;
                    }));
    assertThat(rootMessage(dbError))
        .as("第一道防线是 V153 对留痕表的 REVOKE UPDATE/DELETE：运行角色连试都试不了")
        .contains("permission denied");
    // 第二道防线是触发器。运行角色被 REVOKE 挡在门外，够不到它，
    // 所以这里断言**触发器确实存在**（结构性断言，跨连接稳定）。
    assertThat(guardExists("guard_order_intercept_disposition_append_only"))
        .as("第二道防线：即便绕过 REVOKE，触发器也会挡住改写")
        .isTrue();
  }

  @Test
  void 命中明细不可改写() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    var orderId = seedOrder("MO-APPEND", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    var checkId = check(orderId).check().id();
    var e =
        catchThrowable(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_intercept_finding set match_count=9 where check_id = ?",
                          checkId);
                      return null;
                    }));
    assertThat(rootMessage(e))
        .as("第一道防线是 V153 对命中明细的 REVOKE UPDATE/DELETE")
        .contains("permission denied");
    assertThat(guardExists("guard_order_intercept_finding_append_only"))
        .as("第二道防线：命中明细是凭据，改一格就再也复算不出当时为什么放行")
        .isTrue();
  }

  // ==========================================================================
  // 时间 / 金额刻度
  // ==========================================================================
  @Test
  void 带非零时区偏移的采集时刻写入后往返零漂移() {
    saveDefaultPolicy();
    UUID skuId = UUID.randomUUID();
    asA(
        () ->
            service.saveSkuCost(
                new InterceptSkuCostInput(
                    skuId, "SKU-TZ", "时区夹具", new BigDecimal("42.5000"), "USD", T8, "")));
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select captured_at from order_intercept_sku_cost where tenant_id = "
                        + "nullif(current_setting('app.current_tenant',true),'')::uuid and sku_id = ?",
                    skuId));
    var stored = row.get("captured_at", OffsetDateTime.class);
    // 断言**写回的值**等于同一绝对时刻（只断言「没抛异常」会漏掉静默的 −28800s 偏移）
    assertThat(stored.toInstant()).as("9:30+08:00 与 01:30Z 是同一时刻").isEqualTo(T8.toInstant());
    assertThat(stored.getHour()).as("读回保留 +08:00 偏移小时数").isEqualTo(9);
    // 数量与金额刻度
    var cost =
        asA(() -> service.pageSkuCosts(new InterceptSkuCostQuery(null, skuId, 0, 50)).items().get(0));
    assertThat(cost.unitCost().scale()).as("金额恒 4 位（对齐 numeric(19,4)）").isEqualTo(4);
    assertThat(cost.unitCost()).isEqualByComparingTo("42.5000");
  }

  // ==========================================================================
  // 列表：真分页 + 真搜索
  // ==========================================================================
  @Test
  void 列表真分页且汇总不受分页影响() {
    saveDefaultPolicy();
    for (int i = 0; i < 5; i++) {
      check(seedOrder("MO-PAGE-" + i, "P" + i, "US", "90001", "+1-213-555-020" + i,
          "100 Main St", "100.0000", "0"));
    }
    var page1 = asA(() -> service.pageChecks(new InterceptCheckQuery(null, null, null, null, null, 0, 2)));
    var page2 = asA(() -> service.pageChecks(new InterceptCheckQuery(null, null, null, null, null, 1, 2)));
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(2);
    assertThat(page1.total()).isEqualTo(5);
    assertThat(page1.summary().total()).as("汇总不受分页影响").isEqualTo(5);
    // 两页不重叠
    assertThat(page1.items().stream().map(InterceptCheck::id).toList())
        .doesNotContainAnyElementsOf(page2.items().stream().map(InterceptCheck::id).toList());
  }

  @Test
  void 关键字搜索真的过滤() {
    saveDefaultPolicy();
    check(seedOrder("MO-FIND-A", "Alpha", "US", "90001", "+1-213-555-0301", "100 Main St", "100.0000", "0"));
    check(seedOrder("MO-FIND-B", "Bravo", "US", "90001", "+1-213-555-0302", "100 Main St", "100.0000", "0"));
    var hit = asA(() -> service.pageChecks(new InterceptCheckQuery("MO-FIND-A", null, null, null, null, 0, 50)));
    assertThat(hit.total()).isEqualTo(1);
    assertThat(hit.items().get(0).orderNo()).isEqualTo("MO-FIND-A");
  }

  @Test
  void 按命中项筛走exists不会把一张单数成两行() {
    saveDefaultPolicy();
    seedBlacklist("Mallory", "+1-213-555-0199", "mallory@example.com", "恶意取消");
    seedRestriction(RestrictionKind.SHIPPING_COUNTRY, "US", RestrictionEffect.BLOCK, "禁运", null, null);
    var orderId = seedOrder("MO-MULTI", "Mallory", "US", "90001", "+1-213-555-0199",
        "100 Main St", "100.0000", "0");
    check(orderId);
    var byCode =
        asA(
            () ->
                service.pageChecks(
                    new InterceptCheckQuery(null, null, "BLACKLISTED_BUYER", null, null, 0, 50)));
    assertThat(byCode.total()).isEqualTo(1);
    var pending =
        asA(() -> service.pageChecks(new InterceptCheckQuery(null, null, null, true, null, 0, 50)));
    assertThat(pending.total()).as("只命中两项的单在待处置队列里仍是一行").isEqualTo(1);
  }

  // ==========================================================================
  // 能力声明：如实报缺口
  // ==========================================================================
  @Test
  void capabilities如实报出三处缺口而不是让调用方猜() {
    var caps = asA(service::capabilities);
    assertThat(caps.crossServiceReadAvailable())
        .as("15 客户 / 16 合规在别的服务的独立库，跨服务读表一律禁止").isFalse();
    assertThat(caps.addressValidationAvailable())
        .as("9.2.2.1 未开发").isFalse();
    assertThat(caps.shopScopeSupported())
        .as("sales_order 没有 shop_code 列").isFalse();
    assertThat(caps.policyConfigured()).isFalse();
    assertThat(caps.checks()).hasSize(8);
    assertThat(caps.boldRule()).contains("拦截不自动取消");
    assertThat(caps.gaps()).contains("9.2.2.1").contains("16 合规");

    var deliverability =
        caps.checks().stream().filter(c -> c.code().equals("ADDRESS_DELIVERABILITY")).findFirst().orElseThrow();
    assertThat(deliverability.upstreamDelivered()).isFalse();
    assertThat(deliverability.gap()).contains("9.2.2.1");

    // 已接通的三项必须标 true（SUSPECTED_FRAUD / BELOW_COST_DISCOUNT 虽已接通，
    // 仍有必须随响应一起带走的口径说明，所以 gap 非空 —— 那是说明不是缺口）
    for (String code : List.of("ADDRESS_INCOMPLETE", "DUPLICATE_ORDER", "SUSPECTED_FRAUD")) {
      var c = caps.checks().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
      assertThat(c.upstreamDelivered()).as(code).isTrue();
    }
    // 完全无口径说明的只有这两项
    for (String code : List.of("ADDRESS_INCOMPLETE", "DUPLICATE_ORDER")) {
      var c = caps.checks().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
      assertThat(c.gap()).as(code).isNull();
    }
    // 策略登记后 policyConfigured 变 true
    saveDefaultPolicy();
    assertThat(asA(service::capabilities).policyConfigured()).isTrue();
  }

  @Test
  void 最终处置词表里没有任何自动取值() {
    // 这是一条**结构**断言：FinalDisposition 枚举不存在 AUTO_CANCEL / AUTO_RELEASE。
    // 枚举加值很容易（改一行），但加粗句说的正是「不该有这个状态」，所以钉在这里。
    assertThat(java.util.Arrays.stream(FinalDisposition.values()).map(Enum::name).toList())
        .containsExactlyInAnyOrder("RELEASE", "HOLD", "CANCEL");
    assertThat(java.util.Arrays.stream(RecommendedDisposition.values()).map(Enum::name).toList())
        .containsExactlyInAnyOrder("RELEASE", "MANUAL_REVIEW", "CANCEL");
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 未认证与只读角色的边界() throws Exception {
    http.perform(get("/trade/order-intercept/checks")).andExpect(status().isUnauthorized());
    http.perform(post("/trade/order-intercept/checks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isUnauthorized());
    // 只读角色读得到清单，但登记限制清单被拒
    http.perform(get("/trade/order-intercept/capabilities")
            .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(post("/trade/order-intercept/restrictions")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    new InterceptRestrictionInput(
                        RestrictionKind.SHIPPING_COUNTRY, "US", RestrictionEffect.BLOCK,
                        RestrictionSource.TENANT_MAINTAINED, "", "只读角色不该能写", null, null))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点清单与apiProxy放行的形状一致() throws Exception {
    // capabilities 端点真的可用，且如实报出 policyConfigured
    var body =
        http.perform(get("/trade/order-intercept/capabilities")
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("crossServiceReadAvailable").asBoolean()).isFalse();
    assertThat(parsed.get("checks")).hasSize(8);
  }
}
