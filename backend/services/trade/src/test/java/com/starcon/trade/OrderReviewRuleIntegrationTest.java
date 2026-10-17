package com.starcon.trade;

import static com.starcon.trade.OrderReviewRuleModels.*;
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
 * 9.2.1.2 审单规则。
 *
 * <p>本测试是 <b>V149 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条
 * 连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉住的十一组</b>：
 *
 * <ol>
 *   <li><b>可解释是加粗句</b>：命中链必须能说出「哪条规则的哪个条件、拿订单的哪个字段、实际值多少」，
 *       且**未命中的规则与条件同样留痕**；</li>
 *   <li><b>强制转人工压过一切自动通过</b>：priority=999 的自动通过压不住 priority=1 的强制转人工；</li>
 *   <li><b>无规则命中 = 转人工</b>，不给隐含默认值；</li>
 *   <li><b>生效期真的参与判定</b>：生效前 / 生效后 / 到期后三个时刻结论不同，且**断言写回的时刻值**
 *       （timestamptz 绑定正确性 —— 用 now() 或 Timestamp.valueOf 只会静默偏 −28800s）；</li>
 *   <li><b>取不到值就是取不到</b>：买家未登记画像时等级条件不满足，不兜底 STANDARD；</li>
 *   <li><b>地址异常是实算的</b>，五种异常码逐条可复现；</li>
 *   <li><b>异常折扣</b>：折扣率百分数口径正确，且**小计为 0 时是 null 而不是 0**；</li>
 *   <li><b>金额刻度</b>：读回来仍是 numeric(19,4)，断言到分；</li>
 *   <li><b>DB 级闸门</b>：ACTIVE 零条件规则被延迟约束触发器挡下；维度与运算符不匹配被 CHECK 挡下；
 *       判定留痕 append-only；</li>
 *   <li><b>试跑与生效判定分离</b>：试跑标成 DRY_RUN 且计入汇总，但不冒充生产结论；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户，
 *       外加**跨租户不可判定**（A 会话拿 B 的订单 id 必须失败）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderReviewRuleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("order_review")
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
          actor(user, a, null, List.of(grant("ALL", List.of("trade.order_review.read")))));
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

  @Autowired OrderReviewRuleService service;
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
    return access.run(actorA, "trade.order_review.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.order_review.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  // ------------------------------------------------------------ 夹具（时刻全部钉死）

  /**
   * 判定时刻刻意用**非 UTC 偏移**：timestamptz 绑定错时区只会静默偏 −28800s，
   * 用 UTC 夹具永远测不出来。
   */
  static final OffsetDateTime T_EARLY = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime T_MID = OffsetDateTime.parse("2026-03-05T16:30:45+08:00");
  static final OffsetDateTime T_LATE = OffsetDateTime.parse("2026-04-01T09:00:00+08:00");

  static final String RECIPIENT = "Ada Lovelace";
  static final String PHONE = "+1-213-555-0100";
  static final String EMAIL = "ada@example.com";
  static final String COUNTRY = "US";

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_review_rule_hit_condition, order_review_rule_hit,"
              + " order_review_rule_evaluation, order_review_buyer_profile,"
              + " order_review_rule_condition, order_review_rule,"
              + " order_fulfillment_line_progress, order_charge, order_address_snapshot,"
              + " sales_order_party_snapshot, sales_order_line, sales_order cascade");
    }
  }

  /** 订单夹具。参数覆盖判定用到的每一个维度。 */
  record Spec(
      String orderNo,
      String source,
      BigDecimal unitPrice,
      BigDecimal qty,
      BigDecimal lineDiscount,
      String skuCode,
      String recipient,
      String phone,
      String email,
      String country,
      String company,
      String city,
      String district,
      String postcode,
      String line1) {}

  static Spec spec(String orderNo) {
    return new Spec(orderNo, "AMAZON", new BigDecimal("100.0000"), new BigDecimal("2.000000"),
        BigDecimal.ZERO, "SKU-STD", RECIPIENT, PHONE, EMAIL, COUNTRY, "", "LOS ANGELES", "", "90001",
        "100 Main St");
  }

  static Spec copy(Spec s, java.util.function.UnaryOperator<Spec> f) {
    return f.apply(s);
  }

  /**
   * 落一张 SUBMITTED 订单。
   *
   * <p>走「先插 DRAFT 头 → 写行/地址/客户快照 → UPDATE 提交」这条真实路径，
   * 让 V99 的 {@code guard_sales_order_totals} 独立复算并替我们把关头与行两个答案一致。
   */
  UUID order(UUID tenant, Spec s) {
    UUID orderId = UUID.randomUUID();
    return as(tenant, () -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,external_order_no,"
              + "currency,payment_method,payment_terms_days,status,submitted_at,subtotal_amount,"
              + "discount_amount,tax_amount,shipping_amount,other_amount,grand_total_amount,note,version,"
              + "created_at,updated_at) values(?,?,?,?,?,?,'USD','PREPAID',0,'DRAFT',null,0,0,0,0,0,0,"
              + "'',0,?::timestamptz,?::timestamptz)",
          orderId, tenant, s.orderNo(), s.source(),
          "MANUAL".equals(s.source()) ? "OFFLINE_STORE" : "", "EXT-" + s.orderNo(),
          T_EARLY, T_EARLY);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at) "
              + "values(?,?,?,1,?,?,?,?,?::numeric,?::numeric,0,?::numeric,'',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), s.skuCode(), "测试商品",
          s.qty(), s.unitPrice(), s.lineDiscount(),
          // V99 的 sales_order_line_amount_matches = round(unit_price * quantity, 4)：
          // 行小计**不含**折扣（折扣走行折扣列与订单头折扣汇总）。
          s.unitPrice().multiply(s.qty()).setScale(4, java.math.RoundingMode.HALF_UP),
          T_EARLY);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,customer_code,"
              + "customer_name,customer_type,created_at) values(?,?,?,?,?,?,'INDIVIDUAL',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-1", RECIPIENT, T_EARLY);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,district,postcode,"
              + "address_line1,address_line2,tax_id,note,created_at) "
              + "values(?,?,?,'SHIPPING',?,?,?,?,?,'CA',?,?,?,?,'','','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, s.recipient(), s.phone(), s.email(), s.company(),
          s.country(), s.city(), s.district(), s.postcode(), s.line1(), T_EARLY);
      // 提交：触发 V99 的头行一致性复算（折扣取正、行折扣恒为负）。
      BigDecimal subtotal = s.unitPrice().multiply(s.qty()).setScale(4, java.math.RoundingMode.HALF_UP);
      BigDecimal discount = s.lineDiscount().negate().setScale(4, java.math.RoundingMode.HALF_UP);
      sql.execute(
          "update sales_order set status='SUBMITTED',submitted_at=?::timestamptz,subtotal_amount=?::numeric,"
              + "discount_amount=?::numeric,tax_amount=0,shipping_amount=0,other_amount=0,"
              + "grand_total_amount=?::numeric,updated_at=?::timestamptz where tenant_id=? and id=?",
          T_EARLY, subtotal, discount, subtotal.subtract(discount), T_EARLY, tenant, orderId);
      return orderId;
    });
  }

  /** 一条规则的条件（默认一条）。 */
  static ReviewRuleConditionInput cond(
      int seq, ConditionDimension dim, ConditionOperator op, List<String> set, BigDecimal num) {
    return new ReviewRuleConditionInput(seq, dim, op, set, num, "");
  }

  static ReviewRuleInput ruleInput(
      String code, int priority, ReviewAction action, boolean forced, OffsetDateTime from,
      OffsetDateTime to, ReviewRuleConditionInput... conditions) {
    return new ReviewRuleInput(code, "规则 " + code, "测试用规则", priority, action, forced, "ALL", from, to,
        "", "", List.of(conditions), null);
  }

  ReviewRuleSaved save(UUID tenant, ReviewRuleInput input) {
    return as(tenant, () -> service.saveRule(input, actors.get("Bearer " + (tenant.equals(a) ? "tenant-a" : "tenant-b")).membershipId()));
  }

  ReviewDetail review(UUID tenant, UUID orderId, OffsetDateTime at) {
    return as(tenant, () -> service.review(orderId, new ReviewRunInput(at, null),
        actors.get("Bearer " + (tenant.equals(a) ? "tenant-a" : "tenant-b")).membershipId()));
  }

  ReviewDetail trial(UUID tenant, UUID orderId, OffsetDateTime at, List<UUID> ruleIds) {
    return as(tenant, () -> service.trial(orderId, new ReviewRunInput(at, ruleIds),
        actors.get("Bearer " + (tenant.equals(a) ? "tenant-a" : "tenant-b")).membershipId()));
  }

  ReviewRuleSaved activate(UUID tenant, UUID ruleId, String note) {
    return as(tenant, () -> service.changeStatus(ruleId,
        new ReviewRuleStatusInput(ReviewRuleStatus.ACTIVE, null, null, note, null),
        actors.get("Bearer " + (tenant.equals(a) ? "tenant-a" : "tenant-b")).membershipId()));
  }

  static HitCondition hitOf(ReviewDetail d, String ruleCode) {
    RuleHit h = d.hits().stream().filter(x -> x.ruleCode().equals(ruleCode)).findFirst()
        .orElseThrow(() -> new AssertionError("命中链里没有规则 " + ruleCode + "，实际有："
            + d.hits().stream().map(RuleHit::ruleCode).toList()));
    return h.conditions().get(0);
  }

  // ================================================================ 能力与规则维护

  @Test
  void 能力声明如实报出店铺与买家等级的缺口() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.ruleCount()).isZero();
    assertThat(caps.conditionDimensions()).hasSize(8);
    var shop = caps.dimensions().stream().filter(d -> d.code().equals("SHOP_CONDITION")).findFirst()
        .orElseThrow();
    assertThat(shop.available()).as("sales_order 没有 shop_code 列，店铺维度不能报成已支持").isFalse();
    assertThat(shop.unavailableReason()).contains("shop_code").contains("D5");
    var profile = caps.dimensions().stream().filter(d -> d.code().equals("BUYER_PROFILE")).findFirst()
        .orElseThrow();
    assertThat(profile.available()).as("买家等级与黑名单由本服务自维护，是真的可用").isTrue();
    assertThat(caps.notes()).contains("强制转人工压过一切自动通过").contains("没有规则命中 = 转人工");
    assertThat(caps.downstreamGaps()).contains("9.2.1.1").contains("未交付");
  }

  @Test
  void 规则维护全流程_条件整体替换_编号不可重复() {
    ReviewRuleSaved r1 = save(a, ruleInput("R-001", 100, ReviewAction.AUTO_APPROVE, false, T_EARLY, null,
        cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("500.0000"))));
    assertThat(r1.status()).isEqualTo(ReviewRuleStatus.DRAFT);
    assertThat(r1.conditionCount()).isEqualTo(1);
    assertThat(r1.message()).contains("草稿不参与判定");

    assertThatThrownBy(() -> save(a, ruleInput("R-001", 100, ReviewAction.AUTO_APPROVE, false,
        T_EARLY, null, cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null,
            new BigDecimal("1.0000")))))
        .as("编号在租户内唯一，它同时是同优先级时的排序依据")
        .hasMessageContaining("已存在");

    // 条件整体替换：旧的被移除，新的写上。
    var saved = save(a, new ReviewRuleInput("R-001", "改名后的规则", "", 200, ReviewAction.MANUAL_REVIEW,
        true, "ANY", T_EARLY, null, "", "", List.of(
            cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US", "CA"), null),
            cond(2, ConditionDimension.ITEM, ConditionOperator.IN, List.of("SKU-STD"), null)), 0L));
    var detail = asA(() -> service.rule(saved.id()));
    assertThat(detail.rule().ruleName()).isEqualTo("改名后的规则");
    assertThat(detail.rule().priority()).isEqualTo(200);
    assertThat(detail.conditions()).hasSize(2);
    assertThat(detail.conditions().get(0).dimension()).isEqualTo(ConditionDimension.SHIPPING_COUNTRY);
    assertThat(detail.conditions().get(1).valueSet()).containsExactly("SKU-STD");
    assertThat(detail.rule().version()).as("更新走乐观锁，版本递增").isEqualTo(1);
  }

  @Test
  void 维度与运算符不匹配被服务层与DB层各挡一次() {
    assertThatThrownBy(() -> save(a, ruleInput("R-BAD", 10, ReviewAction.MANUAL_REVIEW, false,
        T_EARLY, null,
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.GTE, null, new BigDecimal("50")))))
        .as("收货国家是词表，不能配数值型")
        .hasMessageContaining("不能配维度");
    assertThatThrownBy(() -> save(a, ruleInput("R-BAD2", 10, ReviewAction.MANUAL_REVIEW, false,
        T_EARLY, null,
        cond(1, ConditionDimension.BUYER_BLACKLIST, ConditionOperator.IN, List.of("TRUE"), null))))
        .as("黑名单是布尔量，用标记型而不是集合型")
        .hasMessageContaining("不能配维度");
    assertThatThrownBy(() -> save(a, ruleInput("R-BAD3", 10, ReviewAction.MANUAL_REVIEW, false,
        T_EARLY, null,
        cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("-1")))))
        .as("负阈值会让这条规则命中每一张单")
        .hasMessageContaining("不能为负");
  }

  @Test
  void ACTIVE零条件规则被延迟约束触发器挡下() {
    // 绕过服务层直接插：服务层已经挡过一次，这里验的是 **DB 层**那一道。
    assertThatThrownBy(() -> asA(() -> {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into order_review_rule(id,tenant_id,rule_code,rule_name,priority,action,"
              + "forced_manual,match_mode,status,effective_from,created_by_membership_id) "
              + "values(?,?,'R-NOCOND','零条件规则',10,'MANUAL_REVIEW',false,'ANY','ACTIVE',"
              + "?::timestamptz,?::uuid)",
          id, a, T_EARLY, actorA.membershipId());
      sql.execute("set constraints all immediate");
      return null;
    }))
        .as("零条件的 ANY 规则会放行每一张单，必须由 DB 挡住")
        .hasMessageContaining("至少要有一个条件");
  }

  @Test
  void 停用必须写备注() {
    ReviewRuleSaved r = save(a, ruleInput("R-DIS", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY, null,
        cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(a, r.id(), "上线");
    assertThatThrownBy(() -> asA(() -> service.changeStatus(r.id(),
        new ReviewRuleStatusInput(ReviewRuleStatus.DISABLED, null, null, "", null), actorA.membershipId())))
        .as("停用会改变之后所有订单的结论，必须留下理由")
        .hasMessageContaining("停用规则必须写备注");
  }

  // ================================================================ 判定：可解释

  @Test
  void 命中链逐条件可解释_含未命中的规则() {
    UUID orderId = order(a, spec("SO-1"));
    // 两条都命中、两条都不命中，四条都要留痕。
    ReviewRuleSaved bigAmount = save(a, ruleInput("R-BIG", 500, ReviewAction.MANUAL_REVIEW, true, T_EARLY,
        null, cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("100.0000"))));
    ReviewRuleSaved usCountry = save(a, ruleInput("R-US", 400, ReviewAction.AUTO_APPROVE, false, T_EARLY,
        null, cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    ReviewRuleSaved deCountry = save(a, ruleInput("R-DE", 300, ReviewAction.AUTO_APPROVE, false, T_EARLY,
        null, cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("DE"), null)));
    ReviewRuleSaved bigSku = save(a, ruleInput("R-SKU", 200, ReviewAction.AUTO_APPROVE, false, T_EARLY,
        null, cond(1, ConditionDimension.ITEM, ConditionOperator.IN, List.of("SKU-LUXURY"), null)));
    activate(a, bigAmount.id(), "大额强制转人工");
    activate(a, usCountry.id(), "美国自动通过");
    activate(a, deCountry.id(), "德国自动通过");
    activate(a, bigSku.id(), "奢侈品自动通过");

    var detail = review(a, orderId, T_MID);
    assertThat(detail.decision().decision()).isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(detail.decision().decisionReason())
        .as("结论必须点名是哪条规则，不能只是「命中了金额条件」")
        .contains("R-BIG").contains("强制转人工");
    assertThat(detail.decision().consideredRuleCount()).isEqualTo(4);
    assertThat(detail.decision().matchedRuleCount()).isEqualTo(2);
    assertThat(detail.decision().forcedRuleCount()).isEqualTo(1);

    // 次序 = priority DESC：命中链可按顺序复现。
    assertThat(detail.hits()).extracting(RuleHit::ruleCode)
        .containsExactly("R-BIG", "R-US", "R-DE", "R-SKU");
    assertThat(detail.hits()).extracting(RuleHit::evalRank).containsExactly(1, 2, 3, 4);

    // 未命中的规则同样留痕，且带「为什么没过」。
    RuleHit de = detail.hits().stream().filter(h -> h.ruleCode().equals("R-DE")).findFirst()
        .orElseThrow();
    assertThat(de.matched()).isFalse();
    assertThat(de.reason()).contains("未命中").contains("DE");

    // 逐条件明细：订单字段 + 实际值 + 人话解释。
    HitCondition amount = hitOf(detail, "R-BIG");
    assertThat(amount.actualField()).isEqualTo("sales_order.grand_total_amount");
    assertThat(amount.actualValue()).as("订单总额 2 × 100.0000 = 200.0000").isEqualTo("200.0000");
    assertThat(amount.satisfied()).isTrue();
    assertThat(amount.expected()).isEqualTo("100.0000");
    assertThat(amount.explanation())
        .contains("订单总额 200.0000").contains("大于等于").contains("100.0000").contains("满足");

    HitCondition country = hitOf(detail, "R-US");
    assertThat(country.actualField()).isEqualTo("order_address_snapshot.country_code");
    assertThat(country.actualValue()).isEqualTo("US");
    assertThat(country.satisfied()).isTrue();
    assertThat(country.explanation()).contains("收货国家 US").contains("属于").contains("满足");

    HitCondition sku = hitOf(detail, "R-SKU");
    assertThat(sku.actualField()).isEqualTo("sales_order_line.sku_code");
    assertThat(sku.actualValue()).isEqualTo("SKU-STD");
    assertThat(sku.explanation()).contains("SKU-LUXURY").contains("不满足");
  }

  @Test
  void 强制转人工压过priority更高的自动通过规则() {
    UUID orderId = order(a, spec("SO-2"));
    // priority 999 的自动通过 vs priority 1 的强制转人工 —— 强制位效力更高。
    ReviewRuleSaved approve = save(a, ruleInput("R-APPROVE", 999, ReviewAction.AUTO_APPROVE, false,
        T_EARLY, null,
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    ReviewRuleSaved forced = save(a, ruleInput("R-FORCED", 1, ReviewAction.MANUAL_REVIEW, true, T_EARLY,
        null, cond(1, ConditionDimension.BUYER_BLACKLIST, ConditionOperator.IS_TRUE, null, null)));
    activate(a, approve.id(), "自动通过");
    activate(a, forced.id(), "黑名单强制转人工");

    // ⚠️ 必须先把买家真的拉黑，否则强制规则命中不了，
    // 测到的就成了「没有规则命中 → 默认转人工」，而不是「强制位压过了自动通过」。
    asA(() -> service.saveBuyerProfile(new BuyerProfileInput(
        OrderReviewRuleService.buyerKey(RECIPIENT, PHONE, EMAIL), RECIPIENT, PHONE, EMAIL,
        "RISK", true, "同一收货地址短期内下单 14 次", null, "", null), actorA.membershipId()));

    var detail = review(a, orderId, T_MID);
    assertThat(detail.decision().decision()).as("priority=999 的自动通过压不住强制转人工")
        .isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(detail.decision().winningRuleCode()).isEqualTo("R-FORCED");
    assertThat(detail.decision().forcedRuleCount()).isEqualTo(1);
    assertThat(detail.hits().get(0).ruleCode()).as("次序仍按 priority，命中链顺序可复现")
        .isEqualTo("R-APPROVE");
    HitCondition bl = hitOf(detail, "R-FORCED");
    assertThat(bl.satisfied()).isTrue();
    assertThat(bl.actualValue()).isEqualTo("true");
    assertThat(bl.explanation()).contains("在黑名单里").contains("短期内下单 14 次");
  }

  @Test
  void 没有任何规则命中就转人工_不给隐含默认值() {
    UUID orderId = order(a, spec("SO-3"));
    ReviewRuleSaved de = save(a, ruleInput("R-DE-ONLY", 10, ReviewAction.AUTO_APPROVE, false, T_EARLY,
        null, cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("DE"), null)));
    activate(a, de.id(), "");
    var detail = review(a, orderId, T_MID);
    assertThat(detail.decision().decision())
        .as("自动通过必须由某条规则明确给出，没有规则命中就转人工")
        .isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(detail.decision().winningRuleId()).isNull();
    assertThat(detail.decision().matchedRuleCount()).isZero();
    assertThat(detail.decision().decisionReason()).contains("没有任何规则命中").contains("默认转人工");
    assertThat(detail.hits()).hasSize(1);
    assertThat(detail.hits().get(0).matched()).isFalse();
  }

  @Test
  void 零条生效规则时给安全的转人工结论而不是报错() {
    UUID orderId = order(a, spec("SO-4"));
    // 「该租户此刻没有生效规则」是真实运营状态，报 409 会让 9.2.1.1 待审队列整批卡住，
    // 而答案（转人工）是明确的、安全的。
    var detail = review(a, orderId, T_MID);
    assertThat(detail.decision().decision())
        .as("零条生效规则也不能默认全过").isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(detail.decision().consideredRuleCount()).isZero();
    assertThat(detail.decision().decisionReason())
        .as("「零条生效规则」与「有规则但都没命中」必须说成两句话")
        .contains("没有任何生效中的规则").doesNotContain("没有任何规则命中（参与判定");
    assertThat(detail.hits()).isEmpty();
    assertThat(detail.orderFacts().orderNo()).as("订单事实照样返回，客服仍能看到判的是哪张单")
        .isEqualTo("SO-4");
  }

  // ================================================================ 判定：生效期与时间

  @Test
  void 生效期真的参与判定_且断言写回的时刻值() {
    UUID orderId = order(a, spec("SO-5"));
    // 生效期：3/1 起、3/10 止。
    ReviewRuleSaved r = save(a, ruleInput("R-WINDOW", 10, ReviewAction.AUTO_APPROVE, false,
        OffsetDateTime.parse("2026-03-01T00:00:00+08:00"),
        OffsetDateTime.parse("2026-03-10T00:00:00+08:00"),
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    activate(a, r.id(), "");

    assertThat(review(a, orderId, OffsetDateTime.parse("2026-02-28T23:59:59+08:00")).decision().decision())
        .as("生效前：规则不参与判定 → 无规则命中 → 转人工")
        .isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(review(a, orderId, T_MID).decision().decision())
        .as("生效期内：自动通过").isEqualTo(ReviewDecisionType.AUTO_APPROVE);
    assertThat(review(a, orderId, T_LATE).decision().decision())
        .as("到期后：又变回转人工").isEqualTo(ReviewDecisionType.MANUAL_REVIEW);

    // ⚠️ 断言写回的时刻值，而不只是「没抛异常」：
    // timestamptz 绑定错时区（Timestamp.valueOf(LocalDateTime)）会静默偏 −28800s。
    var persisted = asA(() -> service.pageEvaluations(
        new EvaluationQuery(null, orderId, null, null, null, 0, 50)));
    assertThat(persisted.items()).hasSize(3);
    var mid = persisted.items().stream()
        .filter(d -> d.evaluatedAt().equals(T_MID)).findFirst().orElseThrow();
    assertThat(mid.evaluatedAt()).as("判定时刻原样写回（+08:00 偏移必须保住）").isEqualTo(T_MID);
    assertThat(mid.evaluatedAt().getOffset()).isEqualTo(T_MID.getOffset());

    var window = asA(() -> service.rule(r.id())).rule();
    assertThat(window.effectiveFrom()).isEqualTo(OffsetDateTime.parse("2026-03-01T00:00:00+08:00"));
    assertThat(window.effectiveTo()).isEqualTo(OffsetDateTime.parse("2026-03-10T00:00:00+08:00"));
  }

  @Test
  void 生效止必须晚于生效起() {
    assertThatThrownBy(() -> save(a, ruleInput("R-BADWIN", 10, ReviewAction.AUTO_APPROVE, false,
        T_MID, T_EARLY, cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null))))
        .as("配了却永远不生效是最难查的一类故障，必须在入口挡下")
        .hasMessageContaining("生效止必须晚于生效起");
  }

  // ================================================================ 判定：各维度

  @Test
  void 店铺维度按平台来源判定并如实标注是平台级() {
    UUID orderId = order(a, copy(spec("SO-6"), s -> new Spec(s.orderNo(), "SHOPIFY", s.unitPrice(),
        s.qty(), s.lineDiscount(), s.skuCode(), s.recipient(), s.phone(), s.email(), s.country(),
        s.company(), s.city(), s.district(), s.postcode(), s.line1())));
    ReviewRuleSaved r = save(a, ruleInput("R-SHOP", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY, null,
        cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(a, r.id(), "");
    var detail = review(a, orderId, T_MID);
    HitCondition c = hitOf(detail, "R-SHOP");
    assertThat(c.actualField()).isEqualTo("sales_order.order_source");
    assertThat(c.actualValue()).isEqualTo("SHOPIFY");
    assertThat(c.satisfied()).isFalse();
    assertThat(c.explanation()).contains("平台级").contains("D5");
  }

  @Test
  void 买家等级未登记时不兜底STANDARD_登记后命中() {
    UUID orderId = order(a, spec("SO-7"));
    ReviewRuleSaved vip = save(a, ruleInput("R-VIP", 10, ReviewAction.AUTO_APPROVE, false, T_EARLY, null,
        cond(1, ConditionDimension.BUYER_LEVEL, ConditionOperator.IN, List.of("VIP"), null)));
    activate(a, vip.id(), "VIP 自动通过");

    var before = review(a, orderId, T_MID);
    assertThat(hitOf(before, "R-VIP").satisfied())
        .as("未登记画像时等级是未知，不是 STANDARD —— 兜底会把全网买家都放过去")
        .isFalse();
    assertThat(hitOf(before, "R-VIP").actualValue()).isEmpty();
    assertThat(before.decision().decision()).isEqualTo(ReviewDecisionType.MANUAL_REVIEW);

    // 登记画像：身份键由订单地址快照现算，与服务层同公式。
    asA(() -> service.saveBuyerProfile(new BuyerProfileInput(
        OrderReviewRuleService.buyerKey(RECIPIENT, PHONE, EMAIL), RECIPIENT, PHONE, EMAIL,
        "VIP", false, "", null, "", null), actorA.membershipId()));

    var after = review(a, orderId, T_MID);
    assertThat(hitOf(after, "R-VIP").satisfied()).isTrue();
    assertThat(hitOf(after, "R-VIP").actualValue()).isEqualTo("VIP");
    assertThat(after.orderFacts().buyerKey())
        .as("判定详情回显的身份键与服务端现算的一致（界面就靠它登记画像）")
        .isEqualTo(OrderReviewRuleService.buyerKey(RECIPIENT, PHONE, EMAIL));
    assertThat(after.decision().decision()).isEqualTo(ReviewDecisionType.AUTO_APPROVE);
  }

  @Test
  void 黑名单命中时把理由写进人话解释() {
    UUID orderId = order(a, spec("SO-8"));
    ReviewRuleSaved bl = save(a, ruleInput("R-BL", 10, ReviewAction.MANUAL_REVIEW, true, T_EARLY, null,
        cond(1, ConditionDimension.BUYER_BLACKLIST, ConditionOperator.IS_TRUE, null, null)));
    activate(a, bl.id(), "黑名单强制转人工");

    assertThat(review(a, orderId, T_MID).decision().decision())
        .as("未登记画像时黑名单状态未知，不满足").isEqualTo(ReviewDecisionType.MANUAL_REVIEW);

    asA(() -> service.saveBuyerProfile(new BuyerProfileInput(
        OrderReviewRuleService.buyerKey(RECIPIENT, PHONE, EMAIL), RECIPIENT, PHONE, EMAIL,
        "RISK", true, "同一收货地址短期内下单 14 次且频繁改址", null, "", null),
        actorA.membershipId()));

    var detail = review(a, orderId, T_MID);
    HitCondition c = hitOf(detail, "R-BL");
    assertThat(c.satisfied()).isTrue();
    assertThat(c.actualValue()).isEqualTo("true");
    assertThat(c.explanation())
        .as("理由必须出现在解释里 —— 没有理由客服无法向买家解释")
        .contains("在黑名单里").contains("频繁改址");
    assertThat(detail.decision().decision()).isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(detail.decision().forcedRuleCount()).isEqualTo(1);
  }

  @Test
  void 拉黑必须写理由() {
    assertThatThrownBy(() -> asA(() -> service.saveBuyerProfile(new BuyerProfileInput(
        OrderReviewRuleService.buyerKey(RECIPIENT, PHONE, EMAIL), RECIPIENT, PHONE, EMAIL,
        "RISK", true, "   ", null, "", null), actorA.membershipId())))
        .hasMessageContaining("拉黑必须写理由");
  }

  @Test
  void 地址异常五种实算并可解释() {
    UUID bad = order(a, copy(spec("SO-9"), s -> new Spec(s.orderNo(), s.source(), s.unitPrice(),
        s.qty(), s.lineDiscount(), s.skuCode(), s.recipient(), "", s.email(), s.country(), "",
        "", "", "00000", "PO BOX 123")));
    ReviewRuleSaved anomalyRule = save(a, ruleInput("R-ANOM", 10, ReviewAction.MANUAL_REVIEW, true,
        T_EARLY, null, cond(1, ConditionDimension.ADDRESS_ANOMALY, ConditionOperator.IN,
            List.of("RESERVED_POSTCODE"), null)));
    var detail = asA(() -> service.trial(bad, new ReviewRunInput(T_MID, List.of(anomalyRule.id())),
        actorA.membershipId()));
    var anomalies = detail.decision().addressAnomalyCodes();
    // ⚠️ 这一张有邮编（00000），所以**没有** MISSING_POSTCODE ——
    // 「缺邮编」与「邮编是占位号」不可能同时成立，凑齐五个才是把断言写假了。
    assertThat(anomalies).containsExactlyInAnyOrder(
        "MISSING_PHONE", "PO_BOX_ONLY", "INCOMPLETE_ADDRESS", "RESERVED_POSTCODE");
    assertThat(anomalies).doesNotContain("MISSING_POSTCODE");
    assertThat(detail.orderFacts().addressAnomalyCodes()).isEqualTo(anomalies);

    // 另一张：完全没填邮编，检出 MISSING_POSTCODE。
    UUID noPostcode = order(a, copy(spec("SO-9C"), s -> new Spec(s.orderNo(), s.source(),
        s.unitPrice(), s.qty(), s.lineDiscount(), s.skuCode(), s.recipient(), s.phone(), s.email(),
        s.country(), s.company(), s.city(), s.district(), "", "100 Main St")));
    var noPc = asA(() -> service.trial(noPostcode, new ReviewRunInput(T_MID, List.of(anomalyRule.id())),
        actorA.membershipId()));
    assertThat(noPc.orderFacts().addressAnomalyCodes()).contains("MISSING_POSTCODE");
    assertThat(noPc.orderFacts().addressAnomalyCodes())
        .as("其余列都正常，就不该顺带报别的异常").doesNotContain("INCOMPLETE_ADDRESS");

    // 规则按异常码命中：命中明细里能说出是哪一种异常。
    activate(a, anomalyRule.id(), "");
    var d2 = review(a, bad, T_MID);
    HitCondition c = hitOf(d2, "R-ANOM");
    assertThat(c.satisfied()).isTrue();
    assertThat(c.actualValue()).contains("RESERVED_POSTCODE").contains("MISSING_PHONE");
    assertThat(c.explanation()).contains("检出 4 种异常").contains("满足");
    assertThat(d2.decision().decision()).isEqualTo(ReviewDecisionType.MANUAL_REVIEW);

    // 正常地址不出异常。
    UUID good = order(a, spec("SO-9B"));
    var g = trial(a, good, T_MID, List.of(anomalyRule.id()));
    assertThat(g.orderFacts().addressAnomalyCodes()).isEmpty();
  }

  @Test
  void 异常折扣_百分数口径_小计为零时是null不是0() {
    // 100 元小计、减 85 元 → 85%。
    UUID discounted = order(a, copy(spec("SO-10"), s -> new Spec(s.orderNo(), s.source(),
        s.unitPrice(), s.qty(), new BigDecimal("-85.0000"), s.skuCode(), s.recipient(), s.phone(),
        s.email(), s.country(), s.company(), s.city(), s.district(), s.postcode(), s.line1())));
    ReviewRuleSaved r = save(a, ruleInput("R-DISC", 10, ReviewAction.MANUAL_REVIEW, true, T_EARLY, null,
        cond(1, ConditionDimension.DISCOUNT_RATIO, ConditionOperator.GTE, null, new BigDecimal("80"))));
    activate(a, r.id(), "");

    var d = review(a, discounted, T_MID);
    assertThat(d.orderFacts().subtotalAmount()).isEqualByComparingTo("200.0000");
    assertThat(d.orderFacts().discountRatePercent())
        .as("85 / 200 = 42.5%，断言到刻度").isEqualByComparingTo("42.5000");
    HitCondition c = hitOf(d, "R-DISC");
    assertThat(c.satisfied()).isFalse();
    assertThat(c.explanation()).contains("42.5000%").contains("小计");

    // 折扣率阈值上限：超过 100% 的配置在入口就被挡。
    assertThatThrownBy(() -> save(a, ruleInput("R-DISC2", 10, ReviewAction.MANUAL_REVIEW, false,
        T_EARLY, null, cond(1, ConditionDimension.DISCOUNT_RATIO, ConditionOperator.GTE, null,
            new BigDecimal("120")))))
        .hasMessageContaining("折扣比例阈值最多 100");

    // 小计为 0：折扣率不可计算，是 null 而不是 0。
    UUID zero = order(a, copy(spec("SO-10B"), s -> new Spec(s.orderNo(), s.source(),
        new BigDecimal("0.0000"), s.qty(), BigDecimal.ZERO, s.skuCode(), s.recipient(), s.phone(),
        s.email(), s.country(), s.company(), s.city(), s.district(), s.postcode(), s.line1())));
    var dz = trial(a, zero, T_MID, List.of(r.id()));
    assertThat(dz.orderFacts().discountRatePercent())
        .as("0 会被读成「零元订单打了 0 折」，那是结论而不是缺口").isNull();
    HitCondition cz = hitOf(dz, "R-DISC");
    assertThat(cz.satisfied()).isFalse();
    assertThat(cz.actualValue()).as("未知时 actual_value 是空串").isEmpty();
    assertThat(cz.explanation()).contains("不可计算").contains("不是 0");
  }

  @Test
  void 金额刻度读回仍是numeric19_4() {
    UUID orderId = order(a, copy(spec("SO-11"), s -> new Spec(s.orderNo(), s.source(),
        new BigDecimal("33.3333"), new BigDecimal("3.000000"), BigDecimal.ZERO, s.skuCode(),
        s.recipient(), s.phone(), s.email(), s.country(), s.company(), s.city(), s.district(),
        s.postcode(), s.line1())));
    ReviewRuleSaved r = save(a, ruleInput("R-SCALE", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
        null, cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("100.0000"))));
    activate(a, r.id(), "");
    var d = review(a, orderId, T_MID);
    assertThat(d.orderFacts().orderTotal())
        .as("33.3333 × 3 = 99.9999，HALF_UP 到 4 位")
        .isEqualByComparingTo("99.9999");
    assertThat(d.orderFacts().orderTotal().scale()).as("刻度必须是 4").isEqualTo(4);
    assertThat(hitOf(d, "R-SCALE").actualValue()).isEqualTo("99.9999");
    assertThat(d.orderFacts().totalQty().scale()).as("数量刻度是 6").isEqualTo(6);
  }

  @Test
  void 商品维度按SKU命中并点名是哪个SKU() {
    UUID orderId = order(a, copy(spec("SO-12"), s -> new Spec(s.orderNo(), s.source(), s.unitPrice(),
        s.qty(), s.lineDiscount(), "SKU-LUXURY", s.recipient(), s.phone(), s.email(), s.country(),
        s.company(), s.city(), s.district(), s.postcode(), s.line1())));
    ReviewRuleSaved r = save(a, ruleInput("R-LUX", 10, ReviewAction.MANUAL_REVIEW, true, T_EARLY, null,
        cond(1, ConditionDimension.ITEM, ConditionOperator.IN, List.of("SKU-LUXURY"), null)));
    activate(a, r.id(), "");
    var d = review(a, orderId, T_MID);
    HitCondition c = hitOf(d, "R-LUX");
    assertThat(c.satisfied()).isTrue();
    assertThat(c.actualValue()).isEqualTo("SKU-LUXURY");
    assertThat(c.explanation()).contains("SKU-LUXURY").contains("满足");
    assertThat(d.decision().decision()).isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
  }

  // ================================================================ 试跑与留痕

  @Test
  void 试跑标成DRY_RUN_可点名未启用的草稿规则() throws Exception {
    UUID orderId = order(a, spec("SO-13"));
    // 草稿规则：不启用也能试跑 —— 这正是「测试数据」这个输入的用途。
    ReviewRuleSaved draft = save(a, ruleInput("R-DRAFT", 10, ReviewAction.AUTO_APPROVE, false, T_EARLY,
        null, cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));

    assertThat(review(a, orderId, T_MID).decision().decision())
        .as("草稿不参与生效判定：该时刻零条生效规则 → 转人工").isEqualTo(ReviewDecisionType.MANUAL_REVIEW);
    assertThat(review(a, orderId, T_MID).decision().decisionReason())
        .contains("没有任何生效中的规则");

    var trial = trial(a, orderId, T_MID, List.of(draft.id()));
    assertThat(trial.decision().mode()).isEqualTo(ReviewMode.DRY_RUN);
    assertThat(trial.decision().evaluatedScope()).isEqualTo("SELECTED");
    assertThat(trial.decision().decision()).isEqualTo(ReviewDecisionType.AUTO_APPROVE);

    // 判定留痕 append-only：**两道**闸，都要钉住。
    UUID hitId = asA(() -> sql
        .fetchOne("select id from order_review_rule_hit where tenant_id = ? limit 1", a)
        .get(0, UUID.class));
    // 第一道：REVOKE —— 运行角色 trade_app 根本没有 UPDATE 权限。
    // ⚠️ PG 的拒绝原因在**根因**里，Spring 抛的那层只有 SQL 串 —— 断言外层消息会假绿。
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> asA(() -> {
          sql.execute("update order_review_rule_hit set reason='改成放行了' where tenant_id=? and id=?",
              a, hitId);
          return null;
        }))
        .as("运行角色 trade_app 根本没有 UPDATE 权限（REVOKE）")
        .rootCause()
        .hasMessageContaining("permission denied for table order_review_rule_hit");
    // 第二道：触发器 —— 即使用表 owner（迁移角色）绕过权限，触发器照样挡下。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.prepareStatement(
            "update order_review_rule_hit set reason='改成放行了' where id=?")) {
      st.setObject(1, hitId);
      java.sql.SQLException err = null;
      try {
        st.executeUpdate();
      } catch (java.sql.SQLException e) {
        err = e;
      }
      assertThat(err == null ? "触发器没有拒绝这次改写" : err.getMessage())
          .as("表 owner 也必须被触发器挡下：判定留痕是客服向买家解释的依据")
          .contains("不可改写的判定留痕表");
    }
  }

  @Test
  void 判定记录列表是真分页且汇总额不受分页影响() {
    for (int i = 1; i <= 7; i++) {
      UUID oid = order(a, spec("SO-P" + i));
      ReviewRuleSaved r = save(a, ruleInput("R-P" + i, 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
          null, cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN,
              List.of(i % 2 == 0 ? "US" : "DE"), null)));
      activate(a, r.id(), "");
      review(a, oid, T_MID);
    }
    var page = asA(() -> service.pageEvaluations(
        new EvaluationQuery(null, null, null, null, null, 0, 3)));
    assertThat(page.items()).hasSize(3);
    assertThat(page.total()).isEqualTo(7);
    assertThat(page.autoApproveCount() + page.manualReviewCount()).isEqualTo(7);
    var page2 = asA(() -> service.pageEvaluations(
        new EvaluationQuery(null, null, null, null, null, 1, 3)));
    assertThat(page2.items()).hasSize(3);
    assertThat(page2.items()).doesNotContainAnyElementsOf(page.items());
    assertThat(page2.total()).as("总条数不受分页影响").isEqualTo(7);

    // 订单号模糊搜索走真 SQL（联查 sales_order 拿 external_order_no）。
    var found = asA(() -> service.pageEvaluations(
        new EvaluationQuery("SO-P3", null, null, null, null, 0, 50)));
    assertThat(found.total()).isEqualTo(1);
    assertThat(found.items().get(0).orderNo()).isEqualTo("SO-P3");
  }

  @Test
  void 规则列表筛选是真SQL且汇总额不受分页影响() {
    ReviewRuleSaved r1 = save(a, ruleInput("R-F-1", 100, ReviewAction.MANUAL_REVIEW, true, T_EARLY, null,
        cond(1, ConditionDimension.BUYER_BLACKLIST, ConditionOperator.IS_TRUE, null, null)));
    ReviewRuleSaved r2 = save(a, ruleInput("R-F-2", 50, ReviewAction.AUTO_APPROVE, false, T_EARLY, null,
        cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("10"))));
    activate(a, r1.id(), "");
    activate(a, r2.id(), "");

    var forced = asA(() -> service.pageRules(
        new ReviewRuleQuery(null, null, null, true, null, false, 0, 1)));
    assertThat(forced.items()).hasSize(1);
    assertThat(forced.total()).isEqualTo(1);
    assertThat(forced.forcedCount()).isEqualTo(1);
    assertThat(forced.activeCount()).isEqualTo(2);

    var byDim = asA(() -> service.pageRules(
        new ReviewRuleQuery(null, null, null, null, "AMOUNT", false, 0, 50)));
    assertThat(byDim.total()).as("维度筛选走 exists，不会因多条条件重复").isEqualTo(1);
    assertThat(byDim.items().get(0).ruleCode()).isEqualTo("R-F-2");

    var kw = asA(() -> service.pageRules(new ReviewRuleQuery("R-F-1", null, null, null, null, false, 0, 50)));
    assertThat(kw.total()).isEqualTo(1);
  }

  @Test
  void 买家画像分页与黑名单筛选() {
    for (int i = 1; i <= 3; i++) {
      final int n = i;
      final String name = "买家" + n;
      final String phone = "+1-000-000-000" + n;
      final String email = "buyer" + n + "@example.com";
      final String key = OrderReviewRuleService.buyerKey(name, phone, email);
      asA(() -> service.saveBuyerProfile(new BuyerProfileInput(key, name, phone, email,
          n == 1 ? "VIP" : "STANDARD", n == 1, n == 1 ? "重复欺诈下单" : "", null, "", null),
          actorA.membershipId()));
    }
    var all = asA(() -> service.pageBuyerProfiles(new BuyerProfileQuery(null, null, null, 0, 2)));
    assertThat(all.items()).hasSize(2);
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.blacklistedCount()).isEqualTo(1);
    assertThat(all.items().get(0).blacklisted()).as("黑名单排最前").isTrue();

    var bl = asA(() -> service.pageBuyerProfiles(new BuyerProfileQuery(null, null, true, 0, 50)));
    assertThat(bl.total()).isEqualTo(1);
    var vip = asA(() -> service.pageBuyerProfiles(new BuyerProfileQuery(null, "VIP", null, 0, 50)));
    assertThat(vip.total()).isEqualTo(1);
    var kw = asA(() -> service.pageBuyerProfiles(new BuyerProfileQuery("buyer2", null, null, 0, 50)));
    assertThat(kw.total()).isEqualTo(1);
  }

  // ================================================================ HTTP 边界

  @Test
  void HTTP端点全通_且未交付形状真的404() throws Exception {
    ReviewRuleSaved r = save(a, ruleInput("R-HTTP", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
        null, cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(a, r.id(), "");
    UUID oid = order(a, spec("SO-HTTP"));

    http.perform(get("/trade/order-review/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/order-review/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/order-review/rules/{id}", r.id()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/order-review/buyer-profiles").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/order-review/evaluations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(post("/trade/order-review/orders/{id}/review", oid)
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"evaluatedAt\":\"2026-03-05T16:30:45+08:00\"}"))
        .andExpect(status().isOk());
    http.perform(post("/trade/order-review/orders/{id}/trial", oid)
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"evaluatedAt\":\"2026-03-05T16:30:45+08:00\"}"))
        .andExpect(status().isOk());

    // 未认证：401。
    http.perform(get("/trade/order-review/rules")).andExpect(status().isUnauthorized());

    // 只读身份不能改规则。
    http.perform(post("/trade/order-review/rules/{id}/status", r.id())
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DISABLED\",\"note\":\"x\"}"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 多租户三断言

  // 断言 1：两租户互不可见
  @Test
  void 断言1_两租户互不可见() {
    ReviewRuleSaved ra = save(a, ruleInput("R-ISO-A", 10, ReviewAction.MANUAL_REVIEW, true, T_EARLY, null,
        cond(1, ConditionDimension.AMOUNT, ConditionOperator.GTE, null, new BigDecimal("1.0000"))));
    activate(a, ra.id(), "");
    UUID oa = order(a, spec("SO-ISO-A"));
    review(a, oa, T_MID);

    ReviewRuleSaved rb = save(b, ruleInput("R-ISO-B", 10, ReviewAction.AUTO_APPROVE, false, T_EARLY, null,
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    activate(b, rb.id(), "");
    UUID ob = order(b, spec("SO-ISO-B"));
    review(b, ob, T_MID);

    var listA = asA(() -> service.pageRules(new ReviewRuleQuery(null, null, null, null, null, false, 0, 50)));
    assertThat(listA.items()).extracting(ReviewRuleRow::ruleCode).containsExactly("R-ISO-A");
    var listB = asB(() -> service.pageRules(new ReviewRuleQuery(null, null, null, null, null, false, 0, 50)));
    assertThat(listB.items()).extracting(ReviewRuleRow::ruleCode).containsExactly("R-ISO-B");

    assertThatThrownBy(() -> asB(() -> service.rule(ra.id())))
        .as("跨租户按 id 取规则 → 404").hasMessageContaining("不存在");
    assertThatThrownBy(() -> asA(() -> service.rule(rb.id()))).hasMessageContaining("不存在");

    var evalA = asA(() -> service.pageEvaluations(new EvaluationQuery(null, null, null, null, null, 0, 50)));
    assertThat(evalA.items()).extracting(ReviewDecision::orderNo).containsExactly("SO-ISO-A");
    var evalB = asB(() -> service.pageEvaluations(new EvaluationQuery(null, null, null, null, null, 0, 50)));
    assertThat(evalB.items()).extracting(ReviewDecision::orderNo).containsExactly("SO-ISO-B");

    // ⚠️ 命中明细里带着买家邮箱与地址异常：漏掉租户谓词就是带 PII 的跨租户泄露。
    // 取 id 也要在租户事务里读：裸 DSLContext 不带 app.current_tenant，
    // FORCE RLS 会正确地让它一行都读不到。
    UUID aEvaluationId = asA(() -> sql
        .fetchOne("select id from order_review_rule_evaluation where tenant_id=? limit 1", a)
        .get(0, UUID.class));
    assertThatThrownBy(() -> asB(() -> service.evaluation(aEvaluationId)))
        .hasMessageContaining("不存在");
  }

  @Test
  void 断言1b_跨租户不可判定() {
    ReviewRuleSaved ra = save(a, ruleInput("R-X-1", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY, null,
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    activate(a, ra.id(), "");
    ReviewRuleSaved rb = save(b, ruleInput("R-X-2", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY, null,
        cond(1, ConditionDimension.SHIPPING_COUNTRY, ConditionOperator.IN, List.of("US"), null)));
    activate(b, rb.id(), "");
    UUID ob = order(b, spec("SO-X-B"));
    assertThatThrownBy(() -> review(a, ob, T_MID))
        .as("A 的会话不得判定 B 的订单")
        .hasMessageContaining("不存在");
    // B 的规则也不得在 A 的判定里参与。
    assertThatThrownBy(() -> trial(a, ob, T_MID, List.of(rb.id())))
        .as("点名的跨租户规则查不到")
        .hasMessageContaining("不存在");
  }

  // 断言 2：伪造租户上下文无效
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    ReviewRuleSaved ra = save(a, ruleInput("R-FORGED", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
        null, cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(a, ra.id(), "");
    UUID oa = order(a, spec("SO-FORGED"));
    review(a, oa, T_MID);

    // (1) DB 层：没有 app.current_tenant 上下文时 FORCE RLS 什么都看不到。
    assertThat(sql.fetchOne("select count(*) c from order_review_rule").get("c", Integer.class))
        .as("RLS 之外无租户上下文 = 一行都读不到").isZero();
    assertThat(sql.fetchOne("select count(*) c from order_review_rule_evaluation").get("c", Integer.class))
        .isZero();

    // (2) HTTP 边界：合法会话 + 伪造 X-Tenant-Id → 仍然只看得到会话租户的数据。
    String body = http.perform(get("/trade/order-review/rules")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("R-FORGED").doesNotContain("R-ISO-B");
  }

  // 断言 3：连接池归还后不串租户（单连接）
  @Test
  void 断言3_连接池归还后不串租户() {
    ReviewRuleSaved ra = save(a, ruleInput("R-POOL-A", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
        null, cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(a, ra.id(), "");
    UUID oa = order(a, spec("SO-POOL-A"));
    // B 也要有一条真实规则：断言「A 读不到 B 的规则」需要 B 真的有规则可读，
    // 否则测到的只是「B 没有数据」。
    ReviewRuleSaved rb = save(b, ruleInput("R-POOL-B", 10, ReviewAction.MANUAL_REVIEW, false, T_EARLY,
        null, cond(1, ConditionDimension.SHOP, ConditionOperator.IN, List.of("AMAZON"), null)));
    activate(b, rb.id(), "");

    // 交替跑 10 轮：连接池只有 1 条连接，A/B 交替必然复用同一条，
    // 只要有一次串租户（读到上一个人的租户）就会被下面这组断言抓住。
    for (int i = 0; i < 10; i++) {
      final int round = i;
      var la = asA(() -> service.pageRules(new ReviewRuleQuery(null, null, null, null, null, false, 0, 50)));
      assertThat(la.items()).extracting(ReviewRuleRow::ruleCode).containsExactly("R-POOL-A");
      var lb = asB(() -> service.pageRules(new ReviewRuleQuery(null, null, null, null, null, false, 0, 50)));
      assertThat(lb.items()).as("第 %d 轮：B 只看得到自己的规则", round)
          .extracting(ReviewRuleRow::ruleCode).containsExactly("R-POOL-B");
      assertThatThrownBy(() -> asA(() -> service.rule(rb.id())))
          .as("第 %d 轮：A 的会话读不到 B 的规则", round).hasMessageContaining("不存在");
    }
    // A 自己仍然能判定（证明上面的「B 看不到」不是因为数据丢了）。
    assertThat(review(a, oa, T_MID).decision().orderNo()).isEqualTo("SO-POOL-A");
  }
}
