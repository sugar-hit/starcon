package com.starcon.trade;

import static com.starcon.trade.AddressPaymentCheckModels.*;
import static org.assertj.core.api.Assertions.*;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.2.2.1 地址与支付校验的模块测试。
 *
 * <p>全部跑在 Testcontainers 的真实 PostgreSQL 16 上（连接池压到 1），
 * 数据真落 V210 的六张表，不 mock 任何判定。
 *
 * <p>本类最要紧的一组用例是<b>蓝图加粗句「货到付款订单的履约风险最高，需额外风控与保险策略」</b>：
 * 货到付款恒为最高风险档（且这一档<b>只有</b>货到付款能用），
 * 保险策略恒为「未交付」—— 而<b>绕过服务层直接写 SQL</b> 同样会被 V210 的守卫挡下。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AddressPaymentCheckIntegrationTest {
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
          "truncate order_address_check_item, order_address_check,"
              + " order_address_check_payment_rule, order_address_check_country_rule,"
              + " order_address_check_embargo_rule, order_address_check_policy,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  AddressPaymentPolicyInput defaultPolicy() {
    return new AddressPaymentPolicyInput("默认校验策略", AddressPaymentMissingReferencePolicy.ASSESS_KNOWN_ONLY);
  }

  void saveDefaultPolicy() {
    asA(() -> service.savePolicy(defaultPolicy()));
  }

  /** 一张可校验的订单：已提交 + 收货地址快照 + 一行商品。 */
  UUID seedOrder(
      String orderNo,
      String paymentMethod,
      String country,
      String postcode,
      String phone,
      String addressLine1,
      String city,
      String province,
      OffsetDateTime submittedAt) {
    UUID orderId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    BigDecimal price = new BigDecimal("19.9900");
    BigDecimal lineAmount = price.setScale(4, java.math.RoundingMode.HALF_UP);
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,payment_terms_days,status,submitted_at,subtotal_amount,"
                  + "discount_amount,tax_amount,shipping_amount,other_amount,grand_total_amount,"
                  + "version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,'SUBMITTED',?::timestamptz,?,0,0,0,0,?,0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId, a, orderNo, "AMAZON", "EXT-" + orderNo, "USD", paymentMethod,
              // V99 的 sales_order_payment_terms_self_consistent：账期单必须带天数，
              // 非账期不许带天数 —— 写成 0 会让账期单被 CHECK 拒。
              "CREDIT_TERM".equals(paymentMethod) ? 30 : 0,
              submittedAt, lineAmount, lineAmount, submittedAt, submittedAt);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,1,?,0,0,?,'',?::timestamptz)",
              UUID.randomUUID(), a, orderId, skuId, "SKU-" + orderNo, "商品", price, lineAmount,
              submittedAt);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
                  + "postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING','收件人',?,?,'',?,?,?,'',?,?,'','','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), a, orderId, phone, "buyer@example.com", country, province, city,
              postcode, addressLine1, submittedAt);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), a, orderId, UUID.randomUUID(), "C-1", "收件人", submittedAt);
          return null;
        });
    return orderId;
  }

  UUID seedUsPrepaidOrder() {
    return seedOrder(
        "SO-US-PREPAID", "PREPAID", "US", "94105", "4155550100", "1 Market Street", "San Francisco",
        "CA", T1);
  }

  AddressPaymentCheckItem itemOf(AddressPaymentCheckDetail detail, String code) {
    return detail.items().stream()
        .filter(i -> i.itemCode().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("明细里没有 " + code + " 这一项：" + detail.items()));
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

  /**
   * 以**迁移超级账号**执行一条 SQL。
   *
   * <p>用来证明「DB 层的守卫本身也成立」：运行角色被 REVOKE 挡住是第一道防线，
   * 但把超级账号也试一遍，才能证明**表级 CHECK 与留痕触发器**同样在生效 ——
   * 只测第一道防线的话，撤掉 REVOKE 之后守卫就形同虚设，而测试仍然全绿。
   */
  void asSuper(String statement, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < binds.length; i++) {
        ps.setObject(i + 1, binds[i]);
      }
      ps.execute();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ============================================================ 能力声明

  @Test
  void capabilitiesSayInsuranceIsNotDeliveredInsteadOfFakingIt() {
    saveDefaultPolicy();
    AddressPaymentCapabilities caps = asA(() -> service.capabilities());

    assertThat(caps.items()).hasSize(7);
    AddressPaymentCapabilityItem insurance =
        caps.items().stream()
            .filter(i -> i.code().equals("COD_INSURANCE_POLICY"))
            .findFirst()
            .orElseThrow();
    assertThat(insurance.available()).isFalse();
    assertThat(insurance.ownerModule()).isNotBlank();
    assertThat(insurance.impact()).contains("未交付");

    // 支付状态那一项也必须如实说「本服务没有这个事实」。
    AddressPaymentCapabilityItem payment =
        caps.items().stream()
            .filter(i -> i.code().equals("PAYMENT_CONSISTENCY"))
            .findFirst()
            .orElseThrow();
    assertThat(payment.available()).isFalse();
    assertThat(payment.impact()).contains("12 财务");

    // 禁运清单这一项是真接通的（读租户可维护的清单 + 收货地址快照）。
    AddressPaymentCapabilityItem embargo =
        caps.items().stream()
            .filter(i -> i.code().equals("COUNTRY_EMBARGO"))
            .findFirst()
            .orElseThrow();
    assertThat(embargo.available()).isTrue();
  }

  @Test
  void emptyEmbargoListIsReportedAsNotConfigured() {
    saveDefaultPolicy();
    assertThat(asA(() -> service.capabilities()).embargoListConfigured()).isFalse();
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "US", AddressPaymentEmbargoLevel.BLOCK, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "测试用禁运", null, null, null)));
    assertThat(asA(() -> service.capabilities()).embargoListConfigured()).isTrue();
  }

  // ============================================================ 策略

  @Test
  void policyMustBeRegisteredBeforeChecking() {
    // 没登记就报「未配置」，不给隐含默认值。
    assertThatThrownBy(() -> asA(() -> service.policy()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("还没有登记");
  }

  @Test
  void savingPolicyInstallsBaselineCodeTables() {
    saveDefaultPolicy();
    // 基线码表在**迁移脚本**里，不在 Java 里：登记策略时幂等装上。
    assertThat(asA(() -> service.pageCountryRules(null, 0, 200)).total()).isEqualTo(34);
    assertThat(asA(() -> service.pagePaymentRules(null, null, 0, 200)).total()).isEqualTo(24);
    // 幂等：再登记一次不会覆盖租户已改的行，也不会翻倍。
    asA(() -> service.saveCountryRule(
        new AddressPaymentCountryRuleInput("US", true, "^[0-9]{5}$", "美国 5 位", true, 3, true,
            false, true, null)));
    saveDefaultPolicy();
    assertThat(asA(() -> service.pageCountryRules(null, 0, 200)).total()).isEqualTo(34);
    assertThat(asA(() -> service.pageCountryRules("US", 0, 200)).items().get(0).postcodePattern())
        .isEqualTo("^[0-9]{5}$");
  }

  @Test
  void countryRuleWithUncompilableRegexIsRejected() {
    saveDefaultPolicy();
    // 写进去一条编译不过的正则 ⇒ 该国的邮编项永远判不了，而界面上它显示为「已配置」。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveCountryRule(
                            new AddressPaymentCountryRuleInput(
                                "FR", true, "^[0-9{5}$", "坏正则", true, 3, true, true, true, null))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("编译不过");
  }

  // ============================================================ 货到付款（加粗句）

  @Test
  void codOrderGetsCriticalRiskWhileAddressStaysDeliverable() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-COD", "COD", "US", "94105", "4155550100", "1 Market Street", "San Francisco",
            "CA", T1);

    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.COD,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());

    // 加粗句：货到付款 → 最高风险档。
    assertThat(detail.check().riskLevel()).isEqualTo("CRITICAL");
    assertThat(detail.check().codFlagged()).isTrue();
    // 但货到付款**不是**地址问题：地址照样可投递。
    assertThat(detail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
    // 货到付款是**独立的一项**，不是支付一致性里的一个分支。
    AddressPaymentCheckItem cod = itemOf(detail, "COD_FULFILLMENT_RISK");
    assertThat(cod.status()).isEqualTo("FLAGGED");
    assertThat(cod.severity()).isEqualTo("CRITICAL");
    // 保险策略恒为未交付，**不是**「保险没问题」。
    AddressPaymentCheckItem insurance = itemOf(detail, "COD_INSURANCE_POLICY");
    assertThat(insurance.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(insurance.observedValue()).isNull();
    // 七项恒定各占一行。
    assertThat(detail.items()).hasSize(7);
  }

  @Test
  void nonCodOrderHasNoCodItemsAndNoCriticalRisk() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(detail.check().riskLevel()).isEqualTo("LOW");
    assertThat(detail.check().codFlagged()).isFalse();
    assertThat(itemOf(detail, "COD_FULFILLMENT_RISK").status()).isEqualTo("NOT_APPLICABLE");
    assertThat(itemOf(detail, "COD_INSURANCE_POLICY").status()).isEqualTo("NOT_APPLICABLE");
  }

  // ============================================================ 禁运清单

  @Test
  void embargoedCountryIsNotDeliverable() {
    saveDefaultPolicy();
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "US", AddressPaymentEmbargoLevel.BLOCK, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "合规原因", null, null, null)));
    UUID orderId = seedUsPrepaidOrder();

    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());

    assertThat(detail.check().deliveryStatus()).isEqualTo("NOT_DELIVERABLE");
    assertThat(detail.check().riskLevel()).isEqualTo("HIGH");
    AddressPaymentCheckItem embargo = itemOf(detail, "COUNTRY_EMBARGO");
    assertThat(embargo.status()).isEqualTo("FLAGGED");
    assertThat(embargo.severity()).isEqualTo("BLOCK");
    assertThat(embargo.evidenceReference()).contains("US");
  }

  @Test
  void embargoReviewOnlyIsWarnAndKeepsAddressDeliverable() {
    saveDefaultPolicy();
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "US", AddressPaymentEmbargoLevel.REVIEW, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "需人工确认", null, null, null)));
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(detail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
    assertThat(detail.check().riskLevel()).isEqualTo("MEDIUM");
    assertThat(itemOf(detail, "COUNTRY_EMBARGO").severity()).isEqualTo("WARN");
  }

  @Test
  void embargoEffectiveWindowIsJudgedAgainstTheOrderSubmittedAt() {
    saveDefaultPolicy();
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "US", AddressPaymentEmbargoLevel.BLOCK, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "只在某段时间禁运", T1.plusDays(10), T1.plusDays(20), null)));
    // 订单在禁运生效**之前**提交 ⇒ 生效期不覆盖判定基准时刻 ⇒ 不命中。
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(itemOf(detail, "COUNTRY_EMBARGO").status()).isEqualTo("CLEARED");
    assertThat(detail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
  }

  // ============================================================ 按国家维度的邮编/街道

  @Test
  void postcodeFormatIsJudgedPerCountryNotByOneRegex() {
    saveDefaultPolicy();
    // 美国邮编 5 位：德国的 5 位数字在**美国**规则下也是合法的（都是 5 位数字），
    // 但英国的字母数字邮编在**美国**规则下不合法 —— 一套正则打天下会判错。
    UUID ukStyle = seedOrder("SO-UK-POST", "PREPAID", "US", "SW1A 1AA", "4155550100",
        "1 Market Street", "San Francisco", "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(ukStyle, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(itemOf(detail, "POSTCODE_FORMAT").status()).isEqualTo("FLAGGED");
    assertThat(itemOf(detail, "POSTCODE_FORMAT").severity()).isEqualTo("BLOCK");
    assertThat(detail.check().deliveryStatus()).isEqualTo("NOT_DELIVERABLE");

    // 同一个邮编，换成德国的规则（也是 5 位数字）就合法 ⇒ 证明规则是**按国家**取的。
    UUID de = seedOrder("SO-DE-POST", "PREPAID", "DE", "10115", "4155550100", "Unter den Linden 1",
        "Berlin", "BE", T1);
    AddressPaymentCheckDetail deDetail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(de, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(itemOf(deDetail, "POSTCODE_FORMAT").status()).isEqualTo("CLEARED");
    assertThat(deDetail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
  }

  @Test
  void streetShorterThanTheCountryMinimumIsFlagged() {
    saveDefaultPolicy();
    UUID orderId = seedOrder("SO-SHORT", "PREPAID", "US", "94105", "4155550100", "ab", "San Francisco",
        "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    AddressPaymentCheckItem street = itemOf(detail, "STREET_FORMAT");
    assertThat(street.status()).isEqualTo("FLAGGED");
    assertThat(street.observedValue()).isEqualByComparingTo("1");
    assertThat(street.thresholdValue()).isEqualByComparingTo("5");
    assertThat(street.evidenceReference()).contains("2");
  }

  @Test
  void missingRequiredContactCountsEveryMissingField() {
    saveDefaultPolicy();
    UUID orderId = seedOrder("SO-NOPHONE", "PREPAID", "US", "94105", "", "1 Market Street",
        "San Francisco", "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    AddressPaymentCheckItem contact = itemOf(detail, "REQUIRED_CONTACT");
    assertThat(contact.status()).isEqualTo("FLAGGED");
    assertThat(contact.observedValue()).isEqualByComparingTo("1");
    assertThat(contact.evidenceReference()).contains("电话");
    assertThat(contact.matchCount()).isEqualTo(1);
  }

  // ============================================================ 缺参照

  @Test
  void countryWithoutRuleIsNoReferenceNotCleared() {
    saveDefaultPolicy();
    // 新加坡在基线里；用「ZZ」这种不存在的国家码 ⇒ 该国没有规则。
    UUID orderId = seedOrder("SO-ZZ", "PREPAID", "ZZ", "12345", "4155550100", "1 Market Street",
        "Somewhere", "XX", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    // 「没量到」不是 0：这一项落 NO_REFERENCE，**不是**「已核对、未命中」。
    assertThat(itemOf(detail, "POSTCODE_FORMAT").status()).isEqualTo("NO_REFERENCE");
    assertThat(itemOf(detail, "POSTCODE_FORMAT").observedValue()).isNull();
    assertThat(itemOf(detail, "STREET_FORMAT").status()).isEqualTo("NO_REFERENCE");
    assertThat(itemOf(detail, "REQUIRED_CONTACT").status()).isEqualTo("NO_REFERENCE");
    // ASSESS_KNOWN_ONLY ⇒ 按已评估项判定，可投递。
    assertThat(detail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
    assertThat(detail.check().noReferenceCount()).isEqualTo(3);
  }

  @Test
  void blockDecisionPolicyDoesNotConcludeDeliverableWhenReferenceIsMissing() {
    saveDefaultPolicy();
    asA(
        () ->
            service.savePolicy(
                new AddressPaymentPolicyInput(
                    "缺参照不出结论", AddressPaymentMissingReferencePolicy.BLOCK_DECISION)));
    UUID orderId = seedOrder("SO-ZZ2", "PREPAID", "ZZ", "12345", "4155550100", "1 Market Street",
        "Somewhere", "XX", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(detail.check().deliveryStatus()).isEqualTo("UNDETERMINED");
  }

  @Test
  void paymentStatusNotProvidedIsNoReferenceNotConsistent() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, null, null, null),
                    actorA.membershipId()).detail());
    AddressPaymentCheckItem payment = itemOf(detail, "PAYMENT_CONSISTENCY");
    assertThat(payment.status()).isEqualTo("NO_REFERENCE");
    assertThat(payment.observedValue()).isNull();
    assertThat(payment.explanation()).contains("不是");
  }

  @Test
  void missingPaymentRuleIsNoReferenceNotSilentlyConsistent() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    // 删掉这一条规则 ⇒ 「查不到规则」只能是**缺参照**，不能是「自洽」。
    asA(() -> sql.execute(
        "delete from order_address_check_payment_rule where payment_method = 'PREPAID'"
            + " and observed_status = 'UNPAID'"));
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.UNPAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    AddressPaymentCheckItem payment = itemOf(detail, "PAYMENT_CONSISTENCY");
    assertThat(payment.status()).isEqualTo("NO_REFERENCE");
    assertThat(detail.check().riskLevel()).isEqualTo("LOW");
  }

  // ============================================================ 支付一致性

  @Test
  void prepaidOrderUnpaidIsBlockAndHighRiskButStillDeliverable() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.UNPAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    AddressPaymentCheckItem payment = itemOf(detail, "PAYMENT_CONSISTENCY");
    assertThat(payment.status()).isEqualTo("FLAGGED");
    assertThat(payment.severity()).isEqualTo("BLOCK");
    // 支付矛盾**不改变**可投递性：货还是能送到，钱收不回来是另一回事。
    assertThat(detail.check().deliveryStatus()).isEqualTo("DELIVERABLE");
    assertThat(detail.check().riskLevel()).isEqualTo("HIGH");
  }

  @Test
  void codOrderReportedAsPaidIsStillCriticalBecauseCodOutranksEverything() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-COD-PAID", "COD", "US", "94105", "4155550100", "1 Market Street",
            "San Francisco", "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(itemOf(detail, "PAYMENT_CONSISTENCY").status()).isEqualTo("FLAGGED");
    // 货到付款压在最上面：即使还有 BLOCK 级命中，最高风险档仍然是 CRITICAL。
    assertThat(detail.check().riskLevel()).isEqualTo("CRITICAL");
  }

  @Test
  void creditTermUnpaidIsSelfConsistent() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-CREDIT", "CREDIT_TERM", "US", "94105", "4155550100", "1 Market Street",
            "San Francisco", "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.UNPAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(itemOf(detail, "PAYMENT_CONSISTENCY").status()).isEqualTo("CLEARED");
    assertThat(detail.check().riskLevel()).isEqualTo("LOW");
  }

  // ============================================================ 确定性 / 时间

  @Test
  void sameOrderCheckedTwiceGivesIdenticalConclusions() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail first =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    AddressPaymentCheckDetail second =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    assertThat(second.check().riskLevel()).isEqualTo(first.check().riskLevel());
    assertThat(second.check().deliveryStatus()).isEqualTo(first.check().deliveryStatus());
    assertThat(second.items()).usingRecursiveComparison()
        .ignoringFields("observedValue", "thresholdValue")
        .isEqualTo(first.items());
    // 两次校验各占一行：历史不被覆盖。
    assertThat(second.check().id()).isNotEqualTo(first.check().id());
  }

  @Test
  void assessedReferenceAtIsPinnedToTheOrderSubmittedAt() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-T8", "PREPAID", "US", "94105", "4155550100", "1 Market Street",
            "San Francisco", "CA", T8);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T8),
                    actorA.membershipId()).detail());
    // 判定基准时刻恒等于订单 submitted_at（不是 now()）。
    assertThat(detail.check().assessedReferenceAt()).isEqualTo(T8);
    // timestamptz 往返零漂移（+08:00 夹具写回仍是同一绝对时刻）。
    OffsetDateTime written =
        asA(
            () ->
                sql.fetchOne(
                        "select order_submitted_at from order_address_check where id = ?",
                        detail.check().id())
                    .get("order_submitted_at", OffsetDateTime.class));
    assertThat(written).isEqualTo(T8);
    assertThat(written.toInstant()).isEqualTo(T8.toInstant());
  }

  // ============================================================ 读路径

  @Test
  void latestForOrderReturnsTheMostRecentCheck() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    asA(
        () ->
            service.assess(
                new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                    AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                actorA.membershipId()).detail());
    AddressPaymentCheckDetail detail = asA(() -> service.latestForOrder(orderId));
    assertThat(detail.check().orderId()).isEqualTo(orderId);
    assertThat(detail.items()).hasSize(7);
  }

  @Test
  void latestForNeverCheckedOrderIsNotFound() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    // 「从未校验过」与「校验结果是空」是两件事 ⇒ 404，不是空对象。
    assertThatThrownBy(() -> asA(() -> service.latestForOrder(orderId)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("还没有跑过");
  }

  @Test
  void unknownOrderIsNotFound() {
    saveDefaultPolicy();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.assess(
                            new AddressPaymentCheckInput(UUID.randomUUID(), null, null, null),
                            actorA.membershipId())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("订单不存在");
  }

  // ============================================================ 绕过服务层也挡得住

  @Test
  void directSqlCannotDowngradeCodToLowerRisk() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-COD-DIRECT", "COD", "US", "94105", "4155550100", "1 Market Street",
            "San Francisco", "CA", T1);
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.COD,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    // 第一道防线：V210 对风险等级三列做了**列级 REVOKE UPDATE**，运行角色连试都试不了。
    var denied =
        catchThrowable(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update order_address_check set risk_level = 'LOW' where id = ?",
                            detail.check().id())));
    assertThat(rootMessage(denied)).as("运行角色改不动风险等级").contains("permission denied");
    // 第二道防线：换成**迁移超级账号**试一次，表级 CHECK 同样把它挡下
    // （撤掉 REVOKE 之后守卫也必须还在，否则撤权限就等于撤守卫）。
    var guarded =
        catchThrowable(
            () ->
                asSuper(
                    "update order_address_check set risk_level = 'LOW' where id = ?",
                    detail.check().id()));
    assertThat(rootMessage(guarded)).as("超级账号也改不动：CRITICAL ⇔ cod_flagged 双向绑定")
        .contains("order_address_check_check");
  }

  @Test
  void directSqlCannotMarkNonCodAsCritical() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    var guarded =
        catchThrowable(
            () ->
                asSuper(
                    "update order_address_check set cod_flagged = false, risk_level = 'CRITICAL'"
                        + " where id = ?",
                    detail.check().id()));
    assertThat(rootMessage(guarded)).as("给非货到付款单虚标最高风险同样被 CHECK 挡下")
        .contains("order_address_check_check");
  }

  @Test
  void directSqlCannotGiveCriticalToAnotherItem() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    // 「最高风险」这一档只能给货到付款项 ⇒ 别的项拿 CRITICAL 写不进去。
    var guarded =
        catchThrowable(
            () ->
                asSuper(
                    "update order_address_check_item set severity = 'CRITICAL'"
                        + " where check_id = ? and item_code = 'POSTCODE_FORMAT'",
                    detail.check().id()));
    assertThat(rootMessage(guarded)).as("CRITICAL 这一档有且只有货到付款能用")
        .contains("order_address_check_item");
  }

  @Test
  void directSqlCannotRewriteCheckItems() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    AddressPaymentCheckDetail detail =
        asA(
            () ->
                service.assess(
                    new AddressPaymentCheckInput(orderId, AddressPaymentStatus.PAID,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId()).detail());
    // 明细是留痕：改一格校验说明就再也复算不出当时为什么判成这个风险等级。
    var denied =
        catchThrowable(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update order_address_check_item set explanation = '改了' where check_id = ?",
                            detail.check().id())));
    assertThat(rootMessage(denied)).as("V210 对明细表 REVOKE UPDATE/DELETE").contains("permission denied");
    var guarded =
        catchThrowable(
            () ->
                asSuper(
                    "update order_address_check_item set explanation = '改了' where check_id = ?",
                    detail.check().id()));
    assertThat(rootMessage(guarded)).as("append-only 触发器在超级账号下同样生效")
        .contains("不可改写");
  }

  @Test
  void checkWithoutItemsCannotBeCommitted() {
    saveDefaultPolicy();
    UUID orderId = seedUsPrepaidOrder();
    // 绕过服务层只插头不插明细 ⇒ 提交期不变量把它挡下（读出来会是「低风险」）。
    var e =
        catchThrowable(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "insert into order_address_check(id,tenant_id,order_id,order_no,"
                                + "order_source,order_currency,order_amount,order_submitted_at,"
                                + "payment_method,policy_id,policy_version,policy_name,"
                                + "assessed_reference_at,delivery_status,risk_level,cod_flagged,"
                                + "item_count,flagged_count,cleared_count,no_reference_count,"
                                + "upstream_unavailable_count,not_applicable_count,blocked_count,"
                                + "warn_count,critical_count,created_at)"
                                + " values(?,?,?,?,'MANUAL','USD',0,?::timestamptz,'PREPAID',"
                                + "(select id from order_address_check_policy where tenant_id = "
                                + "current_setting('app.current_tenant')::uuid),0,'默认',"
                                + "?::timestamptz,'DELIVERABLE','LOW',false,7,0,0,7,0,0,0,0,0,now())",
                            UUID.randomUUID(), a, orderId, "SO-DIRECT", T1, T1)));
    assertThat(rootMessage(e)).as("明细没写完就落库会让这单被读成「低风险」")
        .contains("从未被算过合计");
  }

  // ============================================================ 列表与概览

  @Test
  void checkListIsReallyPagedAndFiltered() {
    saveDefaultPolicy();
    UUID cod = seedOrder("SO-L2", "COD", "US", "94105", "4155550100", "1 Market Street",
        "San Francisco", "CA", T1);
    for (int i = 0; i < 3; i++) {
      UUID id = seedOrder("SO-L3-" + i, "PREPAID", "US", "94105", "4155550100", "1 Market Street",
          "San Francisco", "CA", T1);
      asA(
          () ->
              service.assess(
                  new AddressPaymentCheckInput(id, AddressPaymentStatus.PAID,
                      AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                  actorA.membershipId()).detail());
    }
    asA(
        () ->
            service.assess(
                    new AddressPaymentCheckInput(cod, AddressPaymentStatus.COD,
                        AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                    actorA.membershipId())
                .detail());
    AddressPaymentCheckPage page = asA(() -> service.pageChecks(null, null, null, null, 0, 2));
    // 四单都跑过校验（3 单预付 + 1 单货到付款），分页两页。
    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items()).hasSize(2);
    assertThat(page.totalPages()).isEqualTo(2);
    // 筛选：只取 CRITICAL 的一单。
    assertThat(asA(() -> service.pageChecks(null, "CRITICAL", null, null, 0, 50)).total())
        .isEqualTo(1);
    // 按订单筛。
    assertThat(asA(() -> service.pageChecks(null, null, null, cod, 0, 50)).total()).isEqualTo(1);
    // 没跑过校验的订单筛不出任何行（不是「校验结果是空」）。
    assertThat(asA(() -> service.pageChecks(null, null, null, UUID.randomUUID(), 0, 50)).total())
        .isZero();
  }

  @Test
  void overviewCountsAreIndependentOfPaging() {
    saveDefaultPolicy();
    UUID orderId =
        seedOrder("SO-OV", "COD", "US", "94105", "4155550100", "1 Market Street", "San Francisco",
            "CA", T1);
    asA(
        () ->
            service.assess(
                new AddressPaymentCheckInput(orderId, AddressPaymentStatus.COD,
                    AddressPaymentStatusSource.PLATFORM_CALLBACK, T1),
                actorA.membershipId()).detail());
    AddressPaymentOverview overview = asA(() -> service.overview());
    assertThat(overview.totalChecks()).isEqualTo(1);
    assertThat(overview.codChecks()).isEqualTo(1);
    assertThat(overview.criticalChecks()).isEqualTo(1);
    assertThat(overview.notDeliverableChecks()).isZero();
    assertThat(overview.countryRuleCount()).isEqualTo(34);
    assertThat(overview.paymentRuleCount()).isEqualTo(24);
  }

  @Test
  void embargoRuleListIsReallySearchable() {
    saveDefaultPolicy();
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "US", AddressPaymentEmbargoLevel.BLOCK, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "受制裁", null, null, null)));
    asA(
        () ->
            service.saveEmbargoRule(
                new AddressPaymentEmbargoRuleInput(
                    "RU", AddressPaymentEmbargoLevel.REVIEW, AddressPaymentRuleSource.TENANT_MAINTAINED,
                    null, "高风险地区", null, null, null)));
    assertThat(asA(() -> service.pageEmbargoRules("US", null, 0, 50)).total()).isEqualTo(1);
    assertThat(asA(() -> service.pageEmbargoRules("高风险", null, 0, 50)).total()).isEqualTo(1);
    assertThat(asA(() -> service.pageEmbargoRules(null, "BLOCK", 0, 50)).total()).isEqualTo(1);
    assertThat(asA(() -> service.pageEmbargoRules(null, null, 0, 50)).total()).isEqualTo(2);
  }
}
