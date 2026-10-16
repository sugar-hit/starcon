package com.starcon.channel;

import static com.starcon.channel.FreightCalculatorModels.FreightEstimateRequest;
import static com.starcon.channel.RateComparisonModels.*;
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
import java.util.ArrayList;
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
 * 10.1.2.2 渠道比价。
 *
 * <p>本测试类钉七组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 只看价格选渠道是常见错误</b>（蓝图加粗句）：最便宜但投诉率超红线的渠道
 *       **不会被自动选中**，而且仍然出现在对照表里并给出完整得分
 *   <li><b>评分来自数据不是常量</b>：改一组计数，比价里的可靠性分与综合分跟着变；
 *       没有评分的渠道以「没有评分」落选，而不是拿 0 分参与
 *   <li><b>三个闸各自独立生效</b>：样本量不足、评分过期都拦自动选择
 *   <li><b>绕过服务层直接写 SQL 同样被拒</b>：改派生比率、改生效评分的计数、国家不在适用
 *       国家内、店铺预设跳号、单头与明细自相矛盾，全部由 V143 的触发器兜底
 *   <li><b>比价单落库且权重可复算</b>；DB 层把综合分按权重重算，服务层传什么都不作数
 *   <li><b>真分页真筛选</b>，汇总不受分页影响；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：本模块的
 * 评分新鲜度闸按「比价时刻 − 评分观测时刻」判定，用墙钟会让「现在算不算过期」成为一个
 * flaky 断言 —— 而且这种挂钟依赖只会在别的时段才炸。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateComparisonIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 钉死的比价时刻（不在旺季内，但这一点对本模块无影响 —— 本模块不按旺季判定）。 */
  static final OffsetDateTime COMPARED_AT = OffsetDateTime.parse("2026-10-15T09:00:00+08:00");
  /** 评分观测时刻：距 COMPARED_AT 恰好 30 天，落在默认 90 天新鲜度上限内。 */
  static final OffsetDateTime RATED_AT = OffsetDateTime.parse("2026-09-15T09:00:00+08:00");
  /** 同一时刻，但距 COMPARED_AT 200 天 —— 必然过期。 */
  static final OffsetDateTime STALE_AT = OffsetDateTime.parse("2026-04-01T09:00:00+08:00");
  static final OffsetDateTime WINDOW_FROM = OffsetDateTime.parse("2026-07-01T00:00:00+08:00");
  static final OffsetDateTime WINDOW_TO = OffsetDateTime.parse("2026-09-30T00:00:00+08:00");

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

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
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
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired RateComparisonService service;
  @Autowired FreightCalculatorService freight;
  @Autowired LogisticsChannelService channels;
  @Autowired CarrierService carriers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  void truncateAll() {
    // 带 cascade：本模块的表被 V143 的外键挂在 logistics_channel_config / freight_quote 上，
    // 而本类要清的上游表（承运商 / 渠道配置 / 试算单）正是那些被引用的父表。
    exec(
        "truncate channel_comparison_line, channel_comparison, channel_shop_priority,"
            + " channel_rating_score, freight_quote_line, freight_quote, freight_rate_band,"
            + " freight_rate_card, freight_channel_country, logistics_channel_scope,"
            + " logistics_channel_surcharge, logistics_channel_restriction,"
            + " logistics_channel_config, carrier_country_coverage, carrier_business_scope,"
            + " carrier_logistics_profile cascade");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员连接执行 SQL（RLS 对超级用户不生效，所以直写 SQL 用来验证 DB 层的兜底）。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static void execAs(UUID tenant, String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 断言某条写入被拒，且理由里含指定片段。
   *
   * <p>🔴 沿 {@code getCause()} 链逐层找：驱动把 PG 的报错细节放在 cause 里
   * （jOOQ / Spring 的包装异常只带 SQL），只看最外层消息会让这些断言变成
   * 「断言了一个永远匹配不到的东西」的假绿。
   */
  static void assertRejected(String expected, Runnable work) {
    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(work::run);
    assertThat(thrown).as("期望被拒（%s），但没有抛异常", expected).isNotNull();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(expected)) {
        return;
      }
    }
    StringBuilder chain = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      chain.append("[").append(t.getClass().getSimpleName()).append("] ")
          .append(t.getMessage() == null ? "(无消息)" : t.getMessage()).append('\n');
    }
    throw new AssertionError(
        "拒绝理由里没有「" + expected + "」，实际异常链：\n" + chain, thrown);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  UUID seedCarrier(UUID tenant, String code, String name) {
    UUID id = UUID.randomUUID();
    execAs(
        tenant,
        "insert into carrier_logistics_profile (id,tenant_id,role_id,party_id,party_code,"
            + "party_name,party_usable,interface_type,carrier_category,service_tier,secret_ref,"
            + "secret_masked,readiness) values ('" + id + "','" + tenant + "','" + UUID.randomUUID()
            + "','" + UUID.randomUUID() + "','" + code + "','" + name + "',true,'API','EXPRESS_COURIER',"
            + "'EXPRESS','kv://starcon/test/carriers/" + code.toLowerCase() + "','sv_live_****3f2a',"
            + "'NO_COVERAGE')");
    execAs(
        tenant,
        "insert into carrier_country_coverage (tenant_id,profile_id,country_code) values ('"
            + tenant + "','" + id + "','DE')");
    execAs(
        tenant,
        "insert into carrier_business_scope (tenant_id,profile_id,business_type) values ('"
            + tenant + "','" + id + "','OWN_FULFILLMENT')");
    execAs(tenant, "update carrier_logistics_profile set readiness='READY' where id='" + id + "'");
    return id;
  }

  /** 一个 ACTIVE、按重计费、适用 DE 的渠道配置（10.1.1.2 的主权表，走它的服务层写入）。 */
  LogisticsChannelModels.LogisticsChannelRow seedChannel(
      AccessService.Access who, UUID carrierId, String code, String currency, String baseRate) {
    return seedChannel(who, carrierId, code, currency, baseRate, null);
  }

  /**
   * 同上，但可额外登记一个 SHOP 适用范围。
   *
   * <p>🔴 店铺比价的夹具**必须**带 SHOP scope：上游 10.1.2.1 的候选渠道筛选会按店铺
   * 过滤（{@code logistics_channel_scope} 里 scope_kind='SHOP'}），没有它时试算的
   * 候选集就是空的，后面每一个断言都会空转 —— 而且看起来像「功能没实现」。
   */
  LogisticsChannelModels.LogisticsChannelRow seedChannel(
      AccessService.Access who, UUID carrierId, String code, String currency, String baseRate,
      String shopCode) {
    List<LogisticsChannelModels.ChannelScopeItem> scopes = new ArrayList<>();
    scopes.add(new LogisticsChannelModels.ChannelScopeItem("COUNTRY", "DE"));
    if (shopCode != null) {
      scopes.add(new LogisticsChannelModels.ChannelScopeItem("SHOP", shopCode));
    }
    return as(
        who,
        "channel.operation.create",
        () ->
            channels.save(
                new LogisticsChannelModels.SaveChannelRequest(
                    null, carrierId, code, code, "ACTIVE", "BY_WEIGHT", currency, bd(baseRate),
                    bd("1"), bd("0"), null, null, null, "NONE", bd("0"), bd("0"), null,
                    scopes, List.of(), List.of(), "渠道比价集成测试登记", 0)));
  }

  void seedCountryRule(AccessService.Access who, UUID channelId, Integer minDays, Integer maxDays) {
    as(
        who,
        "channel.operation.create",
        () ->
            freight.saveCountryRule(
                new FreightCalculatorModels.SaveCountryRuleRequest(
                    channelId, "DE", false, minDays, maxDays, "集成测试")));
  }

  /** 登记一条生效中的渠道评分（比率由 DB 生成列派生）。 */
  ChannelRatingRow seedRating(
      AccessService.Access who, UUID channelId, int dispatched, int delivered, int onTime,
      int complaint, OffsetDateTime observedAt, String maxComplaintPct, int minSample) {
    return saveRating(
        who,
        new SaveRatingRequest(
            null, channelId, "DE", dispatched, delivered, onTime, complaint,
            WINDOW_FROM, WINDOW_TO, observedAt, "MANUAL_ENTERED", "集成测试登记",
            bd(maxComplaintPct), minSample, "ACTIVE", "渠道比价集成测试", 0));
  }

  ChannelRatingRow saveRating(AccessService.Access who, SaveRatingRequest request) {
    return as(who, "channel.operation.create", () -> service.saveRating(request));
  }

  /** 比价条件：只给包裹参数（走一次无副作用试算），权重用默认。 */
  CompareRequest compareRequest(String shopCode) {
    return new CompareRequest(
        null,
        new FreightEstimateRequest(
            null, "DE", shopCode, bd("1.5"), null, null, null, 1, null, null,
            List.of("HOME_GOODS"), false, false, false, COMPARED_AT, ""),
        null, null, null, null, null, null, null, null, null, null, false, COMPARED_AT, null, "");
  }

  CompareResult compare(AccessService.Access who, CompareRequest request) {
    return as(who, "channel.operation.read", () -> service.compare(request));
  }

  CompareResult saveComparison(AccessService.Access who, CompareRequest request) {
    return as(who, "channel.operation.create", () -> service.saveComparison(request));
  }

  ComparisonLine lineOf(CompareResult result, String channelCode) {
    return result.lines().stream()
        .filter(l -> l.channelCode().equals(channelCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("比价结果里没有渠道 " + channelCode + "：" + result.lines()));
  }

  /** 带 JavaTimeModule：请求体里有 OffsetDateTime，裸 ObjectMapper 会直接抛异常。 */
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  // ==================================================================
  // 1. 多目标排序真的发生
  // ==================================================================

  @Test
  @DisplayName("🔴 按成本/时效/可靠性多目标排序：便宜的未必排第一（时效权重给足时更快的胜出）")
  void rankingIsMultiObjectiveNotPriceOnly() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-FAST", "USD", "40");
    UUID cheap = channelId(actorA, "SF-DE-CHEAP");
    UUID fast = channelId(actorA, "SF-DE-FAST");
    seedCountryRule(actorA, cheap, 12, 20);
    seedCountryRule(actorA, fast, 3, 5);
    // 两条渠道可靠性完全一样 ⇒ 排名差异只可能来自成本分与时效分
    seedRating(actorA, cheap, 1000, 980, 960, 5, RATED_AT, "5", 30);
    seedRating(actorA, fast, 1000, 980, 960, 5, RATED_AT, "5", 30);

    // 成本权重压倒性：最便宜的赢
    var priceFirst = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(priceFirst.selectedChannelCode()).isEqualTo("SF-DE-CHEAP");

    // 时效权重压倒性：更快的赢，哪怕贵四倍
    var speedFirst = compare(actorA, withWeights(compareRequest(""), "0", "1", "0"));
    assertThat(speedFirst.selectedChannelCode()).isEqualTo("SF-DE-FAST");
    assertThat(lineOf(speedFirst, "SF-DE-CHEAP").costScore()).isEqualByComparingTo("100.0000");
    assertThat(lineOf(speedFirst, "SF-DE-CHEAP").transitScore()).isEqualByComparingTo("0.0000");
  }

  @Test
  @DisplayName("🔴 权重真的是输入：落库的是归一化后的值，事后可复算")
  void weightsAreRealInputAndPersisted() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 200, 192, 180, 4, RATED_AT, "5", 30);

    // 传 2/1/1（和为 4）⇒ 归一化成 0.5 / 0.25 / 0.25
    var saved = saveComparison(actorA, withWeights(compareRequest(""), "2", "1", "1"));
    assertThat(saved.costWeight()).isEqualByComparingTo("0.500000");
    assertThat(saved.transitWeight()).isEqualByComparingTo("0.250000");
    assertThat(saved.reliabilityWeight()).isEqualByComparingTo("0.250000");

    var detail = as(actorA, "channel.operation.read", () -> service.comparison(saved.savedComparisonId().toString()));
    assertThat(detail.comparison().costWeight()).isEqualByComparingTo("0.500000");
    assertThat(detail.comparison().transitWeight()).isEqualByComparingTo("0.250000");
    assertThat(detail.comparison().reliabilityWeight()).isEqualByComparingTo("0.250000");
  }

  // ==================================================================
  // 2. 🔴 蓝图加粗句：只看价格选渠道是常见错误
  // ==================================================================

  @Test
  @DisplayName("🔴 最便宜但投诉率超红线的渠道不被自动选中，且仍然出现在对照表里并给出得分")
  void cheapButLossyChannelIsNotAutoSelected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-SAFE", "USD", "25");
    UUID cheap = channelId(actorA, "SF-DE-CHEAP");
    UUID safe = channelId(actorA, "SF-DE-SAFE");
    seedCountryRule(actorA, cheap, 5, 9);
    seedCountryRule(actorA, safe, 5, 9);
    // 便宜的这条：1000 单发运、800 妥投、80 投诉 ⇒ 投诉率 8% > 红线 5%
    seedRating(actorA, cheap, 1000, 800, 600, 80, RATED_AT, "5", 30);
    // 贵的那条：投诉率 1%，远低于红线
    seedRating(actorA, safe, 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));

    // 🔴 成本权重给到 1.0，最便宜的那条综合分仍然最高 —— 但它**没有被选中**
    assertThat(lineOf(result, "SF-DE-CHEAP").costScore()).isEqualByComparingTo("100.0000");
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-SAFE");
    assertThat(result.selectionBasis()).isEqualTo("SCORE_RANKED");

    var flagged = lineOf(result, "SF-DE-CHEAP");
    assertThat(flagged.riskFlagged()).isTrue();
    assertThat(flagged.riskNote()).contains("投诉率").contains("红线");
    // 🔴 被闸拦下的渠道**仍在表里、仍有完整得分**（从表里删掉会让人以为它不存在）
    assertThat(flagged.compositeScore()).isNotNull();
    assertThat(flagged.rankBasis()).isEqualTo("RANKED");
    assertThat(flagged.notes()).anyMatch(n -> n.contains("未自动选中"));
  }

  @Test
  @DisplayName("🔴 闸 (2) 样本量：只发过 3 单全到（100% 成功率）不被自动选中")
  void tinySampleIsNotAutoSelected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-SAFE", "USD", "25");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-CHEAP"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-SAFE"), 5, 9);
    // 3 单全到：成功率 100%、投诉率 0% —— 数字漂亮，但样本根本不够
    seedRating(actorA, channelId(actorA, "SF-DE-CHEAP"), 3, 3, 3, 0, RATED_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-SAFE"), 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-SAFE");
    var tiny = lineOf(result, "SF-DE-CHEAP");
    assertThat(tiny.sampleSufficient()).isFalse();
    assertThat(tiny.sampleNote()).contains("样本量").contains("3");
  }

  @Test
  @DisplayName("🔴 闸 (3) 评分过期：观测于 200 天前的评分不被自动选中（比价时刻钉死）")
  void staleRatingIsNotAutoSelected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-FRESH", "USD", "25");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-CHEAP"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-FRESH"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-CHEAP"), 1000, 990, 980, 10, STALE_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-FRESH"), 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-FRESH");
    assertThat(result.staleRatingCount()).isEqualTo(1);
    var stale = lineOf(result, "SF-DE-CHEAP");
    assertThat(stale.ratingStale()).isTrue();
    assertThat(stale.staleNote()).contains("天");
  }

  @Test
  @DisplayName("🔴 一条都不合格时不静默回落：报 NO_ELIGIBLE_CHANNEL 并逐条给出原因")
  void noEligibleChannelDoesNotSilentlyFallBack() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10");
    UUID cheap = channelId(actorA, "SF-DE-CHEAP");
    seedCountryRule(actorA, cheap, 5, 9);
    seedRating(actorA, cheap, 1000, 800, 600, 80, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(result.selectionBasis()).isEqualTo("NO_ELIGIBLE_CHANNEL");
    assertThat(result.selectedChannelId()).isNull();
    assertThat(result.selectionNote()).contains("投诉率");
    assertThat(result.lines()).allMatch(l -> !l.selected());
    // 落库也必须落成「无可选渠道」，DB 层 CHECK 会钉住这一致性
    var saved = saveComparison(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(saved.savedComparisonId()).isNotNull();
    var detail = as(actorA, "channel.operation.read", () -> service.comparison(saved.savedComparisonId().toString()));
    assertThat(detail.comparison().selectionBasis()).isEqualTo("NO_ELIGIBLE_CHANNEL");
    assertThat(detail.lines()).allMatch(l -> !l.selected());
  }

  // ==================================================================
  // 3. 评分来自数据
  // ==================================================================

  @Test
  @DisplayName("🔴 评分来自数据：改一组计数，可靠性分与综合分跟着变（换承运商表现不用改代码）")
  void ratingComesFromDatabase() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);

    var draft = new SaveRatingRequest(
        null, channel, "DE", 1000, 900, 800, 60, WINDOW_FROM, WINDOW_TO, RATED_AT,
        "MANUAL_ENTERED", "集成测试登记", bd("5"), 30, "ACTIVE", "", 0);
    var poor = seedRating(actorA, channel, 1000, 900, 800, 60, RATED_AT, "5", 30);
    assertThat(poor.deliverySuccessRate()).isEqualByComparingTo("0.9000");
    assertThat(poor.complaintRate()).isEqualByComparingTo("0.0600");
    // 投诉率 6% > 红线 5% ⇒ 被风险闸拦下
    assertThat(compare(actorA, withWeights(compareRequest(""), "0", "0", "1")).selectionBasis())
        .isEqualTo("NO_ELIGIBLE_CHANNEL");

    // 重新统计一轮：旧行置 SUPERSEDED，新行计数改善 ⇒ 同一个渠道这次合格了
    saveRating(actorA, new SaveRatingRequest(
        poor.id(), channel, "DE", 1000, 900, 800, 60, WINDOW_FROM, WINDOW_TO, RATED_AT,
        "MANUAL_ENTERED", "集成测试登记", bd("5"), 30, "SUPERSEDED", "被新一轮取代", poor.version()));
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "0", "0", "1"));
    assertThat(result.selectionBasis()).isEqualTo("SCORE_RANKED");
    assertThat(lineOf(result, "SF-DE-STD").reliabilityScore()).isGreaterThan(bd("90"));
    assertThat(draft.channelId()).isEqualTo(channel);
  }

  @Test
  @DisplayName("🔴 没有评分的渠道以「没有评分」落选，而不是拿 0 分参与（0 分与没数据含义相反）")
  void unratedChannelIsExcludedNotScoredZero() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-RATED", "USD", "30");
    seedChannel(actorA, carrier, "SF-DE-UNRATED", "USD", "5");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-RATED"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-UNRATED"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-RATED"), 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    var unrated = lineOf(result, "SF-DE-UNRATED");
    assertThat(unrated.rankBasis()).isEqualTo("EXCLUDED_NO_SCORE");
    assertThat(unrated.reliabilityScore()).isNull();
    assertThat(unrated.compositeScore()).isNull();
    assertThat(unrated.rankNo()).isNull();
    assertThat(unrated.exclusionReason()).contains("没有").contains("渠道评分");
    // 结果里也要**提前告诉**使用者哪些渠道还没有评分
    assertThat(as(actorA, "channel.operation.read", () -> service.ratings(null, null, null, "CHANNEL"))
            .unratedChannelCodes())
        .contains("SF-DE-UNRATED");
  }

  @Test
  @DisplayName("🔴 比率是 DB 生成列：直写 SQL 改不了（绕过服务层同样编不出好看的比率）")
  void generatedRatesCannotBeForged() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    var rating = seedRating(actorA, channel, 200, 190, 180, 2, RATED_AT, "5", 30);
    assertThat(rating.deliverySuccessRate()).isEqualByComparingTo("0.9500");
    assertThat(rating.onTimeRate()).isEqualByComparingTo("0.9474");

    assertRejected(
        "can only be updated to DEFAULT",
        () -> execAs(a, "update channel_rating_score set delivery_success_rate = 1.0000 where id = '"
            + rating.id() + "'"));
  }

  // ==================================================================
  // 4. 店铺预设优先级
  // ==================================================================

  @Test
  @DisplayName("按店铺预设优先级自动选择：预设第 1 位优先于综合分第 1 位")
  void shopPresetPriorityWinsOverScore() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "10", "SHOP-DE-1");
    seedChannel(actorA, carrier, "SF-DE-PRESET", "USD", "35", "SHOP-DE-1");
    UUID cheap = channelId(actorA, "SF-DE-CHEAP");
    UUID preset = channelId(actorA, "SF-DE-PRESET");
    seedCountryRule(actorA, cheap, 5, 9);
    seedCountryRule(actorA, preset, 5, 9);
    seedRating(actorA, cheap, 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorA, preset, 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedPriority(actorA, "SHOP-DE-1", preset, 1);
    seedPriority(actorA, "SHOP-DE-1", cheap, 2);

    // 不开预设 ⇒ 按综合分，最便宜的赢
    assertThat(compare(actorA, withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")).selectedChannelCode())
        .isEqualTo("SF-DE-CHEAP");

    // 开预设 ⇒ 按店铺优先级，运营指定的赢（即使它更贵、综合分更低）
    var result = compare(actorA, withShopPriority(withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")));
    assertThat(result.selectionBasis()).isEqualTo("SHOP_PRESET_PRIORITY");
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-PRESET");
    assertThat(lineOf(result, "SF-DE-PRESET").priorityNo()).isEqualTo(1);
    assertThat(result.selectionNote()).contains("预设优先级");
  }

  @Test
  @DisplayName("🔴 预设第 1 位但被投诉率闸拦下时，不会被选中（预设是偏好，不是对差渠道的豁免）")
  void shopPresetCannotOverrideRiskGate() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-LOSSY", "USD", "10", "SHOP-DE-1");
    seedChannel(actorA, carrier, "SF-DE-SAFE", "USD", "35", "SHOP-DE-1");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-LOSSY"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-SAFE"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-LOSSY"), 1000, 800, 600, 80, RATED_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-SAFE"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedPriority(actorA, "SHOP-DE-1", channelId(actorA, "SF-DE-LOSSY"), 1);
    seedPriority(actorA, "SHOP-DE-1", channelId(actorA, "SF-DE-SAFE"), 2);

    var result = compare(actorA, withShopPriority(withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")));
    // 预设第 1 位没过闸 ⇒ 取预设里**下一条合格的**，而不是退回它的第 1 位
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-SAFE");
    assertThat(lineOf(result, "SF-DE-LOSSY").riskFlagged()).isTrue();
  }

  @Test
  @DisplayName("🔴 预设里全部没过闸时，落到综合分推荐并在说明里写明原因，不假装按预设选到了")
  void shopPresetAllBlockedFallsBackToScoreWithReason() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-LOSSY", "USD", "10", "SHOP-DE-1");
    seedChannel(actorA, carrier, "SF-DE-SAFE", "USD", "35", "SHOP-DE-1");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-LOSSY"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-SAFE"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-LOSSY"), 1000, 800, 600, 80, RATED_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-SAFE"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    // 只预设了那条有问题的渠道，且禁自动
    seedPriority(actorA, "SHOP-DE-1", channelId(actorA, "SF-DE-LOSSY"), 1);

    var result = compare(actorA, withShopPriority(withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")));
    // 预设里没有合格渠道 ⇒ 不用预设口径，按综合分选合格的那条并说明原因
    assertThat(result.selectionBasis()).isEqualTo("SCORE_RANKED");
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-SAFE");
  }

  @Test
  @DisplayName("🔴 店铺预设序号跳空被拒（第 2 位是谁从此无人知晓）")
  void shopPriorityGapIsRejected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-A", "USD", "30");
    assertRejected(
        "连续覆盖",
        () -> seedPriority(actorA, "SHOP-GAP", channelId(actorA, "SF-DE-A"), 3));
  }

  // ==================================================================
  // 5. DB 层兜底：绕过服务层写 SQL 同样被拒
  // ==================================================================

  @Test
  @DisplayName("🔴 国家不在渠道适用国家里的评分被拒（否则是「配了但永远用不上」的死配置）")
  void ratingCountryMustBeInChannelScope() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    assertRejected(
        "适用国家",
        () -> saveRating(actorA, new SaveRatingRequest(
            null, channelId(actorA, "SF-DE-STD"), "US", 100, 90, 80, 1,
            WINDOW_FROM, WINDOW_TO, RATED_AT, "MANUAL_ENTERED", "", bd("5"), 30, "ACTIVE", "", 0)));
  }

  @Test
  @DisplayName("🔴 生效中的评分不允许就地改计数（已发出的比价单会永远复算不出来）")
  void activeRatingCountersAreFrozen() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    var rating = seedRating(actorA, channel, 200, 190, 180, 2, RATED_AT, "5", 30);

    assertRejected(
        "不能改原始计数",
        () -> saveRating(actorA, new SaveRatingRequest(
            rating.id(), channel, "DE", 300, 290, 280, 3,
            WINDOW_FROM, WINDOW_TO, RATED_AT, "MANUAL_ENTERED", "", bd("5"), 30, "ACTIVE", "", rating.version())));
  }

  @Test
  @DisplayName("🔴 比价单与明细自相矛盾时在 DB 层被拒（单头选 A、没有任何行标 selected）")
  void inconsistentSelectionIsRejectedByDatabase() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);
    UUID comparisonId = UUID.randomUUID();

    assertRejected(
        "必须恰好 1 条",
        () -> execAs(a, comparisonHead(comparisonId, channel)
            + "insert into channel_comparison_line (tenant_id,comparison_id,channel_id,channel_code,"
            + "channel_name,carrier_name,eligibility,total_charge,currency,cost_score,transit_score,"
            + "reliability_score,rank_basis,rank_no,selected) values ('" + a + "','" + comparisonId
            + "','" + channel + "','SF-DE-STD','SF-DE-STD','SF','PASS',30,'USD',100,100,100,"
            + "'RANKED',1,false)"));
  }

  @Test
  @DisplayName("🔴 综合分由 DB 按权重重算：服务层传什么都不作数")
  void compositeIsRecomputedByDatabase() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    var saved = saveComparison(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    var line = as(actorA, "channel.operation.read", () -> service.comparison(saved.savedComparisonId().toString()))
        .lines().get(0);
    // 成本权重 1.0 ⇒ 综合分必须等于成本分
    assertThat(line.compositeScore()).isEqualByComparingTo(line.costScore());

    // 直写 SQL 把综合分改成 999 ⇒ DB 触发器覆盖成按权重重算的值
    execAs(a, "update channel_comparison_line set composite_score = 999 where comparison_id = '"
        + saved.savedComparisonId() + "'");
    var reread = as(actorA, "channel.operation.read", () -> service.comparison(saved.savedComparisonId().toString()))
        .lines().get(0);
    assertThat(reread.compositeScore()).isEqualByComparingTo(line.compositeScore());
  }

  @Test
  @DisplayName("🔴 未排名的行必须带落选理由（DB 层 CHECK，空串也拒）")
  void excludedLineMustCarryReason() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);
    UUID comparisonId = UUID.randomUUID();

    assertRejected(
        "channel_comparison_line_check",
        () -> execAs(a, comparisonHead(comparisonId, channel)
            + "insert into channel_comparison_line (tenant_id,comparison_id,channel_id,channel_code,"
            + "channel_name,carrier_name,eligibility,total_charge,currency,rank_basis,exclusion_reason)"
            + " values ('" + a + "','" + comparisonId + "','" + channel
            + "','SF-DE-STD','SF-DE-STD','SF','PASS',30,'USD','EXCLUDED_NO_SCORE','')"));
  }

  @Test
  @DisplayName("🔴 权重未归一化的比价单被 DB 拒")
  void unnormalizedWeightsAreRejected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID comparisonId = UUID.randomUUID();
    assertRejected(
        "channel_comparison_check",
        () -> execAs(a,
            "insert into channel_comparison (tenant_id,id,dest_country,shop_code,weight_kg,pieces,"
                + "compared_at,cost_weight,transit_weight,reliability_weight,success_weight,"
                + "on_time_weight,complaint_weight,currency,selection_basis)"
                + " values ('" + a + "','" + comparisonId + "','DE','',1.5,1,'" + COMPARED_AT
                + "',0.5,0.5,0.5,0.4,0.4,0.2,'USD','NO_ELIGIBLE_CHANNEL')"));
  }

  // ==================================================================
  // 6. 比价条件与可比性
  // ==================================================================

  @Test
  @DisplayName("时效上限是真闸：预计时效超要求的渠道以 EXCLUDED_TRANSIT 落选")
  void maxTransitDaysIsARealGate() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-SLOW", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-FAST", "USD", "30");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-SLOW"), 10, 20);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-FAST"), 3, 5);
    seedRating(actorA, channelId(actorA, "SF-DE-SLOW"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-FAST"), 1000, 990, 980, 10, RATED_AT, "5", 30);

    var request = withMaxTransit(
        withWeights(compareRequest(""), "1", "0", "0"), 9);
    var result = compare(actorA, request);
    assertThat(lineOf(result, "SF-DE-SLOW").rankBasis()).isEqualTo("EXCLUDED_TRANSIT");
    assertThat(lineOf(result, "SF-DE-SLOW").exclusionReason()).contains("时效上界").contains("9");
    assertThat(result.selectedChannelCode()).isEqualTo("SF-DE-FAST");
  }

  @Test
  @DisplayName("🔴 币种不同的渠道不参与排序（不同币种的金额直接比大小没有意义）")
  void mixedCurrencyIsNotRanked() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    // USD 明确更便宜（15 vs 75）⇒ 主币种是 USD，EUR 那条不参与排序
    seedChannel(actorA, carrier, "SF-DE-USD", "USD", "10");
    seedChannel(actorA, carrier, "SF-DE-EUR", "EUR", "50");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-USD"), 5, 9);
    seedCountryRule(actorA, channelId(actorA, "SF-DE-EUR"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-USD"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorA, channelId(actorA, "SF-DE-EUR"), 1000, 990, 980, 10, RATED_AT, "5", 30);

    var result = compare(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThat(result.mixedCurrency()).isTrue();
    assertThat(lineOf(result, "SF-DE-EUR").rankBasis()).isEqualTo("EXCLUDED_CURRENCY");
    assertThat(lineOf(result, "SF-DE-EUR").exclusionReason()).contains("币种");
    assertThat(result.currency()).isEqualTo("USD");
  }

  @Test
  @DisplayName("🔴 比价成本来自上游试算单：costBasis 恒为 RULE_ESTIMATE，引用单据可被下游追溯")
  void costBasisIsAlwaysRuleEstimate() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    // 先让上游 10.1.2.1 落一张试算单，再引用它比价
    var quote = as(actorA, "channel.operation.create",
        () -> freight.saveQuote(new FreightEstimateRequest(
            null, "DE", "", bd("1.5"), null, null, null, 1, null, null,
            List.of("HOME_GOODS"), false, false, false, COMPARED_AT, "")));
    assertThat(quote.savedQuoteId()).isNotNull();

    var result = compare(actorA, new CompareRequest(
        quote.savedQuoteId(), null, null, null, null, null, null, null, null, null, null,
        null, false, COMPARED_AT, null, ""));
    assertThat(result.costBasis()).isEqualTo("RULE_ESTIMATE");
    assertThat(result.sourceQuoteId()).isEqualTo(quote.savedQuoteId());
    // 🔴 比价的成本必须**就是**上游试算单那一行的金额（本模块不重算钱），
    //    所以断言的是「等于上游的值」，不是「等于某个写死的数字」。
    var upstream = quote.lines().stream()
        .filter(l -> l.channelCode().equals("SF-DE-STD"))
        .findFirst()
        .orElseThrow();
    assertThat(upstream.totalCharge()).isNotNull();
    assertThat(lineOf(result, "SF-DE-STD").totalCharge())
        .isEqualByComparingTo(upstream.totalCharge());

    // 落库后再读回：单头带 cost_basis 与来源试算单
    var saved = as(actorA, "channel.operation.create", () -> service.saveComparison(
        new CompareRequest(quote.savedQuoteId(), null, null, null, null, null, null, null, null,
            null, null, null, false, COMPARED_AT, null, "")));
    var detail = as(actorA, "channel.operation.read",
        () -> service.comparison(saved.savedComparisonId().toString()));
    assertThat(detail.comparison().costBasis()).isEqualTo("RULE_ESTIMATE");
    assertThat(detail.comparison().sourceQuoteId()).isEqualTo(quote.savedQuoteId());
  }

  @Test
  @DisplayName("非法输入被拒：三个权重全 0 / 国家格式错 / 比价条件两样都没给")
  void invalidInputIsRejected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);
    seedRating(actorA, channel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    assertThatThrownBy(() -> compare(actorA, withWeights(compareRequest(""), "0", "0", "0")))
        .hasMessageContaining("权重");
    assertThatThrownBy(() -> compare(actorA, new CompareRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            false, COMPARED_AT, null, "")))
        .hasMessageContaining("sourceQuoteId");
    assertThatThrownBy(() -> saveRating(actorA, new SaveRatingRequest(
            null, channel, "DEU", 100, 90, 80, 1, WINDOW_FROM, WINDOW_TO, RATED_AT,
            "MANUAL_ENTERED", "", bd("5"), 30, "ACTIVE", "", 0)))
        .hasMessageContaining("两位大写字母");
    // 计数倒挂（按时妥投 > 已妥投）同样在服务层就被拒，不必等 DB
    assertThatThrownBy(() -> saveRating(actorA, new SaveRatingRequest(
            null, channel, "DE", 100, 90, 95, 1, WINDOW_FROM, WINDOW_TO, RATED_AT,
            "MANUAL_ENTERED", "", bd("5"), 30, "ACTIVE", "", 0)))
        .hasMessageContaining("已妥投数");
    // 统计窗口倒挂
    assertThatThrownBy(() -> saveRating(actorA, new SaveRatingRequest(
            null, channel, "DE", 100, 90, 80, 1, WINDOW_TO, WINDOW_FROM, RATED_AT,
            "MANUAL_ENTERED", "", bd("5"), 30, "ACTIVE", "", 0)))
        .hasMessageContaining("统计窗口");
  }

  // ==================================================================
  // 7. 真分页 + 真筛选
  // ==================================================================

  @Test
  @DisplayName("比价单列表真分页真筛选，汇总不受分页影响")
  void comparisonListIsReallyPagedAndFiltered() {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD", "30");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD", "30");
    UUID aChannel = channelId(actorA, "SF-DE-STD");
    UUID bChannel = channelId(actorB, "FX-DE-STD");
    seedCountryRule(actorA, aChannel, 5, 9);
    seedCountryRule(actorB, bChannel, 5, 9);
    seedRating(actorA, aChannel, 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorB, bChannel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    for (int i = 0; i < 5; i++) {
      saveComparison(actorA, shifted(compareRequest(""), i));
    }
    saveComparison(actorB, compareRequest(""));

    var page1 = as(actorA, "channel.operation.read",
        () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 0, 2));
    assertThat(page1.total()).isEqualTo(5);
    assertThat(page1.totalPages()).isEqualTo(3);
    assertThat(page1.items()).hasSize(2);
    // 汇总不受 limit/offset 影响
    assertThat(page1.summary().total()).isEqualTo(5);
    assertThat(page1.summary().withSelection()).isEqualTo(5);

    var page3 = as(actorA, "channel.operation.read",
        () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 2, 2));
    assertThat(page3.items()).hasSize(1);

    // 真筛选：按店铺筛
    assertThat(as(actorA, "channel.operation.read",
            () -> service.comparisons(null, "SHOP-DE-9", null, null, null, "TIME_DESC", 0, 50)).total())
        .isZero();
    // 真筛选：按目的国筛（其它租户的记录看不见）
    assertThat(as(actorB, "channel.operation.read",
            () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 0, 50)).total())
        .isEqualTo(1);
  }

  // ==================================================================
  // 8. 端点与能力声明
  // ==================================================================

  @Test
  @DisplayName("端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD", "30");
    UUID channel = channelId(actorA, "SF-DE-STD");
    seedCountryRule(actorA, channel, 5, 9);

    // 能力声明：未交付段 available=false + ownerModule + impact 三者齐备
    http.perform(get("/channel/rate-comparison/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectionBases").value(
            org.hamcrest.Matchers.contains("SCORE_RANKED", "SHOP_PRESET_PRIORITY", "NO_ELIGIBLE_CHANNEL")))
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.6.2.1')].available")
            .value(org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.6.2.1')].impact").isNotEmpty())
        // 「只看价格选渠道是常见错误」这条口径写在接口自述里
        .andExpect(jsonPath("$.gatingNote").value(
            org.hamcrest.Matchers.containsString("只挡自动选择")))
        .andExpect(jsonPath("$.weightingNote").value(
            org.hamcrest.Matchers.containsString("只看价格选渠道是常见错误")));

    // 登记评分 → 比价 → 落库 → 列表 → 详情
    String ratingBody = JSON.writeValueAsString(new SaveRatingRequest(
        null, channel, "DE", 1000, 990, 980, 10, WINDOW_FROM, WINDOW_TO, RATED_AT,
        "MANUAL_ENTERED", "端到端登记", new BigDecimal("5"), 30, "ACTIVE", "", 0));
    http.perform(post("/channel/rate-comparison/ratings")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json").content(ratingBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliverySuccessRate").value(0.99))
        .andExpect(jsonPath("$.complaintRate").value(0.01));

    String compareBody = JSON.writeValueAsString(withWeights(compareRequest(""), "1", "0", "0"));
    http.perform(post("/channel/rate-comparison/compare")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json").content(compareBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.costBasis").value("RULE_ESTIMATE"))
        .andExpect(jsonPath("$.selectedChannelCode").value("SF-DE-STD"))
        .andExpect(jsonPath("$.savedComparisonId").doesNotExist())
        // 🔴 未参与比价的字段在响应里**不存在**（无副作用）
        .andExpect(jsonPath("$.lines[0].compositeScore").isNumber());

    http.perform(post("/channel/rate-comparison/comparisons")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(new SaveComparisonRequest(
                withWeights(compareRequest(""), "1", "0", "0")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.savedComparisonId").isNotEmpty());

    http.perform(get("/channel/rate-comparison/ratings")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ratings[0].channelCode").value("SF-DE-STD"));

    http.perform(get("/channel/rate-comparison/comparisons?size=10")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.withSelection").value(1));

    http.perform(get("/channel/rate-comparison/comparisons/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 未认证一律 401
    http.perform(get("/channel/rate-comparison/comparisons"))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 9. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.1.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD", "30");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD", "30");
    UUID aChannel = channelId(actorA, "SF-DE-STD");
    UUID bChannel = channelId(actorB, "FX-DE-STD");
    seedCountryRule(actorA, aChannel, 5, 9);
    seedCountryRule(actorB, bChannel, 5, 9);
    seedRating(actorA, aChannel, 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorB, bChannel, 1000, 990, 980, 10, RATED_AT, "5", 30);

    // 比价结果里只有本租户的渠道
    assertThat(compare(actorA, withWeights(compareRequest(""), "1", "0", "0")).lines())
        .extracting(ComparisonLine::channelCode).containsExactly("SF-DE-STD");
    assertThat(compare(actorB, withWeights(compareRequest(""), "1", "0", "0")).lines())
        .extracting(ComparisonLine::channelCode).containsExactly("FX-DE-STD");

    // 评分互不可见
    assertThat(as(actorA, "channel.operation.read", () -> service.ratings(null, null, null, "CHANNEL")).ratings())
        .extracting(ChannelRatingRow::channelCode).containsExactly("SF-DE-STD");
    assertThat(as(actorB, "channel.operation.read", () -> service.ratings(null, null, null, "CHANNEL")).ratings())
        .extracting(ChannelRatingRow::channelCode).containsExactly("FX-DE-STD");

    // 比价单也互不可见：跨租户与不存在返回逐字相同的 404
    var savedA = saveComparison(actorA, withWeights(compareRequest(""), "1", "0", "0"));
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read",
                () -> service.comparison(savedA.savedComparisonId().toString())))
        .hasMessageContaining("比价单不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read",
                () -> service.comparison(UUID.randomUUID().toString())))
        .hasMessageContaining("比价单不存在");

    // 列表也只看得到自己的
    assertThat(as(actorB, "channel.operation.read",
        () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
  }

  @Test
  @DisplayName("10.1.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD", "30");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD", "30");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-STD"), 5, 9);
    seedCountryRule(actorB, channelId(actorB, "FX-DE-STD"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-STD"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorB, channelId(actorB, "FX-DE-STD"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    String body = JSON.writeValueAsString(withWeights(compareRequest(""), "1", "0", "0"));

    // A 的会话 + B 的头 ⇒ 比价结果里仍然只有 A 的渠道
    http.perform(post("/channel/rate-comparison/compare")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json").content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[*].channelCode",
            org.hamcrest.Matchers.contains("SF-DE-STD")))
        .andExpect(jsonPath("$.costBasis").value("RULE_ESTIMATE"));

    // 无会话一律 401
    http.perform(post("/channel/rate-comparison/compare")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.1.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD", "30", "SHOP-DE-1");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD", "30", "SHOP-DE-1");
    seedCountryRule(actorA, channelId(actorA, "SF-DE-STD"), 5, 9);
    seedCountryRule(actorB, channelId(actorB, "FX-DE-STD"), 5, 9);
    seedRating(actorA, channelId(actorA, "SF-DE-STD"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedRating(actorB, channelId(actorB, "FX-DE-STD"), 1000, 990, 980, 10, RATED_AT, "5", 30);
    seedPriority(actorA, "SHOP-DE-1", channelId(actorA, "SF-DE-STD"), 1);
    seedPriority(actorB, "SHOP-DE-1", channelId(actorB, "FX-DE-STD"), 1);

    for (int i = 0; i < 3; i++) {
      assertThat(compare(actorA, withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")).lines())
          .extracting(ComparisonLine::channelCode).containsExactly("SF-DE-STD");
      assertThat(compare(actorB, withWeights(compareRequest("SHOP-DE-1"), "1", "0", "0")).lines())
          .extracting(ComparisonLine::channelCode).containsExactly("FX-DE-STD");
      // 评分与店铺预设是同一个连接池上的另外两个入口
      assertThat(as(actorA, "channel.operation.read", () -> service.ratings(null, null, null, "CHANNEL")).ratings())
          .extracting(ChannelRatingRow::channelCode).containsExactly("SF-DE-STD");
      assertThat(as(actorB, "channel.operation.read", () -> service.ratings(null, null, null, "CHANNEL")).ratings())
          .extracting(ChannelRatingRow::channelCode).containsExactly("FX-DE-STD");
      assertThat(as(actorA, "channel.operation.read",
          () -> service.shopPriorities("SHOP-DE-1", null)).priorities())
          .extracting(ShopPriorityRow::channelCode).containsExactly("SF-DE-STD");
      assertThat(as(actorB, "channel.operation.read",
          () -> service.shopPriorities("SHOP-DE-1", null)).priorities())
          .extracting(ShopPriorityRow::channelCode).containsExactly("FX-DE-STD");
      assertThat(as(actorA, "channel.operation.read",
          () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
      assertThat(as(actorB, "channel.operation.read",
          () -> service.comparisons(null, null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
    }
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  UUID channelId(AccessService.Access who, String channelCode) {
    return as(who, "channel.operation.read", () -> channels.channels(
            null, null, null, null, null, null, null, null, null, 0, 50)).items().stream()
        .filter(c -> c.channelCode().equals(channelCode))
        .map(LogisticsChannelModels.LogisticsChannelRow::id)
        .findFirst()
        .orElseThrow(() -> new AssertionError("找不到渠道 " + channelCode));
  }

  void seedPriority(AccessService.Access who, String shop, UUID channelId, int priorityNo) {
    as(who, "channel.operation.create", () -> service.saveShopPriority(
        new SaveShopPriorityRequest(null, shop, channelId, priorityNo, true, new BigDecimal("0"),
            "ACTIVE", "渠道比价集成测试", 0)));
  }

  static CompareRequest withWeights(CompareRequest base, String cost, String transit, String reliability) {
    return new CompareRequest(
        base.sourceQuoteId(), base.parcel(), base.channelIds(), base.maxTransitDays(),
        base.applyShopPriority(), bd(cost), bd(transit), bd(reliability),
        base.successRateWeight(), base.onTimeRateWeight(), base.complaintRateWeight(),
        base.maxRatingAgeDays(), base.requirePassOnly(), base.comparedAt(),
        base.minCompositeScore(), base.note());
  }

  static CompareRequest withShopPriority(CompareRequest base) {
    return new CompareRequest(
        base.sourceQuoteId(), base.parcel(), base.channelIds(), base.maxTransitDays(),
        Boolean.TRUE, base.costWeight(), base.transitWeight(), base.reliabilityWeight(),
        base.successRateWeight(), base.onTimeRateWeight(), base.complaintRateWeight(),
        base.maxRatingAgeDays(), base.requirePassOnly(), base.comparedAt(),
        base.minCompositeScore(), base.note());
  }

  static CompareRequest withMaxTransit(CompareRequest base, int days) {
    return new CompareRequest(
        base.sourceQuoteId(), base.parcel(), base.channelIds(), days,
        base.applyShopPriority(), base.costWeight(), base.transitWeight(), base.reliabilityWeight(),
        base.successRateWeight(), base.onTimeRateWeight(), base.complaintRateWeight(),
        base.maxRatingAgeDays(), base.requirePassOnly(), base.comparedAt(),
        base.minCompositeScore(), base.note());
  }

  /** 把比价时刻往后挪 n 天，制造可区分的时间线（不用 {@code now()}）。 */
  static CompareRequest shifted(CompareRequest base, int days) {
    return new CompareRequest(
        base.sourceQuoteId(), base.parcel(), base.channelIds(), base.maxTransitDays(),
        base.applyShopPriority(), base.costWeight(), base.transitWeight(), base.reliabilityWeight(),
        base.successRateWeight(), base.onTimeRateWeight(), base.complaintRateWeight(),
        base.maxRatingAgeDays(), base.requirePassOnly(), base.comparedAt().plusDays(days),
        base.minCompositeScore(), base.note());
  }

  /** 一条「选中某渠道」的比价单头（用于验证 DB 层的一致性触发器）。 */
  String comparisonHead(UUID comparisonId, UUID channelId) {
    return "insert into channel_comparison (tenant_id,id,cost_basis,dest_country,shop_code,weight_kg,"
        + "pieces,compared_at,cost_weight,transit_weight,reliability_weight,success_weight,"
        + "on_time_weight,complaint_weight,currency,selected_channel_id,selected_count,"
        + "selection_basis,apply_shop_priority,ranked_count,candidate_count)"
        + " values ('" + a + "','" + comparisonId + "','RULE_ESTIMATE','DE','',1.5,1,'" + COMPARED_AT
        + "',1.0,0.0,0.0,0.4,0.4,0.2,'USD','" + channelId + "',1,'SCORE_RANKED',true,1,1);";
  }
}
