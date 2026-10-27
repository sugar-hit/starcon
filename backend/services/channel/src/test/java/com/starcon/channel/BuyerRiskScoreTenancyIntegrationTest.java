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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.5.1.2 风险评分 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（评分 / 特征明细 / 回写凭据都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerRiskScoreTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static BuyerRiskTradeStub trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
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
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired BuyerRiskScoreService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  BuyerRiskRuleInput rule() {
    return new BuyerRiskRuleInput(
        "多租户测试规则",
        new BigDecimal("0.4000"),
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
  }

  BuyerRiskEvaluateResult evaluate(AccessService.Access who, String bearer) {
    return as(who, "channel.operation.create", () -> service.evaluate(bearer, new BuyerRiskEvaluateInput(null)));
  }

  /** 造一个买家 + 一张订单（两个租户各自一份同名不同 key 的买家）。 */
  void seed(String name, String phone, String email) {
    String key = BuyerRiskScoreSourcePort.buyerKey(name, phone, email);
    trade.putProfile(key, name, phone, email, "STANDARD", false, "");
    String orderId = UUID.randomUUID().toString();
    trade.putOrder(orderId, "SO-" + name, "AMAZON", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    trade.putShippingAddress(orderId, name, phone, email, "US", "10001", "New York", "Manhattan", "1 Main St");
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.5.1.2 断言一：两租户互不可见（评分 / 特征明细 / 回写凭据都不可见）")
  void tenantsAreIsolated() {
    seed("Alice Smith", "+1-555-0100", "alice@example.com");
    as(actorA, "channel.operation.update", () -> service.saveRule(rule()));
    as(actorB, "channel.operation.update", () -> service.saveRule(rule()));
    BuyerRiskScoreView scoreA = evaluate(actorA, bearerA).scores().get(0);
    evaluate(actorB, bearerB);

    // B 读不到 A 的评分
    assertThat(as(actorB, "channel.operation.read", () -> service.scores(null, null, null, 0, 50)).items())
        .as("B 的租户上下文里读不到 A 的评分")
        .extracting(BuyerRiskScoreSummary::scoreId)
        .doesNotContain(scoreA.scoreId());
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.score(scoreA.scoreId().toString()))))
        .as("B 按 id 直取 A 的评分必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from buyer_risk_score where id='"
            + scoreA.scoreId() + "'"))
        .as("B 的上下文里直查 A 的评分必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_risk_feature where score_id='"
            + scoreA.scoreId() + "'"))
        .as("B 的上下文里直查 A 的特征明细必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_risk_signal_writeback where score_id='"
            + scoreA.scoreId() + "'"))
        .as("B 的上下文里直查 A 的回写凭据必须读到零行")
        .isZero();

    // 四张表都是 FORCE RLS
    for (String table :
        List.of("buyer_risk_rule", "buyer_risk_score", "buyer_risk_feature", "buyer_risk_signal_writeback")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from buyer_risk_score where id='"
            + scoreA.scoreId() + "'"))
        .as("A 的上下文必须能直查 A 自己的评分（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_risk_feature where score_id='"
            + scoreA.scoreId() + "'"))
        .isEqualTo(6);
    assertThat(countAsTenant(a, "select count(*) from buyer_risk_signal_writeback where score_id='"
            + scoreA.scoreId() + "'"))
        .isEqualTo(2);

    // 规则也是一租户一条：改 A 的规则不能影响 B 的
    assertThat(countAsTenant(a, "select count(*) from buyer_risk_rule")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from buyer_risk_rule")).isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.5.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seed("Bob Jones", "+1-555-0200", "bob@example.com");
    as(actorA, "channel.operation.update", () -> service.saveRule(rule()));
    as(actorB, "channel.operation.update", () -> service.saveRule(rule()));
    BuyerRiskScoreView scoreB = evaluate(actorB, bearerB).scores().get(0);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的评分
    http.perform(get("/channel/buyer-risk-scores/scores/" + scoreB.scoreId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/buyer-risk-scores/scores")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/buyer-risk-scores/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 自己评一次之后，A 的会话 + B 的头仍然只看到 A 自己的
    evaluate(actorA, bearerA);
    http.perform(get("/channel/buyer-risk-scores/scores")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.5.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seed("Carol White", "+1-555-0300", "carol@example.com");
    as(actorA, "channel.operation.update", () -> service.saveRule(rule()));
    as(actorB, "channel.operation.update", () -> service.saveRule(rule()));
    BuyerRiskScoreView scoreA = evaluate(actorA, bearerA).scores().get(0);
    BuyerRiskScoreView scoreB = evaluate(actorB, bearerB).scores().get(0);

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.scores(null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的评分")
          .extracting(BuyerRiskScoreSummary::scoreId)
          .containsExactly(scoreA.scoreId());
      assertThat(as(actorB, "channel.operation.read", () -> service.scores(null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的评分")
          .extracting(BuyerRiskScoreSummary::scoreId)
          .containsExactly(scoreB.scoreId());
      // 评分详情也跟着租户走
      assertThat(as(actorA, "channel.operation.read", () -> service.score(scoreA.scoreId().toString()))
              .buyerKey())
          .isEqualTo(scoreA.buyerKey());
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from buyer_risk_score"))
        .as("B 的上下文里只能看到 B 自己的评分")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_risk_score"))
        .isEqualTo(1);
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    if (trade != null) trade.close();
    pg.stop();
  }
}
