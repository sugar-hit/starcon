package com.starcon.channel;

import static com.starcon.channel.BuyerRiskScoreModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.5.1.2 风险评分。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 评分要能解释</b>：逐特征明细齐全，<b>各特征贡献分之和恰好等于总分</b>。
 *   <li><b>🔴 缺特征不进分母</b>：「纠纷率」量不到 ⇒ 不参与加权、贡献分是 null，
 *       且总分等于「只用已知维度归一化」的结果（不是被稀释过的数）。
 *   <li><b>🔴 量不到 ≠ 0</b>：没有买家画像 ⇒ 欺诈特征量不到（不是 0 分）；
 *       退款读不到 ⇒ 退款率量不到（不是「退款率 0」）。
 *   <li><b>🔴 缺信号口径由租户选</b>：BLOCK_EVALUATION 下不出分，只给「建议人工」。
 *   <li><b>🔴 权重合计钉死 1.0000</b>，缺信号口径不给默认值。
 *   <li><b>🔴 回写不阻塞交付</b>：回写成功 → trade 收到 REFUND_RATE；回写失败 → 评分照常出分，
 *       凭据里写明「未回写」；量不到的 DISPUTE_RATE 一律 SKIPPED。
 *   <li><b>🔴 判定凭据 append-only</b>：直接改分数会被 DB 触发器拒。
 *   <li><b>🔴 关联账号 / 黑名单不编造</b>：能力声明里 available=false + ownerModule。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerRiskScoreIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static BuyerRiskTradeStub trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
      trade = new BuyerRiskTradeStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    trade.reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", trade::base);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired BuyerRiskScoreService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  void execAsTenant(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 评分规则（默认：纠纷率给 0.2 权重，其余按 0.4/0.2/0.1/0.1）。 */
  BuyerRiskRuleInput rule(String policy) {
    return new BuyerRiskRuleInput(
        "测试规则",
        new BigDecimal("0.4000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.1000"),
        new BigDecimal("0.1000"),
        policy,
        new BigDecimal("40.0000"),
        new BigDecimal("70.0000"),
        new BigDecimal("0.3000"),
        new BigDecimal("1.0000"),
        new BigDecimal("0.6000"),
        new BigDecimal("0.2000"),
        new BigDecimal("0.5000"),
        24,
        3,
        new BigDecimal("8.0000"),
        180,
        null);
  }

  BuyerRiskRuleView saveRule(String policy) {
    return as("channel.operation.update", () -> service.saveRule(rule(policy)));
  }

  BuyerRiskEvaluateResult evaluate() {
    return as("channel.operation.create", () -> service.evaluate(bearer, new BuyerRiskEvaluateInput(null)));
  }

  BuyerRiskFeatureView feature(BuyerRiskScoreView score, String dimensionCode) {
    return score.features().stream()
        .filter(f -> f.dimensionCode().equals(dimensionCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("评分里没有维度 " + dimensionCode));
  }

  /** 造一个买家：姓名 / 电话 / 邮箱 → buyer_key（与生产同一公式）。 */
  static String key(String name, String phone, String email) {
    return BuyerRiskScoreSourcePort.buyerKey(name, phone, email);
  }

  /** 造四张订单：前两张同一个地址，后两张不同地址；其中一张没有邮编。 */
  void seedAlice() {
    String name = "Alice Smith";
    String phone = "+1-555-0100";
    String email = "alice@example.com";
    String key = key(name, phone, email);
    trade.putProfile(key, name, phone, email, "STANDARD", false, "");

    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(5);
    String o1 = UUID.randomUUID().toString();
    String o2 = UUID.randomUUID().toString();
    String o3 = UUID.randomUUID().toString();
    String o4 = UUID.randomUUID().toString();
    trade.putOrder(o1, "SO-001", "AMAZON", base);
    trade.putOrder(o2, "SO-002", "AMAZON", base.plusDays(1));
    trade.putOrder(o3, "SO-003", "SHOPIFY", base.plusDays(3));
    trade.putOrder(o4, "SO-004", "SHOPIFY", base.plusDays(9));
    trade.putShippingAddress(o1, name, phone, email, "US", "10001", "New York", "Manhattan", "1 Main St");
    trade.putShippingAddress(o2, name, phone, email, "US", "10001", "New York", "Manhattan", "1 Main St");
    trade.putShippingAddress(o3, name, phone, email, "US", "10001", "New York", "Manhattan", "2 Main St");
    trade.putShippingAddress(o4, name, phone, email, "US", "", "New York", "Manhattan", "3 Main St");
    trade.putRefund(o1);
  }

  // ==================================================================
  // 用例
  // ==================================================================

  @Test
  void 能力声明逐维给出可用性与归属模块() {
    BuyerRiskCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    BuyerRiskDimensionCapability dispute =
        caps.dimensions().stream()
            .filter(d -> d.dimensionCode().equals("DISPUTE_HISTORY"))
            .findFirst()
            .orElseThrow();
    assertThat(dispute.available()).isFalse();
    assertThat(dispute.ownerModule()).isEqualTo("11.4.1.1");
    assertThat(dispute.impact()).contains("platform_order_no");

    BuyerRiskDimensionCapability related =
        caps.dimensions().stream()
            .filter(d -> d.dimensionCode().equals("RELATED_ACCOUNTS"))
            .findFirst()
            .orElseThrow();
    assertThat(related.available()).isFalse();
    assertThat(related.ownerModule()).isEqualTo("16.5.2.2");

    // 🔴 主体键口径与「为什么选 buyer_key」必须写在能力声明里。
    assertThat(caps.subjectKeySemantics()).contains("buyer_key");
    assertThat(caps.identityChoiceNote()).contains("customer_party_id");
    assertThat(caps.explainSemantics()).contains("解释");
  }

  @Test
  void 没登记规则时不出分也不给默认权重() {
    BuyerRiskRuleView rule = as("channel.operation.read", () -> service.rule());
    assertThat(rule.configured()).isFalse();
    assertThat(rule.weightRefundHistory()).isNull();

    ResponseStatusException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class,
            () -> as("channel.operation.create", () -> service.evaluate(bearer, new BuyerRiskEvaluateInput(null))));
    assertThat(e.getStatusCode().value()).isEqualTo(422);
  }

  @Test
  void 权重合计不是一则拒绝登记() {
    BuyerRiskRuleInput bad =
        new BuyerRiskRuleInput(
            "坏规则",
            new BigDecimal("0.5000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.1000"),
            new BigDecimal("0.1000"),
            "SCORE_KNOWN_ONLY",
            new BigDecimal("40.0000"),
            new BigDecimal("70.0000"),
            new BigDecimal("0.3000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.6000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.5000"),
            24,
            3,
            new BigDecimal("8.0000"),
            180,
            null);
    ResponseStatusException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> as("channel.operation.update", () -> service.saveRule(bad)));
    assertThat(e.getStatusCode().value()).isEqualTo(422);
    assertThat(e.getMessage()).contains("1.0000");
  }

  @Test
  void 评分能解释且贡献分之和恰好等于总分() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();

    BuyerRiskEvaluateResult result = evaluate();
    assertThat(result.scores()).hasSize(1);
    BuyerRiskScoreView score = result.scores().get(0);

    assertThat(score.riskScore()).isNotNull();
    assertThat(score.evaluationStatus()).isEqualTo("SCORED");
    assertThat(score.recommendedAction()).isIn("RELEASE", "MANUAL_REVIEW", "HOLD");

    // 🔴 每个维度都有一行明细，且**人话理由必填**。
    assertThat(score.features()).hasSize(6);
    for (BuyerRiskFeatureView f : score.features()) {
      assertThat(f.reason()).isNotBlank();
      assertThat(f.featureCode()).isNotBlank();
    }

    // 🔴 贡献分之和 = 总分（逐字相等，不是近似）。
    BigDecimal sum = BigDecimal.ZERO;
    for (BuyerRiskFeatureView f : score.features()) {
      if (f.contribution() != null) sum = sum.add(f.contribution());
    }
    assertThat(sum).isEqualByComparingTo(score.riskScore());

    // 🔴 缺特征不进分母：只用「量到的维度」归一化，重算一遍必须得到同一个总分。
    BigDecimal knownWeight = BigDecimal.ZERO;
    BigDecimal weighted = BigDecimal.ZERO;
    for (BuyerRiskFeatureView f : score.features()) {
      if (!f.available()) {
        assertThat(f.normalizedValue()).isNull();
        assertThat(f.contribution()).isNull();
        continue;
      }
      knownWeight = knownWeight.add(f.weight());
      weighted = weighted.add(f.normalizedValue().multiply(f.weight()));
    }
    assertThat(knownWeight).isEqualByComparingTo(new BigDecimal("0.8000"));
    BigDecimal expected =
        weighted.multiply(BigDecimal.valueOf(100)).divide(knownWeight, 4, java.math.RoundingMode.HALF_UP);
    assertThat(score.riskScore()).isEqualByComparingTo(expected);
  }

  @Test
  void 纠纷率量不到不参与加权且显式写明未计入() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();

    BuyerRiskScoreView score = evaluate().scores().get(0);
    BuyerRiskFeatureView dispute = feature(score, "DISPUTE_HISTORY");

    assertThat(dispute.available()).isFalse();
    assertThat(dispute.normalizedValue()).isNull();
    assertThat(dispute.contribution()).isNull();
    assertThat(dispute.ownerModule()).isEqualTo("11.4.1.1");
    assertThat(dispute.reason()).contains("platform_order_no");

    // 🔴 「纠纷率未计入」必须在**评分结论**里说清楚，不能只在特征行里。
    assertThat(score.conclusion()).contains("量不到");
  }

  @Test
  void 没有买家画像时欺诈特征量不到而不是零分() {
    saveRule("SCORE_KNOWN_ONLY");
    // 只造订单，不登记画像。
    String name = "Bob Jones";
    String phone = "+1-555-0200";
    String email = "bob@example.com";
    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
    String o1 = UUID.randomUUID().toString();
    trade.putOrder(o1, "SO-B1", "AMAZON", base);
    trade.putShippingAddress(o1, name, phone, email, "US", "94105", "San Francisco", "", "500 Market St");

    BuyerRiskScoreView score = evaluate().scores().get(0);
    BuyerRiskFeatureView fraud = feature(score, "FRAUD_PROFILE");

    assertThat(fraud.available()).isFalse();
    assertThat(fraud.normalizedValue()).isNull();
    assertThat(fraud.contribution()).isNull();
    assertThat(fraud.ownerModule()).isEqualTo("9.2.1.2");
    assertThat(fraud.reason()).contains("没有画像不等于干净买家");
  }

  @Test
  void 退款数据读不到时退款率量不到而不是零() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    trade.refundsForbidden = true;

    BuyerRiskScoreView score = evaluate().scores().get(0);
    BuyerRiskFeatureView refund = feature(score, "REFUND_HISTORY");

    assertThat(refund.available()).isFalse();
    assertThat(refund.normalizedValue()).isNull();
    assertThat(refund.contribution()).isNull();
    assertThat(refund.reason()).contains("没有拿 0 冒充");
  }

  @Test
  void 缺信号口径选阻断时不出分只给建议人工() {
    saveRule("BLOCK_EVALUATION");
    seedAlice();

    BuyerRiskScoreView score = evaluate().scores().get(0);
    assertThat(score.riskScore()).isNull();
    assertThat(score.evaluationStatus()).isEqualTo("INSUFFICIENT_SIGNALS");
    assertThat(score.recommendedAction()).isEqualTo("MANUAL_REVIEW");
    assertThat(score.missingDimensionCount()).isGreaterThan(0);
    assertThat(score.conclusion()).contains("信号不足");
  }

  @Test
  void 退款率按订单两级关联算出来() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();

    BuyerRiskScoreView score = evaluate().scores().get(0);
    BuyerRiskFeatureView refund = feature(score, "REFUND_HISTORY");

    assertThat(refund.available()).isTrue();
    assertThat(score.orderCount()).isEqualTo(4);
    assertThat(score.refundedOrderCount()).isEqualTo(1);
    assertThat(refund.rawValue()).contains("refunded=1");
    assertThat(refund.reason()).contains("25.00%");
  }

  @Test
  void 回写成功时trade收到退款率信号且纠纷率被跳过() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();

    BuyerRiskScoreView score = evaluate().scores().get(0);

    assertThat(trade.receivedSignals).hasSize(1);
    Map<String, Object> signal = trade.receivedSignals.get(0);
    assertThat(signal.get("signalCode")).isEqualTo("REFUND_RATE");
    assertThat(signal.get("subjectKind")).isEqualTo("BUYER_FINGERPRINT");
    assertThat(signal.get("signalSource")).isEqualTo("TENANT_MAINTAINED");
    assertThat(signal.get("subjectKey")).isEqualTo(score.buyerKey());

    // 🔴 纠纷率量不到 ⇒ 不回写（SKIPPED），而且**没有拿 0 冒充信号值**。
    BuyerRiskWritebackView dispute =
        score.writebacks().stream()
            .filter(w -> w.signalCode().equals("DISPUTE_RATE"))
            .findFirst()
            .orElseThrow();
    assertThat(dispute.writebackStatus()).isEqualTo("SKIPPED");
    assertThat(dispute.measuredValue()).isNull();
  }

  @Test
  void 回写失败不阻塞评分且凭据写明未回写() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    trade.signalForbidden = true;

    BuyerRiskScoreView score = evaluate().scores().get(0);

    // 🔴 评分照常出分。
    assertThat(score.riskScore()).isNotNull();
    BuyerRiskWritebackView refund =
        score.writebacks().stream()
            .filter(w -> w.signalCode().equals("REFUND_RATE"))
            .findFirst()
            .orElseThrow();
    assertThat(refund.writebackStatus()).isEqualTo("FAILED");
    assertThat(refund.writebackNote()).contains("未回写");
  }

  @Test
  void 评分是判定凭据改不动() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    BuyerRiskScoreView score = evaluate().scores().get(0);

    // 🔴 直接改分数被 DB 拒（append-only 触发器）。
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            execAsTenant(
                "update buyer_risk_score set risk_score = 1.0000 where id = '" + score.scoreId() + "'"));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            execAsTenant("delete from buyer_risk_feature where score_id = '" + score.scoreId() + "'"));
  }

  @Test
  void 权重改了不追溯改变历史评分() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    BuyerRiskScoreView first = evaluate().scores().get(0);

    // 改权重（把退款权重调低、地址权重调高）。
    BuyerRiskRuleInput changed =
        new BuyerRiskRuleInput(
            "测试规则 v2",
            new BigDecimal("0.1000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.4000"),
            new BigDecimal("0.1000"),
            "SCORE_KNOWN_ONLY",
            new BigDecimal("40.0000"),
            new BigDecimal("70.0000"),
            new BigDecimal("0.3000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.6000"),
            new BigDecimal("0.2000"),
            new BigDecimal("0.5000"),
            24,
            3,
            new BigDecimal("8.0000"),
            180,
            null);
    as("channel.operation.update", () -> service.saveRule(changed));

    // 🔴 旧评分原样留着（分数、规则版本、权重快照都不变）。
    BuyerRiskScoreView reread = as("channel.operation.read", () -> service.score(first.scoreId().toString()));
    assertThat(reread.riskScore()).isEqualByComparingTo(first.riskScore());
    assertThat(reread.ruleVersion()).isEqualTo(first.ruleVersion());
    assertThat(reread.ruleName()).isEqualTo("测试规则");
    for (BuyerRiskFeatureView f : reread.features()) {
      assertThat(f.weight()).isEqualByComparingTo(first.features().stream()
          .filter(g -> g.dimensionCode().equals(f.dimensionCode()))
          .findFirst()
          .orElseThrow()
          .weight());
    }
  }

  @Test
  void 评分历史是真分页且时间戳逐字往返() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    evaluate();

    BuyerRiskScorePage page = as("channel.operation.read", () -> service.scores(null, null, null, 0, 10));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.counts().scored()).isEqualTo(1);

    BuyerRiskScoreSummary summary = page.items().get(0);
    assertThat(summary.riskScore()).isNotNull();
    assertThat(summary.evaluatedReferenceAt()).isNotNull();

    // 🔴 timestamptz 往返：写回的值与读出的值**逐字相等**（不偏 −28800 秒）。
    //   断言的是**时刻本身**，不是格式化后的字符串 —— ::text 的渲染随会话时区变，
    //   拿字符串比会得到一个与漂移无关的假失败。
    OffsetDateTime stored =
        as(
            "channel.operation.read",
            () ->
                sql.fetchOne(
                        "select evaluated_reference_at from buyer_risk_score where id = ?",
                        summary.scoreId())
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant())
        .as("判定时刻必须原样写回（偏 −28800 秒即失败）")
        .isEqualTo(summary.evaluatedReferenceAt().toInstant());
    // 再钉一句「它确实是刚刚那一刻」：漂 8 小时的写法在这里必炸。
    assertThat(Duration.between(stored.toInstant(), OffsetDateTime.now(ZoneOffset.UTC).toInstant()).abs())
        .as("判定时刻必须落在当下附近（漂 −28800 秒即失败）")
        .isLessThan(Duration.ofMinutes(5));
  }

  @Test
  void http面上有鉴权与不存在() throws Exception {
    http.perform(get("/channel/buyer-risk-scores/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/buyer-risk-scores/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(6));

    http.perform(get("/channel/buyer-risk-scores/scores/not-a-uuid").header("Authorization", bearer))
        .andExpect(status().isNotFound());
  }

  @Test
  void 买家名册读不到上游时显式说读不到() {
    trade.profilesForbidden = true;
    BuyerRiskBuyerPage page = as("channel.operation.read", () -> service.buyers(bearer, null, 0, 50));
    assertThat(page.available()).isFalse();
    assertThat(page.note()).contains("无权");
    assertThat(page.items()).isEmpty();
  }

  @Test
  void 买家名册带最近一次评分() {
    saveRule("SCORE_KNOWN_ONLY");
    seedAlice();
    evaluate();

    BuyerRiskBuyerPage page = as("channel.operation.read", () -> service.buyers(bearer, null, 0, 50));
    assertThat(page.available()).isTrue();
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).latestRiskScore()).isNotNull();
    assertThat(page.items().get(0).buyerName()).isEqualTo("Alice Smith");
  }
}
