package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static com.starcon.channel.AdTargetingModels.*;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 14.1.3.2 投放定位。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 解读归一真的落库</b>：平台原文（上游 V281 的 targeting_settings）被拆解成
 *       GEO / AUDIENCE / DEVICE / DAYPART / MATCH_TYPE；认不出来的键落 <b>UNMAPPED</b>
 *       （显式报出，不丢弃）；时段取值解析不出窗口 ⇒ <b>窗口留白</b>（不编窗口）
 *   <li><b>🔴 效果对比表是真聚合</b>：真分页 / 真筛选 / 真排序；派生比率分母为 0 ⇒
 *       <b>null（不可比）</b>；<b>跨币种不相加</b>
 *   <li><b>🔴 调整建议带依据</b>：每条建议都有 evidence（真实数字）+ basis（口径与阈值）；
 *       样本不足 ⇒ actionable=false
 *   <li><b>🔴 收窄投放有证据门槛</b>：没有浪费证据 ⇒ 409；人工覆盖要显式 override + 理由
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md：断言写回值，不能只断言没抛异常）
 *   <li><b>🔴 数量用 NUMERIC(19,6)</b>：时段小时数与出价系数都是 6 位小数（无浮点）
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdTargetingIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a;
  AccessService.Access actorA;
  String bearerA;

  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);
  static final String FROM = "2026-10-01";
  static final String TO = "2026-10-31";

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    bearerA = "Bearer at-a-" + a;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actorA = actors.get(bearerA);
  }

  /** 播种一个 2.1.2.1 的平台默认映射（全局码表）。 */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdTargetingService service;
  @Autowired AdGroupManagementService groups;
  @Autowired AdCampaignService campaigns;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以租户身份**查**一个值（这些表开了 FORCE RLS，管理员连接查出来永远是 0 行）。 */
  static String execOneAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getString(1);
        }
        throw new IllegalStateException("查询没有返回行：" + sql);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等）。 */
  String seedShop(UUID tenant, String code) {
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + UUID.randomUUID()
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 通过**上游 14.1.2.2** 登记一个广告类型档案（归一口径由它带下来）。 */
  UUID saveProfile(AccessService.Access who, UUID platformId, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
            null, platformId, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  /** 通过**上游 14.1.1.1** 登记一个活动。 */
  UUID seedCampaign(AccessService.Access who, String shop, UUID profileId, String externalId, String name) {
    as(who, "channel.operation.create",
        () -> campaigns.importCampaigns(new ImportRequest(
            AMAZON, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-1"),
            List.of(
                new ImportCampaign(
                    externalId, name, profileId, "ENABLED", "USD",
                    new BigDecimal("100.0000"), null, null,
                    List.of(
                        new ImportDailyMetric(
                            DAY_1, new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("5"),
                            new BigDecimal("120.0000"), new BigDecimal("600.0000"))),
                    "测试登记")))));
    return UUID.fromString(
        execOneAsTenant(
            who.tenantId(),
            "select id from ad_campaign where tenant_id = '" + who.tenantId()
                + "' and external_campaign_id = '" + externalId + "'"));
  }

  /** 新建一个广告组（上游 14.1.2.1 的真实写入口，带平台原文定位串）。 */
  UUID createGroup(
      AccessService.Access who, UUID campaignId, String name, String targetingSettings, String matchType) {
    return as(who, "channel.operation.create",
        () -> groups.create(new AdGroupManagementModels.AdGroupCreateRequest(
                campaignId, name, new BigDecimal("1.0000"), null, new BigDecimal("50.0000"),
                "AUTO", matchType, targetingSettings, "新建测试")))
        .groupId();
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 能力声明：解读 / 对比表 / 建议 / 收窄 / 时段画像已交付；平台回写与执行出价调整显式未交付")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdTargetingCapabilityDimension> dims = new java.util.HashMap<>();
    caps.dimensions().forEach(d -> dims.put(d.dimension(), d));

    assertThat(dims.get("targeting_settings").available()).isTrue();
    assertThat(dims.get("targeting_interpretation").available()).isTrue();
    assertThat(dims.get("targeting_metrics").available()).isTrue();
    assertThat(dims.get("effect_comparison").available()).isTrue();
    assertThat(dims.get("adjustment_suggestions").available()).isTrue();
    assertThat(dims.get("narrow_delivery").available()).isTrue();
    assertThat(dims.get("daypart_profile").available()).isTrue();

    // 🔴 未交付段必须显式 false + ownerModule
    assertThat(dims.get("platform_writeback").available()).isFalse();
    assertThat(dims.get("bid_adjustment_execution").available()).isFalse();
    assertThat(dims.get("bid_adjustment_execution").ownerModule()).contains("14.2.1.1");
    assertThat(dims.get("platform_targeting_sync").available()).isFalse();

    // 🔴 护栏公开
    assertThat(caps.guardrails().minBidFactor()).isEqualByComparingTo("0.1");
    assertThat(caps.guardrails().maxBidFactor()).isEqualByComparingTo("1.5");
    assertThat(caps.guardrails().minSpendForSuggestion()).isEqualByComparingTo("10");
    assertThat(caps.guardrails().targetAcos()).isEqualByComparingTo("0.3");
    assertThat(caps.guardrails().dimensionSpreadFactor()).isEqualByComparingTo("2");
    assertThat(caps.guardrails().maxDimensionsPerInterpret()).isEqualTo(50);

    // 🔴 归一维度全集里 UNMAPPED 是**显式合法值**
    assertThat(caps.dimensionValues()).contains("GEO", "AUDIENCE", "DEVICE", "DAYPART", "MATCH_TYPE", "UNMAPPED");
  }

  // ==================================================================
  // 2. 🔴 解读平台原文 → 归一定位
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 解读：平台原文拆解成归一定位维度，认不出来的键落 UNMAPPED，时段窗口解析不出就留白")
  void interpretNormalizesPlatformRaw() {
    String shop = seedShop(a, "AT-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-1", "投放定位活动");
    UUID groupId =
        createGroup(
            actorA,
            campaignId,
            "定位组",
            "geo=US;audience=premium;device=mobile;schedule=09:00-17:00;weird=zzz",
            "BROAD");

    // ---- 先只预览（apply 不给 = 不落库）----
    var preview =
        as(actorA, "channel.operation.create",
            () -> service.interpret(new AdTargetingInterpretRequest(
                campaignId, groupId, null, null, false, "预览解读")));
    assertThat(preview.applied()).isFalse();
    assertThat(preview.appliedSettings()).isEmpty();
    assertThat(preview.items()).hasSize(6); // geo/audience/device/schedule/weird + match_type
    assertThat(countAsTenant(a, "select count(*) from ad_targeting_setting")).isZero();

    // ---- 真正落库 ----
    var result =
        as(actorA, "channel.operation.create",
            () -> service.interpret(new AdTargetingInterpretRequest(
                campaignId, groupId, null, null, true, "解读并落库")));
    assertThat(result.applied()).isTrue();
    assertThat(result.appliedSettings()).hasSize(6);

    // 🔴 归一后的维度与取值
    assertThat(result.items())
        .filteredOn(r -> r.dimension().equals("GEO"))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.dimensionValue()).isEqualTo("US");
          assertThat(r.platformRawKey()).isEqualTo("geo");
          assertThat(r.platformRawValue()).isEqualTo("US");
          assertThat(r.recognized()).isTrue();
        });
    assertThat(result.items())
        .filteredOn(r -> r.dimension().equals("AUDIENCE"))
        .singleElement()
        .satisfies(r -> assertThat(r.dimensionValue()).isEqualTo("PREMIUM"));
    assertThat(result.items())
        .filteredOn(r -> r.dimension().equals("DEVICE"))
        .singleElement()
        .satisfies(r -> assertThat(r.dimensionValue()).isEqualTo("MOBILE"));
    assertThat(result.items())
        .filteredOn(r -> r.dimension().equals("MATCH_TYPE"))
        .singleElement()
        .satisfies(r -> assertThat(r.dimensionValue()).isEqualTo("BROAD"));

    // 🔴 时段：窗口解析出来 + 小时数是 6 位小数（数量 NUMERIC(19,6)，无浮点）
    var daypart =
        result.items().stream()
            .filter(r -> r.dimension().equals("DAYPART"))
            .findFirst()
            .orElseThrow();
    assertThat(daypart.recognized()).isTrue();
    assertThat(daypart.daypartStartHour()).isEqualTo(9);
    assertThat(daypart.daypartEndHour()).isEqualTo(17);
    assertThat(daypart.daypartHours()).isEqualByComparingTo("8.000000");
    assertThat(daypart.daypartCrossesMidnight()).isFalse();

    // 🔴 认不出来的键 ⇒ UNMAPPED（**不是兜底，也不丢弃**）
    var unmapped =
        result.items().stream()
            .filter(r -> r.dimension().equals("UNMAPPED"))
            .findFirst()
            .orElseThrow();
    assertThat(unmapped.recognized()).isFalse();
    assertThat(unmapped.platformRawKey()).isEqualTo("weird");
    assertThat(unmapped.dimensionValue()).isEqualTo("ZZZ");
    assertThat(result.unrecognizedCount()).isEqualTo(1);

    // 🔴 真的落库了：维度、原文、窗口、来源都对
    String row =
        execOneAsTenant(
            a,
            "select dimension || '|' || dimension_value || '|' || platform_raw_key || '|' || source"
                + " || '|' || coalesce(daypart_hours::text,'') from ad_targeting_setting"
                + " where dimension = 'DAYPART'");
    assertThat(row).isEqualTo("DAYPART|09:00-17:00|schedule|PLATFORM|8.000000");

    // 🔴 幂等：再解读一次不会造出第二条
    var again =
        as(actorA, "channel.operation.create",
            () -> service.interpret(new AdTargetingInterpretRequest(
                campaignId, groupId, null, null, true, "重复解读")));
    assertThat(again.appliedSettings()).isEmpty();
    assertThat(again.skippedCount()).isEqualTo(6);
    assertThat(countAsTenant(a, "select count(*) from ad_targeting_setting")).isEqualTo(6);
  }

  @Test
  @DisplayName("14.1.3.2 解读：时段取值解析不出小时窗口 ⇒ 窗口留白（不编窗口出来）")
  void interpretLeavesUnparsableDaypartBlank() {
    var parsed =
        AdTargetingService.parseTargeting("daypart=WHENEVER", null);
    assertThat(parsed).hasSize(1);
    assertThat(parsed.get(0).dimension()).isEqualTo("DAYPART");
    assertThat(parsed.get(0).recognized()).isFalse();
    assertThat(parsed.get(0).startHour()).isNull();
    assertThat(parsed.get(0).hours()).isNull();
    assertThat(parsed.get(0).note()).contains("解析不出小时窗口");
  }

  @Test
  @DisplayName("14.1.3.2 解读：时段窗口解析（跨零点 / 命名时段 / 小数小时）")
  void daypartWindowParsing() {
    var cross = AdTargetingService.parseDaypart("22:00-02:00");
    assertThat(cross.startHour()).isEqualTo(22);
    assertThat(cross.endHour()).isEqualTo(2);
    assertThat(cross.crossesMidnight()).isTrue();
    assertThat(cross.hours()).isEqualByComparingTo("4.000000");

    var morning = AdTargetingService.parseDaypart("MORNING");
    assertThat(morning.startHour()).isEqualTo(6);
    assertThat(morning.endHour()).isEqualTo(12);
    assertThat(morning.hours()).isEqualByComparingTo("6.000000");

    var fractional = AdTargetingService.parseDaypart("09:15-17:45");
    assertThat(fractional.hours()).isEqualByComparingTo("8.500000");

    var pm = AdTargetingService.parseDaypart("9am-5pm");
    assertThat(pm.startHour()).isEqualTo(9);
    assertThat(pm.endHour()).isEqualTo(17);
    assertThat(pm.hours()).isEqualByComparingTo("8.000000");

    assertThat(AdTargetingService.parseDaypart("WHENEVER")).isNull();
  }

  // ==================================================================
  // 3. 定位设置的增删改查
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 定位设置：新建 / 改 / 归档真的落库，且留痕")
  void targetingSettingsCrudLandsRealRows() {
    String shop = seedShop(a, "AT-AMZ-2");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-2", "定位设置活动");

    var created =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "us", "geo", "US", null,
                OffsetDateTime.parse("2026-10-01T00:00:00+08:00"), null, "新建定位")));

    assertThat(created.id()).isNotNull();
    assertThat(created.scopeKind()).isEqualTo("CAMPAIGN");
    assertThat(created.groupId()).isNull();
    assertThat(created.dimension()).isEqualTo("GEO");
    // 🔴 取值归一：去空白 + 大写
    assertThat(created.dimensionValue()).isEqualTo("US");
    assertThat(created.status()).isEqualTo("ACTIVE");
    assertThat(created.bidFactor()).isEqualByComparingTo("1");
    // 🔴 本地写 ≠ 平台侧已改
    assertThat(created.pendingPlatformWriteback()).isFalse();

    // 🔴 时间字段写回值正确（不能只断言没抛异常）
    assertThat(created.effectiveFrom())
        .isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00+08:00"));
    String storedInstant =
        execOneAsTenant(
            a,
            "select extract(epoch from effective_from)::bigint from ad_targeting_setting where id = '"
                + created.id() + "'");
    assertThat(storedInstant)
        .isEqualTo(
            String.valueOf(
                OffsetDateTime.parse("2026-10-01T00:00:00+08:00").toEpochSecond()));

    // ---- 改（部分更新）----
    var updated =
        as(actorA, "channel.operation.update",
            () -> service.update(
                created.id().toString(),
                new AdTargetingUpdateRequest(
                    null, "PAUSED", new BigDecimal("0.800000"), null, null, null, null, "改系数")));
    assertThat(updated.status()).isEqualTo("PAUSED");
    assertThat(updated.bidFactor()).isEqualByComparingTo("0.800000");
    // 🔴 改过 ⇒ 标上「本地已改、尚未回写平台」
    assertThat(updated.pendingPlatformWriteback()).isTrue();
    assertThat(updated.localChangedAt()).isNotNull();
    // 🔴 没给的字段不改
    assertThat(updated.dimensionValue()).isEqualTo("US");

    // ---- 归档 ----
    var archived =
        as(actorA, "channel.operation.delete",
            () -> service.archive(created.id().toString(), new AdTargetingArchiveRequest("归档")));
    assertThat(archived.status()).isEqualTo("ARCHIVED");
    assertThat(countAsTenant(a, "select count(*) from ad_targeting_setting")).isEqualTo(1);

    // ---- 留痕：三次写都留了痕 ----
    var ops =
        as(actorA, "channel.operation.read",
            () -> service.operations(created.id().toString(), 0, 50));
    assertThat(ops.total()).isEqualTo(3);
    assertThat(ops.items())
        .extracting(AdTargetingOperationRow::operation)
        .containsExactlyInAnyOrder("CREATE_SETTING", "UPDATE_SETTING", "ARCHIVE_SETTING");
  }

  // ==================================================================
  // 4. 🔴 定位维度效果数据同步
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 效果数据同步：真落库；维度由设置决定；批次幂等；逐条拒绝")
  void ingestMetricsIsRealAndIdempotent() {
    String shop = seedShop(a, "AT-AMZ-3");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-3", "效果数据活动");
    var setting =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "US", "geo", "US", null, null, null, "新建")));

    var result =
        as(actorA, "channel.operation.create",
            () -> service.ingestMetrics(
                new AdTargetingMetricBatchRequest(
                    List.of(
                        new AdTargetingMetricEntry(
                            setting.id(), DAY_1, 1000L, 50L, 5,
                            new BigDecimal("120.0000"), new BigDecimal("600.0000"), "USD",
                            OffsetDateTime.parse("2026-10-02T00:00:00+08:00"), "")),
                    "batch-at-1", "PLATFORM", "src-1", "同步测试")));

    assertThat(result.accepted()).isEqualTo(1);
    assertThat(result.rejected()).isZero();
    assertThat(result.replaced()).isZero();

    // 🔴 真的落库了：维度/归属由**设置**决定
    String row =
        execOneAsTenant(
            a,
            "select dimension || '|' || dimension_value || '|' || currency || '|' || spend::text"
                + " || '|' || (extract(epoch from synced_at)::bigint)::text from ad_targeting_metric"
                + " where setting_id = '" + setting.id() + "'");
    assertThat(row).startsWith("GEO|US|USD|120.0000|");
    // 🔴 时间字段写回值正确（+08:00 的时刻写进去还是那个时刻，不是偏 −28800s）
    assertThat(row).endsWith("|" + OffsetDateTime.parse("2026-10-02T00:00:00+08:00").toEpochSecond());

    // 🔴 同一批次重投 ⇒ 不重复落
    var again =
        as(actorA, "channel.operation.create",
            () -> service.ingestMetrics(
                new AdTargetingMetricBatchRequest(
                    List.of(
                        new AdTargetingMetricEntry(
                            setting.id(), DAY_1, 1000L, 50L, 5,
                            new BigDecimal("120.0000"), new BigDecimal("600.0000"), "USD", null, "")),
                    "batch-at-1", "PLATFORM", "src-1", "重复同步")));
    assertThat(again.accepted()).isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_targeting_metric")).isEqualTo(1);

    // 🔴 逐条拒绝：点击数大于展示数 / 币种不一致
    var mixed =
        as(actorA, "channel.operation.create",
            () -> service.ingestMetrics(
                new AdTargetingMetricBatchRequest(
                    List.of(
                        new AdTargetingMetricEntry(
                            setting.id(), DAY_2, 10L, 20L, 1,
                            new BigDecimal("5.0000"), new BigDecimal("10.0000"), "USD", null, ""),
                        new AdTargetingMetricEntry(
                            setting.id(), DAY_2, 10L, 2L, 1,
                            new BigDecimal("5.0000"), new BigDecimal("10.0000"), "EUR", null, "")),
                    "batch-at-2", "PLATFORM", "src-2", "脏数据")));
    assertThat(mixed.rejected()).isEqualTo(2);
    assertThat(mixed.rejectedMessages()).hasSize(2);
    assertThat(mixed.accepted()).isZero();
  }

  // ==================================================================
  // 5. 🔴 定位效果对比表
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 对比表：真聚合 + 真分页 + 真筛选 + 真排序；分母为 0 的比率是 null；跨币种不相加")
  void effectComparisonIsRealAggregation() {
    String shop = seedShop(a, "AT-AMZ-4");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-4", "对比表活动");

    var us =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "US", "geo", "US", null, null, null, "新建 US")));
    var ca =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "CA", "geo", "CA", null, null, null, "新建 CA")));
    // 🔴 另一个币种必须挂在**另一条定位设置**上：一条设置的指标只能是同一个币种
    var gb =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "GB", "geo", "GB", null, null, null, "新建 GB")));

    as(actorA, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingMetricBatchRequest(
                List.of(
                    new AdTargetingMetricEntry(
                        us.id(), DAY_1, 1000L, 100L, 10,
                        new BigDecimal("100.0000"), new BigDecimal("500.0000"), "USD", null, ""),
                    // 🔴 零转化 + 零销售额 ⇒ 无转化花费（ACOS 不可比）
                    new AdTargetingMetricEntry(
                        ca.id(), DAY_1, 500L, 20L, 0,
                        new BigDecimal("200.0000"), new BigDecimal("0.0000"), "USD", null, ""),
                    // 另一个币种：跨币种不能相加
                    new AdTargetingMetricEntry(
                        gb.id(), DAY_2, 300L, 30L, 3,
                        new BigDecimal("90.0000"), new BigDecimal("450.0000"), "EUR", null, "")),
                "batch-at-3", "PLATFORM", "src-3", "对比数据")));

    var page =
        as(actorA, "channel.operation.read",
            () -> service.effects(null, null, "GEO", null, null, FROM, TO, "spendDesc", 0, 50));

    // 🔴 真聚合：两行（US/USD 与 CA/USD）—— CA 有 USD 与 EUR 两行
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(3);

    var usRow =
        page.items().stream()
            .filter(r -> r.dimensionValue().equals("US"))
            .findFirst()
            .orElseThrow();
    assertThat(usRow.impressions()).isEqualTo(1000L);
    assertThat(usRow.clicks()).isEqualTo(100L);
    assertThat(usRow.conversions()).isEqualTo(10L);
    assertThat(usRow.spend()).isEqualByComparingTo("100.0000");
    assertThat(usRow.sales()).isEqualByComparingTo("500.0000");
    // 🔴 派生比率
    assertThat(usRow.ctr()).isEqualByComparingTo("0.1");
    assertThat(usRow.cpc()).isEqualByComparingTo("1");
    assertThat(usRow.cvr()).isEqualByComparingTo("0.1");
    assertThat(usRow.acos()).isEqualByComparingTo("0.2");
    assertThat(usRow.roas()).isEqualByComparingTo("5");
    assertThat(usRow.wastedSpend()).isEqualByComparingTo("0");
    assertThat(usRow.scopeKind()).isEqualTo("CAMPAIGN");
    assertThat(usRow.dataAsOf()).isNotNull();

    var caUsd =
        page.items().stream()
            .filter(r -> r.dimensionValue().equals("CA") && r.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    // 🔴 销售额为 0 ⇒ ACOS 是 **null（不可比）**，不是 0
    assertThat(caUsd.acos()).isNull();
    assertThat(caUsd.sales()).isEqualByComparingTo("0.0000");
    // 🔴 无转化花费 = 转化数为 0 的花费合计
    assertThat(caUsd.wastedSpend()).isEqualByComparingTo("200.0000");
    assertThat(caUsd.wastedRatio()).isEqualByComparingTo("1");

    // 🔴 真排序：花费从高到低
    assertThat(page.items().get(0).spend()).isEqualByComparingTo("200.0000");

    // 🔴 真分页
    var firstPage =
        as(actorA, "channel.operation.read",
            () -> service.effects(null, null, "GEO", null, null, FROM, TO, "spendDesc", 0, 1));
    var secondPage =
        as(actorA, "channel.operation.read",
            () -> service.effects(null, null, "GEO", null, null, FROM, TO, "spendDesc", 1, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(secondPage.items()).hasSize(1);
    assertThat(firstPage.items().get(0).dimensionValue()).isNotEqualTo(secondPage.items().get(0).dimensionValue());

    // 🔴 真筛选：按币种
    var eurOnly =
        as(actorA, "channel.operation.read",
            () -> service.effects(null, null, null, null, "EUR", FROM, TO, "spendDesc", 0, 50));
    assertThat(eurOnly.total()).isEqualTo(1);
    assertThat(eurOnly.items().get(0).currency()).isEqualTo("EUR");

    // 🔴 汇总：金额**按币种分列**，不跨币种相加；计数才相加
    var summary = page.summary();
    assertThat(summary.spendByCurrency()).containsOnlyKeys("USD", "EUR");
    assertThat(summary.spendByCurrency().get("USD")).isEqualByComparingTo("300.0000");
    assertThat(summary.spendByCurrency().get("EUR")).isEqualByComparingTo("90.0000");
    assertThat(summary.impressions()).isEqualTo(1800L);
    assertThat(summary.clicks()).isEqualTo(150L);
    assertThat(summary.conversions()).isEqualTo(13L);
    assertThat(summary.note()).contains("跨币种");
  }

  // ==================================================================
  // 6. 🔴 调整建议
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 调整建议：无效定位 / ACOS 超标 / 表现好 / 维度内差异，每条都带依据数值")
  void suggestionsCarryEvidence() {
    String shop = seedShop(a, "AT-AMZ-5");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-5", "建议活动");

    var waste =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "WASTE", "geo", "WASTE", null, null, null, "无效定位")));
    var over =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "OVER", "geo", "OVER", null, null, null, "超标定位")));
    var good =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "GOOD", "geo", "GOOD", null, null, null, "表现好")));
    var thin =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "THIN", "geo", "THIN", null, null, null, "样本不足")));

    as(actorA, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingMetricBatchRequest(
                List.of(
                    // 花了钱、零转化、零销售 ⇒ 无效定位
                    new AdTargetingMetricEntry(
                        waste.id(), DAY_1, 1000L, 80L, 0,
                        new BigDecimal("150.0000"), new BigDecimal("0.0000"), "USD", null, ""),
                    // ACOS = 100/100 = 1.0 > 目标 0.3 ⇒ 超标
                    new AdTargetingMetricEntry(
                        over.id(), DAY_1, 1000L, 100L, 4,
                        new BigDecimal("100.0000"), new BigDecimal("100.0000"), "USD", null, ""),
                    // ACOS = 0.1 < 目标 0.3 ⇒ 表现好
                    new AdTargetingMetricEntry(
                        good.id(), DAY_1, 1000L, 100L, 10,
                        new BigDecimal("50.0000"), new BigDecimal("500.0000"), "USD", null, ""),
                    // 样本不足：点击数没过门槛
                    new AdTargetingMetricEntry(
                        thin.id(), DAY_1, 20L, 2L, 0,
                        new BigDecimal("30.0000"), new BigDecimal("0.0000"), "USD", null, "")),
                "batch-at-5", "PLATFORM", "src-5", "建议数据")));

    var result =
        as(actorA, "channel.operation.read",
            () -> service.suggestions(null, null, "GEO", FROM, TO));

    assertThat(result.count()).isGreaterThanOrEqualTo(3);

    // 🔴 无效定位 ⇒ 收窄，**依据里有真实数字**
    var wasted =
        result.items().stream()
            .filter(s -> s.dimensionValue().equals("WASTE"))
            .findFirst()
            .orElseThrow();
    assertThat(wasted.ruleId()).isEqualTo("WASTED_TARGETING");
    assertThat(wasted.action()).isEqualTo("NARROW");
    assertThat(wasted.severity()).isEqualTo("HIGH");
    assertThat(wasted.actionable()).isTrue();
    assertThat(wasted.spend()).isEqualByComparingTo("150.0000");
    assertThat(wasted.wastedSpend()).isEqualByComparingTo("150.0000");
    assertThat(wasted.evidence()).isNotEmpty();
    assertThat(wasted.evidence()).anyMatch(e -> e.contains("150.00"));
    assertThat(wasted.basis()).contains("WASTED_TARGETING");

    // 🔴 ACOS 超标 ⇒ 降价 + 系数（目标 ACOS / 当前 ACOS = 0.3）
    var overSuggestion =
        result.items().stream()
            .filter(s -> s.dimensionValue().equals("OVER"))
            .findFirst()
            .orElseThrow();
    assertThat(overSuggestion.action()).isEqualTo("REDUCE_BID");
    assertThat(overSuggestion.acos()).isEqualByComparingTo("1.0");
    assertThat(overSuggestion.bidFactor()).isEqualByComparingTo("0.3");
    assertThat(overSuggestion.evidence()).anyMatch(e -> e.contains("目标 ACOS"));

    // 🔴 表现好 ⇒ 加价（系数上限 1.5）
    var goodSuggestion =
        result.items().stream()
            .filter(s -> s.dimensionValue().equals("GOOD"))
            .findFirst()
            .orElseThrow();
    assertThat(goodSuggestion.action()).isEqualTo("INCREASE_BID");
    assertThat(goodSuggestion.bidFactor()).isEqualByComparingTo("1.5");

    // 🔴 样本不足 ⇒ **不可执行**（不照做）
    var thinSuggestion =
        result.items().stream()
            .filter(s -> s.dimensionValue().equals("THIN"))
            .findFirst()
            .orElseThrow();
    assertThat(thinSuggestion.actionable()).isFalse();
    assertThat(thinSuggestion.note()).contains("样本不足");
    assertThat(result.actionableCount()).isLessThan(result.count());
  }

  @Test
  @DisplayName("14.1.3.2 调整建议：维度内差异过大 ⇒ 建议收窄到表现好的取值")
  void suggestionsDetectDimensionSpread() {
    String shop = seedShop(a, "AT-AMZ-6");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-6", "差异活动");

    var best =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "BEST", "geo", "BEST", null, null, null, "最好")));
    var worst =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "WORST", "geo", "WORST", null, null, null, "最差")));

    as(actorA, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingMetricBatchRequest(
                List.of(
                    new AdTargetingMetricEntry(
                        best.id(), DAY_1, 1000L, 100L, 10,
                        new BigDecimal("20.0000"), new BigDecimal("200.0000"), "USD", null, ""),
                    new AdTargetingMetricEntry(
                        worst.id(), DAY_1, 1000L, 100L, 2,
                        new BigDecimal("100.0000"), new BigDecimal("50.0000"), "USD", null, "")),
                "batch-at-6", "PLATFORM", "src-6", "差异数据")));

    var result =
        as(actorA, "channel.operation.read",
            () -> service.suggestions(null, null, "GEO", FROM, TO));

    var spread =
        result.items().stream()
            .filter(s -> s.ruleId().equals("DIMENSION_SPREAD"))
            .findFirst()
            .orElseThrow();
    assertThat(spread.dimensionValue()).isEqualTo("WORST");
    assertThat(spread.action()).isEqualTo("NARROW");
    assertThat(spread.evidence()).anyMatch(e -> e.contains("BEST"));
    assertThat(spread.evidence()).anyMatch(e -> e.contains("倍"));
  }

  // ==================================================================
  // 7. 🔴 收窄投放
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 收窄投放：没有证据 ⇒ 409；有浪费证据 ⇒ 落库 + 留痕 + 标待回写")
  void narrowRequiresEvidence() {
    String shop = seedShop(a, "AT-AMZ-7");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-7", "收窄活动");

    var setting =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "WASTE", "geo", "WASTE", null, null, null, "待收窄")));

    // 🔴 证据不足 ⇒ 拒绝（409）
    var thrown =
        catchThrowable(
            () ->
                as(actorA, "channel.operation.update",
                    () -> service.narrow(setting.id().toString(), new AdTargetingNarrowRequest("想收窄", null, null))));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("证据不足");

    // 🔴 补上「花了钱零转化」的证据 ⇒ 收窄成功
    as(actorA, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingMetricBatchRequest(
                List.of(
                    new AdTargetingMetricEntry(
                        setting.id(), DAY_1, 1000L, 80L, 0,
                        new BigDecimal("150.0000"), new BigDecimal("0.0000"), "USD", null, "")),
                "batch-at-7", "PLATFORM", "src-7", "浪费数据")));

    var narrowed =
        as(actorA, "channel.operation.update",
            () -> service.narrow(setting.id().toString(), new AdTargetingNarrowRequest("无效定位", null, null)));
    assertThat(narrowed.status()).isEqualTo("PAUSED");
    assertThat(narrowed.pendingPlatformWriteback()).isTrue();
    assertThat(narrowed.evidence()).anyMatch(e -> e.contains("150.00"));

    // 🔴 留痕里有 NARROW_TARGETING + 依据
    var ops =
        as(actorA, "channel.operation.read",
            () -> service.operations(setting.id().toString(), 0, 50));
    assertThat(ops.items())
        .extracting(AdTargetingOperationRow::operation)
        .contains("NARROW_TARGETING");
  }

  // ==================================================================
  // 8. 🔴 时段画像（供下游 14.2.1.2 分时策略）
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 时段画像：窗口 + 小时数（NUMERIC(19,6)）+ 区间聚合，供下游分时策略复用")
  void daypartProfileExposesWindow() {
    String shop = seedShop(a, "AT-AMZ-8");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-8", "时段活动");

    var daypart =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "DAYPART", "22:00-02:00", "schedule", "22:00-02:00",
                null, null, null, "夜间时段")));

    as(actorA, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingMetricBatchRequest(
                List.of(
                    new AdTargetingMetricEntry(
                        daypart.id(), DAY_1, 500L, 40L, 3,
                        new BigDecimal("60.0000"), new BigDecimal("180.0000"), "USD", null, "")),
                "batch-at-8", "PLATFORM", "src-8", "时段数据")));

    var profileResult =
        as(actorA, "channel.operation.read",
            () -> service.daypartProfile(null, null, FROM, TO));
    assertThat(profileResult.items()).hasSize(1);
    var row = profileResult.items().get(0);
    assertThat(row.startHour()).isEqualTo(22);
    assertThat(row.endHour()).isEqualTo(2);
    assertThat(row.crossesMidnight()).isTrue();
    assertThat(row.hours()).isEqualByComparingTo("4.000000");
    assertThat(row.spend()).isEqualByComparingTo("60.0000");
    assertThat(row.acos()).isEqualByComparingTo("0.333333");
    assertThat(profileResult.ownerModule()).contains("14.2.1.2");
    assertThat(profileResult.note()).contains("不是按小时");
  }

  // ==================================================================
  // 9. HTTP 端点（真路由 + 真状态码）
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 HTTP：端点挂在 /channel/ad-targetings 之下；跨租户与不存在返回逐字相同的 404")
  void httpEndpointsWork() throws Exception {
    String shop = seedShop(a, "AT-AMZ-9");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId = seedCampaign(actorA, shop, profile, "EXT-AT-9", "HTTP 活动");
    var setting =
        as(actorA, "channel.operation.create",
            () -> service.create(new AdTargetingCreateRequest(
                campaignId, null, "GEO", "US", "geo", "US", null, null, null, "HTTP 定位")));

    http.perform(get("/channel/ad-targetings/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(10));

    http.perform(get("/channel/ad-targetings").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].dimensionValue").value("US"));

    http.perform(get("/channel/ad-targetings/effects").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    http.perform(get("/channel/ad-targetings/suggestions").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));

    http.perform(get("/channel/ad-targetings/daypart-profile").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    // 🔴 不存在的 id ⇒ 404
    http.perform(get("/channel/ad-targetings/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 🔴 无会话 ⇒ 401
    http.perform(get("/channel/ad-targetings")).andExpect(status().isUnauthorized());
  }
}
