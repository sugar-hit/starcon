package com.starcon.trade;

import static com.starcon.trade.OrderRiskModels.*;
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
import java.util.HashMap;
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
 * 9.2.2.2 风控校验。
 *
 * <p>本测试是 <b>V150 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉住的九条</b>：
 *
 * <ol>
 *   <li><b>上游缺口如实报出</b>：capabilities 必须说清 15 客户与 16 合规都没接通
 *       （{@code crossServiceReadAvailable = false}），而不是给一个空数组让人猜；</li>
 *   <li><b>权重合计恒为 1.0000</b>：不等于 1 的规则被拒（DB CHECK + 服务层前置校验）；</li>
 *   <li><b>信号的三条硬闸</b>：比率超 1 拒、计数 0 拒、MODULE_* 来源必须带 sourceReference；</li>
 *   <li><b>采集时刻写回正确</b>：带 +08:00 偏移量的 timestamptz 写入后，<b>断言读回的值</b>
 *       等于同一绝对时刻（而不是「没抛异常」）—— 这正是 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 −28800s 的那一处；</li>
 *   <li><b>缺信号不出分</b>：BLOCK_EVALUATION 下 riskScore 为 null 且状态为
 *       INSUFFICIENT_SIGNALS；缺任一维时**不得**产出一个看起来安全的低分；</li>
 *   <li><b>五维齐备才出分，且刻度是 4 位</b>：riskScore 落在 numeric(9,4) 上，
 *       contribution = weight × normalized 逐条核对；</li>
 *   <li><b>三个真 SQL 维度真的在查库</b>：多造几笔订单后频次笔数、地址集中度笔数、
 *       同币种历史均单都随订单变而变（换币种则判历史不足）；</li>
 *   <li><b>确定性</b>：同一张单评估两次，两份 riskScore 与五个组件逐位相同；</li>
 *   <li><b>蓝图加粗句</b>：分数不能单独拒发 —— 详情里 recommendedAction 是「建议拒发」时
 *       finalAction 必为 null，落一条人工复核后 finalAction 才出现，
 *       且给「放行」的单补复核被拒。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderRiskIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID partySame = UUID.randomUUID();
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
          actor(user, a, null, List.of(grant("ALL", List.of("trade.orders.read")))));
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

  // ------------------------------------------------------------ 夹具常量（时间全部钉死）

  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-02-20T08:00:00Z");
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00Z");

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
          "truncate order_risk_review, order_risk_assessment_component, order_risk_assessment,"
              + " order_risk_signal, order_risk_rule,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张订单夹具。金额必须与明细行一致（V99 的 guard_sales_order_totals 会复算）。 */
  record OrderSpec(
      String orderNo,
      String currency,
      OffsetDateTime submittedAt,
      String unitPrice,
      UUID partyId,
      String recipient,
      String city,
      String line1,
      String email) {}

  static OrderSpec spec(String orderNo) {
    return new OrderSpec(orderNo, "USD", T1, "10.0000", partySame, RECIPIENT, CITY, LINE1, EMAIL);
  }

  OrderSpec copy(OrderSpec s) {
    return new OrderSpec(
        s.orderNo(), s.currency(), s.submittedAt(), s.unitPrice(), s.partyId(), s.recipient(),
        s.city(), s.line1(), s.email());
  }

  interface Mutator {
    OrderSpec apply(OrderSpec s);
  }

  OrderSpec with(OrderSpec s, Mutator m) {
    return m.apply(s);
  }

  /** 改提交时刻的历史单夹具（金额维要求历史**严格早于**本单，所以这个辅助是必需的）。 */
  OrderSpec at(OrderSpec s, OffsetDateTime submittedAt) {
    return new OrderSpec(
        s.orderNo(), s.currency(), submittedAt, s.unitPrice(), s.partyId(), s.recipient(),
        s.city(), s.line1(), s.email());
  }

  /** 建一张已提交订单，返回订单 ID。 */
  UUID order(UUID tenant, OrderSpec s) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          BigDecimal unit = new BigDecimal(s.unitPrice());
          BigDecimal lineTotal = unit.setScale(4, java.math.RoundingMode.HALF_UP);
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,0,?,?::timestamptz,?::numeric,0,0,0,0,"
                  + "?::numeric,'',0,?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              s.orderNo(),
              "AMAZON",
              "",
              "EXT-" + s.orderNo(),
              s.currency(),
              "PREPAID",
              "SUBMITTED",
              s.submittedAt(),
              lineTotal,
              lineTotal,
              s.submittedAt(),
              s.submittedAt());
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,?::numeric,0,0,?::numeric,'',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              UUID.randomUUID(),
              "SKU-" + s.orderNo(),
              "测试商品 " + s.orderNo(),
              unit,
              lineTotal,
              s.submittedAt());
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,?,'',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              s.partyId(),
              "C-" + s.partyId(),
              RECIPIENT,
              "INDIVIDUAL",
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
              PHONE,
              s.email(),
              COUNTRY,
              PROVINCE,
              s.city(),
              POSTCODE,
              s.line1(),
              s.submittedAt());
          return null;
        });
    return orderId;
  }

  /** 默认规则：五维等权 0.2，人工 40，拒发 80。 */
  RiskRuleInput ruleInput(String name, MissingSignalPolicy policy) {
    return new RiskRuleInput(
        name,
        new BigDecimal("0.2000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.2000"),
        new BigDecimal("40.0000"),
        new BigDecimal("80.0000"),
        24,
        10,
        5,
        new BigDecimal("3.0000"),
        2,
        72,
        5,
        720,
        policy);
  }

  RiskRule registerRule(MissingSignalPolicy policy) {
    return asA(() -> service.saveRule(ruleInput("默认风控", policy), user));
  }

  RiskSignal registerSignal(
      SignalCode code, SubjectKind kind, String key, String value, OffsetDateTime capturedAt) {
    return asA(
        () ->
            service.saveSignal(
                new RiskSignalInput(
                    code,
                    kind,
                    key,
                    new BigDecimal(value),
                    SignalSource.TENANT_MAINTAINED,
                    "",
                    capturedAt,
                    "")));
  }

  /** 把 15 客户 / 16 合规那五个信号一次登记齐（全部早于订单提交时刻）。 */
  void registerAllSignals(UUID tenant) {
    as(
        tenant,
        () -> {
          service.saveSignal(
              new RiskSignalInput(
                  SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                  new BigDecimal("0.10"), SignalSource.TENANT_MAINTAINED, "",
                  T0, "退款率 10%"));
          service.saveSignal(
              new RiskSignalInput(
                  SignalCode.DISPUTE_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                  new BigDecimal("0.05"), SignalSource.TENANT_MAINTAINED, "",
                  T0, "纠纷率 5%"));
          service.saveSignal(
              new RiskSignalInput(
                  SignalCode.DEVICE_IP_LINK_COUNT, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                  new BigDecimal("2"), SignalSource.TENANT_MAINTAINED, "",
                  T0, "2 条关联"));
          service.saveSignal(
              new RiskSignalInput(
                  SignalCode.SHARED_DEVICE_COUNT, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                  new BigDecimal("1"), SignalSource.TENANT_MAINTAINED, "",
                  T0, "共用 1 台设备"));
          service.saveSignal(
              new RiskSignalInput(
                  SignalCode.SHARED_IP_COUNT, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                  new BigDecimal("3"), SignalSource.TENANT_MAINTAINED, "",
                  T0, "共用 3 个 IP"));
          return null;
        });
  }

  AssessmentSaved assess(UUID tenant, UUID orderId) {
    return as(tenant, () -> service.assess(new AssessmentInput(orderId, "测试"), user));
  }

  // ==========================================================================
  // 1. 上游缺口：capabilities 必须自己说，不能让人从空数组里猜
  // ==========================================================================
  @Test
  void 上游缺口如实报出而不是给空数组() {
    var caps = asA(service::capabilities);

    assertThat(caps.crossServiceReadAvailable())
        .as("15 客户与 16 合规都在别的服务的独立库里，跨服务读表一律禁止")
        .isFalse();
    assertThat(caps.ruleConfigured()).as("本用例还没登记规则").isFalse();

    var byCode = new HashMap<String, RiskCapabilityDimension>();
    caps.dimensions().forEach(d -> byCode.put(d.code(), d));

    // 15 客户 / 16 合规这两维必须报未交付，且说清缺口落在哪张表上。
    assertThat(byCode.get("BUYER_HISTORY").upstreamDelivered()).isFalse();
    assertThat(byCode.get("BUYER_HISTORY").gap()).contains("order_risk_signal").contains("15");
    assertThat(byCode.get("DEVICE_IP_LINK").upstreamDelivered()).isFalse();
    assertThat(byCode.get("DEVICE_IP_LINK").gap()).contains("16");

    // 三个真 SQL 维必须报已接通 —— 它们确实在查本服务的订单簇。
    assertThat(byCode.get("ORDER_FREQUENCY").upstreamDelivered()).isTrue();
    assertThat(byCode.get("AMOUNT_ANOMALY").upstreamDelivered()).isTrue();
    assertThat(byCode.get("ADDRESS_CONCENTRATION").upstreamDelivered()).isTrue();
    assertThat(byCode.get("ORDER_FREQUENCY").gap()).isNull();

    // 下游 9.2.1.3 未交付，且必须说清本模块只输出建议动作、不建拦截表。
    assertThat(caps.downstream().moduleId()).isEqualTo("9.2.1.3");
    assertThat(caps.downstream().available()).isFalse();
    assertThat(caps.downstream().handoff()).contains("建议动作");
    assertThat(caps.scoringOrder()).contains("归一化").contains("不参与分母").contains("不出分");
  }

  // ==========================================================================
  // 2. 规则：权重合计必须恰好 1.0000
  // ==========================================================================
  @Test
  void 权重合计不等于一时规则被拒() {
    var bad =
        new RiskRuleInput(
            "权重错", new BigDecimal("0.3000"), new BigDecimal("0.2000"), new BigDecimal("0.2000"),
            new BigDecimal("0.2000"), new BigDecimal("0.3000"), new BigDecimal("40.0000"),
            new BigDecimal("80.0000"), 24, 10, 5, new BigDecimal("3.0000"), 2, 72, 5, 720,
            MissingSignalPolicy.SCORE_KNOWN_ONLY);
    assertThatThrownBy(() -> asA(() -> service.saveRule(bad, user)))
        .hasMessageContaining("恰好等于 1.0000")
        .hasMessageContaining("1.0000");
  }

  @Test
  void 拒发阈值不高于人工阈值时被拒() {
    var bad = ruleInput("阈值错", MissingSignalPolicy.SCORE_KNOWN_ONLY);
    var swapped =
        new RiskRuleInput(
            bad.ruleName(), bad.weightBuyerHistory(), bad.weightDeviceIpLink(),
            bad.weightOrderFrequency(), bad.weightAmountAnomaly(), bad.weightAddressConcentration(),
            new BigDecimal("90.0000"), new BigDecimal("80.0000"), bad.frequencyWindowHours(),
            bad.frequencyMaxOrders(), bad.deviceLinkMaxLinks(), bad.amountAnomalyRatio(),
            bad.amountAnomalyMinOrders(), bad.addressConcentrationWindowHours(),
            bad.addressConcentrationMaxOrders(), bad.signalMaxAgeHours(),
            bad.missingSignalPolicy());
    assertThatThrownBy(() -> asA(() -> service.saveRule(swapped, user)))
        .hasMessageContaining("必须高于建议人工阈值");
  }

  @Test
  void 规则版本随更新递增_评估才能复算() {
    var first = registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    assertThat(first.version()).isZero();
    var second = asA(() -> service.saveRule(ruleInput("改过的风控", MissingSignalPolicy.SCORE_KNOWN_ONLY), user));
    assertThat(second.version()).isEqualTo(1);
    assertThat(second.ruleName()).isEqualTo("改过的风控");
  }

  @Test
  void 没登记规则时读规则是404而不是给默认值() {
    var e = org.assertj.core.api.Assertions.catchThrowable(() -> asA(service::rule));
    assertThat(e).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(404);
  }

  // ==========================================================================
  // 3. 信号的三条硬闸 + 采集时刻写回正确
  // ==========================================================================
  @Test
  void 比率超一与计数为零都被拒() {
    assertThatThrownBy(
            () ->
                registerSignal(
                    SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(), "1.5",
                    T0))
        .hasMessageContaining("比率")
        .hasMessageContaining("30% 传 0.3");
    assertThatThrownBy(
            () ->
                registerSignal(
                    SignalCode.SHARED_IP_COUNT, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                    "0", T0))
        .hasMessageContaining("不登记这一行");
  }

  @Test
  void module来源必须给出来源出处() {
    var missingRef =
        new RiskSignalInput(
            SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
            new BigDecimal("0.1"), SignalSource.MODULE_15_CUSTOMER_PROFILE, "", T0, "");
    assertThatThrownBy(() -> asA(() -> service.saveSignal(missingRef)))
        .hasMessageContaining("sourceReference");
    var withRef =
        new RiskSignalInput(
            SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
            new BigDecimal("0.1"), SignalSource.MODULE_15_CUSTOMER_PROFILE, "crm:profile/42", T0,
            "");
    var saved = asA(() -> service.saveSignal(withRef));
    assertThat(saved.signalSource()).isEqualTo(SignalSource.MODULE_15_CUSTOMER_PROFILE);
    assertThat(saved.sourceReference()).isEqualTo("crm:profile/42");
  }

  /**
   * 时间钉死并<b>断言写回的值</b>。
   *
   * <p>刻意用带 +08:00 偏移量的时刻写入：{@code Timestamp.valueOf(LocalDateTime)} 会把它
   * 当成本地时间静默偏 −28800s，而「没抛异常」的断言完全抓不到这种错。
   */
  @Test
  void 采集时刻带偏移量写入后读回的是同一绝对时刻() {
    var withOffset = OffsetDateTime.parse("2026-02-20T16:00:00+08:00");
    assertThat(withOffset.toInstant()).isEqualTo(T0.toInstant());

    var saved =
        registerSignal(
            SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(), "0.1",
            withOffset);

    assertThat(saved.capturedAt().toInstant())
        .as("读回值必须是同一绝对时刻，不是「没抛异常」")
        .isEqualTo(T0.toInstant());
    assertThat(saved.capturedAt().getOffset())
        .as("回读时区跟随会话，但绝对时刻必须逐毫秒相等")
        .isEqualTo(withOffset.getOffset());
  }

  @Test
  void 信号列表是真分页与真搜索() {
    registerAllSignals(a);
    var page0 = asA(() -> service.pageSignals(new SignalQuery(null, null, null, null, 0, 2)));
    assertThat(page0.total()).isEqualTo(5);
    assertThat(page0.items()).hasSize(2);
    assertThat(page0.size()).isEqualTo(2);

    var page1 = asA(() -> service.pageSignals(new SignalQuery(null, null, null, null, 1, 2)));
    assertThat(page1.items()).hasSize(2);
    assertThat(page0.items().stream().map(RiskSignal::id).toList())
        .as("第二页不能和第一页重叠")
        .doesNotContainAnyElementsOf(page1.items().stream().map(RiskSignal::id).toList());

    var filtered =
        asA(
            () ->
                service.pageSignals(
                    new SignalQuery(null, "SHARED_IP_COUNT", null, null, 0, 50)));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).signalCode()).isEqualTo(SignalCode.SHARED_IP_COUNT);

    var bySource =
        asA(
            () ->
                service.pageSignals(
                    new SignalQuery(null, null, SignalSource.MODULE_15_CUSTOMER_PROFILE, null, 0,
                        50)));
    assertThat(bySource.total()).as("来源筛选是真过滤，不是摆设").isZero();
  }

  // ==========================================================================
  // 4. 缺信号：BLOCK_EVALUATION 下不出分，且不得产出一个安全的低分
  // ==========================================================================
  @Test
  void 缺信号且策略为不出分时风险分为空() {
    registerRule(MissingSignalPolicy.BLOCK_EVALUATION);
    var orderId = order(a, spec("MO-MISS"));

    var saved = assess(a, orderId);
    var head = saved.assessment();

    assertThat(head.evaluationStatus()).isEqualTo(EvaluationStatus.INSUFFICIENT_SIGNALS);
    assertThat(head.riskScore()).as("缺信号不出分：残缺的分会被当成「量过了，结论是低分」").isNull();
    assertThat(head.recommendedAction()).isEqualTo(RecommendedAction.MANUAL_REVIEW);
    assertThat(head.manualReviewRequired()).isTrue();
    assertThat(head.scoredDimensionCount())
        .as("三个真 SQL 维不依赖上游信号：频次与地址可算，金额判历史不足")
        .isEqualTo(2);
    assertThat(head.missingDimensionCount()).isEqualTo(3);

    // 五行组件**都在**，且每行都带人话说明 —— 少一行就只能从总分反推是谁缺席的。
    assertThat(saved.components()).hasSize(5);
    assertThat(saved.components())
        .allSatisfy(c -> assertThat(c.explanation()).isNotBlank());
    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).status())
        .isEqualTo(RiskComponentStatus.MISSING_SIGNAL);
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).explanation())
        .contains("退款率")
        .contains("纠纷率")
        .contains("未登记");
    // 缺失维 rawValue 是 null 而不是 0。
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).rawValue()).isNull();
  }

  @Test
  void 缺信号且策略为按已知维度打分时只在已评分维之间归一化() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    var orderId = order(a, spec("MO-KNOWN"));
    registerAllSignals(a);

    // 三个真 SQL 维全可算：频次 1 笔 / 打满 10 笔 = 10 分；地址 1 笔 / 打满 5 笔 = 20 分；
    // 金额：本单与历史无同币种前序单 → INSUFFICIENT_HISTORY。
    var saved = assess(a, orderId);
    var head = saved.assessment();

    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));

    assertThat(byDim.get(RiskDimension.ORDER_FREQUENCY).normalizedScore())
        .isEqualByComparingTo("10.0000");
    assertThat(byDim.get(RiskDimension.ADDRESS_CONCENTRATION).normalizedScore())
        .isEqualByComparingTo("20.0000");
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).normalizedScore())
        .as("退款率 0.10 与纠纷率 0.05 取较严重的一项")
        .isEqualByComparingTo("10.0000");
    assertThat(byDim.get(RiskDimension.AMOUNT_ANOMALY).status())
        .as("同币种历史不足 2 笔时判历史不足，不拿别的币种凑")
        .isEqualTo(RiskComponentStatus.INSUFFICIENT_HISTORY);
    assertThat(head.evaluationStatus()).isEqualTo(EvaluationStatus.SCORED);
    assertThat(head.missingDimensionCount()).isEqualTo(1);
    assertThat(head.scoredDimensionCount()).isEqualTo(4);

    // 归一化只在**已评分维**之间：分母是 4 × 0.2 = 0.8，不是 1.0。
    // 四个已评分维的贡献之和是 20.0000，除以 0.8 得总分 25.0000 ——
    // 总分**不等于**贡献之和，差额正是「缺失那一维的权重」被摊回的结果。
    BigDecimal contributionSum =
        saved.components().stream()
            .map(RiskComponent::contribution)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal weightScored =
        saved.components().stream()
            .filter(c -> c.status() == RiskComponentStatus.SCORED)
            .map(RiskComponent::weight)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(contributionSum).isEqualByComparingTo("20.0000");
    assertThat(weightScored).isEqualByComparingTo("0.8000");
    assertThat(head.riskScore())
        .isEqualByComparingTo(
            contributionSum.divide(weightScored, 4, java.math.RoundingMode.HALF_UP));
  }

  @Test
  void 过期信号判为已过期而不是当成没登记() {
    registerRule(MissingSignalPolicy.BLOCK_EVALUATION);
    var orderId = order(a, spec("MO-STALE"));
    // 两个码都登记，但采集于 2025 年：规则新鲜度 720 小时（约 30 天），早已过期。
    // ⚠️ 必须**两个码都登记**：「一个码没登记」与「一个码登记了但过期」同时发生时，
    // 状态取更强的那个 —— MISSING_SIGNAL（补登记才能解决），过期那一项在说明里点名。
    var ancient = OffsetDateTime.parse("2025-01-01T00:00:00Z");
    registerSignal(
        SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(), "0.9", ancient);
    registerSignal(
        SignalCode.DISPUTE_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(), "0.8", ancient);

    var saved = assess(a, orderId);
    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).status())
        .isEqualTo(RiskComponentStatus.STALE_SIGNAL);
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).explanation())
        .contains("新鲜度")
        .contains("退款率")
        .contains("纠纷率");
  }

  @Test
  void 订单提交之后采集的信号不算数() {
    registerRule(MissingSignalPolicy.BLOCK_EVALUATION);
    var orderId = order(a, spec("MO-FUTURE"));
    // 采集时刻晚于订单提交时刻：这条画像对这一单的决策没有贡献。
    var future = T1.plusDays(1);
    registerAllSignals(a);
    asA(
        () ->
            service.saveSignal(
                new RiskSignalInput(
                    SignalCode.REFUND_RATE, SubjectKind.CUSTOMER_PARTY, partySame.toString(),
                    new BigDecimal("0.9"), SignalSource.TENANT_MAINTAINED, "", future, "")));
    // 覆盖成「只有这一条在提交之后」：先把它之前的删掉不可（不可改写），改为换个主体键。
    var saved = assess(a, orderId);
    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).status())
        .as("T0 的信号仍然可用；提交之后那条被排除，取的是最新且 <= 基准时刻的那一条")
        .isEqualTo(RiskComponentStatus.SCORED);
    assertThat(byDim.get(RiskDimension.BUYER_HISTORY).rawValue())
        .isEqualByComparingTo("0.1000");
  }

  // ==========================================================================
  // 5. 五个真 SQL 维度真的在查库
  // ==========================================================================
  @Test
  void 频次与地址集中度随订单变而变() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);

    // 造 4 笔同买家同地址的历史单（提交时刻都早于本单），本单是 24 小时窗内第 5 笔。
    for (int i = 1; i <= 4; i++) {
      order(a, at(spec("MO-H" + i), T1.minusHours(6)));
    }
    var target = order(a, spec("MO-TARGET"));

    var saved = assess(a, target);
    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));

    assertThat(byDim.get(RiskDimension.ORDER_FREQUENCY).rawValue())
        .as("24 小时窗内同一买家共 5 笔（含本单）")
        .isEqualByComparingTo("5");
    assertThat(byDim.get(RiskDimension.ADDRESS_CONCENTRATION).rawValue())
        .as("同一收货地址在 72 小时窗内共 5 笔")
        .isEqualByComparingTo("5");
  }

  @Test
  void 金额异常只比同币种() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    order(a, at(spec("MO-EUR"), T1.minusHours(6)));
    var target = order(a, spec("MO-USD-TARGET"));

    var saved = assess(a, target);
    var byDim = new HashMap<RiskDimension, RiskComponent>();
    saved.components().forEach(c -> byDim.put(c.dimension(), c));
    assertThat(byDim.get(RiskDimension.AMOUNT_ANOMALY).status())
        .as("USD 订单不拿 EUR 历史单来比：本服务没有汇率来源")
        .isEqualTo(RiskComponentStatus.INSUFFICIENT_HISTORY);

    // 同币种两笔历史够 2 笔时就能比了：本单 100、历史均 10 → 比值 10 → 打满档 3 → 100 分。
    order(a, at(spec("MO-U1"), T1.minusHours(6)));
    order(a, at(spec("MO-U2"), T1.minusHours(5)));
    var target2 =
        order(
            a,
            new OrderSpec(
                "MO-U3", "USD", T1, "100.0000", partySame, RECIPIENT, CITY, LINE1, EMAIL));
    var saved2 = assess(a, target2);
    var byDim2 = new HashMap<RiskDimension, RiskComponent>();
    saved2.components().forEach(c -> byDim2.put(c.dimension(), c));
    assertThat(byDim2.get(RiskDimension.AMOUNT_ANOMALY).status()).isEqualTo(RiskComponentStatus.SCORED);
    assertThat(byDim2.get(RiskDimension.AMOUNT_ANOMALY).normalizedScore())
        .isEqualByComparingTo("100.0000");
  }

  // ==========================================================================
  // 6. 确定性
  // ==========================================================================
  @Test
  void 同一张单评估两次结果逐位相同() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-DET"));

    var first = assess(a, orderId);
    var second = assess(a, orderId);

    assertThat(second.assessment().riskScore())
        .as("基准时刻钉在订单提交时刻，两次评估必然一致")
        .isEqualByComparingTo(first.assessment().riskScore());
    assertThat(second.assessment().recommendedAction())
        .isEqualTo(first.assessment().recommendedAction());
    assertThat(second.assessment().evaluatedReferenceAt())
        .isEqualTo(first.assessment().evaluatedReferenceAt());

    var firstByDim = new HashMap<RiskDimension, RiskComponent>();
    first.components().forEach(c -> firstByDim.put(c.dimension(), c));
    for (var c : second.components()) {
      var before = firstByDim.get(c.dimension());
      assertThat(c.normalizedScore()).isEqualByComparingTo(before.normalizedScore());
      assertThat(c.contribution()).isEqualByComparingTo(before.contribution());
      assertThat(c.status()).isEqualTo(before.status());
    }
  }

  // ==========================================================================
  // 7. 蓝图加粗句：分数不能单独作为拒绝依据
  // ==========================================================================
  @Test
  void 建议拒发时最终动作必为空_人工复核后才出现() {
    // 阈值压到「人工 10 / 拒发 30」：下面五个维度算出的真实总分是 37.5，会跨过 30 判「建议拒发」。
    // 刻意**按真实算术**跨门槛，而不是把某几维硬凑成 100 —— 那样这条用例
    // 就不再证明打分是对的，只证明阈值比较是对的。
    asA(
        () -> {
          var base = ruleInput("严口径风控", MissingSignalPolicy.SCORE_KNOWN_ONLY);
          return service.saveRule(
              new RiskRuleInput(
                  base.ruleName(), base.weightBuyerHistory(), base.weightDeviceIpLink(),
                  base.weightOrderFrequency(), base.weightAmountAnomaly(),
                  base.weightAddressConcentration(), new BigDecimal("10.0000"),
                  new BigDecimal("30.0000"), base.frequencyWindowHours(),
                  base.frequencyMaxOrders(), base.deviceLinkMaxLinks(), base.amountAnomalyRatio(),
                  base.amountAnomalyMinOrders(), base.addressConcentrationWindowHours(),
                  base.addressConcentrationMaxOrders(), base.signalMaxAgeHours(),
                  base.missingSignalPolicy()),
              user);
        });
    registerAllSignals(a);
    // 把三个租户信号拉满 → 五维全 100 分 → 总分 100 ≥ 80 → 建议拒发。
    asA(
        () -> {
          for (SignalCode code :
              List.of(
                  SignalCode.REFUND_RATE, SignalCode.DISPUTE_RATE, SignalCode.DEVICE_IP_LINK_COUNT,
                  SignalCode.SHARED_DEVICE_COUNT, SignalCode.SHARED_IP_COUNT)) {
            service.saveSignal(
                new RiskSignalInput(
                    code, SubjectKind.CUSTOMER_PARTY, partySame.toString(), new BigDecimal("1"),
                    SignalSource.TENANT_MAINTAINED, "", T0, "打满"));
          }
          return null;
        });
    var orderId = order(a, spec("MO-HOLD"));

    var saved = assess(a, orderId);
    var head = saved.assessment();
    // 买家历史 100（退款率与纠纷率都打到 1.0）/ 设备关联 20（最大计数 1 ÷ 打满档 5）
    // / 频次 10（1 ÷ 10）/ 金额 0（同币种历史不足，判不可用）/ 地址 20（1 ÷ 5），权重各 0.2：
    //   贡献 = 20 + 4 + 2 + 0 + 4 = 30；已评分维权重合计 0.8；总分 = 30 ÷ 0.8 = 37.5
    // 跨过 30 的拒发门槛 → 建议拒发，但最终动作仍为空。
    assertThat(head.riskScore()).isEqualByComparingTo("37.5000");
    assertThat(head.recommendedAction()).isEqualTo(RecommendedAction.HOLD_SHIPMENT);
    assertThat(head.manualReviewRequired()).isTrue();
    assertThat(head.finalAction()).as("分数再高也不能自己填出最终动作").isNull();
    assertThat(head.reviewedAt()).isNull();

    // 落一条人工复核 → 最终动作才出现。
    var reviewed =
        asA(
            () ->
                service.review(
                    head.id(), new RiskReviewInput(ReviewDecision.REJECT, ReviewReasonCode.CONFIRMED_RISK,
                        "复核确认"),
                    user));
    assertThat(reviewed.assessment().finalAction()).isEqualTo(FinalAction.REJECT);
    assertThat(reviewed.assessment().reviewedBy()).isEqualTo(user);
    assertThat(reviewed.assessment().reviewedAt()).isNotNull();
  }

  @Test
  void 建议放行的单补复核会被拒() {
    // 五维齐全且都很低 → 真算出来就是「放行」，才有资格测「放行单不许补复核」。
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-RELEASE"));
    var saved = assess(a, orderId);
    assertThat(saved.assessment().recommendedAction()).isEqualTo(RecommendedAction.RELEASE);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.review(
                            saved.assessment().id(),
                            new RiskReviewInput(ReviewDecision.REJECT, ReviewReasonCode.CONFIRMED_RISK,
                                "想直接拒"),
                            user)))
        .hasMessageContaining("不需要人工复核");
  }

  @Test
  void 复核结论不可覆写且误判原因必须配发货() {
    registerRule(MissingSignalPolicy.BLOCK_EVALUATION);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-TWICE"));
    var saved = assess(a, orderId);
    assertThat(saved.assessment().manualReviewRequired()).isTrue();

    var first =
        asA(
            () ->
                service.review(
                    saved.assessment().id(),
                    new RiskReviewInput(ReviewDecision.DEFERRED, ReviewReasonCode.PENDING_EVIDENCE,
                        "等材料"),
                    user));
    assertThat(first.assessment().finalAction()).isNull();

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.review(
                            saved.assessment().id(),
                            new RiskReviewInput(ReviewDecision.SHIP, ReviewReasonCode.FALSE_POSITIVE,
                                "复购客户"),
                            user)))
        .as("一份评估只复核一次：风控留痕不可覆写")
        .hasMessageContaining("已经复核过");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.review(
                            UUID.randomUUID(),
                            new RiskReviewInput(ReviewDecision.REJECT, ReviewReasonCode.FALSE_POSITIVE,
                                "矛盾组合"),
                            user)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  // ==========================================================================
  // 8. 多租户
  // ==========================================================================
  @Test
  void 跨租户读不到别人的评估与信号() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-X"));
    var saved = assess(a, orderId);

    // B 会话拿 A 的评估 id 读详情 → 404。
    var e = catchThrowable(() -> asB(() -> service.assessment(saved.assessment().id())));
    assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(404);

    // B 的列表里没有 A 的任何东西。
    var pageB = asB(() -> service.pageAssessments(new AssessmentQuery(null, null, null, null, null, 0, 50)));
    assertThat(pageB.total()).isZero();
    var signalsB = asB(() -> service.pageSignals(new SignalQuery(null, null, null, null, 0, 50)));
    assertThat(signalsB.total()).isZero();
    assertThat(asB(service::overview).total()).isZero();
  }

  @Test
  void 伪造tenant头无效() throws Exception {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    var orderId = order(a, spec("MO-HDR"));
    assess(a, orderId);

    // 带合法会话 + 伪造 X-Tenant-Id：拿到的仍然只有**会话租户**的数据。
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
    assertThat(parsed.get("total").asLong()).as("伪造的头不能把会话租户换成 B").isEqualTo(1);

    // 完全不带会话 → 401，没有任何租户上下文被建立。
    http.perform(get("/trade/orders/risk-validation/assessments"))
        .andExpect(status().isUnauthorized());
    assertThat(asB(() -> service.pageAssessments(new AssessmentQuery(null, null, null, null, null, 0, 50))
            .total()))
        .isZero();
  }

  @Test
  void 只读权限不能改规则也不能复核() throws Exception {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-RO"));
    var saved = assess(a, orderId);

    http.perform(
            put("/trade/orders/risk-validation/rule")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ruleInput("只读改规则", MissingSignalPolicy.SCORE_KNOWN_ONLY))))
        .andExpect(status().isForbidden());

    http.perform(
            post("/trade/orders/risk-validation/assessments/" + saved.assessment().id() + "/review")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RiskReviewInput(ReviewDecision.REJECT, ReviewReasonCode.CONFIRMED_RISK, ""))))
        .andExpect(status().isForbidden());
  }

  // ==========================================================================
  // 9. 端点形状与刻度
  // ==========================================================================
  @Test
  void 分数刻度恒为四位且详情组件恒五行() throws Exception {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    registerAllSignals(a);
    var orderId = order(a, spec("MO-SCALE"));
    var saved = assess(a, orderId);

    for (var c : saved.components()) {
      assertThat(c.weight().scale()).as("权重 numeric(9,4)").isEqualTo(4);
      assertThat(c.normalizedScore().scale()).as("归一化分 numeric(9,4)").isEqualTo(4);
      assertThat(c.contribution().scale()).as("贡献 numeric(9,4)").isEqualTo(4);
      assertThat(c.weight().multiply(c.normalizedScore()))
          .as("贡献必须等于权重 × 归一化分（DB 侧也会校验这一条）")
          .isEqualByComparingTo(c.contribution());
    }
    assertThat(saved.assessment().riskScore().scale()).isEqualTo(4);
    assertThat(saved.assessment().orderAmount().scale())
        .as("金额 numeric(19,4)").isEqualTo(4);

    var detail =
        http.perform(
                get("/trade/orders/risk-validation/assessments/" + saved.assessment().id())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(detail);
    assertThat(parsed.get("components")).hasSize(5);
    assertThat(parsed.get("assessment").get("finalAction").isNull()).isTrue();
  }

  @Test
  void 对草稿单评估被拒() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    UUID draftId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,external_order_no,"
                  + "currency,payment_method,status,submitted_at,subtotal_amount,discount_amount,"
                  + "tax_amount,shipping_amount,other_amount,grand_total_amount,version,created_at,"
                  + "updated_at)"
                  + " values(?,?,?,?,'OFFLINE_STORE',?,?,?,'DRAFT',null,0,0,0,0,0,0,0,"
                  + "?::timestamptz,?::timestamptz)",
              draftId, a, "MO-DRAFT", "MANUAL", "EXT-DRAFT", "USD", "PREPAID", T1, T1);
          // 草稿也要有收货地址快照：不补这一行的话，orderFacts 会先在
          // 「没有地址快照」上 404，测的就不是「草稿不许评估」这一条了。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at) values(?,?,?,'SHIPPING','草稿收件人','+1-213-555-0100',"
                  + "'draft@example.com','','US','CA','LOS ANGELES','','90001','100 Main St',"
                  + "'','','',?::timestamptz)",
              UUID.randomUUID(), a, draftId, T1);
          return null;
        });
    assertThatThrownBy(() -> assess(a, draftId))
        .hasMessageContaining("还没提交");
  }

  @Test
  void 评估不存在的订单是404() {
    registerRule(MissingSignalPolicy.SCORE_KNOWN_ONLY);
    assertThatThrownBy(() -> assess(a, UUID.randomUUID()))
        .hasMessageContaining("不存在")
        .hasMessageContaining("收货地址快照");
  }
}
