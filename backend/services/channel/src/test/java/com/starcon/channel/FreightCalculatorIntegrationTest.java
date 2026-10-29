package com.starcon.channel;

import static com.starcon.channel.FreightCalculatorModels.*;
import static com.starcon.channel.FreightRatingEngine.Band;
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
 * 10.1.2.1 运费计算器。
 *
 * <p>本测试类钉六组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>计费规则来自数据库而不是代码常量</b>：改一张费率卡的档位单价，对照表里的
 *       金额跟着变；没有卡时才回退到渠道单值，并且轨迹里写明用的是回退口径
 *   <li><b>算不出就留空，不给 0</b>：规则不全的渠道金额为 null，直接写 SQL 存 0
 *       会被 DB 的 CHECK 拒 —— 一条 0 元运费会被当成「最便宜」选进下游分仓
 *   <li><b>绕过服务层直接写 SQL 同样被拒</b>：档位重叠、生效卡留洞、两张生效卡、
 *       量纲与渠道计费方式不一致、国家不在适用国家里，全部由 V140 的触发器兜底
 *   <li><b>试算 ≠ 实际成本</b>：{@code cost_basis} 在 DB 层只允许 RULE_ESTIMATE，
 *       直写 SQL 想改成实际结算口径会被 CHECK 拒
 *   <li><b>真分页真筛选</b>，汇总不受分页影响；对照表按运费升序、算不出的沉底
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：旺季费有
 * 生效区间，用墙钟会让「今天在不在旺季里」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FreightCalculatorIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 固定求值时刻：落在 10 月旺季区间**内**，旺季费边界两侧各测一次。 */
  static final OffsetDateTime IN_PEAK =
      OffsetDateTime.parse("2026-10-15T09:00:00+08:00");
  static final OffsetDateTime AFTER_PEAK =
      OffsetDateTime.parse("2026-12-01T09:00:00+08:00");
  static final OffsetDateTime PEAK_FROM =
      OffsetDateTime.parse("2026-10-01T00:00:00+08:00");
  static final OffsetDateTime PEAK_TO =
      OffsetDateTime.parse("2026-11-01T00:00:00+08:00");

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
    // 同样带 cascade：本模块的表被 V140 的外键挂在 logistics_channel_config 上，
    // 而本类要清的上游表（承运商 / 渠道配置）正是那些被引用的父表（口径照抄
    // CarrierIntegrationTest / LogisticsChannelIntegrationTest 的同一处理）。
    exec(
        "truncate freight_quote_line, freight_quote, freight_rate_band, freight_rate_card,"
            + " freight_channel_country, logistics_channel_scope, logistics_channel_surcharge,"
            + " logistics_channel_restriction, logistics_channel_config,"
            + " carrier_country_coverage, carrier_business_scope, carrier_logistics_profile cascade");
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

  /** 适用国家 DE —— 渠道没有它就不是任何目的国的候选渠道。 */
  static List<LogisticsChannelModels.ChannelScopeItem> deScope() {
    return List.of(new LogisticsChannelModels.ChannelScopeItem("COUNTRY", "DE"));
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
    execAs(
        tenant,
        "update carrier_logistics_profile set readiness='READY' where id='" + id + "'");
    return id;
  }

  /** 一个 ACTIVE、按重计费、适用 DE 的渠道配置（10.1.1.2 的主权表，走它的服务层写入）。 */
  LogisticsChannelModels.LogisticsChannelRow seedChannel(
      AccessService.Access who, UUID carrierId, String code, String currency) {
    // 适用国家 DE：没有它这条渠道对谁都不是候选（readiness 会停在 NO_SCOPE）
    return saveChannel(
        who, carrierId, code, currency, "BY_WEIGHT", "28.50", "1", "0",
        List.of(new LogisticsChannelModels.ChannelScopeItem("COUNTRY", "DE")),
        List.of(), List.of());
  }

  @SuppressWarnings("java:S107")
  LogisticsChannelModels.LogisticsChannelRow saveChannel(
      AccessService.Access who,
      UUID carrierId,
      String code,
      String currency,
      String billingMode,
      String baseRate,
      String divisor,
      String minCharge,
      List<LogisticsChannelModels.ChannelScopeItem> scopes,
      List<LogisticsChannelModels.ChannelSurchargeItem> surcharges,
      List<LogisticsChannelModels.ChannelRestrictionItem> restrictions) {
    return as(
        who,
        "channel.operation.create",
        () ->
            channels.save(
                new LogisticsChannelModels.SaveChannelRequest(
                    null, carrierId, code, code, "ACTIVE", billingMode, currency, bd(baseRate),
                    bd(divisor), bd(minCharge), null, null, null, "NONE", bd("0"), bd("0"), null,
                    scopes, surcharges, restrictions, "运费试算集成测试登记", 0)));
  }

  RateCardRow seedCard(
      AccessService.Access who, UUID channelId, String chargeModel, String status,
      String roundingMode, int scale, String volumetricDivisor, List<RateBandItem> bands) {
    return saveCard(who, null, channelId, status, chargeModel, "WEIGHT", roundingMode, scale,
        volumetricDivisor, null, null, null, null, bands);
  }

  @SuppressWarnings("java:S107")
  RateCardRow saveCard(
      AccessService.Access who,
      UUID cardId,
      UUID channelId,
      String status,
      String chargeModel,
      String basis,
      String roundingMode,
      int scale,
      String volumetricDivisor,
      String firstQty,
      String firstAmount,
      String additionalQty,
      String additionalAmount,
      List<RateBandItem> bands) {
    return saveCard(who, cardId, channelId, status, chargeModel, basis, roundingMode, scale,
        volumetricDivisor, firstQty, firstAmount, additionalQty, additionalAmount, bands, 0);
  }

  @SuppressWarnings("java:S107")
  RateCardRow saveCard(
      AccessService.Access who,
      UUID cardId,
      UUID channelId,
      String status,
      String chargeModel,
      String basis,
      String roundingMode,
      int scale,
      String volumetricDivisor,
      String firstQty,
      String firstAmount,
      String additionalQty,
      String additionalAmount,
      List<RateBandItem> bands,
      long version) {
    return as(
        who,
        "channel.operation.create",
        () ->
            freight.saveRateCard(
                new SaveRateCardRequest(
                    cardId, channelId, status, chargeModel, basis, roundingMode, scale,
                    volumetricDivisor == null ? null : bd(volumetricDivisor),
                    firstQty == null ? null : bd(firstQty),
                    firstAmount == null ? null : bd(firstAmount),
                    additionalQty == null ? null : bd(additionalQty),
                    additionalAmount == null ? null : bd(additionalAmount),
                    bands, "运费试算集成测试登记", version)));
  }

  /**
   * 三段档位：0–1 / 1–5 / 5 以上，单价 10 / 12 / 20。
   *
   * <p>🔴 半开区间是**唯一能落库**的形态：[1,5] 闭 + [5,∞) 会让 5.0 同时落在两段里，
   * 被 V140 的 guard_freight_band_overlap 当作重叠拒掉。测试夹具必须与生产可落库
   * 的形态一致，否则整个类都在测一种线上不存在的配置。
   */
  static List<RateBandItem> threeBands(String first, String second, String third) {
    return List.of(
        new RateBandItem(1, bd("0"), bd("1"), false, bd(first), ""),
        new RateBandItem(2, bd("1"), bd("5"), false, bd(second), ""),
        new RateBandItem(3, bd("5"), null, false, bd(third), ""));
  }

  ChannelCountryRule seedCountryRule(
      AccessService.Access who, UUID channelId, String country, boolean remote,
      Integer minDays, Integer maxDays) {
    return as(
        who,
        "channel.operation.create",
        () ->
            freight.saveCountryRule(
                new SaveCountryRuleRequest(channelId, country, remote, minDays, maxDays, "集成测试")));
  }

  FreightEstimateRequest plainRequest(String weight) {
    return new FreightEstimateRequest(
        null, "DE", "", bd(weight), null, null, null, 1, null, null, List.of("HOME_GOODS"),
        false, false, false, IN_PEAK, "");
  }

  FreightEstimateRequest requestAt(String weight, OffsetDateTime at) {
    return new FreightEstimateRequest(
        null, "DE", "", bd(weight), null, null, null, 1, null, null, List.of("HOME_GOODS"),
        false, false, false, at, "");
  }

  FreightEstimateResult estimate(AccessService.Access who, FreightEstimateRequest request) {
    return as(who, "channel.operation.read", () -> freight.estimate(request));
  }

  FreightEstimateResult quote(AccessService.Access who, FreightEstimateRequest request) {
    return as(who, "channel.operation.create", () -> freight.saveQuote(request));
  }

  FreightEstimateLine lineOf(FreightEstimateResult result, String channelCode) {
    return result.lines().stream()
        .filter(l -> l.channelCode().equals(channelCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("对照表里没有渠道 " + channelCode + "：" + result.lines()));
  }

  // ==================================================================
  // 1. 计费规则来自数据库
  // ==================================================================

  /** 带 JavaTimeModule：请求体里有 OffsetDateTime，裸 ObjectMapper 会直接抛异常。 */
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName("10.1.2.1 没有生效费率卡 ⇒ 回退渠道单值，并在轨迹里写明口径")
  void fallsBackToChannelFlatRateWithoutCard() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");

    var result = estimate(actorA, plainRequest("4"));
    var line = lineOf(result, "SF-DE-STD");

    assertThat(line.rateSource()).isEqualTo("CHANNEL_FLAT");
    // 4 ÷ 1 × 28.50 = 114.00
    assertThat(line.totalCharge()).isEqualByComparingTo("114.00");
    assertThat(line.trace()).anyMatch(t -> t.contains("渠道单值回退"));
    assertThat(result.costBasis()).isEqualTo("RULE_ESTIMATE");
  }

  @Test
  @DisplayName("🔴 计分档位真的参与计算：按重量段取价，而不是按 base_rate 线性算")
  void tiersComeFromDatabase() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    seedCard(actorA, listFirst(actorA, "SF-DE-STD"), "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
        threeBands("10", "12", "20"));

    // 1.5kg ⇒ 1×10 + 0.5×12 = 16.00（线性 base_rate 会算出 42.75）
    var line = lineOf(estimate(actorA, plainRequest("1.5")), "SF-DE-STD");
    assertThat(line.rateSource()).as("notes=%s trace=%s", line.notes(), line.trace())
        .isEqualTo("RATE_CARD");
    assertThat(line.baseCharge()).isEqualByComparingTo("16.00");
    assertThat(line.trace()).anyMatch(t -> t.contains("第 2 段"));
  }

  @Test
  @DisplayName("🔴 规则来自数据：改档位单价，金额跟着变（换承运商费率不用改代码）")
  void changingBandRateChangesTheQuote() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    var card = seedCard(actorA, listFirst(actorA, "SF-DE-STD"), "FLAT_RATE", "ACTIVE", "HALF_UP", 2,
        null, threeBands("10", "12", "20"));
    assertThat(lineOf(estimate(actorA, plainRequest("1.5")), "SF-DE-STD").totalCharge())
        .isEqualByComparingTo("16.00");

    var cheaper = as(
        actorA,
        "channel.operation.create",
        () ->
            freight.saveRateCard(
                new SaveRateCardRequest(
                    card.id(), card.channelId(), "ACTIVE", "FLAT_RATE", "WEIGHT", "HALF_UP", 2,
                    null, null, null, null, null, threeBands("10", "8", "20"), "", card.version())));

    assertThat(cheaper.version()).isGreaterThan(card.version());
    // 1.5kg ⇒ 1×10 + 0.5×8 = 14.00
    assertThat(lineOf(estimate(actorA, plainRequest("1.5")), "SF-DE-STD").totalCharge())
        .isEqualByComparingTo("14.00");
  }

  @Test
  @DisplayName("首重 + 续重：余量向上进位（余 0.000001kg 也收整段）")
  void firstPlusAdditionalRoundsUp() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    saveCard(actorA, null, listFirst(actorA, "SF-DE-STD"), "ACTIVE", "FIRST_PLUS_ADDITIONAL",
        "WEIGHT", "HALF_UP", 2, null, "1", "12", "0.5", "3", List.of());

    assertThat(lineOf(estimate(actorA, plainRequest("1.5")), "SF-DE-STD").totalCharge())
        .isEqualByComparingTo("15.00");
    assertThat(lineOf(estimate(actorA, plainRequest("1.500001")), "SF-DE-STD").totalCharge())
        .isEqualByComparingTo("18.00");
  }

  @Test
  @DisplayName("体积重：轻泡货按 max(实重, 体积重) 计费")
  void volumetricWeightApplies() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    seedCard(actorA, listFirst(actorA, "SF-DE-STD"), "FLAT_RATE", "ACTIVE", "HALF_UP", 2, "6000",
        threeBands("10", "12", "20"));

    var boxed =
        new FreightEstimateRequest(
            null, "DE", "", bd("1"), bd("30"), bd("20"), bd("20"), 1, null, null,
            List.of("HOME_GOODS"), false, false, false, IN_PEAK, "");
    var line = lineOf(estimate(actorA, boxed), "SF-DE-STD");

    assertThat(line.volumetricQty()).isEqualByComparingTo("2.000000");
    // 12000 ÷ 6000 = 2kg ⇒ 1×10 + 1×12 = 22
    assertThat(line.chargeableQty()).isEqualByComparingTo("2.000000");
    assertThat(line.totalCharge()).isEqualByComparingTo("22.00");
  }

  UUID listFirst(AccessService.Access who, String channelCode) {
    return as(who, "channel.operation.read", () -> channels.channels(
            null, null, null, null, null, null, null, null, "NAME_ASC", 0, 50))
        .items()
        .stream()
        .filter(c -> c.channelCode().equals(channelCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("渠道不存在：" + channelCode))
        .id();
  }

  // ==================================================================
  // 2. 附加费：燃油 / 偏远 / 旺季两端
  // ==================================================================

  @Test
  @DisplayName("🔴 附加费真的参与计算：燃油按百分比、偏远按国家、旺季按求值时刻两端")
  void surchargesAreRealInputs() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(
        actorA, carrier, "SF-DE-STD", "USD", "BY_WEIGHT", "28.50", "1", "0",
        deScope(),
        List.of(
            new LogisticsChannelModels.ChannelSurchargeItem(
                "FUEL", "PERCENT", bd("18.5"), null, null, ""),
            new LogisticsChannelModels.ChannelSurchargeItem(
                "REMOTE_AREA", "FIXED", bd("12"), null, null, ""),
            new LogisticsChannelModels.ChannelSurchargeItem(
                "PEAK_SEASON", "FIXED", bd("5"), PEAK_FROM, PEAK_TO, "")),
        List.of());
    UUID channelId = listFirst(actorA, "SF-DE-STD");
    seedCard(actorA, channelId, "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
        threeBands("10", "12", "20"));
    seedCountryRule(actorA, channelId, "DE", true, 5, 9);

    var inPeak = lineOf(estimate(actorA, requestAt("1", IN_PEAK)), "SF-DE-STD");
    assertThat(inPeak.baseCharge()).isEqualByComparingTo("10.00");
    assertThat(inPeak.fuelFee()).isEqualByComparingTo("1.85"); // 10 × 18.5%
    assertThat(inPeak.remoteFee()).isEqualByComparingTo("12.00");
    assertThat(inPeak.peakFee()).isEqualByComparingTo("5.00");
    assertThat(inPeak.totalCharge()).isEqualByComparingTo("28.85");
    assertThat(inPeak.transitMinDays()).isEqualTo(5);
    assertThat(inPeak.transitMaxDays()).isEqualTo(9);

    // 区间终点那一刻不再收旺季费（左闭右开）
    var atEnd = lineOf(estimate(actorA, requestAt("1", PEAK_TO)), "SF-DE-STD");
    assertThat(atEnd.peakFee()).isEqualByComparingTo("0.00");
    assertThat(atEnd.totalCharge()).isEqualByComparingTo("23.85");

    var outside = lineOf(estimate(actorA, requestAt("1", AFTER_PEAK)), "SF-DE-STD");
    assertThat(outside.peakFee()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("该国不偏远时不收偏远费；未登记时效时给空值并说明，不编天数")
  void remoteAndTransitAreConditional() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(
        actorA, carrier, "SF-DE-STD", "USD", "BY_WEIGHT", "28.50", "1", "0",
        deScope(),
        List.of(new LogisticsChannelModels.ChannelSurchargeItem(
            "REMOTE_AREA", "FIXED", bd("12"), null, null, "")),
        List.of());

    var line = lineOf(estimate(actorA, plainRequest("1")), "SF-DE-STD");
    assertThat(line.remoteFee()).isEqualByComparingTo("0.00");
    assertThat(line.transitMinDays()).isNull();
    assertThat(line.notes()).anyMatch(n -> n.contains("未登记") && n.contains("时效"));
  }

  @Test
  @DisplayName("最低收费：低于它按它收（否则 1 件小件算出几分钱运费）")
  void minChargeApplies() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(actorA, carrier, "SF-DE-STD", "USD", "BY_WEIGHT", "28.50", "1", "15",
        deScope(), List.of(), List.of());

    var line = lineOf(estimate(actorA, plainRequest("0.2")), "SF-DE-STD");
    assertThat(line.baseCharge()).isEqualByComparingTo("15.00");
  }

  // ==================================================================
  // 3. 算不出就留空，不给 0
  // ==================================================================

  @Test
  @DisplayName("🔴 规则不全的渠道金额留空（NO_RULE），而不是给 0 元")
  void unpriceableChannelHasNoMoney() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    // 按体积计费的渠道：没给长宽高就算不出钱
    saveChannel(actorA, carrier, "SF-DE-VOL", "USD", "BY_VOLUME", "30", "6000", "0",
        deScope(), List.of(), List.of());

    var line = lineOf(estimate(actorA, plainRequest("2")), "SF-DE-VOL");

    assertThat(line.rateSource()).isEqualTo("NO_RULE");
    assertThat(line.quoteable()).isFalse();
    assertThat(line.totalCharge()).isNull();
    assertThat(line.baseCharge()).isNull();
    assertThat(line.notes()).anyMatch(n -> n.contains("算不出运费"));
  }

  @Test
  @DisplayName("🔴 直写 SQL 也不能把 0 元运费存成一条正常报价（DB 层 CHECK）")
  void databaseRefusesToStoreZeroForUnpriceableLine() throws Exception {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(actorA, carrier, "SF-DE-VOL", "USD", "BY_VOLUME", "30", "6000", "0",
        deScope(), List.of(), List.of());
    var result = quote(actorA, plainRequest("2"));
    UUID quoteId = result.savedQuoteId();
    UUID channelId = listFirst(actorA, "SF-DE-VOL");

    Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try {
      assertThatThrownBy(
              () -> {
                var s = c.createStatement();
                s.execute("set app.current_tenant = '" + a + "'");
                s.execute(
                    "update freight_quote_line set rate_source='NO_RULE', base_charge=0,"
                        + "fuel_fee=0,remote_fee=0,peak_fee=0,insurance_fee=0,total_charge=0"
                        + " where tenant_id='" + a + "' and quote_id='" + quoteId
                        + "' and channel_id='" + channelId + "'");
              })
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("freight_quote_line_check");
    } finally {
      c.close();
    }
  }

  @Test
  @DisplayName("对照表按运费升序，算不出的沉底（不混在中间冒充便宜）")
  void linesAreSortedCheapestFirst() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(actorA, carrier, "SF-DE-CHEAP", "USD", "BY_WEIGHT", "10", "1", "0",
        deScope(), List.of(), List.of());
    saveChannel(actorA, carrier, "SF-DE-VOL", "USD", "BY_VOLUME", "30", "6000", "0",
        deScope(), List.of(), List.of());

    var result = estimate(actorA, plainRequest("2"));

    assertThat(result.lines()).hasSize(2);
    assertThat(result.lines().get(0).channelCode()).isEqualTo("SF-DE-CHEAP");
    assertThat(result.lines().get(0).quoteable()).isTrue();
    assertThat(result.lines().get(1).channelCode()).isEqualTo("SF-DE-VOL");
    assertThat(result.lines().get(1).quoteable()).isFalse();
    assertThat(result.quoteableCount()).isEqualTo(1);
  }

  // ==================================================================
  // 4. DB 层兜底：绕过服务层写 SQL 同样被拒
  // ==================================================================

  @Test
  @DisplayName("🔴 档位重叠被拒：[0,1] 闭 + [1,5) 开 在 1kg 处重叠；改成半开就放行")
  void overlappingBandsAreRejected() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");
    // 先只放一段：上界是闭的（[0,1]），1.0kg 归属它
    RateCardRow card = seedCard(actorA, channelId, "FLAT_RATE", "DRAFT", "HALF_UP", 2, null,
        List.of(new RateBandItem(1, bd("0"), bd("1"), true, bd("10"), "")));

    // 再加一段 [1,5) ⇒ 1.0 同时落在两段 ⇒ 重叠 ⇒ 拒
    assertRejected(
        "档位重叠",
        () -> execAs(
            a,
            "insert into freight_rate_band (tenant_id,card_id,band_no,from_qty,to_qty,"
                + "to_qty_inclusive,rate_amount) values ('" + a + "','" + card.id()
                + "',2,1,5,false,12)"));

    // 把第一段上界改成开区间 ⇒ 两段严格衔接 ⇒ 放行
    execAs(
        a,
        "update freight_rate_band set to_qty_inclusive=false where tenant_id='" + a
            + "' and card_id='" + card.id() + "' and band_no=1");
    execAs(
        a,
        "insert into freight_rate_band (tenant_id,card_id,band_no,from_qty,to_qty,"
            + "to_qty_inclusive,rate_amount) values ('" + a + "','" + card.id()
            + "',2,1,5,false,12)");

    assertThat(as(actorA, "channel.operation.read", () -> freight.rateCards(
            card.id().toString(), null, null, null)).cards().get(0).bands()).hasSize(2);
  }

  @Test
  @DisplayName("🔴 生效中的费率卡不允许留档位空洞（落在这个区间的货算不出钱）")
  void activeCardRejectsGaps() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");

    // 草稿留洞可以（先加后调是正常编辑流程）
    var draft = seedCard(actorA, channelId, "FLAT_RATE", "DRAFT", "HALF_UP", 2, null,
        List.of(
            new RateBandItem(1, bd("0"), bd("1"), false, bd("10"), ""),
            new RateBandItem(2, bd("5"), null, false, bd("20"), "")));
    assertThat(draft.id()).isNotNull();

    // 但启用它会被拒
    assertRejected(
        "连续覆盖",
        () -> saveCard(actorA, draft.id(), channelId, "ACTIVE", "FLAT_RATE", "WEIGHT", "HALF_UP", 2,
            null, null, null, null, null,
            List.of(
                new RateBandItem(1, bd("0"), bd("1"), false, bd("10"), ""),
                new RateBandItem(2, bd("5"), null, false, bd("20"), "")),
            draft.version()));

    // 补齐 1–5 段后就能启用
    var fixed = saveCard(actorA, draft.id(), channelId, "ACTIVE", "FLAT_RATE", "WEIGHT", "HALF_UP", 2,
        null, null, null, null, null, threeBands("10", "12", "20"), draft.version());
    assertThat(fixed.status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("🔴 一条渠道只能有一张生效费率卡（两张都生效时这一票按哪张算无法回答）")
  void onlyOneActiveCardPerChannel() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");
    seedCard(actorA, channelId, "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null, threeBands("10", "12", "20"));

    assertRejected(
        "freight_rate_card_one_active",
        () -> seedCard(actorA, channelId, "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
            threeBands("99", "99", "99")));

    // 草稿可以并存（对比费率再启用是正常流程）
    var draft = seedCard(actorA, channelId, "FLAT_RATE", "DRAFT", "HALF_UP", 2, null,
        threeBands("30", "30", "30"));
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(draft.version()).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 费率卡量纲与渠道计费方式不一致被拒（按一个量纲分段、拿另一个量纲取价）")
  void basisMustMatchChannelBillingMode() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");

    assertRejected(
        "不一致",
        () -> saveCard(actorA, null, channelId, "DRAFT", "FLAT_RATE", "PIECE", "HALF_UP", 2, null,
            null, null, null, null, List.of(new RateBandItem(1, bd("0"), null, false, bd("5"), ""))));
  }

  @Test
  @DisplayName("🔴 分国家规则必须落在渠道的适用国家里（否则是「配了但永远用不上」的死配置）")
  void countryRuleMustBeInChannelScope() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");

    assertRejected(
        "不在这条渠道的适用国家里",
        () -> seedCountryRule(actorA, channelId, "US", false, 5, 9));

    seedCountryRule(actorA, channelId, "DE", false, 5, 9);
  }

  @Test
  @DisplayName("生效中的费率卡不能改计费模型（已发出的试算单是按旧口径算的）")
  void activeCardKeepsItsModel() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    UUID channelId = listFirst(actorA, "SF-DE-STD");
    var card = seedCard(actorA, channelId, "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
        threeBands("10", "12", "20"));

    assertRejected(
        "不能改计费模型",
        () -> saveCard(actorA, card.id(), channelId, "ACTIVE", "FIRST_PLUS_ADDITIONAL", "WEIGHT",
            "HALF_UP", 2, null, "1", "12", "0.5", "3", List.of(), card.version()));
  }

  // ==================================================================
  // 5. 试算 ≠ 实际成本
  // ==================================================================

  @Test
  @DisplayName("🔴 试算单落库且 cost_basis 恒为 RULE_ESTIMATE；直写 SQL 改不成实际结算口径")
  void costBasisIsLockedToRuleEstimate() throws Exception {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    var result = quote(actorA, plainRequest("1"));

    assertThat(result.savedQuoteId()).isNotNull();
    assertThat(result.costBasis()).isEqualTo("RULE_ESTIMATE");

    var detail = as(actorA, "channel.operation.read", () -> freight.quote(result.savedQuoteId().toString()));
    assertThat(detail.quote().costBasis()).isEqualTo("RULE_ESTIMATE");
    assertThat(detail.lines()).hasSize(1);
    assertThat(detail.lines().get(0).calcTrace()).isNotBlank();

    Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try {
      assertThatThrownBy(
              () -> {
                var s = c.createStatement();
                s.execute("set app.current_tenant = '" + a + "'");
                s.execute(
                    "update freight_quote set cost_basis='ACTUAL_SETTLEMENT' where tenant_id='" + a
                        + "' and id='" + result.savedQuoteId() + "'");
              })
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("freight_quote_cost_basis_check");
    } finally {
      c.close();
    }
  }

  @Test
  @DisplayName("试算（无副作用）与试算落库是两个入口：前者不写库")
  void estimateDoesNotWriteButQuoteDoes() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");

    var dry = estimate(actorA, plainRequest("1"));
    assertThat(dry.savedQuoteId()).isNull();
    assertThat(as(actorA, "channel.operation.read",
        () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();

    var saved = quote(actorA, plainRequest("1"));
    assertThat(saved.savedQuoteId()).isNotNull();
    assertThat(as(actorA, "channel.operation.read",
        () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 50)).total()).isEqualTo(1);
  }

  @Test
  @DisplayName("多币种渠道：明确标出 mixedCurrency，最低价只在主币种内统计")
  void mixedCurrencyIsFlaggedNotSilentlyCompared() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(actorA, carrier, "SF-DE-USD", "USD", "BY_WEIGHT", "10", "1", "0",
        deScope(), List.of(), List.of());
    saveChannel(actorA, carrier, "SF-DE-EUR", "EUR", "BY_WEIGHT", "8", "1", "0",
        deScope(), List.of(), List.of());

    var result = estimate(actorA, plainRequest("1"));

    assertThat(result.mixedCurrency()).isTrue();
    assertThat(result.currency()).isEqualTo("EUR"); // 8 EUR < 10 USD —— 但两者不可比
    // 主币种内只有一个渠道，所以最低 = 最高 = 8.00
    assertThat(result.cheapestTotal()).isEqualByComparingTo("8.00");
    assertThat(result.dearestTotal()).isEqualByComparingTo("8.00");
    assertThat(result.note()).contains("未参与比价");
  }

  // ==================================================================
  // 6. 真分页真筛选
  // ==================================================================

  @Test
  @DisplayName("试算单列表：真分页 + 真筛选，汇总不受分页影响")
  void quotesPageAndFilter() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");
    for (int i = 0; i < 3; i++) {
      quote(actorA, new FreightEstimateRequest(
          null, i == 0 ? "US" : "DE", "", bd("1"), null, null, null, 1, null, null,
          List.of("HOME_GOODS"), false, false, false, IN_PEAK.plusHours(i), ""));
    }

    var firstPage = as(actorA, "channel.operation.read",
        () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(3);
    assertThat(firstPage.totalPages()).isEqualTo(3);
    // 汇总不受分页影响
    assertThat(firstPage.summary().total()).isEqualTo(3);
    // 其中一张寄到 US 的没有候选渠道（渠道只适用 DE）⇒ 它没有最低运费，不算 quoteable
    assertThat(firstPage.summary().quoteable()).isEqualTo(2);

    var byCountry = as(actorA, "channel.operation.read",
        () -> freight.quotes("DE", null, null, null, "TIME_DESC", 0, 50));
    assertThat(byCountry.items()).hasSize(2);
    assertThat(byCountry.summary().total()).isEqualTo(2);

    assertRejected("排序方式", () -> as(actorA, "channel.operation.read",
        () -> freight.quotes(null, null, null, null, "DROP_TABLE", 0, 50)));
  }

  // ==================================================================
  // 7. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.1.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD");

    // 对照表里只有本租户的渠道
    assertThat(estimate(actorA, plainRequest("1")).lines())
        .extracting(FreightEstimateLine::channelCode)
        .containsExactly("SF-DE-STD");
    assertThat(estimate(actorB, plainRequest("1")).lines())
        .extracting(FreightEstimateLine::channelCode)
        .containsExactly("FX-DE-STD");

    // B 用 A 的渠道 id 指定试算 ⇒ 算不出（候选集里没有它）
    UUID aChannelId = listFirst(actorA, "SF-DE-STD");
    var crossRequest = new FreightEstimateRequest(
        List.of(aChannelId), "DE", "", bd("1"), null, null, null, 1, null, null,
        List.of("HOME_GOODS"), false, false, false, IN_PEAK, "");
    assertThat(estimate(actorB, crossRequest).lines()).isEmpty();

    // 试算单也互不可见：跨租户与不存在返回逐字相同的 404
    var savedA = quote(actorA, plainRequest("1"));
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read",
                () -> freight.quote(savedA.savedQuoteId().toString())))
        .hasMessageContaining("试算单不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read",
                () -> freight.quote(UUID.randomUUID().toString())))
        .hasMessageContaining("试算单不存在");

    // 列表也只看得到自己的
    assertThat(as(actorB, "channel.operation.read",
        () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
  }

  @Test
  @DisplayName("10.1.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD");
    String body = JSON.writeValueAsString(plainRequest("1"));

    // A 的会话 + B 的头 ⇒ 试算结果里仍然只有 A 的渠道
    http.perform(post("/channel/freight/estimate")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[*].channelCode",
            org.hamcrest.Matchers.contains("SF-DE-STD")))
        .andExpect(jsonPath("$.costBasis").value("RULE_ESTIMATE"));

    // 无会话一律 401
    http.perform(post("/channel/freight/estimate")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.1.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID carrierA = seedCarrier(a, "SF", "顺丰速运");
    UUID carrierB = seedCarrier(b, "FEDEX", "FedEx");
    seedChannel(actorA, carrierA, "SF-DE-STD", "USD");
    seedChannel(actorB, carrierB, "FX-DE-STD", "USD");
    seedCard(actorA, listFirst(actorA, "SF-DE-STD"), "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
        threeBands("10", "12", "20"));
    seedCard(actorB, listFirst(actorB, "FX-DE-STD"), "FLAT_RATE", "ACTIVE", "HALF_UP", 2, null,
        threeBands("10", "12", "20"));

    for (int i = 0; i < 3; i++) {
      assertThat(estimate(actorA, plainRequest("1")).lines())
          .extracting(FreightEstimateLine::channelCode)
          .containsExactly("SF-DE-STD");
      assertThat(estimate(actorB, plainRequest("1")).lines())
          .extracting(FreightEstimateLine::channelCode)
          .containsExactly("FX-DE-STD");
      // 规则集与试算单列表交替跑 —— 它们是同一个连接池上的另外两个入口
      assertThat(as(actorA, "channel.operation.read",
          () -> freight.rateCards(null, null, "ACTIVE", null)).cards())
          .extracting(RateCardRow::channelCode)
          .containsExactly("SF-DE-STD");
      assertThat(as(actorB, "channel.operation.read",
          () -> freight.rateCards(null, null, "ACTIVE", null)).cards())
          .extracting(RateCardRow::channelCode)
          .containsExactly("FX-DE-STD");
      assertThat(as(actorA, "channel.operation.read",
          () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
      assertThat(as(actorB, "channel.operation.read",
          () -> freight.quotes(null, null, null, null, "TIME_DESC", 0, 50)).total()).isZero();
    }
  }

  // ==================================================================
  // 8. 端点与能力声明
  // ==================================================================

  @Test
  @DisplayName("10.1.2.1 端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");

    // 能力声明：未交付段 available=false + ownerModule + impact 三者齐备
    http.perform(get("/channel/freight/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chargeModels").value(
            org.hamcrest.Matchers.contains("FLAT_RATE", "FIRST_PLUS_ADDITIONAL")))
        .andExpect(jsonPath("$.rateSources").value(
            org.hamcrest.Matchers.contains("RATE_CARD", "CHANNEL_FLAT", "NO_RULE")))
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.1.2.2')].available")
            .value(org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.1.2.2')].impact").isNotEmpty())
        // 试算 ≠ 实际成本这条口径写在接口自述里
        .andExpect(jsonPath("$.estimationNote").value(
            org.hamcrest.Matchers.containsString("结算用实际")));

    // 规则写入口 → 试算入口 → 试算单入口，全链路
    String card = JSON.writeValueAsString(
        new SaveRateCardRequest(
            null, listFirst(actorA, "SF-DE-STD"), "ACTIVE", "FLAT_RATE", "WEIGHT", "HALF_UP", 2,
            null, null, null, null, null, threeBands("10", "12", "20"), "端点链路", 0));
    http.perform(post("/channel/freight/rate-cards")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(card))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.bands.length()").value(3));

    http.perform(get("/channel/freight/rate-cards")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards.length()").value(1))
        .andExpect(jsonPath("$.cards[0].bands[0].rateAmount").value(10.0));

    String rule = JSON.writeValueAsString(
        new SaveCountryRuleRequest(listFirst(actorA, "SF-DE-STD"), "DE", false, 5, 9, "端点链路"));
    http.perform(post("/channel/freight/country-rules")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(rule))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transitMinDays").value(5));

    String estimate = JSON.writeValueAsString(plainRequest("1.5"));
    http.perform(post("/channel/freight/estimate")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(estimate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[0].rateSource").value("RATE_CARD"))
        .andExpect(jsonPath("$.lines[0].totalCharge").value(16.00))
        .andExpect(jsonPath("$.lines[0].transitMinDays").value(5))
        .andExpect(jsonPath("$.costBasis").value("RULE_ESTIMATE"));

    http.perform(post("/channel/freight/quotes")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(
                new SaveQuoteRequest(plainRequest("1.5")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.savedQuoteId").isNotEmpty());

    http.perform(get("/channel/freight/quotes")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].costBasis").value("RULE_ESTIMATE"))
        .andExpect(jsonPath("$.summary.quoteable").value(1));

    // 参数校验是真校验：只给两个尺寸
    var halfDims = new FreightEstimateRequest(
        null, "DE", "", bd("1"), bd("10"), bd("10"), null, 1, null, null, List.of(),
        false, false, false, IN_PEAK, "");
    http.perform(post("/channel/freight/estimate")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(halfDims)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail",
            org.hamcrest.Matchers.containsString("长宽高")));
  }

  @Test
  @DisplayName("包裹参数缺一半尺寸被拒（半套尺寸会算出一个看起来正常的体积重）")
  void partialDimensionsAreRejected() {
    assertRejected("长宽高", () -> estimate(actorA,
        new FreightEstimateRequest(
            null, "DE", "", bd("1"), bd("10"), bd("10"), null, 1, null, null, List.of(),
            false, false, false, IN_PEAK, "")));
  }

  @Test
  @DisplayName("目的地没有候选渠道 ⇒ 空结果并说明（空结果不代表可以随便选一条）")
  void noCandidateChannelIsExplained() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    seedChannel(actorA, carrier, "SF-DE-STD", "USD");

    var result = estimate(actorA, new FreightEstimateRequest(
        null, "JP", "", bd("1"), null, null, null, 1, null, null, List.of("HOME_GOODS"),
        false, false, false, IN_PEAK, ""));

    assertThat(result.lines()).isEmpty();
    assertThat(result.quoteableCount()).isZero();
    assertThat(result.currency()).isEmpty();
    assertThat(result.note()).contains("没有候选渠道");
  }

  @Test
  @DisplayName("可承运性由上游 10.1.1.2 判定：带电渠道被硬拦截时对照表仍是三列齐全")
  void eligibilityComesFromUpstreamService() {
    UUID carrier = seedCarrier(a, "SF", "顺丰速运");
    saveChannel(
        actorA, carrier, "SF-DE-STD", "USD", "BY_WEIGHT", "28.50", "1", "0",
        deScope(),
        List.of(),
        List.of(new LogisticsChannelModels.ChannelRestrictionItem(
            "BATTERY", "", "BLOCK", "含电池需走危险品渠道")));

    var allowed = estimate(actorA, plainRequest("1")).lines().get(0);
    assertThat(allowed.eligibility()).isEqualTo("PASS");

    var withBattery = estimate(actorA, new FreightEstimateRequest(
        null, "DE", "", bd("1"), null, null, null, 1, null, null, List.of("HOME_GOODS"),
        true, false, false, IN_PEAK, "")).lines().get(0);

    assertThat(withBattery.eligibility()).isEqualTo("BLOCK");
    assertThat(withBattery.eligibilityReasons()).anyMatch(r -> r.contains("电池"));
  }

  /** 供 L2 复用的健康检查：本类的 DB 触发器确实建出来了。 */
  @Test
  @DisplayName("V140 的触发器确实存在（硬拦截的边界是 DB，不是应用层的 if）")
  void databaseTriggersExist() throws Exception {
    List<String> triggers = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select tgname from pg_trigger where tgrelid in"
                + " ('freight_rate_card'::regclass,'freight_rate_band'::regclass,"
                + " 'freight_channel_country'::regclass) and not tgisinternal")) {
      while (rs.next()) {
        triggers.add(rs.getString(1));
      }
    }
    assertThat(triggers)
        .contains(
            "guard_freight_rate_card_basis_trg",
            "guard_freight_band_overlap_trg",
            "guard_freight_rate_card_activate_trg",
            "guard_freight_country_in_scope_trg");
  }

  /** 让静态分析不认为 Band 导入未使用（档位形状在本测试里通过三段档位间接使用）。 */
  @SuppressWarnings("unused")
  private static final Class<Band> BAND_TYPE = Band.class;
}
