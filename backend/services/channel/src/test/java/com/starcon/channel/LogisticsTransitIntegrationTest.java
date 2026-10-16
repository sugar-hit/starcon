package com.starcon.channel;

import static com.starcon.channel.LogisticsChannelModels.ChannelRestrictionItem;
import static com.starcon.channel.LogisticsChannelModels.ChannelScopeItem;
import static com.starcon.channel.LogisticsChannelModels.LogisticsChannelRow;
import static com.starcon.channel.LogisticsChannelModels.SaveChannelRequest;
import static com.starcon.channel.LogisticsTransitModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 10.1.1.3 时效与限制。
 *
 * <p>本测试类钉七组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 限制是真硬拦截</b>（蓝图加粗句）：超尺寸 / 重量上限判 BLOCK，
 *       上游禁限品类 + 本模块标记要求汇总成一个结论
 *   <li><b>绕过服务层直接写 SQL 同样被拒</b>：硬拦截的边界是 DB，不是应用层的 if
 *       （重量段重叠 / 更宽松的国家级上限 / 与上游打架的标记要求）
 *   <li><b>「必须贴标识」是 REVIEW 而不是 PASS</b>：不拦，但不允许自动放行
 *   <li><b>没有时效承诺要说出来</b>：不拿相邻段凑一个数
 *   <li><b>时刻一律钉死</b>：timestamptz 往返不偏 8 小时，且断言**写回的值**
 *   <li><b>真分页真筛选</b>，汇总不受分页影响
 * </ol>
 *
 * <p>⚠️ 本测试<b>不用 {@code now()} 去喂一个会被断言的分片键</b>：时效分片键是
 * （国家 × 重量段 × 服务等级 × 生效区间），用墙钟会同时制造 flaky 与定时炸弹 ——
 * 「今天在不在这个档期里」不该是一个随日历漂移的断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsTransitIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
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

  @Autowired LogisticsTransitService transit;
  @Autowired LogisticsChannelService channels;
  @Autowired CarrierService carriers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  /**
   * 🔴 钉死的时刻：档期区间两端都是整秒，避免微秒截断带来的假失败。
   *
   * <p>三组时刻各有用途：区间内、区间外、以及一个「区间中间」的取整点 ——
   * 用 {@code now()} 的话这三组断言会随日历漂移，而且一旦写库偏了 8 小时，
   * 它们仍然可能是绿的（这正是 10.1.1.2 旺季费踩过的坑）。
   */
  static final OffsetDateTime WINDOW_FROM = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime WINDOW_TO = OffsetDateTime.of(2026, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime INSIDE_WINDOW = OffsetDateTime.of(2026, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime OUTSIDE_WINDOW = OffsetDateTime.of(2027, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 🔴 cascade 是必需的：logistics_channel_config 是**渠道配置的父表**，除本模块
      //   10.1.1.3 的三张表外，下游模块也按渠道对它建了复合外键
      //   （10.1.2.1 运费计算器 V140：freight_rate_card / freight_channel_country /
      //   freight_quote_line）。TRUNCATE 一张被外键引用的表时 PostgreSQL 要求把**全部**
      //   引用表一并处理，否则报 23503「cannot truncate a table referenced in a foreign
      //   key constraint」—— 症状是本类**每个**用例在 setUp 里就炸，与断言无关。
      //   显式列举本模块关心的表 + cascade 兜住下游引用，是与
      //   LogisticsChannelIntegrationTest 同款的有意双保险。
      s.execute("truncate logistics_transit_rule, logistics_size_limit,"
          + " logistics_marking_requirement, logistics_channel_scope, logistics_channel_surcharge,"
          + " logistics_channel_restriction, logistics_channel_config,"
          + " carrier_country_coverage, carrier_business_scope, carrier_logistics_profile"
          + " cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  /**
   * 直接落一条齐备的承运商物流档案（10.1.1.1 的表）。
   *
   * <p>写入顺序必须与 {@link CarrierService#save} 一致：先以「子表还空着时正确的
   * readiness」插入父行 → 写子表 → 再回填最终 readiness。直接插一行
   * {@code readiness='READY'} 会被 V134 的触发器当场拒。
   */
  UUID seedCarrier(UUID tenant, String code, String name, boolean usable) {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      s.execute(
          "insert into carrier_logistics_profile (id,tenant_id,role_id,party_id,party_code,"
              + "party_name,party_usable,interface_type,carrier_category,service_tier,"
              + "secret_ref,secret_masked,readiness)"
              + " values ('" + id + "','" + tenant + "','" + UUID.randomUUID() + "','"
              + UUID.randomUUID() + "','" + code + "','" + name + "'," + usable + ","
              + "'API','EXPRESS_COURIER','EXPRESS',"
              + "'kv://starcon/test/carriers/" + code.toLowerCase() + "','sv_live_****3f2a',"
              + "'NO_COVERAGE')");
      s.execute("insert into carrier_country_coverage (tenant_id,profile_id,country_code)"
          + " values ('" + tenant + "','" + id + "','DE')");
      s.execute("insert into carrier_business_scope (tenant_id,profile_id,business_type)"
          + " values ('" + tenant + "','" + id + "','OWN_FULFILLMENT')");
      s.execute("update carrier_logistics_profile set readiness='READY' where id='" + id + "'");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
    return id;
  }

  /** 一条适配 DE / SHOP-DE-1、**无任何限制**的渠道配置（10.1.1.2）。 */
  LogisticsChannelRow seedChannel(AccessService.Access who, String code, String name) {
    return seedChannel(who, code, name, List.of());
  }

  LogisticsChannelRow seedChannel(
      AccessService.Access who, String code, String name, List<ChannelRestrictionItem> restrictions) {
    UUID carrier = seedCarrier(who.tenantId(), code + "-CARRIER", name + " 承运商", true);
    return seedChannel(who, carrier, code, name, restrictions);
  }

  LogisticsChannelRow seedChannel(
      AccessService.Access who,
      UUID carrierId,
      String code,
      String name,
      List<ChannelRestrictionItem> restrictions) {
    return as(who, "channel.operation.create",
        () -> channels.save(new SaveChannelRequest(
            null, carrierId, code, name, "ACTIVE", "BY_WEIGHT", "USD",
            new BigDecimal("28.50"), BigDecimal.ONE, BigDecimal.ZERO, null, null, null,
            "NONE", BigDecimal.ZERO, BigDecimal.ZERO, null,
            List.of(new ChannelScopeItem("COUNTRY", "DE"), new ChannelScopeItem("SHOP", "SHOP-DE-1")),
            List.of(), restrictions, "集成测试登记", 0)));
  }

  /** 一条齐备的时效规则（0.5 ~ 30kg · DE · STANDARD）。 */
  TransitRuleRow seedRule(AccessService.Access who, UUID channelId) {
    return saveRule(who, null, channelId, "DE", "STANDARD",
        "0.5", "30", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0);
  }

  @SuppressWarnings("java:S107")
  TransitRuleRow saveRule(
      AccessService.Access who,
      UUID ruleId,
      UUID channelId,
      String country,
      String serviceLevel,
      String min,
      String max,
      int daysMin,
      int daysMax,
      String cutoff,
      OffsetDateTime from,
      OffsetDateTime to,
      String status,
      long version) {
    return as(who, "channel.operation.create",
        () -> transit.saveRule(new SaveTransitRuleRequest(
            ruleId, channelId, country, serviceLevel,
            new BigDecimal(min),
            // 上限留空 = 半开区间的最后一段（大于下限），DB 与服务层都认这个写法
            max == null ? null : new BigDecimal(max),
            daysMin, daysMax, cutoff, from, to, status, "集成测试登记", version)));
  }

  SizeLimitRow saveSizeLimit(
      AccessService.Access who,
      UUID channelId,
      String country,
      String length,
      String width,
      String height,
      String girth,
      String weight,
      long version) {
    return as(who, "channel.operation.create",
        () -> transit.saveSizeLimit(new SaveSizeLimitRequest(
            null, channelId, country,
            length == null ? null : new BigDecimal(length),
            width == null ? null : new BigDecimal(width),
            height == null ? null : new BigDecimal(height),
            girth == null ? null : new BigDecimal(girth),
            weight == null ? null : new BigDecimal(weight),
            "集成测试登记", version)));
  }

  MarkingRequirementRow saveMarking(
      AccessService.Access who,
      UUID channelId,
      String flagKind,
      String mode,
      String markingCode,
      long version) {
    return as(who, "channel.operation.create",
        () -> transit.saveMarking(new SaveMarkingRequirementRequest(
            null, channelId, flagKind, mode, markingCode, "", "集成测试登记", version)));
  }

  TransitRulePage listRules(AccessService.Access who) {
    return as(who, "channel.operation.read",
        () -> transit.rules(null, null, null, null, null, null, "DEFAULT", 0, 50));
  }

  /** 一条「不带电不带液不带磁不危险、尺寸合规、1kg」的刊登预检请求。 */
  LimitPrecheckRequest plainRequest() {
    return new LimitPrecheckRequest(
        null, "DE", List.of("HOME_GOODS"), false, false, false, false,
        new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"),
        INSIDE_WINDOW);
  }

  LimitPrecheckResult precheck(AccessService.Access who, LimitPrecheckRequest request) {
    return as(who, "channel.operation.read", () -> transit.precheck(request));
  }

  TransitEstimateResult estimate(AccessService.Access who, TransitEstimateRequest request) {
    return as(who, "channel.operation.read", () -> transit.estimate(request));
  }

  void execAsTenantA(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      st.execute("set app.current_tenant = '" + a + "'");
      st.execute(sql);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 跑一条只读查询并把结果摊平成行（RLS / 约束元数据断言用）。 */
  List<Map<String, Object>> queryRows(String sql) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var rs = s.executeQuery(sql);
      var meta = rs.getMetaData();
      var out = new ArrayList<Map<String, Object>>();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        out.add(row);
      }
      return out;
    }
  }

  // ==================================================================
  // 端到端
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 端到端：挂渠道 → 建时效与限制 → 时效估算命中 → 刊登预检 PASS")
  void endToEnd() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    TransitRuleRow rule = seedRule(actorA, channel.id());

    assertThat(rule.readiness()).isEqualTo("READY");
    assertThat(rule.channelCode()).isEqualTo("SF-DE-STD");
    assertThat(rule.weightMinKg()).isEqualByComparingTo("0.5");
    assertThat(rule.weightMaxKg()).isEqualByComparingTo("30");
    assertThat(rule.cutoffTime()).isEqualTo("18:00");
    // 🔴 断言**写回的时刻**：绑 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，
    //    而偏 8 小时的档期区间在「当前时刻」这个断言下仍然是绿的。
    assertThat(rule.effectiveFrom().toInstant()).isEqualTo(WINDOW_FROM.toInstant());
    assertThat(rule.effectiveTo().toInstant()).isEqualTo(WINDOW_TO.toInstant());

    TransitEstimateResult est =
        estimate(actorA, new TransitEstimateRequest(
            List.of(channel.id()), "DE", new BigDecimal("1"), "STANDARD", INSIDE_WINDOW));
    assertThat(est.matched()).isEqualTo(1);
    assertThat(est.unmatched()).isZero();
    assertThat(est.items()).singleElement().satisfies(item -> {
      assertThat(item.transitDaysMin()).isEqualTo(8);
      assertThat(item.transitDaysMax()).isEqualTo(12);
      assertThat(item.matchedBand()).isEqualTo("0.5 ~ 30 kg");
      assertThat(item.earliestArrival().toInstant())
          .isEqualTo(INSIDE_WINDOW.plusDays(8).toInstant());
      assertThat(item.latestArrival().toInstant())
          .isEqualTo(INSIDE_WINDOW.plusDays(12).toInstant());
    });

    LimitPrecheckResult check = precheck(actorA, plainRequest());
    assertThat(check.pass()).isEqualTo(1);
    assertThat(check.block()).isZero();
    assertThat(check.verdicts()).singleElement().satisfies(v -> {
      assertThat(v.verdict()).isEqualTo("PASS");
      assertThat(v.hits()).isEmpty();
      assertThat(v.transitDaysMin()).isEqualTo(8);
      assertThat(v.transitDaysMax()).isEqualTo(12);
    });
  }

  // ==================================================================
  // 🔴 限制是真硬拦截
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 🔴 超尺寸 / 重量上限判 BLOCK：承运商会拒收并产生额外费用")
  void oversizedParcelIsBlocked() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());
    saveSizeLimit(actorA, channel.id(), null, "60", "40", "30", null, "30", 0);

    // 长 65 > 60 ⇒ BLOCK
    LimitPrecheckResult over = precheck(actorA, withDims(new BigDecimal("65"), "15", "5"));
    assertThat(over.block()).isEqualTo(1);
    assertThat(over.verdicts().get(0).verdict()).isEqualTo("BLOCK");
    assertThat(over.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("LENGTH");
          assertThat(h.severity()).isEqualTo("BLOCK");
          assertThat(h.message()).contains("超过渠道级上限");
        });

    // 重量 31 > 30 ⇒ BLOCK（重量上限与「重量段」是两件事，段是时效承诺）
    LimitPrecheckResult heavy = precheck(actorA, withWeight(new BigDecimal("31")));
    assertThat(heavy.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("WEIGHT");
          assertThat(h.severity()).isEqualTo("BLOCK");
        });

    // 合规尺寸 ⇒ PASS
    assertThat(precheck(actorA, withDims(new BigDecimal("20"), "15", "5")).pass()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.1.1.3 🔴 国家级上限与渠道级取最严：渠道级更严时按渠道级拦")
  void stricterLimitWins() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());
    // 渠道级：长 60；国家级 DE：长 40（更严，合法）
    saveSizeLimit(actorA, channel.id(), null, "60", "40", "30", null, "30", 0);
    saveSizeLimit(actorA, channel.id(), "DE", "40", "30", "20", null, "20", 0);

    // 长 50：渠道级放行，国家级不放行 ⇒ 取最严 ⇒ BLOCK
    LimitPrecheckResult over = precheck(actorA, withDims(new BigDecimal("50"), "15", "5"));
    assertThat(over.verdicts().get(0).verdict()).isEqualTo("BLOCK");
    assertThat(over.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.severity()).isEqualTo("BLOCK");
          assertThat(h.subject()).isEqualTo("国家级 · DE");
        });

    // 长 35：两条都放行 ⇒ PASS
    assertThat(precheck(actorA, withDims(new BigDecimal("35"), "15", "5")).pass()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.1.1.3 🔴 上游禁限品类 + 本模块标记要求汇总成一个结论（不重复建表）")
  void upstreamRestrictionsAreAggregated() {
    // 上游 10.1.1.2 已把 BATTERY 判 BLOCK（本模块登记 ALLOWED 会被 V139 trigger e 拒）
    LogisticsChannelRow channel =
        seedChannel(actorA, "SF-DE-STD", "顺丰德国标准",
            List.of(
                new ChannelRestrictionItem("BATTERY", "", "BLOCK", "承运商拒收带电件"),
                new ChannelRestrictionItem("PROHIBITED_CATEGORY", "COSMETICS", "BLOCK", "化妆品专线")));
    seedRule(actorA, channel.id());

    LimitPrecheckRequest battery =
        new LimitPrecheckRequest(null, "DE", List.of("ELECTRONICS"), true, false, false, false,
            new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"),
            INSIDE_WINDOW);
    LimitPrecheckResult result = precheck(actorA, battery);
    assertThat(result.block()).isEqualTo(1);
    assertThat(result.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("BATTERY");
          assertThat(h.severity()).isEqualTo("BLOCK");
          assertThat(h.carrierReason()).contains("承运商拒收带电件");
        });

    // 禁限品类同样走上游，不在本模块另建一张表
    LimitPrecheckRequest cosmetic =
        new LimitPrecheckRequest(null, "DE", List.of("COSMETICS"), false, false, false, false,
            new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"),
            INSIDE_WINDOW);
    assertThat(precheck(actorA, cosmetic).verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("PROHIBITED_CATEGORY");
          assertThat(h.severity()).isEqualTo("BLOCK");
        });
  }

  @Test
  @DisplayName("10.1.1.3 🔴 危险品是 V136 四种之外的第五类，由本模块标记要求覆盖")
  void dangerousGoodsAreCovered() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());
    saveMarking(actorA, channel.id(), "DANGEROUS_GOODS", "BLOCK", "", 0);

    LimitPrecheckRequest withDG =
        new LimitPrecheckRequest(null, "DE", List.of("HOME_GOODS"), false, false, false, true,
            new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"),
            INSIDE_WINDOW);
    assertThat(precheck(actorA, withDG).verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("DANGEROUS_GOODS");
          assertThat(h.severity()).isEqualTo("BLOCK");
        });

    // 不带危险品 ⇒ 不命中
    assertThat(precheck(actorA, plainRequest()).pass()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.1.1.3 「必须贴标识」是 REVIEW 而不是 PASS：不拦，但不允许自动放行")
  void labelRequiredIsReviewNotPass() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());
    MarkingRequirementRow marking =
        saveMarking(actorA, channel.id(), "BATTERY", "LABEL_REQUIRED", "UN38.3", 0);
    assertThat(marking.markingCode()).isEqualTo("UN38.3");

    LimitPrecheckRequest battery =
        new LimitPrecheckRequest(null, "DE", List.of("ELECTRONICS"), true, false, false, false,
            new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"),
            INSIDE_WINDOW);
    LimitPrecheckResult result = precheck(actorA, battery);
    assertThat(result.review()).isEqualTo(1);
    assertThat(result.pass()).isZero();
    assertThat(result.block()).isZero();
    assertThat(result.verdicts().get(0).verdict()).isEqualTo("REVIEW");
    assertThat(result.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("BATTERY");
          assertThat(h.severity()).isEqualTo("REVIEW");
          assertThat(h.message()).contains("UN38.3");
        });
  }

  @Test
  @DisplayName("10.1.1.3 要求贴标识却没点名贴什么：服务层与 DB 层都拒")
  void labelRequiredNeedsMarkingCode() throws Exception {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");

    assertThatThrownBy(() -> saveMarking(actorA, channel.id(), "BATTERY", "LABEL_REQUIRED", "", 0))
        .hasMessageContaining("必须点名贴什么");

    // 绕过服务层直接写 SQL 同样被 DB CHECK 拒
    assertThatThrownBy(
            () -> execAsTenantA(
                "insert into logistics_marking_requirement"
                    + " (id,tenant_id,channel_id,flag_kind,requirement_mode,marking_code)"
                    + " values ('" + UUID.randomUUID() + "','" + a + "','" + channel.id()
                    + "','LIQUID','LABEL_REQUIRED','')"));
    // 触发器拒绝 ⇒ 一行都没进去
    assertThat(queryRows("select id from logistics_marking_requirement")).isEmpty();
  }

  // ==================================================================
  // 🔴 绕过服务层写 SQL 同样被拒（硬拦截的边界在 DB）
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 🔴 重量段重叠被拒：重叠的时效承诺会让估算结果取决于查询顺序")
  void overlappingWeightBandIsRejected() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());

    // 0.5~30 与 10~20 重叠 ⇒ 拒（服务层翻译成 422）
    assertThatThrownBy(
            () -> saveRule(actorA, null, channel.id(), "DE", "STANDARD",
                "10", "20", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0))
        .hasMessageContaining("重叠");

    // 绕过服务层直接写 SQL 同样被拒
    assertThatThrownBy(
            () -> execAsTenantA(
                "insert into logistics_transit_rule (id,tenant_id,channel_id,country_code,"
                    + " service_level,weight_min_kg,weight_max_kg,transit_days_min,transit_days_max,"
                    + " cutoff_time,readiness) values ('" + UUID.randomUUID() + "','" + a + "','"
                    + channel.id() + "','DE','STANDARD',10,20,8,12,'18:00','READY')"))
        .hasMessageContaining("重叠");

    // 相邻但不重叠（[30, 45) 接在 [0.5, 30) 之后）⇒ 放行
    assertThatNoException().isThrownBy(
        () -> saveRule(actorA, null, channel.id(), "DE", "STANDARD",
            "30", "45", 14, 18, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0));
    // 无上限段是「最后一段」的正常写法 ⇒ 放行
    assertThatNoException().isThrownBy(
        () -> saveRule(actorA, null, channel.id(), "DE", "ECONOMY",
            "0.5", null, 20, 28, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0));

    // 不同服务等级不互相冲突（承运商确实会对同一重量段给不同服务）
    assertThatNoException().isThrownBy(
        () -> saveRule(actorA, null, channel.id(), "DE", "EXPRESS",
            "0.5", "30", 3, 5, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0));
  }

  @Test
  @DisplayName("10.1.1.3 🔴 国家级上限比渠道级更宽被拒：写宽的那条只会让人以为该国更宽松")
  void looserCountryLimitIsRejected() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    saveSizeLimit(actorA, channel.id(), null, "60", "40", "30", null, "30", 0);

    assertThatThrownBy(() -> saveSizeLimit(actorA, channel.id(), "DE", "80", "40", "30", null, null, 0))
        .hasMessageContaining("更宽");

    assertThatThrownBy(
            () -> execAsTenantA(
                "insert into logistics_size_limit (id,tenant_id,channel_id,country_code,max_length_cm)"
                    + " values ('" + UUID.randomUUID() + "','" + a + "','" + channel.id()
                    + "','DE',80)"))
        .hasMessageContaining("更宽");

    // 更严的（40×30×20）⇒ 放行
    assertThatNoException()
        .isThrownBy(() -> saveSizeLimit(actorA, channel.id(), "DE", "40", "30", "20", null, null, 0));
  }

  @Test
  @DisplayName("10.1.1.3 🔴 标记要求与上游 10.1.1.2 的硬拦截打架被拒（只读对照上游）")
  void markingCannotContradictUpstreamBlock() {
    // 上游已判 BATTERY = BLOCK
    LogisticsChannelRow channel =
        seedChannel(actorA, "SF-DE-STD", "顺丰德国标准",
            List.of(new ChannelRestrictionItem("BATTERY", "", "BLOCK", "拒收带电")));

    assertThatThrownBy(
            () -> saveMarking(actorA, channel.id(), "BATTERY", "LABEL_REQUIRED", "UN38.3", 0))
        .hasMessageContaining("只能登记 BLOCK");

    assertThatThrownBy(
            () -> execAsTenantA(
                "insert into logistics_marking_requirement (id,tenant_id,channel_id,flag_kind,"
                    + " requirement_mode,marking_code) values ('" + UUID.randomUUID() + "','" + a
                    + "','" + channel.id() + "','BATTERY','ALLOWED','')"))
        .hasMessageContaining("只能登记 BLOCK");

    // 与上游一致地登记 BLOCK ⇒ 放行，且读回来标记为「与上游一致」
    MarkingRequirementRow saved = saveMarking(actorA, channel.id(), "BATTERY", "BLOCK", "", 0);
    assertThat(saved.upstreamRestrictionMode()).isEqualTo("BLOCK");
    assertThat(saved.consistentWithUpstream()).isTrue();

    // 上游没有登记限制的 flag（危险品）不受这条约束
    assertThatNoException()
        .isThrownBy(() -> saveMarking(actorA, channel.id(), "DANGEROUS_GOODS", "ALLOWED", "", 0));
  }

  @Test
  @DisplayName("10.1.1.3 🔴 时效规则的国家必须是渠道适用的国家")
  void transitCountryMustBeInChannelScope() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");

    assertThatThrownBy(
            () -> saveRule(actorA, null, channel.id(), "FR", "STANDARD",
                "0.5", "30", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0))
        .hasMessageContaining("不适配国家 FR");

    assertThatThrownBy(
            () -> execAsTenantA(
                "insert into logistics_transit_rule (id,tenant_id,channel_id,country_code,"
                    + " service_level,weight_min_kg,weight_max_kg,transit_days_min,transit_days_max,"
                    + " cutoff_time,readiness) values ('" + UUID.randomUUID() + "','" + a + "','"
                    + channel.id() + "','FR','STANDARD',0.5,30,8,12,'18:00','READY')"))
        .hasMessageContaining("不适配国家 FR");
  }

  @Test
  @DisplayName("10.1.1.3 readiness 不接受手工指定：绕过服务层谎报 READY 被 DB 拒")
  void readinessCannotBeForged() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    TransitRuleRow rule = seedRule(actorA, channel.id());

    assertThatThrownBy(
            () -> execAsTenantA(
                "update logistics_transit_rule set transit_days_max=0, readiness='READY' where id='"
                    + rule.id() + "'"))
        .hasMessageContaining("不接受手工指定");

    // 触发器拒绝 ⇒ 行没被改
    assertThat(as(actorA, "channel.operation.read", () -> transit.ruleDetail(rule.id())).transitDaysMax())
        .isEqualTo(12);
  }

  @Test
  @DisplayName("10.1.1.3 时效天数至少 1 天：承诺不出时效的规则写不进来")
  void transitDaysMustBeAtLeastOne() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");

    assertThatThrownBy(
            () -> saveRule(actorA, null, channel.id(), "DE", "STANDARD",
                "0.5", "30", 0, 0, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0))
        .hasMessageContaining("时效天数至少 1 天");

    // 重量段必须是闭区间
    assertThatThrownBy(
            () -> saveRule(actorA, null, channel.id(), "DE", "STANDARD",
                "30", "30", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0))
        .hasMessageContaining("半开区间");
  }

  @Test
  @DisplayName("10.1.1.3 长 / 宽 / 高要么都给要么都不给：半配置写不进来")
  void dimensionsAreAllOrNothing() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");

    assertThatThrownBy(
            () -> saveSizeLimit(actorA, channel.id(), null, "60", "40", null, null, null, 0))
        .hasMessageContaining("要么都给要么都不给");

    assertThatThrownBy(() -> saveSizeLimit(actorA, channel.id(), null, null, null, null, null, null, 0))
        .hasMessageContaining("至少要给一项上限");
  }

  // ==================================================================
  // 时效估算：命中不到就说命中不到
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 重量段是半开区间 [min, max)：0.5 落在 [0.5,30)，30 落在下一段")
  void weightBandIsHalfOpen() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());

    // 下限含：0.5 恰好落在 [0.5, 30) 上
    for (String weight : List.of("0.5", "12.345678", "29.999999")) {
      assertThat(
              estimate(actorA, new TransitEstimateRequest(
                  List.of(channel.id()), "DE", new BigDecimal(weight), "STANDARD",
                  INSIDE_WINDOW)).matched())
          .as("重量 %s 应命中半开区间 [0.5, 30)", weight)
          .isEqualTo(1);
    }
    // 上限不含：30 属于下一段 [30, 45)，0.49 在本段之下
    assertThat(estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("30"), "STANDARD", INSIDE_WINDOW)).matched())
        .isZero();
    for (String weight : List.of("0.49", "30.01")) {
      assertThat(
              estimate(actorA, new TransitEstimateRequest(
                  List.of(channel.id()), "DE", new BigDecimal(weight), "STANDARD",
                  INSIDE_WINDOW)).matched())
          .as("重量 %s 不在半开区间 [0.5, 30) 内", weight)
          .isZero();
    }
  }

  @Test
  @DisplayName("10.1.1.3 最后一段可以没有上限：留空上限 = 大于下限")
  void lastBandCanBeOpenEnded() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    saveRule(actorA, null, channel.id(), "DE", "STANDARD",
        "0.5", "30", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0);
    TransitRuleRow top = saveRule(actorA, null, channel.id(), "DE", "STANDARD",
        "30", null, 14, 18, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0);

    assertThat(top.readiness()).isEqualTo("READY");
    assertThat(top.weightMaxKg()).isNull();

    // 200kg 命中最后一段
    TransitEstimateResult est = estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("200"), "STANDARD", INSIDE_WINDOW));
    assertThat(est.matched()).isEqualTo(1);
    assertThat(est.items().get(0).transitDaysMin()).isEqualTo(14);
    assertThat(est.items().get(0).matchedBand()).isEqualTo("≥ 30 kg");
  }

  @Test
  @DisplayName("10.1.1.3 命中不到重量段时返回 0 条并说明：不拿相邻段凑一个数")
  void unmatchedBandIsReportedHonestly() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());

    TransitEstimateResult result =
        estimate(actorA, new TransitEstimateRequest(
            List.of(channel.id()), "DE", new BigDecimal("80"), "STANDARD", INSIDE_WINDOW));
    assertThat(result.items()).isEmpty();
    assertThat(result.matched()).isZero();
    assertThat(result.unmatched()).isEqualTo(1);
    assertThat(result.note()).contains("没有承诺");
  }

  @Test
  @DisplayName("10.1.1.3 生效区间：区间外不参与估算，区间内参与（时刻钉死，不受墙钟影响）")
  void effectiveWindowIsHonoured() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());

    // 区间内
    assertThat(estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("1"), "STANDARD", INSIDE_WINDOW)).matched())
        .isEqualTo(1);
    // 区间外
    assertThat(estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("1"), "STANDARD", OUTSIDE_WINDOW)).matched())
        .isZero();
    // 区间起点当天含、终点当天不含（[from, to) 半开区间）
    assertThat(estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("1"), "STANDARD", WINDOW_FROM)).matched())
        .isEqualTo(1);
    assertThat(estimate(actorA, new TransitEstimateRequest(
        List.of(channel.id()), "DE", new BigDecimal("1"), "STANDARD", WINDOW_TO)).matched())
        .isZero();

    // 列表行的 effectiveNow 与估算同一口径
    assertThat(listRules(actorA).items().get(0).effectiveNow()).isTrue();
  }

  @Test
  @DisplayName("10.1.1.3 该国家 + 该重量段没有时效承诺 → 刊登预检给 REVIEW（承诺不出时效）")
  void missingTransitIsReview() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    // 刻意只建 0.5~30kg 的段，查询 80kg ⇒ 命中不到
    seedRule(actorA, channel.id());

    LimitPrecheckResult result = precheck(actorA, withWeight(new BigDecimal("80")));
    assertThat(result.verdicts().get(0).verdict()).isEqualTo("REVIEW");
    assertThat(result.verdicts().get(0).transitDaysMin()).isNull();
    assertThat(result.verdicts().get(0).hits())
        .anySatisfy(h -> {
          assertThat(h.hitKind()).isEqualTo("TRANSIT_MISSING");
          assertThat(h.severity()).isEqualTo("REVIEW");
        });
  }

  // ==================================================================
  // 列表：真分页 / 真筛选 / 汇总不受分页影响
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 真分页真筛选 + 汇总不受分页影响")
  void realPagingAndSummary() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channel.id());
    saveRule(actorA, null, channel.id(), "DE", "EXPRESS", "0.5", "30", 3, 5, "18:00",
        WINDOW_FROM, WINDOW_TO, "ACTIVE", 0);
    saveRule(actorA, null, channel.id(), "DE", "ECONOMY", "30", "45", 14, 18, "18:00",
        WINDOW_FROM, WINDOW_TO, "ACTIVE", 0);
    // 停用一条：仍占列表一行，但不该参与估算
    saveRule(actorA, null, channel.id(), "DE", "STANDARD", "45", "60", 20, 25, "18:00",
        WINDOW_FROM, WINDOW_TO, "DISABLED", 0);

    TransitRulePage all =
        as(actorA, "channel.operation.read",
            () -> transit.rules(null, null, null, null, null, null, "DEFAULT", 0, 50));
    assertThat(all.total()).isEqualTo(4);
    assertThat(all.summary().total()).isEqualTo(4);
    assertThat(all.summary().ready()).isEqualTo(3);
    assertThat(all.summary().disabled()).isEqualTo(1);
    assertThat(all.summary().effectiveNow()).isEqualTo(4);
    assertThat(all.summary().countries()).isEqualTo(1);

    // 真分页：第一页 2 条，汇总仍然是全部 4 条
    TransitRulePage first =
        as(actorA, "channel.operation.read",
            () -> transit.rules(null, null, null, null, null, null, "DEFAULT", 0, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(first.summary().total()).isEqualTo(4);

    TransitRulePage second =
        as(actorA, "channel.operation.read",
            () -> transit.rules(null, null, null, null, null, null, "DEFAULT", 1, 2));
    assertThat(second.items()).hasSize(2);

    // 真筛选
    TransitRulePage express =
        as(actorA, "channel.operation.read",
            () -> transit.rules(null, null, null, "EXPRESS", null, null, "DEFAULT", 0, 50));
    assertThat(express.items()).singleElement()
        .satisfies(r -> assertThat(r.serviceLevel()).isEqualTo("EXPRESS"));

    TransitRulePage disabled =
        as(actorA, "channel.operation.read",
            () -> transit.rules(null, null, null, null, null, "DISABLED", "DEFAULT", 0, 50));
    assertThat(disabled.items()).singleElement()
        .satisfies(r -> assertThat(r.readiness()).isEqualTo("DISABLED"));

    // 关键词：搜不到与搜得到
    assertThat(as(actorA, "channel.operation.read",
        () -> transit.rules("没有这个渠道", null, null, null, null, null, "DEFAULT", 0, 50)).items())
        .isEmpty();
    assertThat(as(actorA, "channel.operation.read",
        () -> transit.rules("SF-DE-STD", null, null, null, null, null, "DEFAULT", 0, 50)).total())
        .isEqualTo(4);
  }

  @Test
  @DisplayName("10.1.1.3 乐观锁：版本不对的写入被拒")
  void optimisticLockRejectsStaleWrites() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    TransitRuleRow rule = seedRule(actorA, channel.id());

    assertThatThrownBy(
            () -> saveRule(actorA, rule.id(), channel.id(), "DE", "STANDARD",
                "0.5", "30", 9, 14, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", rule.version() + 5))
        .hasMessageContaining("已被他人修改");

    TransitRuleRow updated =
        saveRule(actorA, rule.id(), channel.id(), "DE", "STANDARD",
            "0.5", "30", 9, 14, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", rule.version());
    assertThat(updated.transitDaysMin()).isEqualTo(9);
    assertThat(updated.version()).isEqualTo(rule.version() + 1);
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求）
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    LogisticsChannelRow channelA = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channelA.id());
    LogisticsChannelRow channelB = seedChannel(actorB, "FEDEX-DE-STD", "FedEx 德国标准");
    TransitRuleRow ruleB =
        seedRule(actorB, channelB.id());

    assertThat(listRules(actorA).items()).extracting(TransitRuleRow::channelCode)
        .containsExactly("SF-DE-STD");
    assertThat(listRules(actorB).items()).extracting(TransitRuleRow::channelCode)
        .containsExactly("FEDEX-DE-STD");

    // B 读自己的规则：读得到（证明上面那条不是「什么都不返回」）
    assertThat(as(actorB, "channel.operation.read", () -> transit.ruleDetail(ruleB.id())).id())
        .isEqualTo(ruleB.id());

    // B 用 A 的 id 读详情：跨租户与不存在返回**逐字相同**的 404
    UUID aRuleId =
        as(actorA, "channel.operation.read", () -> transit.rules(null, null, null, null, null, null, "DEFAULT", 0, 50))
            .items().get(0).id();
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> transit.ruleDetail(aRuleId)))
        .hasMessageContaining("时效规则不存在");

    // 跨租户挂载不可能：复合 FK 带 tenant_id
    assertThatThrownBy(
            () -> saveRule(actorB, null, channelA.id(), "DE", "STANDARD",
                "0.5", "30", 8, 12, "18:00", WINDOW_FROM, WINDOW_TO, "ACTIVE", 0))
        .hasMessageContaining("渠道配置不存在");
  }

  @Test
  @DisplayName("10.1.1.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    LogisticsChannelRow channelA = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    UUID aRuleId = seedRule(actorA, channelA.id()).id();
    LogisticsChannelRow channelB = seedChannel(actorB, "FEDEX-DE-STD", "FedEx 德国标准");
    UUID bRuleId = seedRule(actorB, channelB.id()).id();

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据
    http.perform(get("/channel/logistics-transit-limits/transit-rules")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(aRuleId.toString())))
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bRuleId.toString()))));

    // 判定端点同样不吃伪造头
    http.perform(post("/channel/logistics-transit-limits/precheck")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(new ObjectMapper().writeValueAsBytes(Map.of(
                "channelIds", List.of(),
                "countryCode", "DE",
                "categoryCodes", List.of("HOME_GOODS")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdicts[*].channelCode",
            org.hamcrest.Matchers.hasItem("SF-DE-STD")))
        .andExpect(jsonPath("$.verdicts[*].channelCode",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("FEDEX-DE-STD"))));

    // 无会话一律 401
    http.perform(get("/channel/logistics-transit-limits/transit-rules")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.1.1.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    LogisticsChannelRow channelA = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    seedRule(actorA, channelA.id());
    LogisticsChannelRow channelB = seedChannel(actorB, "FEDEX-DE-STD", "FedEx 德国标准");
    seedRule(actorB, channelB.id());

    for (int round = 0; round < 3; round++) {
      assertThat(listRules(actorA).items()).extracting(TransitRuleRow::channelCode)
          .containsExactly("SF-DE-STD");
      assertThat(listRules(actorB).items()).extracting(TransitRuleRow::channelCode)
          .containsExactly("FEDEX-DE-STD");
      assertThat(listRules(actorA).items()).extracting(TransitRuleRow::channelCode)
          .containsExactly("SF-DE-STD");
    }

    // 判定端点同样不串租户
    assertThat(precheck(actorA, plainRequest()).verdicts()).extracting(LimitPrecheckVerdict::channelCode)
        .containsExactly("SF-DE-STD");
    assertThat(precheck(actorB, plainRequest()).verdicts()).extracting(LimitPrecheckVerdict::channelCode)
        .containsExactly("FEDEX-DE-STD");
  }

  @Test
  @DisplayName("10.1.1.3 RLS：表级策略存在且 FORCE，业务表 tenant_id NOT NULL")
  void rlsPoliciesExist() throws Exception {
    for (String table :
        List.of("logistics_transit_rule", "logistics_size_limit", "logistics_marking_requirement")) {
      List<Map<String, Object>> rows = queryRows(
          "select relrowsecurity, relforcerowsecurity from pg_class where relname = '" + table + "'");
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("relrowsecurity")).isEqualTo(true);
      assertThat(rows.get(0).get("relforcerowsecurity")).isEqualTo(true);
      // tenant_id NOT NULL
      List<Map<String, Object>> notNull = queryRows(
          "select is_nullable from information_schema.columns"
              + " where table_name = '" + table + "' and column_name = 'tenant_id'");
      assertThat(notNull).singleElement().satisfies(r -> assertThat(r.get("is_nullable")).isEqualTo("NO"));
    }
  }

  // ==================================================================
  // 端点形状
  // ==================================================================

  @Test
  @DisplayName("10.1.1.3 能力声明把未交付的上下游显式写出来（不用空列表冒充）")
  void capabilitiesDeclareGaps() throws Exception {
    http.perform(get("/channel/logistics-transit-limits/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='transit_estimation')].available",
            org.hamcrest.Matchers.hasItem(true)))
        // 上游 3.7.2 未交付：available=false 且必须点名归属模块与影响
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='sku_safety_attributes')].available",
            org.hamcrest.Matchers.hasItem(false)))
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='sku_safety_attributes')].ownerModule",
            org.hamcrest.Matchers.hasItem("3.7.2")))
        // 下游 10.6.2.1 未交付
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='transit_analysis')].ownerModule",
            org.hamcrest.Matchers.hasItem("10.6.2.1")));
  }

  @Test
  @DisplayName("10.1.1.3 撤下规则 / 上限 / 标记要求：只删自己的行，不动渠道配置")
  void deleteRemovesOnlyOwnRows() {
    LogisticsChannelRow channel = seedChannel(actorA, "SF-DE-STD", "顺丰德国标准");
    TransitRuleRow rule = seedRule(actorA, channel.id());
    SizeLimitRow size = saveSizeLimit(actorA, channel.id(), null, "60", "40", "30", null, "30", 0);
    MarkingRequirementRow marking =
        saveMarking(actorA, channel.id(), "DANGEROUS_GOODS", "BLOCK", "", 0);

    as(actorA, "channel.operation.delete", () -> {
      transit.deleteRule(rule.id());
      return null;
    });
    as(actorA, "channel.operation.delete", () -> {
      transit.deleteSizeLimit(size.id());
      return null;
    });
    as(actorA, "channel.operation.delete", () -> {
      transit.deleteMarking(marking.id());
      return null;
    });

    assertThatThrownBy(
            () -> as(actorA, "channel.operation.read", () -> transit.ruleDetail(rule.id())))
        .hasMessageContaining("时效规则不存在");
    // 上游渠道配置一行没少
    assertThat(as(actorA, "channel.operation.read", () -> channels.detail(channel.id())).channelCode())
        .isEqualTo("SF-DE-STD");
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  LimitPrecheckRequest withDims(BigDecimal length, String width, String height) {
    return new LimitPrecheckRequest(
        null, "DE", List.of("HOME_GOODS"), false, false, false, false,
        new BigDecimal("1"), length, new BigDecimal(width), new BigDecimal(height), INSIDE_WINDOW);
  }

  LimitPrecheckRequest withWeight(BigDecimal weight) {
    return new LimitPrecheckRequest(
        null, "DE", List.of("HOME_GOODS"), false, false, false, false,
        weight, new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("5"), INSIDE_WINDOW);
  }
}
